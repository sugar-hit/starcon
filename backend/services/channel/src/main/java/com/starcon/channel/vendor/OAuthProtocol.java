package com.starcon.channel.vendor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 2.2.1.2 授权向导的平台 OAuth 协议模型。
 *
 * <p>按 AGENTS.md 的模块边界纪律，外部适配器放在 {@code com.starcon.channel.vendor} 下、 每个 vendor 独立包、只暴露 {@link
 * PlatformOAuthAdapter} 统一接口。这里的类型是那条接口 的词汇表：端点与字段名来自配置表 {@code platform_oauth_profile}，行为差异在适配器里，
 * service 只认这些结构，永远不认某个具体平台的字段名。
 *
 * <p>令牌明文只经由 {@link Authorization} 一条路径出适配器，且必须直接进密钥管理器； 任何面向 API 的视图类型都不具备承载它的字段。
 */
public final class OAuthProtocol {
  private OAuthProtocol() {}

  /** 平台 OAuth 端点与字段名配置，对应 {@code platform_oauth_profile} 一行。 */
  public record OAuthProfile(
      String platformCode,
      String authorizeUrl,
      String tokenUrl,
      String accountInfoUrl,
      String scopeParam,
      String loginHintParam,
      String accountPromptParam,
      String accountPromptValue,
      TokenAuthMethod tokenAuthMethod,
      TokenRequestStyle tokenRequestStyle,
      CodeChallengeMethod codeChallengeMethod,
      String defaultScopes,
      String tokenAccessField,
      String tokenRefreshField,
      String tokenExpiresField,
      String tokenScopeField,
      String accountIdField,
      String accountNameField,
      String shopsField,
      String shopIdField,
      String shopNameField,
      String shopMarketplaceField,
      Map<String, String> extraAuthorizeParams) {}

  /** client 凭据怎么带给 token 端点。 */
  public enum TokenAuthMethod {
    /** HTTP Basic：Authorization 头带 base64(client_id:client_secret)。 */
    CLIENT_SECRET_BASIC,
    /** 请求体里带 client_id / client_secret。 */
    CLIENT_SECRET_POST
  }

  /** token 端点的请求体格式。 */
  public enum TokenRequestStyle {
    /** application/x-www-form-urlencoded。 */
    FORM,
    /** application/json。 */
    JSON
  }

  /** PKCE 支持。NONE 表示该平台不需要 code_verifier。 */
  public enum CodeChallengeMethod {
    NONE,
    S256
  }

  /** 发起授权所需的全部参数，授权码本身不经过这里。 */
  public record AuthorizeRequest(
      String clientId,
      String redirectUri,
      String state,
      String loginHint,
      List<String> scopes,
      String codeChallenge,
      CodeChallengeMethod codeChallengeMethod) {}

  /** 换令牌所需的参数。{@code loginHint} 随请求带下去，供把 URL 拼在店铺域名下的平台使用。 */
  public record TokenRequest(
      String tokenUrl,
      TokenRequestStyle style,
      TokenAuthMethod authMethod,
      String clientId,
      String clientSecret,
      String code,
      String redirectUri,
      String codeVerifier,
      String loginHint,
      Map<String, String> extra) {}

  /**
   * 换令牌 + 读账号信息的一次性结果。
   *
   * <p>刻意把两者合成一个返回值：令牌只在这一次调用里活着，调用方拿到后必须立刻写进密钥管理器。 如果拆成"换令牌结果"和"再读账号信息"两个调用，令牌就会横跨两次调用留在字段里，
   * 中间任何一次异常或日志输出都可能把它带出去。合成一次之后，令牌的存活期就等于这次方法调用。
   *
   * <p>没有任何视图、审计事件或日志会读取 {@link #accessToken()} / {@link #refreshToken()}： 落库的是密钥管理器引用，不是它们。
   */
  public record Authorization(
      String accessToken,
      String refreshToken,
      Instant expiresAt,
      List<String> grantedScopes,
      String accountId,
      String accountName,
      List<PlatformShop> shops) {}

  /** 平台账号下的一个店铺。 */
  public record PlatformShop(String externalShopId, String name, String marketplaceCode) {}

  /**
   * 账号信息解析结果。
   *
   * <p>{@code accountId} 是跨租户唯一的绑定主体：蓝图要求同一平台账号只能绑定一个租户， 判定就落在这个值上。{@code shops}
   * 为空表示该平台授权粒度就是账号本身（例如 Temu 全托管）， 向导会据此把账号直接当作一个店铺。
   */
  public record AccountPayload(String accountId, String accountName, List<PlatformShop> shops) {}

  /** 授权失败的机器可读分类与人读原因，两者都写进会话与审计。 */
  public record OAuthFailure(String code, String message) {}
}
