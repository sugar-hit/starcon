# 15.3.2.2 接触记录（阶段 D7，L3 锚点 15.3.2）

日期：2026-10-22 ｜ 分支 `feat/15-3-2-2` ｜ 基线 `integration/module-dev` @ `17169616`
迁移版本：**V299**（本车道预分配；同批另三条车道用 V297 / V298 / V300）
落点服务：**crm**（`backend/services/crm/`，包 `com.starcon.crm`，端口 8086，DB 角色 `crm_app`）

## 决策摘要

### 1. 接触记录落在 crm，与同卷兄弟（客户标签）**不共用表、不共用命名空间**

蓝图把接触记录的主语写成「**与客户**的所有接触」，而 15.3 客户档案（15.3.1.1 / 15.3.1.2）
**未交付** ⇒ 本仓**没有买家档案主数据**，`customer_ref` 只能是**逻辑引用**
（同卷兄弟 15.1.1.1 的台账里已明确写过这一点，本车道沿用同一口径，不编造客户主数据）。

crm 是卷 15 客户与客服的服务，已有 `/crm/knowledge` `/crm/review-requests` `/crm/review-rules`
`/crm/buyer-emails` `/crm/customer-tags` 五个兄弟命名空间 ⇒ 本模块取**独立命名空间**
`/crm/customer-contacts`。

**页面刻意不挂 `/crm/customers`**：15.3.1.1 客户列表未交付，
为本模块造一个客户列表页会**越权**（那是 15.3.1.1 的职责），
而且会让「客户主档已存在」这个假结论成立。页面走**独立路由 `/crm/contacts`**。

### 2. 加粗句「接触记录是自动化的豁免依据」落成**状态 + 审计 + 判定**三件套，不是一个布尔

蓝图加粗句：「曾明确表示"不要再联系我"的客户，营销触达需排除」。
「接触记录要能表达退订/免打扰这类不可再营销的语义，并且是**可审计**的；
退订**不可被后续的普通接触记录悄悄覆盖或撤销** —— 撤销退订要走**显式的、可追溯的动作**（留痕）；
有一个**可查询的判定**：某个客户当前是否处于「不可营销」状态」。

落法（四道闸）：

1. **退订是一等状态**（独立表 `crm_customer_opt_out`，不是接触记录上的一个布尔字段）：
   登记**必须带依据**（`reason` 非空 + `evidence` 快照），并记下「谁在什么时候记下的」。
2. **退订不可被普通接触记录悄悄覆盖或撤销**：写一条接触记录**根本碰不到**退订状态 ——
   手工补录与自动写入的 `contactType` 清单**都不含 `OPT_OUT`**（模型层不给这个口子，
   服务层直接 400 并指向退订端点）。DB 侧另有触发器保证退订状态只能 `ACTIVE → REVOKED`。
3. **撤销退订是显式动作且留痕**：`POST /opt-outs/{id}/revoke` 必须写撤销原因，
   同一事务内追加一条**只追加**的 `REVOKED` 审计事件 + 一条时间线接触记录。
   已撤销的退订**不能**被改回生效（DB 触发器拒），退订记录**删不掉**（只能撤销）。
4. **有可查询的判定**：`GET /opt-out-check`（单个 / 批量）返回
   `suppressed` / `marketingSuppressed` / 范围 / 依据 / 登记时刻 ——
   这正是 15.5.2 将来要消费的**豁免依据**。

另加一道**反向闸**（让豁免依据真的拦得住东西，而不只是可读）：
客户处于生效退订时，**退订之后**发生的营销触达**落不了库**
（DB 触发器 + 服务层同一口径双重拦截）；要留痕必须显式标 `complianceState=VIOLATION`。
判定用 `occurred_at`（接触发生时刻）与退订登记时刻比较 ——
**退订之前**的历史触达照常补录（补录历史不是补造违规）。

### 3. 「各模块自动写入」用**库内物化触发器**做，而不是去改上游模块的代码

这是本车道**最关键的一次取舍**。

