# 8.1.1.3 仓库配置 —— 工作时段与班次落地，变更留痕做成 append-only

日期：2026-10-13
车道：`.worktrees/8.1.1.3`（分支 `feat/8-1-1-3`）
后端主责服务：warehouse

## 决策摘要

**这是 8.1.1 下第一个真正写数据的模块。** 8.1.1.1（列表）与 8.1.1.2（详情）都刻意不建新表
（纯聚合视图，理由是「存一份列表数据就意味着列表上的数与 warehouse 表上的数会有两个真相来源」）。
本模块打破那条纪律，但理由与它们不同，也写进了迁移文件头：

- 蓝图 8.1.1.3 的六个参数**不在 warehouse 表上**。warehouse 表放的是仓库的**身份**
  （编码 / 类型 / 货权法人 / 时区 / 地址 / 状态），改错了是主数据事故；
  这里放的是**作业行为**，改错了直接影响 8.3 作业方式。
- 混在一行的两个具体代价：一次 UPDATE 改不动单个维度（「只想把复核比例从 0.05 调到 0.1」
  会连带把地址五列写一遍，写进去的是读取时看到的旧值）；
  以及「档案是谁改的」与「作业参数是谁改的」在审计上是两件事，混在一行里分不开。

**留痕不用分档，用 append-only。** V68 的 allocation_policy 用
`effective_from / effective_to` 分档，本模块刻意不用：仓库行为参数是**当前态**
（作业单读当前态），读历史区间只会让每个作业单多做一次解析。但蓝图同时要求
「修改需记录变更」，所以两件事分开 —— 当前态一行可改 + 留痕表只许追加。

**留痕粒度逐字段，前后值存规范化 text。** 不用 jsonb 也不用 typed 列：
审计查询几乎总是「这个仓最近改了什么」这种逐字段的读，parse 整份 JSON 才能取一个值太亏。
`old_value IS DISTINCT FROM new_value` 的 CHECK 让「没变」不写行 ——
否则「这个仓改过几次」会被无意义的提交灌水。

**不可篡改两道防线。** `REVOKE UPDATE, DELETE`（权限层，只给 SELECT/INSERT）

- `BEFORE UPDATE OR DELETE` 触发器（表 owner 与超级用户仍能改）。
  集成测试两条都实跑：运行角色报 `permission denied`，超级用户报触发器的 `append-only`。

## 关键 trade-off

| 取舍         | 选择                                                           | 理由                                                                                                                                                                                                             |
| ------------ | -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 权限码       | 新开 `warehouse.facilities.config`，不复用 8.1.1.2 的 `update` | 改地址填错是可恢复的录入事故；改拣货策略是**作业契约** —— 影响的是之后所有出库作业的执行方式，已按旧策略执行的部分不会改回来。且仓库主管与行政通常是不同岗位                                                     |
| 班次时刻类型 | `time`（当地墙上时刻），不是 `timestamptz`                     | 「早班 09:00-18:00」是每天重复的规则不是某个瞬间；存成 timestamptz 必须回答「09:00 是哪天的 09:00」而这个日期没有来源。**因此本模块没有 timestamptz 写入路径，也就不存在 `Timestamp.valueOf` 偏 −28800s 的风险** |
| 跨夜班       | 允许（start > end）                                            | 夜班是真实作业形态，拆成两条会让「22 点到次日 6 点」在数据层没有答案                                                                                                                                             |
| 班次删除     | 不提供 DELETE 端点，出口是停用 `enabled=false`                 | 删班次会让「上周三夜班是谁上的」失去载体 —— 留痕会指向一个不存在的班次                                                                                                                                           |
| 人员花名册   | **不建**                                                       | 蓝图措辞是「工作时段与班次」，说的是时间不是人。人员档案的真相来源在 identity（成员关系），本服务再建一份就是造第二个真相来源（与 V1 `legal_entity_ref` 纪律相反）                                               |
| 未配置态     | 返回默认值 + `unconfigured=true`，**不自动建行**               | 自动建行会让「复核比例是 0」与「还没配过」在界面上分不开，而后者意味着作业正按默认值跑                                                                                                                           |
| 比例存储     | `NUMERIC(9,6)` + 入库前归一到 6 位小数                         | 0.1 在二进制浮点里是 0.1000000000000000055；归一让「同一个比例」在留痕里只有一个写法                                                                                                                             |
| 配置全貌     | 一个端点返回配置 + 班次 + 留痕 + 影响声明                      | 四请求会让页面在数据版本不一致时把「班次改过、配置没改」拼在一起显示                                                                                                                                             |

