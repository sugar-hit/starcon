# 16.6.1.1 PII 管理（阶段 D7，L3 锚点 16.6.1）

> 会话日期：2026-10-23 · 车道：16.6.1.1 · 分支：`feat/16-6-1-1` · 基线：`integration/module-dev`（e1b808e3）

## 决策摘要

实现蓝图 **16.6.1.1 PII 管理**：买家姓名 / 地址 / 电话 / 邮箱的访问与脱敏 ——
默认脱敏展示、按角色授权查看完整信息、访问留痕、导出审批。

**落点**：channel 服务，迁移 `V320__channel_pii_access.sql`（六张新表），
端点 `/channel/pii-access/**`（16 个操作），前端 `/compliance/pii-access`。

### 六条结构上的不变量

1. **默认脱敏是硬默认，不是可选开关。**
   策略表**刻意没有**「是否脱敏」「导出是否需要审批」两列 —— 做成可配置等于留后门。
   脱敏规则写死在 `PiiAccessRules` 里，掩码用 `*` 逐字替换 ⇒ **掩码值不是明文的可逆编码**。
2. **看完整值必须走一次授权判定**：角色 × 字段 × 成员范围（ALL / DEPARTMENT / SELF）。
   **任一字段没授权 ⇒ 整个请求被拒** —— 半授权会让调用方误以为「拿到了就等于拿到了」。
3. **每一次看完整值都落审计**，越权尝试也落。
4. **批量导出必须走审批**：申请 → 审批 → 执行；审批人 ≠ 申请人；导出对象逐条列明；条数有上限。
5. **PII 明文不进本服务**：只存授权配置、访问审计、导出审批与凭据。
6. **上游读不到 ≠ 查无此人**：上游不可达一律 503，查无此人返回 `known=false`。

## 关键 trade-off

### 1. 🔴 被拒的请求由控制器在事务提交后才转 403（而不是服务层抛异常）

服务层在事务里把那次越权尝试**写进了审计表**，而审计表是 append-only 的。
如果在事务里抛 `ResponseStatusException`，整个事务回滚 ⇒ **越权尝试的留痕会一起被回滚掉** ——
那恰恰是最该被看见的一类记录。

⇒ 服务层返回 `PiiAccessRevealOutcome` / `PiiAccessExportOutcome`（`allowed=false` + 理由），
控制器在 `access.run(...)` 返回（事务已提交）之后才抛 403。

**备选方案与放弃原因**：用 `PROPAGATION_REQUIRES_NEW` 在独立事务里写审计 ——
连接池压到 1 的多租户测试里会**死锁**（外层事务仍占着唯一那条连接）。

### 2. 🔴 SELF 成员范围对 ORDER 主体「判不了 ⇒ 拒绝」

crm 的接触记录带 `operator_id`，能判「本人是否经手」；但 trade 的 `sales_order` /
`order_address_snapshot` **没有经手人字段** ⇒ 订单主体在 SELF 范围下**判不了**。

选择「拒绝并写明『判不了』」而不是「默认放行」或「默认拒绝」——
理由写进审计，调用方能区分「确定不是本人经手」与「判不了」。
将来 trade 补上经手人字段，SELF 范围自然可判，**不用改表结构**。

### 3. 🔴 导出对象必须逐条列明（清单表 + 延迟约束触发器）

`pii_access_export_item` 的清单条数必须等于申请条数，由**延迟约束触发器**在提交那一刻核对。
挂在**申请行**上而不是清单行上 —— 挂清单行时「一条清单都没插」就不会有任何触发器跑起来，
申请写了 10 条、清单空了这种最坏情况反而没人拦。

多一条少一条都 23514 ⇒ **物理上没有「一键全量导出」这个形状**。

### 4. 脱敏规则：姓名「保留姓氏」在两种语言里位置不同

- 西式（含空白）：末段是姓 ⇒ 保留姓氏全文，名的部分只留首字母（`Alice Smith ⇒ A**** Smith`）
- 中文式（无空白）：首字符就是姓 ⇒ 保留首字符（`王小明 ⇒ 王**`）

**单字名是刻意的例外**：姓氏只有一个字符时「保留姓氏」意味着必须留下这个字符
（否则什么都不剩）。掩码值**不等于**明文，但**含有**那个唯一的字符 —— 测试里写明了这个例外，
不让后来的读者以为「掩码值必须不含明文的任何字符」。

