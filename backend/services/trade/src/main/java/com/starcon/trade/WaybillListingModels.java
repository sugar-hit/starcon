package com.starcon.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.2.1.1 运单列表的契约形状。
 *
 * <p>风格照抄 10.2.1.2（{@code WaybillModels}）与 9.4.1.2（{@code ShipmentRecordModels}）：
 * 枚举与 record 同文件，输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p>⚠️ 载荷一律用<b>具名 record</b> 而不是 {@code Map<String,Object>}：Map 的值类型
 * 会被 springdoc 推成 {@code object}，codegen 出空对象 {@code {}}（9.6.1.3 已踩过）。
 */
final class WaybillListingModels {
  private WaybillListingModels() {}

  // ================================================================ 枚举

  /**
   * 运单**展示**状态。
   *
   * <p>蓝图给的是七个中文态，本枚举逐个对上，并额外给每个态标 {@code available}
   * —— 「这个态现在**能不能**从上游兑现」。这是本模块最要紧的一个字段。
   *
   * <p>蓝图那加粗句写的是「运单状态从承运商侧获取为准」，而 10.3 物流跟踪**未交付**，
   * 集成头里**没有任何承运商轨迹数据源**。于是七个态里：
   *
   * <ul>
   *   <li>待取号 / 已取号 / 已取消 —— 从 10.2.1.2（V128，同库只读）兑现；{@code available=true}
   *   <li><b>申请失败</b> —— 也是 10.2.1.2 的真实状态，但它**不等于**蓝图的「异常」：
   *       申请失败是「我们还没拿到号」，承运商异常是「号有了、货在途中出问题」。
   *       混成一个「异常」会让现场以为票已经在路上而漏跟进，所以单列为一个态，
   *       并在 {@code statusSource} 里写明它来自<b>内部申请状态</b>。
   *   <li>已揽收 / 运输中 / 已签收 / 异常 —— {@code available=false}，
   *       ownerModule=10.3.x。**这四个态不产出任何行**：不画 0、不留空、
   *       更不默认「运输中」—— 留空会被读成「一切正常」。
   * </ul>
   */
  enum WaybillDisplayStatus {
    PENDING_NUMBER("待取号", true, "10.2.1.2 运单申请（trade V128，同库只读）"),
    NUMBER_ISSUED("已取号", true, "10.2.1.2 运单申请（V128：承运商已真实返回运单号）"),
    APPLICATION_FAILED("申请失败", true, "10.2.1.2 运单申请（V128：申请未成功，**不是**承运商轨迹异常）"),
    CANCELLED("已取消", true, "10.2.1.2 运单申请（V128：作废且未出号）"),
    PICKED_UP("已揽收", false, "10.3.1.1 物流轨迹未交付，没有承运商揽收回执可读"),
    IN_TRANSIT("运输中", false, "10.3.1.1 物流轨迹未交付，没有承运商在途数据可读"),
    DELIVERED("已签收", false, "10.3.1.1 物流轨迹未交付，没有签收回执可读"),
    EXCEPTION("异常", false, "10.3.1.2 异常件处置未交付，没有承运商侧异常回执可读");

    public final String displayName;
    /** 现在能不能从已交付的上游真的取到这个态。false ⇒ 列表里永远不出现这个值。 */
    public final boolean available;

    /** available=false 时的归因与理由；available=true 时是真实来源。 */
    public final String source;

    WaybillDisplayStatus(String displayName, boolean available, String source) {
      this.displayName = displayName;
      this.available = available;
      this.source = source;
    }
  }

  /**
   * 承运渠道（与 V128 / V124 <b>同一词表</b>，不是本模块另起一套）。
   *
   * <p>刻意做成「带标签的常量词表」而不是数据源：渠道枚举本身是 10.1 的口径，
   * 而「这个租户实际登记了哪些渠道」由 {@code /waybills/filter-options} 从
   * <b>真实数据</b> DISTINCT 出来 —— 两者分工明确，不把常量当数据。
   */
  enum WaybillCarrierChannel {
    PLATFORM_INTEGRATED("平台集成"),
    THIRD_PARTY("三方物流"),
    SELF_PICKUP("自提"),
    POSTAL("邮政"),
    FREIGHT_FORWARDER("货代");

    public final String displayName;

