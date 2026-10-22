# 16.5.1.2 风险评分（阶段 D7 · L3 锚点 16.5.1 买家风控）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/16-5-1-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.5.1.2`）
- **基线**：`integration/module-dev` @ `69a76998`
- **迁移版本号**：`V316__channel_buyer_risk_score.sql`（预分配）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 210-216 行）：

> ### 16.5.1.2 风险评分
>
> - 职责：计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，输出评分与建议动作。
> - 输入：买家行为数据；风险特征规则；权重配置。
> - 输出：风险评分 + 特征解释。
> - 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
>   **评分要能解释**（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。

本车道的落地要点：

1. **「评分要能解释」落成三张表的关系。** `buyer_risk_score`（头：分数 / 建议动作 / 规则版本快照 /
   判定基准时刻 / 统计口径）、`buyer_risk_feature`（**逐特征贡献明细**：特征码 / 原始值 / 标准化值 /
   权重快照 / 贡献分 / 人话理由 / 量不到时的归属模块）、`buyer_risk_signal_writeback`（回写凭据）。
   「为什么这个买家 80 分」必须能**逐条**答出来。
2. **贡献分之和 = 总分由延迟约束触发器在数据层钉死。** `buyer_risk_feature_sum` 是
   `DEFERRABLE INITIALLY DEFERRED` 的约束触发器：出了分而分项加起来对不上 ⇒ 23514；
   信号不足时贡献分之和必须为 0 —— **不许出现「半个分数」**。
3. **主体键选 `buyer_key`（收件人三件套的 md5），不选 `customer_party_id`。** 理由写进能力声明：
   `customer_party_id` 是 catalog 的 party.id（**B2B 主体**口径），平台 B2C 买家绝大多数**没有** party_id，
   用它会漏掉绝大多数买家；crm 服务**本仓没有买家档案**（它自己的迁移注释写明 `customer_ref` 只是逻辑引用），
   所以也**不把 crm 的 customer_ref 当买家主键**。公式与 trade 的 `order_review_buyer_profile.buyer_key`
   **逐字同一**：`md5(utf8(姓名 ‖ U+001F ‖ 电话 ‖ U+001F ‖ 邮箱))`，分隔符用**不可见字符**而不是 `'|'`
   （`'|'` 会让两组不同字段拼出同一个串 —— 那是一次跨买家的错误合并）。
4. **「量不到」与「量为 0」彻底分开。** 读不到的维度**不进分母、不算通过、贡献分是 NULL**，
   界面上显示「未知」+ 量不到的原因 + 归属模块。**绝不用 0 冒充** ——
   0 会被读成「这买家很干净」，而真相是「我们没查到」。
5. **纠纷率本轮量不到，如实标。** trade 的 `platform_dispute` 既没有 `order_id`、
   也没有任何买家标识，只有一个自由文本 `platform_order_no` ⇒ 该维度 `available=false` +
   归属 11.4.1.1，**不参与加权**，结论里显式写「纠纷率未计入」。
   **不用字符串匹配凑数，也不用 0 冒充「纠纷率 0」。**
6. **退款率走「订单 → 买家」两级关联。** 先按 `buyer_key` 找到订单集合
   （逐单用 `order_address_snapshot` 实算 buyer_key），再按 `order_id` 统计退款
   —— 退款表上只有 `order_id`、没有买家列。
7. **权重租户可配，合计钉死 1.0000**（DB 层 CHECK，照 trade 的 `order_risk_rule` V150 同一做法）；
   **缺信号口径不给默认值**（`SCORE_KNOWN_ONLY` / `BLOCK_EVALUATION` 由租户显式选；
   选 `BLOCK_EVALUATION` 时**不出分**，只给「建议人工复核」）。
8. **判定凭据 append-only。** 评分 / 特征明细 / 回写凭据三张表都有 `BEFORE UPDATE OR DELETE` 触发器；
   **权重改了不追溯改变历史评分**（旧行原样留着，新评分另起一行）。
