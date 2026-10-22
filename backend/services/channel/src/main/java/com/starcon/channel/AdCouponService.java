package com.starcon.channel;

import static com.starcon.channel.AdCouponModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.4.1.2 优惠券与秒杀（券这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。输入：券规则；投放计划；核销数据同步。
 * 输出：券记录 + 核销统计。关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
 * <b>券的核销要与订单绑定</b>，否则无法评估活动真实效果与补贴成本。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>券规则</b> → {@link #saveCoupon}：一张券就是一份规则快照。
 *   <li><b>生成码</b> → {@link #generateCodes}：真库存计数（不是「生成 N 个就算 N 个库存」）。
 *   <li><b>设置库存与限领</b> → {@link #saveCoupon} / {@link #claim}：库存扣减与限领判据
 *       都在**显式行锁**内做，不靠懒加载掩盖竞态。
 *   <li><b>投放渠道（站内/站外/社媒）</b> → {@link #saveChannel} + {@link #channels}：
 *       投放记录与投放计划落在自己的表里。
 *   <li><b>跟踪领取与核销</b> → {@link #claim} / {@link #redeem} / {@link #statistics}。
 *   <li><b>券记录 + 核销统计</b> → {@link #coupons} / {@link #statistics}（**真聚合**）。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的五个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>把门槛当成实时价去 catalog 拉</b>。上游 5 定价在 <b>catalog</b> 服务
 *       （跨服务不可读）⇒ 门槛 / 面额是**建券时登记的快照**，本模块**不 join catalog 的表**，
 *       也不写 {@code catalog.} 限定名。</li>
 *   <li><b>把「本地已建」说成「平台已建」</b>。平台营销接口未交付 ⇒
 *       {@code platform_sync_status} 恒为 {@code LOCAL_ONLY} +
 *       {@code pending_platform_writeback} 恒为 true，界面必须写明「平台侧还没有这张券」。</li>
 *   <li><b>核销不绑订单</b>。{@code order_ref} NOT NULL，空串直接 400 ——
 *       没有订单标识的核销**写不进来**（蓝图加粗句）。</li>
 *   <li><b>把「测不出成本」写成 0</b>。买赠券的补贴成本需要成本价，成本在 05 定价
 *       （跨服务不可读）⇒ 写 <b>null</b>，统计里单列「不可测算笔数」。
 *       写 0 会被界面渲染成「补贴成本是 0」—— 那是假事实。</li>
 *   <li><b>限领只看单次不看累计</b>。限领的判据是
 *       「这个人在**这张券上**累计领了几张」⇒ {@code ad_coupon_claim} 按
 *       (coupon_id, recipient_key) 聚合，不是看本次请求。</li>
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读**同库的 {@code platform} / {@code shop} / {@code listing}
 * / {@code ad_promotion}，写入一律落在自己的六张表上 ——
 * 券不是广告活动，**不塞进 {@code ad_campaign}**，也不复用广告表当券表。
 */
@Service
public class AdCouponService {

  // ==================================================================
  // 常量：取值域与口径
  // ==================================================================

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 券类型全集。 */
  static final List<String> COUPON_TYPES = List.of("DISCOUNT_CODE", "FULL_REDUCTION", "BOGO");

  /** 本地生命周期状态全集。 */
  static final List<String> STATUSES =
      List.of("DRAFT", "ACTIVE", "PAUSED", "ENDED", "CANCELLED");

  /** 平台同步状态全集（只有 LOCAL_ONLY 是本批真实可达的）。 */
  static final List<String> SYNC_STATUSES =
      List.of("LOCAL_ONLY", "PENDING_WRITEBACK", "SYNCED", "FAILED");

  /** 领取方式全集。 */
  static final List<String> CLAIM_KINDS = List.of("PULL", "PUSH", "CHANNEL");

  /** 投放渠道全集（站内 / 站外 / 社媒）。 */
  static final List<String> CHANNEL_TYPES = List.of("ON_SITE", "OFF_SITE", "SOCIAL");

  /** 领取渠道全集（比投放渠道多一个「定向发放」）。 */
  static final List<String> CLAIM_CHANNEL_TYPES =
      List.of("ON_SITE", "OFF_SITE", "SOCIAL", "DIRECT");

  /**
   * 本地状态机：允许的状态迁移。
   *
   * <p>🔴 终态（ENDED / CANCELLED）**没有出边** —— 券结束或停用之后不能悄悄复活，
   * 要再来一次就是**新建一张**（历史留痕比状态回退更有用）。
   */
  static final Map<String, List<String>> STATUS_TRANSITIONS =
      Map.of(
          "DRAFT", List.of("ACTIVE", "CANCELLED"),
          "ACTIVE", List.of("PAUSED", "ENDED", "CANCELLED"),
          "PAUSED", List.of("ACTIVE", "ENDED", "CANCELLED"),
          "ENDED", List.of(),
          "CANCELLED", List.of());

  /** 买赠券：没有面值口径 ⇒ 补贴成本本地测不出来。 */
  private static final String TYPE_BOGO = "BOGO";

  /** 券码随机字符集默认值（去掉易混的 0/O/1/I）。 */
  static final String DEFAULT_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  // ==================================================================
  // 口径说明（随响应返回，界面上的表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String PRICING_SNAPSHOT_NOTE =
      "🔴 **门槛与面额是快照**：上游 5 定价在 **catalog** 服务，channel **不得跨服务 SQL**"
          + "（不 join、不建跨库 FK、不建共享视图）⇒ 券的门槛 / 面额 / 折扣率是"
          + "**建券时登记的输入**（创建当时的定价口径），**不是**实时价。"
          + "核销时用这份快照算让利额；5 定价交付促销价测算端点后可以接上对账，"
          + "本模块的判定逻辑一行都不用改。";

  static final String SYNC_NOTE =
      "🔴 **平台营销接口未交付** ⇒ 平台同步状态只有「本地已建、平台侧还没有」（LOCAL_ONLY）。"
          + "`pendingPlatformWriteback=true` 表示**平台侧还没有这张券**，"
          + "界面上必须写明这一点 —— 否则「已创建优惠券」会被当成平台侧已创建，那是假事实。"
          + "`SYNCED` 只能由平台回写适配器产生（该适配器未交付），本批代码里没有任何路径写入它。";

  static final String RECIPIENT_NOTE =
      "🔴 **定向发放自包含地做**：下游 15 客户（定向发放）**未交付** ⇒ 本模块不跨服务写客户表，"
          + "收件人先以**收件人标识（字符串）**记录（`claim_kind='PUSH'`、"
          + "`channel_type='DIRECT'`）。发放记录与渠道投放计划**都落在本模块自己的表里**。"
          + "15 客户交付后由它把标识解析成真实客户 id，本模块不用改。";

  static final String SUBSIDY_NOTE =
      "🔴 **补贴成本不可测算时是 null，不是 0**：买赠（BOGO）券的成本 = 赠品成本价，"
          + "成本价在 05 定价（catalog，跨服务不可读）⇒ 这类核销的 `subsidyCost` 写 null，"
          + "统计里单列「不可测算笔数」。⛔ **不写 0** —— 写一个 0 会被界面渲染成"
          + "「补贴成本是 0」，而真相是「拿不到成本」，两者在运营决策上完全相反。";

  static final String ORDER_BINDING_NOTE =
      "🔴 **核销必须与订单绑定**（蓝图加粗句）：核销请求的 `orderRef` 为空直接拒绝 ——"
          + "没有订单标识的核销**写不进来**，否则无法评估活动真实效果与补贴成本。"
          + "同一张券在同一订单上只能核销一次（DB UNIQUE(tenant_id, coupon_id, order_ref)），"
          + "一个券码也只能核销一次（DB UNIQUE(tenant_id, code_id)）。";

  static final String UPSTREAM_NOTE =
      "上游 = 2.1.1.1 platform（全局码表）+ 2.3.1.1 shop（参与店铺，复合 FK 带 tenant_id）"
          + "+ 4.1.1.1 listing（同库同服务，秒杀用）+ 14.4.1.1 ad_promotion（同库同服务，券可挂促销）；"
          + "5 定价在 catalog，**跨服务不可读** ⇒ 门槛与面额只能是快照。";

  static final String BOUNDARY_NOTE =
      "下游 = 平台营销接口（**未交付** ⇒ 券只落本地并标「平台侧还没有」）"
          + "与 15 客户（**未交付** ⇒ 定向发放只记收件人标识）。"
          + "本模块**不实现**促销效果分析（14.4.2.1）。";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final DSLContext sql;

  public AdCouponService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具（照抄 AdPromotionService 的同名写法）
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

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 14.4.1.1 / 10.1.1.2 同款口径）。 */
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

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdCouponCapabilities capabilities() {
    return new AdCouponCapabilities(
        List.of(
            new AdCouponCapabilityDimension(
                "coupon_definition",
                "券规则：类型 / 面额 / 门槛 / 封顶 / 生效时间窗 / 库存 / 限领",
                true,
                "ad_coupon（V286）",
                "",
                "没有券规则就没有券记录；折扣口径由 DB CHECK 钉死（折扣率与面额互斥）"),
            new AdCouponCapabilityDimension(
                "code_generation",
                "生成码：按生成规则批量生成券码，真库存计数",
                true,
                "ad_coupon_code（V286）",
                "",
                "没有券码就领不了；「库存」必须是真计数，不是写死的数字"),
            new AdCouponCapabilityDimension(
                "stock_and_limit",
                "库存扣减与按人限领（显式行锁 + 计数列，DB CHECK 自洽）",
                true,
                "ad_coupon.generated_count / claimed_count + ad_coupon_claim",
                "",
                "没有限领，一个券码可以被同一个人无限领走；没有库存，超发就发生了"),
            new AdCouponCapabilityDimension(
                "channel_placement",
                "投放渠道（站内 / 站外 / 社媒）+ 投放计划",
                true,
                "ad_coupon_channel（V286）",
                "",
                "没有投放记录就不知道这张券投在哪儿、实际领了多少"),
            new AdCouponCapabilityDimension(
                "claim_tracking",
                "领取跟踪（用户领取 / 定向发放 / 渠道投放领取）",
                true,
                "ad_coupon_claim（V286）",
                "",
                "没有领取记录就分不清「没人要」与「没人看见」"),
            new AdCouponCapabilityDimension(
                "redemption_binding",
                "🔴 **核销与订单绑定** + 补贴成本落库",
                true,
                "ad_coupon_redemption（V286）",
                "",
                "核销不绑订单就评估不了活动真实效果与补贴成本（蓝图加粗句）"),
            new AdCouponCapabilityDimension(
                "redemption_statistics",
                "核销统计（真聚合：核销率 / 补贴成本 / 渠道拆分 / 按日拆分）",
                true,
                "ad_coupon_redemption + ad_coupon_claim（V286）",
                "",
                "没有统计就只有一堆流水，运营看不出活动效果"),
            new AdCouponCapabilityDimension(
                "targeted_issue",
                "定向发放（自包含：发放记录落在本模块自己的表里）",
                true,
                "ad_coupon_claim（claim_kind=PUSH）",
                "",
                "下游 15 客户未交付 ⇒ 只记收件人标识，发放动作本身要真的落库、真的扣库存"),
            new AdCouponCapabilityDimension(
                "platform_marketing_writeback",
                "把券 / 秒杀回写到平台营销接口",
                false,
                "—",
                "平台适配层（channel/vendor 的平台营销 API）",
                "平台营销接口未交付 ⇒ 券只落本地并标「平台侧还没有」；"
                    + "界面上不把「本地已建」说成「平台侧已建」"),
            new AdCouponCapabilityDimension(
                "customer_master_data",
                "🔴 **收件人主数据**（客户档案 / 会员等级 / 定向人群包）",
                false,
                "—",
                "15 客户与客服（下游，本批未交付）",
                "定向发放只记收件人标识（字符串）⇒ 拿不到客户画像，"
                    + "人群圈选要等 15 客户交付"),
            new AdCouponCapabilityDimension(
                "bogo_subsidy_cost",
                "🔴 **买赠券的补贴成本**",
                false,
                "—",
                "5 定价（成本价，在 catalog 服务）",
                "成本价跨服务不可读 ⇒ 买赠核销的补贴成本写 **null（不可测算）**并单列笔数，"
                    + "⛔ **不写 0**（0 会被当成「补贴成本是 0」的假事实）")),
        COUPON_TYPES,
        STATUSES,
        SYNC_STATUSES,
        CLAIM_KINDS,
        CHANNEL_TYPES,
        PRICING_SNAPSHOT_NOTE,
        SYNC_NOTE,
        RECIPIENT_NOTE,
        SUBSIDY_NOTE,
        ORDER_BINDING_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdCouponOptions options() {
    List<AdCouponPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new AdCouponPlatformOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)))
            .toList();

    List<String> shopCodes =
        sql.fetch(
                "select distinct code from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> currencies =
        sql.fetch(
                "select distinct currency from ad_coupon where tenant_id = "
                    + TENANT
                    + " and currency <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new AdCouponOptions(
        couponTypeOptions(),
        statusOptions(),
        syncStatusOptions(),
        claimKindOptions(),
        channelTypeOptions(),
        platforms,
        platforms.stream().map(AdCouponPlatformOption::code).toList(),
        shopCodes,
        currencies,
        PRICING_SNAPSHOT_NOTE);
  }

  private static List<AdCouponOption> couponTypeOptions() {
    return List.of(
        new AdCouponOption("DISCOUNT_CODE", "折扣码（折扣率或固定面额）"),
        new AdCouponOption("FULL_REDUCTION", "满减（门槛 + 减免额）"),
        new AdCouponOption("BOGO", "买赠（买 N 送 M）"));
  }

  private static List<AdCouponOption> statusOptions() {
    return List.of(
        new AdCouponOption("DRAFT", "草稿"),
        new AdCouponOption("ACTIVE", "生效中"),
        new AdCouponOption("PAUSED", "已暂停"),
        new AdCouponOption("ENDED", "已结束"),
        new AdCouponOption("CANCELLED", "已停用"));
  }

  private static List<AdCouponOption> syncStatusOptions() {
    return List.of(
        new AdCouponOption("LOCAL_ONLY", "本地已建，平台侧还没有"),
        new AdCouponOption("PENDING_WRITEBACK", "回写已发起，待平台确认"),
        new AdCouponOption("SYNCED", "平台侧已一致"),
        new AdCouponOption("FAILED", "回写失败"));
  }

  private static List<AdCouponOption> claimKindOptions() {
    return List.of(
        new AdCouponOption("PULL", "用户主动领取"),
        new AdCouponOption("PUSH", "定向发放"),
        new AdCouponOption("CHANNEL", "渠道投放领取"));
  }

  private static List<AdCouponOption> channelTypeOptions() {
    return List.of(
        new AdCouponOption("ON_SITE", "站内"),
        new AdCouponOption("OFF_SITE", "站外"),
        new AdCouponOption("SOCIAL", "社媒"));
  }

  // ==================================================================
  // 券列表（真分页 + 真筛选）
  // ==================================================================

  public AdCouponPage coupons(
      String status,
      String couponType,
      UUID platformId,
      String shopCode,
      String channelType,
      String syncStatus,
      String keyword,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String state = blankToNull(status);
    if (state != null) {
      where.append(" and c.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String type = blankToNull(couponType);
    if (type != null) {
      where.append(" and c.coupon_type = ?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }
    if (platformId != null) {
      where.append(" and c.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    String sync = blankToNull(syncStatus);
    if (sync != null) {
      where.append(" and c.platform_sync_status = ?");
      binds.add(sync.toUpperCase(Locale.ROOT));
    }
    String channel = blankToNull(channelType);
    if (channel != null) {
      where.append(
          " and exists (select 1 from ad_coupon_channel ch where ch.tenant_id = c.tenant_id"
              + " and ch.coupon_id = c.id and ch.channel_type = ?)");
      binds.add(channel.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (c.coupon_code ilike ? or c.coupon_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_coupon c" + where, binds.toArray());

    List<AdCouponRow> items =
        sql.fetch(
                "select c.id, c.coupon_code, c.coupon_name, c.coupon_type, c.platform_id,"
                    + " (select pf.code from platform pf where pf.id = c.platform_id) platform_code,"
                    + " c.shop_code, c.promotion_id, c.discount_rate, c.discount_value,"
                    + " c.threshold_amount, c.max_discount_amount, c.buy_quantity,"
                    + " c.gift_quantity, c.currency, c.start_at, c.end_at, c.status,"
                    + " c.total_stock, c.generated_count, c.claimed_count, c.redeemed_count,"
                    + " c.per_user_limit, c.platform_sync_status, c.pending_platform_writeback,"
                    + " c.local_changed_at, c.note, c.version, c.created_at, c.updated_at,"
                    + " (select count(*)::int from ad_coupon_channel ch where ch.tenant_id = c.tenant_id"
                    + "  and ch.coupon_id = c.id) channel_count"
                    + " from ad_coupon c"
                    + where
                    + " order by c.created_at desc, c.id desc"
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::couponRow)
            .toList();

    return new AdCouponPage(items, total, safePage, safeSize);
  }

  private AdCouponRow couponRow(Record r) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    BigDecimal totalStock = r.get("total_stock", BigDecimal.class);
    BigDecimal generated = r.get("generated_count", BigDecimal.class);
    BigDecimal claimed = r.get("claimed_count", BigDecimal.class);
    // 🔴 「此刻可领的剩余额度」= 已生成但还没被领走的券码数（不是 total − generated：
    //   那是「还能再生成多少」，不是「还能领多少」）
    BigDecimal claimable =
        generated == null || claimed == null ? null : generated.subtract(claimed);

    return new AdCouponRow(
        r.get("id", UUID.class),
        r.get("coupon_code", String.class),
        r.get("coupon_name", String.class),
        r.get("coupon_type", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("promotion_id", UUID.class),
        r.get("discount_rate", BigDecimal.class),
        r.get("discount_value", BigDecimal.class),
        r.get("threshold_amount", BigDecimal.class),
        r.get("max_discount_amount", BigDecimal.class),
        r.get("buy_quantity", BigDecimal.class),
        r.get("gift_quantity", BigDecimal.class),
        r.get("currency", String.class),
        r.get("start_at", OffsetDateTime.class),
        r.get("end_at", OffsetDateTime.class),
        withinWindow(now, r.get("start_at", OffsetDateTime.class), r.get("end_at", OffsetDateTime.class)),
        r.get("status", String.class),
        totalStock,
        generated,
        claimed,
        r.get("redeemed_count", BigDecimal.class),
        claimable,
        r.get("per_user_limit", BigDecimal.class),
        r.get("platform_sync_status", String.class),
        r.get("pending_platform_writeback", Boolean.class),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("channel_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 🔴 时间窗判定：一端为空按「无界」处理。 */
  static boolean withinWindow(
      OffsetDateTime now, OffsetDateTime startAt, OffsetDateTime endAt) {
    if (startAt != null && now.isBefore(startAt)) return false;
    if (endAt != null && !now.isBefore(endAt)) return false;
    return true;
  }

  // ==================================================================
  // 券详情
  // ==================================================================

  public AdCouponDetail coupon(String id) {
    UUID couponId = parseId(id);
    Record r =
        sql.fetchOne(
            "select c.id, c.coupon_code, c.coupon_name, c.coupon_type, c.platform_id,"
                + " (select pf.code from platform pf where pf.id = c.platform_id) platform_code,"
                + " c.shop_code, c.promotion_id, c.discount_rate, c.discount_value,"
                + " c.threshold_amount, c.max_discount_amount, c.buy_quantity,"
                + " c.gift_quantity, c.currency, c.start_at, c.end_at, c.status,"
                + " c.total_stock, c.generated_count, c.claimed_count, c.redeemed_count,"
                + " c.per_user_limit, c.platform_sync_status, c.pending_platform_writeback,"
                + " c.local_changed_at, c.note, c.version, c.created_at, c.updated_at,"
                + " (select count(*)::int from ad_coupon_channel ch where ch.tenant_id = c.tenant_id"
                + "  and ch.coupon_id = c.id) channel_count"
                + " from ad_coupon c where c.tenant_id = "
                + TENANT
                + " and c.id = ?",
            couponId);
    if (r == null) throw notFound("优惠券");

    return new AdCouponDetail(
        couponRow(r),
        channels(couponId),
        recentClaims(couponId, 20),
        recentRedemptions(couponId, 20),
        statistics(couponId),
        SYNC_NOTE,
        PRICING_SNAPSHOT_NOTE,
        r.get("note", String.class));
  }

  // ==================================================================
  // 登记 / 修改券
  // ==================================================================

  public AdCouponDetail saveCoupon(SaveCouponRequest request) {
    Validated validated = validate(request, null);
    UUID couponId = Ids.next();

    sql.execute(
        "insert into ad_coupon (tenant_id,id,platform_id,shop_code,promotion_id,coupon_code,"
            + " coupon_name,coupon_type,discount_rate,discount_value,threshold_amount,"
            + " max_discount_amount,buy_quantity,gift_quantity,currency,start_at,end_at,"
            + " total_stock,generated_count,claimed_count,redeemed_count,per_user_limit,"
            + " code_prefix,code_length,code_charset,status,platform_sync_status,"
            + " pending_platform_writeback,local_changed_at,last_status_reason,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,"
            + " ?,0,0,0,?,?,?,?,'DRAFT','LOCAL_ONLY',true,now(),'',?,0,now(),now())",
        currentTenant(),
        couponId,
        request.platformId(),
        validated.shopCode(),
        validated.promotionId(),
        validated.code(),
        validated.name(),
        validated.type(),
        validated.discountRate(),
        validated.discountValue(),
        validated.thresholdAmount(),
        validated.maxDiscountAmount(),
        validated.buyQuantity(),
        validated.giftQuantity(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        validated.totalStock(),
        validated.perUserLimit(),
        validated.codePrefix(),
        validated.codeLength(),
        validated.codeCharset(),
        text(request.note()));

    return coupon(couponId.toString());
  }

  /**
   * 改券规则。
   *
   * <p>🔴 语义是**替换**：没出现的字段按「清空」处理，但**券编码不可改**。
   * 库存**只能调大或调小到不低于已生成数** —— 把库存调到比已生成的码还少，
   * 会让已经发出去的码变成「超发」。
   */
  public AdCouponDetail updateCoupon(String id, SaveCouponRequest request) {
    UUID couponId = parseId(id);
    Validated validated = validate(request, couponId);
    Record current = lockedCoupon(couponId);
    if (current == null) throw notFound("优惠券");

    BigDecimal generated = current.get("generated_count", BigDecimal.class);
    if (validated.totalStock().compareTo(generated) < 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "库存不能调到低于已生成的券码数（已生成 "
              + generated.stripTrailingZeros().toPlainString()
              + " 张）");
    }

    sql.execute(
        "update ad_coupon set coupon_name = ?, coupon_type = ?, discount_rate = ?,"
            + " discount_value = ?, threshold_amount = ?, max_discount_amount = ?,"
            + " buy_quantity = ?, gift_quantity = ?, currency = ?, start_at = ?::timestamptz,"
            + " end_at = ?::timestamptz, total_stock = ?, per_user_limit = ?, code_prefix = ?,"
            + " code_length = ?, code_charset = ?, note = ?, local_changed_at = now(),"
            + " version = version + 1, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        validated.name(),
        validated.type(),
        validated.discountRate(),
        validated.discountValue(),
        validated.thresholdAmount(),
        validated.maxDiscountAmount(),
        validated.buyQuantity(),
        validated.giftQuantity(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        validated.totalStock(),
        validated.perUserLimit(),
        validated.codePrefix(),
        validated.codeLength(),
        validated.codeCharset(),
        text(request.note()),
        couponId);

    return coupon(couponId.toString());
  }

  // ==================================================================
  // 状态机
  // ==================================================================

  /** 改本地状态（上线 / 暂停 / 结束 / 停用）。终态不可回退。 */
  public AdCouponDetail changeStatus(String id, ChangeCouponStatusRequest request) {
    UUID couponId = parseId(id);
    String target = text(request.status()).toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(target)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态取值不合法");
    }
    if (!STATUS_TRANSITIONS.containsKey(target)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态取值不合法");
    }

    Record current = lockedCoupon(couponId);
    if (current == null) throw notFound("优惠券");
    String from = current.get("status", String.class);
    if (from.equals(target)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "状态已经是 " + target);
    }
    List<String> allowed = STATUS_TRANSITIONS.getOrDefault(from, List.of());
    if (!allowed.contains(target)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "状态不允许从 " + from + " 变为 " + target);
    }

    sql.execute(
        "update ad_coupon set status = ?, last_status_reason = ?, local_changed_at = now(),"
            + " version = version + 1, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        target,
        text(request.reason()),
        couponId);

    return coupon(couponId.toString());
  }

  // ==================================================================
  // 生成券码（真库存计数）
  // ==================================================================

  /**
   * 批量生成券码。
   *
   * <p>🔴 库存是**真扣**的：生成数受 {@code total_stock − generated_count} 约束，
   * 请求数超过剩余库存直接 409 并说清还剩多少 —— 不做「静默截断」，
   * 因为静默截断会让调用方以为生成了 N 张。
   */
  public AdCouponCodePage generateCodes(String id, GenerateCouponCodesRequest request) {
    UUID couponId = parseId(id);
    Record locked = lockedCoupon(couponId);
    if (locked == null) throw notFound("优惠券");

    String status = locked.get("status", String.class);
    if (!status.equals("ACTIVE")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有生效中的券才能生成券码（当前状态：" + status + "）");
    }

    BigDecimal totalStock = locked.get("total_stock", BigDecimal.class);
    BigDecimal generated = locked.get("generated_count", BigDecimal.class);
    BigDecimal remaining = totalStock.subtract(generated);

    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "券码库存已用完，没有剩余库存");
    }
    if (BigDecimal.valueOf(request.count()).compareTo(remaining) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "库存不足：剩余 "
              + remaining.stripTrailingZeros().toPlainString()
              + " 张，请求 "
              + request.count()
              + " 张");
    }

    String prefix =
        blankToNull(request.codePrefix()) == null
            ? locked.get("code_prefix", String.class)
            : text(request.codePrefix());
    int length =
        request.codeLength() == null ? locked.get("code_length", Integer.class) : request.codeLength();
    String charset =
        blankToNull(request.codeCharset()) == null
            ? locked.get("code_charset", String.class)
            : text(request.codeCharset());
    if (charset.length() < 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券码字符集至少要有 2 个字符");
    }

    UUID tenant = currentTenant();
    List<UUID> codeIds = new ArrayList<>(request.count());
    for (int i = 0; i < request.count(); i++) {
      String code = uniqueCode(tenant, couponId, prefix, length, charset);
      UUID codeId = Ids.next();
      try {
        sql.execute(
            "insert into ad_coupon_code (tenant_id,id,coupon_id,code,status,claimant_key,"
                + " claimed_at,used_at,order_ref,created_at,updated_at)"
                + " values (?,?,?,?,'GENERATED','',null,null,'',now(),now())",
            tenant,
            codeId,
            couponId,
            code);
      } catch (DataAccessException e) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "券码生成冲突（" + code + "），请重试");
      }
      codeIds.add(codeId);
    }

    sql.execute(
        "update ad_coupon set generated_count = generated_count + ?, version = version + 1,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        BigDecimal.valueOf(codeIds.size()),
        couponId);

    return codes(couponId.toString(), "GENERATED", 0, Math.max(codeIds.size(), 50));
  }

  /** 生成一个租户内唯一的券码（先查后插，UNIQUE 约束做最终兜底）。 */
  private String uniqueCode(UUID tenant, UUID couponId, String prefix, int length, String charset) {
    for (int attempt = 0; attempt < 20; attempt++) {
      StringBuilder sb = new StringBuilder(prefix);
      for (int i = 0; i < length; i++) {
        sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
      }
      String candidate = sb.toString();
      Long exists =
          count(
              "select count(*) from ad_coupon_code where tenant_id = ? and (code = ? or"
                  + " (coupon_id = ? and code = ?))",
              tenant,
              candidate,
              couponId,
              candidate);
      if (exists == 0) return candidate;
    }
    throw new ResponseStatusException(
        HttpStatus.CONFLICT, "券码生成冲突（连续 20 次撞码），请调整生成规则后重试");
  }

  /** 券码列表（真分页 + 真筛选）。 */
  public AdCouponCodePage codes(String id, String status, int page, int size) {
    UUID couponId = parseId(id);
    requireCoupon(couponId);

    StringBuilder where =
        new StringBuilder(" where tenant_id = " + TENANT + " and coupon_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(couponId);
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from ad_coupon_code" + where, binds.toArray());

    List<AdCouponCodeRow> items =
        sql.fetch(
                "select id, code, status, claimant_key, claimed_at, used_at, order_ref, created_at"
                    + " from ad_coupon_code"
                    + where
                    + " order by created_at desc, id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new AdCouponCodeRow(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("status", String.class),
                        r.get("claimant_key", String.class),
                        r.get("claimed_at", OffsetDateTime.class),
                        r.get("used_at", OffsetDateTime.class),
                        r.get("order_ref", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    return new AdCouponCodePage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 领取 / 定向发放（限领校验真读真扣）
  // ==================================================================

  /**
   * 领取一张券（用户主动领取 / 定向发放 / 渠道投放领取）。
   *
   * <p>🔴 限领判据是「**这个人在这张券上累计领了几张**」，不是本次请求的数量。
   * 判定与扣减都在**显式行锁**内做（{@code select ... for update}），
   * 并发的两次领取不会都通过。
   */
  public AdCouponDetail claim(String id, ClaimCouponRequest request) {
    UUID couponId = parseId(id);
    String kind = text(request.claimKind()).toUpperCase(Locale.ROOT);
    if (!CLAIM_KINDS.contains(kind)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取方式取值不合法");
    }
    String recipient = text(request.recipientKey());
    if (recipient.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取人 / 收件人标识必填");
    }

    String channelType = resolveClaimChannelType(kind, request.channelType());
    UUID channelId = request.channelId();
    if (channelId != null) {
      Record channel =
          sql.fetchOne(
              "select id, channel_type from ad_coupon_channel where tenant_id = "
                  + TENANT
                  + " and id = ? and coupon_id = ?",
              channelId,
              couponId);
      if (channel == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放渠道不属于这张券");
      }
    }

    BigDecimal quantity =
        request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取张数必须大于 0");
    }

    Record locked = lockedCoupon(couponId);
    if (locked == null) throw notFound("优惠券");

    String status = locked.get("status", String.class);
    if (!status.equals("ACTIVE")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有生效中的券才能领取（当前状态：" + status + "）");
    }
    if (!withinWindow(
        OffsetDateTime.now(ZoneOffset.UTC),
        locked.get("start_at", OffsetDateTime.class),
        locked.get("end_at", OffsetDateTime.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "券不在生效时间窗内，不能领取");
    }

    // 🔴 库存真扣：已生成数是硬上限（已领取数 <= 已生成数由 DB CHECK 保证）
    BigDecimal generated = locked.get("generated_count", BigDecimal.class);
    BigDecimal claimed = locked.get("claimed_count", BigDecimal.class);
    BigDecimal stockLeft = generated.subtract(claimed);
    if (stockLeft.compareTo(quantity) < 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "券码库存不足：剩余 "
              + stockLeft.stripTrailingZeros().toPlainString()
              + " 张，请求 "
              + quantity.stripTrailingZeros().toPlainString()
              + " 张");
    }

    // 🔴 限领判据：这个人在这张券上**累计**领了多少
    BigDecimal perUserLimit = locked.get("per_user_limit", BigDecimal.class);
    BigDecimal alreadyClaimed =
        one(
            BigDecimal.class,
            "select coalesce(sum(quantity),0) from ad_coupon_claim where tenant_id = "
                + TENANT
                + " and coupon_id = ? and recipient_key = ?",
            couponId,
            recipient);
    if (perUserLimit != null && alreadyClaimed.add(quantity).compareTo(perUserLimit) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "超过每人限领：每人最多 "
              + perUserLimit.stripTrailingZeros().toPlainString()
              + " 张，该收件人已领 "
              + alreadyClaimed.stripTrailingZeros().toPlainString()
              + " 张");
    }

    // 🔴 取一个未被领过的码（**显式行锁**，并发领取不会拿到同一个码）
    UUID tenant = currentTenant();
    List<UUID> picked = new ArrayList<>();
    for (int i = 0; i < quantity.intValue(); i++) {
      Record code =
          sql.fetchOne(
              "select id from ad_coupon_code where tenant_id = "
                  + TENANT
                  + " and coupon_id = ? and status = 'GENERATED'"
                  + " order by created_at, id limit 1 for update",
              couponId);
      if (code == null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "券码库存不足，没有可领取的码");
      }
      UUID codeId = code.get("id", UUID.class);
      int updated =
          sql.execute(
              "update ad_coupon_code set status = 'CLAIMED', claimant_key = ?, claimed_at = now(),"
                  + " updated_at = now()"
                  + " where tenant_id = "
                  + TENANT
                  + " and id = ? and status = 'GENERATED'",
              recipient,
              codeId);
      if (updated != 1) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "券码已被并发领取，请重试");
      }
      sql.execute(
          "insert into ad_coupon_claim (tenant_id,id,coupon_id,code_id,claim_kind,recipient_key,"
              + " channel_type,channel_id,quantity,claimed_at)"
              + " values (?,?,?,?,?,?,?,?,?,now())",
          tenant,
          Ids.next(),
          couponId,
          codeId,
          kind,
          recipient,
          channelType,
          channelId,
          BigDecimal.ONE);
      picked.add(codeId);
    }

    sql.execute(
        "update ad_coupon set claimed_count = claimed_count + ?, version = version + 1,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        quantity,
        couponId);

    return coupon(couponId.toString());
  }

  /** 定向发放走 PUSH + DIRECT；渠道投放领取走 CHANNEL；用户领取走 PULL。 */
  private static String resolveClaimChannelType(String kind, String requested) {
    if (kind.equals("PUSH")) return "DIRECT";
    String channel = blankToNull(requested);
    if (channel == null) {
      return kind.equals("CHANNEL") ? "ON_SITE" : "ON_SITE";
    }
    String upper = channel.toUpperCase(Locale.ROOT);
    if (!CLAIM_CHANNEL_TYPES.contains(upper)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取渠道取值不合法");
    }
    if (kind.equals("PUSH") && upper.equals("DIRECT")) return "DIRECT";
    return upper;
  }

  // ==================================================================
  // 核销（绑定订单标识 + 补贴成本落库）
  // ==================================================================

  /**
   * 核销一张券。
   *
   * <p>🔴 蓝图加粗句的落点：**订单标识为空直接拒绝**（写不进来）。
   * 让利额用**券上的快照**算（不是实时价）；买赠券算不出让利额与补贴成本 ⇒
   * 写 null（不可测算），⛔ 不写 0。
   */
  public AdCouponDetail redeem(String id, RedeemCouponRequest request) {
    UUID couponId = parseId(id);
    String orderRef = text(request.orderRef());
    if (orderRef.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "订单标识必填 —— 核销必须与订单绑定");
    }

    Record locked = lockedCoupon(couponId);
    if (locked == null) throw notFound("优惠券");

    String status = locked.get("status", String.class);
    if (!status.equals("ACTIVE") && !status.equals("ENDED")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有生效中或已结束的券才能核销（当前状态：" + status + "）");
    }

    // 🔴 定位券码：指定了就按码找；没指定就取这个收件人已领未核销的码
    UUID codeId;
    if (blankToNull(request.code()) != null) {
      Record code =
          sql.fetchOne(
              "select id, status, claimant_key from ad_coupon_code where tenant_id = "
                  + TENANT
                  + " and coupon_id = ? and code = ? for update",
              couponId,
              text(request.code()));
      if (code == null) throw notFound("券码");
      if (!"CLAIMED".equals(code.get("status", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "券码状态是 " + code.get("status", String.class) + "，只有已领取未核销的码能核销");
      }
      codeId = code.get("id", UUID.class);
    } else {
      Record code =
          sql.fetchOne(
              "select id from ad_coupon_code where tenant_id = "
                  + TENANT
                  + " and coupon_id = ? and status = 'CLAIMED'"
                  + " order by claimed_at desc, id desc limit 1 for update",
              couponId);
      if (code == null) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "这张券没有已领取未核销的券码，无法核销");
      }
      codeId = code.get("id", UUID.class);
    }

    String type = locked.get("coupon_type", String.class);
    BigDecimal orderAmount = request.orderAmount();
    BigDecimal discountRate = locked.get("discount_rate", BigDecimal.class);
    BigDecimal discountValue = locked.get("discount_value", BigDecimal.class);
    BigDecimal threshold = locked.get("threshold_amount", BigDecimal.class);
    BigDecimal cap = locked.get("max_discount_amount", BigDecimal.class);

    // 🔴 满减门槛：不满足订单金额就不许核销（门槛是建券时登记的快照）
    if (threshold != null) {
      if (orderAmount == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "满减券核销必须提供订单金额（用来判门槛）");
      }
      if (orderAmount.compareTo(threshold) < 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "订单金额未达到满减门槛 "
                + threshold.stripTrailingZeros().toPlainString()
                + "，不能核销");
      }
    }

    // 🔴 让利额与补贴成本（券的快照口径）
    BigDecimal redeemedAmount = null;
    BigDecimal subsidyCost = null;
    if (discountRate != null) {
      if (orderAmount == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "折扣率券核销必须提供订单金额（用来算让利额）");
      }
      redeemedAmount =
          orderAmount.multiply(discountRate).setScale(4, RoundingMode.HALF_UP);
      subsidyCost = redeemedAmount;
    } else if (discountValue != null) {
      redeemedAmount = discountValue;
      subsidyCost = discountValue;
    }
    // 买赠券：没有金额口径 ⇒ redeemedAmount / subsidyCost 都不可测算（null，不是 0）
    if (cap != null && redeemedAmount != null && redeemedAmount.compareTo(cap) > 0) {
      redeemedAmount = cap;
      subsidyCost = cap;
    }

    UUID redemptionId = Ids.next();
    try {
      sql.execute(
          "insert into ad_coupon_redemption (tenant_id,id,coupon_id,code_id,order_ref,"
              + " order_amount,redeemed_amount,subsidy_cost,currency,redeemed_at)"
              + " values (?,?,?,?,?,?,?,?,?,now())",
          currentTenant(),
          redemptionId,
          couponId,
          codeId,
          orderRef,
          orderAmount,
          redeemedAmount,
          subsidyCost,
          locked.get("currency", String.class));
    } catch (DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "核销冲突：这张券在订单 " + orderRef + " 上已经核销过了");
    }

    sql.execute(
        "update ad_coupon_code set status = 'USED', used_at = now(), order_ref = ?,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        orderRef,
        codeId);

    sql.execute(
        "update ad_coupon set redeemed_count = redeemed_count + 1, version = version + 1,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        couponId);

    return coupon(couponId.toString());
  }

  // ==================================================================
  // 核销统计（真聚合）
  // ==================================================================

  /** 核销统计（按券 id 的字符串形态，非法 id 走 400 而不是 500）。 */
  public AdCouponStatistics statistics(String id) {
    return statistics(parseId(id));
  }

  /**
   * 核销统计（真聚合）。
   *
   * <p>🔴 补贴成本：{@code sum(subsidy_cost)} 会忽略 null（买赠那类不可测算的），
   * 所以另外用 {@code count(*) filter (where subsidy_cost is null)} 数出
   * **不可测算的笔数** —— 界面上要分开显示，不能让「没算出来」看起来像「成本是 0」。
   */
  public AdCouponStatistics statistics(UUID couponId) {
    Record totals =
        sql.fetchOne(
            "select (select count(*) from ad_coupon_code where tenant_id = "
                + TENANT
                + " and coupon_id = ?) generated_count,"
                + " (select count(*) from ad_coupon_claim where tenant_id = "
                + TENANT
                + " and coupon_id = ?) claimed_count,"
                + " (select count(*) from ad_coupon_redemption where tenant_id = "
                + TENANT
                + " and coupon_id = ?) redeemed_count,"
                + " (select coalesce(sum(redeemed_amount),0) from ad_coupon_redemption"
                + "  where tenant_id = "
                + TENANT
                + " and coupon_id = ?) total_redeemed,"
                + " (select count(*) from ad_coupon_redemption where tenant_id = "
                + TENANT
                + " and coupon_id = ? and subsidy_cost is null) unmeasurable,"
                + " (select sum(subsidy_cost) from ad_coupon_redemption where tenant_id = "
                + TENANT
                + " and coupon_id = ?) subsidy_total",
            couponId,
            couponId,
            couponId,
            couponId,
            couponId,
            couponId);

    long generated = totals == null ? 0 : totals.get("generated_count", Long.class);
    long claimed = totals == null ? 0 : totals.get("claimed_count", Long.class);
    long redeemed = totals == null ? 0 : totals.get("redeemed_count", Long.class);
    BigDecimal totalRedeemed =
        totals == null ? BigDecimal.ZERO : totals.get("total_redeemed", BigDecimal.class);
    BigDecimal subsidyTotal =
        totals == null ? null : totals.get("subsidy_total", BigDecimal.class);
    long unmeasurable = totals == null ? 0 : totals.get("unmeasurable", Long.class);

    // 🔴 核销率：没有领取时**不可比**（null），不是 0
    BigDecimal rate =
        claimed == 0
            ? null
            : BigDecimal.valueOf(redeemed)
                .divide(BigDecimal.valueOf(claimed), 6, RoundingMode.HALF_UP);

    List<AdCouponChannelStat> breakdown =
        sql.fetch(
                "select cl.channel_type, coalesce(ch.placement,'') placement,"
                    + " count(distinct cl.id) claim_count,"
                    + " count(distinct rd.id) redemption_count,"
                    + " sum(rd.subsidy_cost) subsidy_cost"
                    + " from ad_coupon_claim cl"
                    + " left join ad_coupon_channel ch on ch.tenant_id = cl.tenant_id"
                    + "  and ch.id = cl.channel_id"
                    + " left join ad_coupon_redemption rd on rd.tenant_id = cl.tenant_id"
                    + "  and rd.code_id = cl.code_id"
                    + " where cl.tenant_id = "
                    + TENANT
                    + " and cl.coupon_id = ?"
                    + " group by cl.channel_type, coalesce(ch.placement,'')"
                    + " order by claim_count desc, cl.channel_type",
                couponId)
            .stream()
            .map(
                r ->
                    new AdCouponChannelStat(
                        r.get("channel_type", String.class),
                        r.get("placement", String.class),
                        r.get("claim_count", Long.class),
                        r.get("redemption_count", Long.class),
                        r.get("subsidy_cost", BigDecimal.class)))
            .toList();

    List<AdCouponDailyStat> daily =
        sql.fetch(
                "select (rd.redeemed_at at time zone 'UTC')::date d, count(*) cnt,"
                    + " sum(rd.subsidy_cost) subsidy_cost"
                    + " from ad_coupon_redemption rd"
                    + " where rd.tenant_id = "
                    + TENANT
                    + " and rd.coupon_id = ?"
                    + " group by (rd.redeemed_at at time zone 'UTC')::date"
                    + " order by d",
                couponId)
            .stream()
            .map(
                r ->
                    new AdCouponDailyStat(
                        r.get("d", LocalDate.class),
                        r.get("cnt", Long.class),
                        r.get("subsidy_cost", BigDecimal.class)))
            .toList();

    return new AdCouponStatistics(
        generated,
        claimed,
        redeemed,
        rate,
        subsidyTotal,
        totalRedeemed,
        unmeasurable,
        breakdown,
        daily,
        SUBSIDY_NOTE);
  }

  // ==================================================================
  // 投放渠道与投放计划
  // ==================================================================

  /** 投放渠道（按券 id 的字符串形态，非法 id 走 400 而不是 500）。 */
  public List<AdCouponChannelRow> channels(String id) {
    return channels(parseId(id));
  }

  public List<AdCouponChannelRow> channels(UUID couponId) {
    return sql.fetch(
            "select ch.id, ch.channel_type, ch.placement, ch.plan_start_at, ch.plan_end_at,"
                + " ch.planned_reach, ch.status, ch.note, ch.created_at,"
                + " (select count(*) from ad_coupon_claim cl where cl.tenant_id = ch.tenant_id"
                + "  and cl.channel_id = ch.id) claim_count,"
                + " (select count(*) from ad_coupon_claim cl"
                + "  join ad_coupon_redemption rd on rd.tenant_id = cl.tenant_id"
                + "   and rd.code_id = cl.code_id"
                + "  where cl.tenant_id = ch.tenant_id and cl.channel_id = ch.id) redemption_count"
                + " from ad_coupon_channel ch"
                + " where ch.tenant_id = "
                + TENANT
                + " and ch.coupon_id = ?"
                + " order by ch.created_at, ch.id",
            couponId)
        .stream()
        .map(
            r ->
                new AdCouponChannelRow(
                    r.get("id", UUID.class),
                    r.get("channel_type", String.class),
                    r.get("placement", String.class),
                    r.get("plan_start_at", OffsetDateTime.class),
                    r.get("plan_end_at", OffsetDateTime.class),
                    r.get("planned_reach", BigDecimal.class),
                    r.get("claim_count", Long.class),
                    r.get("redemption_count", Long.class),
                    r.get("status", String.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  /** 登记一条投放渠道与投放计划。 */
  public AdCouponChannelRow saveChannel(String id, SaveCouponChannelRequest request) {
    UUID couponId = parseId(id);
    requireCoupon(couponId);

    String channelType = text(request.channelType()).toUpperCase(Locale.ROOT);
    if (!CHANNEL_TYPES.contains(channelType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道类型取值不合法");
    }
    String placement = text(request.placement());
    if (placement.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放位必填");
    }
    if (request.planStartAt() != null
        && request.planEndAt() != null
        && !request.planEndAt().isAfter(request.planStartAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放结束时间必须晚于开始时间");
    }
    String status =
        blankToNull(request.status()) == null
            ? "PLANNED"
            : text(request.status()).toUpperCase(Locale.ROOT);
    if (!List.of("PLANNED", "RUNNING", "PAUSED", "FINISHED", "CANCELLED").contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放状态取值不合法");
    }

    UUID channelId = Ids.next();
    try {
      sql.execute(
          "insert into ad_coupon_channel (tenant_id,id,coupon_id,channel_type,placement,"
              + " plan_start_at,plan_end_at,planned_reach,status,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,now(),now())",
          currentTenant(),
          channelId,
          couponId,
          channelType,
          placement,
          request.planStartAt(),
          request.planEndAt(),
          request.plannedReach(),
          status,
          text(request.note()));
    } catch (DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "同一张券的同一个渠道上已经登记过这个投放位了");
    }
    return channels(couponId).stream()
        .filter(c -> c.id().equals(channelId))
        .findFirst()
        .orElseThrow(() -> notFound("投放渠道"));
  }

  // ==================================================================
  // 内部：最近领取 / 最近核销 / 校验 / 行锁
  // ==================================================================

  private List<AdCouponClaimRow> recentClaims(UUID couponId, int limit) {
    return sql.fetch(
            "select cl.id, cl.claim_kind, cl.recipient_key, cl.channel_type, cl.quantity,"
                + " cl.claimed_at, cd.code"
                + " from ad_coupon_claim cl"
                + " join ad_coupon_code cd on cd.tenant_id = cl.tenant_id and cd.id = cl.code_id"
                + " where cl.tenant_id = "
                + TENANT
                + " and cl.coupon_id = ?"
                + " order by cl.claimed_at desc, cl.id desc limit ?",
            couponId,
            limit)
        .stream()
        .map(
            r ->
                new AdCouponClaimRow(
                    r.get("id", UUID.class),
                    r.get("claim_kind", String.class),
                    r.get("recipient_key", String.class),
                    r.get("channel_type", String.class),
                    r.get("code", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("claimed_at", OffsetDateTime.class)))
        .toList();
  }

  private List<AdCouponRedemptionRow> recentRedemptions(UUID couponId, int limit) {
    return sql.fetch(
            "select rd.id, rd.order_ref, cd.code, rd.order_amount, rd.redeemed_amount,"
                + " rd.subsidy_cost, rd.currency, rd.redeemed_at"
                + " from ad_coupon_redemption rd"
                + " join ad_coupon_code cd on cd.tenant_id = rd.tenant_id and cd.id = rd.code_id"
                + " where rd.tenant_id = "
                + TENANT
                + " and rd.coupon_id = ?"
                + " order by rd.redeemed_at desc, rd.id desc limit ?",
            couponId,
            limit)
        .stream()
        .map(
            r ->
                new AdCouponRedemptionRow(
                    r.get("id", UUID.class),
                    r.get("order_ref", String.class),
                    r.get("code", String.class),
                    r.get("order_amount", BigDecimal.class),
                    r.get("redeemed_amount", BigDecimal.class),
                    r.get("subsidy_cost", BigDecimal.class),
                    r.get("currency", String.class),
                    r.get("redeemed_at", OffsetDateTime.class)))
        .toList();
  }

  /** 取一行券（不存在或跨租户都是 404）。 */
  private void requireCoupon(UUID couponId) {
    Long exists =
        count(
            "select count(*) from ad_coupon where tenant_id = " + TENANT + " and id = ?",
            couponId);
    if (exists == 0) throw notFound("优惠券");
  }

  /**
   * 🔴 取一行券并**加行锁**（{@code for update}）。
   *
   * <p>库存扣减与限领判据必须在这个锁里做 ⇒ 并发的两次领取不会都通过。
   * 用懒加载 / 先查后改会漏掉竞态（AGENTS.md 明确禁止）。
   */
  private Record lockedCoupon(UUID couponId) {
    return sql.fetchOne(
        "select id, status, coupon_type, total_stock, generated_count, claimed_count,"
            + " redeemed_count, per_user_limit, discount_rate, discount_value,"
            + " threshold_amount, max_discount_amount, currency, code_prefix, code_length,"
            + " code_charset, start_at, end_at, platform_sync_status, pending_platform_writeback"
            + " from ad_coupon where tenant_id = "
            + TENANT
            + " and id = ? for update",
        couponId);
  }

  /** 校验并归一化一张券的规则。 */
  private record Validated(
      String code,
      String name,
      String type,
      String shopCode,
      UUID promotionId,
      BigDecimal discountRate,
      BigDecimal discountValue,
      BigDecimal thresholdAmount,
      BigDecimal maxDiscountAmount,
      BigDecimal buyQuantity,
      BigDecimal giftQuantity,
      String currency,
      BigDecimal totalStock,
      BigDecimal perUserLimit,
      String codePrefix,
      int codeLength,
      String codeCharset) {}

  private Validated validate(SaveCouponRequest request, UUID existingId) {
    String code = text(request.couponCode());
    String name = text(request.couponName());
    String type = text(request.couponType()).toUpperCase(Locale.ROOT);
    String shopCode = text(request.shopCode());
    if (code.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券编码必填");
    if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券名称必填");
    if (!COUPON_TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券类型取值不合法");
    }
    if (shopCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参与店铺必填");
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    if (request.startAt() != null
        && request.endAt() != null
        && !request.endAt().isAfter(request.startAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效时间必须晚于生效时间");
    }

    BigDecimal totalStock =
        request.totalStock() == null ? BigDecimal.ZERO : request.totalStock();
    if (totalStock.compareTo(BigDecimal.ZERO) < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存不能是负数");
    }

    // 🔴 折扣口径三选一（与服务端校验同口径，DB CHECK 再兜一层）
    BigDecimal rate = request.discountRate();
    BigDecimal value = request.discountValue();
    BigDecimal threshold = request.thresholdAmount();
    BigDecimal cap = request.maxDiscountAmount();
    BigDecimal buy = request.buyQuantity();
    BigDecimal gift = request.giftQuantity();

    if (type.equals("DISCOUNT_CODE")) {
      boolean hasRate = rate != null;
      boolean hasValue = value != null;
      if (hasRate == hasValue) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "折扣码必须给折扣率**或**固定面额其中一个（两个都填让利额会有两个答案）");
      }
      if (threshold != null || buy != null || gift != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "折扣码不能给满减门槛或买赠数量");
      }
    } else if (type.equals("FULL_REDUCTION")) {
      if (value == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "满减券必须给减免额");
      }
      if (threshold == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "满减券必须给门槛（适用订单最低金额）");
      }
      if (rate != null || buy != null || gift != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "满减券不能给折扣率或买赠数量");
      }
    } else {
      if (buy == null || gift == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "买赠券必须给买 N 与送 M");
      }
      if (rate != null || value != null || threshold != null || cap != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "买赠券没有金额口径，不能给折扣率 / 面额 / 门槛 / 封顶");
      }
    }

    String codePrefix = text(request.codePrefix());
    int codeLength = request.codeLength() == null ? 8 : request.codeLength();
    if (codeLength < 4 || codeLength > 32) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券码随机段长度必须在 4-32 之间");
    }
    String charset =
        blankToNull(request.codeCharset()) == null ? DEFAULT_CHARSET : text(request.codeCharset());
    if (charset.length() < 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券码字符集至少要有 2 个字符");
    }

    return new Validated(
        code,
        name,
        type,
        shopCode,
        request.promotionId(),
        rate,
        value,
        threshold,
        cap,
        buy,
        gift,
        currency,
        totalStock,
        request.perUserLimit(),
        codePrefix,
        codeLength,
        charset);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }
}
