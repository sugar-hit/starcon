package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.4.1.1 头程发运单。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.4.1.1 的输入/输出契约：
 *
 * <ul>
 *   <li>输入：待发运货物清单；运输方式与渠道；报关资料；成本参数。
 *   <li>输出：头程发运单 + 发货分录（转在途）+ 报关资料 + 预计到港时间。
 * </ul>
 *
 * <p>DTO 一律带 {@code FirstMile}/{@code Shipment} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>三条本模块必须自己讲清楚的口径（都随响应返回，不让界面上凭空猜）：
 *
 * <ol>
 *   <li><b>货权与成本的关键节点</b>：{@code incoterm}（成本转嫁方式）与 {@code riskBearer}
 *       （风险承担方）从「已发出」那一刻起决定利润口径，因此**入库、进契约、可查询**，
 *       不是页面上写死的展示项。DB 层钉死 EXW 只能配 BUYER、DDP 只能配 SELLER。
 *   <li><b>发出不动在手</b>：头程货在发出前**根本没进过我方库存账**（它来自供应商或本租户
 *       另一张收货单，不是从在手扣出来的）。所以发出只登记在途腿，<b>不扣在手</b>；
 *       到货入库由 7.5.1.2 的 arrive 负责。不说清的话「发出后在手没少」会被读成账丢货。
 *   <li><b>已发出是终态</b>：在途腿已在 7.5.1.2 名下，回退状态会让货权记录与在途对不上。
 *       要改这批货走 7.5.1.2 的差异登记，不是抹掉发运单。
 * </ol>
 */
public final class FirstMileShipmentModels {
  private FirstMileShipmentModels() {}

  /**
   * 发运单状态词表。与 {@code first_mile_shipment.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #allowedNext} 是唯一真相，服务层与前端共用）：
   * {@code DRAFT → DRAFT / CONFIRMED / CANCELLED}、
   * {@code CONFIRMED → CONFIRMED / DISPATCHED / CANCELLED}、
   * {@code DISPATCHED} 与 {@code CANCELLED} 为终态。
   *
   * <p>两个<b>自环</b>（DRAFT→DRAFT、CONFIRMED→CONFIRMED）不是状态变更，而是**改单**：
   * 改单整份替换明细但状态不变。前端据此判断「这张单还能不能编辑」——
   * 少了自环，草稿单会被误判成不可改，而草稿恰恰是最该能改的那一张。
   */
  public enum ShipmentStatus {
    /** 草稿：可自由改单，不动任何账。 */
    DRAFT("草稿"),
    /** 已确认：申报信息齐备、已订舱，可改单可发出。 */
    CONFIRMED("已确认"),
    /** 已发出：已在 7.5.1.2 登记在途腿，货权已转移。终态。 */
    DISPATCHED("已发出"),
    /** 已取消：终态。取消必须给原因。 */
    CANCELLED("已取消");

    private final String label;

    ShipmentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ShipmentStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的头程发运单状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<ShipmentStatus> allowedNext(ShipmentStatus current) {
      return switch (current) {
        case DRAFT -> List.of(DRAFT, CONFIRMED, CANCELLED);
        case CONFIRMED -> List.of(CONFIRMED, DISPATCHED, CANCELLED);
        case DISPATCHED -> List.of();
        case CANCELLED -> List.of();
      };
    }
  }

  /**
   * 货物来源词表。
   *
   * <p>{@code GOODS_RECEIPT} 指 8.2.1.1 收货单（已交付，迁移 V70），建单时会真去校验
   * 来源收货单存在、属于本租户，且每一行指明的实收批次真的属于那张收货单。
   *
   * <p>{@code SUPPLIER_PURCHASE} 指 6 采购（**6.1–6.6 全部 36 个模块未交付**），
   * 在 {@link FirstMileCapabilities} 里显式 {@code available=false} 并给出原因，
   * **不建占位表、不返回空集冒充成功**，create 直接拒绝该来源。
   */
  public enum ShipmentSourceType {
    /** 8.2.1.1 收货单。已交付，可真实验证来源单据与批次存在。 */
    GOODS_RECEIPT("收货单", "8.2.1.1"),
    /** 6 采购单。上游 6 采购与供应未交付。 */
    SUPPLIER_PURCHASE("供应商采购单", "6.3.2.2");

