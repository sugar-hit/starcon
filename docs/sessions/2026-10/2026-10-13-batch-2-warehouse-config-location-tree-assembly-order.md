# 组批 #2 交付说明 —— 仓库配置 / 库位树 / 组装单

> 本批是「组批模式」的第 2 批：回收 3 条此前卡在 `conflict` 的车道，
> 逐条解冲突后并入集成头 `integration/module-dev`，再在集成头上跑**一次**全量验证（L2 批次闸）。
> 车道基线是 `6828689`，集成头并入前是 `363dc00`。

## 本批的特殊之处：三条车道都撞在同一行上

三条车道（8.1.1.3 / 8.1.2.1 / 8.4.1.1）分别改了
`apps/web/src/lib/api-proxy.ts` 仓库白名单的**同一个分支**，
外加 8.1.2.1 还和集成头在 `route.test.ts` 的同两个 `it` 块上撞了车。

**根因不是谁写得不小心，是配置层面的必然**：
`.gitattributes` 只把 `packages/api-contracts/src/**`、`*openapi.json`、
`module-progress.md` 三类路径登记到 `merge=contract-union` 驱动器，
`api-proxy.ts` 不在其中，走的是 git 默认的行级三方合并；
而它的整个仓库白名单是**一行两千多字符的单行正则**，
行级合并的最小单位就是行 ⇒ 任何两条车道各自追加分支，必然撞同一行、必然冲突。

**解法**：按「顶层 alternation 分支」粒度做真正的三方求并，必要时**递归**下降。
合并器在 `.tmp/mergefix/union3.mjs`，它带 13 项 fixture 自测（分词器 + 递归求并）。
求并后还要过**行为级**验证，见下节。

**踩到的三个坑（都是探针自身的错，不是文件错）**：

1. **剥壳时复用 base 的配对括号下标** —— ours/theirs 在壳内多塞了分支，
   它们的 `)` 下标和 base 不一样，复用会把 `)` 切进内容里。必须三侧各自 `matchParen`。
2. **`indexOf('(?:')` 返回的是外层 `(` 的下标**，不是 `?` 的下标。
   头一次按「`?` 的位置」算，壳头与内容整体错位两格。
3. **剥离后的内容丢了壳自己的闭合括号** —— `head` 止于 `(?:`、`tail` 起于 `)` 之后，
   重组时必须把 `)` 补回去。

**一条设计决定：冲突分支选择「超集」而不是「二选一」**。
当一侧就地扩写了某个分支而另一侧仍保留 base 旧值时，旧值会被一并保留。
代价是这条行会随并入次数变长（冗余但不改变匹配结果）；
收益是**绝不会因为判断失误而丢掉一条真实路由** —— 丢路由远比冗余严重。

## 模块 8.1.1.3 仓库配置

**这个功能做什么**
运营能在仓库详情页里读全貌、改参数、查看下游影响与变更留痕，
并管理本仓的拣货班次（新建 / 改 / 停用）。

**技术落地**
- 迁移：`V104__warehouse_operating_config.sql`（warehouse，建三张表）
  —— **刻意不改 `warehouse` 表**：那里放的是仓库**身份**（编码/类型/货权法人/时区/地址/状态），
  这里放的是**作业行为**（批次/序列号/拣货策略/复核比例/拆零/班次）。
  两组字段混在一行，会导致「只想把复核比例从 0.05 调到 0.1」连带把地址五列写一遍，
  且「档案是谁改的」与「作业参数是谁改的」在审计上分不开。
  另有 `V104__warehouse_config_permission.sql`（identity，服务间各自编号不冲突）。
- 端点：`GET|PUT /warehouse/facilities/{id}/config`、
  `GET /warehouse/facilities/{id}/config/options|impacts|changes`、
  `POST /warehouse/facilities/{id}/config/shifts`、
  `PUT /warehouse/facilities/{id}/config/shifts/{shiftId}`
- 页面：`/warehouse/facilities/[id]/config` —— 配置读改、影响声明、
  变更留痕（真分页 + 范围与班次筛选）、班次管理；含错误态/空态/加载态
- 测试：`WarehouseConfigIntegrationTest`（845 行）+ `warehouse-config-model.test.ts`，
  含租户隔离三断言（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）

