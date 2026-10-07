# 3.9.2.2 僵尸主数据清理（catalog，阶段 D1–D2）

车道 `feat/3-9-2.2`，基线 `integration/module-dev` @ `12d71b3`。

## 决策摘要

按蓝图 3.9.2.2 交付「识别僵尸主数据 → 候选清单 → 批量停用 → 停用记录」全链路，
落在 catalog 服务。四件套：迁移 `V39`、端点 `/catalog/zombie-candidates/*`、
页面 `/products/master-data-cleanup`、测试 44 例（后端）+ 33 例（前端）。

## 关键 trade-off

### 1. 四个引用维度走本地快照表，不跨库查询

「从未被订单引用 / 无库存 / 无 listing / 成本为 0」四条里，**没有一条是 catalog 自己的数据**。
AGENTS.md 禁止跨服务 SQL/FK/视图，因此不去 join 任何别家服务的表，改为在 catalog 内维护
`master_data_reference_stats`，由上游经授权 API 推送。

选**推送式**而非 V6 sku_detail 那种拉取式（`SkuDetailProjectionPort`），因为僵尸判定要扫全量
主数据而不是详情页上那一条：拉取式在这种量级下会退化成 N+1 次跨服务调用。
两者边界纪律一致 —— catalog 不复制别家的写入所有权，只保存别人主动交过来的观测值。

### 2. 三态判定：未知不等于零（本模块最重要的一条）

判定是 `CANDIDATE / NOT_ZOMBIE / INCOMPLETE` 三态，不是布尔。若把「上游还没接入」当成
「这个 SKU 从未被引用」，**每个 SKU 都会变成候选**，运营批量一点就把整个目录停用了 ——
而系统不报错，因为它确实"如实"地读了 0。所以：

- 快照表缺行 = 未知，不是 0；
- 启用中的维度缺观测或观测过期 → `INCOMPLETE`，**不可停用**；
- 批量停用准入失败**整批 409**（带逐条 blocked 清单），不跳过；
- 顺序是**违反优先于未知**：已观测到"有订单"的主体一定 NOT_ZOMBIE，哪怕另一维度缺观测。

与仓库既有 `UnwiredProjectionPort`「未接入就拒绝执行而不是返回 0 让规则跑通」同一纪律。

### 3. 判定下推到 SQL

僵尸是派生结论。若在内存里过滤再分页，会出现「第 1 页 20 条里 3 条是候选而总数 50」，
而且点第 2 页会看到上一页已经出现的行 —— 那是假分页。
所以 `verdictSql()` 把三态判定整体写成 SQL 表达式，分页与计数走同一份；
逐条的"为什么"仍由纯规则 `ZombieCandidateRules` 在内存算（它要产出可读原因，原因不参与筛选）。

### 4. 停用而非删除 + 记录冻结

无任何物理删除端点：SKU 改 `sku.status=INACTIVE`，供应商/客户改 `party.status=INACTIVE`
并写 `status_reason`/`status_changed_at`（实体目录 §5：被引用后只能停用，历史单据仍需展示）。

停用记录**冻结 rule_snapshot + evidence jsonb**：规则会改、快照会更新、SKU 会改名，
只存 id 则三个月后回答不了"凭什么"。该表只授 SELECT/INSERT 并 REVOKE UPDATE/DELETE。

## 踩到的坑（都不是本模块设计问题，是基础设施的）

1. **DTO 重名会被静默覆盖**。`tools/codegen/catalog-contracts.py` 把四个服务的 schemas 拍平进
   同一命名空间，重名后者覆盖前者。第一版用 `Options`/`OverviewRow`/`DimensionStatus` 时，
   warehouse 的 `OverviewRow`（balanceId/qualityStatusId…）把本模块同名类型覆盖了 ——
   **生成物里看不出任何异常**，前端却会拿到字段完全不相干的类型。
   全部 DTO 改带 `Zombie` 前缀（已核对类型集合 500→523，移除 0 个）。

2. **可空列必须标 `@Schema(nullable=true)`**。codegen 只认这个注解产出 `| null`。
   不标的话 `ZombieDimensionStatus.value/observedAt` 在 TS 里是非空 number/string，
   前端会开始相信"未观测的维度取值是 0"—— 正是本模块要避免的错误。

3. **record 之外的便利方法不会被契约导出**。`requiredDimensionCount()`/`countsConsistent()`
   编译期存在、SpringDoc 不产出 → 前端 TS2339。改成前端按契约里真实存在的四个布尔列现算。

4. **`Map<String,X>` 在 codegen 里退化成 `{ }`**（`ts()` 不处理 additionalProperties），
   前端读任何键都是 TS2339。规则快照与证据快照改成**显式字段**（四个维度各一列）。
   不改 codegen —— 它是多车道共用工具，改它会影响所有车道的生成物。
   附带好处：新增维度忘记加一列会被编译器发现，`Map` 不会。

