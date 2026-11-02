# 2026-11-03: 会员与等级（15.6.1.1）

## 摘要

交付蓝图卷 15 L3 锚点 15.6.1「会员体系」下的 **15.6.1.1 会员与等级**：crm 服务新增
**V440 迁移**（四张新表）、**11 个端点**（`/crm/customer-membership/**`）、前端
**会员等级看板**（`/crm/customer-membership`）与**口径层模型**、契约（codegen 重生成）、
台账与本篇会话日志。

等级**只能**由上游 trade 的**真实订单事实**算出（授权 HTTP API 只读 `GET /trade/sales-orders/list`）；
门槛是**租户数据**（`crm_membership_level_rule`，代码里没有任何写死的门槛）；
**人工覆盖通道本轮未交付**，能力声明里如实标出。

本轮是**接手上一任 worker 的半成品**收口：上一任会话被网络错误掐断，产出未提交
（迁移 + 4 个 Java 文件 + 2 个测试 + 前端页面与模型）。本次先落盘一份「继承」提交防丢，
再逐个定位并修掉 `MembershipIntegrationTest` 的 **12 处失败**（**没有删断言迁就实现**）。

**续做轮次（同一篇日志）**：上一任再次被网络错误（`ERR_CONNECTION_CLOSED`）掐断，
产出分两半 —— 后端（迁移 + 5 个 Java 文件 + 2 个测试类）**已提交**，前端工作区、
白名单、契约、台账与会话日志**留在工作区未提交**。续做轮只做三件事：
① **只修前端**，后端已绿的一行不动（`SectionCard` 的 `action` 槽位本仓叫 `extra`，
改成 `extra` 后 typecheck 恢复绿）；② 跑完 **L1 全套实跑验证**（含共享白名单回归
`pnpm vitest run apps/web/src/app/api`）并把结果写进下面的「L1 验证」节；
③ 用 `sync.mjs` 沙箱核对台账、补齐台账证据里的**例数**（前端口径层 **31 例**，
不是先前写的 24 例）与本篇日志。**契约未手改**，用 codegen 复跑到沙箱后与已生成版本
**逐字节一致**。

## 决策 / 结论

### 1. 🔴 加粗句「会员等级必须由真实消费数据驱动，手工挂等级会导致权益成本失控」——四层落地

| 层 | 落点 | 说明 |
| --- | --- | --- |
| ① 应用层 | **服务端没有任何接受等级代码的写路径** | `MembershipController` 只有 `POST /compute`（重算）与等级规则 CRUD；**没有** `set-level` / `override` 端点，`CrmMembershipLevelRuleInput` 里也没有等级字段 |
| ② DB 层 | 触发器 `guard_crm_membership_origin` **直接拒绝** `level_origin='MANUAL_OVERRIDE'` | 绕开应用层直连数据库也写不进去；等级变更记录上的 `origin` 同样只允许 `RULE` |
| ③ 报表口径 | 等级来源落在 **`level_origin` 列**上 | 将来交付人工覆盖通道后，报表按这一列即可区分「规则算出来的等级」与「人工覆盖的等级」，**现在恒为 RULE** |
| ④ 门槛 | 门槛是**租户数据**（`crm_membership_level_rule`），代码里**没有任何写死的门槛** | 不配规则就没有等级，全员 `NONE`（**一等状态**，不是「普通会员」） |

**人工覆盖通道本轮未交付**（任务书允许的选项二）：交付它必须先设计「原因 + 留痕 + 有效期」三件套，
否则它就是加粗句要禁的那条路。能力声明里 `manual_level_override` 一维 **`available=false`**，
`source` 直写「**人工覆盖等级本轮未交付**」，`platform_membership_sync`（平台会员体系对接，
channel 侧无接口）与 `points_and_redemption`（15.6.1.2 积分）同样 `available=false`。
前端**不提供**任何设置等级的控件（`customer-membership-workspace.tsx` 里没有这一类入口）。

### 2. 🔴 十进制一律走**文本**（出参 `String`），不是 JSON number

