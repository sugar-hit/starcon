package com.starcon.channel;

import static com.starcon.channel.AdTargetingModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.1.3.2 投放定位。
 *
 * <p>蓝图：「管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
 * 指导出价调整。输入：定位设置；定位维度效果数据。输出：定位效果对比表 + 调整建议。
 * 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
 * <b>定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。</b>
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>管理投放定位</b> → {@code ad_targeting_setting}（V285）：**归一后的定位维度**
 *       （地域 / 人群 / 设备 / 时段 / 匹配类型），活动级与组级皆可挂。
 *   <li><b>解读平台原文</b> → 🔴 本模块的**核心动作**：上游 V281 的
 *       {@code ad_campaign_group.targeting_settings} 是**平台原文串**，
 *       V281 的注释写明「解读归 14.1.3.2」⇒ 本模块把原文**拆解 + 归一**成
 *       定位维度，**不改写上游列的语义**（自己的表承载解读结果，原文另存）。
 *   <li><b>按定位维度看效果</b> → {@code ad_targeting_metric}（V285）+ 真实聚合 SQL
 *       （真分页 / 真筛选 / 真排序），派生比率**分母为 0 时给 null**（不可比 ≠ 0）。
 *   <li><b>指导出价调整</b> → 调整建议（**每条都带依据数值 + 口径 + 阈值**，
 *       样本不足时 {@code actionable=false}）与出价系数（比例 NUMERIC(19,6)）。
 *   <li><b>下游 = 14.2.1.2 分时策略</b> → {@code GET /daypart-profile}：
 *       时段画像是**本模块交给下游的可复用数据**（哪个时段值得投、哪个在烧钱）。
 * </ul>
 *
 * <p>🔴 本模块最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>「认不出来」必须显式报</b>。平台原文里的键归一不出来时落
 *       {@code dimension='UNMAPPED'}（**不是兜底**，照抄 2.1.2.1 口径），
 *       时段取值解析不出小时窗口时窗口列**留白**（不编一个窗口出来）。
 *   <li><b>指标只有一处来源</b>。对比表与建议的指标**全部**来自
 *       {@code ad_targeting_metric}，销售额为 0 时 ACOS 给 <b>null</b>。
 *   <li><b>跨币种不相加</b>。对比表按币种分行，汇总金额按币种分列；
 *       只有计数（与币种无关）才相加。
 *   <li><b>建议必须有依据</b>。没有 {@code evidence}（真实数字）与 {@code basis}
 *       （口径 + 阈值）的「建议」就是硬编码文案；样本不足就明说样本不足。
 *   <li><b>本地写 ≠ 平台侧已改</b>。平台回写队列未交付 ⇒ 所有本地写只落本地，
 *       并把设置标成「本地已改、尚未回写平台」。
 * </ol>
 */
@Service
public class AdTargetingService {

  // ==================================================================
  // 阈值与护栏（14 章关键约束「自动调整必须有上限保护」）
  // ==================================================================

  /** 出价系数下限（相对基准）。低于 0.1 的系数等于停投，用「停投」这个动作表达更诚实。 */
  static final BigDecimal MIN_BID_FACTOR = new BigDecimal("0.100000");

  /** 出价系数上限（相对基准）。一次最多加到 1.5 倍，别指望「建议」把出价翻倍。 */
  static final BigDecimal MAX_BID_FACTOR = new BigDecimal("1.500000");

  /** 给出建议所需的最小花费（同币种内）。低于它就是样本不足，不给建议。 */
  static final BigDecimal MIN_SPEND_FOR_SUGGESTION = new BigDecimal("10.0000");

  /** 给出建议所需的最小展示数。 */
  static final long MIN_IMPRESSIONS_FOR_SUGGESTION = 100L;

  /** 给出建议所需的最小点击数。 */
  static final long MIN_CLICKS_FOR_SUGGESTION = 5L;

  /** 目标 ACOS：低于它算表现好（可以加价），高于它算超标。 */
  static final BigDecimal TARGET_ACOS = new BigDecimal("0.300000");

  /** 硬上限 ACOS：超过它算严重超标（建议降到底）。 */
  static final BigDecimal HARD_ACOS = new BigDecimal("0.600000");

  /** 维度内差异倍数阈值：最差取值的 ACOS ≥ 最佳 × 该倍数 ⇒ 建议收窄。 */
  static final BigDecimal DIMENSION_SPREAD_FACTOR = new BigDecimal("2.000000");

  /** 一次解读允许的定位维度条数上限。 */
  static final int MAX_DIMENSIONS_PER_INTERPRET = 50;

  /** 归一后的定位维度全集（UNMAPPED 是显式合法值，不是兜底）。 */
  static final List<String> DIMENSION_VALUES =
      List.of("GEO", "AUDIENCE", "DEVICE", "DAYPART", "MATCH_TYPE", "UNMAPPED");

  /** 定位设置状态全集。 */
  static final List<String> STATUSES = List.of("ACTIVE", "PAUSED", "ARCHIVED");

  /** 定位设置排序方式全集（白名单：排序字段不许由调用方拼进 SQL）。 */
  static final List<String> SETTING_SORTS =
      List.of("updatedAtDesc", "createdAtDesc", "dimensionAsc", "valueAsc", "spendDesc");

  /** 效果对比表排序方式全集（白名单）。 */
  static final List<String> EFFECT_SORTS =
      List.of(
          "spendDesc",
          "spendAsc",
          "acosDesc",
          "acosAsc",
          "clicksDesc",
          "conversionsDesc",
          "wastedSpendDesc",
          "salesDesc",
          "dimensionAsc");

  /** 对比表默认区间长度（UTC 日）。 */
  static final int DEFAULT_RANGE_DAYS = 30;

  /** 🔴 对比表单页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 🔴 下游分时策略的责任模块（蓝图：下游 = 14.2.1.2 分时策略）。 */
  static final String DAYPART_OWNER_MODULE = "14.2.1.2 分时策略";

  /** 🔴 平台原文与归一定位的口径说明。 */
  static final String INTERPRETATION_NOTE =
      "🔴 上游 V281 的 `ad_campaign_group.targeting_settings` 是**平台原文串，不拆解不归一**，"
          + "它的注释写明「**解读归 14.1.3.2**」⇒ 本模块负责把原文**拆解 + 归一**成定位维度。"
          + "本模块**不改写上游列的语义**（上游那一列继续存原文），"
          + "解读结果落在**自己的表** `ad_targeting_setting` 上，并**保留原文**"
          + "（platformRawKey / platformRawValue）以便回溯。"
          + "🔴 平台原文里**归一不出来的键**落 `dimension='UNMAPPED'`（显式合法值，**不是兜底**）—— "
          + "它不会被丢弃，运营在界面上能看见「平台给了个我们不认识的定位维度」。";

  /** 🔴 平台回写未交付的影响说明。 */
  static final String PLATFORM_WRITEBACK_NOTE =
      "🔴 平台广告回写队列**未交付**（平台适配层 channel/vendor）⇒ "
          + "本模块的所有写动作**只落本地**，并把定位设置标成「本地已改、尚未回写平台」。"
          + "界面会把「本地已收窄」与「平台侧已收窄」分开展示 —— "
          + "把本地状态当成平台侧状态就是在骗人（那会让运营以为投放真的停了，其实在烧钱）。";

  // ==================================================================
  // 平台原文 → 归一定位 的映射
  // ==================================================================

  /**
   * 平台原文的键 → 归一后的定位维度。
   *
   * <p>🔴 **认不出来的键不猜**：落 UNMAPPED（照抄 2.1.2.1「映射缺失必须显式报未映射，
   * 不能默认落到某个兜底状态」）。键名一律小写去空白后比对（平台原文的大小写不可信）。
   */
  static final Map<String, String> DIMENSION_KEYS =
      Map.ofEntries(
          Map.entry("geo", "GEO"),
          Map.entry("geos", "GEO"),
          Map.entry("geography", "GEO"),
          Map.entry("country", "GEO"),
          Map.entry("countries", "GEO"),
          Map.entry("region", "GEO"),
          Map.entry("regions", "GEO"),
          Map.entry("market", "GEO"),
          Map.entry("markets", "GEO"),
          Map.entry("location", "GEO"),
          Map.entry("locations", "GEO"),
          Map.entry("audience", "AUDIENCE"),
          Map.entry("audiences", "AUDIENCE"),
          Map.entry("audience_type", "AUDIENCE"),
          Map.entry("segment", "AUDIENCE"),
          Map.entry("segments", "AUDIENCE"),
          Map.entry("persona", "AUDIENCE"),
          Map.entry("device", "DEVICE"),
          Map.entry("devices", "DEVICE"),
          Map.entry("device_type", "DEVICE"),
          Map.entry("daypart", "DAYPART"),
          Map.entry("dayparts", "DAYPART"),
          Map.entry("schedule", "DAYPART"),
          Map.entry("time_window", "DAYPART"),
          Map.entry("hour_window", "DAYPART"),
          Map.entry("hours", "DAYPART"),
          Map.entry("match_type", "MATCH_TYPE"),
          Map.entry("matchtype", "MATCH_TYPE"),
          Map.entry("match", "MATCH_TYPE"));

  /** 平台原文的分隔符（`;` / 换行 / `|`）。 */
  private static final Pattern SEGMENT_SPLIT = Pattern.compile("[;\\n\\r|]+");

  /** 时段区间：`9-17` / `09:00-17:00` / `9am-5pm`。 */
  private static final Pattern DAYPART_RANGE =
      Pattern.compile(
          "^(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)?\\s*-\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)?$");

