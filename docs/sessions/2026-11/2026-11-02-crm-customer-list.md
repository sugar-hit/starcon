# 2026-11-02: 15.3.1.1 客户列表（crm 服务）

## 摘要

交付蓝图 15 卷 **15.3.1.1「客户列表」**（阶段 D7）：客户列表 + 分层统计 + 多维筛选 +
联系方式脱敏与显式授权。后端落在 **crm 服务**（同卷兄弟模块 15.3.2.1 / 15.3.2.2 / 15.1.1.1
都在 crm，保持同卷一致），Flyway 版本号 **V432**（调度者预分配），前端页面
`/crm/customers`。本模块是 **15.3.1.2 客户详情 / 15.3.1.3 跨店合并 / 15.6.2.1 生命周期预警**
三者的上游 ⇒ 交付后它们才能开工。

## 决策 / 结论

1. **客户是「按来源店铺」的，不是「逻辑客户」**
   客户主档的身份列是 `(source_shop, customer_ref)` ⇒ 同一个客户标识在两个来源下是**两行**。
   蓝图加粗句「客户标识（按店铺）」与 15.3.1.3 未交付共同决定了这个建模。
   **不建逻辑客户主表、不建合并映射** —— 那是 15.3.1.3 的事实表，两边都建会撞
   「每张事实表只有一个写入所有者」。合并交付后「按逻辑客户展示」是**下游增强**，不是开工前置。

2. **「来源店铺」当前是订单来源口径（平台级），不假装是店铺级**
   上游 9.1.1.1 订单读投影**没有 shop 列**，只有 `order_source`；真正的店铺目录在 channel
   服务（`GET /channel/shops`），订单事实里没有指向它的列，从 `order_source` 反推 shop 是**猜**。
   ⇒ `crm_customer_profile.source_shop` 记录订单来源口径，列名与文档都写明这一点；
   等 9.1.2.1 平台单同步把 `shop_code` 铺开后再细化。

3. **上游只读走 trade 的授权 HTTP API（复用既有 `CustomerFactsPort`）**
   - 订单：`GET /trade/sales-orders/list` → `OrderView{id, orderNo, orderSource, documentStatus,
     currency, grandTotalAmount, customerPartyId, buyerName, submittedAt, createdAt}`
   - 售后（退款事实）：`GET /trade/aftersales/cases` → `AftersalesCaseView{id, caseNo, orderId,
     orderNo, caseType, status, reasonCode, requestedAmount, currency, receivedAt}`
   - 归因链路：售后单**不带买家维度**（只有 `orderId`）⇒ 「售后单 → orderId → 订单 →
     customer_party_id → 客户」在**一次拉取内**建索引完成，本库**不建任何指向 trade 的外键**。
   - 联系方式另有一个只读口：catalog 3.6.1.1 `GET /catalog/parties/{id}`（蓝图 15 卷开篇写明
     「买家主档在 catalog（party customer_profile）」，上游订单里的 `customer_party_id` 指向它）。

4. **退款率口径照抄 15.3.2.1 客户标签**（同服务、同一个客户口径）
   分子 = 窗口内 `RETURN_REFUND` / `REFUND_ONLY` 两类售后单（换货 / 补发 / 维修**不算退货**），
   分母 = 窗口内订单数，scale 6 HALF_UP。**样本不足 5 单 ⇒ 风险标记是 `INSUFFICIENT_SAMPLE`
   （不可判定），不是「没风险」** —— 一个人只下一单、退一次，退款率 100%，那是样本不足。
   「显著高于」判据 = ≥ 租户基线 × 2（基线由同窗真实事实算出，不写死数字）。

5. **金额按币种分行**：`crm_customer_amount` 每客户每币种一行（累计消费 + 客单价）。
   「USD 与 EUR 加起来不是任何一个金额」⇒ 跨币种的金额筛选 / 排序**必须**给币种，否则 400。

