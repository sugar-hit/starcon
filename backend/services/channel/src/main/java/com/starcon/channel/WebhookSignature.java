package com.starcon.channel;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 02.6.1.1 验签与密钥落库。
 *
 * <p>两条纪律都在这里落地：
 *
 * <ol>
 *   <li>**验签是真算的**。签名用 HMAC-SHA256 对**原始字节**计算，与平台请求头做
 *       常数时间比对。没有"默认通过"的分支——没有签名就是 {@code MISSING}，
 *       算出来不一样就是 {@code FAILED}。
 *   <li>**比对必须是常数时间**。用 {@code MessageDigest.isEqual} 而不是 {@code
 *       String.equals}：后者按字符短路，逐字节试出来签名的攻击成本极低。
 *   <li>**密钥明文不入库**。签名密钥用 AES-256-GCM 加密后落 {@code signing_secret_enc}，
 *       主密钥从环境注入（secret manager）。读出来解密只在内存里短暂存在，
 *       与 2.2.1.3「令牌明文不入库」同一纪律，但方向相反：验签**必须**能读回密钥，
 *       否则无法验签——这就是接收侧密钥与 OAuth 令牌的本质区别。
 * </ol>
 */
final class WebhookSignature {

  /** AES-GCM 固定 12 字节 nonce（GCM 推荐长度）。 */
  private static final int NONCE_BYTES = 12;

  /** 128 位认证标签。 */
  private static final int TAG_BITS = 128;

  private WebhookSignature() {}

  /** 闸门计算的签名，Base64(nonce|ciphertext)。 */
  static String seal(String signingKeyBase64, String plainSecret) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      new java.security.SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, keyOf(signingKeyBase64), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("签名密钥加密失败", e);
    }
  }

  /** 取出密钥明文用于验签。只在内存短暂存在，不回显、不落日志。 */
  static String open(String signingKeyBase64, String sealed) {
    try {
      byte[] raw = Base64.getDecoder().decode(sealed);
      if (raw.length <= NONCE_BYTES)
        throw new IllegalStateException("签名密钥密文长度异常");
      byte[] nonce = java.util.Arrays.copyOfRange(raw, 0, NONCE_BYTES);
      byte[] body = java.util.Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, keyOf(signingKeyBase64), new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("签名密钥解密失败", e);
    }
  }

  /**
   * 用真实 HMAC-SHA256 覆盖原始报文算签名。
   *
   * <p>覆盖的是**字节**而不是 JSON 重排后的结果：平台签的是它发出去的那串字节，
   * 我们重新序列化再算必然对不上。
   */
  static String sign(String secret, byte[] payload, String encoding) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] raw = mac.doFinal(payload);
      return "BASE64".equalsIgnoreCase(encoding)
          ? Base64.getEncoder().encodeToString(raw)
          : HexFormat.of().formatHex(raw);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("签名计算失败", e);
    }
  }

  /**
   * 常数时间比对。
   *
   * <p>先按编码解成同样长度的字节再比：HEX 与 BASE64 的表示长度不同，
   * 直接比字符串会把"编码用错"错判成"签名不匹配"，排查时会往错方向找。
   * 长度不同即不匹配，但长度本身不泄漏秘密内容。
   */
  static boolean matches(String expected, String presented, String encoding) {
    if (presented == null || presented.isBlank()) return false;
    byte[] a;
    byte[] b;
    try {
      a = "BASE64".equalsIgnoreCase(encoding)
          ? Base64.getDecoder().decode(expected)
          : HexFormat.of().parseHex(expected);
      b = "BASE64".equalsIgnoreCase(encoding)
          ? Base64.getDecoder().decode(presented.trim())
          : HexFormat.of().parseHex(presented.trim());
    } catch (IllegalArgumentException e) {
      // 编码不是合法的 HEX/BASE64：请求头被截断或串了别的协议，按验签失败处理
      return false;
    }
    return MessageDigest.isEqual(a, b);
  }

  /** 主密钥必须够长：短密钥会让 AES 直接抛错，而不是悄悄降级。 */
  private static SecretKeySpec keyOf(String base64) {
    byte[] key = Base64.getDecoder().decode(base64);
    if (key.length != 32) throw new IllegalStateException("签名主密钥必须是 32 字节（AES-256）");
    return new SecretKeySpec(key, "AES");
  }
}
