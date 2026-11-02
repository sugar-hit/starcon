# 15.6.2.1 生命周期与流失预警（crm 服务）—— 交付记录

> 车道：`feat/15-6-2-1`（集成头 `integration/module-dev` @ `41391b61` 为基线）。
> 模块：蓝图 15 卷 **15.6.2.1 生命周期与流失预警**（L3 锚点 15.6.2「复购与留存」下的第一格，同 L3 的 15.6.2.2 购物车与弃单挽回是**另一页**）。
> 阶段：D7。

## 决策摘要

| 项 | 结论 |
| --- | --- |
| 落点 | 后端 `backend/services/crm`（卷 15 客户与客服的归属服务）；前端 `apps/web/src`，页面 `/crm/customer-lifecycle`（客户留存看板） |
| 迁移 | `V436__crm_customer_lifecycle.sql` —— **四张新表**、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键 |
| 阈值口径 | **复购周期阈值是租户数据**（`crm_lifecycle_policy`，可按类目配、带生效区间）。**代码里没有任何「默认 30 天」** |
| 阈值来源四档 | ① 命中类目策略 ② 命中全局兜底策略 ③ 都没命中 ⇒ 用**租户真实订单历史算出来的中位复购间隔**（容差 0 天并如实说明）④ 连基线都算不出 ⇒ **不可判定**，不进预警清单 |
| 「本应复购未复购」 | 距上次购买 > **预期复购间隔 + 容差** ⇒ 超期；同时每行带该客户**本人历史相邻两次购买的间隔中位数**与样本数作为证据（只下一单 ⇒ 样本不足，不是「基线 0 天」） |
| 风险分 | 四个因子（逾期倍数 40 / 最近购买 25 / 购买频次 20 / 价值敞口 15）加权，**因子明细落库**（`risk_factors`），DB 层拒绝空数组 |
| 召回 | **只产出建议**，`suggestion_owner` 写明归属（15.5.2 触达 / 15.6.2.2 弃单挽回）；**本模块一条消息都不发** |
| 跨服务 | 订单事实经 `CustomerOrderFactsPort` 读 **trade**、类目经 `CustomerCategoryPort` 读 **catalog**，均只经授权 HTTP API，转发调用方自己的 bearer ⇒ 两道 RLS |
| 权限 | `crm.lifecycle.read` / `crm.lifecycle.update`；请求体不接受 tenantId / userId / membershipId |

## 关键 trade-off

### 1. 另起一条只读端口，而不是给 `CustomerFactsPort` 加 `skuCodes`

既有 `CustomerFactsPort`（15.3.1.1 客户列表）**刻意不读** `skuCodes`（它用不到那个字段，javadoc 里写明了「读得少，上游加字段时本模块不容易被撑爆」）。而本模块的**类目维度**必须从订单行里的 SKU 编码出发去 catalog 解析类目 ⇒ `skuCodes` 是硬需求。

**取舍**：改共享文件最省事，但 `CustomerFactsPort` 是 **15.3.1.1 与并行中的 15.3.1.2 共用的文件** —— AGENTS.md 要求并行车道**只碰自己的文件**，改共享文件会让下轮并入产生结构性冲突、成果全卡在冲突里。
⇒ **按同一写法另起一条只读端口 `CustomerOrderFactsPort`**（同样的 RestClient / 同样的显式失败语义），**一个字都不改**那个共享文件。代价是两条端口读同一个 trade 端点，但本模块只用自己那条，**运行期不会重复拉取**。

### 2. 类目解析失败 ⇒ 整体失败（503），而不是「当作没有类目」

