# Batch 50 —— 8.2.2.2 待检处理 + 16.4.2.2 授权凭证

> 并行车道流水线 · 批次 50 · 2026-10-21（13:00 tick）
> 集成头 `integration/module-dev` · 本批 2 条车道回收并入
> 批次号接在 batch-49 之后（`docs/sessions/2026-10/` 下现有最大为 batch-49，写入前已复核无重号）

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道分支 | 车道 head | 并入 commit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8.2.2.2 | 待检处理 | D3 | warehouse | `V252__pending_inspection_disposition.sql` | `feat/8-2-2-2` | `fa629f98` | `9f14af5a` |
| 16.4.2.2 | 授权凭证 | D7 | channel | `V255__brand_authorization_credential.sql` | `feat/16-4-2-2` | `9ed97ebb` | `5c9a06a8` |

**同批两条车道互不依赖**：8.2.2.2 落在 warehouse / 卷 08，16.4.2.2 落在 channel / 卷 16，
L3 锚点不同（8.2.2 / 16.4.2）、迁移版本不撞号（V252 / V255）、
api-proxy 白名单各自新增**独立** `services[]` 元素（不往既有巨型正则里并分支）⇒ 合并无冲突。

---

## 模块 8.2.2.2 待检处理

**这个功能做什么**：管理**待检区**的库存 —— 收货进来的货先压在待检区，
要发起质检任务（走 6 质检单）、录入质检结果、然后**处置**这批货：
质检通过转可售、不通过转残次 / 报废 / 退货。
最关键的一点是：**「转可售」不是把单据状态改一改，而是账上真的发生一次库存流转**，
可售量随之变化 —— 这正是 6.4.2.1 质检单交付时点名缺的那个「warehouse 侧写端点」。

**技术落地**：

