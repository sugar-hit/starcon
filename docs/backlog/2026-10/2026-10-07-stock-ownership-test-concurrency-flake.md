# StockOwnershipIntegrationTest 在全量并发下随机红 —— 三个独立确认

日期：2026-10-07 23:36

## 现象

`com.starcon.warehouse.StockOwnershipIntegrationTest`
的 `货权档优先级是sku大于法人大于仓库大于兜底`
在全量 `./mvnw verify` 下**随机**失败，失败形态是 UUID 对不上：

```
expected: dd08e275-9f71-4ce5-9cf6-144403c5e1a4
 but was: ed09ae53-8bb4-44e3-bb75-9b7f15a54062
    at StockOwnershipIntegrationTest.java:466
```

同一分支上**隔离重跑必绿**：

```
./mvnw -pl services/warehouse test -Dtest=StockOwnershipIntegrationTest
→ Tests run: 40, Failures: 0, Errors: 0    BUILD SUCCESS
```

## 2026-10-07 一晚的三次独立确认

| # | 观察到的人 | 现象 | 隔离重跑 |
|---|---|---|---|
| 1 | 10.4.1.2 第一个 worker | 802 例里 1 例失败 | 40/40 绿 |
| 2 | 17.4.1.1 worker | warehouse 4F1E | 78/78 绿 |
| 3 | 集成头全量 verify（并入 17.6.1.1 后） | warehouse FAILURE | 40/40 绿 |

三次都在**不同的代码基线**上出现，且都不是自己那条车道引入的。

## 为什么值得单独立一条

这个 flake 一晚上让**三条车道**各花了几分钟到几十分钟去判断
「是不是我改坏了」——包括有一次差点去改本来正常的代码来迎合它。

对并行车道来说，这类不确定红比确定红更贵：它逼停验证、诱发错误修复、
并且污染「我的车道是否引入回归」这个本来应该清晰的问题。

## 疑似成因（未确诊，不要照此直接改）

失败点在 `StockOwnershipIntegrationTest.java:466` 附近，断言的是
「加了 WAREHOUSE 档 / LEGAL_ENTITY 档 / SKU 档之后，resolve 出来的 owner
应当按 SKU > 法人 > 仓库 > 兜底 胜出」。

它逐条 `rule(...)` 插入档位再逐条 resolve。若这些 `rule` 写入**没有隔离**：

- 多测试共用同一 tenant 时，先前测试遗留的档位行会参与 resolve，
  于是「兜底」不再是兜底，优先级断言就可能落到别的 owner 上；
- 全量并发下 surefire 多个 fork 的执行顺序与单测不同，残留更容易出现。

**建议的下一步（先诊断，不要直接改）**：

1. 先确认失败时数据库里是不是真有残留档位行 —— 跑一次该测试并打印
   resolve 实际命中的那条 rule 行；
2. 若确认是残留，给该测试的夹具加租户隔离（每次用新 tenant 或清表），
   而不是给 resolve 加容错；
3. **不要**为了让全量变绿去改 `resolve` 的优先级实现 —— 隔离重跑是绿的，
   说明实现是对的，红的是夹具。

## 处置纪律（已写进车道任务书）

遇到 `./mvnw verify` 红、而**隔离重跑绿**：

- 先隔离重跑确认归属；
- **不要改代码去迎合 flake**；
- 归属结论要写进交付报告（「与本车道无关，隔离重跑 N/N 绿」）。