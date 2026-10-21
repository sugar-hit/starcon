# 7.4.1.1 调整单管理 —— 行序钉死（偶发假红修复）

- 日期：2026-10-21
- 车道：7.4.1.1（修复车道，分支 `fix/7-4-1-1-line-order`）
- 归属：后端 warehouse 服务（模块 7.4.1.1 调整单管理，阶段 D6 / 卷 07 库存核算，L3 锚点 7.4.1）
- 触发：L2 批次闸第二轮，集成头 `9f8b796a` 全量 `mvnw verify` 里 warehouse 1612 例红了 1 例

---

## 一、结论摘要

调整单明细行原先按**随机 UUID 主键**排序，同一张单的两行谁在前每次都可能不一样 ⇒
「批量调整单的明细行自己换位置」⇒ 约 50% 概率的偶发假红。

本次做两件事：

1. **行为修正（本模块自己的表 + 服务）**：给 `inventory_adjustment_order_line` 补一个**行序号**
   `line_no`，两处读行都改成 `order by l.line_no` ⇒ 行的顺序 = **加行顺序**，是确定的。
2. **测试修正**：原用例的断言改成**按行的身份**（sku × 库位 × 批次）找那一行；
   另加一条**确定性**回归用例，把「行的顺序 = 加行顺序」钉死（现场真实构造，不是碰运气）。

---

## 二、根因（已自行复核）

`backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java`：

- 调整单详情读行：`... where l.tenant_id = ? and l.order_id = ? order by l.id`（改前第 314 行）
- 过账前的行预检：同一句 `order by l.id`（改前第 752 行）

`inventory_adjustment_order_line.id` 是 `uuid PRIMARY KEY`，服务层用 `UUID.randomUUID()` 写入
⇒ **随机 v4** ⇒ UUID 的大小顺序与加行顺序毫无关系。V260 建表时**没有**行号列
（只有 id、六维引用、快照列、qty_delta、reason、note、expected_version、入账痕迹、created_at）。

红的那条用例 `一次建单多行_批量调整落到同一张单`（改前第 451 行起）断言的是**索引位置**：

```java
assertThat(lines.get(0)).containsEntry("skuCode", "SKU-1").containsEntry("locationCode", "A-01");
assertThat(lines.get(1)).containsEntry("lotCode", "");
```

用例里第一行加的是 `locOne`（A-01）、第二行加的是 `locTwo`（A-02）
⇒ 只有 UUID 排序恰好与加行顺序一致时才绿 ⇒ 约 50% 概率红。
2026-10-21 19:36 集成头全量跑红的就是它（`locationCode` 期望 A-01、实得 A-02），
而**上一轮 L2 同一断言是绿的** ⇒ 典型偶发，不是稳定缺陷。

---

## 三、关键 trade-off：为什么选「加行序号」，否决了什么

### 3.1 方案 A（采纳）：行序号 `line_no`

- **为什么**：批量调整单里操作员给的顺序**本身有业务含义**（按货架走、按清点顺序）。
  排序键必须能表达它，否则「审批人看到的顺序 = 操作员提交的顺序」这条链就断了。
  行序号是本仓既有口径（V117 `recount_line.line_no`、V213 `logistics_cost_allocation_line.line_no`、
  V254 `purchase_order_line.line_no` 都是同一写法：单内从 1 递增 + `UNIQUE(单头, line_no)`）。
- **跨车道面**：V260 是本批次 2026-10-21 新建、尚未推送的迁移，表是**本模块自己的**，
  全仓只有两个写入方（服务层 + 本模块的租户隔离测试夹具），两个都在本车道内同步改好
  ⇒ **不构成跨车道 DDL 回归**；**零 ALTER 既有表**（`stock_bucket` / `inventory_balance` /
  `stocktake_variance_*` 一律没碰）。
- **API 契约**：行号是内部排序键，**不进 DTO** ⇒ 端点形状不变
  ⇒ `packages/api-contracts` / `packages/sdk` **不重新生成**，前端零改动。

### 3.2 方案 B（否决）：不加列，改按业务键排

`order by sku_code, location_code, lot_code, l.id` —— 顺序确实确定了，但含义变成
「按 SKU / 库位 / 批次排」而不是「按加行顺序」，与 3.1 的理由冲突；
而且它把「同一 SKU 不同库位」这种常见批次的顺序交给字典序，业务上没有依据。

### 3.3 NOT NULL 与默认值怎么兜底（本仓反复出事的那个形状）

纪律：不能出现「加了 NOT NULL 列却不给默认值 ⇒ 别人的老 INSERT 必违约」。
V261 按 identity V6（`department.code`）同一条口径做四步，**全程在一个事务里**
（PG 的 DDL 是事务性的 ⇒ 迁移窗口内没有任何并发会话能看见中间态）：

1. `ADD COLUMN line_no integer NOT NULL DEFAULT 0` —— 加列这一刻在非空表上也不会失败；
2. 回填：`row_number() OVER (PARTITION BY tenant_id, order_id ORDER BY id)`（历史行只有 id 可依；
   回填出来的序号**不还原**当初的加行顺序 —— 那已不可知 —— 但**确定、可复现**，
   且从此行的顺序不再随 UUID 抖动）；
3. `ALTER COLUMN line_no DROP DEFAULT` —— 之后任何 INSERT 都**必须显式**给行号；
4. `CHECK (line_no > 0)` + `UNIQUE (tenant_id, order_id, line_no)`。

