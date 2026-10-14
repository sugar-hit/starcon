package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.3.3.1 复核扫描的传输模型。
 *
 * <p>蓝图 08 卷 8.3.3.1 原文：「<b>打包前复核：逐件扫描商品与订单核对，检出漏件/错件/多件，支持开箱复核（高价值订单）。</b>
 * 输入：扫描记录（条码/序列号）；订单明细；复核规则。输出：复核通过/异常结果；异常单转人工处理。」 关系段：「上游 = 8.3.2.1 拣货任务 / 8.3.2.2 波次与合并拣货；下游 =
 * 8.3.3.2 打包。 <b>复核是错发拦截的最后机会</b>，序列号商品必须逐件核对。」
 *
 * <p><b>命名空间：全部 {@code Recount} 前缀。</b>springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas}
 * 的键，重名会被<b>静默覆盖</b>：别人的字段形状被改掉、 单测全绿、只有跨服务的契约比对才红。开工前逐个 grep 过 backend 各服务与 libraries 下的 Java 源码，
 * {@code Recount} 开头的十个类型名全部 0 冲突。
 *
 * <p><b>四个刻意不给默认值的地方：</b>
 *
 * <ul>
 *   <li><b>复核通过 ≠ 已发货 ≠ 已打包。</b>{@link RecountStatus} 四态里没有「已打包」与 「已发货」：复核通过只是把出库单交给
 *       8.3.3.2，打包与称重是下游的事， 发货是 8.3.4.1 的事。与 8.3.2.1「拣货完 ≠ 已发货」同一纪律。
 *   <li><b>件数相等 ≠ 核过了。</b>{@code requiresSerial} 的行必须 {@code serialMatch} （集合相等，由两个差集查询得出）才能拿
 *       MATCHED。三台机器里混进一台别的机器， 件数一样内容不一样，那是最典型的错件。
 *   <li><b>没有规则 ≠ 没有口径。</b>{@code ruleCode} 与 {@code ruleBasis} 允许为 null， 但那时 {@code verifyBasis}
 *       必须如实写「本次无规则，使用内置默认口径」—— 绝不假装有一条规则存在。
 *   <li><b>「高价值」在本服务里只能取件数。</b>{@code highValueQuantityThreshold} 的名字里是 quantity 不是
 *       amount：出库单上根本没有金额列（V72 §1 明写 「不存类目/售价/成本」），warehouse 侧造一个金额就是编数。详见 V117 §3。
 * </ul>
 */
public final class RecountModels {

  private RecountModels() {}

  // ------------------------------------------------------------ 词表

  /**
   * 复核单状态。
   *
   * <p>四态：复核中 / 复核通过 / 复核异常 / 已取消。<b>没有「已打包」与「已发货」</b> —— 蓝图把它们分给了 8.3.3.2 与
   * 8.3.4.1，混在一列里会让「复核通过」看起来像「已经发出去了」。
   */
  public enum RecountStatus {
    /** 复核中：可以扫、可以开箱、可以撤异常。 */
    OPEN("复核中", false),
    /** 复核通过：每一行都核齐了，且没有未处理的异常。出库单被推到「待打包」。 */
    PASSED("复核通过", true),
    /** 复核异常：检出漏件/错件/多件。异常单转人工处理，或退回拣货重做。 */
    FAILED("复核异常", true),
    /** 已取消：放弃这次复核（货退回打包区流程重来）。库存不动。 */
    CANCELLED("已取消", true);

    private final String label;
    private final boolean terminal;

    RecountStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this.terminal;
    }

