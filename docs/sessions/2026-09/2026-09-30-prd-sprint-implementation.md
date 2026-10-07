# 2026-09-30: PRD 复审与 Sprint 首项开发

## 摘要

用户授权仔细审阅 PRD、修订并按 Sprint 逐功能开始开发。完整阅读当前两份 PRD，核对 ADR-0004/0005、IAM 前后端、已有测试及库存设计入口；保留工作区既有未提交变更，建立七个 Sprint 的依赖顺序，落地 S1-01 租户切换未知结果的前端恢复流程。

## 决策 / 结论

- IAM 组织写入限定 Owner，普通角色只读；平级部门不宣称部门树，本地头像不代表服务端个人资料。
- 对象不存在与对象无权访问不应提供可用于枚举其他租户对象的差异；停用后的新身份核验与在途写入分开验收。
- 库存 PRD 补重复池行汇总、到期竞争、缓冲下限、幂等租户/动作边界、超授权实物隔离、金额容量及单位冻结。
- 先 IAM，再法人/仓库/库存单位及数据范围，再入库、预占、订单、出库取消和实物退货。没有把 18 系统蓝图全部当作已批准 PRD。
- 切换发起即卸载工作区、阻断后续业务请求；明确拒绝恢复原身份，网络错误及服务端异常进入身份核验；不可核验则保持阻断并提供只读重试；401 回到登录。
- API 客户端支持空成功响应；非 JSON 的 401 仍发出全局身份失效事件。

## 涉及文件

- `docs/product/prds/iam.md`
- `docs/product/prds/order-inventory-fulfillment.md`
- `docs/product/prds/README.md`
- `docs/product/README.md`
- `docs/product/sprints/README.md`
- `apps/web/src/components/iam/identity-shell.tsx`
- `apps/web/src/lib/api.ts`
- `apps/web/src/lib/api.test.ts`
- `tests/e2e/iam-recovery.spec.ts`

## 验证

- `pnpm test`：11 文件、64 项通过。
- `pnpm typecheck`、`pnpm lint`：通过。
- 浏览器 4 项通过：响应丢失且核验暂不可用后重试、明确拒绝、会话失效、正常空响应切换；均验证取消不发请求及切换只发一次。
- 浏览器使用接口故障注入，不是后端真实事务或 RLS 验收。独立 3100 测试服务使用测试字体替身；临时配置及服务在 gitignored `work/`，命令为 `pnpm exec playwright test -c work/iam-recovery.config.ts tests/e2e/iam-recovery.spec.ts`。
- 截图 `work/iam-workspace-recovery.png` 已目视检查。
- 常规 `pnpm build` 首次因 Google Fonts DNS 失败，联网重试等待后中止；带测试字体替身的构建通过，36 页面生成成功。未修改生产字体配置，不能将测试构建当作可发布字体验收产物。
- `git diff --check` 通过；未提交、推送或发布，未宣称远端 CI 通过。

## 关键 trade-off / 讨论

- 每次切换统一确认未保存风险，暂不建立覆盖所有表单的 dirty registry。
- 本项主要消除切换未知时旧页面仍可操作的窗口，不宣称已经完成跨标签页并发一致性。
- 没有扩大普通角色的组织写权限，也没有将尚未确认的仓型、金额政策及预占时长设成业务承诺。

## 后续待办

- S1-02：服务端会话版本或等价机制处理两个标签页同时切换、在途请求，以及超时切换晚于身份核验提交的竞态；当前前端门禁不能替代服务端隔离。
- S1-03/04：完整认证与拒绝审计、Owner/撤权及真实 PostgreSQL 隔离回归。
- S2 开始前补最小主数据 PRD；D3/D4 开放业务政策在相应功能前确认。
- 正常网络环境重跑无字体替身的生产构建；完整 IAM AC 未全部验收，PRD 不标 Released。

## 关联

- [Sprint 计划](../../product/sprints/README.md)
- [IAM PRD](../../product/prds/iam.md)
- [订单库存 PRD](../../product/prds/order-inventory-fulfillment.md)
