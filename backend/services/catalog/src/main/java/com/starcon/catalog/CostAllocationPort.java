package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.2.1.3 成本分摊：读上游 10 头程费用的**跨服务端口**。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：头程发运单在
 * warehouse 服务的 {@code first_mile_shipment} / {@code first_mile_shipment_line}，
 * 而成本分摊在本服务（catalog）。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理，而且一旦两边都往这些表写，就出现了两个写入所有者。
 * 所以本服务<b>只</b>通过 warehouse 的授权只读端点取数，
 * <b>catalog 库里不存在任何头程发运单的副本</b>（V224 迁移里零个外键指向 warehouse 的表）。
 *
 * <p><b>为什么这个端口与 {@link CostBasisPort} 不是同一个</b>：{@code CostBasisPort}
 * （5.2.1.1 写的）把头程成本<b>按 SKU 聚合</b>掉了 —— 它回答的是「这个 SKU 的头程分摊是多少」。
 * 而本模块要回答的是<b>另一个</b>问题：「<b>这一张</b>货件的这笔共同成本，按哪条规则摊到<b>这些</b> SKU 上，
 * 换规则会差多少」。这两个问题的**粒度不同**（按 SKU 聚合 vs 按单据逐行），
 * 强行合成一个端口会让 5.2.1.1 的既有行为被动变形 —— 那不是本车道的写入范围。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * warehouse 再向 identity 确认已认证成员关系、解析出租户，并在自己事务里绑定
 * {@code app.current_tenant} 由 RLS 强制隔离。<b>不传这个头等于丢掉租户上下文</b> ——
 * warehouse 无从解析租户，会把请求拒掉，而不是「按空租户放行」。本端口<b>不</b>额外发明一个
 * {@code X-Tenant-Id} 之类的头传租户：那等于新建一条「客户端自称租户」的旁路，
 * 而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有数据</b>：取不到时抛 {@link CostAllocationUnavailableException}，
 * 由服务层翻译成 503。<b>绝不</b>返回空集合冒充「没有可分摊的货件」—— 那会让成本分摊页显示成
 * 「没有任何货件要摊」，而页面上看不出任何异常。
 *
 * <p><b>本接口在测试里由替身满足</b>，但生产代码路径永远是真实的 HTTP 调用 ——
 * 替身逻辑不写进生产代码。
 */
public interface CostAllocationPort {

  /**
   * 可分摊的货件候选清单（头程发运单）。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse，租户由它从成员关系解析</b>）
   * @return 候选清单
   */
  ConsignmentPage consignments(String bearer);

  /**
   * 取一张货件（头程发运单）的成本总额与逐行明细。
   *
   * @param bearer 调用者的 Authorization 头
   * @param sourceRef 发运单 id（UUID 串）
   * @return 货件
   */
  Consignment consignment(String bearer, String sourceRef);

  /**
   * 候选清单。
   *
   * @param truncated 上游单据数超过本端口的覆盖上限、结果只覆盖了最近的一部分
   */
  record ConsignmentPage(List<ConsignmentRef> items, boolean truncated) {}

  /** 一张货件的摘要（列表用）。 */
  record ConsignmentRef(
      UUID id,
      String code,
      String currency,
      BigDecimal totalCost,
      int lineCount,
      OffsetDateTime createdAt) {}

  /**
   * 货件的一行（一个 SKU 一行）。
   *
   * @param plannedQuantity 计划数量
   * @param shippedQuantity 发出数量（0 = 还没发出）
   * @param declaredValue 申报货值（0 = 没申报）
   * @param grossWeightKg 申报毛重（千克，0 = 没申报）
   * @param volumeCbm 申报体积（立方米，0 = 没申报）
   */
  record ConsignmentLine(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal volumeCbm) {}

  /** 一张货件：成本总额 + 逐行明细。 */
  record Consignment(
      UUID id,
      String code,
      String currency,
      BigDecimal totalCost,
      List<ConsignmentLine> lines,
      OffsetDateTime createdAt) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成对调用方有意义的一句话。 */
  class CostAllocationUnavailableException extends RuntimeException {
    public CostAllocationUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public CostAllocationUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置，与本服务既有的
   * {@link AccessService}（读 identity）、{@link CostBasisPort.HttpCostBasisPort} 完全同构 ——
   * 跨服务调用在本仓库只有这一种写法。
   */
  class HttpCostAllocationPort implements CostAllocationPort {

    /**
     * 上游单据上限。
     *
     * <p>本端口是 1+N 次调用，无上限时一个租户的历史发运单会把这个请求拖到超时。
     * 触顶时**不静默截断**：{@code truncated=true} 会一路带到响应里，页面上会写明
     * 「只覆盖了最近 N 张发运单」—— 一个不说清覆盖范围的分摊单是不能用来算利润的。
     */
    static final int MAX_DOCUMENTS = 200;

    private final RestClient client;

