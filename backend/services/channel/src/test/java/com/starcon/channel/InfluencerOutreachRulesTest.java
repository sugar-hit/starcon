package com.starcon.channel;

import static com.starcon.channel.InfluencerOutreachRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * 14.5.1.2 建联与样品 的<b>纯计算规则</b>单测。
 *
 * <p>这些是本模块最容易做丢的几条边界，逐条钉死：
 *
 * <ol>
 *   <li><b>建联状态机</b>：合法边、终态无出边、<b>DRAFT 不能凭空跳到 SENT</b>
 *       （SENT 只由「登记平台回执」产生）。
 *   <li><b>样品单状态由包裹推导</b>：签收只能来自包裹。
 *   <li><b>模板变量是闭合词表</b>：未知变量 / 必填缺值 / 占位符没写完 ⇒ 全部失败，
 *       绝不把 {@code {{xxx}}} 原样发给达人。
 *   <li><b>佣金两种形态不混算</b>。
 *   <li><b>频率上限</b>：每日上限 + 最小间隔。
 * </ol>
 */
class InfluencerOutreachRulesTest {

  // ==================================================================
  // 一、建联状态机
  // ==================================================================

  @Test
  @DisplayName("建联状态机：合法边可走，终态没有出边")
  void outreachStateMachine() {
    assertThat(canTransitionOutreach("DRAFT", "CANCELLED")).isTrue();
    assertThat(canTransitionOutreach("SENT", "REPLIED")).isTrue();
    assertThat(canTransitionOutreach("SENT", "NO_REPLY")).isTrue();
    assertThat(canTransitionOutreach("REPLIED", "ACCEPTED")).isTrue();
    assertThat(canTransitionOutreach("ACCEPTED", "COOPERATING")).isTrue();
    assertThat(canTransitionOutreach("COOPERATING", "COMPLETED")).isTrue();

    // 🔴 终态没有出边 —— 「已拒绝」再变回「已回复」是改历史，不是推进。
    for (String terminal : List.of("REJECTED", "NO_REPLY", "COMPLETED", "CANCELLED")) {
      assertThat(isTerminalOutreach(terminal)).as(terminal + " 必须是终态").isTrue();
      assertThat(nextOutreachStatuses(terminal)).as(terminal + " 没有出边").isEmpty();
    }
    assertThat(isTerminalOutreach("SENT")).isFalse();
    assertThat(isTerminalOutreach("COOPERATING")).isFalse();
  }

  @Test
  @DisplayName("建联状态机：DRAFT 不能凭空跳到 SENT（发送只由登记回执产生）")
  void draftCannotJumpToSent() {
    assertThat(canTransitionOutreach("DRAFT", "SENT"))
        .as("草稿只能被「登记平台回执」推成已发送")
        .isFalse();
    assertThat(nextOutreachStatuses("DRAFT")).containsExactly("CANCELLED");
  }

  @Test
  @DisplayName("建联状态机：跳级与非法状态被拒")
  void illegalTransitionsRejected() {
    assertThat(canTransitionOutreach("DRAFT", "COMPLETED")).isFalse();
    assertThat(canTransitionOutreach("REPLIED", "SENT")).isFalse();
    assertThat(canTransitionOutreach("ACCEPTED", "REPLIED")).isFalse();
    assertThat(canTransitionOutreach(null, "SENT")).isFalse();
    assertThat(canTransitionOutreach("SENT", null)).isFalse();
    assertThat(canTransitionOutreach("NOT_A_STATUS", "SENT")).isFalse();
  }

  @Test
  @DisplayName("封闭词表与 V289 的 CHECK 一致")
  void closedVocabularies() {
    assertThat(OUTREACH_STATUSES)
        .containsExactly(
            "DRAFT", "SENT", "REPLIED", "ACCEPTED", "REJECTED", "NO_REPLY", "COOPERATING",
            "COMPLETED", "CANCELLED");
    assertThat(SAMPLE_ORDER_STATUSES)
        .containsExactly("REQUESTED", "OUTBOUNDED", "DELIVERED", "SIGNED", "CANCELLED");
    assertThat(SHIPMENT_STATUSES)
        .containsExactly("SHIPPED", "IN_TRANSIT", "DELIVERED", "SIGNED", "LOST", "RETURNED");
    assertThat(FOLLOWUP_TYPES)
        .containsExactly("CALL", "IM", "EMAIL", "SAMPLE_FEEDBACK", "NEGOTIATION", "OTHER");
    assertThat(COMMISSION_TYPES).containsExactly("RATE", "FIXED");
    assertThat(COMMISSION_SCOPES).containsExactly("GLOBAL", "SEGMENT", "INFLUENCER");
  }

