package com.starcon.catalog;

import static com.starcon.catalog.SupplierQuoteRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.6.2.1 供应商报价的纯规则离线单测。
 *
 * <p>与 {@code PartyRoleProfileRulesTest} / {@code PartyRulesTest} 同一纪律：
 * 能在保存前判定的规则必须有可以穷举的单测，因为集成测试里每加一条规则就多一轮 Testcontainers 启动。
 *
 * <p>本文件<b>刻意</b>钉死三组断言，它们是「两处各说一套」最容易漂的地方： R2 的优先级方向与歧义、**R5 的阶梯取价边界**、R3/R4 的必填与格式。
 *
 * <p>其中阶梯取价（{@link SupplierQuoteRules#applicableTier}）是本模块最容易写错的一段：
 * 写错了不会报错，只会让采购单按<b>更贵或更便宜</b>的那一档结算，而账面看不出异常。
 */
class SupplierQuoteRulesTest {

  private static final BigDecimal Q1 = new BigDecimal("1");
  private static final BigDecimal Q100 = new BigDecimal("100");
  private static final BigDecimal Q500 = new BigDecimal("500");

  // ================================================================ R2 优先级与歧义

  @Test
  void 优先级从一开始且越小越优先() {
    assertThat(priorityProblem(1)).isNull();
    assertThat(priorityProblem(99)).isNull();
    assertThat(priorityProblem(0)).contains("从 1 开始");
    assertThat(priorityProblem(-1)).contains("从 1 开始");
    assertThat(priorityProblem(100)).contains("最多 99");
    // 留空取 1（首选）而不是 0
    assertThat(normalizePriority(null)).isEqualTo(1);
  }

  @Test
  void 唯一最高优先级标为默认采用() {
    // 优先级 [2, 1, 3]：1 是唯一最高 → SELECTED，其余两个是备选
    assertThat(selectionStates(List.of(2, 1, 3)))
        .containsExactly("LOWER_PRIORITY", "SELECTED", "LOWER_PRIORITY");
  }

  @Test
  void 同优先级并列必须标为需人工选择而不是随机挑一条() {
    // [1, 1, 2]：两条并列最高 → 两条都是 AMBIGUOUS，绝不能有一个是 SELECTED
    assertThat(selectionStates(List.of(1, 1, 2)))
        .containsExactly("AMBIGUOUS", "AMBIGUOUS", "LOWER_PRIORITY");
    // 三条并列：三条全都要人工选择（不能只标前两条）
    assertThat(selectionStates(List.of(3, 3, 3)))
        .containsExactly("AMBIGUOUS", "AMBIGUOUS", "AMBIGUOUS");
  }

  @Test
  void 歧义文案在并列优先级大于一时建议调小() {
    String problem = ambiguityProblem(2, 2);
    assertThat(problem).contains("2 条").contains("优先级同为 2");
    assertThat(problem).contains("不会替你随机选");
    // 优先级 > 1：建议把要用的那条调小一位
    assertThat(problem).contains("2 → 1");
  }

  @Test
  void 歧义文案在并列优先级已经是一时建议调大() {
    // 优先级已经是 1 时说「调小一位」是错的（1 已经是下限，调不成 0）
    String problem = ambiguityProblem(1, 2);
    assertThat(problem).contains("1 → 2");
  }

  @Test
  void 只有一条时不报歧义() {
    assertThat(ambiguityProblem(1, 1)).isNull();
    assertThat(ambiguityProblem(1, 0)).isNull();
  }

  @Test
  void 空集合不产生状态() {
    assertThat(selectionStates(List.of())).isEmpty();
  }

  // ================================================================ R5 阶梯取价

  @Test
  void 按数量取命中的那一档() {
    List<BigDecimal> mins = List.of(Q1, Q100, Q500);
    List<String> prices = List.of("3.80", "3.20", "2.90");
    // 低于第一档 → 没有适用价（不退回高档，见 applicableTier 的说明）
    assertThat(tier(mins, prices, new BigDecimal("0.5"))).isNull();
    // 恰好落在档位下界 → 命中该档
    assertThat(tier(mins, prices, Q1)).isEqualTo("3.80");
    assertThat(tier(mins, prices, Q100)).isEqualTo("3.20");
    assertThat(tier(mins, prices, Q500)).isEqualTo("2.90");
    // 档内任意数量都命中同一档（99 仍属第一档，137 属第二档）
    assertThat(tier(mins, prices, new BigDecimal("99.999999"))).isEqualTo("3.80");
    assertThat(tier(mins, prices, new BigDecimal("137"))).isEqualTo("3.20");
    // 超过最后一档仍命中最后一档（阶梯是开口的，V32 第三节）
    assertThat(tier(mins, prices, new BigDecimal("100000"))).isEqualTo("2.90");
  }

  /**
   * 取适用档的窄化包装。
   *
   * <p>显式指定类型参数 {@code <String>}：{@code applicableTier} 的第三个参数与两个 {@code List} 在传 {@code null} 或
   * {@code List.of()} 时都让 {@code T} 无从推断， Java 21 会把它解析成 AssertJ 的 {@code assertThat(Predicate)}
   * 而报"引用不明确"。
   */
  private static String tier(List<BigDecimal> mins, List<String> tiers, BigDecimal quantity) {
    return SupplierQuoteRules.<String>applicableTier(mins, tiers, quantity);
  }

  @Test
  void 未传数量或档位为空时不取价() {
    assertThat(tier(List.of(Q1), List.of("3.80"), null)).isNull();
    assertThat(tier(List.of(), List.of(), Q100)).isNull();
    // 长度不一致（数据错乱）时宁可返回 null，也不要瞎取一档
    assertThat(tier(List.of(Q1, Q100), List.of("3.80"), Q500)).isNull();
  }

  @Test
  void 阶梯至少一档且起订量严格递增() {
    assertThat(tiersProblem(List.of(Q1, Q100), 2)).isNull();
    assertThat(tiersProblem(List.of(), 0)).contains("至少一档");
    assertThat(tiersProblem(List.of(Q1), 1)).isNull();
    // 重复起订量：主键在 DB 层已不可能，但服务层要提前拦下并说清是哪两档
    assertThat(tiersProblem(List.of(Q100, Q100), 2)).contains("必须大于");
    assertThat(tiersProblem(List.of(Q100, Q1), 2)).contains("必须大于");
  }

  @Test
  void 单档阶梯单价为零非法() {
    // 0 元档要么是赠品要么是漏填，两者在采购单上算出来的金额完全一样（都是 0），没法区分
    assertThat(tierProblem(Q1, BigDecimal.ZERO)).contains("必须大于 0");
    assertThat(tierProblem(Q1, new BigDecimal("-1"))).contains("必须大于 0");
    assertThat(tierProblem(Q1, new BigDecimal("3.8"))).isNull();
    assertThat(tierProblem(BigDecimal.ZERO, new BigDecimal("3.8"))).contains("必须大于 0");
    assertThat(tierProblem(null, new BigDecimal("3.8"))).contains("起订量必填");
    assertThat(tierProblem(Q1, null)).contains("单价必填");
  }

  @Test
  void 低于最小可表示精度的值判非法() {
    // 0.0001 是单价能表示的最小值，0.00001 存不进 numeric(19,4)
    assertThat(tierProblem(Q1, new BigDecimal("0.0001"))).isNull();
    assertThat(tierProblem(Q1, new BigDecimal("0.00001"))).contains("精度不足");
    assertThat(moqProblem(new BigDecimal("0.000001"))).isNull();
    assertThat(moqProblem(new BigDecimal("0.0000001"))).contains("精度不足");
  }

  // ================================================================ R4 MOQ 与交期

  @Test
  void 起订量必填且大于零() {
    assertThat(moqProblem(new BigDecimal("100"))).isNull();
    assertThat(moqProblem(new BigDecimal("0.5"))).isNull();
    // 没有起订门槛的报价不是一条可执行的采购承诺
    assertThat(moqProblem(null)).contains("起订量必填");
    assertThat(moqProblem(BigDecimal.ZERO)).contains("必须大于 0");
    assertThat(moqProblem(new BigDecimal("-1"))).contains("必须大于 0");
  }

  @Test
  void 交期零是当日发货而不是没填() {
    assertThat(leadTimeProblem(0)).isNull();
    assertThat(leadTimeProblem(365)).isNull();
    assertThat(leadTimeProblem(null)).isNull();
    assertThat(normalizeLeadTime(null)).isZero();
    assertThat(leadTimeLabel(0)).isEqualTo("当日发货");
    assertThat(leadTimeLabel(15)).isEqualTo("交期 15 天");
    assertThat(leadTimeProblem(-1)).contains("负数");
    assertThat(leadTimeProblem(366)).contains("365");
  }

  // ================================================================ 有效期

  @Test
  void 有效期闭区间且结束日不能早于生效日() {
    LocalDate from = LocalDate.of(2026, 3, 1);
    assertThat(validityProblem(from, null)).isNull();
    // 只有一天有效的促销价是真实存在的：结束日等于生效日合法
    assertThat(validityProblem(from, from)).isNull();
    assertThat(validityProblem(from, LocalDate.of(2026, 4, 1))).isNull();
    assertThat(validityProblem(from, LocalDate.of(2026, 2, 28))).contains("不能早于");
    assertThat(validityProblem(null, LocalDate.of(2026, 4, 1))).contains("生效日期必填");
  }

  @Test
  void 闭区间当天仍然有效() {
    // 界面上写着「~ 3/31」，系统在 3/31 就突然不认的话两边说的不是同一件事
    LocalDate from = LocalDate.of(2026, 3, 1);
    LocalDate to = LocalDate.of(2026, 3, 31);
    assertThat(withinValidity(LocalDate.of(2026, 3, 31), from, to)).isTrue();
    assertThat(withinValidity(LocalDate.of(2026, 3, 1), from, to)).isTrue();
    assertThat(withinValidity(LocalDate.of(2026, 4, 1), from, to)).isFalse();
    assertThat(withinValidity(LocalDate.of(2026, 2, 28), from, to)).isFalse();
    // 结束日为空 = 长期有效
    assertThat(withinValidity(LocalDate.of(2099, 1, 1), from, null)).isTrue();
    assertThat(withinValidity(LocalDate.of(2026, 1, 1), from, null)).isFalse();
    assertThat(withinValidity(LocalDate.of(2026, 3, 15), null, to)).isFalse();
  }

  @Test
  void 允许补录过去开始日期的报价() {
    // 供应商把纸质报价单发过来，运营照着录 —— valid_from 是过去是正常操作
    assertThat(validityProblem(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31))).isNull();
  }

  @Test
  void 距离失效的天数负数表示已过期() {
    LocalDate at = LocalDate.of(2026, 3, 31);
    assertThat(daysUntilExpiry(at, LocalDate.of(2026, 4, 30))).isEqualTo(30);
    assertThat(daysUntilExpiry(at, LocalDate.of(2026, 3, 28))).isEqualTo(-3);
    // 没有失效日 = 长期有效，用一个极大值而不是 0（0 会被界面显示成"今天到期"）
    assertThat(daysUntilExpiry(at, null)).isEqualTo(Long.MAX_VALUE);
  }

  // ================================================================ R3 货号 / 币种 / 单位

  @Test
  void 供应商货号必填且允许中文与斜杠() {
    assertThat(supplierSkuCodeProblem("XG-2024/A")).isNull();
    assertThat(supplierSkuCodeProblem("工厂料号-001")).isNull();
    assertThat(supplierSkuCodeProblem("  ")).contains("必填");
    assertThat(supplierSkuCodeProblem(null)).contains("必填");
    // 超长报错
    assertThat(supplierSkuCodeProblem("x".repeat(65))).contains("最多 64");
  }

  @Test
  void 币种只校验ISO4217三位格式不查词表() {
    assertThat(currencyProblem("USD")).isNull();
    assertThat(currencyProblem("cny")).isNull(); // 大小写被规范化
    assertThat(currencyProblem("EUR")).isNull();
    // 少见的币种也要能存进来：判"在不在词表里"会把它们挡在门外
    assertThat(currencyProblem("ZWL")).isNull();
    assertThat(currencyProblem("US")).contains("三位");
    assertThat(currencyProblem("USDD")).contains("三位");
    assertThat(currencyProblem("")).contains("币种必填");
    assertThat(normalizeCurrency(" usd ")).isEqualTo("USD");
  }

  @Test
  void 采购单位必填() {
    assertThat(purchaseUnitProblem("PCS")).isNull();
    assertThat(purchaseUnitProblem("箱")).isNull();
    assertThat(purchaseUnitProblem(null)).contains("采购单位必填");
    assertThat(purchaseUnitProblem("  ")).contains("采购单位必填");
  }

  // ================================================================ 状态与展示

  @Test
  void 状态词表() {
    assertThat(statusProblem("ACTIVE")).isNull();
    assertThat(statusProblem("disabled")).isNull(); // 大小写被规范化
    assertThat(statusProblem("EXPIRED")).contains("不在词表");
    assertThat(normalizeStatus(null)).isEqualTo("ACTIVE");
    assertThat(statusLabel("DISABLED")).isEqualTo("已停用");
    assertThat(statusLabel("ACTIVE")).isEqualTo("启用中");
  }

  @Test
  void 数量与金额展示去掉多余尾随零() {
    assertThat(quantityLabel(new BigDecimal("100.000000"))).isEqualTo("100");
    assertThat(quantityLabel(new BigDecimal("0.5"))).isEqualTo("0.5");
    assertThat(quantityLabel(new BigDecimal("0.000001"))).isEqualTo("0.000001");
    assertThat(quantityLabel(null)).isEmpty();
  }

  @Test
  void 规范化按列精度四舍五入() {
    assertThat(normalizeQuantity(new BigDecimal("1.5")).scale()).isEqualTo(6);
    assertThat(normalizeQuantity(new BigDecimal("1.00000004"))).isEqualByComparingTo("1.000000");
    assertThat(normalizePrice(new BigDecimal("3.123456"))).isEqualByComparingTo("3.1235");
  }

  @Test
  void 标签文案() {
    assertThat(priorityLabel(1)).isEqualTo("首选");
    assertThat(priorityLabel(2)).isEqualTo("第 2 优先");
    assertThat(selectionStateLabel("SELECTED")).isEqualTo("默认采用");
    assertThat(selectionStateLabel("AMBIGUOUS")).isEqualTo("需人工选择");
    assertThat(selectionStateLabel("LOWER_PRIORITY")).isEqualTo("备选");
    assertThat(validityLabel(LocalDate.of(2026, 1, 1), null)).isEqualTo("2026-01-01 起长期有效");
  }
}
