# 2026-10-21 batch-54 —— 派发轮：工单管理 / 退货分析两条车道（无并入、L2 未跑）

> 批次 `batch-54`，集成头 `integration/module-dev`（本轮并入前 = 并入后 = `6d31c64d`）。
> **本轮是纯派发轮：0 条车道落地、0 条车道并入、L2 未运行。**
> 与 batch-42（撞闸）、batch-44（节流闸拦截）同型 —— 远端会多一条**纯文档**提交，
> 集成头的**代码内容一字未改**，仍是 batch-53 第三轮 L2 全绿的那份。
>
> ⚠️ **本轮没有「已交付模块」小节** —— 没有任何模块在本轮完成交付。
> 下文「本批派发」记录的是**已派出、尚在开发中**的两条车道的任务书要点；
> 它们落地后由**下一轮 tick 回收 → 并入 → 跑真正的 L2 → 写模块小节 → 随下一批 push**。

## 本轮闸门判定（全部现取，未引用历史文档数字）

| 闸 | 取值 | 判定 |
| --- | --- | --- |
| 静默窗口闸 | `SILENT_WINDOW=0` → 全天运行，不设时段闸 | 放行 |
| 强度档位 | 当前 `2026-10-21T21:00+0800` < `TOKEN_HORIZON=2026-10-25T00:00+0800` → **full 模式**（LANES=4 / THROTTLE=50） | 满档 |
| 批次节流闸 | `last-batch-at=2026-10-21T18:02:28+0800` → 距上批 **178 分钟** ≥ 阈值 50 分钟 | 放行（21:03 才写时间戳） |
| 温度闸 | `pmset -g therm` → `Note: No thermal warning level has been recorded` | 正常 |
| 配额闸 | `status` running = **4** 条（6.1.1.3 / 7.4.1.2 / 6.6.2.2 / 11.4.1.2）< `RUNNING_QUOTA=6` | 放行 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 = **1** < 6；**无标签孤儿容器 0 个** | 放行 |
| load 闸 | 三点采样 `4.25/4.16/3.30` → `4.75/4.27/3.36` → `4.27/4.20/3.36`（1min 远低于 `LOAD_LIMIT=12`，且**无爬升趋势**） | 放行 |

⇒ **容量闸 GO**，闸目录 acquire 成功（`gate acquire` 返回 `ok: true`）。

## 本批派发：2 条车道

**为什么只派 2 条而不是 `LANES=4`** —— `RUNNING_QUOTA=6` 是**在途车道（state=running）总上限**，
本地配置写得很明白：「在途车道（state=running）上限。超过即 STOP，零派发」。
本轮开工时已有 4 条在途（上一 tick 派出、worker 仍在写、心跳 3–22 分钟内），
**配额只剩 2 个位置** ⇒ 派 2 条正好把在途数填到 6，**不越配额**。

⚠️ 这不是把 `--lanes` 调回 1 的那种降档：**`--lanes=4` 仍然是本流水线的批次容量上限**，
是**用户设的并发配额（6）**先把可派名额压到 2。下一轮（22:00）上一批 4 条落地回收后，
在途归零，会恢复满档 4 条。

| 模块 | 名称 | 阶段 | L3 | 主责服务 | 迁移 | 分支 | worktree |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 11.4.2.1 | 工单管理 | D7 | 11.4.2 | trade | `V270` | `feat/11-4-2-1` | `.worktrees/11.4.2.1` |
| 11.5.1.1 | 退货分析 | D7 | 11.5.1 | trade | `V271` | `feat/11-5-1-1` | `.worktrees/11.5.1.1` |

两条车道 L3 锚点不同（11.4.2 / 11.5.1）⇒ 互不依赖；迁移版本号 V270 / V271 在
trade 服务未占用（trade 当前最大 V265）⇒ 不撞号。

**派发前冲突预检**：两条 worktree 与分支均不存在（不存在双 writer）；
V270/V271 在全部 6 个后端服务里都未出现 ⇒ 无版本号占用。

### 11.4.2.1 工单管理 —— 任务书要点

