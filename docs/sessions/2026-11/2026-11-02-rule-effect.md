# 2026-11-02: 18.3.2.2 规则效果（触发次数 / 成功率 / 平均耗时 / 节省工时 / 误触发 / 优化建议）

## 摘要

蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.2「执行」→ 18.3.2.2「规则效果」落地。

车道分支 `feat/18-3-2-2`，迁移版本号由调度者预分配为 **V428**
（`backend/services/platform/.../V428__automation_rule_effect.sql` +
`backend/services/identity/.../V428__platform_automation_rule_effect_permission.sql`，
与 V419 / V420 / V421 / V422 同一「platform + identity 同号」的登记口径）。

蓝图原文：「**职责**：统计规则效果：触发次数、成功率、平均耗时、节省工时预估、误触发反馈与规则优化建议。
**输入**：执行日志聚合。 **输出**：规则效果报表。
**关系**：上游 = 18.3.2.1；下游 = 18.3.1.1（优化依据）。
**误触发率高的规则要能快速定位并关闭**，自动化失控比没有自动化更危险。」

交付物：三张新表、13 个端点（`/platform/automation/effect`）、一个真实工作区页面（`/automations/effects`）、
32 个后端集成测试 + 27 个前端测试。

## 🔴 这是一条**续做**车道：上一轮失联，但工作区里留了半成品

上一轮（本车道第 1 次派发）的心跳在 2026-11-01T17:36Z 失联，工作区里留下 4 个**未提交**的文件：

- `V428__automation_rule_effect.sql`（475 行）
- `V428__platform_automation_rule_effect_permission.sql`（132 行）
- `AutomationRuleEffectModels.java`（550 行）
- `AutomationRuleEffectService.java`（1444 行）

**这轮没有重写它们** —— 读完、接着写。下面记的是在它们之上**具体补了什么**。

## 决策 / 结论

### 1. 🔴 半成品里的两处**真 bug**（不是风格问题，是跑起来就炸）

**（a）编译不过**：服务层引用了 `AutomationRuleRunOutcome`，但它定义在
`AutomationRuleRunModels`（18.3.2.1 的传输模型）里，而半成品只静态导入了
`AutomationRuleEffectModels.*` ⇒ 10 处引用里 2 处找不到符号。
补了 `import com.starcon.platform.AutomationRuleRunModels.AutomationRuleRunOutcome;`。

**（b）🔴 `fetchOne(...).get(0, org.jooq.Record.class)` 会抛 `DataTypeException`** ——
这是半成品里最危险的一处，因为它**编译得过、只会在运行时炸**，而且是全模块
10 处同款写法。`sql.fetchOne(sql)` 返回的**本来就是** `Record`，
再 `.get(0, Record.class)` 等于要求 jOOQ 把「第 0 列的值」转成 `Record` ——
实测（jOOQ 3.19.26）抛：

```
org.jooq.exception.DataTypeException: No Converter found for types java.lang.Long and org.jooq.Record
```

⇒ 概览 / 明细 / 反馈 / 建议 / 刷新建议**每一个端点都会 500**。
**这个写法在整个 backend 里只有半成品这一处**（其余 8 个服务 0 处），
所以它是半产物独有的笔误，不是本仓惯例。10 处全部改回直接用 `Record`
（`row.get("列名", 类型.class)`）。

### 2. 🔴 `savedMinutes` 的 `coalesce(..., 0)` 会把「没评估过」说成「省了 0 分钟」

半成品的概览 SQL 写的是 `coalesce(sum(...), 0)` ⇒ 没有任何规则登记口径时，
合计是 **0**。但模块自己的模型文档写的是「**没有口径就是 null，不是 0**」，
而服务层的能力声明里也写着「没有口径的规则，节省工时就是**空**（不是 0）」——
**代码与自己声明的口径不一致**。

改成 `sum(...)`（不加 coalesce）：

- 一条规则都没登记口径 ⇒ `sum` 为 NULL ⇒ 合计是**空**（「从没评估过」）；
- 有口径但一次都没成功 ⇒ 那行存在 ⇒ `sum = 0`（「真的 0」，与「没评估过」是两件事）。

前端对应地分成两个函数：`formatSavedMinutes(minutes, hasBaseline)` ——
`hasBaseline` 为 false 时显示「—（未登记口径）」，为 true 时才显示分钟数。

### 3. 🔴 反馈与建议的 `status` 筛选器原本是**假的**

