# 15.2.2.1 Request a Review（索评）—— 车道交付记录

> 蓝图：15 卷 15.2.2.1（L3 锚点 15.2.2，阶段 D7）。落地服务 **crm**（15 客户与客服）。
> 车道分支 `feat/15-2-2-1`，迁移 `V220__crm_review_request.sql`。

## 决策摘要

1. **落地服务是 crm，不是 channel。** 蓝图卷 15 的实际归属是 15 客户与客服（crm 服务，端口 8086）；
   15.4.2.1 知识库已经在 crm 里，本模块与它**同服务、不共用表、不共用命名空间**：
   知识库管「这个格子此刻该给买家答哪一条」（内容），索评管「这一位买家、这一张已签收的单子，
   要不要向他发起一次索评」（触达）。
2. **加粗句「索评频次必须受限」落成三层**（而不是界面上的一行提示）：
   - 租户级硬上限表 `crm_review_frequency_policy`，每个值在 DB 层另有天花板；
   - 触发器 `guard_crm_review_request_frequency` 在**数据库层**判定每订单上限 / 每买家上限 / 冷却窗口，
     并强制「本次规则快照只能比硬上限更严」；
   - 服务端用**同一判据**预检，给出**可分类的原因码**，并把被挡的请求以 `status=REJECTED` + 原因**落库留痕**。
   **REJECTED / CANCELLED 不占额度** —— 否则「试一次被挡掉」会把额度吃掉。
3. **超限 = 记录下来，不是报错回滚。** 这是「留下原因」的字面落点：
   请求行存在、`reject_code` + `reject_reason` 有值、列表里筛得到。
   而**输入本身不合法**（上游没签收 / 店铺不在清单 / 规则比硬上限松）走 4xx，**不写行** —— 两类错误分开。
4. **跨服务只走端口。** 上游 10.3.2.2（channel，V217，本批刚并入集成头）里**确实**有能回答
   「这票已签收」的形状：`GET /channel/logistics-pickup-signoff/cases?status=DELIVERED` 带
   `salesOrderNo` 与 `deliveryConfirmedAt`，`GET /channel/logistics-pickup-signoff/cases/{id}` 取单件。
   本服务**一条 SQL 都不碰对方的库**，只经 `SignoffDeliveredOrderPort` 走授权 HTTP API，
   本库只存逻辑引用 + 快照。上游读不到一律 503，**绝不返回空集合冒充「没有已签收订单」**。
5. **上游没有的两个维度，如实标 `available=false`，不造数据源**：
   - `candidate_shop_attribution`（ownerModule=10.3.2.2）：确认单只有物流渠道，没有店铺；
   - `buyer_identity`（ownerModule=15.3.1.1）：本仓**没有买家档案**，买家标识由运营按店铺提供，
     **只作频次去重键**（与 trade V135「买家联系方式归 15 / 客户档案所有」同一纪律）；
   - `platform_review_api`（ownerModule=2）：平台侧索评接口未接入，发送记录只允许 `MANUAL_HANDOFF`。
6. **下游 15.2.1.1 未交付 ⇒ 只落评价回收桶**（收到的评价原文 + 关联到索评请求 + 星级 + 有图有视频），
   `review_sync` 维度 available=false，**不假装**已交付评价管理（无回复 / 无申诉 / 无标签）。
7. **不建 15.2.2.2 的策略配置表。** 索评规则配置（按店铺 / 按物流方式的延后时间、避开节假日、A/B 话术）
   归 15.2.2.2；本模块的规则是**一次请求自带的快照**，租户级频次硬上限是**兜底闸门**。

## 关键 trade-off

| 取舍 | 选择 | 代价 / 理由 |
| --- | --- | --- |
| 频次规则放哪 | **租户级硬上限表 + 请求自带规则快照** | 多一张表，但「放宽上限」必须有一条**改不动的**路径，否则加粗句能被一条参数绕过去 |
| 超限怎么处理 | **落库（REJECTED）+ 原因** | 比抛异常多一张状态，但「谁在什么时候想向哪位买家索评、被什么挡住了」可查 |
| 买家标识从哪来 | **运营提供**（只作去重键） | 本仓无买家档案（15.3.1.1 未交付）；造一个「自动生成」的买家号会让频次计数失去意义 |
| 平台索评通道 | **只允许人工交接** | 一个「返回成功回执」的假通道会让「买家已经被索评过」在数据上看起来发生过 —— 而过度索评的代价正是平台处罚 |
| 外键 vs 触发器 | **触发器** | batch-37 有车道给既有表建 FK 打断了同服务既有测试的 TRUNCATE；触发器（同批 V219 手法）零回归面，**既有测试一行未改** |
| 规则比硬上限更松 | **DB 触发器拒写** | 服务层预检只是提前告诉运营；真正拦得住的是 DB 层 |

