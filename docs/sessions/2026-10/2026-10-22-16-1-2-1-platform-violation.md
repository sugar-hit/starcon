# 2026-10-22: 16.1.2.1 违规与处罚（阶段 D7）

## 摘要

本轮实现能力蓝图模块 **16.1.2.1「违规与处罚」**（卷 16 合规与风控，L3 锚点 16.1.2 风险事件），
落在 **channel 服务**（与同 L3 父级的 16.1.1.1 / 16.1.1.2 同服务同库）。

蓝图原文：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
**违规常涉及多个商品**，需批量定位受影响 listing 与库存。」

产出：迁移 `V308__channel_platform_violation.sql`（6 张新表、零 ALTER）、
`PlatformViolation{Models,Rules,Service,Controller}.java` + `Money` 值对象、
契约重新生成（+16 路径 / +32 DTO，零删除）、前端路由 `/compliance/violations` 真实工作区、
api-proxy 新增一条独立白名单元素。

## 决策 / 结论

1. **🔴 上游 2 同步 = 「接收平台通知」，不是「拉取」** —— 本仓没有平台违规通知的**拉取**适配器，
   而平台违规通知本身是**平台推送**的形态。所以同步入口是
   `POST /channel/platform-violations/shops/{uuid}/sync`（接收一批通知，逐条按平台通知 id 幂等落库），
   **不是**照抄 16.1.1.1 那个「恒 409 的假拉取入口」——
   那个入口在 16.1.1.1 是因为 ODR 指标**只能拉**；本模块的通知可以**接收**，
   硬造一个恒 409 的拉取入口等于凭空多一个永远打不开的门。
   每批同步落一条 `platform_violation_sync_run`（取到 / 写入 / 跳过（幂等）/ 失败各是几条）。

2. **🔴 幂等锚点落在平台侧不透明 id 上**（`external_notice_id`，唯一键 (租户, 店铺, 通知 id)）。
   不用「标题 + 时间」那种能撞车的组合 —— 那种组合会把两条不同的违规当成同一条。

3. **🔴 值域一律由 DB 层 CHECK 钉死**（值域要可审阅，不只靠应用层 enum）：
   处罚措施 = 蓝图点名的五种、违规类型 10 码、处理状态 5 态、整改状态 5 态。
   蓝图只写「违规类型」未列举 ⇒ 那 10 码是**本模块的默认口径、不是平台事实**，
   能力声明里写明了这一点。

4. **🔴 扣款金额 = Money 值对象**（`com.starcon.channel.Money`：金额 + 币种、禁浮点、金额 ≥ 0、
   最多 6 位小数、币种三位大写），DB 里 `numeric(19,6)` + `varchar(3)`。
   **只有扣款带金额**，其余四种措施一律不带（硬塞一个 0 会让「没扣款」与「扣款 0」变成同一件事）。
   **不同币种不得直接相加** —— `Money.plus` 币种不同直接抛异常；扣款合计**按币种分组**返回，
   **没有**跨币种的总数（本模块不假设任何隐含汇率）。

5. **🔴 影响档位是派生的，不落库** —— 由处罚措施集合现算（停号 > 下架 > 限制销售·扣款 > 警告）。
   存成一列就会与措施表漂移（改了一条措施、档位还停在旧值）。
   「尚无处罚措施」是它自己的一个档位，**不等于没问题**（页面上用中性色，不与「轻微」共用绿色）。

6. **🔴 状态机在两层都被拒**：服务层先拒（422，带人话「不能从 X 转到 Y」），DB 触发器再兜底（23514）。
   「已提交」必须真有完成时刻、「已验收」必须真有验收时刻（DB 层 CHECK 钉住）。

7. **🔴 下游 4 刊登（问题商品下架）其实已交付，且真的下架** —— 4.1.1.1 的「批量下架」
   就在 channel 服务内（`ListingService#batch` 的 DELIST 分支）。
   ⇒ 本模块**真的改 listing 状态**（并由 DB 触发器落 `listing_status_event`），
   不是写一条「已下架」的记录。这条在能力声明里是 `available=true`。

8. **🔴 下游未交付一律不编造**：**12 财务（罚扣款）未交付**（finance 服务在本仓尚未落地）⇒
   扣款金额只落在本模块自己的表里，**不建财务表、不造指向不存在入口的放行形状**；
   **16.1.2.2 申诉管理未交付** ⇒ 处理状态只到「申诉中」，**不编造**申诉单 / 举证材料 / 申诉结果。
   两条缺口在能力声明里是**可查的事实**（available=false + ownerModule + 影响说明）。

9. **🔴 批量定位真的批量**：受影响 listing 由**一条 SQL** 查出（同服务同库读 `listing` /
   `listing_variant`，含「违规点名 SKU ⇒ 反查挂着它的 listing」），
   库存**一次**调 07 库存的授权只读 API（**不是一条条循环**）；可售量口径**原样采用 07 的公式**；
   **上游读不到时如实报「读不到」**（`inventoryAvailable=false`），绝不折成「可售 0」或「全部可售」。

10. **🔴 整改凭证 append-only**：触发器钉死不可改写不可删；可逆性靠追加新凭证冲销，不靠改历史。

## 关键 trade-off / 讨论

