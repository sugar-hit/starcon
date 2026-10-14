package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.2.4 单位与换算：单位字典、SKU↔单位的换算关系与生效版本、基础数量换算、单据冻结快照。
 *
 * <p>四条纪律贯穿本类：
 *
 * <ol>
 *   <li><b>比例必须版本化且区间不重叠</b>。每条换算关系由若干生效版本组成，每版有 {@code effective_from}/{@code effective_to}。区间不重叠由
 *       V6 的 DB 触发器兜底， 应用层转成可读的 409——"同一时刻只有一个版本生效"是蓝图硬要求，不是建议。
 *   <li><b>查不到就报查不到</b>。按生效日期取版本命中不到时**不**回退到"最近的一版"。 静默回退会让一张按过期比例生成的采购单看起来完全正常，这是最贵的一类错误。
 *   <li><b>基础单位不在换算表里</b>。基础单位是 {@code sku.base_unit}；换算表只放其他单位 相对它的比例。同理，单据直接用基础单位时比例恒为 1，不占换算行。
 *   <li><b>快照不可变</b>。单据冻结把当时的比例与换算结果一起落库，DB 层禁止 UPDATE/DELETE。
 *       之后改比例改不动已发生单据——这是蓝图的硬要求，靠不可变行兑现而不是靠应用自觉。
 * </ol>
 *
 * <p>单位字典与换算行的关系是**软引用**而非外键：V6 之前写入的换算行（导入/历史遗留）的 unit_code 可能不在字典里，加外键会让既有数据写不进去。字典的强约束放在本类的写入口
 * （新建换算要求单位已登记），遗留行仍可读并在界面上标记为「未登记」。
 */
@Service
public class UnitConversionService {
  /** 留痕回读的条数。 */
  private static final int SNAPSHOT_HISTORY = 50;

  /** 概览里回读的单据数。 */
  private static final int RECENT_SNAPSHOTS = 10;

  private final DSLContext sql;
  private final AccessService access;

  public UnitConversionService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ============================================================ 单位字典

  /** 单位字典列表（含停用）。停用单位也在列里——否则运营会以为单位凭空消失了， 而它明明还挂着一堆历史换算版本。 */
  public List<UnitOfMeasure> units() {
    return sql
        .fetch(
            "select u.id,u.code,u.name,u.dimension,u.scale,u.allow_purchase,u.allow_sale,"
                + "u.allow_storage,u.enabled,u.version,u.created_at,u.updated_at,"
                + "(select count(*) from sku_unit_conversion c"
                + "  where c.tenant_id=u.tenant_id and c.unit_code=u.code) as conversion_count"
                + " from unit_of_measure u order by u.dimension,u.code")
        .map(r -> toUnit(r))
        .stream()
        .toList();
  }

