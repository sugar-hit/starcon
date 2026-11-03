package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 🔴 数据库时刻精度的边界契约。
 *
 * <p>这个契约横跨所有服务（crm 的分类时刻与评分趋势、channel 的违规时刻、trade 的寄回时限
 * 都踩在同一条缝上），但它有个讨厌的性质：**只在一半机器上出现**。
 *
 * <ul>
 *   <li>PostgreSQL 的 {@code timestamptz} 只把时刻存到**微秒**；</li>
 *   <li>Java 的 {@link OffsetDateTime} 能表示到**纳秒**，而 {@code OffsetDateTime.now()}
 *       在 Linux 上走 clock_gettime、带亚微秒尾巴，在 macOS 上走 gettimeofday、恰好微秒对齐；</li>
 *   <li>于是「写进去的」与「读回来的」在 macOS 上分毫不差、在 Linux 上差零点几微秒 ——
 *       本地怎么跑都是绿的，CI 却是红的。</li>
 * </ul>
 *
 * <p>2026-11-03 那次后端作业红灯里，六个用例就死在这条缝上（channel 1 + crm 2 + trade 3），
 * 其中评分趋势那一条最隐蔽：{@code date_bin} 的原点被 PG 落到微秒，而 Java 侧按未对齐的
 * 原点算桶边界，两边差 0.x 微秒 ⇒ **每个桶都查不到行 ⇒ 趋势整片是 0**，
 * 而同一个响应里的总数、均分、分布全都对 —— 看上去像「最近没有新评价」。
 *
 * <p>🔴 本类**不依赖运行机器的时钟**：它直接把带亚微秒尾巴的时刻灌进去，
 * 所以在 macOS 与 Linux 上跑出的结论完全一样 ——
 * 这是这类缺陷唯一可移植的回归守卫（其余用例都得靠 CI 的 Linux runner 才抓得住）。
 */
class DbPrecisionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  /** 一条只用来量精度的探针表 —— 不带租户列，因此不涉及 RLS。 */
  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create table precision_probe (id uuid primary key, moment timestamptz not null)");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 生产路径的绑定方式：jOOQ 绑 OffsetDateTime + SQL 里写 {@code ?::timestamptz}。 */
  static DSLContext dsl() {
    try {
      return DSL.using(
          DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()),
          SQLDialect.POSTGRES);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 一个**刻意**带亚微秒尾巴的时刻（937374765ns ⇒ 第 7 位小数非零）。 */
  static OffsetDateTime withSubMicrosecondTail() {
    return OffsetDateTime.parse("2026-11-03T06:35:44.937374765Z");
  }

  @Test
  void 带亚微秒尾巴的时刻写进去读回来会漂() {
    OffsetDateTime tail = withSubMicrosecondTail();
    UUID id = UUID.randomUUID();
    var sql = dsl();
    sql.execute(
        "insert into precision_probe(id, moment) values (?, ?::timestamptz)", id, tail);

    OffsetDateTime stored =
        sql.fetchOne("select moment from precision_probe where id = ?", id)
            .get(0, OffsetDateTime.class);

    assertThat(stored.toInstant())
        .as("🔴 timestamptz 只存到微秒 ⇒ 带尾巴的时刻写进去读回来必然不是原来那个数")
        .isNotEqualTo(tail.toInstant());
    assertThat(Duration.between(tail.toInstant(), stored.toInstant()).abs().toNanos())
        .as("漂移幅度必须小于 1 微秒（落点就是最近的微秒）")
        .isLessThan(1_000L);
  }

  @Test
  void 对齐到微秒之后写进去读回来分毫不差() {
    OffsetDateTime aligned = withSubMicrosecondTail().truncatedTo(ChronoUnit.MICROS);
    UUID id = UUID.randomUUID();
    var sql = dsl();
    sql.execute(
        "insert into precision_probe(id, moment) values (?, ?::timestamptz)", id, aligned);

    OffsetDateTime stored =
        sql.fetchOne("select moment from precision_probe where id = ?", id)
            .get(0, OffsetDateTime.class);

    assertThat(stored.toInstant())
        .as("🔴 对齐到微秒是这条缝的唯一正解：写进去什么就读回来什么")
        .isEqualTo(aligned.toInstant());
  }

  @Test
  void dateBin原点对齐之后桶起点与Java侧逐个对得上() {
    OffsetDateTime origin = withSubMicrosecondTail().truncatedTo(ChronoUnit.MICROS);
    // 一条落在第 2 个桶（[origin+3d, origin+6d)）里的事实，离边界留足余量。
    UUID id = UUID.randomUUID();
    var sql = dsl();
    sql.execute(
        "insert into precision_probe(id, moment) values (?, ?::timestamptz)",
        id,
        origin.plusDays(4).plusHours(5));

    OffsetDateTime bucket =
        sql.fetchOne(
                "select date_bin(make_interval(days => 3), moment, ?::timestamptz)"
                    + " from precision_probe where id = ?",
                origin,
                id)
            .get(0, OffsetDateTime.class);

    assertThat(bucket.toInstant())
        .as("🔴 原点对齐后，PG 算出的桶起点必须正好是 origin + k*stride —— Java 侧才能按 Instant 配对")
        .isEqualTo(origin.plusDays(3).toInstant());
  }

  @Test
  void dateBin原点没对齐时桶起点与Java侧对不上() {
    OffsetDateTime origin = withSubMicrosecondTail();
    UUID id = UUID.randomUUID();
    var sql = dsl();
    sql.execute(
        "insert into precision_probe(id, moment) values (?, ?::timestamptz)",
        id,
        origin.truncatedTo(ChronoUnit.MICROS).plusDays(4).plusHours(5));

    OffsetDateTime bucket =
        sql.fetchOne(
                "select date_bin(make_interval(days => 3), moment, ?::timestamptz)"
                    + " from precision_probe where id = ?",
                origin,
                id)
            .get(0, OffsetDateTime.class);

    assertThat(bucket.toInstant())
        .as("🔴 原点没对齐 ⇒ 桶起点落在微秒网格上，与 Java 侧算的 cursor 差零点几微秒 ⇒ 配不上对")
        .isNotEqualTo(origin.plusDays(3).toInstant());
    assertThat(Duration.between(origin.plusDays(3).toInstant(), bucket.toInstant()).abs().toNanos())
        .as("差距必须小于 1 微秒 —— 正是这零点几微秒让所有桶的计数变成 0")
        .isLessThan(1_000L);
  }
}
