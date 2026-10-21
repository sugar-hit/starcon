package com.starcon.warehouse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * 8.2.2.2 待检处理 ↔ 6.4.2.1 质检单（<b>trade 服务</b>）的跨服务端口。
 *
 * <p><b>质检单的事实表归 trade 所有</b>（{@code quality_inspection_order}，迁移 V249__quality_inspection_order.sql 在 trade 库里）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 视图，所以本服务：
 *
 * <ul>
 *   <li><b>不在本地建质检单表</b> —— 只留逻辑 ID 与可读单号快照（{@code inspection_order_id} / {@code inspection_order_code}）；
 *   <li><b>不跨库读写对方表</b> —— 一切经 trade 自己的授权 HTTP 端点；
 *   <li><b>不在本地复制一份质检结论</b> —— 结论只在 trade 那一处产生，本服务在需要时<b>回读</b>它并冻结成快照。
 * </ul>
 *
 * <p><b>为什么「发起质检」也走 HTTP 而不是本地建表</b>：待检处理单的职责是「发起质检任务（6 质检单）」，
 * 而 6 质检单这个事实的写入所有者是 trade。warehouse 发起的动作是一次<b>请求</b>：
 * 由 trade 用调用方自己的 bearer 建单、落它自己的表、回它自己的单号。
 * 这与本仓既有的做法同构（trade → warehouse 预占、catalog → identity 审批实例，都是跨服务写「对方拥有的事实」）。
 *
 * <p><b>租户不由此端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS —— 也就是说租户隔离是<b>两道</b>
 * （warehouse 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 * 伪造的 {@code X-Tenant-Id} 头在两道里都改不动任何东西。
 *
 * <p><b>取不到就显式失败，绝不冒充「没有质检单」</b>：空清单会被仓库主管读成「这批货还没检」，
 * 而真实情况是 trade 服务挂了 —— 那两件事的处理方式完全相反（前者是正常业务，后者要叫人）。
 */
@Service
public class QualityInspectionTradePort {

  /** 质检模板（只读）。 */
  public record TemplateView(
      UUID id, String code, String name, String inspectionType, String inspectionTypeLabel) {}

  /** 质检单（只读）。 */
  public record OrderView(
      UUID id,
      String inspectionNo,
      String status,
      String result,
      BigDecimal qualifiedQuantity,
      BigDecimal defectiveQuantity,
      BigDecimal concessionQuantity,
      BigDecimal sellableQuantity,
      BigDecimal nonSellableQuantity,
      BigDecimal lotSize,
      String receiptCode,
      String receiptLineId,
      String disposition,
      String dispositionStatus) {}

  /** 质检单列表响应（trade 的 IncomingOrderPage）。 */
  public record OrderPage(List<Map<String, Object>> rows, long total, int page, int size) {}

