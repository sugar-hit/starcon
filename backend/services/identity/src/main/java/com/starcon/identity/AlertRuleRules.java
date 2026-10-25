package com.starcon.identity;

import com.starcon.identity.AlertRuleCatalog.ChangeBasis;
import com.starcon.identity.AlertRuleCatalog.MetricDefinition;
import com.starcon.identity.AlertRuleCatalog.Unit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.2.1 预警规则 —— 受控词表与规则定义的**纯函数**校验规则。
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些词被拒、哪些阈值被拒、上限是多少），而不是只能靠集成测试间接观察。
 *
 * <p><b>它守的是「规则定义的形状」</b>：
 *
 * <ul>
 *   <li><b>词表受控</b>：对象类型 / 度量码 / 时间窗 / 比较方向 / 比较基准 / 对象范围
 *       全部是受控码，不是自由字符串；</li>
 *   <li><b>阈值是十进制文本</b>：不接受科学计数法、不接受超出 NUMERIC(19,6) 的值、
 *       不接受负数；</li>
 *   <li><b>对象范围与对象标识自洽</b>：ALL 不许带对象，SPECIFIC 必须带对象；</li>
 *   <li><b>时区是 IANA 区域 id</b>：复用 1.4.2.1 的受控词表。</li>
 * </ul>
 *
 * <p><b>为什么不在前端校验就够</b>：前端是可以被绕过的 ——
 * 任何人拿自己的 token 就能直接打这些接口。所以词表与形状
 * 必须在服务端再钉一遍，否则它只是「我们约定不改」，不是「改不了」。
 * 数据库侧另有一道 CHECK 约束（V384），绕过服务层直连 SQL 也一样被拒。
 */
public final class AlertRuleRules {

  private AlertRuleRules() {}

  // ==========================================================================
  // 一、受控词表（与 V384 的 CHECK 约束逐字同词表）
  // ==========================================================================

  /** 对象类型词表。 */
  public static final List<String> OBJECT_TYPES = List.of("SKU", "SHOP", "LEGAL_ENTITY", "SUPPLIER");

  /** 时间窗词表。 */
  public static final List<String> TIME_WINDOWS =
      List.of("INSTANT", "ROLLING_7D", "ROLLING_30D", "CALENDAR_MONTH");

  /** 比较方向词表。 */
  public static final List<String> COMPARISONS = List.of("LT", "LE", "GT", "GE");

  /** 对象范围词表。 */
  public static final List<String> OBJECT_SCOPES = List.of("ALL", "SPECIFIC");

  /** 阈值形状：十进制整数或小数，最多 6 位小数（与 NUMERIC(19,6) 对齐）。 */
  private static final Pattern THRESHOLD = Pattern.compile("^\\d{1,13}(\\.\\d{1,6})?$");

  /** 自定义规则码前缀（触发器与 DB CHECK 都要求这个前缀）。 */
  public static final String CUSTOM_CODE_PREFIX = "CUSTOM-";

  /** 阈值精度上限（NUMERIC(19,6) 的小数位）。 */
  public static final int THRESHOLD_SCALE = 6;

  /** 阈值整数位上限（NUMERIC(19,6) 的 19 位里留 6 位给小数）。 */
  public static final int THRESHOLD_PRECISION = 13;

  // ==========================================================================
  // 二、逐项归一
  // ==========================================================================

