package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.1.2 寄售与代销：寄售协议 → 寄售可用量 → 销售事实 → 结算触发单（→ 12 应付）→ 未售退货与期末清理。
 *
 * <p>蓝图原话：「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、 销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」
 *
 * <p><b>本模块只有一条不可让步的财务语义：寄售未售出不产生采购成本与应付。</b> 它写在 V71 的四道数据库不变量里，不是这里的自觉：
 *
 * <ol>
 *   <li>协议行的 {@code remaining_quantity} 是生成列（寄售 − 已售 − 已退回 − 已转采购）， 且 {@code
 *       CHECK(remaining>=0)}：寄售量这个数永远只是寄售量，不会变成成本或负债。
 *   <li>结算明细的 {@code amount} 是生成列（已售数量 × 结算价）：应付**只有**这一个来源。
 *   <li>结算明细 {@code UNIQUE(tenant_id, sale_id)}：一条销售事实最多进一张结算单， 「售出 → 应付」不会重复发生。
 *   <li>退货单的 {@code CHECK((disposition='CONVERT_TO_PURCHASE') OR total_payable_amount=0)}：
 *       未售退回不产生应付，转入采购是协议写明的**唯一**例外。
 * </ol>
 *
 * <p><b>货主建模是自落地的，不动 {@code stock_bucket}。</b>兄弟模块 7.7.1.1「货主与所有权」 尚未交付且会自己给 bucket 加 {@code
 * stock_owner} 列；本模块因此把寄售事实落成 自成体系的一组表，明细行自带 {@code stockOwnerType='SUPPLIER'} 与恒为 false 的 {@code
 * countsAsOwnedAsset}（DB CHECK 钉死）。这样两条车道各写各的 DDL，合并期不冲突， 同时满足
 * 07-inventory.md:24「寄售货主是供应商，我方只记持有位置与可销售权，不记为自有资产」。
 *
 * <p><b>销售事实自落地</b>：9 订单尚未交付，而「销售触发结算数量」是本模块职责，不能因上游缺席 就停摆。本模块自落地 {@code consignment_sale}，以 {@code
 * (agreement_line_id, sale_ref)} 幂等；9 交付后由它推，本表即为那份事实的唯一出处。
 *
 * <p><b>对外只留定位键</b>：12 应付 / 10 物流 / 6 采购均未交付，本模块只出单据并写 {@code source_system +
 * reference_id}，不跨库读写、不建跨服务 FK（AGENTS.md 同一纪律）。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里， 后面全是普通绑定，cast 永远不在绑定序列末位。写入用
 * {@link OffsetDateTime} 绑定， 不走 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class ConsignmentService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张结算单最多收多少条销售事实。 */
  public static final int MAX_SETTLEMENT_LINES = 5000;

  /** 单张退货/清理单最多收多少行。 */
  public static final int MAX_RETURN_LINES = 2000;

  /** 审计时间线单次返回上限。 */
  static final int MAX_AUDIT_ROWS = 500;

  /** numeric(19,6) 口径下的零。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** 金额列是 numeric(24,6)，零按同一 scale 写，避免同一列里混着不同 scale 的 0。 */
  private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.000000");

  /** 交接口径原文：应付只有这一个来源。 */
  private static final String PAYABLE_BASIS =
      "应付只有一个来源：结算明细的「已售数量 × 结算价」（V71 生成列 amount）。"
          + "寄售量、库存余额、期末未售数量都不会产生应付 —— 这是寄售与普通采购在财务上的根本分叉。";

  private static final String COST_BASIS_NOTE =
      "本模块没有采购入库成本列，而且按设计也不该有：寄售货不进我方采购入库成本，"
          + "仅在售出时按结算价结转。所以「寄售库存成本」这个口径在本模块恒不可算，"
          + "报告如实给 null 而不是给 0（给 0 会被读成「这些货值 0 元」）。";

  private static final String OWNERSHIP_NOTE =
      "寄售货的货主恒为供应商（stockOwnerType=SUPPLIER），我方只记持有位置与可销售权，"
          + "不记为自有资产：countsAsOwnedAsset 由数据库 CHECK 钉死为 false，"
          + "把寄售货并进自有资产口径会先被数据库挡住。";

  private static final String SALE_FACT_NOTE =
      "销售事实由本服务记（9 订单尚未交付）。以 (协议行, saleRef) 幂等，重复推送不会卖两次、"
          + "也就不会算两次应付；9 订单交付后由它推这张表，届时本表仍是那份事实的唯一出处。";

  private static final String PAYABLE_HANDOFF_NOTE =
      "结算触发单是本服务内的事实，12 应付尚未交付。本模块只写 source_system + reference_id "
          + "定位键，12 交付后按该键读回；本模块不替 12 写应付、不建跨服务 FK、不查对方的表。";

  private static final String RETURN_HANDOFF_NOTE =
      "期末退回只出单据：10 物流（运输）与 6 采购（转采购）均未交付，" + "本模块写 source_system + reference_id 交接键，不跨库写它们的表。";

  private final DSLContext sql;

  public ConsignmentService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /** 寄售能力说明：前端据此渲染选项与提示，而不是把规则硬编码在界面上。 */
  public ConsignmentCapabilities capabilities() {
    var modes =
        List.of(ConsignmentMode.values()).stream()
            .map(m -> new ConsignmentOption(m.name(), m.label(), m.description(), true))
            .toList();
    var cycles =
        List.of(ConsignmentSettlementCycle.values()).stream()
            .map(c -> new ConsignmentOption(c.name(), c.label(), c.description(), true))
            .toList();
    var treatments =
        List.of(ConsignmentEndTreatment.values()).stream()
            .map(
                t ->
                    new ConsignmentOption(
                        t.name(),
                        t.label(),
                        t.description(),
                        t == ConsignmentEndTreatment.CONVERT_TO_PURCHASE))
            .toList();
    var kinds =
        List.of(ConsignmentReturnKind.values()).stream()
            .map(k -> new ConsignmentOption(k.name(), k.label(), k.description(), false))
            .toList();
    return new ConsignmentCapabilities(
        modes,
        cycles,
        treatments,
        kinds,
        List.of(ConsignmentAgreementStatus.values()).stream().map(Enum::name).toList(),
        List.of("RETURN_TO_SUPPLIER", "CONVERT_TO_PURCHASE"),
        PAYABLE_BASIS,
        false,
        COST_BASIS_NOTE,
        OWNERSHIP_NOTE,
        SALE_FACT_NOTE,
        PAYABLE_HANDOFF_NOTE,
        RETURN_HANDOFF_NOTE);
  }

  // ============================================================ 协议

  /**
   * 建寄售协议（草稿），并同时写入明细行（寄售量 + 结算价）。
   *
   * <p>协议与明细一起提交：一张没有寄售量与结算价的协议没有任何业务含义。 明细为空时显式拒绝 —— 悄悄建一张零行协议会让台账上看起来像「已经开始寄售了」。
   */
  @Transactional
  public ConsignmentAgreementDetail createAgreement(ConsignmentAgreementInput input, UUID actorId) {
    var tenant = tenant();
    if (input.lines() == null || input.lines().isEmpty()) {
      throw bad("寄售协议必须至少带一行寄售明细：没有寄售量与结算价的协议无法结算。");
    }
    if (input.lines().size() > MAX_RETURN_LINES) {
      throw bad("单张协议的明细行数超过上限 " + MAX_RETURN_LINES + " 行，请分批建立。");
    }
    String mode = requireEnum(input.mode(), ConsignmentMode.class, "经营模式");
    String cycle = requireEnum(input.settlementCycle(), ConsignmentSettlementCycle.class, "结算周期");
    String treatment = requireEnum(input.endTreatment(), ConsignmentEndTreatment.class, "期末处理约定");
    String currency = requireCurrency(input.currency());
    if (input.supplierPartyId() == null) {
      throw bad("必须指定寄售货的货主（供应商）supplierPartyId：寄售的货主是供应商，不是本租户。");
    }
    requireText(input.supplierCode(), "供应商编码");
    requireText(input.supplierName(), "供应商名称");
    if (input.startDate() == null) {
      throw bad("必须指定协议生效起始日 startDate。");
    }
    if (input.endDate() != null && input.endDate().isBefore(input.startDate())) {
      throw bad("协议结束日不能早于起始日。");
    }
    requireRef(input.legalEntityId(), "法人 legalEntityId");

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into consignment_agreement(id,tenant_id,agreement_code,mode,supplier_party_id,"
            + "supplier_code,supplier_name,legal_entity_id,currency,settlement_cycle,end_treatment,"
            + "start_date,end_date,status,note,created_by_user_id) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
        id,
        tenant,
        nextCode("CG"),
        mode,
        input.supplierPartyId(),
        input.supplierCode().trim(),
        input.supplierName().trim(),
        input.legalEntityId(),
        currency,
        cycle,
        treatment,
        input.startDate(),
        input.endDate(),
        nz(input.note()),
        actorId);

    for (var line : input.lines()) {
      insertLine(tenant, id, line);
    }
    audit(tenant, id, "AGREEMENT_CREATED", "建立寄售协议 " + input.lines().size() + " 行", actorId);
    return detail(id);
  }

  /** 协议列表（真分页；行数与五个数量由明细聚合，不另存列）。 */
  public ConsignmentAgreementPage agreements(
      String status, String mode, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("a.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("a.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(mode)) {
      where.add("a.mode = ?");
      binds.add(mode.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add(
          "(a.agreement_code ilike ? or a.supplier_code ilike ? or a.supplier_name ilike ? or a.note ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from consignment_agreement a" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            AGREEMENT_COLUMNS
                + " from "
                + AGREEMENT_FROM
                + sqlWhere
                + " order by a.created_at desc, a.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new ConsignmentAgreementPage(
        rows.stream().map(ConsignmentService::agreement).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  /** 协议详情 = 头部 + 全部明细 + 审计时间线。跨租户 → 404。 */
  public ConsignmentAgreementDetail agreement(UUID agreementId) {
    return detail(agreementId);
  }

  /** 给已存在的协议补一个 SKU（寄售量 + 结算价）。 */
  @Transactional
  public ConsignmentAgreementDetail addLine(
      UUID agreementId, ConsignmentAddLineInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw conflict(
          "只有草稿协议能加行；当前状态 "
              + head.get("status", String.class)
              + "。已在跑的协议要改寄售量请新建协议，否则中途改价会让已出结算单与协议对不上。");
    }
    insertLine(
        tenant,
        agreementId,
        new ConsignmentLineInput(
            input.skuId(),
            input.warehouseId(),
            input.consignedQuantity(),
            input.settlementPrice()));
    audit(tenant, agreementId, "LINE_ADDED", "新增寄售明细行", actorId);
    return detail(agreementId);
  }

  /** 启用协议：DRAFT → ACTIVE。售出、结算、退货都要求协议已启用。 */
  @Transactional
  public ConsignmentAgreement activate(UUID agreementId, ConsignmentNoteInput input, UUID actorId) {
    return transition(agreementId, "DRAFT", "ACTIVE", "AGREEMENT_ACTIVATED", input, actorId);
  }

  /** 暂停协议：ACTIVE → SUSPENDED。暂停后不得再记销售（货主可能已经把货调走）。 */
  @Transactional
  public ConsignmentAgreement suspend(UUID agreementId, ConsignmentNoteInput input, UUID actorId) {
    return transition(agreementId, "ACTIVE", "SUSPENDED", "AGREEMENT_SUSPENDED", input, actorId);
  }

  /** 关闭协议：任何在跑状态 → CLOSED。关闭后全部明细行被 DB 冻结，数量不能再动。 */
  @Transactional
  public ConsignmentAgreement close(UUID agreementId, ConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    OffsetDateTime now = OffsetDateTime.now();
    int changed =
        sql.execute(
            "update consignment_agreement set status='CLOSED', closed_at=?::timestamptz,"
                + " closed_by_user_id=?, note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
                + " where tenant_id=? and id=? and status in ('ACTIVE','SUSPENDED')",
            now,
            actorId,
            note(input),
            now,
            tenant,
            agreementId);
    if (changed == 0) {
      throw conflict("只有生效中或已暂停的协议能关闭（草稿请直接删掉重建）。");
    }
    audit(tenant, agreementId, "AGREEMENT_CLOSED", "关闭协议，全部明细行冻结", actorId);
    return single(agreementId);
  }

  private ConsignmentAgreement transition(
      UUID agreementId,
      String from,
      String to,
      String event,
      ConsignmentNoteInput input,
      UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    OffsetDateTime now = OffsetDateTime.now();
    // 启用时刻与状态同时落，保证 CHECK(status<>'ACTIVE') OR activated_at IS NOT NULL 成立。
    sql.execute(
        "update consignment_agreement set status=?, activated_at=coalesce(activated_at,?::timestamptz),"
            + " activated_by_user_id=coalesce(activated_by_user_id,?),"
            + " note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status=?",
        to,
        now,
        actorId,
        note(input),
        now,
        tenant,
        agreementId,
        from);
    audit(tenant, agreementId, event, "协议状态 " + from + " → " + to, actorId);
    return single(agreementId);
  }

  /**
   * 单份协议的完整读模型。
   *
   * <p><b>刻意不走 {@link #agreements} 的分页列表再回头找自己</b>：那会让一次状态流转在 「这份协议恰好排在第 21 条」时返回 404 ——
   * 刚改完状态却读不到自己，是最难查的一类错。
   */
  private ConsignmentAgreement single(UUID agreementId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            AGREEMENT_COLUMNS + " from " + AGREEMENT_FROM + " where a.tenant_id=? and a.id=?",
            tenant,
            agreementId);
    if (head == null) {
      throw notFound("寄售协议", agreementId);
    }
    return agreement(head);
  }

  // ============================================================ 寄售可用量

  /**
   * 寄售库存视图（蓝图的「寄售量/已售/剩余」）。
   *
   * <p><b>{@code ownedAssetValue} 恒为 null</b>，并且 {@code countsAsOwnedAsset} 恒为 false：
   * 寄售货主是供应商，不是自有资产，而本模块也没有任何库存成本列可算。 如实给 null 而不是给 0 —— 给 0 会被读成「这些货值 0 元」。
   */
  public ConsignmentStockView stockView(UUID agreementId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    var agg =
        sql.fetchOne(
            "select count(*) as line_count, coalesce(sum(consigned_quantity),0) as consigned,"
                + " coalesce(sum(sold_quantity),0) as sold, coalesce(sum(returned_quantity),0) as returned,"
                + " coalesce(sum(converted_quantity),0) as converted,"
                + " coalesce(sum(remaining_quantity),0) as remaining,"
                + " count(*) filter (where remaining_quantity = 0) as zero_lines"
                + " from consignment_agreement_line where tenant_id=? and agreement_id=?",
            tenant,
            agreementId);
    var unsettled =
        sql.fetchOne(
            "select coalesce(sum(s.sold_quantity),0) as qty,"
                + " coalesce(sum(s.sold_quantity * l.settlement_price),0) as amt"
                + " from consignment_sale s"
                + " join consignment_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
                + " where l.agreement_id = ? and s.tenant_id = ?"
                + " and not exists (select 1 from consignment_settlement_line sl"
                + "                 where sl.tenant_id = s.tenant_id and sl.sale_id = s.id)",
            agreementId,
            tenant);
    return new ConsignmentStockView(
        agreementId,
        head.get("agreement_code", String.class),
        head.get("mode", String.class),
        head.get("currency", String.class),
        head.get("settlement_cycle", String.class),
        head.get("end_treatment", String.class),
        head.get("status", String.class),
        scale6(agg.get("consigned", BigDecimal.class)),
        scale6(agg.get("sold", BigDecimal.class)),
        scale6(agg.get("returned", BigDecimal.class)),
        scale6(agg.get("converted", BigDecimal.class)),
        scale6(agg.get("remaining", BigDecimal.class)),
        scale6(unsettled.get("qty", BigDecimal.class)),
        amount(unsettled.get("amt", BigDecimal.class)),
        agg.get("line_count", Long.class),
        agg.get("zero_lines", Long.class),
        "SUPPLIER",
        false,
        null,
        OWNERSHIP_NOTE,
        PAYABLE_BASIS);
  }

  // ============================================================ 销售事实

  /**
   * 记一条销售事实 —— 这是「销售触发结算数量」的唯一来源。
   *
   * <p>三道防线把「卖多了」与「卖两次」挡住：
   *
   * <ol>
   *   <li>协议必须已启用（ACTIVE）：草稿/暂停/关闭的协议不产生销售。
   *   <li>本次售出量不得超过该行剩余：剩余 = 寄售 − 已售 − 已退回 − 已转采购。 超卖在数据库的 {@code CHECK(remaining>=0)}
   *       之外再挡一次，好让人看得懂。
   *   <li>{@code (agreement_line_id, sale_ref)} 唯一：同一外部订单行重复推送会撞 409， 于是「卖一次算两次应付」这条路没有。
   * </ol>
   */
  @Transactional
  public ConsignmentSale recordSale(
      UUID agreementId, UUID lineId, ConsignmentSaleInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw conflict("只有生效中的协议能记销售；当前状态 " + head.get("status", String.class) + "。");
    }
    var line =
        sql.fetchOne(
            "select * from consignment_agreement_line where tenant_id=? and id=? and agreement_id=?",
            tenant,
            lineId,
            agreementId);
    if (line == null) {
      throw notFound("寄售协议明细行", lineId);
    }
    BigDecimal qty = requirePositive(input.soldQuantity(), "售出数量");
    requireText(input.saleRef(), "销售事实引用 saleRef");
    if (input.occurredAt() == null) {
      throw bad("必须指定售出时刻 occurredAt：结算按实际售出归期，缺了它就无法归期。");
    }
    if (input.salePrice() != null && input.salePrice().signum() < 0) {
      throw bad("售价不能为负。");
    }
    String currency = head.get("currency", String.class);
    BigDecimal remaining = line.get("remaining_quantity", BigDecimal.class);
    if (qty.compareTo(remaining) > 0) {
      throw conflict(
          "本次售出 "
              + qty.toPlainString()
              + " 超过该行剩余寄售量 "
              + remaining.toPlainString()
              + "，已拒绝。寄售货主是供应商：在卖供应商没给我们的货之前，"
              + "不能先记销售 —— 那会凭空生成一笔应付。");
    }
    UUID id = UUID.randomUUID();
    String source = notBlank(input.sourceSystem()) ? input.sourceSystem().trim() : "MANUAL";
    // t CTE 让 ?::timestamptz 成为第 1 个绑定，cast 不落在绑定序列末位。
    try {
      sql.execute(
          "with t as (select ?::timestamptz as at) "
              + "insert into consignment_sale(id,tenant_id,agreement_line_id,sold_quantity,sale_ref,"
              + "source_system,sale_price,currency,occurred_at,recorded_by_user_id,created_at) "
              + "select ?,?,?,?,?,?,?,?,t.at,?,t.at from t",
          input.occurredAt(),
          id,
          tenant,
          lineId,
          qty,
          input.saleRef().trim(),
          source,
          input.salePrice(),
          currency,
          actorId);
    } catch (org.springframework.dao.DataAccessException e) {
      // jOOQ 挂在 Spring 的异常转换器上，撞唯一约束抛的是
      // DuplicateKeyException（org.springframework.dao），不是 jOOQ 自己的异常类型。
      throw conflict("这条销售事实已经记过（同一协议行 + saleRef=" + input.saleRef().trim() + " 唯一）。重复推送不会重复计应付。");
    }
    sql.execute(
        "update consignment_agreement_line set sold_quantity = sold_quantity + ?,"
            + " version = version + 1, updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        qty,
        OffsetDateTime.now(),
        tenant,
        lineId);
    audit(
        tenant,
        agreementId,
        "SALE_RECORDED",
        "记录售出 " + qty.toPlainString() + "（ref " + input.saleRef().trim() + "）",
        actorId);
    return sale(tenant, id);
  }

  /** 销售事实列表（真分页；{@code unsettleableOnly=true} 只看还没进结算单的）。 */
  public ConsignmentSalePage sales(
      UUID agreementId, Boolean unsettleableOnly, int pageNo, int size) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("l.agreement_id = ?");
    binds.add(agreementId);
    where.add("l.tenant_id = ?");
    binds.add(tenant);
    if (Boolean.TRUE.equals(unsettleableOnly)) {
      where.add(
          "not exists (select 1 from consignment_settlement_line sl"
              + " where sl.tenant_id = s.tenant_id and sl.sale_id = s.id)");
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from" + SALE_FROM + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            SALE_COLUMNS
                + " from"
                + SALE_FROM
                + sqlWhere
                + " order by s.occurred_at desc, s.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new ConsignmentSalePage(
        rows.stream().map(ConsignmentService::saleRecord).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  // ============================================================ 结算触发单

  /**
   * 出结算触发单：把区间内**已售但还没进任何结算单**的销售事实收成一张单，交给 12 应付。
   *
   * <p>这条路径刻意**没有**「按寄售量出应付」的兄弟端点：V71 的 amount 生成列 与 {@code UNIQUE(tenant_id, sale_id)}
   * 已经让「售出才产生应付」成为数据库事实。
   *
   * <p>归期按**售出时刻**落期（{@code occurred_at} 的 UTC 日期），区间由服务层显式落到 {@code period_start/period_end}，DB
   * 只校验 end > start。
   */
  @Transactional
  public ConsignmentSettlementDetail createSettlement(
      UUID agreementId, ConsignmentSettlementInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw conflict("只有生效中的协议能出结算触发单；当前状态 " + head.get("status", String.class) + "。");
    }
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw bad("必须给出结算区间 periodStart / periodEnd（可用 /settlement-window 取本协议的默认区间）。");
    }
    if (!input.periodEnd().isAfter(input.periodStart())) {
      throw bad("结算区间结束日必须晚于起始日。");
    }
    // 结算周期给出**默认**区间，但允许显式改期（补结算、跨期合并都要用得到）；
    // 这里只提示，不硬拦。
    var pending =
        sql.fetch(
            "select s.id, s.agreement_line_id, s.sold_quantity, l.settlement_price"
                + " from consignment_sale s"
                + " join consignment_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
                + " where l.agreement_id = ? and s.tenant_id = ?"
                + " and s.occurred_at >= ?::timestamptz and s.occurred_at < ?::timestamptz"
                + " and not exists (select 1 from consignment_settlement_line sl"
                + "                 where sl.tenant_id = s.tenant_id and sl.sale_id = s.id)"
                + " order by s.occurred_at, s.id limit ?",
            agreementId,
            tenant,
            input.periodStart().atStartOfDay().atOffset(ZoneOffset.UTC),
            input.periodEnd().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
            MAX_SETTLEMENT_LINES + 1L);
    if (pending.isEmpty()) {
      throw bad("该区间内没有「已售出且尚未结算」的销售事实，结算触发单必须是空的单据时，" + "说明这期根本没卖出东西 —— 未售出不产生应付，这正是寄售该有的结果。");
    }
    if (pending.size() > MAX_SETTLEMENT_LINES) {
      throw bad("该区间待结算的销售事实超过 " + MAX_SETTLEMENT_LINES + " 条，请把结算区间拆小后分批出单。");
    }
    String code = nextCode("ST");
    UUID id = UUID.randomUUID();
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into consignment_settlement(id,tenant_id,settlement_code,agreement_id,period_start,"
            + "period_end,currency,status,source_system,note,created_by_user_id,created_at) "
            + "select ?,?,?,?,?,?,?,'OPEN','WAREHOUSE',?,?,t.at from t",
        OffsetDateTime.now(),
        id,
        tenant,
        code,
        agreementId,
        input.periodStart(),
        input.periodEnd(),
        head.get("currency", String.class),
        nz(input.note()),
        actorId);
    for (var row : pending) {
      sql.execute(
          "insert into consignment_settlement_line(id,tenant_id,settlement_id,agreement_line_id,"
              + "sale_id,quantity,unit_price) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenant,
          id,
          row.get("agreement_line_id", UUID.class),
          row.get("id", UUID.class),
          row.get("sold_quantity", BigDecimal.class),
          row.get("settlement_price", BigDecimal.class));
    }
    audit(
        tenant,
        agreementId,
        "SETTLEMENT_TRIGGERED",
        "按已售事实出结算触发单 " + code + "（" + pending.size() + " 条销售事实）",
        actorId);
    return settlementDetail(id);
  }

  /** 结算单列表（真分页；合计由 V71 的延迟约束触发器保证 == Σ 明细）。 */
  public ConsignmentSettlementPage settlements(
      UUID agreementId, String status, int pageNo, int size) {
    var tenant = tenant();
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("s.tenant_id = ?");
    binds.add(tenant);
    if (agreementId != null) {
      where.add("s.agreement_id = ?");
      binds.add(agreementId);
    }
    if (notBlank(status)) {
      where.add("s.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from consignment_settlement s" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            SETTLEMENT_COLUMNS
                + " from "
                + SETTLEMENT_FROM
                + sqlWhere
                + " order by s.created_at desc, s.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new ConsignmentSettlementPage(
        rows.stream().map(ConsignmentService::settlement).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  /** 结算单详情：每行都指回触发它的那条销售事实，「应付从哪来」必须一眼可追。 */
  public ConsignmentSettlementDetail settlementDetail(UUID settlementId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            SETTLEMENT_COLUMNS + " from " + SETTLEMENT_FROM + " where s.tenant_id=? and s.id=?",
            tenant,
            settlementId);
    if (head == null) {
      throw notFound("结算触发单", settlementId);
    }
    var lines =
        sql.fetch(
            "select sl.id, sl.agreement_line_id, sl.sale_id, sl.quantity, sl.unit_price, sl.amount,"
                + " s.sale_ref, s.occurred_at, k.code as sku_code, k.name as sku_name"
                + " from consignment_settlement_line sl"
                + " join consignment_sale s on s.tenant_id = sl.tenant_id and s.id = sl.sale_id"
                + " join consignment_agreement_line l on l.tenant_id = sl.tenant_id and l.id = sl.agreement_line_id"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
                + " where sl.tenant_id = ? and sl.settlement_id = ? order by s.occurred_at, sl.id",
            tenant,
            settlementId);
    return new ConsignmentSettlementDetail(
        settlement(head),
        lines.stream()
            .map(
                r ->
                    new ConsignmentSettlementLine(
                        r.get("id", UUID.class),
                        r.get("agreement_line_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("sale_id", UUID.class),
                        r.get("sale_ref", String.class),
                        scale6(r.get("quantity", BigDecimal.class)),
                        scale6(r.get("unit_price", BigDecimal.class)),
                        amount(r.get("amount", BigDecimal.class)),
                        r.get("occurred_at", OffsetDateTime.class)))
            .toList());
  }

  /**
   * 确认结算触发单 → 这笔应付正式交给 12 应付（写 reference_id 定位键）。
   *
   * <p>确认后单据与明细被 V71 冻结：应付既已出去，再改它等于篡改对外账。
   */
  @Transactional
  public ConsignmentSettlement confirmSettlement(
      UUID settlementId, ConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementIdOf(tenant, settlementId));
    OffsetDateTime now = OffsetDateTime.now();
    int changed =
        sql.execute(
            "update consignment_settlement set status='CONFIRMED', confirmed_at=?::timestamptz,"
                + " confirmed_by_user_id=?, note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
                + " where tenant_id=? and id=? and status='OPEN'",
            now,
            actorId,
            note(input),
            now,
            tenant,
            settlementId);
    if (changed == 0) {
      throw conflict("只有待确认（OPEN）的结算触发单能确认；已确认或已作废的单据不可再动。");
    }
    var referenceId = "CS-" + settlementId;
    sql.execute(
        "update consignment_settlement set reference_id=? where tenant_id=? and id=?",
        referenceId,
        tenant,
        settlementId);
    var agreementId = agreementIdOf(tenant, settlementId);
    audit(
        tenant,
        agreementId,
        "SETTLEMENT_CONFIRMED",
        "确认结算触发单，应付交 12 应付（ref " + referenceId + "）：" + note(input),
        actorId);
    return settlementDetail(settlementId).settlement();
  }

  // ============================================================ 未售退货与期末清理

  /**
   * 建未售退货 / 期末清理单（草稿）。
   *
   * <p><b>{@code disposition} 决定这张单会不会产生应付</b>，两者的财务后果相反：
   *
   * <ul>
   *   <li>RETURN_TO_SUPPLIER —— 未售货退回供应商，<b>不产生任何应付</b>。
   *   <li>CONVERT_TO_PURCHASE —— 期末我方买断未售货，<b>才</b>产生应付， 且必须协议上 {@code endTreatment =
   *       CONVERT_TO_PURCHASE}。
   * </ul>
   *
   * <p>草稿期不改动任何数量：真正动数量在 {@link #executeReturn}。这样「先拟单、后执行」 与其他单据一致，也不会让还没确认的草稿把寄售剩余量提前吃掉。
   */
  @Transactional
  public ConsignmentReturnDetail createReturn(
      UUID agreementId, ConsignmentReturnInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if ("CLOSED".equals(head.get("status", String.class))) {
      throw conflict("协议已关闭，不能再建退货/清理单。");
    }
    if (input.lines() == null || input.lines().isEmpty()) {
      throw bad("退货/清理单必须至少有一行未售数量。");
    }
    if (input.lines().size() > MAX_RETURN_LINES) {
      throw bad("单张退货/清理单的行数超过上限 " + MAX_RETURN_LINES + " 行。");
    }
    String kind = requireEnum(input.kind(), ConsignmentReturnKind.class, "退货性质");
    String disposition =
        requireEnum(
            input.disposition(), List.of("RETURN_TO_SUPPLIER", "CONVERT_TO_PURCHASE"), "清理方式");
    String agreed = head.get("end_treatment", String.class);
    if ("PERIOD_END_CLEANUP".equals(kind) && !agreed.equals(disposition)) {
      throw conflict(
          "协议约定的期末处理是 "
              + agreed
              + "，与本次清理方式 "
              + disposition
              + " 不一致。期末清理必须按协议走 —— 否则「协议写退回、账上却转采购」就是凭空多一笔应付。");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into consignment_return(id,tenant_id,return_code,agreement_id,kind,disposition,status,"
            + "source_system,note,created_by_user_id,created_at) "
            + "select ?,?,?,?,?,?,'DRAFT','WAREHOUSE',?,?,t.at from t",
        OffsetDateTime.now(),
        id,
        tenant,
        nextCode("CR"),
        agreementId,
        kind,
        disposition,
        nz(input.note()),
        actorId);
    for (var line : input.lines()) {
      if (line.agreementLineId() == null) {
        throw bad("每一行都必须指明是哪条寄售协议明细行 agreementLineId。");
      }
      BigDecimal qty = requirePositive(line.quantity(), "退货数量");
      var price =
          sql.fetchOne(
                  "select settlement_price from consignment_agreement_line where tenant_id=? and id=? and agreement_id=?",
                  tenant,
                  line.agreementLineId(),
                  agreementId)
              .get("settlement_price", BigDecimal.class);
      sql.execute(
          "insert into consignment_return_line(id,tenant_id,return_id,agreement_line_id,quantity,"
              + "settlement_price) values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenant,
          id,
          line.agreementLineId(),
          qty,
          price);
    }
    audit(
        tenant,
        agreementId,
        "RETURN_CREATED",
        "建立" + ("PERIOD_END_CLEANUP".equals(kind) ? "期末清理" : "未售退货") + "单（" + disposition + "）",
        actorId);
    return returnDetail(id);
  }

  /**
   * 执行退货/清理单：DRAFT → EXECUTED。**这一步才真正改动寄售数量。**
   *
   * <p>RETURN_TO_SUPPLIER 把数量记进 {@code returned_quantity}，CONVERT_TO_PURCHASE 记进 {@code
   * converted_quantity}；两者都从剩余量里扣（剩余是生成列，守恒式自动成立）。 「退回」与「转采购」的差别就在这里：退回的货我方不再持有，转采购的货我方买断了。
   */
  @Transactional
  public ConsignmentReturn executeReturn(UUID returnId, ConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            "select * from consignment_return where tenant_id=? and id=?", tenant, returnId);
    if (head == null) {
      throw notFound("退货/清理单", returnId);
    }
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw conflict(
          "只有草稿状态的退货/清理单能执行；当前状态 " + head.get("status", String.class) + "，数量早已动过，不能重复执行。");
    }
    UUID agreementId = head.get("agreement_id", UUID.class);
    requireHead(tenant, agreementId);
    String disposition = head.get("disposition", String.class);
    var lines =
        sql.fetch(
            "select * from consignment_return_line where tenant_id=? and return_id=? order by id",
            tenant,
            returnId);
    for (var line : lines) {
      UUID lineId = line.get("agreement_line_id", UUID.class);
      BigDecimal qty = line.get("quantity", BigDecimal.class);
      var current =
          sql.fetchOne(
              "select remaining_quantity from consignment_agreement_line where tenant_id=? and id=?",
              tenant,
              lineId);
      BigDecimal remaining = current.get("remaining_quantity", BigDecimal.class);
      if (qty.compareTo(remaining) > 0) {
        throw conflict(
            "该行未售数量 "
                + qty.toPlainString()
                + " 超过当前剩余 "
                + remaining.toPlainString()
                + "，已阻止执行。期间这批货可能已经卖出一部分：请重新按当前剩余拟单。");
      }
      String column =
          "RETURN_TO_SUPPLIER".equals(disposition) ? "returned_quantity" : "converted_quantity";
      sql.execute(
          "update consignment_agreement_line set "
              + column
              + " = "
              + column
              + " + ?,"
              + " version = version + 1, updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          qty,
          OffsetDateTime.now(),
          tenant,
          lineId);
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update consignment_return set status='EXECUTED', executed_at=?::timestamptz,"
            + " executed_by_user_id=?, reference_id=?, note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status='DRAFT'",
        now,
        actorId,
        "CR-" + returnId,
        note(input),
        now,
        tenant,
        returnId);
    audit(
        tenant,
        agreementId,
        "RETURN_EXECUTED",
        "执行"
            + ("RETURN_TO_SUPPLIER".equals(disposition) ? "未售退回供应商（不产生应付）" : "期末转我方采购（产生应付）")
            + "，共 "
            + head.get("total_quantity", BigDecimal.class).toPlainString()
            + " 件",
        actorId);
    return returnDetail(returnId).returnOrder();
  }

  /** 作废退货/清理单：DRAFT → CANCELLED。草稿没有动过数量，作废是无损的。 */
  @Transactional
  public ConsignmentReturn cancelReturn(UUID returnId, ConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            "select * from consignment_return where tenant_id=? and id=?", tenant, returnId);
    if (head == null) {
      throw notFound("退货/清理单", returnId);
    }
    sql.execute(
        "update consignment_return set status='CANCELLED', cancelled_at=?::timestamptz,"
            + " note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status='DRAFT'",
        OffsetDateTime.now(),
        note(input),
        OffsetDateTime.now(),
        tenant,
        returnId);
    audit(
        tenant,
        head.get("agreement_id", UUID.class),
        "RETURN_CANCELLED",
        "作废退货/清理单（草稿未动数量）：" + note(input),
        actorId);
    return returnDetail(returnId).returnOrder();
  }

  /** 退货/清理单列表（真分页）。 */
  public ConsignmentReturnPage returns(UUID agreementId, String status, int pageNo, int size) {
    var tenant = tenant();
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("r.tenant_id = ?");
    binds.add(tenant);
    if (agreementId != null) {
      where.add("r.agreement_id = ?");
      binds.add(agreementId);
    }
    if (notBlank(status)) {
      where.add("r.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from consignment_return r" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            RETURN_COLUMNS
                + " from "
                + RETURN_FROM
                + sqlWhere
                + " order by r.created_at desc, r.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new ConsignmentReturnPage(
        rows.stream().map(ConsignmentService::returnOrder).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  /** 退货/清理单详情 = 头部 + 全部明细。 */
  public ConsignmentReturnDetail returnDetail(UUID returnId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            RETURN_COLUMNS + " from " + RETURN_FROM + " where r.tenant_id=? and r.id=?",
            tenant,
            returnId);
    if (head == null) {
      throw notFound("退货/清理单", returnId);
    }
    var lines =
        sql.fetch(
            "select rl.id, rl.agreement_line_id, rl.quantity, rl.settlement_price, rl.payable_amount,"
                + " k.code as sku_code, k.name as sku_name"
                + " from consignment_return_line rl"
                + " join consignment_agreement_line l on l.tenant_id = rl.tenant_id and l.id = rl.agreement_line_id"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
                + " where rl.tenant_id = ? and rl.return_id = ? order by rl.id",
            tenant,
            returnId);
    return new ConsignmentReturnDetail(
        returnOrder(head),
        lines.stream()
            .map(
                r ->
                    new ConsignmentReturnLine(
                        r.get("id", UUID.class),
                        r.get("agreement_line_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        scale6(r.get("quantity", BigDecimal.class)),
                        scale6(r.get("settlement_price", BigDecimal.class)),
                        amount(r.get("payable_amount", BigDecimal.class))))
            .toList());
  }

  /**
   * 按协议的结算周期算出**当前默认结算区间**。
   *
   * <p>它是真算出来的（WEEKLY 取周一至周日、SEMI_MONTHLY 按上下半月、MONTHLY 按自然月）， 不是写死的字符串。ON_DEMAND
   * 没有固定周期，给出今天到明天的一日窗口作为可编辑起点。
   */
  public SettlementWindow settlementWindow(UUID agreementId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    String cycle = head.get("settlement_cycle", String.class);
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    var start = today;
    var end = today;
    switch (cycle) {
      case "WEEKLY" -> {
        start = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        end = start.plusDays(6);
      }
      case "SEMI_MONTHLY" -> {
        if (today.getDayOfMonth() <= 15) {
          start = today.withDayOfMonth(1);
          end = start.withDayOfMonth(15);
        } else {
          start = today.withDayOfMonth(16);
          end = today.withDayOfMonth(today.lengthOfMonth());
        }
      }
      case "MONTHLY" -> {
        start = today.withDayOfMonth(1);
        end = today.withDayOfMonth(today.lengthOfMonth());
      }
      default -> end = today.plusDays(1);
    }
    LocalDate todayValue = today;
    return new SettlementWindow(
        agreementId,
        cycle,
        label(ConsignmentSettlementCycle.values(), cycle),
        start,
        end,
        todayValue);
  }

  /** 审计时间线（append-only）。 */
  public List<ConsignmentAuditEntry> auditTrail(UUID agreementId, Integer limit) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    int n = Math.min(MAX_AUDIT_ROWS, Math.max(1, limit == null || limit <= 0 ? 200 : limit));
    return sql
        .fetch(
            "select id, event_type, detail, actor_user_id, occurred_at from consignment_audit"
                + " where tenant_id = ? and agreement_id = ? order by occurred_at desc, id desc limit ?",
            tenant,
            agreementId,
            n)
        .stream()
        .map(
            r ->
                new ConsignmentAuditEntry(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("detail", String.class),
                    r.get("actor_user_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ============================================================ 内部

  private ConsignmentAgreementDetail detail(UUID agreementId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            AGREEMENT_COLUMNS + " from " + AGREEMENT_FROM + " where a.tenant_id=? and a.id=?",
            tenant,
            agreementId);
    if (head == null) {
      throw notFound("寄售协议", agreementId);
    }
    var lines =
        sql.fetch(
            "select l.*, k.code as sku_code, k.name as sku_name, w.code as warehouse_code,"
                + " coalesce(un.qty,0) as sales_in_period,"
                + " coalesce(st.qty,0) as settled_quantity"
                + " from consignment_agreement_line l"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
                + " join warehouse w on w.tenant_id = l.tenant_id and w.id = l.warehouse_id"
                + " left join lateral (select coalesce(sum(s.sold_quantity),0) as qty"
                + "     from consignment_sale s where s.tenant_id = l.tenant_id"
                + "       and s.agreement_line_id = l.id) un on true"
                + " left join lateral (select coalesce(sum(sl.quantity),0) as qty"
                + "     from consignment_settlement_line sl"
                + "     join consignment_settlement s2 on s2.tenant_id = sl.tenant_id"
                + "       and s2.id = sl.settlement_id"
                + "     where sl.tenant_id = l.tenant_id and sl.agreement_line_id = l.id"
                + "       and s2.status = 'CONFIRMED') st on true"
                + " where l.tenant_id = ? and l.agreement_id = ? order by k.code, l.id",
            tenant,
            agreementId);
    return new ConsignmentAgreementDetail(
        agreement(head),
        lines.stream().map(ConsignmentService::line).toList(),
        auditTrail(agreementId, null));
  }

  private void insertLine(UUID tenant, UUID agreementId, ConsignmentLineInput line) {
    if (line.skuId() == null) {
      throw bad("寄售明细行必须指明 SKU。");
    }
    if (line.warehouseId() == null) {
      throw bad("寄售明细行必须指明存放仓库。");
    }
    BigDecimal qty = requirePositive(line.consignedQuantity(), "寄售量");
    if (line.settlementPrice() == null || line.settlementPrice().signum() < 0) {
      throw bad("结算价不能为空或负数：应付 = 已售数量 × 结算价，这个价就是应付的唯一单价来源。");
    }
    requireRef(line.skuId(), "SKU");
    requireRef(line.warehouseId(), "仓库");
    try {
      sql.execute(
          "insert into consignment_agreement_line(id,tenant_id,agreement_id,sku_id,warehouse_id,"
              + "consigned_quantity,settlement_price) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenant,
          agreementId,
          line.skuId(),
          line.warehouseId(),
          qty,
          line.settlementPrice());
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict("该协议下这个 SKU 在这个仓库已有一行（同一 SKU + 仓库唯一）。" + "两行会让「这个 SKU 还剩多少」出现第二个答案。");
    }
  }

  private void audit(UUID tenant, UUID agreementId, String event, String detail, UUID actorId) {
    // t CTE 让 ?::timestamptz 成为第 1 个绑定：不带 cast 的 OffsetDateTime 会被 jOOQ
    // 当成 varchar 发过去，PG 直接报「expression is of type character varying」。
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into consignment_audit(id,tenant_id,agreement_id,event_type,detail,actor_user_id,occurred_at)"
            + " select ?,?,?,?,?,?,t.at from t",
        OffsetDateTime.now(),
        UUID.randomUUID(),
        tenant,
        agreementId,
        event,
        detail,
        actorId);
  }

  private org.jooq.Record requireHead(UUID tenant, UUID agreementId) {
    var head =
        sql.fetchOne(
            "select * from consignment_agreement where tenant_id=? and id=?", tenant, agreementId);
    if (head == null) {
      throw notFound("寄售协议", agreementId);
    }
    return head;
  }

  private UUID agreementIdOf(UUID tenant, UUID settlementId) {
    var row =
        sql.fetchOne(
            "select agreement_id from consignment_settlement where tenant_id=? and id=?",
            tenant,
            settlementId);
    if (row == null) {
      throw notFound("结算触发单", settlementId);
    }
    return row.get("agreement_id", UUID.class);
  }

  private static final String SALE_FROM =
      " consignment_sale s"
          + " join consignment_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
          + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
          + " left join consignment_settlement_line sl on sl.tenant_id = s.tenant_id and sl.sale_id = s.id"
          + " left join consignment_settlement st on st.tenant_id = sl.tenant_id and st.id = sl.settlement_id";

  private ConsignmentSale sale(UUID tenant, UUID saleId) {
    return sql
        .fetch(
            SALE_COLUMNS + " from" + SALE_FROM + " where s.tenant_id = ? and s.id = ?",
            tenant,
            saleId)
        .stream()
        .findFirst()
        .map(r -> saleRecord(r))
        .orElseThrow(() -> notFound("销售事实", saleId));
  }

  private static ConsignmentSale saleRecord(org.jooq.Record r) {
    var settlementId = r.get("settlement_id", UUID.class);
    return new ConsignmentSale(
        r.get("id", UUID.class),
        r.get("agreement_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        scale6(r.get("sold_quantity", BigDecimal.class)),
        r.get("sale_price", BigDecimal.class) == null
            ? null
            : scale6(r.get("sale_price", BigDecimal.class)),
        r.get("currency", String.class),
        r.get("sale_ref", String.class),
        r.get("source_system", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        settlementId == null,
        settlementId,
        r.get("settlement_code", String.class),
        r.get("recorded_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ConsignmentLine line(org.jooq.Record r) {
    BigDecimal sold = scale6(r.get("sold_quantity", BigDecimal.class));
    BigDecimal settled = scale6(r.get("settled_quantity", BigDecimal.class));
    BigDecimal unsettleable = sold.subtract(settled);
    return new ConsignmentLine(
        r.get("id", UUID.class),
        r.get("agreement_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        scale6(r.get("consigned_quantity", BigDecimal.class)),
        scale6(r.get("settlement_price", BigDecimal.class)),
        sold,
        scale6(r.get("returned_quantity", BigDecimal.class)),
        scale6(r.get("converted_quantity", BigDecimal.class)),
        scale6(r.get("remaining_quantity", BigDecimal.class)),
        r.get("stock_owner_type", String.class),
        r.get("counts_as_owned_asset", Boolean.class),
        scale6(r.get("sales_in_period", BigDecimal.class)),
        settled,
        unsettleable.max(ZERO));
  }

  private static ConsignmentAgreement agreement(org.jooq.Record r) {
    String mode = r.get("mode", String.class);
    String cycle = r.get("settlement_cycle", String.class);
    String treatment = r.get("end_treatment", String.class);
    String status = r.get("status", String.class);
    return new ConsignmentAgreement(
        r.get("id", UUID.class),
        r.get("agreement_code", String.class),
        mode,
        label(ConsignmentMode.values(), mode),
        r.get("supplier_party_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("currency", String.class),
        cycle,
        label(ConsignmentSettlementCycle.values(), cycle),
        treatment,
        label(ConsignmentEndTreatment.values(), treatment),
        r.get("start_date", LocalDate.class),
        r.get("end_date", LocalDate.class),
        status,
        label(ConsignmentAgreementStatus.values(), status),
        r.get("line_count", Long.class),
        scale6(r.get("total_consigned", BigDecimal.class)),
        scale6(r.get("total_sold", BigDecimal.class)),
        scale6(r.get("total_returned", BigDecimal.class)),
        scale6(r.get("total_converted", BigDecimal.class)),
        scale6(r.get("total_remaining", BigDecimal.class)),
        amount(r.get("settled_amount", BigDecimal.class)),
        amount(r.get("unsettleable_amount", BigDecimal.class)),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("activated_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class));
  }

  private static ConsignmentSettlement settlement(org.jooq.Record r) {
    String status = r.get("status", String.class);
    return new ConsignmentSettlement(
        r.get("id", UUID.class),
        r.get("settlement_code", String.class),
        r.get("agreement_id", UUID.class),
        r.get("agreement_code", String.class),
        r.get("supplier_name", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        status,
        label(ConsignmentSettlementStatus.values(), status),
        r.get("line_count", Long.class),
        scale6(r.get("total_quantity", BigDecimal.class)),
        amount(r.get("total_amount", BigDecimal.class)),
        r.get("source_system", String.class),
        r.get("reference_id", String.class),
        r.get("triggered_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ConsignmentReturn returnOrder(org.jooq.Record r) {
    String kind = r.get("kind", String.class);
    String disposition = r.get("disposition", String.class);
    String status = r.get("status", String.class);
    return new ConsignmentReturn(
        r.get("id", UUID.class),
        r.get("return_code", String.class),
        r.get("agreement_id", UUID.class),
        r.get("agreement_code", String.class),
        r.get("supplier_name", String.class),
        kind,
        label(ConsignmentReturnKind.values(), kind),
        disposition,
        label(ConsignmentEndTreatment.values(), disposition),
        status,
        label(ConsignmentReturnStatus.values(), status),
        r.get("line_count", Long.class),
        scale6(r.get("total_quantity", BigDecimal.class)),
        amount(r.get("total_payable_amount", BigDecimal.class)),
        r.get("source_system", String.class),
        r.get("reference_id", String.class),
        r.get("executed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ============================================================ SQL 片段

  private static final String AGREEMENT_COLUMNS =
      "select a.id, a.agreement_code, a.mode, a.supplier_party_id, a.supplier_code, a.supplier_name,"
          + " a.legal_entity_id, a.currency, a.settlement_cycle, a.end_treatment, a.start_date,"
          + " a.end_date, a.status, a.note, a.created_by_user_id, a.created_at, a.activated_at,"
          + " a.closed_at, le.code as legal_entity_code,"
          + " coalesce(agg.line_count,0) as line_count, coalesce(agg.consigned,0) as total_consigned,"
          + " coalesce(agg.sold,0) as total_sold, coalesce(agg.returned,0) as total_returned,"
          + " coalesce(agg.converted,0) as total_converted, coalesce(agg.remaining,0) as total_remaining,"
          + " coalesce(st.amt,0) as settled_amount, coalesce(un.amt,0) as unsettleable_amount";

  private static final String AGREEMENT_FROM =
      " consignment_agreement a"
          + " join legal_entity_ref le on le.tenant_id = a.tenant_id and le.id = a.legal_entity_id"
          + " left join lateral (select count(*) as line_count,"
          + "   coalesce(sum(l.consigned_quantity),0) as consigned,"
          + "   coalesce(sum(l.sold_quantity),0) as sold,"
          + "   coalesce(sum(l.returned_quantity),0) as returned,"
          + "   coalesce(sum(l.converted_quantity),0) as converted,"
          + "   coalesce(sum(l.remaining_quantity),0) as remaining"
          + "   from consignment_agreement_line l"
          + "   where l.tenant_id = a.tenant_id and l.agreement_id = a.id) agg on true"
          + " left join lateral (select coalesce(sum(s.total_amount),0) as amt"
          + "   from consignment_settlement s where s.tenant_id = a.tenant_id"
          + "   and s.agreement_id = a.id and s.status = 'CONFIRMED') st on true"
          + " left join lateral (select coalesce(sum(sa.sold_quantity * li.settlement_price),0) as amt"
          + "   from consignment_sale sa"
          + "   join consignment_agreement_line li on li.tenant_id = sa.tenant_id"
          + "     and li.id = sa.agreement_line_id"
          + "   where li.agreement_id = a.id and sa.tenant_id = a.tenant_id"
          + "   and not exists (select 1 from consignment_settlement_line sl"
          + "     where sl.tenant_id = sa.tenant_id and sl.sale_id = sa.id)) un on true";

  private static final String SALE_COLUMNS =
      "select s.id, s.agreement_line_id, s.sold_quantity, s.sale_price, s.currency, s.sale_ref,"
          + " s.source_system, s.occurred_at, s.recorded_by_user_id, s.created_at,"
          + " k.code as sku_code, k.name as sku_name, sl.settlement_id, st.settlement_code";

  private static final String SETTLEMENT_COLUMNS =
      "select s.id, s.settlement_code, s.agreement_id, s.period_start, s.period_end, s.currency,"
          + " s.status, s.total_quantity, s.total_amount, s.source_system, s.reference_id,"
          + " s.triggered_at, s.confirmed_at, s.note, s.created_by_user_id, s.created_at,"
          + " a.agreement_code, a.supplier_name,"
          + " (select count(*) from consignment_settlement_line sl"
          + "  where sl.tenant_id = s.tenant_id and sl.settlement_id = s.id) as line_count";

  private static final String SETTLEMENT_FROM =
      " consignment_settlement s join consignment_agreement a"
          + " on a.tenant_id = s.tenant_id and a.id = s.agreement_id";

  private static final String RETURN_COLUMNS =
      "select r.id, r.return_code, r.agreement_id, r.kind, r.disposition, r.status, r.total_quantity,"
          + " r.total_payable_amount, r.source_system, r.reference_id, r.executed_at, r.note,"
          + " r.created_by_user_id, r.created_at, a.agreement_code, a.supplier_name,"
          + " (select count(*) from consignment_return_line rl"
          + "  where rl.tenant_id = r.tenant_id and rl.return_id = r.id) as line_count";

  private static final String RETURN_FROM =
      " consignment_return r join consignment_agreement a"
          + " on a.tenant_id = r.tenant_id and a.id = r.agreement_id";

  // ============================================================ 工具

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /** 单号 = 前缀-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼： 纯文本语句里的 {@code ::text} 这类表达式 jOOQ 解析器吃不准，拆开就没有歧义。 */
  private String nextCode(String prefix) {
    long seq;
    if ("CG".equals(prefix)) {
      seq = sql.fetchOne("select nextval('consignment_agreement_code_seq')").get(0, Long.class);
    } else if ("ST".equals(prefix)) {
      seq = sql.fetchOne("select nextval('consignment_settlement_code_seq')").get(0, Long.class);
    } else {
      seq = sql.fetchOne("select nextval('consignment_return_code_seq')").get(0, Long.class);
    }
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    return prefix + "-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
  }

  private static String label(Enum<?>[] values, String name) {
    for (var v : values) {
      if (v.name().equals(name) && v instanceof ConsignmentMode m) return m.label();
      if (v.name().equals(name) && v instanceof ConsignmentSettlementCycle c) return c.label();
      if (v.name().equals(name) && v instanceof ConsignmentEndTreatment t) return t.label();
      if (v.name().equals(name) && v instanceof ConsignmentAgreementStatus s) return s.label();
      if (v.name().equals(name) && v instanceof ConsignmentSettlementStatus s) return s.label();
      if (v.name().equals(name) && v instanceof ConsignmentReturnStatus s) return s.label();
      if (v.name().equals(name) && v instanceof ConsignmentReturnKind k) return k.label();
    }
    return name;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String note(ConsignmentNoteInput input) {
    return input == null || input.note() == null ? "" : input.note().trim();
  }

  private static BigDecimal scale6(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, RoundingMode.HALF_UP);
  }

  private static BigDecimal amount(BigDecimal v) {
    return v == null ? ZERO_AMOUNT : v.setScale(6, RoundingMode.HALF_UP);
  }

  private static BigDecimal requirePositive(BigDecimal v, String what) {
    if (v == null || v.signum() <= 0) {
      throw bad(what + "必须大于 0。");
    }
    return v.setScale(6, RoundingMode.HALF_UP);
  }

  private static void requireText(String v, String what) {
    if (!notBlank(v)) {
      throw bad(what + "不能为空。");
    }
  }

  private static void requireRef(UUID v, String what) {
    if (v == null) {
      throw bad(what + "不能为空。");
    }
  }

  private static String requireEnum(String value, Class<? extends Enum<?>> type, String what) {
    if (notBlank(value)) {
      for (var c : type.getEnumConstants()) {
        if (c.name().equals(value.trim().toUpperCase(Locale.ROOT))) return c.name();
      }
    }
    throw bad(what + "只能是：" + List.of(type.getEnumConstants()) + "，收到：" + value);
  }

  private static String requireEnum(String value, List<String> allowed, String what) {
    if (notBlank(value)) {
      String v = value.trim().toUpperCase(Locale.ROOT);
      if (allowed.contains(v)) return v;
    }
    throw bad(what + "只能是：" + allowed + "，收到：" + value);
  }

  private static String requireCurrency(String value) {
    String v = notBlank(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    if (!v.matches("^[A-Z]{3}$")) {
      throw bad("币种必须是三位大写字母（如 USD / CNY），收到：" + value + "。结算价没有币种就是一个无意义的数字。");
    }
    return v;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
