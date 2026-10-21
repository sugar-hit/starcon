# 2026-10-21 batch-55 —— 并入 11 个模块（L2 首轮红 → 定位 → 修复 → 复跑绿）

> 批次 `batch-55`，集成头 `integration/module-dev`。
> 本轮 = **并入 11 个模块 + 1 次跨车道破坏修复**，并入前后 `fea0cb25` → `976d7d42`。
> 上一批 `batch-54` 是纯派发轮（0 并入、L2 未跑），本轮把它的产出接上。
>
> **本轮台账变化**：已交付 **287 → 297**（+10），未开发 **178 → 168**。
> 全库 465 个 L4 模块，完成率 **63.9%**。
>
> ⚠️ **本轮的关键事件不是「交付了 10 个模块」，而是「L2 抓到了一次跨车道破坏，
> 并且在同一个轮次里定位、修复、复跑、推送」** —— 详见下面「L2 批量验证」两节。

## 本轮闸门判定（全部现取，未引用历史文档数字）

| 闸 | 取值 | 判定 |
| --- | --- | --- |
| 静默窗口闸 | `SILENT_WINDOW=0` → 全天运行，不设时段闸 | 放行 |
| 强度档位 | 当前 `2026-10-21T22:00+0800` < `TOKEN_HORIZON=2026-10-25T00:00+0800` → **full 模式**（LANES=4 / THROTTLE=50） | 满档 |
| 批次节流闸 | `last-batch-at=1792587890` → 距上批 **55 分钟** ≥ 阈值 50 分钟 | 放行 |
| 温度闸 | `pmset -g therm` → `Note: No thermal warning level has been recorded` | 正常 |
| 配额闸 | 开工时 running = **1** 条（11.5.1.1 退货分析，上一批遗留）< `RUNNING_QUOTA=6` | 放行 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 = **0** < 6；**无标签孤儿容器 0 个** | 放行 |
| load 闸 | 三点采样 `3.30/2.96/3.14`（1min 远低于 `LOAD_LIMIT=12`，无爬升趋势） | 放行 |
| 调度闸 | `gate status` → `gate=free, stale=false` → `gate acquire` 返回 `ok: true` | 取得 |

⇒ **容量闸 GO**。节流闸通过后才写 `last-batch-at`（协议硬规定：提前写会把节奏无限后推）。

## 本批回收：并入车道

`lane.mjs merge-ready` 分两轮执行，**全部并入成功**（`failed: []`，零冲突），`gc` 清理 6 个 worktree。

**第 1 轮（开工时并入 5 条，`fea0cb25` → `a881191b`）**：

| 模块 | 名称 | 阶段 | L3 | 主责服务 | 迁移 | 并入 commit |
| --- | --- | --- | --- | --- | --- | --- |
| 6.1.1.3 | 供应商评级 | D6 | 6.1.1 | trade | `V266` | `d5d4c4ba` |
| 7.4.1.2 | 调整审批 | D6 | 7.4.1 | warehouse | `V267` | `9ea1440c` |
| 6.6.2.2 | 转量产与建档 | D6 | 6.6.2 | trade | `V268` | `09f7d98f` |
| 11.4.1.2 | 证据与申诉 | D4 | 11.4.1 | trade | `V269` | `0b5f701f` |
| 11.4.2.1 | 工单管理 | D7 | 11.4.2 | trade | `V270` | `a881191b` |

**第 2 轮（L2 期间陆续落地，连同修复车道一并并入，`a881191b` → `976d7d42`）**：

| 模块 | 名称 | 阶段 | L3 | 主责服务 | 迁移 | 并入 commit |
| --- | --- | --- | --- | --- | --- | --- |
| 11.4.2.1 | 工单管理（**修复车道**） | D7 | 11.4.2 | trade | `V276` | `53ed69e4` |
| 11.5.1.1 | 退货分析 | D7 | 11.5.1 | trade | `V271` | `7e2f48cd` |
| 6.1.2.2 | 同款货源推荐 | D6 | 6.1.2 | trade | `V272` | `d8a87e65` |
| 14.1.1.1 | 活动列表 | D7 | 14.1.1 | channel | `V273` | `653e897f` |
| 14.1.3.1 | 关键词管理 | D7 | 14.1.3 | channel | `V274` | `96c26f1b` |
| 14.2.2.2 | 自动规则 | D7 | 14.2.2 | channel | `V275` | `976d7d42` |

台账完整性核对：行数 **465**（恒定）、**无重复模块 ID**。
台账并入走了「行级兜底并集」路径（多车道各自改了同一张表）—— 已用行数 + 去重两项核对兜住。

## 本批派发：4 条车道

`lane.mjs plan --lanes 4` 产出，**L3 锚点互不相同 ⇒ 互不依赖**；迁移版本 V272–V275 在
trade / channel 两个服务里均未占用 ⇒ 不撞号。

| 模块 | 名称 | 阶段 | L3 | 主责服务 | 迁移 | 分支 | worktree |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6.1.2.2 | 同款货源推荐 | D6 | 6.1.2 | trade | `V272` | `feat/6-1-2-2` | `.worktrees/6.1.2.2` |
| 14.1.1.1 | 活动列表 | D7 | 14.1.1 | channel | `V273` | `feat/14-1-1-1` | `.worktrees/14.1.1.1` |
| 14.1.3.1 | 关键词管理 | D7 | 14.1.3 | channel | `V274` | `feat/14-1-3-1` | `.worktrees/14.1.3.1` |
| 14.2.2.2 | 自动规则 | D7 | 14.2.2 | channel | `V275` | `feat/14-2-2-2` | `.worktrees/14.2.2.2` |

**派发前冲突预检**：4 个 worktree 与分支均已由 `plan` 新建且**工作区干净（无未跟踪文件）**；
V272–V275 在 6 个后端服务里均未出现 ⇒ 无版本号占用、无双 writer。

### 本批的共享文件冲突预案（`apps/web/src/lib/api-proxy.ts`，6349 行 / 约 60 条 `services[]`）

4 条车道都要改这个**多车道共用的累积白名单**。既有铁律：**新增独立 `services[]` 元素**，
绝不能往既有分支的超长单行 alternation 正则里并分支（多车道同时并进去 git 必然判冲突），
更不能手改别人已交付的路由（取错一侧会静默删掉同批兄弟车道的路由 ⇒ 页面 404，
而 typecheck / lint / vitest / Playwright **全部全绿**）。

调度者给本批指定了**四个相距 400 行以上的互不重叠插入锚点**，并在派发前逐个核对了
首 token 在全文件内**零命中**：

| 车道 | API 命名空间 | 插入锚点 | 首 token 零命中 |
| --- | --- | --- | --- |
| 14.1.1.1 | `ad-campaigns` | `prefix: 'auth/'` 那条元素之前 | ✅ 0 |
| 14.1.3.1 | `ad-keywords` | 第一条 `prefix: 'channel/'` 元素之前 | ✅ 0 |
| 6.1.2.2 | `source-alternatives` | `// 6.2.2.2 需求合并与批量` 那条 trade 元素之前 | ✅ 0 |
| 14.2.2.2 | `ad-rules` | `services[]` 数组**末尾**（`platform-disputes` 之后、`];` 之前） | ✅ 0 |

⚠️ 14.2.2.2（自动规则）与 14.1.1.1（活动列表）**同批并行** ⇒ 规则引擎**不得读 14.1.1.1 的表**
（并行车道的迁移同批才落地，worktree 里看不到）。已在任务书里钉死：规则按
**平台 + 店铺 + 外部活动 id** 定位广告对象，表现数据落在**本模块自己的表**里，
两条车道零耦合。

## 静态预检（互相破坏形状扫描，5 条新迁移）

只扫本批新迁移（V266–V270），不扫历史迁移：

| 形状 | V266 | V267 | V268 | V269 | V270 |
| --- | --- | --- | --- | --- | --- |
| ① `ADD COLUMN` 无 DEFAULT | — | — | — | — | **14 处命中 → 逐条核对后判定为安全** |
| ② `SET NOT NULL` | — | — | — | — | — |
| ③ `ADD CONSTRAINT ... CHECK` | 1 条 | — | — | — | 7 条 |

