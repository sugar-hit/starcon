# 2026-10-20: 4.5.2.1 跟卖监控 allowlist 负向断言修复

> 修复车道 `fix/4-5-2-1-allowlist-negative`，worktree `.worktrees/4.5.2.1-fix`。
> 这是 **batch-32** 交付 4 条车道后，L2 批次闸抓到的**真实跨车道缺陷**。纯前端测试修复，
> **零后端改动、零迁移、零契约改动、零台账改动**。

## 摘要

4.5.2.1 跟卖监控的 api-proxy 白名单测试里，「未交付 / 不该存在的形状仍然被拒」这条**负向断言**
把兄弟车道 4.5.1.2 关键词排名的前缀 `listing-keyword-rankings` 当成了「未交付形状」要求 404。
但 4.5.1.2 **在同一批里已经交付** —— `listing-keyword-rankings` 现在是**已登记的合法前缀**。
⇒ 并入后 L2 全量前端测试必然假红：
`AssertionError: channel/listing-keyword-rankings 被放行了，但后端没有这个形状: expected 503 to be 404`。
（503 = 请求被转发到未启动的 channel 后端；404 才是白名单拒绝。）

## 根因

**白名单本身是对的，错的是测试断言。** `apps/web/src/lib/api-proxy.ts` 里
`listing-keyword-rankings/` 是 4.5.1.2 合法新增的一条 `services[]` 元素，**不该动**。

真正违反的是流水线协议里的一条硬规矩：

> **负向断言（未登记形状必须 404）只能用自己前缀下的形状。**
> 拿兄弟车道的前缀当负向样本，等于把「那条车道还没交付」写死在断言里 ——
> 一旦那条车道在同批或后续批次交付，断言就**必然失效**，而且是在最贵的时刻（批次合并闸）失效。

这个缺陷有**两重**危害，都不是「改一行就好」：

1. **假红**：本批已交付 ⇒ 断言与白名单互相矛盾 ⇒ 合并闸红。
2. **假绿（更危险）**：如果 4.5.1.2 那条白名单被**取错一侧**而静默删掉，负向断言反而会绿 ——
   于是「已交付的路由被误删」这个真事故被测试**盖住**。断言方向搞反时，测试会替错误背书。

## 改了什么

只改一个文件的一处：`apps/web/src/app/api/channel/listing-hijackers-allowlist.test.ts`
的「未交付 / 不该存在的形状仍然被拒」用例里的路径清单。

| 动作 | 内容 | 依据 |
| --- | --- | --- |
| **删** | `'channel/listing-keyword-rankings',` 及其上一行注释「🔴 4.5.1.2 关键词排名是**别的模块**的前缀，且未交付」 | 4.5.1.2 已交付 ⇒ 那句「且未交付」已成**假注释**，必须一起删，不能留一句假话在代码里 |
| **补** | `${base}/brand-protection-enforcement` | 本模块**自己前缀下**确实不存在的形状（见下方「补的形状怎么选的」） |
| **补** | 清单首条注释追加一条协议提醒：负向断言只能用自己前缀下的形状，兄弟车道前缀在同批并入后就是合法前缀 | 让同一个坑**不能再被踩第二次** |

**补的形状怎么选的**（先 grep 确认不存在，再写进断言）：

- 契约 `packages/api-contracts/src/channel.openapi.json` 里 `/channel/listing-hijackers/*` 只有 8 条 path
  （`capabilities` / `filter-options` / `watches` / `watches/{watchId}` / `records` / `alerts` /
  `alerts/{alertId}/acknowledge` / `comparison`），**没有** `brand-protection-enforcement`。
- 后端 `ListingHijackerController`（`@RequestMapping("/channel/listing-hijackers")`）也只有对应的
  8 组端点，同样**没有**它。