**关键设计决策**
- 刻意**没有 DELETE 段**：班次的出口是停用（`enabled=false`），配置的出口是改值。
  删配置行等于让仓库「退回没有配置」，而作业单已经在按那套参数执行。
  防线在后端（无 DELETE 端点，Spring 返回 405）与 V104 的表约束上。
- `options` / `impacts` / `changes` 是字面量子路径，**必须排在 `shifts` 之前判别**；
  `shifts/{shiftId}` 是两段 uuid，用 `\/` 分支匹配，否则会被单段 `config` 吃掉半截。
- 白名单是**求并**而非二选一：8.1.1.1 的 `facilities/options/capabilities`
  与 8.1.1.2 的 `{uuid}/locations/entrypoints` 一条都不许被这一支挤掉。

## 模块 8.1.2.1 库位树

**这个功能做什么**
运营能按树形浏览某个仓库的全部库位，一眼看到父子关系与占用，
并在树上新建库位、改属性、调整层级、删除空叶子。

**技术落地**
- 迁移：`V105__warehouse_location_tree.sql`（warehouse，**不建新表**）
  —— 只给 `warehouse_location` 补三列：`parent_id uuid`、`capacity numeric(19,6)`、
  `weight_limit numeric(19,6)`。补的正是 8.1.1.2 交付时点名归属本模块的
  「表上无 parent_id、无容量承重列」。
- 端点：`GET /warehouse/location-trees/capabilities`、
  `GET /warehouse/location-trees/{warehouseId}`、
  `POST /warehouse/location-trees/{warehouseId}/locations`、
  `PUT|DELETE /warehouse/location-trees/{warehouseId}/locations/{id}`
- 页面：库位树工作区
- 测试：`WarehouseLocationTreeIntegrationTest`（752 行）+ 白名单用例
  —— 断言四个形状放行，且「多一层后缀」仍 404 且**不发出请求**

**关键设计决策**
- 静态预检命中过 `ADD COLUMN`（无 DEFAULT），人工核对为**良性**：
  三列都是**可空**的（没写 NOT NULL），可空列不需要默认值，老 INSERT 不受影响。
  迁移注释里也点名记录了「不��任何既有列做 SET NOT NULL」的这条事故教训。
- CHECK 约束用 `capacity IS NULL OR capacity >= 0` 短路 ——
  存量行补列前没有容量概念，「没量过」与「量出来是负数」必须能分开。
- 后缀组**不带 `?`**：裸 `location-trees` 没有任何后端端点，
  带 `?` 会把它一并放行（实测会变成 503 而不是 404）。
- 刻意**不复用** `facilities/{id}/locations`：那是 8.1.1.2 已交付的库位**清单**
  （真分页 + 排序白名单），与本模块的**树**是两种形状，各起各的才不会撞方法签名。
- 发现 springdoc 丢弃自引用 `children` 字段，改用**扁平层级行模型**，树形在服务层组装 ——
  自引用 schema 在 OpenAPI 里表达不可靠。

## 模块 8.4.1.1 组装单

**这个功能做什么**
运营能对一组实物组件发起一次组装：组件出库、成品入库在**同一个本地事务**里完成，
组装单保留组件快照明细与账本分录，出问题能一路追到「哪几个组件变成了哪个成品」。

**技术落地**
- 迁移：`V103__warehouse_assembly_order.sql`（warehouse）
  —— `assembly_order` 组装单 + `assembly_order_component` 组件快照明细；
  全部 `tenant_id NOT NULL` + FORCE RLS + `GRANT warehouse_app`，
  序列 `assembly_order_code_seq` 一并授权
- 端点：`GET|POST /warehouse/assembly-orders`、
  `GET /warehouse/assembly-orders/capabilities`、
  `GET /warehouse/assembly-orders/{id}`、
  `POST /warehouse/assembly-orders/{id}/issue`、
  `POST /warehouse/assembly-orders/{id}/cancel`
- 页面：`/warehouse/assembly-orders` 组装单工作区
- 测试：`AssemblyOrderIntegrationTest`（877 行）+ `assembly-order-allowlist.test.ts`，
  含租户隔离三断言