    public HttpCostAllocationPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public ConsignmentPage consignments(String bearer) {
      ShipmentPage body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/warehouse/first-mile-shipments")
                            .queryParam("sort", "CREATED_AT")
                            .queryParam("page", 0)
                            .queryParam("size", MAX_DOCUMENTS)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(ShipmentPage.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "头程发运单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new CostAllocationUnavailableException("仓库服务返回了空的头程发运单列表");
      }
      var refs = new ArrayList<ConsignmentRef>();
      for (var row : body.rows()) {
        if (row.id() == null) continue;
        // CANCELLED 的发运单没发出去、也不产生成本分摊。
        if ("CANCELLED".equals(row.status())) continue;
        refs.add(
            new ConsignmentRef(
                row.id(),
                row.shipmentCode(),
                row.currency(),
                allocatable(row),
                (int) row.lineCount(),
                row.createdAt()));
      }
      // 🔴 判据是**取回页的原始行数**：取回页满 ⇒ 一定有没读到的单据。
      // 用过滤后的行数判空，会出现「上游有 300 张单，但它们都被过滤掉了」时
      // 返回一个空列表 + truncated=false，页面就会显示「没有可分摊的货件」——
      // 那是在拿「没读到」冒充「没有」。
      return new ConsignmentPage(refs, body.rows().size() >= MAX_DOCUMENTS);
    }

    @Override
    public Consignment consignment(String bearer, String sourceRef) {
      ShipmentDetail body;
      try {
        body =
            client
                .get()
                .uri("/warehouse/first-mile-shipments/" + sourceRef)
                .header("Authorization", bearer)
                .retrieve()
                .body(ShipmentDetail.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "头程发运单明细");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.shipment() == null || body.lines() == null) {
        throw new CostAllocationUnavailableException(
            "仓库服务没有返回头程发运单 " + sourceRef + " 的明细");
      }
      var shipment = body.shipment();
      var lines = new ArrayList<ConsignmentLine>();
      for (var line : body.lines()) {
        if (line.skuId() == null) continue;
        lines.add(
            new ConsignmentLine(
                line.skuId(),
                line.skuCode(),
                line.skuName(),
                line.plannedQuantity(),
                line.shippedQuantity(),
                line.declaredValue(),
                line.grossWeightKg(),
                line.volumeCbm()));
      }
      return new Consignment(
          shipment.id(),
          shipment.shipmentCode(),
          shipment.currency(),
          allocatable(shipment),
          lines,
          shipment.createdAt());
    }

    /**
     * 一张发运单的可分摊成本 = 运费 + 保险费 + 操作费。
     *
     * <p><b>不含关税</b>：那笔是关税，分项归 10.5.1.3 的 {@code duty_tax_estimate_line}，
     * 在这里再摊一次就是把同一笔钱算两遍。
     */
    private static BigDecimal allocatable(ShipmentRow row) {
      return nz(row.freightCost())
          .add(nz(row.insuranceCost()))
          .add(nz(row.handlingCost()));
    }

    private static BigDecimal nz(BigDecimal value) {
      return value == null ? BigDecimal.ZERO : value;
    }

    private static CostAllocationUnavailableException translate(
        RestClientResponseException e, String what) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new CostAllocationUnavailableException(
            "仓库服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由仓库服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行",
            e);
      }
      return new CostAllocationUnavailableException("仓库服务返回 " + status + "，读不到" + what, e);
    }

    private static CostAllocationUnavailableException unavailable(String reason, Throwable cause) {
      return new CostAllocationUnavailableException(
          reason
              + "，读不到上游的可分摊货件。成本分摊的唯一外部来源是 warehouse 的授权只读端点"
              + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空造出一批货件 —— "
              + "拿不到时显示「读不到」而不是显示「没有货件」，因为后者会让成本分摊页看起来一切正常",
          cause);
    }
  }

  /**
   * 上游未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默返回空列表**：{@code WAREHOUSE_URL} 没配时，一个可分摊的货件也取不到。
   * 返回空列表会让页面显示「没有可分摊的货件」—— 那是在骗人：真实情况是「我们没接上游」。
   */
  class UnwiredCostAllocationPort implements CostAllocationPort {
    @Override
    public ConsignmentPage consignments(String bearer) {
      throw new CostAllocationUnavailableException(
          "仓库服务地址未配置（WAREHOUSE_URL 为空）：可分摊货件的唯一来源是 warehouse 的"
              + "授权只读端点，地址没配就一个都读不到。读不到时不能显示「没有货件」—— "
              + "那会让成本分摊页看起来一切正常。请配置 WAREHOUSE_URL 后重试");
    }

    @Override
    public Consignment consignment(String bearer, String sourceRef) {
      throw new CostAllocationUnavailableException(
          "仓库服务地址未配置（WAREHOUSE_URL 为空）：可分摊货件的唯一来源是 warehouse 的"
              + "授权只读端点，地址没配就一个都读不到。请配置 WAREHOUSE_URL 后重试");
    }
  }

  // ================================================================ warehouse 侧契约形状

  /** 头程发运单的一行（只要本模块用得到的列：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShipmentRow(
      UUID id,
      String shipmentCode,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal handlingCost,
      String status,
      long lineCount,
      OffsetDateTime createdAt) {}

  /** 头程发运单的一行明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShipmentLineView(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal volumeCbm) {}

  /** 头程发运单详情（行在明细里，列表里没有）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShipmentDetail(ShipmentRow shipment, List<ShipmentLineView> lines) {}

  /** 头程发运单列表响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShipmentPage(List<ShipmentRow> rows, long total, int page, int size) {}
}