  // ==================================================================
  // 二、样品单状态由包裹推导
  // ==================================================================

  @Test
  @DisplayName("样品单状态由包裹推导：全部签收 ⇒ SIGNED")
  void sampleOrderDerivedFromShipments() {
    assertThat(deriveSampleOrderStatus(List.of(), false)).isEqualTo("REQUESTED");
    assertThat(deriveSampleOrderStatus(List.of("SHIPPED"), false)).isEqualTo("OUTBOUNDED");
    assertThat(deriveSampleOrderStatus(List.of("SHIPPED", "IN_TRANSIT"), false))
        .isEqualTo("OUTBOUNDED");
    assertThat(deriveSampleOrderStatus(List.of("DELIVERED"), false)).isEqualTo("DELIVERED");
    assertThat(deriveSampleOrderStatus(List.of("DELIVERED", "SIGNED"), false))
        .isEqualTo("DELIVERED");
    assertThat(deriveSampleOrderStatus(List.of("SIGNED", "SIGNED"), false)).isEqualTo("SIGNED");
    // 🔴 丢件 / 退回不是「送达」—— 样品单会停在 OUTBOUNDED（已出库但没到）。
    assertThat(deriveSampleOrderStatus(List.of("LOST"), false)).isEqualTo("OUTBOUNDED");
    assertThat(deriveSampleOrderStatus(List.of("RETURNED"), false)).isEqualTo("OUTBOUNDED");
    // 🔴 已取消优先于任何包裹状态。
    assertThat(deriveSampleOrderStatus(List.of("SIGNED"), true)).isEqualTo("CANCELLED");
    assertThat(deriveSampleOrderStatus(null, false)).isEqualTo("REQUESTED");
  }

  @Test
  @DisplayName("包裹状态机：合法边可走，SIGNED / LOST / RETURNED 是终态")
  void shipmentStateMachine() {
    assertThat(canTransitionShipment("SHIPPED", "IN_TRANSIT")).isTrue();
    assertThat(canTransitionShipment("SHIPPED", "DELIVERED")).isTrue();
    assertThat(canTransitionShipment("SHIPPED", "LOST")).isTrue();
    assertThat(canTransitionShipment("IN_TRANSIT", "DELIVERED")).isTrue();
    assertThat(canTransitionShipment("DELIVERED", "SIGNED")).isTrue();

    assertThat(canTransitionShipment("SIGNED", "IN_TRANSIT")).isFalse();
    assertThat(canTransitionShipment("LOST", "DELIVERED")).isFalse();
    assertThat(canTransitionShipment("RETURNED", "SIGNED")).isFalse();
    // 🔴 没送达不能直接签收。
    assertThat(canTransitionShipment("SHIPPED", "SIGNED")).isFalse();
    assertThat(nextShipmentStatuses("SIGNED")).isEmpty();
    assertThat(nextShipmentStatuses("LOST")).isEmpty();
  }

  // ==================================================================
  // 三、模板渲染
  // ==================================================================

  @Test
  @DisplayName("模板渲染：闭合词表里的变量被替换")
  void renderKnownVariables() {
    String out =
        render(
            "你好 {{display_name}}，我们是 {{shop_name}}（{{platform_name}}）。佣金 {{commission_rate}}。",
            Map.of(
                "display_name", "小美",
                "shop_name", "星云优选",
                "platform_name", "Amazon",
                "commission_rate", "10.00%"));
    assertThat(out).isEqualTo("你好 小美，我们是 星云优选（Amazon）。佣金 10.00%。");
  }

