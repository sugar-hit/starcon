package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.2.1.1 成本构成：读上游 10 头程费用与 10.5.1.3 关税与税费的**跨服务端口**。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：头程发运单在 warehouse 服务的
 * {@code first_mile_shipment} / {@code first_mile_shipment_line}，关税估算在 {@code duty_tax_estimate} /
 * {@code duty_tax_estimate_line}，成本构成在本服务（catalog）。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理，而且一旦两边都往这些表写，就出现了两个写入所有者。所以本服务**只**通过 warehouse 的授权只读端点取数，
 * <b>catalog 库里不存在任何头程 / 关税的副本</b>（V215 迁移里零个外键指向 warehouse 的表）。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，warehouse 再向 identity 确认已认证成员关系、
 * 解析出租户，并在自己事务里绑定 {@code app.current_tenant} 由 RLS 强制隔离。<b>不传这个头等于丢掉租户上下文</b> ——
 * warehouse 无从解析租户，会把请求拒掉，而不是「按空租户放行」。本端口<b>不</b>额外发明一个 {@code X-Tenant-Id} 之类的头传租户：
 * 那等于新建一条「客户端自称租户」的旁路，而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有数据</b>：取不到时抛 {@link CostBasisUnavailableException}，由服务层翻译成 503。
 * <b>绝不</b>返回空集合冒充「没有头程成本 / 没有关税」—— 那会让成本构成显示成一个偏低的合计，
 * 而页面上看不出任何异常，直接影响 5.3.1.1 测算与 13 利润。
 *
 * <p><b>🔴 已知代价：端口内部是 1+N 次 HTTP 调用</b>。warehouse 目前**没有**「按 SKU 汇总成本」的只读端点
 * （头程的行在发运单详情里，关税的行在估算单行列表里），而本车道**不新增 warehouse 的端点**
 * （那是另一个服务的写入所有权，且与并行车道有 DDL / 源码冲突面）。所以本端口先取发运单 / 估算单列表，
 * 再逐张取行明细，在**本端口内部**按 SKU 聚合。
 * <b>这是本车道的已知代价，不隐瞒</b>：warehouse 侧补一个按 SKU 聚合的只读端点后，
 * 换掉本端口的 HTTP 实现即可，服务层与页面的聚合逻辑无需改动。
 *
 * <p><b>本接口在测试里由替身满足</b>（见 {@code SkuCostCompositionIntegrationTest}），但生产代码路径永远是真实的 HTTP 调用 ——
 * 替身逻辑不写进生产代码。
 */
public interface CostBasisPort {

  /**
   * 取一组 SKU 的头程分摊与关税分摊（按币种分列）。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse，租户由它从成员关系解析</b>）
   * @param skuIds 要算的 SKU
   * @param windowFrom 成本口径窗口起（含）
   * @param windowTo 成本口径窗口止（含）
   * @return 按 (sku, 币种) 分列的分摊结果
   */
  CostBasisResult allocations(String bearer, List<UUID> skuIds, LocalDate windowFrom, LocalDate windowTo);

  /** 头程分摊：一张发运单的可分摊成本按各行数量摊到 SKU 上。 */
  record FirstMileAllocation(
      UUID skuId, String currency, BigDecimal amount, BigDecimal baseQuantity, int shipmentCount) {}

  /**
   * 关税分摊。
   *
   * <p>{@code dutyAmount} = DUTY（关税）之一 —— 10.5.1.3 把 DUTY / VAT / GST 明确分成三笔独立的钱，
   * 把增值税并进「关税」会让明细答不出「哪部分是关税哪部分是税」。{@code otherTaxAmount} 是 VAT / GST 等
   * **不计入关税分项**的其它税（只读展示，归 5.2.1.2 到岸成本）。
   *
   * <p><b>🔴 按 {@code uom} 分组，不合并</b>：上游的关税估算行带自己的计量单位（报关口径），
   * 它未必是 SKU 的基础单位。把不同单位的数量直接加在一起再除以金额，算出来的「单位关税」
   * 是一个没有意义的数。分组返回后由服务层按 {@code sku_unit_conversion} 折算到基础单位，
   * <b>折不出来就不给这一项</b>（宁可标「读不到」，也不给一个错的单位成本）。
   */
  record DutyAllocation(
      UUID skuId,
      String currency,
      String uom,
      BigDecimal dutyAmount,
      BigDecimal otherTaxAmount,
      BigDecimal quantity,
      int estimateCount) {}

  /** 一次取数的总结果。{@code truncated} 为 true 表示上游单据数超过上限、结果只覆盖了最近的一部分。 */
  record CostBasisResult(
      List<FirstMileAllocation> firstMile,
      List<DutyAllocation> duty,
      boolean truncated,
      String note) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成对调用方有意义的一句话。 */
  class CostBasisUnavailableException extends RuntimeException {
    public CostBasisUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public CostBasisUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置，与本服务既有的 {@link AccessService}（读 identity）
   * 完全同构 —— 跨服务调用在本仓库只有这一种写法。
   */
  class HttpCostBasisPort implements CostBasisPort {

