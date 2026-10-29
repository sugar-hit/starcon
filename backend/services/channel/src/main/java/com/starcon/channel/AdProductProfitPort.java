package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 14.3.1.3 商品维度报表的**利润侧跨服务只读端口**：上游 = **13.4.1.2 SKU 利润**（reporting 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经授权只读 API</b>读
 * {@code GET /reporting/sku-profit/report}（13.4.1.2 已交付）。本模块<b>不建</b>利润事实的影子表 ——
 * 影子表意味着同一笔钱有两个真相，广告与财务对账时必然吵架。
 *
 * <p>🔴 <b>不传租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * reporting 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 <b>上游读不到 = 降级并说明原因，不是空集合</b>：
 * 本模块的广告侧（花费 / 广告销售额 / ACOS）全部来自本服务自己的事实表，
 * <b>利润侧读不到时那一段取不到</b>，但广告侧仍然可用 —— 所以这里返回
 * {@code available=false} + 一句原因，而不是抛 503 把整张报表打掉。
 * 与 {@link AdDaypartSourcePort} 的差别是刻意的：那里推荐曲线**完全依赖**上游，
 * 读不到只能 503；这里只是<b>加了一段</b>。
 *
 * <p>🔴 <b>「上游说没有」与「上游挂了」必须分开</b>：
 * 上游成功返回但窗口内没有 SKU 级利润事实 ⇒ {@code available=true} 且 {@code rows} 为空
 * （那一句是「还没投影」，**不是「利润为 0」**）；上游不可达 / 无权限 ⇒ {@code available=false}。
 */
@Service
public class AdProductProfitPort {

  /** reporting 服务默认端口（见 backend/services/reporting/src/main/resources/application.yml）。 */
  static final String REPORTING_URL = "http://127.0.0.1:8088";

  /** 13.4.1.2 SKU 利润表的读端点。 */
  static final String SKU_PROFIT_PATH = "/reporting/sku-profit/report";

  /** 一次拉取的上游超时（秒）。 */
  private static final int READ_TIMEOUT_SECONDS = 15;

  // ================================================================ 内部形状

  /** 上游的一个成本项 / 收入项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ComponentRow(
      String component, BigDecimal amount, BigDecimal quantity, boolean available) {}

  /** 上游报表的一行（一个 SKU × 一个币种）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuProfitRow(
      String skuCode,
      String currency,
      List<ComponentRow> components,
      BigDecimal totalCost,
      BigDecimal netProfit,
      boolean netProfitComparable) {}

  /** 上游的主响应（本端口只取真用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProfitResponse(
      List<SkuProfitRow> rows, Long skuCount, OffsetDateTime dataUpdatedAt, List<String> warnings) {}

  // ================================================================ 对外形状

  /**
   * 一个 SKU × 一个币种的利润事实（**只有本模块真用到的四项**）。
   *
   * @param revenue 单品收入（13.4.1.2 的 REVENUE 项；拿不到就是 null，**不是 0**）
   * @param revenueQuantity 单品收入对应的件数（null = 上游没给）
   * @param adFee 利润口径的广告费（13.4.1.2 的 AD_FEE 项；null = 拿不到）
   * @param netProfit 净利（null = 成本项缺项 ⇒ 不可比）
   */
  public record SkuProfit(
      String skuCode,
      String currency,
      BigDecimal revenue,
      BigDecimal revenueQuantity,
      BigDecimal adFee,
      BigDecimal totalCost,
      BigDecimal netProfit,
      boolean netProfitComparable) {}

  /**
   * 一次利润侧的读取结果。
   *
   * @param available 上游这次**有没有答**（false ⇒ 原因在 {@code unavailableReason}）
   * @param bySkuAndCurrency 键 = {@code skuCode + "|" + currency}（**币种在键里**，绝不跨币种相加）
   * @param dataUpdatedAt 上游的数据更新时间（上游没投影过任何事实时为 null）
   * @param unavailableReason 上游读不到时的原因（读到了则为空串）
   */
  public record ProfitSnapshot(
      boolean available,
      Map<String, SkuProfit> bySkuAndCurrency,
      OffsetDateTime dataUpdatedAt,
      String unavailableReason) {}

  // ================================================================ 客户端

  private final RestClient reporting;

  public AdProductProfitPort(@Value("${REPORTING_URL:" + REPORTING_URL + "}") String reportingUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS));
    this.reporting = RestClient.builder().baseUrl(reportingUrl).requestFactory(factory).build();
  }

  /**
   * 拉窗口内的 SKU 级利润事实。
   *
   * @param from 窗口起
   * @param to 窗口止（含）
   * @param bearer 调用方自己的会话（转发给上游，**不传租户号**）
   */
  public ProfitSnapshot fetch(LocalDate from, LocalDate to, String bearer) {
    ProfitResponse response;
    try {
      response =
          reporting
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SKU_PROFIT_PATH)
                          .queryParam("from", from.toString())
                          .queryParam("to", to.toString())
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(ProfitResponse.class);
    } catch (RestClientResponseException e) {
      return unavailable(
          e.getStatusCode().value() == 403
              ? "上游 13.4.1.2（reporting 服务）返回 403：当前账号没有 reporting.sku-profit.read 权限，"
                  + "利润侧（总销售额 / TACOS / 广告销售占比 / 广告后净利）这次取不到。"
              : "上游 13.4.1.2（reporting 服务）返回 " + e.getStatusCode().value() + "，"
                  + "利润侧（总销售额 / TACOS / 广告销售占比 / 广告后净利）这次取不到。");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return unavailable(
          "上游 13.4.1.2（reporting 服务）不可达，利润侧（总销售额 / TACOS / 广告销售占比 / 广告后净利）"
              + "这次取不到。广告侧（花费 / 广告销售额 / ACOS）不受影响。");
    }
    if (response == null) {
      return unavailable("上游 13.4.1.2（reporting 服务）没有返回内容，利润侧这次取不到。");
    }

    Map<String, SkuProfit> rows = new LinkedHashMap<>();
    for (SkuProfitRow row : response.rows() == null ? List.<SkuProfitRow>of() : response.rows()) {
      if (row == null || row.skuCode() == null || row.skuCode().isBlank()) continue;
      String currency = row.currency() == null ? "" : row.currency();
      BigDecimal revenue = null;
      BigDecimal quantity = null;
      BigDecimal adFee = null;
      for (ComponentRow component : row.components() == null ? List.<ComponentRow>of() : row.components()) {
        if (component == null || !component.available()) continue;
        if ("REVENUE".equals(component.component())) {
          revenue = component.amount();
          quantity = component.quantity();
        } else if ("AD_FEE".equals(component.component())) {
          adFee = component.amount();
        }
      }
      rows.put(
          row.skuCode() + "|" + currency,
          new SkuProfit(
              row.skuCode(),
              currency,
              revenue,
              quantity,
              adFee,
              row.totalCost(),
              row.netProfit(),
              row.netProfitComparable()));
    }
    // 🔴 上游答了就是「答了」；窗口内没有事实是**另一种空**，由 rows 为空表达。
    return new ProfitSnapshot(true, rows, response.dataUpdatedAt(), "");
  }

  private static ProfitSnapshot unavailable(String reason) {
    return new ProfitSnapshot(false, Map.of(), null, reason);
  }
}
