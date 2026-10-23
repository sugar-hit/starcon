# 2026-10-24: 僵尸主数据清理 timestamptz 断言竞态修复（3.9.2.2 assertion race fix）

> 车道：`fix/3-9-2-2-timestamptz-assertion`（worktree `.worktrees/3.9.2.2-fix`），基线 `b2862d39`。
> 触发：集成头跑全量 `mvnw verify` 时 **catalog 服务红 1 条**（其余全绿）：
> `ZombieCandidateIntegrationTest.最后维护时刻走timestamptz往返`
> `expected: 2025-06-10T23:30:07.354Z but was: 2025-06-10T23:30:07.355Z`。
> 本车道是**纯修复车道**：不改功能面、不改台账，只修这一条既有的时序相关偶发失败（flaky）。

## 摘要

这条红**不是本批次引入的回归** —— 本批次一个 catalog 文件都没改
（`git diff --name-only cb5a91b0..HEAD | grep services/catalog` ⇒ 0 个文件），
而本批之前的集成头跑同一个测试类是绿的。它是一个**既有测试自身写法的缺陷**：
用例把「期望值」和「实际写进库的值」分别用**两次独立的 `OffsetDateTime.now()`**算出来，
两次之间只要跨过一个毫秒边界，断言就差 1 毫秒而红 —— 断言被一个**时钟竞态**否定，
而不是被时区/精度否定。修法是让 helper 把**它写进库的那个时刻**返回出来，调用方直接拿返回值当期望值。

## 决策 / 结论

### 1. 根因复核：成立

逐行核对 `backend/services/catalog/src/test/java/com/starcon/catalog/ZombieCandidateIntegrationTest.java`：

- helper `backdate(String table, UUID tenant, UUID id, long days)`（原 201 行）内部
  `OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).truncatedTo(MILLIS)` 算出 `moment`，
  用 `?::timestamptz` 绑定写进 `updated_at` —— 这是**第 1 次**取时钟。
- 失败用例 `最后维护时刻走timestamptz往返`（原 489 行）自己**另外**算了一个
  `OffsetDateTime.now(ZoneOffset.UTC).minusDays(500).truncatedTo(MILLIS)` 当期望值 ——
  这是**第 2 次**取时钟；随后 `backdate("sku", a, target, 500)` 写进去的是**第 1 次**算的值，
  断言却拿**第 2 次**的值去比。
- 两次调用相隔几毫秒 ⇒ 跨过毫秒边界 ⇒ 期望值与实际值差 1 毫秒 ⇒ 红。
  失败信息里 `expected` 比 `but was` 小 1 毫秒，与这个方向一致。

⇒ 调度者给的根因**成立**：期望值取自后一次 now()、库里写的是前一次 now() 的值。

### 2. 修法：`backdate(...)` 返回它写进库的那个时刻（正解）

- helper 签名 `void` → `OffsetDateTime`，末尾 `return moment;`。
- 调用方直接 `var expected = backdate("sku", a, target, 500);`，
  断言仍是 `assertThat(stored.toInstant()).isEqualTo(expected.toInstant());`。
- 期望值与写入值**是字面同一个对象** ⇒ 不再存在两次独立取时钟 ⇒ 竞态从根上消失，
  且与取时钟的时机无关（不是「这次刚好过了」）。

### 3. 明确**不**放宽断言（关键 trade-off）

- **没有**改成 `isCloseTo`、**没有**引入任何容差窗口。
- 理由：放宽会把真正的**时区回归**一起放过 —— 这条用例想验证的正是
  「`?::timestamptz` 绑定保不保瞬时」；若服务层改用 `Timestamp.valueOf(LocalDateTime)` 绑定，
  库里会偏 `-28800s`，容差 1 秒级断言照样会红，但 1 毫秒级容差对时区回归其实无害，
  问题在于一旦有人把容差放大到「秒」，时区回归就抓不住了 ⇒ **正解是消除竞态，不是放松断言**。
- helper 里「截断到毫秒」那段注释**原样保留**（它说的是对的：PG 的 timestamptz 是微秒精度，
  带纳秒的期望值去比会恒不相等，那种失败是精度噪音，会盖掉真正的时区断言）；
  只在 helper 的 Javadoc 上**补**了一句「返回值 = 写进库的那个时刻」。

### 4. 另一个 timestamptz 用例**未动**

