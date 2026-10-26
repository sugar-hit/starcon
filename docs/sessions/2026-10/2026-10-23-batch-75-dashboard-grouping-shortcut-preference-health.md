# batch-75 —— 卷 01 工作台第二批四条车道并入（搜索结果分组 / 快捷入口配置 / 列表与表格偏好 / 同步与 API 健康）

- 日期：2026-10-23
- 批次号：75（接 batch-74 往后排）
- 调度者：Mavis（pipeline cron tick）
- 集成头：`integration/module-dev`
- 上一轮状态：350/465 已交付；本轮结束 **354/465**（并入 4 条车道即 +4，起点 350）
- 四个并入 commit：`378f3b3c`（1.2.1.2）→ `2f7a63f7`（1.2.2.2）→ `b99aad7c`（1.4.1.2）→ `7725fdd0`（1.5.1.1）
- 本轮起点 `aabae71e` → 本轮结束 `7725fdd0`

---

## 本轮结论速览

| 项 | 结果 |
| --- | --- |
| 台账同步 | 465 个 L4 模块；起点 350 已交付 / 115 未开发 → 结束 **354 已交付 / 111 未开发** |
| 回收并入 | **4 条**：1.2.1.2 搜索结果分组、1.2.2.2 快捷入口配置、1.4.1.2 列表与表格偏好、1.5.1.1 同步与 API 健康（**零冲突**） |
| 并入预演 | 派发前用 `git merge-tree --write-tree` 预演**完整顺序合并链**，四条全部 exit 0、**零冲突**；实际合并后的树哈希与预演**逐字节一致**（`b6f13456d6910eac2f16540847a0581e5a053d3e`） |
| 静态预检 | **✅ 零命中**（V335 / V336 都不 ALTER 任何既有表，详见下方） |
| L2 批量验证 | 见「L2 结果」一节 |
| 派发 | **4 条**：1.1.1.3 审批待办、1.2.2.1 常用页面收藏、1.4.1.1 工作台布局、1.4.2.1 语言与时区（卷 01 第三批） |
| 服务缺口 | 卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）= **79 个模块永久排不出** |
| 调度闸 | 14:33:14 接管（详见「调度闸与并发协调」一节） |

---

## 调度闸与并发协调（**本轮最需要如实交代的一件事**）

本轮开工时集成头处于**一次未完成的 merge 状态**：
`MERGE_HEAD = 6522c497`（正是 `origin/main`）、**58 个冲突文件**、闸的 owner 进程已死。
按 `SYNC-MAIN.md` 第 3 步「**冲突时 `git merge --abort`**……把冲突文件名列进汇报，不要自己解」，
调度者**abort 掉了这次 merge**（`ORIG_HEAD == HEAD == aabae71e`，该提交**已 push 到 origin**，零损失）。

随后查明：闸的 owner PID 只是 `gate acquire` 那个**短命进程**的 pid（SYNC-MAIN.md 明写
「**`owner` 里的 PID 不是闸存活证据**」），**真正的持闸者是 main→集成头同步任务**，
它在 13:23 取闸后一直在做 58 文件冲突的取证，**本轮与它并发约 1 小时**。

处置（按协议逐条走，没走捷径）：

| 时刻 | 动作 | 依据 |
| --- | --- | --- |
| 14:00–14:33 | **只做只读检查**（`sync.mjs` / `lane.mjs status` / 容量闸 / `git merge-tree` 预演），**不碰集成头** | 闸被合法持有 ⇒ 撞闸不得改动 |
| 14:02:56 | 发现闸持有者**重跑了一次 live merge**（为重建 stage 2/3 取证），已 abort | SYNC-MAIN.md 第 3 步 |
| 14:33:14 | 闸超 TTL（4200s）⇒ 用 `lane.mjs gate acquire` **按脚本自己的语义接管**（**不是手工 rm 闸目录，不是 force**） | `lane.mjs` 的既定 TTL 语义 |
| 14:35:29 | `merge-ready` 再次撞上对方刚建的 merge → 再次 abort → 重新并入 4 条车道 | 同上 |
| 14:38 | 启动本批**唯一一次**全量 L2 | 组批协议 |
| 14:39 | 向同步任务发出**协调通知 2**：告知已接管闸、请求其本轮**不要再改动这个 worktree**（L2 的契约闸是 `git diff --exit-code -- packages/api-contracts/src`，被外来 merge 污染会误报红），并给出 `git merge-tree` 这个**不碰工作区**的取证替代方案 | 避免两个任务互相破坏 |

