# batch-131 —— 18.4.2.3 异常诊断 / 18.4.3.2 效果评估 两条 platform 车道的首次并入交付

> 批次：batch-131（接 batch-130 往后排）
> 集成头：`integration/module-dev`
> 本批并入：`18.4.2.3`（V429）+ `18.4.3.2`（V430）
> 车道：`feat/18-4-2-3` / `feat/18-4-3-2`
> 每条模块自己的完整说明见：
> `docs/sessions/2026-11/2026-11-02-ai-diagnosis-anomaly-diagnosis.md`、
> `docs/sessions/2026-11/2026-11-02-ai-effect-evaluation-frontend.md`

---

## 模块 18.4.2.3 异常诊断

**这个功能做什么**：运营在 `/ai/diagnosis` 看到「库存异常、利润异常、发货延迟、同步失败、广告亏损」
等异常事件，逐条看到**机器给出的可能原因**、**支撑这条原因的事实依据**（哪张表的哪一列、什么值、什么时间点），
以及**建议动作**（做什么、指向哪个处理页、归谁）。本模块**只诊断不执行** —— 不替用户改价、不替用户补货。

**技术落地**：

- 迁移：
  - `backend/services/platform/src/main/resources/db/migration/V429__ai_diagnosis.sql`
    —— 四张表 `platform_diagnosis_event` / `platform_diagnosis_run` / `platform_diagnosis_conclusion` /
    `platform_diagnosis_evidence`；RLS **ENABLE 且 FORCE** + `isolation` 策略 + 租户触发器 + GRANT + 发号序列
  - `backend/services/identity/src/main/resources/db/migration/V429__platform_ai_diagnosis_permission.sql`
    —— 权限码 `platform.ai_diagnosis.read / .create / .update`
- 端点：`/platform/ai/diagnosis` 共 9 条 ——
  `GET /capabilities`、`GET /overview`、`GET /events`、`POST /events`、`GET /events/{id}`、
  `GET /runs/{id}`、`POST /events/{id}/diagnose`、`POST /events/{id}/resolve`、`POST /events/sync`
- 页面：`/ai/diagnosis` —— 事件列表 + 人工登记 + 事件详情 + 能力声明；错误态 / 空态 / 加载态齐全
- 测试：`AiDiagnosisIntegrationTest`（12 例）+ `AiDiagnosisTenancyIntegrationTest`（7 例，含三条隔离断言）；
  前端 `ai-diagnosis-model.test.ts`（16 例）+ `ai-diagnosis-allowlist.test.ts`（6 例）

**关键设计决策**：

1. **依据是一等公民且可下钻** —— `platform_diagnosis_evidence` 每行带
   `fact_service / fact_table / fact_field / fact_value / fact_unit / fact_ref / snapshot_at`，
   拿着 `fact_ref` 能回到那条事实行。库层两条 CHECK 钉死：「数值与文字至少有一个」、
   `evidence_count >= conclusion_count`（**每条结论至少一条依据**）。
   服务层只写**有事实支撑**的结论 —— 读不到支撑事实 ⇒ 那一条**根本不出结论**。
2. **「读不到」与「没有结论」分列落库** —— `sources_read` 与 `sources_unavailable` 分开记，
   页面必须分开显示，否则「没查出来」会被读成「没问题」。
3. **`impact` 是幅度、方向由 `impact_text` 承担** —— 库层 CHECK 是 `impact >= 0`，
   成交均价下跌时传**跌幅幅度**（负数差值会被 DB 直接拒成 409），方向交给带符号的 `impact_text`。
4. **只有两条异常来源、如实说没有工作台待办** —— 蓝图写「上游 = 1 工作台待办、各域异常」，
   但卷 01 / 13 / 14 / 16 未交付 ⇒ 异常来源只有「调用方提交」与「平台内部三张失败源事实表聚合（只读）」，
   能力声明里如实标成「未接通，归 1」，**不假装能读**。
5. **没有 `EXECUTED` 状态** —— 状态机只有 `OPEN → DIAGNOSED → RESOLVED`；
   处置建议写清做什么 / 指向哪个处理页 / 归谁，但跨服务写别家库是被禁止的。
6. **本仓没有 LLM ⇒ 归因是确定性、可解释的** —— 22 条规则写死在服务层，
   命中条件、依据表、阈值逐条报在 `/capabilities`；每次运行把 `rule_version` 落库 ⇒
   半年后回看说得清「当时按哪一版判的」。

---

## 模块 18.4.3.2 效果评估

**这个功能做什么**：运营在 `/ai/effects` 看「AI 能力到底有没有被用起来」——
采纳率 / 回测误差 / 有用性三块，每块都带**分子分母与口径**；同时可以登记采纳与撤回、登记有用性反馈、
跑回测、设评估阈值、让系统给「建议下线」并记录人工决定。本模块**只出建议不执行下线**。

**技术落地**：

- 迁移：
  - `backend/services/platform/src/main/resources/db/migration/V430__ai_effect_evaluation.sql`
    —— 五张表 `platform_ai_effect_adoption` / `platform_ai_effect_feedback` /
    `platform_ai_effect_backtest` / `platform_ai_effect_threshold` / `platform_ai_effect_suggestion`
  - `backend/services/identity/src/main/resources/db/migration/V430__platform_ai_effect_permission.sql`
- 端点：`/platform/ai/effects` 共 16 条 ——
  `GET /capabilities`、`GET /overview`、`GET|POST /adoptions`、`POST /adoptions/{id}/withdraw`、
  `GET /adoption-report`、`GET|POST /feedback`、`POST /feedback/{id}/withdraw`、
  `GET /backtests`、`POST /backtests/run`、`GET|POST /thresholds`、
  `GET /suggestions`、`POST /suggestions/generate`、`POST /suggestions/{id}/decision`
