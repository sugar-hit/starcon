# 7.7.2.1 VMI 供应商管理库存

> 阶段 D3，L3 锚点 7.7.2（特殊持有模式），后端主责服务 `warehouse`。
> 车道 `feat/7-7-2-1`，基线 `integration/module-dev`（fb59785）。

## 决策摘要

蓝图对本模块只有一句加粗约束，但它决定了整个数据模型：

> **VMI 的「可售」不等于「自有」**：可售量要计入可售额，货值不能计入自有资产，两者必须分开统计。

所以本模块的第一件事不是建表，而是**把这条约束从「注释」变成「数据库不变量」** —— 否则它
在任何一次绕过服务层的写入面前都只是一句承诺。落地为四道闸：

1. `vmi_agreement_line.counts_as_owned_asset` 恒 false，`CHECK` 写死；
2. 协议行引用的 `stock_owner_id` **不许**是「计入自有资产」的那一类 —— 触发器查 7.7.1.1
   （V67 已交付）的 `stock_owner.counts_as_owned_asset` 生成列，为 true 直接拒绝；
3. 明细表里**没有第二个金额列**，能算出来的只有可售额；
4. `ownedAssetValue` 恒 `null` —— DB 里就不存在「VMI 自有货值」这个数。

第 2 条是最关键的一条：光在 VMI 自己的表里写 `false` 不够，真要把 VMI 货算成自有资产必须去动
7.7.1.1 的货主档案。这条触发器把那扇门焊死了，而且还有一个反向触发器挡住「建协议时合规、
事后把货主改成 SELF」这条更隐蔽的路。

**读模型层**对应的是 `VmiOwnershipSplit`（VMI 货：可售额计入、自有货值恒 null）与
`VmiSelfOwnedSplit`（同一 SKU×仓 在我方 SELF 货主下的量）**两张分开的卡片**。
界面上没有「库存价值」这种合计字段 —— 一旦相加就等于把供应商的货算成我们的资产。
另外做了一个刻意的取舍：**没有把 VMI 量折算成我方自有资产口径的「可售额占比」**，
宁可少一个看起来有用的指标，也不在财务口径上引入一个容易被误读的合并数。

## 关键 trade-off

### 1. 在库量：先查账本，账本没有才回落租户登记

VMI 货**常常物理上就在供应商自己的仓**，那种情况下我方账本里压根没有桶。这不是数据缺失，
是一种真实经营形态。取数顺序因此硬编码为：

- 先查我方账本（`stock_bucket` 按 `stock_owner_id` 聚合 SELLABLE 质量状态的 `available`，
  同服务同库，不是跨服务读）；
- 账本里没有这个 SKU×仓 的桶时，**才**回落租户登记的 `vmi_stock_snapshot`。

每行都带 `stockSource`，且 `WAREHOUSE_BUCKET` **不接受手工登记**（会被 400 拒绝）：手工往里写数
会让「系统算的」与「人报的」混在一起，来源标注就失去意义了。两边都查不到时如实报 `MISSING`
并说清后果（按 0 计入水位会误判断货并触发补货），而不是悄悄按 0 处理。

### 2. 货权复用 7.7.1.1，不 ALTER 别人的表

开工自检确认兄弟模块 7.7.1.1（`V67__stock_ownership.sql`）**已交付**：`stock_owner` 表带
`owner_kind` 词表与 `counts_as_owned_asset` 生成列，`stock_bucket.stock_owner_id` 已 NOT NULL。
因此本模块直接引用货主档案，不建第二套货主概念，也**不 ALTER `stock_bucket` / `inventory_balance`**
（那两张表是 7.1.1.3 车道的唯一写入所有者）。

实测中还撞见一个事实并据此调整了夹具：**`inventory_balance` 的键里没有货主维度**，同一
SKU×仓×法人×质量只有一行余额，货主差异落在 bucket 上。这恰好是「货权是 bucket（最小事实单位）
的维度、balance 是它的汇总」，也是 VMI 货与自有货能在同一 SKU×仓 并存而不互相覆盖的原因 ——
第一版测试夹具按「两条余额行」写，被唯一约束当场挡下。

### 3. 结算：明细 4 位、表头 2 位，刻意不同

明细金额 `numeric(24,4)` 生成列（数量 × 结算价，全表没有第二个金额来源），但**单据级应付
`numeric(24,2)` 由触发器按 `round(Σ明细金额, 2)` 重算**。之所以比寄售多这一步：VMI 对账单是要
发给供应商对账的凭据，分以下必须与发票口径一致。逐笔 4 位（追溯用）与表头 2 位（付款用）是刻意的，
两者都有测试断言读回的 scale。

## 被测试逼出来的三处真 bug

都不是测试写错，是实现的问题：

1. **PL/pgSQL 输出变量与列同名**：`guard_vmi_agreement_line_owner` 里 `code` 既是输出变量又是
   列名，PL/pgSQL 默认 `variable_conflict=error` 直接报 `column reference "code" is ambiguous`，
   把「货权红线」的真实原因完全挡掉。改名 `owner_code` 并给列加表别名。
