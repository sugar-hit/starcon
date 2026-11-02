# 18.4.2.3 异常诊断（2026-11-02）—— 结论 + 依据 + 建议动作，仅诊断不执行

> 车道：`feat/18-4-2-3`（worktree `integration/.worktrees/18.4.2.3`）
> 阶段：D7 · L3 锚点 18.4.2 · 落地后端服务 = **platform**
> 迁移版本号：**V429**（调度器预分配）
> 基线：`integration/module-dev`
> ⚠️ 本车道是**续做**：上一任 worker 留下 5028 行未提交代码就断线了，
> 本轮先把那 10 个文件**原样提交**（sha `197ba35a`）保住成果，再逐条修缺陷。

---

## 一、这个模块做什么

蓝图 18.4.2.3 原文：「**AI 异常诊断**：对库存异常、利润异常、发货延迟、同步失败、广告亏损等给出
可能原因分析（自动关联相关数据）与处置建议。输入：异常事件 + 关联数据（订单、库存、成本、历史记录）。
输出：诊断结论 + 依据 + 建议动作。关系：上游 = 1 工作台待办、各域异常；下游 = 对应处理页。
**诊断要给依据**（"因为 SKU X 的头程成本上涨 18%"），不能只给结论。」

---

## 二、决策摘要（本车道最关键的六条）

### 1. 🔴 依据是一等公民，且**可下钻**

`platform_diagnosis_evidence` 每行带 `fact_service / fact_table / fact_field / fact_value /
fact_value_text / fact_unit / fact_ref / snapshot_at / explanation` ——
拿着 `fact_ref` 能回到那条事实行。DB 层 CHECK 钉死两条：

- 「数值与文字至少有一个」—— 两个都空的依据什么也证明不了；
- `evidence_count >= conclusion_count` —— **每条结论至少一条依据**。

服务层只写**有事实支撑**的结论：**读不到支撑事实 ⇒ 那一条根本不出结论**，
而不是出一条没有依据的结论。

### 2. 🔴 「读不到」与「没有结论」是两件事

一次运行把读到的事实源（`sources_read`）与读不到的（`sources_unavailable`）**分列落库**。
读不到的那几格不生成结论 ⇒ 页面必须分开显示，否则「没查出来」会被读成「没问题」。
前端 `runReadState()` 专守这一条，并有用例断言「读不到时**不得**说成『没有命中规则』」。

### 3. 🔴 impact 是**幅度**，方向由 impact_text 承担

库层 CHECK 是 `impact >= 0`。成交均价下跌时服务层原先传的是**带符号差值**（负数）⇒ 插入被拒（409）。
改为传跌幅**幅度**，方向由 `impact_text` 的带符号百分比（如 `-40.00%`）承担 ——
两者都显示才不会把「涨了 18%」说成「跌了 18%」。

### 4. 🔴 只有两条异常来源，且**如实说**没有工作台待办

蓝图关系句写「上游 = 1 工作台待办、各域异常」，但**这两张表都不存在**
（卷 01 / 13 / 14 / 16 未交付）⇒ 异常事件来源只有：

- `SUBMITTED`：调用方经 `POST /platform/ai/diagnosis/events` 提交；
- `PLATFORM_INTERNAL`：从平台自己的三张失败源事实表聚合
  （V413 投递记录 / V414 接口调用日志 / V422 规则执行）——**只读，一个字都不改**。

能力声明里把这一格如实标成「未接通，归 1」，**不假装能读**。

### 5. 🔴 没有 EXECUTED 状态：本模块**只给建议**

状态机只有 `OPEN → DIAGNOSED → RESOLVED`。处置建议写清**做什么**（`recommendation`）、
**指向哪个处理页**（`action_ref`）、**归谁**（`action_owner`），
但补货归 6 采购、调价归 5 定价、停广告归 14 广告 —— 跨服务写它们的库是被禁止的。
「标记已处置」只**记下人的决定**，处置说明**必填**（不然「为什么关掉」无从追问）。

### 6. 🔴 本仓没有 LLM ⇒ 归因是**确定性、可解释**的

22 条规则写死在服务层，命中条件、依据表、阈值逐条报在 `/capabilities` 里；
每次运行把 `rule_version` 落库 ⇒ 半年后回看，规则改了也能说清「当时按哪一版判的」。
**AI 大模型推理那一维如实答「没有」并归 18.4 卷。**

---

## 三、关键 trade-off

