# 2026-10-20 · 4.4.1.3 采集箱

> 车道 4.4.1.3 · 分支 `feat/4-4-1-3` · 基线 `integration/module-dev`（03785467）
> 蓝图原文：`docs/product/capability-blueprint/04-listing-and-content.md` §4.4.1.3

## 决策摘要

**蓝图职责**：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。**认领时必须做品牌与分类映射**，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」

**归属 channel 服务**：输入是 4.4.1.1 / 4.4.1.2 落的采集草稿，输出是认领结果（商品草稿载荷），
中间不碰任何一张 catalog 的事实表。与 4.1.1.1 / 4.4.1.1 / 4.4.1.2 / 4.4.2.1 同一先例。

**上游依赖已确认**（开工前 10 分钟自检，grep 集成头）：

| 上游 | 交付物 | 本模块怎么用 |
| --- | --- | --- |
| 4.4.1.1 | `listing_collect_source` / `listing_collect_task` / `listing_collect_draft`（V202） | 采集草稿是**采集箱的输入**；白名单是「平台/来源」分组维度；任务留痕 |
| 4.4.1.2 | `listing_bulk_collect_task` / `listing_bulk_collect_result`（V204） | 判定条目的来源是「链接采集 / 批量采集 / 插件上报」；`draft_id` 外键决定了**草稿能不能连带删** |

**🔴 认领时必须做品牌与分类映射** —— 蓝图加粗句，本模块做成**三层硬约束**（不是 TODO、不是占位）：

| 层 | 落点 |
| --- | --- |
| DB | `listing_collect_claim_rule.brand_mapping` 非空是 CHECK；`target_category_code` 与 `category_mapping` 至少一个非空；`naming_template` 非空 |
| 服务层（建规则） | 缺品牌映射 / 缺分类映射 / 命名规则无占位符 ⇒ **400** 并写明原因 |
| 认领（逐条） | 草稿的品牌属性要在「品牌映射」里查得到、类目要落在「分类映射」或「目标分类」上；**查不到的那一条认领失败**，原因写回条目 `claim_error` |

**🔴 认领只产出草稿，不跨服务写 catalog 的表**：认领结果落 `listing_collect_claim_result`
（`spu_draft` / `sku_drafts` 是**载荷**，给 03 建档消费），`build_state` **恒为 `PENDING_BUILD`**；
能力表把「商品草稿落库」标成 available=false + ownerModule=3.1.1.1 + impact。
理由三条：① 04 卷边界明写「**不做：不新建 SKU（引导到 03）**」；② AGENTS.md **禁止跨服务数据库访问**；
③ 03 的建档需要**租户自有的 SPU 编码**（编号规则归 03/identity）、**属性字典 id**（3.2.1.1）与
**计量单位**（3.1.2.4）—— channel 自己造这些 id 就是**假数据**。

## 关键 trade-off

### 1. 采集箱**自己有一张条目表**，而不是「箱子 = 全部草稿」的投影

两种形态都想过。选「条目表 + 显式收录」的理由：

- **删除语义干净**。蓝图要「批量删除」，而 V202 明确把采集草稿的清理权交给 4.4.1.3
  （「草稿是工作副本，清理权归 4.4.1.3 采集箱」）。若箱子只是草稿的投影，「删一条」要么真删草稿
  （会撞上 `listing_bulk_collect_result.draft_id` 外键），要么得再存一个「已删」标记（两份事实）。
- **勾选语义不含糊**。「暂存区」意味着运营**挑**几条进来；箱子里有什么就是有什么，不存在
  「这一页显示的是全部草稿、下一页又变了」的错觉。
- **不写上游的表**（除按 V202 授权的草稿清理）：条目表是本模块自己的可变状态（标签 / 认领状态 / 认领结果），
  草稿是 4.4.1.1 的事实。

代价：前端多一个「待收录的采集草稿」面板（`GET /candidates`）。这个面板反而把「草稿是原料、箱子是暂存区」
讲清楚了。

