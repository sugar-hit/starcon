package com.starcon.reporting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 13.6.2.1 的**文件渲染器**：把报表结果渲染成 CSV / XLSX / PDF 的**真实字节**。
 *
 * <p>🔴 **三种格式都是真文件**：
 *
 * <ul>
 *   <li><b>CSV</b>：自己写，带 UTF-8 BOM（中文 Excel 打开不乱码）与 RFC 4180 引号转义；</li>
 *   <li><b>XLSX</b>：Apache POI 生成的 OOXML 工作簿（能被 Excel / LibreOffice / Numbers 打开）；</li>
 *   <li><b>PDF</b>：Apache PDFBox 生成的 PDF 文档（能被任意阅读器打开）。</li>
 * </ul>
 *
 * <p>⚠️ **不手搓「假 xlsx / 假 pdf」**：那类文件下载下来打不开，
 * 比「不提供这个格式」更糟（用户会以为导坏了）。所以这里用的是两个真实实现库。
 *
 * <p>🔴 **金额与数量**：计算与取值全程 {@link BigDecimal}（服务端口径也是 BigDecimal + NUMERIC）。
 * 写 xlsx 数字单元格时会在**最后一步**转成 double —— 那是**文件格式本身**的限制
 * （xlsx 的数字在 XML 里就是 IEEE-754 双精度，Excel 自己也是如此），
 * **不参与任何计算**；需要精确十进制文本的场景用 CSV（那里写的是 {@code toPlainString()}）。
 *
 * <p>🔴 **PDF 的中文字体**：PDF 的标准 14 字体（Helvetica 等）只有 WinAnsi 编码，
 * 写中文会直接抛异常。本类内嵌一份 **Noto Sans SC 的 GB2312 子集**（SIL OFL 1.1，
 * 见 {@code resources/fonts/OFL.txt}），保证导出的 PDF 里中文能正常显示。
 * 生产可用 {@code REPORTING_PDF_FONT_PATH} 指向另一份字体替换它。
 */
@Component
public class ReportingExportRenderer {
  private static final Logger log = LoggerFactory.getLogger(ReportingExportRenderer.class);

  /** 内嵌中文字体（GB2312 子集，OFL 1.1）。 */
  static final String CLASSPATH_FONT = "/fonts/NotoSansSC-Reporting.ttf";

  /** 字体里没有的字符在 PDF 里替换成它（并记一条警告）。 */
  private static final char MISSING_GLYPH = '?';

  /** PDF 的页边距（点）。 */
  private static final float PDF_MARGIN = 36f;

  /** PDF 正文字号。 */
  private static final float PDF_BODY_SIZE = 8.5f;

  /** PDF 标题字号。 */
  private static final float PDF_TITLE_SIZE = 12f;

  /** PDF 一列的最小宽度（点）。 */
  private static final float PDF_MIN_COL_WIDTH = 48f;

  /** 表格里的一格。 */
  public record Cell(String text, BigDecimal number) {
    /** 文本格（空文本就是空单元格 —— **不是 0**）。 */
    public static Cell text(String value) {
      return new Cell(value == null ? "" : value, null);
    }

    /** 数字格（{@code number} 为空 ⇒ 退化成文本格）。 */
    public static Cell number(BigDecimal value, String display) {
      if (value == null) return new Cell(display == null ? "" : display, null);
      return new Cell(display == null ? value.toPlainString() : display, value);
    }

    /** 这一格是不是数字格。 */
    public boolean numeric() {
      return number != null;
    }
  }

  /** 一张表（表头 + 数据行）。 */
  public record Table(List<String> headers, List<List<Cell>> rows) {}

  /** 渲染结果。 */
  public record Rendered(byte[] content, List<String> warnings) {}

  private final String fontPath;

  public ReportingExportRenderer(
      @Value("${REPORTING_PDF_FONT_PATH:}") String fontPath) {
    this.fontPath = fontPath == null ? "" : fontPath.trim();
  }

  // ==========================================================================
  // 入口
  // ==========================================================================

  /** 按格式渲染一张表。 */
  public Rendered render(
      ReportingExportCatalog.ExportFormat format,
      Table table,
      String title,
      List<String> notes) {
    return switch (format) {
      case CSV -> new Rendered(renderCsv(table), List.of());
      case XLSX -> new Rendered(renderXlsx(table), List.of());
      case PDF -> renderPdf(table, title, notes);
    };
  }

