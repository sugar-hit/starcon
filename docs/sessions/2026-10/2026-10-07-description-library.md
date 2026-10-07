# 3.4.2.2 描述与卖点库（D1–D2）

- 日期：2026-10-07
- 分支：`feat/3-4-2-2-description-library`（基线 `feat/3-4-2-1-title-library` @ `a950843`）
- 状态：**已交付**（后端 + 契约 + 前端 + 台账全绿）

## 交付了什么

蓝图 3.4.2.2 的职责是「管理详情描述模板、卖点短语库、使用说明、注意事项，支持变量占位与多语言版本，
供刊登批量填充」，输出「填充后的描述文本（分语言）」，下游是 04 刊登与 15 客服。

四件套：V21 迁移（八张表）/ `/catalog/descriptions` 端点 / `/products/content` 页签工作区 / 三个 Java
测试类 + 一个 TS 测试文件。

## 关键决策

### 1. 与 3.4.2.1 的边界：短文本 vs 长文本

沿用 V20 文件头的判断，边界写进 V21 文件头：

| | 3.4.2.1 标题库 | 3.4.2.2 描述与卖点库 |
|---|---|---|
| 文本 | 有平台硬字符上限（eBay 80 / 亚马逊 200 / Shopify 255） | **无上限**（正文 20000 字符） |
| 核心矛盾 | 相似但不雷同的重复铺货判定 | 变量缺失必须报告 + 多语言一致性 |
| 词库 | `title_keyword` ≤ 60 字符短词根 | `content_phrase` ≤ 2000 字符长句 |

所以不建 `title_*` 表、不复用 `title_template`、不开 `package/sdk` 之外的交叉引用。
前端也是两个工作区用页签分开，而不是合并——切到描述页看不到标题的平台长度上限，
反过来也看不到卖点短语池。

### 2. 「变量缺失必须报告」怎么才算真的做到

蓝图原文是硬要求：「变量缺失必须在填充时报告，不能静默留下占位符。」
只靠服务层自觉的话，一次手工 SQL 就能绕过，所以钉了五处在数据里：

1. `CHECK (status<>'COMPLETE' OR text NOT LIKE '%{%')` —— 声称填好就不许有花括号
2. `CHECK (status='COMPLETE' OR char_length>0)` —— 声称填好必须有正文
3. `guard_content_render_section_complete` 触发器 —— INCOMPLETE→COMPLETE 被拒
4. `guard_content_render_section_text` 触发器 —— 逐条兜底（CHECK 可被 `NOT VALID` 绕过，触发器不行）
5. `content_render_issue` 一行一个变量，不塞进批次的一句 error

四种成因（`EMPTY_FACT` / `EMPTY_PHRASE_POOL` / `TRANSLATION_MISSING` / `UNBOUND_PLACEHOLDER`）
分开报，因为**修法完全不同**：补商品字段 / 建短语 / 补译文 / 补变量绑定。
混成一句「填充有问题」，运营看到之后不知道该改哪儿。

### 3. 缺变量是一种输出，不是一种失败（与 3.4.2.1 方向相反）

3.4.2.1 是「拒绝也是一种输出」——不产出候选并给原因。
本模块相反：**照样产出正文**（把占位符剥空、整理空白），段落标 `INCOMPLETE`，缺失逐条报告。

理由是长文本的运营诉求不同：「这段描述里 brand 没填上」比「这段描述失败了」有用得多，
运营看到报告就能去补模板变量或短语池，然后只重跑受影响的那批。

代价是批次状态变三态，判据必须是**「有没有产出正文」**而不是「变量是否全有值」——
把「有正文但缺 brand」判成 FAILED 会让运营以为要重来一次。

### 4. 缺译文整批拒，缺字段不拒

- **缺译文**（非源语言且无译文行）→ 提交时整批拒。悄悄退回源语言正文会让运营以为发出去的是译文，
  而 04 刊登引用的是段落里的 `text`。
- **商品字段为空 / 短语池为空** → 不拒，交给渲染器逐变量报告。它们是逐 SKU / 逐类型的正常状态。

### 5. 刻意与 3.4.2.1 相反的两条校验

- **不要求至少一个占位符**：「请勿进水」「适用于 0~40℃」这种注意事项就是一个变量都没有的固定句子，
  完全成立。强制加占位符只会逼运营写废话占位。但**花括号必须配平**——漏写右括号会让解析吞掉后面一整段。
- **四类内容共用一张模板表**（`content_type` 四值），而不是四张形状一样的表：
  拆成四张会让变量、翻译、填充这三套机制各写四遍。

## 开发中实测修掉的真缺陷

1. **`localeProblem` 双重返回语义** —— 早先「合法时返回规范化值、非法时返回原因」，
   被顺手塞进 `firstNonNull(...)` 后 `"zh-CN"` 本身被当成问题报出来，模板一条都建不出来。
   改成与本类其它 `*Problem` 同一契约（合法返回 null），规范化值走 `normalizeLocale`。
   *一条方法两种返回语义是这类 bug 的温床。*
2. **locale 筛选静默失效** —— 入库存 `zh-CN`、查询用 `toUpperCase` 变 `ZH-CN`，永远匹配不到。
   症状是页面上「明明有数据却查不出来」。改成语言小写 + 地区大写，入库与查询同一份规范化。
3. **段落 count 查询漏了 `s` 表别名**，而 WHERE 片段是 `s.xxx=?` → jOOQ 报「未设定参数值 3 的内容」。
   分页查询也漏了 `tenant_id` 绑定参数。
4. **`SKU_SIBLING_LIST` 被当成普通事实字段** —— 它以 `SKU_` 开头，落进 `startsWith("SKU_")` 分支，
   报出「商品字段为空，补上这个字段」，而 SKU 上根本没有「型号清单」这个字段，运营找不到地方改。
   修法完全不同，必须先判它。
5. **剥空后留下「音箱，,。」** —— 相邻两个变量都缺时产生连续同标点。`tidy` 补了连续同标点折叠。
6. **`used_phrases` 改用 jsonb** —— 短语可含换行（`MediaRules.normalize` 只 trim），
   逗号分隔的 text 会被正文里的同名字符伪造出来，把快照切成错的两条。

## 影响面

- catalog 服务新增 `/catalog/descriptions`；不改任何既有端点
- `CatalogModels.java` 共享文件只追加 `Content*` record（+331 行），未触碰既有 record
- `/products/content` 从单工作区改为页签切换（标题库 / 描述库），3.4.2.1 的功能与路由不变
- `packages/api-contracts` 为 codegen 产物（316 → 339 DTO），未手改

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，6 个模块；catalog 545 例全过
  （新增 19 + 16 + 12 = 47 例）
- `pnpm typecheck` → 3/3 成功；`pnpm lint` → 3/3 成功（另两条 warning 是 variant-combination 的既有项）
- `pnpm test` → 33 文件 / 459 例全过（description-model.test.ts 36 例）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，
  `/products/content` 预渲染 22.2 kB（改页签前 12.8 kB，增量即描述工作区）
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致
- api-proxy 31 条路径正反断言（含 4 条必须被拒）全过

## 后续待办

- **04 刊登**按 `(sku_id, locale, content_type)` 取 `status='COMPLETE'` 的段落（部分唯一索引
  `content_render_section_usable` 已为这个查询形状建好）
- **15 客服**复用同一批段落作为答复话术，取用形状与 04 相同
- **18 AI 翻译**翻译完调用 `POST /catalog/descriptions/translations` 写回（`origin='AI'`），
  本模块不调 AI
- `packages/sdk` 本次未动：SDK 的类型面随第一个真实调用方（04 刊登）一起补更合适