### 2. 批量删除时**上游草稿不一定删** —— 分两种情况说清

`listing_bulk_collect_result.draft_id` 外键**没有** ON DELETE CASCADE（V202 建表时如此），
所以删草稿会破坏 4.4.1.2 的结果清单事实。⇒ 删除条目时：草稿**没有被结果清单引用** ⇒ 一并清理
（V202 授权的清理权真正用上）；**被引用** ⇒ 保留草稿，响应里写明「保留草稿以免破坏 4.4.1.2 的结果清单事实」。
两种情况都有集成测试覆盖。**采集任务（抓取留痕）永远不删**（它是审计事实，有 DB 触发器挡着）。

### 3. SKU 草稿只产出一行「观察到的组合」

4.4.1.1 的解析器把规格抓成**键值对**（`{"颜色":"红","尺码":"M"}`），不是 SKU 组合表。
⇒ 认领时产出**一行** SKU 草稿（携带全部规格取值），并在 `build_note` 里写明
「上游每个规格轴只有一个取值 ⇒ 只产出一个观察到的组合；多组合展开、SKU 编码、属性字典 id 与计量单位都由 03 建档补齐」。
**不编造**组合数 —— 编一个笛卡尔积就是假数据。

### 4. 映射的传输形态用**键值项列表**，不用 `Map<String,String>`

Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象 `{}`（9.6.1.3 已踩过），
前端在 strict 模式下按字符串键索引它会编译不过。⇒ 与 4.4.1.1 的 `ListingCollectKeyValue` 同一手法。

### 5. CHECK 约束里不能写子查询 —— 「品牌映射非空」改用与 `'{}'` 比较

PostgreSQL **没有** `jsonb_object_length`（迁移当场炸了），且 CHECK 约束**不允许子查询**，
所以 `NOT EXISTS (SELECT 1 FROM jsonb_each_text(...))` 这种写法也用不了。
⇒ 结构级约束放 DB（`jsonb_typeof(...)='object'` + `brand_mapping <> '{}'::jsonb`），
**值级别**的校验放服务层（`cleanMapping` 把空键空值整条丢掉，丢完为空就建不出规则）。

### 6. 🔴 踩坑：PG JDBC 驱动会改写 SQL 里的 `{}`

`insert ... '{brand}-{title}' ...` 同一串语句在 **psql 里合法**，经 **JDBC 驱动**发出去却报
「Unterminated string literal started at position 287」⇒ 驱动对 `{}` 做了转义处理。
凡是值里带花括号的（jsonb 空对象、命名规则模板），**一律走占位符**（测试里为此加了 `execPrepared`）。
同理，jOOQ 3.19 把 `UUID[]` 绑成**单个** uuid（PG 拒：`cannot cast type uuid to uuid[]`），
批量查标签/认领结果时**自己拼 IN 列表**，不用 `= any(?::uuid[])`。

### 7. api-proxy 前缀选 `listing-collect-box` —— 首 token 与既有全部不同

既有 token：`listing-collects`（4.4.1.1）· `listing-bulk-collects`（4.4.1.2）·
`listing-keyword-rankings`（4.5.1.2）· `listing-hijackers`（4.5.2.1）· `listing-claims`（4.4.2.1）·
`listing-compliance-precheck` · `listing-performance`。
白名单里的正则都是**锚定**的（`/^token(...)?$/`），首 token 不同 ⇒ 互不覆盖。
**为什么不挂 `listing-collects/box`**：4.4.1.1 的元素刻意没放行 `/box`（那是本模块的职责），
挂它下面就要去改**别人已交付**的那一条元素。⇒ 独立前缀（与 `listing-claims` / `listing-hijackers` 同一手法）。
**插入锚点**：`listing-bulk-collects` 那一条**之后**（与本批另外三条车道相隔 ≥ 74 行）。

### 8. 负向断言**只用自己前缀**下的形状

