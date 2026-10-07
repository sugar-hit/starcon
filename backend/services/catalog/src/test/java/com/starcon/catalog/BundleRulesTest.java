package com.starcon.catalog;

import static com.starcon.catalog.BundleRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 3.5.1.1 套装版本管理 · 纯规则层。
 *
 * <p>这个文件的价值全在<b>把生效区间的裁决规则钉死</b>：半开区间、重叠拒绝、当前生效版本、
 * 向前关闭。蓝图标了「设置生效区间」却没说重叠怎么办，本实现的取舍是「一律拒绝，不做裁决」——
 * 那条取舍只有在测试里逐条钉住，才不会在下一次重构时被换成「后来者覆盖」而无人察觉。
 */
class BundleRulesTest {

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  // ================================================================ 码表与规范化

  @Test
  void codeIsUpperCasedButVersionNoKeepsItsCase() {
    // 编码是刊登与订单里的稳定引用：bnd-1 与 BND-1 各建一行会让「这个编码指哪个套装」无法回答。
    assertThat(normalizeCode(" bnd-1 ")).isEqualTo("BND-1");
    // 版本号是人写的标签：V1 与 v1 折叠成一个标签就看不出区别了。
    assertThat(normalizeVersionNo(" V1 ")).isEqualTo("V1");
    assertThat(normalizeVersionNo("2026-春季")).isEqualTo("2026-春季");
  }

  @Test
  void codeAndNameProblemsMatchTheColumnWidths() {
    assertThat(codeProblem("")).contains("不能为空");
    assertThat(codeProblem("X".repeat(65))).contains("64");
    assertThat(codeProblem("BND-1")).isNull();
    assertThat(versionNoProblem("v1")).isNull();
    assertThat(versionNoProblem("X".repeat(33))).contains("32");
    assertThat(nameProblem("")).contains("不能为空");
    assertThat(nameProblem("X".repeat(201))).contains("200");
  }

  @Test
  void statusVocabularyMatchesTheDatabaseCheck() {
    assertThat(statusProblem("DRAFT")).isNull();
    assertThat(statusProblem("PUBLISHED")).isNull();
    assertThat(statusProblem("PUBLISHED_V2")).contains("只能是 DRAFT 或 PUBLISHED");
    // 空输入不是筛选条件时要当「不过滤」，不能报错。
    assertThat(statusProblem("")).isNull();
    assertThat(bundleStatusProblem("ARCHIVED")).isNull();
    assertThat(bundleStatusProblem("DELETED")).contains("ACTIVE");
    assertThat(statusLabel("DRAFT")).isEqualTo("草稿");
    assertThat(statusLabel("PUBLISHED")).isEqualTo("已发布");
  }

  // ================================================================ 组件数量

  @Test
  void componentQuantityMustBePositiveAndFitNumeric196() {
    assertThat(quantityProblem(BigDecimal.ONE)).isNull();
    // 0.5 是真实场景：套装里出现「0.5 米挂绳 + 2 个挂钩」，int 会逼运营写成「1 件」。
    assertThat(quantityProblem(new BigDecimal("0.5"))).isNull();
    assertThat(quantityProblem(new BigDecimal("0.000001"))).isNull();

    assertThat(quantityProblem(BigDecimal.ZERO)).contains("必须大于 0");
    assertThat(quantityProblem(new BigDecimal("-1"))).contains("必须大于 0");
    // 小数位超过 numeric(19,6) 会静默舍入 —— 订单展开按舍入后的数走，而运营以为没舍。
    assertThat(quantityProblem(new BigDecimal("0.0000001"))).contains("6 位小数");
    // 整数位超过 13 位。
    assertThat(quantityProblem(new BigDecimal("10000000000000"))).contains("整数部分");
    assertThat(quantityProblem(null)).contains("不能为空");
  }

  @Test
  void quantityComparisonMustNotBeConfusedByTrailingZeros() {
    // equals 会把 1 与 1.0 判成两个不同的数而误拒；这里钉住用的是 compareTo 语义。
    assertThat(quantityProblem(new BigDecimal("1.000000"))).isNull();
    assertThat(quantityProblem(new BigDecimal("2.50"))).isNull();
  }

  @Test
  void componentCountIsBoundedForUsabilityNotForTheDatabase() {
    assertThat(componentCountProblem(0)).contains("至少要有一个组件");
    assertThat(componentCountProblem(1)).isNull();
    assertThat(componentCountProblem(MAX_COMPONENTS_PER_VERSION)).isNull();
    assertThat(componentCountProblem(MAX_COMPONENTS_PER_VERSION + 1))
        .contains("可用性上限")
        .contains("订单展开出来的子行就没人核得对了");
  }

  // ================================================================ R1 半开区间

