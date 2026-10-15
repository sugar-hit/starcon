# 2026-10-15 10.1.1.1 承运商列表

## 结论

10.1.1.1「承运商列表」在车道 `feat/10-1-1-1` 交付：channel 迁移 `V134__logistics_carrier_profile.sql`、
`CarrierProfileQueryPort` + `CarrierService` + `CarrierController`、页面 `/logistics/carriers`、
14 例后端集成测试（真实 PG16 Testcontainers）、api-proxy 白名单最小追加、OpenAPI 契约重新生成。
L0 编译闸 + L1 模块闸全绿。

## 关键决策

### 1. ⚠️ 上游归属实查结论与派单说明不一致：上游在 **catalog**，不在 identity

派单说明写「3.6.1.2 已交付在 identity（`V30__*.sql`，`/legal-entities` 形状）」。实查：

| 派单说明的说法 | 实际 |
|---|---|
| `V30__*.sql` 在 identity | 在 **catalog**：`V27__party_profile.sql`（`party` + `party_role`）+ `V30__party_role_profiles.sql`（`party_carrier_profile`） |
| `/legal-entities` 形状 | identity 那边是 `V5__legal_entity.sql` = 17.1.2.1 **法人档案**，那是「我们自己这个开票/记账主体」 |

照派单说明去读 `/legal-entities` 会把**我方法人**当成**上游承运商** —— 一个能跑通但业务上完全错误的接法。
已在迁移文件头与服务类注释里把这个纠正写死，避免后来人照抄。

**蓝图加粗纪律一字未改**：承运商身份主权在 catalog，本模块**不建承运商主档表、不提供建承运商身份的端点**。

### 2. 字段归属用一张表钉死（迁移文件头第一节）

catalog 主权：主体名称/编码/类型/国家/联系方式、承运商角色、接口类型、账号、服务能力、主体状态与可用性。
本模块主权：承运商类别、时效等级、覆盖国家、支持业务类型、凭据引用与掩码、可用性判定。

catalog 那些以**只读快照**落库，由 `POST /channel/carriers/sync` 刷新。DB 触发器
`guard_carrier_snapshot_refresh` 要求改快照必须推进 `synced_at`，业务端点想改也改不动。
`SaveCarrierRequest` **不含任何身份字段**，从源头杜绝「同一个承运商在两处各改一次」。

### 3. 可用性：DB 重算 + 服务层合成，不接受手填

- DB 层 `readiness`（NOT_REGISTERED / NO_CREDENTIAL / NO_COVERAGE / NO_BUSINESS_SCOPE / READY）
  由触发器在写入路径上**重算并比对**，绕过服务层写 SQL 谎报 READY 同样被拒。照抄 `guard_handover_batch_counts`。
- 对外 `availability`（PARTY_BLOCKED / 透传 readiness / MANUAL_ONLY / AVAILABLE）在服务层读时合成，
  因为主体可用性与接口类型在上游、本库看不见，硬存就会退化成管理员手填。
- 优先级 **主体不可用 > 配置缺口 > 仅人工**：上游说不能做生意了，配置再齐也没用。
- 筛选与统计**共用同一条 SQL CASE 表达式**，避免「筛出来的 3 条」和卡上的数字各说各话。

### 4. 凭据：CHECK 钉死引用形状

`secret_ref` 只接受 `secret://` / `kv://` / `vault://` / `external-secrets://` 开头，真实 token 写库即拒
（真实 token 不可能带这四个前缀）。另有「有引用必须有真掩码（含 `*`）」——否则界面会显示成「没配凭据」，
运营会重复登记，把同一个承运商登记两遍。测试里只出现引用与掩码，**零真实凭据**。

刻意**不做**：校验引用在密钥管理器里是否真的存在（跨系统，不是数据层能负责的）。

### 5. 同步只带身份，不填物流字段

`carrier_category` / `service_tier` 可空且同步不填 —— 上游没有依据推出来，凭空给默认值正是
「硬编码常量当数据源」。同步建出来的行是 `NOT_REGISTERED`，如实表达「上游已登记，物流侧还没配」。
重跑同步**不覆盖**物流字段与备注（绝不把运营登记过的配置悄悄撤掉）。

### 6. 踩坑记录

- **PostgreSQL 的 `RAISE` 没有 C 的 `%s` 转换**：写成 `'%s'` 会被当成「占位符 + 字面量 s」，
  错误消息里冒出「缺s」这种话。全仓统一用裸 `%`（与 V129 一致）。
- **`= any(?::uuid[])` 在 jOOQ 里要小心**：`sql.fetch(sql, uuidArray)` 会被 varargs 摊平成 N 个参数，
  PG 报 `cannot cast type uuid to uuid[]`。改成显式 `in (?,?,...)` 占位符列表。
- **bsd grep 没有 `-P`**：查控制字符要用 python 或 `grep -a`。

### 7. 跨车道

- 迁移**零 ALTER 任何既有表**（含 V129 的 handover_* 六张），FK 只指向本迁移自建表。
- 契约重新生成时发现 **trade 契约在集成头上本来就是陈旧的**（缺 DropshipMargin / DropshipFeeRate 等
  DTO，且没有 codegen 头注释）。**已 revert trade 的两处改动**，避免踩到另外两条 trade 车道。
- api-proxy 只在 channel 既有 alternation 内层追加 `carriers` 一支；逐字节验证：
  去掉我那一支后与旧 pattern **完全相同**（长度差 57 == 分支长度 57）。

## 文件清单

后端
- `backend/services/channel/src/main/resources/db/migration/V134__logistics_carrier_profile.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/CarrierModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/CarrierProfileQueryPort.java`
- `backend/services/channel/src/main/java/com/starcon/channel/CarrierService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/CarrierController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/CarrierIntegrationTest.java`

前端
- `apps/web/src/components/logistics/carriers-model.ts`
- `apps/web/src/components/logistics/carriers-model.test.ts`
- `apps/web/src/components/logistics/carriers.tsx`
- `apps/web/src/app/logistics/carriers/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（最小追加）
- `apps/web/src/lib/api-proxy-union.test.ts`（5 放行 + 9 反向）

契约（codegen 产物）
- `packages/api-contracts/src/contracts/channel.ts`
- `packages/api-contracts/src/channel.openapi.json`

台账 / 日志
- `docs/product/capability-blueprint/module-progress.md`（只改 10.1.1.1 一行的状态与证据）

## 验证

- `./mvnw -q compile` 通过
- `pnpm typecheck` 三包绿
- `./mvnw -pl services/channel -am test` → **103 例零回归**（本模块 14）
- `pnpm vitest run`（本模块 + api-proxy）→ **159 例绿**

## 后续待办

1. **10.1.1.2 渠道配置**（同 L3 兄弟，未交付）：本模块的承运商档案是它的上游，
   capabilities 里 `channel_configuration` 已标 `available=false` + ownerModule + 影响。
2. **10.1.1.3 时效与限制**：时效等级已在本模块登记，但渠道级的预计到达/截单时间/禁运品类仍缺。
3. **承运商系统对接**：V129 的 `carrier_dispatch` / `carrier_receipt` 仍无 vendor 实现。
   本模块交付后，`secretRef` 已可用于将来接真实适配器，但**适配器仍是空的**（不放假实现）。
4. **trade 契约陈旧**：集成头上的 `trade.ts` 落后于 trade 服务现状，应由 trade 车道的调度器在批次末尾统一重新生成。
