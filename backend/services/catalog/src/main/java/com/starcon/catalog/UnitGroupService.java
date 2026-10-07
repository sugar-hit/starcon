package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.UnitGroupRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.3.3.1 计量单位（字典视角）：单位组、组内换算关系与换算查询能力。
 *
 * <p>本类的边界纪律：
 *
 * <ol>
 *   <li><b>不碰 {@code unit_of_measure} 的写入</b>。单位字典的写入所有者是
 *       {@link UnitConversionService}（V8 建立），本模块只**读**它：量纲、名称、数量精度。
 *       字典视角页面同时调两个端点，不新建第二个写入口，也不复制一份平行的单位字典表。
 *   <li><b>只写自己新建的三张表</b>：{@code unit_group} / {@code unit_group_member} /
 *       {@code unit_group_conversion}。SKU 侧换算（{@code sku_unit_conversion}）
 *       与单据冻结快照的既有语义一个字不动——组级换算是"这两个单位之间换多少"的通用答案，
 *       单据冻结仍然引用 SKU 侧换算行。
 *   <li><b>量纲守卫双层</b>。应用层判一次并给出人话（"单位「件」属于数量，
 *       请改选重量单位的成员"），V17 的三个守卫触发器再兜一次。
 *       只靠 DB 层的话运营看到的是一条 23514，没有任何可执行信息。
 *   <li><b>比例必须版本化、区间不重叠、查不到就报查不到</b>。与
 *       {@link UnitConversionService} 同一套语义：静默回退到"最近的一版"会让一张
 *       按过期比例生成的采购单看起来完全正常，这是最贵的一类错误。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，
 * 与同 L3 的分类树（3.3.1.1）、类目属性要求（3.3.1.2）、品牌（3.3.2.1）一致——
 * 单位组是商品主数据域的租户级字典，不新造权限词表。
 */
@Service
public class UnitGroupService {
  /** 单页最大行数。单位组是租户级字典，行数量级在十位；超过 200 一页基本是脚本在拉全量。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public UnitGroupService(DSLContext sql) {
    this.sql = sql;
  }

  /**
   * 判定基准日：UTC 当天。
   *
   * <p>用 UTC 而不是服务器本地当天，与 {@link BrandService#asOf()} 同一理由：
   * 「这一版换算今天生效吗」按本地时区算，同一份数据在 UTC 机器与 Asia/Shanghai 机器上
   * 会给出不同结论。判定结果里回带 {@code asOf}，调用方能看到这是按哪一天判的。
   */
  private static LocalDate asOf() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /**
   * 组列表 / 组详情的公共 SELECT。
   *
   * <p>它带**两个** {@code asOf} 占位符（都落在 {@code all_members_convertible} 的
   * {@code not exists} 子查询里：{@code effective_from<=?} 与 {@code effective_to>=?}）。
   * 调用方必须先绑这两个 {@code LocalDate} 再绑后面的参数——少绑或多绑都会被驱动报成
   * 类型错误，而报错信息（{@code invalid input syntax for type date: "2026-10-07"}）
   * 完全指不到真正的原因。
   */
  private static final String SELECT_GROUP =
      "select g.id,g.code,g.name,g.dimension,g.base_unit_code,g.note,g.enabled,g.version,"
          + "g.created_at,g.updated_at,"
          + "(select count(*) from unit_group_member m where m.tenant_id=g.tenant_id"
          + "  and m.group_id=g.id) as member_count,"
          + "(select count(*) from unit_group_conversion c where c.tenant_id=g.tenant_id"
          + "  and c.group_id=g.id) as conversion_count,"
          + "(select count(*) from unit_group_conversion c where c.tenant_id=g.tenant_id"
          + "  and c.group_id=g.id and c.effective_to is null) as open_conversion_count,"
          // 「每个非基准成员当天都有生效版本」才算组内换算真的可用。
          // 末尾的 member_count>0 是必要的：只有一个成员（就是基准单位）的组不需要任何换算版本，
          // 没有它的话那种组会被判成「不完整」，界面上要单独提示用户去改一个本来没问题的数据。
          + "(select count(*) from unit_group_member m where m.tenant_id=g.tenant_id"
          + "  and m.group_id=g.id and m.unit_code<>g.base_unit_code) as convertible_member_count,"
          + "(select count(*) from unit_group_member m where m.tenant_id=g.tenant_id"
          + "  and m.group_id=g.id and m.unit_code<>g.base_unit_code and not exists("
          + "   select 1 from unit_group_conversion c where c.tenant_id=m.tenant_id"
          + "    and c.group_id=m.group_id and c.unit_code=m.unit_code"
          + "    and c.effective_from<=? and (c.effective_to is null or c.effective_to>=?)))"
          + "  as missing_member_count,"
          + "(select u.name from unit_of_measure u where u.tenant_id=g.tenant_id"
          + "  and u.code=g.base_unit_code) as base_unit_name";

  /** 换算查询里用到的单位信息快照（只取三个字段，与字典表解耦）。 */
  private record UnitRef(String code, String name, String dimension, boolean enabled) {}

  // ============================================================ 概览

