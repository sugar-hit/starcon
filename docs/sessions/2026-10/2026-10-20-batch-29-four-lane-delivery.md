# 批次 29 · 缺货与饥饿预警 / 批量搬迁 / 刊登编辑器 / 刊登模板

> 时间：2026-10-20 00:0x tick 派发 → 00:40 全部落地 → 并入集成头 → L2
> 集成头：`integration/module-dev`
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0
> 闸：节流 100min ≥ 50 ✓ · therm 无降频 ✓ · load 4.45↓（峰值 8.51 为上一轮 L2 尾波）✓ · Testcontainers PG 0 ✓ · running 0/6 ✓ → **GO**
> 前情：batch-28（11.2.3.3 / 7.8.2.1 / 4.4.2.1 / 2.4.2.3）已由上一轮 tick 并入并 push，本轮开局无待并入车道，`merge-ready` 返回空集。

---

## 本批回收

开局 `merge-ready` 返回 `merged: []` —— batch-28 的 4 条车道上一轮已并入并推送，本轮只做 `gc`
回收其 4 个 worktree（`11.2.3.3` / `7.8.2.1` / `4.4.2.1` / `2.4.2.3`）。

调度闸取闸时上一轮的闸仍 `held`（`ageSeconds=4143`，`stale=false`），按协议**不抢**；等它自然到期
（TTL 4200s）后 `gate=free` 才取闸。这一轮因此比平时晚约 1 分钟开工。

## 本批派发

`lane.mjs plan --lanes 4` 输出 `newlyPlanned` 四条，`serviceBlocked` 为空（服务缺口不构成阻塞）：

| 模块 | 名称 | 落地服务 | 迁移 | 车道分支 | api-proxy 插入锚点 |
| --- | --- | --- | --- | --- | --- |
| 7.8.2.2 | 缺货与饥饿预警 | warehouse | V192 | `feat/7-8-2-2` | `services[]` 数组**最末尾**（`];` 之前） |
| 4.4.2.2 | 批量搬迁 | channel | V193 | `feat/4-4-2-2` | `listing-claims` 元素**之后** / 9.2.1.2 trade 注释**之前** |
| 4.2.1.1 | 刊登编辑器 | channel | V194 | `feat/4-2-1-1` | `listing-content-writeback` 元素**之后** / `listing-claims` 元素**之前** |
| 4.2.2.1 | 刊登模板 | channel | V195 | `feat/4-2-2-1` | trade `fulfillment/split` 元素**之后** / `crm/` 元素**之前** |

### 为什么给每条车道指定不同的 api-proxy 插入锚点

batch-28 里 2.4.2.3 与 4.4.2.1 把各自新的 `services[]` 元素插在了**完全相同的位置**，
git 判冲突，只能另派一个「只修这一个点」的修复任务（把元素换锚点、跑全量 allowlist 回归）才并进去。

本批 3 条 channel 车道都要改 `apps/web/src/lib/api-proxy.ts`（2634 行的共享白名单累积文件），
所以调度者给每条车道**指派了一个两两被既有条目隔开的锚点**，git 能干净求并。事后核对：
`listing-editor` 落在 1504 行、`listing-migrations` 落在 1590 行、`listing-templates` 与
warehouse 那条落在 2673 行一带，四处互不重叠 ⇒ **本批零并入冲突**。

### 一个跨车道依赖陷阱：4.2.2.1 → 4.2.1.1

蓝图里 4.2.2.1 的「下游 = 4.2.1.1 编辑器」，即刊登模板是刊登编辑器的上游。
但 `plan` 把两条排进了**同一批并行**，4.2.1.1 执行时 4.2.2.1 的表还不存在。

处置：两条车道都按本仓既有约定（`ListingBulkEditModels` / `ListingClaimModels` 的
`available=false` + `ownerModule` + 影响说明）显式声明这条缺口，**不硬依赖**对方的表与端点：

