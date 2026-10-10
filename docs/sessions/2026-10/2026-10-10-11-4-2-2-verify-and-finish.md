# 11.4.2.2 知识库与话术 —— 接手验证并收口

日期：2026-10-10
车道：`feat/11-4-2-2`（worktree `.worktrees/11.4.2.2`）
阶段：D4，L3 锚点 11.4.2

## 背景

流水线两天前因用户报告电脑过热暂停，本车道成果当时**一个 commit 都没有**。
调度器把工作区抢救成 `434939c`（16 个文件），但**从未验证过** ——
取消时 worker 正处于 V95→V100 重编号后的重跑阶段。

所以本次不是「从零写」，是**接过来、对齐新集成头、真跑验证、把不通过的修好**。

## 决策摘要

### 1. 并入集成头用 merge，api-proxy 冲突用求而不是二选一

`git merge integration/module-dev`（`f53b918`，含模块 4.2.2.2 描述模板）。
唯一冲突是 `apps/web/src/lib/api-proxy.ts` 的 catalog 白名单巨型正则行。

正则有**两层 alternation**（内层 `inventory/` 组 + 外层仓库级段）。
本车道新增的 `kb(...)` 落在**外层**，4.2.2.2 新增的 `description-templates(...)` 也落在外层，
两边改的是同一行。

**没有手改那行 15k 字符的正则**，写了脚本解析顶层 alternation：

| 版本 | 外层分支数 |
| --- | --- |
| 合并基 `e44c62c` | 36 |
| 本车道 `434939c` | 37（+`kb`） |
| 集成头 `f53b918` | 37（+`description-templates`） |
| **求并结果** | **38（36 + 两个新分支，一个没丢）** |

求并 = 取集成头那行正则 + 把 `kb` 分支插到同一锚点（`sku-merges` 之后、闭括号之前）。
另核对：6 个 service prefix 全部存活，集成头相对求并结果**只少了我那 19 行 `kb` 注释**。

`merge-union` 驱动打过 `走了行级兜底并集，请人工确认这个文件改对了` 的警告 ——
行级兜底正是「分支被静默吞掉、typecheck/lint/vitest 全绿但页面 404」的成因，所以做了上面这套结构化核对。

**已知缺陷按要求未动**：集成头上 `translation-coveragetranslations` 少一个 `|`，
使 3.4.2.2 的 `descriptions/templates/{uuid}/translations` 浏览器端 404。
属于 3.4.2.2 车道 backlog B-01，不在本车道范围。

### 2. 后端唯一的真阻塞是**残留构建产物**，不是代码

首次 `./mvnw verify`：catalog 33 个集成测试**全红**，根因

```
Message : ERROR: relation "kb_rule_version" already exists
Location: db/migration/V100__customer_service_knowledge_base.sql  Line: 90
```

V95→V100 重编号时只改了 `src/`，`target/classes/db/migration/` 里那份
**旧的 `V95__customer_service_knowledge_base.sql` 还在**，
Flyway 于是把 V95 和 V100 都跑了一遍，同一张表建两次。
`git ls-files` 确认它**不在版本控制里**，纯本地构建产物。

删掉残留后重跑：40 个迁移干净落到 v100，全部测试通过。
**没有改任何 SQL**，也没有改任何断言。

### 3. 契约里的重复标识符来自自动合并，用 codegen 修而不是手改

`pnpm typecheck` 报 `catalog.ts(1100,13): Duplicate identifier 'AccountingBookView'`。
两侧各自重新生成的 `catalog.ts` 被**自动合并**成同一类型出现两次
（`AccountingBookView` / `AccountingBookInput` 等共 3 个）。

契约是 codegen 产物禁止手改，按规程重新生成：

```
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
→ Generated 1350 DTOs and two OpenAPI snapshots
```

`catalog.ts` 净变化 = **只删掉这 3 个重复声明**，其余是排序。

### 4. 顺手核对了抢救 commit 里标记的 warehouse.openapi.json 疑点

抢救 commit 自己留言说 `warehouse.openapi.json` 产生约 1.7 万行 diff、
「疑为整体重排而非本模块引入」。核对结论：**确为纯排序抖动**。

- 287 条 path、614 个 schema，**集合完全相同，零内容差异**（`only in OLD: 0` / `only in NEW: 0`）
- `catalog.openapi.json` 同样：329 条 path 集合相同，0 新增 0 删除

本模块在 catalog 服务，warehouse 这次 churn 与 11.4.2.2 无关。
重新生成后 diff 反而变大到 3.7 万行 —— 因为这次把 5 个服务的 spec 全部重新落盘，
**行数不是信号，内容差异才是**，两者都是 0。

## 蓝图自检（八条逐条）

