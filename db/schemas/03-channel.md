> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 03 · 渠道：平台、店铺、凭据、listing 与外部映射

owner 域：`channel`。这一域回答一个问题：**平台上的那个东西，对应我们内部的哪个东西。**

## 表

### `channel_type` — 平台类型码表

```sql
CREATE TABLE channel_type (
  code          TEXT PRIMARY KEY,     -- 'amazon' | 'shopify' | 'tiktok_shop' | '1688' | 'ebay'
  name          TEXT NOT NULL,
  docs_url      TEXT,
  supports_webhook BOOLEAN NOT NULL DEFAULT TRUE
  -- 全局静态表，不带 tenant_id，不启用 RLS
);
```

码表全局共享，因为「Shopify 是 Shopify」与租户无关。租户用不用是 `shop` 表的事。

### `shop` — 店铺

```sql
CREATE TABLE shop (
  id               UUID        PRIMARY KEY,
  tenant_id        UUID        NOT NULL REFERENCES tenant (id),
  channel_type_code TEXT       NOT NULL REFERENCES channel_type (code),
  code             TEXT        NOT NULL,
  name             TEXT        NOT NULL,
  external_shop_id TEXT        NOT NULL,
  country_code     CHAR(2)     NOT NULL,
  timezone         TEXT        NOT NULL,
  default_currency CHAR(3)     NOT NULL,
  status           TEXT        NOT NULL DEFAULT 'ACTIVE',
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_shop_tenant_code        UNIQUE (tenant_id, code),
  CONSTRAINT uq_shop_tenant_channel_ext UNIQUE (tenant_id, channel_type_code, external_shop_id),
  CONSTRAINT uq_shop_tenant_id         UNIQUE (tenant_id, id),
  CONSTRAINT ck_shop_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED'))
);
```

`uq_shop_tenant_channel_ext` 是**同步幂等的基础**：平台同步任务按 `(tenant, channel, external_shop_id)` 定位店铺，避免重复建店。

`default_currency` 独立于 `tenant.base_currency` —— 店铺结算币种由平台决定，一个租户可以有 USD 店和 EUR 店。

### `shop_credential` — 凭据**引用**

```sql
CREATE TABLE shop_credential (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  shop_id      UUID        NOT NULL,
  secret_ref   TEXT        NOT NULL,   -- Vault path / K8s External Secrets key，**不是密钥本身**
  key_version  INT         NOT NULL,
  status       TEXT        NOT NULL DEFAULT 'ACTIVE',
  granted_at   timestamptz NOT NULL,
  expires_at   timestamptz,
  rotated_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_cred_shop FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT ck_credential_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED'))
);

-- 一个店铺同时只能有一个 ACTIVE 凭据
CREATE UNIQUE INDEX uq_credential_active_shop
  ON shop_credential (tenant_id, shop_id) WHERE status = 'ACTIVE';
```

**这里绝对不存密钥本体。** `secret_ref` 只是指向 secret manager 的指针。这条没有例外 —— 一旦密钥进库，它就会进备份、进 dump、进误操作的 `SELECT *` 日志，而且轮换时无法安全地「改而不留痕」。

凭据做成**多行 + 部分唯一索引**而不是「一行 + 一个 `is_active` 布尔列」，是为了能保留轮换历史：旧凭据 `ROTATED`/`REVOKED` 留档，排查「某段时间同步为什么失败」时需要它。

### `listing` — 平台商品

```sql
CREATE TABLE listing (
  id                  UUID        PRIMARY KEY,
  tenant_id           UUID        NOT NULL REFERENCES tenant (id),
  shop_id             UUID        NOT NULL,
  external_listing_id TEXT        NOT NULL,
  external_sku        TEXT        NOT NULL,
  title               TEXT        NOT NULL,
  status              TEXT        NOT NULL DEFAULT 'ACTIVE',
  first_seen_at       timestamptz NOT NULL DEFAULT now(),
  last_seen_at        timestamptz NOT NULL DEFAULT now(),
  deleted_at          timestamptz,
  CONSTRAINT fk_listing_shop FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT uq_listing_shop_ext UNIQUE (tenant_id, shop_id, external_listing_id),
  CONSTRAINT uq_listing_tenant_id  UNIQUE (tenant_id, id),
  CONSTRAINT ck_listing_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED'))
);
```

