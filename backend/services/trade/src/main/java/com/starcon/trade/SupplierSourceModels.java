package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 6.1.2.1 档口与货源池的传输模型。
 *
 * <p>蓝图原话：「管理 1688/义乌购等货源渠道的店铺档口，抓取与同步在售货源（标题、起订量、
 * 价格区间、规格、图片），支持收藏、比价、建立与内部 SKU 的货源关联。」
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>快照语义只在一处落地</b>：价格区间 / 起订量 / 标题 / 采集时刻只出现在
 *       {@link SourceSnapshotRow}（一版快照一行）；{@link SourceItemRow} 上它们是
 *       「**当前快照**里的值」，字段名一律带 {@code Current} 前缀的语义说明 ——
 *       「当前价」与「快照价」在表结构里不是同一列，在传输模型里也不是同一个字段。
 *   <li><b>价格区间永远是区间</b>：{@code priceMin} 与 {@code priceMax} 同时出现，
 *       两者都是 BigDecimal（DB 侧 numeric(19,4)），**没有**任何地方把区间压成一个
 *       「精确价」字段 —— 比价时区间重叠就返回区间。
 *   <li><b>数量全程 BigDecimal</b>，禁浮点；起订量列一律 NUMERIC(19,6)。
 *   <li><b>状态词表只有一处</b>：枚举一字不改地对应 DB 的 CHECK。
 * </ul>
 */
public final class SupplierSourceModels {

  private SupplierSourceModels() {}

  // ------------------------------------------------------------ 枚举

  /** 货源平台（DB 词表，封闭）。每个平台一个 vendor 适配器。 */
  public enum SourcePlatform {
    ALIBABA_1688("1688 开放平台"),
    YIWU_GOU("义乌购");

    private final String label;

