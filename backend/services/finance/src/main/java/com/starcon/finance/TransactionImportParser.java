package com.starcon.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 平台**交易报告**导入的解析器（CSV / JSON）。
 *
 * <p>蓝图 12.3.1.2 的输入写死「<b>平台交易报告解析结果</b>」—— 它的自动来源是「卷 02 同步」（**未交付**），
 * 所以这条路径必须**真的能落地**：解析 → 校验 → 真实入库，而不是只做一个手工登记的壳。
 *
 * <p><b>两级错误分层</b>（与同服务 12.3.1.1 的账单导入逐字同构 —— 这一层决定导入结果是否诚实）：
 *
 * <ul>
 *   <li><b>文件级错误</b>（CSV 没有必需的列、JSON 根本不是数组、CSV 引号没闭合）⇒
 *       整个请求失败（400），<b>一条都不导入</b>。半个文件入库比完全失败更危险。
 *   <li><b>行级错误</b>（某一行金额不是数字、交易类型不认识、日期格式不对）⇒
 *       <b>只跳过那一行</b>，并把「第几行 + 为什么」写进导入结果的 warnings。
 *       吞掉它的话，核对员看到「导入 0 条」会以为文件是空的。
 * </ul>
 *
 * <p><b>列名（CSV 表头 / JSON 键）</b>：{@code transaction_type}（或 {@code type} / {@code category}）与
 * {@code amount} 是**必需**的；{@code currency}、{@code quantity}、{@code occurred_on}、
 * {@code external_reference}、{@code order_reference}、{@code order_line_reference}、
 * {@code idempotency_key}、{@code description} 可选。表头大小写不敏感，{@code -} 与空格统一成 {@code _}。
 *
 * <p><b>交易类型必须认得</b>：不认识就**拒收那一行**（行级错误）——
 * 猜一个类型等于把一笔退款记成销售额，那比少一行糟糕得多。类型别名表见
 * {@link TransactionModels.FinanceTransactionType#parse(String)}。
 *
 * <p><b>幂等键</b>：行里写了 {@code idempotency_key} 就用它；没写就**从外部流水号派生**
 * （{@code external_reference}）—— 一笔平台交易的自然身份就是它的交易号。
 * 两者都没有 ⇒ **拒收那一行**：没有键就没法保证「同一笔交易不记两次」。
 *
 * <p>解析器是**一次性**的（每次导入新建一个实例），所以行级警告收集在实例字段上 ——
 * 刻意**不是** static：static 会让两次导入互相污染。
 */
final class TransactionImportParser {

  /** 金额小数位（与 DB 的 numeric(19,4) 一致）。 */
  private static final int MONEY_SCALE = 4;

  /** 数量小数位（与 DB 的 numeric(19,6) 一致）。 */
  private static final int QUANTITY_SCALE = 6;

  /** 必需列：金额。 */
  static final List<String> REQUIRED_COLUMNS = List.of("amount");

  /** 交易类型列的别名（表头里有**任意一个**即可）。 */
  static final List<String> TYPE_COLUMNS =
      List.of("transaction_type", "type", "transaction", "category");

  /** 一行平台交易报告（已通过行级校验）。 */
  record ImportRow(
      int lineNo,
      TransactionModels.FinanceTransactionType type,
      BigDecimal rawAmount,
      BigDecimal amount,
      String currency,
      BigDecimal quantity,
      LocalDate occurredOn,
      String externalReference,
      String orderReference,
      String orderLineReference,
      String idempotencyKey,
      String description) {}

  /**
   * 解析结果：能用的行 + 逐条说明（为什么没用上）。
   *
   * @param scanned 文件里一共读到多少条（**包含**被跳过的）——
   *     导入结果的不变量是 {@code scanned = imported + duplicated + skipped}。
   */
  record ParseResult(int scanned, List<ImportRow> rows, List<String> warnings) {}

  private final ObjectMapper json;

  /** 行级警告（本次解析累积）。 */
  private final List<String> rejections = new ArrayList<>();

  TransactionImportParser(ObjectMapper json) {
    this.json = json;
  }

  ParseResult parse(TransactionModels.FinanceTransactionImportFormat format, String content) {
    // 防御：同一个解析器被复用时，上一次的行级警告不许漏进这一次的结果。
    rejections.clear();
    if (content == null || content.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "导入内容为空：没有可解析的平台交易报告");
    }
    ParseResult result =
        format == TransactionModels.FinanceTransactionImportFormat.CSV
            ? parseCsv(content)
            : parseJson(content);
    var warnings = new ArrayList<>(rejections);
    warnings.addAll(result.warnings());
    return new ParseResult(result.scanned(), result.rows(), warnings);
  }

  // ================================================================ CSV

  private ParseResult parseCsv(String content) {
    var table = readCsv(content);
    if (table.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "CSV 内容为空：没有表头，也没有数据行");
    }
    var header = table.get(0).stream().map(TransactionImportParser::normalizeHeader).toList();
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
              + String.join("、", REQUIRED_COLUMNS)
              + "（表头不区分大小写，'-' 与空格统一当成 '_'）");
    }
    if (TYPE_COLUMNS.stream().noneMatch(header::contains)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "CSV 缺少交易类型列：需要 "
              + String.join(" / ", TYPE_COLUMNS)
              + " 之一（表头不区分大小写，'-' 与空格统一当成 '_'）");
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
      // 批内去重：同一份报告里同一个幂等键只留第一条。
      if (!seen.add(row.idempotencyKey())) {
        warnings.add(
            "第 " + row.lineNo() + " 行与本批次里已有的一行重复（幂等键相同），已跳过");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  /**
   * CSV 读取：按 RFC 4180 处理引号（引号内的逗号与换行是字段的一部分）。
   *
   * <p>⚠️ 平台报告的备注列里带逗号是常态，所以「按逗号 split」会把金额列冲掉。
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
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "CSV 里有没闭合的引号：整个文件不可解析");
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
    String typeText = null;
    String amountText = null;
    String currency = null;
    String quantityText = null;
    String occurredOnText = null;
    String externalReference = null;
    String orderReference = null;
    String orderLineReference = null;
    String idempotencyKey = null;
    String description = null;

    for (int c = 0; c < header.size() && c < cells.size(); c++) {
      String value = cells.get(c);
      switch (header.get(c)) {
        case "transaction_type", "type", "transaction", "category" -> {
          if (typeText == null || typeText.isBlank()) typeText = value.trim();
        }
        case "amount" -> amountText = value.trim();
        case "currency" -> currency = value.trim();
        case "quantity" -> quantityText = value.trim();
        case "occurred_on", "occurred_at", "date" -> occurredOnText = value.trim();
        case "external_reference", "transaction_id", "transaction_no", "reference" ->
            externalReference = value.trim();
        case "order_reference", "order_no", "order_id" -> orderReference = value.trim();
        case "order_line_reference", "order_line_id", "line_no" -> orderLineReference = value.trim();
        case "idempotency_key", "idempotency" -> idempotencyKey = value.trim();
        case "description", "note", "memo" -> description = value.trim();
        default -> {
          // 未登记的列**刻意忽略**：平台报告常有几十列，多一列不该让整次导入失败。
        }
      }
    }
    return build(
        lineNo,
        typeText,
        amountText,
        currency,
        quantityText,
        occurredOnText,
        externalReference,
        orderReference,
        orderLineReference,
        idempotencyKey,
        description);
  }

  // ================================================================ JSON

  private ParseResult parseJson(String content) {
    JsonNode root;
    try {
      root = json.readTree(content);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "JSON 解析失败：内容不是合法的 JSON");
    }
    if (root == null || root.isNull()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 内容为空");
    }
    JsonNode array = root;
    if (root.isObject()) {
      array = root.get("rows");
      if (array == null || array.isNull()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "JSON 顶层是对象时必须有 rows 数组（每行一条平台交易明细）");
      }
    }
    if (!array.isArray()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "JSON 顶层必须是数组（或含 rows 数组的对象）：每行一条平台交易明细");
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
      var row =
          build(
              lineNo,
              firstText(node, TYPE_COLUMNS),
              amountText(node),
              text(node, "currency"),
              text(node, "quantity"),
              text(node, "occurred_on"),
              firstText(node, List.of("external_reference", "transaction_id", "transaction_no")),
              firstText(node, List.of("order_reference", "order_no", "order_id")),
              firstText(node, List.of("order_line_reference", "order_line_id", "line_no")),
              firstText(node, List.of("idempotency_key", "idempotency")),
              text(node, "description"));
      if (row == null) continue;
      if (!seen.add(row.idempotencyKey())) {
        warnings.add("第 " + row.lineNo() + " 条与本批次里已有的一条重复（幂等键相同），已跳过");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  // ================================================================ 字段校验

  private ImportRow build(
      int lineNo,
      String typeText,
      String amountText,
      String currency,
      String quantityText,
      String occurredOnText,
      String externalReference,
      String orderReference,
      String orderLineReference,
      String idempotencyKey,
      String description) {
    if (typeText == null || typeText.isBlank()) {
      return reject(lineNo, "缺少交易类型（transaction_type）");
    }
    var type = TransactionModels.FinanceTransactionType.parse(typeText);
    if (type == null) {
      return reject(
          lineNo,
          "交易类型不认识（收到 \""
              + typeText
              + "\"）：只认 ORDER_SALES / REFUND / COMMISSION / FBA_FEE / STORAGE_FEE / SHIPPING_FEE"
              + " / ADVERTISING / COMPENSATION / ADJUSTMENT / WITHHOLDING_TAX 及其常见别名 —— "
              + "猜错类型等于把退款记成销售额");
    }
    BigDecimal rawAmount = parseAmount(lineNo, amountText);
    if (rawAmount == null) return null;
    if (currency != null && !currency.isBlank() && !currency.matches("^[A-Za-z]{3}$")) {
      return reject(lineNo, "币种必须是三个字母（收到 \"" + currency + "\"）");
    }
    BigDecimal quantity = parseQuantity(lineNo, quantityText);
    if (quantityText != null && !quantityText.isBlank() && quantity == null) return null;
    LocalDate occurredOn = parseDate(lineNo, occurredOnText);
    if (occurredOnText != null && !occurredOnText.isBlank() && occurredOn == null) return null;

    String external = blankToNull(externalReference);
    String key = blankToNull(idempotencyKey);
    if (key == null) {
      // 没有显式幂等键 ⇒ 从外部流水号派生（一笔平台交易的自然身份就是它的交易号）。
      key = external;
    }
    if (key == null) {
      return reject(
          lineNo,
          "没有幂等键也没有外部流水号：这一行无法保证「同一笔平台交易不记两次」，已跳过");
    }
    String order = blankToNull(orderReference);
    String orderLine = blankToNull(orderLineReference);
    if (orderLine != null && order == null) {
      return reject(
          lineNo,
          "给了订单行引用就必须同时给订单号（「有行号却不知道是哪张订单的行」是数据错误）");
    }
    return new ImportRow(
        lineNo,
        type,
        rawAmount,
        type.normalize(rawAmount),
        currency == null || currency.isBlank() ? null : currency.toUpperCase(Locale.ROOT),
        quantity,
        occurredOn,
        external,
        order,
        orderLine,
        key,
        description == null || description.isBlank() ? null : description);
  }

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
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal parseQuantity(int lineNo, String raw) {
    if (raw == null || raw.isBlank()) return null;
    BigDecimal value;
    try {
      value = new BigDecimal(raw.trim().replace(",", ""));
    } catch (NumberFormatException e) {
      reject(lineNo, "数量不是数字（收到 \"" + raw + "\"）");
      return null;
    }
    if (value.signum() <= 0) {
      reject(lineNo, "数量必须大于 0（收到 " + value.toPlainString() + "）");
      return null;
    }
    return value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private LocalDate parseDate(int lineNo, String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      reject(lineNo, "日期格式不合法（收到 \"" + raw + "\"）：应为 YYYY-MM-DD");
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) return null;
    return value.isValueNode() ? value.asText() : null;
  }

  /** 取第一个存在的键（别名）。 */
  private static String firstText(JsonNode node, List<String> fields) {
    for (String field : fields) {
      var value = text(node, field);
      if (value != null && !value.isBlank()) return value.trim();
    }
    return null;
  }

  private static String amountText(JsonNode node) {
    var value = node.get("amount");
    if (value == null || value.isNull()) return null;
    // JSON 里的金额可能是数字而不是字符串：数字也必须按字符串读，绝不经过 double。
    return value.asText();
  }

  private static String blankToNull(String raw) {
    if (raw == null) return null;
    String value = raw.trim();
    return value.isEmpty() ? null : value;
  }

  /** 记一条行级警告并返回 null（调用方据此跳过该行）。 */
  private ImportRow reject(int lineNo, String reason) {
    rejections.add("第 " + lineNo + " 行/条 " + reason + "，已跳过");
    return null;
  }
}
