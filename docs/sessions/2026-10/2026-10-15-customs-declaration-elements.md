# 10.5.1.2 申报要素（trade / V138）

- 日期：2026-10-15
- 车道：`feat/10-5-1-2`（worktree `.worktrees/10.5.1.2`）
- 归属：**trade**，迁移 `V138__trade_customs_declaration_elements.sql`

> 本车道开工时 worktree 里已有**上一会话被中断留下的半成品**：迁移写完并在真实 PG16 上
> 验过，Java 层四个文件能编译但**测试一行都没编译过**。本记录同时是「那批半成品的
> 处置结论」—— 见下面「遗留半成品复核」一节。

## 决策摘要

1. **申报品名与销售标题是两道防线 + 界面并排，不是「记得别照抄」。**
   蓝图加粗句「申报品名不能照搬销售标题」需要一个**能与销售标题比较的实体**才成立，
   而 catalog 侧没有销售标题（它在商品/渠道侧）⇒ 这道闸只能落在本模块。
   - 服务层：建集/改集时经端口取到**真实**销售标题后逐字（忽略大小写与首尾空白）比对；
     **取不到销售标题就拒绝建集** —— 没有基准这道闸形同虚设，「跳过校验先建一个」
     正是它失效的开始。
   - DB 层：建集时把当时的销售标题存进 `sales_title_snapshot`，
     `CHECK customs_element_set_declared_name_not_sales_title` + 触发器挡
     「绕过服务层直接 SQL 照抄」。
   - 界面：详情页**并排对照且分色**，销售标题固定带「不可直接作为申报品名」前缀。
   - **人工确认边界**：`declared_name` 是人工填录的清关口径，**没有任何自动照搬入口**。
     可从上游继承的只有**归类事实**（HS 编码 / 法定单位 / 归类口径产地）与
     **基础申报要素清单**（只读展示，不复制成第二个事实来源）。
     九件本体**全部要人填**，机器只负责拦「照抄」与「漏填」。

2. **与 catalog 3.7.1.1 / 3.7.1.2 的边界写死在迁移文件头零之二节。**
   catalog V95 第二节**明文拒绝**在目的地版本里复制申报要素（「只能来自基础属性，
   目的地版本不复制它」）。那个论证对版本表成立，但它同时留下一个真空：
   **各国申报要素体系确实不同**（V95 自己写了这句话），而那个差异在 catalog 侧无处安放。
   ⇒ 本表是那个差异的**唯一**安放处，且**读** catalog 的合成结果
   （`GET /catalog/customs-declaration/resolve`，授权只读 HTTP）而**不复制它的行**。
   另外两件 catalog 明确不做的事：**批次实际信息**（数量/单价/币种 —— catalog 的
   `declared_value_reference` 是参考值不是申报值，V94 零之三明文禁止下游拿它当申报货值）
   与**缺失项提示**（V94 规则 A4 是审核那一刻的一次性二元判定，形状不同）。

3. **缺失项一律查询时现算，不落表。** 缺失项是**当前行的现算结论**。
   落一列就得靠触发器在每次改内容时同步，而**漏同步一次的后果是「已补齐却仍报缺失」**
   —— 运营会为一个不存在的缺口再去查一遍报关行。DB 层只保留发布闸那个**真二元事实**
   （能不能发布）。同理，九件本体**刻意可空**：全部 NOT NULL 的话缺失项提示恒为空集，
   那是一条永远返回「齐备」的假话。

4. **九件是强类型列而不是无结构文本。** 数量 numeric(19,6) + 单价 numeric(19,4) +
   BigDecimal 全程禁浮点；币种**枚举真实 ISO 4217 码**而不是 `[A-Z]{3}` 正则
   —— 那条正则会放行 ZZZ / XXX / ABC 这种形状对但根本不是货币的码，
   而一个填错的币种会让整票货值算错一个数量级。
   Java 侧 `ElementSetCurrency` 枚举与 DB CHECK 由测试「币种枚举与 DB CHECK 逐项相等」
   钉死不漂移（防「前端下拉能选、DB 拒收」或反过来）。

5. **已发布即不可逆，要改就新建版本。** 10.5.1.1 已把这一版抄进
   `customs_declaration_line.declaration_elements`（jsonb 快照，V85），
   快照抄完不会跟着上游改 ⇒ 原地改这一版会让「库里的申报事实」与「报关单上写的那份」
   对不上，而事后复核只能查到其中一份。触发器 C7 钉死已发布行的本体/目的地/SKU/
   生效起点一律不可改，**只允许把终点收紧到一个仍在未来的时刻**。
   补充要素项也一起冻住 —— 只钉头表不够，绕过服务层直改子表就能改掉已发布集合的申报内容，
   而发布闸只在发布那一刻跑过一次。

