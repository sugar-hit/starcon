package com.starcon.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 5.1.1.2 价目项的 **Excel 导入导出**：真的读出行、真的写出文件。
 *
 * <p>🔴 **不手搓「假 xlsx」**：这里用 Apache POI 生成/解析 OOXML 工作簿，
 * 产出的是能被 Excel / LibreOffice / Numbers 打开的文件。
 * 下载下来打不开的文件比不提供这个格式更糟（用户会以为导坏了）。
 *
 * <p>🔴 **导入与导出共用同一张列定义**（{@link #COLUMNS}）：
 * 导出的文件原样导回去应该得到同样的数据 —— 所以导出先写、导入按同一份表头解析。
 *
 * <p>🔴 **时间列写 ISO-8601 字符串而不是 Excel 日期单元格**：
 * xlsx 的日期单元格存的是「本地时间 + 无时区」，同一个瞬间在不同机器上会读出不同的偏移。
 * 价目项的生效区间是**跨时区对账**的事实（哪个店铺在哪个时区用哪条价），
 * 所以写带偏移的 ISO 串 —— 无歧义，且导回来逐字相等。
 *
 * <p>🔴 **金额与数量**：单元格是数值型（Excel 里可以直接求和），
 * 读回来时用 {@code BigDecimal.valueOf(double)}（它给出能往返的最短十进制表示），
 * 所以 {@code 19.99} 读回来还是 {@code 19.99}，不是 {@code 19.989999999999998}。
 */
@Component
public class PriceListItemExcelService {

  /** 列定义：表头名 → 列序（导入按表头名定位，导出按这个顺序写）。 */
  public static final List<String> COLUMNS =
      List.of("SKU编码", "SKU名称", "单价", "币种", "数量门槛", "优先级", "生效起点", "生效终点", "启用", "备注");

  /** 表头的英文别名（运营从别的系统导出来的表头未必是中文）。 */
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("sku编码", "SKU编码"),
          Map.entry("sku", "SKU编码"),
          Map.entry("skucode", "SKU编码"),
          Map.entry("sku名称", "SKU名称"),
          Map.entry("skuname", "SKU名称"),
          Map.entry("单价", "单价"),
          Map.entry("unitprice", "单价"),
          Map.entry("price", "单价"),
          Map.entry("币种", "币种"),
          Map.entry("currency", "币种"),
          Map.entry("数量门槛", "数量门槛"),
          Map.entry("minquantity", "数量门槛"),
          Map.entry("数量", "数量门槛"),
          Map.entry("优先级", "优先级"),
          Map.entry("priority", "优先级"),
          Map.entry("生效起点", "生效起点"),
          Map.entry("validfrom", "生效起点"),
          Map.entry("生效终点", "生效终点"),
          Map.entry("validto", "生效终点"),
          Map.entry("启用", "启用"),
          Map.entry("enabled", "启用"),
          Map.entry("备注", "备注"),
          Map.entry("note", "备注"));

  /** 导出文件名里的价目表编码（不含非法字符）。 */
  private static String safeName(String raw) {
    var name = raw == null ? "" : raw.trim();
    if (name.isEmpty()) return "price-list-items";
    var cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isEmpty() ? "price-list-items" : cleaned;
  }

  // ================================================================ 导出

  /**
   * 导出成 xlsx 字节。
   *
   * @param rows 价目项行（由服务层读出来，币种从价目表头带出来）
   * @param currency 价目表的币种（写进「币种」列，导入时会被校验）
   * @param priceListCode 价目表编码（进文件名）
   */
  public byte[] export(List<ExportRow> rows, String currency, String priceListCode) {
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("价目项");
      var header = sheet.createRow(0);
      for (int c = 0; c < COLUMNS.size(); c++) {
        header.createCell(c).setCellValue(COLUMNS.get(c));
      }
      var style = workbook.createCellStyle();
      style.setDataFormat(workbook.createDataFormat().getFormat("0.000000"));
      int r = 1;
      for (var row : rows) {
        var sheetRow = sheet.createRow(r++);
        var sku = sheetRow.createCell(0);
        sku.setCellValue(row.skuCode());
        var name = sheetRow.createCell(1);
        name.setCellValue(row.skuName() == null ? "" : row.skuName());
        var price = sheetRow.createCell(2);
        price.setCellValue(row.unitPrice().doubleValue());
        price.setCellStyle(style);
        var currencyCell = sheetRow.createCell(3);
        currencyCell.setCellValue(currency);
        var minQuantity = sheetRow.createCell(4);
        minQuantity.setCellValue(row.minQuantity().doubleValue());
        minQuantity.setCellStyle(style);
        var priority = sheetRow.createCell(5);
        priority.setCellValue(row.priority());
        var validFrom = sheetRow.createCell(6);
        validFrom.setCellValue(instantText(row.validFrom()));
        var validTo = sheetRow.createCell(7);
        validTo.setCellValue(instantText(row.validTo()));
        var enabled = sheetRow.createCell(8);
        enabled.setCellValue(row.enabled() ? "是" : "否");
        var note = sheetRow.createCell(9);
        note.setCellValue(row.note() == null ? "" : row.note());
      }
      sheet.setColumnWidth(0, 20 * 256);
      sheet.setColumnWidth(1, 28 * 256);
      sheet.setColumnWidth(6, 26 * 256);
      sheet.setColumnWidth(7, 26 * 256);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("导出价目项失败：" + e.getMessage(), e);
    }
  }

  /** 导出文件名。 */
  public String exportFileName(String priceListCode) {
    return safeName(priceListCode) + "-价目项-" + java.time.LocalDate.now() + ".xlsx";
  }

  // ================================================================ 导入

  /** 解析出来的一个原始行（**还没做业务校验**，只做了取值与类型转换）。 */
  public record RawRow(int rowNo, String skuCode, String skuName, String unitPrice, String currency,
      String minQuantity, String priority, String validFrom, String validTo, String enabled, String note) {}

  /** 解析结果：表头映射 + 原始行。 */
  public record ParsedSheet(Map<String, Integer> headerIndex, List<RawRow> rows, List<String> errors) {}

  /**
   * 解析 xlsx 字节（**真的读出行**）。
   *
   * <p>🔴 缺必需列是**整个文件**的问题（不是某一行的问题）⇒ 放进 {@code errors}，
   * 服务层据此整批拒绝 —— 半张表头对半张数据没法导入。
   */
  public ParsedSheet parse(byte[] bytes) {
    var errors = new ArrayList<String>();
    var headerIndex = new LinkedHashMap<String, Integer>();
    List<RawRow> rows = new ArrayList<>();
    if (bytes == null || bytes.length == 0) {
      errors.add("文件是空的");
      return new ParsedSheet(headerIndex, rows, errors);
    }
    try (var in = new ByteArrayInputStream(bytes);
        Workbook workbook = WorkbookFactory.create(in)) {
      var sheet = workbook.getSheetAt(0);
      if (sheet == null) {
        errors.add("工作簿里没有工作表");
        return new ParsedSheet(headerIndex, rows, errors);
      }
      var header = sheet.getRow(sheet.getFirstRowNum());
      if (header == null) {
        errors.add("文件没有表头行");
        return new ParsedSheet(headerIndex, rows, errors);
      }
      for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
        var cell = header.getCell(c);
        var raw = cell == null ? "" : text(cell);
        var key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) continue;
        var canonical = ALIASES.get(key);
        if (canonical != null && !headerIndex.containsKey(canonical)) {
          headerIndex.put(canonical, c);
        }
      }
      for (var required : List.of("SKU编码", "单价", "生效起点")) {
        if (!headerIndex.containsKey(required)) {
          errors.add("文件缺少必需列「" + required + "」（表头需要："
              + String.join(" / ", COLUMNS) + "）");
        }
      }
      if (!errors.isEmpty()) return new ParsedSheet(headerIndex, rows, errors);
      int rowNo = 0;
      for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
        var row = sheet.getRow(r);
        if (row == null) continue;
        rowNo++;
        // 整行都是空的 ⇒ 跳过（Excel 里末尾常有几十行空行）
        if (isBlankRow(row)) continue;
        rows.add(
            new RawRow(
                rowNo,
                cellText(row, headerIndex, "SKU编码"),
                cellText(row, headerIndex, "SKU名称"),
                cellText(row, headerIndex, "单价"),
                cellText(row, headerIndex, "币种"),
                cellText(row, headerIndex, "数量门槛"),
                cellText(row, headerIndex, "优先级"),
                cellText(row, headerIndex, "生效起点"),
                cellText(row, headerIndex, "生效终点"),
                cellText(row, headerIndex, "启用"),
                cellText(row, headerIndex, "备注")));
      }
      return new ParsedSheet(headerIndex, rows, errors);
    } catch (IOException | RuntimeException e) {
      errors.add("文件解析失败（要求 .xlsx 工作簿）：" + e.getMessage());
      return new ParsedSheet(headerIndex, rows, errors);
    }
  }

  /** 一行是否整行空白。 */
  private static boolean isBlankRow(Row row) {
    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
      var cell = row.getCell(c);
      if (cell == null || cell.getCellType() == CellType.BLANK) continue;
      if (!text(cell).trim().isEmpty()) return false;
    }
    return true;
  }

  private static String cellText(Row row, Map<String, Integer> headerIndex, String column) {
    var index = headerIndex.get(column);
    if (index == null) return "";
    var cell = row.getCell(index);
    return cell == null ? "" : text(cell);
  }

  /**
   * 单元格的文本形态。
   *
   * <p>🔴 数值单元格用 {@code BigDecimal.valueOf(double).toPlainString()} ——
   * 它给出能往返的最短十进制表示，所以 {@code 19.99} 不会变成 {@code 19.989999999999998}。
   * 日期单元格按 ISO-8601 输出（带时区偏移）。
   */
  private static String text(Cell cell) {
    var type = cell.getCellType();
    if (type == CellType.FORMULA) type = cell.getCachedFormulaResultType();
    return switch (type) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          yield cell.getLocalDateTimeCellValue().atZone(java.time.ZoneId.systemDefault())
              .toOffsetDateTime().toString();
        }
        yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
      }
      case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
      case ERROR -> "#ERR" + cell.getErrorCellValue();
      default -> "";
    };
  }

  /** 时刻 → ISO-8601 文本（空 = 空串）。 */
  private static String instantText(OffsetDateTime value) {
    return value == null ? "" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
  }

  /** 导出用的一行（服务层把视图转成它）。 */
  public record ExportRow(
      String skuCode,
      String skuName,
      BigDecimal unitPrice,
      BigDecimal minQuantity,
      int priority,
      OffsetDateTime validFrom,
      OffsetDateTime validTo,
      boolean enabled,
      String note) {}
}
