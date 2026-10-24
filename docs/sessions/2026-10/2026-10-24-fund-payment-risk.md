# 1.1.3.2 资金与回款风险（阶段 D7 · L3 锚点 1.1.3 · 卷 01 工作台与门户）

> 会话记录：**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述过程。

## 决策摘要

落地蓝图卷 01 的「1.1.3.2 资金与回款风险」：展示**应收账龄、平台待结算金额、逾期应收、
即将到期的供应商应付、在途资金占用**，输出账龄分布图 + 逾期清单 + 待结算金额，
每个数字都能点进 12 财务的明细。

### 最重要的一条决策：**本模块后端零改动**

卷 01 的卷首写死了两件事：

1. **不产生新的业务事实，只聚合各系统的投影** ⇒ **无迁移、无新表、无快照**。
2. **后端归属 = 前端聚合层 + 各服务的只读投影（不新增业务服务）**。

这一版与兄弟模块 1.1.3.1 的差别在于**缺不缺投影**：

| | 1.1.3.1 库存风险 | 1.1.3.2 资金与回款风险 |
| --- | --- | --- |
| 事实是否已就位 | **缺** —— 可用量 / 阈值 / 预占 / 在途没有一个端点能一次给全 ⇒ 在 warehouse 加了 `GET /warehouse/inventory-risk/projection` | **不缺** —— 本模块五类事实**全部**已在归属服务的既有只读端点里 ⇒ **零新增端点** |
| 迁移 | 无 | **无** |

⇒ 落点：**纯前端聚合层**，与同卷 1.1.1.1「我的待办」同一形态。

### 六个上游只读端点（全部已在 api-proxy 白名单里，本车道 k = 0）

| 来源 | 端点 | 归属模块 | 权限码 | 本模块用它做什么 |
| --- | --- | --- | --- | --- |
| 账龄 | `GET finance/receivable-aging/distribution` | 12.1.1.2 | `finance.receivable.read` | 按币种的账龄分布 + 档位金额 + 逾期集中度 |
| 逾期 | `GET finance/receivable-aging/overdue` | 12.1.1.2 | `finance.receivable.read` | 逾期清单 + 按币种汇总 |
| 待结算 | `GET finance/settlement?status=PENDING` 与 `?status=PARTIAL` | 12.3.1.1 | `finance.settlement.read` | 平台待结算金额 |
| 应付 | `GET finance/payables/unpaid` | 12.1.2.1 | `finance.payable.read` | 即将到期 / 已逾期的供应商应付 |
| 在途（钱） | `GET finance/deferred-settlement/summary` | 12.3.1.3 | `finance.deferred_settlement.read` | 在途未结算金额（两套口径） |
| 在途（货） | `GET warehouse/inventory-risk/projection` | 07 库存 | `warehouse.inventory.read` | 在途库存**数量** |

**白名单是实测的，不是目测**：发车前用脚本把基线 `api-proxy.ts` 的 services[] 正则逐条编译
并实测命中（与 `resolve()` 同构：先切掉 prefix 再 test），上述六个形状全部命中 ⇒ 无需新增元素，
该文件**一字未改、未重排** ⇒ 并入集成头时在该文件上零冲突面。

### 四个 trade-off

1. **待结算读两次而不是一次**：结算单的 `status` 过滤只吃**一个**取值，而「待结算」口径是
   **待到账 + 部分到账**两档 ⇒ 读两次再**按币种**合并。同一币种内相加合法，跨币种相加不合法。
   未到账金额 = 结算金额 − 已到账金额。
2. **「即将到期」需要窗口，窗口必须显形**：蓝图没给天数 ⇒ 做成**界面可调的筛选**（3/7/15/30 天，
   默认 7）并**始终在界面上写出来**。窗口只管「未来多久之内到期」，
   **已逾期的恒计入**（无论窗口多小）—— 逾期的永远是风险。
3. **库存货值如实报不可判定**：蓝图输入写了「07 库存货值」，但 warehouse 与 catalog
   **全库没有库存成本列**（只有采购报价，不是库存计价，且属跨服务引用）；
   12 财务自己的资金分析（12.7.2.1）也把存货周转天数恒置为 null 并写明「存货不在本服务」。
   ⇒ 给**数量**口径的完整拆分，**金额口径显式报不可用并说明原因**。
   用采购报价冒充库存成本会把账面资产算错，比不给更糟。
   （类型层面写成 `amount: null` 而不是 `number | null` —— 让任何试图填数的代码编译期就过不去。）
4. **截断时总量判不了，不报部分和**：warehouse 的投影端点分页（每页上限 500 行）。
   只取回一部分却报一个「总量」，就是在报一个假的数 ⇒ 截断时总量 / 分类量 / 腿数一律 null，
   并如实报出「只取回前 N 条（共 M 条）」。

### 三条纪律的落点

- **多币种绝不相加**：概览按币种分行，`CurrencyRiskSummary` 类型层面**没有**跨币种总额字段。
  汇率不可得时也不按 1:1 兜底 —— 1:1 加出来的「总额」是假的。
- **「不可判定」≠ 0**：分母为 0（占比）、来源读不到（整格）、字段缺失一律 null，
  界面显示「—」并说明为什么。**三种「读不到」严格分开**：
  无权限（连请求都不发）/ 读不到（服务不可达）/ 没有数据（读到了但这一档是空的）。
