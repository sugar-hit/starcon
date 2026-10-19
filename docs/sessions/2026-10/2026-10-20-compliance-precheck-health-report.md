# 16.2.2.1 合规预检（阶段 D7，L3 锚点 16.2.2）

> 会话日期：2026-10-20 · 车道：feat/16-2-2-1 · 落点服务：channel（02 渠道与店铺）

## 决策摘要

1. **落点服务 = channel**：卷 16「合规与风控」的后端主责是 `channel + identity`，取第一项；
   刊登内容与平台能力都在 channel。与同批 4.2.3.2 车道（同一批并行、互不依赖）**不共用表 / 端点 / 页面**。
2. **表命名空间**：`compliance_check_report` / `compliance_check_finding` / `compliance_check_dimension_score`。
   **不建规则库** —— 16.2.1.1 未来要占 `compliance_*`（规则 / 词库 / 版本），
   4.2.3.2 用 `listing_compliance_*`，本模块一律 `compliance_check_*`。
3. **「不可知」是一等状态，不是 0 分**。六个维度里只有「内容合规」现在量得到
   （依据全部来自已交付来源），其余五个维度给 UNKNOWN + ownerModule + 影响说明，**不计入分母**；
   全维度不可知时总体得分是 `NULL` 而不是 0。这条用 DB CHECK 钉死，不靠应用层自觉。
4. **不硬编码任何合规细则**。尺寸规格 / 材质 / 危险品 / 标签四维的细则归 16.2.1.1 规则库（未交付），
   认证档案归 16.2.2.2（未交付），违禁词 / 侵权扫描归 4.2.3.2（同批并行、此刻不存在）。
   本模块只做**检查与报告**，规则与词库的版本化治理不归它。
5. **本模块不执行拦截**。发布前的阻断 / 豁免 / 审批归 16.2.1.2（未交付）；
   这里只把 PASS / ATTENTION / UNKNOWN 算出来交给它消费。
6. **两次触发语义**：`DRAFT_SAVE`（保存草稿时的即时反馈）/ `PUBLISH_GATE`（发布前的强制校验），
   两个端点各产出一份报告，`trigger_kind` 让历史里分得清。
7. **前端不重算后端算出的分维度得分与整改建议**：展示串（`scoreText`）由后端算好，
   前端原样显示（与 3.9.2.1 的 `percent` 串同一手法）。

## 关键 trade-off

- **只让一个维度可评 vs 六个维度都"看起来有分"**。
  后者会让界面上的分数卡很漂亮，但那是**假分数**：没有细则来源却给分，
  比"量不到"危险得多（运营会以为这一项体检过了）。所以本模块接受"一份报告里只有一个维度有分"，
  并在得分卡上并列显示「N 个维度未知」+ 每个维度的归属模块。
- **跨服务读不到时给 UNKNOWN vs 抛 503**。
  一次体检要同时求值多条检查项，其中一条的上游挂掉**不应该让整次体检失败**。
  所以 `CompliancePrecheckSourcePort` 与 4.2.2.1 的 `ListingTemplateSourcePort` 同一手法：
  返回 `available=false` 的结果对象，**不抛异常**，绝不把「读不到」折成「合规」或 0 分。
- **问题清单的行序由生产时写下的 `seq` 承担**，不在读的时候重排。
  本表有 `(tenant_id, report_id, dimension_code, id)` 索引，按随机 UUID 扫出来的行序是随机的 ——
  靠读的时候碰运气，界面上的行序会每次刷新都变（这个坑在测试里真的踩到过一次：
  同一个断言第一次绿、第二次红）。DB 里加 `seq` 是最省事也最不容易再犯的做法。
- **平台改版后既有草稿会变成"平台不支持的栏"**：编辑器与 DB 触发器只拦「保存时」的非法字段，
  拦不住事后改版。`FIELD_UNSUPPORTED` 这条检查兜的就是这个场景
  （测试里用"先按可选保存、再把注册表改成 UNSUPPORTED"复现）。
- **api-proxy 插入锚点选在 `services[]` 最末尾**（紧挨 7.8.2.2 的 `inventory/stockout-alerts` 元素之后），
  与另外三条车道完全不重叠；**独立一条 services[] 元素**，绝不往既有 channel 巨型正则里并分支。

## 文件清单

