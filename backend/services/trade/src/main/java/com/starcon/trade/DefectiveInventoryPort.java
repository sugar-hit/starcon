package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
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

/**
 * 6.4.2.2 的**库存状态变更指令**派发端口。
 *
 * <p>蓝图 6.4.2.2 的输出句写的是「处置单 + <b>库存状态变更指令</b> + 供应商索赔记录」——
 * 措辞是**指令**而不是「改库存」。而库存分录的写入所有权在 warehouse（7 库存 / 8.2.2.2 待检处理）：
 * AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 视图，所以本模块<b>只发指令、不建账</b>。
 *
 * <p><b>🔴 本仓当前的真实状态：warehouse 没有可复用的处置写入口。</b>
 * 已交付的 {@code /warehouse/pending-inspections/**} 全部挂在 warehouse <b>自有的</b>
 * {@code pending_inspection_task} 上 —— 建单要 {@code source_bucket_id}（stock_bucket.id）与
 * {@code receipt_line_id}（goods_receipt_line.id），处置要 {@code target_quality_status_id} /
 * {@code target_location_id}，全是 warehouse 库里的内部外键，而且那张单**必须**挂在一条真实的
 * 收货行上。trade 侧没有「按质检结论把货登记到残次 bucket」的授权写入口。
 *
 * <p>⇒ 所以本端口的行为分两种，**都由代码如实执行，不假装已派发**：
 *
 * <ol>
 *   <li><b>未配置对端</b>（{@code DEFECTIVE_INVENTORY_URL} 为空 —— 本仓的默认状态）⇒
 *       指令状态落 {@code UNAVAILABLE}，并写明「缺的是对端哪个端点」。</li>
 *   <li><b>已配置对端</b> ⇒ 真的发一次 HTTP POST；2xx 落 {@code DISPATCHED} 并带回对端回执，
 *       其它落 {@code FAILED} 并写明失败原因。</li>
 * </ol>
 *
 * <p>租户<b>不</b>由本端口传递：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS —— 隔离是<b>两道</b>，
 * 而不是「trade 替对方决定租户」。
 */
@Service
public class DefectiveInventoryPort {

  /** 一次派发的结果。 */
  public record DispatchOutcome(
      DefectiveGoodsModels.DefectiveInventoryStatus status, String reason, String externalReference) {}

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;
  private final String baseUrl;

  public DefectiveInventoryPort(@Value("${DEFECTIVE_INVENTORY_URL:}") String baseUrl) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(this.baseUrl.isEmpty() ? "http://127.0.0.1:0" : this.baseUrl)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /** 对端是否已配置。没配置 ⇒ 派发结果恒为 {@code UNAVAILABLE}。 */
  public boolean configured() {
    return !baseUrl.isEmpty();
  }

  /**
   * 派发一条库存状态变更指令。
   *
   * <p>返回的 {@link DispatchOutcome} 是<b>事实</b>：没发出去就是没发出去，
   * 绝不返回「已登记」而其实上游什么都没收到。
   */
  public DispatchOutcome dispatch(
      UUID orderId,
      String dispositionNo,
      String inspectionNo,
      String action,
      BigDecimal quantity,
      String bearer) {
    if (!configured()) {
      return new DispatchOutcome(
          DefectiveGoodsModels.DefectiveInventoryStatus.UNAVAILABLE,
          "warehouse 侧未提供「按处置单变更库存状态」的授权写端点。"
              + "已交付的 /warehouse/pending-inspections/** 全部挂在 warehouse 自有的待检处理单上"
              + "（建单要 stock_bucket.id 与 goods_receipt_line.id，处置要目标质量状态与库位，"
              + "都是 warehouse 库里的内部外键），trade 侧没有可复用的入口。"
              + "本模块只落指令、不建账 —— 绝不出现「已登记」却其实没动库存。",
          "");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("dispositionOrderId", orderId.toString());
    payload.put("dispositionNo", dispositionNo);
    payload.put("inspectionNo", inspectionNo);
    payload.put("action", action);
    payload.put("quantity", quantity);
    try {
      Map<String, Object> body =
          client
              .post()
              .uri("/warehouse/inventory/dispositions")
              .header("Authorization", bearer == null ? "" : bearer)
              .body(payload)
              .retrieve()
              .body(Map.class);
      String reference = body == null ? "" : String.valueOf(body.getOrDefault("reference", ""));
      return new DispatchOutcome(
          DefectiveGoodsModels.DefectiveInventoryStatus.DISPATCHED,
          "已向 warehouse 登记库存状态变更指令。",
          reference);
    } catch (RestClientResponseException e) {
      return new DispatchOutcome(
          DefectiveGoodsModels.DefectiveInventoryStatus.FAILED,
          "warehouse 拒绝了这条库存状态变更指令（HTTP "
              + e.getStatusCode().value()
              + "）：库存分录的写入所有权在 warehouse，本模块不代它建账。",
          "");
    } catch (RestClientException e) {
      return new DispatchOutcome(
          DefectiveGoodsModels.DefectiveInventoryStatus.FAILED,
          "warehouse 服务暂时不可达，库存状态变更指令没有送出去。",
          "");
    }
  }

  /** 未配置时的对外说明（能力声明用）。 */
  public String unavailableReason() {
    return "未配置 DEFECTIVE_INVENTORY_URL：warehouse 侧当前没有「按处置单变更库存状态」的授权写端点。";
  }

  /** 供健康检查用：本端口当前是否可派发。 */
  public HttpStatus probe() {
    return configured() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
  }
}
