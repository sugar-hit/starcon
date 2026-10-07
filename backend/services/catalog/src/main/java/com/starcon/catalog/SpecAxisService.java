package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.3.1 规格轴定义：租户级规格轴字典、类目允许规则、SPU 绑定与规格选项。
 *
 * <p>四条纪律贯穿本类：
 *
 * <ol>
 *   <li><b>规格选项与属性选项共享同一个值源</b>。写一个规格选项时，本类在**同一个事务**里
 *       先写 {@code attribute_option}（属性选项值源）再写 {@code spec_axis_option}；
 *       V9 的 {@code guard_spec_axis_option_source} 触发器在后者落库那一刻核对前者。
 *       同一个值被两处各存一份，就会出现"属性选项改了、规格轴还是旧值"——
 *       蓝图把它列为硬约束，本类不提供绕过它的写入口。
 *   <li><b>规格轴是租户级字典，不是每个 SPU 自带一套</b>。颜色/尺码/容量要被多个 SPU 复用，
 *       所以轴在 {@code spec_axis}（无 spu_id），SPU 只是通过 {@code spu_spec_axis} 绑定它。
 *   <li><b>绑定时携带承载属性</b>。绑定一行 = 「这个 SPU 用这条轴，由这个 variant_axis 属性定义
 *       承载它的选项」。触发器强制承载属性属于本 SPU、是 variant_axis、且 code 与轴一致——
 *       两边各叫各的会让值源永远对不齐。
 *   <li><b>删除前先看引用</b>。轴被 SPU 绑定时删不动：删掉会让那些 SPU 的规格凭空消失，
 *       而它们的 SKU 上还留着按这条轴算出的 variant_signature。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 * 规格轴落在属性域内（值源就是属性选项），所以按属性而不是按 SKU 授权；
 * 它又不是 SKU 自身的档案字段，按 SKU 授权反而会让「没有 SKU 权限就配不了颜色」��
 */
@Service
public class SpecAxisService {
  private final DSLContext sql;
  private final AccessService access;
  private final CategoryTreeService categories;

  public SpecAxisService(DSLContext sql, AccessService access, CategoryTreeService categories) {
    this.sql = sql;
    this.access = access;
    this.categories = categories;
  }

  // ============================================================ 规格轴字典

  /** 规格轴字典列表（含停用）。停用轴也在列里——它可能还绑着一堆 SPU。 */
  public List<SpecAxis> axes() {
    return sql.fetch(
            "select a.id,a.code,a.name,a.enabled,a.version,a.created_at,a.updated_at,"
                + "(select count(*) from spec_axis_option o where o.tenant_id=a.tenant_id"
                + "  and o.spec_axis_id=a.id) as option_count,"
                + "(select count(*) from spec_axis_option o where o.tenant_id=a.tenant_id"
                + "  and o.spec_axis_id=a.id and o.enabled) as enabled_option_count,"
                + "(select count(*) from spu_spec_axis b where b.tenant_id=a.tenant_id"
                + "  and b.spec_axis_id=a.id) as bound_spu_count"
                + " from spec_axis a order by a.code")
        .map(this::toAxis)
        .stream()
        .toList();
  }