  /** 对象类型归一。 */
  public static String normalizeObjectType(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (!OBJECT_TYPES.contains(code)) {
      throw badRequest("对象类型不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", OBJECT_TYPES) + "）");
    }
    return code;
  }

  /** 度量码归一（必须命中预置指标词表）。 */
  public static String normalizeMetricCode(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (AlertRuleCatalog.metric(code) == null) {
      throw badRequest("度量码不在受控词表里：" + describe(code));
    }
    return code;
  }

  /** 时间窗归一。 */
  public static String normalizeTimeWindow(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (!TIME_WINDOWS.contains(code)) {
      throw badRequest("时间窗不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", TIME_WINDOWS) + "）");
    }
    return code;
  }

  /** 比较方向归一。 */
  public static String normalizeComparison(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (!COMPARISONS.contains(code)) {
      throw badRequest("比较方向不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", COMPARISONS) + "）");
    }
    return code;
  }

  /** 比较基准归一（不给按 NONE 处理）。 */
  public static String normalizeChangeBasis(String raw) {
    String code = raw == null || raw.isBlank() ? ChangeBasis.NONE : raw.trim();
    if (!ChangeBasis.ALL.contains(code)) {
      throw badRequest("比较基准不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", ChangeBasis.ALL) + "）");
    }
    return code;
  }

  /** 对象范围归一（不给按 ALL 处理）。 */
  public static String normalizeObjectScope(String raw) {
    String code = raw == null || raw.isBlank() ? "ALL" : raw.trim();
    if (!OBJECT_SCOPES.contains(code)) {
      throw badRequest("对象范围不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", OBJECT_SCOPES) + "）");
    }
    return code;
  }

  /**
   * 对象标识归一。
   *
   * <p>对象标识是**上游系统的主键**（SKU 码 / 店铺码 / 法人码 / 供应商码），
   * 字符集收紧到字母 / 数字 / 点 / 下划线 / 连字符，不接收路径分隔符
   * （与 1.3.1.1 的上游主键字符集同一条纪律）。
   */
  public static String normalizeObjectRef(String raw) {
    String ref = raw == null ? "" : raw.trim();
    if (ref.isEmpty()) {
      throw badRequest("对象范围是「具体某对象」时必须给出对象标识");
    }
    if (ref.length() > 120 || !ref.matches("^[A-Za-z0-9._-]{1,120}$")) {
      throw badRequest("对象标识不合法（只允许字母 / 数字 / 点 / 下划线 / 连字符，长度 1~120）");
    }
    return ref;
  }

  /** 规则名归一（自定义规则必填）。 */
  public static String normalizeName(String raw) {
    String name = raw == null ? "" : raw.trim();
    if (name.isEmpty()) {
      throw badRequest("规则名不能为空");
    }
    if (name.length() > 100) {
      throw badRequest("规则名不能超过 100 个字符");
    }
    return name;
  }

  /**
   * 时区归一（**受控 IANA 词表**，复用 1.4.2.1 的实现，不另起一套）。
   *
   * <p>规则是租户级共享的，所以时区必须**显式存在规则上** ——
   * identity 侧没有全局业务时区（1.4.2.1 明确写了「不提供全局业务时区」，
   * 它那个是**个人**展示时区，用在租户共享规则上会让规则随写它的人变）。
   */
  public static String normalizeTimezone(String raw) {
    try {
      return AccountLocaleRules.normalizeTimezone(raw);
    } catch (AccountLocaleException e) {
      throw badRequest(e.getMessage());
    }
  }

  // ==========================================================================
  // 三、阈值
  // ==========================================================================

  /**
   * 阈值归一：十进制文本 → {@link BigDecimal}。
   *
   * <p><b>为什么收文本不收数字</b>：阈值的精度就是告警线的精度 ——
   * 「跌超 20.5%」写成 {@code 20.5} 在 JS 里是 {@code 20.5}（二进制浮点），
   * 但 {@code 0.1 + 0.2} 那类加法误差会让「用户输入的值」与「存下来的值」对不上。
   * 线上走十进制文本，Java 侧 {@code BigDecimal}，库里 {@code NUMERIC(19,6)}。
   *
   * <p><b>拒绝科学计数法</b>：{@code 1E+2} 合法但界面上没人看得懂，
   * 而且它绕过了「最多 6 位小数」的直觉。
   *
   * @throws ResponseStatusException 形状不合法 / 超出精度 / 为负（400）
   */
  public static BigDecimal normalizeThreshold(String raw) {
    String text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) {
      throw badRequest("阈值不能为空");
    }
    if (!THRESHOLD.matcher(text).matches()) {
      throw badRequest(
          "阈值不合法：必须是十进制文本（不带正负号、不带科学计数法，最多 "
              + THRESHOLD_PRECISION
              + " 位整数 + "
              + THRESHOLD_SCALE
              + " 位小数），收到："
              + describe(text));
    }
    BigDecimal value = new BigDecimal(text);
    if (value.precision() - value.scale() > THRESHOLD_PRECISION) {
      throw badRequest("阈值超出可存储精度（最多 " + THRESHOLD_PRECISION + " 位整数）");
    }
    return value;
  }

  /**
   * 阈值 → 十进制文本（**不带科学计数法**，界面与契约都用这个形状）。
   *
   * <p>🔴 <b>要去掉尾随零</b>：库里的列是 {@code NUMERIC(19,6)}，读回来是
   * {@code 21.000000} —— 那不是另一个数，但界面上一会儿显示「21」、一会儿显示
   * 「21.000000」，用户会以为阈值被改过。
   * {@code stripTrailingZeros()} 把 {@code 21.000000} 收回 {@code 21}，
   * 而 {@code 20.500000} 收回 {@code 20.5}（小数位是有效的，不动）。
   */
  public static String thresholdText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.signum() == 0) return "0";
    return stripped.toPlainString();
  }

  // ==========================================================================
  // 四、语义护栏（规则不该「静默永不命中」）
  // ==========================================================================

  /**
   * 阈值与比较方向的组合会不会让这条规则**永远命中**或**永远不命中**。
   *
   * <p>蓝图 18.3.1.1 说过：「规则要能看出最后一次执行结果，否则**坏规则静默失败无人察觉**。」
   * 一个阈值恒等于 0 的「大于等于 0」规则，会对每一个对象都报命中 ——
   * 那不是预警，是噪声。一个阈值恒等于 0 的「小于 0」规则，则永远不会命中 ——
   * 那不是预警，是摆设。两种都得在建规则的时候挡住。
   *
   * <p><b>只对天然非负的量做这个判断</b>（计数 / 数量 / 天数）：
   * 金额与百分比**可以是负数**（净利可以是亏，毛利率可以是负），
   * 对它们套「非负」的假设会误伤合法规则。
   *
   * @param metric 度量定义
   * @param threshold 阈值
   * @param comparison 比较方向
   * @param changeBasis 比较基准
   */
  public static void guardThresholdReachability(
      MetricDefinition metric, BigDecimal threshold, String comparison, String changeBasis) {
    if (threshold == null || threshold.signum() != 0) return;
    if (ChangeBasis.NONE.equals(changeBasis)) {
      Unit unit = metric.unit();
      boolean nonNegative = unit == Unit.COUNT || unit == Unit.QUANTITY || unit == Unit.DAYS;
      if (!nonNegative) return;
      boolean alwaysHits = "GT".equals(comparison) || "GE".equals(comparison);
      if (alwaysHits) {
        throw badRequest(
            "这条规则会对每一个对象都命中（阈值 0 + 「"
                + comparison
                + "」，而"
                + metric.displayName()
                + "不可能小于 0）—— 那是噪声不是预警，请把阈值调高");
      }
      throw badRequest(
          "这条规则永远不会命中（阈值 0 + 「"
              + comparison
              + "」，而"
              + metric.displayName()
              + "不可能小于 0）—— 请把阈值调高");
    }
  }

  // ==========================================================================
  // 五、自定义规则码
  // ==========================================================================

  /**
   * 生成自定义规则码：{@code CUSTOM-} + 12 位十六进制。
   *
   * <p>带前缀是**必须的**：触发器用它把「自定义规则」与「预置规则覆盖行」分开 ——
   * 否则一条自定义规则可以占掉一个预置规则码，把预置规则挤掉。
   */
  public static String newCustomRuleCode() {
    return CUSTOM_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  /** 规则码形状（与 V384 的 CHECK 约束同形）。 */
  public static boolean isValidRuleCode(String code) {
    return code != null && code.matches("^[A-Z][A-Z0-9_-]{1,63}$");
  }

  // ==========================================================================
  // 六、内部
  // ==========================================================================

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String describe(String value) {
    if (value == null) return "(空)";
    return value.length() > 80 ? value.substring(0, 80) + "…" : value;
  }
}