  /**
   * 概览：字典规模、组规模与三个治理缺口指标。
   *
   * <p>三个缺口分别是「单位没进任何组」「组里有成员缺当天生效版本」「组里没配过任何换算」。
   * 它们都会让换算查询报出一个可解释的拒绝原因码，界面上要能一眼看出是哪一类。
   */
  public UnitGroupOverview overview() {
    var units =
        sql.fetchOne(
            "select count(*) as all_units,count(*) filter (where enabled) as enabled_units"
                + " from unit_of_measure");
    var groups =
        sql.fetchOne(
            "select count(*) as all_groups,count(*) filter (where enabled) as enabled_groups"
                + " from unit_group");
    long members = sql.fetchOne("select count(*) from unit_group_member").get(0, Long.class);
    var conversions =
        sql.fetchOne(
            "select count(*) as all_conversions,"
                + " count(*) filter (where effective_to is null) as open_conversions"
                + " from unit_group_conversion");
    // 未归组单位：字典里有、但不属于任何单位组。这些单位能配 SKU 侧换算，
    // 但组级换算查不到它们——是治理缺口，不是错误。
    long ungrouped =
        sql.fetchOne(
                "select count(*) from unit_of_measure u where not exists("
                    + " select 1 from unit_group_member m"
                    + " where m.tenant_id=u.tenant_id and m.unit_code=u.code)")
            .get(0, Long.class);
    // 不完整组：存在「非基准成员且当天没有生效换算版本」的组。
    // 只有基准单位的组不算——它本来就不需要换算版本。
    long incomplete =
        sql.fetchOne(
                "select count(*) from ("
                    + " select g.id from unit_group g"
                    + "  join unit_group_member m on m.tenant_id=g.tenant_id and m.group_id=g.id"
                    + "  where m.unit_code<>g.base_unit_code and not exists("
                    + "   select 1 from unit_group_conversion c"
                    + "   where c.tenant_id=m.tenant_id and c.group_id=m.group_id"
                    + "    and c.unit_code=m.unit_code and c.effective_from<=?"
                    + "    and (c.effective_to is null or c.effective_to>=?))"
                    + " group by g.id) x",
                asOf(),
                asOf())
            .get(0, Long.class);
    var dimensions =
        sql.fetch(
                "select g.dimension,count(distinct g.id) as group_count,count(m.unit_code) as member_count"
                    + " from unit_group g"
                    + " left join unit_group_member m on m.tenant_id=g.tenant_id and m.group_id=g.id"
                    + " group by g.dimension order by g.dimension")
            .map(
                r ->
                    new UnitGroupDimensionCount(
                        r.get("dimension", String.class),
                        dimensionLabel(r.get("dimension", String.class)),
                        r.get("group_count", Long.class),
                        r.get("member_count", Long.class)))
            .stream()
            .toList();
    return new UnitGroupOverview(
        units.get("all_units", Long.class),
        units.get("enabled_units", Long.class),
        groups.get("all_groups", Long.class),
        groups.get("enabled_groups", Long.class),
        members,
        conversions.get("all_conversions", Long.class),
        conversions.get("open_conversions", Long.class),
        ungrouped,
        incomplete,
        dimensions,
        conversions.get("open_conversions", Long.class)
            + " 条组内换算版本当前开放生效（effective_to 为空），其余为已闭合的历史版本，仍然可读。"
            + "改比例只影响之后按新日期换算的结果，已发生单据的比例由 3.1.2.4 的冻结快照保留。");
  }

  // ============================================================ 列表

  /**
   * 单位组列表：关键词（编码 / 名称 / 基准单位 / 成员单位名）+ 量纲 + 启用状态筛选，分页。
   *
   * <p>关键词覆盖成员单位名：运营手里往往只有一个单位（「千克」），要问的是
   * 「千克在哪个组里」，只搜组名会搜不到。成员匹配用 {@code exists} 子查询而不是 join——
   * join 会让一个有 8 个成员的组在结果里出现 8 次。
   */
  public UnitGroupPage list(UnitGroupQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    LocalDate at = asOf();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("g.tenant_id=?");
    binds.add(currentTenantId());
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // LIKE 通配符必须转义：搜「100%」时 % 不是通配符，是用户要找的那两个字。
      // 不转义的话这条搜索会变成一个几乎匹配全表的过滤器，运营看到一堆不相关的组。
      String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
      where.add(
          "(g.code ilike ? escape '!' or g.name ilike ? escape '!'"
              + " or g.base_unit_code ilike ? escape '!'"
              + " or exists(select 1 from unit_group_member m"
              + "   join unit_of_measure u on u.tenant_id=m.tenant_id and u.code=m.unit_code"
              + "   where m.tenant_id=g.tenant_id and m.group_id=g.id and u.name ilike ? escape '!'))");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    String dimension = normalizeDimension(query.dimension());
    if (!dimension.isEmpty()) {
      CatalogService.check(
          DIMENSIONS.containsKey(dimension),
          "量纲只能是 WEIGHT（重量）/ LENGTH（长度）/ VOLUME（体积）/ COUNT（数量）/ PACKAGE（包装）");
      where.add("g.dimension=?");
      binds.add(dimension);
    }
    if (query.enabled() != null) {
      where.add("g.enabled=?");
      binds.add(query.enabled());
    }
    String joined = String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from unit_group g where " + joined, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at); // SELECT_GROUP 的第一个 asOf
    pageBinds.add(at); // SELECT_GROUP 的第二个 asOf
    pageBinds.addAll(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            SELECT_GROUP + " from unit_group g where " + joined
                + " order by g.dimension,g.code limit ? offset ?",
            pageBinds.toArray());
    long enabledCount =
        sql.fetchOne(
                "select count(*) from unit_group where tenant_id=? and enabled", currentTenantId())
            .get(0, Long.class);
    return new UnitGroupPage(
        rows.stream().map(r -> toRow(r)).toList(),
        total,
        page,
        size,
        enabledCount,
        total - enabledCount,
        "统计数不受筛选条件影响：界面上把「筛出来的 N 条」与「全租户 M 个组」并排显示，"
            + "才知道自己漏看了多少。");
  }

