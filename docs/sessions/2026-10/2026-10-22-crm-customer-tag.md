# 15.3.2.1 客户标签（阶段 D7，L3 锚点 15.3.2）

日期：2026-10-22 ｜ 分支 `feat/15-3-2-1` ｜ 基线 `integration/module-dev` @ `da1f96f9`
迁移版本：**V295**（本车道预分配；同批另三条车道用其它版本号）
落点服务：**crm**（`backend/services/crm/`，包 `com.starcon.crm`，端口 8086，DB 角色 `crm_app`）

## 决策摘要

### 1. 「客户标签」落在 crm，不落在 trade

标签的主语是**客户**，但本仓**没有客户档案**（15.3.1.1 客户列表未交付），
客户只能通过「订单的 `customer_party_id`」这一既有事实维度被指认。
若把标签表放进 trade，标签就成了订单的附属属性，跨订单聚合要回 trade 库自己拼；
放在 crm 则天然是**客户维度的独立事实**，将来 15.3.1.1 落地时直接对上。
**crm 是卷 15 客户与客服的服务**，且已存在 `/crm/knowledge` `/crm/review-requests`
`/crm/review-rules` `/crm/buyer-emails` 四个兄弟命名空间 —— 本模块取**独立命名空间**
`/crm/customer-tags`，**不共用表、不共用前缀、不往兄弟的巨型 alternation 里并分支**。

### 2. 加粗句「退货率高」落成可审计的判定，不是落成一个布尔标签

蓝图要求「**可解释、可申诉**」与「**避免歧视性**」。具体落成五层：

1. **样本量门槛**（`min_sample`）：不足 ⇒ 判 `INDETERMINATE`（**不可判定**），**不打标**。
2. **显著高于租户基线**（`baseline_multiplier > 1`）：必须 `rate ≥ baseline × 倍率`。
   基线 = 同窗口全体 `售后单数 ÷ 订单数`；**基线算不出 ⇒ 同样 `INDETERMINATE`**。
3. **可争议标签带原因与证据**：`contestable=1` 的标签由 **DB 触发器**强制要求填写原因；
   判定依据快照进 `evidence` jsonb（订单号 / 订单 id / 金额按币种 / 渠道来源 / 售后单号 /
   退货率 / 基线 / 窗口），页面上可展开逐条看。
4. **可人工复核纠正**：`POST /assignments/{uuid}/review` 记确认或撤销；撤销是
   `state=REVOKED`，**不删行** —— 标签记录是凭据，删了这段历史就凭空消失。
5. **不触发任何歧视性动作**：本模块**只产出标签与判定**，没有屏蔽、没有拒付、
   没有差别定价。`INDETERMINATE` 与 `NOT_MATCHED` 在前端文案上**严格区分** ——
   「不可判定」不是「不匹配」。

**口径**（写在代码注释里，页面上也随证据返回）：
分子 = 窗口内受理且能归到该客户的售后单（只计 `RETURN_REFUND` / `REFUND_ONLY`）；
分母 = 窗口内该客户订单数。
订单扫描额外回看 365 天**仅用于归因索引**（老订单的售后单要能归到客户），
**回看段的订单不进分母** —— 否则老订单会把退货率稀释成看起来正常。

### 3. 幂等与「不覆盖人工」靠 `origin_key` 落成唯一键

`UNIQUE (tenant_id, customer_ref, tag_id, origin_key)`，
`origin_key` 手工恒为 `MANUAL`、规则恒为 `RULE:<rule_code>`。
⇒ 规则重跑**只更新自己那行**；**人工已撤销的行自动不恢复**（结论 `SKIPPED_REVOKED`）——
自动流程不该偷偷把运营手工撤销过的判断加回来。
**判定结论先缓冲、运行记录落库后再写**：evaluation 的触发器要求 run 已存在，
而 run 记录里又需要先知道这次判定了多少条 —— 顺序反过来会自相矛盾。

### 4. 上游在 trade，只经授权 HTTP API 读，读不到就硬失败

归因链路是**「售后单 → orderId → 订单 → customer_party_id → 客户」** ——
**售后单本身不带买家维度**（这是自检时才发现的事实），不猜买家。
读 `GET /trade/sales-orders/list` 与 `GET /trade/aftersales/cases`，
经 `CustomerFactsPort`（RestClient）**转发调用方自己的 `Authorization` bearer**、
**不传租户号**（租户由 trade 从成员关系自行解析），显式超时（connect 3s / read 15s）。
**上游读不到一律 503 / 显式失败** —— 绝不返回空集合冒充「这个租户没有订单」。

