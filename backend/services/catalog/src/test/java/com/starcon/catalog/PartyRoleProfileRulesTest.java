package com.starcon.catalog;

import static com.starcon.catalog.PartyRoleProfileRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.6.1.2 角色档案的纯规则离线单测。
 *
 * <p>与 {@code PartyRulesTest}（21 例）同一纪律：能在保存前判定的规则必须有
 * 可以穷举的单测，因为集成测试里每加一条规则就多一轮 Testcontainers 启动。
 *
 * <p>本文件<b>刻意</b>钉死四组断言，它们是「两处各说一套」最容易漂的地方：
 * R1 的双向拒绝、R2 的额度零非法、R4 的风险客户、R3 的缺身份文案。
 */
class PartyRoleProfileRulesTest {

  // ================================================================ R1 结算方式与账期

  @Test
  void 账期结算必须大于零天() {
    assertThat(settlementConsistencyProblem("CREDIT", 0)).contains("账期必须大于 0");
    assertThat(settlementConsistencyProblem("CREDIT", 1)).isNull();
    assertThat(settlementConsistencyProblem("CREDIT", 365)).isNull();
  }

  @Test
  void 非账期结算一律零天() {
    // 双向：账期结算 +0 被拒，非账期结算 +非0 也被拒
    for (String method : List.of("PREPAID", "ON_DELIVERY", "LETTER_OF_CREDIT")) {
      assertThat(settlementConsistencyProblem(method, 0)).as(method).isNull();
      assertThat(settlementConsistencyProblem(method, 90)).as(method).contains("不占用账期");
    }
  }

  @Test
  void 账期取值范围() {
    assertThat(paymentTermsProblem(0)).isNull();
    assertThat(paymentTermsProblem(365)).isNull();
    assertThat(paymentTermsProblem(-1)).contains("负数");
    assertThat(paymentTermsProblem(366)).contains("365");
    assertThat(paymentTermsProblem(null)).isNull();
    // null 归一成 0 = 不给账期，不是漏填
    assertThat(normalizePaymentTerms(null)).isZero();
    assertThat(normalizePaymentTerms(30)).isEqualTo(30);
  }

  @Test
  void 结算方式与合作等级词表() {
    assertThat(settlementMethodProblem("credit")).as("小写被规范化").isNull();
    assertThat(settlementMethodProblem("CASH")).contains("只能是");
    assertThat(cooperationLevelProblem("strategic")).isNull();
    assertThat(cooperationLevelProblem("PLATINUM")).contains("只能是");
    // 空串归一到 DB 的 DEFAULT，而不是变成 null
    assertThat(normalizeSettlementMethod(null)).isEqualTo("PREPAID");
    assertThat(normalizeSettlementMethod("  ")).isEqualTo("PREPAID");
    assertThat(normalizeCooperationLevel(null)).isEqualTo("STANDARD");
  }

  // ================================================================ R2 信用额度与币种

  @Test
  void 额度零与负数都非法() {
    // 「额度为 0」与「没设额度」在业务上是相反的两种默认，绝不能让它们长得一样
    assertThat(creditLimitProblem(BigDecimal.ZERO)).contains("不限制赊账");
    assertThat(creditLimitProblem(new BigDecimal("-1"))).contains("大于 0");
    assertThat(creditLimitProblem(new BigDecimal("0.01"))).isNull();
    assertThat(creditLimitProblem(new BigDecimal("100000.0000"))).isNull();
    // 没设额度是合法且有意义的"没有"
    assertThat(creditLimitProblem(null)).isNull();
  }

  @Test
  void 额度精度上限四位小数() {
    assertThat(creditLimitProblem(new BigDecimal("1.00001"))).contains("4 位小数");
    assertThat(creditLimitProblem(new BigDecimal("1.0000"))).isNull();
    assertThat(creditLimitProblem(new BigDecimal("123456789012345.99"))).isNull();
    assertThat(creditLimitProblem(new BigDecimal("1234567890123456.99"))).contains("整数部分太长");
  }

  @Test
  void 有额度必须有币种反之不成立() {
    assertThat(creditLimitCurrencyProblem(new BigDecimal("100"), null)).contains("就必须填默认币种");
    assertThat(creditLimitCurrencyProblem(new BigDecimal("100"), "USD")).isNull();
    // 没钱种没额度：正常（先建档，钱种等第一笔交易才知道）
    assertThat(creditLimitCurrencyProblem(null, null)).isNull();
    assertThat(currencyProblem("usd")).isNull();
    assertThat(currencyProblem("US")).contains("ISO 4217");
    assertThat(currencyProblem("USDD")).contains("ISO 4217");
    assertThat(normalizeCurrency(" usd ")).isEqualTo("USD");
    assertThat(normalizeCurrency("  ")).isNull();
  }

