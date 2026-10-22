# 15.1.1.3 消息分类（阶段 D7，L3 锚点 15.1.1）

- **日期**：2026-10-22
- **车道**：`feat/15-1-1-3`（worktree `integration/.worktrees/15.1.1.3`）
- **蓝图原文**（`docs/product/capability-blueprint/15-crm-and-service.md` 99-104 行）：
  > 职责：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），驱动自动回复与优先级，支持人工纠正以改进分类。
  > 输入：消息内容；分类规则（关键词 + 平台类型 + 历史纠正数据）。
  > 输出：分类标签 + 优先级 + 是否可自动回复。
  > 关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。**人工纠正要回流为训练样本**，否则分类准确率永远上不去。

## 决策摘要

### 1. 🔴 加粗句「人工纠正要回流为训练样本」落成**三张表里的两张**，而不是一句注释

蓝图那句的后半句（"否则分类准确率永远上不去"）真正的工程含义是：**要能逐条比对「机器说了什么」与「人改成了什么」**。只存「正确答案」是学不出模型也复不了盘的。

所以：

| 表 | 角色 | 写入语义 |
| --- | --- | --- |
| `crm_message_classification` | **机器的结论** | 可被重新分类覆盖（一条消息一条，`UNIQUE (tenant_id, message_id)`） |
| `crm_message_classification_feedback` | **人的纠正** | **只追加**（DB 触发器拒绝 UPDATE/DELETE） |

关键取舍：

- **纠正不回写结论**。结论里的标签/优先级永远是「机器当时说了什么」。要改机器的判断就**重新分类**，要留人的判断就**追加纠正** —— 两件事不混。
- **纠正行自带机器预测快照**（`predicted_label` / `predicted_priority` / `predicted_rule_id` / `predicted_rule_code`）。哪怕之后重新分类覆盖了结论，这一行仍然记着「当时判成什么、被改成了什么」。
- **训练样本视图带消息正文**。`GET /training-samples` 返回「机器预测 + 人工纠正 + 正文」三件套；少了正文就只剩标签对，训不出「哪句话被判成了什么」。
- **训练样本在 DB 层只追加**（`guard_crm_message_classification_feedback_append_only`）。改得掉的训练样本 = 随时可以改漂亮的准确率，那这条指标就没有意义了。

### 2. 🔴 不许覆写人工设过的优先级：三个数一起给，不是一个

`crm_message.priority` 是 15.1.1.1 的批量动作 `SET_PRIORITY` 设的。本模块**只读不改**（连 ALTER 都不做 —— 迁移零 ALTER 既有表）。

结论里同时落四个字段：

- `suggested_priority` —— 规则给出的**建议**；
- `manual_priority` —— 分类时刻 `crm_message.priority` 的**人工值快照**；
- `effective_priority` —— **实际生效 = 人工值**（人工值优先）；
- `priority_conflict` —— 人工值 ≠ 建议值。

页面把四个一起显示（前端 `priorityText()`），不只显示一个 —— 只显示一个的话，看的人会以为那就是唯一结论。

**未命中任何规则时 `priority_conflict` 恒为 false**：兜底的 `NORMAL` 不是规则建议，拿它当「分歧」是噪声。

### 3. 规则顺序必须**显式**，否则分类不可复现

关键词规则的天然歧义是「一条消息同时命中多条规则」。不给顺序的话，结果取决于插入顺序 —— 那会让「改一条规则」导致历史结论集体漂移。

所以规则表有 `priority_order`（数字小的先命中，并列按 `rule_code`），且：

- **命中依据记下**所有命中的规则（不只是赢家）+ 每条命中的关键词 + 命中的字段（主题/正文）。只记赢家就看不出「为什么不是它」。
- 规则**停用而不是删除** —— 历史命中依据里带着规则编码，删了就查不到了。
- 规则**编码建后不可改**（DB 触发器）—— 改了历史结论就对不上。
- **关键词必填非空**：一条没有关键词的规则永远不会命中，却会占掉一个顺序位 —— 那是一条「看起来配了、其实没生效」的规则。服务层 400 拒绝。

