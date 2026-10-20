# 2026-10-20: 10.3.1.1 轨迹查询

## 摘要

交付能力蓝图 **10.3.1.1 轨迹查询**（channel 服务，阶段 D5，L3 锚点 10.3.1）。

蓝图职责是「查询并展示包裹物流轨迹：节点时间线（揽收-运输-清关-转运-派送-签收）、
预计时效对比、异常节点标记，支持按承运商批量拉取」，输出「轨迹时间线 + 预计到达时间 +
异常标记」，并加粗**「按承运商事件 ID 去重，同一节点重复推送不产生重复记录」**。

本车道是**续写**上一个 worker 的成果：接手时 worktree 里已有 6 个 Java 文件 +
V212 迁移 + 1 个集成测试（全部 untracked）。后端骨架质量可用（主源码 `mvnw compile`
直接通过），但**测试从未跑过**，实测 17 例里 1 失败 10 报错；且**多租户三断言根本没写**
（`actorB` 声明了却从未使用）。本轮做的是：保住可用骨架、**修掉三个真 bug**、
补齐缺失的租户隔离断言，再从零完成前端、契约与台账。

## 决策 / 结论

1. **去重落在 DB 唯一约束，不落在应用层**。
   `UNIQUE (tenant_id, carrier_code, carrier_event_id)`（V212）。服务层的
   `on conflict do nothing` 只是**友好**，真正的闸门是**绕过服务层直接 INSERT
   同一条事件同样撞 23505**。测试用应用账号与迁移超级账号**各试一次**。
   事件另加 append-only 双保险（`REVOKE UPDATE,DELETE` + `BEFORE UPDATE OR DELETE`
   触发器）—— 轨迹是「当时它在哪」的举证，事后改写等于伪造物流史。

2. **去重键带 `carrier_code`**。不同承运商的事件号空间互相独立：同一个
   `EVT-1` 在 A 家与 B 家是两条**不同**的事件，按 `(tenant_id, event_id)` 去重会把
   B 家的第一条误判成 A 家的重复。

3. **运单真值在 trade，本服务只经授权 HTTP 读**。
   `ShipmentTrackingQueryPort` 走 `GET /trade/fulfillment/shipment-records/records`，
   **转发调用方自己的 bearer**（租户隔离 = channel 侧 RLS + trade 侧 RLS **两道**），
   连接超时 3s / 读超时 10s。上游不可达 → **503 且理由写「读不到」**，
   **绝不返回空集合冒充「上游没有运单」**。

4. **时效承诺按发货当时算成快照**。同库只读 10.1.1.3 的 `logistics_transit_rule`；
   事后改档期不能让一笔已经延误的货变成准点。**ON_TIME / AT_RISK / DELAYED /
   NO_PROMISE 是读时算出来的**（是否延误取决于现在几点），**我方承诺与承运商预计
   分列不合并**（合并后界面上就分不清谁承诺的）。

5. **异常只标记不处置**。事件级 EXCEPTION 节点**必须**带异常码（表级 CHECK：
   标了异常却说不清是什么异常 = 假标记）。处置（联系承运商 / 理赔 / 补发 / 改址）
   归 10.3.1.2（未交付），capabilities 里显式 `available=false` + ownerModule + 影响。

6. **承运商出网通道未交付 ⇒ 刻意不放假 vendor 实现**。一个「返回成功轨迹」的假适配器
   会让「这票货走到哪了」在数据上看起来真的发生过 —— 而轨迹是丢件纠纷与平台
   有效追踪率申诉的**举证凭据**。没有通道时那轮拉取如实落 `NO_CHANNEL` 并写明理由，
   **不返回空轨迹冒充「承运商说没轨迹」**（「没轨迹」与「通道没接通」是两件事）。

7. **轮询只提供入口，不自带调度器**。多副本下自带定时器要做主节点选举与分布式锁，
   那是平台能力不是领域能力；外部调度（K8s CronJob / 平台任务执行器）触发一次入口
   反而天然单次、可重试、可审计。

