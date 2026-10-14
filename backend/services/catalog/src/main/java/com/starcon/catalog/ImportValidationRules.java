package com.starcon.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.8.1.2 校验报告的纯规则：<b>错误码 → 蓝图点名的分类</b>，以及带标注文件的行装配。
 *
 * <p><b>本模块不重新校验</b>。校验规则是 3.8.1.1 的 {@link ImportRules}（纯函数）， 唯一写入所有者是 {@code
 * ImportJobExecutor}。这里只做两件纯计算的事：
 *
 * <ol>
 *   <li>把上游那 14 个机器码**归到蓝图点名的六类**，让运营按类批量改而不是逐码认；
 *   <li>把「条级问题」装配成「行级标注」，也就是导出文件里的那一行。
 * </ol>
 *
 * <p><b>为什么归类函数不做兜底</b>：蓝图把「必填缺失、格式错误、重复编码、引用不存在、 唯一冲突」列成报告要展示的五类，而这五类正是运营动手改的入口。 如果这里留一个 {@code
 * OTHER} 兜底桶，那么 3.8.1.1 将来新增一个错误码时会**悄悄**落进 「其他问题」——运营在报告里看到一栏自己看不懂的东西，却不知道该改什么，
 * 而分类这件事本来就是为了消除这种看不懂。所以 {@link #ALL_CODES} 是封闭词表， 上游每加一个码，本类的单测（{@code categoryOf} 对 {@link
 * #ALL_CODES} 全覆盖 + 无遗漏） 与生成报告时的显式失败都会把缺口指出来，而不是让它安静地混进兜底桶。
 *
 * <p><b>严重级别的分界不在这里重新发明</b>：{@link #blocking} 直接读 {@link ImportRules.Severity}，ERROR/WARNING
 * 的语义与落库与否以 {@code ImportRules} 的 类注释为准（ERROR=必须修正、行不落库；WARNING=可跳过、行照常落库）。
 */
final class ImportValidationRules {
  private ImportValidationRules() {}

  /** 报告的六类问题。与 V34 的 {@code import_validation_issue.category} CHECK 同一词表。 */
  enum Category {
    REQUIRED_MISSING("必填缺失"),
    FORMAT_INVALID("格式错误"),
    DUPLICATE_CODE("重复编码"),
    REFERENCE_MISSING("引用不存在"),
    UNIQUE_CONFLICT("唯一冲突"),
    WRITE_FAILED("写入失败");

    final String label;

    Category(String label) {
      this.label = label;
    }

    /** 给运营看的中文类名（导出文件与报告页都用它）。 */
    String label() {
      return label;
    }
  }

  /**
   * 码 → 分类。
   *
   * <p><b>这张表覆盖 V29 上游能产出的全部 14 个机器码</b>，一个不多一个不少： 10 个来自 {@code ImportRules}（REQUIRED /
   * MATCH_KEY_REQUIRED / INVALID_TYPE / INVALID_ENUM / TOO_LONG / DUPLICATE_IN_FILE /
   * EXISTS_IN_CREATE_MODE / NOT_FOUND_IN_UPDATE_MODE / NEGATIVE / GROSS_LT_NET）， 3 个来自 {@code
   * ImportJobExecutor} 预校验侧（UNMAPPED_COLUMN / REFERENCE_NOT_FOUND / BARCODE_TAKEN），1
   * 个来自执行侧（WRITE_FAILED）。
   *
   * <p>两处归类需要说明，都是有取舍的：
   *
   * <ul>
   *   <li>{@code NOT_FOUND_IN_UPDATE_MODE} 归「唯一冲突」而不是「引用不存在」： 它问的是「这条记录在库里存在吗」，与「被引用的对象存不存在」是两件事
   *       （前者决定这一行能不能被定位到，后者决定引用完整性）。蓝图只给了五类， 「编码存在性冲突」归到「唯一冲突」最贴近运营的动作：换模式或先建这条数据。
   *   <li>{@code UNMAPPED_COLUMN} 归「必填缺失」：模板说这一列必填，而这次映射没对上， 运营看到的症状就是「这一格是空的」。动作也是同一个 ——
   *       去字段映射里选中它。
   * </ul>
   */
  private static final Map<String, Category> BY_CODE = build();

  private static Map<String, Category> build() {
    Map<String, Category> m = new LinkedHashMap<>();
    for (Category c : List.of(Category.REQUIRED_MISSING))
      put(m, c, "REQUIRED", "MATCH_KEY_REQUIRED", "UNMAPPED_COLUMN");
    for (Category c : List.of(Category.FORMAT_INVALID))
      put(m, c, "INVALID_TYPE", "INVALID_ENUM", "TOO_LONG", "NEGATIVE", "GROSS_LT_NET");
    put(m, Category.DUPLICATE_CODE, "DUPLICATE_IN_FILE");
    put(m, Category.REFERENCE_MISSING, "REFERENCE_NOT_FOUND");
    put(
        m,
        Category.UNIQUE_CONFLICT,
        "EXISTS_IN_CREATE_MODE",
        "NOT_FOUND_IN_UPDATE_MODE",
        "BARCODE_TAKEN");
    put(m, Category.WRITE_FAILED, "WRITE_FAILED");
    return Map.copyOf(m);
  }

  private static void put(Map<String, Category> m, Category category, String... codes) {
    for (String code : codes) m.put(code, category);
  }

  /**
   * 上游全部机器码的封闭词表。
   *
   * <p>公开它是为了让单测能断言「{@link #BY_CODE} 覆盖了词表里的每一个码， 且词表里没有多余的码」—— 漏一个（3.8.1.1 新增了码而这里没跟上）和多一个
   * （这里写了而上游不再产出）同样会让报告的分类悄悄失真。
   */
  static final Set<String> ALL_CODES = BY_CODE.keySet();

  /**
   * 错误码 → 分类。
   *
   * <p><b>未知码返回 {@code null}，不返回兜底分类。</b>调用方（服务层）必须显式处理它： 报告生成会带着「出现了尚未归类的错误码 X」失败，而不是把这条问题归进某一类
   * 让运营去猜。理由见类注释。
   */
  static Category categoryOf(String code) {
    return code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
  }

  /** 导出与报告页用：按分类名取回分类（供显示中文类名）。 */
  static Category categoryByName(String name) {
    return Category.valueOf(name.trim().toUpperCase(Locale.ROOT));
  }

  /** 合法的严重级别筛选词表（ALL / ERROR / WARNING）。 */
  static final Set<String> SEVERITY_FILTERS = Set.of("ALL", "ERROR", "WARNING");

  /**
   * 这一行是否**永不落库**。
   *
   * <p>直接读 {@link ImportRules.Severity} 而不是在这里写 {@code "ERROR".equals(sev)}： 严重级别的词表以 3.8.1.1
   * 为唯一来源，本模块不复制一份（复制的那份迟早会与上游不同步， 而两者不同步的后果是「报告说能落库、执行阶段把它丢了」）。 未知级别会抛 {@link
   * IllegalArgumentException}，同样是刻意不兜底。
   */
  static boolean blocking(String severity) {
    return ImportRules.Severity.valueOf(severity.trim().toUpperCase(Locale.ROOT))
        == ImportRules.Severity.ERROR;
  }

  /** 行级结论的三个取值（导出文件与报告页共用）。 */
  enum RowConclusion {
    BLOCKING("必须修正"),
    SKIPPABLE("可跳过"),
    CLEAN("校验通过");

    final String label;

    RowConclusion(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  /**
   * 逐行结论的中文名。
   *
   * <p>与 V29 的 {@code import_job_row.outcome} CHECK 同一词表。刻意**不**复用 {@code
   * ImportJobService.statusLabel}：那一套是给<b>任务</b>的（PENDING 叫「已上传」、 FAILED
   * 叫「导入失败」），套到<b>行</b>上会得到「这一行已上传」这种读不通的话。
   */
  static String outcomeLabel(String outcome) {
    if (outcome == null) return "";
    return switch (outcome.trim().toUpperCase(Locale.ROOT)) {
      case "PENDING" -> "待预校验";
      case "VALID" -> "校验通过";
      case "INVALID" -> "校验不通过";
      case "IMPORTED" -> "已导入";
      case "SKIPPED" -> "已跳过";
      case "FAILED" -> "写入失败";
      default -> outcome;
    };
  }

  /**
   * 逐行问题（生成报告时从 {@code import_row_error} 读出的一行）。
   *
   * @param fieldLabel 出错字段在**文件里**的列头文字（整行级问题为空串）
   * @param rawValue 该格在文件里的原始文本
   */
  record Issue(
      int rowNo,
      String fieldKey,
      String fieldLabel,
      String code,
      String category,
      String severity,
      String message,
      String rawValue) {}

  /** 一行被装配好之后的标注内容。 */
  record AnnotatedRow(
      int rowNo,
      List<String> values,
      String fieldLabels,
      String categories,
      String codes,
      String messages,
      String mustFix,
      String conclusion,
      boolean willImport) {

    /** 导出 CSV 的一行：数据列 + 7 个标注列。 */
    List<String> toCsvRow() {
      List<String> out = new java.util.ArrayList<>();
      out.add(String.valueOf(rowNo));
      out.addAll(values);
      out.add(fieldLabels);
      out.add(mustFix);
      out.add(categories);
      out.add(codes);
      out.add(messages);
      out.add(conclusion);
      out.add(willImport ? "是" : "否");
      return out;
    }
  }

  /** 标注列的列头。顺序与 {@link AnnotatedRow#toCsvRow()} 的写出顺序严格一致。 */
  static final List<String> ANNOTATION_HEADERS =
      List.of("问题字段", "必须修正", "问题类别", "问题代码", "问题说明", "本行结论", "是否落库");

  /**
   * 导出文件的列头：行号 + 模板列序的数据列 + 7 个标注列。
   *
   * <p><b>数据列按模板列序（{@code import_template_column.ordinal}）而不是文件原始列序</b>： 文件的原始列序在库里**已经不可复原** ——
   * {@code import_job_row.cells} 是 jsonb， PostgreSQL 的 jsonb 不保留键序（按长度再按字节重排），读回来拿到的顺序不是上传时的顺序。
   * 按模板列序导出反而更实用：它与「下载模板」给出的列头一致， 运营对照模板改字段时不用在两套列序之间来回换算。
   *
   * <p>模板没声明的文件列不导出。这些列本来就**不被导入读取** （{@code ImportJobService.resolveValues} 只按映射读模板声明的列，
   * 多余的表头被显式忽略），所以丢掉它们不丢信息。
   */
  static List<String> exportHeaders(List<String> columnHeaders) {
    List<String> out = new java.util.ArrayList<>();
    out.add("行号");
    out.addAll(columnHeaders);
    out.addAll(ANNOTATION_HEADERS);
    return out;
  }

  /**
   * 逐行问题汇总成行结论。
   *
   * <p><b>行级事实从条级问题归并而来</b>：只要这一行有任意一条 ERROR， 整行就<b>永不落库</b>（{@code ImportRules.Problem.blocking}
   * 的口径），与其它 WARNING 无关。 这条归并是「允许尽力导入吗」这个问题的答案所在 —— 蓝图的回答是不允许，所以 2 条可跳过的提示 + 1 条必须修正的错误 = 仍然不落库。
   */
  static RowConclusion conclude(List<Issue> issues) {
    if (issues.isEmpty()) return RowConclusion.CLEAN;
    return issues.stream().anyMatch(i -> blocking(i.severity()))
        ? RowConclusion.BLOCKING
        : RowConclusion.SKIPPABLE;
  }

  /**
   * 把一行的全部问题装配成标注行。
   *
   * @param values 这一行按模板列序的值（与 {@link #exportHeaders} 的数据列一一对应）
   * @param issues 这一行的问题（已按行号过滤好；无问题时为空列表）
   */
  static AnnotatedRow annotatedRow(int rowNo, List<String> values, List<Issue> issues) {
    RowConclusion conclusion = conclude(issues);

    // 一行可能同时有多个字段、同一字段多个码（「spu_name 必填」+「spu_name 超长」是两条）。
    // 导出成一行时按「字段去重后保序」拼进「问题字段」，
    // 而「问题代码/问题说明」逐条给全 —— 只给一个字段名会让运营看不出这一行到底要改几处。
    List<String> fieldLabels =
        issues.stream().map(Issue::fieldLabel).filter(s -> !s.isBlank()).distinct().toList();
    return new AnnotatedRow(
        rowNo,
        values,
        String.join("；", fieldLabels),
        String.join("；", issues.stream().map(i -> categoryByName(i.category()).label()).toList()),
        String.join("；", issues.stream().map(Issue::code).toList()),
        String.join("；", issues.stream().map(Issue::message).toList()),
        conclusion == RowConclusion.BLOCKING ? "是" : "否",
        conclusion.label(),
        conclusion != RowConclusion.BLOCKING);
  }

  /** 导出种类：带标注的错误文件 / 修正版模板。 */
  static final String KIND_ANNOTATED = "ANNOTATED_ERRORS";

  static final String KIND_FIXED_TEMPLATE = "FIXED_TEMPLATE";

  /**
   * 导出文件的种类名（给运营看的中文）。
   *
   * <p>两者的区别只有一句：<b>校验通过的行给不给</b>。 带标注的错误文件给出全部数据行，所以运营能看到「好行 / 坏行」的比例； 修正版模板只给有问题的行，所以改完这一份就能直接重传。
   */
  static String kindLabel(String kind) {
    return KIND_ANNOTATED.equals(kind) ? "带标注的错误文件" : "修正版模板";
  }

  /**
   * 导出文件的行白名单参数。
   *
   * @param conclusion 这一行的结论（必填）
   * @param severityFilter 只看哪一档问题：ALL / ERROR / WARNING
   * @param problemOnly 是否只含有问题的行（修正版模板为 true，带标注的错误文件为 false）
   */
  static boolean rowIncluded(
      ImportValidationRules.RowConclusion conclusion, String severityFilter, boolean problemOnly) {
    if (problemOnly && conclusion == RowConclusion.CLEAN) return false;
    String filter =
        severityFilter == null || severityFilter.isBlank()
            ? "ALL"
            : severityFilter.trim().toUpperCase(Locale.ROOT);
    return switch (filter) {
      case "ALL" -> true;
      case "ERROR" -> conclusion == RowConclusion.BLOCKING;
      case "WARNING" -> conclusion == RowConclusion.SKIPPABLE;
      default -> throw new IllegalArgumentException("未知的严重级别筛选：" + severityFilter);
    };
  }

  /** 修正版模板只含有问题的行。 */
  static boolean problemOnly(String exportKind) {
    return KIND_FIXED_TEMPLATE.equals(exportKind);
  }
}
