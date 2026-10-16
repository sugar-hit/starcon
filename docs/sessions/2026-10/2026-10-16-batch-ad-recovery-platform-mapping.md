# 2026-10-16 批次：配额事故恢复 + 平台类目映射 / 广告类型差异

- **调度轮次**：17:30（节流闸放行，距上批 179 分钟 ≥ 150）
- **集成头**：`1af79f98` → `4ac07c74`
- **本批交付**：2 个模块（4.2.3.1 平台类目映射、14.1.2.2 广告类型差异）

---

## 本轮的真正主线：14:48 的配额事故

这不是一次普通的批次。开工取证时发现 **4 条车道全部停在 `state=running`**，
心跳 163–175 分钟未续（远超 90 分钟失效线）。

三处信号（台账心跳、worktree mtime、进程表）互相矛盾且**都不记录"为什么停"**：

| 信号 | 显示 | 真相 |
| --- | --- | --- |
| `lanes.json` heartbeatAt | 14:35–14:47 | 最后一次心跳，之后就死了 |
| worktree 源文件 mtime | 14:40–14:48 | 同一刻，之后无写入 |
| `ps` 中 java/maven 进程 | **0** | 根本没在跑 |
| `mavis session list` | **`error` 42212** | 唯一权威证据 |

`errorCode 42212`「已达到 Token Plan 用量上限」——**外部配额在 14:48 把整批 worker 连同父调度会话一起打死**，
不是「跑得慢」。

**判据（值得复用）**：任何「N 分钟无写入就算失联」的心跳机制，都只能证明**上一次活跃距今多久**，
证明不了**现在还活着**。外部配额、鉴权过期、容器驱逐都会在**不写心跳**的前提下杀死会话。
⇒ **判活必须读会话/进程的真实状态，心跳只用来判断「多久没动静」。**

**第二条**：死掉的车道成果是**未提交**的，只活在 worktree 工作区里。
4 条分支 tip 全部等于集成头 `1af79f98`（`ahead=behind=0`），一个提交都没有。
V145–V148 四份迁移与 8 个 untracked 条目/车道全在工作区里。
`lane.mjs gc` 只回收 `merged`/`abandoned` 车道，所以它们不会被自动清掉——
**成果暂时安全，但任何 `git worktree remove` 都会永久丢失。**

---

## 模块 4.2.3.1 平台类目映射

**这个功能做什么**：运营可以把「内部商品分类」映射到「各平台各站点的类目」，
并维护「平台的材质对应内部哪个属性」这类必填属性规则；
刊登表单据此知道每个平台要问哪些必填项。

**技术落地**：

- 迁移：`V145__platform_category_mapping.sql`（catalog）— **纯新建两张表、零 ALTER 任何既有表**；
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + 业务账号零权限 GRANT；8 个索引；复合外键带 `tenant_id`。
- 端点：`/catalog/category-mappings/**` 共 11 条（jOOQ 显式 SQL，真分页 + 真筛选）。
- 页面：`/listings/category-mappings` — 映射台账 / 属性规则维护 / 必填清单解析面板。
- 测试：`PlatformCategoryMappingIntegrationTest` 14 例 + `RulesTest` 5 例 + 前端 15 例。

**关键设计决策**：

- **蓝图加粗句「同一个内部类目在不同平台映射到不同平台类目，映射必须带平台与站点维度」
  做成真正的多对多**，而不是一个单值外键——并有专门测试证明该性质。
- **时间戳全部由 SQL `now()` 写入，Java 侧零 `OffsetDateTime` 绑定。**
  这是**结构性**地绕开 jOOQ 3.19 把 `OffsetDateTime` 绑成 varchar（PG 直接拒）
  与 `Timestamp.valueOf(LocalDateTime…)` 静默偏 −28800s 两个坑，而不是逐处小心。
- 修掉接手时的一处死代码：`currentTenantId()` 全类无调用，且其上方注释误称
  「tenant_id 由 currentTenantId() 走 RLS」（实际该方法显式收 `tenantId` 参数）。
  已删除并改正注释，说明改为：隔离由 RLS `WITH CHECK` 保证，显式带 tenant_id 只是为走 partial index。
