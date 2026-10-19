# 11.2.3.3 供应商索赔

> 时间：2026-10-19 22:20–23:00
> 车道：`feat/11-2-3-3`（worktree `.worktrees/11.2.3.3`）
> 基线：集成头 `integration/module-dev` @ `6904288d`
> 状态：**已完成并通过 L0 编译闸 + L1 模块闸（28 例实跑 Testcontainers PostgreSQL 16 全绿），等待调度器并入。**

---

## 决策摘要

11.2.3.3「供应商索赔」落在 **trade 服务**，迁移 `V188__supplier_claim.sql`
（**五张新表、零 ALTER 零 DROP 既有表**），端点 15 个挂 `/trade/supplier-claims`，
前端 `/aftersales/returns/supplier-claims`。

上游依赖在开工前逐条 grep 确认存在（**未 BLOCKED**）：
`return_quality_inspection`（V179 11.2.3.1）、`product_issue_attribution`（V180 11.1.2.2）、
`quality_supplier_ref`（V185 11.5.2.1）。蓝图写的「上游 = 11.1.2.2」在 trade 的落点
就是 `product_issue_attribution`，本模块只读它、不改它。

---

## 关键 trade-off

### 1. 「索赔不是自动到账」必须落成结构，不能落成文案

蓝图加粗句「索赔不是自动到账，系统只跟踪流程与金额，冲减应付需财务确认」如果只落成一个
`settlement_status`，那么「PENDING」和「已生效但没回执」在库里就没有区别。落法是**两列**：

- `requested_amount`（登记的赔付金额）vs `effective_amount`（**已生效**的冲减金额）
- 表级 CHECK：未 `CONFIRMED` ⇒ `effective_amount = 0`；
  `CONFIRMED` ⇒ `effective_amount = requested_amount` 且 `confirmed_by` / `confirmed_at` /
  `finance_reference` 三样齐全
- 索赔单头上的 `applied_offset_amount` 由 DB 触发器从赔付分录**重算**
- 「已结算 ⇒ 冲减已生效」再由表级 CHECK 兜住
- 已确认的分录与已结算的单**整行冻结**（终态不可逆）

结果：绕过服务层直连 SQL 塞一个生效金额，当场被拒（测试里有这条断言）。
同时**没有**「直接置为已结算」的端点 —— 已结算只能由「财务确认赔付分录」驱动。

### 2. 12 财务（finance 服务）不存在 ⇒ 冲减应付落成 trade 侧自有事实记录

`backend/services/` 下当前只有 identity / catalog / channel / warehouse / trade / crm，
**没有 finance**。蓝图的下游是「12 财务（冲减应付）」，但写一个不存在的服务是自欺欺人。

落法：`supplier_claim_settlement` 是 **trade 侧的自有事实表**，记录赔付方式
（`OFFSET_PAYABLE` 冲减应付 / `SUPPLIER_REFUND` 供应商退款）、登记金额、生效金额、
财务确认人与财务单据号。两种赔付方式的**财务确认要求完全一样** —— 蓝图说的是
「冲减应付**或**退款记录」，二者都是「供应商认了、钱要落地」这一步。

跨服务衔接留待 finance 落地后经**授权 API / event**，本模块**绝不**去读或写它。
同理下游 6.5.1 采购对账**未交付** ⇒ 不写对账单、不建共享视图。

### 3. 财务确认用**独立权限码** `trade.aftersales.finance`

「冲减应付需财务确认」如果只是状态机上的一个按钮，那句规则就只约束了流程、不约束了权限。
落成独立权限码后，登记赔付的人（`trade.aftersales.write`）**不能**顺手把冲减确认掉。
测试里有一条 HTTP 层断言：只有 read/write 的身份调确认 → 4xx，且生效金额仍为 0。

### 4. 索赔基数由 DB 取，调用方传不进来

蓝图输入句是「质检结论与责任判定；索赔金额」—— 但「索赔金额」的**上限**必须有个真来源，
否则「报多少」全凭操作员心情。落法：

- 索赔基数 = 质检单原值 `original_value`（= 订单行单价 × 实收数量，V179 算的真值）
- 本行上限 = `round(原值 × 索赔数量 ÷ 实检数量, 4)`
- 传输层**没有**基数字段；DB 触发器取数并与表级 CHECK 比对

⇒ 「4 件里只坏 1 件，却按 4 件的价索赔」在本模块没有代码路径。
前端也**不给**「原值 / 基数」输入框 —— 可填的金额等于把财务口径交给每个操作员自己编。

### 5. 可索赔的责任环节只有两个：生产 / 描述不符

11.1.2.2 的归因环节是六值枚举，但**不是**六个都能向供应商索赔：

| 环节 | 可索赔 | 理由 |
| --- | --- | --- |
| PRODUCTION 生产 | ✅ | 供应商的产品本身有问题 |
| DESCRIPTION 描述不符 | ✅ | 描述是供应商侧的商品信息 |
| TRANSPORT 运输 | ❌ | 责任在承运商，不在供应商的产品上 |
| WAREHOUSE 仓储 | ❌ | 责任在我们自己 |
| BUYER_FACTOR 买家因素 | ❌ | 明显不是供应商的锅 |
| UNDETERMINED 责任待定 | ❌ | 拿不确定的责任去对质，赢了也是赢了个寂寞 |

