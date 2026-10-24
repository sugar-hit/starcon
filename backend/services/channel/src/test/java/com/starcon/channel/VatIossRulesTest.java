package com.starcon.channel;

import static com.starcon.channel.VatIossRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.VatIossRules.Decision;
import com.starcon.channel.VatIossRules.Inputs;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.3.2.1 VAT/IOSS 的判定规则单测（**纯函数**，不碰数据库）。
 *
 * <p>🔴 这组用例守的是蓝图那句加粗句：
 * 「<b>IOSS 订单与一般 VAT 订单必须区分统计</b>，混在一起申报是最常见的错误。」
 *
 * <p>守住的十件事：
 *
 * <ol>
 *   <li>符合条件 ⇒ 判成 IOSS；</li>
 *   <li>已取消 / 收货地不在欧盟 ⇒ 不在范围；</li>
 *   <li>没有收货国家 ⇒ <b>判不了</b>（不许默认归到 IOSS，也不许归到不在范围）；</li>
 *   <li>买家类型没采到 ⇒ <b>判不了</b>（猜成 B2C 会把 B2B 单错算进 IOSS）；</li>
 *   <li>B2B / 无登记 / 店铺不在范围 / 含应税消费品 / 超门槛 ⇒ 一般 VAT；</li>
 *   <li>门槛折不出（汇率读不到）⇒ <b>判不了</b>；</li>
 *   <li>货值**正好等于**门槛 ⇒ 仍是 IOSS（超门槛是「大于」，不是「大于等于」）；</li>
 *   <li>金额解析：精度超限直接拒（不静默四舍五入）、拒科学计数法、拒负税率；</li>
 *   <li>折算方向：1 原币 = rate 目标币；</li>
 *   <li>词表闭合：每个 reasonCode 都有人话。</li>
 * </ol>
 */
class VatIossRulesTest {

  /** 判定输入的构造器（默认值 = 一笔「符合 IOSS 的欧盟 B2C 订单」）。 */
  private static final class Spec {
    boolean cancelled = false;
    String destinationCountry = "DE";
    boolean destinationInEu = true;
    String customerType = "INDIVIDUAL";
    boolean hasActiveRegistration = true;
    boolean shopInScope = true;
    boolean hasExciseGoods = false;
    BigDecimal intrinsicValue = new BigDecimal("100.000000");
    BigDecimal threshold = new BigDecimal("150.000000");
    boolean thresholdComparable = true;

    Spec cancelled() {
      this.cancelled = true;
      return this;
    }

    Spec destination(String country, boolean inEu) {
      this.destinationCountry = country;
      this.destinationInEu = inEu;
      return this;
    }

    Spec customerType(String type) {
      this.customerType = type;
      return this;
    }

    Spec noRegistration() {
      this.hasActiveRegistration = false;
      return this;
    }

    Spec shopOutOfScope() {
      this.shopInScope = false;
      return this;
    }

    Spec excise() {
      this.hasExciseGoods = true;
      return this;
    }

    Spec intrinsic(BigDecimal value) {
      this.intrinsicValue = value;
      return this;
    }

    Spec thresholdNotComparable() {
      this.thresholdComparable = false;
      this.intrinsicValue = null;
      return this;
    }

    Inputs build() {
      return new Inputs(
          cancelled,
          destinationCountry,
          destinationInEu,
          customerType,
          hasActiveRegistration,
          shopInScope,
          hasExciseGoods,
          intrinsicValue,
          threshold,
          thresholdComparable);
    }
  }

  @Test
  @DisplayName("16.3.2.1 判定一：符合全部条件 ⇒ IOSS")
  void eligibleOrderIsIoss() {
    Decision decision = classify(new Spec().build());
    assertThat(decision.filingScope()).isEqualTo("IOSS");
    assertThat(decision.reasonCode()).isEqualTo("IOSS_ELIGIBLE");
    assertThat(decision.reasonDetail()).contains("适用 IOSS");
  }

  @Test
  @DisplayName("16.3.2.1 判定二：已取消 ⇒ 不在范围")
  void cancelledIsOutOfScope() {
    Decision decision = classify(new Spec().cancelled().build());
    assertThat(decision.filingScope()).isEqualTo("OUT_OF_SCOPE");
    assertThat(decision.reasonCode()).isEqualTo("ORDER_CANCELLED");
  }

  @Test
  @DisplayName("16.3.2.1 判定三：收货地不在欧盟 ⇒ 不在范围")
  void nonEuIsOutOfScope() {
    Decision decision = classify(new Spec().destination("US", false).build());
    assertThat(decision.filingScope()).isEqualTo("OUT_OF_SCOPE");
    assertThat(decision.reasonCode()).isEqualTo("NON_EU_DESTINATION");
  }

  @Test
  @DisplayName("16.3.2.1 判定四：没有收货国家 ⇒ 判不了（不许默认归到 IOSS）")
  void missingDestinationIsUnresolved() {
    Decision decision = classify(new Spec().destination("", true).build());
    assertThat(decision.filingScope()).isEqualTo("UNRESOLVED");
    assertThat(decision.reasonCode()).isEqualTo("NO_DESTINATION_COUNTRY");
    assertThat(decision.filingScope()).isNotEqualTo("IOSS");
  }