- 语义上也对得上：服务层 `capabilities()` 里的能力维度代码字面就是
  **`brand_protection_enforcement`**，`available=false` + `ownerModule=16 合规与风险`
  —— 处置动作在平台侧完成，本模块只负责发现与提醒，**系统不提供也不代为发起**这些动作。

**没动的**（刻意保持最小改动）：

- `apps/web/src/lib/api-proxy.ts`（白名单本身是对的）
- 同文件另外三个用例：契约真实 path 放行 / 写动作真的转发到 8083 / 不误伤其他车道已登记的分支
- 任何后端代码、迁移、契约、台账

## 验证

全部在 `.worktrees/4.5.2.1-fix` 下执行：

```
$ pnpm vitest run apps/web/src/app/api/channel/listing-hijackers-allowlist.test.ts
 Test Files  1 passed (1)
      Tests  4 passed (4)
   Duration  237ms

$ pnpm vitest run apps/web/src/app/api          # 全量 allowlist 回归（共享白名单是累积型文件，必须跑全量）
 Test Files  94 passed (94)
      Tests  567 passed (567)
   Duration  2.63s

$ pnpm typecheck
 Tasks:    3 successful, 3 total
   Time:    22.366s
```

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 补哪一条负向形状 | `brand-protection-enforcement` | 与服务层能力维度代码**字面对齐**，且是「最可能被误登记」的形状（听上去像个合理端点）。补一条**同前缀下**的，而不是再找一条别的车道前缀 —— 后者正是这次假红的成因 |
| 补几条 | **一条** | 覆盖强度不下降即可（清单仍有 6 条，覆盖「未声明动作 / uuid 子动作 ×3 / 未交付能力 ×3」四类）。多补只会让清单变长，不会提高断言强度 |
| 协议提醒写在哪 | 清单首条注释 | 就在**违规会发生的那一处**，而不是文件头 —— 文件头离错误决策太远 |
| 要不要顺手把 `api-proxy.ts` 里「4.5.1.2 关键词排名，未交付」那句注释也改掉 | **不改** | 那在**别的车道**的文件里（4.5.1.2 自己的条目），且本车道不许改白名单。已作为后续待报项交给父会话 |

## 后续待办

1. **给编排器/流水线加一条校验**：负向断言清单里出现**不以自己模块前缀开头**的路径 ⇒ 直接判红。
   本次的根因是**协议靠人记**，不落成机器检查就一定会复发。
2. **复查 `apps/web/src/lib/api-proxy.ts` 里 4.5.1.2 条目的注释**：其中「4.5.1.2 关键词排名，未交付」
   这类描述在本批交付后已过期（注释里的「未交付」指的是能力维度，不是车道状态）——
   **本车道未改**（不在本车道文件所有权内），交由 4.5.1.2 车道或编排者处理。
3. **全量复查已做，零同类问题**：`apps/web/src/app/api` 下 28 个 allowlist 测试文件的负向断言清单
   里，**没有**第二处「拿别的车道自己的前缀当未交付形状」的写法。
   （判据：负向清单里出现的是**另一条车道的 `base`/`MODULE` 前缀字面量**，且不等于本文件自己的前缀。
   同一脚本跑在**修复前**的文件上会精确报出 `listing-hijackers-allowlist.test.ts:146` 那一条 ——
   证明判据本身有效，不是空跑。修复后同一脚本输出 `(none)`。）
   注意：`listing-keyword-rankings-allowlist.test.ts` 里那条 `${base}/listing-hijackers`
   是**自己前缀下的子路径**，写法正确，不是同类问题。

## 关联

- `apps/web/src/app/api/channel/listing-hijackers-allowlist.test.ts` —— 本次修复的唯一文件
- `apps/web/src/lib/api-proxy.ts` —— 白名单（**未改**）
- `docs/sessions/2026-10/2026-10-20-listing-hijacker-monitor.md` —— 4.5.2.1 本模块的原始交付会话
- `docs/product/capability-blueprint/module-progress.md` —— 本模块台账（**未改**）
