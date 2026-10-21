package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentStockModels.PlatformConsignmentOperatingMode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.2.2 的**经营模式只读端口** —— 2.3.2.1（自发货 / 全托管 / 半托管）的唯一来源。
 *
 * <p>店铺与经营模式属 channel 服务（{@code shop.operating_mode}，channel V1 交付）。AGENTS.md
 * 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口**只经 channel 的授权只读端点**读：
 * {@code GET /channel/shops/{id}}。
 *
 * <p>🔴 <b>「读不到」与「确实不是这个模式」必须分得开</b>：
 *
 * <ul>
 *   <li>上游<b>不可达</b> → 抛 503，理由写明「读不到」。<b>绝不</b>返回「自发货」或
 *       「未知模式」当成一个模式用 —— 那会让自发货店铺被当成全托管建出托管备货单。
 *   <li>上游<b>正常返回、但没有这家店铺</b> → {@code known=false} + 模式为 {@code null}。
 *       这不是故障：查询成功了，结论就是「这家店铺在 channel 里没有这条记录」。
 * </ul>
 *
 * <p><b>本端口不建影子表</b>：模式只在建单那一刻**快照**进备货单（那是「建单当时的事实」），
 * 要查店铺**现在**的模式仍然读 channel —— 快照不是第二个权威。
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**
 * （warehouse 侧 RLS + channel 侧 RLS），而不是本服务替对方决定租户。
 */
@Service
public class ChannelShopModePort {

  /** channel 的单店读取出口。 */
  static final String SHOP_PATH = "/channel/shops/{id}";

  private final RestClient client;

  public ChannelShopModePort(@Value("${CHANNEL_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时 8 秒：一次穿透比单条本地读慢一截，但仍然必须有上限 ——
    // 没有上限时上游一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** channel 的店铺读模型（**只声明本模块真正用到的字段**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopRow(UUID id, String code, String name, String operatingMode) {}

  /**
   * 一个店铺的经营模式结论。
   *
   * @param known 上游是否给出了这家店铺的经营模式。false 时 {@code operatingMode} 是 {@code null}
   *     （<b>不是</b> 0、也不是猜一个「自发货」）
   */
  public record ShopMode(
      UUID shopId,
      String shopCode,
      String shopName,
      String operatingMode,
      boolean known,
      String note) {

    /** 该模式是否允许建立平台托管备货单；模式未知时一律 false（宁可不建，也不猜）。 */
    public boolean supportsConsignmentStock() {
      if (!known || operatingMode == null) return false;
      for (var mode : PlatformConsignmentOperatingMode.values()) {
        if (mode.name().equals(operatingMode)) return mode.supportsConsignmentStock();
      }
      return false;
    }

    /** 该模式的展示名；未知时回落到原始值。 */
    public String operatingModeLabel() {
      if (operatingMode == null) return "";
      for (var mode : PlatformConsignmentOperatingMode.values()) {
        if (mode.name().equals(operatingMode)) return mode.label();
      }
      return operatingMode;
    }
  }

  /**
   * 读一家店铺的经营模式。
   *
   * @param shopId 店铺标识
   * @param bearer 调用方自己的 bearer（转发，channel 侧按成员关系解析租户）
   * @throws ResponseStatusException 403（无权读）/ 503（channel 读不到）
   */
  public ShopMode operatingMode(UUID shopId, String bearer) {
    ShopRow row;
    try {
      row =
          client
              .get()
              .uri(SHOP_PATH, shopId)
              .header("Authorization", bearer)
              .retrieve()
              .body(ShopRow.class);
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        // 上游正常应答，只是没有这家店铺：**无记录 ≠ 模式是自发货**。
        return new ShopMode(shopId, null, null, null, false, "channel 没有这家店铺的记录（无记录 ≠ 经营模式已知）");
      }
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取店铺经营模式，托管备货单暂时建不了", e);
      }
      throw unavailable("channel 服务返回 " + code + "：经营模式读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达，经营模式读不到");
    }
    if (row == null) {
      throw unavailable("channel 服务没有返回店铺信息");
    }
    if (row.operatingMode() == null || row.operatingMode().isBlank()) {
      // 上游回了店铺但没给模式：这仍然是「读不到模式」，不能当成自发货。
      return new ShopMode(
          row.id() == null ? shopId : row.id(),
          row.code(),
          row.name(),
          null,
          false,
          "channel 返回的店铺没有经营模式字段（读不到，不当成自发货）");
    }
    return new ShopMode(
        row.id() == null ? shopId : row.id(),
        row.code(),
        row.name(),
        row.operatingMode(),
        true,
        "经营模式来自 channel 的 2.3.2.1（只读，本服务不建影子表）");
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「不是全托管」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        detail
            + "：店铺经营模式（2.3.2.1）的唯一来源是 channel 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空报一个经营模式");
  }
}
