# 7.5.2.1 跨法人调拨（阶段 D3）

- 日期：2026-10-07
- 车道：`feat/7-5-2-1`（worktree `.worktrees/7.5.2.1`）
- 蓝图锚点：`docs/product/capability-blueprint/07-inventory.md` 7.5.2.1（L3 锚点 7.5.2）

## 决策摘要

**跨法人调拨不能复用 7.5.1.1 的表。** 蓝图说「货权变了意味着利润归属变了，这是财务处理而非库存处理的差异点」，落到数据上有三处不可回避的差别：

1. **单头两个法人**。V61 的 `transfer_order` 只有一个 `legal_entity_id`，语义是「这批货算哪个法人的货权」，前后不变；本模块是 `from_legal_entity_id` / `to_legal_entity_id`，`CHECK(... <> ...)` 在 DB 层堵死「同一法人走本模块」。
2. **定价必填**。单价 NOT NULL 写在迁移里而不是「可以后补」——没有金额就没有内部结算凭据，12 财务无从挂账。
3. **双方确认是两个独立端点**。`/source-confirm` 与 `/target-confirm` 分开，而不是一个端点加个参数：让调用方在请求体里声明自己代表哪一方，等于让它可以自称另一方。

**货权转移点定在「实收」而不是「发出」。** 沿用 7.5.1.1「调拨不是瞬时」的纪律：发出只扣转出法人余额、货挂在途；实物在路上时货权归属仍有争议。而**结算凭据在发出时开具** —— 那时转出方已交付、转入方已确认定价，财务据以挂账。两个时刻不同是业务事实，页面上也分开显示。

**行金额用生成列。** `amount numeric(19,4) GENERATED ALWAYS AS (round(quantity*unit_price,4)) STORED`。金额是数量的函数，普通列意味着「改了数量忘了改金额」这种行写得出来，而它会直接变成内部结算凭据上的错数。舍入只发生在落 `numeric(19,4)` 这一步（PostgreSQL 四舍五入、远离零），数量与单价不做中间舍入以免双重舍入。

**在途口径复用共享的 `inventory_transfer`。** 本模块的在途腿 `ref_type='CROSS_ENTITY_TRANSFER'`、`ref_id=单据 id`，因此 7.1.1.1 / 7.2.2.1 / 7.1.3.1 / 7.5.1.2 那条 `sum(quantity) where status='IN_TRANSIT'` 一行都不用改就仍然正确。`inventory_transaction` 的 `ref_id` 则记**事件** id（发货/收货各一），因为 V1 的 `UNIQUE(tenant_id, ref_type, ref_id)` 挡不住同一 `ref_id` 上既有发出又有实收两条分录。

**整单发运，不拆批次。** 转移定价是整单口径，部分发运会让「一张凭据对应哪几行货」失去唯一答案，而凭据正是 12 财务的取数入口。这是有意的范围决策，不是半成品。

**凭据状态只有 ISSUED / ACKNOWLEDGED。** 刻意**没有**「已结算/已入账」—— 那属于 12 财务的账，本模块无权宣告。也刻意**没有** CANCELLED：凭据只在发出时开具，而作废只允许发生在发出之前（那时还没有凭据）；发出之后结案若有短少，凭据保持 ISSUED 并把短少量逐行带给 12，因为部分货权确实转移了。

## 关键 trade-off

- **两个不同自然人 vs 逐法人授权**：17 权限的双方法人授权未交付。当前用 DB 触发器钉死「两个方向的确认人必须是两个人」，并把这一事实随详情响应 `authorizationNote` 返回、页面常驻显示。宁可显式说「还没接」，也不让「能点确认」被读成「有权代表该法人」。
- **auto-create 余额行 vs 准确报错**：`balanceId()` 会现建 0 余额行（沿用 7.5.1.1）。但发出前先 `requireSourceBalance` 查一次，否则货不在转出法人名下时报的是「可用量不足：可用 0」，把排查引到完全不同的地方。
- **自己写 proxy 测试文件 vs 并进 route.test.ts**：`route.test.ts` 属于 7.1.1.1 / 7.5.1.1 等已交付车道，并进去就要改别人在维护的清单。新建 `cross-entity-transfer-allowlist.test.ts` 只钉本模块自己的 11 条调用点。

