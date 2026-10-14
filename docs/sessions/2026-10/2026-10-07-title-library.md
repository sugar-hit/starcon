# 2026-10-07 标题库（3.4.2.1）

## 决策摘要

交付蓝图 L3「3.4.2 文案资产」下的 **3.4.2.1 标题库**，前端锚点 `/products/content`。
基线 `feat/3-4-1-2-image-processing`（tip `cfd9efc`），分支 `feat/3-4-2-1-title-library`。

蓝图对本模块只点名了一条算法要求：**「批量生成要保证"相似但不雷同"，需在生成算法中显式控制重复度」**。
这条被当成整个模块的设计支点来实现，而不是当成锦上添花。

## 关键 trade-off

### 1. 重复度控制：确定性规则，而不是随机碰运气

蓝图要求「显式控制」，所以相似度与生成过程都做成了**纯函数**（`TitleRules` / `TitleGenerator`，
不碰数据库、时钟与随机数）：同样的输入永远得到同样的输出，于是「重复度已受控」可以被单元测试逐条断言。

相似度用两个确定性成分的千分比加权和（**全程整数除法，无浮点**）：

```
sim = (700·dice + 300·adjacent) / 1000
dice     = 2·|A∩B| / (|A|+|B|)   分词多重集上的 Dice 系数
adjacent = |G∩H| / |G∪H|         字符二元组集合上的 Jaccard
```

- 只算 `dice` 不够：「词序换了但词没换」在多重集上完全等价，而平台判重看的是整句话。
- 只算 `adjacent` 不够：中文改一个字会波及一片二元组，惩罚过重。
- 0.7 / 0.3 是刻意的偏重：词袋是主体，相邻关系用来区分「同样这批词的两种排法」。

分词口径三步：NFKC 归一 + 转小写（否则「换全角避重复」成了假差异化）、拉丁连续段合并成一个词元、
CJK **逐字**成词元（中文没有空格，逐字切是唯一确定性的口径）。

生成的差异化靠**两条轴**：词序轮转（`rotation = t % 变量个数`）+ 三类词池下标错开取（`t`、`t+1`、`t+2`）。
三条轴必须互相错开——同步轮转会让每 n 轮重复一次同样的组合。

### 2. 「拒绝」也是一种输出

找不到过闸组合时返回带原因的 `Rejection`，**不放宽阈值凑一条雷同的**。
因此「这个 SKU 只产出 2 条而要了 3 条」是一个诚实且可行动的结果（去加修饰词），
而不是被掩盖的重复。前端与文案都据此反复说明「被拒是正常结果，不是系统坏了」。

### 3. 重复度的比较范围限定在单个 SKU 内

平台判重复铺货看的是**同一个商品在不同站点/店铺的标题是否雷同**；两个不同商品标题相似是品类共性
（「收纳盒」），判重复铺货时反倒不会被拿来对质。所以比较范围 = 该 SKU 已有 + 本次新生成。

这个选择同时是**性能依据**：比较对数与「单 SKU 的候选数」平方成正比，与批次 SKU 总数无关。

### 4. 同步生成，不做异步任务

与 3.4.1.2（图片处理）刻意不同。那里的长耗时来自逐像素加工；这里的成本只与单 SKU 内的候选数有关
（因为范围收在单 SKU 内），200 个 SKU 一次生成是几十毫秒。做成异步只会让运营为几百毫秒去盯进度条。
批次状态因此是三态 `SUCCEEDED / PARTIAL / FAILED`，**没有「排队中」这种自欺欺人的状态**。

### 5. 重复度上界钉在 999 而不是 1000

`1000‰` 就是允许与已有标题完全一样，等于没有重复度控制。所以 `TitleRules.MAX_SIMILARITY_PERMILLE = 999`，
V20 的 CHECK 与前端校验都把 999 当天花板——**完全雷同在配置层就不可能被配出来**。

### 6. 平台长度上限按（模板 × 平台）而不是只按模板

eBay 80 / 亚马逊 200 / Shopify 255 口径不同，而批量会同时发给多个平台。
模板没配的平台直接被拒，而不是悄悄退回默认值——那会让运营以为自己发到 eBay 的标题没超长。

### 7. 与 3.4.2.2 描述与卖点库的边界

3.4.2.2 管**长文本**（详情描述模板、卖点短语库、多语言版本，无字符上限）；
本模块的 `title_keyword` 是**标题用的短词根**（≤60 字符，服务于「标题里放哪几个词」）。
两者排版约束不同，把长文本卖点库塞进标题变量只会让长度控制失去意义。边界写进了 V20 文件头。

### 8. 权限沿用 `catalog.spu.read/update` 而不新造词表

标题是挂在 SKU 上的商品文案、上游是 3.1.1.1 的 SPU/SKU。新词表要改 identity 服务的迁移，
属于另一个服务的写入所有权。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V20__title_library.sql` — 七张新表 + 四个触发器 + 索引 + RLS/GRANT

**后端（`backend/services/catalog/src/main/java/com/starcon/catalog/`）**

- `TitleRules.java` — 纯规则层：相似度算法、分词、码点裁剪、词表与校验
- `TitleGenerator.java` — 纯函数生成引擎：渲染 + 词序轮转 + 重复度闸门
- `TitleService.java` — jOOQ 显式 SQL、模板/词根/批次/候选、选用与 CSV 导出
- `TitleController.java` — `/catalog/titles` 端点
- `CatalogModels.java` — 21 个新 DTO（`Title*`）

**后端测试（`backend/services/catalog/src/test/java/com/starcon/catalog/`）**

- `TitleRulesTest.java` — 23 例
- `TitleGeneratorTest.java` — 14 例
- `TitleLibraryIntegrationTest.java` — 12 例（含租户隔离三断言 + 末尾实测缺陷台账）

**前端**

- `apps/web/src/app/products/content/page.tsx` — 路由页
- `apps/web/src/components/catalog/title-workspace.tsx` — 工作区（加载·空·错误三态）
- `apps/web/src/components/catalog/title-model.ts` — 前端模型与校验（**不重算相似度**）
- `apps/web/src/components/catalog/title-model.test.ts` — 27 例
- `apps/web/src/lib/api-proxy.ts` — `titles` 白名单放行（34 条路径正反断言）

**契约**

- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json` — `pnpm contracts:generate` 重新生成（316 DTO，新增 21 类型）

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md` — 3.4.2.1 → 已交付
- `docs/sessions/2026-10/2026-10-07-title-library.md` — 本文件

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS（catalog 36.9s；全仓库 58s）
- `pnpm typecheck` → 3/3 成功
- `pnpm lint` → 3/3 成功（`variant-combination-workspace.tsx` 的两条 warning 是既有的，与本模块无关）
- `pnpm test` → 32 文件 / 423 用例全过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，`/products/content` 静态预渲染 12.8 kB
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致（已交付 50）

## 后续待办

- **3.4.2.2 描述与卖点库**（同一 L3 的兄弟模块，未开发）：本模块已把边界划清（长文本 vs 短词根），可直接接着做。
- **04 刊登接入**：候选集就是 `title_record`，刊登侧按 `sku_id + platform` 取 `status='SELECTED'` 的一条即可，
  不需要认识本模块的表。本模块不感知 listing，也不写任何刊登状态。
- **多语言标题**（3.4.3.1）：本模块的模板与词根是**单一语言**的（中文标题库）——
  「同一语言内不雷同」与「跨语言是否算重复」是两套判定，后者需要 18 的 AI 翻译参与，不在本模块范围。
