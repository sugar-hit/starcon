# 2026-10-07 3.6.1.2 角色档案

## 结论

3.6.1.2「角色档案」在车道 `feat/3-6-1-2` 交付：迁移 `V30__party_role_profiles.sql`、
三张 1:1 档案表的 jOOQ 服务与端点、`/partners` 页面的档案面板与跨主体台账、
46 例后端测试 + 21 例前端模型测试、OpenAPI 契约重新生成。
上游 3.6.1.1（`V27__party_profile.sql`）的交接契约按 V27 第三节兑现。

## 关键 trade-off

### 1. 档案键选 `role_id` 而不是 V27 交接说明里写的 `party_id`（**唯一的解释性偏离**）

V27 第三节写的是「主键 `(tenant_id, party_id)`，复合外键指向 `party_role(tenant_id, id)`」。
这两句无法同时成立：`party_role` 上一个主体最多挂三个角色（`UNIQUE(tenant_id,party_id,role_type)`），
所以 `(tenant_id, party_id)` 上**建不出唯一约束**，不是合法外键目标；它真实存在的唯一列组是
`UNIQUE(tenant_id,id)`。

落地选择：档案键在角色 id 上 —— `PRIMARY KEY (tenant_id, role_id)` +
`FOREIGN KEY (tenant_id, role_id) → party_role(tenant_id, id) ON DELETE CASCADE`。

- 代价：档案不再直接带 `party_id`，读档案要经 `party_role` 关联 `party`（一次 JOIN，列表查询本来就必须 JOIN）。
- 收益：**不需要给已交付的 `party_role` 补任何索引或约束**（加索引等于改别人的表），
  而「一个主体最多一份供应商档案」由两条传递性保证自动成立。
- 刻意**不加** `UNIQUE(tenant_id, party_id)`：它是上面两条的推论，多加一条只会多一个
  「档案与角色可以不一致」的口子。
- 已在 V30 第二节与台账证据里写明这个解释。

### 2. 「没设额度」与「额度为 0」在业务上相反，所以 0 是非法值

蓝图只说「信用额度」。若用 `0` 表示「不限制赊账」或用 `NULL` 表示「不许赊」，
12 应收会取到相反的默认。因此约定：**留空 = 不设额度 = 不限制赊账；0 判非法**。
同理「没设账期」与「账期 0」在 B2B 里是同一件事（就是现结），所以账期用 `int DEFAULT 0`，
不引入第三种状态。前端表单留空时不会被默默补成 0。

### 3. 额度币种不单独建列，与 `default_currency` 绑成一个字段

分列存储会出现「额度 10 万 USD / 默认币种 EUR」——12 应收无法判定拿哪个币种比，
而界面必须让用户挑一个、挑错了没有任何东西会报错。绑成一个字段后该状态不存在。
`CHECK(default_credit_limit IS NULL OR default_currency IS NOT NULL)` 兜住「有额度没币种」。

### 4. 枚举只增不改（R6）

合作等级 / 结算方式 / 客户分级 / 接口类型 / 服务能力五个词表的既有取值**不许改语义**，
它们已经写进历史采购单与应收单的冻结快照。拿不准该加值还是塞备注时**加值** ——
理由是备注是兜底而不是收纳箱。

### 5. 写入用显式插入分支 + 更新分支，不用 `on conflict` upsert

乐观锁要区分「这行还不存在（version 只能是 0）」与「这行存在（version 必须相等）」两种情况，
而 upsert 只有一个出口：并发的两个请求会「都以为自己是新建」，被静默合并成一行，
后到的覆盖先到的而没有人收到 409。

### 6. 读端点在「没有对应角色」时返回 200 + `profileFound=false` 而不是 404

详情页要区分**没指派身份 / 指派了但没建档案 / 已建档案**三种状态，而 404 只能表达成「什么都没了」。
主体不存在才 404。写入路径相反：没有对应角色时给可读的 409（规则 R3，文案是「先指派身份」）。
两个方向刻意相反，各有各的理由。

### 7. 不加「已用额度 / 剩余额度」

那要读 12 财务自己的应收台账，跨服务读表是 AGENTS.md 明令禁止的。
本模块只给上限，不给余额。已在 V30 第八节写明。

### 8. 币种与单位列表刻意不由本模块提供

ISO 4217 词表是 12 财务的资产、计量单位是 3.3.3 的资产。在本模块硬编码一份等于复制别人的写入所有权，
两份列表迟早会不一样（一方加了新币种另一方不会跟）。所以 `/role-profiles/options` 不返回币种，
这两项走自由输入 + 服务层格式校验。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V30__party_role_profiles.sql`

**后端**
- `.../catalog/PartyRoleProfileRules.java`（新增，纯规则 R1~R7）
- `.../catalog/PartyRoleProfileService.java`（新增，jOOQ 显式 SQL）
- `.../catalog/PartyRoleProfileController.java`（新增）
- `.../catalog/CatalogModels.java`（追加 3.6.1.2 的 DTO）

**测试**
- `.../catalog/PartyRoleProfileIntegrationTest.java`（新增，24 例）
- `.../catalog/PartyRoleProfileRulesTest.java`（新增，22 例）
- `apps/web/src/components/catalog/party-role-profile-model.test.ts`（新增，21 例）

**前端**
- `apps/web/src/components/catalog/party-role-profile-model.ts`（新增）
- `apps/web/src/components/catalog/party-role-profile-workspace.tsx`（新增）
- `apps/web/src/components/catalog/party-workspace.tsx`（挂载面板 + 台账）
- `apps/web/src/lib/api-proxy.ts`（放行本模块路径）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（3.6.1.1 那条「supplier-profile 应被拒」的断言翻转）

**生成物 / 台账**
- `packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`（codegen）
- `docs/product/capability-blueprint/module-progress.md`（只改 3.6.1.2 一行 + sync 重算的计数）

## 后续待办

- **契约 diff 超出本模块范围**：`catalog.openapi.json` 在集成头上是陈旧的
  （缺 3.7.2.1 的 `/catalog/physical-safety/*`，springdoc 的 operationId 序号也已整体位移）。
  本次重新生成顺带刷新了它，所以该生成物的 diff 远大于本模块自身新增。
  并入时若与其它车道冲突，以「类型名 / OpenAPI 路径 / 模块 ID」求并为准，不要按行覆盖。
- **06 采购单**：`GET /catalog/parties/{id}/supplier-profile` 作为新单默认值来源，
  订单行按 MOQ 校验，单据侧冻结快照。
- **12 应收应付**：取客户额度 + 币种 + 账期判超额与算到期日；「已用额度」归 12 自己算。
- **10 物流**：按 `interface_type` 决定运单能否自动跟踪，按 `service_capabilities` 选承运商。
- **3.9.2.2 僵尸主数据清理**：`missingProfileCount` 与 `noRoleCount` 一起作为候选来源。
- 若将来扩展角色类型（分销商、代理商），`roleTypeFor` 与三张表要一起加；
  加了角色类型后 `PARTY_ROLES` 的 `MAX_ROLES` 注释里已经预留了空间。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS（6 模块；catalog 869 例，0 失败，其中本模块新增 46 例）
- `pnpm typecheck` → 3/3 任务成功
- `pnpm lint` → 3/3 任务成功（只有既有的 `variant-combination-workspace.tsx` 两条 warning）
- `pnpm test` → 40 文件 / 673 例通过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功
- `node tools/module-progress/sync.mjs --check` → 一致