6. **联系方式默认脱敏，且是「两把锁」**
   脱敏形态（`a***@***.com` / `138****1234`）在**写库之前**算好落库（不是读时现算 ——
   脱敏规则改了也不该让历史行显示出另一种形态）；明文需要**额外的权限码**
   `crm.customers.pii.read` **且**调用方显式要求（`revealContact=true`）。
   没有权限还硬要 ⇒ **403**，而不是悄悄退回脱敏（那会让调用方以为权限已生效）。

7. **真分页 + 独立统计**：列表 `LIMIT/OFFSET` + 稳定全序
   （`order_count desc, customer_ref asc, source_shop asc`）；分层统计来自**单独一条**
   `GROUP BY` 查询，**不受分页影响** —— 运营正是靠这个分布判断要不要做定向运营。

8. **价值分层的基线必须在金额落库之后算**
   先算基线再落库 ⇒ 第一次刷新时基线是空的 ⇒ 所有人都是「常规」，而分层统计看上去又「有值」，
   那是最难发现的一种错。基线 = 租户内该币种的人均累计消费（VIP ≥ 3×、HIGH ≥ 1×）。

9. **权限码进 identity（V432，同版本号）**
   `crm.customers.read` / `crm.customers.update` / `crm.customers.pii.read` + page_resource
   `crm.customers` + 存量 OWNER 补权（照 V418 `platform.external.*` 的写法）。
   ⚠️ 顺带发现：identity 的 permission 表里**从来没有过 crm.\* 任何一行** ——
   15.3.2.1 / 15.3.2.2 / 15.1.1.1 的 `crm.tag.*` / `crm.contact.*` 同样没登记，
   那些端点在真实环境里会恒 403（不在本车道范围，已记入后续待办）。

## 关键 trade-off

- **不做「实时聚合」的列表**：列表读本地主档（可分页、可统计、可复盘），上游事实由
  **刷新端点**拉取并落库。代价是列表不是「实时的」；换来的是分页与统计是真的
  （实时聚合的列表只能拿当前页做统计，那是假的）。
- **联系方式的 catalog 依赖做成「补充维度」**：catalog 读不到 ⇒ 联系方式留空 +
  在刷新运行记录里**如实报出没取到几个**，不伪装成「这个客户没有邮箱」；
  而订单 / 退款**核心事实**读不到 ⇒ 整个刷新失败（503），一条档案都不落。
- **不给明文联系方式做「静默降级」**：宁可 403，也不要让调用方误以为拿到了明文。

## 涉及文件

- `backend/services/crm/src/main/resources/db/migration/V432__crm_customer_list.sql`
- `backend/services/identity/src/main/resources/db/migration/V432__crm_customer_permission.sql`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerListModels.java`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerListService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerListController.java`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerPartyPort.java`
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerListIntegrationTest.java`
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerListTenancyIntegrationTest.java`
- `apps/web/src/app/crm/customers/page.tsx`
- `apps/web/src/components/crm/customer-list-model.ts`
- `apps/web/src/components/crm/customer-list-model.test.ts`
- `apps/web/src/components/crm/customer-list-workspace.tsx`
- `apps/web/src/app/api/crm/customers-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立的 `services[]` 元素**；锚点见下方「锚点搬家」小节）
- `packages/api-contracts/src/contracts/crm.ts` + `packages/api-contracts/src/crm.openapi.json`
  （codegen 产物，`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`）
- `docs/product/capability-blueprint/module-progress.md`（**只改 15.3.1.1 那一行**）

## 验证

- `./mvnw -q compile`（backend）绿
- `./mvnw -pl services/crm -am test -Dtest='CustomerList*'` ⇒ **18/18 绿**
  （14 集成 + 4 租户隔离；租户隔离实跑 Testcontainers PostgreSQL 16 + FORCE RLS，
  三条断言 + 「断言 FORCE 而不是只 ENABLE」）
