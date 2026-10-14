# Batch 7 · 复核扫描 + 代发模式配置（组批流水线）

- 日期：2026-10-14 16:00 批次（节流闸距上批 150.6 分钟，GO）
- 集成头：`04b40c31` → **`d2568f11`**
- 覆盖模块：**8.3.3.1 复核扫描**（warehouse / V117）、**9.6.1.1 代发模式配置**（trade / V118）
- L2：**绿**（详见文末「L2 执行记录」）

本批两条车道都是上一轮（13:30 批次）派发、本轮回收并入的。
本轮另派发两条新车道：**8.3.3.2 打包与称重**（V121）、**9.6.1.2 代发单与供应商下单**（V122）。

---

## 模块 8.3.3.1 复核扫描

**这个功能做什么**：出库打包之前的一道拦截关。仓库按拣货单把货凑齐后，系统开一张复核单，
作业员拿扫描枪逐件扫商品（条码或序列号），系统实时比对订单明细，自动挑出**漏件、错件、多件**三类问题。
蓝图标了一句「复核是错发拦截的最后机会」—— 货一旦打包装车发出去，召回的成本是拦下的几十倍，
所以序列号商品强制逐件核对，不允许用「件数相等」糊弄过去。高价值订单还要额外开箱复核。
有异常的单自动转人工，人工处置完才能放行打包。

**技术落地**：

- 迁移：`V117__warehouse_recount_scan.sql` — 建六张表
  `recount_rule` / `recount_session` / `recount_line` / `recount_scan` / `recount_exception` / `recount_event`。
  六张全部 `tenant_id NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY` + GRANT（仅 `warehouse_app`，`starcon_app` 拿不到）。
  另有两个自定义函数 `guard_recount_session_status_fn`（放行守卫）与
  `recount_line_line_status_fn`（行状态派生）。
- 端点：`/warehouse/recount-scans` 共 **14 个** ——
  `GET /capabilities`、`POST|GET /rules`、`GET /rules/{id}`、`POST /rules/{id}/retire`、
  `GET /{id}`、`POST /{id}/open-box`、`POST /{id}/scans`、`POST /{id}/settle`、
  `POST /{id}/complete`、`POST /{id}/escalate`、`POST /{id}/exceptions/{exceptionId}/resolve`、
  `POST /{id}/cancel`。
  **刻意没有 PUT / DELETE**：扫描记录与流水是 append-only，异常只能处置一次。
- 页面：`/warehouse/recount-scans` — 复核单列表 + 逐件扫描工作台 + 异常处置；
  错误态/空态/加载态齐备。
- 测试：`RecountScanIntegrationTest` **25 例**（Testcontainers PostgreSQL 16，含租户隔离三断言）；
  前端 `recount-scan-model.test.ts` + api-proxy 白名单用例。

**关键设计决策**：

1. **「最后机会」做成数据库守卫，不是服务层校验。**
   `guard_recount_session_status_fn` 在置 `PASSED` 时同时要求：出库单复核前确实处于 `PENDING_RECHECK`、
   每一行 `line_status='MATCHED'`、没有任何 `resolution='PENDING'` 的异常、有复核人与完成时刻。
   集成测试用**超级用户直连 SQL** 强行改成 `PASSED`，被 23514 拒绝 ——
   「先放行再补异常」这条路绕开服务层也走不通。

2. **序列号核对用「集合相等」而不是「件数相等」。**
   `serial_match` 由两个差集查询算出（实拣集合 − 实扫集合、反向再减一次）。
   理由：应发 3 件、实拣 3 件、实扫 3 件，扫的可能是 S1/S2/S9 —— 件数一样内容不一样，
   那正是最典型的错件。三层保险：计数由触发器从 `recount_scan` 去重派生，
   `serial_match` 由服务层按真实 SQL 算，`line_status` 再由触发器从两者派生；
   CHECK 约束保证 `requires_serial` 的行拿不到 `MATCHED` 除非 `serial_match` 为真。

3. **「高价值订单」在本服务只能取件数 —— 如实命名，不假装是金额。**
   出库单上根本没有金额列（V72 明写「与 transfer_order_line 一样不存类目/售价/成本」，COPY 语义），
   金额的 owner 在 9 订单履约侧，跨服务读表被模块边界纪律禁止。
   所以高价值判定只按件数，并在界面上写明这是件数口径不是金额口径。

