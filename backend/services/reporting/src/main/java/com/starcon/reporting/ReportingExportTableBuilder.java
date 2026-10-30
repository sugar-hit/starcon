package com.starcon.reporting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.2.1 的**表格构造器**：把上游 13.6.1.1 的报表结果铺成一张可导出的表。
 *
 * <p>⚠️ **本类单独成组件是刻意的**：{@link ReportingExportService} 与
 * {@link ReportingExportExecutor} 都要用它，而执行器又由服务层调用 ——
 * 三者放在一起会形成 Spring 的**循环依赖**。抽出本组件后依赖是单向的
 * （服务层 → 执行器 → 本组件），没有环。
 *
 * <p>🔴 **口径唯一**：本类**不写任何聚合 SQL**，取数全部走
 * {@link ReportingCustomReportService#run} ⇒ 导出的内容与用户在报表页面上看到的
 * **逐格一致**。这是「口径唯一」在导出侧的唯一实现方式 —— 不是「再写一遍口径」。
 */
@Component
public class ReportingExportTableBuilder {

  /** 单页行数（与上游报表服务一致）。 */
  static final int PAGE_SIZE = 500;

  private final ReportingCustomReportService reports;

  public ReportingExportTableBuilder(ReportingCustomReportService reports) {
    this.reports = reports;
  }

  /** 一个可导出的列。 */
  public record Column(String key, String label, String kind, String note) {}

  /** 构造出来的一张表（带口径说明）。 */
  public record BuiltTable(ReportingExportRenderer.Table table, List<String> notes) {}

  // ==========================================================================
  // 列解析
  // ==========================================================================

  /** 这张报表的全部可导出列（**白名单**：只能是它自己的维度与度量）。 */
  public List<Column> resolveColumns(ReportingCustomReportModels.CustomReportView view) {
    var labels = dimensionLabels();
    var columns = new ArrayList<Column>();
    for (var dimensionCode : view.dimensions()) {
      columns.add(
          new Column(
              ReportingExportCatalog.dimensionKey(dimensionCode),
              labels.getOrDefault(dimensionCode, dimensionCode),
              "DIMENSION",
              "维度取值（法人 / 店铺给的是 uuid 文本 —— 语义层不带名称档案，不编名字）。"));
    }
    for (var measure : view.measures()) {
      columns.add(
          new Column(
              ReportingExportCatalog.measureKey(measure.metricCode()),
              measure.displayName() + unitSuffix(measure.unit()),
              "MEASURE",
              measure.description()));
    }
    return columns;
  }

  /** 校验并定型选中的列（空 = 全部列；未知列 ⇒ 400，不静默丢弃）。 */
  public List<Column> resolveSelection(List<String> fields, List<Column> available) {
    if (fields == null || fields.isEmpty()) return available;
    var byKey = new LinkedHashMap<String, Column>();
    for (var column : available) byKey.put(column.key(), column);
    var out = new ArrayList<Column>();
    for (var key : fields) {
      if (key == null || key.isBlank()) continue;
      var normalized = key.trim().toUpperCase(Locale.ROOT);
      var column = byKey.get(normalized);
      if (column == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "列「"
                + key
                + "」不属于这份报表的可导出列（可导出列只有："
                + String.join("、", byKey.keySet())
                + "）。");
      }
      if (!out.contains(column)) out.add(column);
    }
    if (out.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要选一列（导出不能没有列）");
    }
    return out;
  }

  /** 维度码 → 中文标签。 */
  public Map<String, String> dimensionLabels() {
    var labels = new LinkedHashMap<String, String>();
    for (var dimension : reports.dimensionCatalog()) {
      labels.put(dimension.dimensionCode(), dimension.label());
    }
    return labels;
  }

  /** 单位后缀（列头里写清这一列是什么量纲）。 */
  public static String unitSuffix(String unit) {
    if (unit == null) return "";
    return switch (unit) {
      case "PERCENT" -> "（%）";
      case "CURRENCY" -> "（金额）";
      case "QUANTITY" -> "（数量）";
      default -> "";
    };
  }

  // ==========================================================================
  // 表格构造
  // ==========================================================================

  /**
   * 跑报表并铺成一张表。
   *
   * @param rowLimit 行数上限（超过 ⇒ 同步端点回 409 / 异步任务失败并写明原因，**不静默截断**）
   * @param failWhenTooLarge true = 同步端点（抛 409）；false = 异步任务（抛异常 ⇒ 任务失败）
   */
  public BuiltTable build(
      UUID tenantId,
      UUID actorUserId,
      ReportingCustomReportModels.CustomReportView view,
      List<Column> selected,
      LocalDate from,
      LocalDate to,
      int rowLimit,
      boolean failWhenTooLarge) {
    var reportId = UUID.fromString(view.reportId());
    var dimensionCodes = view.dimensions();
    var labels = dimensionLabels();
    var headers = new ArrayList<String>();
    for (var dimensionCode : dimensionCodes) {
      headers.add(labels.getOrDefault(dimensionCode, dimensionCode));
    }
    headers.add("币种");
    for (var measure : view.measures()) {
      headers.add(measure.displayName() + unitSuffix(measure.unit()));
    }
    headers.add("口径备注");

    var rows = new ArrayList<List<ReportingExportRenderer.Cell>>();
    var collected = 0;
    var groupsCollected = 0;
    var page = 0;
    long totalGroups = -1;
    while (true) {
      var response = reports.run(tenantId, reportId, actorUserId, from, to, page, PAGE_SIZE);
      if (totalGroups < 0) totalGroups = response.total();
      if (response.rows().isEmpty()) break;
      for (var row : response.rows()) {
        groupsCollected++;
        for (var currencyRow : expandCurrencies(row)) {
          rows.add(currencyRow);
          collected++;
          if (collected > rowLimit) {
            if (failWhenTooLarge) {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT,
                  "结果集超过同步导出的行数上限（"
                      + rowLimit
                      + " 行）—— 同步导出大文件会拖垮服务，请改用异步导出任务"
                      + "（POST /reporting/custom-report/export/jobs）。");
            }
            throw new IllegalStateException(
                "导出结果集超过行数上限（" + rowLimit + " 行），请缩小时间窗口或加筛选条件。");
          }
        }
      }
      // ⚠️ 比较的是**分组数**而不是铺开后的行数：一个分组会按币种铺成多行，
      // 用铺开后的行数比较会提前收尾 ⇒ **静默少导数据**。
      if (groupsCollected >= totalGroups || response.rows().size() < PAGE_SIZE) break;
      page++;
    }

    var table = new ReportingExportRenderer.Table(headers, rows);
    return new BuiltTable(table, responseNotes(view));
  }

  /**
   * 一个维度组合铺成若干行（**每个币种一行**）。
   *
   * <p>⚠️ 涉币度量的取值按币种分开；某度量在这一行没有该币种的取值 ⇒ **空单元格**（不是 0）。
   * 与货币无关的量（币种为空串）在每个币种行上重复出现。
   */
  private List<List<ReportingExportRenderer.Cell>> expandCurrencies(
      ReportingCustomReportModels.CustomReportRow row) {
    var currencies = new LinkedHashSet<String>();
    for (var cell : row.measures()) {
      for (var value : cell.values()) {
        if (!value.currency().isEmpty()) currencies.add(value.currency());
      }
    }
    if (currencies.isEmpty()) currencies.add("");

    var dimensionTexts = new ArrayList<String>();
    for (var dimension : row.dimensions()) {
      dimensionTexts.add(dimension.value());
    }

    var out = new ArrayList<List<ReportingExportRenderer.Cell>>();
    for (var currency : currencies) {
      var cells = new ArrayList<ReportingExportRenderer.Cell>();
      for (var text : dimensionTexts) {
        cells.add(ReportingExportRenderer.Cell.text(text));
      }
      cells.add(ReportingExportRenderer.Cell.text(currency));
      var notes = new ArrayList<String>();
      for (var measure : row.measures()) {
        var hit = findValue(measure, currency);
        if (hit == null) {
          cells.add(ReportingExportRenderer.Cell.text(""));
          continue;
        }
        if (hit.value() == null) {
          // 🔴 算不出来（分母为 0 / 缺数据）⇒ 空单元格 + 写明原因，**不是 0**。
          cells.add(ReportingExportRenderer.Cell.text(""));
          var reason = measure.availabilityNote();
          notes.add(
              measure.displayName()
                  + "："
                  + (reason == null || reason.isEmpty() ? "算不出来（不是 0）" : reason));
          continue;
        }
        cells.add(
            ReportingExportRenderer.Cell.number(hit.value(), hit.value().toPlainString()));
      }
      cells.add(ReportingExportRenderer.Cell.text(String.join("；", notes)));
      out.add(cells);
    }
    return out;
  }

  /**
   * 找某个度量在指定币种下的取值。
   *
   * <p>⚠️ **与货币无关的量（币种为空串）在每个币种行上重复出现** —— 它的取值不在
   * 「USD / EUR」里，而在「空币种」那一条上。找不到精确匹配时回退到它，
   * 否则笔数 / 件数会在多币种的行上变成空单元格（数据少了一列，而不是少了一个数）。
   *
   * <p>涉币度量找不到这个币种 ⇒ **确实没有这个币种的取值** ⇒ 返回 null（空单元格，不是 0）。
   */
  private ReportingCustomReportModels.CustomReportCurrencyValue findValue(
      ReportingCustomReportModels.CustomReportMeasureCell measure, String currency) {
    for (var value : measure.values()) {
      if (value.currency().equals(currency)) return value;
    }
    if (measure.currencyAware()) return null;
    for (var value : measure.values()) {
      if (value.currency().isEmpty()) return value;
    }
    return null;
  }

  /** 导出口径说明（**与页面上看到的口径一致**，不另编一句）。 */
  public List<String> responseNotes(ReportingCustomReportModels.CustomReportView view) {
    var notes = new ArrayList<String>();
    notes.add(
        "口径全部取自 reporting_metric_definition（13.1.1.1 经营总览的**口径唯一表**）—— "
            + "导出不复制一份，也不自己写公式。");
    notes.add(
        "🔴 **涉币指标按币种分别报**（绝不跨币种相加）；与货币无关的量（笔数 / 件数）"
            + "在每个币种行上重复出现，币种列显示空串。");
    notes.add("🔴 **算不出来不是 0**：分母为 0 或缺数据的度量是空单元格，原因写在「口径备注」列。");
    notes.add(
        "导出的时间窗口："
            + (view.windowFrom() == null ? "报表默认" : view.windowFrom())
            + " ~ "
            + (view.windowTo() == null ? "报表默认" : view.windowTo())
            + "。");
    return notes;
  }
}
