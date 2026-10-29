# 2026-10-30 · 13.6.1.2 看板搭建

> 单模块车道（`feat/13-6-1-2`）。本车道只碰 `reporting` 服务 + 一个前端路由 + 一条 api-proxy 白名单
>
> - 台账的 13.6.1.2 那一行。
>
> | 项       | 值                                                                        |
> | -------- | ------------------------------------------------------------------------- |
> | 模块     | 13.6.1.2 看板搭建（卷 13「利润与经营分析」L3 13.6.1 报表构建下的第二格）  |
> | 落地服务 | reporting（卷 13 的唯一归属服务，端口 8088）                              |
> | 迁移     | `V409__board_builder.sql`（五张表 + 一个视图，纯 CREATE TABLE）           |
> | 车道分支 | `feat/13-6-1-2`（不 push，由调度者并入集成头）                            |
> | 台账     | 13.6.1.2 行 由「未开发」改为「已交付」+ 证据格（其余行一字未动）          |

---

## 这个功能做什么

业务人员**搭建自定义经营看板**：拖拽组件、选择指标与筛选、布局保存、按角色共享、设置默认值。

蓝图关系句把边界写死了：**「看板需权限过滤，不同角色看到的组件不同，不能一个看板通吃。」**
本模块的全部设计都是为这一句话服务的 —— 权限过滤**不是**前端把卡片藏起来，是**库层 + 服务层**两道。

上游是 13.6.1.1（受控语义层自助），下游是 1 工作台（可置为个人看板 —— 那属卷 01，本模块**不**另起一套工作台）。

## 关键 trade-off

### 1. 组件上的数字**不落库**

看板 = 组件配置（落库）+ 语义层口径表（V376）+ 投影事实（V376）**实时算出来**的读模型。

落一份结果就是第二个真相：口径改了、事实刷新了，落库的那份不会跟着变。

**取舍**：每个组件渲染时都要跑一次 13.6.1.1 的查询引擎（`ReportingCustomReportService.execute`），
比「存一份结果」慢。换来的是：口径改一处，看板与报表自动跟着改，永远不会对不上。

⇒ 复用方式是把 13.6.1.1 的 `ReportConfig` / `FilterSpec` / `execute` 三个声明**从 private 改成包内可见**
（同包调用），**不重写**聚合逻辑 —— 「另起一套指标计算逻辑」是明确禁止的。

### 2. 权限过滤：库层视图 + 服务层，两道同一条规则

- **库层**：视图 `reporting_board_component_visible`（`WITH (security_invoker = true)` ⇒ 基表的 FORCE RLS 照常生效）
  按组件的 `required_permission` 过滤 —— 没有权限要求的组件谁都能看；否则要当前会话的 `app.grants` 里带这个权限码；
  看板创建者看得到自己摆的全部组件。
- **服务层**：按当前用户的权限 / 角色再过滤一遍。

**取舍**：为什么两条？服务层那道会随代码改动而漂移，库层那道不会 ——
就算哪天服务层的过滤写漏了，库也不会把不该看的组件吐出去。两边是**同一条规则**（视图里的 SQL 片段
与 `requireAccess` / `componentVisible` 的判定一致）。

**取舍**：为什么视图用 `security_invoker = true` 而不是默认的视图属主权限？
默认模式下视图以**视图属主**（迁移用户 = 超级用户）跑 ⇒ 基表的 RLS 会被绕过，租户隔离就没了。
`security_invoker = true` 让视图以**查询者**身份跑 ⇒ 基表 FORCE RLS 照常生效，再叠加视图自己的权限过滤。

**取舍**：创建者能看到自己摆的全部组件（哪怕他没有那个权限）。不这样的话，组件会在保存后
「凭空消失」—— 摆的人自己都看不到自己摆的东西。

### 3. 布局存 jsonb，但形状由 DB 钉死

拖拽的结果是栅格位置与尺寸 `{"x":0,"y":0,"w":3,"h":2}`。

**取舍**：为什么不用四个整数列？因为要存的不止 x/y/w/h 之外的未来字段，而且服务层与 DB 层要用**同一条**形状规则
—— 内联 CHECK 写两遍（组件表 + 模板组件表）就是两份会漂移的真相。抽成 IMMUTABLE 函数
`reporting_board_layout_ok` ⇒ 一处定义两处用。

⚠️ 非法布局**直接报错**（服务层 400 + DB 层 CHECK 兜底），**不静默丢弃** ——
悄悄挪回默认位置会让人以为存上了。

### 4. 模板里的组件用**真表**，不用 jsonb 存一坨

`reporting_board_template_component` 与 `reporting_board_component` **同构**（同样的列、同样的 CHECK、同样的触发器）。

**取舍**：多一张表，换来「模板也不能引用未登记的指标码与白名单外的维度码」。
用 jsonb 存模板组件 ⇒ 模板这条路会绕过触发器与 CHECK ⇒「口径唯一」在模板上被绕过。

### 5. 顺手修了 13.6.1.1 的一个真 bug

`ReportingCustomReportCatalog.Dimension.valueCast()` 对 UUID 维度返回 `text`，拼出
`f.shop_id::uuid = ?::text` —— PostgreSQL **没有 `uuid = text` 运算符**
⇒ **按店铺 / 法人筛选从来就没通过**（13.6.1.1 交付时只测过 CURRENCY 这个文本维度，这条路径没被走到）。

改为按 `kind` 返回 `date` / `uuid` / `text`。本模块的「按店铺筛组件」撞上了它，顺手修好。

## 文件清单

### 后端（`backend/services/reporting/`）

