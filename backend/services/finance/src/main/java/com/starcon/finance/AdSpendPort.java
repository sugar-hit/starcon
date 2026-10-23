package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **14.3.1.1 活动报表**事实的**跨服务只读端口**（channel 服务，端口 8083）。
 *
 * <p>蓝图 12.1.2.1 的输入写的是「服务费用（物流、平台、广告）」。广告这一段的可产生来源是活动花费：
 * 平台按活动报回来的<b>花费</b>（{@code spend}）带<b>币种</b>与<b>平台</b> ——
 * 「应付给谁、多少钱、什么币种」这三样齐了。
 *
 * <p>⚠️ <b>口径纪律</b>：本模块取的是**花费（spend）**，不是销售额（sales）。
 * 销售额是收入侧的事（12.1.1.1 应收），把它当应付会把一笔收入记成一笔负债。
 *
 * <p>用法与既有端口逐字同构：取不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有数据」。
 */
@Service
public class AdSpendPort {

  /** 上游只读形状：{@code GET /channel/ad-reports/rows}（14.3.1.1 活动报表）。 */
  static final String AD_REPORT_PATH = "/channel/ad-reports/rows";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  /** 上游一行广告报表（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AdReportRow(
      @JsonIgnoreProperties(ignoreUnknown = true) String dimension,
      @JsonIgnoreProperties(ignoreUnknown = true) String dimensionKey,
      @JsonIgnoreProperties(ignoreUnknown = true) String dimensionLabel,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) String platformName,
      @JsonIgnoreProperties(ignoreUnknown = true) String shopName,
      @JsonIgnoreProperties(ignoreUnknown = true) String externalCampaignId,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal spend) {}

  /** 上游一页广告报表。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AdReportPage(List<AdReportRow> items, int page, int size, long total) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public AdSpendPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游广告花费。
   *
   * <p>分组维度固定 {@code CAMPAIGN}（按活动）：按活动分组才有「一笔花费对应一个对象」的粒度，
   * 按天分组会把同一个活动拆成很多条应付。
   */
  public AdReportPage rows(int page, String bearer) {
    try {
      AdReportPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(AD_REPORT_PATH)
                          .queryParam("dimension", "CAMPAIGN")
                          .queryParam("granularity", "DAY")
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(AdReportPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("channel 服务没有返回广告报表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游广告花费事实（需要 channel 侧的 channel.operation.read 权限）",
            e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：广告花费事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /**
   * 把一行广告报表压成上游事实形状。
   *
   * <p>⚠️ **花费为 0 的行不产生应付**：没有消耗就没有要付的钱，返回 {@code null} 由调用方跳过。
   */
  public static PayableSourceFact toFact(AdReportRow row) {
    if (row.spend() == null || row.spend().signum() <= 0) return null;
    if (row.currency() == null || row.currency().isBlank()) return null;
    String platform =
        row.platformName() == null || row.platformName().isBlank()
            ? (row.shopName() == null || row.shopName().isBlank() ? "广告平台" : row.shopName())
            : row.platformName();
    String reference =
        row.externalCampaignId() != null && !row.externalCampaignId().isBlank()
            ? row.externalCampaignId()
            : row.dimensionKey();
    if (reference == null || reference.isBlank()) return null;
    String description =
        "广告花费 "
            + (row.dimensionLabel() == null || row.dimensionLabel().isBlank()
                ? reference
                : row.dimensionLabel());
    return new PayableSourceFact(
        reference,
        null,
        PayableModels.CounterpartyType.PLATFORM,
        null,
        platform,
        row.currency(),
        row.spend(),
        null,
        null,
        description,
        null,
        "由 14.3.1.1 活动报表导入");
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