  private SpecAxis toAxis(Record r) {
    return new SpecAxis(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("option_count", Long.class),
        r.get("enabled_option_count", Long.class),
        r.get("bound_spu_count", Long.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 新建或更新规格轴（id 为空即新建），走 version 乐观锁。
   *
   * <p>轴编码建后**不可改**：承载它的 variant_axis 属性定义按同 code 绑定，
   * V9 的 guard_spu_spec_axis 触发器要求两边 code 一致。改轴码会让所有已绑定的 SPU
   * 在下一次写入时突然报「规格轴编码与承载属性编码不一致」。要改就新建一条轴。
   */
  public SpecAxisSaved saveAxis(
      AccessService.Access actor, UUID id, SpecAxisInput input) {
    String problem = SpecAxisRules.codeProblem(input.code());
    CatalogService.check(problem == null, problem);
    CatalogService.check(!input.name().trim().isEmpty(), "规格轴名称不能为空");
    CatalogService.check(
        input.name().trim().length() <= 100, "规格轴名称最多 100 个字符");
    String code = SpecAxisRules.normalizeCode(input.code());

    if (id == null) {
      if (sql.fetchOne("select id from spec_axis where code=?", code) != null)
        throw conflict("规格轴编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into spec_axis(id,tenant_id,code,name,enabled) values(?,?,?,?,?)",
            newId, actor.tenantId(), code, input.name().trim(), input.enabled());
      } catch (DataAccessException e) {
        throw conflict("规格轴编码已被并发创建或占用，请刷新后重试");
      }
      audit(actor, newId, "SpecAxisSaved");
      return new SpecAxisSaved(newId, 0);
    }

    var row = sql.fetchOne("select code,version from spec_axis where id=?", id);
    if (row == null) throw CatalogService.missing();
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "规格轴编码建后不可修改：已绑定的 SPU 按同 code 的 variant_axis 属性承载它的选项，"
            + "改码会让全部绑定在写入时对不齐，请新建一条规格轴");
    int updated;
    try {
      updated =
          sql.execute(
              "update spec_axis set name=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              input.name().trim(), input.enabled(), id, input.version());
    } catch (DataAccessException e) {
      throw conflict("规格轴编码已存在或已被占用，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "规格轴已被他人修改，请刷新后重试");
    long version =
        sql.fetchOne("select version from spec_axis where id=?", id).get("version", Long.class);
    audit(actor, id, "SpecAxisSaved");
    return new SpecAxisSaved(id, version);
  }

  /**
   * 删除规格轴。
   *
   * <p>被 SPU 绑定或挂着选项时一律拒绝。正确做法是先停用（enabled=false）——
   * 停用挡住的是"不许再新建绑定"，已绑定的 SPU 与既有选项照常可读。
   */
  public void deleteAxis(AccessService.Access actor, UUID id, long version) {
    var row = sql.fetchOne("select code,version from spec_axis where id=?", id);
    if (row == null) throw CatalogService.missing();
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "规格轴已被他人修改，请刷新后重试");
    String code = row.get("code", String.class);
    long bound =
        sql.fetchOne("select count(*) from spu_spec_axis where spec_axis_id=?", id).get(0, Long.class);
    CatalogService.check(
        bound == 0,
        "规格轴「" + code + "」已被 " + bound + " 个 SPU 绑定，不能删除；"
            + "请改为停用，停用只挡住新建绑定，已绑定的 SPU 与既有选项仍可读");
    long options =
        sql.fetchOne("select count(*) from spec_axis_option where spec_axis_id=?", id)
            .get(0, Long.class);
    CatalogService.check(
        options == 0,
        "规格轴「" + code + "」还有 " + options + " 个规格选项，不能删除；"
            + "这些选项同时是属性选项值源里的值，请先逐个停用或删除");
    sql.execute("delete from spec_axis where id=?", id);
    audit(actor, id, "SpecAxisDeleted");
  }

  // ============================================================ 概览

  /**
   * 概览：轴字典规模、选项数、绑定数、类目规则数，以及**未挂到轴字典的 variant_axis 属性数**。
   *
   * <p>{@code unboundVariantAxisAttributes} 是治理缺口指标：V1 允许 variant_axis 属性独立存在
   * （3.1.1/3.1.2 的既有测试就这么写），本模块不加"必须挂轴"的触发器去打断它们，
   * 改为把缺口显式暴露出来——0 与"没查过"含义不同。
   */
  public SpecAxisOverview overview() {
    var axes =
        sql.fetchOne(
            "select count(*) as all_axes,count(*) filter (where enabled) as enabled_axes"
                + " from spec_axis");
    long options = sql.fetchOne("select count(*) from spec_axis_option").get(0, Long.class);
    long bound =
        sql.fetchOne("select count(distinct spu_id) from spu_spec_axis").get(0, Long.class);
    long rules = sql.fetchOne("select count(*) from category_spec_axis").get(0, Long.class);
    long unbound =
        sql.fetchOne(
                "select count(*) from attribute_definition d where d.variant_axis and not exists("
                    + " select 1 from spu_spec_axis b where b.tenant_id=d.tenant_id"
                    + " and b.attribute_definition_id=d.id)")
            .get(0, Long.class);
    var top =
        sql.fetch(
                "select a.id,a.code,a.name,"
                    + "(select count(*) from spu_spec_axis b where b.tenant_id=a.tenant_id"
                    + "  and b.spec_axis_id=a.id) as bound_spu_count,"
                    + "(select count(*) from spec_axis_option o where o.tenant_id=a.tenant_id"
                    + "  and o.spec_axis_id=a.id) as option_count"
                    + " from spec_axis a order by bound_spu_count desc,a.code limit 8")
            .map(
                r ->
                    new SpecAxisUsageCount(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("bound_spu_count", Long.class),
                        r.get("option_count", Long.class)))
            .stream()
            .toList();
    return new SpecAxisOverview(
        axes.get("all_axes", Long.class),
        axes.get("enabled_axes", Long.class),
        options,
        bound,
        rules,
        unbound,
        top,
        unbound == 0
            ? "全部规格轴属性都已挂到轴字典上，值源完全由规格轴字典管控。"
            : unbound
                + " 个 variant_axis 属性定义还没有挂到任何规格轴字典上：它们的选项目前只存在于属性侧，"
                    + "不参与规格轴矩阵。3.1.1/3.1.2 的历史数据允许这种状态，本模块只做提示不强制打断。");
  }

  // ============================================================ 单 SPU 面板

  /**
   * 单个 SPU 的规格轴面板：已绑定的轴、每条轴的选项、矩阵行数与类目必绑缺口。
   *
   * <p>矩阵行数 = 各轴**可用**选项数的笛卡尔积，即"按当前配置展开能生成多少个 SKU"。
   * 没有任何可用选项的轴会让结果为 0：那条轴还没配选项，矩阵就是空的。
   */
  public SpecAxisPanel panel(UUID spuId) {
    var row =
        sql.fetchOne("select id,code,name,category from spu where id=?", spuId);
    if (row == null) throw CatalogService.missing();
    String category = row.get("category", String.class);
    List<SpecAxisBinding> bindings = bindings(spuId);

    var optionSets = new ArrayList<java.util.Set<String>>();
    for (SpecAxisBinding binding : bindings) {
      var enabledValues = new java.util.LinkedHashSet<String>();
      for (SpecAxisOption option : binding.options())
        if (option.enabled()) enabledValues.add(option.value());
      optionSets.add(enabledValues);
    }
    long matrixRows = SpecAxisRules.matrixRowCount(optionSets);

    // 类目必绑规则：类目配了 required 轴但 SPU 没绑，界面必须显式说出来，
    // 否则运营会以为"矩阵算出来了就等于规格齐了"。
    List<String> requiredNotes = new ArrayList<>();
    long missing = 0;
    if (category != null && !category.isBlank()) {
      var boundCodes =
          bindings.stream()
              .map(SpecAxisBinding::specAxisCode)
              .collect(java.util.stream.Collectors.toSet());
      var requiredAxes =
          sql.fetch(
                  "select a.code,a.name from category_spec_axis c"
                      + " join spec_axis a on a.tenant_id=c.tenant_id and a.id=c.spec_axis_id"
                      + " where c.tenant_id=? and c.category=? and c.required order by c.position,a.code",
                  actorTenantOf(spuId),
                  category)
              .stream()
              .toList();
      for (Record r : requiredAxes) {
        String code = r.get("code", String.class);
        if (boundCodes.contains(code))
          requiredNotes.add(
              "类目「" + category + "」要求必绑规格轴「" + code + "」（"
                  + r.get("name", String.class) + "），已绑定");
        else {
          missing++;
          requiredNotes.add(
              "类目「" + category + "」要求必绑规格轴「" + code + "」（"
                  + r.get("name", String.class) + "），本 SPU 尚未绑定");
        }
      }
    }

    return new SpecAxisPanel(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        category,
        bindings,
        matrixRows,
        bindings.size(),
        missing,
        requiredNotes);
  }

  /** SPU 所在租户。RLS 已保证调用方只能看到本租户的行，这里取回 tenant_id 供子查询显式限定。 */
  private UUID actorTenantOf(UUID spuId) {
    return sql.fetchOne("select tenant_id from spu where id=?", spuId).get(0, UUID.class);
  }

  private List<SpecAxisBinding> bindings(UUID spuId) {
    var rows =
        sql.fetch(
            "select b.spec_axis_id,b.position,a.code,a.name,d.id as attribute_id,"
                + "d.code as attribute_code,d.name as attribute_name,"
                + "(select count(*) from sku k where k.tenant_id=b.tenant_id and k.spu_id=b.spu_id) as sku_count"
                + " from spu_spec_axis b"
                + " join spec_axis a on a.tenant_id=b.tenant_id and a.id=b.spec_axis_id"
                + " join attribute_definition d on d.tenant_id=b.tenant_id and d.id=b.attribute_definition_id"
                + " where b.spu_id=? order by b.position,a.code",
            spuId);
    List<SpecAxisBinding> result = new ArrayList<>();
    for (Record r : rows) {
      UUID axisId = r.get("spec_axis_id", UUID.class);
      List<SpecAxisOption> options = options(axisId);
      long enabled =
          options.stream().filter(SpecAxisOption::enabled).count();
      result.add(
          new SpecAxisBinding(
              axisId,
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("attribute_id", UUID.class),
              r.get("attribute_code", String.class),
              r.get("attribute_name", String.class),
              r.get("position", Integer.class),
              r.get("sku_count", Long.class),
              enabled,
              options.size(),
              options));
    }
    return result;
  }

  /**
   * 某条轴的选项列表。
   *
   * <p>每个选项都带上它的**承载属性**：规格选项的值同时存在于 {@code attribute_option}，
   * 这里显式把那一侧读出来，界面上才能讲清"它同时是一个属性选项"而不是凭空出现。
   */
  public List<SpecAxisOption> options(UUID axisId) {
    if (sql.fetchOne("select id from spec_axis where id=?", axisId) == null)
      throw CatalogService.missing();
    return sql.fetch(
            "select o.value,o.position,o.enabled,o.created_at,"
                + "sa.attribute_definition_id,d.code as attribute_code,d.name as attribute_name"
                + " from spec_axis_option o"
                + " left join lateral (select b.attribute_definition_id from spu_spec_axis b"
                + "   where b.tenant_id=o.tenant_id and b.spec_axis_id=o.spec_axis_id"
                + "   order by b.position,b.spec_axis_id limit 1) sa on true"
                + " left join attribute_definition d on d.tenant_id=o.tenant_id and d.id=sa.attribute_definition_id"
                + " where o.spec_axis_id=? order by o.position,o.value",
            axisId)
        .map(
            r ->
                new SpecAxisOption(
                    r.get("value", String.class),
                    r.get("position", Integer.class),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                    r.get("attribute_definition_id", UUID.class),
                    r.get("attribute_code", String.class),
                    r.get("attribute_name", String.class),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  // ============================================================ 绑定

  /**
   * 把一条规格轴绑到一个 SPU 上。
   *
   * <p>{@code createAttribute=true} 时，若该 SPU 上还没有同 code 的 variant_axis 属性定义，
   * 就先建一条承载属性再绑定——3.2.1.1 属性定义列表未开发，规格轴要能在自己的端点里自洽地维护，
   * 不能因为"属性管理页面还不存在"就让整条链路走不通。
   *
   * <p>{@code createAttribute=false} 时要求属性已存在，否则 400 并说清缺什么。
   */
  public SpecAxisBindingSaved bindAxis(
      AccessService.Access actor, UUID spuId, SpecAxisBindingInput input) {
    var axis = sql.fetchOne("select id,code,name,enabled from spec_axis where id=?", input.specAxisId());
    if (axis == null) throw CatalogService.missing();
    String axisCode = axis.get("code", String.class);
    CatalogService.check(
        !axisCode.isBlank(), "规格轴编码为空，无法绑定");
    var spu = sql.fetchOne("select id,category,version from spu where id=?", spuId);
    if (spu == null) throw CatalogService.missing();

    var existing =
        sql.fetchOne(
            "select id from spu_spec_axis where spu_id=? and spec_axis_id=?", spuId, input.specAxisId());
    if (existing != null)
      throw conflict("该 SPU 已经绑定过这条规格轴，请先解除绑定再重新绑定");

    UUID attributeId =
        ensureVariantAttribute(actor, spuId, axisCode, input.createAttribute(), input.attributeName());

    int position = input.position() >= 0 ? input.position() : nextPosition(spuId);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into spu_spec_axis(id,tenant_id,spu_id,spec_axis_id,attribute_definition_id,position)"
              + " values(?,?,?,?,?,?)",
          id, actor.tenantId(), spuId, input.specAxisId(), attributeId, position);
    } catch (DataAccessException e) {
      throw conflict(
          bindFailure(e, axisCode)
              + "（类目「"
              + spu.get("category", String.class)
              + "」的允许规则可能不允许这条轴）");
    }
    audit(actor, id, "SpuSpecAxisBound");
    return new SpecAxisBindingSaved(id, spuId, input.specAxisId(), attributeId, position);
  }

  /**
   * 找到（或按需新建）承载规格轴的 variant_axis 属性定义。
   *
   * <p>新建的属性刻意用与轴**相同的 code** 与 V1 的 variant_axis 约束组合
   * （data_type='OPTION'、value_mode='SKU_ONLY'、multiple=false、required=true）：
   * 这不是凑合，是 V1 的 CHECK 唯一允许的规格轴形状，V9 的 guard_spu_spec_axis 也会复核。
   */
  private UUID ensureVariantAttribute(
      AccessService.Access actor, UUID spuId, String axisCode, boolean create, String name) {
    var found =
        sql.fetchOne(
            "select id from attribute_definition where tenant_id=? and spu_id=? and code=?",
            actor.tenantId(), spuId, axisCode);
    if (found != null) {
      UUID id = found.get("id", UUID.class);
      Boolean isVariant =
          sql.fetchOne("select variant_axis from attribute_definition where id=?", id)
              .get("variant_axis", Boolean.class);
      CatalogService.check(
          Boolean.TRUE.equals(isVariant),
          "SPU 上已存在编码为「" + axisCode + "」的属性，但它不是规格轴属性（variant_axis=false）。"
              + "规格轴必须由 variant_axis 属性承载，请先在属性侧调整，或换一个规格轴编码");
      return id;
    }
    CatalogService.check(
        create,
        "该 SPU 上还没有编码为「" + axisCode + "」的规格轴属性。"
            + "规格选项必须由属性选项承载（共享同一个值源），请勾选「同时创建承载属性」，"
            + "或先在属性侧建立这条 variant_axis 属性");
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode,"
              + "multiple,required,variant_axis,group_name,position)"
              + " values(?,?,?,?,?,'OPTION','SKU_ONLY',false,true,true,'规格',?)",
          id,
          actor.tenantId(),
          spuId,
          axisCode,
          name == null || name.isBlank() ? axisCode : name.trim(),
          0);
    } catch (DataAccessException e) {
      throw conflict(
          "创建规格轴承载属性失败：该 SPU 上可能已存在同编码的属性或唯一约束冲突，请刷新后重试");
    }
    return id;
  }

  private int nextPosition(UUID spuId) {
    Integer max =
        sql.fetchOne("select coalesce(max(position),-1)+1 as p from spu_spec_axis where spu_id=?", spuId)
            .get("p", Integer.class);
    return max;
  }

  private String bindFailure(DataAccessException e, String axisCode) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("承载属性属于另一个 SPU")) return "承载属性不属于该 SPU";
    if (message.contains("不是规格轴属性")) return "承载属性不是 variant_axis 属性";
    if (message.contains("编码") && message.contains("不一致"))
      return "规格轴编码「" + axisCode + "」与承载属性编码不一致";
    if (message.contains("类目不允许")) return "该 SPU 所属类目不允许这条规格轴";
    return "规格轴绑定失败，请刷新后重试";
  }

