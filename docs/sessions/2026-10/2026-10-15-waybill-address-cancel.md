# 10.2.1.3 改址与取消

- 日期：2026-10-15
- 模块：10.2.1.3「改址与取消」（阶段 D5，L3 锚点 10.2.1）
- 车道分支：`feat/10-2-1-3`（基线 = 集成头 `9a24fb1c`）

## 决策摘要

蓝图对这条模块只有一句话的加粗约束，但它就是整条模块的设计轴心：

> **改址是买家高频诉求，超时的改址请求要明确告知能否成功而不是让客服猜。**

所以「能不能成」在本模块被拆成**两个刻意独立的维度**，而不是一个布尔值：

| 维度 | 列 | 取值 | 回答什么 |
|---|---|---|---|
| 时间 / 规则 | `verdict` | WITHIN_WINDOW / DEADLINE_PASSED / CHANNEL_UNSUPPORTED / RULE_UNREGISTERED | 在不在时限内、渠道支持不支持、规则登没登记 |
| 结论 | `can_succeed` + `can_succeed_reason` | true/false + 一句直白理由 | 到底能不能成，为什么 |

合并成一个「失败」正是蓝图点名要避免的。四种处境各有各的下一步：

- `DEADLINE_PASSED` → **再也不会成功**（不是重试一下就能过），改用「取消 + 重新发货」
- `CHANNEL_UNSUPPORTED` → 一定不会成功（明确不支持）
- `RULE_UNREGISTERED` → **系统不知道**（不是「不行」），该做的是去登记规则
- `WITHIN_WINDOW` + 渠道未接通 → 接通后**仍可成功**，该做的是催渠道

前端把前两类画红、后两类画黄 —— 行动完全相反，混成一种颜色就是让客服猜。

## 关键 trade-off

**1. 时限判定基准是数据，不是 `now()`。**
判定基准 = 出号时刻（10.2.1.2 落库）+ 该承运商登记的 `deadline_hours`（本模块的规则表）。发起时刻 `requestedAt` 由调用方显式给定，以支持补录历史诉求。代价是调用方要负责传对时刻；收益是同一张单据任何时候重算结论都不变，测试能钉死时刻而不是碰运气。

**2. 渠道规则做成租户可维护的表，不做成 Java 常量。**
「渠道规则与时限」是蓝图输入句的第二项，而各承运商的改址窗口本来就是**商务事实**。写死成 `if (hoursSinceIssue < 24)` 等于宣称「所有渠道一个窗口」。

**3. 凭据分两段钉死，而不是全部永久不可改。**
判定基准（运单归属 / 出号时刻 / 截止时刻 / verdict / requested_at）永久冻结；结论（`can_succeed` / `can_succeed_reason`）**只在还没有渠道结果时允许定稿一次**。全部钉死会让一次真实受理撞 check_violation 回滚 —— 界面永远停在「未受理」，而承运商那边已经改了。

**4. 刻意不建「一票只能有一条未出结果改址」的条件唯一索引。**
`NOT_ATTEMPTED` 在本模块是「没有受理凭据」而不是「在途」：渠道未接通时那张单会永远停在这个值。建了索引，运营接通渠道后想重试会撞 23505，而界面上只显示「唯一键冲突」。派发是同步的，本来就没有「两个请求同时在飞」的窗口需要索引去挡。

**5. 联动 = 建立引用，不是另起一套单据。**
`order_change_request` / `order_cancellation_request` 的写入所有者永远是 9.1.1.3。本模块只校验（存在 / 类型 / 同订单）并持有引用，**不写对方任何一列**。

**6. 买家通知只建任务、不发送、不给「已送达」。**
15 客户与客服在 crm 服务（跨服务不得直连对方库 / 建跨库 FK）。一个假的「已送达」按钮会让客服以为买家已收到而不再跟进 —— 那比没有通知任务更糟。

## 遗留半成品的处置

上一个会话在写码途中被中断，7 个文件一行未 commit。处置如下：