8. **api-proxy 白名单新增一条独立 `channel/` 元素**，不往既有长 alternation 里并分支
   （同一服务两条车道并分支 = 结构性 git 冲突，retry 永不自愈）。插入锚点固定在
   10.1.1.2 `logistics-channels` **之后**、10.1.1.3 `logistics-transit-limits` **之前**。
   **裸路径 `logistics-tracking` 不放行** —— 后端这一组端点没有裸集合读，多放一段
   会让一个**还不存在**的路径看起来像已交付。

## 关键 trade-off / 讨论

- **本模块没有承运商轨迹客户端**（蓝图写「上游 = 承运商 API」，但 channel 服务里
  根本没有那个客户端）。选择：**定义端口 + 如实落 NO_CHANNEL**，而不是在通道缺失时
  假装能拉。理由同上（凭据不能伪造）。

- **`TrackingEventRow` 与 warehouse 服务同名 DTO 跨服务撞名**。契约 codegen 直接以
  「跨服务同名 DTO：字段级覆盖」中止 —— 同名不同结构会变成字段级覆盖，warehouse 独有的
  `fromStage / toStage / etaBefore / etaAfter` 会被本模块的 `carrierEventId / nodeType /
  exceptionCode` 悄悄顶掉，**消费端拿到「凭空多出的字段」而编译期不报错**。
  选择：**改名 `CarrierTrackingEventRow`**（不写进 allowlist 接受覆盖），
  名字同时对齐蓝图词汇「承运商事件」。

- **契约 codegen 会把 6 个服务的文件全部重写**（路径顺序随 springdoc 控制器注册顺序变）。
  实测对 channel / trade 都是**纯重排、零内容变化**（path 集合与 schema 集合逐一比对相同），
  且本仓历史每个车道提交都是这个形态。选择：**只提交 channel 的两个契约文件**，
  其余 5 个服务的重排 `git checkout` 还原 —— 与前一条车道提交（`367f0147`）的形状一致，
  不越界改别的模块的文件。

- **测试里「按目的国筛」这一条**：原测试把 `"DE"` 传给了 `nodeType` 位置（返回 0 条）。
  判定为**测试写错而非功能缺失**：`i % 2 == 0 ? "US" : "DE"` 的夹具 5 票里 DE 是
  **3** 票（原断言写 2，是计数笔误）。选择**补上真正的 `destinationCountry` 筛选维度**
  （跨境延误集中在某些国家，是轨迹查询的主筛选维度之一），并把断言改成 DE 3 票 / US 2 票
  **两侧都断言** —— 两侧都断言才能证明这一维真的在筛，而不是把全量退回来。