约束有三条同时成立：
① 蓝图要求「可由各模块自动写入」**真的做出来**（不许占位）；
② 硬性要求「**只碰自己的文件**」，而 15.1.1.2 车道正在并行改 `MessageInboxService`；
③ 15.1（消息）、15.1.2.1（邮件）与本模块**同服务同库**，
   而 11 售后在 **trade 服务**（跨服务）。

自检事实：**15.1 与 15.1.2.1 的表就在本服务的库里**（`crm_message` / `crm_buyer_email`），
所以「自动写入」对它们**不构成跨服务访问**。于是：

- **15.1 / 15.1.2.1 ⇒ 库内物化触发器**（V299 挂 `AFTER INSERT` 触发器）：
  消息 / 邮件落库时**真的**生成一条接触记录（`origin=AUTO`，`source_module` 记上游模块号，
  `source_ref` 记上游记录 id）。
  **不改上游代码、不改上游表结构**（零 ALTER / 零 DROP / 零改列）⇒ 与并行车道零冲突面。
  触发器三条纪律：**没有匹配的租户上下文就不写**（绝不写错租户）、
  **只物化真正的与客户接触**（平台通知 / 合规通知不算；邮件只有**已发出**的才算一次触达，
  待发与发送失败不算）、**幂等**（`ON CONFLICT DO NOTHING` + 唯一键）。
- **11 售后（trade，跨服务）⇒ 授权 HTTP 入口** `POST /ingest`：
  跨服务只走授权 API，**禁止跨库 SQL / FK / 共享视图**。
  但 **trade 侧尚未接线** ⇒ 能力声明 `auto_write_aftersales` 维度
  `available=false` + `ownerModule=11.1.1.1`，**不假装已自动打通**。

**Trade-off**：触发器方案让「同服务自动写入」今天就是真的（可测、可证），
代价是接触记录与上游表在 **DB 层**产生了耦合（上游表若被重建，触发器会失效）。
替代方案（改上游 service 加一行调用）耦合更浅，但会在 `MessageInboxService.java`
上与并行车道撞车 —— 结构性冲突，retry 不会自愈。**选择冲突面更小的那一侧。**

### 4. 幂等靠 `(tenant_id, source_module, source_ref)` 唯一键，且用 `on conflict do nothing`

重试与平台重复推送**不重复记账**。用 `ON CONFLICT DO NOTHING` 而不是「先查再插」：
先查再插在并发下有窗口，而唯一索引冲突在 PostgreSQL 里会**中止整个事务**，
事务里再查一次就查不动了。`on conflict do nothing` 不中止事务，
命中后重新读出既有那条并明确说「幂等命中，未重复记账」。

### 5. 退订与撤销都**留在时间线上**

「客户明确要求不要再联系」本身就是一次接触。
登记退订 / 撤销退订各写一条 `contact_type='OPT_OUT'` 的接触记录 ——
不留在时间线上，最重要的那次接触就凭空消失了。

## 关键 trade-off 一览

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 自动写入的通路 | 同服务用**库内物化触发器**，跨服务用 **ingest 授权 HTTP 入口** | 接触记录与 `crm_message` / `crm_buyer_email` 在 DB 层耦合；trade 侧自动写入**尚未接线**（已如实标 `available=false`） |
| 退订的表达 | 独立**状态表 + 只追加审计流水**（两张表） | 比「接触记录上一个布尔」多两张表；但换来「现在能不能营销」一次查得到、「曾经发生过什么」查得清 |
| 营销触达的拦截 | 退订后发生的营销触达**落不了库**（要留痕须显式标违规） | 若真发生了违规触达，必须显式承认才能留痕 —— 代价是操作多一步，换来违规不会被静默洗掉 |
| 页面路由 | 独立 `/crm/contacts`，**不挂** `/crm/customers` | 少一个「按客户档案进」的入口；换来不越权、不编造客户主数据 |
| 迁移手法 | **零外键**、触发器做引用校验 | 外键会打破同服务既有测试的 TRUNCATE（仓库既有约定） |

## 文件清单

**新增（后端）**

