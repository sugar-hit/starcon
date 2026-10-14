package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.2.2 SKU 详情：把单个 SKU 的完整档案按分区聚合成一次读取。
 *
 * <p>分区与「谁的数字」严格对齐：
 *
 * <ul>
 *   <li>编码规格、属性有效值、包装尺寸重量、单位换算、条码 —— catalog 自有，走 jOOQ 读本库；
 *   <li>库存分布、成本与售价、平台 listing、各单据引用 —— 各自是别的服务的事实源， 走 {@link
 *       SkuDetailProjectionPort}，未接入就标「未接入」，绝不回 0；
 *   <li>媒体 —— 只读 {@code product_asset} 现有的列，并如实说明这张表缺文件键。
 * </ul>
 *
 * <p>属性区展示的是 {@link EffectiveValues} 解析出来的**有效值**（SPU 公共值经 SKU 覆写/清空后的 结果），不是原始存的两行值——运营要看到的是"这个
 * SKU 实际是什么参数"。
 */
@Service
public class SkuDetailService {
  /** 体积换算：mm³ → cm³。 */
  private static final BigDecimal MM3_PER_CM3 = new BigDecimal("1000");

  private final DSLContext sql;
  private final SkuDetailProjectionPort projections;
  private final AccessService access;

  public SkuDetailService(
      DSLContext sql, SkuDetailProjectionPort projections, AccessService access) {
    this.sql = sql;
    this.projections = projections;
    this.access = access;
  }

  // ------------------------------------------------------------ 读取