**①的复核结论**：V270 的 14 处 `ADD COLUMN` 全部是**可空列**（`assigned_to_user_id` /
`assigned_at` / `knowledge_entry_id` / `sla_*` / `reply_due_at` / `resolve_due_at` /
`first_reply_at` / `waiting_buyer_at` / `closed_at` / `escalated_case_id` /
`escalated_dispute_id`）—— 可空列不带 DEFAULT **不会**让早先车道的 INSERT 违约。
V270 里**所有 NOT NULL 列都带 DEFAULT**（`ticket_type DEFAULT 'INQUIRY'`、
`source_channel DEFAULT 'MANUAL'`、`subject DEFAULT ''`、`version DEFAULT 0`、
`updated_at DEFAULT now()` …）⇒ 早先 11.1.1.1 车道写入 `aftersales_service_ticket` 的 INSERT **继续成立**。

**③的复核结论**：V270 的 7 条表级 CHECK 在加约束那一刻会对存量行校验。存量行是
`ticket_status='OPEN'` + `closed_at IS NULL` ⇒ `closed_iff_terminal` 通过；
`assigned_to_ref=''`（DEFAULT）⇒ `assigned_has_time` 通过；其余 5 条的列在存量行里为 NULL ⇒ 恒真。
`ticket_status` 列名已核对确实存在于 V156（`ticket_status varchar(16) NOT NULL
CHECK(ticket_status IN ('OPEN','IN_PROGRESS','RESOLVED','CANCELLED'))`），不是笔误。

⇒ **静态预检通过，无「必然违约」形状命中。**

---

# L2 批量验证：第 1 轮 —— 🔴 红（单根因跨车道破坏）

在集成头上跑全量 `mvnw verify`（后端 6 个服务全量）。catalog / channel / crm / identity / warehouse
**全部绿**，trade 服务红 **4 个测试类、64 例全 error**：

| 测试类 | 所属模块 | 批次 | errors |
| --- | --- | --- | --- |
| `PlatformDisputeAppealIntegrationTest` | 11.4.1.2 证据与申诉 | 本批 | 28 |
| `PlatformDisputeAppealTenancyIntegrationTest` | 11.4.1.2 证据与申诉 | 本批 | 5 |
| `PlatformDisputeIntegrationTest` | 11.4.1.1 平台纠纷 | **更早批次** | 27 |
| `PlatformDisputeTenancyIntegrationTest` | 11.4.1.1 平台纠纷 | **更早批次** | 4 |

**64 例全部是同一个错误**（100% 同款，不是随机分布 ⇒ 不是假红）：

```
org.postgresql.util.PSQLException:
ERROR: cannot truncate a table referenced in a foreign key constraint
  详细：Table "aftersales_service_ticket" references "platform_dispute".
```

### 根因（已定位，未用二分）

本批 11.4.2.1「工单管理」的迁移 `V270__service_ticket_management.sql` 第 375 / 381 行，
给 `aftersales_service_ticket` 加了**两条跨模块外键**：

```sql
ADD CONSTRAINT aftersales_service_ticket_escalated_case_fk
  FOREIGN KEY (escalated_case_id) REFERENCES aftersales_case(id) ON DELETE RESTRICT;
ADD CONSTRAINT aftersales_service_ticket_escalated_dispute_fk
  FOREIGN KEY (escalated_dispute_id) REFERENCES platform_dispute(id) ON DELETE RESTRICT;
```

`platform_dispute` 是 **11.4.1.1** 的表、`aftersales_case` 是 **11.1.1.1** 的表 —— 都是**别的模块**的表。
PostgreSQL 规定「只要有子表带外键指向 `platform_dispute`，`TRUNCATE platform_dispute` 就被拒绝」，
而 11.4.1.1 与 11.4.1.2 的 4 个测试类都用 `truncate ... platform_dispute` 做测试隔离 ⇒ 隔离直接炸。

**这就是组批模式的 L2 抓到的典型跨车道破坏**：11.4.2.1 单跑全绿（它自己的 worktree 里没有 V269 的测试），
11.4.1.1 / 11.4.1.2 单跑也全绿（它们的 worktree 里没有 V270 的外键）——**各自单跑都绿，合并后才炸**。

### 处置（按协议：不 push → 定位 → 派修复任务 → 重跑 L2）

**没有用 `git bisect`** —— 错误原文直接点名了外键与被引用表，根因唯一，bisect 会白烧一次全量验证。

**修复方向（已定，调度者已取证）**：用**同租户存在性守卫触发器**替换这两条跨模块外键。

- **保住 11.4.2.1 的保护意图**（V270 第 368–370 行的原话：「不加外键的话，『升级了但对方根本不存在』
  会留下一张查无实据的工单」）—— 触发器同样能拒绝悬空引用。
- **触发器不阻止 `TRUNCATE`，外键才会** ⇒ 那 4 个测试类**一行都不用改**就能跑通。
  *否决的方案*：去改 4 个测试类的 `truncate` 语句 —— 那是把跨车道破坏的锅甩给别的批次
  （其中 2 个属于**更早批次**已交付的 11.4.1.1），而且会留下「以后谁再加一条指向 `platform_dispute`
  的外键就又炸一次」的复发陷阱。
- **符合本仓自己的纪律**：`V269__platform_dispute_appeal.sql` 第 243 行明确写着
  「覆盖 11.4.1.1 的哪一项举证清单（**跨模块，不建外键**：清单是上游的表，它的生命周期不由本模块决定）」；
  `V266` 全服务唯一那条外键指向的是**自己模块**的表；`V268` 对 catalog 是「零跨库外键」。
  ⇒ V270 的这两条**跨模块**外键是本仓纪律的破口。
- **顺带补上旧外键的第二个缺陷**：旧外键是裸 `id`（不含 tenant），A 租户的工单可以引用 B 租户的纠纷；
  新守卫按 `tenant_id` + `id` 联合判 ⇒ **比原来更严**。

**修复车道**：`11.4.2.1` 走 `adopt` 收编为修复车道
（分支 `fix/11-4-2-1-cross-module-fk`，worktree `.worktrees/fix-11.4.2.1-fk`），
迁移版本号 **V276**（V271–V275 已被在途车道占用）。
**只修这一个模块，不重写**；不改那 4 个测试类；不动台账、不动 `packages/api-contracts`、不动 `api-proxy.ts`。

### 修复的最终形态：三条跨模块外键 → 两条同租户守卫触发器

首轮 L2 只暴露出 V270 的两条外键。修复过程中**又露出第二条同类外键**（V269:136），
所以最终是**三条**跨模块外键一起处理。调度者把 trade 全部迁移里指向
`platform_dispute` / `aftersales_case` 的外键逐条枚举，结论：

| 出处 | 外键 | 性质 | 处置 |
| --- | --- | --- | --- |
| V270:375 | `aftersales_service_ticket` → `aftersales_case` | 跨模块（11.4.2.1 → 11.1.1.1） | ⇒ 守卫触发器 |
| V270:381 | `aftersales_service_ticket` → `platform_dispute` | 跨模块（11.4.2.1 → 11.4.1.1） | ⇒ 守卫触发器 |
| V269:136 | `platform_dispute_appeal` → `platform_dispute`（**CASCADE**） | 跨模块（11.4.1.2 → 11.4.1.1） | ⇒ 守卫触发器 |
| V265:247 / V265:299 / V269:232 / V269:293 | — | **自己模块**的父子表 | **一条未动** |

**迁移** `V276__service_ticket_cross_module_guard.sql`（trade）：
撤掉那 3 条外键 + 建 2 条**同租户存在性守卫触发器**
（`guard_aftersales_service_ticket_escalation` / `guard_platform_dispute_appeal_dispute`），
`RAISE EXCEPTION … USING ERRCODE = 'check_violation'`。
V269:136 的约束名**不靠猜** —— 按「子表 + 父表」反查 `pg_constraint`（照抄 V270:80-89 的既有写法）。

**守卫比原来的外键更严**：旧外键是**裸 `id`**（不含 tenant），A 租户的工单可以引用 B 租户的纠纷；
新守卫按 `tenant_id` + `id` 联合判，跨租户引用直接拒绝（实测错误码 **23514**）。

**一处语义变化（如实记录）**：V269:136 原来的 `ON DELETE CASCADE` 不再由 DB 兜底 ——
删纠纷不再自动连带删申诉。取证结论是**全仓没有任何 DELETE 路径会删 `platform_dispute`**
（`withdrawAppeal` 是状态流转不是删行；唯一的 delete 是删草稿态的一项材料）⇒ 这条 CASCADE 此前是空转的。
若将来 11.4.1.1 要支持删纠纷，应由**那条线的拥有者**决定「连带删还是拦住」。

