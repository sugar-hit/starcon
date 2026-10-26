package com.starcon.finance;

import java.util.Optional;

/**
 * 密钥管理器（**port**）—— 支付渠道凭据的**唯一**取法。
 *
 * <p>蓝图 12.2.2.2 的输入写死「支付渠道配置（<b>密钥引用</b>）」：
 * 本系统**只存引用**，明文由密钥管理器（Vault / K8s External Secrets）保管。
 *
 * <p>🔴 <b>纪律</b>：
 * <ul>
 *   <li>解析出来的明文**只在内存里短暂存在**：不进数据库、不进日志、不进请求摘要；
 *   <li>解析不到就返回 {@link Optional#empty()} —— 调用方据此报「渠道未配置」，
 *       **绝不**用空串或者假值去「凑」一次请求；
 *   <li>本接口**不提供**写入：写入凭据是渠道接入方的运维动作，不是本模块的职责。
 * </ul>
 */
public interface PaymentSecretPort {

  /**
   * 解析一条密钥引用。
   *
   * @param secretRef 形如 {@code vault://starcon/finance/paypal} 的引用
   * @return 明文；**解析不到时返回 empty**（不抛异常 —— 「没有凭据」是一个正常的业务状态）
   */
  Optional<String> resolve(String secretRef);
}