    WaybillCarrierChannel(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 运费缺口码。**没有这一列就能读出「这笔运费是 0 还是没人登记」** —— 两回事。 */
  enum WaybillFreightGapCode {
    REGISTERED("已登记"),
    NOT_REGISTERED("未登记");

    public final String displayName;

    WaybillFreightGapCode(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 面单凭据可用性。8.3.4.2 未交付 ⇒ 本模块只发凭据、不渲染。 */
  enum WaybillLabelAvailability {
    RECORDED_REFERENCE("已登记面单凭据"),
    NO_REFERENCE("未登记面单凭据");

    public final String displayName;

    WaybillLabelAvailability(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  public record WaybillListingCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule,
      String impact) {}

  public record WaybillListingCapabilities(
      List<WaybillListingCapabilityDimension> dimensions,
      List<WaybillDisplayStatusOption> statuses,
      List<String> carrierChannels,
      List<String> freightSources,
      List<String> batchActions,
      String statusRule,
      String freightRule,
      String boundaryNote) {}

  /** 一个展示态在界面上该怎么画（含「拿不到」时的归因）。 */
  public record WaybillDisplayStatusOption(
      String status, String label, boolean available, String source, String ownerModule,
      String note) {}

  // ================================================================ 清单

  /**
   * 运单清单行。一行 = **一张运单申请（逐包裹一张）**。
   *
   * <p>行粒度刻意与 10.2.1.2 的 {@code logistics_waybill_request} 一致，**不**再拼
   * 待发货包裹去凑「还没申请的行」：那样每一行都得自己判断「有没有申请」，
   * 而蓝图那句「运单主列表」说的是运单本身。待取号由 {@code QUEUED/SUBMITTING}
   * 真实覆盖（已入队等窗口、已取出在提交），不需要靠拼假行凑出这个态。
   */
  public record WaybillListRow(
      UUID id,
      String requestNo,
      String waybillNo,
      String salesOrderNo,
      String outboundOrderNo,
      UUID pendingParcelId,
      String warehouseRef,
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      String destinationCountry,
      WaybillDisplayStatus displayStatus,
      String displayStatusLabel,
      String statusSource,
      String statusNote,
      String upstreamStatus,
      String upstreamStatusLabel,
      BigDecimal grossWeightKg,
      BigDecimal freightAmount,
      String freightCurrency,
      WaybillFreightGapCode freightGapCode,
      String freightGapLabel,
      String freightSource,
      WaybillLabelAvailability labelAvailability,
      String labelAvailabilityLabel,
      String labelFormat,
      String labelUri,
      OffsetDateTime createdAt,
      OffsetDateTime issuedAt,
      OffsetDateTime notBefore,
      String nextAction) {}

  /**
   * 清单汇总。
   *
   * <p>⚠️ <b>这些数字全部来自一条独立于分页的查询</b>：分页只切 {@code items}，
   * 汇总永远覆盖<b>筛选后的全量</b>。把汇总跟着 {@code LIMIT/OFFSET} 走是列表页
   * 最常见的假活 —— 第一页显示「共 20 条、运费 0」，实际筛选出 380 条。
   *
   * <p>运费<b>按币种分组</b>给，不给一个混币总数：跨币种相加是 10.4 明确交给
   * 10 运费试算的职责，而那条线（10.1.2.1 / 10.1.2.2）未交付。
   */
  public record WaybillListSummary(
      long total,
      long pendingNumber,
      long numberIssued,
      long applicationFailed,
      long cancelled,
      long issuedWithoutCarrierTracking,
      long withoutWaybillNo,
      long freightRegistered,
      long freightNotRegistered,
      List<WaybillFreightCurrencyTotal> freightByCurrency,
      long printableCount,
      long withoutLabelReference,
      long distinctCarriers,
      long distinctDestinationCountries,
      List<String> notes) {}

  public record WaybillFreightCurrencyTotal(String currency, long waybills, BigDecimal totalAmount) {}

  public record WaybillListPage(
      List<WaybillListRow> items, int page, int size, long totalElements, long totalPages,
      WaybillListSummary summary, String statusFilterNote) {}

  public record WaybillListDetail(
      WaybillListRow waybill, List<WaybillFreightRow> freightHistory,
      List<WaybillPrintLogRow> printHistory, List<String> nextActions, List<String> boundaryNotes) {}

  /** 运费登记历史（含已被撤下的历史行）—— 凭据不可改写，所以历史看得见。 */
  public record WaybillFreightRow(
      UUID id, UUID requestId, String requestNo, String carrierCode, String currency,
      BigDecimal amount, String chargeType, String source, String evidenceRef, boolean current,
      OffsetDateTime registeredAt) {}

  public record WaybillPrintLogRow(
      UUID id, String batchNo, UUID requestId, String requestNo, String waybillNo,
      String carrierCode, String labelFormat, String labelUri,
      WaybillLabelAvailability labelAvailability, String printerTarget, String remark,
      OffsetDateTime printedAt) {}

  /**
   * 筛选下拉的选项集合 —— 全部来自<b>真实数据 DISTINCT</b>，不是硬编码数组。
   *
   * <p>9.4.1.2 / 10.2.1.2 都栽过这一类：把「可选渠道」写成 Java 常量数组，
   * 结果租户 A 的下拉里出现租户 B 才有的渠道，点下去永远 0 条。
   */
  public record WaybillListFilterOptions(
      List<WaybillCarrierOption> carriers,
      List<String> carrierChannels,
      List<String> destinationCountries,
      List<String> warehouseRefs,
      List<WaybillDisplayStatusOption> statuses,
      List<String> sortOptions,
      String note) {}

  public record WaybillCarrierOption(String carrierCode, long waybills) {}

  // ================================================================ 输入

  /** 登记一票运费（金额 BigDecimal + NUMERIC(19,4)，**禁浮点**）。 */
  public record WaybillFreightInput(
      @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写代码") String currency,
      @NotBlank(message = "运费金额不能为空")
          @DecimalMin(value = "0", message = "运费金额不能为负") BigDecimal amount,
      @NotBlank(message = "费用类型不能为空") String chargeType,
      @NotBlank(message = "来源不能为空") String source,
      @Size(max = 200) String evidenceRef,
      @Size(max = 400) String remark) {}

  /** 批量动作的目标：一次操作的运单行。刻意按 id 选，不接受「全选所有」。 */
  public record WaybillBulkActionInput(
      @NotEmpty(message = "必须选择要操作的运单")
          @Size(min = 1, max = 200, message = "一次最多操作 200 张运单")
          List<@NotBlank(message = "运单 id 不能为空") String> requestIds,
      @Size(max = 400) String reason,
      @Size(max = 120) String printerTarget,
      @Size(max = 400) String remark) {}

  /** 逐条结果 —— 批量里一条失败不能把整批吞掉。 */
  public record WaybillBulkActionEntry(
      UUID requestId, String requestNo, String waybillNo, String action, boolean applied,
      String resultCode, String resultMessage, String currentStatus, String currentStatusLabel) {}

  public record WaybillBulkActionResult(
      String action, int selected, int applied, int skipped, int failed,
      String batchNo, List<WaybillBulkActionEntry> entries, List<String> notes) {}
}
