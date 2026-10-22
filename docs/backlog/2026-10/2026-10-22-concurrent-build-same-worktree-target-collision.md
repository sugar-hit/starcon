# 同一 worktree 内不得并发跑两个构建（共用 `backend/*/target/` ⇒ 正在跑的 JVM 会被换掉 class）

- 创建: 2026-10-22
- Owner: 批次调度者 / 流水线纪律
- 优先级: P1
- 关联: 2026-10-22 批次 L2 trade 段红（`ReplenishmentSuggestionTenancyIntegrationTest` 三条租户断言 503「商品主数据服务返回 404」）；车道 `6.2.1.2-fix`；`docs/sessions/2026-10/2026-10-22-6-2-1-2-trade-catalog-upstream-404.md`

## 背景

2026-10-22 批次 L2（`backend` 段 `./mvnw verify`）在 **trade** 模块红：

```
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言1_两租户互不可见:410->compute:339 Status expected:<200> but was:<503>
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言2_伪造租户上下文无效:460->compute:339 Status expected:<200> but was:<503>
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言3_连接池归还后不串租户:522->compute:339 Status expected:<200> but was:<503>
[ERROR] Tests run: 2380, Failures: 3, Errors: 0, Skipped: 0
```

同一批缺陷**单独跑绿**（4/4）、**与同门类一起跑绿**（45 例）、**与紧邻前一个类一起跑绿**（46 例），
**只有整模块连跑才红** ⇒ 顺序/环境依赖，不是确定性缺陷。

失败窗口（10:51）里，机器上有别的 cron agent 在**同一个 worktree** 活动
（实测 session 列表：10:28 与 11:15 各有一次「main → module-dev 集成头同步」任务）。

## 为什么这是真问题：并发构建共用 `target/`

同一个 worktree 里跑第二个 maven ⇒ 两个进程写**同一份** `backend/<svc>/target/`：

| 共享物 | 后果 |
| --- | --- |
| `target/classes/**.class` | 第二个构建会**重写** class 文件；正在跑的 JVM 是**懒加载** class 的 ⇒ 它的 Spring 上下文可能加载到**另一个构建写出来的字节** ⇒ 运行时行为与源码不一致（URL 拼装、端口、路由都可能变） |
| `target/test-classes/**.class` | 同上，测试类本身可能被换掉 |
| `target/surefire-reports/**` | 两轮跑**互相覆盖报告** ⇒ 事后无法区分「哪一轮红」 |
| `target/flyway` / 迁移产物 | 两轮各自的 PG 容器与库是独立的，但迁移脚本版本可能不同 |

⇒ 「L2 红了，但单独重跑是绿的，而且**原始失败报告已经不在磁盘上**」——
本次正是这个形态（原始失败报告被 11:12–11:14 的重跑覆盖成了绿报告）。

## 待办内容

- [ ] **L2 批次验证与任何其它构建不得在同一 worktree 并发**：要么串行，要么各自开独立 worktree
      （`.worktrees/<lane>-<n>` 已有机制，每个并发车道一个目录 ⇒ `target/` 天然隔离）。
- [ ] 批次调度器在**起跑前**检查该 worktree 是否已有 maven 进程在跑（`pgrep -f maven` / 端口占用），
      有则**排队等待**而不是并发启动。
- [ ] 「main → module-dev 集成头同步」这类**只读/同步任务**不要在同一 worktree 里触发任何构建动作；
      同步任务若必须构建，产物目录要与 L2 分开。

## 影响面

- 模块：全仓所有服务的 `mvnw verify`（trade 只是第一个撞上的）。
- 症状形态：偶发、单跑绿 / 连跑红、**失败报告可能被覆盖**、报错指向「不可能错的地方」
  （本次是「上游回 404」—— 即运行中的 JVM 用了一个与源码不一致的 class）。

## 完成标准

- [ ] 批次调度器有明确的「同 worktree 构建互斥」判据，并在日志里留痕。
- [ ] 后续任何一次 L2 红了，**原始 surefire 报告仍在磁盘上**（不被并发跑覆盖）。