    /**
     * 上游单据上限。
     *
     * <p>本端口是 1+N 次调用，无上限时一个租户的历史头程单会把这个请求拖到超时。
     * 触顶时**不静默截断**：{@code truncated=true} 会一路带到响应里，页面上会写明
     * 「只覆盖了最近 N 张发运单」—— 一个不说清覆盖范围的单位成本是不能用来算利润的。
     */
    static final int MAX_DOCUMENTS = 200;

    private final RestClient client;

    public HttpCostBasisPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public CostBasisResult allocations(
        String bearer, List<UUID> skuIds, LocalDate windowFrom, LocalDate windowTo) {
      if (skuIds == null || skuIds.isEmpty()) {
        return new CostBasisResult(List.of(), List.of(), false, "没有要算的 SKU");
      }
      // 🔴 累加器一律 BigDecimal：金额进 double 再转回来会丢精度（AGENTS.md：禁止浮点记账）。
      // 数组的三个槽分别是 [金额, 数量, 其它税]（头程只用前两个）。
      var firstMile = new LinkedHashMap<String, BigDecimal[]>();
      var firstMileCount = new LinkedHashMap<String, Integer>();
      var duty = new LinkedHashMap<String, BigDecimal[]>();
      var dutyCount = new LinkedHashMap<String, Integer>();
      boolean truncated = false;

      // ---- 头程：先取发运单列表（按创建时间倒序 = 最近的先取），再逐张取行明细 ----
      var shipments = firstMileShipments(bearer, windowFrom, windowTo);
      truncated = truncated || shipments.truncated();
      for (var shipment : shipments.documents()) {
        var lines = firstMileLines(bearer, shipment.id());
        // 可分摊成本 = 运费 + 保险费 + 操作费。
        // **不含 duty_estimate**：那笔是关税，分项归 10.5.1.3 的 duty_tax_estimate_line，
        // 在这里再摊一次就是把同一笔钱算两遍。
        BigDecimal allocatable =
            nz(shipment.freightCost()).add(nz(shipment.insuranceCost())).add(nz(shipment.handlingCost()));
        if (allocatable.signum() <= 0) continue;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (var line : lines) {
          totalQty = totalQty.add(lineQuantity(line));
        }
        if (totalQty.signum() <= 0) continue;
        for (var line : lines) {
          BigDecimal qty = lineQuantity(line);
          if (qty.signum() <= 0) continue;
          // 分摊份额 = 可分摊成本 × 该行数量 ÷ 全单数量。
          // 显式 ::numeric：PG 里 int/int 是整数除法，2/3 会静默变成 0。
          BigDecimal share =
              allocatable
                  .multiply(qty)
                  .divide(totalQty, 10, java.math.RoundingMode.HALF_UP)
                  .stripTrailingZeros();
          String key = line.skuId() + "|" + nz(shipment.currency());
          // 🔴 累加槽必须**初始化为 0**：`new BigDecimal[2]` 的元素默认是 null，
          // 而下一行 `acc[0].add(share)` 会在第一张单据上就 NPE。
          // 槽位含义：[0]=分摊金额, [1]=分摊数量。
          var acc =
              firstMile.computeIfAbsent(
                  key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
          acc[0] = acc[0].add(share);
          acc[1] = acc[1].add(qty);
          firstMileCount.merge(key, 1, Integer::sum);
        }
      }

      // ---- 关税：先取估算单列表，再逐张取行 ----
      var estimates = dutyEstimates(bearer, windowFrom, windowTo);
      truncated = truncated || estimates.truncated();
      for (var estimate : estimates.documents()) {
        var lines = dutyLines(bearer, estimate.id());
        for (var line : lines) {
          if (line.skuId() == null) continue;
          BigDecimal qty = nz(line.quantity());
          if (qty.signum() <= 0) continue;
          // 按 (sku, 币种, 计量单位) 分组：不同单位的数量不能相加（见 DutyAllocation 的注释）。
          String key = line.skuId() + "|" + nz(estimate.estimateCurrency()) + "|" + nz(line.uom());
          // 🔴 同样必须初始化为 0（槽位默认是 null，第一张单据上就会 NPE）。
          // 槽位含义：[0]=关税金额, [1]=其它税（增值税/GST）, [2]=数量。
          var acc =
              duty.computeIfAbsent(
                  key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
          acc[0] = acc[0].add(nz(line.estimatedDutyAmount()));
          acc[1] =
              acc[1]
                  .add(nz(line.estimatedVatAmount()))
                  .add(
                      nz(line.estimatedLineTotalTax())
                          .subtract(nz(line.estimatedDutyAmount()))
                          .subtract(nz(line.estimatedVatAmount())));
          acc[2] = acc[2].add(qty);
          dutyCount.merge(key, 1, Integer::sum);
        }
      }

      var firstMileOut = new ArrayList<FirstMileAllocation>();
      for (var e : firstMile.entrySet()) {
        var parts = e.getKey().split("\\|", -1);
        firstMileOut.add(
            new FirstMileAllocation(
                UUID.fromString(parts[0]),
                parts[1],
                e.getValue()[0],
                e.getValue()[1],
                firstMileCount.getOrDefault(e.getKey(), 0)));
      }
      var dutyOut = new ArrayList<DutyAllocation>();
      for (var e : duty.entrySet()) {
        var parts = e.getKey().split("\\|", -1);
        dutyOut.add(
            new DutyAllocation(
                UUID.fromString(parts[0]),
                parts[1],
                parts[2],
                e.getValue()[0],
                e.getValue()[1],
                e.getValue()[2],
                dutyCount.getOrDefault(e.getKey(), 0)));
      }
      String note =
          "头程分摊与关税分摊取自 warehouse 的授权只读端点"
              + "（GET /warehouse/first-mile-shipments、GET /warehouse/duty-taxes），"
              + "租户由 warehouse 从已认证成员关系解析并由 RLS 强制隔离；"
              + "本端口一次取数内部是 1+N 次调用，warehouse 侧补一个按 SKU 聚合的端点后即可换掉本实现。";
      return new CostBasisResult(firstMileOut, dutyOut, truncated, note);
    }

    /** 发运单的行数量：已发出用发出量，未发出用计划量。 */
    private static BigDecimal lineQuantity(FirstMileLineView line) {
      BigDecimal shipped = nz(line.shippedQuantity());
      return shipped.signum() > 0 ? shipped : nz(line.plannedQuantity());
    }

    private static BigDecimal nz(BigDecimal v) {
      return v == null ? BigDecimal.ZERO : v;
    }

    /** 字符串的空值兜底：null → 空串（分组键拼装用，**不**把 null 写进 key 的中间段）。 */
    private static String nz(String v) {
      return v == null ? "" : v;
    }

    private record DocumentPage<T>(List<T> documents, boolean truncated) {}

    private DocumentPage<FirstMileShipmentView> firstMileShipments(
        String bearer, LocalDate from, LocalDate to) {
      var rows = new ArrayList<FirstMileShipmentView>();
      boolean truncated = false;
      // 单页取满 MAX_DOCUMENTS 即视为「上游单据数超过本端口的覆盖上限」：
      // 🔴 判据是**取回页的原始行数**，不是过滤后落在窗口里的行数 ——
      // 用过滤后的行数判空，会出现「上游有 300 张单，但它们都在窗口之外」时
      // 返回一个空集合 + truncated=false，页面就会显示「没有头程成本」——
      // 那是在拿「没读到」冒充「没有」。取回页满 ⇒ 一定有没读到的单据。
      FirstMilePageView body;
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
                .body(FirstMilePageView.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "头程发运单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new CostBasisUnavailableException("仓库服务返回了空的头程发运单列表");
      }
      if (body.rows().size() >= MAX_DOCUMENTS) {
        truncated = true;
      }
      for (var row : body.rows()) {
        if (row.createdAt() == null) continue;
        LocalDate created = row.createdAt().toLocalDate();
        if (created.isBefore(from) || created.isAfter(to)) continue;
        // CANCELLED 的发运单没发出去、也不产生成本分摊。
        if ("CANCELLED".equals(row.status())) continue;
        rows.add(row);
      }
      return new DocumentPage<>(rows, truncated);
    }

    private List<FirstMileLineView> firstMileLines(String bearer, UUID shipmentId) {
      try {
        FirstMileDetailView body =
            client
                .get()
                .uri("/warehouse/first-mile-shipments/" + shipmentId)
                .header("Authorization", bearer)
                .retrieve()
                .body(FirstMileDetailView.class);
        if (body == null || body.lines() == null) {
          throw new CostBasisUnavailableException("仓库服务没有返回发运单 " + shipmentId + " 的行明细");
        }
        return body.lines();
      } catch (RestClientResponseException e) {
        throw translate(e, "头程发运单明细");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
    }

    private DocumentPage<DutyEstimateView> dutyEstimates(
        String bearer, LocalDate from, LocalDate to) {
      var rows = new ArrayList<DutyEstimateView>();
      boolean truncated = false;
      // 与头程同一判据：**取回页的原始行数**满即视为覆盖上限已触顶（见 firstMileShipments 的注释）。
      DutyPageView body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/warehouse/duty-taxes")
                            .queryParam("sort", "CREATED_AT")
                            .queryParam("page", 0)
                            .queryParam("size", MAX_DOCUMENTS)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(DutyPageView.class);
      } catch (RestClientResponseException e) {
        throw translate(e, "关税估算单");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
      if (body == null || body.rows() == null) {
        throw new CostBasisUnavailableException("仓库服务返回了空的关税估算单列表");
      }
      if (body.rows().size() >= MAX_DOCUMENTS) {
        truncated = true;
      }
      for (var row : body.rows()) {
        if (row.createdAt() == null) continue;
        LocalDate created = row.createdAt().toLocalDate();
        if (created.isBefore(from) || created.isAfter(to)) continue;
        // DRAFT 还没算出来，VOID 已作废：都不进成本口径。
        if ("DRAFT".equals(row.status()) || "VOID".equals(row.status())) continue;
        rows.add(row);
      }
      return new DocumentPage<>(rows, truncated);
    }

    private List<DutyEstimateLineView> dutyLines(String bearer, UUID estimateId) {
      try {
        // warehouse 的行端点返回的是**裸数组**（List<DutyEstimateLineRow>），
        // 不是包了一层的对象 —— 这里用 ParameterizedTypeReference 才能按 List 反序列化。
        List<DutyEstimateLineView> body =
            client
                .get()
                .uri("/warehouse/duty-taxes/" + estimateId + "/lines")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<DutyEstimateLineView>>() {});
        if (body == null) {
          throw new CostBasisUnavailableException("仓库服务没有返回关税估算单 " + estimateId + " 的行明细");
        }
        return body;
      } catch (RestClientResponseException e) {
        throw translate(e, "关税估算单明细");
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
    }

    private static CostBasisUnavailableException translate(RestClientResponseException e, String what) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new CostBasisUnavailableException(
            "仓库服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由仓库服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行",
            e);
      }
      return new CostBasisUnavailableException("仓库服务返回 " + status + "，读不到" + what, e);
    }

    private static CostBasisUnavailableException unavailable(String reason, Throwable cause) {
      return new CostBasisUnavailableException(
          reason
              + "，读不到上游成本构成。成本构成的两个派生分项（头程分摊、关税）唯一来源是"
              + "warehouse 的授权只读端点（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会凭空生成一份成本 —— 拿不到时显示「读不到」而不是显示 0，"
              + "因为显示 0 会让单位成本偏低、毛利偏高，而页面上看不出任何异常",
          cause);
    }
  }