  // ================================================================ R4 风险客户

  @Test
  void 风险客户不能留额度() {
    assertThat(riskCustomerCreditProblem("RISK", new BigDecimal("1"))).contains("风险客户");
    // 反向不拦：普通客户有额度是正常业务
    assertThat(riskCustomerCreditProblem("STANDARD", new BigDecimal("1"))).isNull();
    assertThat(riskCustomerCreditProblem("RISK", null)).isNull();
  }

  @Test
  void 客户分级词表与归一() {
    assertThat(customerLevelProblem("prospect")).isNull();
    assertThat(customerLevelProblem("VIP")).contains("只能是");
    assertThat(normalizeCustomerLevel(null)).isEqualTo("STANDARD");
    assertThat(customerLevelLabel("STRATEGIC")).isEqualTo("战略客户");
    assertThat(customerLevelLabel("RISK")).isEqualTo("风险客户");
  }

  // ================================================================ MOQ

  @Test
  void MOQ数量与单位成对() {
    assertThat(moqProblem(new BigDecimal("100"), null)).contains("就必须填采购单位");
    assertThat(moqProblem(null, "PCS")).contains("就必须填最小起订量");
    assertThat(moqProblem(new BigDecimal("100"), "PCS")).isNull();
    assertThat(moqProblem(null, null)).isNull();
    assertThat(moqProblem(BigDecimal.ZERO, "PCS")).contains("大于 0");
    // 单位代码被规范化（大小写不敏感）
    assertThat(moqProblem(new BigDecimal("100"), " pcs ")).isNull();
  }

  @Test
  void MOQ精度上限六位小数且归一() {
    assertThat(moqProblem(new BigDecimal("1.1234567"), "PCS")).contains("6 位小数");
    assertThat(moqProblem(new BigDecimal("1.123456"), "PCS")).isNull();
    assertThat(normalizeQuantity(new BigDecimal("1.0000001"), MOQ_SCALE)).isEqualByComparingTo("1.000000");
    assertThat(normalizeQuantity(null, MOQ_SCALE)).isNull();
    // 归一到 0 视为"没设"，而不是把 0 存进库（0 在业务上是"不设起订量"的意思）
    assertThat(normalizeQuantity(new BigDecimal("0.0000001"), MOQ_SCALE)).isNull();
  }

  // ================================================================ 承运商

  @Test
  void 账号标识必填() {
    assertThat(accountRefProblem(null)).contains("不能为空");
    assertThat(accountRefProblem("   ")).contains("不能为空");
    assertThat(accountRefProblem("UPS-12345")).isNull();
    assertThat(accountRefProblem("X".repeat(129))).contains("128");
  }

  @Test
  void 服务能力大小写与去重() {
    // 小写会被规范化 —— 存成 'sea' 会在 @> ARRAY['SEA'] 的查询里静默不匹配
    assertThat(normalizeCapabilities(List.of("sea", "Air", "SEA"))).containsExactly("AIR", "SEA");
    // 固定顺序（不随输入顺序抖动）
    assertThat(normalizeCapabilities(List.of("URGENT", "AIR", "CUSTOMS")))
        .containsExactly("AIR", "CUSTOMS", "URGENT");
    assertThat(normalizeCapabilities(java.util.Arrays.asList(" sea ", "SEA", null, ""))).containsExactly("SEA");
    assertThat(normalizeCapabilities(null)).isEmpty();
  }

  @Test
  void 词表外的能力被拒且给出可用值() {
    String problem = serviceCapabilitiesProblem(List.of("SEA", "RAIL_FREIGHT"));
    assertThat(problem).contains("不在词表里").contains("海运").contains("加急");
    assertThat(serviceCapabilitiesProblem(List.of("SEA", "DOOR_TO_DOOR"))).isNull();
    assertThat(serviceCapabilitiesProblem(null)).isNull();
    // 大小写不同的同义词不算词表外（已被规范化）
    assertThat(serviceCapabilitiesProblem(List.of("door_to_door"))).isNull();
  }

  @Test
  void 数组字面量转义单引号与双引号() {
    assertThat(toPgArray(List.of())).isEqualTo("{}");
    assertThat(toPgArray(List.of("SEA"))).isEqualTo("{\"SEA\"}");
    assertThat(toPgArray(List.of("AIR", "SEA"))).isEqualTo("{\"AIR\",\"SEA\"}");
    // 用户输入里的引号被转义 —— 拼进 SQL 的数组字面量不能成为注入通道
    // 规范化会先大写整串（词表值本来就是大写），引号转义依然生效
    assertThat(toPgArray(List.of("SEA\"; drop table party; --")))
        .isEqualTo("{\"SEA\"\"; DROP TABLE PARTY; --\"}");
  }