⚠️ **本轮暴露的一个真实缺陷（值得后续修）**：`lane.mjs` 的 `gate acquire` 在 TTL 超时后**无条件接管**，
**不区分「持闸者已崩」与「持闸者只是慢」**。同步任务持闸 70 分钟以上且**没有做 keepalive**
（`.dev-agent/dispatch.gate/` 里从头到尾只有 `owner` 一个文件），
于是调度者按设计接管时，对方**并不知道自己已失去闸**，仍然继续改集成头。
⇒ **建议**：`lane.mjs` 增加 keepalive 续期入口（协议里已有 `touch .dev-agent/dispatch.gate/keepalive-<新名>` 这个动作，
但没有规定谁在什么时候做），或让 `gate acquire` 的接管**在 owner 仍活着时拒绝**。

---

## 模块 1.2.1.2 搜索结果分组

**这个功能做什么**：全局搜索（⌘K）搜出来的结果**按业务域分组**显示，每组带数量徽标，
可以「只看某一组」；没有权限的域**整组不返回**，只报一个「被抑制了几个域」的计数 ——
不给结果，也不假装「这个域没有命中」。

**技术落地**：

- **迁移**：**无**（V331 预分配但**不占用**）。零新增表、零新增端点、零新增服务 ——
  本模块是 1.2.1.1 全局搜索框的**视图层演进**，改的是既有 `GET /{service}/global-search` 的响应分组语义。
- **端点**：**不新增端点**，改既有 4 个服务的检索端点
  （catalog / channel / trade / warehouse），响应新增分组字段。
- **页面**：`/dashboard/search`（`apps/web/src/app/dashboard/search/page.tsx`）
  + `apps/web/src/components/dashboard/global-search-workspace.tsx` ——
  分组下拉 + 组数量徽标 + 「只看某一组」+ 无权限计数提示。
- **测试**：后端 `GlobalSearchGroupIntegrationTest` × 4 个服务
  （catalog 8 / channel 6 / trade 8 / warehouse 6 = **28 例**，实跑 Testcontainers PostgreSQL 16，连接池压到 1）；
  前端 `global-search-group-model.test.ts` **25 例**。

**关键设计决策**：

1. **不落库、不新增端点** —— 分组是「已有命中集」的一种排布，
   落一张分组表只会比它描述的检索事实**更早过期**。
2. 🔴 **「无权限结果计数提示」只报「域数」，不报命中条数** ——
   报条数就越权了（无权限的人不该知道那个域里有多少条命中）。
   页面明写「**其中命中的具体条数未返回**」。
3. **被抑制的子域要带归属业务域**（新增只读字段 `suppressedDomains`）——
   集成测试断言「子域集合相等」这个不变式：
   被抑制的域不能把它的子域（商品/订单/店铺…）单独放出来。
4. **「标注结果是否在当前数据范围内」只能由后端如实报** —— 前端算不出数据范围。
5. **顺手修掉了上游一处口径不一致**（trade 订单域）：
   部门范围的角色搜不到别人经手过的订单 —— 并补了一条集成测试钉住它。
6. **未改 `api-proxy.ts`**（1.2.1.1 已放行全部检索端点）⇒ 不需要全量 allowlist 回归。

---

## 模块 1.2.2.2 快捷入口配置

**这个功能做什么**：按**角色/团队**预置一批「岗位常用入口」模板，
新成员一进系统就自动拿到这份常用入口；个人可以覆盖模板里的某几条，但**覆盖项只能覆盖模板给过的入口**。