### 5. 上游不可用时，运行记录要留下痕迹

`rules/{uuid}/run` 在上游不可用时**写一条 `status=FAILED` + `failure_reason` 的 run**，
**一条标签都不打**，HTTP 仍 200。
理由：事务内抛异常会**回滚掉**这条记录（运营看不到「今天试过但失败了」），
而 `REQUIRES_NEW` 在**连接池大小=1** 时有**死锁风险**（本仓集成测试池固定 1 条）。
`customer-candidates` 没有运行记录可写 ⇒ 硬 503。

### 6. 零外键，引用校验一律用触发器

与同批 V292 / V228 / V220 / V220 同一手法：**外键会打破同服务既有测试的 TRUNCATE**。
本迁移**零外键**（含自己新建的五张表之间），9 个守卫触发器承担引用校验
（标签/规则引用回表校验、被引用的标签与规则删不掉、身份列建后不可改、
可争议标签必须带原因等）。**既有测试一行未改**。

### 7. 分布占比在服务端算

新增 `share`（0..1）与 `sharePercent`（0..100）两个字段并存，
前端**只原样拼 `%`、不做任何算术**（前端做乘法会让「四舍五入到 0.0%」和
「真实为 0」在界面上混成同一个数）。分母为 0 ⇒ 两者为 `null`，
页面显示「**不计算**」而不是 0%。

### 8. 下游未交付 ⇒ 只留能力声明，不假装

15.3.1.1（按标签筛人群）/ 14.4（促销定向）/ 15.5.2.1（关怀邮件）均未交付 ⇒
capabilities 里对应维度 `available=false` + `ownerModule`，
**不假装已筛出人群、不编造下游 id**；api-proxy 侧**刻意不放行**
`segments` / `promotions` / `campaigns` / `care-emails` 四个形状。

### 9. 权限码用兄弟族约定

`crm.tag.read` / `crm.tag.update`。
⚠️ 任务书写的 `crm.operation.*` 在**全仓任何文件里都不存在**
（identity 的 permission 表里也没有任何 `crm.` 前缀的权限码，既有 crm 模块用自由词表），
故按**兄弟模块的实际约定**取名，**identity 服务零改动**。

## 关键 trade-off

| 决策                                               | 取舍                                                                                                                                                   |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `INDETERMINATE` 单列一态 vs 归入 `NOT_MATCHED`     | 多一个状态、前端多一套文案。代价是「样本不足」看起来像「没做」；收益是**「不可判定」不会被误读成「这个客户没问题」**                                   |
| `origin_key` 唯一键 vs 人工优先覆盖                | 不用「人工优先覆盖」那套隐式规则。代价是同一人工标签在同一客户身上只存一行（要改先撤销）；收益是**自动流程永远覆盖不了人**                             |
| 上游失败写 FAILED run（200） vs 抛 503             | 运行记录选了 200。代价是「HTTP 成功」不等于「打标成功」（要靠 run.status 区分）；收益是**失败留痕**。`customer-candidates` 无处留痕 ⇒ 仍硬 503         |
| 回看 365 天做归因但不入分母                        | 分子分母口径不对称。代价是「退货率」不是纯粹的同期比；收益是**老订单上的售后不会因为窗口外的订单被稀释掉**                                             |
| 零外键用触发器 vs 真实 FK                          | 放弃 FK 的引用完整性保证。代价是引用校验在**触发器里**；收益是**既有测试的 TRUNCATE 不被打破**（batch-37 有车道踩过这个坑）                            |
| 独立 `services[]` 元素 vs 并入既有巨型 alternation | api-proxy 里既有 crm 那几条是**超长单行正则**，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。代价是多一条元素；收益是**既有条目一字未动** |
| 契约只提交 crm 两个文件                            | codegen 会连带重生成 channel / trade（**基线里就有的漂移**）。刻意复原那两个文件，保持本车道 diff 只含 crm ⇒ **不与并行车道撞车**                      |

## 文件清单

