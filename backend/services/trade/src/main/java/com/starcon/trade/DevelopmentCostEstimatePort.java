package com.starcon.trade;

import static com.starcon.trade.DevelopmentProjectModels.DevelopmentCostEstimateView;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 <b>5.3.1.1 利润测算器</b>事实的跨服务端口 —— 「这个新品的**目标成本**是多少」
 * 这个事实的<b>唯一系统来源</b>。
 *
 * <p>蓝图 6.6.1.1 的输入句写死了「目标定价与成本估算（<b>5.3.1.1</b>）」，而 5.3.1.1
 * 利润测算器在 <b>catalog 服务</b>的库里（迁移 V227，端点 {@code POST /catalog/profit-calculator/calculate}）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键 —— 与 {@link BrandQueryPort}（读 3.3.2 品牌）
 * 逐字同构。
 *
 * <p><b>为什么只取「采购成本」这一行</b>：利润测算器返回的是一张**瀑布图**（售价 + 七项扣减 + 毛利），
 * 其中 {@code PURCHASE}（采购成本）才是本模块要的**目标成本**。本模块自己的目标毛利率
 * 由 {@link DevelopmentProjectRules#targetMarginRate} 按 {@code (售价 − 成本) ÷ 售价} 算，
 * **不复用**利润测算器自己的毛利率 —— 那是另一个口径（它扣的是七项，扣全了才给数），
 * 两个口径混用会让「这个立项的毛利率」和「这个 SKU 的毛利率」看起来是同一个数。
 *
 * <p><b>🔴 取不到就如实说取不到</b>：{@link DevelopmentCostEstimateView#available()} 为 false 时
 * {@code unitCost} 必为 null，且 {@code ownerModule} 点名归谁管。**绝不**拿 0 顶替 ——
 * 成本 0 的毛利率是 100%，是彻头彻尾的假数据。
 *
 * <p><b>🔴 跨币种绝不相加</b>：5.2.2.1 汇率查询在本仓<b>未交付</b> ⇒ 成本币种与售价币种不同时
 * 一律判为「取不到」，并说明缺的是汇率（而不是偷偷按 1:1 折算）。
 *
 * <p>租户<b>不</b>由本端口传递：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class DevelopmentCostEstimatePort {

  /** catalog 瀑布图里「采购成本」那一行的 code（5.3.1.1 的固定取值）。 */
  private static final String PURCHASE_LINE = "PURCHASE";

  /** 成本取不到时点名的归管模块（5.2.2.1 汇率查询未交付 ⇒ 跨币种不可比）。 */
  static final String FX_OWNER_MODULE = "5.2.2.1 汇率查询";

  private final RestClient client;

  public DevelopmentCostEstimatePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 取一个参考 SKU 的单位成本估算。
   *
   * @param referenceSkuId 参考 SKU（成本基准）
   * @param targetPrice 本立项的目标售价（利润测算器按售价模式跑）
   * @param currency 本立项的售价币种
   * @param bearer 调用方自己的 bearer（原样转发给 catalog）
   */
  public DevelopmentCostEstimateView estimate(
      UUID referenceSkuId, BigDecimal targetPrice, String currency, String bearer) {
    if (referenceSkuId == null) {
      return unavailable(
          "5.3.1.1 利润测算器",
          "本立项没有挂参考 SKU —— 目标成本只能手工估算，或先挂一个既有 SKU 作为成本基准");
    }
    CalculateResponse response;
    try {
      response =
          client
              .post()
              .uri("/catalog/profit-calculator/calculate")
              .header("Authorization", bearer)
              .body(
                  new CalculateRequest(
                      referenceSkuId, currency, "PRICE", targetPrice))
              .retrieve()
              .body(CalculateResponse.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取成本构成，目标成本暂时取不到");
      }
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "参考 SKU 不存在："
                + referenceSkuId
                + "。参考 SKU 是成本基准的来源，请先确认它在商品主数据里确实存在");
      }
      throw unavailableException(
          "商品主数据服务返回 " + e.getStatusCode().value() + "，目标成本暂时取不到");
    } catch (RestClientException e) {
      throw unavailableException("商品主数据服务暂时不可达");
    }
    if (response == null || response.waterfall() == null) {
      throw unavailableException("商品主数据服务没有返回成本瀑布图");
    }
    return fromWaterfall(response, currency);
  }

  /** 从瀑布图里取「采购成本」那一行，组装成成本估算视图。 */
  private DevelopmentCostEstimateView fromWaterfall(CalculateResponse response, String currency) {
    WaterfallLine purchase =
        response.waterfall().stream()
            .filter(line -> line != null && PURCHASE_LINE.equals(line.code()))
            .findFirst()
            .orElse(null);
    if (purchase == null) {
      return unavailable(
          "5.3.1.1 利润测算器",
          "利润测算器的瀑布图里没有「采购成本」这一行 —— 目标成本取不到");
    }
    if (!purchase.available() || purchase.amount() == null) {
      String reason =
          purchase.unavailableReason() == null || purchase.unavailableReason().isBlank()
              ? "该 SKU 的采购成本取不到"
              : purchase.unavailableReason();
      return unavailable("5.3.1.1 利润测算器", reason);
    }
    String costCurrency = purchase.currency() == null ? currency : purchase.currency();
    if (!currency.equals(costCurrency)) {
      // 🔴 跨币种绝不相加：汇率（5.2.2.1）未交付 ⇒ 判为取不到，而不是按 1:1 折算。
      return unavailable(
          FX_OWNER_MODULE,
          "采购成本的计价币种（"
              + costCurrency
              + "）与本立项的售价币种（"
              + currency
              + "）不同，而汇率查询模块尚未交付 —— 跨币种绝不相加，本模块不按 1:1 折算");
    }
    return new DevelopmentCostEstimateView(
        true,
        purchase.amount(),
        costCurrency,
        "CATALOG_ESTIMATE",
        "5.3.1.1 利润测算器（catalog）",
        null,
        null,
        purchase.basis() == null || purchase.basis().isBlank()
            ? "采购成本取自 5.3.1.1 利润测算器瀑布图的「采购成本」行"
            : purchase.basis(),
        response.costEffectiveFrom());
  }

  /** 取不到时的视图（available=false + 点名归谁管）。 */
  static DevelopmentCostEstimateView unavailable(String ownerModule, String reason) {
    return new DevelopmentCostEstimateView(
        false, null, null, "UNAVAILABLE", "取不到", ownerModule, reason, null, null);
  }

  private ResponseStatusException unavailableException(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：目标成本的唯一系统来源是 catalog 的 5.3.1.1 利润测算器"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空生成一个成本数字");
  }

  // ---- catalog 侧的请求 / 响应形状（只声明本模块用到的字段） ----

  /** 利润测算器的测算请求（只声明本模块用到的字段）。 */
  record CalculateRequest(UUID skuId, String currency, String mode, BigDecimal inputPrice) {}

  /** 利润测算器的测算响应。 */
  record CalculateResponse(
      UUID skuId,
      String currency,
      String decisionBasis,
      OffsetDateTime costEffectiveFrom,
      List<WaterfallLine> waterfall) {}

  /** 瀑布图的一行。 */
  record WaterfallLine(
      String code,
      String label,
      boolean available,
      boolean partial,
      BigDecimal amount,
      BigDecimal totalAmount,
      String currency,
      String unavailableReason,
      String basis) {}
}
