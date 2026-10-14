package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptDiscrepancyModels.*;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.2.1.2 收货差异（**处理**）。
 *
 * <p>蓝图：「处理收货差异：短少、破损、型号不符、过期、错发；支持拍照留证、拒收、部分接收、索赔发起」； 输出「差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录」；
 * 「**破损与短少必须拒收或待检入库**，直接入可售会把问题货卖出去」。
 *
 * <p>七条实现纪律：
 *
 * <ol>
 *   <li><b>只处理，不重记事实</b>：差异类型 / 数量 / 原因 / 凭证说明 / 记录人归 8.2.1.1 （{@code
 *       goods_receipt_discrepancy}），本模块**不改它的任何一列**，只在处理记录上 固化一份快照（快照让列表页不必每次回表 join 一张别人拥有的表）。
 *   <li><b>可售硬规则钉两处</b>：服务层拦「风险差异类型 + 正常入库」，DB 触发器 {@code guard_discrepancy_disposition} 与 {@code
 *       guard_discrepancy_stock_move} 各拦一半。改决定绕不过前者，绕过决定直连 SQL 写库存流转绕不过后者。
 *   <li><b>接受量 + 拒收量恒等于差异数量</b>：DB 触发器钉死。「哪部分留下、哪部分赔掉」 必须能从一行里算出来，而不是靠回忆当初点的是哪个按钮。
 *   <li><b>拒收不入账有真实痕迹</b>：货从没进过库记 {@code NOT_POSTED}， 货已入库又改判的才真扣余额记 {@code WITHDRAW}。
 *       两者都留痕，因为「为什么不入库」答不出来才是真问题。
 *   <li><b>入账走带符号增量</b>：与 8.2.1.1 收货、7.5.1.1 调拨同一条路 —— {@code on_hand += qty}，不是覆盖式写回；余额与库位两处同动（V2
 *       守恒触发器要求）。
 *   <li><b>不回写来源单据、不建假索赔单</b>：蓝图输出写「索赔单（6 采购）」， 但 6.3.2.2 未交付，反向写别人的表会越过「每张事实表只有一个写入所有者」。 本模块在
 *       {@code goods_receipt_discrepancy_claim} 如实记来源单据与索赔意图 / 状态， 上游交付后由上游订阅事件建自己的索赔单并回填 {@code
 *       claim_ref_id}。
 *   <li><b>乐观锁是硬约束</b>：每个写动作先 {@code select ... for update} 锁行再比版本， 不匹配一律 409，不做「读到什么就写什么」。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link HandlingStatus#allowedNext}。
 */