---

## 模块 9.6.1.1 代发模式配置

**这个功能做什么**：决定「哪些店铺、哪些 SKU 走代发（一件代发）」，以及走代发时的履约路径、
结算方式（下单结算 / 确认收货后结算 / 差价抽成）与可售校验规则。
代发的要害在于**供应商直发不等于我方不担货权** —— VMI 型场景（供应商替我发货，但货权仍在我方）
必须照样占用我方库存口径，界面上如果只给一个「是否代发」开关，运营看一眼就会得出错误结论。
这个模块把物流路径与货权拆成两个互不推导的字段，「不记我方库存」成为两者同时成立才有的结论。

**技术落地**：

- 迁移：`V118__trade_dropship_config.sql` — 建七张表
  `fulfillment_dropship_shop_ref` / `fulfillment_dropship_supplier_ref` / `fulfillment_dropship_config` /
  `fulfillment_dropship_config_line` / `fulfillment_dropship_supplier_stock` /
  `fulfillment_dropship_evaluation` / `fulfillment_dropship_event`，
  全部 `tenant_id NOT NULL` + RLS 强制 + GRANT。核心是两条生成列：
  `counts_against_own_inventory`、`pure_agency_exempt`。
- 端点：`/trade/fulfillment/dropship` 共 **16 个** ——
  `GET /capabilities`、`GET|POST /shop-refs`、`GET|POST /supplier-refs`、`GET|POST /configs`、
  `GET /configs/{configId}`、`POST /configs/{configId}/status`、`POST /configs/{configId}/lines`、
  `GET|POST /supplier-stocks`、`GET|POST /evaluations`、`GET /events`。
- 页面：`/orders/fulfillment/dropship` — 店铺/SKU 代发标记、代发供应商、结算方式、可售校验规则、
  供应商库存上报与评估记录；错误态/空态/加载态齐备。
- 测试：`DropshipConfigIntegrationTest` **24 例**（含租户隔离三断言）；
  前端 `dropship-config-model.test.ts` + api-proxy 白名单用例。

**关键设计决策**：

1. **生成列把「该不该占我方库存」钉死在数据层。**

   ```sql
   counts_against_own_inventory = fulfillment_path <> 'SUPPLIER_DIRECT' OR ownership_mode <> 'PURE_AGENCY'
   pure_agency_exempt           = fulfillment_path =  'SUPPLIER_DIRECT' AND ownership_mode = 'PURE_AGENCY'
   ```

   应用层改不了，也就没有「服务层忘了判」这条路。服务层只读出来如实报给下游 9.6.1.2，不另算第二份；
   前端同样只读 `pureAgencyExempt`，不重算 —— 两份算法迟早漂移，
   漂移后界面显示的「不占我方库存」就会和下游实际预占与否对不上。
   四种组合里最要紧的 `SUPPLIER_DIRECT + OUR_ACCOUNT` 专门留了断言。

2. **不预占我方库存，只报出「该不该占」的结论。**
   真正预占要调 warehouse 的授权 API，那是下游 9.6.1.2 的职责。
   现在假装预占，两边会同时以为占了同一批货。`capabilities` 里 `reservesOwnInventory=false` 如实报出。

3. **上游一律用带来源标注的只读投影，不跨库读。**
   店铺（channel）、供应商报价（catalog）、可售量（warehouse）分属三个独立数据库。
   沿用 9.3.2.1/9.3.2.2 的同一套做法：投影表带 `source`（`MODULE_*` / `TENANT_MAINTAINED`）与 `observed_at`，
   将来同步器按同一形状写入即可，表结构与判定逻辑都不需要改。

4. **「没有观测」与「观测为 0」在数据层就分开。**
   `supplier_available_qty` 为 NULL 时 reason_code 是 `NO_SUPPLIER_STOCK_RECORD`，
   理由串明写「没人报过 ≠ 供应商确实没货」。0 是确定结论，NULL 是缺口，
   画成同一个 0 会让人以为这单确定发不出。

5. **差价转负不拦，但一定显形。**
   拦了就录不进真实亏损的供应商报价。改由可售校验抓成 `NEGATIVE_MARGIN`，
   再由 `listing_policy` 决定下架 —— 闸门开在配置与刊登这一层，不在录入这一层。

