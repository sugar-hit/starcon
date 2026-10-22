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

### 4 组缺口 —— ⚠️ **其中 2 组经修复车道独立复核后被证伪（见下方「勘误」）**

| # | 模块 | 服务 | 前端调用点 | 缺失形状 | 后端端点（**真实存在**） |
| --- | --- | --- | --- | --- | --- |
| ① | **17.5.2.1 数据字典** | identity | `components/iam/dictionary-workspace.tsx` | `dict-catalog` / `dict-items` / `dict-history` / `dict-items/{id}` / `dict-items/{id}/usage` / `dict-items/{id}/deactivate` / `dict-items/{id}/reactivate`（+ `options/{dictCode}`） | `DictionaryController`（类级 `/identity`）11 个 mapping |
| ② | catalog 生命周期 | catalog | `components/catalog/spu-lifecycle.tsx` | `lifecycle/rules/{id}` 等 | `LifecycleController`（类级 `/catalog`）8 个 mapping |
| ③ | catalog 商品档案 | catalog | `components/catalog/product-records.tsx` | ~~`records/{kind}` / `records/{kind}/{id}`~~ | **❌ 证伪：基线已放行** |
| ④ | trade 采购来源 | trade | `components/supply/sources-model.ts` | ~~`sources/collector-endpoint/{platform}`~~ | **❌ 证伪：基线已放行** |

#### ⚠️ 勘误（2026-10-23 07:5x，修复车道复核后）

**调度者的 ③ ④ 两组结论是错的，实际只有 2 组缺口（① ②）。**
修复车道 worker 没有照抄任务书，而是逐条对着源码核对，**证伪了 ③ ④**：

| 组 | 调度者结论 | 实测结论 | 根因 |
| --- | --- | --- | --- |
| ③ catalog 商品档案 | 全 404 | **不属实** | 基线 `catalog/` 那条巨型 alternation 里**早就有** `records\/(research\|compliance\|assets)(?:\/[0-9a-f-]+)?` |
| ④ trade 采集接入 | 端点 404 | **不属实** | 基线 6.1.2.1 那条**早就有** `collector\-endpoint\/[A-Z0-9_]+` |

**调度者为什么会错**（两个不同的坑，都值得记住）：
- **③ 是扫描截断造成的假阳性**：扫描脚本把 `catalog/records/${kind}` 截断成裸 `catalog/records`，
  而裸路径本来就不该放行（后端只有 `/records/{kind}`）⇒ 误判成「整组缺失」。
- **④ 是测试路径取值错误**：调度者测的是 `collector-endpoint/amazon`（小写），
  而货源平台枚举的真值是**全大写**（`ALIBABA_1688` / `YIWU_GOU`），后端只认大写 ⇒ 小写路径本就该 404。
  调度者拿一个**后端不存在的形状**去当「应放行」的证据，方向就错了。

**决定性复核方法（比调度者的脚本可靠）**：修复车道的测试**直接跑真实的 Next 路由处理器**，
断言真实 HTTP 状态码（404 = 未放行 / 转发 = 已放行），而不是自己再写一份正则去比。
把它**跑在基线 api-proxy 上**，失败的断言 = 真缺口：

```
# 把基线 api-proxy.ts 放回测试工作树，跑修复车道的测试
FAIL  catalog/lifecycle/metrics 未被放行（白名单漏登记 = 浏览器里静默 404）: expected 404 to be 200
FAIL  POST identity/dict-items 未被放行: expected 404 to be 200
Tests  2 failed | 4 passed (6)
```
⇒ **恰好 2 组缺口（① ②），③ ④ 的「不回归」断言在基线上就是通过的**。
⇒ 修复车道因此**没有**新增 ③ ④ 两条元素（新增了也是死代码：新分支永远不会被既有条目命中之前抢到）。

⚠️ **教训**：白名单这类「运行时行为」的问题，
**静态扫描 + 自己复刻的 `resolve()` 都不可靠** —— 调度者的第一版脚本连控制路径 `identity/roles` 都报 NO MATCH，
第二版用 `eval` 求值真实数组也仍误判 ③（多行正则字面量让括号配对失准）。
**唯一可靠的判据是「跑真实代理、断言真实状态码」，且必须对每个候选跑一条已知可用的控制路径做自检。**

**① 最严重**：8 个调用形状全部 404 ⇒ **数据字典页整页不可用**。
**④ 特别说明**：同一控制器的 `sources/items/{itemId}/sku-links` 与 `.../sku-links/{linkId}` **早已登记** ——
说明当初是**逐条漏登记**，不是整块没做。

### 排除的假阳性（第一轮扫描阶段）

第一轮扫描还报了 `catalog/lifecycle/rules`、`trade/sources/items/...` 等，
逐条复核后确认：
- `trade/sources/items/...` —— 扫描时把 `${itemId}` 截断导致，**实测能匹配** ⇒ 假阳性；
- `catalog/lifecycle/rules` —— 扫描时把 `?version=3` 混进了路径，**去掉查询串即匹配** ⇒ 假阳性；
- `catalog/records` —— **当时判为真缺口，这个判断是错的**，见上方「勘误」（实为真缺口的是 ① ②，不是 ③ ④）。

