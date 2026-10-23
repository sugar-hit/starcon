# 2026-10-23: 11.2.3.2 转可售/残次/报废

## 摘要

落地能力蓝图 **11.2.3.2「转可售/残次/报废」**（卷 11 售后与退货，L3 11.2.3 退货处置的第三格，阶段 D4）。
按质检结论把退货转入对应状态：可再销售 → 可售库存、残次 → 残次库存、报废 → 核销并计入损耗。
蓝图原话里的三条硬要求都落了物理落点：**报废不可逆**（独立审批权限 + 意见必填 + 职责分离 + 终态冻结 + 无反执行端点）、
**输出 = 库存状态变更分录 + 可售量更新 + 报废损耗记录**（分录与 bucket/balance 真落 warehouse 库，损耗台账真落 trade 库）、
**输入 = 质检结论 + 处置决定 + `expected_version`**（乐观锁 + DB 守卫双判）。

跨两个服务：**trade 拥有处置决定 / 审批 / 损耗记录 / 消费质检指令，warehouse 拥有库存状态变更执行**，
两者走授权 HTTP（trade→warehouse 写端口 + 候选库位读端口，调用方 bearer 转发），**两边各一道 RLS**。

## 决策 / 结论

1. **双服务切分（核心决策）**：trade = `return_inventory_disposition` / `return_inventory_disposition_event` /
   `return_inventory_disposition_loss`；warehouse = `inventory_return_disposition_execution`（只写执行回执，
   **不建第二张流水表**，分录本体仍是 warehouse 自己的 `inventory_entry`）。
   **理由**：库存事实的写入所有权在 warehouse（AGENTS.md「每张事实表只有一个写入所有者」+
   「禁止跨服务 SQL / FK / 共享视图」），trade 不能直连 warehouse 表改库存；而 11.2.3.1 的
   `return_quality_disposition_instruction` 在 trade 库、消费状态必须回写，所以「消费指令」留在 trade。
2. **报废不新建「报废 bucket」**：`inventory_quality_status.role` 只有 SELLABLE / PENDING_INSPECTION / DEFECTIVE，
   报废 = 从待检 bucket 核销减库存（新建第四个 role 要改别的模块的词表，且会让可售量口径变浑）。
3. **目标库位必填（转可售 / 残次）**：库位类型与质量状态一一对应
   （`location_allocation_required_type`：SELLABLE→STORAGE、PENDING_INSPECTION→QUARANTINE、DEFECTIVE→DEFECTIVE），
   来源库位由操作员显式指定，不猜。
4. **处置数量 = 质检结论数量**（照抄 8.2.2.2「一批待检货必须被全部处置掉」），由 DB 触发器真查比对。
5. **报废四道闸门**：独立权限码 `trade.aftersales.approve`、审批意见必填、提交人 ≠ 审批人、
   EXECUTED 后整行冻结且**全链路没有反执行端点**（DELETE 一律 405）。
6. **指令消费只做纯增量改动**：对 11.2.3.1 的指令表放宽 `instruction_status` CHECK 为 `IN ('PENDING','CONSUMED')`、
   **仅授 column 级 `GRANT UPDATE(instruction_status)`**、加消费守卫（PENDING→CONSUMED 且必须有一张 EXECUTED 处置单）；
   11.2.3.1 其余列与表级 UPDATE/DELETE 一律不动。
7. **损耗口径**：本模块算 `proposed_loss_amount = original_value − salvage_amount`，**DB 重算比对**；
   12 财务侧字段**恒为 null** + `finance_owner_module='12 财务'` + `loss_status='PENDING_FINANCE'`，
   **绝不填 0**（填 0 会被读成「已核销且无损耗」）。
8. **执行失败不抛 5xx**：落 `EXECUTION_FAILED` 后返回 200。抛错会让事务回滚把失败记录一起回滚掉，
   库里连失败的痕迹都不留。前端按 `disposition.status` 展示。
9. **契约类型改名避免撞名**：channel 服务已有 `ReturnDisposition*` 命名空间 ⇒ trade 契约类型统一改名
   `ReturnInventoryDisposition*`，warehouse 改名 `ReturnDispositionExecution*`。
10. **api-proxy 新增独立 `services[]` 元素**（首 token `return-dispositions`，锚在 `return-quality` 之后），
    不并入既有超长 alternation（多车道并入 git 必冲突）；刻意不放行 DELETE 与 `/resell` `/defective` `/scrap`
    `/salvage-posting` `/claim` 子路径。

