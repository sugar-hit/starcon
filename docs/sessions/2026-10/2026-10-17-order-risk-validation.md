# 2026-10-17 9.2.2.2 风控校验（trade 服务）

## 决策摘要

蓝图 09 卷 9.2.2.2：多信号风险打分（买家历史、设备与 IP 关联、下单频次、金额异常、地址集中度），
输出风险分与建议动作（放行 / 人工 / 拒发）。上游 15 客户 + 16 合规，下游 9.2.1.3。

三条决定本模块形态的判断：

1. **上游两维只能落成租户可维护表。** 集成头 `20ccd017` 实查：卷 15 的 24 个 L4 模块
   **只交付了 1 个**（15.4.1.1 知识库，在 `backend/services/crm`，独立库 `starcon_crm`），
   卷 16 的 24 个 L4 **全部未交付**。两者都是别的微服务的独立库，按 AGENTS.md 模块边界纪律
   **禁止跨服务 SQL / FK / 视图**，所以本服务永远读不到它们的表。照抄 V116
   `stock_source` / `cost_source` 与 V92 `authorization_source` 的既有解法：信号落成
   `order_risk_signal` 真实表，每行强制 `signal_source` 枚举与 `captured_at` 采集时刻；
   `capabilities` 显式报 `crossServiceReadAvailable=false` 与每维 `upstreamDelivered=false`。
   正式接口到位后由同步器按同一形状写入并改来源枚举，**表结构与打分逻辑都不用改**。
2. **另三维是真 SQL，不是占位。** 蓝图输入句里的「订单聚合特征」本服务接得通：
   下单频次 / 金额异常 / 地址集中度全部直接查 `sales_order` 簇现算（V144 合单同一套
   买家与地址指纹口径）。所以本模块不是「两个维度靠登记、另外三个靠占位」。
3. **蓝图加粗句做了四层，不是一层。** 「风控分数不能单独作为拒绝依据，需人工复核，
   避免误伤正常复购客户」——任何单层都能被绕过，所以四层同时成立（见下）。

## 关键 trade-off

- **缺失信号怎么算？** 两种口径都落库由租户选（`SCORE_KNOWN_ONLY` / `BLOCK_EVALUATION`），
  不给默认值：两者差着一个真实的运营后果（前者继续放行，后者攒人工队列）。
  共同点是**归一化只在已评分维之间做** —— 缺失维的权重不参与分母。否则「五个维度缺两个」
  会把总分稀释成一个看起来安全的低分，那比误伤更糟：**放行了一个没人看过的单**。
  `BLOCK_EVALUATION` 下 `risk_score` 留 `NULL` 而不是出一个残缺的分：残缺的分会被下游
  当成「量过了，结论是低分」。
- **「一条都没登记」与「登记了但过期」分不开吗？** 状态取**更强的那个**（`MISSING_SIGNAL`），
  过期那一项在 `explanation` 里点名。理由：「没登记」只能靠补登记解决，「过期」靠重新采集，
  把后者报成前者会让处置动作指错方向；反过来把前者报成后者则会把「压根没数据」说成「数据旧了」。
- **同一张单能不能重复评估？** **允许**，不建 `(order_id, evaluated_reference_at)` 唯一约束。
  「之后补登了一条采集时刻早于订单的历史信号」是合法的**新事实**，该产生一条新留痕而不是被静默丢弃。
  确定性靠的是**基准时刻钉死 + 同键信号取确定的那一条**（`captured_at desc, id desc`），
  不是靠禁止重复评估。测试逐条断言两次评估的 `risk_score` 与五个组件逐位相同。
- **打分放 DB 还是放服务层？** 放 DB（`order_risk_apply_totals` 触发器，第五个组件落下时算）。
  服务层算一遍、触发器再算一遍，早晚有一次两边不一致，而「评估上写着一个总分、它与五个组件
  算出来的不一样」是无法排查的。代价：触发器必须是 `SECURITY DEFINER`（第 8 节对合计相关列
  做了列级 `REVOKE UPDATE`），但 `FORCE ROW LEVEL SECURITY` 对 owner 同样生效，隔离一道不少。
- **买家指纹带 `P:` / `A:` 前缀。** 形状与 V144 `order_consolidation.buyer_fingerprint` 同一口径
  （带前缀不是装饰：两个来源的买家标识可能撞上）。而信号表 `subject_key` 存**裸值**，
  查信号时必须剥掉前缀 —— 这一处不一致是本模块实测踩到的一个真坑。

## 四层「分数不能单独拒发」

