package com.starcon.finance;

import static com.starcon.finance.ReceiptModels.ImportFormat;
import static com.starcon.finance.ReceiptModels.PayerType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 到账流水导入的解析器（CSV / JSON）。
 *
 * <p>蓝图 12.2.1.1 的输入写死「**到账流水导入**或手工登记」—— 所以这条路径必须真的能落地：
 * 解析 → 校验 → 真实入库 + 幂等，而不是只做一个手工登记的壳。
 *
 * <p><b>两级错误分层</b>（这一点决定了导入结果是否诚实）：
 *
 * <ul>
 *   <li><b>文件级错误</b>（CSV 没有必需的列、JSON 根本不是数组、CSV 引号没闭合）⇒
 *       整个请求失败（400），<b>一条都不导入</b>。半个文件入库比完全失败更危险。
 *   <li><b>行级错误</b>（某一行金额不是数字、币种不是三个字母、到账时间没带时区）⇒
 *       <b>只跳过那一行</b>，并把「第几行 + 为什么」写进导入结果的 warnings。
 *       吞掉它的话，核对员看到「导入 0 条」会以为文件是空的。
 * </ul>
 *
 * <p><b>列名（CSV 表头 / JSON 键）</b>：{@code source_reference}、{@code amount}、{@code currency}、
 * {@code received_at} 是**必需**的；{@code payer_name}、{@code payer_type}、{@code fund_account_label}、
 * {@code note} 可选。表头大小写不敏感，{@code -} 与空格统一成 {@code _}。
 *
 * <p><b>到账时间必须带时区偏移</b>：这是本模块最容易错账的一格 ——
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（东八区差 8 小时）。
 * 没有偏移的时间串一律拒收，并说清为什么。
 *
 * <p>解析器是**一次性**的（每次导入新建一个实例），所以行级警告收集在实例字段上 ——
 * 刻意**不是** static：static 会让两次导入互相污染。
 */
final class ReceiptImportParser {

  /** 金额小数位（与 DB 的 numeric(19,4) 一致）。 */
  private static final int MONEY_SCALE = 4;

  static final List<String> REQUIRED_COLUMNS =
      List.of("source_reference", "amount", "currency", "received_at");

  /** 一行到账流水（已通过行级校验）。 */
  record ImportRow(
      int lineNo,
      String sourceReference,
      BigDecimal amount,
      String currency,
      OffsetDateTime receivedAt,
      String payerName,
      PayerType payerType,
      String fundAccountLabel,
      String note) {}

  /**
   * 解析结果：能用的行 + 逐条说明（为什么没用上）。
   *
   * @param scanned 文件里一共读到多少条（**包含**被跳过的）——
   *     导入结果的不变量是 {@code scanned = imported + skipped}。
   */
  record ParseResult(int scanned, List<ImportRow> rows, List<String> warnings) {}

  private final ObjectMapper json;

  /** 行级警告（本次解析累积）。 */
  private final List<String> rejections = new ArrayList<>();

  ReceiptImportParser(ObjectMapper json) {
    this.json = json;
  }

  ParseResult parse(ImportFormat format, String content) {
    // 防御：同一个解析器被复用时，上一次的行级警告不许漏进这一次的结果。
    rejections.clear();
    if (content == null || content.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入内容为空：没有可解析的到账流水");
    }
    ParseResult result = format == ImportFormat.CSV ? parseCsv(content) : parseJson(content);
    var warnings = new ArrayList<>(rejections);
    warnings.addAll(result.warnings());
    return new ParseResult(result.scanned(), result.rows(), warnings);
  }

  // ================================================================ CSV

  private ParseResult parseCsv(String content) {
    var table = readCsv(content);
    if (table.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 内容为空：没有表头，也没有数据行");
    }
    var header = table.get(0).stream().map(ReceiptImportParser::normalizeHeader).toList();
    var missing = new ArrayList<String>();
    for (String column : REQUIRED_COLUMNS) {
      if (!header.contains(column)) missing.add(column);
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "CSV 缺少必需的列："
              + String.join("、", missing)
              + "。必需的列是 "
              + String.join("、", REQUIRED_COLUMNS));
    }

