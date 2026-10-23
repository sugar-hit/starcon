# 2026-10-23 04:29 集成分支并入 main（第 42 轮 · 4 项合规与风控能力交付）

## 摘要

第 42 轮同步：`integration/module-dev` → `main`。

**结果：有净增量，已开 PR #54。**

本轮在第 41 轮（零净变更复核）结束后、纪要合入主分支的同一分钟，
集成分支推送了一批新交付（batch-69）⇒ 重新取证后确认**有 4 项真实新能力**，
台账「已交付」两侧从 332 变为 336。

交付内容属于 **16 合规与风控系统**：

| 模块 ID  | 功能       |
| -------- | ---------- |
| 16.2.1.2 | 拦截执行   |
| 16.3.2.2 | EPR/包装法 |
| 16.4.1.1 | 侵权库     |
| 16.5.1.2 | 风险评分   |

## 决策 / 结论

### 1. 增量判定：树差异非空且落地树 != 主分支树 ⇒ 真有内容

- `upstream/main` tip = `a3ba9ee7`，`upstream/integration/module-dev` tip = `005f62d3`。
- 树差异 **100 个文件**（70386 增 / 35931 删），且合并结果树 `ffdda5ce…` ≠ 主分支树
  ⇒ 与第 41 轮的「零净变更」形态**不同**，本轮确有可合入内容。

### 2. 冲突面 30 个文件：29 个取主分支侧，1 个取集成分支侧

| 类别                                | 数量 | 解法             | 依据                                                                           |
| ----------------------------------- | ---- | ---------------- | ------------------------------------------------------------------------------ |
| 前端 CRM / 账号健康组件、页面与纪要 | 29   | **取 main 侧**   | 字节级格式归一化后两侧**逐字节相同**（A=29）；主分支侧已是规范态（B=29 / C=0） |
| `apps/web/src/lib/api-proxy.ts`     | 1    | **取集成分支侧** | 累积型三件套全部成立（集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓，条目 161 → 165）   |

`api-proxy.ts` 是**累积型白名单**，两侧各往同一处加分支时，只并内层会静默丢掉外层新增分支。
本次独立复核了累积型三件套，并额外核对了新增 4 条的**首 token 遮蔽**：

- `compliance-intercepts` 与主分支既有的 `compliance-rules` / `compliance-precheck` 首 token 不同 ⇒ 不遮蔽
- `epr-registrations` / `ip-risk-library` / `buyer-risk-scores` 在主分支侧无同首 token 条目 ⇒ 不遮蔽

⇒ 取集成分支侧在结果上等同于完整求并，零内容丢失。

### 3. 契约产物零键丢失

合并驱动对 `channel.openapi.json` 走了**行级兜底并集**（协议要求人工核对），
逐键核对结果：

| 指标                                | main | 集成侧 | 合并结果 |
| ----------------------------------- | ---- | ------ | -------- |
| `paths` 键数                        | 781  | 822    | 822      |
| `components.schemas` 数             | 1487 | 1577   | 1577     |
| `contracts/channel.ts` 导出类型名数 | 1487 | 1577   | 1577     |

合并结果**零键丢失**（main 侧独有键 0 个、集成侧独有键 0 个），
规范化 JSON 哈希与集成侧一致 ⇒ 契约闸可过。

### 4. 台账：求并后的摘要行重复，重新生成后与集成侧逐字节相同

合并驱动按模块 ID 求并集时**保留了两个摘要块**（主分支的 332 / 71.4% 与集成侧的 336 / 72.3%），
`sync.mjs --check` 因此为红。按协议跑一次 `sync.mjs` 重新生成后：

- 台账行数 **465**、模块 ID **无重复**、摘要行**恰好 1 条**（已交付 336 / 完成率 72.3%）
- 与集成侧台账**逐字节相同**（blob `fe1f2498…`）
- 465 条模块行相对集成分支**零差异**，相对主分支恰好 4 条从「未开发」翻转为「已交付」
- `sync.mjs --check` 由红转绿 ✓

⇒ 重新生成无损。

### 5. 零新增格式债

本次变更的 29 个可检查文件里有 **9 个不合规**（4 个前端源文件 + 5 篇会话记录）——
它们在**集成分支上就已经不合规**，不是合并引入的。

按仓库既有做法（此前多轮均如此）把这 9 个文件格式化，使本 PR **零新增格式债**。
格式化前逐个做了 **AST 指纹比对**证明语义无损：

