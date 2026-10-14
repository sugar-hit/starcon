package com.starcon.channel.vendor.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.AccountPayload;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.PlatformException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Shopee 授权。
 *
 * <p>Shopee 是这套适配器里对运营影响最大的一个：<b>access token 只有 2 小时有效</b>， 靠 refresh token 续期。因此
 * 2.2.2.2「重新授权提醒」对这个平台并不是可选提醒， 而是"忘了续期就断单"的问题——模块 2.2.2.2 的差异化阈值就是为它设的。
 *
 * <p>响应结构也和其他平台不同：换令牌与店铺列表的响应都把业务数据裹在 {@code response} 下， 失败时 HTTP 仍是 200、错误在 {@code response_code}
 * 里，所以必须先看 code 再读 data。
 */
@Component
public class ShopeeOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "SHOPEE";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public AccountPayload readAccount(OAuthProfile profile, String body) {
    JsonNode node = parse(body);
    JsonNode response = node.get("response");
    if (response != null) {
      // Shopee 把"失败"放在 200 响应体的 response_code 里，不看这层会读到空字段当成授权成功
      long code = response.path("response_code").asLong(0);
      if (code != 0)
        throw new PlatformException(
            new OAuthFailure(
                "ACCOUNT_LOOKUP_FAILED",
                "Shopee 拒绝了账号查询（错误码 "
                    + code
                    + "）："
                    + response.path("response_message").asText("未知原因")));
    }
    return super.readAccount(profile, response == null ? body : response.toString());
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "1003", "7" ->
          new OAuthFailure(
              "CREDENTIAL_REJECTED", "Shopee 拒绝了应用凭据（partner_id 与 partner_key 不匹配），请联系管理员检查配置。");
      case "9" -> new OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此店铺权限，请在 Shopee 开放平台调整后重试。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("shop", "order", "product");
  }
}