- `pnpm typecheck` 绿
- `pnpm vitest run apps/web/src/components/crm/customer-list-model.test.ts
  apps/web/src/app/api/crm/customers-allowlist.test.ts` ⇒ 19/19 绿
- `pnpm vitest run apps/web/src/app/api/trade` ⇒ 71 文件 / 394 用例全绿（改了 api-proxy 的全量回归）
- `pnpm exec prettier --check`（只查本车道改动过的前端文件）绿

## 追加：并入集成头的同锚点冲突与「锚点搬家」

第一轮并入集成头时 `apps/web/src/lib/api-proxy.ts` 报 **CONFLICT**，车道状态被标成 `conflict`
（**成果没丢**，是位置冲突不是内容冲突）。

- **成因**：本车道原先把新的 `services[]` 元素插在**数组末尾**（收尾的 `];` 之前，
  18.3.1.2 `automation-rule-editor` 之后），而已并入集成的 **18.4.2.3** 插在**同一位置**
  ⇒ 三方求并在同一锚点撞车。18.4.3.2 也落在数组末尾附近。
- **解法**：冲突处**取集成分支一侧**（18.4.2.3 / 18.4.3.2 的元素与注释**一字不改、不重排**），
  把本车道的 15.3.1.1 元素（注释块 + `pattern` + `prefix` + `env` + `fallback`）**整块搬家**到
  **紧接在 `prefix: 'crm/'` 且首 token 是 `customer-contacts`（15.3.2.2 接触记录）的那条元素之后**
  —— 该元素在集成头里结束于第 **10166** 行（`  },`），本车道的元素落在 **10167–10206** 行。
- **选这条锚点的理由**：与数组末尾（≈14790 行）相距 4500 行以上、与 18.4.3.2 的锚点
  （≈13964 行）相距 3800 行以上 ⇒ git 能干净求并。
- **本元素的功能字段一个字未改**：`pattern: /^customers(?:\/(?:capabilities|runs|sync|contact))?$/`、
  `prefix: 'crm/'`、`env: 'CRM_URL'`、`fallback: 'http://127.0.0.1:8086'` 逐字保留
  （首 token `customers` 与 `customer-tags` / `customer-contacts` 在第 9 个字符分叉的核对结论
  仍写在注释里）。**只有那段描述锚点的注释**从「数组末尾」改成新锚点 —— 留着旧描述就是
  一句假话，注释必须跟着事实走。
- **与集成头文件的整体差分**：40 行新增、0 行删除 —— 即本车道的这一条元素，
  集成头既有内容**一字未动**。
- **并入后 L0/L1 全绿**：`./mvnw -q compile`（backend）绿；`pnpm typecheck` 绿；
  `prettier --check` 绿；api-proxy **全量 allowlist 回归 291 文件 / 1638 用例全绿**
  （含 trade 71 文件 / 394 用例）。

## 后续待办

- **15.3.1.2 客户详情**：上游已是本模块；详情要聚合订单 / 售后 / 消息 / 评价 / 标签 / 接触记录时间线。
- **15.3.1.3 跨店合并**：上游已是本模块；它拥有逻辑客户与合并映射事实表；
  合并交付后本模块的列表按逻辑客户展示（**下游增强**）。
- **15.6.2.1 生命周期与流失预警**：上游已是本模块（首末购买时间 + 订单数可复用）。
- **identity 的 permission 表缺 crm.\* 登记**：15.3.2.1 / 15.3.2.2 / 15.1.1.1 等既有 crm 端点
  的权限码仍未登记（真实环境恒 403）—— 建议单开一条车道统一登记，别夹在业务模块里。
- **来源店铺细化到店铺级**：等 9.1.2.1 平台单同步把 `shop_code` 铺开后，
  `crm_customer_profile.source_shop` 才能从平台口径细化到店铺口径（需要一次数据迁移）。
