# 10.5.1.1 报关单管理 —— 收尾（land）

日期：2026-10-08 14:02–14:20　车道：`feat/10-5-1-1`　worktree：`.worktrees/10.5.1.1`
迁移版本号：**V85**（沿用预分配号，未改；**未并入集成头，所以本车道可直接改**）
状态：**已交付 · 已 land**

## 接手起点

HEAD `87b271e`，工作区干净，领先集成头 4 个 commit（V85 828 行 + 集成测试 744 行 + 服务层/端点）。

**基线复验（不采信任务书里的数字，实跑确认）：** `./mvnw -pl services/warehouse -am test
-Dtest=CustomsDeclarationIntegrationTest` → **25 例 22 绿 / 3 红**，与任务书一致。

> 坑记一笔：任务书给的那条命令**跑不通** —— `-am` 会先构建 `tenancy` 模块，而它没有匹配的测试，
> surefire 直接以 `No tests matching pattern ... were executed!` 失败。必须补
> `-Dsurefire.failIfNoSpecifiedTests=false`。

前端页面**完全没开工**，这是本轮最大的缺口。

## 决策摘要：3 个红用例里只有 1 个是那个待裁决的问题

任务书假设「3 个红都源于 `submitted_at` 守卫」。实跑下来**不是**，逐个定位后是三个不同的根因。
这条要记下来：前一轮 worker 按纪律拒绝 land 是对的，但阻塞点被归错了因。

| 用例 | 真实根因 | 处置 |
| --- | --- | --- |
| `amendmentCountComesFromResultRowsNotHandFilled` | **就是**裁决说的 `submitted_at` 守卫 | 改 V85 守卫 |
| `clearedRejectedAmendedAllAudited` | **夹具 bug**：`reviewed()` 硬编码同一张发运单，循环 3 次撞唯一约束 `customs_declaration_tenant_id_source_shipment_id_key` | 改测试夹具：备三票货 |
| `submitRequiresAllThreeDocumentsFinal` | **断言写错了**：断言「必须人工复核」，但单子已复核过，真正拦它的是三单据齐备闸 | 改测试断言 |

## 关键 trade-off

**1. 守卫改法：用 OLD 而不是状态集合**

裁决要求「曾经提交过就允许保留提交痕迹」。判据选 `OLD.submitted_at IS NOT NULL`（而不是把
DRAFT/PENDING_REVIEW/REVIEWED/REJECTED 加进允许集合）—— 因为「这张单到底报没报过」是**这一行的事实**，
不是「当前状态是什么」。用状态集合写法的后果是：INSERT 出来的草稿只要带 submitted_at 就会蒙混过关。

反向守卫一点没放松：从未提交过的单在非申报状态下带 submitted_at 仍然被拒。
**CANCELLED 刻意不放行** —— 取消是作废整张单，放行它等于允许「一张报过的单被抹成从没有报过」。

**2. 顺带发现并修掉一个被我的改动暴露的真 bug**

修好上面的守卫后，单子第一次真的落进 DRAFT，于是暴露出
`guard_customs_amendment_count` 里 `IF NEW.status = 'DRAFT' THEN RETURN NULL` —— 这条延迟约束触发器
**整行跳过校验**，「改单次数必须来自结果记录」这道闸在草稿行上形同虚设（测试第 603 行正是钉这个的，
改完守卫后它才开始真正执行）。

原注释写的是「退单后回草稿时计数保持不变」，但**「保持不变」说的是不写新值，不是「这一行从此不校验」**。
改成 `IF NEW.status = 'DRAFT' AND NEW.amendment_count IS NOT DISTINCT FROM OLD.amendment_count`：
没改就放行，改了就照样拿 `customs_result` 里的 AMENDED 条数核对。

这条如果不修，「3 红全绿」是靠放松真守卫换来的，属于假绿。

**3. 前端复用头程发运单的端点做来源**

建单表单的来源下拉直接调 10.4.1.1 的 `first-mile-shipments`，不另造一张「可报关发运单」列表 ——
「哪些发运单能报关」是后端判据（已发出、未取消），前端再抄一遍必然漂移。

## 文件清单

