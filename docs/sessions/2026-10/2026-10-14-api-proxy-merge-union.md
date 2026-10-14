# 2026-10-14 · api-proxy 四车道并入求并（10.2.2.1 / 9.6.1.3 / 9.1.1.3 / 9.7.1.1）

## 一句话

4 条卡在 `apps/web/src/lib/api-proxy.ts` 上的车道已全部并入
`fix/merge-lanes-api-proxy`（基线 `integration/module-dev` @ `18322418`），
求并后 **warehouse 分组 31 → 32、trade 分组 9 → 12**，
用车道各自契约里的 path 当用例集回归，**92 条 path 全部放行，0 条回归**。

> ⚠️ 合并会让集成分支**变红**：车道 9.7.1.1 自带的 nav 改动让
> `packages/ui` 两个导航测试失败（详见第五节）。这不是本次合并引入的，
> 但合过去就是红的，需要调度器决定是退回 9.7.1.1 还是单独修。

## 一、冲突根因：单行顶层 alternation

`apps/web/src/lib/api-proxy.ts` 的 `services[]` 里，**每个服务是一段单行正则**，
按顶层 alternation 组织。warehouse 分组整个塞在**同一个物理行**上：

```ts
      /^(inventory\/...|packing-orders(?:\/...)?|shipment-handovers(?:\/...)?)$/,
```

于是**只要同一批里有两条车道落到同一个服务，就必然 git 判冲突**。
这是结构性的、确定性的，不是时序偶发 —— **retry 永远不会自愈**，
下轮集成分支只会有更多分支、并进更多车道。

本轮 19:00 批次派了 4 条车道，命中两次（warehouse × 2、trade × 3）：

| 车道                       | 分组      | 分支点   | 状态                  |
| -------------------------- | --------- | -------- | --------------------- |
| 8.3.4.1 发货交接           | warehouse | f002cc8f | 早前已并入 `bc23ecb3` |
| 9.4.1.1 待发货             | trade     | f002cc8f | 早前已并入 `b168414c` |
| **10.2.2.1 包裹详情**      | warehouse | f002cc8f | 本轮并入 `30849652`   |
| **9.6.1.3 代发履约跟踪**   | trade     | f002cc8f | 本轮并入 `ab2359ee`   |
| **9.1.1.3 订单变更与取消** | trade     | cbd78408 | 本轮并入 `27bed2e0`   |
| **9.7.1.1 批发订单与账期** | trade     | cbd78408 | 本轮并入 `10ff5291`   |

## 二、4 条车道各自的路由增量

求并方式是**按分支求并集**，不是手工重排：
`新增分支 = 车道契约相对 merge-base 多出来的分支`，追加到集成分支既有分支之后，
既有分支逐字不动。

| 车道     | 分组      | 新增分支（原文）                                                                                                                                                                                                                                                                                              | 分组分支数 |
| -------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| 10.2.2.1 | warehouse | `parcels(?:\/capabilities\|\/[0-9a-f-]+)`                                                                                                                                                                                                                                                                     | 31 → 32    |
| 9.6.1.3  | trade     | `fulfillment\/dropship-tracking\/(?:capabilities\|policy\|events\|exceptions\/evaluate\|exceptions(?:\/[0-9a-f-]+\/(?:dispose\|dismiss))?\|dispositions\/[0-9a-f-]+\/status\|trackings(?:\/[0-9a-f-]+(?:\/(?:supplier-leg\|own-leg))?)?\|tasks(?:\/[0-9a-f-]+\/(?:claim\|complete))?)`                        | 9 → 10     |
| 9.1.1.3  | trade     | `sales-orders\/change\/(?:capabilities\|platform-rules(?:\/[0-9a-f-]+)?\|fulfillment-progress\|requests(?:\/[0-9a-f-]+(?:\/(?:status\|writeback-tasks))?)?\|cancellations(?:\/[0-9a-f-]+(?:\/(?:status\|warehouse-tasks(?:\/[0-9a-f-]+\/status)?\|writeback-tasks))?)?\|writeback-tasks\/[0-9a-f-]+\/status)` | 10 → 11    |
| 9.7.1.1  | trade     | `b2b-orders(?:\/(?:capabilities\|receivables\|customer-terms\|price-quote\|price-books(?:\/[0-9a-f-]+)?\|[0-9a-f-]+(?:\/(?:confirm\|shipments\|invoice-requests))?))?`                                                                                                                                        | 11 → 12    |

**判别顺序逐条核对过，没有踩「宽通配吃掉具体分支」的坑：**

- `parcels`：`capabilities` 排在 `[0-9a-f-]+` 之前。capabilities 的 c/a/p 里
  `p` 落在 `[0-9a-f-]` 之外，顺序反了会凭空放行一个「id=capabilities 的包裹」。
- `sales-orders\/change\/(...)`：`change` 里没有 `[0-9a-f-]` 的字符，
  既有 `sales-orders\/(...)` 的 uuid 段咬不到它，与
  `sales-orders\/list\/(...)` / `sales-orders\/detail\/(...)` 也不互吃。
