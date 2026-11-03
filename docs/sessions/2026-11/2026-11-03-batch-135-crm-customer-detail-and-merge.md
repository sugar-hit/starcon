# batch-135 —— 15.3.1.2 客户详情 / 15.3.1.3 跨店合并 / 5.1.2.1 allowlist 守卫修正

> 本批由 round-163（cron tick）收割产出。上一轮 round-162 的 tick 与它派出的 3 条 worker 会话
> **全部被网络错误掐断**（tick：`ERR_CONNECTION_CLOSED`；workers：`ERR_CONNECTION_CLOSED` /
> `ERR_NETWORK_IO_SUSPENDED`），留下 3 条心跳尚未过期（TTL 90 分钟）的「死车道」占住调度闸，
> 且 batch-134 的 L2 前端红（3 条 allowlist 假红）当时**没有修复**就提交了。
> 本轮先取证 → 接管闸 → 收割死车道产出 → 补跑 L2。

---

## 模块 15.3.1.2 客户详情

**这个功能做什么**：打开一个客户的全档案 —— 基本信息、跨店铺身份线索、订单 / 售后 / 消息 / 评价 / 接触记录
**合并成一条统一时间线**，一次查询拿到全部，不用在 5 个系统之间翻找。

**技术落地**：

- **迁移**：`backend/services/crm/src/main/resources/db/migration/V435__crm_customer_detail.sql` ——
  两张**新表**（crm 服务第二十一个迁移）：`crm_customer_timeline_entry`（统一时间线条目，
  唯一键 (租户, 客户, 来源类别, 来源标识) ⇒ 重建幂等）、`crm_customer_timeline_run`（重建运行台账）。
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + 租户守卫触发器 + `GRANT`。
  **零 ALTER 任何既有表**（V432 客户列表、V436 生命周期一个字都没动）。
  静态预检：`ADD COLUMN` / `SET NOT NULL` / `ADD CONSTRAINT … CHECK` **三项零命中**。
- **端点**：`/crm/customers` 前缀 **3 条**（`CustomerDetailController.java`，crm 服务，jOOQ 显式 SQL）
  - `GET /crm/customers/{id}` —— 全档案详情视图
  - `GET /crm/customers/{id}/timeline` —— 统一时间线（订单 / 消息 / 售后合并）
  - `POST /crm/customers/{id}/timeline/rebuild` —— 显式重建时间线（需 `crm.customers.update`）
- **页面**：`/crm/customers/[id]` —— `apps/web/src/app/crm/customers/[id]/page.tsx` +
  `components/crm/customer-detail-model.ts`（纯函数口径层）+ `customer-detail-workspace.tsx`（真实工作区），
  加载 / 空 / 错误三态齐全。
- **测试**：后端 `CustomerDetailIntegrationTest` 10 例 + `CustomerDetailTenancyIntegrationTest` 4 例 = **14 例**；
  前端 26 例（口径层 + allowlist）。

**关键设计决策**：

1. 🔴 **时间线物化在本模块自己的表里**，不是每次请求现算 —— 详情页打开时不能为了拼一条时间线
   去串行打 5 个上游（订单 / 售后 / 消息 / 评价 / 接触记录）。读端点**不写库**，
   自动重建由前端首次打开时显式触发 ⇒ 读权限不引发写入。
2. 🔴 **「读不到」与「没有数据」是两件事** —— 上游读不到 ⇒ 该来源标 `available=false` + 写明原因与归属模块，
   **一条条目都不落**（绝不编数据）；整次运行状态 `PARTIAL` / `FAILED`，
   `FAILED` 时 `entry_count` 必须为 0（DB 触发器 `guard_crm_customer_timeline_run_result` 强制）。
   前端文案层同样钉死：不可用时输出「不可用（原因）」而**不是**「0 条」——
   画成 0 条等于替上游说「这个人从来没下过单」。
