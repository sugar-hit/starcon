# V3 · 缓存结构设计（Redis + 本地缓存）

> 日期：2026-09-28。状态：设计基线，当前项目**无任何缓存依赖**（`grep redis|caffeine|@Cacheable` 0 命中），本文是从零设计。
> 依赖前提：[`README.md`](README.md) 的物理规约；租户隔离见 [ADR-0002](../../../decisions/0002-multi-tenancy-strategy.md)。

---

## 0. 一句话原则

> **Redis 里可以放"读起来慢但允许短暂不准"的东西（报表投影、平台目录、只读主数据）；
> 不放"算错就会亏钱"的东西（可用量、预占、余额、幂等键、权限判定）。**

理由：缓存的一致性只能做到"最终一致"，而 starcon 的一致性要求是**单据级精确**。可用量错 1 件就是超卖赔付，幂等键错一次就是重复扣款。

---

## 1. 分层

| 层 | 技术 | 存什么 | 一致性 |
|---|---|---|---|
| **L1** | Caffeine（进程内） | 极小且极热的：当前租户上下文、码表、平台能力矩阵 | 秒级，允许短暂过期 |
| **L2** | Redis | 跨实例共享的只读投影、限流计数、分布式锁、报表结果 | 秒级～分钟级 |
| **L3** | PostgreSQL | **一切权威事实** | 强一致 |

**默认走 L3。** 加缓存必须回答"这里错了会怎样"，答不上来就说明该数据不该缓存。

---

## 2. Key 规范（与 RLS 的关系是硬约束）

```
starcon:{env}:{service}:{tenant_id}:{domain}:{key_hash}
```

**`tenant_id` 必须在 key 里**，且必须在值里再带一次。理由：RLS 依赖事务内 `set_config('app.current_tenant')`，**数据库的隔离不会自动延伸到缓存**——key 不带租户前缀，一个 bug 就会让 A 租户读到 B 租户的缓存。

| 规则 | 说明 |
|---|---|
| 必须带 `tenant_id` | 无租户上下文的缓存一律不允许存在 |
| 禁止裸 `user:input` 做 key | 用户输入要 hash，防止 key 爆炸与注入 |
| 禁止跨租户共享业务缓存 | 只有**全局码表**（`platform` `unit_of_measure`）可以不带租户，用 `global:` 段 |
| TTL 必须显式 | 没有 TTL 的 key 是事故源（`k` 过期事件） |
| 换环境必须换前缀 | 防止测试环境读到生产缓存 |

**危险操作**：任何"把 A 租户的数据写进 B 租户 key"的路径，必须在 code review 时按 key 构造检查。跨租户查询应走 `global:` 或直接拒绝，而不是拼 key。

---

## 3. 可以缓存的（带 TTL 与失效方式）

| 域 | Key 示例 | 值 | TTL | 失效方式 |
|---|---|---|---|---|
| 平台目录 / 能力矩阵 | `starcon:{env}:channel:global:platform:{code}` | JSON | 24h | 全局刷新 + 版本号 |
| 币种 / 单位 / 国家码 | `starcon:{env}:catalog:global:uom:{code}` | JSON | 24h | 全局刷新 |
| 店铺只读档案 | `...:{tenant}:channel:shop:{shop_id}` | JSON | 10min | `shop.changed` 事件主动删 |
| SKU 只读展示信息 | `...:{tenant}:catalog:sku:view:{sku_id}` | JSON | 5min | `sku.changed` 事件主动删 |
| 分类树 | `...:{tenant}:catalog:category:tree` | 压缩 JSON | 10min | `category.changed` |
| 报表查询结果 | `...:{tenant}:reporting:q:{hash(sql+filters)}` | 结果集 | 60s | TTL 为主 |
| 首页指标卡 | `...:{tenant}:reporting:metric:{name}:{date}` | 数值 | 60s | TTL + 事件刷新 |
| 开放 API 限流计数 | `...:{tenant}:ratelimit:{api}:{app_id}:{window}` | 计数 | 窗口 | TTL |
| 平台接口配额余量 | `...:{tenant}:channel:quota:{connection_id}` | 数值 | 60s | TTL |

**报表类缓存要点**：key 必须是 `hash(规范化 SQL + 排序后的 filter + 数据范围)`。数据范围必须进 key——否则不同权限的两个人会命中同一个 key 而互相看到对方的数据（这是真实会发生的越权）。

---

## 4. 禁止缓存的（缓存了就等于埋雷）

