# ADR-0002: 多租户隔离采用 Shared Schema + Row-Level Security

- **状态**: Accepted
- **日期**: 2026-09-27
- **决策者**: Architecture Team
- **关联**: [ADR-0001](0001-monorepo-and-modular-monolith.md)、[ADR-0003](0003-backend-runtime-kotlin-spring-boot.md)、[`docs/architecture/multi-tenancy.md`](../architecture/multi-tenancy.md)、[`docs/compliance/`](../compliance/)

> 本 ADR 把 `docs/decisions/README.md` 中早已声明为 Accepted、但文件一直缺失的「多租户：Shared Schema + RLS」正式落盘，并为 `docs/architecture/multi-tenancy.md` 中的编号引用（此前写作 `0006`，与本 ADR 冲突）提供唯一来源。

## 背景与问题

starcon 是多租户 SaaS ERP。隔离模型一旦上线就极难更改：早期按「每租户一库」写的数据访问层，后期改 RLS 要重写全部 SQL；反过来先上 RLS 再想拆库，也要付出代价。因此必须在写第一行业务表之前定死。

同时存在一个必须正视的风险：RLS 依赖应用层正确注入租户上下文。一旦注入逻辑有洞，隔离就形同虚设，而跨租户数据泄漏属于不可逆事故，不是可以靠后续迭代修复的 bug。

## 评估的选项

### 选项 A: 每租户一库（Database per Tenant）

- 优点: 物理隔离最强，单租户故障与负载完全隔离，删租户即删库，合规导出最直接
- 缺点: 租户数增长后连接数与迁移数量爆炸；跨租户运营报表、计费汇总、平台对账几乎无法用 SQL 完成；单租户恢复演练要逐库进行

### 选项 B: 每租户一 Schema

- 优点: 比每租户一库好管理，迁移可按 schema 灰度
- 缺点: 连接数随租户数线性增长；跨租户查询需要跨 schema 拼接，权限模型复杂；收益相比选项 C 并不明显

### 选项 C: Shared Schema + Row-Level Security

- 优点: 单实例服务全部租户，连接池共享，弹性最好；跨租户分析与 BI 查询天然可用；隔离由数据库层强制，绕过应用层也无法越权读取
- 缺点: 隔离正确性完全依赖「租户上下文注入」这一条链路，任何漏洞都是跨租户泄漏；所有业务表必须带 `tenant_id`，跨租户运营场景需要显式白名单

### 选项 D: 每租户一服务实例

- 优点: 完全隔离，可按租户定制部署
- 缺点: 部署爆炸，与 ADR-0001 的模块化单体起步阶段冲突，成本远超收益

## 决策

选 **选项 C: Shared Schema with Row-Level Security**。

## 理由

当前阶段的核心约束是「早期团队、低订单量、但要求零泄漏」。

- 选项 A/B 的隔离强度更高，但代价是把复杂度前移到运维与跨租户分析上，而跨租户运营（内部后台看全租户、订阅计费、平台对账）是 SaaS 的日常动作，不是例外。这两类成本在早期是纯负担。
- 选项 C 把隔离下沉到数据库：即使某个模块写错 SQL 忘了加 `WHERE tenant_id`，RLS 仍然拦截。这是纵深防御，而不是依赖每位工程师的自觉。
- 选项 C 的唯一真实风险是上下文注入，因此该风险被显式列为本 ADR 的强制约束与验收项（见下）。

## 后果

### 正面

- 跨租户运营查询、计费汇总、平台对账可直接用 SQL 完成
- 隔离由 DB 层强制，代码审查压力从「逐条 SQL 检查」降为「检查上下文注入链路」
- 单实例部署，运维面最小，符合 ADR-0001 的模块化单体阶段
- 未来确需物理隔离时，可在 RLS 之上为热点租户叠加独立部署，不推翻本决策

### 负面 / 风险

- **上下文注入是单点故障**：注入缺失或错误等同于跨租户泄漏。必须用集成测试证明，而非靠 code review
- 所有业务表强制 `tenant_id NOT NULL` + RLS policy，新增表是高频重复成本，需要自动化检查
- 运行角色绝不能是 superuser / `BYPASSRLS` / 表 owner，否则 RLS 静默失效
- 连接池存在租户上下文残留风险：连接归还池后若未清理，下一个租户可能读到上一个租户的数据
- 内部运营后台、异步报表、订阅计费三类场景需要绕过 RLS，必须走专用角色 + 应用层白名单，不能靠关策略实现

### 折衷 / 后续待办

- 强制约束（实现时必须满足，缺一不可）：
  - 每张业务表 `tenant_id UUID NOT NULL`，并建索引
  - 开启 `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`，policy 同时写 `USING` 与 `WITH CHECK`
  - 租户上下文经**已认证用户的成员关系**解析得出，不信任客户端任意 `X-Tenant-Id` 头
  - 连接池内以**事务级**上下文（`SET LOCAL`）注入，事务结束自动失效，杜绝连接复用串租户
  - 后台任务与 worker 同样恢复并校验租户上下文
  - 运行角色非 superuser、不带 `BYPASSRLS`
  - CI 扫描迁移文件：新业务表缺 `tenant_id` 或缺 RLS policy 即拒绝合入
- 库存数量扣减使用条件更新或行锁，不做「先读余量再无锁写回」
- [ADR-0003](0003-backend-runtime-kotlin-spring-boot.md) 决定租户上下文在 JVM 服务端的注入实现
- 「何时需要物理隔离」的临界指标（单租户订单占比、独立 SLA、独立团队）另行记录，不在本文预设