| 文件                             | AST 指纹                |
| -------------------------------- | ----------------------- |
| `buyer-risk-model.ts`            | 逐位相同 ✓（1399 节点） |
| `buyer-risk-workspace.tsx`       | 逐位相同 ✓（977 节点）  |
| `epr-registration-model.ts`      | 逐位相同 ✓（2927 节点） |
| `epr-registration-workspace.tsx` | 逐位相同 ✓（2106 节点） |

⚠️ 比对时需**整棵子树一起跳过**三类良性节点（只跳父节点会让子节点变成孤儿，造成假的节点数差）：
`ParenthesizedExpression`（Prettier 增删冗余括号）、`JsxText`（JSX 文本换行）、
纯空白的 `JsxExpression`（`{' '}`，Prettier 为保住渲染空格而显式化）。

### 6. 新增迁移脚本只建新表，零 ALTER 既有表

channel 服务新增 4 个版本（V313 / V314 / V315 / V316），服务内版本号**无重复**（82 个文件，最高 V316）：

| 版本 | 内容                 | 建表数 |
| ---- | -------------------- | ------ |
| V313 | channel 合规拦截     | 4      |
| V314 | channel EPR 登记     | 9      |
| V315 | channel 侵权库       | 5      |
| V316 | channel 买方风险评分 | 4      |

每个脚本**只 CREATE 新表**，RLS 的 `ALTER TABLE` 只作用在自建表上，
**不触碰任何既有表** ⇒ 跨车道 DDL 回归面为 0。

## 关键 trade-off / 讨论

- **为什么冲突面仍要逐个取证，不能因为「上一轮已经解过同样的文件」就照抄？**
  本轮 29 个冲突文件与第 41 轮同名，但**基线已经变了**（主分支多了一份纪要、
  集成分支多了 4 项能力），证据必须重新取证，不能沿用上一轮的结论。
- **为什么把源分支上就不合规的 9 个文件在合并里格式化？**
  合入它们而不格式化，会让 `format:check` 的失败文件数**比主分支基线多 9 个**，
  直接违反「PR 的失败不得比基线更多」的闸。格式化前用 AST 指纹证明语义无损，
  因此不构成对源分支内容的实质改动。
- **为什么本地跑了后端全量？** 本次合并动了后端业务代码与迁移脚本 ⇒ 协议第 5.1 节的例外条件成立。
- **为什么不跑本地前端全量 build？** 冲突解法未触碰前端构建配置，
  且前端全量单元测试与类型检查均已绿 ⇒ 全量闸交给远端 CI。

## 涉及文件

- `backend/services/channel/src/main/java/com/starcon/channel/{BuyerRiskScore,ComplianceIntercept,EprRegistration,IpRiskLibrary}*.java`
- `backend/services/channel/src/main/resources/db/migration/V31{3,4,5,6}__channel_*.sql`
- `apps/web/src/app/api/channel/{buyer-risk-scores,compliance-intercepts,epr-registration,ip-risk-library}-allowlist.test.ts`
- `apps/web/src/app/compliance/{buyer-risk,epr,intercept,ip-risk}/page.tsx`
- `apps/web/src/components/{buyer-risk,compliance-intercept,epr-registration,ip-risk-library}/`
- `apps/web/src/lib/api-proxy.ts` —— 累积型白名单，本次新增 4 条服务规则
- `packages/api-contracts/src/{channel.openapi.json,contracts/channel.ts}`
- `docs/product/capability-blueprint/module-progress.md` —— 465 行，4 条状态翻转

## 后续待办

1. **集成分支仍缺主分支侧的纪要**（本轮为 round32 / round37 / round38 / round39 / round40 共 5 份），
   它们不在合并基线里，合并时主分支侧自动胜出，**不构成冲突**，但持续造成树差异。
2. **主分支与集成分支的排版不对称依然存在**（主分支侧是规范态、集成分支侧是原始态），
   本轮再次由「格式化 9 个新增文件」局部抹平，根治要靠把规范化后的字节回流到集成分支，
   超出本任务授权范围 ⇒ **每轮都要重新取证**。
3. **修 `tools/module-progress/ci-gate.mjs` 的 run 定位**（第 37 轮已记录，仍未修）：
   把 `gh run list --branch main` / `gh run list --commit <sha>` 换成
   「不带过滤器的列表 + 客户端筛选」。**不修则每轮都会以退出码 2 假阻塞。**

## 关联

- 第 41 轮记录（零净变更复核）：`docs/sessions/2026-10/2026-10-23-0414-main-sync-round41-zero-net-change.md`
- 第 40 轮记录：`docs/sessions/2026-10/2026-10-23-0334-main-sync-round40.md`
- 任务书：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
