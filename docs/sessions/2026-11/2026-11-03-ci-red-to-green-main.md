# 2026-11-03: main 的 CI 红灯收口 —— 从「哪层红」到「为什么红」

## 摘要

把 `main` 的 CI 红灯全部收掉。红灯分四层，逐层定位后修复，最后发现真正的
**系统级根因只有一个**：后端测试隐式依赖「跑测试的机器恰好在东八区」。

| 层 | 症状 | 根因 | 处置 |
| -- | ---- | ---- | ---- |
| Lint | 583 个文件 Prettier 不合规 | 存量格式债 | `pnpm format` 归一 |
| Security Scan | 1 critical + 2 high | next 15.5.27 / vite 7.3.6 | 升到 15.5.28 / 7.3.7 |
| Java 作业 | identity 3 例 + catalog 3 例 | 见下 | 见下 |
| Unit & Integration Tests | 1 例 | 毫秒边界竞态 | 改断言边界 |

## 决策 / 结论

- **Java 红灯的根因是「环境差异」，不是「代码写错」**。本地全绿、CI 全红，
  而且失败的 3 个 catalog 用例与本机时区 +08:00 强相关：
  - `PriceListIntegrationTest:502` / `PriceListPriorityIntegrationTest:531`：
    断言「读回来的偏移量 == 写进去的偏移量」。但 PostgreSQL 的 `timestamptz`
    **只保时刻、不保偏移量**，读回来的偏移量由 JDBC 驱动按 **JVM 默认时区**
    换算 —— CI runner 是 UTC ⇒ 读到 `Z`，用例写死 `+08:00` ⇒ 必然红。
  - `BatchPriceAdjustmentIntegrationTest:645`：`OffsetDateTime.now()` 带纳秒，
    而 `timestamptz` 存储精度只到微秒 ⇒ 写回读回必然差几百纳秒
    （CI 实测 `722795824ns` vs `722796µs`）。warehouse 模块此前已用
    `truncatedTo(MICROS)` 硬化过，catalog 是漏网的那一处。
- **根治手段是钉死测试 JVM 的时区**，不是逐条改断言。在 `backend/pom.xml` 的
  surefire 配置里固定 `Asia/Shanghai`，两条启动期通道都设：
  `-Duser.timezone`（argLine，JVM 启动前生效）与环境变量 `TZ`（第二道保险 ——
  插件在启动后 `setProperty` 时 `TimeZone.getDefault()` 早已缓存）。
  ⇒ CI 与本地从此跑出同一个答案，仓库里几十条 `ZoneOffset.ofHours(8)` 断言
  不用动，语义也不变（它们本来就是在断言「读回来落在业务时区」）。
- **失败的断言本身也确实是错断言**，顺手改成断言瞬间 / 换算回业务时区后的墙钟，
  这样即使将来有人改掉时区钉子，这两条用例依然成立。
- **撤掉 spotless 格式闸，而不是全仓归一格式**：`spotless:apply` 实测会改
  2054 个文件、+152373/-104961 行，把刻意分行的中文 javadoc 并成一行、
  切断行内标签；且与仓库书面约定（`docs/backlog/2026-10-21-spotless-apply-mass-reformat-hazard.md`）
  直接冲突。已把插件从 `backend/pom.xml` 移除并留证。
- **CI 后端作业改 `-fae`（fail at end）**：默认 fail-fast 会让第一个失败模块
  停掉整条 reactor，于是 channel/warehouse/trade/crm/finance/reporting/platform
  的失败永远藏在后面，一次只能修一个 —— 这正是这批红灯拖了这么久的成因。
  服务之间没有 Maven 依赖（只依赖 `libraries/tenancy`），`-fae` 不会跳过任何模块。
- **后端作业超时 60 → 180 分钟**：9 个服务共用一条 reactor，815 个测试类，
  每个集成测试类都拉起一个 Testcontainers PostgreSQL 并跑一遍 Flyway；
  实测 identity+catalog 就接近 30 分钟，整条链路远超 60 分钟。

## 第一次跑完整条链，捞出一个真缺陷：「上周」算成了本周

`-fae` 跑通全链后，platform 报出一个从没见过的失败：

```
Expecting actual: 2026-11-08 to be strictly before: 2026-11-03
```

2026-11-03 是**周二**。问「上周」得到的是 **11-02 ~ 11-08**（本周），
正确答案应是 **10-26 ~ 11-01**（上一个完整自然周）。

