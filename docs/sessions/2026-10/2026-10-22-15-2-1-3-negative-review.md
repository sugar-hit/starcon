# 2026-10-22: 15.2.1.3 差评处理（crm）

## 摘要

在 crm 服务（端口 8086，库 `starcon_crm`）落地蓝图 15 卷 **15.2.1.3 差评处理**：
差评专项处理（标记原因 / 判定是否申诉 / 联系买家解决后请求修改 / 跟踪解决率）。
新增迁移 `V302__crm_negative_review.sql`（4 张新表）、15 个端点
`/crm/negative-reviews/**`、前端页面 `/crm/negative-reviews` + 工作区、
api-proxy 新增一条独立 `services[]` 元素（首 token `negative-reviews`），
以及 3 个后端测试类 + 2 个前端测试文件。

## 决策 / 结论

1. 🔴 **蓝图加粗句落成服务端强制的状态机，不是页面提示**
   （「解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额」）：
   处理单下**没有** `outcome='SUCCEEDED'` 的 `CONTACT_BUYER` 动作时，
   请求修改 / 建申诉任务一律被服务端拒绝并给出**明确原因**，且**零行写入**。
   一次 `FAILED` 的联系**不算**走完 —— 买家没被联系上就请求修改，正是加粗句警告的同一种错。

2. 🔴 **「原因分类 + 是否可申诉」真的可配**：规则表两种作用域
   —— `PLATFORM`（逐平台：差评阈值、平台是否支持申诉、是否必须先联系买家、申诉配额与周期、申诉时限）
   与 `CAUSE`（逐「平台 × 原因」：原因名 / 说明 / 该原因能不能申诉 / 判定依据模板 / 是否要求证据）。
   处理单上落**判定结果 + 判定依据 + 判定时刻 + 规则版本快照**：只给结论不给依据，这条判定永远无法复核。
   **一个平台只有一条策略规则**、**一个（平台 × 原因）只有一条原因规则**（DB 唯一索引）——
   两条并存时「这条差评按哪条规则判的」答不了。

3. 🔴 **平台侧未接入 ⇒ 零行写入，绝不写假回执**：请求修改走既有 `PlatformMessageSendPort`，
   申诉提交走新增的 `PlatformNegativeReviewPort` + `UnavailablePlatformNegativeReviewPort`。
   两条路径明确 **409**；申诉状态**不会**变成「已提交」、**没有**平台回执。
   运营在平台侧**人工提交**后，用「登记已提交 / 登记平台判定（通过 / 驳回）」回填
   **人观察到的真事实**（与 15.1.1.2 消息发送的人工交接同一手法）。

4. 🔴 **申诉配额是有限资源**：按（租户 × 平台 × 周期）计数，**建申诉任务即占用**、撤回即归还，
   配额用尽时明确拒绝。周期 MONTHLY / QUARTERLY / YEARLY / NONE 各自算周期键。

5. 🔴 **11 售后未交付 ⇒ 只落转交意图**：`after_sales_reason` 必填，
   而表里**刻意没有「售后单据 id」这一列**（下游没交付就没有 id；编一个会让「已转售后」看起来发生过）。
   测试用 `information_schema` 断言不出现任何售后单据 id 列。

6. 🔴 **解决率由服务端算**：分母 = 本模块处理单数、分子 = `resolution_status='RESOLVED'` 的处理单数，
   两个口径随响应一起给出（`resolutionRateBasis` / `resolutionRateNumerator`），**前端零算术**。
   **不建「解决率汇总表」** —— 落库的汇总会变成第二份事实。

7. **三张表的分工**：处理单主体（状态 / 原因 / 解决状态）/ 动作流水（每一步 + 操作者 + 时刻 + 结果）/
   申诉任务（类型 / 配额计数 / 提交跟踪 / 结果）—— 外加规则表（可配的判定依据来源）。

8. **命名空间刻意独立**：`/crm/negative-reviews` 不挂到 `crm/reviews`（15.2.1.1 评价本体）、
   `crm/review-replies`（15.2.1.2 **公开**回复）、`crm/review-requests`（索评）、`crm/review-rules`（索评规则）之下。
   本模块的「请求修改」是**私下联系买家**，与「公开回复」是两件事 —— 两者刻意不共用表名。

9. **api-proxy 锚点纪律**：新增**一条独立** `services[]` 元素，锚点 = `review-replies`（15.2.1.2）之后，
   既有 147 条元素一字未动、也不重排。首 token 零命中**逐条实测**：抽出既有全部正则，
   对本模块每个 path shape 跑 `new RegExp(pat).test(shape)` ⇒ 命中数 0；
   兄弟车道那四条首 token 都是 `review` 的正则都是 `^` 锚定的，不会吃掉 `negative-reviews/...`。

