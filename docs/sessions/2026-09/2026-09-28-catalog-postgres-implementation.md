# 2026-09-28: PostgreSQL 与商品中心实现

## 摘要

按用户要求启动本地 PostgreSQL、保存可重复部署 SQL，并实现开发→商品中心。使用独立 5433 实例与持久卷，保留旧项目容器。Java21 identity/catalog 微服务、Flyway、jOOQ、Next 真实工作区联调完成。

## 决策 / 结论

- 独立 identity/catalog 数据库和普通运行角色，迁移与运行凭据分开；本地随机密码配置不进仓库。
- SPU/SKU 和每商品强类型属性支持公共固定、默认覆写、独立值，规格唯一、版本锁、RLS、审计/outbox。
- 商品中心四个入口均接入 PostgreSQL。选品/资质/素材为基础人工记录功能，自动审核/通知/上传/翻译不在本轮范围。
- 后端本地真实登录独立于既有顶栏账户原型；角色绑定目标租户，不信任客户端 tenant header。
- 局部属性定义与绑定合并、公共值和 SKU 覆写统一头表以 sku_id 区分；完整目标模型未实施部分明确记录。

## 文件清单

- backend/pom.xml、mvnw、libraries/tenancy、services/identity、services/catalog
- 各服务 src/main/resources/db/migration/V1、V2 SQL
- deploy/docker/docker-compose.catalog.yml、Dockerfile.backend、catalog-init
- deploy/scripts/catalog-dev.sh、catalog-db-sync.sh、catalog-db-generate.sh、backend-test.sh
- apps/web/src/app/products、api/catalog、components/catalog
- tools/codegen/catalog-contracts.py、packages/api-contracts/src（生成物）
- tests/e2e/catalog.spec.ts、.github/workflows/ci.yml、package.json
- AGENTS.md、docs/architecture/overview.md、db/schemas/v2/README.md
- docs/operations/catalog-local-development.md

## 关键 trade-off

先交付真实商品管理和属性闭环，未一次创建所有 ERP 目标表。属性结构暂不可原地修改，避免破坏已有 SKU。业务查询使用显式参数化 jOOQ SQL；数据库结构生成流程另有 Maven profile。两库迁移分别执行，不假装跨库原子。每服务仅接受已实现的租户内 ALL 权限范围。

## 验证

Java21 构建及 Testcontainers 集成测试 10 项通过；前端 typecheck/lint、24 项单元测试、生产构建通过；商品完整浏览器流程通过；重复 Flyway 迁移无新增迁移，jOOQ 从真实库生成编译通过。导航回归结果见本轮最终报告。新增远端 CI 任务但未实际运行远端 CI。

## 后续待办

正式身份提供方、角色配置与子租户管理页面、受限数据范围；租户属性库和类目模板；独立品牌/分类/单位主数据；列表服务端分页；上传/自动翻译/资质提醒；outbox 投递 worker。实际支持范围详见操作文档，不把设计当已实现。
