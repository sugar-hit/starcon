package com.starcon.channel;

import static com.starcon.channel.LinkageRiskRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.5.2.2 关联风险的纯函数断言（不碰数据库）。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 阈值不给默认值</b>：严重度完全由「因子敏感度 + 店铺数 + 是否跨平台」实算，
 *       系统不自带任何默认严重度。</li>
 *   <li><b>🔴 关联组键是确定性的</b>：同一组店铺重扫落到同一个键（否则每次扫描都刷一条新提示）。</li>
 *   <li><b>🔴 状态机</b>：提示与建议的终态不可回退。</li>
 *   <li><b>🔴 维度值域</b>：支付方式 / 收款账户<b>不可登记</b>（归 12.7.1.1，未交付）；
 *       法人<b>不可登记</b>（真相在 {@code shop.legal_entity_id}）。</li>
 *   <li><b>🔴 IP 字面量判定</b>：非法字面量被拒，主机名被拒（绝不让服务变 DNS 客户端）。</li>
 * </ol>
 */
class LinkageRiskRulesTest {

  @Test
  @DisplayName("16.5.2.2 严重度：因子敏感度决定底档，店铺数超阈值升一档")
  void clusterSeverityEscalatesWithShopCount() {
    assertThat(LinkageRiskRules.clusterSeverity(Sensitivity.HIGH, 2, 2))
        .isEqualTo(Severity.HIGH);
    // 🔴 阈值 2 ⇒ 4 家店（>= 2+2）升一档
    assertThat(LinkageRiskRules.clusterSeverity(Sensitivity.HIGH, 4, 2))
        .isEqualTo(Severity.CRITICAL);
    assertThat(LinkageRiskRules.clusterSeverity(Sensitivity.MEDIUM, 3, 2))
        .isEqualTo(Severity.MEDIUM);
    assertThat(LinkageRiskRules.clusterSeverity(Sensitivity.LOW, 2, 2)).isEqualTo(Severity.LOW);
    // 🔴 最高档不再往上叠
    assertThat(LinkageRiskRules.clusterSeverity(Sensitivity.HIGH, 99, 2))
        .isEqualTo(Severity.CRITICAL);
  }

  @Test
  @DisplayName("16.5.2.2 风险等级：跨平台再升一档（同平台不动）")
  void crossPlatformRaisesRiskLevel() {
    assertThat(LinkageRiskRules.clusterRiskLevel(Severity.MEDIUM, false))
        .isEqualTo(Severity.MEDIUM);
    assertThat(LinkageRiskRules.clusterRiskLevel(Severity.MEDIUM, true)).isEqualTo(Severity.HIGH);
    assertThat(LinkageRiskRules.clusterRiskLevel(Severity.HIGH, true)).isEqualTo(Severity.CRITICAL);
    assertThat(LinkageRiskRules.clusterRiskLevel(Severity.CRITICAL, true))
        .isEqualTo(Severity.CRITICAL);
  }

  @Test
  @DisplayName("16.5.2.2 因子成立条件：达到该维度自己选的阈值才算一条边")
  void factorQualifies() {
    assertThat(LinkageRiskRules.factorQualifies(2, 2)).isTrue();
    assertThat(LinkageRiskRules.factorQualifies(1, 2)).isFalse();
    assertThat(LinkageRiskRules.factorQualifies(3, 3)).isTrue();
  }

