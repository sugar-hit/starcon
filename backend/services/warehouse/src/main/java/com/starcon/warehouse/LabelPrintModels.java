package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.3.4.2 面单与标签打印的传输模型。
 *
 * <p>蓝图 08-warehouse-operations.md 8.3.4.2 原文：「打印面单、装箱单、SKU 条码、库位标签、箱唛，
 * 支持批量打印、模板配置、打印次数记录（平台对重复打印有要求）。」输入：「打印任务（对象集合、模板）；
 * 打印机配置。」输出：「打印任务记录 + 打印内容（PDF/指令流）；打印日志。」以及本模块的红线：
 * 「**面单打印次数需记录**，部分平台对重复面单有明确规则。」
 *
 * <p><b>面单数据不在本模块</b>：蓝图上游是 10 物流（面单数据）与 3.1.2.3（条码），两者都尚未交付。
 * 所以本模块不建物流表、不读 10 服务的库（AGENTS.md：禁止跨服务 SQL / FK），
 * 打印对象是调用方传入的**快照**加一个纯文本外部引用。本模块负责的是
 * 「打印任务 → 模板 → 渲染 → 次数 → 日志」这条闭环；上游交付后要改的是「谁来填快照」，
 * 不是这条链路本身。
 *
 * <p><b>打印次数是本模块最容易被做成假逻辑的地方</b>：只往日志表里写一行「第 3 次打印」，
 * 而权威计数列纹丝不动，重复打印就永远查不出来。所以 {@link LabelPrintTarget#printCount} 是权威计数
 * （DB 触发器钉死只增不减），{@link LabelPrintLog#printSequence} 与它同步落库，
 * {@link LabelReprintRule} 给出平台维度的判据，三者互相校验。
 */
public final class LabelPrintModels {

  private LabelPrintModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 五种打印对象（蓝图逐字列出）。
   *
   * <p>五者共用一套任务 / 模板 / 日志骨架，差别只在<b>渲染形态</b>与<b>必填字段</b>：
   *
   * <ul>
   *   <li>{@link #SHIPPING_LABEL} 面单 —— 贴包裹外侧的物流面单，运单号与路由是它的身份。
   *       <b>重复打印对它最要紧</b>：贴错贴歪要重打，而部分平台对同一面单的重复次数有硬上限。
   *   <li>{@link #PACKING_SLIP} 装箱单 —— 随箱走的装箱明细，SKU 与件数是它的内容。
   *   <li>{@link #SKU_BARCODE} SKU 条码 —— 商品条码，收货区扫它入库。
   *   <li>{@link #LOCATION_LABEL} 库位标签 —— 贴在货架/库位上，库位编码是它的身份。
   *   <li>{@link #CARTON_MARK} 箱唛 —— 外箱唛头，箱号与箱型规格是它的内容。
   * </ul>
   */
  public enum PrintObjectType {
    SHIPPING_LABEL("面单", "贴包裹外侧的物流面单，重复打印受平台规则约束", true),
    PACKING_SLIP("装箱单", "随箱装箱明细，列出 SKU 与件数", false),
    SKU_BARCODE("SKU 条码", "商品条码，收货区扫描入库", false),
    LOCATION_LABEL("库位标签", "贴在库位上，库位编码为身份", false),
    CARTON_MARK("箱唛", "外箱唛头，箱号与箱型规格", false);

    private final String label;
    private final String description;
    /** 该对象类型是否受「重复打印次数」判据约束（其余类型默认不限次但仍计数留痕）。 */
    private final boolean reprintGoverned;

    PrintObjectType(String label, String description, boolean reprintGoverned) {
      this.label = label;
      this.description = description;
      this.reprintGoverned = reprintGoverned;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    public boolean reprintGoverned() {
      return reprintGoverned;
    }
  }

  /**
   * 打印内容格式 —— 蓝图说的是「PDF/指令流」，现场要的是能直接进打印机的字节。
   *
   * <ul>
   *   <li>{@link #ZPL} 指令流 —— 标签类（面单 / 条码 / 库位标签）走这条，热敏打印机直接吃 ZPL 指令。
   *   <li>{@link #HTML} 打印视图 —— 单据类（装箱单 / 箱唛）走这条，浏览器直接排版打印。
   * </ul>
   *
   * <p>两种都是<b>由本服务按模板与快照现算</b>的，库里没有写死的假 PDF；
   * 下载端点按本字段原样吐字节。
   */
  public enum PrintContentFormat {
    ZPL("ZPL 指令流", "标签机指令流，热敏打印机直接接收"),
    HTML("HTML 打印视图", "浏览器排版打印视图，用于单据类对象");

    private final String label;
    private final String description;

    PrintContentFormat(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    public String contentType() {
      // ZPL 也带 charset：内容里有中文（收件人、地址），不声明就会被按 ISO-8859-1 解，
      // 现场拿到的条码与地址全是乱码。
      return this == ZPL ? "text/vnd.zpl; charset=utf-8" : "text/html; charset=utf-8";
    }

    public String extension() {
      return this == ZPL ? "zpl" : "html";
    }
  }

  /** 设备类型：热敏标签机 vs 激光单打印机。 */
  public enum PrintDeviceKind {
    THERMAL_LABEL("热敏标签机", "打印面单 / 条码 / 库位标签，吐 ZPL 指令流"),
    LASER_DOCUMENT("激光打印机", "打印装箱单 / 箱唛等单据，吐 HTML 打印视图");

    private final String label;
    private final String description;

    PrintDeviceKind(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** 打印任务状态机（与 DB 的 guard_print_job_transition 同一套）。 */
  public enum PrintJobStatus {
    DRAFT("草稿"),
    QUEUED("已排队"),
    PRINTING("打印中"),
    COMPLETED("已完成"),
    FAILED("已失败"),
    CANCELLED("已取消");

    private final String label;

    PrintJobStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 重复打印判定结果 —— 「下一次打印的序号」落在规则的哪一段。
   *
   * <p>三个值的差别是<b>后果</b>，不是程度：
   *
   * <ul>
   *   <li>{@link #NORMAL} 正常 —— 没到告警线，照打。
   *   <li>{@link #WARNING} 告警 —— 落在「允许但要留意」区间，仍然打，但日志与任务都留告警标记。
   *   <li>{@link #BLOCKED} 超限 —— 平台上限已到，**默认拒绝**；要打必须显式 override 并填理由。
   * </ul>
   */
  public enum ReprintVerdict {
    NORMAL("正常"),
    WARNING("重复打印告警"),
    BLOCKED("超出平台重复打印上限");

    private final String label;

    ReprintVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 打印机

  @Schema(description = "打印机配置（蓝图的打印输入项）")
  public record LabelPrintDevice(
      UUID id,
      String code,
      String name,
      PrintDeviceKind deviceKind,
      String model,
      String paperSize,
      Integer dpi,
      String location,
      boolean active,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  @Schema(description = "新建打印机")
  public record LabelPrintDeviceRequest(
      String code,
      String name,
      PrintDeviceKind deviceKind,
      String model,
      String paperSize,
      Integer dpi,
      String location) {}

  // ------------------------------------------------------------ 模板

  @Schema(description = "打印模板")
  public record LabelPrintTemplate(
      UUID id,
      String code,
      String name,
      PrintObjectType objectType,
      String paperSize,
      String orientation,
      String fieldLayout,
      boolean active,
      int jobCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  @Schema(description = "新建 / 更新打印模板")
  public record LabelPrintTemplateRequest(
      String code,
      String name,
      PrintObjectType objectType,
      String paperSize,
      String orientation,
      String fieldLayout,
      Boolean active) {}

  public record LabelPrintTemplatePage(List<LabelPrintTemplate> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 打印对象（快照）

  @Schema(description = "打印对象：本地快照 + 外部引用，不与 10 物流 / 出库单建外键")
  public record LabelPrintTarget(
      UUID id,
      PrintObjectType objectType,
      String externalRef,
      String waybillNo,
      String carrierCode,
      String platformCode,
      String shipToName,
      String shipToAddress,
      String skuCode,
      String skuName,
      String locationCode,
      String cartonCode,
      Integer packageCount,
      BigDecimal weightKg,
      String remark,
      int printCount,
      OffsetDateTime firstPrintedAt,
      OffsetDateTime lastPrintedAt,
      ReprintVerdict nextVerdict,
      Integer warningCount,
      Integer maxCount,
      OffsetDateTime createdAt) {}

  @Schema(description = "登记打印对象（快照由调用方传入；10 物流未交付前这是唯一的数据来源）")
  public record LabelPrintTargetRequest(
      PrintObjectType objectType,
      String externalRef,
      String waybillNo,
      String carrierCode,
      String platformCode,
      String shipToName,
      String shipToAddress,
      String skuCode,
      String skuName,
      String locationCode,
      String cartonCode,
      Integer packageCount,
      BigDecimal weightKg,
      String remark) {}

  public record LabelPrintTargetPage(List<LabelPrintTarget> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 任务

  @Schema(description = "打印任务")
  public record LabelPrintJob(
      UUID id,
      String jobNo,
      UUID templateId,
      String templateName,
      UUID printerId,
      String printerName,
      PrintJobStatus status,
      int copies,
      int targetCount,
      int labelCount,
      int reprintWarningCount,
      int blockedCount,
      boolean overrideReprint,
      String overrideReason,
      String remark,
      String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime queuedAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  @Schema(
      description =
          "新建打印任务（批量打印：targetIds 一次给多个对象，真批量展开成 N 行、"
              + "渲染 N 份内容、写 N 条打印凭据）")
  public record LabelPrintJobRequest(
      UUID templateId,
      List<UUID> targetIds,
      Integer copies,
      UUID printerId,
      Boolean overrideReprint,
      String overrideReason,
      String remark) {}

  @Schema(description = "打印结果：本次真正落库的打印凭据与权威计数")
  public record LabelPrintExecution(
      LabelPrintJob job,
      List<LabelPrintLog> logs,
      List<LabelPrintTargetCount> counts,
      int warnedTargets,
      int blockedTargets) {}

  @Schema(description = "某对象打印后的权威计数")
  public record LabelPrintTargetCount(UUID targetId, String externalRef, int printCount) {}

  @Schema(description = "打印任务明细行（含真渲染内容）")
  public record LabelPrintJobItem(
      UUID id,
      long lineNo,
      UUID targetId,
      String externalRef,
      PrintObjectType objectType,
      int printCountAtRender,
      PrintContentFormat contentFormat,
      String contentChecksum,
      int contentBytes) {
    /** 渲染内容体积在列表里不回传（可能很大），下载端点单独取。 */
    public String contentPreview() {
      return "（" + contentFormat.label() + "，共 " + contentBytes + " 字节，按内容校验和 " + contentChecksum + " 固定）";
    }
  }

  public record LabelPrintJobDetail(LabelPrintJob job, List<LabelPrintJobItem> items) {}

  public record LabelPrintJobPage(List<LabelPrintJob> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 打印日志

  @Schema(description = "打印日志（append-only 凭据；printSequence 是该对象的第几次打印）")
  public record LabelPrintLog(
      UUID id,
      UUID jobId,
      UUID jobItemId,
      UUID targetId,
      String externalRef,
      PrintObjectType objectType,
      int printSequence,
      int copyIndex,
      PrintContentFormat contentFormat,
      String contentChecksum,
      boolean reprintWarning,
      boolean overrideApplied,
      String operatorId,
      OffsetDateTime printedAt) {}

  public record LabelPrintLogPage(List<LabelPrintLog> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 重复打印规则

  @Schema(description = "平台维度的重复打印判据（蓝图的「部分平台对重复面单有明确规则」）")
  public record LabelReprintRule(
      UUID id,
      String platformCode,
      PrintObjectType objectType,
      Integer warningCount,
      Integer maxCount,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  @Schema(description = "新建 / 更新重复打印规则")
  public record LabelReprintRuleRequest(
      String platformCode, PrintObjectType objectType, Integer warningCount, Integer maxCount, String note) {}

  // ------------------------------------------------------------ 能力声明

  @Schema(
      description =
          "本模块能力声明：五种打印对象各自的渲染形态与必填字段、重复打印判据的取值区间，"
              + "以及「面单数据来自调用方」这一事实的显式声明（前端据此渲染表单与提示）")
  public record LabelPrintCapabilities(
      List<ObjectTypeCapability> objectTypes,
      ReprintCapability reprint,
      String upstreamNote,
      List<String> limits) {}

  @Schema(description = "一种打印对象的渲染形态与必填字段")
  public record ObjectTypeCapability(
      String code,
      String label,
      String description,
      PrintContentFormat defaultFormat,
      List<String> requiredFields,
      boolean reprintGoverned) {}

  @Schema(
      description =
          "重复打印判据说明：判定用的是「下一次打印的序号 = 当前次数 + 1」，"
              + "三段区间与强制重打的口径都在这里，前端据此提示而不是自己猜")
  public record ReprintCapability(
      String verdictBasis,
      List<VerdictRange> ranges,
      String overrideRule,
      boolean countAuthoritative,
      String ruleTableNote) {}

  @Schema(description = "判定区间")
  public record VerdictRange(String verdict, String label, String condition, boolean blocked) {}
}