  /**
   * 上游未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默返回空**：{@code WAREHOUSE_URL} 没配时，两个派生分项就一个都取不到。
   * 返回空集合会让页面显示「头程分摊 0.00、关税 0.00」—— 那是在骗人：真实情况是
   * 「我们没接上游」，而这两笔在到岸成本里通常不是小数目。
   */
  class UnwiredCostBasisPort implements CostBasisPort {
    @Override
    public CostBasisResult allocations(
        String bearer, List<UUID> skuIds, LocalDate windowFrom, LocalDate windowTo) {
      throw new CostBasisUnavailableException(
          "仓库服务地址未配置（WAREHOUSE_URL 为空）：头程分摊与关税分项的唯一来源是 warehouse 的"
              + "授权只读端点，地址没配就一个都读不到。读不到时不能显示 0 —— 那会让单位成本偏低、"
              + "毛利偏高，而页面上看不出任何异常。请配置 WAREHOUSE_URL 后重试");
    }
  }

  // ================================================================ warehouse 侧契约形状

  /** 头程发运单列表（只要本模块用得到的列：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FirstMileShipmentView(
      UUID id,
      String shipmentCode,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal handlingCost,
      String status,
      OffsetDateTime createdAt) {}

  /** 头程发运单的行（一个 SKU 一行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FirstMileLineView(
      UUID skuId, BigDecimal plannedQuantity, BigDecimal shippedQuantity) {}

  /** 头程发运单详情（行在明细里，列表里没有）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FirstMileDetailView(
      FirstMileShipmentView shipment, List<FirstMileLineView> lines) {}

  /** 头程发运单列表响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FirstMilePageView(List<FirstMileShipmentView> rows, long total, int page, int size) {}

  /** 关税估算单（只要币种与状态）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DutyEstimateView(
      UUID id,
      String estimateCode,
      String estimateCurrency,
      String status,
      OffsetDateTime createdAt) {}

  /** 关税估算单的行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DutyEstimateLineView(
      UUID skuId,
      String uom,
      BigDecimal quantity,
      BigDecimal estimatedDutyAmount,
      BigDecimal estimatedVatAmount,
      BigDecimal estimatedLineTotalTax) {}

  /** 关税估算单列表响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DutyPageView(List<DutyEstimateView> rows, int page, int size, int totalPages) {}
}
