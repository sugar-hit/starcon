# 4.3.2.1 内容回写（channel 服务）

**日期**：2026-10-19
**车道**：`feat/4-3-2-1`（worktree `.worktrees/4.3.2.1`）
**Flyway**：`V187__listing_content_writeback.sql`（预分配版本号）

## 决策摘要

### 一、模块落 channel 而不是 catalog

沿用 4.1.1.1 的物理判据：内容回写要按**店铺 / 站点**收敛可见性，`shop` / `shop_marketplace`
是 channel V1 的事实；而目标 listing、内容草稿、平台侧内容快照全部在 channel 同库。
放 catalog 就得为了店铺维度去读 channel 的表 —— 那是跨服务 SQL。

### 二、核心不变量「内容回写不触碰价格与库存」的三层物理落法

蓝图的加粗句不是靠"服务层记得别写"，而是三层同时成立（缺一都不算交付）：

| 层 | 做法 |
| --- | --- |
| 取值域 | `change_types` / `change.field` 的 CHECK 只有 5 个内容字段；`overrides` 的 CHECK 只允许这 5 个键。**语法层面写不出 price / stock** |
| 零金额列 | 三张表**无任何金额列**。集成测试逐表断言 `information_schema.columns` 里 numeric/float/money 计数为 0 |
| 服务层 | 含 PRICE/STOCK/QUANTITY/CATEGORY 的请求（`changeTypes` 或 `overrides` 任一侧）**逐个点名 422** |

**为什么逐个点名而不是静默忽略**：静默忽略会让用户以为价格也推上去了，而它根本没有。
一个"部分成功"的谎报比一次明确的拒绝危险得多。

**类目为什么也拒绝**：职责句列的是「改标题、改图、改描述、改 A+」四件事，类目不在其中；
且草稿里已经带着类目，由 4.3.1.2 发布流程统一上线更安全（V177 的 `guard_listing_live_content`
只放开发布流程写线上）。

### 三、「三条通道独立失败独立重试」的具体落法

- **retry 只打回自己的 FAILED**：DB 触发器 `guard_content_writeback_terminal` 让
  已 SUCCEEDED 的打不回去（23514），在途的不能被悄悄撤回（那等于撤回一次已发出的平台写请求）。
- **重试复用同一个 02 队列任务**：`operation_id` 保留、幂等键不变 ⇒ 平台侧不出现第二个任务。
  这是 V2 `idempotency_key` 的原意（V169 第五节："避免重试造成平台侧重复写入"）。
- **各记各的账**：内容通道的在途 / 窗口计数只数自己的任务表 ⇒ 与 4.3.1.2 批量发布
  共用同一份真实配额配置（V183）但**互不占额度**。

### 四、`SKIPPED_NO_CHANGE` 是独立状态而不是「成功」

改后与线上逐字相同的那条**永不派发**，且不报成功：
对平台的写调用是真实的外部副作用，无意义的写不该发生；而谎报"已推送"比不推送更难发现。
进度上它是**终态**（算 100%），不留在 0% 让运营以为还有事没做完。

### 五、「经 02 队列」是结构事实不是描述

`operation_id` 外键 + 一对 CHECK：只有真正派发过的任务才有队列行，只有从未派发的才一定没有。
少了后半句，"绕开队列直接调平台"的实现能写出 DISPATCHED 却查不到队列任务，
2.5.1.1 的回写任务列表里那条就凭空消失。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么值得 |
| --- | --- | --- |
| 复用 V2 `platform_operation.operation='PUBLISH'` + `payload.contentWriteback=true` | 新增一个 `CONTENT_WRITEBACK` 取值域 | V2 的 CHECK 是 2.5.1.1 的对外契约，改它等于替别的车道改契约。与 V169 把「批量下架」映射成 PUBLISH+`payload.delist` 同一手法 |
| 共用 V183 `publish_rate_limit` 而不另建限流表 | 一个"内容通道专属"的限流配置 | 平台 API 配额是**客观事实**，一份配置两处读比两份可能打架的配置更真；各记各的账已经解决了互相干扰 |
| 不做预览步骤 | "确认前先看看" | 回写不是编辑 —— 没有草稿态可停。改前/改后在提交那一刻就落库，界面上看到的与推出去的是同一批数据（"预览一套、执行另一套"结构上不可能） |
| 描述 / A+ 走显式覆盖值而不是等 4.2.1.1 | 自动从内部草稿取这两段 | V177 草稿**没有** description / a_plus 列。宁可显式要求输入并写明归属，也不编一个假值；既无草稿又无覆盖值判 `SKIPPED_NO_SOURCE` |

