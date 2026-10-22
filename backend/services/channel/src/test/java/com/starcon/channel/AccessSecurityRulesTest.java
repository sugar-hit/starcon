package com.starcon.channel;

import static com.starcon.channel.AccessSecurityRules.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.5.2.1 的纯函数单测：网段合法性、状态机、严重度映射、幂等键。
 *
 * <p>🔴 这些是<b>不依赖数据库就能判定</b>的规则，所以单独钉住：
 * 一旦它们漂了，DB 层的守卫触发器会开始拒绝合法写入，而报错信息完全不会指向真正的原因。
 */
class AccessSecurityRulesTest {

  @Test
  @DisplayName("16.5.2.1 网段：合法写法都收下（IPv4 / IPv6 / 裸地址 / 带主机位）")
  void acceptsValidCidr() {
    assertThat(isValidCidr("10.0.0.0/8")).isTrue();
    assertThat(isValidCidr("192.168.1.1")).isTrue();
    assertThat(isValidCidr("0.0.0.0/0")).isTrue();
    assertThat(isValidCidr("10.0.0.5/24")).as("带主机位也要收（PG 的 inet 收，cidr 不收）").isTrue();
    assertThat(isValidCidr("2001:db8::/32")).isTrue();
    assertThat(isValidCidr("::1")).isTrue();
    assertThat(isValidCidr("  10.0.0.0/8  ")).isTrue();
  }

  @Test
  @DisplayName("16.5.2.1 网段：非法写法都拒绝（不猜、不放行）")
  void rejectsInvalidCidr() {
    assertThat(isValidCidr(null)).isFalse();
    assertThat(isValidCidr("")).isFalse();
    assertThat(isValidCidr("999.1.1.1")).isFalse();
    assertThat(isValidCidr("10.0.0.0/33")).isFalse();
    assertThat(isValidCidr("2001:db8::/129")).isFalse();
    assertThat(isValidCidr("10.0.0.0/8/16")).isFalse();
    assertThat(isValidCidr("10.0.0.0/")).isFalse();
    assertThat(isValidCidr("10.0.0.0/+8")).isFalse();
    assertThat(isValidCidr("10.0.0.0/ 8")).isFalse();
    // 🔴 前导零：同一串在不同解析器下含义不同（八进制 vs 十进制），必须拒绝
    assertThat(isValidCidr("010.0.0.1")).isFalse();
    // 🔴 主机名：解析它要去打 DNS，一个请求就能把服务变成 DNS 客户端
    assertThat(isValidCidr("example.com")).isFalse();
    assertThat(isValidCidr("evil.example.com/24")).isFalse();
  }

  @Test
  @DisplayName("16.5.2.1 告警状态机：新警 → 已确认 / 已忽略 / 已处置；终态不可再改")
  void alertStateMachine() {
    assertThat(canTransition(AlertStatus.NEW, AlertStatus.ACKNOWLEDGED)).isTrue();
    assertThat(canTransition(AlertStatus.NEW, AlertStatus.IGNORED)).isTrue();
    assertThat(canTransition(AlertStatus.NEW, AlertStatus.RESOLVED)).isTrue();
    assertThat(canTransition(AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED)).isTrue();
    assertThat(canTransition(AlertStatus.ACKNOWLEDGED, AlertStatus.IGNORED)).isTrue();
    // 🔴 终态
    assertThat(canTransition(AlertStatus.RESOLVED, AlertStatus.NEW)).isFalse();
    assertThat(canTransition(AlertStatus.RESOLVED, AlertStatus.ACKNOWLEDGED)).isFalse();
    assertThat(canTransition(AlertStatus.IGNORED, AlertStatus.RESOLVED)).isFalse();
    // 🔴 已忽略 / 已处置不许退回新警
    assertThat(canTransition(AlertStatus.ACKNOWLEDGED, AlertStatus.NEW)).isFalse();
    // 同态是幂等的
    assertThat(canTransition(AlertStatus.NEW, AlertStatus.NEW)).isTrue();
  }

  @Test
  @DisplayName("16.5.2.1 MFA 状态机：待绑定 → 已启用 → 已停用 / 已撤销")
  void mfaStateMachine() {
    assertThat(MfaStatus.PENDING.canTransitionTo(MfaStatus.ACTIVE)).isTrue();
    assertThat(MfaStatus.ACTIVE.canTransitionTo(MfaStatus.DISABLED)).isTrue();
    assertThat(MfaStatus.DISABLED.canTransitionTo(MfaStatus.ACTIVE)).isTrue();
    assertThat(MfaStatus.ACTIVE.canTransitionTo(MfaStatus.REVOKED)).isTrue();
    assertThat(MfaStatus.REVOKED.canTransitionTo(MfaStatus.ACTIVE)).isFalse();
    assertThat(MfaStatus.PENDING.canTransitionTo(MfaStatus.REVOKED)).isTrue();
  }

  @Test
  @DisplayName("16.5.2.1 严重度：失败激增比成功激严重；到阈值两倍升一档")
  void severityMapping() {
    assertThat(failedBurstSeverity(3, 3)).isEqualTo(Severity.HIGH);
    assertThat(failedBurstSeverity(6, 3)).isEqualTo(Severity.CRITICAL);
    assertThat(successBurstSeverity(3, 3)).isEqualTo(Severity.MEDIUM);
    assertThat(successBurstSeverity(6, 3)).isEqualTo(Severity.HIGH);
    // 🔴 连续成功登录的严重度必须低于连续失败（误报一多就没人看告警了）
    assertThat(successBurstSeverity(3, 3).weight()).isLessThan(failedBurstSeverity(3, 3).weight());
  }

  @Test
  @DisplayName("16.5.2.1 严重度：只升不降")
  void severityOnlyEscalates() {
    assertThat(Severity.LOW.max(Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
    assertThat(Severity.CRITICAL.max(Severity.LOW)).isEqualTo(Severity.CRITICAL);
    assertThat(Severity.MEDIUM.max(Severity.MEDIUM)).isEqualTo(Severity.MEDIUM);
  }

  @Test
  @DisplayName("16.5.2.1 幂等键：只由类型 + 主体决定（不含窗口，否则每次扫描都刷新告警）")
  void alertDedupKeyIsStable() {
    assertThat(alertDedupKey(AlertType.FAILED_LOGIN_BURST, "alice"))
        .isEqualTo("FAILED_LOGIN_BURST|alice");
    assertThat(alertDedupKey(AlertType.FAILED_LOGIN_BURST, " alice "))
        .isEqualTo("FAILED_LOGIN_BURST|alice");
    assertThat(alertDedupKey(AlertType.SUCCESS_LOGIN_BURST, "alice"))
        .as("不同告警类型必须是不同的键")
        .isNotEqualTo(alertDedupKey(AlertType.FAILED_LOGIN_BURST, "alice"));
  }
}
