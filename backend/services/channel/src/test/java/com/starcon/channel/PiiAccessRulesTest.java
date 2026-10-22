package com.starcon.channel;

import static com.starcon.channel.PiiAccessRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.PiiAccessRules.Decision;
import com.starcon.channel.PiiAccessRules.GrantFact;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.6.1.1 PII 管理的<b>纯规则</b>单测（脱敏 + 授权判定）。
 *
 * <p>🔴 这个测试类钉住的是本模块最容易被悄悄改坏的两件事：
 *
 * <ol>
 *   <li><b>脱敏必须真的脱敏</b>：掩码值**不得等于明文**，也**不得是明文的可逆编码**
 *       （用 * 逐字掩码就天然满足后者）。</li>
 *   <li><b>授权判定必须分得清「没有授权」「范围不够」「判不了」</b>——
 *       这三者在审计里留下的理由不同，混成一句「没权限」就没人能追责。</li>
 * </ol>
 */
class PiiAccessRulesTest {

  // ================================================================ 姓名

  @Test
  @DisplayName("16.6.1.1 姓名脱敏：西式保留姓氏、中文式保留首字")
  void masksNames() {
    assertThat(maskName("Alice Smith")).isEqualTo("A**** Smith");
    assertThat(maskName("王小明")).isEqualTo("王**");
    assertThat(maskName("李")).isEqualTo("李*");
    assertThat(maskName("")).isEmpty();
    assertThat(maskName(null)).isEmpty();
  }

  @Test
  @DisplayName("16.6.1.1 姓名脱敏：掩码值不等于明文，也不含有明文")
  void maskedNameNeverEqualsPlain() {
    for (String plain : List.of("Alice Smith", "王小明", "Bob")) {
      String masked = maskName(plain);
      assertThat(masked).as("掩码值不得等于明文：" + plain).isNotEqualTo(plain);
      assertThat(masked).as("掩码值不得含有明文：" + plain).doesNotContain(plain);
    }
    // 🔴 单字名是个**例外**：姓氏只有一个字符时，「保留姓氏」意味着必须留下这个字符
    //   （否则就什么都不剩了）。这条只断言「不等于明文」——
    //   要求掩码值不含那个唯一的字符，等于要求「单字名一律全掩」，
    //   那与「姓名保留姓氏」的规则直接冲突。
    assertThat(maskName("李")).isEqualTo("李*").isNotEqualTo("李");
  }

  // ================================================================ 电话

  @Test
  @DisplayName("16.6.1.1 电话脱敏：保留末四位，其余逐位掩码")
  void masksPhone() {
    // 🔴 掩码长度按**数字个数**算（分隔符不携带信息）⇒ +1-555-0100 是 8 位数字
    //   ⇒ 4 颗星 + 末四位。
    assertThat(maskPhone("+1-555-0100")).isEqualTo("****0100");
    assertThat(maskPhone("13800138000")).isEqualTo("*******8000");
    assertThat(maskPhone("1234")).isEqualTo("****");
    assertThat(maskPhone("")).isEmpty();
    assertThat(maskPhone(null)).isEmpty();
  }

  @Test
  @DisplayName("16.6.1.1 电话脱敏：掩码值不含明文")
  void maskedPhoneNeverContainsPlain() {
    for (String plain : List.of("+1-555-0100", "13800138000", "+86-138-0013-8000")) {
      String masked = maskPhone(plain);
      assertThat(masked).isNotEqualTo(plain);
      assertThat(masked).doesNotContain(plain);
    }
  }

  // ================================================================ 邮箱

  @Test
  @DisplayName("16.6.1.1 邮箱脱敏：本地部分掩码、域名保留")
  void masksEmail() {
    // 🔴 本地部分 5 个字符 ⇒ 首字符保留 + 4 颗星。
    assertThat(maskEmail("alice@example.com")).isEqualTo("a****@example.com");
    assertThat(maskEmail("a@example.com")).isEqualTo("*@example.com");
    assertThat(maskEmail("")).isEmpty();
    assertThat(maskEmail(null)).isEmpty();
  }

  @Test
  @DisplayName("16.6.1.1 邮箱脱敏：掩码值不含明文")
  void maskedEmailNeverContainsPlain() {
    for (String plain : List.of("alice@example.com", "a@example.com", "buyer.shop@mail.co.uk")) {
      String masked = maskEmail(plain);
      assertThat(masked).isNotEqualTo(plain);
      assertThat(masked).doesNotContain(plain);
    }
  }

  @Test
  @DisplayName("16.6.1.1 邮箱脱敏：本地部分只有一个字符时整段掩码（否则等于没脱敏）")
  void singleCharLocalIsFullyMasked() {
    assertThat(maskEmail("a@example.com")).isNotEqualTo("a@example.com");
  }

  // ================================================================ 地址

  @Test
  @DisplayName("16.6.1.1 地址脱敏：保留到区级，街道与邮编掩码")
  void masksAddressToDistrictLevel() {
    String masked =
        maskAddress("US", "New York", "New York", "Manhattan", "10001", "1 Main St", "");
    assertThat(masked).contains("US").contains("New York").contains("Manhattan");
    assertThat(masked).as("街道必须被掩码").doesNotContain("1 Main St");
    assertThat(masked).as("邮编必须被掩码").doesNotContain("10001");
  }