catalog 不可达时若退到全局阈值，整批客户会**悄悄**落到全局阈值上，而运营以为自己是按类目配的 —— 这正是蓝图加粗句要防的事。
⇒ catalog 不可达 ⇒ **整体 503**；而**单个 SKU 在 catalog 里查不到**是**事实**（客户买了没建档的 SKU），如实标注 `category_source=UNRESOLVED` 并计数，**不猜**。
另外 `q` 是模糊匹配（`ilike '%keyword%'`），会顺带命中 `SKU-001-A` / `SKU-001-B` ⇒ 端口在客户端做**编码全等匹配**，匹配不上就是「解析不出来」。

### 3. 类目查询逐个 SKU 查，且有上限

catalog 只有 `GET /catalog/skus`（支持 `q` 模糊查询），**没有「按编码批量取」的端点** ⇒ 逐个查，用上限（`MAX_SKU_LOOKUPS=300`，每客户最多查最近一单的前 3 个 SKU）兜住，触到上限时**如实报 `categoryLookupTruncated`** 并把剩下的记为「未解析」。没有上限时一个大租户能把请求线程占满。

### 4. 预警「不删只标」，分层行「会删」

预警是**历史事实**（谁在什么时候被判过超期），重算后不再超期时**标为已处理**（`status=RESOLVED` + 说明），**不删** —— 历史要能复核。
分层结果则是**当前状态的投影**：客户老到不再被扫到时他的行必须跟着走，不然后面分层统计的分母会把不同窗口的客户混在一起 ⇒ **清掉**。
⚠️ 实现上顺序很关键：**先把这一次仍然超期的人挑出来，剩下的才标记已处理，最后才 upsert 新的** —— 反过来写（一把梭全表 RESOLVED 再写新的）会把刚算出来的预警也标成已处理，清单会莫名其妙全空而每一次运行都「成功」。

### 5. 阈值不可就地修改（不可变优先）

改一条已生效策略的阈值，会让**之前引用它的那次判定**指向另一个含义，历史解释不了。
⇒ `PUT /policies/{uuid}` **只允许**改 enabled / note / 生效终点；改阈值必须**新建**一条（新的生效起点）。测试里断言 409。

### 6. 扫描窗口默认两年，RFM 窗口另算

流失预警要能看见「**已经很久没买**的人」。若只扫最近一年，一个 400 天没下单的客户根本不会出现在名单里 —— 而他恰恰是最该被召回的那位。
⇒ 扫描窗口默认 **730 天**（`DEFAULT_SCAN_WINDOW_DAYS`），R/F/M 的计算窗口另由策略的 `rfm_window_days` 控制（默认 365 天）。

### 7. 「距上次购买多少天」按 UTC 日历天，两端都归一

订单时刻可能带 +08:00 偏移。直接 `toLocalDate()` 会拿东八区的日期去减 UTC 的日期 —— **差一天就是错判一天**（「本应复购未复购」整天错位）。
⇒ 两端都 `withOffsetSameInstant(UTC)` 再取日期；测试用 +08:00 夹具断言写回仍是**同一绝对时刻**、距上次购买 22 天。

## 文件清单

### 后端（crm 服务）

