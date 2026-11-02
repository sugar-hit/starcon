# batch-136：18.4.1.2 智能查询（受控语义层 + 跨服务委托执行）

> 本批次覆盖 **18.4.1.2 智能查询**（platform 服务）。
>
> ⚠️ 本车道的上一任 worker 会话被网络错误掐断（`net::ERR_CONNECTION_CLOSED`），
> 产出全部留在 worktree 里未提交。接手方**没有重写已有代码**，
> 只做了三处**测试夹具**修复 + 前端 + 契约 + 台账 + 会话日志。

---

## 模块 18.4.1.2 智能查询

**这个功能做什么**：运营用一句中文描述需求（例如「上个月各店铺销售额排名」），
系统把它映射到**受控语义层**的指标与维度上，生成一份可预览的报表配置草稿，
并支持把一次查询固化成「常用查询」再复用。
**它不生成任意 SQL** —— 自然语言只映射到封闭词表里的指标 / 维度，
解析不到就如实回答「未接通」，不返回任何数字。

**技术落地**：

- 迁移：`backend/services/platform/src/main/resources/db/migration/V441__platform_smart_query.sql`
  （四张新表：`platform_smart_query_metric` / `platform_smart_query_dimension` /
  `platform_smart_query` / `platform_smart_query_evidence`）
  + `backend/services/identity/src/main/resources/db/migration/V441__platform_smart_query_permission.sql`
  （权限码 `platform.smart_query.read / .create / .update`）
- 端点：挂 `/platform/smart-query`（platform 服务，jOOQ 显式 SQL；
  执行面**委托**下游 13.6.1.1 自定义报表的查询引擎，跨服务只走授权 HTTP API，
  不读写对方的表、不建跨库 FK）
- 页面：`/platform/smart-query`（真实工作区，四个页签，加载 / 空 / 错误三态齐全）
- 测试：`SmartQueryIntegrationTest` 22 例 + `SmartQueryTenancyIntegrationTest` 7 例
  + `SmartQueryTestStub` 夹具；前端 `smart-query-model.test.ts` +
  `smart-query-allowlist.test.ts` 29 例；
  共享白名单 `api-proxy.ts` 改动后**全量 allowlist 回归 22 文件 / 116 例全绿**
- 契约：`packages/api-contracts/src/contracts/platform.ts`（新增 22 条 `SmartQuery*`）+
  `packages/api-contracts/src/platform.openapi.json`（新增 9 条路径）——
  **codegen 产物，未手改**

**关键设计决策**：

1. **封闭词表是物理边界，不是提示词约束** —— `SmartQuerySemanticCatalog` 是一个
   `final class`，全部内容是静态常量，**没有任何查询、没有任何 IO**。
   指标 12 个 / 维度 5 个 / 运算符 5 个 / 币种 5 个 / 时间窗 4 个。
2. **识别是确定性的，不是模型猜的** —— 指标码、单位、聚合方式与 13.1.1.1 的
   `ReportingMetricCatalog` **逐字一致**：语义层不重写一份口径，
   否则「智能查询里的销售额」与「自定义报表里的销售额」会长成两个数。
3. **解析不到就如实答「未接通」**，不返回任何数字、不返回硬编码列表、不抛异常。
4. **没有自由 SQL 入口** —— 这是蓝图加粗句的物理落点，不是事后补的校验。
5. **跨服务边界**：查询执行委托 13.6.1.1（reporting 服务），
   platform 侧只发**授权 HTTP 调用**，不碰对方的库。

### 接手时修掉的三处夹具 bug（上一任会话遗留）

L1 第一次跑：**29 例、6 红**。根因全在测试夹具 `SmartQueryTestStub`，不在被测代码：

1. **从 run URL 里取 `reportId` 的下标算错** ——
   `substring(lastIndexOf('/') - 3, lastIndexOf('/'))` 只取到**后 3 个字符**
   （`"ae8"` 而不是整个 uuid）⇒ 夹具找不到自己那份报表配置 ⇒ 退化成「公司口径单行」
   ⇒ 排序 / 依据条数 / 租户隔离断言全红。⇒ 改成取「倒数第二个 `/` 与最后一个 `/` 之间**整段**」。
2. **拼多行时漏了行间逗号** ⇒ 上游响应**不是合法 JSON** ⇒ 解析失败被包装成
   `RestClientException`，看起来像模块的问题，其实是夹具的问题。
3. **「算不出来」那一行用了与租户无关的固定店铺 id** ⇒ 两个租户会拿到**一模一样**的事实引用
   ⇒ 「A 与 B 的依据没有任何一条相同」这条租户隔离断言失去意义。⇒ 改成**租户自己的**第三个店铺 id。

🔴 三处都**没有改断言去迁就实现** —— 断言是对的，错的是夹具。

### 租户隔离三条断言

`SmartQueryTenancyIntegrationTest`（真实 PostgreSQL 16 + FORCE RLS，连接池压到 1 条连接）：
① 两租户互不可见（含**按主键直查**也拿不到）；
② 伪造 `X-Tenant-Id` 头无效（**且断言行确实落在会话租户名下**，不是只断言 A 看不见）；
③ 连接池归还后不串租户。

---

## 本批次的验证结论（L2 = r164）

```
L2 r164   START=2026-11-03 02:57   END=2026-11-03 03:21
HEAD=3456b2ab        BACKEND_TREE=47a95b84e62242767613804202f2e5a69fa98af8

前端六步   typecheck exit=0 / lint exit=0 / test exit=0 / nextbuild exit=0
           sync-check exit=0 / format-gate exit=0
           （format-gate batch base=61349b6c6501 head=3456b2ab3c62 mergeCount=7 violations=[]）
后端分块   ./mvnw -pl services/platform,services/identity -am verify → BUILD SUCCESS（13:04 min）
           tenancy（经 -am 带上）/ identity / platform 全部 SUCCESS
复用       catalog / channel / warehouse / trade / finance / reporting / crm ⇒ r163 GREEN（树哈希比对未变）

VERDICT=GREEN    本批新增失败=0    存量红=0
```

⚠️ 本批 L2 结论的完整原文在 `.dev-agent/l2/l2-r164-summary.txt` 与
`.dev-agent/l2/l2-r164-mvn-verify.log`（**该 summary 原本缺失，本轮补记**）。

### 静态预检

本批新增迁移 `V441`（platform 四表）+ `V441`（identity 权限码），
「已知互相破坏形状」扫描（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**
⇒ 跨车道 DDL 回归面 = 0。
