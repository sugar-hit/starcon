package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **2 渠道机制**（channel 服务，端口 8083）的**跨服务只读端口**。
 *
 * <p>蓝图 18.2.3.1 的关系写明「<b>上游 = 2 渠道机制</b>」—— 本模块管的外部系统
 * （1688 / 淘宝 / 海外仓 / 物流商 / 聚合认证 / 自建 BI）都是**围绕店铺**跑的：
 * 货源清单对应哪个店、库存观测对应哪个仓、运单对应哪条渠道。
 * 「可绑定的上游对象」因此来自 channel 服务。
 *
 * <p>⚠️ <b>只读</b>：本服务**不**回写 channel 的库（AGENTS.md：禁止跨服务 SQL / FK）。
 * 连接档案里只存**只读引用**（平台代码 / 店铺 id），不建跨库外键。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>；<b>取不到就 503</b>。
 * 租户隔离是**两道**（platform 侧 RLS + channel 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 */
@Service
public class ChannelPlatformPort {

  private final PlatformUpstreamClient client;

  public ChannelPlatformPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = PlatformUpstreamClient.to("channel 服务", url);
  }

  /** 上游 2.1.1.1 平台目录。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformRow(UUID id, String code, String name, String integration, String supportStatus) {}

  /** 上游 2.3.1.1 店铺。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      java.time.Instant lastSyncAt) {}

  /** 平台目录（2.1.1.1）。 */
  public List<PlatformRow> platforms(String bearer) {
    // ⚠️ channel 的 /channel/platforms 返回的是**裸 JSON 数组**，不是分页对象 ——
    //   所以这里按数组类型反序列化（裸数组被拆成 List<Object> 的话字段会全丢）。
    return java.util.Arrays.stream(
            client.get(
                uriBuilder -> uriBuilder.path("/channel/platforms"), bearer, PlatformRow[].class))
        .map(r -> new PlatformRow(r.id(), r.code(), r.name(), r.integration(), r.supportStatus()))
        .toList();
  }

  /** 店铺列表（2.3.1.1）。 */
  public List<ShopRow> shops(String bearer) {
    return java.util.Arrays.stream(
            client.get(uriBuilder -> uriBuilder.path("/channel/shops"), bearer, ShopRow[].class))
        .map(
            r ->
                new ShopRow(
                    r.id(),
                    r.code(),
                    r.name(),
                    r.platformCode(),
                    r.platformName(),
                    r.status(),
                    r.lastSyncAt()))
        .toList();
  }
}