## 关键 trade-off / 讨论

- **切分方案的三个被否决选项**：
  - ① 全落 trade 直连 warehouse 表 —— 跨库写，违模块边界纪律（每张事实表只有一个写入所有者）。
  - ② 全落 warehouse —— 则 `instruction_status` 无处回写，11.2.3.1 页面永远显示「待消费」，指令链断在半路。
  - ③ 复用 7.4.1.1 通用调整单 —— 调整单是通用库存动作，报废是带审批的**不可逆**业务决定，
    复用会把报废语义稀释成一行备注，且本模块要改的是别的模块的表。
- **为什么不用 `POST /warehouse/inventory/reservations` 顶替**：它记录预占、消耗可售量，
  把货「占住」而非「归还」，与转可售意图相反，且无补偿端点。
- **为什么不「造一个报废状态进 role 词表」**：role 词表是 7.1.1.3 可用量的公共口径，
  多一个 role 会让每个读 bucket 的查询都要多判一个分支。

## 涉及文件

**新增**
- `backend/services/trade/src/main/resources/db/migration/V337__trade_return_inventory_disposition.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnInventoryDispositionModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnDispositionService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnDispositionController.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnDispositionWarehousePort.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnDispositionIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnDispositionTenancyIntegrationTest.java`
- `backend/services/warehouse/src/main/resources/db/migration/V337__warehouse_return_disposition_execution.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ReturnDispositionExecutionModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ReturnDispositionExecutionService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/ReturnDispositionExecutionController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ReturnDispositionExecutionIntegrationTest.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/ReturnDispositionExecutionTenancyIntegrationTest.java`
- `apps/web/src/components/aftersales/return-disposition-model.ts`
- `apps/web/src/components/aftersales/return-disposition-workspace.tsx`
- `apps/web/src/components/aftersales/return-disposition-model.test.ts`
- `apps/web/src/app/aftersales/return-dispositions/page.tsx`
- `apps/web/src/app/api/trade/return-disposition-allowlist.test.ts`

**修改**
- `apps/web/src/lib/api-proxy.ts`（新增独立 `services[]` 元素）
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/contracts/warehouse.ts`
- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/warehouse.openapi.json`
- `docs/product/capability-blueprint/module-progress.md`（第 354 行 11.2.3.2 行的「状态」「证据」）

**契约生成命令（禁止手改生成物）**
```
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```

## 验证结果

- L0：`cd backend && ./mvnw -q compile` → 绿（EXIT=0）；`pnpm typecheck` → **3/3 成功**
- L1 trade：`./mvnw -pl services/trade -am test -Dtest='ReturnDisposition*'` → **16 例绿**（集成 13 + 租户 3）
- L1 warehouse：`./mvnw -pl services/warehouse -am test -Dtest='ReturnDispositionExecution*'` → **12 例绿**（集成 9 + 租户 3）
- 前端：`return-disposition-model.test.ts` → **10 例绿**；`return-disposition-allowlist.test.ts` → 5 例绿
- api-proxy 全量白名单回归：`pnpm vitest run apps/web/src/app/api/trade` → **71 文件 / 394 例绿**（既有 trade 入口未误伤）

## 后续待办

1. **12 财务（`backend/services/finance/`）尚不存在** ⇒ 损耗的财务侧字段恒 null 等 12 财务接手。
   本模块已把 `finance_owner_module` / `loss_status` / capabilites 的 `available=false` + 归属模块留好位。
2. **7.4.1.1 调整单管理仍未开发** —— 本模块自建专用写端点而非复用；若 7.4.1.1 后续交付，
   需要评估两套库存动作入口是否收敛（本模块的报废语义**不**应被通用调整单稀释）。
3. **5.4 调价**不在本模块范围：转可售只改库存质量态、不动价。
4. 下游 **7.1.1.3 可用量**读的是 warehouse 自己的 bucket/balance，本模块已同库更新，
   但跨车道做一次端到端确认仍属上游车道职责。

## 关联

- 能力蓝图台账：`docs/product/capability-blueprint/module-progress.md`（11.2.3.2 行）
- 上游 11.2.3.1（质检结论与处置指令）：`backend/services/trade/.../db/migration/V179__*.sql`
- 落点依据：`docs/architecture/multi-tenancy.md`、`docs/decisions/0002-multi-tenancy-strategy.md`
- 质量口径参照：8.2.2.2（一批待检货必须被全部处置掉）
