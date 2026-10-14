# 14:40 轮 — 三车道在飞，跳过误判重派

| 项     | 值                                                        |
| ------ | --------------------------------------------------------- |
| 进度   | 465 总数 / **59 已交付** / 0 进行中 / 406 未开发（12.7%） |
| 回收   | 无待并入车道；`gc` 清掉已合并的 3.7.2.1 worktree          |
| 派发   | 3.6.1.2 角色档案（V30）、3.6.3.1 地址与联系人（V31）      |
| 未派发 | 3.8.1.1 导入向导 —— 判定为**活车道**，见下                |
| 闸     | acquire / release 正常，无冲突                            |

## 决策：3.8.1.1 不重派

`plan --lanes 3` 的 `toDispatch` 把 3.8.1.1 列进来并标 `redispatched: 2`，
按脚本语义应视为「上次派发没成功」。**没有照抄这个结论**，实测三条证据判定它是活车道：

1. `heartbeatAt` 距当时仅 46 秒（远低于 90 分钟失联阈值），`stale: false`；
2. worktree 里有真实未提交产出 —— `ImportJobExecutor.java` / `ImportJobService.java` /
   `ImportCsvParser.java`、`import-model.ts` + 25 个单测、`import-wizard-workspace.tsx`、
   `products/import/`、契约与 api-proxy 均已改；
3. 14:20 轮会话日志记录它当时已被派发，缺口是「前端与契约」，
   心跳note 正是「前端四步向导 + model + 25 个单测已完成，正在跑全量验证」。

`plan` 误判的根因：它把「状态是 running 但 sessionId 为 null」当成了未派发。
子任务由 `task` 派发，`sessionId` 没有回写进 lane 记录，
于是每轮 `plan` 都认为上一轮崩了。重派会让两个 writer 同时改一个 worktree ——
这正是 14:20 轮「重复派发与并集驱动把已交付模块改回未开发」那次的同类事故。

**处置**：本轮只派 `newlyPlanned` 的两条车道，跳过 3.8.1.1。
`lane.mjs` 里 `plan` 判定重派的条件需要补上心跳新鲜度，否则每个整点都会复发。

## 上游依赖已实证（非凭台账）

两条新车道的上游都是 3.6.1.1。派发前 grep 确认
`V27__party_profile.sql` 已在集成头，建了 `party` + `party_role` 两张表。
台账那行也确实是「已交付」。

V27 第三节有一整段**专门写给 3.6.1.2 的交接说明**（三张 1:1 档案表、
连接键是 `party_role.role_type`、不在 party_role 上加角色字段的理由），
并且点名说明**联系人表归 3.6.3.1**、`party` 上的 email/phone/website 只是主体对外通道。
两段原文都贴进了对应子任务的任务书，省掉子任务重新推导。

蓝图对 3.6.3.1 的「单据上的地址是冻结快照」也写进了任务书并要求注释说明选型理由。

## 附带清理

仓库根目录有个上一轮遗留的未跟踪临时文件 `.tmp-check-party-test.java`（864 行），
违反落盘纪律（临时产物应在 `.tmp/`），已用 mavis-trash 移入回收站。

## 后续待办

- `lane.mjs plan` 的重派判定要改成看心跳新鲜度，不要只看 sessionId 为 null
- 3.8.1.1 等当前在飞车道跑完后，下一轮 `merge-ready` 回收
