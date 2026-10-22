package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;
import static com.starcon.channel.PlatformViolationModels.*;
import static com.starcon.channel.PlatformViolationRules.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.1.2.1 违规与处罚。
 *
 * <p>蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * <b>违规常涉及多个商品</b>，需批量定位受影响 listing 与库存。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>值域在 DB 层钉死</b>：违规类型 / 处罚措施 / 处理状态 / 整改状态的值域与合法转换
 *       由迁移 V308 的 CHECK 与触发器钉死，服务层**先**按同一份规则拒（422，带人话），
 *       DB **再**兜底（23514）。</li>
 *   <li><b>扣款金额带币种，不跨币种合计</b>：{@link Money} 是唯一的金额入口，
 *       合计<b>按币种分组</b>返回，<b>没有一个跨币种的总数</b>。</li>
 *   <li><b>影响档位是派生的</b>：由处罚措施集合现算，<b>不落库</b>（避免与措施表漂移）。</li>
 *   <li><b>幂等锚点是平台侧不透明 id</b>：{@code external_notice_id}；
 *       重复同步命中唯一键 ⇒ 跳过，<b>不产生第二条记录</b>。</li>
 *   <li><b>批量定位真的批量</b>：受影响 listing 一次 SQL 查出，库存**一次**调 07 的
 *       可售量接口（<b>不是</b>一条条循环调上游）。</li>
 *   <li><b>下游未交付一律不编</b>：12 财务（罚扣款）与 16.1.2.2 申诉管理当前<b>都未交付</b>
 *       ⇒ 能力声明里 {@code available=false} + {@code ownerModule} + 影响说明。</li>
 * </ol>
 */