---

## L2 执行记录

| 项                        | 结果                                                                                       |
| ------------------------- | ------------------------------------------------------------------------------------------ |
| 静态预检                  | **无命中**（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT CHECK` 三项均为空） |
| 后端 `mvnw verify`        | 首跑 **BUILD FAILURE**（见下），补跑后 **绿**                                              |
| `pnpm typecheck` / `lint` | 绿 / 绿                                                                                    |
| `pnpm test`               | 绿，**2599 例 / 139 文件**                                                                 |
| Next production build     | 绿（`NEXT_TELEMETRY_DISABLED=1`）                                                          |
| `sync.mjs --check`        | ✓ 台账与蓝图一致                                                                           |

### 首跑失败是 flake，不是回归 —— 证据链

首跑 `trade` 模块 `SalesOrderListIntegrationTest` **17 例失败**，症状整齐划一：
`Status expected:<200> but was:<503>`，全部指向同一个调用点 `postOrder:164`；
同时出现 `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`。

三步取证：

1. **本批有没有碰它** ——
   `git diff --name-only 04b40c31..HEAD` 里**没有任何** `SalesOrderList` 相关文件；
   本批碰到的「order」文件只有代发配置的前端四个，与该测试无关。
2. **单独重跑** ——
   `./mvnw -pl services/trade -am test -Dtest=SalesOrderListIntegrationTest` ⇒ **22 例全绿**，3.7 秒。
3. **根因归类** ——
   503 来自被测代码捕获 `RestClientException`。该测试用 JDK `HttpServer` 起桩并绑**操作系统临时端口**，
   全量并发 + 同时跑两条车道的 L1 时端口被抢占 ⇒ 连接被拒 ⇒ 503 ⇒ 17 个断言一起红。
   这是**并发压力 flake**，与本批改动无因果关系。

⇒ 因此**没有做 `git bisect`**：二分是给「真回归」用的工具，
对一个已证明的 flake 做二分会白烧一次全量验证。
改为补跑未证明绿的那一段（`trade` 219 例 + `crm` 18 例，均绿），
首跑已经跑完的 `identity` / `catalog` / `channel` / `warehouse` 保持其 SUCCESS 结论。
**同一个 commit `d2568f11` 的全部七个 Maven 模块至此都有绿证据。**

---

## 遗留：两条车道因 api-proxy.ts 冲突未并入（下一轮处理）

`9.1.1.3 订单变更与取消`（V119，集成测试 40 例全绿）与 `9.7.1.1 批发订单与账期`（V120）
工作已完成并 `land`，但 `merge-ready` 在 `apps/web/src/lib/api-proxy.ts` 上判冲突。

**根因（结构性，不是时序偶发）**：该文件每个服务是**一段单行正则**，按顶层 alternation 组织
（warehouse 段约 13KB）。三条车道都往 `trade` 段追加路径时，
git 把整行当成一个 diff 单元 ⇒ **必然冲突，retry 不会自愈**。
两条冲突车道的基线是 `cbd78408`，早于本批 8.3.3.1 的白名单改动 ⇒ 属于陈旧分叉。

**下一个 tick 会被重新派发的坑（已在本轮挡住）**：
`lane.mjs plan` 不知道这两条车道是「已完成待并入」，把它们当新模块重新排队，
且**重新分配了迁移版本号（V123/V124），与它们已提交的 V119/V120 不符**。
本轮已把这两条车道的台账状态改回 `conflict` 并还原正确版本号，
**并且没有派发 worker** —— 否则会在同一个 worktree 上开双 writer。

**建议的处置（需要人工决策，调度器不得自行 merge 车道分支）**：
按「一次只并一条」降冲突面，逐条把各自 trade 段的路径并入白名单，
再用行为级判据验收 —— **以各写者自己生成的 OpenAPI 契约里的 path 当用例集**，
断言「契约里每条 path 都能被 proxy 放行」。这个判据不需要人工维护清单，随代码自动变化。

---

## 本批之后

- 已并入并通过 L2：8.3.3.1、9.6.1.1（进度 142 → **144** / 465）
- 在跑：8.3.3.2 打包与称重（V121）、9.6.1.2 代发单与供应商下单（V122）
- 冲突待并：9.1.1.3（V119）、9.7.1.1（V120）