- 4.2.1.1：`listing_template_apply.available=false` + `ownerModule=4.2.2.1`
- 4.2.2.1：`description_field.available=false` + `ownerModule=4.2.1.1`；`editor_entry.available=false` + `ownerModule=4.2.1.1`

两边的其余能力（动态表单 / 实时预览 / 草稿 / 多语言 / 校验；模板定义 / 规则 / 作用域 / 套用 / 出草稿）
**全部做完**，没有因为这条缺口把模块缩成半成品。

---

## 静态预检（并入后、跑 L2 之前）

对本批 4 条新迁移跑「已知互相破坏形状」三条扫描：

| 检查 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **未命中** |
| ② `SET NOT NULL` | **未命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **未命中** |

**最高风险项单独核过**：4.2.1.1 的 V194 确实要 `ALTER TABLE listing` 加内容列
（`bullets` / `images` 是 4.2.1.2 台账里点名「尚未交付」而留给本模块的内容域），
实际写法是 `ADD COLUMN bullets text[] NOT NULL DEFAULT '{}'::text[]` /
`ADD COLUMN images jsonb NOT NULL DEFAULT '[]'::jsonb` ——
**两列都带 DEFAULT**，所以 4.2.1.2 已交付的批量编辑 INSERT 路径（不写这两列）不会违约；
且**没有**对既有列做 `SET NOT NULL`、**没有**改动 V177 的 `guard_listing_live_content` 触发器，
另建 `listing_editor_live_content` 只管新加的两列。4.2.1.2 回归 17 例全绿。

台账完整性核对：`465` 行、`0` 个重复模块 ID。

---

## 模块 7.8.2.2 缺货与饥饿预警

**这个功能做什么**：运营能看到两张**分开的**清单 —— 一张是「这个店铺长期分不到货、正在挨饿」
（饥饿清单），一张是「这个 SKU 真的没货卖了」（缺货清单），两张各自的处置建议不一样。
过去这两种问题混在一个缺货告警里，调优先级和补货搞混了，处置动作就必然做错。

**技术落地**：

- 迁移：`V192__warehouse_stockout_starvation_alerts.sql`（warehouse，两张新表
  `starvation_alert`（SKU×店铺）与 `stockout_alert`（SKU×仓，`warehouse_id` 可空 = 全网查不到记录），
  `tenant_id NOT NULL` + `ENABLE/FORCE RLS` + `REVOKE DELETE` + 5 道 DB 约束；**零 ALTER 既有表**）
- 端点：`/warehouse/inventory/stockout-alerts/…` 共 10 个路径 / 11 个操作
  （`GET capabilities`、`POST evaluate`、`GET/POST` 两套 `清单` `详情` `acknowledge` `resolve`），
  jOOQ 显式 SQL，**无 DELETE 端点**
- 页面：`/inventory/stockout-alerts` + `/inventory/allocation/stockout-alerts`（同族兄弟路由，L3
  `/inventory/allocation` 已被 7.8.1.1 占用）→ `apps/web/src/components/inventory/stockout-alert-*`
- 测试：`StockoutAlertIntegrationTest` 25 例（Testcontainers 真实 PG16，含多租户三断言）

**关键设计决策**：

- **「没货卖」和「没分到货」是两张互不共表的表、两套端点、两套状态枚举、两套建议生成逻辑** ——
  蓝图的加粗句落成结构。否决了「一张表 + 一个 `type` 枚举」的常见做法：那正是蓝图说的
  「混在一个缺货告警里」。
- **「不知道」是一个状态，不是一个 0**：店铺绩效（17 绩效域）在 warehouse 服务无表也无授权 API，
  `capabilities` 恒报 `performanceBasisAvailable=false`，**不接绩效、不算绩效分**，
  而不是拿 0 分假装评过了。
- 总可用量只取 `stock_bucket.available` 生成列，**未复制 7.1.1.3 的可售量五片口径**；
  与 7.8.1.1 的算式尚未抽成共享计算器（要改他人文件，越界）—— 记为遗留。