**后端（channel）**

- `backend/services/channel/src/main/resources/db/migration/V197__compliance_precheck.sql`
  —— 三张新表 + 三条追加式触发器 + RLS/GRANT；纯新建，零 ALTER 既有表。
- `backend/services/channel/src/main/java/com/starcon/channel/CompliancePrecheckModels.java`
  —— 传输模型（含 `scoreText` / `overallScoreText` 展示串）。
- `backend/services/channel/src/main/java/com/starcon/channel/CompliancePrecheckRules.java`
  —— 维度声明 + 权重（类初始化块断言合计为 1）+ 逐检查项判定 + 得分/总体得分。
- `backend/services/channel/src/main/java/com/starcon/channel/CompliancePrecheckSourcePort.java`
  —— catalog 只读端口（SKU / 品牌 / 授权 / 刊登资格），读不到返回 `available=false`。
- `backend/services/channel/src/main/java/com/starcon/channel/CompliancePrecheckService.java`
  —— jOOQ 显式 SQL；只写本模块三张表，对 4.2.1.1 的草稿表只读。
- `backend/services/channel/src/main/java/com/starcon/channel/CompliancePrecheckController.java`
  —— `/channel/compliance-precheck` 六个端点。

**后端测试**

- `backend/services/channel/src/test/java/com/starcon/channel/CompliancePrecheckIntegrationTest.java`（16 例）
- `backend/services/channel/src/test/java/com/starcon/channel/CompliancePrecheckTenancyIntegrationTest.java`（3 例）
- `backend/services/channel/src/test/java/com/starcon/channel/CompliancePrecheckCatalogStub.java`（本地 catalog HTTP 服务）

**契约（codegen 产物，不手改）**

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

**前端**

