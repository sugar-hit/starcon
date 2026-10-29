# 跨模块 TODO · 「XX 未交付」声明在兄弟模块交付后没人回改（存量欠账 ~300 处）

> 发现时间：2026-10-26 · 流水线 round-104 / batch-103
> 发现人：调度者（starcon 并行车道流水线）
> 状态：**未指派** —— 需要一次专门的巡检任务，不属于任何单条车道

## 一、现象

各服务的 `capabilities()` 响应里有一个 `boundaries` 字段（前端直接渲染成「这里没有 / 该去哪找」的提示），
里面逐条列出**相邻模块**的边界声明，并带一个状态标记。

问题是：**相邻模块交付了，声明没人回改**，于是用户从接口读到的���假话。

本轮实测到的 5 处（`identity` 服务，已在 batch-104 随 1.3.2.2 车道修掉）：

| 文件 | 原文 | 真相 |
| --- | --- | --- |
| `NotificationInboxController.java` | `1.3.1.2 通知偏好（接收渠道 / 免打扰时段）未交付` | 已交付 |
| `NotificationInboxController.java` | `1.3.2.1 预警规则未交付` | 已交付（batch-99） |
| `NotificationInboxController.java` | `1.3.2.2 外部渠道推送未交付` | 已交付（batch-104） |
| `NotificationPreferenceService.java` | `1.3.2.1 预警规则未交付` | 已交付（batch-99） |
| `NotificationPreferenceService.java` | `1.3.2.2 外部渠道推送未交付（…）` | 已交付（batch-104） |

## 二、规模（调度者全仓扫描）

扫描 `backend/services/*/src/main/java/**/*.java` 里所有含「未交付」的字符串字面量，
与台账「已交付」集合求交 ⇒ **约 300 处命中**，散布在 catalog / channel / crm / finance /
identity / platform / reporting / trade / warehouse 九个服务里。

命中密度最高的几个文件（抽样）：
`ProfitCalculatorService`（13 处）、`ReturnReceiptService`（8）、`WarehouseDetailService`（8）、
`HandoverBatchService`（7）、`InventoryBucketService`（6）、`ListingMigrationService`（6）、
`PriceAnomalyService`（6）、`PriceAdjustmentService`（5）。

## 三、为什么不能靠「每条车道顺手改」解决

1. **责任边界不匹配**：声明在 A 模块的文件里，但被证伪的是 B 模块的交付 ⇒ A 的车道没有理由碰它，
   B 的车道又不该改 A 的文件。本轮 1.3.2.2 车道之所以动手，是因为
   **它自己的交付直接证伪了同一份列表里的两条**，属于「自己造的谣自己擦」。
2. **合并冲突面**：这些文件大多已被多条车道改过（共享累积型文件），
   一次全仓巡检会产生大量跨车道的 diff 冲突。
3. **验证成本**：一次巡检要重跑九个服务的后端测试 ≈ 2 小时，等价于一整轮 L2。

## 四、建议的处置（供人工拍板）

| 方案 | 说明 | 代价 |
| --- | --- | --- |
| **A. 一次性巡检车道**（推荐） | 派一条**跨模块**车道（不走 L4 模块，单独授权），按服务分组把全部约 300 处声明与台账对齐；一次 L2 全量验证 | 一条车道名额 + 约 2 小时 L2 |
| B. 扫描脚本 + 人工抽查 | 写一个静态检查脚本（比对代码里的模块 ID + 「未交付」与台账状态），接进 L2 闸：**命中就当场点名**，不自动改 | 脚本本身便宜；但会持续产生红，需要有人跟 |
| C. 随到随改 | 每条车道顺手改邻居的声明 | 本轮的做法；能擦掉一部分，但覆盖不全，且责任边界模糊 |

**推荐 A + B 组合**：先用方案 B 的扫描脚本把问题变成「L2 闸上可见的红」，
再由方案 A 的一次巡检车道批量清理；此后脚本长期守住不再复发。

## 五、扫描脚本可以复用的判据（供实现参考）

```
对 backend/services/*/src/main/java/**/*.java：
  取所有含「未交付 / 未接通 / 尚未交付」的字符串字面量
  → 抽出其中的 L4 模块 ID（形如 d+.d+.d+.d+）
  → 查台账 module-progress.md 该行的状态
  → 状态 == 已交付 ⇒ 命中（声明与台账矛盾）
```

⚠️ 注意：不能只匹配「未交付」三个字 —— 同一个字面量里可能出现**别的**模块 ID
（例：「本模块负责 X，Y 未交付」里的 Y 与 X 无关），
必须逐条**按模块 ID** 判定，否则误报会淹没真命中。

## 六、关联

- 本轮修掉的 5 处：commit `f94d7d49`（在 `feat/1-3-2-2` 车道分支上，随 1.3.2.2 一起并入）
- 本轮会话说明：`docs/sessions/2026-10/2026-10-26-batch-103-l2-gate-and-two-lane-redispatch.md` 第十一节
