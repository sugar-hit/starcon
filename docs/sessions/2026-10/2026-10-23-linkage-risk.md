# 16.5.2.2 关联风险（阶段 D7 · L3 锚点 16.5.2）

- **车道**：`feat/16-5-2-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.5.2.2`）
- **基线**：`integration/module-dev` @ `f8733478`
- **落点服务**：channel（迁移 `V321__linkage_risk.sql`，端点前缀 `/channel/linkage-risk/**`）

## 蓝图原文

> ### 16.5.2.2 关联风险
>
> - **职责**：识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，输出关联图谱与风险提示，提示避免触发平台关联处罚。
> - **输入**：店铺与支付/设备/地址/法人关系数据；平台政策要求。
> - **输出**：关联图谱 + 风险提示 + 整改建议。
> - **关系**：上游 = 2 店铺、12.7.1 资金账户；下游 = 1 工作台预警。**关联封号是跨境多店经营的头号风险**：官方 API 授权是规避关联的关键，本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。

## 决策摘要

### 1. 五个关联维度逐条处置（没有一个走 mock）

| 维度 | 事实来源 | 能力声明 |
| --- | --- | --- |
| 法人 | channel 真实表 `shop.legal_entity_id`（**自动发现**） | `available=true / autoDiscoverable=true` |
| 设备 / 网络 IP / 地址 / 其它 | 本模块自持的**关联事实登记面**（租户登记已观测到的共享事实） | `available=true / autoDiscoverable=false` |
| 支付方式 / 收款账户 | 12.7.1 资金账户（**未交付**，finance 不在集成头） | `available=false / ownerModule=12.7.1.1` |
| 工作台预警（下游） | 1 工作台预警（**未交付**） | `available=false / ownerModule=1` |

**关键 trade-off**：设备 / IP / 地址在本仓**没有上游数据表**。两条路可选 ——
(a) 标 `available=false` 什么都不做；
(b) 模块自持登记面，让租户登记**已观测到的**共享事实，再据此实算。
选 (b)，理由：① 它与本仓既有形状同构（16.4.1.2 检测与预警的「租户登记外部查询单 → 登记查回来的结果 → 与本地库一起比对」）；② 事实落 PostgreSQL、查询走 jOOQ 显式 SQL、登记必须过店铺存在性校验，**不是硬编码数组当数据源**；③ 蓝图职责覆盖这五个维度，只做法人等于半成品。
同时在能力声明里把「**可登记但不自动发现**」写清楚 —— 不假装系统能自动发现设备共享。

**法人维度刻意不提供登记面**：`shop.legal_entity_id` 是真相，另开登记面 = 第二份法人真相。
**支付方式 / 收款账户维度值域里根本没有**：DB 守卫触发器连绕过服务层的写入都拒。

### 2. 严重度可解释，且前端不重算

`因子敏感度（租户写明）→ 组内店铺数超阈值升一档 → 是否跨平台再升一档`。
跨平台更危险是**实算**的（组内出现两个平台），不是猜的 —— 平台封号是按主体判的。
阈值（`min_shared_shop_count` / 每维度 `min_shop_count`）**不给默认值**，必须租户显式选；
没有策略时图谱 `known=false`、扫描 422。

### 3. 关联组 = 连通分量，键必须确定性

A↔B 共享设备、B↔C 共享地址 ⇒ A/B/C 是**一组**（哪怕 A 与 C 没有直接共享）。
`cluster_key` = 成员集合的 sha256 ⇒ 重扫同一批事实落到同一组 ⇒ 提示被**更新**而不是刷出新的。
事实消失后旧提示自动收口成「已整改」，但**只在确实读到店铺时**才敢收口 ——
否则一次数据范围异常会把全部提示误判成已整改。

### 4. 只做官方 API 授权路径

维度值域里没有「浏览器指纹 / 模拟登录」；模块不生成、不采集任何设备指纹；
整改建议码全集里没有一条指向模拟登录 / 指纹浏览器 / 绕关联；
通用建议固定为「用官方 API 授权把店铺分开」。

### 5. 读不到 ≠ 没有

