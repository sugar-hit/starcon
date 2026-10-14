package com.starcon.trade;

import static com.starcon.trade.DropshipOrderModels.*;

import com.starcon.trade.SupplierOrderGateway.PlacementRequest;
import com.starcon.trade.SupplierOrderGateway.PlacedLine;
import com.starcon.trade.SupplierOrderGateway.Recipient;
import com.starcon.trade.SupplierOrderGateway.SupplierOrderAck;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.6.1.2 代发单与供应商下单。
 *
 * <p><b>六条实现纪律</b>（照抄 9.6.1.1 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>差价只有一处口径</b>：{@code dropshipCost} / {@code spreadAmount} /
 *       {@code marginRate} 是 V122 的三个<b>生成列</b>，服务层读出来如实报，
 *       不另算第二份。两个实现必然漂移，而漂移的后果是账面毛利与财务结算对不上。</li>
 *   <li><b>告警是本模块的硬输出</b>：涨价 / 无货 / 失败 / 差价转负<b>四条独立</b>地落
 *       {@code fulfillment_dropship_alert}。蓝图原话「否则差价转负的订单会静默亏损」
 *       —— 静默亏损的闸门必须开在<b>回执落库这一刻</b>，而不是等到 9.6.1.3 跟踪或
 *       12 财务对账才被发现。</li>
 *   <li><b>涨价比的是冻结价</b>：{@code actualPurchaseAmount > purchaseAmount}，
 *       其中 {@code purchaseAmount} 是<b>下单时</b>从配置冻结下来的采购价。
 *       拿实时配置价去比，改一次配置就会把历史告警冲掉。</li>
 *   <li><b>不伪造供应商响应</b>：{@link SupplierOrderGateway} 未接线时显式失败
 *       （{@link Alibaba1688SupplierAdapter} 恒不返回编造的 supplierOrderNo）。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       cast 不出现在绑定序列末位；全程 {@code OffsetDateTime}。</li>
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。</li>
 * </ol>
 *
 * <p><b>跨服务一律不读表</b>：10 物流、12 财务、3.6.2 报价各在自己的库里，
 * 本模块一条 SQL 都不碰；而 9.1 订单与 9.6.1.1 配置**同属 trade 库**，
 * 因此可以直接 FK 引用（模块边界纪律禁的是跨服务 FK，不是同库引用）。
 */
@Service
public class DropshipOrderService {