**为什么**：JSON number **装不下「显示标度」**。实测（Jackson）：`1.0000` 被解析成 `1`、
`300.0000` 被解析成 `3E+2` —— 而权益生效配置是要交给 **5 定价 / 14 促销**的，
`3E+2` 会一路传下去。这与仓里 1.3.2.1 预警阈值、15.1.1.3 AI 效果评估的口径一致
（「阈值全程十进制文本：前端字符串 → 契约字符串 → 服务端 `BigDecimal` → 库里 `NUMERIC`」）。

**口径**（服务层三个函数，写在 `MembershipService` 里）：

- **金额** = 去尾零 + **至少两位小数**、**不四舍五入**（`1.0000 → 1.00`；`66.6050 → 66.605`；
  多位就出多位 —— 出参显示的必须是「库里那个数」）。
- **配置里的比率** = 去尾零（运营写 `0.05` 就是 `0.05`，不显示成库里补零后的 `0.050000`）。
- **判定算出来的复购率** = **固定 6 位**（`0.750000`）—— 它是判定口径，固定标度才能逐位复核。

**顺带修掉一个真 bug**：jsonb 读回走的是 `readValue(raw, Object.class)` ⇒ JSON 数字先落成
**double**（金额走了一遍浮点）。改成 `readTree` + `USE_BIG_DECIMAL_FOR_FLOATS`，
全程 `BigDecimal`，**零浮点**。前端同理：`membershipBenefitText` 原本用
`Number(discountRate) * 100`（`0.07 * 100` 在 IEEE 754 下是 `7.000000000000001`），
改成**字符串移位** `membershipPercentText`。

### 3. 时刻出参统一换算成 **UTC**

同一个绝对时刻用不同偏移量写出来会是两个字符串（`2026-10-10T08:00+08:00` 与
`2026-10-10T00:00Z` 是同一时刻），拿字符串比相等会得出「不是同一个时刻」的错结论。
出参统一 UTC（秒必写，秒以下有值才写，结尾恒为 `Z`）。

### 4. 等级变更的 `changed_at` = **这次判定发生的时刻**，不是窗口终点

原来用窗口终点当变更时刻：同一窗口的多次重算落在**同一个时间点**上，
列表排序只能靠随机 `id` 排 —— 「最新的变更排在最前」变成了掷骰子。
改用运行时刻（`startedAt`）后，跨运行的先后是确定的。

### 5. 等级规则 `PUT`：权益字段「**没给 = 不改**」，生效终点「**没给 = 保持原样**」

门槛与等级序**不可就地修改**（不可变优先，改门槛要新建一条带新生效起点的规则），
但「只改启用状态」是运营最常见的动作 —— 它不该因为没重复抄一遍权益就被拒。
清空生效终点等于「把一条已关掉的规则重新打开」，那是改口径，不归 `PUT` 管。

### 6. 错误翻译做成**模块内**的一份 advice（不动共享的 `ApiErrorHandler`）

`ApiErrorHandler` 是 crm 服务**所有**模块共用的文件，改它会让别的车道的行为一起变。
本模块用 `MembershipErrorHandler`（`assignableTypes = MembershipController.class`），
让业务拒绝的**理由进响应体**（默认 `sendError` 是**空体**，运营拿到 409 却不知道是
「门槛不可就地修改」还是「币种没给」）。状态码沿用服务层给的，不另起一套。

## 涉及文件

