> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 01 · 租户、组织与身份

owner 域：`tenancy`。这一域是 [ADR-0002](../../docs/decisions/0002-multi-tenancy-strategy.md) 里「租户上下文由已认证用户的成员关系解析」的物理落点。

## 表

### `tenant` — 根实体

```sql
CREATE TABLE tenant (
  id             UUID        PRIMARY KEY,
  code           TEXT        NOT NULL,
  name           TEXT        NOT NULL,
  status         TEXT        NOT NULL DEFAULT 'ACTIVE',
  timezone       TEXT        NOT NULL DEFAULT 'UTC',
  base_currency  CHAR(3)     NOT NULL DEFAULT 'USD',
  locale         TEXT        NOT NULL DEFAULT 'en-US',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_tenant_code     UNIQUE (code),
  CONSTRAINT ck_tenant_status   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
-- 只能看到自己这一行
CREATE POLICY tenant_self ON tenant
  USING      (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

`code` 是**全局唯一**（不带 `tenant_id`，因为它就是租户本身）。这是与「唯一约束必须带租户维度」的唯一例外。

### `organization` — 租户下的组织

```sql
CREATE TABLE organization (
  id          UUID        PRIMARY KEY,
  tenant_id   UUID        NOT NULL REFERENCES tenant (id),
  code        TEXT        NOT NULL,
  name        TEXT        NOT NULL,
  status      TEXT        NOT NULL DEFAULT 'ACTIVE',
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_organization_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT ck_organization_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  CONSTRAINT uq_organization_tenant_id UNIQUE (tenant_id, id)   -- 供复合外键引用
);
```

**关于「四个业务系统」**：`products / orders / inventory / finance` 这类划分是**工作界面与权限视角**，不是四套组织实体。所以这里不按业务系统拆 `organization`，避免同一批人在四个系统里被维护四份成员关系。业务系统的隔离通过 `permission` 表达，不通过组织树。

### `app_user` — 全局身份

```sql
CREATE TABLE app_user (
  id            UUID        PRIMARY KEY,
  email         CITEXT      NOT NULL,
  password_hash TEXT        NOT NULL,
  display_name  TEXT        NOT NULL,
  mfa_secret    TEXT,
  status        TEXT        NOT NULL DEFAULT 'ACTIVE',
  last_login_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_app_user_email  UNIQUE (email),
  CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);
```

- **不带 `tenant_id`，不启用 RLS** —— 一个用户可属于多个租户，身份本身是全局的
- `email` 用 `CITEXT`（需 `CREATE EXTENSION cititext`）：不区分大小写唯一，否则 `A@x.com` 和 `a@x.com` 能注册成两个账号
- 表名用 `app_user` 而不是 `user` —— `user` 是 PG 保留字，jOOQ 生成类名会撞

### `membership` — 租户上下文的解析依据

```sql
CREATE TABLE membership (
  id              UUID        PRIMARY KEY,
  tenant_id       UUID        NOT NULL REFERENCES tenant (id),
  user_id         UUID        NOT NULL REFERENCES app_user (id),
  organization_id UUID        NOT NULL,
  status          TEXT        NOT NULL DEFAULT 'ACTIVE',
  joined_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_membership_org FOREIGN KEY (tenant_id, organization_id)
    REFERENCES organization (tenant_id, id),
  CONSTRAINT uq_membership_tenant_user UNIQUE (tenant_id, user_id),
  CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
  CONSTRAINT uq_membership_tenant_id UNIQUE (tenant_id, id)
);
```

**`UNIQUE (tenant_id, user_id)` 是整个租户模型的关键约束**：一个用户在一个租户内只有一条成员关系。这让租户解析退化成一次唯一查找，而不是「可能多行、还要挑一个」。

一个租户内多组织目前不支持（`organization_id` 是单值），需要时再拆 `membership_organization`。已记入 [backlog](../../docs/backlog/2026-09/2026-09-27-data-model-open-questions.md)。

### `role` / `permission` / `role_permission`

```sql
CREATE TABLE role (
  id         UUID        PRIMARY KEY,
  tenant_id  UUID        NOT NULL REFERENCES tenant (id),
  code       TEXT        NOT NULL,
  name       TEXT        NOT NULL,
  is_system  BOOLEAN     NOT NULL DEFAULT FALSE,
  status     TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_role_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_role_tenant_id   UNIQUE (tenant_id, id)
);

