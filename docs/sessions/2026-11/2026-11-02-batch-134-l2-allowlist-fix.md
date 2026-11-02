# 2026-11-02: batch-134 L2 红修复 —— 三条 allowlist 过期/错误断言

车道 `feat/5-1-2-1`（5.1.2.1 优先级与冲突检测）。本次**只修断言**，不重写模块、
不碰后端、不碰契约、**不改 `apps/web/src/lib/api-proxy.ts`**。

## 摘要

批次 L2 批量验证红在 3 条前端 allowlist 断言（后端 32 例绿，前端 typecheck / lint /
nextbuild / sync-check / format-gate 全绿）。取证结论：**错的是断言，不是白名单** ——
本模块的 api-proxy 条目放行的形状全部正确。

修复前实测（真跑 route handler 判 HTTP 状态码）：

| 文件 | 断言 | 实测 | 真相 |
| --- | --- | --- | --- |
| `catalog/price-list-allowlist.test.ts` | `catalog/price-lists/lists/{uuid}/conflicts` 应 404 | **200** | 该形状**由本模块交付**（冲突明细端点）⇒ 过期断言，应转正向样本 |
| `catalog/price-list-allowlist.test.ts` | `catalog/price-lists/conflicts` 应 404 | **200** | 同上（全租户冲突概览）⇒ 转正向样本 |
| `catalog/price-list-item-allowlist.test.ts` | `price-lists/lists/{uuid}/conflicts` 应 404 | **200** | 同上（5.1.1.2 当年写负向样本时本模块还没交付）⇒ 转正向样本 |
| `trade/price-list-priority-allowlist.test.ts` | `catalog/price-lists/lists/{uuid}/items` 应 404 | **503** | 写这条负向样本时误以为 5.1.1.2 未交付；实际 **5.1.1.2 已交付**，它自己的 `services[]` 元素放行 `…/items` ⇒ 断言本身错了 |

白名单实况（`apps/web/src/lib/api-proxy.ts`，**未改动**）：

- 5.1.1.1：`/^price\-lists(?:\/(?:capabilities|scope\-directory|coverage)|\/lists(?:\/[uuid])?)$/`
- 5.1.2.1（本模块）：`/^price\-lists(?:\/priority\/(?:capabilities|rules)|\/conflicts|\/resolve|\/lists\/[uuid]\/(?:priority|conflicts))$/`
- 5.1.1.2：`/^price\-lists(?:\/item\-capabilities|\/lists\/[uuid]\/items(?:\/(?:conflicts|resolve|preview|apply|export|import)|\/[uuid])?)$/`

⇒ `…/items/batch` 三条正则**都不命中**（5.1.1.2 的批量形状叫 preview / apply），
是唯一可替换进负向样本的「确实不存在」形状。

## 决策 / 结论

1. **不改 `api-proxy.ts`**：白名单是对的，为了让测试变绿去动白名单 = 把真事故改成假绿。
2. **过期断言只能正向化，不能删掉**：把两条 conflicts 形状从负向样本挪进**正向样本**，
   顺带把「兄弟车道已交付路由」那份清单当守门人 —— 下次并入取错一侧时，
   5.1.2.1 的路由会被这条钉住。
3. **换负向样本要挑「确实不存在」的形状**：`…/items` 由 5.1.1.2 放行（实际返回 503 = 转发
   后上游没接），换成 `…/items/batch`（三正则零命中 ⇒ 真 404）。
4. **注释同步改对**：凡是写「conflicts 是未交付 / 应该 404」的地方改成「由 5.1.2.1 交付」；
   凡是写「`lists/{id}/items` 已由 5.1.1.2 放行」的地方**保持不变**。

## 改了哪 3 个文件（逐条）

`apps/web/src/app/api/catalog/price-list-allowlist.test.ts`（5.1.1.1 的守门人）

- 文件头新增一段 🔧 修复说明（两条 conflicts 形状已由 5.1.2.1 交付 ⇒ 转正向样本）。
- 负向样本清单：**删** `catalog/price-lists/lists/${ID}/conflicts`、**删**
  `catalog/price-lists/conflicts`；**留** `catalog/price-lists/lists/${ID}/items/batch`、
  `catalog/price-lists`、`catalog/price-lists/items`、`.../lists/not-a-uuid`。
- 正向样本（「兄弟车道的白名单条目未被本车道挤掉或污染」清单）：**加**
  `catalog/price-lists/lists/${ID}/conflicts` 与 `catalog/price-lists/conflicts`。

`apps/web/src/app/api/catalog/price-list-item-allowlist.test.ts`（5.1.1.2 的守门人）

- 文件头「🔴 最要紧的一条负向样本：price-lists/lists/{id}/conflicts」那段改写为
  「已由 5.1.2.1 交付 ⇒ 现在是正向样本」。
- 负向样本清单：**删** `price-lists/lists/${ID}/conflicts`（其余负向样本
  `items/batch`、`items/{id}/extra`、`items/resolve/extra`、非 UUID 段等**不动**）。
- 正向样本（「兄弟车道已交付的 catalog 路由未被本车道挤掉或污染」清单）：**加**
  `price-lists/lists/${ID}/conflicts`。

`apps/web/src/app/api/trade/price-list-priority-allowlist.test.ts`（本模块自己的守门人）

- 文件头「价目项是 5.1.1.2 的写入所有权，**尚未交付**」改成「**5.1.1.2 已交付**，
  它自己的 `services[]` 元素放行 `…/items`；本模块不实现价目项，也不重复放行」。
- 负向样本清单：**删** `catalog/price-lists/lists/${ID}/items`、**加**
  `catalog/price-lists/lists/${ID}/items/batch`；
  **留** `catalog/price-lists/conflicts/extra`、`.../lists/not-a-uuid/priority`、
  `catalog/price-lists/priority`、`.../priority/extra`、`.../resolve/extra`。

台账 `docs/product/capability-blueprint/module-progress.md`：只改 **5.1.2.1 那一行**
（状态已是 `已交付`，保持）；证据格**原文全部保留**，只在**开头**补一句修复说明。
**用定点文本编辑**，没有跑 `sync.mjs` 重生成整张表（会与并行车道的台账改动整表冲突）。

## 验证结果（本车道 worktree 实跑）

- `pnpm vitest run <三条 allowlist>` → **Test Files 3 passed (3) / Tests 15 passed (15)**（修复前 3 failed）
- `pnpm vitest run apps/web/src/app/api`（**全量 allowlist 回归**）→
  **Test Files 295 passed (295) / Tests 1658 passed (1658)**
- `pnpm typecheck` → **3 successful / 3 total**（@starcon/web 真实执行，ui / config-tailwind 命中缓存）
- `pnpm exec prettier --check <三条测试文件>` → **All matched files use Prettier code style!**
- `pnpm exec prettier --check docs/product/capability-blueprint/module-progress.md` → 通过
- 后端：**未跑**（本修复不涉及后端，按任务书由调度者在集成头统一跑批次级验证）

## 后续待办

- **调度者**：并入集成头（本车道已 `land`，未 merge、未 push）。
- **调度者**：批次级验证时注意 `pnpm progress:check` 在**基线上本来就是红的** ——
  已用 HEAD 版台账复现（同一报错）。根因有二，与本次改动无关：
  ① 台账经 prettier 格式化（空行被折叠），而 `sync.mjs` 的渲染结果带空行 ⇒ 整文件不等；
  ② 总览计数（已交付 456 / 未开发 9）与行内实际状态（已交付 458 / 未开发 7）已经漂移。
  本次按任务书**没有**跑 `sync.mjs` 重生成。
