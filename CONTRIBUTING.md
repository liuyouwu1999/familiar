# 提交信息规范(Commit Convention)

本仓库所有提交(包括 AI 会话代为提交)必须遵守本规范。`scripts/hooks/commit-msg` 钩子会机械拦截违规提交。

## 格式

```
type(可选scope): 中文标题,≤50字

正文:分段写透技术细节,中文。
```

- 标题行与正文之间空一行
- 标题用「做了什么」的动宾短语,不加句号
- 细节、动机、测试情况、取舍理由全部放正文,标题不塞细节

## type 集合

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `refactor` | 重构(不改行为) |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `docs` | 文档 |
| `build` | 构建脚本/依赖 |
| `chore` | 杂项(不改源码、不改测试) |

scope 按功能域:如 `pet`(桌宠)、`profile`(学习画像)、`roundtable`(圆桌)、`review`(复习)、`classroom`、`mcp`、`chatroom`。可省略。

## 隐私黑名单(硬性,钩子强制)

提交信息中**禁止出现**:

1. **内部任务编号**:`Task N`、`评审修复(Task N)`、`P N 任务X`、`终审Important` 之类的任务管理痕迹
2. **手机品牌与型号**:小米、红米、华为、荣耀、OPPO、vivo、一加、魅族、三星、Galaxy、Pixel、iPhone 等

设备相关的实测表述一律用中性词:「真机实测」「模拟器实测」「装机对比」。

反例 → 正例:

| ❌ 反例 | ✅ 正例 |
|--------|--------|
| `评审修复(Task 6):①…②…` | `fix(roundtable): 评审修复,双 header 与下调门两处`(细节进正文) |
| `大肥鱼动作扩充(P6 任务4,…)…` | `feat(pet): 大肥鱼动作扩充,新增九个表演动作` |
| `小米真机实测发现` | `真机实测发现` |
| `终审Important修复:…` | `fix(review): 复习双入口先拉前台再导航` |

## 正文写法

- 把「为什么改、怎么改的、验证方式」写清楚,宁可详细
- 长消息用 `-` 列表分段,一段一个主题
- 引用代码位置用 `文件:行号`(如 `SkillHost:394-396`)

## 本机启用(每个 clone 各执行一次)

```bash
git config commit.template .gitmessage     # 写提交时给格式提示
git config core.hooksPath scripts/hooks    # 启用规范拦截钩子
```

钩子只拦提交信息,不改内容;确有例外需绕过时用 `git commit --no-verify`,但仅限本规范自身调整类提交。
