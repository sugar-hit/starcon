# 统一错误处理器把「代码缺陷」翻成「业务拒绝」的 400/409，且不记日志 ⇒ 根因在响应里和日志里都消失（2026-10-21 取证）

**优先级：P1 —— 可诊断性（不是正确性缺陷）。** 交付的代码本身是对的，
但**当代码有缺陷时，故障现场会被翻译成一条「看起来像用户输入有问题」的响应**，
于是后来者（人或 agent）会沿着错误的方向排查一轮。

## 现象（2026-10-21 batch-50 续做车道 6.2.2.2，同一轮内两例）

接手时三条测试文件**结构完整但跑不通**：L1 `Tests run: 34, Failures: 5, Errors: 1`。
两个 HTTP 层的症状把排查方向带偏了：

| 症状 | 表面读起来像 | 真相 |
| --- | --- | --- |
| 「批量改数量」「批量改供应商」**全部 409** | 数据与既有记录冲突 ⇒ 刷新重试 | 迁移 V253 **少建了 `affected_line_count` 这一列**：服务写它、读它，建表时没有 ⇒ PG **42703 undefined_column** ⇒ jOOQ `DataAccessException` ⇒ 被翻成 409 |
| 「批量打印」**400「字段格式不正确」** | 调用方参数类型/数量/必填项不对 | 打印读行表时用 `select *` 且**漏连草稿**，而供应商快照列在**草稿**上 ⇒ jOOQ 取不到 `supplier_code` ⇒ `IllegalArgumentException` ⇒ 被翻成 400 |

**两个都不是输入问题**：请求完全合法，坏的是代码。
第三例是 `sort_rank` 在 insert 里被写死成字面量 `1`（同草稿内所有行位次相同），不报错、只是数据错。

## 根因：统一错误处理器既不改写语义、也**完全不记日志**

`backend/services/trade/src/main/java/com/starcon/trade/ApiErrorHandler.java`：

```java
@ExceptionHandler({ org.jooq.exception.DataAccessException.class,
                    org.springframework.dao.DataAccessException.class })
ResponseEntity<ProblemDetail> database(Exception e) {
  return ResponseEntity.status(HttpStatus.CONFLICT)
      .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
          "履约路径数据与既有记录冲突，或记录仍被引用。请刷新后检查输入。"));
}

@ExceptionHandler({ IllegalArgumentException.class, java.time.format.DateTimeParseException.class })
ResponseEntity<ProblemDetail> invalid(Exception e) {
  return ResponseEntity.badRequest()
      .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "字段格式不正确，请检查类型、数量和必填项"));
}
```

⚠️ **两个 handler 都收了 `Exception e` 但一次都没用它** ——
既没有 `log.error(...)`，也没有把 cause 塞进 `ProblemDetail`。
Spring 因为有 `@ExceptionHandler` 接管，**默认的「打印未处理异常」也不会发生**。

⇒ **后果**：PG 的 `42703`、jOOQ 缺的那个字段名、原始堆栈，
**在 HTTP 响应里没有，在服务端日志里也没有**。
最终只能靠**翻 surefire 报告 + 逐个读列常量与 row mapper** 才定位到 ——
这正是本例额外耗掉的时间。

**同样的 handler 在四个服务里同构**（`ApiErrorHandler.java` 的 javadoc 自己写着
「随 9.3.2.2 引入，与 warehouse / catalog / channel 同构」）：
`trade` / `warehouse` / `catalog` / `channel` 各有一份。
⇒ **这不是 trade 独有的问题，是四个服务共有的排查陷阱。**

## 为什么危险

1. **文案会主动把人带偏**。「请刷新后检查输入」暗示重试就能好；
   「字段格式不正确，请检查类型、数量和必填项」暗示是调用方填错。
   两者都会把人推向「改业务规则 / 改调用方代码」，而真正的错处是**服务端 schema 与代码不一致**。
2. **对 agent 更危险**：agent 看到 409 会先怀疑自己的并发/幂等实现，
   看到 400 会先怀疑自己的 DTO 校验，**两处都改不对**，反而把时间烧光。
3. **静默型缺陷更值得警惕**：第三例（`sort_rank` 写死）连异常都不抛，
   统一错误处理器帮不上忙，只能靠断言「同一草稿内行位次互不相同」才能发现。
   ⇒ **断言要写在数据语义上，不要只断言「没抛异常」**。

## 判定口径（下次遇到同类症状先套这个，能省一轮）

| 观察 | 判定 |
| --- | --- |
| 400/409 **换个合法输入也稳定复现** | **代码缺陷**（schema/ORM/映射），不是输入校验 |
| 400/409 **只在某类输入下出现、换个输入就变** | 才是真正的输入校验 / 业务拒绝 |
| 响应文案说的原因（字段格式 / 记录被引用）**与实际调用行为完全对不上** | 几乎一定是翻译层兜住了底层异常 |

⚠️ 6.2.2.2 的两例都落在第一行：调用方根本没传错任何东西。

## 建议（**只补可诊断性，不动状态码语义**）

`ApiErrorHandler` 把 23514 / 23503 归 409、把格式问题归 400，是**刻意的设计决定**
（javadoc 明确写了：业务拒绝走服务层的 `ResponseStatusException(BAD_REQUEST, "能看懂的话")`，
刻意不另开 handler，否则同一个业务错误在不同路径下会返回两种状态码，调用方无法一致处理）。
⇒ **这个决定要保留**，本条要修的是「根因不可见」，不是「状态码不对」。

1. **先记日志**（最小改动、收益最大）：两个 handler 里 `log.error/warn` 一下
   `e.getClass().getSimpleName()` + `e.getMessage()`（PG 的 SQLSTATE 与出错列名就在 message 里），
   并把 `e` 整个作为最后一个参数传进去。**光这一条就能把「翻 surefire 报告」变成「看日志」**。
2. **给 `ProblemDetail` 加一个机器可读的 `code`**（如 `code=db_conflict` / `code=invalid_argument`），
   让前端与集成测试能**区分「业务拒绝」与「兜底翻译」**。
   ⚠️ 只在响应里加 code、不加日志是不够的 —— 语义要能被机器读，根因要能在日志里找。
3. **测试侧**：断言「合法输入 + 稳定复现」时，**先怀疑 schema/映射**，别先改业务规则；
   数据语义（如行位次互不相同）要有独立断言兜底。

## 关联

- 6.2.2.2 车道自述：`docs/sessions/2026-10/2026-10-21-demand-merge-batch.md`
  （该车道在 warehouse 侧撞过**完全同款**的坑并留了注释 —— 这已是第二例，不是孤例）
- 批次说明：`docs/sessions/2026-10/2026-10-21-batch-50-pending-inspection-brand-credential.md`
  §「本轮调度经过」记录了本轮两条车道被运行时网络错误打死的取证过程
  （同属「故障现场不可见」这一类问题：worker 死了，lanes.json 里看不出来）
