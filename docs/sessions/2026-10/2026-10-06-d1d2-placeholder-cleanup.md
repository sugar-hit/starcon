# D1–D2 期占位数据清理：把「已交付但用不了」的部分补成真能用

- 日期：2026-10-06
- 范围：D1–D2 期（02 渠道与店铺 / 03 商品主数据 / 17 组织权限）的前后端代码
- 触发：用户要求「修复 D01 D02 里的所有前端、后端代码里所有 mock 数据、占位数据、占位逻辑」

## 结论先说

D1–D2 的**数据层本来是干净的**：catalog 三个迁移零 INSERT，identity 的 INSERT 全是全局码表，
渠道的平台/站点种子里是注册表常量而不是业务假数据。真正的问题不是"数据是假的"，
而是**一批模块被记成「已交付」，实际缺一半甚至全缺**——页面有壳、能力无入口、空态在骗人。

台账基线因此从 **已交付 33** 修正为 **已交付 29 · 进行中 3**（D1–D2 从 33 降到 29 + 3）。

## 改了什么

### 1. 渠道：后端有能力、页面无入口的死能力（全部补上入口）

| 模块 | 原状 | 现状 |
| --- | --- | --- |
| 2.3.1.2 店铺详情 | `ShopForm` 收 `connections={[]}`、类型写死 `never[]`，`connectionId/legalEntityId` 硬编码 `null`；详情页只能看不能改 | 表单按所选平台过滤真实连接 + 法人选择器；详情页可改名称/经营模式/绑定连接/法人（带版本号乐观锁） |
| 2.3.2.2 负责人与数据范围 | 转让端点存在，页面连入口都没有；`ownerId/departmentId` 从不显示 | 列表与详情显示负责人/部门；详情页可转让（含部门），走 `POST /shops/{id}/transfer` |
| 2.4.1.1 同步任务 | 只有列表，**没有创建入口**——没有游标就永远无法同步 | 可按「连接·站点·资源」登记任务；无连接时明说原因并指向授权连接 |
| 2.4.1.3 断点续传 | rewind 端点存在，页面只能暂停/恢复 | 补游标回退表单，带幂等与影响说明 |

### 2. 2.1.2.1 平台枚举映射：整个模块是空壳 → 做成能用的两层

原状是「一张没有任何写入方的全局只读表 + 一个零调用方的 `resolveEnum` + 一个没有界面的只读端点」，
按蓝图 2.1.2.1 的原文「平台默认 + 租户覆盖」根本没实现。

- `V3__platform_enum_tenant_override.sql`：新增 `platform_enum_override`（`tenant_id NOT NULL` +
  RLS FORCE），并**收回运行角色对 `platform_enum_mapping` 的写权限**
  ——V1 末尾那句 `GRANT ... ON ALL TABLES` 把「全局只读码表」的纪律在库层打开了，本迁移关掉它。
- `resolveEnum` 改为「租户覆盖 > 平台默认 > 未映射（返回 null）」，不再是零调用方的死方法。
- `POST /channel/platforms/{code}/enums` + `DELETE /channel/platforms/{code}/enums/{id}`，
  权限走 `channel.registry.update/delete`。
- 新增 `enum-mapping-panel.tsx`：平台目录里可查看/维护映射，**空映射时显式说明后果**
  （同步会报「未映射」并停在失败队列），不再是一个看起来"没配置而已"的空框。
- 删掉从未被引用的 `EnumMappingInput`，换成 `EnumOverrideInput`（平台由路径决定，不在 body 重复传）。
- 删掉旧的三参 `resolveEnum`，避免两套签名并存。

### 3. 组织权限：三处「假状态」

- `administration-panel.tsx`：`load()` 的 `catch` 没有 `setLoaded(true)`，任一接口失败页面**永远停在
  「正在加载…」**（串行 await 第一个抛错后面几个根本没发）。改为失败态 + 重试按钮。
- 同文件：成员表单的角色列表读不到时静默渲染 0 行，成员会被建成没有角色的裸账号。改为显式提示
  「当前角色没有角色配置查看权限」。