  private UnitGroupRow toRow(Record r) {
    String dimension = r.get("dimension", String.class);
    long convertible = r.get("convertible_member_count", Long.class);
    long missing = r.get("missing_member_count", Long.class);
    return new UnitGroupRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        dimension,
        dimensionLabel(dimension),
        r.get("base_unit_code", String.class),
        r.get("base_unit_name", String.class),
        r.get("member_count", Integer.class),
        r.get("conversion_count", Long.class),
        r.get("open_conversion_count", Long.class),
        // 没有非基准成员的组（只有基准单位）不判为不完整：它本来就不需要换算版本。
        missing == 0,
        r.get("note", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  // ============================================================ 详情

  /**
   * 单位组详情：组本体 + 成员（带当天生效比例）+ 组内换算版本（按成员 + 生效起始日倒序）。
   *
   * <p>换算按成员聚成一组而不是拉平成长列表：运营问的是"千克现在几克一千克、以前是几克"，
   * 聚在一起才看得出比例改过几次。每一版都带生效区间并标出当前生效的那版。
   *
   * <p>{@code asOfText} 决定"当前生效"是按哪一天判的，不是按服务器当天——
   * 补录一条 2024 年的比例时，"哪一版当时生效"与"今天哪一版生效"必须能分开看。
   */
  public UnitGroupDetail detail(UUID id, String asOfText) {
    LocalDate at = dateOrNull(asOfText);
    LocalDate day = at == null ? asOf() : at;
    var row =
        sql.fetchOne(
            SELECT_GROUP + " from unit_group g where g.tenant_id=? and g.id=?",
            day, day, currentTenantId(), id);
    if (row == null) throw CatalogService.missing();

    var members =
        sql.fetch(
                "select m.unit_code,m.position,u.name,u.scale,u.enabled,g.base_unit_code"
                    + " from unit_group_member m"
                    + " join unit_of_measure u on u.tenant_id=m.tenant_id and u.code=m.unit_code"
                    + " join unit_group g on g.tenant_id=m.tenant_id and g.id=m.group_id"
                    + " where m.tenant_id=? and m.group_id=? order by m.position,m.unit_code",
                currentTenantId(),
                id)
            .stream()
            .map(r -> toMember(r, id, day))
            .toList();
    var conversions =
        sql.fetch(
                "select id,unit_code,quantity_per_base,effective_from,effective_to,note,created_at"
                    + " from unit_group_conversion where tenant_id=? and group_id=?"
                    + " order by unit_code,effective_from desc,effective_to desc nulls last",
                currentTenantId(),
                id)
            .stream()
            .map(r -> toConversion(r, day))
            .toList();
    return new UnitGroupDetail(
        toRow(row), day.toString(), members, conversions, conversions.size());
  }

  private UnitGroupMember toMember(Record r, UUID groupId, LocalDate at) {
    String unitCode = r.get("unit_code", String.class);
    String base = r.get("base_unit_code", String.class);
    boolean isBase = base != null && base.equals(unitCode);
    // 基准单位的比例恒为 1，不占换算行（V17 的守卫触发器也会拒）。
    Record version =
        isBase
            ? null
            : sql.fetchOne(
                "select quantity_per_base,effective_from,effective_to from unit_group_conversion"
                    + " where tenant_id=? and group_id=? and unit_code=?"
                    + " and effective_from<=? and (effective_to is null or effective_to>=?)"
                    + " order by effective_from desc limit 1",
                currentTenantId(),
                groupId,
                unitCode,
                at,
                at);
    long versions =
        sql.fetchOne(
                "select count(*) from unit_group_conversion where tenant_id=? and group_id=? and unit_code=?",
                currentTenantId(),
                groupId,
                unitCode)
            .get(0, Long.class);
    return new UnitGroupMember(
        unitCode,
        r.get("name", String.class),
        r.get("scale", Integer.class),
        r.get("position", Integer.class),
        isBase,
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        isBase
            ? BigDecimal.ONE
            : (version == null ? null : version.get("quantity_per_base", BigDecimal.class)),
        isBase
            ? null
            : (version == null ? null : version.get("effective_from", LocalDate.class).toString()),
        isBase
            ? null
            : Optional.ofNullable(version)
                .map(v -> v.get("effective_to", LocalDate.class))
                .map(LocalDate::toString)
                .orElse(null),
        versions);
  }

  private UnitGroupConversionVersion toConversion(Record r, LocalDate at) {
    LocalDate from = r.get("effective_from", LocalDate.class);
    var to = r.get("effective_to", LocalDate.class);
    return new UnitGroupConversionVersion(
        r.get("id", UUID.class),
        r.get("unit_code", String.class),
        r.get("quantity_per_base", BigDecimal.class),
        from.toString(),
        to == null ? null : to.toString(),
        effectiveOn(from, to, at),
        r.get("note", String.class),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  // ============================================================ 建 / 改

  /**
   * 建或改单位组（id 为空即新建）。成员是**整体替换**语义。
   *
   * <p>编码与量纲建后**不可改**：组编码是下游 06 / 07 / 08 按它引用换算关系的锚点；
   * 量纲改了会让已配置的换算版本在守卫触发器下一次写入时突然报「量纲不匹配」。
   * 要改就新建一个单位组。
   *
   * <p>基准单位**可以改**（蓝图没禁止这个动作），但改它会让所有已配比例的含义变化，
   * 所以旧基准单位改完会变成需要一条换算版本的普通成员；界面上要提示这一点，
   * {@code allMembersConvertible} 指标会立刻变成 false。
   *
   * <p>更新走 version 乐观锁，冲突直接 409。
   */
  public UnitGroupSaved save(AccessService.Access actor, UUID id, UnitGroupInput input) {
    String code = normalizeCode(input.code());
    String codeProblem = codeProblem(code);
    CatalogService.check(codeProblem == null, codeProblem);
    String nameProblem = nameProblem(input.name());
    CatalogService.check(nameProblem == null, nameProblem);
    String dimension = normalizeDimension(input.dimension());
    CatalogService.check(
        DIMENSIONS.containsKey(dimension),
        "量纲只能是 WEIGHT（重量）/ LENGTH（长度）/ VOLUME（体积）/ COUNT（数量）/ PACKAGE（包装）");
    String note = normalizeNote(input.note());
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");
    String baseUnit = normalizeUnitCode(input.baseUnitCode());

    // 先把组本身查出来（编辑时）再校验成员：
    // 组不可见必须报 404，而不是被后面某条成员校验抢先报成 400。
    // 否则别的租户拿一个不存在的组 id 来改，会看到"编码已存在"这种泄露存在性的报错。
    var existingGroup =
        id == null
            ? null
            : sql.fetchOne("select code,dimension,version from unit_group where id=?", id);
    if (id != null && existingGroup == null) throw CatalogService.missing();
    if (existingGroup != null) {
      CatalogService.check(
          existingGroup.get("code", String.class).equals(code),
          "单位组编码建后不可修改：下游按编码引用换算关系，请新建一个单位组");
      CatalogService.check(
          existingGroup.get("dimension", String.class).equals(dimension),
          "单位组量纲建后不可修改：改了会让已配置的组内换算在量纲守卫下一次写入时突然不通过，"
              + "请新建一个单位组");
    }

    // 成员：去重 + 保序。重复提交同一个单位不能让换算矩阵出现两行一样的单位。
    var members = new LinkedHashMap<String, Integer>();
    int position = 0;
    if (input.members() != null) {
      for (var member : input.members()) {
        String unitCode = normalizeUnitCode(member.unitCode());
        CatalogService.check(!unitCode.isEmpty(), "成员单位编码不能为空");
        members.putIfAbsent(unitCode, member.position() == null ? position : member.position());
        position++;
      }
    }
    CatalogService.check(
        members.size() <= MAX_MEMBERS,
        "一个单位组最多 " + MAX_MEMBERS + " 个成员：超过这个数说明这是「所有单位」而不是一个换算组");
    CatalogService.check(
        members.containsKey(baseUnit),
        "组基础单位「" + baseUnit + "」必须在本组成员里：它不占换算行（比例恒为 1），"
            + "但组内换算比例都是相对它定义的");

    // 每个成员都必须是已登记、启用、且与组同量纲的单位。
    // 这三条应用层先判一次：触发器的 23514 对运营没有可执行信息。
    for (String unitCode : members.keySet()) {
      var unit = sql.fetchOne("select name,dimension,enabled from unit_of_measure where code=?", unitCode);
      CatalogService.check(
          unit != null,
          "单位「" + unitCode + "」尚未登记到单位字典，请先在单位字典中新建后再加入单位组");
      CatalogService.check(
          Boolean.TRUE.equals(unit.get("enabled", Boolean.class)),
          "单位「" + unitCode + "」已停用，不能加入单位组；停用只挡住新建，历史换算仍可读");
      String unitDimension = unit.get("dimension", String.class);
      CatalogService.check(
          sameDimension(unitDimension, dimension),
          dimensionMismatchMessage(unitCode, unitDimension, "组「" + code + "」", dimension));
    }

    UUID groupId = id;
    long version;
    if (id == null) {
      CatalogService.check(
          sql.fetchOne("select id from unit_group where code=?", code) == null,
          "单位组编码已存在：" + code);
      groupId = Ids.next();
      try {
        sql.execute(
            "insert into unit_group(id,tenant_id,code,name,dimension,base_unit_code,note,enabled)"
                + " values(?,?,?,?,?,?,?,?)",
            groupId,
            actor.tenantId(),
            code,
            input.name().trim(),
            dimension,
            baseUnit,
            note,
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("单位组编码已被并发创建或占用，请刷新后重试");
      }
      version = 0;
    } else {
      // 组的存在性与「编码 / 量纲不可改」已在成员校验之前判过（见上方 existingGroup）。
      int updated;
      try {
        updated =
            sql.execute(
                "update unit_group set name=?,base_unit_code=?,note=?,enabled=?,"
                    + "version=version+1,updated_at=now() where id=? and version=?",
                input.name().trim(),
                baseUnit,
                note,
                input.enabled(),
                id,
                input.version());
      } catch (DataAccessException e) {
        throw conflict("单位组保存失败，请刷新后重试");
      }
      if (updated != 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "单位组已被他人修改，请刷新后重试");
      version = sql.fetchOne("select version from unit_group where id=?", id).get("version", Long.class);
    }

    // 成员整体替换：按**增量**删 / 插，不做「删光再全插」。
    //
    // 为什么不能用删光再全插：改基准单位时（gram → kilo），删光那一瞬旧基准单位 gram
    // 仍被 `refuse_unit_group_base_removal` 认定为基准，整次保存会被自己的守卫拒掉，
    // 结果是"改基准单位"这个动作永远做不了。增量删除让基准单位的迁移变成一次正常的
    // 「先改 base_unit_code，再删旧基准成员」。
    //
    // 被移走的单位，它的组内换算版本随之级联删除——**这是刻意的，不是疏漏**。
    // 组内换算只在组内有意义：这个单位已经不属于这个组，"1 千克 = 1000 克"这条关系
    // 也就没有归属了。留着它会让换算矩阵出现一条指向非成员单位的比例。
    // 与 V16 处理 brand_category「类目没了，品牌属于该类目的关联也就没有意义」同一口径。
    //
    // 反过来，真正不可删的是 3.1.2.4 的 `sku_unit_conversion_snapshot`：
    // 那是**已发生单据**的冻结事实，DB 层禁止 UPDATE/DELETE。组内换算是字典配置，
    // 单据快照是既成事实，两条不可变性口径不同，不要混。
    //
    // 为了让运营自己控制"删到哪一版"，已闭合的历史版本也可以单独删
    // （deleteConversion）；当前仍开放生效的那一版不允许删，否则等于静默让单位不可换算。
    var current = currentMembers(groupId);
    for (String unitCode : current) {
      if (members.containsKey(unitCode)) continue;
      sql.execute(
          "delete from unit_group_member where tenant_id=? and group_id=? and unit_code=?",
          actor.tenantId(),
          groupId,
          unitCode);
    }
    // 位置有变化的成员也要更新 position，否则整体替换后顺序不会跟着提交走。
    // 上面的删除已经把 base_unit_code 更新过了（update 组本体在前），
    // 所以移出「旧基准单位」时守卫看到的是新基准单位，会放行。
    var currentPositions = memberPositions(groupId);
    for (var entry : members.entrySet()) {
      String unitCode = entry.getKey();
      Integer target = entry.getValue();
      Integer existing = currentPositions.get(unitCode);
      if (existing != null) {
        if (!existing.equals(target))
          sql.execute(
              "update unit_group_member set position=? where tenant_id=? and group_id=? and unit_code=?",
              target,
              actor.tenantId(),
              groupId,
              unitCode);
        continue;
      }
      try {
        sql.execute(
            "insert into unit_group_member(tenant_id,group_id,unit_code,position) values(?,?,?,?)",
            actor.tenantId(),
            groupId,
            unitCode,
            target);
      } catch (DataAccessException e) {
        throw memberWriteConflict(e, unitCode, code, dimension);
      }
    }
    audit(actor, groupId, "UnitGroupSaved");
    return new UnitGroupSaved(groupId, version, members.size());
  }

  private List<String> currentMembers(UUID groupId) {
    return new ArrayList<>(memberPositions(groupId).keySet());
  }

  /** 组内现有成员 → position。keySet 按 position 升序（查询里已排序），删除顺序因此是「从后往前」。 */
  private LinkedHashMap<String, Integer> memberPositions(UUID groupId) {
    var existing = new LinkedHashMap<String, Integer>();
    for (var r :
        sql.fetch(
            "select unit_code,position from unit_group_member where tenant_id=? and group_id=?"
                + " order by position,unit_code",
            currentTenantId(),
            groupId))
      existing.put(r.get("unit_code", String.class), r.get("position", Integer.class));
    return existing;
  }

  /**
   * 成员写入失败时的解释。V17 的守卫触发器对量纲不匹配抛 23514，
   * 这里把它翻译成"换一个同量纲的单位"这种可执行的话。
   */
  private ResponseStatusException memberWriteConflict(
      DataAccessException e, String unitCode, String groupCode, String groupDimension) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("量纲不匹配")) {
      var unit = sql.fetchOne("select dimension from unit_of_measure where code=?", unitCode);
      String unitDimension = unit == null ? "未知" : unit.get("dimension", String.class);
      return conflict(
          dimensionMismatchMessage(unitCode, unitDimension, "单位组「" + groupCode + "」", groupDimension));
    }
    return conflict("单位「" + unitCode + "」写入单位组失败，请刷新后重试");
  }

  /**
   * 启用 / 停用单位组。
   *
   * <p>停用挡的是「新建换算版本」与「用这个组换算」，已配置的组内换算版本照常可读——
   * 与 {@code unit_of_measure.enabled} 同一语义：停用是"不许再新建"，不是"抹掉过去"。
   */
  public UnitGroupSaved setEnabled(AccessService.Access actor, UUID id, boolean enabled, long version) {
    int updated =
        sql.execute(
            "update unit_group set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and version=?",
            enabled,
            currentTenantId(),
            id,
            version);
    if (updated != 1) throw CatalogService.missing();
    long current =
        sql.fetchOne("select version from unit_group where id=?", id).get("version", Long.class);
    audit(actor, id, "UnitGroupEnabled");
    int members =
        sql.fetchOne(
                "select count(*) from unit_group_member where tenant_id=? and group_id=?",
                currentTenantId(),
                id)
            .get(0, Integer.class);
    return new UnitGroupSaved(id, current, members);
  }

  /**
   * 删除单位组（成员与组内换算版本随之级联删除）。
   *
   * <p>还有组内换算版本时一律拒绝：删掉组会让那些版本指向一个查不到的组。
   * 这种情况下应该停用——停用挡住「新建」，历史照常可读。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = sql.fetchOne("select code,version from unit_group where id=?", id);
    if (row == null) throw CatalogService.missing();
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "单位组已被他人修改，请刷新后重试");
    long conversions =
        sql.fetchOne("select count(*) from unit_group_conversion where group_id=?", id).get(0, Long.class);
    CatalogService.check(
        conversions == 0,
        "单位组「" + row.get("code", String.class) + "」还有 " + conversions
            + " 条组内换算版本，不能删除；请改为停用，停用只挡住新建，历史换算仍可读");
    sql.execute("delete from unit_group where id=?", id);
    audit(actor, id, "UnitGroupDeleted");
  }

  // ============================================================ 组内换算版本

  /**
   * 新增一条组内换算版本。
   *
   * <p>五条硬约束（应用层先判、DB 触发器再兜）：
   *
   * <ul>
   *   <li>单位必须是本组成员，且不是组基础单位（基准比例恒为 1，不占行）；
   *   <li>组必须启用、单位必须启用：停用挡住的是"不许再新建"；
   *   <li>量纲必须与组一致：蓝图 3.3.3.1 点名的硬约束；
   *   <li>比例必须 &gt; 0、≤6 位小数（与 numeric(19,6) 一致）；
   *   <li>区间不重叠：同一组的同一单位，同一时刻只能有一个版本生效。
   * </ul>
   */
  public UnitGroupConversionSaved addConversion(
      AccessService.Access actor, UUID groupId, UnitGroupConversionInput input) {
    var group = sql.fetchOne("select code,dimension,base_unit_code,enabled from unit_group where id=?", groupId);
    if (group == null) throw CatalogService.missing();
    String groupCode = group.get("code", String.class);
    String baseUnit = group.get("base_unit_code", String.class);
    String groupDimension = group.get("dimension", String.class);
    String unitCode = normalizeUnitCode(input.unitCode());

    CatalogService.check(
        !unitCode.equals(baseUnit),
        "单位「" + unitCode + "」就是本组的基础单位，比例恒为 1，不需要换算版本");
    CatalogService.check(
        Boolean.TRUE.equals(group.get("enabled", Boolean.class)),
        "单位组「" + groupCode + "」已停用，不能再新建换算版本；停用只挡住新建，历史换算仍可读");
    CatalogService.check(
        sql.fetchOne(
                "select 1 from unit_group_member where tenant_id=? and group_id=? and unit_code=?",
                actor.tenantId(),
                groupId,
                unitCode)
            != null,
        "单位「" + unitCode + "」不是单位组「" + groupCode
            + "」的成员，不能给它配组内换算；请先把它加入本组成员");
    var unit = sql.fetchOne("select dimension,enabled from unit_of_measure where code=?", unitCode);
    CatalogService.check(unit != null, "单位「" + unitCode + "」尚未登记到单位字典，无法做量纲校验");
    CatalogService.check(
        Boolean.TRUE.equals(unit.get("enabled", Boolean.class)),
        "单位「" + unitCode + "」已停用，不能再新建换算版本；停用只挡住新建，历史换算仍可读");
    String fromDimension = unit.get("dimension", String.class);
    CatalogService.check(
        sameDimension(fromDimension, groupDimension),
        dimensionMismatchMessage(unitCode, fromDimension, "组基准单位「" + baseUnit + "」", groupDimension));

    String ratioProblem = ratioProblem(input.quantityPerBase());
    CatalogService.check(ratioProblem == null, ratioProblem);
    String note = normalizeNote(input.note());
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");

    LocalDate from = date(input.effectiveFrom(), "生效起始日");
    LocalDate to = dateOrNull(input.effectiveTo());
    String windowProblem = windowProblem(from, to);
    CatalogService.check(windowProblem == null, windowProblem);

    // 冲突区间在插入**之前**查好：触发器一旦抛错，PostgreSQL 就把整个事务标记为 aborted，
    // 那时再查库只会得到 "current transaction is aborted"。触发器的报错要翻译成日期说明，
    // 而要说出是哪几天冲突，就只能提前查。
    var existing = existingWindows(groupId, unitCode);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into unit_group_conversion(id,tenant_id,group_id,unit_code,quantity_per_base,"
              + "effective_from,effective_to,note) values(?,?,?,?,?,?,?,?)",
          id,
          actor.tenantId(),
          groupId,
          unitCode,
          input.quantityPerBase(),
          from,
          to,
          note);
    } catch (DataAccessException e) {
      throw conversionWriteConflict(e, unitCode, baseUnit, fromDimension, groupDimension, existing);
    }
    audit(actor, groupId, "UnitGroupConversionAdded");
    return new UnitGroupConversionSaved(
        id, groupId, unitCode, input.quantityPerBase(), from.toString(),
        to == null ? null : to.toString());
  }

  /** 把 V17 的触发器报错翻译成给运营看的日期说明。 */
  private ResponseStatusException conversionWriteConflict(
      DataAccessException e,
      String unitCode,
      String baseUnit,
      String unitDimension,
      String groupDimension,
      List<Record> existing) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("单位组换算生效区间与已有版本重叠")) {
      StringBuilder detail = new StringBuilder();
      for (var r : existing) {
        var toDate = r.get("effective_to", LocalDate.class);
        detail
            .append(detail.isEmpty() ? "" : "、")
            .append(r.get("effective_from", LocalDate.class))
            .append(" ~ ")
            .append(toDate == null ? "至今" : toDate);
      }
      throw conflict(
          "组内换算生效区间与已有版本重叠（已有："
              + (detail.isEmpty() ? "无" : detail.toString())
              + "）：同一组的同一单位，同一时刻只能有一个版本生效。"
              + "请调整新区间的起止日，或把旧版本的 effective_to 闭合到新区间开始前一天");
    }
    if (message.contains("组基础单位"))
      throw conflict("组基础单位「" + unitCode + "」不需要换算版本：它的比例恒为 1");
    if (message.contains("量纲不匹配"))
      throw conflict(dimensionMismatchMessage(unitCode, unitDimension, "组基准单位「" + baseUnit + "」", groupDimension));
    throw conflict("组内换算版本写入失败，请刷新后重试");
  }

