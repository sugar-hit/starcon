# 14.1.2.2 广告类型差异（channel 服务，阶段 D7）

- **日期**：2026-10-16
- **车道**：`feat/14-1-2-2`（worktree `.worktrees/14.1.2.2`）
- **基线**：`integration/module-dev` = `1af79f98`。接手时本车道 tip 仍等于集成头（尚无自有提交），
  成果全部存在于工作区未提交文件中，未丢失。
- **结论**：**已交付**。迁移 V146 + 9 个端点 + 前端工作区 + 21 后端测试 / 33 前端测试。

## 决策摘要

### 1. 服务归属 = channel（任务书前提，已复核）

蓝图卷 14 的 L2 表把 14.1 广告与增长指向 channel；更硬的证据是**上游 2.1.2.1 平台枚举映射的两张表本身就在 channel 库里**：
`platform_enum_mapping`（V1，全局只读码表，V3 已收回 channel_app 写权限）与 `platform_enum_override`（V3，租户覆盖层）。
所以本模块的归一口径**不是自己另立一套枚举**，而是直接消费上游那两张表（`enum_kind = 'AD_TYPE'`）。

**依赖自检结论：上游确实存在，未 BLOCKED。**

### 2. 「不能丢信息」做成四条并存的存储 + 两个反方向的闸

蓝图加粗句「归一化让跨平台广告可比，但必须保留原始类型标记，不能丢信息」有张力：
可比天然要求压平，压平就不可逆。解法是**归一只新增维度、不替换**：

| 落点 | 内容 | 谁说了算 |
|---|---|---|
| `platform_ad_type` | 原始标记，原样存、不折叠大小写与别名 | trigger (b) 从档案复制 |
| `internal_ad_type` | 归一后内部类型，与上者同表并存 | 同上 |
| `raw_payload` | 平台 API 返回的差异字段**原文照抄** | 服务层写，触发器只读校验 |
| `raw_only_field_keys` | 原文 minus 已归一键 = 没有内部落点的字段 | **DB 触发器派生** |

**两个反方向的闸**（这一对是本模块真正的牙齿）：
- `normalized_field_keys ⊄ raw_payload` 的键 → **拒**（否则「声称归一了一个不存在的字段」能把覆盖率刷成 100%）；
- `raw_only_field_keys` / `mapping_status` → **触发器覆盖写入**（服务层谎报保真状态在这一刻被改写回来）。

### 3. `UNMAPPED` 是显式值，不是兜底

照抄 2.1.2.1 加粗句「映射缺失必须显式报未映射」。内部类型枚举显式含 `UNMAPPED`，
`mapping_status` 恒为 `UNMAPPED_TYPE` 且**不参与任何可比聚合**，但在可比视图里**带完整原始标记单列** ——
「没有可比口径」与「没有这种广告」是相反的两件事，从表里删掉会让人以为它不存在。

### 4. 归一口径的来源不由服务层声称

`GET /resolve` 走两层（租户覆盖 > 平台默认 > 未映射）。登记档案时服务端拿解析结果与请求里声明的
`internalAdType` / `mappingSource` **逐项比对，不符即拒**；trigger (a) 再核一层 ——
**声称某层却在那张表里查无此行，DB 直接拒**（绕服务层写 SQL 同样被拒，已测）。

## 关键 trade-off

- **不做前端归一**。内部类型由「按 2.1.2.1 解析」按钮从上游带出、不可手填；`ExtraFieldView`
  （原始值 + 归一后的内部落点）**在服务端拼成同一条返回**。
  代价：前端少了一点「灵活」；收益：界面口径与落库口径不可能分叉。
- **可比性只做到字段级，不做单位级**。`unit` 只作标签展示，**不做跨平台单位换算** ——
  那是 14.3.1.1 报表口径的事，硬做会把两条车道耦在一起。
- **刻意不碰 listing / trade 表**。广告归因落不到内部 SKU 是 14.1.2.1 的责任，
  本车道跨过去会同时踩到 4.2.3.1 与 9.4.2.1 两条并行车道。
