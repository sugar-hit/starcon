package com.starcon.reporting;

import java.util.List;
import java.util.Locale;

/**
 * 13.6.2.1 导出的**受控目录**：格式白名单、列键构造、排序与筛选运算符。
 *
 * <p>🔴 **本类与上游 13.6.1.1 的 {@link ReportingCustomReportCatalog} 是同一条纪律**：
 * 能出现在导出里的东西**全部**是这个文件里的常量或上游的白名单，
 * 客户端传的字符串一律先过白名单再落库，**绝不直接拼进文件名 / SQL / 磁盘路径**。
 *
 * <p>🔴 **列键是稳定的程序内键**（{@code DIM:SHOP} / {@code MEASURE:SALES_AMOUNT}），
 * 不是列头文字：列头文字随时可能改，而「当时导出的那份文件里第 3 列叫什么」
 * 要靠另存的 {@code field_headers} 回答（见 V410 的源快照表）。
 */
public final class ReportingExportCatalog {

  private ReportingExportCatalog() {}

  /** 列键的维度前缀。 */
  public static final String DIMENSION_PREFIX = "DIM:";

  /** 列键的度量前缀。 */
  public static final String MEASURE_PREFIX = "MEASURE:";

  /** 唯一的排序键：按维度元组升序 —— **与页面上看到的行序完全一致**。 */
  public static final String SORT_DIMENSION_TUPLE = "DIMENSION_TUPLE";

  /**
   * 可用的导出格式（**白名单**）。
   *
   * <p>⚠️ **三种格式都是真文件**：XLSX 由 Apache POI 生成、PDF 由 Apache PDFBox 生成、
   * CSV 自己写（带 UTF-8 BOM，中文 Excel 打开不乱码）。
   * **不提供「假 xlsx / 假 pdf」** —— 下载下来打不开的文件比不提供更糟。
   */
  public enum ExportFormat {
    /** 逗号分隔的文本（UTF-8 + BOM）。 */
    CSV("CSV", "CSV（UTF-8，Excel 直接打开不乱码）", ".csv", "text/csv; charset=UTF-8"),
    /** Excel 工作簿。 */
    XLSX("XLSX", "Excel 工作簿（.xlsx）", ".xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    /** PDF 文档。 */
    PDF("PDF", "PDF 文档（.pdf）", ".pdf", "application/pdf");

    private final String code;
    private final String label;
    private final String extension;
    private final String contentType;

    ExportFormat(String code, String label, String extension, String contentType) {
      this.code = code;
      this.label = label;
      this.extension = extension;
      this.contentType = contentType;
    }

    public String code() {
      return code;
    }

    public String label() {
      return label;
    }

    public String extension() {
      return extension;
    }

    public String contentType() {
      return contentType;
    }

    /** 按码取格式；取不到抛异常（**不静默回落成 CSV** —— 回落会让用户拿到另一种格式却以为是自己点的）。 */
    public static ExportFormat of(String code) {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("导出格式不能为空");
      }
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var format : values()) {
        if (format.code.equals(normalized)) return format;
      }
      throw new IllegalArgumentException(
          "导出格式只能是 CSV / XLSX / PDF（收到的是「" + code + "」）。");
    }
  }

  /** 维度列的键。 */
  public static String dimensionKey(String dimensionCode) {
    return DIMENSION_PREFIX + dimensionCode;
  }

  /** 度量列的键。 */
  public static String measureKey(String metricCode) {
    return MEASURE_PREFIX + metricCode;
  }

  /** 列键是不是维度列。 */
  public static boolean isDimensionKey(String key) {
    return key != null && key.startsWith(DIMENSION_PREFIX);
  }

  /** 列键是不是度量列。 */
  public static boolean isMeasureKey(String key) {
    return key != null && key.startsWith(MEASURE_PREFIX);
  }

  /** 从列键里取出维度码 / 指标码。 */
  public static String stripPrefix(String key) {
    if (isDimensionKey(key)) return key.substring(DIMENSION_PREFIX.length());
    if (isMeasureKey(key)) return key.substring(MEASURE_PREFIX.length());
    return key;
  }

  /** 可用的排序（**只有一种**，因为上游查询的行序就是这么定的 —— 不编造不存在的排序）。 */
  public static List<ReportingExportModels.ExportSubscriptionSortInfo> sorts() {
    return List.of(
        new ReportingExportModels.ExportSubscriptionSortInfo(
            SORT_DIMENSION_TUPLE, "按维度升序（与报表页面上看到的行序一致）"));
  }

  /** 可用的筛选运算符（**沿用上游 13.6.1.1 的白名单**，不多不少）。 */
  public static List<ReportingExportModels.ExportSubscriptionFilterOperatorInfo> filterOperators() {
    var out = new java.util.ArrayList<ReportingExportModels.ExportSubscriptionFilterOperatorInfo>();
    for (var operator : ReportingCustomReportCatalog.FilterOperator.values()) {
      out.add(
          new ReportingExportModels.ExportSubscriptionFilterOperatorInfo(operator.name(), operator.label()));
    }
    return out;
  }

  /** 能力声明里的固定说明（页面上照着显示，别自己编一句）。 */
  public static final List<String> NOTES =
      List.of(
          "🔴 **大导出走异步任务并给出有效期** —— 同步导出只允许小结果集（阈值见本响应的"
              + " syncRowThreshold）；超过阈值请发起异步任务，同步端点会明确拒绝而不是把服务拖垮。",
          "🔴 **导出的内容与报表页面上看到的完全一致**：走的是 13.6.1.1 的同一套取数逻辑"
              + "（口径唯一：度量只引用已登记的 metric_code，派生型按登记口径在服务端算）。",
          "🔴 **涉币指标不跨币种相加**：导出的每一行带一个币种列，同一维度组合的不同币种是**不同的行**；"
              + "与货币无关的量（笔数 / 件数）在每个币种行上重复出现，币种列显示空串（= 与货币无关）。",
          "🔴 **算不出来不是 0**：分母为 0 或缺数据的度量在导出里是**空单元格**（不是 0），"
              + "并在「口径备注」列写明原因。",
          "🔴 **文件本体不进业务库**：文件存在导出存储里，库里只留「在哪、多大、sha 是多少」；"
              + "过期后下载端点返回 410 Gone 并清理文件。",
          "**下载有效期是可配置的业务参数**（REPORTING_EXPORT_TTL_HOURS），在任务成功那一刻写入，"
              + "不是写死的常量。");
}