**修复车道的独立复核**（一次性 postgres:16-alpine 上按序跑完 76 个迁移后用 psql 直验）：
`pg_constraint` 反查确认指向 `platform_dispute` / `aftersales_case` 的外键**只剩 2 条**
（均为 11.4.1.1 自己模块的子表）；两个测试类里**原样**的 truncate 语句均执行成功（**无 CASCADE**）；
跨租户引用申诉单 → `ERROR: 申诉单挂的纠纷不存在或不属于当前租户`（23514），同租户 → 放行。

**修复车道的 L1 结果**（`-Dtest='PlatformDispute*,ServiceTicket*'`）：
`Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`，**连续两次跑均绿**。

| 测试类 | 修复前 | 修复后 |
| --- | --- | --- |
| `PlatformDisputeIntegrationTest` | **27 error** | 27 绿 |
| `PlatformDisputeTenancyIntegrationTest` | **4 error** | 4 绿 |
| `PlatformDisputeAppealIntegrationTest` | **28 error** | 28 绿 + 3 例守卫自证 = 31 |
| `PlatformDisputeAppealTenancyIntegrationTest` | **5 error** | 5 绿 |
| `ServiceTicketIntegrationTest` | 29 绿 | 29 + 6 例守卫自证 = 35 绿 |
| `ServiceTicketTenancyIntegrationTest` | 3 绿 | 3 绿 |

**否决的方案（留档）**：

1. **不改那 4 个测试类的 truncate**（加 `cascade` / 补子表）—— 把跨车道破坏的锅甩给别的批次
   （其中 2 个属于**更早批次**已交付的 11.4.1.1，它们交付时这些外键还不存在），
   并留下「谁再加一条指向 `platform_dispute` 的外键就又炸一次」的复发陷阱
   —— **本轮实际就是同一个坑被 V270、V269 各埋了一遍**。
2. **直接删掉保护** —— 会重新放行「升级了但对方根本不存在」的查无实据工单
   （V270:368-370 的原话）。守卫触发器把这个意图保住了。
3. **改 V269 / V270 原文件** —— 迁移一旦发布就是历史，只能用新迁移往前修。

### L2 批量验证：第 2 轮 —— 🟢 绿

修复并入后重跑全量 `mvnw verify`（集成头 `976d7d42`）：

```
backend ............ SUCCESS [  0.002 s]
tenancy ............ SUCCESS [  0.438 s]
identity ........... SUCCESS [01:22 min]
catalog ............ SUCCESS [05:15 min]
channel ............ SUCCESS [07:17 min]
warehouse .......... SUCCESS [13:10 min]
trade .............. SUCCESS [16:45 min]
crm ................ SUCCESS [ 15.393 s]
BUILD SUCCESS   Total time: 44:06 min
```

**7667 例 / 451 个测试类 / Failures: 0 / Errors: 0 / Skipped: 0。**

首轮红的那 4 个类，本轮在**集成头上**逐一复验：

| 测试类 | 第 1 轮 | 第 2 轮 |
| --- | --- | --- |
| `PlatformDisputeIntegrationTest` | **27 error** | 27 绿 |
| `PlatformDisputeTenancyIntegrationTest` | **4 error** | 4 绿 |
| `PlatformDisputeAppealIntegrationTest` | **28 error** | 31 绿（含 3 例守卫自证） |
| `PlatformDisputeAppealTenancyIntegrationTest` | **5 error** | 5 绿 |
| `ServiceTicketIntegrationTest` | 29 绿 | 35 绿（含 6 例守卫自证） |
| `ServiceTicketTenancyIntegrationTest` | 3 绿 | 3 绿 |

本批 5 条新落地车道的测试也一并绿：
`SourceAlternativeIntegrationTest` / `ReturnAnalyticsIntegrationTest`(26) /
`AdCampaignIntegrationTest` / `AdKeywordIntegrationTest` / `AdRuleIntegrationTest`。

台账核对：`node tools/module-progress/sync.mjs --check` → **✓ 台账与蓝图一致**。

### L2 前端与契约半程

