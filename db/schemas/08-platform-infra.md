# 08 · 平台基础设施：outbox、幂等、审计、附件

owner 域：`platform`。这些表支撑「外部副作用」与「第三方网络调用」的安全落地。

## 事务 outbox

外部调用（调平台 API、发通知、推 webhook）**不能放进业务事务** —— 事务无法回滚已经发出去的 HTTP 请求。所以模式是：业务数据和 outbox 事件**同事务提交**，worker 异步投递。

```sql
CREATE TABLE outbox_event (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  aggregate_type TEXT        NOT NULL,   -- 'ORDER' | 'INVENTORY' | 'SETTLEMENT' | 'SHOP'
  aggregate_id   UUID        NOT NULL,
  event_type     TEXT        NOT NULL,
  dedupe_key     TEXT,                   -- 业务级去重键
  payload        JSONB       NOT NULL,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz,
  attempts       INT         NOT NULL DEFAULT 0,
  next_retry_at  timestamptz,
  last_error     TEXT,
  CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- 部分索引：只索引未发布的事件，worker 的轮询查询走这个索引
CREATE INDEX idx_outbox_pending
  ON outbox_event (next_retry_at, occurred_at)
  WHERE published_at IS NULL;

-- 业务级去重：同一 dedupe_key 只会产生一个事件
CREATE UNIQUE INDEX uq_outbox_dedupe
  ON outbox_event (tenant_id, dedupe_key)
  WHERE dedupe_key IS NOT NULL;
```

必须成立的四点：

1. **`outbox_event` 与业务数据同事务写入。** 事务回滚，事件一起消失 —— 这才是 outbox 存在的意义
2. **投递语义是 at-least-once，不是 exactly-once。** worker 崩溃时可能重投，消费者必须按 `outbox_event.id` 去重
3. **消费者按事件 ID 去重并记录版本号**，这样乱序到达时不会把状态回退
4. `next_retry_at` 支撑指数退避；`attempts` 超过阈值后标记失败并告警，不无限重试

`payload` 用 JSONB 是**这里允许的**（见 [README](README.md#10-jsonb-使用边界)）—— 事件体只被顺序消费，从不按字段查询。

## 幂等记录

处理平台重复推送（webhook 重投、订单重复同步）。

```sql
CREATE TABLE idempotency_record (
  id              UUID        PRIMARY KEY,
  tenant_id       UUID        NOT NULL REFERENCES tenant (id),
  scope           TEXT        NOT NULL,   -- 'SHOPIFY_ORDERS_CREATE' | 'SHOPIFY_WEBHOOK'
  idempotency_key TEXT        NOT NULL,
  request_hash    TEXT        NOT NULL,   -- 请求体 SHA-256
  response_status INT,
  response_body   JSONB,
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  CONSTRAINT uq_idempotency UNIQUE (tenant_id, scope, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);
```

`request_hash` 的作用是**区分「重复投递」和「键冲突」**：

- 同 key + 同 hash → 已处理过，直接返回上次的结果（幂等成功）
- 同 key + **不同** hash → 说明两个不同的请求抢了同一个幂等键，必须报冲突并告警，**不能默默返回旧结果**

只存 key 不存 hash 的话，第二种情况会被误判成「重复投递」而返回旧响应，调用方以为成功了，实际处理的是另一份数据。

`expires_at` + 索引支撑定期清理。清理窗口要**大于平台的最大重推周期**，否则老订单重推会被当成新请求。

## 审计日志

```sql
CREATE TABLE audit_log (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  actor_type    TEXT        NOT NULL,    -- 'USER' | 'PLATFORM' | 'WORKER' | 'SYSTEM'
  actor_id      UUID,
  action        TEXT        NOT NULL,    -- 'UPDATE' | 'CREATE' | 'CANCEL' | 'ADJUST' | 'EXPORT'
  resource_type TEXT        NOT NULL,
  resource_id   UUID        NOT NULL,
  before_data   JSONB,
  after_data    JSONB,
  request_id    TEXT,
  ip_address    INET,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_audit_actor CHECK (actor_type IN ('USER', 'PLATFORM', 'WORKER', 'SYSTEM'))
);

CREATE INDEX idx_audit_resource ON audit_log (tenant_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_actor   ON audit_log (tenant_id, actor_id, occurred_at DESC);
```

**append-only**，不 UPDATE 不 DELETE。

`actor_type` 必须区分「人做的」和「系统做的」。库存盘亏、订单自动取消这类高风险操作，如果审计里只记 `actor_id` 而不记来源，事后没人分得清是员工操作还是定时任务干的。

`ip_address` 用 `INET` 而不是 `TEXT` —— 顺手就能做 IP 段过滤和范围查询。

## 附件

```sql
CREATE TABLE attachment (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  resource_type  TEXT        NOT NULL,   -- 'PRODUCT' | 'ORDER' | 'RETURN' | 'INVOICE'
  resource_id    UUID        NOT NULL,
  file_name      TEXT        NOT NULL,
  content_type   TEXT        NOT NULL,
  size_bytes     BIGINT      NOT NULL,
  storage_key    TEXT        NOT NULL,   -- 对象存储路径，字节不进库
  checksum_sha256 CHAR(64)   NOT NULL,
  uploaded_by    UUID        NOT NULL REFERENCES app_user (id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_attachment_size CHECK (size_bytes > 0)
);

CREATE INDEX idx_attachment_resource ON attachment (tenant_id, resource_type, resource_id);
```

**只存元数据，字节永远不进数据库。** 发票 PDF、商品图、批量导入文件都放对象存储，库里只留受权限约束的元数据。

`checksum_sha256` 让重复上传能被识别，也让「文件是否被改过」可验证。

`resource_type` + `resource_id` 是多态引用（和 `journal_entry.source_id` 同样的取舍），代价是没有外键完整性，收益是不为每种资源建一张关联表。附件场景可接受 —— 孤儿附件只是占点空间，不影响账目正确性。

## 跨域暴露

| 谁用                     | 用什么                                                               |
| ------------------------ | -------------------------------------------------------------------- |
| 所有域                   | `OutboxService.append(event)` —— 必须在业务事务内调用                |
| platform（webhook 入口） | `IdempotencyService.check(scope, key, requestHash)` / `.record(...)` |
| 所有域（写操作）         | `AuditService.record(...)`                                           |
| 所有域（文件）           | `AttachmentService`                                                  |

## 关键不变量

1. `outbox_event` 与业务数据**同事务**写入或一起回滚
2. 投递是 **at-least-once**；消费者按事件 ID 去重 + 记版本号防乱序回退
3. 同幂等键 + **不同** 请求 hash → 报冲突，**不返回旧结果**
4. `audit_log` append-only，且必须区分 `actor_type`
5. 附件字节不进库；`idempotency_record` 清理窗口必须大于平台最大重推周期
