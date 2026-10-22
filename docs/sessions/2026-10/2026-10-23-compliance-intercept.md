# 2026-10-23: 16.2.1.2 拦截执行（阶段 D7）

## 摘要

本轮实现能力蓝图模块 **16.2.1.2「拦截执行」**（卷 16 合规与风控，L3 锚点 16.2.1 合规规则），
落在 **channel 服务**（与同卷 16.1.1.1 / 16.1.1.2 / 16.1.2.1 / 16.2.1.1 / 16.2.2.1 / 16.2.2.2 / 16.4.2.1 同服务同库）。

蓝图原文：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
**豁免必须有审批与理由**，否则阻断会被随意绕过。」

产出：迁移 `V313__channel_compliance_intercept.sql`（4 张新表、零 ALTER）、
`ComplianceIntercept{Models,Rules,Service,Controller}.java`、
**4.3.1.1 刊登派发路径上的服务端闸门**（`ListingPublishJobService`）、
契约重新生成（+17 DTO / +10 路径，零删除零改字段）、前端路由 `/compliance/intercept` 真实工作区、
api-proxy 新增一条独立白名单元素。

## 决策 / 结论

1. **🔴🔴 结论是「三态 + 一个判不了」，不是三态** ——
   蓝图只点了「通过 / 警告 / 阻断」，但**「判不了」必须与「通过」分开**：
   规则读不到、适用版本查不到、规则集为空时**不能返回 PASS**（那等于放行）。
   ⇒ 结论值域四值 `PASS / WARN / BLOCK / UNDECIDABLE`；
   `blocked` 与 `release_granted` **分开记** —— UNDECIDABLE 时 `blocked=false` 而
   `release_granted=**false**`（判不了 ≠ 放行，也 ≠ 违规）。
   六个「判不了」原因码（`RULE_SET_EMPTY` / `RULE_VERSION_UNRESOLVED` / `RULE_TERMS_MISSING` /
   `REQUIREMENT_UNRESOLVED` / `RULE_SCOPE_UNRESOLVED` / `RULE_READ_FAILED`）在 DB 层用**具名 CHECK** 钉死。

2. **🔴🔴 判定绑定「当时生效的那个 version」** —— 与 16.2.1.1「规则版本化 ⇒ 历史判定可解释」同口径：
   上游 `compliance_rule*` 四表**同库只读**（本模块**不写**上游任何一张表），
   按 DB 时钟取生效窗口覆盖此刻的那一版，`rule_version_id` 冻结在原因行里 ⇒
   规则后来发新版本**不追溯改变历史判定**（集成测试里专门断言了这一点）。

3. **🔴🔴 阻断在服务端强制，放行路径必须真的读服务端结论** ——
   4.3.1.1 一键刊登（**同服务已交付**）的 `dispatch` 与 `retry` 两条路径都调
   `ComplianceInterceptService.enforceListingPublish`：命中阻断 ⇒ 该目标被标成 `FAILED`、
   `failure_reason` 写明「合规校验阻断，已拦截（服务端强制）」，前端只是显示。
   **关键实现细节**：判定凭据写在 SAVEPOINT **之外** —— 写在 savepoint 里会跟着目标回滚一起滚没，
   那就没有凭据可查了（审计链断在这里最隐蔽）。

4. **🔴🔴 豁免必须有审批与理由**（蓝图加粗句）落成五道闸：
   ① 状态机 PENDING→APPROVED/REJECTED，**终态不可回退**且身份字段冻结；
   ② **申请人 ≠ 审批人**（DB 具名 CHECK + 服务层 422 双重钉住；服务端还算出 `decidable` 标志给页面用）；
   ③ 申请人理由与审批意见都**必填非空白**；
   ④ **有效期必填**（不能永久生效；过期即失效；连已过期的都不让批）；
   ⑤ 一张豁免**只针对一条规则** —— 什么都豁免的空白豁免等于把阻断整个关掉，那不是豁免。

5. **🔴 因豁免放行必须可审计** —— 被豁免放行的原因**照样写进判定凭据**（`waived=true` + `exemption_id`），
   结论里逐条标出「因豁免放行 → 豁免单 id」。阻断不允许无声消失。

6. **🔴 原因必须具名** —— 原因码走 DB 层**具名 CHECK**（`compliance_intercept_reason_code_chk`），
   报错能看出是哪条口径被违反（匿名 CHECK 报错只说 check2，读报错的人根本不知道哪条被违反）。
   另外加了一道 **CONSTRAINT TRIGGER（DEFERRABLE）**：`BLOCK / WARN / UNDECIDABLE` 的判定
   **零原因直接拒写** —— 「被拦了」却说不出为什么，运营就只能来问人。

7. **🔴 判定凭据 append-only** —— `compliance_intercept_decision` 与 `compliance_intercept_reason`
   的 UPDATE / DELETE 一律被触发器拒。