不可索赔的环节**说清楚为什么**（前端也有同一份说明），而不是让人只看到「加不进去」。

### 6. 协商是必经环节，且「达成金额」全库只有一个出处

状态机：`DRAFT → SUBMITTED → NEGOTIATING → AGREED → SETTLED`（终态）/ `REJECTED` / `WITHDRAWN`。

- 提交前必须至少一条明细且金额 > 0
- `SUBMITTED → NEGOTIATING` 必须已有协商记录
- `NEGOTIATING → AGREED` 必须有一条 `type='AGREEMENT'` 且 `agreed_amount > 0` 的记录，
  且单头的达成金额**必须等于**那条记录里的金额（不许在单头上另填一个数）
- 协商记录的序号由 **DB 排**（调用方填序号就等于让它变成可以改的东西）

⇒ 「协商成多少」在库里永远只有一个答案。

### 7. 登记赔付幂等

重复登记赔付返回**同一条**分录，不新建第二条（重复冲减是资损事故）。
DB 上另加部分唯一索引 `supplier_claim_one_confirmed_settlement ... WHERE settlement_status='CONFIRMED'`
保证一张索赔最多一条已确认分录（并发下靠应用层先查一次会双双通过）。

### 8. 候选集合是「发起索赔」的唯一入口素材

新增 `GET /trade/supplier-claims/candidates`：已判定 × 已归因 × 环节可索赔 × 尚未被索赔过。
界面**不让人手填**质检结论 id 与责任判定 id —— 手填等于让「哪批货被报上去了」由人凭记忆拼出来。
候选行自带基数与上限（后端算好的推导值）。

---

## 文件清单

**后端（新增）**

- `backend/services/trade/src/main/resources/db/migration/V188__supplier_claim.sql` — 五表 + 8 个守卫触发器 + 索引 + RLS + GRANT
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierClaimModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierClaimService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/SupplierClaimController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierClaimIntegrationTest.java`（24 例）
- `backend/services/trade/src/test/java/com/starcon/trade/SupplierClaimTenancyIntegrationTest.java`（4 例）

**前端（新增）**

- `apps/web/src/components/aftersales/supplier-claim-model.ts`
- `apps/web/src/components/aftersales/supplier-claim-model.test.ts`
- `apps/web/src/components/aftersales/supplier-claim-workspace.tsx`
- `apps/web/src/app/aftersales/returns/supplier-claims/page.tsx`
- `apps/web/src/app/api/trade/supplier-claim-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts` — **新增一条独立 services[] 元素**（不并进既有 trade 长正则，多车道并入 git 必冲突）
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json` — codegen 产物（**零删除**）
- `docs/product/capability-blueprint/module-progress.md` — 只改 11.2.3.3 那一行的「状态」「证据」两格

---

## 验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译闸 | `cd backend && ./mvnw -q compile` | EXIT=0 |
| L1 模块闸 | `./mvnw -pl services/trade -am test -Dtest='SupplierClaim*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | `Tests run: 28, Failures: 0, Errors: 0` BUILD SUCCESS |
| 契约重生成 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | 3101 DTOs；六服务 schemas 合计 3075 → 3101，**删除集为空** |
| 前端 allowlist 全量回归 | `pnpm vitest run apps/web/src/app/api/trade` | **42 文件 / 224 例全绿**（改了共享 api-proxy.ts，必须全量） |
| 前端单测 | `pnpm vitest run apps/web/src/components/aftersales/supplier-claim-model.test.ts` | 8 例全绿 |
| 类型 | `pnpm typecheck` | 3/3 successful |
| Lint | `pnpm lint` | 3/3 successful（余下均为其它文件的既有 warning） |

**多租户三断言**（实跑本模块五张表，连接池压到 1）：

1. 两租户互不可见 —— 列表 / 候选 / 按 id 直达 / 写入口四路都拦
2. 伪造 `X-Tenant-Id` 头无效 —— 双向各验一次；**且在 RLS 上直接验证**：
   绑定 A 租户后 B 的行读不出来，**即使不带 `tenant_id` 谓词**也读不到
3. 连接池归还后不串租户 —— 交替借还 3 轮，协商金额 / 冲减金额 / 财务单据号三项全不串

---

## 后续待办

- [ ] **12 财务（finance 服务）落地后**：冲减应付需经授权 API / event 交给 finance 记账，
      trade 侧 `supplier_claim_settlement` 退化为「已确认事实」的对账凭据。当前 trade 侧
      自有事实记录**不是**财务分录，不可直接当应付冲减凭据使用。
- [ ] **6.5.1 采购对账交付后**：索赔需要进采购对账单（跨服务只允许走授权 API / event）。
- [ ] **11.2.3.2 转可售/残次/报废未交付**：本模块不消费质量状态指令；
      报废损耗与残值处理仍属 12 财务，未交付。
- [ ] **供应商主数据在 catalog 独立库**：本模块只引用 trade 侧镜像 `quality_supplier_ref`；
      若 catalog 侧供应商 id 口径变更，需同步器按同形状回写。
