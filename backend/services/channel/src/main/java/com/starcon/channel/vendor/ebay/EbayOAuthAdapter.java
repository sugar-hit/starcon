package com.starcon.channel.vendor.ebay;

import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * eBay OAuth。
 *
 * <p>eBay 是这里唯一用 {@code prompt=select_account} 的平台：授权页会强制回到账号选择页，
 * 这正好对应 2.2.1.2 第 2 步「选账号」——用户不需要先在 starcon 里填账号，
 * 而是在跳转后的页面上挑"这一次要授权哪个卖家账号"。
 *
 * <p>{@code offline_access} 必须申请：没有它拿不到 refresh token，令牌 2 小时后到期就只能
 * 重新授权，跨境 ERP 的同步会直接断掉。
 */
@Component
public class EbayOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "EBAY";
  }

  @Override
  public String scopeSeparator() {
    return " ";
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_grant" ->
          new OAuthFailure("CREDENTIAL_REJECTED", "授权码已失效，请重新发起授权。");
      case "invalid_client" ->
          new OAuthFailure("CREDENTIAL_REJECTED", "eBay 拒绝了应用凭据（client_id / client_secret 或证书）不匹配，请联系管理员检查配置。");
      case "unauthorized_client" ->
          new OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此权限范围（RBAC），请在 eBay 开发者后台补齐后重试。");
      case "consent_declined" -> new OAuthFailure("PLATFORM_REJECTED", "你已拒绝授权。重新发起即可继续。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("offline_access", "https://api.ebay.com/oauth/api_scope/sell.fulfillment");
  }
}