- `fulfillment\/dropship-tracking\/(...)` 与既有
  `fulfillment\/dropship\/(...)`、`fulfillment\/dropship-orders\/(...)`
  靠首段的连字符区分（`dropship` 后必须跟 `/`），互不吃掉。
- `b2b-orders(...)`：首段字面量，与 `fulfillment\/`、`sales-orders\/` 不冲突；
  字面量子路径排在 `{uuid}` 分支之前。

**锚点完整性**：外层 `^` / `$` 仍包住完整分组，**没有**被拆成
`^a|b|c$`（那实际是 `(^a)|b|(c$)`，只锚定首尾两项，中间分支静默失效且不报错）。
求并脚本里有一条硬断言会拒绝锚点松掉的正则。

## 三、验证方式与结果

### 用例集 = 各车道自己生成的 OpenAPI 契约里的 path

不看正则、不自己重写一份正则去比，而是从
`packages/api-contracts/src/<service>.openapi.json` 的 `paths` 取用例，
把 `{param}` 换成真实 UUID，再交给**从源码里切出来的真实 `services[]` 正则**判定。

三个坑都避开了：

1. 切单行正则用 `indexOf('/^')` + 定位未转义的 `$/`，**没有**用
   `/\/\^\((.*)\)\$\//` —— 以 `(?:` 开头的段里那个 `(` 会被模式里的 `\(` 吃掉，
   拼成 `^?:` = `Nothing to repeat`。
2. `execFileSync` 显式放大 `maxBuffer`（warehouse.openapi.json > 1MB，
   默认 1MB 会截断成非法 JSON，看起来像「全挂了」）。
3. pattern 是对**去掉了服务前缀**的路径串求值的，**没有前导斜杠**
   （`resolve()` 里是 `path.slice(prefix.length)`）。
   多留一个 `/` 会让所有 path 全部判成未放行。

**结果（4 条车道各多少条 path）**：

| 车道     | 服务      | 契约 path 数 | 放行        |
| -------- | --------- | ------------ | ----------- |
| 10.2.2.1 | warehouse | 29           | **29 / 29** |
| 9.6.1.3  | trade     | 37           | **37 / 37** |
| 9.1.1.3  | trade     | 15           | **15 / 15** |
| 9.7.1.1  | trade     | 11           | **11 / 11** |

并入前单独跑 10.2.2.1 的同一用例集：`warehouse/parcels/{id}` 与
`warehouse/parcels/capabilities` 两条判为未放行 —— 正是这次求并要补的缺口。

### 并入前后全量回归对比

| 服务      | 并入前    | 并入后        |
| --------- | --------- | ------------- |
| identity  | 82 / 99   | 82 / 99       |
| catalog   | 299 / 339 | 299 / 339     |
| channel   | 46 / 49   | 46 / 49       |
| warehouse | 439 / 443 | **441 / 445** |
| trade     | 75 / 75   | **116 / 116** |
| crm       | 11 / 11   | 11 / 11       |

未放行集合做差集：**新增 0 条**（`comm -13` 空）。warehouse / trade 的
「未放行绝对数」也没有变化（各 4 / 0），只是契约新增的 path 全部被放行。

### 全量 vitest（并入前后各跑一次）

|                                              | 测试文件           | 测试用例           |
| -------------------------------------------- | ------------------ | ------------------ |
| 并入前 `integration/module-dev` @ `18322418` | 146 全绿           | 2701 全绿          |
| 并入后 `fix/merge-lanes-api-proxy`           | 153 个，**2 失败** | 2796 个，2794 通过 |

失败的 2 个是 `packages/ui/src/lib/nav-routes.test.ts` 与 `task-pages.test.ts`，
详见第五节。

### typecheck

- `apps/web`（含 api-proxy.ts）：`tsc --noEmit` **exit 0**
- `packages/ui`：`tsc --noEmit` **exit 0**

## 四、既有的缺口（不是本次引入）

并入前后都放行不了、**本次合并没有碰**的 path，共 **64 条**：

| 服务      | 条数 | 形态                                                                                             |
| --------- | ---- | ------------------------------------------------------------------------------------------------ |
| catalog   | 40   | 散落多处                                                                                         |
| identity  | 16   | 主要是 `dict-items` / `role-assignments` 整族 + `operation-log/purge`                            |
| warehouse | 4    | `inventory/availability`、`inventory/consignments/{id}/sales`、`inventory/printing/devices/{id}` |
| channel   | 3    | `platforms/{id}/enums` 族 + `sync-failures/{id}/replay`                                          |
| internal  | 1    | `internal/access`                                                                                |

这些是本任务范围外的既成缺口，建议单独立项，不要混进并入批次。

## 五、⚠️ 合并会让集成分支变红

车道 9.7.1.1 改了 `packages/ui/src/lib/nav-config.ts`（+6 行），
在「运营」系统下加了一个顶层 item：

```ts
{ label: '批发订单', href: '/orders/b2b', icon: Package, description: 'B2B 账期 / 阶梯价 / 起订量' },
```

