package com.starcon.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3.4.3.1 多语言内容的纯规则层：语言标签、译文正文、三态状态流转、建议处置、批量入参。
 *
 * <p>与 {@link TitleRules} / {@link DescriptionRules} / {@link MediaRules} 同一形状：
 * 无状态静态方法。服务层拒绝时给出的话、前端表单校验、集成测试断言的边界都引同一份实现。
 *
 * <p><b>状态机只在这一处定义。</b>「待翻 / 已翻 / 已校对」是蓝图点名的输出， 而它的每一次流转都要能被回答「这一步是谁做的、依据是什么」。散落在服务层里 就会出现「前端只改
 * status 字段就能把一条空行标成已校对」这种绕过校对的做法， 所以合法迁移在这里枚举，V22 的触发器兜底。
 *
 * <p><b>与 {@link DescriptionRules} 的 locale 词表同源但不是同一件事</b>：那边校验的是
 * 「描述模板的译文语言」，这边是「商品档案的多语言内容语言」。规则实现可以复用， 落到的表与服务不同（见 V22 文件头边界说明）。
 */
public final class TranslationRules {
  private TranslationRules() {}

  // ============================================================ 词表（与 V22 的 CHECK 逐字一致）

  /** 译文状态三态：待翻 / 已翻 / 已校对。与 V22 的 status CHECK 逐字一致。 */
  public static final List<String> STATUSES = List.of("PENDING", "TRANSLATED", "REVIEWED");

  /** 译文来源：人工 / AI 采纳 / 导入。与 V22 的 origin CHECK 逐字一致。 */
  public static final List<String> ORIGINS = List.of("MANUAL", "AI", "IMPORTED");

  /** 建议来源。与 translation_suggestion.origin CHECK 逐字一致。 */
  public static final List<String> SUGGESTION_ORIGINS = List.of("AI", "MANUAL", "IMPORTED");

  /** 建议处置状态。ADOPTED / REJECTED 是终态（V22 触发器钉住）。 */
  public static final List<String> SUGGESTION_STATUSES = List.of("PENDING", "ADOPTED", "REJECTED");

  /** 批量动作三值。与 translation_batch.action CHECK 逐字一致。 */
  public static final List<String> BATCH_ACTIONS =
      List.of("CREATE_PENDING", "ADOPT_SUGGESTIONS", "MARK_REVIEWED");

  /** 逐条结果三值。与 translation_batch_item.outcome CHECK 逐字一致。 */
  public static final List<String> BATCH_OUTCOMES = List.of("SUCCEEDED", "SKIPPED", "REJECTED");

  public static final List<String> BATCH_STATUSES = List.of("SUCCEEDED", "PARTIAL", "FAILED");

  /** 主体类型：SPU 或 SKU。 */
  public static final List<String> TARGET_TYPES = List.of("SPU", "SKU");

  /** 语言需求来源。与 translation_locale.source CHECK 逐字一致。 */
  public static final List<String> LOCALE_SOURCES = List.of("MANUAL", "SHOP_SITE");

  private static final Map<String, String> STATUS_LABELS =
      Map.of("PENDING", "待翻", "TRANSLATED", "已翻", "REVIEWED", "已校对");

  private static final Map<String, String> ORIGIN_LABELS =
      Map.of("MANUAL", "人工撰写", "AI", "AI 翻译", "IMPORTED", "导入");

  private static final Map<String, String> SUGGESTION_STATUS_LABELS =
      Map.of("PENDING", "待处置", "ADOPTED", "已采纳", "REJECTED", "已驳回");

  private static final Map<String, String> BATCH_ACTION_LABELS =
      Map.of(
          "CREATE_PENDING", "补齐待翻占位",
          "ADOPT_SUGGESTIONS", "批量采纳 AI 建议",
          "MARK_REVIEWED", "批量标记已校对");

  private static final Map<String, String> OUTCOME_LABELS =
      Map.of("SUCCEEDED", "成功", "SKIPPED", "已跳过", "REJECTED", "未执行");