- 权限码复用既有 `warehouse.inventory.read` / `.update`，未新增权限码。
- 契约只改 warehouse 两个文件；codegen 造成的 `channel.*` key-order 噪音被 `git checkout` 还原，
  避免与其他车道撞车。

## 模块 4.4.2.2 批量搬迁

**这个功能做什么**：运营按类目 / 销量区间 / 价格区间筛出一批 listing，一次性搬到多个目标店铺，
每个目标店铺的标题**自动差异化**，并同时看到重复铺货风险评分 —— 避免平台判重复铺货把 listing 压下去。

**技术落地**：

- 迁移：`V193__listing_bulk_migration.sql`（channel，四张新表 `listing_migration_batch` /
  `_item` / `_content_diff` / `_duplicate`，**零 ALTER 既有表**，RLS + 触发器 + 索引 + GRANT）
- 端点：`ListingMigrationController` 9 个 —— `/channel/listing-migrations`
  `capabilities` / `filter-options` / `preview` / `POST` / `GET` / `{id}` / `{id}/items` /
  `duplicate-risks` / `migrated`
- 页面：`/listings/migrations` → `apps/web/src/components/listing-migration/`；
  `/listings/claim` 的页头注释已更新并指向新页
- 测试：`ListingMigrationIntegrationTest` 19 例 + `ListingMigrationTenancyIntegrationTest` 3 例
  + 前端 `listing-migration-model.test.ts` 23 例 + `listing-migration-allowlist.test.ts`（双向断言）

**关键设计决策**：

- **标题差异化由 DB 层强制**，不是提示文案：`CHECK` + BEFORE INSERT 触发器用
  `lower(btrim())` 归一化比较，**只改大小写不算差异化**。
- **重复度分值真的算出来**：标题（字符二元组 Dice）/ 类目 / 属性三轴加权；
  **不可知分量不计入分母**，三轴全不可知时给 `UNKNOWN` + **分值 null（不是 0）**。
  否决了「三轴都按 0 算」的做法 —— 那会把「量不到」读成「重复度为零」。
- **风险与已搬迁分表分端点**，两类问题可分别查询。
- 幂等三道闸，含派生幂等键。
- **销量维度**按蓝图要求以「来源未接入 + 显式 UNKNOWN」交付，真实数据源待 4.5.1.1 平台商品监控。

## 模块 4.2.1.1 刊登编辑器

**这个功能做什么**：给单个商品开一张**按目标平台动态生成**的编辑表单（标题、要点、描述、变体维度、
图片集、视频、A+ 模块、类目属性、合规声明），左边编辑右边实时预览，多语言 Tab，
存草稿、提交审核。

**技术落地**：

- 迁移：`V194__listing_editor.sql`（channel，三张新表 + 对 `listing` 的 2 列 ALTER，
  ALTER 部分带 DEFAULT，见上文静态预检）
- 端点：`/channel/listing-editor` 共 9 个 —— `capabilities` / `form-schema` / `validate` /
  `drafts` 列表·`{id}`·POST / `drafts/{id}/submit` / `submissions` / `source-product` / `media-assets`
- 页面：`/listings/editor` → `apps/web/src/components/listing-editor/`（model / workspace / model.test）
- 测试：`ListingEditorIntegrationTest` 16 + `ListingEditorTenancyIntegrationTest` 3 +
  `ListingEditorFieldRulesTest` 6 + 前端 11 例 + allowlist 6 例

**关键设计决策**：

- **表单字段由平台要求驱动生成**：新建全局只读注册表 `platform_listing_field_requirement`
  （12 平台 + Amazon 站点级差异），服务层用 SQL「就近覆盖」解出字段集，
  能力门控挂到 2.1.1.2 已交付的 `platform.capabilities` 上 ——
  **代码里没有平台分支**，测试直接改能力矩阵证明「矩阵变化 ⇒ 表单自动变」。
