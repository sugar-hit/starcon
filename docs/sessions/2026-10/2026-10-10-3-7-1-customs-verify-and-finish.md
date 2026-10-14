# 3.7.1.1 + 3.7.1.2 复验与收口（挂起后续跑）

日期：2026-10-10
分支：`feat/3-7-1-customs-attributes`
车道：3.7.1.1 / 3.7.1.2（lanes.json 登记为同一 branch + 同一 worktree，设计如此）

## 一句话

两个模块此前从未跑过验证，本轮把 `integration/module-dev` 并进来、跑完全量门禁、修掉
三个真实缺陷后双双落地。原台账上「product_compliance 阻塞」是**真阻塞但已绕开**，
不是代码建立在错误前提上。

## 决策摘要

### 1. product_compliance 阻塞：已消解，方式是另建表而不是拆表

台账原话是「`product_compliance` 同为 `LIKE product_research INCLUDING ALL` 复制，
装不了 HS 编码与认证要素。须先拆表」。核对结论：

- `V1__catalog_and_flexible_attributes.sql:77` 确实 `CREATE TABLE product_compliance (LIKE product_research INCLUDING ALL)`，
  而 `product_research` 的列是无 sku 维度、无唯一性约束的泛化记录面。
- V94 **没有拆这张表，也没有往里写 HS 编码**，而是另建 `customs_attribute` +
  `customs_declaration_element` 两张自有表。理由写进了迁移文件第零之二节，三条：
  往无主记录面塞 HS 编码等于「一个 SKU 两个码时无法回答报哪个」；申报要素是**有序**的，
  JSONB 给不了「同属性内位次唯一」与「required 必须有值」两条约束；拆既有表属跨车道写所有权变更。
- 代价是**放弃与 product_compliance 复用同一张表**，换来的是本车道不碰别人拥有的结构。

所以这条阻塞被**绕开而非解决**，且绕的方式让模块真的能交付 —— 台账 3.7.1.1 行已写明。

### 2. api-proxy 白名单求并：两层 alternation 都要并

集成头 `48e7fd5`（4.2.2.2 + 11.4.2.2）在**同一条 catalog 正则**上各自追加了分支。
用脚本解析顶层 alternation 求并，没手改那一行一万多字符的正则：

| 正则                   | 本车道 | 集成头 | 并集后                         |
| ---------------------- | ------ | ------ | ------------------------------ |
| #0 identity            | 20     | 20     | 20                             |
| #1 catalog             | 37     | 38     | **39**（+1 为 3.7.1.2 新增根） |
| #2 channel             | 9      | 9      | 9                              |
| #3 warehouse/inventory | 17     | 17     | 17                             |

catalog 那条的增量精确核对过：本车道独有 `customs-attributes`，集成头独有
`description-templates` 与 `kb`，其余 36 条两边重复、去重后各留一份 —— 39 = 37 + 2，
**没有丢分支**。

⚠️ 内层 `inventory/` 组那条正则两边**逐字相同**，git 自动合并没有冲突，
所以「只并内层会静默丢外层」这个坑在本轮没有发生；但核对脚本仍逐条比对了四行正则。

### 3. 顺手补上的三个真实缺陷（都不是「顺手」，是会导致静默 404 的那类）

1. **3.7.1.2 的 6 个端点从未登记进白名单** —— 后端有 `customs-destination-versions/*`
   与 `catalog/customs-declaration/resolve`，前端 model 也照这个路径发请求，但白名单里
   一个都没有。表现是**页面在浏览器端 404，而 typecheck / lint / vitest 全绿**
   （白名单只在浏览器端生效）—— 正是那个文件的注释里写明的失败模式。
2. **3.7.1.2 没有页面路由** —— `CustomsDestinationVersionWorkspace` 写完了却没有任何
   `page.tsx` 引用它，组件不可达。补 `/products/sku/[id]/customs-destinations`
   （与 3.7.1.1 同层的兄弟路由，两者审核态机不同不能合并成一页）。
3. **`draftToPayload` 把 `effectiveTo` 省略而不是发 null** —— 契约里是
   `string | null`（键必在、值可空），省略键会被读成「这个字段没传」，
   而 `null` 才是 +∞（规则 R1）。改为发 null，与 V25 `BomVersionInput` 同一口径。

## 蓝图硬要求自检

### 3.7.1.1 审核流程（真的拦住了「未审核即生效」）

- DB 层：`customs_attribute_reviewed` / `draft_unreviewed` / `rejected_needs_reason`
  / `approved_not_drafted` 四条 CHECK 双向钉死审核痕迹，`status='APPROVED'` 还需要
  `submitted_by` 非空（没有提交留痕就无法回答「这份资料是谁交上来的」）。
- 触发器：`guard_customs_attribute_review` 复钉 A1~A4；A4 是**跨表**的
  （APPROVED 时至少一条申报要素、且每条 required 的值非空，逐项点名），
  CHECK 做不到所以用触发器。`guard_customs_element_under_review` 让已过审属性的申报要素冻结。
