r"""桌宠皮肤 clip 连贯性扫描（素材体检，配合 normalize_sleep_frames.py 使用）。

对每个 clip 的每对相邻帧算三个不连贯信号：
- best-shift IoU：整体剪影在平移搜索对齐后的重叠率——低 = 姿势断裂/瞬移/
  尺度跳变（已知坏样本：旧 sleep_4→5 ≈ 0.75；已知好样本：idle 对 ≈ 0.9+）；
- 质心位移 / 角色高：剪影重心的相对跳变（瞬移检测）；
- 包围盒宽跳变 %：尺寸毛刺（粗粒度，姿势张收也会推高，只作参考）。

阈值来自 idle（好）与修复前 sleep（坏）的标定：IoU<0.70、质心>0.18、宽跳>15%。
注意：低 IoU 不必然是 bug——转身/蹲跳类动作本来就低重叠，flag 只代表"值得
肉眼复核"（--sheet 参数出拼图）。

用法：python scripts/scan_clip_continuity.py [skin] [--all] [--sheet CLIP]
      默认只打印超阈值帧对 + 各 clip 最差一对；--all 全量打印；
      --sheet 输出该 clip 的带序号拼图到 %TEMP%。
依赖：Pillow + numpy。蓝橙几何相同，扫一套即可。
"""

import json
import os
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CANVAS = 512
DS = 2
SHIFT_RNG = 60   # 全分辨率平移搜索半径
SHIFT_STEP = 4   # 降采样域步长
ALPHA_THR = 60
IOU_FLAG = 0.70
CENTROID_FLAG = 0.18
WIDTH_FLAG = 15.0


def body_mask(im):
    a = np.array(im)[:, :, 3] > ALPHA_THR
    ys, xs = np.where(a)
    return a[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1]


def best_shift_iou(m1, m2):
    """平移搜索下的最大剪影 IoU（降采样域）。"""
    rng, step = SHIFT_RNG // DS, SHIFT_STEP
    H = max(m1.shape[0], m2.shape[0]) + 2 * rng
    W = max(m1.shape[1], m2.shape[1]) + 2 * rng
    p1 = np.zeros((H, W), bool)
    p1[rng : rng + m1.shape[0], rng : rng + m1.shape[1]] = m1
    best = 0.0
    for dy in range(0, 2 * rng, step):
        for dx in range(0, 2 * rng, step):
            y1, x1 = min(dy + m2.shape[0], H), min(dx + m2.shape[1], W)
            sub = p1[dy:y1, dx:x1]
            mm = m2[: y1 - dy, : x1 - dx]
            union = (sub | mm).sum()
            if union:
                v = (sub & mm).sum() / union
                if v > best:
                    best = v
    return best


def centroid(m):
    ys, xs = np.nonzero(m)
    return xs.mean(), ys.mean()


def scan_clip(name, clip, frames):
    pairs = []
    masks = [body_mask(im) for im in frames]
    widths = [m.shape[1] for m in masks]
    heights = [m.shape[0] for m in masks]
    cents = [centroid(m) for m in masks]
    for i in range(1, len(frames)):
        iou = best_shift_iou(masks[i - 1], masks[i])
        cd = np.hypot(cents[i][0] - cents[i - 1][0], cents[i][1] - cents[i - 1][1])
        cd_rel = cd / max(heights[i], 1)   # 已含 1/DS 缩放：分子分母同域，比值不变
        wj = 100.0 * abs(widths[i] - widths[i - 1]) / max(widths[i - 1], 1)
        pairs.append((i - 1, iou, cd_rel, wj))
    return pairs


def main():
    skin = "blue"
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        skin = args[0]
    base = os.path.join(REPO, "app", "src", "main", "assets", "pets", skin)
    with open(os.path.join(base, "pet.json"), encoding="utf-8") as f:
        clips = json.load(f)["clips"]

    sheets_wanted = [a[len("--sheet=") :] for a in sys.argv[1:] if a.startswith("--sheet=")]
    verbose = "--all" in sys.argv[1:]

    for name, clip in clips.items():
        frames = [Image.open(os.path.join(base, fn)).convert("RGBA") for fn, _ in clip["frames"]]
        if len(frames) < 2:
            continue
        pairs = scan_clip(name, clip, frames)
        worst = min(pairs, key=lambda p: p[1])
        flagged = [p for p in pairs if p[1] < IOU_FLAG or p[2] > CENTROID_FLAG or p[3] > WIDTH_FLAG]
        if verbose:
            print(f"--- {name} 全量 ---")
            for i, iou, cd, wj in pairs:
                mark = " <FLAG" if (iou, cd, wj) and (iou < IOU_FLAG or cd > CENTROID_FLAG or wj > WIDTH_FLAG) else ""
                print("  %2d->%2d  IoU=%.3f  质心=%.3f  宽跳=%4.1f%%%s" % (i, i + 1, iou, cd, wj, mark))
        elif not flagged:
            print("%-7s 对数=%2d  最差 IoU=%.3f(%d->%d)  — 无 flag" % (name, len(pairs), worst[1], worst[0], worst[0] + 1))
        else:
            print("%-7s 对数=%2d  FLAG %d 对:" % (name, len(pairs), len(flagged)))
            for i, iou, cd, wj in flagged:
                print("    %2d->%2d  IoU=%.3f  质心=%.3f  宽跳=%4.1f%%" % (i, i + 1, iou, cd, wj))
        if name in sheets_wanted:
            cell = 192
            cols = 8
            rows = (len(frames) + cols - 1) // cols
            sheet = Image.new("RGBA", (cell * cols, cell * rows), (255, 255, 255, 255))
            dr = ImageDraw.Draw(sheet)
            for i, im in enumerate(frames):
                small = im.resize((cell, cell), Image.Resampling.LANCZOS)
                x, y = (i % cols) * cell, (i // cols) * cell
                sheet.paste(small, (x, y), small)
                dr.rectangle([x, y, x + cell - 1, y + cell - 1], outline=(200, 200, 200, 255))
                dr.text((x + 5, y + 3), str(i), fill=(255, 0, 0, 255))
            out = os.path.join(tempfile.gettempdir(), "clip_%s_%s.png" % (skin, name))
            sheet.save(out)
            print("  拼图:", out)


if __name__ == "__main__":
    main()
