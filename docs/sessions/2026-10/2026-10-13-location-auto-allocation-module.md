# 8.1.2.2 库位自动分配（阶段 D3，L3 锚点 8.1.2）

日期：2026-10-13 ｜ 分支 `feat/8-1-2-2` ｜ 基线 `integration/module-dev` @ `6273501`
迁移版本：**V106**（预分配，未自行找最大版本号）

## 决策摘要

### 1. 只建一张新表，且推荐不落库

蓝图输出是「推荐库位列表（带推荐理由）」，而下游 8.2.2.1 上架任务**未交付**。
所以：**推荐按需算（Service 层 jOOQ 显式 SQL），落库的只有人真正选定的那一个库位**。
把每次推荐的候选都存成行，用户点两下就能刷出几百行没有任何下游消费的垃圾数据。

`location_allocation` 存的是**决策 + 决策当时的规则快照**（`required_type` /
`source_distance` / `affinity_skus` / `rule_summary`）。库位占用会随作业变化，事后再算
「当初为什么选它」已经算不出来。边界写死在文件头：**排序与过滤永远读当前事实**
（`warehouse_location` / `stock_bucket`），**只有「当时的理由」读快照**。

库位的属性与占用**不在本表各存一份** —— 它们属于 8.1.2.1 与 7.1.x 的事实表。

### 2. 硬规则进 DB，且混放独立判据

蓝图红线「残次品入残次库位是硬规则，不允许混放」。做成触发器
`guard_location_allocation_placement`，绕开服务层用超级用户直连 SQL 也照样拒。

**混放是独立于类型再查一次当前实际存放的 bucket** —— 因为库位类型是 8.1.2.1 允许改的，
只靠「类型对得上」兜不住「类型被人改过之后混放已经发生」。

质量状态→库位类型写成 SQL 函数 `location_allocation_required_type(role)`，
而不是把 CASE 抄进触发器与应用层：规则只有一个定义，Java 侧只负责给可读的中文报错，
**权威判据是 DB**。测试逐条断言两侧一致。

### 3. 三条规则各有一个诚实的口径，全部写进 capabilities

| 蓝图词         | 本服务实际口径                       | 为什么不是字面值                                                                                                                                                                               |
| -------------- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 同品类聚类     | `stock_bucket` 的**真实共置关系**    | catalog 类目树 3.3.1.1 未开发，且 AGENTS.md 禁止把上游权威列复制成本地列（`sku_ref` 明文不存类目）。这是本服务内可得的真信号，但**不等同于 catalog 类目**，故 `catalogCategoryAvailable=false` |
| 路径距离       | 库位树跳数（V105 的 `parent_id` 树） | 真巷道路径图归 8.3.2.1。距离 = depth(src)+depth(dst)−2×LCA，LCA 层号用**路径共有段数**求（库位编码仓内 UNIQUE）                                                                                |
| 容量与承重匹配 | 只核算**本模块未取消决策已承诺的量** | `capacity` 是体积单位而 `stock_bucket.on_hand` 是件数；本服务没有「每个 SKU 占多少体积」的真相来源，拿件数凑利用率是**单位错配**，故 `stockVolumeAvailable=false`                              |

另外三类库位（`RECEIVING` / `SHIPPING` / `FROZEN`）**刻意不是任何自动分配的目标**：
前两个是货还没上架或拣完待发的中转位，冻结位是「这批货不许动」。

### 4. 「判不了」与「通过」分开建模

硬规则**做过滤**（不满足就不进候选），排序规则**做打分**。
`score = 各条理由权重之和` —— `reasonCodes` 带 weight，所以「为什么它排第一」
逐条加起来就是那个 score，不需要额外解释一个算不出来的数字。

无法判定的规则（库位没量过容量 / 调用方没给需求容量）给 **0 分 + 一条
`CAPACITY_UNKNOWN` / `WEIGHT_UNKNOWN` 说明**，并在 `capacityEvaluated=false`
上如实标出。把「没量过」按「够」处理才是把不确定性伪装成结论。
而**量出超容的库位根本不进候选** —— 装不下不是「差一点的推荐」，是错的答案。

