package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.VendorListModels.VendorMetricWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.1.1.1 供应商列表的纯规则单测（不碰数据库，可以穷举）。
 *
 * <p>重点覆盖三类最容易出错的地方：<b>枚举词表</b>（非法值必须给可读原因而不是静默筛出空列表）、
 * <b>比率的 null 语义</b>（分母为 0 时是「暂无数据」而不是 0%）、<b>排序键白名单</b>（绝不把外部字符串拼进 SQL）。
 */
class VendorListRulesTest {

  // ================================================================ 词表

  @Test
  void 合作状态词表与V137的CHECK逐字一致() {
    assertThat(VendorListRules.SUPPLY_STATUSES)
        .extracting(VendorListRules.Option::value)
        .containsExactly("PENDING_REVIEW", "ACTIVE", "SUSPENDED", "TRIAL", "TERMINATED");
  }

  @Test
  void 合作等级与主体状态是词表里的只读取值() {
    assertThat(VendorListRules.COOPERATION_LEVELS)
        .extracting(VendorListRules.Option::value)
        .containsExactly("STRATEGIC", "PREFERRED", "STANDARD", "TRIAL", "RISK");
    assertThat(VendorListRules.PARTY_STATUSES)
        .extracting(VendorListRules.Option::value)
        .containsExactly("ACTIVE", "INACTIVE", "BLACKLISTED");
  }

  @Test
  void 非法枚举值给出可读原因() {
    assertThat(VendorListRules.supplyStatusProblem("ACTIVE")).isNull();
    assertThat(VendorListRules.supplyStatusProblem("active")).isNull();
    assertThat(VendorListRules.supplyStatusProblem("GOLD")).contains("无效的合作状态");
    assertThat(VendorListRules.supplyStatusProblem("")).contains("不能为空");
    assertThat(VendorListRules.cooperationLevelProblem("GOLD")).contains("无效的合作等级");
    assertThat(VendorListRules.partyStatusProblem("BANANA")).contains("无效的主体状态");
    // 空串 = 不过滤，而不是「查一个空状态」
    assertThat(VendorListRules.cooperationLevelProblem("")).isNull();
  }

  @Test
  void 币种只校验格式不校验存在性() {
    assertThat(VendorListRules.currencyProblem("usd")).isNull();
    assertThat(VendorListRules.currencyProblem("ZZZ")).isNull(); // 格式对就放过：完整词表是 12 财务的资产
    assertThat(VendorListRules.currencyProblem("US")).contains("ISO 4217");
    assertThat(VendorListRules.currencyProblem("US_DOLLAR")).contains("ISO 4217");
  }

  // ================================================================ 比率的 null 语义（规则 R3）