**后端（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V440__crm_membership_level.sql` —— 四张表 +
  RLS（ENABLE **与** FORCE）+ 索引 + GRANT + 序列单独授权 + 4 个触发器（零外键）
- `backend/services/crm/src/main/java/com/starcon/crm/MembershipController.java` —— 11 个端点
- `backend/services/crm/src/main/java/com/starcon/crm/MembershipModels.java` —— DTO（**出参十进制文本**）
- `backend/services/crm/src/main/java/com/starcon/crm/MembershipOrderFactsPort.java` —— trade 只读端口
- `backend/services/crm/src/main/java/com/starcon/crm/MembershipService.java` —— 判定 / 规则 / 权益 / 台账
- `backend/services/crm/src/main/java/com/starcon/crm/MembershipErrorHandler.java` —— **新增**，模块内错误翻译
- `backend/services/crm/src/test/java/com/starcon/crm/MembershipIntegrationTest.java` —— 18 例
- `backend/services/crm/src/test/java/com/starcon/crm/MembershipTenancyIntegrationTest.java` —— 4 例

**前端**

- `apps/web/src/app/crm/customer-membership/page.tsx` —— 路由页
- `apps/web/src/components/crm/customer-membership-workspace.tsx` —— 真实工作区（三态齐全）
- `apps/web/src/components/crm/customer-membership-model.ts` —— 口径层纯函数
- `apps/web/src/components/crm/customer-membership-model.test.ts` —— **新增**，31 例
- `apps/web/src/app/api/crm/customer-membership-allowlist.test.ts` —— **新增**，5 例（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条** `crm/` services 元素（既有条目一字未改）

**契约（codegen 产物，未手改）**：`packages/api-contracts/src/contracts/crm.ts`、
`packages/api-contracts/src/crm.openapi.json`（**纯增量**：+19 个类型 / +9 条路径，0 行删除）

**台账**：`docs/product/capability-blueprint/module-progress.md` —— **只改 15.6.1.1 那一行的
「状态」「证据」两格**（diff 1 行，表格行数不变）

## 关键 trade-off / 讨论

- **十进制出参用文本还是 JSON number**：选了文本。代价是契约里这些字段变成 `string`
  （前端要按字符串处理），收益是「显示标度」这件事有了**唯一**答案，而且下游
  （5 定价 / 14 促销）拿到的是 `0.05` 而不是 `5.000000000000001%` 那种东西。
  入参仍是 `BigDecimal`（Jackson 按 token 原文解析，**不经过 double**），避免把入参也改成字符串后
  再加一层格式校验。

- **测试断言的两处「修正」**（都不是删断言，是把断言改成**自洽**的）：
  ① 「未达任何等级」的会员数：期望值 3 与同一文件里的另两条断言（甲=GOLD、乙=BRONZE、
     共 4 位）**算不平**（1 + 1 + 3 ≠ 4），实测口径是 **2**（丙、丁）；② 「窗口外的会员」
     的窗口原本取最近 2 天，而最近 5 天才有一单（会员丙）—— 窗口改成 10 天，
     `memberCount == 1` 这条断言**原样保留**且更有意义（窗口里恰好有一位会员，
     而会员甲不在其中 ⇒ 等级与指标保持不变）。

- **合并用例的夹具污染**：`MembershipIntegrationTest` 的 `@BeforeEach` 原来只清本模块的表，
  15.3.1.3 的合并用例写进 `crm_customer_merge_*` 的**真实合并映射**会漏给后面的用例 ——
  症状是「会员数从 4 变 3、甲被判成 BRONZE」，病因在别的表。现在连合并表与
  `crm_customer_profile` 一起清。合并用例本身也按 **V437 的纪律**改写成三步
  （候选 SUGGESTED → 人工复核 CONFIRMED → 写映射），因为 V437 的触发器**本来就要拦**
  「没有已确认候选就写映射」。

## L1 验证（**实跑**，命令与结果原样）

### 后端（crm 服务，只跑本模块测试类，不跑全量 `verify`）

```bash
cd backend && ./mvnw -pl services/crm -am test -Dtest='Membership*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```

结果：**`BUILD SUCCESS`**，`Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`

| 测试类 | 例数 | 结果 |
| --- | --- | --- |
| `com.starcon.crm.MembershipIntegrationTest` | **18** | 全绿 |
| `com.starcon.crm.MembershipTenancyIntegrationTest` | **4** | 全绿（租户隔离三断言实跑） |

### 前端

```bash
pnpm vitest run apps/web/src/components/crm/customer-membership-model.test.ts \
             apps/web/src/app/api/crm/customer-membership-allowlist.test.ts
```

结果：`Test Files 2 passed (2)`，`Tests 36 passed (36)`

