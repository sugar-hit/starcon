# 在 jOOQ 绑定层统一做时刻精度对齐

- 创建: 2026-11-04
- Owner: TBD
- 优先级: P1
- 关联: `docs/sessions/2026-11/2026-11-04-nanosecond-microsecond-boundary.md`、ADR-0002（多租户）

## 背景

2026-11-03 的后端 CI 红灯里，六个用例死在同一条缝上：**Java 的时刻到纳秒，
PostgreSQL 的 `timestamptz` 只存到微秒**。

- `OffsetDateTime.now()` 在 Linux 上带亚微秒尾巴（`clock_gettime`），
  在 macOS 上恰好微秒对齐（`gettimeofday`）⇒ 同一份代码，**只在一半机器上出错**；
- 写进去前不对齐 ⇒ PG 把第 7 位小数落到微秒，「写进去的」与「读回来的」不是同一个数；
- 更隐蔽的是分桶：`date_bin(stride, source, origin)` 的原点被 PG 落到微秒，
  而 Java 侧按未对齐的原点算桶边界 ⇒ 两边差 0.x 微秒 ⇒ **所有桶的计数变成 0**，
  而总数、均分、分布全对 —— 图表整片空白，看上去像「最近没有新评价」。

本轮已在**逐个调用点**收口（`crm` 生产代码 2 处 + 测试夹具 4 处），但仓库里
还有 **2582 处 `OffsetDateTime.now(`**。只要有第二处「写进去的 Java 时刻」
被逐值比较，就会重演同一条缝。

## 待办内容

- [ ] 调研 jOOQ 的自定义 `DataType<OffsetDateTime>` / `Converter` 机制，
      在 `libraries/` 下做一个统一的绑定层：**所有写进 `timestamptz` 的时刻
      自动截断到微秒**
- [ ] 确认这个截断是**无损**的：PG 本来就存不下亚微秒，截断只是把
      「PG 四舍五入」换成「我们截断」—— 后者更可预期（值只变小、方向固定）
- [ ] 逐服务接入（9 个服务 × jOOQ 配置），每服务跑一次全量
- [ ] 接入后清理本轮加的逐点 `truncatedTo(ChronoUnit.MICROS)`（**逐条**清理，
      不许批量替换）
- [ ] 把 `DbPrecisionIntegrationTest` 的契约扩到「绑定层自动对齐」这条断言上

## 影响面

- 后端全部 9 个服务的 jOOQ 配置
- 所有写 `timestamptz` 的路径（订单、评价、违规、售后、结算、日志……）
- 测试：断言「写回值 == 原值」的那批用例会从「需要手工对齐」变成「天然对齐」

## 完成标准

- 任意服务里写进 `timestamptz` 的时刻，读回来与写进去**逐纳秒相等**（不需要调用方截断）
- 全仓不再出现「因为时刻精度而失败」的用例
- 9 个服务的全量测试通过
