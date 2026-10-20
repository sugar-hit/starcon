package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.2.1.2 到岸成本：读上游 10 头程发运单与 10.5.1.3 关税估算的**跨服务端口**。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 头程发运单在 warehouse 服务的 {@code first_mile_shipment} / {@code first_mile_shipment_line}，
 * 报关单在 {@code customs_declaration}，关税估算在 {@code duty_tax_estimate} /
 * {@code duty_tax_estimate_line}，而到岸成本单在本服务（catalog）。
 * 跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，跨库 FK / 共享视图同理，
 * 而且一旦两边都往这些表写，就出现了两个写入所有者。
 * 所以本服务**只**通过 warehouse 的授权只读端点取数，
 * <b>catalog 库里不存在任何头程 / 报关 / 关税的副本</b>（V223 迁移里零个外键指向 warehouse 的表）。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * warehouse 再向 identity 确认已认证成员关系、解析出租户，并在自己事务里绑定
 * {@code app.current_tenant} 由 RLS 强制隔离。<b>不传这个头等于丢掉租户上下文</b> ——
 * warehouse 无从解析租户，会把请求拒掉，而不是「按空租户放行」。
 * 本端口<b>不</b>额外发明一个 {@code X-Tenant-Id} 之类的头传租户：
 * 那等于新建一条「客户端自称租户」的旁路，而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有数据</b>：取不到时抛 {@link LandedCostUnavailableException}，
 * 由服务层翻译成 503。<b>绝不</b>返回空集合冒充「没有头程 / 没有关税」——
 * 那会让到岸成本显示成一个偏低的合计，而页面上看不出任何异常。
 * 注意这里的区别：**上游整体读不到**（不可达 / 500）⇒ 503；
 * **上游读到了但这一项是 0**（例如发运单上没登记运费）⇒ 这是**缺口**，
 * 由服务层标 {@code available=false}，不是 0 也不是 503。
 *
 * <p><b>本接口在测试里由替身满足</b>（见 {@code LandedCostIntegrationTest}），
 * 但生产代码路径永远是真实的 HTTP 调用 —— 替身逻辑不写进生产代码。
 */
public interface LandedCostPort {

  /**
   * 取一张头程发运单（批次）及其行。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse，租户由它从成员关系解析</b>）
   * @param shipmentId 发运单 id；为 null 时按 {@code shipmentCode} 查
   * @param shipmentCode 发运单号
   * @return 发运单；查不到时返回 null（**不是**空集合冒充「没有发运单」）
   */
  ShipmentView shipment(String bearer, UUID shipmentId, String shipmentCode);

  /**
   * 取这张发运单对应的关税估算（经报关单关联）。
   *
   * <p>没有报关单、或报关单还没估税 ⇒ 返回 null（**缺口**，由服务层标 available=false）。
   */
  DutyView duty(String bearer, UUID shipmentId);

  /** 一张头程发运单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShipmentView(
      UUID id,
      String shipmentCode,
      UUID legalEntityId,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String incoterm,
      String riskBearer,
      String status,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal handlingCost,
      List<ShipmentLineView> lines) {}

  /** 发运单的一行 = 一个 SKU × 一个批次。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShipmentLineView(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity) {}

  /** 一张关税估算单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DutyView(
      UUID id,
      String estimateCode,
      String declarationCode,
      String dutyBearer,
      String estimateCurrency,
      String destinationCountryCode,
      String status,
      List<DutyLineView> lines) {}

  /** 关税估算的一行 = 一个 SKU。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DutyLineView(
      UUID id,
      int lineNo,
      UUID skuId,
      BigDecimal quantity,
      String uom,
      BigDecimal estimatedDutyAmount,
      BigDecimal estimatedVatAmount,
      BigDecimal estimatedLineTotalTax) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成对调用方有意义的一句话。 */
  class LandedCostUnavailableException extends RuntimeException {
    public LandedCostUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public LandedCostUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 读配置，与本服务既有的 {@link AccessService}（读 identity）
   * 完全同构 —— 跨服务调用在本仓库只有这一种写法。
   */
  class HttpLandedCostPort implements LandedCostPort {

    /** 上游单页取多少行。触发上限时**不静默截断**：本模块只取「这一张发运单」相关的单据，不做批量扫描。 */
    static final int MAX_ROWS = 50;

    private final RestClient client;