**技术落地**：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V335__identity_shortcut_template.sql`
  —— 建 `shortcut_entry_template`（岗位模板）+ `user_shortcut_override`（个人覆盖项）
  + 索引 + `ENABLE` + **`FORCE`** RLS + `GRANT`。
- **端点**：**10 个**（`ShortcutTemplateController.java`，jOOQ 显式 SQL）——
  `GET /capabilities` · `GET` 列表 · `GET /effective`（模板 + 个人覆盖的合并结果）·
  `GET /overrides` · `POST /overrides` · `DELETE /overrides/{id}` ·
  `GET /{id}` · `POST` · `PUT /{id}` · `DELETE /{id}`。
- **页面**：`/dashboard/shortcuts`（`apps/web/src/app/dashboard/shortcuts/page.tsx`）
  + `apps/web/src/components/dashboard/shortcut-template-{model,workspace}.tsx`。
- **测试**：后端 `ShortcutTemplateIntegrationTest` **29 例** + `ShortcutTemplateTenancyIntegrationTest` **5 例**
  = **34 例**（Testcontainers PG16）；前端 `shortcut-template-model.test.ts` **35 例**
  + `shortcut-template-allowlist.test.ts` **6 例**。

**关键设计决策**：

1. **表 owner 在 FORCE RLS 下也受策略约束** —— 否则迁移脚本用 owner 身份一连就绕过了隔离。
2. **「默认值必须在权限范围内」落成服务层的权限闸** ——
   入口目标页面 `pageCode` 对应的页面权限拿不到 ⇒ 该入口**不生效**。
3. 🔴 **覆盖项只能覆盖模板给的入口，不能凭空新增** ——
   「凭空新增」就是 1.2.2.1 常用页面收藏，那是另一个模块，**两者不许互相实现**。
4. **api-proxy 末尾刻意不带 `?`** —— 本模块的裸路径 `shortcut-templates` 是**真的有端点的**，
   不是靠一个可选组顺带放行；形态与「本来没有裸集合端点却顺手用 `?` 放行」是两回事。
5. **测试夹具的坑**：测试底座只建一个租户，**所有用例共用它**，
   而 TENANT 范围的模板对全租户可见 ⇒ 断言改成 409，而不是去改全局映射。

---

## 模块 1.4.1.2 列表与表格偏好

**这个功能做什么**：记住每个列表页的列显示、排序、每页条数、筛选条件 ——
下次进那个页面，视图状态原样恢复；下拉里改了就存，走开再回来还在。

**技术落地**：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V336__identity_user_preference.sql`
  —— 建 `user_preference(id, tenant_id, membership_id, page_key, preference jsonb, created_at, updated_at)`，
  `UNIQUE(tenant_id, membership_id, page_key)` + 复合外键指向 `membership(tenant_id, id)`
  + 索引 + `ENABLE` + **`FORCE`** RLS + `GRANT`。
  **本仓第一张个人偏好表**（迁移目录里 `grep user_preference` 此前零命中）。
- **端点**：**5 个**（`UserPreferenceController.java`）——
  `GET /{pageKey}` · `PUT /{pageKey}` · `DELETE /{pageKey}` · `GET`（当前用户全部偏好）· `DELETE`（清空）。
  租户与 `membership_id` **只从已认证会话解析**，请求里没有也不接受租户/用户 id。
- **页面**：**可复用 hook** `apps/web/src/lib/use-list-preferences.ts` +
  模型 `apps/web/src/lib/list-preferences-model.ts` +
  偏好条 `apps/web/src/components/common/list-preference-bar.tsx`，
  并**真实接进 5 个已交付列表工作区**
  （catalog SKU/SPU 列表 · orders 待发货 · orders 发货记录 · dashboard 库存风险 · warehouse 出库时效）。
- **测试**：后端 `UserPreferenceIntegrationTest` **19 例** + `UserPreferenceTenancyIntegrationTest` **4 例**
  = **23 例**（Testcontainers PG16，含租户隔离三断言）；前端 `list-preferences-model.test.ts` **21 例**
  + `list-preferences-api-proxy.test.ts` **9 例**。

**关键设计决策**：

1. 🔴 **「一页一行 + JSONB 载荷」，不做「每种偏好一列」的大宽表** ——
   蓝图硬约束「**偏好按页面 key 存储，新增字段不影响旧偏好**」直接否掉了宽表：
   宽表每加一个字段就要 ALTER，且老行的默认值语义会被迫重写，
   老用户下次进页面时被新字段的默认值**静默覆盖**。
   JSONB 下老行缺新键是**正常**的，由前端对缺失键套默认值 ⇒ **新增字段零 ALTER、零数据迁移**。
2. **租户内「这个人」= `membership.id`** —— `app_user` 是跨租户全局账号（**没有 tenant_id**），
   别拿 `app_user.id` 当租户内用户键。
3. **恢复时不写库** —— 刚进页面就把默认值存回去，会让「用户从没调过的页面」也留下一行，
   把「用户配置过」和「系统有默认值」混成一件事。
4. **排序键是页面自己定义的不透明 token**（如 `PACKED_AT_DESC`），方向编在 token 里 ——
   前端不解释业务语义，也不按列名猜。
5. 🔴 **自由文本筛选一律不落库**（关键字 / 姓名 / 备注可能含 PII）：
   只有页面**显式声明**的枚举与编号才进白名单。
