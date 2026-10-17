# 批次 18 · 售后单详情（11.1.1.2）

> 调度会话：2026-10-17 20:00 tick ｜ 集成头 `c03b53ac`
> 本批 1 条车道落地（18:00 批次的另外 3 条因 Token 配额中断，未进入本批）

## 本轮的真实背景：配额停摆后的恢复

18:00 tick 在 18:27 因 `errorCode 42212`（Token Plan 用量上限）中断，19:00 tick 在启动后
**第 1 秒**再次撞同一配额墙（两条调度会话的 `status` 都是 `error`）。
于是本轮面对的不是常规流水线状态，而是**配额暴毙后的现场**：

| 车道 | 账本 state | 分支领先 | 工作区未提交文件 | 真实情况 |
| --- | --- | --- | --- | --- |
| 11.1.1.2 售后单详情 | `ready` | 1 | 0 | **真正跑完了**，18:23:59 落地 |
| 11.1.2.1 原因分类 | `running` | 0 | 13 | worker 18:27 暴毙，成果只在工作区 |
| 11.3.1.2 退款状态 | `running` | 0 | 9 | worker 18:27 暴毙，成果只在工作区 |
| 17.4.2.1 待我审批 | `running` | 0 | 11 | worker 18:27 暴毙，成果只在工作区 |

⚠️ **`lane.mjs status` 报 `lanesRunning: 0` 是假象** —— 那 3 条心跳停在 95–99 分钟前，
超过 90 分钟 TTL 被容量闸排除出计数，于是闸以为机器空闲。
真实状态要读 `lanes.json` 的非终态条目 + 会话 status + 进程表，三者交叉才能下结论。

## 模块 11.1.1.2 售后单详情

**这个功能做什么**：运营打开一张售后单，能在一屏内看到「买家说了什么」和「我们做了什么」
按时间线对齐的全过程 —— 原订单信息、申请诉求、退的商品行与数量、沟通记录、附件凭证、
处置流程状态、平台侧受理情况、责任判定。退款/退货关联与商品问题归因尚未开发，
页面逐块显式标注「未交付 + 归属模块号」，不编造结论。

**技术落地**：

- 迁移：`V157__trade_aftersales_case_detail.sql` —— 新建
  `aftersales_case_line`（商品行与数量）、`aftersales_message`（沟通记录）、
  `aftersales_attachment`（附件凭证）三表，`tenant_id NOT NULL` + ENABLE **与 FORCE** RLS +
  GRANT，表级 REVOKE UPDATE/DELETE（append-only）。**零 ALTER 既有表、零外键**：
  对 V156 的 `aftersales_case` 簇与 V99 的 `sales_order` 簇只读，
  跨表一致性交给触发器在写入时刻真查，避免本迁移对别的车道产生 DDL 依赖。
- 端点：`/trade/aftersales` 下 7 个 —— `case-detail/capabilities`、`cases/{uuid}`、
  `cases/{uuid}/timeline`、`/notes`、`/messages`、`/attachments`、`/lines`。
  刻意不提供 PUT / DELETE：留痕表在 DB 层连权限都没有，改写与删除不是「没做」而是「做不到」。
- 页面：`/aftersales/cases/[id]` —— 九宫格概览，时间线作主干置顶，按说话方四档着色，
  顶部并列「买家侧 N · 我方侧 N」，加载/空/错误三态齐全。
- 测试：`AftersalesCaseDetailIntegrationTest` 15 例 + `AftersalesCaseDetailTenancyIntegrationTest`
  4 例，Testcontainers 真实 PG16，含租户隔离三断言实跑。

**关键设计决策**：

1. **不复制订单字段**。SKU / 单价 / 规格读 `sales_order_line` 的下单快照 ——
   复制出来的订单号会与真表漂移，而纠纷举证里「截图上的订单号和系统里的不一样」是最致命的破绽。
2. **`sent_at`（对话那一刻）与 `captured_at`（采集时刻）分成两列**。
   填 `now()` 会让三个月前的平台消息在举证里显示成「今天买家说的」。
3. **附件只存 `storage_ref`，不存文件内容**。本服务没有文件服务，存 base64 会让一行凭证
   把详情响应顶到几十 MB，而举证打包要的是引用。
4. **「外部消息号不重复」用部分唯一索引**（`WHERE btrim(external_message_id) <> ''`），
   **不是**表级 UNIQUE + 哨兵生成列 —— 哨兵方案会让所有无外部号的消息互相冲突，
   一张单上第二条手写备注就插不进去，比不做还糟。
5. **时间线是十条来源 UNION ALL 的一条 SQL**，统一按 `occurred_at`（事件发生时刻，非录入时刻）
   排序；绑定值用只含两行的 `t` CTE 承载 —— 十段各写一遍 where 需要 20 个占位符，
   而 jOOQ 占位符顺序错位**不报错**，只把值绑到别的 `?` 上。

## 本轮踩到并修掉的四个真实缺陷（测试逼出来的）

1. **11.1.1.1 的 `platform_received_at` 少写 `::timestamptz`** —— 该列是 timestamptz
   而 jOOQ 按 varchar 绑定，PG 报错；**只在调用方真传了平台受理时刻时才炸**
   （上游测试恰好传 null 所以一直没暴露）。而平台受理时刻是时效倒计时的起点，
   这个洞让它永远写不进去 ⇒ 修上游一行 + 加注释说明根因。
