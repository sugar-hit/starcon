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