  private List<Record> existingWindows(UUID groupId, String unitCode) {
    return sql.fetch(
        "select effective_from,effective_to from unit_group_conversion"
            + " where group_id=? and unit_code=? order by effective_from desc",
        groupId,
        unitCode);
  }

  /**
   * 删除一条组内换算版本。
   *
   * <p>为什么组内换算可以删、而 3.1.2.4 的单据快照不能删：组内换算是**字典配置**，
   * 写错了就该改掉；单据冻结快照是**已发生事实**，删掉就没法对账了。
   * 两条不可变性的口径不同，所以这里给删除、那里给触发器禁止。
   *
   * <p>唯一不能删的是当前仍然开放生效（{@code effective_to is null}）的那一版：
   * 删掉它会让该单位在"今天"这个时刻没有生效版本，而换算查询不回退到历史版本——
   * 那等于静默地把这个单位变成不可换算。正确做法是先把它的 effective_to 闭合，
   * 再为下一个区间配一版。
   */
  public void deleteConversion(AccessService.Access actor, UUID groupId, UUID conversionId) {
    var row =
        sql.fetchOne(
            "select unit_code,effective_to from unit_group_conversion where tenant_id=? and group_id=? and id=?",
            currentTenantId(),
            groupId,
            conversionId);
    if (row == null) throw CatalogService.missing();
    if (row.get("effective_to", LocalDate.class) == null)
      throw conflict(
          "不能删除当前仍生效的组内换算版本：删掉之后「" + row.get("unit_code", String.class)
              + "」在今天就没有生效比例了，而换算查询不回退到历史版本。请先把它的生效截止日闭合");
    sql.execute("delete from unit_group_conversion where id=?", conversionId);
    audit(actor, groupId, "UnitGroupConversionDeleted");
  }