3. 🔴 **跨店铺身份关系按「合并映射尚未交付」的形态实现** —— 本模块不建 / 不读 / 不写
   `crm_customer_merge_*` 任何一张表（那是 15.3.1.3 的写入所有权），也没有造一张「假的合并表」。
   合并状态恒为 `NOT_MERGED`，归属写明 `15.3.1.3`；跨店铺关系只列**线索**（脱敏联系方式指纹 / 买家名一致）。
   **线索不是合并结论** —— 同名可能是两个人，掩码邮箱也可能是同一个人。
4. 🔴 顺带修掉一个**真 bug**：查询串里的字面 `+` 会让整个售后窗口作废（`+` 被当成空格）。
5. 排序统一时钟域：`occurred_at DESC, indexed_at DESC, id DESC`，后两个键与运行记录的
   `started_at` / `finished_at` 全部取自 **DB 时钟**，不拿 JVM 墙钟与 DB 时钟比大小。

---

## 模块 15.3.1.3 跨店合并

**这个功能做什么**：把散落在不同店铺、看起来是同一个人的客户**识别成候选**（只给建议），
由人工逐条确认或驳回；确认后可**拆分**回原来的多个来源，合并前后的映射全程留痕。

**技术落地**：

- **迁移**：`backend/services/crm/src/main/resources/db/migration/V437__crm_customer_merge.sql` ——
  **五张新表**：`crm_customer_merge_logical`（逻辑客户）、`crm_customer_merge_mapping`（合并映射）、
  `crm_customer_merge_candidate`（候选队列）、`crm_customer_merge_operation`（操作记录）、
  `crm_customer_merge_run`（候选生成运行台账）。
  全部 `tenant_id NOT NULL` + FORCE RLS + 守卫触发器 + GRANT。
  静态预检：`ADD COLUMN` / `SET NOT NULL` / `ADD CONSTRAINT … CHECK` **三项零命中**。
- **端点**：`/crm/customer-merge` 前缀 **11 条路径**（`CustomerMergeController.java`）
  `GET /capabilities`、`GET /candidates`、`GET /candidates/{id}`、`POST /scan`、
  `POST /candidates/{id}/confirm`、`POST /candidates/{id}/reject`、`GET /logical`、`GET /logical/{id}`、
  `POST /logical/{id}/split`、`GET /operations`、`GET /runs`。
- **页面**：`/crm/customer-merge` —— `apps/web/src/app/crm/customer-merge/page.tsx` +
  `components/crm/customer-merge-model.ts` + `customer-merge-workspace.tsx`。
- **测试**：后端 `CustomerMergeIntegrationTest` 18 例 + `CustomerMergeTenancyIntegrationTest` 4 例 = **22 例**；
  前端 `customer-merge-model.test.ts` 22 例 + `customer-merge-allowlist.test.ts` 5 例 = 27 例。

**关键设计决策**：

1. 🔴 **只给建议不自动执行** —— `scan` 只写 `crm_customer_merge_candidate`，逻辑客户与映射表**一行都不写**。
   DB 级两道钉子：① 触发器拒绝 INSERT 一条 `status='CONFIRMED'` 的候选；
   ② 触发器拒绝直接 INSERT 映射（映射必须挂在一个**已人工确认**的候选上）
   ⇒ **绕过服务层直接写 SQL 同样被挡**。
2. 🔴 **给出置信度** —— 四级信号（PLATFORM_BUYER_ID 0.95 / EMAIL 0.85 / PHONE 0.75 / NAME_ADDRESS 0.60）
   加权 = 最强信号 + 0.05×旁证数，上限 0.99、下限 0.60；**numeric(5,4) + BigDecimal**（权重也是 BigDecimal 不是 double）。
   每条候选都带**逐信号证据明细**（命中了哪个信号、强度多少、比对值是什么 —— 只存脱敏形态）。
3. 🔴 **保留合并前映射可拆分** —— `split` 把映射 `ACTIVE → SPLIT` 并留痕（`split_at` + `split_reason`），
   **映射行不删**；**来源行 `crm_customer_profile` 从头到尾一个字都没改过**。
   DB 级三道钉子：触发器拒绝 `SPLIT → ACTIVE`（重新合并要新写一行）、拒绝 DELETE 映射行、拆分必须留痕；操作流水只追加。
