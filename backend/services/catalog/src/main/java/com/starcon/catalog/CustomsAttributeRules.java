package com.starcon.catalog;

import static com.starcon.catalog.CustomsAttributeModels.CustomsProfile;
import static com.starcon.catalog.CustomsAttributeModels.DeclarationElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3.7.1.1 HS 编码与申报要素的<b>纯规则</b>：HS 编码格式校验、享惠一致性、
 * 申报要素齐备性、可报关性判定、国家码归一。
 *
 * <p>抽成纯函数的理由同 {@link PhysicalSafetyRules}：这些判定决定的是
 * 「这个 SKU 能不能拿去报关」，代价出现在<b>真实的报关单与罚款</b>上，
 * 而判定发生在录入那一刻。所以它们必须能不碰数据库逐条跑单元测试。
 *
 * <p><b>本类不落任何派生值</b>（V94 零之三）：可报关性、税率、完税价格全部现算。
 * 完税价格 = 成交价 × 汇率 × 税率，而<b>成交价是 10 报关的事实、汇率与税率是
 * 12 财务与 10.5.1.3 的事实</b>；落一列等于替三个服务选定口径。
 *
 * <p><b>四条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>HS 编码必须 6–10 位纯数字</b>（规则 A1）。蓝图点名的罚款来源。
 *   <li><b>享惠资格与协定代码双向一致</b>（规则 A2）。两个方向都拦，
 *       否则「不能享惠但填了协定」会同时告诉 10 与 16 两个矛盾的答案。
 *   <li><b>只有审核通过才可报关</b>（V94 第一节）。这不是「质量建议」而是闸门：
 *       草稿行对 10 报关<b>不可见</b>，而不是「可见但要小心」。
 *   <li><b>要素码按 ordinal 有序，不按字母序</b>。海关按序比对，顺序是业务事实。
 * </ol>
 */
public final class CustomsAttributeRules {
  /** 审核状态的三档。与 {@code customs_attribute.status} 的 CHECK 同一词表。 */
  public static final String DRAFT = "DRAFT";

  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";

  public static final List<String> STATUSES = List.of(DRAFT, APPROVED, REJECTED);

  public static final String SOURCE_MODULE = "MODULE_3_7_1_1";

  /** 供 10 报关回填的「申报要素齐备」状态值（V85 的 CHECK 词表之一）。 */
  public static final String ELEMENT_STATUS_COMPLETE = "MODULE_3_7_1_1_COMPLETE";

  private CustomsAttributeRules() {}

  // ============================================================ 规则 A1：HS 编码

  /** HS 编码格式：6–10 位纯数字。与 {@code customs_attribute_hs_code_format} 同一正则。 */
  public static boolean validHsCode(String hsCode) {
    return hsCode != null && hsCode.matches("[0-9]{6,10}");
  }

  /**
   * 归一化 HS 编码：去首尾空格后必须仍是 6–10 位纯数字。
   *
   * <p><b>只去空格，不做别的「善意修正」</b>：自动补零、把字母换成数字这类修正
   * 会把一个明显的错码改成一个「看起来对」的错码 —— 而错码要到海关退单时才发现，
   * 代价是罚款。所以这里要么原样通过，要么报错。
   *
   * @throws IllegalArgumentException 格式不合法时抛出可读的中文原因
   */
  public static String normalizeHsCode(String hsCode) {
    String trimmed = hsCode == null ? "" : hsCode.trim();
    if (trimmed.isEmpty()) throw new IllegalArgumentException("请填写 HS 编码");
    if (!validHsCode(trimmed))
      throw new IllegalArgumentException(
          "HS 编码「"
              + trimmed
              + "」必须是 6–10 位纯数字（如 851712 或 8517123090）："
              + "带字母或位数不对的是目的国本地税则号，不是这一列该装的编码。"
              + "HS 编码错误会直接导致申报失败与罚款，而扣货与罚款由卖家承担");
    return trimmed;
  }

  // ============================================================ 原产地

