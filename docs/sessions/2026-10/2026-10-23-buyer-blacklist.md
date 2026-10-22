# 16.5.1.1 黑名单（阶段 D7 · L3 锚点 16.5.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/16-5-1-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.5.1.1`）
- **基线**：`integration/module-dev` @ `e1b808e`
- **迁移版本号**：`V318__channel_buyer_blacklist.sql`（预分配；同批另外三条车道占用 V317 / V319 / V320）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 203-208 行）：

> ### 16.5.1.1 黑名单
> - 职责：维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），下单时自动标记并可拦截。
> - 输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
> - 输出：黑名单记录；订单拦截标记。
> - 关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。**黑名单应支持申诉解除**，永久不解除会让误判的正常买家永久损失。

本车道的落地要点：

1. **黑名单按「判定凭据」而不是「普通业务表」设计。** 登记行 `buyer_blacklist_entry`
   **append-only**（BEFORE UPDATE OR DELETE 触发器直接 RAISE）—— 原始登记（谁、何时、为什么、
   什么范围、什么等级）永远是当时的原样；解除 / 升级 / 申诉裁决一律**追加**到
   `buyer_blacklist_status_event`，当前状态由「最新一条事件」派生。
2. **加粗句「黑名单应支持申诉解除」的物理落点有三层。**
   (a) 解除**必须留痕**（`buyer_blacklist_status_event`：actor + 理由 + 时刻，reason 必填）；
   (b) 申诉是**一张单子**（`buyer_blacklist_appeal`：谁提交 / 何时 / 什么理由 / 被谁裁决 / 裁决理由），
   **裁决只许走一次**（PENDING→APPROVED/REJECTED，触发器钉死），**事实部分不可改**；
   (c) 延迟约束保证「申诉已批准 ⇒ 必配一条 APPEAL_APPROVED 事件」，杜绝「申诉过了、黑名单还生效着」。
3. **生效范围是受控值域，且参与判定。** DB 层 CHECK 钉死 `SHOP_ONLY` / `ALL_SHOPS`
   并与 `shop_code` 互相咬死（全店铺 ⇒ 店铺必空）；店铺必须**真实存在且属于当前租户**
   （触发器校验）—— 挂在一个不存在的店铺上的黑名单**永远不会生效**，而运营会以为「已经拉黑了 A 店」。
   判定按范围过滤，**不只按 tenant 过滤**。
4. **读不到 ≠ 没风险。** 9.2 审单（trade 的 `order_review_buyer_profile`）**只经授权 HTTP API 只读**；
   上游不可达一律 **503**，绝不返回「这个买家没有风险」；上游正常但查无此人时按「未知」处理
   （**无记录 ≠ 风险为 0**）。
5. **只出建议，不改订单。** `recommended_action` 只有 BLOCK / REVIEW / WATCH。
   真正执行拦截是 **9.2.1.3**（trade，台账 D4 已交付）的职责，开售后单是 **11.1.1.1** 的职责 ——
   两者在能力声明里逐条 `available=false` + `ownerModule`，**不代劳、不编造指向它们不存在入口的放行形状**。
6. **订单拦截标记是时点事实。** `buyer_blacklist_order_flag` append-only：下单那一刻命中就落一行，
   后来解除黑名单**不改写**这一行（那会是一次事后改写事实）。

## 关键 trade-off