## 文件清单

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V64__warehouse_cross_legal_entity_transfer.sql`

**后端**

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LegalEntityTransferModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LegalEntityTransferService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LegalEntityTransferController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/LegalEntityTransferIntegrationTest.java`

**前端**

- `apps/web/src/app/inventory/cross-entity-transfers/page.tsx`
- `apps/web/src/components/inventory/legal-entity-transfer.tsx`
- `apps/web/src/components/inventory/legal-entity-transfer-model.ts`
- `apps/web/src/components/inventory/legal-entity-transfer-model.test.ts`
- `apps/web/src/app/api/warehouse/cross-entity-transfer-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（白名单，在 `inventory/(?:…)` 组**内**新增分支）

**契约（codegen 产物，只读）**

- `packages/api-contracts/src/catalog.ts`
- `packages/api-contracts/src/warehouse.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 7.5.2.1 那一行）

## 跨服务交接方式（下游均未交付）

**12 财务（内部结算 / 收入确认）**

- 定位键：`cross_entity_settlement` 的 `source_system`（固定 `'warehouse'`）+ `reference_id`（= 跨法人调拨单 id）。
- 唯一性：`UNIQUE(source_system, reference_id)` + `UNIQUE(tenant_id, transfer_id)`，保证一单一张凭据、重复投递不会重复计提收入。
- 取数形状：`GET /warehouse/inventory/cross-entity-transfers/{id}/settlement`，含金额、币种、双方法人、逐行数量/单价/金额/实收/差异。**不含**科目、凭证行、税额与汇率 —— 那些属 12 自己的权威，复制一份只会造出两份对不上的账。
- 未发运时返回 404 而非空壳：「还没发运所以没有凭据」与「凭据存在但金额为 0」必须能分开。
- **不建跨服务外键、不查 12 的表、不用 `starcon_app` 直连本库**（迁移末尾照例 `REVOKE ALL … FROM starcon_app`）。

**17 权限（双方法人授权）**

- 未接入。当前可执行约束：同租户 + 租户级权限码 `warehouse.inventory.{read,update}` + DB 触发器钉死两个方向的确认人必须是两个人。
- 缺口说清楚：能调用本端点**不等于**有权代表某个法人，两者当前没有绑定。接入 17 后会在同一处（`AccessService.require` 之后）叠加逐法人校验。

## 后续待办

1. **17 权限交付后**接入逐法人授权，并在 `authorizationNote` 里改掉「尚未交付」的措辞。
2. **12 财务交付后**确认取数方式（轮询授权 API 还是订阅事件），并评估是否需要凭据回写字段。
3. **批次级拆分**：与 7.5.1.1 口径一致，本模块整单发运、明细不按批次拆分；`receive` 的 `lotId` 可选（给了落该批次、不给落无批次 bucket），批次级拆分属 7.3.1.x 的演进范围。
4. **跨币种**：本模块整单单币种且不存汇率。跨币种换算与汇兑损益随 12 财务一并交付。

## 验证结果

- `backend/ ./mvnw verify`：全绿（warehouse 服务含本模块 26 例）
- `LegalEntityTransferIntegrationTest`：26 例全绿（Testcontainers PostgreSQL 16，连接池压到 1）
- `pnpm typecheck`：3/3 通过
- `pnpm lint`：通过（仅余 catalog 车道的既有 warning）
- `pnpm test`：1155 通过 / 65 文件；**基线 1142**（移走本模块两个测试文件实测）→ +13，**通过数未下降**
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`：成功
- `node tools/module-progress/sync.mjs --check`：`✓ 台账与蓝图一致`
- codegen 后类型名集合：**新增 24 个 `LegalEntityTransfer*`，消失 0 个**（`catalog.ts` 原始 diff 5858/4344 行只是键序重排）
