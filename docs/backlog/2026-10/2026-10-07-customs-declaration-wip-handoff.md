# 10.5.1.1 报关单管理 —— 车道中断与接手说明

日期：2026-10-07 23:30　车道：`feat/10-5-1-1`　worktree：`.worktrees/10.5.1.1`
迁移版本号：**V85**（已预分配，不要另找号）

## 状态：WIP，未 land

车道被 `net::ERR_NETWORK_CHANGED` 打断一次、续做后又耗尽时间预算。
集成测试 **25 例中 13 绿 / 12 红**，因此**没有调用 `lane.mjs land`**。
集成头里目前**没有**报关单这部分代码。

```
c(10.5.1.1 wip): V85 报关单迁移（已完成真实 PostgreSQL 16 校验）
9c65685 fix(10.5.1.1): 修 V85 守卫真 bug + 报关单服务层/端点/集成测试
```

worktree 干净，直接在原 worktree 续做，不要重新排车道。

## ⚠️ 上一轮「8 条 DB 不变量逐条验证通过」是错的

续做的 worker 用独立脚本把 V85 连同 warehouse 全部迁移在真实 PostgreSQL 16 上应用后，
发现 V85 有一个**上一轮漏掉的真 bug**：

`guard_customs_line_count`（V85 第 7.6 节，约 655-671 行）读 `NEW.source_shipment_id`，
但该列在**单头** `customs_declaration` 上，`customs_declaration_line` 根本没有这一列。
触发器挂在明细表上 ⇒ **任何一行报关明细写入都必炸**：
`record "new" has no field "source_shipment_id"`。

**已修**（改为先从单头取 `source_shipment_id` 再比对）。修复后测试错误数 22 → 12，
证实这就是主阻塞点。

**教训**：上一轮的「验证通过」没真跑过明细写入路径。
上一轮 worker 自述修过的三个 bug（触发器字母序、`jsonb_object_length` 在 PG 里不存在、
PL/pgSQL `record IS NULL` 误判）读代码确认修法是对的，**不要再重复查这三个**。

## 已完成

- `V85__warehouse_customs_declaration.sql`（802 行，6 表，10 个守卫，RLS）—— **已复验并修了上面的 bug**
- `CustomsDeclarationModels.java` / `CustomsDeclarationService.java` /
  `CustomsDeclarationController.java` —— 已写、**编译通过**
- `CustomsDeclarationIntegrationTest.java` —— 25 例骨架，Testcontainers PG16

## 剩余 12 例红的两个已知根因（已定位，不要重新查）

1. **`function min(uuid) does not exist`**
   `summary()` 里对 `uuid` 列用了 `min()`（`CustomsDeclarationService.java:297` 附近）。
   PG 的 `min()` 没有 uuid 重载 ⇒ 改 `min(declaration_id::text)` 或直接取首行。
2. **`状态「REJECTED」必须已完成至少一轮人工复核`**
   `rejectReview` 把状态置为 `REJECTED` 时 `review_round` 仍为 0，
   而 V85 守卫要求 `REJECTED` 也必须 `review_round > 0`。
   这是守卫与状态机的口径冲突：**改服务层**（rejectReview 先给轮次 +1）更符合
   「复核退回也走完过一轮复核」的注释原意。

其余约 10 例尚未逐一定位。

## 未完成

契约重生成 → 前端工作区与页面 → **api-proxy 白名单** → 台账/会话日志 → 全量验证 → `land`

## 下一步（按此顺序，每步都能独立验证）

1. 修 `summary()` 的 `min(uuid)`
2. 修 `rejectReview` 的 `review_round` 口径（改服务层）
3. 重跑至全绿，再量基线（上一轮量到后端 662 例）
4. 契约重生成：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
5. 前端工作区与页面
6. **api-proxy 两层 alternation 都登记**：外层加 `customs-declarations`，
   内层加 `customs\/(?:declarations)`。改完必须**从 `apps/web/src` 反向抽真实调用点
   逐条断言放行** —— 只处理内层会静默丢掉外层分支，表现为浏览器 404 而
   typecheck / lint / vitest 全绿。2026-10-07 一晚这个坑咬了两次。
7. 全量验证 → commit → `lane.mjs land`（**必须在主仓库根执行**）

## HS 编码「上游未交付」的处理（已做好，不要改回去）

上游 3.7.1.1 HS 编码与申报要素被 `product_compliance` 服务缺失阻塞，**未交付**。
当前实现方式是正确示范，不要改成建假字典表：

- **不建**任何 HS 编码字典表（建了就是假上游 + 双 writer）
- `hs_code` 接受自由码 + `hs_code_source` + `hs_code_verified` 如实记录
- 申报要素留成**可后补结构**：`declaration_elements` jsonb +
  `declaration_element_status` 齐备性状态机 + `declaration_elements_source/ref`
- `/capabilities` 里 `HS_CODE_DICTIONARY` 维度 `available=false` + `ownerModule='3.7.1.1'` + 原因
- 外部报关行接口标 `BROKER_API / MANUAL_ENTRY`，回执人工回填并标 `source_system='MANUAL_ENTRY'`

## 一个方法论教训（别再踩）

上一轮续做花了约 35 分钟用**裸 SQL 夹具**去撞上游 V80 的在途腿守卫
（`first_mile_leg`），反复踩 `source_type`/`status`/`dispatched_at`/在途腿四个约束，
最后才定位到真正原因是 **psql 不加 `--single-transaction` 会逐语句自动提交，
延迟触发器在明细 INSERT 那一刻就 COMMIT 了**。

**正确做法是一开始就写集成测试**，让 `@Transactional` 天然满足全部延迟约束。
需要独立验迁移时，psql 必须带 `-1` / `--single-transaction`。