| 文件 | 说明 |
| --- | --- |
| `backend/services/crm/src/main/resources/db/migration/V436__crm_customer_lifecycle.sql` | 四张新表 + 索引 + 六个触发器 + RLS（ENABLE/FORCE）+ GRANT + 序列授权 |
| `backend/services/crm/src/main/java/com/starcon/crm/CustomerLifecycleModels.java` | 传输模型（枚举 + 入参 + 出参） |
| `backend/services/crm/src/main/java/com/starcon/crm/CustomerLifecycleService.java` | 业务逻辑（jOOQ 显式 SQL；重算 / 分层 / 预警 / 阈值策略） |
| `backend/services/crm/src/main/java/com/starcon/crm/CustomerLifecycleController.java` | 12 个端点，命名空间 `/crm/customer-lifecycle` |
| `backend/services/crm/src/main/java/com/starcon/crm/CustomerOrderFactsPort.java` | 跨服务只读端口（trade，**含 `skuCodes`**） |
| `backend/services/crm/src/main/java/com/starcon/crm/CustomerCategoryPort.java` | 跨服务只读端口（catalog，SKU → 类目） |
| `backend/services/crm/src/test/java/com/starcon/crm/CustomerLifecycleIntegrationTest.java` | 业务不变量（20 例，真实 PostgreSQL 16） |
| `backend/services/crm/src/test/java/com/starcon/crm/CustomerLifecycleTenancyIntegrationTest.java` | 租户隔离三断言（4 例，连接池压到 1） |

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/app/crm/customer-lifecycle/page.tsx` | 客户留存看板页面 |
| `apps/web/src/components/crm/customer-lifecycle-workspace.tsx` | 真实工作区（能力边界 / 概览 / 预警清单 / 分层结果 / 阈值策略 / 重算） |
| `apps/web/src/components/crm/customer-lifecycle-model.ts` | 前端模型（标签、空态、阈值与基线文本、请求） |
| `apps/web/src/components/crm/customer-lifecycle-model.test.ts` | 纯规则测试（17 例） |
| `apps/web/src/app/api/crm/customer-lifecycle-allowlist.test.ts` | api-proxy 白名单双向断言（5 例） |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `services[]` 元素**（`prefix: 'crm/'`，首 token `customer-lifecycle`），插在 **identity 中段**（紧邻 1.3.2.1 预警规则那条之后） |

### 契约与台账

| 文件 | 说明 |
| --- | --- |
| `packages/api-contracts/src/contracts/crm.ts` | codegen 产物（crm 纯新增 22 个 DTO，零删除零改字段） |
| `packages/api-contracts/src/crm.openapi.json` | codegen 产物 |
| `docs/product/capability-blueprint/module-progress.md` | **只改 15.6.2.1 那一行**（状态 → 已交付 + 证据） |

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | exit 0 |
| L0 类型 | `pnpm typecheck` | 3/3 通过 |
| L0 格式 | `pnpm exec prettier --check <本车道 6 个前端文件>` | 全部通过 |
| L0 lint | `pnpm --filter @starcon/web lint` | 本车道零告警（其余为既有告警） |
| L1 模块 | `./mvnw -pl services/crm -am test -Dtest='CustomerLifecycle*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **24 例全绿**（集成 20 + 租户隔离 4） |
| 前端 | `pnpm vitest run apps/web/src/components/crm/customer-lifecycle-model.test.ts apps/web/src/app/api/crm/customer-lifecycle-allowlist.test.ts` | **22 例全绿** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api` | **293 文件 / 1648 例全绿**（改了共享的 api-proxy.ts ⇒ 必须跑） |
| 契约 | `./mvnw -pl services/crm -am test -Dtest='OpenApiContractTest'` + `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | crm 纯新增，**其余 8 个服务零漂移** |

## 后续待办

- **15.5.2 触达未交付** ⇒ 本模块只产出召回建议。待其交付后，召回建议的归属方应直接消费本模块的预警清单；本模块**不**接管发送。
- **15.3.1.3 跨店合并 / 15.3.1.2 客户详情未交付** ⇒ 客户仍按来源店铺展示；合并交付后由它增强本模块的客户维度（**下游增强，不是本模块的前置**）。
- **13 分析未交付** ⇒ 本模块的分层结果与预警清单是它的上游事实。
- 分类目阈值目前是**人工登记**（运营填 catalog 的类目编码）。若后续需要「按类目自动匹配历史订单」，可考虑把类目维度做成**受控词表 + 校验**，但那属于阈值配置体验的增强，**不影响**本模块的判定正确性。
- `MAX_SKU_LOOKUPS=300` 与扫描页数上限（25 页）在大租户下会触发截断；截断**如实报出**，但如果真实规模远超，需要另设一个「类目缓存表 + 增量刷新」的口子 —— 本轮**未做**，因为它会引入新的写入所有者与同步语义。
