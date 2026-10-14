package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.2.1.2 预占分配明细的传输模型。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的 held→终态迁移记录。」输入 {@code
 * reservation_id}，输出「分配明细行 + 状态迁移时间线」。
 *
 * <p><b>命名纪律</b>：本类每一个 record / enum 都以 {@code ReservationAllocation} 起头。 springdoc 用 record
 * 的**简单类名**做 OpenAPI {@code components.schemas} 的键， 同名而不同形状的 record 会让先注册的那份被**静默覆盖** ——
 * 别人的字段形状被改掉， 而后端测试全绿、只有前端 typecheck 会红。所以本车道不声明任何通用名 （{@code StatusOption} / {@code SourceOption}
 * / {@code Row} 之类一律不用）。
 *
 * <p>形状刻意与 7.2.1.1 的 {@code ReservationModels} 同构：明细页抬头复用的就是需求列表那一行的事实 （单号 / 来源 / SKU / 需求量 /
 * 剩余量），不另造一套私有结构，两页显示同一个数才对得上。
 *
 * <p>表与服务归属：两张表落 warehouse（V107 文件头第零节，与 V102 同一条纪律）， 不跨服务读写 trade 的表。
 */
public final class ReservationAllocationModels {

  private ReservationAllocationModels() {}

  // ------------------------------------------------------------ 分配状态机

  /**
   * 分配级状态（受控词表，与 V107 的 CHECK 逐字一致，也与父表 {@code ReservationStatus} 同词表）。
   *
   * <p>与父表同名同词表是刻意的：分配状态与预占状态不是两个层次的概念， 一个预占进入 CONSUMED 是因为它的分配都 CONSUMED 了。让两套词表各叫各的，
   * 时间线上就会出现「分配已消耗、预占还在承诺」这种需要额外解释才能看懂的状态对。
   *
   * <p>合法迁移由 V107 的 {@code guard_reservation_allocation_update} 在 DB 层裁决； 这里只声明**读侧**需要的性质（是否终态）。
   */
  public enum ReservationAllocationStatus {
    /** 已圈到 bucket、尚未承诺。 */
    HELD("预占中"),
    /** 已承诺。承诺后**不再自动 TTL 过期**（蓝图 7.2.1.3 红线）。 */
    COMMITTED("已承诺"),
    /** 已真实出库 / 领料（终态）。 */
    CONSUMED("已消耗"),
    /** 被释放（终态）。写入方是 7.2.1.3。 */
    RELEASED("已释放"),
    /** 超时回收（终态）。写入方是 7.2.1.3。 */
    EXPIRED("已过期");

    private final String label;

    ReservationAllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否终态。与父表 {@code ReservationStatus} 的终态集合完全一致。 */
    public boolean terminal() {
      return this == CONSUMED || this == RELEASED || this == EXPIRED;
    }

    /** 是否仍在占额（父表 allocated_quantity 的口径：非终态才算）。 */
    public boolean occupying() {
      return !terminal();
    }
  }

  /**
   * 本模块能触发的状态迁移（受控词表）。
   *
   * <p>刻意**不含** RELEASED / EXPIRED：释放与过期属 7.2.1.3（蓝图： 「提供手动释放入口与批量过期清理，释放时同步恢复可用量」）。 本模块只提供「分配」与「承诺
   * / 消耗」两个动作，把释放按钮留给真正实现它的模块， 而不是做一个点下去什么也不发生的按钮。
   */
  public enum ReservationAllocationTransition {
    /** 登记分配：预占 → 圈到某个 bucket。 */
    ALLOCATE("登记分配"),
    /** 承诺：分配 HELD → COMMITTED。 */
    COMMIT("承诺分配"),
    /** 消耗：分配 COMMITTED → CONSUMED。 */
    CONSUME("消耗出库");

    private final String label;

    ReservationAllocationTransition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 目标状态。 */
    public ReservationAllocationStatus target() {
      return switch (this) {
        case ALLOCATE -> ReservationAllocationStatus.HELD;
        case COMMIT -> ReservationAllocationStatus.COMMITTED;
        case CONSUME -> ReservationAllocationStatus.CONSUMED;
      };
    }
  }

  // ------------------------------------------------------------ 筛选条件