2. **延迟约束触发器把原因吞成一句无消息的 500**：原本用 `DEFERRABLE INITIALLY DEFERRED`，
   在 COMMIT 时抛错、Spring 异常转换后只剩 `JDBC commit; bad SQL grammar []`，调用方完全不知道
   为什么被拒。改成**立即**触发器（理由与 V71 的 `consignment_settlement_totals` 同源），
   PostgreSQL 的原始原因原样抛出。
3. **累计超卖漏成 500**：服务层只比**本次增量**，累计口径由 DB `CHECK` 兜底，超卖时调用方拿到的是
   一个指不到原因的 `DataAccessException`。补上 `DataAccessException → 409` 的翻译。

另外顺手修了测试辅助方法 `assertDbRejects`：它只打 `e.toString()`，而 jOOQ/Spring 包装后的
`toString()` 里没有 PostgreSQL 的原始原因 —— 正是它让第 1 条 bug 难以定位。改成逐条打印异常链消息。

## 文件清单

**新增**

- `backend/services/warehouse/src/main/resources/db/migration/V115__warehouse_vmi.sql`（8 表 + 3 序列 + 12 索引 + 11 触发器）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/VmiModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/VmiService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/VmiController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/VmiIntegrationTest.java`（27 例）
- `apps/web/src/app/inventory/vmi/page.tsx`
- `apps/web/src/components/inventory/vmi-workspace.tsx`
- `apps/web/src/components/inventory/vmi-model.ts`
- `apps/web/src/components/inventory/vmi-model.test.ts`（23 例）

**修改（均为多车道共用文件，按约定各自追加）**

- `apps/web/src/lib/api-proxy.ts` — 外科式追加 `vmi` 分支（两处）。把分支原样摘掉后与 HEAD
  **逐字节相同** ⇒ 除追加外零改动；`capabilities` 排在 `[0-9a-f-]+` 通配之前判别。
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts` — 追加 3 组用例（23 个调用点放行 /
  6 个不存在子路径仍 404 / 追加后 7.7.1.1 与 7.7.1.2 形状仍放行）。
- `packages/api-contracts/**` — codegen 产物，只由脚本生成。
- `docs/product/capability-blueprint/module-progress.md` — 只改 7.7.2.1 那一行的「状态」「证据」
  两格（路由格保持 `—`；`sync.mjs --check` 通过）。

**刻意没做**

- **没加导航条目**：`packages/ui/src/lib/task-pages.ts` 里只有 4 个 inventory 路由，兄弟模块
  7.7.1.1 货权、7.7.1.2 寄售、7.4.2.1 盘点都不在其中。改它是全仓共用文件，多车道并行时会产生
  跨车道冲突，与 7.4.2.1 / 7.4.2.2 的既有决定保持一致。页面路由 `/inventory/vmi` 可直达。
- **没在 api-proxy 加解释性注释块**：该文件是 3 条并行 warehouse 车道共用的累加型文件，
  任务要求的证明是「插入点之前逐字节相同」，加注释会破坏这条证明。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 绿 |
| L1 模块 | `cd backend && ./mvnw -pl services/warehouse -am test` | **1308 例全绿**（含 VMI 27 例） |
| 类型 | `pnpm typecheck` | 绿 |
| Lint | `pnpm lint` | 绿 |
| 前端 | `pnpm vitest run <vmi-model.test.ts> <warehouse route.test.ts>` | 54 例绿（23 + 31） |
| 台账 | `node tools/module-progress/sync.mjs --check` | 通过 |

契约 codegen：warehouse **+23 路径 / +32 schema / 消失 0 / 既有 schema 内容变化 0**，
identity/catalog/channel/trade/crm 五份快照零漂移。

## 后续待办

- **上游交付后要接的三处**：`vmi_sale`（9 订单 / 13 销量）、补货信号的 `PROCUREMENT` 去向
  （6.2.1.2）、对账单的应付交接（12 结算）。三者都只留了 `source_system + reference_id` 定位键，
  交接方式写在 `capabilities.unavailable` 里，接入时按该键读回即可，本表即为那份事实的唯一出处。
- **`vmi_stock_snapshot` 的 `VENDOR_FEED` 来源目前只能手工登记**：供应商对接（3.6.2）交付后应改由
  自动采集落这张表（来源标 `VENDOR_FEED`），本表即为观测留痕。
- **`coverageDays` 只在自落地销量覆盖的范围内成立**：13 销量交付后应改读其授权 API，
  否则近 30 天销量只反映本服务登记的售出事实，不是完整的多平台销量。
- **协议价改不了**：已在跑的协议不能中途改 `settlement_price`（会让已出对账单与协议对不上），
  要改价请新建协议。若业务上需要「调价不带回溯」，需要另开一版价格生效期表 —— 那是 6 采购的职责，
  不在本模块范围。