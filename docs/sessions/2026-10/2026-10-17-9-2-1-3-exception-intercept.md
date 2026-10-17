# 9.2.1.3 异常拦截（trade 服务）

> 会话：2026-10-17 · 车道 `feat/9-2-1-3`（worktree `.worktrees/9.2.1.3`）· 集成头基线 `a052a025`

## 决策摘要

按蓝图 09 卷 9.2.1.3 交付「异常拦截」，归属 **trade** 服务，迁移 `V153__trade_order_exception_intercept.sql`
（预分配版本号，**零 ALTER 既有表**）。

蓝图职责句列了七类异常，本模块落成**八个命中项**、六张表、11 个端点：

| 蓝图项 | 命中项 | 数据来源 |
| --- | --- | --- |
| 地址信息不全 | `ADDRESS_INCOMPLETE` | 收货地址快照列**真算** |
| 地址不可达 | `ADDRESS_DELIVERABILITY` | 9.2.2.1 未交付 ⇒ 恒 `UPSTREAM_UNAVAILABLE` |
| 黑名单买家 | `BLACKLISTED_BUYER` | 读 V149 `order_review_buyer_profile` |
| 重复下单 | `DUPLICATE_ORDER` | 同 `buyer_key` 窗口内**本单之前**的已提交笔数 |
| 异常折扣（低于成本） | `BELOW_COST_DISCOUNT` | 行净额单价 vs 租户成本基线 |
| 疑似欺诈 | `SUSPECTED_FRAUD` | 读 V150 `order_risk_assessment`，**不重算分数** |
| 超出收货国家限制 | `COUNTRY_RESTRICTED` | 租户可维护限制清单 |
| 平台合规风险 | `PLATFORM_COMPLIANCE` | 租户可维护限制清单（**平台级，非店铺级**） |

### 加粗句「拦截不自动取消，默认转人工」的五层落点

1. `final_disposition` 词表（`RELEASE` / `HOLD` / `CANCEL`）**不含任何「自动」取值** ——
   DB 里不存在「系统自己取消了这一单」这个状态。
2. 表级 CHECK `manual_review_required = (recommended_disposition <> 'RELEASE')` ⇒
   **「建议取消」也必须携带「需人工处置」**。
3. `guard_order_intercept_final_disposition`：最终处置必须已有 `order_intercept_disposition` 留痕。
4. `guard_order_intercept_disposition_requires_manual`：人工处置只挂在需人工处置的检查上
   （挡住**反方向**越权）。
5. 服务层**没有任何一处**写 `finalDisposition`；唯一入口 `POST /checks/{id}/disposition` 要人工身份。

派生的一条同样落进 DB：**一条命中都没评上时不出「放行」**，只给「转人工」——
放行一个没人看过的单与误拦真实订单一样是真实损失，而加粗句只说了后者更贵，不是只说了后者。

## 关键 trade-off

- **「地址不可达/信息不全」拆成两个命中项而不是合成一个。**
  前者依赖未交付的 9.2.2.1（判不了），后者能从收货地址快照的列真算。合成一行只能让
  能判的那半也被一起标成「不可用」—— 等于把已经接通的能力也报成缺口。
- **`observed_value` 只放真正可比的数值。** `COUNTRY_RESTRICTED` 与 `PLATFORM_COMPLIANCE`
  比的是「US」「AMAZON」这样的文本，文本比对结果一律走 `evidence_reference`。
  塞进 numeric 列要么被 DB 拒，要么被写成 0 然后被读成「量过了，结论是 0」。
- **折扣符号在 V99 里头行与行相反**（头 `discount_amount >= 0`，行 `<= 0`）。
  行净额必须是 `line_amount + discount_amount`；写成减号会让「异常折扣」**永不命中**，
  而一个永不命中的检查比没有检查更糟 —— 界面会显示「无异常折扣」。
- **黑名单「无档案」落 `NO_REFERENCE` 而不是 `CLEARED`。** 把「没登记过」当成「核验过、干净」
  会让合规黑名单形同虚设。
- **成本基线按 SKU 存且带采集时刻。** 按订单存会让「这单为什么被判低于成本」在成本改了之后无法复算；
  取值只使用**订单提交时刻之前**采集到的基线。
- **平台维度只能是平台级。** 实查 V99 `sales_order` **没有 `shop_code` 列**，
  所以 `shopScopeSupported = false`，不在界面上假装支持店铺维度。

