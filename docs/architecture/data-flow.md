# 关键数据流

> 配套 `overview.md`，逐流画清。本文档随功能上线持续补充。

## 1. 订单接入流（Amazon SP-API 为例）

```mermaid
sequenceDiagram
    autonumber
    participant Seller as 商家后台
    participant AMZ as Amazon SP-API
    participant Webhook as K8s Ingress
    participant Con as platform-connectors/amazon
    participant Q as Kafka (order.created.v1)
    participant Ord as modules/order
    participant Inv as modules/inventory

    Seller->>AMZ: 下单
    AMZ->>Webhook: POST /webhooks/amazon/order-created
    Webhook->>Con: 验签 + 反规范化
    Con->>Con: Amazon Order → Unified OrderDTO
    Con->>Q: produce (tenant_id, payload)
    Q->>Ord: 消费事件
    Ord->>Ord: 落库 + 去重 (idempotency_key)
    Ord->>Inv: RPC 预占库存
    Inv-->>Ord: reserved
    Ord->>Q: emit order.reserved.v1
```

幂等性：每条消息带 `idempotency_key`（Amazon 原生 order_id），下游表 `UNIQUE(tenant_id, idempotency_key)` 保证不重复消费。

## 2. 库存同步流

```mermaid
sequenceDiagram
    autonumber
    participant WMS as 仓库 WMS
    participant API as apps/api
    participant Inv as modules/inventory
    participant DB as Postgres
    participant Q as Kafka (inventory.changed.v1)
    participant Ord as modules/order

    WMS->>API: POST /webhooks/wms/stock-changed
    API->>Inv: 调 WMS 适配器
    Inv->>DB: 写库存变更日志（append-only）
    Inv->>DB: 更新 inventory_levels (with version)
    Inv->>Q: emit inventory.changed.v1
    Q->>Ord: 触发关联订单状态更新
```

乐观锁：每次库存写带 `version`，DB 层 `UPDATE ... WHERE version = $1`，失败重试。

## 3. 履约出库流

```mermaid
sequenceDiagram
    autonumber
    participant Ord as modules/order
    participant Ful as modules/fulfillment
    participant Ship as modules/shipment
    participant DHL as DHL API
    participant Q as Kafka

    Ord->>Ful: 触发履约 (RPC)
    Ful->>Ful: 拣货单生成（按仓库分单）
    Ful->>Ship: 申请运单 (DHL/FedEx/...)
    Ship->>DHL: create shipment
    DHL-->>Ship: tracking_number, label_url
    Ship->>Q: emit shipment.created.v1
    Ship->>Ful: 回调，附 tracking_number
    Ful->>Q: emit fulfillment.shipped.v1
```

## 4. 财务结算流

```mermaid
sequenceDiagram
    autonumber
    participant Ship as modules/shipment
    participant Fin as modules/finance
    participant FX as packages/money (汇率服务)
    participant DB as Postgres
    participant Q as Kafka (finance.recorded.v1)

    Ship->>Fin: 履约完成事件
    Fin->>FX: 拉取结算日汇率（USD → CNY）
    FX-->>Fin: rate, source, timestamp
    Fin->>DB: 写应收记录（amount, currency, base_currency, rate）
    Fin->>Q: emit finance.recorded.v1
```

汇率来源：ECB / 央行公开数据 + 内部加权平均，每日快照存 `fx_rates` 表。

## 5. 跨租户数据汇总流（BI）

```mermaid
graph LR
    A["apps/api<br/>(业务库写入)"] -->|"binlog / outbox"| B["Kafka<br/>(cdc topic)"]
    B --> C["services/data-pipeline<br/>(Debezium + Flink)"]
    C --> D["ClickHouse<br/>(BI 库)"]
    D --> E["apps/admin<br/>(BI 报表)"]
    D --> F["apps/web<br/>(租户自查看)"]
```

为避免 ClickHouse 写入影响业务库，采用 **outbox 模式**：业务写入同事务下写 outbox 表，独立 worker 把 outbox 投递到 Kafka，下游异步消费。