package com.starcon.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 平台**代扣代缴报表**的解析器（CSV / JSON）。
 *
 * <p>蓝图 16.3.2.3 的输入之一是「<b>平台代扣代缴报表</b>」—— 它的来源是平台后台导出的文件，
 * 所以这条路径必须**真的能落地**：上传 → 解析 → 校验 → 真实入库，而不是只做一个手工登记的壳。
 *
 * <p><b>两级错误分层</b>（与同服务 12.3.1.1 账单导入、finance 12.3.1.2 交易报告导入逐字同构 ——
 * 这一层决定导入结果是否诚实）：
 *
 * <ul>
 *   <li><b>文件级错误</b>（CSV 没有必需的列、JSON 根本不是数组、CSV 引号没闭合）⇒
 *       整个请求失败（400），<b>一条都不导入</b>。半个文件入库比完全失败更危险。</li>
 *   <li><b>行级错误</b>（某一行金额不是数字、州码形状不对、币种不是三位字母）⇒
 *       <b>只跳过那一行</b>，并把「第几行 + 为什么」写进导入结果的 warnings。
 *       吞掉它的话，核对员看到「导入 0 条」会以为文件是空的。</li>
 * </ul>
 *
 * <p><b>列名（CSV 表头 / JSON 键）</b>：{@code state_code}（或 {@code state} / {@code jurisdiction}）、
 * {@code tax_collected}（或 {@code tax} / {@code tax_amount} / {@code collected_tax}）与
 * {@code currency} 是**必需**的；{@code platform_code}、{@code shop_code}、{@code taxable_amount}、
 * {@code order_count}、{@code remark} 可选。表头大小写不敏感，{@code -} 与空格统一成 {@code _}。
 *
 * <p>🔴 <b>州码不认识 ⇒ 那一行不落库</b>：服务层按 {@code tax_registration_jurisdiction}
 * 校验州码是否真实存在（DB 触发器也会钉死），不认识的州码**逐行跳过并写明原因** ——
 * 把「US-XX」这种看着像州码的字符串当成真州记进去，对账就会拿一个不存在的州去比税。
 *
 * <p>解析器是**一次性**的（每次导入新建一个实例），所以行级警告收集在实例字段上 ——
 * 刻意**不是** static：static 会让两次导入互相污染。
 */
final class SalesTaxFacilitatorImportParser {

  /** 金额小数位（与 DB 的 numeric(19,6) 一致）。 */
  private static final int MONEY_SCALE = 6;

  /** 必需列：州码。 */
  static final List<String> REQUIRED_COLUMNS = List.of("state_code", "tax_collected", "currency");

  /** 州码列的别名（表头里有**任意一个**即可）。 */
  static final List<String> STATE_COLUMNS = List.of("state_code", "state", "jurisdiction");

  /** 代扣代缴税额列的别名。 */
  static final List<String> TAX_COLUMNS =
      List.of("tax_collected", "tax", "tax_amount", "collected_tax", "facilitator_tax");

  /** 应税销售额列的别名。 */
  static final List<String> TAXABLE_COLUMNS =
      List.of("taxable_amount", "taxable_sales", "taxable", "sales_amount");

  /** 一行代扣代缴报表（已通过行级**形状**校验；州码是否真实存在由服务层校验）。 */
  record ImportRow(
      int lineNo,
      String stateCode,
      String platformCode,
      String shopCode,
      BigDecimal taxableAmount,
      BigDecimal taxCollected,
      long orderCount,
      String currency,
      String remark) {}

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

  SalesTaxFacilitatorImportParser(ObjectMapper json) {
    this.json = json;
  }

