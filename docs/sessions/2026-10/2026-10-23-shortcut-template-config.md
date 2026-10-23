# 2026-10-23: 快捷入口配置（1.2.2.2）

## 摘要

交付能力蓝图 **1.2.2.2 快捷入口配置**（卷 01 工作台与门户，L3 1.2.2 快捷入口下的第二格，
阶段 D7）。模块在 **identity 服务**新建两张表（`shortcut_entry_template` 岗位/角色快捷入口模板、
`user_shortcut_override` 个人覆盖项），落 10 个端点与真实前端工作区 `/dashboard/shortcuts`，
输出蓝图要的「最终生效的快捷入口集合（模板 + 个人覆盖的合并结果）」。

## 决策 / 结论

1. **两张表都用 `tenant_id NOT NULL` + DB 层 `ENABLE` + `FORCE` RLS**，policy 只认
   `app.current_tenant`（照抄 `V330__global_search_recent.sql`）。**FORCE 是必须的** ——
   表 owner 在 FORCE 下也受策略约束，否则迁移脚本用 owner 身份一连就绕过了隔离。
   identity 侧没有 `app.actor` 这个 GUC，所以「只看自己的」由服务层的 `membership_id` 谓词保证。
2. **「用户」用 `membership_id`，不用 `app_user.id`**：`app_user` 是跨租户全局账号，
   「这个租户里的这个人」的唯一身份是 membership。
3. **模板的适用范围用 `role_id` / `department_id` 两个可空列，而不是多态 `scope_ref`**：
   多态列没法加外键 —— ROLE 行的 scope_ref 指向 `role.id`、DEPARTMENT 行指向 `department.id`，
   一列同时挂两张表的外键会让其中一种 scope 永远插不进去。两个可空列 + 一个 CHECK 表达
   「恰好一个非空」能把**真正的外键**加回来，于是「模板指向一个不存在的岗位」在数据库层被拒。
4. **合并规则确定、可测，不依赖插入顺序**（七步，完整写进 `capabilities.mergeRules` 与台账）：
   命中模板 → `(priority, code)` 排序 → 模板内 `(position, key)` 排序 → 跨模板按 key 去重
   （**第一次出现的那条赢**）→ 个人覆盖（**REMOVE 优先于模板存在**，UPSERT 逐字段覆盖，
   未给字段沿用模板项）→ **权限闸** → 最终 `(position, key)` 排序。
5. **「默认值必须在权限范围内」落成服务层的权限闸**：入口目标页面 `pageCode` 对应的
   `<pageCode>.read` 不在当前用户权限集合里 ⇒ 该入口**不生效**（不是返回后再置灰）。
   权限变更后自动失效并回落，不需要任何同步任务。
6. **覆盖项只能覆盖模板给的入口，不能凭空新增** —— 允许新增就是「常用页面收藏」（1.2.2.1）
   的另一个实现，同一个能力两套真相。写入时 `targetKey` 必须命中当前生效集合，
   否则 400。模板后来删掉那个入口，覆盖项变孤儿（不产生任何入口，不报错也不复活）。
7. **入口形状钉在数据库层**：`shortcut_entry_shape_ok` / `shortcut_entries_shape_ok` 两个
   IMMUTABLE 函数 + CHECK，每条入口必须有 key/label/pageCode/path、key 在模板内唯一、
   path 必须是站内相对路径（不许 `..`）、key 受控为小写标识符 ⇒ **绕过服务层直连 SQL 也一样被拒**。
8. **模板是租户级配置，不是平台级**；权限码 `identity.shortcut.read`（读模板与生效集合、
   读写自己的覆盖项）+ `identity.shortcut.manage`（建/改/删模板），OWNER 全量授权（含存量 OWNER）。
9. **合并在后端算完，前端只渲染**：前端没有「我命中哪些模板」「我持有哪些权限」的真值，
   前端再算一遍合并就是造第二个真相。前端只保留**与后端逐字一致**的形状校验（为了让用户
   当场看到「入口标识只能小写」，而不是等 400）。
10. **api-proxy 末尾刻意不带 `?`**：本模块的裸路径 `shortcut-templates` 是**真的有端点的**
    （列表 + 新建，与既有 `identity/roles` 同构），所以用**显式 alternation** 把裸路径列出来，
    而不是靠一个可选组顺带放行 —— 形态与「本来没有裸集合端点却顺手用 `?` 放行」是两回事。

## 涉及文件

**迁移**
- `backend/services/identity/src/main/resources/db/migration/V335__identity_shortcut_template.sql`

**后端**
- `backend/services/identity/src/main/java/com/starcon/identity/ShortcutTemplateModels.java`
- `backend/services/identity/src/main/java/com/starcon/identity/ShortcutTemplateService.java`
- `backend/services/identity/src/main/java/com/starcon/identity/ShortcutTemplateController.java`

**后端测试**
- `backend/services/identity/src/test/java/com/starcon/identity/ShortcutTemplateIntegrationTest.java`（29 例）
- `backend/services/identity/src/test/java/com/starcon/identity/ShortcutTemplateTenancyIntegrationTest.java`（5 例）