| 测试文件 | 例数 | 结果 |
| --- | --- | --- |
| `customer-membership-model.test.ts` | **31** | 全绿 |
| `customer-membership-allowlist.test.ts` | **5** | 全绿 |

### 共享白名单回归（本车道改了 `apps/web/src/lib/api-proxy.ts` ⇒ **必须**跑）

```bash
pnpm vitest run apps/web/src/app/api
```

结果：`Test Files 300 passed (300)`，`Tests 1684 passed (1684)`。
本车道对 `api-proxy.ts` 是**纯新增一条** `crm/` services 元素（既有条目一字未改、不重排），
所以**没有**取错一侧、**没有**静默删掉别人已交付的路由。

### 类型检查 / Lint / 格式（只查本车道改动过的文件）

```bash
pnpm typecheck   # → 3/3 packages successful，EXIT=0
pnpm lint        # → 3/3 packages successful，EXIT=0（本车道文件 0 warning）
pnpm exec prettier --check <本车道 6 个文件>   # → All matched files use Prettier code style!
```

### 契约核对（codegen 产物，**未手改**）

```bash
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi \
        --out-dir .tmp/contracts-check      # 输出到沙箱，不污染共享生成物
```

结果：**EXIT=0**，`Generated 7993 DTOs in 9 per-service files, 9 OpenAPI snapshots and one barrel`。
与工作区里已生成好的契约**逐字节一致**：

- `.tmp/contracts-check/contracts/crm.ts` vs `packages/api-contracts/src/contracts/crm.ts` → **无差异**
- `.tmp/contracts-check/crm.openapi.json` vs `packages/api-contracts/src/crm.openapi.json` → **无差异**

spec 里 `/crm/customer-membership/**` 共 **9 条路径 / 11 个操作**，
与代理白名单正则**逐条对齐**（`capabilities` / `members` / `members/{uuid}` / `levels` /
`levels/{uuid}` / `benefits` / `level-changes` / `compute` / `runs`）。

## 未交付部分与**明确原因**

| 未交付项 | 原因 |
| --- | --- |
| **人工覆盖等级通道** | 交付它必须先设计「原因 + 留痕 + 有效期」三件套，否则它就是蓝图加粗句要禁的那条路；能力声明里 `manual_level_override` 一维 `available=false`，DB 层触发器也直接拒绝 `level_origin='MANUAL_OVERRIDE'` |
| **平台会员体系对接**（`platform_membership_sync`） | channel 服务**没有**平台会员接口可对；且平台侧等级**不应回写**本服务 —— 那会绕过「真实消费数据」这条硬约束 |
| **积分与权益核销**（`points_and_redemption`） | 属 **15.6.1.2 积分与权益**，是另一格，不在本车道 |
| **权益生效配置的下游消费**（5 定价 / 14 促销） | 本模块**只输出**配置，**不代劳**改价目表 / 促销表（跨服务禁止直连对方库）；下游消费方尚未开发 |

## 后续待办

- **人工覆盖等级通道**（未交付）：要交付必须先设计「原因 + 留痕 + 有效期」三件套，
  再放开 `guard_crm_membership_origin` 触发器，并给 `level_origin` 填上真实取值。
- **平台会员体系对接**（未交付）：等 channel 服务有平台会员接口；平台侧等级**不回写**本服务
  （那会绕过「真实消费数据」这条硬约束）。
- **15.6.1.2 积分与权益**：另一格，不在本车道。
- **权益生效配置的下游消费**（5 定价 / 14 促销）：本模块只**输出**配置，不代劳改价目表/促销表。

## 关联

- 蓝图原文：`docs/product/capability-blueprint/15-crm-and-service.md`（15.6.1.1）
- 兄弟模块：15.6.2.1 生命周期（V436，同服务写法模板）、15.3.1.2 客户详情（V435）、
  15.3.1.3 跨店合并（V437，本模块只读它的合并口径）
- 上游：9 订单（trade `GET /trade/sales-orders/list`，V100）
- 口径参考：`docs/sessions/2026-10/2026-10-25-1-3-2-1-alert-rules.md`（十进制文本口径）