## 文件清单

**新增（后端）**
- `backend/services/channel/src/main/resources/db/migration/V187__listing_content_writeback.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/ContentWritebackModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ContentWritebackService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ContentWritebackController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ContentWritebackIntegrationTest.java`

**新增（前端）**
- `apps/web/src/components/listing-content-writeback/content-writeback-model.ts`
- `apps/web/src/components/listing-content-writeback/content-writeback-model.test.ts`
- `apps/web/src/components/listing-content-writeback/content-writeback-workspace.tsx`
- `apps/web/src/app/listings/content-writeback/page.tsx`
- `apps/web/src/app/api/channel/listing-content-writeback-allowlist.test.ts`

**改动**
- `apps/web/src/lib/api-proxy.ts`（**纯增量**新增一条元素，未动任何既有条目）
- `packages/api-contracts/src/channel.openapi.json` + `contracts/channel.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改本模块那一行）

## 开发中实测修掉的四个真缺陷

1. **任务 CHECK 漏了 `SKIPPED_NO_CHANGE`** —— 它是"从未派发"的状态，
   「派发必有派发时刻」与「非 PENDING 必有队列行」两条判据都会误伤它。
   这个状态是本模块自己发明的，CHECK 也得跟它一起改。
2. **写 02 队列时误用限流行解析出的 `shop_id`** —— 平台级限流行解析出的是 NULL，
   而 `platform_operation.shop_id` NOT NULL ⇒ 整条通道在最常见的"平台级限流"配置下插不进去。
   正确来源是**任务自己的店铺**（限流行只是配额配置，不是这条任务的归属）。
3. **租户隔离断言原本用 Testcontainers 超级用户连接** —— 超级用户绕过 RLS，
   用它查"读不到对方数据"会永远得到"读得到"。**那不是隔离被破坏，是这个断言本身失效**
   （4.1.1.2 已实测踩过一次，这次直接照它的 `countAsTenant` 写）。
4. **allowlist 正则把 tasks 段的动作写成可选** `(?:changes|report)?` ——
   放行了一个后端不存在的裸 `tasks/{uuid}` GET。放行不存在的形状比 404 更糟：
   界面会以为功能已交付。

## 验证

- L0：`./mvnw -q compile` ✅ / `pnpm typecheck` ✅（3/3 tasks）
- L1：`./mvnw -pl services/channel -am test -Dtest='ContentWritebackIntegrationTest'` → **20 例全绿**
- 前端：`pnpm vitest run`（本模块两个文件）→ **26 例全绿**
- **allowlist 全量回归**（改了共享累积文件，必跑）：`pnpm vitest run apps/web/src/app/api/trade`
  → **41 文件 / 218 例全绿**，确认未误伤其他车道分支

## 后续待办

- **4.3.2.2 平台状态回流**未交付 —— 它是在途任务的回报方。页面显示"在途（等平台回报）"
  是预期状态，接入后 `/tasks/{id}/report` 才有真实调用方。
- **4.2.1.1 刊登编辑器**未交付 —— 描述与 A+ 的内部待推送内容要等它；届时
  `listing_content_draft` 加上这两列，`content-writeback-model.ts` 的显式覆盖值输入
  可以退成"可选"而不是"必填"。
- **3 商品主数据的变更事件**是跨服务的 —— 真要"变更即自动回写"需 catalog 侧提供授权端点。
  本批的入口是用户显式触发，`capabilities.upstream_change_events` 已 `available=false` 写明。
- 本模块**没有**自己的页面路由入口（侧边栏），`/listings/content-writeback` 目前只能直接访问 ——
  与 4.3.1.2 的 `/listings/publish` 同一状况，未在本车道改动导航（导航是多车道共用的累积文件）。