  /**
   * 读取详情。
   *
   * <p>属性有效值用一条 SQL 把定义 + 公共赋值 + SKU 赋值一次性取齐（见 {@link #effectiveAttributes}），不在循环里逐个属性查库——一个 SKU
   * 上百个属性时那是 N+1。
   *
   * <p>媒体区跨了权限边界：{@code product_asset} 的 data_scope 策略要求 {@code app.permission} 形如 {@code
   * catalog.assets.%}（V3），而详情接口跑在 {@code catalog.sku.read} 下。因此媒体单独开一段 {@code catalog.assets.read}
   * 事务去读； 没有这个权限就整区标成不可用，而不是读到空数组冒充"这个 SKU 没有素材"。
   */
  public SkuDetail detail(AccessService.Access actor, UUID skuId) {
    var row =
        sql.fetchOne(
            "select s.tenant_id,s.id,s.spu_id,s.code,s.name,s.barcode,s.base_unit,s.status,s.version,"
                + "s.variant_signature,s.created_at,s.updated_at,"
                + "p.code as spu_code,p.name as spu_name,p.brand,p.category,p.status as spu_status"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.id=?",
            skuId);
    // 取不到就是「不存在或没有访问权限」：RLS 在这条 SQL 里生效，不区分两者，
    // 避免用 403/404 的差别探测别人的 SKU 是否存在。
    if (row == null) throw CatalogService.missing();

    UUID tenantId = row.get("tenant_id", UUID.class);
    UUID id = row.get("id", UUID.class);
    UUID spuId = row.get("spu_id", UUID.class);
    var attributes = effectiveAttributes(spuId, id);
    var packaging = packaging(id);
    var media = media(actor, spuId);
    var sources =
        Arrays.stream(SkuDetailProjectionPort.Source.values())
            .map(s -> projections.value(tenantId, id, s))
            .map(
                p ->
                    new SkuDetailSource(
                        p.source().code(),
                        p.source().label(),
                        p.source().ownerLabel(),
                        p.available(),
                        p.note()))
            .toList();

    return new SkuDetail(
        id,
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("status", String.class),
        row.get("version", Long.class),
        row.get("variant_signature", String.class),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)),
        spuId,
        row.get("spu_code", String.class),
        row.get("spu_name", String.class),
        row.get("brand", String.class),
        row.get("category", String.class),
        row.get("spu_status", String.class),
        specs(spuId, id),
        summary(attributes),
        attributes,
        row.get("base_unit", String.class),
        conversions(id, row.get("base_unit", String.class)),
        barcode(row.get("barcode", String.class)),
        packaging.value(),
        media.available(),
        media.rows(),
        media.limitation(),
        sources,
        history(id));
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  // ------------------------------------------------------------ 属性有效值

  /**
   * 一次性取齐「定义 + 公共赋值 + SKU 赋值」，再交给 {@link EffectiveValues} 解析。
   *
   * <p>公共侧是 {@code sku_id is null} 的那一行，SKU 侧是 {@code sku_id = 该 SKU} 的那一行。 V1 的 {@code UNIQUE
   * NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)} 保证两侧各自唯一， 所以这里用两个 LEFT JOIN 不会放大行数。
   *
   * <p>取值明细用标量子查询聚成数组，避免把每个属性的每个取值都拉成一行后在内存里重组。
   */
  private List<SkuEffectiveAttribute> effectiveAttributes(UUID spuId, UUID skuId) {
    return sql
        .fetch(
            "select d.id,d.code,d.name,d.data_type,d.value_mode,d.multiple,d.required,d.variant_axis,"
                + "d.unit,d.group_name,d.position,"
                + "cv.state as common_state,"
                + "(select array_agg(coalesce(i.text_value,i.decimal_value::text,i.boolean_value::text,"
                + "i.date_value::text,i.option_value) order by i.ordinal)"
                + "  from attribute_value_item i where i.value_id=cv.id) as common_values,"
                + "sv.state as sku_state,"
                + "(select array_agg(coalesce(i.text_value,i.decimal_value::text,i.boolean_value::text,"
                + "i.date_value::text,i.option_value) order by i.ordinal)"
                + "  from attribute_value_item i where i.value_id=sv.id) as sku_values,"
                + "(select array_agg(o.value order by o.value) from attribute_option o"
                + "  where o.attribute_id=d.id) as options"
                + " from attribute_definition d"
                + " left join attribute_value cv on cv.tenant_id=d.tenant_id and cv.attribute_id=d.id"
                + "   and cv.sku_id is null"
                + " left join attribute_value sv on sv.tenant_id=d.tenant_id and sv.attribute_id=d.id"
                + "   and sv.sku_id=?"
                + " where d.tenant_id=(select tenant_id from sku where id=?) and d.spu_id=?"
                + " order by d.position,d.name,d.id",
            skuId,
            skuId,
            spuId)
        .map(
            r -> {
              var attributeId = r.get("id", UUID.class);
              String mode = r.get("value_mode", String.class);
              boolean required = Boolean.TRUE.equals(r.get("required", Boolean.class));
              var resolved =
                  EffectiveValues.resolve(
                      mode,
                      new EffectiveValues.Raw(
                          r.get("common_state", String.class), strings(r, "common_values")),
                      new EffectiveValues.Raw(
                          r.get("sku_state", String.class), strings(r, "sku_values")),
                      required);
              return new SkuEffectiveAttribute(
                  attributeId,
                  r.get("code", String.class),
                  r.get("name", String.class),
                  r.get("data_type", String.class),
                  mode,
                  EffectiveValues.modeLabel(mode),
                  Boolean.TRUE.equals(r.get("multiple", Boolean.class)),
                  required,
                  Boolean.TRUE.equals(r.get("variant_axis", Boolean.class)),
                  r.get("unit", String.class),
                  r.get("group_name", String.class),
                  list(r, "options"),
                  resolved.overrideState(),
                  resolved.source().code(),
                  resolved.source().label(),
                  resolved.rawValues(),
                  resolved.effectiveValues(),
                  resolved.inherited(),
                  resolved.missingRequired());
            })
        .stream()
        .toList();
  }

  /** text[] 列转不可变列表；SQL 侧 array_agg 在无行时给 null，不是空数组。 */
  private static List<String> strings(Record r, String column) {
    return list(r, column);
  }

  private static List<String> list(Record r, String column) {
    var values = r.get(column, String[].class);
    return values == null ? List.of() : List.of(values);
  }

  private SkuAttributeSummary summary(List<SkuEffectiveAttribute> attributes) {
    return new SkuAttributeSummary(
        attributes.size(),
        (int)
            attributes.stream()
                .filter(a -> a.source().equals(EffectiveValues.Source.SKU_OVERRIDE.code()))
                .count(),
        (int)
            attributes.stream()
                .filter(a -> a.source().equals(EffectiveValues.Source.SKU_CLEARED.code()))
                .count(),
        (int) attributes.stream().filter(a -> a.inherited()).count(),
        (int)
            attributes.stream()
                .filter(a -> a.source().equals(EffectiveValues.Source.UNSET.code()))
                .count(),
        (int) attributes.stream().filter(SkuEffectiveAttribute::variantAxis).count(),
        (int) attributes.stream().filter(SkuEffectiveAttribute::missingRequired).count());
  }

  /** 规格轴取值：variant_axis 为真的轴在 V1 约束下必填单选，所以每个轴最多一个值。 */
  private List<SkuSpec> specs(UUID spuId, UUID skuId) {
    return sql.fetch(
            "select d.name as axis,i.option_value as value"
                + " from attribute_value v"
                + " join attribute_definition d on d.tenant_id=v.tenant_id and d.id=v.attribute_id"
                + " join attribute_value_item i on i.tenant_id=v.tenant_id and i.value_id=v.id"
                + " where v.sku_id=? and v.state='SET' and d.variant_axis"
                + " order by d.position,d.name",
            skuId)
        .map(r -> new SkuSpec(r.get("axis", String.class), r.get("value", String.class)));
  }

  // ------------------------------------------------------------ 包装

  private record PackagingResult(SkuPackaging value) {}

  private PackagingResult packaging(UUID skuId) {
    var r =
        sql.fetchOne(
            "select length_mm,width_mm,height_mm,net_weight_g,gross_weight_g,packaging_type,version,updated_at"
                + " from sku_package where sku_id=?",
            skuId);
    if (r == null) return new PackagingResult(null);
    return new PackagingResult(
        new SkuPackaging(
            r.get("version", Long.class),
            r.get("length_mm", BigDecimal.class),
            r.get("width_mm", BigDecimal.class),
            r.get("height_mm", BigDecimal.class),
            r.get("net_weight_g", BigDecimal.class),
            r.get("gross_weight_g", BigDecimal.class),
            r.get("packaging_type", String.class),
            volume(r),
            instant(r.get("updated_at", OffsetDateTime.class))));
  }

  /** 体积：长宽高都是真实采集值时才计算，缺一维就返回 null，不拿 0 顶替。 */
  private static BigDecimal volume(Record r) {
    var l = r.get("length_mm", BigDecimal.class);
    var w = r.get("width_mm", BigDecimal.class);
    var h = r.get("height_mm", BigDecimal.class);
    if (l == null || w == null || h == null) return null;
    return l.multiply(w).multiply(h).divide(MM3_PER_CM3, 3, RoundingMode.HALF_UP);
  }

  // ------------------------------------------------------------ 单位换算

  /**
   * 单位换算：只把**当前生效**的版本标成 current，历史版本仍然显示出来。
   *
   * <p>今天之前的版本 effective_to 已经闭合，今天之后才生效的还没开始——两者都不是"当前"， 但都必须看得见，否则改比例之后就再也查不到单据当时冻结的是哪一版。
   */
  private List<SkuUnitConversion> conversions(UUID skuId, String baseUnit) {
    LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
    return sql
        .fetch(
            "select unit_code,role,quantity_per_base,effective_from,effective_to from sku_unit_conversion"
                + " where sku_id=? order by unit_code,role,effective_from desc,effective_to desc nulls last",
            skuId)
        .map(
            r -> {
              LocalDate from = r.get("effective_from", LocalDate.class);
              var to = r.get("effective_to", LocalDate.class);
              boolean current = !from.isAfter(today) && (to == null || !to.isBefore(today));
              return new SkuUnitConversion(
                  r.get("unit_code", String.class),
                  r.get("role", String.class),
                  roleLabel(r.get("role", String.class)),
                  r.get("quantity_per_base", BigDecimal.class),
                  current,
                  from.toString(),
                  to == null ? null : to.toString());
            })
        .stream()
        .toList();
  }

  private static String roleLabel(String role) {
    return switch (role) {
      case "PURCHASE" -> "采购单位";
      case "SALE" -> "销售单位";
      case "PACKAGING" -> "包装单位";
      default -> throw new IllegalArgumentException("未知的单位角色：" + role);
    };
  }

  // ------------------------------------------------------------ 条码

  private SkuBarcode barcode(String primary) {
    return new SkuBarcode(
        primary,
        "3.1.2.3 编码与条码",
        "外部条码（sku_identifier，按 scheme+value 唯一，如 EAN/UPC）由 3.1.2.3 维护，该模块尚未开发；"
            + "店铺的 seller_sku 属平台属性、由 04 刊登维护，不是内部编码，也不能填到这里。");
  }

  // ------------------------------------------------------------ 媒体

  private record MediaResult(boolean available, List<SkuAsset> rows, String limitation) {}

  /**
   * 媒体区：只读 {@code product_asset} 现有可能的列。
   *
   * <p>这张表是 {@code LIKE product_research INCLUDING ALL} 的同构复制，缺陷有三处，直接影响本区： 没有文件键（存不了对象存储的
   * key，因此画不出缩略图）、没有 {@code sku_id}（只能落到 SPU 级）、 没有任何媒体类型/尺寸/格式列。因此本区**只**给出资料元数据行，并把缺陷写进
   * limitation 返回， 不去给这张错表加字段——修表是 3.4.1.1 的专门任务。
   */
  private MediaResult media(AccessService.Access actor, UUID spuId) {
    String limitation =
        "product_asset 表缺少文件键（storage key）、媒体类型、尺寸与格式，也没有 sku_id，"
            + "只能读到 SPU 级的资料元数据行，画不出缩略图也无法精确到 SKU。"
            + "该表结构缺陷由 3.4.1.1 媒体库的专门修表任务解决，本模块不往这张错表上加字段。";
    if (!actor.permissions().contains("catalog.assets.read"))
      return new MediaResult(
          false, List.of(), "当前角色没有素材查看权限（catalog.assets.read），媒体区未加载。" + limitation);
    // product_asset 的 data_scope 策略要求 app.permission 形如 catalog.assets.%（V3），
    // 而详情接口跑在 catalog.sku.read 下，所以这里用**同一个访问者**单独开一段
    // catalog.assets.read 的租户事务去读：租户上下文是事务级 GUC，换连接就丢；
    // 复用身份也让 assets 的 SELF/DEPARTMENT 数据范围跟主事务一致。
    var rows =
        access.run(
            actor,
            "catalog.assets.read",
            () ->
                sql
                    .fetch(
                        "select id,title,notes,reference,status,locale,target_country,expires_on,updated_at"
                            + " from product_asset where spu_id=? order by updated_at desc,id desc limit 100",
                        spuId)
                    .map(
                        r ->
                            new SkuAsset(
                                r.get("id", UUID.class),
                                r.get("title", String.class),
                                r.get("notes", String.class),
                                r.get("reference", String.class),
                                r.get("status", String.class),
                                r.get("locale", String.class),
                                r.get("target_country", String.class),
                                r.get("expires_on", LocalDate.class) == null
                                    ? null
                                    : r.get("expires_on", LocalDate.class).toString(),
                                instant(r.get("updated_at", OffsetDateTime.class))))
                    .stream()
                    .toList());
    return new MediaResult(true, rows, limitation);
  }

  // ------------------------------------------------------------ 状态历史

  private List<SkuStatusChange> history(UUID skuId) {
    return sql
        .fetch(
            "select id,from_status,to_status,reason,source,actor_id,occurred_at,created_at"
                + " from sku_status_event where sku_id=?"
                + " order by occurred_at desc,id desc limit 50",
            skuId)
        .map(
            r ->
                new SkuStatusChange(
                    r.get("id", UUID.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("reason", String.class),
                    r.get("source", String.class),
                    r.get("actor_id", UUID.class),
                    instant(r.get("occurred_at", OffsetDateTime.class)),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  // ------------------------------------------------------------ 编辑

  /**
   * 编辑入口：名称、条码、基础单位、包装尺寸重量，全部走 {@code version} 乐观锁。
   *
   * <p>属性覆写、编码规则与外部条码、单位版本维护分别属 3.2.2.2 / 3.1.2.3 / 3.1.2.4， 都不在这里代劳——详情页的编辑按钮只打开本模块真正拥有的字段。
   *
   * <p>包装是可选分区：四个尺寸/重量字段与包装方式全空就删掉这一行， 让"没有维护包装"在数据上是真的没有，而不是留一行全 null 装作维护过。
   */
  public SkuDetailSaved update(AccessService.Access actor, UUID skuId, SkuDetailInput input) {
    var row = sql.fetchOne("select tenant_id,spu_id,status,version from sku where id=?", skuId);
    if (row == null) throw CatalogService.missing();
    // CAS 乐观锁：版本不符直接 409。不用「先查后写」的读-改-写，
    // 那样两个并发编辑会双双通过校验然后互相覆盖。
    int updated =
        sql.execute(
            "update sku set name=?,barcode=?,base_unit=?,version=version+1,updated_at=now()"
                + " where id=? and version=?",
            input.name().trim(),
            input.barcode().trim(),
            input.baseUnit().trim(),
            skuId,
            input.version());
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU 已被他人修改，请刷新后重试");

    boolean packagingChanged = writePackaging(actor, skuId, input);
    long version =
        sql.fetchOne("select version from sku where id=?", skuId).get("version", Long.class);
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        skuId,
        "SkuProfileSaved");
    // 下游关心的是 SKU 档案变了（04 刊登可能要同步 listing 文案与图片），事件里带上新的 version。
    sql.execute(
        "insert into outbox_event(id,tenant_id,aggregate_id,aggregate_version,event_type,payload)"
            + " select ?,tenant_id,id,version,'SkuProfileSaved',"
            + " jsonb_build_object('skuId',id,'version',version,'packagingChanged',?::boolean)"
            + " from sku where id=?",
        Ids.next(),
        packagingChanged,
        skuId);
    return new SkuDetailSaved(skuId, version, packagingChanged);
  }

  /**
   * 写包装行；全空则删除。
   *
   * <p>净重/毛重与长宽高都用 NUMERIC（禁浮点），正负与"毛重不小于净重"由 V6 的 CHECK 兜底， 这里先给出可读的中文错误，省掉一次必然失败的往返。
   */
  private boolean writePackaging(AccessService.Access actor, UUID skuId, SkuDetailInput input) {
    String type = input.packagingType() == null ? "" : input.packagingType().trim();
    var length = mm(input.lengthMm(), "长");
    var width = mm(input.widthMm(), "宽");
    var height = mm(input.heightMm(), "高");
    var net = weight(input.netWeightG(), "净重");
    var gross = weight(input.grossWeightG(), "毛重");
    CatalogService.check(net == null || gross == null || gross.compareTo(net) >= 0, "毛重不能小于净重");
    boolean empty =
        type.isEmpty()
            && length == null
            && width == null
            && height == null
            && net == null
            && gross == null;
    if (empty) {
      return sql.execute("delete from sku_package where sku_id=?", skuId) > 0;
    }
    sql.execute(
        "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,net_weight_g,"
            + "gross_weight_g,packaging_type,version)"
            + " values(?,?,?,?,?,?,?,?,?,0)"
            + " on conflict (tenant_id,sku_id) do update set length_mm=excluded.length_mm,"
            + " width_mm=excluded.width_mm,height_mm=excluded.height_mm,net_weight_g=excluded.net_weight_g,"
            + " gross_weight_g=excluded.gross_weight_g,packaging_type=excluded.packaging_type,"
            + " version=sku_package.version+1,updated_at=now()",
        Ids.next(),
        actor.tenantId(),
        skuId,
        length,
        width,
        height,
        net,
        gross,
        type);
    return true;
  }

  private static BigDecimal mm(BigDecimal value, String label) {
    if (value == null) return null;
    CatalogService.check(value.signum() >= 0, label + "不能为负数");
    CatalogService.check(value.scale() <= 6, label + "最多 6 位小数");
    return value;
  }

  private static BigDecimal weight(BigDecimal value, String label) {
    if (value == null) return null;
    CatalogService.check(value.signum() >= 0, label + "不能为负数");
    CatalogService.check(value.scale() <= 6, label + "最多 6 位小数");
    return value;
  }
}