**迁移（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V295__crm_customer_tag.sql`
  —— 5 张新表（`crm_customer_tag` / `crm_customer_tag_rule` / `crm_customer_tag_assignment` /
  `crm_customer_tag_run` / `crm_customer_tag_evaluation`）+ 9 个守卫触发器 +
  RLS（ENABLE + FORCE，策略名 `isolation`）+ GRANT（表与**序列分开**授权）+
  `REVOKE ALL ON flyway_schema_history FROM crm_app`。
  **零 ALTER 任何既有表**；**零外键**。

**后端（crm 服务，包 `com.starcon.crm`）**

- `CustomerTagModels.java` —— 传输模型与枚举（`CrmCustomerTag*` 前缀，避免跨服务同名 DTO 冲突）
- `CustomerFactsPort.java` —— 跨服务只读端口（trade 订单 + 售后单；转发 bearer，不传租户号；取不到 503）
- `CustomerTagService.java` —— jOOQ 显式 SQL；能力声明；标签/规则 CRUD；`executeRule`；
  判定/打标/撤销分离；手工打标；人工复核；分布统计；候选客户；事实扫描
- `CustomerTagController.java` —— `/crm/customer-tags/**` 共 **21 个方法映射 / 14 条路径**
- `src/test/.../CustomerTagIntegrationTest.java`（16 例）、
  `src/test/.../CustomerTagTenancyIntegrationTest.java`（4 例）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/contracts/crm.ts` —— 新增 21 个 `CrmCustomerTag*` 类型
- `packages/api-contracts/src/crm.openapi.json`

> 跑 codegen 时 channel / trade 的既有文件也被重排（**基线里就有的漂移**，与本车道无关），
> 已**刻意复原为基线版本**，保持本车道 diff 只含 crm。

**前端**

- `apps/web/src/app/crm/tags/page.tsx` —— 页面入口（与 `/crm/review-requests` 同级）
- `apps/web/src/components/crm/customer-tag-model.ts` —— API 客户端 + 可读形态
- `apps/web/src/components/crm/customer-tag-workspace.tsx` —— 真实工作区
- `apps/web/src/components/crm/customer-tag-model.test.ts`
- `apps/web/src/app/api/crm/customer-tags-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（145 → **146**），既有条目一字未动
  （另有 2 处**基线里就存在**的 prettier 违规行被格式化器顺带修正：`ad-budgets` 与
  `ad-promotion-analysis` 的换行 —— 与本车道逻辑无关，仅格式）

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 15.3.2.1 那一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-22-crm-customer-tag.md` —— 本文件

## api-proxy 锚点与零命中核对

- **锚点**：**紧跟 `crm/review-rules`（15.2.2.2 索评规则）那条元素之后**。
  同批另三条车道的锚点分别在 `crm/knowledge` 之后（15.1.1.1 重派）、
  `crm/review-requests` 之后（15.2.1.1）、services[] 数组末尾（15.6.2.2）——
  都与本处相距足够远，git 能干净求并。
- **零命中核对**：基线全文 **145 条** services[] 正则里 `customer-tags` 出现次数 **= 0**；
  既有 crm 四条的正则都是**锚定**的（`^knowledge/…` / `^review-requests/…` /
  `^review-rules/…` / `^buyer-emails/…`），对 `customer-tags/…` **逐条实测零命中**
  ⇒ 两条互不吃掉。id 段用**完整 UUID 形状**（不是宽松的 `[0-9a-f-]+`）。
- **不误伤**：allowlist 测试正向钉住 `crm/knowledge/*`、`crm/review-requests/*`、
  `crm/review-rules/*`、`crm/buyer-emails/*` 与 catalog / channel / warehouse / trade / identity
  的代表形状；全量 allowlist 回归 **978 例全绿**。

## 后续待办

- **15.3.1.1 客户列表**落地后：补一个真正的「按标签筛客户」入口，并让
  `customer-candidates` 的客户标识与客户档案的主键对齐（当前只能用
  `customer_party_id` 字符串指认）。
- **14.4 促销定向 / 15.5.2.1 关怀邮件**落地后：本模块的 capabilities 里对应维度翻成
  `available=true`，并由**它们**读取本模块的标签（**本模块不代劳定向逻辑**）。
- **客户档案本身**（15.3.1.x 全家）落地后：`customer_ref` 的口径可能要跟着收敛，
  届时可加一层映射而不动既有 `origin_key` 唯一键。
- **`crm.tag.read` / `crm.tag.update` 两个权限码**若要进 identity 的 permission 表，
  需另起一次跨服务改动（本车道**刻意没动** identity）。
- **契约工具缺口**：codegen 在**同服务内**同名 DTO 时是「后者覆盖前者」，
  本车道靠 `CrmCustomerTag*` 前缀规避（与 15.4.2.1 知识库、15.2.2.2 索评规则同一手法），
  但工具本身的「同服务重名检测」仍是待补项。
- **`packages/api-contracts` 的 channel / trade 相对当前 codegen 脚本已陈旧**（基线漂移），
  需要一次**独立**的全量 codegen 同步，本车道刻意不做（避免与并行车道撞车）。