  // ============================================================ 换算查询（蓝图的输出）

  /**
   * 换算查询：从任意已登记单位换算到目标单位，按 asOf 取当天生效的组内版本。
   *
   * <p>这是蓝图 3.3.3.1 的输出「换算查询能力」。换不动时返回
   * {@code denied=true} + 原因码，**不抛 HTTP 错误**——「件换不成千克」是业务判定不是调用失败，
   * 调用方要拿到结构化结果去提示用户，而不是接一个异常。
   *
   * <p>判定顺序刻意如此，从最具体排到最笼统：
   *
   * <ol>
   *   <li>同单位 → {@code SELF_CONVERSION}（不是错误，是"不用换"）；
   *   <li>单位未登记 → {@code UNIT_UNREGISTERED}；
   *   <li>量纲不同 → {@code DIMENSION_MISMATCH}（蓝图点名的硬约束，先于任何组内判断）；
   *   <li>单位已停用 → {@code UNIT_DISABLED}；
   *   <li>没有同时包含两个单位的组 → {@code NOT_SAME_GROUP}；
   *   <li>组已停用 → {@code GROUP_DISABLED}；
   *   <li>当天缺生效版本 → {@code NO_EFFECTIVE_VERSION}（**不回退到最近一版**）。
   * </ol>
   *
   * <p>先判量纲再判组：蓝图的硬约束说的是"量纲不匹配的单位之间不允许换算"，
   * 这句话在"能不能同组"之前就该给出答案——用户问的正是"为什么换不了"。
   */
  public UnitGroupConvertResult convert(UnitGroupConvertInput input) {
    LocalDate parsed = dateOrNull(input.asOf());
    LocalDate day = parsed == null ? asOf() : parsed;
    String fromCode = normalizeUnitCode(input.fromUnitCode());
    String toCode = normalizeUnitCode(input.toUnitCode());
    CatalogService.check(!fromCode.isEmpty(), "源单位编码不能为空");
    CatalogService.check(!toCode.isEmpty(), "目标单位编码不能为空");
    CatalogService.check(input.quantity().signum() > 0, "数量必须大于 0");
    CatalogService.check(input.quantity().scale() <= 6, "数量最多 6 位小数");

    UnitRef from = unitRef(fromCode);
    UnitRef to = unitRef(toCode);
    if (fromCode.equals(toCode)) {
      // 同单位不换算：这是"无需换算"而不是错误，但它要给调用方一个明确答案而不是空结果。
      return denied(
          DENY_SELF_CONVERSION,
          "源单位与目标单位相同（" + fromCode + "），无需换算",
          from, to, input.quantity(), day, "同单位换算的比例恒为 1，界面直接回显原数量即可");
    }
    if (from == null || to == null) {
      String missing = from == null ? fromCode : toCode;
      return denied(
          DENY_UNIT_UNREGISTERED,
          "单位「" + missing + "」尚未登记到单位字典，无法换算；请先在单位字典中新建",
          from, to, input.quantity(), day, "未登记的单位连量纲都无从判断，所以换算查询在查组之前就停下了");
    }
    if (!sameDimension(from.dimension(), to.dimension())) {
      return denied(
          DENY_DIMENSION_MISMATCH,
          dimensionMismatchMessage(fromCode, from.dimension(), toCode, to.dimension()),
          from, to, input.quantity(), day, "蓝图 3.3.3.1 的关系约束：量纲不匹配的单位之间不允许换算");
    }
    for (UnitRef unit : List.of(from, to)) {
      if (!unit.enabled()) {
        return denied(
            DENY_UNIT_DISABLED,
            "单位「" + unit.code() + "」已停用，不能用于新的换算；停用只挡住新建，历史换算仍可读",
            from, to, input.quantity(), day, "停用是「不许再新建」，不是「抹掉过去」");
      }
    }

    // 同时包含两个单位、且启用的组。基准单位也参与匹配：它可以是组的 base_unit_code。
    var group =
        sql.fetchOne(
            "select g.id,g.code,g.name,g.base_unit_code from unit_group g"
                + " where g.tenant_id=? and g.enabled"
                + "  and exists(select 1 from unit_group_member m where m.tenant_id=g.tenant_id"
                + "   and m.group_id=g.id and m.unit_code=?)"
                + "  and exists(select 1 from unit_group_member m where m.tenant_id=g.tenant_id"
                + "   and m.group_id=g.id and m.unit_code=?)"
                + " order by g.code limit 1",
            currentTenantId(),
            fromCode,
            toCode);
    if (group == null) {
      // 再问一次「有没有含两者的停用组」：命中时给出更准的原因码，
      // 否则界面上会把「组停用了」显示成「不在同一个单位组」，运营去错地方找。
      var disabled =
          sql.fetchOne(
              "select g.id from unit_group g where g.tenant_id=? and not g.enabled"
                  + "  and exists(select 1 from unit_group_member m where m.tenant_id=g.tenant_id"
                  + "   and m.group_id=g.id and m.unit_code=?)"
                  + "  and exists(select 1 from unit_group_member m where m.tenant_id=g.tenant_id"
                  + "   and m.group_id=g.id and m.unit_code=?) limit 1",
              currentTenantId(),
              fromCode,
              toCode);
      return disabled == null
          ? denied(
              DENY_NOT_SAME_GROUP,
              "「" + fromCode + "」与「" + toCode + "」不属于同一个单位组，无法换算；"
                  + "请把它们放进同一个同量纲的单位组",
              from, to, input.quantity(), day, "同量纲只是必要条件：两个单位还需要被维护进同一个单位组才谈得上换算")
          : denied(
              DENY_GROUP_DISABLED,
              "包含「" + fromCode + "」与「" + toCode + "」的单位组已停用，不能用于新的换算；"
                  + "停用只挡住新建，历史换算仍可读",
              from, to, input.quantity(), day, "启用该单位组后即可换算");
    }

    UUID groupId = group.get("id", UUID.class);
    String groupCode = group.get("code", String.class);
    String baseUnit = group.get("base_unit_code", String.class);
    BigDecimal fromRatio = ratioOn(groupId, fromCode, baseUnit, day);
    if (fromRatio == null) {
      return deniedWithGroup(
          DENY_NO_EFFECTIVE_VERSION,
          "单位「" + fromCode + "」在 " + day + " 没有生效的组内换算版本。"
              + "请先配置该单位的组内换算版本并让生效区间覆盖这一天——这里不做「取最近一版」的回退，"
              + "否则一张按过期比例生成的采购单会看起来完全正常",
          from, to, input.quantity(), day, groupId, groupCode, group.get("name", String.class), baseUnit, null, null,
          "组内换算版本与 SKU 侧换算版本是同一套版本化语义");
    }
    BigDecimal toRatio = ratioOn(groupId, toCode, baseUnit, day);
    if (toRatio == null) {
      return deniedWithGroup(
          DENY_NO_EFFECTIVE_VERSION,
          "单位「" + toCode + "」在 " + day + " 没有生效的组内换算版本。"
              + "请先配置该单位的组内换算版本并让生效区间覆盖这一天",
          from, to, input.quantity(), day, groupId, groupCode, group.get("name", String.class), baseUnit, fromRatio, null,
          "换算结果依赖两个单位当天各自的比例，任一缺失都不能给答案");
    }

    // 全限定名而不是静态导入的 convert：本类的 convert(UnitGroupConvertInput) 与
    // UnitGroupRules.convert(BigDecimal,...) 同名，裸调用会解析到本类的方法上。
    BigDecimal converted = UnitGroupRules.convert(input.quantity(), fromRatio, toRatio);
    return new UnitGroupConvertResult(
        false, null, null,
        fromCode, from.name(), toCode, to.name(),
        input.quantity(), converted, from.dimension(), to.dimension(),
        groupId, groupCode, group.get("name", String.class), baseUnit,
        fromRatio, toRatio, day.toString(),
        "1 " + fromCode + " = " + fromRatio.toPlainString() + " " + baseUnit + "，1 " + toCode + " = "
            + toRatio.toPlainString() + " " + baseUnit + "，按 " + input.quantity().toPlainString()
            + " × " + fromRatio.toPlainString() + " ÷ " + toRatio.toPlainString() + " 得出");
  }

