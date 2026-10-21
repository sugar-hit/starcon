package com.starcon.warehouse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

/**
 * 7.4.1.1 调整单管理 ↔ 6.4.2.2 不良品处理（<b>trade 服务</b>）的跨服务只读端口。
 *
 * <p><b>6.4.2.2 的事实表归 trade 所有</b>（{@code defective_inventory_instruction}，迁移
 * {@code V256__defective_goods_handling.sql} 在 trade 库里）。AGENTS.md「模块边界纪律」禁止
 * 跨服务 SQL / FK / 视图，所以本服务：
 *
 * <ul>
 *   <li><b>不跨库读它的表</b> —— 一切经 trade 自己的授权 HTTP 端点
 *       （{@code GET /trade/defective-dispositions} 与 {@code GET /trade/defective-dispositions/{id}}）；
 *   <li><b>不在本地复制一份处置结论</b> —— 结论只在 trade 那一处产生，本服务在需要时<b>回读</b>它；
 *   <li><b>不建跨库外键</b> —— 调整单头上只留 {@code source_order_id} / {@code source_instruction_id}
 *       两个逻辑 ID 与一个单号快照。
 * </ul>
 *
 * <p><b>租户不由此端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS —— 也就是说租户隔离是<b>两道</b>
 * （warehouse 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 * 伪造的 {@code X-Tenant-Id} 头在两道里都改不动任何东西。
 *
 * <p><b>取不到就显式失败，绝不冒充「没有不良品处置单」</b>：空清单会被仓库主管读成
 * 「这批货还没登记不良品」，而真实情况是 trade 服务挂了 —— 那两件事的处理方式完全相反
 * （前者是正常业务，后者要叫人）。所以端口在 {@code /capabilities} 里如实报
 * {@code available=false} + 原因 + 归属模块，而不是给一个空数组。
 *
 * <p><b>本端口是只读的</b>：调整单不「指挥」trade 改处置单 —— 6.4.2.2 那边怎么处理是它自己的事，
 * 本模块只把「因为哪张不良品处置单，才做了这次调整」这条因果链记下来。
 */
@Service
public class DefectiveDispositionTradePort {

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段（trade 的响应比这里声明的多）。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** 探活结果的缓存窗口：能力端点不该每次都去敲 trade，但也不能缓存成「永远可用」。 */
  private static final long PROBE_TTL_MILLIS = 30_000L;

  /** 上游不可达时的归属说明（模块号 + 服务 + 原因）。 */
  public static final String OWNER_MODULE = "6.4.2.2";

  public static final String OWNER_SERVICE = "trade";

  /** 端口状态：可用 / 不可用 + 原因。 */
  public record PortStatus(boolean available, String note) {}

  private final RestClient client;

  /** 探活缓存。volatile + 单次赋值，竞态最多多探一次，不会给出错的结论。 */
  private volatile PortStatus cachedStatus;

  private volatile long cachedAt;

