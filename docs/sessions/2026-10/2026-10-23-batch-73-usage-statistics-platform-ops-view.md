# batch-73 —— 用量统计 / 平台运维视图 并入 + 微服务闸死代码分支修复

- 日期：2026-10-23
- 批次号：73（接 batch-72 往后排）
- 调度者：Mavis（pipeline cron tick）
- 集成头：`integration/module-dev`
- 上一轮状态：346/465 已交付；本轮结束 346/465（并入 2 条车道即 +2，起点 344）

---

## 本轮结论速览

| 项 | 结果 |
| --- | --- |
| 台账同步 | 465 个 L4 模块；起点 344 已交付 / 121 未开发 |
| 回收并入 | **2 条**：17.7.1.2 用量统计、17.7.2.2 平台运维视图（零冲突） |
| 工具链修复 | **微服务闸死代码分支**：卷 01「前端聚合」不再被永久误封 |
| 派发 | **4 条**：1.1.1.2 / 1.1.2.3 / 1.1.3.1 / 1.2.1.1（卷 01 首批）—— **四条全部 land 成功**，等下一轮回收并入 |
| 静态预检 | 通过（两条新迁移均无 ADD COLUMN 无 DEFAULT / SET NOT NULL / ADD CONSTRAINT CHECK） |
| L2 批量验证 | 见「L2 结果」一节 |
| 服务缺口 | 卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）= **79 个模块永久排不出** |

---

## 模块 17.7.1.2 用量统计

**这个功能做什么**：平台管理员能看到「每个租户现在用了多少」——
按六个维度（`SHOP_COUNT` / `SKU_COUNT` / `ORDER_VOLUME` / `USER_COUNT` / `API_CALL` / `AI_USAGE`）
与四个周期（`DAY` / `MONTH` / `YEAR` / `TOTAL`）把当前用量物化成周期分桶的统计事实，
于是有了历史与趋势；还能回答「这个租户的用量有没有超套餐上限」「超了没有、结论是什么」。

**技术落地**：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V325__usage_metering_statistics.sql`
  —— **租户级**两张表：`saas_usage_statistic`（周期用量聚合，`tenant_id NOT NULL` + `RLS FORCE isolation`，
  统计事实**不给 DELETE**）+ `saas_usage_collection`（归集批次留痕，只追加，
  触发器挡 UPDATE/DELETE）。**未新增平台级表** —— 套餐定义仍由 17.7.1.1 拥有，
  本模块只做其上的统计层。
- **端点**：`SaasUsageController.java`（`@RequestMapping("/identity")`）9 个 ——
  GET `capabilities` / `options` / `report` / `trend` / `billing-basis` / `capacity` / `coverage`，
  POST `collections`（归集）。
- **页面**：`apps/web/src/app/org/usage/page.tsx` +
  `apps/web/src/components/iam/usage-stat-{model,workspace}.tsx`。
- **测试**：`SaasUsageIntegrationTest.java` 14 例（Testcontainers PostgreSQL 16）+
  `usage-stat-model.test.ts` 19 例 + `usage-allowlist.test.ts` 5 例。

**关键设计决策**：

1. **不重造「登记用量」端点** —— 上游 17.7.1.1 已有，本模块只读 `saas_entitlement_usage`
   （写入所有者仍是 17.7.1.1），新增「归集」把当前用量物化成周期分桶的统计事实。
2. **口径与套餐定义严格一致** —— 六个维度码与四个周期码沿用 17.7.1.1 已定义的那一套
   （DB CHECK 钉死，前端直接引用 `saas-plan-model` 的词表）。
   流量维度桶粒度固定为套餐定义的周期（来源只给月值就不切进日桶）；
   存量维度桶粒度跟随统计周期；上限**实时读** `saas_plan_entitlement`
   （统计表不存上限快照，改上限后历史报表按新上限重算）。
3. **没接通的不假装接通** —— 未登记的维度在统计事实里**没有那一行**（不是一行 0）；
   计费依据用 LEFT JOIN LATERAL 保留该维度、结论 `UNDETERMINED`（判定不了 ≠ 没超限）；
   覆盖视图按维度报 `WIRED` / `PARTIAL` / `NOT_WIRED` / `NO_SUBSCRIPTION`（从真实登记推导）。
4. **不落任何金额** —— 无发票 / 应收 / 付款 / 结算 / 收入确认列，数量一律 `NUMERIC(19,6)`，
   测试用 `information_schema` 断言无金额语义列。
5. **跨租户读只走 SECURITY DEFINER** —— 6 条函数（scope / report / billing / capacity / coverage /
   collections）内部各自再判一次 `saas_platform_gate`；写动作逐租户单独成事务（ADR-0005）。
   同一周期重复归集是**覆盖不是累加**。
6. **不去 `CREATE OR REPLACE` 上游函数** —— 多车道并行时两条车道改同一个函数定义，
   后应用的那条会把前一条的授权分支覆盖掉。权限码沿用
   `identity.subscription.read` / `identity.subscription.update`
   （另起 `identity.usage.*` 会被上游注册函数漏授）。
7. **测试隔离**：平台运营身份用**专属子租户所有者**（不挂共享 `local-admin`），
   `@AfterEach` 哨兵断言共享 owner 仍只有 1 个角色（grants 长度 + role_assignment 行数）——
   这是 batch-72 里 17.7.1.1 污染共享 owner 的同款缺陷，本车道不重犯。
8. **api-proxy 只新增一条独立 `services[]` 元素**（`prefix: 'identity/'`），锚点固定在
   `// 2.4.2.3 映射缺失队列（channel 服务）` 之前，既有条目一字未改；
   首 token「saas-usage」对既有 182 条正则实测零命中。

