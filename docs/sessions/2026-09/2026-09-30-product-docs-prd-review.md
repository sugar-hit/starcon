# 2026-09-30: 业务设计与 PRD 复审

## 摘要

复审产品文档、18 系统功能蓝图、IAM PRD、相关 ADR、当前导航配置与既有会话记录。建立统一 PRD 治理规范，并把 IAM 从简略功能说明重构为可追踪、可验收的发布基线；同步修正蓝图中的结构错误、过强准入结论、目标态与现状混写及邮件邀请范围冲突。

## 决策 / 结论

- 功能蓝图负责系统全貌、能力归属和上下游关系；PRD 负责单个可交付增量的范围、行为、验收和发布；ADR/架构文档负责技术与边界约束。
- 蓝图的“职责 / 输入 / 输出 / 关系”是设计完整性门槛，不等同于可执行验收标准。PRD 使用稳定 FR/AC 编号建立追踪关系。
- PRD 必须区分当前已实现、本期承诺和后续候选，并声明状态、负责人、目标版本、关联蓝图和 ADR。
- P0–P3 是能力分级口径，不代表全部蓝图模块已逐项定级；实际优先级由路线图和单功能 PRD 确认。
- 交易能力需挂到业务闭环；IAM、合规、门户、集成等横向能力可以挂到明确治理目标，不能因不直接位于交易链路而被排除。
- 当前 IAM 基线仅支持管理员创建本地账号或关联已有账号，不提供邮件邀请、自助激活及凭据生命周期能力。

## 涉及文件

- `docs/product/README.md`
- `docs/product/prds/README.md`
- `docs/product/prds/iam.md`
- `docs/product/capability-blueprint/README.md`
- `docs/product/capability-blueprint/09-order-and-fulfillment.md`
- `docs/product/capability-blueprint/17-org-permission-and-settings.md`
- `docs/sessions/2026-09/2026-09-30-product-docs-prd-review.md`

## 关键 trade-off / 讨论

- 没有把 18 卷蓝图全部改写为 PRD。蓝图约 465 个 L4 模块，是产品地图而不是一批已承诺需求；直接补验收标准会制造虚假范围和优先级。
- IAM PRD 记录已实现基线，但保持 `In Review`，因为产品负责人、正式指标基线和部分安全策略仍待确认；邮件邀请、MFA、SSO、密码生命周期等能力继续明确为范围外，后续分别立项。
- 指标缺少生产基线，因此保留“建议目标”并要求下次立项前补基线，避免把未经测量的数字冒充 SLA。
- 蓝图保留目标态 `/settings/*` 路由，但明确当前组织入口为 `/org` 且迁移需经 PRD，避免静默把规划写成现状。

## 后续待办

- 指定 IAM 产品负责人，确认登录/切换指标基线和成员停用后的会话失效时限。
- 为 D1–D7 每个拟交付闭环分别建立 PRD，不按 18 个系统整卷立项。
- 建立蓝图 L4 → PRD FR → 测试用例的可检索索引；优先覆盖正在实施的 identity、catalog 和 channel。
- 邮件邀请、凭据生命周期、MFA/SSO、设备会话管理分别立项，避免再次混入当前基线。
- 待当前导航稳定后，单独评审 `/org` 与目标态 `/settings/*` 是否需要迁移。

## 关联

- [PRD 规范](../../product/prds/README.md)
- [IAM PRD](../../product/prds/iam.md)
- [系统功能蓝图](../../product/capability-blueprint/README.md)
- [ADR-0002](../../decisions/0002-multi-tenancy-strategy.md)
- [ADR-0005](../../decisions/0005-tenant-hierarchy-and-page-rbac.md)
