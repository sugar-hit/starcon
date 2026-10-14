# 2026-10-14 8.2.2.1 上架任务

> 车道 `feat/8-2-2-1`，worktree `.worktrees/8.2.2.1`，基线 `integration/module-dev` @ `bddb48d`，Flyway `V109`。

## 决策摘要

蓝图 08 卷 8.2.2.1 的四问很短，但关系段那句加粗**「上架只改位置不改数量，数量在收货时已确定」**
就是整个模块的技术内容。本车道把它当成唯一的设计轴，其余选择都是它的推论。

**1. 加粗句在数据模型上的落点是「分层」，不是「加一列」。**
`inventory_balance` 的键**刻意不含 `location_id`**（V2 文件头已把这条写死），所以
「同一批货从 A 库位挪到 B 库位」在 balance 上**根本不是一个可表达的事件**。
它只能落成**同一 balance 行上两条反向分录**（源位 -Q、目标位 +Q），代数和必须恰好为 0。
一次扫码 = 一对分录；一张任务 = 一条 `inventory_transaction`（`ref_type=PUTAWAY_TASK`）。

**2. 三条不变量都放在数据库层，且刻意带回账本分录。**

| | 内容 | 为什么不够/为什么够 |
|---|---|---|
| P1 | 该任务那条 transaction 的全部分录 `on_hand_delta` 之和 **= 0** | 抓「有人塞了不成对的分录」 |
| P2 | 完成时每行 `putaway_quantity` **恰好 =** `planned_quantity` | 抓「半张单说完成」 |
| P3 | 账上负向分录绝对值之和 **=** 明细实上架量 | 抓「少搬」 |

P3 独立于 P1 不是冗余：源位减 90、目标位加 90 时 **P1 照样成立**，只有核对「搬了多少」才抓得住。
三条都靠 `guard_putaway_task()` 在转 COMPLETED 时**回读 `inventory_entry`**裁决，
所以绕过服务层直连 SQL 也绕不过 —— 测试里有三条专门的「绕过服务层」用例。

**3. 数量不是入参。** `PutawayTaskLineInput` 里**没有数量字段**。应上架量取自
`goods_receipt_line.received_quantity`，建单时冻结成 `planned_quantity`；收货方事后改单
不影响已建任务。收货时定错数量的出口是 8.2.1.2 收货差异，不是在上架单上改数。
界面形态就是**建单表单没有数量输入框**。

**4. 与 8.1.2.2 的分工：它负责算，本模块负责搬。** 不重写推荐算法、不复制推荐结果，
消费其已选定（`RELEASED`）的决策作 `allocationId` 追溯；人工直接指派库位同样合法
（蓝图原文是「库位推荐**或**指定库位」）。质量状态 → 库位类型的硬规则**复用 V106 的
`location_allocation_required_type(role)`**，不抄第二份 CASE。

## 关键 trade-off

**① `netOnHandDelta` 恒为 0，不是完成标志。**
第一版我以为「执行中的任务净额非 0」，测试直接把它打回来了 —— 因为一次扫码写的就是
一对反向分录，成对的两条加起来恒为 0，所以**任务没做完时净额同样是 0**。
P1 抓的是「不成对的分录」，做没做完看 P2/P3 与明细的 `putaway_quantity`。
前端与 `capabilities` 的文案都按这个口径改了，并在界面上明写「净额为 0 不代表任务做完了」。
*代价*：P1 单独看比较弱；*收益*：文案不再骗人。

**② 幂等必须单独立一道。** 同一个 `scan_reference` 重复提交撞部分唯一索引。
少了这道，**PDA 网络抖动重发会把同一批货搬两遍，而 P1 的净额仍然是 0** ——
这是「只改位置」这条不变量唯一抓不到的 bug 形态，所以不能靠 P1 兜。

**③ bucket 的 `last_movement_at` 只能单调，业务时刻另存。**
V2 的 `guard_bucket_movement_time` 要求该列严格前进。现实里两种合法情况会违反它：
事后补录（业务时刻早于上次）与 PDA 同秒连扫（时刻相等）。
所以服务层取「作业员填的时刻」与「原值 +1 微秒」中较晚的一个写进这个**缓存列**，
而**作业员填的业务时刻原样落在 `putaway_task_scan.scanned_at` 与
`inventory_transaction.occurred_at` 上** —— 测试断言的正是写回的那两个值。