    var rows = new ArrayList<ImportRow>();
    var warnings = new ArrayList<String>();
    var seen = new LinkedHashSet<String>();
    int scanned = 0;
    for (int r = 1; r < table.size(); r++) {
      var cells = table.get(r);
      if (cells.stream().allMatch(c -> c.isBlank())) continue;
      scanned++;
      var row = parseCells(cells, header, r + 1);
      if (row == null) continue;
      if (!seen.add(row.sourceReference())) {
        warnings.add(
            "第 "
                + row.lineNo()
                + " 行的来源流水号 "
                + row.sourceReference()
                + " 在本批次内重复，已跳过（幂等键必须唯一）");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  /**
   * CSV 读取：按 RFC 4180 处理引号（引号内的逗号与换行是字段的一部分）。
   *
   * <p>⚠️ 银行 / 支付平台导出的流水备注里带逗号是常态，所以「按逗号 split」会把金额列冲掉。
   */
  private static List<List<String>> readCsv(String content) {
    var rows = new ArrayList<List<String>>();
    var current = new ArrayList<String>();
    var field = new StringBuilder();
    boolean inQuotes = false;
    int i = 0;
    int n = content.length();
    while (i < n) {
      char c = content.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < n && content.charAt(i + 1) == '"') {
            field.append('"');
            i += 2;
            continue;
          }
          inQuotes = false;
          i++;
          continue;
        }
        field.append(c);
        i++;
        continue;
      }
      if (c == '"') {
        inQuotes = true;
        i++;
        continue;
      }
      if (c == ',') {
        current.add(field.toString());
        field.setLength(0);
        i++;
        continue;
      }
      if (c == '\r') {
        i++;
        continue;
      }
      if (c == '\n') {
        current.add(field.toString());
        field.setLength(0);
        rows.add(current);
        current = new ArrayList<>();
        i++;
        continue;
      }
      field.append(c);
      i++;
    }
    if (inQuotes) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 里有没闭合的引号：整个文件不可解析");
    }
    if (field.length() > 0 || !current.isEmpty()) {
      current.add(field.toString());
      rows.add(current);
    }
    // Excel 导出常见的 UTF-8 BOM：它会粘在第一个表头名上，让第一列永远匹配不上。
    if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
      var first = rows.get(0);
      first.set(0, first.get(0).replace("\uFEFF", ""));
    }
    return rows;
  }

  private static String normalizeHeader(String raw) {
    return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }

  /** 校验并解析一行；不合法时返回 null 并把原因写进 rejections。 */
  private ImportRow parseCells(List<String> cells, List<String> header, int lineNo) {
    String sourceReference = null;
    String amountText = null;
    String currency = null;
    String receivedAtText = null;
    String payerName = null;
    String payerTypeText = null;
    String fundAccountLabel = null;
    String note = null;

    for (int c = 0; c < header.size() && c < cells.size(); c++) {
      String value = cells.get(c);
      switch (header.get(c)) {
        case "source_reference" -> sourceReference = value.trim();
        case "amount" -> amountText = value.trim();
        case "currency" -> currency = value.trim();
        case "received_at" -> receivedAtText = value.trim();
        case "payer_name" -> payerName = value.trim();
        case "payer_type" -> payerTypeText = value.trim();
        case "fund_account_label" -> fundAccountLabel = value.trim();
        case "note" -> note = value.trim();
        default -> {
          // 未登记的列**刻意忽略**：银行流水文件常有几十列，多一列不该让整次导入失败。
        }
      }
    }
    if (sourceReference == null || sourceReference.isBlank()) {
      return reject(lineNo, "缺少来源流水号（幂等键必须可追溯）");
    }
    BigDecimal amount = parseAmount(lineNo, amountText);
    if (amount == null) return null;
    if (currency == null || !currency.matches("^[A-Za-z]{3}$")) {
      return reject(lineNo, "币种必须是三个字母（收到 \"" + currency + "\"）");
    }
    OffsetDateTime receivedAt = parseReceivedAt(lineNo, receivedAtText);
    if (receivedAt == null) return null;
    PayerType payerType = PayerType.CUSTOMER;
    if (payerTypeText != null && !payerTypeText.isBlank()) {
      try {
        payerType = PayerType.valueOf(payerTypeText.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return reject(lineNo, "付款方类型不合法（收到 \"" + payerTypeText + "\"）");
      }
    }
    return new ImportRow(
        lineNo,
        sourceReference,
        amount,
        currency.toUpperCase(Locale.ROOT),
        receivedAt,
        payerName == null || payerName.isBlank() ? null : payerName,
        payerType,
        fundAccountLabel == null || fundAccountLabel.isBlank() ? null : fundAccountLabel,
        note == null || note.isBlank() ? null : note);
  }

  // ================================================================ JSON

  private ParseResult parseJson(String content) {
    JsonNode root;
    try {
      root = json.readTree(content);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 解析失败：内容不是合法的 JSON");
    }
    if (root == null || root.isNull()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 内容为空");
    }
    JsonNode array = root;
    if (root.isObject()) {
      array = root.get("rows");
      if (array == null || array.isNull()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "JSON 顶层是对象时必须有 rows 数组（每行一个到账流水）");
      }
    }
    if (!array.isArray()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "JSON 顶层必须是数组（或含 rows 数组的对象）：每行一个到账流水");
    }

    var rows = new ArrayList<ImportRow>();
    var warnings = new ArrayList<String>();
    var seen = new LinkedHashSet<String>();
    int lineNo = 0;
    int scanned = 0;
    for (var node : array) {
      lineNo++;
      scanned++;
      if (!node.isObject()) {
        warnings.add("第 " + lineNo + " 条不是一个对象，已跳过");
        continue;
      }
      var row = parseJsonRow(node, lineNo);
      if (row == null) continue;
      if (!seen.add(row.sourceReference())) {
        warnings.add(
            "第 "
                + row.lineNo()
                + " 条的来源流水号 "
                + row.sourceReference()
                + " 在本批次内重复，已跳过（幂等键必须唯一）");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  private ImportRow parseJsonRow(JsonNode node, int lineNo) {
    String sourceReference = text(node, "source_reference");
    if (sourceReference == null || sourceReference.isBlank()) {
      return reject(lineNo, "缺少来源流水号（幂等键必须可追溯）");
    }
    String amountText = text(node, "amount");
    if (amountText == null && node.hasNonNull("amount")) {
      // JSON 里的金额可能是数字而不是字符串：数字也必须按字符串读，绝不经过 double。
      amountText = node.get("amount").asText();
    }
    BigDecimal amount = parseAmount(lineNo, amountText);
    if (amount == null) return null;
    String currency = text(node, "currency");
    if (currency == null || !currency.matches("^[A-Za-z]{3}$")) {
      return reject(lineNo, "币种必须是三个字母（收到 \"" + currency + "\"）");
    }
    OffsetDateTime receivedAt = parseReceivedAt(lineNo, text(node, "received_at"));
    if (receivedAt == null) return null;
    String payerTypeText = text(node, "payer_type");
    PayerType payerType = PayerType.CUSTOMER;
    if (payerTypeText != null && !payerTypeText.isBlank()) {
      try {
        payerType = PayerType.valueOf(payerTypeText.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return reject(lineNo, "付款方类型不合法（收到 \"" + payerTypeText + "\"）");
      }
    }
    String payerName = text(node, "payer_name");
    String fundAccountLabel = text(node, "fund_account_label");
    String note = text(node, "note");
    return new ImportRow(
        lineNo,
        sourceReference.trim(),
        amount,
        currency.toUpperCase(Locale.ROOT),
        receivedAt,
        payerName == null || payerName.isBlank() ? null : payerName.trim(),
        payerType,
        fundAccountLabel == null || fundAccountLabel.isBlank() ? null : fundAccountLabel.trim(),
        note == null || note.isBlank() ? null : note.trim());
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) return null;
    return value.isValueNode() ? value.asText() : null;
  }

  // ================================================================ 字段校验

  private BigDecimal parseAmount(int lineNo, String raw) {
    if (raw == null || raw.isBlank()) {
      reject(lineNo, "缺少金额");
      return null;
    }
    BigDecimal value;
    try {
      // ⚠️ 绝不用 Double.parseDouble：二进制浮点会把 0.1 变成 0.1000000000000000055511151231257827。
      value = new BigDecimal(raw.trim().replace(",", ""));
    } catch (NumberFormatException e) {
      reject(lineNo, "金额不是数字（收到 \"" + raw + "\"）");
      return null;
    }
    if (value.signum() <= 0) {
      reject(lineNo, "金额必须大于 0（收到 " + value.toPlainString() + "）");
      return null;
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 解析到账时间。
   *
   * <p>⚠️ <b>必须带时区偏移</b>：没有偏移的时间串一律拒收 ——
   * 用 {@code Timestamp.valueOf(LocalDateTime)} 补上的是**服务器本地时区**，
   * 在东八区会静默偏 −28800s，到账时间直接错 8 小时（错账）。
   */
  private OffsetDateTime parseReceivedAt(int lineNo, String raw) {
    if (raw == null || raw.isBlank()) {
      reject(lineNo, "缺少到账时间");
      return null;
    }
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      // 有意区分「格式对但没有偏移」：这两种错误的修法完全不同。
      try {
        LocalDateTime.parse(raw.trim());
        reject(
            lineNo,
            "到账时间必须带时区偏移（如 2026-10-01T08:30:00+08:00 或 ...Z）：没有偏移会静默偏 8 小时");
        return null;
      } catch (DateTimeParseException ignored) {
        // 落到下面这句：格式本身就不对。
      }
      reject(lineNo, "到账时间格式不合法（收到 \"" + raw + "\"）：应为 ISO-8601 带偏移的时间");
      return null;
    }
  }

  /** 记一条行级警告并返回 null（调用方据此跳过该行）。 */
  private ImportRow reject(int lineNo, String reason) {
    rejections.add("第 " + lineNo + " 行/条 " + reason + "，已跳过");
    return null;
  }
}