CREATE TABLE permission (
  code     TEXT PRIMARY KEY,
  name     TEXT NOT NULL,
  category TEXT NOT NULL,
  -- 全局静态表，不带 tenant_id，不启用 RLS
  -- 例: 'order.read' / 'order.write' / 'inventory.adjust' / 'finance.settlement.read'
);

CREATE TABLE role_permission (
  tenant_id      UUID NOT NULL REFERENCES tenant (id),
  role_id        UUID NOT NULL,
  permission_code TEXT NOT NULL REFERENCES permission (code),
  CONSTRAINT fk_role_perm_role FOREIGN KEY (tenant_id, role_id) REFERENCES role (tenant_id, id),
  CONSTRAINT uq_role_permission UNIQUE (tenant_id, role_id, permission_code)
);

CREATE TABLE membership_role (
  tenant_id    UUID NOT NULL REFERENCES tenant (id),
  membership_id UUID NOT NULL,
  role_id      UUID NOT NULL,
  CONSTRAINT fk_membership_role_membership FOREIGN KEY (tenant_id, membership_id)
    REFERENCES membership (tenant_id, id),
  CONSTRAINT fk_membership_role_role FOREIGN KEY (tenant_id, role_id) REFERENCES role (tenant_id, id),
  CONSTRAINT uq_membership_role UNIQUE (tenant_id, membership_id, role_id)
);
```

`role` 按租户隔离（`is_system = TRUE` 表示租户创建时自动种下的系统角色），`permission` 是全局静态码表。这样允许每个租户自定义角色，同时不污染全局权限清单。

角色与权限是**多对多**，因为「运营主管」在 A 租户能退款、B 租户不能，是正常业务。

## 租户上下文注入链路

这是 ADR-0002 里唯一不允许有洞的路径：

```
请求 → 认证 (JWT 验签，取出 user_id)
     → 查 membership WHERE user_id = ? AND status = 'ACTIVE'
       （若带了 X-Tenant-Id，则作为额外过滤条件，不匹配即 403）
     → 得到 tenant_id / organization_id / roles
     → 开启事务：SELECT set_config('app.current_tenant', :tenantId, true)
     → 业务逻辑（此时 RLS 已生效）
     → 提交（事务结束，set_config 的 local 语义自动失效）
```

三个必须成立的前提：

1. **租户来源是 `membership`，不是请求头。** `X-Tenant-Id` 只能作为「用户确实有这个成员关系」的校验条件，不能作为租户来源 —— 否则伪造头即可越权
2. **`set_config` 第三参数为 `true`** = local 语义，只在当前事务有效，连接归还连接池后不残留
3. **每个写操作都在显式事务里。** 不在事务里的 SQL 拿不到租户上下文 → RLS 判定 NULL → 查不到任何行（fail-closed）

## 跨域暴露

| 给谁                        | 暴露什么                                                     |
| --------------------------- | ------------------------------------------------------------ |
| 所有域                      | `TenantId` 值类；`currentTenant()` 读当前上下文              |
| catalog / channel / order … | `MembershipService.resolveTenant(userId, claimedTenantId?)`  |
| 内部运营（`apps/admin`）    | 走 `starcon_internal` 角色 + 应用层白名单，绕 RLS 查询全租户 |

任何域都**不得**自己解析 `X-Tenant-Id`，也不得直接 `SELECT` `membership` 表 —— 这两件事必须经 `membership` 的公开 service，否则会出现第二个租户解析实现。

## 关键不变量

- `tenant.code` 全局唯一，创建时即占用（即使租户是 `CLOSED` 状态也不释放）
- 一个用户在一个租户内至多一条 `ACTIVE` membership
- 至少保留一个 `ACTIVE` 且拥有 `tenant.manage` 权限的成员（防止把租户锁死）
- `app_user` 的删除：物理删除前必须无任何 `membership`，否则外键拒绝