- 蓝图原文：`docs/product/capability-blueprint/11-aftersales-and-returns.md` 第 206–211 行
  （职责=非售后类客服工单：咨询/投诉/催单/平台政策咨询，支持分配、SLA 计时、转派、关闭、满意度评价；
  关系=与售后单互转，升级要保留上下文）。
- **关键约束（已写进任务书）**：`aftersales_service_ticket` 表**已经存在**，是 11.1.1.1 车道在 V156
  为本模块预留的（`owner_module` CHECK 恒等于 `'11.4.2.1'`，`UNIQUE(tenant_id, case_id)`
  = 一张售后单只转一次工单）。V270 必须**在预留表之上扩展**，不许重建同名表；
  若 `ALTER TABLE` 加列，**每列必须带 DEFAULT 或可空**（仓库真实事故：某车道把列改成 NOT NULL
  且无 DEFAULT，破坏了早先车道的 INSERT，各自单跑都绿、合并后炸）。
- 上游事实：`aftersales_case` / `aftersales_case_note` / `aftersales_owner_roster` /
  `aftersales_reply_sla`（V164，SLA 规则来源，整数小时口径）/ `aftersales_reason_category` /
  `platform_dispute`（投诉升级为纠纷的落点）。
- 已知缺口如实写进任务书：15 客户与客服的工单/消息模块大多未交付 ⇒ 「来自消息」的创建入口
  做成**带来源引用的显式入口**，不读不存在的消息表；核心职责做不出来就报 BLOCKED，不许写半成品。

### 11.5.1.1 退货分析 —— 任务书要点

- 蓝图原文：同文件第 220–225 行（职责=退货率按店铺/SKU/类目/原因/批次、退货金额趋势、拒退率、
  处理时效、平均退款金额与原因分布；输出=报表 + 维度下钻 + 异常识别；
  **关系=退货率必须与销售额同口径比较**）。
- 参考实现：**11.5.2.1 质量分析**（已交付）—— 后端 `QualityAnalysis{Models,Service,Controller}.java`、
  两个集成测试、`/aftersales/quality-analysis` 页面与 `components/aftersales/quality-analysis-*`，
  照抄结构与命名。
- 上游事实：`aftersales_case` / `aftersales_case_line` / `aftersales_reason_category` /
  `return_receipt(_line)` / `return_receipt_allocation(_line)` / `return_quality_inspection` /
  `return_inventory_posting` / `refund_application` / `platform_order`（V207）/
  `sales_order` + `sales_order_line`（V99，**退货率的分母口径用它，不许自己拼分母**）。
- 口径要求写死：比值用 `BigDecimal` 显式计算并定标度（禁 float/double/float8）；
  分子分母**同币种同时间窗**，跨币种要么按币种分组、要么明确报错，**不许静默相加**。

## 静态预检（5.1）

对本轮**并入区间**（V250–V269）的迁移跑「已知互相破坏形状」扫描：

- ① `ADD COLUMN` 无 `DEFAULT` —— **0 处真实命中**（扫描命中的 7 行全是迁移文件里的**说明注释**，
  内容是「没有 ADD COLUMN / SET NOT NULL」这句话本身）
- ② `SET NOT NULL` —— **0 处真实命中**（同上，注释）
- ③ `ADD CONSTRAINT ... CHECK` —— **1 处**：`warehouse/V261__inventory_adjustment_order_line_no.sql`
  的 `inventory_adjustment_order_line_no_positive CHECK (line_no > 0)`。
  该列在同一迁移里新增并带默认值，且**已在 batch-53 的 L2 全绿批次里验证过** ⇒ 不是本轮引入。

## 本轮为什么没有跑 L2（写清楚，避免下轮误判）

**L2 是「批次闸」，验的是「本批并入集成头的内容」。本轮并入内容为空**，集成头的代码与
batch-53 第三轮 L2 全绿时的字节一致（`6d31c64d`）。

更重要的是**协议明令禁止 L2 与在途车道的 L1 并发**：

> L2 不得与在途车道的 L1 并发跑 —— 多套 Testcontainers PG 互相抢资源，
> 2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，
> 后端 1364 例里 3 failures + 24 errors **全是假红**（`seed()` 503 / 连接拒绝），
> 机器空闲后隔离复跑 61 例全绿。**先等在途车道落地，再跑 L2**。