- 迁移：`backend/services/warehouse/src/main/resources/db/migration/V252__pending_inspection_disposition.sql`
  — 3 张新表（`pending_inspection_task` 待检处理单 / `pending_inspection_disposition` 处置明细 /
  `pending_inspection_event` 事件台账），**零 ALTER 任何既有表**；
  `tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + `isolation` 策略 +
  `warehouse_app` GRANT / `starcon_app` REVOKE；发号序列 `pending_inspection_task_code_seq` 单独授权。
- 端点：`/warehouse/pending-inspections/**` — `capabilities` / `stock` / `options` / 集合（GET 真分页 + POST 建单）/
  `{id}` 详情 / `{id}/request-inspection` / `{id}/record-result` / `{id}/dispose` / `{id}/cancel`。
  **刻意无 PUT / DELETE** —— 处置完的凭据不可删。
- 页面：`/warehouse/pending-inspections`（真实工作区，非占位页）——
  待检库存清单与处理单列表**分开**、**待检品不可售**常驻、**质检三量只展示不输入**、
  处置表单四个去向（报废 / 退货标「离仓」且不要目标库位）、提交前两道自检、
  详情页把质量状态变更分录 / 事件台账 / 处置去向 / 处置前后可售量快照 / 分录净额并排摊开。
- 测试：`PendingInspectionIntegrationTest` **16 例**（Testcontainers 真实 PG 16）+
  `PendingInspectionTenancyIntegrationTest` **3 例**（租户隔离三断言）= 后端 **19 例**；
  前端 `pending-inspection-model.test.ts` **10 例** + `pending-inspection-allowlist.test.ts` **8 例** = **18 例**。

**关键设计决策**：

1. 🔴 **质量状态在账上的形状是「一对反向分录」，不是「改一列」**。
   V1/V2 的分层把质量状态放进 balance / bucket 的键里，所以「转可售」= 待检 balance −Q + 可售 balance +Q
   （bucket 同步减 / 增）⇒ **不新增任何库存数量表**，复用 V1 已有的 `inventory_transaction` + `inventory_entry`
   （append-only；一次处置 = 一条 transaction + N 条分录，ref_type=PENDING_INSPECTION，
   `UNIQUE(tenant_id,ref_type,ref_id)` 顺带给整张单幂等）。
2. 🔴 **质检单（6.4.2.1）归 trade 所有，warehouse 只做跨服务调用**。
   不在本地建质检单表、不跨库读写（AGENTS.md 模块边界纪律），只留逻辑 ID 与单号快照；
   发起质检经 trade 自己的授权端点 `POST /trade/quality-inspections`（转发调用方自己的 bearer，
   租户仍由 trade 按已认证成员关系解析 ⇒ RLS 两道）；trade 不可达一律 503，**绝不冒充「没有质检单」**。
3. 🔴 **质检结论只有一个来源**：录入结果时**不接受调用方自报数量**，三量一律从 trade 的质检单回读
   并冻结成快照 —— 让操作员再敲一遍数字等于让同一个结论在两个服务各有一份，迟早对不上。
4. 🔴 **四条不变量交给数据库裁决**（回读真正的账本分录，不是单据上的自报数字）：
   P1 转出量 == 任务批量；P2 转入量（**正向**分录之和）== 批量 − 离仓量；
   P3 每个目标余额行的转入量 == 指向它的明细量之和（**双向**比）；
   P4 目标余额行的质量角色必须与处置类型一致、来源余额行必须是待检。
   绕过服务层直连 SQL 也绕不过（集成测试实跑断言）。
5. 🔴 **目标库位类型复用 8.1.2.2 的权威函数** `location_allocation_required_type(role)`，
   不另抄一份 CASE —— 抄一份就会改一处忘一处，而「残次品混进非残次库位」是蓝图点名的硬规则。
6. 🔴 **同一 bucket 同时只能有一张未完结的待检处理单**（部分唯一索引），
   否则同一批货能被处置两次。
7. 🔴 **不合格品不能转可售**：转可售的量受质检结论里的可售量（合格量 + 让步接收量）上限约束，
   服务层与数据库层各挡一道。
8. **报废 / 退货按「离仓」建模**（只有负向分录，没有目标质量状态与目标库位）：
   `inventory_transaction.kind` 词表里没有「报废」，整单只做退货时取 `kind='RETURN'`，
   其余取 `'ADJUSTMENT'` 并在 notes 里写清构成。
9. **未交付项如实标 `available=false` + `ownerModule` + 影响说明**：
   6.4.2.2 不良品处理（退换货补货 / 返修 / 索赔归它，本模块只把不合格量分流出去）、
   6.1.1.3 供应商评级、6.5.1 采购对账。
10. **api-proxy 纯增量**：新增一条独立 `services[]` 元素（`prefix: 'warehouse/'` + 自己的 pattern，
    插在数组最末尾），**未**往既有 warehouse 巨型正则里并分支。

---

## 模块 16.4.2.2 授权凭证

**这个功能做什么**：集中存储并核验**品牌授权凭证** —— 谁签的、签了哪一版、文件是哪一份、
**这份文件现在还是不是原件**。并能**一键核验**：把文件本体读回来重算摘要，跟登记摘要逐字比，
给出「通过 / 摘要不符 / 文件丢失 / 已过期 / 还没生效 / 签署方不全 / 没登记文件 / 已撤销」八种结论。
凭证是**争议时是唯一证据**的那份东西，所以它必须活得比它引用的授权更久。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V255__brand_authorization_credential.sql`
  — **纯新建 4 张表、零 ALTER 任何既有表**：`brand_cred_credential`（凭证档案）/
  `brand_cred_signatory`（签署方）/ `brand_cred_document`（文件版本，**只追加**，含 sha256 摘要）/
  `brand_cred_verification`（核验记录，**追加式凭据**）；
  均 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + `isolation` 策略 +
  `channel_app` GRANT / `starcon_app` REVOKE。
  表前缀 `brand_cred_*` 与 16.4.2.1 的 `brand_authz_*`、16.2.2.2 的 `product_certification_*`、
  16.2.2.1 的 `compliance_check_*`、4.2.3.2 的 `listing_compliance_*` 以及
  **catalog 服务自己库里的 `brand_authorization`（3.2.1.2）** 五个命名空间彼此隔离。
- 端点：`/channel/brand-credentials/**` — `capabilities` / `overview` / `credentials`（GET 真分页 + 真筛选、POST 登记）/
  `credentials/{id}`（GET / PUT）/ `credentials/{id}/revoke`（必写理由）/
  `credentials/{id}/verify`（🔴 一键核验）/ `credentials/{id}/signatories`（GET + POST）/
  `credentials/{id}/signatories/{id}`（DELETE）/`credentials/{id}/documents`（GET + POST）/
  `credentials/verify-batch`（批量，一次最多 50 个）/ `documents/{id}` / `documents/{id}/file` /
  `verifications` / `authorizations`。
- 页面：`/compliance/brand-credential`（真实工作区）—— 概览九张卡 + 台账 + 一键核验 + 详情 +
  登记 / 修改 / 撤销 + 签署方 + 文件版本清单带 sha256 摘要 + 核验记录。
- 测试：`BrandCredentialRulesTest` **19 例**（纯函数）+ `BrandCredentialIntegrationTest` **22 例**
  （Testcontainers 真实 PG 16）+ `BrandCredentialTenancyIntegrationTest` **3 例**（租户隔离三断言）
  = 后端 **44 例**；前端 `brand-credential-model.test.ts` **24 例** + `brand-credential-allowlist.test.ts`
  **5 例** = **29 例**。

**关键设计决策**：

1. 🔴 **与上游 16.4.2.1 的交接面**：16.4.2.1 交付时明确留下三样东西 ——
   ① `brand_authz_document` 只存引用与元数据、**文件本体不入库**，原件的归宿当时是空的；
   ②「真伪验证记录 / 文件版本 / 签署方信息 / 一键核验」四样它**明确没做**；
   ③「`brand_authz_document` 的写入所有权可以移交过来」。
   本模块的决策是**不移交**、两边**各自独立**：
   - 移交要 ALTER 16.4.2.1 的既有表（加 NOT NULL 列会直接打挂它已交付的 INSERT 路径 ——
     本仓纪律是「加列必须带 DEFAULT 或可空」），且「凭证版本」这个概念挂在授权下面本来就不对；
   - **凭证必须活得比它引用的授权更久** ⇒ 凭证侧**不建**指向 `brand_authz_*` 的外键，引用一律用**逻辑 id**。
2. 🔴 **文件本体的存储与原始文件保留是本模块的责任** —— 不是「什么都不做」，
   也不是「16.4.2.1 已经存好了」。上传时真写受管存储（`LocalCredentialDocumentStorage`，
   路径穿越防护 + 键里带租户段），核验时真读回来。DB 只存句柄 + 元数据 + sha256 摘要。
3. 🔴 **「真伪」的判据是文件摘要**：核验把受管存储里的文件本体读回来**重算 sha256**，
   与登记摘要逐字比对；不符 ⇒ `DIGEST_MISMATCH`；文件不在 ⇒ `FILE_MISSING`（**不是「通过」**）。
   这不是「连品牌方服务器验签」（那需要品牌方提供接口，不在本模块范围内），
   但它**本地可复算、可审计、争议时拿得出来**。
4. 🔴 **结论八态**：`VERIFIED` / `DIGEST_MISMATCH` / `FILE_MISSING` / `EXPIRED` / `NOT_YET_EFFECTIVE` /
   `SIGNATORY_INCOMPLETE` / `NO_DOCUMENT` / `REVOKED`；`conclusion` 取**最严重**的那一条，
   其余作为逐条原因一并给出（只报最严重的会让运营以为「修好这一条就没事了」）。
5. 🔴 **「没核验过」(UNVERIFIED) ≠「核验过但不通过」(FAILED)** —— 两个状态在概览、列表、筛选里都分开；
   把「还没查」显示成「假的」是误伤，把「假的」显示成「还没查」是放过。
6. 🔴 **「通过但有提醒」不是「不通过」**：签署方身份未人工核验是 **WARN** 级（`SIGNATORY_UNVERIFIED`），
   而**一个签署方都没登记**（`SIGNATORY_INCOMPLETE`）才是 FAIL —— 把前者算成不通过会让「一键核验」天天红。
7. 🔴 **核验记录 append-only**（触发器 `guard_brand_cred_verification_immutable` 钉住
   conclusion / severity / passed / rule_version / evaluated_at / document_id 事后改写不了，
   另有 `guard_brand_cred_verification_no_delete` 不许删）；规则有版本（`rule_version`），
   改了就是**新版本 + 新一行**，旧行原样留着 ——「上次为什么这么判」永远答得出来；
   **不通过必有逐条原因**（DB 层**具名** CHECK `brand_cred_verification_reasons_required` 兜底 ——
   匿名 CHECK 的报错只会说 check2，读报错的人根本不知道是哪条口径被违反）。
8. 🔴 **凭证文件版本只追加**（触发器 `guard_brand_cred_document_append_only` 拒绝 UPDATE / DELETE）——
   续期 / 换件是「再上传一份」，原来交的是什么不会消失。
9. **撤销必须写明理由且撤销是终态**（不提供「恢复」）；**凭证不做物理删除**（端点里没有 DELETE 凭证）。
10. **上游 16.4.2.1 是同库只读**（同属 channel 服务、同一个库）：只读 `brand_authz_authorization` 拿
    「授权档案候选」，凭证通过逻辑 id 挂在它下面，**不写**它的任何一张表。
11. **当前文件版本与最近核验状态不落库，由读出口现算** —— 存一列就要一个任务去维护它，
    维护出来那天与算出来那天差一天时「这张凭证到底核验过没有」会答错。
12. **api-proxy 纯增量**：新增一条独立 `services[]` 元素（`brand-credentials` 前缀），
    **不并**既有 channel 巨型正则。

---

## L2 批量验证（集成头，全量）

被验证的树 = 本批合并后的集成头 `5c9a06a8`（含 8.2.2.2 + 16.4.2.2），
**13:32:26 → 14:10:58**（38 分 32 秒），本轮**唯一**一次发热高峰。

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | ✅ **EXIT=0 · BUILD SUCCESS** —— 8 模块 reactor，**36:50 min**；6 个有测试的服务模块合计 **6922 例全绿**（identity 270 / catalog 1981 / **channel 1244** / **warehouse 1588** / trade 1769 / crm 70） |
| 类型 | `pnpm typecheck` | ✅ EXIT=0 |
| Lint | `pnpm lint` | ✅ EXIT=0（仅既有 `react-hooks/exhaustive-deps` warning，无 error） |
| 单测 | `pnpm test` | ✅ EXIT=0 —— **383 个文件 / 5831 例全绿** |
| 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ EXIT=0 |
| 台账 | `node tools/module-progress/sync.mjs --check` | ✅ EXIT=0（台账与蓝图一致，465 行无重复 ID） |

**本批两个模块的测试在 L2 里实跑全绿**（不是只跑 L1）：

- **8.2.2.2 待检处理**：`PendingInspectionIntegrationTest` 16 例 +
  `PendingInspectionTenancyIntegrationTest` 3 例 = **19 / 19 绿**（warehouse 1569 → **1588**）
- **16.4.2.2 授权凭证**：`BrandCredentialRulesTest` 19 例 +
  `BrandCredentialIntegrationTest` 22 例 + `BrandCredentialTenancyIntegrationTest` 3 例
  = **44 / 44 绿**（channel 1200 → **1244**）

**两处交叉验证（说明计数可信，不是把历史残留累加）**：

1. **后端增量与本批精确吻合**：batch-49 的 L2 是 **6859 例**，
   本轮 **6922 例** ⇒ **+63 例** = 19（warehouse）+ 44（channel），
   **不多不少**。
2. **前端增量与本批精确吻合**：batch-49 的 L2 是 **379 文件 / 5784 例**，
   本轮 **383 文件 / 5831 例** ⇒ **+4 文件 / +47 例**，
   正好是本批新增的 4 个前端测试文件
   （`pending-inspection-model.test.ts` 10 + `pending-inspection-allowlist.test.ts` 8 +
   `brand-credential-model.test.ts` 24 + `brand-credential-allowlist.test.ts` 5 = **47 例**）。

---

## 静态预检（本批新迁移）

| 模式 | 命中 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **0** |
| ② `SET NOT NULL` | **0** |
| ③ `ADD CONSTRAINT ... CHECK` | **0** |

两条迁移都是**纯 CREATE**（V252 = 3 张新表 + 索引 + RLS(FORCE) + 发号序列 + GRANT/REVOKE；
V255 = 4 张新表 + 索引 + RLS(FORCE) + 3 个守卫触发器 + GRANT/REVOKE），
**零 ALTER 任何既有表** ⇒ 对并行车道的既有写入路径零影响。

**共享白名单文件复核**：`apps/web/src/lib/api-proxy.ts` 本批改动为
**+96 行 / −0 行**（两条车道各新增一条独立 `services[]` 元素，**零删除**）⇒
不存在「取错一侧静默删掉别人已交付路由」的风险。

**契约产物复核**：`packages/api-contracts/src/trade.openapi.json` 本批 diff 很大（35154 行），
已逐条核对 **method + path 集合与合并前完全一致**（595 paths / 724 operations，diff 为空）
⇒ 是 codegen 的**重排**而非端点丢失。

---

## 涉及文件

**8.2.2.2 待检处理**
- `backend/services/warehouse/src/main/resources/db/migration/V252__pending_inspection_disposition.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PendingInspection{Models,Service,Controller}.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/QualityInspectionTradePort.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/PendingInspection{TestBase,IntegrationTest,TenancyIntegrationTest}.java`
- `apps/web/src/components/warehouse/pending-inspection-{model.ts,model.test.ts,workspace.tsx}`
- `apps/web/src/app/warehouse/pending-inspections/page.tsx`
- `apps/web/src/app/api/warehouse/pending-inspection-allowlist.test.ts`

**16.4.2.2 授权凭证**
- `backend/services/channel/src/main/resources/db/migration/V255__brand_authorization_credential.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/BrandCredential{Models,Rules,Service,Controller}.java`
- `backend/services/channel/src/main/java/com/starcon/channel/{CredentialDocumentStorage,LocalCredentialDocumentStorage}.java`
- `backend/services/channel/src/test/java/com/starcon/channel/BrandCredential{RulesTest,IntegrationTest,TenancyIntegrationTest}.java`
- `apps/web/src/components/brand-credential/brand-credential-{model.ts,model.test.ts,workspace.tsx}`
- `apps/web/src/app/compliance/brand-credential/page.tsx`
- `apps/web/src/app/api/channel/brand-credential-allowlist.test.ts`

**共用**
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**（+96 / −0）
- `packages/api-contracts/src/{channel,warehouse,trade,catalog}.openapi.json` + `src/contracts/*.ts` —— codegen 产物
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-pending-inspection-disposition.md` —— 车道自己的会话日志
- `docs/sessions/2026-10/2026-10-21-brand-credential.md` —— 车道自己的会话日志

## 关联

- 上一批：`docs/sessions/2026-10/2026-10-21-batch-49-brand-authorization.md`
- 本批两条车道都是 **12:00 tick 派发、13:19–13:20 落地** 的，
  由 **13:00 tick** 回收并入（见下节「本轮调度经过」）。

---

## 本轮调度经过（13:00 tick）

| 阶段 | 时刻 | 动作 |
| --- | --- | --- |
| 容量闸 | 13:00 | **GO** —— `pmset -g therm` 无降频项；load 3.25/2.62/2.59（阈值 12，10 核）；带 `org.testcontainers.sessionId` 标签的 PG 容器 **0** 个（阈值 6），无标签的孤儿容器 **0** 个；running 4 / 配额 6 |
| 节流闸 | 13:02 | **放行** —— 距上批 59 分钟 ≥ 阈值 50 分钟；通过后才写 `last-batch-at` |
| 取调度闸 | 13:02 | `gate acquire` → `gate: free` → 取得（`ageSeconds` 0 / TTL 4200 / `stale: false`） |
| 等待落地 | 13:04–13:32 | 4 条在途车道全部确认存活（心跳 + 工作区提交活动双向取证）。16.4.2.2 于 13:19:51 落地、8.2.2.2 于 13:20:23 落地 |
| 回收并入 | 13:32 | `merge-ready` → 8.2.2.2（`4ed2006a`→`9f14af5a`）、16.4.2.2（`9f14af5a`→`5c9a06a8`），**零冲突**；`sync` → **276 已交付 / 189 未开发**；`gc` → 回收 2 个 worktree |
| 静态预检 | 13:32 | 三种互相破坏形状**全部 0 命中**；api-proxy **+96/−0** |
| L2 批量验证 | 13:32–14:11 | 集成头全量 **全绿**（见上节）—— 后端 6922 例 / 前端 5831 例 / Next build / sync --check，四步 EXIT=0 |

### ⚠️ 本轮观测到的异常：两条车道的 worker 被网络故障打死（不是业务失败）

| 车道 | worker 会话 | 状态 |
| --- | --- | --- |
| 6.2.2.2 需求合并与批量 | `mvs_9306f16515504f62a0b64745e6d29b58`（re-dispatch） | **error: `net::ERR_NETWORK_CHANGED`**（errorCode 50113，agent-runtime） |
| 6.3.1.1 采购单列表 | `mvs_86ff0c5ea82e448691a34673a98857b1`（re-dispatch） | **error: `net::ERR_NETWORK_CHANGED`**（errorCode 50113，agent-runtime） |

两条都**不是** BLOCKED、也不是测试红 —— 是 agent 运行时的网络错误。
**工作区成果都还在**（6.2.2.2 已 commit 2883 行后端 + 3 个未提交测试文件；
6.3.1.1 已 commit 400 行迁移 + 6 个未提交服务 / 测试文件），
本 tick 结束时已按**续做**（re-dispatch）方式重新派发，**没有丢弃已有成果**。

> ⚠️ 这正是协议里「worker 静默早退」那类问题的**另一个变种**：不是会话静默结束，
> 而是**运行时网络错误直接终止**。取证方式：`mavis session list` 看子会话的
> `status.type == "error"` 与 `errorCode`。**心跳正常不代表 worker 活着** ——
> 判定存活要同时看心跳**和**子会话状态。

### 两条未落地车道的处置（本 tick 未并入，留待下一批）

| 车道 | 迁移 | 已完成 | 剩余 |
| --- | --- | --- | --- |
| 6.2.2.2 需求合并与批量 | V253 | 迁移 + 模型 + 服务 + 端点（2883 行，已 commit `0ebf57ce`） | 补完 3 个测试文件、L1 跑绿、契约重生成、前端工作区 + 白名单、台账、会话日志、commit、land |
| 6.3.1.1 采购单列表 | V254 | 迁移 400 行（已 commit `5dd04845`） | 补完服务 / 模型 / 端点 + 3 个测试文件、L1 跑绿、契约重生成、前端工作区 + 白名单、台账、会话日志、commit、land |