### 5. 契约类型用列表而不是 Map

后端 `Map<String, String>` 会被契约 codegen 落成无类型的 `{}`（既有的 AccountHealth 阈值也是这个形状），
前端按字段名取值时 TS 直接报错 ⇒ 改成 `List<PiiAccessSubjectField>` / `List<PiiAccessFieldValue>`，
契约类型是真的。

### 6. 前端拿不到当前成员 id ⇒ 「审批自己申请的单」的按钮仍会显示

**由后端拒**（审批人 ≠ 申请人是 DB 层 CHECK）。前端不去猜当前成员是谁 ——
猜错了会让「自己批自己」在界面上看起来能过，比直接显示按钮更危险。

## 文件清单

**迁移**

- `backend/services/channel/src/main/resources/db/migration/V320__channel_pii_access.sql`

**后端（`com.starcon.channel`，扁平包）**

- `PiiAccessModels.java` — 传输模型
- `PiiAccessRules.java` — 脱敏与授权判定的纯函数
- `PiiAccessSourcePort.java` — 跨服务只读端口（trade + crm）
- `PiiAccessService.java` — 业务逻辑（jOOQ 显式 SQL）
- `PiiAccessController.java` — 16 个操作

**测试**

- `PiiAccessRulesTest.java`（18 例）
- `PiiAccessIntegrationTest.java`（25 例）
- `PiiAccessTenancyIntegrationTest.java`（4 例）
- `PiiAccessUpstreamStub.java`（本地 trade + crm 真实 HTTP 服务器）

**前端**

- `apps/web/src/components/pii-access/pii-access-model.ts`
- `apps/web/src/components/pii-access/pii-access-workspace.tsx`
- `apps/web/src/components/pii-access/pii-access-model.test.ts`
- `apps/web/src/app/compliance/pii-access/page.tsx`
- `apps/web/src/app/api/channel/pii-access-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**纯新增一条独立的 `services[]` 元素**，锚在数组末尾）

**契约（codegen 产物）**

- `packages/api-contracts/src/contracts/channel.ts`
- `packages/api-contracts/src/channel.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 `| 16.6.1.1 |` 那一行）

## 踩到并修掉的真问题

1. **`pii_access_grant` 漏了 `version` 列** —— 服务层的乐观锁与 `version = version + 1` 直接撞
   「bad SQL grammar」，五个用例连环报错。
2. **延迟约束触发器用错了 `NEW` 字段** —— 触发器挂在 `pii_access_export_request` 上，
   函数里却写 `NEW.export_request_id`（那张表没有这一列）⇒ 提交那一刻 PL/pgSQL 抛
   「record NEW has no field ...」，**把导出申请最常走的路径整个炸掉**。
   改成按 `TG_TABLE_NAME` 分流，两张表都能挂。
3. **`has_table_privilege(...)::text` 给的是 `true` / `false`**，不是 psql 的 `t` / `f` ——
   断言按 `t` 比会假失败，按「非 t 即假」比会假通过（`UPDATE` / `DELETE` 那两条）。
   顺带把表名加上 `public.` 限定：解析不到时 `has_table_privilege` 给的是 null 而不是 false。
4. **前端模型里的嵌套模板串**（`` `${suffix ? `?${suffix}` : ''}` ``）——
   白名单测试「从源码反向抽取真实调用点」的模板串正则匹配到内层反引号就断了，
   `subjects/{kind}/{ref}` 那条**抽不到** ⇒ 白名单漏登记时反而测不出来。拆成两个平铺的模板串。

## 后续待办

- **16.6.2.1 数据出境与留存未交付**：本模块**不写**数据出境登记、留存期配置与被遗忘权删除流程，
  页面上**没有**它的入口，也不声称已交付。
- **下游客服与运营页面的专属形状本模块不写** —— 那不是本模块的端点，
  放行未实现的路径只会让界面以为可用。
- **trade 侧订单缺经手人字段** ⇒ SELF 成员范围对订单主体恒为「判不了 ⇒ 拒绝」。
  trade 补上经手人字段后本模块可直接受益，**不用改表结构**。
- **`actor_name` 目前为空**：identity 的 `/internal/access` 不返回成员显示名，
  本模块**不改 identity**（跨车道回归面 = 0）⇒ 界面上显示成员 id 短码。
  identity 侧若将来返回显示名，本模块只需多读一个字段。
