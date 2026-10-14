package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.3.1.2 效期与先进先出的传输模型。
 *
 * <p>蓝图原文：「对有保质期的商品，按 FEFO（先到期先出）或 FIFO 分配出库批次，预警临期与过期库存」，
 * 输入「批次失效日期；出库分配策略（FEFO/FIFO/指定批次）；预警提前天数」， 输出「推荐出库批次；临期/过期预警清单」。
 *
 * <p><b>类型名前缀是刻意选的</b>：契约 codegen 一次拍平全部四个服务的 schema 到同一命名空间， 重名的 DTO 会被静默覆盖且没有任何警告。本模块的每个类型都带
 * {@code Expiry} 或 {@code Fefo} 前缀， 就是为了让「重名覆盖」这件事在物理上不可能发生。
 *
 * <p><b>效期日��是 {@code date} 不是 {@code timestamptz}</b>：失效日期是产线与包装上写死的事实， 用 {@link LocalDate} + PG
 * {@code date} 列承载。硬套时间戳会让「哪天到期」随服务器时区漂一天， 而 FEFO 的整个排序都建立在这上面。
 *
 * <p><b>临期与过期是两件事，不合并成一个状态</b>：过期是日历事实（不能再发，必须走 6.4.2 临期品处理）， 临期是「还剩 N
 * 天、该开始促销了」。合成一个「效期状态」字段会让界面无法回答 「哪些货现在必须拦下来」，所以这里给三个互斥档 + 一个不适用档。
 */
public final class InventoryExpiryModels {

  private InventoryExpiryModels() {}

  // ================================================================ 词表

  /**
   * 出库分配策略。
   *
   * <p>三种策略的差别是**排序依据不同**，不是「谁更严格」：
   *
   * <ul>
   *   <li>{@link #FEFO} 按失效日期升序（先到期先出）。非效期批次**不参与排序**。
   *   <li>{@link #FIFO} 按收货日期升序（先进先出），与效期无关。
   *   <li>{@link #SPECIFIED_LOT} 固定从指定批次取。
   * </ul>
   *
   * <p>刻意**没有** {@code LIFO}：库存出库里「后进的先出」只在极少数行业成立， 把它放进词表只会让「按效期出库」这个需求被误解成「可任意排序」。
   */
  public enum ExpiryAllocationStrategy {
    FEFO("先到期先出"),
    FIFO("先进先出"),
    SPECIFIED_LOT("指定批次");

    private final String label;

    ExpiryAllocationStrategy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 未知码 400：把拼错的策略静默当成默认策略，会排出一个看起来合理但完全错误的出库顺序。 */
    public static ExpiryAllocationStrategy parse(String value) {
      for (var strategy : values()) {
        if (strategy.name().equalsIgnoreCase(value.trim())) return strategy;
      }
      throw new IllegalArgumentException("未知的出库分配策略：" + value + "，可选：" + names());
    }

