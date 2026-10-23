# 16.3.2.3 销售税与 Marketplace Facilitator（阶段 D6 · L3 锚点 16.3.2）

- **日期**：2026-10-24（Asia/Shanghai）
- **车道**：`feat/16-3-2-3`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.3.2.3`）
- **基线**：`integration/module-dev` @ `2874248d`
- **迁移版本号**：`V352__channel_sales_tax.sql`（预分配；同批另外三条车道占用 V350 / V351 一带）
- **主责服务**：`channel`（卷 16「合规与风控」主责 = `channel + identity`，取第一项）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 168-174 行）：

> ### 16.3.2.3 销售税与平台代收
>
> - **职责**：处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、按平台的代扣代缴说明与对账。
> - **输入**：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
> - **输出**：各州税务状态 + 代扣代缴对账结果。
> - **关系**：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。**平台代扣代缴不等于免税**，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。

本车道的落地要点：

1. **🔴 主线是「应缴 vs 已代缴两条线」，数据模型里刻意不设「销售税净额」这一列。**
   `expected_tax`（应缴，卖家欠税局的）与 `facilitator_tax`（**已由平台代扣代缴**，平台代扣代缴报表口径）
   分开存，`variance` = 应缴 − 已代缴（**正数 = 平台少收了，卖家要补缴**；负数 = 平台多收了）。
   另存 `settlement_tax`（结算单口径）**只作交叉验证，不参与差异计算**。
   理由：压成净额就看不出「该补缴」与「该退款」—— 而这恰恰是本模块存在的意义。
2. **🔴 税率 / 州级阈值 / 免征额是「可配置、可版本化的规则数据」，不是代码常量。**
   落在 `sales_tax_rate_rule`（州码 × 生效区间 × 版本号），一个州可有多版；
   生效区间**左闭右开**且**不许重叠**（DB 触发器钉死）；**已封版版本内容冻结**（DB 触发器钉死）；
   **改规则 = 发新版本**（封旧版 + 建新版，不回填）。
   对账结果**快照**当时用的版本号与税率 ⇒ 事后改规则**改不动历史判定**（蓝图「关键约束」第 2 条）。
3. **🔴 州码不另建表**：直接复用 16.3.1.1 已有的 `tax_registration_jurisdiction`
   （美国 50 州 + DC 已以 `US-XX` / `tax_regime='SALES_TAX'` 落库），州码由触发器钉死落在码表里、
   **不留自由文本**。本模块**不写**上游的 `tax_registration_record`（上游写所有权不变），
   也**不把**上游那个辖区级登记号复制一份当自己的权威源 —— 两者是「粗 / 细」两层。
4. **🔴 平台代扣代缴报表走真实上传 + 真解析 + 真落库。**
   `SalesTaxFacilitatorImportParser`（CSV / JSON）：文件级错误整份失败 400、
   行级错误只跳过那一行并写明「第几行 + 为什么」；原文一字不改地留着 + 内容指纹 sha256 +
   `parse_version`（同一 `file_key` 重新导入 +1，历史版本留着）。
5. **🔴 自营应税销售额跨服务只经授权 HTTP API**：`SalesTaxTransactionPort` 读 finance 的
   `GET /finance/transactions`（`ORDER_SALES` = 自营应税销售额，`WITHHOLDING_TAX` = 结算单口径代扣税），
   **转发调用方自己的 Authorization bearer、绝不传租户号头**，租户由上游按已认证成员关系解析（两侧各一道 RLS）。
   **读不到就整体失败（503）、一条都不写** —— 拿 0 当「这段时间没卖货」算出来的「应缴 0」
   是本模块最贵的错误。
6. **🔴 三种「不是 0」的结论**：
   没有生效规则 ⇒ `RULE_MISSING`；该州没登记任何店铺所在州 ⇒ `NEXUS_MISSING`（自营销售额根本没归集）；
   未达州级阈值 ⇒ `BELOW_THRESHOLD`（**期间无申报义务，但 ≠ 免税**；免征额只减基数，落进 `exempt_sales` 单列）。
7. **下游 12.6 申报未交付 ⇒ 不建申报表、不做申报提交、不声称已申报**，
   只产出「各州应缴 / 平台已代缴 / 差异」的对账结论与 `GET /obligations` 的申报义务视图。
   16.3.2.1 VAT/IOSS、16.3.1.2 申报日历同样未交付 ⇒ 不编造其清单与统计。

## 关键 trade-off

| 决策点                                              | 取舍                              | 理由                                                                                                                                                                          |
| --------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 销售税结果的口径：净额 vs **两条线 + 差异**         | **两条线 + 差异**                 | 蓝图加粗句是硬要求。净额把「平台少收了要补缴」与「平台多收了要退款」压成同一个 0                                                                                            |
| 税率规则的载体：Java 常量表 vs **可配置规则表**     | **可配置规则表**                  | 税率、阈值、免征额会变且因州因时而异。写死在代码里 = 改一次规则发一次版，且事后查不出「当时用的是哪一版」                                                                        |
| 州码来源：自建州表 vs **复用 16.3.1.1 的辖区码表** | **复用辖区码表**                  | 16.3.1.1 已经把美国 50 州 + DC 落库。再建一张就是两份「州是什么」的真值                                                                                                    |
| 「没有规则 / 没有所在州」怎么算：按 0 算 vs **判不了** | **判不了**（两个独立状态）     | 「应缴 0」在申报里是最危险的数字。规则缺失与 nexus 缺失是**两件不同的事**，分开标                                                                                            |
| 规则改动：就地改 vs **发新版本**                    | **发新版本**                      | 「改了就是新的生效版本，历史判定按当时那版算」。就地改当前版本的事实在 DB 层被触发器直接拒                                                                                  |
| 平台代缴口径：报表口径 vs **结算单口径**            | **报表口径为主，结算单口径作交叉验证** | 蓝图输入点名的「平台代扣代缴报表」是权威口径；结算单的 `WITHHOLDING_TAX` 是另一个数，**不一致本身就是「要查」的信号**，但不该混进差异计算 |
| 销售税与「店铺所在州」的归属：改 `shop` 表 vs **独立 nexus 表** | **独立 `sales_tax_shop_state`** | 改 `shop` 会动到卷 2 的表；销售税联系（物理存在 / 库存存放 / 经济联系）是**合规域的事实**，不是店铺的通用属性         |
| 平台代扣代缴报表来源：手填 vs **真上传解析**        | **真上传解析**                    | 报表是外部事实；手填等于让人把平台的汇总数再抄一遍，抄错的概率远高于解析错的概率                                  |

## 涉及文件

**迁移（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V352__channel_sales_tax.sql`