| 决策点 | 取舍 | 理由 |
| --- | --- | --- |
| 上游 `buyer_risk_score.blacklisted` / `blacklist_reason` 两列 | **不写**（自建登记表） | 实读上游代码（`BuyerRiskScoreService` 558-559 行）确认这两列是**评分时刻从 trade 画像抄来的快照**，不是给本模块的回写位；且 V316 给整张表挂了 `BEFORE UPDATE OR DELETE` 的 append-only 触发器，**任何 UPDATE 都会被直接 RAISE**。要写它就得 ALTER 上游的触发器 = 跨车道 DDL 回归面。⇒ 本模块自建登记表承载权威事实，将来由 16.5.1.2 自己来读（同服务同库 jOOQ 直读） |
| 主体键 `buyer_key` 的 md5 公式 | **复用** `BuyerRiskScoreSourcePort.buyerKey`（同服务同包），不另写一份 | 「同一个人的判定必须一致」是这几条模块之间最要紧的不变量；两份 md5 早晚会漂移一次，而漂移的后果是「9.2 拉黑了这个人、16.5.1.1 判定不是黑名单买家」这种无法排查的**漏拦** |
| 登记的当前状态：落在登记行 vs 派生 | **派生**（最新一条事件） | 落在登记行就意味着解除要 UPDATE 登记行 —— 登记行是判定凭据，事后改写它比拉黑本身更危险 |
| 删除 vs 解除 | **只解除，不删除** | 蓝图要求「支持申诉解除」；删除等于把历史抹掉，而误判的正常买家会永久损失。页面上按钮叫「解除（留痕）」，**没有删除入口**（后端也没有 DELETE 端点 ⇒ 405） |
| 同一买家同一范围下多条黑名单 | **只允许一条生效中** | 「这个买家在这个范围已经拉黑了」必须是一个确定答案；重复生效会让「谁拉黑的、为什么」说不清。用**延迟约束触发器**实现 ⇒ 一个事务里「登记新的 + 解除旧的」能一起提交 |
| 风险等级能否就地降低 | **只升不降** | 降级是「不再认为他有问题」，那属于**解除**（留痕）而不是「改个数字」。DB 层触发器钉死 |
| 跨服务读 9.2 的失败口径 | **503**（不是空集合） | 本模块的产出是「这个买家被拉黑了 / 没被拉黑」；把「读不到」折成「没风险」是一次真实的欺诈损失 |
| 9.2 查无此人 | **允许登记** + 标「未知」 | 还没下过单的买家也可能被拉黑；「查无此人」是**未知**，不是「干净」 |
| 拦截标记是否可撤销 | **不可撤销**（append-only） | 它是「当时被标记过」这个事实；后来解除了黑名单也不该改写它（误拦放行是 9.2.1.3 的人工处置闭环） |
| 下单检查是否读 9.2 | **读** | 调用方是 9.2 / 9.2.1.3（都在 trade），trade 本来就在线；读得到才能把「9.2 里有没有这个人」一起说出来 |

## 文件清单

### 后端

| 文件 | 说明 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V318__channel_buyer_blacklist.sql` | 四张表 + 七个 DB 守卫（不可变 / 事件链 / 生效唯一 / 至少有事件 / 店铺引用 / 申诉引用 / 申诉裁决 + 申诉事件匹配）+ RLS + GRANT |
| `backend/services/channel/src/main/java/com/starcon/channel/BuyerBlacklistModels.java` | 传输模型（15 个 record） |
| `backend/services/channel/src/main/java/com/starcon/channel/BuyerBlacklistRules.java` | 受控值域（Scope / RiskLevel / FlagAction / Source / Status / EventType）+ scope⇔shopCode 自洽校验 |
| `backend/services/channel/src/main/java/com/starcon/channel/BuyerBlacklistService.java` | 服务层（jOOQ 显式 SQL；当前状态由 LATERAL 派生） |
| `backend/services/channel/src/main/java/com/starcon/channel/BuyerBlacklistController.java` | 10 个端点 |
| `backend/services/channel/src/main/java/com/starcon/channel/BuyerBlacklistSourcePort.java` | 9.2 审单的跨服务只读端口（读不到 ⇒ 503，不折成空集合） |

### 测试

| 文件 | 说明 |
| --- | --- |
| `backend/services/channel/src/test/java/com/starcon/channel/BuyerBlacklistIntegrationTest.java` | 22 例（Testcontainers 真实 PG16） |
| `backend/services/channel/src/test/java/com/starcon/channel/BuyerBlacklistTenancyIntegrationTest.java` | 4 例（租户三断言 + 同主体键跨租户） |
| `backend/services/channel/src/test/java/com/starcon/channel/BuyerBlacklistTradeStub.java` | 本地 trade 服务（真的 HTTP socket，不是 mock） |

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/app/compliance/buyer-blacklist/page.tsx` | 路由 `/compliance/buyer-blacklist` |
| `apps/web/src/components/buyer-blacklist/buyer-blacklist-model.ts` | 前端模型 + 取数 + 展示映射 |
| `apps/web/src/components/buyer-blacklist/buyer-blacklist-workspace.tsx` | 真实工作区（八块：能力声明 / 手工录入 / 自动录入 / 清单 / 详情与留痕 / 解除 / 申诉与裁决 / 下单检查 / 拦截标记） |
| `apps/web/src/components/buyer-blacklist/buyer-blacklist-model.test.ts` | 15 例纯函数断言 |
| `apps/web/src/app/api/channel/buyer-blacklist-allowlist.test.ts` | 8 例白名单**双向**断言 |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `services[]` 元素**（锚点：`logistics-channels` 那条之后、`attributions` 那条之前），既有条目一字未动、不重排 |

