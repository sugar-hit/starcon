package com.starcon.channel.vendor.amazon;

import com.starcon.channel.vendor.AbstractOAuthAdapter;
import com.starcon.channel.vendor.OAuthProtocol.OAuthFailure;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Amazon SP-API 授权（卖家中心授权）。
 *
 * <p>与多数平台的差异：换令牌用 HTTP Basic 传 client 凭据、强制 PKCE（S256）、 返回的 access token <b>只有 1 小时</b>有效、真正的长期凭据是
 * refresh token。 所以对 Amazon 来说 2.2.2.2 的临期提醒是刚需而不是可选项。
 *
 * <p>另有一个容易踩的坑：Amazon 授权一旦成功就会<b>立即生效并覆盖该应用此前的授权</b>， 因此同一个店铺重复授权不是"多一条连接"，而是换掉旧的。服务层按"同租户同账号即同一连接"
 * 幂等处理，跨租户才会撞上"已被其他租户绑定"。
 */
@Component
public class AmazonOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "AMAZON";
  }

  @Override
  public String scopeSeparator() {
    return " ";
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_grant" ->
          new OAuthFailure("CREDENTIAL_REJECTED", "授权码已失效或与请求的 redirect_uri 不匹配，请重新发起授权。");
      case "invalid_client" ->
          new OAuthFailure(
              "CREDENTIAL_REJECTED", "Amazon 拒绝了应用凭据（client_id / client_secret 不正确），请联系管理员检查配置。");
      case "unauthorized_client" ->
          new OAuthFailure("INSUFFICIENT_SCOPE", "该 SP-API 应用未被授予所需权限，请在 Seller Central 的应用设置里补齐。");
      case "access_denied" -> new OAuthFailure("PLATFORM_REJECTED", "你已取消授权，未选择任何店铺。重新发起即可继续。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    // 没有 orders 权限就抓不到订单，这是该平台最常见的"授权成功但同步没数据"根因
    return List.of("sellingpartnerapi::orders", "sellingpartnerapi::items");
  }
}
