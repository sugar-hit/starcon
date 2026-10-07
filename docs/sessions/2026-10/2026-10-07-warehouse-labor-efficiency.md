# 8.5.1.1 作业效率（人效）落地

- **模块**：8.5.1.1 作业效率（阶段 D4，L3 锚点 8.5 作业绩效）
- **车道**：`feat/8-5-1-1`（worktree `.worktrees/8.5.1.1`）
- **日期**：2026-10-07

## 路线选择：路线 A（自落地作业记录事实表）

蓝图把上游写成「8.3 全部作业记录」，但调度器核实后确认 **8.3 整块尚未交付**：
集成头里 warehouse 服务 33 张表没有任何出库/拣货/复核/打包/发货表；8.3.1.1 出库单列表正在
同批次并行落地（V72），它只建 `outbound_order` / `outbound_order_line`，**不建作业记录表**；
8.3.2.1 拣货、8.3.3.1 复核、8.3.3.2 打包都还没排上。

因此选**路线 A**：本模块自己拥有「作业任务/作业记录」事实表并提供**真实的写入入口**
（派工 / 开工 / 完成 / 异常），报表从这张自有表聚合。没有选第三条路（建假演示表或在内存里
塞硬编码当作业记录源）。

**关键论证（写在这里，免得以后有人重新推一遍）**：本表与 8.3 的作业单据**粒度不同**，所以不是同一张表。

- 8.3 记的是**作业单据**：要拣哪些货、复核哪一批。
- 本表记的是**人效事实**：某个人在某段时间做了多少、走了多远、出了几处差错。

人效统计的最小事实本来就比作业单据细一层。「每张事实表只有一个写入所有者」在这里落成可执行的
边界：**本车道是 `warehouse_work_task` / `warehouse_work_event` 的唯一写入者**；8.3 各车道落地后
把作业事件投递到本服务的端点（`sourceModule` 标明来源模块号），而不是直接写表 ——
AGENTS.md 禁止跨服务写对方的表。

本模块**刻意不建** `picking_task` / `review_task` / `packing_task`：那是 8.3.2.1 / 8.3.3.1 /
8.3.3.2 各自车道的事，会撞表。

## 核心交付：五个指标的精确口径（蓝图点名「需明确口径避免争议」）

蓝图原文：「人效数据用于提成核算与排班，需明确口径避免争议（ERP 人效报表是绩效争议高发区）」。
所以口径不是注释，是**交付物**：逐字写进 V74 迁移注释、服务 javadoc、`/capabilities` 响应体、
页面口径卡片与本文件。

| 指标 | 计算式 | **分母** | 关键决定 |
| --- | --- | --- | --- |
| 人均日拣货件数 | ΣPICKING.piece_count ÷ (人数 × **作业天数**) | 人数 = 区间内有过完成作业的**不同操作人**个数；作业天数 = 区间内出现过完成作业的**自然日**天数 | 刻意**不用**「人数 × 区间自然日数」：那样停工日、休假日会把人均拉低，而停工不是任何一个人的效率问题。代价：首尾不完整日被当成完整日，该口径**偏乐观** —— 已写进 `perCapitaBasisNote` |
| 人均日拣货单量 | 同上，换 order_count | 同上 | `order_count` 是**该任务内**去重后的张数；跨任务去重需要全量明细，这张汇总表的粒度做不到，如实说明而不假装做得到 |
| 拣货行走距离 | ΣPICKING.travel_distance_m | 人数 × 作业天数 | 只有拣货任务有值，其他类型恒 `NULL`（用 0 会把「没记」和「走了 0 米」混成一条数）；服务层直接拒绝非拣货任务带该字段 |
| 打包时效 | AVG(completed_at − started_at) | **打包任务张数**（不是件数） | **墙钟时长，不是净作业时长**：净时长要剔除等待与中断，需另记暂停区间，本表没有那组数据，算出来的是假的。墙钟可复算 —— 任何人拿同一行都能算出同一个数，这正是在争议里能被采信的理由。显式排除 `status='EXCEPTION'` 与无 `started_at` 的补录任务（分子分母同时排除，不是算成 0） |
| 复核准确率 | (复核件数 − 差错件数) ÷ 复核件数 × 100 | **复核件数**（`work_type='REVIEW'` 的 piece_count 合计），不是复核单数、不是复核行数 | 差错记在**发现**它的那张任务上，所以这个口径下的准确率就是复核员自己的准确率。**分母为 0 返回 `null` 而不是 0%**：0% 会被读成「复核全对」，真实含义是「今天没人复核」 |
| 差错率 | 两个口径并列：按件数 = Σ差错件数 ÷ Σ作业件数；按单量 = Σ差错单量 ÷ Σ涉及单量 | 两个口径的分母各自写明，不共用一个「总量」 | 按件数口径**跨三种作业类型统计**（拣错、复核漏、打错包装都记在各自那张任务上）。DB 级 CHECK 钉死 `error_piece_count ≤ piece_count`：超过它意味着口径本身错了，而不是「这个人错得离谱」 |

### 另外三条决定