  /** 单页最大行数（与 9.6.1.1 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");

  private static final String ORDER_COLUMNS =
      "id,order_no,sales_order_id,sales_order_no,customer_name,currency_code,config_id,"
          + "config_code,supplier_code,fulfillment_path,ownership_mode,counts_against_own_inventory,"
          + "status,dispatch_mode,vendor_code,supplier_order_no,supplier_tracking_no,"
          + "supplier_shipped_at,rejection_reason,sell_amount,purchase_amount,"
          + "supplier_shipping_amount,actual_purchase_amount,actual_supplier_shipping_amount,"
          + "dropship_cost,spread_amount,margin_rate,payment_confirmed_at,"
          + "payment_confirmed_by_membership_id,payment_confirmed_source,payment_confirmed_note,"
          + "note,dispatched_at,accepted_at,created_at,updated_at";

  private static final String LINE_COLUMNS =
      "id,line_no,sku_id,sku_ref,sku_name,spec_snapshot,quantity,sell_unit_price,"
          + "purchase_unit_price,sell_amount,purchase_amount,supplier_sku_ref,note";

  private static final String ALERT_COLUMNS =
      "id,order_id,alert_kind,severity,expected_amount,actual_amount,detail,status,"
          + "acknowledged_at,acknowledged_by_membership_id,resolution_note,occurred_at";

  private static final String EVENT_COLUMNS =
      "id,order_id,event_kind,from_status,to_status,detail,actor_membership_id,occurred_at";

  private final DSLContext sql;
  private final Map<String, SupplierOrderGateway> gateways = new LinkedHashMap<>();

  public DropshipOrderService(
      DSLContext sql,
      List<SupplierOrderGateway> gateways,
      Alibaba1688SupplierAdapter alibaba1688) {
    this.sql = sql;
    for (SupplierOrderGateway gateway : gateways) {
      this.gateways.put(gateway.vendorCode(), gateway);
    }
    // 能力声明要能把「哪些 vendor 已接线」如实报出来，因此两个 vendor 都要在册。
    this.gateways.put(alibaba1688.vendorCode(), alibaba1688);
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>本模块只有一处「拿不到却必须说」：<b>支付状态</b>。9.1 的 sales_order 只有
   * {@code payment_method}（付款<b>方式</b>，我方与客户的约定）与
   * {@code status}（DRAFT/SUBMITTED，审单队列状态），两者都<b>不是</b>实收流水。
   * V100 已把这三件事逐条记过并把支付状态归给 12 财务（未交付）。
   *
   * <p>所以本模块不读「已付款」，而是要求一条<b>有主体的人工确认凭据</b>
   * （确认时刻 + 确认人 + MANUAL_ATTESTATION + 依据），由 DB 触发器
   * {@code guard_dropship_order_payment_confirmed} 在提交给供应商那一刻强制复查。
   * 12 财务交付后把这四件套换成授权 API 的读取即可，表结构不变。
   */
  public DropshipOrderCapabilities capabilities() {
    List<DropshipOrderCapabilityVendor> vendors =
        gateways.values().stream()
            .map(
                g ->
                    new DropshipOrderCapabilityVendor(
                        g.vendorCode(), g.displayName(), g.wired(), g.wiringRequirement()))
            .toList();
    return new DropshipOrderCapabilities(
        vendors,
        java.util.Arrays.stream(DropshipOrderStatus.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(DropshipAlertKind.values()).map(Enum::name).toList(),
        List.of(
            new DropshipOrderCapabilityDimension(
                "ORDER",
                "9.1 销售订单（订单头 / 行 / 收货地址）",
                true,
                "9.1.2.2 手工订单 V99（sales_order / sales_order_line / order_address_snapshot）"
                    + " → 本模块同库 FK 引用",
                null,
                null),
            new DropshipOrderCapabilityDimension(
                "DROPSHIP_CONFIG",
                "9.6.1.1 代发配置（供应商 / 采购价 / 货权）",
                true,
                "9.6.1.1 V118（fulfillment_dropship_config，含 supplier_cost 与"
                    + " counts_against_own_inventory 生成列）",
                null,
                null),
            new DropshipOrderCapabilityDimension(
                "PAYMENT",
                "销售订单支付状态",
                false,
                "12 财务实收流水（未交付）",
                "9.1 的 sales_order 没有支付状态列：status 的 CHECK 只有"
                    + "（DRAFT/SUBMITTED），那是审单队列状态；payment_method 是付款"
                    + "**方式**（预付/货到付）不是实收。本模块因此不读支付状态，改要求"
                    + "有成员署名的人工确认凭据（payment_confirmed_at / 确认人 / "
                    + "MANUAL_ATTESTATION / 依据），并由 DB 触发器在提交供应商时强制复查",
                "12 财务（实收流水）"),
            new DropshipOrderCapabilityDimension(
                "VENDOR_1688",
                "1688 开放平台代发下单接口",
                gateways.getOrDefault("ALIBABA_1688", stubGateway()).wired(),
                "Alibaba1688SupplierAdapter（本服务内，vendor 独立包）",
                null,
                "未登记 base_url 与 credential_ref 时**显式 503**，绝不返回编造的"
                    + "供应商单号。人工录入（MANUAL_ENTRY）这条路径是通的，"
                    + "蓝图输入句明写「供应商代发接口**或**人工录入」"),
            new DropshipOrderCapabilityDimension(
                "LOGISTICS_TRACK",
                "供应商运单跟踪与妥投判定",
                false,
                "10 物流轨迹 / 9.6.1.3 代发履约跟踪",
                "9.6.1.3",
                "本模块只**记录**供应商回执里的运单号（supplier_tracking_no），"
                    + "跟踪、延迟、丢件、跑单属下游 9.6.1.3；这里报 false 而不是"
                    + "给一个永远不发新的「跟踪状态」"),
            new DropshipOrderCapabilityDimension(
                "PLATFORM_CALLBACK",
                "回传平台发货信息",
                false,
                "2.5.1.1 平台回传",
                "2.5.1.1",
                "代发模式下「及时发货」平台考核算我方，但回传动作属 2.5.1.1；"
                    + "本模块不代为回传，避免两边都以为回传过了")),
        false,
        "差价毛利是本模块的第二个硬输出，生成列 spread_amount = 售价 −（采购价 + 供应商运费）。",
        "差价**可以为负且不拦** —— 拦了就录不进真实亏损的单，而亏损单恰恰是最该被看见的。"
            + "唯一要求是它必须有值（NOT NULL）：「没算」与「算了是负的」是两件事，"
            + "前者才是蓝图说的静默亏损。因此 spread_amount < 0 会自动落一条 "
            + "NEGATIVE_SPREAD 告警（CRITICAL），与涨价/无货/失败并列，"
            + "四类互不合并 —— 处置动作完全不同（改售价 / 换供应商 / 重下 / 止损）。"
            + "12 财务（差价结算）与 13 利润（代发口径）消费的是本模块的这些生成列，"
            + "不在下游重算第二份。");
  }

  private SupplierOrderGateway stubGateway() {
    return gateways.getOrDefault("ALIBABA_1688", new ManualEntrySupplierAdapter());
  }

  // ================================================================ 供应商接口登记

  /** 供应商代发接口登记列表（真分页）。 */
  public DropshipEndpointPage pageEndpoints(DropshipEndpointQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.vendorCode())) {
      where.add("vendor_code = ?");
      binds.add(query.vendorCode().trim());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_supplier_endpoint" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipEndpointRow> items =
        sql.fetch(
                "select id,vendor_code,vendor_name,base_url,credential_ref,status,wired,note,"
                    + "updated_at from fulfillment_dropship_supplier_endpoint"
                    + sqlWhere
                    + " order by vendor_code limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipOrderService::endpointRow);
    return new DropshipEndpointPage(items, total, pageNo, size);
  }

  /**
   * 登记 / 更新一个供应商代发接口。
   *
   * <p>只保存 {@code credentialRef}（指向 secret manager 的键），迁移与代码里
   * 都不出现任何明文凭证（AGENTS.md 安全条）。声称已接线就必须给出 base_url，
   * 否则「已接线」只是个标签而调用必然失败。
   */
  @Transactional
  public DropshipEndpointSaved saveEndpoint(DropshipEndpointInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String vendorCode = endpointVendor(input.vendorCode());
    String baseUrl = nz(input.baseUrl()).trim();
    String credentialRef = nz(input.credentialRef()).trim();
    boolean wired = Boolean.TRUE.equals(input.wired());
    if (wired && baseUrl.isEmpty()) {
      throw bad(
          "标记为「已接线」就必须给出 baseUrl：网关地址为空时任何调用都不可能成功，"
              + "而一个声称可用却调不通的集成比明确报未接线更糟");
    }
    if (!credentialRef.isEmpty() && credentialRef.contains("=")) {
      throw bad(
          "credentialRef 只能是 secret manager 里的**键名**，不接受 key=value 形态的明文凭证");
    }
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_supplier_endpoint where vendor_code = ?",
            vendorCode);
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    OffsetDateTime now = OffsetDateTime.now();
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_supplier_endpoint(id,tenant_id,vendor_code,vendor_name,"
              + "base_url,credential_ref,status,wired,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,'ACTIVE',?,?,?::timestamptz,?::timestamptz)",
          id, currentTenant(), vendorCode, input.vendorName().trim(), baseUrl, credentialRef, wired,
          nz(input.note()), now, now);
    } else {
      sql.execute(
          "update fulfillment_dropship_supplier_endpoint set vendor_name = ?, base_url = ?,"
              + " credential_ref = ?, wired = ?, note = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          input.vendorName().trim(), baseUrl, credentialRef, wired, nz(input.note()), now, id);
    }
    return new DropshipEndpointSaved(id, vendorCode, created);
  }

  // ================================================================ 建单