- `legal-entity-workspace.tsx`：无读权限时显示「还没有法人档案」，是在骗人（建法人同样会 403）。
  改为明确的无权限文案。
- 关联项显示统一走 `linkedName()`：读得到显示名字，读不到说「已设置但无查看权限」，**绝不谎报未设置**。

### 4. 首页引导的死链接

`/org/stores` 没有对应页面（`task-pages.ts` 自己标了 `implemented: false`），第一步点进去就是 404；
第三步指向 `/warehouse`（D3 未开发的占位页）。三步改为全部指向已交付工作区：`/channels`、`/products`、`/org`。

### 5. 台账诚实化

- **3.3.1.1 分类树管理**：从「已交付」撤回为「未开发 + 阻塞」。证据列写的
  `GET/POST/PUT /catalog/records/category` 在后端不存在（`CatalogService.table()` 白名单只有
  research/compliance/assets）、`api-proxy` 没放行、前端没有页面——三处都没有。
- 证据列里不存在的表名全部改成真实对象：`child_tenants`→`tenant + tenant_closure`、
  `member`→`membership + app_user + role_assignment`、`page_permission`→`page_resource + permission`。
- **17.2.1.1 部门树** 改「进行中」：建表没有 `parent_id`，页面是平铺列表，层级与部门负责人未做。
- **17.5.3.1 审计日志** 改「进行中」：只有写入（`GRANT INSERT`），没有查询端点与页面。
- **2.2.1.2 授权向导** 改「进行中」：建连接与凭据引用是真的，平台 OAuth 跳转/回调未接，页面不假装有跳转。
- `sync.mjs`：`进行中` 的定义从「有分支在做」改为「部分交付、未收口」；分阶段表补「进行中」列
  （原来把它并进「未开发」，会让排期以为那块完全没动过）。

## 关键 trade-off

**平台枚举映射要不要给租户写权限？** 蓝图写的是"平台默认 + 租户覆盖"，但 `platform_enum_mapping`
是全局只读码表，直接开租户写权限等于让任意租户污染所有租户的订单状态机。
所以拆成两层：全局默认只读（且在库层收回写权限，有测试守着），租户覆盖带 `tenant_id` + RLS。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，78 tests（identity 13 / catalog 22 / channel 43），
  新增 5 例：覆盖生效顺序、删除回落默认、未映射不兜底、租户隔离与跨租户删除被拒、
  运行角色写不了全局默认表。
- `pnpm lint` / `pnpm typecheck` / `pnpm test` → 66 tests 全绿，新增
  `apps/web/src/app/api/channel/[...path]/route.test.ts` 守着枚举映射的代理白名单。
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致。

## 仓库事故记录（重要）

本次改动在提交时被仓库里的**每小时开发流水线**并发提交卷走：另一路任务在 23:45 执行
`feat(catalog): 交付 3.1.1.3 SPU 生命周期状态机与批量规则`，把本会话尚未提交的
渠道 V3 迁移 / ChannelService / ChannelController / ChannelModels / ChannelIntegrationTest /
enum-mapping-panel.tsx / api-proxy.ts / administration-panel.tsx / legal-entity-workspace.tsx
/ 三份契约一起 `git add` 进了它自己的提交。

代码没丢、测试全绿，但 **89994e9 这条提交的 message 与它实际包含的内容不符**，
按 message 找渠道与组织权限的改动会找不到。后续接手者请以本文件与代码为准，
不要相信 `89994e9` 的标题。

## 后续待办（不在本次范围）

- 3.3.1.1 分类树：需要先加 `product_category` 迁移 + 端点 + 放行代理，SPU 的 `category`
  目前是自由文本（设计稿要求外键），品牌 `brand`、单位 `base_unit` 同样是自由文本。
- 17.5.3.1 审计日志：缺查询端点与页面，运营目前查不到审计记录。
- 17.2.1.1 部门层级：`department` 缺 `parent_id`。
- 2.2.1.2 授权向导：平台 OAuth 跳转与回调校验需要各平台应用凭据。
