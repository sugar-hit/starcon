> **状态：Done（2026-11-03 销账）**
>
> - 判定类别：**已修复但条目未销账**
> - 修复：`apps/web/src/app/api/platform/[...path]/route.ts` 已补 `export const PUT = proxy;`；
>   新增方法守卫测试 `apps/web/src/app/api/platform/put-method-allowlist.test.ts`
>   （正向断言 5 条 PUT 路径被转发且方法确实是 PUT；反向断言非白名单路径仍 404 且 fetch 未被调用）。
> - 关键取证：`apps/web/src/lib/api-proxy.ts` 的放行判据是**路径白名单**、与方法无关，
>   而这 5 条 PUT 路径**本来就已经在白名单里** ⇒ 缺的只是出口的方法，路径边界一点没动。
>   与兄弟出口（channel / warehouse / catalog / trade / crm / finance）同构 —— platform 原本是唯一例外。
> - 提交：`d1d4b4a3`

# platform 代理路由不导出 PUT ⇒ platform 服务里所有 `@PutMapping` 端点在浏览器里调不到（405）

> 发现时间：2026-11-02 10:27（流水线 round 130，18.4.3.2 效果评估车道自查时发现）
> 严重度：**中** —— **后端端点本身是对的**（curl / 直连服务可用），
> 但**浏览器侧的前端工作区永远调不到它** ⇒ 页面上那个功能等于没交付，
> 而 typecheck / lint / vitest / 后端测试**全部全绿**（没有任何一层会红）。
> 归属：`apps/web/src/app/api/platform/[...path]/route.ts`（18.1.1.1 应用与凭证拥有的共享代理路由）

---

## 现象

共享代理路由 `apps/web/src/app/api/platform/[...path]/route.ts` **刻意只导出 `GET` / `POST` / `DELETE`**，
**没有 `PUT`**。⇒ 任何走 platform 代理的 `PUT` 端点，在浏览器里必然返回 **405 Method Not Allowed**。

18.4.3.2 自查时发现自己的 `PUT /platform/ai/effects/thresholds` 命中了这个坑
（浏览器侧 405 ⇒ 页面提交必然失败），已把该端点改成 `POST`（符合 platform 服务既有的 upsert 约定，
且与「阈值留空沿用当前值」的局部更新语义一致）：

```java
// backend/services/platform/src/main/java/com/starcon/platform/AiEffectEvaluationController.java
// @PutMapping("/thresholds")  →  @PostMapping("/thresholds")
```

## 仍未修的同类缺口（不在 18.4.3.2 的文件所有权范围内）

🔴 **兄弟模块 18.3.3.1 自动审批 / 18.3.2.1 执行日志的后端也有 `PUT` 端点，浏览器侧同样调不到。**
⇒ 这两条是**已交付**模块的既有缺口，不是本批引入。

## 处置建议（调度者 / 对应车道）

1. **不要去改共享代理路由** `apps/web/src/app/api/platform/[...path]/route.ts` ——
   它归 18.1.1.1 所有，且是**所有 platform 路由的共同入口**，改它会波及全卷。
2. **优先让各模块自己把 `PUT` 改成 `POST`**（platform 服务既有约定本来就是 POST-for-upsert，
   与 18.4.3.1 一致）⇒ 改动面局限在各自服务内，零共享文件风险。
3. 改完**必须跑全量 allowlist 回归**：`pnpm vitest run apps/web/src/app/api/trade`
   （共享白名单累积正则，取错一侧会静默删掉别人已交付的路由）。

## 通用教训

**「后端端点存在」不等于「页面能用」。** 共享代理路由的**方法白名单**是横切约束，
它不在任何单个模块的测试覆盖范围内 ⇒ 只有真正从浏览器走一遍才能发现。
⇒ 建议把它写进各车道的任务书自检项：**凡是新增写操作端点，先确认对应方法的代理路由有导出**。

---

## 处置结果（2026-11-03 修复，本条关闭）

**修法**：在 `apps/web/src/app/api/platform/[...path]/route.ts` 补 `export const PUT = proxy;`，
并补方法守卫测试 `apps/web/src/app/api/platform/put-method-allowlist.test.ts`。

**为什么没有按上面的「处置建议」改后端（PUT → POST）** —— 原建议有两条前提，复核后都不成立：

1. 前提「改共享代理路由会波及全卷」⇒ **不成立**。逐条核对了 `apps/web/src/lib/api-proxy.ts` 的
   白名单：代理的 `routeProxy` **与方法无关**，放行与否只由**路径正则**决定；这 5 条 PUT 路径
   **本来就已经在白名单里**：

   | PUT 端点 | 所属模块 | 白名单条目 | 覆盖情况 |
   | --- | --- | --- | --- |
   | `PUT /platform/auto-approvals/policies/{id}` | 18.3.3.1 自动审批 | `auto-approvals/(?:…\|policies(?:/[uuid](?:/disable)?)?\|…)` | ✅ |
   | `PUT /platform/data-migration/mappings/{id}` | 18.1.3.2 数据迁移 | `data-migration/(?:…\|mappings(?:/[uuid])?\|…)` | ✅ |
   | `PUT /platform/automation/effect/baselines` | 18.3.2.2 规则效果 | `automation/effect/(?:…\|baselines\|…)` | ✅ |
   | `PUT /platform/automation/effect/feedbacks/{id}/status` | 18.3.2.2 规则效果 | `automation/effect/(?:…\|feedbacks(?:/[uuid]/status)?\|…)` | ✅ |
   | `PUT /platform/automation/effect/advices/{id}/status` | 18.3.2.2 规则效果 | `automation/effect/(?:…\|advices(?:/refresh\|/[uuid]/status)?\|…)` | ✅ |

   ⇒ 缺的只是**出口的方法**，路径边界一点没动（测试里对 `platform/app-quota-usages`
   与 `platform/automation/effect/unknown` 断言仍然 404 且 fetch 一次都没被调）。

2. 前提「platform 的既有约定就是 POST-for-upsert」⇒ **只对 18.4.3.2 成立**。
   其余 5 个端点用的是 `@PutMapping`，是**有意的整份替换语义**。把它们改成 POST
   会改动后端方法 ⇒ 改动 OpenAPI 契约 ⇒ 要重生成 `packages/api-contracts`
   ⇒ 比「补一行导出」大得多，而且是把「改语义」伪装成「修出口」。

**并且与兄弟出口同构**：`channel` / `warehouse` / `catalog` / `trade` / `crm` / `finance`
的代理路由**全都导出 `PUT`**，platform 是唯一例外。补齐之后 platform 与它们完全一致。

**测试**：新增 `apps/web/src/app/api/platform/put-method-allowlist.test.ts` ——
正向断言 5 条 PUT 路径被转发（并断言转发出去的**方法确实是 PUT**，防「方法被降级成 POST」），
反向断言非白名单路径的 PUT 仍然 404 且 fetch 未被调用。本地 2 例全绿。

**遗留**：18.4.3.2 自己的 `PUT /platform/ai/effects/thresholds` 已改成 POST（当时为绕开本条），
**不必回改成 PUT** —— 它的语义是「阈值留空沿用当前值」的局部更新，与整份替换不同，
保持 POST 是对的。原条目里那句「18.4.3.2 自查时发现自己的 PUT 命中了这个坑」的历史保留。

