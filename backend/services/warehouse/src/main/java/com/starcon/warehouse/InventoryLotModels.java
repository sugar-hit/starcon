package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 台账页（7.1.2.1 的响应）。批次出入库历史直接复用它，所以这里引它的类型。 */
import com.starcon.warehouse.InventoryLedgerModels.InventoryLedgerPage;

/**
 * 7.3.1.1 批次管理的传输模型。
 *
 * <p>蓝图原文：「管理批次（lot）：批号、生产日期、失效日期、来源采购单/供应商，
 * 支持按批次查询库存与流向追溯」，输出「批次档案 + 批次库存分布 + 出入库历史」。
 *
 * <p><b>批次与序列号是两个独立实体</b>：蓝图明确点名「不能合成一个『批次序列号』字段」。
 * 本模块只管批次，序列号级库存是 7.3.1.2 的职责，两者的表、端点与模型都互不相干 ——
 * 合成一个字段会让"这批货里哪几件是单独跟踪的"变成一个无法回答的问题。
 *
 * <p><b>生产日期 / 失效日期是业务日期不是时间戳</b>：它们是产线与包装上写死的事实，
 * 用 {@link LocalDate} + PG {@code date} 列承载。用 {@code timestamptz} 硬套会让
 * 「哪天生产的」随服务器时区漂一天，FEFO 的排序也会跟着错。
 *
 * <p><b>来源采购单/供应商是跨服务定位键</b>：采购(6) 与收货(8) 不在本服务，
 * 因此只有 {@code sourceSystem + sourceRefId} 与 {@code supplierRefId + supplierName}，
 * **没有外键、也不联查对方表**（AGENTS.md 的模块边界纪律）。单号与供应商名在建立批次时
 * 固化成字符串快照，让追溯不依赖跨服务联查。
 *
 * <p><b>派生字段不算假数据</b>：{@code expired}（失效日期早于今天）与 {@code depleting}（在手为 0）
 * 是查询时按当前日期与真实聚合算出来的，不是写死的标志位。真正的 status 仍在
 * {@code inventory_lot.status} 里，由人与收货流程维护，两者不混为一谈。
 */
public final class InventoryLotModels {

  private InventoryLotModels() {}

  // ------------------------------------------------------------ 词表

  /** 批次建立方式。RECEIPT = 收货时生成（8 收货调用），MANUAL = 手工建立。 */
  public enum LotOrigin {
    RECEIPT("收货生成"),
    MANUAL("手工建立");

    private final String label;

    LotOrigin(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 未知码 400：筛一个不存在的建立方式返回空列表，会被误读成"没有这种批次"。 */
    public static LotOrigin parse(String value) {
      for (var origin : values()) {
        if (origin.name().equalsIgnoreCase(value.trim())) return origin;
      }
      throw new IllegalArgumentException(
          "未知的批次建立方式：" + value + "，可选：" + names());
    }

    public static String names() {
      return String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList());
    }
  }

  /**
   * 来源系统。与 {@code inventory_lot.source_system} 的 CHECK 逐值对应。
   *
   * <p>它是**跨服务协议字符串**，不是本库的一张表：PURCHASE 指 6 采购的采购单，
   * RECEIPT 指 8 收货的收货单。所以做成词表枚举是为了挡住拼写错误，不是为了建外键。
   */
  public enum LotSourceSystem {
    PURCHASE("采购单"),
    RECEIPT("收货单"),
    MANUAL("手工补录");

    private final String label;

    LotSourceSystem(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static LotSourceSystem parse(String value) {
      for (var system : values()) {
        if (system.name().equalsIgnoreCase(value.trim())) return system;
      }
      throw new IllegalArgumentException(
          "未知的来源系统：" + value + "，可选：" + names());
    }

    public static String names() {
      return String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList());
    }
  }

  // ------------------------------------------------------------ 建立与修改

