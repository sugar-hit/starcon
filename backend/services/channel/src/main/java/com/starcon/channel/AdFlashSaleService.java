package com.starcon.channel;

import static com.starcon.channel.AdFlashSaleModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 14.4.1.2 优惠券与秒杀（秒杀这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与**秒杀活动**：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。……<b>券的核销要与订单绑定</b>，
 * 否则无法评估活动真实效果与补贴成本。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>秒杀活动</b> → {@link #saveFlashSale}：一条秒杀记录就是一次活动定义
 *       （商品、秒杀价、库存、限领、限时、投放渠道）。
 *   <li><b>设置库存与限领</b> → {@link #claim}：库存真扣 + 按人限领，都在**显式行锁**内。
 *   <li><b>投放渠道</b> → 秒杀一次只投一个渠道 ⇒ 渠道与投放位是活动自身的字段。
 *   <li><b>跟踪领取与核销</b> → {@link #claim} / {@link #redeem} / {@link #statistics}。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的三个错：
 *
 * <ol>
 *   <li><b>把「本地已建」说成「平台已建」</b>。平台营销接口未交付 ⇒
 *       {@code platform_sync_status} 恒为 {@code LOCAL_ONLY} +
 *       {@code pending_platform_writeback} 恒为 true。</li>
 *   <li><b>核销不绑订单</b>。{@code order_ref} NOT NULL，空串直接 400。</li>
 *   <li><b>把「不可比」写成 0</b>。原价没登记 ⇒ 让利额不可比（null），不是 0。</li>
 * </ol>
 */
@Service
public class AdFlashSaleService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 本地生命周期状态全集。 */
  static final List<String> STATUSES =
      List.of("DRAFT", "ACTIVE", "PAUSED", "ENDED", "CANCELLED");

  /** 平台同步状态全集（只有 LOCAL_ONLY 是本批真实可达的）。 */
  static final List<String> SYNC_STATUSES =
      List.of("LOCAL_ONLY", "PENDING_WRITEBACK", "SYNCED", "FAILED");

  /** 投放渠道全集（站内 / 站外 / 社媒）。 */
  static final List<String> CHANNEL_TYPES = List.of("ON_SITE", "OFF_SITE", "SOCIAL");

  /** 本地状态机：终态没有出边（历史留痕比状态回退更有用）。 */
  static final Map<String, List<String>> STATUS_TRANSITIONS =
      Map.of(
          "DRAFT", List.of("ACTIVE", "CANCELLED"),
          "ACTIVE", List.of("PAUSED", "ENDED", "CANCELLED"),
          "PAUSED", List.of("ACTIVE", "ENDED", "CANCELLED"),
          "ENDED", List.of(),
          "CANCELLED", List.of());

  static final String PRICING_SNAPSHOT_NOTE =
      "🔴 **秒杀价与原价都是快照**：上游 5 定价在 **catalog** 服务，channel **不得跨服务 SQL**"
          + "（不 join、不建跨库 FK、不建共享视图）⇒ 秒杀价 / 原价是**建活动时登记的输入**，"
          + "**不是**实时价。原价没登记 ⇒ 让利额**不可比（null）**，**不是 0**。";

  static final String SYNC_NOTE =
      "🔴 **平台营销接口未交付** ⇒ 平台同步状态只有「本地已建、平台侧还没有」（LOCAL_ONLY）。"
          + "`pendingPlatformWriteback=true` 表示**平台侧还没有这个秒杀活动**，"
          + "界面上必须写明这一点 —— 否则「已创建秒杀」会被当成平台侧已创建，那是假事实。"
          + "`SYNCED` 只能由平台回写适配器产生（该适配器未交付），本批代码里没有任何路径写入它。";

  static final String ORDER_BINDING_NOTE =
      "🔴 **核销必须与订单绑定**（蓝图加粗句）：核销请求的 `orderRef` 为空直接拒绝 ——"
          + "没有订单标识的核销**写不进来**，否则无法评估活动真实效果与补贴成本。";

  static final String MARGIN_NOTE =
      "🔴 **毛利 / 利润维度当前不可用**：成本价在 05 定价（catalog 服务），channel 跨服务不可读"
          + "⇒ 拿不到成本，「这笔秒杀赚没赚」判不了。本模块**不写一个 0 毛利糊弄过去**"
          + "（界面上的「毛利 0」会被当成「这单不赚钱」，而真相是「拿不到成本」）；"
          + "本地**能**算的是让利额与补贴成本，本模块据此真算真落库。";

  static final String UPSTREAM_NOTE =
      "上游 = 2.1.1.1 platform（全局码表）+ 2.3.1.1 shop（参与店铺，复合 FK 带 tenant_id）"
          + "+ 4.1.1.1 listing（同库同服务，秒杀针对的商品）；"
          + "5 定价在 catalog，**跨服务不可读** ⇒ 秒杀价与原价只能是快照。";

  static final String BOUNDARY_NOTE =
      "下游 = 平台营销接口（**未交付** ⇒ 秒杀只落本地并标「平台侧还没有」）。"
          + "本模块**不实现**优惠券那一半（见 AdCouponService）与促销效果分析（14.4.2.1）。";

  private final DSLContext sql;

  public AdFlashSaleService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具
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

  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

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

  public AdFlashSaleCapabilities capabilities() {
    return new AdFlashSaleCapabilities(
        List.of(
            new AdFlashSaleCapabilityDimension(
                "flash_sale_definition",
                "秒杀活动定义：商品 / 秒杀价 / 原价 / 库存 / 限领 / 限时 / 投放渠道",
                true,
                "ad_flash_sale（V286）",
                "",
                "没有活动定义就没有秒杀记录；库存与限领由 DB CHECK 钉死自洽"),
            new AdFlashSaleCapabilityDimension(
                "stock_deduction",
                "🔴 **库存真扣**（显式行锁 + 计数列）",
                true,
                "ad_flash_sale.claimed_quantity + ad_flash_sale_claim",
                "",
                "没有真扣库存，秒杀就会超发 —— 这是最贵的错误"),
            new AdFlashSaleCapabilityDimension(
                "per_user_limit",
                "🔴 **按人限领**（累计件数判据）",
                true,
                "ad_flash_sale_claim（按 (flash_sale_id, recipient_key) 聚合）",
                "",
                "没有限领，一个人可以把秒杀库存全领走"),
            new AdFlashSaleCapabilityDimension(
                "redemption_binding",
                "🔴 **核销与订单绑定** + 让利额 / 补贴成本落库",
                true,
                "ad_flash_sale_claim（order_ref / redeemed_amount / subsidy_cost）",
                "",
                "核销不绑订单就评估不了活动真实效果与补贴成本（蓝图加粗句）"),
            new AdFlashSaleCapabilityDimension(
                "flash_sale_statistics",
                "秒杀统计（真聚合：核销率 / 补贴成本 / 按日拆分）",
                true,
                "ad_flash_sale_claim（V286）",
                "",
                "没有统计就只有流水，运营看不出秒杀效果"),
            new AdFlashSaleCapabilityDimension(
                "platform_marketing_writeback",
                "把秒杀回写到平台营销接口",
                false,
                "—",
                "平台适配层（channel/vendor 的平台营销 API）",
                "平台营销接口未交付 ⇒ 秒杀只落本地并标「平台侧还没有」；"
                    + "界面上不把「本地已建」说成「平台侧已建」"),
            new AdFlashSaleCapabilityDimension(
                "gross_margin",
                "🔴 **毛利 / 利润**",
                false,
                "—",
                "5 定价（成本价，在 catalog 服务）",
                "成本价跨服务不可读 ⇒ 「这笔秒杀赚没赚」判不了；"
                    + "⛔ **不写 0 毛利**（那会被当成「这单不赚钱」的假事实）")),
        STATUSES,
        SYNC_STATUSES,
        CHANNEL_TYPES,
        PRICING_SNAPSHOT_NOTE,
        SYNC_NOTE,
        ORDER_BINDING_NOTE,
        MARGIN_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdFlashSaleOptions options() {
    List<AdFlashSalePlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new AdFlashSalePlatformOption(
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
                "select distinct currency from ad_flash_sale where tenant_id = "
                    + TENANT
                    + " and currency <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new AdFlashSaleOptions(
        List.of(
            new AdFlashSaleOption("DRAFT", "草稿"),
            new AdFlashSaleOption("ACTIVE", "进行中"),
            new AdFlashSaleOption("PAUSED", "已暂停"),
            new AdFlashSaleOption("ENDED", "已结束"),
            new AdFlashSaleOption("CANCELLED", "已停用")),
        List.of(
            new AdFlashSaleOption("LOCAL_ONLY", "本地已建，平台侧还没有"),
            new AdFlashSaleOption("PENDING_WRITEBACK", "回写已发起，待平台确认"),
            new AdFlashSaleOption("SYNCED", "平台侧已一致"),
            new AdFlashSaleOption("FAILED", "回写失败")),
        List.of(
            new AdFlashSaleOption("ON_SITE", "站内"),
            new AdFlashSaleOption("OFF_SITE", "站外"),
            new AdFlashSaleOption("SOCIAL", "社媒")),
        platforms,
        shopCodes,
        currencies,
        PRICING_SNAPSHOT_NOTE);
  }

  // ==================================================================
  // 列表与详情
  // ==================================================================

  public AdFlashSalePage flashSales(
      String status,
      UUID platformId,
      String shopCode,
      String channelType,
      String syncStatus,
      String keyword,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where f.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String state = blankToNull(status);
    if (state != null) {
      where.append(" and f.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    if (platformId != null) {
      where.append(" and f.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and f.shop_code = ?");
      binds.add(shop);
    }
    String channel = blankToNull(channelType);
    if (channel != null) {
      where.append(" and f.channel_type = ?");
      binds.add(channel.toUpperCase(Locale.ROOT));
    }
    String sync = blankToNull(syncStatus);
    if (sync != null) {
      where.append(" and f.platform_sync_status = ?");
      binds.add(sync.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (f.sale_code ilike ? or f.sale_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from ad_flash_sale f" + where, binds.toArray());

    List<AdFlashSaleRow> items =
        sql.fetch(
                "select f.id, f.sale_code, f.sale_name, f.platform_id,"
                    + " (select pf.code from platform pf where pf.id = f.platform_id) platform_code,"
                    + " f.shop_code, f.listing_id,"
                    + " (select l.title from listing l where l.tenant_id = f.tenant_id"
                    + "  and l.id = f.listing_id) listing_title,"
                    + " f.flash_price, f.original_price, f.currency, f.start_at, f.end_at,"
                    + " f.status, f.total_stock, f.claimed_quantity, f.redeemed_quantity,"
                    + " f.per_user_limit, f.channel_type, f.placement, f.platform_sync_status,"
                    + " f.pending_platform_writeback, f.local_changed_at, f.note, f.version,"
                    + " f.created_at, f.updated_at"
                    + " from ad_flash_sale f"
                    + where
                    + " order by f.created_at desc, f.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::flashSaleRow)
            .toList();

    return new AdFlashSalePage(items, total, safePage, safeSize);
  }

  private AdFlashSaleRow flashSaleRow(Record r) {
    BigDecimal totalStock = r.get("total_stock", BigDecimal.class);
    BigDecimal claimed = r.get("claimed_quantity", BigDecimal.class);
    BigDecimal remaining = totalStock == null || claimed == null ? null : totalStock.subtract(claimed);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    return new AdFlashSaleRow(
        r.get("id", UUID.class),
        r.get("sale_code", String.class),
        r.get("sale_name", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("listing_id", UUID.class),
        r.get("listing_title", String.class),
        r.get("flash_price", BigDecimal.class),
        r.get("original_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("start_at", OffsetDateTime.class),
        r.get("end_at", OffsetDateTime.class),
        AdCouponService.withinWindow(
            now, r.get("start_at", OffsetDateTime.class), r.get("end_at", OffsetDateTime.class)),
        r.get("status", String.class),
        totalStock,
        claimed,
        r.get("redeemed_quantity", BigDecimal.class),
        remaining,
        r.get("per_user_limit", BigDecimal.class),
        r.get("channel_type", String.class),
        r.get("placement", String.class),
        r.get("platform_sync_status", String.class),
        r.get("pending_platform_writeback", Boolean.class),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public AdFlashSaleDetail flashSale(String id) {
    UUID saleId = parseId(id);
    Record r =
        sql.fetchOne(
            "select f.id, f.sale_code, f.sale_name, f.platform_id,"
                + " (select pf.code from platform pf where pf.id = f.platform_id) platform_code,"
                + " f.shop_code, f.listing_id,"
                + " (select l.title from listing l where l.tenant_id = f.tenant_id"
                + "  and l.id = f.listing_id) listing_title,"
                + " f.flash_price, f.original_price, f.currency, f.start_at, f.end_at,"
                + " f.status, f.total_stock, f.claimed_quantity, f.redeemed_quantity,"
                + " f.per_user_limit, f.channel_type, f.placement, f.platform_sync_status,"
                + " f.pending_platform_writeback, f.local_changed_at, f.note, f.version,"
                + " f.created_at, f.updated_at"
                + " from ad_flash_sale f where f.tenant_id = "
                + TENANT
                + " and f.id = ?",
            saleId);
    if (r == null) throw notFound("秒杀活动");

    return new AdFlashSaleDetail(
        flashSaleRow(r),
        recentClaims(saleId, 20),
        statistics(saleId),
        SYNC_NOTE,
        PRICING_SNAPSHOT_NOTE,
        r.get("note", String.class));
  }

  // ==================================================================
  // 登记 / 修改 / 状态
  // ==================================================================

  public AdFlashSaleDetail saveFlashSale(SaveFlashSaleRequest request) {
    Validated validated = validate(request);
    UUID saleId = Ids.next();

    sql.execute(
        "insert into ad_flash_sale (tenant_id,id,platform_id,shop_code,sale_code,sale_name,"
            + " listing_id,flash_price,original_price,currency,start_at,end_at,total_stock,"
            + " claimed_quantity,redeemed_quantity,per_user_limit,channel_type,placement,status,"
            + " platform_sync_status,pending_platform_writeback,local_changed_at,"
            + " last_status_reason,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,0,0,?,?,?,'DRAFT',"
            + " 'LOCAL_ONLY',true,now(),'',?,0,now(),now())",
        currentTenant(),
        saleId,
        request.platformId(),
        validated.shopCode(),
        validated.saleCode(),
        validated.saleName(),
        request.listingId(),
        validated.flashPrice(),
        validated.originalPrice(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        validated.totalStock(),
        validated.perUserLimit(),
        validated.channelType(),
        validated.placement(),
        text(request.note()));

    return flashSale(saleId.toString());
  }

  /** 改秒杀定义。秒杀编码创建后不可改；库存不能调到低于已领取数。 */
  public AdFlashSaleDetail updateFlashSale(String id, SaveFlashSaleRequest request) {
    UUID saleId = parseId(id);
    Validated validated = validate(request);
    Record current = lockedFlashSale(saleId);
    if (current == null) throw notFound("秒杀活动");

    BigDecimal claimed = current.get("claimed_quantity", BigDecimal.class);
    if (validated.totalStock().compareTo(claimed) < 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "库存不能调到低于已领取数（已领取 "
              + claimed.stripTrailingZeros().toPlainString()
              + " 件）");
    }

    sql.execute(
        "update ad_flash_sale set sale_name = ?, listing_id = ?, flash_price = ?,"
            + " original_price = ?, currency = ?, start_at = ?::timestamptz,"
            + " end_at = ?::timestamptz, total_stock = ?, per_user_limit = ?, channel_type = ?,"
            + " placement = ?, note = ?, local_changed_at = now(), version = version + 1,"
            + " updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        validated.saleName(),
        request.listingId(),
        validated.flashPrice(),
        validated.originalPrice(),
        validated.currency(),
        request.startAt(),
        request.endAt(),
        validated.totalStock(),
        validated.perUserLimit(),
        validated.channelType(),
        validated.placement(),
        text(request.note()),
        saleId);

    return flashSale(saleId.toString());
  }

  public AdFlashSaleDetail changeStatus(String id, ChangeFlashSaleStatusRequest request) {
    UUID saleId = parseId(id);
    String target = text(request.status()).toUpperCase(Locale.ROOT);
    if (!STATUS_TRANSITIONS.containsKey(target)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态取值不合法");
    }

    Record current = lockedFlashSale(saleId);
    if (current == null) throw notFound("秒杀活动");
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
        "update ad_flash_sale set status = ?, last_status_reason = ?, local_changed_at = now(),"
            + " version = version + 1, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        target,
        text(request.reason()),
        saleId);

    return flashSale(saleId.toString());
  }

  // ==================================================================
  // 领取（库存真扣 + 按人限领）
  // ==================================================================

  /**
   * 领取秒杀。
   *
   * <p>🔴 库存真扣与限领判据都在**显式行锁**内做（{@code select ... for update}）——
   * 并发的两次领取不会都通过（DB CHECK 再兜一层：claimed_quantity <= total_stock）。
   */
  public AdFlashSaleDetail claim(String id, ClaimFlashSaleRequest request) {
    UUID saleId = parseId(id);
    String recipient = text(request.recipientKey());
    if (recipient.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取人标识必填");
    }
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取件数必须大于 0");
    }

    Record locked = lockedFlashSale(saleId);
    if (locked == null) throw notFound("秒杀活动");

    String status = locked.get("status", String.class);
    if (!status.equals("ACTIVE")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有进行中的秒杀才能领取（当前状态：" + status + "）");
    }
    if (!AdCouponService.withinWindow(
        OffsetDateTime.now(ZoneOffset.UTC),
        locked.get("start_at", OffsetDateTime.class),
        locked.get("end_at", OffsetDateTime.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "秒杀不在活动时间窗内，不能领取");
    }

    BigDecimal totalStock = locked.get("total_stock", BigDecimal.class);
    BigDecimal claimed = locked.get("claimed_quantity", BigDecimal.class);
    BigDecimal left = totalStock.subtract(claimed);
    if (left.compareTo(quantity) < 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "秒杀库存不足：剩余 "
              + left.stripTrailingZeros().toPlainString()
              + " 件，请求 "
              + quantity.stripTrailingZeros().toPlainString()
              + " 件");
    }

    BigDecimal perUserLimit = locked.get("per_user_limit", BigDecimal.class);
    BigDecimal already =
        one(
            BigDecimal.class,
            "select coalesce(sum(quantity),0) from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ? and recipient_key = ?",
            saleId,
            recipient);
    if (perUserLimit != null && already.add(quantity).compareTo(perUserLimit) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "超过每人限领：每人最多 "
              + perUserLimit.stripTrailingZeros().toPlainString()
              + " 件，该收件人已领 "
              + already.stripTrailingZeros().toPlainString()
              + " 件");
    }

    sql.execute(
        "insert into ad_flash_sale_claim (tenant_id,id,flash_sale_id,recipient_key,quantity,"
            + " claimed_at,order_ref,redeemed_at,redeemed_amount,subsidy_cost,currency,status)"
            + " values (?,?,?,?,?,now(),null,null,null,null,?,'CLAIMED')",
        currentTenant(),
        Ids.next(),
        saleId,
        recipient,
        quantity,
        locked.get("currency", String.class));

    sql.execute(
        "update ad_flash_sale set claimed_quantity = claimed_quantity + ?, version = version + 1,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        quantity,
        saleId);

    return flashSale(saleId.toString());
  }

  // ==================================================================
  // 核销（绑定订单标识）
  // ==================================================================

  /**
   * 核销一次秒杀领取。
   *
   * <p>🔴 订单标识为空直接拒绝（蓝图加粗句）。让利额 = (原价 − 秒杀价) × 数量；
   * 原价没登记 ⇒ 让利额**不可比（null）**，**不是 0**。
   */
  public AdFlashSaleDetail redeem(String id, RedeemFlashSaleRequest request) {
    UUID saleId = parseId(id);
    String orderRef = text(request.orderRef());
    if (orderRef.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "订单标识必填 —— 核销必须与订单绑定");
    }

    Record locked = lockedFlashSale(saleId);
    if (locked == null) throw notFound("秒杀活动");

    Record claim;
    if (request.claimId() != null) {
      claim =
          sql.fetchOne(
              "select id, quantity, status from ad_flash_sale_claim where tenant_id = "
                  + TENANT
                  + " and id = ? and flash_sale_id = ? for update",
              request.claimId(),
              saleId);
      if (claim == null) throw notFound("秒杀领取记录");
    } else {
      claim =
          sql.fetchOne(
              "select id, quantity, status from ad_flash_sale_claim where tenant_id = "
                  + TENANT
                  + " and flash_sale_id = ? and status = 'CLAIMED'"
                  + " order by claimed_at desc, id desc limit 1 for update",
              saleId);
      if (claim == null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "这个秒杀没有未核销的领取记录");
      }
    }
    if (!"CLAIMED".equals(claim.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条领取记录已经核销过了（状态：" + claim.get("status", String.class) + "）");
    }

    BigDecimal original = locked.get("original_price", BigDecimal.class);
    BigDecimal flash = locked.get("flash_price", BigDecimal.class);
    BigDecimal quantity = claim.get("quantity", BigDecimal.class);
    BigDecimal redeemedAmount = null;
    BigDecimal subsidyCost = null;
    if (original != null && flash != null) {
      BigDecimal unit = original.subtract(flash);
      redeemedAmount = unit.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
      subsidyCost = redeemedAmount;
    }

    int updated =
        sql.execute(
            "update ad_flash_sale_claim set status = 'REDEEMED', order_ref = ?,"
                + " redeemed_at = now(), redeemed_amount = ?, subsidy_cost = ?"
                + " where tenant_id = "
                + TENANT
                + " and id = ? and status = 'CLAIMED'",
            orderRef,
            redeemedAmount,
            subsidyCost,
            claim.get("id", UUID.class));
    if (updated != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条领取记录已被并发核销，请重试");
    }

    sql.execute(
        "update ad_flash_sale set redeemed_quantity = redeemed_quantity + ?, version = version + 1,"
            + " updated_at = now() where tenant_id = "
            + TENANT
            + " and id = ?",
        quantity,
        saleId);

    return flashSale(saleId.toString());
  }

  // ==================================================================
  // 统计（真聚合）
  // ==================================================================

  public AdFlashSaleStatistics statistics(String id) {
    return statistics(parseId(id));
  }

  public AdFlashSaleStatistics statistics(UUID saleId) {
    Record totals =
        sql.fetchOne(
            "select (select coalesce(sum(quantity),0) from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ?) claimed_count,"
                + " (select coalesce(sum(quantity),0) from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ? and status = 'REDEEMED') redeemed_count,"
                + " (select coalesce(sum(redeemed_amount),0) from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ?) total_redeemed,"
                + " (select coalesce(sum(subsidy_cost),0) from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ?) subsidy_total,"
                + " (select total_stock from ad_flash_sale where tenant_id = "
                + TENANT
                + " and id = ?) total_stock",
            saleId,
            saleId,
            saleId,
            saleId,
            saleId);

    long claimed = totals == null ? 0 : totals.get("claimed_count", Long.class);
    long redeemed = totals == null ? 0 : totals.get("redeemed_count", Long.class);
    BigDecimal totalRedeemed =
        totals == null ? BigDecimal.ZERO : totals.get("total_redeemed", BigDecimal.class);
    BigDecimal subsidyTotal = totals == null ? BigDecimal.ZERO : totals.get("subsidy_total", BigDecimal.class);
    BigDecimal totalStock = totals == null ? null : totals.get("total_stock", BigDecimal.class);
    BigDecimal remaining =
        totalStock == null ? null : totalStock.subtract(BigDecimal.valueOf(claimed));

    BigDecimal rate =
        claimed == 0
            ? null
            : BigDecimal.valueOf(redeemed)
                .divide(BigDecimal.valueOf(claimed), 6, RoundingMode.HALF_UP);

    List<AdFlashSaleDailyStat> daily =
        sql.fetch(
                "select (cl.redeemed_at at time zone 'UTC')::date d, count(*) cnt,"
                    + " sum(cl.subsidy_cost) subsidy_cost"
                    + " from ad_flash_sale_claim cl"
                    + " where cl.tenant_id = "
                    + TENANT
                    + " and cl.flash_sale_id = ? and cl.status = 'REDEEMED'"
                    + " group by (cl.redeemed_at at time zone 'UTC')::date order by d",
                saleId)
            .stream()
            .map(
                r ->
                    new AdFlashSaleDailyStat(
                        r.get("d", LocalDate.class),
                        r.get("cnt", Long.class),
                        r.get("subsidy_cost", BigDecimal.class)))
            .toList();

    return new AdFlashSaleStatistics(
        totalStock,
        claimed,
        redeemed,
        remaining,
        rate,
        totalRedeemed,
        subsidyTotal,
        daily,
        MARGIN_NOTE);
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private List<AdFlashSaleClaimRow> recentClaims(UUID saleId, int limit) {
    return sql.fetch(
            "select id, recipient_key, quantity, claimed_at, order_ref, redeemed_at,"
                + " redeemed_amount, subsidy_cost, status"
                + " from ad_flash_sale_claim where tenant_id = "
                + TENANT
                + " and flash_sale_id = ?"
                + " order by claimed_at desc, id desc limit ?",
            saleId,
            limit)
        .stream()
        .map(
            r ->
                new AdFlashSaleClaimRow(
                    r.get("id", UUID.class),
                    r.get("recipient_key", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("claimed_at", OffsetDateTime.class),
                    r.get("order_ref", String.class),
                    r.get("redeemed_at", OffsetDateTime.class),
                    r.get("redeemed_amount", BigDecimal.class),
                    r.get("subsidy_cost", BigDecimal.class),
                    r.get("status", String.class)))
        .toList();
  }

  /** 🔴 取一行秒杀并**加行锁**（{@code for update}）：库存扣减必须在这个锁里做。 */
  private Record lockedFlashSale(UUID saleId) {
    return sql.fetchOne(
        "select id, status, flash_price, original_price, currency, total_stock,"
            + " claimed_quantity, redeemed_quantity, per_user_limit, start_at, end_at,"
            + " platform_sync_status, pending_platform_writeback"
            + " from ad_flash_sale where tenant_id = "
            + TENANT
            + " and id = ? for update",
        saleId);
  }

  private record Validated(
      String saleCode,
      String saleName,
      String shopCode,
      BigDecimal flashPrice,
      BigDecimal originalPrice,
      String currency,
      BigDecimal totalStock,
      BigDecimal perUserLimit,
      String channelType,
      String placement) {}

  private Validated validate(SaveFlashSaleRequest request) {
    String saleCode = text(request.saleCode());
    String saleName = text(request.saleName());
    String shopCode = text(request.shopCode());
    if (saleCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "秒杀编码必填");
    }
    if (saleName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "秒杀名称必填");
    }
    if (shopCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参与店铺必填");
    }
    if (request.flashPrice() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "秒杀价必填");
    }
    if (request.startAt() == null || request.endAt() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "秒杀的开始与结束时间必填");
    }
    if (!request.endAt().isAfter(request.startAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时间必须晚于开始时间");
    }
    BigDecimal totalStock =
        request.totalStock() == null ? BigDecimal.ZERO : request.totalStock();
    if (totalStock.compareTo(BigDecimal.ZERO) < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存不能是负数");
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    String channelType = text(request.channelType()).toUpperCase(Locale.ROOT);
    if (!CHANNEL_TYPES.contains(channelType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放渠道取值不合法");
    }
    return new Validated(
        saleCode,
        saleName,
        shopCode,
        request.flashPrice(),
        request.originalPrice(),
        currency,
        totalStock,
        request.perUserLimit(),
        channelType,
        text(request.placement()));
  }
}
