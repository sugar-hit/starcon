# Batch 47 —— 平台托管备货 / 平台价格同步 / 采购申请 / 认证与资质

> 并行车道流水线 · 批次 47 · 2026-10-21（08:00 tick）
> 集成头 `integration/module-dev` · 4 条车道并行开发，L2 批量验证每批只跑一次
> 批次号接在 batch-46 之后（`docs/sessions/2026-10/` 下现有最大为 batch-46，已确认无重号）

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道分支 | 车道 commit |
| --- | --- | --- | --- | --- | --- | --- |
| 7.7.2.2 | 平台托管与全托管备货 | D3 | warehouse | `V244__consignment_stock_ledger.sql` | `feat/7-7-2-2` | `eb14d63b` |
| 5.4.2.1 | 平台价格同步 | D6 | catalog | `V245__platform_price_sync.sql` | `feat/5-4-2-1` | `5d0e93d4` |
| 6.2.2.1 | 采购申请 | D6 | trade | `V246__purchase_request.sql` | `feat/6-2-2-1` | `5487bf48` |
| 16.2.2.2 | 认证与资质 | D7 | channel | `V247__product_certification.sql` | `feat/16-2-2-2` | `7404eb85` |

4 条车道落在 **4 个不同服务**、**4 个互不相同的 L3 锚点**（`7.7.2` / `5.4.2` / `6.2.2` / `16.2.2`），
彼此无依赖、无共享迁移版本号。

### 本批静态预检（合并前先行）

在 `V244`–`V247` 四个新迁移上跑「已知互相破坏形状」扫描：

| 形状 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **零命中**（V246 里两处是注释里提到这两个词） |
| ② `SET NOT NULL` | **零命中**（同上） |
| ③ `ADD CONSTRAINT ... CHECK` | **零命中** |
| ④ `ALTER TABLE` 既有表 | **零命中** —— 4 个迁移里的 `ALTER TABLE` 全部只作用于**本迁移自己新建的表**（开 RLS） |

⇒ **跨车道 DDL 回归面 = 0**。四个迁移都是「纯新建表 + 零 ALTER 既有表」。

### api-proxy.ts 合并形态复核

`apps/web/src/lib/api-proxy.ts` **不在** `merge-union` 驱动器的覆盖范围内（驱动器只覆盖
`packages/api-contracts/src/**` 与台账），它是普通 git 合并 ⇒ 插入点必须互不重叠。

本批 4 条车道的插入块在基线 `dd607c5b` 上的实际区间：

| 车道 | 插入块区间（基线行号） |
| --- | --- |
| 6.2.2.1 | 3993 – 4035 |
| 7.7.2.2 | 4232 – 4307 |
| 16.2.2.2 | 4409 – 4467 |

合并后复核 `git diff dd607c5b ce656ac0 -- apps/web/src/lib/api-proxy.ts`：
**删除行数 = 0**（纯增量）⇒ 没有静默删掉别人已交付的路由。

---

## 模块 7.7.2.2 平台托管与全托管备货

**这个功能做什么**：把「我方发给平台仓备货」和「平台仓里的货」分成两本账 ——
备货台账记我方发出多少，平台在库 / 平台已售 / 结算未结由平台回执推导；
两者对不上时给出**有符号的差异量**（完全对平 / 在途未确认 / 平台多收 / 平台出库超过入库），
并按平台自己的结算规则算出未结量。

**技术落地**：