`listing` 是**软删除例外**（见 [README](README.md#8-软删除)）：平台可能重推已下架商品，如果这里物理删除，重推时会建重复行，历史订单行指向的 listing 就成了孤儿。

### `listing_sku_mapping` — 外部 SKU ↔ 内部 SKU

```sql
CREATE TABLE listing_sku_mapping (
  id              UUID        PRIMARY KEY,
  tenant_id       UUID        NOT NULL REFERENCES tenant (id),
  listing_id      UUID        NOT NULL,
  shop_id         UUID        NOT NULL,   -- 刻意冗余，用于表达唯一性
  sku_id          UUID        NOT NULL,
  external_sku    TEXT        NOT NULL,
  quantity_ratio  NUMERIC(19,6) NOT NULL DEFAULT 1,
  deleted_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_map_listing FOREIGN KEY (tenant_id, listing_id) REFERENCES listing (tenant_id, id),
  CONSTRAINT fk_map_shop    FOREIGN KEY (tenant_id, shop_id)    REFERENCES shop (tenant_id, id),
  CONSTRAINT fk_map_sku     FOREIGN KEY (tenant_id, sku_id)     REFERENCES sku (tenant_id, id),
  CONSTRAINT uq_mapping_shop_external UNIQUE (tenant_id, shop_id, external_sku),
  CONSTRAINT ck_mapping_ratio CHECK (quantity_ratio > 0)
);
```

**`shop_id` 是刻意冗余的**，因为业务规则的真实键是 `(tenant, platform, shop, external_id) → 内部 SKU`（见 [主数据调研](../../docs/investment/2026-09-27-backend-and-master-data.md)）。如果只对 `(tenant_id, listing_id, external_sku)` 唯一，就无法阻止「同一店铺下同一个 external_sku 被映射到两个内部 SKU」—— 那会让订单路由到错误的 SKU，是静默的数据损坏。

`quantity_ratio` 处理「1 个外部 listing item = 2 个内部装」这种组合装场景。默认 1。

### `channel_sync_state` — 同步游标

```sql
CREATE TABLE channel_sync_state (
  id                  UUID        PRIMARY KEY,
  tenant_id           UUID        NOT NULL REFERENCES tenant (id),
  shop_id             UUID        NOT NULL,
  resource            TEXT        NOT NULL,   -- 'orders' | 'listings' | 'returns'
  cursor              TEXT,                  -- 平台返回的增量游标，不透明字符串
  last_run_at         timestamptz,
  last_success_at     timestamptz,
  status              TEXT        NOT NULL DEFAULT 'IDLE',
  consecutive_failures INT       NOT NULL DEFAULT 0,
  last_error          TEXT,
  CONSTRAINT fk_sync_shop FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT uq_sync_shop_resource UNIQUE (tenant_id, shop_id, resource),
  CONSTRAINT ck_sync_status CHECK (status IN ('IDLE', 'RUNNING', 'FAILED', 'RATE_LIMITED'))
);
```

`cursor` 用 `TEXT` 不加类型：不同平台的游标格式完全不同（Shopify 是 `updated_at_min` + id，Amazon 是 `LastUpdatedAfter`），强行统一成时间戳会丢信息。

`consecutive_failures` 存在就是为了让同步任务能**退避并告警**，而不是无限重试打爆平台配额。

## 跨域暴露

| 给谁               | 暴露什么                                                                                          |
| ------------------ | ------------------------------------------------------------------------------------------------- |
| order              | `ChannelMapping.resolveSku(shopId, externalSku): SkuId?`                                          |
| order              | `ShopService.findByExternal(channelCode, externalShopId)`                                         |
| inventory          | `ListingService`（预留占用 / 回填 listing 状态）                                                  |
| platform（worker） | `ShopCredentialService.currentSecret(shopId): String`（运行时从 secret manager 取，**不落日志**） |
| platform（worker） | `ChannelSyncStateService`                                                                         |

**`resolveSku` 返回可空是刻意的** —— 平台推来一个我们没映射过的 external_sku，不能猜一个 SKU 顶上去。调用方（order 域）必须走「未映射订单」流程：挂起并通知运营补映射，而不是猜。

## 关键不变量

- `shop.external_shop_id` 在 `(tenant, channel_type)` 内唯一
- 一个店铺同时至多一个 `ACTIVE` 凭据
- `listing_sku_mapping` 的 `(tenant, shop, external_sku)` 至多映射一个内部 SKU
- **凭据本体永不进库**，只存 `secret_ref`
- 平台 SKU 字符串**不能**当主键 —— 不同店铺会重复，且平台可能复用