半成品的枚举里有 `ACCEPTED` / `DISMISSED`（反馈）和 `ADOPTED` / `IGNORED`（建议），
列表端点也支持按它们筛选、模型也返回它们 —— 但**没有任何端点能把状态改成这些值**：
登记时写死 `'OPEN'`。⇒ 那几个筛选器永远筛不出东西 = **假筛选**（硬性要求 1 明令禁止）。

补了两个写端点（服务层 + Controller + 前端按钮 + 测试）：

| 端点 | 权限码 | 作用 |
| --- | --- | --- |
| `PUT /feedbacks/{uuid}/status?status=ACCEPTED\|DISMISSED` | `.feedback` | 处理一条反馈 |
| `PUT /advices/{uuid}/status?status=ADOPTED\|IGNORED` | `.configure` | 处理一条建议 |

🔴 **处理状态不影响误触发率**：一条被「已忽略」的反馈**仍然是发生过的误触发**，
把它从误触发率里抹掉等于让报表说谎 ⇒ 状态与「是否计入分子」是两件事，分开实现。

🔴 **状态与 `active` 也是两件事**：`status` 是**人的决定**，`active` 是「按当前聚合
这条建议还成不成立」。系统重新生成时**只改 `active`，不覆盖 `status`** ——
这正是半成品 `refreshAdvices` 里那句「已经被人处理过的建议不会被覆盖状态」的落点；
没有写端点时那句话是空话。

### 4. 🔴 只读上游：18.3.2.1 与 18.3.1.1 的表一个字都不改

- **逐步明细（`_step`）不参与聚合** —— 它是「一次执行内部」的粒度，
  混进来会把执行次数放大好几倍，成功率就成了假的。聚合只读 `platform_automation_rule_run`。
- **规则档案只读**取规则名与事件类型。
- 有一条测试专门盯这件事：走一遍本模块的**全部写入口**（口径 / 反馈 / 建议 / 重新生成建议），
  然后断言 18.3.2.1 的执行台账**行数与总耗时一个都没变**、18.3.1.1 的规则数也没变。

### 5. 🔴 本模块**不做**「关闭规则」动作

蓝图说「误触发率高的规则要能**快速定位**并关闭」—— 但「**关闭**」这个动作归
**下游 18.3.1.1 规则档案**（那里有 enable / disable 端点）。

本模块只负责「快速定位」：规则 id + 规则名快照 + 指向规则档案的引用 `target_ref`，
`target_module` 由 DB 层 CHECK 钉死恒为 `18.3.1.1`。**页面上不提供「关闭规则」按钮** ——
点了必然失败的按钮会让运营以为「这里就能关规则」。
api-proxy 白名单也**刻意不放行** `automation/effect/rules/{uuid}/disable`。

### 6. 🔴 统计的是「记录」，不是「真实执行」

18.3.2「执行引擎」**尚未交付** ⇒ 现阶段 run 行绝大多数是人工登记或「未执行」的重跑请求。

- 能力声明**如实答「未交付」**并点名缺在 18.3.2；
- 前端顶部**常驻提示条**写明「触发次数 = 登记在执行台账里的行数」；
- 「未执行」单列一档，**既不算成功也不算失败**，也**不进**成功率的母。

### 7. 三张表：口径 / 反馈 / 建议

- **`baseline`**（口径）：`manual_minutes_per_run numeric(19,6)` + `baseline_source`（受控词表）
  + `baseline_note`（怎么算出来的）。DB 层 CHECK 钉死「POLICY 也必须有说明」。
  一条规则一条**当前**口径（`UNIQUE(tenant_id, rule_id)` + 服务层 upsert）。
- **`feedback`**（误触发反馈）：带 `rule_id`（真 FK）+ 可选 `run_id`（真 FK +
  **触发器**钉死「run 必须属于同一条规则」—— 复合 FK 做不到，run 表上没有
  `(tenant_id, id, rule_id)` 唯一约束，而那两张表是 18.3.2.1 拥有的、**不能 ALTER**）。
  **没有指向具体 run 的反馈单独统计**（`unlinkedFeedbacks`）且**不进误触发率的分子**，
  但**照样计数并显示** —— 少算的那部分必须让人看见。
- **`advice`**（优化建议）：**带依据**（`evidence` JSON，DB 层 CHECK：空对象 = 没有依据 = 空话）、
  `target_module` 恒为 18.3.1.1、幂等重生成（`source='SYSTEM'` 的部分唯一索引）。

