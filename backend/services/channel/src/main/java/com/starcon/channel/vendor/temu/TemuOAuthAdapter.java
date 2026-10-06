package com.starcon.channel.vendor.temu;

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
 * Temu 授权（全托管）。
 *
 * <p>Temu 的授权粒度是"应用 × 商家"，没有传统意义的店铺列表接口，
 * 所以它也是这里唯一 {@code requiresShopDomain == false} 却把账号当作店铺的平台：
 * {@link #readAccount} 在拿不到店铺数组时，会用账号本身补出一个店铺，
 * 让向导第 5 步仍然有真实的东西可确认，而不是退化成"跳过这一步"。
 *
 * <p>全托管的店铺在 2.3.2.1 里是 {@code FULL_CONSIGNMENT} 经营模式——货权与结算口径和
 * 自发货完全不同，这里固化的默认模式会写进向导建出来的 shop 行。
 */
@Component
public class TemuOAuthAdapter extends AbstractOAuthAdapter {

  @Override
  public String platformCode() {
    return "TEMU";
  }

  @Override
  public String scopeSeparator() {
    return ",";
  }

  @Override
  public AccountPayload readAccount(OAuthProfile profile, String body) {
    // Temu 的店铺列表挂在 data.result 下，与 TikTok 的 data.shops 不同，先剥壳再走公共解析
    JsonNode node = unwrap(body);
    AccountPayload payload = super.readAccount(profile, node == null ? body : node.toString());
    if (!payload.shops().isEmpty()) return payload;
    List<PlatformShop> shops = new ArrayList<>();
    shops.add(new PlatformShop(payload.accountId(), payload.accountName(), ""));
    return new AccountPayload(payload.accountId(), payload.accountName(), List.copyOf(shops));
  }

  @Override
  public OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "invalid_client" ->
          new OAuthFailure("CREDENTIAL_REJECTED", "Temu 拒绝了应用凭据，请联系管理员检查 client_id / client_secret。");
      case "999016", "40301" ->
          new OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此权限范围，请在 Temu 开放平台调整后重试。");
      default -> super.translatePlatformError(errorCode, description);
    };
  }

  @Override
  public List<String> minimumScopes() {
    return List.of("order", "product");
  }

  /** Temu 的店铺列表挂在 data.result 下，与 TikTok 的 data.shops 不同，这里单独处理。 */
  private JsonNode unwrap(String body) {
    try {
      JsonNode node = parse(body);
      JsonNode data = node.get("data");
      if (data == null) return node;
      JsonNode result = data.get("result");
      return result == null ? data : result;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