| # | 项 | 结论 |
| --- | --- | --- |
| 1 | 多语言版本是真实表行 | ✅ `kb_entry_translation` 独立表；测试 `多语言版本是真实表行而不是json挂一列` |
| 2 | 使用效果/命中率真实落库 | ✅ `kb_usage` append-only（触发器 + REVOKE 双重兜底），端点 POST /entries/{id}/usages + GET /hit-rates；测试 `引用记录真的落库且页面上查得到` |
| 3 | 话术与平台规则版本挂钩 | ✅ `kb_rule_version` + `kb_entry_rule` + `kb_review_task`；规则被接替（SUPERSEDED）自动给挂在上面的话术生成 PENDING 复核任务，有端点与测试 |
| 4 | 无硬编码/占位页/假分页假搜索 | ✅ 词表常量（ENTRY_TYPES 等）对应 DB CHECK 约束，业务数据全走 API；`listEntries` 是真 `limit/offset` + `LIKE` + `exists` 子查询 |
| 5 | 真落 PG16，jOOQ 显式 SQL | ✅ Testcontainers PG16，jOOQ 拼接显式 SQL，无 JPA |
| 6 | 多租户 + RLS 三断言 | ✅ 六表 `tenant_id uuid NOT NULL` + ENABLE/FORCE RLS；租户只取 `AccessService.Access`；三断言见下 |
| 7 | 金额数量 BigDecimal/NUMERIC，时间 OffsetDateTime + `?::timestamptz` | ✅ 十个时间列全 `timestamptz`；命中率经 `KnowledgeBaseRules.hitRate` 走 BigDecimal 无浮点；两个测试断言写回值零漂移 |
| 8 | V100 既定不改 | ✅ |

### 第 6 条的三个断言（实跑，不是走过场）

1. **两租户互不可见** —— 读对方条目/规则/挂钩/引用全部拒绝；A 不能把话术挂到 B 的规则上，不能改删 B 的规则版本与话术
2. **伪造 X-Tenant-Id 无效** —— 带伪造头请求仍 404；且**断言行确实落在自称租户 B 名下**（`select tenant_id ... isEqualTo(b)`），不是只断言 A 看不见
3. **连接池归还后不串租户** —— 同一连接反复交替，5 轮逐轮断言话术集合与规则版本集合 `disjoint`

### 第 7 条的时间断言

- `规则生效时间写回零漂移` —— 防 `Timestamp.valueOf(LocalDateTime)` 静默偏 −28800s
- `引用记录的时间戳往返不丢精度`

## 验证结果（真实数字）

```
cd backend && ./mvnw verify
  BUILD SUCCESS / EXIT=0，136 个测试类
  identity   236  Failures 0  Errors 0  Skipped 0
  catalog   1304  Failures 0  Errors 0  Skipped 0
  channel     75  Failures 0  Errors 0  Skipped 0
  warehouse  908  Failures 0  Errors 0  Skipped 0
  trade       37  Failures 0  Errors 0  Skipped 0
  合计      2560
  Flyway: Successfully applied 40 migrations ... now at version v100
  其中本模块：KnowledgeBaseIntegrationTest 33 / KnowledgeBaseRulesTest 35（全绿）

pnpm typecheck     ✓ 3/3 successful
pnpm lint          ✓ 3/3 successful（4 条 warning 均为他车道既有文件）
pnpm test          ✓ Test Files 99 passed / Tests 1882 passed
  其中本模块：knowledge-model.test.ts 44、api-proxy catalog route.test.ts 38
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
  ✓ Compiled successfully，/aftersales/knowledge 12.4 kB
node tools/module-progress/sync.mjs --check
  ✓ 台账与蓝图一致（已交付 118 / 未开发 347）
```

**没有为了让验证通过而放宽任何断言。**

## 文件清单

- 迁移 `backend/services/catalog/src/main/resources/db/migration/V100__customer_service_knowledge_base.sql`
- 后端 `KnowledgeBaseController/Models/Rules/Service.java`
- 测试 `KnowledgeBaseIntegrationTest.java`、`KnowledgeBaseRulesTest.java`
- 前端 `knowledge-model.ts(.test)`、`knowledge-workspace.tsx`、`app/aftersales/knowledge/page.tsx`
- 白名单 `apps/web/src/lib/api-proxy.ts`（`kb` 分支求并）
- 契约 `packages/api-contracts/src/*`（codegen 重生成）
- 台账 `docs/product/capability-blueprint/module-progress.md`（只改 11.4.2.2 行 + 派生计数）

## 资源

本车道 verify 期间同时存活的 Testcontainers PG 容器：**1 个**（ryuk 之外的业务容器，10 核单车道）。
verify 结束后 ryuk 已回收，`docker ps` 回到基线 **4 个**（三个 dev 服务 + `starcon-mig-v2`，
都是本车道开始前就在跑的），无残留。

## 后续待办

- **B-01（3.4.2.2 车道，不属本车道）**：`translation-coveragetranslations` 少一个 `|`
- 台账 11.4.2.2 的「页面」列仍是 `—`：该列由蓝图派生，蓝图 `### 11.4.2.2` 标题未声明路由。
  本车道按要求只改状态与证据两格，页面路径已写进证据列。是否回填蓝图路由留给集成方决定。