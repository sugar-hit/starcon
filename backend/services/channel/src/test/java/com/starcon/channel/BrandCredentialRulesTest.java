package com.starcon.channel;

import static com.starcon.channel.BrandCredentialRules.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * 16.4.2.2 授权凭证的规则层单测（纯函数，不碰数据库 / HTTP / 文件系统）。
 *
 * <p>🔴 这组用例守的是四件最容易做丢的事：
 *
 * <ol>
 *   <li><b>摘要形态钉死</b>：小写十六进制 64 位。写成大写会让比对静默失效，
 *       而「对不上」在界面上读起来是「文件是假的」—— 那是误伤。
 *   <li><b>「没核验过」≠「核验过但不通过」</b>：两个状态必须能区分开。
 *   <li><b>结论取最严重的那一条</b>，其余作为原因一并给出 ——
 *       只报最严重的会让运营以为「修好这一条就没事了」。
 *   <li><b>存储键不接受调用方原样透传的文件名</b>：文件名带路径分隔符就是越权。
 * </ol>
 */
class BrandCredentialRulesTest {

  static OffsetDateTime at(int days) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
  }

  // ==================================================================
  // 摘要
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 摘要是小写十六进制 64 位")
  void digestIsLowercaseHex() {
    String digest = sha256Hex("starcon".getBytes(StandardCharsets.UTF_8));
    assertThat(digest).hasSize(64).matches("^[0-9a-f]{64}$");
    assertThat(digest)
        .as("已知向量：sha256(\"starcon\")")
        .isEqualTo("cf0ef572c0cab5023dc6b2f03fd1d712b4da431656fee2c24995a7702e8cac4f");
  }

  @Test
  @DisplayName("16.4.2.2 同一份内容两次摘要逐字相等（核验的对照锚点必须稳定）")
  void digestIsStable() {
    byte[] content = "授权书原件".getBytes(StandardCharsets.UTF_8);
    assertThat(sha256Hex(content)).isEqualTo(sha256Hex(content.clone()));
  }

  @Test
  @DisplayName("16.4.2.2 内容变一个字节，摘要就变")
  void digestChangesWithContent() {
    byte[] a = "授权书原件".getBytes(StandardCharsets.UTF_8);
    byte[] b = "授权书原件!".getBytes(StandardCharsets.UTF_8);
    assertThat(sha256Hex(a)).isNotEqualTo(sha256Hex(b));
  }

  @Test
  @DisplayName("16.4.2.2 摘要形态校验：大写 / 长度不对 / 含非十六进制都拒")
  void digestShapeIsValidated() {
    assertThat(isDigest(sha256Hex("x".getBytes(StandardCharsets.UTF_8)))).isTrue();
    assertThat(isDigest(sha256Hex("x".getBytes(StandardCharsets.UTF_8)).toUpperCase())).isFalse();
    assertThat(isDigest("abc")).isFalse();
    assertThat(isDigest(null)).isFalse();
    assertThat(isDigest("z".repeat(64))).isFalse();
  }

  @Test
  @DisplayName("16.4.2.2 空内容算不出摘要（不是「空摘要」）")
  void emptyContentHasNoDigest() {
    assertThatThrownBy(() -> sha256Hex(null)).isInstanceOf(IllegalArgumentException.class);
  }

  // ==================================================================
  // 结论与分级
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 只有 VERIFIED 算通过，其余都不通过")
  void onlyVerifiedPasses() {
    assertThat(passed("VERIFIED")).isTrue();
    for (String conclusion :
        List.of(
            "DIGEST_MISMATCH",
            "FILE_MISSING",
            "EXPIRED",
            "NOT_YET_EFFECTIVE",
            "SIGNATORY_INCOMPLETE",
            "NO_DOCUMENT",
            "REVOKED")) {
      assertThat(passed(conclusion)).as(conclusion + " 不该算通过").isFalse();
      assertThat(severity(conclusion)).as(conclusion + " 的分级").isEqualTo("FAIL");
    }
    assertThat(severity("VERIFIED")).isEqualTo("PASS");
  }

  @Test
  @DisplayName("16.4.2.2 结论取最严重的那一条，其余原因一并保留")
  void mostSevereWins() {
    assertThat(mostSevere(List.of())).isEqualTo("VERIFIED");
    assertThat(mostSevere(List.of("VERIFIED"))).isEqualTo("VERIFIED");
    assertThat(mostSevere(List.of("EXPIRED", "DIGEST_MISMATCH"))).isEqualTo("DIGEST_MISMATCH");
    assertThat(mostSevere(List.of("EXPIRED", "NO_DOCUMENT"))).isEqualTo("NO_DOCUMENT");
    assertThat(mostSevere(List.of("SIGNATORY_INCOMPLETE", "REVOKED"))).isEqualTo("REVOKED");
    assertThat(mostSevere(List.of("FILE_MISSING", "NOT_YET_EFFECTIVE"))).isEqualTo("FILE_MISSING");
  }

  @Test
  @DisplayName("16.4.2.2 结论优先级单调（严重度排序固定）")
  void conclusionPriorityIsOrdered() {
    assertThat(conclusionPriority("REVOKED"))
        .isGreaterThan(conclusionPriority("NO_DOCUMENT"))
        .isGreaterThan(conclusionPriority("FILE_MISSING"))
        .isGreaterThan(conclusionPriority("DIGEST_MISMATCH"))
        .isGreaterThan(conclusionPriority("NOT_YET_EFFECTIVE"))
        .isGreaterThan(conclusionPriority("EXPIRED"))
        .isGreaterThan(conclusionPriority("SIGNATORY_INCOMPLETE"))
        .isGreaterThan(conclusionPriority("VERIFIED"));
  }

  @Test
  @DisplayName("16.4.2.2 🔴「没核验过」与「核验过但不通过」是两个状态")
  void unverifiedIsNotFailed() {
    assertThat(verificationStateLabel("UNVERIFIED")).isEqualTo("从未核验");
    assertThat(verificationStateLabel("FAILED")).isEqualTo("核验未通过");
    assertThat(verificationStateLabel("VERIFIED")).isEqualTo("核验通过");
    assertThat(verificationStateLabel("UNVERIFIED")).isNotEqualTo(verificationStateLabel("FAILED"));
  }

  @Test
  @DisplayName("16.4.2.2 签署方「身份未核验」是 WARN 不是 FAIL（否则一键核验天天红）")
  void unverifiedSignatoryIsWarnOnly() {
    assertThat(reasonSeverity("SIGNATORY_UNVERIFIED")).isEqualTo("WARN");
    assertThat(reasonSeverity("SIGNATORY_INCOMPLETE")).isEqualTo("FAIL");
    assertThat(reasonSeverity("DIGEST_MISMATCH")).isEqualTo("FAIL");
  }

  @Test
  @DisplayName("16.4.2.2 每个原因码都有人话标题")
  void everyReasonCodeHasLabel() {
    for (String code : REASON_CODES) {
      assertThat(reasonLabel(code)).as(code + " 的标题").isNotBlank();
    }
    assertThat(reasonLabel("UNKNOWN_CODE")).isNotBlank();
  }

  // ==================================================================
  // 到期状态
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 到期状态五个态各自算对（判据是数据库时钟）")
  void expiryStatesAreClassified() {
    OffsetDateTime now = at(0);
    // 🔴 10 天 < 30 天临期窗口 ⇒ 已经是「即将到期」，不是「生效中」
    assertThat(classifyExpiry("ACTIVE", at(-10), at(10), now, 30)).isEqualTo("EXPIRING");
    assertThat(classifyExpiry("ACTIVE", at(-10), at(90), now, 30)).isEqualTo("ACTIVE");
    assertThat(classifyExpiry("ACTIVE", at(-10), at(5), now, 30)).isEqualTo("EXPIRING");
    assertThat(classifyExpiry("ACTIVE", at(-10), at(-1), now, 30)).isEqualTo("EXPIRED");
    assertThat(classifyExpiry("ACTIVE", at(10), at(20), now, 30)).isEqualTo("NOT_YET_EFFECTIVE");
    assertThat(classifyExpiry("REVOKED", at(-10), at(10), now, 30)).isEqualTo("REVOKED");
  }

  @Test
  @DisplayName("16.4.2.2 撤销优先于一切（撤销就是撤销，不是「还没到期」）")
  void revokedBeatsEverything() {
    assertThat(classifyExpiry("REVOKED", at(10), at(-1), now0(), 30)).isEqualTo("REVOKED");
  }

  static OffsetDateTime now0() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("16.4.2.2 剩余天数向下取整：还剩 12 小时是 0 天，负数 = 已过期几天")
  void daysRemainingFloors() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThat(daysRemaining(now.plusDays(12), now)).isEqualTo(12);
    assertThat(daysRemaining(now.plusHours(12), now)).isEqualTo(0);
    assertThat(daysRemaining(now.minusDays(3), now)).isEqualTo(-3);
  }

  // ==================================================================
  // 存储键与文件名
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 存储键带租户段（一个租户算不出另一个租户的键）")
  void storageKeyCarriesTenant() {
    UUID tenant = UUID.randomUUID();
    UUID credential = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    String key = storageKey(tenant, credential, document, "授权书.pdf");
    assertThat(key).startsWith("credentials/" + tenant + "/");
    assertThat(key).contains(credential.toString());
    assertThat(key).endsWith(".pdf");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 文件名里的路径与控制字符被净化（不接受调用方原样透传）")
  void fileNameIsSanitized() {
    assertThat(sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(sanitizeFileName("C:\\windows\\evil.pdf")).isEqualTo("evil.pdf");
    assertThat(sanitizeFileName("")).isEqualTo("credential-file");
    assertThat(sanitizeFileName(null)).isEqualTo("credential-file");
    assertThat(sanitizeFileName("正常授权书.pdf")).isEqualTo("正常授权书.pdf");
  }

  @Test
  @DisplayName("16.4.2.2 存储键的扩展名只认净化后的小写字母数字")
  void storageKeyExtensionIsSanitized() {
    UUID tenant = UUID.randomUUID();
    UUID credential = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    // 目录部分被丢掉，只留净化后的扩展名
    assertThat(storageKey(tenant, credential, document, "a/b.pdf")).endsWith(".pdf").doesNotContain("a/b");
    // 扩展名含空格 / 非法字符 ⇒ 整个扩展名丢掉（uuid 里没有「.」，所以这个断言是确定的）
    assertThat(storageKey(tenant, credential, document, "x.ex e")).doesNotContain(".ex");
    // 没有扩展名 ⇒ 不加点
    assertThat(storageKey(tenant, credential, document, "x")).doesNotContain(".x");
    // 键的段数固定：credentials / 租户 / 凭证 / 文档（文件名**不进来**）
    assertThat(storageKey(tenant, credential, document, "a/b.pdf").split("/")).hasSize(4);
  }

  // ==================================================================
  // 词表
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 凭证类型规范化：去空白 + 转大写")
  void credentialTypeIsNormalized() {
    assertThat(normalizeCredentialType(" brand_license ")).isEqualTo("BRAND_LICENSE");
    assertThat(normalizeCredentialType(null)).isEmpty();
    assertThat(CREDENTIAL_TYPES).contains("BRAND_LICENSE", "OTHER");
  }

  @Test
  @DisplayName("16.4.2.2 结论词表与分级词表固定")
  void vocabulariesAreFixed() {
    assertThat(CONCLUSIONS).hasSize(8).contains("VERIFIED", "DIGEST_MISMATCH", "FILE_MISSING");
    assertThat(SEVERITIES).containsExactly("PASS", "FAIL");
    assertThat(VERIFICATION_STATES).containsExactly("UNVERIFIED", "VERIFIED", "FAILED");
    assertThat(EXPIRY_STATES).containsExactly("REVOKED", "NOT_YET_EFFECTIVE", "EXPIRED", "EXPIRING", "ACTIVE");
  }
}
