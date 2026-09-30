# 2026-09-30: 工作区 Tab 溢出视觉修复

## 摘要

修复工作区页签较多时暴露浏览器原生横向滚动条的问题。页签仍支持触控板、滚轮和触摸滚动，但以左右边缘渐隐表达隐藏内容，并保持当前页签自动进入可视区。

## 决策 / 结论

- 隐藏的只是滚动条外观，不改变原生滚动容器及其输入能力。
- 以两侧渐隐提示是否还有不可见页签；提示依据真实滚动位置和容器尺寸动态计算。
- 只有左侧业务 Tab 组进入滚动容器；右侧账户 Tab 组和切换器固定在栏尾。
- 保留尾部左右按钮“切换当前分组相邻 Tab”的既有语义，不新增一套滚动按钮。
- 工作台继续固定在最左侧，并显示在渐隐层之上。
- 键盘方向切换、关闭左侧/右侧和拖拽排序均限定在当前分组内。

## 涉及文件

- `packages/ui/src/components/layout/workspace-tabs.tsx`
- `packages/ui/src/lib/workspace-tab-overflow.ts`
- `packages/ui/src/lib/workspace-tab-overflow.test.ts`
- `packages/ui/src/styles/tokens.css`
- `docs/design/visual-spec.md`

## 关键 trade-off / 讨论

相较自绘可拖动滚动条，边缘渐隐不占用 Tab 栏的垂直空间，也不会引入额外的拖动状态和无障碍语义；相较只隐藏滚动条，渐隐仍能向用户说明左右还有内容。

## 后续待办

无。

## 关联

- `docs/design/visual-spec.md`