| 数据 | 为什么禁 |
|---|---|
| **可用量 / `stock_bucket`** | 超卖赔付。预占必须靠 DB 原子条件更新（见 `02-*.sql` 的 `reserve_stock()`） |
| **预占与分配状态** | 迟到请求复活预占 → 重复发货 |
| **库存余额 / 流水汇总** | 账实不符，盘点与对账全崩 |
| **幂等键（`idempotency_request`）** | 缓存丢了幂等性就丢了，重复扣款/重复建单。幂等必须落在 DB 唯一索引上 |
| **inbox 去重记录** | 同上，丢一条就重复消费 |
| **凭证 / 应收应付余额** | 财务不可逆 |
| **权限判定（长 TTL）** | 撤销权限后缓存未失效 = 越权窗口 |
| **汇率与成本** | 换算快照必须与单据同事务落库 |
| **平台 token 明文** | 只存密钥管理器引用，缓存会扩大泄露面 |

**关于权限判定**：可以缓存，但**只允许 ≤30 秒 TTL + 事件失效 + 写操作实时校验**。读列表可以吃缓存，写操作（下单、改价、审批、删除）**每次实时校验**，不接受缓存命中。

---

## 5. 分布式锁：用与不用

**库存、预占、余额变更一律不用 Redis 锁**，改用数据库的原子操作：

```sql
-- 正确：单条 UPDATE 内完成判定与扣减，天然并发安全
UPDATE stock_bucket
   SET reserved = reserved + :qty, version = version + 1
 WHERE tenant_id = :t AND id = :b
   AND version = :expected_version
   AND on_hand - reserved >= :qty
   AND quality_status = 'GOOD'
RETURNING version;
```

**原因**：Redis 锁在故障切换、网络分区、锁过期时都可能失效，锁不住不等于数据安全；数据库的条件更新本身就是锁，而且和数据同生共死。

**可以用 Redis 锁的场景**（都是"可重试、幂等、错了不亏钱"的）：
- 同一平台连接串行同步（防触发限流）
- 同一租户的报表预计算任务去重
- 批量导入/迁移任务的并发抑制

分布式锁的硬性要求：必须有 TTL、必须设 owner token（防止 A 的锁被 B 释放）、释放前校验 owner、解锁用 Lua 脚本原子比较。

---

## 6. 缓存与事务的一致性顺序

**写路径：先落库，再删缓存**（cache-aside）。

```
BEGIN
  UPDATE stock_bucket ...          -- 1. 库内完成事实
  INSERT outbox_event ...          -- 2. 同事务写事件
COMMIT                             -- 3. 提交
-- 4. 事务提交后（或由 outbox relay 异步）删缓存
```

**不要在事务内删缓存**：事务回滚时缓存已经没了，但数据没变，会造成缓存穿透到库。

**读路径：先缓存，未命中回库并回填**；回填时用 `SET NX` 或加抖动 TTL，避免热点 key 击穿。

---

## 7. 防护三问

| 风险 | 手段 |
|---|---|
| **穿透**（查不存在的 key） | 负缓存（空结果也缓存，TTL 短，如 60s）；参数校验前置 |
| **击穿**（热点 key 过期瞬间大量回库） | 逻辑过期 + 单飞（single-flight）互斥重建；TTL 加 ±10% 抖动 |
| **雪崩**（大批 key 同时过期） | TTL 抖动；多级缓存；关键读降级到库并限流 |

**Redis 不可用时的降级**：
- 只读主数据 / 报表：直接回源 DB（慢但正确）
- 限流计数：降级为"放行 + 告警"，或本地计数
- 幂等 / 预占 / 余额：**不可降级**，直接失败并提示——这些本来就不该走缓存

---

## 8. 监控（必须有，否则缓存会变成黑盒）

| 指标 | 用途 |
|---|---|
| `cache_hit_ratio` 按域拆分 | 判断哪类缓存白建了 |
| `cache_key_count` / `memory_used` | 容量与 key 爆炸 |
| `eviction_count` | 内存不足被淘汰 → 命中率塌陷 |
| `command_latency` | 慢命令往往是"大 value"或"热 key" |
| 缓存与库不一致的抽查（抽样对账） | **唯一真正的健康指标** |

**上线顺序**：先上监控 → 再灰度 1 个域 → 观察一周 → 再扩。没有监控的缓存不要上。

---

## 9. 落库之外的"缓存"：别忘了 PG 自己

不是所有读放大都要 Redis 解决，先看这三样：

1. **覆盖索引**：`stock_bucket(tenant_id, sku_id) WHERE available > 0` 这类部分索引，比任何缓存都快且不会不一致
2. **物化视图 / 汇总表**：报表类查询用 T+1 汇总表，别在 OLTP 上跑大范围聚合
3. **连接池与语句缓存**：p6spy 都比缓存便宜

**判断顺序**：加索引 → 加汇总表 → 加请求内缓存 → 才是 Redis 分布式缓存。跳到第四步通常是过早优化。
