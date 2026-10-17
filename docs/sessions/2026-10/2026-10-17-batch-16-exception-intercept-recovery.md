# 批次 16 — 异常拦截交付 + 11:00 配额中断抢救

> 会话：2026-10-17 15:00 tick · 集成头 `4225d99b` · 上一批基线 `a052a025`
> 本批只交付 **1 个模块**；另 3 条车道的成果处于不同状态，见「未交付部分」。

## 进度

| 指标 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | **179**（38.5%） |
| 进行中 | 0 |
| 未开发 | **286** |

---

## 模块 9.2.1.3 异常拦截

**这个功能做什么**：运营在订单审核页能看到一张订单被判出哪几类异常（地址不全、黑名单买家、重复下单、
异常折扣、疑似欺诈、超出收货国家限制、平台合规风险），每条命中带证据；处置只能由人工做，
系统**不自动取消**任何一单 —— 取消的唯一入口是 `POST /checks/{id}/disposition`。

**技术落地**：

- 迁移：`V153__trade_order_exception_intercept.sql` — 六张表（policy / restriction / sku_cost /
  check / finding / disposition）+ 11 个端点。**零 ALTER 既有表**：sales_order 簇（V99）、
  order_review_*（V149）、order_risk_*（V150）全部只读 ⇒ 跨车道 DDL 回归面 = 0。
  六张表全部 ENABLE + FORCE ROW LEVEL SECURITY。
- 页面：`/orders/review/intercept` — 策略、限制清单、成本基线、检查清单、检查详情、人工处置留痕。
- 测试：后端 38 + 5 例（集成 + 租户），前端 13 + 8 例。
- 详细设计决策与踩坑记录见 `docs/sessions/2026-10/2026-10-17-9-2-1-3-exception-intercept.md`。

**关键设计决策**：「拦截不自动取消，默认转人工」落成五层 ——
`final_disposition` 词表不含任何「自动」取值；表级 CHECK 保证「建议取消」也必须携带「需人工处置」；
DB 触发器要求最终处置必须已有留痕、且人工处置只挂在需人工处置的检查上；服务层**没有任何一处**写
`finalDisposition`。派生的一条同样落进 DB：**一条命中都没评上时不出「放行」**，只给「转人工」。

---

## 未交付部分（重要）

本批 11:00 那一轮派发的 4 条车道中，**只有 1 条走完了流程**。其余 3 条的状态各不相同：

### 事故：11:00 批次被 Token Plan 配额切断

11:00 tick 派发 4 条车道后，其父调度会话在 **11:36 因 `42212`（已达到 Token Plan 用量上限）被切断**，
没来得及执行 `merge-ready` / L2 / push。此后 **12:00、12:30、13:00、13:30、14:00、14:30 共 6 个 tick
全部在同一处配额墙上一秒内暴毙** —— 六个 tick 零产出。

**为什么台账上看不出来**：这些 tick 死在做任何事之前，`lanes.json` 里没有留下痕迹；
`lanesRunning` 只统计心跳新鲜的条目，于是两条死掉的车道在容量闸眼里**等同于不存在**，
闸显示「空闲」，而机器实际上一小时多无法工作。

**险些永久丢失的成果**：两条车道的 worker 做到接近完成（各写了会话日志，说明已到收尾阶段），
但成果**只存在于 worktree 的未跟踪/已修改文件里**，分支上一个提交都没有。
任何一次 `git worktree remove` 都会永久删除它。本轮的处置是**先把成果提交到各自车道分支**
（`wip(...)` 提交，明确标注不作为交付），再考虑并入。

### 三条车道当前状态

| 车道 | 状态 | 成果位置 | 处置 |
| --- | --- | --- | --- |
| 11.1.1.1 售后单列表 | `conflict` | 分支 1 个提交（worktree 干净） | 与 9.2.1.3 在 `api-proxy.ts` 冲突，待解 |
| 9.3.3.2 履约流程监控 | `running`（心跳 11:27，已失效） | 分支 3 个提交（含本次抢救提交） | 缺 L1 全量验证 + 契约重生成 |
| 9.5.2.1 履约质量 | `running`（心跳 11:28，已失效） | 分支 1 个提交（本次抢救提交） | 缺 L1 全量验证 + 契约重生成 |

⚠️ **这三条都不要清理 worktree**，成果虽已落库但尚未通过 L1。

**关于 11.1.1.1 的冲突**：两侧都是往 `apps/web/src/lib/api-proxy.ts` 的 `services[]` 里**新增一条
独立元素**（不是并进既有 trade 单行 alternation），插在同一个锚点。两条路径前缀互不相交
（`order-intercept` vs `aftersales`），`resolve()` 按顺序首个命中即返回 ⇒ **正确解法是两个元素都保留**。
按协议本轮不强行解冲突，已留给后续处理。

---

## 本轮验证（L2，集成头上全量）

| 项 | 结果 |
| --- | --- |
| `cd backend && ./mvnw verify` | **BUILD SUCCESS**，13:41，7 个 Maven 模块全 SUCCESS |
| `pnpm typecheck` | 3/3 successful，`Cached: 0 cached`（强制绕过 turbo 缓存） |
| `pnpm lint` | 3/3 successful，`Cached: 0 cached`（仅既有 warning） |
| `pnpm test` | **200 文件 / 3600 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功 |
| `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 |
| 台账完整性 | 465 行、零重复 ID |

⚠️ **typecheck / lint / test 一律加 `TURBO_FORCE=true` 跑**：并行车道场景下 turbo 缓存可能命中
**别的 worktree** 的产物，「跑过了」不等于「验证的是这份代码」。本轮确认 `Cached: 0 cached`。

`sync --check` 首次报红的原因是：9.2.1.3 的提交更新了自己那一行的状态与证据，但汇总计数
（已交付 178 / 完成率）没跟着刷新。跑一次 `sync.mjs` 后一致，且 **worker 写的证据原文未被覆盖**。

## 静态预检

本批四份迁移（V153–V156）全部为纯 `CREATE TABLE` + `CREATE TRIGGER` + RLS 启用，
**零 `ADD COLUMN`、零 `SET NOT NULL`、零 `ADD CONSTRAINT ... CHECK`** ⇒ 无「已知互相破坏形状」命中。

## 后续待办

- 解 11.1.1.1 的 `api-proxy.ts` 冲突（两个 `services[]` 元素都要保留），验证后 land。
- 9.3.3.2 / 9.5.2.1 补 L1 全量 trade 测试 + 契约重生成后 land。
- 配额是当前吞吐的实际瓶颈，不是容量：事故全程机器 load 约 2.4/10 核、therm 无降频。