  ParseResult parse(String contentType, String content) {
    // 防御：同一个解析器被复用时，上一次的行级警告不许漏进这一次的结果。
    rejections.clear();
    if (content == null || content.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "导入内容为空：没有可解析的代扣代缴报表");
    }
    ParseResult result =
        "JSON".equalsIgnoreCase(contentType) ? parseJson(content) : parseCsv(content);
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
    var header = table.get(0).stream().map(SalesTaxFacilitatorImportParser::normalizeHeader).toList();
    var missing = new ArrayList<String>();
    for (String column : REQUIRED_COLUMNS) {
      if (!header.contains(column) && !aliasesOf(column).stream().anyMatch(header::contains)) {
        missing.add(column);
      }
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
      // 批内去重：同一份报表里同一个「州 × 平台 × 店铺」只留第一条。
      String key = dedupeKey(row);
      if (!seen.add(key)) {
        warnings.add(
            "第 " + row.lineNo() + " 行与本批次里已有的一行重复（同州同平台同店铺），已跳过");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  /**
   * CSV 读取：按 RFC 4180 处理引号（引号内的逗号与换行是字段的一部分）。
   *
   * <p>⚠️ 平台报表的备注列里带逗号是常态，所以「按逗号 split」会把金额列冲掉。
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

  /** 某个必需列的别名清单。 */
  private static List<String> aliasesOf(String column) {
    return switch (column) {
      case "state_code" -> STATE_COLUMNS;
      case "tax_collected" -> TAX_COLUMNS;
      default -> List.of(column);
    };
  }

  /** 批内去重键：州 × 平台 × 店铺。 */
  private static String dedupeKey(ImportRow row) {
    return row.stateCode() + '|' + row.platformCode() + '|' + row.shopCode();
  }

  /** 校验并解析一行；不合法时返回 null 并把原因写进 rejections。 */
  private ImportRow parseCells(List<String> cells, List<String> header, int lineNo) {
    String stateCode = null;
    String platformCode = "";
    String shopCode = "";
    String taxableText = null;
    String taxText = null;
    String orderText = null;
    String currency = null;
    String remark = "";

    for (int c = 0; c < header.size() && c < cells.size(); c++) {
      String value = cells.get(c);
      String column = header.get(c);
      if (STATE_COLUMNS.contains(column)) {
        if (stateCode == null || stateCode.isBlank()) stateCode = value.trim();
        continue;
      }
      if (TAX_COLUMNS.contains(column)) {
        if (taxText == null || taxText.isBlank()) taxText = value.trim();
        continue;
      }
      if (TAXABLE_COLUMNS.contains(column)) {
        if (taxableText == null || taxableText.isBlank()) taxableText = value.trim();
        continue;
      }
      switch (column) {
        case "currency" -> currency = value.trim();
        case "platform_code", "platform" -> platformCode = value.trim();
        case "shop_code", "shop" -> shopCode = value.trim();
        case "order_count", "orders", "order_cnt" -> orderText = value.trim();
        case "remark", "note", "memo" -> remark = value.trim();
        default -> {
          // 未登记的列**刻意忽略**：平台报表常有几十列，多一列不该让整次导入失败。
        }
      }
    }
    return build(
        lineNo,
        stateCode,
        platformCode,
        shopCode,
        taxableText,
        taxText,
        orderText,
        currency,
        remark);
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
            HttpStatus.BAD_REQUEST, "JSON 顶层是对象时必须有 rows 数组（每行一条代扣代缴明细）");
      }
    }
    if (!array.isArray()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "JSON 顶层必须是数组（或含 rows 数组的对象）：每行一条代扣代缴明细");
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
              firstText(node, STATE_COLUMNS),
              text(node, "platform_code", "platform"),
              text(node, "shop_code", "shop"),
              firstText(node, TAXABLE_COLUMNS),
              firstText(node, TAX_COLUMNS),
              firstText(node, List.of("order_count", "orders", "order_cnt")),
              text(node, "currency"),
              text(node, "remark", "note", "memo"));
      if (row == null) continue;
      String key = dedupeKey(row);
      if (!seen.add(key)) {
        warnings.add(
            "第 " + row.lineNo() + " 条与本批次里已有的一条重复（同州同平台同店铺），已跳过");
        continue;
      }
      rows.add(row);
    }
    return new ParseResult(scanned, rows, warnings);
  }

  // ================================================================ 行级校验

  private ImportRow build(
      int lineNo,
      String stateCode,
      String platformCode,
      String shopCode,
      String taxableText,
      String taxText,
      String orderText,
      String currency,
      String remark) {
    String state = stateCode == null ? "" : stateCode.trim().toUpperCase(Locale.ROOT);
    if (state.isEmpty()) {
      rejections.add("第 " + lineNo + " 行缺少州码（state_code），已跳过");
      return null;
    }
    // 🔴 形状校验：真实州码由服务层按码表校验（这里只挡明显不是州码的东西）。
    if (!state.matches("US-[A-Z]{2}")) {
      rejections.add("第 " + lineNo + " 行的州码 " + state + " 不是 US-XX 形状，已跳过");
      return null;
    }
    if (taxText == null || taxText.isBlank()) {
      rejections.add("第 " + lineNo + " 行缺少代扣代缴税额（tax_collected），已跳过");
      return null;
    }
    BigDecimal tax = decimal(taxText);
    if (tax == null) {
      rejections.add("第 " + lineNo + " 行的代扣代缴税额不是数字：" + taxText + "，已跳过");
      return null;
    }
    if (tax.signum() < 0) {
      rejections.add("第 " + lineNo + " 行的代扣代缴税额是负数：" + taxText + "，已跳过");
      return null;
    }
    BigDecimal taxable = BigDecimal.ZERO;
    if (taxableText != null && !taxableText.isBlank()) {
      BigDecimal parsed = decimal(taxableText);
      if (parsed == null) {
        rejections.add("第 " + lineNo + " 行的应税销售额不是数字：" + taxableText + "，已跳过");
        return null;
      }
      if (parsed.signum() < 0) {
        rejections.add("第 " + lineNo + " 行的应税销售额是负数：" + taxableText + "，已跳过");
        return null;
      }
      taxable = parsed;
    }
    long orderCount = 0;
    if (orderText != null && !orderText.isBlank()) {
      try {
        orderCount = Long.parseLong(orderText.trim());
      } catch (NumberFormatException e) {
        rejections.add("第 " + lineNo + " 行的订单笔数不是整数：" + orderText + "，已跳过");
        return null;
      }
      if (orderCount < 0) {
        rejections.add("第 " + lineNo + " 行的订单笔数是负数：" + orderText + "，已跳过");
        return null;
      }
    }
    String currencyCode = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    if (!currencyCode.matches("[A-Z]{3}")) {
      rejections.add("第 " + lineNo + " 行的币种不是三位大写字母，已跳过");
      return null;
    }
    return new ImportRow(
        lineNo,
        state,
        platformCode == null ? "" : platformCode.trim(),
        shopCode == null ? "" : shopCode.trim(),
        taxable.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        tax.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        orderCount,
        currencyCode,
        remark == null ? "" : remark.trim());
  }

  /** 金额解析：不是数字就返回 null（由调用方写进行级告警）。 */
  private static BigDecimal decimal(String raw) {
    try {
      return new BigDecimal(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String firstText(JsonNode node, List<String> keys) {
    for (String key : keys) {
      String value = text(node, key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String text(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode value = node.get(key);
      if (value == null || value.isNull()) continue;
      if (value.isNumber()) {
        return value.asText();
      }
      if (value.isTextual()) {
        return value.asText().trim();
      }
    }
    return null;
  }
}