4. 🔴 **白名单多放行了一个后端不存在的裸路径（本轮收紧）** —— `api-proxy.ts` 本车道那条 elements[] 的正则
   外层子路径组**带了 `?`** ⇒ 裸路径 `crm/customer-merge` 被放行；
   但后端 `@RequestMapping("/crm/customer-merge")` 下面第一个子路径就是 `capabilities` ——
   **没有裸路径端点**（逻辑客户列表的真身是 `customer-merge/logical`）。
   与该文件自己写下的原则矛盾（「放行后端不存在的映射比 404 更糟」）⇒ 外层组末尾去掉 `?`
   （`candidates` / `logical` 两个子组各自的 `?` 保留），裸路径写进「刻意不放行」清单 + 负向断言。
   trade-off：改的是共享累积型文件；判断为**安全** —— 首 token `customer-merge` 全仓唯一
   （与 `customer-tags` / `customer-contacts` / `customers` 第 9 个字符互斥），
   全仓只有本车道自己的守卫引用过这个前缀 ⇒ 收紧不会误伤别的车道。改完已重跑**全量 crm allowlist 回归**（135 例全绿）。

---

## 5.1.2.1 优先级与冲突检测（已交付模块的 L2 假红修正）

**这个功能做什么**：不是新功能 —— 修掉 batch-134 L2 的 3 条前端红，让「已交付形状」不再被兄弟车道断言成 404。

**技术落地**：

- 5.1.2.1 自身**已交付**（迁移 V4xx / 价目表冲突守门员，本批只补一条 fix commit）。
- 改动 3 个前端守卫测试文件：
  - `apps/web/src/app/api/catalog/price-list-allowlist.test.ts`
  - `apps/web/src/app/api/catalog/price-list-item-allowlist.test.ts`
  - `apps/web/src/app/api/trade/price-list-priority-allowlist.test.ts`
  把 `price-lists/lists/{uuid}/conflicts`、`price-lists/conflicts`、`price-lists/lists/{uuid}/items`
  这三条**由 5.1.2.1 / 5.1.1.2 自己放行**的形状，从「不该存在的形状必须 404」的负向样本
  移到「兄弟车道的白名单条目未被挤掉」的正向断言；**确实不存在**的 `price-lists/lists/{uuid}/items/batch`
  仍留负向样本。
- 验证：本车道 worktree 内 `pnpm vitest run` 三个文件 ⇒ **3 files / 16 tests 全绿**。

**关键设计决策**：

1. 🔴 **断言方向过期是比「代码写错」更隐蔽的失败** —— 白名单是**多车道共用的累积型文件**，
   后交付的车道放行新形状后，先前车道的负向样本表就成了「过期断言」：
   断言它 404 ⇒ 假红；**断言方向搞反时还会让「已交付的路由被静默删掉」这个真事故假绿**。
2. 正向断言（必须被放行）与负向断言（必须仍 404）**成对写进同一个测试**，
   这样下一次并入取错一侧时会被立刻抓住，而不是等到页面 404 才发现。

---

## 本轮调度取证与处置（**如实记录偏离**）

