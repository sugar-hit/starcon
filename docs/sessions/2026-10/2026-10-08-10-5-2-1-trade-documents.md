# 2026-10-08: 10.5.2.1 贸易单证落地

## 摘要

在 worktree `.worktrees/10.5.2.1`、分支 `feat/10-5-2-1`（基线 `9346c68`）实现能力蓝图 L4 模块 **10.5.2.1「贸易单证」**（L3 锚点 10.5.2，阶段 D7）。新增迁移 `V91__warehouse_trade_documents.sql`（9 张表，全部 `tenant_id NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY`）、`TradeDocumentModels/Service/Controller.java` 三件、`/warehouse/trade-documents` 端点组、前端 `/logistics/trade-documents` 工作区与 api-proxy 白名单分支。

蓝图原文（`docs/product/capability-blueprint/10-logistics-and-freight.md:237-242`）的加粗句「FTA 优惠原产地证能显著降低关税，其适用条件（尤其是"实质性改变"）需要业务确认，**系统只负责按规则校验与留档**」是本模块的硬边界，实现方式见下。

## 决策 / 结论

### 1. 「实质性改变」：不实现任何判定逻辑

**这是本模块最核心的边界。** 系统回答「有没有人确认过、依据是什么、什么时候」，**不回答**「法律上是否成立」。落法是四件事，每件都落库：

1. **规则可配置**：`trade_origin_preference_rule.substantive_change_criterion` 存**人读条件描述**（不是可执行表达式）+ `requires_business_confirmation DEFAULT true`。做成可执行表达式等于让运营在无评审的情况下定义报关规则。
2. **只校验流程事实**：校验的是「有没有 `confirmed_at`/`confirmed_by`」「确认依据是否非空（≥5 字）」，不是法律事实。
3. **留档**：单证上落 `substantive_change_status/note/business_confirmed_at/by/preference_rule_version` 并进事件时间线。
4. **显式标出待确认**：DB 触发器 `guard_trade_3_preference` 钉死「主张优惠但无业务确认 → 不许进 `APPROVED`/`SUBMITTED`/`ACKNOWLEDGED`」，同时写一条 `reminder_type='UNCONFIRMED_SUBSTANTIVE_CHANGE'` + `severity='BLOCKER'` 的提醒。

`CONFIRMED` 的语义在代码注释里明确写死：**「有人留痕确认过」，不代表系统判定法律上成立**。本模块不存在（也不允许新增）任何自动置为 `CONFIRMED` 的代码路径，唯一入口是 `confirmPreference`。

### 2. 「待业务确认」刻意不是「通过」

`CheckResult` 枚举单列 `PENDING_BUSINESS_CONFIRMATION`（不塞进 `PASS`）。DB CHECK 钉死 `result<>'PASS' OR (missing_count=0 AND pending_business_count=0)`。前端 `checkPassed()` 只认 `PASS`，`substantiveChangeBlocking()` 只认 `PENDING_BUSINESS_CONFIRMATION`。这样界面不会许诺「优惠已通过」这种还没人拍板的结论。

### 3. 有条件必备（`CONDITIONAL`）的条件是人口径，系统不执行

缺失时只报 `WARNING` 级提醒请人判断（`items` 里标 `CONDITIONAL_REVIEW_REQUIRED`），**不自动判成必备或可省**。

### 4. 「没配规则」= 校验不通过，不是「没有要求就算通过」

没有定义要求就无法断言齐不齐。此场景 `required` 与 `missing` **同时 +1**（同时满足 `satisfied+missing<=required` 的 DB CHECK）。

### 5. 认证要求计入 `required`

单证要求里 `requires_certification=true` 的行会计入必备数；认证缺失/过期报提醒。这是调试中踩到的坑 —— 初版没计入，导致 `satisfied+missing<=required` 的 CHECK 被违反。

### 6. 来源判据用状态机，不用时间戳

`submitted_at` 一旦打上就**永不消失**（被下游退回的单也留着 —— 「这套备证资料曾经发出去过没有」是举证材料）。用它判「是否已报关」会把退回草稿的单错判成已报关，所以 `createSet` 拒绝 `DRAFT/PENDING_REVIEW/REJECTED/CANCELLED` 的报关单。集成测试有专门的 `draftSourceDeclarationIsRejectedByStatusNotTimestamp` 一例。

### 7. 校验结论 append-only、缺失提醒是可处理工作项

两者分表：`trade_document_check` 不改不删（历史结论是举证材料）；`trade_document_reminder` 可 `RESOLVED`/`DISMISSED` 但处理痕迹必须留痕（`resolution_note` 非空）。重跑校验时把上一轮不再报的提醒**显式自动结清**（带说明与处理人）—— 否则历史阻断会永久挡住已补齐的单证包。

### 8. 单证要求 / 模板 / 优惠规则全部真实落库、租户可维护

写死成 Java 常量或 CHECK 词表等于把海关要求抄成第二份且必然漂移。前车之鉴：V80 的风险承担方枚举抄了一份后静默漂移。

### 9. 16 合规未交付的处理