- 迁移：`backend/services/warehouse/src/main/resources/db/migration/V244__consignment_stock_ledger.sql`
  — **5 张新表、零 ALTER 既有表**，全部 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY`
  + `GRANT`；外键一律用触发器不用 FK。
- 端点：**19 个** `/warehouse/inventory/consignment-stock/*`（按 `@XxxMapping` 逐条统计）——
  能力声明、备货预报预览、备货单台账 / 详情 / 审计 / 台账明细 / 回执 / 对账 / 结算口径 / 结算回执、
  店铺信息、建单 / 加行 / 发货 / 登记回执 / 登记结算回执 / 关闭 / 作废。
- 页面：`/inventory/consignment-stock`（备货单列表 + 明细 + 对账 + 结算口径）。
- 测试：**34 例后端** — `ConsignmentStockIntegrationTest` 31 例 + `ConsignmentStockTenancyIntegrationTest` 3 例
  （租户隔离三断言）+ 前端 allowlist 17 例。

**关键设计决策**：

1. **「备货 ≠ 自有库存」落成三道数据库不变量**，不是一句注释：
   明细列 `counts_as_owned_asset` 恒 false 且 CHECK 写死；`inventory_entry` 上挂触发器
   `guard_consignment_stock_entry_not_added`（`ref_type='CONSIGNMENT_STOCK'` 的分录
   `on_hand_delta` 只许 ≤ 0、`reserved_delta` 必须 = 0）—— 把平台在库并进自有库存余额这件事
   **在写入那一刻**就被数据库挡住，绕过服务层直连 SQL 一样被拒；
   **平台在库 / 已售 / 结算未结不落库**（由回执推导，落了就会漂移）。
2. **四个口径是四个字段**，不是一个「库存量」换名字用：
   备货量（我方发出）/ 平台在库（入库回执 − 出库回执）/ 平台已售（出库回执）/ 结算未结（已售未结）。
   「备货量 − 平台入库」是**在途未确认**，不是差异损失。
3. **结算两套真算法**（按件 / 按日），集成测试用同一组回执断言未结量 **25 ≠ 20** ——
   口径是装饰的话两者会相等。口径离开草稿态即冻结（触发器 `guard_consignment_stock_basis_frozen`），
   中途改口径会让要交给 12 结算的未结量出现两个答案。
4. **只读 `inventory_balance` 做分账对账，从不写** `stock_bucket` / `inventory_balance`
   （那是 7.1.1.3 那条线的唯一写入所有者）。
5. **经营模式（2.3.2.1）与备货预报（10.4.2.2）都只读、不建影子表，但真的起作用**：
   channel 不可达 ⇒ 503 报「读不到」（绝不猜一个模式）；channel 正常但没有这家店 ⇒ `known=false`
   （**无记录 ≠ 模式已知**）；自发货模式下建单直接 400。

---

## 模块 5.4.2.1 平台价格同步

**这个功能做什么**：平台可能拒绝或调整我方回写的价格（如低于最低价被拒）。
本模块把「回写任务 / 平台实际承接价 / 与期望价的差异」做成三样可查可算的事实，
而不是假设回写即成功。

**技术落地**：

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V245__platform_price_sync.sql`
  — **3 张新表、零 ALTER 既有表**，全部 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + `GRANT`。
- 端点：**10 个** `/catalog/price-sync/*`（按 `@XxxMapping` 逐条统计）—— 能力声明、规则 CRUD、
  平台定价规则、发起同步、回写记录台账 / 详情、差异台账、登记平台回执。
- 页面：`/pricing/sync`（规则维护 + 同步记录 + 差异清单）。
- 测试：**36 例后端** — `PlatformPriceSyncIntegrationTest` 22 例 + `PlatformPriceSyncTenancyIntegrationTest` 3 例
  + `PlatformPriceSyncRulesTest` 11 例；前端模型 18 例。

**关键设计决策**：

1. **不自造第二套队列、不自己造平台 HTTP 客户端**：回写任务复用 channel 已交付的 02 队列
   （2.5.1.1 `platform_operation`，`operation=UPDATE_PRICE`）。本仓没有真实平台账号与网络出口，
   写一个假客户端打真实域名只会制造假成功。
2. **平台定价规则是租户自己配的真实数据**（`platform_price_rule` 表），不是代码里的常量：
   `MIN_PRICE` ⇒ **REJECTED**（本次不回写）；`AUTO_COMPARE` ⇒ **ADJUSTED**（压到对比价）；
   `MULTI_SITE_LINK` ⇒ **ADJUSTED**（拉到带边界）。
3. **判定口径钉死四条**：拒收优先于改价（最低价是硬门槛）；**判不出来 ≠ 判成通过**
   （对比价缺观测 / 币种不同 ⇒ 该规则不参与，并把原因写进结果与差异台账）；
   币种不同**绝不按 1:1 折算**；越具体的规则越先判（SKU 级 > 站点级 > 平台级）。
4. **最低价触发不发平台调用**：平台根本不收低于门槛的价，发过去只是浪费一次调用，
   还会在 02 队列里留下一条永远失败的任务。拦截发生在派发之前，但**原因必须显性**
   （落进 `platform_price_sync_diff`，`outcome=REJECTED` / `reason_code=BELOW_MIN_PRICE`）。
5. **上游边界清晰**：5.4.1.1 的 `price_adjustment_writeback` 是唯一写入所有者，本模块**只读**它；
   结掉一条事实走它的 ack 端点（同服务内直接调服务方法 —— 自我 HTTP 调用会在单连接池下把自己堵死）。
   **本车道未对那张表做任何 GRANT 变更，也没有任何触发器写它。**
6. **差异记录只追加**：一次回写留两条（规则判定一条 + 平台回执一条），后来的结论**不覆盖**前面的历史。

---

## 模块 6.2.2.1 采购申请

**这个功能做什么**：把补货建议与采购计划行转成内部采购申请单，走「草稿 → 待审 → 已批 → 已转单」，
每一步都留审批痕迹；已转单的行会记下转单参数（供应商 / 数量 / 交期 / 目标仓）与源计划行。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V246__purchase_request.sql`
  — **4 张新表、零 ALTER 既有表**，全部 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + `GRANT`。
- 端点：**13 个** `/trade/purchase-requests/*`（按 `@XxxMapping` 逐条统计）—— 能力声明、参照下拉、
  申请单台账 / 详情 / 行集合、建单 / 更新草稿 / 加行 / 调整数量交期单价 / 选供应商 /
  提交审批 / 刷新审批结论 / 转单。
- 页面：`/purchase/demand/requests`（清单 + 详情 + 审批 + 转单）。
- 测试：**31 例后端** — `PurchaseRequestIntegrationTest` 27 例 + `PurchaseRequestTenancyIntegrationTest` 4 例；
  前端模型 18 例 + allowlist 9 例。

**关键设计决策**：

1. **加粗句「申请是内部决策单据，采购单是对供应商的正式承诺」的落地方式**：
   6.3.1.2 采购单**未交付**（`backend/services/` 下没有任何采购单实体）⇒
   既不自造一张假采购单表占位，也不假装能调一个不存在的端点。
   本模块的「已转单」是一个**事实**：`purchase_request_conversion` 逐行记录转单参数与
   可追溯的源计划行 / 源建议；6.3.1.2 交付后由它读这份事实去建真正的采购单。
   能力声明里 `purchaseOrderDelivered=false` + `purchaseOrderNote` 写清这件事，页面据此显示警示块。
2. **状态机用 DB 层触发器钉死**（`guard_purchase_request_status`，BEFORE UPDATE ON purchase_request）：
   合法流转只有 **草稿→待审/已批、待审→已批/已驳回、已批→已转单**，其余一律 RAISE 23514。
3. **同一条触发器还管「离开草稿后内容冻结」**：数量 / 交期 / 供应商 / 目标仓 / 法人 / 金额 / 备注
   在离开草稿后一律不许再改 —— 改了就会与「已审批的那一版」对不上，审批留痕随之失去意义。
4. **本模块没有本地批准端点**：审批结论由 identity（17 组织）裁定；
   本地批准会把审批流本地化 —— identity 那边什么都不知道，还留一条没人管的待办。
5. **零 ALTER 既有表** ⇒ 跨车道 DDL 回归面 = 0。

---

## 模块 16.2.2.2 认证与资质

**这个功能做什么**：登记产品认证档案（认证类型 / 证件 / 有效期 / 适用 SKU 与类目），
按数据库时钟现算到期状态与到期提醒，并回答「哪些 SKU 缺哪项认证」。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V247__product_certification.sql`
  — **8 张新表、零 ALTER 既有表**，全部 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + `GRANT`。
- 端点：**29 个** `/channel/product-certifications/*`（按 `@XxxMapping` 逐条统计）——
  能力声明、总览、到期提醒、覆盖统计（整体 / 按 SKU）、认证档案 CRUD 与撤销、
  SKU / 类目适用性维护、要求侧登记、扫描件（上传 / 读取 / 下载）、认证类型词表、租户策略。
- 页面：`/compliance/certifications`。
- 测试：**26 例后端** — `ProductCertificationIntegrationTest` 18 例
  + `ProductCertificationTenancyIntegrationTest` 3 例（多租户三断言）+ `ProductCertificationRulesTest` 5 例；
  前端模型 7 例 + allowlist 7 例。

**关键设计决策**：

1. **落点服务 = channel**，与同 L3 的 16.2.2.1 合规预检（已落在 channel，V197）同服务同库 ——
   将来 16.2.2.1 的「认证缺失」维度能直接读本模块的档案，不必跨服务 HTTP 绕一圈。
   命名空间 `/channel/product-certifications` **刻意不与 16.2.2.1 的 `compliance-precheck` 撞车**。
2. **认证类型是受控词表，不是代码里的常量数组**：内置类型写在迁移的 INSERT 种子数据里，
   生效词表 = 内置 ∪ 租户字典、**租户行优先**；词表由 DB 触发器兜底（直连插一个不在词表里的类型直接拒）。
3. **到期状态不落库**（与 6.1.2.3 供应商资质同一纪律）：表里没有 `expiry_state` 列，
   由读出口按**数据库时钟 `now()`** 现算；「即将过期」窗口来自**租户级策略**
   `product_certification_policy.warning_days`（默认 30 天），不是代码常量。
   剩余天数按 **floor** 算（「还剩 12 小时」显示成「还剩 0 天」而不是「还剩 1 天」）。
4. **覆盖统计要求侧自己维护**：只登记「有哪些认证」答不出「谁缺认证」—— 缺的那一行本来就不存在。
   因此额外维护 `product_certification_requirement`，覆盖判定 = 要求侧 × 覆盖侧，现算三个口径。
5. **撤销与过期是两件事**：撤销必须写理由（触发器钉住时刻 + 理由）；
   两者在覆盖统计里都不算「有有效认证」，但原因不同、界面上分得清。
   **认证档案不可删** —— 它是合规凭据，能做的只有撤销。
6. **租户字典视图用 `security_invoker = true`**：否则视图以拥有者权限执行，会绕过 RLS 让别的租户看见你的字典。
7. **扫描件真存**：文件本体写进受管目录（`LocalCertificationDocumentStorage`），
   业务表只存 key + 文件名 + MIME + 字节数 + 上传时刻；文件行**只追加**
   （续期是「再上传一份」，不是「覆盖掉原来那份」）。
8. **跨服务只经授权 HTTP API 读，且读不到就显式报读不到**（503 + 原因），
   **绝不**拿空列表冒充 —— 空列表会被读成「这个租户没有 SKU / 没有类目要求」。

---

## 本批共性观察

1. **四条迁移全部「纯新建表 + 零 ALTER 既有表」**，静态预检零命中 ⇒ 跨车道 DDL 回归面 = 0。
2. **四张事实表族全部带 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY`**，
   并配了多租户三断言集成测试（两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池归还后不串租户）。
3. **「取不到」与「确实是 0」被系统性地区分**：上游不可达 ⇒ 503 或 `available=false`；
   上游正常但无记录 ⇒ `known=false`。**没有任何一处用 0 或空集合冒充「没有这回事」**。
4. **未交付的上游一律显式标注**（`available=false` + `ownerModule` + 影响说明），
   不自造影子表、不假装能调不存在的端点（6.3.1.2 采购单、12.x 财务、13.x 利润分析均如此）。
5. **同服务内也不自我 HTTP 调用**：需要结掉上游事实时直接调同服务的方法
   （自我 HTTP 调用会在单连接池下把自己堵死）。
6. **「共享白名单累积文件」的风险被显式管理**：`api-proxy.ts` 每条车道新增**独立的 `services[]` 元素**
   + 独立正则根路径（`consignment-stock` / `platform-price-sync` / `purchase-requests` / `product-certifications`），
   **不往既有巨型正则里并分支**，并在动手前逐条核对既有正则对新根路径零命中。

## ⛔ L2 批量验证结果：**红**（1 / 1981 例失败，与本批 4 条车道无关）

### 验证结果

| 层 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | **1** | ❌ `catalog` 模块 **1981 例里 1 例失败**（identity 270 / catalog 1981 …） |
| 前端 typecheck | `pnpm typecheck` | 0 | ✅ |
| 前端 lint | `pnpm lint` | 0 | ✅ |
| 前端全量 | `pnpm test` | 0 | ✅ |
| Next production build | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 0 | ✅ |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | 0 | ✅ |

失败用例（唯一一条）：

```
com.starcon.catalog.PriceHistoryIntegrationTest.时间轴按维度真筛选且真分页真排序
java.lang.AssertionError: Expected size: 1 but was: 0 in: []
  at PriceHistoryIntegrationTest.java:417
```

⇒ **按协议本轮不 push**（L2 没绿不 push）。说明与台账照写、照 commit，成果与诊断留在集成头本地。

### 根因：5.5.1.1 价格历史里一条**依赖墙上时钟时刻**的断言（上一批交付的潜伏缺陷，不是本批造成的）

**归属模块 5.5.1.1 价格历史**（batch-46 交付，commit `64afd819`），**本批 4 条车道完全没碰它**。

三条取证（都已在集成头实测，不是推测）：

1. **文件级**：`git diff --name-only dd607c5b ce656ac0 | grep -iE "pricehistory|price_change"` → **空**。
   本批在 catalog 的唯一车道是 5.4.2.1（V245），它只**读** `price_adjustment_writeback`，
   **没有任何 GRANT 变更 / ALTER / 触发器**碰那张表，也没碰 `price_change_history`。
2. **提交级**：在**本批合并之前**的提交 `dd607c5b` 上单独跑同一条测试 → **同样失败**
   （`Tests run: 16, Failures: 1`，同一断言同一行号）。⇒ 失败与本批 4 条车道**无关**。
3. **机制级**：失败与否是**本机墙上时钟时刻**的函数。
   - 测试用 `LocalDate.now().minusDays(9)` 当查询窗口，服务端把 `LocalDate` 换算成
     **UTC 零点**：`windowFrom.atStartOfDay().atOffset(ZoneOffset.UTC)`（`PriceHistoryService.java:579`），
     上界 `windowTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)`（`:584`，半开区间）。
   - 测试的种子是 `base = now − 10 天`，被窗口查询断言的 `A2` 落在 `base.plusDays(2)` = **now − 8 天**，
     即「A2 在（now−9）那一日历日」这个期望**只在 JVM 本地时刻 < 08:00 时成立**：
     本地 07:0x 时 now 的 UTC 值落在前一日 23:0x，A2 才落进 `[D−9 00:00Z, D−8 00:00Z)`。
   - 实测吻合：batch-46 的 L2 跑在 **07:02 → 07:36** ⇒ 绿；
     本批 L2 跑在 **08:15 → 08:26** ⇒ 红；`TZ=UTC` 重跑 ⇒ 仍红（UTC 下本地时刻 01:0x，不满足条件）。
   ⇒ **本地时刻一旦越过 08:00，这条用例就恒红** —— 与仓库里此前修过的同类缺陷
   （4.3.1.1 clock-assert、9.3.3.1 clock-domain、5.2.1.2 cost-page-assert）**同一类**。

**处置**：按协议派**修复任务**（只修 5.5.1.1 这一个模块，不重写），
把断言改成**与墙上时钟时刻无关**（种子与查询窗口同口径，显式落在 UTC 某日正午），
并补一条「任何时刻运行都成立」的回归。**服务端的 UTC 日界语义不动** ——
它是确定性的、已交付的 API 口径，改它等于改已交付模块的契约；错的是测试的期望。

### 为什么本轮不做二分定位

协议允许「用 `git bisect` 在本批 merge commit 上二分，最多 2 次全量验证」。
本轮**不做**，因为二分要回答的问题（「是不是这 4 条车道弄坏了它」）已经被上面三条取证
**用更便宜的方式证伪**：文件级零接触 + 合并前提交上同样红 + 失败与否只是时钟时刻的函数。
二分只能再花 80 分钟机器时间重复验证同一件事，且会额外产生一次发热高峰。

### 对调度节奏的影响（需知悉）

**这条潜伏缺陷会让每天 08:00 之后的任何 L2 都红**，直到修复任务落地。
它不阻塞编码（车道自己的 L1 不含这条用例），但会阻塞「绿批次才能 push」。
修复任务已在派发中；修好之前，集成头保持本地不 push。

---

## 涉及文件

- 本批 4 条车道共 **77 个文件**（迁移 4 / 后端 Java 12 / 测试 8 / 前端 16 / 契约 4 / 文档 4 + 台账）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-batch-47-*.md` —— 本文件

## 关联

- `tools/module-progress/PIPELINE.md` —— 流水线协议（组批协议 / 三层验证 / 收尾三件套）
- `docs/sessions/2026-10/2026-10-21-platform-consignment-stock.md` —— 7.7.2.2 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-platform-price-sync.md` —— 5.4.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-purchase-request.md` —— 6.2.2.1 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-product-certification.md` —— 16.2.2.2 车道自写会话日志
- `docs/sessions/2026-10/2026-10-21-batch-46-price-purchase-forecast.md` —— 上一批（4 条车道 + L2 一次通过）