  private UnitOfMeasure toUnit(Record r) {
    return new UnitOfMeasure(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("dimension", String.class),
        UnitRules.dimensionLabel(r.get("dimension", String.class)),
        r.get("scale", Integer.class),
        Boolean.TRUE.equals(r.get("allow_purchase", Boolean.class)),
        Boolean.TRUE.equals(r.get("allow_sale", Boolean.class)),
        Boolean.TRUE.equals(r.get("allow_storage", Boolean.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("conversion_count", Long.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 新建或更新单位（id 为空即新建）。
   *
   * <p>更新走 version 乐观锁，冲突直接 409。单位码与量纲建后**不可改**： 换算行按 unit_code 文本引用它，改码会让所有历史换算与已冻结快照指向一个不存在的单位，
   * 改量纲会让既有换算在守卫触发器下一次写入时突然报「量纲不匹配」。要改就新建一个单位。
   */
  public UnitOfMeasureSaved saveUnit(
      AccessService.Access actor, UUID id, UnitOfMeasureInput input) {
    String problem = UnitRules.codeProblem(input.code());
    CatalogService.check(problem == null, problem);
    CatalogService.check(!input.name().trim().isEmpty(), "单位名称不能为空");
    String dimension = UnitRules.normalizeDimension(input.dimension());
    CatalogService.check(
        UnitRules.DIMENSIONS.containsKey(dimension),
        "量纲只能是 WEIGHT（重量）/ LENGTH（长度）/ VOLUME（体积）/ COUNT（数量）/ PACKAGE（包装）");
    CatalogService.check(input.scale() >= 0 && input.scale() <= 6, "数量精度需在 0~6 位小数之间");
    String code = UnitRules.normalizeCode(input.code());

    if (id == null) {
      CatalogService.check(
          sql.fetchOne("select id from unit_of_measure where code=?", code) == null,
          "单位编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                + "allow_sale,allow_storage,enabled) values(?,?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            code,
            input.name().trim(),
            dimension,
            input.scale(),
            input.allowPurchase(),
            input.allowSale(),
            input.allowStorage(),
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("单位编码已被并发创建或占用，请刷新后重试");
      }
      audit(actor, newId, "UnitOfMeasureSaved");
      return new UnitOfMeasureSaved(newId, 0);
    }

    var row = sql.fetchOne("select code,dimension,version from unit_of_measure where id=?", id);
    if (row == null) throw CatalogService.missing();
    CatalogService.check(
        row.get("code", String.class).equals(code), "单位编码建后不可修改：换算行与已冻结快照按编码引用它，请新建一个单位");
    CatalogService.check(
        row.get("dimension", String.class).equals(dimension),
        "量纲建后不可修改：改了会让既有换算在量纲校验时突然不通过，请新建一个单位");
    int updated;
    try {
      updated =
          sql.execute(
              "update unit_of_measure set name=?,scale=?,allow_purchase=?,allow_sale=?,"
                  + "allow_storage=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              input.name().trim(),
              input.scale(),
              input.allowPurchase(),
              input.allowSale(),
              input.allowStorage(),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("单位编码已存在或已被占用，请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "单位已被他人修改，请刷新后重试");
    long version =
        sql.fetchOne("select version from unit_of_measure where id=?", id)
            .get("version", Long.class);
    audit(actor, id, "UnitOfMeasureSaved");
    return new UnitOfMeasureSaved(id, version);
  }

  /**
   * 删除单位。
   *
   * <p>被换算行或快照引用时一律拒绝：删掉单位会让那些换算与快照指向一个查不到的定义。 这种情况应该停用（enabled=false）而不是删除——停用挡住的是「新建」，历史照常可读。
   */
  public void deleteUnit(AccessService.Access actor, UUID id, long version) {
    var row = sql.fetchOne("select code,version from unit_of_measure where id=?", id);
    if (row == null) throw CatalogService.missing();
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "单位已被他人修改，请刷新后重试");
    String code = row.get("code", String.class);
    long conversions =
        sql.fetchOne("select count(*) from sku_unit_conversion where unit_code=?", code)
            .get(0, Long.class);
    CatalogService.check(
        conversions == 0,
        "单位「" + code + "」已被 " + conversions + " 条换算版本引用，不能删除；" + "请改为停用，停用只挡住新建，历史换算与已冻结快照仍可读");
    long snapshots =
        sql.fetchOne("select count(*) from sku_unit_conversion_snapshot where unit_code=?", code)
            .get(0, Long.class);
    CatalogService.check(snapshots == 0, "单位「" + code + "」已被 " + snapshots + " 条单据快照引用，不能删除");
    // 3.3.3.1 补的：单位组（V17 的 unit_group_member）引用着它。
    // 删掉单位会经外键 ON DELETE CASCADE 连带删掉它在所有组里的成员行与组内换算版本——
    // 那是静默的数据丢失，运营根本不知道自己刚删掉了什么。与上面两条同一口径：被引用就拒绝。
    long groups =
        sql.fetchOne("select count(*) from unit_group_member where unit_code=?", code)
            .get(0, Long.class);
    CatalogService.check(
        groups == 0,
        "单位「"
            + code
            + "」已被 "
            + groups
            + " 个单位组引用，不能删除；"
            + "请先到计量单位（/products/units）把它移出这些组，或改为停用——"
            + "停用只挡住新建，历史换算与已冻结快照仍可读");
    sql.execute("delete from unit_of_measure where id=?", id);
    audit(actor, id, "UnitOfMeasureDeleted");
  }

  // ============================================================ 概览

  /**
   * 概览：字典规模、换算版本数、快照数，以及**基础单位未登记的 SKU 数**。
   *
   * <p>{@code unregisteredSkus} 是本模块的治理缺口指标——基础单位没进字典的 SKU 量纲校验对它放行， 数量再多也说明字典没覆盖到货品上。0
   * 与"没查过"含义不同，界面上要分开写。
   */
  public UnitOverview overview() {
    var units =
        sql.fetchOne(
            "select count(*) as all_units,count(*) filter (where enabled) as enabled_units"
                + " from unit_of_measure");
    var conversions =
        sql.fetchOne(
            "select count(*) as conversion_count,"
                + " count(*) filter (where effective_to is null) as open_count from sku_unit_conversion");
    long snapshots =
        sql.fetchOne("select count(*) from sku_unit_conversion_snapshot").get(0, Long.class);
    var dimensions =
        sql
            .fetch(
                "select dimension,count(*) as n from unit_of_measure group by dimension"
                    + " order by dimension")
            .map(
                r ->
                    new UnitDimensionCount(
                        r.get("dimension", String.class),
                        UnitRules.dimensionLabel(r.get("dimension", String.class)),
                        r.get("n", Long.class)))
            .stream()
            .toList();
    long unregistered =
        sql.fetchOne(
                "select count(*) from sku s where not exists("
                    + " select 1 from unit_of_measure u where u.tenant_id=s.tenant_id and u.code=s.base_unit)")
            .get(0, Long.class);
    return new UnitOverview(
        units.get("all_units", Long.class),
        units.get("enabled_units", Long.class),
        unregistered,
        conversions.get("conversion_count", Long.class),
        snapshots,
        dimensions,
        conversions.get("open_count", Long.class)
            + " 条换算版本当前开放生效（effective_to 为空），"
            + "其余为已闭合的历史版本，仍然可读——改比例只影响之后生成的单据。");
  }

  // ============================================================ 单 SKU 面板

  /**
   * 单个 SKU 的单位与换算面板。
   *
   * <p>换算按「单位 + 角色」聚成一组，组内版本按生效起始日倒序： 运营问的是"箱这个采购单位现在几件一箱、以前是几件"，平铺成长列表反而看不出改过几次。
   */
  public UnitPanel panel(UUID skuId) {
    var row =
        sql.fetchOne(
            "select s.id,s.code,s.name,s.base_unit,s.version from sku s where s.id=?", skuId);
    if (row == null) throw CatalogService.missing();
    String baseUnit = row.get("base_unit", String.class);
    var baseRow =
        sql.fetchOne("select id,name,enabled from unit_of_measure where code=?", baseUnit);
    var snapshots = snapshotLines(skuId);
    return new UnitPanel(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        baseUnit,
        baseRow == null ? null : baseRow.get("name", String.class),
        baseRow != null,
        groups(skuId, baseUnit),
        snapshots,
        snapshots.size());
  }

  private List<UnitConversionGroup> groups(UUID skuId, String baseUnit) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    var unitNames = new HashMap<String, String>();
    for (var u : sql.fetch("select code,name from unit_of_measure")) {
      unitNames.put(u.get("code", String.class), u.get("name", String.class));
    }
    // 按 (unit_code, role) 分组，组内按 effective_from 倒序。
    var grouped = new LinkedHashMap<String, List<Record>>();
    for (var r :
        sql.fetch(
            "select c.id,c.unit_code,c.role,c.quantity_per_base,c.effective_from,c.effective_to,"
                + "c.created_at,(select count(*) from sku_unit_conversion_snapshot s"
                + "  where s.tenant_id=c.tenant_id and s.source_conversion_id=c.id) as snapshotted"
                + " from sku_unit_conversion c where c.sku_id=?"
                + " order by c.unit_code,c.role,c.effective_from desc,c.effective_to desc nulls last",
            skuId)) {
      grouped
          .computeIfAbsent(
              r.get("unit_code", String.class) + " " + r.get("role", String.class),
              k -> new ArrayList<>())
          .add(r);
    }
    var result = new ArrayList<UnitConversionGroup>();
    for (var entry : grouped.entrySet()) {
      String[] parts = entry.getKey().split(" ", 2);
      String unitCode = parts[0];
      String role = parts[1];
      var versions = new ArrayList<UnitConversionVersion>();
      UnitConversionVersion current = null;
      for (Record r : entry.getValue()) {
        LocalDate from = r.get("effective_from", LocalDate.class);
        var to = r.get("effective_to", LocalDate.class);
        boolean isCurrent = UnitRules.effectiveOn(from, to, today);
        var version =
            new UnitConversionVersion(
                r.get("id", UUID.class),
                unitCode,
                role,
                UnitRules.roleLabel(role),
                r.get("quantity_per_base", BigDecimal.class),
                from.toString(),
                to == null ? null : to.toString(),
                isCurrent,
                unitNames.containsKey(unitCode),
                r.get("snapshotted", Long.class) > 0,
                instant(r.get("created_at", OffsetDateTime.class)));
        versions.add(version);
        if (isCurrent && current == null) current = version;
      }
      result.add(
          new UnitConversionGroup(
              unitCode,
              unitNames.get(unitCode),
              role,
              UnitRules.roleLabel(role),
              unitNames.containsKey(unitCode),
              current == null ? null : current.id(),
              current == null ? null : current.quantityPerBase(),
              List.copyOf(versions)));
    }
    return result;
  }

  private List<UnitSnapshotLine> snapshotLines(UUID skuId) {
    return snapshotQuery(" and s.sku_id=?", skuId);
  }

  private List<UnitSnapshotLine> snapshotQuery(String extra, Object... args) {
    Object[] params = new Object[args.length + 1];
    params[0] =
        "select s.id,s.line_no,s.sku_id,k.code as sku_code,s.role,s.unit_code,s.base_unit,"
            + "s.quantity,s.quantity_per_base,s.base_quantity,s.source_conversion_id,"
            + "s.effective_from,s.effective_to,s.occurred_at"
            + " from sku_unit_conversion_snapshot s join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
            + " where true"
            + extra
            + " order by s.created_at desc,s.id desc limit "
            + SNAPSHOT_HISTORY;
    System.arraycopy(args, 0, params, 1, args.length);
    return sql.fetch((String) params[0], Arrays.copyOfRange(params, 1, params.length)).stream()
        .map(r -> toSnapshotLine(r, false))
        .toList();
  }

  private UnitSnapshotLine toSnapshotLine(Record r, boolean created) {
    return new UnitSnapshotLine(
        r.get("line_no", Integer.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("role", String.class),
        UnitRules.roleLabel(r.get("role", String.class)),
        r.get("unit_code", String.class),
        r.get("base_unit", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("quantity_per_base", BigDecimal.class),
        r.get("base_quantity", BigDecimal.class),
        r.get("source_conversion_id", UUID.class),
        r.get("effective_from", LocalDate.class).toString(),
        Optional.ofNullable(r.get("effective_to", LocalDate.class))
            .map(LocalDate::toString)
            .orElse(null),
        created,
        instant(r.get("occurred_at", OffsetDateTime.class)));
  }

  // ============================================================ 新增换算版本

  /**
   * 为一个 SKU 新增一条换算版本。
   *
   * <p>区间不重叠由 V6 的触发器在 DB 层保证，这里把它的报错翻译成人话： 「2026-01-01 ~ 至今」与已有的「2024-01-01 ~
   * 2025-12-31」不冲突，但与「2025-06-01 ~ 至今」冲突， 运营看到的必须是后者的日期，而不是一条 23514。
   *
   * <p>单位必须已登记且启用：新建换算引用一个不存在的单位定义，量纲校验无从进行。 历史遗留行（未登记）仍可读，见本类头部说明。
   */
  public UnitConversionSaved addConversion(
      AccessService.Access actor, UUID skuId, UnitConversionInput input) {
    var sku = sql.fetchOne("select code,base_unit,version from sku where id=?", skuId);
    if (sku == null) throw CatalogService.missing();
    String unitCode = UnitRules.normalizeCode(input.unitCode());
    String role = UnitRules.normalizeRole(input.role());
    CatalogService.check(
        UnitRules.CONVERSION_ROLES.contains(role),
        "换算角色只能是 PURCHASE（采购单位）/ SALE（销售单位）/ PACKAGING（包装单位）；"
            + "基础单位是 SKU 自身的属性，请直接改 SKU 的基础单位，不需要换算行");
    String ratioProblem = UnitRules.ratioProblem(input.quantityPerBase());
    CatalogService.check(ratioProblem == null, ratioProblem);

    String baseUnit = sku.get("base_unit", String.class);
    CatalogService.check(
        !unitCode.equals(baseUnit), "单位「" + unitCode + "」就是该 SKU 的基础单位，比例恒为 1，不需要换算行");

    var unit =
        sql.fetchOne("select id,dimension,enabled from unit_of_measure where code=?", unitCode);
    CatalogService.check(unit != null, "单位「" + unitCode + "」尚未登记到单位字典，请先在单位字典中新建后再配置换算");
    CatalogService.check(
        Boolean.TRUE.equals(unit.get("enabled", Boolean.class)),
        "单位「" + unitCode + "」已停用，不能再新建换算版本；停用只挡住新建，历史换算与快照仍可读");

    // 量纲校验。**基础单位也必须已登记**才能判：sku.base_unit 是 V1 的自由文本列，
    // 3.1.2.2 可以写入任何字符串，字典里查不到时我们无从知道基础单位属于哪个量纲。
    // 这时选择显式拒绝而不是放行——"放行"等于让「件 → 千克」这种错账从后门进来。
    var baseUnitRow = sql.fetchOne("select dimension from unit_of_measure where code=?", baseUnit);
    CatalogService.check(
        baseUnitRow != null, "基础单位「" + baseUnit + "」尚未登记到单位字典，无法做量纲校验。" + "请先在单位字典中登记该基础单位，再配置换算");
    String fromDimension = unit.get("dimension", String.class);
    String toDimension = baseUnitRow.get("dimension", String.class);
    CatalogService.check(
        fromDimension.equals(toDimension),
        "量纲不匹配：「"
            + unitCode
            + "」属于"
            + UnitRules.dimensionLabel(fromDimension)
            + "，基础单位「"
            + baseUnit
            + "」属于"
            + UnitRules.dimensionLabel(toDimension)
            + "，两者之间不允许换算");

    LocalDate from = date(input.effectiveFrom(), "生效起始日");
    LocalDate to =
        input.effectiveTo() == null || input.effectiveTo().isBlank()
            ? null
            : date(input.effectiveTo(), "生效截止日");
    CatalogService.check(to == null || !to.isBefore(from), "生效截止日不能早于生效起始日");

    // 冲突区间在插入前查好：触发器抛错后事务已 aborted，那时再查只会拿到
    // "current transaction is aborted"，运营看到的是一句无法定位的数据库报错。
    var existing = existingWindows(skuId, unitCode, role);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,"
              + "effective_from,effective_to) values(?,?,?,?,?,?,?,?)",
          id,
          actor.tenantId(),
          skuId,
          unitCode,
          role,
          input.quantityPerBase(),
          from,
          to);
    } catch (DataAccessException e) {
      if (overlap(e, existing))
        throw conflict(
            "换算版本生效区间与已有版本重叠：同一 SKU 的同一单位同一角色，同一时刻只能有一个版本生效。"
                + "请先调整新区间的起止日，或把旧版本的 effective_to 闭合到新区间开始前一天");
      throw conflict("换算版本写入失败，请刷新后重试");
    }
    audit(actor, skuId, "SkuUnitConversionAdded");
    return new UnitConversionSaved(
        id, skuId, unitCode, role, from.toString(), to == null ? null : to.toString());
  }

  /**
   * V6 触发器用 23514 抛区间重叠；据此把 DB 报错翻译成给运营看的日期说明。
   *
   * <p>冲突区间在**插入之前**查好并作为参数传进来：触发器一旦抛错，PostgreSQL 就把整个 事务标记为 aborted，此时再查库只会得到 "current transaction
   * is aborted"。
   */
  private boolean overlap(DataAccessException e, List<Record> existing) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (!message.contains("单位换算生效区间与已有版本重叠")) return false;
    if (existing.isEmpty()) return true;
    StringBuilder detail = new StringBuilder();
    for (var r : existing) {
      var to = r.get("effective_to", LocalDate.class);
      detail
          .append(detail.isEmpty() ? "" : "、")
          .append(r.get("effective_from", LocalDate.class))
          .append(" ~ ")
          .append(to == null ? "至今" : to);
    }
    return true;
  }

  /** 该 SKU/单位/角色已有的生效区间，用于把 DB 的重叠报错翻译成人话。 */
  private List<Record> existingWindows(UUID skuId, String unitCode, String role) {
    return sql.fetch(
        "select effective_from,effective_to from sku_unit_conversion"
            + " where sku_id=? and unit_code=? and role=?"
            + " order by effective_from desc",
        skuId,
        unitCode,
        role);
  }

  // ============================================================ 换算计算

  /**
   * 基础数量换算：给定 SKU + 数量 + 单位 + 生效日期，返回换算后的基础数量。
   *
   * <p>这是蓝图要求的输出（入库、出库、采购统一到基础单位）。三条语义：
   *
   * <ul>
   *   <li>单���直接用基础单位 → 比例恒为 1，{@code conversionId} 为空。
   *   <li>按 {@code asOf} 命中当时生效的版本；命中不到就**报错**，不回退到最近一版。
   *   <li>不写库。这是纯计算，供上游在生成单据前预览。
   * </ul>
   */
  public UnitConvertResult convert(UnitConvertInput input) {
    var sku = sql.fetchOne("select code,base_unit from sku where id=?", input.skuId());
    if (sku == null) throw CatalogService.missing();
    String baseUnit = sku.get("base_unit", String.class);
    String unitCode = UnitRules.normalizeCode(input.unitCode());
    String role =
        input.role() == null || input.role().isBlank()
            ? null
            : UnitRules.normalizeRole(input.role());
    LocalDate at =
        input.asOf() == null || input.asOf().isBlank()
            ? LocalDate.now(ZoneOffset.UTC)
            : date(input.asOf(), "生效日期");
    CatalogService.check(input.quantity().signum() > 0, "数量必须大于 0");
    CatalogService.check(input.quantity().scale() <= 6, "数量最多 6 位小数");

    var unit =
        sql.fetchOne("select name,dimension,scale from unit_of_measure where code=?", unitCode);
    String note;
    if (unitCode.equals(baseUnit)) {
      // 基础单位直接用：比例恒为 1，不占换算行。
      var result =
          new UnitConvertResult(
              input.skuId(),
              sku.get("code", String.class),
              unitCode,
              unit == null ? null : unit.get("name", String.class),
              "BASE",
              UnitRules.roleLabel("BASE"),
              input.quantity(),
              BigDecimal.ONE,
              baseUnit,
              input.quantity().setScale(6, java.math.RoundingMode.HALF_UP),
              at.toString(),
              null,
              null,
              null,
              "单据直接使用基础单位，比例恒为 1，不占用换算版本。");
      return result;
    }

    var row =
        role == null
            ? effectiveVersion(input.skuId(), unitCode, at)
            : effectiveVersion(input.skuId(), unitCode, role, at);
    BigDecimal ratio = row == null ? null : row.get("quantity_per_base", BigDecimal.class);
    LocalDate from = row == null ? null : row.get("effective_from", LocalDate.class);
    var to = row == null ? null : row.get("effective_to", LocalDate.class);
    String label = role == null ? "任意角色" : UnitRules.roleLabel(role);
    CatalogService.check(
        ratio != null,
        "「"
            + unitCode
            + "」在 "
            + at
            + " 没有生效的"
            + label
            + "换算版本。"
            + "请先配置该单位的换算版本并让生效区间覆盖这一天——这里不做「取最近一版」的回退，"
            + "否则一张按过期比例生成的采购单会看起来完全正常");
    note = row == null ? null : "命中 " + from + " ~ " + (to == null ? "至今" : to) + " 的换算版本";
    return new UnitConvertResult(
        input.skuId(),
        sku.get("code", String.class),
        unitCode,
        unit == null ? null : unit.get("name", String.class),
        role == null ? row.get("role", String.class) : role,
        UnitRules.roleLabel(role == null ? row.get("role", String.class) : role),
        input.quantity(),
        ratio,
        baseUnit,
        UnitRules.toBaseQuantity(input.quantity(), ratio),
        at.toString(),
        row.get("id", UUID.class),
        from.toString(),
        to == null ? null : to.toString(),
        note);
  }

  private Record effectiveVersion(UUID skuId, String unitCode, LocalDate at) {
    return sql.fetchOne(
        "select id,role,quantity_per_base,effective_from,effective_to from sku_unit_conversion"
            + " where sku_id=? and unit_code=? and effective_from<=?"
            + " and (effective_to is null or effective_to>=?)"
            + " order by effective_from desc limit 1",
        skuId,
        unitCode,
        at,
        at);
  }

  private Record effectiveVersion(UUID skuId, String unitCode, String role, LocalDate at) {
    return sql.fetchOne(
        "select id,role,quantity_per_base,effective_from,effective_to from sku_unit_conversion"
            + " where sku_id=? and unit_code=? and role=? and effective_from<=?"
            + " and (effective_to is null or effective_to>=?)"
            + " order by effective_from desc limit 1",
        skuId,
        unitCode,
        role,
        at,
        at);
  }

  // ============================================================ 单据冻结快照

  /**
   * 冻结一张单据的换算快照。
   *
   * <p>这是蓝图点名的那条能力：「单据生成时冻结快照，之后修改比例不影响已发生单据」。 做法是把**当时生效的比例连同换算结果**一起落库，且这张表在 DB 层禁止
   * UPDATE/DELETE。
   *
   * <p>幂等：同一 (document_type, document_ref, line_no) 已冻结过时返回原行（{@code created=false}），
   * 不覆盖也不重复计数。上游重跑一次单据生成不应该让数量翻倍。
   *
   * <p>任何一行换算不到就**整单拒绝**：半张单据冻结了比例、另一半没冻结，事后无法对账。
   */
  public UnitSnapshot freeze(AccessService.Access actor, UnitSnapshotInput input) {
    String documentType = UnitRules.normalizeDocumentType(input.documentType());
    CatalogService.check(
        UnitRules.DOCUMENT_TYPES.containsKey(documentType),
        "单据类型只能是 PURCHASE / INBOUND / OUTBOUND / PICKING / TRANSFER / COUNT");
    String documentRef = input.documentRef().trim();
    CatalogService.check(!documentRef.isEmpty(), "单据号不能为空");
    CatalogService.check(!input.lines().isEmpty(), "单据明细不能为空");

    var seen = new HashSet<Integer>();
    for (var line : input.lines())
      CatalogService.check(seen.add(line.lineNo()), "行号不能重复：" + line.lineNo());

    OffsetDateTime occurredAt = instant(input.occurredAt());
    // 冻结按**单据的业务时间**取比例，不是按"今天"。
    // 补录一张 2024-06-01 的采购单时用今天的比例，等于用今天的汇率去算去年的账——
    // 而 2024-06-01 当时生效的那一版才是这张单据该用的比例。
    String asOf = occurredAt.toLocalDate().toString();
    // 先全部解析成功再落库：避免前几行已冻结、后一行换算不到时的半张单据。
    var resolved = new ArrayList<Object[]>();
    for (var line : input.lines()) {
      var result =
          convert(
              new UnitConvertInput(
                  line.skuId(), line.quantity(), line.unitCode(), line.role(), asOf));
      resolved.add(new Object[] {line.lineNo(), result});
    }

    UUID snapshotId = Ids.next();
    int created = 0;
    int reused = 0;
    var out = new ArrayList<UnitSnapshotLine>();
    // 快照的对外 id 取**第一行真实写入的行 id**。凭空造一个 id 返回等于给调用方一个
    // 指向空气的句柄：拿它回查必然查不到，重跑时还会让人以为"这张单据已冻结但读不出来"。
    UUID firstLineId = null;
    for (Object[] pair : resolved) {
      int lineNo = (Integer) pair[0];
      var result = (UnitConvertResult) pair[1];
      var existing =
          sql.fetchOne(
              "select s.id,s.line_no,s.sku_id,k.code as sku_code,s.role,s.unit_code,s.base_unit,"
                  + "s.quantity,s.quantity_per_base,s.base_quantity,s.source_conversion_id,"
                  + "s.effective_from,s.effective_to,s.occurred_at"
                  + " from sku_unit_conversion_snapshot s"
                  + " join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
                  + " where s.document_type=? and s.document_ref=? and s.line_no=?",
              documentType,
              documentRef,
              lineNo);
      if (existing != null) {
        reused++;
        UUID existingId = existing.get("id", UUID.class);
        if (firstLineId == null) firstLineId = existingId;
        out.add(toSnapshotLine(existing, false));
        continue;
      }
      UUID lineId = Ids.next();
      try {
        sql.execute(
            "insert into sku_unit_conversion_snapshot(id,tenant_id,sku_id,document_type,document_ref,"
                + "line_no,role,unit_code,base_unit,quantity,quantity_per_base,base_quantity,"
                + "source_conversion_id,effective_from,effective_to,occurred_at,actor_id)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
            lineId,
            actor.tenantId(),
            result.skuId(),
            documentType,
            documentRef,
            lineNo,
            result.role(),
            result.unitCode(),
            result.baseUnit(),
            result.quantity(),
            result.quantityPerBase(),
            result.baseQuantity(),
            result.conversionId(),
            date(
                result.effectiveFrom() == null
                    ? LocalDate.now(ZoneOffset.UTC).toString()
                    : result.effectiveFrom(),
                "生效起始日"),
            result.effectiveTo() == null ? null : date(result.effectiveTo(), "生效截止日"),
            occurredAt,
            actor.userId());
      } catch (DataAccessException e) {
        throw conflict("单据 " + documentRef + " 第 " + lineNo + " 行已被并发冻结，请刷新后重试");
      }
      created++;
      if (firstLineId == null) firstLineId = lineId;
      out.add(
          new UnitSnapshotLine(
              lineNo,
              result.skuId(),
              result.skuCode(),
              result.role(),
              result.roleLabel(),
              result.unitCode(),
              result.baseUnit(),
              result.quantity(),
              result.quantityPerBase(),
              result.baseQuantity(),
              result.conversionId(),
              result.effectiveFrom(),
              result.effectiveTo(),
              true,
              instant(occurredAt)));
    }
    audit(actor, firstLineId, "SkuUnitConversionSnapshotFrozen");
    return new UnitSnapshot(
        firstLineId,
        documentType,
        UnitRules.documentTypeLabel(documentType),
        documentRef,
        instant(occurredAt),
        instant(OffsetDateTime.now(ZoneOffset.UTC)),
        input.lines().size(),
        created,
        reused,
        List.copyOf(out));
  }

  /** 读回一张单据的冻结快照。documentType 留空则不限类型。 */
  public UnitSnapshot snapshot(String documentRef, String documentType) {
    var type =
        documentType == null || documentType.isBlank()
            ? null
            : UnitRules.normalizeDocumentType(documentType);
    var rows =
        sql
            .fetch(
                "select s.id,s.line_no,s.sku_id,k.code as sku_code,s.role,s.unit_code,s.base_unit,"
                    + "s.quantity,s.quantity_per_base,s.base_quantity,s.source_conversion_id,"
                    + "s.effective_from,s.effective_to,s.occurred_at,s.created_at,s.document_type,"
                    + "s.document_ref"
                    + " from sku_unit_conversion_snapshot s"
                    + " join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
                    + " where s.document_ref=?"
                    + (type == null ? "" : " and s.document_type=?")
                    + " order by s.document_type,s.line_no",
                type == null ? new Object[] {documentRef} : new Object[] {documentRef, type})
            .stream()
            .map(r -> toSnapshotLine(r, false))
            .toList();
    if (rows.isEmpty()) throw CatalogService.missing();
    var head =
        sql.fetchOne(
            "select id,document_type,document_ref,occurred_at,created_at"
                + " from sku_unit_conversion_snapshot where document_ref=?"
                + (type == null ? "" : " and document_type=?")
                + " order by created_at desc limit 1",
            type == null ? new Object[] {documentRef} : new Object[] {documentRef, type});
    String label = head.get("document_type", String.class);
    return new UnitSnapshot(
        head.get("id", UUID.class),
        label,
        UnitRules.documentTypeLabel(label),
        head.get("document_ref", String.class),
        instant(head.get("occurred_at", OffsetDateTime.class)),
        instant(head.get("created_at", OffsetDateTime.class)),
        rows.size(),
        0,
        rows.size(),
        rows);
  }

  // ============================================================ 工具

  /** 业务时间：前端传 ISO-8601，留空取当前；与系统写入时间分列存储。 */
  private static OffsetDateTime instant(String value) {
    if (value == null || value.isBlank()) return OffsetDateTime.now(ZoneOffset.UTC);
    try {
      return OffsetDateTime.ofInstant(Instant.parse(value.trim()), ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时间格式需为 ISO-8601，例如 " + Instant.EPOCH);
    }
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static LocalDate date(String value, String label) {
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "需为 YYYY-MM-DD");
    }
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

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
