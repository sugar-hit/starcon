# 2026-10-16 批次 14 · 配额中断恢复：平台回传 + 出库时效

> 本批次不是常规派发。两条车道的原 worker 于 **2026-10-16 14:48 因模型配额耗尽
> （错误码 `42212`）被强制中断**，成果只存在于 worktree 的未跟踪文件里，
> 分支上一个提交都没有。19:30 那一轮发现后做了取证、基线修复、接手、全量验证与推送。

## 恢复前的取证（决定了能不能救，且任一条不成立就不该动手）

| 取证项 | 结果 |
| --- | --- |
| 分支落后集成头 | 9.4.2.1 / 8.5.3.1 各落后 **13 个提交** |
| 这 13 个提交是否碰 trade / warehouse | **0 个文件**（全部是 catalog / channel / web / 契约 / 文档） |
| 滞留文件与集成头是否路径碰撞 | **0 处** |
| 滞留文件是否含 modified tracked | **0 个**（全是 `??`，`reset --hard` 不会丢） |
| V147 / V148 是否被别的车道占用 | **均未被占用** |

结论：`reset --hard integration/module-dev` 对两条车道都无冲突面、无成果损失风险。
执行后两条分支双向差异归零，7 + 5 个文件全部保住（约 4200 行）。

> ⚠️ **只读 `rev-list integration..branch == 0` 会得出「这是全新车道」的错误结论**——
> 陈旧分支同样满足它。基线新鲜度必须**两个方向都读**。

---

## 模块 9.4.2.1 平台回传（回传状态）

**这个功能做什么**：把发货信息（运单号、承运商、发货时间）回传给平台并跟踪结果，
让运营能看见「哪些单子平台还没认」、为什么没认、能不能补救。支持批量重试与人工修正。

**技术落地**：

- 迁移：`trade/V147__trade_shipment_writeback.sql` — **纯新建 4 张表，0 处 ALTER 既有表**，
  因此跨车道 DDL 回归面为零；四表均 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS。
- 端点：`/fulfillment/shipment-writeback/**` 共 12 个（capabilities / board / events /
  retry-batch / tasks 集合与建任务 / `{uuid}` 明细 / submit / outcome / verify / retry / corrections）。
  刻意**不放行** PUT 与 DELETE：凭据行 append-only，要更正走 corrections。
- 页面：`/orders/fulfillment/shipment-writeback`（workspace + model + 单测 + 白名单测试）。
- 测试：`ShipmentWritebackIntegrationTest` **26 例**；trade 服务全量 **665 例 0 失败**。

**关键设计决策**：

- **三态不可退化成两态**（蓝图加粗句「结果未知要先查证再重发」）—— 未知态必须先走
  `/verify` 才有资格谈重发，这条写进了闸门而不是注释。
- **人工修正新建一版而不是就地改**（append-only），幂等键随之变化。
- ⚠️ **接手时这段代码是「完整但严重损坏」的**：26 例里 **18 例失败**，包括**三条多租户断言全红**。
  根因之一是测试夹具把租户硬编码成 A —— 那些「隔离测试通过」实际测的是一个空集。
  修掉 8 个生产 bug（含 `INSERT ... VALUES ... FROM` 语法错误、参数顺序错、
  修正时先插后废撞 partial unique index）后才是当前的绿。

---

## 模块 8.5.3.1 出库时效

**这个功能做什么**：统计出库四个环节的耗时（订单到出库、拣货、打包、交接），
定位超时环节与瓶颈，给运营一张「哪里慢了」的账。

**技术落地**：

- 迁移：`warehouse/V148__outbound_timeliness.sql` — **两张新表，0 处 ALTER 既有表**，
  两表 ENABLE **与 FORCE** RLS；两个 FK 均留在 warehouse 库内。
- 端点：`/warehouse/outbound-timeliness/**` 4 个（capabilities / report / order-times / thresholds）。
- 页面：`/warehouse/outbound-timeliness`（workspace + model + 13 例单测）。
- 测试：`OutboundTimelinessIntegrationTest` **26 例**；warehouse 服务全量 **1485 例 0 失败**。

**关键设计决策**：

- **订单时刻属 trade，走本服务投影快照表，不跨库 JOIN** —— 已核实 `grep` 全服务零直接读 trade 表。
  时刻未到时该环节为 `null` 并计入 `missingSampleCounts`，**不回填假值**。
- ⚠️ **接手时 `report()` 这条唯一读路径从未成功执行过**：拼 SQL 时漏了 `SELECT` 子句、
  `WHERE` 用了 `FROM` 里根本不存在的别名 `t`、且跨租户 `warehouseId` 返回空报告而非 404
  （租户探测侧信道，与控制器自己的 javadoc 相矛盾）。三处编译期都看不出来。

---

## 本批次的验证与放行

| 闸 | 结果 |
| --- | --- |
| L2 后端 `./mvnw verify` | **BUILD SUCCESS**，4180 例 / 0 失败 / 0 错误（13:42） |
| L2 `pnpm typecheck --force` | exit 0，**0 cached**（绕过 turbo 跨 worktree 缓存） |
| L2 `pnpm lint --force` | exit 0，**0 cached** |
| L2 `pnpm exec vitest run` | **191 文件 / 3509 例全绿** |
| L2 Next production build | exit 0，两个新页面均在产物中 |
| `sync --check` | ✓ 台账与蓝图一致（465 行 / 0 重复） |

**并入后用例数对照**：`api-proxy-union.test.ts` 基线 **239** → 并入后 **246**（+7，零丢失）。
`merge-ready` 对该文件报了「行级兜底并集」，故单独核对——这类并集最危险的失败形态是
**测试文件能编译但收集到 0 个用例**，CI 变绿而它已不再检查任何东西。

## 已知缺口（如实记录，未用空列表冒充）

1. **9.4.2.1 改了一处公开契约**：`create()` 在「无记录可见」时由 `200 + unresolvedRecords`
   改为 `404`。继承代码里两个测试互相矛盾，选择 404 的理由是 200 会让伪造 tenant header
   的调用方误读为成功。若要回退只需改一个方法 + 一个测试。
2. **9.4.2.1 有一道守卫是死代码**：「回执凭据」校验永远触发不到（DB 约束已禁止其依赖的状态），
   已保留并标注，未删除。
3. **8.5.3.1 的 `order-to-outbound` 在 trade 未交付订单时刻前为 `null`** —— 设计如此，
   但意味着该环节目前生产上无数据。
4. **两个新页面都没有视觉 / E2E 验证**（无 Playwright、无截图），运行时行为未经验证。
5. **8.5.3.1 的 report 是 N+1 查询**，宽时间范围会慢；未超范围重构。