| 事项 | 实测 | 处置 |
| --- | --- | --- |
| 节流闸 | 距上批 590 分钟 ≥ 阈值 50 分钟 | 通过 |
| 温度 / load / PG 闸 | `pmset -g therm` 无降频项；load 1.75 / 10 核；带 `org.testcontainers.sessionId` 标签的 PG 容器 **0** 个 | GO |
| 档位 | `TOKEN_HORIZON=2026-11-08`，当前 2026-11-03 ⇒ **full**，LANES=4 / THROTTLE_MIN=50 | 按满档 |
| 调度闸 | round-162 于 00:04:13 取闸，owner PID 34252 已死，tick 与 3 个 worker 会话全部 `status=error` | **等闸龄超 TTL（4200s，01:14:13）后 `gate reclaim` 原子接管**，接管时 `inFlightLanes=0` / `holderAlive=false` / `buildProcesses=[]` |
| **偏离 1** | 3 条死车道的心跳未过期（TTL 90 分钟）⇒ `inFlightLanes=3` ⇒ 工具的「持闸者还活着」判据为假阳性 | 先用 `lane.mjs abandon` 把这 3 条**已证实死亡**的车道置为 abandoned（清零假在途数）⇒ 闸龄窗口一开即可干净接管；随后用 `lane.mjs land` 正常标 ready 并 `merge-ready` 并入。**未使用 `--force`，未删闸目录，未清闸** |
| **偏离 2** | round-162 的 3 个 worker 会话被网络错误掐断，**两个车道留有未提交的产出**（15.3.1.3 的 api-proxy 收紧 + 台账 + 会话日志；5.1.2.1 的 3 个守卫测试 + 台账 + 2 份会话日志） | 调度者**只做提交动作**，代码与断言全部为 worker 产出：先在各自 worktree 里跑完 L1 级验证（15.3.1.3：后端 `CustomerMerge*` 22 例 BUILD SUCCESS + 前端全量 crm allowlist 135 例全绿；5.1.2.1：3 files / 16 tests 全绿），再提交并并入 |
| 车道名额 | `plan --lanes 4` 只排出 **2 条** | 15.6.1.2 积分与权益的上游是 15.6.1.1（**本批未交付**）⇒ 按编排规则本轮不可派；**不手工绕过依赖规则**。派发：15.6.1.1（V440，crm）、18.4.1.2（V441，platform） |

**接管调度闸时序**：01:14:13 闸龄越过 TTL ⇒ `gate reclaim` 判定「闸龄 4200s > TTL 4200s 且持闸者已死
⇒ 原子接管成功」⇒ 01:14:23 三条车道 land + merge-ready（`80e19a8f` / `86959192` / `2547f2ba`）。

---

## L2 批次验证（本批唯一一次）

- **后端**：按**分块 verify** 跑 `./mvnw -pl services/identity,services/platform,services/crm -am verify`
  （相对 r161 已验证 commit `b6f6059657f0`：identity 与 platform 的树哈希变了 —— 18.4.1.1 / 18.5.2.1 两笔 merge；
  crm 因本批并入 15.3.1.2 / 15.3.1.3 而变；tenancy 由 `-am` 带上）。
  **未变更模块复用上次结论**（逐模块树哈希比对确认未变）：
  catalog ⇒ r160 chunk-A GREEN、channel / warehouse / trade / finance / reporting ⇒ r156 GREEN。
- **前端六步**：`pnpm typecheck` / `pnpm lint` / `pnpm test` / `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` /
  `node tools/module-progress/sync.mjs --check` / `node tools/module-progress/format-gate.mjs`。
- **静态预检**：本批新迁移 V435 / V437 的「已知互相破坏形状」扫描 **零命中**
  （无 `ADD COLUMN` 无 DEFAULT 缺失 / 无 `SET NOT NULL` / 无 `ADD CONSTRAINT … CHECK` 收窄）⇒ 跨车道 DDL 回归面 = 0。

### L2 实测结论：**GREEN**（全绿）

| 步骤 | 结果 |
| --- | --- |
| `pnpm typecheck` | **EXIT=0** |
| `pnpm lint` | **EXIT=0** |
| `pnpm test` | **EXIT=0** —— 741 files / **10387 tests** 全过 |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **EXIT=0** |
| `node tools/module-progress/sync.mjs --check` | **EXIT=0** |
| `node tools/module-progress/format-gate.mjs` | **EXIT=0** —— batch base `e0f815b1febb` / head `2547f2ba6eee` / mergeCount 8 / changedFiles 121 / **violations 空** |
| `./mvnw -pl services/identity,services/platform,services/crm -am verify` | **EXIT=0** —— BUILD SUCCESS，40:51 min |

后端分块用例（**1899 例，0 失败 0 错误 0 跳过**）：
identity **644** · crm **569** · platform **686**（tenancy 由 `-am` 带上）。
本批两个新模块逐类全绿：`CustomerDetailIntegrationTest` 10 · `CustomerDetailTenancyIntegrationTest` 4 ·
`CustomerMergeIntegrationTest` 18 · `CustomerMergeTenancyIntegrationTest` 4。

