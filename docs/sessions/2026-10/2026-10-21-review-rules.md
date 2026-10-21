# 15.2.2.2 索评规则 —— 车道交付记录

> 蓝图：15 卷 15.2.2.2（L3 锚点 15.2.2，阶段 D7）。落地服务 **crm**（15 客户与客服）。
> 车道分支 `feat/15-2-2-2`，迁移 `V228__crm_review_rule.sql`。
> 兄弟模块 15.2.2.1 Request a Review（索评）已交付，见 `2026-10-20-review-request-lane.md`。

## 决策摘要

1. **命名空间与兄弟模块分开：`/crm/review-rules`（不挂 `review-requests/rules`）。**
   15.2.2.1 的 api-proxy 注释里写着「索评规则配置归 15.2.2.2」，而它的**拒绝清单**里正好有一条
   `review-requests/rules`。本车道**不去动那条拒绝清单**（它是兄弟模块的既有行），改为独立前缀
   `review-rules`：一个管「策略怎么配」，一个管「这一单要不要索评」，**不共用任何一张表**。
   两条 crm 正则首 token 不同（`knowledge` / `review-requests` / `review-rules`），互不吃掉。
2. **「生效策略」必须是解析出来的，不是指定的。**
   页面**不提供**「指定用哪条规则」的入口 —— 指定规则就绕过了「生效策略」这件事本身。
   服务端按「范围越具体越优先 → 同范围 priority 升序」解析，并发一条**唯一索引**
   `crm_review_rule_one_active_per_priority`（同一店铺范围 × 物流方式 × 优先级只允许一条 ACTIVE 规则），
   从根上排除「生效策略有两个答案」。
3. **效果对比要有真实归属，才敢叫「效果对比」。**
   「哪个变体带来了这次回收」必须有归属事实，否则回收率与平均星级无从按 A / B 拆开。
   新表 `crm_review_rule_assignment` 记这个落地（哪条索评请求 → 哪条规则 → 哪个变体 → 排期到什么时候），
   效果对比从它出发 join 15.2.2.1 的 `crm_review_recovery` 聚合。
   **不 ALTER 15.2.2.1 的任何一张表**（零回归面）。
4. **节假日按规则时区换算，不按 UTC 日界。**
   节假日是**日历概念**：用 UTC 日界，「东八区 2026-10-02 00:00」会被算成 10-01，节假日错开一天。
   规则上带 `timezone`（默认 `Asia/Shanghai`），服务层用 `ZoneId` 换算；
   命中后排期推到**第一个不是节假日的日子**（先按 `holiday_shift_days` 挪，再逐日后找，上限 30 天）。
   集成测试用一条**跨 UTC 日界**的排期把这条钉死。
5. **A/B 分桶是确定性的。**
   种子（买家标识 + 订单号 + 请求 id）→ SHA-256 → 0..9999 的桶 → 按累计权重落到 A / B。
   同一个种子**永远**落到同一个臂，所以效果对比可复算；A/B 未开启时用**默认臂**（权重最高，同权重按变体码升序）。
   不用 `String.hashCode()`：它在短前缀上分布很差，会让某一臂几乎拿不到样本。
6. **不发送、不写「已发送」回执。**
   下游 **15.1.1.2 消息发送未交付** ⇒ 本模块只**产出**话术与生效策略。
   自己写的「已发送」会让「买家到底收到没收到」看起来发生过 —— 而那正是过度索评投诉的源头。
   页面因此**没有**「已发送」按钮（一个点了就返回成功的假按钮比没有更糟）。
7. **已落地引用过的规则 / 变体 / 模板删不掉。**
   删掉之后效果对比里那一段历史会**凭空消失** —— 结论被改写，而不是少了几条。
   防线在 V228 的 DB 触发器（`guard_crm_review_rule_delete` 等），白名单不区分方法。
