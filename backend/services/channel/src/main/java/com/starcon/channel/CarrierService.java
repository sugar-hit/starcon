package com.starcon.channel;

import static com.starcon.channel.CarrierModels.*;

import com.starcon.channel.CarrierModels.CarrierAvailability;
import com.starcon.tenancy.Ids;
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
 * 10.1.1.1 承运商列表。
 *
 * <p>蓝图：「管理承运商（快递、专线、邮政、FBA 承运、末端派送）：接口类型、账号、
 * 覆盖国家、时效等级、支持业务类型（自发货/退货/FBA 入仓）」，
 * 输出「承运商档案 + 可用性状态」。
 *
 * <p>🔴 本类唯一不能妥协的约束是加粗那句：**承运商是往来单位的一种角色
 * （实体目录 §3.5），不在此处重复建档**。所以：
 *
 * <ol>
 *   <li>本服务<b>没有任何建承运商主档的端点</b>。身份（名称 / 编码 / 公司类型 /
 *       国家 / 联系方式）归 catalog 3.6.1.1，CARRIER 角色归 catalog 3.6.1.2，
 *       接口类型与账号归 catalog 的 {@code party_carrier_profile} —— 三样都
 *       <b>只读快照</b>，写端点一个字段都不接受（见 {@link SaveCarrierRequest}）；
 *   <li>本服务拥有的是<b>物流侧的身份没有、但物流侧需要的东西</b>：承运商类别、
 *       时效等级、覆盖国家、支持业务类型、凭据引用与可用性判定。
 * </ol>
 *
 * <p>数据来源纪律：承运商身份<b>只</b>经 {@link CarrierProfileQueryPort} 读 catalog
 * 的授权 HTTP API 落成本服务投影表，本类<b>一条 SQL 都不碰 catalog 的库</b>
 * （AGENTS.md 禁止跨服务 SQL / FK / 共享视图）。
 */
@Service
public class CarrierService {

  private final DSLContext sql;
  private final CarrierProfileQueryPort upstream;

