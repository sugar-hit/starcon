package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.4.2.1 平台价格同步的**跨服务端口**（catalog → channel）。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 回写任务住在 channel 服务的库里（V2 {@code platform_operation}，即蓝图说的「02 队列」），
 * 价格同步在 catalog 服务。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理 —— 而且 channel 那边**还在往这张表写**，
 * 那样就会出现两个写入所有者。
 * 所以本服务**只**通过 channel 的授权端点投递与回执，
 * <b>catalog 库里不存在任何 channel 表的副本</b>。
 *
 * <p><b>不自己造平台 HTTP 客户端</b>：本仓没有真实平台账号与网络出口，
 * 打真实域名的客户端只会制造一个「看起来能连、实际永远失败」的假能力。
 * 平台那一跳收敛在 channel 的 vendor 适配层里 —— 本端口只负责把
 * <b>回写意图</b>投递进 02 队列。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * channel 再向 identity 确认已认证成员关系、解析出租户。本端口<b>不</b>额外发明
 * {@code X-Tenant-Id} 之类的头传租户：那等于新建一条「客户端自称租户」的旁路，
 * 而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>登记式边界</b>：{@link #resolveTarget} 查的是 channel 里**已登记**的店铺与站点。
 * 一个平台 / 站点没有登记店铺 ⇒ 平台侧根本没有这个落点 ⇒ 回写**发不出去**，
 * 这件事必须显性回报（「没有登记店铺」），而不是凭空造一个任务出去。
 *
 * <p><b>本接口在测试里由替身满足</b>，但生产代码路径永远是真实的 HTTP 调用 ——
 * 替身逻辑不写进生产代码。
 */
public interface PlatformPriceSyncPort {

  /**
   * 解析回写落点：channel 里**已登记**的店铺 + 站点。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 channel</b>）
   * @param platformCode 平台码
   * @param siteCode 站点码
   */
  Optional<Target> resolveTarget(String bearer, String platformCode, String siteCode);

  /**
   * 把一次改价投递进 02 队列（channel 的 {@code platform_operation}，operation='UPDATE_PRICE'）。
   *
   * @return 02 队列任务 id
   */
  UUID enqueueWriteback(String bearer, WritebackCommand command);

  /**
   * 把回写结果回执给 02 队列（成功 / 失败）。
   *
   * <p>失败走 channel 的 {@code /fail} 而不是 {@code /unknown}：本端口只在**已经知道**
   * 平台结论时调用（回执里带着实际价或拒绝原因），结果未知时压根不该调这个方法。
   */
  void completeWriteback(
      String bearer, UUID operationId, boolean succeeded, String platformRequestId, String reason);

  /**
   * 回写落点。
   *
   * @param shopId channel 的店铺 id（02 队列任务必填）
   * @param marketplaceId channel 的站点 id（可空）
   */
  record Target(
      UUID shopId,
      UUID marketplaceId,
      String platformCode,
      String siteCode,
      String shopCode,
      String shopName) {}

  /**
   * 一次改价的投递意图。
   *
   * <p>🔴 这里给的是**我方 SKU 码 + 平台 + 站点 + 价**，不是平台的 listing id ——
   * 「我方 SKU 对应哪个平台商品」是 channel 的刊登映射（4.1 / 2.4）该回答的事，
   * catalog 不复制那份映射，也不该在这里猜一个平台 listing id 出来。
   */
  record WritebackCommand(
      UUID shopId,
      UUID marketplaceId,
      String idempotencyKey,
      String platformCode,
      String siteCode,
      String skuCode,
      BigDecimal price,
      String currency,
      BigDecimal previousPrice) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成 503 或失败结果。 */
  class ChannelUnavailableException extends RuntimeException {
    public ChannelUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public ChannelUnavailableException(String message) {
      super(message);
    }
  }

  // ================================================================ 真实 HTTP 实现

  /**
   * 真实的 HTTP 实现。与本服务既有的 {@link ChannelPlatformPort}（读平台目录）完全同构：
   * {@link RestClient} + {@code @Value} 读配置 + 出网超时。
   */
  class HttpPlatformPriceSyncPort implements PlatformPriceSyncPort {

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public HttpPlatformPriceSyncPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public Optional<Target> resolveTarget(String bearer, String platformCode, String siteCode) {
      List<ShopView> shops;
      try {
        shops =
            client
                .get()
                .uri("/channel/shops")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<ShopView>>() {});
      } catch (RestClientResponseException e) {
        throw translate(e, "读已登记的店铺与站点");
      } catch (RestClientException e) {
        throw unavailable("读已登记的店铺与站点", e);
      }
      if (shops == null) return Optional.empty();
      String platform = PlatformPriceSyncRules.norm(platformCode);
      String site = PlatformPriceSyncRules.norm(siteCode);
      for (var shop : shops) {
        if (shop == null || shop.id() == null) continue;
        if (!platform.equals(PlatformPriceSyncRules.norm(shop.platformCode()))) continue;
        for (var market : shop.marketplaces() == null ? List.<MarketplaceView>of() : shop.marketplaces()) {
          if (market == null) continue;
          if (!site.equals(PlatformPriceSyncRules.norm(market.marketplaceCode()))) continue;
          return Optional.of(
              new Target(
                  shop.id(),
                  market.marketplaceId(),
                  shop.platformCode(),
                  market.marketplaceCode(),
                  shop.code(),
                  shop.name()));
        }
      }
      return Optional.empty();
    }

    @Override
    public UUID enqueueWriteback(String bearer, WritebackCommand command) {
      // payload 的键与 2.5.2.1 批量改价的 UPDATE_PRICE **逐字同形**：
      // channel 那边的读方按 targetType + externalRef 归属平台操作记录。
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("targetType", "LISTING");
      payload.put("externalRef", command.skuCode());
      payload.put("skuCode", command.skuCode());
      payload.put("price", command.price().setScale(PlatformPriceSyncRules.MONEY_SCALE, java.math.RoundingMode.UNNECESSARY));
      payload.put("currency", command.currency());
      payload.put("previousPrice", command.previousPrice());
      payload.put("platformCode", command.platformCode());
      payload.put("marketplaceCode", command.siteCode());

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("shopId", command.shopId().toString());
      body.put("marketplaceId", command.marketplaceId() == null ? null : command.marketplaceId().toString());
      body.put("operation", "UPDATE_PRICE");
      body.put("idempotencyKey", command.idempotencyKey());
      body.put("payload", writeJson(payload));

      try {
        UUID operationId =
            client
                .post()
                .uri("/channel/platform-operations")
                .header("Authorization", bearer)
                .body(body)
                .retrieve()
                .body(UUID.class);
        if (operationId == null) {
          throw new ChannelUnavailableException("渠道服务没有返回 02 队列任务 id");
        }
        return operationId;
      } catch (RestClientResponseException e) {
        throw translate(e, "投递回写任务到 02 队列");
      } catch (RestClientException e) {
        throw unavailable("投递回写任务到 02 队列", e);
      }
    }

    @Override
    public void completeWriteback(
        String bearer, UUID operationId, boolean succeeded, String platformRequestId, String reason) {
      try {
        if (succeeded) {
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("platformRequestId", platformRequestId == null ? "" : platformRequestId);
          client
              .post()
              .uri("/channel/platform-operations/{id}/succeed", operationId)
              .header("Authorization", bearer)
              .body(body)
              .retrieve()
              .toBodilessEntity();
        } else {
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("reason", reason == null || reason.isBlank() ? "平台拒收" : reason);
          client
              .post()
              .uri("/channel/platform-operations/{id}/fail", operationId)
              .header("Authorization", bearer)
              .body(body)
              .retrieve()
              .toBodilessEntity();
        }
      } catch (RestClientResponseException e) {
        throw translate(e, "回执 02 队列任务");
      } catch (RestClientException e) {
        throw unavailable("回执 02 队列任务", e);
      }
    }

    private String writeJson(Object payload) {
      try {
        return json.writeValueAsString(payload);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("回写任务载荷序列化失败", e);
      }
    }

    private static ChannelUnavailableException translate(RestClientResponseException e, String what) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new ChannelUnavailableException(
            "渠道服务拒绝了这次调用（"
                + status
                + "）："
                + what
                + "需要 channel.operation.* 权限。本服务把调用方的 Authorization 头原样透传过去，"
                + "租户由渠道服务从成员关系解析 —— 不传这个头等于丢掉租户上下文。这里选择明确拒绝而不是放行",
            e);
      }
      return new ChannelUnavailableException("渠道服务返回 " + status + "，" + what + "失败", e);
    }

    private static ChannelUnavailableException unavailable(String what, Throwable cause) {
      return new ChannelUnavailableException(
          "渠道服务暂时不可达，" + what + "失败。本服务不会凭空造一个回写任务出来 —— "
              + "那样页面上会显示「已回写」，而平台上根本没有这次动作",
          cause);
    }
  }

  /**
   * 上游未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默成功**：{@code CHANNEL_URL} 没配时，一次回写都发不出去。
   * 假装成功会让页面上显示「已回写平台」，而平台侧什么也没发生 ——
   * 那正是蓝图那句「不能假设回写即成功」最典型的违反方式。
   */
  class UnwiredPlatformPriceSyncPort implements PlatformPriceSyncPort {
    @Override
    public Optional<Target> resolveTarget(String bearer, String platformCode, String siteCode) {
      throw new ChannelUnavailableException(
          "渠道服务地址未配置（CHANNEL_URL 为空）：回写任务的唯一去向是渠道服务的 02 队列（2.5.1.1），"
              + "地址没配就一次都发不出去。请配置 CHANNEL_URL 后重试");
    }

    @Override
    public UUID enqueueWriteback(String bearer, WritebackCommand command) {
      throw new ChannelUnavailableException(
          "渠道服务地址未配置（CHANNEL_URL 为空）：回写任务的唯一去向是渠道服务的 02 队列（2.5.1.1），"
              + "地址没配就一次都发不出去。请配置 CHANNEL_URL 后重试");
    }

    @Override
    public void completeWriteback(
        String bearer, UUID operationId, boolean succeeded, String platformRequestId, String reason) {
      throw new ChannelUnavailableException(
          "渠道服务地址未配置（CHANNEL_URL 为空）：02 队列任务的回执发不出去，"
              + "该回写任务会停在 PENDING 而不是被误标成已成功。请配置 CHANNEL_URL 后重试");
    }
  }

  // ================================================================ channel 侧契约形状

  /** 店铺（只要本模块用得到的列）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      List<MarketplaceView> marketplaces) {}

  /** 站点。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MarketplaceView(UUID marketplaceId, String marketplaceCode, String marketplaceName) {}
}