  // ==========================================================================
  // CSV
  // ==========================================================================

  /**
   * CSV：UTF-8 + BOM + RFC 4180 引号转义。
   *
   * <p>🔴 **BOM 是必须的**：没有 BOM 的话中文 Excel 会按本地编码（GBK）猜，
   * 于是中文表头变成乱码 —— 而用户看到的正是一份「导坏了」的表。
   */
  byte[] renderCsv(Table table) {
    var buffer = new ByteArrayOutputStream();
    // 必须按 UTF-8 **字节**写：写 U+FEFF 的整数只会得到一个 0xFF 字节而不是三字节 BOM。
    buffer.writeBytes("﻿".getBytes(StandardCharsets.UTF_8));
    buffer.writeBytes(csvRow(table.headers()).getBytes(StandardCharsets.UTF_8));
    buffer.write('\r');
    buffer.write('\n');
    for (var row : table.rows()) {
      var cells = new ArrayList<String>(row.size());
      for (var cell : row) cells.add(cell.text());
      buffer.writeBytes(csvRow(cells).getBytes(StandardCharsets.UTF_8));
      buffer.write('\r');
      buffer.write('\n');
    }
    return buffer.toByteArray();
  }

  /** 一行 CSV（含引号转义）。 */
  private String csvRow(List<String> values) {
    var out = new StringBuilder();
    for (var index = 0; index < values.size(); index++) {
      if (index > 0) out.append(',');
      out.append(csvCell(values.get(index)));
    }
    return out.toString();
  }

  /** 一个 CSV 单元格（RFC 4180：含逗号 / 引号 / 换行时加引号，内部引号翻倍）。 */
  static String csvCell(String value) {
    var text = value == null ? "" : value;
    var needsQuote =
        text.indexOf(',') >= 0
            || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
    if (!needsQuote) return text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  // ==========================================================================
  // XLSX
  // ==========================================================================

  /**
   * XLSX：Apache POI 生成的真 OOXML 工作簿。
   *
   * <p>⚠️ **数字格写的是数值（不是文本）**：用户拿到这份表的第一件事就是求和与排序，
   * 全是文本的表在 Excel 里没法直接用。文本格（维度值、备注）才是文本。
   */
  byte[] renderXlsx(Table table) {
    try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("报表");

      var headerStyle = workbook.createCellStyle();
      var headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);
      headerStyle.setAlignment(HorizontalAlignment.CENTER);

      var header = sheet.createRow(0);
      for (var index = 0; index < table.headers().size(); index++) {
        var cell = header.createCell(index);
        cell.setCellValue(table.headers().get(index));
        cell.setCellStyle(headerStyle);
      }

      var rowIndex = 1;
      for (var row : table.rows()) {
        var sheetRow = sheet.createRow(rowIndex++);
        for (var index = 0; index < row.size(); index++) {
          var value = row.get(index);
          var cell = sheetRow.createCell(index);
          if (value.numeric()) {
            cell.setCellValue(value.number().doubleValue());
          } else {
            cell.setCellValue(value.text());
          }
        }
      }

      // 冻结表头：表大了以后滚动仍然看得见列名。
      sheet.createFreezePane(0, 1);
      for (var index = 0; index < table.headers().size(); index++) {
        sheet.setColumnWidth(index, columnWidth(table, index));
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("生成 Excel 工作簿失败", e);
    }
  }

  /** 一列的宽度（Excel 单位：1/256 个字符宽）。 */
  private int columnWidth(Table table, int index) {
    var width = 8;
    if (index < table.headers().size()) {
      width = Math.max(width, table.headers().get(index).length() * 2);
    }
    var sample = Math.min(table.rows().size(), 50);
    for (var i = 0; i < sample; i++) {
      var row = table.rows().get(i);
      if (index < row.size()) {
        width = Math.max(width, Math.min(40, row.get(index).text().length() + 2));
      }
    }
    return Math.min(width * 256, 255 * 256);
  }

  // ==========================================================================
  // PDF
  // ==========================================================================