  public CarrierService(DSLContext sql, CarrierProfileQueryPort upstream) {
    this.sql = sql;
    this.upstream = upstream;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /**
   * 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>。
   *
   * <p>不给「拿别的租户的 id 探测存在性」留信息面 —— 与 10.3.2.1 同款口径。
   */
  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "承运商不存在");
  }

  /**
   * 可用性的 SQL 表达式。
   *
   * <p>与 {@link CarrierAvailability#of} 逐条对应。写成 SQL 是为了让<b>按可用性筛选
   * 与按可用性统计都是真的</b>：拉全量在 Java 里判一遍既会把 N+1 次判断塞进
   * 每一行，又会让「筛出来的 3 条」这个数字不可信。
   */
  private static final String AVAILABILITY_SQL =
      "case when party_usable = false then 'PARTY_BLOCKED'"
          + " when readiness <> 'READY' then readiness"
          + " when interface_type in ('WEB_PORTAL','EMAIL') then 'MANUAL_ONLY'"
          + " else 'AVAILABLE' end";

  private static final String SELECT_COLUMNS =
      "id,role_id,party_id,party_code,party_name,party_status,party_usable,role_status,"
          + "interface_type,account_ref,service_capabilities,carrier_category,service_tier,"
          + "secret_ref,secret_masked,readiness,note,version,synced_at,updated_at";

  // ==================================================================
  // 能力声明：未交付的段显式 available=false + ownerModule + 影响
  // ==================================================================

  public CarrierCapabilities capabilities() {
    List<CarrierCapabilityDimension> dimensions =
        List.of(
            new CarrierCapabilityDimension(
                "carrier_identity",
                "承运商身份（往来单位 + CARRIER 角色 + 接口类型 / 账号）",
                true,
                "上游 catalog 3.6.1.1 V27 / 3.6.1.2 V30，经 CarrierProfileQueryPort 只读投影",
                "",
                "身份主权不在本服务，改名称 / 编码 / 接口类型请回 /partners"),
            new CarrierCapabilityDimension(
                "coverage_countries",
                "覆盖国家（多值，可按国家筛选）",
                true,
                "本服务 V134 carrier_country_coverage（每国一行，不是逗号串）",
                "",
                "决定这个承运商送得到哪些国家，缺失时渠道无法限定国家"),
            new CarrierCapabilityDimension(
                "business_scope",
                "支持业务类型（自发货 / 退货 / FBA 入仓）",
                true,
                "本服务 V134 carrier_business_scope（每类一行）",
                "",
                "下游按业务类型挑承运商；缺了就只能靠人工判断"),
            new CarrierCapabilityDimension(
                "service_tier",
                "时效等级（标准 / 加急 / 优先）",
                true,
                "本服务 V134 carrier_logistics_profile.service_tier",
                "",
                "渠道配置按时效等级选线路的依据"),
            new CarrierCapabilityDimension(
                "credential_reference",
                "账号凭据（只存密钥管理器引用 + 掩码）",
                true,
                "本服务 V134 secret_ref / secret_masked，DB 层 CHECK 拒非引用形状",
                "",
                "真实凭据从不落库；未登记引用时系统对接拿不到鉴权材料"),
            new CarrierCapabilityDimension(
                "availability_status",
                "可用性状态（由凭据 / 覆盖 / 业务类型 / 上游可用性算出）",
                true,
                "DB 触发器 guard_carrier_logistics_readiness 重算 + 服务层合成",
                "",
                "不接受手工指定；绕过服务层直接写 SQL 改状态同样被拒"),
            new CarrierCapabilityDimension(
                "channel_configuration",
                "渠道配置（所属渠道 / 计费方式 / 附加费 / 申报限制 / 保价规则）",
                false,
                "下游 10.1.1.2 渠道配置未开发",
                "10.1.1.2",
                "本模块只交付承运商侧；计费与限制无处配置，运费试算与自动匹配尚不可用"),
            new CarrierCapabilityDimension(
                "transit_time_limits",
                "渠道时效与限制（预计到达 / 截单时间 / 禁运品类）",
                false,
                "10.1.1.3 时效与限制未开发",
                "10.1.1.3",
                "无法提示某渠道是否会错过截单或禁运品类，只能靠经验"),
            new CarrierCapabilityDimension(
                "rate_calculation",
                "运费计算与比价",
                false,
                "10.1.2.1 / 10.1.2.2 未开发",
                "10.1.2.1",
                "承运商档案齐备但算不出运费，渠道选择仍靠人工询价"));
    return new CarrierCapabilities(
        dimensions,
        List.of("EXPRESS_COURIER", "LINE_HAUL", "POSTAL", "FBA_CARRIER", "LAST_MILE"),
        List.of("STANDARD", "EXPRESS", "PRIORITY"),
        List.of("OWN_FULFILLMENT", "RETURN", "FBA_INBOUND"),
        coveredCountries(),
        List.of("READY", "NOT_REGISTERED", "NO_CREDENTIAL", "NO_COVERAGE", "NO_BUSINESS_SCOPE"),
        List.of("AVAILABLE", "MANUAL_ONLY", "PARTY_BLOCKED"),
        "承运商身份只经 catalog 的授权 HTTP API 读取（V27 party + V30 party_carrier_profile）；"
            + "上游不可达时本模块返回 503，绝不返回空承运商列表冒充「这个租户没有承运商」。",
        "承运商是往来单位的一种角色，不在此处重复建档：本服务不提供建承运商身份的端点，"
            + "身份字段全部是只读快照。");
  }

  /** 已被登记过的覆盖国家（真实 DISTINCT 查询，喂给页面上的国家筛选框）。 */
  private List<String> coveredCountries() {
    return sql.fetch(
            "select distinct country_code from carrier_country_coverage"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " order by country_code")
        .map(r -> r.get(0, String.class));
  }

  // ==================================================================
  // 同步：把上游的承运商身份投影成本服务的物流侧行
  // ==================================================================

  /**
   * 同步承运商身份。
   *
   * <p>唯一会写「上游快照」那几列的入口 —— DB 层 {@code guard_carrier_snapshot_refresh}
   * 要求每次改快照都必须推进 {@code synced_at}，业务端点想改也改不动。
   *
   * <p>同步<b>不碰</b>物流侧字段（类别 / 时效 / 覆盖 / 业务类型 / 凭据）：
   * 那几个是本模块的主权，上游没有依据推出来，凭空给个默认值正是
   * 「硬编码常量当数据源」。所以同步建出来的行 {@code readiness=NOT_REGISTERED} ——
   * 如实表达「这个承运商已被上游登记，但物流侧还没配」。
   *
   * <p>重跑幂等：按 {@code (tenant_id, role_id)} upsert，且**不覆盖**物流侧字段
   * 与 {@code note}（重跑同步绝不能把运营登记过的配置悄悄撤掉）。
   */
  public SyncCarriersResult syncCarriers(int page, int size, String bearer) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 100 : Math.min(size, 200);
    var fetched = upstream.carrierParties(p, s, bearer);

    UUID tenant = currentTenant();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int created = 0;
    int refreshed = 0;
    int skipped = 0;

    for (var party : fetched.items()) {
      // 列表是按 roleType=CARRIER 筛的，但仍然逐个回读角色档案确认：
      // 「挂着 CARRIER 角色」与「建了承运商角色档案」是两件事（上游的
      // profileFound=false 就是后者没做）。**档案没建时如实跳过并计数** ——
      // 那一行连「怎么对接」都不知道，造出来就得靠猜，而 interface_type
      // 正是可用性判定的输入之一，猜出来的判定是假的。
      var profile = upstream.carrierProfile(party.id(), bearer);
      if (profile.roleId() == null || !profile.profileFound()) {
        skipped++;
        continue;
      }

      var prior =
          sql.fetchOne(
              "select id from carrier_logistics_profile where tenant_id=? and role_id=?",
              tenant,
              profile.roleId());
      boolean existed = prior != null;

      // interface_type / account_ref 原样取自上游档案，不做兜底猜测。
      String interfaceType = text(profile.interfaceType());
      String accountRef = text(profile.accountRef());

      sql.execute(
          "insert into carrier_logistics_profile"
              + " (id,tenant_id,role_id,party_id,party_code,party_name,party_status,"
              + "  party_usable,role_status,interface_type,account_ref,service_capabilities,"
              + "  readiness,synced_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?::text[],'NOT_REGISTERED',?::timestamptz)"
              + " on conflict (tenant_id,role_id) do update set"
              + "  party_name = excluded.party_name,"
              + "  party_status = excluded.party_status,"
              + "  party_usable = excluded.party_usable,"
              + "  role_status = excluded.role_status,"
              + "  interface_type = excluded.interface_type,"
              + "  account_ref = excluded.account_ref,"
              + "  service_capabilities = excluded.service_capabilities,"
              + "  synced_at = excluded.synced_at,"
              + "  updated_at = excluded.synced_at",
          prior == null ? Ids.next() : prior.get(0, UUID.class),
          tenant,
          profile.roleId(),
          party.id(),
          party.code(),
          party.name(),
          text(party.status()).isEmpty() ? "ACTIVE" : party.status(),
          party.usable(),
          text(profile.roleStatus()).isEmpty() ? "ACTIVE" : profile.roleStatus(),
          interfaceType,
          accountRef,
          postArray(profile.serviceCapabilities()),
          now);

      if (existed) {
        refreshed++;
      } else {
        created++;
      }
    }

    return new SyncCarriersResult(
        fetched.items().size(),
        created,
        refreshed,
        skipped,
        now,
        "承运商身份来自 catalog 3.6.1.1 / 3.6.1.2 的授权 API；其中 "
            + skipped
            + " 个主体还没有建承运商角色档案（接口类型未知），已如实跳过，没有拿猜出来的值造行。"
            + "物流侧档案（类别 / 时效 / 覆盖 / 业务类型 / 凭据）需运营登记，同步不会覆盖。");
  }

  /** String[] 绑定：空集合要显式绑成空数组，否则 PG 推不出类型。 */
  private static String[] postArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new String[] {};
    }
    return values.toArray(new String[0]);
  }

  // ==================================================================
  // 读：真分页 + 真筛选 + 不受分页影响的真聚合
  // ==================================================================

  public CarrierPage carriers(
      String keyword,
      String carrierCategory,
      String serviceTier,
      String availability,
      String countryCode,
      String businessType,
      String sort,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    // 关键词覆盖主体编码与名称。% _ \ 要转义 —— 不转义的话运营搜 "100%"
    // 会变成「匹配全部」而界面上看不出来（照抄 3.6.1.1 的同一坑）。
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (party_code ilike ? or party_name ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
    }
    String cat = text(carrierCategory);
    if (!cat.isEmpty()) {
      where.append(" and carrier_category = ?");
      filters.add(cat);
    }
    String tier = text(serviceTier);
    if (!tier.isEmpty()) {
      where.append(" and service_tier = ?");
      filters.add(tier);
    }
    // 按可用性筛选：走与统计**同一条** SQL 表达式，筛选与数字不会各说各话。
    String avail = text(availability);
    if (!avail.isEmpty()) {
      where.append(" and (" + AVAILABILITY_SQL + ") = ?");
      filters.add(avail);
    }
    // 覆盖国家 / 业务类型是真多值关系，走 EXISTS 而不是 like 逗号串。
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(
          " and exists (select 1 from carrier_country_coverage c"
              + " where c.tenant_id = carrier_logistics_profile.tenant_id"
              + " and c.profile_id = carrier_logistics_profile.id and c.country_code = ?)");
      filters.add(country);
    }
    String biz = text(businessType).toUpperCase(Locale.ROOT);
    if (!biz.isEmpty()) {
      where.append(
          " and exists (select 1 from carrier_business_scope b"
              + " where b.tenant_id = carrier_logistics_profile.tenant_id"
              + " and b.profile_id = carrier_logistics_profile.id and b.business_type = ?)");
      filters.add(biz);
    }

    long total =
        sql.fetchOne(
                "select count(*) from carrier_logistics_profile" + where, filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + SELECT_COLUMNS + " from carrier_logistics_profile" + where
                + " order by " + orderBy(sort) + " limit ? offset ?",
            binds.toArray());

    return new CarrierPage(
        hydrate(rows).values().stream().toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        // 汇总走**独立查询**：与 limit/offset 无关，也与列表取的是哪几行无关。
        summary(where.toString(), filters));
  }

  /**
   * 逐批取覆盖国家与业务类型，批量装配。
   *
   * <p>刻意<b>不做 N+1</b>：一页 50 行就是 100 次查询。两条 {@code = any(?)} 批量取回
   * 再按 profile_id 归组。顺带保证多值关系是**真的按行存的**，
   * 不是在 Java 里把数组 join 出来的字符串。
   */
  private Map<UUID, CarrierRow> hydrate(List<? extends Record> rows) {
    Map<UUID, CarrierRow> out = new LinkedHashMap<>();
    if (rows.isEmpty()) {
      return out;
    }
    List<UUID> ids = rows.stream().map(r -> r.get(0, UUID.class)).toList();

    Map<UUID, List<String>> countries = groupValues(
        "select profile_id, country_code from carrier_country_coverage"
            + " where profile_id in ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + ") order by country_code",
        ids,
        1);
    Map<UUID, List<String>> business = groupValues(
        "select profile_id, business_type from carrier_business_scope"
            + " where profile_id in ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + ") order by business_type",
        ids,
        1);

    for (Record r : rows) {
      out.put(r.get(0, UUID.class), toRow(r, countries, business));
    }
    return out;
  }

  private Map<UUID, List<String>> groupValues(String query, List<UUID> ids, int valueColumn) {
    Map<UUID, List<String>> grouped = new LinkedHashMap<>();
    for (Record r : sql.fetch(query, ids.toArray(new UUID[0]))) {
      grouped
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(r.get(valueColumn, String.class));
    }
    return grouped;
  }

  private CarrierRow toRow(
      Record r, Map<UUID, List<String>> countries, Map<UUID, List<String>> business) {
    UUID id = r.get("id", UUID.class);
    boolean partyUsable = r.get("party_usable", Boolean.class);
    String readiness = r.get("readiness", String.class);
    String interfaceType = r.get("interface_type", String.class);
    String availability = CarrierAvailability.of(partyUsable, readiness, interfaceType);
    List<String> cov = countries.getOrDefault(id, List.of());
    List<String> biz = business.getOrDefault(id, List.of());
    OffsetDateTime syncedAt = r.get("synced_at", OffsetDateTime.class);
    return new CarrierRow(
        id,
        r.get("role_id", UUID.class),
        r.get("party_id", UUID.class),
        r.get("party_code", String.class),
        r.get("party_name", String.class),
        r.get("party_status", String.class),
        partyUsable,
        r.get("role_status", String.class),
        interfaceType,
        r.get("account_ref", String.class),
        stringArray(r, "service_capabilities"),
        r.get("carrier_category", String.class),
        r.get("service_tier", String.class),
        r.get("secret_ref", String.class),
        r.get("secret_masked", String.class),
        cov,
        cov.size(),
        biz,
        biz.size(),
        readiness,
        availability,
        CarrierAvailability.message(availability, partyUsable),
        r.get("note", String.class),
        r.get("version", Long.class),
        syncedAt,
        r.get("updated_at", OffsetDateTime.class));
  }

  /** PostgreSQL 的 {@code text[]} 读成 {@code String[]}，空数组归一成空列表。 */
  private static List<String> stringArray(Record r, String column) {
    String[] values = r.get(column, String[].class);
    return values == null ? List.of() : List.of(values);
  }

  /** LIKE 通配符转义（照抄 3.6.1.1：搜 "100%" 不该变成「匹配全部」）。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String orderBy(String sort) {
    return switch (text(sort)) {
      case "NAME_ASC" -> "party_name asc, party_code asc";
      case "CODE_ASC" -> "party_code asc";
      case "READINESS_ASC" -> "readiness asc, party_name asc";
      case "SYNCED_AT_ASC" -> "synced_at asc nulls last";
      default -> "party_name asc, party_code asc";
    };
  }

  /** 汇总：独立查询，覆盖 where 条件下的<b>全部</b>行，与分页无关。 */
  private CarrierSummary summary(String where, List<Object> filters) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where " + AVAILABILITY_SQL + " = 'AVAILABLE') as available,"
                + " count(*) filter (where " + AVAILABILITY_SQL + " = 'MANUAL_ONLY') as manual_only,"
                + " count(*) filter (where " + AVAILABILITY_SQL + " = 'PARTY_BLOCKED') as party_blocked,"
                + " count(*) filter (where readiness = 'NOT_REGISTERED') as not_registered,"
                + " count(*) filter (where readiness = 'NO_CREDENTIAL') as no_credential,"
                + " count(*) filter (where readiness = 'NO_COVERAGE') as no_coverage,"
                + " count(*) filter (where readiness = 'NO_BUSINESS_SCOPE') as no_business_scope"
                + " from carrier_logistics_profile" + where,
            filters.toArray());
    return new CarrierSummary(
        r.get("total", Long.class),
        r.get("available", Long.class),
        r.get("manual_only", Long.class),
        r.get("party_blocked", Long.class),
        r.get("not_registered", Long.class),
        r.get("no_credential", Long.class),
        r.get("no_coverage", Long.class),
        r.get("no_business_scope", Long.class));
  }

  /** 概览：全量口径（不受列表筛选影响）的分类 / 时效分布。 */
  public CarrierOverview overview() {
    CarrierSummary all = summary(" where 1=1", List.of());
    List<CarrierCategoryCount> byCategory = distribution("carrier_category", List.of(
        new String[] {"EXPRESS_COURIER", "快递"},
        new String[] {"LINE_HAUL", "专线"},
        new String[] {"POSTAL", "邮政"},
        new String[] {"FBA_CARRIER", "FBA 承运"},
        new String[] {"LAST_MILE", "末端派送"}));
    List<CarrierCategoryCount> byTier = distribution("service_tier", List.of(
        new String[] {"STANDARD", "标准"},
        new String[] {"EXPRESS", "加急"},
        new String[] {"PRIORITY", "优先"}));
    return new CarrierOverview(
        all,
        byCategory,
        byTier,
        "统计覆盖本租户全部承运商，不随列表筛选或分页变化；"
            + "尚未登记物流侧档案的承运商（NOT_REGISTERED）计入 total 但不计入任何分类。");
  }

  /** 按某一列分组计数；未登记（NULL）不混进任何一类，如实留在 total 里。 */
  private List<CarrierCategoryCount> distribution(String column, List<String[]> options) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select " + column + " as k, count(*) as n from carrier_logistics_profile"
                + " where " + column + " is not null group by " + column)) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    List<CarrierCategoryCount> out = new ArrayList<>();
    for (String[] option : options) {
      out.add(new CarrierCategoryCount(option[0], option[1], counts.getOrDefault(option[0], 0L)));
    }
    return out;
  }

  // ==================================================================
  // 单条读写
  // ==================================================================

  public CarrierRow detail(UUID id) {
    var row = sql.fetchOne("select " + SELECT_COLUMNS + " from carrier_logistics_profile where id=?", id);
    if (row == null) {
      throw notFound();
    }
    return hydrate(List.of(row)).get(id);
  }

  /**
   * 登记 / 改一个承运商的物流侧档案。
   *
   * <p>整体替换语义（覆盖国家与业务类型按提交的整份替换）：这是界面上的
   * 「这张档案的完整内容」，少发一条就是静默丢失，所以前端必须提交全集。
   *
   * <p>⚠️ 写入顺序有讲究：先把 {@code readiness} 按新值算出来写进去，**再**换
   * 覆盖国家与业务类型 —— 因为 DB 触发器是在**每一行写入时**按当时的两张子表
   * 重算比对的。顺序反了触发器会在中间那次写入上拒掉一个其实正确的中间态。
   * 所以这里先删子表再写子表，主表最后写，主表那次写入看到的就是最终形态。
   *
   * <p>凭据只存引用：{@code secretRef} 必须是 {@code secret://} / {@code kv://} /
   * {@code vault://} / {@code external-secrets://} 开头，DB 层 CHECK 钉死，
   * 而且有引用就必须带掩码。
   */
  public CarrierRow save(UUID id, SaveCarrierRequest request) {
    UUID tenant = currentTenant();
    var existing =
        sql.fetchOne("select version, readiness from carrier_logistics_profile where id=?", id);
    if (existing == null) {
      throw notFound();
    }
    long currentVersion = existing.get("version", Long.class);
    // 乐观锁：400 留给非法字段，409 留给版本冲突（照抄 3.6.1.1 的口径）。
    if (request.version() != currentVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "承运商档案已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
    }

    String secretRef = text(request.secretRef());
    // 引用清空时掩码必须一起清掉：留着一个旧掩码会让界面上仍显示
    // 「已配置（sk_****3f2a）」，而其实凭据已经被撤了 —— 那是会误导人的假状态。
    String secretMasked = secretRef.isEmpty() ? "" : text(request.secretMasked());

    // 子表整体替换
    sql.execute("delete from carrier_country_coverage where tenant_id=? and profile_id=?", tenant, id);
    sql.execute("delete from carrier_business_scope where tenant_id=? and profile_id=?", tenant, id);
    for (String country : normalize(request.coverageCountries())) {
      sql.execute(
          "insert into carrier_country_coverage (tenant_id,profile_id,country_code)"
              + " values (?,?,?) on conflict do nothing",
          tenant,
          id,
          country);
    }
    for (String business : normalize(request.businessTypes())) {
      sql.execute(
          "insert into carrier_business_scope (tenant_id,profile_id,business_type)"
              + " values (?,?,?) on conflict do nothing",
          tenant,
          id,
          business);
    }

    // readiness 由服务层按与触发器**完全相同的规则**算一遍（单一实现在
    // CarrierAvailability 与 V134 触发器两处，注释互相指向）。
    String expected =
        text(secretRef).isEmpty()
            ? "NO_CREDENTIAL"
            : countCoverage(id) == 0
                ? "NO_COVERAGE"
                : countScope(id) == 0
                    ? "NO_BUSINESS_SCOPE"
                    : "READY";

    sql.execute(
        "update carrier_logistics_profile set carrier_category=?, service_tier=?, secret_ref=?,"
            + " secret_masked=?, readiness=?, note=?, version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        request.carrierCategory(),
        request.serviceTier(),
        secretRef,
        secretMasked,
        expected,
        text(request.note()),
        tenant,
        id);
    return detail(id);
  }

  private int countCoverage(UUID id) {
    return sql.fetchOne(
            "select count(*) from carrier_country_coverage where profile_id=?", id)
        .get(0, Integer.class);
  }

  private int countScope(UUID id) {
    return sql.fetchOne("select count(*) from carrier_business_scope where profile_id=?", id).get(0, Integer.class);
  }

  /** 国家代码统一大写去重（DB 的 CHECK 只接受两位大写字母）。 */
  private static List<String> normalize(List<String> values) {
    List<String> out = new ArrayList<>();
    if (values == null) {
      return out;
    }
    for (String v : values) {
      String u = text(v).toUpperCase(Locale.ROOT);
      if (!u.isEmpty() && !out.contains(u)) {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * 撤下物流侧档案（只删本服务的投影，<b>不动上游的承运商身份</b>）。
   *
   * <p>刻意命名为 {@code DELETE /channel/carriers/{id}} 而不是「删除承运商」：
   * 主体记录在 catalog，本服务无权删；这里撤的是「这个承运商的物流配置」，
   * 撤掉之后它会重新变回 {@code NOT_REGISTERED}（重新同步即可看到）。
   */
  public void deleteLogisticsProfile(UUID id) {
    UUID tenant = currentTenant();
    int rows = sql.execute("delete from carrier_logistics_profile where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound();
    }
  }
}
