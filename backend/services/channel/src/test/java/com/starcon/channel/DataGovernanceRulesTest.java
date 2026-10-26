package com.starcon.channel;

import static com.starcon.channel.DataGovernanceRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.DataGovernanceRules.Adjudication;
import com.starcon.channel.DataGovernanceRules.PolicyFact;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.6.2.1 留存期裁决规则（**纯函数单测**，不碰数据库）。
 *
 * <p>本测试类钉住蓝图那条加粗句的全部形状：
 * 「**财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先**」。
 *
 * <ol>
 *   <li>两类留存期分开（STANDARD / FINANCIAL）。</li>
 *   <li>生效留存期 = 两者取长（法定留存期是**下限**）。</li>
 *   <li>冲突时 {@code resolution = FINANCIAL_PREVAILS}。</li>
 *   <li>法定留存期没走完 ⇒ 删除被**推迟**（{@code deletionBlocked}），
 *       并给出推迟到哪一天（{@code financialMaturityAt}）。</li>
 *   <li><b>判不了 ≠ 默认放行</b>：没登记策略 ⇒ {@code NO_POLICY}。</li>
 * </ol>
 */
class DataGovernanceRulesTest {

  static final OffsetDateTime FROM = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  // 🔴 判定时刻取「策略生效后不久」——这样 30 天的法定留存期**还没走完**，
  //   删除才真的会被挡住（若 NOW 晚于 30 天后，法定期已过，删除本就不该被挡）。
  static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 10, 0, 0, 0, 0, ZoneOffset.UTC);

  static PolicyFact standard(long days) {
    return new PolicyFact(
        UUID.randomUUID(), CLASS_STANDARD, BigDecimal.valueOf(days), "内部制度", FROM);
  }

  static PolicyFact financial(long days) {
    return new PolicyFact(
        UUID.randomUUID(), CLASS_FINANCIAL, BigDecimal.valueOf(days), "税法要求", FROM);
  }

  @Test
  @DisplayName("没登记任何留存策略 ⇒ NO_POLICY（判不了，不是「没冲突」）")
  void noPolicyIsUndecidable() {
    Adjudication verdict = adjudicate(List.of(), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_NO_POLICY);
    assertThat(verdict.effectiveRetentionDays()).isNull();
    assertThat(verdict.deletionBlocked()).isFalse();
    assertThat(verdict.rationale()).contains("判不了");
  }

  @Test
  @DisplayName("只有普通留存期 ⇒ STANDARD_APPLIES，删除不被挡")
  void standardOnly() {
    Adjudication verdict = adjudicate(List.of(standard(180)), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_STANDARD_ONLY);
    assertThat(verdict.resolution()).isEqualTo(RESOLUTION_STANDARD_APPLIES);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("180");
    assertThat(verdict.deletionBlocked()).isFalse();
    assertThat(verdict.financialMaturityAt()).isNull();
  }

  @Test
  @DisplayName("只有财务（法定）留存期 ⇒ FINANCIAL_PREVAILS")
  void financialOnly() {
    Adjudication verdict = adjudicate(List.of(financial(3650)), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_FINANCIAL_ONLY);
    assertThat(verdict.resolution()).isEqualTo(RESOLUTION_FINANCIAL_PREVAILS);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("3650");
    assertThat(verdict.deletionBlocked()).isTrue();
    assertThat(verdict.financialMaturityAt()).isEqualTo(FROM.plusDays(3650));
  }

  @Test
  @DisplayName("财务期更长 ⇒ FINANCIAL_LONGER + 财务留存优先，生效期取财务期")
  void financialLongerWins() {
    Adjudication verdict = adjudicate(List.of(standard(180), financial(3650)), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_FINANCIAL_LONGER);
    assertThat(verdict.resolution()).isEqualTo(RESOLUTION_FINANCIAL_PREVAILS);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("3650");
    assertThat(verdict.deletionBlocked()).isTrue();
    assertThat(verdict.rationale()).contains("财务留存优先").contains("3650").contains("180");
  }

  @Test
  @DisplayName("普通期更长 ⇒ STANDARD_LONGER，但法定留存期仍挡住删除")
  void standardLongerStillBlocksOnFinancialFloor() {
    Adjudication verdict = adjudicate(List.of(standard(3650), financial(30)), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_STANDARD_LONGER);
    assertThat(verdict.resolution()).isEqualTo(RESOLUTION_STANDARD_APPLIES);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("3650");
    // 🔴 法定留存期（下限）没走完之前，删除**照样**被挡住 —— 这是「财务留存优先」的落点。
    assertThat(verdict.deletionBlocked()).isTrue();
    assertThat(verdict.financialMaturityAt()).isEqualTo(FROM.plusDays(30));
  }

  @Test
  @DisplayName("两条期限一样长 ⇒ 裁决仍是财务留存优先")
  void equalPeriodsStillPreferFinancial() {
    Adjudication verdict = adjudicate(List.of(standard(365), financial(365)), NOW);
    assertThat(verdict.conflictKind()).isEqualTo(KIND_NO_CONFLICT);
    assertThat(verdict.resolution()).isEqualTo(RESOLUTION_FINANCIAL_PREVAILS);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("365");
  }

  @Test
  @DisplayName("法定留存期走完之后 ⇒ 不再阻挡删除")
  void deletionUnblockedAfterFinancialMaturity() {
    OffsetDateTime later = FROM.plusDays(4000);
    Adjudication verdict = adjudicate(List.of(standard(180), financial(3650)), later);
    assertThat(verdict.deletionBlocked()).isFalse();
    assertThat(verdict.rationale()).contains("已走完");
  }

  @Test
  @DisplayName("最早可清理日 = 裁决所依据的策略生效日 + 生效留存期")
  void eligibleAtUsesGoverningPolicy() {
    Adjudication verdict = adjudicate(List.of(standard(180), financial(3650)), NOW);
    assertThat(eligibleAt(verdict)).isEqualTo(FROM.plusDays(3650));
  }

  @Test
  @DisplayName("同一（域 × 类别）两条并存时按更长的一条算（防御性）")
  void takesTheLongerOfSameClass() {
    Adjudication verdict =
        adjudicate(List.of(standard(180), standard(365), financial(30)), NOW);
    assertThat(verdict.effectiveRetentionDays()).isEqualByComparingTo("365");
    assertThat(verdict.conflictKind()).isEqualTo(KIND_STANDARD_LONGER);
  }

  @Test
  @DisplayName("标签函数给出人话，不是裸枚举值")
  void labelsAreHumanReadable() {
    assertThat(retentionClassLabel(CLASS_FINANCIAL)).isEqualTo("法定（财务）留存期");
    assertThat(retentionClassLabel(CLASS_STANDARD)).isEqualTo("普通留存期");
    assertThat(resolutionLabel(RESOLUTION_FINANCIAL_PREVAILS)).isEqualTo("财务留存优先");
    assertThat(conflictKindLabel(KIND_NO_POLICY)).contains("判不了");
    assertThat(legalBasisLabel("LEGAL_OBLIGATION")).contains("法定义务");
    assertThat(deletionStatusLabel("DEFERRED")).contains("推迟");
    assertThat(cleanupStatusLabel("PARTIAL")).isEqualTo("部分完成");
    assertThat(triggerKindLabel("RETENTION_EXPIRY")).isEqualTo("留存期到期");
  }

  @Test
  @DisplayName("天数的人话写法去掉多余的 .0")
  void plainDropsTrailingZeros() {
    assertThat(plain(new BigDecimal("365.0"))).isEqualTo("365");
    assertThat(plain(new BigDecimal("365"))).isEqualTo("365");
    assertThat(plain(null)).isEqualTo("—");
  }
}