- `观测时刻写回值一致_不偏八小时` 用的是**固定**时刻
  `OffsetDateTime.of(2026,2,20,15,30,0,0, ZoneOffset.ofHours(8))`，
  期望值与写入值不依赖取时钟时机 ⇒ **天然没有这个竞态**，一个字都没改。

### 5. helper 改签名的编译安全性：全文件 30 处调用点逐个看过

- 全文件 `backdate(` 调用点共 31 处（1 处定义 + 30 处调用）。
- 30 处调用**全部是丢弃返回值的语句调用**（形如 `backdate("sku", a, target, 400);`）——
  Java 允许忽略返回值 ⇒ 编译通过（L0 编译闸已实证）。
- 全文件**没有** `::backdate` 方法引用、没有把 `backdate` 当 `Runnable`/`Consumer` 之类
  函数式接口传递 ⇒ 改返回值类型不会打破任何既有用法。
- 另有 `party` 表的调用点（`backdate("party", a, supplier, 400)` 等），同样安全。

## 涉及文件

- `backend/services/catalog/src/test/java/com/starcon/catalog/ZombieCandidateIntegrationTest.java`
  —— helper 改签名 + 加 `return moment` + Javadoc 补一句；失败用例改用返回值当期望值。
  （`8 insertions(+), 7 deletions(-)`）
- `docs/sessions/2026-10/2026-10-24-zombie-timestamptz-assertion-race-fix.md`（本篇）

**未动**：catalog 的任何生产代码 / 迁移 / 其它测试类；台账
（`docs/product/capability-blueprint/module-progress.md` —— 3.9.2.2 的交付状态没变）；
集成头工作区里的任何文件。

## 验证

- L0 编译闸：`backend/ && ./mvnw -q compile` → **EXIT=0**
- L1 模块闸：`./mvnw -pl services/catalog -am test -Dtest='ZombieCandidateIntegrationTest'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` **连跑 3 次**：
  - RUN 1: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`
  - RUN 2: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`
  - RUN 3: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`
- surefire 报告确认目标用例 `最后维护时刻走timestamptz往返` **确实执行**（不是被跳过）。
- 3 次全绿不是因为「这次刚好过」：修好之前该用例在同一台机器上**第一次绿、隔几十分钟再跑就红**，
  正是竞态的典型表现；现在期望值与写入值是同一个对象，与取时钟的时机彻底无关。
- 未跑全量 `mvnw verify`、未跑整个 catalog 服务（那是调度者在集成头上每批只跑一次的事）。

## 关键 trade-off / 讨论

- **为什么不给 helper 加「注入时刻」的参数**（比如让调用方自己算好再传进去）：
  会把「算时刻」这件事又摊回每个调用点，30 个调用点各写一遍 `now()` 更容易再写错；
  让 helper **返回它写入的值**更简单，也把「期望值 = 实际写入值」这条纪律固化在签名上。
- **为什么不动另一个固定时刻的用例**：它没有竞态，改它属于扩大范围（且容易和并行车道产生无谓冲突）。
- **取证方式**：修完之后连跑 3 次同一测试类（3×28 例全绿）+ 读 surefire XML 确认目标用例真跑过；
  竞态「修前绿、隔一段时间红」的历史表现由调度者复核过，本车道未重跑旧版复现（避免在本车道引入脏状态）。

## 后续待办

- **并入集成头由调度者做**（本车道不 merge、不 checkout 集成分支）。
  并入后建议调度者在集成头上再跑一次全量 `mvnw verify` 确认 catalog 绿。
- 本车道**未改台账** —— 3.9.2.2 的交付状态没变；台账证据列若需要补「L2 绿」字样，由调度者统一处理。
- 同类隐患可能存在于其它服务里**同样「自己算一次 now() 再跟库里比」的测试**里
  （任何 `now()` 与写入值分两次取时钟的断言都有同样问题）。本车道按职责范围只修了 catalog 这一处；
  若要普查，建议单开一轮「测试侧时钟竞态普查」（搜 `OffsetDateTime.now()` 与被写入值分两次取时钟的断言）。

## 关联

- `backend/services/catalog/src/test/java/com/starcon/catalog/ZombieCandidateIntegrationTest.java`
- 3.9.2.2「僵尸主数据清理」交付车道（功能面，本车道未改）
- `docs/sessions/2026-10/2026-10-24-payment-execution-handoff-guard-fix.md`（同批次另一条修复车道的记录格式参照）