三张表全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + **租户触发器兜底**
（`app.current_tenant` 缺失或与写入值不符 ⇒ 当场拒）+ `GRANT SELECT,INSERT,UPDATE`
（这三张表**不是**只追加台账 —— 口径要能改、建议与反馈的状态要能改）。

### 8. 命名空间与白名单

端点挂 `/platform/automation/effect`（**斜杠**），兄弟模块用**连字符**
（`automation-rules` / `automation-rule-editor`）⇒ 天然互斥。
api-proxy 新增一条独立 `platform/` 元素，插入锚点 = **18.3.2.1 执行日志那条之后**
（与同批其它车道锚点不同 ⇒ 并入时 git 能干净求并），既有条目**一字未改、不重排**。

## 关键 trade-off

- **口径表 vs 硬编码常量**：蓝图只说「节省工时预估」，没说怎么估。
  选了「落一张口径表、由租户自己登记」—— 多一张表、多两个端点、多一次前端填写，
  换来的是**那个数字能用于决策**。硬编码「一次省 3 分钟」是 mock，且换条规则就是错的。
- **反馈/建议的状态端点要不要做**：严格按蓝图原文，「误触发反馈」只要求「反馈」，
  状态更新可以算「多做」。但**留着假筛选器更糟** —— 硬性要求 1 明令禁止假筛选，
  且 `refreshAdvices` 里「不覆盖人的决定」那句话在没有写端点时是空话 ⇒ 补上。
- **`savedMinutes` 空 vs 0**：选了空。理由是「按 0 分钟算」会把「从没评估过」
  说成「评估结果是省了 0 分钟」；但**有口径且零成功**仍然是 0（那是真的 0）。

## 文件清单

**新增（未跟踪 → 已提交）**

- `backend/services/platform/src/main/resources/db/migration/V428__automation_rule_effect.sql`
- `backend/services/identity/src/main/resources/db/migration/V428__platform_automation_rule_effect_permission.sql`
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEffectModels.java`（半成品续做）
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEffectService.java`（半成品续做）
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEffectController.java`（**本轮新写**）
- `backend/services/platform/src/test/java/com/starcon/platform/AutomationRuleEffectIntegrationTest.java`（**本轮新写**，23 例）
- `backend/services/platform/src/test/java/com/starcon/platform/AutomationRuleEffectTenancyIntegrationTest.java`（**本轮新写**，9 例）
- `apps/web/src/app/automations/effects/page.tsx`（**本轮新写**）
- `apps/web/src/components/automations/automation-rule-effect-model.ts`（**本轮新写**）
- `apps/web/src/components/automations/automation-rule-effect-workspace.tsx`（**本轮新写**）
- `apps/web/src/components/automations/automation-rule-effect-model.test.ts`（**本轮新写**，21 例）
- `apps/web/src/app/api/platform/automation-rule-effect-allowlist.test.ts`（**本轮新写**，6 例）

**修改**

- `apps/web/src/lib/api-proxy.ts`（+ 一条独立 `platform/` 元素，既有条目一字未改）
- `packages/api-contracts/src/contracts/platform.ts`、`packages/api-contracts/src/platform.openapi.json`（codegen 产物，**未手改**）
- `docs/product/capability-blueprint/module-progress.md`（**只改 18.3.2.2 那一行**）

## 验证

- L0 编译闸：`./mvnw -q compile` → **EXIT=0**
- L1 模块闸：`./mvnw -pl services/platform -am test -Dtest='AutomationRuleEffect*' …`
  → **Tests run: 32, Failures: 0, Errors: 0** / BUILD SUCCESS
  （23 + 9；真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）
- 前端：`pnpm vitest run <本模块两个测试文件>` → **27 例全绿**
- `pnpm typecheck` → 3/3 successful；`pnpm lint` → 3/3 successful
- `pnpm exec prettier --check`（本车道改动文件）→ 全绿
- `spotless:check`（JDK 21）→ 本车道 4 个 Java 文件**零违规**
  （闸里报的 3 个文件 `PaymentFundsIntegrationTest` / `IdentityIntegrationTest` /
  `PageFavoriteRulesTest` 是**存量违规**，本车道未碰）

## 后续待办

- ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
  （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
- ⚠️ **下游缺口（如实标注在能力声明里）**：
  18.3.2「执行引擎」**未交付** ⇒ 本模块统计的是记录不是真实执行；
  「关闭规则」归 18.3.1.1 规则档案，本模块只做定位。
- ⏭️ 后续可考虑（不在本模块范围）：把「误触发率」接到 1 工作台告警；
  给建议加「按严重度排序」的默认视图（当前只有按规则排序）。
