# 批次 #3 · 8.1.2.2 库位自动分配（L2 绿）

> 本轮由 2026-10-13 20:00 tick 调度。批次内实际交付 1 个模块（其余 3 条车道并入冲突，见文末）。
> L2 全量验证：后端 2974 例 + 前端 2230 例 + Next production build，全绿。

---

## 模块 8.1.2.2 库位自动分配

**这个功能做什么**

仓库管理员对一批收货明细执行「自动分配库位」，系统按**容量匹配 + 现有占用**自动挑选合适的库位并落库；
每一次分配都是可追溯的：谁、按什么策略、把哪批货放进了哪个库位，都能回看和改判。

**技术落地**

- **迁移**：`V106__warehouse_location_auto_allocation.sql`
  - 建 `location_allocation`（分配单）与 `location_allocation_line`（分配明细）两张表
  - 业务表 `tenant_id uuid NOT NULL`，复合唯一键 `(tenant_id, id)`
  - 外键一律走复合形式 `FOREIGN KEY (tenant_id, …)`，从 DB 层保证跨租户引用不可能发生
  - 启用 Row-Level Security
- **端点**：`LocationAllocationController` —— 发起分配 / 查询分配单 / 查询候选库位 / 改判
- **页面**：`/warehouse/location-allocations` —— 库位自动分配工作台
- **测试**：`LocationAllocationIntegrationTest`（含租户隔离三断言）

**关键设计决策**

- 候选库位按**容量与占用**实时筛选，而不是预生成快照 —— 库位占用是高频变化的事实，
  快照会在下一次收货前就过期
- 改判走**新记录**而非原地更新，保留完整判定链路

---

## 流水线本轮修复：一次被静默丢弃的成功合并

**这不是模块交付，是本轮在 L2 前发现并抢救的调度器缺陷。**

`lane.mjs merge-ready` 处理 4 条 ready 车道时：

- `8.1.2.2` **合并成功**，生成 merge commit `d6c882c`（16 文件 / 5485 行）
- `7.2.1.2`、`8.4.2.1`、`8.5.2.1` 三条**冲突**（都在 `api-proxy.ts` 这类累积型共享文件上）

脚本按设计对冲突车道执行 `git merge --abort` 清理现场。
但 **`merge --abort` 的语义是「把分支退回 `ORIG_HEAD`」，而 `ORIG_HEAD` 正是上一条车道刚合出来的那个 commit**
⇒ 8.1.2.2 的成果被连带回滚。reflog 留痕：

```
b41a14f HEAD@{0}: reset: moving to HEAD~1        ← abort 冲掉了 d6c882c
d6c882c HEAD@{4}: merge feat/8-1-2-2: Merge made by the 'ort' strategy.
```

**难发现的原因**：`d6c882c` 这个 commit 对象还在对象库里，工作区内容也原封不动，
只有 `git branch --contains d6c882c` 为空、`git log` 里看不到它，才暴露它已不在集成分支上。
而 lane 状态已被写成 `merged`、`gc` 又删掉了它的 worktree ——
**台账说它交付了，分支上却没有它的代码，且没有任何自动机制会发现。**

**抢救**：`git reset --soft d6c882c`（`d6c882c^ == b41a14f`，纯快进、零内容变更）。
事后核对工作区干净、commit 已在集成分支上、5485 行全部在位。

完整分析与修法建议见 `docs/backlog/2026-10-13-merge-ready-abort-discards-successful-merge.md`。
**代码缺陷本轮未修**（批次中途不改编排器），下轮派修复任务。

---

## L2 全量验证记录

| 项                    | 结果                                       |
| --------------------- | ------------------------------------------ |
| `./mvnw verify`       | **BUILD SUCCESS**，7 模块全绿，7:18        |
| 后端用例              | **2974 例，0 失败 0 错误**                 |
| `pnpm typecheck`      | EXIT=0                                     |
| `pnpm lint`           | EXIT=0（1 条 react-hooks warning，非阻塞） |
| `pnpm test`           | **2230 例全绿**，118 个测试文件            |
| Next production build | EXIT=0                                     |
| `sync.mjs --check`    | ✅ 台账与蓝图一致                          |

### 两次假红：既有测试在全量并发下 flake

首轮 L2 连续两次 BUILD FAILURE，两次红的用例**完全不同**，且都与本批改动无关
（本批只碰 warehouse / apps/web / contracts，无一个 catalog / identity / tenancy 文件）：

1. `catalog.DuplicateDetectionIntegrationTest` 2 例 `expected:<404> but was:<503>`
   —— 该测试用 JDK `HttpServer` 起身份桩并绑定**操作系统临时端口**，
   全量并发下端口被抢占 ⇒ `AccessService` 捕获 `RestClientException` 抛 503
2. `warehouse.StockOwnershipIntegrationTest` 1 例 UUID 不匹配
   —— 测试用**固定业务编码**建档，并发下同编码档位互相覆盖
3. warehouse 那轮实为 `Surefire is going to kill self fork JVM`：
   测试全通过后 fork JVM 30 秒不退被强杀，reactor 报 FAILURE 但**无任何非零断言失败**

三者单独重跑均绿（19 例 / 40 例 / 42 类全绿）。
分析与修法建议见 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`。

---

## 待办

- [ ] **修 `lane.mjs` 的 abort 缺陷**（用 `before` SHA 精确回退，不用 `ORIG_HEAD`）—— 高优先级，每批都会复发
- [ ] **修三条冲突车道**：7.2.1.2 / 8.4.2.1 / 8.5.2.1，冲突集中在 `api-proxy.ts` 与 `warehouse/[...path]/route.test.ts`
- [ ] **修两条 flake 测试**（临时端口 / 固定编码 / fork 退出超时）
- [ ] **cron 排程与 PIPELINE.md 不一致**：实际 `0 * * * *`，文档写 `0,30 8-19 * * *`（见本轮汇报）