- 没有策略 ⇒ 图谱 `known=false` + 说明，扫描 422（**不是**「算过了，没有关联」）。
- 法人名称读不到（identity 不可达）⇒ `legalEntityNameAvailable=false` + `null` 名称，
  **不返回空字符串**冒充「这个法人没有名字」；图谱里的法人因子显示「法人名称读不到」。
- 前端五种空态文案**各不相同**：还没登记策略 / 读不到店铺 / 还没登记共享事实 / 没有发现关联组 / 加载失败。

### 6. 踩过的两个坑（写下来免得再踩）

- 🔴 `Sensitivity` 枚举按 `HIGH/MEDIUM/LOW` 声明 ⇒ `ordinal()` 把 HIGH 排成 0（比 LOW 还小），
  「取最高敏感度」的比较整个反了。已改成显式 `weight()`。
- 🔴 `timestamptz` 绑定必须写 `?::timestamptz`（本模块踩了 5 处），
  否则 PG 报 `column "first_observed_at" is of type timestamp with time zone but expression is of type character varying`。

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V321__linkage_risk.sql`（9 张表 + RLS + 守卫触发器 + GRANT/REVOKE）
- `backend/services/channel/src/main/java/com/starcon/channel/LinkageRiskModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LinkageRiskRules.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LinkageRiskService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LinkageRiskController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIntegrationTest.java`（20 例）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskTenancyIntegrationTest.java`（3 例，三断言）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskRulesTest.java`（10 例）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIdentityStub.java`（法人档案读面桩，可造「不可达」）

### 前端

- `apps/web/src/app/compliance/linkage-risk/page.tsx`
- `apps/web/src/components/linkage-risk/linkage-risk-model.ts`
- `apps/web/src/components/linkage-risk/linkage-risk-workspace.tsx`
- `apps/web/src/components/linkage-risk/linkage-risk-model.test.ts`（17 例）
- `apps/web/src/app/api/channel/linkage-risk-allowlist.test.ts`（7 例，双向断言）

### 契约 / 台账

- `packages/api-contracts/src/contracts/channel.ts` + `channel.openapi.json`（codegen 产物，新增 27 个 `LinkageRisk*` DTO）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 16.5.2.2 |` 一行的「状态」「证据」两格）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过 |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='LinkageRisk*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **33 例全绿**（20 + 3 + 10） |
| L0 类型 | `pnpm typecheck` | 3/3 任务通过 |
| Lint | `pnpm lint` | 3/3 任务通过（告警均在既有文件） |
| 前端单测 | `pnpm vitest run apps/web/src/components/linkage-risk apps/web/src/app/api/channel/linkage-risk-allowlist.test.ts` | **24 例全绿** |
| api-proxy 全量回归 | `pnpm vitest run apps/web/src/app/api/trade` | **68 文件 / 379 例零回归** |

三断言（Testcontainers PG 16）：两租户互不可见（九张表逐张按「对方 tenant_id 零命中 + 自己读得到」断言）、
伪造 `X-Tenant-Id` 头无效（仍读到 A 的数据；无会话 401）、连接池归还后不串租户（池压到 1，A→B→A 三轮）。
直查一律用**应用账号 channel_app**（超级用户绕过 RLS会让断言永远「通过」）。

## 后续待办

1. **12.7.1 资金账户交付后**：支付方式 / 收款账户两个维度从 `available=false` 转 `available=true`。
   本仓的取值域与守卫触发器刻意把它们**排除在外**（`linkage_identifier.dimension` 的 CHECK +
   `guard_linkage_dimension_not_finance_owned`），届时需要**新迁移**放开并接上游读面，
   不要就地改 V321（已发布的迁移不改）。
2. **1 工作台预警交付后**：本模块的风险提示可以推给下游；本模块**不代**推送，
   届时走下游自己的读面，不要在本模块加推送表。
3. **设备 / IP / 地址的自动发现**：若将来有真实上游（设备管理 / 网络出口登记），本模块的
   `autoDiscoverable` 需要跟着翻成 `true` —— 那时要动的是「事实来源」，不是登记面本身。
4. 本模块**不做**任何模拟登录 / 指纹浏览器能力 —— 这不是待办，是边界。
