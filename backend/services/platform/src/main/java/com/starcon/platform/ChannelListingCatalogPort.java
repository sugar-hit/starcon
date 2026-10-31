package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * channel 服务（端口 8083）的**跨服务只读端口**：平台商品清单（4.x）。
 *
 * <p>蓝图 18.1.3.1 写「上游 = 全部业务域」—— 平台商品是其中一格。
 * 刊登的事实住在 channel 的库里，本端口只读它，<b>不抄它</b>。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>列表字段是 {@code items}</b>（与 warehouse 的 {@code rows} 不同 —— 写错会得到空导出文件）。
 *
 * <p>⚠️ **本类与 {@code ChannelListingPort}（18.4.2.1 文本翻译的刊登草稿端口）不是一件事**：
 * 那一个读的是「刊登编辑器的草稿逐栏内容」，这一个读的是「平台商品清单」。
 * 两个都指向 channel，但端点、语义、归属模块都不同 —— 故分开两个类。
 */
@Service
public class ChannelListingCatalogPort {

  private final PlatformUpstreamClient client;

  public ChannelListingCatalogPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = PlatformUpstreamClient.to("channel 服务", url);
  }

  /** 上游一行平台商品。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingRow(
      UUID id,
      String shopCode,
      String shopName,
      String platformCode,
      String marketplaceCode,
      String externalItemId,
      String title,
      String status,
      String mappingStatus,
      List<UUID> internalSkuIds) {}

  /** 上游一页平台商品（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPage(List<ListingRow> items, long total, int page, int size) {}

  /** 拉一页平台商品。 */
  public ListingPage listings(int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/channel/listings")
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          return uriBuilder;
        },
        bearer,
        ListingPage.class);
  }
}
