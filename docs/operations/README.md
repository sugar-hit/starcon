# 运维

## 子目录

- [`runbooks/`](runbooks/) — 标准运维手册（incident 响应步骤）
- [`incidents/`](incidents/) — 历史故障复盘（postmortem）
- [`on-call/`](on-call/) — On-call 排班 / 升级路径

## Runbook 列表

（待补充）

- `incident-response.md` — 故障响应通用流程
- `db-failover.md` — DB 主从切换
- `kafka-rebalance.md` — Kafka 消费者组重平衡
- `tenant-data-leak.md` — 疑似跨租户数据泄漏
- `security-incident.md` — 安全事件响应

## Incident 复盘模板

每个 postmortem 一个文件，命名 `YYYY-MM-DD-<incident-slug>.md`：

1. 时间线（UTC）
2. 影响面（多少租户、多少订单、多长时间）
3. Root cause
4. 检测方式
5. 响应措施
6. 后续 action items（必须分配 owner 和 deadline）