package com.starcon.warehouse;

import static com.starcon.warehouse.PackingModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.3.3.2 打包与称重。
 *
 * <p>蓝图 08 卷 8.3.3.2：「<b>打包作业：选择包材、填充物、打印面单、称重与体积测量、记录包装尺寸
 * （用于体积重与平台申报）。</b>输入：复核结果；包材选择；称重数据；申报信息（3.7.2 物理属性）。 输出：包裹记录（parcel + parcel_item）+ 实测重量体积。」
 * 关系：「上游 = 8.3.3.1；下游 = 8.3.4.1 发货、10 物流（取重算费）。 <b>包裹必须含明细项</b>（哪些 SKU、多少件），只有重量和运单号无法追溯与对账。」
 *
 * <h2>本模块对库存做了什么、没做什么</h2>
 *
 * <p><b>什么都不做。</b>打包只读、只记：货还在打包区，{@code on_hand} / {@code reserved} 一动不动；不写 {@code
 * inventory_entry}、不释放预占、也不实扣。实发扣减与预占消费 在 8.3.4.1 的同一事务里完成（与 8.3.3.1「复核不动库存」同一纪律）。
 *
 * <h2>状态怎么交出去</h2>
 *
 * <ul>
 *   <li><b>开单</b>：只接受一张 <b>PASSED</b> 的复核单（V121 的 BEFORE INSERT 守卫）， 并把复核基线冻结到 packing_order 上 ——
 *       不是每次去 join recount_line。
 *   <li><b>完成</b>：出库单 PENDING_PACKING → <b>PENDING_SHIPMENT</b>，交给 8.3.4.1。 <b>从不碰
 *       SHIPPED</b>（运单号与承运渠道是下游的事）。
 *   <li><b>失败</b>：出库单退回 <b>PENDING_RECHECK</b>（V72 留的「打包时发现缺件回复核」返工边）。
 * </ul>
 *
 * <h2>哪些量是派生的（页面与下游都不要再算一遍）</h2>
 *
 * <p>净重（毛重 − 包材自重）、体积（三边相乘）、以及 packing_order 的全部合计， 都是 V121 的 <b>触发器</b>写的。服务层只读回来。体积重（除数）由 10 物流算
 * —— 见 {@link PackingCapabilities#volumetricWeightBasis()}。
 */
@Service
public class PackingService {

  private static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("STARTED_AT", "PACKING_CODE", "BASELINE_QUANTITY", "PACKED_QUANTITY");

  private static final List<String> STATUSES = List.of("OPEN", "PACKED", "FAILED", "CANCELLED");

  private static final List<String> MATERIAL_ROLES = List.of("PACKAGING", "CUSHIONING");

  private static final List<String> MATERIAL_TYPES =
      List.of("CARTON", "ENVELOPE", "BAG", "BOX", "BUBBLE", "PAPER", "FOAM", "OTHER");

  private static final String WEIGHT_BASIS =
      "本服务所有称重一律用克（g），NUMERIC(19,3)：秤按克读。禁浮点 —— 实测值要进对账与申报，" + "浮点误差会变成退单原因。";

  private static final String DIMENSION_BASIS =
      "包装尺寸一律用厘米（cm），NUMERIC(14,3)；体积用立方米（cbm），NUMERIC(19,6) = 长×宽×高/10⁶，" + "由触发器派生，应用层写的值一律被覆盖。";

  private static final String VOLUMETRIC_BASIS =
      "体积重不在本模块算：除数（6000/5000/…）是承运商渠道口径，归 10 物流。"
          + "这里给的是实测三边与实测体积，10 物流按自己的除数取这几个数去算 —— "
          + "在本模块先乘一个除数就是替渠道定价。";

  private static final String DECLARATION_BASIS =
      "申报信息只快照本服务真读得到的 customs_declaration_line（V85，同服务）；"
          + "蓝图输入写的「3.7.2 物理属性」属 catalog（V6 sku_detail / V28 安全属性），"
          + "跨服务读表被模块边界纪律禁止且尚未交付读取端口，故 unavailable 里显式报出，"
          + "绝不编一份数字冒充已带出申报信息。";

  private static final String MEASURE_GUARD_BASIS =
      "包材目录是权威：实测三边不许超出包材外形（留 5mm 装配公差）、实测毛重不许超承重、" + "毛重不许低于包材自重 —— 三条都在 DB 层当场拒，选错包材与超载在结算前就被拦。";

  private final DSLContext sql;

  public PackingService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力与边界声明。
   *
   * <p>四段「拿不到 / 不由本模块负责」的事<b>显式报出来</b>（照 8.3.1.2 的灰块约定）： 空列表会被读成「这单没有包裹」，而不是「这段能力还没接入」。
   */
  public PackingCapabilities capabilities() {
    return new PackingCapabilities(
        List.of(
            "选择包材与填充物（真实目录，不是自由文本）",
            "打印面单（引用 8.3.4.2 已交付的打印任务，不自建渲染引擎）",
            "称重与体积测量（实测毛重 + 实测三边，派生净重与体积）",
            "记录包装尺寸（供 10 物流算体积重、供平台申报）",
            "包裹 + 包裹明细项（哪些 SKU、多少件）"),
        STATUSES,
        MATERIAL_ROLES,
        MATERIAL_TYPES,
        List.of("CUSTOMS_DECLARATION_LINE", "NOT_FOUND"),
        "g（克）",
        "cm（厘米）",
        "cbm（立方米）",
        WEIGHT_BASIS,
        DIMENSION_BASIS,
        VOLUMETRIC_BASIS,
        DECLARATION_BASIS,
        false,
        MEASURE_GUARD_BASIS,
        List.of(
            new UnavailableLink(
                "SKU 物理档案（长宽高/净重/毛重/包装方式）",
                "3.7.2.1",
                "/catalog",
                "属 catalog 服务（V6 sku_detail / V28 物理与安全属性），本服务跨服务读表被模块边界纪律"
                    + "禁止，且 3.7.2.1 尚未交付供 warehouse 读取的授权端口 —— 故本模块不返回任何"
                    + "物理属性数字，而不是编一份。"),
            new UnavailableLink(
                "运单号与承运渠道",
                "8.3.4.1",
                "/warehouse/inventory/outbound-orders",
                "发货交接归 8.3.4.1：打包完成只把出库单推到「待发货」，从不碰 SHIPPED。"),
            new UnavailableLink(
                "体积重与运费试算",
                "10",
                "/logistics",
                "体积重除数（6000/5000/…）是承运商渠道口径，归 10 物流；本模块只给实测三边与实测体积。"),
            new UnavailableLink(
                "面单内容渲染与打印设备",
                "8.3.4.2",
                "/warehouse/inventory/printing",
                "面单内容与打印次数的权威是 8.3.4.2 已交付的 print_job / label_print_log；"
                    + "本模块只在包裹上挂引用并记次数，不建第二个打印引擎。")),
        "8.3.3.1",
        List.of("8.3.4.1", "10"));
  }

  // ================================================================ 包材目录

  /** 建包材 / 填充物（真实目录，不是自由文本：自重与外形是净重口径的一部分）。 */
  @Transactional
  public PackingMaterialRow createMaterial(PackingMaterialInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String code = text(input.materialCode());
    if (code.isEmpty()) throw bad("materialCode 不能为空");
    if (text(input.name()).isEmpty()) throw bad("name 不能为空");
    requireOneOf(input.materialRole(), MATERIAL_ROLES, "materialRole");
    requireOneOf(input.materialType(), MATERIAL_TYPES, "materialType");

    BigDecimal tare = decimal(input.tareWeightG(), "tareWeightG", BigDecimal.ZERO);
    if (tare.signum() < 0) throw bad("tareWeightG 不能为负");
    if ("CUSHIONING".equals(input.materialRole()) && tare.signum() != 0)
      throw bad("填充物不进净重口径，tareWeightG 只能是 0");

    BigDecimal max = decimal(input.maxGrossWeightG(), "maxGrossWeightG", BigDecimal.ZERO);
    if (max.signum() < 0) throw bad("maxGrossWeightG 不能为负");

    BigDecimal length = decimal(input.outerLengthCm(), "outerLengthCm", null);
    BigDecimal width = decimal(input.outerWidthCm(), "outerWidthCm", null);
    BigDecimal height = decimal(input.outerHeightCm(), "outerHeightCm", null);
    if (length == null || width == null || height == null)
      throw bad("outerLengthCm / outerWidthCm / outerHeightCm 必填（包材外形是实测的上界）");
    if (length.signum() <= 0 || width.signum() <= 0 || height.signum() <= 0)
      throw bad("包材外形三边必须都大于 0");

    if (input.warehouseId() != null) requireWarehouse(tenant, input.warehouseId());

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into packing_material(id,tenant_id,material_code,name,material_role,material_type,"
            + "warehouse_id,outer_length_cm,outer_width_cm,outer_height_cm,tare_weight_g,"
            + "max_gross_weight_g,created_by) values(?,?,?,?,?,?,?,cast(? as numeric),"
            + "cast(? as numeric),cast(? as numeric),cast(? as numeric),cast(? as numeric),?)",
        id,
        tenant,
        code,
        text(input.name()),
        input.materialRole(),
        input.materialType(),
        input.warehouseId(),
        plain(length),
        plain(width),
        plain(height),
        plain(tare),
        plain(max),
        actorId);
    return materialRow(
        one(tenant, "select * from packing_material where tenant_id=? and id=?", id));
  }

  /** 包材列表（真分页 + 真筛选）。 */
  public PackingMaterialPage materials(
      UUID warehouseId,
      String role,
      String type,
      String status,
      String keyword,
      int page,
      int size) {
    UUID tenant = currentTenant();
    var where = new StringBuilder(" where tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and (warehouse_id=? or warehouse_id is null)");
      args.add(warehouseId);
    }
    if (text(role).length() > 0) {
      where.append(" and material_role=?");
      args.add(role);
    }
    if (text(type).length() > 0) {
      where.append(" and material_type=?");
      args.add(type);
    }
    if (text(status).length() > 0) {
      where.append(" and status=?");
      args.add(status);
    }
    if (text(keyword).length() > 0) {
      where.append(" and (material_code ilike ? or name ilike ?)");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    long total = count("select count(*) as c from packing_material" + where, args);
    var rows =
        sql.fetch(
            "select * from packing_material"
                + where
                + " order by material_code, id limit ? offset ?",
            all(args, pageSize(size), offset(page, size)));
    return new PackingMaterialPage(
        rows.stream().map(this::materialRow).toList(), page, pageSize(size), total);
  }

  /** 包材详情：包材本身 + 被用过多少次（现算，不是存的列）。 */
  public PackingMaterialDetail material(String materialId) {
    UUID tenant = currentTenant();
    UUID id = uuid(materialId);
    Record row = one(tenant, "select * from packing_material where tenant_id=? and id=?", id);
    long asPackaging =
        sql.fetchOne(
                "select count(*) as c from parcel where tenant_id=? and material_id=?", tenant, id)
            .get("c", Long.class);
    long asFiller =
        sql.fetchOne(
                "select count(*) as c from parcel where tenant_id=? and filler_material_id=?",
                tenant,
                id)
            .get("c", Long.class);
    return new PackingMaterialDetail(materialRow(row), asPackaging, asFiller);
  }

  /** 停用包材。停用后不能复活（包材口径的历史必须可追，V121 触发器兜底）。 */
  @Transactional
  public PackingMaterialRow retireMaterial(
      String materialId, PackingMaterialRetireInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (text(input.reason()).isEmpty()) throw bad("停用包材必须写原因");
    UUID id = uuid(materialId);
    Record row = one(tenant, "select * from packing_material where tenant_id=? and id=?", id);
    if (!"ACTIVE".equals(row.get("status", String.class)))
      throw conflict("包材 " + row.get("material_code", String.class) + " 已经是停用状态");
    sql.execute(
        "update packing_material set status='RETIRED',retired_at=?::timestamptz,retire_reason=?,"
            + "updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(),
        trim(input.reason(), 300),
        tenant,
        id);
    return materialRow(
        one(tenant, "select * from packing_material where tenant_id=? and id=?", id));
  }

  // ================================================================ 开单

  /**
   * 开一张打包单：接一张 <b>PASSED</b> 的复核单，把复核基线冻结成本单的基线。
   *
   * <p>三道前置：复核单已通过（V121 的 BEFORE INSERT 守卫同样拒）、出库单在「待打包」、 基线非空。基线由触发器从复核单读数覆盖，调用方写的两个数一律不作数。
   */
  @Transactional
  public PackingCreated open(PackingOpenInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.recountSessionId() == null) throw bad("recountSessionId 不能为空");

    Record session =
        one(
            tenant,
            "select * from recount_session where tenant_id=? and id=?",
            input.recountSessionId());
    if (!"PASSED".equals(session.get("status", String.class)))
      throw conflict(
          "复核单 "
              + session.get("session_code", String.class)
              + " 当前是 "
              + session.get("status", String.class)
              + "，只有「复核通过」（PASSED）才能开打包单 —— "
              + "打包接的是已核齐的货。");
    if (!exists(
        tenant,
        "select 1 from recount_line where tenant_id=? and session_id=?",
        input.recountSessionId())) throw conflict("复核单没有基线，没有可装的货，不能开打包单");

    if (exists(
        tenant,
        "select 1 from packing_order where tenant_id=? and recount_session_id=?",
        input.recountSessionId()))
      throw conflict(
          "复核单 "
              + session.get("session_code", String.class)
              + " 已经有一张打包单。一张复核单只装一次 —— 打包失败退回复核后的第二轮"
              + "是**新的一张复核单**，不是把上一张打包单改掉。");

    Record order =
        one(
            tenant,
            "select * from outbound_order where tenant_id=? and id=?",
            session.get("outbound_order_id", UUID.class));
    if (!"PENDING_PACKING".equals(order.get("status", String.class)))
      throw conflict(
          "出库单 "
              + order.get("order_code", String.class)
              + " 当前是 "
              + order.get("status", String.class)
              + "，只有「待打包」（PENDING_PACKING）才能开打包单");

    UUID sessionId = input.recountSessionId();
    String basis = packingBasis(session);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into packing_order(id,tenant_id,packing_code,recount_session_id,outbound_order_id,"
            + "warehouse_id,packing_basis,outbound_status_before,packer_user_id,started_at,created_by)"
            + " values(?,?,?,?,?,?,?,'PENDING_PACKING',?,?::timestamptz,?)",
        id,
        tenant,
        packingCode(),
        sessionId,
        order.get("id", UUID.class),
        session.get("warehouse_id", UUID.class),
        basis,
        input.packerUserId(),
        OffsetDateTime.now(),
        actorId);
    event(
        tenant,
        id,
        "OPENED",
        null,
        "OPEN",
        null,
        actorId,
        OffsetDateTime.now(),
        "开打包单：接复核单 "
            + session.get("session_code", String.class)
            + " 的基线 "
            + plain(session.get("baseline_quantity", BigDecimal.class))
            + " 件。");
    return created(tenant, id);
  }

  private String packingBasis(Record session) {
    StringBuilder sb = new StringBuilder();
    sb.append("接 8.3.3.1 复核单 ")
        .append(session.get("session_code", String.class))
        .append("（")
        .append(session.get("status", String.class))
        .append("）的基线，共 ")
        .append(session.get("baseline_line_count", Integer.class))
        .append(" 行 / ")
        .append(plain(session.get("baseline_quantity", BigDecimal.class)))
        .append(" 件。");
    sb.append(' ').append(WEIGHT_BASIS);
    sb.append(' ').append(VOLUMETRIC_BASIS);
    return trim(sb.toString(), 600);
  }

  // ================================================================ 包裹

  /** 建一个包裹：选包材（角色必须是 PACKAGING）与填充物（可选，角色必须是 CUSHIONING）。 */
  @Transactional
  public ParcelRow addParcel(String orderId, ParcelAddInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "建包裹");
    if (input.materialId() == null) throw bad("materialId 不能为空（包材必须选，不接受自由文本）");
    requireMaterial(tenant, input.materialId(), "PACKAGING");

    int parcelNo =
        input.parcelNo() == null
            ? nextParcelNo(tenant, order.get("id", UUID.class))
            : input.parcelNo();
    if (parcelNo <= 0) throw bad("parcelNo 必须大于 0");

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into parcel(id,tenant_id,packing_order_id,parcel_no,parcel_code,material_id,"
            + "filler_material_id,created_by) values(?,?,?,?,?,?,?,?)",
        id,
        tenant,
        order.get("id", UUID.class),
        parcelNo,
        parcelCode(parcelNo),
        input.materialId(),
        input.fillerMaterialId(),
        actorId);
    event(
        tenant,
        order.get("id", UUID.class),
        "PACKAGE_ADDED",
        "OPEN",
        "OPEN",
        id,
        actorId,
        OffsetDateTime.now(),
        "建包裹 " + parcelCode(parcelNo) + "（箱号 " + parcelNo + "）。");
    return parcelRow(one(tenant, "select * from parcel where tenant_id=? and id=?", id));
  }

  private int nextParcelNo(UUID tenant, UUID orderId) {
    Record r =
        sql.fetchOne(
            "select coalesce(max(parcel_no),0) as m from parcel where tenant_id=? and packing_order_id=?",
            tenant,
            orderId);
    return r.get("m", Integer.class) + 1;
  }

  /**
   * 装一件明细。
   *
   * <p>明细<b>必须指回一条复核基线行</b>（{@code recountLineId} 必填，DB 复合外键兜底）：
   * 没有来源行的明细就是凭空装进去的东西，那正是蓝图说的「无法追溯与对账」。 逐件核对的基线行必须一行一号（件数恒为 1），件数不许超基线（跨包裹合计，DB 触发器）。
   */
  @Transactional
  public ParcelItemRow addItem(
      String orderId, String parcelId, ParcelItemInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "装明细");
    Record parcel = parcelRecord(tenant, order.get("id", UUID.class), parcelId);

    if (input.recountLineId() == null)
      throw bad("recountLineId 不能为空 —— 包裹明细必须指回一条复核基线行（哪些 SKU、多少件才能对账）");
    BigDecimal quantity = decimal(input.quantity(), "quantity", null);
    if (quantity == null || quantity.signum() <= 0) throw bad("quantity 必须大于 0");

    Record line =
        one(tenant, "select * from recount_line where tenant_id=? and id=?", input.recountLineId());
    if (!line.get("session_id", UUID.class).equals(order.get("recount_session_id", UUID.class)))
      throw bad("复核基线行不属于这张打包单对应的复核单");

    // 序列号：逐件核对的行必须一行一号；不是逐件核对的行不许带号。
    boolean requiresSerial = Boolean.TRUE.equals(line.get("requires_serial", Boolean.class));
    UUID serialId = null;
    String serialNumber = null;
    if (input.serialId() != null) {
      Record serial = resolveSerial(tenant, input.serialId(), line.get("sku_id", UUID.class));
      serialId = serial.get("id", UUID.class);
      serialNumber = serial.get("serial_number", String.class);
      if (quantity.compareTo(BigDecimal.ONE) != 0)
        throw bad("带序列号时件数必须恒为 1 —— 一件一号，把三个号塞进「共 3 件」" + "就回到「只有件数无法追溯」的毛病上了");
    } else if (requiresSerial) {
      throw bad("这一行复核时做了逐件核对，装包也必须一行一号（带 serialId，件数恒为 1）");
    }

    // 申报快照：只读本服务真读得到的报关明细，取不到如实记 NOT_FOUND。
    var declaration = declarationOf(tenant, line.get("sku_id", UUID.class));

    int itemNo =
        sql.fetchOne(
                "select coalesce(max(item_no),0)+1 as n from parcel_item where tenant_id=? and parcel_id=?",
                tenant,
                parcel.get("id", UUID.class))
            .get("n", Integer.class);

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into parcel_item(id,tenant_id,parcel_id,item_no,recount_line_id,outbound_line_id,"
            + "sku_id,sku_code,sku_name,quantity,serial_id,serial_number,declaration_source,"
            + "declaration_line_id,hs_code,declared_item_name,declared_origin_country_code,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,cast(? as numeric),?,?,?,?,?,?,?,?)",
        id,
        tenant,
        parcel.get("id", UUID.class),
        itemNo,
        input.recountLineId(),
        line.get("outbound_line_id", UUID.class),
        line.get("sku_id", UUID.class),
        line.get("sku_code", String.class),
        line.get("sku_name", String.class),
        plain(quantity),
        serialId,
        serialNumber,
        declaration.source(),
        declaration.lineId(),
        declaration.hsCode(),
        declaration.itemName(),
        declaration.country(),
        actorId);
    event(
        tenant,
        order.get("id", UUID.class),
        "ITEM_ADDED",
        "OPEN",
        "OPEN",
        parcel.get("id", UUID.class),
        actorId,
        OffsetDateTime.now(),
        "装明细 "
            + line.get("sku_code", String.class)
            + " × "
            + plain(quantity)
            + " 到包裹 "
            + parcel.get("parcel_code", String.class)
            + "。");
    return itemRow(one(tenant, "select * from parcel_item where tenant_id=? and id=?", id));
  }

  /**
   * 申报快照：只读本服务真读得到的报关明细（V85 customs_declaration_line，同服务）。
   *
   * <p>蓝图输入写的「3.7.2 物理属性」属 catalog，本服务跨服务读不到， 取不到就记 NOT_FOUND —— 绝不猜一个 HS 编码填上去。
   */
  private record Declaration(
      String source, UUID lineId, String hsCode, String itemName, String country) {}

  private Declaration declarationOf(UUID tenant, UUID skuId) {
    Record d =
        sql.fetchOne(
            "select id,hs_code,declared_item_name,origin_country_code from customs_declaration_line"
                + " where tenant_id=? and sku_id=? order by created_at desc, id limit 1",
            tenant,
            skuId);
    if (d == null) return new Declaration("NOT_FOUND", null, "", "", "");
    return new Declaration(
        "CUSTOMS_DECLARATION_LINE",
        d.get("id", UUID.class),
        d.get("hs_code", String.class),
        d.get("declared_item_name", String.class),
        d.get("origin_country_code", String.class));
  }

  /**
   * 序列号解析：**必须解析成功**才能装这一件。
   *
   * <p>逐件核对的基线行在 DB 层就拒「没有序列号的明细」（V121 的 {@code
   * guard_parcel_item_quantity_fn}），所以这里解析不出来就直接说清楚是什么错。 序列号原文只在 inventory_serial 上（与 8.3.3.1
   * 同一纪律：不在明细上抄一份， 否则两处会分叉）。
   */
  private Record resolveSerial(UUID tenant, UUID serialId, UUID skuId) {
    Record s =
        sql.fetchOne("select * from inventory_serial where tenant_id=? and id=?", tenant, serialId);
    if (s == null) throw notFound("序列号不存在或不属于本租户");
    if (!s.get("sku_id", UUID.class).equals(skuId))
      throw bad(
          "序列号 "
              + s.get("serial_number", String.class)
              + " 不是这个 SKU 的 —— "
              + "序列号与 SKU 的绑定是永久的（一台设备换不成别的型号）");
    return s;
  }

  /**
   * 称重与量尺寸（实测重量体积 —— 本模块的核心产出）。
   *
   * <p>净重与体积由 DB 触发器派生，服务层只把实测毛重与三边写进去； 超重、超外形、毛重低于自重三条由 V121 触发器当场拒。
   */
  @Transactional
  public ParcelRow measure(
      String orderId, String parcelId, ParcelMeasureInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "称重");
    Record parcel = parcelRecord(tenant, order.get("id", UUID.class), parcelId);

    BigDecimal gross = decimal(input.measuredGrossWeightG(), "measuredGrossWeightG", null);
    if (gross == null || gross.signum() <= 0) throw bad("measuredGrossWeightG 必须大于 0");
    BigDecimal length = decimal(input.outerLengthCm(), "outerLengthCm", null);
    BigDecimal width = decimal(input.outerWidthCm(), "outerWidthCm", null);
    BigDecimal height = decimal(input.outerHeightCm(), "outerHeightCm", null);
    if (length == null || width == null || height == null)
      throw bad("outerLengthCm / outerWidthCm / outerHeightCm 必填 —— 实测体积是本模块交给 10 物流的产出");
    if (length.signum() <= 0 || width.signum() <= 0 || height.signum() <= 0)
      throw bad("包装尺寸三边必须都大于 0");

    OffsetDateTime at = instant(input.measuredAt(), "measuredAt");
    sql.execute(
        "update parcel set outer_length_cm=cast(? as numeric),outer_width_cm=cast(? as numeric),"
            + "outer_height_cm=cast(? as numeric),measured_gross_weight_g=cast(? as numeric),"
            + "measured_at=?::timestamptz,measured_by=?,updated_at=now()"
            + " where tenant_id=? and id=?",
        plain(length),
        plain(width),
        plain(height),
        plain(gross),
        at,
        actorId,
        tenant,
        parcel.get("id", UUID.class));
    Record after =
        one(
            tenant,
            "select * from parcel where tenant_id=? and id=?",
            parcel.get("id", UUID.class));
    event(
        tenant,
        order.get("id", UUID.class),
        "MEASURED",
        "OPEN",
        "OPEN",
        parcel.get("id", UUID.class),
        actorId,
        at,
        "实测包裹 "
            + parcel.get("parcel_code", String.class)
            + "：毛重 "
            + plain(gross)
            + " g，"
            + plain(length)
            + "×"
            + plain(width)
            + "×"
            + plain(height)
            + " cm，净重 "
            + plain(after.get("net_content_weight_g", BigDecimal.class))
            + " g，体积 "
            + plain(after.get("volume_cbm", BigDecimal.class))
            + " cbm。");
    return parcelRow(after);
  }

  /**
   * 记面单打印（<b>只引用</b> 8.3.4.2 的 print_job，不在本模块渲染 ZPL）。
   *
   * <p>面单内容与打印次数的权威是 V73 的 print_job / label_print_log； 自造一份渲染内容会出现两份「印的是什么」。
   */
  @Transactional
  public ParcelRow recordLabel(
      String orderId, String parcelId, ParcelLabelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "记面单打印");
    Record parcel = parcelRecord(tenant, order.get("id", UUID.class), parcelId);
    if (input.labelPrintJobId() == null) throw bad("labelPrintJobId 不能为空");

    Record job =
        one(tenant, "select * from print_job where tenant_id=? and id=?", input.labelPrintJobId());
    OffsetDateTime at = instant(input.printedAt(), "printedAt");
    sql.execute(
        "update parcel set label_print_job_id=?,label_print_count=label_print_count+1,"
            + "label_printed_at=?::timestamptz,updated_at=now() where tenant_id=? and id=?",
        job.get("id", UUID.class),
        at,
        tenant,
        parcel.get("id", UUID.class));
    event(
        tenant,
        order.get("id", UUID.class),
        "LABEL_PRINTED",
        "OPEN",
        "OPEN",
        parcel.get("id", UUID.class),
        actorId,
        at,
        "包裹 "
            + parcel.get("parcel_code", String.class)
            + " 记面单打印第 "
            + (parcel.get("label_print_count", Integer.class) + 1)
            + " 次，引用打印任务 "
            + job.get("job_no", String.class)
            + "（内容与次数的权威在 8.3.4.2）。");
    return parcelRow(
        one(
            tenant,
            "select * from parcel where tenant_id=? and id=?",
            parcel.get("id", UUID.class)));
  }

  // ================================================================ 结算 / 失败 / 取消

  /**
   * 打包完成。
   *
   * <p>五个前提（DB 层同样拒一遍）：至少一个包裹、每个包裹都有明细、都称过重量过尺寸、 每行基线的实装件数<b>恰好等于</b>应发、有打包人。通过后把出库单推到
   * <b>PENDING_SHIPMENT</b> 交给 8.3.4.1，<b>从不碰 SHIPPED</b>。
   */
  @Transactional
  public PackingCreated complete(String orderId, PackingCompleteInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "完成打包");

    PackingCompletionReport report = completion(tenant, order.get("id", UUID.class));
    if (!report.packable())
      throw conflict(
          "打包单 "
              + order.get("packing_code", String.class)
              + " 还不能完成："
              + String.join("；", report.blockingReasons()));

    UUID packer =
        input.packerUserId() != null
            ? input.packerUserId()
            : order.get("packer_user_id", UUID.class);
    if (packer == null) throw bad("打包完成必须有打包人");

    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update packing_order set status='PACKED',packer_user_id=?,completed_at=?::timestamptz,"
            + "updated_at=now() where tenant_id=? and id=?",
        packer,
        at,
        tenant,
        order.get("id", UUID.class));
    // 交给 8.3.4.1：待打包 → 待发货。发货交接（实发扣减 + 预占消费）是下游的事。
    sql.execute(
        "update outbound_order set status='PENDING_SHIPMENT',updated_at=now()"
            + " where tenant_id=? and id=?",
        tenant,
        order.get("outbound_order_id", UUID.class));
    Record after =
        one(
            tenant,
            "select * from packing_order where tenant_id=? and id=?",
            order.get("id", UUID.class));
    event(
        tenant,
        order.get("id", UUID.class),
        "PACKED",
        "OPEN",
        "PACKED",
        null,
        actorId,
        at,
        "打包完成："
            + after.get("parcel_count", Integer.class)
            + " 个包裹 / "
            + after.get("parcel_item_count", Integer.class)
            + " 条明细 / 实测毛重合计 "
            + plain(after.get("total_gross_weight_g", BigDecimal.class))
            + " g / 体积合计 "
            + plain(after.get("total_volume_cbm", BigDecimal.class))
            + " cbm；出库单交到「待发货」（8.3.4.1）。");
    return created(tenant, order.get("id", UUID.class));
  }

  /** 打包失败（打包时发现缺件/破损）。出库单退回 PENDING_RECHECK 重新复核。 */
  @Transactional
  public PackingCreated fail(String orderId, PackingFailInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "判失败");
    if (text(input.reason()).isEmpty()) throw bad("打包失败必须写原因（缺件？破损？）");

    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update packing_order set status='FAILED',failed_at=?::timestamptz,fail_reason=?,"
            + "updated_at=now() where tenant_id=? and id=?",
        at,
        trim(input.reason(), 300),
        tenant,
        order.get("id", UUID.class));
    sql.execute(
        "update outbound_order set status='PENDING_RECHECK',updated_at=now()"
            + " where tenant_id=? and id=?",
        tenant,
        order.get("outbound_order_id", UUID.class));
    event(
        tenant,
        order.get("id", UUID.class),
        "FAILED",
        "OPEN",
        "FAILED",
        null,
        actorId,
        at,
        "打包失败：" + trim(input.reason(), 300) + "；出库单退回「待复核」重新核。");
    return created(tenant, order.get("id", UUID.class));
  }

  /** 取消打包（<b>不动库存</b>，打包本来就不碰库存）。原因必填。 */
  @Transactional
  public PackingCreated cancel(String orderId, PackingCancelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    requireOpen(order, "取消");
    if (text(input.reason()).isEmpty()) throw bad("取消打包必须写原因");

    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update packing_order set status='CANCELLED',cancelled_at=?::timestamptz,cancel_reason=?,"
            + "updated_at=now() where tenant_id=? and id=?",
        at,
        trim(input.reason(), 300),
        tenant,
        order.get("id", UUID.class));
    event(
        tenant,
        order.get("id", UUID.class),
        "CANCELLED",
        "OPEN",
        "CANCELLED",
        null,
        actorId,
        at,
        "取消打包：" + trim(input.reason(), 300) + "（库存不动）。");
    return created(tenant, order.get("id", UUID.class));
  }

  /** 能不能完成打包 —— 页面的唯一判据来源（前端不自己数行、不自己算重量）。 */
  public PackingCompletionReport completionOf(String orderId) {
    UUID tenant = currentTenant();
    Record order = orderRecord(tenant, orderId);
    return completion(tenant, order.get("id", UUID.class));
  }

  private PackingCompletionReport completion(UUID tenant, UUID orderId) {
    List<String> blocking = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    int parcelCount =
        intOf(
            tenant,
            "select count(*) as c from parcel where tenant_id=? and packing_order_id=?",
            orderId);
    int empty =
        intOf(
            tenant,
            "select count(*) as c from parcel p where p.tenant_id=? and p.packing_order_id=?"
                + " and not exists (select 1 from parcel_item pi where pi.tenant_id=p.tenant_id"
                + " and pi.parcel_id=p.id)",
            orderId);
    int unmeasured =
        intOf(
            tenant,
            "select count(*) as c from parcel where tenant_id=? and packing_order_id=?"
                + " and (measured_gross_weight_g is null or volume_cbm is null)",
            orderId);
    int itemCount =
        intOf(
            tenant,
            "select count(*) as c from parcel_item pi join parcel p"
                + " on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                + " where p.tenant_id=? and p.packing_order_id=?",
            orderId);
    BigDecimal baseline =
        sql.fetchOne(
                "select coalesce(sum(expected_quantity),0) as q from recount_line"
                    + " where tenant_id=? and session_id=(select recount_session_id from packing_order"
                    + " where tenant_id=? and id=?)",
                tenant,
                tenant,
                orderId)
            .get("q", BigDecimal.class);
    BigDecimal packed =
        sql.fetchOne(
                "select coalesce(sum(pi.quantity),0) as q from parcel_item pi"
                    + " join parcel p on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                    + " where p.tenant_id=? and p.packing_order_id=?",
                tenant,
                orderId)
            .get("q", BigDecimal.class);

    if (parcelCount == 0) blocking.add("还没有建任何包裹");
    if (empty > 0)
      blocking.add("有 " + empty + " 个包裹一件明细都没有 —— 蓝图要求「包裹必须含明细项」，" + "只有重量和运单号无法追溯与对账");
    if (unmeasured > 0) blocking.add("有 " + unmeasured + " 个包裹没称重或没量尺寸 —— 实测重量体积是本模块的产出");
    if (packed.compareTo(baseline) != 0)
      blocking.add(
          "实装 " + plain(packed) + " 件与复核基线 " + plain(baseline) + " 件不相等（少装或多装）—— 复核核过的货必须整箱装出来");
    if (packed.compareTo(baseline) < 0)
      warnings.add("短装 " + plain(baseline.subtract(packed)) + " 件：确认要判失败退回复核，还是补装后再完成");

    return new PackingCompletionReport(
        blocking.isEmpty(),
        List.copyOf(blocking),
        List.copyOf(warnings),
        parcelCount,
        unmeasured,
        empty,
        intOf(
            tenant,
            "select count(*) as c from recount_line where tenant_id=? and session_id="
                + "(select recount_session_id from packing_order where tenant_id=? and id=?)",
            tenant,
            tenant,
            orderId),
        plain(baseline),
        plain(packed),
        plain(baseline.subtract(packed)));
  }

  // ================================================================ 列表 / 详情

  /** 打包单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  public PackingPage page(
      UUID warehouseId,
      UUID outboundOrderId,
      UUID recountSessionId,
      String status,
      UUID packerUserId,
      String keyword,
      String sort,
      int page,
      int size) {
    UUID tenant = currentTenant();
    if (!SORTS.contains(text(sort).isEmpty() ? "STARTED_AT" : sort.trim()))
      throw bad("sort 取值不合法，允许：" + String.join(" / ", SORTS));
    if (text(status).length() > 0 && !STATUSES.contains(status.trim()))
      throw bad("status 取值不合法，允许：" + String.join(" / ", STATUSES));

    var where = new StringBuilder(" where o.tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and o.warehouse_id=?");
      args.add(warehouseId);
    }
    if (outboundOrderId != null) {
      where.append(" and o.outbound_order_id=?");
      args.add(outboundOrderId);
    }
    if (recountSessionId != null) {
      where.append(" and o.recount_session_id=?");
      args.add(recountSessionId);
    }
    if (text(status).length() > 0) {
      where.append(" and o.status=?");
      args.add(status.trim());
    }
    if (packerUserId != null) {
      where.append(" and o.packer_user_id=?");
      args.add(packerUserId);
    }
    if (text(keyword).length() > 0) {
      where.append(
          " and (o.packing_code ilike ? or ob.order_code ilike ?" + " or rc.session_code ilike ?)");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    String join =
        " from packing_order o"
            + " join outbound_order ob on ob.tenant_id=o.tenant_id and ob.id=o.outbound_order_id"
            + " join recount_session rc on rc.tenant_id=o.tenant_id and rc.id=o.recount_session_id"
            + where;

    long total = count("select count(*) as c" + join, args);
    String orderBy =
        switch (text(sort).isEmpty() ? "STARTED_AT" : sort.trim()) {
          case "PACKING_CODE" -> " order by o.packing_code desc, o.id desc";
          case "BASELINE_QUANTITY" -> " order by o.baseline_quantity desc, o.id desc";
          case "PACKED_QUANTITY" -> " order by o.packed_quantity desc, o.id desc";
          default -> " order by o.started_at desc, o.id desc";
        };
    var rows =
        sql.fetch(
            "select o.*, ob.order_code, ob.status as outbound_status, rc.session_code"
                + join
                + orderBy
                + " limit ? offset ?",
            all(args, pageSize(size), offset(page, size)));
    return new PackingPage(
        rows.stream().map(this::orderRow).toList(),
        page,
        pageSize(size),
        total,
        summary(tenant, warehouseId));
  }

  /** 打包作业台汇总。不按筛选条件收窄 —— 与 8.3.2.1 / 8.3.3.1 同一纪律。 */
  private PackingSummary summary(UUID tenant, UUID warehouseId) {
    var where = new StringBuilder(" where tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and warehouse_id=?");
      args.add(warehouseId);
    }
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='OPEN') as open_orders,"
                + " count(*) filter (where status='PACKED') as packed_orders,"
                + " count(*) filter (where status='FAILED') as failed_orders,"
                + " count(*) filter (where status='CANCELLED') as cancelled_orders,"
                + " coalesce(sum(parcel_count),0) as parcels,"
                + " coalesce(sum(packed_quantity),0) as packed_qty,"
                + " coalesce(sum(baseline_quantity),0) as baseline_qty,"
                + " coalesce(sum(total_gross_weight_g),0) as gross,"
                + " coalesce(sum(total_volume_cbm),0) as vol"
                + " from packing_order"
                + where,
            args.toArray());
    long unmeasured =
        sql.fetchOne(
                "select count(*) as c from parcel p join packing_order o"
                    + " on o.tenant_id=p.tenant_id and o.id=p.packing_order_id"
                    + " where p.tenant_id=? and p.packing_order_id in"
                    + " (select id from packing_order o2 where o2.tenant_id=?"
                    + (warehouseId == null ? "" : " and o2.warehouse_id=?")
                    + ")"
                    + " and (p.measured_gross_weight_g is null or p.volume_cbm is null)",
                warehouseId == null
                    ? new Object[] {tenant, tenant}
                    : new Object[] {tenant, tenant, warehouseId})
            .get("c", Long.class);
    return new PackingSummary(
        r.get("total", Long.class),
        r.get("open_orders", Long.class),
        r.get("packed_orders", Long.class),
        r.get("failed_orders", Long.class),
        r.get("cancelled_orders", Long.class),
        r.get("parcels", Long.class),
        unmeasured,
        plain(r.get("baseline_qty", BigDecimal.class)),
        plain(r.get("packed_qty", BigDecimal.class)),
        plain(r.get("gross", BigDecimal.class)),
        plain(r.get("vol", BigDecimal.class)));
  }

  /** 详情：一次调用返回整屏 = 单头 + 包裹 + 明细 + 基线对齐 + 时间线 + 边界声明。 */
  public PackingDetail detail(String orderId) {
    UUID tenant = currentTenant();
    UUID id = uuid(orderId);
    Record order =
        one(
            tenant,
            "select o.*, ob.order_code, ob.status as outbound_status, rc.session_code"
                + " from packing_order o"
                + " join outbound_order ob on ob.tenant_id=o.tenant_id and ob.id=o.outbound_order_id"
                + " join recount_session rc on rc.tenant_id=o.tenant_id and rc.id=o.recount_session_id"
                + " where o.tenant_id=? and o.id=?",
            id);

    var parcels =
        sql
            .fetch(
                "select * from parcel where tenant_id=? and packing_order_id=?"
                    + " order by parcel_no, id",
                tenant,
                id)
            .stream()
            .map(this::parcelRow)
            .toList();
    var items =
        sql
            .fetch(
                "select pi.* from parcel_item pi join parcel p"
                    + " on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                    + " where p.tenant_id=? and p.packing_order_id=? order by p.parcel_no, pi.item_no, pi.id",
                tenant,
                id)
            .stream()
            .map(this::itemRow)
            .toList();
    var baseline = baselineRows(tenant, order.get("recount_session_id", UUID.class), id);
    var timeline =
        sql
            .fetch(
                "select * from packing_event where tenant_id=? and packing_order_id=?"
                    + " order by occurred_at, id",
                tenant,
                id)
            .stream()
            .map(this::eventRow)
            .toList();

    return new PackingDetail(
        orderRow(order),
        parcels,
        items,
        baseline,
        timeline,
        capabilities().unavailable(),
        completion(tenant, id));
  }

  /** 复核基线行 + 该行已装件数（现算的差，不存列）。 */
  private List<PackingBaselineRow> baselineRows(UUID tenant, UUID sessionId, UUID orderId) {
    return sql
        .fetch(
            "select rl.id, rl.line_no, rl.sku_id, rl.sku_code, rl.sku_name, rl.expected_quantity,"
                + " rl.requires_serial,"
                + " coalesce((select sum(pi.quantity) from parcel_item pi"
                + "   join parcel p on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                + "   where pi.tenant_id=rl.tenant_id and pi.recount_line_id=rl.id"
                + "     and p.packing_order_id=?),0) as packed_qty,"
                + " (select count(*) from parcel_item pi"
                + "   join parcel p on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                + "   where pi.tenant_id=rl.tenant_id and pi.recount_line_id=rl.id"
                + "     and p.packing_order_id=?) as parcel_count"
                + " from recount_line rl where rl.tenant_id=? and rl.session_id=? order by rl.line_no",
            orderId,
            orderId,
            tenant,
            sessionId)
        .stream()
        .map(
            r -> {
              BigDecimal expected = r.get("expected_quantity", BigDecimal.class);
              BigDecimal packed = r.get("packed_qty", BigDecimal.class);
              return new PackingBaselineRow(
                  r.get("id", UUID.class),
                  r.get("line_no", Integer.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  plain(expected),
                  plain(packed),
                  plain(expected.subtract(packed)),
                  Boolean.TRUE.equals(r.get("requires_serial", Boolean.class)),
                  packed.compareTo(expected) == 0,
                  r.get("parcel_count", Integer.class));
            })
        .toList();
  }

  // ================================================================ 行映射

  private PackingMaterialRow materialRow(Record r) {
    String role = r.get("material_role", String.class);
    String type = r.get("material_type", String.class);
    return new PackingMaterialRow(
        r.get("id", UUID.class),
        r.get("material_code", String.class),
        r.get("name", String.class),
        role,
        materialRoleLabel(role),
        type,
        materialTypeLabel(type),
        r.get("warehouse_id", UUID.class),
        decimalText(r, "outer_length_cm"),
        decimalText(r, "outer_width_cm"),
        decimalText(r, "outer_height_cm"),
        decimalText(r, "tare_weight_g"),
        decimalText(r, "max_gross_weight_g"),
        r.get("status", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("retired_at", OffsetDateTime.class));
  }

  private PackingRow orderRow(Record r) {
    String status = r.get("status", String.class);
    String outboundStatus = r.get("outbound_status", String.class);
    return new PackingRow(
        r.get("id", UUID.class),
        r.get("packing_code", String.class),
        r.get("recount_session_id", UUID.class),
        r.get("session_code", String.class),
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        outboundStatus,
        outboundStatusLabel(outboundStatus),
        r.get("warehouse_id", UUID.class),
        warehouseCode(tenantOf(r), r.get("warehouse_id", UUID.class)),
        status,
        packingStatusLabel(status),
        r.get("baseline_line_count", Integer.class),
        decimalText(r, "baseline_quantity"),
        r.get("packed_line_count", Integer.class),
        decimalText(r, "packed_quantity"),
        r.get("parcel_count", Integer.class),
        r.get("parcel_item_count", Integer.class),
        r.get("measured_parcel_count", Integer.class),
        decimalText(r, "total_gross_weight_g"),
        decimalText(r, "total_volume_cbm"),
        r.get("packer_user_id", UUID.class),
        r.get("packing_basis", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  private ParcelRow parcelRow(Record r) {
    UUID tenant = r.get("tenant_id", UUID.class);
    Record material =
        sql.fetchOne(
            "select * from packing_material where tenant_id=? and id=?",
            tenant,
            r.get("material_id", UUID.class));
    UUID fillerId = r.get("filler_material_id", UUID.class);
    Record filler =
        fillerId == null
            ? null
            : sql.fetchOne(
                "select * from packing_material where tenant_id=? and id=?", tenant, fillerId);
    boolean measured = r.get("measured_gross_weight_g") != null && r.get("volume_cbm") != null;
    return new ParcelRow(
        r.get("id", UUID.class),
        r.get("packing_order_id", UUID.class),
        r.get("parcel_no", Integer.class),
        r.get("parcel_code", String.class),
        r.get("material_id", UUID.class),
        material == null ? null : material.get("material_code", String.class),
        material == null ? null : material.get("name", String.class),
        material == null ? null : materialRoleLabel(material.get("material_role", String.class)),
        fillerId,
        filler == null ? null : filler.get("material_code", String.class),
        filler == null ? null : filler.get("name", String.class),
        decimalText(r, "outer_length_cm"),
        decimalText(r, "outer_width_cm"),
        decimalText(r, "outer_height_cm"),
        decimalText(r, "measured_gross_weight_g"),
        decimalText(r, "tare_weight_g"),
        decimalText(r, "net_content_weight_g"),
        decimalText(r, "volume_cbm"),
        measured,
        r.get("measured_at", OffsetDateTime.class),
        r.get("label_print_job_id", UUID.class),
        r.get("label_print_count", Integer.class),
        r.get("label_printed_at", OffsetDateTime.class),
        r.get("item_count", Integer.class),
        decimalText(r, "total_quantity"),
        r.get("created_at", OffsetDateTime.class));
  }

  private ParcelItemRow itemRow(Record r) {
    String source = r.get("declaration_source", String.class);
    UUID serialId = r.get("serial_id", UUID.class);
    String serialNumber = r.get("serial_number", String.class);
    if (serialNumber == null || serialNumber.isBlank()) {
      // 序列号原文在 inventory_serial 上（与 8.3.3.1 同一纪律：不在明细上抄一份）。
      Record s =
          serialId == null
              ? null
              : sql.fetchOne(
                  "select serial_number from inventory_serial where tenant_id=? and id=?",
                  r.get("tenant_id", UUID.class),
                  serialId);
      serialNumber = s == null ? null : s.get("serial_number", String.class);
    }
    return new ParcelItemRow(
        r.get("id", UUID.class),
        r.get("parcel_id", UUID.class),
        r.get("item_no", Integer.class),
        r.get("recount_line_id", UUID.class),
        r.get("outbound_line_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        decimalText(r, "quantity"),
        serialId,
        serialNumber,
        source,
        declarationLabel(source),
        r.get("hs_code", String.class),
        r.get("declared_item_name", String.class),
        r.get("declared_origin_country_code", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private PackingEventRow eventRow(Record r) {
    String type = r.get("event_type", String.class);
    return new PackingEventRow(
        r.get("id", UUID.class),
        type,
        eventLabel(type),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("parcel_id", UUID.class),
        r.get("actor_user_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  private PackingCreated created(UUID tenant, UUID id) {
    Record r =
        one(
            tenant,
            "select o.*, ob.order_code, ob.status as outbound_status, rc.session_code"
                + " from packing_order o"
                + " join outbound_order ob on ob.tenant_id=o.tenant_id and ob.id=o.outbound_order_id"
                + " join recount_session rc on rc.tenant_id=o.tenant_id and rc.id=o.recount_session_id"
                + " where o.tenant_id=? and o.id=?",
            id);
    String status = r.get("status", String.class);
    return new PackingCreated(
        r.get("id", UUID.class),
        r.get("packing_code", String.class),
        r.get("recount_session_id", UUID.class),
        r.get("session_code", String.class),
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("warehouse_id", UUID.class),
        status,
        packingStatusLabel(status),
        r.get("baseline_line_count", Integer.class),
        decimalText(r, "baseline_quantity"),
        r.get("packed_line_count", Integer.class),
        decimalText(r, "packed_quantity"),
        r.get("parcel_count", Integer.class),
        r.get("parcel_item_count", Integer.class),
        r.get("measured_parcel_count", Integer.class),
        decimalText(r, "total_gross_weight_g"),
        decimalText(r, "total_tare_weight_g"),
        decimalText(r, "total_volume_cbm"),
        r.get("packing_basis", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  // ================================================================ 内部动作

  private Record orderRecord(UUID tenant, String raw) {
    return one(tenant, "select * from packing_order where tenant_id=? and id=?", uuid(raw));
  }

  private Record parcelRecord(UUID tenant, UUID orderId, String raw) {
    return one(
        tenant,
        "select * from parcel where tenant_id=? and packing_order_id=? and id=?",
        orderId,
        uuid(raw));
  }

  private void requireOpen(Record order, String what) {
    if (!"OPEN".equals(order.get("status", String.class)))
      throw conflict(
          "打包单 "
              + order.get("packing_code", String.class)
              + " 当前是 "
              + packingStatusLabel(order.get("status", String.class))
              + "，不能再"
              + what);
  }

  private void requireMaterial(UUID tenant, UUID materialId, String role) {
    Record m =
        sql.fetchOne(
            "select * from packing_material where tenant_id=? and id=?", tenant, materialId);
    if (m == null) throw notFound("包材/填充物不存在");
    if (!"ACTIVE".equals(m.get("status", String.class)))
      throw conflict("物料 " + m.get("material_code", String.class) + " 已停用，不能用于新包裹");
    if (!role.equals(m.get("material_role", String.class)))
      throw bad(
          role.equals("PACKAGING")
              ? "包裹必须用「包材」角色的物料 —— "
                  + m.get("material_code", String.class)
                  + " 是「填充物」角色，填充物不构成外形也不计入自重"
              : "填充物必须是「填充物」角色的物料");
  }

  private void requireWarehouse(UUID tenant, UUID warehouseId) {
    if (!exists(tenant, "select 1 from warehouse where tenant_id=? and id=?", warehouseId))
      throw notFound("仓库不存在");
  }

  private void event(
      UUID tenant,
      UUID orderId,
      String type,
      String from,
      String to,
      UUID parcelId,
      UUID actorId,
      OffsetDateTime at,
      String detail) {
    sql.execute(
        "insert into packing_event(id,tenant_id,packing_order_id,event_type,from_status,to_status,"
            + "parcel_id,actor_user_id,occurred_at,detail) values(?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        tenant,
        orderId,
        type,
        from,
        to,
        parcelId,
        actorId,
        at,
        detail);
  }

  private String packingCode() {
    return "PK-"
        + OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString().replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private String parcelCode(int parcelNo) {
    return "BX-" + parcelNo + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  private String warehouseCode(UUID tenant, UUID warehouseId) {
    if (warehouseId == null) return null;
    Record r =
        sql.fetchOne("select code from warehouse where tenant_id=? and id=?", tenant, warehouseId);
    return r == null ? null : r.get("code", String.class);
  }

  // ================================================================ 查询helper

  private UUID tenantOf(Record r) {
    return r.get("tenant_id", UUID.class);
  }

  /** 计数：args 里已经带好 tenant（调用方的 where 子句自己拼的第一个占位符就是它）。 */
  private long count(String query, List<Object> args) {
    return ((Number) sql.fetchOne(query, args.toArray()).get(0)).longValue();
  }

  /** 计数并窄化成 int：count(*) 在 PG 是 bigint，直接 get(Integer.class) 会抛。 */
  private int intOf(UUID tenant, String query, Object... args) {
    return ((Number) sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args)).get(0))
        .intValue();
  }

  private Record one(UUID tenant, String query, Object... args) {
    Record r = sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args));
    if (r == null) throw notFound("对象不存在");
    return r;
  }

  /**
   * 存在性判断。
   *
   * <p>刻意用 {@code fetch(...).isEmpty()} 而不是 {@code fetchOne(...)}：jOOQ 的 {@code fetchOne}
   * 在结果多于一行时会抛 TooManyRows，而「这个基线行在不在」 本来就允许有多行（一条复核单有两行基线是常态）。
   */
  private boolean exists(UUID tenant, String query, Object... args) {
    return !sql.fetch(query, all(new ArrayList<>(List.of(tenant)), args)).isEmpty();
  }

  private UUID uuid(String raw) {
    try {
      return UUID.fromString(raw == null ? "" : raw.trim());
    } catch (IllegalArgumentException e) {
      throw bad("id 格式不合法：" + raw);
    }
  }

  private static Object[] all(List<Object> args, Object... extra) {
    var out = new ArrayList<Object>(args);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private int pageSize(int size) {
    if (size <= 0) throw bad("size 必须大于 0");
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private int offset(int page, int size) {
    return Math.max(page, 0) * pageSize(size);
  }

  private static void requireOneOf(String value, List<String> allowed, String field) {
    if (text(value).isEmpty()) throw bad(field + " 不能为空");
    if (!allowed.contains(value.trim()))
      throw bad(field + " 取值不合法，允许：" + String.join(" / ", allowed));
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant', true)").get(0, String.class));
  }

  // ================================================================ 格式化

  /** 十进制串（不经 double）：NUMERIC 出参一律用 plain 文本回给前端。 */
  private static BigDecimal decimal(String raw, String field, BigDecimal fallback) {
    if (raw == null || raw.isBlank()) {
      if (fallback != null) return fallback;
      throw bad(field + " 不能为空");
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      throw bad(field + " 不是合法数字：" + raw);
    }
  }

  private static String decimalText(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? null : plain(v);
  }

  private static String plain(BigDecimal v) {
    return v == null ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String trim(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  /**
   * 时刻解析：<b>显式带偏移量</b>，不走 {@code Timestamp.valueOf(LocalDateTime)} —— 那条路会把东八区的时间静默偏 −28800 秒（上秤时刻比
   * DB 时刻靠前几个小时时， 记录会「早到凌晨」，且没有任何报错）。与 8.3.3.1 同一纪律。
   */
  private static OffsetDateTime instant(String raw, String what) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now();
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      try {
        return java.time.LocalDateTime.parse(raw.trim())
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime();
      } catch (DateTimeParseException e2) {
        throw bad(what + " 不是合法时刻：" + raw);
      }
    }
  }

  private static String packingStatusLabel(String status) {
    for (PackingStatus s : PackingStatus.values()) if (s.name().equals(status)) return s.label();
    return status;
  }

  private static String materialRoleLabel(String role) {
    for (MaterialRole s : MaterialRole.values()) if (s.name().equals(role)) return s.label();
    return role;
  }

  private static String materialTypeLabel(String type) {
    for (MaterialType s : MaterialType.values()) if (s.name().equals(type)) return s.label();
    return type;
  }

  private static String eventLabel(String type) {
    for (PackingEventType s : PackingEventType.values())
      if (s.name().equals(type)) return s.label();
    return type;
  }

  private static String declarationLabel(String source) {
    for (DeclarationSource s : DeclarationSource.values())
      if (s.name().equals(source)) return s.label();
    return source;
  }

  /** 出库单状态的中文标签：复用 8.3.1.1 的既有词表，不在本类再抄一份七态。 */
  private static String outboundStatusLabel(String status) {
    for (OutboundOrderModels.OutboundOrderStatus s :
        OutboundOrderModels.OutboundOrderStatus.values())
      if (s.name().equals(status)) return s.label();
    return status;
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
}