- `api-proxy.ts` 的插入**逐字符证明为纯增量**（5852→6009，delta 157 = 片段 156 + 一个 `|`）。

---

## 模块 14.1.2.2 广告类型差异

**这个功能做什么**：不同平台的广告类型（自动广告 / SB / SD / 品牌广告 / DSP / 大促）
字段各不相同。本模块把它们归一成内部模型让运营横向比较，
同时**完整保留平台原始类型标记**，不让归一化吃掉信息。

**技术落地**：

- 迁移：`V146__ad_type_normalization.sql`（channel）— **纯新建三表、零 ALTER 既有表**；
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT；4 触发器；8 个索引。
- 端点：`/channel/ad-type-diffs/**` 共 9 个。
- 页面：`/ads/ad-type-diff` — 保真概览 → 档案登记 → 特有字段定义 → 归一数据登记 → 明细。
- 测试：`AdTypeDiffIntegrationTest` 21 例 + 前端 28 + 5 例。

**关键设计决策**：

- **「必须保留原始类型标记，不能丢信息」做成四条并存的存储**（不是一句注释）：
  ① `platform_ad_type` 原样存、不折叠大小写与别名；
  ② `internal_ad_type` **与它同表并存**，归一只多一列；
  ③ `raw_payload` 原文照抄，归一错了能回原文重来；
  ④ `normalized_field_keys` / `raw_only_field_keys` 由 **DB 触发器从原文现场派生**——
  `normalized_field_keys ⊄ raw_payload` 的键**直接拒**（否则「声称归一了一个不存在的字段」
  会把保真率刷成 100%），`raw_only_field_keys` 由触发器**覆盖写入**（服务层谎报保真状态在这一刻被改写回来）。
- **「没有可比口径」与「没有这种广告」是两件事**：内部类型枚举显式含 `UNMAPPED`，
  带它的行不参与任何可比聚合，但在可比视图的 `unmappedTypes` 里**带完整原始标记单列**
  （从表里删掉会让人以为「它不存在」）。
- **归一口径的真相在上游 2.1.2.1，不在请求里**：`GET /resolve` 走两层解析；
  登记档案时服务端拿解析结果与声明值逐项比对，不符即拒；
  触发器再核一层——声称 `PLATFORM_DEFAULT` / `TENANT_OVERRIDE` 却在码表里查无此行，**写入被 DB 拒**
  （绕服务层直接写 SQL 同样被拒）。
- `ctr` / `cpc` / `cvr` / `acos` 是 `GENERATED ALWAYS AS … STORED` 生成列，
  **分母为 0 给 NULL（不可比）而不是 0**（「可比但真的是零」与「压根算不出来」在界面上必须分开）。
- 接手核实发现并修正：**台账写「7 个索引」，迁移实际建了 8 个**。

---

## L2 批量验证结果（全绿）

本批只跑**一次**全量验证，跑在集成头 `4ac07c74` 上：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `./mvnw verify` | **4149 例全绿**，BUILD SUCCESS<br>identity 236 / catalog 1502 / channel 295 / warehouse 1459 / trade 639 / crm 18 |
| 类型 | `pnpm typecheck --force` | **exit 0** |
| Lint | `pnpm lint` | **exit 0** |
| 前端 | `pnpm test` | **188 文件 / 3468 例全绿** |
| 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **✓ Compiled successfully，170 条路由** |
| 台账 | `sync.mjs --check` | **✓ 台账与蓝图一致** |

两个新模块的测试**确实在集成头上跑了**：
`PlatformCategoryMappingIntegrationTest` 14 ✓、`PlatformCategoryMappingRulesTest` 5 ✓、
`AdTypeDiffIntegrationTest` 21 ✓。
Flyway 日志实证迁移落库：catalog `now at version v145`、channel `now at version v146`。

### 静态预检：零跨车道 DDL 回归面

- `ADD COLUMN` 缺 DEFAULT：**无**
- `SET NOT NULL`：**无**
- 新增 CHECK 约束：**无**
- 两条迁移**零 ALTER 任何既有表**（唯一的两处 `ALTER TABLE` 在 `format()` 里，
  对**本次迁移自己创建的表**开 RLS）

