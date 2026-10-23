# batch-66 · 卷 15 客户与客服 + 卷 16 合规风控（4 条车道并入 + L2 全绿）

- **日期**：2026-10-22
- **分支**：`integration/module-dev`（集成分支）
- **批次号**：batch-66（接 batch-65 往后排）
- **模式**：full（当前 < `TOKEN_HORIZON` = 2026-10-25T00:00:00+0800；`LANES=4`，节流闸 50 分钟）
- **覆盖模块**：15.1.1.3 消息分类 · 15.2.1.3 差评处理 · 15.5.2.1 关怀与催办 · 16.1.1.2 绩效指标监控

## 批次构成

| 模块     | 名称         | 归属服务                        | 迁移                                   | 分支 → 并入 commit                                            |
| -------- | ------------ | ------------------------------- | -------------------------------------- | ------------------------------------------------------------- |
| 15.1.1.3 | 消息分类     | crm（8086 / `crm_app`）         | `V301__crm_message_classification.sql` | `feat/15-1-1-3` `df0f72a2` → `f4c21467`                       |
| 15.2.1.3 | 差评处理     | crm                             | `V302__crm_negative_review.sql`        | `feat/15-2-1-3` `481fbd9f` → `e64b5391`                       |
| 15.5.2.1 | 关怀与催办   | crm                             | `V303__crm_care_reminder.sql`          | `feat/15-5-2-1` `d0810726` → `34741f72`                       |
| 16.1.1.2 | 绩效指标监控 | channel（8083 / `channel_app`） | `V304__account_health_performance.sql` | `feat/16-1-1-2` `7b6b861e`+`5ce23a96`+`97b838f5` → `3cbbf834` |

---

## 模块 15.1.1.3 消息分类

