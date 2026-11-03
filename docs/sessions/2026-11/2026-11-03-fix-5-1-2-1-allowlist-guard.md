# 2026-11-03: 5.1.2.1 白名单守卫测试对齐已交付兄弟模块

车道 `feat/5-1-2-1`（5.1.2.1 优先级与冲突检测，L3 锚点 5.1.2，阶段 D6）。

## 摘要

本轮**只修断言**，不碰后端、不碰契约、**不改 `apps/web/src/lib/api-proxy.ts`**。
上一轮全量 L2 验证前端 10266 例里红 3 例，取证结论是**守卫测试的期望过期**（白名单是对的，
断言停留在「兄弟模块未交付」的旧事实），而不是业务缺陷。本轮把两条**已交付**的形状从
「刻意不放行 ⇒ 必须 404」挪进「放行 ⇒ 200」，并保留全部**确实不存在**的形状作为负向样本。

## 决策 / 结论

1. **不改 `api-proxy.ts`**：白名单放行的形状全部正确。为了让测试变绿去动白名单 =
   把真事故改成假绿。
2. **过期断言只能正向化，不能删掉**：
   - `catalog/price-lists/lists/{uuid}/conflicts`（某张价目表的冲突明细）
   - `catalog/price-lists/conflicts`（全租户冲突概览）

   两条由 **5.1.2.1（本车道）** 交付 ⇒ 从负向样本改成**正向样本**（被放行 ⇒ 状态码不是 404）。
3. **`catalog/price-lists/lists/{uuid}/items` 属 5.1.1.2**（价目项批量维护，已并入集成头），
   由 5.1.1.2 自己那条独立的 `services[]` 元素放行 ⇒ 本车道（5.1.2.1）测试里那条
   「刻意不放行 ⇒ 404」是**过期断言**（实测 503：白名单放行、代理转发后上游不可达，
   测试里 fetch 未打桩）。处置：**移出负向样本 + 补一条正向断言**（钉住「它已被放行」）。
   替换上来的负向样本是 `…/items/batch` —— 三条正则零命中（5.1.1.2 的批量形状叫
   `preview` / `apply`）⇒ 真 404。
4. **负向样本继续守住「后端根本不存在的映射」**（放行它界面会以为功能已接通，比 404 更糟）：
   `…/items/{非 UUID 段}`、`…/items/batch`、`lists/not-a-uuid/priority`、
   裸 `price-lists/priority`、`…/priority/extra`、`…/conflicts/extra`、
   `…/resolve/extra`、裸 `price-lists`、`price-lists/items`、`lists/not-a-uuid`。
5. **断言双向**：每个守卫文件都同时保留
   （a）**源码反向抽取**的真实调用点断言（`price-list-model.ts` / `price-list-priority-model.ts`
   + `price-list-priority-workspace.tsx` / `packages/api-contracts/src/catalog.openapi.json`），
   与
   （b）**兄弟车道已交付路由未被挤掉**的正向清单。
   只写死一份与白名单同源的清单 = 恒真断言，禁止。

## 涉及文件

| 文件 | 改动 |
| --- | --- |
| `apps/web/src/app/api/catalog/price-list-allowlist.test.ts` | 两条 conflicts 形状从负向样本移进「兄弟车道正向清单」；文件头补修复说明 |
| `apps/web/src/app/api/catalog/price-list-item-allowlist.test.ts` | `lists/{uuid}/conflicts` 从负向样本移进正向清单；文件头「最要紧的一条负向样本」那段改写 |
| `apps/web/src/app/api/trade/price-list-priority-allowlist.test.ts` | `…/items` 从负向样本移出、**补一条正向断言**（钉住 5.1.1.2 已交付）；负向样本换成 `…/items/batch`；文件头「5.1.1.2 尚未交付」改对 |
| `docs/product/capability-blueprint/module-progress.md` | 只在 **5.1.2.1 那一行**的证据格**开头**补一句修复说明（原文一字未删）。**未跑 `sync.mjs`**（会与并行车道的台账改动整表冲突） |

## 关键 trade-off / 讨论

- **为什么不改 `api-proxy.ts` 里的过期注释**：该文件是多车道共用的**累积型**热文件
  （本批 4 条车道各占一个相距万行的锚点），其中 `price-lists` 那两条元素分别归 5.1.1.1 /
  5.1.1.2 车道所有。本轮任务书把范围限定在 3 个测试文件，且改共享文件有并入冲突风险 ⇒
  只记为后续待办（见下）。
- **为什么不把负向样本整条换成正向**：守卫的价值恰恰在「白名单放行了后端根本不存在的映射」，
  删守卫 / 改成恒真 = 把这个守卫拆了。负向样本只做**逐条替换**，一条不删。
- **台账为什么不跑 `progress:sync`**：`sync.mjs` 会重排整张表，与并行车道的台账改动整表冲突；
  本次按任务书用定点文本编辑。

## 后续待办

1. **调度者**：并入集成头（本车道已 `land`，**未 merge、未 push**）。
2. **`apps/web/src/lib/api-proxy.ts` 的注释已过期**（根因：守卫断言就是照着这两处注释写的）：
   - 5.1.1.1 那条元素（约 L2805-2808）写着「`price-lists/lists/{uuid}/items` —— 后端没有这个端点」
     与「`price-lists/lists/{uuid}/conflicts` —— 本模块不实现冲突判定 ⇒ 后端没有这个端点」——
     两条事实都已过期（分别由 5.1.1.2 / 5.1.2.1 交付）。
   - 5.1.2.1 那条元素（约 L2847）写着「`price-lists/lists/{uuid}/items` —— 价目项是 5.1.1.2 的
     写入所有权，**未交付** ⇒ 不放行」—— 「本条目不放行」仍为真，但「未交付」已过期。

   **归 5.1.1.1 / 5.1.1.2 车道或调度者处置**，本车道未改（共享热文件 + 锚点纪律）。
3. **`pnpm progress:check` 在基线上本来就是红的**（与本轮改动无关，已用未改动的 HEAD 台账复现）：
   `✗ 台账与蓝图不一致`。根因（上一轮取证）：① 台账经 prettier 格式化（空行被折叠），
   而 `sync.mjs` 渲染结果带空行 ⇒ 整文件不等；② 总览计数与行内实际状态漂移。
   本轮按任务书**没有**跑 `sync.mjs` 重生成。

## 关联

- 蓝图：`docs/product/capability-blueprint/module-progress.md`（5.1.2.1 行）
- 白名单：`apps/web/src/lib/api-proxy.ts`（`price-lists` 三条 `services[]` 元素）
- 同轮前置日志（同一批 L2 红修复的另一车道视角）：
  `docs/sessions/2026-11/2026-11-02-batch-134-l2-allowlist-fix.md`
