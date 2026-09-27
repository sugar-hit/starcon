# 2026-09-27: 页签辨识度与创建时机

## 摘要

增强页签边界和选中态，消除当前页面先出现、页签随后由 effect 补建的问题。

## 决策 / 结论

- 当前路由在 Provider 初始化及 pathname 变化的同轮渲染登记；移除展示组件中的异步登记 effect。
- localStorage 恢复合并当前列表，不覆盖当前页签；关闭操作不因同一路由重新登记。
- 蓝色文字、浅蓝背景、描边标识选中项；加大页签高度和关闭按钮点击范围。

## 涉及文件

- packages/ui/src/lib/workspace-tabs.tsx
- packages/ui/src/components/layout/workspace-tabs.tsx
- tests/e2e/navigation-regression.spec.ts
- docs/design/visual-spec.md

## 关键 trade-off / 讨论

已提交 pathname 继续决定选中态。点击菜单可先显示新页签，但加载期间不提前选中，保持正文和选中项一致。历史页签仍需客户端读取 localStorage，当前路由页签不依赖该读取。

用户复核后去掉选中项底部蓝线，页签栏恢复原 background 浅色背景，其余保持不变。

## 验证

- lint、typecheck、生产 build 通过。
- 9 项导航回归通过，含禁用 JavaScript 的直接访问首屏、阻塞目的路由请求时即时创建、刷新恢复及关闭不重开。

## 后续待办

无新增功能待办。保留本轮开始前已有的世界时钟相关未提交改动；仅提交页签相关文件和视觉规范对应段落。

## 关联

- [前次布局修复](2026-09-27-tab-layout-fix.md)
