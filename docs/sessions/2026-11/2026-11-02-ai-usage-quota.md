# 2026-11-02: 18.4.3.1 使用与配额（卷 18 · AI 用量 / 成本 / 配额 / 敏感策略）

## 摘要

蓝图 18「集成开放与智能自动化」L3 18.4.3「AI 使用与效果管理」下的第一格 **18.4.3.1「使用与配额」** 落地。
职责原文：「管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、敏感数据处理策略开关。
输入：调用记录；配额配置。输出：用量报表 + 配额执行。上游 = 18.4 全部；下游 = 17.6.1 计费。」

后端落在 **platform 服务**（与 18.1 / 18.2 / 18.3 / 18.4.2.1 同服务），迁移版本 **V425**（调度器预分配）。
交付四件齐全：Flyway 迁移 + jOOQ 服务/端点/集成测试 + 重新生成 OpenAPI 契约 + 前端真实工作区 `/ai/usage`；
台账 `module-progress.md` 的 18.4.3.1 一行由「未开发」改为「已交付」并补证据。

**L1 模块闸 24 例全绿**（真 Testcontainers PostgreSQL 16，FORCE RLS）；前端 24 例全绿；
全量 allowlist 回归 **284 文件 / 1600 例全过**；契约重生成后**删除 0 条**、既有 schema **0 处改动**、其余 8 个服务**逐字节不变**。

## 决策 / 关键 trade-off

### 1. 成本不硬编码：单价落库 + 快照进台账（而不是算完就丢）

单价是**商业条款**，归租户定 ⇒ 落 `platform_ai_model`（平台**零预置**），记用量时把当时的 `unit_cost`
**快照**进台账行。目录后来改价，历史台账的成本**不动** —— 回看历史账单看到的仍是当初的价。
测试直接读回历史行断言 `cost=3.000000 / unit_cost=0.002000` 在改价（0.002 → 0.005）后**没有变**。

### 2. 量纲由目录决定，客户端不能自称

`unit ∈ {REQUEST,TOKEN,THOUSAND_TOKEN,CHARACTER}`，**模型目录**决定这一行的量纲；请求体里**没有** `unit`。
以「次」为量纲的模型，调用量**只能是 1**（传 `0.5` ⇒ 400）—— 否则「0.5 次调用」这种说法会一路流到账单。
同样是 0.002，「每次 0.002」与「每 Token 0.002」差着好几个数量级，所以量纲不是装饰。

### 3. 配额并发正确性：DB 侧原子计数，不许 Java 读后写

递增是一条 `INSERT ... ON CONFLICT (quota_id, period_start) DO UPDATE
SET used_count = platform_ai_quota_usage.used_count + EXCLUDED.used_count`，
由数据库**行锁**串行化，**返回值即递增后的累计值**。测试用 8 条并发请求打同一条配额，
断言计数**恰好 8**、成本 `4.000000` —— 「Java 先 SELECT 再 UPDATE」的读后写在这里会小于 8（丢计数）。

排期窗口按 `(quota_id, period_start)` 唯一 ⇒ 周期切换就是新的一行，上一周期的计数**原样留着**。

### 4. 超限必须说清五要素

「超了哪一条（quotaId / scope / capabilityCode）/ 当前用量 / 阈值 / 周期 / 什么时候重置」缺一不可 ——
只说一句「超配额了」，调用方没法行动。周期边界固定 **UTC 自然日 / 自然月**
（跨租户对齐的口径，下游计费按同一口径切账期），所以「什么时候重置」永远是一个确定的时刻。

### 5. 不同币种不混算（实现期发现并修掉的真缺陷）

最初的设计里，配额的「成本上限」没有币种，而计数累计的是所有匹配调用的成本 ——
于是「本月最多花 100 USD」这条额度会被一笔 EUR 调用顶穿，**不同币种的钱被加在了一起**。
修法：给 `platform_ai_quota` 加 `currency`，**成本上限按本条配额的币种计量**，
币种不符的调用**照计次数、不计成本**（跨币种请分别配置多条配额）。
报表侧同理：`summary.byCurrency` **分币种**给，页面不替它们求和。

