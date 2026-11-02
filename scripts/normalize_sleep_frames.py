r"""归一化桌宠 sleep 帧的角色尺度（一次性素材修复，2026-11）。

症状：久置打盹时角色"一会大一会小"。根因在素材：sleep_0..15 分批生成，
角色在 512×512 画布里的绘制比例不一致（相邻帧如 sleep_4→5 宽度 325→268，
±11% 跳变），脚底落地线也漂移 30px；窗口代码与帧画布尺寸均无问题。

方法（两处已知估计器的失败模式都不踩）：
- 不按"对固定参考帧的 IoU 最优尺度"修——低头蜷缩的透视缩显小会被误放大
  （f15 被估到 1.25，实际是合法的低头前缩）；
- 不按"bbox 宽度比例"修——甩发/张臂的姿势噪声会顶替真实尺度（f4 甩发被
  高估到 1.21）。
- 改用相邻帧链式解算：相邻帧姿态相近，逐对 IoU 最大化估相对尺度后链成
  全序列轨迹；三点中值+均值平滑出目标轨迹——保留"坐下蜷缩渐小"的整体
  叙事弧（含打哈欠的单拍收束），只抹掉单帧毛刺；校正系数钳 [0.90, 1.12]。
- 缩放后按 alpha 包围盒底边对齐中位落地线，消除上下浮沉。

用法：python scripts/normalize_sleep_frames.py [clip] [--clamp LO,HI] [--dry-run]
      clip 默认 sleep；--clamp 校正系数上下限（默认 0.90,1.12；动作幅度大的
      clip 如 sneak 需放宽下限，缩小时画布无出界风险）。
      就地处理 blue/orange 全部帧；--dry-run 只打印前后对照表不回写。
输出：处理前后逐帧尺寸表（相邻帧跳变 %）到 stdout；同名回写 webp。
依赖：Pillow + numpy；蓝橙几何相同但各自独立解算，不共享系数。
"""

import json
import os
import sys

import numpy as np
from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKINS = ("blue", "orange")
_ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
CLIP = _ARGS[0] if _ARGS else "sleep"
CANVAS = 512
SCALE_GRID = np.round(np.arange(0.90, 1.1001, 0.01), 3)
_CLAMP_ARG = next((a[len("--clamp="):] for a in sys.argv[1:] if a.startswith("--clamp=")), None)
CORR_CLAMP = tuple(float(v) for v in _CLAMP_ARG.split(",")) if _CLAMP_ARG else (0.90, 1.12)
ALPHA_THR = 60
# 尺度解算用 2× 降采样头部掩码（平移搜索 61×61 网格 × 21 档缩放，全分辨率太慢）
DS = 2
SHIFT_RNG = 60
SHIFT_STEP = 4  # 降采样坐标下 ±60 步 4 ≈ 全分辨率 ±30 步 2