6. **列表与详情刻意不依赖跨服务调用。** 给高频读列表引入「商品中心不可达就整页 503」
   的依赖，会让商品中心宕机时现场连自己的申报要素都看不到（10.2.1.1 的教训）。
   跨服务核对只发生在**写路径**上（建集 / 改集 / 发布），因为那正是必须核实上游的时刻，
   而写是低频的。上游的两种失败语义**分开**：404 = 业务事实（没维护，去维护），
   其余 4xx/5xx 与连接失败 = 503（暂时不可达）—— 混成一句「取不到申报信息」的话，
   运营会以为要去补维护，而真实原因可能只是商品中心重启。

7. **不产出 HS 编码 / 法定计量单位 / 税率。** 都在 `capabilities` 里标
   `available=false` + 原因 + ownerModule。**主张优惠 ≠ 会免**：优惠幅度取决于目的国税则
   与「实质性改变」判定（属 16 合规与 10.5.2.1，且都要求业务确认），
   界面上把勾选显示成「已享惠」是本模块明确不做的事。

## 遗留半成品复核（上一会话遗留 6 个未提交文件）

### 迁移 V138 —— 复核结论：**齐全，沿用未改一个字节**

逐条核对：CHECK（R1 区间有序 / 目的地格式 / 产地格式 / 数量 > 0 / 单价 > 0 /
ISO 4217 币种枚举 / 品牌类型域 / 原产地规则形状 / **C3 优惠双向一致** /
**C1 申报品名 ≠ 销售标题** / C2 发布态一致性 / 文本长度）、
触发器 C1–C7（含跨表的 C4 发布闸与 C5 区间不重叠、子表冻结）、
**ENABLE + FORCE RLS**（两表）、`GRANT trade_app` + `REVOKE ALL ... FROM starcon_app`
—— 全部到位。`V138` 版本号与既有 `V132` / `V133` 无冲突。

### Java 层 —— 主源码**可编译但测试从未编译过**，修掉 7 个真实缺陷

测试首跑是 **24 处编译错误**，跑起来后 **27 个 error**，收敛到 0 error 后又暴露 3 个
断言缺陷。以下 7 个是**会真出错**的，不只是测试写法问题：

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | `sql.fetch(String, Object...)` 收到 `List` **不展开 varargs** | 整个 List 被当成一个 `ArrayList` 参数，报 `Type class java.util.ArrayList is not supported in dialect POSTGRES`；count 查询因为写了 `.toArray()` 恰好没事 |
| 2 | 列表/汇总查询里 `effective_now` 的两个 `?::timestamptz` 占位符在 SELECT 列表（**早于** WHERE），绑定值却把 WHERE 参数放在最前 | PG 把 `"DE"` 当 timestamptz 写进 bind index 1；**没有筛选条件时恰好蒙对**（`f.params` 为空），带筛选才炸 |
| 3 | 详情路径走 `load()` 时行里没有 `effective_now` 列，jOOQ 严格模式的 `get(field,type)` 抛 `IllegalArgumentException` | **24 例挂在这**；必须用 `field()` 先问「有没有」 |
| 4 | 国家码形状只在 DTO 的 `@Pattern` 上校验 | `@Pattern` 只在 HTTP 入口的 `@Valid` 上生效，不经控制器的调用会绕过它，然后 4 字母去撞 `varchar(2)` 以 **500** 收场 —— 那是一个「你填错了」的 400 |
| 5 | `close()` 对 null 终点一路走到 `isAfter` 抛 NPE | 500 + "null"，运营完全看不懂这是「你想放宽区间」 |
| 6 | 区间重叠只被 DB 触发器拦住 | 抛出来是 `DataAccessException` → **业务冲突回 500 而非 409** |
| 7 | 汇总里的 `effectiveNow` 恒用 `now()`，行却用传入的 `at` | 「按 2030 年看没有生效版本」这一问会得到 1 —— 汇总与行对同一批数据给两个答案 |

另修 3 个**测试自身**的缺陷（共享 DB 导致计数类断言时绿时红 → 照
`BomBindingIntegrationTest` 同一约定在 `@BeforeEach` truncate；区间重叠用例的两个版本
用了**随机不同 SKU** 因此压根不构成冲突 —— 那是一条「因为没撞上而通过」的假活；
`close()` 写死乐观锁 0 而发布已把它推到 1，于是测的是乐观锁而不是区间收紧）。

