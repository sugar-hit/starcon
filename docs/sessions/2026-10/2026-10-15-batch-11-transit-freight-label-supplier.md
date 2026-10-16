# Batch 11 — 物流时效/运费/面单模板 + 采购供应商详情

> 2026-10-15 20:52 起，组批模式 4 车道并行。集成头 `fbbb105d` → `4caca9e9`。
> 本批 L2 首次在该分支跑出全量结果，并暴露出一批**与本批无关的历史欠债**（见「L2 与取证」一节）。

---

## 模块 10.1.1.3 时效与限制

**这个功能做什么**：维护每个物流渠道的时效与限制规则 —— 分国家分重量段的时效承诺、尺寸/重量上限、禁限品类与电池液体危险品标记要求。运营在选品与刊登阶段就能拿到「这个 SKU 能不能走这个渠道、走要多久」的判定，而不是等承运商拒收。

**技术落地**：

- 迁移：`V139__logistics_transit_and_limits.sql` —— 三张表（时效规则 / 尺寸重量上限 / 标记要求）+ 5 个触发器 + RLS + GRANT，**纯新建、零 ALTER**
- 端点：`/channel/logistics-transit-limits/**` 共 15 个 —— capabilities / overview / transit-rules CRUD+真分页 / size-limits CRUD / markings CRUD / `POST precheck` / `POST estimate`
- 页面：`/logistics/transit-limits` —— 五个面板，加载/空/错误态齐备
- 测试：`LogisticsTransitIntegrationTest` 27 例（Testcontainers PG16）、`logistics-transit-model.test.ts` 28 例、allowlist 13 例

**关键设计决策**：重量段初版按**闭区间** `[0.5, 30]` 设计，被自己的约束判死 —— 相邻段 `[30, 45]` 在 30kg 上同时命中，估算结果取决于查询顺序。改为**半开区间 `[下限, 上限)`、上限可空（最后一段）**，测试钉住 0.5 / 12.345678 / 29.999999 命中、30 落到下一段。渠道限制走**硬拦截**而非提示，因为蓝图标明「渠道限制漏了会被承运商拒收产生额外费用」。

---

## 模块 10.1.2.1 运费计算器

**这个功能做什么**：输入收货国家、重量、体积、件数、申报价值、是否带电带液体，输出各可用渠道的运费与预计时效对照表，供人工决策或规则自动匹配。

**技术落地**：

- 迁移：`V140__freight_rate_card_and_quote.sql` —— 5 表 + 4 触发器 + RLS + GRANT，**纯新建、零 ALTER**
- 端点：`/channel/freight/**` 共 8 个，含无副作用的 `POST /estimate` 与落库的 `POST /quotes`
- 引擎：`FreightRatingEngine` —— **纯函数，代码里没有任何费率常量**，计费方式与附加费规则全部来自数据库
- 页面：`/logistics/freight-quote`
- 测试：引擎 22 例 + 集成 29 例 + 前端 27 例

**关键设计决策**：蓝图要求「试算用规则、结算用实际，不能拿试算当实际成本」—— 落地为**数据库层的 CHECK 约束**把 quote 的 `cost_basis` 物理锁死为 `RULE_ESTIMATE`，从存储层就杜绝把试算当结算写入。计费边界（首重/续重、体积重、重量段端点、舍入位数与模式）全部有测试钉死。

**已知实现约束**：jOOQ 的 SQL 解析器吃不下 PostgreSQL 数组语法（`?::text[]` 与 `'{...}'` 字面量都试过），`category_codes` 改为逗号串 `text` + DB CHECK + 服务层白名单。

---

## 模块 10.2.2.2 面单模板

**这个功能做什么**：按渠道与国家维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），并真正渲染出可打印的 ZPL 指令。

**技术落地**：

- 迁移：`V141__logistics_label_template.sql` —— 五表 + 5 触发器 + RLS + GRANT，**零 ALTER 任何既有表**
- 端点：`/channel/label-templates` 共 9 个
- 页面：`/logistics/label-templates`
- 测试：集成 28 例 + 模型 17 例 + allowlist 5 例

**关键设计决策**：模板表原设计了 `label_format`（PDF/HTML）下拉，但渲染器只产 ZPL —— 一个会骗人的选择器。**主动移除该字段**，实际产出格式记在 render 行上，概览的 `byFormat` 换成 `byDeclarationKind`（强制附加声明分布）。

**测试抓到的真 bug**：Code 128 编码器漏了 STOP 位 —— 条纹都在、长度看着正常，扫码枪直接读不出。测试钉住后才修好。这类缺陷靠「看着对」永远发现不了。

---

## 模块 6.1.1.2 供应商详情

**这个功能做什么**：单个供应商的全景视图 —— 基础信息、账期与开票信息、供货 SKU 与报价、采购历史与金额聚合、交期与质量表现、联系人、附件资质。

**技术落地**：

- 迁移：`catalog/.../V142__vendor_detail_terms_qualifications.sql` —— 两表，**零 ALTER 既有表**，FORCE RLS + GRANT
- 端点：9 个，挂 `/catalog/supply/vendors/{roleId}/*`
- 页面：`/supply/vendors/[roleId]`
- 测试：集成 26 例（真实 PG16）+ 前端 17 例

