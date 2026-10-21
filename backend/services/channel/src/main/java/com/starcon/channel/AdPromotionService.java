package com.starcon.channel;

import static com.starcon.channel.AdPromotionModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.4.1.1 促销列表。
 *
 * <p>蓝图：「管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
 * 同步状态与效果。输入：促销定义；平台促销接口；适用商品选择。输出：促销记录 + 平台同步状态。
 * 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
 * <b>促销的毛利必须实时测算</b>——折扣叠加后亏损是常态，需在下单前拦截或预警。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>促销类型 / 折扣幅度 / 起止时间 / 预算上限 / 参与店铺</b> → {@link #savePromotion}：
 *       一条促销记录就是「促销定义」。
 *   <li><b>适用商品选择</b> → {@link #savePromotion} 的商品清单 + 独立的关联表
 *       （多对多 ⇒ 同一商品可被多个促销覆盖，这正是「折扣叠加」的来源）。
 *   <li><b>平台同步状态</b> → {@code ad_promotion.platform_sync_status}：
 *       平台促销 API 未交付 ⇒ 只有「本地已建、平台侧还没有」这一种真实状态。
 *   <li><b>效果</b> → **不可用**：没有平台同步源，**不建空的促销效果表**。
 *   <li><b>拦截 / 预警</b> → {@link #check}：真规则、真判定、真落库。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的四个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>用假毛利顶替</b>。上游 5 定价在 <b>catalog</b> 服务（跨服务不可读），
 *       且 catalog <b>没有促销价测算端点</b> ⇒ 毛利维度显式 {@code available=false} +
 *       {@code ownerModule}，模型层**根本没有 margin 字段**。
 *       写一个 0 毛利会被界面渲染成「毛利是 0」——那是假事实，比明说「拿不到」严重得多。
 *   <li><b>把「本地已建」说成「平台已建」</b>。平台促销 API 未交付 ⇒
 *       {@code pendingPlatformWriteback} 恒为 true，界面必须写明「平台侧还没有这个促销」。
 *   <li><b>把整条线退化成「拿不到」</b>。折后价、折扣幅度、促销叠加、折后价下限
 *       **本地就能算** ⇒ 这几类规则本模块**真做**（真阈值、真判定、真留痕），
 *       只有「是否亏损」因为缺成本才 unavailable。
 *   <li><b>把「不可比」写成 0</b>。基准价没登记 ⇒ 折后价是 <b>null</b>（不可比），
 *       与 0（可比但真的是零）含义相反。
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读**同库的 {@code listing} / {@code shop} / {@code platform}，
 * 写入一律落在自己的四张表上（{@code ad_promotion} / {@code ad_promotion_product} /
 * {@code ad_promotion_guardrail} / {@code ad_promotion_check}）——
 * 促销不是广告活动，**不塞进 {@code ad_campaign}**，也不复用广告表当促销表。
 */
@Service
public class AdPromotionService {

  // ==================================================================
  // 常量：取值域与口径
  // ==================================================================

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 促销类型全集。 */
  static final List<String> PROMOTION_TYPES =
      List.of("PERCENT_OFF", "FIXED_AMOUNT_OFF", "BOGO", "BUNDLE", "FREE_SHIPPING");

  /** 本地生命周期状态全集。 */
  static final List<String> STATUSES =
      List.of("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED", "CANCELLED");

  /** 平台同步状态全集（只有 LOCAL_ONLY 是本批真实可达的）。 */
  static final List<String> SYNC_STATUSES =
      List.of("LOCAL_ONLY", "PENDING_WRITEBACK", "SYNCED", "FAILED");

  /** 拦截 / 预警规则全集。 */
  static final List<String> RULE_CODES =
      List.of("DISCOUNT_DEPTH", "PRICE_FLOOR", "PROMOTION_STACKING", "BUDGET_CAP");

  /** 结论严重级别全集。 */
  static final List<String> SEVERITIES = List.of("BLOCKED", "WARNING");

  /**
   * 本地状态机：允许的状态迁移。
   *
   * <p>🔴 终态（ENDED / CANCELLED）**没有出边** —— 促销结束或取消之后不能悄悄复活，
   * 要再来一次就是**新建一条**（历史留痕比状态回退更有用）。
   */
  static final Map<String, List<String>> STATUS_TRANSITIONS =
      Map.of(
          "DRAFT", List.of("SCHEDULED", "CANCELLED"),
          "SCHEDULED", List.of("ACTIVE", "PAUSED", "CANCELLED", "DRAFT"),
          "ACTIVE", List.of("PAUSED", "ENDED", "CANCELLED"),
          "PAUSED", List.of("ACTIVE", "ENDED", "CANCELLED"),
          "ENDED", List.of(),
          "CANCELLED", List.of());

  /** 算价格的促销类型（其余类型没有可直接折算的价格系数 ⇒ 折后价不可比）。 */
  private static final List<String> PRICE_BEARING_TYPES = List.of("PERCENT_OFF", "FIXED_AMOUNT_OFF");

  // ==================================================================
  // 口径说明（随响应返回，界面上的表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String NET_PRICE_NOTE =
      "折后价 = 基准价 × (1 − 折扣率)（百分比促销）或 基准价 − 固定减免额（固定额促销）；"
          + "叠加折后价 = 基准价 × Π(各覆盖促销的折后系数)。"
          + "**基准价是促销定义里登记的输入**（listing 没有价格列 —— 售价归 05 定价，"
          + "本模块不跨服务去 catalog 读），没登记 ⇒ 折后价**不可比**（null），**不是 0**。"
          + "买 N 送 M / 组合优惠 / 免运费三类没有可直接折算的价格系数 ⇒ 折后价同样不可比（null），"
          + "但**叠加条数照样算**。";

  static final String MARGIN_NOTE =
      "🔴 **毛利 / 利润维度当前不可用**：上游 5 定价（促销价计算）在 **catalog** 服务，"
          + "channel **不得跨服务 SQL**（不 join、不建跨库 FK、不建共享视图），"
          + "且 catalog **没有促销价测算端点**（已 grep 确认无 promo / discount 端点）"
          + "⇒ 拿不到成本价，「是否亏损」判不了。"
          + "本模块**不写一个 0 毛利糊弄过去** —— 界面上的「毛利 0」会被当成「这单不赚钱」，"
          + "而真相是「拿不到成本」，两者在运营决策上完全相反。"
          + "本地**能**算的是折后价与折扣幅度，本模块据此做真拦截 / 真预警；"
          + "5 定价交付促销价测算端点后放开该维度即可接上，判定逻辑一行都不用改。";

  static final String EFFECT_NOTE =
      "🔴 **平台效果维度当前不可用**：曝光 / 点击 / 销售这些是**平台侧回报**的数据，"
          + "本仓没有平台促销 API 同步器 ⇒ 没有同步源。"
          + "本模块**不建一张空的促销效果表**（空表会被渲染成「效果是 0」，那是假事实）。"
          + "促销效果分析归 **14.4.2.1 促销效果分析**。";

  static final String FINANCE_NOTE =
      "🔴 **促销费用维度当前不可用**：下游 12 财务（卷 12 主责服务 finance）**不在本仓**，"
          + "channel 不去 finance 建表，也不跨服务读。促销补贴 / 让利的费用归集归 12.x。";

  static final String SYNC_NOTE =
      "🔴 **平台促销 API 未交付** ⇒ 平台同步状态只有「本地已建、平台侧还没有」（LOCAL_ONLY）。"
          + "`pendingPlatformWriteback=true` 表示**平台侧还没有这个促销**，"
          + "界面上必须写明这一点 —— 否则「已创建促销」会被当成平台侧已创建，那是假事实。"
          + "`SYNCED` 只能由平台回写适配器产生（该适配器未交付），本批代码里没有任何路径写入它。";

  static final String STACKING_NOTE =
      "促销与商品是**多对多** ⇒ 同一商品被多个促销覆盖就是**折扣叠加**。判定口径："
          + "只算**时间窗与本促销重叠**且状态为 SCHEDULED / ACTIVE 的其它促销"
          + "（不含 DRAFT / ENDED / CANCELLED）；时间窗为空的一端按「无界」处理。"
          + "叠加折后价 = 基准价 × Π(各促销折后系数)。";

  static final String UPSTREAM_NOTE =
      "上游 = 4.1.1.1 listing（**同库同服务**，促销的适用商品挂它，复合 FK 带 tenant_id "
          + "⇒ 跨租户挂载物理上不可能）+ 2.1.1.1 platform（全局码表）+ 2.3.1.1 shop（参与店铺）；"
          + "5 定价在 catalog，**跨服务不可读** ⇒ 毛利维度 available=false。";

  static final String BOUNDARY_NOTE =
      "下游 = 平台促销 API（未交付 ⇒ 同步状态只到「本地已建、平台侧还没有」）"
          + "与 12 财务（不在本仓 ⇒ 费用维度不可用）。"
          + "本模块**不实现**效果分析（14.4.2.1）与优惠券 / 秒杀（14.4.1.2）。";

  private final DSLContext sql;

  public AdPromotionService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具（照抄 AdBudgetAllocationService 的同名写法）
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

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
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

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdPromotionCapabilities capabilities() {
    return new AdPromotionCapabilities(
        List.of(
            new AdPromotionCapabilityDimension(
                "promotion_definition",
                "促销定义：类型 / 折扣幅度 / 起止时间 / 预算上限 / 参与店铺",
                true,
                "ad_promotion（V284）",
                "",
                "没有促销定义就没有促销记录；折扣口径由 DB CHECK 钉死（百分比与固定额互斥）"),
            new AdPromotionCapabilityDimension(
                "applicable_products",
                "适用商品选择（多对多：同一商品可被多个促销覆盖）",
                true,
                "ad_promotion_product（V284）",
                "",
                "没有适用商品就不知道这条促销影响哪些 listing，也判不了折扣叠加"),
            new AdPromotionCapabilityDimension(
                "net_price",
                "折后价 = 基准价 × (1 − 折扣率)；叠加折后价 = 基准价 × Π(折后系数)",
                true,
                "本地计算（基准价取自促销定义登记值）",
                "",
                "折后价是「折扣叠完还剩多少」的唯一可得口径；没有它，折扣幅度只能靠人脑算"),
            new AdPromotionCapabilityDimension(
                "guardrail_interception",
                "拦截 / 预警：折扣幅度上限、折后价下限、促销叠加、预算上限",
                true,
                "ad_promotion_guardrail + ad_promotion_check（V284）",
                "",
                "没有这四条真规则，蓝图加粗句要求的「下单前拦截或预警」就落不了地"),
            new AdPromotionCapabilityDimension(
                "platform_sync_status",
                "平台同步状态：本地已建、平台侧还没有",
                true,
                "ad_promotion.platform_sync_status（V284）",
                "",
                "没有同步状态，界面上的「已创建促销」会被当成平台侧已创建"),
            new AdPromotionCapabilityDimension(
                "gross_margin",
                "🔴 **毛利 / 利润实时测算**（蓝图加粗句）",
                false,
                "—",
                "5 定价（5.x 促销价计算，在 catalog 服务）",
                "上游 5 定价在 catalog，跨服务不可读，且 catalog 没有促销价测算端点 "
                    + "⇒ 拿不到成本，**是否亏损判不了**。"
                    + "本模块**不写假毛利 0**（那会被渲染成「毛利是 0」的假事实）；"
                    + "可算的折后价与折扣幅度已做成真拦截 / 真预警"),
            new AdPromotionCapabilityDimension(
                "promotion_effect",
                "🔴 **平台效果**（曝光 / 点击 / 销售）",
                false,
                "—",
                "14.4.2.1 促销效果分析（数据源是平台促销 API 同步）",
                "没有平台同步源 ⇒ 不建空的促销效果表（空表会被渲染成「效果是 0」）"),
            new AdPromotionCapabilityDimension(
                "platform_writeback",
                "把促销回写到平台",
                false,
                "—",
                "平台适配层（channel/vendor 的平台促销 API）",
                "平台促销 API 未交付 ⇒ 促销只落本地并标记「平台侧还没有」；"
                    + "界面上不把「本地已建」说成「平台侧已建」"),
            new AdPromotionCapabilityDimension(
                "promotion_expense",
                "🔴 **促销费用**（补贴 / 让利的费用归集）",
                false,
                "—",
                "12 财务（卷 12 主责服务 finance，不在本仓）",
                "channel 不去 finance 建表，也不跨服务读；费用归集归 12.x")),
        PROMOTION_TYPES,
        STATUSES,
        SYNC_STATUSES,
        RULE_CODES,
        SEVERITIES,
        NET_PRICE_NOTE,
        MARGIN_NOTE,
        EFFECT_NOTE,
        FINANCE_NOTE,
        SYNC_NOTE,
        STACKING_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdPromotionOptions options() {
    List<AdPromotionPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new AdPromotionPlatformOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)))
            .toList();

    List<String> platformCodes =
        platforms.stream().map(AdPromotionPlatformOption::code).toList();

    List<String> shopCodes =
        sql.fetch(
                "select distinct code from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> currencies =
        sql.fetch(
                "select distinct currency from ad_promotion where tenant_id = "
                    + TENANT
                    + " and currency <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new AdPromotionOptions(
        promotionTypeOptions(),
        statusOptions(),
        syncStatusOptions(),
        ruleCodeOptions(),
        severityOptions(),
        platforms,
        platformCodes,
        shopCodes,
        currencies,
        NET_PRICE_NOTE,
        MARGIN_NOTE);
  }

  private static List<AdPromotionOption> promotionTypeOptions() {
    return List.of(
        new AdPromotionOption("PERCENT_OFF", "百分比折扣（按折扣率）"),
        new AdPromotionOption("FIXED_AMOUNT_OFF", "固定额减免"),
        new AdPromotionOption("BOGO", "买 N 送 M"),
        new AdPromotionOption("BUNDLE", "组合优惠"),
        new AdPromotionOption("FREE_SHIPPING", "免运费"));
  }

  private static List<AdPromotionOption> statusOptions() {
    return List.of(
        new AdPromotionOption("DRAFT", "草稿"),
        new AdPromotionOption("SCHEDULED", "已排期"),
        new AdPromotionOption("ACTIVE", "进行中"),
        new AdPromotionOption("PAUSED", "已暂停"),
        new AdPromotionOption("ENDED", "已结束"),
        new AdPromotionOption("CANCELLED", "已取消"));
  }

  private static List<AdPromotionOption> syncStatusOptions() {
    return List.of(
        new AdPromotionOption("LOCAL_ONLY", "本地已建，平台侧还没有"),
        new AdPromotionOption("PENDING_WRITEBACK", "回写已发起，待平台确认"),
        new AdPromotionOption("SYNCED", "平台侧已一致"),
        new AdPromotionOption("FAILED", "回写失败"));
  }

  private static List<AdPromotionOption> ruleCodeOptions() {
    return List.of(
        new AdPromotionOption("DISCOUNT_DEPTH", "折扣幅度超出阈值"),
        new AdPromotionOption("PRICE_FLOOR", "折后价低于下限"),
        new AdPromotionOption("PROMOTION_STACKING", "多个促销叠加覆盖同一商品"),
        new AdPromotionOption("BUDGET_CAP", "最坏情况让利额超过预算上限"));
  }

  private static List<AdPromotionOption> severityOptions() {
    return List.of(
        new AdPromotionOption("BLOCKED", "拦截（不允许激活）"),
        new AdPromotionOption("WARNING", "预警（允许但要人知道）"));
  }

  // ==================================================================
  // 促销列表
  // ==================================================================

  public AdPromotionPage promotions(
      String status,
      String promotionType,
      UUID platformId,
      String shopCode,
      String syncStatus,
      String keyword,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where p.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String state = blankToNull(status);
    if (state != null) {
      where.append(" and p.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String type = blankToNull(promotionType);
    if (type != null) {
      where.append(" and p.promotion_type = ?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }
    if (platformId != null) {
      where.append(" and p.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and p.shop_code = ?");
      binds.add(shop);
    }
    String sync = blankToNull(syncStatus);
    if (sync != null) {
      where.append(" and p.platform_sync_status = ?");
      binds.add(sync.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (p.promotion_code ilike ? or p.promotion_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_promotion p" + where, binds.toArray());

    List<AdPromotionRow> items =
        sql.fetch(
                "select p.id, p.promotion_code, p.promotion_name, p.promotion_type, p.platform_id,"
                    + " (select pf.code from platform pf where pf.id = p.platform_id) platform_code,"
                    + " p.shop_code, p.discount_rate, p.discount_amount, p.budget_cap, p.currency,"
                    + " p.start_at, p.end_at, p.status, p.platform_sync_status,"
                    + " p.pending_platform_writeback, p.local_changed_at, p.last_checked_at,"
                    + " p.platform_promotion_id, p.note, p.version, p.created_at, p.updated_at,"
                    + " ((p.start_at is null or p.start_at <= now())"
                    + "  and (p.end_at is null or now() < p.end_at)) within_window,"
                    + " (select count(*) from ad_promotion_product pp"
                    + "   where pp.tenant_id = p.tenant_id and pp.promotion_id = p.id) product_count,"
                    + " coalesce(ck.blocked_count,0) blocked_count,"
                    + " coalesce(ck.warning_count,0) warning_count"
                    + " from ad_promotion p"
                    + " left join lateral ("
                    + "   select count(*) filter (where c.severity = 'BLOCKED')::int blocked_count,"
                    + "          count(*) filter (where c.severity = 'WARNING')::int warning_count"
                    + "   from ad_promotion_check c"
                    + "   where c.tenant_id = p.tenant_id and c.promotion_id = p.id"
                    + "     and c.check_run_id = p.last_check_run_id"
                    + " ) ck on true"
                    + where
                    + " order by p.created_at desc, p.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    promotionRow(
                        r,
                        r.get("blocked_count", Integer.class) == null
                            ? 0
                            : r.get("blocked_count", Integer.class),
                        r.get("warning_count", Integer.class) == null
                            ? 0
                            : r.get("warning_count", Integer.class)))
            .toList();

    return new AdPromotionPage(items, total, safePage, safeSize);
  }

  private AdPromotionRow promotionRow(Record r, int blockedCount, int warningCount) {
    return new AdPromotionRow(
        r.get("id", UUID.class),
        r.get("promotion_code", String.class),
        r.get("promotion_name", String.class),
        r.get("promotion_type", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("discount_rate", BigDecimal.class),
        r.get("discount_amount", BigDecimal.class),
        r.get("budget_cap", BigDecimal.class),
        r.get("currency", String.class),
        r.get("start_at", OffsetDateTime.class),
        r.get("end_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("within_window", Boolean.class)),
        r.get("status", String.class),
        r.get("platform_sync_status", String.class),
        Boolean.TRUE.equals(r.get("pending_platform_writeback", Boolean.class)),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("last_checked_at", OffsetDateTime.class),
        r.get("platform_promotion_id", String.class),
        r.get("product_count", Integer.class) == null ? 0 : r.get("product_count", Integer.class),
        blockedCount,
        warningCount,
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 促销详情
  // ==================================================================

  public AdPromotionDetail promotion(String id) {
    UUID promotionId = parseId(id);
    Record row =
        sql.fetchOne(
            promotionSelect() + " where p.tenant_id = " + TENANT + " and p.id = ?", promotionId);
    if (row == null) {
      throw notFound("促销");
    }
    List<AdPromotionProductRow> products = productsOf(row, promotionId);
    List<AdPromotionCheckRow> checks = latestChecks(promotionId);
    AdPromotionGuardrailRow guardrail = activeGuardrail();

    int blocked = 0;
    int warning = 0;
    for (AdPromotionCheckRow c : checks) {
      if ("BLOCKED".equals(c.severity())) {
        blocked++;
      } else {
        warning++;
      }
    }

    return new AdPromotionDetail(
        promotionRow(row, blocked, warning),
        products,
        checks,
        guardrail,
        blocked,
        warning,
        MARGIN_NOTE,
        SYNC_NOTE,
        "适用商品与折后价随促销定义走；拦截 / 预警是**最近一次检查**的结论，"
            + "改完商品或折扣后要重新跑一次检查（改完不重跑时旧结论仍然生效 —— 宁可拦着，不放行）。");
  }

  /** 促销主记录的查询片段（列表与详情共用，避免两处口径漂移）。 */
  private static String promotionSelect() {
    return "select p.id, p.promotion_code, p.promotion_name, p.promotion_type, p.platform_id,"
        + " (select pf.code from platform pf where pf.id = p.platform_id) platform_code,"
        + " p.shop_code, p.discount_rate, p.discount_amount, p.budget_cap, p.currency,"
        + " p.start_at, p.end_at, p.status, p.platform_sync_status,"
        + " p.pending_platform_writeback, p.local_changed_at, p.last_checked_at,"
        + " p.platform_promotion_id, p.note, p.version, p.created_at, p.updated_at,"
        + " ((p.start_at is null or p.start_at <= now())"
        + "  and (p.end_at is null or now() < p.end_at)) within_window,"
        + " (select count(*) from ad_promotion_product pp"
        + "   where pp.tenant_id = p.tenant_id and pp.promotion_id = p.id) product_count"
        + " from ad_promotion p";
  }

  private Record promoRecord(UUID promotionId) {
    Record row =
        sql.fetchOne(
            "select id, promotion_code, promotion_name, promotion_type, platform_id, shop_code,"
                + " discount_rate, discount_amount, budget_cap, currency, start_at, end_at, status,"
                + " platform_sync_status, pending_platform_writeback, local_changed_at,"
                + " last_checked_at, platform_promotion_id, note, version, created_at, updated_at"
                + " from ad_promotion where tenant_id = "
                + TENANT
                + " and id = ?",
            promotionId);
    if (row == null) {
      throw notFound("促销");
    }
    return row;
  }

  // ==================================================================
  // 适用商品 + 折后价 + 叠加
  // ==================================================================

  /** 一条覆盖同一商品的其它促销（叠加判定用）。 */
  record StackedPromotion(
      UUID listingId,
      UUID promotionId,
      String promotionCode,
      String promotionName,
      String promotionType,
      BigDecimal discountRate,
      BigDecimal discountAmount) {}

  private List<AdPromotionProductRow> productsOf(Record promo, UUID promotionId) {
    String type = promo.get("promotion_type", String.class);
    BigDecimal rate = promo.get("discount_rate", BigDecimal.class);
    BigDecimal amount = promo.get("discount_amount", BigDecimal.class);
    OffsetDateTime startAt = promo.get("start_at", OffsetDateTime.class);
    OffsetDateTime endAt = promo.get("end_at", OffsetDateTime.class);

    List<Record> rows =
        sql.fetch(
            "select pp.id, pp.listing_id, l.external_item_id, l.title, l.status listing_status,"
                + " pp.base_price, pp.currency, pp.note, pp.created_at"
                + " from ad_promotion_product pp"
                + " join listing l on l.tenant_id = pp.tenant_id and l.id = pp.listing_id"
                + " where pp.tenant_id = "
                + TENANT
                + " and pp.promotion_id = ?"
                + " order by pp.created_at, pp.id",
            promotionId);

    // 🔴 叠加集合一次查完（N+1 会让商品多的促销详情慢到不可用）
    Map<UUID, List<StackedPromotion>> stacked = stackedBy(promotionId, startAt, endAt);

    List<AdPromotionProductRow> out = new ArrayList<>();
    for (Record r : rows) {
      UUID listingId = r.get("listing_id", UUID.class);
      BigDecimal basePrice = r.get("base_price", BigDecimal.class);
      BigDecimal netPrice = netPrice(type, rate, amount, basePrice);
      BigDecimal depth = discountDepth(type, rate, amount, basePrice);

      List<StackedPromotion> others = stacked.getOrDefault(listingId, List.of());
      BigDecimal stackedNet = null;
      if (netPrice != null) {
        BigDecimal factor = factor(type, rate, amount, basePrice);
        for (StackedPromotion other : others) {
          factor =
              factor.multiply(
                  factor(
                      other.promotionType(),
                      other.discountRate(),
                      other.discountAmount(),
                      basePrice));
        }
        stackedNet = basePrice.multiply(factor).setScale(4, RoundingMode.HALF_UP);
      }

      out.add(
          new AdPromotionProductRow(
              r.get("id", UUID.class),
              listingId,
              r.get("external_item_id", String.class),
              r.get("title", String.class),
              r.get("listing_status", String.class),
              basePrice,
              r.get("currency", String.class),
              netPrice,
              depth,
              others.size(),
              stackedNet,
              r.get("note", String.class),
              r.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  /**
   * 查出与本促销**时间窗重叠**且状态为 SCHEDULED / ACTIVE 的其它覆盖促销。
   *
   * <p>🔴 时间窗为空的一端按「无界」处理（'-infinity' / 'infinity'）——
   * 一个没写结束时间的促销不该因为「没写结束时间」就不参与叠加判定。
   */
  private Map<UUID, List<StackedPromotion>> stackedBy(
      UUID promotionId, OffsetDateTime startAt, OffsetDateTime endAt) {
    Map<UUID, List<StackedPromotion>> map = new LinkedHashMap<>();
    List<Record> rows =
        sql.fetch(
            "select op.listing_id, o.id, o.promotion_code, o.promotion_name, o.promotion_type,"
                + " o.discount_rate, o.discount_amount"
                + " from ad_promotion o"
                + " join ad_promotion_product op"
                + "   on op.tenant_id = o.tenant_id and op.promotion_id = o.id"
                + " join ad_promotion_product mine"
                + "   on mine.tenant_id = op.tenant_id and mine.listing_id = op.listing_id"
                + "  and mine.promotion_id = ?"
                + " where o.tenant_id = "
                + TENANT
                + " and o.id <> ?"
                + " and o.status in ('SCHEDULED','ACTIVE')"
                + " and coalesce(o.start_at,'-infinity'::timestamptz)"
                + "     < coalesce(?::timestamptz,'infinity'::timestamptz)"
                + " and coalesce(?::timestamptz,'-infinity'::timestamptz)"
                + "     < coalesce(o.end_at,'infinity'::timestamptz)"
                + " order by op.listing_id, o.promotion_code",
            promotionId,
            promotionId,
            endAt,
            startAt);
    for (Record r : rows) {
      UUID listingId = r.get("listing_id", UUID.class);
      map.computeIfAbsent(listingId, k -> new ArrayList<>())
          .add(
              new StackedPromotion(
                  listingId,
                  r.get("id", UUID.class),
                  r.get("promotion_code", String.class),
                  r.get("promotion_name", String.class),
                  r.get("promotion_type", String.class),
                  r.get("discount_rate", BigDecimal.class),
                  r.get("discount_amount", BigDecimal.class)));
    }
    return map;
  }

  /** 折后价：基准价 × (1 − 折扣率) 或 基准价 − 固定减免额。不可比时返回 null（**不是 0**）。 */
  static BigDecimal netPrice(
      String type, BigDecimal rate, BigDecimal amount, BigDecimal basePrice) {
    if (basePrice == null || !PRICE_BEARING_TYPES.contains(type)) {
      return null;
    }
    if ("PERCENT_OFF".equals(type) && rate != null) {
      return basePrice.multiply(BigDecimal.ONE.subtract(rate)).setScale(4, RoundingMode.HALF_UP);
    }
    if ("FIXED_AMOUNT_OFF".equals(type) && amount != null) {
      return basePrice.subtract(amount).setScale(4, RoundingMode.HALF_UP);
    }
    return null;
  }

  /** 折扣幅度：百分比促销 = 折扣率；固定额促销 = 减免额 / 基准价。不可比时返回 null。 */
  static BigDecimal discountDepth(
      String type, BigDecimal rate, BigDecimal amount, BigDecimal basePrice) {
    if ("PERCENT_OFF".equals(type) && rate != null) {
      return rate;
    }
    if ("FIXED_AMOUNT_OFF".equals(type)
        && amount != null
        && basePrice != null
        && basePrice.signum() > 0) {
      return amount.divide(basePrice, 6, RoundingMode.HALF_UP);
    }
    return null;
  }

  /** 折后系数（用于叠加连乘）。非价格型促销 = 1（不影响价格，但**照样参与叠加计数**）。 */
  static BigDecimal factor(String type, BigDecimal rate, BigDecimal amount, BigDecimal basePrice) {
    if ("PERCENT_OFF".equals(type) && rate != null) {
      return BigDecimal.ONE.subtract(rate);
    }
    if ("FIXED_AMOUNT_OFF".equals(type)
        && amount != null
        && basePrice != null
        && basePrice.signum() > 0) {
      return BigDecimal.ONE.subtract(amount.divide(basePrice, 10, RoundingMode.HALF_UP));
    }
    return BigDecimal.ONE;
  }

  // ==================================================================
  // 最近一次检查的结论
  // ==================================================================

  private List<AdPromotionCheckRow> latestChecks(UUID promotionId) {
    UUID runId =
        one(
            UUID.class,
            "select last_check_run_id from ad_promotion where tenant_id = "
                + TENANT
                + " and id = ?",
            promotionId);
    if (runId == null) {
      return List.of();
    }
    return sql.fetch(
            "select id, listing_id, rule_code, severity, message, observed_value, threshold_value,"
                + " checked_at from ad_promotion_check where tenant_id = "
                + TENANT
                + " and promotion_id = ? and check_run_id = ?"
                + " order by severity desc, rule_code, listing_id nulls first, id",
            promotionId,
            runId)
        .stream()
        .map(
            r ->
                new AdPromotionCheckRow(
                    r.get("id", UUID.class),
                    r.get("listing_id", UUID.class),
                    r.get("rule_code", String.class),
                    r.get("severity", String.class),
                    r.get("message", String.class),
                    r.get("observed_value", BigDecimal.class),
                    r.get("threshold_value", BigDecimal.class),
                    r.get("checked_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 护栏规则
  // ==================================================================

  private AdPromotionGuardrailRow activeGuardrail() {
    Record row =
        sql.fetchOne(
            "select id, guardrail_code, guardrail_name, max_discount_rate, min_net_price,"
                + " block_on_stacking, status, note, version, created_at, updated_at"
                + " from ad_promotion_guardrail where tenant_id = "
                + TENANT
                + " and status = 'ACTIVE'"
                + " order by updated_at desc, id desc limit 1");
    return row == null ? null : guardrailRow(row);
  }

  private AdPromotionGuardrailRow guardrailRow(Record r) {
    return new AdPromotionGuardrailRow(
        r.get("id", UUID.class),
        r.get("guardrail_code", String.class),
        r.get("guardrail_name", String.class),
        r.get("max_discount_rate", BigDecimal.class),
        r.get("min_net_price", BigDecimal.class),
        Boolean.TRUE.equals(r.get("block_on_stacking", Boolean.class)),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdPromotionGuardrailPage guardrails(String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_promotion_guardrail" + where, binds.toArray());
    List<AdPromotionGuardrailRow> items =
        sql.fetch(
                "select id, guardrail_code, guardrail_name, max_discount_rate, min_net_price,"
                    + " block_on_stacking, status, note, version, created_at, updated_at"
                    + " from ad_promotion_guardrail"
                    + where
                    + " order by updated_at desc, id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::guardrailRow)
            .toList();
    return new AdPromotionGuardrailPage(items, total, safePage, safeSize);
  }

  /**
   * 登记 / 修改护栏规则（拦截与预警的阈值）。
   *
   * <p>🔴 一个租户**同一时刻只能有一套生效的护栏**（DB 部分唯一索引钉死）——
   * 两套阈值同时生效时「折扣幅度上限到底是多少」就没有答案了。
   * 启用新的一套 ⇒ 旧的自动停用（同一事务内完成）。
   */
  public AdPromotionGuardrailRow saveGuardrail(SaveGuardrailRequest request) {
    String code = text(request.guardrailCode());
    String name = text(request.guardrailName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "护栏规则编码与名称都不能为空");
    }
    String status = blankToNull(request.status());
    if (status == null) {
      status = "ACTIVE";
    }
    status = status.toUpperCase(Locale.ROOT);
    if (!status.equals("ACTIVE") && !status.equals("PAUSED")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "护栏规则状态只能是 ACTIVE / PAUSED");
    }
    if (request.maxDiscountRate() != null
        && (request.maxDiscountRate().signum() <= 0
            || request.maxDiscountRate().compareTo(BigDecimal.ONE) > 0)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "折扣幅度上限必须大于 0 且不超过 1（0.8 = 让利不超过八成）");
    }
    if (request.minNetPrice() != null && request.minNetPrice().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折后价下限不能为负");
    }
    if (request.maxDiscountRate() == null && request.minNetPrice() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "折扣幅度上限与折后价下限至少要有一个 —— 两个都不设的护栏一条规则都判不了");
    }

    if ("ACTIVE".equals(status)) {
      sql.execute(
          "update ad_promotion_guardrail set status = 'PAUSED', updated_at = now()"
              + " where tenant_id = ?::uuid and status = 'ACTIVE' and guardrail_code <> ?",
          currentTenant(),
          code);
    }

    sql.execute(
        "insert into ad_promotion_guardrail (tenant_id,id,guardrail_code,guardrail_name,"
            + " max_discount_rate,min_net_price,block_on_stacking,status,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,0,now(),now())"
            + " on conflict (tenant_id,guardrail_code) do update set"
            + " guardrail_name = excluded.guardrail_name,"
            + " max_discount_rate = excluded.max_discount_rate,"
            + " min_net_price = excluded.min_net_price,"
            + " block_on_stacking = excluded.block_on_stacking,"
            + " status = excluded.status,"
            + " note = excluded.note,"
            + " version = ad_promotion_guardrail.version + 1,"
            + " updated_at = now()",
        currentTenant(),
        Ids.next(),
        code,
        name,
        request.maxDiscountRate(),
        request.minNetPrice(),
        request.blockOnStacking() == null || request.blockOnStacking(),
        status,
        text(request.note()));

    Record row =
        sql.fetchOne(
            "select id, guardrail_code, guardrail_name, max_discount_rate, min_net_price,"
                + " block_on_stacking, status, note, version, created_at, updated_at"
                + " from ad_promotion_guardrail where tenant_id = "
                + TENANT
                + " and guardrail_code = ?",
            code);
    return guardrailRow(row);
  }

  // ==================================================================
  // 登记促销（蓝图的「促销定义」+「适用商品选择」）
  // ==================================================================

  public AdPromotionDetail savePromotion(SavePromotionRequest request) {
    Validated validated = validate(request, null);
    UUID promotionId = Ids.next();

    sql.execute(
        "insert into ad_promotion (tenant_id,id,platform_id,shop_code,promotion_code,"
            + " promotion_name,promotion_type,discount_rate,discount_amount,budget_cap,currency,"
            + " start_at,end_at,status,platform_sync_status,pending_platform_writeback,"
            + " local_changed_at,last_status_reason,platform_promotion_id,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'DRAFT','LOCAL_ONLY',"
            + " true,now(),'','',?,0,now(),now())",
        currentTenant(),
        promotionId,
        request.platformId(),
        validated.shopCode(),
        validated.code(),
        validated.name(),
        validated.type(),
        validated.discountRate(),
        validated.discountAmount(),
        validated.budgetCap(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        text(request.note()));

    insertProducts(promotionId, validated.products(), validated.currency());

    // 🔴 建完立刻跑一次检查：新建的促销当场就该看见拦截 / 预警结论，
    //   而不是让人以为「建好了就没问题」。
    runChecks(promotionId);

    return promotion(promotionId.toString());
  }

  /**
   * 改促销定义（折扣幅度 / 起止时间 / 预算上限 / 适用商品）。
   *
   * <p>🔴 语义是**替换**：载荷里没出现的适用商品会被移出这条促销
   * （与 14.1.1.2 的「结构登记是替换」同一手法，避免出现「以为改了其实没改」）。
   *
   * <p>🔴 促销编码**不可改**：它是租户内唯一的指称，改了会让历史记录指不到原来那条。
   */
  public AdPromotionDetail updatePromotion(String id, SavePromotionRequest request) {
    UUID promotionId = parseId(id);
    Record existing = promoRecord(promotionId);
    Validated validated = validate(request, existing.get("promotion_code", String.class));

    sql.execute(
        "update ad_promotion set promotion_name = ?, promotion_type = ?, discount_rate = ?,"
            + " discount_amount = ?, budget_cap = ?, currency = ?, start_at = ?::timestamptz,"
            + " end_at = ?::timestamptz, note = ?, local_changed_at = now(),"
            + " version = version + 1, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        validated.name(),
        validated.type(),
        validated.discountRate(),
        validated.discountAmount(),
        validated.budgetCap(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        text(request.note()),
        currentTenant(),
        promotionId);

    sql.execute(
        "delete from ad_promotion_product where tenant_id = ?::uuid and promotion_id = ?::uuid",
        currentTenant(),
        promotionId);
    insertProducts(promotionId, validated.products(), validated.currency());

    // 🔴 改完立刻重跑检查：旧结论不会自动消失（宁可拦着，不放行）。
    runChecks(promotionId);

    return promotion(promotionId.toString());
  }

  /** 校验后的促销定义（各字段都已被规范化）。 */
  private record Validated(
      String code,
      String name,
      String type,
      String shopCode,
      String currency,
      BigDecimal discountRate,
      BigDecimal discountAmount,
      BigDecimal budgetCap,
      Map<UUID, SavePromotionProduct> products) {}

  /**
   * 校验促销定义。
   *
   * @param existingCode 非空表示这是**修改**（促销编码必须与原值一致）。
   */
  private Validated validate(SavePromotionRequest request, String existingCode) {
    String code = text(request.promotionCode());
    String name = text(request.promotionName());
    String type = text(request.promotionType()).toUpperCase(Locale.ROOT);
    String shop = text(request.shopCode());

    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "促销编码与名称都不能为空");
    }
    if (!PROMOTION_TYPES.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "促销类型只能是 " + String.join(" / ", PROMOTION_TYPES));
    }
    if (shop.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参与店铺不能为空");
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }

    // 🔴 折扣口径与类型必须自洽（DB CHECK 也钉着，但这里要给一句人话）
    if ("PERCENT_OFF".equals(type)) {
      if (request.discountRate() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "百分比促销必须给折扣率");
      }
      if (request.discountRate().signum() <= 0 || request.discountRate().compareTo(BigDecimal.ONE) > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折扣率必须大于 0 且不超过 1");
      }
      if (request.discountAmount() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "百分比促销不能再给固定减免额 —— 折后价只能有一个答案");
      }
    } else if ("FIXED_AMOUNT_OFF".equals(type)) {
      if (request.discountAmount() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "固定额促销必须给减免额");
      }
      if (request.discountAmount().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "减免额不能为负");
      }
      if (request.discountRate() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "固定额促销不能再给折扣率 —— 折后价只能有一个答案");
      }
    } else {
      if (request.discountRate() != null || request.discountAmount() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "买 N 送 M / 组合优惠 / 免运费没有可折算的折扣率或减免额（折后价不可比，不是 0）");
      }
    }

    if (request.startAt() != null
        && request.endAt() != null
        && !request.endAt().isAfter(request.startAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "促销结束时间必须晚于开始时间");
    }
    if (request.budgetCap() != null && request.budgetCap().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算上限不能为负");
    }

    // 参与店铺必须属于当前租户
    Long shopExists =
        one(
            Long.class,
            "select count(*) from shop where tenant_id = " + TENANT + " and code = ?",
            shop);
    if (shopExists == null || shopExists == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "参与店铺不存在或不属于当前租户（店铺编码 " + shop + "）");
    }
    // 平台必须存在（全局码表）
    Long platformExists =
        one(Long.class, "select count(*) from platform where id = ?", request.platformId());
    if (platformExists == null || platformExists == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台不存在");
    }

    if (existingCode == null) {
      // 促销编码租户内唯一
      Long codeExists =
          one(
              Long.class,
              "select count(*) from ad_promotion where tenant_id = "
                  + TENANT
                  + " and promotion_code = ?",
              code);
      if (codeExists != null && codeExists > 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "促销编码 " + code + " 在当前租户下已存在");
      }
    } else if (!existingCode.equals(code)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "促销编码创建后不可更改（它是租户内唯一的指称，改了历史记录就指不到原来那条）");
    }

    // 适用商品：每个 listing 必须属于当前租户（复合 FK 也钉着）
    List<SavePromotionProduct> products =
        request.products() == null ? List.of() : request.products();
    Map<UUID, SavePromotionProduct> unique = new LinkedHashMap<>();
    for (SavePromotionProduct product : products) {
      if (product.listingId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "适用商品必须点名 listing");
      }
      if (unique.containsKey(product.listingId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "同一个商品在一条促销里只能登记一次");
      }
      Long listingExists =
          one(
              Long.class,
              "select count(*) from listing where tenant_id = " + TENANT + " and id = ?",
              product.listingId());
      if (listingExists == null || listingExists == 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "适用商品指向的 listing 不存在或不属于当前租户：" + product.listingId());
      }
      if (product.basePrice() != null && product.basePrice().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基准价不能为负");
      }
      unique.put(product.listingId(), product);
    }

    return new Validated(
        code, name, type, shop, currency, request.discountRate(), request.discountAmount(),
        request.budgetCap(), unique);
  }

  private void insertProducts(
      UUID promotionId, Map<UUID, SavePromotionProduct> products, String promotionCurrency) {
    for (SavePromotionProduct product : products.values()) {
      String productCurrency = text(product.currency()).toUpperCase(Locale.ROOT);
      if (productCurrency.isEmpty()) {
        productCurrency = promotionCurrency;
      }
      sql.execute(
          "insert into ad_promotion_product (tenant_id,id,promotion_id,listing_id,base_price,"
              + " currency,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,now(),now())",
          currentTenant(),
          Ids.next(),
          promotionId,
          product.listingId(),
          product.basePrice(),
          productCurrency,
          text(product.note()));
    }
  }

  // ==================================================================
  // 状态变更
  // ==================================================================

  /**
   * 改本地状态（激活 / 暂停 / 结束 / 取消）。
   *
   * <p>🔴 进入生效路径（{@code SCHEDULED} 排期 与 {@code ACTIVE} 上线）都是**拦截点**：
   * 最近一次检查里有 BLOCKED 结论时**拒绝**并说清是哪条规则拦下的。
   * 这才是蓝图加粗句「需在下单前拦截或预警」的落点 —— 没有这条，光有「预警」等于没拦。
   *
   * <p>🔴 结论取**最近一次检查**：改完折扣 / 基准价后必须重跑检查，旧结论不会自动消失
   * （宁可拦着，不放行）。
   */
  public AdPromotionDetail changeStatus(String id, ChangePromotionStatusRequest request) {
    UUID promotionId = parseId(id);
    Record promo = promoRecord(promotionId);
    String current = promo.get("status", String.class);
    String target = text(request.status()).toUpperCase(Locale.ROOT);

    if (!STATUSES.contains(target)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标状态只能是 " + String.join(" / ", STATUSES));
    }
    if (current.equals(target)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "促销已经是「" + target + "」状态，无需重复变更");
    }
    List<String> allowed = STATUS_TRANSITIONS.getOrDefault(current, List.of());
    if (!allowed.contains(target)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "不允许从「" + current + "」变更为「" + target + "」"
              + (allowed.isEmpty() ? "（终态不可回退，要再来一次请新建一条）" : "（允许的目标：" + String.join(" / ", allowed) + "）"));
    }

    if ("SCHEDULED".equals(target) || "ACTIVE".equals(target)) {
      List<AdPromotionCheckRow> checks = latestChecks(promotionId);
      List<String> blocked =
          checks.stream()
              .filter(c -> "BLOCKED".equals(c.severity()))
              .map(c -> c.ruleCode() + "：" + c.message())
              .toList();
      if (!blocked.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "促销被拦截，不能进入「"
                + target
                + "」。拦截原因："
                + String.join("；", blocked)
                + "。改完折扣 / 基准价后**重新跑一次检查**再排期。");
      }
    }

    sql.execute(
        "update ad_promotion set status = ?, last_status_reason = ?,"
            + " local_changed_at = now(), version = version + 1, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        target,
        text(request.reason()),
        currentTenant(),
        promotionId);

    return promotion(promotionId.toString());
  }

  // ==================================================================
  // 拦截 / 预警：真规则、真判定、真落库
  // ==================================================================

  /** 跑一次检查（真写入口：结论落 {@code ad_promotion_check}）。 */
  public AdPromotionDetail check(String id) {
    UUID promotionId = parseId(id);
    promoRecord(promotionId);
    runChecks(promotionId);
    return promotion(promotionId.toString());
  }

  private void runChecks(UUID promotionId) {
    Record promo = promoRecord(promotionId);
    String type = promo.get("promotion_type", String.class);
    BigDecimal rate = promo.get("discount_rate", BigDecimal.class);
    BigDecimal amount = promo.get("discount_amount", BigDecimal.class);
    BigDecimal budgetCap = promo.get("budget_cap", BigDecimal.class);

    AdPromotionGuardrailRow guardrail = activeGuardrail();
    List<AdPromotionProductRow> products = productsOf(promo, promotionId);

    UUID runId = Ids.next();
    OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);

    // ---- 规则一：折扣幅度是否超出阈值 ----
    // 百分比促销是**促销级**（折扣率是促销自己的属性）；
    // 固定额促销是**商品级**（幅度取决于该商品的基准价）。
    if (guardrail != null && guardrail.maxDiscountRate() != null) {
      BigDecimal max = guardrail.maxDiscountRate();
      if ("PERCENT_OFF".equals(type) && rate != null) {
        if (rate.compareTo(max) > 0) {
          insertCheck(
              runId,
              promotionId,
              null,
              "DISCOUNT_DEPTH",
              "BLOCKED",
              "折扣率 " + percentText(rate) + " 超过阈值 " + percentText(max)
                  + "（该阈值由护栏规则「" + guardrail.guardrailName() + "」设定）",
              rate,
              max,
              checkedAt);
        }
      } else if ("FIXED_AMOUNT_OFF".equals(type)) {
        for (AdPromotionProductRow product : products) {
          BigDecimal depth = product.discountDepth();
          if (depth == null) {
            continue;
          }
          if (depth.compareTo(max) > 0) {
            insertCheck(
                runId,
                promotionId,
                product.listingId(),
                "DISCOUNT_DEPTH",
                "BLOCKED",
                "商品「" + product.title() + "」的折扣幅度 " + percentText(depth)
                    + " 超过阈值 " + percentText(max)
                    + "（减免额 / 基准价；阈值由护栏规则「" + guardrail.guardrailName() + "」设定）",
                depth,
                max,
                checkedAt);
          }
        }
      }
    }

    // ---- 规则二：折后价是否低于下限 ----
    // 用**叠加后**的折后价：折扣叠加才是真正会把价格打穿的那一种。
    if (guardrail != null && guardrail.minNetPrice() != null) {
      BigDecimal floor = guardrail.minNetPrice();
      for (AdPromotionProductRow product : products) {
        BigDecimal net = product.stackedNetPrice() != null ? product.stackedNetPrice() : product.netPrice();
        if (net == null) {
          continue;
        }
        if (net.compareTo(floor) < 0) {
          insertCheck(
              runId,
              promotionId,
              product.listingId(),
              "PRICE_FLOOR",
              "BLOCKED",
              "商品「" + product.title() + "」折后价 " + net.toPlainString()
                  + " 低于下限 " + floor.toPlainString()
                  + (product.stackedPromotionCount() > 0
                      ? "（含 " + product.stackedPromotionCount() + " 个叠加促销）"
                      : "")
                  + "（下限由护栏规则「" + guardrail.guardrailName() + "」设定）",
              net,
              floor,
              checkedAt);
        }
      }
    }

    // ---- 规则三：促销是否叠加 ----
    if (products.stream().anyMatch(p -> p.stackedPromotionCount() > 0)) {
      boolean block = guardrail == null || guardrail.blockOnStacking();
      for (AdPromotionProductRow product : products) {
        if (product.stackedPromotionCount() <= 0) {
          continue;
        }
        insertCheck(
            runId,
            promotionId,
            product.listingId(),
            "PROMOTION_STACKING",
            block ? "BLOCKED" : "WARNING",
            "商品「" + product.title() + "」同时被 " + (product.stackedPromotionCount() + 1)
                + " 个促销覆盖（叠加 " + product.stackedPromotionCount() + " 个），"
                + "叠加折后价 "
                + (product.stackedNetPrice() == null
                    ? "不可比（该促销类型没有可直接折算的价格系数）"
                    : product.stackedNetPrice().toPlainString())
                + (block ? "；护栏规则要求叠加必须拦截" : "；护栏规则配置为只预警"),
            BigDecimal.valueOf(product.stackedPromotionCount()),
            BigDecimal.ONE,
            checkedAt);
      }
    }

    // ---- 规则四：最坏情况让利额是否超过预算上限 ----
    // 🔴 「最坏情况」= 每个商品都按折后价卖掉时的让利额（销量未知 ⇒ 取上界）。
    //   算不出来的商品（没登记基准价 / 非价格型促销）不计入，消息里要说清。
    if (budgetCap != null) {
      BigDecimal worst = BigDecimal.ZERO;
      int counted = 0;
      int skipped = 0;
      for (AdPromotionProductRow product : products) {
        BigDecimal net = product.netPrice();
        if (net == null || product.basePrice() == null) {
          skipped++;
          continue;
        }
        worst = worst.add(product.basePrice().subtract(net));
        counted++;
      }
      if (worst.compareTo(budgetCap) > 0) {
        insertCheck(
            runId,
            promotionId,
            null,
            "BUDGET_CAP",
            "WARNING",
            "最坏情况让利额 " + worst.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + " 已超过预算上限 " + budgetCap.toPlainString()
                + "（按 " + counted + " 个可测算商品估算"
                + (skipped > 0 ? "，另有 " + skipped + " 个商品算不出来未计入" : "")
                + "；实际消耗取决于销量）",
            worst,
            budgetCap,
            checkedAt);
      }
    }

    // 🔴 本次检查的批次指针落在促销行上 —— 一次**没有发现问题**的检查一行都不写，
    //   指针必须仍然指到这一批，否则「最近一次检查」会退回上一批的旧结论。
    sql.execute(
        "update ad_promotion set last_checked_at = ?::timestamptz, last_check_run_id = ?::uuid"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        checkedAt,
        runId,
        currentTenant(),
        promotionId);
  }

  private void insertCheck(
      UUID runId,
      UUID promotionId,
      UUID listingId,
      String ruleCode,
      String severity,
      String message,
      BigDecimal observed,
      BigDecimal threshold,
      OffsetDateTime checkedAt) {
    sql.execute(
        "insert into ad_promotion_check (tenant_id,id,promotion_id,listing_id,rule_code,severity,"
            + " message,observed_value,threshold_value,check_run_id,checked_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        currentTenant(),
        Ids.next(),
        promotionId,
        listingId,
        ruleCode,
        severity,
        message,
        observed,
        threshold,
        runId,
        checkedAt);
  }

  /** 折扣率按百分比展示（0.2 → 20%），避免界面上出现 0.2 这种看不出量级的数字。 */
  private static String percentText(BigDecimal rate) {
    return rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
  }
}
