# 12.4.1.1 计价方法配置（V355 · 阶段 D6 · 车道 12.4.1.1）

> 蓝图原文：`docs/product/capability-blueprint/12-finance.md` 的 **12.4.1.1 计价方法配置**。
> 车道分支 `feat/12-4-1-1`，基线 `integration/module-dev` 的 `6a1b2fc0`。
> 迁移版本 **V355**（调度者预分配；同批另三条车道占 V354 / V356 / V357）。

## 决策摘要

### 1. 最重要的一条：**配置面与重算执行分开，且这条边界写在数据层**

蓝图关系句：「上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。」
12.4.1.2「成本计算执行」与 12.4.1.3「成本差异分析」本轮**均未开发**，本模块交付的是它们的**配置面**。

⇒ 边界不靠注释，靠**数据层写不出来**：

| 列 | 约束 | 含义 |
| --- | --- | --- |
| `finance_cost_valuation_method.recalculation_status` | `NOT_REQUIRED` / `PENDING` | 切换方法只**标记**「需要重算」 |
| `finance_cost_valuation_method.recalculation_owner_module` | CHECK `= '12.4.1.2'` | 重算归下游 |
| 本库**不建**成本层表 / 结转金额表 | —— | **「已重算」这个状态在结构上就写不出来** |

页面侧同样克制：只显示「待重算（12.4.1.2）」，**绝不显示任何「已重算」结论**。

### 2. 「系统提供选项与试算，不替用户决定政策」—— 落成三条可执行的纪律

1. **零预置行**：四张表**一行配置都不预置**。计价方法是租户自己的会计政策，
   系统替用户默认一个方法就是替用户记账（同一个 SKU 在两套账里的成本能差 30%，
   而这个差额是谁定的，必须可追溯到某个人在某天的选择）。
   方法选项由服务层**只读**提供（值 + 名称 + **口径说明**），前端模型里**没有任何「推荐 / 默认 / 建议」措辞**
   （前端单测直接把这条钉住）。
2. **试算只算给人看**：不改配置、不写成本事实、不产出结转金额。
3. **试算用系统里真实存在的数据**：成本层来自 **catalog 5.2.1.1 成本构成**
   （真实到货记录 `vendor_delivery_record` 派生的批次清单），经授权只读端口读取，
   **不造示例成本**。上游读不到一律 **503 并写明「读不到」** ——
   绝不返回空集合冒充「这个 SKU 没有成本层」。

### 3. 「切换方法需重算并留版本对比」—— 版本链写在数据层

切换方法 = **追加新版本**，不是就地 UPDATE：

- 触发器钉死：版本号 = 上一版 + 1；生效起点**严格晚于**上一版；新版本必须接在上一版之后
  （`supersedes_id` 必须等于上一版 id）；
- 旧版本被置为 `SUPERSEDED` 并**收口生效区间**（`effective_to = 新版本 effective_from`），
  ⇒ 任意时刻「当时生效的是哪一版」可由区间唯一定位；
- **已存在的版本行不许改**方法 / 范围 / 版本号 / 生效起点（触发器钉死历史不可改写）——
  否则版本对比会退化成「同一个方法跟自己比」，历史也不再是证据；
- 「同一范围同时只有一个生效版本」由**部分唯一索引**钉死（不是靠应用层自觉）。

> ⚠️ 一个写库顺序上的坑：部分唯一索引在 INSERT 时立即生效 ⇒ 写库顺序必须是
> **先把上一版置为 SUPERSEDED，再插入新的一版**。因此版本守卫比对的是
> **版本号最大的那一版**（而不是 `status='ACTIVE'` 的那一行）——
> 若守卫拿 ACTIVE 行来比，插入时库里已经不存在 ACTIVE 行，正常的版本切换反而会被挡住。

### 4. 范围（法人 / 品类）按**跨服务逻辑引用 + 名称快照**处理

法人来自 identity 17 法人档案（`GET /identity/legal-entities`），品类来自 catalog 品类树
（`GET /catalog/categories/trees` + `/trees/{treeId}/nodes`）。
AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ **不建影子表、不复制身份证明字段**，
配置行只存逻辑 ID + 编码 + **名称快照**（改名不改历史）。
范围下拉读不到时显示「读不到」，**不是**「这个租户没有法人」。

### 5. 试算结果表**按方法一行**（不是一行四组数字）

放一行里就得把四组数字塞成列（`fifo_cost` / `moving_average_cost` / …）——
那是最容易算错的一行（少一列就静默变成 0）。拆成「表头 + 每方法一行」之后：
加一种方法就多一行，不用改结构；**算不出结果的方法一律不给数字**
（`comparable=false` + `gap_note` 写明原因），因为给 0 会被读成「成本是 0」。