  /**
   * PDF：Apache PDFBox 生成的真 PDF 文档（横向 A4）。
   *
   * <p>🔴 **中文字体必须内嵌**：PDF 的标准 14 字体是 WinAnsi 编码，写中文会抛异常。
   * 这里内嵌 Noto Sans SC 的 GB2312 子集；字体里没有的字符替换成 {@code ?} 并记一条警告
   * （**不静默丢字**：页面上会看到警告，知道有字被替换了）。
   */
  Rendered renderPdf(Table table, String title, List<String> notes) {
    var warnings = new ArrayList<String>();
    try (var document = new PDDocument()) {
      var fontBytes = loadFontBytes();
      var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
      var covered = fontCoverage(fontBytes);

      var landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
      var page = new PDPage(landscape);
      document.addPage(page);

      var usableWidth = landscape.getWidth() - 2 * PDF_MARGIN;
      var widths = pdfColumnWidths(table, font, usableWidth);

      var y = landscape.getHeight() - PDF_MARGIN;
      // ⚠️ 内容流要跨页重建 ⇒ 不能用 try-with-resources（资源变量必须 effectively final），
      // 改成显式 close（finally 里兜底）。
      var content = new PDPageContentStream(document, page);
      try {
        // 标题
        content.beginText();
        content.setFont(font, PDF_TITLE_SIZE);
        content.newLineAtOffset(PDF_MARGIN, y);
        content.showText(sanitize(title == null ? "报表导出" : title, covered, warnings));
        content.endText();
        y -= PDF_TITLE_SIZE + 8;

        // 口径说明（每行一条，截断到页面宽度）
        for (var note : notes == null ? List.<String>of() : notes) {
          y = pdfLine(content, font, note, PDF_MARGIN, y, usableWidth, PDF_BODY_SIZE - 1.5f, covered, warnings);
        }
        y -= 4;

        // 表头
        var header = table.headers();
        y = pdfRow(content, font, header, widths, PDF_MARGIN, y, PDF_BODY_SIZE, covered, warnings);

        // 数据行
        for (var row : table.rows()) {
          if (y < PDF_MARGIN + 20) {
            content.close();
            page = new PDPage(landscape);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = landscape.getHeight() - PDF_MARGIN;
            y = pdfRow(content, font, header, widths, PDF_MARGIN, y, PDF_BODY_SIZE, covered, warnings);
          }
          y = pdfRow(content, font, texts(row), widths, PDF_MARGIN, y, PDF_BODY_SIZE, covered, warnings);
        }
      } finally {
        content.close();
      }
      var output = new ByteArrayOutputStream();
      document.save(output);
      return new Rendered(output.toByteArray(), warnings);
    } catch (IOException e) {
      throw new IllegalStateException("生成 PDF 失败", e);
    }
  }

  /** 写一行（返回下一行的 y）。 */
  private float pdfRow(
      PDPageContentStream content,
      PDType0Font font,
      List<String> values,
      float[] widths,
      float x0,
      float y,
      float size,
      java.util.Set<Integer> covered,
      List<String> warnings)
      throws IOException {
    content.beginText();
    content.setFont(font, size);
    var x = x0;
    for (var index = 0; index < values.size(); index++) {
      var width = index < widths.length ? widths[index] : widths[widths.length - 1];
      // ⚠️ **先 sanitize 再 truncate**：truncate 里的 getStringWidth 遇到字体没有的字形
      // 会直接抛 IllegalArgumentException（"No glyph for U+XXXX"）⇒ 必须先替掉。
      var text = truncate(font, sanitize(values.get(index), covered, warnings), width - 4, size);
      content.newLineAtOffset(x, y);
      content.showText(text);
      x += width;
    }
    content.endText();
    return y - size - 4;
  }

  /** 写一行说明文字。 */
  private float pdfLine(
      PDPageContentStream content,
      PDType0Font font,
      String value,
      float x,
      float y,
      float usableWidth,
      float size,
      java.util.Set<Integer> covered,
      List<String> warnings)
      throws IOException {
    content.beginText();
    content.setFont(font, size);
    // ⚠️ **先 sanitize 再 truncate**（理由同上）。
    content.newLineAtOffset(x, y);
    content.showText(truncate(font, sanitize(value, covered, warnings), usableWidth, size));
    content.endText();
    return y - size - 3;
  }

  /** 截断到给定宽度（放不下就在末尾加省略号）。 */
  static String truncate(PDType0Font font, String value, float maxWidth, float size)
      throws IOException {
    if (value == null || value.isEmpty()) return "";
    if (font.getStringWidth(value) / 1000f * size <= maxWidth) return value;
    var cut = value;
    while (cut.length() > 1 && font.getStringWidth(cut + "…") / 1000f * size > maxWidth) {
      cut = cut.substring(0, cut.length() - 1);
    }
    return cut + "…";
  }