- 服务层：`GET /declaration-ready` 在 `blockers` 非空时**一律 409**，
  六码 `NOT_RECORDED / NOT_APPROVED / SUBMITTED_AWAITING_REVIEW / DECLARATION_REJECTED /
NO_ELEMENTS / REQUIRED_ELEMENTS_INCOMPLETE` 各配中文标签。
- 权限分离：写 `catalog.compliance.update`、审 `catalog.compliance.review` 是两个独立权限。

**结论：拦住了。** 草稿与被驳回的行不是「数据不够好」，而是下游拿不到。

### 3.7.1.2「不同目的地必须用不同版本」（有数据结构与约束支撑）

不是界面上多一个输入框，是四层：

1. **DB 层 `CHECK (destination_country_code <> '*')`**（规则 B5）—— 通配在存储层就写不进来；
   `Rules.normalizeDestination` 再显式拒绝归一化成 `'*'` 的写法（`' * '` 与 `'*'` 同义）。
2. **目的地是业务主键之一**：`UNIQUE(tenant_id, sku_id, destination_country_code, version_no)`，
   且 `profile / effective / requirements / resolve` 四个取数口的 `destination` 参数全部必填。
3. **无生效版本时返回 409，不降级**：`/effective` 与 `/requirements` 宁可让下游拿不到，
   也不回退到基础报关属性 —— 那个回退就是「用一套申报资料打通全球」。
4. **生效区间口径与 V25 的 R1~R5 逐条一致**（判据：`from<=t AND (to IS NULL OR t<to)` +
   只认 `status='PUBLISHED'`；R2 重叠一律拒绝不裁决、R5 已发布只能向前收窄且 UPDATE 只写
   `effective_to`）。**理由**：两条轴都是「同一事实按时间切段」，口径不一致会让「当时报的是哪一版」
   在 BOM 与报关两侧对不上，而这条链要举证。

## 验证方式与真实结果

| 命令                                                         | 结果                                                                                          |
| ------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| `./mvnw verify`                                              | **BUILD SUCCESS**（7 模块，06:42）全仓 **2677 tests / 0 failures / 0 errors / 0 skipped**     |
| ├ 3.7.1.1                                                    | `CustomsAttributeIntegrationTest` 38 例 + `CustomsAttributeRulesTest` 20 例                   |
| └ 3.7.1.2                                                    | `CustomsDestinationVersionIntegrationTest` 37 例 + `CustomsDestinationVersionRulesTest` 22 例 |
| `pnpm typecheck`                                             | 3/3 通过（修了契约缺 6 个 DTO 与 `effectiveTo` 可选性两处）                                   |
| `pnpm lint`                                                  | 3/3 通过（仅 4 条既有 warning，在本车道不拥有的文件里）                                       |
| `pnpm test`                                                  | **102 文件 / 2027 tests 全通过**（含新增 34 例 + 白名单 106 例）                              |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 编译成功，`/products/sku/[id]/customs-destinations` 在路由表里                                |
| `node tools/module-progress/sync.mjs --check`                | ✓ 台账与蓝图一致                                                                              |

契约用 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
重新生成（1372 DTOs），**未手改任何 codegen 产物**。

## 关键 trade-off

- **求并而非二选一**：白名单宁可冗余也不能缺一条 —— 缺一条是浏览器端静默 404，
  typecheck/lint/vitest/Playwright 全绿也发现不了。所以校验写成「跑真实 proxy 断言 HTTP 状态码」，
  并配反向用例（`customs-destination-versions`、`.../skus/{id}/publish`、`customs-declaration`
  等必须仍被 404）。
- **不动 3.4.2.2 的既有缺陷**：`translation-coveragetranslations` 少一个 `|`，使
  3.4.2.2 已交付的 translations 浏览器端 404（backlog B-01）。那是别的车道的 `descriptions` 分支，
  本轮**没碰**，并在 union 测试里以 `CATALOG_KNOWN_BROKEN` 显式断言它仍是 404，
  让缺陷留在测试里而不是只留在某个人脑子里。
- **前端补测试而不是只补后端**：只钉后端的话，用户仍然能在页面上填出一个必然被后端拒绝、
  且看不出原因的值（通配目的地尤其如此）。所以 B5 与 R1 在前端也各钉了一遍。

## 影响面

- 迁移：新增 V94（3.7.1.1）、V95（3.7.1.2），版本号未动。
- catalog 契约：重新生成，新增 3.7.1.2 的 6 个 DTO。
- `apps/web/src/lib/api-proxy.ts`：白名单 39 → 40 条顶层分支。
- 台账：只改 3.7.1.1 / 3.7.1.2 两行的状态与证据，另加两条 L3 锚点路由（路由列由蓝图树驱动）。

## 后续待办

- B-01（backlog）：`translation-coveragetranslations` 缺 `|`，归 3.4.2.2 车道修。
- 3.7.1.1 / 3.7.1.2 并入集成头由调度器执行，本车道未自行 merge。