### 6. 请求体不接受归属；用户级配额的主体走路径

请求体**没有** tenantId / userId / membershipId（Jackson 全局 fail-on-unknown-properties ⇒ 400），
主体成员由服务端从**已认证会话**解析（`actor.membershipId()`）。
管理员代配别人的用户级配额时，成员走**路径**（`POST /platform/ai/usage/members/{memberId}/quotas`），
不是请求体 —— 客户端不能自称归属。

### 7. 敏感数据策略：落库 + 可查询，**执行点如实声明**

`platform_ai_sensitive_policy`（能力 × 模型 × 动作 BLOCK/MASK/ALLOW）落库并可查询
（`GET /platform/ai/usage/policies/effective` 供各能力在调用前查；**更具体的那条生效**，没登记落到默认档「允许」并如实说明）。
但**本仓没有统一的 AI 出站拦截点** ⇒ 能力声明把「全局 AI 出站拦截点」这一维**如实答不可用**并写明归谁，
页面顶部也写着「执行点是各个 AI 能力自己」—— **不谎称已全局拦截**。

### 8. 下游计费边界：只提供可计费口径的汇总

`GET /platform/ai/usage/summary` 的粒度 = **能力 × 模型 × 币种**，另给 `byMember` / `byCurrency`；
`billableBasis` 写明口径（含「不含税费、折扣与汇率换算」）。
**不直连 finance 库、不建跨库 FK、不写 finance 表** —— 17.6.1 自行取数或由事件消费。

### 9. api-proxy：新增**独立**一条 `services[]` 元素，零命中逐条实测

既有条目**一字未改、也不重排**；本条插在 18.4.2.1 那条**之后**（同卷相邻的 AI 能力），
与开头段相距 13000+ 行、与末尾段相距 700+ 行，三个 hunk 各自独立 ⇒ 并入时 git 能干净求并。
发车前用一次性脚本（`.tmp/api-proxy-ai-usage-check.mjs`，不入库）**用 TypeScript 编译器解析真实数组字面量**，
按 `resolve()` 的同构口径实测：`services[]` 共 **280** 条、本条目第 **265** 条、
**9** 条正向路径 **9/9** 命中本条目（**无遮蔽**）、**9** 条负向形状 **9/9** 无命中、
其余 **279** 条既有条目对本模块路径**零命中**。

### 10. 按新事实修正 18.4.2.1 的负向样本**理由**（断言本身不变）

`platform/text/ai-settings` 的负向样本原注释写「18.4.3.1 使用与配额**未交付**」——本车道交付后这句**事实过期**。
只改了**理由**（端点在 `/platform/ai/usage` 下，不是 `/platform/text/ai-settings`），
**负向断言本身一字未动**（该路径**仍然** 404）。
18.4.2.1 自己的测试文件 `apps/web/src/app/api/platform/text-translation-allowlist.test.ts` 里有同一句注释 ——
**本车道没有动它**（不是本车道的文件），留作后续清理项。

## 文件清单

**后端（新增）**

- `backend/services/platform/src/main/resources/db/migration/V425__ai_usage_quota.sql` — 五表 + RLS + GRANT + 只追加触发器
- `backend/services/identity/src/main/resources/db/migration/V425__platform_ai_usage_permission.sql` — `platform.ai-usage.*` 权限码
- `backend/services/platform/src/main/java/com/starcon/platform/AiUsageQuotaModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/AiUsageQuotaService.java`
- `backend/services/platform/src/main/java/com/starcon/platform/AiUsageQuotaController.java`
- `backend/services/platform/src/test/java/com/starcon/platform/AiUsageQuotaIntegrationTest.java`（16 例）
- `backend/services/platform/src/test/java/com/starcon/platform/AiUsageQuotaTenancyIntegrationTest.java`（8 例）

**前端（新增）**

- `apps/web/src/app/ai/usage/page.tsx`
- `apps/web/src/components/ai/ai-usage-model.ts`
- `apps/web/src/components/ai/ai-usage-model.test.ts`（19 例）
- `apps/web/src/components/ai/ai-usage-workspace.tsx`
- `apps/web/src/app/api/platform/ai-usage-allowlist.test.ts`（5 例）