## 涉及文件

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V212__logistics_tracking_query.sql`
  —— 4 表 + 2 触发器 + RLS + GRANT，**零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）。

**后端**

- `backend/services/channel/src/main/java/com/starcon/channel/ShipmentTrackingQueryPort.java`
  —— 读 trade 运单集合的跨服务只读端口。
- `backend/services/channel/src/main/java/com/starcon/channel/TrackingQueryModels.java`
  —— 传输模型。
- `backend/services/channel/src/main/java/com/starcon/channel/TrackingQueryService.java`
  —— 业务服务（jOOQ 显式 SQL）。
- `backend/services/channel/src/main/java/com/starcon/channel/TrackingQueryController.java`
  —— 端点 `/channel/logistics-tracking/**`。
- `backend/services/channel/src/main/java/com/starcon/channel/carrier/CarrierTrackingAdapter.java`
- `backend/services/channel/src/main/java/com/starcon/channel/carrier/CarrierTrackingAdapterRegistry.java`

**测试**

- `backend/services/channel/src/test/java/com/starcon/channel/TrackingQueryIntegrationTest.java`
  —— 21 例，真实 PostgreSQL 16 + Testcontainers。

**前端**

- `apps/web/src/app/logistics/tracking/page.tsx`
- `apps/web/src/components/logistics/tracking-query-model.ts`
- `apps/web/src/components/logistics/tracking-query-model.test.ts`
- `apps/web/src/components/logistics/tracking-query-workspace.tsx`
- `apps/web/src/app/api/channel/tracking-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **共享白名单**，只新增一条独立 `channel/` 元素。

**契约 / 台账**

- `packages/api-contracts/src/channel.openapi.json`（codegen 产物，**禁止手改**）
- `packages/api-contracts/src/contracts/channel.ts`（同上）
- `docs/product/capability-blueprint/module-progress.md` —— 只改本模块那一行的「状态」「证据」两格。

## 接手时修掉的三个真 bug

上一个 worker 的代码**编译通过但跑不起来**（17 例：1 失败 10 报错）：

1. **`push` 的 INSERT 占位符错位**：18 列 / 18 个 `?` 但只传 17 个绑定 ——
   `?::jsonb` 落到了 `registered_by_membership_id` 头上，多出一个普通 `?`，
   症状是 jOOQ 报「未设定参数值 18 的内容」。**这一条直接卡死承运商推送这条唯一
   真实写入口**，也是去重用例失败的原因。
2. **4 处 `timestamptz` 列漏写 `?::timestamptz`**（拉取任务的 `started_at` /
   `finished_at`）：症状是 `column "started_at" is of type timestamp with time zone
   but expression is of type character varying`。
3. **筛选维度对不上**：测试把 `"DE"` 传给了 `nodeType` 位置。

另：**多租户三断言完全缺失**（`actorB` 声明未用）—— 已补齐，并给测试加上
`spring.datasource.hikari.maximum-pool-size=1`（串租户在单连接下最容易暴露）。

## 验证

- **L0 编译闸**：`./mvnw -q compile`（backend）通过；`pnpm typecheck` 三包绿。
- **L1 模块闸**：`./mvnw -pl services/channel -am test -Dtest='TrackingQuery*'
  -Dsurefire.failIfNoSpecifiedTests=false` → **21 例 / 0 失败 / 0 错误，BUILD SUCCESS**。
- **前端本模块**：模型 17 例 + allowlist 5 例 = **22 例绿**。
- **api-proxy 全量回归**：`pnpm vitest run apps/web/src/app/api apps/web/src/lib`
  → **91 文件 / 780 例绿**（另一次 101 文件 / 927 例全绿，但并行下有 8 个 vitest
  worker 启动超时，缩窄范围后复跑全绿 —— 是资源争用，不是断言失败）。
- `pnpm lint` 无新增告警（存量告警在 catalog / orders / warehouse，不在本模块）。

## 后续待办

- **承运商出网轨迹通道**（10.1.1.1 交付的是**档案与规则**，不是连接与凭据）——
  按 `carrier/<vendor>/` 独立子包实现 `CarrierTrackingAdapter` 即可，
  上层服务与数据模型不需要改动。
- **异常件处置**（10.3.1.2）—— 本模块只标记，处置入口归它。
- **轮询调度器** —— 外部调度触发 `POST /channel/logistics-tracking/pull-tasks` 即可。
- **逐箱轨迹** —— 跨境场景当前以主运单号为键。
- **运费成本归集**（12 财务）—— 本模块**刻意不涉及任何金额**。

## 关联

- `docs/product/capability-blueprint/10-logistics-and-freight.md`（10.3.1.1 原文）
- `docs/architecture/multi-tenancy.md` · `docs/decisions/0002-multi-tenancy-strategy.md`
- `docs/decisions/0004-java-spring-boot-microservices.md`（跨服务只经授权 API）
- `docs/product/capability-blueprint/module-progress.md`（台账，本模块那一行）
- 兄弟模块：10.1.1.2 渠道配置（V136）· 10.1.1.3 时效与限制（V139）· 10.3.2.1 交运批次（V129）
