package com.starcon.channel.vendor;

import com.starcon.channel.vendor.OAuthProtocol.AccountPayload;
import com.starcon.channel.vendor.OAuthProtocol.Authorization;
import com.starcon.channel.vendor.OAuthProtocol.AuthorizeRequest;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.OAuthProtocol.TokenRequest;
import java.net.URI;
import java.util.Collection;
import java.util.List;

/**
 * 2.2.1.2 平台适配器统一接口。
 *
 * <p>AGENTS.md 要求外部适配器放在 {@code com.starcon.channel.vendor} 下、每个 vendor 独立包、
 * 只暴露统一接口。这个接口就是那个"统一接口"：授权服务只跟它打交道，永远不写死任何平台的 URL、参数名或字段名。
 *
 * <p>分工：
 *
 * <ul>
 *   <li><b>配置</b>（{@code platform_oauth_profile} 表）放端点与字段名——这些是数据，换环境只改数据。
 *   <li><b>适配器</b>放行为差异——scope 用逗号还是空格、授权 URL 要不要拼店铺域名、 token 请求是表单还是 JSON、账号响应套了几层壳、平台错误码怎么翻译。
 * </ul>
 *
 * <p>所以新增一个平台通常只需要插一行配置；只有当新平台的协议行为超出下面这些方法能表达的 范围时，才需要新写一个 vendor 包。
 */
public interface PlatformOAuthAdapter {

  /** 平台码，与 {@code platform.code} 一致。注册表用它找到对应适配器。 */
  String platformCode();

  /** scope 分隔符：多数平台用空格，Shopify / TikTok Shop / Temu 用逗号。 */
  String scopeSeparator();

  /** 授权跳转是否需要店铺域名（Shopify 的授权与换令牌都在店铺自己的域名下）。 */
  default boolean requiresShopDomain() {
    return false;
  }

  /** 把本次授权需要的 scope 拼成平台约定的形态。 */
  default String joinScopes(Collection<String> scopes) {
    return scopes.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(String::trim)
        .reduce((a, b) -> a + scopeSeparator() + b)
        .orElse("");
  }

  /**
   * 授权跳转前的最后一道路闸，返回非 null 即为拒绝原因（可执行文案）。
   *
   * <p>默认放行。各 vendor 在这里挡掉自己协议上的硬性前置条件，例如 Shopify 缺店铺域名 就拼不出授权地址 —— 那时必须明确告诉用户"还差什么"，而不是跳到一个必然 404
   * 的地址。
   */
  default String rejectAuthorize(OAuthProfile profile, AuthorizeRequest request) {
    return null;
  }

  /** 拼出授权跳转 URL。端点来自配置，平台特有的拼法由适配器决定。 */
  URI authorizeUri(OAuthProfile profile, AuthorizeRequest request);

  /**
   * 换令牌并读出账号信息。
   *
   * <p>实现里绝对不允许把令牌写进日志或异常消息——{@link Authorization} 出去之后必须立刻进密钥管理器。
   * 各平台"账号信息是随令牌响应一起给，还是要另调一个接口"的差异也在这里消化。
   */
  Authorization authorize(OAuthProfile profile, TokenRequest request);

  /**
   * 解析账号信息。
   *
   * <p>账号 ID 缺失时必须抛异常而不是返回空串：账号 ID 是"同一平台账号只能绑定一个租户" 的判定依据，缺失时宁可直接失败，也不能让一个空账号名去建连接。
   */
  AccountPayload readAccount(OAuthProfile profile, String body);

  /** 平台授权页直接返回错误（用户拒绝授权、平台拒绝）时的可执行原因翻译。 */
  OAuthFailure translatePlatformError(String errorCode, String description);

  /** 本平台完成同步所必需的最小 scope；授予范围缺这些就是"权限不足"。 */
  List<String> minimumScopes();
}
