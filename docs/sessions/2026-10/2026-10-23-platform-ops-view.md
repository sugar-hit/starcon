# 17.7.2.2 平台运维视图（阶段 D7 · L3 锚点 17.7.2）

> 会话记录：**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述过程。

## 决策摘要

在 identity 服务内落地 17.7.2.2「平台运维视图」，交付四件：Flyway 迁移 `V326`、jOOQ 显式 SQL 的
`PlatformOpsController`（12 个端点）、前端工作区 `apps/web/src/app/org/platform-ops` + `components/iam/platform-ops-*`、
集成测试 `PlatformOpsIntegrationTest`（16 例，Testcontainers PostgreSQL 16，连接池压到 1）。

三条结构性事实支撑蓝图点名的红线「**跨租户视图只看技术指标不看业务数据**」：

1. **表结构层面**：指标表 / 告警表的**每一个字符型列**都被 CHECK 钉死在技术枚举上
   （`service_code` / `metric_code` / `unit` / `severity` / `source` / `alert_code` / `status`），
   量纲与指标码绑定（RATIO 必须落 0~1、`ONLINE_STATUS` 只允许 0/1）。
   **表里没有任何自由文本列** ⇒ 业务数据在结构上塞不进去。
2. **端点层面**：跨租户读只有 `platform_ops_metric_list` / `platform_ops_alert_list` /
   `platform_ops_alert_total` / `platform_ops_tenant_health` 这几条 **SECURITY DEFINER** 函数，
   函数内部**自己再判一次**平台运营三道闸门（应用层漏判不会让数据漏出去）。
3. **测试层面**：`metricTablesCannotHoldBusinessData()` 逐列断言「每个字符型列都挂着取值域 CHECK、
   列名不是自由文本列名、四张表没有任何金额语义列」。

**「成本」是数量不是金额**：`cost_estimate = Σ(最近一次采样值 × platform_ops_cost_model 权重)`，
单位是「估算单位数量」；不落发票 / 应收 / 付款 / 结算（那是 12 财务域）。

**平台管理员视角**：权限码 `identity.platform_ops.{read,alert,update}` **刻意不授 OWNER**
（与 17.7.1.1 同一先例、与 V53 相反）。闸门是三道：会话租户有 `saas_platform_role` 登记 →
登记的 roleId 正是会话持有的角色且 `scope_mode='ALL'` → 该角色带有所需权限码。
`create_child` 会把 permission 表里**全部**权限码授给子租户 OWNER，只看权限码当场失效，
所以测试实跑断言：普通租户**带着** `identity.platform_ops.*` 权限码照样 403。

## 关键 trade-off

- **要不要给租户开一条读自己指标的口子**（`GET /platform-ops/self/metrics`）？
  蓝图说本模块是平台管理员视角。取舍：**开**，但只开「自己租户」这一条，且走 RLS 保护。
  理由：多租户三断言（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）需要**真实的 HTTP 落点**，
  否则断言只能停在数据库层、验不出「伪造 X-Tenant-Id 头无效」这一条。
  这条路径**不返回**任何别的租户的数据，跨租户那份仍要过三道闸门。
- **跨租户读为什么是 SECURITY DEFINER 函数而不是视图**：视图默认以 owner 权限执行、
  等于绕过 RLS 且闸门只能靠应用层；ADR-0005 说「每次业务事务选定一个明确目标租户」，
  函数可以自己判闸门并在必要时再切到目标租户的上下文里写。写路径（告警确认/闭环）就是
  「先用 `platform_ops_alert_tenant()` 定出目标租户 → 切到那个租户的 RLS 上下文里改」。
- **告警是「技术量 vs 阈值」的比较结果，不是文本**：告警表没有 message/description 列，
  人话由接口层按枚举码拼、前端另有一份同样的映射。
  代价是枚举扩一个码要改三处（迁移 CHECK、Java 映射、前端映射）—— 换来的是结构上不可能混进业务描述。
- **看板指标取「最差值」而不是「最新值」**：跨服务聚合时错误率/积压/延迟/占用取 max，
  可用性与在线状态取 min（有一个服务报离线，租户整体就判为不在线）。
  成本估算另走「租户级最近一次采样」加权，避免同一个指标被多个服务重复计入。
- **没有采样的指标返回 `null` 而不是 0**：补 0 会被读成「指标是 0」，那是把「没数据」说成了事实。
  前端所有格式化函数对 `null` 显示「—」，`healthLevel` 单列 `UNKNOWN`（无数据）不并进「正常」。
- **指标接入面是服务端到服务端的**（`X-Internal-Key`），**刻意不进浏览器 allowlist**：
  放行它等于让任意租户往别的租户写指标；角色登记路径同理（放行等于让任意租户自升平台运营方）。
- **测试隔离**：平台运营身份用**专属子租户所有者**，不用共享 `local-admin`。
  原因：`platform_ops_register_role()` 是「自分配给调用者」形状（与 V323 的
  `saas_platform_register_role` 同形），对共享 owner 做会永久把它的角色数从 1 变成 2，
  别的测试类里「按 `grants.get(0)` 取角色」的写法就会飘（17.7.1.1 在 L2 全量批次里踩过这个坑）。
  本车道另有 `@AfterEach` 哨兵：断言共享 owner 的 `grants` 长度仍为 1 且 `role_assignment` 只有一行。

## 文件清单

| 类型 | 路径 |
| --- | --- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V326__platform_ops_view.sql` |
| 后端 | `backend/services/identity/src/main/java/com/starcon/identity/PlatformOpsController.java` |
| 后端测试 | `backend/services/identity/src/test/java/com/starcon/identity/PlatformOpsIntegrationTest.java` |
| 契约（codegen 产物） | `packages/api-contracts/src/contracts/identity.ts`、`packages/api-contracts/src/identity.openapi.json` |
| 页面 | `apps/web/src/app/org/platform-ops/page.tsx` |
| 前端 | `apps/web/src/components/iam/platform-ops-model.ts`、`platform-ops-workspace.tsx`、`platform-ops-model.test.ts` |
| 前端测试 | `apps/web/src/app/api/iam/[...path]/platform-ops-allowlist.test.ts` |
| 共享文件 | `apps/web/src/lib/api-proxy.ts`（**只新增一条** `services[]` 元素，锚在数组末尾）、`apps/web/src/components/iam/identity-workspace.tsx`（导航入口 + 可见性判定） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（只改 17.7.2.2 那一行） |

## 后续待办

1. **各服务的指标采集器接入**：仓内 `backend/libraries` 下没有 observability / 事件信封库，
   本模块只提供接入面（`POST /identity/platform-ops/ingest/metrics`）与消费面。
   capabilities 里 `UPSTREAM_COLLECTION` 标 `available=false` 并写明归属。
2. **与 17.7.1.2 用量统计的口径联动**（计费口径的告警）：capabilities 里
   `BILLING_ALERTING` 标 `available=false`，归属 17.7.1.2。
3. **告警的外部通道分发**（通知 / 值班 / 外部告警系统）：capabilities 里
   `TENANT_ALERT_SUBSCRIPTION` 标 `available=false`。
4. **迁移到 apps/admin**：apps/admin 目前仍只有空脚手架。
5. **17.7.2.1 的 `capabilities` 里 `PLATFORM_OPS_VIEW` 那一维**：本模块确实交付了跨租户运维视图，
   已把该维度的 `available` / `ownerModule` / `impact` 改为「已交付 / 空 / 说明口径」，
   其余维度一字未动。
