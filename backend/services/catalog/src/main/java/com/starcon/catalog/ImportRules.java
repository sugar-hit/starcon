package com.starcon.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.8.1.1 的预校验规则（纯函数，不碰数据库）。
 *
 * <p>「预校验」是蓝图五环节里独立的一环，它的产出是<b>逐行逐列、可定位到行号</b>的错误。
 * 这类规则刻意做成纯函数：单测不需要起 PostgreSQL 就能把边界值钉死，
 * 而规则的边界（空串算不算缺、{@code 0} 算不算填、{@code gross < net} 拦不拦）
 * 恰恰是最容易在集成测试里被"顺手写对"从而永远测不到的地方。
 *
 * <p><b>ERROR 与 WARNING 的分界是硬约定</b>，它直接决定该行落不落库：
 *
 * <ul>
 *   <li>{@code ERROR} —— 必须修正。行**不落库**。缺必填、类型不对、编码格式非法、
 *       引用了不存在的商品/规格轴、模式要求「必须已存在」而它不存在。
 *   <li>{@code WARNING} —— 可跳过。行**照常落库**，只是提示。
 *       空的可选字段、已存在的编码在 CREATE 模式下会被复用（不是数据错误）。
 * </ul>
 *
 * <p>分界理由与 3.8.1.2 蓝图一致：「**校验失败的行不落库**，不允许『尽力导入』产生半截数据」。
 * 所以任何「这行可能有点问题但先导进去吧」的判断都必须是 WARNING 且不涉及事实正确性。
 */
final class ImportRules {

  /** 严重级别。落库与否由它决定，见类注释。 */
  enum Severity {
    ERROR,
    WARNING
  }

  /** 一条问题。fieldKey 为空串表示「整行级」问题（列数不符、文件内编码重复等）。 */
  record Problem(String fieldKey, String code, String message, Severity severity) {
    static Problem error(String fieldKey, String code, String message) {
      return new Problem(fieldKey, code, message, Severity.ERROR);
    }

    static Problem warning(String fieldKey, String code, String message) {
      return new Problem(fieldKey, code, message, Severity.WARNING);
    }

    boolean blocking() {
      return severity == Severity.ERROR;
    }
  }

  /** 模板列的规则视图（服务层从 import_template_column 读出来后喂进来）。 */
  record Column(String fieldKey, String dataType, boolean required, boolean matchKey) {}

  /**
   * 行校验需要、但**只能从库里知道**的事实。
   *
   * <p>把「引用是否存在」「编码是否已存在」做成参数而不是让 Rules 连库，
   * 是为了让规则保持纯函数；这些事实由 {@code ImportJobService} 在预校验时查一次传进来。
   *
   * @param matchValue 匹配键的值（CREATE/UPDATE 模式下用它定位记录）
   * @param matchExists 匹配键在库里**已存在**（MATCH_BY_CODE 决定新建还是更新）
   * @param duplicateInFile 同一个匹配键在文件里出现了第二次
   * @param referenceProblem 引用类错误（引用的 SPU/SKU/属性不存在），空表示引用都成立
   */
  record Facts(
      String matchValue,
      boolean matchExists,
      boolean duplicateInFile,
      Problem referenceProblem,
      List<Problem> extraWarnings) {
    public static Facts clean() {
      return new Facts(null, false, false, null, List.of());
    }
  }

  /** 导入模式。 */
  enum Mode {
    CREATE,
    UPDATE,
    MATCH_BY_CODE
  }

  /** 目标对象。与 V29 的 import_job.target_entity CHECK 同一词表。 */
  enum Entity {
    SPU,
    SKU,
    ATTRIBUTE,
    PACKAGE,
    BARCODE
  }

  private ImportRules() {}