  /**
   * 解除 SPU 与规格轴的绑定。
   *
   * <p>承载属性上的 SKU 规格值仍引用它，因此**连带删除属性定义时会被 attribute_value 的
   * 外键挡住**：这正是期望行为——要真删干净得先把 SKU 的规格值改掉，
   * 不能悄悄把已存在的 SKU 规格抹成空。catch 里把它翻译成人话。
   */
  public void unbindAxis(AccessService.Access actor, UUID spuId, UUID axisId) {
    var row =
        sql.fetchOne(
            "select b.attribute_definition_id,a.code from spu_spec_axis b"
                + " join spec_axis a on a.tenant_id=b.tenant_id and a.id=b.spec_axis_id"
                + " where b.spu_id=? and b.spec_axis_id=?",
            spuId, axisId);
    if (row == null) throw CatalogService.missing();
    try {
      sql.execute("delete from spu_spec_axis where spu_id=? and spec_axis_id=?", spuId, axisId);
    } catch (DataAccessException e) {
      throw conflict(
          "解除绑定失败：规格轴「" + row.get("code", String.class) + "」的承载属性上仍有 SKU 规格值。"
              + "这些 SKU 是按这条轴生成的，请先改掉它们的规格值再解除绑定");
    }
    audit(actor, axisId, "SpuSpecAxisUnbound");
  }