6. **每页条数只给真正分页的页面** —— 库存风险是一次给全量投影（改 size 会截断数据）、
   出库时效的每页条数由后端固定 20 ⇒ 这两页**不显示**「每页条数」，
   而不是给一个改了没用的控件。

---

## 模块 1.5.1.1 同步与 API 健康

**这个功能做什么**：一屏回答「现在能不能正常同步」——
各平台连接状态、最近同步时间、失败计数、限流命中、队列积压，
按店铺/连接展示红黄绿，每个颜色都能追到一条规则；需要处理时给出跳转入口。

**技术落地**：

- **迁移**：**无**（V338 预分配但**不占用**）。交付为 channel 服务的**只读投影层**，零迁移、零写端点。
- **端点**：**6 个 GET**（`SyncHealthController.java`，`@RequestMapping("/channel/sync-health")`）——
  `GET /capabilities` · `GET /options` · `GET /summary` · `GET /connections` ·
  `GET /connections/{id}` · `GET /pending-items`。
- **页面**：`/system-health`（`apps/web/src/app/system-health/page.tsx`）
  + `apps/web/src/components/system-health/sync-health-{model,workspace}.tsx`。
- **测试**：后端 `SyncHealthIntegrationTest` **26 例** + `SyncHealthRulesTest` **23 例**
  + `SyncHealthTenancyIntegrationTest` **3 例** = **52 例**（Testcontainers PG16）；
  前端 `sync-health-model.test.ts` **18 例** + `sync-health-allowlist.test.ts` **10 例**。

**关键设计决策**：

1. **红黄绿来自一张显式的「枚举 → 严重度」映射表**（`SyncHealthRules.java`）——
   每条规则把「命中条件 / 严重度 / 人话理由 / 事实来源表列」逐条公布，
   页面上每个颜色都能追到一条规则，不是不可解释的综合分。
2. **「限流」判黄不判红** —— 限流是平台侧的正常保护，不等于数据错了。
3. 🔴 **「读不到」与「没有」分三处处理**：
   - **上游读不到** ⇒ 整个端点 **503**，绝不折成空集合冒充「一切正常」；
   - 读得到但**没有这条记录** ⇒ 报「没有」；
   - 有记录但**该字段无写入方** ⇒ 如实报「无数据」，不填 0。
4. 🔴 **`channel_connection.last_sync_at` 本仓没有任何写入方** ——
   这一列的事实不存在 ⇒ 该项如实报「**无数据源**」，不拿 `now()` 冒充。
5. 🔴 **下游跳转落点按真实路由校正** ——
   照抄会落到兜底页「这里没有页面」；
   失败队列（2.4.2.1）**本仓无独立前端页面**（台账路由为 —）
   ⇒ 指向「渠道与店铺 → 同步运行」Tab，而不是编一条不存在的链接。
6. **积压逐张表报，合计 = 逐行相加** —— 不给一个来源不明的总数。
7. **api-proxy 白名单：路径级，方法不级**。

---

## 静态预检（互相破坏形状扫描）

扫描命令：

```bash
grep -rn 'ADD COLUMN' backend/services/*/src/main/resources/db/migration/ | grep -v 'DEFAULT'
grep -rn 'SET NOT NULL' backend/services/*/src/main/resources/db/migration/
grep -rn 'ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/
```

结果：**✅ 零命中**。本批只有两条新迁移，且**都只新建表、不碰任何既有表**：

| 迁移 | 新建表 | ALTER 既有表？ | ADD COLUMN | SET NOT NULL | ADD CONSTRAINT…CHECK |
| --- | --- | --- | --- | --- | --- |
| `V335__identity_shortcut_template.sql` | `shortcut_entry_template` + `user_shortcut_override` | **否** | 无 | 无 | 无（CHECK 全部**内联在 CREATE TABLE 里**） |
| `V336__identity_user_preference.sql` | `user_preference` | **否** | 无 | 无 | 无 |

⇒ 形状①（加了列没给默认值 → 别人的老 INSERT 必违约）**不成立**：
两条迁移**一行 ALTER 都没有**。
⇒ 形状②（改成必填但没有 BEFORE INSERT 触发器兜底）**不成立**：
新表的 `NOT NULL` 只约束本批自己写的 INSERT。
⇒ 形状③（新增 CHECK 约束没放宽旧值域）**不成立**：
两条迁移的 CHECK 全部**内联在建表语句里**（`shortcut_entry_template_code_chk` /
`_name_chk` / `_scope_type_chk` 等），**没有一条是 ALTER 既有表加上去的**。

