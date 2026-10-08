# 10.5.1.1 报关单管理 —— 接手收尾（未 land）

日期：2026-10-08 00:02–00:52　车道：`feat/10-5-1-1`　worktree：`.worktrees/10.5.1.1`
迁移版本号：**V85**（沿用预分配号，未改）
状态：**进行中 · 未 land**（硬停机前未跑完全量验证）

## 接手起点

上一轮 worker 因 `net::ERR_NETWORK_CHANGED` 崩溃，留 WIP。接手时工作区干净、无未跟踪文件，
分支落后集成头 19 个 commit。

- 冲突预检：两次取样无文件变动、无未跟踪 → 通过
- `git merge integration/module-dev`：19 个 commit，**零冲突**（本车道改的 5 个文件上游都没有）

## 依赖自检

- 10.4.1.1 头程发运单 `V80__warehouse_first_mile_shipment.sql` 已在集成头 → 通过
- 3.7.1.1 HS 编码字典**上游未交付**（被 `product_compliance` 服务缺失阻塞）。
  沿用上一轮的正确处理，**不建假字典表**：HS 码自由录入 + `hs_code_source` / `hs_code_verified`
  如实标记，申报要素留 `declaration_elements` jsonb + 齐备性状态机，`/capabilities` 里
  `HS_CODE_DICTIONARY` 维度 `available=false` 且 `ownerModule='3.7.1.1'`。

## 集成测试真实红绿（接手的首要任务）

首跑结果与上一轮自述一致：**25 例 13 绿 / 12 红**。逐个定位后修到 **22 绿 / 3 红**。

修掉的 9 个根因（都是真 bug，不是断言迁就）：

1. **`min(uuid)`**（6 例红）—— `code()` 用 `select min(id)` 去别的报关单取单号来编本单的单据号，
   PG 无 `min(uuid)` 重载，且**逻辑本身也是错的**：单据号应挂在本单的 `declaration_code` 下。
   改为直接取当前单头的 `declaration_code`，删掉 `code()`。
2. **单据 content 不是 JSON**（6 例红，之前被 1 掩盖）—— `content(...).toString()` 出来的是
   `{k=v}`，不是 JSON，插 `jsonb` 直接 `invalid input syntax for type json`。
   改为注入的 `ObjectMapper.writeValueAsString`（与 `elementsJson` 同一套 `toJson` 帮助方法）。
3. **复核退回没记轮次**（1 例红）—— `rejectReview` 走 `default` 分支，`review_round` 仍为 0，
   而 V85 守卫要求 `REJECTED` 也必须 `review_round > 0`。守卫注释本身就写明
   「复核退回同样走完过一轮复核」，所以**改服务层**：`REJECTED` 分支也 `review_round+1`
   并写 `reviewed_at/reviewed_by`。
4. **撤回复核是死路**（1 例红）—— `withdrawReview` 把单子退回 `PENDING_REVIEW`，消息还写着
   「现在可以改明细」，但 `update` 只放行 `DRAFT`/`REJECTED`，退回后既不能改也不能复核。
   `update` 放开 `PENDING_REVIEW`；改完仍要重新复核才能提交（`submit` 只认「已复核」），闸门没被绕过。
5. **汇率/货值守卫对草稿态完全失效**（2 例红）—— `guard_customs_rate_snapshot` 开头对
   `DRAFT/PENDING_REVIEW/REJECTED` 直接 `RETURN NULL`，于是「改单头货值」和「改折算额」都畅通无阻。
6. **延迟约束不能用 NEW 快照**（修 5 时踩到）—— 延迟触发器在提交时才跑，但 `NEW` 是**触发那一刻**
   的行镜像。建单是「插单头(货值 0) → 写明细 → 回填单头货值」，拿 INSERT 那一刻的 0 去比提交时
   已存在的明细，必然误报 `0 ≠ 2000`（第一次改完 22 例全红就是这个原因）。
   改为**回表读最终提交态**，与 `guard_customs_line_count` 同一个做法（它本来就该这么写）。
   顺带去掉状态白名单，货值恒等式成为无条件的真不变量。
