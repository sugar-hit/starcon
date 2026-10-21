package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * 6.2.1.1 断货风险读库存与在途的**跨服务端口**。
 *
 * <p>蓝图本模块的四个输入里有两个来自 warehouse：<b>当前可用库存</b>与<b>在途量</b>。它们在
 * warehouse 服务的库存账里，而 AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * <b>只经 warehouse 的授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用的两个已交付形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory/sellable/skus} —— 7.1.1.3 可用量计算（已交付）。
 *       本模块取其中的 {@code sellable}（可售量）作为<b>有效可用量</b>：可售量是全系统的单一口径来源
 *       （warehouse 的原文），本模块**不重新实现一遍} available − buffer，否则必然与防超卖的那个数漂移。
 *   <li>{@code GET /warehouse/inventory/in-transit} —— 7.5.1.2 在途库存（已交付，逐腿一行）。
 *       本模块要判「备货周期内能不能到」，所以取<b>逐腿</b>的 ETA，而不是净额聚合 ——
 *       聚合口径（{@code /in-transit/netting}）只给 {@code latestExpectedArriveAt}，
 *       分不出「多少量在备货周期内到、多少量更晚才到」。</li>
 * </ul>
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>（trade 侧 RLS +
 * warehouse 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>取不到就<b>显式失败</b>（503），绝不返回空集合冒充「仓库没有库存」—— 那会让断货清单显示
 * 「全部健康」，而真实情况是仓库不可达。
 */
@Service
public class StockoutInventoryPort {

  /**
   * 7.1.1.3 的分 SKU 可用量行（字段名逐字对齐 warehouse 的 {@code AvailableQuantityRow}）。
   *
   * <p>只声明本模块真正用到的字段；对方加字段不该把本模块撑爆（见类注释里的 ObjectMapper 选择）。
   */
  record SellableRow(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellableBeforeBuffer,
      BigDecimal safetyBuffer,
      BigDecimal sellable) {}

  /** 7.5.1.2 的在途腿（一行一条腿，含 ETA）。 */
  record TransitLegRow(
      UUID id,
      String transitKind,
      UUID skuId,
      String skuCode,
      BigDecimal outstandingQuantity,
      OffsetDateTime expectedArriveAt,
      String sourceSystem,
      String sourceReference) {}

  /**
   * 分页读取的结果。
   *
   * @param truncated 是否被页数上限截断。true 表示<b>清单不完整</b>，
   *     界面必须如实报出 —— 一个被悄悄截断的断货清单比一个明确报出截断的清单危险得多
   */
  record Paged<T>(List<T> rows, boolean truncated, long total) {}

  /** 单页行数上限（warehouse 侧的上限是 200）。 */
  private static final int PAGE_SIZE = 200;

  /** 页数上限：20 × 200 = 4000 行。超过就报 truncated，不静默继续拉。 */
  private static final int MAX_PAGES = 20;

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public StockoutInventoryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次计算要翻好几页库存与在途，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .messageConverters(java.util.List.of(converter))
        .build();
  }

  /** warehouse 的分 SKU 可用量页（7.1.1.3）。 */
  private record SellablePage(List<SellableRow> rows, long total) {}

  /** warehouse 的在途腿页（7.5.1.2）。 */
  private record TransitPage(List<TransitLegRow> rows, long total) {}

  /**
   * 读本租户的分 SKU 可用量（翻完所有页）。
   *
   * <p>翻页而不是只读第一页：断货清单要覆盖「所有 SKU」，只读第一页等于让排在后面的 SKU 静默消失，
   * 而「静默消失的 SKU」在断货场景里就是**看不见的断货**。
   */
  public Paged<SellableRow> sellable(String bearer) {
    List<SellableRow> all = new ArrayList<>();
    long total = 0;
    boolean truncated = false;
    int page = 0;
    while (true) {
      SellablePage response =
          fetch("/warehouse/inventory/sellable/skus?page=" + page + "&size=" + PAGE_SIZE, bearer, SellablePage.class);
      if (response == null || response.rows() == null) {
        throw unavailable("仓库服务没有返回可用量列表");
      }
      all.addAll(response.rows());
      total = response.total();
      if (all.size() >= total || response.rows().isEmpty()) {
        break;
      }
      page++;
      if (page >= MAX_PAGES) {
        truncated = true;
        break;
      }
    }
    return new Paged<>(all, truncated, total);
  }

  /**
   * 读**单个 SKU** 的可用量（7.1.1.3 的 {@code ?skuId=} 过滤）。
   *
   * <p>6.3.1.2 采购单带出库存快照用：一次只问一个 SKU，
   * 不必把整个租户的可用量清单翻完（那是 {@link #sellable(String)} 的用途）。
   *
   * @return 该 SKU 的可用量行；**没有这一行时返回 null**（= 仓库里没有这个 SKU 的库存账，
   *     与「有账但可用量是 0」是两件事，前者是「没记录」，后者是「真的一件都没有」）
   */
  public SellableRow sellableForSku(UUID skuId, String bearer) {
    SellablePage response =
        fetch(
            "/warehouse/inventory/sellable/skus?skuId=" + skuId + "&page=0&size=1",
            bearer,
            SellablePage.class);
    if (response == null || response.rows() == null) {
      throw unavailable("仓库服务没有返回可用量列表");
    }
    for (SellableRow row : response.rows()) {
      if (row != null && skuId.equals(row.skuId())) {
        return row;
      }
    }
    return null;
  }

  /**
   * 读本租户**仍在途**的在途腿（翻完所有页）。
   *
   * <p>{@code kinds} 是要纳入的在途类型。默认纳入调拨 / 头程 / 采购三类：它们都是「往我方仓里补货」的路。
   * 刻意<b>排除</b> {@code FBA_OUTBOUND}：那是「从我方仓发往平台仓」，方向相反，
   * 把它算成补货会让缺口被一个**正在离开**的量掩盖 —— 那正是重复补货的另一个来源。
   *
   * <p>warehouse 侧的清单端点已经只列 {@code status='IN_TRANSIT'} 的腿，
   * 所以本端口不重复过滤状态。
   */
  public Paged<TransitLegRow> transitLegs(List<String> kinds, String bearer) {
    List<TransitLegRow> all = new ArrayList<>();
    long total = 0;
    boolean truncated = false;
    int page = 0;
    while (true) {
      StringBuilder uri = new StringBuilder("/warehouse/inventory/in-transit?page=")
          .append(page)
          .append("&size=")
          .append(PAGE_SIZE);
      if (kinds != null && !kinds.isEmpty()) {
        uri.append("&transitKind=").append(String.join(",", kinds));
      }
      TransitPage response = fetch(uri.toString(), bearer, TransitPage.class);
      if (response == null || response.rows() == null) {
        throw unavailable("仓库服务没有返回在途清单");
      }
      all.addAll(response.rows());
      total = response.total();
      if (all.size() >= total || response.rows().isEmpty()) {
        break;
      }
      page++;
      if (page >= MAX_PAGES) {
        truncated = true;
        break;
      }
    }
    return new Paged<>(all, truncated, total);
  }

  private <T> T fetch(String uri, String bearer, Class<T> type) {
    try {
      return client.get().uri(uri).header("Authorization", bearer).retrieve().body(type);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库库存，断货风险清单暂时算不出来");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空集合冒充「仓库没有库存」：那会让断货清单显示「全部健康」，
   * 而真实情况是仓库挂了 —— 现场照着空清单以为不需要补货。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：断货风险清单的可用量与在途量唯一来源是 warehouse 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份「零库存」的清单");
  }
}