| 文件                                                            | 说明                                                       |
| --------------------------------------------------------------- | ---------------------------------------------------------- |
| `src/main/resources/db/migration/V409__board_builder.sql`       | 五张表 + 权限过滤视图 + 触发器守卫 + RLS + 授权           |
| `src/main/java/com/starcon/reporting/ReportingBoardModels.java` | 出入参模型（DTO 名统一带 `Board` 前缀）                    |
| `src/main/java/com/starcon/reporting/ReportingBoardCatalog.java` | 受控目录与常量（栅格、权限码形状、可见范围、能力声明事实）  |
| `src/main/java/com/starcon/reporting/ReportingBoardService.java` | 服务层（jOOQ 显式 SQL，复用 13.6.1.1 的查询引擎）          |
| `src/main/java/com/starcon/reporting/ReportingBoardController.java` | HTTP 端点（22 条，`/reporting/board`）                    |
| `src/test/java/com/starcon/reporting/BoardIntegrationTest.java`  | 业务口径 28 例（真实 PostgreSQL 16）                       |
| `src/test/java/com/starcon/reporting/BoardTenancyIntegrationTest.java` | 多租户三条断言 9 例 + RLS 断言                     |

**改到 13.6.1.1 的两个文件**（都是最小改动，理由见上）：

- `ReportingCustomReportService.java`：`ReportConfig` / `FilterSpec` / `execute` 三个声明从 `private` 改成包内可见。
- `ReportingCustomReportCatalog.java`：`Dimension.valueCast()` 按 `kind` 返回 `date` / `uuid` / `text`（修 UUID 维度筛选失效）。

### 前端（`apps/web/`）

| 文件                                                        | 说明                                       |
| ----------------------------------------------------------- | ------------------------------------------ |
| `src/components/analytics/board-model.ts`                   | 纯函数层（展示格式化 + 布局换算 + 请求体构造） |
| `src/components/analytics/board-model.test.ts`              | 25 例                                       |
| `src/components/analytics/board-workspace.tsx`              | 工作区（画布 + 拖拽 + 共享 + 默认值 + 模板） |
| `src/app/analytics/custom/board/page.tsx`                   | 路由 `/analytics/custom/board`             |
| `src/lib/api-proxy.ts`                                      | **新增一条独立 `services[]` 元素**（首 token `board`） |

### 契约与台账

- `packages/api-contracts/src/contracts/reporting.ts` + `packages/api-contracts/src/reporting.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（**只改 13.6.1.2 那一行的状态与证据两格**）

## 验证

| 闸     | 命令                                                                                                        | 结果                          |
| ------ | ----------------------------------------------------------------------------------------------------------- | ----------------------------- |
| L0 编译 | `cd backend && ./mvnw -q compile`                                                                          | BUILD SUCCESS                 |
| L0 类型 | `pnpm typecheck`                                                                                            | 3 tasks successful            |
| L1 后端 | `./mvnw -pl services/reporting -am test -Dtest='BoardIntegrationTest*,BoardTenancyIntegrationTest*'`          | **37 例全绿**（28 + 9）        |
| L1 前端 | `pnpm vitest run apps/web/src/components/analytics/board-model.test.ts`                                    | **25 例全绿**                 |
| allowlist | `pnpm vitest run apps/web/src/app/api/trade`（改了 api-proxy.ts ⇒ 必跑）                                     | **71 文件 394 例全绿**         |
| lint    | `pnpm lint`                                                                                                  | 通过（我的文件零告警）         |

### api-proxy 白名单核对（脚本 `.tmp/api-proxy-allowlist-check.mjs`，不入库）

按 `resolve()` 的**同构口径**（先 `path.startsWith(prefix)` 收窄服务 → 再测
`pattern.test(path.slice(prefix.length))`）把文件里**全部 267 条**既有元素逐条实测：

- 本模块 **15 条路径插入前全部零命中**（既有 23 条 `reporting/` 正则都是按命名空间精确匹配的，
  没有一条能吃下 `board`）⇒ 必须新增独立元素；
- 插入后 15 条**只**命中新加的那一条（#38）；
- **刻意不放行**的形状（`board` 裸路径 / `boards/{uuid}/export` / `subscriptions` / `deliveries`）**全部 404**；
- 反向核对 **95 条**别的车道已交付的 reporting 形状，命中集合**逐条未变**（本条不遮蔽别人）。

### 契约核对

重生成前先把**全部 9 个服务**的 spec 用各自的 `OpenApiContractTest` 重新导出（集成头里的
`backend/target/openapi` 是**过期构建产物**，直接用会让 channel 契约出现无关漂移），
再跑 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`：

- **只有 reporting 的契约变化**（+29 个 schema、+15 条 path，**零删除**）；
- 其余 8 个服务的契约**逐字节未变**。

## 后续待办

1. 🔴 **看板的导出与定时推送**属 13.6.2.1（未交付）—— 本模块既无端点也无表，界面上写「未交付」，不提供假按钮。
2. **1 工作台的「个人看板」置位**属卷 01 的下游模块 —— 本模块只提供看板与看板视图。
3. **角色 id 来自 identity，本服务不校验它是否存在**（跨服务只走授权 API / event）
   ⇒ 失效的角色 id 会让这条共享**谁也命中不了**（fail-closed，不 fail-open）。
4. **不提供本位币折算合计** —— 涉币指标按币种分别报（与 13.6.1.1 同一纪律）。
5. 13.6.1.1 的 `CustomReportCapabilities.rules` 在契约里的 `$ref` 从 `RuleNote` 变成 `CustomReportRuleNote`
   （重新生成契约时顺带纠正的一处**过期引用**，不是本模块引入的）—— 并入时若与 13.6.2.1 冲突，按类型名求并即可。
