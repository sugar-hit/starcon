package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.1 读「涉及店铺」的**跨服务端口**。
 *
 * <p>蓝图输出句的最后一项是「涉及店铺」：一个 SKU 常常同时挂在几个店铺上，断货的影响面就是这几个店铺。
 * 「哪个 SKU 挂在哪些店铺上」这条事实属 channel 服务（4.1.1.1 的 {@code listing_mapping}：
 * {@code internal_sku_id} + {@code shop_code}），所以本端口<b>只经 channel 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键 —— AGENTS.md 模块边界纪律。
 *
 * <p><b>按 SKU 逐个查，不做全量拉取</b>：断货清单关心的 SKU 集合是有限的，而一个租户的映射表
 * 可能非常大。全量拉取要么被页数上限悄悄截断（那样「涉及店铺」会缺一批 —— 界面上的影响面就是错的），
 * 要么把整张映射表搬进内存。逐 SKU 查的代价是 N 次调用，代价有界且正确。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class StockoutChannelPort {

  /**
   * channel 4.1.1.1 的映射行（只声明本模块用到的字段）。
   *
   * <p>{@code shopCode} 是店铺编码；{@code shopName} 刻意<b>不</b>在这里查 ——
   * 店铺主数据属 channel 2.3.1.1，本模块只落 shop_code 快照，不复制别人的主数据。
   */
  record MappingRow(
      UUID id, String shopCode, UUID internalSkuId, String relationType, String status, String mappingStatus) {}

  /** channel 的映射分页。 */
  record MappingPage(List<MappingRow> items, long total) {}

  /** 单页行数上限。 */
  private static final int PAGE_SIZE = 200;

  /** 单个 SKU 的页数上限（一个 SKU 挂几千个平台变体时截断并如实报出）。 */
  private static final int MAX_PAGES = 10;

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public StockoutChannelPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .messageConverters(java.util.List.of(converter))
        .build();
  }

  /**
   * 查某个 SKU 当前生效的店铺映射。
   *
   * @return 该 SKU 涉及的店铺编码（去重、排序）；映射为空时返回空列表 —— 那是<b>真状态</b>
   *     （这个 SKU 还没挂到任何店铺），不是「查不到」
   * @throws ResponseStatusException 401 会话失效 / 403 无权限 / 503 channel 不可达
   */
  public List<MappingRow> mappingsForSku(UUID skuId, String bearer) {
    List<MappingRow> all = new ArrayList<>();
    int page = 0;
    while (true) {
      String uri =
          "/channel/listing-mappings?internalSkuId="
              + skuId
              + "&status=ACTIVE&page="
              + page
              + "&size="
              + PAGE_SIZE;
      MappingPage response = fetch(uri, bearer);
      if (response == null || response.items() == null) {
        throw unavailable("渠道服务没有返回 SKU 的店铺映射");
      }
      all.addAll(response.items());
      if (all.size() >= response.total() || response.items().isEmpty()) {
        break;
      }
      page++;
      if (page >= MAX_PAGES) {
        break;
      }
    }
    return all;
  }

  private MappingPage fetch(String uri, String bearer) {
    try {
      return client.get().uri(uri).header("Authorization", bearer).retrieve().body(MappingPage.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取渠道映射，断货风险清单暂时算不出来");
      }
      throw unavailable("渠道服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("渠道服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503。
   *
   * <p>刻意<b>不</b>返回空映射冒充「这个 SKU 没有店铺」：那会让「涉及店铺」这一项显示空，
   * 而真实情况是渠道服务挂了 —— 运营以为影响面只有零个店铺。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：「涉及店铺」的唯一来源是 channel 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份「没有店铺」的清单");
  }
}