8. **配置可以删，凭据不能删。**
   规则 / 模板 / 节假日是**配置**（改了就是改了），所以 crm 代理出口新增 `export const DELETE`；
   15.2.2.1 的索评请求仍是频次计数的凭据，**不提供** DELETE 端点。

## 关键 trade-off

| 取舍 | 选择 | 代价 / 理由 |
| --- | --- | --- |
| 命名空间 | **独立 `/crm/review-rules`** | 多一条白名单元素，但不去动兄弟模块的拒绝清单（少一处并入冲突点） |
| 「生效策略」怎么定 | **服务端解析 + DB 唯一索引** | 多一个唯一索引与一段排序逻辑，但「生效策略有两个答案」是最坏的形状 |
| 效果对比的归属 | **独立落地表**（不改 15.2.2.1 的表） | 多一张表，零 ALTER 既有表；改别人的表会把兄弟模块的测试一起拖下水 |
| 订单履约方式（自发货 / 平台仓） | **运营在应用策略时给出** | 不可从上游核实（15.2.2.1 的请求不带这个维度），店铺的 `operating_mode` 只作提示 —— 造一个「自动推断」会让规则命中到错的臂 |
| A/B 分配方式 | **确定性哈希分桶** | 同一个种子永远同一臂，可复算；代价是不能「按人手工指定臂」 |
| 节假日日界 | **规则自带 timezone** | 多一列 + 一次换算；用 UTC 日界会让节假日错开一天，而错开一天的节假日是**静默**的 |
| 外键 vs 触发器 | **触发器** | batch-37 有车道给既有表建 FK 打断了同服务既有测试的 TRUNCATE；触发器（同批 V219 / V220 手法）零回归面 |
| 删配置的方式 | **真 DELETE**（规则 / 模板 / 节假日） | 配置项只进不出会变成「配错了却改不掉」的僵尸配置；凭据（索评请求）仍然不可删 |

## 踩到的坑（对下一条车道有复用价值）

1. **同服务内 codegen 会「后者覆盖前者」。**
   `tools/codegen/catalog-contracts.py` 的 `gate()` **只查跨服务**同名；
   **同一个服务内**同名且结构不同的 DTO 会被**静默覆盖**。
   本车道最初把规则输入也叫 `CrmReviewRuleInput`，与 15.2.2.1 的同名类型结构不同 ⇒
   重新生成契约后，**兄弟模块的 `CrmReviewRuleInput` 被我的覆盖掉**，
   `review-request-workspace.tsx` 立刻 typecheck 报错（不是我的文件先红，是**它**先红）。
   ⇒ 本车道改名 `CrmReviewRuleConfigInput`，两条类型现在并存。
   **待补缺口**：codegen 的 gate 应当也覆盖**同服务内**的重名（否则下一个同服务车道还会踩）。
2. **jOOQ 抛的不是 jOOQ 的异常。**
   本仓的 `DSLContext` 走 Spring 的 `SpringConnectionProvider`，SQL 错误被翻译成
   **Spring 的** `org.springframework.dao.DuplicateKeyException`（不是 `org.jooq.exception.DataAccessException`）。
   只 catch jOOQ 那个类型 ⇒ 异常直接逃到 `ApiErrorHandler`，接口只回一句
   「客服路径数据与既有记录冲突」—— **人话原因丢了**。
   ⇒ 本车道两处都 catch（`org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException`）。
   （15.2.2.1 的 `ReviewRequestService` 只 catch 了 jOOQ 那个 —— 它的 DB 触发器拒绝路径
   现在只回通用 409，不是人话。**不是本车道的范围**，但值得下一条 crm 车道顺手修。）
3. **`ResponseStatusException.getMessage()` 拿不到人话。**
   它走 `NestedRuntimeException`，`getMessage()` 返回的是 **cause** 的消息
   （比如 jOOQ 的 `duplicate key value violates ...`），人话在 **`getReason()`** 里。
   测试里必须把两者拼起来断言（与 15.2.2.1 的 `errorOf` 同款写法）。
