package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.1.1.1 价目表的**适用范围目录**跨服务端口（法人 + 店铺）。
 *
 * <p>蓝图输入句：「币种与适用范围选择」；范围 = <b>法人 / 店铺 / 站点</b>。
 * 三个上游各在一处：
 *
 * <ul>
 *   <li><b>法人</b> —— 17.1.2.2，identity 服务的 {@code legal_entity} 档案；
 *   <li><b>店铺</b> —— 2.3.1.1，channel 服务的 {@code shop}；
 *   <li><b>站点</b> —— 2.1.1.1，channel 服务的 {@code marketplace}，
 *       <b>复用既有的 {@link ChannelPlatformPort}</b>（本端口不重复实现它）。
 * </ul>
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 法人档案在 identity 的库，店铺与站点在 channel 的库，价目表在本服务（catalog）的库。
 * 跨库 JOIN 会把三个服务的部署绑死在同一个 PG 实例上，跨库 FK / 共享视图同理。
 * 所以本服务只通过上游的授权只读端点取数，
 * <b>catalog 库里不存在任何法人 / 店铺 / 站点的副本</b>。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * 上游再向 identity 确认已认证成员关系、解析出租户。本端口<b>不</b>额外发明
 * {@code X-Tenant-Id} 之类的头传租户：那等于新建一条「客户端自称租户」的旁路，
 * 而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>🔴 读不到 ≠ 没有法人 / 没有店铺</b>：取不到时抛
 * {@link ScopeUnavailableException}，由服务层翻译成 503。
 * <b>绝不</b>返回空列表冒充「这个租户没有法人」——
 * 那会让「适用范围」选择器变成空的，而用户看不出是上游挂了。
 *
 * <p><b>为什么法人不走 {@link FunctionalCurrencyPort}</b>：那个端口的契约是
 * 「本位币」（5.2.2.1 的「各币种对各本位币的汇率」），它会**主动过滤掉没配本位币的法人**
 * —— 那对汇率是对的，对「适用范围目录」是错的：没配本位币的法人照样是一个可以定价的法人，
 * 把它藏起来会让运营以为这个法人不存在。本端口因此自己读 identity 的法人列表，
 * <b>一个都不漏</b>。
 */
public interface PriceListScopePort {

  /** 一个法人（identity 的 legal_entity）。 */
  record LegalEntity(
      UUID id, String code, String name, String baseCurrency, String status) {}

  /**
   * 一个店铺（channel 的 shop）。
   *
   * @param code 店铺编码（价目表适用范围里引用的就是它）
   * @param legalEntityId 绑定的法人（channel 只存逻辑 id，跨服务 FK 被禁止）
   * @param siteCodes 该店铺已启用的站点码（覆盖摘要里显示店铺管到哪些站点）
   */
  record Shop(
      String code,
      String name,
      String platformCode,
      UUID legalEntityId,
      String status,
      List<String> siteCodes) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成 503。 */
  class ScopeUnavailableException extends RuntimeException {
    public ScopeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public ScopeUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 取本租户全部法人。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 identity</b>）
   */
  List<LegalEntity> legalEntities(String bearer);

  /**
   * 取本租户全部店铺。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 channel</b>）
   */
  List<Shop> shops(String bearer);

  /** 真实的 HTTP 实现。 */
  class HttpPriceListScopePort implements PriceListScopePort {

    /** 没有配地址的那一侧是 null（调用时给出「读不到」而不是空列表）。 */
    private final RestClient identity;

    private final RestClient channel;

    public HttpPriceListScopePort(String identityUrl, String channelUrl) {
      this.identity = client(identityUrl);
      this.channel = client(channelUrl);
    }

    private static RestClient client(String baseUrl) {
      if (baseUrl == null || baseUrl.isBlank()) return null;
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public List<LegalEntity> legalEntities(String bearer) {
      if (identity == null) {
        throw new ScopeUnavailableException(
            "未配置身份服务地址（IDENTITY_URL 为空），读不到法人。法人档案归 identity 服务"
                + "（17.1.2.2），本服务不另建法人表 —— 读不到时显示「读不到」而不是显示「没有法人」");
      }
      List<LegalEntityView> body;
      try {
        body =
            identity
                .get()
                .uri("/identity/legal-entities")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<LegalEntityView>>() {});
      } catch (RestClientResponseException e) {
        throw translate(e, "身份服务", "identity.legal_entity.read");
      } catch (RestClientException e) {
        throw new ScopeUnavailableException(
            "身份服务暂时不可用，读不到法人（适用范围是价目表的一部分，读不到就不能保存）", e);
      }
      if (body == null) {
        throw new ScopeUnavailableException("身份服务返回了空的法人列表，读不到适用范围目录");
      }
      var out = new ArrayList<LegalEntity>();
      for (var view : body) {
        if (view == null || view.code() == null || view.code().isBlank()) continue;
        out.add(
            new LegalEntity(
                view.id(),
                view.code().trim(),
                view.legalName() == null ? view.code().trim() : view.legalName().trim(),
                view.baseCurrency(),
                view.status() == null ? "ACTIVE" : view.status()));
      }
      return out;
    }

