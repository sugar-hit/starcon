package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.2.2.2 认证与资质 的<b>规则层单测</b>（纯函数，不碰数据库）。
 *
 * <p>🔴 钉住的是三件容易悄悄错掉的事：
 *
 * <ol>
 *   <li><b>到期状态的五个分支</b>：撤销 &gt; 尚未生效 &gt; 已过期 &gt; 即将过期 &gt; 有效。
 *       顺序反了会让「已撤销但还没到期」的证被算成「即将过期」，运营看到的是一条提醒而不是一条作废。
 *   <li><b>剩余天数向下取整</b>：「还剩 12 小时」显示成「还剩 0 天」而不是「还剩 1 天」——
 *       那是同一天到期，本该是最该提醒的那一档。
 *   <li><b>认证类型编码的形态</b>：大写字母 / 数字 / 下划线 / 短横线，且必须以字母或数字开头
 *       （否则能造出「空编码」或以短横线开头的编码，界面上排序与匹配都会怪）。
 * </ol>
 */
class ProductCertificationRulesTest {

  static final OffsetDateTime NOW = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  @Test
  @DisplayName("16.2.2.2 到期状态：五个分支各自算对")
  void expiryStateBranches() {
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "REVOKED", NOW.minusDays(10), NOW.plusDays(10), NOW, 30))
        .as("已撤销优先于一切")
        .isEqualTo("REVOKED");
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.plusDays(1), NOW.plusDays(10), NOW, 30))
        .as("还没生效")
        .isEqualTo("FUTURE");
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.minusDays(100), NOW.minusSeconds(1), NOW, 30))
        .as("已经到期")
        .isEqualTo("EXPIRED");
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.minusDays(10), NOW.plusDays(5), NOW, 30))
        .as("落在即将过期窗口内")
        .isEqualTo("EXPIRING");
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.minusDays(10), NOW.plusDays(200), NOW, 30))
        .isEqualTo("VALID");
  }

  @Test
  @DisplayName("16.2.2.2 即将过期窗口边界：窗口外的第一天不算，窗口内的最后一天算")
  void warningWindowBoundary() {
    // 正好落在窗口边界上 ⇒ 算「即将过期」（边界含端点，否则第 30 天会漏掉）
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.minusDays(1), NOW.plusDays(30), NOW, 30))
        .isEqualTo("EXPIRING");
    assertThat(
            ProductCertificationRules.classifyExpiry(
                "ACTIVE", NOW.minusDays(1), NOW.plusDays(31), NOW, 30))
        .isEqualTo("VALID");
  }

  @Test
  @DisplayName("16.2.2.2 剩余天数向下取整：还剩 12 小时是 0 天，已过期 1 小时是 -1 天")
  void daysRemainingFloors() {
    assertThat(ProductCertificationRules.daysRemaining(NOW.plusDays(5), NOW)).isEqualTo(5);
    assertThat(ProductCertificationRules.daysRemaining(NOW.plusHours(12), NOW))
        .as("还剩 12 小时显示成「还剩 0 天」而不是「还剩 1 天」")
        .isEqualTo(0);
    assertThat(ProductCertificationRules.daysRemaining(NOW.minusHours(1), NOW))
        .as("已过期 1 小时算「已过期 1 天」而不是 0 天")
        .isEqualTo(-1);
    assertThat(ProductCertificationRules.daysRemaining(NOW.minusDays(3), NOW)).isEqualTo(-3);
  }

  @Test
  @DisplayName("16.2.2.2 认证类型编码：规范化与形态校验")
  void typeCodeNormalization() {
    assertThat(ProductCertificationRules.normalizeTypeCode("  ce  ")).isEqualTo("CE");
    assertThat(ProductCertificationRules.normalizeTypeCode(null)).isEmpty();

    assertThat(ProductCertificationRules.validTypeCode("CE")).isTrue();
    assertThat(ProductCertificationRules.validTypeCode("ROHS")).isTrue();
    assertThat(ProductCertificationRules.validTypeCode("EN_71")).isTrue();
    assertThat(ProductCertificationRules.validTypeCode("A-B")).isTrue();

    assertThat(ProductCertificationRules.validTypeCode("")).isFalse();
    assertThat(ProductCertificationRules.validTypeCode("CE.2024"))
        .as("带点的不是编码")
        .isFalse();
    assertThat(ProductCertificationRules.validTypeCode("-CE"))
        .as("不能以短横线开头")
        .isFalse();
    assertThat(ProductCertificationRules.validTypeCode("A".repeat(33)))
        .as("超过 32 位")
        .isFalse();
  }

  @Test
  @DisplayName("16.2.2.2 认证档案编码：字母数字下划线短横线，首字符必须是字母或数字")
  void certificationCodeValidation() {
    assertThat(ProductCertificationRules.validCertificationCode("CE-2026-001")).isTrue();
    assertThat(ProductCertificationRules.validCertificationCode("ce_1")).isTrue();
    assertThat(ProductCertificationRules.validCertificationCode("_CE")).isFalse();
    assertThat(ProductCertificationRules.validCertificationCode("CE 1")).isFalse();
    assertThat(ProductCertificationRules.validCertificationCode("A".repeat(65))).isFalse();
  }
}
