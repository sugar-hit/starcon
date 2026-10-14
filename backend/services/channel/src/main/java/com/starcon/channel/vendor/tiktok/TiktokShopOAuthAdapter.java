package com.starcon.channel.vendor.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TikTok Shop 授权。
 *
 * <p>三个平台特有的点：
 *
 * <ol>
 *   <li>scope 用<b>逗号</b>分隔（不是空格）；
 *   <li>换令牌是 <b>JSON</b> 请求；
 *   <li>账号接口的响应<b>套了两层壳</b>：店铺列表在 {@code data.shops} 下，账号字段在 {@code data} 上， 直接按顶层字段读会全部读空。
 * </ol>
 */
@Component
public class TiktokShopOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "TIKTOK_SHOP";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public com.starcon.channel.vendor.OAuthProtocol.AccountPayload readAccount(
      OAuthProfile profile, String body) {
    // 剥掉 data 包装再交给公共解析，否则 shop_id / shop_name 全部读空
    JsonNode node = parse(body);
    JsonNode data = node.get("data");
    return super.readAccount(profile, data == null || !data.isObject() ? body : data.toString());
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_client" ->
          new OAuthFailure(
              "CREDENTIAL_REJECTED", "TikTok Shop 拒绝了应用凭据，请联系管理员检查 client_id / client_secret。");
      case "99991663", "99991661" ->
          new OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此权限范围，请在 TikTok Shop 开放平台调整后重试。");
      case "2" -> new OAuthFailure("PLATFORM_REJECTED", "你已取消授权。重新发起即可继续。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("read_orders", "read_products");
  }
}