  private static final Map<String, String> BATCH_STATUS_LABELS =
      Map.of("SUCCEEDED", "全部成功", "PARTIAL", "部分未成功", "FAILED", "全部未成功");

  private static final Map<String, String> TARGET_TYPE_LABELS = Map.of("SPU", "SPU", "SKU", "SKU");

  private static final Map<String, String> LOCALE_SOURCE_LABELS =
      Map.of("MANUAL", "人工维护", "SHOP_SITE", "店铺站点推导");

  public static String statusLabel(String value) {
    return STATUS_LABELS.getOrDefault(value, value);
  }

  public static String originLabel(String value) {
    return ORIGIN_LABELS.getOrDefault(value, value);
  }

  public static String suggestionStatusLabel(String value) {
    return SUGGESTION_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String batchActionLabel(String value) {
    return BATCH_ACTION_LABELS.getOrDefault(value, value);
  }

  public static String outcomeLabel(String value) {
    return OUTCOME_LABELS.getOrDefault(value, value);
  }

  public static String batchStatusLabel(String value) {
    return BATCH_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String targetTypeLabel(String value) {
    return TARGET_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String localeSourceLabel(String value) {
    return LOCALE_SOURCE_LABELS.getOrDefault(value, value);
  }

  // ============================================================ 容量上限

  /** 商品名称长度上限 200 码点。与 V1 的 spu.name varchar(200) 同宽。 */
  public static final int MAX_NAME_LENGTH = 200;

  /**
   * 商品描述长度上限 8000 字符。
   *
   * <p>比 3.4.2.2 的模板正文（20000）紧：模板正文是拼装源，单条译文通常只是一两段， 8000 足够一整页多语言详情。蓝图标了本模块输出的是「名称与描述」，不是说明书。
   */
  public static final int MAX_DESCRIPTION_LENGTH = 8000;

  /** 语言标签长度上限 16（BCP-47 简写如 zh-CN / en-US）。与 V21 同一宽度。 */
  public static final int MAX_LOCALE_LENGTH = 16;

  /** 语言展示名长度上限 60（与 V22 的 locale_name varchar(60) 同宽）。 */
  public static final int MAX_LOCALE_NAME_LENGTH = 60;

  /** AI 翻译建议的批次归组键长度上限 64。 */
  public static final int MAX_BATCH_REF_LENGTH = 64;

  /** 模型名长度上限 100（自由文本，不跨服务建 FK）。 */
  public static final int MAX_MODEL_NAME_LENGTH = 100;

  /** 单页最大行数。 */
  public static final int MAX_PAGE_SIZE = 200;

  /**
   * 单次批量最多 200 个商品。
   *
   * <p>与 {@link DescriptionRules#MAX_SKUS_PER_RENDER} 同一量纲，理由也相同： 逐条结果列表要能被人逐条核对，而「哪 8
   * 个没成」正是批量操作唯一有用的输出。 这三件批量动作都是短事务（几百行的写入），所以同步执行。
   */
  public static final int MAX_ITEMS_PER_BATCH = 200;

  /** 单次批量最多指定 10 种期望语言（CREATE_PENDING 展开成商品 × 语言）。 */
  public static final int MAX_LOCALES_PER_BATCH = 10;

  /** 单次最多登记 500 条 AI 建议（18 一次翻一个批次的回写量级）。 */
  public static final int MAX_SUGGESTIONS_PER_SUBMIT = 500;

  /** 一次翻译完整度检查最多指定 20 种语言（比 V21 的 10 宽：本模块的语言来自店铺站点，可能更多）。 */
  public static final int MAX_COVERAGE_LOCALES = 20;

  // ============================================================ 语言标签

  /**
   * 语言标签校验（BCP-47 简写）。
   *
   * <p>只校验形状不校验语义：catalog 不维护语言字典字典表， 所以 {@code zh-CN} / {@code en-US} / {@code pt-BR} / {@code ja}
   * 都被接受， 而 {@code 中文} / {@code en_US}（下划线）/ {@code e}（太短）被拒—— 下划线写法会让「同一个语言的两种拼法」各建一条译文， {@code
   * UNIQUE(tenant_id, spu_id, locale)} 因此形同虚设。
   *
   * <p>与本类其它 {@code *Problem} 方法同一契约：合法返回 null，非法返回原因文本。 规范化后的值请用 {@link #normalizeLocale(String)}
   * 取。
   */
  public static String localeProblem(String locale) {
    String value = MediaRules.normalize(locale);
    if (value.isEmpty()) return "语言不能为空（例如 zh-CN / en-US）";
    if (value.length() > MAX_LOCALE_LENGTH) return "语言标签最多 " + MAX_LOCALE_LENGTH + " 个字符";
    if (!value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?"))
      return "语言标签请用 BCP-47 简写（zh-CN / en-US / ja-JP）：" + "不要用中文名或下划线写法，同一个语言的两种拼法会各建一条译文";
    return null;
  }

  /**
   * 规范化后的语言标签（BCP-47 规范写法：语言小写 + 地区大写）。
   *
   * <p><b>入库与查询必须走同一份规范化</b>，否则筛选会静默失效： 入库存 "zh-CN"、查询用 {@code toUpperCase} 变成 "ZH-CN"，那条筛选就永远匹配不到，
   * 而页面上表现为「明明有数据却查不出来」。与 {@link DescriptionRules#normalizeLocale} 同一份口径，两边不能各写一套。
   */
  public static String normalizeLocale(String locale) {
    String value = MediaRules.normalize(locale);
    if (value.isEmpty()) return "";
    int dash = value.indexOf('-');
    if (dash < 0) return value.toLowerCase(Locale.ROOT);
    return value.substring(0, dash).toLowerCase(Locale.ROOT)
        + "-"
        + value.substring(dash + 1).toUpperCase(Locale.ROOT);
  }

  public static String localeNameProblem(String localeName) {
    String value = MediaRules.normalize(localeName);
    if (value.length() > MAX_LOCALE_NAME_LENGTH) return "语言名称最多 " + MAX_LOCALE_NAME_LENGTH + " 个字符";
    return null;
  }

  /** 一次翻译完整度检查的语言数上限。 */
  public static String coverageLocalesSizeProblem(int size) {
    if (size > MAX_COVERAGE_LOCALES) return "一次最多检查 " + MAX_COVERAGE_LOCALES + " 种语言的翻译完整度";
    return null;
  }

  // ============================================================ 译文正文

  /**
   * 规范化译文正文：折叠连续空白 + 去首尾空白。
   *
   * <p>折叠空白对多语言尤其重要：同一句德语译文有人写成两行有人写成一行， 不规范化就会在完整度报表上被当成两份不同的内容。
   */
  public static String normalizeText(String text) {
    String value = MediaRules.normalize(text);
    if (value.isEmpty()) return "";
    return value.replaceAll("\\s+", " ");
  }

  /**
   * 译文正文校验。
   *
   * <p>与 {@link DescriptionRules#bodyProblem} 有一处**刻意的差别**：那边不要求至少一个占位符，
   * 但会拒绝未配平的花括号；这边**连孤立的左花括号都拒**—— 3.4.2.2 的模板机制在商品多语言内容里根本不该出现， 一个 {@code {} 开头} 的商品名发到平台上就是
   * 3.4.2.2 的模板没被填。
   */
  public static String nameProblem(String name) {
    String value = normalizeText(name);
    if (value.length() > MAX_NAME_LENGTH) return "商品名称最多 " + MAX_NAME_LENGTH + " 个字符";
    if (value.contains("{") || value.contains("}"))
      return "商品名称里出现了花括号：{...} 是 3.4.2.2 描述模板的占位符机制，" + "商品多语言名称是**已填好的成品**，请直接写出实际名称";
    return null;
  }

  public static String descriptionProblem(String description) {
    String value = normalizeText(description);
    if (value.length() > MAX_DESCRIPTION_LENGTH) return "商品描述最多 " + MAX_DESCRIPTION_LENGTH + " 个字符";
    if (value.contains("{") || value.contains("}"))
      return "商品描述里出现了花括号：{...} 是 3.4.2.2 描述模板的占位符机制，" + "本表的描述是**已填好的成品**，占位符只允许出现在模板正文里";
    return null;
  }

  /**
   * 三态状态校验：**只校验取值合法，不校验流转合法**。
   *
   * <p>流转由 {@link #statusTransitionProblem} 单独判——两者必须分开， 否则「PENDING 不能直接跳到 REVIEWED」这条规则会被一个只改
   * status 的接口绕过。
   */
  public static String statusProblem(String status) {
    String value = MediaRules.normalizeUpper(status);
    if (!STATUSES.contains(value))
      return "翻译状态只能是 " + String.join(" / ", STATUSES) + "（待翻 / 已翻 / 已校对）";
    return null;
  }

  public static String originProblem(String origin) {
    String value = MediaRules.normalizeUpper(origin);
    if (!ORIGINS.contains(value))
      return "译文来源只能是 " + String.join(" / ", ORIGINS) + "（人工 / AI 翻译 / 导入）";
    return null;
  }

  /**
   * 状态流转校验：合法返回 null，非法返回人话原因。
   *
   * <p>三条规则：
   *
   * <ul>
   *   <li>同状态 → 同状态是幂等重写，允许（改正文不改状态是正常编辑）。
   *   <li>{@code PENDING → REVIEWED} 不允许：中间那一步「已翻」正是 「有没有人真的读过」的信号，跳级等于把校对这一步抹掉。
   *   <li>任何 → {@code PENDING} 且正文非空不允许：正文不会因为改状态就消失， 静默退回待翻会让已填好的文案从刊登可取范围里消失且不留痕迹。
   * </ul>
   */
  public static String statusTransitionProblem(
      String from, String to, String name, String description) {
    String target = MediaRules.normalizeUpper(to);
    if (statusProblem(target) != null) return statusProblem(target);
    String source = MediaRules.normalizeUpper(from);
    if ("PENDING".equals(source) && "REVIEWED".equals(target))
      return "「待翻」不能直接标成「已校对」：中间那一步「已翻」正是有没有人真的读过的信号。" + "要直接校对，请先填入译文（保存后状态是「已翻」，再标校对）";
    boolean emptyName = MediaRules.normalize(name).isEmpty();
    boolean emptyDescription = MediaRules.normalize(description).isEmpty();
    if ("PENDING".equals(target) && !(emptyName && emptyDescription))
      return "已经带正文的译文不能退回「待翻」：正文不会因为改状态就消失，" + "而静默退回待翻会让这条内容从刊登可取范围里消失且不留痕迹。" + "要停止维护这条译文请直接删除它";
    return null;
  }

  /**
   * 保存译文时的正文一致性校验（状态与正文必须对得上）。
   *
   * <p>V22 里有三条 CHECK 表达同一件事，这里是应用层那一层，好处是能在写库之前 给出「这条德语还没有名称，不能标成已校对」而不是一条约束名。
   */
  public static String translationTextProblem(String status, String name, String description) {
    String target = MediaRules.normalizeUpper(status);
    boolean emptyName = MediaRules.normalize(name).isEmpty();
    boolean emptyDescription = MediaRules.normalize(description).isEmpty();
    if ("PENDING".equals(target)) {
      if (!emptyName || !emptyDescription) return "「待翻」要求名称与描述都为空：还没有文本就谈不上已翻译。" + "要填译文请把状态改成「已翻」";
      return null;
    }
    if (emptyName && emptyDescription) return "「" + statusLabel(target) + "」要求至少有名称或描述其中之一有内容";
    if ("REVIEWED".equals(target) && emptyName) return "「已校对」要求商品名称非空：空名称进不了刊登，标成已校对是自欺欺人";
    return null;
  }

  // ============================================================ 语言需求

  public static String localeSourceProblem(String source) {
    String value = MediaRules.normalizeUpper(source);
    if (!LOCALE_SOURCES.contains(value))
      return "语言需求来源只能是 " + String.join(" / ", LOCALE_SOURCES) + "（人工维护 / 店铺站点推导）";
    return null;
  }

  /** 单次批量最多 200 个商品。 */
  public static String itemsSizeProblem(int size) {
    if (size == 0) return "请至少选择一个商品";
    if (size > MAX_ITEMS_PER_BATCH) return "单次批量最多 " + MAX_ITEMS_PER_BATCH + " 个商品";
    return null;
  }

  /** 单次批量最多指定 10 种期望语言。 */
  public static String batchLocalesSizeProblem(int size) {
    if (size > MAX_LOCALES_PER_BATCH)
      return "单次批量最多指定 " + MAX_LOCALES_PER_BATCH + " 种语言（商品 × 语言会相乘）";
    return null;
  }

  /** 单次最多登记 500 条 AI 建议。 */
  public static String suggestionsSizeProblem(int size) {
    if (size == 0) return "请至少提交一条翻译建议";
    if (size > MAX_SUGGESTIONS_PER_SUBMIT) return "单次最多提交 " + MAX_SUGGESTIONS_PER_SUBMIT + " 条翻译建议";
    return null;
  }

  public static String suggestionStatusProblem(String status) {
    String value = MediaRules.normalizeUpper(status);
    if (!SUGGESTION_STATUSES.contains(value))
      return "建议处置状态只能是 " + String.join(" / ", SUGGESTION_STATUSES) + "（待处置 / 已采纳 / 已驳回）";
    return null;
  }

  public static String suggestionOriginProblem(String origin) {
    String value = MediaRules.normalizeUpper(origin);
    if (!SUGGESTION_ORIGINS.contains(value))
      return "建议来源只能是 " + String.join(" / ", SUGGESTION_ORIGINS) + "（AI / 人工草拟 / 导入）";
    return null;
  }

  public static String batchActionProblem(String action) {
    String value = MediaRules.normalizeUpper(action);
    if (!BATCH_ACTIONS.contains(value))
      return "批量动作只能是 " + String.join(" / ", BATCH_ACTIONS) + "（补齐待翻占位 / 批量采纳 AI 建议 / 批量标记已校对）";
    return null;
  }

  public static String targetTypeProblem(String targetType) {
    String value = MediaRules.normalizeUpper(targetType);
    if (!TARGET_TYPES.contains(value)) return "主体类型只能是 " + String.join(" / ", TARGET_TYPES);
    return null;
  }

  public static String batchRefProblem(String batchRef) {
    String value = MediaRules.normalize(batchRef);
    if (value.length() > MAX_BATCH_REF_LENGTH) return "批次归组键最多 " + MAX_BATCH_REF_LENGTH + " 个字符";
    return null;
  }

  public static String modelNameProblem(String modelName) {
    String value = MediaRules.normalize(modelName);
    if (value.length() > MAX_MODEL_NAME_LENGTH) return "模型名最多 " + MAX_MODEL_NAME_LENGTH + " 个字符";
    return null;
  }

  /** 驳回理由非空校验：不给理由的驳回让下一轮 AI 翻译无从改进。 */
  public static String rejectReasonProblem(String reason) {
    String value = MediaRules.normalize(reason);
    if (value.isEmpty()) return "驳回必须写理由：没有理由的驳回让下一轮 AI 翻译无从改进，" + "同一批商品会被反复翻成同一个错法";
    if (value.length() > 500) return "驳回理由最多 500 个字符";
    return null;
  }
}
