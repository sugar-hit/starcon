# 2026-10-24: main 同步第 62 轮 —— 平台开关失败用例根因定位（纳秒 vs 微秒）、集成头批量格式化未同步

## 摘要

本轮同步 PR #65（`chore/sync-module-dev` → `main`）的闸门结论仍是**阻塞**，未合并。

本轮把上一轮只点名的失败用例**定位到了根因**：平台开关公告生效期的断言失败，不是时区偏移，
而是**纳秒精度与微秒精度不匹配** —— 测试用 `OffsetDateTime.now(UTC)` 取到纳秒值，
落库时 PostgreSQL 的 `timestamptz` 只保留微秒，回读比较时少了 94 纳秒，断言必然失败。

本轮同时确认集成头推进的 7 个文件是**一次纯格式化改写**（代码逐字节未变、台账未变），
因此**不刷新同步分支**：取集成侧原文会引入 6 个新的格式不合规文件，功能增量为零。

闸门标为「明细不可比」的三个作业，本轮**自己下钻到用例级**逐一比对，结论是全部持平。

## 决策 / 结论

1. **本轮不合 PR #65**。闸工具结论「阻塞」：出现了 `main` 基线里没有的失败用例。
2. **本轮不刷新同步分支**。集成头推进的 7 个文件是纯格式化改写，无功能增量、无台账增量，
   且集成侧原文不符合仓库自己的格式化标准 ⇒ 取用只会新增排版债。
3. **不在同步分支上修改那条失败用例**。它是集成侧交付内容的所有物，本任务只做合入、不做开发，
   也不允许为了让 PR 能推而改测试。解除条件由集成侧车道（或人工）处理。

## 关键 trade-off

- **为什么不刷新分支**：刷新看起来是「保持同步」的例行动作，但本轮刷新会引入 6 个新的
  格式不合规文件（集成侧用约 80 列宽重排版，仓库标准是 100 列），换来的功能增量是零。
  判据是「合入 main 的内容是否更好」，不是「分支是否逐字节等于集成头」。
- **为什么不给那条失败用例打补丁**：让 PR 能推进与让 PR 正确是两件事。那条用例断言的是
  「按值写回」，而存储层只有微秒精度 —— 缺陷在**测试的精度假设**，不在业务逻辑。
  在同步分支上打补丁会让同步分支与集成分支就此分叉，集成侧后续修复时还要再解一次冲突。
- **为什么自己下钻「明细不可比」的作业**：闸门把三个作业判为「明细不可比」时，只能比对到作业粒度，
  「持平」是**默认放行**而非证据。逐个作业取到用例级明细，才能区分「真的持平」与「只是看不见差异」。

## 本轮关键取证

### ① 失败用例的根因：纳秒 vs 微秒

CI 日志（run 40002631902，Java 作业）的真实断言输出：

```
expected: 2026-09-23T18:25:28.540236094Z (java.time.OffsetDateTime)
 but was: 2026-09-23T18:25:28.540236Z (java.time.OffsetDateTime)
when comparing values using 'OffsetDateTime.timeLineOrder()'
	at ...PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示(PlatformFlagIntegrationTest.java:273)
```

- 期望值 9 位小数（纳秒），实际值 6 位小数（微秒）⇒ 实际值少了 94 纳秒 ⇒ **按时刻序比较必然不等**。
- 存储层：`V324__platform_feature_flags.sql:151` 声明 `announcement_from timestamptz`，
  PostgreSQL 的 `timestamptz` 默认精度就是**微秒** ⇒ 写进去时被截断。
- 测试侧：`from`/`to` 由 `OffsetDateTime.now(java.time.ZoneOffset.UTC)` 派生，带纳秒精度。
- 该用例的注释预判的是「偏 8 小时（-28800s）」的时区问题，**实际发生的是亚微秒截断**，
  两者根因不同、修法也不同。
- 归属：这条用例**在 `main` 上根本不存在**（`PlatformFlagIntegrationTest.java` 是集成侧新交付的），
  且在同步分支头与集成头之间**逐字节相同** ⇒ 不是合并动作引入的，是集成侧交付内容自带的。

### ② 集成头推进 = 一次纯格式化改写（代码逐字节未变）

集成头相对同步分支头推进 7 个文件（+49 / −23），其中没有台账文件 ⇒ **无功能增量、无能力增量**。

- `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`：
  去掉注释与空白后，两侧归一化到**同一个 1747 字符、同一个 SHA-1**（`83f3349623ba`）⇒ 代码零变化，
  差异全部是 Javadoc 折行与一行赋值语句的换行。