8. **🔴 拦截默认不生效（租户显式开启才拦）** —— 这是一个**产品判断**，写在这里免得以后有人当 bug 改：
   把「还没开合规」当成「违规」会让刊登链一步都走不动（与 4.2.3.2「未扫描过则不拦」同一纪律）。
   同理，「判不了」时失败关闭(BLOCK) 还是放行并留痕(ALLOW) 是**数据**（`compliance_intercept_policy`）
   而不是硬编码 —— 默认 BLOCK（失败关闭），因为「不能返回 PASS」这条口径的前提就是失败关闭。

9. **🔴 非词条类规则（认证 / 禁限售 / 标签 / 材质）怎么判？** ——
   上游 16.2.1.1 的规则版本**没有结构化的要求载荷**（只有自由文本的标题与说明），
   所以这四类规则**无法**从词条推出判定。⇒ 判定请求里带一个 **要求状态**（按规则编码给
   `SATISFIED` / `UNSATISFIED` + 依据）；**不给就是「判不了」**（`REQUIREMENT_UNRESOLVED`），
   而不是默认通过。这是本模块唯一一处「输入由调用方给」的边界，已写进迁移文件头、
   能力声明与本日志；真正的数据源（16.2.2.2 认证档案 / catalog 属性）接上是后续车道的事。

10. **🔴 不编造未交付的下游** ——
    8.3.4.1 发货在 **warehouse 服务**（跨服务）⇒ 本模块**只出结论**，**不跨服务 SQL / 不造跨库 FK**；
    12 结算提现的 **finance 服务在本仓尚未落地** ⇒ **不建财务表、不造提现调用、不声称已拦截提现**。
    两条都在能力声明里 `available=false` + `ownerModule` + 影响说明。
    4.3.1.2 批量发布那条通道**尚未接入**本闸门（归 4.3.1.2 自己接），也在能力声明里写明。

11. **🔴 词条命中的口径** —— 拉丁词按**词边界**（`best` 不命中 `bestseller`），
    中文按包含（中文没有词边界），大小写不敏感；含符号的词条退回包含匹配。
    与 4.2.3.2 同一口径，不另立一套。

## 关键 trade-off

- **判定凭据写在 SAVEPOINT 之外**（而不是 savepoint 内）——
  代价：被拦下的那次判定与目标失败不在同一个 SAVEPOINT 语义里；
  收益：判定凭据不会跟着目标回滚消失（审计链不能因为一次拦截就断）。
- **豁免只针对一条规则**（不给「整店/整类目一票豁免」）——
  代价：规则多的租户申请次数会变多；收益：一张空白豁免等于把阻断整个关掉，
  那正是蓝图加粗句要防的「随意绕过」。
- **拦截默认关闭**——
  代价：租户不主动开，拦截就不会生效（看起来像「没做」）；
  收益：不破坏既有刊登链（否则 4.3.1.1 已交付车道的既有测试会全红，
  且「规则集为空 ⇒ 判不了 ⇒ 失败关闭」会让所有没配规则的租户发不了货）。
  页面与能力声明都把这条写明，不靠默契。

## 文件清单

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V313__channel_compliance_intercept.sql`

**后端**

- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceInterceptRules.java`（值域 + 纯判定逻辑）
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceInterceptModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceInterceptService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceInterceptController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ListingPublishJobService.java`（**跨模块接入点**：派发 / 重试路径加服务端闸门）

**测试**

- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceInterceptRulesTest.java`（14 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceInterceptIntegrationTest.java`（21 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceInterceptTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/app/compliance/intercept/page.tsx`
- `apps/web/src/components/compliance-intercept/compliance-intercept-model.ts`
- `apps/web/src/components/compliance-intercept/compliance-intercept-model.test.ts`（12 例）
- `apps/web/src/components/compliance-intercept/compliance-intercept-workspace.tsx`
- `apps/web/src/app/api/channel/compliance-intercepts-allowlist.test.ts`（allowlist 双向断言）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**新增一条 `compliance-intercepts` 元素）

**契约 / 台账**

- `packages/api-contracts/src/contracts/channel.ts`（codegen 产物，禁手改）
- `packages/api-contracts/src/channel.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（**只改 16.2.1.2 那一行**）

## 后续待办

1. **4.3.1.2 批量发布通道未接入本闸门** —— 归 4.3.1.2 那条车道（或后续批次）在
   `PublishService.dispatch` 上补同样的闸门；本模块的能力声明里已写明这个缺口。
2. **8.3.4.1 发货**（warehouse，跨服务）—— 发货路径接上本模块的结论，需要一条
   **跨服务只读端口**（本模块**不需要**，上游同库；接发货时才需要）。
3. **12 结算提现** —— finance 服务尚未落地；落地后接 `WITHDRAWAL` 场景的闸门。
4. **非词条类规则的要求状态数据源** —— 目前由调用方给（缺省即「判不了」）；
   接上 16.2.2.2 的认证档案与 catalog 的属性后，可由本模块自己算。
5. **规则集为空的失败关闭默认档** —— 现在默认 `BLOCK`；若运营反馈「没配规则也发不了货」，
   可考虑把默认改成 `ALLOW`（放行并留痕）—— 但**那是产品决定**，且会削弱
   「不能返回 PASS」这条口径，**不要顺手改**。
