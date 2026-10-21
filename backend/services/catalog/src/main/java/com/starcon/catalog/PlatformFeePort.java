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
 * 5.2.3.1 平台费用：读上游 **2 平台目录**（channel 服务）的跨服务端口。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 平台码表（{@code platform} / {@code marketplace}）在 channel 服务的库里，
 * 而平台费用规则在 catalog 的库里。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理 —— 而且一旦两边都往这些表写，就出现了两个写入所有者。
 * 所以本服务**只**通过 channel 的授权只读端点取数，
 * <b>catalog 库里不存在任何平台 / 站点的副本</b>（V226 迁移里零个外键指向 channel 的表）。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * 租户由 channel 从成员关系解析。本端口<b>不</b>额外发明一个 {@code X-Tenant-Id} 之类的头传租户：
 * 那等于新建一条「客户端自称租户」的旁路，而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有平台</b>：取不到时抛 {@link PlatformDirectoryUnavailableException}，
 * 由服务层翻译成 503。<b>绝不</b>返回空集合冒充「没有平台」——
 * 那会让运营以为「这个租户没接任何平台」，从而以为「不配置费率就是对的」，
 * 而真实情况是「我们没接上游，读不到平台清单」。
 *
 * <p><b>本接口在测试里由替身满足</b>（见 {@code PlatformFeeIntegrationTest}），但生产代码路径
 * 永远是真实的 HTTP 调用 —— 替身逻辑不写进生产代码。
 */
public interface PlatformFeePort {

  /**
   * 取平台 / 站点目录。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 channel，租户由它从成员关系解析</b>）
   */
  PlatformFeeModels.PlatformDirectory directory(String bearer);

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成对调用方有意义的一句话。 */
  class PlatformDirectoryUnavailableException extends RuntimeException {
    public PlatformDirectoryUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public PlatformDirectoryUnavailableException(String message) {
      super(message);
    }
  }

  /** 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置，与本服务既有的 {@link AccessService}（读 identity）、{@link CostBasisPort}（读 warehouse）完全同构 —— 跨服务调用在本仓库只有这一种写法。 */
  class HttpPlatformFeePort implements PlatformFeePort {

    private final RestClient client;

    public HttpPlatformFeePort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public PlatformFeeModels.PlatformDirectory directory(String bearer) {
      List<PlatformView> views;
      try {
        views =
            client
                .get()
                .uri("/channel/platforms")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<PlatformView>>() {});
      } catch (RestClientResponseException e) {
        throw translate(e);
      } catch (RestClientException e) {
        throw unavailable(e);
      }
      if (views == null) {
        throw new PlatformDirectoryUnavailableException("渠道服务返回了空的平台目录");
      }
      var platforms = new ArrayList<PlatformFeeModels.DirectoryPlatform>();
      for (PlatformView view : views) {
        if (view == null || view.code() == null) continue;
        var sites = new ArrayList<PlatformFeeModels.DirectoryMarketplace>();
        for (MarketplaceView site : view.marketplaces() == null ? List.<MarketplaceView>of() : view.marketplaces()) {
          if (site == null || site.code() == null) continue;
          sites.add(
              new PlatformFeeModels.DirectoryMarketplace(
                  site.code(),
                  site.name(),
                  site.countryCode(),
                  site.defaultCurrency(),
                  site.currencyScale(),
                  site.vatRequired()));
        }
        platforms.add(
            new PlatformFeeModels.DirectoryPlatform(view.code(), view.name(), List.copyOf(sites)));
      }
      String note =
          "平台与站点目录取自 channel 服务的授权只读端点 GET /channel/platforms，"
              + "租户由 channel 从已认证成员关系解析并由 RLS 强制隔离；"
              + "本服务不在库里留平台 / 站点副本（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图）。";
      return new PlatformFeeModels.PlatformDirectory(List.copyOf(platforms), note);
    }

    private static PlatformDirectoryUnavailableException translate(RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new PlatformDirectoryUnavailableException(
            "渠道服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由渠道服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行",
            e);
      }
      return new PlatformDirectoryUnavailableException("渠道服务返回 " + status + "，读不到平台目录", e);
    }

    private static PlatformDirectoryUnavailableException unavailable(Throwable cause) {
      return new PlatformDirectoryUnavailableException(
          "渠道服务暂时不可达，读不到平台目录。"
              + "平台费用规则挂在「平台 / 站点」维度上，读不到目录就没法确认"
              + "「这个平台码 / 站点码是不是真的存在」—— "
              + "本服务不会凭空列一份平台清单，也不会拿空清单冒充「这个租户没有平台」",
          cause);
    }
  }

  /** channel 的平台目录响应（与 {@code ChannelModels.PlatformView} 同形）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PlatformView(
      String id,
      String code,
      String name,
      String integration,
      String supportStatus,
      List<String> capabilities,
      List<MarketplaceView> marketplaces) {}

  /** channel 的站点视图（与 {@code ChannelModels.MarketplaceView} 同形）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record MarketplaceView(
      String id,
      String code,
      String name,
      String countryCode,
      String defaultCurrency,
      String defaultTimezone,
      String defaultLocale,
      Integer currencyScale,
      Boolean vatRequired) {}
}
