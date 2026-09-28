# 商品中心：本地启动、SQL 迁移与功能范围

更新：2026-09-28。角色、子租户与数据范围的 V3 迁移已部署，并完成 Java21、真实 PostgreSQL 与浏览器验收。Java 21 + Spring Boot 3.5.6 + Maven 3.9.9；PostgreSQL 16；jOOQ 3.19.26；Flyway 11.7.2。版本由 backend/pom.xml 及 Spring Boot BOM 固定。

## 已运行的服务

| 服务       | 本地地址 / 数据库                 | 用途                                       |
| ---------- | --------------------------------- | ------------------------------------------ |
| 前端       | http://localhost:3000/products    | 开发 → 商品中心                            |
| PostgreSQL | 127.0.0.1:5433                    | 独立持久卷，不覆盖旧 starcon-postgres 容器 |
| identity   | 127.0.0.1:8081 / starcon_identity | 本地账户、成员、角色权限和会话             |
| catalog    | 127.0.0.1:8082 / starcon_catalog  | SPU、SKU、自定义属性及商品中心记录         |

数据库在同一集群但独立 database 与运行角色 identity_app/catalog_app，运行角色非 owner/superuser/BYPASSRLS。迁移使用单独的 starcon_migrator。历史引导角色 starcon_app 不再具有业务表/身份函数权限，后续不要使用它连接服务。

## 启动与登录

```sh
pnpm dev:backend
pnpm --filter @starcon/web dev
```

需要 Docker Desktop。后端由容器内 Java 21 构建运行，不依赖宿主机 Java 25。正常启动会由 Flyway 校验并执行尚未应用的迁移；启动返回后稍等接口就绪。

首次启动生成 `deploy/.env.catalog.local`（权限 0600，gitignored），包含随机本地密码与内部服务密钥。**本地登录账号为 local-admin，密码为该文件的 LOCAL_ADMIN_PASSWORD 值**。不要将该文件提交、贴入日志或复制到生产。首次引导创建一个本地开发租户与 Owner 成员，不插入演示商品。

浏览器在商品工作区登录，Next 服务端代理把会话保存在 HttpOnly/SameSite=Strict Cookie，浏览器不保存 bearer token。普通业务不读取 X-Tenant-Id；catalog 向 identity 验证当前会话及操作权限，再以事务级租户上下文查询数据库。

当前顶栏“体验用户”浮层仍是原 UI 原型，不代表本轮真实工作区账号，也不能切换后端角色。本轮登录入口位于商品中心，退出同样在那里。

## SQL 文件与同步文件

唯一可执行表结构来源：

- `backend/services/identity/src/main/resources/db/migration/V1__identity_and_page_permissions.sql`
- `backend/services/identity/src/main/resources/db/migration/V2__separate_service_accounts.sql`
- `backend/services/catalog/src/main/resources/db/migration/V1__catalog_and_flexible_attributes.sql`
- `backend/services/catalog/src/main/resources/db/migration/V2__separate_service_accounts.sql`
- `backend/services/identity/src/main/resources/db/migration/V3__tenant_administration.sql`
- `backend/services/catalog/src/main/resources/db/migration/V3__record_data_scopes.sql`

部署与同步：

- `deploy/docker/docker-compose.catalog.yml`：PG、两个应用及 migrate-identity/migrate-catalog 一次性迁移任务。
- `deploy/docker/Dockerfile.backend`：Java 21 多阶段构建；运行时普通用户。
- `deploy/docker/catalog-init/01-databases.sh`：仅负责建独立库与数据库角色，不承载业务表 SQL。
- `deploy/scripts/catalog-dev.sh`：生成本地配置、构建并启动。
- `deploy/scripts/catalog-db-sync.sh`：独立同步数据库，不要求启动 HTTP 服务。

```sh
pnpm db:migrate          # 构建当前迁移镜像，分别执行两个库的 Flyway 任务
pnpm db:generate         # 从已迁移 catalog 数据库生成 jOOQ 到 target/generated-sources/jooq
pnpm contracts:generate # 从两个 Java 服务的 OpenAPI 生成 TS DTO 和 spec 快照
```

重复迁移验证已通过，已有版本保持不变。后续改表新增 `V4__*.sql` 等版本，**不修改已成功应用的 V1/V2/V3，不使用 clean、删卷或 repair 掩盖校验失败**。某一服务迁移成功不代表另一服务成功；脚本失败即停止，应读具体服务日志后修复。跨库变更按向后兼容顺序发布，不承诺两个库一个事务回滚。

jOOQ 生成目标是数据库真实结构；目前业务查询使用显式绑定参数的 jOOQ SQL，可审阅且受 RLS 保护。生成物不手改、不提交。前端通过后端 DTO 生成的 `packages/api-contracts/src/catalog.ts` 使用契约，接口变化后重新生成。

## 当前建表与实现

V1/V2 的 identity 有 8 张应用表；V3 增加部门、页面资源、租户闭包、审计和 outbox 共 5 张表。catalog 保持 11 张应用表；两库另各有 Flyway 历史表。

- 商品档案：SPU 新建/编辑/搜索/状态、草稿删除；SKU 新建/编辑/状态/草稿删除；规格组合唯一，启用校验必填值。
- 每 SPU 自定义属性：文本、数值、布尔、日期、选项；单值/多值；必填、规格轴、单位、分组。
- 公共固定、公共默认、SKU 独立；继承/覆写/显式清空/恢复默认；矩阵显示完整有效值和来源；默认值更新保留 SKU 覆写。
- 选品与开发：项目标题、商品关联、目标国家、评估备注、资料链接及状态。
- 合规与资质：资质记录、商品关联、目标国家、有效期、资料链接及状态。
- 素材与翻译：资料链接、语言、人工文案及状态。
- 真实登录、成员解析、每页 CRUD 权限验证、租户 RLS；业务变更审计和本地 outbox 同事务保存。

