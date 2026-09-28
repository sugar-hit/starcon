# 2026-09-27: SPU/SKU、子租户与页面权限

## 摘要

用户指出商品抽象不足，明确要求子租户、用户角色和每角色页面 CRUD。补充专项设计，同步 V2 字段、模型入口及架构指引；未创建业务代码或迁移。

## 决策 / 结论

- product 明确改为 spu；一个 SPU 多 SKU，默认商品也有 SKU；细化规格、描述属性、媒体、包装、报关与生命周期。
- 租户树采用 parent + closure，每节点独立业务边界；父级访问默认目标租户显式成员授权，策略仍待评审。
- 页面资源 × 操作权限支持 CRUD 与业务动作，多角色和数据范围结合；权限和范围必须来自同一分配。
- 集团商品默认显式发布/导入独立目录，源版本保留；不共享库存、不隐式覆盖子租户。

## 关键 trade-off

显式授权和商品导入增加管理步骤，保留租户隔离及历史稳定性。子租户和页面 CRUD 是确定需求，默认父级访问与目录共享是建议，记录于 Proposed ADR-0005。

## 涉及文件

- db/schemas/v2/02-product-tenant-permissions.md
- db/schemas/v2/01-foundation.md、README.md
- docs/decisions/0005-tenant-hierarchy-and-page-rbac.md、README.md
- docs/architecture/overview.md、AGENTS.md

## 验证

检查本轮文档链接、代码块与空白差异；新增隔离、角色组合越权、商品关系等验收清单，未运行后端测试（无后端实现）。

## 后续待办

评审父级业务访问、共享商品默认策略；生成 D1/D2 迁移前补接口及授权撤销缓存时限，逐项运行真实 PostgreSQL 测试。