| 决策 | 取舍 | 代价 / 理由 |
| --- | --- | --- |
| **事件详情与运行详情拆成两个端点** | 多一次请求 | 内嵌「最近一次运行」需要契约表达「可空的嵌套对象」—— springdoc 对 `$ref` 会丢掉 `nullable`（实测），契约会把「还没诊断过」谎报成「有运行」⇒ 拆开后 `runs` 为空即「没诊断过」，契约不再撒谎 |
| **上游端口取不到就 503**，不用空集合冒充 | 诊断会因为上游挂掉而失败 | 空集合在页面上会长成「这个 SKU 没问题」，而真相是「这一格没读出来」 |
| **依据缺失就不出结论** | 会少给结论 | 没依据的结论比没结论更危险 —— 它会被当成已排查过的证据 |
| **impact 传幅度、方向走 impact_text** | 要看两个字段才知道涨跌 | 库层 `impact >= 0` 的 CHECK 是刻意的不变式；传带符号差值会直接被 DB 拒（409） |
| **同步幂等靠部分唯一索引** | 同一失败源只登记一次 | 重复同步不刷屏；`ON CONFLICT DO NOTHING` 而不是「插完再捕获冲突」（PostgreSQL 里失败语句会毒化整个事务） |
| **白名单只放行本模块 9 条真实路径** | 少放行一些形状 | 放行后端不存在的映射比 404 更糟 —— 界面会以为「点一下就能改价」 |

---

## 四、本轮修掉的 5 个真实缺陷（上一任 worker 没跑过测试）

上一任的代码**能编译**，但跑不过自己的集成测试。本轮逐条修：

1. **`reported_unit` 写 null 撞 NOT NULL**（`submit` + `syncInternalFailures` 两处）
   ⇒ `DataIntegrityViolationException` 被 `ApiErrorHandler` 翻成 409「与既有记录冲突」，
   **掩盖了真实原因**。库里的口径是 `NOT NULL DEFAULT ''`，服务层就传 `''`。
2. **诊断运行行插得太晚**：`platform_diagnosis_conclusion` 对 `platform_diagnosis_run`
   有外键 `(tenant_id, run_id)`，而运行行原先在结论**之后**才插 ⇒ 插结论直接撞外键（409）。
   改为运行行先落库；依据条数在写库前由内存里已组装好的 `findings` 算出（不再靠插完再 `++`）。
3. **事件详情 NPE**：还没跑过诊断的事件 `fetchOne(...)` 返回 null，代码却直接 `.get("id")`
   ⇒ 事件详情页整个 500。改为先判空。
4. **成交均价下跌的 impact 为负** ⇒ 撞库层 CHECK `impact >= 0`。见上文决策 3。
5. **租户夹具 seed SQL 多一个空串**：values 列表 17 个表达式对 16 个列
   ⇒ 「INSERT has more expressions than target columns」，租户测试全红。

---

## 五、文件清单

### 后端（platform 服务）

- `backend/services/platform/src/main/resources/db/migration/V429__ai_diagnosis.sql` —— 四表 + RLS（ENABLE **与 FORCE**）+ `isolation` 策略 + 租户触发器 + GRANT + 发号序列
- `backend/services/platform/src/main/java/com/starcon/platform/AiDiagnosisModels.java` —— 19 个传输模型（`AiDiagnosis*` 前缀，避免与别服务 schema 撞名）
- `backend/services/platform/src/main/java/com/starcon/platform/AiDiagnosisService.java` —— 服务层（jOOQ 显式 SQL）+ 22 条确定性归因规则
- `backend/services/platform/src/main/java/com/starcon/platform/AiDiagnosisController.java` —— 9 条端点，挂 `/platform/ai/diagnosis`
- `backend/services/platform/src/main/java/com/starcon/platform/DiagnosisPurchasePort.java` —— 6 采购（trade 8085）采购**成本**事实
- `backend/services/platform/src/main/java/com/starcon/platform/DiagnosisTimelinessPort.java` —— 9 订单履约（trade 8085）时效事实
- `backend/services/platform/src/main/java/com/starcon/platform/DiagnosisAdPort.java` —— 02 渠道（channel 8083）广告指标
- `backend/services/platform/src/test/java/com/starcon/platform/AiDiagnosisIntegrationTest.java` —— 12 例
- `backend/services/platform/src/test/java/com/starcon/platform/AiDiagnosisTenancyIntegrationTest.java` —— 7 例（三条隔离断言）