  @Test
  @DisplayName("16.3.2.1 判定五：买家类型没采到 ⇒ 判不了（不许猜成 B2C）")
  void missingCustomerTypeIsUnresolved() {
    Decision decision = classify(new Spec().customerType("").build());
    assertThat(decision.filingScope()).isEqualTo("UNRESOLVED");
    assertThat(decision.reasonCode()).isEqualTo("NO_CUSTOMER_TYPE");
  }

  @Test
  @DisplayName("16.3.2.1 判定六：B2B ⇒ 一般 VAT")
  void b2bIsGeneralVat() {
    Decision decision = classify(new Spec().customerType("ORGANIZATION").build());
    assertThat(decision.filingScope()).isEqualTo("GENERAL_VAT");
    assertThat(decision.reasonCode()).isEqualTo("B2B_ORDER");
  }

  @Test
  @DisplayName("16.3.2.1 判定七：没有生效登记 / 店铺不在范围 ⇒ 一般 VAT")
  void noRegistrationOrShopOutOfScopeIsGeneralVat() {
    Decision noReg = classify(new Spec().noRegistration().build());
    assertThat(noReg.filingScope()).isEqualTo("GENERAL_VAT");
    assertThat(noReg.reasonCode()).isEqualTo("NO_ACTIVE_REGISTRATION");

    Decision outOfScope = classify(new Spec().shopOutOfScope().build());
    assertThat(outOfScope.filingScope()).isEqualTo("GENERAL_VAT");
    assertThat(outOfScope.reasonCode()).isEqualTo("SHOP_OUT_OF_SCOPE");
  }

  @Test
  @DisplayName("16.3.2.1 判定八：含应税消费品 ⇒ 一般 VAT（哪怕没超门槛）")
  void exciseGoodsIsGeneralVat() {
    Decision decision = classify(new Spec().excise().build());
    assertThat(decision.filingScope()).isEqualTo("GENERAL_VAT");
    assertThat(decision.reasonCode()).isEqualTo("EXCISE_GOODS");
  }

  @Test
  @DisplayName("16.3.2.1 判定九：门槛折不出（汇率读不到）⇒ 判不了")
  void unresolvableThresholdIsUnresolved() {
    Decision decision = classify(new Spec().thresholdNotComparable().build());
    assertThat(decision.filingScope()).isEqualTo("UNRESOLVED");
    assertThat(decision.reasonCode()).isEqualTo("THRESHOLD_UNRESOLVED");
  }

  @Test
  @DisplayName("16.3.2.1 判定十：超过门槛 ⇒ 一般 VAT；**正好等于门槛仍是 IOSS**")
  void aboveThresholdIsGeneralVat() {
    Decision above = classify(new Spec().intrinsic(new BigDecimal("150.000001")).build());
    assertThat(above.filingScope()).isEqualTo("GENERAL_VAT");
    assertThat(above.reasonCode()).isEqualTo("ABOVE_THRESHOLD");

    Decision exact = classify(new Spec().intrinsic(new BigDecimal("150.000000")).build());
    assertThat(exact.filingScope()).isEqualTo("IOSS");
    assertThat(exact.reasonCode()).isEqualTo("IOSS_ELIGIBLE");
  }

  @Test
  @DisplayName("16.3.2.1 判定表是**闭合**的：每个 reasonCode 都有分档与人话")
  void reasonCodesAreClosed() {
    for (String code : REASON_CODES) {
      assertThat(reasonLabel(code)).as("reasonCode " + code + " 必须有人话").isNotBlank();
      assertThat(reasonLabel(code)).isNotEqualTo("未知原因");
    }
    for (String scope : FILING_SCOPES) {
      assertThat(scopeLabel(scope)).as("filingScope " + scope + " 必须有人话").isNotBlank();
      assertThat(scopeLabel(scope)).isNotEqualTo("未知分档");
    }
    assertThat(FILING_SCOPES).containsExactly("IOSS", "GENERAL_VAT", "OUT_OF_SCOPE", "UNRESOLVED");
    assertThat(REASON_CODES).contains("NO_CUSTOMER_TYPE");
  }

  @Test
  @DisplayName("16.3.2.1 数值纪律：精度超限直接拒，不静默四舍五入")
  void decimalParsingRejectsTooMuchPrecision() {
    assertThatThrownBy(() -> parseDecimal("0.1234567", "税率", 6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("小数位超过 6 位");
    assertThatThrownBy(() -> parseDecimal("1E3", "金额", 6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("科学计数法");
    assertThatThrownBy(() -> parseDecimal("", "金额", 6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parseDecimal("abc", "金额", 6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(parseDecimal("0.190000", "税率", 6)).isEqualByComparingTo("0.19");
  }

  @Test
  @DisplayName("16.3.2.1 数值纪律：税率不许负数")
  void rateRejectsNegative() {
    assertThatThrownBy(() -> parseRate("-0.19", "税率"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能是负数");
    assertThat(parseRate("0.19", "税率")).isEqualByComparingTo("0.19");
  }

  @Test
  @DisplayName("16.3.2.1 折算方向：1 原币 = rate 目标币")
  void convertDirection() {
    // 100 USD × 0.9 = 90 EUR
    assertThat(convert(new BigDecimal("100"), new BigDecimal("0.9")))
        .isEqualByComparingTo("90.000000");
    assertThat(money(convert(new BigDecimal("100"), new BigDecimal("0.9"))))
        .isEqualTo("90.000000");
  }
}