补充核查（V335）：入口清单的形状由 `shortcut_entries_shape_ok(jsonb)` /
`shortcut_entry_shape_ok(jsonb)` 两个 PL/pgSQL 函数在**数据库层**钉死，
且 `REVOKE EXECUTE … FROM PUBLIC` + `GRANT EXECUTE … TO identity_app` ——
校验函数没有落到 PUBLIC 手上。

两条迁移的 RLS 写法一致且到位：
`ALTER TABLE … ENABLE ROW LEVEL SECURITY` + **`FORCE ROW LEVEL SECURITY`**
+ `CREATE POLICY isolation`（只认 `app.current_tenant`）+ `GRANT … TO identity_app`。
**FORCE 是必须的** —— 表 owner 在 FORCE 下也受策略约束，否则迁移脚本用 owner 身份一连就绕过了隔离。

---

## L2 结果

在集成头上跑**一次**全量：`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1` 绕开 turbo 缓存 ——
本批契约产物有变更（`packages/api-contracts/` **不是** workspace 包，
不在 turbo 的 input hash 里 ⇒ 契约变了也可能命中旧缓存、typecheck 假绿）。
2026-10-23 **14:38:32** 起、**15:34:12** 止（**55:40**）。

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | **MVN_EXIT=0** —— BUILD SUCCESS，耗时 **53:00** |
| | 575 个测试类 / **9203 例** / 0 失败 0 错误 0 跳过 | |
| | （catalog 1998 / channel 2213 / trade 2442 / warehouse 1660 / crm 491 / identity 399） | |
| [2/6] | `pnpm typecheck` / `pnpm lint` / `pnpm test` | **EXIT=0 / 0 / 0** —— 前端 **536 文件 / 7636 例全过** |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | **BUILD_EXIT=0** |
| [4/6] | `sync.mjs --check`（台账 ↔ 蓝图一致性） | **SYNCCHECK_EXIT=0** |
| [5/6] | `merge-union.itest.mjs` + codegen python 单测 | **ITEST_EXIT=0 / CODEGEN_EXIT=0** |
| [6/6] | 契约闸（CI catalog-e2e 同款：重新生成 + 逐字节比对） | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0** |
| 附加 | 强制 typecheck（`--force`） | **FORCED_TYPECHECK_EXIT=0** |

⇒ **L2 GREEN**，无二分定位、无修复重跑。

⚠️ **本轮 L2 期间的一次真实风险（已化解，值得记下来）**：
L2 的 [6/6] 契约闸里有一道 `git diff --exit-code -- packages/api-contracts/src`
—— **只要有别的任务在这个 worktree 里留下一个未提交的 merge，契约闸就会误报红**
（而那不是契约漂移，是别人的 merge 改了工作区）。
本轮 main→集成头同步任务恰好在 14:35 前后又建了一次 live merge（为重建 stage 2/3 取证），
**调度者在 `merge-ready` 前 abort 掉了它**，并在 14:39 发出协调通知请对方本轮不要再动这个 worktree。
⇒ 后续 55 分钟里 L2 全程无外来干扰（`MERGE_HEAD` 始终为 none，`git status` 始终只有台账一个文件）。

**机器温度观测**：全程 `pmset -g therm` **无任何降频项**（`No thermal warning level has been recorded`）·
load 峰值 **6.44**（`LOAD_LIMIT=12`）· Testcontainers PG 容器峰值 **55** 个
（全部属 L2 自己的那一个 session，每个测试类起一个、ryuk 在 session 结束才统一回收；
**无标签的孤儿容器为 0**）⇒ 发热峰值与上一轮同量级，**未触及温度闸**。

---

## 本轮派发（卷 01 第三批，等下一轮回收并入）

| 模块 | 名称 | 迁移号 | 服务 | api-proxy 锚点 |
| --- | --- | --- | --- | --- |
| 1.1.1.3 | 审批待办 | V337 | identity（只读投影） | **无需改动**（`my-approvals` 已放行） |
| 1.2.2.1 | 常用页面收藏 | V338 | identity | `shortcut-templates` 那条元素**之后** |
| 1.4.1.1 | 工作台布局 | V339 | identity | **无需改动**（`user-preferences/{slug}` 已放行） |
| 1.4.2.1 | 语言与时区 | V340 | identity | **无需改动**（`user-preferences/{slug}` 已放行） |

⚠️ **迁移号是派发那一刻 `plan` 现场分配的**（V337–V340），
不是上一轮 dry-run 里看到的 V339–V342 —— dry-run 跑的时候 V335/V336 还没并入，
版本号计数器把它们占着。**任务书已按实际分配值改正。**