⇒ **教训（已被勘误加强）**：白名单这类「运行时行为」的问题，
**静态扫描 + 自己复刻的 `resolve()` 都不可靠**。
调度者两次脚本（正则扫描版、`eval` 求值版）都给出了错误结论，
真正可靠的判据只有一条：**跑真实代理、断言真实 HTTP 状态码**，
并且**必须对每个候选跑一条已知可用的控制路径做自检** —— 否则你分不清「没登记」和「脚本坏了」。

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
**本批 4 条车道 + 1 条修复车道 = 6 处实际插入点，全部互不重叠**
（修复车道原被指派 3 处，实际只用了前 2 处 —— 第 3 处对应被证伪的 ④，未使用）：

| 车道 | 插入锚点 | 行号 | 实际是否使用 |
| --- | --- | --- | --- |
| 16.6.2.1 数据出境与留存 | `// 5.5.1.1 价格历史` 之前 | ~460 | ✅ |
| **17.5.2.1 修复车道** | `// 14.2.2.1 预算分配（channel 服务）` 之前 | ~715 | ✅（identity/dict） |
| 16.5.2.2 关联风险 | `// 07 库存：库存总览（7.1.1.1）` 之前 | ~1020 | ✅ |
| **17.5.2.1 修复车道** | `// 10.7.1.1 退货接收与处置决策` 之前 | ~1794 | ✅（catalog/lifecycle） |
| 17.7.1.1 套餐与权益 | `// 2.4.2.3 映射缺失队列（channel 服务）` 之前 | ~2423 | ✅ |
| **17.5.2.1 修复车道** | `// 4.2.3.2 合规预检（违禁词/侵权）（channel 服务）` 之前 | ~2637 | ❌ **未使用**（④ 被证伪） |
| 17.7.2.1 平台参数与开关 | `services[]` 数组末尾 | ~8956 | ✅ |

最小间距 **200+ 行** ⇒ git 逐行合并能干净求并（**已并入预演证实：五步全 exit 0**）。

### 修复车道的三条硬性要求 —— ✅ **三条都被真正执行了**

1. **只放行后端真实存在的形状** —— 必须**逐条读后端 Controller 源码**核对，
   「放行后端不存在的映射比 404 更糟，界面会以为功能已交付」。
   ⇒ **这条直接救了 ③ ④**：worker 据此证伪了两组「缺口」，**拒绝新增两条会被既有条目先命中的死代码元素**。
2. **id 段用完整 UUID 形状**，**运行期值段**（`{kind}` / `{platform}` / `{dictCode}`）用**受控值域**，
   **绝不**用 `[^/]+` 这种能吞掉任意路径的通配。
   ⇒ 实际落地：`{dictCode}` 用 `[A-Z][A-Z0-9_]{0,63}`（对齐 V53 `dict_catalog.code varchar(64)` 出厂码表）。
3. **必须加 allowlist 回归测试**：正向（从模型源码反向抽取真实调用点逐条断言放行）
   + 反向（未交付形状仍然 404）。
   ⇒ 实际落地：`apps/web/src/lib/data-dictionary-lifecycle-api-proxy.test.ts`，
   **6 个 `it()` / 17 个正向形状 / 21 个反向形状 / 26 条既有模块不误伤**，
   判据是**跑真实 Next 路由处理器、断言真实 HTTP 状态码**（404 vs 转发）——
   **不是自己再写一份正则去比**（这正是调度者两次自研脚本翻车的地方）。

---

## 五、下一轮 tick 的动作清单（更新）

1. `lane.mjs merge-ready` 回收 **4 条 ready 车道**（16.5.2.2 / 16.6.2.1 / 17.7.2.1 / 17.5.2.1 修复）
   —— 17.7.1.1 若已 land 则一并收，共 5 条；
2. 跑「已知互相破坏形状」扫描（新迁移 V321 / V322 / V323 / V324；修复车道**无迁移**）；
3. 跑**一次** L2 全量（`mvnw verify` + `pnpm typecheck/lint/test` + Next build + `sync --check`）；
4. L2 绿 ⇒ 写 **batch-72** 说明（4 个模块各一节 + 修复车道一节）⇒ commit ⇒ push；
5. L2 红 ⇒ **不 push**，二分定位（最多 2 次全量），派修复任务；
6. **⚠️ 先确认所有在途车道的 L1 都已结束再跑 L2**；
7. **⚠️ 并入后核对 `services[]` 元素数**：基线 **173** ⇒ 4 条车道各 +1 ⇒ 修复车道 **+2**（不是 +4，见「勘误」）
   ⇒ 批次末应为 **179**（**已并入预演证实，见「附一」**）；且**从基线掉落的元素为 0**；
8. **⚠️ 并入后核对契约重名**：
   `grep -oE 'export type [A-Za-z0-9_]+' packages/api-contracts/src/contracts/{channel,identity}.ts | sort | uniq -d` **须为空**；