接口详情见运行服务 `/v3/api-docs`。列表当前返回最近 200 条，界面明确说明统计与搜索范围；未做全库分页搜索。

### 与完整设计的阶段差异

本次实现优先覆盖商品可配置属性闭环：attribute_definition 是 **SPU 局部定义与绑定的合并表**，attribute_value 通过可空 sku_id 区分公共值与 SKU 覆写，明细强类型存储。没有重复的 sku_variant_value 值源。完整设计中的租户属性库、类目模板、跨租户发布导入仍待实现；后续可提取公共定义，保留现有 ID 与值引用。

品牌/分类当前是 SPU 展示字段，单位是受长度约束的文本标签；品牌分类独立主数据、单位量纲转换、套装/库存/报关实体不在本轮实施范围。不能据此进行库存或报关运算。

已有属性的结构（类型、模式、单位、选项及是否规格轴）不可原地改变；可编辑名称、分组、必填与公共值。已有 SKU 不可新增规格轴，避免悄悄改变组合含义。完整的结构版本迁移向导后续实现。

V3 实现入口为商品工作区的角色配置、成员管理、部门管理和子租户管理。角色权限矩阵基于服务端页面资源，CRUD 操作必须在同角色中包含 read。每个成员角色分配独立选择 ALL / DEPARTMENT / SELF；角色停用或撤销后，下一次身份核验即使用新授权，不缓存旧授权。所有授权与组织写操作由目标租户 Owner 执行；普通角色可以获得管理页只读权限。Owner 角色受保护，所有者转移使用独立接口，原所有者的 Owner 分配在同事务移除。

子租户创建显式指定首位所有者账号，可使用已有账号（密码留空），或设置密码创建本地账号。不能借成员添加修改已有账号密码。子租户闭包与首个管理员同事务建立，最大深度为根下 5 层，不开放移动或硬删除。可修改名称、暂停和恢复直属子租户；祖先暂停立即使后代的身份核验失败。父级不会自动获得子租户 membership。对子租户的成员管理须使用目标租户的显式成员身份，经工作区“切换租户”后操作；同一会话不能切换到他人的成员身份。

SPU 与选品/资质/素材记录的 owner_id 和 department_id 由后端在创建时记录，客户端不能指定或修改。SKU 和属性沿用 SPU 归属。SELF 匹配当前认证 user_id，DEPARTMENT 匹配当前 membership 的单个所属部门，ALL 只覆盖当前租户。部门变更不重写历史记录归属。历史记录从本库最早审计恢复创建人，不跨库推断历史部门；无法确定归属的行只允许 ALL，已恢复创建人的行亦支持 SELF。数据库额外使用 restrictive RLS 策略，操作和 read 与范围在同一分配中满足；未知/空范围、缺失上下文默认不可见。列表、详情、计数和写入均使用范围，详情分别裁剪未获授权的 SKU、属性及属性值。SKU 新建和编辑还需同商品的属性查看权限，避免编辑不可见属性时意外覆写值。前端按钮按同样的逐分配规则显示，最终以后端判断为准。

部署仍为本地账户模式，正式 IdP、outbox 投递及其他明确排除的模块未变更。

资质的“已确认”是人工状态记录，不代表自动认证通过；素材仅引用用户已有 HTTPS 链接，不下载远程文件，没有上传、自动翻译或到期通知。outbox 已可靠落库，跨服务事件投递 worker 尚未接入。

## 验证与持续集成

```sh
pnpm test:backend  # Docker 内 Java21 + Testcontainers PG16，临时测试库自动清理
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm exec playwright test tests/e2e/catalog.spec.ts
```

有本机 JDK21 时可用 `cd backend && ./mvnw verify`；格式检查 `./mvnw spotless:check`。Docker 29 与当前 Testcontainers 组合需要 `-Dapi.version=1.44`，容器测试脚本已配置。

后端测试覆盖：两租户互不可见、伪造 header 无效、单连接池复用不串租户、跨租户关联拒绝、普通角色非 bypass、会话注销与祖先停用、角色拒绝写入、属性继承/覆写/清空/恢复、false/0、重复组合回滚、版本冲突、状态校验及附属页面记录。

浏览器测试通过真实接口创建并清理测试草稿，覆盖公共值修改、SKU 独立值保留、刷新持久化、三个记录页面、跨站写请求拒绝和 390/320px 布局。截图位于本地 `work/catalog-desktop.png`、`work/catalog-mobile.png`。

`.github/workflows/ci.yml` 已新增 Java/PostgreSQL 集成测试和商品浏览器/契约检查任务；本轮仅验证本机执行结果，未声称远端 CI 已运行。

### V3 验证状态与契约导出

Java21 Docker 工具链下 19 项后端集成与契约测试、Spotless 检查通过；两库 V1/V2/V3 迁移成功。前端 typecheck、lint、36 项单元测试及生产构建通过。角色/成员/部门/子租户真实浏览器流程及受限成员、父子租户隔离验证通过。详细证据见 [租户权限验收记录](../sessions/2026-09/2026-09-28-tenant-roles-data-scopes.md)。

契约仍由后端 SpringDoc OpenAPI 生成。新增 `OpenApiContractTest` 使用 MockMvc 导出真实控制器/DTO 的文档，不需连接数据库或启动监听 socket；在依赖可用的环境可以单独生成：

```sh
cd backend
./mvnw -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
cd ..
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```

生成脚本支持从测试导出的文档生成 30 个 DTO 和两个快照；已验证重新生成无差异，未手改契约。正常服务启动后 `pnpm contracts:generate` 保持原用法。