**根因**：`today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY))`。
`previous(DayOfWeek)` 的语义是「**最近的一个已经过去的**那个星期几」——
今天不是周一时它落在**本周**的周一，不是上一周的周一
（实测：周二时 `previous(MONDAY)` 与 `previousOrSame(MONDAY)` 都返回本周的周一）。
⇒ 「上周」的窗口终点是**本周末**，在非周一的日子会问到**还没发生的几天**。
`AssistantService` 与 `SmartQueryService` 是同一份写法，两边都受影响。

🔴 **这个缺陷 6/7 的日子都发作，但集成测试只在「今天不是周一」时才红**
⇒ 等于有一半时间是绿的，缺陷被时钟藏起来 —— 这也是它一直没被发现的原因。

**修法**：口径抽到 `PeriodWindow.lastWeekMonday(today)`（先退一周再对齐那一周的周一），
两个服务共用；新增 `PeriodWindowRulesTest` 用**固定日期**覆盖一整周七天 + 跨年，
把口径钉成「与今天星期几无关」；并强化集成测试（补 5 条确定性断言）。
变异验证：把口径改回旧写法 ⇒ 单测 2 例红 + 集成测试 1 例红。

## 本地全量（9 个模块，816 个测试类，12,225 个用例）

| 模块 | 测试类 | 用例 | 结果 |
| --- | --- | --- | --- |
| identity | 54 | 644 | ✅ |
| catalog | 133 | 2108 | ✅ |
| channel | 188 | 2462 | ✅ |
| warehouse | 72 | 1674 | ✅ |
| trade | 141 | 2461 | ✅ |
| crm | 56 | 620 | ✅ |
| finance | 65 | 970 | ✅ |
| reporting | 51 | 585 | ✅ |
| platform | 56 | 701 | ✅（修掉「上周」缺陷后） |

**只有 1 个真实失败**（platform 的「上周」），其余是变异测试留下的旧 surefire XML。

> 教训：读 surefire 报告要**按 mtime 过滤**——变异测试会覆盖同名报告文件，
> 留下的失败会让人以为真缺陷还在。

## 仍然存在的盲区：时钟精度

本地 macOS 的 `OffsetDateTime.now()` 是**微秒对齐**（`gettimeofday`），
而 CI 的 Linux runner 用 `clock_gettime` ⇒ **带亚微秒尾巴**（实测 `…722795824`）。
⇒ 本地**永远复现不了** CI 的精度类红灯。本轮已系统扫过：
「`now()` 派生值 → 写库 → 精确相等断言」的形状全仓只有一处（catalog，已修）；
warehouse 模块此前已用 `truncatedTo(MICROS)` 硬化过。

## 涉及文件

- `backend/pom.xml` — surefire 钉时区；移除 spotless 插件
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceListIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceListPriorityIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/BatchPriceAdjustmentIntegrationTest.java`
- `.github/workflows/ci.yml` — 后端作业 `-fae` + 超时 + `TZ` 兜底；质量闸
- `docs/operations/catalog-local-development.md` — 时区约定写入运维文档

## 关键 trade-off / 讨论

- **没有选「把所有断言改成时区无关」**：那要动 8 个模块几十条断言，
  而且会把「读回来必须落在 +08:00」这种**真实业务要求**（运营/班次/日切按东八区）
  一起抹平成「瞬间相等」，反而丢了语义。钉时区保住了这些断言的含义。
- **时区钉死是测试环境约定，不是运行时行为**：生产部署时区仍由部署环境决定。
  文档里写明了「断言瞬间或换算回业务时区后的墙钟」这条写法约定。
- **`-fae` 让失败的 CI 跑得更久**（要跑完全部模块才退出），换来一次跑出全部问题，
  值得。
- **本地绿 ≠ CI 绿**：这批红灯里有一半是「本地复现不出来」的。教训是
  CI 环境差异（时区、时钟精度、容器时区）必须被当作一等公民，写在 CI 配置里钉死，
  而不是指望断言自己扛住。

## 后续待办

- [ ] 合入 main 后，main 自身的 CI 需要再跑一遍并确认为绿。
- [ ] `docs/backlog/` 里仍有效的 12 条技术债（按成本排序，见 backlog README）继续收口。

## 关联

- PR：https://github.com/sugar-hit/starcon/pull/74
- 后端红灯 run：https://github.com/sugar-hit/starcon/actions/runs/41640461446
