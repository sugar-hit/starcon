# 8.1.2.1 库位树（D3，L3 锚点 8.1.2，主责服务 warehouse）

车道：`feat/8-1-2-1`（worktree `.worktrees/8.1.2.1`），基线 `integration/module-dev` @ `6828689`。

## 结论摘要

蓝图 8.1.2.1「库位树」交付完成：给 `warehouse_location` 补 **parent_id / capacity / weight_limit** 三列，
出树用递归 CTE，占用来自 `stock_bucket` 实时聚合，防环与跨仓库挂父子**在 DB 层钉死**，
前端 `/warehouse/facilities/[id]/locations` 是真实工作区。

上游 8.1.1.2 已交付（V77），它的证据栏明文把「表上无 parent_id、无容量承重列」指向本模块 —— 这是本车道的活。

## ADD COLUMN 的安全论证（本批次最危险的一步）

`warehouse_location` 是**别的已交付车道正在读的表**（8.1.1.1 列表、8.1.1.2 详情与库位清单）。
仓库里有真实事故：某车道把列改成 `NOT NULL` 且无 `DEFAULT` 兜底，破坏了早先合入车道的 `INSERT`，
**各自单跑都绿，只有合并后才炸**。因此本迁移取最保守的形状：

1. 三列 `parent_id / capacity / weight_limit` 全部 **nullable 且无 `DEFAULT`**。
   PostgreSQL 对 nullable 无默认列的 `ADD COLUMN` 是**元数据操作，不重写表**、不长时持锁，
   其他车道的读查询不会被阻塞。
2. 全仓 **40 处** `insert into warehouse_location(...)`（`services/warehouse/src/test/**` 与各车道夹具）
   **全部显式列出列名**。不带列名的 `INSERT VALUES(...)` 才会因列数变化而报错，仓库里没有这种写法
   —— 所以既有 `INSERT` 不会违约。集成测试里有一例直接**不带列名** `INSERT`（`MINIMAL-INSERT`），
   把「没有 NOT NULL 兜底要求」钉成运行时事实而不是口头承诺。
3. **没有对任何既有列做 `SET NOT NULL`**；**没有收紧既有 `CHECK`**（`type` / `status` 词表一个字未改）。
4. 新增的两个 `CHECK`（容量、承重非负）都用 `IS NULL OR ...` 短路，对全部存量 `NULL` 行恒真。
5. 「必须有 parent」**不用 `NOT NULL`**：树根（顶层「区」）天然 `parent_id IS NULL`，
   而 `NOT NULL` 会立刻要求存量行全部有父值 —— 正是上面那条事故的形状。
   层级合法性交给触发器，数据类型上保持 nullable。
6. **刻意不重复 `ENABLE ROW LEVEL SECURITY`、也不重复 `GRANT`**：`warehouse_location` 的
   `tenant_id NOT NULL` + `ENABLE` + `FORCE` + isolation 策略由 V2 建立，新列自动继承同一张表的策略；
   `warehouse_app` 在 V2 已获该表的**表级** `SELECT/INSERT/UPDATE/DELETE`，表级权限自动覆盖新列。
   归属写错比不写更糟（与同服务 V77 同一理由）。集成测试仍逐张断言 `relforcerowsecurity` ——
   断言的是运行时事实，不是本迁移的归属。

## 两条蓝图红线如何落成 DB 约束

蓝图关系段：「**库位不能跨租户引用**，也不允许跨仓库」。

一条复合外键同时钉死两条：

```sql
FOREIGN KEY (tenant_id, warehouse_id, parent_id)
  REFERENCES warehouse_location (tenant_id, warehouse_id, id)
```

引用侧带 `tenant_id` 与 `warehouse_id`，所以 parent 属于别的租户或别的仓库都找不到匹配行。
`MATCH SIMPLE` 下 `parent_id IS NULL` 时该外键整体跳过，根节点照常插入。

**防环是 DB 不变量**（蓝图输入里点名「防环校验」），成环会让递归 CTE 永不收敛、整棵树查询打不出结果，
所以它不能只是服务层一个 `if`：

- `guard_warehouse_location_tree` —— `BEFORE INSERT OR UPDATE OF parent_id` 上用递归 CTE 沿 `parent` 链上行，
  走回 `NEW.id` 即成环则抛错（`23514`）；连带拒绝超过蓝图六层（区→巷→排→架→层→格）。
  递归深度带 32 的死循环保险丝：即便库里已有脏环，语句也会停在一个有限深度上而不是挂死。
- 集成测试用**超级用户连接直连 SQL**（绕开应用角色）证明两道都会拒 ——
  只做应用层检查的话，运维脚本与其它直连接口照样能挂出环或跨仓父子。
- 服务层仍先查一次，目的只是给出中文报错（「库位不能挂到它自己的子孙之下」）而不是让用户撞裸 `check_violation`。
  **两道都要**：权威判据是 DB。

## 踩到并修掉的坑