## 关键 trade-off

- **命名空间新起 `/warehouse/location-allocations`**，不复用 8.1.2.1 的
  `/warehouse/location-trees`（维护库位档案）、8.1.1.2 的 `/facilities/{id}/locations`
  （扁平分页清单）、7.x 的库存 allocation（数量分配给订单）。三者主语完全不同，
  混用会让「上架分配」与「库存分配」在接口上分不清。代价：导航里多一个条目。
- **权限码复用 `warehouse.facilities.read/.update`**，与 8.1.2.1 同一处置。
  不注册 delete：撤销走 `.update`，且撤销是一等动作（带原因、可重新指派），不是删数据。
  代价：看板上「能改库位的人」也能指派上架位 —— 但这两件事的风险面本来就接近。
- **按行推荐（N 行 = N 条查询）**。各收货行的质量状态不同 → 目标库位类型不同，
  一次查询跨不了行类型。每行一条查询不是 N×M，可接受。
- **容量口径偏保守**。宁可不判（`evaluated=false`）也不拿件数凑一个假的利用率 ——
  后者会让人拿它去排库位。

## 踩坑记录（留给下一批）

### plpgsql 的 `RAISE` 参数必须与格式串**同一行**

```sql
-- 报错：syntax error at or near "NEW.quality_status_id"
RAISE EXCEPTION '质量状态 % 在本租户不存在'
  NEW.quality_status_id USING ERRCODE='23514';

-- 正确：参数与格式串同一行
RAISE EXCEPTION '质量状态 % 在本租户不存在', NEW.quality_status_id USING ERRCODE='23514';

-- 或者按 V105 的写法用字面量续行（下一行先以字符串字面量开头）
RAISE EXCEPTION '质量状态 % 在本租户不存在'
  '（不能凭空指派）', NEW.quality_status_id USING ERRCODE='23514';
```

格式串后一旦换行，plpgsql 认为 RAISE 已结束，再下一行的参数被解析成新语句。
本地变量（`v_role`）也一样会踩。

**定位手法**：这类报错在 surefire 报告里被截断成 `ERROR: sy`，
直接起一个 `postgres:16-alpine` 容器按序灌全部迁移能拿到完整错误文本，
比在报告里猜快得多。

### 其他三个

- **PG 报错文本不含 SQLSTATE**：想断言 `23514` 要取 `SQLException.getSQLState()`，
  在 message 里 `contains("23514")` 永远不成立（文本是中文业务文案）。
- **`OffsetDateTime.compareTo` 偏移量不同时先比本地日历时间**：断言时间区间必须
  比 `toInstant()`，否则 PG 读回的时区与用例的 `+08:00` 不同就会假失败。
- **`numeric(19,6)` 读回是 `"0.300000"`**：比 0.3 多一位有效位，不是少。
  断言要用 `isEqualByComparingTo`，别用字符串相等。

## 文件清单

### 新增

| 文件                                                                                                      | 说明                                                              |
| --------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `backend/services/warehouse/src/main/resources/db/migration/V106__warehouse_location_auto_allocation.sql` | 一张新表 + 三值映射函数 + 硬规则触发器 + 部分唯一索引 + RLS/GRANT |
| `backend/.../warehouse/LocationAllocationModels.java`                                                     | 11 个契约类型，**全部带 `LocationAllocation` 前缀**               |
| `backend/.../warehouse/LocationAllocationService.java`                                                    | 推荐 / 确认 / 释放 / 撤销 / 清单，jOOQ 显式 SQL                   |
| `backend/.../warehouse/LocationAllocationController.java`                                                 | 6 个端点                                                          |
| `backend/.../warehouse/LocationAllocationIntegrationTest.java`                                            | 28 例，Testcontainers PG16                                        |
| `apps/web/src/components/warehouse/warehouse-location-allocation-model.ts`                                | 前端模型（纯函数）                                                |
| `apps/web/src/components/warehouse/warehouse-location-allocation-workspace.tsx`                           | 真实工作区                                                        |
| `apps/web/src/components/warehouse/warehouse-location-allocation-model.test.ts`                           | 11 例                                                             |
| `apps/web/src/app/warehouse/location-allocations/page.tsx`                                                | 路由                                                              |

