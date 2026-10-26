# 17.5.2.1 api-proxy 白名单缺口修复（纯前端车道）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`fix/17-5-2-1-api-proxy-whitelist`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/17.5.2.1`）
- **基线**：`integration/module-dev` @ `3e550ac0`
- **迁移版本号**：无（`migrationVersion = null`）—— 本车道**零后端改动、零迁移**，只改浏览器侧白名单

## 决策摘要

`apps/web/src/lib/api-proxy.ts` 是一份**累积型白名单**：浏览器只允许请求命中 `services[]` 里某条
`pattern` 的路径，否则 `resolve()` 返回 null ⇒ 直接 404「接口不存在」。已交付模块的前端页面一旦调用了
后端真实存在、但白名单里没登记的路径，**这些页面在浏览器里 100% 拿不到数据**，
而 `typecheck` / `lint` / `vitest` / `Playwright` **全是绿的** —— 这正是这个文件的经典失配形态。

本车道只做「补登记」，**不改后端、不写迁移、不碰契约**（后端端点全部存在且正确）。

### 1. 四组缺口的独立核对结论（**其中两组与任务书给的结论不一致，以本仓实查为准**）

用 `resolve()` 实测脚本逐条跑过，基线（173 条既有元素）结果：

| 组                              | 任务书结论           | 实测结论                                           | 处置                                             |
| ------------------------------- | -------------------- | -------------------------------------------------- | ------------------------------------------------ |
| ① 17.5.2.1 数据字典（identity） | 8 个调用形状全部 404 | ✅ **属实**，全部 404                              | 新增一条 `identity/` 元素补登记                  |
| ② catalog 生命周期（catalog）   | 页面调用全部 404     | ✅ **属实**，但**漏了 `/products/{id}/lifecycle`** | 新增一条 `catalog/` 元素补登记（含漏掉的那一组） |
| ③ catalog 商品档案（catalog）   | 页面调用全部 404     | ❌ **不属实** —— 已由既有 3.1.x 条目放行           | **不新增元素**（新增只会是走不到的死分支）       |
| ④ trade 采集接入（trade）       | 采集端点 404         | ❌ **不属实** —— 已由既有 6.1.2.1 条目放行         | **不新增元素**（新增只会是冗余分支）             |

**③ 的证据**：既有 catalog 巨型 alternation 里已有 `records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?`
分支。前端的 `kind` 值域（`WorkspaceKind = 'products' | 'research' | 'compliance' | 'assets'`，
`product-records.tsx` 的 `copy` 对象）与后端 `CatalogService.table(kind)` 的 switch
（`research` → `product_research`、`compliance` → `product_compliance`、`assets` → `product_asset`，
`default -> throw missing()`）**完全一致**，实测 `catalog/records/research|compliance|assets`
与 `catalog/records/assets/{uuid}` 全部放行，`catalog/records`（裸路径）/ `catalog/records/products`
（不存在的 kind）被拒。⇒ **不需要新登记**。

**④ 的证据**：既有 6.1.2.1「档口与货源池」元素已含 `\/collector\-endpoint\/[A-Z0-9_]+` 分支，
且既有注释就写着「platform 段用 `[A-Z0-9_]+`：它是平台词表（ALIBABA_1688 / YIWU_GOU）」。
前端唯一的调用来源是 `sources.tsx` 的 `options.platforms[].value`，而后端
`SupplierSourceService.platformOptions()` 的取值就是 `SourcePlatform` 枚举名
（`ALIBABA_1688` / `YIWU_GOU`，`SupplierSourceModels.java:44`）—— **全是大写**，实测放行。

> ⚠️ 任务书里 `trade/sources/collector-endpoint/amazon` 这个反例**不能作为「应当放行」的形状**：
> `amazon` 不是货源平台枚举值（渠道平台枚举 AMAZON/SHOPIFY 属于 channel 服务），
> 后端 `platform(String)` 会 `throw bad("未知的货源平台「amazon」：只能是 ALIBABA_1688 / YIWU_GOU")` → 400；
> 而本仓**既有**的 `apps/web/src/app/api/trade/supplier-source-allowlist.test.ts` 已经把
> `trade/sources/collector-endpoint/alibaba-1688` 钉成 **404**。⇒ 放行 `amazon` 只会违反
> 「放行后端不存在的映射比 404 更糟」这条硬约束，并且与既有回归测试冲突。

### 2. 新增两条 `services[]` 元素（既有条目一字未动、不重排、不格式化整文件）

**① `prefix: 'identity/'`（锚点：`// 14.2.2.1 预算分配（channel 服务）` 那条之前）**

- 逐条核对 `DictionaryController.java`（类级 `@RequestMapping("/identity")`）的 **11 个端点全部放行**：
  `GET /dict-catalog`、`GET /dict-items`、`GET /dict-items/options/{dictCode}`、`GET /dict-items/{id}`、
  `GET /dict-items/{id}/usage`、`GET /dict-history`、`POST /dict-items`、`PUT /dict-items/{id}`、
  `POST /dict-items/{id}/deactivate`、`POST /dict-items/{id}/reactivate`、`POST /dict-items/{id}/usage`。
- id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）。
- `{dictCode}` 段用**受控值域** `[A-Z][A-Z0-9_]{0,63}`（V53 迁移 `dict_catalog.code varchar(64)`，
  出厂码表全是大写蛇形；值域开放 ⇒ 用紧收紧字符集而不是写死枚举，也绝不用 `[^/]+`）。