### ⚠️ 一个必须记住的坑：`pnpm typecheck` 的 turbo 缓存会撒谎

worker 发现 `pnpm typecheck` 走的是 **FULL TURBO 缓存命中**，
而缓存把 `ui` / `config-tailwind` 解析到了**其他车道的 worktree 路径**上——
"绿"反映的是别处代码的状态。它改用 `tsc --noEmit` 直跑才拿到真实结果。

调度者的 L2 因此改用 `pnpm typecheck --force` **绕开缓存**取真实结果。
⇒ **判据：凡是有「声明 vs 生效」两份东西的系统，取证要取生效的那一份。**
只读「跑过了」这个事实，会拿到一个恰好掩盖了别人错误的假绿。

---

## 派发策略：为什么只派 2 条而不是 4 条

`.dev-agent/PIPELINE.local.md` 里 **`LANES=4` 与 `RUNNING_QUOTA=2` 自相矛盾**：

- `LANES=4` 表达的是「每批排 4 条」
- `RUNNING_QUOTA=2` 表达的是「同时最多跑 2 条」

两种读法都说得通。本轮取了**更严格的读法**（并发 2）——它在两种解读下都成立。
理由不止合规：14:48 正是 **4 并行**把配额打爆的。

**这属于用户的决定，调度者不自行改参数，只报差异。**

未派发的两条（`9.4.2.1 回传状态 V147`、`8.5.3.1 出库时效 V148`）成果原样保留在 worktree。
它们的心跳已冻结，约 60 分钟后会被 `laneIsUnclaimed` 自动识别为孤儿，下轮可安全接手。
**期间不得清理 worktree。**

---

## 另一个教训：探针报错时先怀疑探针

并集完成后 `merge-union` 提示「走了行级兜底并集，请人工确认这个文件改对了」。
我写了个正则探针去验 `api-proxy.ts`，结果报告 `category-mappings` 全部 deny、
裸路径被放行——看着像并集彻底坏了。

真因是**探针自己**：它只解析出 18 个前缀里的 1 个 catalog 条目，
而且把 UUID 形状当字面量字符串去测正则。

改跑**仓库自带的 39 个白名单测试文件**（真正维护着这套规则的权威定义）：
**38 文件 / 227 例全绿**，并集正确。

⇒ 任何覆盖率式探针报出极端比例（0% 或 100%）时，
第一反应应是探针错而不是代码错；**基线对照或权威测试一次就能把两者分开。**

---

## 文件清单

- 迁移：`backend/services/catalog/.../V145__platform_category_mapping.sql`
- 迁移：`backend/services/channel/.../V146__ad_type_normalization.sql`
- 后端：`PlatformCategoryMapping{Controller,Models,Rules,Service}.java`、`AdTypeDiff{Controller,Models,Service}.java`
- 前端：`apps/web/src/app/listings/category-mappings/`、`apps/web/src/components/listings/platform-mapping-{model,workspace}.ts(x)`
- 前端：`apps/web/src/app/ads/ad-type-diff/`、`apps/web/src/components/ads/ad-type-diff-{model,workspace}.ts(x)`
- 白名单：`apps/web/src/lib/api-proxy.ts`（两条车道各新增一个独立前缀元素）
- 契约：`packages/api-contracts/src/{catalog,channel}.*`（codegen 产物，非手改）
- 台账：`docs/product/capability-blueprint/module-progress.md`

## 后续待办

1. **`LANES` 与 `RUNNING_QUOTA` 的矛盾需要用户裁决**（4 vs 2）。
2. `9.4.2.1` / `8.5.3.1` 待下轮接手（worktree 成果完整保留）。
3. 外部 Token Plan 配额是本轮吞吐的真实瓶颈，建议关注消耗节奏。
4. `pnpm typecheck` 的 turbo 缓存跨 worktree 串味问题值得在仓库层面修掉
   （例如让 `typecheck` 任务不进缓存，或按 worktree 隔离缓存键）。
