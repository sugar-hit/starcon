# 4.4.2.1 商品认领（跨店搬家）· 车道 feat/4-4-2-1

- **日期**：2026-10-19
- **车道**：`feat/4-4-2-1`（worktree `.worktrees/4.4.2.1`，基线 `integration/module-dev` @ `6904288d`）
- **归属服务**：`backend/services/channel`（**不是 catalog**，理由见下）
- **迁移版本**：`V190__listing_claim.sql`（预分配；同批 11.2.3.3=V188 / 7.8.2.1=V189 / 2.4.2.3=V191）

---

## 决策摘要

### 1. 归属 channel，而不是蓝图 L2 表首项的 catalog

蓝图 README 的 L2 表把卷 04 写成「catalog + channel」（列表首项 catalog），编排脚本据此排了这条车道。
但**已交付的同族事实全部在 channel**：4.1.1.1（V171 `listing` / `listing_variant`）、
4.1.1.2（V175 `listing_content_snapshot`）、4.3.1.2（V183 `publish_batch` / `publish_task`）、
4.3.2.1（V187）。本模块要读**源 listing**、要写**目标店铺 listing 草稿**，源与目标都是 channel 自己的事实：

- `listing` 必须按 `shop` / `shop_marketplace` 收敛可见性，那两张表是 channel V1 的事实，
  带 `data_scope` RESTRICTIVE 策略读 `app.grants`；
- 放 catalog 就得为店铺维度数据范围去读 channel 的表 —— 那是 AGENTS.md 禁止的跨服务 SQL；
- 目标草稿要挂进 V183 的发布队列，那两张表也在 channel。

**这与上一轮 4.3.2.1 的实跑结论一致**（同车道 4.1.1.1 的台账证据里已写明"归属 channel"）。
台账证据格同步写明了这条理由，下一轮读台账的人不会再以为它该在 catalog。

### 2. 三条边界的物理落法（不是靠注释提醒）

| 蓝图加粗句 | 落点 |
| --- | --- |
| 源 listing 不受影响 | 服务层对源**只读**（全类无一条 UPDATE/DELETE 打源上）+ V190 触发器 `guard_claim_source_untouched`（AFTER INSERT ON listing_claim）复核源 status / review_status / version **逐字相等**（23514 拒绝，兼作乐观并发）+ 认领记录存「认领时 vs 现在」两组快照 + 身份不可改写 / 记录不可删除的触发器 |
| 新店铺 listing 是全新平台资源，需重新过平台审核 | 目标写死 `DRAFT` / `NOT_SUBMITTED` / 平台可售 `UNKNOWN`；评分 / 币种 / 上架时间 / 可售量全 NULL（源商品在 A 店铺的平台事实在 B 店铺不成立）；**零 `listing_publish_record` 写入**；触发器复核目标仍是草稿态；认领状态 `PUBLISHED` 只表示"已离开草稿态"，**不代表平台已批准** |
| 输出含待发布任务 | **复用 V183** 的 `publish_batch` + `publish_task`（`state='PENDING'`），不另建平行发布任务表 —— 另建 = 平台上两套队列、4.3.1.2 的限流只管一套 |

### 3. 平台类目 ID 与「站点」绑定（开发中改掉的一个设计）

初版打算把所有字段一视同仁地"保留 / 重写 / 跳过"。写的时候发现：
**平台类目 ID、类目属性、合规声明是与站点绑定的**（不是店铺、也不是平台）。源站点与目标站点不同还沿用源值，
等于在目标 listing 上写一个**假平台事实**。于是改成：

- 跨站点时「保留」这三个字段 ⇒ **422 拒绝并点名**改「重写」或「跳过」；
- 同站点时允许保留；
- `CATEGORY_PATH`（人类可读的类目路径）跨站点允许保留，只在 note 里提示复核。

代价是页面要多一个"跨站点"判定，收益是不会在目标 listing 上留下假事实。

### 4. 待发布任务的 `draft_id` 为什么是空的

V177 `listing_content_draft` 的 CHECK 要求 `state='DRAFT'` 时 `last_operation_id IS NOT NULL`，
而 `last_operation_id` 语义上指向 `listing_bulk_operation`（4.2.1.2 批量编辑的产出）。
**认领不是批量编辑** —— 造一条假的批量操作去凑这个 CHECK 是在写假事实。

所以：内部内容草稿（4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑）未交付 ⇒ 本模块**不预建**草稿表、
待发布任务 `draft_id` 为空，内容以**本次认领登记的内容快照**（V175 `listing_content_snapshot`，
`source='MANUAL'`）为准。这一点在 `/channel/listing-claims/capabilities` 里 available=false + ownerModule 明写。

---

## 关键 trade-off

1. **复用 V183 而不是自建任务表**：自建能少一层耦合，但会让平台上出现两套待发布队列，
   而 4.3.1.2 的限流（在途 / 滑动窗口）只管自己那套 —— 那正是「触发平台限流并可能影响店铺安全」要防的事。选复用。
2. **目标的内容快照写进 V175 而不是另建表**：V175 的 `source` 取值域里就有 `MANUAL`（人工登记），
   复用它让 4.1.1.2 的内容对照能直接看到目标草稿的内容，且不新增"第二套内容事实来源"。
