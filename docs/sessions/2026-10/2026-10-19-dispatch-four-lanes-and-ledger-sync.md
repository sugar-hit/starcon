# 2026-10-19: 派发四条车道 + 蓝图主责服务判据错位

## 摘要

22:19 的一轮 cron tick。同步台账后已交付 **210 / 465**（未开发 255），容量闸全绿，
在集成头 `integration/module-dev` 派发 **4 条互不依赖的车道**并行开发。
本轮**没有可回收的车道**（`merge-ready` 返回空），因此**没有跑 L2、没有 push**——
按协议，L2 不得与在途车道的 L1 并发跑，而 4 条车道正在途。

本轮真正的产出不是那 4 条车道本身，而是**三个派发前必须查清、否则代码会落错服务的边界问题**，
以及**蓝图 README 的「后端主责服务」判据与实际落地不一致**这一处协议级隐患。

## 决策 / 结论

### 1. 蓝图 README 的主责服务首项 ≠ 实际落地服务（协议级隐患，未擅自改协议）

蓝图 README 的「后端主责服务」列写系统 04 刊登与内容 = `catalog + channel`，
**列表首项是 catalog**；`lane.mjs plan` 的微服务闸正是取首项判服务是否存在。
但已交付的 4.x listing 族**全部在 channel**：

| 模块 | 实际服务 | 迁移 |
| --- | --- | --- |
| 4.1.1.1 Listing 列表 | channel | `V171__listing_list.sql` |
| 4.1.1.2 Listing 详情 | channel | `V175__listing_detail.sql` |
| 4.3.1.2 批量发布 | channel | `V183__listing_publish_batch.sql` |
| 4.3.2.1 内容回写 | channel | `V187__listing_content_writeback.sql` |

台账里 4.1.1.1 的证据写明了理由：「listing 必须按店铺/站点筛选并收敛可见性，
而 `shop`/`shop_marketplace` 是 channel V1 的事实且带 data_scope RESTRICTIVE 策略；
**放 catalog 就得为店铺维度数据范围去读 channel 的表 —— 那是跨服务 SQL**」。

⇒ **4.4.2.1 商品认领落在 channel**，并在任务书与台账证据里写明这条理由（沿用 4.1.1.1 先例）。
⇒ **待办**：README 那张表的「多服务」写法与 plan 的「取首项」判据本身不自洽。
改 README 还是改 `plan` 的判据，是协议改动，**本轮未擅自修改**，留给人工/下一轮决策。

### 2. 11.2.3.3 供应商索赔：12 财务尚未落地，冲减应付只能在 trade 侧自持

`backend/services/` 下只有 identity / catalog / channel / warehouse / trade / crm，**没有 finance**。
蓝图要求「输出：索赔单 + 冲减应付或退款记录」，下游写「12 财务（冲减应付）」。
⇒ 冲减应付落成 **trade 自己的事实表**，且蓝图加粗句「**索赔不是自动到账**，系统只跟踪流程与金额，
冲减应付需财务确认」落成状态机 + 幂等 + 不可逆终态：**没有财务确认动作前冲减不生效**。
与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。

### 3. 三条跨服务边界各自的具体处理（禁止跨服务 SQL / 跨库 FK / 共享视图）

| 车道 | 跨服务点 | 处理 |
| --- | --- | --- |
| 7.8.2.1 分摊结果查看 | 平台回写状态来自 4.3.2.1（**channel**） | warehouse 侧建**本地投影事实**（带来源标识 + `as_of` 采集时刻）；**没有回写状态时显式判「未知」，绝不编造或按 0 处理** |
| 2.4.2.3 映射缺失队列 | 缺映射订单来自 09 订单（**trade**） | 订单来源经 **event / 授权 API** 接入；本批未接线 ⇒ 明确写成待接线，**不写假数据** |
| 4.4.2.1 商品认领 | 读写同在 channel | 无跨服务；若确需 03 商品主数据，走授权 API / event |

### 4. 上批车道状态被并发会话重置 —— 判定为「重派」而非「丢工作」

22:10–22:15 有一个并发会话在集成头做了：`git reset` 掉 22:10:57 的合并 `b5603af8`（merge `feat/11-2-3-3`）、
清空 `lanes.json`、移除上一轮的车道 worktree。该合并的内容经查证**只有 1 行 `.smoke-test.txt`**，
没有 V188 迁移、没有实现代码 ⇒ 那是**空壳车道**，回滚是对的。
⇒ 本轮 4.4.2.1 / 11.2.3.3 等是**从零重派**，不是「续写被删掉的工作」。
`b5603af8` 仍在 object store 里（`git cat-file -t b5603af8` → commit），万一需要可从 reflog 捞回。

### 5. 协议内部张力：步骤顺序与「L2 不得与在途车道 L1 并发」冲突