1. **差错 ≠ 异常**。差错（做错了）进差错率；异常（缺货 / 设备故障 / 单据取消 / 安全暂停，做不了）
   进异常、**不进差错率**。两者是两个字段、两套端点、两套口径。混在一起会让遇上缺货的人
   被算成错得最离谱 —— 这是排班时最不公平的一种算法。
2. **只统计已定稿任务**（COMPLETED + EXCEPTION）。未开工、作业中的任务没有产出，
   算成人效等于奖励「领了任务不干活」。
3. **排名一次只排一个指标**，方向由指标自带 `higherIsBetter`（差错率/距离越低越好，
   准确率/件数越高越好），排序按 SQL 里的未截断原值做、取整只发生在展示层。
   把方向相反的指标塞进同一个「排行榜」再让人自己看，是绩效争议的标准起手式。

### 时区口径

「日」= 任务**完成时刻**在 **Asia/Shanghai** 下的自然日，聚合键写死为
`(completed_at at time zone 'Asia/Shanghai')::date`，四个部分索引直接建在这个表达式上。
刻意不用服务器本地时区：同一份人效报表在两台机器上跑出不同的「昨天」，绩效争议无从谈起。
判「一天算到什么时候」以**完成时刻**为准，不是开工或派工时刻。

## 人效凭据的稳定性做成 DB 不变量

蓝图那句「避免争议」落到可执行的东西上 —— 争议之所以无法裁决，通常是因为数据能被随手改。

- `guard_work_task_transition`：身份定位列（单号/类型/来源/仓库/`created_at`）恒不可改；
  **已开工不许换操作人**；**状态未变时计量与时间一个字都不许动**；合法边只认
  `PENDING → RUNNING → COMPLETED/EXCEPTION` 且终态不可回退。
  冻结判据是「**状态未变**」而不是「已开工」—— 完成端点本来就要在同一条 UPDATE 里既写计量列
  又改状态，那是**定稿**不是改。（实现过程中先写成「已开工」判据，结果在 RUNNING→COMPLETED
  那一次误伤被集成测试当场抓出来。）
- `guard_work_event_append_only`：事件只能追加。删除有两层防护（应用角色 `REVOKE DELETE`
  + 触发器），测试用**超级用户直连**验过第二层确实也生效。
- 未开工的任务**允许改派**（现场调度是正常的），但不允许手工填计量 —— 计量只能由
  开工 / 完成 / 异常端点写入。

## 踩到的坑（都留了痕）

1. **`?::timestamptz` 的占位符顺序**：夹具 INSERT 里两个连续 `::timestamptz` 会把 cast 落到
   uuid 列上（`cannot cast type uuid to timestamp with time zone`）。`assigned_at`（timestamptz）
   后面紧跟 `assigned_by_user_id`（uuid），不能连着写两个 cast。
2. **`LIMIT` / `OFFSET` 的绑定类型**：`Integer` 绑 `LIMIT` 在驱动层就报
   `Error while writing value at bind index: N`。与 7.4.2.2 同一处约束，两个都绑 `(long)`。
3. **枚举不能直接当绑定值**：`binds.add(status(status))` 把 `WorkTaskStatus` 实例交给驱动，
   报 `Type class ... is not supported in dialect POSTGRES`。要 `.name()`。
4. **裸 SQL 在测试里也要绑租户**：RLS 下没绑 `app.current_tenant` 的 `UPDATE` 影响 0 行而**不报错**，
   会让「DB 层拒绝了我」的断言假通过。测试里所有直写都包在 `access.run(...)` 里。
5. **触发器的两条判据不能混**：见上面「冻结判据」那段。

## 落地清单

- **迁移**：`backend/services/warehouse/src/main/resources/db/migration/V74__warehouse_labor_efficiency.sql`
- **后端**：`LaborEfficiencyModels.java` / `LaborEfficiencyService.java` / `LaborEfficiencyController.java`
  （11 个端点挂 `/warehouse/labor`，新起 controller，不动别的车道的文件）
- **测试**：`LaborEfficiencyIntegrationTest.java` 23 例（Testcontainers PostgreSQL 16，连接池压到 1）
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → 新增 21 个 `Labor*` 类型，identity / catalog / channel 三份 spec 零变化
- **前端**：`apps/web/src/app/inventory/labor/page.tsx` +
  `labor-efficiency-workspace.tsx` + `labor-efficiency-model.ts`（20 例单测）
- **代理白名单**：`apps/web/src/lib/api-proxy.ts` 登记 labor 全部形状。
  注意 `labor` 与 `warehouses` 同级（**不在 `inventory/` 之下**），因为前端打的是 `warehouse/labor/...`。
- **台账**：8.5.1.1 那一行的「状态」「证据」两格（+ `sync.mjs` 自己管的派生计数）

## 后续待办

- **8.3.2.1 / 8.3.3.1 / 8.3.3.2 落地后**，由它们把作业事件投递到本服务的
  `POST /warehouse/labor/work-tasks`（`sourceModule` 填各自模块号）。表结构不需要改。
- **8.5.3.1 出库时效**是本表的自然下游（同样吃作业时间戳），口径要和本模块的墙钟时长对齐。
- 17 绩效交付后，`sourceModule` + 事件时间线可直接支撑提成核算复核，不需要另建审计。