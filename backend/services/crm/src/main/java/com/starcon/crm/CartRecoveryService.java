package com.starcon.crm;

import static com.starcon.crm.CartRecoveryModels.*;

import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentDetail;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentInput;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentPage;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentRow;
import com.starcon.crm.CartRecoveryModels.CrmCartCapabilities;
import com.starcon.crm.CartRecoveryModels.CrmCartCapabilityDimension;
import com.starcon.crm.CartRecoveryModels.CrmCartMessageInput;
import com.starcon.crm.CartRecoveryModels.CrmCartMessagePage;
import com.starcon.crm.CartRecoveryModels.CrmCartMessageRow;
import com.starcon.crm.CartRecoveryModels.CrmCartPolicy;
import com.starcon.crm.CartRecoveryModels.CrmCartPolicyInput;
import com.starcon.crm.CartRecoveryModels.CrmCartRecoveryInput;
import com.starcon.crm.CartRecoveryModels.CrmCartRecoveryRate;
import com.starcon.crm.CartRecoveryModels.CrmCartRecoveryRateBucket;
import com.starcon.crm.CartRecoveryModels.CrmCartShopProfileInput;
import com.starcon.crm.CartRecoveryModels.CrmCartShopView;
import com.starcon.crm.CartRecoveryModels.CrmCartSyncInput;
import com.starcon.crm.CartRecoveryModels.CrmCartSyncResult;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchChannelBucket;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchInput;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchResult;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchResultInput;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchRow;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribeInput;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribePage;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribeRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.6.2.2 购物车与弃单挽回：管理独立站购物车与弃单挽回。
 *
 * <p>蓝图 15 卷 15.6.2.2 原话：
 *
 * <blockquote>
 *
 * 职责：管理独立站购物车与弃单挽回：采集加购/结账未完成事件（Shopify 等提供 cart/checkout 事件），
 * 按超时策略触发挽回（邮件/短信/站内信），跟踪挽回率；平台订单不适用此项。
 * 输入：购物车/结账事件（2 渠道 Shopify 等）；挽回规则与话术；客户联系方式与退订状态。
 * 输出：弃单清单 + 挽回触达记录 + 挽回率报表。
 * 关系：上游 = 2 渠道（独立站事件）、15.5.2 触达；下游 = 9 订单（挽回成交）、13 分析。
 * <b>此项是独立站(DTC)特有能力</b>，平台订单无购物车概念；触达须遵守退订与频次上限，
 * 挽回话术需人工确认后启用。
 *
 * </blockquote>
 *
 * <p><b>三条加粗句在本服务落成三道闸</b>：
 *
 * <ol>
 *   <li><b>平台订单不适用此项</b> —— {@link #resolvePlatformType} 是<b>唯一判定点</b>：
 *       已知平台码（SHOPIFY 之外的全部平台）一律 MARKETPLACE，登记弃单一律 <b>409</b>
 *       且<b>不写任何数据</b>；未知平台码必须由租户显式声明，没声明也 409。</li>
 *   <li><b>触达须遵守退订与频次上限</b> —— {@link #registerTouch} 在<b>落库之前</b>查退订名单
 *       （硬拦）与频次上限（可配置 + 默认值）；越界时<b>明确 409 并写明原因</b>，
 *       不是静默跳过 —— 静默跳过会让运营以为发过了。</li>
 *   <li><b>挽回话术需人工确认后启用</b> —— {@link #registerTouch} 只接受 {@code status='ENABLED'}
 *       的话术；未审核 / 未启用一律 409（DB 触发器还有第二道）。</li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>：上游 2 渠道在 channel 服务，本服务<b>一条 SQL 都不碰对方的库</b>：
 * 店铺目录经 {@link SignoffDeliveredOrderPort} 走授权 HTTP API 读（{@code GET /channel/shops}）；
 * 平台购物车事件经 {@link CartEventPort} 接缝接（当前实现
 * {@link UnavailableCartEventPort} 对所有平台答「未接入」，同步端点 409 且<b>不写任何数据</b>）。
 * 下游 15.5.2.1 触达 / 9 订单 / 13 分析**未交付** ⇒ 本服务<b>只登记触达记录</b>，
 * 成交订单号由运营人工登记，<b>不假装</b>已经发出邮件 / 短信、<b>不编造</b>下游 id。
 */
@Service
public class CartRecoveryService {

  // ================================================================ 常量

  /**
   * 平台类型判定表（<b>唯一判定点</b>）。
   *
   * <p>🔴 这张表是「平台订单无购物车概念」这条红线的**系统保证**：本仓的平台目录
   * （channel 的 platform 表）里**没有**「独立站 / 平台店铺」这一列 ——
   * {@code operating_mode} 是经营模式（自发货 / 全托管 …），不是店铺形态。
   * 而「Amazon / eBay / TikTok Shop 这些是平台店铺」是**事实**，不该靠运营自觉。
   *
   * <p>取值与 channel 的 platform 种子目录（V1）**逐个对齐**：
   * 12 个平台里只有 SHOPIFY 是独立站建站平台，其余 11 个都是第三方平台市场。
   *
   * <p>🔴 <b>不在表里的平台码</b>（自建站 / 私有部署的建站系统）由租户在
   * {@code crm_cart_shop_profile} <b>显式声明</b>；没声明就登记弃单 ⇒ 409。
   * 也就是说：<b>未知 ≠ 独立站</b>，默认不当作独立站 —— 误判成独立站会让平台订单混进
   * 弃单清单，把挽回率报表整体带歪。
   */
  static final Map<String, String> PLATFORM_KINDS =
      Map.ofEntries(
          Map.entry("AMAZON", "MARKETPLACE"),
          Map.entry("EBAY", "MARKETPLACE"),
          Map.entry("TIKTOK_SHOP", "MARKETPLACE"),
          Map.entry("TEMU", "MARKETPLACE"),
          Map.entry("WALMART", "MARKETPLACE"),
          Map.entry("SHOPEE", "MARKETPLACE"),
          Map.entry("LAZADA", "MARKETPLACE"),
          Map.entry("MERCADO_LIBRE", "MARKETPLACE"),
          Map.entry("ALIEXPRESS", "MARKETPLACE"),
          Map.entry("ETSY", "MARKETPLACE"),
          Map.entry("WAYFAIR", "MARKETPLACE"),
          Map.entry("SHOPIFY", "DTC"));

  /** 平台类型词表。 */
  static final List<String> PLATFORM_TYPES = List.of("DTC", "MARKETPLACE");

  /** 弃单阶段。 */
  static final List<String> STAGES = List.of("CART", "CHECKOUT");

  /** 弃单状态。 */
  static final List<String> ABANDONMENT_STATUSES = List.of("ABANDONED", "RECOVERED", "EXPIRED");

  /** 话术状态机的四档。 */
  static final List<String> MESSAGE_STATUSES = List.of("DRAFT", "APPROVED", "ENABLED", "DISABLED");

  /** 触达通道（= 退订通道，同一套词表）。 */
  static final List<String> CHANNELS = List.of("EMAIL", "SMS", "IN_APP");

  /** 触达记录状态。 */
  static final List<String> TOUCH_STATUSES = List.of("REGISTERED", "SENT", "FAILED");

  /** 触达通道（transport）。只允许人工交接。 */
  static final List<String> TRANSPORTS = List.of("MANUAL_HANDOFF");

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 频次上限与超时的默认值（没有策略行时用这一组）。 */
  static final int DEFAULT_MAX_TOUCHES_PER_DAY = 1;

  static final int DEFAULT_MAX_TOUCHES_PER_CYCLE = 3;
  static final int DEFAULT_CYCLE_DAYS = 30;
  static final int DEFAULT_ABANDON_TIMEOUT_HOURS = 24;

  /** 平台类型判定结果（类型 + 来源）。 */
  record PlatformType(String type, String source) {}

  /** 频次上限与超时策略（行缺省时用默认值）。 */
  record Policy(
      int maxTouchesPerDay,
      int maxTouchesPerCycle,
      int cycleDays,
      int abandonTimeoutHours,
      boolean customized) {}

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort shops;
  private final CartEventPort cartEvents;

  public CartRecoveryService(
      DSLContext sql, SignoffDeliveredOrderPort shops, CartEventPort cartEvents) {
    this.sql = sql;
    this.shops = shops;
    this.cartEvents = cartEvents;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 四维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code cart_event_sync} —— 平台购物车 / 结账事件适配器**未交付**（channel/vendor 只有 OAuth 适配器）。</li>
   *   <li>{@code message_delivery} —— 下游 15.5.2.1 触达**未交付** ⇒ 只登记触达记录，<b>不假装已发出</b>。</li>
   *   <li>{@code recovered_order_link} —— 9 订单**未交付** ⇒ 成交订单号人工登记，<b>不编造下游 id</b>。</li>
   *   <li>{@code buyer_profile} —— 15.3.1.1 客户档案**未交付** ⇒ 买家标识与联系方式由运营提供。</li>
   * </ul>
   */
  public CrmCartCapabilities capabilities() {
    List<CrmCartCapabilityDimension> dimensions =
        List.of(
            new CrmCartCapabilityDimension(
                "shop_directory",
                "店铺目录（弃单按店铺归属）",
                true,
                "上游 channel V1 shop，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "弃单的店铺从**真实清单**里选，不是前端写死的常量；"
                    + "店铺清单读不到时端点 503，**绝不**返回空集合冒充「没有店铺」"),
            new CrmCartCapabilityDimension(
                "platform_type_classification",
                "🔴 店铺的平台类型（独立站 / 平台店铺）",
                true,
                "本服务 V296 crm_cart_shop_profile + 平台判定表",
                "",
                "已知平台码由系统判定：Amazon / eBay / TikTok Shop / Temu / Walmart / Shopee / Lazada / "
                    + "Mercado Libre / AliExpress / Etsy / Wayfair 一律是**平台店铺**，**没有购物车概念**；"
                    + "SHOPIFY 是独立站。未知平台码必须由租户显式声明 —— 未知**不等于**独立站"),
            new CrmCartCapabilityDimension(
                "abandonment_registration",
                "弃单登记入口（人工登记加购 / 结账未完成）",
                true,
                "本服务 V296 crm_cart_abandonment",
                "",
                "登记的是**独立站**的弃单；平台店铺的登记一律 409 且不写数据 —— "
                    + "平台订单混进来会让挽回率报表整体失真"),
            new CrmCartCapabilityDimension(
                "cart_event_sync",
                "🔴 平台购物车 / 结账事件同步（自动把平台上的弃单拉进来）",
                false,
                "channel/vendor 目前只有 19 个 OAuth 适配器，**没有任何平台的购物车事件适配器**",
                "2",
                "同步端点明确 **409** 且**不写任何数据**；弃单只能**人工登记**。"
                    + "一个「返回空列表」的假同步会让挽回率报表的分母凭空少掉一整批弃单"),
            new CrmCartCapabilityDimension(
                "recovery_message_lifecycle",
                "挽回话术的生命周期（草稿 → 已审核 → 已启用）",
                true,
                "本服务 V296 crm_cart_recovery_message",
                "",
                "**只有「已启用」的话术能被触达流程选用**（服务端 + DB 触发器两道）；"
                    + "改了内容自动回到草稿并把版本 +1 —— 否则会出现「审核的是 A 版、发出去的是 B 版」；"
                    + "审核留痕记谁、何时、哪个版本"),
            new CrmCartCapabilityDimension(
                "unsubscribe_enforcement",
                "🔴 退订硬拦",
                true,
                "本服务 V296 crm_cart_unsubscribe + guard_crm_cart_recovery_touch_unsubscribe",
                "",
                "已退订的联系人**一律不触达**，而且是**发出去之前查**；"
                    + "「先发出去、报表里再过滤」不成立 —— 已经发出去的邮件收不回来"),
            new CrmCartCapabilityDimension(
                "frequency_cap",
                "🔴 触达频次上限",
                true,
                "本服务 V296 crm_cart_recovery_policy + guard_crm_cart_recovery_touch_frequency",
                "",
                "可配置、有默认值（每日 1 次 / 周期 3 次 / 周期 30 天），DB 层另有天花板；"
                    + "越界**明确失败并写明原因**，不是静默跳过 —— 静默跳过会让运营以为发过了"),
            new CrmCartCapabilityDimension(
                "touch_record",
                "挽回触达记录（运输方式 / 渠道 / 时间 / 结果）",
                true,
                "本服务 V296 crm_cart_recovery_touch",
                "",
                "登记即落库，状态恒为「已登记待发」；发出与否由运营在通道侧完成后回填结果 —— "
                    + "本服务**不假装**已经发出邮件 / 短信"),
            new CrmCartCapabilityDimension(
                "recovered_order_link",
                "🔴 挽回成交与 9 订单的自动关联",
                false,
                "9 订单**未交付**",
                "9",
                "成交订单号由运营**人工登记**；本模块**不编造**下游订单 id，也不假装已生成订单"),
            new CrmCartCapabilityDimension(
                "buyer_profile",
                "🔴 买家主数据（客户档案）",
                false,
                "15.3.1.1 客户列表**未交付**",
                "15.3.1.1",
                "买家标识与联系方式由运营按店铺提供，**只作去重键与触达对象**，不是买家主数据"),
            new CrmCartCapabilityDimension(
                "recovery_analytics",
                "🔴 挽回率进 13 分析平台",
                false,
                "13 分析**未交付**",
                "13",
                "本模块**自己出**挽回率报表（服务端真算）；不假装已把指标推进分析平台"));
    return new CrmCartCapabilities(
        dimensions,
        PLATFORM_TYPES,
        STAGES,
        ABANDONMENT_STATUSES,
        MESSAGE_STATUSES,
        CHANNELS,
        TOUCH_STATUSES,
        TRANSPORTS,
        platformTypeRule(),
        messageLifecycle(),
        "上游 2 渠道在 **channel** 服务，本服务一条 SQL 都不碰对方的库："
            + "店铺目录经授权 HTTP API 读（GET /channel/shops），"
            + "平台购物车事件经 CartEventPort 接缝接（当前对所有平台答「未接入」）。"
            + "下游 15.5.2.1 触达 / 9 订单 / 13 分析未交付 ⇒ 本模块只做**触达记录**与"
            + "**人工登记的成交**与**本模块自己的挽回率报表**。",
        "🔴 平台订单无购物车概念：平台店铺的购物车 / 结账事件一律拒绝，不进弃单清单；"
            + "🔴 触达须遵守退订与频次上限（发出去之前查，越界明确失败）；"
            + "🔴 挽回话术需人工确认后启用（未启用的话术选不出来）。");
  }

  /** 平台类型判定规则（页面与运营都按这一条对齐）。 */
  public static String platformTypeRule() {
    return "已知平台码由系统判定，租户**不可改**：Amazon / eBay / TikTok Shop / Temu / Walmart / "
        + "Shopee / Lazada / Mercado Libre / AliExpress / Etsy / Wayfair ⇒ 平台店铺（**没有购物车概念**，"
        + "登记弃单一律 409）；SHOPIFY ⇒ 独立站。未知平台码（自建站 / 私有部署的建站系统）"
        + "必须由租户在「店铺平台类型」里**显式声明** —— 未知**不等于**独立站。";
  }

  /** 话术生命周期（页面上照着画，不靠猜）。 */
  public static String messageLifecycle() {
    return "草稿（DRAFT）→ 已审核（APPROVED）→ 已启用（ENABLED）→ 已停用（DISABLED）。"
        + "**只有「已启用」的话术能被触达流程选用**；改了内容自动回到草稿、版本 +1；"
        + "审核留痕记谁、何时、哪个版本。";
  }

  // ================================================================ 频次上限与超时策略

  /** 读租户级频次上限 + 超时策略（没有行时给默认值，{@code customized=false}）。 */
  public CrmCartPolicy policy() {
    Policy policy = currentPolicy();
    return new CrmCartPolicy(
        policy.maxTouchesPerDay(),
        policy.maxTouchesPerCycle(),
        policy.cycleDays(),
        policy.abandonTimeoutHours(),
        "",
        policy.customized(),
        policyUpdatedAt());
  }

  /**
   * 改租户级频次上限 + 超时策略（幂等替换：一租户一行）。
   *
   * <p>🔴 上限的**天花板在 DB 层**（CHECK）：「把上限调到 999」这条路本身被堵死 ——
   * 没有天花板的频次上限等于没有上限。
   */
  @Transactional
  public CrmCartPolicy updatePolicy(CrmCartPolicyInput input) {
    int perDay = requireRange(input.maxTouchesPerDay(), 1, 10, "每日触达上限");
    int perCycle = requireRange(input.maxTouchesPerCycle(), 1, 20, "每周期触达上限");
    int cycleDays = requireRange(input.cycleDays(), 1, 365, "周期长度（天）");
    int timeoutHours = requireRange(input.abandonTimeoutHours(), 1, 720, "弃单超时（小时）");
    if (perCycle < perDay) {
      throw bad(
          "每周期上限（"
              + perCycle
              + "）不能小于每日上限（"
              + perDay
              + "）：否则周期上限这条线永远不会触发，配置就自相矛盾。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_cart_recovery_policy"
            + "(id,tenant_id,max_touches_per_day,max_touches_per_cycle,cycle_days,"
            + " abandon_timeout_hours,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id) do update set"
            + " max_touches_per_day=excluded.max_touches_per_day,"
            + " max_touches_per_cycle=excluded.max_touches_per_cycle,"
            + " cycle_days=excluded.cycle_days,"
            + " abandon_timeout_hours=excluded.abandon_timeout_hours,"
            + " note=excluded.note,updated_at=excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        perDay,
        perCycle,
        cycleDays,
        timeoutHours,
        nz(input.note()).trim(),
        moment,
        moment);
    return policy();
  }

  // ================================================================ 店铺目录与平台类型

  /**
   * 店铺目录（来自 channel 的真实清单）+ 每个店铺的平台类型 + 购物车事件接入状态。
   *
   * <p>上游读不到一律 503 —— **绝不返回空集合冒充「没有店铺」**。
   */
  public List<CrmCartShopView> shops(String bearer) {
    List<CrmCartShopView> out = new ArrayList<>();
    for (var shop : shops.shops(bearer)) {
      String platformCode = nz(shop.platformCode()).toUpperCase(Locale.ROOT);
      var availability = cartEvents.availability(platformCode);
      String known = PLATFORM_KINDS.get(platformCode);
      String declared = declaredPlatformType(shop.id());
      String type;
      String source;
      if (known != null) {
        type = known;
        source = "PLATFORM_CATALOG";
      } else if (declared != null) {
        type = declared;
        source = "TENANT_DECLARED";
      } else {
        type = "UNDECLARED";
        source = "NONE";
      }
      out.add(
          new CrmCartShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              platformCode,
              nz(shop.platformName()),
              nz(shop.status()),
              type,
              source,
              availability.available(),
              availability.reason()));
    }
    return out;
  }

  /**
   * 声明店铺的平台类型。
   *
   * <p>🔴 已知平台码**不可**声明成别的类型：Amazon / eBay / TikTok Shop 这些是平台店铺，
   * 让他们声明成独立站等于让平台订单混进弃单清单。未知平台码才由租户说了算。
   */
  @Transactional
  public CrmCartShopView declareShopType(
      UUID shopId, CrmCartShopProfileInput input, UUID actorId, String bearer) {
    SignoffDeliveredOrderPort.ShopView shop = requireShop(shopId, bearer);
    String platformCode = nz(shop.platformCode()).toUpperCase(Locale.ROOT);
    String declared = oneOf(PLATFORM_TYPES, input.platformType(), "平台类型");
    String known = PLATFORM_KINDS.get(platformCode);
    if (known != null && !known.equals(declared)) {
      throw conflict(
          "平台 "
              + platformCode
              + " 在系统里是「"
              + known
              + "」，不能声明成「"
              + declared
              + "」："
              + (known.equals("MARKETPLACE")
                  ? "平台店铺**没有购物车概念**，把它当独立站会让平台订单混进弃单清单、"
                      + "把挽回率报表整体带歪。"
                  : "独立站建站平台才有购物车，声明成平台店铺会让真正的弃单登不进来。"));
    }
    String type = known != null ? known : declared;

    var existing =
        sql.fetchOne(
            "select id, platform_code from crm_cart_shop_profile where tenant_id = ? and shop_id = ?",
            currentTenant(),
            shopId);
    OffsetDateTime moment = now();
    if (existing == null) {
      insertShopProfile(shop, platformCode, type, input, actorId, moment);
    } else if (!nz(existing.get("platform_code", String.class)).equals(platformCode)) {
      // 🔴 平台码变了（店铺换了平台）⇒ 平台类型必须**重新判定**，
      //   所以旧的声明行作废、重建一行 —— 留着旧声明就是让旧平台的结论套到新平台上。
      sql.execute(
          "delete from crm_cart_shop_profile where tenant_id = ? and id = ?",
          currentTenant(),
          existing.get("id", UUID.class));
      insertShopProfile(shop, platformCode, type, input, actorId, moment);
    } else {
      sql.execute(
          "update crm_cart_shop_profile set shop_name=?,platform_type=?,declared_by=?,note=?,"
              + " updated_at=?::timestamptz where tenant_id=? and id=?",
          nz(shop.name()),
          type,
          actorId,
          nz(input.note()).trim(),
          moment,
          currentTenant(),
          existing.get("id", UUID.class));
    }
    for (var view : shops(bearer)) {
      if (view.id().equals(shopId)) return view;
    }
    throw notFound("店铺不存在：" + shopId);
  }

  /** 写一行店铺平台类型档案。 */
  private void insertShopProfile(
      SignoffDeliveredOrderPort.ShopView shop,
      String platformCode,
      String type,
      CrmCartShopProfileInput input,
      UUID actorId,
      OffsetDateTime moment) {
    sql.execute(
        "insert into crm_cart_shop_profile"
            + "(id,tenant_id,shop_id,shop_name,platform_code,platform_type,declared_by,note,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        shop.id(),
        nz(shop.name()),
        platformCode,
        type,
        actorId,
        nz(input.note()).trim(),
        moment,
        moment);
  }

  // ================================================================ 弃单清单

  /**
   * 弃单清单：真分页 + 真筛选（店铺 / 状态 / 阶段 / 买家 / 购物车号 / 摘要 / 时间窗）。
   *
   * <p>🔴 清单里**只有独立站弃单**：表级 CHECK 把平台店铺挡在门外，服务层也拒绝登记。
   */
  public CrmCartAbandonmentPage abandonmentPage(
      UUID shopId,
      String status,
      String stage,
      String buyerRef,
      String cartRef,
      String keyword,
      String abandonedFrom,
      String abandonedTo,
      int page,
      int size) {
    OffsetDateTime from = optionalMoment(abandonedFrom, "弃单时刻起点");
    OffsetDateTime to = optionalMoment(abandonedTo, "弃单时刻终点");
    List<Object> binds = new ArrayList<>();
    String where =
        abandonmentWhere(shopId, status, stage, buyerRef, cartRef, keyword, from, to, binds);

    long total =
        sql.fetchOne("select count(*) from crm_cart_abandonment a" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCartAbandonmentRow> items =
        sql.fetch(
                abandonmentColumns() + " from crm_cart_abandonment a" + where
                    + " order by a.abandoned_at desc, a.id limit ? offset ?",
                pageBinds.toArray())
            .map(CartRecoveryService::abandonment);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCartAbandonmentPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条弃单详情 = 弃单本体 + 触达记录。 */
  public CrmCartAbandonmentDetail detail(UUID cartId) {
    CrmCartAbandonmentRow row = requireAbandonment(cartId);
    return new CrmCartAbandonmentDetail(row, touches(cartId));
  }

  /**
   * 登记一条弃单（人工登记入口）。
   *
   * <p>🔴 平台店铺的弃单**登不进来**：判定为平台店铺一律 <b>409</b> 且<b>不写任何数据</b>。
   */
  @Transactional
  public CrmCartAbandonmentRow create(
      CrmCartAbandonmentInput input, UUID actorId, String bearer) {
    SignoffDeliveredOrderPort.ShopView shop = requireShop(input.shopId(), bearer);
    String platformCode = nz(shop.platformCode()).toUpperCase(Locale.ROOT);
    PlatformType platformType = resolvePlatformType(platformCode, shop.id());
    if (!"DTC".equals(platformType.type())) {
      throw conflict(
          "店铺 "
              + nz(shop.name())
              + "（"
              + platformCode
              + "）是**平台店铺**，平台订单无购物车概念："
              + "弃单挽回是独立站(DTC)特有能力，平台店铺的购物车 / 结账事件一律不登记 —— "
              + "把它们混进弃单清单会让挽回率报表整体失真。");
    }
    String cartRef = nz(input.cartRef()).trim();
    if (!cartRef.isEmpty() && cartRefExists(cartRef)) {
      throw conflict(
          "购物车 "
              + cartRef
              + " 已经登记过了（弃单号 "
              + cartNoOf(cartRef)
              + "）：同一张购物车只登记一次，免得挽回率的分母被重复计算。");
    }
    OffsetDateTime abandonedAt = parseMoment(input.abandonedAt(), "弃单时刻");
    Policy policy = currentPolicy();
    OffsetDateTime deadline = abandonedAt.plusHours(policy.abandonTimeoutHours());
    String buyerRef = nz(input.buyerRef()).trim();
    if (buyerRef.isEmpty()) {
      throw bad("必须写明买家标识：它既是触达的去重键，也是频次上限的计数对象。");
    }
    String stage = oneOf(STAGES, input.stage(), "弃单阶段");
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    // ⚠️ 占位符布局（**顺序与列顺序严格一致**，改列时必须同步改这里）：
    //   17 个普通占位符（id..item_summary）→ abandoned_at / recover_deadline_at 带
    //   ::timestamptz → status → recovered_at（null 也要显式类型，否则 PG 报
    //   "could not determine data type of parameter"）→ 5 个普通占位符
    //   → created_at / updated_at 带 ::timestamptz。
    sql.execute(
        "insert into crm_cart_abandonment"
            + "(id,tenant_id,cart_no,shop_id,shop_name,platform_code,platform_type,cart_ref,"
            + " external_event_id,stage,buyer_ref,buyer_email,buyer_phone,currency,amount,item_count,"
            + " item_summary,abandoned_at,recover_deadline_at,status,recovered_at,recovered_amount,"
            + " recovered_order_no,source,created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
            + "?::timestamptz,?::timestamptz,?,?::timestamptz,?,?,?,?,?,"
            + "?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextCartNo(),
        shop.id(),
        nz(shop.name()),
        platformCode,
        platformType.type(),
        cartRef,
        nz(input.externalEventId()).trim(),
        stage,
        buyerRef,
        nz(input.buyerEmail()).trim(),
        nz(input.buyerPhone()).trim(),
        nz(input.currency()).trim().toUpperCase(Locale.ROOT),
        input.amount() == null ? BigDecimal.ZERO : input.amount(),
        input.itemCount() == null ? BigDecimal.ZERO : input.itemCount(),
        nz(input.itemSummary()).trim(),
        abandonedAt,
        deadline,
        "ABANDONED",
        null,
        BigDecimal.ZERO,
        "",
        "MANUAL_ENTRY",
        actorId,
        nz(input.note()).trim(),
        moment,
        moment);
    return requireAbandonment(id);
  }

  /**
   * 登记一次挽回触达。
   *
   * <p>🔴 四道闸，任何一道挡住都**明确 409 并写明原因**，而且**什么都没写**：
   *
   * <ol>
   *   <li><b>话术必须已启用</b> —— 未审核 / 未启用的话术不允许被触达流程选用。</li>
   *   <li><b>必须到了挽回时点</b> —— 「按超时策略触发挽回」：未到时点不允许触达。</li>
   *   <li><b>退订硬拦</b> —— 已退订的联系人一律不触达，<b>发出去之前查</b>。</li>
   *   <li><b>频次上限</b> —— 每日 / 每周期上限，越界明确失败（不是静默跳过）。</li>
   * </ol>
   */
  @Transactional
  public CrmCartTouchResult registerTouch(UUID cartId, CrmCartTouchInput input, UUID actorId) {
    CrmCartAbandonmentRow cart = requireAbandonment(cartId);
    CrmCartMessageRow message = requireMessage(input.messageId());
    if (!"ENABLED".equals(message.status())) {
      throw conflict(
          "挽回话术 "
              + message.messageCode()
              + " 当前是「"
              + message.status()
              + "」，不能被触达流程选用：挽回话术需人工确认后启用"
              + "（草稿 → 已审核 → 已启用）。");
    }
    String channel = message.channel();
    String recipient = recipientOf(cart, channel);
    if (recipient.isEmpty()) {
      throw bad(
          "这条弃单没有"
              + ("EMAIL".equals(channel) ? "邮箱" : "SMS".equals(channel) ? "手机号" : "站内信标识")
              + "，没法用「"
              + channel
              + "」通道触达：请先在弃单详情里补上联系方式，或换一条别的通道。");
    }
    OffsetDateTime moment = now();

    // 🔴 闸 ②：未到挽回时点不允许触达。
    if (moment.isBefore(OffsetDateTime.parse(cart.recoverDeadlineAt()))) {
      throw conflict(
          "还没到挽回时点：这条弃单的挽回时点是 "
              + cart.recoverDeadlineAt()
              + "（事件时刻 + 租户策略的超时小时数），"
              + "「按超时策略触发挽回」意味着过早触达会把还在犹豫的买家也打扰一遍。");
    }
    // 🔴 闸 ③：退订硬拦（发出去之前查）。
    var unsubscribe = findUnsubscribe(channel, recipient, cart.shopId());
    if (unsubscribe != null) {
      throw conflict(
          "收件人 "
              + recipient
              + " 已退订（"
              + unsubscribe.contactType()
              + "，"
              + nz(unsubscribe.reason())
              + "），一律不触达：退订是硬拦，发出去之前就要查。");
    }
    // 🔴 闸 ④：频次上限。
    Policy policy = currentPolicy();
    long dayCount = touchCountSince(cart.buyerRef(), moment.minusDays(1));
    if (dayCount >= policy.maxTouchesPerDay()) {
      throw conflict(
          "买家 "
              + cart.buyerRef()
              + " 在 24 小时内已被触达 "
              + dayCount
              + " 次，达到每日上限 "
              + policy.maxTouchesPerDay()
              + " 次：触达须遵守频次上限（可在频次策略里调整）。");
    }
    long cycleCount = touchCountSince(cart.buyerRef(), moment.minusDays(policy.cycleDays()));
    if (cycleCount >= policy.maxTouchesPerCycle()) {
      throw conflict(
          "买家 "
              + cart.buyerRef()
              + " 在 "
              + policy.cycleDays()
              + " 天周期内已被触达 "
              + cycleCount
              + " 次，达到周期上限 "
              + policy.maxTouchesPerCycle()
              + " 次：触达须遵守频次上限（可在频次策略里调整）。");
    }

    UUID id = UUID.randomUUID();
    // ⚠️ 占位符布局：13 个普通占位符（id..failure_reason）→ sent_at 带 cast
    //   → operator_id → created_at / updated_at 带 cast。
    sql.execute(
        "insert into crm_cart_recovery_touch"
            + "(id,tenant_id,cart_id,cart_no,message_id,message_code,channel,transport,status,"
            + " recipient,subject,body,failure_reason,sent_at,operator_id,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,"
            + "?::timestamptz,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        cartId,
        cart.cartNo(),
        message.id(),
        message.messageCode(),
        channel,
        "MANUAL_HANDOFF",
        "REGISTERED",
        recipient,
        message.subject(),
        message.body(),
        "",
        null,
        actorId,
        moment,
        moment);
    return new CrmCartTouchResult(
        touchById(id),
        "已登记触达（状态「已登记待发」）：15.5.2.1 触达通道未交付，"
            + "本服务**只留触达记录**，不假装已经发出邮件 / 短信 —— 请在通道侧发出后回来回填结果。");
  }

  /**
   * 回填一次触达的结果（人工交接之后）。
   *
   * <p>🔴 这是「运营在别处发出之后回来登记结果」，**不是**「让本服务去发」——
   * 15.5.2.1 触达未交付，本服务没有真实通道。
   */
  @Transactional
  public CrmCartTouchRow confirmTouch(UUID touchId, CrmCartTouchResultInput input) {
    CrmCartTouchRow touch = requireTouch(touchId);
    if (!"REGISTERED".equals(touch.status())) {
      throw conflict(
          "这条触达已经是「"
              + touch.status()
              + "」，不能重复回填结果：重复回填会让「挽回率」与「触达量」出现两个答案。");
    }
    String status = oneOf(List.of("SENT", "FAILED"), input.status(), "触达结果");
    String failureReason = nz(input.failureReason()).trim();
    if ("FAILED".equals(status) && failureReason.isEmpty()) {
      throw bad("登记失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
    }
    OffsetDateTime sentAt =
        nz(input.sentAt()).trim().isEmpty() ? now() : parseMoment(input.sentAt(), "发出时刻");
    sql.execute(
        "update crm_cart_recovery_touch set status=?,failure_reason=?,sent_at=?::timestamptz,"
            + " updated_at=?::timestamptz where tenant_id=? and id=?",
        status,
        failureReason,
        sentAt,
        now(),
        currentTenant(),
        touchId);
    return touchById(touchId);
  }

  /**
   * 登记一次挽回成交。
   *
   * <p>🔴 9 订单（挽回成交）**未交付** ⇒ 订单号由运营**人工登记**，
   * 本模块**不编造**下游订单 id。
   */
  @Transactional
  public CrmCartAbandonmentRow registerRecovery(UUID cartId, CrmCartRecoveryInput input) {
    CrmCartAbandonmentRow cart = requireAbandonment(cartId);
    if ("RECOVERED".equals(cart.status())) {
      throw conflict(
          "这条弃单已经登记过成交（订单号 "
              + cart.recoveredOrderNo()
              + "）：重复登记会让挽回率的分子出错。");
    }
    OffsetDateTime recoveredAt = parseMoment(input.recoveredAt(), "成交时刻");
    BigDecimal amount = input.recoveredAmount() == null ? cart.amount() : input.recoveredAmount();
    sql.execute(
        "update crm_cart_abandonment set status='RECOVERED',recovered_at=?::timestamptz,"
            + " recovered_amount=?,recovered_order_no=?,note=?,updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        recoveredAt,
        amount,
        nz(input.salesOrderNo()).trim(),
        nz(input.note()).trim().isEmpty() ? cart.note() : nz(input.note()).trim(),
        now(),
        currentTenant(),
        cartId);
    return requireAbandonment(cartId);
  }

  // ================================================================ 挽回话术（状态机）

  /** 话术列表：真分页 + 真筛选（状态 / 通道 / 关键字）。 */
  public CrmCartMessagePage messagePage(
      String status, String channel, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where t.tenant_id = ?");
    if (blankToNull(status) != null) {
      where.append(" and t.status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(channel) != null) {
      where.append(" and t.channel = ?");
      binds.add(channel.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and (strpos(lower(t.message_code), lower(?)) > 0"
          + " or strpos(lower(t.name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_cart_recovery_message t" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCartMessageRow> items =
        sql.fetch(
                messageColumns() + " from crm_cart_recovery_message t"
                    + where
                    + " order by t.message_code limit ? offset ?",
                pageBinds.toArray())
            .map(CartRecoveryService::message);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCartMessagePage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条话术详情。 */
  public CrmCartMessageRow message(UUID messageId) {
    return requireMessage(messageId);
  }

  /** 新建话术（**草稿**：新的话术一律从草稿起步，不能直接启用）。 */
  @Transactional
  public CrmCartMessageRow createMessage(CrmCartMessageInput input, UUID actorId) {
    String code = nz(input.messageCode()).trim();
    if (messageCodeExists(code)) {
      throw conflict(
          "话术编号 "
              + code
              + " 已存在：它是业务键，改它会让「这次挽回用的哪套话术」在历史里出现两个答案。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_cart_recovery_message"
            + "(id,tenant_id,message_code,name,channel,locale,subject,body,status,version,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        nz(input.name()).trim(),
        oneOf(CHANNELS, input.channel(), "触达通道"),
        messageLocale(input.locale()),
        nz(input.subject()).trim(),
        nz(input.body()),
        "DRAFT",
        1,
        actorId,
        nz(input.note()).trim(),
        moment,
        moment);
    return requireMessage(id);
  }

  /**
   * 改话术。
   *
   * <p>🔴 <b>改了内容就回到草稿</b>（由 DB 触发器统一负责）：版本 +1、审核与启用痕迹清空 ——
   * 否则会出现「审核的是 A 版、发出去的是 B 版」。只改备注不动内容时状态保持不变。
   */
  @Transactional
  public CrmCartMessageRow updateMessage(UUID messageId, CrmCartMessageInput input, UUID actorId) {
    CrmCartMessageRow existing = requireMessage(messageId);
    String code = nz(input.messageCode()).trim();
    if (!code.isEmpty() && !code.equals(existing.messageCode())) {
      throw bad(
          "话术编号建后不可改（当前 "
              + existing.messageCode()
              + "）：它是业务键，改了会让「这次挽回用的哪套话术」在历史里出现两个答案。");
    }
    sql.execute(
        "update crm_cart_recovery_message set name=?,channel=?,locale=?,subject=?,body=?,"
            + " note=?,updated_at=?::timestamptz where tenant_id=? and id=?",
        nz(input.name()).trim().isEmpty() ? existing.name() : nz(input.name()).trim(),
        oneOf(CHANNELS, input.channel(), "触达通道"),
        messageLocale(input.locale()),
        nz(input.subject()).trim(),
        nz(input.body()),
        nz(input.note()).trim(),
        now(),
        currentTenant(),
        messageId);
    return requireMessage(messageId);
  }

  /**
   * 审核话术（草稿 → 已审核）。
   *
   * <p>🔴 留痕：审核人 + 审核时刻 + **审核的版本**（由触发器校验 approved_version = version）。
   */
  @Transactional
  public CrmCartMessageRow approveMessage(UUID messageId, UUID actorId) {
    CrmCartMessageRow existing = requireMessage(messageId);
    if (!"DRAFT".equals(existing.status())) {
      throw conflict(
          "话术当前是「"
              + existing.status()
              + "」，只有「草稿」才能审核：话术的生命周期是 草稿 → 已审核 → 已启用 → 已停用。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_cart_recovery_message set status='APPROVED',approved_by=?,approved_at=?::timestamptz,"
            + " approved_version=?,updated_at=?::timestamptz where tenant_id=? and id=?",
        actorId,
        moment,
        existing.version(),
        moment,
        currentTenant(),
        messageId);
    return requireMessage(messageId);
  }

  /**
   * 启用话术（已审核 → 已启用）。
   *
   * <p>🔴 只有「已启用」的话术能被触达流程选用 —— 这是「挽回话术需人工确认后启用」的落点。
   */
  @Transactional
  public CrmCartMessageRow enableMessage(UUID messageId, UUID actorId) {
    CrmCartMessageRow existing = requireMessage(messageId);
    if (!"APPROVED".equals(existing.status())) {
      throw conflict(
          "话术当前是「"
              + existing.status()
              + "」，只有「已审核」才能启用：挽回话术需人工确认后启用，"
              + "不能跳过审核这一步。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_cart_recovery_message set status='ENABLED',enabled_by=?,enabled_at=?::timestamptz,"
            + " enabled_version=?,updated_at=?::timestamptz where tenant_id=? and id=?",
        actorId,
        moment,
        existing.version(),
        moment,
        currentTenant(),
        messageId);
    return requireMessage(messageId);
  }

  /** 停用话术（已启用 / 已审核 → 已停用）。 */
  @Transactional
  public CrmCartMessageRow disableMessage(UUID messageId, UUID actorId) {
    CrmCartMessageRow existing = requireMessage(messageId);
    if ("DRAFT".equals(existing.status()) || "DISABLED".equals(existing.status())) {
      throw conflict(
          "话术当前是「"
              + existing.status()
              + "」，没有可停用的状态：只有「已审核」或「已启用」才需要停用。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_cart_recovery_message set status='DISABLED',disabled_by=?,disabled_at=?::timestamptz,"
            + " updated_at=?::timestamptz where tenant_id=? and id=?",
        actorId,
        moment,
        moment,
        currentTenant(),
        messageId);
    return requireMessage(messageId);
  }

  // ================================================================ 退订名单

  /** 退订名单：真分页 + 真筛选（通道 / 联系人 / 店铺）。 */
  public CrmCartUnsubscribePage unsubscribePage(
      String contactType, String contactValue, UUID shopId, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where u.tenant_id = ?");
    if (blankToNull(contactType) != null) {
      where.append(" and u.contact_type = ?");
      binds.add(contactType.trim());
    }
    if (blankToNull(contactValue) != null) {
      where.append(" and lower(u.contact_value) = lower(?)");
      binds.add(contactValue.trim());
    }
    if (shopId != null) {
      where.append(" and (u.shop_id = ? or u.shop_id is null)");
      binds.add(shopId);
    }
    long total =
        sql.fetchOne("select count(*) from crm_cart_unsubscribe u" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCartUnsubscribeRow> items =
        sql.fetch(
                unsubscribeColumns() + " from crm_cart_unsubscribe u"
                    + where
                    + " order by u.unsubscribed_at desc, u.id limit ? offset ?",
                pageBinds.toArray())
            .map(CartRecoveryService::unsubscribe);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCartUnsubscribePage(items, safePage, safeSize, total, totalPages);
  }

  /**
   * 登记一条退订。
   *
   * <p>🔴 退订是<b>硬拦</b>：登记之后这个联系人**一律不触达**，
   * 而且是<b>发出去之前查</b>（不是只在报表里过滤）。
   */
  @Transactional
  public CrmCartUnsubscribeRow createUnsubscribe(
      CrmCartUnsubscribeInput input, UUID actorId) {
    String contactType = oneOf(CHANNELS, input.contactType(), "退订通道");
    String contactValue = nz(input.contactValue()).trim();
    if (contactValue.isEmpty()) {
      throw bad("必须写明退订的联系方式：它就是触达前要查的那个值。");
    }
    if (findUnsubscribe(contactType, contactValue, input.shopId()) != null) {
      throw conflict(
          "这个联系人（"
              + contactType
              + " / "
              + contactValue
              + "）已经退订过了：重复登记没有意义，反而会让退订名单看起来比实际更长。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_cart_unsubscribe"
            + "(id,tenant_id,contact_type,contact_value,shop_id,reason,unsubscribed_at,"
            + " created_by,note,created_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        id,
        currentTenant(),
        contactType,
        contactValue,
        input.shopId(),
        nz(input.reason()).trim(),
        moment,
        actorId,
        nz(input.note()).trim(),
        moment);
    return unsubscribeById(id);
  }

  /** 取消退订（删掉一条退订记录）。 */
  @Transactional
  public void deleteUnsubscribe(UUID unsubscribeId) {
    var row =
        sql.fetchOne(
            "select id from crm_cart_unsubscribe where tenant_id = ? and id = ?",
            currentTenant(),
            unsubscribeId);
    if (row == null) throw notFound("退订记录不存在：" + unsubscribeId);
    sql.execute(
        "delete from crm_cart_unsubscribe where tenant_id = ? and id = ?",
        currentTenant(),
        unsubscribeId);
  }

  // ================================================================ 挽回率报表

  /**
   * 挽回率报表（<b>服务端真算</b>，前端只原样展示）。
   *
   * <p>🔴 分子分母都只统计**独立站弃单**（表里根本没有平台店铺的弃单），
   * 所以这张表不会因为混进平台订单而失真。
   */
  public CrmCartRecoveryRate recoveryRate(UUID shopId, String abandonedFrom, String abandonedTo) {
    OffsetDateTime from = optionalMoment(abandonedFrom, "弃单时刻起点");
    OffsetDateTime to = optionalMoment(abandonedTo, "弃单时刻终点");
    List<Object> binds = new ArrayList<>();
    String where = abandonmentWhere(shopId, null, null, null, null, null, from, to, binds);

    Record overall =
        sql.fetchOne(
                "select count(*) as total,"
                    + " count(*) filter (where a.status='RECOVERED') as recovered,"
                    + " count(*) filter (where a.status='ABANDONED') as pending,"
                    + " count(*) filter (where a.status='EXPIRED') as expired,"
                    + " coalesce(sum(a.amount),0) as total_amount,"
                    + " coalesce(sum(a.recovered_amount),0) as recovered_amount,"
                    + " round(100.0 * count(*) filter (where a.status='RECOVERED')"
                    + "       / nullif(count(*),0), 2) as recovery_rate"
                    + " from crm_cart_abandonment a" + where,
                binds.toArray());

    List<CrmCartRecoveryRateBucket> byShop =
        sql.fetch(
                "select a.shop_id,a.shop_name,count(*) as total,"
                    + " count(*) filter (where a.status='RECOVERED') as recovered,"
                    + " coalesce(sum(a.amount),0) as total_amount,"
                    + " coalesce(sum(a.recovered_amount),0) as recovered_amount,"
                    + " round(100.0 * count(*) filter (where a.status='RECOVERED')"
                    + "       / nullif(count(*),0), 2) as recovery_rate"
                    + " from crm_cart_abandonment a" + where
                    + " group by a.shop_id, a.shop_name order by count(*) desc, a.shop_name",
                binds.toArray())
            .map(CartRecoveryService::rateBucket);

    List<CrmCartTouchChannelBucket> byChannel =
        sql.fetch(
                "select channel, count(*) as touch_count from crm_cart_recovery_touch"
                    + " where tenant_id = ? group by channel order by channel",
                currentTenant())
            .map(
                r ->
                    new CrmCartTouchChannelBucket(
                        nz(r.get("channel", String.class)), nz(r.get("touch_count", Long.class))));

    long touchCount =
        sql.fetchOne(
                "select count(*) from crm_cart_recovery_touch where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    long unsubscribedCount =
        sql.fetchOne(
                "select count(*) from crm_cart_unsubscribe where tenant_id = ?", currentTenant())
            .get(0, Long.class);

    return new CrmCartRecoveryRate(
        nz(overall.get("total", Long.class)),
        nz(overall.get("recovered", Long.class)),
        nz(overall.get("pending", Long.class)),
        nz(overall.get("expired", Long.class)),
        overall.get("recovery_rate", BigDecimal.class),
        overall.get("total_amount", BigDecimal.class),
        overall.get("recovered_amount", BigDecimal.class),
        touchCount,
        unsubscribedCount,
        byShop,
        byChannel,
        "挽回率 = 已挽回弃单数 / 弃单总数（分母为 0 时不显示比率，不显示 0%）。"
            + "弃单只统计**独立站** —— 平台店铺的购物车事件在登记入口就被拒了，"
            + "所以这张表不会因为混进平台订单而失真。");
  }

  // ================================================================ 平台购物车事件同步

  /**
   * 平台购物车 / 结账事件同步。
   *
   * <p>🔴 当前**恒为 409** 且**不写任何数据**：平台购物车事件适配器未交付
   * （channel/vendor 只有 OAuth 适配器）。未接入是业务状态，不是故障 ——
   * 返回空集合会让运营以为这个店铺没有购物车，而真相是「我们拉不到」；
   * 更糟的是挽回率报表的**分母会凭空少掉一整批弃单**。
   */
  @Transactional
  public CrmCartSyncResult sync(CrmCartSyncInput input, String bearer) {
    SignoffDeliveredOrderPort.ShopView shop = requireShop(input.shopId(), bearer);
    String platformCode = nz(shop.platformCode()).toUpperCase(Locale.ROOT);
    PlatformType platformType = resolvePlatformType(platformCode, shop.id());
    if (!"DTC".equals(platformType.type())) {
      throw conflict(
          "店铺 "
              + nz(shop.name())
              + "（"
              + platformCode
              + "）是**平台店铺**，平台订单无购物车概念，没有可同步的购物车 / 结账事件。");
    }
    var availability = cartEvents.availability(platformCode);
    if (!availability.available()) {
      throw conflict(availability.reason());
    }
    var fetched = cartEvents.fetch(platformCode, shop.id(), bearer);
    if (!fetched.available()) {
      throw conflict(fetched.reason());
    }
    int synced = 0;
    for (var raw : fetched.events()) {
      insertSyncedCart(shop, raw);
      synced++;
    }
    return new CrmCartSyncResult(
        synced, true, "", "已同步 " + synced + " 条购物车 / 结账事件（按 cart_ref 幂等去重）。");
  }

  /** 落一条平台同步来的弃单（人工登记那条路不经过这里）。 */
  private void insertSyncedCart(
      SignoffDeliveredOrderPort.ShopView shop, CartEventPort.RawCartEvent raw) {
    String cartRef = nz(raw.cartRef()).trim();
    if (!cartRef.isEmpty() && cartRefExists(cartRef)) return;
    OffsetDateTime abandonedAt = raw.abandonedAt() == null ? now() : raw.abandonedAt();
    Policy policy = currentPolicy();
    OffsetDateTime deadline = abandonedAt.plusHours(policy.abandonTimeoutHours());
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    // ⚠️ 与 create() **同一套**占位符布局（17 普通 → 2 带 cast → status → recovered_at
    //   带 cast → 5 普通 → 2 带 cast）。
    sql.execute(
        "insert into crm_cart_abandonment"
            + "(id,tenant_id,cart_no,shop_id,shop_name,platform_code,platform_type,cart_ref,"
            + " external_event_id,stage,buyer_ref,buyer_email,buyer_phone,currency,amount,item_count,"
            + " item_summary,abandoned_at,recover_deadline_at,status,recovered_at,recovered_amount,"
            + " recovered_order_no,source,created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
            + "?::timestamptz,?::timestamptz,?,?::timestamptz,?,?,?,?,?,"
            + "?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, cart_ref) where cart_ref <> '' do nothing",
        id,
        currentTenant(),
        nextCartNo(),
        shop.id(),
        nz(shop.name()),
        nz(shop.platformCode()).toUpperCase(Locale.ROOT),
        "DTC",
        cartRef,
        nz(raw.externalEventId()).trim(),
        oneOf(STAGES, raw.stage(), "弃单阶段"),
        nz(raw.buyerRef()).trim(),
        nz(raw.buyerEmail()).trim(),
        nz(raw.buyerPhone()).trim(),
        nz(raw.currency()).trim().toUpperCase(Locale.ROOT),
        raw.amount() == null ? BigDecimal.ZERO : raw.amount(),
        raw.itemCount() == null ? BigDecimal.ZERO : raw.itemCount(),
        nz(raw.itemSummary()).trim(),
        abandonedAt,
        deadline,
        "ABANDONED",
        null,
        BigDecimal.ZERO,
        "",
        "PLATFORM_EVENT",
        null,
        "",
        moment,
        moment);
  }

  // ================================================================ 内部

  /**
   * 平台类型判定（<b>唯一判定点</b>）。
   *
   * <p>🔴 已知平台码由系统判定，租户不可改 —— 这就是「平台订单无购物车概念」那条红线的
   * 系统保证。未知平台码必须由租户显式声明，没声明 ⇒ 409。
   */
  private PlatformType resolvePlatformType(String platformCode, UUID shopId) {
    String known = PLATFORM_KINDS.get(platformCode);
    if (known != null) return new PlatformType(known, "PLATFORM_CATALOG");
    String declared = declaredPlatformType(shopId);
    if (declared == null) {
      throw conflict(
          "平台 "
              + platformCode
              + " 不在已知平台目录里，本模块无法判定它是独立站还是平台店铺："
              + "请先在「店铺平台类型」里声明它，声明之后才能登记弃单。"
              + "（未知**不等于**独立站 —— 误判成独立站会让平台订单混进弃单清单。）");
    }
    return new PlatformType(declared, "TENANT_DECLARED");
  }

  /** 租户对该店铺的显式声明（没有则 null）。 */
  private String declaredPlatformType(UUID shopId) {
    var row =
        sql.fetchOne(
            "select platform_type from crm_cart_shop_profile where tenant_id = ? and shop_id = ?",
            currentTenant(),
            shopId);
    return row == null ? null : row.get("platform_type", String.class);
  }

  /** 目标店铺必须在 channel 的真实店铺清单里（否则运营会对一家不存在的店登记弃单）。 */
  private SignoffDeliveredOrderPort.ShopView requireShop(UUID shopId, String bearer) {
    for (var shop : shops.shops(bearer)) {
      if (shop.id().equals(shopId)) return shop;
    }
    throw bad("店铺 " + shopId + " 不在上游的店铺清单里：请从真实店铺目录里选一个店铺。");
  }

  /** 当前频次策略（没有行时用默认值）。 */
  private Policy currentPolicy() {
    var row =
        sql.fetchOne(
            "select max_touches_per_day,max_touches_per_cycle,cycle_days,abandon_timeout_hours"
                + " from crm_cart_recovery_policy where tenant_id = ?",
            currentTenant());
    if (row == null) {
      return new Policy(
          DEFAULT_MAX_TOUCHES_PER_DAY,
          DEFAULT_MAX_TOUCHES_PER_CYCLE,
          DEFAULT_CYCLE_DAYS,
          DEFAULT_ABANDON_TIMEOUT_HOURS,
          false);
    }
    return new Policy(
        row.get("max_touches_per_day", Integer.class),
        row.get("max_touches_per_cycle", Integer.class),
        row.get("cycle_days", Integer.class),
        row.get("abandon_timeout_hours", Integer.class),
        true);
  }

  /** 策略行的最后修改时刻（没有行则 null）。 */
  private String policyUpdatedAt() {
    var row =
        sql.fetchOne(
            "select updated_at from crm_cart_recovery_policy where tenant_id = ?", currentTenant());
    return row == null ? null : str(row.get("updated_at", OffsetDateTime.class));
  }

  /** 某买家在给定时刻之后被触达的次数（口径与 DB 触发器逐字一致）。 */
  private long touchCountSince(String buyerRef, OffsetDateTime since) {
    var row =
        sql.fetchOne(
            "select count(*) from crm_cart_recovery_touch t"
                + " join crm_cart_abandonment a on a.tenant_id = t.tenant_id and a.id = t.cart_id"
                + " where t.tenant_id = ? and a.buyer_ref = ? and t.created_at >= ?::timestamptz",
            currentTenant(),
            buyerRef,
            since);
    return nz(row.get(0, Long.class));
  }

  /** 退订命中（口径与 DB 触发器逐字一致）。 */
  private CrmCartUnsubscribeRow findUnsubscribe(
      String contactType, String contactValue, UUID shopId) {
    var row =
        sql.fetchOne(
            unsubscribeColumns() + " from crm_cart_unsubscribe u"
                + " where u.tenant_id = ? and u.contact_type = ? and lower(u.contact_value) = lower(?)"
                + " and (u.shop_id is null or u.shop_id = ?)",
            currentTenant(),
            contactType,
            contactValue,
            shopId);
    return row == null ? null : unsubscribe(row);
  }

  /** 弃单行的 SELECT 片段（触达数**从真实明细数出来**）。 */
  private static String abandonmentColumns() {
    return "select a.id,a.tenant_id,a.cart_no,a.shop_id,a.shop_name,a.platform_code,a.platform_type,"
        + " a.cart_ref,a.external_event_id,a.stage,a.buyer_ref,a.buyer_email,a.buyer_phone,a.currency,"
        + " a.amount,a.item_count,a.item_summary,a.abandoned_at,a.recover_deadline_at,a.status,"
        + " a.recovered_at,a.recovered_amount,a.recovered_order_no,a.source,a.created_by,a.note,"
        + " a.created_at,a.updated_at,"
        + " (select count(*)::int from crm_cart_recovery_touch u"
        + "   where u.tenant_id = a.tenant_id and u.cart_id = a.id) as touch_count";
  }

  /** 话术行的 SELECT 片段（使用次数**从真实明细数出来**）。 */
  private static String messageColumns() {
    return "select t.id,t.tenant_id,t.message_code,t.name,t.channel,t.locale,t.subject,t.body,"
        + " t.status,t.version,t.approved_by,t.approved_at,t.approved_version,t.enabled_by,"
        + " t.enabled_at,t.enabled_version,t.disabled_by,t.disabled_at,t.created_by,t.note,"
        + " t.created_at,t.updated_at,"
        + " (select count(*)::int from crm_cart_recovery_touch u"
        + "   where u.tenant_id = t.tenant_id and u.message_id = t.id) as usage_count";
  }

  /** 退订行的 SELECT 片段。 */
  private static String unsubscribeColumns() {
    return "select u.id,u.tenant_id,u.contact_type,u.contact_value,u.shop_id,u.reason,"
        + " u.unsubscribed_at,u.created_by,u.note,u.created_at";
  }

  /** 弃单清单的 WHERE 片段与绑定（列表与计数**同一套口径**）。 */
  private String abandonmentWhere(
      UUID shopId,
      String status,
      String stage,
      String buyerRef,
      String cartRef,
      String keyword,
      OffsetDateTime from,
      OffsetDateTime to,
      List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    if (shopId != null) {
      where.append(" and a.shop_id = ?");
      binds.add(shopId);
    }
    if (blankToNull(status) != null) {
      where.append(" and a.status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(stage) != null) {
      where.append(" and a.stage = ?");
      binds.add(stage.trim());
    }
    if (blankToNull(buyerRef) != null) {
      where.append(" and a.buyer_ref = ?");
      binds.add(buyerRef.trim());
    }
    if (blankToNull(cartRef) != null) {
      where.append(" and a.cart_ref = ?");
      binds.add(cartRef.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and strpos(lower(a.item_summary), lower(?)) > 0");
      binds.add(keyword.trim());
    }
    if (from != null) {
      where.append(" and a.abandoned_at >= ?::timestamptz");
      binds.add(from);
    }
    if (to != null) {
      where.append(" and a.abandoned_at < ?::timestamptz");
      binds.add(to);
    }
    return where.toString();
  }

  private CrmCartAbandonmentRow requireAbandonment(UUID cartId) {
    var row =
        sql.fetchOne(
            abandonmentColumns() + " from crm_cart_abandonment a where a.tenant_id = ? and a.id = ?",
            currentTenant(),
            cartId);
    if (row == null) throw notFound("弃单不存在：" + cartId);
    return abandonment(row);
  }

  private CrmCartMessageRow requireMessage(UUID messageId) {
    var row =
        sql.fetchOne(
            messageColumns() + " from crm_cart_recovery_message t where t.tenant_id = ? and t.id = ?",
            currentTenant(),
            messageId);
    if (row == null) throw notFound("挽回话术不存在：" + messageId);
    return message(row);
  }

  private CrmCartTouchRow requireTouch(UUID touchId) {
    CrmCartTouchRow row = touchById(touchId);
    if (row == null) throw notFound("触达记录不存在：" + touchId);
    return row;
  }

  private CrmCartTouchRow touchById(UUID touchId) {
    var row =
        sql.fetchOne(
            touchColumns() + " from crm_cart_recovery_touch u where u.tenant_id = ? and u.id = ?",
            currentTenant(),
            touchId);
    return row == null ? null : touch(row);
  }

  private List<CrmCartTouchRow> touches(UUID cartId) {
    return sql.fetch(
            touchColumns() + " from crm_cart_recovery_touch u"
                + " where u.tenant_id = ? and u.cart_id = ? order by u.created_at desc, u.id desc",
            currentTenant(),
            cartId)
        .map(CartRecoveryService::touch);
  }

  private CrmCartUnsubscribeRow unsubscribeById(UUID id) {
    var row =
        sql.fetchOne(
            unsubscribeColumns() + " from crm_cart_unsubscribe u where u.tenant_id = ? and u.id = ?",
            currentTenant(),
            id);
    if (row == null) throw notFound("退订记录不存在：" + id);
    return unsubscribe(row);
  }

  /** 触达行的 SELECT 片段。 */
  private static String touchColumns() {
    return "select u.id,u.tenant_id,u.cart_id,u.cart_no,u.message_id,u.message_code,u.channel,"
        + " u.transport,u.status,u.recipient,u.subject,u.body,u.failure_reason,u.sent_at,"
        + " u.operator_id,u.created_at,u.updated_at";
  }

  /** 收件人：由弃单上的联系方式按通道派生（**不是**客户端另填的）。 */
  private static String recipientOf(CrmCartAbandonmentRow cart, String channel) {
    return switch (channel) {
      case "EMAIL" -> nz(cart.buyerEmail()).trim();
      case "SMS" -> nz(cart.buyerPhone()).trim();
      case "IN_APP" -> nz(cart.buyerRef()).trim();
      default -> "";
    };
  }

  /** 弃单号：CR-yyyyMMdd-000001（序列单独授权，见 V296 授权段）。 */
  private String nextCartNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_cart_abandonment_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CR-" + day + "-" + String.format("%06d", seq);
  }

  /** 这个购物车号是不是已经登记过了（幂等判定）。 */
  private boolean cartRefExists(String cartRef) {
    var row =
        sql.fetchOne(
            "select cart_no from crm_cart_abandonment where tenant_id = ? and cart_ref = ?",
            currentTenant(),
            cartRef);
    return row != null;
  }

  /** 已登记过的购物车号对应的弃单号。 */
  private String cartNoOf(String cartRef) {
    var row =
        sql.fetchOne(
            "select cart_no from crm_cart_abandonment where tenant_id = ? and cart_ref = ?",
            currentTenant(),
            cartRef);
    return row == null ? "" : nz(row.get("cart_no", String.class));
  }

  /** 话术编号是不是已经存在了。 */
  private boolean messageCodeExists(String code) {
    var row =
        sql.fetchOne(
            "select id from crm_cart_recovery_message where tenant_id = ? and message_code = ?",
            currentTenant(),
            code);
    return row != null;
  }

  // ================================================================ 行映射

  private static CrmCartAbandonmentRow abandonment(Record r) {
    return new CrmCartAbandonmentRow(
        r.get("id", UUID.class),
        nz(r.get("cart_no", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("platform_type", String.class)),
        nz(r.get("cart_ref", String.class)),
        nz(r.get("external_event_id", String.class)),
        nz(r.get("stage", String.class)),
        nz(r.get("buyer_ref", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("buyer_phone", String.class)),
        nz(r.get("currency", String.class)),
        r.get("amount", BigDecimal.class),
        r.get("item_count", BigDecimal.class),
        nz(r.get("item_summary", String.class)),
        str(r.get("abandoned_at", OffsetDateTime.class)),
        str(r.get("recover_deadline_at", OffsetDateTime.class)),
        nz(r.get("status", String.class)),
        str(r.get("recovered_at", OffsetDateTime.class)),
        r.get("recovered_amount", BigDecimal.class),
        nz(r.get("recovered_order_no", String.class)),
        nz(r.get("source", String.class)),
        nz(r.get("touch_count", Long.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCartMessageRow message(Record r) {
    return new CrmCartMessageRow(
        r.get("id", UUID.class),
        nz(r.get("message_code", String.class)),
        nz(r.get("name", String.class)),
        nz(r.get("channel", String.class)),
        nz(r.get("locale", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("version", Integer.class)),
        r.get("approved_by", UUID.class),
        str(r.get("approved_at", OffsetDateTime.class)),
        r.get("approved_version", Integer.class),
        r.get("enabled_by", UUID.class),
        str(r.get("enabled_at", OffsetDateTime.class)),
        r.get("enabled_version", Integer.class),
        r.get("disabled_by", UUID.class),
        str(r.get("disabled_at", OffsetDateTime.class)),
        nz(r.get("usage_count", Long.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCartTouchRow touch(Record r) {
    return new CrmCartTouchRow(
        r.get("id", UUID.class),
        r.get("cart_id", UUID.class),
        nz(r.get("cart_no", String.class)),
        r.get("message_id", UUID.class),
        nz(r.get("message_code", String.class)),
        nz(r.get("channel", String.class)),
        nz(r.get("transport", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("recipient", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("failure_reason", String.class)),
        str(r.get("sent_at", OffsetDateTime.class)),
        r.get("operator_id", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCartUnsubscribeRow unsubscribe(Record r) {
    return new CrmCartUnsubscribeRow(
        r.get("id", UUID.class),
        nz(r.get("contact_type", String.class)),
        nz(r.get("contact_value", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("reason", String.class)),
        str(r.get("unsubscribed_at", OffsetDateTime.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static CrmCartRecoveryRateBucket rateBucket(Record r) {
    long bucketTotal = nz(r.get("total", Long.class));
    return new CrmCartRecoveryRateBucket(
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        bucketTotal,
        nz(r.get("recovered", Long.class)),
        bucketTotal <= 0 ? null : r.get("recovery_rate", BigDecimal.class),
        r.get("total_amount", BigDecimal.class),
        r.get("recovered_amount", BigDecimal.class));
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，写进去的弃单时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  /** 可选时刻：空白即「不筛」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  private static String oneOf(List<String> allowed, String value, String label) {
    String candidate = nz(value).trim();
    if (!allowed.contains(candidate)) {
      throw bad(label + "「" + candidate + "」不在允许取值里：" + String.join(" / ", allowed));
    }
    return candidate;
  }

  private static int requireRange(Integer value, int min, int max, String label) {
    int candidate = value == null ? min : value;
    if (candidate < min || candidate > max) {
      throw bad(
          label + "必须在 " + min + " 到 " + max + " 之间（当前 " + candidate
              + "）：没有天花板的频次上限等于没有上限。");
    }
    return candidate;
  }

  /** 话术语言标签：留空即 zh-CN（与本仓其它模块同一默认）。 */
  private static String messageLocale(String locale) {
    String text = blankToNull(locale);
    return text == null ? "zh-CN" : text;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }
}
