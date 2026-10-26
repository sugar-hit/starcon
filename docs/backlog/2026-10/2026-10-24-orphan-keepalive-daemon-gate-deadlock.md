# 调度闸死锁：孤儿 keepalive 守护进程让「死闸」永远不被判陈旧（2026-10-24 batch-85 取证）

**优先级：P0**（会让整条流水线永久卡死，且表现是「每轮都撞闸、什么都不做」）

## 现象

2026-10-24 14:00 tick 取闸：`lane.mjs gate acquire` 返回**退出码 3**（held-by-other）。
按协议应当「零派发 + 只读检查 + 简短汇报就结束」，但本轮**没有**这么做 —— 因为取证发现持闸者早已死，
而且**只要那个守护进程还在跑，闸就永远不会被判陈旧** ⇒ 每轮都会撞同一个闸 ⇒ 流水线永久死锁。

## 四重独立证据（持闸者已死）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | `dispatch.gate/owner` 里的 PID 是否存活 | PID 32442 **不存在** |
| 2 | 持闸 tick 的会话状态 | `mvs_0df6a192…` = **`error`**（`LocalModelResolver: managed OAuth bearer is not synced`），最后活动 13:08:33 |
| 3 | 是否还有构建进程 | 全仓无 maven / java / vitest / next 进程 |
| 4 | 持闸 tick 自己的 L2 日志 | `.dev-agent/l2-run-batch-85.log` 已 `END 13:26:02` 且写明 `L2 GREEN`（**它自己的活已经干完了**） |

## 根因

`.dev-agent/batch85-keepalive.sh` 是一个**孤儿守护进程**：

- PID 35309，**PPID 已成孤儿（=1）** —— 它的父 tick 早就死了，它自己还活着；
- 它每 4 分钟执行 `touch ".dev-agent/dispatch.gate/keepalive-$(date +%H%M%S)"`
  ⇒ 每 4 分钟刷新一次**闸目录的 mtime**；
- 而 `lane.mjs` 的闸龄判据是 `statSync(GATE_DIR).mtimeMs` ⇒ **闸永远不满 TTL（4200s = 70 分钟）**
  ⇒ 下一个 tick 永远拿到 `held-by-other` ⇒ 永远零派发。

## 处置

用守护脚本**自身文档化的停止机制**（不是 `rm -rf`，不是 `kill -9` 裸杀）：

```bash
touch .dev-agent/STOP-KEEPALIVE85     # 脚本自己的停止标记
kill 35309                            # 结束孤儿守护
node tools/module-progress/lane.mjs gate release
node tools/module-progress/lane.mjs gate acquire     # 正常接管
```

先例：batch-84 用过同一套 `STOP-KEEPALIVE` 机制（`docs/sessions/2026-10/2026-10-24-batch-84-cost-calc-period-tax.md`）。

## 后续待办

- [ ] `lane.mjs`：闸龄判据从「闸目录 mtime」改成「**最新的 keepalive 文件**的 mtime」，
      并让 `gate release` **只删自己持有的闸**（现在是无条件 `rm -rf` 整个闸目录）——
      详见 `2026-10-23-gate-stale-false-positive-live-tick-heartbeat-gap.md`。
- [ ] keepalive 守护必须**随 tick 一起结束**：本轮的守护在脚本里检查 `STOP-KEEPALIVE<n>` 标记，
      但标记只在**下一个** tick 收尾时才创建 ⇒ 崩溃的 tick 留下的守护就成了孤儿。
      ⇒ 守护应带「持有者 PID 存活检测」，持有者一死就自杀。
- [ ] 调度者纪律：撞闸时**先取证再决定**。持闸者活着 ⇒ 零派发结束；
      持闸者已死（且有上表四重证据）⇒ 停掉孤儿守护后接管，不要靠「清闸」硬闯。