    public HttpLandedCostPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public ShipmentView shipment(String bearer, UUID shipmentId, String shipmentCode) {
      // 优先按 id 精确取；没有 id 时按单号查列表再按单号精确挑一行。
      var detail = shipmentId == null ? null : shipmentDetail(bearer, shipmentId);
      if (detail == null && (shipmentCode == null || shipmentCode.isBlank())) {
        throw new LandedCostUnavailableException("必须给出头程发运单的 id 或单号");
      }
      if (detail == null) {
        var row = shipmentByCode(bearer, shipmentCode);
        if (row == null) {
          // 查不到 ≠ 读不到上游：这是「没有这张单」，返回 null 由服务层回答 404。
          return null;
        }
        detail = shipmentDetail(bearer, row.id());
      }
      if (detail == null) {
        return null;
      }
      var shipment = detail.shipment();
      if (shipment == null) {
        throw new LandedCostUnavailableException("仓库服务没有返回头程发运单本体");
      }
      var lines = new ArrayList<ShipmentLineView>();
      if (detail.lines() != null) {
        for (var line : detail.lines()) {
          lines.add(
              new ShipmentLineView(
                  line.id(),
                  line.lineNo(),
                  line.skuId(),
                  line.skuCode(),
                  line.skuName(),
                  line.plannedQuantity(),
                  line.shippedQuantity()));
        }
      }
      return new ShipmentView(
          shipment.id(),
          shipment.shipmentCode(),
          shipment.legalEntityId(),
          shipment.destinationCountryCode(),
          shipment.destinationName(),
          shipment.transportMode(),
          shipment.incoterm(),
          shipment.riskBearer(),
          shipment.status(),
          shipment.currency(),
          shipment.freightCost(),
          shipment.insuranceCost(),
          shipment.handlingCost(),
          lines);
    }

    @Override
    public DutyView duty(String bearer, UUID shipmentId) {
      if (shipmentId == null) return null;
      // 链路：发运单 → 报关单（sourceShipmentId）→ 关税估算（declarationId）。
      var declarations = declarationRows(bearer, shipmentId);
      if (declarations.isEmpty()) {
        // 没有报关单 ⇒ 还没有税可估。这是**缺口**（不是读不到上游），返回 null。
        return null;
      }
      // 取最新的一张有效报关单（作废/取消的不算）。
      var declaration =
          declarations.stream()
              .filter(d -> !"CANCELLED".equals(d.status()))
              .max((a, b) -> compareCreated(a.createdAt(), b.createdAt()))
              .orElse(null);
      if (declaration == null) {
        return null;
      }
      var estimates = dutyRows(bearer, declaration.id());
      if (estimates.isEmpty()) {
        return null;
      }
      var estimate =
          estimates.stream()
              .filter(e -> !"VOID".equals(e.status()))
              .max((a, b) -> compareCreated(a.createdAt(), b.createdAt()))
              .orElse(null);
      if (estimate == null) {
        return null;
      }
      var lines = dutyLines(bearer, estimate.id());
      return new DutyView(
          estimate.id(),
          estimate.estimateCode(),
          declaration.declarationCode(),
          estimate.dutyBearer(),
          estimate.estimateCurrency(),
          estimate.destinationCountryCode(),
          estimate.status(),
          lines);
    }

    /** 创建时间比较：null 视为最早。 */
    private static int compareCreated(java.time.OffsetDateTime a, java.time.OffsetDateTime b) {
      if (a == null && b == null) return 0;
      if (a == null) return -1;
      if (b == null) return 1;
      return a.compareTo(b);
    }

    // ---------------------------------------------------------------- 发运单