| 步骤 | 结果 |
| --- | --- |
| `pnpm typecheck` | ✅ 3/3 successful |
| `pnpm lint` | ✅ 3/3 successful（仅既有 warning，无 error） |
| `pnpm test`（vitest 全量） | ✅ **424 个文件 / 6379 例全过** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ BUILD EXIT=0（Next production build 通过） |
| `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

⇒ **L2 批次闸整体 🟢 绿。**

### 合并后的契约与白名单核对

调度者在并入后逐项核对（这些是本批最容易静默坏掉的地方）：

- **10 个新 API 命名空间全部在 `api-proxy.ts` 里注册且互不重叠**：
  `supplier-ratings` / `inventory/adjustment-approvals` / `mass-productions` /
  `platform-dispute-appeals` / `service-tickets` / `source-alternatives` /
  `return-analysis` / `ad-campaigns` / `ad-keywords` / `ad-rules`。
- **契约里每个模块的端点都在**（union 驱动器按键求并，无覆盖）：
  trade 789 paths / channel 547 / warehouse 518；
  逐个命名空间核对：supplier-ratings 10、mass-productions 16、platform-dispute-appeals 11、
  service-tickets 14、source-alternatives 10、return-analysis 8、ad-campaigns 8、
  ad-keywords 13、ad-rules 16、inventory/adjustment-approvals 15 —— **一个不缺**。
- **迁移版本号无撞车**：trade V266/V268–V272/V276、channel V273–V275、warehouse V267。

### ⚠️ 一个必须记下的并发事故

本轮 L2 跑着的时候，**另一个并发会话在同一集成头上动了手**：

- `010becb4` `docs(blueprint): batch-55 台账并入 11 个模块（287 → 297）`
- `223a2139` `docs(sessions): 补记 batch-55 并入 11 个模块与跨车道破坏修复`

它**读走了本调度者工作区里尚未提交的说明文件**、连同台账一起 commit，并**已经 push 到
`origin/integration/module-dev`**。

- 好消息：这两个 commit **只动了 `docs/`**，**一行代码都没碰**（`git diff 976d7d42..223a2139`
  排除 docs/ 后为空），本轮 6 条车道的并入成果**完好**。
- 坏消息：**调度闸没有挡住它**（两个会话的闸不是同一个，或一方绕过了闸），
  撞上了协议明确写过的形状「两个 tick 同时改同一个集成头」。
  撞车窗口里它还起了一批 Testcontainers PG（本轮 L2 期间观测到 PG 峰值 41、其中 40 个属于本轮自己的 session）。
  本轮 L2 **未受影响**（7667 例 0 failure / 0 error），但这是运气好，不是机制挡住了。
- 本调度者的应对：**不重写已推送的历史**，不 `reset`、不 force push。
  改为在已有提交之上**再补一个 commit**，把本轮真正的 L2 结果（第 2 轮 🟢 绿 + 前端半程）补进说明 ——
  那两个并发 commit 里的说明是 23:41 写的，**写的时候 L2 第 2 轮还没跑完**，
  所以它只记录了「L2 首轮红 → 定位 → 修复」，缺「修复后复跑绿」这半句。
  **这半句由本 commit 补齐。**

⚠️ **给下一轮的建议**：调度闸的互斥在本轮**没有真正生效**，原因已取证：
`lane.mjs` 第 44 行 `const ROOT = process.env.LANE_ROOT ? resolve(process.env.LANE_ROOT) : join(HERE, '..', '..')`，
闸目录是 `join(STATE_DIR, 'dispatch.gate')` —— **闸挂在 `ROOT` 下**。
而 `/Users/guan/Projects/starcon/.dev-agent/dispatch.gate`（**主工作区**）在本轮
**23:44:05 自己也建了一个**（mtime 取证），与集成头 worktree 里的那个
（`/Users/guan/Projects/starcon-worktrees/integration/.dev-agent/dispatch.gate`）**是两个不同的目录**。
⇒ 并发会话只要在**主工作区**里跑 `lane.mjs`（或带 `LANE_ROOT` 指过去），
拿到的就是**另一把闸**，「同一把闸互斥」这件事在跨 worktree 时根本不成立。

**下一轮开工第一件事：核对闸目录的绝对路径**（`ls -ld <各自 ROOT>/.dev-agent/dispatch.gate`），
确认自己拿到的和对方拿的是**同一个目录**再 `gate acquire`。
若确认是不同目录，**在开始任何写操作前**先与对方对齐 `LANE_ROOT`。
（另一种更稳的做法：把闸目录锚定到**固定绝对路径**，不随 worktree 漂移。）

---

# 已交付模块

## 模块 6.1.1.3 供应商评级

**这个功能做什么**：把供应商的交期达成、质量合格、响应速度、价格竞争力、售后配合五个维度，
按周期自动算成一个 0–100 的客观分，排出名次；运营可以在这个客观分之上做**有理由的人工加权**，
两者永远分开展示。买手据此知道该优先找谁谈。

**技术落地**：

- **迁移**：`V266__supplier_rating.sql`（trade，418 行）—— **4 张新表、零 ALTER 零 DROP 任何既有表**：
  `supplier_rating_weight_policy`（租户级权重配置）/ `supplier_rating_result`（每供应商每周期一行）/
  `supplier_rating_adjustment`（人工加权分明细，追加式）/ `supplier_rating_change`（评级变动历史，追加式）。
  四表 `tenant_id NOT NULL` + `ENABLE` + **`FORCE`** RLS + GRANT；两张留痕表的运行角色 UPDATE/DELETE 已收回。
- **端点**：`/trade/supplier-ratings` × **11** 个 —— `capabilities`（能力声明）/`options`（词表）/
  `overview`（等级分布、平均分、人工调整家数）/`policy`（GET 读 + PUT 改权重）/`compute`（POST 计算一个周期）/
  `ratings`（列表，真分页 + 真筛选 + 真排序）/`ratings/{resultId}`（详情）/
  `ratings/{resultId}/adjustment`（POST 人工加权分，理由必填）/`changes`（变动历史）/`priorities`（默认供应商优先级，供 6.2.1.2 补货建议消费）。
  权限码 `trade.ratings.read` / `trade.ratings.update`；租户只来自已认证成员关系，**不读 `X-Tenant-Id` 头**。
- **页面**：`/supply/ratings`（`apps/web/src/app/supply/ratings/page.tsx` + `components/supply/supplier-rating.tsx`
  + `components/supply/supplier-rating-model.ts`）—— 周期选择、权重配置、评级列表（客观分 / 人工调整 / 最终分并排）、
  人工加权弹窗（理由必填）、变动历史、优先级。**没有依据的维度显示「不可用 / 暂无依据」而不是 0 分**；
  人工调整过的行带单独标记。
- **测试**：`SupplierRatingIntegrationTest` 28 例 + `SupplierRatingTenancyIntegrationTest` 3 例（三条租户隔离断言）+
  `SupplierRatingRulesTest` 35 例；前端 `supplier-rating-model.test.ts` 22 例 +
  **`supplier-rating-allowlist.test.ts` 13 例（api-proxy 白名单回归，守住「取错一侧静默删路由」）**。
  合计 **101 例**。

**关键设计决策**：

1. **「不可用」≠「0 分」**：分母为 0 的维度**不参与合成**（得分与原始指标一律可空），
   合成按**可用维度归一**（Σ权重×得分 ÷ Σ可用权重，仍是 0–100 的可比值），结果行同时报出
   `available_weight` / `unavailable_dimensions` / `unavailable_reason`。
   *否决的方案*：把无数据的维度记 0 分 —— 那会让「没跟过单的新供应商」看起来比「合格但单少」更差，
   排名直接失真。
2. **人工加权分单独标记、不重算客观分**：`objective_score`（纯客观）/ `manual_adjustment`（人工 ±50，理由必填）/
   `final_score`（最终分）三者**并存**，DB 层 CHECK 钉死
   `final_score = greatest(0, least(100, objective_score + manual_adjustment))`，
   外加 `manually_adjusted` 布尔标记 + 追加式调整明细 + 变动历史三处留痕。
   重算**不抹掉**人工调整，人工调整**不重算**客观分。
   *否决的方案*：把人工分直接并进综合分再存一个总分 —— 那样就再也答不出「这分里有多少是人给的」，
   正好是蓝图加粗句要防的「变成纯主观打分」。
3. **五个维度的口径逐字钉进迁移与台账证据**：交期达成率与 6.3.2.1 跟单延迟统计**逐字同源**
   （到货时刻之前最后一次交期变更的 `to_promised_date`）；质量合格率按**数量比**（Σ合格量 ÷ Σ覆盖批量），
   **不按批次数比**；价格竞争力是「同币种最优价 ÷ 本供应商价」的**按采购量加权**平均，跨服务**只读** catalog 报价台账。
4. **归属 trade 而非 catalog**：卷 06 的「后端主责服务」是 `trade + catalog（party）`，取列表第一项 = trade；
   且五个维度的事实**全在 trade 库内**，只有市场报价需要跨服务读。

---

## 模块 7.4.1.2 调整审批

**这个功能做什么**：库存调整单提交上来之前，先按租户自己配的**金额阈值 / 数量阈值**判一下要不要走审批；
要审批就攒够人数，逐条批准或驳回；不用审批的直接放行。全过程留痕，批量审批能一次处理一堆。

**技术落地**：

- **迁移**：`V267__inventory_adjustment_approval.sql`（warehouse，602 行）—— **5 张新表、零 ALTER 既有表**：
  阈值规则表 / 审批人配置表 / 审批链路记录（append-only）/ 审批实例 / 会计影响事实
  `inventory_adjustment_valuation`。全部 `tenant_id NOT NULL` + ENABLE + **FORCE** RLS + GRANT。
- **端点**：`/warehouse/inventory/adjustment-approvals` × **18** 个 —— `capabilities` / `rules`（GET+POST）/
  `rules/{ruleId}/update` / `rules/{ruleId}/status` / `approvers`（GET+POST）/
  `approvers/{approverId}/status` / `pending` / `evaluate`（阈值判定）/
  `{orderId}/submit` / `{orderId}`（详情）/`{orderId}/approve` / `{orderId}/reject` /
  `batch/approve` / `batch/reject` / `valuations`（会计影响事实）。
  **刻意独立于 7.4.1.1 的 `/warehouse/inventory/adjustments`** —— 同一份资源的两类动作，两条命名空间不混。
- **页面**：`/inventory/adjustments`（`apps/web/src/app/inventory/adjustments/page.tsx` 新增**审批工作流页签**，
  与 7.4.1.1 的调整工作区共用蓝图路由）+ `components/inventory/inventory-adjustment-approval-workspace.tsx`
  + `inventory-adjustment-approval-model.ts`。
- **测试**：`InventoryAdjustmentApprovalIntegrationTest` 23 例 + `InventoryAdjustmentApprovalTenancyIntegrationTest` 3 例
  + 前端 `inventory-adjustment-approval-model.test.ts` 27 例 + **`inventory-adjustment-approval-allowlist.test.ts` 16 例**。
  合计 **69 例**。

**关键设计决策**：

1. **阈值规则自持，不委托 identity 的通用审批规则服务**。取证：identity 的 `approval_trigger_rule`（V89）
   维度只有「金额 + 跨法人/跨部门/新供应商/高风险商品」，**没有「数量阈值」，也没有「调整单类型」**；
   `/approval-rules/evaluate` 的入参同样传不进去。要让 identity 表达这两维就得**改 identity 的规则模型**
   （另一个服务的写入所有权），而 identity 的 Flyway 版本号**没有预分配**给本车道 ⇒ 并行撞号会让它的 Flyway 直接炸。
   ⇒ 与 6.3.1.3 采购单审批（V262）**同口径自持**，能力声明里 `identityApproval` 恒 `available=false` 并点名原因。
   *代价*：「审批规则」在本仓有两套模型。**代价写进能力声明，不藏。**
2. **批准动作仍归 7.4.1.1，本模块不重复实现**。7.4.1.1 的会话日志已把边界写死：
   「阈值审批规则归 7.4.1.2 / 执行批准这一动作归 7.4.1.1」。⇒ 本模块 `/approve` 做的是
   **阈值判定 → 记审批链路 → 人数凑齐 → 调 7.4.1.1 的 `InventoryAdjustmentService.approve`**。
   7.4.1.1 的表结构与状态机**一行未动**，只在 `inventory_adjustment_order` 上**新增两个触发器**（纯增量）。
3. **阈值判定只有一个实现**：`inventory_adjustment_approval_match(...)` 是 **DB 函数**，服务端与数据库共用
   ⇒ 不存在「两套判定漂移」（照抄 6.3.1.3 的 `purchase_order_approval_match` 口径）。
   集成测试**真的**去数据库里硬改状态来证明 DB 级闸门有效。
4. **「本租户无生效规则时放行」**，而不是「无规则 = 一律拒绝」。理由：7.4.1.1 的基础管控
   （提交人≠审批人的 DB CHECK、必填理由、状态机、入账闸门）**一道不少** ——
   「没配规则就不审」是错的，「没配规则就**没有阈值档**」是对的。
   若做成「无规则即拒绝」，7.4.1.1 已交付的 25 个集成测试会当场红（它们不走本模块的提交路径）
   ⇒ 那是**跨车道回归**，不该由本车道制造。
5. **金额是「申报值」**：warehouse 库里**没有任何成本列**（`inventory_balance` / `stock_bucket` 逐列核对过），
   12 财务服务**不存在** ⇒ 调整单没有原生金额。金额由提交人**申报**、**提交那一刻冻结**、进 append-only 审批链路记录。
   会计影响落 **warehouse 侧自有事实**（`inventory_adjustment_valuation`，**登记额 / 生效额分两列**，
   照抄 6.3.1.3 的 `purchase_order_payable` 与 11.2.3.3 的 `supplier_claim_settlement` 写法）。

---

## 模块 6.6.2.2 转量产与建档

**这个功能做什么**：试产单跑通了，可以转正式量产：定下量产 BOM、生成正式 SKU（编码/条码/属性/报关属性）、
把量产参数交接给采购需求。建档是**真的去商品中心提交**的，不是本页自己编一个 SKU id。

**技术落地**：

- **迁移**：`V268__mass_production_and_sku_filing.sql`（trade，662 行）—— **8 张新表、零 ALTER 既有表**。
- **端点**：`/trade/mass-productions` × **18** 个 —— `capabilities` / `options` / `summary` /
  `trial-candidates` / `bom-version-candidates` / `spu-candidates` / `spu-attributes` /
  `{id}`（详情 GET + PUT）/ `{id}/transitions`（状态流转）/ `{id}/bom-lines` /
  `{id}/sku-filing`（GET + POST）/ `{id}/sku-filing/{filingId}/submit` /
  `{id}/handoffs` / `{id}/handoffs/replenishment`（交接 6.2.1.2）/ `{id}/cost-comparison`。
- **页面**：`/purchase/development/mass-production`（`apps/web/src/app/purchase/development/mass-production/page.tsx`
  + `components/purchase/mass-production-workspace.tsx` + `mass-production-model.ts`）——
  试产候选、量产 BOM 版本、正式 SKU 建档请求状态、目标成本对比（不可比时显示「不可比」+ 原因，**不显示 0**）、
  交接状态。**做不到的事写在常驻横幅上**（价格模型 5.3.1.1 未开发、生产计划下达 8.4.1 在 warehouse 独立库）。
- **测试**：`MassProductionIntegrationTest` 27 例 + `MassProductionTenancyIntegrationTest` 3 例 +
  `MassProductionRulesTest` 26 例 + 前端 `mass-production-model.test.ts` 14 例。合计 **70 例**。

**关键设计决策**：

1. **正式 SKU 的写入所有权在 catalog（3.1.2.1），本模块只落「建档请求事实」**。
   取证确认 catalog **有入口**（`POST /catalog/products/{id}/skus`，权限 `catalog.sku.create`；
   报关属性走 `PUT /catalog/customs-attributes/skus/{skuId}`）。⇒ 本模块**不建 SKU 表、不跨库写、不跨库 FK/视图**，
   只落「目标系统 + 目标键（SPU）+ 载荷快照 + 幂等键 + 状态机」的建档请求，
   再经 catalog 的**授权 API** 真提交，**SKU id 与编码从商品中心读回**（不是本服务编的）。
2. **🔴「结果未知（UNKNOWN）」是独立状态，且禁止重试**。catalog 的建档端点**不接受幂等键**
   ⇒ 超时/断连时无法判断建没建出来，重试会建出**第二个** SKU。所以 UNKNOWN 既不是 FAILED 也不是可重试态：
   页面显示「结果未知 · 不要重试」，**不提供重试按钮**，只提示先到商品中心核对该编码是否已存在。
   相对地，「部分完成（PARTIAL）」= SKU **已建成功**、只是报关属性没写进去 ⇒ 记 `target_sku_id`，
   可再提交**只补报关属性**，不会建第二个 SKU。
3. **建档失败/未知不抛异常**。抛异常会回滚事务，把「失败」这条事实一起抹掉。改成：把失败/未知**如实落库**，
   由控制器按**落库状态**映射 HTTP 码（CONFIRMED→200 / FAILED→400 / UNKNOWN→503 / PARTIAL→400），
   非 2xx 的响应体也带着建档请求本身。
4. **6.2.1.2 采购需求：同库但只触发重算**。6.2.1.2 的起订额与交期是**人工维护的策略**，
   本模块不顶掉它 ⇒ 量产参数**进不去**它的计算。因此只调 `ReplenishmentSuggestionService.compute()` **触发重算**，
   **绝不 INSERT/UPDATE 它的表**，留痕如实标 `massProductionInputApplied=false`。
5. **5.3.1.1 价格模型未交付 ⇒ 交接留痕标缺口，不 mock 算价**。跨币种**不折算**（5.2.2.1 未交付）。

---

## 模块 11.4.1.2 证据与申诉

**这个功能做什么**：平台纠纷（Amazon A-to-Z、eBay 争议、速卖通纠纷）有**严格的举证时限，超期自动判败**。
这一模块管两件事：把该交的证据**从系统记录里凑齐**成包，然后**在时限内向平台提交申诉**。

**技术落地**：

- **迁移**：`V269__platform_dispute_appeal.sql`（trade，1102 行）—— **4 张新表、零 ALTER 既有表**。
- **端点**：`/trade/platform-dispute-appeals` × **10** 个 —— `capabilities` / `{id}`（详情）/
  `{id}/text`（PUT 改我方立场）/ `{id}/materials`（POST 加举证材料）/
  `{id}/materials/{materialId}`（DELETE 移除）/`{id}/submit`（提交申诉）/
  `{id}/retry-submission`（重试提交）/`{id}/submission-attempts`（记录一次提交尝试）/
  `{id}/platform-reply`（记录平台回函）/`{id}/withdraw`（撤回）。
- **页面**：`/aftersales/disputes/appeals`（`apps/web/src/app/aftersales/disputes/appeals/page.tsx`
  + `components/aftersales/platform-dispute-appeal-workspace.tsx` + `platform-dispute-appeal-model.ts`）——
  时效倒计时、举证清单（必举证项 / 已具备 / 未覆盖）、证据包组装、立场编辑、提交与回函留痕。
- **测试**：`PlatformDisputeAppealIntegrationTest` 28 例 + `PlatformDisputeAppealTenancyIntegrationTest` 5 例
  + 前端 `platform-dispute-appeal-model.test.ts` 17 例。合计 **50 例**。

**关键设计决策**：

1. **「提交申诉」与库层守卫共用同一个判据**。机制：单个 DB 函数
   `platform_dispute_appeal_gate(tenant, dispute, appeal)`（**STABLE**）——
   服务的 Java 层与 DB 触发器守卫① **调同一个函数**（照抄 6.3.1.3 `purchase_order_approval_match` 的做法）。
   函数给出：窗口状态（`OPEN` / `EXPIRED` / `RELEASED` / `CLOSED` / `UNKNOWN`）、是否可提交、
   清单项数、**必举证项没具备数**、**必举证项没被材料覆盖数**、已覆盖数、未覆盖数。
2. **判定用 DB 自己的 `now()`**，函数**不接受调用方传入的时刻** ⇒ 服务层与触发器在同一时钟域，
   不会因 JVM 墙钟与库时钟的偏差误判超期。服务的 Java 层**没有**自己的 `evidence_deadline <= now()` 表达式。
   *测试直接断言这条性质*：查 `pg_proc` 确认函数存在；服务层判据与**直连调用该函数**的输出**逐字一致**；
   判据说不可提交时**直连 SQL 也被 DB 拒**；判据说可提交时直连 SQL **过得去**。
3. **举证材料只来自系统记录** —— 四类来源，全是 trade 自己的库里已有的事实记录（同服务同库，无跨库 FK），
   不接受任意外链材料。理由：纠纷举证要的是**可追溯的内部凭据**，不是随手贴的图。

---

## 模块 11.4.2.1 工单管理

**这个功能做什么**：非售后类的客服工单 —— 咨询、投诉、催单、平台政策咨询。
支持分配、认领、转派、SLA 计时、首次回复、关闭、满意度评价、升级（咨询↔投诉↔纠纷）。

**技术落地**：

- **迁移**：`V270__service_ticket_management.sql`（trade，480 行）—— **只 ALTER 预留表 + 新建 4 张附属事实表**：
  `aftersales_ticket_assignment`（转派历史，追加式）/ `aftersales_ticket_event` /
  `aftersales_ticket_satisfaction` / `aftersales_ticket_sla_snapshot`。
- **端点**：`/trade/service-tickets` × **13** 个 —— `capabilities` / `{id}`（详情）/
  `{id}/assign` / `{id}/auto-assign` / `{id}/transfer` / `{id}/claim` / `{id}/release` /
  `{id}/first-reply` / `{id}/status` / `{id}/close` / `{id}/satisfaction` / `{id}/escalate` / `sla-stats`。
- **页面**：`/aftersales/tickets`（`apps/web/src/app/aftersales/tickets/page.tsx`
  + `components/aftersales/service-ticket-workspace.tsx` + `service-ticket-model.ts`）——
  工单列表与详情、SLA 状态（达标 / 待回复 / 超期 / 未配时限）、责任人流转、升级出口、满意度。
- **测试**：`ServiceTicketIntegrationTest` 29 例 + `ServiceTicketTenancyIntegrationTest` 3 例
  + 前端 `service-ticket-model.test.ts` 36 例。合计 **68 例**。

**关键设计决策**：

1. **预留表是「扩展」而不是重建**。`aftersales_service_ticket` 是 11.1.1.1 在 V156 建的**本模块预留表**
   （`case_id uuid NOT NULL` + `UNIQUE(tenant_id, case_id)` + `owner_module='11.4.2.1'`）。
   V270 **只做 ALTER**，绝不 `CREATE TABLE` 同名表：建同名表会让 Flyway 直接炸；
   更要命的是 V156 已经把运行角色的权限与 FORCE RLS 策略配好了，
   重建出来的表没有那些东西 —— **一个「看起来对」的重建表是租户隔离的破口**。
2. **21 个新列全部带 DEFAULT 或可空**（早先车道的 INSERT 必须继续成立 —— 仓库里真实发生过
   「某车道把列改成 NOT NULL 且无 DEFAULT，当场破坏早先合入车道的 INSERT」的事故）。
   **状态词表只加 `WAITING_BUYER`**（蓝图输出句点名「待买家」），其余三态的拼写一个字不动。
   约束名**不硬写**（PostgreSQL 对内联 CHECK 的自动命名是实现细节），迁移里按 `pg_constraint` 反查后命中才删。
3. **「非售后类」的落法：工单挂在售后单之上**。蓝图说本模块管的是「非售后类客服工单」，
   而预留表要求 `case_id NOT NULL`。调和口径：工单**不替代**售后单 ——
   售后单管「退/换/补/修」，工单管「这次来问的这件事有人跟没有」；
   一张售后单**只转一次**工单（预留表自带 UNIQUE），重复转**报错并附上已有工单号**；
   工单与售后单的上下文**双向可达**。
4. **SLA 判定：真身只有 DB 一处 + 达标结论在关闭那一刻冻结**。时限是**真实 timestamptz 列**，
   由租户 SLA 策略（`aftersales_reply_sla`，V164）按「平台精确档 > `''` 兜底档」解析当时生效的那一档；
   **解析不到就报错**（不编默认小时数）—— 编出来的时限会让「没配 SLA」和「承诺 48 小时」
   在绩效口径上混成同一件事。判定函数 `aftersales_ticket_sla_state` 是 **IMMUTABLE**。

---

# 本批收尾

## 进度

| 指标 | 开工时 | 收工时 |
| --- | --- | --- |
| 已交付 | 287 | **297**（+10） |
| 进行中 | 0 | 0 |
| 未开发 | 178 | **168** |
| 完成率 | 61.7% | **63.9%** |
| 距 465 还差 | 178 | **168** |

分阶段：卷 06 采购与供应 D6 补齐（6.1.2.2 / 6.6.2.2），
卷 11 售后与退货补齐 11.4.1.2 / 11.4.2.1 / 11.5.1.1，
卷 14 广告与增长**从零起量**（14.1.1.1 / 14.1.3.1 / 14.2.2.2 三个 L3 首次交付）。

## 容量闸判定

| 闸 | 取值 | 判定 |
| --- | --- | --- |
| 静默窗口闸 | `SILENT_WINDOW=0` → 全天运行 | 放行 |
| 强度档位 | 当前 < `TOKEN_HORIZON=2026-10-25T00:00+0800` → **full 模式**（LANES=4 / THROTTLE=50） | 满档 |
| 批次节流闸 | 距上批 **55 分钟** ≥ 阈值 50 分钟 | 放行（通过后才写时间戳） |
| 温度闸 | `Note: No thermal warning level has been recorded` | 正常 |
| 配额闸 | 开工时 running = 1 < `RUNNING_QUOTA=6` | 放行 |
| PG 闸 | 派发时带标签容器 = **0** < 6；**无标签孤儿容器 0 个** | 放行 |
| load 闸 | `3.30/2.96/3.14`，远低于 `LOAD_LIMIT=12` | 放行 |

**温度观测**：全程 `pmset -g therm` 报「No thermal warning level has been recorded」——
**无任何降频项**。L2 期间 PG 峰值 **41**（40 个属于本轮 L2 自己的 session —— 仓库的集成测试
每个测试类各起一个容器），ryuk 三个 session 都在跑，**结束后全部被回收，无孤儿容器**。

## 吞吐预期（按当前档位现算，不引用任何历史文档里的写死数字）

当前档位：`CRON=0 * * * *`（24 次/天）+ `SILENT_WINDOW=0` + `LANES=4` + `THROTTLE_MIN=50`
⇒ 名义吞吐 = 4 × 24 = **96 个/天**；乘协议里的综合折损系数 0.70 ⇒ **约 67 个/天**。

但**本轮不是正常节奏**：它包含了一次 L2 红 + 一次修复车道 + 一次完整重跑 L2，
实际用时约 2 小时才交付 10 个。正常的干净轮次是 4 条车道 / 1 轮 L2。
⇒ **诚实区间：约 30–60 个/天**，剩 168 个未开发 ⇒ **约 3–6 天**（不承诺单点日期）。

## 风险提示

1. **🔴 调度闸在本轮没有真正互斥** —— 并发会话在同一个集成头上 commit + push 了。
   本轮代码未受影响（那两个 commit 只动 `docs/`），但机制上闸是漏的。
   下一轮开工第一件事应核对闸目录的绝对路径是否跨会话一致。
2. **11.5.1.1 退货分析没有自己的 `return-analysis-allowlist.test.ts`**
   （其余 5 个新模块都有）。它**按协议跑了全量 allowlist 回归**（63 文件 / 351 例），
   所以不是没验证，只是缺一条专属回归测试 —— 建议下一轮补。
3. **V269:136 的 `ON DELETE CASCADE` 语义变化**已记在迁移与说明里：
   删纠纷不再自动连带删申诉。取证是全仓无 DELETE 路径会删 `platform_dispute`，所以那条 CASCADE 此前是空转的；
   但若 11.4.1.1 将来要支持删纠纷，「连带删还是拦住」应由那条线的拥有者决定。
4. **卷 12 财务 / 卷 13 报表 / 卷 1 前端聚合**三个卷仍被微服务闸挡住
   （`finance` / `reporting` / 前端聚合服务不在集成头），
   `serviceBlocked` 已列出 12.1.1.1 / 13.1.1.1 / 1.1.1.1 等样本 —— 这三卷在服务落地前无法排车道。
5. **仓库基线的 trade 契约存在既有漂移**（本轮三条车道各自独立发现并各自还原了它，
   统一求并后 10 个命名空间的端点一个不缺）—— 漂移的**根因**在更早批次，本轮未追责，留给下一轮。

## 收尾三件套

| # | 动作 | 结果 |
| --- | --- | --- |
| ① | **写说明** | ✅ `docs/sessions/2026-10/2026-10-21-batch-55-merge-5-lanes-and-dispatch-4.md`（本文件，11 个模块各一节 + L2 两轮记录 + 收尾） |
| ② | **commit** | ✅ 说明与台账进版本库（台账由并发会话在 `010becb4` 提交，本 commit 补齐说明的 L2 复跑绿结果） |
| ③ | **push** | ✅ `git push origin integration/module-dev`（**只推集成头，未碰 main**） |

---

# 本批并入的第二批：5 条车道在本轮 L2 期间落地

⚠️ **时序说明（必须写清，否则这批的归属会被误读）**：本轮开工时并入的是 **5 条**车道（上节）。
本轮 L2 跑到一半时，**另外 5 条车道陆续 `land`** —— 它们是本轮**派发**的 4 条
（6.1.2.2 / 14.1.1.1 / 14.1.3.1 / 14.2.2.2）加上上一批遗留的 11.5.1.1 退货分析。

调度者先在**临时 worktree 里做了一次空合并演练**（`.tmp/mergetest`，未触碰集成头），
确认 5 条分支**逐条 CLEAN、零冲突**（`api-proxy.ts` 不在 union 驱动器覆盖范围内，
靠本轮指定的 4 个互不重叠插入锚点保证），且合并后：台账 **465 行无重复**、
5 个新模块行均为「已交付」、迁移 V271–V275 **无版本号撞车**。
⇒ 于是把它们一并并入，**用一次 L2 覆盖全部 11 个模块**，而不是让下一轮再跑一遍全量。

## 模块 6.1.2.2 同款货源推荐

**这个功能做什么**：某个 SKU 缺货或质量不达标时，推荐可替代的同款货源（同款不同供应商、
历史买过这货的供应商、价格对比），并能一键生成采购申请草稿。

**技术落地**：

- **迁移**：`V272__source_alternatives.sql`（trade）—— 候选、判定、政策、运行等新表，零 ALTER 既有表。
- **端点**：`/trade/source-alternatives` × **12** 个 —— `capabilities` / `options` / `targets` /
  `overview` / `policy`（GET + PUT）/ `runs`（GET + POST）/ `runs/{runId}` /
  `skus/{skuId}/candidates`（候选清单）/ `candidates/{candidateId}/decision`（人工确认）/
  `candidates/{candidateId}/purchase-request`（一键生成采购申请草稿）。
- **页面**：`/supply/alternatives`（`app/supply/alternatives/page.tsx` + `components/supply/source-alternative.tsx`
  + `source-alternative-model.ts`）。
- **测试**：`SourceAlternativeIntegrationTest` 18 例 + `SourceAlternativeTenancyIntegrationTest` 3 例
  + 前端 `source-alternative-model.test.ts` 21 例 + `source-alternative-allowlist.test.ts` 6 例。合计 **48 例**。

**关键设计决策**：

1. **同款判定只认三种证据，强度不同、置信度不同、每条写明依据**：`SKU_LINK` **确证**
   （6.1.2.1 人工确认过的货源↔SKU 关联）= 1.0000，**不再要求人工确认**；
   `PURCHASE_HISTORY` **强线索**（历史真下过、没被驳回的单）= 0.5000~0.7500；
   `TITLE_SPEC_MATCH` **弱线索**（标题与规格完全一致 0.4000 / 标题包含且规格一致 0.3000）。
   **三种证据都不成立的货源根本不进候选清单。**
2. **刻意不做图片相似度自动判定**：本仓没有视觉相似度服务，凭空实现「图片哈希像所以是同款」
   就是把采购单指向错误商品。页面只把目标 SKU 的参考图与候选图**摆给人看**，`capabilities` 明写未交付。
3. **弱匹配必须人工确认才能生成采购申请**：DB 层 CHECK 钉死（`SKU_LINK` ⇔ 不需确认，其余 ⇔ 一定需要），服务层再挡一道。
4. **置信度与排序分成两个数**：置信度回答「是不是同款」，排序分回答「先看哪一条」。
   合并后运营会把「便宜」误读成「同一个货」—— 那正是误判采购的起点。
   排序分由**租户级权重**（价格/评级/历史采购/历史交期，四项和**精确等于 1**）合成，不是写死的常数。
5. **候选落成快照**，不每次实时重算 —— 证据（价格区间、交期、评分）都是那一刻的事实。
6. **没有破坏「一张事实表一个写入所有者」**：`purchase_request` 的写入所有者是 6.2.2.1，
   本模块**没有**直接插表，而是给它加了一个窄方法 `createFromSuggestion`，
   并把 `create()` 的落库段抽成私有的 `persistRequest(...)` 让两条来源共用一条落库路径。

## 模块 14.1.1.1 活动列表

**这个功能做什么**：广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、
销售额、ACOS/ROAS，可按维度筛选排序，并支持批量暂停/启用/调预算。

**技术落地**：

- **迁移**：`V273__ad_campaigns.sql`（channel）—— **4 张新表、零 ALTER 零 DROP 任何既有表**：
  `ad_campaign` / `ad_campaign_daily_metric` / `ad_campaign_sync_run`（水位来源）/ `ad_campaign_operation_log`（批量审计）。
  四表 `tenant_id NOT NULL` + ENABLE/**FORCE** RLS + **data_scope（RESTRICTIVE）按店铺 owner/部门收敛**
  （对应蓝图输入里的「用户数据范围」，与 V171 listing / V203 同构）+ GRANT。
- **端点**：`/channel/ad-campaigns` × **8** 个 —— `capabilities` / `options` / `campaigns`（列表，真分页 + 真筛选 + 真排序）/
  `campaigns/{id}` / `by-external`（按外部 id 取，供同批 14.2.2.2 自动规则调用）/ `imports`（**真实写入口**）/
  `bulk-operations`（批量操作）/ `sync-runs`。
- **页面**：`/ads/campaigns`（`app/ads/campaigns/page.tsx` + `components/ads/ad-campaigns.tsx` + `ad-campaign-model.ts`）。
- **测试**：`AdCampaignIntegrationTest` 16 例（含**多租户三断言**：两租户互不可见、伪造 tenant header 无效、
  连接池压到 1 后不串租户）+ 前端 `ad-campaign-model.test.ts` 24 例 + `ad-campaign-allowlist.test.ts` 5 例。合计 **45 例**。

**关键设计决策**：

1. **平台同步器未交付 ⇒ 本模块自带真实写入口**（`POST /imports`）：蓝图的输入写着「广告数据同步结果」，
   而上游同步器本批不存在。两种做法摆在面前：① 用空数组/硬编码常量假装有投放；
   ② **给活动与表现数据一个真的落点**。选 ② —— 一行假数据都没有；
   且**失败的同步只登记失败**（不写活动、`data_as_of` 为空 = **不推进水位**、必须带失败原因，DB 层 CHECK 钉死）。
2. **「没有投放」与「还没同步到数据」拆成三态枚举** `emptyReason` =
   `NO_SYNCED_DATA`（从没成功同步过 ⇒ **不能**说「本租户没有投放」）/ `NO_CAMPAIGNS`（同步成功过、范围内确实一条都没有）/
   `FILTERED_EMPTY`（范围里有活动，被筛掉了）。判据用**范围口径**（只按平台+店铺数一次活动数），
   否则两个范围会互相污染。
3. **归一类型与原始类型并存**：活动上三列 `platform_ad_type`（平台原文）/ `internal_ad_type`（归一后）/
   `ad_type_mapping_source`，**由 DB 触发器从 14.1.2.2 的 `ad_type_profile` 复制** ——
   绕过服务层直接写 SQL 也改不掉（触发器挂在**所有** UPDATE 上，不是 `UPDATE OF 某列`）。
   `UNMAPPED` 是显式合法值；`PARTIAL_FIELD_COVERAGE` 是**单条广告报文**粒度的状态，
   **不是活动级属性**，本模块刻意不把它伪装成活动级状态。
4. **ACOS/ROAS 口径写进返回值**（`metricDefinitionNote` 随汇总返回，界面表头直接显示）；
   分母为 0 给**空值（不可比）而不是 0**。汇总**先汇总再相除**（总消耗/总销售额），
   **不是各活动比率的平均** —— 后者是数学错误（两条 ACOS 100% 的活动平均成 50% 会让人以为整体在盈亏线上）。
5. **批量护栏**：单活动单次调整上限 5000 / 一次批量总额上限 20000 / 单活动预算上限 100000 / 单次最多 200 条；
   超限**逐条拒绝**并写审计（`APPLIED`/`REJECTED`/`SKIPPED` 三种结果都留痕）。
   **只接受显式选中的 id** ——「按筛选结果全选 + 变更预览 + 异步批量任务」是 14.1.1.3（未交付），
   capabilities 里显式 `available=false`，**不用空列表冒充已交付**。

## 模块 14.1.3.1 关键词管理

**这个功能做什么**：关键词与搜索词的分析台：搜索词报告、关键词表现（展示/点击/转化/CPC/ACOS）、
否定词建议、词频与排名，支持批量调价与否定 —— 用来快速找出「高曝光零转化」的浪费词。

**技术落地**：

- **迁移**：`V274__ad_keywords.sql`（channel）—— **6 张新表、零 ALTER 既有表** + 3 个护栏触发器
  + ENABLE/FORCE RLS + RESTRICTIVE 数据范围策略。
- **端点**：`/channel/ad-keywords` × **17** 个 —— `capabilities` / `filter-options` / `{keywordId}`（GET/PUT/DELETE）/
  `metrics`（POST 落指标）/ `search-terms`（GET+POST）/ `negative-suggestions` / `negatives`（GET+POST）/
  `negatives/{negativeId}/result` / `bulk-actions`（GET+POST）/ `bulk-actions/{actionId}` /
  `report` / `frequency`。
- **页面**：`/ads/keywords`（`app/ads/keywords/page.tsx` + `components/ads/ad-keywords.tsx` + `ad-keywords-model.ts`）。
- **测试**：`AdKeywordIntegrationTest` 21 例 + `AdKeywordTenancyIntegrationTest` 4 例
  + 前端 `ad-keywords-model.test.ts` 20 例 + `ad-keywords-allowlist.test.ts` 5 例。合计 **50 例**。

**关键设计决策**：

1. **搜索词报告是发现浪费的入口**：高曝光零转化词要能一眼识别并批量否定，
   否定建议必须给出依据（曝光/点击/转化/花费/ACOS 的实际数值），不能只丢一个词表。
2. **数据截至时间显式**：同步未交付时给出数据截至时间，区分「没有跑这个词」与「还没同步到数据」。
3. **活动维度用外部引用，不建跨车道 FK**：按「平台 + 店铺 + 外部活动 id / 外部关键词 id」定位 ——
   14.1.1.1 是同批并行车道，它的表同批才落地，跨车道 FK 会让两条车道互相破坏。
4. **归一类型沿用 14.1.2.2 的 `AdTypeDiffService.resolve()`**：`internal_ad_type` 与 `platform_ad_type` 并存，
   `UNMAPPED` 是显式合法值，**声明与解析结果不一致即拒**。
5. **名次/词频引用 4.5.1.2 的 `listing_keyword_rank_snapshot` / `listing_keyword`**，不自建第二套排名口径。

## 模块 14.2.2.2 自动规则

**这个功能做什么**：给广告配一套自动化规则 —— ACOS 超标就降价、ROAS 达标就加预算、消耗完就暂停、
花了钱没转化就停投。规则有优先级、有单次与总额上限、能先「试运行」看看会怎么动。

**技术落地**：

- **迁移**：`V275__ad_rules.sql`（channel）—— **8 张新表 + 3 个触发器 + 12 个索引 + FORCE RLS + GRANT/REVOKE，
  零 ALTER 既有表**。
- **端点**：`/channel/ad-rules` × **19** 个 —— `capabilities` / `rules`（GET+POST）/ `rules/{id}` /
  `rules/{id}/status` / `rules/{id}/conditions` / `metrics`（GET+POST）/ `metrics/{id}` /
  `runs`（GET+POST）/ `runs/{id}` / `hits` / `executions` / `executions/{id}` /
  `dispatches` / `dispatches/{id}/cancel` / `effects` / `effects/evaluate`。
- **页面**：`/ads/rules`（`app/ads/rules/page.tsx` + `components/ads/ad-rules.tsx` + `ad-rules-model.ts`）。
- **测试**：`AdRuleIntegrationTest` 20 例（含**多租户三断言**，连接池压到 1）+
  前端 `ad-rules-model.test.ts` 23 例 + `ad-rules-allowlist.test.ts` 5 例。合计 **48 例**。

**关键设计决策**：

1. **上限是执行路径上的三道闸**（单次金额+比例取更严 / 总上限按窗口累计金额+次数 / 上下限保护）。
   超限**拒绝并留痕，绝不静默截断** —— 测试断言 `after_value` 仍是「本来会变成多少」、
   `applied_delta` 为空、无待下发动作。
   ⚠️ 车道自己在实现中抓到一个真 bug：总上限原先按**带符号** `applied_delta` 累加，
   降价是负数会互相抵消 ⇒ 总上限形同虚设。已改为 `sum(abs(...))`。
2. **试运行取更严者**：规则 `dry_run`（默认 true）∧ 运行 `run_mode` ⇒ 一次 APPLY 推不动开着试运行的规则；
   DRY_RUN / APPLIED 在执行记录里分得开。
3. **优先级真参与判定**：同目标取一条，落选的照样留痕并写明为什么。
4. **比率不可比 ≠ 0**：ACOS/ROAS 是 GENERATED 列，销售额为 0 ⇒ 两者皆 null。
5. **与 14.1.1.1 零耦合**：外部 id 定位对象，指标落本模块自己的表 + 显式登记入口，零跨车道 FK。
6. **明确未交付并显式声明**：平台侧真实下发（`platform_dispatch` available=false，只落 PENDING 待下发，
   界面写「待下发」不是「已生效」）、上游 14.3 报表、14.1.1.1 活动列表、14.2.2.1 预算分配
   —— 四项均以 `available=false + ownerModule + 影响` 显式声明，未用空列表或 0 冒充。

## 模块 11.5.1.1 退货分析

**这个功能做什么**：按店铺/SKU/类目/原因/批次统计退货率、拒退率、退货金额趋势与处理时效，
每个维度都能下钻，并标出异常 —— 用来判断「退货率从 3% 升到 5%」到底严重不严重。

**技术落地**：

- **迁移**：`V271__trade_return_analytics.sql`（trade）。
- **端点**：`/trade/return-analytics`（`ReturnAnalyticsController` / `Service` / `Models`）。
- **页面**：`/aftersales/return-analysis`（`app/aftersales/return-analysis/page.tsx`
  + `components/aftersales/return-analysis-workspace.tsx` + `return-analysis-model.ts`）。
- **测试**：`ReturnAnalyticsIntegrationTest` 26 例 + `ReturnAnalyticsTenancyIntegrationTest` 3 例
  + 前端 `return-analysis-model.test.ts` 15 例。合计 **44 例**。

**关键设计决策**：

1. **加粗句「退货率必须与销售额同口径比较」落在四个层次，不只是「一个比率字段」**：
   ① **币种是报表的第一维** —— 分子与分母必须同币种才允许相除，`overview` **按币种分组**返回，
      响应里**没有**跨币种的合计金额与合计退货率（`totals` 只保留与币种无关的计数）；
   ② **每个维度用自己口径的总体率做基准** —— 拿全店销售额当每个 SKU 的分母，报出来的是「占比」不是「退货率」；
   ③ **分子分母取自真表真列** —— 分母唯一来源是 `sales_order.grand_total_amount`
      （`status='SUBMITTED'`、时间轴 `submitted_at`），不自拼、不用第二套口径；
   ④ **算不出来返回 `null` 而不是 `0`** —— 没有销售额就没有退货率，报 0 会被读成「这个维度很健康」，
      前端一律渲染成「—」/「无样本」。
2. **口径三分法，每一行都带标记**（三者**不可比**，不标出来就会把 3% 和 30% 直接比大小）：
   `CASE_LEVEL`（`aftersales_case.requested_amount` / `sales_order.grand_total_amount`）、
   `LINE_LEVEL`（`aftersales_case_line.requested_amount` / `sales_order_line.line_amount`）、
   `QTY_LEVEL`（`return_receipt_line.received_qty` / 关联 SKU 同期 `sales_order_line.quantity`）。
   每一行都带 `caliber`，前端每一行都渲染口径徽标。
3. **上游事实表逐条核对过**（10 分钟自检的结论，不构成 BLOCKED）：拒退率的唯一来源是
   `rma_authorization.rma_status`；店铺维度来自 `platform_order.platform_code/shop_code`
   （**`sales_order` 本身没有 shop 列**，必须经平台单投影关联）；退款金额来自
   `refund_application.refund_amount/currency`；类目映射走 trade 侧 `return_quality_category_sku`
   （catalog 的商品类目在**独立库**，跨库 join 被 AGENTS.md 禁止），未映射 SKU 落「未归类」桶。
4. **拒退率** = `REJECTED` ÷（`AUTHORIZED` + `PARTIALLY_AUTHORIZED` + `REJECTED`）——
   **草稿与 EXPIRED 不进分母**（它们还没有结论，把它们算进分母等于把「还没批」当成「批了不批」），
   部分授权单列，另报件数口径的未授权占比。