**调度者为本批做的跨车道防撞裁决**（写进四份任务书，四条车道互不依赖）：

1. 🔴 **`user_preference` 归 1.4.1.2（已并入本批集成头）** ——
   1.2.2.1 / 1.4.1.1 / 1.4.2.1 三条车道**全部复用**它、各用自己的 `page_key`
   （`dashboard.favorites` / `dashboard.layout` / `account.locale`），
   **一律不许 `ALTER TABLE`**（无 DEFAULT 的 ADD COLUMN 会让对方的 INSERT 违约）。
2. 🔴 **「系统事实」与「个人偏好」严格分开** ——
   1.4.1.1 的「系统可选卡片清单」是**系统事实**（代码里注册了哪些卡片），
   **不许**塞进 `user_preference`（那是个人数据，系统事实变一次就要给所有人改数据）。
3. 🔴 **1.1.1.3 只读，不执行审批动作** ——
   上游 `ApprovalInboxController`（17.4.2.1）的类注释里逐字写着
   「**不在工作台重复实现审批**」⇒ 本车道不许出现 approve / reject / transfer 端点。
4. 🔴 **1.4.2.1 的 i18n 现状已如实告知**（本仓**没有** i18n 运行时，全站文案硬编码中文），
   要求 worker **显式在 A（做工作台层真实 locale）/ B（只做时区，语言报 unavailable）之间选一个**，
   并**明令禁止**「假开关」与「伪国际化」两种半成品。
5. **api-proxy 锚点互斥**：本批**只有 1.2.2.1 需要改** `api-proxy.ts`，
   锚点固定在 `shortcut-templates` 那条元素之后（该元素的注释里正好写着
   「🔴 本模块**不写**收藏相关的形状（1.2.2.1 常用页面收藏未交付）」—— 你的新元素正好把它兑现掉）；
   另外三条车道**零 api-proxy 改动**（`my-approvals` 与 `user-preferences/{slug}` 已被放行）。
   ⇒ 本批只有 1 条车道需要跑全量 allowlist 回归，并入冲突面比上一批小一个量级。
6. **改了 api-proxy.ts 就必须跑全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api`）。

### 调度者本轮修掉的一个编排缺陷（`lane.mjs plan` 复用分支不 rebase）

`lane.mjs plan` 里是 `if (!branchExists) git branch branch base` ——
**复用已存在的分支时不会把它 rebase 到当前集成头**。
上一轮（batch-74）撞上「上一轮崩了留的分支」就拿到了陈旧基线。

本轮 4 条车道**全部是复用分支**（batch-74 崩过一轮留下的），
派发前实测：四条分支都停在 `de6dbb98`，**落后集成头 22 个提交**。
⇒ 调度者在 `plan` 之后**逐条核对「自己的提交数 = 0」**，再把每个 worktree
`git reset --hard` 到当前集成头 `7725fdd0`，并重跑 `pnpm install --prefer-offline`。
四条分支的 ahead 均为 0 ⇒ **无成果丢失**；重置后 behind 全部归 0。

⇒ **建议**（与 batch-74 的同一条建议合并）：给 `lane.mjs plan` 补一条
「复用分支时 reset 到 base」的逻辑，并加一条 itest。否则每轮都会拿到陈旧基线，
worker 会基于**22 个提交之前**的代码开工 —— 那不是「基线旧一点」，是**依赖的表可能还不存在**。

---

## 后续待办

1. **79 个模块永久排不出**：卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）
   —— 这三个服务不在集成头，`plan` 的微服务闸会挡住它们的全部模块。
2. **卷 01 的导航入口未登记**：`/dashboard/todo`、`/dashboard/fulfillment-health`、
   `/dashboard/inventory-risk`、`/dashboard/search`、`/dashboard/shortcuts`、`/system-health`
   六个路由都**没有**进 `packages/ui/src/lib/nav-config.ts`（该文件多车道共用，本批四条车道都刻意不碰）。
3. **1.1.1.1 我的待办** 仍未交付 —— 卷 01 首屏还差它。
4. **`lane.mjs plan` 的分支复用不 rebase** ——
   `if (!branchExists) git branch branch base` ⇒ 复用的分支基线停在旧提交。
   本轮派发前调度者**手工**把四条复用分支的 worktree `reset --hard` 到最新集成头；
   建议给 `lane.mjs plan` 补一条「复用分支时 reset 到 base」的逻辑，并加一条 itest。