  @Test
  void 分母为零时比率是null而不是0() {
    assertThat(VendorListRules.onTimeRate(0L, 0L)).isNull();
    assertThat(VendorListRules.onTimeRate(null, null)).isNull();
    assertThat(VendorListRules.qualityRate(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    assertThat(VendorListRules.rateLabel(null)).isEqualTo("暂无数据");
    // 「还没交货过」与「交货全部延期」是相反的两件事
    assertThat(VendorListRules.rateLabel(VendorListRules.onTimeRate(0L, 5L))).isEqualTo("0.00%");
  }

  @Test
  void 比率用BigDecimal不用浮点() {
    // 2/3 用 double 会给出 0.6666666666666666，这里定到 4 位
    assertThat(VendorListRules.onTimeRate(2L, 3L)).isEqualByComparingTo("0.6667");
    assertThat(VendorListRules.qualityRate(new BigDecimal("9999"), new BigDecimal("10000")))
        .isEqualByComparingTo("0.9999");
  }

  @Test
  void 质量合格率按数量比而不是批次数比() {
    // 一批 10000 合格 9999 + 一批 3 合格 1：按批次算是 50%，按数量算是 99.97%
    BigDecimal inspected = new BigDecimal("10000").add(new BigDecimal("3"));
    BigDecimal qualified = new BigDecimal("9999").add(BigDecimal.ONE);
    assertThat(VendorListRules.qualityRate(qualified, inspected)).isEqualByComparingTo("0.9997");
  }

  // ================================================================ 窗口

  @Test
  void 窗口缺省取最近一年() {
    OffsetDateTime now = OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    VendorMetricWindow window = VendorListRules.metricWindow(null, null, now);
    assertThat(window.from()).isEqualTo(OffsetDateTime.of(2025, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toString());
    assertThat(window.to()).isEqualTo(now.toInstant().toString());
  }

  @Test
  void 窗口起止颠倒与超长都给出可读原因() {
    OffsetDateTime now = OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    assertThatThrownBy(() -> VendorListRules.metricWindow(now, now.minusDays(10), now))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("起点不能晚于终点");
    assertThatThrownBy(
            () -> VendorListRules.metricWindow(now.minusDays(5000), now, now))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("最长");
  }

  // ================================================================ 排序白名单

  @Test
  void 排序键命中白名单否则给可读原因() {
    assertThat(VendorListRules.sortProblem("partyCode")).isNull();
    assertThat(VendorListRules.sortProblem(null)).isNull();
    assertThat(VendorListRules.sortProblem("partyCode; drop table party"))
        .contains("未知的排序键");
  }

  @Test
  void 排序键映射到常量列名绝不接受外部拼接() {
    assertThat(VendorListRules.sortColumn("partyCode")).isEqualTo("p.code");
    assertThat(VendorListRules.sortColumn("onTimeRate")).isEqualTo("on_time_rate");
    // 大小写无关：前端列头传 partyCode、URL 传 partycode 都要命中同一条
    assertThat(VendorListRules.sortColumn("PARTYCODE")).isEqualTo("p.code");
    assertThatThrownBy(() -> VendorListRules.sortColumn("(select 1)"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 比率与计数默认降序文本默认升序() {
    assertThat(VendorListRules.defaultDescFor("onTimeRate")).isTrue();
    assertThat(VendorListRules.defaultDescFor("qualityRate")).isTrue();
    assertThat(VendorListRules.defaultDescFor("supplySkuCount")).isTrue();
    assertThat(VendorListRules.defaultDescFor("partyCode")).isFalse();
  }

  // ================================================================ 写入前置条件

  @Test
  void 到货记录的数量金额与日期关系() {
    LocalDate due = LocalDate.of(2026, 6, 1);
    LocalDate received = LocalDate.of(2026, 6, 3);
    assertThat(
            VendorListRules.deliveryProblem(
                new BigDecimal("10"), new BigDecimal("1.00"), due, received))
        .isNull();
    assertThat(VendorListRules.deliveryProblem(BigDecimal.ZERO, BigDecimal.ONE, due, received))
        .contains("数量必须大于 0");
    assertThat(VendorListRules.deliveryProblem(BigDecimal.ONE, new BigDecimal("-1"), due, received))
        .contains("金额不能为负");
    assertThat(
            VendorListRules.deliveryProblem(
                BigDecimal.ONE, BigDecimal.ONE, due, due.minusYears(11)))
        .contains("年份填错了");
  }

  @Test
  void 质检记录的合格数不得超过检验数() {
    assertThat(VendorListRules.qualityProblem(new BigDecimal("10"), new BigDecimal("9"))).isNull();
    assertThat(VendorListRules.qualityProblem(new BigDecimal("10"), new BigDecimal("10"))).isNull();
    assertThat(VendorListRules.qualityProblem(new BigDecimal("10"), new BigDecimal("11")))
        .contains("不能超过");
    assertThat(VendorListRules.qualityProblem(BigDecimal.ZERO, BigDecimal.ZERO))
        .contains("检验数量必须大于 0");
  }

  @Test
  void 准时判定当天到货算准时() {
    LocalDate due = LocalDate.of(2026, 6, 1);
    assertThat(VendorListRules.onTime(due, due.minusDays(3))).isTrue();
    assertThat(VendorListRules.onTime(due, due)).isTrue();
    assertThat(VendorListRules.onTime(due, due.plusDays(1))).isFalse();
    assertThat(VendorListRules.daysLate(due, due.plusDays(5))).isEqualTo(5);
    assertThat(VendorListRules.daysLate(due, due.minusDays(5))).isZero();
  }
}