`PIPELINE.md` 的步骤顺序是 step 4 派发 → step 5 merge-ready + L2 + push，
但同一份协议明确禁止 L2 与在途车道的 L1 并发（2026-10-19 20:00 实测：PG 峰值 16，
1364 例里 3 failures + 24 errors **全是假红**）。
⇒ **下一轮的顺序应当是「先 L2 验上一批，再派发下一批」**，而不是照步骤号机械执行。
本轮未改协议文件，只在汇报与本文件里点名。

## 本轮数字（现算，勿引用旧文档）

- 台账：已交付 **210** / 进行中 0 / 未开发 **255**（总 465）
- 容量闸：ther m 无降频项（`No thermal warning level has been recorded`）·
  running 0/6 · Testcontainers 标签 PG 容器 **0**/6 · load **1.56 / 2.74 / 3.33**（下降趋势）⇒ **GO**
- 模式：`MODE=full`（`TOKEN_HORIZON=2026-10-25`，当前 10-19）· LANES=4 · THROTTLE_MIN=50
- 节流闸：`.dev-agent/last-batch-at` 此前不存在 ⇒ 首轮语义 ⇒ 放行；本轮**通过闸门后**才写时间戳
- 台账完整性：`grep -c` = **465**，模块 ID 无重复
- 静态预检：本批**无新迁移可扫**（车道在途，V188–V191 尚未落盘）；
  全仓扫描命中项全部落在**历史已合入**迁移（V14 / V15 / V35 / V41 / V7），非本批引入

## 本批派发的 4 条车道

| 模块 | 名称 | 阶段 | 落地服务 | 迁移 | 车道分支 |
| --- | --- | --- | --- | --- | --- |
| 11.2.3.3 | 供应商索赔 | D4 | trade | V188 | `feat/11-2-3-3` |
| 7.8.2.1 | 分摊结果查看 | D3 | warehouse | V189 | `feat/7-8-2-1` |
| 4.4.2.1 | 商品认领 | D5 | **channel** | V190 | `feat/4-4-2-1` |
| 2.4.2.3 | 映射缺失队列 | D5 | channel | V191 | `feat/2-4-2-3` |

worktree 全部在 `integration/.worktrees/<模块ID>`，基线 `6904288d`，0 未跟踪文件；
V188–V191 派发前确认无版本号占用。

## 涉及文件

- `tools/module-progress/PIPELINE.md` / `PIPELINE_CRON_PROMPT.md`（协议，本轮只读）
- `.dev-agent/lanes.json`（4 条车道 running）、`.dev-agent/last-batch-at`（本轮写入）
- `docs/product/capability-blueprint/module-progress.md`（台账，本轮无变更）
- `docs/product/capability-blueprint/{02-channel-and-shop,04-listing-and-content,07-inventory,11-aftersales-and-returns}.md`（4 个模块的设计原文）
- 车道 worktree：`integration/.worktrees/{11.2.3.3,7.8.2.1,4.4.2.1,2.4.2.3}`

## 收尾三件套状态

| 环节 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 |
| ② commit | ✅ 与台账同 commit（台账本轮无变更） |
| ③ push | ❌ **本轮不 push** —— 本轮无可回收车道 ⇒ 无 L2；4 条车道在途，L2 不得与在途车道 L1 并发跑。按 5.3 的「L2 红的路径」：说明照写、commit 照做、成果与诊断留在集成头本地，随下一批一起 push |

## 后续待办

- [ ] **下一轮**：先 `merge-ready` 回收本批 4 条车道 ⇒ 跑**一次**全量 L2（后端 `mvnw verify` + 前端 typecheck/lint/test + `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` + `sync --check`）⇒ 绿了才 push `integration/module-dev` ⇒ 再派下一批。**不要在有车道在途 L1 时跑 L2。**
- [ ] **下一轮**：本批批次号取 **batch-28**（接 `batch-27` 往后排；取号命令见 `PIPELINE.md` 5.3）。说明文件 + 台账同一个 commit，commit 前缀 `docs(batch-28):`。
- [ ] 静态预检要扫本批新迁移 **V188–V191**（本轮扫不到，车道未落盘）：`ADD COLUMN` 无 `DEFAULT`、`SET NOT NULL` 无触发器兜底、`ADD CONSTRAINT ... CHECK` 三条。
- [ ] **协议待决**：蓝图 README「后端主责服务」多服务写法与 `plan` 取首项判据不自洽 —— 改 README 还是改 `plan`？
- [ ] **协议待决**：step 4 派发 / step 5 L2 的顺序与「L2 不得与在途车道 L1 并发」冲突 —— 是否把协议改成「先 L2 后派发」？
- [ ] `finance` 服务落地后，11.2.3.3 的冲减应付需与 12 财务经授权 API / event 对账。
- [ ] `trade` 侧缺映射订单的推送事件落地后，2.4.2.3 的订单来源接入点接线。
