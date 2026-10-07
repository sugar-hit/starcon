package com.starcon.channel.vendor.walmart;

import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Walmart Marketplace 授权。
 *
 * <p>Walmart 的 OAuth 有一个和其他平台都不同的点：<b>令牌按店铺签发</b>，
 * 也就是说一次授权只对应一个店铺，用户在授权页选的店铺就是这次授权的店铺。
 * 适配器把店铺列表补成一个元素，向导第 5 步因此仍然是"确认这一个店铺"，
 * 而不是给用户看一个必然为空的列表。
 *
 * <p>该平台没有 scope 概念（配置里 scope_param 是 state），所以最小权限集合是空的：
 * 权限是否齐全由 2.2.2.1 健康诊断在真实调用时判定，而不是在这里猜。
 */
@Component
public class WalmartOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "WALMART";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public com.starcon.channel.vendor.OAuthProtocol.AccountPayload readAccount(
      OAuthProfile profile, String body) {
    com.starcon.channel.vendor.OAuthProtocol.AccountPayload payload =
        super.readAccount(profile, body);
    if (!payload.shops().isEmpty()) return payload;
    return new com.starcon.channel.vendor.OAuthProtocol.AccountPayload(
        payload.accountId(),
        payload.accountName(),
        List.of(
            new com.starcon.channel.vendor.OAuthProtocol.PlatformShop(
                payload.accountId(), payload.accountName(), "")));
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_client_id", "unauthorized_client" ->
          new OAuthFailure("CREDENTIAL_REJECTED", "Walmart 拒绝了应用凭据（client_id / client_secret 或 OAuth 消费者密钥）不匹配，请联系管理员检查配置。");
      case "access_denied" -> new OAuthFailure("PLATFORM_REJECTED", "你已取消授权，未选择任何店铺。重新发起即可继续。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of();
  }
}
