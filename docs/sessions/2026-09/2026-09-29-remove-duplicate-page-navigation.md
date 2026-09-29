# 2026-09-29: 移除页面重复导航

## 摘要

系统检查并移除页面正文中与侧栏二级任务重复的跨路由导航，避免用户误以为这些入口是页面内部 Tab。页面正文的 Tab 仅保留不改变页面层级的局部视图或状态切换。

## 决策 / 结论

- 侧栏二级任务和工作区 Tab 承担跨路由导航，页面正文不得再复制同一组入口。
- 商品工作区顶部功能栏、组织管理顶部导航和通用任务页“相关任务”均属于重复导航，全部移除。
- 设计系统中的空状态、错误、无权限和加载示例仍是同页状态切换，保留内部 Tab。

## 涉及文件

- `apps/web/src/components/catalog/catalog-workspace.tsx`
- `apps/web/src/components/iam/identity-workspace.tsx`
- `packages/ui/src/components/common/task-workspace.tsx`
- `docs/design/visual-spec.md`
- `tests/e2e/navigation-regression.spec.ts`
- `tests/e2e/iam.spec.ts`
- `tests/e2e/tenant-administration.spec.ts`

## 关键 trade-off / 讨论

组织概览仍保留有权限的管理卡片，作为进入管理任务的内容级行动入口；进入具体管理页后不再显示同一组顶部链接。业务任务已有稳定侧栏入口，不额外保留“相关任务”快捷区。

## 后续待办

- 新增业务页面评审时继续区分“跨路由任务导航”和“同页视图切换”，避免再次混用 Tab 视觉。

## 关联

- [视觉规范](../../design/visual-spec.md)
- [账户导航与工作区 Tab](2026-09-28-account-navigation-tab-feedback.md)
