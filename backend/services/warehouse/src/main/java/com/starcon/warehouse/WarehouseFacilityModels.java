package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.1.1.1 仓库列表的传输模型。
 *
 * <p>职责原文：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、 国家地区、时区、启用状态、库存量与作业量，支持筛选与新增。」
 *
 * <p>本类是 8.1.1 下第一个模块，所以形状刻意给后面两个兄弟留好接口： {@code WarehouseRow} 上的 {@code timezone} / {@code kind} /
 * {@code defaultLegalEntityId} 在 8.1.1.2 详情与 8.1.1.3 配置里都是同一份事实的子集，届时不另造一套私有结构。
 */
public final class WarehouseFacilityModels {

  private WarehouseFacilityModels() {}

  // ------------------------------------------------------------ 仓库类型

  /**
   * 仓库类型（受控词表）。
   *
   * <p>与 V55 建立的四值 + V69 补的 BONDED 逐条对应，蓝图五类一个不少： 自营 / 三方 / 海外仓 / 平台仓 / 监管仓。
   *
   * <p>为什么 BONDED（监管仓）算**自有账本**而不是平台仓观测值：保税仓的货是本租户自有货， 只是处于海关监管状态。V55 的 {@code
   * guard_observation_requires_platform_warehouse} 因此会在 DB 层拒绝它写
   * platform_stock_observation，界面上也不会把两类数相加。
   */
  public enum WarehouseKind {
    /** 自营仓：自有账本。 */
    LOCAL("自营仓"),
    /** 海外仓：自有或三方运营的境外仓，数量仍记自有账本。 */
    OVERSEAS("海外仓"),
    /** 平台仓（FBA）：数量是平台观测值，不进自有账本。 */
    PLATFORM_FBA("平台仓"),
    /** 三方仓：第三方运营，数量以自有账本登记的收货/发货为准。 */
    THIRD_PARTY("三方仓"),
    /** 监管仓（保税仓）：海关监管下的自有货，数量记自有账本。 */
    BONDED("监管仓");

    private final String label;

    WarehouseKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 平台仓的数量是观测值，不进自有账本 —— 界面上必须与自有仓分开显示。 */
    public boolean platformObserved() {
      return this == PLATFORM_FBA;
    }
  }

  // ------------------------------------------------------------ 筛选条件

