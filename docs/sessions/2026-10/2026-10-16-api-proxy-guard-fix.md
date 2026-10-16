# 2026-10-16 api-proxy 白名单守卫修复（并入后 11 个假红）

分支 `fix/api-proxy-guard-union`，基线集成头 `6ca92f11`。**纯测试修复**：`apps/web/src/lib/api-proxy.ts` 一行未改。

## 背景

`apps/web/src/lib/api-proxy.ts` 是累积型白名单：每个车道往 `services[]` 里加一条
`{ prefix, env, fallback, pattern }`。白名单**只在浏览器端生效** —— 前端调了但没登记
= 浏览器静默 404，而 typecheck / lint / vitest / Playwright 全绿。仓库里那批
`*-api-proxy.test.ts` 守卫用 `readFileSync` 把该文件当纯文本解析来提前发现这件事。

## 决策一：守卫镜像运行时语义，改守卫不改 api-proxy.ts

**根因**：5 个守卫的抽取器只取**第一条** `prefix: 'X'` 元素
（`source.search(/^\s*prefix: 'trade\/'/m)` → `block.indexOf('/^')`），
然后拿那**一条**正则去断言**所有**该前缀的路径。

9.3.1.2 合单合法地新增了一条 `prefix: 'trade/'`（`fulfillment/consolidation/...`）
并插在既有那条大元素**之前**（该车道的刻意锚点约定）。于是守卫抽到的是合单那条窄正则，
拿它去判 `sales-orders/detail/...` / `processing/orders/.../bom-binding` ⇒ 10 个假红。

**为什么不改 `api-proxy.ts` 的元素顺序**：运行时 `resolve()`（api-proxy.ts:1057）是
逐条 fallthrough —— 前缀不匹配就 `continue`，正则不匹配也继续试下一条。所以新元素插在
前面**不会遮蔽**后面的既有规则，实测已证实（见下「验证」）。改顺序等于推翻合单车道的
刻意锚点选择，并把 git 并入冲突风险加回来；而这里根本不存在遮蔽问题。

**修法**：抽取器改为收集同一前缀的**全部**元素正则，判定放行 = **任一条**匹配。
与 `resolve()` 完全同构 ⇒ 将来加第 3、第 4 条同前缀元素也不会再假红。

### 关键实现细节：每条元素单独定界

元素边界 = 下一个元素的 `prefix:` 行（或数组结尾 `];`）。
不定界的话，从本元素 `prefix:` 往后找第一个 `/^` 找到的是**下一条元素**的正则 ——
这正是那 10 个假红的确切机制。

### 权衡

- **没有抽公共 helper 模块**：`Hard constraint` 要求只改 `*.test.ts`。且这 5 个文件
  本来就各自持有一份抽取器副本，**沿用既有约定**（per-file 副本）改动最小、评审面最小。
- **反向断言没有被削弱**：`some()` 返回 false 意味着**全部**元素都不匹配，
  而不是一个来自别的元素的窄正则碰巧没匹配上。**这是选 `some()` 而不是「拼一个大正则」的原因** ——
  拼接会丢掉「逐条 fallthrough」这个语义，且让反向判定的来源不可追。
- 顺带修掉 3 个**尚未触发**的同款隐患（`warehouse/` ×2、`identity/` ×1，
  这些前缀目前各只有 1 条元素，但用的是更脆的裸 `indexOf`，连注释都可能命中）。

## 决策二：已知缺陷的断言改为回归守卫，而不是删掉

`api-proxy-union.test.ts` 里 `CH10_FREIGHT_KNOWN_BROKEN` 断言
`channel/freight/quotes/{uuid}` 返回 404，标注为「10.1.2.1 的既有缺陷」。
10.1.2.2（渠道比价，commit `3a4a1710`）顺手把这个洞补上了：给 `freight` 正则加了
嵌套分支 `\/quotes\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`，该路径现在返回 200
⇒ 假红。

**修法**：改名 `CH10_FREIGHT_FIXED_IN_10_1_2_2`，断言改为**必须放行**，
注释保留「它曾经为什么是坏的」+「为什么现在必须保持好的」。
「曾经坏过」恰恰是需要钉住「现在必须好」的理由，删掉用例等于丢掉这条信息。

同时把原注释块里 (b) 裸 `freight` 的成因迁到它自己那条用例上（那条缺陷 10.1.2.2 **没**修）。

## 文件清单

| 文件                                                    | 改了什么                                                                                                                                              |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `apps/web/src/lib/order-overview-api-proxy.test.ts`     | `tradePattern()` → `tradePatterns()`（全量 + 逐元素定界），`resolveOk` 改 `some()`                                                                    |
| `apps/web/src/lib/bom-binding-api-proxy.test.ts`        | 同上                                                                                                                                                  |
| `apps/web/src/lib/api-proxy.test.ts`                    | `warehousePattern()` → `warehousePatterns()`，裸 `indexOf` → 定界扫描；顺带修掉该文件一处**既有 U+FFFD 乱码**（`跳过` → `跳<?>`，在我重写的注释行内） |
| `apps/web/src/lib/inventory-sellable-api-proxy.test.ts` | 同上（`allows` 改 `some()`）                                                                                                                          |
| `apps/web/src/lib/ledger-book-api-proxy.test.ts`        | `identityPattern()` → `identityPatterns()`，`resolveOk` 改 `some()`                                                                                   |
| `apps/web/src/lib/api-proxy-union.test.ts`              | 决策二：freight 断言翻转 + 常量改名 + 历史注释                                                                                                        |

`apps/web/src/lib/customs-attribute-api-proxy.test.ts` 与 `app/api/**/*-allowlist.test.ts`
**不需要改**：它们 import 真正的 `proxy` handler 判 HTTP 状态码，天然与元素顺序无关。

## 验证

- 三个守卫文件：`253 passed / 0 failed`（原 `242 passed / 11 failed`）
- 全量：`185 files / 3420 passed / 0 failed`（基线 3409 passed / 11 failed，3409+11=3420，一条没少）
- `pnpm typecheck` 3/3 successful；`pnpm lint` 3/3 successful（6 个改动文件零告警）
- **同构探针**（`.tmp/isomorphism.test.ts`，跑完即删）：对 39 条 trade 路径
  （19 正向 + 20 反向）比对「新守卫判定」vs「真 proxy handler 的 HTTP 状态」：
  **guard vs real DIFFER = 0，guard vs expected = 0**。抽出 trade/ 元素 = 2 条（证明确实是并集）。
- **反向对照实验**：`git stash` 回退 ⇒ `11 failed / 242 passed`；恢复 ⇒ `253 passed`。
  变绿是因为守卫修对了，不是因为断言被放松。

## 后续待办

1. `channel/` 目前有 6 条元素、`trade/` 2 条。**任何车道再往这两组加元素都不会再假红**；
   但反过来，如果哪个车道改了 `pattern` 的 alternation 顺序，守卫仍按真实语义判，不会掩盖问题。
2. 10.1.2.1 的**另一条**已知缺陷（裸 `freight` 实际被放行，与其注释矛盾）仍开着，
   归属 10.1.2.1，本车道未动。
3. 5 个守卫仍各自持有一份抽取器副本。哪天想收敛成公共 helper，
   需要先允许建 `apps/web/src/lib/api-proxy-guard.ts`（超出「只改 *.test.ts」的约束）。