**后端（channel 服务）**

- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxRules.java`
- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxFacilitatorImportParser.java`
- `backend/services/channel/src/main/java/com/starcon/channel/SalesTaxTransactionPort.java`

**后端测试**

- `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxRulesTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxTenancyIntegrationTest.java`

**前端**

- `apps/web/src/components/sales-tax/sales-tax-model.ts`
- `apps/web/src/components/sales-tax/sales-tax-model.test.ts`
- `apps/web/src/components/sales-tax/sales-tax-workspace.tsx`
- `apps/web/src/app/compliance/sales-tax/page.tsx`
- `apps/web/src/app/api/channel/sales-tax-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，首 token `sales-tax`，锚点 = `epr-registrations` 之后）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（**只改 16.3.2.3 那一行的「状态」「证据」两格**）
- `docs/sessions/2026-10/2026-10-24-sales-tax-marketplace-facilitator.md`（本文件）

## 已知口径（写进能力声明与页面，不是 bug）

- **期间内该州发过新版规则时，对账按「期间末（`period_end`）生效的那一版」计**，
  结果里快照版本号并置 `rateChangedInPeriod=true`，页面提示人工复核；**不做按日拆分**。
  理由：自营应税销售额在本模块是**按期间汇总**的口径，按日拆分需要逐单税额，
  而上游 12.3.1.2 给的是汇总行不是税额行。
- **对账按币种分开算**（`UNIQUE(tenant_id, state_code, period_start, period_end, currency)`）：
  不同币种的应税基数与阈值不跨币种比较；某币种只有平台代缴而没有自营销售时，
  结论落在「有差异」而不是「已对平」—— 这本身就是需要查的信号。

## 后续待办

- **12.6 申报**（下游，未交付）：直接消费 `sales_tax_reconciliation` 的
  `expected_tax` / `facilitator_tax` / `variance` 与 `GET /obligations` 的申报义务结论，
  补上申报日历与提交状态跟踪。本模块**刻意不做**。
- **16.3.1.2 申报日历**（未交付）：本模块只给「期间是否在申报期内」需要的那部分事实
  （规则的 `filing_cycle` 已在 `sales_tax_rate_rule` 里），日历本身归它。
- **销售税登记号与 16.3.1.1 的辖区级登记的联动**：目前两套登记各自独立
  （`tax_registration_record` 是辖区级、`sales_tax_shop_state` 是店铺 × 州级）。
  若将来要做「辖区登记号自动带出店铺登记」，需要先在 16.3.1.1 侧确认写所有权归属，
  **不要**在本模块里改上游的表。
- **跨州退货 / 退款的分摊**：本模块的自营应税销售额取自上游的 `ORDER_SALES` 与 `REFUND` 之外的净额口径，
  退款跨州分摊（refund allocation）未做；若上游将来提供按州的退款行，需在 `SalesTaxTransactionPort`
  侧扩展，而不是在本模块里推算。

## 关联

- `docs/product/capability-blueprint/16-compliance-and-risk.md`（16.3.2.3 原文；卷 16 的「关键约束」第 2 条：规则变更不能追溯改变历史判定）
- `docs/product/capability-blueprint/module-progress.md`（台账行 16.3.2.3）
- `docs/decisions/0002-multi-tenancy-strategy.md`、`docs/architecture/multi-tenancy.md`（租户隔离三断言）
- `docs/operations/catalog-local-development.md`（后端本地开发与 Testcontainers 口径）
- 同 L3 兄弟：`docs/sessions/2026-10/2026-10-23-epr-registration.md`（16.3.2.2 EPR/包装法，本车道的落点与写法参照它）