    private final String label;
    private final String ownerModule;

    ShipmentSourceType(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    public String label() {
      return label;
    }

    /** 该来源维度的事实所有者模块号。 */
    public String ownerModule() {
      return ownerModule;
    }

    public static ShipmentSourceType parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的货物来源类型："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /** 运输方式词表。蓝图 10.4.1.1 原文点名的四类：海运 / 空运 / 铁运 / 快递。 */
  public enum TransportMode {
    SEA("海运", 35),
    AIR("空运", 6),
    RAIL("铁运", 18),
    EXPRESS("快递", 5);

    private final String label;
    private final int typicalTransitDays;

    TransportMode(String label, int typicalTransitDays) {
      this.label = label;
      this.typicalTransitDays = typicalTransitDays;
    }

    public String label() {
      return label;
    }

    /** 常见运输时长（天），仅作录入参考；真实 ETA 一律以单上的 expectedArriveAt 为准。 */
    public int typicalTransitDays() {
      return typicalTransitDays;
    }

    public static TransportMode parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的运输方式："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /**
   * 国际贸易术语词表。与 {@code first_mile_shipment.incoterm} 的 CHECK 逐值对应。
   *
   * <p>这是**成本转嫁方式**的权威词表：不同术语下运费、保险、关税由谁付、
   * 报价口径完全不同，直接决定 12 财务与 13 利润的「真实成本」。
   */
  public enum Incoterm {
    EXW("EXW 工厂交货", "SELLER_TO_BUYER_AT_SELLER_PREMISES"),
    FCA("FCA 货交承运人", "SELLER_TO_BUYER_AT_ORIGIN"),
    FAS("FAS 装运港船边交货", "SELLER_TO_BUYER_AT_ORIGIN"),
    FOB("FOB 装运港船上交货", "SELLER_TO_BUYER_AT_ORIGIN"),
    CFR("CFR 成本加运费", "SELLER_TO_BUYER_AT_ORIGIN"),
    CIF("CIF 成本保险加运费", "SELLER_TO_BUYER_AT_DESTINATION"),
    CPT("CPT 运费付至", "SELLER_TO_BUYER_AT_ORIGIN"),
    CIP("CIP 运费保险付至", "SELLER_TO_BUYER_AT_DESTINATION"),
    DAP("DAP 目的地交货", "SELLER_TO_BUYER_AT_DESTINATION"),
    DPU("DPU 目的地卸货交货", "SELLER_TO_BUYER_AT_DESTINATION"),
    DDP("DDP 完税后交货", "SELLER_UNTIL_DESTINATION");

    private final String label;
    private final String riskPoint;

    Incoterm(String label, String riskPoint) {
      this.label = label;
      this.riskPoint = riskPoint;
    }

    public String label() {
      return label;
    }

    /** 风险转移点的机读描述，供前端渲染「货权在哪一刻转」。 */
    public String riskPoint() {
      return riskPoint;
    }

    public static Incoterm parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的贸易术语："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /**
   * 风险承担方词表。
   *
   * <p>与 {@link Incoterm} 配对使用。DB 层钉死两条真实约束：
   * {@code EXW} 只能配 {@code BUYER}（风险在卖方场所即转移），
   * {@code DDP} 只能配 {@code SELLER}（卖方负责到目的地）。
   */
  public enum RiskBearer {
    SELLER("卖方承担"),
    BUYER("买方承担"),
    CARRIER("承运人承担");

    private final String label;

    RiskBearer(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static RiskBearer parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的风险承担方："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /**
   * 维度接入情况。
   *
   * <p>沿用仓库既有约定（8.2.1.1 {@code ReceiptSourceCapability}、7.5.1.2 capabilities）：
   * {@code available=false} 时 {@code href} 仍然给出 —— 它是**路由契约**而不是死链，
   * 指明这件事落在哪个模块、上游交付后直接可用；{@code note} 说明当前为什么不可用，
   * 避免界面上出现点下去没反应或 404 的按钮。
   *
   * <p>名字刻意带 {@code FirstMile} 前缀：7.1.1.2 已经有一个 {@code DimensionCapability}，
   * 而四个服务的 schema 被 codegen 拍平进同一命名空间，**重名会被静默覆盖且没有任何警告**
   * （AGENTS.md 契约纪律）。本类型照抄那套形状，但必须换一个自己的名字。
   */
  public record FirstMileDimension(
      String value,
      String label,
      boolean available,
      String ownerModule,
      String href,
      String note) {}

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public record ShipmentTransition(String from, String to, boolean allowed, String note) {}

  public record ShipmentStatusOption(String value, String label, long shipmentCount) {}

  /** 运输方式下拉，附本租户真实单数与常见时长（录入 ETA 时的参考，不是强制值）。 */
  public record TransportModeOption(
      String value, String label, long shipmentCount, int typicalTransitDays) {}

  /** 贸易术语下拉，附风险转移点（货权在哪个节点转）。 */
  public record IncotermOption(
      String value, String label, String riskPoint, List<String> allowedRiskBearers) {}

  public record RiskBearerOption(String value, String label) {}

  /**
   * 头程发运能力：来源维度接入情况、四态状态机、运输方式与贸易术语词表、
   * **未交付依赖的逐条声明**，以及四条必须如实讲清楚的口径说明。
   *
   * <p>前端据此渲染来源下拉、运输方式选择、货权/风险选择与提示，
   * 而不是把规则硬编码在界面上 —— 规则改了就该只改一处。
   */
  public record FirstMileCapabilities(
      List<FirstMileDimension> sources,
      List<FirstMileDimension> dependencies,
      List<ShipmentTransition> transitions,
      List<ShipmentStatusOption> statusOptions,
      List<TransportModeOption> transportModes,
      List<IncotermOption> incoterms,
      List<RiskBearerOption> riskBearers,
      String transitRegistrationNote,
      String titleTransferNote,
      String declarationNote,
      String costNote) {}

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record FirstMileQuery(
      List<String> status,
      String transportMode,
      String incoterm,
      UUID originWarehouseId,
      UUID destinationWarehouseId,
      String destinationCountryCode,
      Boolean inTransitOnly,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 头程发运单列表行。 */
  public record FirstMileRow(
      UUID id,
      String shipmentCode,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      UUID legalEntityId,
      String legalEntityName,
      UUID originWarehouseId,
      String originWarehouseCode,
      String originWarehouseName,
      UUID destinationWarehouseId,
      String destinationWarehouseCode,
      String destinationWarehouseName,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String transportModeLabel,
      String carrierName,
      String bookingNo,
      String incoterm,
      String incotermLabel,
      String riskBearer,
      String riskBearerLabel,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal dutyEstimate,
      BigDecimal handlingCost,
      BigDecimal totalCost,
      BigDecimal declaredValue,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      long lineCount,
      long transitLegCount,
      String status,
      String statusLabel,
      @Schema(nullable = true) OffsetDateTime departAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) String cancelReason,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 列表页的全量汇总（**不受分页影响**）。
   *
   * <p>{@code inTransitShipmentCount} / {@code inTransitQuantity} 是补货与断货预警最直接的输入：
   * 已确认与已发出的单就是「在路上还没到」的头程。
   */
  public record FirstMileSummary(
      long shipmentCount,
      long lineCount,
      BigDecimal declaredValue,
      BigDecimal totalCost,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      long draftCount,
      long confirmedCount,
      long dispatchedCount,
      long cancelledCount,
      long inTransitShipmentCount,
      BigDecimal inTransitQuantity,
      long seaCount,
      long airCount,
      long railCount,
      long expressCount,
      @Schema(nullable = true) OffsetDateTime earliestCreatedAt) {}

  /** 列表页。{@code summary} 是全量汇总，下拉词表随响应一起给。 */
  public record FirstMilePage(
      FirstMileSummary summary,
      List<FirstMileRow> rows,
      List<ShipmentStatusOption> statusOptions,
      List<TransportModeOption> transportModes,
      List<WarehouseOption> warehouseOptions,
      long total,
      int page,
      int size) {}

  public record WarehouseOption(UUID id, String code, String name, String countryCode) {}

  /** 明细行 = 一个 SKU × 一个批次 × 一份申报信息。 */
  public record FirstMileLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      @Schema(nullable = true) UUID sourceReceiptEventLineId,
      @Schema(nullable = true) String sourceReceiptCode,
      @Schema(nullable = true) String sourceWaybillNo,
      @Schema(nullable = true) OffsetDateTime sourceReceivedAt,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      // 效期用 date 而非 timestamptz（与 8.2.1.1 同口径）：效期是日历日概念，
      // 用 timestamptz 会让「2027-03-01 过期」在时区换算后漂成 02-28 或 03-02。
      @Schema(nullable = true) LocalDate lotExpiryDate,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal pendingQuantity,
      String declaredItemName,
      String hsCode,
      String brand,
      String originCountryCode,
      BigDecimal declaredUnitValue,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal netWeightKg,
      BigDecimal volumeCbm,
      int cartonCount,
      @Schema(nullable = true) UUID transitLegId,
      @Schema(nullable = true) String transitLegStatus,
      @Schema(nullable = true) BigDecimal transitSettledQuantity,
      long version) {}

  /** 一次业务动作留痕。货权什么时候转、当时申报了什么、谁点的发出，都要能逐笔回放。 */
  public record FirstMileEventRow(
      UUID id,
      String kind,
      OffsetDateTime occurredAt,
      String fromStatus,
      String toStatus,
      UUID actorId,
      String reason,
      String notes) {}

  /**
   * 发货分录（在途腿）。
   *
   * <p>{@code transitLegId} 是 7.5.1.2 {@code inventory_transfer} 的行 id（逻辑引用）。
   * 本模块**只调它的公开端点登记，从不直写那张表** —— 它的写入所有者是 7.5.1.2。
   */
  public record FirstMileTransitLegRow(
      UUID id,
      UUID lineId,
      int lineNo,
      String skuCode,
      String skuName,
      UUID transitLegId,
      String transitKind,
      String transitStatus,
      BigDecimal quantity,
      BigDecimal settledQuantity,
      BigDecimal outstandingQuantity,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      String carrierName,
      String trackingNo,
      String sourceSystem,
      String sourceReference,
      OffsetDateTime registeredAt) {}

  /** 头程发运单详情：头 + 明细 + 在途腿 + 事件时间线 + 合法下一步。 */
  public record FirstMileDetail(
      FirstMileRow shipment,
      List<FirstMileLineRow> lines,
      List<FirstMileTransitLegRow> transitLegs,
      List<FirstMileEventRow> events,
      List<ShipmentTransition> transitions,
      String transitNote,
      String titleTransferNote) {}

  /**
   * 可发批次候选项（8.2.1.1 逐次实收行）。
   *
   * <p>{@code shippableQuantity} = 实收量 − 已被其他发运单发走的量。头程发运的输入是
   * 「选择货物与批次」，而批次挂在**逐次实收**上（同一 SKU 分三车到货可以是三个批次），
   * 所以候选项必须精确到实收行，而不是精确到 SKU。
   */
  public record SourceCandidateRow(
      UUID receiptEventLineId,
      UUID receiptId,
      String receiptCode,
      UUID eventId,
      OffsetDateTime receivedAt,
      String waybillNo,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      @Schema(nullable = true) LocalDate lotExpiryDate,
      BigDecimal receivedQuantity,
      BigDecimal alreadyShippedQuantity,
      BigDecimal shippableQuantity,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName) {}

  /** 选货助手分页。 */
  public record SourceCandidatePage(
      List<SourceCandidateRow> rows,
      BigDecimal totalShippableQuantity,
      long candidateCount,
      int page,
      int size) {}

  /** 建单请求。 */
  public record FirstMileCreateRequest(
      String sourceType,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      UUID legalEntityId,
      UUID originWarehouseId,
      UUID destinationWarehouseId,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String carrierName,
      String bookingNo,
      String vesselOrFlightNo,
      String incoterm,
      String riskBearer,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal dutyEstimate,
      BigDecimal handlingCost,
      String customsBroker,
      @Schema(nullable = true) OffsetDateTime departAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      String notes,
      List<FirstMileLineRequest> lines) {}

  /**
   * 建单/改单的一条明细。
   *
   * <p>{@code sourceReceiptEventLineId} 指明「哪一次实收的哪一批货」——
   * 收到货但没登记过实收的货**发不走**（没有实物批次就没有可追的货权线索）。
   */
  public record FirstMileLineRequest(
      UUID skuId,
      UUID qualityStatusId,
      @Schema(nullable = true) UUID sourceReceiptEventLineId,
      @Schema(nullable = true) UUID lotId,
      BigDecimal plannedQuantity,
      String declaredItemName,
      String hsCode,
      String brand,
      String originCountryCode,
      BigDecimal declaredUnitValue,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg,
      BigDecimal netWeightKg,
      BigDecimal volumeCbm,
      Integer cartonCount) {}

  /** 改单请求：{@code expectedVersion} 是乐观锁，null 视为非法（不静默放行）。 */
  public record FirstMileUpdateRequest(
      Long expectedVersion,
      String sourceRefCode,
      UUID destinationWarehouseId,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String carrierName,
      String bookingNo,
      String vesselOrFlightNo,
      String incoterm,
      String riskBearer,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal dutyEstimate,
      BigDecimal handlingCost,
      String customsBroker,
      @Schema(nullable = true) OffsetDateTime departAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      String notes,
      List<FirstMileLineRequest> lines) {}

  /** 确认请求：申报信息齐备、已订舱。货还没走，所以不动任何账。 */
  public record FirstMileConfirmRequest(
      Long expectedVersion,
      @Schema(nullable = true) OffsetDateTime departAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      String notes) {}

  /**
   * 发出请求：**货权转移与转在途就在这一刻发生**。
   *
   * <p>{@code lines} 给出时逐行给实际发出量（允许部分发运）；不给时全部按计划量发出。
   * 两种都是真实行为，界面上必须显示每行实际发出量，不允许「发了多少」不可见。
   *
   * <p>{@code shippedAt} 不给时取单上的 {@code departAt}，再不给才取当前时刻。
   */
  public record FirstMileDispatchRequest(
      Long expectedVersion,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      String notes,
      List<FirstMileDispatchLineRequest> lines) {}

  /** 发出请求的一行。 */
  public record FirstMileDispatchLineRequest(UUID lineId, BigDecimal shippedQuantity) {}

  /** 取消请求：必须给原因（货权与在途都会因取消而变）。 */
  public record FirstMileCancelRequest(
      Long expectedVersion, String reason, String notes) {}

  /** 建单/改单/确认/发出/取消后的回读结果（响应是「库里真实存了什么」）。 */
  public record FirstMileSaved(
      UUID id,
      String shipmentCode,
      String status,
      String statusLabel,
      long version,
      BigDecimal plannedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal declaredValue,
      BigDecimal totalCost,
      String incoterm,
      String incotermLabel,
      String riskBearer,
      String riskBearerLabel,
      long transitLegCount,
      @Schema(nullable = true) OffsetDateTime departAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt) {}
}