1. **`springdoc 会丢掉自引用的 record 属性。** 运行时树确实是嵌套的（前端按 `depth` 缩进渲染），
   但 `List<LocationNode> children` 在生成的 OpenAPI spec 里**整个字段消失**，codegen 出来的 TS 类型也没有它 ——
   前端按类型写了 `children` 却收不到。identity 的 `DepartmentView.children` 能进契约是因为它在 Controller 里
   被 springdoc 当作根类型扫描。**决策**：DTO 改成输出**扁平 `levels` + `depth`**（父先于子、同层按 code），
   契约 / 运行时 / 前端三者从此说同一件事，而不是让契约与运行时不一致。
2. **jOOQ 绑定无类型的 `null` 会抛**
   `SQLDialectNotSupportedException: Type class java.lang.Class is not supported in dialect POSTGRES`。
   根节点（`parent_id = null`）是**最常见的形状**，不是边角情况。解法：
   可空列在 SQL 里写 `cast(? as uuid)` / `cast(? as numeric)`，或用 `DSL.val(null, X.class)`。
3. **`sql.fetchValue(sql, X.class, args)` 这个重载在本仓库的 jOOQ 版本上会把 `Class` 当绑定值**。
   一律改用 `fetchOne(...)` + `get(0, ...)`。
4. **递归 CTE 的 `path` 列必须显式 `::text`**：非递归项是 `varchar(64)`，递归项里
   `t.path || '|' || c.code` 是 `text`，PG 要求两分支列类型一致。
5. **RAISE 的第二段字符串要拼进格式串**（`'…', arg` 之间不能插裸字符串字面量），
   否则编译报 `too many parameters specified for RAISE`。
6. **plpgsql 变量名会替换 SQL 里同名列引用**：触发器里的局部变量叫 `depth` 会与 CTE 的 `depth` 列冲突，
   报 `column reference "depth" is ambiguous` —— 改名为 `ancestor_depth`。
7. **api-proxy 白名单里裸 `location-trees` 的后缀组不能带 `?`**：带 `?` 会把裸路径一并放行
   （实测拿到 503 而不是 404）。反向用例专门钉了这条。
8. **子树口径的容量利用率恒为 `null`**：拿子节点手数除以上层「排」的容量没有物理含义，
   给一个没人能解释的百分比比不给更糟。逐节点的 `occupied.capacityUtilization` 才是真数。

## 文件清单

- `backend/services/warehouse/src/main/resources/db/migration/V105__warehouse_location_tree.sql`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseLocationTreeModels.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseLocationTreeService.java`（新）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseLocationTreeController.java`（新）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/WarehouseLocationTreeIntegrationTest.java`（新，25 例）
- `apps/web/src/components/warehouse/warehouse-location-tree-model.ts`（新）
- `apps/web/src/components/warehouse/warehouse-location-tree-model.test.ts`（新，10 例）
- `apps/web/src/components/warehouse/warehouse-location-tree-workspace.tsx`（新）
- `apps/web/src/app/warehouse/facilities/[id]/locations/page.tsx`（新）
- `apps/web/src/lib/api-proxy.ts`（**追加** location-trees 四个形状 + 注释）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（**追加**放行 + 反向拒绝用例）
- `packages/api-contracts/**`、`packages/sdk/**`（codegen 产物，只读，由脚本重新生成）
- `docs/product/capability-blueprint/module-progress.md`（**只改 8.1.2.1 那一行**的状态与证据两格）

## 验证

- L0：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` 3/3 ✅
- L1：`./mvnw -o -pl services/warehouse -am test` → **949 例，1 例失败**
- 该失败是 `StockOwnershipIntegrationTest.货权档优先级是sku大于法人大于仓库大于兜底`，
  **在集成头基线上同样失败**（把本车道改动 `git stash` 后跑全量复现，924 例同 1 例失败）。
  根因与本车道无关：规则行存 `now()` 的全精度时间戳，而用例用毫秒截断的 `now()` 去比较
  （`effective_from <= ?::timestamptz`），同一毫秒内落库时会漏命中。单跑该用例绿。
  `StockOwnershipService` 不读 `warehouse_location`。
- `WarehouseFacilityIntegrationTest` **29 例全绿**、`WarehouseDetailIntegrationTest` **31 例全绿**
  —— 两者都读本车道改动的那张表。
- 前端：`pnpm vitest run` 本车道两个测试文件 10 + 17 例全绿。

## 交接与遗留

- **8.1.2.2 库位自动分配**（兄弟模块，未开发）：本模块的 `capabilities` 如实报
  `autoAllocationAvailable=false` + `ownerModule=8.1.2.2`；拣货路径（8.3.2.1）同理。
  它要读的是 `capacity` / `weight_limit` / `pickable` / 占用，这些现在都有真实数据了。
- **删除权限码**：本车道去 identity 加迁移会与其他车道撞版本号，所以删除复用
  `warehouse.facilities.update`（identity 里不存在 `warehouse.facilities.delete`，用不存在的码会让 OWNER 也 403）。
- **导航未新增**：详情页与库位树页都挂在已实现任务页 `/warehouse/facilities` 之下，
  不改共享的 `nav-config.ts`（与 8.1.1.2 同一处理）。
- **`StockOwnership` 的时刻 flake 属于别人那条线的文件**，本车道按边界纪律没有改它，留待其归属车道处理。