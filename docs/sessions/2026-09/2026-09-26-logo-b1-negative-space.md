# 2026-09-26: B1 Logo 负空间修正

## 摘要 / 决策

对照用户上传的 B1 精修稿，采用推荐 B1-1 的圆润四节点造型。原实现中心为实心且节点超出 viewBox；改为 evenodd 透明镂空，轮廓完整位于画布内。Logo 字标改为 Bold / Regular、自然字距，按大小调整图文间距；修复 brand tone 仍使用黑色 mark 的问题。

## 涉及文件

- packages/ui/src/components/common/logo.tsx
- apps/web/public/logo-mark.svg、logo-mark-dark.svg、logo-mark-brand.svg
- apps/web/public/favicon.svg、logo-lockup.svg、logo-wordmark.svg
- apps/web/public/logo-app-icon-light.svg、logo-app-icon-dark.svg
- docs/design/visual-spec.md §9

## 取舍

直接修改实际共用组件及 SVG，不用位图覆盖。三种配色与 favicon 保持相同轮廓；App Icon 增加内边距。保留 AppShell 现有 64px 头部高度与分隔线对齐。

## 验证与后续

- 浏览器核对横版 SVG：四节点、中央镂空及两行字标正常显示。
- TSX 独立语法检查通过；SVG XML 与 diff 静态检查通过。
- Web build 编译阶段成功，类型检查在原有 styleguide/page.tsx:447 的 possibly undefined 处失败。
- Web/UI typecheck 仍存在 styleguide、section-card、app-shell 及 UI 包 next 模块解析错误。
- Web lint 因缺少 ESLint 配置进入初始化提示，未通过。默认 Prettier 缺少 prettier-plugin-tailwindcss；使用已安装 Prettier 的显式基础选项完成该组件格式化。
- 后续：单独修复项目原有检查配置与类型错误；本次未改动这些无关组件。