  @Test
  @DisplayName("16.5.2.2 关联组键是确定性的：成员集合相同 ⇒ 键相同（与顺序无关）")
  void clusterKeyIsDeterministic() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    assertThat(LinkageRiskRules.clusterKey(List.of(a, b, c)))
        .isEqualTo(LinkageRiskRules.clusterKey(List.of(c, a, b)));
    assertThat(LinkageRiskRules.clusterKey(List.of(a, b))).isNotEqualTo(LinkageRiskRules.clusterKey(List.of(a, c)));
    assertThat(LinkageRiskRules.clusterKey(List.of(a, b))).hasSize(64);
  }

  @Test
  @DisplayName("16.5.2.2 提示状态机：已整改 / 已忽略是终态")
  void alertStateMachine() {
    assertThat(LinkageRiskRules.canTransition(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED)).isTrue();
    assertThat(LinkageRiskRules.canTransition(AlertStatus.OPEN, AlertStatus.REMEDIATED)).isTrue();
    assertThat(LinkageRiskRules.canTransition(AlertStatus.ACKNOWLEDGED, AlertStatus.REMEDIATED)).isTrue();
    assertThat(LinkageRiskRules.canTransition(AlertStatus.REMEDIATED, AlertStatus.OPEN)).isFalse();
    assertThat(LinkageRiskRules.canTransition(AlertStatus.DISMISSED, AlertStatus.ACKNOWLEDGED)).isFalse();
  }

  @Test
  @DisplayName("16.5.2.2 建议状态机：已忽略 / 已完成是终态")
  void remediationStateMachine() {
    assertThat(LinkageRiskRules.canTransition(RemediationStatus.PROPOSED, RemediationStatus.ACCEPTED))
        .isTrue();
    assertThat(LinkageRiskRules.canTransition(RemediationStatus.ACCEPTED, RemediationStatus.DONE)).isTrue();
    assertThat(LinkageRiskRules.canTransition(RemediationStatus.DONE, RemediationStatus.ACCEPTED)).isFalse();
    assertThat(LinkageRiskRules.canTransition(RemediationStatus.DISMISSED, RemediationStatus.DONE)).isFalse();
  }

  @Test
  @DisplayName("16.5.2.2 维度值域：支付方式 / 收款账户不可登记，法人不可登记")
  void dimensionRegistry() {
    assertThat(Dimension.PAYMENT_METHOD.supported()).isFalse();
    assertThat(Dimension.PAYOUT_ACCOUNT.supported()).isFalse();
    assertThat(Dimension.PAYMENT_METHOD.registrable()).isFalse();
    assertThat(Dimension.PAYOUT_ACCOUNT.registrable()).isFalse();
    // 🔴 法人维度能判（事实来自 shop.legal_entity_id），但**不能登记**
    assertThat(Dimension.LEGAL_ENTITY.supported()).isTrue();
    assertThat(Dimension.LEGAL_ENTITY.registrable()).isFalse();
    assertThat(Dimension.LEGAL_ENTITY.autoDiscovered()).isTrue();
    assertThat(Dimension.DEVICE.registrable()).isTrue();
    assertThat(Dimension.DEVICE.autoDiscovered()).isFalse();
  }

  @Test
  @DisplayName("16.5.2.2 IP 字面量：非法值与主机名被拒（绝不让服务变 DNS 客户端）")
  void ipIdentifierValidation() {
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "203.0.113.7")).isTrue();
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "203.0.113.0/24")).isTrue();
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "2001:db8::/32")).isTrue();
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "not-an-ip")).isFalse();
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "shop.example.com")).isFalse();
    assertThat(LinkageRiskRules.isValidIdentifier(Dimension.NETWORK_IP, "010.0.0.1")).isFalse();
    // 🔴 设备维度不猜归一化：只去空白 + 转大写
    assertThat(LinkageRiskRules.normalizeIdentifierKey(Dimension.DEVICE, "  dev-01 "))
        .isEqualTo("DEV-01");
    assertThat(LinkageRiskRules.normalizeIdentifierKey(Dimension.NETWORK_IP, "203.0.113.7"))
        .isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("16.5.2.2 整改建议：按维度生成，且没有一条是模拟登录 / 指纹浏览器")
  void remediationMapping() {
    assertThat(LinkageRiskRules.recommendationFor(Dimension.DEVICE))
        .isEqualTo(RecommendationCode.SEPARATE_DEVICE);
    assertThat(LinkageRiskRules.recommendationFor(Dimension.NETWORK_IP))
        .isEqualTo(RecommendationCode.SEPARATE_NETWORK);
    assertThat(LinkageRiskRules.recommendationFor(Dimension.ADDRESS))
        .isEqualTo(RecommendationCode.SEPARATE_ADDRESS);
    assertThat(LinkageRiskRules.recommendationFor(Dimension.LEGAL_ENTITY))
        .isEqualTo(RecommendationCode.VERIFY_LEGAL_ENTITY);
    for (RecommendationCode code : RecommendationCode.values()) {
      assertThat(code.name()).doesNotContain("FINGERPRINT");
      assertThat(code.name()).doesNotContain("SIMULATE");
      assertThat(code.name()).doesNotContain("PROXY");
    }
  }

  @Test
  @DisplayName("16.5.2.2 幂等键：同一关联组一条提示，同一提示下同一建议一条")
  void dedupKeys() {
    String key = LinkageRiskRules.clusterKey(List.of(UUID.randomUUID(), UUID.randomUUID()));
    assertThat(LinkageRiskRules.alertDedupKey(key)).isEqualTo("SHARED_FACTOR|" + key);
    assertThat(LinkageRiskRules.remediationDedupKey("alert-1", RecommendationCode.SEPARATE_DEVICE))
        .isEqualTo("alert-1|SEPARATE_DEVICE");
  }
}
