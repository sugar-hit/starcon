# 2026-09-28: 沉浸式登录页

## 摘要

按照用户要求，将登录页改为全屏图片背景、左侧 Slogan、右侧真实登录框。手机上下排列，主体独立滚动，保持所有表单控件可达。

## 决策 / 结论

- 左侧文案：「让跨境生意，有序抵达全球。」沿用现有品牌 Logo；右侧为高对比浅色表单。
- 背景为内置 imagegen 生成的蓝调晨曦港口照片。源图未直接交付浏览器，使用本地 WebP：1600px / 86,570 字节、1024px / 44,618 字节、640px / 20,484 字节。
- 静态导入产生带内容哈希的资源路径，浏览器 picture 按屏幕选择图片；手机仅取小图。没有远程图库依赖、运行时图片转码或视频背景。
- 图片高优先级下载、异步解码，登录表单不等待图片；图片被延迟或请求失败时，深蓝底色和遮罩仍保证文案与操作可用。
- 768px 平板使用均衡分栏，修正截图验收发现的 Slogan 挤成四行问题。

## 文件清单

- `apps/web/src/components/iam/login-scene.tsx`
- `apps/web/src/components/iam/identity-shell.tsx`
- `apps/web/src/assets/login/harbor-{desktop,tablet,mobile}.webp`
- `tests/e2e/login-design.spec.ts`
- `docs/design/visual-spec.md`

## 图片生成记录

使用内置 imagegen（非 CLI）。最终提示词：

> Use case: photorealistic-natural. Asset type: immersive background photograph for Starcon enterprise cross-border commerce ERP sign-in page. Create a cinematic ultra-wide 16:9 aerial photograph of a modern international container port and calm ocean at blue hour before sunrise. A few elegant gantry cranes and container terminal lights at the lower left and bottom, a distant cargo vessel, quiet blue water and atmospheric horizon occupying most of the scene. Restrained midnight navy, slate blue, faint warm ivory dawn light. Premium editorial architectural photography, realistic and calm, subtle detail, no futuristic CGI. Composition: leave the upper left and center-left uncluttered dark negative space for a large white Chinese slogan added by HTML; right half is calm dark ocean to sit behind a white login panel. No text, logos, watermark, maps, UI, typography, people or overlays. Landscape 16:9.

## 验证

- lint、typecheck、41 项单元测试与 `pnpm build` 生产构建通过。
- IAM 真实登录与新视觉验收共 11 项通过；平板调整后的 6 项视觉/加载回归再次通过。
- 覆盖 320 / 390 / 768 / 1440 / 1920px，无页面横向溢出，Slogan 两行、表单可达、同源图片请求及单图体积预算；另测背景请求阻塞和失败时表单可用。
- 人工查看 1440、768、390px 截图，输出在 `work/login-design-{width}.png`。

## Trade-off / 后续

使用轻量本地静态背景换取稳定首屏，不引入轮播和动效。画面做装饰使用，语义文案全部用 HTML，图片不重复向辅助技术播报。当前无新增后端行为，原认证、租户选择和错误处理保留。
