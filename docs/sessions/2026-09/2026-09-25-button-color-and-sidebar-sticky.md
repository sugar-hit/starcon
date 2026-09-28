# 2026-09-25 — Button 颜色 + 侧栏 sticky 修复

## 用户反馈

> 修复2个问题：左侧的菜单会滑动走；实际上他应该是单独滑动的，且如果没有超过视界是不用滑动的。其次button的颜色有严重的问题，看不到。

## 根因 & 修复

### 1. Button 颜色看不到 — twMerge text-* 冲突

**根因**: cva 把 base 类（含 `text-btn-14`）+ variant `default`（含 `text-primary-foreground`）+ size `default`（含 `text-btn-14`）拼在一起。
twMerge 默认把所有 `text-*` 当作颜色类同组冲突，**最后出现的胜出** → size 的 `text-btn-14` 覆盖了 variant 的 `text-primary-foreground` → 黑底黑字。

**证据**: Button 5 (`size="icon"` 不含 `text-btn-14`) **保留**了 `text-primary-foreground`，其他都丢。HTML 验证渲染输出对比一致。

**修复** (`packages/ui/src/components/ui/button.tsx`):
- 删除 size 变体里冗余的 `text-btn-14`
- base 已经提供 font-size，size 只控制 height/padding
- 注释里写明"不要在 size 里重复写"避免后人复现

**验证** (Playwright):
- 主操作 button: `color: rgb(255,255,255)`, `bg: rgb(10,10,10)` ✓
- 所有 variant 都正确显示文字

### 2. 侧栏滚动走 — sticky + overflow-y-auto

**根因**: `<aside>` 用 `flex-1` 在 `flex min-h-screen` 容器里 → 高度跟主区一起被撑高，主区滚动时侧栏跟着走。

**修复** (`packages/ui/src/components/layout/app-shell.tsx`):
```html
<aside class="hidden md:sticky md:top-0 md:h-screen md:flex md:w-60 md:shrink-0 md:flex-col md:border-r md:border-sidebar-border md:overflow-y-auto md:self-start">
```
- `md:sticky md:top-0` — 固定在视窗顶部
- `md:h-screen` — 高度 = 视窗
- `md:overflow-y-auto` — **只有内容溢出才出滚动条**（用户硬要求）
- `md:self-start` — flex 容器里 sticky 必需（否则会被 align-items: stretch 拉伸失败）

**验证** (Playwright):
- 滚动 0 / 2000 / bottom 三档，sidebar boundingBox.y 恒为 0 ✓
- `scrollHeight=900 clientHeight=900` → 无溢出 → 无滚动条 ✓

## 提交

```
ac4ceb2 fix(ui): button text color stripped by twMerge text-* collision
1060875 fix(ui): sidebar should be sticky + only overflow when content exceeds viewport
6589c6b feat(web): logo v3 — multi-layer geometric mark (gradient + network + compass + star)
```

## 待办（用户没要求但可能下一步）

- [ ] 把这个 twMerge 教训写进 `packages/ui/CONTRIBUTING.md` 或在 preset 加注释：自定义 fontSize 别用 `text-` 前缀以免被 twMerge 误判为颜色类
- [ ] Button 现在缺 unit test（snapshot + 各 variant 渲染 className 验证）— 防止未来 regression
- [ ] 继续迭代 Logo（v3 mark + 副标"跨境电商 AI ERP"）— 用户没明确说是否接受
- [ ] 移动端 hamburger 在 414px Chrome headless 不渲染的怪癖需真机验证