`backend/services/` 只有 `catalog / channel / identity / trade / warehouse`，**无 `product_compliance` 服务**。处理（照 10.5.1.1 / 10.5.1.3 先例）：

- **不建** 16 合规模像表（会成双 writer）。
- 建租户可维护的 `trade_certification_status`，每行带 `certification_source`（`TENANT_MAINTAINED` / `MODULE_16`）+ `source_reference` 如实标注来源。
- capabilities 里 `COMPLIANCE_CERTIFICATION` 显式 `available=false` + `ownerModule="16 合规"` + `href`。
- 前端「认证状态」页签可维护，来源标注为「租户自维护」。

16 合规交付后把 `certification_source` 换成 `MODULE_16` 就能接上，不必在本模块再抄一份认证状态。

### 10. 零跨车道 DDL 回归

V91 只新建表，**不 ALTER 任何既有表**；对 V85（报关单）/ V90（关税）只读 + 建在复合唯一键上的外键。

### 11. 金额标度口径

Java `setScale(2,HALF_UP)` 得到 `2000.01`；列是 `NUMERIC(19,4)`，读回是 `2000.0100` —— 值相同、标度由列宽决定，两处口径不冲突（测试同时断言两者）。

## 涉及文件

### 后端（新增）
- `backend/services/warehouse/src/main/resources/db/migration/V91__warehouse_trade_documents.sql`（9 表 + 9 个约束触发器）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/TradeDocumentModels.java`（枚举 + ~60 个 record DTO）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/TradeDocumentService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/TradeDocumentController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/TradeDocumentIntegrationTest.java`

### 前端（新增）
- `apps/web/src/components/logistics/trade-document-model.ts`
- `apps/web/src/components/logistics/trade-document-model.test.ts`（45 例）
- `apps/web/src/components/logistics/trade-document.tsx`
- `apps/web/src/app/logistics/trade-documents/page.tsx`
- `apps/web/src/app/api/warehouse/trade-document-allowlist.test.ts`（9 例）

### 前端（修改）
- `apps/web/src/lib/api-proxy.ts`（在 `duty-taxes` 之后**纯插入** `trade-documents` 分支，求并，未改动任何既有分支）

### 生成物
- `packages/api-contracts/src/catalog.ts`（codegen，新增 **59 个 `Trade*` DTO**，共 1272 条，**重名检查 0 冲突**）
- `packages/api-contracts/src/warehouse.openapi.json`
- `docs/product/capability-blueprint/module-progress.md`（第 355 行 + 4 行派生汇总计数）

### 会话文档
- 本文件

## 关键 trade-off

| 选择 | 放弃的 | 原因 |
|---|---|---|
| 「实质性改变」只做校验与留痕 | 自动判定 | 蓝图加粗句是硬边界；自动判定等于替业务做法律判断 |
| 缺失提醒自动结清 | 历史提醒永久留档 | 否则已补齐的单证包被历史阻断永久挡住；结清走显式留痕，不静默消失 |
| 条件描述存文本不存表达式 | 可自动判定 `CONDITIONAL` | 报关规则的执行权不能在无评审下交给运营 |
| 认证状态登记表（非 16 合规模像表） | 单一真相源直连 | 16 合规未交付；建镜像表会成双 writer，交付后必然漂移 |

## 验证

全部命令在 worktree `/Users/tommy/Project/starcon/.worktrees/10.5.2.1` 下执行。

| 命令 | 结果 |
|---|---|
| `cd backend && ./mvnw verify` | **BUILD SUCCESS**（05:53），identity 46.9s / catalog 1:27 / channel 7.7s / warehouse 3:27 / trade 3.1s |
| `TradeDocumentIntegrationTest` | **37 例全绿**（Testcontainers PG16 + fake identity + Hikari 池压到 1） |
| `OpenApiContractTest`（warehouse） | 1 例绿 |
| `pnpm typecheck` | 3/3 包绿 |
| `pnpm lint` | 绿（仅他模块既有 warning） |
| `pnpm test` | **95 文件 / 1761 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功，`/logistics/trade-documents` 16.9 kB 进产物 |
| `node tools/module-progress/sync.mjs --check` | `✓ 台账与蓝图一致` |

**PG 容器**：verify 期间同时存活 **2** 个（Testcontainers 自己的 + 预先存在的 `starcon-mig-v2`）；verify 跑完后本轮的已被 ryuk 回收，`docker ps --filter "ancestor=postgres:16-alpine"` 只剩 `starcon-mig-v2`（Up 30 hours，非本车道产生）。

## 后续待办

1. **16 合规交付后**接上认证状态：把 `trade_certification_status.certification_source` 的写入源换成 `MODULE_16`，并把 capabilities 的 `COMPLIANCE_CERTIFICATION` 改成 `available=true`。
2. **单证文件本体渲染**（模板变量 → PDF/文件产出）当前是「内容快照 + 状态」模型，`content` 是 jsonb；真正的文件渲染（`trade_document_file`）未做，属后续独立模块。
3. **报关行/目的仓回执**（`ACKNOWLEDGED` / `SENT_BACK`）目前是人工登记端点；等 10.5.1.1 的 `customs_submission` 打通后可以改为自动回写。