9. **⚠️ 修复车道必须跑过全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api/trade`）——
   它是本批唯一会动白名单累积文件的车道，白名单回归是它的专属闸。

---

## 六、留给下一轮的更深一层的待办

**本轮实际只修了 2 组缺口（① ②，另有 2 组被证伪），没有解决「这类缺口为什么会漏」的根因。**

`api-proxy.ts` 是白名单累积文件，**页面调用与白名单之间没有自动化对照**。
⇒ 建议后续做一件事：**一个全仓级的「前端 api() 调用 ↔ api-proxy 白名单」对照测试**。
⚠️ 但**判据必须是「跑真实代理、断言真实 HTTP 状态码」** ——
修复车道这次的测试（`apps/web/src/lib/data-dictionary-lifecycle-api-proxy.test.ts`）就是这么做的：
它直接 import 真实的 Next 路由处理器，断言 404 vs 转发，
**而不是自己再写一份正则去比**（调度者两次自研脚本都给出了错误结论，见「勘误」）。

⇒ 落地形态：把「从模型源码反向抽取调用点 → 逐条断言被放行 / 未交付形状仍 404」做成**全仓通用**的测试，
每次新增页面时自动比对 —— **新增页面调用了未登记的形状 ⇒ 测试红**。

这件事**本轮没做**（属新增测试基础设施，不是缺陷修复，且会与本批 5 条车道的 api-proxy 改动抢同一个文件），
**留作下一轮之后的独立任务**。在它落地之前，同类缺口仍可能再发生。

---

## 附一：5 条车道的并入预演（`git merge-tree` 干跑，2026-10-23 07:4x / 07:5x）

5 条车道全部 land 之后，调度者做了一次**不改 ref、不改工作区、不产生合并 commit** 的并入预演，
按**下一轮 tick 的真实顺序**逐条合并
（base → 16.5.2.2 → 16.6.2.1 → 17.7.1.1 → 17.7.2.1 → **17.5.2.1 修复车道**）。
中间树用 `git commit-tree` 串成链（`merge-tree --write-tree` 产出的是 tree，不是 commit，不能直接当下一轮的 ours）。

| 检查项 | 结果 |
| --- | --- |
| 合并是否报冲突 | **否**，**五步全 exit 0** |
| `CONFLICT_KEYS` | **五步均未出现** ⇒ 契约按「导出类型名 / paths / schemas 名」求并成功 |
| `services[]` 元素数 | base **173** → 合并后 **179**（4 条车道各 +1 ＋ 修复车道 +2，精确相等） |
| **从 base 掉落的元素** | **0 个** —— api-proxy 差异 **389 行新增 / 0 行删除**（纯插入） |
| **重名 `export type`** | `channel.ts` **0 个**、`identity.ts` **0 个** ⇒ batch-56 抓过的「契约静默串味」形态**未复现** |
| 迁移文件 | `V321` / `V322` / `V323` / `V324` **各 1 份，无重复无缺失**（修复车道无迁移） |
| 台账行 | **465 行**、**重复 ID 0 个**；16.5.2.2 / 16.6.2.1 / 17.7.1.1 / 17.7.2.1 / 17.5.2.1 **均为「已交付」** |
| 契约 type 数 | `channel.ts` **1682 → 1738**（+56）；`identity.ts` **158 → 189**（+31） |

⇒ **下一轮 tick 可以直接走 `merge-ready`，不必再做并入预演。**
唯一要补的是：并入后**实跑**修复车道那个 allowlist 回归测试（它在预演阶段已单独验过 6/6 通过）。

⚠️ **修复车道（17.5.2.1）未纳入本次预演** —— 它此刻仍在跑，尚未 land。
⚠️ **修复车道的 2 处实际插入锚点**（它原本被指派 3 处，实际只用了 2 处，因为 ③ ④ 被证伪后不需要新增）：
`// 14.2.2.1 预算分配（channel 服务）` 之前（identity/dict）与 `// 10.7.1.1 退货接收与处置决策` 之前（catalog/lifecycle）。
两者与上表 4 条车道的锚点**互不重叠**（最小间距 200+ 行），
**并入预演五步全 exit 0、无 `CONFLICT_KEYS`**，`services[]` 元素数 173 → **179**。

### ⚠️ 附二：台账证据格的「竖线截断」陷阱（17.7.1.1 车道实测踩到，值得全仓记住）

`module-progress.md` 的表格列由 `sync.mjs` 按 `[^|\n]*` 解析。
⇒ **证据格里只要出现一个竖线 `|`，那一行的证据就会被静默截断到第一个内部竖线处**，
而 `sync.mjs` **仍然报「同步成功」、`sync.mjs --check` 仍然通过**。

**判据**：`--check` 通过 ≠ 证据完整。写完证据后**必须另看行尾**：
```bash
grep -E '^\| 17\.7\.1\.1 ' docs/product/capability-blueprint/module-progress.md | tail -c 200
```
确认证据没有被截断，且**证据格内不含任何竖线**。

⇒ 这条陷阱对本流水线是**系统性**的：任何车道只要在证据里写了 `a|b` 形式的枚举（例如权限码列表），
就会静默丢掉后半段 —— 而台账是交付记录的唯一权威副本。
