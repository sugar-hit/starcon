package com.starcon.warehouse;

import static com.starcon.warehouse.ParcelDetailModels.*;

import com.starcon.warehouse.OutboundOrderModels.OutboundSourceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.2.2.1 包裹详情：把一屏之内该看到的东西聚到一处。
 *
 * <p>蓝图原话：「单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、 面单、轨迹、交运信息。」输出：「包裹详情 + 操作入口。」
 *
 * <p><b>本模块落在 warehouse，不是 channel</b>：蓝图卷 10 写「主责服务 = channel + trade +
 * warehouse」，那三个字说的是<b>谁提供物流能力</b>，不是「单包裹视图落在哪个服务」。 {@code parcel} / {@code parcel_item} 由上游
 * 8.3.3.2 打包与称重（warehouse，V121）拥有， AGENTS.md 明令禁止跨服务直读对方的表 / 跨库 FK / 视图 —— 所以「表所有者的读模型」
 * 只能长在表所有者自己的服务里。轨迹与交运信息在本服务<b>不存在</b>， 由 {@link ParcelDetailUnavailable} 如实标注归属模块，不返回空列表冒充。
 *
 * <p><b>本模块只读不改</b>：除了 V125 自己那张 {@code parcel_item_sales_order_line}（由上游对齐作业写入），本服务对 {@code
 * parcel} / {@code parcel_item} 一个字节都不写 —— 包裹是 8.3.3.2 的凭据。
 *
 * <p><b>蓝图点名的那句要求怎么落地</b>：「parcel_item 与订单行的对应关系是成本归集到行的 基础，缺了就只能算到订单级。」{@code parcel_item} 原本只有
 * {@code outbound_line_id}（指向<b>出库单行</b>，warehouse 自己的计划快照）；而 12 财务 要把运费摊到的那一行是 <b>trade
 * 的销售订单行</b>。V125 补的就是这一环， 并带 <b>归集分母</b>（本箱分给这一行的件数）与 <b>匹配口径</b>（强/弱关联）—— 分母错一分运费就错一分，所以分母由 V125
 * 的触发器钉死等于明细项实装件数。
 *
 * <p><b>恒等式是本类最要紧的不变量</b>： {@code matchedQuantity + unmatchedQuantity == totalQuantity}。
 * 归集到行还是归到订单级完全取决于有没有这条等式；本类读回后断言它， 对不上宁可 500 也不返回一个自相矛盾的归集结论。
 *
 * <p><b>时间绑定纪律</b>：本类<b>不写</b>任何时间列（只读视图），故无 {@code ?::timestamptz} 的绑定问题；读 timestamptz 列统一走 {@link
 * #moment}，它对驱动 返回的三种类型（OffsetDateTime / Instant / Timestamp）都收口。
 *
 * <p><b>多租户</b>：每条 SQL 都带 {@code tenant_id = ?} 显式条件（RLS 之外的第一道闸）， 租户由 {@code app.current_tenant} 取
 * —— 它由 AccessService 在事务内从 <b>已认证身份的成员关系</b>设置，客户端的 {@code X-Tenant-Id} 头进不来也改不动它。
 */
@Service
public class ParcelDetailService {

  /** numeric(19,6) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  private final DSLContext sql;

  public ParcelDetailService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 详情

  /**
   * 包裹详情。
   *
   * <p>一次调用返回整屏：单头（含实测重量体积）+ 明细项（含销售订单行关联）+ 面单 + 成本归集基线 + 操作入口 + 三段不可得能力。
   *
   * <p>单头查不到时 404 —— <b>跨租户与不存在返回同一个结果</b>，这是有意的： 让 A 租户拿 B 租户的 id 探测存在性，本身就是一个信息泄露面。
   */
  public ParcelDetail detail(UUID parcelId) {
    var head = header(parcelId);
    var items = items(parcelId, head);
    var allocation = costAllocation(items, "SALES".equals(head.outboundSourceType()));
    return new ParcelDetail(
        head, items, label(parcelId), allocation, actions(head), unavailable(head), notes());
  }

  // ------------------------------------------------------------ 单头

  /**
   * 包裹头 + 所属打包单 + 出库单 + 包材 + 仓库。
   *
   * <p>实测三件（尺寸 / 毛重 / 体积）全部原样透传，<b>不派生体积重</b>： 除数（6000/5000/…）是承运商渠道口径，归 10 物流（V121 §1.5 同一条理由）。
   */
  private ParcelDetailHeader header(UUID parcelId) {
    var r =
        sql.fetchOne(
            "select p.id,p.parcel_code,p.parcel_no,p.packing_order_id,"
                + "p.outer_length_cm,p.outer_width_cm,p.outer_height_cm,p.measured_gross_weight_g,"
                + "p.tare_weight_g,p.net_content_weight_g,p.volume_cbm,p.measured_at,p.measured_by,"
                + "p.item_count,p.total_quantity,p.created_at,"
                + "po.packing_code,po.status as packing_status,"
                + "o.id as outbound_id,o.order_code,o.source_type,"
                + "w.id as warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + "m.id as material_id,m.material_code,m.name as material_name,"
                + "f.id as filler_id,f.name as filler_name "
                + "from parcel p "
                + "join packing_order po on po.tenant_id = p.tenant_id and po.id = p.packing_order_id "
                + "join outbound_order o on o.tenant_id = po.tenant_id and o.id = po.outbound_order_id "
                + "join warehouse w on w.tenant_id = o.tenant_id and w.id = o.warehouse_id "
                + "join packing_material m on m.tenant_id = p.tenant_id and m.id = p.material_id "
                + "left join packing_material f on f.tenant_id = p.tenant_id and f.id = p.filler_material_id "
                + "where p.tenant_id = ? and p.id = ?",
            tenant(),
            parcelId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "包裹不存在或不属于当前租户：" + parcelId);
    }

    var source = OutboundSourceType.valueOf(r.get("source_type", String.class));
    boolean measured =
        r.get("measured_gross_weight_g", BigDecimal.class) != null
            && r.get("volume_cbm", BigDecimal.class) != null;

    return new ParcelDetailHeader(
        r.get("id", UUID.class),
        r.get("parcel_code", String.class),
        r.get("parcel_no", Integer.class),
        r.get("packing_order_id", UUID.class),
        r.get("packing_code", String.class),
        nullToEmpty(r.get("packing_status", String.class)),
        r.get("outbound_id", UUID.class),
        r.get("order_code", String.class),
        source.name(),
        source.label(),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("material_id", UUID.class),
        r.get("material_code", String.class),
        r.get("material_name", String.class),
        r.get("filler_id", UUID.class),
        r.get("filler_name", String.class),
        weight(r, "outer_length_cm", 3),
        weight(r, "outer_width_cm", 3),
        weight(r, "outer_height_cm", 3),
        weight(r, "measured_gross_weight_g", 3),
        weight(r, "tare_weight_g", 3),
        weight(r, "net_content_weight_g", 3),
        weight(r, "volume_cbm", 6),
        measured,
        moment(r, "measured_at"),
        r.get("measured_by", UUID.class),
        r.get("item_count", Integer.class),
        scale(r.get("total_quantity", BigDecimal.class)),
        moment(r, "created_at"),
        "**本表不计算体积重**：除数（6000 / 5000 / …）是承运商渠道口径，归 10 物流。"
            + "这里给的是实测尺寸与实测体积，由 10 物流按自己渠道的除数去算 —— "
            + "在本服务先乘一个除数就是替渠道定价。");
  }

  // ------------------------------------------------------------ 明细项

  /**
   * 包裹内明细项（含销售订单行关联）。
   *
   * <p>按 item_no 升序 —— 打包与对账都按它排序，人心里的顺序也是这个。
   *
   * <p>每条明细项带它自己的销售订单行列表；非 SALES 来源的出库单在 trade 侧没有销售订单行， 那时是<b>空数组 + 明确说明</b>，而不是 null、更不是编一个 uuid
   * 填上去。
   */
  private List<ParcelDetailItem> items(UUID parcelId, ParcelDetailHeader header) {
    boolean salesSource = "SALES".equals(header.outboundSourceType());
    var links = linksByItem(parcelId);

    return sql
        .fetch(
            "select pi.id,pi.item_no,pi.sku_id,pi.sku_code,pi.sku_name,pi.quantity,"
                + "pi.serial_id,pi.serial_number,pi.recount_line_id,pi.outbound_line_id,"
                + "pi.declaration_source,pi.hs_code,pi.declared_item_name,"
                + "pi.declared_origin_country_code,ol.line_no as outbound_line_no "
                + "from parcel_item pi "
                + "left join outbound_order_line ol on ol.tenant_id = pi.tenant_id "
                + "  and ol.id = pi.outbound_line_id "
                + "where pi.tenant_id = ? and pi.parcel_id = ? "
                + "order by pi.item_no",
            tenant(),
            parcelId)
        .stream()
        .map(
            r -> {
              UUID itemId = r.get("id", UUID.class);
              var mine = links.getOrDefault(itemId, List.of());
              return new ParcelDetailItem(
                  itemId,
                  r.get("item_no", Integer.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  scale(r.get("quantity", BigDecimal.class)),
                  r.get("serial_id", UUID.class),
                  r.get("serial_number", String.class),
                  r.get("recount_line_id", UUID.class),
                  r.get("outbound_line_id", UUID.class),
                  r.get("outbound_line_no", Integer.class),
                  nullToEmpty(r.get("declaration_source", String.class)),
                  nullToEmpty(r.get("hs_code", String.class)),
                  nullToEmpty(r.get("declared_item_name", String.class)),
                  nullToEmpty(r.get("declared_origin_country_code", String.class)),
                  mine,
                  orderLineNote(mine, salesSource));
            })
        .toList();
  }

  /** V125 新表按包裹一次取回，按明细项分组 —— 避免逐行查库（N+1）。 */
  private java.util.Map<UUID, List<ParcelItemSalesOrderLine>> linksByItem(UUID parcelId) {
    var out = new java.util.LinkedHashMap<UUID, List<ParcelItemSalesOrderLine>>();
    for (var r :
        sql.fetch(
            "select id,parcel_item_id,sales_order_id,sales_order_line_id,sales_order_no,"
                + "sales_order_line_no,sku_id,sku_code,allocated_quantity,sales_line_quantity,"
                + "match_basis,created_at "
                + "from parcel_item_sales_order_line "
                + "where tenant_id = ? and parcel_id = ? "
                + "order by sales_order_no, sales_order_line_no",
            tenant(),
            parcelId)) {
      var basis = r.get("match_basis", String.class);
      boolean strong = "SKU_UNIQUE_ALIGNED".equals(basis);
      var link =
          new ParcelItemSalesOrderLine(
              r.get("id", UUID.class),
              r.get("sales_order_id", UUID.class),
              r.get("sales_order_line_id", UUID.class),
              r.get("sales_order_no", String.class),
              r.get("sales_order_line_no", Integer.class),
              r.get("sku_id", UUID.class),
              r.get("sku_code", String.class),
              scale(r.get("allocated_quantity", BigDecimal.class)),
              scale(r.get("sales_line_quantity", BigDecimal.class)),
              basis,
              strong ? "强关联（SKU 唯一键对齐）" : "弱关联（编码一致，uuid 不同）",
              strong,
              moment(r, "created_at"));
      out.computeIfAbsent(r.get("parcel_item_id", UUID.class), k -> new ArrayList<>()).add(link);
    }
    return out;
  }

  /**
   * 订单行缺失时的说明。
   *
   * <p>非 SALES 来源是一句实话（调拨出库本来就没有销售订单行）； SALES 来源却没关联上则是<b>真缺口</b>，必须说出来 —— 那正是蓝图说的
   * 「缺了就只能算到订单级」的成因，界面上不能装作没事。
   */
  private static String orderLineNote(List<ParcelItemSalesOrderLine> mine, boolean salesSource) {
    if (!mine.isEmpty()) {
      return "已关联到 " + mine.size() + " 条销售订单行，可按行归集运费。";
    }
    if (!salesSource) {
      return "本包裹所属出库单**不是销售来源**，trade 侧没有对应的销售订单行 —— " + "调拨/采购退货/加工出库本来就不该有，也就不该硬造一个关联。";
    }
    return "**这一条还没有关联到销售订单行。**蓝图要求「parcel_item 与订单行的对应关系" + "是成本归集到行的基础」：没有它，这一箱的运费只能归到订单级，算不到行。";
  }

  // ------------------------------------------------------------ 面单

  /**
   * 面单（8.3.4.2 已交付的 print_job / print_target，同服务真实表）。
   *
   * <p>经 parcel.label_print_job_id 关联；<b>刻意不自己渲染 ZPL</b>（V121 §1.2 同一条理由： 自造一份渲染内容会出现两份「印的是什么」）。
   *
   * <p>没记过打印时 available=false —— 那是事实（这一箱还没印），不是能力缺失， 所以 note 说清「去 8.3.4.2 的打包作业页记打印」而不是报「不可用」。
   */
  private ParcelDetailLabel label(UUID parcelId) {
    var r =
        sql.fetchOne(
            "select p.label_print_job_id,p.label_print_count,p.label_printed_at,"
                + "j.job_no,j.status as job_status,j.copies,"
                + "pt.object_type,pt.external_ref,pt.waybill_no,pt.carrier_code,"
                + "pt.ship_to_name,pt.ship_to_address,pt.platform_code,pt.print_count,"
                + "pt.first_printed_at,pt.last_printed_at "
                + "from parcel p "
                + "left join print_job j on j.tenant_id = p.tenant_id and j.id = p.label_print_job_id "
                + "left join print_job_item pi on pi.tenant_id = j.tenant_id and pi.job_id = j.id "
                + "  and pi.object_type = 'SHIPPING_LABEL' "
                + "left join print_target pt on pt.tenant_id = pi.tenant_id and pi.target_id = pt.id "
                + "where p.tenant_id = ? and p.id = ? "
                + "order by pi.line_no limit 1",
            tenant(),
            parcelId);

    Integer count = r == null ? 0 : r.get("label_print_count", Integer.class);
    UUID jobId = r == null ? null : r.get("label_print_job_id", UUID.class);
    if (jobId == null) {
      return new ParcelDetailLabel(
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          count == null ? 0 : count,
          null,
          "这一箱**还没有记过面单打印**。面单内容与打印次数的权威是 8.3.4.2 已交付的 "
              + "print_job / print_target；本模块只读它，不自建渲染引擎 —— "
              + "在这里造第二份「印的是什么」只会在对账时出现两个答案。");
    }
    return new ParcelDetailLabel(
        true,
        jobId,
        r.get("job_no", String.class),
        nullToEmpty(r.get("job_status", String.class)),
        printStatusLabel(r.get("job_status", String.class)),
        r.get("copies", Integer.class),
        r.get("object_type", String.class),
        r.get("external_ref", String.class),
        r.get("waybill_no", String.class),
        r.get("carrier_code", String.class),
        r.get("ship_to_name", String.class),
        r.get("ship_to_address", String.class),
        r.get("platform_code", String.class),
        r.get("print_count", Integer.class),
        moment(r, "first_printed_at"),
        moment(r, "last_printed_at"),
        count == null ? 0 : count,
        moment(r, "label_printed_at"),
        "面单来自 8.3.4.2 的打印任务与打印目标，本模块只读不写。" + "运单号与承运渠道的权威在 10 物流（尚未交付），这里显示的是打印时冻结的那一份。");
  }

  private static String printStatusLabel(String status) {
    if (status == null) return null;
    return switch (status) {
      case "DRAFT" -> "草稿";
      case "QUEUED" -> "已排队";
      case "PRINTING" -> "打印中";
      case "COMPLETED" -> "已完成";
      case "FAILED" -> "打印失败";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  // ------------------------------------------------------------ 成本归集

  /**
   * 成本归集基线（下游 12 财务）。
   *
   * <p>蓝图那句「缺了就只能算到订单级」在这里变成一个可判定的布尔： <b>所有明细项都关联上了销售订单行 → 归集到行；有一条没关联上 → 只能归到订单级</b>，
   * 并把没关联上的那几条逐条列出来（能追到具体是哪几件货），而不是静默按订单摊。
   *
   * <p>{@code matchedQuantity + unmatchedQuantity} 必须等于 {@code totalQuantity}：
   * 这个恒等式是本模块最要紧的不变量，对不上宁可抛也不返回一个自相矛盾的归集结论。
   */
  private ParcelCostAllocation costAllocation(List<ParcelDetailItem> items, boolean salesSource) {
    var unmatched = new ArrayList<ParcelUnmatchedItem>();
    var orders = new LinkedHashSet<String>();
    var orderLines = new LinkedHashSet<UUID>();

    BigDecimal matched = ZERO;
    BigDecimal unmatchedQty = ZERO;
    int linkedItems = 0;

    for (var item : items) {
      if (item.salesOrderLines().isEmpty()) {
        unmatchedQty = unmatchedQty.add(item.quantity());
        unmatched.add(
            new ParcelUnmatchedItem(
                item.parcelItemId(),
                item.itemNo(),
                item.skuCode(),
                item.skuName(),
                item.quantity(),
                item.orderLineNote(),
                // 非 SALES 来源不是缺口（本来就没有销售订单行），归集不到行的成因
                // 归上游哪个模块也就无从谈起，所以 ownerModule 留空；SALES 来源没关联上
                // 才是真缺口，指向负责对齐的订单侧。
                salesSource ? "9.1.1.2 销售订单" : null));
        continue;
      }
      linkedItems++;
      for (var link : item.salesOrderLines()) {
        matched = matched.add(link.allocatedQuantity());
        orders.add(link.salesOrderNo());
        orderLines.add(link.salesOrderLineId());
      }
    }

    BigDecimal total = items.stream().map(ParcelDetailItem::quantity).reduce(ZERO, BigDecimal::add);

    // 恒等式断言：宁可 500 也不返回一个自己都说不圆的归集结论。
    if (matched.add(unmatchedQty).compareTo(total) != 0) {
      throw new IllegalStateException(
          "成本归集件数对不上：已关联 " + matched + " + 未关联 " + unmatchedQty + " ≠ 合计 " + total);
    }

    boolean toLine = unmatched.isEmpty() && !items.isEmpty();
    return new ParcelCostAllocation(
        toLine,
        total,
        matched,
        unmatchedQty,
        items.size(),
        linkedItems,
        unmatched.size(),
        orders.size(),
        orderLines.size(),
        toLine ? "LINE" : "ORDER",
        toLine
            ? null
            : "有 "
                + unmatched.size()
                + " 条明细项没有销售订单行关联，运费**只能归到订单级**。"
                + "这是蓝图点名的失败模式（「缺了就只能算到订单级」），已逐条列出未关联的明细项。",
        unmatched,
        "**归集口径**：分母是本箱分给该销售订单行的件数（V125 的触发器钉死等于明细项"
            + "实装件数）；重量与体积取本包裹的实测值。三个口径由 12 财务各自加权，"
            + "本模块只提供依据，不产生任何金额 —— 运费金额归 12 财务。");
  }

  // ------------------------------------------------------------ 操作入口

  /**
   * 详情页操作入口。
   *
   * <p>本模块是<b>只读视图</b>，所以入口全部指向真正做这件事的作业模块： 详情页给跳转与前置条件，不在这里开写操作。
   */
  private List<ParcelDetailAction> actions(ParcelDetailHeader header) {
    boolean measured = header.measured();
    List<ParcelDetailAction> out = new ArrayList<>();
    out.add(
        new ParcelDetailAction(
            "OPEN_PACKING_ORDER",
            "打开打包作业单",
            "/warehouse/packing-orders",
            "8.3.3.2 打包与称重",
            "无",
            "包裹与明细项是 8.3.3.2 的凭据：要改包装、补明细或重新称重，都在那张作业单上" + "按它的状态机走（本视图不提供任何写入口）。"));
    out.add(
        new ParcelDetailAction(
            "REMEASURE",
            measured ? "重新称重 / 量尺寸" : "称重并量尺寸",
            "/warehouse/packing-orders",
            "8.3.3.2 打包与称重",
            "打包单状态必须是「进行中」（OPEN）",
            measured
                ? "已实测（毛重 / 净重 / 体积齐全）。重称仍要走 8.3.3.2 的实测入口。"
                : "**尚未称重**：10 物流按渠道除数算费要的就是实测重量与体积，" + "未量的箱子进不了运费计算。"));
    out.add(
        new ParcelDetailAction(
            "QUERY_TRACKING",
            "查询物流轨迹",
            "/logistics/tracking",
            "10.3.1.1 轨迹查询",
            "运单号已生成（归 10.2.1.x）",
            "轨迹归 10 物流（channel），**本服务没有轨迹表**（AGENTS.md 禁止跨服务直读）。"
                + "等 10.3.1.1 交付后由它承接，本详情页届时给出真实入口。"));
    out.add(
        new ParcelDetailAction(
            "SHIP_HANDOVER",
            "发货交运",
            "/warehouse/outbound",
            "8.3.4.1 发货交接",
            "打包单已判完成（出库单待发货）",
            "交运信息归 8.3.4.1，**本服务没有交运实体**。打包完成只是把包裹交给它" + "（V121 的同一纪律：打包完成 ≠ 已发货）。"));
    out.add(
        new ParcelDetailAction(
            "ALLOCATE_FREIGHT",
            "运费成本归集",
            null,
            "12 财务",
            "本包裹每一条明细项都已关联销售订单行",
            "归集依据就是本响应的 costAllocation 段：分母（件数）、实测重量与实测体积。" + "金额由 12 财务算，本模块不产生金额。"));
    return out;
  }

  // ------------------------------------------------------------ 不可得能力

  /**
   * 三段本服务<b>真读不到</b>的数据，逐段说明归谁。
   *
   * <p>轨迹与交运信息刻意返回 {@code available=false} 而不是空列表 —— 空列表在界面上与「这一票确实没有轨迹」一模一样。
   */
  private List<ParcelDetailUnavailable> unavailable(ParcelDetailHeader header) {
    return List.of(
        new ParcelDetailUnavailable(
            "物流轨迹",
            false,
            "10.3.1.1",
            "/logistics/tracking",
            "**轨迹归 10 物流（channel 服务），尚未交付。**本服务没有轨迹表，"
                + "而 AGENTS.md 明令禁止跨服务直读对方的表 —— 所以这里不返回任何轨迹节点，"
                + "也不返回空列表冒充「这一票没有轨迹」。等 10.3.1.1 交付后，"
                + "由它按运单号承接。"),
        new ParcelDetailUnavailable(
            "交运信息",
            false,
            "8.3.4.1",
            "/warehouse/outbound",
            "**交运归 8.3.4.1 发货交接，尚未交付。**本服务只有打包完成这件事"
                + "（把出库单推到「待发货」），交运本身在别的模块。刻意不预告运单号、"
                + "承运渠道与交运时刻 —— 那是替下游编数据。"),
        new ParcelDetailUnavailable(
            "买家地址",
            false,
            "9.1.1.2",
            "/orders",
            "**买家地址归 9 订单履约（trade 侧订单冻结的地址快照）。**面单上的寄件地址"
                + "（上面的 label.shipToAddress）是打印时冻结的那一份，属于打印凭据，"
                + "不是订单侧的权威地址；两者改期后可能不同，所以这里不冒充订单地址。"),
        new ParcelDetailUnavailable(
            "运费与体积重",
            false,
            "12 财务 / 10 物流",
            null,
            "**运费金额归 12 财务，体积重归 10 物流。**本服务给的是实测尺寸与实测体积"
                + "（以及本箱的归集分母），除数与金额都不在这里算 —— "
                + "在这里乘一个除数就是替渠道定价。"),
        new ParcelDetailUnavailable(
            "异常件处理", false, "10.3.1.2", null, "**异常件归 10.3.1.2，尚未交付。**本模块只呈现事实，不判定异常。"),
        new ParcelDetailUnavailable(
            "申报信息",
            false,
            "3.7.2.1",
            null,
            "**3.7.2 的 SKU 物理属性属 catalog 服务，跨服务读不到。**V121 已把本服务"
                + "真读得到的 customs_declaration_line 快照冻结到每条明细项上"
                + "（hsCode / declaredItemName / declaredOriginCountryCode）；"
                + "取不到时如实记 NOT_FOUND，**不猜一个 HS 编码填上去**。"));
  }

  // ------------------------------------------------------------ 说明

  private static List<String> notes() {
    return List.of(
        "**本模块是只读视图。**包裹与明细项是 8.3.3.2 的凭据，本模块一个字节都不改写；"
            + "它只新增一张 parcel_item_sales_order_line（V125）承载「明细项 → 销售订单行」"
            + "这条蓝图点名的关联。",
        "**实测口径**：重量一律克、体积一律立方米、尺寸一律厘米，全部 NUMERIC，无浮点。" + "未称重的值是 null 而不是 0 —— 0 会被读成「量出来是 0」。",
        "**体积重不在本服务算**：除数是承运商渠道口径，归 10 物流。",
        "**跨租户与不存在返回同一个 404**：让 A 租户拿 B 租户的 id 探测存在性，" + "本身就是一个信息泄露面。");
  }

  // ============================================================ 能力声明

  /**
   * 详情页能力声明：六段内容的可用性 + 归集口径 + 必须写在界面上的说明。
   *
   * <p>前端据此渲染「未接入」的灰块与提示，而不是把六段都画成空的详情表 —— 空的详情表会被读成「这一票没有明细/没有轨迹」，而真相是「这段能力还没接入」。
   */
  public ParcelDetailCapabilities capabilities() {
    return new ParcelDetailCapabilities(
        unavailable(null),
        new ParcelCostAllocationPolicy(
            "PARCEL_ITEM_TO_SALES_ORDER_LINE",
            true,
            null,
            "归集到行的基础是 V125 的 parcel_item_sales_order_line：每条包裹明细项显式关联"
                + "一条 trade 销售订单行，并带**归集分母**（本箱分给这一行的件数，"
                + "由触发器钉死等于明细项实装件数）与**匹配口径**（强/弱关联）。"
                + "12 财务按件数 / 实测重量 / 实测体积三个口径各自加权；"
                + "只要有一条明细项没关联上，就退回订单级并逐条列名。"),
        List.of("8.3.3.2 打包与称重", "8.3.4.2 面单打印"),
        List.of("10.3.1.1 轨迹查询", "10.3.1.2 异常件", "12 财务（运费成本归集）"),
        "**六段里三段本服务真读得到**（SKU 与数量、重量体积、订单行对应关系 + 面单），"
            + "两段在别的服务（轨迹、交运），一段按渠道口径算（运费与体积重）。"
            + "不可得的一律 available=false + ownerModule，不用空列表或 0 冒充。");
  }

  // ============================================================ 内部

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /** numeric(19,6) 口径：统一 scale，避免同一列里混着 "2.5" 与 "2.500000"。 */
  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, RoundingMode.HALF_UP);
  }

  /**
   * 重量 / 尺寸列的 scale。
   *
   * <p>V121 §3 的口径：重量 numeric(19,3)（克，三位小数 = 毫克级余量）、 外形 numeric(14,3)（厘米）、体积
   * numeric(19,6)（立方米）。这里按列各自的 scale 收口， 不用统一的 6 位 —— 把毫米级读数补成 6 位小数是伪造精度。
   */
  private static BigDecimal weight(Record r, String field, int digits) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? null : v.setScale(digits, RoundingMode.HALF_UP);
  }

  /**
   * 读 timestamptz 列。
   *
   * <p>驱动返回的类型可能是 {@link OffsetDateTime}、{@link java.time.Instant} 或 {@link java.sql.Timestamp}，直接
   * get(field, OffsetDateTime.class) 会在拿到 Instant 时抛 ClassCastException（与 7.1.2.2 / 8.3.1.1 /
   * 8.3.3.2 同一处收口）。
   */
  private static OffsetDateTime moment(Record r, String field) {
    Object value = r.get(field);
    if (value == null) return null;
    if (value instanceof OffsetDateTime offset) return offset;
    if (value instanceof java.time.Instant instant) {
      return instant.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    return OffsetDateTime.parse(value.toString());
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  /** 供集成测试与前端复用：集合去重的小工具（保持 LinkedHashSet 的插入序）。 */
  static List<UUID> distinct(List<ParcelItemSalesOrderLine> links) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (var l : links) ids.add(l.salesOrderLineId());
    return List.copyOf(ids);
  }
}
