# 2026-10-29: 13.4.3.1 利润预警

## 摘要

卷 13「利润与经营分析」L3 13.4.3 利润预警下的 **13.4.3.1 利润预警**落地：
从 13.4.1.1 的**利润投影**里算出**五类利润异常预警**（SKU 毛利转负 / 店铺利润率骤降 /
费用占比异常 / 广告亏损超标 / 订单级亏损订单占比突增），
输出**预警清单 + 通知 + 处置入口**。

蓝图原文：「**职责**：利润异常预警：SKU 毛利转负、店铺利润率骤降、费用占比异常、
广告亏损超标、订单级亏损订单占比突增。**输入**：利润指标投影；阈值规则配置。
**输出**：预警清单 + 通知 + 处置入口。**关系**：上游 = 13.4.1.1；下游 = 1 工作台、5 调价、14 广告。
**亏损订单占比是广告投放失控的早期信号，比 ROAS 下降更早出现。**」

车道：`feat/13-4-3-1`（worktree `.worktrees/13.4.3.1`），基线 `integration/module-dev`（9aee9a86）。

## 决策 / 结论

### 1. 🔴 预警是**读模型**，不是一张事实表

蓝图第 13 章的总纪律：「**不产生业务事实、不做业务写入。报表是投影，可从事件重建；
任何报表数字与业务系统冲突时，以业务系统为准。**」

⇒ 预警 = 「13.4.1.1 的 `reporting_profit_fact` 投影 + 租户自己的阈值规则」的**确定性计算**：
同一批投影 + 同一批规则 ⇒ 同一份预警清单。

**所以本模块只加两张表**，都不存「同一笔钱」：

| 表 | 是什么 | 为什么需要 |
| --- | --- | --- |
| `reporting_profit_alert_rule` | 阈值规则（租户可改） | **阈值不写死在代码里** |
| `reporting_profit_alert_notification` | 通知投递记录 | 「通知」要有**可观测的落点** |

**取舍**：另一种做法是给预警建一张 `..._alert_fact` 表把命中的预警落库。
那样能查历史、也能做「预警台账」，
但它会引入**第二个真相**（投影改了、预警表没重算 ⇒ 两边对不上），
而且这张表的**唯一写入所有者是本模块**、事实来源却在上游 ——
违反「一笔钱只有一个写入所有者」与「报表可从事件重建」两条纪律。
⇒ **不建**。预警每次查询现算，历史由「按历史窗口再查一次」得到。

### 2. 🔴 「判不出来」与「没异常」必须分开（比「算不出来 ≠ 0」更要命）

13.4.2.1 已经消灭了「占比 0.00%」。本模块多了一层：
**有些格子连判定都做不成** —— 历史期数不够、订单级毛利算不出来、该店铺没有广告费事实。

把它们写成「无异常」比误报更危险：
**误报会被处理，「没异常」会被忽略。**

⇒ 服务端把这类条目单列在 `undecided` 清单里**逐条写明原因**，
页面上单独一区展示，**绝不混进「没有命中」的结论里**。

### 3. 阈值单位由 (预警类型 × 阈值口径) **派生**，不落库

「骤降 10」是 10 个**百分点**还是 10**%** 是两种完全不同的含义；
「倍数 1」又是第三种。

⇒ 规则表只存 `threshold_value`（一个数），
**单位由代码派生后随响应下发**（`thresholdUnit` / `observedUnit`）。
存一份单位副本 ⇒ 上游改了口径、库里那份副本不会跟着改 ⇒ 阈值含义静默变掉。

**取舍**：派生意味着「单位」不是数据、不能被租户自定义。
但可自定义的单位没有业务意义（租户不能把「百分点」改成「美元」），
所以派生是对的。

### 4. 阈值取值范围写进 **CHECK**（DB 层挡住不可能的阈值）

- `SKU_NEGATIVE_GROSS_PROFIT` 的阈值是**毛利率上限** ⇒ 必须 **≤ 0**
  （阈值 > 0 那是「毛利率偏低」，不是「毛利转负」）；
- `AD_LOSS_EXCEEDED` 的阈值是**倍数** ⇒ 只允许 `FIXED` 且 **≥ 0**（0 会让任何有广告费的店铺都命中）；
- 其余类型的阈值是**幅度** ⇒ **≥ 0**。

服务层先挡一道并给一句**能展示的话**（「阈值是毛利率上限，必须 ≤ 0」），
DB 的 CHECK 是最后一道防线。

### 5. 通知 = **可观测的投递记录**，不硬编码 SMTP / webhook

蓝图输出句点名了「通知」。真发需要地址与凭据，
而 AGENTS.md 明文规定「**任何 .env / secret 一律不入仓库**」。

⇒ 通道只有站内（`CONSOLE`），落库 + 查询端点；将来接真通道时**在 `channel` 上加值**，
而不是改写历史记录。

**幂等键 = 预警指纹**（类型 + 维度 + 取值 + 币种 + 窗口）：
同一窗口反复点「生成通知」不会产生第二条记录 —— **通知轰炸比没有通知更糟**。

**终态不可复活**（`PENDING → ACKNOWLEDGED / DISMISSED`）：
否则「处理过的预警」会重新出现在待办里，那比不处理更糟。

### 6. 处置入口由**服务端**给出

服务端知道预警类型 ⇒ 知道该去哪儿处理。链接指向**已经存在**的站内页面
（利润报表 / 成本结构 / 调价 / 广告活动 / 广告预算 / 工作台）。
前端不自己编地址 —— 前端编的地址会漂。

⚠️ 本模块**只给定位，不代替下游**：调价要走 5.4 的单据与审批，广告归因归 14 广告。

### 7. 五类预警各自的口径