  // ============================================================ 规格选项

  /**
   * 登记一个规格选项。
   *
   * <p>**写两处，同一事务，顺序固定**：
   *
   * <ol>
   *   <li>先写 {@code attribute_option}（属性选项值源）——落在该轴所有已绑定 SPU 的承载属性上；
   *   <li>再写 {@code spec_axis_option}。
   * </ol>
   *
   * <p>顺序不能反：V9 的 guard_spec_axis_option_source 在 spec_axis_option 落库那一刻就去值源核对，
   * 值源行还没写就会判成孤儿。写入用 ON CONFLICT DO NOTHING：同一个值在多个 SPU 上都已存在时
   * 不该报错——值源是共享的，重复写才是正常的。
   */
  public SpecAxisOptionSaved addOption(
      AccessService.Access actor, UUID axisId, SpecAxisOptionInput input) {
    String problem = SpecAxisRules.valueProblem(input.value());
    CatalogService.check(problem == null, problem);
    String value = SpecAxisRules.normalizeValue(input.value());

    var axis = sql.fetchOne("select id,code,name from spec_axis where id=?", axisId);
    if (axis == null) throw CatalogService.missing();

    var bindings =
        sql.fetch(
            "select attribute_definition_id from spu_spec_axis where tenant_id=? and spec_axis_id=? order by position",
            actor.tenantId(), axisId);
    CatalogService.check(
        !bindings.isEmpty(),
        "规格轴「" + axis.get("code", String.class) + "」还没有绑定任何 SPU，无法登记选项："
            + "规格选项必须挂在某个 SPU 的 variant_axis 属性的选项集合上（共享同一个值源）。"
            + "请先绑定 SPU，再登记选项");

    for (Record b : bindings) {
      UUID attributeId = b.get("attribute_definition_id", UUID.class);
      try {
        sql.execute(
            "insert into attribute_option(tenant_id,attribute_id,value) values(?,?,?)"
                + " on conflict (tenant_id,attribute_id,value) do nothing",
            actor.tenantId(), attributeId, value);
      } catch (DataAccessException e) {
        throw conflict("写入属性选项值源失败：属性「" + attributeId + "」已不接受该值，请刷新后重试");
      }
    }

    try {
      sql.execute(
          "insert into spec_axis_option(tenant_id,spec_axis_id,value,position,enabled)"
              + " values(?,?,?,?,?) on conflict (tenant_id,spec_axis_id,value)"
              + " do update set position=excluded.position,enabled=excluded.enabled",
          actor.tenantId(), axisId, value, input.position(), input.enabled());
    } catch (DataAccessException e) {
      throw conflict(
          "规格选项写入失败：「" + value + "」未能在属性选项值源中找到对应值（蓝图要求两者共享同一个值源）");
    }
    audit(actor, axisId, "SpecAxisOptionSaved");
    return new SpecAxisOptionSaved(axisId, value, input.position());
  }