  /** 建质检单的请求体（字段名与 trade 的 IncomingOrderInput 对齐）。 */
  public record CreateRequest(
      UUID templateId,
      String skuCode,
      UUID skuId,
      String skuName,
      String receiptCode,
      UUID receiptLineId,
      String batchNo,
      BigDecimal lotSize,
      String note) {}

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段（trade 的响应比这里声明的多）。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public QualityInspectionTradePort(
      @Value("${TRADE_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：质检单详情带逐项结论与让步记录，比列表慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  // ================================================================ 读

  /**
   * 质检模板列表（建单时要选模板）。
   *
   * <p>返回空列表的语义是「trade 上确实一个模板都没有」；trade 不可达一律抛 503。
   */
  @SuppressWarnings("unchecked")
  public List<TemplateView> templates(String bearer) {
    try {
      Map<String, Object> body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/trade/quality-inspections/templates")
                          .queryParam("page", 0)
                          .queryParam("size", 100)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(Map.class);
      if (body == null) {
        throw unavailable("质检服务没有返回模板列表");
      }
      Object rows = body.get("rows");
      if (!(rows instanceof List<?> list)) {
        throw unavailable("质检服务返回的模板列表形状不对");
      }
      return list.stream()
          .filter(Map.class::isInstance)
          .map(item -> (Map<String, Object>) item)
          .map(
              item ->
                  new TemplateView(
                      uuid(item.get("id")),
                      str(item.get("templateCode")),
                      str(item.get("templateName")),
                      str(item.get("inspectionType")),
                      str(item.get("inspectionTypeLabel"))))
          .filter(t -> t.id() != null)
          .toList();
    } catch (RestClientResponseException e) {
      throw translate(e, "读取质检模板");
    } catch (RestClientException e) {
      throw unavailable("质检服务暂时不可达");
    }
  }

  /**
   * 读一张质检单（含三量结论）。
   *
   * <p>返回 {@code null} 表示「质检服务上没有这一张单」；上游不可达一律抛 503。
   */
  public OrderView order(UUID orderId, String bearer) {
    if (orderId == null) {
      return null;
    }
    try {
      OrderView view =
          client
              .get()
              .uri("/trade/quality-inspections/" + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderView.class);
      if (view == null) {
        throw unavailable("质检服务没有返回质检单详情");
      }
      return view;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return null;
      }
      throw translate(e, "读取质检单");
    } catch (RestClientException e) {
      throw unavailable("质检服务暂时不可达");
    }
  }

  // ================================================================ 写

  /**
   * 发起质检任务：在 trade 侧建一张 6.4.2.1 质检单。
   *
   * <p>本服务<b>不</b>决定「检什么、抽多少」——那是模板与抽样规则的职责，都在 trade 侧。
   * 本服务只给三样它真正知道的事实：<b>哪个 SKU</b>、<b>来自哪张收货单的哪一行</b>、<b>本次检多少</b>。
   *
   * <p>trade 侧会自己回读收货单拿实收量（它不认客户端报的数量），所以本服务传过去的
   * {@code lotSize} 必须 ≤ 收货明细行实收量 —— 服务层在发起前已校验过。
   */
  public OrderView create(CreateRequest request, String bearer) {
    try {
      OrderView view =
          client
              .post()
              .uri("/trade/quality-inspections")
              .header("Authorization", bearer)
              .body(request)
              .retrieve()
              .body(OrderView.class);
      if (view == null || view.id() == null) {
        throw unavailable("质检服务没有返回质检单");
      }
      return view;
    } catch (RestClientResponseException e) {
      throw translate(e, "发起质检任务");
    } catch (RestClientException e) {
      throw unavailable("质检服务暂时不可达");
    }
  }

  /**
   * 作废质检单（trade 侧）。
   *
   * <p>本服务取消一张「已发起质检」的待检处理单时，必须**同时**把这张质检单作废 ——
   * 否则 trade 里会留下一张永远不会被判的质检单，而仓库这边显示「已取消」，
   * 两边对不上账（谁也说不清这批货到底检没检）。
   */
  public void cancel(UUID orderId, String reason, String bearer) {
    if (orderId == null) {
      return;
    }
    try {
      client
          .post()
          .uri("/trade/quality-inspections/" + orderId + "/cancel")
          .header("Authorization", bearer)
          .body(Map.of("cancelReason", reason))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw translate(e, "作废质检单");
    } catch (RestClientException e) {
      throw unavailable("质检服务暂时不可达");
    }
  }

  // ================================================================ 内部

  /** 上游错误如实翻译：401 / 403 保留，404 在调用处单独处理，其余一律 503（不是「查不到」）。 */
  private ResponseStatusException translate(RestClientResponseException e, String what) {
    if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
      return new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "当前身份无权在质检服务（trade）上"
              + what
              + "：发起质检任务需要 6.4.2.1 质检单的写入权限");
    }
    return unavailable("质检服务返回 " + e.getStatusCode().value() + "，" + what + "失败");
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：6.4.2.1 质检单的唯一所有者是 trade 服务"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一张质检单或一份质检结论");
  }

  private static UUID uuid(Object value) {
    return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