| 预警类型 | 归属维度 | 观测指标 | 口径 |
| --- | --- | --- | --- |
| `SKU_NEGATIVE_GROSS_PROFIT` | SKU | 毛利率 | 订单**行金额**收入口径；阈值默认 0 = 严格转负 |
| `SHOP_MARGIN_DROP` | SHOP | 毛利率 | 订单**总额**收入口径；BASELINE = 前 N 期同口径窗口均值之差（百分点） |
| `FEE_RATIO_ANOMALY` | SHOP | 费用 ÷ 收入 | 费用 = 平台费 + 物流费 + 广告费 + 头程 + 其它成本 |
| `AD_LOSS_EXCEEDED` | SHOP | 广告费 ÷ 毛利（倍数） | **不拉广告归因成交**（归因口径归 14 广告） |
| `LOSS_ORDER_RATIO_SPIKE` | SHOP | 亏损订单 ÷ **可比**订单 | 订单级成本常缺 ⇒ 分母只算算得出毛利的订单 ⇒ 占比是**下界** |

**「可比订单」的判定按店铺走**（该店铺在窗口跨度内出现过的成本项集合），
**跨窗口用同一把尺子** —— 否则基线窗口与当前窗口的分母口径不同，比出来的差是假的。

### 8. 「缺成本项 = 算不出来」沿用 13.4.1.1 的纪律

上游 12.4.2.2 的分摊多在**店铺 / 订单**维度，
平台费 / 物流费 / 广告费只到店铺级
⇒ **SKU 维度的毛利通常只剩「收入 − 商品成本」**。

本模块**不另立一套口径**：与 13.4.1.1 同一份投影、同一套「缺项即算不出来」规则
（该成本项在租户的该维度上适用、而这一格没有 ⇒ 这一格算不出来，**不是 0**）。
口径一旦有争议三方都不认账 —— 口径必须与上游报表一致。

## 文件清单

**迁移（1）**
- `backend/services/reporting/src/main/resources/db/migration/V401__profit_alert.sql`
  —— `reporting_profit_alert_rule` + `reporting_profit_alert_notification`

**后端（3）**
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProfitAlertModels.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProfitAlertService.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingProfitAlertController.java`

**测试（2）**
- `backend/services/reporting/src/test/java/com/starcon/reporting/ProfitAlertIntegrationTest.java`（16 例）
- `backend/services/reporting/src/test/java/com/starcon/reporting/ProfitAlertTenancyIntegrationTest.java`（3 例）

**前端（4）**
- `apps/web/src/app/analytics/profit/profit-alert/page.tsx`
- `apps/web/src/components/analytics/profit-alert-model.ts`
- `apps/web/src/components/analytics/profit-alert-model.test.ts`（25 例）
- `apps/web/src/components/analytics/profit-alert-workspace.tsx`
- `apps/web/src/app/api/reporting/profit-alert-allowlist.test.ts`

**共享文件（只改自己那一处）**
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（数组末尾），
  既有 258 条元素**一字未改、不重排、不删除**
- `packages/api-contracts/src/contracts/reporting.ts` + `reporting.openapi.json` —— **codegen 产物**
  （+12 类型 / **0 删除**；其余 8 个服务的 spec 与契约逐字节未变）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 **13.4.3.1 那一行**的「状态」「证据」两格

**会话日志（1）**
- `docs/sessions/2026-10/2026-10-29-profit-alert.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ 无错误 |
| L0 类型 | `pnpm typecheck` | ✅ 3/3 successful |
| L1 模块 | `./mvnw -pl services/reporting -am test -Dtest='ProfitAlert*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **19 tests / 0 failures / 0 errors** |
| 前端单测 | `pnpm vitest run apps/web/src/components/analytics/profit-alert-model.test.ts` | ✅ 25 例 |
| allowlist 回归 | `pnpm vitest run apps/web/src/app/api` | ✅ **266 files / 1505 tests**（覆盖任务书指定的 `api/trade` 子集） |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | ✅ 仅 reporting 变更（+12 / -0） |

## 教训 / 坑

1. **零命中核对脚本会静默失效**。第一版脚本按 `/* */` 块注释抽元素，
   而本文件的注释全是 `//` 行注释 ⇒ 抽出 **0 条**却「全部零命中」——
   **一个什么都没比过的检查看起来和通过了没区别**。
   修法：脚本必须**自检**（抽出的条数 == `env:` 条数 == `fallback:` 条数），不符就抛错退出。
   同理，别的车道的**注释散文**里写着 `prefix: 'identity/'` ⇒ 必须先剥掉整行注释再抽。

2. **「0 条抽出」的失败模式是静默的**：本车道差点把一个没验证过的核对写进注释块。

3. **通知的确认时刻不能用手上的值糊弄**：第一版 `updateNotification` 写完库后
   返回了一个 `acknowledgedAt = null` 的对象 ⇒ 断言直接炸。
   改成**写完再读一遍**（确认时刻与确认人从库里读回来）。

## 后续待办

- **1 工作台**（下游）：读 `GET /reporting/profit-alert/alerts` 把预警挂进待办。
- **5 调价 / 14 广告**（下游）：处置入口已给出定位链接，真正的动作在它们那边做；
  14 广告若要提供「按 ROAS 判亏损」的端点，那是**广告模块**的口径，本模块不代劳。
- **13.4.1.2 SKU 利润**（同批另一条车道）：与本模块读**同一批** `reporting_profit_fact`，
  两边的「缺项即算不出来」口径必须保持一致。
- 本模块**不打算**补「预警历史台账」：历史由「按历史窗口再查一次」得到，
  再落一份表就会产生第二个真相（见决策 1）。