  /**
   * 删除一个规格选项。
   *
   * <p>两处一起删：spec_axis_option 与它在**每个已绑定 SPU 承载属性上的 attribute_option 行**。
   * 只删前者会留下一个再也无人引用的值源行；只删后者会被 V9 的 guard_attribute_option_in_use 挡住。
   *
   * <p>承载属性上已有 SKU 取过该值时删除会失败（attribute_value_item 的复合外键），
   * 这种情况应该**停用**而不是删除：历史 SKU 的规格还要能解析出来。
   */
  public void deleteOption(AccessService.Access actor, UUID axisId, String rawValue) {
    String value = SpecAxisRules.normalizeValue(rawValue);
    var row =
        sql.fetchOne(
            "select value from spec_axis_option where tenant_id=? and spec_axis_id=? and value=?",
            actor.tenantId(), axisId, value);
    if (row == null) throw CatalogService.missing();
    var bindings =
        sql.fetch(
            "select attribute_definition_id from spu_spec_axis where tenant_id=? and spec_axis_id=?",
            actor.tenantId(), axisId);
    sql.execute(
        "delete from spec_axis_option where tenant_id=? and spec_axis_id=? and value=?",
        actor.tenantId(), axisId, value);
    for (Record b : bindings) {
      try {
        sql.execute(
            "delete from attribute_option where tenant_id=? and attribute_id=? and value=?",
            actor.tenantId(), b.get("attribute_definition_id", UUID.class), value);
      } catch (DataAccessException e) {
        throw conflict(
            "无法删除规格选项「" + value + "」：已有 SKU 取用了该属性选项值。"
                + "历史 SKU 的规格需要它来解析，请改为停用该选项");
      }
    }
    audit(actor, axisId, "SpecAxisOptionDeleted");
  }