- **「不知道」是状态**：约束三值 KNOWN / DELEGATED / UNKNOWN，能力四值
  SUPPORTED / UNSUPPORTED / UNKNOWN / NOT_APPLICABLE，各带 `ownerModule`；
  **必填只在约束已知时才阻断**（约束 UNKNOWN 时不拿它当必填，否则会把「量不到」变成「填不了」）。
- **草稿隔离三层**：无改线上的端点 + `app.draft_only` 事务标记 + 物理分表；
  平台 + 站点 + 语言隔离由两条部分唯一索引 + 触发器钉住。
- **提交审核 = 入队**（QUEUED 快照 + 幂等键 + 旧入队自动撤销），页面上没有「直接上线」。

## 模块 4.2.2.1 刊登模板

**这个功能做什么**：把「标题取商品属性、图片取图片组、类目走类目级规则」这类填充规则沉淀成
**类目级 / 店铺级**模板，一键套用到目标商品 —— 模板存的是**取值规则**，
商品改价换图后套用结果跟着变，不是拷一份快照。

**技术落地**：

- 迁移：`V195__listing_template.sql`（channel，601 行，五张新表，**零 ALTER 既有表**）
- 端点：`ListingTemplateController` 12 个 —— `/channel/listing-templates/**`
  + `ListingTemplateSourcePort`（跨服务只读 catalog）
- 页面：`/listings/templates` **加页签**（刊登模板 / 描述模板并存，不覆盖 4.2.2.2 描述模板工作区）
  → `apps/web/src/components/listing-template/`
- 测试：`ListingTemplateIntegrationTest` 12 + `ListingTemplateTenancyIntegrationTest` 3 +
  `StubCatalogServer` + 前端 15 + allowlist 4

**关键设计决策**：

- **「模板存规则不存快照」落成四道结构约束**：
  ① 物理分表 —— 模板表**无任何按 listing 存值的列**，值只落 append-only 套用日志；
  ② `source_ref` 标识符 CHECK，杜绝把具体取值写死进规则；
  ③ 表达式守卫触发器 —— 占位符须为标识符且至少一个非 LITERAL；
  ④ `refuse_listing_template_delete` 触发器 —— 套用日志只追加。
- 对应测试 `rulesNotSnapshots_liveEvaluationFollowsSourceProduct`：改 stub catalog 的价格
  99.00 → 129.00、素材键 `media/a2-v1.jpg` → `media/a2-v2.jpg`，重套用后草稿 title 由
  `Special 99.00` 变 `Special 129.00`、images 含新键，
  并断言 `listing_template_field` 聚合行**套用前后逐字相同**。
- **不新建平行草稿表**：套用结果写进 4.2.1.2 已交付的 `listing_content_draft`（V177），
  沿用其 CHECK 与 `guard_listing_live_content` 触发器。
- catalog 上游经 `StubCatalogServer` 在集成测试里真实起 HTTP 端点（跨服务只读，不跨库 FK）。

---

## L2 批量验证

在本批次**唯一一次**全量验证，于集成头 `4364cf88` 上执行：

```bash
cd backend && ./mvnw verify                                    # exit 0
cd ..    && pnpm typecheck && pnpm lint && pnpm test            # 全 exit 0
cd ..    && NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   # exit 0
cd ..    && node tools/module-progress/sync.mjs --check          # ✓ 台账与蓝图一致
```

| 闸 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | **exit 0 · BUILD SUCCESS** —— 277 个测试类 / **5310 例** / 0 失败 / 0 错误 / 0 跳过 |
| ├ identity | 270 例 |
| ├ catalog | 1502 例 |
| ├ channel | 594 例（本批 4 条车道全在其中：ListingEditor 16+3+6、ListingMigration 19+3、ListingTemplate 12+3） |
| ├ warehouse | 1535 例（含本批 `StockoutAlertIntegrationTest` 25 例） |
| ├ trade | 1391 例 |
| └ crm | 18 例 |
| `pnpm typecheck` | **3/3 successful** |
| `pnpm lint` | **3/3 successful**（1 条 `exhaustive-deps` warning 属既有文件） |
| `pnpm test` | **269 个测试文件 / 4426 例全绿**（含 `apps/web/src/app/api` 全量 allowlist 回归） |
| Next production build | **exit 0** —— 新路由全部进产物：`/listings/editor`、`/listings/migrations`、`/listings/templates`、`/inventory/stockout-alerts`、`/inventory/allocation/stockout-alerts` |
| `sync.mjs --check` | **✓ 台账与蓝图一致** |