而 `SYSTEMS` 的 `children` 是**从 `IMPLEMENTED_TASK_PAGES` 按 domain 推导**的：

```ts
children: IMPLEMENTED_TASK_PAGES.filter(
  (page) => page.domain === item.href.slice(1) && !isAccountDomain(page.domain),
),
```

`item.href.slice(1)` 是 `orders/b2b`，但 `task-pages.ts` 里**没有** `orders/b2b`
这个 domain —— 该车道只加了 nav 入口，没加对应的 TASK_PAGES 条目。
于是 `children` 为空数组，撞上两条断言。

**归属已确证**：在车道自己的分支 `feat/9-7-1-1` 上单独跑，同样是
`2 failed | 136 passed`，失败的是同两个文件。所以这是**车道 9.7.1.1 自带的缺陷**，
本次合并只是原样带过来 —— 我没有在合并里改它，也没有为了让测试变绿去动断言。

修法（属于 9.7.1.1 的范围，不在本次并入任务内）：给
`packages/ui/src/lib/task-pages.ts` 补一条 `domain === 'orders/b2b'` 的任务页，
或把 nav 入口降级成 `订单中心` 的子页。

**调度器需要在这里决策**：直接合入则 `integration/module-dev` 变红；
先退回 9.7.1.1 修好再合则保持绿。

## 六、仍然存在的结构性风险（本轮只是手工解开，没有解决）

**1. 同一服务分组被多写者并发追加，这件事本身没被解决。**
`api-proxy.ts` 的每个服务分组仍是**一个物理行里的顶层 alternation**。
只要同一批里有两条车道落到同一个服务，就必然冲突，retry 不会自愈。
本轮 4 条车道是**手工**求并的，下一轮还会原样复发。可选方向：

- 把每个服务分组**拆成多个 pattern 元素**（`services[]` 是数组，
  按前缀再切一个 `prefix: 'warehouse/'` + 更窄的 `pattern`），
  让「一个服务一个 alternation」不再物理相邻；
- 或给 api-proxy.ts 也配一个**按路径前缀求并**的 merge driver
  （现有 `merge.contract-union` 只覆盖 `packages/api-contracts/**` 与台账）。

**2. 并入顺序会影响冲突面。**
9.1.1.3 与 9.7.1.1 的分支点是 cbd78408，比 9.6.1.3 / 10.2.2.1 的 f002cc8f 更早，
它们看到的 trade 分支更少、冲突面更大。批次越老的车道越往后并，
撞行越狠、解冲突时越容易把新分支挤掉。

**3. 车道从旧基线重新生成契约，会污染别的服务的契约文件。**
9.1.1.3 从 cbd78408 重新生成了 `warehouse.openapi.json`（整文件 22737 行全变），
导致 merge driver 报出 61 个 warehouse path + 1 个 trade path 的 `CONFLICT_KEYS`。
本轮逐键核对后确认**合并内容与合并前逐字相同、键集无增减**，没有回退 ——
但这是运气好，不是机制保证。相关 backlog：
`docs/backlog/2026-10/2026-10-10-contract-flat-overwrite-and-nav-gaps.md`。

**4. 白名单只在浏览器端生效，`pnpm test` 直连服务层天然测不出漏登记。**
漏登记的路径在浏览器里静默 404，typecheck / lint / vitest 全绿。
本轮的契约 path 回归是外部脚本，不是仓库内常驻测试 ——
建议把「契约 path ⊆ proxy 白名单」做成一个常驻的仓库内测试，
否则下一次合并仍然只能靠人记得跑。

## 七、本次的环境偏差（需要记录）

任务书假定工作区 `/Users/tommy/Project/starcon/.worktrees/fix-api-proxy-merge`
与分支 `fix/merge-lanes-api-proxy` 已备好，**实际两者都不存在**
（`git worktree list` 里没有该 worktree，`git rev-parse fix/merge-lanes-api-proxy` 报
`Needed a single revision`）。

在「不得执行 `git worktree add/remove`、不得改主工作区任何文件」的约束下，
本次改用纯 plumbing 路线完成，**没有创建 worktree、没有动主工作区文件**：

- 分支 `fix/merge-lanes-api-proxy` 从当时的 `integration/module-dev`（`18322418`）建出；
- 合并用 `git merge-tree --write-tree` + `GIT_INDEX_FILE` 临时索引 +
  `git commit-tree` 完成，逐条一个 merge commit；
- 求并后的 `api-proxy.ts` 由脚本**计算**得出（不是手打），并带三条硬断言：
  不丢既有分支、不丢车道分支、无重复分支；把注释块摘掉、正则行还原后
  必须与合并前的文件**逐字相同**，否则拒绝写盘；
- 临时产物（脚本、导出树、报告）都在
  `.worktrees/fix-api-proxy-merge/.tmp/`，已被 `.gitignore` 覆盖。

另外注意：`integration/module-dev` 在本次作业期间**前进过一次**
（`b168414c` → `18322418`，一个 Java 测试确定性修复，不碰 `api-proxy.ts`）。
本分支的基线就是前进后的 `18322418`，api-proxy.ts 基线未受影响。
