# Starcon Tab 与布局修复

本地项目：`/Users/tommy/Project/starcon`。预览：http://localhost:3000。

## 已完成

- 已打开菜单切换到既有 Tab；未打开菜单创建一个 Tab，重复打开不重复创建。
- Tab 选中状态从当前已提交的 pathname 推导；移除对 Next 路由 children 的错误缓存，避免标题、选中态和内容错配。
- 右键支持关闭当前、关闭其他、关闭左侧、关闭右侧、关闭全部；移除常驻省略号操作按钮；增加首尾跳转。支持中键关闭、方向键/Home/End、Shift+F10 菜单及拖动排序。
- 批量关闭后导航到右键目标；关闭活动 Tab 回工作台，关闭非活动 Tab 不改变当前页。Logo 与关闭全部清空列表并回工作台，不选中 Tab；桌面与移动端一致。
- 一级导航桌面最小宽 80px、左右 padding 20px；正文与 Tab 栏共享桌面 24px、窄屏 16px 边距。标题不重复添加内边距，顶部间距 16px，正文不再受 1280px 居中容器限制。
- 侧栏和搜索导航移除工作台、Copilot；右上独立 Copilot 使用已有 Sheet，关闭后恢复触发器焦点，路由与 Tab 保持不变。
- 旧 `/copilot` 地址重定向工作台；不再创建 Copilot 路由 Tab。
- localStorage 恢复时过滤无效/非业务路由及重复项；持久化去掉 200ms 延迟，修复快速刷新丢列表。

## 核心状态模型

`tabs` 是有序列表，按业务路径唯一；`activeTabId = tabs.find(tab.href === pathname)?.id ?? null`。URL 为当前页面和高亮的唯一来源。路由改变后自动登记通过直接链接、浏览器历史或搜索进入的业务页；同一 pathname 下的关闭操作不触发重新登记。Copilot 使用独立 `copilotOpen` 布尔值，不写入 tabs。页面内容由 Next 管理，不缓存 ReactNode。

## 本轮修改文件

所有相对路径均以 `/Users/tommy/Project/starcon` 为根：

- `packages/ui/src/lib/workspace-tabs.tsx`：状态、导航、持久化。
- `packages/ui/src/components/layout/workspace-tabs.tsx`：紧凑栏、右键菜单、首尾和键盘操作。
- `packages/ui/src/components/layout/app-shell.tsx`：布局、导航入口、移除错误缓存、Copilot 抽屉。
- `packages/ui/src/components/common/page-header.tsx`：删除重复边距和最大宽度。
- `apps/web/src/app/copilot/page.tsx`：旧路径兼容重定向。
- `docs/design/visual-spec.md`：更新布局与交互契约。
- `tests/e2e/navigation-regression.spec.ts`：新增 12 项回归用例。
- `docs/sessions/2026-09/2026-09-27-tab-layout-fix.md`：会话记录。

保留仓库原有未提交修改，未提交或推送。

## 验证结果

- 新增回归：12/12 通过。遍历 15 个菜单及反向切换；重复打开；五种右键关闭；关闭活动/非活动/最后 Tab；重开；首尾；刷新；Logo；历史返回；Copilot 开关、Escape 和焦点恢复。
- 320、390、768、1440、1920px：标题与 Tab 栏左侧一致，无页面级横向溢出；窄屏导航、Logo 和抽屉通过。
- `pnpm lint`、`pnpm typecheck`、独立 `pnpm build` 通过，构建生成 22 个页面。
- 原有 Tab 测试 1/4 通过，其余 3 项使用订单正文的外链（target=_blank）作为同页导航，导致原页找不到新 Tab。未改写原测试来掩盖问题。
- 原有 design-system 测试 3/8 通过，5 项预期已不存在的指标筛选、订单表格或旧搜索输入名称；相关页面/CommandPalette 未在本轮改动。
- `pnpm test:unit` 找不到单元测试文件，退出码 1；不能声称单元测试通过。
- 首次构建与开发服务共享 .next 导致临时缓存错误；停止开发服务后独立构建成功，已启动构建预览。

## 风险与后续

- 移除不可靠的 keep-alive 后，页面内存中的筛选/表单/滚动状态不保证跨 Tab 保留。后续应通过 URL 或按页面键保存业务状态，不应缓存 Next 路由 children。
- Copilot 沿用现有功能占位内容，尚未接入真实 AI 服务。
- 既有测试需按当前原型维护，全仓 E2E 目前并非全绿；本轮新增导航回归全部通过。
- 旧持久化钉住标记不再阻止关闭全部；当前 UI 不提供钉住操作。