## 上游依赖实查（按实查为准，与简报无出入）

- `warehouse` 表：V1 建立，`tenant_id NOT NULL` + ENABLE/FORCE RLS；V69 放宽 `kind`（五值含 BONDED）；V77 补地址五列（**全部 nullable 无 DEFAULT**）。三条都实查存在。
- `warehouse_work_task`（V74 / 8.5.1.1）：带 `warehouse_id` 与 `operator_user_id`，**但没有人员花名册表**。
- identity：V69 已注册 `warehouse.facilities` 的 `page_resource` 与 `read` / `create`，V77 已补 `update`。
- **无 BLOCKED。**

## 兑现的交接

三条交接都做了：

1. **identity V69 文件头**：「刻意只注册 read + create，不注册 update/delete。
   改仓库属性属 8.1.1.2 详情、行为参数属 8.1.1.3 配置，两条车道落地时各自补自己的码。」
   → V77 兑现前半句，本车道 V104 兑现后半句。
2. **8.1.1.2 的 `rosterAvailable=false`**：理由写的是「工作时段与班次属 8.1.1.3 配置」，
   实查确认该表不存在 → 本迁移建 `warehouse_shift` 补上。
3. **8.1.1.2 的 route.test.ts 拒绝清单**里写着 `/{id}/config`「属 8.1.1.3、尚未交付」
   → 本车道把它**移进放行清单**。不挪的话前端一访问就 404。
   这是唯一一处改到别人模块的测试文件，改动范围仅限那一条断言 + 它的注释。

## 文件清单

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V104__warehouse_operating_config.sql`（三张表 + 触发器 + GRANT）
- `backend/services/identity/src/main/resources/db/migration/V104__warehouse_config_permission.sql`（与 warehouse 同取预分配 V104，是仓库既有先例，V69 就发生过）

**后端**

- `WarehouseConfigModels.java`（PickingStrategy 三值枚举 / WarehouseConfig 带 unconfigured / ShiftRow / ConfigChange / ConfigImpact）
- `WarehouseConfigService.java`
- `WarehouseConfigController.java`（**独立文件**，不并进 `WarehouseFacilityController` / `WarehouseDetailController`）
- `WarehouseConfigIntegrationTest.java`（36 例）

**前端**

- `apps/web/src/components/warehouse/warehouse-config-model.ts` + `.test.ts`（19 例）
- `apps/web/src/components/warehouse/warehouse-config-workspace.tsx`
- `apps/web/src/app/warehouse/facilities/[id]/config/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（**只追加** config 一支）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行 3 例 + 反向拒绝 1 例）

**契约**（codegen 产物，未手改）

- `packages/api-contracts/src/contracts/warehouse.ts` + `warehouse.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（**只改 8.1.1.3 那一行的状态与证据两格**）

## 后续待办（不属本车道）

- 8.1.1.2 的 `WarehouseDetailService.labor()` 里 `rosterAvailable=false` 现在**过时**了 ——
  班次表已落地，且那边还有一个 `FBA 备货策略 ownerModule='8.1.1.3'`。
  **刻意没动**：那是 8.1.1.2 的端点文件，翻转它要同时改它们的 DTO 语义
  （rosterNote / equipmentNote 的措辞），本车道不越界改别人模块的端点。
  调度器可决定由谁翻。
- 8.3.2 拣货 / 8.3.3 复核 / 8.2.1.1 批次 / 7.1.1.2 序列号 / 8.3.4 拆零五条影响声明
  现在是 `available=false`，各条 `note` 里写清了缺什么。**配置已落库并留痕，下游接上后直接读当前态。**

## 验证

```
cd backend && ./mvnw -q -o compile                                  # L0 编译闸
cd backend && ./mvnw -o -pl services/warehouse,services/identity -am test   # L1 模块闸
cd . && pnpm typecheck
cd . && pnpm vitest run "apps/web/src/app/api/warehouse/[...path]/route.test.ts"
                     "apps/web/src/components/warehouse/warehouse-config-model.test.ts"
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```

未跑 `./mvnw verify`（调度器在集成头上每批只跑一次）与 `pnpm --filter @starcon/web build`
（按调度要求）。