    /** 能不能继续扫。只有「复核中」可以。 */
    public boolean acceptsScans() {
      return this == OPEN;
    }
  }

  /** 复核基线行的五态。<b>由数量 + 序列号集合派生</b>（V117 的 BEFORE UPDATE 触发器）， 应用层写不出与数量对不上的状态。 */
  public enum RecountLineStatus {
    /** 未核：本行一件都没扫到。 */
    PENDING("未核"),
    /** 核过：件数相等，且（若需逐件）序列号集合相等。 */
    MATCHED("核过"),
    /** 漏件：实扫 < 应发。 */
    MISSING("漏件"),
    /** 多件：实扫 > 应发。 */
    OVER("多件"),
    /** 序列号不符：件数可能对得上，但序列号集合与实拣集合不相等 —— 错件最典型的形态。 */
    MISMATCH("序列号不符");

    private final String label;

    RecountLineStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 扫描方式。蓝图输入原话是「扫描记录（条码/序列号）」，两值枚举而不是混着来的字符串。 */
  public enum RecountScanType {
    /** 条码：扫商品条码，按 SKU 核对。 */
    BARCODE("条码"),
    /** 序列号：扫单件码，逐件核对。 */
    SERIAL("序列号");

    private final String label;

    RecountScanType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 单次扫描的判定。DUPLICATE 由 DB 触发器强制（同一值第二次起只能是它）。 */
  public enum RecountVerdict {
    /** 命中：对上了这一行，且这一件确实该发。 */
    MATCHED("命中"),
    /** 错件：解析出了 SKU，但不是这一行该发的那个（重复件、串行）。 */
    WRONG_SKU("错件"),
    /** 未知码：扫出来的码在本仓解析不出 SKU。 */
    UNKNOWN("未知码"),
    /** 重复扫：同一个值在本复核单里第二次起。 */
    DUPLICATE("重复扫");

    private final String label;

    RecountVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 复核异常种类 —— 蓝图点名的「漏件/错件/多件」三值，加上三种现场真实存在的形态。
   *
   * <p>六类各有判定来源，不是同一类问题换个名字：SERIAL_MISMATCH 来自两个差集查询， DUPLICATE_SCAN 来自 recount_scan
   * 的第几次计数，UNKNOWN_BARCODE 来自条码解析失败。
   */
  public enum RecountExceptionKind {
    /** 漏件：该发的行没扫够。 */
    MISSING_ITEM("漏件"),
    /** 错件：扫到的 SKU 与该行不符。 */
    WRONG_ITEM("错件"),
    /** 多件：扫到的东西这张单压根没有。 */
    EXTRA_ITEM("多件"),
    /** 重复扫：同一个值第二次起。 */
    DUPLICATE_SCAN("重复扫"),
    /** 序列号集合与实拣集合不相等。 */
    SERIAL_MISMATCH("序列号集合不符"),
    /** 未知码：扫出来的码在本仓解析不出 SKU。 */
    UNKNOWN_BARCODE("未知码");

    private final String label;

    RecountExceptionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 异常处置。系统不推，由人写；PENDING 被清干净才许判通过（V117 §7.1）。 */
  public enum RecountResolution {
    /** 还没人管。 */
    PENDING("待处理"),
    /** 转人工处理（蓝图输出原话「异常单转人工处理」）。处置意见必填。 */
    MANUAL("转人工处理"),
    /** 已修正：退回拣货重拣，或现场换了一件对的。 */
    CORRECTED("已修正"),
    /** 接受现状：确认可以带着这个异常放行（必须写为什么）。 */
    ACCEPTED("接受现状"),
    /** 忽略：判定为误报（必须写为什么）。 */
    IGNORED("忽略");

    private final String label;

    RecountResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 复核留痕的事件类型，与 V117 的 CHECK 逐字一致。 */
  public enum RecountEventType {
    OPENED("开单"),
    OPEN_BOX("开箱"),
    SCANNED("扫描"),
    EXCEPTION_RAISED("检出异常"),
    ESCALATED("转人工"),
    RESOLVED("异常处置"),
    PASSED("复核通过"),
    FAILED("复核异常"),
    CANCELLED("取消");

    private final String label;

    RecountEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 建复核规则入参。
   *
   * @param warehouseId 适用仓。<b>可空 = 全仓通用</b>：一张仓网可以只有一套复核口径。
   * @param requireSerialCheck 「序列号商品必须逐件核对」的开关（蓝图关系段原话）。
   * @param highValueQuantityThreshold 高价值 → 强制开箱的<b>件数</b>阈值。 <b>刻意不是金额</b>：出库单上没有金额列（V72
   *     §1「不存类目/售价/成本」）， 金额的 owner 是 9 订单履约侧，跨服务读表被模块边界纪律禁止。 可空 = 不按件数强制开箱（仍可手动开箱）。
   */
  public record RecountRuleInput(
      String ruleCode,
      String name,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) Boolean requireSerialCheck,
      @Schema(nullable = true) BigDecimal highValueQuantityThreshold,
      @Schema(nullable = true) String openBoxNote) {}

  /** 开一份复核单入参。 */
  public record RecountOpenInput(
      UUID outboundOrderId,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) UUID reviewerUserId,
      @Schema(nullable = true) String note) {}

  /**
   * 扫一件。
   *
   * @param scanType 条码 / 序列号
   * @param scannedValue 条码或序列号原文（大小写敏感，不做 upper 归一 —— 序列号是铭牌字面量）
   * @param scannedAt 扫描时刻（PDA 时钟），不是 DB 的 now()
   * @param note 现场备注
   */
  public record RecountScanInput(
      String scanType,
      String scannedValue,
      @Schema(nullable = true) String scannedAt,
      @Schema(nullable = true) String note) {}

  /**
   * 开箱复核入参。
   *
   * <p>{@code reason} <b>必填</b>：开箱是一次要把货倒出来重新核的动作， 不写理由的开箱既费时又说不清。
   */
  public record RecountOpenBoxInput(String reason) {}

  /**
   * 处置一条异常入参。
   *
   * @param resolution 转人工 / 已修正 / 接受现状 / 忽略。
   * @param note 处置意见。<b>这四个值全都必填</b> —— 「已处理」而不写怎么处理， 半年后回看等于没处理。
   */
  public record RecountResolveInput(String resolution, String note) {}

  /** 转人工处理入参。{@code note} 必填：不写转给谁的什么话。 */
  public record RecountEscalateInput(String note) {}

  /** 取消复核入参。原因必填。 */
  public record RecountCancelInput(String reason) {}

  /** 停用复核规则入参。原因必填。 */
  public record RecountRetireInput(String reason) {}

  // ------------------------------------------------------------ 出参

  /** 开单 / 开箱 / 扫描 / 完成 之后返回的单头读数。 */
  public record RecountCreated(
      UUID id,
      String sessionCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      UUID pickTaskId,
      String pickTaskCode,
      UUID warehouseId,
      String ruleId,
      String status,
      String statusLabel,
      boolean openBox,
      String openBoxReason,
      Integer baselineLineCount,
      String baselineQuantity,
      Integer matchedLineCount,
      Integer mismatchLineCount,
      Integer exceptionCount,
      Integer pendingExceptionCount,
      String verifyBasis,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {}

  /** 复核单列表行。 */
  public record RecountRow(
      UUID id,
      String sessionCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundOrderStatus,
      String outboundOrderStatusLabel,
      UUID pickTaskId,
      String pickTaskCode,
      UUID warehouseId,
      String warehouseCode,
      UUID ruleId,
      String ruleCode,
      String status,
      String statusLabel,
      boolean openBox,
      // 开箱原因随列表行一起返回：只给一个「已开箱」而不给为什么，
      // 复核作业台上就没人能判断这一单是不是该开箱。
      String openBoxReason,
      boolean highValue,
      Integer baselineLineCount,
      String baselineQuantity,
      Integer scannedLineCount,
      String scannedQuantity,
      Integer matchedLineCount,
      Integer mismatchLineCount,
      Integer serialCheckedLineCount,
      Integer exceptionCount,
      Integer pendingExceptionCount,
      UUID reviewerUserId,
      String outboundStatusBefore,
      String verifyBasis,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt) {}

  /** 复核单分页页。汇总不受筛选影响 —— 与 8.3.2.1 / 8.3.2.2 同一纪律。 */
  public record RecountPage(
      List<RecountRow> items, int page, int size, long total, RecountSummary summary) {}

  /** 复核作业台汇总。 */
  public record RecountSummary(
      long totalSessions,
      long openSessions,
      long passedSessions,
      long failedSessions,
      long cancelledSessions,
      long openBoxSessions,
      long pendingExceptions,
      long manualExceptions,
      long missingItems,
      long wrongItems,
      long extraItems,
      long serialMismatches,
      String baselineQuantity,
      String scannedQuantity) {}

  /**
   * 复核基线行（应发明细的冻结快照 + 逐行结论）。
   *
   * <p>{@code expectedQuantity} 来自订单明细、{@code pickedQuantity} 来自实拣、 {@code scannedQuantity} 来自扫描 ——
   * <b>三个数分开</b>： 「拣漏了」和「复核没扫到」是两件不同的事，混成一个数就分不清该退回拣货还是重扫一遍。
   */
  public record RecountLineRow(
      UUID id,
      Integer lineNo,
      UUID outboundLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      String expectedQuantity,
      String pickedQuantity,
      boolean requiresSerial,
      Integer expectedSerialCount,
      Integer scannedSerialCount,
      boolean serialMatch,
      String scannedQuantity,
      String lineStatus,
      String lineStatusLabel,
      List<String> expectedSerials,
      List<String> scannedSerials) {}

  /** 一条扫描记录。 */
  public record RecountScanRow(
      UUID id,
      String scanType,
      String scannedValue,
      Integer scanOccurrence,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID serialId,
      String serialNumber,
      UUID outboundLineId,
      String quantity,
      String verdict,
      String verdictLabel,
      UUID operatorUserId,
      OffsetDateTime scannedAt,
      String note) {}

  /** 一条复核异常。 */
  public record RecountExceptionRow(
      UUID id,
      UUID scanId,
      String kind,
      String kindLabel,
      UUID outboundLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID serialId,
      String serialNumber,
      String expectedQuantity,
      String scannedQuantity,
      String resolution,
      String resolutionLabel,
      String manualNote,
      String detail,
      OffsetDateTime raisedAt,
      OffsetDateTime resolvedAt,
      UUID resolvedBy) {}

  /** 复核流水行。 */
  public record RecountEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 复核单详情。 */
  public record RecountDetail(
      RecountRow session,
      List<RecountLineRow> lines,
      List<RecountScanRow> scans,
      List<RecountExceptionRow> exceptions,
      List<RecountEventRow> timeline,
      RecountVerdictReport verdict) {}

  /**
   * 复核结论报告。
   *
   * <p>{@code blocking} 是「能不能放行给 8.3.3.2」的唯一判据，且它是 {@code blockingReasons} 非空**且** {@code
   * verdict=PASSED} 才为 false 的派生值 —— 界面上的「通过」按钮不该由前端自己判断该不该可点。
   */
  public record RecountVerdictReport(
      String verdict,
      String verdictLabel,
      boolean blocking,
      List<String> blockingReasons,
      Integer baselineLineCount,
      String baselineQuantity,
      Integer scannedLineCount,
      String scannedQuantity,
      Integer matchedLineCount,
      Integer mismatchLineCount,
      Integer serialCheckedLineCount,
      Integer pendingExceptionCount,
      boolean openBox,
      boolean openBoxRequired,
      String basis) {}

  /** 复核规则行。 */
  public record RecountRuleRow(
      UUID id,
      String ruleCode,
      String name,
      UUID warehouseId,
      String warehouseCode,
      boolean requireSerialCheck,
      BigDecimal highValueQuantityThreshold,
      String openBoxNote,
      String status,
      OffsetDateTime retiredAt,
      String retireReason,
      OffsetDateTime createdAt) {}

  /** 复核规则分页页。 */
  public record RecountRulePage(List<RecountRuleRow> items, int page, int size, long total) {}

  /** 复核规则详情：规则 + 被它复核过的会话数。 */
  public record RecountRuleDetail(RecountRuleRow rule, long sessionCount, List<String> statuses) {}

  /** 能力声明。 */
  public record RecountCapabilities(
      String refType,
      boolean serialVerificationLive,
      boolean setEqualityCheck,
      boolean barcodeScanSupported,
      boolean serialScanSupported,
      boolean duplicateScanDetected,
      boolean missingWrongExtraDetected,
      boolean openBoxSupported,
      boolean highValueRuleLive,
      boolean escalationSupported,
      boolean inventoryUnaffected,
      String inventoryOwnerModule,
      boolean passesOnlyWhenClean,
      boolean lastChanceGate,
      boolean packingHandoffLive,
      String downstreamModule,
      List<String> statuses,
      List<String> exceptionKinds,
      List<String> resolutions,
      List<String> sorts,
      String verifyBasis,
      String highValueBasis,
      List<RecountUpstreamModule> upstreams,
      List<RecountDownstreamModule> downstreams) {}

  /** 上游模块声明。 */
  public record RecountUpstreamModule(
      String moduleId, String name, String service, boolean live, String handoff) {}

  /** 下游模块声明。 */
  public record RecountDownstreamModule(
      String moduleId, String name, String service, boolean live, String handoff) {}
}