## 踩到的坑（都是错误信息与成因无关的那类）

| 现象 | 真正成因 |
| --- | --- |
| javac「非法的表达式开始」指向某个 `)` | 方法调用实参列表的**尾逗号**（`List.of(a, b,)`） |
| jOOQ `BadSqlGrammarException: bad SQL grammar` | 参数化 SQL 里写了 `--` 行注释，注释把后面的 `?` 占位符一起吞掉 |
| `new row violates row-level security policy` | 测试夹具在**租户事务外**写库（V153 六张表都 FORCE RLS） |
| 绕过服务的 UPDATE「什么都没发生」 | 同上，RLS 让它静默 0 行 —— 断言「被挡下了」其实是空的 |
| `value too long for character varying(24)` | `evidence_source` 枚举名 `MODULE_9_2_2_2_RISK_ASSESSMENT` 有 28 个字符 |
| ts `Argument of type 'undefined' is not assignable` | 仓库开了 `exactOptionalPropertyTypes` |
| codegen 输入类型缺 `effectiveFrom` | `@Schema(nullable)` 被生成成**必填的 nullable**，必须显式传 `null` |

## 文件清单

**新增（后端）**
- `backend/services/trade/src/main/resources/db/migration/V153__trade_order_exception_intercept.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderInterceptModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderInterceptService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderInterceptController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/OrderInterceptIntegrationTest.java`（38 例）
- `backend/services/trade/src/test/java/com/starcon/trade/OrderInterceptTenancyIntegrationTest.java`（5 例）

**新增（前端）**
- `apps/web/src/app/orders/review/intercept/page.tsx`
- `apps/web/src/components/orders/order-intercept-model.ts`
- `apps/web/src/components/orders/order-intercept-model.test.ts`（13 例）
- `apps/web/src/components/orders/order-intercept-workspace.tsx`
- `apps/web/src/app/api/trade/order-intercept-allowlist.test.ts`（8 例）

**修改**
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json`（codegen 产物，**未手改**）
- `apps/web/src/lib/api-proxy.ts`（新增**独立一条** `services[]` 元素，不并进既有 trade 单行 alternation）
- `packages/ui/src/lib/task-pages.ts`（新增 `/orders/review/intercept` 条目）
- `docs/product/capability-blueprint/module-progress.md`（**只改 9.2.1.3 那一行**的状态与证据两格）

## 路由说明（与台账路径列不一致，已核实）

台账路径列写 `/orders/review`，但该路由已在 `packages/ui/src/lib/task-pages.ts` 登记为
**同 L3 的 9.2.1.1 待审队列**入口（`implemented: false`），9.2.1.2 已刻意避开它另取 `/orders/review/rules`。
本模块同样避开，取 **`/orders/review/intercept`**，并按约定**只改自己那一行**，未动路径列。

## 验证

- L0：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck --force` 3/3（`Cached: 0`，非 turbo 缓存命中）
- L1：`cd backend && ./mvnw -pl services/trade -am test` ✅ **835 例全绿，BUILD SUCCESS**
- 前端：`pnpm vitest run`（本模块 2 个文件 21 例 + `nav-routes` 守卫）✅ 31 例全绿
- 迁移：在真实 PG16 上按序跑完 V1..V153 共 29 条迁移全部通过，并**实跑验证了五层守卫**
  （无留痕写 final 被拒 / finding 与 disposition append-only / BLOCK 命中只给建议且 final 为空 /
  平台来源词表校验 / 处置后才回填）

## 后续待办

- 9.2.2.1 地址与支付校验交付后：`ADDRESS_DELIVERABILITY` 由同步器按同一形状写入，
  把 `status` 从 `UPSTREAM_UNAVAILABLE` 改掉即可，**表结构与判定逻辑都不用改**。
- 16 合规黑名单与平台合规清单交付后：`order_intercept_restriction.restriction_source`
  改成对应 `MODULE_*`，同上不改结构。
- 卷 05 定价交付后：成本基线可由同步器刷新，容忍度口径仍留在策略表里。
- 2 平台取消回写、11 售后（欺诈售后）交付后，可消费本模块的 `final_disposition`（而非
  `recommended_disposition`）—— 两者分开存正是为了让下游无法「只看建议就回写取消」。
