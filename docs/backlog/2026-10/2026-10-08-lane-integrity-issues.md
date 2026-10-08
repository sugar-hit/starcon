# 流水线车道完整性问题（2026-10-08 22:00 tick 取证）

调度器在 22:36 复查在飞车道时发现三个问题。前两个来自并发 tick 叠加排车道，
第三个是更早就存在、但直到本轮车道在 `api-proxy.ts` 上真的撞上才暴露的既有缺陷。

## B-01 `api-proxy.ts` 白名单少一个 `|`，已交付路由静默 404

**状态**：待修，归属 **3.4.2.2 多语言内容**车道。已确认存在于集成头 `874a192`。

集成头 `apps/web/src/lib/api-proxy.ts` 的 catalog 白名单字面量是：

```
translation-coveragetranslations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}…
```

`translation-coverage` 与 `translations` 本该是两个 alternation 分支，中间**少了一个 `|`**，
于是整个 alternation 变成一个永远匹配不上的拼接 token。

**影响**：3.4.2.2 已交付的 `descriptions/templates/{uuid}/translations` 在浏览器端静默 404。
typecheck / lint / vitest / Playwright **全绿**——白名单只在浏览器端生效，
且 3.4.2.2 自己的测试对这条路由**零断言**，所以没有任何闸能拦住它。

**证据分工**：调度器已直接确认该字面量在集成头里（`grep -c` = 1，且不在注释中）；
4.2.2.2 车道用**跑真 proxy 断言 HTTP 状态码**的方式确认了行为侧确实是 404。

**修法**：补回 `|`，并把 4.2.2.2 车道留在 `api-proxy` union 测试里的
`CATALOG_KNOWN_BROKEN` 条目移到 `CATALOG_ALLOW`——**该断言设计成「对方修好就会红」**，
所以必须两边一起改，否则修完反而红。

⚠️ 验证只能靠跑真 proxy（白名单拦截 = 404，放行 = 转发）并**同时断言反向用例**。
`grep` + 自写正则已连续骗过 6 版，不可再用。

## B-02 两条车道共用同一个 worktree（双 writer）

**状态**：待处理，归属 `3.7.1.1` 与 `3.7.1.2` 两条车道的持有方。

`.dev-agent/lanes.json` 里两条 running 车道的 worktree 完全相同：

| 车道 | 迁移版本 | worktree |
| --- | --- | --- |
| `3.7.1.1` HS 编码与申报要素 | V94 | `/Users/tommy/Project/starcon/.worktrees/3-7-1` |
| `3.7.1.2` 目的地报关版本 | V95 | `/Users/tommy/Project/starcon/.worktrees/3-7-1` |

`plan` 的冲突预检只检查「worktree 目录是否已存在且含**未跟踪文件**」，
`3-7-1` 在第二条车道排进来时目录已存在但当时干净，预检因此放行。
预检漏掉了**同一路径已被 lanes.json 登记占用**这一种形态。

**影响**：两条车道在同一目录里写不同迁移、分支，提交会互相污染；
`.dev-agent` 里 `3.7.1.1` 的 `worktreeExists` 还因为路径形态不一致而误报 `false`。

**修法（两条，缺一不可）**：
1. `plan` 的冲突预检增加一条：在 lanes.json 里查 `state==='running' && worktree===目标路径` 的占用，与目录检查同等对待，命中即零改动退出。
2. 派 `3.7.1.2` 的那条车道改用独立 worktree 目录，并把 `V95__customs_destination_version.sql` 挪过去。

## B-03 同一服务两条车道预分配到同一个 Flyway 版本号

**状态**：**已由调度器单方面解除**，记录在案以备复核。

`11.4.2.2`（V95）与 `3.7.1.2`（V95）都往 **catalog** 服务排迁移，
两边都已落盘：`V95__customer_service_knowledge_base.sql` 与 `V95__customs_destination_version.sql`。
Flyway 遇到同版本号会直接炸，炸的是 **catalog 整个服务的 Flyway 历史**，不是单个模块。

**处置**：调度器指令 `11.4.2.2` 车道 `git mv` 改到 **V100**（当时第一个空闲版本号），
追加 commit、不改写历史、重跑全量验证。`3.7.1.2` 保留 V95。

**根因**：`plan` 的版本号预分配是按**调用时看到的状态**算的，
两条 tick 并发各自预分配时没有互斥——`gate` 只保证「一次只有一个 tick 在派发」，
但本轮实际有两个 tick 先后取闸（22:00 与 22:19），彼此不在同一个临界区内。

**建议**：版本号预分配应在 `gate acquire` 之后、且预分配时重新读一次 `lanes.json`
中**全部**已登记版本号（含已 merge 的），而不是复用进入时的快照。