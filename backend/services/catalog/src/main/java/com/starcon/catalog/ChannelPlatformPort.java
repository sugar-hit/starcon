package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.3.1.1 利润测算器：读 2.1.1.1 平台目录（channel 服务）的**跨服务端口**。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 平台目录（{@code platform} / {@code marketplace}）在 channel 服务的库里，
 * 利润测算在 catalog 服务。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理，而且一旦两边都往这些表写，就出现了两个写入所有者。
 * 所以本服务**只**通过 channel 的授权只读端点取数，
 * <b>catalog 库里不存在任何平台目录的副本</b>（V227 迁移里零个外键指向 channel 的表）。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * channel 再向 identity 确认已认证成员关系、解析出租户。本端口<b>不</b>额外发明
 * {@code X-Tenant-Id} 之类的头传租户：那等于新建一条「客户端自称租户」的旁路，
 * 而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有数据</b>：取不到时抛
 * {@link ChannelPlatformUnavailableException}，由服务层翻译成 503。
 * <b>绝不</b>返回空站点列表冒充「这个平台没有站点」——
 * 那会让页面的「目标店铺」选择器变成空的，而用户看不出是上游挂了。
 *
 * <p><b>本接口在测试里由替身满足</b>，但生产代码路径永远是真实的 HTTP 调用 ——
 * 替身逻辑不写进生产代码。
 */
public interface ChannelPlatformPort {

  /**
   * 取平台目录里的全部站点（平台 + 站点 + 默认币种 + 币种精度 + 增值税开关）。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 channel</b>）
   */
  List<Site> sites(String bearer);

  /**
   * 一个站点（= 目标店铺）。
   *
   * @param currencyScale 站点的币种精度（显示用；本模块的算术精度固定 4 位小数，见能力声明）
   * @param vatRequired 该站点是否需要增值税 —— 增值税本身的扣减归 5.2.3.1 平台费用，本模块只提示
   */
  record Site(
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      String countryCode,
      String defaultCurrency,
      int currencyScale,
      boolean vatRequired) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成对调用方有意义的一句话。 */
  class ChannelPlatformUnavailableException extends RuntimeException {
    public ChannelPlatformUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public ChannelPlatformUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置，
   * 与本服务既有的 {@link CostBasisPort}（读 warehouse）完全同构。
   */
  class HttpChannelPlatformPort implements ChannelPlatformPort {

    private final RestClient client;

    public HttpChannelPlatformPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public List<Site> sites(String bearer) {
      List<PlatformView> platforms;
      try {
        platforms =
            client
                .get()
                .uri("/channel/platforms")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<PlatformView>>() {});
      } catch (RestClientResponseException e) {
        throw translate(e);
      } catch (RestClientException e) {
        throw unavailable("渠道服务暂时不可达", e);
      }
      if (platforms == null) {
        throw new ChannelPlatformUnavailableException("渠道服务没有返回平台目录");
      }
      var out = new ArrayList<Site>();
      for (var platform : platforms) {
        if (platform == null || platform.code() == null) continue;
        for (var market : platform.marketplaces() == null ? List.<MarketplaceView>of() : platform.marketplaces()) {
          if (market == null || market.code() == null) continue;
          out.add(
              new Site(
                  platform.code(),
                  platform.name(),
                  market.code(),
                  market.name(),
                  market.countryCode(),
                  market.defaultCurrency(),
                  market.currencyScale(),
                  market.vatRequired()));
        }
      }
      return out;
    }

    private static ChannelPlatformUnavailableException translate(RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new ChannelPlatformUnavailableException(
            "渠道服务拒绝了这次调用（" + status + "）：读平台目录需要 channel.registry.read 权限。"
                + "本服务把调用方的 Authorization 头原样透传过去，租户由渠道服务从成员关系解析 —— "
                + "不传这个头等于丢掉租户上下文。这里选择明确拒绝而不是放行",
            e);
      }
      return new ChannelPlatformUnavailableException("渠道服务返回 " + status + "，读不到平台目录", e);
    }

    private static ChannelPlatformUnavailableException unavailable(String reason, Throwable cause) {
      return new ChannelPlatformUnavailableException(
          reason
              + "，读不到平台目录（2.1.1.1）。目标店铺的币种校验与增值税提示依赖它。"
              + "本服务不会凭空生成一份平台目录 —— 拿不到时显示「读不到」而不是显示空列表",
          cause);
    }
  }

  /**
   * 上游未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默返回空**：{@code CHANNEL_URL} 没配时，平台目录一个都读不到。
   * 返回空列表会让页面的「目标店铺」选择器变成空的，而用户看不出是配置问题。
   */
  class UnwiredChannelPlatformPort implements ChannelPlatformPort {
    @Override
    public List<Site> sites(String bearer) {
      throw new ChannelPlatformUnavailableException(
          "渠道服务地址未配置（CHANNEL_URL 为空）：平台目录的唯一来源是渠道服务的授权只读端点，"
              + "地址没配就一个都读不到。请配置 CHANNEL_URL 后重试");
    }
  }

  // ================================================================ channel 侧契约形状

  /** 平台（只要本模块用得到的列：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformView(
      String code, String name, List<MarketplaceView> marketplaces) {}

  /** 站点（默认币种 / 币种精度 / 增值税开关）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MarketplaceView(
      String code,
      String name,
      String countryCode,
      String defaultCurrency,
      int currencyScale,
      boolean vatRequired) {}
}
