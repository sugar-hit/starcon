package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 读取 **finance 服务 12.6.1.1 税率配置**的**跨服务只读端口**。
 *
 * <p>蓝图 16.3.2.1 的「税率适用」这一项的真实归属是 finance 服务的
 * {@code finance_tax_rate}（V361，12.6.1.1 税率配置），端点
 * {@code GET /finance/tax-rates/resolve} —— 它**按生效时间取税率**，
 * **命中不到一律报错**，不退回最新一条、不按 0% 处理。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 finance 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * finance 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>。
 *
 * <p>🔴 <b>读不到就是读不到</b>：本端口**不抛异常、也不返回 0%**，而是返回一个
 * {@code available=false} 的结果并写明原因（上游不可达 / 命中不到 / 无权限）。
 * 上层据此把 {@code tax_rate} 留 <b>null</b> 并标 {@code tax_rate_source='UNAVAILABLE'} ——
 * <b>0% 是最危险的数字</b>：它会让「税率读不到」看起来像「免税」。
 *
 * <p>用法与既有 {@link SalesTaxTransactionPort}（读 finance）、{@link TradeFreightQueryPort}
 * （读 trade）逐字同构：转发调用方自己的 bearer，读不到就<b>显式说读不到</b>。
 */
@Service
public class FinanceTaxPort {

  /** 上游只读形状：{@code GET /finance/tax-rates/resolve}（12.6.1.1 税率配置）。 */
  static final String RESOLVE_PATH = "/finance/tax-rates/resolve";

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * 一次税率解析的结果。
   *
   * @param available 🔴 读得到吗（false ⇒ {@code rate} 为 null，**绝不是 0%**）
   * @param rate 税率（null = 读不到）
   * @param taxType 税种
   * @param jurisdictionCode 辖区码
   * @param jurisdictionName 辖区名
   * @param resolvedBy 命中方式（SHOP_BINDING / JURISDICTION）
   * @param asOf 取税率的时点
   * @param impact 读不到 / 命中不到的影响说明（**界面上原样显示**）
   */
  public record TaxResolution(
      boolean available,
      BigDecimal rate,
      String taxType,
      String jurisdictionCode,
      String jurisdictionName,
      String resolvedBy,
      OffsetDateTime asOf,
      String impact) {

    /** 读不到时的结果（**rate 恒为 null**）。 */
    static TaxResolution unavailable(String taxType, String jurisdictionCode, String impact) {
      return new TaxResolution(false, null, taxType, jurisdictionCode, "", "", null, impact);
    }
  }

  private final RestClient client;

  public FinanceTaxPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 按生效时间取税率。
   *
   * @param taxType 税种（VAT）
   * @param asOf 取税率的时点（**按这一刻生效的税率**）
   * @param shopId 店铺 ID（可空 —— 有店铺绑定时优先按店铺命中）
   * @param jurisdictionCode 辖区码（可空）
   * @param bearer 调用方自己的会话（租户由 finance 侧按已认证成员关系解析）
   */
  public TaxResolution resolve(
      String taxType, OffsetDateTime asOf, UUID shopId, String jurisdictionCode, String bearer) {
    String type = taxType == null || taxType.isBlank() ? "VAT" : taxType.trim();
    try {
      ResolutionBody body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(RESOLVE_PATH)
                          .queryParam("taxType", type)
                          .queryParam("asOf", asOf == null ? null : asOf.toString())
                          .queryParam("shopId", shopId)
                          .queryParam("jurisdictionCode", jurisdictionCode)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(ResolutionBody.class);
      if (body == null || body.rate() == null) {
        return TaxResolution.unavailable(
            type,
            jurisdictionCode,
            "finance 服务没有返回税率 —— **读不到**，不是 0%，也不是免税。");
      }
      return new TaxResolution(
          true,
          body.rate(),
          body.taxType() == null ? type : body.taxType(),
          body.jurisdictionCode() == null ? "" : body.jurisdictionCode(),
          body.jurisdictionName() == null ? "" : body.jurisdictionName(),
          body.resolvedBy() == null ? "" : body.resolvedBy(),
          body.asOf() == null ? asOf : body.asOf(),
          "已按生效时点取到税率。");
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 404) {
        return TaxResolution.unavailable(
            type,
            jurisdictionCode,
            "finance 服务按该生效时点**命中不到税率**（404）—— **读不到**，不是 0%，也不是免税。");
      }
      if (status == 401 || status == 403) {
        return TaxResolution.unavailable(
            type,
            jurisdictionCode,
            "调用方无权读取 finance 的税率配置（" + status + "）—— **读不到**，不是 0%。");
      }
      return TaxResolution.unavailable(
          type,
          jurisdictionCode,
          "finance 服务返回 " + status + " —— 税率**读不到**，不是 0%。");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return TaxResolution.unavailable(
          type, jurisdictionCode, "finance 服务不可达 —— 税率**读不到**，不是 0%，也不是免税。");
    }
  }

  /** 上游返回体（只声明本模块真正用到的字段）。 */
  record ResolutionBody(
      String resolvedBy,
      String taxType,
      String jurisdictionCode,
      String jurisdictionName,
      BigDecimal rate,
      OffsetDateTime asOf) {}
}