---

## 模块 17.7.2.2 平台运维视图

**这个功能做什么**：平台运营视角看**技术健康度** —— 各服务在线率、延迟、错误率、告警，
不掺任何业务数据；能按租户维度横向对比，也能追到某条告警的确认与闭环。

**技术落地**：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V326__platform_ops_view.sql`
  —— **租户级**：`platform_ops_metric` / `platform_ops_alert`（`tenant_id NOT NULL` +
  `ENABLE/FORCE RLS isolation`）；**平台级**：`platform_ops_threshold` / `platform_ops_cost_model`
  （无 `tenant_id`、不参与 RLS、只 `GRANT SELECT`）。
- **端点**：`PlatformOpsController.java`，12 个挂在 `/identity/platform-ops` ——
  GET `capabilities` / `overview` / `tenants` / `metrics` / `alerts` / `thresholds`、
  PUT `thresholds/{metricCode}`、POST `alerts/{uuid}/acknowledge` / `alerts/{uuid}/resolve`、
  GET `self/metrics`、POST `ingest/metrics`（内部密钥）、POST `roles`（内部密钥）。
- **页面**：`apps/web/src/app/org/platform-ops/page.tsx` +
  `apps/web/src/components/iam/platform-ops-{model,workspace}.tsx`。
- **测试**：`PlatformOpsIntegrationTest.java` 16 例（Testcontainers PostgreSQL 16，连接池压到 1）+
  `platform-ops-model.test.ts` 25 例 + `platform-ops-allowlist.test.ts`。

**关键设计决策**：

1. 🔴 **「跨租户视图只看技术指标不看业务数据」落成三条结构性事实**（不是靠代码自觉）：
   ① 指标表 / 告警表的**每个字符型列**都被 CHECK 钉死在技术枚举上
   （service_code / metric_code / unit / severity / source / alert_code / status），
   量纲与指标码绑定（RATIO 必落 0~1、ONLINE_STATUS 只 0/1），
   **表里没有任何自由文本列** —— 业务数据在结构上塞不进来
   （测试逐列断言：每个字符列都挂 CHECK、列名不是自由文本名）；
   ② 跨租户读只有 `platform_ops_metric_list` / `platform_ops_alert_list` /
   `platform_ops_alert_total` / `platform_ops_tenant_health` 这几条 SECURITY DEFINER 函数，
   函数内部自己再判一次三道闸门（应用层漏判不会让数据漏出去）；
   ③ 四张表**无任何金额语义列**（测试用 information_schema 断言
   amount/price/fee/balance/tax/currency 一律不存在）。
2. **「成本」是数量不是金额** —— cost_estimate = Σ(最近一次采样值 × `platform_ops_cost_model` 权重)，
   单位是估算单位数量，不落发票/应收/付款/结算。
3. **平台运营域不对租户开放** —— 权限码 `identity.platform_ops.{read,alert,update}` 刻意**不授 OWNER**
   （create_child 会把全部权限码授给子租户 OWNER，只看权限码挡不住 —— 测试实跑：
   普通租户带着权限码照样 403）。
4. **开「只读自己租户」这一条口子**（`GET /platform-ops/self/metrics`）——
   否则多租户三断言只能停在数据库层，验不出「伪造 X-Tenant-Id 头无效」。
   这条路径**不返回**任何别的租户的数据，跨租户那份仍要过三道闸门。
5. **告警真的会响** —— 采样越阈值即产生 OPEN 告警（按 tenant/service/metric 部分唯一索引去重），
   回到阈值以内自动闭环；确认/闭环走「先定出目标租户、再在该租户 RLS 上下文里改」。
6. **指标接入是服务端到服务端** —— `POST /identity/platform-ops/ingest/metrics` 要 `X-Internal-Key`，
   **刻意不进浏览器 allowlist**（放行它等于让任意租户往别的租户写指标）。
7. **api-proxy 在 services[] 数组末尾纯新增一条 identity/ 元素**，
   放行 10 个形状、刻意不放行 8 个形状（含 `platform-ops/ingest/metrics` 与 `platform-ops/roles`）；
   既有 179 条 services[] 正则对本模块全部形状逐条实测零命中，
   改共享文件后已跑全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api/trade` 68 文件 379 例全过。