**④ 目标位 bucket 一次 INSERT 建好，不走「先 0 再 UPDATE」。**
后者会让建行与搬入用同一个业务时刻、两次写的 `last_movement_at` 相等，被 V2 直接拒；
而且新建行已带 `on_hand = Q` 时再 UPDATE 一次会凭空多货（守恒触发器会在提交时掀掉事务）。

**⑤ 一行收货明细同时只能有一个未取消的上架任务。**
部分唯一索引的谓词里不能 join 子查询问任务状态，所以用 denormalize 的 `task_active`
派生列，唯一写入者是触发器（应用层写不进去）。顺带发现一个自己锁自己的死锁：
取消时要把明细的 `task_active` 置 false，而那一刻明细守卫看到的状态已经是 `CANCELLED` ——
守卫里为此开了一个**极窄的例外**（只有 `task_active` 变、数量与全部路由字段一个没动）。

**⑥ 不注册 identity 权限码。** 与 8.4.2.1 / 8.1.2.2 同一处置，本车道不碰 identity 迁移
（那里没有为本批预留版本号，擅自取号会与另外 3 条车道撞）。

**⑦ 一张任务一条 `inventory_transaction`。** 「每扫一次新建一条」会让这批货的完整上架账
散成 N 条互不相干的交易，P1 也就无从核对（净额只在单条交易内才说得清）。

## 文件清单

**新增（backend）**
- `backend/services/warehouse/src/main/resources/db/migration/V109__warehouse_putaway_task.sql`
  —— 三张表 + 三个派生量触发器 + 守卫触发器 + append-only + RLS/FORCE/GRANT
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/PutawayTaskModels.java`
- `.../PutawayTaskService.java`
- `.../PutawayTaskController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/PutawayTaskIntegrationTest.java`（33 例）

**新增（frontend）**
- `apps/web/src/components/warehouse/putaway-task-model.ts` / `-model.test.ts`（32 例）
- `apps/web/src/components/warehouse/putaway-task-workspace.tsx`
- `apps/web/src/app/warehouse/putaway-tasks/page.tsx`
- `apps/web/src/app/api/warehouse/putaway-task-allowlist.test.ts`（8 例，双向断言）

**修改（求并相关，务必注意）**
- `apps/web/src/lib/api-proxy.ts` —— **只在 `warehouse/` 那条正则尾部追加了一个顶层分支**
  `putaway-tasks`，**插在 `disassembly-orders` 之后（当前最末）**，未重排任何既有分支。
  下一轮求并按这个位置定位。
- `packages/api-contracts/src/contracts/warehouse.ts` + `warehouse.openapi.json` —— codegen 产物（723 → 743 类型，零丢失）

## 验证

| 闸 | 命令 | 结果 |
|---|---|---|
| L0 编译 | `cd backend && ./mvnw -q compile` | 绿 |
| L0 类型 | `pnpm typecheck` | 绿（3/3 tasks） |
| L1 后端 | `./mvnw -pl services/warehouse -am test` | **1183 例绿** |
| L1 本模块 | `-Dtest=PutawayTaskIntegrationTest` | **33 例绿** |
| L1 前端 | `pnpm vitest run <本车道两个测试文件> + 全部 warehouse 代理测试` | **113 例绿** |

`StockOwnershipIntegrationTest`（7.7.1.1，非本车道）在首轮全量跑里失败过一次，
单独跑 40/40 绿、全量复跑也绿 —— 判定为既有的执行顺序偶发，**与本车道无关**。

## 后续待办

- **8.3.2.1 拣货任务**：本模块的完成结果是它的输入。`capabilities` 里已显式标
  `available=false` 并写明「本模块**不**假装已经推了事件」—— 接线是下游交付时的事。
- **8.2.2.2 待检处理**：待检品走 `QUARANTINE` 库位（硬规则已生效），但「质检通过转可售」
  是 6 质检单的事，不在本模块。
- **8.1.2.2 的 `release` 决策 → 建任务**这条线目前靠前端手填 `allocationId`，
  若要做成「在分配页一键生成上架任务」需要一条新的读端点（当前没有）。
- 容量/承重口径与 8.1.2.2 一致：只算**本模块自己承诺过的量**，不算库位既有库存占了多少
  （capacity 是体积单位、on_hand 是件数，拿件数凑利用率是单位错配）。等 8.1.2.1 量测数据
  补齐后再考虑收口。