@Service
public class PlatformViolationService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一次批量挂载涉及主体最多多少条。 */
  private static final int MAX_SUBJECTS_PER_CALL = 500;

  /** 一次同步入口最多接收多少条平台通知。 */
  private static final int MAX_NOTICES_PER_SYNC = 200;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String TYPE_DOMAIN_NOTE =
      "🔴 违规类型是**受控值域**，由 DB 层 CHECK 钉死（值域要可审阅，不只靠应用层 enum）。"
          + "蓝图只写「违规类型」未列举，所以这份值域是**本模块的默认口径、不是平台事实**"
          + "（各平台的违规分类并不通用）；要按平台细分得等平台适配器接上后按租户扩展。";

  private static final String MONEY_SEMANTICS =
      "🔴 扣款金额 = Money（金额 + 币种），DB 里是 numeric(19,6) + 三位币种，**禁浮点**。"
          + "**不同币种不得直接相加** —— 所以扣款合计**按币种分组**返回，"
          + "**没有**一个跨币种的总数；要跨币种合计必须先有明确的折算口径，"
          + "本模块**不假设任何隐含汇率**（假设一个隐含汇率比不合计更危险）。"
          + "只有「扣款」这一种措施带金额，其余四种（警告 / 下架 / 限制销售 / 停号）"
          + "**一律不带金额**（硬塞一个 0 会让「没扣款」与「扣款 0」变成同一件事）。";

  private static final String IMPACT_SEMANTICS =
      "🔴 影响档位是**派生**的：一张违规的影响取决于它身上的**处罚措施集合**"
          + "（停号 > 下架 > 限制销售 / 扣款 > 警告）。它**不落库** —— "
          + "存成一列就会与措施表漂移（改了一条措施、档位还停在旧值）。"
          + "「尚无处罚措施」是它自己的一个档位，**不等于没问题**："
          + "一条没有任何处罚的违规仍然需要处理。";

  private static final String STATE_MACHINE_SEMANTICS =
      "🔴 处理状态与整改状态都是**显式状态机**：违规 = 未处理 → 处理中 → 已整改 / 申诉中 / 已关闭；"
          + "整改 = 待整改 → 整改中 → 已提交 → 已验收 / 已驳回。"
          + "**非法转换在服务层（422，带人话）与 DB 层（23514，兜底）都会被拒**。"
          + "「已提交」必须真的提交过、「已验收」必须真的验收过（DB 层 CHECK 钉住时刻）。"
          + "「申诉中」只表示**已在申诉** —— 16.1.2.2 申诉管理未交付，"
          + "本模块**不编造申诉单、不编造申诉结果**。";

  private static final String IDEMPOTENCY_SEMANTICS =
      "🔴 幂等锚点是**平台侧的通知 id**（external_notice_id），"
          + "唯一键是 (租户, 店铺, 通知 id)。"
          + "同一条平台违规**重复同步不产生第二条记录**（命中唯一键 ⇒ 跳过）。"
          + "锚点**不用**「标题 + 时间」这种能撞车的组合 —— 那种组合会把两条不同的违规当成同一条。";

  private static final String UPSTREAM_NOTE =
      "上游 = **2 同步**（平台违规通知同步）。平台违规通知是**平台推送**的形态，"
          + "所以本模块的同步入口是**接收通知**（按 external_notice_id 幂等登记），"
          + "不是拉取；每批同步都落一条**同步运行记录**（取到几条 / 写了���条 / 跳过几条 / 失败几条）。"
          + "**人工登记**是同步入口之外的第二条正当路径，但它**恒标 MANUAL**，不冒充平台同步。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **12 财务（罚扣款）**（**未交付** ⇒ 扣款金额只落在本模块自己的表里，"
          + "**不建财务表、不编造放行形状**）、"
          + "**4 刊登（问题商品下架）**（**已交付**：4.1.1.1 的批量下架就在本服务，"
          + "本模块真的能把那批 listing 下架）、"
          + "**16.1.2.2 申诉管理**（**未交付** ⇒ 只记「申诉中」这个状态，"
          + "**不编造**申诉单与申诉结果）。"
          + "这两条缺口在能力声明里是**可查的事实**（available=false + ownerModule + 影响说明），"
          + "不是文档里的一句话。";

  private final DSLContext sql;
  private final SellableQuantityQueryPort inventory;
  private final ListingService listings;

  public PlatformViolationService(
      DSLContext sql, SellableQuantityQueryPort inventory, ListingService listings) {
    this.sql = sql;
    this.inventory = inventory;
    this.listings = listings;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：登记 / 生效 / 提交 / 验收时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 🔴 非法 UUID 按「筛不出东西」处理（筛选参数是用户敲进来的），但**绝不当成没筛选**。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  /**
   * 取一行的第一列并转成 UUID。
   *
   * <p>🔴 jOOQ 3.19 <b>没有</b> {@code fetchValue(String, Class, Object...)} 这个重载 ——
   * 传 Class 进去会落到 {@code (String, Object...)} 上、被当成绑定值。
   * 所以这里照仓里既有的写法自己转一次。
   */
  private UUID fetchUuid(String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, UUID.class);
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  private static OffsetDateTime time(Record row, String column) {
    return row.get(column, OffsetDateTime.class);
  }

  private static BigDecimal decimal(Record row, String column) {
    return row.get(column, BigDecimal.class);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：值域、状态机、口径、以及**哪些下游未交付**。
   *
   * <p>🔴 下游 **12 财务（罚扣款）** 与 **16.1.2.2 申诉管理** 当前<b>都未交付</b>，
   * 如实报出 ownerModule 与影响；**4 刊登（问题商品下架）已交付**（4.1.1.1 批量下架在本服务内）。
   */
  public PlatformViolationCapabilities capabilities() {
    List<PlatformViolationCapabilityGap> gaps =
        List.of(
            new PlatformViolationCapabilityGap(
                "12",
                "财务（罚扣款）",
                "12 财务系统",
                false,
                "财务服务在本仓**尚未落地** ⇒ 扣款金额只落在本模块自己的表里"
                    + "（platform_penalty，按币种分组，**不跨币种相加**）。"
                    + "本模块**不建财务表、不造一个指向不存在入口的放行形状**；"
                    + "等 12 交付后由那一侧来读这笔扣款。"),
            new PlatformViolationCapabilityGap(
                "04",
                "刊登与内容（问题商品下架）",
                "04 刊登与内容 · 4.1.1.1 Listing 列表 · 批量下架",
                true,
                "批量下架**已交付**且就在本服务（ListingService#batch 的 DELIST 分支），"
                    + "所以本模块**真的能把**受影响 listing 下架 —— "
                    + "不是写一条「已下架」的记录，是真的改 listing 状态并留状态历史。"),
            new PlatformViolationCapabilityGap(
                "16.1.2.2",
                "申诉管理",
                "16.1.2.2 申诉管理",
                false,
                "申诉管理**未交付** ⇒ 本模块的处理状态只到「申诉中」这个状态为止，"
                    + "**不编造**申诉单、举证材料、提交跟踪与申诉结果；"
                    + "违规详情里也**没有**申诉入口（放行一个不存在的入口比 404 更糟）。"));

    return new PlatformViolationCapabilities(
        gaps,
        VIOLATION_TYPES,
        VIOLATION_TYPE_LABELS,
        MEASURES,
        MEASURE_LABELS,
        STATUSES,
        STATUS_LABELS,
        STATUS_TRANSITIONS,
        REMEDIATION_STATUSES,
        REMEDIATION_STATUS_LABELS,
        REMEDIATION_STATUS_TRANSITIONS,
        SUBJECT_TYPES,
        SOURCES,
        IMPACT_LEVELS,
        TYPE_DOMAIN_NOTE,
        MONEY_SEMANTICS,
        IMPACT_SEMANTICS,
        STATE_MACHINE_SEMANTICS,
        IDEMPOTENCY_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 店铺目录
  // ==================================================================

  /** 店铺目录里的一行。 */
  private record ShopRow(
      UUID id, String code, String name, String platformCode, String platformName) {}

  /**
   * 🔴 店铺清单<b>直接读本服务的店铺目录</b>（{@code shop} / {@code platform}），
   * 不写死店铺常量、不造平行店铺表。
   */
  private List<ShopRow> loadShops(UUID tenant, String platformCode, String keyword, UUID shopId) {
    StringBuilder statement =
        new StringBuilder(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(platformCode).isEmpty()) {
      statement.append(" and p.code = ?");
      params.add(text(platformCode).toUpperCase());
    }
    if (!text(keyword).isEmpty()) {
      statement.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    if (shopId != null) {
      statement.append(" and s.id = ?");
      params.add(shopId);
    }
    statement.append(" order by s.code");

    List<ShopRow> shops = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      shops.add(
          new ShopRow(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class)));
    }
    return shops;
  }

  /** 取店铺目录里的一行；不存在或不可见一律 404（跨租户与不存在逐字相同）。 */
  private ShopRow requireShop(UUID tenant, UUID shopId) {
    for (Record row :
        sql.fetch(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.id = ?
            """,
            tenant,
            shopId)) {
      return new ShopRow(
          row.get("id", UUID.class),
          row.get("code", String.class),
          row.get("name", String.class),
          row.get("platform_code", String.class),
          row.get("platform_name", String.class));
    }
    throw notFound();
  }

  // ==================================================================
  // 店铺范围清单
  // ==================================================================

  /** 店铺范围清单（**真分页** + 真统计）。 */
  public PlatformViolationShopPage shops(
      String platformCode, String keyword, String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    List<ShopRow> shops = loadShops(tenant, platformCode, keyword, parseFilterId(shopId));
    long total = shops.size();

    Map<UUID, long[]> counts = new LinkedHashMap<>();
    for (ShopRow shop : shops) {
      counts.put(shop.id(), new long[3]);
    }
    if (!shops.isEmpty()) {
      List<Object> params = new ArrayList<>();
      params.add(tenant);
      StringBuilder placeholders = new StringBuilder();
      for (ShopRow shop : shops) {
        placeholders.append(placeholders.length() == 0 ? "?" : ",?");
        params.add(shop.id());
      }
      // 🔴 三个计数在**一条 SQL** 里算出来：违规总数 / 未完结数 / 带扣款措施的违规数。
      //   「带扣款」用 exists 子查询（不去重计数，否则一条违规有两条扣款措施会被数两次）。
      for (Record row :
          sql.fetch(
              """
              select v.shop_id,
                     count(*) as violation_count,
                     count(*) filter (where v.status in ('PENDING','IN_PROGRESS')) as open_count,
                     count(*) filter (where exists (
                        select 1 from platform_penalty p
                         where p.tenant_id = v.tenant_id and p.violation_id = v.id
                           and p.measure = 'DEDUCTION')) as deduction_count
              from platform_violation v
              where v.tenant_id = ? and v.shop_id in ("""
                  + placeholders
                  + """
              ) group by v.shop_id
              """,
              params.toArray())) {
        long[] bucket = counts.get(row.get("shop_id", UUID.class));
        if (bucket != null) {
          bucket[0] = count(row.get("violation_count"));
          bucket[1] = count(row.get("open_count"));
          bucket[2] = count(row.get("deduction_count"));
        }
      }
    }

    int from = Math.min(safePage * safeSize, shops.size());
    int to = Math.min(from + safeSize, shops.size());
    List<PlatformViolationShop> items = new ArrayList<>();
    for (ShopRow shop : shops.subList(from, to)) {
      long[] bucket = counts.getOrDefault(shop.id(), new long[3]);
      items.add(
          new PlatformViolationShop(
              shop.id().toString(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              shop.platformName(),
              (int) bucket[0],
              (int) bucket[1],
              (int) bucket[2]));
    }
    return new PlatformViolationShopPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 违规记录
  // ==================================================================

  /** 违规记录列表（**真分页 + 真筛选**）。 */
  public PlatformViolationPage violations(
      String shopId,
      String status,
      String violationType,
      String measure,
      String source,
      String keyword,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where v.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and v.shop_id = ?");
      params.add(filterShop);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownStatus(statusValue)) {
        throw unprocessable("未知的处理状态：" + status);
      }
      where.append(" and v.status = ?");
      params.add(statusValue);
    }
    String typeValue = text(violationType).toUpperCase();
    if (!typeValue.isEmpty()) {
      if (!knownViolationType(typeValue)) {
        throw unprocessable("未知的违规类型：" + violationType);
      }
      where.append(" and v.violation_type = ?");
      params.add(typeValue);
    }
    String measureValue = text(measure).toUpperCase();
    if (!measureValue.isEmpty()) {
      if (!knownMeasure(measureValue)) {
        throw unprocessable("未知的处罚措施：" + measure);
      }
      where.append(
          " and exists (select 1 from platform_penalty p where p.tenant_id = v.tenant_id"
              + " and p.violation_id = v.id and p.measure = ?)");
      params.add(measureValue);
    }
    String sourceValue = text(source).toUpperCase();
    if (!sourceValue.isEmpty()) {
      if (!SOURCES.contains(sourceValue)) {
        throw unprocessable("未知的来源：" + source);
      }
      where.append(" and v.source = ?");
      params.add(sourceValue);
    }
    String keywordValue = text(keyword);
    if (!keywordValue.isEmpty()) {
      where.append(
          " and (v.title ilike '%' || ? || '%' or v.description ilike '%' || ? || '%'"
              + " or v.external_notice_id ilike '%' || ? || '%')");
      params.add(keywordValue);
      params.add(keywordValue);
      params.add(keywordValue);
    }
    OffsetDateTime fromTime = parseTime(from, "起始时间");
    if (fromTime != null) {
      where.append(" and v.occurred_at >= ?::timestamptz");
      params.add(fromTime);
    }
    OffsetDateTime toTime = parseTime(to, "截止时间");
    if (toTime != null) {
      where.append(" and v.occurred_at <= ?::timestamptz");
      params.add(toTime);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from platform_violation v" + where, params.toArray()));

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (String code : STATUSES) {
      statusCounts.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select v.status, count(*) as c from platform_violation v"
                + where
                + " group by v.status",
            params.toArray())) {
      statusCounts.put(row.get("status", String.class), count(row.get("c")));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select v.*, s.code as shop_code, s.name as shop_name, p.code as platform_code,
                   (select count(*) from platform_violation_subject ss
                     where ss.tenant_id = v.tenant_id and ss.violation_id = v.id) as subject_count,
                   (select count(*) from platform_penalty pp
                     where pp.tenant_id = v.tenant_id and pp.violation_id = v.id) as penalty_count,
                   (select count(*) from platform_violation_remediation rr
                     where rr.tenant_id = v.tenant_id and rr.violation_id = v.id) as remediation_count,
                   (select count(*) from platform_violation_remediation rr
                     where rr.tenant_id = v.tenant_id and rr.violation_id = v.id
                       and rr.status not in ('VERIFIED')) as open_remediation_count
            from platform_violation v
            join shop s on s.tenant_id = v.tenant_id and s.id = v.shop_id
            join platform p on p.id = s.platform_id
            """
                + where
                + " order by v.occurred_at desc, v.id desc limit ? offset ?",
            pageParams.toArray());

    List<PlatformViolation> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toViolation(row));
    }

    return new PlatformViolationPage(
        items,
        total,
        safePage,
        safeSize,
        statusCounts.getOrDefault("PENDING", 0L),
        statusCounts.getOrDefault("IN_PROGRESS", 0L),
        statusCounts.getOrDefault("RECTIFIED", 0L),
        statusCounts.getOrDefault("APPEALING", 0L),
        statusCounts.getOrDefault("CLOSED", 0L),
        "🔴 影响档位是**派生**的（由处罚措施集合现算），**不落库**；"
            + "各状态计数覆盖**整个筛选结果集**（不是当前页）。");
  }

  /** 违规记录详情。跨租户与不存在返回逐字相同的 404。 */
  public PlatformViolationDetail violation(String violationId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    List<PlatformPenalty> penalties = penaltiesOf(tenant, id);
    List<PlatformViolationSubject> subjects = subjectsOf(tenant, id);
    List<PlatformViolationRemediation> remediations = remediationsOf(tenant, id);
    List<PlatformViolationEvidence> evidence = evidenceOf(tenant, id);
    return new PlatformViolationDetail(
        toViolation(row),
        penalties,
        subjects,
        remediations,
        evidence,
        "🔴 涉及商品 / 订单是**多对多**；整改凭证是**追加式**（不可改写、不可删）。");
  }

  private Record violationRow(UUID tenant, UUID id) {
    return sql.fetchOne(
        """
        select v.*, s.code as shop_code, s.name as shop_name, p.code as platform_code,
               (select count(*) from platform_violation_subject ss
                 where ss.tenant_id = v.tenant_id and ss.violation_id = v.id) as subject_count,
               (select count(*) from platform_penalty pp
                 where pp.tenant_id = v.tenant_id and pp.violation_id = v.id) as penalty_count,
               (select count(*) from platform_violation_remediation rr
                 where rr.tenant_id = v.tenant_id and rr.violation_id = v.id) as remediation_count,
               (select count(*) from platform_violation_remediation rr
                 where rr.tenant_id = v.tenant_id and rr.violation_id = v.id
                   and rr.status <> 'VERIFIED') as open_remediation_count
        from platform_violation v
        join shop s on s.tenant_id = v.tenant_id and s.id = v.shop_id
        join platform p on p.id = s.platform_id
        where v.tenant_id = ? and v.id = ?
        """,
        tenant,
        id);
  }

  /** 一行违规 → 传输模型（**影响档位在这里现算**）。 */
  private PlatformViolation toViolation(Record row) {
    List<String> measures =
        sql.fetch(
            """
            select measure from platform_penalty
             where tenant_id = ? and violation_id = ? order by effective_at desc, id
            """,
            row.get("tenant_id", UUID.class),
            row.get("id", UUID.class)).stream()
            .map(r -> r.get("measure", String.class))
            .toList();
    String impact = impactLevel(measures);
    String status = row.get("status", String.class);
    return new PlatformViolation(
        row.get("id", UUID.class).toString(),
        row.get("shop_id", UUID.class).toString(),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        row.get("platform_code", String.class),
        row.get("external_notice_id", String.class),
        row.get("source", String.class),
        sourceLabel(row.get("source", String.class)),
        row.get("violation_type", String.class),
        violationTypeLabel(row.get("violation_type", String.class)),
        row.get("title", String.class),
        row.get("description", String.class),
        time(row, "occurred_at"),
        time(row, "notified_at"),
        status,
        statusLabel(status),
        nextStatuses(status),
        time(row, "deadline"),
        impact,
        impactLabel(impact),
        measures,
        measures.stream().map(PlatformViolationRules::measureLabel).toList(),
        intOf(row, "subject_count"),
        intOf(row, "penalty_count"),
        intOf(row, "remediation_count"),
        intOf(row, "open_remediation_count"),
        time(row, "created_at"));
  }

  // ==================================================================
  // 登记（人工登记 + 同步接收，共用同一套幂等键）
  // ==================================================================

  /**
   * 人工登记一条违规（<b>source 恒为 MANUAL</b>，入参里根本没有 source 字段，服务端写死）。
   *
   * <p>🔴 <b>幂等</b>：命中 {@code (租户, 店铺, 平台通知 id)} ⇒ 原样返回既有记录（{@code created=false}），
   * <b>不产生第二条记录</b>、<b>不改写**既有内容</b>（改写是「登记笔误」的事，
   * 而平台后来下发了真正的内容时也不该被旧的人工记录盖住）。
   */
  public PlatformViolationRegistration register(PlatformViolationRegisterInput input) {
    UUID tenant = currentTenant();
    UUID shopId = requireUuid(input.shopId());
    requireShop(tenant, shopId);
    String noticeId = text(input.externalNoticeId());
    if (noticeId.isEmpty()) {
      throw unprocessable("平台通知 id（externalNoticeId）不能为空 —— 它是本模块的幂等锚点");
    }
    String type = text(input.violationType()).toUpperCase();
    if (!knownViolationType(type)) {
      throw unprocessable(
          "未知的违规类型：" + input.violationType() + "。可选：" + String.join(" / ", VIOLATION_TYPES));
    }
    String title = text(input.title());
    if (title.isEmpty()) {
      throw unprocessable("标题不能为空");
    }
    OffsetDateTime occurredAt = input.occurredAt() == null ? dbNow() : input.occurredAt();

    PlatformViolationDetail existing = findByNotice(tenant, shopId, noticeId);
    if (existing != null) {
      return new PlatformViolationRegistration(
          existing, false, "🔴 幂等命中：这条平台通知（" + noticeId + "）已经登记过了，返回既有记录，**不新建**。");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_violation
          (id, tenant_id, shop_id, external_notice_id, source, violation_type, title, description,
           occurred_at, notified_at, status, deadline, recorded_by, created_at, updated_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'PENDING',?::timestamptz,?,now(),now())
        on conflict (tenant_id, shop_id, external_notice_id) do nothing
        """,
        id,
        tenant,
        shopId,
        noticeId,
        "MANUAL",
        type,
        title,
        text(input.description()),
        occurredAt,
        input.notifiedAt(),
        input.deadline(),
        currentActor());
    PlatformViolationDetail stored = findByNotice(tenant, shopId, noticeId);
    if (stored == null) {
      throw unprocessable("登记失败：平台通知 id 冲突或写入被拒");
    }
    return new PlatformViolationRegistration(
        stored, true, "🔴 人工登记的记录 **source 恒为 MANUAL**，不冒充平台同步。");
  }

  /** 按平台通知 id 找一条违规（幂等命中用）。 */
  private PlatformViolationDetail findByNotice(UUID tenant, UUID shopId, String noticeId) {
    UUID id =
        fetchUuid(
            """
            select id from platform_violation
             where tenant_id = ? and shop_id = ? and external_notice_id = ?
            """,
            tenant,
            shopId,
            noticeId);
    if (id == null) {
      return null;
    }
    Record row = violationRow(tenant, id);
    if (row == null) {
      return null;
    }
    return new PlatformViolationDetail(
        toViolation(row),
        penaltiesOf(tenant, id),
        subjectsOf(tenant, id),
        remediationsOf(tenant, id),
        evidenceOf(tenant, id),
        "");
  }

  // ==================================================================
  // 同步入口（接收平台违规通知 · 幂等）
  // ==================================================================

  /**
   * 🔴 <b>同步入口</b>：接收一批平台违规通知。
   *
   * <p>平台违规通知是**平台推送**的形态，所以同步入口是「接收」而不是「拉取」。
   * 每条通知按 {@code external_notice_id} <b>幂等</b>：已存在 ⇒ 跳过（不新建、不改写），
   * 不存在 ⇒ 落违规记录 + 涉及主体 + 处罚措施。
   *
   * <p>🔴 每批同步落一条**同步运行记录**（取到几条 / 写了���条 / 跳过几条 / 失败几条）——
   * 「同步跑了没有、写了多少」是可查的事实，不是口头承诺。
   */
  public PlatformViolationSyncResult sync(String shopId, PlatformViolationSyncInput input) {
    UUID tenant = currentTenant();
    UUID shop = requireUuid(shopId);
    requireShop(tenant, shop);

    List<PlatformViolationNotice> notices =
        input == null || input.notices() == null ? List.of() : input.notices();
    if (notices.isEmpty()) {
      throw unprocessable("同步请求里没有平台通知（notices 不能为空）");
    }
    if (notices.size() > MAX_NOTICES_PER_SYNC) {
      throw unprocessable("一次最多接收 " + MAX_NOTICES_PER_SYNC + " 条平台通知");
    }

    UUID runId = UUID.randomUUID();
    OffsetDateTime startedAt = dbNow();
    sql.execute(
        """
        insert into platform_violation_sync_run
          (id, tenant_id, shop_id, status, started_at, finished_at, fetched_count, written_count,
           skipped_count, failed_count, reason, created_at)
        values (?,?,?,'RUNNING',?::timestamptz,null,0,0,0,0,'',now())
        """,
        runId,
        tenant,
        shop,
        startedAt);

    int written = 0;
    int skipped = 0;
    List<String> failures = new ArrayList<>();
    for (PlatformViolationNotice notice : notices) {
      try {
        if (ingestNotice(tenant, shop, notice)) {
          written++;
        } else {
          skipped++;
        }
      } catch (RuntimeException e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        failures.add(
            (text(notice.externalNoticeId()).isEmpty() ? "(缺通知 id)" : notice.externalNoticeId())
                + "："
                + reason);
      }
    }
    int failed = failures.size();
    String status = failed == 0 ? "SUCCEEDED" : "PARTIAL";
    OffsetDateTime finishedAt = dbNow();
    sql.execute(
        """
        update platform_violation_sync_run
           set status = ?, finished_at = ?::timestamptz, fetched_count = ?, written_count = ?,
               skipped_count = ?, failed_count = ?, reason = ?
         where tenant_id = ? and id = ?
        """,
        status,
        finishedAt,
        notices.size(),
        written,
        skipped,
        failed,
        failed == 0 ? "" : String.join("；", failures),
        tenant,
        runId);

    return new PlatformViolationSyncResult(
        runId.toString(),
        shop.toString(),
        notices.size(),
        written,
        skipped,
        failed,
        failures,
        "🔴 **幂等**：同一平台通知重复同步**不产生第二条记录**（skipped 就是重复的那些）。"
            + "source 恒为 PLATFORM_SYNC —— 同步来的**不冒充**人工登记。");
  }

  /** 落一条平台通知；命中幂等键返回 false（跳过）。 */
  private boolean ingestNotice(UUID tenant, UUID shop, PlatformViolationNotice notice) {
    String noticeId = text(notice.externalNoticeId());
    if (noticeId.isEmpty()) {
      throw unprocessable("平台通知 id（externalNoticeId）不能为空 —— 它是幂等锚点");
    }
    String type = text(notice.violationType()).toUpperCase();
    if (!knownViolationType(type)) {
      throw unprocessable("未知的违规类型：" + notice.violationType());
    }
    String title = text(notice.title());
    if (title.isEmpty()) {
      throw unprocessable("标题不能为空");
    }
    UUID existing =
        fetchUuid(
            "select id from platform_violation where tenant_id = ? and shop_id = ? and external_notice_id = ?",
            tenant,
            shop,
            noticeId);
    if (existing != null) {
      return false;
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime occurredAt = notice.occurredAt() == null ? dbNow() : notice.occurredAt();
    sql.execute(
        """
        insert into platform_violation
          (id, tenant_id, shop_id, external_notice_id, source, violation_type, title, description,
           occurred_at, notified_at, status, deadline, recorded_by, created_at, updated_at)
        values (?,?,?,?,'PLATFORM_SYNC',?,?,?,?::timestamptz,?::timestamptz,'PENDING',?::timestamptz,?,now(),now())
        on conflict (tenant_id, shop_id, external_notice_id) do nothing
        """,
        id,
        tenant,
        shop,
        noticeId,
        type,
        title,
        text(notice.description()),
        occurredAt,
        notice.notifiedAt(),
        notice.deadline(),
        currentActor());

    UUID stored =
        fetchUuid(
            "select id from platform_violation where tenant_id = ? and shop_id = ? and external_notice_id = ?",
            tenant,
            shop,
            noticeId);
    if (stored == null || !stored.equals(id)) {
      // 并发下别人先写进去了 ⇒ 幂等跳过
      return false;
    }

    if (notice.subjects() != null) {
      insertSubjects(tenant, stored, notice.subjects());
    }
    if (notice.penalties() != null) {
      for (PlatformPenaltyInput penalty : notice.penalties()) {
        insertPenalty(tenant, stored, shop, penalty);
      }
    }
    return true;
  }

  /** 同步运行记录清单（**真分页**）。 */
  public PlatformViolationSyncRunPage syncRuns(String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and r.shop_id = ?");
      params.add(filterShop);
    }
    long total =
        count(sql.fetchValue("select count(*) from platform_violation_sync_run r" + where, params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select r.*, s.code as shop_code
            from platform_violation_sync_run r
            join shop s on s.tenant_id = r.tenant_id and s.id = r.shop_id
            """
                + where
                + " order by r.started_at desc, r.id desc limit ? offset ?",
            pageParams.toArray());
    List<PlatformViolationSyncRun> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new PlatformViolationSyncRun(
              row.get("id", UUID.class).toString(),
              row.get("shop_id", UUID.class).toString(),
              row.get("shop_code", String.class),
              row.get("status", String.class),
              time(row, "started_at"),
              time(row, "finished_at"),
              intOf(row, "fetched_count"),
              intOf(row, "written_count"),
              intOf(row, "skipped_count"),
              intOf(row, "failed_count"),
              row.get("reason", String.class)));
    }
    return new PlatformViolationSyncRunPage(
        items, total, safePage, safeSize, "🔴 每批同步都留一条运行记录：取到 / 写入 / 跳过 / 失败各是几条。");
  }

  // ==================================================================
  // 处理状态流转
  // ==================================================================

  /** 违规处理状态流转（**非法转换在服务层就拒**，DB 再兜底）。 */
  public PlatformViolation transition(String violationId, PlatformViolationStatusInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    String target = text(input == null ? null : input.status()).toUpperCase();
    if (!knownStatus(target)) {
      throw unprocessable("未知的处理状态：" + (input == null ? null : input.status()));
    }
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String current = row.get("status", String.class);
    if (!canTransition(current, target)) {
      throw unprocessable(
          "处理状态不能从「"
              + statusLabel(current)
              + "」转到「"
              + statusLabel(target)
              + "」。合法转换："
              + (nextStatuses(current).isEmpty()
                  ? "（终态，不可再改）"
                  : nextStatuses(current).stream()
                      .map(PlatformViolationRules::statusLabel)
                      .toList()));
    }
    sql.execute(
        "update platform_violation set status = ?, updated_at = now() where tenant_id = ? and id = ?",
        target,
        tenant,
        id);
    return toViolation(violationRow(tenant, id));
  }

  // ==================================================================
  // 处罚措施
  // ==================================================================

  /** 记一条处罚措施。 */
  public PlatformPenalty addPenalty(String violationId, PlatformPenaltyInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    UUID shopId = row.get("shop_id", UUID.class);
    UUID penaltyId = insertPenalty(tenant, id, shopId, input);
    return penaltiesOf(tenant, id).stream()
        .filter(p -> p.penaltyId().equals(penaltyId.toString()))
        .findFirst()
        .orElseThrow(() -> unprocessable("处罚写入后读不回来"));
  }

  /** 落一条处罚（**只有扣款带金额**）。 */
  private UUID insertPenalty(UUID tenant, UUID violationId, UUID shopId, PlatformPenaltyInput input) {
    String measure = text(input == null ? null : input.measure()).toUpperCase();
    if (!knownMeasure(measure)) {
      throw unprocessable(
          "未知的处罚措施："
              + (input == null ? null : input.measure())
              + "。可选："
              + String.join(" / ", MEASURES));
    }
    BigDecimal amount = input.penaltyAmount();
    String currency = text(input.penaltyCurrency()).toUpperCase();
    if (MEASURE_DEDUCTION.equals(measure)) {
      if (amount == null) {
        throw unprocessable("扣款必须给出金额（penaltyAmount）");
      }
      if (currency.isEmpty()) {
        throw unprocessable("扣款必须给出币种（penaltyCurrency）");
      }
      // 🔴 Money 是唯一的金额入口：币种格式、负数、小数位都在这里被拒一次。
      Money money = new Money(amount, currency);
      amount = money.amount();
      currency = money.currency();
    } else {
      if (amount != null || !currency.isEmpty()) {
        throw unprocessable(
            "「"
                + measureLabel(measure)
                + "」不带金额 —— 只有「扣款」这一种措施有金额。"
                + "硬塞一个 0 进去会让「没扣款」和「扣款 0」变成同一件事。");
      }
    }
    OffsetDateTime effectiveAt = input.effectiveAt() == null ? dbNow() : input.effectiveAt();
    if (input.endsAt() != null && input.endsAt().isBefore(effectiveAt)) {
      throw unprocessable("结束时刻不能早于生效时刻");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_penalty
          (id, tenant_id, violation_id, shop_id, measure, penalty_amount, penalty_currency,
           effective_at, ends_at, status, note, created_at, updated_at)
        values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'ACTIVE',?,now(),now())
        """,
        id,
        tenant,
        violationId,
        shopId,
        measure,
        amount,
        currency.isEmpty() ? null : currency,
        effectiveAt,
        input.endsAt(),
        text(input.note()));
    return id;
  }

  /** 处罚清单（**真分页** + **按币种分组的合计**）。 */
  public PlatformPenaltyPage penalties(
      String shopId, String violationId, String measure, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where p.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and p.shop_id = ?");
      params.add(filterShop);
    }
    UUID filterViolation = parseFilterId(violationId);
    if (filterViolation != null) {
      where.append(" and p.violation_id = ?");
      params.add(filterViolation);
    }
    String measureValue = text(measure).toUpperCase();
    if (!measureValue.isEmpty()) {
      if (!knownMeasure(measureValue)) {
        throw unprocessable("未知的处罚措施：" + measure);
      }
      where.append(" and p.measure = ?");
      params.add(measureValue);
    }

    long total = count(sql.fetchValue("select count(*) from platform_penalty p" + where, params.toArray()));

    // 🔴 合计**按币种分组**：不同币种**不得直接相加**。
    List<PlatformPenaltyTotal> totals = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select p.penalty_currency as currency, sum(p.penalty_amount) as amount, count(*) as c
            from platform_penalty p
            """
                + where
                + " and p.penalty_amount is not null"
                + " group by p.penalty_currency order by p.penalty_currency",
            params.toArray())) {
      totals.add(
          new PlatformPenaltyTotal(
              row.get("currency", String.class),
              row.get("amount", BigDecimal.class),
              count(row.get("c"))));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            "select p.* from platform_penalty p"
                + where
                + " order by p.effective_at desc, p.id desc limit ? offset ?",
            pageParams.toArray());
    List<PlatformPenalty> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toPenalty(row));
    }

    return new PlatformPenaltyPage(
        items,
        total,
        safePage,
        safeSize,
        totals,
        "🔴 合计**按币种分组**，**没有**跨币种的总数 —— 要跨币种合计必须先有明确的折算口径，"
            + "本模块**不假设任何隐含汇率**。",
        "🔴 只有「扣款」这一种措施带金额，其余四种不带。");
  }

  private List<PlatformPenalty> penaltiesOf(UUID tenant, UUID violationId) {
    List<PlatformPenalty> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select p.* from platform_penalty p
             where p.tenant_id = ? and p.violation_id = ?
             order by p.effective_at desc, p.id desc
            """,
            tenant,
            violationId)) {
      result.add(toPenalty(row));
    }
    return result;
  }

  private PlatformPenalty toPenalty(Record row) {
    BigDecimal amount = row.get("penalty_amount", BigDecimal.class);
    String currency = row.get("penalty_currency", String.class);
    String moneyText = amount == null ? "" : new Money(amount, currency).display();
    return new PlatformPenalty(
        row.get("id", UUID.class).toString(),
        row.get("violation_id", UUID.class).toString(),
        row.get("measure", String.class),
        measureLabel(row.get("measure", String.class)),
        amount,
        currency,
        moneyText,
        time(row, "effective_at"),
        time(row, "ends_at"),
        row.get("status", String.class),
        row.get("note", String.class));
  }

  // ==================================================================
  // 涉及商品 / 订单（多对多 · 批量）
  // ==================================================================

  /** 批量挂涉及主体（**真的批量**，一条条循环会让「涉及 200 个商品」变成 200 次往返）。 */
  public PlatformViolationSubjectBatchResult attachSubjects(
      String violationId, List<PlatformViolationSubjectInput> inputs) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    if (inputs == null || inputs.isEmpty()) {
      throw unprocessable("至少要给一个涉及主体");
    }
    if (inputs.size() > MAX_SUBJECTS_PER_CALL) {
      throw unprocessable("一次最多挂 " + MAX_SUBJECTS_PER_CALL + " 个涉及主体");
    }
    List<String> skipped = new ArrayList<>();
    int inserted = insertSubjects(tenant, id, inputs, skipped);
    return new PlatformViolationSubjectBatchResult(
        id.toString(),
        inputs.size(),
        inserted,
        skipped.size(),
        skipped,
        "🔴 一次批量挂载；**已存在的同一主体会被跳过**（同一张违规里同一个主体只登记一次）。");
  }

  /** 批量落主体，返回真正插入的条数。 */
  private int insertSubjects(
      UUID tenant, UUID violationId, List<PlatformViolationSubjectInput> inputs) {
    return insertSubjects(tenant, violationId, inputs, new ArrayList<>());
  }

  private int insertSubjects(
      UUID tenant,
      UUID violationId,
      List<PlatformViolationSubjectInput> inputs,
      List<String> skippedRefs) {
    int inserted = 0;
    for (PlatformViolationSubjectInput input : inputs) {
      String type = text(input.subjectType()).toUpperCase();
      if (!knownSubjectType(type)) {
        throw unprocessable("未知的主体类型：" + input.subjectType());
      }
      String ref = text(input.subjectRef());
      if (ref.isEmpty()) {
        throw unprocessable("主体引用（subjectRef）不能为空");
      }
      UUID skuId = parseUuid(input.skuId());
      UUID listingId = parseUuid(input.listingId());
      String orderRef = text(input.orderRef());
      if ("SKU".equals(type) && skuId == null) {
        throw unprocessable("主体类型是 SKU 时必须给出 skuId");
      }
      if ("LISTING".equals(type) && listingId == null) {
        throw unprocessable("主体类型是 LISTING 时必须给出 listingId");
      }
      if ("ORDER".equals(type) && orderRef.isEmpty()) {
        throw unprocessable("主体类型是 ORDER 时必须给出 orderRef");
      }
      if (input.affectedQuantity() != null && input.affectedQuantity().signum() < 0) {
        throw unprocessable("受影响数量不能为负");
      }
      int affected =
          sql.execute(
              """
              insert into platform_violation_subject
                (id, tenant_id, violation_id, subject_type, subject_ref, sku_id, listing_id,
                 order_ref, affected_quantity, note, created_at)
              values (?,?,?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id, violation_id, subject_type, subject_ref) do nothing
              """,
              UUID.randomUUID(),
              tenant,
              violationId,
              type,
              ref,
              skuId,
              listingId,
              orderRef.isEmpty() ? null : orderRef,
              input.affectedQuantity(),
              text(input.note()));
      if (affected > 0) {
        inserted++;
      } else {
        skippedRefs.add(ref);
      }
    }
    return inserted;
  }

  private List<PlatformViolationSubject> subjectsOf(UUID tenant, UUID violationId) {
    List<PlatformViolationSubject> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from platform_violation_subject
             where tenant_id = ? and violation_id = ? order by subject_type, subject_ref
            """,
            tenant,
            violationId)) {
      result.add(
          new PlatformViolationSubject(
              row.get("id", UUID.class).toString(),
              row.get("violation_id", UUID.class).toString(),
              row.get("subject_type", String.class),
              subjectTypeLabel(row.get("subject_type", String.class)),
              row.get("subject_ref", String.class),
              idText(row.get("sku_id", UUID.class)),
              idText(row.get("listing_id", UUID.class)),
              row.get("order_ref", String.class),
              row.get("affected_quantity", BigDecimal.class),
              row.get("note", String.class)));
    }
    return result;
  }

  private static String idText(UUID id) {
    return id == null ? null : id.toString();
  }

  // ==================================================================
  // 批量定位受影响 listing 与库存
  // ==================================================================

  /**
   * 🔴 <b>批量定位</b>：一次查出受影响的 listing，<b>一次</b>调 07 库存的可售量接口。
   *
   * <p>蓝图加粗句「违规常涉及多个商品，需批量定位受影响 listing 与库存」——
   * 「批量」在这里是<b>真的批量</b>：一条 SQL 取 listing，<b>一次</b> HTTP 取库存，
   * <b>不是</b>一个 SKU 一次调用。
   */
  public PlatformViolationAffected affected(
      String violationId, String warehouseId, String bearer) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }

    AffectedScope scope = loadAffectedScope(tenant, id);
    List<PlatformViolationAffectedListing> listings = new ArrayList<>();
    for (AffectedListing listing : scope.listings()) {
      listings.add(
          new PlatformViolationAffectedListing(
              listing.id().toString(),
              listing.externalItemId(),
              listing.title(),
              listing.status(),
              listingStatusLabel(listing.status()),
              listing.platformStockState(),
              listing.sourceSubjectType(),
              listing.sourceSubjectRef()));
    }

    boolean inventoryAvailable = true;
    String inventoryNote;
    List<PlatformViolationAffectedInventory> inventoryRows = new ArrayList<>();
    UUID warehouse = parseWarehouseId(warehouseId);
    if (scope.skuIds().isEmpty()) {
      inventoryNote = "这张违规没有涉及商品（SKU），没有可查的库存。";
    } else {
      try {
        // 🔴 一次调用：整个 sku 集合一起交给 07，**不一条条循环**。
        SellableQuantityQueryPort.SellablePage page =
            inventory.sellable(warehouse, new ArrayList<>(scope.skuIds()), bearer);
        Map<UUID, SellableQuantityQueryPort.SellableRow> bySku = new LinkedHashMap<>();
        for (SellableQuantityQueryPort.SellableRow sellable : page.rows()) {
          bySku.put(sellable.skuId(), sellable);
        }
        for (UUID skuId : scope.skuIds()) {
          SellableQuantityQueryPort.SellableRow sellable = bySku.get(skuId);
          if (sellable == null) {
            inventoryRows.add(
                new PlatformViolationAffectedInventory(
                    skuId.toString(),
                    null,
                    null,
                    false,
                    "07 库存里没有这个 SKU 的可售量行（**不是「可售 0」**）"));
          } else {
            inventoryRows.add(
                new PlatformViolationAffectedInventory(
                    sellable.skuId().toString(),
                    sellable.skuCode(),
                    sellable.sellable(),
                    true,
                    "可售量口径由 07 库存定义（已扣安全缓冲），本模块**原样采用**，不自己重算"));
          }
        }
        inventoryNote =
            "🔴 可售量来自 **07 库存**（跨服务，**只经授权只读 API**）；"
                + "口径是那边的公式，本模块**原样采用**、**不自己重算一遍**。";
      } catch (ResponseStatusException e) {
        // 🔴 **查不到 ≠ 没有货**：上游读不到时如实报「读不到」，
        //    绝不把它折成「可售 0」或「全部可售」。
        inventoryAvailable = false;
        inventoryNote =
            "🔴 07 库存读不到（"
                + (e.getReason() == null ? e.getMessage() : e.getReason())
                + "）⇒ 库存这一段是**「读不到」**，**不是「没有库存」**。受影响的 listing 仍然可信。";
      }
    }

    return new PlatformViolationAffected(
        id.toString(),
        scope.subjectCount(),
        listings.size(),
        listings,
        inventoryAvailable,
        inventoryRows,
        inventoryNote,
        "🔴 受影响 listing 一次 SQL 查出、库存**一次**调用取回 —— **批量**，不是逐条循环。");
  }

  /** 受影响范围（listing 集合 + sku 集合）。 */
  private record AffectedScope(
      List<AffectedListing> listings, Set<UUID> skuIds, int subjectCount) {}

  /** 受影响的一个平台商品（服务层内部形状）。 */
  private record AffectedListing(
      UUID id,
      String externalItemId,
      String title,
      String status,
      String platformStockState,
      String sourceSubjectType,
      String sourceSubjectRef) {}

  /**
   * 🔴 一次 SQL 把受影响的 listing 与它们的内部 SKU 映射一起取回（**不 N+1**）。
   *
   * <p>两类来源都覆盖：
   * <ul>
   *   <li>违规直接点名了某个 listing ⇒ 它就是受影响的；</li>
   *   <li>违规点名了某个 SKU ⇒ 挂着这个 SKU 的那些 listing 同样受影响
   *       （{@code listing_variant.internal_sku_id} 是 catalog 的逻辑 id，跨服务不建 FK）。</li>
   * </ul>
   */
  private AffectedScope loadAffectedScope(UUID tenant, UUID violationId) {
    List<Record> subjects =
        sql.fetch(
            """
            select subject_type, subject_ref, sku_id, listing_id
            from platform_violation_subject
            where tenant_id = ? and violation_id = ? order by subject_type, subject_ref
            """,
            tenant,
            violationId);

    Set<UUID> skuIds = new LinkedHashSet<>();
    Set<UUID> listingIds = new LinkedHashSet<>();
    Map<UUID, String[]> listingSource = new LinkedHashMap<>();
    Map<UUID, String> skuSource = new LinkedHashMap<>();
    for (Record subject : subjects) {
      String type = subject.get("subject_type", String.class);
      String ref = subject.get("subject_ref", String.class);
      UUID skuId = subject.get("sku_id", UUID.class);
      UUID listingId = subject.get("listing_id", UUID.class);
      if (skuId != null) {
        skuIds.add(skuId);
        skuSource.putIfAbsent(skuId, ref);
      }
      if (listingId != null) {
        listingIds.add(listingId);
        listingSource.putIfAbsent(listingId, new String[] {type, ref});
      }
    }

    if (listingIds.isEmpty() && skuIds.isEmpty()) {
      return new AffectedScope(List.of(), skuIds, subjects.size());
    }

    // 🔴 一条 SQL：listing + 变体映射（一个 listing 可能有多行变体 ⇒ 按 listing 去重）。
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    StringBuilder condition = new StringBuilder(" l.tenant_id = ? and (");
    List<Object> ids = new ArrayList<>();
    if (!listingIds.isEmpty()) {
      condition.append("l.id in (");
      condition.append(listingIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse(""));
      condition.append(")");
      for (UUID id : listingIds) {
        ids.add(id);
      }
    }
    if (!skuIds.isEmpty()) {
      if (!listingIds.isEmpty()) {
        condition.append(" or ");
      }
      condition.append("exists (select 1 from listing_variant lv where lv.tenant_id = l.tenant_id");
      condition.append(" and lv.listing_id = l.id and lv.internal_sku_id in (");
      condition.append(skuIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse(""));
      condition.append("))");
      for (UUID id : skuIds) {
        ids.add(id);
      }
    }
    condition.append(")");
    params.addAll(ids);

    List<Record> rows =
        sql.fetch(
            """
            select l.id, l.external_item_id, l.title, l.status, l.platform_stock_state,
                   lv.internal_sku_id
            from listing l
            left join listing_variant lv on lv.tenant_id = l.tenant_id and lv.listing_id = l.id
            where """
                + condition
                + " order by l.external_item_id",
            params.toArray());

    // 一个 listing 可能有多行变体 ⇒ 按 listing 去重，顺便把「由 SKU 反查出来的变体 SKU」补进库存集合。
    Map<UUID, Record> byListing = new LinkedHashMap<>();
    Map<UUID, String> skuOfListing = new LinkedHashMap<>();
    for (Record row : rows) {
      UUID listingId = row.get("id", UUID.class);
      byListing.putIfAbsent(listingId, row);
      UUID internalSku = row.get("internal_sku_id", UUID.class);
      if (internalSku != null) {
        skuIds.add(internalSku);
        skuOfListing.putIfAbsent(listingId, skuSource.getOrDefault(internalSku, internalSku.toString()));
      }
    }

    List<AffectedListing> listings = new ArrayList<>();
    for (Record row : byListing.values()) {
      UUID listingId = row.get("id", UUID.class);
      String[] source = listingSource.get(listingId);
      String subjectType = source == null ? "SKU" : source[0];
      String subjectRef = source == null ? skuOfListing.getOrDefault(listingId, "") : source[1];
      listings.add(
          new AffectedListing(
              listingId,
              row.get("external_item_id", String.class),
              row.get("title", String.class),
              row.get("status", String.class),
              row.get("platform_stock_state", String.class),
              subjectType,
              subjectRef));
    }
    return new AffectedScope(listings, skuIds, subjects.size());
  }

  /** listing 状态的人话。 */
  private static String listingStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "草稿";
      case "ON_SALE" -> "在售";
      case "UNDER_REVIEW" -> "平台审核中";
      case "REJECTED" -> "平台驳回";
      case "DELISTED_BY_US" -> "主动下架";
      case "DELISTED_BY_PLATFORM" -> "平台下架";
      case "BLOCKED" -> "被封禁";
      default -> status == null ? "" : status;
    };
  }

  // ==================================================================
  // 批量下架（下游 4 刊登 · 4.1.1.1 批量下架 —— **已交付，就在本服务**）
  // ==================================================================

  /**
   * 把受影响的 listing **真的下架**。
   *
   * <p>🔴 复用 4.1.1.1 的批量下架（{@code ListingService#batch} 的 DELIST 分支）：
   * 不是在本模块里写一条「已下架」的记录，而是**真的改 listing 状态**（并由 DB 触发器留状态历史）。
   */
  public PlatformViolationDelistResult delist(String violationId, String bearer) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    AffectedScope scope = loadAffectedScope(tenant, id);
    List<UUID> listingIds = new ArrayList<>();
    for (AffectedListing listing : scope.listings()) {
      listingIds.add(listing.id());
    }
    if (listingIds.isEmpty()) {
      return new PlatformViolationDelistResult(
          id.toString(),
          0,
          0,
          0,
          List.of(),
          "NOT_APPLICABLE",
          "这张违规没有定位到任何 listing（没有涉及商品，或涉及的 listing 不在本租户的数据范围内）",
          "",
          "");
    }
    ListingBatchResult batch =
        listings.batch(new ListingBatchRequest("DELIST", listingIds, null, null, null));
    return new PlatformViolationDelistResult(
        id.toString(),
        batch.requestedCount(),
        batch.affectedCount(),
        batch.skipCount(),
        batch.skippedIds().stream().map(UUID::toString).toList(),
        batch.outcome(),
        batch.reason(),
        batch.ownerModule(),
        batch.taskId() == null ? "" : batch.taskId().toString());
  }

  // ==================================================================
  // 整改跟踪
  // ==================================================================

  /** 记一条整改要求。 */
  public PlatformViolationRemediation addRemediation(
      String violationId, PlatformViolationRemediationInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(violationId);
    Record row = violationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String requirement = text(input == null ? null : input.requirement());
    if (requirement.isEmpty()) {
      throw unprocessable("整改要求内容不能为空");
    }
    UUID remediationId = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_violation_remediation
          (id, tenant_id, violation_id, requirement, due_at, status, owner_ref, created_at, updated_at)
        values (?,?,?,?,?::timestamptz,'OPEN',?,now(),now())
        """,
        remediationId,
        tenant,
        id,
        requirement,
        input.dueAt(),
        text(input.ownerRef()));
    return remediationsOf(tenant, id).stream()
        .filter(r -> r.remediationId().equals(remediationId.toString()))
        .findFirst()
        .orElseThrow(() -> unprocessable("整改要求写入后读不回来"));
  }

  /** 整改状态流转（**非法转换在服务层就拒**，DB 再兜底）。 */
  public PlatformViolationRemediation transitionRemediation(
      String remediationId, PlatformViolationRemediationStatusInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(remediationId);
    String target = text(input == null ? null : input.status()).toUpperCase();
    if (!knownRemediationStatus(target)) {
      throw unprocessable("未知的整改状态：" + (input == null ? null : input.status()));
    }
    Record row = remediationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String current = row.get("status", String.class);
    if (!canTransitionRemediation(current, target)) {
      throw unprocessable(
          "整改状态不能从「"
              + remediationStatusLabel(current)
              + "」转到「"
              + remediationStatusLabel(target)
              + "」。合法转换："
              + (nextRemediationStatuses(current).isEmpty()
                  ? "（终态，不可再改）"
                  : nextRemediationStatuses(current).stream()
                      .map(PlatformViolationRules::remediationStatusLabel)
                      .toList()));
    }
    OffsetDateTime now = dbNow();
    // 🔴 「已提交」必须真的提交过、「已验收」必须真的验收过（DB 层 CHECK 也会钉一次）。
    sql.execute(
        """
        update platform_violation_remediation
           set status = ?,
               completed_at = case when ? = 'SUBMITTED' then ?::timestamptz else completed_at end,
               verified_at = case when ? = 'VERIFIED' then ?::timestamptz else verified_at end,
               updated_at = now()
         where tenant_id = ? and id = ?
        """,
        target,
        target,
        now,
        target,
        now,
        tenant,
        id);
    return remediationsOf(tenant, row.get("violation_id", UUID.class)).stream()
        .filter(r -> r.remediationId().equals(id.toString()))
        .findFirst()
        .orElseThrow(() -> unprocessable("整改状态更新后读不回来"));
  }

  /** 整改要求清单（**真分页** + 真筛选）。 */
  public PlatformViolationRemediationPage remediations(
      String shopId, String violationId, String status, String overdueOnly, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(
          " and exists (select 1 from platform_violation v where v.tenant_id = r.tenant_id"
              + " and v.shop_id = ? and v.id = r.violation_id)");
      params.add(filterShop);
    }
    UUID filterViolation = parseFilterId(violationId);
    if (filterViolation != null) {
      where.append(" and r.violation_id = ?");
      params.add(filterViolation);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownRemediationStatus(statusValue)) {
        throw unprocessable("未知的整改状态：" + status);
      }
      where.append(" and r.status = ?");
      params.add(statusValue);
    }
    if ("true".equalsIgnoreCase(text(overdueOnly))) {
      where.append(" and r.due_at is not null and r.due_at < now() and r.status <> 'VERIFIED'");
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from platform_violation_remediation r" + where, params.toArray()));
    long overdue =
        count(
            sql.fetchValue(
                "select count(*) from platform_violation_remediation r"
                    + where
                    + " and r.due_at is not null and r.due_at < now() and r.status <> 'VERIFIED'",
                params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select r.*,
                   (select count(*) from platform_violation_evidence e
                     where e.tenant_id = r.tenant_id and e.remediation_id = r.id) as evidence_count
            from platform_violation_remediation r
            """
                + where
                + " order by r.due_at nulls last, r.created_at desc, r.id desc limit ? offset ?",
            pageParams.toArray());
    List<PlatformViolationRemediation> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toRemediation(row));
    }
    return new PlatformViolationRemediationPage(
        items,
        total,
        safePage,
        safeSize,
        overdue,
        "🔴 「已逾期」是**派生**的（已过期限且尚未验收），**不落库** —— "
            + "存成一列就必须靠定时任务刷新，而定时任务漏跑一次就会给出过期的结论。");
  }

  private Record remediationRow(UUID tenant, UUID id) {
    return sql.fetchOne(
        """
        select r.*,
               (select count(*) from platform_violation_evidence e
                 where e.tenant_id = r.tenant_id and e.remediation_id = r.id) as evidence_count
        from platform_violation_remediation r
        where r.tenant_id = ? and r.id = ?
        """,
        tenant,
        id);
  }

  private List<PlatformViolationRemediation> remediationsOf(UUID tenant, UUID violationId) {
    List<PlatformViolationRemediation> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select r.*,
                   (select count(*) from platform_violation_evidence e
                     where e.tenant_id = r.tenant_id and e.remediation_id = r.id) as evidence_count
            from platform_violation_remediation r
            where r.tenant_id = ? and r.violation_id = ?
            order by r.due_at nulls last, r.created_at desc, r.id desc
            """,
            tenant,
            violationId)) {
      result.add(toRemediation(row));
    }
    return result;
  }

  private PlatformViolationRemediation toRemediation(Record row) {
    OffsetDateTime dueAt = time(row, "due_at");
    String status = row.get("status", String.class);
    boolean overdue =
        dueAt != null && !"VERIFIED".equals(status) && dueAt.isBefore(dbNow());
    return new PlatformViolationRemediation(
        row.get("id", UUID.class).toString(),
        row.get("violation_id", UUID.class).toString(),
        row.get("requirement", String.class),
        dueAt,
        overdue,
        status,
        remediationStatusLabel(status),
        nextRemediationStatuses(status),
        row.get("owner_ref", String.class),
        time(row, "completed_at"),
        time(row, "verified_at"),
        intOf(row, "evidence_count"),
        time(row, "created_at"));
  }

  // ==================================================================
  // 整改凭证（append-only）
  // ==================================================================

  /** 追加一条整改凭证（**不可改写、不可删**）。 */
  public PlatformViolationEvidence addEvidence(
      String remediationId, PlatformViolationEvidenceInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(remediationId);
    Record row = remediationRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String type = text(input == null ? null : input.evidenceType()).toUpperCase();
    if (!Set.of("DOCUMENT", "IMAGE", "VIDEO", "LINK", "NOTE").contains(type)) {
      throw unprocessable(
          "未知的凭证类型："
              + (input == null ? null : input.evidenceType())
              + "。可选：DOCUMENT / IMAGE / VIDEO / LINK / NOTE");
    }
    String ref = text(input.evidenceRef());
    String summary = text(input.summary());
    if (ref.isEmpty() && summary.isEmpty()) {
      throw unprocessable("整改凭证至少要给一个引用（evidenceRef）或一句说明（summary）");
    }
    UUID violationId = row.get("violation_id", UUID.class);
    OffsetDateTime submittedAt = input.submittedAt() == null ? dbNow() : input.submittedAt();
    UUID evidenceId = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_violation_evidence
          (id, tenant_id, violation_id, remediation_id, evidence_type, evidence_ref, summary,
           submitted_by, submitted_at, created_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,now())
        """,
        evidenceId,
        tenant,
        violationId,
        id,
        type,
        ref,
        summary,
        currentActor(),
        submittedAt);
    return evidenceOf(tenant, violationId).stream()
        .filter(e -> e.evidenceId().equals(evidenceId.toString()))
        .findFirst()
        .orElseThrow(() -> unprocessable("整改凭证写入后读不回来"));
  }

  private List<PlatformViolationEvidence> evidenceOf(UUID tenant, UUID violationId) {
    List<PlatformViolationEvidence> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from platform_violation_evidence
             where tenant_id = ? and violation_id = ? order by submitted_at desc, id desc
            """,
            tenant,
            violationId)) {
      result.add(
          new PlatformViolationEvidence(
              row.get("id", UUID.class).toString(),
              row.get("remediation_id", UUID.class).toString(),
              row.get("violation_id", UUID.class).toString(),
              row.get("evidence_type", String.class),
              row.get("evidence_ref", String.class),
              row.get("summary", String.class),
              time(row, "submitted_at")));
    }
    return result;
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private static UUID requireUuid(String raw) {
    UUID parsed = parseUuid(raw);
    if (parsed == null) {
      // 🔴 路径参数非法 ⇒ 404（跨租户与不存在逐字相同），不泄露「这个 id 格式对不对」。
      throw notFound();
    }
    return parsed;
  }

  /** 解析时间入参；空 ⇒ null，非法 ⇒ 422。 */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法的 ISO 时间");
    }
  }

  /**
   * 🔴 目标仓库入参：空 ⇒ null（由 07 按调用方可见范围取全量）；
   *   给了但不是合法 UUID ⇒ **422**（不能静默当成「全部仓库」）。
   */
  private static UUID parseWarehouseId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    if (parsed == null) {
      throw unprocessable("warehouseId 不是合法的 UUID");
    }
    return parsed;
  }
}