    public static String names() {
      return String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList());
    }
  }

  /**
   * 效期档位。
   *
   * <p>{@link #EXPIRED} 与 {@link #EXPIRING} 必须分开：前者是「不能再发」，后者是「还能发但该促销了」。 临期阈值来自每个 SKU 自己的策略（{@code
   * lead_days}），不是全局常量 —— 食品与日化的临期窗口差一个数量级。
   */
  public enum ExpiryStatus {
    /** 失效日期早于今天。数量仍在库里，但不能再发。 */
    EXPIRED("已过期"),
    /** 未过期，且剩余天数 <= 该 SKU 的提前天数阈值。 */
    EXPIRING("临期"),
    /** 未过期且还在阈值之外。 */
    HEALTHY("正常"),
    /** 该批次没有失效日期（非效期品），阈值对它不适用。 */
    NO_EXPIRY("非效期品");

    private final String label;

    ExpiryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 分配策略配置

  /**
   * 策略列表查询条件。
   *
   * @param strategy 策略多选（不传 = 不按策略筛）
   * @param enabled 只看启用/停用。不传 = 两种都列 —— 停用的策略同样是运营需要看到的配置事实
   * @param keyword 关键字：匹配 SKU 编码/名称与指定批次号
   */
  public record ExpiryPolicyQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) List<String> strategy,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 建立/修改某 SKU 的出库分配策略（按 SKU 幂等 upsert）。
   *
   * @param skuId 策略主体：一个 SKU 至多一条策略（DB 的 UNIQUE(tenant_id,sku_id) 裁决）
   * @param strategy 出库分配策略；不传按 FEFO
   * @param enabled 策略开关。**默认 false**：效期/FIFO 是食品化妆品类目的硬需求， 但系统不知道哪个 SKU 是效期品（品类属性在 catalog，不在本服务），
   *     替租户默认打开等于凭空做了「这个 SKU 受效期管理」的判断。 关着时出库退回「按库位顺序取可用量」，响应里 policyEnabled=false 如实报出
   * @param leadDays 临期预警提前天数；不传按 30。上限 3650 由 DB CHECK 兜住
   * @param specifiedLotId strategy=SPECIFIED_LOT 时**必填**，且该批次必须属于该 SKU （DB 触发器
   *     guard_expiry_policy_lot_sku 裁决）
   */
  public record ExpiryPolicyUpsertRequest(
      @NotNull UUID skuId,
      @Schema(nullable = true) String strategy,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Integer leadDays,
      @Schema(nullable = true) UUID specifiedLotId,
      @Size(max = 500) String notes) {}

  /**
   * 单个 SKU 的**生效策略**。
   *
   * @param configured 该 SKU 是否真的配过策略行。false 时下面几个字段是**兜底默认值**， 不是租户的选择 —— 界面上必须能区分「配成 30 天」与「没人配、用的
   *     30 天默认值」， 否则运营会以为预警窗口是别人调过的
   * @param strategy / enabled / leadDays / specifiedLotId 实际生效值（配了就用配的，没配就用默认）
   * @param source 来源：POLICY = 来自策略行；DEFAULT = 兜底默认
   */
  public record ExpiryPolicy(
      UUID skuId,
      String skuCode,
      String skuName,
      String strategy,
      String strategyLabel,
      boolean enabled,
      boolean configured,
      Integer leadDays,
      @Schema(nullable = true) UUID specifiedLotId,
      @Schema(nullable = true) String specifiedLotCode,
      @Schema(nullable = true) LocalDate nearestExpiry,
      String notes,
      String source,
      OffsetDateTime updatedAt) {}

  /** 策略保存后的回读结果。 */
  public record ExpiryPolicySaved(
      UUID policyId,
      UUID skuId,
      String strategy,
      boolean enabled,
      Integer leadDays,
      @Schema(nullable = true) UUID specifiedLotId,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 策略列表行。与 {@link ExpiryPolicy} 分开是因为列表要带聚合计数，不带过期的指定批次信息。 */
  public record ExpiryPolicyRow(
      UUID policyId,
      UUID skuId,
      String skuCode,
      String skuName,
      String strategy,
      String strategyLabel,
      boolean enabled,
      Integer leadDays,
      @Schema(nullable = true) UUID specifiedLotId,
      @Schema(nullable = true) String specifiedLotCode,
      BigDecimal onHand,
      long lotCount,
      long expiringLotCount,
      long expiredLotCount,
      String notes,
      OffsetDateTime updatedAt) {}

  /** 策略列表汇总（全量，不受分页影响）。 */
  public record ExpiryPolicySummary(
      long policyCount,
      long skuCount,
      long enabledCount,
      long fefoCount,
      long fifoCount,
      long specifiedLotCount,
      long disabledCount,
      long skuWithExpiryLotCount) {}

  /** 策略列表页。 */
  public record ExpiryPolicyPage(
      ExpiryPolicySummary summary,
      List<ExpiryPolicyRow> rows,
      List<ExpiryPolicyOption> strategyOptions,
      long total,
      int page,
      int size) {}

  /** 策略下拉项，带命中策略数。 */
  public record ExpiryPolicyOption(String value, String label, long policyCount) {}

  // ================================================================ 分配推荐

  /**
   * 出库分配推荐请求（8 拣货侧的下单依据，本模块只出推荐、不动库存）。
   *
   * @param skuId 要出库的 SKU
   * @param warehouseId 从哪个仓出。**必填**：FEFO/FIFO 都要在仓内排序， 跨仓合并排序会排出一个物理上拣不到的顺序
   * @param quantity 要出的数量（numeric(19,4)，禁浮点）
   * @param strategy 本次请求显式指定的策略。不传则用该 SKU 的策略； 传了就是**覆盖**（拣货员临时指定「这单走指定批次」的真实场景）
   * @param specifiedLotId strategy=SPECIFIED_LOT 时必填（请求覆盖策略里的指定批次）
   */
  public record FefoAllocationRequest(
      @NotNull UUID skuId,
      @NotNull UUID warehouseId,
      @NotNull BigDecimal quantity,
      @Schema(nullable = true) String strategy,
      @Schema(nullable = true) UUID specifiedLotId) {}

  /**
   * 一个批次的取用量（推荐结果的一行）。
   *
   * @param quantity 从该批次取的量。**按批次精确**：stock_bucket 是批次粒度的事实层， 所以这里的数不是「这个余额行上大约有多少」而是「这批货取多少」
   */
  public record FefoAllocationLine(
      UUID lotId,
      String lotCode,
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) LocalDate receivedOn,
      @Schema(nullable = true) Integer daysToExpiry,
      String sortBasis,
      @Schema(nullable = true) String sortBasisValue,
      BigDecimal quantity,
      long bucketCount,
      List<FefoAllocationBucketLine> buckets) {}

  /**
   * 批次内的一个库位取用量。
   *
   * <p>给到库位级是因为**推荐必须可执行**：8 拣货拿到的是「A-01 库位取 12、A-02 库位取 8」， 只给到批次级的话拣货还要自己决定去哪个库位，那正是 FEFO
   * 该替人做的决定。
   */
  public record FefoAllocationBucketLine(
      UUID bucketId,
      UUID locationId,
      String locationCode,
      String locationName,
      String warehouseCode,
      String qualityStatusCode,
      BigDecimal quantity,
      BigDecimal bucketAvailable) {}

  /**
   * **不可用于本次出库的量，按原因拆开报出**。
   *
   * <p>存在的理由：分配不足时，界面必须能回答「为什么不够」。 只回一句「缺 20 件」的话，人只能回去自己翻库存 —— 而缺口往往正是过期或待检造成的。
   *
   * @param expiredAvailable 已过期仍占着库位的可用量（必须先走 6.4.2 临期品处理）
   * @param noExpiryAvailable 无失效日期的可用量（FEFO 下不参与排序；FIFO 下正常可用）
   * @param nonSellableAvailable 待检/残次等非可售质量状态的可用量
   * @param nonPickableAvailable 不可拣库位（收货中/待检/冻结位）上的可用量
   * @param noLotAvailable 无批次的可用量（该 SKU 没启用批次管理）
   */
  public record FefoAllocationIneligible(
      BigDecimal expiredAvailable,
      BigDecimal noExpiryAvailable,
      BigDecimal nonSellableAvailable,
      BigDecimal nonPickableAvailable,
      BigDecimal noLotAvailable) {}

  /**
   * 出库分配推荐结果。
   *
   * @param strategy 本次实际用的策略；策略开关关着时是 NONE（按库位顺序取，不做效期排序）
   * @param policyEnabled 该 SKU 的策略开关。false 时 {@code strategy=NONE`，
   *     并如实报出「策略未启用」，而不是假装已经按 FEFO 排过
   * @param strategySource 策略从哪来：REQUEST = 本次请求覆盖；POLICY = SKU 策略；DEFAULT = 兜底默认
   * @param requestedQuantity 请求量
   * @param allocatedQuantity 实际能分配到的量
   * @param shortfallQuantity 缺口 = requested − allocated。0 表示可全额出库
   * @param lines 推荐取用顺序，**按实际取用顺序排列**（不是按批次号）
   */
  public record FefoAllocationPlan(
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String strategy,
      String strategyLabel,
      boolean policyEnabled,
      String strategySource,
      BigDecimal requestedQuantity,
      BigDecimal allocatedQuantity,
      BigDecimal shortfallQuantity,
      BigDecimal skuAvailableInWarehouse,
      @Schema(nullable = true) LocalDate earliestExpiry,
      long lineCount,
      List<FefoAllocationLine> lines,
      FefoAllocationIneligible ineligible,
      List<String> notes) {}

  // ================================================================ 效期预警

  /**
   * 效期预警清单查询条件。
   *
   * @param expiryStatus 只看某一档（多选）。**不传 = 只看需要处理的两档** （EXPIRED + EXPIRING）—— 预警清单的用途是「找出待办的货」， 把
   *     HEALTHY 也塞进来会让清单长到没法用
   * @param leadDays 临时覆盖提前天数。不传则逐 SKU 用它自己策略里的阈值， 都没配时用默认值 30。传了就统一按这个窗口算 —— 「让我看看未来 90 天」的临时视图
   * @param inStock 只看在手 > 0 的批次。不传默认 true：零库存批次发不出货，占着预警位是噪声
   * @param sort 排序键（白名单）
   */
  public record ExpiryAlertQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) List<String> expiryStatus,
      @Schema(nullable = true) Integer leadDays,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) Boolean inStock,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 效期预警清单的一行 = 一个批次。
   *
   * <p>行上的 {@code expiryStatus} 是按**该 SKU 自己的提前天数**算出来的， 所以同一个租户里不同 SKU 的「临期」口径可以不同 —— 这是刻意的：
   * 日化品的临期窗口本来就比食品长。
   *
   * @param leadDays 本行实际用的提前天数（policyLeadDays 为空时是全局默认值）
   * @param leadDaysSource 阈值来源：POLICY = 该 SKU 配过；DEFAULT = 用的默认值
   * @param daysToExpiry 距离失效日期的天数。已过期为负数；无失效日期为 null （**必须能读出负数**：jOOQ 对纯文本语句里的 date 差值表达式拿不到列类型，
   *     取值方式不当会让每一行都退化成 null，也就是「全都显示没有效期」）
   */
  public record ExpiryAlertRow(
      UUID lotId,
      UUID skuId,
      String skuCode,
      String skuName,
      String lotCode,
      @Schema(nullable = true) LocalDate producedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) LocalDate receivedOn,
      String expiryStatus,
      String expiryStatusLabel,
      @Schema(nullable = true) Integer daysToExpiry,
      Integer leadDays,
      String leadDaysSource,
      String lotStatus,
      String origin,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long warehouseCount,
      String notes,
      OffsetDateTime updatedAt) {}

  /**
   * 效期预警汇总（**全量**，不受分页影响）。
   *
   * @param expiredOnHand 已过期的在手量。与 expiringOnHand 分开报： 两者的处置路径完全不同（过期走 6.4.2
   *     临期品处理，临期走促销/调拨），合成一个数就没法派工
   * @param earliestExpiry 最早的失效日期（已经过期时也在内，那正是最紧急的那个）
   */
  public record ExpiryAlertSummary(
      long lotCount,
      long skuCount,
      long expiredLotCount,
      long expiringLotCount,
      long healthyLotCount,
      long noExpiryLotCount,
      BigDecimal onHand,
      BigDecimal expiredOnHand,
      BigDecimal expiringOnHand,
      long distinctLeadDaysCount,
      @Schema(nullable = true) LocalDate earliestExpiry,
      @Schema(nullable = true) LocalDate latestExpiry) {}

  /** 效期档位下拉项，带命中批次数：让人在筛选器里就看出哪一档最需要处理。 */
  public record ExpiryStatusOption(String value, String label, long lotCount) {}

  /** 效期预警清单页。 */
  public record ExpiryAlertPage(
      ExpiryAlertSummary summary,
      List<ExpiryAlertRow> rows,
      List<ExpiryStatusOption> statusOptions,
      Integer leadDaysOverride,
      String leadDaysNote,
      long total,
      int page,
      int size) {}

  // ================================================================ 下拉参照

  /** 仓库下拉项。分配推荐必须限定单仓，这里给仓库清单。 */
  public record ExpiryWarehouseOption(UUID warehouseId, String code, String name) {}

  /** 批次下拉项（指定批次策略用）。按失效日期升序，先到期的排前面。 */
  public record ExpiryLotOption(
      UUID lotId, String lotCode, @Schema(nullable = true) LocalDate expiresOn) {}
}