### 4. 标签词表：六类业务类别 + 一个**兜底** `UNCLASSIFIED`

蓝图点名六类（售前咨询 / 物流查询 / 退货退款 / 投诉 / 平台合规 / 垃圾信息）。另加 `UNCLASSIFIED 未分类` 作为「没有规则命中」的兜底：

- 规则的 `target_label` **不许**指向它（一条「未分类」的规则没有意义）；
- 未分类的消息**一律不可自动回复** —— 说不清是什么的消息就自动回，风险由买家承担。

### 5. 下游未交付一律不编：能力声明逐维标 `available=false`

15.5.1 自动回复与 1.3 预警在本车道基线里**都不可调用** ⇒ 能力声明里两维 `available=false` + `ownerModule` + 影响说明。

`auto_reply_eligible` 是**本模块自己的判定结果字段**，**不代表**自动回复功能已存在：本模块**不触发**任何自动回复、**不写**任何回复记录、**不写**任何预警。页面**不提供**「触发自动回复」这种看起来能点的按钮。

另两维也如实标 false：「把建议优先级写回消息」（只读不改）与「模型训练 / 规则学习」（**没有**训练任务调度器 —— 纠正行是样本，但**不会**自动学出新规则；本模块**不编**一个「已训练」的假状态）。

### 6. 上游 `crm_message` 在**本服务本库** ⇒ 直接 jOOQ **只读**

15.1.1.1 消息收件箱与本模块同属 crm 服务（同库同服务），所以直接查，不走 HTTP 端口。但本模块**一个字都不改**它的列 —— 跨表引用一律用**触发器**做校验，**不建数据库外键**（与同卷 V220 / V293 同款手法：同库 FK 会打破既有测试的 TRUNCATE）。

### 7. 命中依据用 `jsonb` 存，统计用 `jsonb_array_elements` 取

命中依据是「多条规则 × 多关键词」的结构，用 `jsonb` 一列存整份（`{"winningRuleId":…,"matches":[…]}`）。规则命中统计靠 `jsonb_array_elements(coalesce(match_basis->'matches','[]'::jsonb))` 展开后 `group by` 统计 —— 显式 SQL，可审阅。

**已知边界**：`hitCount` 统计的是**当前结论**里命中该规则的消息数，**不是**历史累计（重新分类会覆盖同一消息的旧命中依据；纠正行里的规则编码快照仍保留）。

### 8. api-proxy：新增一条**独立** `crm/` 元素，锚在 `^message\-sends...$` 之后

既有 149 条 services[] 正则**一字未动、不重排**。首 token 零命中用**脚本实测**（不是目测）：抽出既有全部 149 条正则，对本模块 12 个 path shape 逐条 `new RegExp(pat).test(shape)` ⇒ **命中数 = 0**。

`/messages/{uuid}` 的 uuid 段是**必需**的（后端只按消息 id 寻址，没有 `messages` 集合这个映射）。15.1.1.1 收件箱那条里 `message-inbox/messages/{uuid}/classify` **依旧不放行**（本车道不动那条既有元素，分类能力走自己的 `message-classifications` 前缀）。

## 关键 trade-off

| 取舍 | 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- | --- |
| 纠正写在哪 | **独立表 + 只追加** | 「一条记录走到底」的简单 | 改得掉的训练样本 = 随时能改漂亮的准确率；不改写结论才留得住「机器当时说了什么」 |
| 机器预测快照 | **纠正行自带** | 纠正行变大 | 只有正确答案的样本学不出「错在哪」 |
| 优先级 | **只读 + 三值并列** | 「一键采用建议值」的省事 | 偷偷用建议值盖掉人工值 = 客服设的优先级无声消失 |
| 规则顺序 | **显式 priority_order** | 插入即生效的省事 | 顺序不定 ⇒ 改一条规则让历史结论集体漂移 |
| 命中依据 | **jsonb 一列存全量** | 关系表的 join | 多规则多关键词；`jsonb_array_elements` 展开统计，显式可审阅 |
| 未分类兜底 | **UNCLASSIFIED 且不可自动回复** | 强制每条消息都有标签 | 说不清是什么的消息自动回，风险由买家承担 |
| 训练调度器 | **不实现** | 「自动学出新规则」 | 不在本模块职责内；编一个「已训练」的假状态比没有更糟 |