- 页面：`/ai/effects` —— 八个页签的真实工作区，三态齐全
- 测试：`AiEffectEvaluationIntegrationTest` + `AiEffectEvaluationTenancyIntegrationTest`；
  前端 `ai-effect-model.test.ts`（30 例）+ `ai-effect-allowlist.test.ts`（4 例）

**关键设计决策**：

1. **前端只按后端真实形状建模** —— 16 个端点 ⇒ 13 条路径，模型层逐条对齐，**未凭空设计**。
2. **比率全程不碰浮点** —— 后端 `NUMERIC` 十进制文本 ⇒ 前端用**字符串移位**算百分比
   （`Number(x) * 100` 会把 `0.07` 变成 `7.000000000000001`，而比率是**判定口径**）；
   `null`（没有分母）与 `0`（真的是 0%）必须分开 —— 前者显示「—」，绝不画成 0% 的柱子。
3. **阈值端点 `PUT` → `POST`** —— 浏览器侧 `apps/web/src/app/api/platform/[...path]/route.ts`
   刻意不导出 PUT（该文件归 18.1.1.1，未改动）⇒ 挂 PUT 端点等于让浏览器根本调不到。
   改用 platform 服务既有的 upsert 约定（配额 / 模型 / 敏感策略都走 POST），语义是「同一条覆盖」
   而非「整份替换」，与阈值「留空沿用当前值」的局部更新语义一致。
4. **白名单负向样本只取本模块自己的前缀** —— `platform/ai/…` 是兄弟车道共用命名空间，
   拿兄弟的前缀当「未交付形状」是假红；`suggestions/{uuid}/disable` **刻意不放行**（本仓没有下线端点）。
5. **契约只提交 platform 两个文件** —— codegen 对未重建 spec 的其余 8 个服务产生纯键序抖动 ⇒ 逐个还原，
   避免把别的车道的契约冲掉；`platform.openapi.json` 的实际变化只有 `put → post` 三行。

---

## 本批并入过程与两条修复车道

`merge-ready` 一次并入 4 条已落地车道，其中 **2 条成功、2 条因 `apps/web/src/lib/api-proxy.ts`
同锚点冲突失败**：

| 车道 | 结果 | 说明 |
| --- | --- | --- |
| 18.4.2.3 异常诊断 | ✅ 并入 `42077d32` | — |
| 18.4.3.2 效果评估 | ✅ 并入 `8961c48f` | — |
| 5.1.1.1 价目表列表 | ❌ conflict → 修复中 | 与 18.4.2.3 插在同一个数组末尾锚点 |
| 15.3.1.1 客户列表 | ❌ conflict → 已修复待并 | 同上 |

**根因**：`api-proxy.ts` 是多车道共用的累积型白名单文件，几条车道都按任务书把新的 `services[]` 元素
**追加在数组末尾** ⇒ 同一基线上的同位置追加互相重叠 ⇒ 顺序并入时逐个撞。
（根因与两条结构性解法见 `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`，
其中「解法 A：给 api-proxy.ts 配并集合并驱动器」已翻转为**按现状禁止**，不得实施。）

**本轮处置**：不熔断、不重做，按「续做」派修复任务 —— 两条车道各自**在原分支原 worktree** 上
把白名单元素**整块搬到互不重叠的锚点**：

| 车道 | 新锚点 | 元素最终位置（集成头） |
| --- | --- | --- |
| 5.1.1.1 价目表列表 | `prefix: 'catalog/'` 且首 token `price-anomaly-alerts` 的那条元素之后 | ≈2711 起 |
| 15.3.1.1 客户列表 | `prefix: 'crm/'` 且首 token `customer-contacts`（15.3.2.2）的那条元素之后 | 10167–10206 |

修复任务同时补齐了 **5.1.1.1 缺失的 api-proxy 白名单回归测试**
（`apps/web/src/app/api/catalog/price-list-allowlist.test.ts`），
并按协议硬性要求跑了**全量 allowlist 回归**（15.3.1.1 实测 291 文件 / 1638 用例全绿）。

---

## L2 批量验证结果

（本节在 L2 出结论后填写）

---

## 后续待办

1. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` 刻意不登记（多车道并发改同一文件必然冲突）
   ⇒ **卷 18.4 的 5 个已交付页面**（`/ai/usage` `/ai/forecast` `/ai/templates` `/ai/diagnosis` `/ai/effects`）
   目前**在导航 / 工作区 Tab / 搜索里都不可见**（页面本身能直接输 URL 访问）。
   该文件由**调度者统一处理**，`nav-routes.test.ts` 现有不变量**不会**因此变红
   （它是「已登记的页面必须有菜单入口」单向校验，不是「每个页面都必须登记」）。
2. **卷 01 / 13 / 14 / 16 落地后**，18.4.2.3 应改成从「工作台待办 + 各域异常事件表」读异常事件。
3. **AI / LLM 供应商适配器**接通后，18.4.2.3 可在确定性归因之外补充模型推断，
   但**依据必须仍然来自事实表** —— 模型不许自己编依据。
4. **18.4.3.2 的采纳率分母目前为 0** —— 上游 18.4 的其它 AI 能力（对话助手 / 智能查询 / 异常诊断）
   未接通 ⇒ 用量台账里还没有真实调用记录 ⇒ 比率恒为 `null`（页面如实显示「—」）。
5. **兄弟模块 18.3.3.1 / 18.3.2.1 的后端有 `PUT` 端点**，但浏览器侧 platform 代理不导出 PUT
   ⇒ 那两个端点前端同样调不到（本车道未改动，只记录）。