  /**
   * 分配明细筛选条件。
   *
   * @param statuses 分配状态过滤（可多选）；null / 空 = 全部
   * @param warehouseId / locationId / lotId bucket 三维过滤；三者都是精确匹配 （货位与批次维度的候选值来自 {@code
   *     /allocation-options}，不是全库）
   * @param keyword 关键字：匹配仓编码 / 仓名 / 货位编码 / 货位名 / 批次号 / 分配备注
   * @param sort 排序键（白名单）：ALLOCATED_AT / STATUS / QUANTITY / REMAINING / BUCKET
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   */
  public record ReservationAllocationQuery(
      @Schema(nullable = true) List<String> statuses,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 时间线筛选条件。
   *
   * @param allocationId 只看某一条分配的分录；null = 该预占的全部（含预占级）
   * @param transition 迁移类型过滤；null = 全部
   */
  public record ReservationAllocationTimelineQuery(
      @Schema(nullable = true) UUID allocationId,
      @Schema(nullable = true) String transition,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 响应

  /**
   * 明细页抬头：预占需求本身的事实。
   *
   * <p>复用需求列表同一张表的同一批列，不重算 —— 明细页与列表页显示的「需求量 / 剩余量」 必须是同一个数，否则两页对不上账时没人说得清哪个是对的。
   */
  public record ReservationAllocationHeader(
      UUID reservationId,
      String reservationNo,
      String sourceSystem,
      @Schema(nullable = true) String sourceSystemLabel,
      String sourceType,
      @Schema(nullable = true) String sourceTypeLabel,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) String referenceCode,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      BigDecimal requestedQuantity,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal remainingQuantity,
      String status,
      @Schema(nullable = true) String statusLabel,
      boolean terminal,
      OffsetDateTime heldAt,
      @Schema(nullable = true) OffsetDateTime committedAt,
      @Schema(nullable = true) OffsetDateTime settledAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) String note) {}

  /**
   * 分配汇总。
   *
   * <p>与列表同一条纪律：<b>汇总不受分页与筛选影响</b>，它回答的是「这笔预占一共圈了多少」， 拿筛选后的数当汇总会让「切了个筛选怎么总量变了」看起来像数据丢了。
   *
   * @param unallocatedQuantity 未分配量 = 需求量 − 已分配量。**不是**「可以放弃的量」： 它是「还欠多少没圈到
   *     bucket」，与剩余量（欠多少没出库）是两个问题
   * @param counts 五个状态各自的数量，键为 {@link ReservationAllocationStatus} 名，固定五项
   * @param bucketCount 分配到的不同 bucket 数（仓+货位+批次的去重组合数）—— 「圈了几个地方」与「分几笔圈」是两件事，同一个 bucket 分两次圈只会让笔数虚高
   */
  public record ReservationAllocationSummary(
      long totalCount,
      java.util.Map<String, Long> counts,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal remainingQuantity,
      BigDecimal unallocatedQuantity,
      long bucketCount) {}