**存量红 0 条，本批新增失败 0 条**（失败集为空，无需比对 `.dev-agent/l2-baseline.txt`）。
**后端 verify 未复用上次结论** —— `HEAD:backend` 从 r161 的 `093fe3c9` 变为 `307f9845`
（identity / platform 树变了，crm 因本批并入而变）⇒ 本轮实跑 identity + platform + crm 三块；
未变更的 catalog / channel / warehouse / trade / finance / reporting **逐模块树哈希比对确认未变** ⇒ 复用上次 GREEN 结论。

L2 日志：`.dev-agent/l2/l2-r163.log` / `.dev-agent/l2/l2-r163-mvn-verify.log` / `.dev-agent/l2/l2-r163-summary.txt`。

---

## 本批之后仍未交付的 3 个模块

| 模块 | 名称 | 状态 |
| --- | --- | --- |
| 15.6.1.1 | 会员与等级 | **本轮已派发**（车道 `feat/15-6-1-1`，迁移 V440，crm 服务）。⚠️ worker 会话被 `net::ERR_CONNECTION_CLOSED` 掐断，**产出留在 worktree 未提交**：V440 迁移 + `MembershipController` / `MembershipModels` / `MembershipOrderFactsPort` / `MembershipService`（**已编译通过**）+ `MembershipIntegrationTest` / `MembershipTenancyIntegrationTest`（**租户隔离测试已 4/4 绿**）+ 前端 `customer-membership/` 页面与 model/workspace。**尚缺**：集成测试还剩 12 处失败待定位（上一任 worker 正在查）、前端 api-proxy 白名单条目未加、契约未重生成、台账与会话日志未写、未 commit / 未 land |
| 18.4.1.2 | 智能查询 | **本轮已派发**（车道 `feat/18-4-1-2`，迁移 V441，platform 服务）。⚠️ worker 会话同样被 `net::ERR_CONNECTION_CLOSED` 掐断，**产出留在 worktree 未提交**：platform `V441__platform_smart_query.sql` + identity `V441__platform_smart_query_permission.sql` + `SmartQueryController` / `SmartQueryModels` / `SmartQueryReportingPort` / `SmartQuerySemanticCatalog` / `SmartQueryService`（**已编译通过**）+ `SmartQueryIntegrationTest` / `SmartQueryTenancyIntegrationTest` / `SmartQueryTestStub`。**尚缺**：L1 闸结果未收（上一任 worker 正在跑）、前端未做完、契约未重生成、台账与会话日志未写、未 commit / 未 land |
| 15.6.1.2 | 积分与权益 | 上游 15.6.1.1 **未交付** ⇒ 本轮按编排规则不可派；下一轮随 15.6.1.1 落地后派发 |

> ⚠️ **两个 worker 会话都是被网络错误掐断的**（`net::ERR_CONNECTION_CLOSED`），
> 与 round-162 的崩溃**同一种环境故障**，不是代码问题。
> 处置：按协议「续做优先于重派」，**沿用原分支与原 worktree** 续做，
> 派单时写清「已有什么、还缺什么」，不新建分支重做一遍。
> 两条车道已重新派发续做任务，下一轮收割。

## 后续待办

1. 收割 15.6.1.1 / 18.4.1.2 两条车道（已 land 时并入；心跳过期时由 `plan` 的孤儿回收路径续做），
   并入后跑 L2（分块口径：crm + platform 两块）。
2. 15.6.1.1 落地后派发最后一个模块 15.6.1.2（迁移版本号待预分配）。
3. **全 465 个模块交付后**：跑 `lane.mjs merge-ready` → `sync.mjs` → `lane.mjs gc`，
   出最终收尾报告，并关闭 cron 任务 `96f8ea76-977c-4807-ae89-5d4412780fc9`
   （⚠️ 关闭时必须显式带上 schedule，否则会被静默重置成 `0 * * * *`）。
4. **环境侧待查**：连续两轮（round-162 与 round-163）出现 worker 会话被 `ERR_CONNECTION_CLOSED` 掐断，
   建议排查 MCode worker 会话的网络稳定性或用量上限（部分会话报 `已达到 Token Plan 用量上限`）。