**L2 全绿，一次通过，没有二分定位，没有修复任务。**

### 本轮 L2 期间的两个环境事实（如实记录）

1. **PG 容器峰值 9 个**（带 `org.testcontainers.sessionId` 标签）—— 是 `mvnw verify` 自己按测试类
   逐个起 Testcontainers 容器造成的，**不是**残留孤儿容器（无标签的那种不计入本口径）。
   verify 结束后 `docker ps` 里带标签的容器回到 **0**，全部被 ryuk 回收。
   load 全程 3.0–3.8 区间，没有出现爬升。
2. **01:00 另一个 tick 在集成头上提交了一个 commit**（`82254938`）：本轮我自 00:02 起持有调度闸
   （TTL 70 分钟），那个 tick 按协议判定「闸被活 tick 持有」→ 零派发 + 只读，
   但它仍把自己的只读取证会话日志提交进了集成头。**该 commit 是纯文档（1 个 markdown 文件，
   131 行），没有碰任何代码**，我的 4 条车道合并 commit（`4364cf88`）是它的直接父提交，
   所以 L2 的结论对当前 HEAD 依然成立。
   记录这一点是因为：持闸 tick 期间**只读 tick 仍能写集成头**这个缝隙，协议没有覆盖到。


## 收尾三件套

| 步骤 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-20-batch-29-four-lane-delivery.md`，四个模块各一节 |
| ② commit | ✅ 说明文件与台账**同一个 commit**：`docs(batch-29): 7.8.2.2 / 4.4.2.2 / 4.2.1.1 / 4.2.2.1 —— 四条车道并入集成头，L2 全绿` |
| ③ push | ✅ `git push origin integration/module-dev` —— **不 push `main`** |

### 本批的合并 commit

| 模块 | 合并 commit | 车道 commit |
| --- | --- | --- |
| 7.8.2.2 缺货与饥饿预警 | `f088a03e` | `c7469d3a` / `6fa93d91` / `8e9b68b7` / `6c3b9b79`（4 个 atomic commit） |
| 4.4.2.2 批量搬迁 | `d199f85a` | `ffdff581` |
| 4.2.1.1 刊登编辑器 | `605d7e05` | `a5c750ae` |
| 4.2.2.1 刊登模板 | `4364cf88` | `1e919ca6` |

### 遗留与后续待办

- **7.8.2.2**：店铺绩效（17 绩效域）在 warehouse 无表无授权 API，`performanceBasisAvailable=false`
  恒为假；总可用量只取 `stock_bucket.available`，与 7.1.1.3 的可售量五片口径尚未统一
  （共享计算器要改他人文件，越界）。
- **4.4.2.2**：销量维度以「来源未接入 + 显式 UNKNOWN」交付，真实数据源待 4.5.1.1 平台商品监控。
- **4.2.1.1**：4.2.2.1 刊登模板未交付 ⇒ `listing_template_apply.available=false` +
  `ownerModule=4.2.2.1`；另有 4.3.1.1 派发、4.2.3.2 合规预检、4.2.3.1 类目属性细则、3.4.2.2 文案库
  套用四段缺口，均按 `available=false` + `ownerModule` 如实报出。
- **4.2.2.1**：`description_field.available=false` + `ownerModule=4.2.1.1`；
  `editor_entry.available=false` + `ownerModule=4.2.1.1`。
- **协议缝隙**：持闸 tick 期间，另一个只读 tick 仍能往集成头提交（本次是纯文档，无害）。
  建议在 `PIPELINE.md` 的闸规则里补一句「只读 tick 的只读产物也不得 commit 进集成头」。

