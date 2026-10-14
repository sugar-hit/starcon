package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 7.2.1.1 预占需求列表的传输模型。
 *
 * <p>蓝图：「展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
 * 状态（held/committed/consumed/released/expired）、剩余量、超时时长。」
 *
 * <p>本类是 7.2.1 下第一个模块，形状刻意给两个兄弟留好接口：{@link ReservationRow} 上的 {@code skuId}/{@code
 * sourceType}/{@code referenceId} 在 7.2.1.2 分配明细与 7.2.1.3 释放页都是同一份事实的子集，届时不另造一套私有结构。
 *
 * <p><b>服务归属</b>：蓝图关系句写「预占需求由 trade 持久化协调」，本实现**不采纳**—— 实查确认 inventory_reservation 表在任何一个服务里都不存在，V1
 * 文件头已把该表 预留给 warehouse（且注明 7.2.1.x 才建）。理由详见 V102 迁移文件头第零节。
 */
public final class ReservationModels {

  private ReservationModels() {}

  // ------------------------------------------------------------ 需求来源

  /**
   * 需求来源系统（受控词表，与 V102 的 CHECK 逐字一致）。
   *
   * <p>蓝图点名三类来源：订单履约、加工领料、其他占用。三类里前两类有明确上游服务 （09 订单、6.4.1.1 加工，都在 trade），第三类没有上游，由运营在 warehouse
   * 侧登记。
   */
  public enum SourceSystem {
    /** 09 订单履约需求（trade 侧协调，warehouse 执行分配与账变）。 */
    TRADE("订单履约"),
    /** 6.4.1.1 加工领料需求。 */
    PROCESSING("加工领料"),
    /** 无上游单据的临时占用（运营手工登记）。 */
    MANUAL("其他占用");

    private final String label;

