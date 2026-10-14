# 18:00 tick —— 「假重派」第一次差点开出同分支双 writer

本轮零回收、零新建车道，只派发 2 条。交付数仍为 75。调度闸已释放，工作区干净，无 MERGE_HEAD。

## 核心：三条 running 车道里，两条是僵尸

`plan --lanes 3` 返回 `newlyPlanned: []`，三条 toDispatch 全是 `redispatched`
（17.3.2.1 红了 3 次，另两条红了 2 次）。**redispatched 次数高 ≠ 该派** ——
它只说明「plan 排过但没派出去」，两种完全相反的实况都落在这个字段里。

这次三条 `running` 车道的心跳**是同一个时间戳** `09:56:22.685Z`，三秒不差。
真 worker 各发各的心跳，不可能整齐到毫秒。这是 `plan` 把 state 直接写成 `running`
留下的僵尸签名 —— 上一轮 plan 排完车道、还没派发就崩了。

**光看心跳不够**。真正的判据是 worktree：

| 车道     | 心跳 | porcelain | 自身提交 | 判定     |
| -------- | ---- | --------- | -------- | -------- |
| 17.3.2.1 | 4min | 6 个文件  | 无       | **活的** |
| 17.5.1.1 | 4min | 0         | 无       | 僵尸     |
| 17.5.3.2 | 4min | 0         | 无       | 僵尸     |

17.3.2.1 里有 `V47__member_role_assignment.sql`、`RoleAssignmentController.java`、
`RoleAssignmentIntegrationTest.java` 三个未提交的新文件 —— worker 真的在写。
**如果照 toDispatch 全派，17.3.2.1 就会同时有两个 writer 写同一个分支、同一个 V47。**

## 判据升级：心跳 → 文件 mtime

心跳被 `plan` 污染过，不可信。文件 mtime 骗不了人：

```bash
find .worktrees/<id> -type f -newermt '-2 hours' -exec stat -f '%Sm %N' -t '%H:%M:%S' {} \;
```

17.3.2.1 的 `RoleAssignmentController.java` mtime 是 **18:01:23**，
而当时是 18:02:12 —— **49 秒前刚写过**。这条一次就把「僵尸/存活」判死了，
不需要再纠结 heartbeatAt。

**派发前的正确顺序**：先看 `redispatched` 决定「要不要查」，
再用 worktree porcelain + mtime 决定「派不派」。mtime 是最终判据。

## 处置

- **17.5.1.1 / 17.5.3.2**：worktree 空、无自身提交 → 真僵尸，本轮正常派发（V48 / V49）。
- **17.3.2.1**：不派。给它续心跳并写 note，把「切勿再派」落进台账侧车，
  因为 `plan` 会**每轮继续把它列进 toDispatch**（redispatched 已经涨到 3，
  下一轮就是 4、5……）。不写 note，下一个 tick 的我只看到「红了 3 次」，很可能照派。
- 三个车道同属 `17.5` 一个 L3，但 17.5.1.1 / 17.5.1.2 / 17.5.3.2 的 L3 锚点互不相同，
  互相不阻塞 —— 编排选它们是对的。

## 顺带确认的既有事实

- `merge-ready` 在零 ready 车道时是干净的 no-op（`merged: []`，不产生 MERGE_HEAD），
  退出码 0。stuck MERGE_HEAD 那条坑只在真有车道要并时才可能触发。
- `gc` 同样 no-op（18 条 merged 车道的 worktree 上轮已清）。
- 资源：10 核，load 1.83/2.67/3.90（下行趋势，说明上轮 3 条车道已收工）。
  Docker limit 11.67GiB，4 个常驻容器合计约 **1.3GiB**（41M + 426M + 433M + 408M），
  余量约 10.4GiB。

## 下一个 tick 的待办

1. 17.3.2.1 大概率会先落地（V47 已写完）→ 回收时**核对台账没被回退**：
   `comm -23` 已交付模块集合，加「合并后已交付数必须 = 合并前 + 本次新增」算术校验。
2. 17.5.1.1 / 17.5.3.2 若本轮没派出去（worker 崩），它们会继续以僵尸身份留在 toDispatch，
   按上表判据重判即可。
3. **不要因为 17.3.2.1 的 redispatched 继续上涨就派它** —— note 里写了原因。
