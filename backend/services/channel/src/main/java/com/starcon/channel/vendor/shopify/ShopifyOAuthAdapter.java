package com.starcon.channel.vendor.shopify;

import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.AccountPayload;
import com.starcon.channel.vendor.OAuthProtocol.AuthorizeRequest;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.OAuthProtocol.PlatformShop;
import com.starcon.channel.vendor.OAuthProtocol.TokenRequest;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Shopify OAuth。
 *
 * <p>这个平台和其他平台有三处结构性差异，都必须由适配器兜住：
 *
 * <ol>
 *   <li>授权与换令牌都在<b>店铺自己的域名</b>下（{@code {shop}.myshopify.com}），不是平台统一域名；
 *   <li>scope 用<b>逗号</b>分隔；
 *   <li>换令牌是 <b>JSON</b> 请求，且响应里已经带出店铺信息，不需要再查账号接口。
 * </ol>
 *
 * <p>令牌是长期有效的（Shopify 不返回过期时间），因此 2.2.2.2 的到期提醒对它不触发， 失效只可能来自店铺在后台主动解除授权 —— 这种情况由 2.2.2.1 健康诊断读
 * last_error 暴露。
 */
@Component
public class ShopifyOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "SHOPIFY";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public boolean requiresShopDomain() {
    return true;
  }

  @Override
  public String rejectAuthorize(OAuthProfile profile, AuthorizeRequest request) {
    // 店铺域名是拼 URL 的前提，缺了它就没有任何合法跳转可给
    if (request.loginHint().isBlank())
      return "请先填写店铺域名（例如 my-store.myshopify.com），Shopify 的授权在店铺自己的域名下完成";
    if (!request.loginHint().endsWith(".myshopify.com")) return "店铺域名应以 .myshopify.com 结尾";
    return null;
  }

  @Override
  public URI authorizeUri(OAuthProfile profile, AuthorizeRequest request) {
    return uri(
        withShopDomain(profile, request.loginHint()).authorizeUrl(),
        authorizeParams(profile, request));
  }

  @Override
  public com.starcon.channel.vendor.OAuthProtocol.Authorization authorize(
      OAuthProfile profile, TokenRequest request) {
    // token_url 同样带 {shop_domain} 占位符，换令牌前必须先把店铺域名填进去
    OAuthProfile resolved = withShopDomain(profile, request.loginHint());
    return super.authorize(
        resolved,
        new TokenRequest(
            resolved.tokenUrl(),
            request.style(),
            request.authMethod(),
            request.clientId(),
            request.clientSecret(),
            request.code(),
            request.redirectUri(),
            request.codeVerifier(),
            request.loginHint(),
            request.extra()));
  }

  @Override
  public AccountPayload readAccount(OAuthProfile profile, String body) {
    // 换令牌响应本身就是账号信息：access_token 之外的字段就是店铺档案
    AccountPayload payload = super.readAccount(profile, body);
    if (payload.shops().isEmpty())
      return new AccountPayload(
          payload.accountId(),
          payload.accountName(),
          List.of(new PlatformShop(payload.accountId(), payload.accountName(), "")));
    return payload;
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("read_orders", "read_products");
  }

  /** 把 {@code {shop_domain}} 占位符换成实际店铺域名。 */
  private static OAuthProfile withShopDomain(OAuthProfile profile, String shopDomain) {
    String domain = shopDomain.isBlank() ? "" : shopDomain;
    return new OAuthProfile(
        profile.platformCode(),
        profile.authorizeUrl().replace("{shop_domain}", domain),
        profile.tokenUrl().replace("{shop_domain}", domain),
        profile.accountInfoUrl().replace("{shop_domain}", domain),
        profile.scopeParam(),
        profile.loginHintParam(),
        profile.accountPromptParam(),
        profile.accountPromptValue(),
        profile.tokenAuthMethod(),
        profile.tokenRequestStyle(),
        profile.codeChallengeMethod(),
        profile.defaultScopes(),
        profile.tokenAccessField(),
        profile.tokenRefreshField(),
        profile.tokenExpiresField(),
        profile.tokenScopeField(),
        profile.accountIdField(),
        profile.accountNameField(),
        profile.shopsField(),
        profile.shopIdField(),
        profile.shopNameField(),
        profile.shopMarketplaceField(),
        profile.extraAuthorizeParams());
  }
}