  @Test
  @DisplayName("模板渲染：未知变量直接失败（绝不把 {{xxx}} 原样发给达人）")
  void renderUnknownVariableFails() {
    assertThatThrownBy(() -> render("你好 {{达人名字}}", Map.of("display_name", "小美")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未登记的占位符");
    assertThatThrownBy(() -> render("你好 {{unknown_var}}", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("模板渲染：必填变量缺值直接失败")
  void renderRequiredVariableMissingFails() {
    assertThatThrownBy(() -> render("你好 {{display_name}}", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必填");
    assertThatThrownBy(() -> render("{{shop_name}}", Map.of("display_name", "小美")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("模板渲染：可选变量缺值渲染成空串，不失败")
  void renderOptionalVariableMissing() {
    assertThat(render("你好 {{display_name}}{{handle}}", Map.of("display_name", "小美")))
        .isEqualTo("你好 小美");
  }

  @Test
  @DisplayName("模板渲染：占位符没写完直接失败")
  void renderBrokenPlaceholderFails() {
    assertThatThrownBy(() -> render("你好 {{display_name", Map.of("display_name", "小美")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没写完");
  }

  @Test
  @DisplayName("模板变量词表是闭合的：没有「其它」桶")
  void templateVariablesAreClosed() {
    assertThat(TEMPLATE_VARIABLES.stream().map(TemplateVariable::name))
        .containsExactly(
            "display_name", "handle", "category", "shop_name", "platform_name", "segment_name",
            "commission_rate");
    assertThat(isKnownVariable("display_name")).isTrue();
    assertThat(isKnownVariable("其它")).isFalse();
    assertThat(isKnownVariable("other")).isFalse();
    assertThat(variableRequired("display_name")).isTrue();
    assertThat(variableRequired("handle")).isFalse();
    assertThat(variableRequired("unknown")).isNull();
  }

  // ==================================================================
  // 四、佣金计提
  // ==================================================================

  @Test
  @DisplayName("佣金计提：RATE 按基数乘比例，FIXED 就是固定额")
  void commissionAccrual() {
    assertThat(accrue("RATE", new BigDecimal("0.1"), null, new BigDecimal("1000")))
        .isEqualByComparingTo("100.0000");
    // 🔴 FIXED 形态下基数只作留痕，不参与计算。
    assertThat(accrue("FIXED", null, new BigDecimal("50"), new BigDecimal("1000")))
        .isEqualByComparingTo("50.0000");
    // 🔴 比例形态下基数缺省按 0（不是按 1）。
    assertThat(accrue("RATE", new BigDecimal("0.1"), null, null)).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("佣金计提：形态与取值不匹配直接失败")
  void commissionAccrualMismatchFails() {
    assertThatThrownBy(() -> accrue("RATE", null, new BigDecimal("10"), new BigDecimal("100")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("佣金比例");
    assertThatThrownBy(() -> accrue("FIXED", new BigDecimal("0.1"), null, new BigDecimal("100")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("固定金额");
    assertThatThrownBy(() -> accrue("HYBRID", new BigDecimal("0.1"), new BigDecimal("1"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的佣金形态");
  }

  // ==================================================================
  // 五、频率上限
  // ==================================================================

  @Test
  @DisplayName("频率上限：超过每日上限被拒并说清原因")
  void rateLimitDailyCap() {
    RateCheck over = checkRate(20, 20, 3600L, 60);
    assertThat(over.allowed()).isFalse();
    assertThat(over.reason()).contains("每日上限").contains("20");

    RateCheck ok = checkRate(19, 20, 3600L, 60);
    assertThat(ok.allowed()).isTrue();
    assertThat(ok.reason()).isEmpty();
  }

  @Test
  @DisplayName("频率上限：最小间隔没到被拒")
  void rateLimitMinInterval() {
    RateCheck tooSoon = checkRate(1, 20, 30L, 60);
    assertThat(tooSoon.allowed()).isFalse();
    assertThat(tooSoon.reason()).contains("最小间隔");

    assertThat(checkRate(1, 20, 30L, 0).allowed())
        .as("间隔配 0 = 运营显式说「不限间隔」")
        .isTrue();
    assertThat(checkRate(1, 20, null, 60).allowed())
        .as("今天还没发过 ⇒ 没有间隔约束")
        .isTrue();
  }
}
