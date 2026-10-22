# 2026-10-23 —— api-proxy 白名单缺口：诊断与修复车道派发

> 关联批次：`batch-71`（派发轮）之后追加的一次**缺陷修复派发**。
> 集成头 `integration/module-dev`（HEAD = `3e550ac0`）。
> 修复车道：`17.5.2.1`（数据字典），分支 `fix/17-5-2-1-api-proxy-whitelist`，
> worktree `.worktrees/17.5.2.1`，`migrationVersion = null`（**纯前端，不动后端**）。

---

## 一、缺陷是什么

`apps/web/src/lib/api-proxy.ts` 是**累积型浏览器白名单**：

```js
function resolve(path) {
  for (const service of services) {
    if (!path.startsWith(service.prefix)) continue;
    if (service.pattern.test(path.slice(service.prefix.length)))
      return { base: process.env[service.env] ?? service.fallback };
  }
  return null;   // ← 没命中 ⇒ 直接 404「接口不存在」
}
```

**已交付的 4 个模块的前端页面调用了后端真实存在、但白名单里从未登记的路径
⇒ 这些页面在浏览器里每一次请求都 404。**
而后端端点、类型、测试**全部正确** —— 缺的只是浏览器侧那一行登记。

**最危险的特征：`typecheck` / `lint` / `vitest` / `Playwright` 全绿。**
白名单是运行时行为，静态检查与组件级测试都覆盖不到它。
这正是 `PIPELINE.md` 里点名过的形态：「取错一侧会静默删掉别人已交付的路由，
且 typecheck / lint / vitest / Playwright **全绿**」。

---

## 二、缺口清单（调度者独立复现，非道听途说）

### 复现方法

直接**求值真实的 `services[]` 数组**（不是正则扫描源码 —— 第一版扫描脚本是坏的，
控制路径 `identity/roles` 竟也报 NO MATCH，才暴露了脚本本身有 bug），
再按 `resolve()` 的真实口径跑：

> ⚠️ **复现时最容易踩的坑**：传给 `resolve()` 的必须是**含服务前缀的完整路径**
> （如 `identity/dict-items`，不是 `dict-items`）—— 漏前缀会得到全盘 NO MATCH 的假阴性。

复现脚本见修复车道任务书（可直接复制到 `.tmp/` 下运行）。

### 4 组缺口

| # | 模块 | 服务 | 前端调用点 | 缺失形状 | 后端端点（**真实存在**） |
| --- | --- | --- | --- | --- | --- |
| ① | **17.5.2.1 数据字典** | identity | `components/iam/dictionary-workspace.tsx` | `dict-catalog` / `dict-items` / `dict-history` / `dict-items/{id}` / `dict-items/{id}/usage` / `dict-items/{id}/deactivate` / `dict-items/{id}/reactivate`（+ `options/{dictCode}`） | `DictionaryController`（类级 `/identity`）11 个 mapping |
| ② | catalog 生命周期 | catalog | `components/catalog/spu-lifecycle.tsx` | `lifecycle/rules/{id}` 等 | `LifecycleController`（类级 `/catalog`）8 个 mapping |
| ③ | catalog 商品档案 | catalog | `components/catalog/product-records.tsx` | `records/{kind}` / `records/{kind}/{id}` | `CatalogController`（类级 `/catalog`）4 个 mapping |
| ④ | trade 采购来源 | trade | `components/supply/sources-model.ts` | `sources/collector-endpoint/{platform}` | `SupplierSourceController`（类级 `/trade/sources`）2 个 mapping |

**① 最严重**：8 个调用形状全部 404 ⇒ **数据字典页整页不可用**。
**④ 特别说明**：同一控制器的 `sources/items/{itemId}/sku-links` 与 `.../sku-links/{linkId}` **早已登记** ——
说明当初是**逐条漏登记**，不是整块没做。

### 排除的假阳性

第一轮扫描还报了 `catalog/lifecycle/rules`、`trade/sources/items/...` 等，
逐条复核后确认：
- `trade/sources/items/...` —— 扫描时把 `${itemId}` 截断导致，**实测能匹配** ⇒ 假阳性；
- `catalog/lifecycle/rules` —— 扫描时把 `?version=3` 混进了路径，**去掉查询串即匹配** ⇒ 假阳性；
- `catalog/records` —— 是 `catalog/records/${kind}` 的截断，`kind` 是运行期值，需按真实值域收紧 ⇒ **真缺口**。

⇒ **教训：白名单这类「运行时行为」的问题，静态扫描必须用「真实求值 + 真实 resolve 口径」，
并对每个候选跑一条已知可用的控制路径做自检，否则你分不清「没登记」和「脚本坏了」。**

---

## 三、为什么现在才抓到

`api-proxy.ts` **不在** `.gitattributes` 的 `merge=contract-union` 覆盖范围内
（覆盖的只有 `packages/api-contracts/src/**/*.ts` 与 `module-progress.md`）。
⇒ 它是**纯累积、多车道共用的行级文件**，每次并入都可能静默丢条目，
而丢失**不会让任何测试变红**。

**已交付模块的页面调用与白名单的对应关系，仓里没有任何一处自动化检查。**
修复车道这次补的 allowlist 回归测试是这个方向的第一道防线，但**只覆盖它自己那几个模块**。

---