3. **源 listing 没有内容快照 ⇒ 422**：宽松做法是"没有内容就建个空壳草稿"，但那样"复用原商品内容"是空话。
   宁可直接拒，让运营先让 02 同步 / 4.4.1 采集补齐快照。
4. **跨平台认领 ⇒ 422**：平台类目 ID / 站点 / 发布限流都按平台绑定，跨平台不是"搬家"而是"迁移到另一个平台"。
   蓝图说的"跨店搬家"按同平台理解；越界请求被显式拒绝而不是悄悄生成一个不成立的类目 ID。
5. **契约文件文本差 2 万行**：重生成 `channel.openapi.json` 后与仓库产物的**语义**差只有 6 个新 schema +
   4 个新 path（外加 `BatchCandidateRequest.minSimilarity` 在集成头里本来就有的 int/float 漂移），
   文本差大是因为仓库里那份是历次并入后由 `merge-union.mjs` 重排过的顺序，与 SpringDoc 原始输出顺序不同。
   合并驱动器按「键」（paths / schema 名）做三方求并，顺序差异不影响并入结果。

---

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V190__listing_claim.sql` — 两张新表 + 4 个触发器 + RLS + GRANT（零 ALTER 既有表）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingClaimModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingClaimService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingClaimController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ListingClaimIntegrationTest.java`（12 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingClaimTenancyIntegrationTest.java`（3 例）

### 前端

- `apps/web/src/components/listing-claim/listing-claim-model.ts` + `listing-claim-model.test.ts`（23 例）
- `apps/web/src/components/listing-claim/listing-claim-workspace.tsx`
- `apps/web/src/app/listings/claim/page.tsx`
- `apps/web/src/app/api/channel/listing-claim-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` — **纯增量**新增一条 `listing-claims` 元素（既有分支一字未动）

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` / `src/contracts/channel.ts` — codegen 产物（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`）
- `docs/product/capability-blueprint/module-progress.md` — 只改 `| 4.4.2.1 |` 那一行

---

## 验证

- **L0 编译闸**：`backend/./mvnw -q compile` 绿；`pnpm typecheck` 绿（3 包全过）。
- **L1 模块闸**：`./mvnw -pl services/channel -am test -Dtest='ListingClaim*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → `ListingClaimIntegrationTest` 12 例 + `ListingClaimTenancyIntegrationTest` 3 例 = **15 例全绿**（Testcontainers PostgreSQL 16，连接池压到 1）。
- **前端**：`pnpm vitest run apps/web/src/components/listing-claim/listing-claim-model.test.ts apps/web/src/app/api/channel/listing-claim-allowlist.test.ts`
  → 2 文件 26 例绿。
- **api-proxy 全量 allowlist 回归**（改了共享白名单必跑）：`pnpm vitest run apps/web/src/app/api` → **75 文件 476 例绿**。
- `pnpm lint` 绿（只有其他车道既有的 react-hooks 警告，本车道文件无警告）。

---

## 开发中修掉的真缺陷

1. **跳过的字段在 jsonb 列上插不进去**：SKIP 最初把目标值写成空串，PG 直接 `invalid input syntax for type json`。
   改成"该字段类型的合法空值"（数组 `[]` / 对象 `{}` / 文本 `""`）。
2. **`fetchValue(String, Class, Object...)` 在 jOOQ 3.19 不存在**：多传了一个 `Class` 参数时，
   编译器选到了 `fetchValue(String, Object...)`，`Class` 被当成绑定值 ⇒
   `Type class java.lang.Class is not supported in dialect POSTGRES`。改用 `fetchOne(...).get(0, type)`。
3. **平台 / 站点注册表是 V1 迁移随代码发布的**：测试自己造一份会撞 `platform.code` /
   `marketplace(platform_id, code)` 唯一约束。改成直接读迁移发布的码表（并按 (platform, code) 查 id）。
4. **PG 的 jsonb 输出会把 `/` 转义成 `\/`**：断言用 `contains("https://img/1.jpg")` 会假红。
   改成解析 JSON 后比元素。
5. **映射复制不能只看 `listing_mapping`**：源 listing 也可能只有变体上的投影位
   （4.1.1.1 登记路径）。只读映射表会**悄悄丢掉一条真实映射** ⇒ 目标商品变未映射。加了投影位回退。
6. **`listing_claim_field` 的 CHECK 少写了两个字段**：模型扩到 10 个字段后忘了同步迁移的 CHECK，
   被 DB 拒（这正是 CHECK 该做的事）。

---

## 后续待办

- **4.4.2.2 批量搬迁**（未开发）：本模块的「标题逐字沿用」会提示重复铺货风险，
  但**标题差异化本身是 4.4.2.2 的职责**，本页只提示、不代改。
- **4.3.1.1 一键刊登**（未开发）：本模块只把草稿与待发布任务入队，真正发往平台由它承接。
- **4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑**（未交付）：内部内容草稿表一交付，
  认领产出的待发布任务就可以带 `draft_id`（内容以内部草稿为准），本模块的 `internal_content_draft`
  能力维度届时从 available=false 翻成 true。
- **4.3.2.2 平台状态回流**（未开发）：目标 listing 提交平台后的审核结果由它回报；
  在那之前认领状态里的 `PUBLISHED` 只表示"已离开草稿态"。
- **卷 05 定价 / 卷 07 库存**：认领不带售价与内部可售量，两侧交付后本模块不需要改（那是内容与库存的事）。