## 文件清单

**迁移（crm 服务）**
- `backend/services/crm/src/main/resources/db/migration/V301__crm_message_classification.sql` —— 三张新表 + RLS（ENABLE **与** FORCE，policy 名 isolation）+ 序列单独授权 + 零外键 + 4 个触发器 + **零 ALTER / 零 DROP 既有表**。

**后端**
- `backend/services/crm/src/main/java/com/starcon/crm/MessageClassificationModels.java`
- `backend/services/crm/src/main/java/com/starcon/crm/MessageClassificationService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/MessageClassificationController.java`

**测试**
- `backend/services/crm/src/test/java/com/starcon/crm/MessageClassificationIntegrationTest.java`（25 例）
- `backend/services/crm/src/test/java/com/starcon/crm/MessageClassificationTenancyIntegrationTest.java`（4 例，租户隔离三断言）

**契约（codegen 产物，只改 crm 一份）**
- `packages/api-contracts/src/contracts/crm.ts`（+28 行：28 个新类型）
- `packages/api-contracts/src/crm.openapi.json`（+12 条路径 / +28 个 schema，**纯增量，零删除**）

**前端**
- `apps/web/src/components/crm/message-classification-model.ts`
- `apps/web/src/components/crm/message-classification-model.test.ts`（20 例）
- `apps/web/src/components/crm/message-classification-workspace.tsx`
- `apps/web/src/app/crm/classifications/page.tsx`
- `apps/web/src/app/api/crm/message-classifications-allowlist.test.ts`（5 例，allowlist 反查）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点在 `^message\-sends...$` 之后；既有 149 条元素一字未动、不重排）

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 `15.1.1.3` 一行的「状态」「证据」两格。

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` 绿；`pnpm typecheck` 三包绿；`pnpm lint` 绿（无新增告警）；`prettier --check` 绿。
- L1 模块闸：`cd backend && ./mvnw -pl services/crm -am test -Dtest='MessageClassification*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ⇒ **29 例绿**（真实 PG16 Testcontainers，含租户隔离三断言）。
- 契约：6 个服务的 `OpenApiContractTest` 各 1 例绿 → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → **只改 crm 两份**，逐项核对 **0 path 移除 / 0 schema 移除**。
- 前端：模型 20 例 + allowlist 反查 5 例绿；**`pnpm vitest run apps/web/src/app/api` 全量 1021 例绿（177 文件）**（改了共享白名单必须全量回归）。

## 后续待办

1. **15.5.1 自动回复**（未交付）—— 交付后应当**消费本模块的 `auto_reply_eligible` 判定**作为入口条件，而不是自己再判一遍；本模块不触发任何回复。
2. **1.3 工作台预警**（未交付）—— 本模块不写预警；「投诉 / 平台合规」这类标签天然是预警候选，但接线不在本模块职责内。
3. **平台买家站内信适配器**（归模块 2，channel 的 vendor 包）—— 交付后消息不用再人工登记，本模块的分类输入自动变多；分类逻辑本身不用改。
4. **模型训练 / 规则学习**（本仓尚无）—— 纠正行已经是可导出的训练样本，但**没有**调度器；训练侧落地后可以直接从 `crm_message_classification_feedback` 出样本。
5. 台账里 `15.1.1.1`（消息收件箱）与 `15.1.1.2`（消息发送）两行的「已知缺口」里都写着「15.1.1.3 消息分类未交付」，在本车道交付后已过时 —— 本车道按纪律**只改自己那一行**，未动别的行，留给调度者并入时统一处理。