**为什么不留默认值**：留一个「魔法默认值 1」会让漏写行号的 INSERT 静默拿到 1
⇒ 同单第二行撞 UNIQUE（报一个看不懂的错），更糟的是单行单悄悄排到了最前面。
本表只有一个业务写入方（服务层，已同步显式给行号）⇒ 「必须显式给」是安全的，
漏给时报的是**响亮的 23502 not-null violation**，不是一行含义错掉的明细。
本模块的第二个写入方（`InventoryAdjustmentTenancyIntegrationTest` 的夹具）**同步显式补上行号** ——
这正是「别人老 INSERT 违约」的形状，在本车道内被显式修好，而不是靠默认值掩盖。

### 3.4 并发加行怎么保证不撞号

- **服务层**：加行前**先 `select ... for update` 锁住单头**，再取 `max(line_no) + 1`
  ⇒ 同一张单的并发加行排队执行，拿到互不相同的行号。
  顺带把「加行期间单据被别人提交/作废」这条竞态也关上了（提交/作废都要改这一行）。
  建单时（同一个事务、这张单还没提交给别的会话）行号按输入顺序直接 1..n 赋值。
- **DB 层**：`UNIQUE(tenant_id, order_id, line_no)` 是最终保证 ——
  绕过服务层直接 INSERT 也撞不动（23505），不会静默重号。
- **删行不重排**：行号只增不减（删掉第 2 行后第 3 行仍是 3）。重排会改写已过账行的意义
  （V260 的 `guard_inventory_adjustment_line_posted` 也不许改行），而且留空号比「行号连续」更诚实：
  它如实说「这里原本有过第 2 行」。

### 3.5 其它

- 行号**不可改写**：`guard_inventory_adjustment_line_posted` 的不可变元组加上 `line_no`
  （`CREATE OR REPLACE FUNCTION`）⇒ 改行号 = 重写单据的先后，DB 层当场拒绝。
- 旧的 `(tenant_id, order_id)` 索引被新唯一索引的前缀完全覆盖 ⇒ `DROP INDEX` 去掉重复索引。

---

## 四、文件清单

| 文件 | 改了什么 |
| --- | --- |
| `backend/services/warehouse/src/main/resources/db/migration/V261__inventory_adjustment_line_no.sql` | **新增**。行表补 `line_no`（加列 → 回填 → DROP DEFAULT → CHECK + UNIQUE）、删重复索引、触发器函数加 `line_no` 不可变 |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java` | 两处 `order by l.id` → `order by l.line_no`；`create` 按 1..n 赋行号；`addLine` 先 `for update` 锁单头再取 `max(line_no)+1`；`insertLine` 带行号；新增 `nextLineNo` / `lockOrderHeader`；类注释补「行序 = 加行顺序」 |
| `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentIntegrationTest.java` | 原用例断言改成**按行的身份**找行；新增确定性回归用例 `行的顺序是确定的_按加行顺序而不是随机UUID`；新增 4 个助手（`lineOf` / `detailLines` / `lineNoByLocation` / `locationsById` / `reverseLineIds`） |
| `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentTenancyIntegrationTest.java` | 夹具 INSERT 显式补 `line_no`（本模块第二个写入方） |
| `docs/product/capability-blueprint/module-progress.md` | 只改 7.4.1.1 那一行的「证据」格（追加本次修复说明），「状态」格保持 `已交付`；分隔符用全角 ｜，不引入 ASCII 半角竖线 |
| `docs/sessions/2026-10/2026-10-21-inventory-adjustment-line-order-fix.md` | 本文件 |

**未动**：其它服务的表、其它迁移、`stock_bucket` / `inventory_balance` 等既有表、前端、
`packages/api-contracts` / `packages/sdk`、台账其它行。

---

## 五、验证结果

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 通过（退出码 0）
- **L0 类型闸**：`pnpm typecheck` ⇒ 通过（本车道未动任何 TS）
- **L1 模块闸**：
  `./mvnw -pl services/warehouse -am test -Dtest='InventoryAdjustment*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **25 例全绿**（`InventoryAdjustmentIntegrationTest` 22 + `InventoryAdjustmentTenancyIntegrationTest` 3；
  原 24 + 新增 1），0 failure / 0 error，BUILD SUCCESS。
  迁移日志可见 `Migrating schema "public" to version "261 - inventory adjustment line no"`。
- **确定性连跑 5 轮** ⇒ 每轮 25/25 全绿（耗时 23.3 / 16.2 / 15.8 / 15.8 / 15.5 秒量级）。
- **变异验证**：把两处 `order by l.line_no` 改回 `order by l.id`
  ⇒ 新回归用例**立刻 FAIL**（`行的顺序是确定的_按加行顺序而不是随机UUID:564
  [第 1 行仍然是加行时提交的第 1 件（A-01）]`），
  而改写后的原用例 `一次建单多行_批量调整落到同一张单` **仍绿**
  ⇒ 证明「原用例已不再依赖位置」「新用例确实在盯着排序键」。变异已回滚。

---

## 六、后续待办

1. 7.4.1.2（审批）交付时若要写调整行，**必须显式给 `line_no`** ——
   漏给会撞 23502（响亮地炸，不会静默落成 0/1），但要在交接说明里提一句。
2. 行号目前**不对外暴露**（不进 DTO）。若前端要显示「第几行」，
   那是契约变更 ⇒ 改后端 DTO 再重新生成 `packages/api-contracts` / `packages/sdk`，不要手改生成物。
3. 本次只跑了本模块的测试类；**集成头全量 verify 由调度者跑**（本车道不得自行 merge）。