**关键设计决策**
- 刻意**没有 PUT / DELETE**：组件明细是建单时按 BOM **冻结的快照**，改它等于改账的输入；
  而「把已完成的组装单删掉」更是不可能 —— 组件已出库、成品已入库，
  后端 V103 的终态守卫会拒。
- `capabilities` 是字面量子路径，必须排在单段 `{id}` 之前判别，
  否则会被 `[0-9a-f-]+` 分支漏掉。
- 虚拟套装不走这里：能力声明里显式说明边界，避免界面以为任何套装都能组装。

## 并入冲突的验证方法（可复用）

结构对账（分支集合、括号平衡、往返一致）**不够**。本批用的判据是三层：

1. **合并器 fixture 自测** —— 13 项，覆盖转义、字符类、空分支、不平衡抛错、
   同层两侧各追加、单侧扩写、递归剥壳、两层递归、超集语义。
2. **行为级差分探针** —— 直接调**真的** `proxy`，断言 HTTP 状态码：
   - 反向抽取 `apps/web/src` 的 184 条真实调用点，
     断言「合并前放行的，合并后仍放行」（差分，不是「184 条全放行」——
     抽取器会带进拼接残渣，直接断言会把噪音当成回归）
   - 断言本批三条车道新增的 15 个形状全部放行
   - 断言未登记形状**仍然 404**（否则「全部放行」也能让前两条全绿）
3. **探针标定** —— 报告「无丢失」之前，先证明探针对已知正确的输入会报错：
   注入删除 `assembly-orders` 分支 → 「新增形状」用例转红 5 条；
   注入删除 `transit-trackings` 分支 → 「不丢基线」用例转红 12 条。
   两次标定都成功转红，才把绿当证据。

**顺带修掉的一个真 bug**：`58ea346`（并入 8.1.2.1）按段求并 `route.test.ts` 时，
两侧的文档注释**共用同一个 `/**`** 作为共同上下文，
theirs 那一侧因此丢了开头，在该提交里留下一处语法错误。
已在 `b688fec` 补回。该提交尚未 push，集成头在 `b688fec` 处即为正确状态。

## 遗留的根因（未在本轮修）

`api-proxy.ts` 仍未纳入 union 驱动器覆盖。本轮的求并脚本是**一次性**的
（放在 `.tmp/mergefix/`），下一次撞同样的冲突仍然要手工重跑一遍。
结构性修法两条，建议择一：
- 把 `apps/web/src/lib/api-proxy.ts` 登记进 `merge=contract-union`，
  并给合并器补上「顶层 alternation 分支」这种键（**不能**直接登记 ——
  现在驱动器对无法解析语义的文件会走行级兜底并集，
  对这条单行正则会同时留下新旧两行，直接把文件写坏）。
- 或者把白名单改成「每条路由一行 + 由键派生的数组」，让行级合并重新可用。
---

## 追加：L2 第一次红了 —— 契约被静默改形状（本批最有价值的收获）

第一次跑 L2 时**后端全绿、前端 2217 例全绿、lint 全绿，只有 `typecheck` 红**：

```
warehouse.ts(435,13): error TS2300: Duplicate identifier 'ScopeOption'.
warehouse.ts(478,13): error TS2300: Duplicate identifier 'StatusOption'.
warehouse.ts(641,13): error TS2300: Duplicate identifier 'ScopeOption'.
warehouse.ts(647,13): error TS2300: Duplicate identifier 'StatusOption'.
warehouse-config-model.test.ts(51,7): error TS2353: 'code' does not exist in type 'ScopeOption'.
warehouse-config-workspace.tsx(522,36): error TS2339: Property 'code' does not exist on type 'ScopeOption'.
```

**vitest 不做类型检查**，所以这个损坏在任何「跑测试」的闸眼里都是绿的 ——
这正是组批模式把全量验证收上到集成头才能抓到的东西。

### 两个独立缺陷

**① 合并驱动器的语义分支从未被走到过（长期存在，非本批引入）**

`tools/module-progress/merge-union.mjs` 的 `TS_KEY` 写成 `/^export\s+.../ `，
**缺 `m` 标志**，而 `^` 在没有 `m` 时只锚定整段字符串的第 0 个字符。
契约是 codegen 产物，首行固定是 `// Generated by ... DO NOT EDIT.`，
所以 `TS_KEY.test(整文件)` **恒为 false** ——
按类型名求并的语义分支**从这套驱动器上线到现在一次都没走到过**，
每次契约并入都静默落到「行级去重并集」兜底，于是两条车道的同名不同形状行都留下了。

