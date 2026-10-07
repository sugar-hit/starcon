# 10.4.1.2 在途跟踪 —— 车道中断与接手说明

日期：2026-10-07 23:25　车道：`feat/10-4-1-2`　worktree：`.worktrees/10.4.1.2`
迁移版本号：**V84**（已预分配，不要另找号）

## 状态：WIP，未 land

车道在 2026-10-07 一晚被 `net::ERR_NETWORK_CHANGED` 打断两次，现已把成果提交到车道分支，
但**集成测试仍有 9 例红**，因此**没有调用 `lane.mjs land`**。
集成头里目前**没有**在途跟踪这部分代码。

```
23ae211 wip(10.4.1.2): V84 迁移 + models + service（编译未收口）
9383522 wip(10.4.1.2): 收口编译 + 端点 + 集成测试骨架（集成测试仍有 9 例红，未验证全量）
```

接手时 worktree 是干净的，直接在原 worktree 续做即可，不要重新排车道。

## 已完成

- `V84__warehouse_in_transit_tracking.sql`
- `InTransitTrackingModels.java`
- `InTransitTrackingService.java`（编译已收口：25 错 → 0）
- `InTransitTrackingController.java`（13 个端点，`/warehouse/transit-trackings`，
  读 `inventory.read` / 写 `inventory.update`，租户来自 `AccessService` 而非请求头）
- `InTransitTrackingIntegrationTest.java`（18 例骨架，Testcontainers PG16）

## 未完成

1. **集成测试收口**（最高优先，见下）
2. 全量 `./mvnw verify`
3. 契约重生成
4. 前端页面 + 工作区
5. **api-proxy 白名单登记 `transit-trackings`**
6. 台账自己那行 + 会话日志
7. `lane.mjs land`

## 剩余 9 例红的成因（已定位，不要重新查一遍）

- `断言二`（伪造头断言 `$.length()==0`）与 `thresholds()` 的惰性 bootstrap 冲突 ——
  B 租户现在也会被补默认行，所以不再为空
- `端点_串通` 400
- `阈值_改一行就换口径`、`预警_按剩余窗口分级` 断言失败
- 3 处 `idOfShipment` / 事件 insert 的 NPE 与 SQL 错误

## 上一轮已经修掉的 4 个真 bug（别改回去）

1. **占位符与绑定参数个数不匹配**：`effectiveThreshold`（3 个 `?` 只传 2）、
   `updateThreshold` 的读（3 只传 1）与写（6 只传 5）都漏绑 `tenant_id`。
   **读阈值与改阈值两条路径原本全废。**
2. `create` / `recordEvent` 的 `timestamptz` 缺 `?::timestamptz`，传 `null` 时 PG 无法推断类型
3. `first_mile_tracking_event` insert 15 个 `?` 对 14 列
4. `rowOf` 逐行回查目的仓名（漏绑 `tenant_id` + N+1）→ 改为随主查询一次 join

## 两个容易踩的既有约束

1. **新增表名绝不能包含 `transit` 子串。**
   `InventoryInTransitIntegrationTest.java:1415` 断言全库匹配 `%transit%` 的表**只允许**
   `inventory_transfer` 与 `in_transit_event`。
   当前用的 `first_mile_tracking*` / `first_mile_delay_*` 是合规的。
2. **必须在既有 `inventory_transfer` 之上构建，不要另造平行的在途表。**
   上游 7.5.1.2 在途库存已交付（V63 + `InventoryInTransitService`），
   它才是外部在途腿的权威数据源。

## 已排除的怀疑

`StockOwnershipIntegrationTest` 的失败**与 V84 无关**：隔离单跑 `Tests run: 40, Failures: 0`，
且 Flyway 日志显示 `Successfully applied 30 migrations … now at version v84`。
之前报的「802 例里 1 例失败」是并发下的 flake，**不要再花时间查它**。

## 蓝图的硬要求

- **预警必须提前到预计到港前若干天**（头程延误是断货的主要外因）⇒ 阈值真实落库可配置，
  不能写死在代码里。当前 `thresholds()` 有 `ensureDefaultThreshold()` 惰性 bootstrap，
  否则全新租户读阈值是空列表、而看板会补行，两边口径不一致。
- 货代/船司/清关的**外部同步没有真实连接器**（10.4.2.1 未交付）：
  用状态机 + 审计 + `source_system`/`reference_id` 承载，**如实标注外部同步未接入**。