**改动**

- `apps/web/src/lib/api-proxy.ts` — 新增一条独立 `platform/` 元素（+ 修正 18.4.2.1 那条负向样本的理由）
- `packages/api-contracts/src/contracts/platform.ts` + `packages/api-contracts/src/platform.openapi.json` — codegen 重生成
- `docs/product/capability-blueprint/module-progress.md` — 18.4.3.1 一行（状态 + 证据两格）

**临时产物（不入库）**：`.tmp/api-proxy-ai-usage-check.mjs`

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| 后端编译 | `cd backend && ./mvnw -q compile` | ✅ |
| L1 模块闸 | `./mvnw -pl services/platform -am test -Dtest='AiUsageQuota*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **24 例全绿**（16 + 8） |
| 契约导出 | `./mvnw -pl services/platform -am test -Dtest='OpenApiContractTest'` | ✅ 1 例 |
| 契约重生成 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | ✅ 7703 DTOs / 9 服务；**删除 0 条**、既有 schema **0 处改动**、其余 8 服务**逐字节不变** |
| 类型检查 | `pnpm typecheck` | ✅ 3/3 任务成功 |
| 格式 | `pnpm exec prettier --check`（仅本次改动文件） | ✅ |
| Lint | `pnpm --filter @starcon/web exec eslint src/components/ai src/app/ai …` | ✅ 无告警 |
| 前端单测（新增/改动文件） | `pnpm exec vitest run apps/web/src/components/ai apps/web/src/app/api/platform/ai-usage-allowlist.test.ts` | ✅ **24 例全绿** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api` | ✅ **284 文件 / 1600 例全过** |
| 白名单零命中取证 | `node .tmp/api-proxy-ai-usage-check.mjs` | ✅ 9/9 放行、9/9 拒绝、279 条零命中 |

**多租户三条断言**（AGENTS.md 明文要求，实跑 Testcontainers PostgreSQL 16）：

1. **两租户互不可见** —— 列表 + **按主键直查**对方租户的行也拿不到；事务外一行都看不见。
2. **伪造 tenant header 无效** —— 带伪造 `X-Tenant-Id` / `X-Internal-Key` 的请求**仍然写入成功**，
   但行落在**会话租户**名下（不是调用方自称的），且**主体成员也来自会话**。
3. **连接池归还后不串租户** —— 连接池**压到 1 条**，交替 A→B→A→B 五轮，每次只看见自己租户的行；
   事务外读仍为零行。

另断言：五张表 RLS **既 ENABLE 又 FORCE**（只 ENABLE 挡不住「运行角色被误授 owner」）、
`tenant_id` 全部 NOT NULL、且**用量台账只授 SELECT+INSERT 不授 UPDATE/DELETE**。

## 后续待办

1. **「成员是否属于本租户」的查询端点缺失**（identity 侧只有 `/internal/access`）——
   管理员代配用户级配额时**无法校验成员归属**；写进去的配额若指向非本租户成员，永远匹配不到用量
   （**惰性行**），**不会越权**。要补的话需要在 identity 侧新增一个只读端点（本车道不碰 identity 的端点）。
2. **配额预留（调用前占位）未实现** —— 预检是**读快照**，不占额度；
   并发下的权威判定发生在**记用量**那一次的数据库原子递增，因此「预检通过」≠「这次一定放得下」。
   能力声明里如实写成不可用。
3. **上游 18.4 的其它 AI 能力尚未交付** ⇒ 台账里**还没有真实调用记录**；
   记录入口已就绪，各能力接上后自行回报（主体成员由服务端从会话解析）。
4. **清理项**：18.4.2.1 的测试文件 `apps/web/src/app/api/platform/text-translation-allowlist.test.ts`
   里仍有一句「18.4.3.1 使用与配额**未交付**」的过期注释 —— 本车道**没有动它**（不是本车道的文件），
   请 18.4.2.1 的属主或后续清理批次改掉。