9. **建议动作受控枚举** `RELEASE` / `MANUAL_REVIEW` / `HOLD`，**只出建议、不改订单状态**
   （改订单是 9.2 的事）、**不写黑名单**（16.5.1.1 未交付）、**不编关联账号图谱**（16.5.2.2 未交付）。
10. **回写 9.2.2.2 尽力而为。** 能读到时把 `REFUND_RATE` 回写
    `POST /trade/orders/risk-validation/signals`（`subjectKind=BUYER_FINGERPRINT`、
    `signalSource` 固定 `TENANT_MAINTAINED` —— `MODULE_*` 两个预留槽是别人的）；
    **回写失败不阻塞交付**（降级为「未回写」+ 原因，那个端点是用户权限端点，
    调用方是否真有 `trade.orders.update` 未经运行时验证）；
    **买家综合分留在本服务**（trade 的 `signal_code` 枚举里没有这个码，**不改 trade 的枚举**）。

## 关键 trade-off

| 决策点                                        | 取舍                                 | 理由                                                                                                                 |
| --------------------------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| 主体键：`customer_party_id` vs `buyer_key`    | **`buyer_key`（md5 三件套）**        | 平台 B2C 买家绝大多数没有 party_id，用 party_id 会漏掉绝大多数买家；crm 没有买家档案，只能当辅助线索                 |
| 纠纷率：平台订单号模糊匹配 vs 标「量不到」    | **标「量不到」**                     | `platform_dispute` 没有订单 / 买家 join 键，凑出来的纠纷率**看似有数、实则不可信**；拿 0 冒充「纠纷率 0」更危险      |
| 关联账号：自己建一张表 vs 标「量不到」        | **标「量不到」，且刻意没有权重列**   | 16.5.2.2 未交付；一个**永远量不到**的维度占着权重会稀释分数（量不到的维度不进分母）                                  |
| 缺特征：折成 0 vs 留空并排除出分母            | **留空（NULL）+ 不进分母**           | 「没数据」说成「这买家很干净」是评分系统最致命的一种撒谎                                                             |
| 缺信号口径：给默认值 vs 租户显式选            | **租户显式选**                       | 两者差着一个真实后果（前者放行、后者攒人工队列），照 `order_risk_rule.missing_signal_policy` 的做法                  |
| 订单集合不完整（有 N 张订单的收货地址读不到） | **三个订单派生维度全部标「量不到」** | 拿不完整的集合算出来的退款率会偏低，不能当成结论；宁可说不知道                                                       |
| 评分证据：可改 vs append-only                 | **append-only（DB 触发器）**         | 评分是判定凭据；权重改了只能**新起一次评分**，不能就地改历史行                                                       |
| 回写失败：阻塞交付 vs 降级                    | **降级为「未回写」+ 原因**           | 回写端点是**用户权限端点**（不是签名的服务间内部 API），调用方是否真有那个权限未经运行时验证                         |
| 跨服务读法：直连 trade 的表 vs 授权 HTTP API  | **只经授权 HTTP API**                | AGENTS.md 模块边界纪律；channel 与 trade 是两个独立库                                                                |
| 表外键：建 FK vs 零外键 + 触发器              | **零外键 + 触发器校验**              | channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503（与 V300 / V308 / V312 同一纪律）      |
| 数据范围：整表租户级 vs 店铺主体收敛          | **租户级（只带 isolation 策略）**    | 买家是**租户级主体**，不是某家店 / 某个部门的资产；硬套店铺收敛会让「这个买家在跨境店铺下的历史风险」凭空消失        |
| 规则读取：没登记就 404 vs `configured=false`  | **`configured=false`**               | 界面必须能同时呈现「还没登记规则」与「登记规则」两种状态，404 会让它误读成「读失败」；返回体里**不预填任何默认权重** |
| 查询串里的时间：本地时区串 vs UTC 串          | **一律换算成 UTC**                   | ISO 串里的「+08:00」带一个 `+`，`+` 在查询串里是空格 ⇒ 上游会收到解析不了的字面量，400 掉整条查询                    |

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V316__channel_buyer_risk_score.sql`（四张新表 + RLS + 授权 + 触发器）
- `backend/services/channel/src/main/java/com/starcon/channel/BuyerRiskScoreModels.java`（DTO）
- `backend/services/channel/src/main/java/com/starcon/channel/BuyerRiskScoreRules.java`（**纯计算**：归一化 / 汇总 / 贡献分）
- `backend/services/channel/src/main/java/com/starcon/channel/BuyerRiskScoreSourcePort.java`（trade 只读端口 + 回写）
- `backend/services/channel/src/main/java/com/starcon/channel/BuyerRiskScoreService.java`（服务：聚合 / 落库 / 查询）
- `backend/services/channel/src/main/java/com/starcon/channel/BuyerRiskScoreController.java`（端点）

### 测试

- `backend/services/channel/src/test/java/com/starcon/channel/BuyerRiskScoreIntegrationTest.java`（17 例）
- `backend/services/channel/src/test/java/com/starcon/channel/BuyerRiskScoreTenancyIntegrationTest.java`（3 例 · 多租户三断言）
- `backend/services/channel/src/test/java/com/starcon/channel/BuyerRiskTradeStub.java`（本地 trade 服务，**真 HTTP 服务器**，不是 mock）

### 前端

- `apps/web/src/app/compliance/buyer-risk/page.tsx`
- `apps/web/src/components/buyer-risk/buyer-risk-workspace.tsx`
- `apps/web/src/components/buyer-risk/buyer-risk-model.ts` + `buyer-risk-model.test.ts`（6 例）
- `apps/web/src/app/api/channel/buyer-risk-scores-allowlist.test.ts`（6 例 · 白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**新增一条 `buyer-risk-scores` 的 `services[]` 元素，插在数组末尾）

### 契约 / 台账

- `packages/api-contracts/src/contracts/channel.ts` + `packages/api-contracts/src/channel.openapi.json`（codegen 产物，**手改即破坏契约**）
- `docs/product/capability-blueprint/module-progress.md`（**只改 16.5.1.2 那一行的「状态」「证据」两格**）

## 后续待办

- **11.4.1.1 平台纠纷补 join 键**（`platform_dispute` 加 `order_id` 或买家标识）⇒
  `DISPUTE_HISTORY` 维度立刻可算，`buyer_risk_rule.weight_dispute_history` 的权重位已留好，**结构不用改**。
- **16.5.1.1 黑名单交付后**：本模块读到的 `buyer_level` / `blacklisted` / `blacklist_reason`
  改成从黑名单读，并在能力声明里把 16.5.1.1 从「未交付」改成「已交付」。
- **16.5.2.2 关联风险交付后**：「关联账号」维度带着自己的维度码与权重**在后续迁移**里进来
  （现在刻意没有权重列）。
- **订单扫描的读放大**：`GET /trade/sales-orders/list` 之后要逐单取一次收货地址快照
  （trade 没有「按买家键列订单」的读面）。本轮用**统计窗口**（默认 180 天）把它框住，
  窗口与「有 N 张订单读不到 ⇒ 三个维度量不到」的严格口径都在能力声明里写明。
  若将来订单量级上来，应由 trade 补一个按买家键列订单的端点，而不是在本服务里加缓存。

## 关联

- 蓝图：[`docs/product/capability-blueprint/16-compliance-and-risk.md`](../docs/product/capability-blueprint/16-compliance-and-risk.md)
- 相邻已交付：16.3.1.1 税号与辖区（`docs/sessions/2026-10/2026-10-23-tax-registration-jurisdiction.md`）、
  16.2.2.1 合规预检（同卷「量不到 ≠ 0 分」纪律的来源）
- 参考口径：trade 的 9.2.2.2 风控校验（`V150__trade_order_risk_validation.sql`）——
  权重合计 CHECK、`missing_signal_policy`、信号枚举与「不许改别人已交付服务的 DDL 面」四条都是照它抄的