- 字面量子路径 `options` 排在 `{uuid}` 段之前判别。
- 刻意不放行：`dict-items/{uuid}/delete`（后端**没有** DELETE 端点 —— V53 迁移的四道防线）、
  `/{uuid}/archive`、`/{uuid}/enable`、`dict-items/options`（缺 dictCode）、
  `dict-catalog/{uuid}`、`dict-history/{uuid}`、`dict-items/not-a-uuid`。

**② `prefix: 'catalog/'`（锚点：`// 10.7.1.1 退货接收与处置决策` 那条之前）**

- 逐条核对 `LifecycleController.java`（类级 `@RequestMapping("/catalog")`）的 **9 个端点全部放行**：
  `GET/POST /products/{id}/lifecycle`、`GET /lifecycle/metrics`、`GET/POST /lifecycle/rules`、
  `PUT/DELETE /lifecycle/rules/{id}`、`GET /lifecycle/rules/{id}/preview`、`POST /lifecycle/rules/{id}/run`。
- ⚠️ **`/products/{id}/lifecycle` 这一组任务书没列，但同属 catalog 生命周期模块、同一个前端页面
  （`spu-lifecycle.tsx` 的 GET / POST），且基线同样是 404 ⇒ 一并补登记。**
- 刻意不放行：`/lifecycle/rules/{uuid}/delete`、`/executions`、`/preview/{x}`、`/history`、
  `/lifecycle/metrics/{uuid}`、`/lifecycle/transitions`、`/lifecycle/rules/not-a-uuid`。
- 注释里写明 `catalog/records/{kind}` **刻意不在本条重复登记**的原因（既有条目更靠前 ⇒ 死分支）。

### 3. 关键 trade-off

- **「多写一条冗余元素」vs「不写」。** ③ ④ 两组若各补一条元素：③ 会是**永远走不到的死分支**
  （既有条目在数组里更靠前，`resolve()` 首个命中即返回）；④ 会是既有 `[A-Z0-9_]+` 的**严格子集**
  （功能上零效果，也**收紧不了** —— resolve 是「首个命中即返回」，后面的宽条目照样放行）。
  两条都属于「假交付」：看起来登记了，实际什么都没变。⇒ 选**不写 + 把核对结论写进注释与台账**。
- **顺带把 ② 的漏项补上**（`/products/{id}/lifecycle`）：同一个缺陷、同一个模块、同一个页面，
  不补就是留一个 404 在那儿。属于「同一交付边界内」，不算扩范围。

### 4. 首 token 零命中核对（`grep -c`，基线 173 条既有正则）

| 首 token             | 计数          | 结论                                                                                                                                                                             |
| -------------------- | ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dict\-`             | **0**         | 全文无 `dict` ⇒ 独立字面量                                                                                                                                                       |
| `\^lifecycle`        | **0**         | 21 处 `lifecycle` 全是**第二段**（`attributes/options/lifecycle`、`listing-lifecycles`、`listing-details/{uuid}/lifecycle`），无首 token                                         |
| `records`            | 12 处正则命中 | 逐条核过：`catalog/records` 走既有 3.1.x 分支；其余是 `price-sync/records`、`attributions/records`、`backfills/{uuid}/records` 等**别的模块**的子路径，首 token 不同、互不为前缀 |
| `collector-endpoint` | 1 处正则命中  | 既有 6.1.2.1 元素自己的分支（同一模块、同一控制器），本车道不重复登记                                                                                                            |

## 文件清单

- `apps/web/src/lib/api-proxy.ts` —— **+140 行 / -0 行**（纯新增两条 `services[]` 元素，既有条目零改动）
- `apps/web/src/lib/data-dictionary-lifecycle-api-proxy.test.ts` —— 新增 allowlist 回归测试（6 项）
- `docs/product/capability-blueprint/module-progress.md` —— 仅 17.5.2.1 那一行的「证据」格追加一段
- `docs/sessions/2026-10/2026-10-23-api-proxy-whitelist-gap-fix.md` —— 本文件

## 验证

- `pnpm vitest run apps/web/src/lib/data-dictionary-lifecycle-api-proxy.test.ts` → 6 passed
- `pnpm vitest run apps/web/src/app/api/trade` → 68 files / 379 tests passed（既有 trade 全量 allowlist 回归）
- `pnpm vitest run`（全量）→ 511 files / 7287 tests passed
- `pnpm typecheck` → 3 tasks successful
- `pnpm lint` → 3 tasks successful（只有与本车道无关的既有 warning）
- `npx prettier --check` 两个改动文件 → All matched files use Prettier code style
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- `resolve()` 实测脚本：① ② 的全部形状 ✅；反向用例（非 UUID 段、后端不存在的动作、不存在的 kind、
  非枚举平台）全部 404 ✅

## 后续待办

1. **既有 catalog 巨型 alternation 里 `records/(research|compliance|assets)` 的 id 段是 `[0-9a-f-]+`**
   （不是完整 UUID 形状），比本仓其它条目宽松。收紧要改既有条目 ⇒ 超出本车道「既有条目一字未动」的约束，
   留给单独一条车道。
2. **既有 6.1.2.1 元素里 `collector-endpoint` 的 platform 段是 `[A-Z0-9_]+`**，比后端
   `SourcePlatform`（ALIBABA_1688 / YIWU_GOU）宽。收紧同样要改既有条目 ⇒ 留给单独一条车道。
3. 建议调度者把「**api-proxy 白名单覆盖度**」做成常规闸：把前端全部 `api()` 调用点反向抽取、
   逐条跑 `resolve()`，本车道就是靠这个扫描才发现 ② 的漏项（`/products/{id}/lifecycle`）与
   ③ ④ 的误判 —— 只按「模块已交付」清单逐条对，靠人眼是查不出来的。
