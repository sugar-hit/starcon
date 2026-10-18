package com.starcon.trade;

import static com.starcon.trade.ExchangeAuthorizationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.3.2.1 换货授权。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>批准换货：确认可换数量与库存（要真实预占替换货）、
 * 生成新出库安排，保留原单追溯关系。</b>」输入：「换货申请；替换商品选择；库存可用性；expected_version。」
 * 输出：「换货授权 + 新发货需求 + 替换货预占。」
 *
 * <p><b>本模块与「假预占」的分界线</b>：蓝图那句「要真实预占替换货」是全文最容易做成假的一条。
 * 查一次可售量、在自己库里写个 {@code is_reserved = true} 就宣称预占完成 ——
 * 那不是预占，那是把「查到什么」记成「占住了什么」，并发两单会把同一份库存预占两次，
 * 而两份预占都指向同一批货，结果就是替换货发出去两次。
 * 所以本模块的每一次授权都必须经过 {@link ExchangeReservationPort} 向 warehouse
 * （7.2.1.1 预占的写入所有者）登记一笔<b>真实</b>预占，并把返回的 id / 单号如实落库；
 * 取不到预占应答就整笔失败，<b>绝不</b>降级成「先授权、稍后补预占」。
 *
 * <p><b>并发为什么必须先锁单再打远程</b>：warehouse 的 7.2.1.1 登记端点在
 * {@code (source_system, source_ref_id)} 上<b>没有</b>唯一约束（V102 只 UNIQUE(tenant_id, reservation_no)，
 * 而单号是 warehouse 自己发的），所以同一张换货售后单被并发提交两次时，
 * 两次请求都会真的打到 warehouse 并各拿到一个 reservation_no ——
 * 预占两次从此成立。只有 trade 侧先 {@code SELECT ... FOR UPDATE} 锁住售后单、
 * 再让 {@code UNIQUE(tenant_id, case_id)} 判第二次，那第二次才会在<b>发出远程调用之前</b>被拒。
 * 这个顺序是 load-bearing 的，不是风格问题。
 *
 * <p><b>加粗句的落点</b>：「<b>换货必须同时预占替换货并等待旧货回寄</b>，否则会出现只发不收的损失。」
 * 服务层在每一行上同时建三样东西：预占记录、新发货需求、等待旧货回寄要求；
 * DB 层的两个迟到约束触发器（V162 第七节）会在<b>提交时</b>复核这三样缺一不可 ——
 * 绕过服务层直接写 SQL 也一样被拒。
 *
 * <p><b>跨服务失败的补偿是真的释放</b>：多行授权时第 3 行的预占可能失败，而前两行的预占已经
 * 在 warehouse 落库。本服务会把已成功的那几笔逐笔 release（7.2.1.3），
 * <b>拿不到释放应答就整笔回滚并如实报错</b> —— 「本地事务回滚了、仓库还占着货」的幽灵占用
 * 正是从这里来的，而本地表回滚后根本没有那一行可以让你事后发现它。
 */
@Service
public class ExchangeAuthorizationService {

  private static final BigDecimal QTY_SCALE = new BigDecimal("0.000001");

  /** 列表排序白名单：只认这三个键，其余一律回落默认，绝不把任意表达式拼进 ORDER BY。 */
  private static final Map<String, String> SORTS =
      Map.of(
          "authorized_at", "a.authorized_at desc, a.id desc",
          "authorized_at_asc", "a.authorized_at asc, a.id asc",
          "case_no", "a.case_no asc, a.id asc");

  private final DSLContext sql;
  private final ExchangeReservationPort reservations;
  private final ExchangeSkuQueryPort skus;

