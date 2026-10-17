# 2026-10-17 批次 15 · 审单规则 / 风控校验 / 拆单 / 履约请求

> 本批 4 条车道全部落在 **trade 服务**（卷 09「后端归属：trade + channel」，取第一项 trade），
> 迁移 V149–V152 **全部零 ALTER 既有表**，因此跨车道 DDL 回归面 = 0。
> 但四条车道改的是**同一批共享文件**，本批真正的工作量不在写功能，在**合并**。

## 进度

465 个 L4 模块 · **已交付 178**（本批 +4）· 进行中 0 · 未开发 287

## 批次闸

| 闸 | 结果 |
| --- | --- |
| 后端 `./mvnw verify` | **BUILD SUCCESS**，**4328 例 / 0 失败 / 0 错误**，206 个测试类，约 14 分钟 |
| `pnpm typecheck --force` | exit 0，**0 cached**（`--force` 绕开 turbo 跨 worktree 缓存） |
| `pnpm exec tsc --noEmit`（web + ui） | 均 exit 0（直出真实结果，不采信缓存） |
| `pnpm lint --force` | exit 0，**0 cached** |
| `pnpm test` | **198 文件 / 3579 例全绿**（上一批 191/3509，+7 文件 +70 例） |
| Next production build | exit 0，`Compiled successfully in 7.4s`，4 个新页面均在产物中 |
| `sync --check` | ✓ 台账与蓝图一致 |

---

## 模块 9.2.1.2 审单规则

**这个功能做什么**：配置自动审单规则（按店铺/金额/买家等级/商品/收货国自动通过或转人工），
并对一张订单给出**可解释的命中链** —— 客服被买家投诉时能说出「为什么这单被拦了」。

- 迁移：`V149__trade_order_review_rules.sql` — 六张新表，**零 ALTER 既有表**，4 道 DB 闸门
- 端点：`/trade/order-review` 共 11 个
- 页面：`/orders/review/rules`
- 测试：`OrderReviewRuleIntegrationTest` 28 + 前端 23 + 白名单 5

**关键设计决策**：命中结果必须能逐层解释到「哪条规则的哪个条件命中了订单的哪个字段」。

**⚠️ 如实记录的功能缺口**：蓝图要求「按店铺」判定，但集成头 `sales_order` **没有 `shop_code` 列**
（上游 9.1.2.1 平台订单同步 / D5 未交付），店铺维度**当前降级为平台级**（按 `order_source` 判）。
表结构与判定逻辑都留好了，D5 补列即可启用，但**同平台跨店铺目前区分不了**。

---

## 模块 9.2.2.2 风控校验

**这个功能做什么**：多信号风险打分（买家历史、设备与 IP 关联、下单频次、金额异常、地址集中度），
输出风险分与**建议动作**（放行/人工/拒发）。

- 迁移：`V150__trade_order_risk_validation.sql` — 五张新表，**零 ALTER 既有表**
- 端点：`/trade/orders/risk-validation` 共 9 个
- 页面：`/orders/risk-validation`
- 测试：`OrderRiskIntegrationTest` 25 + `OrderRiskTenancyIntegrationTest` 5 + 前端 18

**关键设计决策**：

- **上游不可用是实查结论，不是假设**：卷 15（24 个 L4）仅 `15.4.1.1` 已交付，且它是**知识库**、
  **不是**退款率/纠纷率画像 —— 即便将来交付，本服务也读不到；卷 16（合规）**24 个全部未交付**。
  且两者分属独立数据库，**禁止跨库读**。
  ⇒ 退款率/纠纷率/设备 IP 三维落成**租户可维护信号表**，每行强制 `signal_source` 枚举 + `captured_at`，
  `capabilities` 显式报 `crossServiceReadAvailable=false` 与每维 `upstreamDelivered=false`。
- **另三维是真 SQL**，不是占位：下单频次 / 金额异常 / 地址集中度直接查 `sales_order` 簇。
- **「风控分数不能单独作为拒绝依据」做成 DB 不变量**而不是注释：延迟约束触发器拒绝把最终动作
  直接写成拒发，`{uuid}/reject` 形状**刻意不放行**进白名单 —— 改最终动作的唯一入口是 `/review`（要人工身份）。
- **该车道被自己的迁移抓到 4 个真 bug**：延迟约束触发器**不能读 `NEW`**（队里存的是 INSERT 那一刻的快照）；
  合计不能挂在头行 INSERT（那一刻组件一张都没有）；完整性必须交给提交期约束而不是每行断言；
  **跨时钟比较**（JVM 写 `created_at`、DB 写 `totals_computed_at`）—— 改为让 DB 自己拥有时间列。

---

## 模块 9.3.1.1 拆单

**这个功能做什么**：把一个订单拆成多个履约单元（按仓库/承运商/发货时效/平台限制拆），
**拆单不产生新的销售订单** —— 订单总金额与买家视角不变。

- 迁移：`V151__trade_order_split.sql` — 10 张新表，**零 ALTER 既有表**
- 端点：`/trade/fulfillment/split` 共 14 个
- 页面：`/orders/fulfillment/split`
- 测试：`OrderSplitIntegrationTest` 36 + 前端 18 + 白名单 5

**关键设计决策**：

- **金额守恒是核心断言**：各单元分摊之和 + 尾差 == 订单总额，前端**独立复算一遍**而不是只信后端的布尔；
  尾差必须有承载列，「静默吞掉几分钱」在页面上必须显示成不同的话。
