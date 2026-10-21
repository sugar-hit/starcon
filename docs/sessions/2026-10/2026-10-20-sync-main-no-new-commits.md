# 2026-10-20: 集成头同步 main（无新提交）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 的同步。
本轮**未并入任何提交**：`origin/main`（`1bb270a0`）已是集成头的祖先，无需 merge、无需预检、无需 push。

关键过程：调度闸被一个**活着的流水线 tick** 持续持闸（`stale: false`），本轮从 18:20 等到 19:15
（约 55 分钟）才等到交接窗口。期间发现闸的 `ageSeconds` 反复归零**不是**交接，而是上一轮 tick
留下的 `keepalive.sh` 每 5 分钟 `touch` 闸目录刷新 mtime 所致——这一点直接影响了等待策略的判断。

## 决策 / 结论

- **闸的 `ageSeconds` 归零 ≠ 闸被交接**。判据仍是 `gate status` 的 `stale` 字段；
  但要意识到 keepalive `touch` 会刷新闸目录 mtime，使 `ageSeconds` 归零而闸仍被持有。
- **等闸用密集 `acquire` 竞态**（本轮最终跑到 0.25s 间隔、60 分钟预算），
  而不是低频 `status` 观测——交接窗口可能窄到零宽度。
- **等闸期间只做只读取证**：不跑第二个构建（与持闸 tick 的 L2 共用 `backend/**/target/` 和 PG 容器，
  并发必假红）、连 `git fetch` 都不先跑。
- **`owner` 里的 PID 不是持闸存活性证据**：`gate acquire` 是短命进程。本轮实测 owner pid
  `7425` 查无此进程，而持闸 tick 同时在同 worktree 跑 `mvnw verify`。
- **拿到过闸才 `gate release`**：本轮确实 acquire 成功过，收尾正常 release（`gate: released`）。
  release 后 19:16:44 已被**下一个** tick 正常重新取闸，互斥未被破坏。

## 本轮事实数据

| 项 | 值 |
| --- | --- |
| 结果 | 无新提交，未同步 |
| `origin/main` HEAD | `1bb270a0` docs(batch-28): 11.2.3.3 / 7.8.2.1 / 4.4.2.1 / 2.4.2.3 |
| 待并入 main 提交数 | 0 |
| 集成头 HEAD | `d22bc1e1` docs(batch-37): 10.3.1.2 / 10.3.2.2 / 10.6.2.1 / 10.7.1.2 |
| 台账（同步前后无变化） | 已交付 242 / 进行中 0 / 未开发 223（共 465，完成率 52.0%） |
| 合并 commit | 无（未产生 merge） |
| 预检 | 未跑（无合并，无需预检） |
| 闸 | 已释放（19:15:19 acquire → release，19:16:44 由下一 tick 正常接续） |

## 涉及文件

- `tools/module-progress/SYNC-MAIN.md` — 本轮协议来源
- `tools/module-progress/lane.mjs` — `gate acquire` / `gate status` / `gate release` 语义
- `docs/product/capability-blueprint/module-progress.md` — 台账（`sync.mjs --check` 校验一致）