8. **如实标注未接通** —— 各服务的指标采集器（仓内没有 observability / 事件信封库）、
   与 17.7.1.2 用量计费口径的联动、告警的外部通道分发，
   在 capabilities 里标 `available=false` 并写明 ownerModule + impact。

---

## 🔧 工具链修复：微服务闸的「死代码分支」（本轮最实质的改动）

### 症状

`plan --lanes 4` 返回 **`newlyPlanned: []`** —— 341+ 个未开发模块里一个车道都排不出来。
`serviceBlocked` 列出四个卷：18 platform / 12 finance / 1 「前端聚合」/ 13 reporting。

### 根因（实测取证，不是推测）

卷 01 的「后端主责服务」原文是 **「前端聚合 + 各服务投影」**。
`readVolumeOwners()` 取该列按 `+` 拆开后的**第一项** ⇒ `primary = '前端聚合'`（非空字符串）。

而 `serviceGate()` 里那条**本意就是放行卷 01** 的分支：

```js
} else if (!primary) {
  // 主责不是服务标识符（如卷 01「前端聚合 + 各服务投影」）→ 纯前端聚合层，照排
  verdict = null;
}
```

**永远进不去** —— `primary` 是非空字符串，`!primary` 为 false ⇒ 落到
`serviceInHead('前端聚合')`；而后者的正则 `/^[a-z][a-z0-9_-]*$/` 只认 ASCII 服务标识符，
非 ASCII 一律返回 false ⇒ 卷 01 被判「主责服务不在集成头」⇒ **永久阻塞**。

而卷 01 **根本不需要后端服务** —— 蓝图 01 开头写死：

> **后端归属**：前端聚合层 + 各服务的只读投影（**不新增业务服务**）。

⇒ 那个「前端聚合服务」永远不会存在。batch-54 / 55 / 56 / 57 / 64 / 65 / 68 / 70 / 71 / 72
**十个批次**都把它记成「服务缺口」，等着「把它并进集成头」—— 等一个不存在的东西。

### 修法

1. **闸门**：`serviceGate()` 增加一条可达分支 —— 主责**不是服务标识符**（非 ASCII）时
   判定为「纯前端聚合层 / 跨服务投影」，照排。
2. **上游图**：`readUpstreamMap()` 从「只解 L4 粒度上游」扩成**两张图** ——
   `l4`（`上游 = 11.2.3.1`）与 `vol`（`上游 = 09 订单、07 库存` / `上游 = 02、18`）。
   卷 01 的「关系」字段几乎全是卷粒度写法 ⇒ 原来它的上游图**整卷失明**
   （upstream 恒为空数组 ⇒ `every()` 恒真 ⇒ 一律判「上游已就绪」）。
   没有这一层，闸门一放行就会把「上游是 12 财务（0 交付）的模块」也排出去 ——
   正是微服务闸当初要消灭的那类浪费（一条车道 40 分钟 + 一个 worktree + 一个 Flyway 版本号）。