并发现一个**浏览器里静默 404** 的隐患：`@PostMapping("/")` 只匹配带尾斜杠的路径，
而代理转发过来的是不带尾斜杠的基路径 ⇒ 改成**无值** `@PostMapping`（trade 其余模块也一律
用具名子路径），并补一条 HTTP 层测试钉住「不带尾斜杠能通、尾斜杠 404」。

### 丢弃

无。6 个文件全部复用，命名与结构（`CustomsElementSet*` + 一个 Port 抽象）原样沿用，
没有另起一套实现。

## 关键 trade-off

- **建集时就必须能取到上游**（取不到销售标题就拒绝建集）vs **先建后补**。
  选了前者：一旦允许「跳过校验先建一个」，那道闸就开始失效，而它要拦的错误
  （申报品名照搬销售标题）**要到报关那一刻才暴露**，代价是扣货与罚款。
- **列表不过滤 `at`、只标注 `effectiveNow`** vs **按 `at` 过滤行**。
  选了前者：清单是**维护视图**，运营要能看到自己还没发的未来版本；
  「这一刻哪一版生效」由 `effectiveNow` 标注 + 汇总的生效计数回答。
  过滤掉的话，运营就看不到自己尚未发布的版本了。
- **发布区间重叠：服务层先查后写（409）** vs **只靠 DB 触发器**。
  两层都留着：服务层给可读的 409 让前端能提示「与 v1 撞了」，触发器挡绕过服务层的直接 SQL。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V138__trade_customs_declaration_elements.sql`

**后端**
- `backend/services/trade/src/main/java/com/starcon/trade/CustomsElementSetModels.java`（词表 / DTO）
- `.../CustomsElementSetPort.java`（3.7.1.2 跨服务只读端口，404 / 503 语义分开）
- `.../CustomsElementSetService.java`（jOOQ 显式 SQL + 缺失项现算 + 写路径）
- `.../CustomsElementSetController.java`（9 个端点，权限码复用 `trade.fulfillment.*`）
- `backend/services/trade/src/test/java/com/starcon/trade/CustomsElementSetIntegrationTest.java`（40 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/trade.ts`
- `packages/api-contracts/src/trade.openapi.json`

**前端**
- `apps/web/src/app/logistics/customs-elements/page.tsx`
- `apps/web/src/components/logistics/customs-elements.tsx`
- `apps/web/src/components/logistics/customs-elements-model.ts`
- `apps/web/src/components/logistics/customs-elements-model.test.ts`（27 例）
- `apps/web/src/app/api/trade/customs-elements-allowlist.test.ts`（5 例）

**共享文件（求和追加，未改动他人条目）**
- `apps/web/src/lib/api-proxy.ts`（trade 组追加 `logistics/customs-elements` 分支 + 说明）
- `docs/product/capability-blueprint/module-progress.md`（只改 10.5.1.2 那一行）

## 验证（实跑）

- `cd backend && ./mvnw -pl services/trade -am test` → **591 例全绿**（本模块 40 + 全部既有 trade 用例回归）
- `pnpm typecheck` → 3/3 零错误
- `pnpm lint` → 3/3（无新增 warning）
- `npx vitest run <本车道两个文件>` → **32 例全过**

## 后续待办

1. **建集 / 改集对话框未做** —— 后端 `POST /` 与 `PUT /{id}`、模型层
   `emptyElementSetInput` / `detailToInput` 都已完整，界面上还没有录入九件与补充项的表单。
2. **侧栏/搜索导航未登记** `/logistics/customs-elements` —— `packages/ui` 的共享任务页表
   未改，避免重排他人条目（与 10.5.1.1 / 10.5.1.3 / 10.4.1.1 现状一致）。
3. **错误文案受既有 `ApiErrorHandler` 限制** —— `IllegalArgumentException` 统一翻成泛化 400，
   不指出具体字段。该 handler 为多服务共用，改它会动到既有模块的错误体，本车道刻意不改。
4. **优惠幅度 / 协定税率数值不产出** —— 16 合规与 10.5.2.1 未交付授权可读口径。
5. **`trade.openapi.json` 之前是旧的** —— 本车道 codegen 时顺带把集成头刚合入的
   9.x 时效分析 DTO 补上了（只改 trade 两个文件，其余 5 个服务契约零变动）。