### 修改

| 文件                                                     | 改动                                                                       |
| -------------------------------------------------------- | -------------------------------------------------------------------------- |
| `apps/web/src/lib/api-proxy.ts`                          | **追加** `location-allocations` 七个形状（只加自己那一支，未重排整条正则） |
| `apps/web/src/app/api/warehouse/[...path]/route.test.ts` | +2 例（放行 + 8 条反向拒绝）；修正一条过时注释                             |
| `packages/ui/src/lib/task-pages.ts`                      | 登记一条任务页                                                             |
| `packages/api-contracts/src/contracts/warehouse.ts`      | codegen 产物，**+11/−0 纯新增**                                            |
| `packages/api-contracts/src/warehouse.openapi.json`      | codegen 产物                                                               |
| `docs/product/capability-blueprint/module-progress.md`   | 只改 8.1.2.2 那一行的「状态」「证据」                                      |

### 声明的 record 名与重名扫描

扫描命令与结果（`grep -rnE "(record|class|enum|interface)[[:space:]]+LocationAllocation" backend/ --include=*.java`）
**零命中**，同服务既有裸名 `LocationType` / `LocationRow` / `LocationTotals` /
`LocationTree` / `AllocationCapabilities` 等一个都没复用。

声明的 11 个契约类型：
`LocationAllocationCapabilities` / `LocationAllocationRecommendInput` /
`LocationAllocationLinePlan` / `LocationAllocationCandidate` / `LocationAllocationReason` /
`LocationAllocationConfirmInput` / `LocationAllocationCancelInput` /
`LocationAllocationSaved` / `LocationAllocationDecision` / `LocationAllocationPage` /
`LocationAllocationTotals`

打分器 `ScoreCard` 刻意做成 **Service 内部私有类**而不是 Models 里的 record：
它不进任何响应体，springdoc 不会扫它，也就没有撞名风险。

## 验证

| 闸      | 命令                                                     | 结果                                |
| ------- | -------------------------------------------------------- | ----------------------------------- |
| L0 编译 | `./mvnw -q compile`（backend）                           | 通过                                |
| L0 类型 | `pnpm typecheck`                                         | **3/3 成功**                        |
| L1 模块 | `./mvnw -pl services/warehouse -am test`                 | **1069 例，0 失败 0 错误**          |
| 前端    | `pnpm vitest run`（本模块 + route）                      | **35 例通过**（11 + 24）            |
| 契约    | `catalog-contracts.py --spec-dir backend/target/openapi` | 1521 DTO，`warehouse.ts` **+11/−0** |

## 后续待办

- **8.2.2.1 上架任务**：消费本模块 `status='RELEASED'` 的决策行，并真正移动
  `stock_bucket` 的 `location_id`（蓝图：「上架只改位置不改数量」）。
- **8.3.2.1 拣货路径**：交付后把「库位树跳数」换成真实路径图距离；
  `capabilities.routingGraphAvailable` 翻 true。
- **catalog 3.3.1.1 类目树**：接入后把共置聚类换成真类目；
  `capabilities.catalogCategoryAvailable` 翻 true。
- **既有库存的体积口径**：需要 SKU 级体积/重量事实（属 catalog），
  否则容量匹配永远只能覆盖本模块承诺量。本模块**不**为此加列 ——
  加一个没人维护的 `occupied_volume` 会变成一个永远是 0 的假字段。
- `goods_receipt_event.location_id` 是上架路径的**唯一起点**。若将来允许
  「无实收记录直接指派」，需要给这条约束一个明确的例外，而不是放宽。
