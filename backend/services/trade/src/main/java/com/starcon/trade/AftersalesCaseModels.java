package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.1.1.1 售后单列表的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 80-84 行原文：
 *
 * <blockquote>
 * 职责：售后单主列表：售后号、关联订单、店铺、类型（退货退款/仅退款/换货/补发/维修）、金额、原因、状态、时效（平台要求回复/处理时限）、责任人，支持多维筛选与批量处理。
 * 输入：筛选条件（类型、状态、原因、店铺、时效、金额）；用户数据范围。
 * 输出：列表 + 时效预警标记；批量操作（授权、备注、转工单）。
 * 关系：上游 = 9 订单、15 客服、平台消息；下游 = 11.1.1.2 详情、11.2/11.3。
 * <b>平台时效倒计时必须显眼</b>：超时会直接影响账号绩效与资金冻结。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>时效是数据不是 UI 装饰。</b>{@link AftersalesCaseRow#timelinessState()} 是独立的状态枚举，
 *       {@link AftersalesCaseRow#replyDueAt()} / {@link AftersalesCaseRow#resolveDueAt()} /
 *       {@link AftersalesCaseRow#effectiveDueAt()} 是三个真列，{@link AftersalesCaseSort#DUE_ASC}
 *       按最紧的那个时限排、{@link AftersalesCaseQuery#timelinessState()} 按它筛 ——
 *       全在数据库层完成，<b>不是</b>页面上现算给人看的临时值。
 *   <li><b>「店铺」当前只有平台级。</b>{@code sales_order} 没有 shop_code 列（实查 V99），
 *       卷 2 的店铺在 channel 服务独立库、禁止跨服务 SQL ⇒ {@link AftersalesCaseRow#platformSource()}
 *       是平台级来源，{@link AftersalesCaseRow#platformShopCode()} 恒为 null 并在 capabilities 里
 *       显式报 unavailable。<b>不建一个恒为空的 shop 列</b>（照抄 9.1.1.1 的同一条纪律）。
 *   <li><b>批量操作逐条回报。</b>{@link AftersalesBulkResult} 给出每一条的成败与原因，
 *       不把「30 条批了 12 条」压成一个布尔值。
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code AftersalesCase*} / {@code AftersalesBulk*} 前缀，全仓 grep 确认与既有
 * 类型零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class AftersalesCaseModels {

  private AftersalesCaseModels() {}

  // ================================================================ 枚举

  /** 蓝图点名的五种售后类型，一个都不能少。 */
  public enum AftersalesCaseType {
    RETURN_REFUND("退货退款"),
    REFUND_ONLY("仅退款"),
    EXCHANGE("换货"),
    REISSUE("补发"),
    REPAIR("维修");

    private final String label;

    AftersalesCaseType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 售后单状态。
   *
   * <p>⚠️ 刻意**不含**「已退款」「已收到退货」这类值：那是 11.2 退货管理与 11.3 退款的状态，
   * 由那边维护。本列说的是**这张售后单在我们这边的处理进展**，两个口径混在一列里
   * 会出现「状态=已退款但货还没收到」这种自相矛盾的记录。
   */
  public enum AftersalesCaseStatus {
    RECEIVED("已受理待处理"),
    AUTHORIZING("授权中"),
    IN_PROCESS("处理中"),
    WAITING_BUYER("待买家响应"),
    RESOLVED("已解决"),
    REJECTED("已拒绝"),
    CLOSED("已关闭");

    private final String label;

    AftersalesCaseStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 三个终态：进终态就必须有 resolved_at（DB 层 CHECK 强制，见 V156）。 */
    public boolean terminal() {
      return this == RESOLVED || this == REJECTED || this == CLOSED;
    }
  }

  /** 原因分类：照蓝图 11.1.2.1 点名的七类逐字对齐。11.1.2.1 未交付，故此处是内联词表。 */
  public enum AftersalesReasonCode {
    QUALITY_DEFECT("质量问题"),
    DESCRIPTION_MISMATCH("描述不符"),
    SIZE_MISMATCH("尺寸不符"),
    LOGISTICS_DAMAGE("物流损坏"),
    BUYER_REASON("买家原因"),
    MISTAKE_BUY("误购"),
    FRAUD_RETURN("欺诈退货");

    private final String label;

    AftersalesReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 时效状态（蓝图加粗句「平台时效倒计时必须显眼」的**数据落点**）。
   *
   * <p>⚠️ 刻意是**独立的枚举**而不是让前端拿 due_at 自己比大小：颜色与文案散落在每个前端组件里，
   * 早晚会出现「一处按 12 小时算临期、另一处按 24 小时算」的不一致，而超时直接关系到账号绩效与
   * 资金冻结，这个不一致的代价太高。判定的唯一真身在数据库函数
   * {@code aftersales_timeliness_state()}（V156），前端只负责把枚举画出来。
   */
  public enum AftersalesTimelinessState {
    /** 已超时：平台已经在计绩效、可能冻结资金。 */
    OVERDUE("已超时"),
    /** 临期：距时限 ≤ 24 小时（阈值在 V156 的函数里写死，语义只有这一处定义）。 */
    DUE_SOON("临期"),
    /** 正常。 */
    ON_TRACK("正常"),
    /** 已处理：倒计时已停止，不再报警。 */
    RESOLVED("已处理"),
    /**
     * 平台未给时限。
     *
     * <p>⚠️ 刻意与 ON_TRACK 分开：「没有人给时限」和「有时限且还早」对运营是两条相反的指令 ——
     * 前者要去平台催一个时限出来，后者不用管。合成一个值会让前者被读成后者。
     */
    NO_DEADLINE("无平台时限");

    private final String label;

    AftersalesTimelinessState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 授权种类与售后类型**一一对应**（V156 的触发器 guard_aftersales_authorization_kind 钉死）。 */
  public enum AftersalesAuthorizationKind {
    RETURN_APPROVED("退货退款授权"),
    REFUND_APPROVED("退款授权"),
    EXCHANGE_APPROVED("换货授权"),
    REISSUE_APPROVED("补发授权"),
    REPAIR_APPROVED("维修授权");

    private final String label;

    AftersalesAuthorizationKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 这条售后类型该配的授权种类；DB 触发器里也有一份同样的映射，两边都要能看懂。 */
    public static AftersalesAuthorizationKind forType(AftersalesCaseType type) {
      return switch (type) {
        case RETURN_REFUND -> RETURN_APPROVED;
        case REFUND_ONLY -> REFUND_APPROVED;
        case EXCHANGE -> EXCHANGE_APPROVED;
        case REISSUE -> REISSUE_APPROVED;
        case REPAIR -> REPAIR_APPROVED;
      };
    }
  }

  /** 授权决定。 */
  public enum AftersalesDecision {
    APPROVED("批准"),
    PARTIALLY_APPROVED("部分批准"),
    REJECTED("拒绝");

    private final String label;

    AftersalesDecision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 责任人来源。
   *
   * <p>照抄 V150 {@code order_risk_signal.signal_source} 的既有解法：15 客服未交付，
   * 责任人只能由租户自己登记；正式接口到位后由同步器写 MODULE_* 行，<b>表结构不变</b>。
   */
  public enum AftersalesOwnerSource {
    TENANT_MAINTAINED("租户自行登记"),
    MODULE_15_SERVICE_STAFF("15 客服同步（未交付）");

    private final String label;

    AftersalesOwnerSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 工单优先级。 */
  public enum AftersalesTicketPriority {
    LOW("低"),
    NORMAL("普通"),
    HIGH("高"),
    URGENT("紧急");

    private final String label;

    AftersalesTicketPriority(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 批量操作三件：授权、备注、转工单（蓝图输出句点名的三样，三件都可用）。 */
  public enum AftersalesBulkOperation {
    AUTHORIZE("授权"),
    NOTE("备注"),
    ESCALATE("转工单");

    private final String label;

    AftersalesBulkOperation(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AftersalesBulkOperation parse(String raw) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("批量操作必须是 AUTHORIZE / NOTE / ESCALATE 之一");
      }
      try {
        return valueOf(raw.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "批量操作只能是 AUTHORIZE（授权）/ NOTE（备注）/ ESCALATE（转工单）：收到 " + raw);
      }
    }
  }

  /**
   * 可用排序维度。
   *
   * <p>⚠️ 全部落在<b>真实存在</b>的列上：{@code effective_due_at}（触发器维护的真列）、{@code
   * received_at}、{@code requested_amount}。平台状态、支付状态这类列在本服务不存在，
   * 就不提供对应排序项 —— 点下去发现顺序没变的排序项比没有更糟。
   */
  public enum AftersalesSort {
    DUE_ASC("最紧急的时限在前（超时排最上）", "a.effective_due_at asc nulls last, a.id asc"),
    DUE_DESC("最宽松的时限在前", "a.effective_due_at desc nulls last, a.id asc"),
    RECEIVED_DESC("受理时间，最新在前", "a.received_at desc, a.id desc"),
    RECEIVED_ASC("受理时间，最早在前", "a.received_at asc, a.id asc"),
    AMOUNT_DESC("申请金额，由高到低", "a.requested_amount desc, a.id desc"),
    AMOUNT_ASC("申请金额，由低到高", "a.requested_amount asc, a.id asc");

    private final String label;
    private final String sql;

    AftersalesSort(String label, String sql) {
      this.label = label;
      this.sql = sql;
    }

    public String label() {
      return label;
    }

    public String sql() {
      return sql;
    }

    /** 非法值在这里就 400，不静默回落到默认排序。 */
    public static AftersalesSort parse(String raw) {
      if (raw == null || raw.isBlank()) return DUE_ASC;
      try {
        return valueOf(raw.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "排序维度只能是 DUE_ASC / DUE_DESC / RECEIVED_DESC / RECEIVED_ASC / AMOUNT_DESC / AMOUNT_ASC");
      }
    }
  }

  // ================================================================ 入参

  /**
   * 列表查询。
   *
   * <p><b>每一个筛选参数都对应一个真实存在的列、索引或 DB 函数</b>。筛选与排序都在数据库层完成，
   * <b>绝不</b>是「查全量再在 Java 里过滤」—— 那是列表模块最容易犯、后果最严重的错：
   * 它在多租户下会变成跨租户泄露（A 家看到 B 家的买家与退款金额），在数据量上会变成全表扫描。
   *
   * <p>⚠️ <b>刻意没有 {@code shopCode} 参数</b>：{@code sales_order} 没有 shop_code 列（实查 V99），
   * 卷 2 的店铺在 channel 服务独立库、禁止跨服务 SQL。提供一个永远筛不出东西的下拉框
   * 会让界面误以为这条链路已经通了。平台级筛选用 {@link #platformSource()}（映射 sales_order.order_source）。
   *
   * @param actorId / selfOnly 由服务端从**已认证身份**与角色数据范围推导，<b>不接受请求参数传入</b>
   */
  public record AftersalesCaseQuery(
      @Schema(nullable = true) String caseType,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String reasonCode,
      @Schema(nullable = true) String platformSource,
      @Schema(nullable = true) String timelinessState,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String authorizationState,
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      @Schema(nullable = true) OffsetDateTime receivedFrom,
      @Schema(nullable = true) OffsetDateTime receivedTo,
      @Schema(nullable = true) String ownerRef,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) Boolean selfOnly) {}

  /**
   * 登记一张售后单。
   *
   * <p>⚠️ <b>这是 11.1.1.3 售后受理未交付期间的登记入口</b>，它只做「单据落库 + 平台时限登记」，
   * <b>不含</b>受理判定（售后期校验 / 条件判定 / 责任人分配 / 回复时限设置）——
   * 那四件事是 11.1.1.3 的职责，它交付后应当接管本入口。
   * capabilities 里 {@code intakeDelivered=false} 如实报出这一点，
   * <b>不让调用方从「为什么要我自己填时限」去猜哪一环没通</b>。
   *
   * @param ownerRef / ownerSource 责任人登记（15 客服未交付，租户自行维护）
   * @param replyDueAt / resolveDueAt 平台给的两个时限；DB CHECK 要求至少给一个
   */
  public record AftersalesCaseCreate(
      UUID orderId,
      String caseType,
      String reasonCode,
      @Schema(nullable = true) String reasonNote,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      @Schema(nullable = true) String platformCaseNo,
      @Schema(nullable = true) String platformCaseUrl,
      @Schema(nullable = true) OffsetDateTime platformReceivedAt,
      @Schema(nullable = true) String buyerRequest,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      @Schema(nullable = true) String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel,
      @Schema(nullable = true) String ownerSource,
      @Schema(nullable = true) String ownerSourceReference,
      @Schema(nullable = true) OffsetDateTime ownerCapturedAt) {}

  /**
   * 批量操作请求。
   *
   * <p><b>三种操作共用一个请求体</b>：它们要落的三张表形状接近、逐条判定规则也一样
   * （逐条找单 → 逐条判定 → 逐条回报），拆成三个端点只会让「逐条结果回报」这个最要紧的
   * 行为出现三份实现。
   *
   * @param caseIds 目标售后单 ID；<b>单次上限 100 条</b>（BULK_MAX，服务层校验）
   * @param note NOTE 用：备注正文（非空）
   * @param decision / authorizedAmount AUTHORIZE 用：决定与授权金额
   * @param priority ESCALATE 用：工单优先级
   */
  public record AftersalesBulkRequest(
      String operation,
      List<UUID> caseIds,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) String decision,
      @Schema(nullable = true) BigDecimal authorizedAmount,
      @Schema(nullable = true) String reasonNote,
      @Schema(nullable = true) String priority) {}

  // ================================================================ 出参

  /**
   * 列表行 —— 蓝图职责句里点名的每一列都在这里。
   *
   * @param platformSource **平台级**来源（映射 sales_order.order_source）
   * @param platformShopCode <b>恒为 null</b>：sales_order 没有 shop_code 列（实查 V99），
   *     卷 2 店铺在 channel 服务独立库、禁止跨服务 SQL ⇒ 店铺维度当前不可得
   * @param timelinessState 时效状态（独立枚举，判定真身在 DB 函数里）
   * @param effectiveDueAt 最紧的那个时限（触发器维护的真列，可排序可筛选）
   * @param remainingSeconds 距 effectiveDueAt 的剩余秒数；已结案或无时限为 <b>null</b>（null 有语义：不是 0）
   * @param ownerSource 责任人来源；MODULE_15_SERVICE_STAFF 当前<b>没有写入者</b>
   * @param ticketNo 已转工单的工单号；未转则 null
   */
  public record AftersalesCaseRow(
      UUID id,
      String caseNo,
      UUID orderId,
      String orderNo,
      String platformSource,
      @Schema(nullable = true) String platformShopCode,
      String caseType,
      String caseTypeLabel,
      String status,
      String statusLabel,
      String reasonCode,
      String reasonLabel,
      @Schema(nullable = true) String reasonNote,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      String timelinessState,
      String timelinessLabel,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime effectiveDueAt,
      @Schema(nullable = true) Long remainingSeconds,
      @Schema(nullable = true) String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel,
      @Schema(nullable = true) String ownerSource,
      boolean ownerUpstreamDelivered,
      @Schema(nullable = true) String platformCaseNo,
      String authorizationState,
      @Schema(nullable = true) String ticketNo,
      long noteCount,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt) {}

  /**
   * 列表页：真分页（LIMIT/OFFSET + 独立 COUNT(*)）+ 与分页无关的全量汇总。
   *
   * @param evaluatedAt 本次查询判定时效的**同一个**参考时刻。
   *     前端倒计时以它为基准 —— 整页共用一个基准，翻页时同一张单不会在「临期」和「正常」之间跳。
   * @param amountRangeSemantics 金额区间的口径原文（闭区间），界面上要写出来
   */
  public record AftersalesCasePage(
      List<AftersalesCaseRow> rows,
      int page,
      int size,
      long total,
      AftersalesCaseSummary summary,
      OffsetDateTime evaluatedAt,
      String amountRangeSemantics,
      String timelinessThresholdNote,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 汇总条。
   *
   * <p>⚠️ <b>金额按币种分开报</b>：本服务没有任何汇率来源（12 财务未交付），
   * USD 与 EUR 加起来不是任何一个金额。
   *
   * @param overdueCount / dueSoonCount 时效预警计数 —— 蓝图加粗句的**列表级**落点
   */
  public record AftersalesCaseSummary(
      long totalCount,
      long openCount,
      long overdueCount,
      long dueSoonCount,
      long onTrackCount,
      long noDeadlineCount,
      long resolvedCount,
      long ticketCount,
      List<AftersalesAmountBucket> amountsByCurrency,
      List<AftersalesTypeBucket> countsByType,
      String amountsNote) {}

  /** 单币种金额汇总。金额一律 BigDecimal，对应 DB 的 numeric(19,4)，禁浮点。 */
  public record AftersalesAmountBucket(String currency, BigDecimal amount, long caseCount) {}

  /** 单类型计数。 */
  public record AftersalesTypeBucket(String caseType, String label, long caseCount) {}

  /** 下拉筛选项（租户级词表，<b>不随筛选变化</b>，避免下拉随翻页漂移）。 */
  public record AftersalesFilterOptions(
      List<AftersalesEnumOption> caseTypes,
      List<AftersalesEnumOption> statuses,
      List<AftersalesEnumOption> reasonCodes,
      List<AftersalesEnumOption> timelinessStates,
      List<String> platformSources,
      List<String> currencies,
      List<String> authorizationStates,
      List<AftersalesCaseSortOption> sorts,
      int bulkMaxSize) {}

  /** 枚举下拉项。 */
  public record AftersalesEnumOption(String code, String label) {}

  /** 单个排序选项。 */
  public record AftersalesCaseSortOption(String code, String label) {}

  /**
   * 逐条结果回报 —— 批量操作的**核心**产出。
   *
   * <p>⚠️ 「30 条批了 12 条」不是一个可以接受的结果：运营必须知道<b>哪 12 条成功、哪 18 条失败、
   * 为什么失败</b>，才能回头一条条处理。{@code items} 是这个信息的唯一载体。
   *
   * @param items 每条目标一个元素，<b>顺序与请求里的 caseIds 一致</b>（含重复项：重复的那条自己会失败）
   * @param succeeded / failed 是 items 里的计数，三者与 requested 的关系：requested = succeeded + failed
   */
  public record AftersalesBulkResult(
      String operation,
      String operationLabel,
      int requested,
      int succeeded,
      int failed,
      List<AftersalesBulkItemResult> items,
      String batchId,
      String dataScope,
      String note) {}

  /**
   * 单条批量结果。
   *
   * @param outcome 结果码（稳定的机器可读值，见服务层常量）：AUTHORIZED / NOTE_ADDED / TICKET_CREATED /
   *     NOT_FOUND / ALREADY_TICKETED / STATUS_TERMINAL / TYPE_MISMATCH / AMOUNT_EXCEEDS /
   *     DUPLICATE_IN_REQUEST / AMOUNT_REQUIRED / AMOUNT_NOT_ALLOWED / CURRENCY_MISMATCH / NOTE_REQUIRED
   * @param detail 人话原因，界面直接显示
   * @param reference 产物引用（授权 id / 备注 id / 工单号），成功时非空
   */
  public record AftersalesBulkItemResult(
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String caseNo,
      boolean success,
      String outcome,
      String detail,
      @Schema(nullable = true) String reference) {}

  /**
   * 能力声明：把「哪些维度真有依据」「上游通没通」「下游交付没交付」显式报出来，
   * 而不是让调用方从 404 或空数组里去猜哪一环没通（照 9.1.1.1 已建立的仓库约定）。
   */
  public record AftersalesCaseCapabilities(
      List<String> availableDimensions,
      List<AftersalesCaseDimension> unavailableDimensions,
      List<AftersalesBulkOperationSpec> bulkOperations,
      List<AftersalesCaseSortOption> availableSorts,
      String filterableNote,
      boolean crossServiceReadAvailable,
      String crossServiceNote,
      AftersalesDownstream downstream,
      String shopGranularityNote,
      String timelinessNote,
      String intakeNote) {}

  /**
   * 蓝图维度里<b>本服务拿不到</b>的项。
   *
   * @param field 列表响应里对应为 null 的字段名
   * @param ownerModule 该维度的归属模块（谁交付之后它才会变成真数据）
   */
  public record AftersalesCaseDimension(
      String field, String label, boolean available, String ownerModule, String reason) {}

  /**
   * 批量操作规格。三件<b>全部可用</b>（蓝图输出句点名，且它们都落在本服务内的真实表上）。
   *
   * @param maxBatchSize 单次上限（超出 400）
   * @param landsIn 本模块写入的表名（说明产物落在本服务内，<b>不假装已对接下游模块</b>）
   */
  public record AftersalesBulkOperationSpec(
      String code,
      String label,
      boolean available,
      int maxBatchSize,
      String landsIn,
      String precondition,
      String reason,
      List<String> outcomeCodes) {}

  /** 下游可用性：11.1.1.2 / 11.1.1.3 / 11.2 / 11.3 当前<b>全部未交付</b>。 */
  public record AftersalesDownstream(
      boolean detailDelivered,
      boolean intakeDelivered,
      boolean returnsDelivered,
      boolean refundsDelivered,
      boolean ticketDelivered,
      String detailOwner,
      String intakeOwner,
      String returnsOwner,
      String refundsOwner,
      String ticketOwner,
      String note) {}
}
