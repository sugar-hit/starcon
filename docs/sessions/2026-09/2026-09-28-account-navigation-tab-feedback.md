# 2026-09-28: 账户入口与 Tab 即时反馈

## 摘要

修复登录态刷新闪现登录背景、组织权限归属错误、右上角账户入口粗糙以及页面跳转时 Tab 与页面错位四项体验问题。认证恢复改用中性启动态；组织与权限从运营系统导航移出，统一从账户卡片进入；账户卡片按设计令牌重构并支持头像上传/更换；Tab 与新页面（或其加载骨架）在同一路由提交边界出现。账户与组织页使用靠右、可关闭的独立 Tab 组。

## 决策 / 结论

- 登录页视觉只属于确认未登录后的登录表单，认证状态未知时不得加载港口背景。
- 组织、权限和个人资料属于全局账户设置，不归属于开发、运营、物流或仓储任一业务系统。
- 路由 pathname 是 Tab 创建与选中态的唯一真相源。点击导航期间保留旧页面与旧 Tab 的完整配对，只显示细进度反馈；新页面或骨架提交时再同步出现并选中新 Tab。
- 个人资料、组织与权限页面可创建 Tab，但属于右侧独立账户组，不进入任何业务系统菜单；组内 Tab 均可关闭。
- 工作台永远固定且不可关闭：从工作台“关闭其他”会清空全部可关闭 Tab；从业务或账户 Tab“关闭其他”只保留当前 Tab 与工作台，不受左右分组限制。
- Tab 栏尾部切换器以细分隔线与 Tab 视觉分离，不使用额外卡片外框、阴影或常驻底色；它以当前激活 Tab 判断业务/账户分组，前后按钮和键盘方向键都只能在当前组内移动。
- 账户浮层增加登录状态、租户身份、角色/权限摘要和会话边界；租户切换与多租户登录不再使用原生 `select` 或 `window.confirm`，统一改为项目风格列表和确认弹窗。
- 头像按用户 ID 存入浏览器本地存储，支持 JPG、PNG、WebP 与 2 MB 上限；服务端对象存储能力未建立前不伪造跨设备同步承诺。

## 涉及文件

- `apps/web/src/components/iam/identity-shell.tsx`
- `apps/web/src/components/iam/identity-workspace.tsx`
- `apps/web/src/app/loading.tsx`
- `packages/ui/src/lib/nav-config.ts`
- `packages/ui/src/lib/workspace-tabs.tsx`
- `packages/ui/src/components/common/avatar.tsx`
- `tests/e2e/workspace-tabs.spec.ts`
- `tests/e2e/scroll-shell.spec.ts`

## 关键 trade-off / 讨论

未采用“Tab 抢先渲染”，因为这会让旧页面与新 Tab 在明显时间内错配。导航期间维持原有已提交界面并给出克制的进度条；Next.js 可先提交目标页骨架，真实数据继续异步加载。头像当前采用用户维度的本地持久化，避免在没有媒体存储、病毒扫描、裁剪和访问控制方案时把大文件塞入 identity 数据库。

## 后续待办

- 规划用户资料 API 与头像对象存储，补齐跨设备同步、图片裁剪和旧头像清理。
- 业务页面接入真实异步数据后，在局部数据区继续使用语义化骨架，避免整页阻塞。

## 关联

- [应用级 IAM 重构](2026-09-28-application-iam-redesign.md)
- [Tab 渲染时序](2026-09-27-tab-clarity-render-timing.md)