  /**
   * 分配明细行。
   *
   * @param remainingQuantity 本行剩余 = 分配量 − 已消耗 − 已释放（DB 生成列）。 它是「这条分配上还欠多少」，与抬头的
   *     remainingQuantity（整笔预占还欠多少）不是同一个数
   * @param warehouseCode / warehouseName / locationCode / locationName / lotCode 来自本服务
   *     引用登记表的可读快照（warehouse / warehouse_location / inventory_lot），不跨库 join
   * @param terminal 是否终态
   * @param committedAt / settledAt 承诺与终结时刻；未到达该阶段时为 null
   */
  public record ReservationAllocationRow(
      UUID id,
      UUID reservationId,
      String status,
      @Schema(nullable = true) String statusLabel,
      boolean terminal,
      boolean occupying,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) String locationName,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      @Schema(nullable = true) String lotExpiresOn,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal remainingQuantity,
      OffsetDateTime allocatedAt,
      @Schema(nullable = true) OffsetDateTime committedAt,
      @Schema(nullable = true) OffsetDateTime settledAt,
      @Schema(nullable = true) String note,
      long transitionCount) {}

  /**
   * 分配明细页响应。
   *
   * @param reservation 预占抬头（需求量 / 已分配 / 剩余 —— 与需求列表同一批列）
   * @param summary 分配汇总（不受分页与筛选影响）
   * @param rows 当前页的分配行
   * @param total 满足筛选条件的总行数
   * @param timelineCount 该预占的迁移分录总条数（**不受筛选影响**）—— 让页面能从明细直接知道时间线有多长，而不必先跳过去问一次
   * @param note 口径说明：分配与释放的职责边界
   */
  public record ReservationAllocationPage(
      ReservationAllocationHeader reservation,
      ReservationAllocationSummary summary,
      List<ReservationAllocationRow> rows,
      long total,
      long timelineCount,
      int page,
      int size,
      String note) {}

  /**
   * 迁移时间线的一条。
   *
   * @param level ALLOCATION = 挂在某条分配上；RESERVATION = 预占级迁移（{@code allocationId} 为空）
   * @param transition 迁移语义（ALLOCATED / COMMITTED / CONSUMED / RELEASED / EXPIRED）
   * @param fromStatus 迁移前状态；建行那一条为 null（此前没有状态）—— 给 null 而不是给 HELD，是因为「此前无状态」与「此前就是 HELD」在审计上不是一回事
   * @param quantity 本次迁移涉及的数量。**不是**当前量：时间线上每一步的量各不相同， 拿当前量倒推会让「先承诺 10 再消耗 3」显示成两次都是 10
   * @param occurredAt 发生时刻（调用方提供，timestamptz）
   * @param warehouseCode / locationCode / lotCode 分配级分录的 bucket 可读快照； 预占级分录为 null
   */
  public record ReservationAllocationEntryRow(
      UUID id,
      UUID reservationId,
      @Schema(nullable = true) UUID allocationId,
      String level,
      String transition,
      @Schema(nullable = true) String transitionLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      @Schema(nullable = true) String toStatusLabel,
      BigDecimal quantity,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) String lotCode) {}

  /**
   * 时间线响应。
   *
   * @param entries 按发生时刻正序的分录（同一时刻并列时按 id 收尾，保证两次请求顺序一致）
   * @param note 说明 append-only 与「预占级 / 分配级」混排的原因
   */
  public record ReservationAllocationTimeline(
      UUID reservationId,
      List<ReservationAllocationEntryRow> entries,
      long total,
      int page,
      int size,
      String note) {}

  // ------------------------------------------------------------ 写入

  /**
   * 登记分配入参：把这笔预占圈到某个 bucket 上。
   *
   * @param warehouseId 目标仓，必填
   * @param locationId 目标货位，可空（虚拟仓 / 直发仓没有货位档案）
   * @param lotId 目标批次，可空（非批次管理品）
   * @param allocatedQuantity 圈定量，必须为正
   * @param allocatedAt 圈定时刻。**建议显式传**：它是时间线上的第一笔，也是批次追溯的时间基准， 不传则取服务端当前时刻 ——
   *     跨服务调用不传等于把「什么时候圈的」换成「本服务什么时候知道的」
   */
  public record ReservationAllocationCreateInput(
      @jakarta.validation.constraints.NotNull @Schema(nullable = false) UUID warehouseId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID lotId,
      @jakarta.validation.constraints.NotNull @Schema(nullable = false)
          BigDecimal allocatedQuantity,
      @Schema(nullable = true) OffsetDateTime allocatedAt,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 200) String note) {}

  /**
   * 状态迁转入参（承诺 / 消耗）。
   *
   * @param quantity 本次迁移的数量。消耗支持部分出库，所以它可以小于分配量； 承诺时传空或与分配量一致（由服务层裁决，见 {@code
   *     ReservationAllocationService#commit}）
   * @param occurredAt 迁移发生时刻；不传则取服务端当前时刻
   * @param reason 迁移原因（写入 append-only 流水，事后要能读出来为什么）
   */
  public record ReservationAllocationTransitionInput(
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 200) String reason) {}

  /**
   * 登记分配的结果。
   *
   * @param status 恒为 {@code HELD}：建行即 HELD，终态只能由状态迁移产生 （V107 的 {@code
   *     guard_reservation_allocation_insert} 在 DB 层拒绝插终态行）
   * @param reservationAllocatedQuantity 回写后的父预占已分配量 —— 一并返回是因为调用方最关心的就是「还剩多少没圈」，多一次查询才能得到这个数
   */
  public record ReservationAllocationCreated(
      UUID allocationId,
      UUID reservationId,
      String status,
      @Schema(nullable = true) String statusLabel,
      BigDecimal reservationAllocatedQuantity,
      BigDecimal reservationUnallocatedQuantity,
      UUID entryId) {}

  /**
   * 状态迁移的结果。
   *
   * @param fromStatus / toStatus 本次迁移的两端
   * @param allocationStatus 迁移后该分配的状态
   * @param reservationStatus 迁移后父预占的状态。**会与分配一起变**： 分配全承诺则预占进 COMMITTED，分配全消耗且覆盖需求量则预占进 CONSUMED ——
   *     不同步会让预占一直停在 HELD 而分配已经承诺，超时清理会把已承诺的货误释放
   * @param entryId append-only 流水中对应那一条的 id
   */
  public record ReservationAllocationTransitioned(
      UUID allocationId,
      UUID reservationId,
      String fromStatus,
      String toStatus,
      String allocationStatus,
      @Schema(nullable = true) String allocationStatusLabel,
      String reservationStatus,
      @Schema(nullable = true) String reservationStatusLabel,
      BigDecimal consumedQuantity,
      BigDecimal reservationRemainingQuantity,
      UUID entryId) {}

  // ------------------------------------------------------------ 下拉项

  /**
   * 下拉项。
   *
   * <p><b>仓 / 货位 / 批次的候选值分两组，含义不同，不要混用</b>：
   *
   * <ul>
   *   <li>{@code warehouses / locations / lots} 是**筛选器**用的候选值：只取这条预占 {@code
   *       已经出现过}的那些。明细页问的是「这笔预占圈过哪些地方」， 把全库几千个批次倒给筛选框既慢又没意义。
   *   <li>{@code targets} 是**登记分配**时可选的目标：租户名下在用的仓 / 货位， 以及这笔预占那个 SKU 名下的在用批次。它必须覆盖全量 —— 筛选器用不到的东西，
   *       正是登记时最需要的（新建的预占还没有任何分配，筛选候选集是空的， 拿它去填登记表就是死结）。
   * </ul>
   *
   * @param statuses 五个分配状态（含是否终态、是否占额）
   * @param transitions 本模块能触发的迁移（**不含**释放与过期：那属 7.2.1.3）
   * @param sortOptions 五个排序键
   */
  public record ReservationAllocationOptions(
      List<ReservationAllocationOption> statuses,
      List<ReservationAllocationOption> transitions,
      List<ReservationAllocationBucketOption> warehouses,
      List<ReservationAllocationBucketOption> locations,
      List<ReservationAllocationBucketOption> lots,
      List<ReservationAllocationOption> sortOptions,
      ReservationAllocationTargets targets,
      String timezoneNote,
      String ownerNote) {}

  /**
   * 登记分配时的可选目标（租户在用的仓 / 货位 + 这笔预占那个 SKU 的在用批次）。
   *
   * <p>刻意只给在用的（{@code ACTIVE}）：把已归档的仓与已过期批次倒进选择框， 只会让人选到一个提交后必然被拒的组合。
   *
   * @param locations 各仓名下的在用货位，用 {@code parentId} 指回所属仓 —— 平铺成一个列表的话前端得自己按仓分组，而分组这件事在服务端做更不容易出错
   * @param lots 这笔预占那个 SKU 名下的在用批次；批次与仓无关，所以 {@code parentId} 恒为空
   */
  public record ReservationAllocationTargets(
      List<ReservationAllocationTarget> warehouses,
      List<ReservationAllocationTarget> locations,
      List<ReservationAllocationTarget> lots) {}

  /**
   * 一个可选目标。
   *
   * @param id 仓 / 货位 / 批次的 id
   * @param parentId 所属仓的 id；批次没有所属仓，为 null
   * @param code 编码
   * @param name 名称；批次没有名称列（{@code inventory_lot} 只有 lot_code），为 null
   */
  public record ReservationAllocationTarget(
      UUID id,
      @Schema(nullable = true) UUID parentId,
      String code,
      @Schema(nullable = true) String name) {}

  /**
   * 一个下拉项。
   *
   * <p>刻意不叫 {@code StatusOption} / {@code SourceOption}：这两个简单名已被 7.2.1.1 的 {@code
   * ReservationModels} 占用，springdoc 以简单类名为 schema 键，同名即静默覆盖。
   *
   * @param code 取值
   * @param label 中文标签
   * @param terminal 是否终态（仅状态项有意义，其余为 false）
   * @param description 补充说明，供下拉项右侧提示用（如「承诺后不再自动过期」）
   */
  public record ReservationAllocationOption(
      String code, String label, boolean terminal, @Schema(nullable = true) String description) {}

  /**
   * 一个 bucket 下拉项。
   *
   * @param id 仓 / 货位 / 批次的 id
   * @param code 编码（仓编码、货位编码、批次号）
   * @param name 名称；批次没有名称列（{@code inventory_lot} 只有 lot_code），此时为 null
   * @param quantity 该候选项上圈定的量合计 —— 让用户在下拉框里就能看出 「这个货位上圈了 30 件」而不是选完才在明细里找
   * @param allocationCount 该候选项上的分配行数
   */
  public record ReservationAllocationBucketOption(
      UUID id,
      String code,
      @Schema(nullable = true) String name,
      BigDecimal quantity,
      long allocationCount) {}
}