    SourceSystem(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 需求类型（受控词表，与 V102 的 CHECK 逐字一致）。
   *
   * <p>与 {@link SourceSystem} **正交而不是同一件事**：加工领料既是一个来源系统， 也是一种需求类型；其他占用在 warehouse 侧登记，所以 type 是
   * OTHER_HOLD 而 system 是 MANUAL。把两者合成一列就会出现「手工登记的销售订单预占」这种 自相矛盾的行 —— 一行的 system 与 type 组合在本类里被
   * {@link #isValid()} 钉死。
   */
  public enum SourceType {
    /** 销售订单行预占。 */
    SALES_ORDER("销售订单"),
    /** 加工单组件领料预占。 */
    PROCESSING_ORDER("加工领料"),
    /** 其他/临时占用。 */
    OTHER_HOLD("其他占用");

    private final String label;

    SourceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 状态机

  /**
   * 预占状态（受控词表，与 V102 的 CHECK 逐字一致）。
   *
   * <p>五态的合法迁移由 V102 的 {@code guard_reservation_state_machine} 在 DB 层裁决，
   * 这里只声明**读侧**需要的性质（是否终态、是否参与自动过期）。 写侧不放在这里：让两条兄弟车道的 service 各自调同一个矩阵会分叉， 而分叉的状态机比没有状态机更危险。
   */
  public enum ReservationStatus {
    /** 已提出需求、尚未承诺分配。 */
    HELD("预占中"),
    /** 已分配到 bucket、承诺发货。承诺后**不再自动 TTL 过期**（蓝图 7.2.1.3 红线）。 */
    COMMITTED("已承诺"),
    /** 已真实出库/领料（终态）。 */
    CONSUMED("已消耗"),
    /** 被释放（终态）。 */
    RELEASED("已释放"),
    /** 超时过期回收（终态）。 */
    EXPIRED("已过期");

    private final String label;

    ReservationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 筛选条件

  /**
   * 列表查询条件（蓝图输入：「筛选条件（状态、来源类型、时间）；用户数据范围」）。
   *
   * @param statuses 状态过滤（可多选）；null / 空 = 全部
   * @param sourceType 来源类型过滤；null = 全部
   * @param sourceSystem 来源系统过滤；null = 全部
   * @param requestedFrom / requestedTo 时间范围，**左闭右开** {@code [from, to)}。 右开是为了让「按天筛选」可以把次日 00:00
   *     作为上界而不必减一秒
   * @param overdueOnly 只要已超过 expires_at 的行（HELD 才有 expires_at）
   * @param legalEntityId / skuId 货权与商品过滤
   * @param keyword 关键字：匹配预占单号 / 上游单号 / SKU 编码 / SKU 名称 / 备注
   * @param sort 排序键（白名单）：REQUESTED_AT / EXPIRES_AT / STATUS / QUANTITY / REMAINING / RESERVATION_NO
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   */
  public record ReservationQuery(
      @Schema(nullable = true) List<String> statuses,
      @Schema(nullable = true) String sourceType,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) OffsetDateTime requestedFrom,
      @Schema(nullable = true) OffsetDateTime requestedTo,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 列表响应

  /**
   * 列表响应。
   *
   * @param summary 状态汇总（**不受分页与筛选影响**，守住「合计 == 逐行求和」这条核对线）
   * @param rows 当前页的行
   * @param total 满足筛选条件的总行数
   * @param page 当前页码
   * @param size 每页行数
   * @param detailUnavailable 7.2.1.2 分配明细尚未交付：true 时前端**禁用**进明细入口 并说明去向，而不是跳到一个空路由
   */
  public record ReservationPage(
      ReservationSummary summary,
      List<ReservationRow> rows,
      long total,
      int page,
      int size,
      boolean detailUnavailable,
      String note) {}

  /**
   * 全量状态汇总。
   *
   * @param totalCount 预占总条数（含终态：终态行也要能被筛到，否则「过期了但查不到」 会让用户以为预占凭空消失）
   * @param counts 五个状态各自的数量，键为 {@link ReservationStatus} 名 —— 固定五项， 某状态 0 条时也带键（前端不需要为「0」写一条特判）
   * @param requestedQuantity 全量需求量合计
   * @param consumedQuantity 全量已消耗量合计
   * @param remainingQuantity 全量剩余量合计 = requested − consumed
   * @param overdueCount 已超过 expires_at 且仍在 HELD 的条数 —— 「该释放了」的候选数
   * @param overdueQuantity 上述候选的剩余量合计（= 还占着多少可用量）
   */
  public record ReservationSummary(
      long totalCount,
      Map<String, Long> counts,
      BigDecimal requestedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal remainingQuantity,
      long overdueCount,
      BigDecimal overdueQuantity) {}

  /**
   * 预占需求行。
   *
   * @param remainingQuantity 剩余量 = requested − consumed（生成列，DB 里只有这一个来源）。 口径见 V102
   *     §二：<b>不含</b>已分配未出库的部分 —— 那部分货还在仓里占着可用量， 算成已结清会让列表显示「这笔已经没事了」而实际还在圈货
   * @param overdueSeconds 超时时长（秒）。<b>恒从真实写入时刻算</b>：held_at/expires_at
   *     都在库里，没有「创建时间取当前时间」这种自欺。已终态或未过期时为 null —— 给一个 0 会把「没超时」与「超时 0 秒」混成同一个数
   * @param allocatedQuantity 已分配量。7.2.1.2 交付前恒 0，**如实返回 0 而不是 null**： null 在这个字段上会被读成「未知」，而实际是「已知为
   *     0」
   * @param sourceSystemLabel / sourceTypeLabel 来源的中文标签
   * @param statusLabel 状态的中文标签
   * @param skuCode / skuName / legalEntityCode / legalEntityName 引用登记表里的快照
   * @param referenceId / referenceCode 上游单据定位键（弱引用，无外键）
   * @param committedAt / settledAt 承诺与终结时刻；未到达该阶段时为 null
   */
  public record ReservationRow(
      UUID id,
      String reservationNo,
      String sourceSystem,
      @Schema(nullable = true) String sourceSystemLabel,
      String sourceType,
      @Schema(nullable = true) String sourceTypeLabel,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) String referenceCode,
      UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      BigDecimal requestedQuantity,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal remainingQuantity,
      String status,
      @Schema(nullable = true) String statusLabel,
      boolean terminal,
      @Schema(nullable = true) Long overdueSeconds,
      OffsetDateTime requestedAt,
      OffsetDateTime heldAt,
      @Schema(nullable = true) OffsetDateTime committedAt,
      @Schema(nullable = true) OffsetDateTime settledAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 能力声明与下拉项

  /**
   * 预占链路各环节的接入情况。
   *
   * <p>7.2.1.2 分配明细与 7.2.1.3 释放/过期**都未交付**，本端点显式报 {@code available=false} 并写明归哪个模块，前端据此禁用入口并说明去向 ——
   * 而不是显示一个永远为 0 的「已分配量」让人以为分配功能已经跑通。
   *
   * @param available 是否已接入
   * @param ownerModule 未接入时归哪个模块
   * @param note 未接入时说明缺什么
   */
  public record ReservationCapability(
      String dimension, String label, boolean available, String ownerModule, String note) {}

  /** 筛选与录入用的下拉项。 */
  public record ReservationOptions(
      List<StatusOption> statuses,
      List<SourceOption> sourceSystems,
      List<SourceOption> sourceTypes,
      List<StatusOption> sortOptions,
      String timezoneNote,
      String ownerNote) {}

  public record StatusOption(String code, String label, boolean terminal) {}

  public record SourceOption(String code, String label) {}

  // ------------------------------------------------------------ 登记预占

  /**
   * 登记预占需求入参。
   *
   * <p>本模块是**列表**，但列表要有数据就得有写入入口 —— 蓝图上游（09 订单 / 6.4.1.1 加工） 都是跨服务调本服务的授权 API，所以这里提供登记端点。**只允许建成
   * HELD**： 终态只能由 7.2.1.2 / 7.2.1.3 的状态迁移产生（V102 的 guard_reservation_insert 钉死）。
   *
   * @param requestedQuantity 需求数量，必须为正
   * @param expiresAt 过期时刻；不传则该预占不参与超时筛选（可稍后由 7.2.1.3 补）
   * @param heldAt 预占建立时刻（超时时长的唯一基准）。不传则取服务端当前时刻 —— 但**跨服务调用必须显式传**：调用方与本服务之间有时钟差，
   *     用服务端当前时间会让「上游什么时候提的需求」这个事实丢掉
   */
  public record ReservationCreateInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 24)
          String sourceSystem,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32)
          String sourceType,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 64) String referenceCode,
      @jakarta.validation.constraints.NotNull @Schema(nullable = false) UUID legalEntityId,
      @jakarta.validation.constraints.NotNull @Schema(nullable = false) UUID skuId,
      @jakarta.validation.constraints.NotNull @Schema(nullable = false)
          BigDecimal requestedQuantity,
      @Schema(nullable = true) OffsetDateTime heldAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 200) String note) {}

  /**
   * 登记结果。
   *
   * @param status 恒为 {@code HELD}：登记只能建成预占中，终态由 7.2.1.2 / 7.2.1.3 的 状态迁移产生（V102 的 {@code
   *     guard_reservation_insert} 在 DB 层拒绝插终态行）
   */
  public record ReservationCreated(
      UUID id, String reservationNo, String status, String statusLabel) {}
}
