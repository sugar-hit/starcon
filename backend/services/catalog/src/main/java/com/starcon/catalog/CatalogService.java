package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * All public calls run inside TenantTransactions. SQL identifiers below are constants, values are
 * bound.
 */
@Service
public class CatalogService {
  private final DSLContext sql;

  public CatalogService(DSLContext sql) {
    this.sql = sql;
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或没有访问权限");
  }

  static String code(String value) {
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    check(normalized.matches("[A-Z0-9][A-Z0-9_-]{0,63}"), "编码请使用英文字母、数字、下划线或短横线");
    return normalized;
  }

  /** SKU 仍是四值状态域；SPU 已迁移到 3.1.1.3 的五态生命周期，见 {@link SpuLifecycle}。 */
  private static void skuStatus(String value) {
    check(Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").contains(value), "无效状态");
  }

  /** 商品当前行；RLS 与数据范围策略在此生效，取不到即"不存在或无权限"。 */
  Record productRow(UUID id) {
    var row = sql.fetchOne("select * from spu where id=?", id);
    if (row == null) throw missing();
    return row;
  }

  private boolean permitted(org.jooq.Record p, String permission) {
    return Boolean.TRUE.equals(
        sql.fetchOne(
                "select catalog_scope(?,?,?) as allowed",
                permission,
                p.get("owner_id"),
                p.get("department_id"))
            .get("allowed", Boolean.class));
  }

  public Detail readDetail(UUID id) {
    var row = productRow(id);
    boolean attributesAllowed = permitted(row, "catalog.attribute.read");
    boolean skusAllowed = permitted(row, "catalog.sku.read");
    var detail = detail(id);
    var visibleSkus =
        skusAllowed
            ? detail.skus().stream()
                .map(
                    s ->
                        new Sku(
                            s.id(),
                            s.code(),
                            s.name(),
                            s.barcode(),
                            s.baseUnit(),
                            s.status(),
                            s.version(),
                            attributesAllowed ? s.values() : List.of()))
                .toList()
            : List.<Sku>of();
    return new Detail(
        detail.product(), attributesAllowed ? detail.attributes() : List.of(), visibleSkus);
  }

  private Product product(Record r) {
    return new Product(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("brand", String.class),
        r.get("category", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        sql.fetchCount(
            sql.selectFrom(org.jooq.impl.DSL.table("sku"))
                .where(org.jooq.impl.DSL.field("spu_id").eq(r.get("id")))),
        r.get("owner_id", UUID.class),
        r.get("department_id", UUID.class));
  }

  public List<Product> list(String query) {
    String escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return sql.fetch(
            "select * from spu where name ilike ? escape '!' or code ilike ? escape '!' order by updated_at desc limit 200",
            "%" + escaped + "%",
            "%" + escaped + "%")
        .map(this::product);
  }

  public Detail detail(UUID id) {
    var p = product(productRow(id));
    var attributes = attributes(id);
    var skus =
        sql.fetch("select * from sku where spu_id=? order by created_at", id)
            .map(
                r -> {
                  UUID sid = r.get("id", UUID.class);
                  var values =
                      attributes.stream()
                          .map(
                              a -> {
                                var common =
                                    sql.fetchOne(
                                        "select id,state from attribute_value where attribute_id=? and sku_id is null",
                                        a.id());
                                var sku =
                                    sql.fetchOne(
                                        "select id,state from attribute_value where attribute_id=? and sku_id=?",
                                        a.id(),
                                        sid);
                                // 有效值解析走 EffectiveValues 这一个实现，3.1.2.2 的属性区
                                // 与商品档案页共用，避免两处对「公共值 + SKU 覆写」的理解漂移。
                                var resolved =
                                    EffectiveValues.resolve(
                                        a.valueMode(),
                                        side(common),
                                        side(sku),
                                        a.required());
                                return new EffectiveValue(
                                    a.id(),
                                    resolved.overrideState(),
                                    resolved.rawValues(),
                                    resolved.effectiveValues(),
                                    resolved.source().code());
                              })
                          .toList();
                  return new Sku(
                      sid,
                      r.get("code", String.class),
                      r.get("name", String.class),
                      r.get("barcode", String.class),
                      r.get("base_unit", String.class),
                      r.get("status", String.class),
                      r.get("version", Long.class),
                      values);
                });
    return new Detail(p, attributes, skus);
  }

  /** 把一行 attribute_value 转成解析器的输入；没有这一行就是「没有赋值」。 */
  private EffectiveValues.Raw side(Record row) {
    if (row == null) return new EffectiveValues.Raw(null, List.of());
    var values =
        items(row.get("id", UUID.class));
    return new EffectiveValues.Raw(row.get("state", String.class), values);
  }

  /**
   * SPU 资料保存。状态不是自由文本：新建固定为 DRAFT，更新时若携带不同状态一律拒绝，
   * 状态只能经 {@code POST /products/{id}/lifecycle} 按合法矩阵流转。
   */
  public UUID saveSpu(AccessService.Access access, UUID id, SpuInput input) {
    if (id == null) {
      check(input.status().equals("DRAFT"), "新商品先保存为草稿，补齐 SKU 后再提交待上架");
      id = Ids.next();
      sql.execute(
          "insert into spu(id,tenant_id,code,name,description,brand,category) values(?,?,?,?,?,?,?)",
          id,
          access.tenantId(),
          code(input.code()),
          input.name().trim(),
          input.description(),
          input.brand(),
          input.category());
    } else {
      lock(id, input.version());
      check(
          productRow(id).get("status", String.class).equals(input.status()),
          "商品状态只能在生命周期中按合法转换流转，并填写变更原因");
      sql.execute(
          "update spu set code=?,name=?,description=?,brand=?,category=? where id=?",
          code(input.code()),
          input.name().trim(),
          input.description(),
          input.brand(),
          input.category(),
          id);
    }
    event(access, id, "SpuSaved");
    return id;
  }

  private void lock(UUID id, long version) {
    var r = sql.fetchOne("select * from spu where id=? for update", id);
    if (r == null) throw missing();
    if (r.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已被修改，请刷新后重试");
    sql.execute("update spu set version=version+1,updated_at=now() where id=?", id);
  }

  public void deleteSpu(AccessService.Access access, UUID id, long version) {
    lock(id, version);
    check(productRow(id).get("status", String.class).equals("DRAFT"), "只能删除草稿，已提交或已上架商品请先停用或停产");
    check(
        sql.fetchCount(
                sql.selectFrom(org.jooq.impl.DSL.table("sku"))
                    .where(org.jooq.impl.DSL.field("spu_id").eq(id)))
            == 0,
        "请先处理该商品下的 SKU");
    event(access, id, "SpuDeleted");
    sql.execute("delete from spu where id=?", id);
  }

  public List<Attribute> attributes(UUID spu) {
    return sql.fetch("select * from attribute_definition where spu_id=? order by position,id", spu)
        .map(
            r -> {
              UUID aid = r.get("id", UUID.class);
              var defaults =
                  sql.fetchOne(
                      "select id from attribute_value where attribute_id=? and sku_id is null",
                      aid);
              return new Attribute(
                  aid,
                  r.get("code", String.class),
                  r.get("name", String.class),
                  r.get("data_type", String.class),
                  r.get("value_mode", String.class),
                  r.get("multiple", Boolean.class),
                  r.get("required", Boolean.class),
                  r.get("variant_axis", Boolean.class),
                  r.get("unit", String.class),
                  r.get("group_name", String.class),
                  sql.fetch(
                          "select value from attribute_option where attribute_id=? order by value",
                          aid)
                      .getValues("value", String.class),
                  defaults == null ? List.of() : items(defaults.get("id", UUID.class)));
            });
  }

  private List<String> items(UUID id) {
    return sql.fetch(
            "select coalesce(text_value,decimal_value::text,boolean_value::text,date_value::text,option_value) as value from attribute_value_item where value_id=? order by ordinal",
            id)
        .getValues("value", String.class);
  }

  public void saveAttribute(AccessService.Access access, UUID spu, UUID id, AttributeInput input) {
    lock(spu, input.version());
    check(
        Set.of("TEXT", "DECIMAL", "BOOLEAN", "DATE", "OPTION").contains(input.dataType()),
        "不支持的属性类型");
    check(
        Set.of("COMMON_LOCKED", "COMMON_DEFAULT", "SKU_ONLY").contains(input.valueMode()),
        "不支持的取值模式");
    check(
        !input.variantAxis()
            || (input.dataType().equals("OPTION")
                && input.valueMode().equals("SKU_ONLY")
                && !input.multiple()
                && input.required()),
        "规格轴必须是按 SKU 填写的必填单选属性");
    check(
        !input.valueMode().equals("SKU_ONLY") || input.defaultValues().isEmpty(),
        "SKU 独立属性不能设置公共值");
    check(input.dataType().equals("OPTION") || input.options().isEmpty(), "只有选项属性可以配置可选值");
    check(!input.dataType().equals("OPTION") || !input.options().isEmpty(), "请提供至少一个选项");
    check(new HashSet<>(input.options()).size() == input.options().size(), "选项不可重复");
    var current = attributes(spu);
    if (id == null) {
      check(current.size() < 200, "单个商品最多配置 200 个属性");
      check(!input.variantAxis() || detail(spu).skus().isEmpty(), "已有 SKU 时不能新增规格轴，请先完成新商品规格规划");
      id = Ids.next();
      sql.execute(
          "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode,multiple,required,variant_axis,unit,group_name,position) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          access.tenantId(),
          spu,
          code(input.code()),
          input.name().trim(),
          input.dataType(),
          input.valueMode(),
          input.multiple(),
          input.required(),
          input.variantAxis(),
          input.unit(),
          input.groupName(),
          current.size());
      for (String option : input.options())
        // 列名必须显式列出：V11 给 attribute_option 加了 position/enabled/created_at，
        // 位置化插入会随列数变化而错位或直接报错。
        sql.execute(
            "insert into attribute_option(tenant_id,attribute_id,value) values(?,?,?)",
            access.tenantId(),
            id,
            option);
    } else {
      UUID aid = id;
      var old =
          current.stream()
              .filter(a -> a.id().equals(aid))
              .findFirst()
              .orElseThrow(CatalogService::missing);
      check(
          old.code().equals(code(input.code()))
              && old.dataType().equals(input.dataType())
              && old.valueMode().equals(input.valueMode())
              && old.multiple() == input.multiple()
              && old.variantAxis() == input.variantAxis()
              && old.unit().equals(input.unit())
              && new HashSet<>(old.options()).equals(new HashSet<>(input.options())),
          "已建属性的编码、类型、模式、选项与单位不能直接变更，请新建属性");
      sql.execute(
          "update attribute_definition set name=?,required=?,group_name=? where id=?",
          input.name(),
          input.required(),
          input.groupName(),
          id);
    }
    var a =
        new Attribute(
            id,
            code(input.code()),
            input.name(),
            input.dataType(),
            input.valueMode(),
            input.multiple(),
            input.required(),
            input.variantAxis(),
            input.unit(),
            input.groupName(),
            input.options(),
            input.defaultValues());
    validateValues(a, input.defaultValues(), false);
    writeValue(
        access.tenantId(),
        spu,
        id,
        null,
        input.defaultValues().isEmpty() ? "INHERIT" : "SET",
        input.defaultValues(),
        a.dataType());
    var d = detail(spu);
    for (var s : d.skus()) if (s.status().equals("ACTIVE")) validateEffective(d.attributes(), s);
    event(access, spu, "AttributeSaved");
  }

  public void deleteAttribute(AccessService.Access access, UUID spu, UUID id, long version) {
    lock(spu, version);
    var a =
        attributes(spu).stream()
            .filter(x -> x.id().equals(id))
            .findFirst()
            .orElseThrow(CatalogService::missing);
    check(
        detail(spu).skus().isEmpty() && productRow(spu).get("status", String.class).equals("DRAFT"),
        "已有 SKU 或非草稿商品不能删除属性");
    sql.execute("delete from attribute_definition where id=?", a.id());
    event(access, spu, "AttributeDeleted");
  }

  /**
   * 属性取值校验：数量、必填、类型与选项归属，四类数据类型的规则都在这里。
   *
   * <p>包可见且无实例状态，是为了让 3.2.2.2 的 SKU 覆写**走同一份规则**而不是另写一套——
   * SPU 侧与 SKU 侧对「什么是合法的数值属性值」必须一致，两处各判一次迟早会漂移。
   */
  static void validateValues(Attribute a, List<String> values, boolean activation) {
    check(a.multiple() || values.size() <= 1, "属性「" + a.name() + "」只允许一个值");
    check(!activation || !a.required() || !values.isEmpty(), "请填写必填属性「" + a.name() + "」");
    check(values.size() <= 50, "多值属性最多 50 项");
    check(new HashSet<>(values).size() == values.size(), "属性值不能重复");
    for (String value : values) {
      check(value != null && !value.isBlank() && value.length() <= 2000, "属性值不能为空或超长");
      switch (a.dataType()) {
        case "DECIMAL" -> {
          var n = new BigDecimal(value);
          check(n.scale() <= 10 && n.precision() - n.scale() <= 20, "数值最多 20 位整数和 10 位小数");
        }
        case "BOOLEAN" -> check(Set.of("true", "false").contains(value), "布尔值必须是 true 或 false");
        case "DATE" -> LocalDate.parse(value);
        case "OPTION" -> check(a.options().contains(value), "属性选项不属于此商品");
        default -> {}
      }
    }
  }

  private void validateEffective(List<Attribute> attributes, Sku sku) {
    for (var a : attributes)
      validateValues(
          a,
          sku.values().stream()
              .filter(v -> v.attributeId().equals(a.id()))
              .findFirst()
              .orElseThrow()
              .effectiveValues(),
          true);
  }

  public UUID saveSku(AccessService.Access access, UUID spu, UUID id, SkuInput input) {
    lock(spu, input.version());
    if (!permitted(productRow(spu), "catalog.attribute.read"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "编辑 SKU 需要此商品的属性查看权限");
    skuStatus(input.status());
    var attributes = attributes(spu);
    var supplied = new HashMap<UUID, ValueInput>();
    for (var v : input.values()) {
      check(supplied.put(v.attributeId(), v) == null, "属性不能重复提交");
      check(attributes.stream().anyMatch(a -> a.id().equals(v.attributeId())), "属性不属于此 SPU");
    }
    var signature = new ArrayList<String>();
    for (var a : attributes) {
      var v = supplied.getOrDefault(a.id(), new ValueInput(a.id(), "INHERIT", List.of()));
      check(Set.of("SET", "CLEAR", "INHERIT").contains(v.state()), "无效覆写状态");
      check(v.state().equals("SET") || v.values().isEmpty(), "继承或清空时不能附带值");
      check(!v.state().equals("SET") || !v.values().isEmpty(), "设置值不能为空，请使用清空");
      check(
          !a.valueMode().equals("COMMON_LOCKED") || v.state().equals("INHERIT"),
          "公共固定属性不能由 SKU 覆写");
      check(!a.required() || !v.state().equals("CLEAR"), "必填属性不能清空");
      var effective =
          v.state().equals("SET")
              ? v.values()
              : v.state().equals("CLEAR") || a.valueMode().equals("SKU_ONLY")
                  ? List.<String>of()
                  : a.defaultValues();
      validateValues(a, effective, input.status().equals("ACTIVE") || a.variantAxis());
      if (a.variantAxis())
        signature.add(a.id() + ":" + effective.getFirst().length() + ":" + effective.getFirst());
    }
    Collections.sort(signature);
    String sig = String.join("|", signature);
    if (id == null) {
      id = Ids.next();
      sql.execute(
          "insert into sku(id,tenant_id,spu_id,code,name,barcode,base_unit,status,variant_signature) values(?,?,?,?,?,?,?,?,?)",
          id,
          access.tenantId(),
          spu,
          code(input.code()),
          input.name(),
          input.barcode(),
          input.baseUnit(),
          input.status(),
          sig);
    } else {
      var old = sql.fetchOne("select * from sku where id=? and spu_id=?", id, spu);
      if (old == null) throw missing();
      check(
          old.get("status", String.class).equals("DRAFT") || !input.status().equals("DRAFT"),
          "已启用 SKU 不能恢复草稿");
      check(
          old.get("status", String.class).equals("DRAFT")
              || old.get("base_unit", String.class).equals(input.baseUnit()),
          "已启用 SKU 不能改变基础单位");
      check(
          old.get("status", String.class).equals("DRAFT")
              || old.get("variant_signature", String.class).equals(sig),
          "已启用 SKU 的规格不能改变，请新建 SKU");
      sql.execute(
          "update sku set code=?,name=?,barcode=?,base_unit=?,status=?,variant_signature=?,version=version+1,updated_at=now() where id=?",
          code(input.code()),
          input.name(),
          input.barcode(),
          input.baseUnit(),
          input.status(),
          sig,
          id);
    }
    for (var a : attributes) {
      var v = supplied.getOrDefault(a.id(), new ValueInput(a.id(), "INHERIT", List.of()));
      writeValue(access.tenantId(), spu, a.id(), id, v.state(), v.values(), a.dataType());
    }
    if (productRow(spu).get("status", String.class).equals(SpuLifecycle.ON_SALE.name()))
      check(
          detail(spu).skus().stream().anyMatch(s -> s.status().equals("ACTIVE")),
          "请先把 SPU 转为停售，再停用最后一个在用 SKU");
    event(access, spu, "SkuSaved");
    return id;
  }

  public void deleteSku(AccessService.Access access, UUID spu, UUID id, long version) {
    lock(spu, version);
    var row = sql.fetchOne("select * from sku where id=? and spu_id=?", id, spu);
    if (row == null) throw missing();
    check(row.get("status", String.class).equals("DRAFT"), "只能删除草稿 SKU");
    sql.execute("delete from sku where id=?", id);
    event(access, spu, "SkuDeleted");
  }

  /**
   * 写入一侧的属性赋值行：覆盖式（先删后插），{@code INHERIT} 就是"删掉这一行"。
   *
   * <p>{@code sku} 传 {@code null} 写 SPU 公共侧（3.2.2.1），传 SKU id 写覆写侧（3.2.2.2）——
   * 两侧是同一张表的同一份写入逻辑，所以「显式清空」在两侧的物理形态完全一致：
   * 一行 {@code state='CLEAR'}，且不带任何 {@code attribute_value_item}
   * （V1 的 validate_attribute_item 触发器会拒绝给 CLEAR 行写取值）。
   *
   * <p>包可见：3.2.2.2 的覆写写入复用它，保证覆写与已交付的 SKU 保存路径**逐字同构**。
   */
  void writeValue(
      UUID tenant,
      UUID spu,
      UUID attribute,
      UUID sku,
      String state,
      List<String> values,
      String type) {
    sql.execute(
        "delete from attribute_value where attribute_id=? and sku_id is not distinct from ?::uuid",
        attribute,
        sku);
    if (state.equals("INHERIT")) return;
    UUID vid = Ids.next();
    sql.execute(
        // 列名必须显式列出：V13 给 attribute_value 加了 updated_at，
        // 位置化插入会随列数变化而错位或直接报错（与 V11 的 attribute_option 同一个坑）。
        "insert into attribute_value(tenant_id,id,spu_id,attribute_id,sku_id,state) values(?,?,?,?,?,?)",
        tenant,
        vid,
        spu,
        attribute,
        sku,
        state);
    for (int i = 0; i < values.size(); i++) {
      String v = values.get(i);
      sql.execute(
          "insert into attribute_value_item values(?,?,?,?,?,?,?,?,?)",
          tenant,
          vid,
          attribute,
          i,
          type.equals("TEXT") ? v : null,
          type.equals("DECIMAL") ? new BigDecimal(v) : null,
          type.equals("BOOLEAN") ? Boolean.valueOf(v) : null,
          type.equals("DATE") ? LocalDate.parse(v) : null,
          type.equals("OPTION") ? v : null);
    }
  }

  /**
   * 审计 + outbox 事件；aggregate 始终是 SPU。
   *
   * <p>包可见：3.2.2.2 的 SKU 属性覆写复用它，让覆写事件与已交付的属性 / SKU 变更
   * 在同一条流上，下游不需要为覆写再开一路订阅。
   */
  void event(AccessService.Access access, UUID spu, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        access.tenantId(),
        access.userId(),
        spu,
        action);
    sql.execute(
        "insert into outbox_event(id,tenant_id,aggregate_id,aggregate_version,event_type,payload) select ?,tenant_id,id,version,?,jsonb_build_object('spuId',id,'version',version) from spu where id=?",
        Ids.next(),
        action,
        spu);
  }

  private static String table(String kind) {
    return switch (kind) {
      case "research" -> "product_research";
      case "compliance" -> "product_compliance";
      case "assets" -> "product_asset";
      default -> throw missing();
    };
  }

  private ProductRecord record(Record r) {
    return new ProductRecord(
        r.get("id", UUID.class),
        r.get("spu_id", UUID.class),
        r.get("title", String.class),
        r.get("notes", String.class),
        r.get("reference", String.class),
        r.get("status", String.class),
        r.get("locale", String.class),
        r.get("target_country", String.class),
        r.get("expires_on", String.class),
        r.get("version", Long.class),
        r.get("owner_id", UUID.class),
        r.get("department_id", UUID.class));
  }

  public List<ProductRecord> records(String kind) {
    return sql.fetch("select * from " + table(kind) + " order by updated_at desc limit 200")
        .map(this::record);
  }

  public UUID saveRecord(AccessService.Access access, String kind, UUID id, RecordInput input) {
    String table = table(kind);
    check(Set.of("DRAFT", "REVIEW", "APPROVED", "ARCHIVED").contains(input.status()), "无效状态");
    if (input.spuId() != null) productRow(input.spuId());
    if (!input.reference().isBlank()) {
      var url = java.net.URI.create(input.reference());
      check(
          "https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null,
          "资料链接必须使用 HTTPS");
    }
    LocalDate date =
        input.expiresOn() == null || input.expiresOn().isBlank()
            ? null
            : LocalDate.parse(input.expiresOn());
    if (id == null) {
      id = Ids.next();
      sql.execute(
          "insert into "
              + table
              + "(id,tenant_id,spu_id,title,notes,reference,status,locale,target_country,expires_on) values(?,?,?,?,?,?,?,?,?,?)",
          id,
          access.tenantId(),
          input.spuId(),
          input.title(),
          input.notes(),
          input.reference(),
          input.status(),
          input.locale(),
          input.targetCountry(),
          date);
    } else {
      int n =
          sql.execute(
              "update "
                  + table
                  + " set spu_id=?,title=?,notes=?,reference=?,status=?,locale=?,target_country=?,expires_on=?,version=version+1,updated_at=now() where id=? and version=?",
              input.spuId(),
              input.title(),
              input.notes(),
              input.reference(),
              input.status(),
              input.locale(),
              input.targetCountry(),
              date,
              id,
              input.version());
      if (n != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已更新或不存在，请刷新");
    }
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        access.tenantId(),
        access.userId(),
        id,
        kind + ".saved");
    return id;
  }

  public void deleteRecord(AccessService.Access access, String kind, UUID id, long version) {
    int n =
        sql.execute(
            "delete from " + table(kind) + " where id=? and version=? and status='DRAFT'",
            id,
            version);
    check(n == 1, "只能删除未变化的草稿记录，其他记录请归档");
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        access.tenantId(),
        access.userId(),
        id,
        kind + ".deleted");
  }
}