复用（未改）：`WarehouseInventoryPort`（7 库存）、`ForecastSalesPort`（13 分析）。

### 后端（identity 服务，权限码）

- `backend/services/identity/src/main/resources/db/migration/V429__platform_ai_diagnosis_permission.sql` —— `platform.ai_diagnosis.read / .create / .update`

### 前端

- `apps/web/src/app/ai/diagnosis/page.tsx` —— 路由 `/ai/diagnosis`
- `apps/web/src/components/ai/ai-diagnosis-model.ts` —— 模型层 + 展示口径（依据摊开 / 读数状态分档 / 依据硬度 / 未接通口径）
- `apps/web/src/components/ai/ai-diagnosis-workspace.tsx` —— 真实工作区（事件列表 + 人工登记 + 详情 + 能力声明）
- `apps/web/src/components/ai/ai-diagnosis-model.test.ts` —— 16 例
- `apps/web/src/app/api/platform/ai-diagnosis-allowlist.test.ts` —— 6 例（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `platform/` services 元素**（既有条目一字未改、不重排；插在数组末尾，与同批其它车道锚点相距 800 行以上）

### 契约 / 台账 / 会话

- `packages/api-contracts/src/contracts/platform.ts` + `packages/api-contracts/src/platform.openapi.json` —— codegen 产物（**未手改**）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 18.4.2.3 这一行**的状态与证据
- `docs/sessions/2026-11/2026-11-02-ai-diagnosis-anomaly-diagnosis.md` —— 本文件

---

## 六、验证（真实输出）

```bash
# L0 编译闸
cd backend && ./mvnw -q -pl services/platform -am compile        # ✅ exit 0，无 ERROR

# L0 类型闸
pnpm typecheck                                                   # ✅ Tasks: 3 successful, 3 total

# L0 格式自检（只查本车道改动过的文件）
pnpm exec prettier --check <本车道的 6 个前端文件>
# ✅ All matched files use Prettier code style!

# L1 模块闸（后端，只跑本车道新增的测试类）
cd backend && ./mvnw -pl services/platform -am test \
    -Dtest='AiDiagnosis*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
# ✅ Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
#   ├─ AiDiagnosisIntegrationTest        12 例
#   └─ AiDiagnosisTenancyIntegrationTest  7 例

# L1 前端
pnpm vitest run apps/web/src/components/ai/ai-diagnosis-model.test.ts \
                 apps/web/src/app/api/platform/ai-diagnosis-allowlist.test.ts
# ✅ Test Files 2 passed / Tests 22 passed

# api-proxy 全量 allowlist 回归（因为改了共享白名单）
pnpm vitest run apps/web/src/app/api/trade
# ✅ Test Files 71 passed / Tests 394 passed

# 契约重新生成（codegen 产物，禁止手改）
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
# ✅ Generated 7791 DTOs in 9 per-service files
#    git status packages/ ⇒ 只有 platform.ts 与 platform.openapi.json 变化
#    （其余 8 个服务的契约逐字未动 ⇒ 本车道没给别的服务带来漂移）
```

**多租户三条断言**（`AiDiagnosisTenancyIntegrationTest`，真实 PostgreSQL 16 + FORCE RLS）：

1. 两租户互不可见 —— 列表 + **按主键直查** + 事务外零行；
2. 伪造租户上下文无效 —— 无 `app.current_tenant` 时写入被**租户触发器**挡下，
   伪造 `X-Tenant-Id` 头时行**仍然写入成功但落在会话租户名下**（不是只断言「看不见」）；
3. 连接池归还后不串租户。

外加两条独立断言：四张表都是 **FORCE** RLS（不是只 ENABLE）+ 每张表都有 `isolation` 策略 +
`tenant_id NOT NULL`；依据表的 CHECK（「数值与文字至少有一个」）在库层成立。

---

## 七、后续待办

1. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
   （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
2. **工作台待办 / 各域异常事件表**（卷 01 / 13 / 14 / 16）落地后，
   本模块应改成从它们读异常事件，而不是只接受「调用方提交 + 内部失败源聚合」。
3. **AI / LLM 供应商适配器**（卷 18.4 共用通道）接通后，
   本模块可以在确定性归因之外**补充**模型推断 —— 但**依据必须仍然来自事实表**，
   模型不许自己编依据。
4. **处置动作的下游通道**尚未约定：建议被采纳后，下游**怎么取**（事件？轮询？）
   本模块没有实现，也不该由本模块实现（那是属主服务的写入所有权）。
