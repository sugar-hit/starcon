# 2026-09-30: 业务闭环设计与 PRD 续审

## 摘要

在工作区已有 PRD 整理改动上继续审查，保留原改动和其他未跟踪文件。聚焦订单、库存、售后、IAM 及旧数据流的内部矛盾；新增 D3–D4 可验收闭环提案。未修改业务代码或宣称已通过业务验收。

## 决策 / 结论

- 未知结果与明确失败分开：租户切换未知时暂停操作核验；退款未知保留额度；取消必须与仓库实发协调。
- bucket 可用与汇总可售分开，避免不可售量重复扣减。
- IAM 从“已发布基线”改为待验收要求；新增审计 FR，修复 AC 追踪及停用即时拒绝与开放问题的冲突。
- 数据流按 ADR-0004 重构，去除旧单体参与者、固定中间件和订单号替代事件去重的错误。
- PRD 为 Draft，自营仓及单币种是待确认范围；未更改 ADR，也未批准首发仓型。

## 关键 trade-off

不把 18 卷功能目录全部转成已承诺需求。先为高风险跨域闭环补用户流程、不变量、状态、故障验收和发布门槛；代发及财务政策保留显式评审事项，不替用户决定经营或会计政策。

## 涉及文件

- `docs/product/prds/{README,iam,order-inventory-fulfillment}.md`
- `docs/product/capability-blueprint/{README,07-inventory,09-order-and-fulfillment,11-aftersales-and-returns,12-finance}.md`
- `docs/architecture/data-flow.md`
- `docs/backlog/2026-09-30-business-design-review.md`
- 本会话记录

## 后续待办

见 [集中审查清单](../../backlog/2026-09-30-business-design-review.md) R-01–R-07：确认首发范围，核验 IAM 安全要求，补渠道 PRD，细化代发/财务/多平台库存策略。历史会话中的“已实现”表述属于当时记录，当前需求状态以 PRD 为准。

## 验证

本轮仅修改 Markdown；检查变更文件格式、相对链接及新增 PRD 的 FR/AC 覆盖，不运行与文档变更无关的应用测试。文档检查不能替代 PRD 所要求的业务测试。