3. **卷粒度就绪判定**（保守）：只认「**整个上游卷一行都没交付**」这一种硬阻塞。
   卷已交付过一部分 ⇒ 解不出需要的是哪个 L4，可能已经在了 ⇒ **不凭卷号误杀**，放行，
   交给 worker 的「开工前 10 分钟自检」兜底。自引用不算依赖。
4. **serviceBlocked 改全量登记**：原来只在「排到 tryTake 时才撞上闸」的卷上登记 ⇒
   同时被上游挡住的卷（实测卷 13 reporting）整个从报告里消失 ⇒
   下一轮会误判成「服务缺口已清干净，只差上游」。现在对**所有未完成模块**登记，
   永久性服务缺口永远可见。

### 效果

| | 修复前 | 修复后 |
| --- | --- | --- |
| `plan --lanes 4` 可排车道 | **0** | **4**（1.1.1.2 / 1.1.2.3 / 1.1.3.1 / 1.2.1.1） |
| `serviceBlocked` | 卷 18 / 12 / **1** / 13 | 卷 18（24）/ 12（32）/ **13**（23）—— 全是真实缺口 |
| 卷 01 模块数 | 23 个永远排不出 | 23 个进入候选池 |

### 测试

新增 `tools/module-progress/lane-volume-upstream.itest.mjs`（11 条断言全绿）：
前端聚合层照排 / 真服务缺失照挡 / 上游卷 0 交付硬阻塞 / 上游卷部分交付放行 /
自引用不算依赖 / L4 上游仍生效 / 双挡卷仍进 serviceBlocked / 可落地卷不误报 /
serviceBlocked 带原因与样本。

既有 7 个 lane itest 全绿（`lane-service-gate` / `lane-plan-orphan` / `lane-plan-state` /
`lane-critical-path` / `lane-gate` / `lane-merge-abort` / `remote-lane`）。

---

## 本轮派发（4 条车道）

卷 01（工作台与门户）**首次**拿到车道 —— 此前十轮都被那个死代码分支挡在门外。

| 模块 ID | 名称 | 分支 | 迁移 | worktree |
| --- | --- | --- | --- | --- |
| 1.1.1.2 | 异常订单队列 | `feat/1-1-1-2` | `V327` | `.worktrees/1.1.1.2` |
| 1.1.2.3 | 履约健康 | `feat/1-1-2-3` | `V328` | `.worktrees/1.1.2.3` |
| 1.1.3.1 | 库存风险 | `feat/1-1-3-1` | `V329` | `.worktrees/1.1.3.1` |
| 1.2.1.1 | 全局搜索框 | `feat/1-2-1-1` | `V330` | `.worktrees/1.2.1.1` |

四条车道共享两个高风险点，任务书里都做了硬约束：

1. **卷 01 是只读投影层** —— 不新增后端微服务、不跨服务 SQL；
   后端只在**数据归属服务**补只读端点，跨服务聚合在前端做；
   蓝图里写的表名是示意，真实表名要 grep（已核实：`sales_order_status_history`、
   `order_charge`、`stock_bucket`、`inventory_reservation`、`parcel`、`in_transit_event`、
   `buyer_blacklist_entry` / `buyer_blacklist_order_flag`、`listing` 等）；
   蓝图里的 `platform_performance_metric`、`shipment` **在仓内不存在** ⇒ 必须如实标
   `available=false` + ownerModule + impact，不许造表假装有。