本轮开工时**在途车道 4 条 + 新派 2 条 = 6 条**，它们的 L1 正在并发跑 Testcontainers PG。
此刻跑 L2 恰好落在「成片假红」的已知形状上，且**验不出任何新东西**（无新内容）。
⇒ **本轮不跑 L2，把 L2 留给下一轮回收这两条车道之后**。这不是「L2 红了」，是「本轮无待验内容」。

**本轮 push 的内容只有文档**（本文件 + 台账行），**没有任何未经 L2 验证的代码进入远端**。

## 收尾三件套

| 环节 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-21-batch-54-dispatch-service-ticket-return-analytics.md`（无交付模块，故无模块小节） |
| ② commit | ✅ 与台账**同一个 commit**（`docs(batch-54):` 前缀，与 batch-21~53 惯例一致） |
| ③ push | ✅ `git push origin integration/module-dev` —— **仅本文件的文档提交**；集成头代码内容未变（= batch-53 L2 全绿状态） |

## 服务缺口：`serviceBlocked` —— 178 个未开发模块里有 78 个排不出来

`plan` 输出的 `serviceBlocked`（三条）：

| 卷 | 未开发模块数 | 原因 |
| --- | --- | --- |
| 12 财务核算 | 32 | 主责服务 `finance` **不在集成头**（`backend/services/` 下只有 catalog / channel / crm / identity / trade / warehouse） |
| 1 工作台与门户 | 23 | 主责服务「前端聚合」**不在集成头** |
| 13 利润与经营分析 | 23 | 主责服务 `reporting` **不在集成头** |

⇒ **当前真正可排队的只有 100 个**（178 − 78）。
这 78 个不是「没排到」，是**服务本身还没建**，调度侧无法绕过 —— 需要先把
`finance` / `reporting` 两个后端服务与「前端聚合」这一层并进集成头。
这与历史上「卷 09 / 11 主责 trade 在 trade 落地前一直排车道、一直注定 BLOCK」是同一种堵法，
治法一样：**先把服务建起来**，而不是改锚点绕开。

## 吞吐预期（现算，不引用任何历史文档里的写死数字）

- 档位：full（`LANES=4`）、cron `0 * * * *`（24 触发点/天）、节流闸 50 分钟
  ⇒ 名义批次 24/天、名义吞吐 4×24 = **96 模块/天**；按协议折损系数 ≈0.70 ⇒ 有效 ≈ **67/天**（理论上限）。
- **实测更可信**：按 ledger 的「已交付」净增，10-17 +9、10-18 +12、10-19 +10、
  10-20 +19、**10-21 +32**（当日已跑 21 小时）。取近两日 **19–32/天**。
- 剩余 **178** 个（其中可排队的 100 个、服务缺口 78 个）。
  按 19–32/天：**可排队部分约 3–5 天**；
  但 **78 个服务缺口模块不会随时间自动减少** —— 它们要等 `finance` / `reporting` /
  前端聚合层落地。**这是当前交付速度的真实天花板**，不是调度侧能靠加并发解决的。

## 后续待办

1. **下一轮 tick（22:00）回收本批 2 条车道**（11.4.2.1 V270 / 11.5.1.1 V271）：
   `merge-ready` → `sync` → **跑真正的 L2**（此时在途车道应已落地，不与 L1 并发）→
   写两个模块小节 → `docs(batch-55)` 同一个 commit → push。
2. **上一批 4 条车道**（6.1.1.3 V266 / 7.4.1.2 V267 / 6.6.2.2 V268 / 11.4.1.2 V269）：
   **收尾时 6.1.1.3（供应商评级）与 7.4.1.2（调整审批）已 `land` 成 `ready`**
   （心跳 2.8 / 13.4 分钟前，`landedAt` 21:13 / 21:05），另有 6.6.2.2、11.4.1.2 仍在跑。
   ⚠️ **本轮没有并入这 2 条**：并入而不跑 L2 就违反「集成头只接受绿批次」，
   而此刻还有 4 条车道的 L1 在并发跑 Testcontainers PG，此刻跑 L2 恰好落在已知「成片假红」形状上。
   ⇒ 这 2 条 `ready` 车道**留给下一轮 tick 一起并入 + 跑 L2 + 写模块小节**（batch-55）。
   `ready` 是稳定状态，不会有丢失风险；**不要**为了「早并早验」在有在途 L1 时抢跑 L2。
3. **`finance` / `reporting` 服务缺口**：78 个模块压在这两个服务上。
   需要决定是先建服务再排模块，还是把卷 13 的分析需求降级到 trade 侧自有事实（11.4.1.1 纠纷
   列表已经这么做过一次 —— 结算影响先记在 trade 侧、finance 落地后再经授权 API 对账）。
4. **`11.4.2.2 知识库与话术`（已交付）与本批 11.4.2.1 工单管理的转派/话术引用**：
   同服务内走授权 API，不要跨库读表。
5. **配额节奏**：本轮 4+2=6 填满配额。下一轮回收后若在途归零，恢复满档 4 条/批。

## ⚠️ 风险发现：共享契约文件与 CI 的校验口径存在偏差（**先于本批车道就存在**）

收尾时 `11.4.2.1` 车道交来 `packages/api-contracts/src/trade.openapi.json`，文本 diff 高达 **10 万行**。
逐键独立复核（base `6d31c64d` vs 车道 `87d2b41d`，用 JSON 语义比较而非文本比较）结论：

| 维度 | base | 车道版 | 新增 | 删除 | **值变** |
| --- | --- | --- | --- | --- | --- |
| `paths` | 720 | 734 | 14 | **0** | **0** |
| `components.schemas` | 1577 | 1603 | 26 | **0** | **0** |

⇒ **纯新增**，没有删除、没有既有条目被改。10 万行来自 **JSON 键序整体位移**
（新增 14 条 path 后对象顺序整体移动，codegen 原样落盘）。

**但真正的问题在数字字面量**，已实测复现：

```
node   JSON.stringify({maximum:100.0})  →  {"maximum":100}    ← 塌掉小数
python json.dumps({"maximum": 100.0})   →  {"maximum": 100.0} ← 保留
```

- 集成头 HEAD 版 `trade.openapi.json`（由 **`merge-union.mjs` 的 JS `JSON.stringify` 写出**）
  形态是 `0` / `1` / `100`；
- 各车道用 `pnpm contracts:generate`（**Python codegen**）生成，形态是 `0.0` / `1.0` / `100.0`；
- CI（`.github/workflows/ci.yml:140-141`）跑 `pnpm contracts:generate` 后接
  `git diff --exit-code -- packages/api-contracts/src`。

⇒ **CI 期望 Python 形态，集成头存的是 JS 形态 ⇒ 该校验在集成头上必红。**
这不是本批车道引入的（HEAD 早已是 JS 写出的形态），而是**合并驱动器与 codegen 的序列化差异**。

**下一轮（batch-55）合并 5 条车道时的处理建议**：

1. 5 条 ready 车道里有 **3 条**（6.6.2.2 V268 / 11.4.1.2 V269 / 11.4.2.1 V270）都重写了
   `trade.openapi.json`，各自 ~5 万行 diff ⇒ **git 层面必然冲突**，必须由 `merge-union.mjs`
   按「paths 键 / schema 名」三方求并（它走 `JSON.stringify` 逐键比较，数字字面量差异会被
   判为「theirs 相对基线变了」而取 theirs，**不会**进 `conflictKeys`，实测这条路径是安全的）。
2. 合并**之后**在集成头上补跑一次 `pnpm contracts:generate`，把文件形态统一回 CI 期望的
   Python 原生形态，再跑 L2 —— 否则 CI 仍会因为数字字面量形态不一致而红。
3. 根治方案（需要人工决策，不在调度侧权限内）：让 `merge-union.mjs` 写出时用与 codegen
   一致的序列化（Python `json.dumps` 或 JS 里对 number 做显式格式化），否则每次合并都会
   把契约文件重新写成 JS 形态，与 CI 校验长期拉锯。