5. **VALUES 列表里裸 `?` 会让 jOOQ 把 `OffsetDateTime` 当 varchar 发**，
   PG 报 `column "observed_at" is of type timestamp with time zone but expression is of
   type character varying`。所有 timestamptz 写入改 `?::timestamptz`。

6. **判定投影里不要绑 `?`**（`? - s.updated_at` 算 days_since）：jOOQ 对投影表达式里无名参数的
   计数不可靠，报"未设定参数值 N"而 N 指向 `limit`/`offset`，指针完全不在真正的位置上。
   改为不在 SQL 算天数，由 `daysSince()` 从 `last_maintained_at` 现算。

7. **jOOQ 的 `Record.field(String)` 缺列时可能返回非 null 的 Field**（随后 `get` 抛
   `Field X is not contained in row type`），只在某类查询路径上出现，极难定位。
   逐条明细的观测来源统一到 `loadObservations()`，不再按列名从行里读。

8. **测试里同一 SPU 下不能建两个无属性 SKU**（`UNIQUE(tenant,spu,variant_signature)`），
   每个 SKU 挂各自的 SPU。

## 当前哪些引用维度没有可用事件源

| 维度 | 归属 | 本仓库现状 |
| --- | --- | --- |
| `INVENTORY_ON_HAND` | warehouse（07 库存） | **服务已交付**，接入只差 warehouse 侧推送方 |
| `ORDER_LINE_COUNT` | trade（订单） | **服务不存在**（`services/` 下无 trade 目录） |
| `UNIT_COST` | purchase（06 采购） | **服务不存在** |
| `LISTING_COUNT` | channel（04 刊登） | 服务存在，但只有平台目录/连接/店铺/同步，**无刊登实体** |

四个开关**默认全 false**，词表带 `wired` 标注、概览列出未接入清单、界面禁用对应开关。
**没有用假数据糊过去** —— 缺观测就是 INCOMPLETE。

本车道**未修改 warehouse 任何文件**（那是另一个服务的写入所有权）；推送端点现在就可用，
上游接入时无需改本模块 schema 或规则。

## 文件清单

新增：
- `backend/services/catalog/src/main/resources/db/migration/V39__zombie_master_data_cleanup.sql`
- `backend/.../catalog/ZombieCandidateModels.java` / `ZombieCandidateRules.java` /
  `ZombieCandidateService.java` / `ZombieCandidateController.java` /
  `ZombieMetric.java` / `ZombieEntityType.java` / `ZombieVerdict.java`
- `backend/.../catalog/ZombieCandidateRulesTest.java` / `ZombieCandidateIntegrationTest.java`
- `apps/web/src/components/catalog/zombie-candidate-model.ts` / `-workspace.tsx` / `-model.test.ts`
- `apps/web/src/app/products/master-data-cleanup/page.tsx`

改动：
- `backend/.../catalog/ApiErrorHandler.java`（+1 个 handler）
- `apps/web/src/lib/api-proxy.ts`（+1 个顶层分支，自带前导 `|`）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（+1 例）
- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（仅自己那一行 + sync 自动更新的汇总数）

## 验证

```
backend: ./mvnw verify            → BUILD SUCCESS（catalog 1091 / identity 35 / channel 66 / warehouse 70，0 失败）
frontend: pnpm typecheck          → 3/3
frontend: pnpm lint               → 3/3
frontend: pnpm test               → 45 files / 772 tests 全绿（基线约 739，新增后上升）
frontend: NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
sync:   node tools/module-progress/sync.mjs --check → ✓ 台账与蓝图一致
```

## 后续待办（不是本模块的缺口）

- **trade 服务落地后**推送 `ORDER_LINE_COUNT`：调用
  `POST /catalog/zombie-candidates/reference-stats?entityType=SKU`，无需改本模块。
- **warehouse 侧加推送方**推 `INVENTORY_ON_HAND`：服务已具备 bucket 数据，只差定时推送。
- **channel 04 刊登落地后**推 `LISTING_COUNT`。
- **purchase 06 采购落地后**推 `UNIT_COST`。
- 上游接入后由运营在规则配置里逐个打开维度开关，每打开一个都能立刻看到覆盖率变化
  （概览的"有观测的主数据"那一格）。

## 与兄弟模块的边界

- **3.9.2.1 完整度评分**：同在 3.9.2 L3 下但**独立**，不共享数据表 ——
  评分回答"缺什么"，本模块回答"没人用"。
- **3.1.1.3 SPU 生命周期**：本模块只读 `spu`/`sku` 的状态与 `updated_at`，
  **不改 V4 的 `spu_lifecycle` 五态** —— 那是 SPU 层级的状态，与 `sku.status` 不是一回事。
- **3.6.1.1 往来单位**：`party` 的 `status` 列由它拥有，本模块只把它改成 `INACTIVE`
  并留停用记录；V27 第七节点名"3.9.2.2 清理的正是这类"，本模块兑现了这句话。