4. **SpringDoc 对嵌套 record 的 `@Schema(nullable = true)` 不生效。**
   生成的 OpenAPI 里就是一个裸 `$ref`，codegen 出来的 TS 类型是**非空**的 —— 类型在骗人，运行时却是 null。
   ⇒ 生效策略里的「选中的变体」改成 **`variants` 数组 + `pickedVariantId`（可空 UUID）** 两段：
   数组永远是数组，UUID 走基础类型路径 nullable 有效。

## 文件清单

**后端（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V228__crm_review_rule.sql` —— 五张表 + 七条守卫触发器 + RLS + 授权 + 两个序列
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRuleModels.java` —— 传输模型
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRuleService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/crm/src/main/java/com/starcon/crm/ReviewRuleController.java` —— 13 个端点
- `backend/services/crm/src/test/java/com/starcon/crm/ReviewRuleIntegrationTest.java` —— 22 例
- `backend/services/crm/src/test/java/com/starcon/crm/ReviewRuleTenancyIntegrationTest.java` —— 4 例（租户隔离三断言）

**前端**

- `apps/web/src/components/crm/review-rule-model.ts` —— 前端模型与可读形态
- `apps/web/src/components/crm/review-rule-workspace.tsx` —— 工作区（七个分区）
- `apps/web/src/components/crm/review-rule-model.test.ts` —— 前端模型纯规则测试
- `apps/web/src/app/crm/review-rules/page.tsx` —— 页面 `/crm/review-rules`
- `apps/web/src/app/api/crm/review-rules-allowlist.test.ts` —— api-proxy 白名单双向断言
- `apps/web/src/lib/api-proxy.ts` —— **新增一条** `services[]` 元素（64 行新增 / **0 行删除**，108 → 109）
- `apps/web/src/app/api/crm/[...path]/route.ts` —— 新增 `export const DELETE`

**契约与台账**

- `packages/api-contracts/src/contracts/crm.ts` / `packages/api-contracts/src/crm.openapi.json` —— codegen 产物（纯新增：12 → 13 条路径）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 15.2.2.2 那一行的「状态」「证据」两格

## 验证

```text
backend: ./mvnw -q compile                                   → BUILD SUCCESS
backend: ./mvnw -pl services/crm -am test -Dtest='ReviewRule*' → Tests run: 26, Failures: 0, Errors: 0
front:   pnpm typecheck                                       → 3/3 成功
front:   pnpm vitest run apps/web/src/app/api/crm apps/web/src/components/crm → 47 例全绿
front:   pnpm vitest run apps/web/src/app/api                 → 660 例全绿（api-proxy 全量 allowlist 回归）
api-proxy 自检: grep -c "prefix: '" → 109（基线 108 + 1）；git diff 64 行新增 / 0 行删除
```

## 后续待办

1. **codegen 的 gate 要覆盖同服务内重名**（否则下一个 crm 车道还会踩「后者覆盖前者」）。
   本车道只做了规避（改名 `CrmReviewRuleConfigInput`），**没有**改 `tools/codegen/catalog-contracts.py`
   —— 那是多车道共用工具，本批另外三条车道也在跑，改它会扩大冲突面。
2. **15.2.2.1 的 DB 触发器拒绝路径只回通用 409**（`ReviewRequestService` 只 catch 了 jOOQ 的
   `DataAccessException`，而本仓实际抛的是 Spring 的 `DuplicateKeyException`）。
   修法与本车道一致：两个类型都 catch。**不是本车道范围**，留给下一条 crm 车道。
3. **效果对比目前只接人工登记的回收桶**（平台评价同步未交付）。
   15.2.1.1 评价同步落地后，效果对比的「回收」需要换成平台侧真值，并相应调整
   capabilities 里 `review_sync` 维度的 available 与 note。
4. **下游 15.1.1.2 消息发送落地后**，本模块产出的 `variants` + `pickedVariantId` 需要被
   发送侧消费；在此之前页面**不提供**任何「已发送」入口。