    @Override
    public List<Shop> shops(String bearer) {
      if (channel == null) {
        throw new ScopeUnavailableException(
            "未配置渠道服务地址（CHANNEL_URL 为空），读不到店铺。店铺归 channel 服务（2.3.1.1），"
                + "本服务不另建店铺表 —— 读不到时显示「读不到」而不是显示「没有店铺」");
      }
      List<ShopView> body;
      try {
        body =
            channel
                .get()
                .uri("/channel/shops")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<ShopView>>() {});
      } catch (RestClientResponseException e) {
        throw translate(e, "渠道服务", "channel.shop.read");
      } catch (RestClientException e) {
        throw new ScopeUnavailableException(
            "渠道服务暂时不可用，读不到店铺（适用范围是价目表的一部分，读不到就不能保存）", e);
      }
      if (body == null) {
        throw new ScopeUnavailableException("渠道服务返回了空的店铺列表，读不到适用范围目录");
      }
      var out = new ArrayList<Shop>();
      for (var view : body) {
        if (view == null || view.code() == null || view.code().isBlank()) continue;
        var siteCodes = new ArrayList<String>();
        if (view.marketplaces() != null) {
          for (var marketplace : view.marketplaces()) {
            if (marketplace == null
                || marketplace.marketplaceCode() == null
                || marketplace.marketplaceCode().isBlank()) continue;
            if (marketplace.status() != null && !"ACTIVE".equalsIgnoreCase(marketplace.status())) {
              continue;
            }
            siteCodes.add(marketplace.marketplaceCode().trim());
          }
        }
        out.add(
            new Shop(
                view.code().trim(),
                view.name() == null ? view.code().trim() : view.name().trim(),
                view.platformCode(),
                view.legalEntityId(),
                view.status() == null ? "ACTIVE" : view.status(),
                siteCodes));
      }
      return out;
    }

    private static ScopeUnavailableException translate(
        RestClientResponseException e, String service, String permission) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new ScopeUnavailableException(
            service
                + "拒绝了这次调用（"
                + status
                + "）：读取适用范围需要 "
                + permission
                + " 权限，跨服务调用必须透传当前用户的 Authorization 头，租户由上游从已认证成员关系解析",
            e);
      }
      return new ScopeUnavailableException(service + "返回 " + status + "，读不到适用范围目录", e);
    }
  }

  /**
   * 上游地址**都**没配时的实现：**显式抛错**，绝不返回空集合。
   *
   * <p>理由与 {@link ChannelPlatformPort.UnwiredChannelPlatformPort} 相同：
   * 空集合会让页面的「适用范围」选择器变成空的，而真实情况是「我们没接上游」——
   * 前者会被当成业务结论。
   *
   * <p>只配了一侧时走 {@link HttpPriceListScopePort}：它对没配的那一侧单独报「未配置」，
   * 另一侧照常可用 —— 两个上游各自的可用性是**两件事**。
   */
  class UnwiredPriceListScopePort implements PriceListScopePort {

    @Override
    public List<LegalEntity> legalEntities(String bearer) {
      throw new ScopeUnavailableException(
          "未配置身份服务地址（IDENTITY_URL 为空），读不到法人。法人档案归 identity 服务"
              + "（17.1.2.2），本服务不另建法人表 —— 读不到时显示「读不到」而不是显示「没有法人」");
    }

    @Override
    public List<Shop> shops(String bearer) {
      throw new ScopeUnavailableException(
          "未配置渠道服务地址（CHANNEL_URL 为空），读不到店铺。店铺归 channel 服务（2.3.1.1），"
              + "本服务不另建店铺表 —— 读不到时显示「读不到」而不是显示「没有店铺」");
    }
  }

  // ================================================================ 上游契约形状

  /** identity 的法人视图（只取本模块用得到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LegalEntityView(
      UUID id, String code, String legalName, String baseCurrency, String status) {}

  /** channel 的店铺视图（只取本模块用得到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShopView(
      UUID id,
      String platformCode,
      UUID legalEntityId,
      String code,
      String name,
      String status,
      List<MarketplaceView> marketplaces) {}

  /** channel 的店铺-站点视图。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record MarketplaceView(String marketplaceCode, String status) {}
}