**这个功能做什么**：客服打开收件箱，每条买家消息自动带上「售前咨询 / 物流查询 / 退货退款 / 投诉 / 平台合规 / 垃圾信息」分类标签与建议优先级，并且可以人工纠正——纠正会沉淀成可导出的训练样本，分类越用越准。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V301__crm_message_classification.sql` —— **三张新表、零 ALTER / 零 DROP 任何既有表**：`crm_message_classification`（分类结论：标签 / 建议优先级 / 是否可自动回复 / 命中依据）、`crm_message_classification_rule`（关键词 + 适用平台 + 适用消息类型 + 目标标签 + 优先级 + 启停）、`crm_message_classification_feedback`（人工纠正 = 训练样本）。全部 `tenant_id uuid NOT NULL` + ENABLE **与** FORCE RLS + POLICY isolation + `GRANT crm_app` / `starcon_app`，序列单独授权。
- 端点：`/crm/message-classifications/**` —— 12 path / 15 端点（`capabilities` / `options` / `statistics` / `training-samples` / `classify` / `classify-batch` / `feedback` / `rules` CRUD / `classifications` / `messages/{uuid}`）。
- 页面：`/crm/classifications` —— `apps/web/src/app/crm/classifications/page.tsx` + `apps/web/src/components/crm/message-classification-{model,workspace}.ts`。真实工作区：能力声明 → 平台接入状态 → 分类规则配置 → 收件箱分类结论 + 命中依据 → 人工纠正 → 训练样本视图 / 规则命中统计；加载态 / 错误态 / 空态齐全。
- 测试：`MessageClassificationIntegrationTest` 25 例 + `MessageClassificationTenancyIntegrationTest` 4 例 = **后端 29 例**（真实 PostgreSQL 16 Testcontainers，租户隔离三断言实跑）；前端 `message-classification-model.test.ts` + allowlist 反查 = **25 例**。

**关键设计决策**：

1. **纠正与结论分两张表，且纠正 DB 层只追加**（触发器拒绝 UPDATE / DELETE）—— 分类结论可以被重新分类覆盖，但纠正历史不可改，否则「训练样本」随时可被抹掉。
2. **不覆写人工设过的优先级**：`crm_message.priority` 只读；结论同时给 `suggested / manual / effective / conflict` 四个字段，测试断言分类之后 `priority` 仍是人工设的值。自动分类不该偷偷推翻人的判断。
3. **下游未交付一律不编**：15.5.1.1 自动回复、1.3 预警未开发 ⇒ 能力声明里 `available=false` + `ownerModule` + 影响说明；`auto_reply_eligible` 只是本模块的判定结果，**不触发回复、不写预警**。
4. **api-proxy 新增独立元素**（首 token `message-classifications`，锚点固定在 `message-sends` 之后）—— 既有 149 条正则逐条 `new RegExp(pat).test(shape)` 实测 12 个 path shape **零命中**；既有元素一字未动、不重排。

**已知缺口**：平台买家站内信适配器未交付（消息仍只能人工登记）；**没有模型训练调度器**（纠正行可导出，但不会自动学出新规则，不编「已训练」假状态）；规则命中统计是**当前结论**口径而非历史累计。

---

## 模块 15.2.1.3 差评处理

**这个功能做什么**：把差评从「一条评价」变成「一件要办的事」：标记差评原因（产品 / 物流 / 服务 / 误判）、判定这条能不能申诉、联系买家解决后再请求修改，并跟踪解决率。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V302__crm_negative_review.sql` —— **四张新表** `crm_negative_review_{rule,case,action,appeal}`，零 ALTER 既有表、**零外键**（跨表引用一律用触发器，外键会打破同服务既有测试的 TRUNCATE），FORCE RLS + 序列单独授权。
- 端点：`/crm/negative-reviews/**` —— 15 端点（`capabilities` / `shops` / `stats` / `candidates` / `rules` CRUD / 分类 / 动作 / 申诉 submit·submitted·outcome·cancel），含 `PlatformNegativeReviewPort` + `UnavailablePlatformNegativeReviewPort`。
- 页面：`/crm/negative-reviews` —— `apps/web/src/app/crm/negative-reviews/page.tsx` + `apps/web/src/components/crm/negative-review-{model,workspace}.ts`。真实工作区：差评候选 → 标记原因 → 判定可申诉 → 联系买家 → 请求修改 → 申诉任务 → 解决率统计。
- 测试：`NegativeReviewIntegrationTest` 25 例 + `NegativeReviewTenancyIntegrationTest` 5 例 + `NegativeReviewRulesTest` 12 例 = **后端 42 例**；前端 **19 例**（model + allowlist）。

**关键设计决策**：

1. **蓝图加粗句落成服务端状态机，而不是文案**：没走完「联系买家」（没有 `outcome=SUCCEEDED` 的 CONTACT_BUYER 动作）就不允许创建「请求修改 / 申诉任务」，服务端**拒绝并给明确原因、零行写入**；失败的联系不算已联系。理由：直接申诉容易被驳回且消耗申诉配额。
2. **规则真的可配**：`PLATFORM` 维度（差评星级阈值 / 逐平台是否支持申诉 / 是否必须先联系买家 / 申诉配额与周期 / 申诉窗口）× `CAUSE` 维度（逐平台 × 逐原因）。处理单存**判定结论 + 判定依据 + 判定时刻 + 规则版本**。
3. **平台未接入 ⇒ 零行**：联系买家走 `PlatformMessageSendPort`、申诉提交走新的 `PlatformNegativeReviewPort`，两者当前都是 Unavailable 实现，**返回 409 且一条数据都不写**，申诉状态永远不会变成「已提交」，更不会返回假回执；人工转交端点只记录**操作员观察到的真相**。
4. **申诉配额**按（租户 × 平台 × 周期）计数，建任务时扣减、取消时归还。
5. **11 售后未交付 ⇒ 只落转交意图**（reason 必填），表里**没有**售后单据 id 列（用 `information_schema` 断言过），不编下游 id。
6. **解决率服务端算**，响应同时给出分子与分母口径，前端不做任何算术。

**已知缺口**：平台申诉 / 联系买家接口未接入（只能人工转交）；11 售后未交付（只落意图）；平台不支持申诉的店铺如实区分，不一律说能申诉。

---

## 模块 15.5.2.1 关怀与催办

**这个功能做什么**：主动触达买家 —— 发货通知、物流提醒、催付（未付款订单）、催评、售后进度通知、关怀邮件；按规则定时发送，**并由服务端强制**频次上限与退订拦截，触达记录与打开/点击统计可查。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V303__crm_care_reminder.sql` —— **三张新表** `crm_care_reminder_rule`（触达规则：类型 / 平台 / 人群 / 频次上限 / 静默时段 / 话术）/ `crm_care_reminder_task`（触达任务与发送记录）/ `crm_care_reminder_stat`（打开 / 点击事件）。ENABLE + FORCE RLS、GRANT `crm_app` / `starcon_app`、序列单独授权、5 个触发器、零 ALTER 既有表、零外键。
- 端点：`/crm/care-reminders/**` —— `capabilities` / `rules` CRUD + 启停 / `rules/{id}/generate` / `tasks` / `tasks/{id}/dispatch` / `stats` / `stats/events` / `stats/tasks/{id}/events` / `stats/platform-events`。
- 页面：`/crm/care-reminders` —— `apps/web/src/app/crm/care-reminders/page.tsx` + `apps/web/src/components/crm/care-reminder-{model,workspace}.tsx`。
- 测试：`CareReminderIntegrationTest` 18 例 + `CareReminderTenancyIntegrationTest` 4 例 + `CareReminderRulesTest` 7 例 = **后端 29 例**；前端 **21 例**。

**关键设计决策**：

1. **退订名单复用既有事实源**（15.3.2.2 的 `crm_customer_opt_out`），**绝不另建退订表** —— 两个退订事实源必然打架，客户说了「不要再联系我」却仍被触达，是最伤信任的一类事故。`scope='ALL'` 一律排除，`scope='MARKETING'` 排除营销类触达。
2. **新增跨服务只读端口 `OrderPaymentStatusPort`**：实查 trade 的 `sales_order` **没有 payment_status 列**，`GET /trade/sales-orders/list` 的 `paymentStatus` **恒为 null**（12 财务缺口），真正的支付状态观察值在 trade 的 `order_address_check`。按既有端口纪律实现（转发调用方 bearer / 不传租户号 / 3s + 10s 超时 / **取不到显式 503，绝不返回空集合冒充「上游没有数据」**）。
3. **平台回执端点返回 200 + 带原因的结论，不抛异常** —— 抛 `ResponseStatusException` 会走 Spring `sendError`，响应体为空、原因丢失，页面只剩「保存失败」。改用 `Unavailable*Port` 同款写法。
4. **频次上限与退订是服务端两道强制闸**：任一命中就**拒绝并给明确原因**（「客户已退订（ALL）」「24 小时内已触达 2 次，上限 2 次」），不是静默跳过。
5. **催付按平台配置**：不同平台的催付话术与频次上限不同，不允许一个全局规则套所有平台；平台不在白名单里**不许发催付**，如实答「该平台不支持催付」。催评只对**已签收**订单成立（用既有 `SignoffDeliveredOrderPort`）。

**已知缺口**：本仓**没有**可读的「已发货订单集合」端口（channel 揽收签收端口只给已签收档）⇒ 发货通知 / 物流提醒的目标集合是窗口内订单，能力声明标 `available=false` + `ownerModule=10`；12 财务未交付 ⇒ 只落催付任务与「已提醒回款」意图，不写财务单据 id；平台打开 / 点击接口未接入 ⇒ 端点如实答「未接入」且不写数据；15.3.1.x 客户档案未交付 ⇒ 客户标识是逻辑引用。

---

## 模块 16.1.1.2 绩效指标监控

**这个功能做什么**：按店铺盯住平台绩效指标的趋势 —— 订单缺陷率 / 迟发率 / 取消率 / 有效追踪率 / 响应时间 / 账户评级；超阈值就预警，**还能预测「再过几个周期会跌破阈值」**，提前几天知道就能提前改。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V304__account_health_performance.sql` —— **纯新建 4 张表、零 ALTER 任何既有表**：`account_health_performance_metric` / `_alert_rule` / `_alert` / `_forecast`。均 `tenant_id NOT NULL` + ENABLE **与** FORCE RLS + POLICY isolation + GRANT `channel_app` / REVOKE `starcon_app`；零外键，跨表引用用触发器 `guard_account_health_performance_shop_ref` 校验店铺落在本租户店铺目录里。
- 端点：`/channel/performance-metrics/**` —— `capabilities` / `shops`（真分页真筛选）/ `trends` / `alerts` / `forecasts` / `alert-rules` / 按店铺的 `trends`·`metrics`·`alert-evaluations`·`forecasts`·`forecast`。
- 页面：`/compliance/metrics` —— `apps/web/src/app/compliance/metrics/page.tsx` + `apps/web/src/components/performance-metrics/`（独立于 16.1.1.1 的 `/compliance/account`）。
- 测试：`PerformanceMetricRulesTest` 19 例 + `PerformanceMetricIntegrationTest` 17 例 + `PerformanceMetricTenancyIntegrationTest` 3 例 = **后端 39 例**（另连跑上游 `AccountHealth*` 36 例确认新迁移未破坏 16.1.1.1，合计 75 例全绿）；前端 model 23 例 + allowlist 6 例 = **29 例**。

**关键设计决策**：

1. **六项指标分属两个来源，互补切分、零 ALTER 上游**：蓝图列了六项指标，但上游 16.1.1.1 的 `account_health_metric.metric_code` 的 CHECK 只钉死四个码，而本批约定零 ALTER 既有表（改 CHECK 等于破坏已交付的契约与既有测试）。⇒ ODR / 迟发率 / 取消率 / 有效追踪率的时间序列**只读**上游表，响应时间 / 账户评级落在**本模块自有表**，两个表的 metric_code 值域**互补且不重叠**（CHECK 钉死）—— **同一个指标不会有第二个真相来源**。这个双来源事实在能力声明 `metricCatalog`、每条序列的 `sourceModule`、页面「来源」徽章三处如实表达。
2. **量纲不混**：四项比值指标是 `RATIO`（0..1），响应时间是 `HOURS`（0..8760），账户评级是 `SCORE`（0..5，**本模块默认口径、不是平台事实**，可按租户覆盖）。DB 层 CHECK 钉住「量纲必须与指标码相符」，**不会出现用比值的阈值去比小时**。账户评级的 0..5 星制与 16.1.1.1 的内置默认阈值同一纪律。
3. **趋势是区间口径**：上游一行 = 一个区间的一个指标，不是日粒度点。序列按周期终点排序聚起，响应写明 `granularity=PERIOD_RANGE`，**不把区间值伪装成每日值**。
4. **NULL = 量不到，既不是 0 也不是通过**：量不到的点不进已知数、不算通过，并产出一条 **UNKNOWN 预警凭据**（量不到本身就是需要关注的事）；均值分母只算量到的点且分母写清楚；上一个周期量不到 ⇒ **环比不可知**（不是「没变化」）。
5. **阈值解析顺序在服务端算、可审阅**：本模块租户自定义 > 16.1.1.1 的租户阈值 > 模块内置默认；每条预警凭据**冻结当时用到的阈值快照**，**改阈值不追溯改变历史预警**；**绝不改 `account_health_threshold`**（会破坏它的 rule_version 触发器语义）；改阈值必然推进 `rule_version`（触发器钉住）。
6. **预测口径明确可复核**：取窗口内最近 N 个周期，对量到的点做**最小二乘线性回归**（量不到的周期占位但**不进回归、序号不压缩**，压缩会让斜率算错），以最后一个量到的周期为起点向后推；周期明细 / 点数 / 斜率 / 预测值全部写进凭据；**已知点数不足 3 个 ⇒ `CANNOT_PREDICT` + 原因，绝不给假预测**；已越线报 `ALREADY_BREACHED`（那是现状不是预测）。
7. **凭据追加式**：预警与预测凭据都是 append-only（触发器 `..._immutable` + `..._no_delete` 钉住）；已知且未命中的指标**不产**预警凭据。
8. **下游 1 工作台预警 / 9 订单履约未交付 ⇒ 不编造**，能力声明标 `available=false` + `ownerModule`，api-proxy **刻意不放行**指向不存在入口的形状。

---

## 批次编排：4 条车道如何做到零冲突并入

本批 4 条车道全部改了**同一个共享文件** `apps/web/src/lib/api-proxy.ts`（累积型白名单，150+ 条 `services[]` 元素）与**同一批 codegen 契约产物**。三条结构性纪律让 git 能干净求并：

| 车道     | 服务    | api-proxy 首 token        | 插入锚点                 | 契约                  |
| -------- | ------- | ------------------------- | ------------------------ | --------------------- |
| 15.1.1.3 | crm     | `message-classifications` | `message-sends` 之后     | crm 两份              |
| 15.2.1.3 | crm     | `negative-reviews`        | `review-replies` 之后    | crm 两份              |
| 15.5.2.1 | crm     | `care-reminders`          | `customer-contacts` 之后 | crm 两份              |
| 16.1.1.2 | channel | `performance-metrics`     | `account-health` 之后    | **只有 channel 两份** |

- **四个锚点互不重叠** —— 上一版曾有两条车道选了同一锚点，同位置插入元素 ⇒ git 必然判冲突（结构性，retry 不会自愈）。本批四个锚点两两不同。
- **既有元素一字未动、不重排** —— 既有条目保持原样，只**新增**独立 `services[]` 元素，不往既有 alternation 里并分支。
- **首 token 零命中逐条实测** —— 每条车道都把自己模块的每个 path shape 对既有全部正则跑 `new RegExp(pat).test(shape)`，实测零命中并把结论写进代码注释。（车道 15.5.2.1 首版脚本把正则字面量的 `/` 分隔符也喂给了 `new RegExp`，导致断言空转，修正后重跑结论才成立。）
- **Flyway 版本号预分配**（V301 / V302 / V303 / V304），四条车道同时提交迁移也不会撞号。
- **表命名空间彼此隔离**：`crm_message_classification*` / `crm_negative_review*` / `crm_care_reminder*` / `account_health_performance_*`，互不重名且不与既有表冲突。

## 静态预检（互相破坏形状扫描）

对四条车道的新迁移跑「已知互相破坏形状」检查，**三项全部零命中**：

- `ADD COLUMN` 无 DEFAULT ⇒ **none**
- `SET NOT NULL` ⇒ **none**
- `ADD CONSTRAINT ... CHECK` ⇒ **none**

（四个模块全部是**纯新建表、零 ALTER 既有表**，跨车道 DDL 回归面 = 0。）

## L2 批量验证结果

L2 在**集成头上跑一次**（本轮唯一发热高峰，19:38:35 → 20:24:17，约 46 分钟），全量：

```
cd backend && ./mvnw verify
cd .. && pnpm typecheck && pnpm lint && pnpm test
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
node tools/module-progress/sync.mjs --check
```

**绿。六个阶段退出码全为 0：**

| 阶段                               | 结果                                                                                                                                                                   |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `./mvnw verify`                    | **BUILD SUCCESS**（43:42）· **8317 例 0 失败 0 错误**（identity 270 / catalog 1981 / channel 1677 / warehouse 1640 / trade 2383 / crm 366）· 七模块 Reactor 全 SUCCESS |
| `pnpm typecheck`                   | exit 0（3 包绿）                                                                                                                                                       |
| `pnpm lint`                        | exit 0（无新增告警）                                                                                                                                                   |
| `pnpm test`                        | exit 0 · **478 文件 / 6988 例全绿**                                                                                                                                    |
| `pnpm --filter @starcon/web build` | exit 0（Next production build 成功）                                                                                                                                   |
| `sync.mjs --check`                 | exit 0（✓ 台账与蓝图一致）                                                                                                                                             |

**L2 抓不到跨车道互相破坏** —— 四个模块分属 crm / channel 两个服务、表命名空间互不重叠、
api-proxy 四个锚点互不相同、Flyway 版本号预分配，因此本批并入后第一次全量验证即绿。

**Flyway 在 L2 中实跑确认**：crm 服务一次应用 **14 个迁移**（`99 → 220 → 228 → 292 → 293 → 294 → 295 → 296 → 297 → 298 → 299 → 301 → 302 → 303`），无冲突、无版本号撞车。

## 台账

`docs/product/capability-blueprint/module-progress.md`：本批 4 行由「未开发」改为「已交付」，**只改自己模块那一行的「状态」「证据」两格**，其余 461 行未动。台账行数恒为 **465**，模块 ID 无重复。同步后计数：**已交付 324 / 进行中 0 / 未开发 141**（本轮起点是 320 / 0 / 145）。

## 后续待办

1. 15.1.1.3 的**模型训练调度器**未交付 —— 纠正样本可导出但不会自动学出新规则；需要一个后续模块把训练样本变成规则。
2. 15.2.1.3 的**平台申诉接口**未接入 —— 申诉提交仍只能人工转交。
3. 15.5.2.1 缺**「已发货订单集合」只读端口**（属卷 10 物流）；12 财务未交付 ⇒ 催付不能写回款单据。
4. 16.1.1.2 的**平台绩效同步适配器**未交付（属卷 02 同步运行）⇒ 指标只能人工录入（`source=MANUAL`）。
5. 卷 18（平台服务）主责服务 `platform` 仍未并入集成头 ⇒ 该卷模块持续 `serviceBlocked`，需要单独并入该服务后才能排车道。
6. **台账兄弟行里「15.1.1.3 未交付」等表述已过时**（15.1.1.1 的证据格里写着「15.1.1.3 未交付 ⇒ 优先级由人工设置」）。本批按纪律**未动他模块的证据格**，留给后续统一校正。