  public DefectiveDispositionTradePort(
      @Value("${TRADE_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    // 读超时给到 5 秒：处置单详情带指令与索赔记录，比列表慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(5));
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
   * 不良品处置单列表（6.4.2.2）。
   *
   * <p>只取列表页本身（trade 的列表端点已经带真分页），本服务不把整张单拉下来再本地分页 ——
   * 那是把上游的分页绕开，页码与总数会对不上。
   */
  @SuppressWarnings("unchecked")
  public List<InventoryAdjustmentModels.InventoryAdjustmentDefectiveSource> dispositions(
      String bearer, String keyword, int page, int size) {
    try {
      Map<String, Object> body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/trade/defective-dispositions")
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .queryParamIfPresent(
                              "keyword",
                              java.util.Optional.ofNullable(
                                  keyword == null || keyword.isBlank() ? null : keyword.trim()))
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(Map.class);
      if (body == null) {
        throw new IllegalStateException("trade 没有返回处置单列表");
      }
      Object rows = body.get("rows");
      if (!(rows instanceof List<?> list)) {
        throw new IllegalStateException("trade 返回的处置单列表形状不对");
      }
      List<InventoryAdjustmentModels.InventoryAdjustmentDefectiveSource> out = new ArrayList<>();
      for (var item : list) {
        if (!(item instanceof Map<?, ?> raw)) {
          continue;
        }
        Map<String, Object> row = (Map<String, Object>) raw;
        UUID id = uuid(row.get("id"));
        if (id == null) {
          continue;
        }
        out.add(
            new InventoryAdjustmentModels.InventoryAdjustmentDefectiveSource(
                id,
                str(row.get("dispositionNo")),
                str(row.get("status")),
                str(row.get("statusLabel")),
                uuid(row.get("skuId")),
                str(row.get("skuCode")),
                str(row.get("skuName")),
                str(row.get("batchNo")),
                decimal(row.get("remainingQuantity")),
                longOf(row.get("instructionCount"))));
      }
      return out;
    } catch (RestClientResponseException e) {
      throw translate(e, "读取不良品处置单");
    } catch (RestClientException e) {
      throw new IllegalStateException("trade 服务暂时不可达：" + e.getMessage(), e);
    }
  }

  /**
   * 一张处置单 + 它的库存处置指令。
   *
   * <p>指令才是「要调哪批货」的直接来源（{@code DefectiveInstructionRow}）：处置单头上只有
   * 汇总数量，指令才是逐条的库存状态变更要求。
   *
   * <p>一次 HTTP 往返同时拿到头与指令：建单时要核对「这条指令确实属于这张处置单」，
   * 分两次调用既慢又会出现「头读到了、指令没读到」的半截状态。
   */
  public record DefectiveDetail(
      InventoryAdjustmentModels.InventoryAdjustmentDefectiveSource order,
      List<InventoryAdjustmentModels.InventoryAdjustmentDefectiveInstruction> instructions) {}

  /** 一张处置单的详情（头 + 指令）。 */
  public DefectiveDetail detail(UUID orderId, String bearer) {
    if (orderId == null) {
      return new DefectiveDetail(null, List.of());
    }
    try {
      Map<String, Object> body =
          client
              .get()
              .uri("/trade/defective-dispositions/" + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(Map.class);
      if (body == null) {
        throw new IllegalStateException("trade 没有返回处置单详情");
      }
      Map<String, Object> order = body.get("order") instanceof Map<?, ?> m ? cast(m) : Map.of();
      String dispositionNo = str(order.get("dispositionNo"));
      UUID skuId = uuid(order.get("skuId"));
      var source =
          new InventoryAdjustmentModels.InventoryAdjustmentDefectiveSource(
              orderId,
              dispositionNo,
              str(order.get("status")),
              str(order.get("statusLabel")),
              skuId,
              str(order.get("skuCode")),
              str(order.get("skuName")),
              str(order.get("batchNo")),
              decimal(order.get("remainingQuantity")),
              longOf(order.get("instructionCount")));
      Object raw = body.get("instructions");
      if (!(raw instanceof List<?> list)) {
        return new DefectiveDetail(source, List.of());
      }
      List<InventoryAdjustmentModels.InventoryAdjustmentDefectiveInstruction> out = new ArrayList<>();
      for (var item : list) {
        if (!(item instanceof Map<?, ?> rawRow)) {
          continue;
        }
        Map<String, Object> row = cast(rawRow);
        UUID id = uuid(row.get("id"));
        if (id == null) {
          continue;
        }
        out.add(
            new InventoryAdjustmentModels.InventoryAdjustmentDefectiveInstruction(
                id,
                orderId,
                dispositionNo,
                str(row.get("action")),
                str(row.get("actionLabel")),
                decimal(row.get("quantity")),
                str(row.get("ownerModule")),
                str(row.get("status")),
                str(row.get("statusLabel")),
                str(row.get("reason")),
                str(row.get("externalReference"))));
      }
      return new DefectiveDetail(source, out);
    } catch (RestClientResponseException e) {
      throw translate(e, "读取不良品处置指令");
    } catch (RestClientException e) {
      throw new IllegalStateException("trade 服务暂时不可达：" + e.getMessage(), e);
    }
  }

  // ================================================================ 探活

  /**
   * 上游可用性（能力端点用）。
   *
   * <p>真的去敲一次 trade，而不是看配置猜 —— 「配置里有 TRADE_URL」与「trade 现在能回话」
   * 是两件事。探活结果缓存 30 秒：能力端点不该每次都去敲上游，但也不能缓存成永远可用。
   */
  public PortStatus status(String bearer) {
    long now = System.currentTimeMillis();
    PortStatus cached = cachedStatus;
    if (cached != null && now - cachedAt < PROBE_TTL_MILLIS) {
      return cached;
    }
    PortStatus fresh = probe(bearer);
    cachedStatus = fresh;
    cachedAt = now;
    return fresh;
  }

  private PortStatus probe(String bearer) {
    try {
      client
          .get()
          .uri(
              uriBuilder ->
                  uriBuilder.path("/trade/defective-dispositions").queryParam("size", 1).build())
          .header("Authorization", bearer)
          .retrieve()
          .body(Map.class);
      return new PortStatus(true, "6.4.2.2 不良品处置单（trade）可读：调整单可以引用它的处置指令作为上游来源。");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        return new PortStatus(
            false,
            "trade 服务可达但拒绝了当前身份（401）：调整单仍可独立发起，只是不能引用 6.4.2.2 的处置指令作为来源。");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        return new PortStatus(
            false,
            "当前身份在 trade 服务上没有 6.4.2.2 的读取权限（403）：调整单仍可独立发起，"
                + "只是不能引用不良品处置指令作为来源。");
      }
      return new PortStatus(
          false,
          "trade 服务返回 "
              + e.getStatusCode().value()
              + "：调整单仍可独立发起，只是不能引用 6.4.2.2 的处置指令作为来源。");
    } catch (RestClientException e) {
      return new PortStatus(
          false,
          "trade 服务（6.4.2.2 不良品处理）当前不可达：调整单仍可独立发起，"
              + "只是不能引用不良品处置指令作为上游来源。");
    }
  }

  // ================================================================ 内部

  /** 上游错误如实翻译：401 / 403 保留，404 返回 null，其余一律 503（不是「查不到」）。 */
  private RuntimeException translate(RestClientResponseException e, String what) {
    if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
      return new IllegalStateException("登录已失效，请重新登录：" + what);
    }
    if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
      return new IllegalStateException(
          "当前身份无权在 trade 服务上" + what + "：需要 6.4.2.2 不良品处置单的读取权限");
    }
    return new IllegalStateException("trade 服务返回 " + e.getStatusCode().value() + "，" + what + "失败");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> raw) {
    return (Map<String, Object>) raw;
  }

  private static UUID uuid(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof UUID u) {
      return u;
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static long longOf(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** 探活时刻，供测试断言缓存行为。 */
  Instant probedAt() {
    return Instant.ofEpochMilli(cachedAt);
  }
}