  /** 单个小时：`9` / `09:00` / `9pm`。 */
  private static final Pattern DAYPART_SINGLE =
      Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)?$");

  /** 平台命名的时段（全天 / 上午 / 下午 / 晚上 / 凌晨）。 */
  private static final Map<String, int[]> NAMED_DAYPARTS =
      Map.of(
          "ALLDAY", new int[] {0, 0, 1440},
          "ALWAYS", new int[] {0, 0, 1440},
          "24H", new int[] {0, 0, 1440},
          "24HOURS", new int[] {0, 0, 1440},
          "MORNING", new int[] {6, 12, 360},
          "AFTERNOON", new int[] {12, 16, 240},
          "EVENING", new int[] {16, 0, 480},
          "NIGHT", new int[] {0, 6, 360});

  // ==================================================================
  // 内部结构
  // ==================================================================

  /** 解读出来的一条归一定位（还没落库）。 */
  record ParsedTargeting(
      String dimension,
      String dimensionValue,
      String platformRawKey,
      String platformRawValue,
      boolean recognized,
      Integer startHour,
      Integer endHour,
      BigDecimal hours,
      boolean crossesMidnight,
      String note) {}

  /** 时段窗口（解析结果）。 */
  record DaypartWindow(Integer startHour, Integer endHour, BigDecimal hours, boolean crossesMidnight) {}

  /** 筛选条件（白名单字段 + 转义后的 LIKE 模式）。 */
  private record Filter(String where, List<Object> binds) {}

  private final DSLContext sql;
  private final AdCampaignService campaigns;
  private final ObjectMapper json;

  public AdTargetingService(DSLContext sql, AdCampaignService campaigns, ObjectMapper json) {
    this.sql = sql;
    this.campaigns = campaigns;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 AdGroupManagementService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** LIKE 里的通配符要转义，否则用户搜 "50%" 会匹配到一切。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄上游 14.1.1.1 / 14.1.1.2 的同一款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  /** 长度受限的原文字段。 */
  private static String bounded(String value, int max, String what) {
    String trimmed = text(value);
    if (trimmed.length() > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "最长 " + max + " 个字符");
    }
    return trimmed;
  }

  /** 金额比较：null 与 0 含义相反，所以 null 只与 null 相等。 */
  private static boolean sameMoney(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return a.compareTo(b) == 0;
  }

  /** 派生比率：分母为 0 ⇒ null（不可比），不是 0。 */
  static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  /** IN (?, ?, ...) 的占位符。 */
  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdTargetingCapabilities capabilities() {
    return new AdTargetingCapabilities(
        List.of(
            new AdTargetingCapabilityDimension(
                "targeting_settings",
                "定位设置：归一后的定位维度（地域 / 人群 / 设备 / 时段 / 匹配类型），活动级与组级皆可挂（真分页 + 真筛选）",
                true,
                "ad_targeting_setting（V285）",
                "",
                "没有定位设置，「投给了谁」这个问题就没有答案；也无处落「收窄投放」的结论"),
            new AdTargetingCapabilityDimension(
                "targeting_interpretation",
                "🔴 解读平台原文定位串：把上游 V281 的 targeting_settings 原文**拆解 + 归一**成定位维度",
                true,
                "ad_campaign_group.targeting_settings（V281 上游）+ ad_targeting_setting（V285）",
                "",
                "🔴 V281 的注释写明「解读归 14.1.3.2」—— 不解读就**没法按维度看效果**，"
                    + "「定位效果差异大时收窄投放」也无从谈起"),
            new AdTargetingCapabilityDimension(
                "targeting_metrics",
                "定位维度效果数据（展示 / 点击 / 转化 / 花费 / 销售额，按 UTC 日）",
                true,
                "ad_targeting_metric（V285）",
                "",
                "没有效果数据就没有对比表，建议就只能靠编"),
            new AdTargetingCapabilityDimension(
                "effect_comparison",
                "🔴 定位效果对比表：按定位维度看效果（真分页 + 真筛选 + 真排序 + 区间汇总）",
                true,
                "ad_targeting_metric + ad_campaign + ad_campaign_group（V285 / V273 / V277）",
                "",
                "这是蓝图的**输出**之一：没有对比表就看不出「哪个定位在烧钱」"),
            new AdTargetingCapabilityDimension(
                "adjustment_suggestions",
                "🔴 调整建议：带**依据数值**的建议（无效定位 / ACOS 超标 / 表现好可加价 / 维度内差异过大）",
                true,
                "ad_targeting_metric 现算（V285）",
                "",
                "这是蓝图的**输出**之一：没有建议，运营看完对比表还是不知道该怎么办"),
            new AdTargetingCapabilityDimension(
                "narrow_delivery",
                "🔴 收窄投放：按建议把一条定位置为暂停（带证据门槛 + 留痕）",
                true,
                "ad_targeting_setting + ad_targeting_operation_log（V285）",
                "",
                "蓝图的加粗句：「**定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱**」"),
            new AdTargetingCapabilityDimension(
                "daypart_profile",
                "🔴 时段画像：供**下游 14.2.1.2 分时策略**复用的时段维度数据",
                true,
                "ad_targeting_setting + ad_targeting_metric（V285）",
                "",
                "下游分时策略要的就是「哪个时段值得投、哪个时段在烧钱」—— 本模块把它交出来"),
            new AdTargetingCapabilityDimension(
                "platform_writeback",
                "🔴 本地写动作**回写平台**（收窄 / 改定位 / 归档）",
                false,
                "—",
                "平台适配层（channel/vendor）的平台回写队列",
                "本模块的动作只落本地并标记「待回写平台」—— 界面会显式标出，"
                    + "避免把「本地已收窄」当成「平台侧已收窄」"),
            new AdTargetingCapabilityDimension(
                "bid_adjustment_execution",
                "🔴 **执行**出价调整（真正把竞价改成建议值）",
                false,
                "—",
                "14.2.1.1 竞价管理",
                "本模块只给**出价系数建议**与**系数记录**（定位设置上的 bidFactor），"
                    + "**不据此改钱** —— 改竞价是钱，走 14.2.1.1 的护栏入口"),
            new AdTargetingCapabilityDimension(
                "platform_targeting_sync",
                "🔴 平台侧定位结构的**自动同步**（平台改了定位，本系统自动跟上）",
                false,
                "—",
                "平台适配层（channel/vendor）的平台广告 API 同步",
                "没有平台同步源 ⇒ 定位数据的新鲜度取决于谁来登记 / 谁来解读；"
                    + "「数据截至 X 时间」必须一起显示，不能用旧数据假装是新的")),
        DIMENSION_VALUES,
        STATUSES,
        guardrails(),
        INTERPRETATION_NOTE,
        PLATFORM_WRITEBACK_NOTE,
        "上游 = 14.1.2.1 广告组管理（V281：ad_campaign_group.match_type / .targeting_settings —— "
            + "**平台原文，只读消费**；本模块的解读结果落在自己的表上，不改写上游列的语义）、"
            + "14.1.1.1 活动列表（V273：ad_campaign，只读引用）、"
            + "14.1.1.2 活动详情（V277：ad_campaign_group，只读引用）。",
        "下游 = 14.2.1.2 分时策略（🔴 本模块用 GET /daypart-profile 把时段维度数据交出去）、"
            + "14.2.1.1 竞价管理（出价系数建议由它执行）。"
            + "🔴 本模块**不 import** 同批并行车道的任何类、**不建**它们的表、**不猜**它们的 URL；"
            + "🔴 本模块**不执行**出价调整、不做平台回写、不做平台侧定位同步 —— "
            + "能力声明里对应维度显式 available=false，不用空列表冒充已交付。");
  }

  private static AdTargetingGuardrails guardrails() {
    return new AdTargetingGuardrails(
        MIN_BID_FACTOR,
        MAX_BID_FACTOR,
        MIN_SPEND_FOR_SUGGESTION,
        MIN_IMPRESSIONS_FOR_SUGGESTION,
        MIN_CLICKS_FOR_SUGGESTION,
        TARGET_ACOS,
        HARD_ACOS,
        DIMENSION_SPREAD_FACTOR,
        MAX_DIMENSIONS_PER_INTERPRET,
        "🔴 **建议门槛与系数上下限是公开的**：运营在点「收窄」之前就该知道门槛在哪、天花板在哪 —— "
            + "建议引擎的阈值不公开，界面就变成黑箱。"
            + "🔴 系数下限 0.1：再低就是停投，而「停投」这个动作有它自己的入口（收窄投放），"
            + "用系数表达停投是在绕路。"
            + "🔴 系数上限 1.5：一次最多加到 1.5 倍 —— 「表现好就多花钱」也得有上限，"
            + "一次加到 3 倍就是把预算一次性押上去。"
            + "🔴 建议门槛（花费 / 展示 / 点击）不满足时建议**不可执行**（actionable=false），"
            + "宁可少给一条也不给一条基于噪音的建议。");
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdTargetingOptions options() {
    return new AdTargetingOptions(
        List.of(
            new AdTargetingOption("GEO", "地域"),
            new AdTargetingOption("AUDIENCE", "人群"),
            new AdTargetingOption("DEVICE", "设备"),
            new AdTargetingOption("DAYPART", "时段"),
            new AdTargetingOption("MATCH_TYPE", "匹配类型"),
            new AdTargetingOption("UNMAPPED", "未识别（平台原文认不出来的键）")),
        List.of(
            new AdTargetingOption("ACTIVE", "在投"),
            new AdTargetingOption("PAUSED", "已收窄（停投这条定位）"),
            new AdTargetingOption("ARCHIVED", "已归档")),
        List.of(
            new AdTargetingOption("updatedAtDesc", "最近更新"),
            new AdTargetingOption("createdAtDesc", "最近创建"),
            new AdTargetingOption("dimensionAsc", "维度"),
            new AdTargetingOption("valueAsc", "取值"),
            new AdTargetingOption("spendDesc", "花费从高到低")),
        guardrails(),
        "🔴 「未识别」不是兜底状态：它表示平台原文里的键本模块归一不出来，显式摆出来让人处理。");
  }

  // ==================================================================
  // 定位设置列表
  // ==================================================================

  /** 定位设置列表：真分页 + 真筛选 + 真排序。 */
  public AdTargetingSettingPage settings(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String status,
      String keyword,
      String sort,
      int page,
      int size) {
    Filter filter = settingFilter(campaignId, groupId, dimension, dimensionValue, status, keyword);
    String where = filter.where();
    List<Object> binds = filter.binds();
    String from =
        " from ad_targeting_setting t"
            + " join ad_campaign c on c.tenant_id = t.tenant_id and c.id = t.campaign_id"
            + " left join ad_campaign_group g on g.tenant_id = t.tenant_id and g.id = t.group_id";

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    long total = count("select count(*)" + from + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdTargetingSettingRow> items =
        sql.fetch(
                "select t.id, t.campaign_id, c.campaign_name, c.shop_code, c.currency,"
                    + " t.group_id, g.group_name, t.scope_kind, t.dimension, t.dimension_value,"
                    + " t.platform_raw_key, t.platform_raw_value, t.status, t.bid_factor,"
                    + " t.daypart_start_hour, t.daypart_end_hour, t.daypart_hours,"
                    + " t.daypart_crosses_midnight, t.effective_from, t.effective_to,"
                    + " t.pending_platform_writeback, t.local_changed_at, t.source, t.note,"
                    + " t.created_at, t.updated_at"
                    + from
                    + where
                    + " order by "
                    + settingOrderBy(sort)
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(AdTargetingService::toSettingRow)
            .toList();

    return new AdTargetingSettingPage(items, total, safePage, safeSize);
  }

  /** 排序字段白名单：排序表达式**绝不允许**由调用方拼进 SQL。 */
  private static String settingOrderBy(String sort) {
    String key = blankToNull(sort);
    if (key == null) {
      key = "updatedAtDesc";
    }
    return switch (key) {
      case "createdAtDesc" -> "t.created_at desc, t.id asc";
      case "dimensionAsc" -> "t.dimension asc, t.dimension_value asc, t.id asc";
      case "valueAsc" -> "t.dimension_value asc, t.dimension asc, t.id asc";
      case "spendDesc" ->
          "(select coalesce(sum(m.spend),0) from ad_targeting_metric m"
              + " where m.tenant_id = t.tenant_id and m.setting_id = t.id) desc nulls last, t.id asc";
      case "updatedAtDesc" -> "t.updated_at desc, t.id asc";
      default -> {
        if (!SETTING_SORTS.contains(key)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "排序方式只能是 updatedAtDesc / createdAtDesc / dimensionAsc / valueAsc / spendDesc");
        }
        yield "t.updated_at desc, t.id asc";
      }
    };
  }

  private Filter settingFilter(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String status,
      String keyword) {
    StringBuilder where = new StringBuilder(" where t.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String campaign = blankToNull(campaignId);
    if (campaign != null) {
      where.append(" and t.campaign_id = ?");
      binds.add(parseId(campaign));
    }
    String group = blankToNull(groupId);
    if (group != null) {
      where.append(" and t.group_id = ?");
      binds.add(parseId(group));
    }
    String dim = blankToNull(dimension);
    if (dim != null) {
      String upper = dim.toUpperCase(Locale.ROOT);
      if (!DIMENSION_VALUES.contains(upper)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位维度不合法：" + dim);
      }
      where.append(" and t.dimension = ?");
      binds.add(upper);
    }
    String value = blankToNull(dimensionValue);
    if (value != null) {
      where.append(" and t.dimension_value = ?");
      binds.add(normalizeValue(value));
    }
    String state = blankToNull(status);
    if (state != null) {
      String upper = state.toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(upper)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位状态不合法：" + status);
      }
      where.append(" and t.status = ?");
      binds.add(upper);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (t.dimension_value ilike ? or t.platform_raw_value ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    return new Filter(where.toString(), binds);
  }

  /** 定位设置列表汇总（**不受分页影响**；只给条数，不做跨币种金额相加）。 */
  public AdTargetingSettingSummary summary(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String status,
      String keyword) {
    Filter filter = settingFilter(campaignId, groupId, dimension, dimensionValue, status, keyword);
    String where = filter.where();
    List<Object> binds = filter.binds();
    String from =
        " from ad_targeting_setting t"
            + " join ad_campaign c on c.tenant_id = t.tenant_id and c.id = t.campaign_id";

    Map<String, Long> dimensionCounts = new LinkedHashMap<>();
    Map<String, Long> statusCounts = new LinkedHashMap<>();
    long total = 0L;
    for (Record r :
        sql.fetch(
            "select t.dimension d, t.status s, count(*) n" + from + where + " group by t.dimension, t.status",
            binds.toArray())) {
      dimensionCounts.merge(r.get("d", String.class), r.get("n", Long.class), Long::sum);
      statusCounts.merge(r.get("s", String.class), r.get("n", Long.class), Long::sum);
      total += r.get("n", Long.class);
    }

    Record totals =
        sql.fetchOne(
            "select count(distinct t.campaign_id) cc,"
                + " count(*) filter (where t.pending_platform_writeback) pw"
                + from
                + where,
            binds.toArray());
    long campaignCount = totals == null ? 0L : totals.get("cc", Long.class);
    long pending = totals == null ? 0L : totals.get("pw", Long.class);

    return new AdTargetingSettingSummary(
        total,
        campaignCount,
        dimensionCounts,
        statusCounts,
        pending,
        "🔴 汇总只给**条数**：定位设置的金额类字段只有出价系数（比例），跨币种相加没有意义。");
  }

  // ==================================================================
  // 定位设置详情与留痕
  // ==================================================================

  /** 定位设置详情：设置 + 该设置的区间效果 + 最近留痕 + 下游入口。 */
  public AdTargetingDetail detail(String settingId) {
    UUID id = parseId(settingId);
    Record row = loadSetting(id);
    if (row == null) {
      throw notFound("定位设置");
    }
    AdTargetingSettingRow setting = toSettingRow(row);

    List<AdTargetingEffectRow> effects =
        effectRows(
            setting.campaignId().toString(),
            setting.groupId() == null ? null : setting.groupId().toString(),
            setting.dimension(),
            setting.dimensionValue(),
            null,
            null,
            null);

    List<AdTargetingOperationRow> recent = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, campaign_id, group_id, setting_id, operation, before_value, after_value,"
                + " result, message, requested_by, reason, created_at"
                + " from ad_targeting_operation_log where tenant_id = "
                + TENANT
                + " and setting_id = ? order by created_at desc, id desc limit 20",
            id)) {
      recent.add(toOperationRow(r));
    }

    return new AdTargetingDetail(
        setting,
        effects,
        recent,
        entries(),
        "🔴 本地写只落本地：设置被标成「待回写平台」时，平台侧还没跟着改。"
            + "🔴 本模块**不改钱**：出价系数只是建议与记录，真正改竞价归 14.2.1.1。");
  }

  /** 下游入口：哪些能走、哪些不能、为什么不能。 */
  private List<AdTargetingEntry> entries() {
    return List.of(
        new AdTargetingEntry(
            "DAYPART_STRATEGY",
            "分时策略",
            true,
            DAYPART_OWNER_MODULE + "（本模块用 GET /daypart-profile 把时段数据交出去）",
            "",
            "",
            "🔴 时段画像是**本模块交给下游的可复用数据**：哪个时段值得投、哪个时段在烧钱。"
                + "🔴 指标按**时段窗口**归集（不是按小时），所以小时级拆分只能到窗口边界 —— "
                + "下游按小时排策略时要知道自己拿到的是什么粒度。"),
        new AdTargetingEntry(
            "BID_ADJUSTMENT",
            "出价调整",
            true,
            "本模块只给**出价系数建议**与**系数记录**（定位设置上的 bidFactor）",
            "14.2.1.1 竞价管理（执行侧）",
            "本模块**不执行**出价调整",
            "🔴 改竞价是钱，走 14.2.1.1 的护栏入口（带单次上限与绝对上限、逐条留审计）—— "
                + "本模块**不另起同义端点**：两个端点改同一笔钱，审计就会分叉。"),
        new AdTargetingEntry(
            "PLATFORM_WRITEBACK",
            "平台回写",
            false,
            "—",
            "平台适配层（channel/vendor）的平台回写队列",
            "本地写只落本地并标记「待回写平台」",
            "🔴 把「本地已收窄」当成「平台侧已收窄」就是在骗人（那会让运营以为投放真的停了，"
                + "其实在烧钱）。"));
  }

  /** 留痕：定位的每一次写都要有据可查（被拒 / 跳过的同样留）。 */
  public AdTargetingOperationPage operations(String settingId, int page, int size) {
    UUID id = parseId(settingId);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    long total =
        count(
            "select count(*) from ad_targeting_operation_log where tenant_id = " + TENANT + " and setting_id = ?",
            id);
    List<Object> binds = new ArrayList<>();
    binds.add(id);
    binds.add(safeSize);
    binds.add(safePage * safeSize);
    List<AdTargetingOperationRow> items =
        sql.fetch(
                "select id, campaign_id, group_id, setting_id, operation, before_value, after_value,"
                    + " result, message, requested_by, reason, created_at"
                    + " from ad_targeting_operation_log where tenant_id = "
                    + TENANT
                    + " and setting_id = ? order by created_at desc, id desc limit ? offset ?",
                binds.toArray())
            .stream()
            .map(AdTargetingService::toOperationRow)
            .toList();
    return new AdTargetingOperationPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 🔴 定位效果对比表
  // ==================================================================

  /**
   * 定位效果对比表：真分页 + 真筛选 + 真排序 + 区间汇总。
   *
   * <p>🔴 派生比率（CTR / CPC / CVR / ACOS / ROAS）**分母为 0 时是 null**：
   * 「不可比」与「真的是零」在界面上必须分开（照抄 V274 同一款纪律）。
   * 🔴 汇总**不做跨币种金额相加**：金额按币种分列，只有计数（与币种无关）才相加。
   */
  public AdTargetingEffectPage effects(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String currency,
      String from,
      String to,
      String sort,
      int page,
      int size) {
    LocalDate[] range = resolveRange(from, to);
    Filter filter = effectFilter(campaignId, groupId, dimension, dimensionValue, currency, range);
    String where = filter.where();
    List<Object> binds = filter.binds();

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<AdTargetingEffectRow> all = effectRows(binds, where, sort, null, null);
    long total = all.size();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<AdTargetingEffectRow> items =
        sql.fetch(
                effectSelect()
                    + " , case when sum(m.sales) > 0 then sum(m.spend) / sum(m.sales) end acos_expr"
                    + effectFrom()
                    + where
                    + effectGroupBy()
                    + " order by "
                    + effectOrderBy(sort)
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(AdTargetingService::toEffectRow)
            .toList();

    return new AdTargetingEffectPage(items, total, safePage, safeSize, effectSummary(all, range));
  }

  /** 区间汇总（**不受分页影响**）：计数相加，金额按币种分列。 */
  private AdTargetingEffectSummary effectSummary(List<AdTargetingEffectRow> rows, LocalDate[] range) {
    Map<String, Long> dimensionCounts = new LinkedHashMap<>();
    Map<String, BigDecimal> spendByCurrency = new LinkedHashMap<>();
    Map<String, BigDecimal> salesByCurrency = new LinkedHashMap<>();
    Set<UUID> campaigns = new java.util.LinkedHashSet<>();
    long impressions = 0L;
    long clicks = 0L;
    long conversions = 0L;

    for (AdTargetingEffectRow r : rows) {
      dimensionCounts.merge(r.dimension(), 1L, Long::sum);
      campaigns.add(r.campaignId());
      spendByCurrency.merge(r.currency(), r.spend() == null ? zero() : r.spend(), BigDecimal::add);
      salesByCurrency.merge(r.currency(), r.sales() == null ? zero() : r.sales(), BigDecimal::add);
      impressions += r.impressions();
      clicks += r.clicks();
      conversions += r.conversions();
    }

    return new AdTargetingEffectSummary(
        rows.size(),
        campaigns.size(),
        dimensionCounts,
        spendByCurrency,
        salesByCurrency,
        impressions,
        clicks,
        conversions,
        "🔴 汇总口径：展示 / 点击 / 转化是**计数**（与币种无关）⇒ 可以相加；"
            + "花费与销售额是**金额** ⇒ **按币种分列**，跨币种相加没有意义"
            + "（那是把美元和人民币加起来）。"
            + "🔴 区间 = "
            + range[0]
            + " ~ "
            + range[1]
            + "（UTC 日）；未指定时间范围时默认最近 "
            + DEFAULT_RANGE_DAYS
            + " 个 UTC 日。"
            + "🔴 派生比率（ACOS / ROAS）在分母为 0 时给 **null（不可比）**，不是 0。");
  }

  private String effectSelect() {
    return "select m.campaign_id, c.campaign_name, c.shop_code, m.group_id, g.group_name,"
        + " case when m.group_id is null then 'CAMPAIGN' else 'GROUP' end scope_kind,"
        + " m.dimension, m.dimension_value, m.currency,"
        + " coalesce(sum(m.impressions),0) impressions, coalesce(sum(m.clicks),0) clicks,"
        + " coalesce(sum(m.conversions),0) conversions, coalesce(sum(m.spend),0) spend,"
        + " coalesce(sum(m.sales),0) sales,"
        + " coalesce(sum(case when m.conversions = 0 then m.spend else 0 end),0) wasted_spend,"
        + " max(m.synced_at) data_as_of, count(distinct m.setting_id) setting_count,"
        + " sum(coalesce(sum(m.spend),0)) over (partition by m.campaign_id, m.group_id, m.dimension,"
        + " m.currency) dim_spend";
  }

  /**
   * 🔴 聚合的 GROUP BY：少了它，「按维度聚合」就变成了「把每个指标行当一行返回」。
   */
  private String effectGroupBy() {
    return " group by m.campaign_id, c.campaign_name, c.shop_code, m.group_id, g.group_name,"
        + " m.dimension, m.dimension_value, m.currency";
  }

  private String effectFrom() {
    return " from ad_targeting_metric m"
        + " join ad_campaign c on c.tenant_id = m.tenant_id and c.id = m.campaign_id"
        + " left join ad_campaign_group g on g.tenant_id = m.tenant_id and g.id = m.group_id";
  }

  private Filter effectFilter(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String currency,
      LocalDate[] range) {
    StringBuilder where = new StringBuilder(" where m.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String campaign = blankToNull(campaignId);
    if (campaign != null) {
      where.append(" and m.campaign_id = ?");
      binds.add(parseId(campaign));
    }
    String group = blankToNull(groupId);
    if (group != null) {
      where.append(" and m.group_id = ?");
      binds.add(parseId(group));
    }
    String dim = blankToNull(dimension);
    if (dim != null) {
      String upper = dim.toUpperCase(Locale.ROOT);
      if (!DIMENSION_VALUES.contains(upper)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位维度不合法：" + dim);
      }
      where.append(" and m.dimension = ?");
      binds.add(upper);
    }
    String value = blankToNull(dimensionValue);
    if (value != null) {
      where.append(" and m.dimension_value = ?");
      binds.add(normalizeValue(value));
    }
    String ccy = blankToNull(currency);
    if (ccy != null) {
      where.append(" and m.currency = ?");
      binds.add(ccy.toUpperCase(Locale.ROOT));
    }
    where.append(" and m.bucket_date >= ? and m.bucket_date <= ?");
    binds.add(java.sql.Date.valueOf(range[0]));
    binds.add(java.sql.Date.valueOf(range[1]));
    return new Filter(where.toString(), binds);
  }

  /** 效果对比表的排序白名单。 */
  private static String effectOrderBy(String sort) {
    String key = blankToNull(sort);
    if (key == null) {
      key = "spendDesc";
    }
    String tail = " m.campaign_id asc, m.dimension asc, m.dimension_value asc, m.currency asc";
    return switch (key) {
      case "spendAsc" -> "spend asc nulls last," + tail;
      case "acosDesc" -> "acos_expr desc nulls last," + tail;
      case "acosAsc" -> "acos_expr asc nulls last," + tail;
      case "clicksDesc" -> "clicks desc," + tail;
      case "conversionsDesc" -> "conversions desc," + tail;
      case "wastedSpendDesc" -> "wasted_spend desc," + tail;
      case "salesDesc" -> "sales desc," + tail;
      case "dimensionAsc" -> "m.dimension asc, m.dimension_value asc, m.currency asc";
      case "spendDesc" -> "spend desc," + tail;
      default -> {
        if (!EFFECT_SORTS.contains(key)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "排序方式只能是 spendDesc / spendAsc / acosDesc / acosAsc / clicksDesc /"
                  + " conversionsDesc / wastedSpendDesc / salesDesc / dimensionAsc");
        }
        yield "spend desc," + tail;
      }
    };
  }

  /** 取聚合行（{@code limit} 为 null 时取全部 —— 建议引擎要全量）。 */
  private List<AdTargetingEffectRow> effectRows(
      List<Object> binds, String where, String sort, Integer limit, Integer offset) {
    StringBuilder sqlText =
        new StringBuilder(
            effectSelect()
                + " , case when sum(m.sales) > 0 then sum(m.spend) / sum(m.sales) end acos_expr"
                + effectFrom()
                + where
                + effectGroupBy());
    List<Object> args = new ArrayList<>(binds);
    if (limit != null) {
      sqlText.append(" order by ").append(effectOrderBy(sort)).append(" limit ? offset ?");
      args.add(limit);
      args.add(offset == null ? 0 : offset);
    }
    return sql.fetch(sqlText.toString(), args.toArray()).stream()
        .map(AdTargetingService::toEffectRow)
        .toList();
  }

  /** 便捷入口：按筛选取全量聚合行（详情页用）。 */
  private List<AdTargetingEffectRow> effectRows(
      String campaignId,
      String groupId,
      String dimension,
      String dimensionValue,
      String currency,
      String from,
      String to) {
    LocalDate[] range = resolveRange(from, to);
    Filter filter = effectFilter(campaignId, groupId, dimension, dimensionValue, currency, range);
    return effectRows(filter.binds(), filter.where(), "spendDesc", null, null);
  }

  private static AdTargetingEffectRow toEffectRow(Record r) {
    BigDecimal spend = r.get("spend", BigDecimal.class);
    BigDecimal sales = r.get("sales", BigDecimal.class);
    BigDecimal wasted = r.get("wasted_spend", BigDecimal.class);
    BigDecimal dimSpend = r.get("dim_spend", BigDecimal.class);
    long impressions = r.get("impressions", Long.class);
    long clicks = r.get("clicks", Long.class);
    long conversions = r.get("conversions", Long.class);

    return new AdTargetingEffectRow(
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("shop_code", String.class),
        r.get("group_id", UUID.class),
        r.get("group_name", String.class),
        r.get("scope_kind", String.class),
        r.get("dimension", String.class),
        r.get("dimension_value", String.class),
        r.get("currency", String.class),
        impressions,
        clicks,
        conversions,
        spend,
        sales,
        ratio(BigDecimal.valueOf(clicks), BigDecimal.valueOf(impressions)),
        ratio(spend, BigDecimal.valueOf(clicks)),
        ratio(BigDecimal.valueOf(conversions), BigDecimal.valueOf(clicks)),
        ratio(spend, sales),
        ratio(sales, spend),
        wasted,
        ratio(wasted, spend),
        ratio(spend, dimSpend),
        r.get("setting_count", Long.class),
        r.get("data_as_of", OffsetDateTime.class));
  }

  // ==================================================================
  // 🔴 调整建议
  // ==================================================================

  /**
   * 调整建议：每条都带**依据数值** + 口径 + 阈值。
   *
   * <p>🔴 五条规则（全部现算，全部带真实数字）：
   *
   * <ol>
   *   <li>{@code WASTED_TARGETING} —— 花了钱、零转化、零销售 ⇒ 建议**收窄**（严重度 HIGH）。
   *   <li>{@code ACOS_OVER_TARGET} —— ACOS 超过目标 ⇒ 建议**降价**，系数 = 目标 ACOS / 当前 ACOS。
   *   <li>{@code UNDERPERFORMING} —— ACOS 超过硬上限 ⇒ 建议**降价**（严重度 HIGH）。
   *   <li>{@code SCALE_UP} —— ACOS 低于目标且花够了 ⇒ 建议**加价**（系数上限 1.5）。
   *   <li>{@code DIMENSION_SPREAD} —— 同一维度内最差取值的 ACOS ≥ 最佳 × 倍数阈值
   *       ⇒ 建议**收窄到表现好的取值**（蓝图的加粗句）。
   * </ol>
   *
   * <p>🔴 样本不足（展示 / 点击 / 花费不够）时建议 **actionable=false** ——
   * 宁可说「样本不足」，也不给一条基于噪音的建议。
   */
  public AdTargetingSuggestionResult suggestions(
      String campaignId, String groupId, String dimension, String from, String to) {
    LocalDate[] range = resolveRange(from, to);
    Filter filter = effectFilter(campaignId, groupId, dimension, null, null, range);
    List<AdTargetingEffectRow> rows = effectRows(filter.binds(), filter.where(), "spendDesc", null, null);

    List<AdTargetingSuggestion> items = new ArrayList<>();
    if (rows.isEmpty()) {
      return new AdTargetingSuggestionResult(
          items,
          0,
          0,
          "🔴 区间 "
              + range[0]
              + " ~ "
              + range[1]
              + " 内**没有定位效果数据** ⇒ 没有可比的效果，也就没有建议。"
              + "先把定位维度效果数据同步进来（POST /ad-targetings/metrics），"
              + "或者换个时间范围 / 筛选条件。"
              + "🔴 本模块**不拿别的口径冒充**：没有定位维度指标时，"
              + "拿活动级指标或关键词指标顶上来是假事实。");
    }

    // ---- 逐行规则 ----
    for (AdTargetingEffectRow r : rows) {
      boolean sampleOk = sampleOk(r);
      BigDecimal spend = r.spend() == null ? zero() : r.spend();
      BigDecimal sales = r.sales() == null ? zero() : r.sales();

      // 规则 1：无效定位（花了钱、零转化、零销售）
      if (spend.compareTo(MIN_SPEND_FOR_SUGGESTION) >= 0
          && r.conversions() == 0
          && sales.signum() == 0) {
        List<String> evidence =
            List.of(
                "区间花费 " + money(spend, r.currency()) + "，转化 0 次，销售额 " + money(sales, r.currency()),
                "展示 " + r.impressions() + " 次 / 点击 " + r.clicks() + " 次",
                shareEvidence(r),
                "建议门槛：花费 ≥ " + MIN_SPEND_FOR_SUGGESTION + " 且 转化 = 0 且 销售额 = 0");
        items.add(
            suggestion(
                "WASTED_TARGETING",
                "无效定位：花了钱但零转化",
                "NARROW",
                "HIGH",
                r,
                null,
                evidence,
                "规则 WASTED_TARGETING：花费 ≥ "
                    + MIN_SPEND_FOR_SUGGESTION
                    + " 且 转化数 = 0 且 销售额 = 0 ⇒ 这条定位在烧钱，"
                    + "建议**收窄投放**（蓝图的加粗句：「无效定位的花费是最容易省下来的钱」）。",
                sampleOk,
                sampleOk
                    ? "🔴 建议**收窄投放**（把这条定位置为暂停）。本模块**不替你改价**："
                        + "改竞价归 14.2.1.1。"
                    : "🔴 样本不足（展示/点击未达门槛），**别照做** —— 先把样本攒够再判断。"));
      }

      // 规则 2 / 3：ACOS 超标
      if (r.acos() != null) {
        if (r.acos().compareTo(HARD_ACOS) > 0) {
          items.add(
              acosSuggestion(
                  "UNDERPERFORMING",
                  "严重超标：ACOS 超过硬上限",
                  "HIGH",
                  r,
                  sampleOk));
        } else if (r.acos().compareTo(TARGET_ACOS) > 0) {
          items.add(
              acosSuggestion(
                  "ACOS_OVER_TARGET",
                  "ACOS 超过目标",
                  "MEDIUM",
                  r,
                  sampleOk));
        } else if (spend.compareTo(MIN_SPEND_FOR_SUGGESTION) >= 0) {
          // 规则 4：表现好 ⇒ 建议加价
          BigDecimal factor =
              clamp(
                  ratio(TARGET_ACOS, r.acos()),
                  BigDecimal.ONE,
                  MAX_BID_FACTOR);
          List<String> evidence =
              List.of(
                  "区间花费 " + money(spend, r.currency()) + "，销售额 " + money(sales, r.currency()),
                  "ACOS " + plain(r.acos()) + " ≤ 目标 ACOS " + plain(TARGET_ACOS),
                  "建议出价系数 " + plain(factor) + "（上限 " + plain(MAX_BID_FACTOR) + "）",
                  "展示 " + r.impressions() + " 次 / 点击 " + r.clicks() + " 次 / 转化 " + r.conversions() + " 次",
                  shareEvidence(r));
          items.add(
              suggestion(
                  "SCALE_UP",
                  "表现好：ACOS 低于目标，可以加价放量",
                  "INCREASE_BID",
                  "LOW",
                  r,
                  factor,
                  evidence,
                  "规则 SCALE_UP：ACOS ≤ 目标 ACOS "
                      + plain(TARGET_ACOS)
                      + " 且 花费 ≥ "
                      + MIN_SPEND_FOR_SUGGESTION
                      + " ⇒ 建议**适度加价放量**；系数 = 目标 ACOS / 当前 ACOS，"
                      + "上限 "
                      + plain(MAX_BID_FACTOR)
                      + "（一次最多加到 1.5 倍 —— 一次加到 3 倍就是把预算一次性押上去）。",
                  sampleOk,
                  sampleOk
                      ? "🔴 建议**加价放量**：本模块只给系数与记录，**不替你改价** —— 改竞价归 14.2.1.1。"
                      : "🔴 样本不足（展示/点击未达门槛），**别照做**。"));
        }
      }
    }

    // 规则 5：维度内差异过大 ⇒ 建议收窄到表现好的取值
    items.addAll(spreadSuggestions(rows));

    // 排序：严重度优先，再按浪费额
    List<AdTargetingSuggestion> sorted = new ArrayList<>(items);
    sorted.sort(
        java.util.Comparator.comparingInt((AdTargetingSuggestion s) -> severityRank(s.severity()))
            .thenComparing(
                s -> s.wastedSpend() == null ? BigDecimal.ZERO : s.wastedSpend(),
                java.util.Comparator.reverseOrder())
            .thenComparing(AdTargetingSuggestion::dimension)
            .thenComparing(AdTargetingSuggestion::dimensionValue));

    int actionable = (int) sorted.stream().filter(AdTargetingSuggestion::actionable).count();
    return new AdTargetingSuggestionResult(
        sorted,
        sorted.size(),
        actionable,
        "🔴 区间 = "
            + range[0]
            + " ~ "
            + range[1]
            + "（UTC 日）。"
            + "🔴 每条建议都带**依据数值**（evidence）与**口径**（basis：规则 + 用了哪些阈值）；"
            + "样本不足的建议 actionable=false —— 宁可少给一条，也不给一条基于噪音的建议。"
            + "🔴 金额与比率**只在同币种内**比较；销售额为 0 时 ACOS 不可比（null），"
            + "这类行按「无效定位」规则处理，不按 ACOS 排序。");
  }

  /** ACOS 超标类建议（规则 2 / 3 共用）。 */
  private AdTargetingSuggestion acosSuggestion(
      String ruleId, String title, String severity, AdTargetingEffectRow r, boolean sampleOk) {
    BigDecimal spend = r.spend() == null ? zero() : r.spend();
    BigDecimal sales = r.sales() == null ? zero() : r.sales();
    BigDecimal factor = clamp(ratio(TARGET_ACOS, r.acos()), MIN_BID_FACTOR, BigDecimal.ONE);
    BigDecimal cut = BigDecimal.ONE.subtract(factor);
    List<String> evidence =
        List.of(
            "区间花费 " + money(spend, r.currency()) + "，销售额 " + money(sales, r.currency()),
            "ACOS " + plain(r.acos()) + "，目标 ACOS " + plain(TARGET_ACOS) + "，硬上限 " + plain(HARD_ACOS),
            "建议出价系数 " + plain(factor) + "（相当于降价 " + plain(cut.setScale(4, RoundingMode.HALF_UP)) + "）",
            "展示 " + r.impressions() + " 次 / 点击 " + r.clicks() + " 次 / 转化 " + r.conversions() + " 次",
            shareEvidence(r));
    return suggestion(
        ruleId,
        title,
        "REDUCE_BID",
        severity,
        r,
        factor,
        evidence,
        "规则 "
            + ruleId
            + "：ACOS > 目标 ACOS "
            + plain(TARGET_ACOS)
            + "（硬上限 "
            + plain(HARD_ACOS)
            + "）⇒ 建议**降价**；系数 = 目标 ACOS / 当前 ACOS，下限 "
            + plain(MIN_BID_FACTOR)
            + "（再低就是停投，而停投有它自己的入口：收窄投放）。",
        sampleOk,
        sampleOk
            ? "🔴 建议**降价**：本模块只给系数与记录，**不替你改价** —— 改竞价归 14.2.1.1。"
            : "🔴 样本不足（展示/点击未达门槛），**别照做**。");
  }

  /** 规则 5：同一维度内差异过大 ⇒ 建议收窄到表现好的取值。 */
  private List<AdTargetingSuggestion> spreadSuggestions(List<AdTargetingEffectRow> rows) {
    Map<String, List<AdTargetingEffectRow>> groups = new LinkedHashMap<>();
    for (AdTargetingEffectRow r : rows) {
      if (r.acos() == null) {
        continue;
      }
      String key = r.campaignId() + "|" + (r.groupId() == null ? "" : r.groupId()) + "|" + r.dimension() + "|" + r.currency();
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
    }

    List<AdTargetingSuggestion> out = new ArrayList<>();
    for (List<AdTargetingEffectRow> group : groups.values()) {
      List<AdTargetingEffectRow> comparable =
          group.stream()
              .filter(
                  r ->
                      r.spend() != null
                          && r.spend().compareTo(MIN_SPEND_FOR_SUGGESTION) >= 0)
              .toList();
      if (comparable.size() < 2) {
        continue;
      }
      AdTargetingEffectRow best = null;
      AdTargetingEffectRow worst = null;
      for (AdTargetingEffectRow r : comparable) {
        if (best == null || r.acos().compareTo(best.acos()) < 0) {
          best = r;
        }
        if (worst == null || r.acos().compareTo(worst.acos()) > 0) {
          worst = r;
        }
      }
      if (best == null || worst == null || best.equals(worst)) {
        continue;
      }
      BigDecimal times = ratio(worst.acos(), best.acos());
      if (times == null || times.compareTo(DIMENSION_SPREAD_FACTOR) < 0) {
        continue;
      }
      boolean sampleOk = sampleOk(worst);
      List<String> evidence =
          List.of(
              "同维度 "
                  + worst.dimension()
                  + " 内最差取值 "
                  + worst.dimensionValue()
                  + " 的 ACOS "
                  + plain(worst.acos())
                  + "，是最佳取值 "
                  + best.dimensionValue()
                  + " 的 ACOS "
                  + plain(best.acos())
                  + " 的 "
                  + plain(times.setScale(2, RoundingMode.HALF_UP))
                  + " 倍",
              "最差取值花费 " + money(worst.spend(), worst.currency()) + "，转化 " + worst.conversions() + " 次",
              "最佳取值花费 " + money(best.spend(), best.currency()) + "，转化 " + best.conversions() + " 次",
              "差异阈值：同维度内最差 ACOS ≥ 最佳 × " + plain(DIMENSION_SPREAD_FACTOR));
      out.add(
          suggestion(
              "DIMENSION_SPREAD",
              "维度内差异过大：建议收窄到表现好的取值",
              "NARROW",
              "MEDIUM",
              worst,
              null,
              evidence,
              "规则 DIMENSION_SPREAD：同一（活动 / 组 / 维度 / 币种）内，最差取值的 ACOS ≥ 最佳取值 × "
                  + plain(DIMENSION_SPREAD_FACTOR)
                  + " ⇒ 建议**收窄投放**，把预算留给表现好的取值"
                  + "（蓝图的加粗句：「定位效果差异大时应收窄投放」）。",
              sampleOk,
              sampleOk
                  ? "🔴 建议**收窄到表现好的取值**（本模块提供收窄动作，收窄后要盯住留下来的取值的指标变化）。"
                  : "🔴 样本不足（展示/点击未达门槛），**别照做**。"));
    }
    return out;
  }

  /** 组装一条建议（证据与口径都在里面 —— 没有它们就是硬编码文案）。 */
  private AdTargetingSuggestion suggestion(
      String ruleId,
      String title,
      String action,
      String severity,
      AdTargetingEffectRow r,
      BigDecimal bidFactor,
      List<String> evidence,
      String basis,
      boolean actionable,
      String note) {
    return new AdTargetingSuggestion(
        ruleId,
        title,
        action,
        severity,
        r.campaignId(),
        r.campaignName(),
        r.groupId(),
        r.groupName(),
        r.dimension(),
        r.dimensionValue(),
        r.currency(),
        r.spend(),
        r.sales(),
        r.impressions(),
        r.clicks(),
        r.conversions(),
        r.acos(),
        TARGET_ACOS,
        r.wastedSpend(),
        r.spendShare(),
        bidFactor,
        evidence,
        basis,
        actionable,
        note);
  }

  /** 样本够不够：展示、点击、花费三项门槛都要过。 */
  private boolean sampleOk(AdTargetingEffectRow r) {
    BigDecimal spend = r.spend() == null ? zero() : r.spend();
    return r.impressions() >= MIN_IMPRESSIONS_FOR_SUGGESTION
        && r.clicks() >= MIN_CLICKS_FOR_SUGGESTION
        && spend.compareTo(MIN_SPEND_FOR_SUGGESTION) >= 0;
  }

  private static int severityRank(String severity) {
    if ("HIGH".equals(severity)) {
      return 0;
    }
    if ("MEDIUM".equals(severity)) {
      return 1;
    }
    return 2;
  }

  private static String shareEvidence(AdTargetingEffectRow r) {
    if (r.spendShare() == null) {
      return "占同维度花费的比重：不可比（该维度没有可加总的花费）";
    }
    return "占同维度花费的比重 " + plain(r.spendShare().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)) + "%";
  }

  // ==================================================================
  // 🔴 时段画像（供下游 14.2.1.2 分时策略复用）
  // ==================================================================

  /**
   * 时段画像：**本模块交给下游 14.2.1.2 分时策略的可复用数据**。
   *
   * <p>🔴 指标按**时段窗口**归集（不是按小时）：平台回报的定位效果就是按窗口给的，
   * 硬拆成小时等于编数据。页面与下游要知道自己拿到的是什么粒度。
   */
  public AdTargetingDaypartProfile daypartProfile(
      String campaignId, String groupId, String from, String to) {
    LocalDate[] range = resolveRange(from, to);
    Filter filter = effectFilter(campaignId, groupId, "DAYPART", null, null, range);
    List<AdTargetingEffectRow> rows =
        sql.fetch(
                effectSelect()
                    + effectFrom()
                    + filter.where()
                    + effectGroupBy()
                    + " order by m.campaign_id asc, m.group_id asc nulls first, m.dimension_value asc",
                filter.binds().toArray())
            .stream()
            .map(AdTargetingService::toEffectRow)
            .toList();

    // 取每个 (campaign, group, dimension_value) 的时段窗口（来自定位设置）
    Map<String, DaypartWindow> windows = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select campaign_id, group_id, dimension_value, daypart_start_hour, daypart_end_hour,"
                + " daypart_hours, daypart_crosses_midnight"
                + " from ad_targeting_setting where tenant_id = "
                + TENANT
                + " and dimension = 'DAYPART' and daypart_hours is not null")) {
      String key =
          r.get("campaign_id", UUID.class)
              + "|"
              + (r.get("group_id", UUID.class) == null ? "" : r.get("group_id", UUID.class))
              + "|"
              + r.get("dimension_value", String.class);
      windows.put(
          key,
          new DaypartWindow(
              r.get("daypart_start_hour", Integer.class),
              r.get("daypart_end_hour", Integer.class),
              r.get("daypart_hours", BigDecimal.class),
              r.get("daypart_crosses_midnight", Boolean.class)));
    }

    List<AdTargetingDaypartRow> items = new ArrayList<>();
    for (AdTargetingEffectRow r : rows) {
      String key =
          r.campaignId()
              + "|"
              + (r.groupId() == null ? "" : r.groupId())
              + "|"
              + r.dimensionValue();
      DaypartWindow window = windows.get(key);
      items.add(
          new AdTargetingDaypartRow(
              r.campaignId(),
              r.campaignName(),
              r.groupId(),
              r.groupName(),
              r.dimensionValue(),
              window == null ? null : window.startHour(),
              window == null ? null : window.endHour(),
              window != null && window.crossesMidnight(),
              window == null ? null : window.hours(),
              r.impressions(),
              r.clicks(),
              r.conversions(),
              r.spend(),
              r.sales(),
              r.currency(),
              r.acos(),
              r.roas()));
    }

    return new AdTargetingDaypartProfile(
        items,
        DAYPART_OWNER_MODULE,
        "🔴 这是**本模块交给下游 "
            + DAYPART_OWNER_MODULE
            + " 的可复用数据**：哪个时段值得投、哪个时段在烧钱（按区间聚合）。"
            + "🔴 指标按**时段窗口**归集，**不是按小时** —— 平台回报的定位效果就是按窗口给的，"
            + "硬拆成小时等于编数据；下游按小时排策略时要知道自己拿到的是什么粒度。"
            + "🔴 时段窗口解析不出来的定位（平台给了个看不懂的时段串）**不在这里**（窗口列为空），"
            + "它们留在定位设置里显示「未识别」。"
            + "🔴 区间 = "
            + range[0]
            + " ~ "
            + range[1]
            + "（UTC 日）；金额按币种分行，跨币种不相加。");
  }

  // ==================================================================
  // 写入口 1：新建定位设置
  // ==================================================================

  /**
   * 新建定位设置（本地真落库）。
   *
   * <p>🔴 三条边界：
   *
   * <ol>
   *   <li><b>活动必须存在</b>（走上游 14.1.1.1 的读取入口，跨租户与不存在都是 404）。
   *   <li><b>组级定位的组必须属于该活动</b> —— 挂错活动的组等于定位挂在了错的投放上。
   *   <li><b>同归属同维度同取值只有一条</b>（唯一索引钉死）：重复解读同一条原文不会造出两条。
   * </ol>
   */
  public AdTargetingSettingRow create(AdTargetingCreateRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    var campaign = campaigns.campaign(request.campaignId().toString()).campaign();

    String dimension = request.dimension().trim().toUpperCase(Locale.ROOT);
    if (!DIMENSION_VALUES.contains(dimension)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位维度不合法：" + request.dimension());
    }
    String dimensionValue = normalizeValue(request.dimensionValue());
    if (dimensionValue.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位取值不能为空");
    }
    if (dimensionValue.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位取值最长 200 个字符");
    }

    UUID groupId = request.groupId();
    String scopeKind = groupId == null ? "CAMPAIGN" : "GROUP";
    if (groupId != null) {
      requireGroupInCampaign(groupId, campaign.id());
    }

    // 🔴 DAYPART 维度必须能解析出小时窗口：时段是**窗口**，
    //   解析不出来的取值不能落成一条「没有窗口的时段定位」（那会让下游分时策略拿到空窗口）。
    Integer daypartStart = null;
    Integer daypartEnd = null;
    BigDecimal daypartHours = null;
    boolean crossesMidnight = false;
    if ("DAYPART".equals(dimension)) {
      DaypartWindow window = parseDaypart(dimensionValue);
      if (window == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "时段取值「"
                + dimensionValue
                + "」解析不出小时窗口（可用 09:00-17:00 / 9am-5pm / MORNING 这类形状）；"
                + "平台原文请走解读入口 POST /channel/ad-targetings/interpret（那里会显式报出解析结果）");
      }
      daypartStart = window.startHour();
      daypartEnd = window.endHour();
      daypartHours = window.hours();
      crossesMidnight = window.crossesMidnight();
    }

    BigDecimal bidFactor = request.bidFactor() == null ? BigDecimal.ONE : request.bidFactor();
    validateBidFactor(bidFactor);

    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? OffsetDateTime.now() : request.effectiveFrom();
    OffsetDateTime effectiveTo = request.effectiveTo();
    if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效结束时刻不能早于生效起始时刻");
    }

    UUID id = Ids.next();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into ad_targeting_setting (tenant_id,id,campaign_id,group_id,scope_kind,dimension,"
            + " dimension_value,platform_raw_key,platform_raw_value,status,effective_from,effective_to,"
            + " daypart_start_hour,daypart_end_hour,daypart_hours,daypart_crosses_midnight,"
            + " bid_factor,pending_platform_writeback,local_changed_at,source,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,?::timestamptz,?,?,?,?,?,false,?::timestamptz,"
            + " 'MANUAL','',now(),now())",
        tenant,
        id,
        campaign.id(),
        groupId,
        scopeKind,
        dimension,
        dimensionValue,
        bounded(request.platformRawKey(), 64, "平台原文键"),
        bounded(request.platformRawValue(), 500, "平台原文取值"),
        effectiveFrom,
        effectiveTo,
        daypartStart,
        daypartEnd,
        daypartHours,
        crossesMidnight,
        bidFactor,
        now,
        "");
    AdTargetingSettingRow row = toSettingRow(loadSetting(id));
    logOperation(
        tenant,
        actor,
        campaign.id(),
        groupId,
        id,
        "CREATE_SETTING",
        "",
        snapshotOf(row),
        "APPLIED",
        "已新建（本地），尚未回写平台",
        request.reason());
    return row;
  }

  // ==================================================================
  // 写入口 2：改定位设置
  // ==================================================================

  /** 改定位设置（部分更新：没给的字段不改）。 */
  public AdTargetingSettingRow update(String settingId, AdTargetingUpdateRequest request) {
    UUID id = parseId(settingId);
    Record row = loadSetting(id);
    if (row == null) {
      throw notFound("定位设置");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    AdTargetingSettingRow before = toSettingRow(row);

    String dimensionValue =
        request.dimensionValue() == null
            ? before.dimensionValue()
            : normalizeValue(request.dimensionValue());
    if (dimensionValue.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位取值不能为空");
    }
    if (dimensionValue.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位取值最长 200 个字符");
    }

    String status = before.status();
    if (request.status() != null) {
      String candidate = request.status().trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(candidate)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位状态不合法：" + request.status());
      }
      status = candidate;
    }

    BigDecimal bidFactor = before.bidFactor();
    if (request.bidFactor() != null) {
      validateBidFactor(request.bidFactor());
      bidFactor = request.bidFactor();
    }

    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? before.effectiveFrom() : request.effectiveFrom();
    OffsetDateTime effectiveTo =
        Boolean.TRUE.equals(request.clearEffectiveTo()) ? null : before.effectiveTo();
    if (request.effectiveTo() != null) {
      effectiveTo = request.effectiveTo();
    }
    if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效结束时刻不能早于生效起始时刻");
    }

    String note = request.note() == null ? before.note() : bounded(request.note(), 500, "备注");

    List<String> changed = new ArrayList<>();
    if (!dimensionValue.equals(before.dimensionValue())) {
      changed.add("dimensionValue");
    }
    if (!status.equals(before.status())) {
      changed.add("status");
    }
    if (!sameMoney(bidFactor, before.bidFactor())) {
      changed.add("bidFactor");
    }
    if (!effectiveFrom.equals(before.effectiveFrom())) {
      changed.add("effectiveFrom");
    }
    if (!java.util.Objects.equals(effectiveTo, before.effectiveTo())) {
      changed.add("effectiveTo");
    }
    if (!note.equals(before.note())) {
      changed.add("note");
    }

    if (changed.isEmpty()) {
      logOperation(
          tenant,
          actor,
          before.campaignId(),
          before.groupId(),
          id,
          "UPDATE_SETTING",
          snapshotOf(before),
          snapshotOf(before),
          "SKIPPED",
          "没有字段发生变化",
          request.reason());
      return before;
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update ad_targeting_setting set dimension_value = ?, status = ?, bid_factor = ?,"
            + " effective_from = ?::timestamptz, effective_to = ?::timestamptz, note = ?,"
            + " pending_platform_writeback = true, local_changed_at = ?::timestamptz, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        dimensionValue,
        status,
        bidFactor,
        effectiveFrom,
        effectiveTo,
        note,
        now,
        id);

    AdTargetingSettingRow after = toSettingRow(loadSetting(id));
    logOperation(
        tenant,
        actor,
        before.campaignId(),
        before.groupId(),
        id,
        "UPDATE_SETTING",
        snapshotOf(before),
        snapshotOf(after),
        "APPLIED",
        "已生效（本地），尚未回写平台",
        request.reason());
    return after;
  }

  // ==================================================================
  // 写入口 3：归档定位设置
  // ==================================================================

  /** 归档定位设置（软删除：status=ARCHIVED，历史指标与留痕都还在）。 */
  public AdTargetingSettingRow archive(String settingId, AdTargetingArchiveRequest request) {
    UUID id = parseId(settingId);
    Record row = loadSetting(id);
    if (row == null) {
      throw notFound("定位设置");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    AdTargetingSettingRow before = toSettingRow(row);

    if ("ARCHIVED".equals(before.status())) {
      logOperation(
          tenant,
          actor,
          before.campaignId(),
          before.groupId(),
          id,
          "ARCHIVE_SETTING",
          snapshotOf(before),
          snapshotOf(before),
          "SKIPPED",
          "已经是归档状态",
          request.reason());
      return before;
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update ad_targeting_setting set status = 'ARCHIVED', pending_platform_writeback = true,"
            + " local_changed_at = ?::timestamptz, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        now,
        id);

    AdTargetingSettingRow after = toSettingRow(loadSetting(id));
    logOperation(
        tenant,
        actor,
        before.campaignId(),
        before.groupId(),
        id,
        "ARCHIVE_SETTING",
        snapshotOf(before),
        snapshotOf(after),
        "APPLIED",
        "已归档（本地），尚未回写平台",
        request.reason());
    return after;
  }

  // ==================================================================
  // 写入口 4：🔴 解读平台原文定位串
  // ==================================================================

  /**
   * 🔴 解读平台原文定位串 → 归一定位维度（这是「解读归 14.1.3.2」的真正动作）。
   *
   * <p>🔴 三条边界：
   *
   * <ol>
   *   <li><b>原文从上游读</b>：给了 {@code groupId} 且没给原文 ⇒ 从
   *       {@code ad_campaign_group.targeting_settings} / {@code .match_type} 读（只读）。
   *   <li><b>归一不出来就显式报</b>：键认不出来 ⇒ UNMAPPED；时段取值解析不出窗口 ⇒ 窗口留白。
   *   <li><b>落库是幂等的</b>：同归属同维度同取值已存在 ⇒ 跳过（不造第二条）。
   * </ol>
   */
  public AdTargetingInterpretResult interpret(AdTargetingInterpretRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    var campaign = campaigns.campaign(request.campaignId().toString()).campaign();
    UUID groupId = request.groupId();

    String raw = blankToNull(request.targetingSettings());
    String matchType = blankToNull(request.matchType());
    if (raw == null && groupId != null) {
      Record group =
          sql.fetchOne(
              "select targeting_settings, match_type, campaign_id from ad_campaign_group where tenant_id = "
                  + TENANT
                  + " and id = ?",
              groupId);
      if (group == null) {
        throw notFound("广告组");
      }
      raw = blankToNull(group.get("targeting_settings", String.class));
      if (matchType == null) {
        matchType = blankToNull(group.get("match_type", String.class));
      }
      if (group.get("campaign_id", UUID.class) != null
          && !group.get("campaign_id", UUID.class).equals(campaign.id())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组不属于该活动");
      }
    }
    if (raw == null && matchType == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "没有可解读的原文：要么给 targetingSettings，要么给广告组 id");
    }

    List<ParsedTargeting> parsed = parseTargeting(raw, matchType);
    if (parsed.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "平台原文里没有可解读的定位内容（原文为空或只有分隔符）");
    }
    if (parsed.size() > MAX_DIMENSIONS_PER_INTERPRET) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一次解读最多 " + MAX_DIMENSIONS_PER_INTERPRET + " 条定位，原文解析出 " + parsed.size() + " 条");
    }

    boolean apply = Boolean.TRUE.equals(request.apply());
    List<AdTargetingSettingRow> applied = new ArrayList<>();
    int skipped = 0;
    int recognized = 0;
    for (ParsedTargeting p : parsed) {
      if (p.recognized()) {
        recognized++;
      }
      if (!apply) {
        continue;
      }
      String existsSql =
          "select count(*) from ad_targeting_setting where tenant_id = "
              + TENANT
              + " and campaign_id = ? and dimension = ? and dimension_value = ? and scope_kind = ?"
              + (groupId == null ? " and group_id is null" : " and group_id = ?");
      Object[] existsArgs =
          groupId == null
              ? new Object[] {campaign.id(), p.dimension(), p.dimensionValue(), "CAMPAIGN"}
              : new Object[] {campaign.id(), p.dimension(), p.dimensionValue(), "GROUP", groupId};
      if (count(existsSql, existsArgs) > 0) {
        skipped++;
        continue;
      }
      applied.add(insertParsed(campaign.id(), groupId, p, request.reason(), actor));
    }

    if (apply) {
      logOperation(
          tenant,
          actor,
          campaign.id(),
          groupId,
          null,
          "INTERPRET_TARGETING",
          "{\"raw\":\"" + escapeJson(raw) + "\"}",
          "{\"applied\":" + applied.size() + ",\"skipped\":" + skipped + "}",
          "APPLIED",
          "解读平台原文并落库 " + applied.size() + " 条（跳过已存在 " + skipped + " 条）",
          request.reason());
    }

    List<AdTargetingInterpretedRow> items =
        parsed.stream()
            .map(
                p ->
                    new AdTargetingInterpretedRow(
                        p.dimension(),
                        p.dimensionValue(),
                        p.platformRawKey(),
                        p.platformRawValue(),
                        p.recognized(),
                        p.startHour(),
                        p.endHour(),
                        p.hours(),
                        p.crossesMidnight(),
                        p.note()))
            .toList();

    return new AdTargetingInterpretResult(
        items,
        recognized,
        parsed.size() - recognized,
        apply,
        applied,
        skipped,
        "🔴 解读的是**平台原文**（上游 V281 的 targeting_settings / match_type）："
            + "原文**一字不改**地留在上游列里，本模块把拆解 + 归一的结果落在自己的表上。"
            + (apply
                ? "🔴 已落库 "
                    + applied.size()
                    + " 条（跳过已存在 "
                    + skipped
                    + " 条：同归属同维度同取值只有一条）。"
                : "🔴 **只预览未落库**（apply=false）—— 确认解读结果无误后再落库。")
            + "🔴 认不出来的键落 UNMAPPED（**不是兜底**），不会丢弃。");
  }

  /** 落一条解读结果（服务层私有：维度、取值、原文都由解读器决定，调用方不能自报）。 */
  private AdTargetingSettingRow insertParsed(
      UUID campaignId, UUID groupId, ParsedTargeting p, String reason, UUID actor) {
    UUID tenant = currentTenant();
    UUID id = Ids.next();
    OffsetDateTime now = OffsetDateTime.now();
    String scopeKind = groupId == null ? "CAMPAIGN" : "GROUP";
    sql.execute(
        "insert into ad_targeting_setting (tenant_id,id,campaign_id,group_id,scope_kind,dimension,"
            + " dimension_value,platform_raw_key,platform_raw_value,status,effective_from,effective_to,"
            + " daypart_start_hour,daypart_end_hour,daypart_hours,daypart_crosses_midnight,bid_factor,"
            + " pending_platform_writeback,local_changed_at,source,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,null,?,?,?,?,1,false,?::timestamptz,"
            + " 'PLATFORM','',now(),now())",
        tenant,
        id,
        campaignId,
        groupId,
        scopeKind,
        p.dimension(),
        p.dimensionValue(),
        p.platformRawKey(),
        p.platformRawValue(),
        now,
        p.startHour(),
        p.endHour(),
        p.hours(),
        p.crossesMidnight(),
        now);
    AdTargetingSettingRow row = toSettingRow(loadSetting(id));
    logOperation(
        tenant,
        actor,
        campaignId,
        groupId,
        id,
        "INTERPRET_TARGETING",
        "",
        snapshotOf(row),
        "APPLIED",
        "解读平台原文并落库",
        reason);
    return row;
  }

  // ==================================================================
  // 写入口 5：🔴 定位维度效果数据同步
  // ==================================================================

  /**
   * 🔴 定位维度效果数据同步（真实写入口）。
   *
   * <p>🔴 四条纪律：
   *
   * <ol>
   *   <li><b>维度与归属由设置决定</b>，调用方**不能自报**（否则一条指标能挂到别的维度上）。
   *   <li><b>同币种</b>：一个定位设置的指标只能是同一个币种（跨币种的 ACOS 是假的）。
   *   <li><b>批次幂等</b>：同一个 batchKey 重投**不重复落**（落两遍会让 ACOS 与 CPC 变成两倍）。
   *   <li><b>逐条拒绝</b>：不合法的那条被拒并写明原因，**其余照落**（不给整批陪葬）。
   * </ol>
   */
  public AdTargetingMetricBatchResult ingestMetrics(AdTargetingMetricBatchRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    String source = blankToNull(request.source());
    if (source == null) {
      source = "PLATFORM";
    } else {
      source = source.toUpperCase(Locale.ROOT);
      if (!Set.of("PLATFORM", "API", "IMPORT").contains(source)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源不合法：" + request.source());
      }
    }
    String batchKey = bounded(request.batchKey(), 160, "批次幂等键");
    String sourceRef = bounded(request.sourceRef(), 160, "来源引用");

    // ---- 一次性把这一批涉及的定位设置读出来（维度与归属**由设置决定**，调用方不能自报）----
    Map<UUID, AdTargetingSettingRow> settings = new LinkedHashMap<>();
    List<UUID> ids = new ArrayList<>();
    for (AdTargetingMetricEntry entry : request.entries()) {
      if (entry.settingId() != null && !ids.contains(entry.settingId())) {
        ids.add(entry.settingId());
      }
    }
    if (!ids.isEmpty()) {
      for (Record r :
          sql.fetch(
              "select t.id, t.campaign_id, c.campaign_name, c.shop_code, c.currency,"
                  + " t.group_id, g.group_name, t.scope_kind, t.dimension, t.dimension_value,"
                  + " t.platform_raw_key, t.platform_raw_value, t.status, t.bid_factor,"
                  + " t.daypart_start_hour, t.daypart_end_hour, t.daypart_hours,"
                  + " t.daypart_crosses_midnight, t.effective_from, t.effective_to,"
                  + " t.pending_platform_writeback, t.local_changed_at, t.source, t.note,"
                  + " t.created_at, t.updated_at"
                  + " from ad_targeting_setting t"
                  + " join ad_campaign c on c.tenant_id = t.tenant_id and c.id = t.campaign_id"
                  + " left join ad_campaign_group g on g.tenant_id = t.tenant_id and g.id = t.group_id"
                  + " where t.tenant_id = "
                  + TENANT
                  + " and t.id in ("
                  + placeholders(ids.size())
                  + ")",
              ids.toArray())) {
        settings.put(r.get("id", UUID.class), toSettingRow(r));
      }
    }

    // 留痕要挂在一个**真实活动**上（campaign_id 有外键）⇒ 取这一批里第一个能解析出来的设置
    UUID batchCampaignId = null;
    for (AdTargetingMetricEntry entry : request.entries()) {
      AdTargetingSettingRow found = settings.get(entry.settingId());
      if (found != null) {
        batchCampaignId = found.campaignId();
        break;
      }
    }

    if (!batchKey.isEmpty() && batchCampaignId != null) {
      long seen =
          count(
              "select count(*) from ad_targeting_metric where tenant_id = " + TENANT + " and batch_key = ?",
              batchKey);
      if (seen > 0) {
        logOperation(
            tenant,
            actor,
            batchCampaignId,
            null,
            null,
            "INGEST_METRIC",
            "{\"batchKey\":\"" + escapeJson(batchKey) + "\"}",
            "{\"skipped\":true}",
            "SKIPPED",
            "这一批已经落过（batch_key=" + batchKey + "），不重复累加",
            request.reason());
        return new AdTargetingMetricBatchResult(
            0,
            0,
            0,
            List.of("批次 " + batchKey + " 已经落过，本次不重复累加"),
            batchKey,
            OffsetDateTime.now(),
            "🔴 批次幂等：同一 batchKey 重投**不重复落**（落两遍会让 ACOS 与 CPC 变成两倍）。"
                + "要重导修正后的数据，请换一个新的 batchKey。");
      }
    }

    int accepted = 0;
    int replaced = 0;
    List<String> rejected = new ArrayList<>();
    OffsetDateTime syncedAt = OffsetDateTime.now();

    for (AdTargetingMetricEntry entry : request.entries()) {
      try {
        AdTargetingSettingRow setting = settings.get(entry.settingId());
        if (setting == null) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定位设置不存在");
        }
        if (entry.impressions() < 0 || entry.clicks() < 0 || entry.conversions() < 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计数不能为负");
        }
        if (entry.clicks() > entry.impressions()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "点击数不能大于展示数");
        }
        BigDecimal spend = entry.spend() == null ? zero() : entry.spend();
        BigDecimal sales = entry.sales() == null ? zero() : entry.sales();
        if (spend.signum() < 0 || sales.signum() < 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额不能为负");
        }
        String currency = entry.currency().trim().toUpperCase(Locale.ROOT);
        if (currency.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指标币种不能为空");
        }

        // 🔴 同币种纪律：一个定位设置的指标只能是同一个币种
        List<String> otherCurrencies =
            sql.fetch(
                    "select distinct currency from ad_targeting_metric where tenant_id = "
                        + TENANT
                        + " and setting_id = ? and currency <> ?",
                    entry.settingId(), currency)
                .stream()
                .map(r -> r.get(0, String.class))
                .toList();
        if (!otherCurrencies.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "同一定位设置的指标只能是同一个币种，已有 "
                  + String.join(", ", otherCurrencies)
                  + "（ACOS 是同币种内的比值，跨币种相加再相除是假的）");
        }

        boolean existed =
            count(
                    "select count(*) from ad_targeting_metric where tenant_id = "
                        + TENANT
                        + " and setting_id = ? and bucket_date = ?",
                    entry.settingId(),
                    java.sql.Date.valueOf(entry.bucketDate()))
                > 0;

        OffsetDateTime when = entry.syncedAt() == null ? syncedAt : entry.syncedAt();
        sql.execute(
            "insert into ad_targeting_metric (tenant_id,id,setting_id,campaign_id,group_id,dimension,"
                + " dimension_value,bucket_date,impressions,clicks,conversions,spend,sales,currency,"
                + " synced_at,source,source_ref,batch_key,note,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,now(),now())"
                + " on conflict (tenant_id,setting_id,bucket_date) do update set"
                + " impressions = excluded.impressions, clicks = excluded.clicks,"
                + " conversions = excluded.conversions, spend = excluded.spend, sales = excluded.sales,"
                + " currency = excluded.currency, synced_at = excluded.synced_at,"
                + " source = excluded.source, source_ref = excluded.source_ref,"
                + " batch_key = excluded.batch_key, note = excluded.note, updated_at = now()",
            tenant,
            Ids.next(),
            entry.settingId(),
            setting.campaignId(),
            setting.groupId(),
            setting.dimension(),
            setting.dimensionValue(),
            java.sql.Date.valueOf(entry.bucketDate()),
            entry.impressions(),
            entry.clicks(),
            entry.conversions(),
            spend,
            sales,
            currency,
            when,
            source,
            sourceRef,
            batchKey,
            bounded(entry.note(), 500, "备注"));
        if (existed) {
          replaced++;
        } else {
          accepted++;
        }
      } catch (ResponseStatusException e) {
        rejected.add("settingId=" + entry.settingId() + " date=" + entry.bucketDate() + "：" + e.getReason());
      }
    }

    if (batchCampaignId != null) {
      logOperation(
          tenant,
          actor,
          batchCampaignId,
          null,
          null,
          "INGEST_METRIC",
          "{\"batchKey\":\"" + escapeJson(batchKey) + "\"}",
          "{\"accepted\":" + accepted + ",\"replaced\":" + replaced + ",\"rejected\":" + rejected.size() + "}",
          rejected.isEmpty() ? "APPLIED" : "REJECTED",
          "同步定位维度效果数据：落库 "
              + accepted
              + " 条、覆盖 "
              + replaced
              + " 条、拒 "
              + rejected.size()
              + " 条",
          request.reason());
    }

    return new AdTargetingMetricBatchResult(
        accepted,
        replaced,
        rejected.size(),
        rejected,
        batchKey,
        syncedAt,
        "🔴 维度与归属**由定位设置决定**，调用方不能自报（否则一条指标能挂到别的维度上）。"
            + "🔴 同一个设置同一天重投是**覆盖**不是累加。"
            + "🔴 不合法的那条**逐条被拒**（写明原因），其余照落 —— 不给整批陪葬。"
            + (batchCampaignId == null
                ? "🔴 这一批里**没有一条能解析出定位设置** ⇒ 没有可挂的活动，未写留痕（逐条拒绝原因见上）。"
                : ""));
  }

  // ==================================================================
  // 写入口 6：🔴 收窄投放
  // ==================================================================

  /**
   * 🔴 收窄投放：把一条定位置为暂停（蓝图的加粗句：「定位效果差异大时应收窄投放」）。
   *
   * <p>🔴 收窄是**会打断投放**的动作 ⇒ 必须有证据门槛：
   * 该定位在区间内**花了钱且零转化**（花费 ≥ 门槛）。证据不足时**拒绝**，
   * 运营可以显式给 {@code override=true} + 理由强行收窄（留痕里会记下这是**人工决定**）。
   */
  public AdTargetingNarrowResult narrow(String settingId, AdTargetingNarrowRequest request) {
    UUID id = parseId(settingId);
    Record row = loadSetting(id);
    if (row == null) {
      throw notFound("定位设置");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    AdTargetingSettingRow before = toSettingRow(row);

    if (!"ACTIVE".equals(before.status())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "只有「在投」的定位才能收窄（当前状态 " + before.status() + "）");
    }

    // ---- 证据门槛 ----
    Record totals =
        sql.fetchOne(
            "select coalesce(sum(spend),0) spend, coalesce(sum(conversions),0) conversions,"
                + " coalesce(sum(sales),0) sales, coalesce(sum(clicks),0) clicks,"
                + " coalesce(sum(impressions),0) impressions"
                + " from ad_targeting_metric where tenant_id = "
                + TENANT
                + " and setting_id = ?",
            id);
    BigDecimal spend = totals == null ? zero() : totals.get("spend", BigDecimal.class);
    long conversions = totals == null ? 0L : totals.get("conversions", Long.class);
    BigDecimal sales = totals == null ? zero() : totals.get("sales", BigDecimal.class);
    long clicks = totals == null ? 0L : totals.get("clicks", Long.class);
    long impressions = totals == null ? 0L : totals.get("impressions", Long.class);

    List<String> evidence =
        new ArrayList<>(
            List.of(
                "累计花费 " + money(spend, campaignCurrency(before.campaignId())) + "，转化 " + conversions + " 次",
                "累计点击 " + clicks + " 次 / 展示 " + impressions + " 次，累计销售额 " + money(sales, campaignCurrency(before.campaignId()))));

    boolean hasEvidence =
        spend.compareTo(MIN_SPEND_FOR_SUGGESTION) >= 0 && conversions == 0 && sales.signum() == 0;
    boolean override = Boolean.TRUE.equals(request.override());
    if (!hasEvidence && !override) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "证据不足，不能收窄：这条定位累计花费 "
              + spend
              + "、转化 "
              + conversions
              + " 次、销售额 "
              + sales
              + "（收窄门槛：花费 ≥ "
              + MIN_SPEND_FOR_SUGGESTION
              + " 且 转化 = 0 且 销售额 = 0）。"
              + "确实要收窄请显式给 override=true 并写明理由 —— 留痕会记下这是人工决定。");
    }
    if (!hasEvidence) {
      evidence.add("🔴 **人工覆盖**：证据不足仍执行收窄（override=true）");
    }

    BigDecimal bidFactor = before.bidFactor();
    if (request.bidFactor() != null) {
      validateBidFactor(request.bidFactor());
      bidFactor = request.bidFactor();
      evidence.add("采纳出价系数 " + plain(bidFactor) + "（本模块只记录系数，**不改钱**）");
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update ad_targeting_setting set status = 'PAUSED', bid_factor = ?,"
            + " pending_platform_writeback = true, local_changed_at = ?::timestamptz, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        bidFactor,
        now,
        id);

    AdTargetingSettingRow after = toSettingRow(loadSetting(id));
    logOperation(
        tenant,
        actor,
        before.campaignId(),
        before.groupId(),
        id,
        "NARROW_TARGETING",
        snapshotOf(before),
        snapshotOf(after),
        "APPLIED",
        String.join("；", evidence),
        request.reason());

    return new AdTargetingNarrowResult(
        id,
        after.status(),
        true,
        after.bidFactor(),
        evidence,
        "🔴 收窄已**落本地**：这条定位状态改成「已收窄（停投这条定位）」，"
            + "并标着「本地已改、尚未回写平台」—— 平台回写队列未交付，"
            + "平台侧还在按原来的定位投放，界面会显式标出这一点。"
            + "🔴 本模块**不改钱**：出价系数只是记录，改竞价归 14.2.1.1。");
  }

  // ==================================================================
  // 解读器（平台原文 → 归一定位）
  // ==================================================================

  /**
   * 🔴 平台原文 → 归一定位维度。
   *
   * <p>🔴 解析规则（写清楚，避免「解读」变成玄学）：
   *
   * <ol>
   *   <li>按 {@code ;} / 换行 / {@code |} 分段；每段按**第一个** {@code =} 拆成键与取值。
   *   <li>键名小写去空白后查映射表 ⇒ 命中就是归一维度，**不命中就是 UNMAPPED**（不猜）。
   *   <li>取值归一 = 去空白 + 空白折叠 + 大写（平台原文的大小写不可信）。
   *   <li>DAYPART 的取值还要能解析成**小时窗口**（{@code 9-17} / {@code 09:00-17:00} /
   *       {@code 9am-5pm} / 单个小时 / 平台命名时段）；解析不出来 ⇒ 窗口留白（不编窗口）。
   *   <li>没有 {@code =} 的裸词 ⇒ 键名缺失，落 UNMAPPED（保留原文）。
   * </ol>
   */
  static List<ParsedTargeting> parseTargeting(String raw, String matchType) {
    List<ParsedTargeting> out = new ArrayList<>();
    String source = raw == null ? "" : raw;
    for (String segment : SEGMENT_SPLIT.split(source)) {
      String trimmed = segment.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      String rawKey = eq >= 0 ? trimmed.substring(0, eq).trim() : "";
      String rawValue = eq >= 0 ? trimmed.substring(eq + 1).trim() : trimmed;

      String key = normalizeKey(rawKey);
      String dimension = key.isEmpty() ? "UNMAPPED" : DIMENSION_KEYS.getOrDefault(key, "UNMAPPED");
      String value = normalizeValue(rawValue);
      boolean recognized = !"UNMAPPED".equals(dimension);

      Integer startHour = null;
      Integer endHour = null;
      BigDecimal hours = null;
      boolean crosses = false;
      String note;

      if (key.isEmpty()) {
        note = "🔴 平台原文里这一段**没有键名**，无法归一 ⇒ 落 UNMAPPED（保留原文，不丢弃）";
        recognized = false;
      } else if (!recognized) {
        note = "🔴 平台原文的键「" + rawKey + "」本模块**归一不出来** ⇒ 落 UNMAPPED（显式报出，不猜）";
      } else if ("DAYPART".equals(dimension)) {
        DaypartWindow window = parseDaypart(rawValue);
        if (window == null) {
          note =
              "🔴 时段取值「"
                  + rawValue
                  + "」**解析不出小时窗口** ⇒ 窗口列留白（不编一个窗口出来）";
          recognized = false;
        } else {
          startHour = window.startHour();
          endHour = window.endHour();
          hours = window.hours();
          crosses = window.crossesMidnight();
          note =
              "🔴 时段窗口："
                  + startHour
                  + ":00 ~ "
                  + endHour
                  + ":00"
                  + (crosses ? "（跨零点）" : "")
                  + "，覆盖 "
                  + plain(hours)
                  + " 小时";
        }
      } else {
        note = "🔴 归一自平台原文的键「" + rawKey + "」";
      }

      out.add(
          new ParsedTargeting(
              dimension,
              value.isEmpty() ? "(空值)" : value,
              rawKey,
              rawValue,
              recognized,
              startHour,
              endHour,
              hours,
              crosses,
              note));
    }

    String match = blankToNull(matchType);
    if (match != null) {
      out.add(
          new ParsedTargeting(
              "MATCH_TYPE",
              normalizeValue(match),
              "match_type",
              match,
              true,
              null,
              null,
              null,
              false,
              "🔴 匹配类型来自上游 V281 的 match_type（平台原文）"));
    }
    return out;
  }

  /** 键归一：小写 + 去空白 + 下划线/连字符归一。 */
  private static String normalizeKey(String key) {
    if (key == null) {
      return "";
    }
    return key.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }

  /** 取值归一：去空白 + 空白折叠 + 大写。 */
  static String normalizeValue(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  /**
   * 🔴 时段取值 → 小时窗口。
   *
   * <p>支持的形状：{@code 9-17} / {@code 09:00-17:00} / {@code 9am-5pm} / 单个小时
   * （{@code 9} / {@code 09:00} / {@code 9pm}）/ 平台命名时段
   * （ALLDAY / MORNING / AFTERNOON / EVENING / NIGHT）。
   * 解析不出来返回 {@code null}（**不编窗口**）。
   */
  static DaypartWindow parseDaypart(String value) {
    String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    if (v.isEmpty()) {
      return null;
    }
    int[] named = NAMED_DAYPARTS.get(v);
    if (named != null) {
      return new DaypartWindow(
          named[0], named[1], hoursOf(named[2]), named[1] <= named[0] && named[2] < 1440);
    }

    Matcher range = DAYPART_RANGE.matcher(v);
    if (range.matches()) {
      Integer startHour = hour(range.group(1), range.group(3));
      Integer endHour = hour(range.group(4), range.group(6));
      if (startHour == null || endHour == null) {
        return null;
      }
      int startMinute = startHour * 60 + minute(range.group(2));
      int endMinute = endHour * 60 + minute(range.group(5));
      return window(startMinute, endMinute);
    }

    Matcher single = DAYPART_SINGLE.matcher(v);
    if (single.matches()) {
      Integer startHour = hour(single.group(1), single.group(3));
      if (startHour == null) {
        return null;
      }
      int startMinute = startHour * 60 + minute(single.group(2));
      return window(startMinute, startMinute + 60);
    }
    return null;
  }

  private static int minute(String mm) {
    if (mm == null || mm.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(mm);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** 小时 + 12 小时制 → 24 小时制（越界返回 null）。 */
  private static Integer hour(String hh, String meridiem) {
    if (hh == null) {
      return null;
    }
    int h;
    try {
      h = Integer.parseInt(hh);
    } catch (NumberFormatException e) {
      return null;
    }
    if (h == 24) {
      h = 0;
    }
    if (h < 0 || h > 23) {
      return null;
    }
    if (meridiem != null) {
      if ("PM".equals(meridiem) && h < 12) {
        h += 12;
      } else if ("AM".equals(meridiem) && h == 12) {
        h = 0;
      }
    }
    return h;
  }

  /** 起止分钟 → 窗口（跨零点自动识别；相同端点按 1 小时算）。 */
  private static DaypartWindow window(int startMinute, int endMinute) {
    int duration = ((endMinute - startMinute) + 1440) % 1440;
    if (duration == 0) {
      duration = 1440;
    }
    boolean crosses = endMinute <= startMinute;
    return new DaypartWindow(
        (startMinute / 60) % 24, (endMinute / 60) % 24, hoursOf(duration), crosses);
  }

  /** 分钟数 → 小时数（数量：NUMERIC(19,6) 语义，BigDecimal，无浮点）。 */
  private static BigDecimal hoursOf(int minutes) {
    return BigDecimal.valueOf(minutes).divide(new BigDecimal("60"), 6, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 读模型的行映射与工具
  // ==================================================================

  private Record loadSetting(UUID id) {
    return sql.fetchOne(
        "select t.id, t.campaign_id, c.campaign_name, c.shop_code, c.currency,"
            + " t.group_id, g.group_name, t.scope_kind, t.dimension, t.dimension_value,"
            + " t.platform_raw_key, t.platform_raw_value, t.status, t.bid_factor,"
            + " t.daypart_start_hour, t.daypart_end_hour, t.daypart_hours,"
            + " t.daypart_crosses_midnight, t.effective_from, t.effective_to,"
            + " t.pending_platform_writeback, t.local_changed_at, t.source, t.note,"
            + " t.created_at, t.updated_at"
            + " from ad_targeting_setting t"
            + " join ad_campaign c on c.tenant_id = t.tenant_id and c.id = t.campaign_id"
            + " left join ad_campaign_group g on g.tenant_id = t.tenant_id and g.id = t.group_id"
            + " where t.tenant_id = "
            + TENANT
            + " and t.id = ?",
        id);
  }

  private static AdTargetingSettingRow toSettingRow(Record r) {
    return new AdTargetingSettingRow(
        r.get("id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("shop_code", String.class),
        r.get("currency", String.class),
        r.get("group_id", UUID.class),
        r.get("group_name", String.class),
        r.get("scope_kind", String.class),
        r.get("dimension", String.class),
        r.get("dimension_value", String.class),
        r.get("platform_raw_key", String.class),
        r.get("platform_raw_value", String.class),
        r.get("status", String.class),
        r.get("bid_factor", BigDecimal.class),
        r.get("daypart_start_hour", Integer.class),
        r.get("daypart_end_hour", Integer.class),
        r.get("daypart_hours", BigDecimal.class),
        r.get("daypart_crosses_midnight", Boolean.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("pending_platform_writeback", Boolean.class),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("source", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static AdTargetingOperationRow toOperationRow(Record r) {
    return new AdTargetingOperationRow(
        r.get("id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("group_id", UUID.class),
        r.get("setting_id", UUID.class),
        r.get("operation", String.class),
        r.get("before_value", String.class),
        r.get("after_value", String.class),
        r.get("result", String.class),
        r.get("message", String.class),
        r.get("requested_by", UUID.class),
        r.get("reason", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 留痕：定位的每一次写都要有据可查（被拒 / 跳过的同样留）。 */
  private void logOperation(
      UUID tenant,
      UUID actor,
      UUID campaignId,
      UUID groupId,
      UUID settingId,
      String operation,
      String beforeValue,
      String afterValue,
      String result,
      String message,
      String reason) {
    sql.execute(
        "insert into ad_targeting_operation_log (tenant_id,id,campaign_id,group_id,setting_id,operation,"
            + " before_value,after_value,result,message,requested_by,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
        tenant,
        Ids.next(),
        campaignId,
        groupId,
        settingId,
        operation,
        beforeValue,
        afterValue,
        result,
        message,
        actor == null ? tenant : actor,
        text(reason));
  }

  /** 设置快照（紧凑 JSON 原文）：变更前后都存一份，复盘时不用猜当时到底是什么值。 */
  private String snapshotOf(AdTargetingSettingRow row) {
    ObjectNode node = json.createObjectNode();
    node.put("campaignId", String.valueOf(row.campaignId()));
    node.put("groupId", row.groupId() == null ? null : row.groupId().toString());
    node.put("scopeKind", row.scopeKind());
    node.put("dimension", row.dimension());
    node.put("dimensionValue", row.dimensionValue());
    node.put("platformRawKey", row.platformRawKey());
    node.put("platformRawValue", row.platformRawValue());
    node.put("status", row.status());
    node.put("bidFactor", row.bidFactor() == null ? null : row.bidFactor().toPlainString());
    node.put("daypartHours", row.daypartHours() == null ? null : row.daypartHours().toPlainString());
    node.put("effectiveFrom", row.effectiveFrom() == null ? null : row.effectiveFrom().toString());
    node.put("effectiveTo", row.effectiveTo() == null ? null : row.effectiveTo().toString());
    return node.toString();
  }

  /** 广告组必须属于该活动（挂错活动的组等于定位挂在了错的投放上）。 */
  private void requireGroupInCampaign(UUID groupId, UUID campaignId) {
    UUID owner =
        one(
            UUID.class,
            "select campaign_id from ad_campaign_group where tenant_id = " + TENANT + " and id = ?",
            groupId);
    if (owner == null) {
      throw notFound("广告组");
    }
    if (!owner.equals(campaignId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组不属于该活动");
    }
  }

  /** 活动币种（只读，用于金额展示）。 */
  private String campaignCurrency(UUID campaignId) {
    String ccy = one(String.class, "select currency from ad_campaign where tenant_id = " + TENANT + " and id = ?", campaignId);
    return ccy == null ? "" : ccy;
  }

  private static void validateBidFactor(BigDecimal factor) {
    if (factor == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出价系数不能为空");
    }
    if (factor.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出价系数必须大于 0");
    }
    if (factor.compareTo(MAX_BID_FACTOR) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "出价系数超过上限 " + plain(MAX_BID_FACTOR));
    }
  }

  /** 区间：不给就是最近 N 个 UTC 日；给了就按给的来。 */
  private LocalDate[] resolveRange(String from, String to) {
    LocalDate end;
    LocalDate start;
    String toText = blankToNull(to);
    String fromText = blankToNull(from);
    if (toText == null) {
      end = LocalDate.now(java.time.ZoneOffset.UTC);
    } else {
      end = parseDate(toText, "结束日期");
    }
    if (fromText == null) {
      start = end.minusDays(DEFAULT_RANGE_DAYS - 1L);
    } else {
      start = parseDate(fromText, "开始日期");
    }
    if (start.isAfter(end)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始日期不能晚于结束日期");
    }
    if (ChronoUnit.DAYS.between(start, end) > 366) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间范围最长 366 天");
    }
    return new LocalDate[] {start, end};
  }

  private static LocalDate parseDate(String raw, String what) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "必须是 yyyy-MM-dd：" + raw);
    }
  }

  private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
    if (value == null) {
      return min;
    }
    if (value.compareTo(min) < 0) {
      return min;
    }
    if (value.compareTo(max) > 0) {
      return max;
    }
    return value;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  private static String money(BigDecimal value, String currency) {
    if (value == null) {
      return "—";
    }
    String text = value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    return currency == null || currency.isEmpty() ? text : text + " " + currency;
  }

  private static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
