package com.starcon.channel;

import static com.starcon.channel.TaxRegistrationRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.TaxRegistrationRules.Gap;
import com.starcon.channel.TaxRegistrationRules.JurisdictionFact;
import com.starcon.channel.TaxRegistrationRules.RegistrationFact;
import com.starcon.channel.TaxRegistrationRules.RequirementFact;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.3.1.1 税号与辖区 的**判定规则**单元测试（不碰数据库）。
 *
 * <p>🔴 这组用例守的是缺失项判定里**最容易做丢**的五件事：
 *
 * <ol>
 *   <li><b>「未注册」与「缺号」不重复报</b>：没注册时号为空是正常的。</li>
 *   <li><b>过期 ≠ 未注册</b>：过期要续期，未注册要去注册 —— 两件事给两条不同的整改建议。</li>
 *   <li><b>没记有效期 ≠ 已过期</b>：那是「判不了」，不是「过期」。</li>
 *   <li><b>辖区不要求 VAT 号就不报缺号</b>；不要求 EPR 就不报缺 EPR 号。</li>
 *   <li><b>归档的登记不参与判定</b>（撤下了就是不在册，不是「有缺项的登记」）。</li>
 * </ol>
 */
class TaxRegistrationRulesTest {

  private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC);

  /** 欧盟成员国：要求 VAT 号、要求 EPR 号。 */
  private static final JurisdictionFact EU =
      new JurisdictionFact("EU-DE", "德国", true, true);

  /** 美国某州：要求登记号（销售税账号），不要求 EPR。 */
  private static final JurisdictionFact US_CA =
      new JurisdictionFact("US-CA", "美国 加利福尼亚州", true, false);

  /** 不要求登记号的辖区（例如巴西）。 */
  private static final JurisdictionFact BR = new JurisdictionFact("BR", "巴西", false, false);

  private static RequirementFact requirement() {
    return new RequirementFact(SUBJECT_SHOP, "shop-1", "EU-DE");
  }

  private static RegistrationFact registration(
      boolean registered,
      String vatStatus,
      String vatNumber,
      String eprNumber,
      OffsetDateTime validUntil) {
    return new RegistrationFact(
        "reg-1",
        SUBJECT_SHOP,
        "shop-1",
        "EU-DE",
        registered,
        vatStatus,
        vatNumber,
        eprNumber,
        validUntil == null ? null : validUntil.minusYears(1),
        validUntil,
        false);
  }

  // ==================================================================
  // 外部要求
  // ==================================================================

  @Test
  @DisplayName("没有登记 ⇒ 未注册该辖区（高严重度）")
  void missingRegistrationIsAGap() {
    List<Gap> gaps = evaluateRequirement(requirement(), null, NOW);
    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0).gapType()).isEqualTo(GAP_JURISDICTION_NOT_REGISTERED);
    assertThat(gaps.get(0).severity()).isEqualTo(SEVERITY_HIGH);
    assertThat(gaps.get(0).remediation()).contains("税务登记");
  }

  @Test
  @DisplayName("登记了但 is_registered=false ⇒ 仍然是未注册")
  void notRegisteredIsAGap() {
    List<Gap> gaps =
        evaluateRequirement(requirement(), registration(false, VAT_NOT_REGISTERED, "", "", NOW), NOW);
    assertThat(gaps).extracting(Gap::gapType).containsExactly(GAP_JURISDICTION_NOT_REGISTERED);
  }

  @Test
  @DisplayName("已登记且在有效期内 ⇒ 外部要求已满足，没有缺口")
  void registeredInTimeSatisfiesRequirement() {
    List<Gap> gaps =
        evaluateRequirement(
            requirement(), registration(true, VAT_REGISTERED, "DE123", "LUCID-1", NOW.plusYears(1)), NOW);
    assertThat(gaps).isEmpty();
  }

  @Test
  @DisplayName("已登记但已过期 ⇒ 报「已过有效期」，**不是**「未注册」")
  void expiredIsNotMissingRegistration() {
    List<Gap> gaps =
        evaluateRequirement(
            requirement(), registration(true, VAT_REGISTERED, "DE123", "LUCID-1", NOW.minusDays(1)),
            NOW);
    assertThat(gaps).extracting(Gap::gapType).containsExactly(GAP_REGISTRATION_EXPIRED);
    assertThat(gaps).noneMatch(gap -> GAP_JURISDICTION_NOT_REGISTERED.equals(gap.gapType()));
  }

  @Test
  @DisplayName("归档的登记 ⇒ 不算在册 ⇒ 未注册")
  void archivedRegistrationIsNotInForce() {
    RegistrationFact archived =
        new RegistrationFact(
            "reg-1", SUBJECT_SHOP, "shop-1", "EU-DE", true, VAT_REGISTERED, "DE123", "LUCID-1",
            NOW.minusYears(1), NOW.plusYears(1), true);
    List<Gap> gaps = evaluateRequirement(requirement(), archived, NOW);
    assertThat(gaps).extracting(Gap::gapType).containsExactly(GAP_JURISDICTION_NOT_REGISTERED);
  }

  // ==================================================================
  // 登记自身
  // ==================================================================

  @Test
  @DisplayName("未注册时 VAT 号为空 ⇒ **不报**缺号（没注册当然没号）")
  void notRegisteredDoesNotDoubleCountMissingNumber() {
    List<Gap> gaps =
        evaluateRegistration(
            registration(false, VAT_NOT_REGISTERED, "", "", NOW.plusYears(1)), EU, NOW);
    assertThat(gaps).isEmpty();
  }

  @Test
  @DisplayName("已注册但 VAT 号为空 ⇒ 报缺 VAT 号")
  void registeredWithoutNumberIsAGap() {
    List<Gap> gaps =
        evaluateRegistration(registration(true, VAT_REGISTERED, "", "", NOW.plusYears(1)), EU, NOW);
    assertThat(gaps).extracting(Gap::gapType).contains(GAP_VAT_NUMBER_MISSING);
  }

  @Test
  @DisplayName("辖区不要求登记号 ⇒ 不报缺 VAT 号")
  void jurisdictionWithoutVatRequirementDoesNotReportMissingNumber() {
    List<Gap> gaps =
        evaluateRegistration(registration(true, VAT_REGISTERED, "", "", NOW.plusYears(1)), BR, NOW);
    assertThat(gaps).extracting(Gap::gapType).doesNotContain(GAP_VAT_NUMBER_MISSING);
  }

  @Test
  @DisplayName("VAT 状态是豁免（无本地 VAT 号）⇒ 不报缺 VAT 号")
  void exemptStatusDoesNotReportMissingNumber() {
    List<Gap> gaps =
        evaluateRegistration(registration(true, VAT_EXEMPT, "", "", NOW.plusYears(1)), EU, NOW);
    assertThat(gaps).extracting(Gap::gapType).doesNotContain(GAP_VAT_NUMBER_MISSING);
  }

  @Test
  @DisplayName("辖区要求 EPR 且没填 ⇒ 报缺 EPR 注册号")
  void missingEprNumberIsAGap() {
    List<Gap> gaps =
        evaluateRegistration(
            registration(true, VAT_REGISTERED, "DE123", "", NOW.plusYears(1)), EU, NOW);
    assertThat(gaps).extracting(Gap::gapType).contains(GAP_EPR_NUMBER_MISSING);
  }

  @Test
  @DisplayName("辖区不要求 EPR ⇒ 不报缺 EPR 号")
  void jurisdictionWithoutEprDoesNotReportMissingEpr() {
    List<Gap> gaps =
        evaluateRegistration(
            registration(true, VAT_REGISTERED, "US-CA-TAX", "", NOW.plusYears(1)), US_CA, NOW);
    assertThat(gaps).extracting(Gap::gapType).doesNotContain(GAP_EPR_NUMBER_MISSING);
  }

  @Test
  @DisplayName("没记有效期 ⇒ 报「判不了」，**不是**「已过期」")
  void unknownValidityIsNotExpired() {
    List<Gap> gaps =
        evaluateRegistration(registration(true, VAT_REGISTERED, "DE123", "LUCID-1", null), EU, NOW);
    assertThat(gaps).extracting(Gap::gapType).contains(GAP_REGISTRATION_VALIDITY_UNKNOWN);
    assertThat(gaps).extracting(Gap::gapType).doesNotContain(GAP_REGISTRATION_EXPIRED);
  }

  @Test
  @DisplayName("已过有效期 ⇒ 报已过期；30 天内到期 ⇒ 报即将到期")
  void expiryStatesAreDistinguished() {
    List<Gap> expired =
        evaluateRegistration(
            registration(true, VAT_REGISTERED, "DE123", "LUCID-1", NOW.minusDays(3)), EU, NOW);
    assertThat(expired).extracting(Gap::gapType).contains(GAP_REGISTRATION_EXPIRED);

    List<Gap> soon =
        evaluateRegistration(
            registration(true, VAT_REGISTERED, "DE123", "LUCID-1", NOW.plusDays(10)), EU, NOW);
    assertThat(soon).extracting(Gap::gapType).contains(GAP_REGISTRATION_EXPIRING_SOON);
    assertThat(soon).extracting(Gap::gapType).doesNotContain(GAP_REGISTRATION_EXPIRED);
  }

  @Test
  @DisplayName("归档的登记不产生自身缺口")
  void archivedRegistrationHasNoSelfGaps() {
    RegistrationFact archived =
        new RegistrationFact(
            "reg-1", SUBJECT_SHOP, "shop-1", "EU-DE", true, VAT_REGISTERED, "", "", null, null, true);
    assertThat(evaluateRegistration(archived, EU, NOW)).isEmpty();
  }

  // ==================================================================
  // 覆盖状态
  // ==================================================================

  @Test
  @DisplayName("覆盖状态：MISSING / NOT_REGISTERED / EXPIRED / REGISTERED")
  void coverageStates() {
    assertThat(coverageState(null, NOW)).isEqualTo("MISSING");
    assertThat(coverageState(registration(false, VAT_NOT_REGISTERED, "", "", NOW.plusYears(1)), NOW))
        .isEqualTo("NOT_REGISTERED");
    assertThat(coverageState(registration(true, VAT_REGISTERED, "DE", "", NOW.minusDays(1)), NOW))
        .isEqualTo("EXPIRED");
    assertThat(coverageState(registration(true, VAT_REGISTERED, "DE", "", NOW.plusDays(1)), NOW))
        .isEqualTo("REGISTERED");
  }

  @Test
  @DisplayName("距失效天数：负数 = 已过期；没记有效期 = null")
  void daysToExpiryIsComputedCorrectly() {
    assertThat(daysToExpiry(registration(true, VAT_REGISTERED, "DE", "", NOW.minusDays(5)), NOW))
        .isEqualTo(-5);
    assertThat(daysToExpiry(registration(true, VAT_REGISTERED, "DE", "", NOW.plusDays(5)), NOW))
        .isEqualTo(5);
    assertThat(daysToExpiry(registration(true, VAT_REGISTERED, "DE", "", null), NOW)).isNull();
  }
}