  /**
   * 归一化原产地国家码：<b>trim + 转大写</b>，然后校验两位大写字母。
   *
   * <p><b>转大写是对的，国家码转小写是错的</b>：ISO 3166-1 alpha-2 定义的就是大写，
   * 用户从任何来源粘贴都可能带来小写/前后空格，而它落库后在页面上
   * 「看起来完全正常」，直到报关时才发现归类到了错误的税率。
   *
   * <p>对照 V94 第二节对 {@code char(2)} 的取舍：库层用 {@code varchar(2)} 不做填充，
   * 归一化全部在应用层做，所以库里存的一定是干净的两位大写。
   */
  public static String normalizeCountryCode(String countryCode, String label) {
    String trimmed = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty()) throw new IllegalArgumentException("请填写" + label);
    if (!trimmed.matches("[A-Z]{2}"))
      throw new IllegalArgumentException(
          label
              + "「"
              + trimmed
              + "」必须是两位大写字母的 ISO 3166-1 alpha-2 代码（如 CN / VN / DE）："
              + "原产地决定适用税率与是否适用自贸协定，填错会让优惠失效或被追缴");
    return trimmed;
  }

  // ============================================================ 规则 A2：享惠一致性

  /**
   * 校验享惠资格与协定代码双向一致（规则 A2），返回归一化后的协定代码。
   *
   * <p>两个方向都拦，与 DB 层的两条 CHECK 一一对应：
   * 「勾了能享惠但没说是哪个协定」会让海关无法适用优惠税率，而运营以为已经享惠；
   * 「填了协定却没勾享惠」会同时告诉 10 报关「不能享惠」和 16 合规「有协定」。
   */
  public static String normalizePreference(boolean eligible, String agreement) {
    String trimmed = agreement == null ? "" : agreement.trim();
    if (eligible && trimmed.isEmpty())
      throw new IllegalArgumentException(
          "勾了可享惠就必须填写协定代码（RCEP / CPTPP 等）：海关要凭协定代码才能适用优惠税率，"
              + "没有它这项优惠直接失效，而运营以为已经享惠 —— 少缴的税到年底可能被追缴");
    if (!eligible && !trimmed.isEmpty())
      throw new IllegalArgumentException(
          "没有勾可享惠就不该填协定代码（当前填了「"
              + trimmed
              + "」）：这一行会同时告诉 10 报关「不能享惠」和 16 合规「有协定」，"
              + "下游拿到两个互相矛盾的答案");
    return eligible ? trimmed.toUpperCase(Locale.ROOT) : "";
  }

  // ============================================================ 申报要素

  /**
   * 按 {@code ordinal} 升序排列申报要素（纪律 4：顺序是业务事实）。
   *
   * <p><b>按位次排而不是按字母序排</b>：海关按申报要素的编号顺序逐项比对，
   * 顺序错了会被判为填报不符。库里的 UNIQUE 约束保证位次不重复，
 * 但<b>不保证读出来是有序的</b>（没有 ORDER BY 的 SQL 返回顺序不保证），
   * 所以这一层必须显式排。
   */
  public static List<DeclarationElement> ordered(List<DeclarationElement> elements) {
    List<DeclarationElement> sorted = new ArrayList<>(elements);
    sorted.sort((left, right) -> Integer.compare(left.ordinal(), right.ordinal()));
    return List.copyOf(sorted);
  }

  /**
   * 把申报要素拍成 jsonb 对象形态（键 = elementCode、值 = elementValue）。
   *
   * <p>给 10 报关直接落进 V85 的 {@code declaration_elements jsonb}，
   * 满足它的 {@code CHECK(jsonb_typeof(... )='object')}。
   *
   * <p><b>重复的要素码后者覆盖前者</b>：V85 刻意不写死键名，
   * 而本表的位次唯一并不蕴含要素码唯一（不同位次可以是同一个码，比如
   * 「品牌类型」在两个子项上各出现一次）。对象形态下这是唯一可表达的语义，
   * 所以取<b>位次靠后的那个</b>并保持稳定顺序。
   * 位次唯一由 DB 的 UNIQUE(tenant_id,customs_attribute_id,ordinal) 保证，
   * 要素码重复因此是一个**真实存在**的合法输入，不是脏数据。
   */
  public static Map<String, String> toByCode(List<DeclarationElement> ordered) {
    Map<String, String> byCode = new LinkedHashMap<>();
    for (DeclarationElement element : ordered)
      byCode.put(element.elementCode(), element.elementValue() == null ? "" : element.elementValue());
    return Map.copyOf(byCode);
  }

  /** 必填却还空着的申报要素（规则 A4 的未满足项）。 */
  public static List<DeclarationElement> missingRequired(List<DeclarationElement> ordered) {
    return ordered.stream()
        .filter(element -> element.required() && isBlank(element.elementValue()))
        .toList();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  // ============================================================ 可报关性（V94 第一节）

  /** 阻断原因码。顺序固定，页面上的展示顺序才稳定。 */
  public static final String NOT_RECORDED = "NOT_RECORDED";

  public static final String NOT_APPROVED = "NOT_APPROVED";
  public static final String SUBMITTED_AWAITING_REVIEW = "SUBMITTED_AWAITING_REVIEW";

  /** 已被驳回。<b>刻意不叫 {@code REJECTED}</b>：那是审核状态常量，
   *  同名会让「状态是 REJECTED」与「被 REJECTED 这条原因拦住」在读代码时无法区分。 */
  public static final String DECLARATION_REJECTED = "DECLARATION_REJECTED";

  public static final String NO_ELEMENTS = "NO_ELEMENTS";
  public static final String REQUIRED_ELEMENTS_INCOMPLETE = "REQUIRED_ELEMENTS_INCOMPLETE";

  /**
   * 可报关性判定。
   *
   * @param ready 是否允许被 10 报关引用（{@code blockers} 为空即 true）
   * @param blockers 阻断原因（可报关时为空列表，**不为 null**）
   */
  public record DeclarationVerdict(boolean ready, List<String> blockers) {}

  /**
   * 判定这份报关属性能不能被 10 报关引用（纪律 3）。
   *
   * <p><b>{@code status != APPROVED} 一律判不可报关</b>，且按当前态区分三种未就绪：
   * 还没维护 / 已提交待审 / 被驳回。这三者给运营的下一步动作完全不同
   * （去填 / 等审核 / 改后重提），所以不能塌缩成一个「未就绪」。
   *
   * <p>元素齐备性也在这里判：它是「现算」的派生值，**不落表**
   * （V94 零之三）—— 落一列就等于缓存了一个会被审核流程改写的状态。
   */
  public static DeclarationVerdict verdict(CustomsProfile profile, List<DeclarationElement> ordered) {
    List<String> blockers = new ArrayList<>(2);
    if (profile.attributeId() == null) {
      blockers.add(NOT_RECORDED);
    } else {
      String status = profile.status() == null ? DRAFT : profile.status();
      switch (status) {
        case APPROVED -> {
          // 已审核通过：只查申报要素齐备性。
          // 缺任何一条都会被 10 报关拿到一份不完整的资料，所以这里仍然拦住。
          if (ordered.isEmpty()) blockers.add(NO_ELEMENTS);
          else if (!missingRequired(ordered).isEmpty()) blockers.add(REQUIRED_ELEMENTS_INCOMPLETE);
        }
        case REJECTED -> blockers.add(DECLARATION_REJECTED);
        case DRAFT -> blockers.add(
            profile.submittedAt() == null ? NOT_APPROVED : SUBMITTED_AWAITING_REVIEW);
        default -> blockers.add(NOT_APPROVED);
      }
    }
    return new DeclarationVerdict(blockers.isEmpty(), List.copyOf(blockers));
  }

  public static String blockerLabel(String code) {
    return switch (code) {
      case NOT_RECORDED -> "这个 SKU 还没有报关属性，10 报关引用不到任何申报资料";
      case NOT_APPROVED -> "报关属性还没提交审核，草稿不可被报关引用";
      case SUBMITTED_AWAITING_REVIEW -> "报关属性已提交，等待审核通过后才能被报关引用";
      case DECLARATION_REJECTED -> "报关属性已被驳回，改完后重新提交才能被报关引用";
      case NO_ELEMENTS -> "一条申报要素都没有：海关按申报要素逐项比对，缺项会被判为填报不符并退单";
      case REQUIRED_ELEMENTS_INCOMPLETE -> "有必填申报要素还是空的，补齐后重新提交审核";
      default -> code;
    };
  }

  /** 审核状态的中文标签。页面上三档三色：草稿中性、待审警示、通过成功、驳回危险。 */
  public static String statusLabel(String status) {
    if (status == null) return "未维护";
    return switch (status) {
      case DRAFT -> "草稿";
      case APPROVED -> "已审核通过";
      case REJECTED -> "已驳回";
      default -> status;
    };
  }

  // ============================================================ 筛选归一

  /** 状态筛选归一：空串当作不筛；非词表值直接报错而不是静默返回空集。 */
  public static String normalizeStatus(String status) {
    if (status == null) return null;
    String trimmed = status.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty()) return null;
    if (!STATUSES.contains(trimmed))
      throw new IllegalArgumentException("无效的审核状态（可选：DRAFT / APPROVED / REJECTED）");
    return trimmed;
  }

  /**
   * 协定筛选归一：<b>空串是一个有意义的筛选值</b>（筛「不适用任何协定」）。
   *
   * <p>所以这里返回 {@code null} 表示「不筛」、返回 {@code ""} 表示「筛不适用协定」，
   * 调用方必须区分这两种 null/空串，不能一并当成不筛。
   */
  public static String normalizeAgreementFilter(String agreement) {
    if (agreement == null) return null;
    String trimmed = agreement.trim().toUpperCase(Locale.ROOT);
    return trimmed;
  }

  /** 关键字归一：去空格、转小写；过长直接报错而不是截断（截断会搜出看不懂的结果集）。 */
  public static String normalizeKeyword(String keyword) {
    if (keyword == null) return null;
    String trimmed = keyword.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > 64) throw new IllegalArgumentException("搜索词最多 64 个字符");
    return trimmed.toLowerCase(Locale.ROOT);
  }

  /**
   * HS 编码筛选归一：<b>不校验格式</b>。
   *
   * <p>与写入路径的差别是有意的：筛选一个写坏的值（比如历史脏数据或用户就是想确认
   * 「没有 851712-ABC 这种码」）应当返回空集而不是报错 —— 报错会让页面只进错误态，
   * 看不出「筛出来是 0 条」。
   */
  public static String normalizeHsCodeFilter(String hsCode) {
    if (hsCode == null) return null;
    String trimmed = hsCode.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}