### 契约与台账

| 文件 | 说明 |
| --- | --- |
| `packages/api-contracts/src/channel.openapi.json` | codegen 产物（channel 纯新增 9 条路径） |
| `packages/api-contracts/src/contracts/channel.ts` | codegen 产物（纯新增 20 个类型） |
| `docs/product/capability-blueprint/module-progress.md` | **只改本模块那一行**的「状态」「证据」两格 |

## 关键事实与验证

- **上游自检**（开工前 10 分钟）：`buyer_risk_score` / `buyer_risk_rule` / `buyer_risk_feature` /
  `buyer_risk_signal_writeback` 四张表与 `BuyerRiskScore*` 五个 Java 类在基线里都已存在 ⇒ **不阻塞**。
- **PL/pgSQL 的一个坑**：`IF CASE ... WHEN ... THEN ... END THEN` 里 CASE 内部的 `THEN`
  会被 plpgsql 当成 **IF 自己的 THEN**，条件被截断 ⇒ 函数在解析期报
  `syntax error at end of input`（纯 SQL 里完全合法，所以极容易踩）。
  本仓的写法是**先把严重度算进变量再比较**。
- **上游那条 `buyer-risk-scores` 的 `services[]` 元素刻意没动**（它的注释里还写着
  「16.5.1.1 黑名单未交付」）。同批另一条车道在数组末尾插入，动它会造成无谓的 diff 风险；
  本模块的能力声明与前端页面都已覆盖这件事。
- **api-proxy 首 token 零命中核对**：`buyer-blacklist` 在本文件既有 services[] 正则里
  **命中 0**（既有的是 `buyer-risk-scores` / `buyer-emails`（crm 前缀）/ `influencers/blacklist`），
  与它们互不为前缀。
- **契约差异 0 删除**：channel paths **+9**（全部本模块）、channel types **+20**（全部本模块），
  identity / catalog / warehouse / trade / crm **+0/-0**。
- **验证**：L0 `./mvnw -q compile` EXIT=0；L1 `Tests run: 26, Failures: 0, Errors: 0` BUILD SUCCESS；
  前端模块 23 例全绿；共享白名单全量回归 `pnpm vitest run apps/web/src/app/api/trade apps/web/src/app/api/channel`
  **139 文件 / 735 例全绿**；`pnpm typecheck` 3/3 successful。

## 后续待办

- **9.2.1.3 异常拦截**：本模块只产出**建议动作**与订单拦截标记，**不写**它的检查 / 人工处置表
  （跨服务不写对方库）。要让拦截真正读这份黑名单，需要 trade 侧接一个 channel 的只读入口 ——
  那是 9.2.1.3 的改动。
- **11 售后**：欺诈售后单由 11 自己的模块创建，本模块**不代建售后单**。
- **16.5.1.2 风险评分**：它的 `FRAUD_PROFILE` 维度目前读的是 trade 的
  `order_review_buyer_profile.blacklisted`。若要让它看到本模块的权威黑名单，
  由 **16.5.1.2 自己**来读 `buyer_blacklist_entry`（同服务同库 jOOQ 直读，无需跨服务）。
- **批量操作**：本模块刻意**不提供**批量拉黑 / 批量放行 —— 批量会让「谁拉黑的、为什么」糊掉。