## 关键 trade-off

- **差评阈值放进规则表而不是代码常量**：业务口径是「rating ≤ 2 视为差评」，但那是**口径**不是宇宙真理 ——
  不同平台的星级语义不同，阈值必须能按平台调；且改了要能查清「这条单当时按哪个阈值判的」
  ⇒ 处理单上落阈值快照。代价是**没配策略就建不了处理单**（页面据此提示先配置规则）。
- **候选差评清单与处理单清单分成两个端点**：`GET /crm/negative-reviews` 是处理单，
  `GET /crm/negative-reviews/candidates` 是**还没建处理单**的差评（来自 15.2.1.1 的评价列表）。
  没有后者，第一张处理单永远建不出来。代价是页面上要维护两段列表。
- **三个测试类共用一个 PG 容器**：本仓既有做法是每个集成测试类各起一个容器（10.3.2.2 踩过
  「第一条记录就报权限不足」的序列授权坑），但本车道三个类跑的是同一批表 ⇒ 共用一个容器，
  断言强度不变（角色仍是 `NOSUPERUSER NOBYPASSRLS`，表仍是 FORCE RLS 的真实表），
  代价是类之间必须自己清干净（每个 `@BeforeEach` 都 TRUNCATE）。
- **不建「转售后单据」列而不是建了留空**：列存在就会有人往里写 id，
  而下游没交付就没有 id。少一列比多一列空列安全。

## 涉及文件

- `backend/services/crm/src/main/resources/db/migration/V302__crm_negative_review.sql`（新增）
- `backend/services/crm/src/main/java/com/starcon/crm/NegativeReviewModels.java`（新增）
- `backend/services/crm/src/main/java/com/starcon/crm/NegativeReviewService.java`（新增）
- `backend/services/crm/src/main/java/com/starcon/crm/NegativeReviewController.java`（新增）
- `backend/services/crm/src/main/java/com/starcon/crm/PlatformNegativeReviewPort.java`（新增）
- `backend/services/crm/src/main/java/com/starcon/crm/UnavailablePlatformNegativeReviewPort.java`（新增）
- `backend/services/crm/src/test/java/com/starcon/crm/NegativeReviewTestSupport.java`（新增）
- `backend/services/crm/src/test/java/com/starcon/crm/NegativeReviewIntegrationTest.java`（新增）
- `backend/services/crm/src/test/java/com/starcon/crm/NegativeReviewTenancyIntegrationTest.java`（新增）
- `backend/services/crm/src/test/java/com/starcon/crm/NegativeReviewRulesTest.java`（新增）
- `packages/api-contracts/src/contracts/crm.ts`（codegen 产物，27 个新类型）
- `apps/web/src/lib/api-proxy.ts`（新增一条 `services[]` 元素）
- `apps/web/src/components/crm/negative-review-model.ts`（新增）
- `apps/web/src/components/crm/negative-review-model.test.ts`（新增）
- `apps/web/src/components/crm/negative-review-workspace.tsx`（新增）
- `apps/web/src/app/crm/negative-reviews/page.tsx`（新增）
- `apps/web/src/app/api/crm/negative-reviews-allowlist.test.ts`（新增）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 15.2.1.3 |` 那一行的状态与证据）

## 验证

- L0：`backend && ./mvnw -q compile` 通过；`pnpm typecheck` 3/3 通过。
- L1 后端：`./mvnw -pl services/crm -am test -Dtest='NegativeReview*'`
  ⇒ **42 例全绿**（25 + 5 + 12）。
- L1 前端：`pnpm vitest run apps/web/src/components/crm/negative-review-model.test.ts
  apps/web/src/app/api/crm/negative-reviews-allowlist.test.ts` ⇒ 19 例全绿；
  `pnpm vitest run apps/web/src/app/api`（**全量 allowlist 回归**）⇒ **177 文件 / 1024 例全绿**。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  （6 个服务的 spec 全部**从本车道基线重新导出**，未复用集成头的产物）。

## 后续待办

- **平台差评申诉 / 修改申请适配器**（归 channel，模块 2）：接上后
  `UnavailablePlatformNegativeReviewPort` 换成真实现即可，服务层与前端一行都不用改。
- **买家站内信发送适配器**（归 channel，模块 2）：接上后「联系买家 / 请求修改」可走
  `transport=PLATFORM_API`，人工交接路径保留。
- **11 售后交付后**（模块 11）：本模块的转交意图才有下游单据可挂；届时再加列并补触发器，
  **不要**现在就把 id 列补上。
- **`crm_negative_review_case` 的分母口径**若将来要跨模块对比（例如与售后单据联表），
  需要先确认口径是否一致 —— 当前口径是「本模块的处理单数」。