  /**
   * 从一张已提交的销售订单 + 一份 9.6.1.1 生效配置建代发单。
   *
   * <p>采购价**冻结**自配置（{@code supplier_cost} 或行级覆盖），售价取自
   * {@code sales_order_line.unit_price} —— 下单后买家改价不影响已下的代发单，
   * 而供应商涨价比的是这份冻结价，不是实时配置价。
   */
  @Transactional
  public DropshipOrderSaved createOrder(DropshipOrderInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    DropshipDispatchMode mode = dispatchMode(input.dispatchMode());
    String vendorCode = vendorCode(input.vendorCode());
    SupplierOrderGateway gateway = gateways.get(vendorCode);
    if (gateway == null) {
      throw bad("未知的供应商 vendor：" + vendorCode);
    }

    // 上游：9.6.1.1 配置。必须 ACTIVE 且在生效期内，且是供应商直发。
    var config =
        sql.fetchOne(
            "select config_code,fulfillment_path,supplier_code,supplier_cost,reference_sell_price,"
                + "currency_code,status,effective_from,effective_to,counts_against_own_inventory,"
                + "ownership_mode from fulfillment_dropship_config"
                + " where id = ? and effective_from <= now()"
                + " and (effective_to is null or effective_to > now())",
            input.configId());
    if (config == null) {
      throw notFound(
          "代发配置不存在、不属于当前租户，或不在生效期内：未生效的配置下单等于"
              + "绕过 9.6.1.1 的可售判定直接向供应商买货");
    }
    if (!"ACTIVE".equals(config.get("status", String.class))) {
      throw bad(
          "代发配置 " + config.get("config_code", String.class) + " 当前是 "
              + config.get("status", String.class) + "，只有 ACTIVE 的配置能建代发单");
    }
    if (!"SUPPLIER_DIRECT".equals(config.get("fulfillment_path", String.class))) {
      throw bad(
          "这份配置的履约路径是「自营发货」：货从我方仓出，不存在「向供应商下单」这回事。"
              + "自营发货的订单请走 9.3 分仓，而不是建一张代发单");
    }
    String supplierCode = config.get("supplier_code", String.class);
    BigDecimal configCost = money(config.get("supplier_cost", BigDecimal.class), "supplierCost");

    // 上游：9.1 订单。status 必须已提交（进过审单队列），草稿不许下单。
    var order =
        sql.fetchOne(
            "select order_no,currency,status from sales_order where id = ?",
            input.salesOrderId());
    if (order == null) {
      throw notFound("销售订单不存在或不属于当前租户");
    }
    if (!"SUBMITTED".equals(order.get("status", String.class))) {
      throw bad(
          "销售订单 " + order.get("order_no", String.class) + " 当前是草稿："
              + "买家还没下单的单不能向供应商采购");
    }
    String currency = order.get("currency", String.class);
    String customerName =
        sql.fetchOne(
                "select coalesce(max(customer_name),'') as customer_name"
                    + " from sales_order_party_snapshot where order_id = ?",
                input.salesOrderId())
            .get("customer_name", String.class);

    // 行：从销售订单行快照过来，逐行按配置算采购价（行级覆盖优先）
    var orderLines =
        sql.fetch(
            "select l.id,l.line_no,l.sku_id,l.sku_code,l.sku_name,l.spec_snapshot,l.quantity,"
                + "l.unit_price,c.supplier_cost_override"
                + " from sales_order_line l left join fulfillment_dropship_config_line c"
                + " on c.tenant_id = l.tenant_id and c.sku_ref = l.sku_code"
                + " and c.active and c.config_id = ?"
                + " where l.order_id = ? order by l.line_no",
            input.configId(), input.salesOrderId());
    if (orderLines.isEmpty()) {
      throw bad("销售订单没有任何明细行，无从向供应商下单");
    }

    BigDecimal totalSell = ZERO_MONEY;
    BigDecimal totalPurchase = ZERO_MONEY;
    for (Record line : orderLines) {
      BigDecimal qty = line.get("quantity", BigDecimal.class);
      BigDecimal sellUnit = money(line.get("unit_price", BigDecimal.class), "unitPrice");
      BigDecimal override = line.get("supplier_cost_override", BigDecimal.class);
      BigDecimal purchaseUnit = override == null ? configCost : money(override, "override");
      totalSell = totalSell.add(money(sellUnit.multiply(qty), "sellAmount"));
      totalPurchase = totalPurchase.add(money(purchaseUnit.multiply(qty), "purchaseAmount"));
    }

    String orderNo = nextOrderNo();
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into fulfillment_dropship_order(id,tenant_id,order_no,sales_order_id,"
            + "sales_order_no,customer_name,currency_code,config_id,config_code,supplier_code,"
            + "fulfillment_path,ownership_mode,counts_against_own_inventory,status,dispatch_mode,"
            + "vendor_code,sell_amount,purchase_amount,supplier_shipping_amount,note,"
            + "created_by_membership_id,created_at,updated_at)"
            // 23 列 = 20 个占位符 + 3 个字面量（'DRAFT' 与 supplier_shipping_amount 的 0），
            // 顺序与上面的列清单逐位对应；cast 写在两个时间位上，不放在绑定序列末位之后。
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,0,?,?,?::timestamptz,"
            + "?::timestamptz)",
        id, currentTenant(), orderNo, input.salesOrderId(),
        order.get("order_no", String.class), customerName, currency, input.configId(),
        config.get("config_code", String.class), supplierCode,
        config.get("fulfillment_path", String.class), config.get("ownership_mode", String.class),
        config.get("counts_against_own_inventory", Boolean.class), mode.name(), vendorCode,
        totalSell, totalPurchase, nz(input.note()), membershipId, now, now);
    event(id, "CREATED", "", "DRAFT",
        "建代发单（供应商 " + supplierCode + "，采购价已从配置冻结为 "
            + totalPurchase.toPlainString() + " " + currency + "）",
        membershipId);

    int lineNo = 1;
    for (Record line : orderLines) {
      BigDecimal qty = line.get("quantity", BigDecimal.class);
      BigDecimal sellUnit = money(line.get("unit_price", BigDecimal.class), "unitPrice");
      BigDecimal override = line.get("supplier_cost_override", BigDecimal.class);
      BigDecimal purchaseUnit = override == null ? configCost : money(override, "override");
      sql.execute(
          "insert into fulfillment_dropship_order_line(id,tenant_id,order_id,line_no,sku_id,"
              + "sku_ref,sku_name,spec_snapshot,quantity,sell_unit_price,purchase_unit_price,"
              + "sell_amount,purchase_amount,supplier_sku_ref,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), currentTenant(), id, lineNo++, line.get("sku_id", UUID.class),
          line.get("sku_code", String.class), nz(line.get("sku_name", String.class)),
          nz(line.get("spec_snapshot", String.class)), qty, sellUnit, purchaseUnit,
          money(sellUnit.multiply(qty), "sellAmount"), money(purchaseUnit.multiply(qty), "purchaseAmount"),
          line.get("sku_code", String.class), now, now);
    }
    return new DropshipOrderSaved(id, orderNo, true);
  }

