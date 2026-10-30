package com.starcon.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 18.1.2.1 签名密钥的**落库加密**与**签名计算**。
 *
 * <p>三条纪律：
 *
 * <ol>
 *   <li><b>密文落库</b>：密钥用 AES-256-GCM 加密后存 {@code platform_webhook_signing_secret.secret_ciphertext}，
 *       格式 {@code Base64(nonce(12B) || ciphertext || tag(16B))}。明文只在内存里短暂存在。
 *   <li><b>主密钥从已有配置派生，不新增部署变量</b>：主密钥 = {@code INTERNAL_KEY} 经
 *       <b>HKDF-SHA256</b>（RFC 5869）派生，info 串与其它用途隔离。
 *       <p>🔴 **为什么不新增一个必填 env**：新增必填环境变量会让**没配该变量的既有部署直接启动失败** ——
 *       一次密钥轮换不该让整个 platform 服务起不来。派生自 {@code INTERNAL_KEY} 则零部署改动。
 *   <li><b>签名是 HMAC-SHA256 且盖的是原始字节</b>：验签方要能自己算一遍，
 *       所以「算的是什么」必须写死在契约里，不能含糊。
 * </ol>
 *
 * <p>与 channel 的 {@code WebhookSignature} 同构（AES-256-GCM + 12 字节 nonce + 128 位 tag），
 * 差别是主密钥来源：channel 从 secret manager 注入一个独立变量，本服务用 HKDF 从
 * {@code INTERNAL_KEY} 派生。
 */
final class WebhookSigningSecretCipher {

  /** AES-GCM 固定 12 字节 nonce（GCM 推荐长度）。 */
  private static final int NONCE_BYTES = 12;

  /** 128 位认证标签。 */
  private static final int TAG_BITS = 128;

  /** HKDF 的 salt：固定串即可 —— 它的作用是域分离，不是保密。 */
  private static final byte[] HKDF_SALT =
      "starcon.platform.webhook.v1".getBytes(StandardCharsets.UTF_8);

  /** HKDF 的 info：与本服务其它密钥用途隔离，防止同一主密钥派生出可互换的密钥。 */
  private static final byte[] HKDF_INFO =
      "starcon.webhook.signing-key.v1".getBytes(StandardCharsets.UTF_8);

  private final SecretKeySpec key;

  WebhookSigningSecretCipher(String internalKey) {
    if (internalKey == null || internalKey.isBlank()) {
      throw new IllegalStateException("缺少 INTERNAL_KEY：无法派生签名密钥的主密钥");
    }
    this.key = new SecretKeySpec(derive(internalKey), "AES");
  }

  // ================================================================ 密钥派生

  /**
   * HKDF-SHA256（RFC 5869）：Extract + Expand。
   *
   * <p>输出 32 字节（AES-256）。SHA-256 的输出长度正好是 32 字节，
   * 所以 Expand 只需要 T(1) 一块。
   */
  private static byte[] derive(String internalKey) {
    byte[] ikm = internalKey.getBytes(StandardCharsets.UTF_8);
    byte[] prk = hmac(HKDF_SALT, ikm);
    return hmac(prk, concat(HKDF_INFO, (byte) 0x01));
  }

  private static byte[] hmac(byte[] keyBytes, byte[] data) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 不可用", e);
    }
  }

  private static byte[] concat(byte[] a, byte b) {
    var out = new byte[a.length + 1];
    System.arraycopy(a, 0, out, 0, a.length);
    out[a.length] = b;
    return out;
  }

  // ================================================================ 加解密

  /** 加密后落库：{@code Base64(nonce || ciphertext || tag)}。 */
  String seal(String plainSecret) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      new java.security.SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("签名密钥加密失败", e);
    }
  }

  /**
   * 解出密钥明文，**只用于算签名**。
   *
   * <p>调用方拿到的明文不允许回显、不允许落日志、不允许返回给客户端。
   */
  String open(String sealed) {
    try {
      byte[] raw = Base64.getDecoder().decode(sealed);
      if (raw.length <= NONCE_BYTES) throw new IllegalStateException("签名密钥密文长度异常");
      byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_BYTES);
      byte[] body = Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("签名密钥解密失败", e);
    }
  }

  // ================================================================ 签名

  /**
   * 计算签名。
   *
   * <p><b>签的是「时间戳 + 点 + 原始字节」</b>：
   *
   * <ul>
   *   <li>带时间戳是为了让验签方能**拒绝重放**（超出时间窗的签名一律不认）。
   *   <li>盖**原始字节**而不是重新序列化后的 JSON —— 平台签的是它发出去的那串字节，
   *       对方重新序列化再算必然对不上。
   * </ul>
   *
   * @param secret 密钥明文（仅在内存中短暂存在）
   * @param timestamp 时间戳文本（与签名头里的一致）
   * @param payload 原始报文字节
   * @return 十六进制小写的 HMAC-SHA256
   */
  static String sign(String secret, String timestamp, byte[] payload) {
    var macInput = new byte[timestamp.getBytes(StandardCharsets.UTF_8).length + 1 + payload.length];
    var ts = timestamp.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(ts, 0, macInput, 0, ts.length);
    macInput[ts.length] = (byte) '.';
    System.arraycopy(payload, 0, macInput, ts.length + 1, payload.length);
    return java.util.HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), macInput));
  }

  /** 常数时间比对（验签侧用；本模块的试算端点也会用它自检）。 */
  static boolean matches(String expected, String presented) {
    if (presented == null || presented.isBlank()) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.trim().getBytes(StandardCharsets.UTF_8));
  }
}