def load_frames(paths):
    frames = [Image.open(p).convert("RGBA") for p in paths]
    heads = []
    widths = []
    for im in frames:
        a = np.array(im)[:, :, 3] > ALPHA_THR
        ys, xs = np.where(a)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        widths.append(x1 - x0)
        head = a[y0 : y0 + int((y1 - y0) * 0.35), x0:x1]
        small = Image.fromarray(head.astype(np.uint8) * 255).resize(
            (head.shape[1] // DS, head.shape[0] // DS), Image.Resampling.LANCZOS
        )
        heads.append(np.array(small) > 127)
    return frames, heads, widths


def best_iou(ref, mask):
    """平移搜索下最大 IoU（不带缩放；缩放在外层网格）。坐标为降采样域。"""
    rng, step = SHIFT_RNG // DS, SHIFT_STEP
    H = max(ref.shape[0], mask.shape[0]) + 2 * rng
    W = max(ref.shape[1], mask.shape[1]) + 2 * rng
    padded = np.zeros((H, W), bool)
    padded[rng : rng + ref.shape[0], rng : rng + ref.shape[1]] = ref
    best = 0.0
    for dy in range(0, 2 * rng, step):
        for dx in range(0, 2 * rng, step):
            y1, x1 = min(dy + mask.shape[0], H), min(dx + mask.shape[1], W)
            sub = padded[dy:y1, dx:x1]
            m = mask[: y1 - dy, : x1 - dx]
            inter = (sub & m).sum()
            union = (sub | m).sum()
            if union:
                v = inter / union
                if v > best:
                    best = v
    return best


def rel_scale(head_prev, head_cur):
    """s：把当前帧头部放大 s 倍能与前一帧头部最吻合 → s>1 即当前帧偏小。"""
    best = (0.0, 1.0)
    for s in SCALE_GRID:
        w = int(round(head_cur.shape[1] * s))
        h = int(round(head_cur.shape[0] * s))
        m = np.array(
            Image.fromarray(head_cur.astype(np.uint8) * 255).resize((w, h), Image.Resampling.LANCZOS)
        ) > 127
        v = best_iou(head_prev, m)
        if v > best[0]:
            best = (v, float(s))
    return best[1]


def moving(x, fn, win=3):
    """窗长 3 的滑动聚合，端点收缩窗口（不做反射填充，防端点被邻值带偏）。"""
    n = len(x)
    out = []
    for i in range(n):
        lo, hi = max(0, i - win // 2), min(n, i + win // 2 + 1)
        out.append(fn(x[lo:hi]))
    return out


def chain_scales(heads):
    """相邻帧相对尺度 → 链成相对 f0 的尺寸轨迹。"""
    sizes = [1.0]
    for i in range(1, len(heads)):
        s = rel_scale(heads[i - 1], heads[i])
        sizes.append(sizes[-1] / s)
    return sizes


def normalize_frames(paths):
    frames, heads, before_w = load_frames(paths)
    sizes = chain_scales(heads)
    target = moving(moving(sizes, np.median), np.mean)
    corr = [max(CORR_CLAMP[0], min(CORR_CLAMP[1], t / s)) for t, s in zip(target, sizes)]
    corr[0] = 1.0  # 首帧锚定：idle→sleep 衔接处不许动

    out_frames = []
    for im, c in zip(frames, corr):
        if abs(c - 1.0) > 1e-3:
            w = int(round(CANVAS * c))
            scaled = im.resize((w, w), Image.Resampling.LANCZOS)
            canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
            canvas.paste(scaled, ((CANVAS - w) // 2, (CANVAS - w) // 2), scaled)
            im = canvas
        out_frames.append(im)

    # 落地线：缩放后重测 alpha 底边，整体对齐中位数
    pre_bottoms = [int(np.where(np.array(im)[:, :, 3] > ALPHA_THR)[0].max()) for im in out_frames]
    med = float(np.median(pre_bottoms))
    shifted = []
    for im, bottom in zip(out_frames, pre_bottoms):
        dy = int(round(med - bottom))
        if dy != 0:
            canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
            canvas.paste(im, (0, dy), im)
            im = canvas
        shifted.append(im)
    bottoms = [int(np.where(np.array(im)[:, :, 3] > ALPHA_THR)[0].max()) for im in shifted]
    after_w = []
    for im in shifted:
        xs = np.where(np.array(im)[:, :, 3] > ALPHA_THR)[1]
        after_w.append(int(xs.max() - xs.min()))
    return shifted, before_w, after_w, sizes, corr, bottoms


def jumps(ws):
    return [100.0 * abs(b - a) / max(a, 1) for a, b in zip(ws, ws[1:])]


def report(tag, before_w, after_w, sizes, corr, bottoms):
    jb, ja = jumps(before_w), jumps(after_w)
    print(f"--- {tag} ---")
    print("帧序       :", " ".join("%5d" % i for i in range(len(before_w))))
    print("宽 前→后   :", " ".join("%5s" % f"{a}->{b}" for a, b in zip(before_w, after_w)))
    print("跳变% 前   :", " ".join("%5.1f" % v for v in jb), " max=%.1f" % max(jb))
    print("跳变% 后   :", " ".join("%5.1f" % v for v in ja), " max=%.1f" % max(ja))
    print("链式尺寸轨迹:", " ".join("%.3f" % s for s in sizes))
    print("校正系数   :", " ".join("%.3f" % c for c in corr))
    print("底边 px    :", " ".join("%5d" % b for b in bottoms))


def main():
    for skin in SKINS:
        base = os.path.join(REPO, "app", "src", "main", "assets", "pets", skin)
        with open(os.path.join(base, "pet.json"), encoding="utf-8") as f:
            clip = json.load(f)["clips"][CLIP]
        paths = [os.path.join(base, fn) for fn, _ in clip["frames"]]
        shifted, before_w, after_w, sizes, corr, bottoms = normalize_frames(paths)
        report(skin, before_w, after_w, sizes, corr, bottoms)
        if "--dry-run" in sys.argv:
            continue
        for p, im in zip(paths, shifted):
            im.save(p, "WEBP", quality=92, method=6, exact=True)
        print(f"{skin}: {len(paths)} 帧已回写\n")


if __name__ == "__main__":
    main()