## 四、修复车道的派发

### 车道信息

| 项 | 值 |
| --- | --- |
| 模块 ID | `17.5.2.1`（数据字典，**本仓无该模块的车道记录 ⇒ `adopt` 不会覆盖任何历史**） |
| 分支 | `fix/17-5-2-1-api-proxy-whitelist` |
| worktree | `.worktrees/17.5.2.1`（符合 `laneWorktree(id)` 约定，`gc` 能正确回收） |
| 迁移版本 | **无**（`migrationVersion = null`）—— 后端零改动 |
| 注册方式 | `lane.mjs adopt --lane 17.5.2.1 --branch … --worktree … --session …` |

> `adopt` 是本仓既有的**收编车道**机制（`lane.mjs` 第 771 行起），
> 用途是「把已经在别处跑着的任务收编成一条车道，让它落地后走同一条并入路径」。
> 本次用它登记一条**修复车道**：`plan` 只会挑「未开发」模块，挑不出这种「已交付模块的缺陷修复」。

### 🔴 插入锚点（与同批另 3 条车道互不重叠）

`api-proxy.ts` 不在并集驱动器的覆盖范围内 ⇒ 撞锚点必冲突。
**本批 4 条车道 + 1 条修复车道 = 7 处插入点，全部互不重叠：**

| 车道 | 插入锚点 | 行号 |
| --- | --- | --- |
| 16.6.2.1 数据出境与留存 | `// 5.5.1.1 价格历史` 之前 | ~460 |
| **17.5.2.1 修复车道** | `// 14.2.2.1 预算分配（channel 服务）` 之前 | ~715 |
| 16.5.2.2 关联风险 | `// 07 库存：库存总览（7.1.1.1）` 之前 | ~1020 |
| **17.5.2.1 修复车道** | `// 10.7.1.1 退货接收与处置决策` 之前 | ~1794 |
| 17.7.1.1 套餐与权益 | `// 2.4.2.3 映射缺失队列（channel 服务）` 之前 | ~2423 |
| **17.5.2.1 修复车道** | `// 4.2.3.2 合规预检（违禁词/侵权）（channel 服务）` 之前 | ~2637 |
| 17.7.2.1 平台参数与开关 | `services[]` 数组末尾 | ~8956 |

最小间距 **200+ 行** ⇒ git 逐行合并能干净求并。

### 修复车道的三条硬性要求

1. **只放行后端真实存在的形状** —— 必须**逐条读后端 Controller 源码**核对，
   「放行后端不存在的映射比 404 更糟，界面会以为功能已交付」。
2. **id 段用完整 UUID 形状**，**运行期值段**（`{kind}` / `{platform}` / `{dictCode}`）用**受控值域**，
   **绝不**用 `[^/]+` 这种能吞掉任意路径的通配。
3. **必须加 allowlist 回归测试**：正向（从模型源码反向抽取真实调用点逐条断言放行）
   + 反向（未交付形状仍然 404）。

---

## 五、下一轮 tick 的动作清单（更新）

1. `lane.mjs merge-ready` 回收 **4 条 ready 车道**（16.5.2.2 / 16.6.2.1 / 17.7.2.1 / 17.5.2.1 修复）
   —— 17.7.1.1 若已 land 则一并收，共 5 条；
2. 跑「已知互相破坏形状」扫描（新迁移 V321 / V322 / V323 / V324；修复车道**无迁移**）；
3. 跑**一次** L2 全量（`mvnw verify` + `pnpm typecheck/lint/test` + Next build + `sync --check`）；
4. L2 绿 ⇒ 写 **batch-72** 说明（4 个模块各一节 + 修复车道一节）⇒ commit ⇒ push；
5. L2 红 ⇒ **不 push**，二分定位（最多 2 次全量），派修复任务；
6. **⚠️ 先确认所有在途车道的 L1 都已结束再跑 L2**；
7. **⚠️ 并入后核对 `services[]` 元素数**：基线 **173** ⇒ 修复车道 +4 ⇒ 批次末应为 **173 + 4（车道）+ 4（修复）= 181**，
   且**从基线掉落的元素为 0**；
8. **⚠️ 并入后核对契约重名**：
   `grep -oE 'export type [A-Za-z0-9_]+' packages/api-contracts/src/contracts/{channel,identity}.ts | sort | uniq -d` **须为空**；
9. **⚠️ 修复车道必须跑过全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api/trade`）——
   它是本批唯一会动白名单累积文件的车道，白名单回归是它的专属闸。

---

## 六、留给下一轮的更深一层的待办

**本轮只修了已发现的 4 组缺口，没有解决「这类缺口为什么会漏」的根因。**

`api-proxy.ts` 是白名单累积文件，**页面调用与白名单之间没有自动化对照**。
⇒ 建议后续做一件事：**一个全仓级的「前端 api() 调用 ↔ api-proxy 白名单」对照测试**，
把调度者本轮那段 `resolve()` 脚本固化成一个 vitest 用例，
每次新增页面时自动比对 —— **新增页面调用了未登记的形状 ⇒ 测试红**。

这件事**本轮没做**（属新增测试基础设施，不是缺陷修复，且会与本批 5 条车道的 api-proxy 改动抢同一个文件），
**留作下一轮之后的独立任务**。在它落地之前，同类缺口仍可能再发生。