7. **海关回执写错了枚举口径**（3 例红）—— `from_result/to_result` 的 CHECK 词表是**海关回执口径**
   （PENDING/ACCEPTED/CLEARED/REJECTED/AMENDED），服务却把**报关单内部状态**
   （SUBMITTED/CUSTOMS_REJECTED…）写进去，撞 CHECK 约束。新增 `resultVocabulary()` 做两套枚举映射。
8. **时间戳守卫是过严双向等价**（3 例红）—— `(NEW.status='CLEARED') <> (NEW.cleared_at IS NOT NULL)`
   这种写法，会把「往前走一步」也判成不自洽：放行后退单/改单时 `cleared_at` 必须留着当举证，
   却因为状态不再是 CLEARED 而报错。改成**单向**约束：「没发生过的事不许有痕迹」+
   「到了那一步必须有痕迹」，对 `submitted_at`/`cleared_at`/`customs_rejected_at` 三条都改了。

## 改测试的 3 处（都是断言机制错，不是实现错）

1. `headValueMustEqualLineSum` —— 测试体改的是 `review_round/reviewed_at/reviewed_by`，
   和它名字声称的「单头货值 = 明细合计」毫无关系。改为真的篡改 `declaration_value` 并断言守卫消息。
2. `pooledConnectionDoesNotLeakTenant` —— 原来断言「读对方事件表要抛异常」。但 RLS 的隔离语义就是
   **静默过滤**（对方的行对你根本不存在），正确断言是「读不到行」。实现是对的，断言机制错了。
3. `reviewRequiresAtLeastOneLine` —— 原来用「建单时明细为空」走到复核，但 `create` 本来就拒空明细。
   改走可达路径：`update` 整份替换明细把明细清空，再送复核，验 `customs_2_review_completeness`。

多租户三断言仍为**实跑**（Testcontainers 真 PostgreSQL 16，RLS + FORCE ROW LEVEL SECURITY）：
两租户互不可见、伪造 `X-Tenant-Id` 头无效、连接池归还后不串租户。

## 未完成的 3 红（硬停机，未决）

1. `amendmentCountComesFromResultRowsNotHandFilled` —— 改单后退回 `DRAFT` 时保留 `submitted_at`，
   与新守卫「DRAFT 不允许带提交痕迹」冲突。**口径待定**：退回草稿是否该抹掉提交痕迹，
   还是提交痕迹应另表留痕而不是留在单头上。没定就改，属于替业务拍板。
2. `clearedRejectedAmendedAllAudited` —— 测试里对**同一张发运单建了两次报关单**，
   撞 `customs_declaration_tenant_id_source_shipment_id_key`（一张发运单一张报关单，是设计约束）。
   属测试流程要改（拆租户/换发运单），不是实现问题。
3. `submitRequiresAllThreeDocumentsFinal` —— 抛异常消息与断言期望的子串不一致，具体口径待核。

## 未做的事（明确说明）

- **未跑完全量验证**：`./mvnw verify`、`pnpm typecheck/lint/test`、web build、
  `sync.mjs --check` 均未在本轮跑完（硬停机）。
- **未 land**：3 红未清零，且全量验证未跑完，按纪律不调用 `lane.mjs land`。
- **契约未重生成**：本轮**未改任何后端 DTO/record 签名**（只改了方法体与内部帮助方法），
  故 `packages/api-contracts` 无需重生成。
- **前端页面未做**：契约与 `/capabilities` 端点已就绪，但工作区与页面未开工；
  下一轮若做，**api-proxy 两层 alternation 都要登记**（外层 `customs-declarations`，
  内层 `customs\/(?:declarations)`），改完必须从 `apps/web/src` 反向抽真实调用点逐条断言放行。

## 方法论教训

延迟约束触发器**不能信 `NEW` 快照**。触发时捕获的行镜像 ≠ 提交时的最终态；
只要一个事务里对同一行先插后改，就必须回表读最终态（`guard_customs_line_count` 是正面样板）。
同理，「时间戳是否为空」这类判据要先问一句：状态往前走时这个痕迹会不会保留？
会保留就不能写成双向等价。