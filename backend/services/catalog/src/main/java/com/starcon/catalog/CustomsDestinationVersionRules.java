package com.starcon.catalog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 3.7.1.2 目的地报关版本的<b>纯规则</b>：目的国归一（含拒绝通配）、目的地税号格式、
 * 认证码归一、生效区间判定，以及<b>覆盖与继承的合成</b>。
 *
 * <p>抽成纯函数的理由同 {@link CustomsAttributeRules}：这些判定决定的是
 * 「这批货发到德国该报哪个税号」，代价出现在<b>真实的清关退单、扣货与罚款</b>上，
 * 而判定发生在录入那一刻。所以它们必须能不碰数据库逐条跑单元测试。
 *
 * <p><b>本类不落任何派生值</b>（V95 第二节）：合成后的申报资料与关税成本一律现算。
 * 完税价格 = 成交价 × 汇率 × 税率，而<b>成交价是 10 报关的事实、汇率与税率是
 * 12 财务的事实</b>；落一列等于替两个服务选定口径。
 *
 * <p><b>五条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>目的国不可通配（规则 B5）</b>。仓库里有 {@code '*'} 的先例
 *       （V85 / V84 / V92），但那三处的 {@code *} 是<b>筛选</b>语义
 *       （「这次申报可以发往任何国家」），而本表的目的地是<b>申报资料的口径归属</b>。
 *       一条 {@code '*'} 的申报资料正是蓝图要消灭的「用一套打通全球」。
 *   <li><b>覆盖只覆盖两格</b>（V95 第二节）：目的地税号与目的地申报品名。
 *       法定计量单位与申报要素<b>只来自基础属性</b> —— 复制它们就是第二个事实来源。
 *   <li><b>享惠要两边都满足</b>：基础属性说「有资格」，目的地版本说「该目的地承认与否」，
 *       两个布尔语义不同，压成一列就答不出「有资格但这个目的地不认」。
 *   <li><b>半开区间 [from, to)</b>（规则 R1）：{@code [10:00,12:00)} 与
 *       {@code [12:00,14:00)} 在 12:00 处<b>不</b>重叠。
 *   <li><b>没有生效版本就是没有</b>，不是一个「继承基础」的隐含答案。
 *       回退等于让运营漏维护的目的地被基础属性静默接管。
 * </ol>
 */
public final class CustomsDestinationVersionRules {
  /** 版本状态的两档。与 {@code customs_destination_version.status} 的 CHECK 同一词表。 */
  public static final String DRAFT = "DRAFT";

  public static final String PUBLISHED = "PUBLISHED";

  public static final java.util.List<String> STATUSES = java.util.List.of(DRAFT, PUBLISHED);

  /** 「这一格来自基础属性」的值域。与 {@link CustomsAttributeRules#SOURCE_MODULE} 同口径。 */
  public static final String SOURCE_BASE = "MODULE_3_7_1_1";

  /** 「这一格来自目的地版本」的来源标记。 */
  public static final String SOURCE_DESTINATION = "MODULE_3_7_1_2";

  /**
   * 供 10 报关回填的「申报要素齐备」状态值。
   *
   * <p><b>沿用 3.7.1.1 的常量而不是新造一个</b>：合成链路里的申报要素
   * <b>原封不动地来自基础属性</b>（V95 第二节），所以它的齐备状态就是
   * 3.7.1.1 判出来的那一个 —— 新造一个 {@code MODULE_3_7_1_2_COMPLETE} 会让
   * 下游分不清「要素齐备是本模块判的」还是「是 3.7.1.1 判的」。
   */
  public static final String ELEMENT_STATUS_COMPLETE = CustomsAttributeRules.ELEMENT_STATUS_COMPLETE;

  private CustomsDestinationVersionRules() {}

  // ============================================================ 规则 B5：目的地国家码

  /** 目的地国家码格式：两位大写字母。 */
  public static boolean validDestination(String destination) {
    return destination != null && destination.matches("[A-Z]{2}");
  }

