package com.starcon.channel.vendor;

import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;

/**
 * 平台侧协议失败（凭据错、权限不足、授权被拒、响应解析不了）。
 *
 * <p>刻意不携带原始响应体：换令牌失败时平台常把 client_secret 的校验细节回显在 body 里，
 * 让异常把这些内容带进日志就等于把凭据写进了日志。授权服务只需要 {@link #failure()} 里的
 * 分类与可执行文案。
 */
public class PlatformException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient OAuthFailure failure;

  public PlatformException(OAuthFailure failure) {
    super(failure.code() + ": " + failure.message());
    this.failure = failure;
  }

  public OAuthFailure failure() {
    return failure;
  }
}