## 文件清单

**后端（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V220__crm_review_request.sql` —— 四张表 + 四条触发器 + RLS + 授权
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRequestModels.java` —— 传输模型
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRequestService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRequestController.java` —— 12 个端点
- `backend/services/crm/src/main/java/com/starcon/crm/SignoffDeliveredOrderPort.java` —— 上游 10.3.2.2 只读端口
- `backend/services/crm/src/test/java/com/starcon/crm/ReviewRequestIntegrationTest.java` —— 22 例
- `backend/services/crm/src/test/java/com/starcon/crm/ReviewRequestTenancyIntegrationTest.java` —— 4 例（租户隔离三断言）

**前端**

- `apps/web/src/components/crm/review-request-model.ts` —— 前端模型
- `apps/web/src/components/crm/review-request-model.test.ts` —— 8 例
- `apps/web/src/components/crm/review-request-workspace.tsx` —— 工作区
- `apps/web/src/app/crm/review-requests/page.tsx` —— 页面
- `apps/web/src/app/api/crm/review-requests-allowlist.test.ts` —— 8 例
- `apps/web/src/lib/api-proxy.ts` —— **新增一条 `services[]` 元素**（既有条目一字未动）
- `apps/web/src/app/api/crm/[...path]/route.ts` —— 补 `export const PUT`（其余五个服务出口早已导出）

**契约 / 台账**

- `packages/api-contracts/src/crm.openapi.json` + `packages/api-contracts/src/contracts/crm.ts` —— codegen 产物（纯增量）
- `docs/product/capability-blueprint/module-progress.md` —— 只改自己那一行的「状态」「证据」两格

## 踩到的坑（留给下一条车道）

1. **`RAISE EXCEPTION 'msg', USING ERRCODE=...` 在**没有参数**时是语法错误** ——
   逗号会开启「参数列表」，`USING` 不是表达式。正确写法是 `RAISE EXCEPTION 'msg' USING ERRCODE=...`。
2. **PG 没有 `numeric * interval` 运算符** —— 小时数（`numeric(19,6)`）折算成 interval 要走
   `((hours * 3600)::bigint * interval '1 second')`。
3. **jOOQ 的 `Record.get(0, Long.class)` 返回装箱 `Long`，`(int) Long` 编译不过** ——
   先拆成 `long` 局部变量再窄化。
4. **类内自定义的 `post(...)` / `put(...)` 助手会遮蔽 `MockMvcRequestBuilders` 的静态导入** ——
   助手改名（`postJson` / `putJson`）。
5. **白名单正则里 UUID 分支前面漏了 `\/`** —— `(?:\/字面量|UUID...)` 的 UUID 那一支没有斜杠，
   于是 `{uuid}` 形状全被拒（且字面量分支照常通过，**很难一眼看出来**）。
6. **事务局部 GUC 在提交后被 PostgreSQL 复位成**空串**（占位符仍在会话里），不是 `NULL`** ——
   两者对 RLS 策略（`nullif(current_setting(...),'')::uuid`）**同义**，都表示「没有租户」。
   断言「没有租户上下文」时用 `isNullOrEmpty()` + `effectiveTenant() == null` + 「事务外看不到任何行」，
   不要写 `isNull()`。
7. **代码生成会顺带把别的服务的契约一起刷新**（集成头的 channel / trade 契约是陈的）——
   车道只提交**自己那个服务**的契约增量，其余服务的一律 `git checkout` 还原。

## 后续待办

- **15.2.2.2 索评规则**（未开发）：按店铺 / 按物流方式的延后时间、避开节假日、A/B 话术。
  落地后它管**策略配置**，但**改不动** `crm_review_frequency_policy` 的硬天花板 ——
  加粗句的兜底闸门必须留在本模块。
- **15.3.1.1 客户档案**（未开发）：买家主数据落地后，`buyer_identity` 维度可从
  available=false 转成 true，买家标识不再由运营提供。
- **平台侧索评接口**：平台适配器在 channel 服务的 vendor 层；接入后 `PLATFORM_API` 传输通道才可放开
  （当前服务端刻意拒绝）。
- **10.3.2.2 的确认单若补上店铺维度**，`candidate_shop_attribution` 才能从 available=false 转 true，
  批次也才能真正「按店铺过滤上游候选」（当前只能按「本次索评活动的目标店铺」记录）。