  @Test
  void rangesAreHalfOpenSoAdjacentIntervalsDoNotOverlap() {
    // 这是整个模块最容易被改坏的一处：区间一旦变成闭区间，
    // 12:00 这一瞬间会同时属于两版，「当前生效版本」就必须返回两行或随便挑一行。
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), at("2026-03-01T00:00:00Z"),
        at("2026-03-01T00:00:00Z"), null))
        .isFalse();
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), at("2026-03-01T00:00:00Z"),
        at("2026-04-01T00:00:00Z"), null))
        .isFalse();
    // 真重叠必须被认出来。
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), at("2026-05-01T00:00:00Z"),
        at("2026-04-01T00:00:00Z"), null))
        .isTrue();
    // 开放区间与之后开始的区间重叠（但与它之前结束的区间**不**重叠 ——
    // 「开放」不等于「和所有东西都撞」）。
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), null,
        at("2027-01-01T00:00:00Z"), null))
        .isTrue();
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), null,
        at("2020-01-01T00:00:00Z"), at("2020-06-01T00:00:00Z")))
        .isFalse();
    // 区间包含（外层包住内层）也算重叠。
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), null,
        at("2026-02-01T00:00:00Z"), at("2026-03-01T00:00:00Z")))
        .isTrue();
    // 两个区间完全相同。
    assertThat(overlaps(at("2026-01-01T00:00:00Z"), at("2026-02-01T00:00:00Z"),
        at("2026-01-01T00:00:00Z"), at("2026-02-01T00:00:00Z")))
        .isTrue();
  }

  @Test
  void rangeMustBeOrderedAndEmptyRangeIsRejected() {
    assertThat(rangeProblem(at("2026-01-01T00:00:00Z"), null)).isNull();
    assertThat(rangeProblem(at("2026-01-01T00:00:00Z"), at("2026-03-01T00:00:00Z"))).isNull();
    // 终点等于起点 = 空区间 = 「从不生效」，那是一个没有版本的版本。
    assertThat(rangeProblem(at("2026-01-01T00:00:00Z"), at("2026-01-01T00:00:00Z")))
        .contains("等于「从不生效」");
    assertThat(rangeProblem(at("2026-03-01T00:00:00Z"), at("2026-01-01T00:00:00Z")))
        .contains("必须晚于生效起点");
    assertThat(rangeProblem(null, null)).contains("生效起点不能为空");
  }

  @Test
  void instantsMustCarryATimeZoneAndAreTruncatedToMillis() {
    // 不带偏移量的写法会按 JVM 默认时区解析：同一份数据在不同机器上得到不同的生效时刻。
    assertThatThrownBy(() -> parseInstant("2026-03-01T00:00:00", "生效起点"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("带时区");
    // 带偏移的写法按偏移归一到同一时刻。
    assertThat(parseInstant("2026-03-01T08:00:00+08:00", "生效起点"))
        .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    assertThat(parseInstant("2026-03-01T00:00:00Z", "生效起点"))
        .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    assertThat(parseInstant("  ", "生效终点")).isNull();
    // 收到毫秒：PG 的 timestamptz 是微秒，而前端来回序列化会截断，不收窄就会出现「保存后区间变了」。
    assertThat(parseInstant("2026-03-01T00:00:00.123456Z", "生效起点"))
        .isEqualTo(Instant.parse("2026-03-01T00:00:00.123Z"));
  }

  // ================================================================ R4 当前生效版本

  @Test
  void effectiveVersionIsAPureMomentQueryNotTheLatestVersion() {
    Instant from = at("2026-01-01T00:00:00Z");
    Instant to = at("2026-03-01T00:00:00Z");
    // 左端点闭：起点这一刻就生效。
    assertThat(covers(from, to, from)).isTrue();
    // 右端点开：终点那一瞬间归下一版。
    assertThat(covers(from, to, to)).isFalse();
    assertThat(covers(from, to, at("2026-02-28T23:59:59Z"))).isTrue();
    assertThat(covers(from, to, at("2025-12-31T23:59:59Z"))).isFalse();
    // 开放区间覆盖它之后的一切。
    assertThat(covers(from, null, at("2099-01-01T00:00:00Z"))).isTrue();
    assertThat(covers(from, null, at("2025-01-01T00:00:00Z"))).isFalse();
  }

  @Test
  void adjacentVersionsNeverBothCoverTheBoundaryMoment() {
    // 「同一时刻至多一个生效版本」是 R2 拒绝重叠换来的确定性；
    // 边界这一瞬间尤其要钉死，否则刊登会在跨版本那天挑错版本。
    Instant boundary = at("2026-03-01T00:00:00Z");
    Instant previous = at("2026-01-01T00:00:00Z");
    Instant next = at("2026-05-01T00:00:00Z");
    assertThat(covers(previous, boundary, boundary)).isFalse();
    assertThat(covers(boundary, next, boundary)).isTrue();
  }

  // ================================================================ R5 向前关闭

  @Test
  void publishedVersionMayOnlyBeClosedForward() {
    Instant now = at("2026-10-01T00:00:00Z");
    // 唯一的例外：把开放区间收到一个仍在未来的时刻。
    assertThat(forwardCloseProblem(null, at("2027-01-01T00:00:00Z"), now)).isNull();
    assertThat(forwardCloseProblem(at("2027-06-01T00:00:00Z"), at("2027-03-01T00:00:00Z"), now)).isNull();

    // 重新变成「一直有效」会与已接替它的版本重叠。
    assertThat(forwardCloseProblem(at("2027-06-01T00:00:00Z"), null, now))
        .contains("重新变成「一直有效」");
    // 落进过去 = 追溯改写「那时生效的是哪一版」。
    assertThat(forwardCloseProblem(null, at("2026-09-30T23:59:59Z"), now))
        .contains("必须落在当前时刻之后");
    // 正好等于 now 也不行：那已经是过去的一刻。
    assertThat(forwardCloseProblem(null, now, now)).contains("必须落在当前时刻之后");
    // 往后延 = 与接替版本重叠。
    assertThat(forwardCloseProblem(at("2027-06-01T00:00:00Z"), at("2028-01-01T00:00:00Z"), now))
        .contains("只能收紧不能往后延");
  }

  @Test
  void componentCountLimitMatchesTheDocumentedValue() {
    // 50 是可用性上限（200 行子清单没人核对），不是数据库限制 —— 改动时要想清楚。
    assertThat(MAX_COMPONENTS_PER_VERSION).isEqualTo(50);
    assertThat(QUANTITY_SCALE).isEqualTo(6);
    // numeric(19,6)：整数位 13 + 小数位 6。
    assertThat(QUANTITY_PRECISION).isEqualTo(13);
  }
}