- **为什么不给「平台违规拉取」立一个恒 409 的 vendor 端口（像 16.1.1.1 那样）？**
  16.1.1.1 立 `PlatformPerformancePort` 是因为 ODR / 迟发率这类指标**只能拉**，
  而 channel 服务里确实没有任何账号绩效适配器 —— 拉取入口只能恒 409。
  本模块的通知是**平台推送**的形态，入口本就该是「接收」。
  照抄一个恒 409 的拉取入口 = 凭空多一个永远打不开的门，而且那条门后面的运行记录表也会永远是空的
  （**死代码**）。⇒ 选「接收 + 幂等 + 运行记录」这条路。

- **多对多还是拆表？** 一张违规常涉及多个商品（蓝图加粗句），所以
  `platform_violation_subject` 是独立的多对多表，而不是把 sku 塞进违规行的一个列里。
  批量挂载用 `ON CONFLICT DO NOTHING` —— 同一张违规里同一主体重复挂载**被跳过**（幂等），
  返回里分清 inserted / skipped。

- **子表的数据范围怎么收敛？** `platform_violation_subject` / `_remediation` / `_evidence`
  **没有自己的 shop_id**（父违规才有），否则要么漂移、要么得靠触发器同步。
  它们的 data_scope（RESTRICTIVE）策略沿**父违规 → shop** 收敛 ——
  否则一条属于「看不见的店铺」的违规，它的整改凭证会被别人读到。

- **跨服务只存逻辑 id**：SKU 是 catalog 的逻辑 id（**不建 FK、不复制对方任何一列**）、
  订单只存订单号（订单归 09 履约）、库存只经 warehouse 的授权只读 API 取。
  AGENTS.md 禁止跨服务 SQL / FK / 共享视图。

- **契约漂移的一处顺带修正**：重新生成契约时发现 `PerformanceMetricAlertPage` /
  `PerformanceMetricForecastPage` 两个**既有** DTO 少了状态计数字段 ——
  那是 16.1.1.2 留下的契约漂移（后端已加、契约没重新生成）。本轮由 codegen 从真实 spec **一并补齐**，
  **非手改**；identity / catalog / warehouse / trade / crm 五个服务的契约**零漂移**。

## 涉及文件

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V308__channel_platform_violation.sql` — 新建（6 表）

**后端**

- `backend/services/channel/src/main/java/com/starcon/channel/Money.java` — 新建（金额值对象）
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationModels.java` — 新建
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationRules.java` — 新建（值域 + 状态机）
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationService.java` — 新建
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationController.java` — 新建

**测试**

- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationRulesTest.java` — 新建（11 例）
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationIntegrationTest.java` — 新建（20 例）
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationTenancyIntegrationTest.java` — 新建（3 例）

**契约**（codegen 产物，禁手改）

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

**前端**

- `apps/web/src/components/platform-violations/platform-violations-model.ts` — 新建
- `apps/web/src/components/platform-violations/platform-violations-model.test.ts` — 新建
- `apps/web/src/components/platform-violations/platform-violations-workspace.tsx` — 新建
- `apps/web/src/app/compliance/violations/page.tsx` — 新建
- `apps/web/src/app/api/channel/platform-violations-allowlist.test.ts` — 新建
- `apps/web/src/lib/api-proxy.ts` — **新增一条独立 `services[]` 元素**（既有条目一字未动）

**台账 / 记录**

- `docs/product/capability-blueprint/module-progress.md` — 只改 `| 16.1.2.1 |` 一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-22-16-1-2-1-platform-violation.md` — 本记录

## 验证

- `./mvnw -q compile` — exit 0（L0 编译闸）
- `./mvnw -pl services/channel -am test -Dtest='PlatformViolation*' -Dsurefire.failIfNoSpecifiedTests=false
  -DfailIfNoSpecifiedTests=false` — **34 例 0 失败 0 错误 BUILD SUCCESS**（11 rules + 20 integration + 3 tenancy）
- `./mvnw -pl services/channel -am test -Dtest='AccountHealth*,PerformanceMetric*' …` — **75 例全绿**
  （确认 V308 未破坏同 L3 父级的 16.1.1.1 / 16.1.1.2）
- `pnpm typecheck` — 3/3 通过
- `pnpm vitest run apps/web/src/app/api` — **181 文件 1046 例全绿**（改了 api-proxy.ts ⇒ 必须跑全量 allowlist 回归）
- `pnpm vitest run <本模块两个前端测试文件>` — **24 例全绿**
- `pnpm exec eslint <本模块前端文件>` — 零告警

## 后续待办

1. **16.1.2.2 申诉管理未交付** —— 本模块的处理状态已留好「申诉中」这个状态，
   但申诉单 / 举证材料 / 提交跟踪 / 申诉结果**都还没有**；交付后由那一侧读本模块的违规记录。
2. **12 财务未交付** —— 扣款金额在本模块的 `platform_penalty` 里（按币种分组）；
   finance 服务落地后需要决定折算口径才能跨币种合计，**本模块刻意不做这个假设**。
3. **平台违规的拉取适配器仍不存在** —— 本模块走的是「接收通知」这条路；
   若将来要改成从平台主动拉取，需要先有适配器，届时 `platform_violation_sync_run`
   的状态语义可以扩一条 `FETCHED`。
4. **违规类型值域是默认口径** —— 各平台的违规分类并不通用；
   要按平台细分需要按租户扩展值域（**改 CHECK 就要 ALTER 既有表**，届时需要重新评估降回归面的取舍）。
5. 本模块的**影响档位排序**目前是按措施集合派生的固定次序；
   若将来要按「扣款金额大小」参与排序，需要先有跨币种折算口径。