- `apps/web/src/components/compliance-precheck/compliance-precheck-model.ts`（+ 9 例单测）
- `apps/web/src/components/compliance-precheck/compliance-precheck-workspace.tsx`
- `apps/web/src/app/compliance/precheck/page.tsx`
- `apps/web/src/app/api/channel/compliance-precheck-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**只新增一条 `services[]` 元素**，插在数组最末尾）

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 16.2.2.1 那一行的「状态」「证据」两格。

## 验证

- L0 编译闸：`./mvnw -q compile`（backend）绿；`pnpm typecheck` 绿；`pnpm lint` 绿（只有既有文件的既有告警）。
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='CompliancePrecheck*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ⇒ **19 例全绿**（连跑三次确认非偶发）。
- 前端：`pnpm vitest run apps/web/src/components/compliance-precheck/... apps/web/src/app/api/channel/compliance-precheck-allowlist.test.ts`
  ⇒ 15 例全绿；`pnpm vitest run apps/web/src/app/api`（改了 api-proxy 的全量 allowlist 回归）⇒ **83 文件 517 断言全绿**。
- 契约：六个服务的 `OpenApiContractTest` 刷新快照后跑 `catalog-contracts.py --spec-dir backend/target/openapi`，
  **只有 channel 两个文件变更**（其它五个服务零漂移）。

## 后续待办

1. **16.2.1.1 规则库交付后**：五个 UNKNOWN 维度的细则接进来，`CompliancePrecheckRules.DIMENSIONS`
   里那五个 `basisOwnerModule` 改成"已交付"即可，`score()` 的分母规则不用动 ——
   维度一旦量得到就自动进分母，量不到就自动不进。
2. **16.2.2.2 认证与资质交付后**：「认证缺失」维度从 UNKNOWN 变成可评，
   认证档案的读取要新增到 `CompliancePrecheckSourcePort`（catalog 侧仍是只经授权 HTTP API）。
3. **16.2.1.2 拦截执行交付后**：本模块的 `PUBLISH_GATE` 报告是它的输入；
   拦截 / 豁免 / 审批**不要**加到本模块的端点里。
4. **4.2.3.2 交付后**：`CONTENT_RULE_LIBRARY` 那条 UNKNOWN 自然变成可评；
   两边仍然不共用表 / 端点 / 页面。
5. **卷 16 的导航入口**：本页路由 `/compliance/precheck` 已在 Next 路由树里，
   但**没有**在 `packages/ui/src/lib/nav-config.ts` 里加"合规"域入口 ——
   那个文件是多车道共用的，且卷 16 的整组模块都在 D7 阶段，留给该批次统一处理。

---

## 补记（2026-10-20 03:05）：L2 批量验证红在本模块的**负向断言**上 —— 夹具前提在合并后不成立

### 根因

- 本车道（16.2.2.1）在 `apps/web/src/lib/api-proxy.ts` 新增一条 `services[]` 元素（前缀 `compliance-precheck`），
  同批并行的 4.2.3.2 车道新增**另一条**元素（前缀 `listing-compliance-precheck`）——
  两条元素互不相干，各自的插入锚点也互不重叠。
- 两条车道**各自**在自己的 worktree 里写了一条**负向断言**，用来证明
  「同批另一条车道的前缀不属于本模块，仍然被拒（404）」。
  **在各自 worktree 里这两条断言都是对的** —— 那时对方的前缀还不存在，代理返回 404。
- **四条车道并入集成头之后，两条前缀都真实存在了** ⇒ 代理按白名单转发到上游，
  而单元测试里上游 channel 服务没在跑 ⇒ 返回 **503**（`服务暂时无法连接`），不是 404。
  ⇒ L2 批量验证红在这两条对称的断言上（2 failed / 4509 passed）。
- ⇒ **生产代码是对的**：503 恰恰证明「白名单命中并转发」这个行为正确。
  **错的是测试夹具** —— 它建立在一个「同批另一条车道此刻不存在」的假设上，
  这个假设在合并后不成立。

### 改法（本车道只改一个测试文件）

- `apps/web/src/app/api/channel/compliance-precheck-allowlist.test.ts`
  1. **负向清单**里把 `'channel/listing-compliance-precheck/capabilities'` 换成
     `'channel/listing-compliance-precheck/score'` ——
     `capabilities` 是 4.2.3.2 **已登记**的形状（合并后由它自己的白名单元素放行 ⇒ 503 是正确行为），
     `/score` 是它**未登记**的形状 ⇒ 仍然必须是 404。
     注释同步改成如实描述「合并前后前提不同」。
  2. **新增一条独立的正向断言**（自带 `vi.stubGlobal('fetch', ...)`）：
     `'channel/listing-compliance-precheck/capabilities'` 现在**不是** 404 ——
     上游有响应时是透传状态码、上游不在线时是 503；
     并钉住「确实转发了、目标就是 channel 出口（:8083）」。
     用来证明**合并没有把另一条车道的白名单条目弄丢**（丢了会退回 404，
     而 404 在浏览器里是静默的 —— 界面只会以为「这功能没交付」）。
  3. ⚠️ 原 `expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled()`
     **仍然成立** —— 会转发的形状**没有**被塞进那个块里，放行验证另起一块。

### 验证结果

| 命令 | 结果 |
| --- | --- |
| `pnpm vitest run apps/web/src/app/api/channel/compliance-precheck-allowlist.test.ts` | **1 文件 / 7 例全绿** |
| `pnpm vitest run apps/web/src/app/api/trade` | **42 文件 / 224 例全绿** |
| `pnpm vitest run apps/web/src/app/api/channel` | 25 文件 / 119 例 ⇒ **1 failed**：`listing-compliance-precheck-allowlist.test.ts` 里**4.2.3.2 车道的对称断言**（`expected 503 to be 404`）—— 那是**另一条车道**的文件，本车道无权修改，需由 4.2.3.2 车道同法修 |
| `pnpm typecheck` | 3 包全绿（`3 successful`） |
| `pnpm --filter @starcon/web lint` | exit 0（只有既有文件的既有告警） |
| `./mvnw -pl services/channel -am test -Dtest='CompliancePrecheck*' ...` | **19 例全绿** · BUILD SUCCESS |

### 教训

- **同批并行车道的测试夹具不能假设对方的产物「此刻不存在」** ——
  那类假设在单跑时绿、在合并后红，只有 L2 批量闸能抓到。
  跨车道共享 `services[]` 白名单时，负向断言必须挑一个
  **对方也未登记**的形状，而不是「对方登记过、只是还没合并进来」的形状。
- 代理的状态码分界要记住：**白名单拒绝 ⇒ 404；放行了但上游连不上 ⇒ 503**。
  跨车道断言里看到 503，先怀疑**夹具前提**，别急着改生产代码。
