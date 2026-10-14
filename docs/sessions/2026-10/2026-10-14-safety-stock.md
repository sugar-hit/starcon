# 7.2.2.2 安全库存（L3 · 阶段 D3 · 锚点 7.2.2）

## 决策摘要

蓝图原文：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成补货
建议。输入：阈值配置；当前可用量投影；日均销量。输出：安全库存预警 + 可选自动触发 6.2.1.2 补货建议。」
其中**加粗**的那条是本模块的全部难点：「安全库存要区分『平台断货风险』和『自有仓耗尽』：
FBA 断货造成的损失远大于自有仓」。

### 1. 那条红线落成三件可计算的事

不是写进注释就算交付：

1. `warehouse_kind` 挂在**阈值档自己的挂仓表**上（`safety_stock_policy_warehouse`），
   不改 `warehouse` 表 —— 它根本没有 `type` 列，FBA 分类归 7.1.3.1 那条车道（与 V56 同一纪律）。
2. 延迟约束触发器钉死「**一档阈值挂的仓必须同 kind**」。这一条是红线最实质的落点：
   混挂会得到一个「还剩 30 件」而实际上自有仓已归零、FBA 早断货的数 ——
   那样就分不出这两件事了。
3. 预警类型由 kind **派生**（调用方不能自选），且平台侧**一律按上一级严重度处理**
   （`effectiveSeverity`），排序 SQL 与 Java 端提级是同一套规则的两次实现。

关键取舍：`effectiveSeverity` 与配置值 `severity` **两个都返回**。只给提级后的会让人以为自己
配了 CRITICAL；只给配置值又会让 INFO 的 FBA 预警排在自有仓 WARNING 后面 —— 那就等于没提级。

### 2. 槽位唯一性是「范围 × 仓」，不是「范围」

**这是本模块唯一一个「没有照抄上游」的地方，且必须如此。**

7.2.2.1 的 `sellable_policy_open_reference` 建在 `(tenant_id, scope_type, reference_id)` 上，
V56 照抄它即可。但安全库存不行：**同一个 SKU 必须能同时对自有仓与 FBA 各配一档**，
那正是「区分两种风险」的物理前提。槽位只按范围键去重的话，第二档在插入时就被拒，
模块最核心的能力根本配不出来（第一版就是这么写的，测试直接打回）。

PG 的部分唯一索引跨不了两张表（范围键在 policy、仓在挂仓表），因此改由两道
`DEFERRABLE INITIALLY DEFERRED` 约束守住：同仓区间不得重叠 + 同仓至多一档 open。

**关键 trade-off**：延迟约束必须延迟到提交（换版是「先插新档、再收口旧档」，即时触发会误报），
而延迟到提交就意味着 `NEW` 的定型问题会在两张表之间暴露。

### 3. 「算不出来一律留 null + 写明原因，绝不拿 0 兜底」

这是全模块反复出现的同一条纪律，因为它保护的是两种不同的误读：

- 未登记销量 → `daysOfCover` / `suggestedQuantity` 留空 + `reviewNote` / `unquantifiedReason`。
  **0 天会被读成「今天断货」；0 件建议会被读成「不用补货」** —— 后者会让该补的那批直接过去，
  而且界面上看不出任何异常。
- 日均销量为 0 时天数也留空（除以零无意义），并与「未登记」用不同文案区分开。
- 销量为 0 本身是**合法登记**（某天确实没卖出去），不能当成缺数据。

### 4. 三环上游未交付 → capabilities 逐条显式报出

| 环 | 状态 | 本模块的做法 |
| --- | --- | --- |
| 日均销量（13 销量域） | 未交付 | 登记进来（`safety_stock_sales`，带 `source_system` 标来源），照 7.8.1.1 的 `allocation_shop_sales` 先例 |
| 分类 → SKU 展开（catalog） | 跨服务不可读 | 评估请求随 SKU 清单带分类上下文；清单为空时**只扫 SKU 档**，其余计入 `policies_not_scannable` 并如实报出 |
| 6.2.1.2 补货建议 | 未交付、属 06 采购域 | 建议落库、`external_purchase_reference` 留空，不建外键/视图/跨库写 |

四个 `*Available` 布尔位让「哪一环没通」成为**可读字段**，而不是让人从 404 或空数组里猜。

**为什么分类档不本地展开**：分类在 catalog 服务，本服务跨库读不到（AGENTS.md 模块边界纪律）。
在本地另建一张「分类 ↔ SKU」映射表就是复制一份真相（V56 文件头对店铺 ↔ 仓做过同样的论证并拒绝）。
所以配置按分类**完全可用**，只是**评估时**需要调用方把分类上下文带过来 —— 这个边界在
capabilities 与 `policiesNotScannable` 两处都写清楚了。

### 5. 跨服务键与槽位收紧后的额外一道闸