| 类别 | 位置 |
| --- | --- |
| 迁移 | `backend/services/warehouse/src/main/resources/db/migration/V85__warehouse_customs_declaration.sql`（改 2 处守卫：submitted_at 回退、amendment_count 漏校验） |
| 端点 | `backend/services/warehouse/src/main/java/com/starcon/warehouse/CustomsDeclarationController.java` + `CustomsDeclarationService.java` + `CustomsDeclarationModels.java`（本轮未改） |
| 集成测试 | `backend/services/warehouse/src/test/java/com/starcon/warehouse/CustomsDeclarationIntegrationTest.java`（夹具三票货 + 一处断言纠正） |
| 前端模型 | `apps/web/src/components/logistics/customs-declaration-model.ts`（新） |
| 前端页面 | `apps/web/src/components/logistics/customs-declaration.tsx`（新）、`apps/web/src/app/logistics/customs/page.tsx`（新） |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（放行 customs-declarations 全形状 + 注释） |
| 前端测试 | `.../customs-declaration-model.test.ts`（27 例）、`apps/web/src/app/api/warehouse/customs-declaration-allowlist.test.ts`（7 例） |
| 契约 | `packages/api-contracts/src/catalog.ts` + `warehouse.openapi.json`（**codegen 重生成，禁手改**） |
| 台账 | `docs/product/capability-blueprint/module-progress.md` 第 352 行（仅本车道那一行） |

## 验证（真实结果）

- `./mvnw verify`（backend 全量）：**BUILD SUCCESS**，identity/catalog/channel/warehouse/trade 全绿
- 集成测试：**25 例 25 绿**
- `pnpm typecheck`：**3 successful**（先红过 8 处，全是真错：`exactOptionalPropertyTypes` 下 undefined 不等于「没填」、
  模型导出名与页面 import 名字对不上、jsonb Map 生成的类型是 `{ }`）
- `pnpm lint`：**3 successful**（残留 warning 在 catalog/warehouse 既有文件，非本车道）
- `pnpm test`：**87 文件 1592 例全绿**
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`：成功，`/logistics/customs` 11.6 kB 在产物里
- `node tools/module-progress/sync.mjs --check`：**✓ 台账与蓝图一致**

### 白名单怎么验的（不靠 grep）

`customs-declaration-allowlist.test.ts` **调真正的 route handler 断言 HTTP 状态码**：
放行 = 200 且 fetch 被调、拦截 = 404 且 fetch 一次都没调。两个方向都断言 ——
否则一个「全部拒绝」的正则也能让放行用例全绿。放行清单是从模型源码**反向抽取**真实调用点
（`${BASE}…` 模板串），不是手抄。

**关于「两层 alternation 都要加」：实测只需要外层。** 报关单端点命名空间是
`/warehouse/customs-declarations`，与 `first-mile-shipments` 同级、不在 `inventory/` 之下，
所以只加外层仓库级段即可，内层 `inventory/` 组加不加都一样。反向用例已经钉住这件事：
`warehouse/first-mile-shipments/{id}/customs` 仍然 404（兄弟模块**没有** `/customs` 子路径），
`warehouse/first-mile-shipments/{id}` 等形状仍然 200（没被报关单段吃掉）。

## 后续待办 / 已知遗留

1. **`customs_rejected_at` 有同型隐患（未修，需下一轮裁决）**：
   `CUSTOMS_REJECTED → DRAFT（退回改）` 在状态机里是**合法**的（`allowedNext` 第 116 行），
   但守卫 `customs_rejected_at IS NOT NULL AND status NOT IN ('CUSTOMS_REJECTED','AMENDED')`
   会把它误判成不合法 —— 与本轮修掉的 `submitted_at` 是**完全同型**的 bug。
   本轮没动它，因为：① 裁决只授权改 `submitted_at` 那一条；② 任务书明确要求「确认没有放松掉任何一条真正的守卫」，
   顺手改另一条属于扩大范围。
   补充证据：**当前没有任何服务动作会走这条路**（`amend()` 只做 SUBMITTED/AMENDED/CLEARED → AMENDED），
   所以它目前**打不到**，不是活 bug；但状态机已经声明允许，迟早会有人加这个动作。
   另注：`cancel()` 没有校验 `allowedNext`，所以从 CUSTOMS_REJECTED 取消会直接撞上这条守卫
   （报「没有被退过单」，语义完全不对）。这两处建议一起裁决。
2. **提交时间戳的断言**：集成测试里写了时间的用例（`resultAt` / `submitted_at` / `cleared_at`）已断言写回值；
   前端侧 `moment()` 对后端可能返回的 null 是运行时空值安全的（显示 `—`），但生成的契约类型把它们标成了非空
   `string`，这是 springdoc 的已知失真，不是本模块引入的。
3. `spotless:apply` 在 Java 25 上崩溃是仓库既有问题，不阻塞 verify（本轮未触发）。