    SourcePlatform(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 采集方式（DB 词表）。对应蓝图输入句「档口 URL 或插件采集」。 */
  public enum CollectMethod {
    /** 本服务按档口 URL 主动抓取（走 vendor 适配器）。 */
    URL_SYNC("按档口 URL 主动抓取"),
    /** 外部插件把采到的货源条目推上来（本服务不主动发起外部请求）。 */
    PLUGIN_PUSH("插件采集推送");

    private final String label;

    CollectMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 档口状态（DB 词表）。停用后不再采集，历史货源照常可读。 */
  public enum StallStatus {
    ACTIVE("启用"),
    PAUSED("停用");

    private final String label;

    StallStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 采集批次状态（DB 词表）。失败批次**零快照写入**。 */
  public enum CollectionStatus {
    SUCCEEDED("成功"),
    FAILED("失败");

    private final String label;

    CollectionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 采集触发来源（DB 词表）。 */
  public enum TriggerSource {
    MANUAL("人工触发"),
    PLUGIN("插件推送"),
    SCHEDULED("定时任务");

    private final String label;

    TriggerSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 词表 / 选项

  /** 一个词表选项（值 / 中文名）。 */
  public record SupplierSourceOption(String value, String label, String hint) {}

  /** 一个可排序键。 */
  public record SupplierSourceSortOption(String key, String label, boolean defaultDesc) {}

  /** 采集接入登记（**只存凭证引用，不存明文**）。 */
  public record CollectorEndpointRow(
      String platform,
      String platformLabel,
      String baseUrl,
      /** 凭证引用名（指向 secret manager 的键）。**不是**密钥本身。 */
      String credentialRef,
      boolean wired,
      String status,
      String note,
      String updatedAt) {}

  /** 一个可选供应商候选（catalog 的 SUPPLIER 角色，只读）。 */
  public record SupplierCandidateRow(
      UUID roleId, String partyCode, String partyName, String partyStatus) {}

  /** 词表选项：前端下拉的数据源，与后端同源。 */
  public record SupplierSourceOptions(
      List<SupplierSourceOption> platforms,
      List<SupplierSourceOption> collectMethods,
      List<SupplierSourceOption> statuses,
      List<SupplierSourceSortOption> sorts,
      /** 哪些平台已登记采集接入（未登记 = 未接线，同步会显式失败）。 */
      List<CollectorEndpointRow> collectorEndpoints,
      String note) {}

  // ------------------------------------------------------------ 档口

  /** 档口（店铺）一行。 */
  public record StallRow(
      UUID id,
      String code,
      String platform,
      String platformLabel,
      String platformStallId,
      String name,
      String url,
      String collectMethod,
      String collectMethodLabel,
      String status,
      String statusLabel,
      /** 关联的供应商（catalog SUPPLIER 角色的逻辑 ID；未关联为 null）。 */
      @Schema(nullable = true) UUID supplierPartyId,
      /** 关联时刻的供应商名快照。 */
      String supplierName,
      /** 最近一次采集时刻（从未采集为 null）。 */
      @Schema(nullable = true) String lastCollectedAt,
      /** 最近一次采集结果（从未采集为 null）。 */
      @Schema(nullable = true) String lastCollectionStatus,
      /** 最近一次采集的失败原因（成功时为空串）。 */
      String lastFailureReason,
      /** 当前在售货源条目数（真聚合，不受分页影响）。 */
      long itemCount,
      /** 收藏数。 */
      long favoriteCount,
      /** 已关联的内部 SKU 数。 */
      long skuLinkCount,
      String note,
      long version) {}

  /** 档口详情：档口头 + 当前货源条目 + 采集批次历史。 */
  public record StallDetail(
      StallRow stall,
      List<SourceItemRow> items,
      List<CollectionRow> collections,
      /** 该档口下各平台的采集接入状态（未接线时界面直接显示「未登记」）。 */
      CollectorEndpointRow collectorEndpoint,
      String note) {}

  /** 档口登记 / 修改入参。 */
  public record StallInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 32) String platform,
      @NotBlank @Size(max = 128) String platformStallId,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 2000) String url,
      @NotBlank @Size(max = 24) String collectMethod,
      /** 关联的供应商（catalog SUPPLIER 角色 id）。留空表示暂不关联。 */
      @Schema(nullable = true) UUID supplierPartyId,
      @Size(max = 2000) String note,
      /** 乐观锁版本号（修改时必填）。 */
      @PositiveOrZero long version) {}

  /** 档口修改入参（编码与平台建后不可改）。 */
  public record StallUpdateInput(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 2000) String url,
      @NotBlank @Size(max = 24) String collectMethod,
      @Schema(nullable = true) UUID supplierPartyId,
      @NotBlank @Size(max = 16) String status,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ------------------------------------------------------------ 采集批次

  /** 一次采集批次。 */
  public record CollectionRow(
      UUID id,
      UUID stallId,
      String collectMethod,
      String collectMethodLabel,
      String status,
      String statusLabel,
      String triggerSource,
      String triggerSourceLabel,
      /** 采集时刻（服务端写入）。 */
      String collectedAt,
      String startedAt,
      @Schema(nullable = true) String finishedAt,
      /** 本次采到的条目数（失败时为 0）。 */
      long itemCount,
      /** 因坏数据被跳过的条目数。 */
      long skippedCount,
      /** 跳过原因（无跳过时为空串）。 */
      String skippedReason,
      /** 失败原因（成功时为空串）。 */
      String failureReason) {}

  // ------------------------------------------------------------ 货源条目

  /**
   * 货源条目一行。
   *
   * <p>价格 / 起订量 / 标题来自**当前快照**（{@code currentSnapshotId} 指向的那一版），
   * {@code collectedAt} 是**那一版的采集时刻** —— 不是「现在的价」。
   */
  public record SourceItemRow(
      UUID id,
      UUID stallId,
      String stallName,
      String platform,
      String platformLabel,
      String platformItemId,
      String title,
      /** 当前快照的价格下限。 */
      BigDecimal priceMin,
      /** 当前快照的价格上限。 */
      BigDecimal priceMax,
      String currency,
      /** 当前快照的起订量。 */
      BigDecimal moq,
      String spec,
      String imageUrl,
      String itemUrl,
      /** 当前快照里是否在售。 */
      boolean onSale,
      /** 当前快照的采集时刻。 */
      String collectedAt,
      /** 当前快照是第几版。 */
      int snapshotSeq,
      /** 是否已收藏。 */
      boolean favorited,
      /** 关联的供应商名快照（来自档口）。 */
      String supplierName,
      /** 关联到的内部 SKU 编码（可能多个）。 */
      List<String> skuCodes,
      /** 当前快照的 id（供价格历史下钻）。 */
      UUID currentSnapshotId) {}

  /** 货源条目详情：条目 + 它的全部快照版本（价格历史）。 */
  public record SourceItemDetail(
      SourceItemRow item, List<SourceSnapshotRow> snapshots, String note) {}

  /** 一版采集快照。**只追加**：价格 / 起订量 / 标题永不改写。 */
  public record SourceSnapshotRow(
      UUID id,
      UUID itemId,
      int snapshotSeq,
      String title,
      BigDecimal priceMin,
      BigDecimal priceMax,
      String currency,
      BigDecimal moq,
      String spec,
      String imageUrl,
      String itemUrl,
      boolean onSale,
      /** 采集时刻。 */
      String collectedAt,
      UUID collectionId,
      String collectionStatus,
      /** 是否为当前版本。 */
      boolean current) {}

  /** 货源条目列表分页结果。 */
  public record SourceItemPage(
      List<SourceItemRow> items,
      int page,
      int size,
      long total,
      /** 不受筛选与分页影响的汇总。 */
      long onSaleCount,
      long offSaleCount,
      long favoriteCount,
      long linkedSkuCount,
      String note) {}

  /** 档口列表分页结果。 */
  public record StallPage(
      List<StallRow> items,
      int page,
      int size,
      long total,
      long activeCount,
      long pausedCount,
      long failedCollectionCount,
      String note) {}

  // ------------------------------------------------------------ 采集推送

  /** 一条被采到的货源（插件推送或外部适配器返回的原始形状）。 */
  public record CollectedItemInput(
      @NotBlank @Size(max = 128) String platformItemId,
      @NotBlank @Size(max = 400) String title,
      /** 价格区间下限。 */
      @NotNull @DecimalMin(value = "0", inclusive = false)
          @Digits(integer = 15, fraction = 4)
          BigDecimal priceMin,
      /** 价格区间上限（不得低于下限）。 */
      @NotNull @DecimalMin(value = "0", inclusive = false)
          @Digits(integer = 15, fraction = 4)
          BigDecimal priceMax,
      /** ISO 4217 三位大写币种。 */
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      /** 起订量。 */
      @NotNull @DecimalMin(value = "0", inclusive = false)
          @Digits(integer = 13, fraction = 6)
          BigDecimal moq,
      @Size(max = 200) String spec,
      @Size(max = 2000) String imageUrl,
      @Size(max = 2000) String itemUrl,
      /** 是否在售（外部下架的货显式传 false）。 */
      Boolean onSale) {}

  /** 插件推送的一次采集结果。 */
  public record CollectionPushInput(
      @NotEmpty @Size(max = 500) @Valid List<CollectedItemInput> items) {}

  // ------------------------------------------------------------ SKU 关联 / 比价

  /** 与内部 SKU 的货源关联入参。 */
  public record SkuLinkInput(
      @NotNull UUID skuId, @Size(max = 2000) String note) {}

  /** 一条 SKU 货源关联。 */
  public record SkuLinkRow(
      UUID id,
      UUID itemId,
      String platformItemId,
      String title,
      UUID stallId,
      String stallName,
      String platform,
      String supplierName,
      BigDecimal priceMin,
      BigDecimal priceMax,
      String currency,
      BigDecimal moq,
      String spec,
      String collectedAt,
      boolean onSale,
      boolean favorited,
      String linkedAt,
      String note) {}

  /** 比价候选货源（按币种 + 价格下限升序）。 */
  public record SkuCandidate(
      UUID itemId,
      UUID stallId,
      String stallName,
      String platform,
      String platformLabel,
      String platformItemId,
      String title,
      String supplierName,
      BigDecimal priceMin,
      BigDecimal priceMax,
      String currency,
      BigDecimal moq,
      String spec,
      String collectedAt,
      boolean onSale,
      boolean favorited,
      /** 是否是该币种下的最低价候选。 */
      boolean lowestInCurrency) {}

  /** 某币种下的最低价候选。 */
  public record LowestPrice(
      String currency, UUID itemId, String title, BigDecimal priceMin, BigDecimal priceMax, BigDecimal moq) {}

  /** SKU 货源关联视图 + 比价。 */
  public record SkuSourceView(
      UUID skuId,
      String skuCode,
      String skuName,
      /** 关联数与候选数（候选 = 当前在售的货源）。 */
      long linkCount,
      List<SkuCandidate> candidates,
      /** 各币种下的最低价（**不跨币种比价**：本模块不做汇率换算）。 */
      List<LowestPrice> lowestByCurrency,
      String note) {}

  // ------------------------------------------------------------ 概览 / 能力声明

  /** 概览统计（不受筛选与分页影响）。 */
  public record SourceOverview(
      long stallCount,
      long activeStallCount,
      long pausedStallCount,
      long itemCount,
      long onSaleCount,
      long offSaleCount,
      long favoriteCount,
      long skuLinkCount,
      long snapshotCount,
      long failedCollectionCount,
      @Schema(nullable = true) String lastCollectedAt,
      String note) {}

  /** 一个未交付的下游 / 维度。 */
  public record SourceGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /** 能力声明：把「哪些维度真能取到、哪些取不到」显式报出来。 */
  public record SupplierSourceCapabilities(
      List<SupplierSourceOption> platforms,
      List<SupplierSourceOption> collectMethods,
      /** 各平台的采集接入是否已登记（未登记 = 主动抓取会显式失败）。 */
      List<CollectorEndpointRow> collectorEndpoints,
      List<SourceGap> gaps,
      /** 快照语义说明（界面上要照实显示给运营）。 */
      String snapshotSemantics,
      String note) {}

  /** 收藏入参（可选备注）。 */
  public record FavoriteInput(@Size(max = 2000) String note) {}

  /** 收藏结果。 */
  public record FavoriteSaved(UUID itemId, boolean favorited, String favoritedAt) {}

  /** 采集接入登记入参。 */
  public record CollectorEndpointInput(
      @Size(max = 300) String baseUrl,
      /** 凭证**引用**（secret:// / kv:// / vault:// / external-secrets:// 开头）。 */
      @Size(max = 300) String credentialRef,
      /** 是否声称已接线（声称已接线就必须同时给出地址与引用）。 */
      Boolean wired,
      @Size(max = 2000) String note) {}

  /** 采集接入登记结果。 */
  public record CollectorEndpointSaved(
      String platform, String baseUrl, String credentialRef, boolean wired, long version) {}

  /** 同步结果（成功 / 失败都如实返回，失败带原因）。 */
  public record SyncResult(
      UUID stallId,
      UUID collectionId,
      String status,
      String statusLabel,
      /** 采集到的条目数（失败为 0）。 */
      long itemCount,
      /** 因坏数据被跳过的条目数。 */
      long skippedCount,
      /** 跳过原因（成功且无跳过时为空串）。 */
      String skippedReason,
      /** 失败原因（成功时为空串）。 */
      String failureReason,
      /** 采集时刻（服务端写入）。 */
      String collectedAt) {}

  // ------------------------------------------------------------ 查询条件

  /** 档口列表的查询条件。 */
  public record StallQuery(
      @Schema(nullable = true) String platform,
      @Schema(nullable = true) String collectMethod,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String keyword,
      /** 只看有收藏货源的档口。 */
      boolean favoritedOnly,
      int page,
      int size) {}

  /** 货源条目列表的查询条件。 */
  public record SourceItemQuery(
      @Schema(nullable = true) UUID stallId,
      @Schema(nullable = true) String platform,
      @Schema(nullable = true) String keyword,
      /** 只看在售（on_sale=true）/ 只看下架（false）；null = 全部。 */
      @Schema(nullable = true) Boolean onSale,
      /** 只看已收藏。 */
      boolean favoritedOnly,
      /** 只看关联到这个内部 SKU 的货源。 */
      @Schema(nullable = true) UUID skuId,
      /** 价格区间筛选：**重叠**即命中（区间与区间相交，不是「价格落在区间内」）。 */
      @Schema(nullable = true) BigDecimal priceFrom,
      @Schema(nullable = true) BigDecimal priceTo,
      /** 排序键白名单。 */
      String sort,
      boolean desc,
      int page,
      int size) {}
}
