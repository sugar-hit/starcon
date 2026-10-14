package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 17.5.3.1 脱敏单元测试。
 *
 * <p>蓝图的硬要求是「审计不记录密码与完整客户地址；变更详情必须脱敏」。这里逐项证明 写入侧产出的值里不再含明文——集成测试再从数据库侧证明明文从未落库。
 */
class AuditRedactorTest {

  @Test
  void credentialFieldsAreReplacedWholesale() {
    // 凭证类：整值替换。连长度信息都不保留（bcrypt 前缀本身会泄露哈希强度）。
    assertThat(AuditRedactor.redact("password", "hunter2-correct-horse")).isEqualTo("***");
    assertThat(AuditRedactor.redact("passwordHash", "$2a$10$abcdefghijklmnopqrstuv"))
        .isEqualTo("***");
    assertThat(AuditRedactor.redact("apiKey", "sk-live-9f8e7d6c5b4a")).isEqualTo("***");
    assertThat(AuditRedactor.redact("accessToken", "eyJhbGciOiJIUzI1NiJ9.body.sig"))
        .isEqualTo("***");
    assertThat(AuditRedactor.redact("clientSecret", "s3cr3t")).isEqualTo("***");
    assertThat(AuditRedactor.redact("privateKey", "-----BEGIN RSA PRIVATE KEY-----"))
        .isEqualTo("***");
  }

  @Test
  void keyMatchingIgnoresCaseAndSeparators() {
    // 字段名归一化：大小写与分隔符不同但语义相同，必须判定为敏感。
    assertThat(AuditRedactor.redact("Password", "x")).isEqualTo("***");
    assertThat(AuditRedactor.redact("API_KEY", "x")).isEqualTo("***");
    assertThat(AuditRedactor.redact("api-key", "x")).isEqualTo("***");
    assertThat(AuditRedactor.redact("idCard", "x")).isEqualTo("***");
    assertThat(AuditRedactor.redact("bank_card", "x")).isEqualTo("***");
    assertThat(AuditRedactor.isSensitiveKey("shippingAddress")).isTrue();
    assertThat(AuditRedactor.isSensitiveKey("手机号")).isTrue();
  }

  @Test
  void contactAndIdentityFieldsAreMaskedNotDropped() {
    // 联系与证件类：保留头尾，既能看出「改过」又认不出原文。
    assertThat(AuditRedactor.redact("phone", "13800138000"))
        .isEqualTo("138" + AuditRedactor.MASK + "8000");
    assertThat(AuditRedactor.redact("email", "ops@example.com"))
        .isEqualTo("o" + AuditRedactor.MASK + "@example.com");
    assertThat(AuditRedactor.redact("address", "上海市浦东新区世纪大道100号2栋3层"))
        .isEqualTo(AuditRedactor.MASK);
    // 完整收货地址是蓝图的明文禁区。
    assertThat(AuditRedactor.redact("consigneeAddress", "Room 402, No.8 Jianguo Rd, Beijing"))
        .isEqualTo(AuditRedactor.MASK);
    assertThat(AuditRedactor.redact("idCard", "110101199003072316"))
        .isEqualTo("110" + AuditRedactor.MASK + "16");
    assertThat(AuditRedactor.redact("bankCard", "6222021234567890123"))
        .isEqualTo(AuditRedactor.MASK + "0123");
  }

  @Test
  void sensitiveShapesAreMaskedEvenUnderInnocuousFieldNames() {
    // 按值判定兜底：业务代码可能把收件地址塞进 remark / note 这类无害字段名。
    assertThat(String.valueOf(AuditRedactor.redact("remark", "联系我 13800138000")))
        .doesNotContain("13800138000");
    assertThat(String.valueOf(AuditRedactor.redact("note", "发票发到 ops@example.com")))
        .doesNotContain("ops@example.com");
    assertThat(String.valueOf(AuditRedactor.redact("description", "卡号 6222021234567890123")))
        .doesNotContain("6222021234567890123");
    assertThat(String.valueOf(AuditRedactor.redact("comment", "证件 110101199003072316")))
        .doesNotContain("110101199003072316");
  }

  @Test
  void ordinaryBusinessValuesSurviveRedaction() {
    // 脱敏不能把审计变成空壳：授权与状态类字段必须原样保留，否则事后无法解释变更。
    assertThat(AuditRedactor.redact("code", "EDITOR_7F3A")).isEqualTo("EDITOR_7F3A");
    assertThat(AuditRedactor.redact("status", "ACTIVE")).isEqualTo("ACTIVE");
    // 数字保持 JSON 数字类型：审计详情里 amountScale=2 不能变成字符串 "2"，
    // 否则前端做数值比较与排序时会静默出错。
    assertThat(AuditRedactor.redact("amountScale", 2)).isEqualTo(2);
    assertThat(AuditRedactor.redact("owner", true)).isEqualTo(true);
    assertThat(AuditRedactor.redact("permissions", "identity.role.update"))
        .isEqualTo("identity.role.update");
    assertThat(AuditRedactor.redact("baseCurrency", "USD")).isEqualTo("USD");
    assertThat(AuditRedactor.redact("code", null)).isNull();
  }

  @Test
  void redactAllMasksEveryFieldAndPreservesOrder() {
    var input = new LinkedHashMap<String, Object>();
    input.put("username", "auditor-01");
    input.put("password", "hunter2-correct-horse");
    input.put("phone", "13800138000");
    input.put("status", "ACTIVE");

    var redacted = AuditRedactor.redactAll(input);

    assertThat(redacted).containsEntry("username", "auditor-01");
    assertThat(redacted).containsEntry("status", "ACTIVE");
    assertThat(redacted).containsEntry("password", AuditRedactor.MASK);
    // 明文不在结果里的任何一个位置
    assertThat(redacted.values()).doesNotContain("hunter2-correct-horse", "13800138000");
    // 字段顺序保留，审计详情才能逐行对比 before/after
    assertThat(redacted.keySet()).containsExactly("username", "password", "phone", "status");
    // 新建没有 before：null 快照必须原样返回，不能变成空对象假装有值
    assertThat(AuditRedactor.redactAll(null)).isNull();
  }

  @Test
  void redactedOutputNeverContainsTheOriginalSecrets() {
    // 端到端断言：把各种敏感值混在一张快照里，结果整体不含任何一个原文。
    var snapshot = new LinkedHashMap<String, Object>();
    Map.of(
            "password", "P@ssw0rd!2024",
            "apiKey", "sk-live-abcdef123456",
            "email", "finance@corp.example.com",
            "bankCard", "6222021234567890123",
            "idCard", "310101198505054321",
            "address", "广东省深圳市南山区科技园南路18号")
        .forEach(snapshot::put);

    var rendered = String.valueOf(AuditRedactor.redactAll(snapshot));

    assertThat(rendered)
        .doesNotContain("P@ssw0rd!2024")
        .doesNotContain("sk-live-abcdef123456")
        .doesNotContain("finance@corp.example.com")
        .doesNotContain("6222021234567890123")
        .doesNotContain("310101198505054321")
        .doesNotContain("广东省深圳市南山区科技园南路18号");
  }
}
