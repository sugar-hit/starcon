# 流水线重启：每小时 4 车道，停机延到周日 18:00

日期：2026-10-08 21:30　决策人：用户　执行：调度会话（非车道）

## 决策摘要

用户要求「创建自动化任务，每小时开发 2 个未完成模块，本周日 18:00 前完成」。
评估后确认按字面执行不可行（见下），经用户选择改为 **每小时 4 个 + 24 小时连续**。

- 复用现有 `tools/module-progress/lane.mjs` 编排器，**没有另建调度系统**
- cron A `fcf14c15-6744-4ec2-b733-fc31d5babf44` 复活为 `0 * * * *`（原 `0,15,30,45`）
- cron B `f029d0bf-f94a-4aa4-8633-6dd220fe2ce7` 保持 `enabled: false`，未改动
- 硬停机：2026-10-08 18:00 → **2026-10-11 18:00（周日）**

## 关键 trade-off

### 为什么不是 100%（必须说清的前提）

起点：465 模块 / 已交付 116 / 未开发 349。
停机前约 67 个整点 tick × 4 ≈ **268 个理论上限**，终点约 **384 / 465 ≈ 83%**。

剩下约 81 个做不完 —— 多为 D5（0/52）、D7（6/149）长尾，以及上游被阻塞的模块。
用户已确认接受这个目标。**调度协议里显式写了「不要承诺全部完成」**，
避免每轮汇报都许一个兑现不了的愿。

### 为什么保留降噪闸

用户选了 4/小时，等于把稳态并发拉回 2026-10-07 22:56 那个「风扇很响」的量级
（当时 running=3 → 6 个 PG 容器）。这是**已知并接受的取舍**，不是疏漏。

对策是保留降噪闸（PG 容器 >= 6 零派发）作为**自动兜底** —— 噪声高时调度器自己停手，
不需要用户在场盯着。running 配额 4 同时是真闸：单模块超 60 分钟，下一轮自动停手。

### 提示词落库而不是塞在 cron 字段里

策略写成 `tools/module-progress/PIPELINE_CRON_PROMPT.md`（21KB），cron 字段只留瘦引导。
理由：cron 字段不可版本控制、不可评审，改策略要改它就得靠记忆 ——
而「停机时刻」这种事出现两份不一致的记录，历史上已经咬过（见下方 backlog）。
PIPELINE.md 与该文件现在是同一事实的两个落库位置，收尾报告要求不一致时点名。

## 途中发现并修掉的两个真问题

### 1. 主工作区脏 → 并入通道被彻底堵死（阻塞级）

18:00 停机时主工作区停在 `feat/3-7-1-customs-attributes`，带着 4 个未跟踪文件
（`CustomsAttribute{Models,Rules,Service}.java` + `V94__customs_hs_declaration_elements.sql`，1780 行）。

`lane.mjs` 第 882–886 行：**`git status --porcelain` 非空就 `exit(1)`**。
即每轮 tick 的第 3 步都会失败，**整条并入通道全封**。

处置：`git stash push -u`（可恢复，不删）→ 切回 `integration/module-dev` → 实测 `merge-ready` `exit=0`。

### 2. 台账与代码不一致（3.7.1.1）

`CustomsAttributeController.java` **已合入集成头**，但台账 `3.7.1.1` 仍标 `未开发`。
未来车道若按「未开发 = 代码不存在」排这条，会撞已存在的 Controller。

真正阻塞（台账证据列原文）：`product_compliance` 是 `product_research` 的 `INCLUDING ALL` 克隆，
装不下 HS 编码/申报要素/认证要素，**须先拆表**。

### 3. 残留 V94 已被重新分配（版本号地雷）

stash 掉那个 V94 之后，`lane.mjs plan --lanes 4 --dry-run` 给 `11.4.2.2` 分配的正是 **V94**。

**版本号分配器扫的是工作区文件，不是 git 历史。** 残留一旦 `git stash pop` 恢复，
会和 11.4.2.2 的 V94 同号，Flyway 直接炸。已写进 backlog，恢复前必须改号。

## 验证记录（不是「已验证」，是实测输出）

- `plan --lanes 4 --dry-run` → `newlyPlanned: 4`
  （11.4.2.2 / 4.2.2.2 / 6.4.1.1 / 15.4.2.1，分落 D4/D5/D6/D7，L3 锚点互不重复，`serviceBlocked: []`）
- `merge-ready` → `{"ok":true,"merged":[],"note":"没有待并入的车道"}`，**exit 0**
- `cron_update` 返回体逐字核对：`schedule="0 * * * *"`、`enabled=true`、`status="active"`、
  `nextRun=1791468000000` → 换算 **2026-10-08 22:00:00**，与 `0 * * * *` 一致
- cron B 复读：`enabled=false`、`status="paused"`、`schedule="0 */2 * * *"` 未被副作用改动
- 台账完整性：465 行，重复 ID 0

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `tools/module-progress/PIPELINE.md` | 改：停机时刻、配额、收尾协议、交付预期表 |
| `tools/module-progress/PIPELINE_CRON_PROMPT.md` | 新增：调度协议落库（21KB） |
| `docs/backlog/2026-10/2026-10-08-module-3-7-1-1-hs-code-blocked.md` | 新增：3.7.1.1 拆表阻塞 + 两个坑 |

commit：`69ed6c3`（在 `integration/module-dev`，**未 push**，按流水线纪律）

## 后续待办

1. **周日 18:00 收尾** —— 停机后调度器会自行关 cron，收尾报告要点名「距 268 上限还差多少」
2. **`.worktrees/trade-bootstrap/` 疑似孤儿** —— trade 服务已进集成头，该 worktree 创建于
   2026-10-07 22:58，`gc` 没清它（可能不在车道台账里）。占磁盘，无害，未处理。
3. **3.7.1.1 拆表** —— 该模块在拆表落地前不会被调度器排出，这是正确行为不是 bug
4. **D5 阶段 0/52** —— 整阶段零交付，值得单独查是否有共同上游阻塞