  /** 由 DB 发号器取下一个代发单号（DSO-yyyyMMdd-000001），并发建单不撞号。 */
  private String nextOrderNo() {
    var code =
        sql.fetchOne(
            "select 'DSO-' || to_char(now(),'YYYYMMDD') || '-' ||"
                + " lpad(nextval('dropship_order_no_seq')::text, 6, '0') as code");
    return code.get("code", String.class);
  }

  // ================================================================ 收款确认

  /**
   * 确认收款（9.1 无支付状态列，见类注释与 V122 文件头第二节）。
   *
   * <p>这不是「设置一个支付状态」，而是记录**一次由有权限的成员作出的书面确认**：
   * 有主体、有时刻、有依据。DB 的 CHECK 与触发器会分别复查「四件套齐不齐」
   * 与「未确认不许提交给供应商」。
   */
  @Transactional
  public DropshipOrderSaved confirmPayment(
      UUID orderId, DropshipPaymentConfirmInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var row = requireOrder(orderId);
    if (!"DRAFT".equals(row.get("status", String.class))) {
      throw bad(
          "代发单 " + row.get("order_no", String.class) + " 当前是 "
              + row.get("status", String.class) + "：收款确认只在建单后、提交供应商之前做");
    }
    String note = nz(input.note()).trim();
    if (note.isEmpty()) {
      throw bad(
          "必须写明确认依据：这笔钱是怎么确认到账的（银行流水号 / 平台回执 / 现金签收）。"
              + "留空等于没有凭据，事后无从追责");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_order set status = 'READY',"
            + " payment_confirmed_at = ?::timestamptz, payment_confirmed_by_membership_id = ?,"
            + " payment_confirmed_source = 'MANUAL_ATTESTATION', payment_confirmed_note = ?,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and status = 'DRAFT'",
        input.confirmedAt(), membershipId, note, now, orderId);
    event(orderId, "PAYMENT_CONFIRMED", "DRAFT", "READY",
        "人工确认收款：" + note, membershipId);
    return new DropshipOrderSaved(orderId, row.get("order_no", String.class), false);
  }

  // ================================================================ 向供应商下单

  /**
   * 向供应商提交代发单（人工路径与自动路径同一套回填与告警逻辑）。
   *
   * <p>人工路径（{@code dispatchMode=MANUAL}）<b>不调用任何 vendor</b>：供应商单号与
   * 实际成本由运营随后用 {@link #recordSupplierAck} 录进来。之所以不合成一步，
   * 是因为「已下单」与「供应商回执了多少钱」是两个不同时刻发生的事实，
   * 合成一步会让「还没回执」看起来像「回执是 0」。
   *
   * <p><b>供应商侧失败刻意<b>不</b>抛异常</b>（与 9.6.1.1 的 evaluate 同一纪律：
   * 「无法履约也是业务结论而不是请求失败」）。原因有一个很实际的：
   * 本方法带 {@code @Transactional}，一旦抛出，{@link #failWith} 刚刚写下的
   * FAILED 状态与 ORDER_FAILED / NO_STOCK 告警会**一起回滚** ——
   * 而那正是蓝图要求「必须显式」的东西。把证据回滚掉，换来一个 503 响应，
   * 净效果是这次失败什么都没留下。所以这里返回单据本身，让调用方从
   * {@code status=FAILED} + 告警读到事实。
   */
  @Transactional
  public DropshipOrderSaved dispatch(UUID orderId, UUID membershipId) {
    var row = requireOrder(orderId);
    String orderNo = row.get("order_no", String.class);
    String current = row.get("status", String.class);
    if (!"READY".equals(current)) {
      throw bad(
          "代发单 " + orderNo + " 当前是 " + current + "：只有 READY（已确认收款）才能提交供应商");
    }
    String vendorCode = row.get("vendor_code", String.class);
    if ("MANUAL_ENTRY".equals(vendorCode)) {
      // 人工路径：置为已提交，等运营录回执。这里**不**调 vendor，也**不**写供应商单号。
      sql.execute(
          "update fulfillment_dropship_order set status = 'DISPATCHED',"
              + " dispatched_at = ?::timestamptz, dispatched_by_membership_id = ?,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          OffsetDateTime.now(), membershipId, OffsetDateTime.now(), orderId);
      event(orderId, "DISPATCHED_MANUAL", "READY", "DISPATCHED",
          "人工路径：已转「待录入供应商回执」，供应商单号与实际成本由运营录入", membershipId);
      return new DropshipOrderSaved(orderId, orderNo, false);
    }

    PlacementRequest request = placementRequest(row);
    SupplierOrderGateway gateway = gateways.get(vendorCode);
    try {
      SupplierOrderAck ack = gateway.place(request);
      applyAck(orderId, orderNo, row, ack, membershipId);
    } catch (SupplierOrderGateway.SupplierOutOfStockException e) {
      // 无货：显式落 NO_STOCK 告警（蓝图点名的三类差异之一）
      failWith(orderId, orderNo, row, "NO_STOCK", e.supplierMessage(), membershipId);
    } catch (SupplierOrderGateway.SupplierRejectedException e) {
      failWith(orderId, orderNo, row, "ORDER_FAILED", e.supplierMessage(), membershipId);
    } catch (SupplierOrderException e) {
      // 未接线 / 平台不可达：显式失败并落告警，**绝不**伪造一张已接单的代发单。
      failWith(orderId, orderNo, row, "ORDER_FAILED", e.getMessage(), membershipId);
    }
    return new DropshipOrderSaved(orderId, orderNo, false);
  }

  /** 组装下单报文（地址与商品信息逐字取自 9.1 的快照，vendor 不回查我方库）。 */
  private PlacementRequest placementRequest(Record row) {
    var addr =
        sql.fetchOne(
            "select recipient_name,recipient_phone,country_code,province,city,district,"
                + "address_line1,address_line2,postcode from order_address_snapshot"
                + " where order_id = ? and address_role = 'SHIPPING'",
            row.get("sales_order_id", UUID.class));
    Recipient recipient;
    if (addr == null) {
      throw bad(
          "销售订单没有收货地址快照：没有地址就无法「传递地址与商品信息」给供应商，"
              + "本模块不会拿空地址去下单");
    }
    recipient =
        new Recipient(
            nz(addr.get("recipient_name", String.class)),
            nz(addr.get("recipient_phone", String.class)),
            nz(addr.get("country_code", String.class)),
            nz(addr.get("province", String.class)),
            nz(addr.get("city", String.class)),
            nz(addr.get("district", String.class)),
            nz(addr.get("address_line1", String.class)),
            nz(addr.get("address_line2", String.class)),
            nz(addr.get("postcode", String.class)));
    List<PlacedLine> lines =
        sql.fetch(
                "select sku_ref,sku_name,spec_snapshot,quantity,supplier_sku_ref"
                    + " from fulfillment_dropship_order_line where order_id = ? order by line_no",
                row.get("id", UUID.class))
            .map(
                l ->
                    new PlacedLine(
                        l.get("sku_ref", String.class), l.get("sku_name", String.class),
                        l.get("spec_snapshot", String.class), l.get("quantity", BigDecimal.class),
                        l.get("supplier_sku_ref", String.class)));
    return new PlacementRequest(
        row.get("id", UUID.class), row.get("order_no", String.class),
        row.get("supplier_code", String.class), row.get("currency_code", String.class), recipient,
        lines);
  }

  /** 落供应商回执（自动与人工两条路径共用，因此告警逻辑只有一份）。 */
  private void applyAck(
      UUID orderId, String orderNo, Record row, SupplierOrderAck ack, UUID membershipId) {
    BigDecimal expectedPurchase = row.get("purchase_amount", BigDecimal.class);
    BigDecimal expectedShipping = row.get("supplier_shipping_amount", BigDecimal.class);
    BigDecimal actualPurchase = money(ack.actualPurchaseAmount(), "actualPurchaseAmount");
    BigDecimal actualShipping = money(ack.actualSupplierShippingAmount(), "actualSupplierShipping");
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_order set status = 'ACCEPTED', supplier_order_no = ?,"
            + " actual_purchase_amount = ?, actual_supplier_shipping_amount = ?,"
            + " supplier_tracking_no = coalesce(nullif(?,''),supplier_tracking_no),"
            + " accepted_at = ?::timestamptz, accepted_by_membership_id = ?,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and status in ('DISPATCHED','READY')",
        ack.supplierOrderNo(), actualPurchase, actualShipping, nz(ack.trackingNo()),
        now, membershipId, now, orderId);
    event(orderId, "SUPPLIER_ACKED", row.get("status", String.class), "ACCEPTED",
        "供应商回执：单号 " + ack.supplierOrderNo() + "，实际采购价 "
            + actualPurchase.toPlainString() + "（下单冻结价 " + expectedPurchase.toPlainString()
            + "），实际运费 " + actualShipping.toPlainString() + "（预计 "
            + expectedShipping.toPlainString() + "）",
        membershipId);
    // 蓝图点名必须显式告警的三类差异，就在这里产生。
    raiseCostAlerts(orderId, expectedPurchase, expectedShipping, actualPurchase, actualShipping,
        ack.supplierOrderNo(), membershipId);
  }