  @Test
  @DisplayName("16.6.1.1 地址脱敏：掩码值不含街道原文")
  void maskedAddressNeverContainsStreet() {
    String masked =
        maskAddress("US", "NY", "New York", "Manhattan", "10001", "742 Evergreen Terrace", "");
    assertThat(masked).doesNotContain("742 Evergreen Terrace");
    assertThat(masked).doesNotContain("742");
  }

  // ================================================================ 主体标签

  @Test
  @DisplayName("16.6.1.1 主体标签用脱敏值（审计与清单里不留明文姓名）")
  void subjectLabelIsMasked() {
    assertThat(subjectLabel("order-1", "Alice Smith")).isEqualTo("A**** Smith");
    assertThat(subjectLabel("order-1", "")).isEqualTo("order-1");
  }

  // ================================================================ 授权判定

  private static GrantFact grant(String role, String field, String scope) {
    return new GrantFact(UUID.randomUUID(), role, field, scope, true);
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：没有对应角色的授权 ⇒ 拒绝，并说清是哪个字段")
  void deniesWithoutGrant() {
    Decision decision =
        decide(List.of(grant("SUPPORT", "NAME", SCOPE_ALL)), Set.of("OPS"), FIELD_NAME, false, null);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.grantId()).isNull();
    assertThat(decision.reason()).contains("没有授权配置").contains("姓名");
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：角色对了但字段没授权 ⇒ 拒绝")
  void deniesWhenFieldNotGranted() {
    Decision decision =
        decide(List.of(grant("SUPPORT", "NAME", SCOPE_ALL)), Set.of("SUPPORT"), FIELD_ADDRESS, false, null);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("地址");
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：ALL 范围放行，并说清依据哪条授权")
  void allowsAllScope() {
    GrantFact g = grant("SUPPORT", FIELD_ADDRESS, SCOPE_ALL);
    Decision decision = decide(List.of(g), Set.of("SUPPORT"), FIELD_ADDRESS, false, null);
    assertThat(decision.allowed()).isTrue();
    assertThat(decision.grantId()).isEqualTo(g.grantId());
    assertThat(decision.roleCode()).isEqualTo("SUPPORT");
    assertThat(decision.reason()).contains("SUPPORT");
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：DEPARTMENT 范围要求成员属于某个部门")
  void departmentScopeNeedsDepartment() {
    List<GrantFact> grants = List.of(grant("SUPPORT", FIELD_PHONE, SCOPE_DEPARTMENT));
    Decision denied = decide(grants, Set.of("SUPPORT"), FIELD_PHONE, false, null);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reason()).contains("部门");

    Decision allowed = decide(grants, Set.of("SUPPORT"), FIELD_PHONE, true, null);
    assertThat(allowed.allowed()).isTrue();
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：SELF 范围 —— 判不了（null）与不是本人（false）必须分得开")
  void selfScopeDistinguishesUnknownFromNo() {
    List<GrantFact> grants = List.of(grant("SUPPORT", FIELD_NAME, SCOPE_SELF));

    Decision unknown = decide(grants, Set.of("SUPPORT"), FIELD_NAME, false, null);
    assertThat(unknown.allowed()).as("判不了 ⇒ 必须拒绝，不是默认放行").isFalse();
    assertThat(unknown.reason()).contains("判不了");

    Decision notMine = decide(grants, Set.of("SUPPORT"), FIELD_NAME, false, Boolean.FALSE);
    assertThat(notMine.allowed()).isFalse();
    assertThat(notMine.reason()).contains("不是本人经手");

    Decision mine = decide(grants, Set.of("SUPPORT"), FIELD_NAME, false, Boolean.TRUE);
    assertThat(mine.allowed()).isTrue();
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：多条授权命中时取最宽松的一条（ALL > DEPARTMENT > SELF）")
  void picksMostPermissiveGrant() {
    GrantFact all = grant("SUPPORT", FIELD_PHONE, SCOPE_ALL);
    GrantFact self = grant("SUPPORT", FIELD_PHONE, SCOPE_SELF);
    Decision decision =
        decide(List.of(self, all), Set.of("SUPPORT"), FIELD_PHONE, false, Boolean.FALSE);
    assertThat(decision.allowed()).isTrue();
    assertThat(decision.grantId()).isEqualTo(all.grantId());
  }

  @Test
  @DisplayName("16.6.1.1 授权判定：生效期外 / 已撤销的授权不在候选里")
  void onlyActiveGrantsCount() {
    // 服务层只把「当前时刻生效」的授权喂进来；这里验证判定本身不会自己放宽。
    Decision decision =
        decide(List.of(), Set.of("SUPPORT"), FIELD_NAME, true, Boolean.TRUE);
    assertThat(decision.allowed()).isFalse();
  }

  // ================================================================ 摘要

  @Test
  @DisplayName("16.6.1.1 导出摘要：稳定、与顺序无关、且不是明文")
  void digestIsStableAndOrderIndependent() {
    String a = digest(List.of("order-2", "order-1"));
    String b = digest(List.of("order-1", "order-2"));
    assertThat(a).isEqualTo(b);
    assertThat(a).hasSize(32).matches("^[0-9a-f]{32}$");
    assertThat(a).doesNotContain("order-1");
    assertThat(digest(List.of("order-1"))).isNotEqualTo(a);
  }
}