销量登记区间**不得重叠**（DB 触发器）。唯一约束只能挡住「完全相同的区间」，
挡不住 `[1,31]` 与 `[15,20]` 这种部分重叠 —— 两段都算进去日均销量会虚高，
可售天数算短，补货建议量偏大，而这条链路最终会变成一张采购单。
（V68 的 `allocation_shop_sales` 有同样形状，那里的偏差体现在分摊比例上、可事后对账；
这里会直接花钱，所以显式挡住。）

## 过程中踩到并修掉的坑（都是真 bug，不是笔误）

1. **一个挂仓表的延迟约束没触发** —— 第一版 `sku_label` 只在 Java 里读，DDL 里根本没建这一列，
   报错是 `column a.sku_label does not exist`。`policies_not_scannable` 同理。
   → 这类「模型/服务里有、迁移里没有」的漏项，只能靠真跑测试发现；
   顺手把 8 个 CHECK 约束**显式命名**了 —— 自动生成的 `safety_stock_policy_check3` 逼着人靠数序号
   反推是哪一条（编号既不看语义也不看书写顺序），报错里根本读不出是哪条约束。

2. **`NEW` 按触发表定型** —— 同一个触发函数挂到两张表上时，
   `CASE WHEN TG_TABLE_NAME=... THEN NEW.id ELSE NEW.policy_id END` 会在 SQL 里把**两个分支都定型**，
   运行时报 `record new has no field policy_id`。必须在 PL/pgSQL 里分支。
   区间触发器最终**只挂阈值表**（它比的是时间区间，用的都是 policy 自己的列），挂仓那一侧交给槽位约束。

3. **评估批次与预警的写入顺序** —— 预警的 `evaluation_id` 是外键指向评估批次，
   而批次计数在插入前一次算清（批次 append-only，不能插完再改），所以顺序只能是
   「先算 → 先落批次 → 再落预警与建议」。

4. **换版会被自己的旧档挡下** —— 槽位约束最初不检查「本档是否已收口」，
   于是换版提交时旧档（已 closed）把同仓的新档（open）当成了冲突。
   → 已收口的档不再参与裁决。

5. **jOOQ `Result.map()` 返回 `List` 不是 `Stream`**，`sum()` 返回 `numeric` 不是 `Long`。

6. **前端 `??` 不管空串** —— 后端给 `label: ''` 时按 `??` 回退会得到空标签，改成 `?.trim()` 判定。

## 文件清单

**新增**
- `backend/services/warehouse/src/main/resources/db/migration/V114__warehouse_safety_stock.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/SafetyStockModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/SafetyStockService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/SafetyStockController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/SafetyStockIntegrationTest.java`
- `apps/web/src/components/inventory/safety-stock-model.ts`
- `apps/web/src/components/inventory/safety-stock-model.test.ts`
- `apps/web/src/components/inventory/safety-stock-workspace.tsx`
- `apps/web/src/app/inventory/safety-stock/page.tsx`

**改动**
- `apps/web/src/lib/api-proxy.ts` —— 追加 `safety-stock` 单支（单处 243 字符纯插入，零删除）
- `docs/product/capability-blueprint/module-progress.md` —— 仅 7.2.2.2 那一行的状态与证据两格
- `packages/api-contracts/**` —— codegen 产物，手改即破坏契约

## 影响面

- 端点 20 个操作 / 16 个路径，全部挂在 `/warehouse/inventory/safety-stock`。
- 权限码复用已注册的 `warehouse.inventory.read` / `.update`，**没有新起任何权限码**。
- 六张新表，全部 `tenant_id NOT NULL` + `ENABLE/FORCE RLS` + `GRANT` / `starcon_app REVOKE`。
- **零 ALTER 既有表**：只新增表与触发器函数，`warehouse` / `inventory_balance` / `sku_ref` /
  `sellable_policy` 一个字节都没动。
- 契约：新增 23 个 DTO，消失 0；codegen 未报任何跨服务重名冲突
  （30 个候选 record/enum 名开工前已全库 grep 过，0 冲突）。

## 后续待办

1. **阈值配置表单未做**：后端的新建 / 换版 / 作废端点完整可用，页面目前只提供读与流转动作
   （认领、处置、采纳、忽略），阈值只能经端点配。这是界面的欠账，不是能力的欠账。
2. **分类档评估依赖调用方带分类上下文**：本服务无法本地展开（catalog 跨服务）。
   工作台接入时应由调用方按 SKU 清单一并提供；若日后 catalog 提供授权查询，可改为服务端自取。
3. **未登记进导航**：与 7.8.1.1 / 7.8.1.2 一致（那两条也不在 nav 里），本轮保持一致。
4. **1 工作台预警 / 6.2.1.2 交付后应由它们来读本模块**：本模块不代它们实现推送或回写采购单。
5. `policiesNotScannable` 的语义值得在其他含分类维度的模块复用：**「没扫」与「扫了没风险」
   必须长得不一样**，否则漏扫会被读成安全。