  /**
   * 列表查询条件（蓝图输入：「筛选条件（类型、法人、国家）」）。
   *
   * @param kind 类型过滤；null = 全部
   * @param legalEntityId 所属法人过滤；null = 全部
   * @param countryCode 国家地区过滤（ISO-3166-1 alpha-2）；null = 全部
   * @param status 启用状态过滤；null = 全部
   * @param keyword 关键字：匹配仓库编码 / 名称 / 备注
   * @param sort 排序键（白名单）：CODE / NAME / KIND / STOCK / WORK
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   */
  public record FacilityQuery(
      @Schema(nullable = true) String kind,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 列表响应

  /**
   * 列表响应。
   *
   * @param summary 全量合计（**不受分页与筛选影响**，守住「合计 == 逐行求和」这条核对线）
   * @param rows 当前页的行
   * @param total 满足筛选条件的总行数
   * @param page 当前页码
   * @param size 每页行数
   * @param detailUnavailable 详情页（8.1.1.2）尚未交付：true 时前端**禁用**进详情按钮 并说明去向，而不是跳到一个空路由
   */
  public record WarehouseFacilityPage(
      FacilitySummary summary,
      List<WarehouseRow> rows,
      long total,
      int page,
      int size,
      boolean detailUnavailable,
      String note) {}

  /**
   * 全量合计。
   *
   * @param warehouseCount 仓库总数（含停用与归档：列表要能筛到它们，否则「归档了但查不到」 会让用户以为仓库凭空消失）
   * @param activeCount 启用中
   * @param platformWarehouseCount 平台仓数：这几行的数量来自观测值，不进自有账本
   * @param onHand 自有账本在手合计 = Σ inventory_balance.on_hand（**不含**平台仓观测值）
   * @param available 自有账本可用合计 = Σ inventory_balance.available（生成列）
   * @param reserved 自有账本预占合计
   * @param platformObservedOnHand 平台仓观测在手合计 = Σ platform_stock_observation 各来源最新一条
   * @param workOrderTotal 作业单合计（盘点任务 + 调入单 + 调出单，见 {@code WorkVolume}）
   * @param openWorkOrderCount 未结作业单数
   */
  public record FacilitySummary(
      long warehouseCount,
      long activeCount,
      long platformWarehouseCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal platformObservedOnHand,
      long workOrderTotal,
      long openWorkOrderCount) {}

  /**
   * 仓库行。
   *
   * @param timezone IANA 时区 ID（如 {@code Asia/Shanghai}）。**不是裸的 UTC 偏移**：
   *     偏移量不表达夏令时规则，前端拿到「+08:00」无法判断某一天当地几点
   * @param localTime 当前时刻在该仓库当地时区的墙上时间，由服务端算好
   * @param stock 自有账本库存量
   * @param platformStock 平台仓观测值，**只在 {@code kind == PLATFORM_FBA} 时非 null**； 自有仓恒为 null ——
   *     两个来源的数在任何地方都不相加（V55 文件头的纪律）
   * @param work 作业量
   * @param legalEntityId / legalEntityCode / legalEntityName 所属法人。
   *     蓝图红线：「仓库绑定法人是财务前提：不同法人的库存货权与收入归属不同。」 三个字段都可能为 null —— 默认法人允许为空（V1 的列就是可空的，且货权按
   *     inventory_balance 行判定而非靠仓库默认值推断），此时界面必须显式说「未绑定法人」
   * @param legalEntityCount 该仓实际持有货物的法人数（含非默认法人）： 一个仓同时存多个法人的货是合法的，这个数与「绑定法人」不是一回事
   */
  public record WarehouseRow(
      UUID id,
      String code,
      String name,
      WarehouseKind kind,
      @Schema(nullable = true) String kindLabel,
      String countryCode,
      String timezone,
      @Schema(nullable = true) String localTime,
      String status,
      boolean defaultWarehouse,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      long legalEntityCount,
      long locationCount,
      long skuCount,
      StockMetrics stock,
      @Schema(nullable = true) PlatformStock platformStock,
      WorkVolume work,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 自有账本库存量（{@code inventory_balance} 聚合）。
   *
   * @param onHand 在手
   * @param reserved 预占
   * @param available 可用 = 生成列 {@code on_hand - reserved} 的聚合，**没有第三个来源**
   * @param balanceRowCount 参与聚合的余额行数
   */
  public record StockMetrics(
      BigDecimal onHand, BigDecimal reserved, BigDecimal available, long balanceRowCount) {}

  /**
   * 平台仓观测值（{@code platform_stock_observation} 各来源最新一条的聚合）。
   *
   * <p>与 {@link StockMetrics} **永不相加**：V55 把「FBA 库存是平台仓观测值，不是自有账本」 写进了两条触发器，这里在类型层再钉一次 ——
   * 字段分开而不是合成一个「总库存」。
   *
   * @param sourceCount 来源数（{@code AMAZON_FBA} / {@code SHOPIFY_3PL} …）
   * @param observedOnHand 观测在手
   * @param observedInbound 平台侧报出的在途。与自有仓的 inventory_transfer 在途 **不是同一批货**（一个是平台报的，一个是我们自己发的）
   * @param latestCollectedAt 最近一次采集时间 —— 观测值的新鲜度必须看得见， 否则「上次采集到多少」这个判断做不出来
   */
  public record PlatformStock(
      long sourceCount,
      BigDecimal observedOnHand,
      BigDecimal observedAvailable,
      BigDecimal observedInbound,
      OffsetDateTime latestCollectedAt) {}

  /**
   * 作业量。
   *
   * <p>口径说清楚：这里的「作业」= 本服务**已交付**的作业单据。蓝图 08 的收货（8.2）、 出库（8.3）、组装（8.4）都还没有落库，所以它们**不在**这个合计里 ——
   * {@link WorkVolumeCapability} 用 {@code available=false} 如实报出这几个缺口， 而不是把它们算成 0 冒充「没有作业」。
   *
   * @param stocktakeTaskCount 盘点任务数（该仓发起的盘点单的任务单元）
   * @param transferInCount 调入本仓的调拨单数
   * @param transferOutCount 调出本仓的调拨单数
   * @param inventoryMovementCount 库存变动笔数（该仓库存余额上发生的带符号分录条数）
   * @param workOrderTotal 作业单合计 = 盘点任务 + 调入 + 调出
   * @param openWorkOrderCount 未结作业单数（未录完的盘点 + 未 RECEIVED 的调拨）
   */
  public record WorkVolume(
      long stocktakeTaskCount,
      long transferInCount,
      long transferOutCount,
      long inventoryMovementCount,
      long workOrderTotal,
      long openWorkOrderCount) {}

  // ------------------------------------------------------------ 能力声明与下拉项

  /**
   * 作业量维度的接入情况。
   *
   * <p>蓝图 8.1.1.1 说「库存量与作业量」，但 8.2 / 8.3 / 8.4 三条作业链尚未交付。 前端据此**禁用**未接入的维度并说明归哪个模块，而不是让用户点一个永远为 0
   * 的数字。
   *
   * @param available 是否已接入
   * @param note 未接入时写明归哪个模块、缺什么
   */
  public record WorkVolumeCapability(
      String dimension, String label, boolean available, String ownerModule, String note) {}

  /** 筛选与录入用的下拉项。 */
  public record FacilityOptions(
      List<KindOption> kinds,
      List<CountryOption> countries,
      List<LegalEntityOption> legalEntities,
      List<WarehouseFacilityStatusOption> statuses,
      String timezoneNote,
      String countryNote) {}

  public record KindOption(String code, String label, boolean platformObserved) {}

  /** 国家下拉**只列本租户真实在用的国家**，而不是造一份内置国别表 —— 详见 service 的说明。 */
  public record CountryOption(String code) {}

  public record LegalEntityOption(UUID id, String code, String name, String status) {}

  /** 仓库状态下拉项。带模块前缀以避开 {@code ReservationModels.StatusOption}（形状不同）。 */
  public record WarehouseFacilityStatusOption(String code, String label) {}

  // ------------------------------------------------------------ 新增仓库

  /**
   * 新增仓库入参。
   *
   * @param defaultLegalEntityId 所属法人（货权）。**必填**：蓝图红线说「仓库绑定法人是 财务前提」，一个不绑定法人的仓会让「这批货算哪个法人的收入」无从回答。
   *     存量行的该列可空是历史事实（V1 建表时就没有这层强制），但**新建一律要填** —— 不给历史行补值是如实（确实不知道），给新行强填是纪律。
   * @param timezone IANA 时区 ID，必须能被 JDK 的 {@code ZoneId} 解析
   * @param isDefault 是否设为默认发货仓（每租户至多一个启用中的默认仓，由 {@code uq_warehouse_default} 部分唯一索引在 DB 层裁决）
   */
  public record WarehouseCreateInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String code,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200)
          String name,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 16)
          String kind,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2)
          String countryCode,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String timezone,
      @jakarta.validation.constraints.NotNull @Schema(nullable = false) UUID defaultLegalEntityId,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 16) String status,
      @Schema(nullable = true) Boolean isDefault) {}

  /** 新增结果。 */
  public record WarehouseCreated(UUID id, String code, WarehouseKind kind) {}
}