2. **`apps/web/src/lib/api-proxy.ts` 是多车道共用的累积白名单** —— 调度者给四条车道
   **指派了互不重叠的插入锚点**（数组最开头 / `identity/` 之前 / `warehouse/` 之前 / 数组末尾，
   相距 600 行以上），各加**一条独立 `services[]` 元素**，既有条目一字未改也不重排；
   改了这个文件就必须跑全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api/trade`。

### 一次操作事故（留档）

第一次 `plan --lanes 4` 调用报了 `fatal: Needed a single revision`（git 瞬时错误），
但它**已经把 4 条车道建出来了**；重跑一次又建了 4 条 ⇒ 一共 8 条。
按并发上限 4，把多出来的 4 条（1.1.1.3 / 1.2.2.1 / 1.4.1.1 / 1.4.2.1）用
`lane.mjs abandon` 退回池子并 `gc` 清掉 worktree，Flyway 版本号随之释放
（`plan` 的版本分配器跳过 `abandoned` 状态的车道）。
⇒ **教训：`plan` 有副作用且可能在报错前已生效，报错后必须先 `lane.mjs status` 核对实际车道数，
再决定要不要重跑**，不能盲目重试。

---

## 静态预检（互相破坏形状）

对本批新迁移（V325 / V326）跑「已知互相破坏形状」扫描：

| 检查 | V325 | V326 |
| --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | 无命中 | 无命中 |
| ② `SET NOT NULL` | 无命中 | 无命中 |
| ③ `ADD CONSTRAINT ... CHECK` | 无命中 | 无命中 |

⇒ **静态预检通过**。两条迁移都是**建新表**（不是对既有表的 ALTER），
不会破坏早先合入车道的 INSERT。

---

## L2 结果：✅ 绿

在集成头上跑**一次**全量（`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1` 绕开 turbo 缓存），
2026-10-23 10:01:51 起、10:55:32 结束。

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | **MVN_EXIT=0** —— BUILD SUCCESS，耗时 50:59 |
| | identity 1:42 / catalog 5:07 / channel 13:55 / warehouse 13:02 / trade 15:51 / crm 1:18 —— 6 个模块全 SUCCESS | |
| | 后端合计 **554 个测试类 / 8974 例 / 0 失败 0 错误 0 跳过** | |
| | （catalog 1981 / channel 2148 / trade 2383 / warehouse 1640 / crm 491 / identity 331） | |
| [2/6] | `pnpm typecheck` / `pnpm lint` / `pnpm test` | **EXIT=0 / 0 / 0** —— 前端 **521 文件 / 7443 例全过** |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | **BUILD_EXIT=0** |
| [4/6] | `sync.mjs --check`（台账 ↔ 蓝图一致性） | **SYNCCHECK_EXIT=0** |
| [5/6] | `merge-union.itest.mjs` + codegen python 单测 | **ITEST_EXIT=0 / CODEGEN_EXIT=0** |
| [6/6] | 契约闸（CI catalog-e2e 同款：重新生成 + 逐字节比对） | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0** |
| 附加 | 强制 typecheck（`--force`） | **FORCED_TYPECHECK_EXIT=0** |

⇒ **L2 GREEN**，无二分定位、无修复重跑。

⚠️ **本轮 L2 耗时 53:47（其中后端 50:59）**，明显长于往轮的 25–40 分钟。
原因是组批模式下 **L2 的全量构建与 4 条车道自己的 L1 构建在同一时段并发**：
4 条车道各自在 worktree 里跑 `mvnw -pl services/X -am test` + 各起 Testcontainers 容器，
实测同时在跑的 `postgres:16-alpine` 容器一度到 **12 个**（`PG_CONTAINER_LIMIT=6` 是**派发时刻**的容量闸，
不是运行期并发上限）。通道数不变时这个重叠是常态 ⇒
**「每批只跑一次全量」的发热峰值没变，但发热时间被拉长了** —— 这是组批协议的真实成本，此前没被量化过。

---

## 本轮 4 条车道的一线结果（车道自报，调度者待下一轮回收验证）

三条已在本轮结束前 land，第四条（1.2.1.1 全局搜索框）也在收尾时刻 land。

| 模块 | 迁移 | 端点 | 落地位置 | L0/L1 |
| --- | --- | --- | --- | --- |
| 1.1.1.2 异常订单队列 | **无**（V327 未动用） | trade：`capabilities` / `options` / 列表 / 明细 | `apps/web/src/app/dashboard/todo/page.tsx` + `components/dashboard/anomaly-order-*` | 后端 19 例 + 前端 21 例 + allowlist 回归 **383 例** 全绿 |
| 1.1.2.3 履约健康 | **无**（V328 未动用） | trade：`/trade/fulfillment/health`（capabilities / summary / red-flags） | `apps/web/src/app/dashboard/fulfillment-health/page.tsx` + `components/dashboard/fulfillment-health-*` | 后端 21 例 + 前端 14 例 + allowlist 回归 **385 例** 全绿 |
| 1.1.3.1 库存风险 | **无**（V329 未动用） | warehouse：`/warehouse/inventory-risk/{capabilities,projection}` | `apps/web/src/app/dashboard/inventory-risk/page.tsx` + `components/dashboard/inventory-risk-*` | 后端 5 例 + 前端 30 例 + allowlist 回归 **382 例** 全绿 |
| 1.2.1.1 全局搜索框 | **V330**（`global_search_recent`，identity，FORCE RLS） | catalog / trade / channel / warehouse 各一组只读 `GET /{service}/global-search` + identity `GET/POST/DELETE /identity/global-search/recent`；**四个业务服务刻意零写端点** | ⌘K 面板（`packages/ui` 加**可选**属性，不注入时行为逐字不变）+ `apps/web/src/app/dashboard/search/page.tsx` + `components/dashboard/global-search-*` | 后端 5 个测试类 **47 例** + 前端 14 例 + allowlist 回归 **200 文件 / 1151 例** 全绿 |

四条车道里 **三条一条迁移都没用**（V327–V329 空出来留给真正需要建表的场景），
只有 1.2.1.1 用了 V330 —— 因为「最近搜索记录」是个人偏好数据，权威副本必须在后端
（前端 localStorage 只能当缓存层），落 identity 与 1.4 个人设置同域，
表带 `tenant_id` + `user_id` + keyword 唯一键 + FORCE RLS。

四条车道都只在自己那一条 `api-proxy.ts` 锚点插了**一条独立 `services[]` 元素**，
既有条目一字未改，且都跑了全量 allowlist 回归（382 / 383 / 385 / 1151 例，
数字递增说明是同一基线上的累积，也说明**四条车道互不覆盖**）。

值得记的五条一线发现：

1. 🔴 **1.2.1.1 挖出一个「生产上恒 403」的潜伏缺陷**：
   `trade.orders.read` / `trade.purchase.read` 这两个权限码，trade 自己的端点早就在 `require`，
   但 identity 的 `permission` 表里**从来没有登记过**；
   而 `role_permission.permission_code` 有指向 `permission(code)` 的**外键**
   ⇒ 没有任何角色可能持有这两个码 ⇒ **订单与采购两个域的端点在生产上一直是 403**。
   1.2.1.1 的 V330 顺带把这两个码补登记了。
   ⇒ **这正是「每张事实表只有一个写入所有者 / 跨服务契约要真跑」这类纪律的价值**：
   一个查搜索的模块，顺手发现了跨两个服务的权限登记断裂。
2. **1.1.2.3 在 `shipment_record`(V127) 里找到了「有效追踪率 / 迟发率」的真实口径** ——
   调度者任务书里判定「这两项没有合法上游、必须标未接通」是**错的**，
   车道自己找到了 `shipment_record` + `shipment_record_tracking_observation` 的真实分母，
   **只有平台 ODR 真的没有上游**（仓内既无 `platform_performance_metric` 表，也无索赔/拒付/差评率事实表）。
   ⇒ 调度者的上游预判偏保守，车道的实测优先。
3. **1.1.1.2 的契约重生成有个真陷阱**：把仓里已提交的 `<service>.openapi.json` 回灌 codegen
   会把 properties 排成字典序，**六份 `contracts/*.ts` 全变 ⇒ 必然并入冲突**。
   正确做法是**各服务用 `OpenApiContractTest` 真实导出后再跑 codegen**。
   三条车道都踩到了并各自绕开了（只保留自己那一个服务的变更，其余 `git checkout` 还原）。
   ⇒ **这条要写进 worker 任务书模板**，否则每个卷 01 车道都会重新踩一遍。
4. **1.1.2.3 的「平均出库时长」没有新建 warehouse 端点** ——
   8.5.3.1 出库时效已有 `/warehouse/outbound-timeliness/report`（既有白名单已放行），
   由它出数、前端合并。**避免两个出库时长口径分叉** —— 这正是 AGENTS.md「每张事实表只有一个写入所有者」的精神。
5. **「客户在 crm」这个假设不成立**：crm 的 `crm_customer_contact.customer_ref` 迁移注释写死
   「本仓没有买家档案（15.3.1.1 客户列表未交付）」，权威主数据是 catalog 的 `party` + `party_role` ⇒
   客户/供应商检索落在 catalog，不落 crm。调度者任务书里的这个指派也是错的，车道按实测纠正了。

---

## 服务缺口：仍然是最大的结构性瓶颈

---

## 服务缺口：仍然是最大的结构性瓶颈

| 卷 | 主责服务 | 未开发模块数 | 状态 |
| --- | --- | --- | --- |
| 12 财务核算 | `finance` | 32 | 服务不在集成头（AGENTS.md 的服务清单里有 finance，但尚未创建） |
| 13 利润与经营分析 | `reporting` | 23 | 服务不在集成头 |
| 18 集成开放与智能自动化 | `platform` | 24 | 服务不在集成头 |
| **合计** | | **79** | **永久排不出** |

⇒ 119 个未开发模块里，79 个卡在服务缺口，17 个卡在 L4 上游未交付，
**可调度的只剩卷 01 的 23 个**。这 23 个本轮排了 4 条，
下一轮还能再排（1.1.1.1 / 1.1.1.3 / 1.2.1.2 / 1.2.2.1 / 1.2.2.2 / 1.4.1.1 / 1.4.1.2 /
1.4.2.1 / 1.4.2.2 等，取决于同批车道的交付情况）。

**结论不变**：**补齐 `finance` / `reporting` / `platform` 三个服务比提高 `--lanes` 划算得多。**
`--lanes` 现在是 4，而实际可调度池只剩卷 01 —— 并发不是瓶颈，服务缺口才是。

---

## 后续待办

1. **补齐 `finance` / `reporting` / `platform` 三个后端服务**（79 个模块的唯一解）——
   这是架构级决定（要不要新建这三个微服务、边界怎么切），不是调度侧能自决的。
2. **卷 01 的剩余 19 个模块**在接下来几轮随同批车道陆续排出；
   1.1.1.3 / 1.2.2.1 / 1.4.1.1 / 1.4.2.1 本轮因并发上限退回池子，下一轮会被重新排上。
3. **卷 01 的上游卷级依赖要持续复查** —— 本轮新增的「上游卷 0 交付 ⇒ 硬阻塞」规则是保守口径，
   随卷 12 / 13 / 18 的交付进度，部分卷 01 模块会重新变得可排（判据会自然放开）。
4. **`session_access` 的 `grants` 聚合无 `ORDER BY`**（V47:167-181）—— batch-72 的遗留项，
   根治需单独评估对全仓 identity 消费者的影响，不能塞进测试修复车道顺手做。
5. **`sync.mjs` 台账证据格截断（35 个模块）** —— 根因在 `tools/module-progress/sync.mjs:192`
   的 `([^|\n]*)`，需「工具链改动 + 批量数据修复」单独一轮做完，并让 L2 的 `sync --check` 重新验证。
6. **`origin/main` 与集成头双向分叉**（batch-72 留档的 58 个冲突文件）——
   main 侧那 6 个「feat: … 4 项能力交付」提交独立创建了与集成头同路径的同名文件 ⇒ add/add 冲突。
   本流水线**不 merge main**（那是同步任务职责），但若人工裁决把 main 并进来，
   33 个 `apps/web` 代码文件会替换掉经过 L2 验证的版本 ⇒ **下轮 L2 极可能因此变红**。
7. **`lane.mjs merge-ready` 生成的合并 commit 只有标题没有正文** —— 协议 5.3 ①(b) 要求正文列本批模块清单，
   脚本写死的是 `merge --no-ff -m "merge(<lane.id>): <lane.name>"`。
   本轮沿用 batch-72 的处理办法：完整模块清单放在 `docs(batch-73)` 这个 commit 的正文里。
8. **`merge-ready` 的 `serviceBlocked` 语义变更需同步到协议文档** ——
   `tools/module-progress/PIPELINE.md` 与 `PIPELINE_CRON_PROMPT.md` 里关于 serviceBlocked 的描述
   现在落后于实现（改成全量登记），下轮补文档。