「覆盖量 + 缺口量 = 试算量」由触发器钉死 —— 缺口被写成 0，界面就会显示
「按 FIFO 成本 12.00」，而实际库存只够一半 ⇒ 那个数字是假的。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- |
| 零预置行 | 开箱即用 | 计价方法是租户自己的会计政策，系统替用户默认就是替用户记账 |
| 切换 = 追加版本（不 UPDATE） | 「改一下方法」的简单性 | 历史不可改写才有版本对比可言，且旧成本不被追溯改写 |
| 试算在 finance 侧按真实成本层计算 | 直接复用 catalog 的成本构成接口 | catalog 的接口按**已存**方法算，无法给出四种方法的并排对比 |
| 指定单价单独一张表（按 SKU + 币种） | 挂在方法行上的简单性 | 方法按法人/品类设，单价按 SKU 设 —— 挂方法行就变成「整个法人一个标准成本」 |
| 异币种成本层排除并报出 | 自动折算 | 跨币种折算要汇率表（属 12 财务的其它部分），绝不编一个汇率 |
| 上游读不到一律 503 | 返回空集合「看起来正常」 | 空集合会被界面读成「没有成本层」，那是本模块最致命的一种撒谎 |

## 文件清单

**迁移**
- `backend/services/finance/src/main/resources/db/migration/V355__finance_cost_valuation_method.sql`

**后端**（`backend/services/finance/src/main/java/com/starcon/finance/`）
- `CostValuationModels.java`（枚举 / 能力声明 / 方法配置 / 指定成本 / 试算 / 版本对比 / 输入）
- `CostValuationService.java`（能力声明、选项、范围、方法 CRUD + 版本链、版本对比、指定单价、试算算法与留痕）
- `CostValuationController.java`（12 个端点挂 `/finance/cost-valuation`）
- `CostValuationScopePort.java`（**新建**：法人 / 品类清单的跨服务只读端口，读不到即状态而非异常）
- `CostValuationLayerPort.java`（**新建**：catalog 真实成本层的跨服务只读端口，读不到一律 503）

**测试**（`backend/services/finance/src/test/java/com/starcon/finance/`）
- `CostValuationMethodIntegrationTest.java`（16 条）
- `CostValuationMethodTenancyIntegrationTest.java`（7 条，三条隔离断言）

**契约**（codegen 产物，只由生成器改）
- `packages/api-contracts/src/contracts/finance.ts`
- `packages/api-contracts/src/finance.openapi.json`

**前端**
- `apps/web/src/components/finance/cost-valuation-model.ts`
- `apps/web/src/components/finance/cost-valuation-model.test.ts`
- `apps/web/src/components/finance/cost-valuation-workspace.tsx`
- `apps/web/src/app/finance/cost-valuation/page.tsx`
- `apps/web/src/app/api/finance/cost-valuation-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.4.1.1 那一行的状态与证据两格**）

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ 全绿；`pnpm typecheck` ⇒ 全绿（3/3 任务成功）。
- **L1 模块闸**：
  `./mvnw -pl services/finance -am test -Dtest='CostValuationMethod*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 23, Failures: 0, Errors: 0`
  （CostValuationMethodIntegrationTest 16 + CostValuationMethodTenancyIntegrationTest 7）。
- **前端**：`pnpm vitest run apps/web/src/components/finance/cost-valuation-model.test.ts apps/web/src/app/api/finance`
  ⇒ **17 文件 / 96 例通过**（因改了共享的 `api-proxy.ts`，这一条就是**全量 allowlist 回归**）。
- **契约**：`./mvnw -pl services/{identity,catalog,channel,warehouse,trade,crm,finance} -am test -Dtest='OpenApiContractTest' …`
  ⇒ 7 个服务各导出 1 份 spec（BUILD SUCCESS）；随后
  `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  ⇒ `Generated 6517 DTOs in 7 per-service files`（**未手改任何契约文件**）。

## 后续待办

- **12.4.1.2 成本计算执行**（下一格，**未开发**）：读
  `finance_cost_valuation_method` 里 `status='ACTIVE'` 且落在期间内的那一行（唯一真相）即可开始重算，
  **不必再抄一份方法**；本表的 `recalculation_status='PENDING'` 是「需要重算」的**标记**，
  由它在**自己的**表里落成本层与结转金额。
- **12.4.1.3 成本差异分析**（**未开发**）：输入是 12.4.1.2 的成本计算结果，
  本模块**不产出**任何差异数字。
- **13 利润**（**未开发**）：本模块只产出「用的是哪一版方法」这一条口径事实，不产出任何利润数字。
- **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（四条车道并发改同一文件必然冲突）
  ⇒ 待调度者统一处理。
- **identity 权限目录**：finance 服务的权限码（`finance.cost_valuation.*`）尚未进 identity 的 `permission` 表 ——
  这是**全服务既有的缺口**（`finance.receivable.*` / `finance.transaction.*` 同样未进），
  不属本车道写入所有权，本车道沿用既有约定（模块自己的 `finance.<module>.<action>` 词表）。
