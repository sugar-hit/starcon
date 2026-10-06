package com.starcon.channel.secrets;

/**
 * 2.2.1.3 令牌落点：密钥管理器。
 *
 * <p>2.2.1.2 / 2.2.1.3 的铁律是"令牌明文只在内存里短暂存在并直接写密钥管理器，数据库仅存引用"。
 * 这个接口就是那条边界的两侧：写入方法收明文并返回一个 {@code external_ref}，
 * 数据库只保存这个引用；读取方法在这里刻意<b>不提供</b>给 channel 的业务路径，
 * 因为 2.2.1.3 要求"绝不回显明文"——2.2.1.2 换完令牌就不需要再读它了。
 */
public interface SecretStore {

  /** 实现自己写入的存储位置，例如 {@code vault://starcon/channel/...}。 */
  String provider();

  /**
   * 写入令牌并返回引用。
   *
   * @param secretPath 逻辑路径，建议包含租户与平台，便于人工排查时定位
   * @param secret     明文令牌，仅在本次调用内存活
   */
  String write(String secretPath, String secret);

  /** 写同一路径两次应返回同一引用：重新授权是覆盖而不是产生第二份凭据。 */
  String reference(String secretPath);
}
