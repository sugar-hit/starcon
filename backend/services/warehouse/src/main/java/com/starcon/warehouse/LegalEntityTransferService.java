package com.starcon.warehouse;

import static com.starcon.warehouse.LegalEntityTransferModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.5.2.1 跨法人调拨。
 *
 * <p>蓝图：「法人之间的库存转移（业务上是<b>货权转移 + 内部结算</b>），需要定价、生成内部往来单据、 双方确认，<b>不能当作普通同仓调拨</b>」；输入「转出与转入法人；转移 SKU
 * 与数量；转移定价」； 输出「跨法人调拨单 + 内部结算单据 + 双方库存变更」。
 *
 * <p>与 7.5.1.1（{@link InventoryTransferService}）的三处根本差别，全都是因为货权变了：
 *
 * <ol>
 *   <li><b>两个法人</b>。V61 的 transfer_order 只有一个 legal_entity_id，货权前后不变； 这里 {@code inventory_balance}
 *       的扣与加落在<b>不同法人</b>的余额行上（V1 第 4 节 余额维度含 legal_entity_id），这就是蓝图说的「双方库存变更」。
 *   <li><b>定价必填</b>。货权变了意味着利润归属变了（蓝图原话），没有金额就没有内部结算凭据， 12 财务的内部结算与收入确认就无从挂账。因此单价在明细行上是 NOT NULL 的，
 *       写死在迁移里而不是「可以后补」。
 *   <li><b>双方确认</b>。转出方与转入方是两个法人，<b>不能一个接口同时改两边</b>： 两个确认是两次独立调用、两条 append-only 记录，而且 DB 触发器钉死
 *       「两个方向的确认人必须是两个人」（见迁移 guard_cross_entity_two_parties）。
 * </ol>
 *
 * <p>跨服务边界（下游均未交付，这里只留交接面，不谎称已接入）：
 *
 * <ul>
 *   <li><b>12 财务</b>：本服务只开具 {@code cross_entity_settlement} 凭据， 以 {@code source_system +
 *       reference_id} 为定位键。不建跨服务外键、不查 12 的表， 也不复制科目/凭证/税额 —— 那些属于 12 的权威。取数契约见 {@link #settlement}。
 *   <li><b>17 权限（双方法人授权）</b>：尚未交付。当前可执行的约束是「同租户 + 租户级权限码 + 两次不同自然人的确认」，逐法人授权待 17 接入后叠加。该事实随详情响应返回，
 *       见 {@link #detail} 的 {@code authorizationNote} —— 界面上必须显示，不能让人 误以为「能确认」等于「有权代表该法人」。
 * </ul>
 *
 * <p>货权转移点定在<b>实收</b>而不是发出：沿用 7.5.1.1「调拨不是瞬时」的纪律， 实物在路上时货权归属仍有争议。而结算凭据在<b>发出</b>时开具 —— 那时转出方已交付、
 * 转入方已确认定价，财务据以挂账。两个时刻不同，是业务事实。
 */
@Service
public class LegalEntityTransferService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("CREATED_AT", "UPDATED_AT", "TRANSFER_CODE", "TOTAL_AMOUNT", "EXPECTED_ARRIVE");

  /** 数量与单价的精度：numeric(19,4)。 */
  private static final int SCALE = 4;

  /**
   * 转移定价必须在界面上说清的口径说明，随详情一起返回。
   *
   * <p>没有它，「单价 12.50、金额 1250」这行数看起来只是一个数，不知道按什么定的。
   */
  private static final String PRICING_NOTE =
      "行金额 = 数量 × 单价，由 DB 生成列算出，落库时四舍五入到 4 位小数（远离零），"
          + "数量与单价本身不做中间舍入以免双重舍入。整单一个币种（ISO 4217 三字母大写）："
          + "跨币种换算、汇率与汇兑损益属 12 财务职责，本模块不存汇率，也不换算。"
          + "单价为 0 是允许的（关联方按零定价平移货权），但必须与 valuationBasis 一起被解释，"
          + "而不是被悄悄当成「没填」。";

  /** 货权归属的时点说明。 */
  private static final String OWNERSHIP_NOTE =
      "货权在「实收」这一刻从转出法人转到转入法人：发出只扣转出法人的余额、货挂在途，"
          + "实物在路上时货权归属仍有争议。内部结算凭据在「发出」时开具，"
          + "因为那时转出方已交付、转入方已确认定价，财务据以挂账；实收时凭据被转入方确认。"
          + "两时刻不同是业务事实，不是数据延迟。";

  /** 授权现状说明。17 权限未交付，必须如实告知而不是含糊带过。 */
  private static final String AUTHORIZATION_NOTE =
      "本模块当前的可执行约束是：同租户 + 租户级权限码 warehouse.inventory.update，"
          + "以及转出方与转入方必须是两个不同的自然人（DB 触发器钉死）。"
          + "**逐法人授权（17 权限）尚未交付**：能调用本端点不等于有权代表某个法人，"
          + "两者当前没有被绑定，接入 17 后会在同一处叠加校验。";

  private final DSLContext sql;

  public LegalEntityTransferService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 列表

  private static String from(String a) {
    return " from cross_entity_transfer o"
        + a
        + " join warehouse fw on fw.tenant_id=o"
        + a
        + ".tenant_id and fw.id=o"
        + a
        + ".from_warehouse_id"
        + a
        + " join warehouse tw on tw.tenant_id=o"
        + a
        + ".tenant_id and tw.id=o"
        + a
        + ".to_warehouse_id"
        + a
        + " join legal_entity_ref fle on fle.tenant_id=o"
        + a
        + ".tenant_id and fle.id=o"
        + a
        + ".from_legal_entity_id"
        + a
        + " join legal_entity_ref tle on tle.tenant_id=o"
        + a
        + ".tenant_id and tle.id=o"
        + a
        + ".to_legal_entity_id";
  }

  /**
   * 每单的数量聚合。
   *
   * <p>在途走 {@code left join lateral} 到 {@code inventory_transfer} 的余量求和 —— 与 7.1.1.1 / 7.2.2.1 /
   * 7.1.3.1 / 7.5.1.2 用的是同一条 {@code sum(quantity) where status='IN_TRANSIT'} 口径，本模块的在途货不会在别的模块里消失。
   *
   * <p>lateral 在没有明细行时也返回一行全 0：一张没有明细的单不该从列表里「查不到」。
   */
  private static String aggJoin(String a) {
    return " left join lateral (select count(*) as line_count,"
        + " coalesce(sum(l.quantity),0) as total_quantity,"
        + " coalesce(sum(l.received_quantity),0) as received_quantity,"
        + " coalesce(sum(l.discrepancy_quantity),0) as discrepancy_quantity,"
        + " coalesce(sum(c.transit),0) as in_transit"
        + " from cross_entity_transfer_line l"
        + a
        + " left join lateral (select coalesce(sum(i.quantity),0) as transit"
        + " from inventory_transfer i where i.tenant_id=l"
        + a
        + ".tenant_id and i.ref_type='CROSS_ENTITY_TRANSFER' and i.ref_id=l"
        + a
        + ".transfer_id and i.status='IN_TRANSIT') c"
        + a
        + " on true where l"
        + a
        + ".tenant_id=o"
        + a
        + ".tenant_id and l"
        + a
        + ".transfer_id=o"
        + a
        + ".id) agg"
        + a
        + " on true"
        + a
        + " left join cross_entity_settlement s"
        + a
        + " on s.tenant_id=o"
        + a
        + ".tenant_id and s.transfer_id=o"
        + a
        + ".id";
  }

  private static final String ROW_SELECT =
      """
      select o.id as transfer_id,o.transfer_code,o.tenant_id as head_tenant_id,o.from_warehouse_id,\
       fw.code as from_warehouse_code,fw.name as from_warehouse_name,\
       o.to_warehouse_id,tw.code as to_warehouse_code,tw.name as to_warehouse_name,\
       o.from_legal_entity_id,fle.code as from_legal_entity_code,fle.name as from_legal_entity_name,\
       o.to_legal_entity_id,tle.code as to_legal_entity_code,tle.name as to_legal_entity_name,\
       o.status,o.valuation_basis,o.currency_code,o.total_amount,\
       o.expected_ship_at,o.expected_arrive_at,o.source_confirmed_at,o.target_confirmed_at,\
       o.shipped_at,o.completed_at,o.reason,o.version,o.created_at,o.updated_at,\
       coalesce(agg.line_count,0) as line_count,\
       coalesce(agg.total_quantity,0) as total_quantity,\
       coalesce(agg.received_quantity,0) as received_quantity,\
       coalesce(agg.discrepancy_quantity,0) as discrepancy_quantity,\
       coalesce(agg.in_transit,0) as in_transit_quantity,\
       s.status as settlement_status,s.settlement_code\
      """;

  /** 跨法人调拨单列表 + 全量汇总。 */
  public LegalEntityTransferPage page(LegalEntityTransferQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + from("") + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            ROW_SELECT + from("") + aggJoin("") + sqlWhere + order(query) + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new LegalEntityTransferPage(
        summary(query),
        currencyAmounts(query),
        rows.stream().map(LegalEntityTransferService::row).toList(),
        statusOptions(),
        valuationOptions(),
        legalEntityOptions(),
        warehouseOptions(),
        total,
        pageNumber,
        size);
  }

  /** 列表汇总。**全量**，不受分页影响。 */
  private LegalEntityTransferSummary summary(LegalEntityTransferQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Record r =
        sql.fetchOne(
            "select count(*) as transfer_count,"
                + " coalesce(sum(agg.line_count),0) as line_count,"
                + " coalesce(sum(agg.total_quantity),0) as total_quantity,"
                + " coalesce(sum(agg.received_quantity),0) as received_quantity,"
                + " coalesce(sum(agg.discrepancy_quantity),0) as discrepancy_quantity,"
                + " coalesce(sum(agg.in_transit),0) as in_transit_quantity,"
                + " count(*) filter (where o.status='DRAFT') as draft_count,"
                + " count(*) filter (where o.status='PENDING_SOURCE_CONFIRM') as pending_source,"
                + " count(*) filter (where o.status='PENDING_TARGET_CONFIRM') as pending_target_status,"
                + " count(*) filter (where o.status='CONFIRMED') as confirmed_count,"
                + " count(*) filter (where o.status='IN_TRANSIT') as in_transit_count,"
                + " count(*) filter (where o.status='COMPLETED') as completed_count,"
                + " count(*) filter (where o.status='DISCREPANCY') as discrepancy_count,"
                + " count(*) filter (where o.status='CANCELLED') as cancelled_count,"
                // 待对方确认：转出已确认但转入未确认（运营最该盯的一列）
                + " count(*) filter (where o.source_confirmed_at is not null"
                + " and o.target_confirmed_at is null) as awaiting_target,"
                + " count(*) filter (where o.source_confirmed_at is null) as awaiting_source,"
                + " count(*) filter (where s.status='ISSUED') as settlement_issued,"
                + " count(*) filter (where s.status='ACKNOWLEDGED') as settlement_acknowledged,"
                + " min(o.expected_arrive_at) as earliest_expected_arrive_at"
                + from("")
                + aggJoin("")
                + sqlWhere,
            binds.toArray());

    long awaitingBoth =
        longOf(r, "pending_target_status")
            + longOf(r, "confirmed_count")
            + longOf(r, "in_transit_count")
            + longOf(r, "completed_count")
            + longOf(r, "discrepancy_count");

    return new LegalEntityTransferSummary(
        longOf(r, "transfer_count"),
        longOf(r, "line_count"),
        r.get("total_quantity", BigDecimal.class),
        r.get("received_quantity", BigDecimal.class),
        r.get("discrepancy_quantity", BigDecimal.class),
        r.get("in_transit_quantity", BigDecimal.class),
        longOf(r, "draft_count"),
        longOf(r, "pending_source"),
        longOf(r, "pending_target_status"),
        longOf(r, "confirmed_count"),
        longOf(r, "in_transit_count"),
        longOf(r, "completed_count"),
        longOf(r, "discrepancy_count"),
        longOf(r, "cancelled_count"),
        awaitingBoth,
        longOf(r, "awaiting_target"),
        longOf(r, "awaiting_source"),
        longOf(r, "settlement_issued"),
        longOf(r, "settlement_acknowledged"),
        r.get("earliest_expected_arrive_at", OffsetDateTime.class));
  }

  /**
   * 按币种分组的金额合计。
   *
   * <p>整单单币种，但列表会跨单 —— 跨币种直接相加是错的（USD 100 + CNY 100 不是 200）， 所以这里按币种分层给出，界面上必须分行显示。
   */
  private List<LegalEntityTransferCurrencyAmount> currencyAmounts(LegalEntityTransferQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select o.currency_code,sum(o.total_amount) as amount,count(*) as cnt"
                + from("")
                + sqlWhere
                + " group by o.currency_code order by o.currency_code",
            binds.toArray())
        .map(
            r ->
                new LegalEntityTransferCurrencyAmount(
                    r.get("currency_code", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Long.class)));
  }

  private static long longOf(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private void conditions(
      LegalEntityTransferQuery q, String a, List<String> where, List<Object> binds) {
    if (q.fromLegalEntityId() != null) {
      where.add("o" + a + ".from_legal_entity_id=?");
      binds.add(q.fromLegalEntityId());
    }
    if (q.toLegalEntityId() != null) {
      where.add("o" + a + ".to_legal_entity_id=?");
      binds.add(q.toLegalEntityId());
    }
    if (q.fromWarehouseId() != null) {
      where.add("o" + a + ".from_warehouse_id=?");
      binds.add(q.fromWarehouseId());
    }
    if (q.toWarehouseId() != null) {
      where.add("o" + a + ".to_warehouse_id=?");
      binds.add(q.toWarehouseId());
    }
    if (q.status() != null && !q.status().isEmpty()) {
      var marks = new StringBuilder();
      for (var s : q.status()) {
        marks.append(marks.length() == 0 ? "" : ",").append("?");
        binds.add(s.toUpperCase(Locale.ROOT));
      }
      where.add("o" + a + ".status in (" + marks + ")");
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      var kw = "%" + q.keyword().trim() + "%";
      where.add(
          "(o"
              + a
              + ".transfer_code ilike ? or o"
              + a
              + ".reason ilike ? or exists(select 1 from cross_entity_transfer_line kl"
              + a
              + " where kl.tenant_id=o"
              + a
              + ".tenant_id and kl.transfer_id=o"
              + a
              + ".id and exists(select 1 from sku_ref ks where ks.tenant_id=kl"
              + a
              + ".tenant_id and ks.id=kl"
              + a
              + ".sku_id and (ks.code ilike ? or ks.name ilike ?))))");
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    if (q.skuId() != null) {
      where.add(
          "exists(select 1 from cross_entity_transfer_line sl where sl.tenant_id=o"
              + a
              + ".tenant_id and sl.transfer_id=o"
              + a
              + ".id and sl.sku_id=?)");
      binds.add(q.skuId());
    }
  }

  private static String order(LegalEntityTransferQuery q) {
    String sort = q.sort() == null ? "CREATED_AT" : q.sort().toUpperCase(Locale.ROOT);
    return switch (sort) {
      case "UPDATED_AT" -> " order by o.updated_at desc nulls last,o.id";
      case "TRANSFER_CODE" -> " order by o.transfer_code desc";
      case "TOTAL_AMOUNT" -> " order by o.total_amount desc nulls last,o.id";
      case "EXPECTED_ARRIVE" -> " order by o.expected_arrive_at asc nulls last,o.id";
      case "CREATED_AT" -> " order by o.created_at desc,o.id";
      default ->
          throw new IllegalArgumentException(
              "不支持的排序：" + q.sort() + "，可选：" + String.join("、", SORTS));
    };
  }

  // ================================================================ 下拉项

  private List<LegalEntityTransferOption> statusOptions() {
    return sql.fetch(
            "select o.status,count(*) as cnt from cross_entity_transfer o group by o.status order by o.status")
        .map(
            r -> {
              String raw = r.get("status", String.class);
              return new LegalEntityTransferOption(
                  raw, LegalEntityTransferStatus.parse(raw).label(), r.get("cnt", Long.class));
            });
  }

  private List<LegalEntityTransferOption> valuationOptions() {
    return java.util.Arrays.stream(LegalEntityTransferValuationBasis.values())
        .map(b -> new LegalEntityTransferOption(b.name(), b.label(), 0L))
        .toList();
  }

  private List<LegalEntityTransferLegalEntityOption> legalEntityOptions() {
    return sql.fetch(
            "select id,code,name from legal_entity_ref where status='ACTIVE' order by code")
        .map(
            r ->
                new LegalEntityTransferLegalEntityOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class)));
  }

  private List<LegalEntityTransferWarehouseOption> warehouseOptions() {
    return sql.fetch("select id,code,name from warehouse where status<>'ARCHIVED' order by code")
        .map(
            r ->
                new LegalEntityTransferWarehouseOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class)));
  }

  // ================================================================ 详情

  /** 调拨单详情：头 + 明细 + 双方确认 + 结算凭据。跨租户在 RLS 下查不到 → 404。 */
  public LegalEntityTransferDetail detail(UUID id) {
    Record head = requireTransfer(id);
    var settlement = settlementRow(head);
    return new LegalEntityTransferDetail(
        row(head),
        linesOf(id),
        approvalsOf(id),
        settlement == null ? null : settlement(settlement),
        PRICING_NOTE,
        OWNERSHIP_NOTE,
        AUTHORIZATION_NOTE);
  }

  /**
   * 内部结算凭据的对外取数形状 —— 给 12 财务的交接面。
   *
   * <p>12 按 {@code (sourceSystem, referenceId)} 反查。凭据不存在时返回 404 而不是空壳： 「还没发运、因此还没有凭据」和「凭据存在但金额为
   * 0」必须能分开。
   */
  public LegalEntityTransferSettlementHandoff settlement(UUID id) {
    Record head = requireTransfer(id);
    Record s = settlementRow(head);
    if (s == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该跨法人调拨单尚未开具内部结算凭据（凭据在发出时开具）");
    }
    var lines =
        sql.fetch(
                "select l.sku_id,k.code as sku_code,l.quantity,l.unit_price,l.amount,"
                    + "l.received_quantity,l.discrepancy_quantity"
                    + " from cross_entity_transfer_line l"
                    + " join sku_ref k on k.tenant_id=l.tenant_id and k.id=l.sku_id"
                    + " where l.tenant_id=? and l.transfer_id=? order by k.code",
                head.get("head_tenant_id", UUID.class),
                id)
            .map(
                r ->
                    new LegalEntityTransferSettlementLine(
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("quantity", BigDecimal.class),
                        r.get("unit_price", BigDecimal.class),
                        r.get("amount", BigDecimal.class),
                        r.get("received_quantity", BigDecimal.class),
                        r.get("discrepancy_quantity", BigDecimal.class)));
    return new LegalEntityTransferSettlementHandoff(
        s.get("source_system", String.class),
        s.get("reference_id", UUID.class),
        "CROSS_ENTITY_TRANSFER",
        head.get("transfer_code", String.class),
        s.get("from_legal_entity_id", UUID.class),
        s.get("to_legal_entity_id", UUID.class),
        s.get("currency_code", String.class),
        s.get("amount", BigDecimal.class),
        s.get("issued_at", OffsetDateTime.class),
        s.get("status", String.class),
        lines,
        "按 (sourceSystem, referenceId) 反查：一张跨法人调拨单只对应一张凭据（UNIQUE 约束保证不会重复计提）。"
            + "金额为不含税的转移定价金额，税额、科目与凭证行属 12 财务自有权威，本服务不提供也不复制。"
            + "discrepancyQuantity 大于 0 表示该行发出后有短少，据此的差额处理是财务判断。");
  }

  private List<LegalEntityTransferLineRow> linesOf(UUID transferId) {
    return sql.fetch(
            "select l.id,l.sku_id,k.code as sku_code,k.name as sku_name,"
                + "l.quality_status_id,q.code as quality_code,q.name as quality_name,"
                + "l.quantity,l.unit_price,l.amount,l.received_quantity,l.discrepancy_quantity,"
                + "coalesce((select sum(i.quantity) from inventory_transfer i"
                + " where i.tenant_id=l.tenant_id and i.ref_type='CROSS_ENTITY_TRANSFER'"
                + " and i.ref_id=l.transfer_id and i.status='IN_TRANSIT'),0) as in_transit,"
                + "l.version"
                + " from cross_entity_transfer_line l"
                + " join sku_ref k on k.tenant_id=l.tenant_id and k.id=l.sku_id"
                + " join inventory_quality_status q on q.tenant_id=l.tenant_id"
                + " and q.id=l.quality_status_id"
                + " where l.tenant_id=current_setting('app.current_tenant',true)::uuid"
                + " and l.transfer_id=? order by k.code",
            transferId)
        .map(
            r ->
                new LegalEntityTransferLineRow(
                    r.get("id", UUID.class),
                    r.get("sku_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("sku_name", String.class),
                    r.get("quality_status_id", UUID.class),
                    r.get("quality_code", String.class),
                    r.get("quality_name", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("unit_price", BigDecimal.class),
                    r.get("amount", BigDecimal.class),
                    r.get("received_quantity", BigDecimal.class),
                    r.get("discrepancy_quantity", BigDecimal.class),
                    r.get("in_transit", BigDecimal.class),
                    r.get("version", Long.class)));
  }

  private List<LegalEntityTransferApprovalRow> approvalsOf(UUID transferId) {
    return sql.fetch(
            "select side,approved_by,approved_at,notes from cross_entity_approval"
                + " where tenant_id=current_setting('app.current_tenant',true)::uuid"
                + " and transfer_id=? order by approved_at",
            transferId)
        .map(
            r ->
                new LegalEntityTransferApprovalRow(
                    r.get("side", String.class),
                    r.get("approved_by", UUID.class),
                    r.get("approved_at", OffsetDateTime.class),
                    r.get("notes", String.class)));
  }

  private Record settlementRow(Record head) {
    return sql.fetchOne(
        "select s.id,s.settlement_code,s.transfer_id,s.source_system,s.reference_id,"
            + "s.from_legal_entity_id,fle.code as from_legal_entity_code,"
            + "s.to_legal_entity_id,tle.code as to_legal_entity_code,"
            + "s.currency_code,s.amount,s.status,s.issued_at,s.acknowledged_at,s.acknowledged_by"
            + " from cross_entity_settlement s"
            + " join legal_entity_ref fle on fle.tenant_id=s.tenant_id"
            + " and fle.id=s.from_legal_entity_id"
            + " join legal_entity_ref tle on tle.tenant_id=s.tenant_id"
            + " and tle.id=s.to_legal_entity_id"
            + " where s.tenant_id=? and s.transfer_id=?",
        head.get("head_tenant_id", UUID.class),
        head.get("transfer_id", UUID.class));
  }

  private static LegalEntityTransferSettlementRow settlement(Record r) {
    return new LegalEntityTransferSettlementRow(
        r.get("id", UUID.class),
        r.get("settlement_code", String.class),
        r.get("transfer_id", UUID.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        r.get("from_legal_entity_id", UUID.class),
        r.get("from_legal_entity_code", String.class),
        r.get("to_legal_entity_id", UUID.class),
        r.get("to_legal_entity_code", String.class),
        r.get("currency_code", String.class),
        r.get("amount", BigDecimal.class),
        r.get("status", String.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by", UUID.class));
  }

  // ================================================================ 建单 / 改单

  /** 建单（草稿）。定价必填：没有单价就没有内部结算凭据。 */
  public LegalEntityTransferSaved create(
      UUID tenantId, UUID actorId, LegalEntityTransferCreateRequest req) {
    requireTwoWarehouses(req.fromWarehouseId(), req.toWarehouseId());
    requireTwoLegalEntities(req.fromLegalEntityId(), req.toLegalEntityId());
    String currency = currency(req.currencyCode());
    String basis =
        LegalEntityTransferValuationBasis.parse(orDefault(req.valuationBasis(), "MANUAL")).name();
    var lines = req.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("跨法人调拨单至少要有一条明细（要转移什么货）");
    }

    UUID id = UUID.randomUUID();
    BigDecimal total = BigDecimal.ZERO;
    sql.execute(
        "insert into cross_entity_transfer(id,tenant_id,transfer_code,from_warehouse_id,"
            + "to_warehouse_id,from_legal_entity_id,to_legal_entity_id,valuation_basis,currency_code,"
            + "total_amount,expected_ship_at,expected_arrive_at,reason,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,0,cast(? as timestamptz),cast(? as timestamptz),?,?,?)",
        id,
        tenantId,
        nextCode("cross_entity_transfer_code_seq", "CET"),
        req.fromWarehouseId(),
        req.toWarehouseId(),
        req.fromLegalEntityId(),
        req.toLegalEntityId(),
        basis,
        currency,
        req.expectedShipAt(),
        req.expectedArriveAt(),
        fit(req.reason(), 200, "调拨原因"),
        fit(req.notes(), 2000, "备注"),
        actorId);

    for (var line : lines) {
      total = total.add(insertLine(tenantId, id, line));
    }
    syncTotal(id, total);
    return savedOf(id);
  }

  private BigDecimal insertLine(
      UUID tenantId, UUID transferId, LegalEntityTransferLineRequest line) {
    if (line.skuId() == null) {
      throw new IllegalArgumentException("明细行必须指定 SKU");
    }
    if (line.qualityStatusId() == null) {
      throw new IllegalArgumentException("明细行必须指定质量状态");
    }
    requireSku(tenantId, line.skuId());
    requireQualityStatus(tenantId, line.qualityStatusId());
    BigDecimal quantity = scale4(positive(line.quantity(), "调拨数量"));
    BigDecimal unitPrice = scale4(nonNegative(line.unitPrice(), "转移单价"));
    sql.execute(
        "insert into cross_entity_transfer_line(id,tenant_id,transfer_id,sku_id,quality_status_id,"
            + "quantity,unit_price) values(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        transferId,
        line.skuId(),
        line.qualityStatusId(),
        quantity,
        unitPrice);
    return quantity.multiply(unitPrice).setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** 改单：仅草稿可改，整份替换明细与定价。发出之后一行都不让改。 */
  public LegalEntityTransferSaved update(
      UUID id, UUID actorId, LegalEntityTransferUpdateRequest req) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    requireStatus(head, LegalEntityTransferStatus.DRAFT, "只有草稿状态的跨法人调拨单可以修改");
    String currency =
        currency(
            req.currencyCode() == null
                ? head.get("currency_code", String.class)
                : req.currencyCode());
    String basis =
        req.valuationBasis() == null
            ? head.get("valuation_basis", String.class)
            : LegalEntityTransferValuationBasis.parse(req.valuationBasis()).name();
    var lines = req.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("跨法人调拨单至少要保留一条明细");
    }
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    // 改单只可能发生在草稿（没有任何货权流水），因此直接整份替换明细是安全的
    sql.execute(
        "delete from cross_entity_transfer_line where tenant_id=? and transfer_id=?", tenantId, id);
    BigDecimal total = BigDecimal.ZERO;
    for (var line : lines) {
      total = total.add(insertLine(tenantId, id, line));
    }
    sql.execute(
        "update cross_entity_transfer set valuation_basis=?,currency_code=?,total_amount=?,"
            + "expected_ship_at=cast(? as timestamptz),expected_arrive_at=cast(? as timestamptz),"
            + "reason=?,notes=?,version=version+1,updated_at=now() where id=?",
        basis,
        currency,
        total,
        req.expectedShipAt(),
        req.expectedArriveAt(),
        fit(req.reason(), 200, "调拨原因"),
        fit(req.notes(), 2000, "备注"),
        id);
    return savedOf(id);
  }

  // ================================================================ 双方确认

  /**
   * 转出方确认（草稿 → 待转入方确认）。
   *
   * <p>这是**两个不同的调用**，不是一个接口里传个布尔值：转出方与转入方是两个法人， 一个接口同时改两边等于让一方替另一方签字。
   */
  public LegalEntityTransferSaved sourceConfirm(
      UUID id, UUID actorId, LegalEntityTransferApproveRequest req) {
    return confirm(id, actorId, req, LegalEntityTransferSide.SOURCE);
  }

  /** 转入方确认（待转入方确认 → 双方已确认）。 */
  public LegalEntityTransferSaved targetConfirm(
      UUID id, UUID actorId, LegalEntityTransferApproveRequest req) {
    return confirm(id, actorId, req, LegalEntityTransferSide.TARGET);
  }

  private LegalEntityTransferSaved confirm(
      UUID id, UUID actorId, LegalEntityTransferApproveRequest req, LegalEntityTransferSide side) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    // 重复确认的判定放在状态判定**之前**：状态检查会先抛一个 409「当前状态不许确认」，
    // 而调用方真正要知道的是「这张单你已经签过了」——后者才是可行动的信息。
    if (sql.fetchOne(
            "select id from cross_entity_approval where tenant_id=? and transfer_id=? and side=?",
            tenantId,
            id,
            side.name())
        != null) {
      throw new IllegalArgumentException(side.label() + "已经确认过这张单了，不能重复确认");
    }
    String status = head.get("status", String.class);
    if (side == LegalEntityTransferSide.SOURCE) {
      requireStatus(head, LegalEntityTransferStatus.DRAFT, "只有草稿状态的跨法人调拨单可以由转出方确认");
    } else {
      requireStatus(
          head,
          LegalEntityTransferStatus.PENDING_TARGET_CONFIRM,
          "只有「待转入方确认」的单可以由转入方确认（转出方还没确认的话不能越级）");
    }
    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "insert into cross_entity_approval(id,tenant_id,transfer_id,side,approved_by,approved_at,notes)"
            + " values(?,?,?,?,?,cast(? as timestamptz),?)",
        UUID.randomUUID(),
        tenantId,
        id,
        side.name(),
        actorId,
        at,
        fit(req.notes(), 2000, "确认备注"));

    String next =
        side == LegalEntityTransferSide.SOURCE
            ? LegalEntityTransferStatus.PENDING_TARGET_CONFIRM.name()
            : LegalEntityTransferStatus.CONFIRMED.name();
    String stamp =
        side == LegalEntityTransferSide.SOURCE ? "source_confirmed_at" : "target_confirmed_at";
    sql.execute(
        "update cross_entity_transfer set status=?,"
            + stamp
            + "=cast(? as timestamptz),version=version+1,updated_at=now() where id=?",
        next,
        at,
        id);
    return savedOf(id);
  }

  // ================================================================ 发出：转出方出库 + 开具结算凭据

  /**
   * 发出：扣<b>转出法人</b>的余额、挂上在途、开具内部结算凭据。
   *
   * <p>整单发运（不拆批次）：转移定价是整单口径，部分发运会让「一张凭据对应哪几行货」 失去唯一答案，而凭据正是 12 财务的取数入口。
   *
   * <p>这里<b>不动</b>转入法人的余额 —— 货还没到，货权也还没转移（见类注释）。
   */
  public LegalEntityTransferSaved ship(UUID id, UUID actorId, LegalEntityTransferShipRequest req) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    requireStatus(head, LegalEntityTransferStatus.CONFIRMED, "只有「双方已确认」的单可以发出");
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    UUID fromWarehouse = head.get("from_warehouse_id", UUID.class);
    UUID fromEntity = head.get("from_legal_entity_id", UUID.class);
    OffsetDateTime at = req.shippedAt() == null ? OffsetDateTime.now() : req.shippedAt();

    var lines =
        sql.fetch(
            "select id,sku_id,quality_status_id,quantity from cross_entity_transfer_line"
                + " where tenant_id=? and transfer_id=? order by id for update",
            tenantId,
            id);
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("该跨法人调拨单没有明细，无法发出");
    }

    // 发货事件 id：inventory_transaction 的 ref_id 必须按**事件**唯一
    // （V1 的 UNIQUE(tenant_id,ref_type,ref_id) 挡不住同一 ref_id 上既有发出又有实收两条分录），
    // 而 inventory_transfer 的在途腿 ref_id 记**单据** id —— 只有这样才能一次问出「这张单还有多少在路上」。
    UUID shipmentId = UUID.randomUUID();
    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
            + " values(?,?,'TRANSFER_OUT','CROSS_ENTITY_TRANSFER',?,cast(? as timestamptz),?,?)",
        transactionId,
        tenantId,
        shipmentId,
        at,
        actorId,
        "跨法人调拨发出：货权仍属转出法人，货进入在途");

    for (var l : lines) {
      BigDecimal qty = l.get("quantity", BigDecimal.class);
      // 先确认转出法人名下**真的有**这条余额行再动它。
      // 少这一步的话 balanceId 会现建一条 0 余额，报出来的是「可用量不足：可用 0」——
      // 而真实原因是「这批货不归转出法人」，两句话会把排查引到完全不同的地方。
      requireSourceBalance(
          tenantId,
          l.get("sku_id", UUID.class),
          fromWarehouse,
          fromEntity,
          l.get("quality_status_id", UUID.class));
      UUID balanceId =
          balanceId(
              tenantId,
              l.get("sku_id", UUID.class),
              fromWarehouse,
              fromEntity,
              l.get("quality_status_id", UUID.class));
      deductSource(balanceId, qty, at);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(),
          tenantId,
          transactionId,
          balanceId,
          qty.negate());
      // 在途行的 legal_entity_id 记**转出方**：货权尚未转移。这条口径与 7.1.1.1 /
      // 7.2.2.1 / 7.1.3.1 / 7.5.1.2 的 sum(quantity) where status='IN_TRANSIT' 一致，
      // 本模块的在途货不会在别的模块里凭空消失。
      sql.execute(
          "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
              + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at)"
              + " values(?,?,?,?,?,?,?,?,'IN_TRANSIT','CROSS_ENTITY_TRANSFER',?,cast(? as timestamptz))",
          UUID.randomUUID(),
          tenantId,
          l.get("sku_id", UUID.class),
          fromEntity,
          fromWarehouse,
          head.get("to_warehouse_id", UUID.class),
          l.get("quality_status_id", UUID.class),
          qty,
          id,
          at);
    }

    issueSettlement(head, at);
    sql.execute(
        "update cross_entity_transfer set status='IN_TRANSIT',shipped_at=cast(? as timestamptz),"
            + "version=version+1,updated_at=now() where id=?",
        at,
        id);
    return savedOf(id);
  }

  /**
   * 开具内部结算凭据（一张单只开一张）。
   *
   * <p>金额取自单头合计 —— 而合计由迁移的延迟约束触发器与明细逐行之和钉死， 因此凭据金额与逐行金额必然对得上，12 那边复算时不会出现「总额与明细不符」。
   */
  private void issueSettlement(Record head, OffsetDateTime at) {
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    UUID transferId = head.get("transfer_id", UUID.class);
    sql.execute(
        "insert into cross_entity_settlement(id,tenant_id,settlement_code,transfer_id,source_system,"
            + "reference_id,from_legal_entity_id,to_legal_entity_id,currency_code,amount,status,issued_at)"
            + " values(?,?,?,?,'warehouse',?,?,?,?,?,'ISSUED',cast(? as timestamptz))",
        UUID.randomUUID(),
        tenantId,
        nextCode("cross_entity_settlement_code_seq", "CES"),
        transferId,
        transferId,
        head.get("from_legal_entity_id", UUID.class),
        head.get("to_legal_entity_id", UUID.class),
        head.get("currency_code", String.class),
        head.get("total_amount", BigDecimal.class),
        at);
  }

  // ================================================================ 实收：货权转到转入法人

  /**
   * 实收：把货加进<b>转入法人</b>的余额行、冲减在途、确认结算凭据。
   *
   * <p>「加进转入法人的余额行」就是货权转移本身（V1 第 4 节余额维度含 legal_entity_id）， 不是把转出方的余额搬过来 ——
   * 那是同仓调拨的做法，在本模块里会让利润归属留在转出方。
   */
  public LegalEntityTransferSaved receive(
      UUID id, UUID actorId, LegalEntityTransferReceiveRequest req) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    requireStatus(head, LegalEntityTransferStatus.IN_TRANSIT, "只有在途的跨法人调拨单可以实收");
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    UUID toWarehouse = head.get("to_warehouse_id", UUID.class);
    UUID toEntity = head.get("to_legal_entity_id", UUID.class);
    OffsetDateTime at = req.receivedAt() == null ? OffsetDateTime.now() : req.receivedAt();
    if (req.locationId() == null) {
      throw new IllegalArgumentException("实收必须指定落库库位（货到了哪个库位是收货单据必须回答的问题）");
    }
    requireLocation(tenantId, req.locationId(), toWarehouse);
    if (req.lotId() != null
        && sql.fetchOne(
                "select id from inventory_lot where id=? and tenant_id=?", req.lotId(), tenantId)
            == null) {
      throw new IllegalArgumentException("批次不存在或无权访问：" + req.lotId());
    }
    var reqLines = req.lines();
    if (reqLines == null || reqLines.isEmpty()) {
      throw new IllegalArgumentException("本次实收没有指定任何明细行");
    }

    UUID receiptId = UUID.randomUUID();
    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
            + " values(?,?,'TRANSFER_IN','CROSS_ENTITY_TRANSFER',?,cast(? as timestamptz),?,?)",
        transactionId,
        tenantId,
        receiptId,
        at,
        actorId,
        "跨法人调拨实收：货权由转出法人转到转入法人");

    for (var rl : reqLines) {
      BigDecimal qty = scale4(positive(rl.quantity(), "本次实收数量"));
      Record l =
          sql.fetchOne(
              "select id,sku_id,quality_status_id,quantity,received_quantity"
                  + " from cross_entity_transfer_line where tenant_id=? and transfer_id=? and id=?",
              tenantId,
              id,
              rl.lineId());
      if (l == null) {
        throw new IllegalArgumentException("实收引用了不属于本单的明细行：" + rl.lineId());
      }
      UUID lineId = l.get("id", UUID.class);
      // 在途余量按**行**判定：多行时用整单余量会让第一行把整单在途都吃掉，
      // 第二行就能收走别人的货。
      BigDecimal outstanding = inTransitOfLine(id, lineId);
      if (qty.compareTo(outstanding) > 0) {
        throw new IllegalArgumentException(
            "本次实收数量超过该行仍在途的数量（在途 " + outstanding + "，本次 " + qty + "）");
      }
      UUID balanceId =
          balanceId(
              tenantId,
              l.get("sku_id", UUID.class),
              toWarehouse,
              toEntity,
              l.get("quality_status_id", UUID.class));
      addDestination(balanceId, req.locationId(), req.lotId(), qty, at);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(),
          tenantId,
          transactionId,
          balanceId,
          qty);
      consumeInTransit(id, lineId, qty, at, receiptId);
      sql.execute(
          "update cross_entity_transfer_line set received_quantity=received_quantity+?,"
              + "version=version+1,updated_at=now() where id=?",
          qty,
          lineId);
    }

    syncReceivedAt(id, at);
    return finishReceive(id, actorId, at);
  }

  /** 全部收完 → 已交割并确认凭据；仍有差额 → 留在途（可再收）。 */
  private LegalEntityTransferSaved finishReceive(UUID id, UUID actorId, OffsetDateTime at) {
    BigDecimal outstanding = inTransitOf(id);
    if (outstanding.signum() > 0) {
      return savedOf(id);
    }
    UUID tenantId = requireTenantOf(id);
    sql.execute(
        "update cross_entity_transfer set status='COMPLETED',completed_at=cast(? as timestamptz),"
            + "version=version+1,updated_at=now() where id=?",
        at,
        id);
    sql.execute(
        "update cross_entity_settlement set status='ACKNOWLEDGED',"
            + "acknowledged_at=cast(? as timestamptz),acknowledged_by=?,updated_at=now()"
            + " where tenant_id=? and transfer_id=? and status='ISSUED'",
        at,
        actorId,
        tenantId,
        id);
    return savedOf(id);
  }

  // ================================================================ 结案 / 作废

  /**
   * 结案：把发出过但收不到的部分如实记为差异，在途归零。
   *
   * <p>结算凭据**不**作废：部分货权确实转移了，短少量逐行带给 12 财务， 差额怎么处理是财务判断。本服务只如实摆出差额。
   */
  public LegalEntityTransferSaved close(
      UUID id, UUID actorId, LegalEntityTransferCloseRequest req) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    requireStatus(head, LegalEntityTransferStatus.IN_TRANSIT, "只有在途的跨法人调拨单可以结案");
    UUID tenantId = head.get("head_tenant_id", UUID.class);
    OffsetDateTime at = OffsetDateTime.now();

    var lines =
        sql.fetch(
            "select id,quantity,received_quantity from cross_entity_transfer_line"
                + " where tenant_id=? and transfer_id=? order by id",
            tenantId,
            id);
    for (var l : lines) {
      BigDecimal received = l.get("received_quantity", BigDecimal.class);
      BigDecimal transit = inTransitOfLine(id, l.get("id", UUID.class));
      if (transit.signum() <= 0) {
        continue;
      }
      sql.execute(
          "update cross_entity_transfer_line set discrepancy_quantity=discrepancy_quantity+?,"
              + "version=version+1,updated_at=now() where id=?",
          transit,
          l.get("id", UUID.class));
      cancelInTransit(id, l.get("id", UUID.class), transit, at, "结案确认短少");
    }

    boolean hasDiscrepancy =
        sql.fetchOne(
                "select 1 from cross_entity_transfer_line where tenant_id=? and transfer_id=?"
                    + " and discrepancy_quantity>0 limit 1",
                tenantId,
                id)
            != null;
    String status =
        hasDiscrepancy
            ? LegalEntityTransferStatus.DISCREPANCY.name()
            : LegalEntityTransferStatus.COMPLETED.name();
    sql.execute(
        "update cross_entity_transfer set status=?,completed_at=cast(? as timestamptz),"
            + "version=version+1,updated_at=now() where id=?",
        status,
        at,
        id);
    if (!hasDiscrepancy) {
      sql.execute(
          "update cross_entity_settlement set status='ACKNOWLEDGED',"
              + "acknowledged_at=cast(? as timestamptz),acknowledged_by=?,updated_at=now()"
              + " where tenant_id=? and transfer_id=? and status='ISSUED'",
          at,
          actorId,
          tenantId,
          id);
    }
    return savedOf(id);
  }

  /** 作废：仅在**发运之前**可用。已发运的单只能结案（差异如实记录），不能一笔抹掉。 */
  public LegalEntityTransferSaved cancel(
      UUID id, UUID actorId, LegalEntityTransferCancelRequest req) {
    Record head = lockTransfer(id);
    requireVersion(head, req.expectedVersion());
    String status = head.get("status", String.class);
    if (status.equals(LegalEntityTransferStatus.IN_TRANSIT.name())
        || status.equals(LegalEntityTransferStatus.COMPLETED.name())
        || status.equals(LegalEntityTransferStatus.DISCREPANCY.name())) {
      throw statusError(status, "已发运的单不能作废，只能结案（短少如实记为差异）");
    }
    if (req.reason() == null || req.reason().isBlank()) {
      throw new IllegalArgumentException("作废必须写明原因");
    }
    sql.execute(
        "update cross_entity_transfer set status='CANCELLED',completed_at=cast(? as timestamptz),"
            + "version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        id);
    return savedOf(id);
  }

  // ================================================================ 库存动作

  /** 从转出方余额扣减，并同步扣减库位明细。可用量不足直接报错。 */
  private void deductSource(UUID balanceId, BigDecimal qty, OffsetDateTime at) {
    Record bal =
        sql.fetchOne(
            "select on_hand,reserved,available from inventory_balance where id=?", balanceId);
    if (bal == null) {
      throw new IllegalArgumentException("转出法人没有这条库存余额，无法调拨（货可能还没入库，或这批货不归该法人）");
    }
    BigDecimal available = bal.get("available", BigDecimal.class);
    if (qty.compareTo(available) > 0) {
      throw new IllegalArgumentException(
          "转出方可用量不足：可用 " + available + "，本次发出 " + qty + "（已被预占的数量不能调走）");
    }
    var buckets =
        sql.fetch(
            "select id,available from stock_bucket where balance_id=? and available>0"
                + " order by last_movement_at,id for update",
            balanceId);
    if (buckets.isEmpty()) {
      sql.execute(
          "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
              + " where id=?",
          qty,
          balanceId);
      return;
    }
    BigDecimal left = qty;
    for (var b : buckets) {
      if (left.signum() <= 0) break;
      BigDecimal take = b.get("available", BigDecimal.class).min(left);
      sql.execute(
          "update stock_bucket set on_hand=on_hand-?,"
              + "last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          take,
          at,
          b.get("id", UUID.class));
      left = left.subtract(take);
    }
    if (left.signum() > 0) {
      throw new IllegalArgumentException("转出方库位可用量与余额对不上，请先做一次账实核对");
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty,
        balanceId);
  }

  /** 转入方入库：加余额 + 同步加库位明细（V2 守恒）。余额行落在**转入法人**名下。 */
  private void addDestination(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=? and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + "last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty,
          at,
          existing.get("id", UUID.class));
    } else {
      Record bal = sql.fetchOne("select * from inventory_balance where id=?", balanceId);
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,0,cast(? as timestamptz))",
          UUID.randomUUID(),
          bal.get("tenant_id", UUID.class),
          bal.get("legal_entity_id", UUID.class),
          bal.get("sku_id", UUID.class),
          bal.get("warehouse_id", UUID.class),
          locationId,
          lotId,
          bal.get("quality_status_id", UUID.class),
          balanceId,
          qty,
          at);
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand+?,version=version+1,updated_at=now()"
            + " where id=?",
        qty,
        balanceId);
  }

  /** 整单在途余量。与其他模块同口径（sum where status='IN_TRANSIT'）。 */
  private BigDecimal inTransitOf(UUID transferId) {
    return sql.fetchOne(
            "select coalesce(sum(quantity),0) from inventory_transfer"
                + " where ref_type='CROSS_ENTITY_TRANSFER' and ref_id=? and status='IN_TRANSIT'",
            transferId)
        .get(0, BigDecimal.class);
  }

  /**
   * 单行在途余量。
   *
   * <p>本模块的在途行不带 {@code line_id}（那是 V61 调拨单自己的归属）， 因此按 sku × 质量状态 定位到具体明细行。
   */
  private BigDecimal inTransitOfLine(UUID transferId, UUID lineId) {
    return sql.fetchOne(
            "select coalesce(sum(i.quantity),0) from inventory_transfer i"
                + " join cross_entity_transfer_line l"
                + " on l.tenant_id=i.tenant_id and l.transfer_id=i.ref_id"
                + " and l.sku_id=i.sku_id and l.quality_status_id=i.quality_status_id"
                + " where i.ref_type='CROSS_ENTITY_TRANSFER' and i.ref_id=? and i.status='IN_TRANSIT'"
                + " and l.id=?",
            transferId,
            lineId)
        .get(0, BigDecimal.class);
  }

  /**
   * 按「先发先收」冲减<b>某一行</b>的在途腿。
   *
   * <p>按行收尾而不是按整单收尾：多行时按整单收会让第一行把整单在途都吃掉。
   *
   * <p>V1 给 {@code inventory_transfer} 留了两条 CHECK，收尾方式由它们决定： {@code quantity>0} 不允许归零；{@code
   * (status='IN_TRANSIT')=(received_at IS NULL)} 要求 非在途状态必须带一个了结时刻。因此部分收货走「拆行」而不是「把余量改成 0」。
   */
  private void consumeInTransit(
      UUID transferId, UUID lineId, BigDecimal qty, OffsetDateTime at, UUID receiptId) {
    BigDecimal left = qty;
    var legs =
        sql.fetch(
            "select i.id,i.quantity from inventory_transfer i"
                + " join cross_entity_transfer_line l"
                + " on l.tenant_id=i.tenant_id and l.transfer_id=i.ref_id"
                + " and l.sku_id=i.sku_id and l.quality_status_id=i.quality_status_id"
                + " where i.ref_type='CROSS_ENTITY_TRANSFER' and i.ref_id=? and i.status='IN_TRANSIT'"
                + " and l.id=? order by i.shipped_at,i.id for update",
            transferId,
            lineId);
    for (var leg : legs) {
      if (left.signum() <= 0) break;
      BigDecimal have = leg.get("quantity", BigDecimal.class);
      BigDecimal take = have.min(left);
      BigDecimal rest = have.subtract(take);
      UUID legId = leg.get("id", UUID.class);
      if (rest.signum() == 0) {
        sql.execute(
            "update inventory_transfer set status='RECEIVED',"
                + "received_at=cast(? as timestamptz),updated_at=now() where id=?",
            at,
            legId);
      } else {
        sql.execute(
            "update inventory_transfer set quantity=?,updated_at=now() where id=?", rest, legId);
        // 已收到的那一段另起一行；ref_id 仍是**单据** id，保持「一次问出本单在途」的口径不变
        sql.execute(
            "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at,"
                + "received_at)"
                + " select ?,tenant_id,sku_id,legal_entity_id,from_warehouse_id,to_warehouse_id,"
                + "quality_status_id,?,'RECEIVED','CROSS_ENTITY_TRANSFER',?,shipped_at,"
                + "cast(? as timestamptz) from inventory_transfer where id=?",
            UUID.randomUUID(),
            take,
            transferId,
            at,
            legId);
      }
      left = left.subtract(take);
    }
  }

  /** 结案时把某行的在途腿整腿取消：数量保持发出量，状态带一个了结时刻。 */
  private void cancelInTransit(
      UUID transferId, UUID lineId, BigDecimal qty, OffsetDateTime at, String reason) {
    BigDecimal left = qty;
    var legs =
        sql.fetch(
            "select i.id,i.quantity from inventory_transfer i"
                + " join cross_entity_transfer_line l"
                + " on l.tenant_id=i.tenant_id and l.transfer_id=i.ref_id"
                + " and l.sku_id=i.sku_id and l.quality_status_id=i.quality_status_id"
                + " where i.ref_type='CROSS_ENTITY_TRANSFER' and i.ref_id=? and i.status='IN_TRANSIT'"
                + " and l.id=? order by i.shipped_at,i.id for update",
            transferId,
            lineId);
    for (var leg : legs) {
      if (left.signum() <= 0) break;
      BigDecimal have = leg.get("quantity", BigDecimal.class);
      BigDecimal take = have.min(left);
      BigDecimal rest = have.subtract(take);
      UUID legId = leg.get("id", UUID.class);
      if (rest.signum() == 0) {
        sql.execute(
            "update inventory_transfer set status='CANCELLED',"
                + "received_at=cast(? as timestamptz),updated_at=now() where id=?",
            at,
            legId);
      } else {
        sql.execute(
            "update inventory_transfer set quantity=?,updated_at=now() where id=?", rest, legId);
        sql.execute(
            "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at,"
                + "received_at)"
                + " select ?,tenant_id,sku_id,legal_entity_id,from_warehouse_id,to_warehouse_id,"
                + "quality_status_id,?,'CANCELLED','CROSS_ENTITY_TRANSFER',?,shipped_at,"
                + "cast(? as timestamptz) from inventory_transfer where id=?",
            UUID.randomUUID(),
            take,
            transferId,
            at,
            legId);
      }
      left = left.subtract(take);
    }
  }

  // ================================================================ 校验与工具

  private Record requireTransfer(UUID id) {
    var found = sql.fetchOne(ROW_SELECT + from("") + aggJoin("") + " where o" + ".id=?", id);
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跨法人调拨单不存在");
    }
    return found;
  }

  private Record lockTransfer(UUID id) {
    Record found = requireTransfer(id);
    // 行锁：双方是两个自然人，两边可能同时点确认/发运
    sql.fetchOne("select id from cross_entity_transfer where id=? for update", id);
    return found;
  }

  private UUID requireTenantOf(UUID id) {
    return sql.fetchOne("select tenant_id from cross_entity_transfer where id=?", id)
        .get("tenant_id", UUID.class);
  }

  private static void requireVersion(Record head, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion（并发保护：请回读最新版本再提交）");
    }
    long actual = head.get("version", Long.class);
    if (expected != actual) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "单据已被他人修改（当前版本 " + actual + "），请刷新后重试");
    }
  }

  private static void requireStatus(
      Record head, LegalEntityTransferStatus expected, String message) {
    String status = head.get("status", String.class);
    if (!status.equals(expected.name())) {
      throw statusError(status, message);
    }
  }

  private static ResponseStatusException statusError(String status, String message) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        message + "（当前状态：" + LegalEntityTransferStatus.parse(status).label() + "）");
  }

  private static void requireTwoWarehouses(UUID from, UUID to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("必须同时指定转出仓与转入仓");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException("转出仓与转入仓不能是同一个仓（同仓移动不产生货权转移）");
    }
  }

  private static void requireTwoLegalEntities(UUID from, UUID to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("必须同时指定转出法人与转入法人");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException(
          "转出法人与转入法人不能是同一个法人 —— 同一法人之间的转移是 7.5.1.1 普通调拨，" + "不需要定价与内部结算");
    }
  }

  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("必须指定币种（内部结算凭据没有币种就无法记账）");
    }
    String c = raw.trim().toUpperCase(Locale.ROOT);
    if (!c.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("币种必须是 ISO 4217 三字母代码，例如 CNY / USD");
    }
    return c;
  }

  private void requireSku(UUID tenantId, UUID skuId) {
    if (sql.fetchOne("select id from sku_ref where tenant_id=? and id=?", tenantId, skuId)
        == null) {
      throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
    }
  }

  private void requireQualityStatus(UUID tenantId, UUID id) {
    if (sql.fetchOne(
            "select id from inventory_quality_status where tenant_id=? and id=?", tenantId, id)
        == null) {
      throw new IllegalArgumentException("质量状态不存在或无权访问：" + id);
    }
  }

  private void requireLocation(UUID tenantId, UUID locationId, UUID warehouse) {
    if (sql.fetchOne(
            "select id from warehouse_location where tenant_id=? and id=? and warehouse_id=?",
            tenantId,
            locationId,
            warehouse)
        == null) {
      throw new IllegalArgumentException("库位不存在、不属于转入仓或无权访问：" + locationId);
    }
  }

  private void requireSourceBalance(
      UUID tenantId, UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    if (sql.fetchOne(
            "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            tenantId,
            skuId,
            warehouseId,
            legalEntityId,
            qualityStatusId)
        == null) {
      throw new IllegalArgumentException(
          "转出法人名下没有这条库存余额，无法调拨（货可能还没入库，或者这批货本来就不算转出法人的货——" + "货权不在转出方时调拨过去，货权就不会转移，本模块也就失去意义）");
    }
  }

  private UUID balanceId(
      UUID tenantId, UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    var found =
        sql.fetchOne(
            "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            tenantId,
            skuId,
            warehouseId,
            legalEntityId,
            qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0,0)",
        id,
        tenantId,
        skuId,
        warehouseId,
        legalEntityId,
        qualityStatusId);
    return id;
  }

  private void syncTotal(UUID id, BigDecimal total) {
    sql.execute(
        "update cross_entity_transfer set total_amount=?,version=version+1,updated_at=now()"
            + " where id=?",
        total.setScale(SCALE, RoundingMode.HALF_UP),
        id);
  }

  /**
   * 实收后推进 updated_at。
   *
   * <p>刻意<b>不</b>动 version：实收改的是明细行上的数量，单据头在「全部收完」时才动状态。 头版本跟着每一次分次实收跳会让乐观锁在多人分批收货时互相打架。
   *
   * <p>updated_at 必须严格前进：直接 now() 在同一批动作里可能与既有值相等或倒退， 而「按 updated_at 排序」是列表默认口径。
   */
  private void syncReceivedAt(UUID id, OffsetDateTime at) {
    sql.execute(
        "update cross_entity_transfer set"
            + " updated_at=greatest(cast(? as timestamptz),updated_at) where id=?",
        at,
        id);
  }

  /** 单号由 DB 发：应用层拼出来的单号在并发建单时必然撞号， 而撞号会被 UNIQUE(tenant_id,transfer_code) 变成 409 —— 那是一个本不该发生的错误。 */
  private String nextCode(String sequence, String prefix) {
    return sql.fetchOne(
            "select ? || '-' || to_char(now(),'YYYYMMDD') || '-' || "
                + "lpad(nextval(?)::text,6,'0')",
            prefix,
            sequence)
        .get(0, String.class);
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负数");
    }
    return value;
  }

  private static BigDecimal scale4(BigDecimal value) {
    if (value.scale() > SCALE) {
      throw new IllegalArgumentException("数值最多 4 位小数（与库存数量与定价口径 numeric(19,4) 一致）");
    }
    return value.setScale(SCALE, RoundingMode.UNNECESSARY);
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value;
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private LegalEntityTransferSaved savedOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select o.id as transfer_id,o.transfer_code,o.status,o.version,o.valuation_basis,"
                + "o.currency_code,o.total_amount,o.source_confirmed_at,o.target_confirmed_at,"
                + "o.shipped_at,o.completed_at,s.status as settlement_status"
                + " from cross_entity_transfer o"
                + " left join cross_entity_settlement s"
                + " on s.tenant_id=o.tenant_id and s.transfer_id=o.id"
                + " where o.id=?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跨法人调拨单不存在");
    }
    return new LegalEntityTransferSaved(
        r.get("transfer_id", UUID.class),
        r.get("transfer_code", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("valuation_basis", String.class),
        r.get("currency_code", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("source_confirmed_at", OffsetDateTime.class),
        r.get("target_confirmed_at", OffsetDateTime.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("settlement_status", String.class));
  }

  private static LegalEntityTransferRow row(Record r) {
    return new LegalEntityTransferRow(
        r.get("transfer_id", UUID.class),
        r.get("transfer_code", String.class),
        r.get("from_warehouse_id", UUID.class),
        r.get("from_warehouse_code", String.class),
        r.get("from_warehouse_name", String.class),
        r.get("to_warehouse_id", UUID.class),
        r.get("to_warehouse_code", String.class),
        r.get("to_warehouse_name", String.class),
        r.get("from_legal_entity_id", UUID.class),
        r.get("from_legal_entity_code", String.class),
        r.get("from_legal_entity_name", String.class),
        r.get("to_legal_entity_id", UUID.class),
        r.get("to_legal_entity_code", String.class),
        r.get("to_legal_entity_name", String.class),
        r.get("status", String.class),
        r.get("valuation_basis", String.class),
        r.get("currency_code", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("expected_ship_at", OffsetDateTime.class),
        r.get("expected_arrive_at", OffsetDateTime.class),
        r.get("source_confirmed_at", OffsetDateTime.class),
        r.get("target_confirmed_at", OffsetDateTime.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("line_count", Long.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("received_quantity", BigDecimal.class),
        r.get("discrepancy_quantity", BigDecimal.class),
        r.get("in_transit_quantity", BigDecimal.class),
        r.get("settlement_status", String.class),
        r.get("settlement_code", String.class),
        r.get("reason", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }
}
