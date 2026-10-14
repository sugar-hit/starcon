package com.starcon.catalog;

import static com.starcon.catalog.BrandRules.*;
import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.3.2.1 品牌管理：品牌字典（名称 / 多语言名 / Logo / 授权状态 / 关联类目 / 启用停用）、
 * 授权凭证维护，以及蓝图点名的输出——**刊登与合规的引用候选**与**阻止刊登判定**。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>判定只有一处实现</b>。{@link BrandRules#publishBlock} 是唯一的「能不能刊登」判定，
 *       列表行、详情、批量判定三个出口都调它。前端也只用同一份原因码显示提示 （{@code brand-model.ts}），不在浏览器里再写一套——两处各判一次的症状是
 *       「页面说能刊登，接口说不能」，而这种事只有真要发的那天才会暴露。
 *   <li><b>建档不要求凭证，刊登才要求</b>。允许「先登记成需授权、凭证稍后补」是正常业务流， 所以授权状态与凭证的配对关系**不写成 brand 表的 CHECK**：
 *       那样会连建档都拒，而被拒的本来应该是刊登（V16 迁移第 6 节有同一段理由）。
 *   <li><b>撤销不是删除</b>。凭证 {@code enabled=false} 是撤销，行仍在： 16 侵权检测要回溯「出事那天有没有授权」，删了行就查不到了。
 *   <li><b>关联类目指向 3.3.1.1 的类目主键</b>，不复刻一份类目文本： {@code brand_category.category_id}
 *       走复合外键，类目改名后关联自动跟着变。 {@code spu.brand} 的自由文本本模块不夺走（V1 的列，属已交付模块的写入所有权）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 品牌是商品主数据的一部分，与分类树（3.3.1.1）同域；不新造权限词表 （那需要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 */
@Service
public class BrandService {
  /** 单页最大行数。品牌字典是租户级的，行数量级在百位；超过 500 一页基本是脚本在拉全量。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 刊登判定的基准日。
   *
   * <p>用 UTC 当天而不是服务器本地当天：仓库里跨时区约定是业务时间用 IANA/UTC 表达， 而「凭证今天是否有效」如果按服务器本地时区算，同一份数据在 UTC 机器与
   * Asia/Shanghai 机器上会给出不同结论——这正是 16 侵权判定最不能容忍的一类漂移。 判定结果里回带 {@code asOf}，调用方能看到这是按哪一天判的。
   */
  private LocalDate asOf() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private static final String SELECT_COLUMNS =
      "select b.id,b.code,b.name,b.logo_uri,b.authorization_status,b.enabled,b.version,"
          + "b.created_at,b.updated_at,"
          // 凭证计数：总数与「按 asOf 有效的条数」。列表页要据此显示可刊登与否。
          + "(select count(*) from brand_authorization a where a.tenant_id=b.tenant_id"
          + "  and a.brand_id=b.id) as authorization_count,"
          + "(select count(*) from brand_authorization a where a.tenant_id=b.tenant_id"
          + "  and a.brand_id=b.id and a.enabled and a.valid_from<=?"
          + "  and (a.valid_to is null or a.valid_to>=?)) as valid_authorization_count,"
          // 关联类目：条数与名称串。名称串给列表页直接显示，省一次详情请求。
          + "(select count(*) from brand_category l where l.tenant_id=b.tenant_id"
          + "  and l.brand_id=b.id) as category_count,"
          + "coalesce((select string_agg(c.name, '、' order by l.position, c.name)"
          + "  from brand_category l join product_category c"
          + "   on c.tenant_id=l.tenant_id and c.id=l.category_id"
          // 非启用类目也要显示：运营会想知道「这个品牌挂在哪个已停用的类目上」。
          // 所以这里不按 c.enabled 过滤，只取名称。
          + "  where l.tenant_id=b.tenant_id and l.brand_id=b.id), '') as category_names";

  private static final String FROM_BRAND = " from brand b where b.tenant_id=?";

  private final DSLContext sql;

  public BrandService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 列表（分页 + 搜索 + 筛选）

  /**
   * 品牌列表：关键词搜索（编码 / 名称 / 多语言名）+ 授权状态 / 启用状态 / 刊登可行性 / 关联类目筛选。
   *
   * <p>关键词覆盖多语言名：刊登侧按 locale 渲染品牌名时搜的是本地化名， 只搜基础名的话运营搜「ニomsday」会搜不到那条挂着日文名的品牌。 那一段用 {@code exists}
   * 子查询而不是 join——join 会让一个品牌因为有 3 条语言名而在结果里出现 3 次。
   */
  public BrandPage list(BrandQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    LocalDate asOf = asOf();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds, asOf);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    // SELECT_COLUMNS 里有两个 asOf 占位符（凭证有效性判定），必须先绑定。
    List<Object> selectBinds = new ArrayList<>();
    selectBinds.add(asOf);
    selectBinds.add(asOf);
    selectBinds.add(currentTenantId());

    // count 查询只取 FROM_BRAND（一个 tenant 占位符），**不能**带 SELECT_COLUMNS 的两个 asOf
    // 占位符：那是给凭证有效性计数的子查询用的。少绑或多绑都会被驱动报成类型错误，
    // 而报错信息（"invalid input syntax for type uuid: 2026-10-06"）完全指不到真正的原因。
    long total =
        sql.fetchOne(
                "select count(*)" + FROM_BRAND + sqlWhere,
                concat(List.of(currentTenantId()), binds).toArray())
            .get(0, Long.class);
    long enabledCount =
        sql.fetchOne("select count(*) from brand where tenant_id=? and enabled", currentTenantId())
            .get(0, Long.class);
    long blockedCount =
        sql.fetchOne(
                "select count(*) from ("
                    + SELECT_COLUMNS
                    + FROM_BRAND
                    + ") x where not x.enabled or"
                    + " (x.authorization_status='LICENSED' and x.valid_authorization_count=0)",
                selectBinds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            SELECT_COLUMNS
                + FROM_BRAND
                + sqlWhere
                + " order by b.authorization_status,b.code,b.id limit ? offset ?",
            concat(selectBinds, concat(binds, List.of(size, (long) page * size))).toArray());
    List<BrandRow> items = withNames(rows.stream().map(this::toRow).toList());
    return new BrandPage(
        items,
        total,
        page,
        size,
        enabledCount,
        blockedCount,
        "刊登判定基准日为 " + asOf + "（UTC）。授权状态 OWNED / AGENCY 恒可刊登；" + "LICENSED 需至少一条在有效期内的授权凭证。");
  }

  private void conditions(
      BrandQuery query, List<String> where, List<Object> binds, LocalDate asOf) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      String escaped = query.keyword().replace("!", "!!").replace("%", "!%").replace("_", "!_");
      where.add(
          "(b.code ilike ? escape '!' or b.name ilike ? escape '!'"
              + " or exists (select 1 from brand_name n where n.tenant_id=b.tenant_id"
              + "  and n.brand_id=b.id and n.name ilike ? escape '!'))");
      String pattern = "%" + escaped + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.authorizationStatus() != null && !query.authorizationStatus().isBlank()) {
      String problem = authorizationStatusProblem(query.authorizationStatus());
      CatalogService.check(problem == null, problem);
      where.add("b.authorization_status=?");
      binds.add(query.authorizationStatus().trim());
    }
    if (query.enabled() != null) {
      where.add("b.enabled=?");
      binds.add(query.enabled());
    }
    if (query.categoryId() != null) {
      // 筛选必须验证类目归属本租户：直接 exists 就够，写不存在的 id 得到空列表而不是报错。
      where.add(
          "exists (select 1 from brand_category l where l.tenant_id=b.tenant_id"
              + " and l.brand_id=b.id and l.category_id=?)");
      binds.add(query.categoryId());
    }
    if (query.publishable() != null) {
      // 与 blockedCount 同一套表达式（不是 BrandRules）：列表筛选要在 SQL 侧完成，
      // 而 BrandRules 判的是「已经取出来的凭证行」。两处口径必须逐字一致，
      // 所以 SQL 表达式只在这里写一次（两个调用点共用）。
      String blocked =
          "(not b.enabled or"
              + " (b.authorization_status='LICENSED'"
              + "  and (select count(*) from brand_authorization a where a.tenant_id=b.tenant_id"
              + "    and a.brand_id=b.id and a.enabled and a.valid_from<=?"
              + "    and (a.valid_to is null or a.valid_to>=?))=0))";
      if (query.publishable()) where.add("not " + blocked);
      else where.add(blocked);
      binds.add(asOf);
      binds.add(asOf);
    }
  }

  private BrandRow toRow(Record r) {
    String block =
        blockedCode(
            r.get("enabled", Boolean.class),
            r.get("authorization_status", String.class),
            r.get("valid_authorization_count", Long.class));
    return new BrandRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("logo_uri", String.class),
        r.get("authorization_status", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        block == null,
        block,
        blockLabel(block),
        r.get("authorization_count", Integer.class),
        r.get("valid_authorization_count", Integer.class),
        r.get("category_count", Long.class),
        r.get("category_names", String.class),
        Map.of(),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * SQL 侧的阻止判定（列表用）。
   *
   * <p>只覆盖「数量为 0」这一种阻断（停用 / 需授权而无有效凭证）， 而 BrandRules.publishBlock 额外区分「未生效」与「已过期」——
   * 那两种区分需要在内存里逐条看凭证（要拿到 valid_from 与 asOf 的先后）， SQL 侧只数条数区分不出来。详情与批量判定端点走 BrandRules，给出完整原因码。
   * 列表页显示的「不可刊登」在两种口径下**是同一个集合**：BrandRules 只在计数为 0 时 才给出 BLOCK_*（非零但全不在有效期内的情况，BrandRules 也返回阻断码），
   * 而 SQL 侧此时 valid_authorization_count=0，与 BrandRules 一致。
   */
  private static String blockedCode(boolean enabled, String status, Long validCount) {
    if (!enabled) return BrandRules.BLOCK_BRAND_DISABLED;
    if (!"LICENSED".equals(status)) return null;
    return validCount == null || validCount == 0 ? BrandRules.BLOCK_LICENSE_MISSING : null;
  }

  /** 批量补多语言名：一次查询按品牌归拢，避免每行各查一次（N+1）。 */
  private List<BrandRow> withNames(List<BrandRow> rows) {
    if (rows.isEmpty()) return rows;
    List<UUID> ids = rows.stream().map(BrandRow::id).toList();
    Map<UUID, Map<String, String>> byBrand = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select brand_id,locale,name from brand_name where brand_id in ("
                + placeholders(ids)
                + ")",
            ids.toArray()))
      byBrand
          .computeIfAbsent(r.get("brand_id", UUID.class), k -> new LinkedHashMap<>())
          .put(r.get("locale", String.class), r.get("name", String.class));
    return rows.stream()
        .map(row -> withNames(row, byBrand.getOrDefault(row.id(), Map.of())))
        .toList();
  }

  private static BrandRow withNames(BrandRow row, Map<String, String> names) {
    return new BrandRow(
        row.id(),
        row.code(),
        row.name(),
        row.logoUri(),
        row.authorizationStatus(),
        row.enabled(),
        row.publishable(),
        row.publishBlockCode(),
        row.publishBlockLabel(),
        row.authorizationCount(),
        row.validAuthorizationCount(),
        row.categoryCount(),
        row.categoryNames(),
        names,
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }

  // ============================================================ 详情

  /** 品牌详情：本体 + 多语言名 + 关联类目 + 凭证 + 刊登判定，同一时刻的一致快照。 */
  public BrandDetail detail(UUID id) {
    var row = brandRow(id);
    LocalDate asOf = asOf();
    List<BrandAuthorization> credentials = authorizations(id, asOf);
    var detail =
        new BrandDetail(
            row.get("id", UUID.class),
            row.get("code", String.class),
            row.get("name", String.class),
            row.get("logo_uri", String.class),
            row.get("authorization_status", String.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
            row.get("version", Long.class),
            namesOf(id),
            categoryLinks(id),
            credentials,
            false,
            null,
            null,
            0,
            asOf.toString(),
            instant(row.get("created_at", OffsetDateTime.class)),
            instant(row.get("updated_at", OffsetDateTime.class)));
    return decorateEligibility(detail, credentials, asOf);
  }

  /**
   * 给详情补上刊登判定。判定必须带上**已经取出来的凭证行**， 不能在 SQL 里重查一遍：两处各查一次的话，中间被人改掉一条凭证就会得到 「凭证列表说已过期、判定说可刊登」的自相矛盾页面。
   */
  private BrandDetail decorateEligibility(
      BrandDetail detail, List<BrandAuthorization> credentials, LocalDate asOf) {
    String block =
        publishBlock(
            new BrandRules.PublishInputs(
                detail.enabled(),
                detail.authorizationStatus(),
                credentials.stream()
                    .map(
                        c ->
                            new BrandRules.Credential(
                                c.enabled(), date(c.validFrom()), dateOrNull(c.validTo())))
                    .toList(),
                asOf));
    int valid =
        (int)
            credentials.stream()
                .filter(
                    c -> validOn(c.enabled(), date(c.validFrom()), dateOrNull(c.validTo()), asOf))
                .count();
    return new BrandDetail(
        detail.id(),
        detail.code(),
        detail.name(),
        detail.logoUri(),
        detail.authorizationStatus(),
        detail.enabled(),
        detail.version(),
        detail.names(),
        detail.categories(),
        detail.authorizations(),
        block == null,
        block,
        blockMessage(block, detail.name()),
        valid,
        detail.asOf(),
        detail.createdAt(),
        detail.updatedAt());
  }

  /** 多语言名：一次查询归拢。 */
  private Map<String, String> namesOf(UUID brandId) {
    Map<String, String> names = new LinkedHashMap<>();
    for (Record r :
        sql.fetch("select locale,name from brand_name where brand_id=? order by locale", brandId))
      names.put(r.get("locale", String.class), r.get("name", String.class));
    return names;
  }

  /**
   * 关联类目：带路径串。
   *
   * <p>类目取的是**当前名称**（不是建关联时的快照），所以分类树那边重命名之后 这里的显示自动跟着变——与 CategoryTreeService 路径串同一口径。
   * 停用的类目照样返回：运营需要看见「这个品牌挂在哪个已停用的类目上」。
   */
  private List<BrandCategoryLink> categoryLinks(UUID brandId) {
    var rows =
        sql.fetch(
            "select l.category_id,c.code,c.name,c.path_ids,l.position"
                + " from brand_category l join product_category c"
                + " on c.tenant_id=l.tenant_id and c.id=l.category_id"
                + " where l.brand_id=? order by l.position,c.name",
            brandId);
    return rows.stream()
        .map(
            r ->
                new BrandCategoryLink(
                    r.get("category_id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    pathText(r.get("path_ids", UUID[].class)),
                    r.get("position", Integer.class)))
        .toList();
  }

  private String pathText(UUID[] ids) {
    if (ids == null || ids.length == 0) return "";
    Map<UUID, String> names = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,name from product_category where id in (" + placeholders(ids) + ")", ids))
      names.put(r.get("id", UUID.class), r.get("name", String.class));
    var parts = new ArrayList<String>(ids.length);
    for (UUID id : ids) {
      String name = names.get(id);
      if (name != null) parts.add(name);
    }
    return String.join(" / ", parts);
  }

  /** 凭证列表（含当前有效性与剩余天数）。 */
  public List<BrandAuthorization> authorizations(UUID brandId) {
    return authorizations(brandId, asOf());
  }

  private List<BrandAuthorization> authorizations(UUID brandId, LocalDate asOf) {
    brandRow(brandId);
    var rows =
        sql.fetch(
            "select id,brand_id,certificate_no,grantor,valid_from,valid_to,evidence_uri,note,"
                + "enabled,version,created_at,updated_at from brand_authorization"
                + " where brand_id=? order by valid_from desc,certificate_no,id",
            brandId);
    return rows.stream().map(r -> toAuthorization(r, asOf)).toList();
  }

  private static BrandAuthorization toAuthorization(Record r, LocalDate asOf) {
    LocalDate from = r.get("valid_from", LocalDate.class);
    LocalDate to = r.get("valid_to", LocalDate.class);
    boolean enabled = Boolean.TRUE.equals(r.get("enabled", Boolean.class));
    boolean valid = validOn(enabled, from, to, asOf);
    // 剩余天数只在「当前有效且有终止日」时给：过期凭证的负数天数与未生效凭证的
    // 正数天数都不是运营要看的量（它们看的是「过没过」「起没起」这两个状态位）。
    int days = valid && to != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(asOf, to) : 0;
    return new BrandAuthorization(
        r.get("id", UUID.class),
        r.get("brand_id", UUID.class),
        r.get("certificate_no", String.class),
        r.get("grantor", String.class),
        from == null ? null : from.toString(),
        to == null ? null : to.toString(),
        r.get("evidence_uri", String.class),
        r.get("note", String.class),
        enabled,
        valid,
        days,
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  // ============================================================ 刊登判定（蓝图点名的输出）

  /**
   * 单个品牌的刊登判定。04 刊登与 16 侵权检测都调它。
   *
   * <p>判定不可绕过：不提供 {@code force} 参数，也不接受调用方自带结论—— 一旦有了开关，第一个「先发出去再说」的调用就会把这条规则永久废掉。
   */
  public BrandPublishEligibility eligibility(UUID brandId) {
    return eligibility(asOf(), brandId);
  }

  /** 批量判定：04 刊登一次提交多个商品时按品牌去重后批量判定，避免 N 次往返。 */
  public List<BrandPublishEligibility> eligibility(List<UUID> brandIds) {
    LocalDate asOf = asOf();
    List<UUID> distinct = brandIds == null ? List.of() : brandIds.stream().distinct().toList();
    if (distinct.isEmpty()) return List.of();
    if (distinct.size() > 500)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多判定 500 个品牌，请分批提交");
    Map<UUID, List<BrandAuthorization>> credentials = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select brand_id,id,certificate_no,grantor,valid_from,valid_to,evidence_uri,note,"
                + "enabled,version,created_at,updated_at from brand_authorization"
                + " where brand_id in ("
                + placeholders(distinct)
                + ")",
            distinct.toArray()))
      credentials
          .computeIfAbsent(r.get("brand_id", UUID.class), k -> new ArrayList<>())
          .add(toAuthorization(r, asOf));
    var rows =
        sql.fetch(
            "select id,name,authorization_status,enabled from brand where id in ("
                + placeholders(distinct)
                + ")",
            distinct.toArray());
    Map<UUID, Record> byId = new LinkedHashMap<>();
    for (Record r : rows) byId.put(r.get("id", UUID.class), r);
    var out = new ArrayList<BrandPublishEligibility>(distinct.size());
    for (UUID id : distinct) {
      Record row = byId.get(id);
      if (row == null) throw CatalogService.missing();
      out.add(eligibility(asOf, id, row, credentials.getOrDefault(id, List.of())));
    }
    return out;
  }

  private BrandPublishEligibility eligibility(LocalDate asOf, UUID brandId) {
    return eligibility(asOf, brandId, brandRow(brandId), authorizations(brandId, asOf));
  }

  private BrandPublishEligibility eligibility(
      LocalDate asOf, UUID brandId, Record row, List<BrandAuthorization> credentials) {
    String name = row.get("name", String.class);
    String status = row.get("authorization_status", String.class);
    boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
    String block =
        publishBlock(
            new BrandRules.PublishInputs(
                enabled,
                status,
                credentials.stream()
                    .map(
                        c ->
                            new BrandRules.Credential(
                                c.enabled(), date(c.validFrom()), dateOrNull(c.validTo())))
                    .toList(),
                asOf));
    int valid =
        (int)
            credentials.stream()
                .filter(
                    c -> validOn(c.enabled(), date(c.validFrom()), dateOrNull(c.validTo()), asOf))
                .count();
    return new BrandPublishEligibility(
        brandId,
        name,
        status,
        enabled,
        block == null,
        block,
        blockLabel(block),
        blockMessage(block, name),
        credentials.size(),
        valid,
        asOf.toString());
  }

  // ============================================================ 写入：品牌

  /**
   * 建品牌或改品牌（id 为空即新建），走 version 乐观锁。
   *
   * <p>编码建后不可改：它是刊登的品牌字段与 16 侵权检测对齐这个品牌的锚点， 改编码会让已有对齐在下一次写入时突然报「对不上」。要改就新建一条品牌。
   *
   * <p>授权状态**可以**改（自有 → 需授权是真实业务动作），且改完立刻反映到刊登判定： 一个自有品牌转成需授权的瞬间就会变成「不可刊登」，这是对的——
   * 运营改状态就是想表达「这个品牌从现在起需要凭证」。
   */
  public BrandSaved save(AccessService.Access actor, UUID id, BrandInput input) {
    String problem =
        firstNonNull(
            codeProblem(input.code()),
            nameProblem(input.name()),
            authorizationStatusProblem(input.authorizationStatus()),
            logoProblem(input.logoUri()),
            namesProblem(input.names()));
    CatalogService.check(problem == null, problem);
    String code = normalizeCode(input.code());
    String status = normalizeStatus(input.authorizationStatus());
    String logo =
        input.logoUri() == null || input.logoUri().isBlank() ? null : input.logoUri().trim();

    if (id == null) {
      if (sql.fetchOne("select id from brand where code=?", code) != null)
        throw conflict("品牌编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into brand(id,tenant_id,code,name,logo_uri,authorization_status,enabled)"
                + " values(?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            code,
            normalizeName(input.name()),
            logo,
            status,
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("品牌编码已被并发创建或占用，请刷新后重试");
      }
      replaceNames(actor.tenantId(), newId, input.names());
      replaceCategories(actor.tenantId(), newId, input.categories());
      audit(actor, newId, "BrandSaved");
      return savedResult(newId);
    }

    var row = brandRow(id);
    // 不可改字段给 400（提交的内容不合法），409 留给 version 乐观锁与唯一键冲突——
    // 与 3.3.1.1 的分类编码同一口径。
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "品牌编码建后不可修改：刊登的品牌字段与侵权检测按 code 对齐这个品牌，改编码会让已有对齐在下一次写入时报「对不上」。要改请新建一条品牌");
    int updated;
    try {
      updated =
          sql.execute(
              "update brand set name=?,logo_uri=?,authorization_status=?,enabled=?,"
                  + "version=version+1,updated_at=now() where id=? and version=?",
              normalizeName(input.name()),
              logo,
              status,
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("品牌保存失败，请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "品牌已被他人修改，请刷新后重试");
    replaceNames(actor.tenantId(), id, input.names());
    replaceCategories(actor.tenantId(), id, input.categories());
    audit(actor, id, "BrandSaved");
    return savedResult(id);
  }

  private BrandSaved savedResult(UUID id) {
    var e = eligibility(id);
    return new BrandSaved(
        id,
        brandRow(id).get("version", Long.class),
        e.authorizationStatus(),
        e.publishable(),
        e.blockCode());
  }

  /** 启用停用。停用会立刻让刊登判定变成 {@code BRAND_DISABLED}——那正是停用的目的。 */
  public void setEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update brand set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "BrandEnabledChanged");
  }

  /**
   * 删除品牌：连同多语言名、凭证与类目关联一起级联删除（DB 外键已声明）。
   *
   * <p>停用不删除：品牌被商品与历史刊登引用着，删了就查不到「当时用的是哪个品牌」， 而 16 侵权检测要回溯的正是这个。所以只有明确知道没人引用时才删。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = brandRow(id);
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "品牌已被他人修改，请刷新后重试");
    sql.execute("delete from brand where id=?", id);
    audit(actor, id, "BrandDeleted");
  }

  /** 整体替换多语言名。 */
  private void replaceNames(UUID tenantId, UUID brandId, List<BrandNameInput> names) {
    sql.execute("delete from brand_name where brand_id=?", brandId);
    if (names == null || names.isEmpty()) return;
    for (BrandNameInput entry : names)
      sql.execute(
          "insert into brand_name(tenant_id,brand_id,locale,name) values(?,?,?,?)",
          tenantId,
          brandId,
          normalizeLocale(entry.locale()),
          normalizeName(entry.name()));
  }

  /**
   * 整体替换关联类目。
   *
   * <p>提交前逐个确认类目真实存在且属于本租户：{@code brand_category} 的复合外键
   * 会拒跨租户引用，但报出来的是约束名；这里先给「类目不存在或不属于当前租户」这种可读原因。
   *
   * <p>停用的类目不允许**新增**关联（新建一个关联指向一个已停用的类目没有意义）， 但保留已有关联——类目停用时不会级联删关联，那种情况下运营要能看到并自己解掉它。
   */
  private void replaceCategories(UUID tenantId, UUID brandId, List<BrandCategoryLinkInput> links) {
    if (links != null && links.size() > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个品牌最多关联 50 个类目");
    sql.execute("delete from brand_category where brand_id=?", brandId);
    if (links == null || links.isEmpty()) return;
    int position = 0;
    for (BrandCategoryLinkInput link : links) {
      var row =
          sql.fetchOne(
              "select id,enabled from product_category where tenant_id=? and id=?",
              tenantId,
              link.categoryId());
      if (row == null) throw CatalogService.missing();
      CatalogService.check(
          Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
          "不能把品牌关联到停用的类目上：请先在分类树里启用该类目，或换一个类目");
      int slot = link.position() == null ? position++ : link.position();
      try {
        sql.execute(
            "insert into brand_category(tenant_id,brand_id,category_id,position) values(?,?,?,?)",
            tenantId,
            brandId,
            link.categoryId(),
            slot);
      } catch (DataAccessException e) {
        throw conflict("关联类目重复或已被并发写入，请刷新后重试");
      }
    }
  }

  // ============================================================ 写入：授权凭证

  /** 登记一条授权凭证（id 为空即新建），走 version 乐观锁。 */
  public BrandSaved saveAuthorization(
      AccessService.Access actor, UUID brandId, UUID id, BrandAuthorizationInput input) {
    brandRow(brandId);
    String problem =
        firstNonNull(
            certificateNoProblem(input.certificateNo()),
            grantorProblem(input.grantor()),
            validityProblem(dateOrNull(input.validFrom()), dateOrNull(input.validTo())),
            evidenceProblem(input.evidenceUri()));
    CatalogService.check(problem == null, problem);
    LocalDate from = dateOrNull(input.validFrom());
    LocalDate to = dateOrNull(input.validTo());

    if (id == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into brand_authorization(id,tenant_id,brand_id,certificate_no,grantor,"
                + "valid_from,valid_to,evidence_uri,note,enabled) values(?,?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            brandId,
            normalizeText(input.certificateNo()),
            normalizeText(input.grantor()),
            from,
            to,
            emptyToNull(input.evidenceUri()),
            input.note() == null ? "" : input.note().trim(),
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("同一品牌下凭证编号已存在（" + normalizeText(input.certificateNo()) + "），请改用更新而不是重复登记");
      }
      audit(actor, newId, "BrandAuthorizationSaved");
      return savedResult(brandId);
    }

    var existing = authorizationRow(brandId, id);
    int updated;
    try {
      updated =
          sql.execute(
              "update brand_authorization set certificate_no=?,grantor=?,valid_from=?,valid_to=?,"
                  + "evidence_uri=?,note=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              normalizeText(input.certificateNo()),
              normalizeText(input.grantor()),
              from,
              to,
              emptyToNull(input.evidenceUri()),
              input.note() == null ? "" : input.note().trim(),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("凭证保存失败：该品牌下凭证编号已被占用，请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "凭证已被他人修改，请刷新后重试");
    audit(actor, existing.get("brand_id", UUID.class), "BrandAuthorizationSaved");
    return savedResult(brandId);
  }

  /**
   * 撤销一条凭证（{@code enabled=false}）。
   *
   * <p>刻意**不提供删除凭证的端点**：撤销与删除在这里是两件事， 而 16 侵权检测要回溯「曾经有过授权、后来被撤销」，删行就把这个事实抹掉了。
   * 真要清理试录进来的脏数据，也让运营走停用而不是删除——它同样不参与刊登判定。
   */
  public BrandSaved revokeAuthorization(
      AccessService.Access actor, UUID brandId, UUID id, boolean enabled) {
    authorizationRow(brandId, id);
    int updated =
        sql.execute(
            "update brand_authorization set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, brandId, "BrandAuthorizationRevoked");
    return savedResult(brandId);
  }

  // ============================================================ 引用候选（蓝图的两个输出之一）

  /**
   * 刊登 / 合规的品牌引用候选。
   *
   * <p>与列表端点的区别是**语义**：列表是给运营维护用的（带停用项、带全部状态）， 候选是给 04 刊登与 16 侵权检测用的——默认只给启用且可刊登的品牌，
   * 且每条带上刊登判定，调用方不必再打一次判定端点。
   *
   * <p>{@code includeBlocked=true} 时连不可刊登的品牌一起返回（带原因码）， 用于刊登表单要显示「这个品牌为什么选不了」而不是让它凭空消失。
   */
  public List<BrandPublishEligibility> candidates(
      UUID categoryId, String keyword, boolean includeBlocked) {
    BrandQuery query =
        new BrandQuery(
            keyword,
            null,
            includeBlocked ? null : Boolean.TRUE,
            includeBlocked ? null : Boolean.TRUE,
            categoryId,
            0,
            MAX_PAGE_SIZE);
    // 走列表的 SQL 以保证候选与列表口径一致（同一套筛选与同一个判定表达式），
    // 再把行转成判定结果：候选上限就是单页上限，刊登表单的品牌选择器不需要更多。
    var page = list(query);
    var out = new ArrayList<BrandPublishEligibility>(page.items().size());
    for (BrandRow row : page.items()) {
      String block = row.publishBlockCode();
      out.add(
          new BrandPublishEligibility(
              row.id(),
              row.name(),
              row.authorizationStatus(),
              row.enabled(),
              row.publishable(),
              block,
              row.publishBlockLabel(),
              blockMessage(block, row.name()),
              row.authorizationCount(),
              row.validAuthorizationCount(),
              asOf().toString()));
    }
    return out;
  }

  // ============================================================ 概览

  /** 概览：规模与几个治理缺口指标。 */
  public BrandOverview overview() {
    UUID tenantId = currentTenantId();
    LocalDate asOf = asOf();
    var counts =
        sql.fetchOne(
            "select count(*) as brand_count,"
                + "count(*) filter (where enabled) as enabled_count,"
                + "count(*) filter (where not enabled) as disabled_count,"
                + "count(*) filter (where authorization_status='OWNED') as owned_count,"
                + "count(*) filter (where authorization_status='AGENCY') as agency_count,"
                + "count(*) filter (where authorization_status='LICENSED') as licensed_count"
                + " from brand where tenant_id=?",
            tenantId);
    // 「现在有多少品牌登不了」：停用的 + 需授权而无有效凭证的。与列表的 blockedCount 同一套口径。
    long blocked =
        sql.fetchOne(
                "select count(*) from ("
                    + SELECT_COLUMNS
                    + FROM_BRAND
                    + ") x where not x.enabled or"
                    + " (x.authorization_status='LICENSED' and x.valid_authorization_count=0)",
                asOf,
                asOf,
                tenantId)
            .get(0, Long.class);
    long licensedWithoutCredential =
        sql.fetchOne(
                "select count(*) from brand b where b.tenant_id=?"
                    + " and b.authorization_status='LICENSED' and not exists"
                    + " (select 1 from brand_authorization a where a.tenant_id=b.tenant_id"
                    + "  and a.brand_id=b.id)",
                tenantId)
            .get(0, Long.class);
    long expiring =
        sql.fetchOne(
                "select count(*) from brand_authorization where tenant_id=? and enabled"
                    + " and valid_to is not null and valid_to>=? and valid_to<=?",
                tenantId,
                asOf,
                asOf.plusDays(30))
            .get(0, Long.class);
    long links =
        sql.fetchOne("select count(*) from brand_category where tenant_id=?", tenantId)
            .get(0, Long.class);
    return new BrandOverview(
        counts.get("brand_count", Long.class),
        counts.get("enabled_count", Long.class),
        counts.get("disabled_count", Long.class),
        counts.get("owned_count", Long.class),
        counts.get("agency_count", Long.class),
        counts.get("licensed_count", Long.class),
        blocked,
        licensedWithoutCredential,
        expiring,
        links,
        "刊登判定基准日为 "
            + asOf
            + "（UTC）。blockedListingCount 是当前不允许刊登的品牌数："
            + "停用的品牌，以及标记为需授权却没有一条有效凭证的品牌。");
  }

  // ============================================================ 工具

  private Record brandRow(UUID id) {
    var row = sql.fetchOne("select * from brand where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record authorizationRow(UUID brandId, UUID id) {
    var row =
        sql.fetchOne(
            "select * from brand_authorization where tenant_id=? and brand_id=? and id=?",
            currentTenantId(),
            brandId,
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /**
   * 拼出一串 {@code ?,?,?} 占位符。
   *
   * <p>不用 {@code = any(?::uuid[])}：jOOQ 把 {@code UUID[]} 绑成**一个** uuid 参数 （而不是 uuid[]），整条语句会报
   * {@code cannot cast type uuid to uuid[]}。 与 {@link CategoryTreeService} 同一做法。调用方必须先判空。
   */
  private static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  private static String placeholders(UUID[] ids) {
    return String.join(",", java.util.Collections.nCopies(ids.length, "?"));
  }

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 AccessService.run 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var all = new ArrayList<Object>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  private static String emptyToNull(String value) {
    String trimmed = normalizeText(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static LocalDate dateOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式需为 yyyy-MM-dd，例如 2026-12-31");
    }
  }

  private static LocalDate date(String value) {
    LocalDate parsed = dateOrNull(value);
    if (parsed == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期不能为空，格式 yyyy-MM-dd");
    return parsed;
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
