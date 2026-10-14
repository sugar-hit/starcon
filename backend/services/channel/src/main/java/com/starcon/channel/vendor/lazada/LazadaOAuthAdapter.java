package com.starcon.channel.vendor.lazada;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.AccountPayload;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.OAuthProtocol.PlatformShop;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lazada 授权。
 *
 * <p>账号即店铺：这个平台一次授权只落一个卖家账号，没有多店铺选择， 所以候选列表被补成单元素 —— 向导第 5 步要显示的是一个真实待确认对象， 而不是"没有店铺可选"这种看起来像失败的空态。
 *
 * <p>响应同样套了 {@code data} 壳，且换令牌用 JSON 提交。
 */
@Component
public class LazadaOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "LAZADA";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public AccountPayload readAccount(OAuthProfile profile, String body) {
    JsonNode node = parse(body);
    JsonNode data = node.get("data");
    AccountPayload payload = super.readAccount(profile, data == null ? body : data.toString());
    if (!payload.shops().isEmpty()) return payload;
    List<PlatformShop> shops = new ArrayList<>();
    shops.add(new PlatformShop(payload.accountId(), payload.accountName(), ""));
    return new AccountPayload(payload.accountId(), payload.accountName(), List.copyOf(shops));
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_app_key", "invalid_app_secret" ->
          new OAuthFailure(
              "CREDENTIAL_REJECTED", "Lazada 拒绝了应用凭据（app_key / app_secret 不正确），请联系管理员检查配置。");
      case "permission_denied" ->
          new OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此权限范围，请在 Lazada Open Platform 调整后重试。");
      case "user_cancelled" -> new OAuthFailure("PLATFORM_REJECTED", "你已取消授权。重新发起即可继续。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("orders", "products");
  }
}