@Service
public class GoodsReceiptDiscrepancyService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("CREATED_AT", "UPDATED_AT", "DECIDED_AT", "QUANTITY");

  /** 凭证单条上限（字节）。与迁移 V78 的 {@code CHECK(byte_size<=1500000)} 同值： 服务层先挡一次给可读报错，DB 再兜一次防绕过。 */
  static final int MAX_EVIDENCE_BYTES = 1_500_000;

  /** 差异处理库存分录的来源类型。inventory_transaction.ref_type 上限 32 字符。 */
  private static final String REF_TYPE = "GOODS_RECEIPT_DISCREPANCY";

  /** 蓝图硬规则，界面与 /capabilities 都要常驻显示。 */
  private static final String SELLABLE_RULE_NOTE =
      "蓝图硬规则：**破损与短少必须拒收或待检入库**，直接入可售会把问题货卖出去。"
          + "所以「正常入库」只对超收与错发开放；破损 / 短少 / 型号不符 / 过期只能拒收、"
          + "部分接收或转待检。这条规则在服务层与数据库层各拦一次 —— "
          + "改处理决定绕不过服务层，绕过服务层直连 SQL 写库存流转绕不过数据库。";

  /** 索赔单归属口径。 */
  private static final String CLAIM_NOTE =
      "蓝图输出写「索赔单（6 采购）」，但 6.3.2.2 采购单域**未交付**。"
          + "反向写别人的表会越过「每张事实表只有一个写入所有者」的边界纪律，"
          + "所以本模块只如实记录来源单据与索赔意图 / 状态（goods_receipt_discrepancy_claim），"
          + "上游交付后由**上游订阅事件**建自己的索赔单并回填 claimRefId —— "
          + "在 6.5.1 对账索赔交付前该字段恒为空，这是边界而不是漏填。"
          + "索赔状态只到「已提交」为止：受理与结算是 6.5.1 拥有的事实，本模块不代写。";

  /** 拒收不入账口径。 */
  private static final String NOT_POSTED_NOTE =
      "拒收那部分分两种情况，都留痕：货**从没进过库**记「拒收不入账」"
          + "（典型是短少 —— 货压根没到，拒收它唯一的库存痕迹就是这一行）；"
          + "货**已入库又改判拒收**的才真从库存撤出并写负增量分录。"
          + "本模块**不回写** 8.2.1.1 的 rejected_quantity —— 那是 8.2.1.1 收货现场就发生的事，"
          + "事实表只有一个写入所有者。";

  /** 改判口径。 */
  private static final String REVISION_NOTE =
      "已决定的处理**不允许改判**：改判会让已经发生的库存分录没法用「一次改判」讲清楚。"
          + "要改判请另登记一条差异 —— 改写历史不如追加事实，"
          + "这与 8.2.1.1「预期与实收分开记录」是同一条纪律。";

  private final DSLContext sql;

  public GoodsReceiptDiscrepancyService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力

  /**
   * 差异处理能力：四态状态机、四种处理决定、索赔与凭证词表、五条下游出口接入情况。
   *
   * <p>本车道的依赖自检结论（对集成头实际查证，不凭蓝图的「下游 =」假定）： 6 采购单（6.3.2.2）、6.4.2.1 质检单、6.4.2.2 不良品处理、6.5.1 对账索赔、
   * 11.2.1.1 退货授权与 10.x 平台仓回货**全部未交付**，按仓库既有约定显式 {@code available=false} 并给出原因与 ownerModule，
   * **不建占位表、不返回空集冒充成功**。
   */
  public GoodsReceiptDiscrepancyCapabilities capabilities() {
    List<DiscrepancyHandlingOption> statuses = new ArrayList<>();
    for (var s : HandlingStatus.values()) {
      statuses.add(new DiscrepancyHandlingOption(s.name(), s.label(), countByStatus(s)));
    }

    List<DiscrepancyHandlingTransition> transitions = new ArrayList<>();
    for (var from : HandlingStatus.values()) {
      var allowed = HandlingStatus.allowedNext(from);
      for (var to : HandlingStatus.values()) {
        boolean ok = allowed.contains(to);
        transitions.add(
            new DiscrepancyHandlingTransition(
                from.name(),
                to.name(),
                ok,
                ok
                    ? "允许"
                    : (from == HandlingStatus.CLOSED
                        ? "已关闭是终态，不再接受任何动作"
                        : "非法跳转：处理状态由已发生的动作推进，不接受手工设置")));
      }
    }

    List<DiscrepancyDispositionOption> dispositions =
        List.of(
            new DiscrepancyDispositionOption(
                "REJECT",
                "拒收",
                true,
                "否",
                "全部不要。给了落库库位就是「货已入库、真从库存撤出」；" + "不给就只留「拒收不入账」痕迹（货从没进过库，典型是短少）。"),
            new DiscrepancyDispositionOption(
                "PARTIAL_ACCEPT",
                "部分接收",
                true,
                "是",
                "一部分入库、一部分拒收。两个数量都要填，去向质量状态与落库库位必填 —— " + "接受与拒收之和必须恰好等于差异数量。"),
            new DiscrepancyDispositionOption(
                "HOLD_FOR_INSPECTION",
                "待检入库",
                true,
                "是",
                "全部转入待检或残次质量状态，等 6.4.2.1 质检结论（该模块尚未交付）。" + "去向质量状态必须是待检或残次，入可售会被数据库层拒绝。"),
            new DiscrepancyDispositionOption(
                "ACCEPT",
                "正常入库",
                false,
                "是",
                "按给定质量状态入可售。**只对超收与错发开放** —— " + "破损 / 短少 / 型号不符 / 过期一律禁止，直接入可售会把问题货卖出去。"));

    List<DiscrepancyHandlingOption> claimTypes = new ArrayList<>();
    for (var t : ClaimType.values()) {
      claimTypes.add(new DiscrepancyHandlingOption(t.name(), t.label(), countByClaimType(t)));
    }

    List<DiscrepancyHandlingOption> evidenceTypes = new ArrayList<>();
    for (var t : EvidenceType.values()) {
      evidenceTypes.add(new DiscrepancyHandlingOption(t.name(), t.label(), countByEvidenceType(t)));
    }

    List<DiscrepancyHandlingOption> directions = new ArrayList<>();
    for (var d : StockMoveDirection.values()) {
      directions.add(new DiscrepancyHandlingOption(d.name(), d.label(), countByDirection(d)));
    }

    List<DiscrepancyDependencyCapability> downstream =
        List.of(
            dependency(
                "PURCHASE_ORDER",
                "采购单与采购索赔单",
                false,
                "6.3.2.2",
                "/purchase/claims",
                "上游 6 采购与供应尚未交付：采购单表与索赔单端点都不存在。"
                    + "本模块因此只在 goods_receipt_discrepancy_claim 上记来源单据与索赔意图，"
                    + "不建跨服务外键、不反向写对方的表。上游交付后由上游订阅事件建单并回填 claimRefId。"),
            dependency(
                "INSPECTION_ORDER",
                "质检单",
                false,
                "6.4.2.1",
                "/quality/inspection-orders",
                "上游 6.4.2.1 尚未交付：质检单不存在。因此「待检入库」的处理决定"
                    + "只能把那批货落进待检 bucket 等着，发起质检单这个出口在本模块里点不动 —— "
                    + "页面上写明归属，而不是给一个点下去 404 的按钮。"),
            dependency(
                "DEFECTIVE_DISPOSITION",
                "不良品处置",
                false,
                "6.4.2.2",
                "/quality/defectives",
                "上游 6.4.2.2 尚未交付：不良品处理（报废 / 退货 / 让步接收）不存在。"
                    + "本模块只负责把处置**意图**落成库存流转与处理决定，"
                    + "实际报废与退货动作归属该模块。"),
            dependency(
                "RECONCILIATION_CLAIM",
                "对账索赔受理与结算",
                false,
                "6.5.1",
                "/finance/reconciliation/claims",
                "上游 6.5.1 尚未交付：对账侧索赔的受理、核准与结算都不存在。" + "因此索赔状态只到「已提交」为止；受理与结算是对方的写入所有者，本模块不代写。"),
            dependency(
                "RETURN_AUTH",
                "退货授权",
                false,
                "11.2.1.1",
                "/after-sales/returns",
                "上游 11 售后未交付：退货授权单不存在。残次品走退货的出口在本模块里点不动。"),
            dependency(
                "PLATFORM_WAREHOUSE",
                "平台仓回货",
                false,
                "10.x",
                "/logistics",
                "上游 10 物流与头程未交付：平台仓回货没有可引用的来源单据。"));

    return new GoodsReceiptDiscrepancyCapabilities(
        statuses,
        transitions,
        dispositions,
        RISK_DISCREPANCY_TYPES,
        claimTypes,
        evidenceTypes,
        directions,
        downstream,
        qualityStatusOptions(),
        SELLABLE_RULE_NOTE,
        CLAIM_NOTE,
        NOT_POSTED_NOTE,
        REVISION_NOTE);
  }

  private DiscrepancyDependencyCapability dependency(
      String key, String label, boolean available, String ownerModule, String href, String note) {
    return new DiscrepancyDependencyCapability(key, label, available, ownerModule, href, note);
  }

  private long countByStatus(HandlingStatus status) {
    return sql.fetchOne(
            "select count(*) from goods_receipt_discrepancy_handling where"
                + " tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and status=?",
            status.name())
        .get(0, Long.class);
  }

  private long countByClaimType(ClaimType type) {
    return sql.fetchOne(
            "select count(*) from goods_receipt_discrepancy_claim where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid and claim_type=?",
            type.name())
        .get(0, Long.class);
  }

  private long countByEvidenceType(EvidenceType type) {
    return sql.fetchOne(
            "select count(*) from goods_receipt_discrepancy_evidence where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid and evidence_type=?",
            type.name())
        .get(0, Long.class);
  }

  private long countByDirection(StockMoveDirection direction) {
    return sql.fetchOne(
            "select count(*) from goods_receipt_discrepancy_stock_move where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid and direction=?",
            direction.name())
        .get(0, Long.class);
  }

  /** 可选入库去向质量状态。role 决定它是可售还是待检 / 残次。 */
  public List<DiscrepancyQualityStatusOption> qualityStatusOptions() {
    return sql
        .fetch(
            "select id,code,name,role from inventory_quality_status where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid order by code")
        .stream()
        .map(
            r ->
                new DiscrepancyQualityStatusOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("role", String.class)))
        .toList();
  }

  // ================================================================ 列表

  private static String fromClause() {
    return " from goods_receipt_discrepancy_handling h"
        + " join goods_receipt r on r.tenant_id=h.tenant_id and r.id=h.receipt_id"
        + " join warehouse w on w.tenant_id=r.tenant_id and w.id=r.warehouse_id"
        + " left join goods_receipt_line l on l.tenant_id=h.tenant_id and l.id=h.line_id"
        + " left join sku_ref s on s.tenant_id=h.tenant_id and s.id=h.sku_id"
        + " left join goods_receipt_discrepancy d on d.tenant_id=h.tenant_id"
        + "   and d.id=h.discrepancy_id"
        + " left join inventory_quality_status qs on qs.tenant_id=h.tenant_id"
        + "   and qs.id=h.inbound_quality_status_id";
  }

  /**
   * 每条处理记录的聚合：凭证数、库存流转数、索赔状态。
   *
   * <p>lateral 在没有凭证 / 没有流转 / 没有索赔时也返回一行（count 为 0）， 一条刚接手还没留证的差异不该从列表里「查不到」。
   */
  private static String aggJoin() {
    return " left join lateral (select count(*) as n from goods_receipt_discrepancy_evidence e"
        + "   where e.tenant_id=h.tenant_id and e.handling_id=h.id) ev on true"
        + " left join lateral (select count(*) as n from goods_receipt_discrepancy_stock_move m"
        + "   where m.tenant_id=h.tenant_id and m.handling_id=h.id) mv on true"
        + " left join lateral (select status as claim_status, claim_code,"
        + "   claimed_amount from goods_receipt_discrepancy_claim c"
        + "   where c.tenant_id=h.tenant_id and c.handling_id=h.id) cl on true";
  }

  private static String rowSelect() {
    // warehouse_id 住在收货单上而不是处理记录上：必须显式取出来，
    // 否则 row() 读 warehouse_id 会拿到 null，而按仓筛选在界面上却「看着能筛」。
    return "select h.*,r.receipt_code,r.warehouse_id as warehouse_id,"
        + " w.code as warehouse_code,l.sku_id as line_sku_id,"
        + " s.code as sku_code,s.name as sku_name,qs.name as inbound_quality_status_name,"
        + " coalesce(ev.n,0) as evidence_count,coalesce(mv.n,0) as stock_move_count,"
        + " cl.claim_status";
  }

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  private void conditions(
      DiscrepancyHandlingQuery q,
      AccessService.Access actor,
      List<String> where,
      List<Object> binds) {
    if (q.receiptId() != null) {
      where.add("h.receipt_id=?");
      binds.add(q.receiptId());
    }
    if (q.warehouseId() != null) {
      where.add("r.warehouse_id=?");
      binds.add(q.warehouseId());
    }
    if (q.status() != null && !q.status().isEmpty()) {
      for (var raw : q.status()) {
        where.add("h.status=?");
        binds.add(HandlingStatus.parse(raw).name());
      }
    }
    if (q.disposition() != null && !q.disposition().isEmpty()) {
      for (var raw : q.disposition()) {
        where.add("h.disposition=?");
        binds.add(HandlingDisposition.parse(raw).name());
      }
    }
    if (q.discrepancyType() != null && !q.discrepancyType().isEmpty()) {
      for (var raw : q.discrepancyType()) {
        where.add("h.discrepancy_type=?");
        binds.add(GoodsReceiptModels.DiscrepancyType.parse(raw).name());
      }
    }
    if (q.claimStatus() != null && !q.claimStatus().isEmpty()) {
      for (var raw : q.claimStatus()) {
        where.add("cl.claim_status=?");
        binds.add(ClaimStatus.parse(raw).name());
      }
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 真实模糊检索：收货单号、SKU、差异类型、决定原因 / 说明、索赔单号。
      // **不是**假搜索 —— 命中不了的字段不会出现在这里。
      where.add(
          "(r.receipt_code ilike ? or s.code ilike ? or s.name ilike ?"
              + " or h.discrepancy_type ilike ? or h.decision_reason ilike ?"
              + " or h.decision_note ilike ? or cl.claim_code ilike ?"
              + " or d.reason_code ilike ? or d.reason_note ilike ?"
              + " or d.evidence_note ilike ?)");
      String kw = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 10; i++) {
        binds.add(kw);
      }
    }
    // 数据范围：SELF 收窄到本人接手过或决定过的差异。actorId 来自**已认证身份**，
    // 不接受任何请求参数传入 —— 否则「只看我的」会变成一个能越权的开关。
    if (actor != null && InventoryController.selfOnly(actor, "warehouse.inventory.read")) {
      where.add("(h.created_by=? or h.decided_by=?)");
      binds.add(actor.userId());
      binds.add(actor.userId());
    }
  }

  /** 排序白名单。**不接受**用户传入的排序表达式 —— 那是 SQL 注入面。 */
  private static String orderBy(DiscrepancyHandlingQuery q) {
    String sort = q.sort() == null ? "UPDATED_AT" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      sort = "UPDATED_AT";
    }
    return switch (sort) {
      case "CREATED_AT" -> " order by h.created_at desc,h.id";
      case "DECIDED_AT" -> " order by h.decided_at desc nulls last,h.id";
      case "QUANTITY" -> " order by h.quantity desc,h.id";
      default -> " order by h.updated_at desc,h.id";
    };
  }

  /** 列表页的全量汇总：与筛选条件一致，但**不受分页影响**。 */
  private DiscrepancyHandlingSummary summary(
      DiscrepancyHandlingQuery q, AccessService.Access actor) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, actor, where, binds);
    // claimStatus 条件打在别名 c 上，汇总查询必须带上同一个 lateral 才不会报「列不存在」。
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record r =
        sql.fetchOne(
            "select count(*) as handling_count,"
                + " coalesce(sum(h.quantity),0) as quantity,"
                + " coalesce(sum(h.accepted_quantity),0) as accepted,"
                + " coalesce(sum(h.rejected_quantity),0) as rejected,"
                + " coalesce(sum(case when h.status='PENDING' then 1 else 0 end),0) as pending,"
                + " coalesce(sum(case when h.status='DECIDED' then 1 else 0 end),0) as decided_st,"
                + " coalesce(sum(case when h.status='CLAIMED' then 1 else 0 end),0) as claimed_st,"
                + " coalesce(sum(case when h.status='CLOSED' then 1 else 0 end),0) as closed,"
                + " coalesce(sum(case when h.status<>'CLOSED' then 1 else 0 end),0) as open_cnt,"
                + " coalesce(sum(case when h.discrepancy_type in"
                + "   ('DAMAGED','SHORT_RECEIPT','WRONG_ITEM','EXPIRED') then 1 else 0 end),0) as risk,"
                + " coalesce(sum(ev.n),0) as evidence_count,"
                + " coalesce(sum(cm.claimed_amount),0) as claimed_amount,"
                + " coalesce(sum(cm.n),0) as claim_count,"
                + " coalesce(sum(case when h.discrepancy_type in"
                + "   ('DAMAGED','SHORT_RECEIPT','WRONG_ITEM','EXPIRED')"
                + "   and coalesce(cm.n,0)=0 then 1 else 0 end),0) as unclaimed_risk,"
                + " min(h.created_at) as earliest"
                + fromClause()
                + aggJoin()
                + " left join lateral (select count(*) as n,"
                + "   sum(c2.claimed_amount) as claimed_amount"
                + "   from goods_receipt_discrepancy_claim c2"
                + "   where c2.tenant_id=h.tenant_id and c2.handling_id=h.id) cm on true"
                + sqlWhere,
            binds.toArray());
    BigDecimal accepted = decimal(r, "accepted");
    BigDecimal rejected = decimal(r, "rejected");
    return new DiscrepancyHandlingSummary(
        n(r, "handling_count"),
        n(r, "pending"),
        n(r, "decided_st"),
        n(r, "claimed_st"),
        n(r, "closed"),
        n(r, "open_cnt"),
        n(r, "risk"),
        decimal(r, "quantity"),
        accepted,
        rejected,
        decimal(r, "claimed_amount"),
        n(r, "evidence_count"),
        n(r, "claim_count"),
        n(r, "unclaimed_risk"),
        r.get("earliest", OffsetDateTime.class));
  }

  public DiscrepancyHandlingPage page(DiscrepancyHandlingQuery q, AccessService.Access actor) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));
    int pageNumber = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, actor, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + fromClause() + aggJoin() + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            rowSelect() + fromClause() + aggJoin() + sqlWhere + orderBy(q) + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    List<DiscrepancyHandlingOption> statuses = new ArrayList<>();
    for (var s : HandlingStatus.values()) {
      statuses.add(new DiscrepancyHandlingOption(s.name(), s.label(), countByStatus(s)));
    }
    return new DiscrepancyHandlingPage(
        summary(q, actor),
        rows.stream().map(GoodsReceiptDiscrepancyService::row).toList(),
        statuses,
        capabilities().downstream(),
        qualityStatusOptions(),
        total,
        pageNumber,
        size);
  }

  // ================================================================ 详情

  /** 处理记录详情。跨租户在 RLS 下查不到 → 404。 */
  public DiscrepancyHandlingDetail detail(UUID handlingId) {
    Record head =
        sql.fetchOne(rowSelect() + fromClause() + aggJoin() + " where h.id=?", handlingId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异处理记录不存在或无权查看");
    }
    DiscrepancyHandlingRow handling = row(head);
    String status = handling.status();
    List<DiscrepancyDispositionOption> allowed =
        HandlingStatus.DECIDED.name().equals(status) ? List.of() : dispositionOptions();
    return new DiscrepancyHandlingDetail(
        handling,
        discrepancyFact(handling.discrepancyId()),
        evidenceRows(handlingId),
        stockMoveRows(handlingId),
        claimOf(handlingId),
        HandlingStatus.allowedNext(HandlingStatus.parse(status)).stream()
            .map(
                to ->
                    new DiscrepancyHandlingTransition(status, to.name(), true, "允许：" + to.label()))
            .toList(),
        allowed,
        SELLABLE_RULE_NOTE,
        NOT_POSTED_NOTE);
  }

  private List<DiscrepancyDispositionOption> dispositionOptions() {
    return capabilities().dispositionOptions();
  }

  private DiscrepancyFactRow discrepancyFact(UUID discrepancyId) {
    Record d = sql.fetchOne("select * from goods_receipt_discrepancy where id=?", discrepancyId);
    if (d == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异记录不存在或无权查看");
    }
    var t = GoodsReceiptModels.DiscrepancyType.parse(d.get("discrepancy_type", String.class));
    return new DiscrepancyFactRow(
        d.get("id", UUID.class),
        d.get("line_id", UUID.class),
        t.name(),
        t.label(),
        decimal(d, "quantity"),
        d.get("reason_code", String.class),
        d.get("reason_note", String.class),
        d.get("evidence_note", String.class),
        d.get("recorded_at", OffsetDateTime.class),
        d.get("actor_id", UUID.class));
  }

  private List<DiscrepancyEvidenceRow> evidenceRows(UUID handlingId) {
    return sql
        .fetch(
            "select * from goods_receipt_discrepancy_evidence where handling_id=?"
                + " order by captured_at,id",
            handlingId)
        .stream()
        .map(
            e -> {
              var t = EvidenceType.parse(e.get("evidence_type", String.class));
              byte[] content = e.get("content", byte[].class);
              String contentType = e.get("content_type", String.class);
              return new DiscrepancyEvidenceRow(
                  e.get("id", UUID.class),
                  t.name(),
                  t.label(),
                  e.get("file_name", String.class),
                  contentType,
                  e.get("byte_size", Integer.class),
                  e.get("sha256", String.class),
                  e.get("captured_at", OffsetDateTime.class),
                  e.get("uploaded_by", UUID.class),
                  e.get("note", String.class),
                  // 凭证本体真落库（bytea），这里原样带回 data URL —— 索赔时拿不出照片等于没留证。
                  "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(content));
            })
        .toList();
  }

  private List<DiscrepancyStockMoveRow> stockMoveRows(UUID handlingId) {
    return sql
        .fetch(
            "select m.*,qf.name as from_quality_name,qt.name as to_quality_name,"
                + " loc.code as location_code,lo.lot_code"
                + " from goods_receipt_discrepancy_stock_move m"
                + " left join inventory_quality_status qf on qf.tenant_id=m.tenant_id"
                + "   and qf.id=m.from_quality_status_id"
                + " left join inventory_quality_status qt on qt.tenant_id=m.tenant_id"
                + "   and qt.id=m.to_quality_status_id"
                + " left join warehouse_location loc on loc.tenant_id=m.tenant_id"
                + "   and loc.id=m.location_id"
                + " left join inventory_lot lo on lo.tenant_id=m.tenant_id and lo.id=m.lot_id"
                + " where m.handling_id=? order by m.occurred_at,m.id",
            handlingId)
        .stream()
        .map(
            m -> {
              var d = StockMoveDirection.parse(m.get("direction", String.class));
              return new DiscrepancyStockMoveRow(
                  m.get("id", UUID.class),
                  d.name(),
                  d.label(),
                  decimal(m, "quantity"),
                  m.get("from_quality_name", String.class),
                  m.get("to_quality_name", String.class),
                  m.get("location_code", String.class),
                  m.get("lot_code", String.class),
                  m.get("transaction_id", UUID.class),
                  m.get("occurred_at", OffsetDateTime.class),
                  m.get("note", String.class));
            })
        .toList();
  }

  private DiscrepancyClaimRow claimOf(UUID handlingId) {
    Record c =
        sql.fetchOne(
            "select * from goods_receipt_discrepancy_claim where handling_id=?", handlingId);
    return c == null ? null : claimRow(c);
  }

  // ================================================================ 接手差异

  /**
   * 接手一条 8.2.1.1 记录下来的差异事实，建处理记录（进入待处理）。
   *
   * <p>差异类型与数量在这里被**固化**成快照：列表页要按类型筛、要按数量算索赔比例， 不该每次都回表 join 一张别人拥有的表。8.2.1.1 后续不再改事实行，所以快照不会漂。
   */
  @Transactional
  public DiscrepancyHandlingSaved intake(
      AccessService.Access actor, DiscrepancyHandlingIntakeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("接手请求不能为空");
    }
    UUID discrepancyId = require(request.discrepancyId(), "差异记录");
    Record d = sql.fetchOne("select * from goods_receipt_discrepancy where id=?", discrepancyId);
    if (d == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异记录不存在或无权查看");
    }
    if (sql.fetchOne(
            "select id from goods_receipt_discrepancy_handling where discrepancy_id=?",
            discrepancyId)
        != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条差异已经在处理中：一条差异一个处理过程，要改判请另登记一条差异（见 /capabilities）");
    }
    UUID tenantId = d.get("tenant_id", UUID.class);
    UUID lineId = d.get("line_id", UUID.class);
    UUID skuId = null;
    if (lineId != null) {
      skuId =
          sql.fetchOne("select sku_id from goods_receipt_line where id=?", lineId)
              .get("sku_id", UUID.class);
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into goods_receipt_discrepancy_handling(id,tenant_id,discrepancy_id,receipt_id,"
            + "line_id,sku_id,discrepancy_type,quantity,created_by) values(?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        discrepancyId,
        d.get("receipt_id", UUID.class),
        lineId,
        skuId,
        d.get("discrepancy_type", String.class),
        decimal(d, "quantity"),
        actor.userId());
    return savedOf(id);
  }

  // ================================================================ 拍照留证

  /**
   * 拍照留证：凭证本体落 PostgreSQL（bytea），单条上限 1.5 MB。
   *
   * <p>待处理与已决定状态都能留证：货损照片常常是在「先拒收、后补证据」时补拍的。 已关闭是终态，不再接受任何动作 —— 关闭之后再往一条结案记录上挂证据，
   * 等于让「当时结案时有什么证据」这个问题永远答不出来。
   */
  @Transactional
  public DiscrepancyEvidenceRow addEvidence(
      AccessService.Access actor, UUID handlingId, DiscrepancyEvidenceRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("凭证请求不能为空");
    }
    Record head = lockHandling(handlingId);
    String status = head.get("status", String.class);
    if (status.equals(HandlingStatus.CLOSED.name())) {
      throw statusError(status, "已关闭的处理记录不再接受新凭证");
    }
    EvidenceType type = EvidenceType.parse(require(request.evidenceType(), "凭证类型"));
    String contentType = fit(request.contentType(), 64, "凭证 MIME 类型");
    if (contentType.isBlank()) {
      throw new IllegalArgumentException("缺少凭证 MIME 类型");
    }
    byte[] content = decodeBase64(request.contentBase64());
    if (content.length == 0) {
      throw new IllegalArgumentException("凭证内容为空：照片或单据必须真的带字节");
    }
    if (content.length > MAX_EVIDENCE_BYTES) {
      throw new IllegalArgumentException(
          "单个凭证不能超过 "
              + (MAX_EVIDENCE_BYTES / 1024)
              + " KB（当前 "
              + (content.length / 1024)
              + " KB）：请在拍照时用长边 1280px 以内的画质上传");
    }
    OffsetDateTime capturedAt =
        request.capturedAt() == null ? OffsetDateTime.now() : request.capturedAt();

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into goods_receipt_discrepancy_evidence(id,tenant_id,handling_id,evidence_type,"
            + "file_name,content_type,content,byte_size,sha256,captured_at,uploaded_by,note)"
            + " values(?,?,?,?,?,?,?,?,?,cast(? as timestamptz),?,?)",
        id,
        head.get("tenant_id", UUID.class),
        handlingId,
        type.name(),
        fit(request.fileName(), 200, "凭证文件名"),
        contentType,
        content,
        content.length,
        sha256(content),
        capturedAt,
        actor.userId(),
        fit(request.note(), 2000, "凭证说明"));

    return evidenceRows(handlingId).stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("凭证写入后读不回来"));
  }

  /** 解 base64。容忍带 {@code data:...;base64,} 前缀的串 —— 前端拼 data URL 时很容易带上。 */
  private static byte[] decodeBase64(String raw) {
    if (raw == null || raw.isBlank()) {
      return new byte[0];
    }
    String value = raw.trim();
    int comma = value.indexOf(',');
    if (value.startsWith("data:") && comma > 0) {
      value = value.substring(comma + 1);
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("凭证内容不是合法的 base64：" + e.getMessage());
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  // ================================================================ 处理决定

  /**
   * 提交处理决定：拒收 / 部分接收 / 待检入库 / 正常入库，并写对应的库存流转。
   *
   * <p>蓝图硬规则在**服务层**先拦一次：「破损与短少必须拒收或待检入库」。 DB 触发器 {@code guard_discrepancy_disposition} 与 {@code
   * guard_discrepancy_stock_move} 各再拦一半（改决定绕不过这里，绕过这里直连 SQL 也绕不过 DB）。
   *
   * <p>一个事务里完成：处理记录 + 库存分录 + 余额 / 库位增量 + 流转痕迹。 任何一步失败整体回滚，不会留下「货撤了但记录没改」的半截状态。
   */
  @Transactional
  public DiscrepancyHandlingSaved decide(
      AccessService.Access actor, UUID handlingId, DiscrepancyDecisionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("处理决定请求不能为空");
    }
    Record head = lockHandling(handlingId);
    requireVersion(head, request.expectedVersion());
    String status = head.get("status", String.class);
    if (!HandlingStatus.allowedNext(HandlingStatus.parse(status))
        .contains(HandlingStatus.DECIDED)) {
      throw statusError(status, "只有待处理状态才能提交处理决定");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    UUID receiptId = head.get("receipt_id", UUID.class);
    UUID skuId = head.get("sku_id", UUID.class);
    String type = head.get("discrepancy_type", String.class);
    BigDecimal quantity = decimal(head, "quantity");
    HandlingDisposition disposition =
        HandlingDisposition.parse(require(request.disposition(), "处理决定"));
    boolean risk = RISK_DISCREPANCY_TYPES.contains(type);

    // 硬规则第一道（服务层）：风险差异类型不得按正常入库处理。
    if (risk && disposition == HandlingDisposition.ACCEPT) {
      throw new IllegalArgumentException(
          GoodsReceiptModels.DiscrepancyType.parse(type).label()
              + "不允许按「正常入库」处理："
              + "蓝图要求破损与短少必须拒收或待检入库，直接入可售会把问题货卖出去。"
              + "可选处理：拒收 / 部分接收 / 待检入库");
    }

    BigDecimal accepted;
    BigDecimal rejected;
    switch (disposition) {
      case REJECT -> {
        accepted = BigDecimal.ZERO;
        rejected = quantity;
      }
      case ACCEPT, HOLD_FOR_INSPECTION -> {
        accepted = quantity;
        rejected = BigDecimal.ZERO;
      }
      case PARTIAL_ACCEPT -> {
        accepted = stock(request.acceptedQuantity(), "接受数量");
        rejected = stock(request.rejectedQuantity(), "拒收数量");
        if (accepted.signum() == 0 || rejected.signum() == 0) {
          throw new IllegalArgumentException("部分接收要求接受量与拒收量都大于 0：全都要就是「拒收」或「正常入库」，不是部分接收");
        }
      }
      default -> throw new IllegalStateException("未覆盖的处理决定：" + disposition);
    }
    if (accepted.add(rejected).compareTo(quantity) != 0) {
      throw new IllegalArgumentException(
          "处理数量对不上：差异数量 "
              + quantity
              + "，接受 "
              + accepted
              + " + 拒收 "
              + rejected
              + " = "
              + accepted.add(rejected)
              + "。接受量与拒收量之和必须恰好等于差异数量");
    }

    // 入库去向：接受量 > 0 时必填。待检入库还必须是待检 / 残次。
    UUID inboundQualityId = null;
    String inboundRole = null;
    if (accepted.signum() > 0) {
      inboundQualityId = require(request.inboundQualityStatusId(), "入库去向质量状态");
      inboundRole = requireQualityRole(tenantId, inboundQualityId);
      if (disposition == HandlingDisposition.HOLD_FOR_INSPECTION
          && !("PENDING_INSPECTION".equals(inboundRole) || "DEFECTIVE".equals(inboundRole))) {
        throw new IllegalArgumentException(
            "待检入库的去向质量状态必须是待检或残次（当前是" + roleLabel(inboundRole) + "）：待检的意思就是先不入可售");
      }
    }

    Record receipt =
        sql.fetchOne(
            "select warehouse_id,legal_entity_id from goods_receipt where id=?", receiptId);
    if (receipt == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收货单不存在或无权查看");
    }
    UUID warehouseId = receipt.get("warehouse_id", UUID.class);
    UUID legalEntityId = receipt.get("legal_entity_id", UUID.class);
    UUID locationId = request.locationId();
    if (accepted.signum() > 0 && locationId == null) {
      throw new IllegalArgumentException("接受入库 " + accepted + " 必须指定落库库位");
    }
    if (locationId != null) {
      requireLocation(tenantId, locationId, warehouseId);
    }
    if (request.lotId() != null
        && sql.fetchOne(
                "select id from inventory_lot where id=? and tenant_id=?",
                request.lotId(),
                tenantId)
            == null) {
      throw new IllegalArgumentException("批次不存在或无权访问：" + request.lotId());
    }
    UUID actorId = actor.userId();
    OffsetDateTime at = OffsetDateTime.now();

    // 「这批货现在在不在库里」由**调用方如实声明**，不由服务层猜：
    //   fromQualityStatusId 给了 = 货已在库存里那个质量状态（收货时已入可售，现在要挪走）
    //   fromQualityStatusId 没给 = 货从没进过库（典型是短少，后来供应商补货才到）
    // 猜错的后果是凭空多出或少掉一批货，所以宁可要求调用方说明，也不自动选一个。
    //
    // 接受部分的三种情形：
    //   a) from 与去向相同        → 货已经在目标质量状态，**不动库存**（超收确认就是这种）
    //   b) from 与去向不同且都有  → **质量状态流转**：从 from 减、往去向加，总量不变
    //   c) from 为空              → 纯入库：货第一次进库
    UUID fromQualityId = request.fromQualityStatusId();
    if (fromQualityId != null) {
      requireQualityRole(tenantId, fromQualityId);
    }
    boolean acceptedMoves =
        accepted.signum() > 0
            && locationId != null
            && (fromQualityId == null || !fromQualityId.equals(inboundQualityId));
    boolean withdrawsStock = rejected.signum() > 0 && locationId != null;
    boolean movesStock = acceptedMoves || withdrawsStock;
    UUID transactionId = null;
    if (movesStock) {
      if (skuId == null) {
        throw new IllegalArgumentException(
            "这条差异没有对应 SKU（单级差异），无法动库存：只能记为「拒收不入账」。" + "要真的撤出或入库，请在收货单明细行上登记差异");
      }
      transactionId = UUID.randomUUID();
      // 分录用 V1 已有的 kind='ADJUSTMENT'：这是库存调整而不是一次新的到货收货，
      // 来源单据挂在差异处理上（ref_type/ref_id），台账可回溯到是哪条差异引起的。
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
              + "notes) values(?,?,?,?,?,cast(? as timestamptz),?,?)",
          transactionId,
          tenantId,
          "ADJUSTMENT",
          REF_TYPE,
          handlingId,
          at,
          actorId,
          "收货差异处理：接受 "
              + accepted
              + "，拒收 "
              + rejected
              + "；"
              + "去向质量状态 "
              + (inboundQualityId == null ? "无（全部拒收）" : inboundQualityId));
    }

    // 接受部分。方向由**实际去向质量状态的 role** 推导，而不是由差异类型推导 ——
    // 方向是事实，规则由 DB 触发器独立判定，两者不共用同一个判断来源。
    if (acceptedMoves) {
      boolean toSellable = "SELLABLE".equals(inboundRole);
      StockMoveDirection direction =
          toSellable ? StockMoveDirection.INBOUND_TO_SELLABLE : StockMoveDirection.INBOUND_TO_HOLD;
      // 情形 b：货已在 from 质量状态里，先真扣掉，再加到去向 —— 两条分录一正一负，总量不变。
      // 只加不减会凭空多出 accepted 件：处理记录上写着「接受 4 件」，库存里却凭空多了 4 件，
      // 而这 4 件本来就在可售里，会被直接卖掉。
      if (fromQualityId != null) {
        UUID fromBalance = balanceId(tenantId, skuId, warehouseId, legalEntityId, fromQualityId);
        addOutbound(fromBalance, locationId, request.lotId(), accepted, at);
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                + "reserved_delta) values(?,?,?,?,?,0)",
            UUID.randomUUID(),
            tenantId,
            transactionId,
            fromBalance,
            accepted.negate());
        insertStockMove(
            tenantId,
            handlingId,
            head.get("discrepancy_id", UUID.class),
            skuId,
            warehouseId,
            legalEntityId,
            StockMoveDirection.WITHDRAW,
            fromQualityId,
            null,
            locationId,
            request.lotId(),
            accepted,
            transactionId,
            at,
            actorId,
            "质量状态流转出：" + fromQualityId + " 转出 " + accepted);
      }
      UUID toBalance = balanceId(tenantId, skuId, warehouseId, legalEntityId, inboundQualityId);
      addInbound(toBalance, locationId, request.lotId(), accepted, at);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(),
          tenantId,
          transactionId,
          toBalance,
          accepted);
      insertStockMove(
          tenantId,
          handlingId,
          head.get("discrepancy_id", UUID.class),
          skuId,
          warehouseId,
          legalEntityId,
          direction,
          null,
          inboundQualityId,
          locationId,
          request.lotId(),
          accepted,
          transactionId,
          at,
          actorId,
          (fromQualityId == null ? "差异处理接受入库 " : "质量状态流转入：")
              + accepted
              + "，去向质量状态 "
              + inboundQualityId);
    } else if (accepted.signum() > 0
        && locationId != null
        && fromQualityId != null
        && fromQualityId.equals(inboundQualityId)) {
      // 情形 a：货已经在这个质量状态里（例如超收确认 —— 收货时就入了可售，现在只是确认）。
      // **不重复加减**：再来一次就会凭空翻倍。这也是「超收确认后库存仍是 105 而不是 110」的依据。
      insertStockMove(
          tenantId,
          handlingId,
          head.get("discrepancy_id", UUID.class),
          skuId,
          warehouseId,
          legalEntityId,
          StockMoveDirection.NO_MOVE,
          fromQualityId,
          inboundQualityId,
          locationId,
          request.lotId(),
          accepted,
          null,
          at,
          actorId,
          "货已在去向质量状态（" + inboundQualityId + "）内，接受 " + accepted + " 无需移动库存");
    }

    // 拒收部分：给了库位就是货已入库、真撤出；没给就是货从没进过库，只留不入账痕迹。
    if (rejected.signum() > 0) {
      if (withdrawsStock) {
        UUID withdrawFrom = require(fromQualityId, "撤出前的质量状态");
        UUID balanceId = balanceId(tenantId, skuId, warehouseId, legalEntityId, withdrawFrom);
        addOutbound(balanceId, locationId, request.lotId(), rejected, at);
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                + "reserved_delta) values(?,?,?,?,?,0)",
            UUID.randomUUID(),
            tenantId,
            transactionId,
            balanceId,
            rejected.negate());
        insertStockMove(
            tenantId,
            handlingId,
            head.get("discrepancy_id", UUID.class),
            skuId,
            warehouseId,
            legalEntityId,
            StockMoveDirection.WITHDRAW,
            withdrawFrom,
            null,
            locationId,
            request.lotId(),
            rejected,
            transactionId,
            at,
            actorId,
            "差异处理拒收，从库存撤出 " + rejected);
      } else {
        insertStockMove(
            tenantId,
            handlingId,
            head.get("discrepancy_id", UUID.class),
            skuId,
            warehouseId,
            legalEntityId,
            StockMoveDirection.NOT_POSTED,
            null,
            null,
            null,
            null,
            rejected,
            null,
            at,
            actorId,
            "差异处理拒收：这批货从未进入库存，不写分录、不改余额");
      }
    }

    sql.execute(
        "update goods_receipt_discrepancy_handling set status='DECIDED',disposition=?,"
            + "accepted_quantity=?,rejected_quantity=?,inbound_quality_status_id=?,"
            + "decision_reason=?,decision_note=?,decided_at=cast(? as timestamptz),decided_by=?,"
            + "version=version+1,updated_at=now() where id=?",
        disposition.name(),
        accepted,
        rejected,
        inboundQualityId,
        fit(request.reason(), 200, "决定原因"),
        fit(request.note(), 2000, "决定说明"),
        at,
        actorId,
        handlingId);
    return savedOf(handlingId);
  }

  private void insertStockMove(
      UUID tenantId,
      UUID handlingId,
      UUID discrepancyId,
      UUID skuId,
      UUID warehouseId,
      UUID legalEntityId,
      StockMoveDirection direction,
      UUID fromQualityId,
      UUID toQualityId,
      UUID locationId,
      UUID lotId,
      BigDecimal quantity,
      UUID transactionId,
      OffsetDateTime at,
      UUID actorId,
      String note) {
    sql.execute(
        "insert into goods_receipt_discrepancy_stock_move(id,tenant_id,handling_id,discrepancy_id,"
            + "sku_id,warehouse_id,legal_entity_id,direction,from_quality_status_id,"
            + "to_quality_status_id,location_id,lot_id,quantity,transaction_id,occurred_at,actor_id,note)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),?,?)",
        UUID.randomUUID(),
        tenantId,
        handlingId,
        discrepancyId,
        skuId,
        warehouseId,
        legalEntityId,
        direction.name(),
        fromQualityId,
        toQualityId,
        locationId,
        lotId,
        quantity,
        transactionId,
        at,
        actorId,
        note);
  }

  // ================================================================ 索赔

  /**
   * 发起索赔：只记来源单据与索赔意图 / 状态，**不建假采购索赔单**（见迁移文件头「三」）。
   *
   * <p>金额可空：供应商还没报价时先把索赔发起 —— 「金额必填」会让现场因为等一个 还没有人给的数字而把货损压着不报，那才是真损失。
   */
  @Transactional
  public DiscrepancyHandlingSaved claim(
      AccessService.Access actor, UUID handlingId, DiscrepancyClaimRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("索赔请求不能为空");
    }
    Record head = lockHandling(handlingId);
    requireVersion(head, request.expectedVersion());
    String status = head.get("status", String.class);
    if (!HandlingStatus.allowedNext(HandlingStatus.parse(status))
        .contains(HandlingStatus.CLAIMED)) {
      throw statusError(status, "只有已决定状态才能发起索赔");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    UUID receiptId = head.get("receipt_id", UUID.class);
    if (sql.fetchOne(
            "select id from goods_receipt_discrepancy_claim where handling_id=?", handlingId)
        != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这条处理记录已经有一条索赔了：同一批差异发两次索赔会让对账收到重复金额");
    }

    ClaimType claimType = ClaimType.parse(require(request.claimType(), "索赔类型"));
    // 索赔数量**不动库存**，所以按差异记录的原始精度（numeric(19,6)）接受，
    // 不能套 stock() 的 4 位上限 —— 差异数量从库里读出来就带 6 位小数，
    // 用 stock() 校验会让「不填索赔数量、默认取整条差异」这条最自然的用法直接报错。
    BigDecimal claimedQuantity =
        positive6(
            request.claimedQuantity() == null
                ? decimal(head, "quantity")
                : request.claimedQuantity(),
            "索赔数量");
    BigDecimal amount = null;
    if (request.claimedAmount() != null) {
      if (request.claimedAmount().signum() <= 0 || request.claimedAmount().scale() > 4) {
        throw new IllegalArgumentException("索赔金额必须大于 0 且最多 4 位小数");
      }
      amount = request.claimedAmount();
    }
    String currency = null;
    if (request.currency() != null && !request.currency().isBlank()) {
      currency = request.currency().trim().toUpperCase(Locale.ROOT);
      if (!currency.matches("^[A-Z]{3}$")) {
        throw new IllegalArgumentException("币种必须是 ISO 4217 三字母大写代码：" + currency);
      }
    }
    if (amount != null && currency == null) {
      throw new IllegalArgumentException("给了索赔金额就必须同时给币种：没有币种的金额无法结算");
    }
    if (currency != null && amount == null) {
      throw new IllegalArgumentException("给了币种就必须同时给索赔金额：只给币种的索赔是对账那边的空行");
    }

    // 索赔对象默认取收货单的来源单据：这是「向谁追」的既成事实，
    // 现场不需要再填一遍，填了则以现场填的为准（承运异常与供应商短少可能不是同一个来源）。
    UUID sourceRefId = request.sourceRefId();
    if (sourceRefId == null) {
      sourceRefId =
          sql.fetchOne("select source_ref_id from goods_receipt where id=?", receiptId)
              .get("source_ref_id", UUID.class);
    }
    String sourceRefCode = fit(request.sourceRefCode(), 64, "来源单号");
    if (sourceRefCode.isBlank() && sourceRefId != null) {
      Record receipt =
          sql.fetchOne("select source_ref_code from goods_receipt where id=?", receiptId);
      sourceRefCode = receipt == null ? "" : receipt.get("source_ref_code", String.class);
    }

    long seq = sql.fetchOne("select nextval('goods_receipt_claim_code_seq')").get(0, Long.class);
    var today = sql.fetchOne("select current_date").get(0, java.time.LocalDate.class);
    String code = "CLM-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
    sql.execute(
        "insert into goods_receipt_discrepancy_claim(id,tenant_id,claim_code,handling_id,receipt_id,"
            + "source_ref_id,source_ref_code,claim_type,claimed_quantity,claimed_amount,currency,"
            + "reason,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        code,
        handlingId,
        receiptId,
        sourceRefId,
        sourceRefCode,
        claimType.name(),
        claimedQuantity,
        amount,
        currency,
        fit(request.reason(), 2000, "索赔说明"),
        actor.userId());
    sql.execute(
        "update goods_receipt_discrepancy_handling set status='CLAIMED',version=version+1,"
            + "updated_at=now() where id=?",
        handlingId);
    return savedOf(handlingId);
  }

  /** 提交索赔给对方。已提交不可撤回（DB 触发器钉死）：撤回只能发生在已发起阶段。 */
  @Transactional
  public DiscrepancyClaimRow submitClaim(
      AccessService.Access actor, UUID handlingId, DiscrepancyClaimSubmitRequest request) {
    Record head = lockHandling(handlingId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    if (HandlingStatus.CLOSED.name().equals(head.get("status", String.class))) {
      throw statusError(head.get("status", String.class), "已关闭的处理记录不再接受索赔动作");
    }
    Record claim = lockClaim(handlingId);
    if (!claim.get("status", String.class).equals(ClaimStatus.INITIATED.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "索赔当前是「"
              + ClaimStatus.parse(claim.get("status", String.class)).label()
              + "」：只有已发起的索赔能提交");
    }
    sql.execute(
        "update goods_receipt_discrepancy_claim set status='SUBMITTED',"
            + "submitted_at=cast(? as timestamptz),submitted_by=?,version=version+1,"
            + "updated_at=now() where id=?",
        OffsetDateTime.now(),
        actor.userId(),
        claim.get("id", UUID.class));
    return claimOf(handlingId);
  }

  /** 提交前撤回索赔。 */
  @Transactional
  public DiscrepancyClaimRow withdrawClaim(
      AccessService.Access actor, UUID handlingId, DiscrepancyClaimWithdrawRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("撤回索赔请求不能为空");
    }
    Record head = lockHandling(handlingId);
    requireVersion(head, request.expectedVersion());
    Record claim = lockClaim(handlingId);
    if (!claim.get("status", String.class).equals(ClaimStatus.INITIATED.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "索赔当前是「"
              + ClaimStatus.parse(claim.get("status", String.class)).label()
              + "」：只有已发起的索赔能撤回（已提交不可撤回，"
              + "否则对账那边会出现一笔凭空消失的索赔）");
    }
    String reason = fit(request.reason(), 200, "撤回原因");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("撤回索赔必须给出原因");
    }
    sql.execute(
        "update goods_receipt_discrepancy_claim set status='WITHDRAWN',"
            + "withdrawn_at=cast(? as timestamptz),withdrawn_reason=?,version=version+1,"
            + "updated_at=now() where id=?",
        OffsetDateTime.now(),
        reason,
        claim.get("id", UUID.class));
    // 撤回后处理记录退回已决定：它不再「已索赔」，但处理决定与库存流转原样保留。
    sql.execute(
        "update goods_receipt_discrepancy_handling set status='DECIDED',version=version+1,"
            + "updated_at=now() where id=? and status='CLAIMED'",
        handlingId);
    return claimOf(handlingId);
  }

  private Record lockClaim(UUID handlingId) {
    Record claim =
        sql.fetchOne(
            "select * from goods_receipt_discrepancy_claim where handling_id=? for update",
            handlingId);
    if (claim == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "索赔记录不存在或无权查看");
    }
    return claim;
  }

  // ================================================================ 关单

  /**
   * 关闭处理。要求已决定，且必须有凭证 / 库存流转 / 索赔三者之一 —— DB 触发器 {@code guard_discrepancy_handling_close}
   * 钉死，关掉一条空白的差异处理 等于把货损痕迹抹掉。
   */
  @Transactional
  public DiscrepancyHandlingSaved close(
      AccessService.Access actor, UUID handlingId, DiscrepancyHandlingCloseRequest request) {
    Record head = lockHandling(handlingId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    String status = head.get("status", String.class);
    if (!HandlingStatus.allowedNext(HandlingStatus.parse(status)).contains(HandlingStatus.CLOSED)) {
      throw statusError(status, "当前状态不能关闭");
    }
    String reason = fit(request == null ? null : request.reason(), 200, "关闭原因");
    String finalReason = reason.isBlank() ? "差异处理完成，结案" : reason;
    sql.execute(
        "update goods_receipt_discrepancy_handling set status='CLOSED',"
            + "closed_at=cast(? as timestamptz),closed_by=?,close_reason=?,version=version+1,"
            + "updated_at=now() where id=?",
        OffsetDateTime.now(),
        actor.userId(),
        finalReason,
        handlingId);
    return savedOf(handlingId);
  }

  // ================================================================ 库存写入

  /**
   * 入库：加余额 + 同步加库位明细（V2 守恒触发器要求两处同动）。
   *
   * <p>用**带符号增量**而不是覆盖式写回：覆盖式写回（{@code set on_hand = 期望值}） 会把处理期间真实发生的其他交易（并行的出库、预占、盘点调整）整段抹掉。
   *
   * <p>{@code last_movement_at} 用 {@code greatest(...)} 保证严格单调： V2 的 {@code
   * guard_bucket_movement_time} 会挡住倒退。
   */
  private void addInbound(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
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

  /**
   * 出库（拒收撤出）：减余额 + 同步减库位明细。
   *
   * <p>先查库位上的实际在手量并给出可读报错：V1 / V2 的 {@code CHECK(available>=0)}
   * 确实会挡住扣成负数，但那条报错只说「违反检查约束」，完全不指到是哪批货、 在哪个库位、差多少。这里先说人话 —— 常见真实原因是货已经被销售预占。
   */
  private void addOutbound(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record bucket =
        sql.fetchOne(
            "select id,on_hand,reserved from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (bucket == null) {
      throw new IllegalArgumentException(
          "撤出失败：库位上没有这条 SKU 的库存行。拒收不入账请不要传 locationId —— " + "传了 locationId 就表示货已入库，要真从库存里撤出去");
    }
    BigDecimal onHand = decimal(bucket, "on_hand");
    BigDecimal reserved = decimal(bucket, "reserved");
    if (onHand.compareTo(qty) < 0) {
      throw new IllegalArgumentException(
          "撤出失败：库位上只有 "
              + onHand
              + "，要撤 "
              + qty
              + (reserved.signum() > 0 ? "（其中 " + reserved + " 已被预占）" : "")
              + "。货可能已被销售预占或已出库，先处理占用再撤出");
    }
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?,"
            + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
            + " + interval '1 microsecond') where id=?",
        qty,
        at,
        bucket.get("id", UUID.class));
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty,
        balanceId);
  }

  /** 取（或建）一条余额行。跨租户塞进来的仓/SKU 会被 UNIQUE 或 RLS 挡住。 */
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

  // ================================================================ 校验与工具

  /** 锁行 + 读租户。并发决定 / 索赔时同一条被串行化，版本比对才可靠。 */
  private Record lockHandling(UUID handlingId) {
    Record head =
        sql.fetchOne(
            "select * from goods_receipt_discrepancy_handling where id=? for update", handlingId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异处理记录不存在或无权查看");
    }
    return head;
  }

  /** 乐观锁：写操作必须带上版本号，不匹配一律 409，绝不静默覆盖。 */
  private void requireVersion(Record head, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    long actual = head.get("version", Long.class);
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "差异处理记录已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "），请刷新后重试");
    }
  }

  private static ResponseStatusException statusError(String actual, String hint) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "差异处理当前是「" + HandlingStatus.parse(actual).label() + "」：" + hint);
  }

  private String requireQualityRole(UUID tenantId, UUID qualityStatusId) {
    Record q =
        sql.fetchOne(
            "select role from inventory_quality_status where id=? and tenant_id=?",
            qualityStatusId,
            tenantId);
    if (q == null) {
      throw new IllegalArgumentException("质量状态不存在或无权访问：" + qualityStatusId);
    }
    return q.get("role", String.class);
  }

  private void requireLocation(UUID tenantId, UUID locationId, UUID warehouseId) {
    if (sql.fetchOne(
            "select id from warehouse_location where id=? and tenant_id=? and warehouse_id=?",
            locationId,
            tenantId,
            warehouseId)
        == null) {
      throw new IllegalArgumentException("落库库位不存在，或不属于收货仓（差异处理只能动本仓的库位）：" + locationId);
    }
  }

  private static String roleLabel(String role) {
    return switch (role) {
      case "SELLABLE" -> "可售";
      case "PENDING_INSPECTION" -> "待检";
      case "DEFECTIVE" -> "残次";
      default -> role;
    };
  }

  private static UUID require(UUID value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少" + label);
    }
    return value;
  }

  private static String require(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少" + label);
    }
    return value;
  }

  /**
   * 会真的动库存的数量：最多 4 位小数。
   *
   * <p>为什么比 8.2.1.1 的 6 位更严：{@code inventory_balance.on_hand} 与 {@code stock_bucket.on_hand} 都是
   * {@code numeric(19,4)}。给 6 位小数会被数据库 **静默四舍五入**，于是处理记录上写着接受 1.000001、库存余额上却是 1.0000 ——
   * 两个数对不上却谁都不报错。这里直接拒绝并说清原因。
   */
  private static BigDecimal stock(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > 4) {
      throw new IllegalArgumentException(
          label + "最多 4 位小数：这批货要真的动库存，而库存余额列是 numeric(19,4)，" + "给更多小数会被数据库静默四舍五入，两处数量就对不上了");
    }
    return value;
  }

  /** 不动库存的数量：最多 6 位小数，与差异事实表 numeric(19,6) 同口径。 */
  private static BigDecimal positive6(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > 6) {
      throw new IllegalArgumentException(label + "最多 6 位小数（与差异数量口径 numeric(19,6) 一致）");
    }
    return value;
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  /** 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，收口成不依赖驱动返回类型的读法。 */
  private static BigDecimal decimal(Record r, String field) {
    var value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof Number num) {
      return new BigDecimal(num.toString());
    }
    return new BigDecimal(value.toString());
  }

  private static long n(Record r, String field) {
    var value = r.get(field);
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static DiscrepancyHandlingRow row(Record r) {
    var status = HandlingStatus.parse(r.get("status", String.class));
    String typeRaw = r.get("discrepancy_type", String.class);
    var type = GoodsReceiptModels.DiscrepancyType.parse(typeRaw);
    String dispositionRaw = r.get("disposition", String.class);
    HandlingDisposition disposition =
        dispositionRaw == null ? null : HandlingDisposition.parse(dispositionRaw);
    String claimStatus = r.get("claim_status", String.class);
    String skuCode = r.get("sku_code", String.class);
    return new DiscrepancyHandlingRow(
        r.get("id", UUID.class),
        r.get("discrepancy_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("line_id", UUID.class),
        skuCode,
        r.get("sku_name", String.class),
        type.name(),
        type.label(),
        decimal(r, "quantity"),
        status.name(),
        status.label(),
        dispositionRaw,
        disposition == null ? null : disposition.label(),
        decimal(r, "accepted_quantity"),
        decimal(r, "rejected_quantity"),
        r.get("inbound_quality_status_name", String.class),
        n(r, "evidence_count"),
        n(r, "stock_move_count"),
        claimStatus != null,
        claimStatus,
        r.get("decided_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        n(r, "version"),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DiscrepancyClaimRow claimRow(Record c) {
    var type = ClaimType.parse(c.get("claim_type", String.class));
    var status = ClaimStatus.parse(c.get("status", String.class));
    return new DiscrepancyClaimRow(
        c.get("id", UUID.class),
        c.get("claim_code", String.class),
        type.name(),
        type.label(),
        decimal(c, "claimed_quantity"),
        decimalOrNull(c, "claimed_amount"),
        c.get("currency", String.class),
        status.name(),
        status.label(),
        c.get("source_ref_id", UUID.class),
        c.get("source_ref_code", String.class),
        c.get("claim_ref_id", UUID.class),
        c.get("reason", String.class),
        c.get("submitted_at", OffsetDateTime.class),
        c.get("withdrawn_at", OffsetDateTime.class),
        n(c, "version"),
        c.get("created_at", OffsetDateTime.class));
  }

  /** 金额可空：null 与 0 含义完全不同（没报价 vs 报价 0），不能用 ZERO 顶替。 */
  private static BigDecimal decimalOrNull(Record r, String field) {
    var value = r.get(field);
    return value == null ? null : decimal(r, field);
  }

  /** 每个写动作后回读：响应是「库里真实存了什么」。 */
  private DiscrepancyHandlingSaved savedOf(UUID handlingId) {
    Record r = sql.fetchOne(rowSelect() + fromClause() + aggJoin() + " where h.id=?", handlingId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异处理记录不存在或无权查看");
    }
    var status = HandlingStatus.parse(r.get("status", String.class));
    String dispositionRaw = r.get("disposition", String.class);
    HandlingDisposition disposition =
        dispositionRaw == null ? null : HandlingDisposition.parse(dispositionRaw);
    String claimStatus = r.get("claim_status", String.class);
    return new DiscrepancyHandlingSaved(
        r.get("id", UUID.class),
        status.name(),
        status.label(),
        dispositionRaw,
        disposition == null ? null : disposition.label(),
        decimal(r, "accepted_quantity"),
        decimal(r, "rejected_quantity"),
        n(r, "evidence_count"),
        claimStatus != null,
        claimStatus,
        n(r, "version"),
        r.get("decided_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class));
  }
}