- 其余 6 个 TS/TSX 文件：集成侧原文**不符合仓库自己的 Prettier 配置**（`printWidth: 100`）。
  例：`packages/ui/src/components/common/command-palette.tsx` 里集成侧把
  `<p role="status" className="...">` 拆成 4 行，而仓库自己的 Prettier 会把它放回 1 行（95 字符，放得下）。
  ⇒ **取集成侧 = 新增 6 个未格式化文件 = 自造排版债**。
- 验证方式：用仓库自己的 `node_modules/.bin/prettier --stdin-filepath` 逐文件归一化后比字节
  （不用 `--check <path>`：被 `.gitignore` 忽略的路径会被静默跳过，还给出「全部合规」的假绿灯）。

### ③ 三个「明细不可比」作业 → 自主下钻到用例级

| 作业            | 基线（main run 39883208132）                      | PR（run 40002631902）            | 判定               |
| --------------- | ------------------------------------------------- | -------------------------------- | ------------------ |
| Catalog Browser | channel 服务 `brandCredentialController` 装配失败 | 同上，错误文本与出现次数逐项相同 | **持平**           |
| Lint            | 646 个文件不合规                                  | 749 个文件不合规                 | **不劣化**（见下） |
| Security Scan   | `pnpm audit` 失败，11 项漏洞                      | `pnpm audit` 失败，10 项漏洞     | **不劣化**（见下） |

- **Lint 的排版债对账**：基线 646 + PR 独有 103 = 749，与 CI 自报数字逐位吻合（提取器带阴性对照）。
  逐个核对这 103 个文件在 `main` 上是否存在：**103 个全部是 `main` 上不存在的新文件，
  既有文件从合规退化为不合规的数量为 0** ⇒ 本次合入**没有让任何既有文件退化**。
- **Security Scan 的差异不来自代码**：`package.json` 与 `pnpm-lock.yaml` 在 `main` 与同步分支头之间
  **完全一致** ⇒ 本次合入没有引入任何依赖变化；10 与 11 的差异来自两次运行之间
  漏洞库自身的数据漂移，两侧可见的 critical / high 集合相同。

### ④ 台账与迁移预检

- 台账 465 行、无重复模块 ID、`sync.mjs --check` 通过（台账与蓝图一致）。
- 本次合入带来 19 个迁移文件，**全部是新建**；唯一的 CHECK 约束
  （`platform_flag_setting_shape`）作用在同一迁移里新建的表上 ⇒ 没有「收紧既有表」的形状。

## 涉及文件

- `backend/services/identity/src/test/java/com/starcon/identity/PlatformFlagIntegrationTest.java`
  —— 基线之外的失败用例所在处（集成侧交付内容）
- `backend/services/identity/src/main/resources/db/migration/V324__platform_feature_flags.sql`
  —— `announcement_from timestamptz`，微秒精度的证据
- `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`
  —— 本轮集成头推进的文件之一，代码逐字节未变
- `apps/web/src/lib/api-proxy.ts`、`packages/ui/src/components/common/command-palette.tsx`
  —— 集成侧原文不符合仓库格式化标准（取用即新增排版债）的证据
- `docs/product/capability-blueprint/module-progress.md` —— 台账，465 行
- `docs/sessions/2026-10/2026-10-24-0237-main-sync-round62-platform-flag-precision-rootcause.md` —— 本篇

## 后续待办

1. **集成侧车道（或人工）修掉** `PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`：
   断言前把 `from`/`to` 截断到微秒（与 `timestamptz` 的存储精度对齐），
   或改用容差比较。**解除条件就是这一条** —— 它修好之前，本任务每一轮都会在同一处阻塞。
2. 集成侧后续若再推批量格式化改动，**同步前逐文件用仓库自己的 Prettier 比对合规性**，
   取合规的一侧；本轮集成头那 6 个 TS/TSX 文件就是反例。
3. 集成分支的格式化改写目前用的是约 80 列宽，与仓库标准的 100 列不一致 ——
   已在 `docs/backlog/` 记录，同步侧不再重复处理。
4. Java 作业在 identity 模块失败后，reactor 会把 `catalog`/`channel`/`warehouse`/`trade`/`crm`/`finance`
   全部 SKIPPED ⇒ 本次交付的 4 项财务能力（坏账与核销、付款计划、核销、结算单管理）
   的集成测试**在 CI 里一次都没跑过**；上一条解除后它们才会有 CI 证据。
