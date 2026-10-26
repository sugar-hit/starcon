# 2026-10-24 · 12.6.1.1 税率配置（阶段 D6）

## 决策摘要

| 项   | 结论                                                                                                                                                          |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V361__finance_tax_rate.sql`（两张新表，纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表） |
| 表   | `finance_tax_rate`（税率记录，版本链）· `finance_tax_shop_binding`（店铺站点默认税率与税码绑定，版本链）                                                      |
| 端点 | 13 个，挂 `/finance/tax-rates`；权限 `finance.tax_rate.read / .create / .update`                                                                              |
| 页面 | `/finance/tax-rates`                                                                                                                                          |
| 测试 | 后端 38（30 + 8）· 前端 18（13 + 5）· 全量 allowlist 回归 230 文件 / 1300 例                                                                                  |
| 契约 | `packages/api-contracts/src/contracts/finance.ts` 新增 20 个 `FinanceTax*` 类型（+20/-0）                                                                     |

### 职责切分（与 channel 16.3.2.3）

- **归本模块**：VAT / IOSS / 代扣代缴（withholding）/ **目的国/地区的**销售税率，以及店铺站点的默认税率与税码绑定。
- **归 channel 16.3.2.3**：美国**州级**销售税（州基准税率 / 州阈值 / 免征额 / Marketplace Facilitator 义务与申报周期）与店铺所在州（nexus）事实。本模块**不重复建表**，只在页面上经 `ChannelTaxPort` 只读引用。
- **怎么读**：`ChannelTaxPort`（照 `ChannelShopPort` 的既有模式）转发**调用方自己的 bearer**，读 `GET /channel/sales-tax/rate-rules`；辖区码表读 `GET /channel/tax-registration/jurisdictions`。**不跨库 SQL / 不跨库 FK / 不跨库视图**。channel 读不到一律 503 并写明「读不到」，不用空集合冒充「没有州销售税 / 没有辖区」。

## 关键 trade-off

### 1. 绑定钉「税档」而不是「税率版本 id」

第一版设计让 `finance_tax_shop_binding.tax_rate_id` 指向**某一版税率**。评估后发现它会造出一个**无解的操作顺序**：

- 税率版本被换掉时，收口它会被「还有店铺绑着它」的守卫挡下；
- 而要换绑就得先有新版本，可新版本又要求旧版本先收口。

⇒ 改成：绑定钉**一条税档**（辖区 × 税码），税率按**生效时间**从版本链里取。政策改税率时，绑在这条税档上的店铺自动从新版本的生效时刻起按新税率算，历史订单仍按当时那版算 —— 既没有悬空引用，也没有操作顺序的死结。

代价：绑定行上不直接存税率数字（页面上通过取税率面板或绑定行的「当时那条税档的税率」体现）。这个代价换来的是**结构上不可能出现「店铺绑着一版已失效的税率」**。

### 2. 税率版本链的键带「税码」

版本链键 = （租户 × 税种 × 辖区 × **税码**），不是（租户 × 税种 × 辖区）。同一辖区里的不同税档（标准 / 优惠 / 零税率）各自版本化：德国标准 VAT 19% 改成 17% 时，只动 STANDARD 那条链，REDUCED 那条链**一个字都不动**。

### 3. 辖区码钉在上游受控码表上，而不是自由文本

「目的国/地区」的值域由 channel 16.3.1.1 的 `tax_registration_jurisdiction` 决定。本模块**不复制**那张码表（复制 = 两个真值源）、**不建**指向它的外键（跨库 FK 是模块边界纪律禁止的），只在落库前经授权只读 API 校验「这个辖区码真实存在」。**美国辖区（联邦与 50 州）在本模块录不进去** —— 州销售税归 channel 16.3.2.3，重复维护就是制造第二个真值源。

### 4. 「历史不可追溯改写」落成结构而不是文案

蓝图加粗句由五件东西共同钉死：生效区间（左闭右开，`effective_to` 为空 = 还没被换掉）+ 版本链（触发器钉死版本号递增、生效起点严格递进、新版本必须接在上一版之后）+ 不可改写（触发器钉死税率 / 辖区 / 税码 / 版本号 / 生效起点 / 接续版本一律不许改，`effective_to` 只允许「从空变成有值」这一次）+ 生效区间不许重叠（`tstzrange` 触发器）+ 部分唯一索引保证同链同时只有一个生效版本。

「按生效时间取税率」命中不到时**一律报错**，不退回最新一条、不按 0% 处理 —— 「这个时点没有税率」和「这个时点税率是 0」是两件完全不同的事。

### 5. 税率不带币种列

税率是**比例**，作用于任何币种的应税基数；币种属于税额（金额）那一侧，由 12.6.1.2 申报模块与订单侧负责。所以 `finance_tax_rate` 只有 `numeric(9,6)` 的税率，没有 `currency`。

## 文件清单

**新增**

- `backend/services/finance/src/main/resources/db/migration/V361__finance_tax_rate.sql`
- `backend/services/finance/src/main/java/com/starcon/finance/TaxRateModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TaxRateService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TaxRateController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ChannelTaxPort.java`
- `backend/services/finance/src/test/java/com/starcon/finance/TaxRateIntegrationTest.java`
- `backend/services/finance/src/test/java/com/starcon/finance/TaxRateTenancyIntegrationTest.java`
- `apps/web/src/components/finance/tax-rate-model.ts`
- `apps/web/src/components/finance/tax-rate-model.test.ts`
- `apps/web/src/components/finance/tax-rate-workspace.tsx`
- `apps/web/src/app/finance/tax-rates/page.tsx`
- `apps/web/src/app/api/finance/tax-rate-allowlist.test.ts`
- `docs/sessions/2026-10/2026-10-24-tax-rate-config.md`（本文件）

**改动**

- `apps/web/src/lib/api-proxy.ts` —— 新增一条独立 `services[]` 元素（数组末尾），首 token `tax-rates`。
- `packages/api-contracts/src/contracts/finance.ts` —— codegen 产物，新增 20 个类型，**未手改**。
- `packages/api-contracts/src/finance.openapi.json` —— codegen 产物快照（**纯新增**：11 路径 + 20 schema，零删除、零既有内容变化）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 12.6.1.1 那一行的「状态」「证据」两格。

## 验证

```
L0  cd backend && ./mvnw -q compile                                   → EXIT=0
L0  pnpm typecheck                                                    → 3/3 successful
L1  cd backend && ./mvnw -pl services/finance -am test \
        -Dtest='TaxRate*' -Dsurefire.failIfNoSpecifiedTests=false \
        -DfailIfNoSpecifiedTests=false                                → Tests run: 38, Failures: 0, Errors: 0
前端  pnpm vitest run apps/web/src/components/finance/tax-rate-model.test.ts \
        apps/web/src/app/api/finance/tax-rate-allowlist.test.ts        → 2 文件 / 18 例全绿
全量  pnpm vitest run apps/web/src/app/api                            → 230 文件 / 1300 例全绿
      pnpm lint                                                       → 3/3 successful
```

Testcontainers PostgreSQL 16 实跑；租户隔离三条断言跑在**本模块自己的两张表**上，连接池压到 1。

## 后续待办

- **12.6.1.2 VAT/IOSS 申报数据**（同服务，未交付）：消费本模块的取税率能力出申报底稿。接法：按（辖区 × 税码 × 期间）调 `GET /finance/tax-rates/resolve` 或直接读 `finance_tax_rate` 的生效区间。
- **9 订单（税额，别的服务）**：经授权只读 API 取「某时点的生效税率」并**快照那一版**进订单。
- **12.6.2.1 发票管理**（同服务，未交付）：行级税码税率归它；本模块只到**店铺默认**税码这一层。
- ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记** —— 四条车道并发改同一文件必然冲突，留给调度者统一处理。