**为什么一直没被发现**：`merge-union: 走了行级兜底并集，请人工确认这个文件改对了`
这条 stderr **每并入一次都在打**，此前一直被当成例行噪音 ——
它其实每次都在报告「我没能按语义合并」。
**一条每次都出现的警告，要判断「每次出现」是不是它本来的语义，而不是因为看着眼熟就归入例行。**

**② springdoc 按 record 的简单类名建键，重名即覆盖（危害远大于重复标识符）**

| record | 文件 | 字段 | 服务的 schema |
|---|---|---|---|
| `ScopeOption` | `WarehouseConfigModels:323` | `(code, label)` | `ConfigOptions`（本批 8.1.1.3） |
| `ScopeOption` | `DutyTaxModels:457` | `(value, label)` | `DutyTaxCapabilities`、`AllocationCapabilities` |
| `StatusOption` | `WarehouseFacilityModels:258` | `(code, label)` | `FacilityOptions` |
| `StatusOption` | `ReservationModels:262` | `(code, label, terminal)` | `ReservationOptions`、`ExpiryAlertPage`、`SlowMovingPage`、`FirstMilePage` 等十余个 |

并入前 JSON 里 `ScopeOption = {value,label}`，并入后变成 `{code,label}` ——
**`DutyTaxCapabilities` / `AllocationCapabilities` 的字段被静默改了**。

**「重名覆盖」比「缺键」危险得多**：缺键会立刻报错，
重名覆盖会一路绿灯，直到某个前端读到错的字段才发现。

### 修法与命名取舍

只给**后到的那两个**加模块前缀，被覆盖的两个保持不动：

```
WarehouseConfigModels.ScopeOption     → WarehouseConfigScopeOption      (code,label)
WarehouseFacilityModels.StatusOption  → WarehouseFacilityStatusOption   (code,label)
DutyTaxModels.ScopeOption             → 不动  = {value,label}   ← 与并入前逐字一致
ReservationModels.StatusOption        → 不动  = {code,label,terminal} ← 与并入前逐字一致
```

理由：四个全改会让 ~15 个既有 schema 连带改名、还要动前端 import，
收益为零；而保留「并入前的主人」能让既有消费者的契约**逐字不变**，
对照检查才有一个干净的基线。

**顺带修好了一个更早的旧伤**：`FacilityOptions.statuses` 在 `363dc00` 基线里就指着
Reservation 的三字段形状（解引用后确认）—— 说明这个重名**比 8.1.1.3 还老**，
某个库位模块一直在对外发布错误的契约形状。本次是第一次真正修好它，
而不只是「恢复原状」。

### L2 重跑结果（修复后）

`./mvnw verify` BUILD SUCCESS（7 模块，6:58）· `pnpm typecheck` EXIT=0 ·
`pnpm lint` 绿 · `pnpm test` 117 文件 2217 例全绿 · Next production build 成功 ·
`sync.mjs --check` ✓ 台账与蓝图一致。

### 留给后续的同类风险（本批未修，如实记录）

1. **`Incoterm` 与 `WarehouseKind` 也是重名的简单类名，且枚举体不同**（7 vs 11 值、2 vs 5 值），
   分处 `DutyTaxModels`/`FirstMileShipmentModels` 与 `SellablePolicyModels`/`WarehouseFacilityModels`。
   当前**零影响**只因为没有任何 schema 引用它们 —— 但任何新端点一旦直接返回它们就会再次静默覆盖。
2. **codegen 的碰撞闸抓不到这一类缺陷**：`tools/codegen/catalog-contracts.py` 的 `collisions()`
   比较的是**跨服务**；服务**内**的覆盖发生在 springdoc 生成 spec 的时候，脚本根本看不到。
   要抓它需要在生成期扫描 `*Models.java` 的重复简单类名 —— 目前没有任何自动闸。
3. **求并器仍是临时的**：本轮的分支级求并脚本在 `.tmp/mergefix/union3.mjs`，
   `api-proxy.ts` 仍未纳入 union 驱动器覆盖，下次撞同样的冲突还要手工重跑。