**前端**
- `apps/web/src/app/dashboard/shortcuts/page.tsx`
- `apps/web/src/components/dashboard/shortcut-template-model.ts`
- `apps/web/src/components/dashboard/shortcut-template-workspace.tsx`
- `apps/web/src/components/dashboard/shortcut-template-model.test.ts`（25 例）
- `apps/web/src/app/api/shortcut-template-allowlist.test.ts`（4 例）

**共享文件（多车道共用）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚在 `prefix: 'identity/'`
  那条元素之前即 `saas-plans` 那条之前；既有条目一字未改也不重排）
- `packages/api-contracts/src/contracts/identity.ts` + `packages/api-contracts/src/identity.openapi.json`
  （codegen 产物，**只跑 codegen 不手改**）

**台账与日志**
- `docs/product/capability-blueprint/module-progress.md`（只改 1.2.2.2 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-23-shortcut-template-config.md`

## 关键 trade-off / 讨论

1. **entries 存 JSONB 还是拆子表**：拆子表要多一张表（本车道表名空间与同批另三条车道互不重叠，
   但表归属是调度者预先划定的，多建一张表会让人以为越界）；且入口清单是「整体读、整体改」，
   从不按单条关联查询。与同批 1.4.1.2 的 `user_preference.preference jsonb` 同一口径 ⇒ **选 JSONB**，
   但**形状用 DB 层 CHECK 钉死**，不让「JSONB = 什么都能塞」变成漏洞。
2. **一个岗位能不能有多张模板**：能。多张按 `(priority, code)` 排序合并，跨模板同 key 时
   先到的赢 —— 规则仍然完全确定。选「能」是因为现实里「运营专员-基础」「运营专员-进阶」
   都合理；选「不能」会让模板表多一个唯一约束但表达能力更弱。
3. **模板管理要不要独立权限码**：要（`identity.shortcut.manage`）。岗位模板是租户级组织配置，
   与「改自己的覆盖项」（个人面）是两回事，绑在同一个码上就没法把「运营主管能配本组模板」
   与「普通成员只能改自己的入口」分开授权。
4. **覆盖项该允许「恢复」被移除的入口吗**：允许。校验用的目标集合是「**经权限过滤后的模板项**」
   （不含个人覆盖），所以已经 REMOVE 掉的入口仍然可以被 UPSERT 改回来；
   若拿「最终生效集合」当校验目标，被移除后就再也回不来了。
5. **api-proxy 锚点选哪个 `prefix: 'identity/'` 元素**：文件里有 7 条同前缀元素，
   本车道选的是 `saas-plans` 那条（**距最近的 `catalog/` 元素 681 行、距 `warehouse/` 1326 行、
   距数组末尾 6768 行**），是全文件里隔离度最好的一条 ⇒ 与同批另三条车道的插入点互不重叠。

## 过程中修掉的真实缺陷

- **测试夹具的坑**：测试底座只建一个租户，**所有用例共用它**，而 TENANT 范围的模板对全租户可见
  ⇒ 用例 A 建的入口会出现在用例 B 的生效集合里；入口标识撞车时「谁的模板赢了」取决于模板编码的
  字典序，断言变得不确定。修法：**每个用例用带自己随机后缀的入口标识**（`key(prefix, tag)`），
  并且 `hiddenByPermission` 这类**全局计数**只断言下界（精确值随同租户里其它用例建的模板浮动）。
- **角色编码正则被小写后缀打回**：`suffix()` 返回大写十六进制，但拼接的 `"B"` 写成了小写 `"b"`，
  撞上 `RoleInput.code` 的 `[A-Z][A-Z0-9_-]{0,63}` ⇒ 400。改成大写后缀。
- **唯一键冲突按仓库既有口径返回 409 而不是 400**（`ApiErrorHandler` 的既有映射），
  测试断言改成 409 而不是去改全局映射。

## 后续待办

1. **1.2.2.1 常用页面收藏**（同批另一条车道 / 后续车道）：收藏列表的渲染与拖拽排序。
   本模块**刻意没有**收藏表，覆盖项也不许凭空新增入口 —— 落地时**不要**在本模块的覆盖项上
   兼职实现「新增入口」，那是同一个能力两套真相。
2. **前端入口的可达性**：`/dashboard/shortcuts` 目前是独立路由，卷 01 的工作台侧边栏
   没有登记它（既有 `/dashboard/search`、`/dashboard/todo` 同样没登记）。若要在工作台里
   给一个固定入口位，应由卷 01 的导航层统一登记，不要在本模块里另写一份导航。
3. **模板的作用域引用是整 UUID**：岗位/团队模板要填 `roleId` / `departmentId`（UUID），
   界面上目前是手填输入框。可用性更好的是下拉选择（17.3.1.1 角色管理 / 17.2.1.1 部门树
   都有现成的列表端点），留作后续界面改进。

## 关联

- 能力蓝图：`docs/product/capability-blueprint/01-dashboard-and-portal.md`（1.2.2 快捷入口）
- 台账：`docs/product/capability-blueprint/module-progress.md`（1.2.2.2 行）
- 多租户策略：ADR-0002 `docs/decisions/0002-multi-tenancy-strategy.md`
- 租户层级与页面权限：ADR-0005 `docs/decisions/0005-tenant-hierarchy-and-page-rbac.md`
- 同批兄弟车道：1.2.1.2（V331，前端聚合层）、1.4.1.2（V336，`user_preference`）、
  1.5.1.1（V338，channel）