| 文件 | 处置 | 说明 |
|---|---|---|
| `V135__trade_waybill_address_cancel.sql` | **改写** | 结构与判据设计质量高，保留；但修掉 3 处会炸的缺陷（见下） |
| `WaybillRedirectModels.java` | **原样复用** | 契约形状无误 |
| `CarrierRedirectPort.java` | **原样复用** | 与 10.2.1.2 的要号端口分开的理由成立 |
| `CarrierRedirectAdapterRegistry.java` | **原样复用** | 无实现时如实 unavailable 的纪律正确 |
| `WaybillRedirectController.java` | **原样复用** | 命名空间与兄弟互不为前缀 |
| `WaybillRedirectService.java` | **改写** | 修掉 5 处逻辑缺陷（见下） |
| `WaybillRedirectIntegrationTest.java` | **改写** | 补齐隔离、补 3 条走真实链路的用例、修 3 条前提写错的用例 |

**「编译通过 ≠ 逻辑正确」在本车道被实测证实**：遗留代码 L0 编译 exit 0，但首次跑测试是 **20/21 失败**。共修掉 9 处真实缺陷：

1. 四处 INSERT 把 `tenant_code` 绑进了 `tenant_id` 列（少一个参数、整体错位）
2. `concat()` 返回 `List` 传给 jOOQ 的 `Object...` 重载 → 被当成单个绑定值，端点上表现为**误导性的 409**
3. 取消清单的关键词过滤引用了只存在于改址表的 `recipient_name`（取消表是 `cancel_no`，改址表是 `redirect_no`）
4. 不可改写触发器把「派发后写回承运商真实回话」一起挡掉
5. 买家通知类型按**判定阶段的陈旧局部变量**计算 → 承运商拒收时会给买家发一条「改址已受理」
6. `countOf(sql, f.params)` 同样漏了 `.toArray()`
7. 通知任务汇总把 `total` 抄三遍冒充三个指标
8. 测试的 `INSERT` VALUES 子句漏了 `reason` 占位符，导致后面每个字面量整体错位
9. 测试用 `?::timestamptz` 绑 `uuid` 类型的 `requested_by_membership_id`

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V135__trade_waybill_address_cancel.sql`

**后端**（`backend/services/trade/src/main/java/com/starcon/trade/`）
- `WaybillRedirectModels.java` / `WaybillRedirectService.java` / `WaybillRedirectController.java`
- `CarrierRedirectPort.java` / `CarrierRedirectAdapterRegistry.java`

**后端测试**
- `backend/services/trade/src/test/java/com/starcon/trade/WaybillRedirectIntegrationTest.java`（21 例）

**契约**（codegen 产物，**禁止手改**）
- `packages/api-contracts/src/contracts/trade.ts` / `trade.openapi.json`

**前端**
- `apps/web/src/components/logistics/waybill-redirect-model.ts` + `.test.ts`
- `apps/web/src/components/logistics/waybill-redirect-workspace.tsx`
- `apps/web/src/app/logistics/address-change/page.tsx`
- `apps/web/src/app/api/trade/waybill-address-change-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（白名单**新增一组**，未并入任何兄弟组）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 10.2.1.3 那一行）

## 验证

- `./mvnw -q compile`（L0）：exit 0
- `./mvnw -pl services/trade -am test`（L1）：**573 例全绿**，含本模块 21 例
- `pnpm typecheck`：3/3 成功
- `pnpm lint`：3/3 成功
- `pnpm vitest run`（本模块 2 个文件）：**26 例全绿**；兄弟 4 个文件 **53 例全绿**（白名单改动没挤掉它们）

## 后续待办

- **10.1 物流渠道接通后**：`CarrierRedirectPort` 的 vendor 实现由 channel 侧提供；接通前本模块不会凭空受理（capabilities 里 `CARRIER_REDIRECT_CALL` available=false，ownerModule=10.1.1.1）。
- **10.3 物流轨迹接通后**：可把「承运商侧是否已揽收」纳入判定 —— 现在时限内判成「可改」仍可能被承运商以「已揽收」拒收（该拒收会如实落 REJECTED，不会被提前猜成成功）。
- **15 客户与客服接通后**：消费 `logistics_buyer_notification_task` 并回写送达状态；本模块刻意不代写。
- **2.5.1.1 平台回写接通后**：改址 / 取消结果回写平台；订单侧写回任务仍归 9.1.1.3。
- **渠道改址费**：10.1 接通后若渠道收改址费，必须 `BigDecimal` + `NUMERIC(19,4)`（本模块刻意不落金额列，界面上是「无费率口径」而不是 0）。
