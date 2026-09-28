# 2026-09-28: 应用级 IAM 重设计

## 摘要

用户指出 IAM 错误地位于商品功能下。本轮调研 Odoo、Shopify 和 ERPNext 官方资料，编写 IAM 产品方案，并将真实登录、全局会话、租户切换和组织管理从商品工作区拆出。

## 决策 / 结论

- `/login` 是全应用入口；业务页面共享 `IdentityShell`。公开 `/styleguide` 保留组件预览，不作为真实账户入口。
- `/account` 展示认证身份，`/org` 展示租户概览；成员、角色、部门、子租户管理迁至 `/org/*`，旧 `/products/*` 管理地址只作重定向。
- 顶栏账户浮层连接真实租户、会话和退出，不再提供业务环境中的演示角色切换。
- IAM 请求使用 `/api/iam/*`，共享代理和客户端移至 `src/lib`；应用 cookie 为 `starcon_session`，兼容读取旧 cookie 并在登录/退出时清理旧值。
- 服务端 token 不返回浏览器脚本；保留同源写入检查和白名单。回跳地址只允许应用内部路径。
- 用户账号与租户成员区分；成员管理复用 identity 现有后端、Owner 授权与 RLS，不重写已验证的隔离规则。
- 切换租户重建工作区，登录/切换/退出通过 storage 事件通知其他标签页重新读取身份。多租户登录明确选择，不默选第一项。

## 关键 trade-off

保留四个业务系统导航，IAM 从全局账户入口可达，运营导航中保留组织快捷入口。已有后端 CRUD、账号创建/关联、成员启停和所有者转移继续使用；邮件邀请、找回密码、个人资料写入、MFA/SSO、设备会话管理和审计检索列为后续，绝不以原型冒充。

客户端登录边界先呈现验证状态，再挂载业务界面。原来无 JavaScript、无会话仍显示业务 Tab 的测试已改为验证未认证不暴露工作区；业务 API 仍由后端鉴权，不依赖此展示边界。

## 涉及文件

- `apps/web/src/components/iam/`：统一身份工作区、全局会话和原管理组件迁移。
- `apps/web/src/components/common/fields.tsx`：共享表单组件；商品模块保留导出兼容。
- `apps/web/src/lib/`：同源 API、服务端代理、登录回跳校验。
- `apps/web/src/app/{login,account,org,api/iam}/` 与旧商品管理路由。
- `apps/web/src/components/catalog/catalog-workspace.tsx`：移除内嵌登录、退出、租户切换和组织管理。
- `packages/ui/src/components/layout/app-shell.tsx`：真实账户插槽。
- `packages/ui/src/lib/task-pages.ts`：管理导航归属。
- `backend/services/identity/src/main/java/com/starcon/identity/IdentityController.java`：认证失效提示去除商品语义。
- `tests/e2e/iam.spec.ts`、`tenant-administration.spec.ts` 及共享已认证测试入口。
- `docs/investment/2026-09-28-saas-iam.md`、`docs/product/prds/iam.md`、视觉规范更新。

## 验证

- `pnpm typecheck`、`pnpm lint`、41 项 Vitest 测试通过。
- Java 21 Docker `pnpm test:backend` 通过：identity 8 项、catalog 11 项，包含真实 Testcontainers PostgreSQL 权限与隔离测试。
- 首次完整浏览器回归 60 项中 59 通过，1 项旧未登录 SSR 假设已按新的登录边界修正；后续相关 14 项全部通过。
- 新增多租户/跨标签页/未保存表单补测通过；最终 IAM 与租户管理 6 项通过，包括无商品权限的成员可读组织成员、不能写管理数据或读商品。
- 商品 SPU/属性/SKU 持久化回归通过；账户与导航 320–1920px 检查通过；截图在 `work/iam-{320,768,1440}.png`，已人工查看移动与桌面图。
- 构建首次受沙箱 Google Fonts 网络限制，升级权限后单独重跑 `pnpm build` 成功（35 个静态页面及动态 API 路由）。开发服务与构建不并行，以免共享 Next 缓存冲突。

## 后续待办

- 依产品分期补充邮件邀请/找回密码、个人资料/改密、MFA/SSO、设备与会话管理、审计检索和根租户开通；需要对应后端设计与外部服务配置。
- 测试生成的子租户不可删除，切换验证的子租户已暂停。没有提交或推送。
- 保留进入会话时已有的操作文档、滚动测试和 catalog 最终验证记录修改；本轮仅在滚动测试上增加真实登录入口。

## 关联

[产品方案](../../product/prds/iam.md) · [研究依据](../../investment/2026-09-28-saas-iam.md) · [ADR-0005](../../decisions/0005-tenant-hierarchy-and-page-rbac.md)