- **三张表而非一张宽表**。档案 / 字段定义 / 广告数据分离，因为它们的生命周期完全不同：
  字段定义随平台适配器发布，广告数据每天都在变。

## 本车道修掉的三个真 bug（都是自己引入后被测试逼出来的）

1. **触发器字母序**：`guard_ad_type_payload_fidelity` 原先读 `NEW.internal_ad_type`，
   而 PostgreSQL **按触发器名字母序触发**（`fidelity` < `identity`），
   实测让一条 `SPONSORED_DISPLAY` 的广告被算成 `UNMAPPED_TYPE`。
   改成自己从档案查 ⇒ 与触发器顺序彻底解耦。
2. **jOOQ 3.19 把 `OffsetDateTime` 绑成 varchar**，`start_at` / `end_at` 被 PG 拒。
   values 位置必须写 `?::timestamptz`（`ChannelService.java:380` 早有同款注释）。
3. **`PAYLOAD_COLUMNS` 里带了 FROM 子句**，列表页再拼筛选条件时拼出坏 SQL。
   拆成 `PAYLOAD_COLUMNS` + `PAYLOAD_FROM`（照抄 10.1.2.2 的 `COMPARISON_COLUMNS` / `COMPARISON_FROM`）。

另有两处测试设计缺陷（不是产品缺陷但同样值得记）：
- 静态块播种 `platform` **早于 Flyway 建表**（静态块先于上下文刷新）⇒ 改到 `@BeforeEach` + `on conflict do nothing`；
- 整个测试类共用一对租户 UUID ⇒ 用例之间互相污染（症状是「期望 0 却拿到 1」）⇒ 改 `a` / `b` 为实例字段，每例一对新租户。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V146__ad_type_normalization.sql`

**后端**
- `backend/services/channel/src/main/java/com/starcon/channel/AdTypeDiffModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdTypeDiffService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdTypeDiffController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AdTypeDiffIntegrationTest.java`

**前端**
- `apps/web/src/components/ads/ad-type-diff-model.ts`
- `apps/web/src/components/ads/ad-type-diff-model.test.ts`
- `apps/web/src/components/ads/ad-type-diff.tsx`
- `apps/web/src/app/ads/ad-type-diff/page.tsx`
- `apps/web/src/app/api/channel/ad-type-diff-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**仅新增一条 `ad-type-diffs/` 元素**，既有条目一字未动）

**契约 / 台账**
- `packages/api-contracts/src/channel.openapi.json`（codegen 产物：+9 path / +22 schema / **0 删除**）
- `packages/api-contracts/src/contracts/channel.ts`（同上）
- `docs/product/capability-blueprint/module-progress.md`（**只改 14.1.2.2 那一行**）

## 验证结果

| 闸 | 命令 | 结果 |
|---|---|---|
| L0 后端 | `./mvnw -q compile` | 通过 |
| L0 前端 | `pnpm typecheck` | 3 包绿 |
| Lint | `pnpm lint` | 绿（警告均在他车道文件） |
| L1 后端 | `./mvnw -pl services/channel -am test` | **295 例零回归**（本模块 21） |
| 前端 | `pnpm vitest run <两个本模块文件>` | **33 例绿** |

迁移在写 Java 之前先在一个**一次性 postgres:16-alpine** 上跑过 DDL + 触发器冒烟
（V1 → V3 → V146 全量 apply + 六条正反例），验证完即删容器。

## 后续待办

- **14.1.2.1 广告组管理**消费本模块的 `internal_ad_type`：广告组要按归一后的类型决定
  是否给分时竞价 / 否定词入口（能力位已在 `ad_type_profile` 里备好）。
- **14.1.1.1 活动列表**上线后按 `external_ad_id` / `external_campaign_id` 对接，本模块不用改结构。
- **14.3.1.1 报表**若要跨平台单位统一（多币种换算），应在本模块之外做，不要回头改 `unit` 的语义。
- 若将来平台广告类型暴增，`platform_ad_type` 可加**只读字典表**来替代当前的口径约束，
  但**不要**把它做成归一的真相来源 —— 真相仍在 2.1.2.1。