  /**
   * 涨价与差价转负的告警判定（本模块最要紧的一段）。
   *
   * <p>四条判定彼此独立：
   * <ol>
   *   <li><b>涨价</b>：实际采购价或实际运费高于下单冻结价 → PRICE_INCREASE；</li>
   *   <li><b>差价转负</b>：按**实际**成本重算后差价 &lt; 0 → NEGATIVE_SPREAD（CRITICAL）。
   *       用实际成本而不是冻结成本，正是因为「贵了 3 块导致这一单由赚变亏」才是
   *       蓝图说的那个静默亏损；按冻结价算它还是正的，就永远不报。</li>
   * </ol>
   */
  private void raiseCostAlerts(
      UUID orderId,
      BigDecimal expectedPurchase,
      BigDecimal expectedShipping,
      BigDecimal actualPurchase,
      BigDecimal actualShipping,
      String supplierOrderNo,
      UUID membershipId) {
    if (actualPurchase.compareTo(expectedPurchase) > 0) {
      alert(
          orderId,
          DropshipAlertKind.PRICE_INCREASE,
          "供应商 " + supplierOrderNo + " 的实际采购价 " + actualPurchase.toPlainString()
              + " 高于下单时的冻结价 " + expectedPurchase.toPlainString()
              + "（多 " + actualPurchase.subtract(expectedPurchase).toPlainString()
              + "）。差价已按实际成本重算；若转负会另有一条亏损告警",
          expectedPurchase,
          actualPurchase,
          membershipId);
    }
    if (actualShipping.compareTo(expectedShipping) > 0) {
      alert(
          orderId,
          DropshipAlertKind.PRICE_INCREASE,
          "供应商 " + supplierOrderNo + " 的实际运费 " + actualShipping.toPlainString()
              + " 高于下单时的预计 " + expectedShipping.toPlainString()
              + "（多 " + actualShipping.subtract(expectedShipping).toPlainString() + "）",
          expectedShipping,
          actualShipping,
          membershipId);
    }
    // 差价按**实际**代发成本重算：售价 −（实际采购价 + 实际运费）
    var spreadRow =
        sql.fetchOne(
            "select sell_amount - ? - ? as spread from fulfillment_dropship_order where id = ?",
            actualPurchase, actualShipping, orderId);
    BigDecimal actualSpread = spreadRow.get("spread", BigDecimal.class);
    if (actualSpread.signum() < 0) {
      alert(
          orderId,
          DropshipAlertKind.NEGATIVE_SPREAD,
          "按供应商实际成本重算后，这一单差价已转负：" + actualSpread.toPlainString()
              + "（售价 −（实际采购价 " + actualPurchase.toPlainString() + " + 实际运费 "
              + actualShipping.toPlainString() + "））。这正是蓝图说的静默亏损："
              + "不告警就会一路走到财务结算才被发现",
          actualSpread.negate(),
          actualSpread,
          membershipId);
    }
  }