**关键设计决策（服务归属偏离任务书）**：任务书指定落 `trade`，worker 核实后**证伪了这个前提** —— 集成头里 6.1.1.1 整个落在 catalog，且本模块所有输入表都在 catalog。它用两条硬约束证明 trade 方案不可行：① 四个聚合指标必须由聚合 SQL 从事实表算出，事实表归 catalog；② 把事实表复制到 trade 再写聚合，正撞 AGENTS.md「每张事实表只有一个写入所有者」。最终落 catalog，迁移号仍用预分配的 V142。

**顺带修掉的既有缺陷**：`api-proxy.ts` 的 catalog 白名单里**根本没有 `supply/vendors`** —— 6.1.1.1 已交付模块的页面请求会在浏览器代理层静默 404。后端端点和页面都在，前端调不通。

---

## L2 与取证：本批最重要的一节

L2 在集成头 `0c7719e0` 上跑出**首个全量结果**（此前该分支从未被完整验证过）：

| 阶段 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | ✅ BUILD SUCCESS（195 条 Tests run 汇总，零失败零错误） |
| `pnpm typecheck` | ✅ 3/3，0 个 TS error |
| `pnpm lint` | ✅ 3/3 |
| `pnpm test` | ❌ 13 例失败 / 3181 |
| Next build | ✅ Compiled successfully |
| `sync --check` | ❌ 台账与蓝图不一致 |

**取证过程**：未直接二分（只有 1 例新增，二分不划算），改用**基线对照** —— 把 `api-proxy.ts` 临时换成本批之前的版本 `fbbb105d` 跑同一批测试，再恢复。结论：

- **本批唯一新增回归 = 1 例**：`catalog/customs-declaration/resolve`
- **12 例是既有欠债**，本批之前就红（bom-binding 7 / order-overview 3 / nav-routes 2）

**根因**：6.1.1.2 往 catalog 那条超长单行正则里追加 `supply\/vendors(...)` 时，是**整条顶替**而非追加 —— 把原有的 `customs-declaration\/resolve` 挤掉了。worker 摘掉新分支后与旧行**逐字相等**（首个失配字符下标 5586）。已按 `cadb9ce8` 单行恢复，命中数 6→5→6。

**为什么这条是真实生产回归而不是测试脆弱**：`api-proxy-union.test.ts` 是 stub `fetch` 后**调用真实代理处理器**断言 HTTP 状态码（该文件注释自述这是唯一可信判据）；而 bom-binding / order-overview 两个测试是用 `readFileSync` **解析源码文本**抽正则，脆弱性来源不同，不能混为一谈。

**修复后全量复跑**：12 例失败 / 3169 例通过（对比修复前 13 / 3168）。**本批净新增失败 = 0，与基线完全一致**。

**仍然遗留的既有欠债（12 例，不在本批范围）**：

- `bom-binding-api-proxy.test.ts` 7 例（6.4.1.2）
- `order-overview-api-proxy.test.ts` 3 例（9.1.1.2）
- `nav-routes.test.ts` 2 例 —— `/logistics/channels 标记=false 实际=true`，10.1.1.2 的老问题

另有两处已被 worker 实测确认的**漏放行**（白名单有注释声明、实际正则里没有）：

- `catalog/supply/vendors/detail-options` —— **已随 6.1.1.2 进集成头，在线缺陷**
- `channel/freight/quotes/{id}` —— 10.1.2.1 并入后生效；且裸路径 `freight` 反而放行了，与该车道自己注释相反

根因是这两条车道都没有自己的 allowlist 测试兜底。

---

## 组批模式的两个实测教训

**① 「编码阶段低发热」的前提不成立。** PIPELINE.md 的温度三层模型假设 worker 只写代码、L1 很轻。实测峰值 **load 1min = 76.94**（阈值 12 的 6.4 倍）、Testcontainers PG **8 个**（阈值 6）。数到的进程是 2 个 maven JVM + 6 个 surefire fork。单条车道的 L1 真实成本 ≈ maven JVM + 多个 surefire fork + 独立 PG 容器 + ryuk —— **4 条车道并发跑 L1，叠出来的就是 load 77**。发热源不是 L2 批次闸，是「N 条车道并发 L1」这个被当作低发热层的动作。

**② N 条车道会各自独立发现同一个跨模块问题，然后各自改同一段代码。** 本批 10.1.2.1 与 10.2.2.2 都因为新增复合 FK 导致兄弟测试 `truncateAll` 撞 23503，两边互不可见、各自写了一个「加 CASCADE」的修复版，语义等价但文字不同，落在同一段上必然冲突。**跨车道的同一个坑会被踩 N 次、同一段代码被改 N 次** —— 这是组批模式的结构性代价，不是偶发。

并入冲突最终由**一个** worker 串行处理两条车道（绝不派两个，否则双 writer）。合并形态选**拆 services[] 元素**而非 alternation 合并：保留 10.1.1.3 的条目原位不动，自己的追加其后，对集成头**零行删除**（+37/+35 纯新增）。`truncateAll` 按「显式表名全保留 + 末尾加 CASCADE」处理，注释合并成一段写清三组 FK 的来源。