2. **时间线分页对未知/跨租户 id 会 NPE**（`fetchOne` 返回 null 后直接 `.get`），
   NPE 冒到 HTTP 层是 500，调用方看到「服务器坏了」而不是「这张单不存在或不属于你」。
3. **时间线排序不全序会让分页漏条或重条** —— 同一事务里追加的多条备注共享同一个
   `created_at`（PG 的 `now()` 是事务局部），排序不全序时翻页可能漏一条或重复一条。
   ORDER BY 补足 `reference` 与 `summary`。
4. **时间线计数查询漏传 CTE 的两个绑定** → jOOQ 抛 `DataIntegrityViolation`，
   而 HTTP 层把约束类异常映射成 409，一个 GET 返回 409 会让人以为「数据冲突」，与根因毫无关系。

另有一处**跨模块断言的必然翻转**：11.1.1.1 的集成测试与前端 allowlist 测试原先断言
`cases/{uuid}` 返回 404，前提是「11.1.1.2 未交付」。前提已经不成立，改为断言放行 ——
继续断言 404 等于把一条真实存在的路由钉成「不存在」，任何人删掉它测试还会照绿。

## L2 批次闸：红 → 取证 → 判定 flake

**首跑**：后端 BUILD FAILURE，`trade` 模块 1 例报错 ——
`FulfillmentRequestIntegrationTest.完整走通一次履约_从建单到完成`，
`fulfillment_request_step` 违反 `check3`（`complete_fulfillment_step` 把
`reservation_id` 与 `outbound_order_id` 都留 null 就置 COMPLETED）。

**两项取证**：

1. **文件级因果独立**：本批合并 diff 未触碰任何 fulfillment 文件；
   本模块自己的 19 例在 L2 内全绿。
2. **定向复跑**：`-Dtest='FulfillmentRequestIntegrationTest'` 单独跑 **33 例全绿**
   ⇒ 判定为 flake（与 `.dev-agent/PIPELINE.local.md` 记录的 trade flake 历史一致）。

**其余 L2 项全部绿**：typecheck / lint 均 `0 cached, 3 total`（`TURBO_FORCE=true` 取真实结果，
避免命中别的 worktree 的 turbo 缓存条目）、vitest **3697 例全绿**、Next production build 绿、
台账 465 行零重复、`sync --check` 刷新后一致。

**共享白名单的静默失败面已核对**：本批改动 `apps/web/src/lib/api-proxy.ts` 为
**纯新增 37 行、删除 0 行**，且 11.1.1.1 的 `cases` / `cases/bulk` 条目（第 1080 行）
与本模块新增的 `cases/{uuid}` 条目（第 1054 行）**并存**，形状互不相交。
两条 allowlist 双向断言测试各自钉住互不吃 —— 这正是协议点名的「typecheck / lint / vitest /
Playwright 全绿但页面 404」那个静默失败面。

## 遗留：3 条死车道的处置（未动，等 08:00 决定接手还是放弃）

11.1.2.1 / 11.3.1.2 / 17.4.2.1 的成果**只存在于 worktree 工作区，分支上零提交**。
本轮**没有**清理、**没有**抢救提交、**没有**重派，理由：

- 距静默窗口（20:30）只剩十几分钟，任何「抢救 + 补 L0/L1 + 合入」的完整动作都做不完；
- 抢救出来的代码从未跑过任何验证，L2 又红着，把它塞进集成头等于把未验证代码合进去；
- `gc` 只回收 `merged` 车道，这 3 条 worktree 不会被自动清掉，成果暂时安全。

⚠️ **给下一轮的警告**：`state=running` 且心跳过期 ⇒ 它们对容量闸**不可见**，
但 `plan` 存在**复用既有分支与 worktree**的行为。若直接复用，新 worker 会在
「缺 4 个上游提交的旧基线 + 上一位死者的未跟踪文件」上开工，而它自己的 L1 还会全绿
（因为它验证的正是那个旧树）。**必须先决定接手还是放弃，再决定是否复用。**
它们各自还占着预分配的 Flyway 版本号 V158 / V159 / V160。

## 顺带发现的协议文档缺陷

PIPELINE.md 子任务模板里的 L1 命令写的是 `-DfailIfNoSpecifiedTests=false`。
在本仓 surefire **3.5.4** 下，配合 `-am` 构建依赖模块（`tenancy` 等没有匹配 `-Dtest` 的用例）
**仍然会构建失败**，错误信息明确要求 `-Dsurefire.failIfNoSpecifiedTests=false`。
本轮定向复跑第一次就是因此 exit 127 → 实际是 exit 1 的构建失败，不是测试失败。
模板建议同时带上两个属性名。**未在本轮改协议文档**（改动会污染本批 commit 语义），留给下一轮。

## 后续待办

1. **决定 11.1.2.1 / 11.3.1.2 / 17.4.2.1 接手还是放弃**，接手则走
   `wip(...)` 抢救 → 合入集成头 → 重新生成 trade/identity 契约 → 补 L0/L1 三层流程。
2. `fulfillment_request_step` 的 flake 需要单独立项：要么补 `complete_fulfillment_step`
   的前置校验（COMPLETED 时必须有 reservation 或 outbound），要么确认是测试数据构造问题。
3. 修 PIPELINE.md 的 surefire 属性名。
