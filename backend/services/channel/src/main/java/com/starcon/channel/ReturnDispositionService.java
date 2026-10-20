package com.starcon.channel;

import static com.starcon.channel.ReturnDispositionModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
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
 * 10.7.1.1 退货接收与处置决策。
 *
 * <p>蓝图：「管理买家退回商品的接收与处置决策：本地仓/海外仓/平台仓接收登记，判定
 * <b>转销售（可再售）/退运回国/弃置销毁</b>，记录处置成本（退货运费、销毁费、平台处理费）
 * 与残值。输入：退货实收记录（11.2.2.1）；处置决策与成本参数；商品再销售限制
 * （品牌方要求/平台政策）。输出：处置单（处置方式、数量、成本、残值）；库存状态变更指令
 * （→ 7）；处置成本（→ 12 费用）。」
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句。它的落地是三件事同时做，缺一件就退化成
 * 「默认转销售」那种假活：
 *
 * <ol>
 *   <li><b>处置方式没有默认值</b>：{@link #decide} 显式要求 {@code dispositionMethod}
 *       必填且在三值白名单内，DB 列上<b>不写 DEFAULT</b>（V214）。少给一个值 ⇒
 *       请求被拒，而不是悄悄落成「转销售」。</li>
 *   <li><b>判定留痕</b>：去向落在**决策**上（谁、什么时候、依据什么），
 *       处置单只能引用一条决策。决策一旦被消费或被取代就是终态（DB 触发器冻结）。</li>
 *   <li><b>转销售要过两道路</b>：上游 11.2.3.1 的质检结论必须是 RESALABLE，
 *       且本租户没有命中任何品牌方 / 平台政策的再销售限制。两条都由服务端判
 *       （{@link #eligibility}），越权判定在服务层与 DB 各拒一次。</li>
 * </ol>
 *
 * <p>数据来源纪律：退货实收与质检结论在 <b>trade</b> 服务（11.2 退货管理），
 * 本服务<b>一条 SQL 都不碰对方的库</b>，只经 {@link ReturnReceiptQueryPort} 读
 * 授权 HTTP API（转发调用方自己的 bearer，租户仍由上游按已认证成员关系解析，
 * 隔离是两道）。库存状态变更指令（→ 7 库存）本模块<b>只产出事实</b>，
 * 不改 warehouse 的任何表。处置成本（→ 12 财务）落在本服务自持的处置单里 ——
 * finance 服务当前不存在（见 V214 第四节）。
 */
@Service
public class ReturnDispositionService {

  private final DSLContext sql;
  private final ReturnReceiptQueryPort upstream;

  public ReturnDispositionService(DSLContext sql, ReturnReceiptQueryPort upstream) {
    this.sql = sql;
    this.upstream = upstream;
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

  private static String method(String raw) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    for (DispositionMethod m : DispositionMethod.values()) {
      if (m.name().equals(value)) {
        return value;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "处置方式必须是 "
            + String.join(" / ", DispositionMethod.codes())
            + " 之一：转销售/退运回国/弃置销毁必须显式给出，本模块不替任何人默认");
  }

  private static String label(String raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    for (DispositionMethod m : DispositionMethod.values()) {
      if (m.name().equals(raw)) {
        return m.label();
      }
    }
    return fallback;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public ReturnDispositionCapabilities capabilities() {
    List<CapabilityDimension> dimensions =
        List.of(
            new CapabilityDimension(
                "receiving_registration",
                "本地仓 / 海外仓 / 平台仓接收登记（货到了哪个仓、由谁登记）",
                true,
                "本服务 V214 return_receiving_registration",
                "",
                "本模块只登记接收视角；入库分录与待检分录是上游 11.2.2.1 的事实，本模块不重做"),
            new CapabilityDimension(
                "upstream_receipt",
                "上游退货实收记录与质检结论（经 trade 授权 HTTP API 读）",
                true,
                "trade 11.2.2.1 / 11.2.3.1，经 ReturnReceiptQueryPort",
                "",
                "上游读不到一律 503，绝不返回空集合冒充「上游没有退货」"),
            new CapabilityDimension(
                "disposition_decision",
                "🔴 处置决策（转销售 / 退运回国 / 弃置销毁）—— **显式，无默认值**",
                true,
                "本服务 V214 return_disposition_decision",
                "",
                "处置方式必须由人显式给出并留下依据；被消费或被取代后即冻结"),
            new CapabilityDimension(
                "resale_restriction",
                "🔴 商品再销售限制（品牌方要求 / 平台政策）—— 本服务自持",
                true,
                "本服务 V214 return_resale_restriction",
                "",
                "全库查过：trade / catalog / warehouse 都没有这类表；蓝图把它列为本模块的输入，所以归本模块自持"),
            new CapabilityDimension(
                "resale_veto",
                "🔴 转销售的服务端否决（质检结论 + 政策限制）",
                true,
                "本服务 V214 列级 CHECK + 服务层 eligibility 判定",
                "",
                "没有可再销售的质检结论、或命中任一限制 ⇒ 转销售被拒；残次品当新品卖是退货里最贵的错"),
            new CapabilityDimension(
                "disposition_cost",
                "处置成本（退货运费 / 销毁费 / 平台处理费）与残值",
                true,
                "本服务 V214 return_disposition_cost + return_disposition_order",
                "",
                "🔴 12 财务（finance 服务）当前不存在 ⇒ 成本与残值以本服务自有事实落地，"
                    + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接"),
            new CapabilityDimension(
                "cost_currency",
                "🔴 处置成本币种 + 残值币种（两个独立币种，**不做汇率换算**）",
                true,
                "本服务 V214 cost_currency / salvage_currency",
                "",
                "成本明细行币种必须与成本币种逐字相同；残值币种可不同；"
                    + "本模块没有汇率表也没有折算列，净额归 12 财务"),
            new CapabilityDimension(
                "inventory_instruction",
                "库存状态变更指令（→ 7 库存）—— **只出事实，不动对方表**",
                true,
                "本服务 V214 return_inventory_change_instruction",
                "7.4.1.1",
                "🔴 消费方 7.4.1.1 调整单管理（warehouse）**当前未交付** ⇒ 指令恒为 PENDING，"
                    + "本模块绝不读写 warehouse 的任何表"),
            new CapabilityDimension(
                "repatriation",
                "退运回国（批量退运单 / 报关 / 成本分摊）",
                false,
                "下游 10.7.1.2 退运回国未交付",
                "10.7.1.2",
                "本模块只出「这批货决定退运回国」这个决策，不建退运单、不算合箱成本"),
            new CapabilityDimension(
                "disposal",
                "弃置与销毁（销毁方式 / 凭证 / 照片 / 重量记录）",
                false,
                "下游 10.7.1.3 弃置与销毁未交付",
                "10.7.1.3",
                "本模块只出「这批货决定弃置销毁」这个决策，不出销毁凭证、不做索赔依据"),
            new CapabilityDimension(
                "supplier_claim",
                "供应商 / 品牌索赔（依据销毁数量与凭证）",
                false,
                "上游 11.2.3.3 供应商索赔（trade V188）已交付，但销毁凭证归 10.7.1.3",
                "11.2.3.3",
                "本模块不建索赔单：销毁数量与凭证由 10.7.1.3 产出后才谈得上索赔"));
    return new ReturnDispositionCapabilities(
        dimensions,
        DispositionMethod.codes(),
        WarehouseType.codes(),
        CostType.codes(),
        RestrictionSubjectType.codes(),
        RestrictionBasis.codes(),
        "🔴 处置方式（转销售 / 退运回国 / 弃置销毁）**没有默认值**：必须由人显式给出，"
            + "并在决策上留下「谁、什么时候、凭什么」三条证据。默认按可再售处理会高估回收价值。",
        "🔴 处置成本与残值是**两个币种**：成本明细行与成本币种逐字相同，残值币种可以不同；"
            + "本模块**不做任何汇率换算**，也没有汇率表与折算列。",
        "🔴 下游 12 财务（finance 服务）当前不存在：处置成本与残值以本服务自有事实落地，"
            + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接；"
            + "本模块绝不读写一个不存在的 finance 服务。",
        "🔴 库存状态变更指令只产出一条事实记录：消费方 7.4.1.1 调整单管理（warehouse）"
            + "当前未交付，指令恒为 PENDING，本模块不动 warehouse 的任何表。",
        "上游实收与质检结论在 trade 服务（11.2.2.1 / 11.2.3.1）：只经授权 HTTP API 读，"
            + "转发调用方自己的 bearer；上游不可达一律 503（读不到），"
            + "上游正常返回但没有该行则按「尚未判定」处理 —— 两者不是一回事。",
        "本模块站在 11.2 之上做**处置决策**，不重做入库流程：入库分录、待检分录、"
            + "撤销与冲正都是 trade 的事实。");
  }

  // ==================================================================
  // 概览
  // ==================================================================

  public ReturnDispositionOverview overview() {
    UUID tenant = currentTenant();
    long registrations = count(sql.fetchOne(
        "select count(*) from return_receiving_registration where tenant_id=?::uuid", tenant));
    long pending =
        count(
            sql.fetchOne(
                "select count(*) from return_receiving_registration g"
                    + " where g.tenant_id=?::uuid and not exists ("
                    + "   select 1 from return_disposition_order o"
                    + "    where o.tenant_id=g.tenant_id and o.registration_id=g.id)",
                tenant));
    long issued =
        count(
            sql.fetchOne(
                "select count(*) from return_disposition_order where tenant_id=?::uuid"
                    + " and status='ISSUED'",
                tenant));
    long cancelled =
        count(
            sql.fetchOne(
                "select count(*) from return_disposition_order where tenant_id=?::uuid"
                    + " and status='CANCELLED'",
                tenant));
    long resale = decisionCount(tenant, "RESALE");
    long repatriation = decisionCount(tenant, "RETURN_TO_COUNTRY");
    long discard = decisionCount(tenant, "DISCARD");
    long restricted =
        count(
            sql.fetchOne(
                "select count(*) from return_receiving_registration g"
                    + " where g.tenant_id=?::uuid and exists ("
                    + "   select 1 from return_resale_restriction r"
                    + "    where r.tenant_id=g.tenant_id and r.status='ACTIVE'"
                    + "      and r.effective_from <= now()"
                    + "      and (r.effective_to is null or r.effective_to > now())"
                    + "      and ((r.subject_type='SKU' and r.subject_ref=g.sku_code)"
                    + "        or (r.subject_type='BRAND' and r.subject_ref=g.brand_code)"
                    + "        or (r.subject_type='PLATFORM' and r.subject_ref=g.platform_code)))",
                tenant));
    List<CurrencyTotal> costTotals = currencyTotals(tenant, true);
    List<CurrencyTotal> salvageTotals = currencyTotals(tenant, false);
    return new ReturnDispositionOverview(
        registrations,
        pending,
        issued,
        cancelled,
        resale,
        repatriation,
        discard,
        restricted,
        costTotals,
        salvageTotals,
        now(),
        "「待决策」= 还没有下发过处置单的接收登记；处置分布按**未被取代**的决策统计。",
        "🔴 成本与残值**按币种分行**：本模块不做汇率换算，跨币种绝不相加。");
  }

  private long decisionCount(UUID tenant, String method) {
    return count(
        sql.fetchOne(
            "select count(*) from return_disposition_decision"
                + " where tenant_id=?::uuid and disposition_method=? and status in ('RECORDED','CONSUMED')",
            tenant,
            method));
  }

  /** 成本 / 残值按币种分行（🔴 跨币种绝不相加）。 */
  private List<CurrencyTotal> currencyTotals(UUID tenant, boolean cost) {
    String sqlText =
        cost
            ? "select c.currency, sum(c.amount) as amount, count(*) as item_count"
                + "  from return_disposition_cost c"
                + "  join return_disposition_order o on o.tenant_id=c.tenant_id and o.id=c.order_id"
                + " where c.tenant_id=?::uuid and o.status='ISSUED'"
                + " group by c.currency order by c.currency"
            : "select o.salvage_currency as currency, sum(o.salvage_amount) as amount,"
                + " count(*) as item_count"
                + "  from return_disposition_order o"
                + " where o.tenant_id=?::uuid and o.status='ISSUED'"
                + " group by o.salvage_currency order by o.salvage_currency";
    return sql.fetch(sqlText, tenant)
        .map(
            r ->
                new CurrencyTotal(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("item_count", Long.class)));
  }

  private static long count(Record row) {
    return row == null ? 0L : row.get(0, Long.class);
  }

  // ==================================================================
  // 接收登记
  // ==================================================================

  private static final String REGISTRATION_COLUMNS =
      "g.id,g.registration_no,g.receipt_id,g.receipt_line_id,g.receipt_no,g.sku_code,g.sku_name,"
          + "g.batch_no,g.brand_code,g.platform_code,g.warehouse_type,g.warehouse_ref,"
          + "g.warehouse_country,g.received_qty,g.received_at,g.registered_by,g.note,g.version,"
          + "d.id as decision_id,d.disposition_method,o.id as order_id,"
          + "(select count(*) from return_resale_restriction r"
          + "  where r.tenant_id=g.tenant_id and r.status='ACTIVE'"
          + "    and r.effective_from <= now()"
          + "    and (r.effective_to is null or r.effective_to > now())"
          + "    and ((r.subject_type='SKU' and r.subject_ref=g.sku_code)"
          + "      or (r.subject_type='BRAND' and r.subject_ref=g.brand_code)"
          + "      or (r.subject_type='PLATFORM' and r.subject_ref=g.platform_code)))"
          + "  as active_restriction_count";

  private static final String REGISTRATION_FROM =
      " from return_receiving_registration g"
          + " left join lateral ("
          + "   select dd.id, dd.disposition_method from return_disposition_decision dd"
          + "    where dd.tenant_id=g.tenant_id and dd.registration_id=g.id"
          + "    order by dd.decided_at desc, dd.created_at desc limit 1) d on true"
          + " left join lateral ("
          + "   select oo.id from return_disposition_order oo"
          + "    where oo.tenant_id=g.tenant_id and oo.registration_id=g.id"
          + "    order by oo.issued_at desc limit 1) o on true";

  public ReturnReceivingPage registrations(
      String keyword, String warehouseType, String dispositionState, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where g.tenant_id=?::uuid");
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (g.sku_code ilike ? or g.sku_name ilike ? or g.receipt_no ilike ?"
              + " or g.batch_no ilike ? or g.warehouse_ref ilike ? or g.registration_no ilike ?)");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
    }
    String wt = text(warehouseType).toUpperCase(Locale.ROOT);
    if (!wt.isEmpty()) {
      where.append(" and g.warehouse_type=?");
      args.add(wt);
    }
    String ds = text(dispositionState).toUpperCase(Locale.ROOT);
    if (!ds.isEmpty()) {
      switch (ds) {
        case "PENDING_DECISION" -> where.append(" and o.id is null");
        case "DECIDED" -> where.append(" and d.id is not null and o.id is null");
        case "ORDERED" -> where.append(" and o.id is not null");
        default ->
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "处置状态筛选只支持 PENDING_DECISION / DECIDED / ORDERED");
      }
    }
    // 🔴 计数查询必须带**同一组 lateral join**：`dispositionState` 筛选引用的是
    //    `d.id` / `o.id`（最近一条决策、最近一张处置单），漏掉 join 的话
    //    「待决策 / 已决策 / 已下发」三种筛选在计数上直接报 SQL 语法错，
    //    或者更糟 —— 换成别的写法后计数与列表对不上。
    long total =
        count(
            sql.fetchOne(
                "select count(*)" + REGISTRATION_FROM + where, args.toArray()));
    var rows =
        sql.fetch(
            "select " + REGISTRATION_COLUMNS + REGISTRATION_FROM + where
                + " order by g.received_at desc, g.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnReceivingRow> items = rows.map(this::registrationRow);
    return new ReturnReceivingPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "接收登记 = 货到了哪个仓（本地仓 / 海外仓 / 平台仓）、由谁在什么时候登记的；"
            + "入库分录与待检分录是上游 11.2.2.1 的事实，本模块不重做。",
        "🔴 处置状态是**本服务的判定进度**，不是处置方式：处置方式必须显式给出，见处置决策。");
  }

  /**
   * 登记一次接收。
   *
   * <p>🔴 上游实收单必须是**已入库（POSTED）**的：DRAFT 还在录、上游随时会改行；
   * CANCELLED 已经作废。拿一条没定稿的实收单做处置决策，等于给一个会变的数签字。
   */
  public ReturnReceivingRow register(RegisterReceivingRequest request, String bearer) {
    UUID tenant = currentTenant();
    if (request.receiptId() == null || request.receiptLineId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须指定上游退货实收单与实收行（receiptId / receiptLineId）");
    }
    String warehouseType = text(request.warehouseType()).toUpperCase(Locale.ROOT);
    boolean known = false;
    for (WarehouseType w : WarehouseType.values()) {
      known |= w.name().equals(warehouseType);
    }
    if (!known) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "接收仓类型必须是 " + String.join(" / ", WarehouseType.codes()) + " 之一");
    }
    String warehouseRef = text(request.warehouseRef());
    if (warehouseRef.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接收仓不能为空");
    }
    String country = text(request.warehouseCountry()).toUpperCase(Locale.ROOT);
    if (!country.isEmpty() && country.length() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库国家必须是两位国家代码");
    }
    BigDecimal qty = positive(request.receivedQty(), "接收数量");
    OffsetDateTime receivedAt = request.receivedAt() == null ? now() : request.receivedAt();

    var receipt = upstream.receipt(request.receiptId(), bearer);
    if (receipt == null || receipt.head() == null) {
      throw notFound("上游退货实收单");
    }
    String status = text(receipt.head().receiptStatus()).toUpperCase(Locale.ROOT);
    if (!"POSTED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上游退货实收单当前状态为 "
              + (status.isEmpty() ? "未知" : status)
              + "：请先在 11.2.2.1 完成入库（POSTED）再登记接收 —— 还在录的实收单随时会改行");
    }
    ReturnReceiptQueryPort.ReceiptLineView line = null;
    if (receipt.lines() != null) {
      for (ReturnReceiptQueryPort.ReceiptLineView l : receipt.lines()) {
        if (request.receiptLineId().equals(l.id())) {
          line = l;
          break;
        }
      }
    }
    if (line == null) {
      throw notFound("上游退货实收行");
    }
    if (line.receivedQty() != null && qty.compareTo(line.receivedQty()) > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "登记数量 " + qty + " 超过上游实收行的实收数量 " + line.receivedQty());
    }
    var existing =
        sql.fetchOne(
            "select id from return_receiving_registration where tenant_id=?::uuid and receipt_line_id=?::uuid",
            tenant,
            request.receiptLineId());
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这一条上游实收行已经登记过接收了：一条实收行只登记一次");
    }

    UUID id = Ids.next();
    String no = nextNo("return_receiving_registration_no_seq", "RCV");
    sql.execute(
        "insert into return_receiving_registration"
            + " (id,tenant_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
            + "  brand_code,platform_code,warehouse_type,warehouse_ref,warehouse_country,received_qty,"
            + "  received_at,registered_by,note,registration_no)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?::numeric,"
            + "  ?::timestamptz,?::uuid,?,?)",
        id,
        tenant,
        request.receiptId(),
        request.receiptLineId(),
        text(receipt.head().receiptNo()),
        text(line.skuCode()),
        text(line.skuName()),
        text(line.batchNo()),
        text(request.brandCode()).toUpperCase(Locale.ROOT),
        text(request.platformCode()).toUpperCase(Locale.ROOT),
        warehouseType,
        warehouseRef,
        country,
        qty,
        receivedAt,
        actorOrFail(),
        text(request.note()),
        no);
    event(
        tenant,
        "RECEIVED",
        id,
        null,
        null,
        null,
        "接收登记 " + no + "：" + warehouseType + " " + warehouseRef + " 收到 " + qty + " 件");
    return registrationRow(sql.fetchOne("select " + REGISTRATION_COLUMNS + REGISTRATION_FROM + " where g.id=?::uuid", id));
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

  private ReturnReceivingRow registrationRow(Record r) {
    UUID decisionId = r.get("decision_id", UUID.class);
    UUID orderId = r.get("order_id", UUID.class);
    String method = r.get("disposition_method", String.class);
    String state = orderId != null ? "ORDERED" : (decisionId != null ? "DECIDED" : "PENDING_DECISION");
    return new ReturnReceivingRow(
        r.get("id", UUID.class),
        r.get("registration_no", String.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        r.get("warehouse_type", String.class),
        warehouseTypeLabel(r.get("warehouse_type", String.class)),
        r.get("warehouse_ref", String.class),
        r.get("warehouse_country", String.class),
        r.get("received_qty", BigDecimal.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("registered_by", UUID.class),
        r.get("note", String.class),
        state,
        method,
        method == null ? null : label(method, method),
        decisionId,
        orderId,
        r.get("active_restriction_count", Long.class),
        r.get("version", Long.class));
  }

  private static String warehouseTypeLabel(String raw) {
    for (WarehouseType w : WarehouseType.values()) {
      if (w.name().equals(raw)) {
        return w.label();
      }
    }
    return raw;
  }

  // ==================================================================
  // 再销售限制
  // ==================================================================

  public ReturnResaleRestrictionPage restrictions(
      String subjectType, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    String st = text(subjectType).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and subject_type=?");
      args.add(st);
    }
    String ss = text(status).toUpperCase(Locale.ROOT);
    if (!ss.isEmpty()) {
      where.append(" and status=?");
      args.add(ss);
    }
    long total = count(sql.fetchOne("select count(*) from return_resale_restriction" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select id,subject_type,subject_ref,restriction_basis,reason,effective_from,effective_to,"
                + "status,revoked_at,revoke_reason,version"
                + " from return_resale_restriction"
                + where
                + " order by status asc, subject_type asc, subject_ref asc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnResaleRestrictionRow> items = rows.map(this::restrictionRow);
    return new ReturnResaleRestrictionPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "🔴 再销售限制是**本服务自持**的租户级政策：全库查过，trade / catalog / warehouse "
            + "都没有这类表，蓝图把它列为 10.7.1.1 的输入。它**全租户生效**，"
            + "命中即否决转销售（可见性按负责人收窄会让「否决」变成「可绕过的建议」）。");
  }

  public ReturnResaleRestrictionRow saveRestriction(SaveRestrictionRequest request) {
    UUID tenant = currentTenant();
    String subjectType = text(request.subjectType()).toUpperCase(Locale.ROOT);
    boolean known = false;
    for (RestrictionSubjectType t : RestrictionSubjectType.values()) {
      known |= t.name().equals(subjectType);
    }
    if (!known) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "限制对象类型必须是 " + String.join(" / ", RestrictionSubjectType.codes()) + " 之一");
    }
    String subjectRef = text(request.subjectRef()).toUpperCase(Locale.ROOT);
    if (subjectRef.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "限制对象不能为空");
    }
    String basis = text(request.restrictionBasis()).toUpperCase(Locale.ROOT);
    boolean basisKnown = false;
    for (RestrictionBasis b : RestrictionBasis.values()) {
      basisKnown |= b.name().equals(basis);
    }
    if (!basisKnown) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "限制依据必须是 " + String.join(" / ", RestrictionBasis.codes()) + " 之一");
    }
    String reason = text(request.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "限制理由不能为空：只写「不能转销售」没人知道是品牌方还是平台政策");
    }
    OffsetDateTime from = request.effectiveFrom() == null ? now() : request.effectiveFrom();
    if (request.effectiveTo() != null && !request.effectiveTo().isAfter(from)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "限制结束时间必须晚于生效时间");
    }
    var existing =
        sql.fetchOne(
            "select id from return_resale_restriction"
                + " where tenant_id=?::uuid and subject_type=? and subject_ref=?",
            tenant,
            subjectType,
            subjectRef);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这个限制对象已经登记过限制了：要改请先撤销再重新登记");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into return_resale_restriction"
            + " (id,tenant_id,subject_type,subject_ref,restriction_basis,reason,"
            + "  effective_from,effective_to,status)"
            + " values(?::uuid,?::uuid,?,?,?,?,?::timestamptz,?::timestamptz,'ACTIVE')",
        id,
        tenant,
        subjectType,
        subjectRef,
        basis,
        reason,
        from,
        request.effectiveTo());
    event(
        tenant,
        "RESTRICTION_REGISTERED",
        null,
        null,
        null,
        id,
        "登记再销售限制：" + subjectType + " " + subjectRef + "（" + basis + "）");
    return restrictionRow(
        sql.fetchOne(
            "select id,subject_type,subject_ref,restriction_basis,reason,effective_from,"
                + "effective_to,status,revoked_at,revoke_reason,version"
                + " from return_resale_restriction where id=?::uuid",
            id));
  }

  public ReturnResaleRestrictionRow revokeRestriction(
      UUID id, RevokeRestrictionRequest request) {
    UUID tenant = currentTenant();
    String reason = text(request.revokeReason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "撤销理由不能为空");
    }
    var row =
        sql.fetchOne(
            "select status from return_resale_restriction where tenant_id=?::uuid and id=?::uuid",
            tenant,
            id);
    if (row == null) {
      throw notFound("再销售限制");
    }
    if (!"ACTIVE".equals(row.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条限制已经撤销过了");
    }
    sql.execute(
        "update return_resale_restriction set status='REVOKED', revoke_reason=?,"
            + " revoked_by=?::uuid, revoked_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        reason,
        actorOrFail(),
        now(),
        tenant,
        id);
    event(
        tenant,
        "RESTRICTION_REVOKED",
        null,
        null,
        null,
        id,
        "撤销再销售限制：" + reason);
    return restrictionRow(
        sql.fetchOne(
            "select id,subject_type,subject_ref,restriction_basis,reason,effective_from,"
                + "effective_to,status,revoked_at,revoke_reason,version"
                + " from return_resale_restriction where id=?::uuid",
            id));
  }

  private ReturnResaleRestrictionRow restrictionRow(Record r) {
    return new ReturnResaleRestrictionRow(
        r.get("id", UUID.class),
        r.get("subject_type", String.class),
        subjectTypeLabel(r.get("subject_type", String.class)),
        r.get("subject_ref", String.class),
        r.get("restriction_basis", String.class),
        basisLabel(r.get("restriction_basis", String.class)),
        r.get("reason", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("status", String.class),
        r.get("revoked_at", OffsetDateTime.class),
        r.get("revoke_reason", String.class),
        r.get("version", Long.class));
  }

  private static String subjectTypeLabel(String raw) {
    for (RestrictionSubjectType t : RestrictionSubjectType.values()) {
      if (t.name().equals(raw)) {
        return t.label();
      }
    }
    return raw;
  }

  private static String basisLabel(String raw) {
    for (RestrictionBasis b : RestrictionBasis.values()) {
      if (b.name().equals(raw)) {
        return b.label();
      }
    }
    return raw;
  }

  // ==================================================================
  // 可行性判定（只读，无副作用）
  // ==================================================================

  /** 上游质检结论快照。 */
  private record QualitySnapshot(
      UUID inspectionId,
      String verdict,
      OffsetDateTime verdictAt,
      BigDecimal salvageEstimate,
      String salvageCurrency,
      BigDecimal originalValue,
      String note) {}

  /** 命中的再销售限制。 */
  private record RestrictionHit(String subjectType, String subjectRef, String basis, String reason) {}

  /**
   * 判定这批退货的三个去向各自**允许还是被挡**。
   *
   * <p>🔴 它<b>不做决定</b>：处置方式仍然必须由人显式给出。
   * 它只回答「凭什么允许 / 凭什么挡住」—— 规则只有一份，且放在服务端。
   */
  public ReturnDispositionEligibility eligibility(UUID registrationId, String bearer) {
    UUID tenant = currentTenant();
    var registration = requireRegistration(tenant, registrationId);
    var evaluation = evaluate(tenant, registration, bearer);
    return new ReturnDispositionEligibility(
        registrationId,
        registration.skuCode(),
        registration.skuName(),
        registration.receivedQty(),
        evaluation.quality().verdict(),
        evaluation.quality().verdict() == null
            ? null
            : verdictLabel(evaluation.quality().verdict()),
        evaluation.quality().verdictAt(),
        evaluation.quality().note(),
        !evaluation.restrictions().isEmpty(),
        evaluation.restrictionNote(),
        evaluation.methods(),
        now(),
        evaluation.note());
  }

  /**
   * 一次判定的全部结论。
   *
   * <p>⚠️ 决策与可行性预览走<b>同一条</b>路径：两边都调它。
   * 各算一次必然漂移，而漂移的方向一定是「预览说能转销售、真判的时候又不能」。
   */
  private record Evaluation(
      RegistrationView registration,
      QualitySnapshot quality,
      List<RestrictionHit> restrictions,
      List<ReturnMethodEligibility> methods,
      String note,
      String restrictionNote) {}

  private Evaluation evaluate(UUID tenant, RegistrationView registration, String bearer) {
    var quality = qualitySnapshot(tenant, registration, bearer);
    var restrictions = activeRestrictions(tenant, registration);
    List<ReturnMethodEligibility> methods = new ArrayList<>();

    List<String> resaleBlocks = new ArrayList<>();
    if (quality.verdict() == null) {
      resaleBlocks.add("上游 11.2.3.1 尚未给出该实收行的质检结论：没有证据证明它能再销售");
    } else if (!"RESALABLE".equals(quality.verdict())) {
      resaleBlocks.add(
          "上游 11.2.3.1 判定为「" + verdictLabel(quality.verdict()) + "」：不允许转销售");
    }
    StringBuilder hit = new StringBuilder();
    if (!restrictions.isEmpty()) {
      for (int i = 0; i < restrictions.size(); i++) {
        RestrictionHit h = restrictions.get(i);
        if (i > 0) {
          hit.append("；");
        }
        hit
            .append(h.subjectType())
            .append(' ')
            .append(h.subjectRef())
            .append("（")
            .append(basisLabel(h.basis()))
            .append("：")
            .append(h.reason())
            .append("）");
      }
      resaleBlocks.add("命中本租户再销售限制：" + hit);
    }
    methods.add(
        new ReturnMethodEligibility(
            "RESALE",
            DispositionMethod.RESALE.label(),
            resaleBlocks.isEmpty(),
            List.copyOf(resaleBlocks),
            "转销售让货重新变成能卖的东西，所以它要过两道路："
                + "上游质检结论必须是「可再销售」，且本租户没有命中任何再销售限制。"));
    methods.add(
        new ReturnMethodEligibility(
            "RETURN_TO_COUNTRY",
            DispositionMethod.RETURN_TO_COUNTRY.label(),
            true,
            List.of(),
            "🔴 10.7.1.2 退运回国未交付：本模块只出「这批货决定退运回国」这个决策，"
                + "不建退运单、不算合箱成本。"));
    methods.add(
        new ReturnMethodEligibility(
            "DISCARD",
            DispositionMethod.DISCARD.label(),
            true,
            List.of(),
            "🔴 10.7.1.3 弃置与销毁未交付：本模块只出「这批货决定弃置销毁」这个决策，"
                + "不出销毁凭证、不做索赔依据。"));

    String note =
        quality.verdict() == null
            ? "上游没有质检结论：转销售被挡住，另外两个去向可判。"
            : "上游质检结论：「" + verdictLabel(quality.verdict()) + "」。";
    String restrictionNote =
        restrictions.isEmpty()
            ? "没有命中任何品牌方 / 平台政策的再销售限制"
            : "命中本租户再销售限制：" + hit;
    return new Evaluation(
        registration, quality, restrictions, methods, note, restrictionNote);
  }

  /** 上游质检结论快照（读不到就是读不到，读到没有才是「尚未判定」）。 */
  private QualitySnapshot qualitySnapshot(
      UUID tenant, RegistrationView registration, String bearer) {
    List<ReturnReceiptQueryPort.InspectionView> candidates =
        upstream.concludedInspections(registration.receiptId(), bearer);
    UUID best = null;
    OffsetDateTime bestAt = null;
    ReturnReceiptQueryPort.InspectionView bestRow = null;
    int considered = 0;
    for (ReturnReceiptQueryPort.InspectionView c : candidates) {
      if (considered >= 5) {
        break;
      }
      if (!registration.receiptLineId().equals(c.receiptLineId())) {
        continue;
      }
      if (!"CONCLUDED".equals(c.inspectionStatus())) {
        continue;
      }
      considered++;
      OffsetDateTime at = null;
      for (ReturnReceiptQueryPort.InspectionEventView e : upstream.inspectionEvents(c.id(), bearer)) {
        if ("CONCLUDED".equals(e.eventType())
            && e.occurredAt() != null
            && (at == null || e.occurredAt().isAfter(at))) {
          at = e.occurredAt();
        }
      }
      if (best == null || (at != null && (bestAt == null || at.isAfter(bestAt)))) {
        best = c.id();
        bestAt = at;
        bestRow = c;
      }
    }
    if (bestRow == null) {
      return new QualitySnapshot(
          null,
          null,
          null,
          null,
          null,
          null,
          "上游 11.2.3.1 还没有给这一条实收行下质检结论");
    }
    return new QualitySnapshot(
        best,
        bestRow.verdict(),
        bestAt,
        bestRow.salvageAmount(),
        bestRow.salvageCurrency(),
        bestRow.originalValue(),
        "上游 11.2.3.1 的质检结论（取该实收行最近一次已判定）");
  }

  /** 当前生效、且命中这一批货的再销售限制。 */
  private List<RestrictionHit> activeRestrictions(UUID tenant, RegistrationView registration) {
    return sql
        .fetch(
            "select r.subject_type, r.subject_ref, r.restriction_basis, r.reason"
                + " from return_resale_restriction r"
                + " where r.tenant_id=?::uuid and r.status='ACTIVE'"
                + "   and r.effective_from <= now()"
                + "   and (r.effective_to is null or r.effective_to > now())"
                + "   and ((r.subject_type='SKU' and r.subject_ref=?)"
                + "     or (r.subject_type='BRAND' and r.subject_ref=?)"
                + "     or (r.subject_type='PLATFORM' and r.subject_ref=?))"
                + " order by r.subject_type, r.subject_ref",
            tenant,
            registration.skuCode(),
            registration.brandCode(),
            registration.platformCode())
        .map(
            r ->
                new RestrictionHit(
                    r.get("subject_type", String.class),
                    r.get("subject_ref", String.class),
                    r.get("restriction_basis", String.class),
                    r.get("reason", String.class)));
  }

  private static String verdictLabel(String raw) {
    if ("RESALABLE".equals(raw)) {
      return "可再销售";
    }
    if ("DEFECTIVE".equals(raw)) {
      return "残次";
    }
    if ("SCRAPPED".equals(raw)) {
      return "报废";
    }
    return raw;
  }

  // ==================================================================
  // 处置决策
  // ==================================================================

  /** 接收登记的服务层视图。 */
  private record RegistrationView(
      UUID id,
      UUID receiptId,
      UUID receiptLineId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      BigDecimal receivedQty) {}

  private RegistrationView requireRegistration(UUID tenant, UUID id) {
    Record row =
        sql.fetchOne(
            "select id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
                + "brand_code,platform_code,received_qty"
                + " from return_receiving_registration where tenant_id=?::uuid and id=?::uuid",
            tenant,
            id);
    if (row == null) {
      throw notFound("接收登记");
    }
    return new RegistrationView(
        row.get("id", UUID.class),
        row.get("receipt_id", UUID.class),
        row.get("receipt_line_id", UUID.class),
        row.get("receipt_no", String.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("batch_no", String.class),
        row.get("brand_code", String.class),
        row.get("platform_code", String.class),
        row.get("received_qty", BigDecimal.class));
  }

  private static final String DECISION_COLUMNS =
      "id,decision_no,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
          + "batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
          + "decided_by,decided_at,quality_verdict,quality_verdict_at,quality_salvage_estimate,"
          + "quality_salvage_currency,resale_restricted,resale_restriction_note,status,"
          + "consumed_by_order_id,version";

  public ReturnDispositionDecisionPage decisions(
      String dispositionMethod, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    String m = text(dispositionMethod).toUpperCase(Locale.ROOT);
    if (!m.isEmpty()) {
      where.append(" and disposition_method=?");
      args.add(m);
    }
    String s = text(status).toUpperCase(Locale.ROOT);
    if (!s.isEmpty()) {
      where.append(" and status=?");
      args.add(s);
    }
    long total =
        count(sql.fetchOne("select count(*) from return_disposition_decision" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select " + DECISION_COLUMNS + " from return_disposition_decision" + where
                + " order by decided_at desc, id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDispositionDecisionRow> items = rows.map(this::decisionRow);
    return new ReturnDispositionDecisionPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "决策 = 「这一次凭什么这么判」：去向 + 数量 + 依据 + 判定人 + 判定时刻，"
            + "外加判定当时的上游质检结论与政策判定快照。",
        "🔴 被消费（已下发处置单）或被取代的决策即冻结：改它等于改历史。");
  }

  /**
   * 记录一条处置决策。
   *
   * <p>🔴 三件事缺一不可：<b>处置方式显式给出</b>、<b>依据必填</b>、
   * <b>转销售要过服务端的两道路</b>。
   */
  public ReturnDispositionDecisionRow decide(DecideDispositionRequest request, String bearer) {
    UUID tenant = currentTenant();
    if (request.registrationId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定接收登记");
    }
    String method = method(request.dispositionMethod());
    String basis = text(request.decisionBasis());
    if (basis.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "判定依据不能为空：转卖/退运/弃置对利润影响巨大，必须留下凭什么这么判");
    }
    BigDecimal qty = positive(request.decidedQty(), "处置数量");
    var registration = requireRegistration(tenant, request.registrationId());
    if (qty.compareTo(registration.receivedQty()) > 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "处置数量 " + qty + " 超过接收登记的 " + registration.receivedQty());
    }

    // 🔴 服务端判定：转销售的两道路在这里判，判定结论冻结在决策行上。
    //    与可行性预览走**同一条** evaluate 路径 —— 各算一次必然漂移。
    var evaluation = evaluate(tenant, registration, bearer);
    ReturnMethodEligibility chosen = null;
    for (ReturnMethodEligibility m : evaluation.methods()) {
      if (m.method().equals(method)) {
        chosen = m;
        break;
      }
    }
    if (chosen == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "处置方式必须是 " + String.join(" / ", DispositionMethod.codes()) + " 之一");
    }
    if (!chosen.allowed()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "不能判成「" + chosen.methodLabel() + "」：" + String.join("；", chosen.blockingReasons()));
    }

    var quality = evaluation.quality();
    var restrictions = evaluation.restrictions();
    String restrictionNote = evaluation.restrictionNote();

    UUID id = Ids.next();
    OffsetDateTime decidedAt = request.decidedAt() == null ? now() : request.decidedAt();
    // 旧的待下发决策作废（重判）：先让出「同一登记行只能有一条待下发决策」的唯一位。
    var open =
        sql.fetchOne(
            "select id from return_disposition_decision"
                + " where tenant_id=?::uuid and registration_id=?::uuid and status='RECORDED'",
            tenant,
            request.registrationId());
    if (open != null) {
      sql.execute(
          "update return_disposition_decision set status='SUPERSEDED', superseded_by_decision_id=?::uuid,"
              + " version=version+1, updated_at=?::timestamptz"
              + " where tenant_id=?::uuid and id=?::uuid and status='RECORDED'",
          id,
          now(),
          tenant,
          open.get("id", UUID.class));
    }
    String no = nextNo("return_disposition_decision_no_seq", "RDC");
    sql.execute(
        "insert into return_disposition_decision"
            + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
            + "  batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
            + "  decided_by,decided_at,quality_inspection_id,quality_verdict,quality_verdict_at,"
            + "  quality_salvage_estimate,quality_salvage_currency,quality_original_value,"
            + "  resale_restricted,resale_restriction_note,status,decision_no)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?::numeric,?,"
            + "  ?::uuid,?::timestamptz,?::uuid,?,?::timestamptz,?::numeric,?,?::numeric,?,?,'RECORDED',?)",
        id,
        tenant,
        registration.id(),
        registration.receiptId(),
        registration.receiptLineId(),
        registration.receiptNo(),
        registration.skuCode(),
        registration.skuName(),
        registration.batchNo(),
        registration.brandCode(),
        registration.platformCode(),
        method,
        qty,
        basis,
        actorOrFail(),
        decidedAt,
        quality.inspectionId(),
        quality.verdict(),
        quality.verdictAt(),
        quality.salvageEstimate(),
        quality.salvageCurrency(),
        quality.originalValue(),
        !restrictions.isEmpty(),
        restrictionNote,
        no);
    event(
        tenant,
        "DECIDED",
        registration.id(),
        id,
        null,
        null,
        "处置决策 " + no + "：" + label(method, method) + " " + qty + " 件，依据：" + basis);
    return decisionRow(
        sql.fetchOne("select " + DECISION_COLUMNS + " from return_disposition_decision where id=?::uuid", id));
  }

  private ReturnDispositionDecisionRow decisionRow(Record r) {
    String method = r.get("disposition_method", String.class);
    String status = r.get("status", String.class);
    String verdict = r.get("quality_verdict", String.class);
    return new ReturnDispositionDecisionRow(
        r.get("id", UUID.class),
        r.get("decision_no", String.class),
        r.get("registration_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        method,
        label(method, method),
        r.get("decided_qty", BigDecimal.class),
        r.get("decision_basis", String.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        verdict,
        verdict == null ? null : verdictLabel(verdict),
        r.get("quality_verdict_at", OffsetDateTime.class),
        r.get("quality_salvage_estimate", BigDecimal.class),
        r.get("quality_salvage_currency", String.class),
        r.get("resale_restricted", Boolean.class),
        r.get("resale_restriction_note", String.class),
        status,
        decisionStatusLabel(status),
        r.get("consumed_by_order_id", UUID.class),
        r.get("version", Long.class));
  }

  private static String decisionStatusLabel(String raw) {
    for (DecisionStatus s : DecisionStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  public ReturnDispositionDecisionDetail decisionDetail(UUID id) {
    UUID tenant = currentTenant();
    var row =
        sql.fetchOne(
            "select " + DECISION_COLUMNS + " from return_disposition_decision"
                + " where tenant_id=?::uuid and id=?::uuid",
            tenant,
            id);
    if (row == null) {
      throw notFound("处置决策");
    }
    return new ReturnDispositionDecisionDetail(decisionRow(row), events(tenant, null, id, null, null));
  }

  /** 接收登记详情：登记行 + 它的全部决策 + 留痕。 */
  public ReturnReceivingDetail registrationDetail(UUID id) {
    UUID tenant = currentTenant();
    var row =
        sql.fetchOne(
            "select " + REGISTRATION_COLUMNS + REGISTRATION_FROM + " where g.id=?::uuid", id);
    if (row == null) {
      throw notFound("接收登记");
    }
    List<ReturnDispositionDecisionRow> decisions =
        sql.fetch(
                "select " + DECISION_COLUMNS + " from return_disposition_decision"
                    + " where tenant_id=?::uuid and registration_id=?::uuid"
                    + " order by decided_at desc, id desc",
                tenant,
                id)
            .map(this::decisionRow);
    return new ReturnReceivingDetail(
        registrationRow(row), decisions, events(tenant, id, null, null, null));
  }

  // ==================================================================
  // 处置单
  // ==================================================================

  private static final String ORDER_COLUMNS =
      "o.id,o.disposition_no,o.decision_id,o.registration_id,o.receipt_id,o.receipt_line_id,"
          + "o.receipt_no,o.sku_code,o.sku_name,o.batch_no,o.brand_code,o.platform_code,"
          + "o.disposition_method,o.disposed_qty,o.cost_currency,o.salvage_currency,"
          + "o.salvage_amount,o.status,o.issued_by,o.issued_at,o.note,o.version,"
          + "coalesce((select sum(c.amount) from return_disposition_cost c"
          + "  where c.tenant_id=o.tenant_id and c.order_id=o.id),0) as total_cost_amount,"
          + "(select count(*) from return_disposition_cost c"
          + "  where c.tenant_id=o.tenant_id and c.order_id=o.id) as cost_item_count";

  public ReturnDispositionOrderPage orders(
      String dispositionMethod, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where o.tenant_id=?::uuid");
    String m = text(dispositionMethod).toUpperCase(Locale.ROOT);
    if (!m.isEmpty()) {
      where.append(" and o.disposition_method=?");
      args.add(m);
    }
    String s = text(status).toUpperCase(Locale.ROOT);
    if (!s.isEmpty()) {
      where.append(" and o.status=?");
      args.add(s);
    }
    long total =
        count(sql.fetchOne("select count(*) from return_disposition_order o" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select " + ORDER_COLUMNS + " from return_disposition_order o" + where
                + " order by o.issued_at desc, o.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDispositionOrderRow> items = rows.map(this::orderRow);
    return new ReturnDispositionOrderPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "处置单 = 这次处置的执行凭据：去向来自被引用的决策，数量与决策逐字相同，"
            + "成本明细逐笔记、残值按实际口径记。",
        "🔴 成本合计是**查询时现算**的 SUM（不落表）；跨币种不相加。");
  }

  /**
   * 下发一张处置单。
   *
   * <p>🔴 处置方式**不**在这里给 —— 它只能来自被引用的那条决策（DB 触发器钉死
   * 「去向相同 / 数量相同 / 决策只能被消费一次」）。
   */
  public ReturnDispositionOrderRow issueOrder(IssueDispositionOrderRequest request) {
    UUID tenant = currentTenant();
    if (request.decisionId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定被下发的处置决策");
    }
    String costCurrency = currency(request.costCurrency(), "处置成本币种");
    String salvageCurrency = currency(request.salvageCurrency(), "残值币种");
    BigDecimal salvage = nonNegative(request.salvageAmount(), "残值金额");
    var decision =
        sql.fetchOne(
            "select " + DECISION_COLUMNS + " from return_disposition_decision"
                + " where tenant_id=?::uuid and id=?::uuid",
            tenant,
            request.decisionId());
    if (decision == null) {
      throw notFound("处置决策");
    }
    if (!"RECORDED".equals(decision.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条决策已经不是「待下发」状态（当前 "
              + decisionStatusLabel(decision.get("status", String.class))
              + "）：一条决策只能下发一次");
    }
    String method = decision.get("disposition_method", String.class);
    BigDecimal qty = decision.get("decided_qty", BigDecimal.class);
    UUID orderId = Ids.next();
    String no = nextNo("return_disposition_order_no_seq", "RDO");
    OffsetDateTime issuedAt = request.issuedAt() == null ? now() : request.issuedAt();
    sql.execute(
        "insert into return_disposition_order"
            + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
            + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,disposed_qty,"
            + "  cost_currency,salvage_currency,salvage_amount,status,issued_by,issued_at,note,"
            + "  disposition_no)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?::numeric,"
            + "  ?,?,?::numeric,'ISSUED',?::uuid,?::timestamptz,?,?)",
        orderId,
        tenant,
        request.decisionId(),
        decision.get("registration_id", UUID.class),
        decision.get("receipt_id", UUID.class),
        decision.get("receipt_line_id", UUID.class),
        decision.get("receipt_no", String.class),
        decision.get("sku_code", String.class),
        decision.get("sku_name", String.class),
        decision.get("batch_no", String.class),
        decision.get("brand_code", String.class),
        decision.get("platform_code", String.class),
        method,
        qty,
        costCurrency,
        salvageCurrency,
        salvage,
        actorOrFail(),
        issuedAt,
        text(request.note()),
        no);
    // 决策被消费（此后它的结论不可改）
    sql.execute(
        "update return_disposition_decision set status='CONSUMED', consumed_by_order_id=?::uuid,"
            + " version=version+1, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid and status='RECORDED'",
        orderId,
        now(),
        tenant,
        request.decisionId());
    event(
        tenant,
        "ORDER_ISSUED",
        decision.get("registration_id", UUID.class),
        request.decisionId(),
        orderId,
        null,
        "下发处置单 " + no + "：" + label(method, method) + " " + qty + " 件，成本币种 " + costCurrency
            + "，残值 " + salvage + " " + salvageCurrency);

    if (request.costs() != null) {
      for (ReturnCostDraft draft : request.costs()) {
        insertCost(tenant, orderId, draft, costCurrency);
      }
    }
    insertInstruction(tenant, orderId, method, qty, decision, issuedAt);
    return orderRow(
        sql.fetchOne("select " + ORDER_COLUMNS + " from return_disposition_order o where o.id=?::uuid", orderId));
  }

  private void insertCost(UUID tenant, UUID orderId, ReturnCostDraft draft, String costCurrency) {
    String costType = text(draft.costType()).toUpperCase(Locale.ROOT);
    boolean known = false;
    for (CostType c : CostType.values()) {
      known |= c.name().equals(costType);
    }
    if (!known) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "成本类型必须是 " + String.join(" / ", CostType.codes()) + " 之一");
    }
    BigDecimal amount = nonNegative(draft.amount(), "成本金额");
    String cur = text(draft.currency()).isEmpty() ? costCurrency : currency(draft.currency(), "成本币种");
    if (!cur.equals(costCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "成本明细的币种必须与处置单的成本币种（" + costCurrency + "）逐字相同：混币种的合计是没有意义的数字");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into return_disposition_cost"
            + " (id,tenant_id,order_id,cost_type,amount,currency,vendor_ref,incurred_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?::numeric,?,?,?::timestamptz,?)",
        id,
        tenant,
        orderId,
        costType,
        amount,
        cur,
        text(draft.vendorRef()),
        draft.incurredAt() == null ? now() : draft.incurredAt(),
        text(draft.note()));
    event(
        tenant,
        "COST_RECORDED",
        null,
        null,
        orderId,
        null,
        "登记处置成本 " + costTypeLabel(costType) + " " + amount + " " + cur);
  }

  /** 库存状态变更指令（→ 7 库存）：只产出一条事实，不动 warehouse 的表。 */
  private void insertInstruction(
      UUID tenant,
      UUID orderId,
      String method,
      BigDecimal qty,
      Record decision,
      OffsetDateTime issuedAt) {
    String instructionType =
        switch (method) {
          case "RESALE" -> "MOVE_TO_SELLABLE";
          case "RETURN_TO_COUNTRY" -> "HOLD_FOR_REPATRIATION";
          default -> "HOLD_FOR_DISPOSAL";
        };
    String targetState =
        switch (instructionType) {
          case "MOVE_TO_SELLABLE" -> "SELLABLE";
          case "HOLD_FOR_REPATRIATION" -> "RETURN_PENDING";
          default -> "DISPOSAL_PENDING";
        };
    UUID id = Ids.next();
    sql.execute(
        "insert into return_inventory_change_instruction"
            + " (id,tenant_id,order_id,disposition_method,instruction_type,target_inventory_state,"
            + "  sku_code,sku_name,batch_no,qty,issued_at,issued_by)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?::numeric,?::timestamptz,?::uuid)",
        id,
        tenant,
        orderId,
        method,
        instructionType,
        targetState,
        decision.get("sku_code", String.class),
        decision.get("sku_name", String.class),
        decision.get("batch_no", String.class),
        qty,
        issuedAt,
        actorOrFail());
    event(
        tenant,
        "INSTRUCTION_ISSUED",
        null,
        null,
        orderId,
        null,
        "产出库存状态变更指令 " + instructionType + "（目标 " + targetState + "）"
            + " —— 🔴 消费方 7.4.1.1 调整单管理未交付，指令恒为 PENDING");
  }

  public ReturnDispositionOrderDetail orderDetail(UUID id) {
    UUID tenant = currentTenant();
    var row =
        sql.fetchOne(
            "select " + ORDER_COLUMNS + " from return_disposition_order o"
                + " where o.tenant_id=?::uuid and o.id=?::uuid",
            tenant,
            id);
    if (row == null) {
      throw notFound("处置单");
    }
    List<ReturnDispositionCostRow> costs =
        sql.fetch(
                "select id,order_id,cost_type,amount,currency,vendor_ref,incurred_at,note"
                    + " from return_disposition_cost where tenant_id=?::uuid and order_id=?::uuid"
                    + " order by incurred_at asc, id asc",
                tenant,
                id)
            .map(
                c ->
                    new ReturnDispositionCostRow(
                        c.get("id", UUID.class),
                        c.get("order_id", UUID.class),
                        c.get("cost_type", String.class),
                        costTypeLabel(c.get("cost_type", String.class)),
                        c.get("amount", BigDecimal.class),
                        c.get("currency", String.class),
                        c.get("vendor_ref", String.class),
                        c.get("incurred_at", OffsetDateTime.class),
                        c.get("note", String.class)));
    var instructionRow =
        sql.fetchOne(
            "select id,order_id,disposition_method,instruction_type,target_inventory_state,sku_code,"
                + "sku_name,batch_no,qty,instruction_status,consumer_module,issued_at,issued_by"
                + " from return_inventory_change_instruction where tenant_id=?::uuid and order_id=?::uuid",
            tenant,
            id);
    ReturnInventoryInstructionRow instruction = null;
    if (instructionRow != null) {
      instruction =
          new ReturnInventoryInstructionRow(
              instructionRow.get("id", UUID.class),
              instructionRow.get("order_id", UUID.class),
              instructionRow.get("disposition_method", String.class),
              instructionRow.get("instruction_type", String.class),
              instructionTypeLabel(instructionRow.get("instruction_type", String.class)),
              instructionRow.get("target_inventory_state", String.class),
              targetStateLabel(instructionRow.get("target_inventory_state", String.class)),
              instructionRow.get("sku_code", String.class),
              instructionRow.get("sku_name", String.class),
              instructionRow.get("batch_no", String.class),
              instructionRow.get("qty", BigDecimal.class),
              instructionRow.get("instruction_status", String.class),
              instructionRow.get("consumer_module", String.class),
              instructionRow.get("issued_at", OffsetDateTime.class),
              instructionRow.get("issued_by", UUID.class));
    }
    return new ReturnDispositionOrderDetail(
        orderRow(row), costs, instruction, events(tenant, null, null, null, id));
  }

  private ReturnDispositionOrderRow orderRow(Record r) {
    String method = r.get("disposition_method", String.class);
    String status = r.get("status", String.class);
    return new ReturnDispositionOrderRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("decision_id", UUID.class),
        r.get("registration_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("brand_code", String.class),
        r.get("platform_code", String.class),
        method,
        label(method, method),
        r.get("disposed_qty", BigDecimal.class),
        r.get("cost_currency", String.class),
        r.get("salvage_currency", String.class),
        r.get("salvage_amount", BigDecimal.class),
        r.get("total_cost_amount", BigDecimal.class),
        r.get("cost_item_count", Integer.class),
        status,
        orderStatusLabel(status),
        r.get("issued_by", UUID.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class));
  }

  private static String orderStatusLabel(String raw) {
    for (OrderStatus s : OrderStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String costTypeLabel(String raw) {
    for (CostType c : CostType.values()) {
      if (c.name().equals(raw)) {
        return c.label();
      }
    }
    return raw;
  }

  private static String instructionTypeLabel(String raw) {
    for (InstructionType t : InstructionType.values()) {
      if (t.name().equals(raw)) {
        return t.label();
      }
    }
    return raw;
  }

  private static String targetStateLabel(String raw) {
    for (TargetInventoryState s : TargetInventoryState.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  /** 追加一条处置成本（销毁费常常是事后才拿到单据）。 */
  public ReturnDispositionCostRow recordCost(UUID orderId, RecordCostRequest request) {
    UUID tenant = currentTenant();
    var order =
        sql.fetchOne(
            "select cost_currency, status from return_disposition_order"
                + " where tenant_id=?::uuid and id=?::uuid",
            tenant,
            orderId);
    if (order == null) {
      throw notFound("处置单");
    }
    if ("CANCELLED".equals(order.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "已撤销的处置单不能再记成本");
    }
    insertCost(
        tenant,
        orderId,
        new ReturnCostDraft(
            request.costType(),
            request.amount(),
            request.currency(),
            request.vendorRef(),
            request.incurredAt(),
            request.note()),
        order.get("cost_currency", String.class));
    var latest =
        sql.fetchOne(
            "select id,order_id,cost_type,amount,currency,vendor_ref,incurred_at,note"
                + " from return_disposition_cost where tenant_id=?::uuid and order_id=?::uuid"
                + " order by incurred_at desc, id desc limit 1",
            tenant,
            orderId);
    return new ReturnDispositionCostRow(
        latest.get("id", UUID.class),
        latest.get("order_id", UUID.class),
        latest.get("cost_type", String.class),
        costTypeLabel(latest.get("cost_type", String.class)),
        latest.get("amount", BigDecimal.class),
        latest.get("currency", String.class),
        latest.get("vendor_ref", String.class),
        latest.get("incurred_at", OffsetDateTime.class),
        latest.get("note", String.class));
  }

  /**
   * 撤销一张处置单。
   *
   * <p>🔴 撤销**不撤回**已产出的库存状态变更指令：指令是「当时下达过什么」的事实，
   * 消费方 7.4.1.1 未交付，此刻没有任何库存被改动。改判的正确做法是
   * 「撤销本单 → 重新记录一条决策 → 重新下发」。
   */
  public ReturnDispositionOrderRow cancelOrder(UUID orderId, CancelDispositionOrderRequest request) {
    UUID tenant = currentTenant();
    String reason = text(request.cancelReason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "撤销理由不能为空");
    }
    var order =
        sql.fetchOne(
            "select status, version from return_disposition_order where tenant_id=?::uuid and id=?::uuid",
            tenant,
            orderId);
    if (order == null) {
      throw notFound("处置单");
    }
    if ("CANCELLED".equals(order.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张处置单已经撤销过了");
    }
    if (request.expectedVersion() != null
        && request.expectedVersion() != order.get("version", Long.class)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "处置单已被他人改动，请重新载入后再撤销");
    }
    sql.execute(
        "update return_disposition_order set status='CANCELLED', cancel_reason=?, cancelled_at=?::timestamptz,"
            + " version=version+1, updated_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
        reason,
        now(),
        now(),
        tenant,
        orderId);
    event(tenant, "ORDER_CANCELLED", null, null, orderId, null, "撤销处置单：" + reason);
    return orderRow(
        sql.fetchOne("select " + ORDER_COLUMNS + " from return_disposition_order o where o.id=?::uuid", orderId));
  }

  // ==================================================================
  // 留痕
  // ==================================================================

  public ReturnDispositionEventPage events(
      UUID registrationId, UUID decisionId, UUID orderId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    if (registrationId != null) {
      where.append(" and registration_id=?::uuid");
      args.add(registrationId);
    }
    if (decisionId != null) {
      where.append(" and decision_id=?::uuid");
      args.add(decisionId);
    }
    if (orderId != null) {
      where.append(" and order_id=?::uuid");
      args.add(orderId);
    }
    long total = count(sql.fetchOne("select count(*) from return_disposition_event" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select id,event_type,registration_id,decision_id,order_id,actor_id,occurred_at,detail"
                + " from return_disposition_event"
                + where
                + " order by occurred_at desc, id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ReturnDispositionEventRow> items = rows.map(this::eventRow);
    return new ReturnDispositionEventPage(items, Math.max(page, 0), limit, total);
  }

  private List<ReturnDispositionEventRow> events(
      UUID tenant, UUID registrationId, UUID decisionId, UUID orderId, UUID onlyOrderId) {
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    if (registrationId != null) {
      where.append(" and registration_id=?::uuid");
      args.add(registrationId);
    }
    if (decisionId != null) {
      where.append(" and decision_id=?::uuid");
      args.add(decisionId);
    }
    if (onlyOrderId != null) {
      where.append(" and order_id=?::uuid");
      args.add(onlyOrderId);
    } else if (orderId != null) {
      where.append(" and order_id=?::uuid");
      args.add(orderId);
    }
    return sql.fetch(
            "select id,event_type,registration_id,decision_id,order_id,actor_id,occurred_at,detail"
                + " from return_disposition_event"
                + where
                + " order by occurred_at asc, id asc",
            args.toArray())
        .map(this::eventRow);
  }

  private ReturnDispositionEventRow eventRow(Record r) {
    String type = r.get("event_type", String.class);
    String label = type;
    for (EventType t : EventType.values()) {
      if (t.name().equals(type)) {
        label = t.label();
        break;
      }
    }
    return new ReturnDispositionEventRow(
        r.get("id", UUID.class),
        type,
        label,
        r.get("registration_id", UUID.class),
        r.get("decision_id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  /** 追加一条留痕（追加式：表上 UPDATE / DELETE 已收回）。 */
  private void event(
      UUID tenant,
      String type,
      UUID registrationId,
      UUID decisionId,
      UUID orderId,
      UUID restrictionId,
      String detail) {
    sql.execute(
        "insert into return_disposition_event"
            + " (id,tenant_id,event_type,registration_id,decision_id,order_id,restriction_id,"
            + "  actor_id,occurred_at,detail)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::timestamptz,?)",
        Ids.next(),
        tenant,
        type,
        registrationId,
        decisionId,
        orderId,
        restrictionId,
        actorOrFail(),
        now(),
        detail);
  }

  // ==================================================================
  // 工具
  // ==================================================================

  private String nextNo(String sequence, String prefix) {
    long n = sql.fetchOne("select nextval('" + sequence + "')").get(0, Long.class);
    return prefix + n;
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
}
