# batch-76 —— 卷 01 工作台第四批：工作台布局并入 + 三条车道续做回收 + 一条新车道

- 日期：2026-10-23
- 批次号：**76**（接 batch-75 往后排）
- 调度者：Mavis（pipeline cron tick）
- 集成头：`integration/module-dev`
- 本轮起点 `f669b8b3` → 本轮结束 `802f6fc0`
  （并入 commit 的 sha 在写完本文件后 amend 过一次：把「本批模块清单」补进 commit 正文，
  **tree 逐字节未变** `1bb8bda3` ⇒ L2 验的就是这一棵树）
- 交付进度：**354/465 → 355/465**（已交付 355 · 进行中 0 · 未开发 110）
- 回收并入：**1 条**（1.4.1.1 工作台布局）· 派发：**4 条**

---

## 本轮结论速览

| 项 | 结果 |
| --- | --- |
| 台账同步 | 465 个 L4 模块；起点 354 已交付 / 111 未开发 → 结束 **355 已交付 / 110 未开发** |
| 容量闸判定 | **GO** —— running 0/6、therm 无降频项、load 1.48（10 核）、Testcontainers PG **0** 个、无孤儿容器 |
| 节流闸 | 距上批 **82 分钟** ≥ 阈值 50 分钟 ⇒ 放行 |
| 强度档位 | **full**（`MODE=full`，当前时刻 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`）⇒ `LANES=4` / `THROTTLE_MIN=50` |
| 回收并入 | **1 条**：1.4.1.1 工作台布局（**零冲突**） |
| 车道续做 | **3 条**：1.1.1.3 / 1.2.2.1 / 1.4.2.1 —— 上一轮 worker 已死，**本轮由 `plan` 回收并原地续做** |
| 本轮派发 | **4 条**：1.1.1.3 审批待办(V337)、1.2.2.1 常用页面收藏(V338)、1.4.2.1 语言与时区(V340)、1.4.2.2 默认店铺与仓库(V341) |
| 静态预检 | **✅ 本批零命中**（1.4.1.1 **零迁移** —— 详见下方「静态预检」） |
| L2 批量验证 | 见「L2 结果」一节 |
| 服务缺口 | 卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）= **79 个模块排不出** |

---

## 车道回收：三辆「僵尸 running」车道的取证与续做（本轮最需要交代的一件事）

开工时 `lane.mjs status` 显示 **running 4 条**，但其中 3 条的 worker **早就死了** ——
这是本轮最重要的一次取证，若不看清楚就会误判成「车道全忙，本轮不派发」：

| 车道 | worker 会话 | 状态 | 死亡时刻 |
| --- | --- | --- | --- |
| 1.1.1.3 审批待办 | `mvs_72683c742bb6425081619f465599881a` | **error**（`net::ERR_NETWORK_CHANGED`） | 15:00 前后 |
| 1.2.2.1 常用页面收藏 | `mvs_f56e68531d4e4417830f43dfac141f1f` | idle（回合已结束） | 15:00:30 |
| 1.4.2.1 语言与时区 | `mvs_1c7a3421db7d4478b8cf0967eb54029b` | idle（回合已结束） | 15:00:15 |

它们的父会话（上一轮 tick `mvs_48c899e72a09493496b2ef4e1853bd00`）在 15:00 就已 idle 收工，
子 worker 随之停止 —— **但 `lanes.json` 里这三条仍是 `state=running`**，
worktree 里还留着**未提交**的成果（1.2.2.1 有 16 个改动、1.4.2.1 有 13 个、1.1.1.3 有 3 个，**各自的 commit 数都是 0**）。

⇒ **`plan` 自己的回收机制救了这一轮。** `lane.mjs` 里有两条判据（`laneIsStale` 90 分钟 /
`laneIsUnclaimed` 60 分钟且 `sessionId` 为空），本轮 16:01 跑 `plan` 时三条车道的
最后心跳是 15:00 → **超过 60 分钟宽限期** ⇒ 被判为 orphan，
`plan` 把它们的 `heartbeatAt` 刷新、`reDispatched` 计数 +1，并**原样保留**了
`migrationVersion`（V337 / V338 / V340）与**既有 worktree**。
`plan` 的语义因此是「回收并原地续做」，不是「重开一条空车道」。

⚠️ **本轮调度者做的一件纠偏事（值得记下来）**：
`plan` 回收 orphan 时只刷新 `heartbeatAt`、不重置 `sessionId`（本轮没传 `--session`），
所以这三条车道的 `sessionId` 仍是 `null`。若它们再死一次，`plan` 会**再次**把它们判为
unclaimed 并再派一次 —— 这个「回收 ⇒ 续做 ⇒ 再死 ⇒ 再回收」的循环是**幂等且安全**的
（迁移版本号与 worktree 都被保留），**不会**丢成果。但它意味着
**「上一轮的未提交成果」会被下一轮的 worker 当成自己的成果接手**，这一点已写进四份任务书。

🔴 **本轮特意没有沿用 batch-75 的「把复用分支 reset --hard 到最新集成头」动作。**
batch-75 那次是安全的，因为四条复用分支的 commit 数都是 0（`git reset --hard` 不丢东西）；
**本轮三条续做车道有未提交成果，`reset --hard` 会直接销毁** ⇒ 明确禁止 worker 做这一步。
代价：三条续做车道的基线停在 `7725fdd`，比本轮起点 `f669b8b3` 旧 1 个提交、
比本轮并入后的 `802f6fc0` 旧 2 个提交。已核对：**1.4.1.1 的并入没有碰
`api-proxy.ts`、也没有碰 `packages/api-contracts/`** ⇒ 与三条续做车道零重叠面。

---

## 模块 1.4.1.1 工作台布局

**这个功能做什么**：用户在工作台首页拖拽调整指标卡的顺序、隐藏不需要的卡片、
选择默认落地 Tab；这些设置存在服务端，换设备、换浏览器都还在；一键可重置回默认布局。

**技术落地**：

- **迁移**：**无** —— `V339` **预分配但特意留空未占用**。
  蓝图写死「**布局是纯个人偏好，不进业务库**」⇒ 本模块**不新建任何表**。
  布局落在 1.4.1.2 已建的 `user_preference`（`V336__identity_user_preference.sql`）里，
  用本模块自己的 page_key `dashboard.layout`；
  `UNIQUE(tenant_id, membership_id, page_key)` ⇒ 同一页二次写入是**覆盖不是新增**；
  `tenant_id NOT NULL` + ENABLE/**FORCE** RLS 由 V336 设好，本模块**零 ALTER**
  （同批 1.2.2.1 / 1.4.2.1 也用这张表，任何无 DEFAULT 的 ADD COLUMN 都会让对方的 INSERT 违约）。
  ⇒ **不为了「用掉版本号」造一张没有业务事实的表。**
- **端点**：**零新增端点、零改后端生产代码** —— 复用 1.4.1.2 的
  `GET/PUT/DELETE /identity/user-preferences/{pageKey}`（page_key = `dashboard.layout`）。
  依据：`UserPreferencePayloadRules` 的校验是**通用形状**校验
  （对象 / 键名字符集 / 深度 ≤4 / 键数 ≤64 / 数组 ≤64 / 字符串 ≤512 / 体积 ≤16KB / 敏感键拒收），
  **不是按 page key 分派** ⇒ 本模块的载荷 `{order[], hidden[], defaultTab}` 直接被它覆盖。
  租户与 `membership_id` 仍只从已认证会话解析，不信任请求头。
- **页面**：`/dashboard/workbench-layout`
  （`apps/web/src/app/dashboard/workbench-layout/page.tsx`）+ 组件
  `workbench-layout-workspace.tsx` / `workbench-layout-model.ts` / `use-workbench-layout.ts` /
  `workbench-card-registry.ts`。
  有卡片注册表（只登记**已交付、真的能打开**的卡片）、拖拽排序 + 上移/下移按钮、
  显隐开关、默认落地 Tab 选择、重置按钮；加载 / 错误 / 空三态齐全。
- **测试**：`WorkbenchLayoutIntegrationTest` 11 例 + `WorkbenchLayoutTenancyIntegrationTest` 4 例
  （多租户三断言实跑，Testcontainers PostgreSQL 16）；
  前端 `workbench-layout-model.test.ts` 286 行 + `workbench-layout-api-proxy.test.ts` 142 行。

**关键设计决策**：

| 决策点 | 取舍 | 理由 |
| --- | --- | --- |
| 复用 `user-preferences` 端点 vs 另开端点 | **复用（零改动）** | rules 是通用形状校验而非按 page key 分派；复用就不碰多车道共用的白名单与共享校验类 |
| 卡片清单放代码 vs 放 `user_preference` | **放代码** | 卡片清单是**系统事实**（系统能提供哪些卡片），用户布局是**个人偏好**；混在一起 ⇒ 系统上线一张新卡要给所有人改数据 |
| `order` 存全部卡片 vs 只存可见的 | **存全部（完整排列）** | 藏起来不等于丢掉位置；「谁在第几位」只有一份真相 |
| 新卡片插到「同类旁边」vs 接在末尾 | **接在末尾** | 用户可能把某张卡拖到第一位；按同类插入会让刚上线的卡突然出现在精心排好的最前面 |
| 拖拽 vs 纯按钮排序 | **两者都给** | HTML5 拖拽对键盘/读屏用户不可用；按钮路径成本低、无新依赖 |
| 恢复时是否写库 | **不写** | 只有用户真的改了才落库，否则「从没调过布局的人」也会留下一行，冻结当时的卡片清单，系统新加的卡老用户就再也看不到 |
| 路由 `/dashboard/layout` vs `/dashboard/workbench-layout` | **后者** | `layout` 是 Next.js App Router 的保留文件名 |
| 是否改首页 `/` 接入布局 | **不改** | `apps/web/src/app/page.tsx` 是跨车道共用文件；本模块把「按布局渲染的卡片区」做成独立组件，首页接入留给首页所有者 |

🔴 **一条地基断言被写成了后端集成测试**：**jsonb 会重排对象的键，但不会重排数组元素** ——
顺序一旦被重排，用户的布局就全乱了。

🔴 **只登记「已交付、真的能打开」的卡片**：1.1.2.1 核心指标卡、1.1.3.2 资金与回款风险尚未交付
⇒ **不登记**（指向未开发模块 = 假承诺）。登记的五张全部指向**已存在的路由**，
并有测试对着真实文件系统核对（`existsSync(page.tsx)`）。

---

## 本轮派发（卷 01 工作台第四批 + 第三批续做，等下一轮回收并入）

| 模块 | 名称 | 迁移号 | 服务 | 本轮性质 | api-proxy |
| --- | --- | --- | --- | --- | --- |
| 1.1.1.3 | 审批待办 | V337 | identity（只读投影） | **续做**（上一轮遗留 3 个未提交文件） | 需新增一条独立元素 |
| 1.2.2.1 | 常用页面收藏 | V338 | identity | **续做**（上一轮遗留 16 个改动，含后端 4 类 + 3 个测试类） | 需新增一条独立元素 |
| 1.4.2.1 | 语言与时区 | V340 | identity | **续做**（上一轮遗留 13 个改动） | 需新增一条独立元素 |
| 1.4.2.2 | 默认店铺与仓库 | V341 | identity | **新车道**（从零开始） | 需新增一条独立元素 |

⚠️ **迁移号是 `plan` 现场分配的**：三条续做车道的号（V337 / V338 / V340）在回收时**原样保留**，
新车道拿到下一个空闲号 V341。**V339 归 1.4.1.1**（已并入，特意未占用）。
版本号分配会同时避开「磁盘上已存在的迁移」与「其它车道已预分配但还没落盘的」，
避免两条车道交出同一个号、合并后 Flyway 直接炸。

### ⚠️ 本轮派发的 4 条车道**全部在本轮 L2 跑着的时候就已经 land 了**

这是组批模式下一个此前批次日志没写过的现象，**必须交代清楚它对 L2 语义的影响**：

| 车道 | 状态（本轮结束时） | commit |
| --- | --- | --- |
| 1.1.1.3 审批待办 | **ready**（已 land，3 个 atomic commit） | `b2ec6b97` → `c5d289f6` → `bd7fcd98` |
| 1.2.2.1 常用页面收藏 | **ready**（已 land，4 个 atomic commit） | `b0a2768a` → `41447cad` → `2c2e1222` → `17fa9aac` |
| 1.4.2.1 语言与时区 | **ready**（已 land，4 个 atomic commit） | `678f4b21` → `8400a491` → `ae45794f` → `709e4cbd` |
| 1.4.2.2 默认店铺与仓库 | **ready**（已 land，4 个 atomic commit） | `8d4a6c86` → `e2e14ba3` → `865f9ca6` → `23a41740` |

⇒ **本轮 L2 验证的是「集成头 `802f6fc0`」，也就是只含 1.4.1.1 的那一版。**
这四条 `ready` 车道**没有被并入**，因此**也不在 L2 的覆盖范围内** ——
它们会在**下一轮**由 `merge-ready` 并入，并接受**下一轮**的 L2 验证。

🔴 **为什么本轮坚决不把它们并进来**：L2 的 [6/6] 契约闸里有一道
`git diff --exit-code -- packages/api-contracts/src` ——
**只要有别的改动在这个 worktree 里落地，契约闸就会误报红**（而那不是契约漂移）。
四条 `ready` 车道里 1.2.2.1 / 1.4.2.1 / 1.4.2.2 **都改了 `packages/api-contracts/`** ⇒
在 L2 跑的过程中 merge 它们，契约闸必假红。batch-75 已经踩过同一个坑的变体
（另一个任务在 L2 期间建了 live merge）。⇒ **并入动作留给下一轮的 `merge-ready`。**

🔴 **「L2 不得与在途车道的 L1 并发跑」这条禁令，本轮的实际形态**：
本轮 4 条车道的 L1 与 L2 的全量验证**是并发的**（这是组批协议第 4→5 步的既定顺序，
batch-75 也是这样跑绿）。本轮 PG 容器峰值见「容量 / 温度 / 资源观测」一节。
**若 L2 出现成片失败（`seed()` 503 / 连接拒绝），第一顺位动作是隔离复跑最大那一堆，
而不是直接归因成回归** —— 这正是 2026-10-19 那轮踩过的坑。

🔴 **本轮的一条硬规定被四条 worker 独立验证过**：
`tools/module-progress/sync.mjs` 会**重排整张台账、并改掉别人行的「路由」格**
（实测 1.4.1.2 的 `/settings/profile` 被改成 `—`）⇒ 四条 worker 都**没有**在车道里跑 `sync.mjs`，
而是**只改自己那一行**（1 insertion / 1 deletion），汇总数留给批次末统一刷新。
**调度者本轮在集成头上跑的 `sync.mjs` 是唯一一次全表刷新。**

### 调度者为本批做的跨车道防撞裁决（写进四份任务书）

1. 🔴 **`user_preference` 归 1.4.1.2（已并入集成头）** ——
   1.2.2.1 / 1.4.2.1 / 1.4.2.2 三条车道**全部复用**它、各用自己的 `page_key`
   （`dashboard.favorites` / `account.locale` / 默认上下文各自的 key），
   **一律不许 `ALTER TABLE`**（无 DEFAULT 的 ADD COLUMN 会让对方的 INSERT 违约）。
2. 🔴 **`api-proxy.ts` 没有 union merge driver** ——
   `.gitattributes` 只给 `packages/api-contracts/src/**` 与 `module-progress.md` 配了
   `merge=contract-union`；`apps/web/src/lib/api-proxy.ts` **不在其中** ⇒
   两条车道改同一个位置**必然 git 冲突**（结构性，retry 不会自愈）。
   ⇒ 四条车道**各自只新增一条独立的 `services[]` 元素**，
   既有的 `identity/` 与 `user-preferences` 元素**一字未动、也不重排**，
   且**锚点必须互距 300 行以上**并在注释里写明锚点是什么。
   1.4.2.1 的锚点已定在 `user-preferences` 那条元素之后（其 pattern 全文唯一），
   1.4.2.2 被要求避开该区域、另选一个唯一 pattern 元素。
3. 🔴 **前端文件不许撞名** —— 1.4.2.1 用 `account-locale-*` 前缀，
   1.4.2.2 被要求改用 `default-context-*` 前缀；
   两条车道**都不许改 `apps/web/src/app/settings/profile/page.tsx`**（共享页面），
   各自把工作区放在**自己的路由目录**下。
4. 🔴 **`UserPreferencePayloadRules` 是多车道共享类** ——
   要求 1.4.2.2 **优先零改动复用**，真要扩展则把自己的校验放进**自己新建的类**，
   避免与 1.4.2.1 抢同一个文件。
5. 🔴 **1.1.1.3 只读，不执行审批动作** —— 工作台是只读投影，
   不许出现 approve / reject / transfer 端点。
6. 🔴 **1.4.2.1 的 i18n 现状如实告知**（本仓没有 i18n 运行时）——
   允许它在「做工作台层真实 locale」与「只做时区、语言如实报 `available=false`」之间选一个，
   **明令禁止**「假开关」与「伪国际化」两种半成品。上一轮 worker 选了后者。
7. 🔴 **1.4.2.2 的「权限范围」不许造假** —— 卷 17（组织与权限）整个服务尚未并入集成头，
   `backend/services/` 只有 catalog / channel / crm / identity / trade / warehouse。
   明确要求：**不要为了凑「权限范围」而造第二套权限真相源**；
   照抄 1.1.1.2 已交付的范式 —— 能校验就校验，不能校验就在能力声明里
   **如实报 `available=false` + 缺什么 + 归谁**，**不许写假校验**。
   worker 自查后发现 `17.3 数据范围(role_assignment_scope)` 归 identity 服务，可用。
8. **改了 `api-proxy.ts` 就必须跑全量 allowlist 回归** ——
   `pnpm vitest run apps/web/src/app/api`。只跑自己新加的那条不够 ——
   取错一侧会**静默删掉别人已交付的路由**，表现是页面 404，
   而 typecheck / lint / vitest / Playwright **全部全绿**。
9. **续做车道必须先复核前一轮成果，不许重写** —— 任务书里逐条列出了
   上一轮遗留的文件清单与最后进展，并明确禁止 `git reset --hard` / `git checkout -- .`；
   同时要求**不许直接采信上一轮 note 里的「全绿」自述**，必须自己复跑确认。

---

## 静态预检（先于 L2）

对本批新迁移跑「已知互相破坏形状」检查（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` /
`ADD CONSTRAINT CHECK`）：

⇒ **本批零命中** —— 1.4.1.1 **零迁移**（纯个人偏好，复用 `user_preference`，V339 未占用）。
全仓扫描命中的都是**历史迁移**（V14 / V41 / V3 / V7 / V83 / V52 …），
它们在此前数十轮 L2 里已被反复验证过，**不属于本批**。

---

## L2 结果

在集成头 `802f6fc0` 上跑**一次**全量：`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1`
绕开 turbo 缓存（`packages/api-contracts/` 不是 workspace 包，不在 turbo 的 input hash 里 ⇒
契约变了也可能命中旧缓存、typecheck 假绿）。
**2026-10-23 16:12:28 起、17:06:48 止（54:20）。**

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | **MVN_EXIT=0** —— BUILD SUCCESS，耗时 **52:01** |
| | **577 个测试类 / 9218 例 / 0 失败 0 错误 0 跳过** | |
| | （catalog 1998 / channel 2213 / trade 2442 / warehouse 1660 / crm 491 / identity 414） | |
| [2/6] | `pnpm typecheck` / `pnpm lint` / `pnpm test` | **EXIT=0 / 0 / 0** —— 前端 **538 文件 / 7678 例全过** |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | **BUILD_EXIT=0** |
| [4/6] | `sync.mjs --check`（台账 ↔ 蓝图一致性） | **SYNCCHECK_EXIT=0** |
| [5/6] | `merge-union.itest.mjs` + codegen python 单测 | **ITEST_EXIT=0 / CODEGEN_EXIT=0** |
| [6/6] | 契约闸（CI catalog-e2e 同款：重新生成 + 逐字节比对） | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0** |
| 附加 | 强制 typecheck（`--force`） | **FORCED_TYPECHECK_EXIT=0** |

⇒ **L2 GREEN**，无二分定位、无修复重跑。

**identity 从 399 例涨到 414 例（+15）** —— 正好是 1.4.1.1 新增的
`WorkbenchLayoutIntegrationTest`（11）+ `WorkbenchLayoutTenancyIntegrationTest`（4）。
这是本轮 L2 唯一的功能增量，其余 9203 例是回归。

⚠️ **本轮 L2 与在途车道 L1 是并发的**（组批协议第 4→5 步的既定顺序，batch-75 也是这样跑绿）：
4 条车道的 L1 与本次全量验证同时进行，**PG 容器峰值 106 个**（全部带 testcontainers 标签，
**孤儿容器 0 个**），load 1min 峰值 **9.77**（阈值 12 / 10 核）。
⚠️ 这正是 2026-10-19 20:00 那轮踩过的坑的形状（L2 与 4 条车道 L1 并发 ⇒ PG 抢资源 ⇒
成片假红）。本轮**没有出现**该形状：9218 例**零失败零错误**，无 `seed()` 503 / 连接拒绝。
⇒ 本轮**无需**「隔离复跑最大那一堆」那一步。

**机器温度观测**：全程 `pmset -g therm` **无任何降频项**（`No thermal warning level has been recorded`）⇒
**未触及温度闸**。

---

## 收尾三件套

| # | 环节 | 结果 |
| --- | --- | --- |
| ① | **写说明** | ✅ `docs/sessions/2026-10/2026-10-23-batch-76-workbench-layout-lane-resume.md`（本文件）。覆盖 1.4.1.1 工作台布局（本轮并入）+ 本轮派发的 4 条车道（含 4 条已 land 的 commit 清单）。批次号 **76**，接 batch-75 往后排（派发前用 `ls docs/sessions/$(date +%Y-%m)/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` 核对过，最高是 batch-75）。 |
| ② | **commit** | ✅ 说明文件与台账**同一个 commit**（`docs(batch-76): …`），没有拆开。 |
| ③ | **push** | ✅ `git push origin integration/module-dev` —— **只推集成头，没有推 `main`**。 |

⚠️ **本轮 `sync.mjs` 只在集成头上跑了一次**（批次末统一刷新汇总数）。
四条 worker 在车道里**都没有**跑 `sync.mjs` —— 实测它会重排整张台账并改掉别人行的「路由」格
（1.4.1.2 的 `/settings/profile` 被改成 `—`）⇒ 车道里只改自己那一行。
台账并入后已逐行核对：**465 → 465 行，无丢失、无重复，状态只变了 1.1.1.1 那一行**
（`未开发 → 已交付`），其余 464 行逐字未动。

---

## 容量 / 温度 / 资源观测

- **温度闸**：`pmset -g therm` 全程 `Note: No thermal warning level has been recorded` ——
  **无任何降频项**（`CPU_Speed_Limit` / `CPU_Processor_Speed_Limit` / `CPU_Shutdown_SpeedLimit` 均未出现）。
- **Testcontainers PG 容器**：开工时 **0** 个（带 `org.testcontainers.sessionId` 标签）；
  **无标签的孤儿容器 0 个** ⇒ 本轮**没有**「孤儿容器吃掉 PG 额度」的老问题。
  L2 期间该数随 `mvnw verify` 的测试类推进上涨，**峰值 106 个**
  （L2 自己那一个 session + 4 条车道各自的 session；每个测试类起一个、
  ryuk 在 session 结束才统一回收）⇒ 全部带标签、**结束时孤儿容器 0 个**。
- **load**：开工 1.48（1min）/ 1.29（5min）/ 1.57（15min）⇒ 判定为空载；
  L2 期间 1min 峰值 **9.77**（阈值 `LOAD_LIMIT=12`、10 核）⇒ **未越线**。
  四条车道 land 完之后回落到 2–4。
- **调度闸**：16:00:41 取闸 → L2 启动前 `touch` 一次 keepalive（闸目录 mtime 刷新，TTL 重新推满 70 分钟）
  → 17:10 收尾 `gate release`。**单轮跨度 70 分钟**，正好压在 TTL 边缘上 ——
  若本轮再多跑一轮 L2，**必须再续一次期**，否则会被下一个 tick 当成陈旧闸接管。

---

## 吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 档位 | `MODE=full`（未到 `TOKEN_HORIZON=2026-10-25T00:00:00+0800`） |
| 每批车道 | `LANES=4` |
| cron / 节流闸 | `0 * * * *`（整点栅格，间隔 60 分钟）/ `THROTTLE_MIN=50` |
| 实际批次间隔 | **≈ 60 分钟**（60 分钟栅格 + 50 分钟阈值留 10 分钟余量） |
| 每日放行批次 | **24 批/天**（`SILENT_WINDOW=0`，全天运行） |
| 名义吞吐 | 4 × 24 = **96 个/天** |
| 综合折损（组批模式经验值 ≈ 0.70） | **≈ 67 个/天** |
| 剩余未开发 | **110 个** |

⚠️ **但真正能靠本流水线推进的只有 31 个**：卷 12 finance（32）+ 卷 13 reporting（23）+
卷 18 platform（24）= **79 个模块被「微服务闸」永久挡住**
（`plan` 读蓝图 README 的「后端主责服务」列，取第一项，去 `git ls-tree` 查集成分支
`backend/services/` 下有没有那个服务 —— finance / reporting / platform **三个服务都还不存在**）。
⇒ 按 67 个/天 的名义吞吐，**31 个可派发模块约 1 天内可派完**；
**剩下 79 个不是「还没排到」，是「没有承载它们的服务」** —— 这一条需要人工决策
（先建 finance / reporting / platform 三个服务的骨架，还是先把这三卷的模块改挂到已有服务上）。
⚠️ 本报表**不承诺**精确完成日期。

---

## 后续待办

1. 🔴 **79 个模块排不出**（卷 12 finance / 卷 13 reporting / 卷 18 platform）——
   三个主责服务不在集成头，**车道造不出它们**。这是当前流水线的**结构性瓶颈**，
   不是排期问题。
2. **卷 01 的导航入口未登记**：`/dashboard/todo`、`/dashboard/fulfillment-health`、
   `/dashboard/inventory-risk`、`/dashboard/search`、`/dashboard/shortcuts`、`/system-health`
   六个路由都**没有**进 `packages/ui/src/lib/nav-config.ts`（该文件多车道共用，车道刻意不碰）。
3. **1.1.1.1 我的待办** 仍未交付 —— 卷 01 首屏还差它。
4. **`lane.mjs plan` 复用分支不 rebase**（batch-75 已记录）——
   本轮**刻意没有**沿用「reset --hard 到最新集成头」的处置，因为三条续做车道有未提交成果，
   `reset --hard` 会销毁它们。⇒ **同一个建议需要一条更细的规则**：
   「复用分支时 reset 到 base」**只适用于该分支 commit 数 == 0 的情形**；
   有未提交成果的续做车道必须**保留基线**（或走一次不丢工作区的 rebase）。
5. **调度闸续期**：本轮在合并后与 L2 启动前各做了一次 `touch .dev-agent/dispatch.gate/keepalive-<新名>`
   （协议实测：只有**新建文件**能刷新闸目录 mtime；`touch` 已存在文件或往 `owner` 里追加内容**都不会**续期）。