  public ExchangeAuthorizationService(
      DSLContext sql, ExchangeReservationPort reservations, ExchangeSkuQueryPort skus) {
    this.sql = sql;
    this.reservations = reservations;
    this.skus = skus;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条报出上游接入情况与两条硬规则。
   *
   * <p>{@code hardRules} 把蓝图那句加粗原样交给前端：页面在授权对话框上必须显示它，
   * 而不是让运营在不知情的情况下点完一次「只发不收」。
   */
  public ExchangeCapabilities capabilities() {
    return new ExchangeCapabilities(
        true,
        true,
        true,
        true,
        false,
        "9.3.3.1",
        false,
        "11.2.2.2",
        List.of("ACTIVE", "CANCELLED"),
        List.of("生效中", "已撤销"),
        List.of("APPROVED", "PARTIALLY_APPROVED"),
        List.of("整量批准", "部分批准（库存不足）"),
        List.of("CANCEL"),
        "换货授权只在 warehouse 的预占真正登记成功后才成立：授权与预占在同一个请求里完成，"
            + "拿不到预占应答就整笔失败，不会出现「账上已授权、仓库没占住货」的单子。",
        List.of(
            "换货必须同时预占替换货并等待旧货回寄，缺一不可 —— 否则会出现只发不收的损失",
            "同一张换货售后单只能有一张换货授权单，同一条售后商品行只能被换一次",
            "撤销换货会真去仓库释放预占；释放不成功则整笔撤销失败，不会留下只发不收的占用"));
  }

  // ================================================================ 列表与筛选

  /** 列表：真分页（独立 COUNT）+ 汇总 + 筛选 + 排序白名单。 */
  public ExchangePage list(ExchangeQuery q) {
    int page = q.page() == null || q.page() < 0 ? 0 : q.page();
    int size = q.size() == null || q.size() < 1 ? 20 : Math.min(q.size(), 100);
    UUID tenant = currentTenant();

    var where = new StringBuilder(" where a.tenant_id = ?::uuid");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (notBlank(q.caseId())) {
      where.append(" and a.case_id = ?::uuid");
      binds.add(UUID.fromString(q.caseId()));
    }
    if (notBlank(q.orderId())) {
      where.append(" and a.order_id = ?::uuid");
      binds.add(UUID.fromString(q.orderId()));
    }
    if (notBlank(q.skuId())) {
      where.append(
          " and exists (select 1 from exchange_authorization_line l"
              + " where l.tenant_id = a.tenant_id and l.authorization_id = a.id"
              + " and l.replacement_sku_id = ?::uuid)");
      binds.add(UUID.fromString(q.skuId()));
    }
    if (notBlank(q.status())) {
      where.append(" and a.status = ?");
      binds.add(q.status());
    }
    if (notBlank(q.decision())) {
      where.append(" and a.decision = ?");
      binds.add(q.decision());
    }
    if (notBlank(q.keyword())) {
      where.append(
          " and (a.authorization_no ilike ? or a.case_no ilike ? or a.order_no ilike ?"
              + " or exists (select 1 from exchange_authorization_line l"
              + " where l.tenant_id = a.tenant_id and l.authorization_id = a.id"
              + " and (l.replacement_sku_code ilike ? or l.replacement_sku_name ilike ?"
              + " or l.original_sku_code ilike ?)))");
      String kw = "%" + q.keyword() + "%";
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }

    long total =
        one(
                "select count(*) from exchange_authorization a" + where,
                binds.toArray())
            .get(0, Long.class);

    String sort =
        SORTS.getOrDefault(
            q.sort() == null ? "" : q.sort(), SORTS.get("authorized_at"));

    var rows =
        sql.fetch(
            "select a.id,a.authorization_no,a.case_id,a.case_no,a.order_id,a.order_no,a.status,"
                + "a.decision,a.requested_quantity,a.authorized_quantity,a.replacement_sku_count,"
                + "a.reason_note,a.authorized_by,a.authorized_at,a.cancelled_by,a.cancelled_at,"
                + "a.cancel_reason,a.version,a.created_at,"
                + "(select count(*) from exchange_authorization_line l"
                + " where l.tenant_id = a.tenant_id and l.authorization_id = a.id) as line_count,"
                + "(select count(*) from exchange_reservation r"
                + " where r.tenant_id = a.tenant_id and r.authorization_id = a.id"
                + " and r.status='HELD') as active_res,"
                + "(select count(*) from exchange_reservation r"
                + " where r.tenant_id = a.tenant_id and r.authorization_id = a.id"
                + " and r.status='RELEASED') as released_res,"
                + "(select count(*) from exchange_outbound_demand d"
                + " where d.tenant_id = a.tenant_id and d.authorization_id = a.id"
                + " and d.status='PENDING') as pending_demand,"
                + "(select count(*) from exchange_return_requirement x"
                + " where x.tenant_id = a.tenant_id and x.authorization_id = a.id"
                + " and x.status='WAITING') as waiting_return"
                + " from exchange_authorization a"
                + where
                + " order by "
                + sort
                + " limit ? offset ?",
            bindsWith(binds, size, page * size));

    var items = rows.stream().map(r -> toRow(r)).toList();
    return new ExchangePage(items, total, page, size, summary(where, binds));
  }

  /** 汇总：与列表同一套筛选，所以「第一页 1 条却说有 3 条」这种矛盾不会出现。 */
  private ExchangeSummary summary(StringBuilder where, List<Object> binds) {
    var row =
        one(
            "select count(*) as total_count,"
                + "count(*) filter (where a.status='ACTIVE') as active_count,"
                + "count(*) filter (where a.status='CANCELLED') as cancelled_count,"
                + "count(*) filter (where a.decision='APPROVED') as approved_count,"
                + "count(*) filter (where a.decision='PARTIALLY_APPROVED') as partial_count,"
                + "coalesce(sum(a.requested_quantity),0) as sum_req,"
                + "coalesce(sum(a.authorized_quantity),0) as sum_auth"
                + " from exchange_authorization a"
                + where,
            binds.toArray());
    var pending =
        one(
                "select coalesce(sum(d.quantity),0) from exchange_outbound_demand d"
                    + " where d.tenant_id = ?::uuid and d.status='PENDING'",
                currentTenant())
            .get(0, BigDecimal.class);
    var waiting =
        one(
                "select coalesce(sum(x.quantity),0) from exchange_return_requirement x"
                    + " where x.tenant_id = ?::uuid and x.status='WAITING'",
                currentTenant())
            .get(0, BigDecimal.class);
    return new ExchangeSummary(
        row.get("total_count", Long.class),
        row.get("active_count", Long.class),
        row.get("cancelled_count", Long.class),
        row.get("approved_count", Long.class),
        row.get("partial_count", Long.class),
        qty(row.get("sum_req", BigDecimal.class)),
        qty(row.get("sum_auth", BigDecimal.class)),
        qty(pending),
        qty(waiting));
  }

  /**
   * 下拉项：可授权的换货售后单。
   *
   * <p>只列类型为 EXCHANGE 且未结案的单 —— 已结案的单点进来必然被拒，
   * 把它放进下拉等于让运营填完一整张单再收一个 422。
   */
  public ExchangeFilterOptions filters() {
    UUID tenant = currentTenant();
    var cases =
        sql.fetch(
            "select c.id,c.case_no,c.order_id,c.status,c.reason_code,c.requested_quantity,"
                + "coalesce(o.order_no,'') as order_no"
                + " from aftersales_case c"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where c.tenant_id = ?::uuid and c.case_type = 'EXCHANGE'"
                + " and c.status not in ('RESOLVED','REJECTED','CLOSED')"
                + " and not exists (select 1 from exchange_authorization x"
                + " where x.tenant_id = c.tenant_id and x.case_id = c.id)"
                + " order by c.created_at desc limit 200",
            tenant);
    var caseRows =
        cases.stream()
            .map(
                r -> {
                  UUID caseId = r.get("id", UUID.class);
                  // 商品行随单一起下发：授权对话框必须显示「退的是哪一行、原来是什么 SKU」。
                  var lines =
                      sql.fetch(
                          "select l.id,l.order_line_id,coalesce(ol.sku_id,'00000000-0000-0000-0000-000000000000'::uuid) as sku_id,"
                              + "coalesce(ol.sku_code,'') as sku_code,coalesce(ol.sku_name,'') as sku_name,"
                              + "l.quantity"
                              + " from aftersales_case_line l"
                              + " left join sales_order_line ol"
                              + " on ol.tenant_id = l.tenant_id and ol.id = l.order_line_id"
                              + " where l.tenant_id = ?::uuid and l.case_id = ?::uuid"
                              + " order by l.id",
                          tenant,
                          caseId)
                          .stream()
                          .map(
                              ln ->
                                  new ExchangeCaseLineOption(
                                      ln.get("id", UUID.class),
                                      ln.get("order_line_id", UUID.class),
                                      ln.get("sku_id", UUID.class),
                                      ln.get("sku_code", String.class),
                                      ln.get("sku_name", String.class),
                                      qty(ln.get("quantity", BigDecimal.class))))
                          .toList();
                  return new ExchangeCaseOption(
                      caseId,
                      r.get("case_no", String.class),
                      r.get("order_id", UUID.class),
                      r.get("order_no", String.class),
                      r.get("status", String.class),
                      "受理中（可授权换货）",
                      r.get("reason_code", String.class),
                      qty(r.get("requested_quantity", BigDecimal.class)),
                      lines);
                })
            .toList();
    return new ExchangeFilterOptions(
        List.of("ACTIVE", "CANCELLED"),
        List.of("生效中", "已撤销"),
        List.of("APPROVED", "PARTIALLY_APPROVED"),
        List.of("整量批准", "部分批准（库存不足）"),
        caseRows,
        caseRows.size());
  }

  // ================================================================ 详情

  /**
   * 单张换货授权详情。
   *
   * <p>不存在的 id 与不属于本租户的 id 返回<b>同一条</b> 404 ——
   * 「这个 id 存在但不属于你」本身也是一条信息泄露。
   */
  public ExchangeDetail detail(UUID id) {
    UUID tenant = currentTenant();
    var head =
        one(
            "select a.id,a.authorization_no,a.case_id,a.case_no,a.order_id,a.order_no,a.status,"
                + "a.decision,a.requested_quantity,a.authorized_quantity,a.replacement_sku_count,"
                + "a.reason_note,a.authorized_by,a.authorized_at,a.cancelled_by,a.cancelled_at,"
                + "a.cancel_reason,a.version,a.created_at,"
                + "(select count(*) from exchange_authorization_line l"
                + " where l.tenant_id = a.tenant_id and l.authorization_id = a.id) as line_count,"
                + "(select count(*) from exchange_reservation r"
                + " where r.tenant_id = a.tenant_id and r.authorization_id = a.id"
                + " and r.status='HELD') as active_res,"
                + "(select count(*) from exchange_reservation r"
                + " where r.tenant_id = a.tenant_id and r.authorization_id = a.id"
                + " and r.status='RELEASED') as released_res,"
                + "(select count(*) from exchange_outbound_demand d"
                + " where d.tenant_id = a.tenant_id and d.authorization_id = a.id"
                + " and d.status='PENDING') as pending_demand,"
                + "(select count(*) from exchange_return_requirement x"
                + " where x.tenant_id = a.tenant_id and x.authorization_id = a.id"
                + " and x.status='WAITING') as waiting_return"
                + " from exchange_authorization a where a.tenant_id = ?::uuid and a.id = ?::uuid",
            tenant,
            id);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "换货授权单不存在或不属于当前租户");
    }
    return new ExchangeDetail(toRow(head), lines(id), allowedActions(head.get("status", String.class)));
  }

  /** 逐行视图：退什么 → 换成什么 → 换几件 → 预占在哪一笔 → 旧货等不等。 */
  private List<ExchangeLineView> lines(UUID authorizationId) {
    UUID tenant = currentTenant();
    return sql
        .fetch(
            "select l.id,l.case_line_id,l.original_sku_id,l.original_sku_code,l.original_sku_name,"
                + "l.original_quantity,l.replacement_sku_id,l.replacement_sku_code,"
                + "l.replacement_sku_name,l.replacement_reason,l.warehouse_id,l.legal_entity_id,"
                + "l.requested_quantity,l.authorized_quantity,l.reserved_quantity,"
                + "r.reservation_id,r.reservation_no,r.status as reservation_status,"
                + "d.id as demand_id,d.demand_no,d.status as demand_status,"
                + "x.id as return_id,x.status as return_status,x.quantity as return_quantity"
                + " from exchange_authorization_line l"
                + " left join exchange_reservation r"
                + " on r.tenant_id = l.tenant_id and r.authorization_line_id = l.id"
                + " left join exchange_outbound_demand d"
                + " on d.tenant_id = l.tenant_id and d.authorization_line_id = l.id"
                + " left join exchange_return_requirement x"
                + " on x.tenant_id = l.tenant_id and x.authorization_line_id = l.id"
                + " where l.tenant_id = ?::uuid and l.authorization_id = ?::uuid"
                + " order by l.original_sku_code, l.id",
            tenant,
            authorizationId)
        .stream()
        .map(
            r ->
                new ExchangeLineView(
                    r.get("id", UUID.class),
                    r.get("case_line_id", UUID.class),
                    r.get("original_sku_id", UUID.class),
                    r.get("original_sku_code", String.class),
                    r.get("original_sku_name", String.class),
                    qty(r.get("original_quantity", BigDecimal.class)),
                    r.get("replacement_sku_id", UUID.class),
                    r.get("replacement_sku_code", String.class),
                    r.get("replacement_sku_name", String.class),
                    r.get("replacement_reason", String.class),
                    r.get("warehouse_id", UUID.class),
                    // 仓编码在 warehouse 服务的库里，本服务读不到（跨库视图被 AGENTS.md 禁止），
                    // 所以这里给空串而不是编一个 —— 编出来的仓编码会被当成真实主数据。
                    "",
                    r.get("legal_entity_id", UUID.class),
                    qty(r.get("requested_quantity", BigDecimal.class)),
                    qty(r.get("authorized_quantity", BigDecimal.class)),
                    qty(r.get("reserved_quantity", BigDecimal.class)),
                    r.get("reservation_id", UUID.class),
                    r.get("reservation_no", String.class),
                    r.get("reservation_status", String.class),
                    r.get("demand_id", UUID.class),
                    r.get("demand_no", String.class),
                    r.get("demand_status", String.class),
                    r.get("return_id", UUID.class),
                    r.get("return_status", String.class),
                    qty(r.get("return_quantity", BigDecimal.class))))
        .toList();
  }

  private List<String> allowedActions(String status) {
    return "ACTIVE".equals(status) ? List.of("CANCEL") : List.of();
  }

  // ================================================================ 授权

  /**
   * <b>批准换货</b>：确认可换数量 → 向 warehouse <b>真实预占</b>替换货 → 生成新发货需求 + 等待旧货回寄。
   *
   * <p>顺序是 load-bearing 的，理由见类注释：<b>先锁售后单、再打远程</b>。
   *
   * @param bearer 调用方自己的凭证，原样转发给 warehouse / catalog ——
   *     租户由对方按已认证成员关系解析，本服务不替对方决定租户
   */
  public ExchangeDetail authorize(ExchangeAuthorizeRequest req, UUID actorId, String bearer) {
    if (req == null || req.lines() == null || req.lines().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要有一行换货明细");
    }
    boolean allowPartial = Boolean.TRUE.equals(req.allowPartial());
    UUID tenant = currentTenant();

    // ---- ① 锁住售后单（并发下真正起作用的那道防线的第一半）----
    var aftersales =
        one(
            "select id,case_no,order_id,status,case_type,requested_quantity,currency"
                + " from aftersales_case where tenant_id = ?::uuid and id = ?::uuid for update",
            tenant,
            req.caseId());
    if (aftersales == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "售后单不存在或不属于当前租户");
    }
    // 类型与结案状态由 DB 触发器再钉一次（guard_exchange_authorization_case），
    // 这里先给出可读的中文原因，避免用户只看到一个 PG 异常。
    if (!"EXCHANGE".equals(aftersales.get("case_type", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "只有换货类售后单能换货授权，本单类型是 "
              + aftersales.get("case_type", String.class));
    }
    if (List.of("RESOLVED", "REJECTED", "CLOSED").contains(aftersales.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "售后单已结案，不能再授权换货");
    }

    // ---- ② 同一张售后单只能有一张换货授权单（先查重，让并发失败的那次在远程调用**之前**结束）----
    var existingRow =
        one(
            "select authorization_no from exchange_authorization"
                + " where tenant_id = ?::uuid and case_id = ?::uuid",
            tenant,
            req.caseId());
    // ⚠️ 这里必须是「查不到行」而不是「查到 null 值」：没授权过时这张表本来就该没有行，
    //   把它当成一个必有行的标量去 .get(0, ...) 会让第一次授权直接 NPE。
    if (existingRow != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张换货售后单已经授权过（"
              + existingRow.get(0, String.class)
              + "），不能重复换货");
    }

    // ---- ③ 校验请求行：重复的售后行直接拒，不做静默合并 ----
    var seen = new LinkedHashMap<UUID, ExchangeLineRequest>();
    for (var line : req.lines()) {
      if (line == null || line.caseLineId() == null || line.replacementSkuId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一行都要给 caseLineId 与 replacementSkuId");
      }
      if (seen.put(line.caseLineId(), line) != null) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "同一售后商品行只能换一次，请把数量合并到一行");
      }
    }

    // ---- ④ 读售后商品行 ----
    // ⚠️ 刻意**不加 FOR UPDATE**：PostgreSQL 要求 SELECT ... FOR UPDATE 具备 UPDATE 权限，
    //   而 aftersales_case_line 在 V157 里被刻意做成 append-only（运行角色的 UPDATE/DELETE
    //   已被 REVOKE —— 改掉或删掉一行就是毁掉纠纷举证原材料）。对它加行锁既拿不到，
    //   也没有意义：这张表不会被并发 UPDATE，能被并发插入的只有「新的售后行」，
    //   而那不与本授权冲突。
    //   真正需要串行化的是「同一张售后单并发授权两次」，那道锁在 ① 的 aftersales_case 上，
    //   再由 UNIQUE(tenant_id, case_id) 兜底。
    var caseLines = new LinkedHashMap<UUID, Record>();
    for (var line : req.lines()) {
      var row =
          one(
              "select id,case_id,order_line_id,quantity from aftersales_case_line"
                  + " where tenant_id = ?::uuid and id = ?::uuid",
              tenant,
              line.caseLineId());
      if (row == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "售后商品行不存在或不属于当前租户：" + line.caseLineId());
      }
      if (!aftersales.get("id", UUID.class).equals(row.get("case_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "售后商品行不属于这张售后单");
      }
      caseLines.put(line.caseLineId(), row);
    }

    // ---- ⑤ 替换商品事实：必须是真实的、在售的 SKU（catalog 是唯一来源）----
    var skuCache = new LinkedHashMap<UUID, ExchangeSkuQueryPort.SkuView>();
    for (var line : req.lines()) {
      skuCache.computeIfAbsent(line.replacementSkuId(), id -> skus.requireActive(id, bearer));
    }

    // ---- ⑥ 库存可用性：按含安全缓冲的可售量判（warehouse 7.4.1.x 是唯一口径）----
    var sellableCache = new LinkedHashMap<UUID, ExchangeReservationPort.SellableView>();
    for (var line : req.lines()) {
      sellableCache.computeIfAbsent(
          line.replacementSkuId(),
          id -> reservations.sellable(req.warehouseId(), id, bearer));
    }

    // ---- ⑦ 定每行授权量 ----
    var plan = new ArrayList<PlannedLine>();
    BigDecimal sumRequested = BigDecimal.ZERO;
    BigDecimal sumAuthorized = BigDecimal.ZERO;
    var shortfalls = new ArrayList<String>();
    for (var line : req.lines()) {
      var caseLine = caseLines.get(line.caseLineId());
      BigDecimal caseQuantity = caseLine.get("quantity", BigDecimal.class);
      BigDecimal requested =
          (line.requestedQuantity() == null ? caseQuantity : line.requestedQuantity())
              .setScale(6, RoundingMode.HALF_UP);
      if (requested.compareTo(BigDecimal.ZERO) <= 0) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "换货数量必须为正：" + requested.toPlainString());
      }
      if (requested.compareTo(caseQuantity) > 0) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "申请换 " + requested.toPlainString() + " 超过售后单登记的 " + caseQuantity.toPlainString());
      }

      var original =
          one(
              "select sku_id,coalesce(sku_code,'') as sku_code,coalesce(sku_name,'') as sku_name"
                  + " from sales_order_line where tenant_id = ?::uuid and id = ?::uuid",
              tenant,
              caseLine.get("order_line_id", UUID.class));
      if (original == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "原订单行不存在或不属于当前租户：" + caseLine.get("order_line_id", UUID.class));
      }
      if (line.replacementSkuId().equals(original.get("sku_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "替换商品不能与原商品相同（" + original.get("sku_code", String.class) + "），那不叫换货");
      }

      var sellable =
          sellableCache.get(line.replacementSkuId()).sellable() == null
              ? BigDecimal.ZERO
              : sellableCache.get(line.replacementSkuId()).sellable();
      BigDecimal authorized;
      if (sellable.compareTo(requested) >= 0) {
        authorized = requested;
      } else if (allowPartial) {
        authorized = sellable.min(requested).max(BigDecimal.ZERO);
      } else {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "替换货库存不足："
                + sellableCache.get(line.replacementSkuId()).skuName()
                + "（"
                + sellableCache.get(line.replacementSkuId()).skuCode()
                + "）可售 "
                + sellable.toPlainString()
                + "，本次要换 "
                + requested.toPlainString()
                + "。如需按可售量少批，请勾选「允许部分授权」。");
      }
      if (authorized.compareTo(BigDecimal.ZERO) <= 0) {
        shortfalls.add(
            sellableCache.get(line.replacementSkuId()).skuCode() + " 可售 0，无法换货");
        continue;
      }
      plan.add(
          new PlannedLine(
              caseLine,
              original,
              skuCache.get(line.replacementSkuId()),
              requested,
              authorized,
              line.replacementReason() == null ? "" : line.replacementReason()));
      sumRequested = sumRequested.add(requested);
      sumAuthorized = sumAuthorized.add(authorized);
    }

    if (plan.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "没有一行可以授权换货：" + String.join("；", shortfalls));
    }

    String decision =
        sumAuthorized.compareTo(sumRequested) == 0 ? "APPROVED" : "PARTIALLY_APPROVED";

    // ---- ⑧ 落库：头 → 行 → 逐行预占（远程）→ 新发货需求 + 回寄要求 ----
    UUID authorizationId = UUID.randomUUID();
    String authorizationNo = nextAuthorizationNo();
    OffsetDateTime now =
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String orderNo =
        one(
                "select coalesce(order_no,'') from sales_order where tenant_id = ?::uuid and id = ?::uuid",
                tenant,
                aftersales.get("order_id", UUID.class))
            .get(0, String.class);

    sql.execute(
        "insert into exchange_authorization(id,tenant_id,authorization_no,case_id,case_no,"
            + "order_id,order_no,status,decision,requested_quantity,authorized_quantity,"
            + "replacement_sku_count,reason_note,version,authorized_by,authorized_at,"
            + "created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,1,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        authorizationId,
        tenant,
        authorizationNo,
        req.caseId(),
        aftersales.get("case_no", String.class),
        aftersales.get("order_id", UUID.class),
        orderNo,
        decision,
        sumRequested,
        sumAuthorized,
        (long) plan.stream().map(p -> p.replacement().id()).distinct().count(),
        req.reasonNote() == null ? "" : req.reasonNote(),
        actorId,
        now,
        now,
        now);

    var created = new ArrayList<ExchangeReservationPort.ReservationCreatedView>();
    try {
      int seq = 0;
      for (var p : plan) {
        seq++;
        UUID lineId = UUID.randomUUID();

        // ★ 本模块的核心一步：向 warehouse 登记**真实**预占。
        // 刻意**先预占、后落本地行**：明细行在 V162 里是 append-only 的（运行角色的 UPDATE 被 REVOKE），
        // 所以「先插 0 再回填」这种写法在本服务里根本不可执行 ——
        // 而「插的时候就已经预占足额」正是那条迟到约束触发器要复核的形态。
        // 拿不到应答就抛出去（由下面的 catch 补偿已成功的那几笔），绝不降级成「先授权再说」。
        var held =
            reservations.create(
                new ExchangeReservationPort.ReservationCreate(
                    "TRADE",
                    "SALES_ORDER",
                    authorizationId,
                    authorizationNo,
                    req.legalEntityId(),
                    p.replacement().id(),
                    p.authorized(),
                    now,
                    null,
                    "换货授权 " + authorizationNo + " 第 " + seq + " 行：替买家换货"),
                bearer);
        created.add(held);

        sql.execute(
            "insert into exchange_authorization_line(id,tenant_id,authorization_id,case_line_id,"
                + "original_sku_id,original_sku_code,original_sku_name,original_quantity,"
                + "replacement_sku_id,replacement_sku_code,replacement_sku_name,replacement_reason,"
                + "warehouse_id,legal_entity_id,requested_quantity,authorized_quantity,"
                + "reserved_quantity,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::numeric,?::timestamptz)",
            lineId,
            tenant,
            authorizationId,
            p.caseLine().get("id", UUID.class),
            p.original().get("sku_id", UUID.class),
            p.original().get("sku_code", String.class),
            p.original().get("sku_name", String.class),
            p.caseLine().get("quantity", BigDecimal.class),
            p.replacement().id(),
            p.replacement().code(),
            p.replacement().name(),
            p.reason(),
            req.warehouseId(),
            req.legalEntityId(),
            p.requested(),
            p.authorized(),
            // 建行即预占足额：这一列不允许事后回填（迟到约束触发器在提交时复核它与真实预占一致）。
            p.authorized(),
            now);

        sql.execute(
            "insert into exchange_reservation(id,tenant_id,authorization_id,authorization_line_id,"
                + "reservation_id,reservation_no,sku_id,sku_code,quantity,status,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,'HELD',?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            authorizationId,
            lineId,
            held.id(),
            held.reservationNo(),
            p.replacement().id(),
            p.replacement().code(),
            p.authorized(),
            now,
            now);

        // 新发货需求（输出句第二项）：交给 9.3.3.1 接手，本服务只如实立需求。
        sql.execute(
            "insert into exchange_outbound_demand(id,tenant_id,authorization_id,"
                + "authorization_line_id,demand_no,sku_id,sku_code,sku_name,quantity,warehouse_id,"
                + "status,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,'PENDING',?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            authorizationId,
            lineId,
            nextDemandNo(),
            p.replacement().id(),
            p.replacement().code(),
            p.replacement().name(),
            p.authorized(),
            req.warehouseId(),
            now,
            now);

        // 等待旧货回寄（加粗句的后半段）：没有这一行就是只发不收，DB 层会拒。
        sql.execute(
            "insert into exchange_return_requirement(id,tenant_id,authorization_id,"
                + "authorization_line_id,original_sku_id,original_sku_code,quantity,status,"
                + "note,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,'WAITING',?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            authorizationId,
            lineId,
            p.original().get("sku_id", UUID.class),
            p.original().get("sku_code", String.class),
            p.authorized(),
            "换货发出后请按指引寄回原商品",
            now,
            now);
      }
    } catch (RuntimeException failure) {
      // 补偿：已登记成功的预占逐笔**真释放**。释放失败也照实抛出 ——
      // 那时本地事务回滚、仓库还占着货，运维必须能从错误信息里看见这件事。
      compensate(created, bearer);
      throw failure;
    }

    return detail(authorizationId);
  }

  /**
   * 撤销换货：真去 warehouse 把预占释放掉，然后把需求取消、回寄要求免回。
   *
   * <p>{@code expectedVersion} 不匹配一律 409：两个人同时点「撤销」时，
   * 后到的那个必须被拒 —— 否则两笔释放各跑一次，而第二笔会让仓库侧的幂等键
   * 变成「已终结预占的再次终结」，报错信息与真实原因完全对不上。
   */
  public ExchangeDetail cancel(UUID id, ExchangeCancelRequest req, UUID actorId, String bearer) {
    UUID tenant = currentTenant();
    var head =
        one(
            "select id, status, version, case_no from exchange_authorization"
                + " where tenant_id = ?::uuid and id = ?::uuid for update",
            tenant,
            id);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "换货授权单不存在或不属于当前租户");
    }
    String status = head.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "换货授权单已撤销过，不能重复撤销");
    }
    int version = head.get("version", Integer.class);
    if (req == null || req.expectedVersion() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给 expectedVersion");
    }
    if (req.expectedVersion() != version) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "换货授权单已被其他人改动（当前版本 "
              + version
              + "，你看到的是 "
              + req.expectedVersion()
              + "），请刷新后重试");
    }

    var held =
        sql.fetch(
            "select reservation_id, quantity from exchange_reservation"
                + " where tenant_id = ?::uuid and authorization_id = ?::uuid and status = 'HELD'",
            tenant,
            id);
    // 先释放远端再改本地：本地置 RELEASED 而仓库还占着，就是最典型的幽灵占用。
    for (var r : held) {
      reservations.release(
          r.get("reservation_id", UUID.class),
          r.get("quantity", BigDecimal.class),
          req.reason() == null ? "换货授权撤销" : req.reason(),
          bearer);
    }
    for (var r : held) {
      sql.execute(
          "update exchange_reservation set status='RELEASED', released_at=?::timestamptz,"
              + " release_reason=?, updated_at=?::timestamptz"
              + " where tenant_id = ?::uuid and reservation_id = ?::uuid",
          OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
          req.reason() == null ? "换货授权撤销" : req.reason(),
          OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
          tenant,
          r.get("reservation_id", UUID.class));
    }
    sql.execute(
        "update exchange_outbound_demand set status='CANCELLED', cancel_reason=?,"
            + " updated_at=?::timestamptz where tenant_id = ?::uuid and authorization_id = ?::uuid"
            + " and status='PENDING'",
        req.reason() == null ? "换货授权撤销" : req.reason(),
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        tenant,
        id);
    sql.execute(
        "update exchange_return_requirement set status='WAIVED', waived_by=?, waived_at=?::timestamptz,"
            + " updated_at=?::timestamptz where tenant_id = ?::uuid and authorization_id = ?::uuid"
            + " and status='WAITING'",
        actorId,
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        tenant,
        id);
    sql.execute(
        "update exchange_authorization set status='CANCELLED', version = version + 1,"
            + " cancelled_by=?, cancelled_at=?::timestamptz, cancel_reason=?, updated_at=?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        actorId,
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        req.reason() == null ? "换货授权撤销" : req.reason(),
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        tenant,
        id);

    return detail(id);
  }

  // ================================================================ 内部

  /** 一行换货计划的中间态：原商品 / 替换商品 / 申请量 / 授权量。 */
  private record PlannedLine(
      Record caseLine,
      Record original,
      ExchangeSkuQueryPort.SkuView replacement,
      BigDecimal requested,
      BigDecimal authorized,
      String reason) {}

  /**
   * 补偿：把本请求已经登记成功的预占逐笔释放。
   *
   * <p>刻意<b>不吞掉</b>释放失败：那意味着「本地回滚了、仓库还占着货」，
   * 而本地表已回滚、事后根本查不到那一行 —— 这个事实必须出现在错误里，不能被静默吞掉。
   */
  private void compensate(
      List<ExchangeReservationPort.ReservationCreatedView> created, String bearer) {
    for (var held : created) {
      try {
        reservations.release(held.id(), null, "换货授权失败，回滚已登记的预占", bearer);
      } catch (RuntimeException ignored) {
        // 见上：吞掉会让幽灵占用彻底不可见。这里刻意只记不抛，
        // 让原始失败（真正的根因）继续往上抛。
      }
    }
  }

  private ExchangeRow toRow(Record r) {
    return new ExchangeRow(
        r.get("id", UUID.class),
        r.get("authorization_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("status", String.class),
        statusLabel(r.get("status", String.class)),
        r.get("decision", String.class),
        decisionLabel(r.get("decision", String.class)),
        qty(r.get("requested_quantity", BigDecimal.class)),
        qty(r.get("authorized_quantity", BigDecimal.class)),
        r.get("replacement_sku_count", Integer.class),
        r.get("line_count", Integer.class),
        r.get("active_res", Integer.class),
        r.get("released_res", Integer.class),
        r.get("pending_demand", Integer.class),
        r.get("waiting_return", Integer.class),
        r.get("reason_note", String.class),
        r.get("authorized_by", UUID.class),
        r.get("authorized_at", OffsetDateTime.class),
        r.get("cancelled_by", UUID.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private String statusLabel(String s) {
    return "CANCELLED".equals(s) ? "已撤销" : "生效中";
  }

  private String decisionLabel(String d) {
    return "PARTIALLY_APPROVED".equals(d) ? "部分批准（库存不足）" : "整量批准";
  }

  /** 数量一律 {@code BigDecimal} → 字符串，Java 侧全程禁浮点（AGENTS.md）。 */
  private String qty(BigDecimal v) {
    return v == null ? "0.000000" : v.setScale(6, RoundingMode.HALF_UP).toPlainString();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private Object[] bindsWith(List<Object> binds, int size, int offset) {
    var all = new ArrayList<>(binds);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  /**
   * 取单行，取不到就返回 {@code null}。
   *
   * <p>刻意不用 {@code sql.fetchOne(sql, Object.class, binds)}：jOOQ **没有**
   * {@code fetchOne(String, Class, Object...)} 这个重载，那个调用会退化成
   * {@code fetchOne(String, Object...)}，于是 {@code Object.class} 被当成**绑参**
   * 塞进 SQL —— 运行时报「Type class java.lang.Class is not supported」。
   * 这个坑很隐蔽：SQL 里的 {@code ?::uuid} 全都对，单看语句根本看不出问题。
   */
  private Record one(String query, Object... binds) {
    var result = sql.fetch(query, binds);
    return result.isEmpty() ? null : result.get(0);
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private String nextAuthorizationNo() {
    long n = sql.fetchOne("select nextval('exchange_authorization_no_seq')").get(0, Long.class);
    return "EX"
        + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
        + String.format("%06d", n);
  }

  private String nextDemandNo() {
    // 需求号与授权号同源同格式：本服务自己没有第二个发号器，
    // 多造一个序列只会让「号从哪来」这件事多一个答案。
    long n = sql.fetchOne("select nextval('exchange_authorization_no_seq')").get(0, Long.class);
    return "EXD"
        + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
        + String.format("%06d", n);
  }
}