1. `order_risk_assessment` 表级 CHECK：`final_action IS NULL OR manual_review_required`
2. `guard_order_risk_final_action` 触发器：`final_action` 非空时**必须已存在**一条
   `order_risk_review`（分也写不出最终动作）
3. `guard_order_risk_review_requires_manual` 触发器：复核记录只允许挂在
   `manual_review_required = true` 的评估上（给「放行」的单补一条「同意拒发」是反方向越权）
4. **接口层没有任何端点能只凭分数改写 `final_action`**：唯一入口是
   `POST /assessments/{id}/review`，且要求已认证的人工成员身份

api-proxy 也配合：刻意**不放行** `assessments/{uuid}/reject` 与 `/decision`。

## 文件清单

迁移：
- `backend/services/trade/src/main/resources/db/migration/V150__trade_order_risk_validation.sql`

后端：
- `backend/services/trade/src/main/java/com/starcon/trade/OrderRiskModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderRiskService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderRiskController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/OrderRiskIntegrationTest.java`（25 条）
- `backend/services/trade/src/test/java/com/starcon/trade/OrderRiskTenancyIntegrationTest.java`（5 条）

前端：
- `apps/web/src/app/orders/risk-validation/page.tsx`
- `apps/web/src/components/orders/order-risk-model.ts`
- `apps/web/src/components/orders/order-risk-workspace.tsx`
- `apps/web/src/components/orders/order-risk-model.test.ts`（13 条）
- `apps/web/src/app/api/trade/order-risk-allowlist.test.ts`（5 条）

共用（各自只动自己那一处）：
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，未碰既有 trade 分支）
- `packages/ui/src/lib/task-pages.ts`（只加 9.2.2.2 一条）
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json`（codegen 产物）

## 过程中被自己的迁移与触发器抓到的四个真 bug

留档是因为它们都是「写得对、但第一次跑就炸」的那一类：

1. **延迟约束触发器不能看 `NEW`。** `AFTER INSERT OR UPDATE ... DEFERRABLE` 把事件排队时，
   队里存的是 INSERT 那一刻的 `NEW`（`totals_computed_at` 还是 NULL），而合计要到第五个组件
   落下才回填 —— 直接看 `NEW` 会让每一次**正常**评估都在提交时误报「从没被算过合计」。
   改成回表读当前行。
2. **合计不能挂在头行 INSERT 上。** 服务层是「先插头 → 再写 5 行组件」，头插入那一刻
   组件一张都没有，挂上去只能算出「0 个维度」。
3. **「必须已满 5 行」不能每行都断言。** 写在那条触发器里等于让本模块自己的分 5 次 INSERT
   的写入路径永远走不通。完整性由提交期约束保证（算过才有 `totals_computed_at`）。
4. **跨时钟比较。** 服务层用 JVM 时钟写 `created_at`、触发器用 DB 的 `now()` 写
   `totals_computed_at`，两台钟一比就违反 CHECK。改成这几个时间列一律由 DB 自己拥有。

另有一个 SQL 细节：`subject_key` 是 varchar，写 `subject_key = ?::uuid` 会报
`operator does not exist: character varying = uuid` —— 转换必须加在**列**那一侧。

## 后续待办

- **9.2.1.3 审单拦截**（本模块下游，未交付）：本模块**刻意没建**它的拦截表，
  只输出建议动作。拦截落地后读 `recommended_action` 与 `final_action` 两个字段即可 ——
  两者分开存就是为了让下游无法「只看分数就拒发」。
- **9.2.2.1 地址与支付校验**（同 L3，未开发）：与本模块共用「订单聚合特征」这套真 SQL 口径，
  但判定维度完全不同（地址合法性 vs 地址集中度），不复用表也不复用打分逻辑。
- **15 客户 / 16 合规交付后的同步器**：按 `order_risk_signal` 同一形状写入，
  把 `signal_source` 改成 `MODULE_15_CUSTOMER_PROFILE` / `MODULE_16_RISK_LINKAGE`
  并补 `source_reference` 即可，**表结构与打分逻辑都不用改**。
- **10 财务的汇率来源**：当前金额异常**只在同币种内**比较（同币种历史不足时判
  `INSUFFICIENT_HISTORY`，不拿别的币种凑）。跨币种归因要等汇率来源落地。
- **D5 平台单同步**：当前买家指纹优先用 `sales_order_party_snapshot.customer_party_id`，
  退回收货地址快照 md5。平台单同步落地后若带来更多身份列，可再评估口径。
