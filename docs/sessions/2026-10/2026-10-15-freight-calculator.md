# 2026-10-15 · 10.1.2.1 运费计算器（channel）

> 阶段 D5，L3 锚点 10.1.2 运费试算。车道 `feat/10-1-2-1`，worktree `.worktrees/10.1.2.1`。
> 依赖自检：上游 10.1.1.2 渠道配置（`V136__logistics_channel_config.sql` + `LogisticsChannelService`）在集成头 `fbbb105d` 里存在 → **不阻塞**。

## 决策摘要

1. **计分档位自己建表，不复用上游 `base_rate`。** 上游给的是渠道级单值；真实报价要么「首重 1kg + 续重每 0.5kg」（问「进几位」），要么「0–0.5kg / 0.5–1kg …」（问「落在哪一段」）。两种模型边界语义不同，合并成万能公式在界面上看不出差别、在钱上系统性偏差 → `charge_model` 显式二选一。
2. **不复用 10.1.1.3 的表。** 该模块在并行车道上开发，引用一张还不存在的表会让本车道 Flyway 直接炸。分国家时效落在 `freight_channel_country`（本模块自有的**试算口径**），与 10.1.1.3 的**承诺时效**语义不同。
3. **表名一律 `freight_*`。** 刻意不复用 `logistics_channel_*` 前缀：并行车道在同一服务里加自己的表，撞表名会让并入后的 Flyway 在生产炸掉。
4. **试算 ≠ 实际成本做成 DB 约束。** `freight_quote.cost_basis` 是只允许单个取值的 CHECK ⇒ 试算结果**在物理上不可能**被标成实际结算口径。求值时刻（`quoted_at`）落库，事后可复算「当时加没加旺季费」。
5. **可承运性不重写。** 直接调上游 10.1.1.2 的 `/channel/logistics-channels/eligibility`；重写限制规则就意味着总有一份会漏掉「磁力」这类限制。
6. **算不出钱一律留空，不给 0。** `rate_source=NO_RULE` 的行运费各列 NULL（DB 层 CHECK 钉「NO_RULE ⇔ 合计为 NULL」）。一条 0 元运费会被当成「最便宜」选进 9.3.2.1 分仓。

## 关键 trade-off

- **档位边界闭开做成数据（`to_qty_inclusive`）而不是代码里的 if**：不同承运商答案不同，且两者都真实存在。闭开对**总额无影响**，它影响的是**归属**（进 `calc_trace`）与**重叠校验**（重叠 = 同一个重量被算两遍钱）。
- **首重续重的进位方向固定 CEILING，与金额舍入模式分开**：0.5 件续重不存在，少算半段就是白送运费；而金额怎么舍是承运商对账口径，由费率卡的 `rounding_mode`/`rounding_scale` 决定。混成一个 `RoundingMode` 是这类计算最常见的错。
- **没有生效费率卡时回退渠道单值线性估价**（`CHANNEL_FLAT`），而不是「算不出」：完全不算会让「渠道配好了却算不出钱」。但**轨迹里必须写明用的是回退口径**。
- **无上界段渲染成 `)` 而不是 `]`**：+∞ 那个端点永远不闭合，DB 的 `band_range()` 与前端 `bandRangeText()` 同口径。
- **`category_codes` 存逗号串而不是 `text[]`**：jOOQ 的 SQL 解析器吃不下 PG 的数组语法（`?::text[]`、`'{...}'` 字面量、`string_to_array` 的 `'{}'::text[]` 默认值都试过，都报 bad SQL grammar）。这一列只是回显、没有任何查询按它过滤，形态由 DB 层 CHECK + 服务层白名单同时钉住。
- **触发器与 ApiErrorHandler 的冲突**：DB 触发器的拒绝经 `ApiErrorHandler` 会被翻译成一句关于「店铺编码重复」的 409（既有行为，不属本车道文件）。所以服务层**先**做同一套校验并给出正确的 422 理由，DB 触发器留作硬边界（绕过服务层写 SQL 仍被拒），两层都有测试。

## 文件清单

**后端（channel）**
- `backend/services/channel/src/main/resources/db/migration/V140__freight_rate_card_and_quote.sql`（新建，5 表 + 4 触发器 + RLS + GRANT）
- `backend/services/channel/src/main/java/com/starcon/channel/FreightRatingEngine.java`（计费引擎，纯函数）
- `.../FreightCalculatorModels.java`、`FreightCalculatorService.java`、`FreightCalculatorController.java`
- 测试：`FreightRatingEngineTest`（22 例）、`FreightCalculatorIntegrationTest`（29 例）

**前端**
- `apps/web/src/components/logistics/freight-calculator-model.ts` + `-model.test.ts`（27 例）
- `apps/web/src/components/logistics/freight-calculator.tsx`、`apps/web/src/app/logistics/freight-quote/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（**新增一条 `services[]` 元素**，未动既有条目）

**契约 / 台账**
- `packages/api-contracts/src/contracts/channel.ts`、`src/channel.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改 10.1.2.1 那一行 + `sync.mjs` 重算的计数）

**跨模块最小改动**
- `LogisticsChannelIntegrationTest.truncateAll` 加 `cascade`：V140 的表按复合 FK 挂在 `logistics_channel_config` 上，不加会让该类 21 个用例在 setUp 阶段全炸（口径照抄 `CarrierIntegrationTest` 对承运商三张表的同一处理）。

## 验证

- L0：`./mvnw -q compile` 通过；`pnpm typecheck` 三包绿
- L1：`./mvnw -pl services/channel -am test` → **175 例零失败**（本模块 51 例）
- 前端：`pnpm vitest run`（本模块 27 + 既有 proxy 5）绿
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`（先跑各服务 `OpenApiContractTest` 生成 spec）；trade 的既有漂移（生成器头部注释位置）已 revert，不夹带进本车道

## 后续待办

1. **上游 10.1.1.2 的 capabilities 里仍记 `rate_estimation available=false`**（`LogisticsChannelService`）。按「只碰自己的文件」纪律本车道未改；建议 10.1.1.3 车道下次动那个文件时一并翻转为 `available=true`。
2. **10.1.2.2 渠道比价未开发**：本模块只按运费升序给出对照表，不做成本/时效/成功率多目标排序（capabilities 已标 `available=false` + ownerModule + impact）。
3. **实际结算成本回填与费用差异项不在本模块**（归 10.2.1.2 运单与 12 财务），capabilities 同样标为未交付。
4. `ApiErrorHandler` 把所有 DB 异常翻译成关于「店铺编码」的 409（既有行为）。本模块已用服务层预校验规避，但其它模块仍会踩到，值得单独一条 backlog。
5. 分国家时效（`freight_channel_country`）与 10.1.1.3 的承诺时效将来若要合并，需要一次口径对齐：前者是试算用区间，后者是渠道对外承诺值。
