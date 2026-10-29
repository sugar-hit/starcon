package com.starcon.identity;

import java.util.List;

/**
 * 外部渠道凭据的**引用解析**端口。
 *
 * <p>蓝图原文：「外部渠道 Webhook 地址（**存密钥管理器引用**）」「**凭据不落库**，
 * 只存 secret manager 引用（见 18 集成系统）」。
 *
 * <p>本接口是那条纪律的**边界**：渠道登记时只落一个引用串，
 * 要拿凭据本体时**必须**过这个端口 —— 而这个端口今天**没有可用实现**。
 *
 * <p>🔴 <b>解析出来的凭据绝不落库</b>：它只在一次投递尝试的内存里流转，
 * 用完即弃。本仓 V385 的表里**没有任何一列**能装下它。
 */
public interface SecretReferencePort {

  /**
   * secret manager 是否已接通。
   *
   * <p>没接通时能力声明里如实报 {@code available=false}，
   * 并写清「归 18 系统负责」—— 不假装能解析。
   */
  boolean available();

  /**
   * 逐条解析结果（给能力声明用：哪个渠道的凭据能解析、哪个不能）。
   *
   * @param channelCode 渠道码
   */
  record Availability(String channelCode, boolean resolved, String reason, String ownerModule) {}

  /** 逐渠道的解析可用性。 */
  List<Availability> availability();

  /**
   * 解析一个引用。
   *
   * @param secretRef secret manager 引用串（**不是**凭据本体）
   * @return 解析结果；解析不了时 {@code resolved=false} 且带原因
   */
  Resolution resolve(String secretRef);

  /**
   * 一次解析的结果。
   *
   * @param resolved 解析成功与否
   * @param secret 凭据本体（**只在内存里流转，绝不落库**；解析失败时为 null）
   * @param reason 解析失败时的原因（成功时为 null）
   * @param ownerModule 缺的那块归谁
   */
  record Resolution(boolean resolved, String secret, String reason, String ownerModule) {}
}