- **精确十进制**：金额合计与占比全程在 BigInt 缩放整数上做
  （`0.1 + 0.2 = 0.3` 而不是 `0.30000000000000004`），占比四舍五入到 4 位。
  （兄弟模块 1.1.3.1 的血泪教训：浮点下 `0.3 ÷ 0.1` 是 2.999…，向下取整会得 2 而不是 3。）

### 数据范围与多租户

- **服务端判定**：租户来自**已认证成员关系**（`auth/session` → 各服务 `AccessService.require`），
  **不信任任何客户端租户头**；法人 / 店铺 / 仓库的范围由**归属服务**在服务端判定，
  前端**不把范围当成可信输入往下传**（不下发租户 id、不下发范围参数）。
  前端算出的 scopeMode（ALL > DEPARTMENT > SELF，与后端 `GrantPermissions.scopeMode` 逐字同式）
  **只用于「要不要去读」与「怎么措辞」，不是授权**（服务端会独立再判一次）。
- **隔离继承自上游**：本模块后端零改动、不建任何表 ⇒ 隔离由四个上游模块各自的
  Testcontainers PG16 隔离测试覆盖（`ReceivableAgingTenancyIntegrationTest` ·
  `SettlementTenancyIntegrationTest` · `PayableTenancyIntegrationTest` ·
  `DeferredSettlementTenancyIntegrationTest` · （warehouse）`InventoryRiskTenancyIntegrationTest`），
  三断言（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）由它们证明，
  **不重复造一份**。代理侧另有一条断言：伪造的 `X-Tenant-Id` 与 `X-Internal-Key` **不被转发**。

### 只读 + 跳转

页面上**没有**确认 / 催收 / 调整 / 核销任何写动作；每个数字的出口是**跳到 12 财务**的
应收账龄 / 应付单 / 结算单 / 延迟结算页。allowlist 测试里有两条断言钉住这件事：
**写形状仍然 404**、**前端源码零写调用**（`api(path,'POST')` 与写动作按钮文案双查）。

## 涉及文件

**新增**

- `apps/web/src/components/dashboard/fund-payment-risk-model.ts` —— 纯函数模型
  （精确十进制 / 来源声明 / 账龄 / 逾期 / 待结算 / 应付 / 在途 / 汇总 / 口径文案）
- `apps/web/src/components/dashboard/fund-payment-risk-model.test.ts` —— **47 例**
- `apps/web/src/components/dashboard/fund-payment-risk-workspace.tsx` —— 真实工作区
- `apps/web/src/app/dashboard/fund-payment-risk/page.tsx` —— 路由 `/dashboard/fund-payment-risk`
- `apps/web/src/app/api/fund-payment-risk-allowlist.test.ts` —— **7 例**（真跑 proxy 断言状态码）

**修改**

- `docs/product/capability-blueprint/module-progress.md` —— **仅 1.1.3.2 那一行**
  （状态 未开发 → 已交付 + 证据列）

**未改动（刻意）**

- `apps/web/src/lib/api-proxy.ts` —— 六个形状**已放行**，k = 0，一字未改
- `packages/api-contracts/**` —— 后端零改动 ⇒ 契约不变，**未重新生成**
- `backend/**` —— **无迁移、无端点、无 SQL、无 RLS 变更**
- `apps/web/src/components/dashboard/workbench-card-registry.ts` —— 见「后续待办」
- 兄弟模块页面（`/dashboard/inventory-risk`、`/dashboard/todo/**`）—— 一行未碰

## 验证

```
cd backend && ./mvnw -q compile            # exit 0（后端零改动）
pnpm typecheck                             # 3/3 通过
pnpm vitest run apps/web/src/components/dashboard/            # 12 文件 299 例全绿（本车道 47 例）
pnpm vitest run apps/web/src/app/api/fund-payment-risk-allowlist.test.ts   # 1 文件 7 例全绿
eslint 本车道 4 个文件                      # 无输出
```

**PG 容器数：0** —— 本车道后端零改动、无新增集成测试类，未启动任何 Testcontainers 容器，
也就不存在 ryuk 回收的问题（本车道 `verify` 期间同时存活的 PG 容器数为 0）。

## 后续待办

1. **工作台卡片注册表未登记**：`workbench-card-registry.ts` 是 1.4.1.1 的**系统事实**文件，
   它的单测把卡片清单**写死**（`workbench-layout-model.test.ts` 有 3 处断言）。
   登记 1.1.3.2 的卡片要连带改 1.4.1.1 的测试文件 ⇒ 越界，**本车道刻意未做**。
   待 1.4.1.1 的 owner 统一登记（1.4.1.1 的会话日志本来就挂着这条待办：
   「1.1.2.1 核心指标卡 / 1.1.3.2 资金与回款风险交付后，把它们登记进 workbench-card-registry.ts」）。
2. **1.1.2.1 核心指标卡**（同 L3 的兄弟格）同样尚未交付 —— 本模块的账龄 / 待结算 / 在途数字
   是它的天然输入，等它交付后可复用本页的聚合口径。
3. **库存货值**要等真正落库存成本列（07 库存核算侧）才有金额口径；
   在那之前本模块的「在途资金占用」只有数量口径与钱口径，**金额口径恒为不可判定**。