  /** 各列宽度（按内容宽度按比例分配，最小 {@link #PDF_MIN_COL_WIDTH}）。 */
  private float[] pdfColumnWidths(Table table, PDType0Font font, float usableWidth)
      throws IOException {
    var count = Math.max(1, table.headers().size());
    var widths = new float[count];
    var total = 0f;
    for (var index = 0; index < count; index++) {
      var max = font.getStringWidth(table.headers().get(index)) / 1000f * PDF_BODY_SIZE + 8;
      var sample = Math.min(table.rows().size(), 40);
      for (var i = 0; i < sample; i++) {
        var row = table.rows().get(i);
        if (index < row.size()) {
          max = Math.max(max, font.getStringWidth(row.get(index).text()) / 1000f * PDF_BODY_SIZE + 6);
        }
      }
      widths[index] = Math.max(PDF_MIN_COL_WIDTH, Math.min(max, 220f));
      total += widths[index];
    }
    if (total <= usableWidth) {
      // 还有富余：按比例摊回去（避免表格左边挤一块右边一大片空白）。
      var factor = usableWidth / total;
      for (var index = 0; index < count; index++) widths[index] *= factor;
      return widths;
    }
    var factor = usableWidth / total;
    for (var index = 0; index < count; index++) widths[index] *= factor;
    return widths;
  }

  /** 一行数据的文本形式。 */
  private static List<String> texts(List<Cell> row) {
    var out = new ArrayList<String>(row.size());
    for (var cell : row) out.add(cell.text());
    return out;
  }

  // ==========================================================================
  // 字体
  // ==========================================================================

  /** 读取字体字节（配置的路径优先，否则用内嵌的 GB2312 子集）。 */
  private byte[] loadFontBytes() throws IOException {
    if (!fontPath.isEmpty()) {
      var path = Path.of(fontPath);
      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("REPORTING_PDF_FONT_PATH 指向的文件不存在：" + fontPath);
      }
      return Files.readAllBytes(path);
    }
    try (InputStream stream = ReportingExportRenderer.class.getResourceAsStream(CLASSPATH_FONT)) {
      if (stream == null) {
        throw new IllegalStateException("内嵌 PDF 中文字体缺失：" + CLASSPATH_FONT);
      }
      return stream.readAllBytes();
    }
  }

  /** 字体覆盖的码位集合（用来判断某个字能不能画出来）。 */
  private java.util.Set<Integer> fontCoverage(byte[] fontBytes) {
    var covered = new java.util.HashSet<Integer>();
    try (var ttf = new TTFParser().parse(new ByteArrayInputStream(fontBytes))) {
      var cmap = ttf.getUnicodeCmap();
      for (var code = 0x20; code <= 0xFFFF; code++) {
        if (cmap.getGlyphId(code) != 0) covered.add(code);
      }
    } catch (IOException | RuntimeException e) {
      // ⚠️ 覆盖范围读不出来时**不能装作全都能画**（那会在 showText 时抛异常）⇒
      // 退回「只信 ASCII」，非 ASCII 全部替换成「?」并给出警告。
      log.warn("读取 PDF 字体覆盖范围失败，非 ASCII 字符将按未知字符处理", e);
      for (var code = 0x20; code < 0x80; code++) covered.add(code);
      return covered;
    }
    return covered;
  }

  /** 把字体画不出来的字替换成 {@code ?}，并记一条警告（**不静默丢字**）。 */
  private String sanitize(String value, java.util.Set<Integer> covered, List<String> warnings) {
    if (value == null || value.isEmpty()) return "";
    var out = new StringBuilder(value.length());
    var replaced = 0;
    var codePoints = value.codePoints().toArray();
    for (var codePoint : codePoints) {
      if (covered.isEmpty() || covered.contains(codePoint)) {
        out.appendCodePoint(codePoint);
      } else {
        out.append(MISSING_GLYPH);
        replaced++;
      }
    }
    if (replaced > 0) {
      warnings.add(
          "PDF 字体里没有 " + replaced + " 个字符（已替换成「" + MISSING_GLYPH + "」）；"
              + "需要完整字形请配置 REPORTING_PDF_FONT_PATH。");
    }
    return out.toString();
  }
}
