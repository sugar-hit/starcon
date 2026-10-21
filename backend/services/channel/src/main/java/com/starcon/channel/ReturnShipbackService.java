package com.starcon.channel;

import static com.starcon.channel.ReturnShipbackModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 10.7.1.2 退运回国。
 *
 * <p>蓝图：「管理退运回国：<b>批量退运单（凑单/合箱/报关）</b>、国内收货仓、关税与申报、
 * 回国后的重新入库或报废；<b>支持海运拼箱的成本分摊</b>。输入：待退运清单；报关资料（3.7）；
 * 运输方式与成本。输出：退运单 + 报关资料；回国收货记录 + 重新入库分录；退运成本。」
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句。它的落地是三件事同时做，缺一件就退化成
 * 「一个退运单的空壳」：
 *
 * <ol>
 *   <li><b>合单是真的</b>：{@link #createConsignment} 一次接受<b>多条</b>处置单，
 *       一张退运单装多批货（凑单 / 合箱）。一条处置单最多进一个退运单
 *       （DB 唯一键 + 触发器）。</li>
 *   <li><b>成本分摊是真的算出来的</b>：{@link #recordCost} 记一笔成本的同时，
 *       用「先算总额、再按权重分摊、尾差调整到某一笔」的显式算法产出
 *       <b>逐行</b>分摊（{@link ShipbackAllocationResult}）。
 *       比例是 {@link BigDecimal}（scale 4，HALF_UP 钉死），全程禁浮点；
 *       <b>SUM(分摊金额) 恒等于该笔成本金额</b>（提交时由 DEFERRABLE 约束触发器钉死）。</li>
 *   <li><b>分摊口径是数据</b>：每笔成本自带口径（重量 / 件数 / 申报货值 / 平均），
 *       权重合计为 0 时<b>显式回退</b>到平均并把实际口径写进 {@code appliedBasis}。</li>
 * </ol>
 *
 * <p>数据来源纪律：待退运清单来自 <b>10.7.1.1</b> 的处置决策，<b>就在本服务</b>
 * （所以本模块真的读它，且只有 {@code RETURN_TO_COUNTRY} 的处置单能进清单）。
 * 报关资料（10.5.1 / 3.7）<b>全部跨服务</b>（warehouse / trade / catalog），
 * 所以本模块持有的是<b>快照</b>，不跨库 join、不建跨服务外键。
 * 下游 7 库存与 12 财务<b>均未交付</b> ⇒ 重新入库分录只出事实不消费，
 * 退运成本落在本服务自持的成本行与分摊明细里。
 */
@Service
public class ReturnShipbackService {

  private final DSLContext sql;

  public ReturnShipbackService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 基础工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>。 */
  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 币种必须是三个大写字母（DB 列级 CHECK 再钉一道）。 */
  private static String currency(String raw, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是三个大写字母的币种代码（例如 USD / EUR）");
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能小于 0");
    }
    return value;
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static <E extends Enum<E>> String enumCode(String raw, E[] values, String field, String what) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    for (E e : values) {
      if (e.name().equals(value)) {
        return value;
      }
    }
    List<String> codes = new ArrayList<>();
    for (E e : values) {
      codes.add(e.name());
    }
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY, field + "必须是 " + String.join(" / ", codes) + " 之一：" + what);
  }

  private static String statusLabel(String raw) {
    for (ShipbackStatus s : ShipbackStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String modeLabel(String raw) {
    for (TransportMode m : TransportMode.values()) {
      if (m.name().equals(raw)) {
        return m.label();
      }
    }
    return raw;
  }

  private static String lineStatusLabel(String raw) {
    for (ShipbackLineStatus s : ShipbackLineStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String costTypeLabel(String raw) {
    for (ShipbackCostType c : ShipbackCostType.values()) {
      if (c.name().equals(raw)) {
        return c.label();
      }
    }
    return raw;
  }

  private static String basisLabel(String raw) {
    for (AllocationBasis b : AllocationBasis.values()) {
      if (b.name().equals(raw)) {
        return b.label();
      }
    }
    return raw;
  }

  private static String declarationTypeLabel(String raw) {
    for (DeclarationType d : DeclarationType.values()) {
      if (d.name().equals(raw)) {
        return d.label();
      }
    }
    return raw;
  }

  private static String declarationStatusLabel(String raw) {
    for (DeclarationStatus s : DeclarationStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String customsStatusLabel(String raw) {
    for (ConsignmentCustomsStatus s : ConsignmentCustomsStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String entryTypeLabel(String raw) {
    for (EntryType e : EntryType.values()) {
      if (e.name().equals(raw)) {
        return e.label();
      }
    }
    return raw;
  }

  private static String eventTypeLabel(String raw) {
    for (ShipbackEventType e : ShipbackEventType.values()) {
      if (e.name().equals(raw)) {
        return e.label();
      }
    }
    return raw;
  }

  private static long count(Record row) {
    return row == null ? 0L : row.get(0, Long.class);
  }

  private static Object[] concat(List<Object> args, Object... more) {
    Object[] all = new Object[args.size() + more.length];
    for (int i = 0; i < args.size(); i++) {
      all[i] = args.get(i);
    }
    for (int i = 0; i < more.length; i++) {
      all[args.size() + i] = more[i];
    }
    return all;
  }

  private String nextNo(String sequence, String prefix) {
    long n = sql.fetchOne("select nextval('" + sequence + "')").get(0, Long.class);
    return prefix + n;
  }

  /** 当前事务内的操作者（由 AccessService 从已认证成员关系解析并盖章到 app.actor）。 */
  private UUID actorOrFail() {
    Record row = sql.fetchOne("select nullif(current_setting('app.actor',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少操作者上下文");
    }
    return UUID.fromString(raw);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public ReturnShipbackCapabilities capabilities() {
    List<ShipbackCapabilityDimension> dimensions =
        List.of(
            new ShipbackCapabilityDimension(
                "consolidation",
                "🔴 批量退运单（凑单 / 合箱）：一张退运单装多条处置单的货",
                true,
                "本服务 V219 return_shipback_consignment + return_shipback_line",
                "",
                "一条处置单最多进一个退运单（唯一键 + 触发器），"
                    + "同一条货不会在两个退运单里被摊两次成本"),
            new ShipbackCapabilityDimension(
                "candidate_list",
                "🔴 待退运清单：只取 10.7.1.1 决策为「退运回国」的处置单",
                true,
                "上游 10.7.1.1（**同服务** channel，直接读 return_disposition_order）",
                "",
                "转销售 / 弃置销毁的处置单**根本不会**出现在候选清单里"),
            new ShipbackCapabilityDimension(
                "cost_allocation",
                "🔴 海运拼箱成本分摊：逐行权重 / 比例 / 分摊金额 / 尾差",
                true,
                "本服务 V219 return_shipback_cost + return_shipback_cost_allocation",
                "",
                "比例 scale 4 HALF_UP；SUM(分摊金额) 恒等于成本金额（提交时 DB 钉死）"),
            new ShipbackCapabilityDimension(
                "allocation_basis",
                "分摊口径（按重量 / 按件 / 按申报货值 / 平均）—— **数据不是代码常量**",
                true,
                "本服务 V219 allocation_basis / requested_basis / applied_basis",
                "",
                "权重合计为 0 时显式回退到平均并如实记录实际口径，不静默换口径"),
            new ShipbackCapabilityDimension(
                "customs_declaration",
                "报关资料（申报品名 / HS 编码 / 货值 / 关税 / 税费）",
                true,
                "本服务 V219 return_shipback_customs_declaration（**本服务自持快照**）",
                "10.5.1",
                "10.5.1.1 报关单（warehouse）、10.5.1.2 申报要素（trade）、10.5.1.3 关税（warehouse）、"
                    + "3.7.1.1 HS 编码（catalog）**全部跨服务** ⇒ 本模块持快照、记逻辑引用，"
                    + "不跨库 join、不建跨服务外键；最终对账待上游授权 API / event 衔接"),
            new ShipbackCapabilityDimension(
                "tariff",
                "🔴 关税与申报：关税金额 = 申报货值 × 税率（**派生，不给两个矛盾的数**）",
                true,
                "本服务 V219 tariff_rate / tariff_amount / total_amount",
                "",
                "total_amount = tariff_amount + tax_amount 由 DB CHECK 钉死"),
            new ShipbackCapabilityDimension(
                "domestic_warehouse",
                "国内收货仓（回国后货落在哪里）与回国收货记录",
                true,
                "本服务 V219 return_shipback_arrival",
                "",
                "收货记录带破损 / 短少数量；短少会进「短少」状态而不是被抹平"),
            new ShipbackCapabilityDimension(
                "restock_entry",
                "🔴 重新入库分录 / 报废分录（→ 7 库存 / 10.7.1.3）—— **只出事实不消费**",
                true,
                "本服务 V219 return_shipback_inventory_entry",
                "7.4.1.1 / 10.7.1.3",
                "🔴 消费方 7.4.1.1 调整单管理与 10.7.1.3 弃置销毁**均未交付** ⇒ 分录恒为 PENDING，"
                    + "运行角色对本表没有 UPDATE / DELETE 权限"),
            new ShipbackCapabilityDimension(
                "shipback_cost",
                "退运成本（运费 / 报关费 / 操作费……）",
                true,
                "本服务 V219 return_shipback_cost",
                "12",
                "🔴 12 财务（finance 服务）当前不存在 ⇒ 成本以本服务自有事实落地，"
                    + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接"),
            new ShipbackCapabilityDimension(
                "cost_currency",
                "🔴 成本币种 + 报关币种 + 残值币种（三个独立口径，**不做汇率换算**）",
                true,
                "本服务 V219 cost_currency / declared_currency / salvage_currency",
                "",
                "成本行币种必须与退运单成本币种逐字相同；报关金额与运输成本**永远不相加**"),
            new ShipbackCapabilityDimension(
                "returns_ratio_metric",
                "退运比例（产品质量代理指标）",
                false,
                "下游 13 售后分析未交付",
                "13",
                "本模块产出退运量与退运成本的事实，**不进** 13 的分析口径；"
                    + "退运比例由 13 售后分析在本模块事实之上自算"));
    return new ReturnShipbackCapabilities(
        dimensions,
        ShipbackStatus.codes(),
        TransportMode.codes(),
        ShipbackCostType.codes(),
        AllocationBasis.codes(),
        DeclarationType.codes(),
        EntryType.codes(),
        "🔴 退运是批量、长周期、高单票成本操作：一张退运单可以装多条处置单的货"
            + "（凑单 / 合箱），单票成本由合单摊薄。一条处置单最多进一个退运单。",
        "🔴 成本分摊是**真的算出来的**：先算总额、再按权重分摊、尾差调整到某一笔；"
            + "比例是 scale 4 的 BigDecimal（HALF_UP），合计恒等于总额，全程禁浮点。",
        "🔴 成本币种、报关币种、残值币种是三个独立口径：本模块**不做任何汇率换算**，"
            + "三者**永远不相加**。",
        "🔴 报关资料是**本服务自持快照**：10.5.1 与 3.7 全部跨服务，"
            + "本模块不跨库 join、不建跨服务外键，只以 source_reference 记逻辑引用。",
        "🔴 12 财务（finance 服务）当前不存在：退运成本以本服务自有事实落地，"
            + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。",
        "🔴 重新入库 / 报废分录只产出一条事实记录：消费方 7.4.1.1 与 10.7.1.3 均未交付，"
            + "分录恒为 PENDING，本模块不动 warehouse 的任何表。",
        "上游待退运清单来自 10.7.1.1（**同服务**，直接读处置单）；"
            + "报关资料来自 10.5.1 / 3.7（**跨服务**，以快照方式持有）。",
        "本模块站在 10.7.1.1 之上做**批量退运**：不改处置决策、不改处置单、"
            + "不重做 10.7.1.1 的库存指令。");
  }

  // ==================================================================
  // 概览
  // ==================================================================

  public ReturnShipbackOverview overview() {
    UUID tenant = currentTenant();
    long consignments =
        count(sql.fetchOne("select count(*) from return_shipback_consignment where tenant_id=?::uuid", tenant));
    long draft = statusCount(tenant, "DRAFT");
    long confirmed = statusCount(tenant, "CONFIRMED");
    long dispatched = statusCount(tenant, "DISPATCHED");
    long arrived = statusCount(tenant, "ARRIVED");
    long closed = statusCount(tenant, "CLOSED");
    long cancelled = statusCount(tenant, "CANCELLED");
    long lines =
        count(sql.fetchOne("select count(*) from return_shipback_line where tenant_id=?::uuid", tenant));
    var candidate =
        sql.fetchOne(
            "select count(*), coalesce(sum(o.disposed_qty),0) from return_disposition_order o"
                + " where o.tenant_id=?::uuid and o.disposition_method='RETURN_TO_COUNTRY'"
                + "   and o.status='ISSUED'"
                + "   and not exists (select 1 from return_shipback_line l"
                + "     where l.tenant_id=o.tenant_id and l.disposition_order_id=o.id)",
            tenant);
    long candidateCount = candidate == null ? 0L : candidate.get(0, Long.class);
    BigDecimal candidateQty =
        candidate == null ? BigDecimal.ZERO : zero(candidate.get(1, BigDecimal.class));
    List<ShipbackCurrencyTotal> costTotals = costTotals(tenant);
    List<ShipbackCurrencyTotal> declaredTotals = declaredTotals(tenant);
    long restock =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_inventory_entry where tenant_id=?::uuid"
                    + " and entry_type='RESTOCK'",
                tenant));
    long scrap =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_inventory_entry where tenant_id=?::uuid"
                    + " and entry_type='SCRAP'",
                tenant));
    return new ReturnShipbackOverview(
        consignments,
        draft,
        confirmed,
        dispatched,
        arrived,
        closed,
        cancelled,
        candidateCount,
        candidateQty,
        lines,
        costTotals,
        declaredTotals,
        restock,
        scrap,
        now(),
        "待退运候选 = 10.7.1.1 已下发且去向为「退运回国」的处置单，"
            + "且尚未进入任何退运单；退运比例（候选量 / 处置量）由 13 售后分析在本模块事实之上自算。",
        "🔴 成本合计按币种分行（跨币种绝不相加）；报关金额（申报货值 / 关税）"
            + "与运输成本是**两个币种口径**，两者永远不相加。");
  }

  private long statusCount(UUID tenant, String status) {
    return count(
        sql.fetchOne(
            "select count(*) from return_shipback_consignment where tenant_id=?::uuid and status=?",
            tenant,
            status));
  }

  private List<ShipbackCurrencyTotal> costTotals(UUID tenant) {
    return sql.fetch(
            "select c.currency, sum(c.amount) as amount, count(*) as item_count"
                + "  from return_shipback_cost c"
                + "  join return_shipback_consignment k on k.tenant_id=c.tenant_id and k.id=c.consignment_id"
                + " where c.tenant_id=?::uuid and k.status not in ('CANCELLED')"
                + " group by c.currency order by c.currency",
            tenant)
        .map(
            r ->
                new ShipbackCurrencyTotal(
                    r.get("currency", String.class),
                    zero(r.get("amount", BigDecimal.class)),
                    r.get("item_count", Long.class)));
  }

  private List<ShipbackCurrencyTotal> declaredTotals(UUID tenant) {
    return sql.fetch(
            "select d.declared_currency as currency, sum(d.declared_value) as amount, count(*) as item_count"
                + "  from return_shipback_customs_declaration d"
                + "  join return_shipback_consignment k on k.tenant_id=d.tenant_id and k.id=d.consignment_id"
                + " where d.tenant_id=?::uuid and k.status not in ('CANCELLED')"
                + " group by d.declared_currency order by d.declared_currency",
            tenant)
        .map(
            r ->
                new ShipbackCurrencyTotal(
                    r.get("currency", String.class),
                    zero(r.get("amount", BigDecimal.class)),
                    r.get("item_count", Long.class)));
  }

  // ==================================================================
  // 待退运清单（上游 10.7.1.1，同服务）
  // ==================================================================

  private static final String CANDIDATE_COLUMNS =
      "o.id,o.disposition_no,o.decision_id,o.receipt_no,o.sku_code,o.sku_name,o.batch_no,"
          + "o.brand_code,o.platform_code,o.disposed_qty,o.disposition_method,o.issued_at";

  private static final String CANDIDATE_FROM =
      " from return_disposition_order o"
          + " where o.tenant_id=?::uuid and o.disposition_method='RETURN_TO_COUNTRY'"
          + "   and o.status='ISSUED'"
          + "   and not exists (select 1 from return_shipback_line l"
          + "     where l.tenant_id=o.tenant_id and l.disposition_order_id=o.id)";

  public ShipbackCandidatePage candidates(String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder();
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (o.sku_code ilike ? or o.sku_name ilike ? or o.receipt_no ilike ?"
              + " or o.batch_no ilike ? or o.disposition_no ilike ?)");
      for (int i = 0; i < 5; i++) {
        args.add("%" + kw + "%");
      }
    }
    long total =
        count(sql.fetchOne("select count(*)" + CANDIDATE_FROM + where, args.toArray()));
    var qtyRow =
        sql.fetchOne(
            "select coalesce(sum(o.disposed_qty),0)" + CANDIDATE_FROM + where, args.toArray());
    BigDecimal totalQty = qtyRow == null ? BigDecimal.ZERO : zero(qtyRow.get(0, BigDecimal.class));
    var rows =
        sql.fetch(
            "select " + CANDIDATE_COLUMNS + CANDIDATE_FROM + where
                + " order by o.issued_at desc, o.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ShipbackCandidateRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(candidateRow(r));
    }
    return new ShipbackCandidatePage(
        items,
        Math.max(page, 0),
        limit,
        total,
        totalQty,
        now(),
        "🔴 候选只来自 10.7.1.1 决策为「退运回国」的处置单："
            + "转销售 / 弃置销毁的处置单不会出现在这里。");
  }

  private ShipbackCandidateRow candidateRow(Record r) {
    String method = r.get("disposition_method", String.class);
    return new ShipbackCandidateRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("decision_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        zero(r.get("disposed_qty", BigDecimal.class)),
        BigDecimal.ZERO,
        "",
        method,
        dispositionMethodLabel(method),
        r.get("issued_at", OffsetDateTime.class) == null
            ? ""
            : r.get("issued_at", OffsetDateTime.class).toString(),
        "");
  }

  private static String dispositionMethodLabel(String raw) {
    return switch (raw) {
      case "RETURN_TO_COUNTRY" -> "退运回国";
      case "RESALE" -> "转销售（可再售）";
      case "DISCARD" -> "弃置销毁";
      default -> raw;
    };
  }

  // ==================================================================
  // 退运单
  // ==================================================================

  private static final String CONSIGNMENT_COLUMNS =
      "k.id,k.shipback_no,k.status,k.transport_mode,k.origin_country,k.destination_country,"
          + "k.origin_warehouse_ref,k.domestic_warehouse_ref,k.carrier_ref,k.tracking_no,"
          + "k.total_qty,k.total_weight_kg,k.total_volume_m3,k.cost_currency,k.customs_status,"
          + "k.customs_declaration_no,k.planned_departure_at,k.planned_arrival_at,"
          + "k.actual_departure_at,k.actual_arrival_at,k.created_at,k.consolidation_note,"
          + "k.note,k.version,"
          + "(select count(*) from return_shipback_line l"
          + "  where l.tenant_id=k.tenant_id and l.consignment_id=k.id) as line_count,"
          + "(select coalesce(sum(c.amount),0) from return_shipback_cost c"
          + "  where c.tenant_id=k.tenant_id and c.consignment_id=k.id) as total_cost";

  private static final String CONSIGNMENT_FROM = " from return_shipback_consignment k";

  public ReturnShipbackPage consignments(
      String keyword, String status, String transportMode, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where k.tenant_id=?::uuid");
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (k.shipback_no ilike ? or k.tracking_no ilike ? or k.carrier_ref ilike ?"
              + " or k.origin_warehouse_ref ilike ? or k.domestic_warehouse_ref ilike ?"
              + " or k.customs_declaration_no ilike ?)");
      for (int i = 0; i < 6; i++) {
        args.add("%" + kw + "%");
      }
    }
    String st = text(status).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and k.status=?");
      args.add(st);
    }
    String tm = text(transportMode).toUpperCase(Locale.ROOT);
    if (!tm.isEmpty()) {
      where.append(" and k.transport_mode=?");
      args.add(tm);
    }
    long total = count(sql.fetchOne("select count(*)" + CONSIGNMENT_FROM + where, args.toArray()));
    var sumRow =
        sql.fetchOne(
            "select coalesce(sum(c.amount),0) from return_shipback_cost c"
                + " join return_shipback_consignment k on k.tenant_id=c.tenant_id and k.id=c.consignment_id"
                + " where c.tenant_id=?::uuid and k.status <> 'CANCELLED'",
            tenant);
    BigDecimal totalCost = sumRow == null ? BigDecimal.ZERO : zero(sumRow.get(0, BigDecimal.class));
    var rows =
        sql.fetch(
            "select " + CONSIGNMENT_COLUMNS + CONSIGNMENT_FROM + where
                + " order by k.created_at desc, k.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnShipbackRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(consignmentRow(r));
    }
    return new ReturnShipbackPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        totalCost,
        now(),
        "退运单 = 一次批量退运的批次：多批退货的货合在一张单里，"
            + "单票成本由此摊薄（凑单 / 合箱）。合计为查询时现算的 SUM，不落表。");
  }

  private ReturnShipbackRow consignmentRow(Record r) {
    String status = r.get("status", String.class);
    String mode = r.get("transport_mode", String.class);
    String customs = r.get("customs_status", String.class);
    return new ReturnShipbackRow(
        r.get("id", UUID.class),
        r.get("shipback_no", String.class),
        status,
        statusLabel(status),
        mode,
        modeLabel(mode),
        r.get("origin_country", String.class),
        r.get("destination_country", String.class),
        r.get("origin_warehouse_ref", String.class),
        r.get("domestic_warehouse_ref", String.class),
        r.get("carrier_ref", String.class),
        r.get("tracking_no", String.class),
        zero(r.get("total_qty", BigDecimal.class)),
        zero(r.get("total_weight_kg", BigDecimal.class)),
        zero(r.get("total_volume_m3", BigDecimal.class)),
        r.get("cost_currency", String.class),
        customs,
        customsStatusLabel(customs),
        r.get("customs_declaration_no", String.class),
        r.get("line_count", Long.class),
        zero(r.get("total_cost", BigDecimal.class)),
        r.get("planned_departure_at", OffsetDateTime.class),
        r.get("planned_arrival_at", OffsetDateTime.class),
        r.get("actual_departure_at", OffsetDateTime.class),
        r.get("actual_arrival_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("consolidation_note", String.class),
        r.get("note", String.class),
        r.get("version", Long.class));
  }

  /**
   * 建一张退运单（合单）。
   *
   * <p>🔴 {@code lines} 就是「凑单 / 合箱」的落点：一次装多条处置单的货。
   * 每行挂一条 10.7.1.1 的「退运回国」处置单（触发器钉死），数量不得超过该处置单的处置量。
   */
  public ReturnShipbackDetail createConsignment(CreateConsignmentRequest request) {
    UUID tenant = currentTenant();
    String mode =
        enumCode(
            request.transportMode(),
            TransportMode.values(),
            "运输方式",
            "海运拼箱 / 海运整柜 / 空运 / 国际快递 / 陆运 / 其他");
    String originCountry = text(request.originCountry()).toUpperCase(Locale.ROOT);
    if (originCountry.length() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起运国家必须是两位国家代码");
    }
    String destinationCountry = text(request.destinationCountry()).toUpperCase(Locale.ROOT);
    if (destinationCountry.length() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目的国家必须是两位国家代码");
    }
    String originWarehouse = text(request.originWarehouseRef());
    if (originWarehouse.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起运仓不能为空");
    }
    String domesticWarehouse = text(request.domesticWarehouseRef());
    if (domesticWarehouse.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "国内收货仓不能为空");
    }
    String costCurrency = currency(request.costCurrency(), "退运成本币种");

    UUID id = Ids.next();
    String no = nextNo("return_shipback_consignment_no_seq", "SBK");
    sql.execute(
        "insert into return_shipback_consignment"
            + " (id,tenant_id,shipback_no,status,transport_mode,origin_country,destination_country,"
            + "  origin_warehouse_ref,domestic_warehouse_ref,departure_port,arrival_port,carrier_ref,"
            + "  tracking_no,planned_departure_at,planned_arrival_at,cost_currency,consolidation_note,note)"
            + " values(?::uuid,?::uuid,?,'DRAFT',?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?)",
        id,
        tenant,
        no,
        mode,
        originCountry,
        destinationCountry,
        originWarehouse,
        domesticWarehouse,
        text(request.departurePort()),
        text(request.arrivalPort()),
        text(request.carrierRef()),
        text(request.trackingNo()),
        request.plannedDepartureAt(),
        request.plannedArrivalAt(),
        costCurrency,
        text(request.consolidationNote()),
        text(request.note()));
    event(tenant, "CONSIGNMENT_CREATED", id, null, null, null, null, "新建退运单 " + no);

    if (request.lines() != null) {
      for (ShipbackLineDraft draft : request.lines()) {
        addLine(tenant, id, draft);
      }
    }
    refreshConsignmentTotals(tenant, id);
    return detailOf(tenant, id);
  }

  /** 往既有退运单里再加入一行（继续凑单）。 */
  public ReturnShipbackDetail addLine(UUID consignmentId, AddLineRequest request) {
    UUID tenant = currentTenant();
    if (request == null || request.line() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要加入的明细行");
    }
    requireConsignment(tenant, consignmentId);
    addLine(tenant, consignmentId, request.line());
    refreshConsignmentTotals(tenant, consignmentId);
    return detailOf(tenant, consignmentId);
  }

  /**
   * 加入一行明细。
   *
   * <p>🔴 行上的 SKU / 批次 / 收货单号由**服务端从处置单抄**（触发器再钉一道），
   * 不接受客户端自报 —— 客户端自报会出现「单上写着 A 仓的货、实际发的是 B 仓的货」。
   */
  private void addLine(UUID tenant, UUID consignmentId, ShipbackLineDraft draft) {
    if (draft.dispositionOrderId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须指定上游处置单（dispositionOrderId）：退运单的货来自 10.7.1.1");
    }
    var order =
        sql.fetchOne(
            "select id,decision_id,disposition_no,receipt_no,sku_code,sku_name,batch_no,"
                + "brand_code,platform_code,disposed_qty,disposition_method,status,"
                + "salvage_currency from return_disposition_order"
                + " where tenant_id=?::uuid and id=?::uuid",
            tenant,
            draft.dispositionOrderId());
    if (order == null) {
      throw notFound("上游处置单");
    }
    if (!"RETURN_TO_COUNTRY".equals(order.get("disposition_method", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "处置单 "
              + order.get("disposition_no", String.class)
              + " 的去向是「"
              + dispositionMethodLabel(order.get("disposition_method", String.class))
              + "」：只有明确决策为「退运回国」的货才能进退运单");
    }
    if (!"ISSUED".equals(order.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "上游处置单已撤销：不能进退运单");
    }
    BigDecimal qty = positive(draft.qty(), "退运数量");
    BigDecimal disposed = zero(order.get("disposed_qty", BigDecimal.class));
    if (qty.compareTo(disposed) > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "退运数量 " + qty + " 超过处置单的处置量 " + disposed);
    }
    String declaredCurrency = currency(defaultText(draft.declaredCurrency(), "CNY"), "申报货值币种");
    BigDecimal declaredValue = nonNegative(zero(draft.declaredValue()), "申报货值");
    String salvageCurrency =
        currency(
            defaultText(draft.salvageCurrency(), order.get("salvage_currency", String.class)),
            "残值币种");
    BigDecimal salvage = nonNegative(zero(draft.salvageAmount()), "残值");
    String hsCode = text(draft.hsCode());
    String declaredItemName = text(draft.declaredItemName());
    if (!declaredItemName.isEmpty() && hsCode.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "填了申报品名就必须填 HS 编码：申报资料不能只有一半");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into return_shipback_line"
            + " (id,tenant_id,consignment_id,disposition_order_id,decision_id,disposition_no,"
            + "  receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,qty,weight_kg,"
            + "  volume_m3,hs_code,declared_item_name,declared_value,declared_currency,"
            + "  salvage_amount,salvage_currency,note)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?::numeric,"
            + "  ?::numeric,?::numeric,?,?,?::numeric,?,?::numeric,?,?)",
        id,
        tenant,
        consignmentId,
        draft.dispositionOrderId(),
        order.get("decision_id", UUID.class),
        order.get("disposition_no", String.class),
        order.get("receipt_no", String.class),
        order.get("sku_code", String.class),
        order.get("sku_name", String.class),
        order.get("batch_no", String.class),
        order.get("brand_code", String.class),
        order.get("platform_code", String.class),
        qty,
        nonNegative(zero(draft.weightKg()), "重量"),
        nonNegative(zero(draft.volumeM3()), "体积"),
        hsCode,
        declaredItemName,
        declaredValue,
        declaredCurrency,
        salvage,
        salvageCurrency,
        text(draft.note()));
    event(
        tenant,
        "LINE_ADDED",
        consignmentId,
        id,
        null,
        null,
        null,
        "加入明细行：" + order.get("sku_code", String.class) + " × " + qty);
  }

  private static String defaultText(String value, String fallback) {
    String v = text(value);
    return v.isEmpty() ? text(fallback) : v;
  }

  /** 明细行变动后重算退运单上的合计快照（真实合计仍以查询时 SUM 为准）。 */
  private void refreshConsignmentTotals(UUID tenant, UUID consignmentId) {
    sql.execute(
        "update return_shipback_consignment k set"
            + " total_qty = coalesce((select sum(l.qty) from return_shipback_line l"
            + "   where l.tenant_id=k.tenant_id and l.consignment_id=k.id),0),"
            + " total_weight_kg = coalesce((select sum(l.weight_kg) from return_shipback_line l"
            + "   where l.tenant_id=k.tenant_id and l.consignment_id=k.id),0),"
            + " total_volume_m3 = coalesce((select sum(l.volume_m3) from return_shipback_line l"
            + "   where l.tenant_id=k.tenant_id and l.consignment_id=k.id),0),"
            + " version = version + 1"
            + " where k.tenant_id=?::uuid and k.id=?::uuid",
        tenant,
        consignmentId);
  }

  /** 退运单详情（对外入口：租户由当前事务上下文决定）。 */
  public ReturnShipbackDetail consignmentDetail(UUID id) {
    return detailOf(currentTenant(), id);
  }

  private ReturnShipbackDetail detailOf(UUID tenant, UUID id) {
    var head =
        sql.fetchOne(
            "select " + CONSIGNMENT_COLUMNS + CONSIGNMENT_FROM + " where k.tenant_id=?::uuid and k.id=?::uuid",
            tenant,
            id);
    if (head == null) {
      throw notFound("退运单");
    }
    List<ReturnShipbackLineRow> lines = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select l.* from return_shipback_line l"
                + " where l.tenant_id=?::uuid and l.consignment_id=?::uuid"
                + " order by l.created_at asc, l.id asc",
            tenant,
            id)) {
      lines.add(lineRow(r));
    }
    List<ReturnShipbackDeclarationRow> declarations = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select d.* from return_shipback_customs_declaration d"
                + " where d.tenant_id=?::uuid and d.consignment_id=?::uuid"
                + " order by d.created_at asc, d.id asc",
            tenant,
            id)) {
      declarations.add(declarationRow(r));
    }
    List<ReturnShipbackCostRow> costs = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select c.*, coalesce((select sum(a.allocated_amount) from return_shipback_cost_allocation a"
                + "   where a.tenant_id=c.tenant_id and a.cost_id=c.id),0) as allocated_total"
                + "  from return_shipback_cost c"
                + " where c.tenant_id=?::uuid and c.consignment_id=?::uuid"
                + " order by c.created_at asc, c.id asc",
            tenant,
            id)) {
      costs.add(costRow(r));
    }
    List<ReturnShipbackAllocationRow> allocations = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.*, l.sku_code, l.sku_name from return_shipback_cost_allocation a"
                + "  join return_shipback_line l on l.tenant_id=a.tenant_id and l.id=a.line_id"
                + " where a.tenant_id=?::uuid and a.consignment_id=?::uuid"
                + " order by a.cost_id asc, l.sku_code asc, a.id asc",
            tenant,
            id)) {
      allocations.add(allocationRow(r));
    }
    List<ReturnShipbackArrivalRow> arrivals = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.* from return_shipback_arrival a"
                + " where a.tenant_id=?::uuid and a.consignment_id=?::uuid"
                + " order by a.created_at asc, a.id asc",
            tenant,
            id)) {
      arrivals.add(arrivalRow(r));
    }
    List<ReturnShipbackEntryRow> entries = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select e.* from return_shipback_inventory_entry e"
                + " where e.tenant_id=?::uuid and e.consignment_id=?::uuid"
                + " order by e.created_at asc, e.id asc",
            tenant,
            id)) {
      entries.add(entryRow(r));
    }
    List<ReturnShipbackEventRow> events = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select e.* from return_shipback_event e"
                + " where e.tenant_id=?::uuid and e.consignment_id=?::uuid"
                + " order by e.occurred_at desc, e.id desc",
            tenant,
            id)) {
      events.add(eventRow(r));
    }
    String status = head.get("status", String.class);
    return new ReturnShipbackDetail(
        consignmentRow(head),
        lines,
        declarations,
        costs,
        allocations,
        arrivals,
        entries,
        events,
        nextActions(status),
        "🔴 成本合计与分摊合计都是**查询时现算**的 SUM；分摊明细逐行给出权重、比例与尾差。");
  }

  /** 合法的下一步动作（状态机只有一处真相：这里读的就是退运单自己的 status）。 */
  private static List<String> nextActions(String status) {
    return switch (status) {
      case "DRAFT" -> List.of("确认（货已凑齐、报关齐备）", "取消");
      case "CONFIRMED" -> List.of("发出", "取消");
      case "DISPATCHED" -> List.of("登记回国收货");
      case "ARRIVED" -> List.of("登记重新入库 / 报废分录", "关闭");
      case "CLOSED" -> List.of();
      case "CANCELLED" -> List.of();
      default -> List.of();
    };
  }

  private ReturnShipbackLineRow lineRow(Record r) {
    String status = r.get("status", String.class);
    return new ReturnShipbackLineRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        r.get("disposition_order_id", UUID.class),
        r.get("decision_id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        zero(r.get("qty", BigDecimal.class)),
        zero(r.get("weight_kg", BigDecimal.class)),
        zero(r.get("volume_m3", BigDecimal.class)),
        r.get("hs_code", String.class),
        r.get("declared_item_name", String.class),
        zero(r.get("declared_value", BigDecimal.class)),
        r.get("declared_currency", String.class),
        zero(r.get("salvage_amount", BigDecimal.class)),
        r.get("salvage_currency", String.class),
        zero(r.get("allocated_cost", BigDecimal.class)),
        status,
        lineStatusLabel(status),
        r.get("note", String.class));
  }

  private ReturnShipbackDeclarationRow declarationRow(Record r) {
    String type = r.get("declaration_type", String.class);
    String status = r.get("status", String.class);
    return new ReturnShipbackDeclarationRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("source_module", String.class),
        r.get("source_reference", String.class),
        r.get("declaration_no", String.class),
        type,
        declarationTypeLabel(type),
        r.get("hs_code", String.class),
        r.get("declared_item_name", String.class),
        zero(r.get("declared_qty", BigDecimal.class)),
        zero(r.get("declared_value", BigDecimal.class)),
        r.get("declared_currency", String.class),
        zero(r.get("tariff_rate", BigDecimal.class)),
        zero(r.get("tariff_amount", BigDecimal.class)),
        zero(r.get("tax_amount", BigDecimal.class)),
        zero(r.get("total_amount", BigDecimal.class)),
        status,
        declarationStatusLabel(status),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("cleared_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  private ReturnShipbackCostRow costRow(Record r) {
    String type = r.get("cost_type", String.class);
    String basis = r.get("allocation_basis", String.class);
    return new ReturnShipbackCostRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        type,
        costTypeLabel(type),
        zero(r.get("amount", BigDecimal.class)),
        r.get("currency", String.class),
        basis,
        basisLabel(basis),
        r.get("vendor_ref", String.class),
        r.get("incurred_at", OffsetDateTime.class),
        zero(r.get("allocated_total", BigDecimal.class)),
        r.get("note", String.class));
  }

  private ReturnShipbackAllocationRow allocationRow(Record r) {
    String requested = r.get("requested_basis", String.class);
    String applied = r.get("applied_basis", String.class);
    return new ReturnShipbackAllocationRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("cost_id", UUID.class),
        requested,
        basisLabel(requested),
        applied,
        basisLabel(applied),
        zero(r.get("basis_value", BigDecimal.class)),
        zero(r.get("basis_total", BigDecimal.class)),
        zero(r.get("allocation_ratio", BigDecimal.class)),
        zero(r.get("allocated_amount", BigDecimal.class)),
        r.get("currency", String.class),
        zero(r.get("residual_amount", BigDecimal.class)),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class));
  }

  private ReturnShipbackArrivalRow arrivalRow(Record r) {
    return new ReturnShipbackArrivalRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        r.get("domestic_warehouse_ref", String.class),
        r.get("arrived_at", OffsetDateTime.class),
        zero(r.get("received_qty", BigDecimal.class)),
        zero(r.get("damaged_qty", BigDecimal.class)),
        zero(r.get("shortage_qty", BigDecimal.class)),
        r.get("received_by", UUID.class),
        r.get("note", String.class));
  }

  private ReturnShipbackEntryRow entryRow(Record r) {
    String type = r.get("entry_type", String.class);
    return new ReturnShipbackEntryRow(
        r.get("id", UUID.class),
        r.get("consignment_id", UUID.class),
        r.get("line_id", UUID.class),
        type,
        entryTypeLabel(type),
        zero(r.get("qty", BigDecimal.class)),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("domestic_warehouse_ref", String.class),
        r.get("entry_no", String.class),
        r.get("posting_status", String.class),
        r.get("consumer_module", String.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("issued_by", UUID.class),
        r.get("note", String.class));
  }

  private ReturnShipbackEventRow eventRow(Record r) {
    String type = r.get("event_type", String.class);
    return new ReturnShipbackEventRow(
        r.get("id", UUID.class),
        type,
        eventTypeLabel(type),
        r.get("consignment_id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("cost_id", UUID.class),
        r.get("declaration_id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  private Record requireConsignment(UUID tenant, UUID id) {
    var row =
        sql.fetchOne(
            "select id,status,shipback_no,cost_currency,domestic_warehouse_ref,version"
                + " from return_shipback_consignment where tenant_id=?::uuid and id=?::uuid",
            tenant,
            id);
    if (row == null) {
      throw notFound("退运单");
    }
    return row;
  }

  // ==================================================================
  // 报关资料
  // ==================================================================

  /**
   * 登记报关资料。
   *
   * <p>🔴 关税金额是<b>派生</b>的：{@code tariffAmount = declaredValue × tariffRate}
   * （HALF_UP 到 4 位），不给「税率」与「关税金额」两个互相矛盾的数；
   * 合计 = 关税 + 税费，由 DB CHECK 再钉一道。
   */
  public ReturnShipbackDeclarationRow recordDeclaration(
      UUID consignmentId, RecordShipbackDeclarationRequest request) {
    UUID tenant = currentTenant();
    requireConsignment(tenant, consignmentId);
    String type =
        enumCode(
            request.declarationType(),
            DeclarationType.values(),
            "报关单类型",
            "退运进口 / 出口 / 过境");
    String hsCode = text(request.hsCode());
    if (hsCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HS 编码不能为空");
    }
    String itemName = text(request.declaredItemName());
    if (itemName.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "申报品名不能为空：申报品名不照搬销售标题，但它必须有");
    }
    BigDecimal qty = positive(request.declaredQty(), "申报数量");
    BigDecimal value = nonNegative(zero(request.declaredValue()), "申报货值");
    String ccy = currency(request.declaredCurrency(), "申报币种");
    BigDecimal rate = nonNegative(zero(request.tariffRate()), "关税税率");
    if (rate.compareTo(BigDecimal.ONE) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "关税税率必须是小数（0.15 表示 15%），不能填 15");
    }
    BigDecimal tax = nonNegative(zero(request.taxAmount()), "税费");
    BigDecimal tariff = value.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    BigDecimal total = tariff.add(tax).setScale(4, RoundingMode.HALF_UP);
    String status =
        text(request.status()).isEmpty()
            ? "DRAFT"
            : enumCode(
                request.status(),
                DeclarationStatus.values(),
                "报关资料状态",
                "草稿 / 已提交 / 已放行 / 已退单");
    UUID id = Ids.next();
    sql.execute(
        "insert into return_shipback_customs_declaration"
            + " (id,tenant_id,consignment_id,line_id,source_module,source_reference,declaration_no,"
            + "  declaration_type,hs_code,declared_item_name,declared_qty,declared_value,"
            + "  declared_currency,tariff_rate,tariff_amount,tax_amount,total_amount,status,"
            + "  submitted_at,cleared_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,'10.5.1',?,?,?,?,?,?::numeric,?::numeric,?,"
            + "  ?::numeric,?::numeric,?::numeric,?::numeric,?,?::timestamptz,?::timestamptz,?)",
        id,
        tenant,
        consignmentId,
        request.lineId(),
        text(request.sourceReference()),
        text(request.declarationNo()),
        type,
        hsCode,
        itemName,
        qty,
        value,
        ccy,
        rate,
        tariff,
        tax,
        total,
        status,
        request.submittedAt(),
        request.clearedAt(),
        text(request.note()));
    // 退运单头上的报关状态跟着走（快照口径）
    String consignmentCustomsStatus =
        switch (status) {
          case "SUBMITTED" -> "SUBMITTED";
          case "CLEARED" -> "CLEARED";
          case "REJECTED" -> "REJECTED";
          default -> "PREPARED";
        };
    sql.execute(
        "update return_shipback_consignment set customs_status=?,"
            + " customs_declaration_no=coalesce(nullif(?,''),customs_declaration_no),"
            + " version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        consignmentCustomsStatus,
        text(request.declarationNo()),
        tenant,
        consignmentId);
    event(
        tenant,
        "CUSTOMS_PREPARED",
        consignmentId,
        null,
        null,
        id,
        null,
        "登记报关资料：" + hsCode + " / " + itemName + " / 货值 " + value + " " + ccy
            + " / 关税 " + tariff + " " + ccy);
    return declarationRow(
        sql.fetchOne(
            "select d.* from return_shipback_customs_declaration d where d.tenant_id=?::uuid and d.id=?::uuid",
            tenant,
            id));
  }

  // ==================================================================
  // 🔴 成本与分摊
  // ==================================================================

  /**
   * 登记一笔退运成本，并**立刻把成本分摊到每一行**。
   *
   * <p>🔴 分摊算法（不是留空字段）：
   *
   * <ol>
   *   <li>取本退运单的全部明细行（按 id 排序 ⇒ 顺序确定）；</li>
   *   <li>按人选的口径取每行的权重（重量 / 件数 / 申报货值 / 1）；</li>
   *   <li>权重合计为 0 ⇒ <b>显式回退</b>到平均（权重全为 1），并把实际口径记为
   *       {@code appliedBasis = EQUAL}，<b>不静默换口径</b>；</li>
   *   <li>逐行金额 = 总额 × 权重 / 权重合计，HALF_UP 到 4 位；</li>
   *   <li><b>尾差调整</b>：总额 − 逐行四舍五入之和，补到<b>最后一行</b>上，
   *       于是 <b>SUM(分摊金额) 恒等于该笔成本金额</b>。</li>
   * </ol>
   *
   * <p>全程 {@link BigDecimal}，比例 scale 4，**禁浮点**。
   */
  public ShipbackAllocationResult recordCost(UUID consignmentId, RecordShipbackCostRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    String status = consignment.get("status", String.class);
    if ("CANCELLED".equals(status) || "CLOSED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "退运单已" + ("CANCELLED".equals(status) ? "取消" : "关闭") + "：不能再记成本");
    }
    String type =
        enumCode(
            request.costType(),
            ShipbackCostType.values(),
            "成本类型",
            "海运费 / 空运费 / 关税 / 报关费 / 内陆运费 / 操作费 / 换包装费 / 其他");
    BigDecimal amount = nonNegative(zero(request.amount()), "成本金额");
    String ccy = currency(request.currency(), "成本币种");
    String basis =
        enumCode(
            request.allocationBasis(),
            AllocationBasis.values(),
            "分摊口径",
            "按重量 / 按数量 / 按申报货值 / 平均分摊");
    OffsetDateTime incurredAt = request.incurredAt() == null ? now() : request.incurredAt();

    List<Record> lines =
        sql.fetch(
            "select l.id,l.sku_code,l.qty,l.weight_kg,l.declared_value"
                + "  from return_shipback_line l"
                + " where l.tenant_id=?::uuid and l.consignment_id=?::uuid"
                + " order by l.id asc",
            tenant,
            consignmentId);
    if (lines.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "退运单还没有明细行：先把要退运的货合进来（凑单），再记成本才能分摊");
    }

    UUID costId = Ids.next();
    sql.execute(
        "insert into return_shipback_cost"
            + " (id,tenant_id,consignment_id,cost_type,amount,currency,allocation_basis,vendor_ref,"
            + "  incurred_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?::numeric,?,?,?,?::timestamptz,?)",
        costId,
        tenant,
        consignmentId,
        type,
        amount,
        ccy,
        basis,
        text(request.vendorRef()),
        incurredAt,
        text(request.note()));

    // ---- 权重 ----
    List<BigDecimal> weights = new ArrayList<>();
    BigDecimal basisTotal = BigDecimal.ZERO;
    for (Record line : lines) {
      BigDecimal w =
          switch (basis) {
            case "WEIGHT" -> zero(line.get("weight_kg", BigDecimal.class));
            case "QUANTITY" -> zero(line.get("qty", BigDecimal.class));
            case "DECLARED_VALUE" -> zero(line.get("declared_value", BigDecimal.class));
            default -> BigDecimal.ONE;
          };
      weights.add(w);
      basisTotal = basisTotal.add(w);
    }
    String appliedBasis = basis;
    boolean fallback = false;
    if (basisTotal.signum() <= 0) {
      // 🔴 权重之和为 0（全是 0 重量 / 0 申报货值）⇒ 显式回退到平均分摊，
      //    并把实际口径如实记录，绝不静默换口径。
      appliedBasis = "EQUAL";
      fallback = true;
      weights = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        weights.add(BigDecimal.ONE);
      }
      basisTotal = BigDecimal.valueOf(lines.size());
    }

    // ---- 逐行分摊 + 尾差调整 ----
    List<BigDecimal> amounts = new ArrayList<>();
    BigDecimal allocatedSum = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      BigDecimal share =
          amount
              .multiply(weights.get(i))
              .divide(basisTotal, 4, RoundingMode.HALF_UP);
      amounts.add(share);
      allocatedSum = allocatedSum.add(share);
    }
    BigDecimal residual = amount.subtract(allocatedSum).setScale(4, RoundingMode.HALF_UP);
    if (residual.signum() != 0) {
      int last = amounts.size() - 1;
      amounts.set(last, amounts.get(last).add(residual).setScale(4, RoundingMode.HALF_UP));
    }

    List<ReturnShipbackAllocationRow> rows = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      Record line = lines.get(i);
      BigDecimal ratio =
          weights
              .get(i)
              .divide(basisTotal, 4, RoundingMode.HALF_UP)
              .setScale(4, RoundingMode.HALF_UP);
      UUID allocationId = Ids.next();
      BigDecimal residualOnThis = i == amounts.size() - 1 ? residual : BigDecimal.ZERO.setScale(4);
      sql.execute(
          "insert into return_shipback_cost_allocation"
              + " (id,tenant_id,consignment_id,line_id,cost_id,requested_basis,applied_basis,"
              + "  basis_value,basis_total,allocation_ratio,allocated_amount,currency,residual_amount)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::numeric,"
              + "  ?::numeric,?::numeric,?,?::numeric)",
          allocationId,
          tenant,
          consignmentId,
          line.get("id", UUID.class),
          costId,
          basis,
          appliedBasis,
          weights.get(i),
          basisTotal,
          ratio,
          amounts.get(i),
          ccy,
          residualOnThis);
      rows.add(
          new ReturnShipbackAllocationRow(
              allocationId,
              consignmentId,
              line.get("id", UUID.class),
              costId,
              basis,
              basisLabel(basis),
              appliedBasis,
              basisLabel(appliedBasis),
              weights.get(i),
              basisTotal,
              ratio,
              amounts.get(i),
              ccy,
              residualOnThis,
              line.get("sku_code", String.class),
              ""));
    }

    // 明细行上的「摊到本行的成本合计」= 该行所有分摊之和（现算落回行上便于现场看）
    for (Record line : lines) {
      sql.execute(
          "update return_shipback_line l set allocated_cost = coalesce(("
              + " select sum(a.allocated_amount) from return_shipback_cost_allocation a"
              + "  where a.tenant_id=l.tenant_id and a.line_id=l.id),0)"
              + " where l.tenant_id=?::uuid and l.id=?::uuid",
          tenant,
          line.get("id", UUID.class));
    }
    event(
        tenant,
        "COST_RECORDED",
        consignmentId,
        null,
        costId,
        null,
        null,
        "登记成本 " + amount + " " + ccy + "（口径 " + basis
            + (fallback ? "，权重合计为 0 已显式回退为平均分摊" : "") + "），分摊到 " + lines.size() + " 行");
    BigDecimal totalAllocated = BigDecimal.ZERO;
    for (BigDecimal a : amounts) {
      totalAllocated = totalAllocated.add(a);
    }
    return new ShipbackAllocationResult(
        costId,
        type,
        amount,
        ccy,
        basis,
        appliedBasis,
        fallback,
        totalAllocated.setScale(4, RoundingMode.HALF_UP),
        rows,
        "🔴 分摊合计恒等于成本金额：尾差（"
            + residual.setScale(4, RoundingMode.HALF_UP)
            + "）已补到最后一笔，不会丢。");
  }

  // ==================================================================
  // 状态推进：确认 / 发出 / 到货 / 关闭 / 取消
  // ==================================================================

  public ReturnShipbackDetail confirm(UUID consignmentId, AdvanceShipbackRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    if (!"DRAFT".equals(consignment.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿状态的退运单可以确认");
    }
    long lines =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_line where tenant_id=?::uuid and consignment_id=?::uuid",
                tenant,
                consignmentId));
    if (lines == 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "退运单还没有明细行：空单确认不了（合单才有意义）");
    }
    long declarations =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_customs_declaration"
                    + " where tenant_id=?::uuid and consignment_id=?::uuid",
                tenant,
                consignmentId));
    if (declarations == 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "报关资料还没登记：退运回国必须报关才能确认（ customs_status 仍为 NOT_PREPARED）");
    }
    OffsetDateTime at = request == null || request.occurredAt() == null ? now() : request.occurredAt();
    sql.execute(
        "update return_shipback_consignment set status='CONFIRMED', confirmed_by=?::uuid,"
            + " confirmed_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        actorOrFail(),
        at,
        tenant,
        consignmentId);
    event(tenant, "CONFIRMED", consignmentId, null, null, null, null, "确认退运单");
    return detailOf(tenant, consignmentId);
  }

  public ReturnShipbackDetail dispatch(UUID consignmentId, AdvanceShipbackRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    if (!"CONFIRMED".equals(consignment.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已确认的退运单可以发出");
    }
    OffsetDateTime at = request == null || request.occurredAt() == null ? now() : request.occurredAt();
    String tracking = request == null ? "" : text(request.trackingNo());
    sql.execute(
        "update return_shipback_consignment set status='DISPATCHED', dispatched_by=?::uuid,"
            + " dispatched_at=?::timestamptz, actual_departure_at=?::timestamptz,"
            + " tracking_no=coalesce(nullif(?,''),tracking_no), version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        actorOrFail(),
        at,
        at,
        tracking,
        tenant,
        consignmentId);
    sql.execute(
        "update return_shipback_line set status='SHIPPED', updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and consignment_id=?::uuid and status='PLANNED'",
        at,
        tenant,
        consignmentId);
    event(tenant, "DISPATCHED", consignmentId, null, null, null, null, "退运单已发出");
    return detailOf(tenant, consignmentId);
  }

  /**
   * 登记回国收货（国内收货仓签收）。
   *
   * <p>🔴 收货数量不得超过退运单的总数量；短少与破损**单列**，
   * 不会因为「少了几件」就把总账抹平。
   */
  public ReturnShipbackDetail recordArrival(UUID consignmentId, RecordShipbackArrivalRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    if (!"DISPATCHED".equals(consignment.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已发出的退运单可以登记回国收货");
    }
    String warehouse =
        defaultText(request.domesticWarehouseRef(), consignment.get("domestic_warehouse_ref", String.class));
    if (warehouse.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "国内收货仓不能为空");
    }
    BigDecimal received = nonNegative(zero(request.receivedQty()), "收货数量");
    BigDecimal damaged = nonNegative(zero(request.damagedQty()), "破损数量");
    BigDecimal shortage = nonNegative(zero(request.shortageQty()), "短少数量");
    var totalRow =
        sql.fetchOne(
            "select coalesce(sum(qty),0) from return_shipback_line"
                + " where tenant_id=?::uuid and consignment_id=?::uuid",
            tenant,
            consignmentId);
    BigDecimal planned = totalRow == null ? BigDecimal.ZERO : zero(totalRow.get(0, BigDecimal.class));
    if (received.compareTo(planned) > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "收货数量 " + received + " 超过退运单计划发运数量 " + planned);
    }
    OffsetDateTime at = request.arrivedAt() == null ? now() : request.arrivedAt();
    UUID arrivalId = Ids.next();
    sql.execute(
        "insert into return_shipback_arrival"
            + " (id,tenant_id,consignment_id,domestic_warehouse_ref,arrived_at,received_qty,"
            + "  damaged_qty,shortage_qty,received_by,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?::timestamptz,?::numeric,?::numeric,?::numeric,"
            + "  ?::uuid,?)",
        arrivalId,
        tenant,
        consignmentId,
        warehouse,
        at,
        received,
        damaged,
        shortage,
        actorOrFail(),
        text(request.note()));
    sql.execute(
        "update return_shipback_consignment set status='ARRIVED', actual_arrival_at=?::timestamptz,"
            + " version=version+1 where tenant_id=?::uuid and id=?::uuid",
        at,
        tenant,
        consignmentId);
    sql.execute(
        "update return_shipback_line set status=case when ?::numeric > 0 then 'SHORTAGE' else 'ARRIVED' end,"
            + " updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and consignment_id=?::uuid and status in ('PLANNED','SHIPPED')",
        shortage,
        at,
        tenant,
        consignmentId);
    event(
        tenant,
        "ARRIVED",
        consignmentId,
        null,
        null,
        null,
        null,
        "回国收货 " + received + " 件（破损 " + damaged + " / 短少 " + shortage + "），入 " + warehouse);
    return detailOf(tenant, consignmentId);
  }

  /**
   * 登记重新入库 / 报废分录（→ 7 库存 / 10.7.1.3）。
   *
   * <p>🔴 分录恒为 PENDING：消费方 7.4.1.1 与 10.7.1.3 **均未交付**，
   * 本模块**只产出事实**，不动 warehouse 的任何表。
   */
  public ReturnShipbackDetail recordEntry(UUID consignmentId, RecordShipbackEntryRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    if (!"ARRIVED".equals(consignment.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "退运单还没到货：重新入库 / 报废分录只能在回国收货之后登记");
    }
    if (request.lineId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定明细行（lineId）");
    }
    String type =
        enumCode(
            request.entryType(),
            EntryType.values(),
            "去向类型",
            "重新入库（→ 7 库存）/ 报废（→ 10.7.1.3）");
    BigDecimal qty = positive(request.qty(), "入库 / 报废数量");
    var line =
        sql.fetchOne(
            "select id,sku_code,sku_name,batch_no,qty from return_shipback_line"
                + " where tenant_id=?::uuid and id=?::uuid and consignment_id=?::uuid",
            tenant,
            request.lineId(),
            consignmentId);
    if (line == null) {
      throw notFound("退运单明细行");
    }
    String consumer = "RESTOCK".equals(type) ? "7.4.1.1" : "10.7.1.3";
    String warehouse =
        defaultText(request.domesticWarehouseRef(), consignment.get("domestic_warehouse_ref", String.class));
    UUID id = Ids.next();
    String no = nextNo("return_shipback_entry_no_seq", "SBE");
    sql.execute(
        "insert into return_shipback_inventory_entry"
            + " (id,tenant_id,consignment_id,line_id,entry_type,qty,sku_code,sku_name,batch_no,"
            + "  domestic_warehouse_ref,entry_no,posting_status,consumer_module,issued_at,issued_by,note)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?::numeric,?,?,?,?,?,'PENDING',?,"
            + "  ?::timestamptz,?::uuid,?)",
        id,
        tenant,
        consignmentId,
        request.lineId(),
        type,
        qty,
        line.get("sku_code", String.class),
        line.get("sku_name", String.class),
        line.get("batch_no", String.class),
        warehouse,
        no,
        consumer,
        now(),
        actorOrFail(),
        text(request.note()));
    sql.execute(
        "update return_shipback_line set status=case when ?='SCRAP' then 'SCRAPPED'"
            + " else 'RECEIVED' end, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        type,
        now(),
        tenant,
        request.lineId());
    event(
        tenant,
        "ENTRY_ISSUED",
        consignmentId,
        request.lineId(),
        null,
        null,
        id,
        "出入库 / 报废分录 " + no + "：" + type + " " + qty + " 件（消费方 " + consumer + "，恒为 PENDING）");
    return detailOf(tenant, consignmentId);
  }

  public ReturnShipbackDetail close(UUID consignmentId, AdvanceShipbackRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    if (!"ARRIVED".equals(consignment.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已到货的退运单可以关闭");
    }
    long openEntries =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_inventory_entry"
                    + " where tenant_id=?::uuid and consignment_id=?::uuid",
                tenant,
                consignmentId));
    long lines =
        count(
            sql.fetchOne(
                "select count(*) from return_shipback_line where tenant_id=?::uuid and consignment_id=?::uuid",
                tenant,
                consignmentId));
    if (openEntries < lines) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "还有 " + (lines - openEntries) + " 行没有登记重新入库 / 报废分录：关闭前每一行都要有去向");
    }
    OffsetDateTime at = request == null || request.occurredAt() == null ? now() : request.occurredAt();
    sql.execute(
        "update return_shipback_consignment set status='CLOSED', closed_by=?::uuid,"
            + " closed_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        actorOrFail(),
        at,
        tenant,
        consignmentId);
    event(tenant, "CLOSED", consignmentId, null, null, null, null, "退运单已关闭");
    return detailOf(tenant, consignmentId);
  }

  public ReturnShipbackDetail cancel(UUID consignmentId, AdvanceShipbackRequest request) {
    UUID tenant = currentTenant();
    var consignment = requireConsignment(tenant, consignmentId);
    String status = consignment.get("status", String.class);
    if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有草稿 / 已确认的退运单可以取消（当前 " + statusLabel(status) + "）：货已经发出就只能走收货与入库");
    }
    String reason = request == null ? "" : text(request.cancelReason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "取消理由不能为空");
    }
    OffsetDateTime at = request == null || request.occurredAt() == null ? now() : request.occurredAt();
    sql.execute(
        "update return_shipback_consignment set status='CANCELLED', cancelled_by=?::uuid,"
            + " cancelled_at=?::timestamptz, cancel_reason=?, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        actorOrFail(),
        at,
        reason,
        tenant,
        consignmentId);
    event(tenant, "CANCELLED", consignmentId, null, null, null, null, "取消退运单：" + reason);
    return detailOf(tenant, consignmentId);
  }

  // ==================================================================
  // 留痕
  // ==================================================================

  public ReturnShipbackEventPage events(UUID consignmentId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where e.tenant_id=?::uuid");
    if (consignmentId != null) {
      where.append(" and e.consignment_id=?::uuid");
      args.add(consignmentId);
    }
    long total = count(sql.fetchOne("select count(*) from return_shipback_event e" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select e.* from return_shipback_event e" + where
                + " order by e.occurred_at desc, e.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnShipbackEventRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(eventRow(r));
    }
    return new ReturnShipbackEventPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "留痕是追加式的：已写入的事件不许改、不许删（DB 触发器 + 权限两道闸）。");
  }

  private void event(
      UUID tenant,
      String type,
      UUID consignmentId,
      UUID lineId,
      UUID costId,
      UUID declarationId,
      UUID entryId,
      String detail) {
    sql.execute(
        "insert into return_shipback_event"
            + " (id,tenant_id,event_type,consignment_id,line_id,cost_id,declaration_id,entry_id,"
            + "  actor_id,occurred_at,detail)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,"
            + "  ?::timestamptz,?)",
        Ids.next(),
        tenant,
        type,
        consignmentId,
        lineId,
        costId,
        declarationId,
        entryId,
        actorOrFail(),
        now(),
        text(detail));
  }
}