  /**
   * 解析目标对象，不合法时返回 {@code null}（而不是抛 IllegalArgumentException ——
   * 那条异常会变成 500，而「目标对象写错了」是 400）。
   */
  static Entity entityOf(String value) {
    if (value == null) return null;
    try {
      return Entity.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 解析导入模式，不合法时返回 {@code null}。 */
  static Mode modeOf(String value) {
    if (value == null) return null;
    try {
      return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ================================================================ 标准列定义
  //
  // 一份权威列定义，**同时**用于「建模板时的默认列」与「预校验时认哪些字段」。
  // 分成两份的话，改了默认列而校验还按旧列名跑，运营会看到「必填缺失」却怎么填都对不上。

  /** 各目标对象的**匹配键**：编码类字段，三种模式都用它定位记录。 */
  private static final Map<Entity, String> MATCH_KEYS =
      Map.of(
          Entity.SPU, "spu_code",
          Entity.SKU, "sku_code",
          Entity.ATTRIBUTE, "spu_code",
          Entity.PACKAGE, "sku_code",
          Entity.BARCODE, "sku_code");

  /**
   * 各目标对象在 {@link Entity#SPU} 之外**额外必填**的字段。
   *
   * <p>SPU 自身的 code/name 是匹配键 + 名称，不在这里重复列。
   */
  private static final Map<Entity, List<String>> EXTRA_REQUIRED =
      Map.of(
          Entity.SPU, List.of("spu_name"),
          Entity.SKU, List.of("spu_code", "sku_name"),
          Entity.ATTRIBUTE, List.of("attribute_code", "attribute_value"),
          Entity.PACKAGE, List.of(),
          Entity.BARCODE, List.of("scheme", "barcode_value"));

  /** 默认列定义。code/name/sample/help 供模板下载用。 */
  record DefaultColumn(
      String fieldKey,
      String header,
      String dataType,
      boolean required,
      boolean matchKey,
      String sample,
      String help) {}

  /** 某个目标对象的标准列。 */
  static List<DefaultColumn> defaultColumns(Entity entity) {
    return switch (entity) {
      case SPU -> List.of(
          dc("spu_code", "商品编码", "TEXT", true, true, "SPU-1001",
              "租户内唯一。导入模式用它定位记录，建后不可改"),
          dc("spu_name", "商品名称", "TEXT", true, false, "无线蓝牙耳机", "展示名，刊登与订单都用它"),
          dc("description", "商品描述", "TEXT", false, false, "TWS 蓝牙耳机，续航 30 小时", "留空表示还没采集，不影响导入"),
          dc("brand", "品牌", "TEXT", false, false, "SoundCore", "自由文本，与 3.3.1.1 品牌档案按文字对齐"),
          dc("category", "类目", "TEXT", false, false, "消费电子/音频", "自由文本路径，刊登侧按它匹配类目"),
          dc("status", "状态", "TEXT", false, false, "DRAFT",
              "DRAFT 草稿 / PENDING_LISTING 待上架 / ON_SALE 在售 / SUSPENDED 停售 / DISCONTINUED 停产"));
      case SKU -> List.of(
          dc("sku_code", "SKU 编码", "TEXT", true, true, "SKU-1001-BLK",
              "租户内唯一。导入模式用它定位记录"),
          dc("spu_code", "所属商品编码", "TEXT", true, false, "SPU-1001", "必须指向本租户已存在的商品"),
          dc("sku_name", "SKU 名称", "TEXT", true, false, "黑色 / 中号", "规格描述"),
          dc("base_unit", "基础单位", "TEXT", false, false, "件", "留空按「件」处理"),
          dc("status", "状态", "TEXT", false, false, "ACTIVE", "DRAFT / ACTIVE / INACTIVE / ARCHIVED"));
      case ATTRIBUTE -> List.of(
          dc("spu_code", "商品编码", "TEXT", true, true, "SPU-1001", "属性挂在商品上，用它定位"),
          dc("attribute_code", "属性编码", "TEXT", true, false, "battery_life", "必须指向该商品下已定义的属性"),
          dc("attribute_value", "属性值", "TEXT", true, false, "30", "文本原样写入，数值型属性请填纯数字"),
          dc("sku_code", "SKU 编码", "TEXT", false, false, "", "仅「仅 SKU」属性需要填：给公共属性填它会被拒"),
          dc("value_mode", "赋值范围", "TEXT", false, false, "COMMON_DEFAULT",
              "COMMON_LOCKED 公共锁定 / COMMON_DEFAULT 公共默认 / SKU_ONLY 仅 SKU"));
      case PACKAGE -> List.of(
          dc("sku_code", "SKU 编码", "TEXT", true, true, "SKU-1001-BLK", "包装挂在 SKU 上，用它定位"),
          dc("packaging_type", "包装类型", "TEXT", false, false, "彩盒", "自由文本"),
          dc("length_mm", "长(mm)", "DECIMAL", false, false, "120", "留空表示未采集，与「采集到是 0」不同"),
          dc("width_mm", "宽(mm)", "DECIMAL", false, false, "80", ""),
          dc("height_mm", "高(mm)", "DECIMAL", false, false, "45", ""),
          dc("net_weight_g", "净重(g)", "DECIMAL", false, false, "210", "不能大于毛重"),
          dc("gross_weight_g", "毛重(g)", "DECIMAL", false, false, "260", "必须不小于净重"));
      case BARCODE -> List.of(
          dc("sku_code", "SKU 编码", "TEXT", true, true, "SKU-1001-BLK", "条码挂在 SKU 上，用它定位"),
          dc("scheme", "条码体系", "TEXT", true, false, "EAN_13", "EAI_UPC_A / EAN_13 / GTIN_14 / CUSTOM"),
          dc("barcode_value", "条码值", "TEXT", true, false, "6941234567890", "同一体系下全租户唯一"));
    };
  }

  private static DefaultColumn dc(String key, String header, String type, boolean required,
      boolean matchKey, String sample, String help) {
    return new DefaultColumn(key, header, type, required, matchKey, sample, help);
  }

  /** 某个目标对象的匹配键字段名。 */
  static String matchKeyOf(Entity entity) {
    return MATCH_KEYS.get(entity);
  }

  /**
   * 某行的**自然键**（用来判「这行指的是不是同一条记录」）。
   *
   * <p>大多数对象就是匹配键本身，但 <b>属性导入是复合键</b>：
   * 一个商品有几十个属性，文件里同一个 {@code spu_code} 会出现几十行（每个属性一行）。
   * 若按单列判「文件内重复」，第二个属性行会被错判成重复编码而被拒 ——
   * 而那不是重复，是正常数据。所以属性的自然键是 {@code spu_code + attribute_code}。
   *
   * <p>服务层用同一个方法去库里查「这条记录是不是已存在」，
   * 规则层用它判文件内重复，两边口径一致才不会一处放行一处拒绝。
   */
  static String naturalKey(Entity entity, Map<String, String> values) {
    String spu = text(values.get("spu_code"));
    return switch (entity) {
      case ATTRIBUTE -> spu + "␟" + text(values.get("attribute_code"));
      case SKU, PACKAGE, BARCODE -> text(values.get("sku_code"));
      case SPU -> spu;
    };
  }

  private static String text(String v) {
    return v == null ? "" : v.trim();
  }

  // ================================================================ 逐行校验

  /**
   * 校验一行。返回该行的全部问题（可能多条：一次把这一行的问题都列出来，
   * 而不是让用户改一个重传一次）。
   *
   * @param columns 模板列（已按 ordinal 排序）
   * @param values field_key → 原始文本
   */
  static List<Problem> validateRow(
      Entity entity, Mode mode, List<Column> columns, Map<String, String> values, Facts facts) {
    List<Problem> problems = new ArrayList<>();
    Set<String> declared = new java.util.LinkedHashSet<>();
    for (Column c : columns) declared.add(c.fieldKey());

    // ---- 1) 必填（列定义里的 required + 各对象的额外必填）
    //
    // 「额外必填」只在**该字段没有在列定义里声明为 required** 时才补报。
    // 不去重的话同一个空字段会被报两次 REQUIRED —— 页面上同一个格子挂两条一样的错，
    // 而 import_row_error 的唯一键 (job,row,field,code) 又会让第二次插入直接失败。
    var requiredColumns = new java.util.LinkedHashSet<String>();
    for (Column column : columns) {
      requiredColumns.add(column.fieldKey());
      if (isBlank(values.get(column.fieldKey())) && column.required())
        problems.add(
            Problem.error(column.fieldKey(), "REQUIRED", "「" + column.fieldKey() + "」是必填列，这一行没有值"));
    }
    for (String required : EXTRA_REQUIRED.get(entity)) {
      if (requiredColumns.contains(required)) continue;
      if (!declared.contains(required) || isBlank(values.get(required)))
        problems.add(Problem.error(required, "REQUIRED", "「" + required + "」是必填列，这一行没有值"));
    }

    // ---- 2) 类型（只对有值的格子判类型：空值归必填管，不重复报两种错）
    for (Column column : columns) {
      String raw = values.get(column.fieldKey());
      if (isBlank(raw)) continue;
      String typeProblem = checkType(column, raw.trim());
      if (typeProblem != null) problems.add(Problem.error(column.fieldKey(), "INVALID_TYPE", typeProblem));
    }

    // ---- 3) 匹配键与模式
    String matchKey = matchKeyOf(entity);
    String matchValue = values.get(matchKey) == null ? null : values.get(matchKey).trim();
    if (isBlank(matchValue) && mode != Mode.CREATE)
      problems.add(
          Problem.error(matchKey, "MATCH_KEY_REQUIRED",
              mode == Mode.UPDATE
                  ? "更新模式必须提供「" + matchKey + "」来定位要改的记录"
                  : "按编码匹配模式必须提供「" + matchKey + "」"));
    if (isBlank(matchValue) && mode == Mode.CREATE)
      problems.add(
          Problem.error(matchKey, "MATCH_KEY_REQUIRED", "新增模式必须提供「" + matchKey + "」作为编码"));
    if (!isBlank(matchValue) && matchValue.length() > 64)
      problems.add(
          Problem.error(matchKey, "TOO_LONG",
              "「" + matchKey + "」最长 64 个字符，当前 " + matchValue.length() + " 个"));
    if (facts.duplicateInFile())
      problems.add(
          Problem.error(matchKey, "DUPLICATE_IN_FILE",
              "这一行与本文件里另一行指向同一条记录（"
                  + naturalKey(entity, values)
                  + "）。一次导入里同一条记录只能出现一次，否则后一行会覆盖前一行"));
    if (mode == Mode.CREATE && !isBlank(matchValue) && facts.matchExists())
      problems.add(
          Problem.warning(matchKey, "EXISTS_IN_CREATE_MODE",
              "「" + matchKey + " = " + matchValue + "」已存在。新增模式不会覆盖它，这一行会被跳过；"
                  + "要修改请改用更新或按编码匹配模式"));
    if (mode == Mode.UPDATE && !isBlank(matchValue) && !facts.matchExists())
      problems.add(
          Problem.error(matchKey, "NOT_FOUND_IN_UPDATE_MODE",
              "更新模式要求记录已存在，但找不到「" + matchKey + " = " + matchValue + "」"));

    // ---- 4) 引用完整性（事实由服务层查库得到）
    if (facts.referenceProblem() != null) problems.add(facts.referenceProblem());
    problems.addAll(facts.extraWarnings());

    // ---- 5) 各对象的字段间约束
    problems.addAll(entityChecks(entity, values));
    return List.copyOf(problems);
  }

  /**
   * 字段间约束。
   *
   * <p>这些是 DB 层 CHECK 也会挡的规则（{@code sku_package} 的「毛重不小于净重」），
   * 提前在预校验报出来，是为了让人**在第 47 行就知道**而不是等整个任务失败。
   * 规则不因为 DB 已有约束就省掉：约束拦住的是写入，报错是白屏；预校验报的是「第 47 行」。
   */
  private static List<Problem> entityChecks(Entity entity, Map<String, String> values) {
    List<Problem> problems = new ArrayList<>();
    switch (entity) {
      case SPU -> {
        var status = trimmed(values.get("status"));
        if (!isBlank(status) && !Set.of("DRAFT", "PENDING_LISTING", "ON_SALE", "SUSPENDED", "DISCONTINUED")
            .contains(status.toUpperCase(Locale.ROOT)))
          problems.add(
              Problem.error("status", "INVALID_ENUM",
                  "商品状态只能是 DRAFT / PENDING_LISTING / ON_SALE / SUSPENDED / DISCONTINUED，当前「" + status + "」"));
        if (longerThan(values.get("spu_name"), 200))
          problems.add(Problem.error("spu_name", "TOO_LONG", "商品名称最长 200 个字符"));
      }
      case SKU -> {
        var status = trimmed(values.get("status"));
        if (!isBlank(status) && !Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").contains(status.toUpperCase(Locale.ROOT)))
          problems.add(Problem.error("status", "INVALID_ENUM", "SKU 状态只能是 DRAFT / ACTIVE / INACTIVE / ARCHIVED，当前「" + status + "」"));
        if (longerThan(values.get("sku_name"), 200))
          problems.add(Problem.error("sku_name", "TOO_LONG", "SKU 名称最长 200 个字符"));
      }
      case BARCODE -> {
        var scheme = trimmed(values.get("scheme"));
        if (!isBlank(scheme) && !BARCODE_SCHEMES.contains(scheme.toUpperCase(Locale.ROOT)))
          problems.add(
              Problem.error("scheme", "INVALID_ENUM",
                  "条码体系只能是 " + String.join(" / ", BARCODE_SCHEMES) + "，当前「" + scheme + "」"));
        var value = trimmed(values.get("barcode_value"));
        if (!isBlank(value) && value.length() > 128)
          problems.add(Problem.error("barcode_value", "TOO_LONG", "条码值最长 128 个字符"));
      }
      case PACKAGE -> {
        for (String field : List.of("length_mm", "width_mm", "height_mm", "net_weight_g", "gross_weight_g")) {
          var raw = trimmed(values.get(field));
          if (isBlank(raw)) continue;
          // 不是数字的格子由 checkType 报 INVALID_TYPE；这里直接跳过，
          // 否则 new BigDecimal 会把一个「类型错」变成一个 500。
          BigDecimal v = decimalOrNull(raw);
          if (v == null) continue;
          if (v.signum() < 0)
            problems.add(Problem.error(field, "NEGATIVE", "「" + field + "」不能是负数，当前 " + raw));
        }
        // 毛重不小于净重：与 sku_package 的 CHECK 同口径，DB 层也会挡。
        var net = decimalOrNull(values.get("net_weight_g"));
        var gross = decimalOrNull(values.get("gross_weight_g"));
        if (net != null && gross != null && gross.compareTo(net) < 0)
          problems.add(
              Problem.error("gross_weight_g", "GROSS_LT_NET",
                  "毛重（" + gross.toPlainString() + "g）不能小于净重（" + net.toPlainString() + "g）"));
      }
      case ATTRIBUTE -> {
        var mode = trimmed(values.get("value_mode"));
        if (!isBlank(mode) && !Set.of("COMMON_LOCKED", "COMMON_DEFAULT", "SKU_ONLY").contains(mode.toUpperCase(Locale.ROOT)))
          problems.add(
              Problem.error("value_mode", "INVALID_ENUM",
                  "赋值范围只能是 COMMON_LOCKED / COMMON_DEFAULT / SKU_ONLY，当前「" + mode + "」"));
      }
    }
    return problems;
  }

  /** 条码体系词表。与 V7 的 sku_identifier.scheme 自由文本不同，这里收窄到 3.1.2.3 点名的四种。 */
  static final Set<String> BARCODE_SCHEMES = Set.of("EAI_UPC_A", "EAN_13", "GTIN_14", "CUSTOM");

  /**
   * 按列声明的类型检查一个格子。返回 null 表示通过。
   *
   * <p><b>空值不进来</b>（调用方先判了 isBlank）：空是「没填」而不是「填了空串」，
   * 两件事的报错不同，混在一起会让人以为类型错了。
   */
  static String checkType(Column column, String raw) {
    return switch (column.dataType() == null ? "TEXT" : column.dataType().toUpperCase(Locale.ROOT)) {
      case "DECIMAL" -> {
        try {
          new BigDecimal(raw);
          yield null;
        } catch (NumberFormatException e) {
          yield "「" + column.fieldKey() + "」应该是数字，当前是「" + raw + "」";
        }
      }
      case "INTEGER" -> {
        try {
          new BigDecimal(raw).toBigIntegerExact();
          yield null;
        } catch (ArithmeticException e) {
          yield "「" + column.fieldKey() + "」应该是整数，当前是「" + raw + "」";
        } catch (NumberFormatException e) {
          yield "「" + column.fieldKey() + "」应该是整数，当前是「" + raw + "」";
        }
      }
      case "BOOLEAN" -> {
        var v = raw.toLowerCase(Locale.ROOT);
        yield (v.equals("true") || v.equals("false") || v.equals("是") || v.equals("否")
                || v.equals("1") || v.equals("0") || v.equals("y") || v.equals("n"))
            ? null
            : "「" + column.fieldKey() + "」应该是 true/false、是/否、1/0，当前是「" + raw + "」";
      }
      case "DATE" -> {
        try {
          LocalDate.parse(raw);
          yield null;
        } catch (DateTimeParseException e) {
          yield "「" + column.fieldKey() + "」应该是日期（yyyy-MM-dd），当前是「" + raw + "」";
        }
      }
      case "TIMESTAMPTZ" -> {
        try {
          OffsetDateTime.parse(raw);
          yield null;
        } catch (DateTimeParseException e) {
          yield "「" + column.fieldKey() + "」应该是带时区的时间（2026-10-07T10:00:00+08:00），当前是「" + raw + "」";
        }
      }
      default -> null;
    };
  }

  /**
   * 把 DB 事实并进一行的问题列表里，并给出行是否可落库。
   *
   * <p>执行阶段用同一个判据决定「这行写不写」：有 ERROR 就不写。
   * 判据放在这里是为了让<b>预校验看到的与执行做的必然一致</b> ——
   * 两边各写一份 `anyMatch(ERROR)` 的话，改了一边就会变成
   * 「预校验说没问题、执行却跳过」。
   */
  static boolean blocking(List<Problem> problems) {
    return problems.stream().anyMatch(Problem::blocking);
  }

  /** 行内容归一化：保留原始文本（类型转换在执行时做），键按列顺序排好，便于前端展示。 */
  static Map<String, String> normalizeValues(List<Column> columns, List<String> cells) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      String key = columns.get(i).fieldKey();
      out.put(key, i < cells.size() && cells.get(i) != null ? cells.get(i).trim() : "");
    }
    return out;
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  private static String trimmed(String v) {
    return v == null ? "" : v.trim();
  }

  private static boolean longerThan(String v, int max) {
    return !isBlank(v) && v.trim().length() > max;
  }

  private static BigDecimal decimalOrNull(String v) {
    if (isBlank(v)) return null;
    try {
      return new BigDecimal(v.trim());
    } catch (NumberFormatException e) {
      return null; // 类型错误已由 checkType 单独报过，这里不重复
    }
  }
}