  private UnitRef unitRef(String code) {
    var row = sql.fetchOne("select name,dimension,enabled from unit_of_measure where code=?", code);
    return row == null
        ? null
        : new UnitRef(
            code,
            row.get("name", String.class),
            row.get("dimension", String.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
  }

  /**
   * 取某个单位在组内当天生效的比例。基准单位返回 1（它不占换算行）。
   * 查不到就返回 null，由调用方报 {@link UnitGroupRules#DENY_NO_EFFECTIVE_VERSION}。
   */
  private BigDecimal ratioOn(UUID groupId, String unitCode, String baseUnit, LocalDate at) {
    if (unitCode.equals(baseUnit)) return BigDecimal.ONE;
    var row =
        sql.fetchOne(
            "select quantity_per_base from unit_group_conversion"
                + " where tenant_id=? and group_id=? and unit_code=?"
                + " and effective_from<=? and (effective_to is null or effective_to>=?)"
                + " order by effective_from desc limit 1",
            currentTenantId(),
            groupId,
            unitCode,
            at,
            at);
    return row == null ? null : row.get("quantity_per_base", BigDecimal.class);
  }

  private UnitGroupConvertResult denied(
      String code,
      String message,
      UnitRef from,
      UnitRef to,
      BigDecimal quantity,
      LocalDate at,
      String note) {
    return deniedWithGroup(code, message, from, to, quantity, at, null, null, null, null, null, null, note);
  }

  private UnitGroupConvertResult deniedWithGroup(
      String code,
      String message,
      UnitRef from,
      UnitRef to,
      BigDecimal quantity,
      LocalDate at,
      UUID groupId,
      String groupCode,
      String groupName,
      String baseUnit,
      BigDecimal fromRatio,
      BigDecimal toRatio,
      String note) {
    return new UnitGroupConvertResult(
        true,
        code,
        message,
        from == null ? "" : from.code(),
        from == null ? null : from.name(),
        to == null ? "" : to.code(),
        to == null ? null : to.name(),
        quantity,
        null,
        from == null ? null : from.dimension(),
        to == null ? null : to.dimension(),
        groupId,
        groupCode,
        groupName,
        baseUnit,
        fromRatio,
        toRatio,
        at.toString(),
        note);
  }

  // ============================================================ 工具

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 AccessService.run 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static LocalDate date(String value, String label) {
    LocalDate parsed = dateOrNull(value);
    if (parsed == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "需为 YYYY-MM-DD");
    return parsed;
  }

  private static LocalDate dateOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式需为 yyyy-MM-dd，例如 2026-12-31");
    }
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}