  /**
   * 归一化目的地国家码：<b>trim + 转大写 + 显式拒绝 {@code '*'}</b>。
   *
   * <p><b>拒绝通配是本类最重要的一行</b>，理由见类注释纪律 1 与 V95 第五节的完整论证。
   * 这里在应用层就拦一次（给可读的中文错误），DB 层的 CHECK 与触发器是兜底
   * 而不是唯一防线 —— 一条裸 SQL 改不出可读的中文报错，而运营看不懂「23514」。
   *
   * <p><b>转大写是对的，国家码转小写是错的</b>：ISO 3166-1 alpha-2 定义的就是大写，
   * 用户从任何来源粘贴都可能带来小写 / 前后空格，而它落库后在页面上
   * 「看起来完全正常」，直到清关时才发现归类错了税率。
   *
   * @throws IllegalArgumentException 目的地缺失、通配或格式非法时抛出可读的中文原因
   */
  public static String normalizeDestination(String destination) {
    String trimmed = destination == null ? "" : destination.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty()) throw new IllegalArgumentException("请填写目的地国家");
    // 归一化成 '*' 与字面量 '*' 都要拒：' * ' 与 '*' 在「不限目的地」这件事上是同一个意思。
    if ("*".equals(trimmed) || "*".equals(trimmed.trim().replace(" ", "")))
      throw new IllegalArgumentException(
          "目的地不能写成「*」（不限目的地）：不同目的地必须用不同版本，不能用一套申报资料打通全球 —— "
              + "同一份资料申报到欧盟会因归类不符被退单，申报到美国会因 FCC 要求缺失被扣货，"
              + "而扣货与罚款由卖家承担。请为这个目的地单独建一个版本（例如 DE 一个、US 一个）");
    if (!validDestination(trimmed))
      throw new IllegalArgumentException(
          "目的地「"
              + trimmed
              + "」必须是两位大写字母的 ISO 3166-1 alpha-2 代码（如 DE / US / GB）："
              + "目的地决定了这一版用哪个税号、哪个申报品名与哪套认证要求，"
              + "填错等于把某个国家的申报资料报给了另一个国家");
    return trimmed;
  }

  // ============================================================ 规则 B4：目的地税号

  /** 目的地税号格式：<b>空串（沿用基础）合法</b>；非空时 6–10 位纯数字，与 A1 同口径。 */
  public static boolean validDestinationHsCode(String hsCode) {
    return hsCode != null && (hsCode.isEmpty() || hsCode.matches("[0-9]{6,10}"));
  }

  /**
   * 归一化目的地税号：<b>可以为空</b>（空 = 沿用基础 HS 编码，V95 第二节）。
   *
   * <p><b>只去空格，不做别的「善意修正」</b>：自动补零、把字母换成数字这类修正
   * 会把一个明显的错码改成一个「看起来对」的错码 —— 而错码要到清关退单时才发现，
   * 代价是罚款。所以这里要么原样通过，要么报错。
   *
   * <p><b>与 3.7.1.1 的 {@code normalizeHsCode} 唯一的差别就是允许空串</b>：
   * 那边空串是「没填」（必填字段），这边空串是「与基础一致」这个<b>有意义的值</b>。
   */
  public static String normalizeDestinationHsCode(String hsCode) {
    String trimmed = hsCode == null ? "" : hsCode.trim();
    if (trimmed.isEmpty()) return "";
    if (!validDestinationHsCode(trimmed))
      throw new IllegalArgumentException(
          "目的地税号「"
              + trimmed
              + "」必须是 6–10 位纯数字（如 851712 或 8517123090），或者留空表示沿用基础 HS 编码："
              + "带字母或位数不对的是别的税则体系的编号，不是这一列该装的编码。"
              + "错码会直接导致申报失败与罚款，而扣货与罚款由卖家承担");
    return trimmed;
  }

  /**
   * 归一化目的地申报品名：<b>空串 = 沿用基础申报品名</b>，与税号同一套语义。
   *
   * <p><b>不照搬销售标题</b>（V85 第 207–208 行：申报口径不照搬销售标题，
   * 混用是常见被退单原因），所以它是独立维护的字段而不是读销售标题。
   */
  public static String normalizeDestinationDeclaredName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.length() > 500)
      throw new IllegalArgumentException(
          "目的地申报品名最多 500 字：它会原样出现在报关单上，截断成另一个商品比报错糟得多");
    return trimmed;
  }

  /**
   * 归一化版本号：从 1 起（DB 有 CHECK 钉死 {@code version_no>0}）。
   *
   * <p>运营心智里是「v1 / v2 / 2026 春季」，但这里存 integer：
   * 版本号的唯一性由 {@code UNIQUE(tenant_id,sku_id,destination_country_code,version_no)}
   * 保证，而区间判定（B1）需要能按版本排序出「哪一个接替了哪一个」。
   * 字符串版本号（「2026 春季」「v2」）两者都做不到。
   */
  public static int normalizeVersionNo(int versionNo) {
    if (versionNo < 1)
      throw new IllegalArgumentException("版本号必须从 1 开始（同一 SKU 同一目的地内唯一）");
    return versionNo;
  }

  // ============================================================ 认证要求

  /**
   * 归一化认证码：<b>trim + 转大写</b>，不能为空。
   *
   * <p><b>转大写是对的</b>：认证代码是法定标识符（CE / FCC / RoHS / UKCA / UN38.3），
   * 大小写不参与含义，而运营从任何地方粘贴都可能带来不一致的大小写；
   * 不归一化的话「RoHS」与「ROHS」会在页面上被当成两条要求，
   * 16 合规的台账上同一个认证出现两行，而清关时只会认其中一个。
   */
  public static String normalizeRequirementCode(String code) {
    String trimmed = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty())
      throw new IllegalArgumentException("请填写认证代码（例：CE / FCC / RoHS / UKCA / UN38.3）");
    if (trimmed.length() > 64)
      throw new IllegalArgumentException(
          "认证代码最多 64 个字符：危包证这类代码带版本号与分隔符（如 UN38.3），"
              + "但更长的多半是粘错了整段文字");
    return trimmed;
  }

  // ============================================================ 规则 R1 / R4：生效区间

  /**
   * 两个半开区间 {@code [aFrom, aTo)} 与 {@code [bFrom, bTo)} 是否重叠（规则 R2 的判定）。
   *
   * <p><b>{@code to = null} 表示 +∞</b>（规则 R1），所以 null 端点永远不收窄区间。
   *
   * <p><b>半开比较的写法</b>：{@code aFrom < bTo && bFrom < aTo}。
   * 用严格小于号（而不是 {@code <=}）正是为了让 {@code [10:00,12:00)} 与
   * {@code [12:00,14:00)} 在 12:00 处<b>不</b>重叠 —— 这是相邻版本接替的标准形状，
   * 用闭区间会把「刚刚好接替」误判成冲突，运营每次换版都要被迫留一小时空档。
   *
   * <p>与 DB 侧 {@code tstzrange(from, to, '[)') && ...} 的语义逐字对齐：
   * 集成测试 {@code 相邻区间在前一版终点处不重叠} 证明两边一致。
   */
  public static boolean overlaps(
      Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
    return before(aFrom, bTo) && before(bFrom, aTo);
  }

  /** 半开区间包含判定（规则 R4）：{@code from <= at < to}，{@code to=null} 表示 +∞。 */
  public static boolean covers(Instant from, Instant to, Instant at) {
    return !at.isBefore(from) && (to == null || at.isBefore(to));
  }

  /** a 是否严格早于 b；{@code b == null} 表示 +∞，任何时刻都严格早于它。 */
  private static boolean before(Instant a, Instant b) {
    return b == null || a.isBefore(b);
  }

  /**
   * 解析并归一化时间戳输入（{@code ?::timestamptz} 的入参）。
   *
   * <p><b>精度收到毫秒</b>：PG 的 {@code timestamptz} 是微秒精度，
   * 前端来回序列化时秒级以下会被截断，不收窄就会出现「保存后区间变了」。
   *
   * @return 空串（{@code null}）返回 {@code null}，表示「调用方没传」
   */
  public static Instant parseInstant(String value, String field) {
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) return null;
    try {
      return OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          field
              + " 不是合法的带时区时间（应为 2026-03-01T00:00:00Z 或 2026-03-01T08:00:00+08:00）："
              + text);
    }
  }

  /** {@code timestamptz → OffsetDateTime}，供 {@code ?::timestamptz} 绑定。 */
  public static OffsetDateTime toOffsetDateTime(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  /**
   * 区间有序性校验（{@code to 必须 > from}）。
   *
   * <p>与 DB 的 {@code customs_destination_version_range_ordered} 同一判定。
   * 空区间没有意义（等于「从不生效」），左闭右开所以必须严格大于。
   */
  public static void checkRangeOrdered(Instant from, Instant to) {
    if (from == null) throw new IllegalArgumentException("请填写生效起点");
    if (to != null && !to.isAfter(from))
      throw new IllegalArgumentException(
          "生效终点必须晚于生效起点（当前：起点 "
              + from
              + "，终点 "
              + (to == null ? "空" : to.toString())
              + "）：生效区间是左闭右开的 [起点, 终点)，空区间等于「从不生效」");
  }

  // ============================================================ 覆盖 vs 继承（V95 第二节，本模块最要紧的一条）

  /**
   * 合成所需的输入：基础侧来自 3.7.1.1，目的地侧来自本模块。
   *
   * <p><b>参数本身就是这条纪律的一部分</b>：法定计量单位与申报要素<b>不出现在
   * 目的地侧</b>，所以调用方在类型层面就无法把它们传进来 ——
   * 「本模块不复制申报要素」不是一句注释，是一个类型签名。
   */
  public record MergeInput(
      String baseHsCode,
      String baseDeclaredName,
      String baseStatutoryUnitCode,
      String baseOriginCountryCode,
      boolean basePreferentialEligible,
      String basePreferenceAgreement,
      String destinationHsCode,
      String destinationDeclaredName,
      boolean destinationPreferentialApplicable) {}

  /**
   * 合成结果。
   *
   * <p>每一格都带 {@code *Source}（来自哪一层）与 {@code *Overridden}（这一格
   * 是不是被目的地版本改过）：继承与覆盖在页面上长得一模一样，
   * 而运营漏维护一个目的地正是从「看不出哪里被改过」开始的。
   */
  public record Merged(
      String hsCode,
      String hsCodeSource,
      boolean hsCodeOverridden,
      String declaredName,
      String declaredNameSource,
      boolean declaredNameOverridden,
      String statutoryUnitCode,
      String statutoryUnitSource,
      String originCountryCode,
      boolean preferentialOriginEligible,
      boolean preferentialApplicable,
      boolean preferentialGranted,
      String preferenceAgreement) {}

  /**
   * <b>覆盖与继承的唯一实现</b>（V95 第二节）。
   *
   * <p>逐条规则：
   *
   * <ul>
   *   <li><b>目的地税号</b>：目的地版本非空则用目的地版本，否则用基础 {@code hs_code}；
   *   <li><b>申报品名</b>：目的地版本非空则用目的地版本，否则用基础 {@code declared_name}；
   *   <li><b>法定计量单位 / 原产地 / 申报要素</b>：<b>只能来自基础属性</b>，
   *       本方法把它们原样透传且 {@code statutoryUnitSource} 恒为 {@link #SOURCE_BASE} ——
   *       目的地版本在数据形状上就碰不到它们（{@link MergeInput} 里没有这两格）；
   *   <li><b>享惠</b>：{@code granted = baseEligible && destinationApplicable}，
   *       <b>两者都满足才为 true</b>。
   * </ul>
   *
   * <p><b>空目的地税号 / 空目的地品名 = 继承，不是「这一格没维护」</b>：
   * 这是本方法唯一一处「空值有正面含义」的地方，也正是它与
   * {@link CustomsAttributeRules#normalizeHsCode}（那边空串是「没填」）的差别。
   *
   * <p><b>为什么不在这里报错</b>：基础属性缺失 / 未审核通过是<b>上游闸门</b>的事，
   * 由 {@code CustomsAttributeService.declarationReady} 统一判；本方法只负责
   * 「两边都有值时怎么合」。把两件事混在一个方法里会让闸门的报错被合并逻辑的
   * 空值处理吃掉 —— 而闸门必须是硬边界。
   */
  public static Merged merge(MergeInput input) {
    String destinationHsCode =
        input.destinationHsCode() == null ? "" : input.destinationHsCode().trim();
    String destinationName =
        input.destinationDeclaredName() == null ? "" : input.destinationDeclaredName().trim();
    boolean hsOverridden = !destinationHsCode.isEmpty();
    boolean nameOverridden = !destinationName.isEmpty();

    return new Merged(
        hsOverridden ? destinationHsCode : input.baseHsCode(),
        hsOverridden ? SOURCE_DESTINATION : SOURCE_BASE,
        hsOverridden,
        nameOverridden ? destinationName : input.baseDeclaredName(),
        nameOverridden ? SOURCE_DESTINATION : SOURCE_BASE,
        nameOverridden,
        // 法定计量单位：**原样透传，来源恒为基础属性**。目的地版本碰不到它（MergeInput 里就没有）。
        input.baseStatutoryUnitCode(),
        SOURCE_BASE,
        // 原产地同理：它决定适用税率与协定，而协定本身是原产地事实，不是目的地事实。
        input.baseOriginCountryCode(),
        input.basePreferentialEligible(),
        input.destinationPreferentialApplicable(),
        // **享惠：两者都满足才成立**（V95 第二节末段）。
        input.basePreferentialEligible() && input.destinationPreferentialApplicable(),
        input.basePreferenceAgreement() == null ? "" : input.basePreferenceAgreement());
  }

  // ============================================================ 展示口径

  /** 版本状态的中文标签。页面上两档两色：草稿中性、已发布成功。 */
  public static String statusLabel(String status) {
    if (status == null) return "未维护";
    return switch (status) {
      case DRAFT -> "草稿";
      case PUBLISHED -> "已发布";
      default -> status;
    };
  }

  /**
   * 生效状态的标签：<b>与 {@link #statusLabel} 分开</b>。
   *
   * <p>「已发布」与「此刻生效」是两件事：一个已发布的版本可能早已被下一个接替。
   * 把两者塌缩成一个标签，运营会把历史版本当成现在该用的版本 —— 那就是拿旧税号申报。
   */
  public static String effectiveLabel(boolean published, boolean effective) {
    if (!published) return "未生效（草稿不可被报关引用）";
    return effective ? "此刻生效" : "历史版本（已被接替或区间未到）";
  }

  /** 来源标记的中文标签，用于页面上把「继承」与「覆盖」显式说出来。 */
  public static String sourceLabel(String source) {
    return SOURCE_DESTINATION.equals(source) ? "目的地版本覆盖" : "继承基础报关属性";
  }
}