上一批（batch-32）4.5.2.1 拿兄弟车道 4.5.1.2 的前缀当「未交付形状」要求 404，而 4.5.1.2 **同批已交付** ⇒
并入后必然假红；更危险的是反向 —— 兄弟车道那条白名单若被取错一侧**静默删掉**，这条负向断言反而会**绿**，
把真事故盖住。⇒ 本模块的 7 个负向样本全部是 `channel/listing-collect-box/...` 下的形状
（`undeclared-action` / `items/{uuid}/claim|tags|remove|draft` / `claim` / `box`），
并在「不误伤其他车道分支」那条里**显式**把 4.5.1.2 / 4.5.2.1 的已交付前缀钉住。

## 文件清单

**迁移（1）**
- `backend/services/channel/src/main/resources/db/migration/V208__listing_collect_box.sql` —— 五张新表 + RLS + data_scope + GRANT，**零 ALTER 任何既有表**

**后端（3）**
- `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectBoxModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectBoxService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectBoxController.java`

**测试（2）**
- `backend/services/channel/src/test/java/com/starcon/channel/ListingCollectBoxIntegrationTest.java` —— 24 例
- `backend/services/channel/src/test/java/com/starcon/channel/ListingCollectBoxTenancyIntegrationTest.java` —— 3 例

**前端（4）**
- `apps/web/src/components/listing-collect-box/listing-collect-box-model.ts` + `.test.ts`（23 例）
- `apps/web/src/components/listing-collect-box/listing-collect-box-workspace.tsx`
- `apps/web/src/app/listings/collect/box/page.tsx`
- `apps/web/src/app/api/channel/listing-collect-box-allowlist.test.ts`

**共享文件（各改各的，按模块 ID / path / 类型名求并）**
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**新增一条 `listing-collect-box` 元素
- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts` —— **codegen 产物**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 4.4.1.3 那一行

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                                  # ✅
pnpm typecheck                                                  # ✅ 3/3 包

# L1 模块闸（只跑本模块两个测试类）
cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='ListingCollectBox*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ✅ Tests run: 24（集成）+ 3（多租户）= 27，Failures 0, Errors 0

# 前端
pnpm vitest run apps/web/src/components/listing-collect-box/listing-collect-box-model.test.ts \
                    apps/web/src/app/api/channel/listing-collect-box-allowlist.test.ts
# ✅ 24 例全绿

# 🔴 改了共享的 api-proxy.ts ⇒ 必须跑全量 allowlist 回归
pnpm vitest run apps/web/src/app/api
# ✅ 95 个文件 / 571 例全绿
```

**PG 容器**：本车道 L1 跑测时**同时存活 1 个** Testcontainers PG 16 容器（surefire 单 fork、两个测试类顺序跑）；
结束后**全部被 ryuk 回收**（`docker ps` 已无 `postgres:16-alpine` 残留）。

## 后续待办

1. **03 catalog 侧接管「商品草稿落库」**：03 的建档流程消费 `listing_collect_claim_result.spu_draft` / `sku_drafts`，
   补齐 SPU 编码、属性字典 id、计量单位与合规信息；接上之后本模块的 `build_state` 才有第二个取值。
2. **认领规则的品牌/分类目标值目前是不透明引用串**（分类字典与品牌字典都是 03 的事实表，本模块不跨服务校验）。
   03 侧的建档要能拒绝不存在的品牌/分类引用 —— 否则「映射过了但引用无效」会漏到建档那一步才暴露。
3. **采集箱的分组维度目前只有平台/来源、标签、认领状态**；蓝图没写更多维度，不自行扩。
4. 契约的 `channel.openapi.json` 本次是**全量重生成**（SpringDoc 的 path 发现顺序随新增 controller 变化），
   路径集合是**纯增量**（+12 path / +30 schema，0 删除）—— 并入时由 merge 驱动器按 path / 类型名求并。