  /**
   * 建立批次（收货生成与手工建立共用一条入口）。
   *
   * <p>{@code origin} 由调用方显式声明而不是从 {@code sourceSystem} 反推：手工补登历史批次时
   * 也会带来源单据，那种情况下"它是收货生成的还是人建的"是两条不同的信息。
   *
   * @param skuId 批次所属 SKU。一个批次只属于一个 SKU（V2 的 UNIQUE(tenant,sku,lot_code) 就是这么定的）
   * @param lotCode 批号，同 SKU 下唯一
   * @param producedOn 生产日期（业务日期）
   * @param expiresOn 失效日期（业务日期）。不传表示非效期品
   * @param receivedOn 收货日期（业务日期）。不传则取服务端当天 —— 手工补登时"登记日"就是收货日
   * @param origin 建立方式
   * @param sourceSystem / sourceRefId 跨服务来源定位键；origin=RECEIPT 时**必填**（DB 的 ck_lot_origin_source 也兜一道）
   * @param sourceRefCode 来源单号快照，让界面不必联查采购/收货服务就能显示"来自哪张单"
   * @param supplierRefId 供应商逻辑 ID（跨服务，不建外键）
   * @param supplierName 供应商名快照
   * @param notes 备注（质检结论、退运原因等）
   */
  public record LotArchiveCreateRequest(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 64) String lotCode,
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) LocalDate receivedOn,
      @Schema(nullable = true) String origin,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) UUID sourceRefId,
      @Size(max = 64) String sourceRefCode,
      @Schema(nullable = true) UUID supplierRefId,
      @Size(max = 128) String supplierName,
      @Size(max = 500) String notes) {}

  /**
   * 修改批次属性。
   *
   * <p>刻意**不允许改批号、SKU 与 origin**：三者都是批次的身份，改了等于换了另一个批次，
   * 而 {@code stock_bucket} 里挂着的 {@code lot_id} 还指着旧身份，就地改会让历史无法自洽。
   * 要换批号请新建批次。
   *
   * <p>status 可改（ACTIVE / DEPLETED / EXPIRED）：它是运营对批次状态的判断，不是身份。
   */
  public record LotArchiveUpdateRequest(
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID supplierRefId,
      @Size(max = 128) String supplierName,
      @Size(max = 500) String notes) {}

  /**
   * 建立/修改的结果。
   *
   * @param receivedOn 落库后的实际收货日期：没传时是服务端当天，回显出来才不会让人以为没记上
   * @param expired 按当前日期算出的派生值（失效日期早于今天），与 status 分开表达
   */
  public record LotArchiveSaved(
      UUID lotId,
      String lotCode,
      UUID skuId,
      String origin,
      String status,
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) LocalDate receivedOn,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierName,
      boolean expired,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  // ------------------------------------------------------------ 查询条件

  /**
   * 批次列表查询条件。
   *
   * @param skuId 只看该 SKU 的批次
   * @param warehouseId 只看**当前在该仓有库存**的批次：批次可能横跨多个仓，
   *     这是 exists 到 stock_bucket 上的存在性过滤，不是"这个批次的货来自哪个仓"
   * @param status 批次状态多选（ACTIVE / DEPLETED / EXPIRED）
   * @param origin 建立方式多选
   * @param sourceSystem 来源系统多选
   * @param supplierRefId 按供应商筛
   * @param keyword 关键字：匹配批号、SKU 编码/名称、来源单号、供应商名
   * @param expiringWithinDays 只看 N 天内到期的批次（不传 = 不按效期筛）。这是**读侧**筛选，
   *     临期预警与 FEFO 推荐归 7.3.1.2，本模块只提供取数，不产生预警
   * @param inStock true = 只看在手 > 0 的批次
   * @param sort 排序键（白名单）
   */
  public record LotArchiveQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) List<String> status,
      @Schema(nullable = true) List<String> origin,
      @Schema(nullable = true) List<String> sourceSystem,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) Integer expiringWithinDays,
      @Schema(nullable = true) Boolean inStock,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 批次档案

  /**
   * 一行 = 一个批次。
   *
   * <p>数量列是**当前**值：{@code on_hand} / {@code reserved} / {@code available} 由
   * {@code stock_bucket} 按批次实时聚合而来（可用量是 V2 的生成列 on_hand − reserved，
   * 不可能与在手漂移）。它们**不受分页影响**：合计与行上的数来自同一次全量聚合。
   *
   * @param producedOn 生产日期（业务日期）
   * @param expiresOn 失效日期（业务日期）
   * @param receivedOn 收货日期（业务日期）
   * @param expired 派生：失效日期早于服务端当天。与 status=EXPIRED 是两件事 ——
   *     status 是人工/流程维护的判断，expired 是日历事实，二者不一致时要能在界面上看出来
   * @param daysToExpiry 距离失效日期还有几天（无失效日期时为 null；已过期为负数）
   * @param bucketCount 该批次当前分布在多少个 bucket 上
   * @param locationCount 分布在多少个库位上
   * @param warehouseCount 横跨多少个仓库
   */
  public record LotArchiveRow(
      UUID lotId,
      UUID skuId,
      String skuCode,
      String skuName,
      String lotCode,
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) LocalDate receivedOn,
      String origin,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierName,
      String status,
      boolean expired,
      @Schema(nullable = true) Integer daysToExpiry,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long bucketCount,
      long locationCount,
      long warehouseCount,
      String notes,
      OffsetDateTime updatedAt) {}

  /**
   * 批次汇总（**全量**，不受分页影响）。
   *
   * <p>{@code expiredLotCount} 与 {@code depletedLotCount} 分开数：前者是日历事实，
   * 后者是数量事实，合在一起报成"问题批次"会让人分不清该先处理过期还是先处理零库存。
   *
   * @param earliestExpiry 最早的失效日期（筛出来的批次里没有失效日期时为 null）
   * @param expiringLotCount N 天内到期的批次数（与 expiringWithinDays 筛选同口径）
   */
  public record LotArchiveSummary(
      long lotCount,
      long skuCount,
      long warehouseCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long expiredLotCount,
      long depletedLotCount,
      long expiringLotCount,
      long sourceLinkedLotCount,
      long manualLotCount,
      @Schema(nullable = true) LocalDate earliestExpiry) {}

  /** 批次页。 */
  public record LotArchivePage(
      LotArchiveSummary summary,
      List<LotArchiveRow> rows,
      List<LotOption> statusOptions,
      List<LotOption> originOptions,
      List<LotOption> sourceSystemOptions,
      long total,
      int page,
      int size) {}

  /** 下拉项，带命中批次数：让用户在下拉里就能看出哪类批次多。 */
  public record LotOption(String value, String label, long lotCount) {}

  // ------------------------------------------------------------ 批次库存分布

  /**
   * 批次库存分布：**真从 {@code stock_bucket} 聚合**，不是登记一个固定数字。
   *
   * <p>蓝图 7.1.1.2 要求界面上能看到「在哪个货位、什么批次、什么质量状态」，
   * 所以这里给到库位级切片，并同时给仓库级汇总。
   *
   * <p>合计口径：{@code totalOnHand} 等于 {@code warehouses} 各行之和，也等于
   * {@code locations} 各行之和 —— 这个等式是本端点自检的凭据，测试里逐条断言。
   */
  public record LotDistribution(
      UUID lotId,
      String lotCode,
      UUID skuId,
      String skuCode,
      String skuName,
      String status,
      boolean expired,
      @Schema(nullable = true) LocalDate expiresOn,
      BigDecimal totalOnHand,
      BigDecimal totalReserved,
      BigDecimal totalAvailable,
      long bucketCount,
      long warehouseCount,
      long locationCount,
      List<LotWarehouseSlice> warehouses,
      List<LotLocationSlice> locations) {}

  /** 仓库级切片。 */
  public record LotWarehouseSlice(
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long bucketCount,
      long locationCount) {}

  /**
   * 库位级切片（最细粒度）。
   *
   * @param qualityStatusCode 质量状态码：批次相同但质量状态不同的货是分开记的
   *     （可售 / 待检 / 残次），合并显示会让"这批有多少能发"答不出来
   * @param lastMovementAt 最近一次数量变动的**业务时间**（V2 的 last_movement_at，
   *     由库存业务写入方提供），不是行被 UPDATE 的时刻
   */
  public record LotLocationSlice(
      UUID bucketId,
      UUID locationId,
      String locationCode,
      String locationName,
      String locationType,
      boolean locationPickable,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      OffsetDateTime lastMovementAt) {}

  // ------------------------------------------------------------ 出入库历史

  /**
   * 批次出入库历史。
   *
   * <p><b>数据来自 7.1.2.1 库存流水台账，本模块不另写一套流水查询</b>：
   * {@code movements} 就是 {@code InventoryLedgerService.page(...)} 的原样返回，
   * 分录下钻继续走 {@code /warehouse/inventory/ledger/{transactionId}}。
   *
   * <p><b>归属口径必须讲清（读这个端点的人第一个要知道的）</b>：流水按
   * {@code inventory_balance} 记账，而 {@code balance} 行**不含批次维度** ——
   * 批次是 {@code stock_bucket} 这一层的事实（V2 的 balance 唯一键是
   * SKU × 仓库 × 货权法人 × 质量状态，没有 lot）。因此一条分录无法被精确归属到某一个批次：
   * 同一 balance 行下可能同时挂着本批次与其它批次的货。本端点取的是
   * 「该批次当前所在 bucket 的 balance 行上发生过的全部流水」，是**批次相关流水**，
   * 当同一 balance 下并存多个批次时，其数量不可按批次拆分。
   *
   * <p>要真正按批次记流水，需要给 {@code inventory_entry} 增加批次维度 —— 那是台账表结构的
   * 变更，不属于本模块，结论记在会话日志的后续待办里。届时期末时效（FEFO）出库会在
   * {@code stock_bucket} 上扣减，库存分布这一层始终是批次精确的。
   */
  public record LotMovements(
      UUID lotId,
      String lotCode,
      String attribution,
      String attributionNote,
      InventoryLedgerPage movements) {}
}