  // ============================================================ 类目规则

  public List<CategorySpecAxisRule> categoryRules(String category) {
    String where = category == null || category.isBlank() ? "" : " where r.category = ? ";
    Object[] args =
        category == null || category.isBlank() ? new Object[0] : new Object[] {category.trim()};
    return sql.fetch(
            "select r.category,r.spec_axis_id,r.required,r.position,r.created_at,"
                + "a.code,a.name,"
                + "(select count(*) from spu p where p.tenant_id=r.tenant_id and p.category=r.category)"
                + " as bound_spu_count"
                + " from category_spec_axis r"
                + " join spec_axis a on a.tenant_id=r.tenant_id and a.id=r.spec_axis_id"
                + where
                + " order by r.category,r.position,a.code",
            args)
        .map(
            r ->
                new CategorySpecAxisRule(
                    r.get("category", String.class),
                    r.get("spec_axis_id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    Boolean.TRUE.equals(r.get("required", Boolean.class)),
                    r.get("position", Integer.class),
                    r.get("bound_spu_count", Long.class),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  /** 新增或更新类目允许的规格轴规则。 */
  public CategorySpecAxisSaved saveCategoryRule(
      AccessService.Access actor, CategorySpecAxisInput input, UUID axisId) {
    var axis = sql.fetchOne("select id,code from spec_axis where id=?", axisId);
    if (axis == null) throw CatalogService.missing();
    String category = input.category().trim();
    CatalogService.check(!category.isBlank(), "类目不能为空");
    CatalogService.check(
        category.length() <= 100, "类目名称最多 100 个字符");
    try {
      // V14 兑现点：category_id 是 NOT NULL 外键，入参仍是类目文本（已交付 API 语义不变），
      // 解析与按需建节点统一走 CategoryTreeService，本模块是唯一写入口。
      UUID categoryId = categories.resolveOrCreateCategoryId(actor, category);
      sql.execute(
          "insert into category_spec_axis(tenant_id,category,category_id,spec_axis_id,required,position)"
              + " values(?,?,?,?,?,?) on conflict (tenant_id,category,spec_axis_id)"
              + " do update set required=excluded.required,position=excluded.position",
          actor.tenantId(), category, categoryId, axisId, input.required(), input.position());
    } catch (DataAccessException e) {
      throw conflict("类目规则写入失败，请刷新后重试");
    }
    audit(actor, axisId, "CategorySpecAxisSaved");
    return new CategorySpecAxisSaved(category, axisId);
  }

  /**
   * 删除类目规则。
   *
   * <p>删规则**不**解绑任何 SPU——规则是"新建绑定时的准入条件"，
   * 已经存在的绑定本来就合法，被一条新规则连坐删掉会凭空改掉既有 SPU 的规格结构。
   */
  public void deleteCategoryRule(AccessService.Access actor, String category, UUID axisId) {
    int deleted =
        sql.execute(
            "delete from category_spec_axis where tenant_id=? and category=? and spec_axis_id=?",
            actor.tenantId(), category == null ? "" : category.trim(), axisId);
    if (deleted == 0) throw CatalogService.missing();
    audit(actor, axisId, "CategorySpecAxisDeleted");
  }

  // ============================================================ 工具

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}