  @Test
  void 接口类型词表与标签() {
    assertThat(interfaceTypeProblem("sftp_file")).isNull();
    assertThat(interfaceTypeProblem("EDI")).contains("只能是");
    assertThat(normalizeInterfaceType(null)).isEqualTo("API");
    assertThat(interfaceTypeLabel("SFTP_FILE")).isEqualTo("文件交换");
    assertThat(interfaceTypeLabel("EMAIL")).isEqualTo("邮件往来");
  }

  @Test
  void 能力摘要为空时给出可读文案() {
    assertThat(capabilitySummary(List.of())).isEqualTo("未登记服务能力");
    assertThat(capabilitySummary(null)).isEqualTo("未登记服务能力");
    assertThat(capabilitySummary(List.of("SEA", "DOOR_TO_DOOR"))).isEqualTo("海运、门到门");
    assertThat(capabilityLabel("INSURED")).isEqualTo("保价");
    assertThat(capabilityLabel("URGENT")).isEqualTo("加急");
  }

  // ================================================================ R3 角色对应

  @Test
  void 档案类型词表与角色映射() {
    assertThat(profileKindProblem("supplier")).isNull();
    assertThat(profileKindProblem("VENDOR")).contains("只能是");
    assertThat(profileKindProblem(" ")).contains("不能为空");
    assertThat(roleTypeFor("SUPPLIER")).isEqualTo("SUPPLIER");
    assertThat(roleTypeFor("CUSTOMER")).isEqualTo("CUSTOMER");
    assertThat(roleTypeFor("CARRIER")).isEqualTo("CARRIER");
    assertThat(roleTypeFor("VENDOR")).isNull();
  }

  @Test
  void 缺身份时提示先指派身份而不是档案不存在() {
    assertThat(roleMissingProblem("SUPPLIER", List.of("SUPPLIER"))).isNull();
    // 传的是 code 不是中文标签 —— 拿标签去比 role_type 列会永远不相等，于是所有保存都被拒
    String problem = roleMissingProblem("CUSTOMER", List.of("SUPPLIER", "CARRIER"));
    assertThat(problem).contains("客户").contains("先指派角色").contains("已指派：供应商、承运商");
    // 空集合（没查或没指派）
    assertThat(roleMissingProblem("CARRIER", List.of())).contains("承运商").contains("一个角色都没有");
    assertThat(roleMissingProblem("SUPPLIER", null)).contains("供应商");
  }

  // ================================================================ 选项与标签

  @Test
  void 选项按业务权重排序而不是字母序() {
    // 战略在前：运营最常选的在最上面
    assertThat(cooperationLevelOptions().getFirst().code()).isEqualTo("STRATEGIC");
    assertThat(settlementMethodOptions()).hasSize(4);
    assertThat(customerLevelOptions()).hasSize(5);
    assertThat(interfaceTypeOptions()).hasSize(4);
    // 能力按词表顺序（空运 → 海运 → …），不是字母序
    assertThat(capabilityOptions().getFirst().code()).isEqualTo("AIR");
    assertThat(capabilityOptions()).hasSize(9);
  }

  @Test
  void 标签与词表一一对应不漏不重() {
    assertThat(cooperationLevelOptions()).hasSize(COOPERATION_LEVELS.size());
    assertThat(settlementMethodOptions()).hasSize(SETTLEMENT_METHODS.size());
    assertThat(customerLevelOptions()).hasSize(CUSTOMER_LEVELS.size());
    assertThat(interfaceTypeOptions()).hasSize(INTERFACE_TYPES.size());
    assertThat(capabilityOptions()).hasSize(SERVICE_CAPABILITIES.size());
    // 每个标签都不是空串（界面上出现一个空白下拉项是最难查的那种 bug）
    for (var option : capabilityOptions()) assertThat(option.label()).isNotBlank();
    for (var option : cooperationLevelOptions()) assertThat(option.label()).isNotBlank();
  }

  @Test
  void 分级标签() {
    assertThat(cooperationLevelLabel("PREFERRED")).isEqualTo("优选供应商");
    assertThat(cooperationLevelLabel("TRIAL")).isEqualTo("试单供应商");
    assertThat(settlementMethodLabel("CREDIT")).isEqualTo("账期结算");
    assertThat(settlementMethodLabel("LETTER_OF_CREDIT")).isEqualTo("信用证");
    // 未知值原样返回（不要给空白，也不要静默变成"未知"）
    assertThat(settlementMethodLabel("WHATEVER")).isEqualTo("WHATEVER");
    assertThat(cooperationLevelLabel(null)).isEmpty();
  }
}