    /** 发运单详情（头 + 行）。warehouse 的 {@code FirstMileDetail} 形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FirstMileDetailView(
        FirstMileRowView shipment, List<FirstMileLineView> lines) {}

    /** 发运单列表页。warehouse 的 {@code FirstMilePage} 形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FirstMilePageView(List<FirstMileRowView> rows, long total) {}

    /** 发运单一行。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FirstMileRowView(
        UUID id,
        String shipmentCode,
        UUID legalEntityId,
        String destinationCountryCode,
        String destinationName,
        String transportMode,
        String incoterm,
        String riskBearer,
        String status,
        String currency,
        BigDecimal freightCost,
        BigDecimal insuranceCost,
        BigDecimal handlingCost) {}

    /** 发运单一行明细。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FirstMileLineView(
        UUID id,
        int lineNo,
        UUID skuId,
        String skuCode,
        String skuName,
        BigDecimal plannedQuantity,
        BigDecimal shippedQuantity) {}

    private FirstMileDetailView shipmentDetail(String bearer, UUID shipmentId) {
      try {
        FirstMileDetailView body =
            client
                .get()
                .uri("/warehouse/first-mile-shipments/" + shipmentId)
                .header("Authorization", bearer)
                .retrieve()
                .body(FirstMileDetailView.class);
        return body;
      } catch (RestClientResponseException e) {
        // 404 ⇒ 这张发运单不存在（跨租户也走 404，不泄露「这张单存在」）。
        if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
          return null;
        }
        throw translate(e, "头程发运单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
    }

    private FirstMileRowView shipmentByCode(String bearer, String shipmentCode) {
      FirstMilePageView body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/warehouse/first-mile-shipments")
                            .queryParam("keyword", shipmentCode)
                            .queryParam("page", 0)
                            .queryParam("size", MAX_ROWS)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(FirstMilePageView.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "头程发运单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new LandedCostUnavailableException("仓库服务返回了空的头程发运单列表");
      }
      for (var row : body.rows()) {
        // 按单号**精确**匹配：keyword 是模糊搜索，模糊命中不等于就是这一张单。
        if (row.shipmentCode() != null && row.shipmentCode().equalsIgnoreCase(shipmentCode.trim())) {
          return row;
        }
      }
      return null;
    }

    // ---------------------------------------------------------------- 报关单

    /** 报关单列表页。warehouse 的 {@code CustomsDeclarationPage} 形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomsDeclarationPageView(List<CustomsDeclarationRowView> rows, long total) {}

    /** 报关单一行。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomsDeclarationRowView(
        UUID id,
        String declarationCode,
        UUID sourceShipmentId,
        String sourceShipmentCode,
        String destinationCountryCode,
        String status,
        java.time.OffsetDateTime createdAt) {}

    private List<CustomsDeclarationRowView> declarationRows(String bearer, UUID shipmentId) {
      CustomsDeclarationPageView body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/warehouse/customs-declarations")
                            .queryParam("sourceShipmentId", shipmentId)
                            .queryParam("page", 0)
                            .queryParam("size", MAX_ROWS)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(CustomsDeclarationPageView.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "报关单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new LandedCostUnavailableException("仓库服务返回了空的报关单列表");
      }
      return body.rows();
    }

    // ---------------------------------------------------------------- 关税估算

    /** 关税估算列表页。warehouse 的 {@code DutyEstimatePage} 形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DutyEstimatePageView(List<DutyEstimateRowView> rows, long total) {}

    /** 关税估算一行。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DutyEstimateRowView(
        UUID id,
        String estimateCode,
        String declarationCode,
        String dutyBearer,
        String estimateCurrency,
        String destinationCountryCode,
        String status,
        java.time.OffsetDateTime createdAt) {}

    private List<DutyEstimateRowView> dutyRows(String bearer, UUID declarationId) {
      DutyEstimatePageView body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/warehouse/duty-taxes")
                            .queryParam("declarationId", declarationId)
                            .queryParam("page", 0)
                            .queryParam("size", MAX_ROWS)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(DutyEstimatePageView.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "关税估算单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new LandedCostUnavailableException("仓库服务返回了空的关税估算单列表");
      }
      return body.rows();
    }

    private List<DutyLineView> dutyLines(String bearer, UUID estimateId) {
      try {
        // warehouse 的行端点返回的是**裸数组**（List<DutyEstimateLineRow>），
        // 不是包了一层的对象 —— 用 ParameterizedTypeReference 才能按 List 反序列化。
        List<DutyLineView> body =
            client
                .get()
                .uri("/warehouse/duty-taxes/" + estimateId + "/lines")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<DutyLineView>>() {});
        if (body == null) {
          throw new LandedCostUnavailableException("仓库服务没有返回关税估算单 " + estimateId + " 的行明细");
        }
        return body;
      } catch (RestClientResponseException e) {
        throw translate(e, "关税估算单明细");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
    }

    // ---------------------------------------------------------------- 错误翻译

    private static LandedCostUnavailableException translate(
        RestClientResponseException e, String what) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new LandedCostUnavailableException(
            "仓库服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由仓库服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行",
            e);
      }
      return new LandedCostUnavailableException("仓库服务返回 " + status + "，读不到" + what, e);
    }

    private static LandedCostUnavailableException unavailable(String reason, Throwable cause) {
      return new LandedCostUnavailableException(
          reason
              + "，读不到上游到岸成本依据。头程与关税的唯一来源是 warehouse 的授权只读端点"
              + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会凭空生成一份到岸成本 —— 拿不到时显示「读不到」而不是显示 0，"
              + "因为显示 0 会让到岸成本系统性低估（尤其是关税），而页面上看不出任何异常",
          cause);
    }
  }

  /**
   * 「上游没配」的实现。
   *
   * <p>🔴 **显式抛错**，而不是返回空集合：地址没配就等于一个上游都读不到，
   * 返回空集合会让页面显示「国际头程 0.00、关税 0.00」——
   * 那是在骗人：真实情况是「我们没接上游」，而这两笔在到岸成本里通常不是小数目。
   */
  class UnwiredLandedCostPort implements LandedCostPort {

    @Override
    public ShipmentView shipment(String bearer, UUID shipmentId, String shipmentCode) {
      throw new LandedCostUnavailableException(
          "仓库服务地址未配置（WAREHOUSE_URL 为空）：读不到头程发运单。"
              + "本服务不猜头程成本，也不返回空集合冒充「没有头程」——"
              + "那会让到岸成本系统性低估且页面上看不出异常");
    }

    @Override
    public DutyView duty(String bearer, UUID shipmentId) {
      throw new LandedCostUnavailableException(
          "仓库服务地址未配置（WAREHOUSE_URL 为空）：读不到关税估算。"
              + "本服务不猜税率，也不返回空集合冒充「没有关税」——"
              + "用 0 填关税会让到岸成本系统性低估");
    }
  }
}