  /** 落一条告警。同一张单同一类告警不重复落（一次涨价一条，不是每刷新一次一条）。 */
  private void alert(
      UUID orderId, DropshipAlertKind kind, String detail, BigDecimal expected, BigDecimal actual,
      UUID membershipId) {
    long existing =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_alert"
                    + " where order_id = ? and alert_kind = ?",
                orderId, kind.name())
            .get("c", Long.class);
    if (existing > 0) {
      return;
    }
    sql.execute(
        "insert into fulfillment_dropship_alert(id,tenant_id,order_id,alert_kind,severity,"
            + "expected_amount,actual_amount,detail,status,occurred_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,'OPEN',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), orderId, kind.name(), kind.severity(), expected,
        actual, detail, OffsetDateTime.now(), OffsetDateTime.now());
    event(orderId, "ALERT_" + kind.name(), "", "", detail, membershipId);
  }

  /** 下单失败：置 FAILED + 落告警（蓝图点名的「失败」必须显式）。 */
  private void failWith(
      UUID orderId, String orderNo, Record row, String kind, String reason, UUID membershipId) {
    String safe = nz(reason).trim();
    if (safe.isEmpty()) {
      safe = "供应商下单失败但未给出原因";
    }
    if (safe.length() > 400) {
      safe = safe.substring(0, 400);
    }
    sql.execute(
        "update fulfillment_dropship_order set status = 'FAILED', rejection_reason = ?,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and status in ('READY','DISPATCHED','ACCEPTED')",
        safe, OffsetDateTime.now(), orderId);
    alert(
        orderId,
        kind.equals("NO_STOCK") ? DropshipAlertKind.NO_STOCK : DropshipAlertKind.ORDER_FAILED,
        "供应商下单未成功：" + safe, null, null, membershipId);
    event(orderId, "ORDER_FAILED", row.get("status", String.class), "FAILED", safe, membershipId);
  }

  // ================================================================ 人工回填回执

  /**
   * 人工录入供应商回执（蓝图输入句「供应商代发接口<b>或</b>人工录入」）。
   *
   * <p>与自动路径走<b>同一个</b> {@link #applyAck}，因此告警逻辑只有一份 ——
   * 人工录入涨价同样会落 PRICE_INCREASE，差价同样会算。
   */
  @Transactional
  public DropshipOrderSaved recordSupplierAck(
      UUID orderId, DropshipSupplierAckInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var row = requireOrder(orderId);
    String current = row.get("status", String.class);
    if (!"DISPATCHED".equals(current)) {
      throw bad(
          "只有 DISPATCHED（已提交供应商、待回执）的代发单才能录入回执，当前是 " + current);
    }
    String supplierOrderNo = nz(input.supplierOrderNo()).trim();
    if (supplierOrderNo.isEmpty()) {
      throw bad("必须录入供应商单号：没有单号就对不上账，也追不到这批货");
    }
    SupplierOrderAck ack =
        new SupplierOrderAck(
            supplierOrderNo,
            money(input.actualPurchaseAmount(), "actualPurchaseAmount"),
            money(input.actualSupplierShippingAmount(), "actualSupplierShippingAmount"),
            nz(input.trackingNo()),
            nz(input.note()));
    applyAck(orderId, row.get("order_no", String.class), row, ack, membershipId);
    return new DropshipOrderSaved(orderId, row.get("order_no", String.class), false);
  }

  /** 记录供应商发货（运单号交给下游 9.6.1.3 / 10 物流，本模块只记录）。 */
  @Transactional
  public DropshipOrderSaved recordShipment(
      UUID orderId, DropshipShipmentInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var row = requireOrder(orderId);
    if (!"ACCEPTED".equals(row.get("status", String.class))) {
      throw bad(
          "只有供应商已受理（ACCEPTED）的代发单才能记发货，当前是 "
              + row.get("status", String.class));
    }
    String tracking = nz(input.supplierTrackingNo()).trim();
    if (tracking.isEmpty()) {
      throw bad("必须录入供应商运单号：没有运单号，10 物流与 9.6.1.3 无从接这一段");
    }
    sql.execute(
        "update fulfillment_dropship_order set status = 'SHIPPED', supplier_tracking_no = ?,"
            + " supplier_shipped_at = ?::timestamptz, version = version + 1,"
            + " updated_at = ?::timestamptz where id = ? and status = 'ACCEPTED'",
        tracking, input.shippedAt(), OffsetDateTime.now(), orderId);
    event(orderId, "SUPPLIER_SHIPPED", "ACCEPTED", "SHIPPED",
        "供应商已发货，运单号 " + tracking + "（跟踪与妥投判定属 9.6.1.3 / 10 物流）", membershipId);
    return new DropshipOrderSaved(orderId, row.get("order_no", String.class), false);
  }

  /** 人工置失败（供应商口头拒单、平台返回失败等）。必落 ORDER_FAILED 告警。 */
  @Transactional
  public DropshipOrderSaved markFailed(
      UUID orderId, DropshipFailureInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var row = requireOrder(orderId);
    String current = row.get("status", String.class);
    if (!List.of("READY", "DISPATCHED", "ACCEPTED").contains(current)) {
      throw bad("代发单当前是 " + current + "，不在可置失败的��态");
    }
    failWith(orderId, row.get("order_no", String.class), row, "ORDER_FAILED", input.reason(),
        membershipId);
    return new DropshipOrderSaved(orderId, row.get("order_no", String.class), false);
  }

  // ================================================================ 读

  /** 代发单列表（真分页）。{@code negativeSpreadOnly=true} 只回差价转负的那些。 */
  public DropshipOrderPage pageOrders(DropshipOrderQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.orderNo())) {
      where.add("order_no ilike ?");
      binds.add("%" + query.orderNo().trim() + "%");
    }
    if (notBlank(query.salesOrderNo())) {
      where.add("sales_order_no ilike ?");
      binds.add("%" + query.salesOrderNo().trim() + "%");
    }
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.vendorCode())) {
      where.add("vendor_code = ?");
      binds.add(vendorCode(query.vendorCode()));
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(orderStatus(query.status()).name());
    }
    if (Boolean.TRUE.equals(query.negativeSpreadOnly())) {
      where.add("spread_amount < 0");
    }
    if (notBlank(query.keyword())) {
      where.add("(order_no ilike ? or sales_order_no ilike ? or supplier_order_no ilike ?"
          + " or customer_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 4; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_order" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipOrderRow> items =
        sql.fetch(
                "select " + ORDER_COLUMNS + " from fulfillment_dropship_order"
                    + sqlWhere
                    + " order by created_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipOrderService::orderRow);
    return new DropshipOrderPage(items, total, pageNo, size);
  }

  /** 代发单详情：头 + 明细行 + 告警 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public DropshipOrderDetail orderDetail(UUID orderId) {
    var row =
        sql.fetchOne("select " + ORDER_COLUMNS + " from fulfillment_dropship_order where id = ?",
            orderId);
    if (row == null) {
      throw notFound("代发单不存在或不属于当前租户");
    }
    List<DropshipOrderLineRow> lines =
        sql.fetch(
                "select " + LINE_COLUMNS + " from fulfillment_dropship_order_line"
                    + " where order_id = ? order by line_no",
                orderId)
            .map(DropshipOrderService::lineRow);
    List<DropshipAlertRow> alerts =
        sql.fetch(
                // 列清单逐位加 a. 前缀：这张查询 join 了 orders 表，
                // 不限定别名时 id / status 等同名列会报「column reference is ambiguous」。
                "select a.id,a.order_id,a.alert_kind,a.severity,a.expected_amount,"
                    + " a.actual_amount,a.detail,a.status,a.acknowledged_at,"
                    + " a.acknowledged_by_membership_id,a.resolution_note,a.occurred_at,"
                    + " o.order_no from fulfillment_dropship_alert a"
                    + " join fulfillment_dropship_order o on o.tenant_id = a.tenant_id"
                    + " and o.id = a.order_id where a.order_id = ?"
                    + " order by a.occurred_at desc, a.id",
                orderId)
            .map(DropshipOrderService::alertRow);
    return new DropshipOrderDetail(orderRow(row), lines, alerts, events(orderId, 50));
  }

  /** 代发单详情返回体。 */
  public record DropshipOrderDetail(
      DropshipOrderRow order,
      List<DropshipOrderLineRow> lines,
      List<DropshipAlertRow> alerts,
      List<DropshipOrderEventRow> events) {}

  /** 差异告警工作台（蓝图的告警是硬输出，必须好找）。 */
  public DropshipAlertPage pageAlerts(DropshipAlertQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.orderId() != null) {
      where.add("a.order_id = ?");
      binds.add(query.orderId());
    }
    if (notBlank(query.alertKind())) {
      where.add("a.alert_kind = ?");
      binds.add(alertKind(query.alertKind()).name());
    }
    if (notBlank(query.status())) {
      where.add("a.status = ?");
      binds.add(alertStatus(query.status()).name());
    }
    if (notBlank(query.severity())) {
      where.add("a.severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add("(a.detail ilike ? or o.order_no ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = whereClause(where);
    String from =
        " from fulfillment_dropship_alert a join fulfillment_dropship_order o"
            + " on o.tenant_id = a.tenant_id and o.id = a.order_id";
    long total = sql.fetchOne("select count(*) c" + from + sqlWhere, binds.toArray()).get("c", Long.class);
    List<DropshipAlertRow> items =
        sql.fetch(
                "select a.id,a.order_id,o.order_no,a.alert_kind,a.severity,a.expected_amount,"
                    + " a.actual_amount,a.detail,a.status,a.acknowledged_at,"
                    + " a.acknowledged_by_membership_id,a.resolution_note,a.occurred_at"
                    + from
                    + sqlWhere
                    + " order by a.occurred_at desc, a.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipOrderService::alertRow);
    return new DropshipAlertPage(items, total, pageNo, size);
  }

  /**
   * 处置一条告警。
   *
   * <p>处置<b>不删除</b>历史行（触发器 + REVOKE 双层挡住）：告警是「已经贵过 3 块」
   * 这件事的凭据，删掉它就等于把这段历史抹掉 —— 那正是静默亏损的另一种形状。
   */
  @Transactional
  public DropshipAlertRow acknowledgeAlert(UUID alertId, DropshipAlertAckInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String note = nz(input.resolutionNote()).trim();
    if (note.isEmpty()) {
      throw bad("必须写处置说明：改价了？换供应商了？退款了？留空等于没处置");
    }
    var row =
        sql.fetchOne(
            "select order_id,status from fulfillment_dropship_alert where id = ?", alertId);
    if (row == null) {
      throw notFound("告警不存在或不属于当前租户");
    }
    if (!"OPEN".equals(row.get("status", String.class))) {
      throw bad("该告警已处置过，不必重复处置");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_alert set status = 'RESOLVED', acknowledged_at = ?::timestamptz,"
            + " acknowledged_by_membership_id = ?, resolution_note = ? where id = ?"
            + " and status = 'OPEN'",
        now, currentMembership(), note, alertId);
    event(row.get("order_id", UUID.class), "ALERT_RESOLVED", "", "", "处置告警：" + note,
        currentMembership());
    // 回读时带上 order_no：alertRow 少了这一列会在 jOOQ 取值时抛
    // "Field order_no is not contained in row type"（不是业务失败，是读模型形状不符）。
    return sql.fetchOne(
            "select a.id,a.order_id,o.order_no,a.alert_kind,a.severity,a.expected_amount,"
                + " a.actual_amount,a.detail,a.status,a.acknowledged_at,"
                + " a.acknowledged_by_membership_id,a.resolution_note,a.occurred_at"
                + " from fulfillment_dropship_alert a join fulfillment_dropship_order o"
                + " on o.tenant_id = a.tenant_id and o.id = a.order_id where a.id = ?",
            alertId)
        .map(DropshipOrderService::alertRow);
  }

  /** 事件流（只读；append-only，没有任何写形状）。 */
  public DropshipEventPage pageEvents(DropshipEventQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.orderId() != null) {
      where.add("order_id = ?");
      binds.add(query.orderId());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_order_event" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipOrderEventRow> items =
        sql.fetch(
                "select " + EVENT_COLUMNS + " from fulfillment_dropship_order_event"
                    + sqlWhere
                    + " order by occurred_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipOrderService::eventRow);
    return new DropshipEventPage(items, total, pageNo, size);
  }

  private List<DropshipOrderEventRow> events(UUID orderId, int limit) {
    return sql.fetch(
            "select " + EVENT_COLUMNS + " from fulfillment_dropship_order_event"
                + " where order_id = ? order by occurred_at desc, id limit ?",
            orderId, limit)
        .map(DropshipOrderService::eventRow);
  }

  private void event(
      UUID orderId, String kind, String from, String to, String detail, UUID actor) {
    sql.execute(
        "insert into fulfillment_dropship_order_event(id,tenant_id,order_id,event_kind,"
            + "from_status,to_status,detail,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), orderId, kind, nz(from), nz(to),
        detail.length() > 400 ? detail.substring(0, 400) : detail, actor, OffsetDateTime.now());
  }

  // ================================================================ 取行 / 取数

  /** 取一张代发单（RLS 下跨租户查不到 → 404）。 */
  private Record requireOrder(UUID orderId) {
    var row =
        sql.fetchOne("select " + ORDER_COLUMNS + " from fulfillment_dropship_order where id = ?",
            orderId);
    if (row == null) {
      throw notFound("代发单不存在或不属于当前租户");
    }
    return row;
  }

  /**
   * 当前操作人（来自 identity 已认证的成员关系，不是任何请求头）。
   *
   * <p>与 {@link #currentTenant()} 同源：都由 backend/libraries/tenancy 在事务内
   * 设置。用于告警处置留痕「谁处置的」。
   */
  private UUID currentMembership() {
    return sql.fetchOne("select nullif(current_setting('app.actor',true),'')::uuid as actor")
        .get("actor", UUID.class);
  }

  private static DropshipOrderRow orderRow(Record r) {
    return new DropshipOrderRow(
        r.get("id", UUID.class), r.get("order_no", String.class),
        r.get("sales_order_id", UUID.class), r.get("sales_order_no", String.class),
        r.get("customer_name", String.class), r.get("currency_code", String.class),
        r.get("config_id", UUID.class), r.get("config_code", String.class),
        r.get("supplier_code", String.class), r.get("fulfillment_path", String.class),
        r.get("ownership_mode", String.class),
        r.get("counts_against_own_inventory", Boolean.class), r.get("status", String.class),
        r.get("dispatch_mode", String.class), r.get("vendor_code", String.class),
        r.get("supplier_order_no", String.class), r.get("supplier_tracking_no", String.class),
        r.get("supplier_shipped_at", OffsetDateTime.class), r.get("rejection_reason", String.class),
        r.get("sell_amount", BigDecimal.class), r.get("purchase_amount", BigDecimal.class),
        r.get("supplier_shipping_amount", BigDecimal.class),
        r.get("actual_purchase_amount", BigDecimal.class),
        r.get("actual_supplier_shipping_amount", BigDecimal.class),
        r.get("dropship_cost", BigDecimal.class), r.get("spread_amount", BigDecimal.class),
        r.get("margin_rate", BigDecimal.class), r.get("payment_confirmed_at", OffsetDateTime.class),
        r.get("payment_confirmed_by_membership_id", UUID.class),
        r.get("payment_confirmed_source", String.class),
        r.get("payment_confirmed_note", String.class), r.get("note", String.class),
        r.get("dispatched_at", OffsetDateTime.class), r.get("accepted_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipOrderLineRow lineRow(Record r) {
    return new DropshipOrderLineRow(
        r.get("id", UUID.class), r.get("line_no", Integer.class), r.get("sku_id", UUID.class),
        r.get("sku_ref", String.class), r.get("sku_name", String.class),
        r.get("spec_snapshot", String.class), r.get("quantity", BigDecimal.class),
        r.get("sell_unit_price", BigDecimal.class), r.get("purchase_unit_price", BigDecimal.class),
        r.get("sell_amount", BigDecimal.class), r.get("purchase_amount", BigDecimal.class),
        r.get("supplier_sku_ref", String.class), r.get("note", String.class));
  }

  private static DropshipAlertRow alertRow(Record r) {
    String kind = r.get("alert_kind", String.class);
    DropshipAlertKind kindEnum = DropshipAlertKind.valueOf(kind);
    return new DropshipAlertRow(
        r.get("id", UUID.class), r.get("order_id", UUID.class), r.get("order_no", String.class),
        kind, kindEnum.label(), r.get("severity", String.class),
        r.get("expected_amount", BigDecimal.class), r.get("actual_amount", BigDecimal.class),
        r.get("detail", String.class), r.get("status", String.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by_membership_id", UUID.class),
        r.get("resolution_note", String.class), r.get("occurred_at", OffsetDateTime.class));
  }

  private static DropshipOrderEventRow eventRow(Record r) {
    return new DropshipOrderEventRow(
        r.get("id", UUID.class), r.get("order_id", UUID.class),
        r.get("event_kind", String.class), r.get("from_status", String.class),
        r.get("to_status", String.class), r.get("detail", String.class),
        r.get("actor_membership_id", UUID.class), r.get("occurred_at", OffsetDateTime.class));
  }

  private static DropshipEndpointRow endpointRow(Record r) {
    return new DropshipEndpointRow(
        r.get("id", UUID.class), r.get("vendor_code", String.class),
        r.get("vendor_name", String.class), r.get("base_url", String.class),
        r.get("credential_ref", String.class), r.get("status", String.class),
        r.get("wired", Boolean.class), r.get("note", String.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  private static int pageSize(int size) {
    return size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static Object[] append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal money(BigDecimal value, String field) {
    if (value == null) {
      throw bad(field + " 不能为空：代发成本与差价是本模块的硬输出，缺它就无从判断赚还是亏");
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  private static DropshipOrderStatus orderStatus(String value) {
    try {
      return DropshipOrderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的代发单状态：" + value);
    }
  }

  private static DropshipDispatchMode dispatchMode(String value) {
    try {
      return DropshipDispatchMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的下单方式：" + value + "（AUTO=走供应商接口，MANUAL=人工录入回执）");
    }
  }

  private static String vendorCode(String value) {
    if (value == null) {
      throw bad("请给出 vendorCode");
    }
    String v = value.trim().toUpperCase(Locale.ROOT);
    if (!List.of("MANUAL_ENTRY", "ALIBABA_1688").contains(v)) {
      throw bad("未知的 supplier vendor：" + value + "（MANUAL_ENTRY 或 ALIBABA_1688）");
    }
    return v;
  }

  private static String endpointVendor(String value) {
    if (value == null) {
      throw bad("请给出 vendorCode");
    }
    String v = value.trim().toUpperCase(Locale.ROOT);
    if (!List.of("MANUAL_ENTRY", "ALIBABA_1688").contains(v)) {
      throw bad("未知的 supplier vendor：" + value + "（MANUAL_ENTRY 或 ALIBABA_1688）");
    }
    return v;
  }

  private static DropshipAlertKind alertKind(String value) {
    try {
      return DropshipAlertKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的告警种类：" + value);
    }
  }

  private static DropshipAlertStatus alertStatus(String value) {
    try {
      return DropshipAlertStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的告警状态：" + value);
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}