- 9.2 审单整条线未交付 ⇒ 逻辑引用 + 收货/货权快照 + `ORDER_REVIEW available=false`。
- 7 预占跨库不可读 ⇒ 预占指令登记在本服务内，并用 `CHECK(upstream_reserved = false)`
  **把「不预占上游库存」钉在数据层**（否则下游落地时两边都以为占了同一批货）。
- 测试抓到 5 个真 bug，含 `unit_line` 唯一键原本禁止部分履约（本模块核心场景被自己的约束挡死）。

---

## 模块 9.3.3.1 履约请求

**这个功能做什么**：编排一次履约 —— 锁定预占 → 生成出库单 → 编排作业 → 跟踪完成，
**流程状态持久化**，支持断点恢复与超时重试。

- 迁移：`V152__trade_fulfillment_request.sql`
- 端点：`/trade/fulfillment/requests` 共 17 个
- 页面：`/orders/fulfillment`（该路由原本就是专门留给本模块的，`implemented: false → true`）
- 测试：`FulfillmentRequestIntegrationTest` 33 + 白名单 6

**关键设计决策（两条安全发现）**：

1. **`SECURITY DEFINER` 绕过 RLS** —— 七个窄函数原本只靠外层 RLS。本租户用户拿着**别家租户的
   `request_id`** 就能推进别人的流程。已全部补 `current_setting('app.current_tenant')` 判据。
2. **只 `REVOKE DELETE` 等于没收** —— 一条顺手改 `warehouse_ref`/`requested_qty`、version 照样递增的
   `UPDATE` 能通过全部校验。四张当前状态表改为 `REVOKE UPDATE,DELETE`。

**拆单未交付** ⇒ 逻辑引用 + capabilities `available=false`，未建表、未读表。
**预占缺省状态是 `REQUESTED`（待回执）而非 `LOCKED`** —— 它就是那个下游，两个下游布尔全为 false。

---

## 合并：本批真正的工作量

四条车道都按同一约定在 `apps/web/src/lib/api-proxy.ts` 的**同一个锚点**新增自己的 `services[]`
元素（既有 `prefix: 'trade/'` 元素之前），git 的 diff 对齐把 hunk 判成互相重叠 ⇒
`9.2.2.2` 与 `9.3.3.1` 并入冲突。

**解法是确定性求并集，不是手写改写**：把该车道相对 `merge-base` 的插入块**逐字**贴回 ours，
并以「移除插入块后能否**逐字节还原** ours」作为结构判据。全文件唯一允许非逐字来自某一侧的
字节就是接缝那两行。

**⚠️ 一个必须记下的判据陷阱**：我先写的括号平衡检查报 `UNBALANCED — 2 unclosed`。
拿**未修改的 ours 文件**跑同一检查也是 2 —— 是我的粗糙检查器数到了巨型正则字面量里的括号，
不是拼接坏了。**真实判据是 `tsc`**，不是自制的括号计数器。

---

## 途中修掉的 3 个真问题（都不是合并造成的）

1. **9.3.1.1 交付了一个类型错误** —— `order-split-model.test.ts` 用字符串 `'100.0000'` 构造 `SplitRow`，
   而生成契约里 money 一律 `number`（同服务既有类型与兄弟模块测试都是 number），tsc 报 9 处 `TS2322`。
   **根因不是契约并集丢字段**：我查了 `feat/9-3-1-1` 分支自己的 `contracts/trade.ts`，
   `orderTotalAmount` 本来就已经是 `number`，是它的用例写错了。
   它没被自己的闸抓到，是因为 **vitest 不做类型检查**，而它的 tsc 跑在契约定稿之前。
2. **9.3.3.1 交了格式坏掉的台账行** —— 状态与证据之间少了 ` | ` 分隔符（7 个单元格，正常行 8 个），
   `sync.mjs` 解析不到合法状态、**按设计回落到「未开发」**，统计因此少算一个（177 而非 178）。
   证据文本没丢，按其分支原文取回补上分隔符。**全表审计确认 465 行里 0 行单元格数异常**。
3. **专职解冲突 worker 被 Token Plan 配额打死**（`42212`），工作区留在半合并状态，
   且它**临死前发现自己的拼接有错**。处置：`git merge --abort` 复原（两条车道分支 commit 完好），
   改由调度者用脚本重做。

---

## 已知缺口（如实记录，未用空列表冒充）

1. **既有代码的安全缺口（非本批引入，未擅自扩大范围修）**：
   `V116` 的 `advance_warehouse_allocation_status` 是 `SECURITY DEFINER` 且**函数体内无租户判据**
   （全文件仅 2 处 `current_setting`，都在 RLS 策略定义里）。形态与 9.3.3.1 修掉的那七个窄函数相同。
   **本批没有改它** —— 它属于已交付的 9.3.2.1，修改应作为独立修复任务派发并单独验证。
2. **9.2.1.2 店铺维度降级为平台级**（`sales_order` 无 `shop_code`，D5 未交付）。
3. **四个新页面都没有视觉 / E2E 验证**（无 Playwright、无截图），运行时行为未经验证。
4. **9.3.1.1 的 report/列表类查询存在 N+1 风险**，本批未超范围重构。
5. 三维风控信号（退款率/纠纷率/设备 IP）在 15/16 上游交付前**只能由租户手工维护**。

## 并入后回归对照

- `api-proxy-union.test.ts`：**246 例 = 本批之前基线，零丢失**（并入前实测取的基线）。
  这类「行级求并集」最危险的失败形态是**测试文件能编译但收集到 0 个用例** ——
  CI 变绿而它已不再检查任何东西，故单独核对。
- `prefix: 'trade/'` 元素：2（基线）+ 4（本批）= **6**，兄弟车道路由组无一被挤掉。
- 前端用例：191 → **198 文件**，3509 → **3579 例**。