- `backend/services/crm/src/main/resources/db/migration/V299__crm_customer_contact.sql`
  —— 三张新表 + RLS（ENABLE **与** FORCE，策略名 `isolation`，USING 与 WITH CHECK 同表达式）
  + GRANT（表与**序列单独**授权）+ `REVOKE ALL ON flyway_schema_history FROM crm_app`
  + 5 个守卫触发器 + 2 个库内物化触发器。**零外键、零 ALTER 任何既有表。**
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerContactModels.java`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerContactService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerContactController.java`
  —— 权限码 `crm.contact.read` / `crm.contact.update`（全仓此前零出现，identity 零改动）。

**新增（测试）**

- `backend/services/crm/src/test/java/com/starcon/crm/CustomerContactIntegrationTest.java`（18 例）
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerContactTenancyIntegrationTest.java`（4 例，
  跑 AGENTS.md 明文要求的三条租户隔离断言）

**新增（前端）**

- `apps/web/src/components/crm/customer-contact-model.ts`
- `apps/web/src/components/crm/customer-contact-workspace.tsx`
- `apps/web/src/components/crm/customer-contact-model.test.ts`
- `apps/web/src/app/crm/contacts/page.tsx`
- `apps/web/src/app/api/crm/customer-contacts-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**
  （锚点：紧跟 `crm/customer-tags` 那条之后）；既有条目**一字未动、不重排**。
- `packages/api-contracts/src/contracts/crm.ts` / `packages/api-contracts/src/crm.openapi.json`
  —— codegen 产物（**禁手改**）。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 `15.3.2.2` 这一行**的状态与证据。

## 验证（真实命令与结果）

- L0 编译闸：`./mvnw -q compile`（backend）✅ ／ `pnpm typecheck` **3/3** ✅
- L1 模块闸：`./mvnw -pl services/crm -am test -Dtest='CustomerContact*' …`
  → **22 例全绿**（18 + 4），Testcontainers PostgreSQL 16 ✅
- 契约：6 个服务导出 OpenAPI → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → **diff 只含 crm**（另 5 个服务的契约文件 codegen 未改动）✅
- 前端：`pnpm vitest run apps/web/src/components/crm/customer-contact-model.test.ts
  apps/web/src/app/api/crm/customer-contacts-allowlist.test.ts` → **16 例全绿** ✅
- **api-proxy 全量 allowlist 回归**（改了共享白名单 ⇒ 必跑）：
  `pnpm vitest run apps/web/src/app/api` → **173 个文件 / 999 例全绿** ✅
- 额外自查：**整个 crm 服务全量 217 例全绿** ——
  证明本车道挂在 `crm_message` / `crm_buyer_email` 上的物化触发器
  **没有**打破 15.1 / 15.1.2.1 的既有测试。
- `pnpm lint` **3/3** ✅（残留 warning 均为既有文件，非本车道新增）

## 后续待办

1. **11 售后（trade 服务）接线自动写入**：在 trade 侧调 `POST /crm/customer-contacts/ingest`
   （转发调用方自己的 `Authorization` bearer、**不传租户号**、显式超时），
   完成后把本模块能力声明里 `auto_write_aftersales` 维度改为 `available=true`。
   ⚠️ 售后单**不带买家维度** ⇒ 归因链路仍是
   「售后单 → orderId → 订单 → customer_party_id → 客户」，**不猜买家**。
2. **15.5.2.1 关怀与催办**：接上本模块的 `GET /crm/customer-contacts/opt-out-check` 作为豁免依据；
   营销触达真正落地后，`MARKETING` 类型的接触记录会开始自动出现。
3. **15.3.1.1 / 15.3.1.2 客户列表 / 客户详情**：本模块的 `customer_ref` 仍是**逻辑引用**；
   客户档案落地后，需要决定「单店口径的 customer_ref」如何映射到合并后的逻辑客户
   （合并本身属 15.3.1.3）。
4. **前端导航**：`/crm/contacts` 目前没有进侧边导航（与 `/crm/tags` 等同批页面一样），
   导航注册不在本车道职责内。
