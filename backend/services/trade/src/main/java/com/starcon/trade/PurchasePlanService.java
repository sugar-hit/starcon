package com.starcon.trade;

import static com.starcon.trade.PurchasePlanModels.*;
import static com.starcon.trade.PurchasePlanRules.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.3 采购计划。
 *
 * <p>蓝图原话：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
 * 支持调整、锁定、导出给采购员。」输入「补货建议集合；计划周期；大促日历；锁库策略」。
 * 输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、优先级）」。
 *
 * <p><b>本模块只做一件事：把「该进多少货」变成一份有凭据、可调整、可锁定、可导出的采购计划。</b>
 *
 * <p><b>九条实现纪律</b>：
 *
 * <ol>
 *   <li><b>只读 6.2.1.2，绝不写它</b>：补货建议读 {@code replenishment_suggestion}
 *       （那张表的写入所有权是 6.2.1.2）。本模块只 SELECT。</li>
 *   <li><b>大促与日常在结构上分开</b>：{@code plan_type} 是列不是标签；DB 层 CHECK 钉死
 *       「CAMPAIGN 必须带 campaign_id、ROUTINE 必须不带」。</li>
 *   <li><b>备货量分开算</b>：大促 = 建议量 × 销量系数 × 备货系数；日常 = 建议量本身。
 *       算式原文写进 {@code quantity_basis}，不靠复算。</li>
 *   <li><b>汇总视图分开看</b>：{@code GET /trade/purchase-plans/summary} 返回日常段与大促段
 *       <b>两个独立块</b>，不给一个把两者加在一起的总数字。</li>
 *   <li><b>锁定走 warehouse 的写端口</b>：预占事实的写入所有者是 warehouse（7.2.1.1），
 *       本服务只记「已提交请求 / 已确认 / 失败」这些属于计划自己的事实，
 *       <b>不建影子表假装锁住了</b>。端口未接线 ⇒ 显式报「未接线」。</li>
 *   <li><b>重新汇总不覆盖已调整的行</b>：同一个 SKU 已在计划里 ⇒ 跳过并计数。
 *       悄悄覆盖会把运营刚调好的数量抹回去。</li>
 *   <li><b>周期边界由服务端裁决</b>：月度计划的周期必须是某月 1 号到月末，季度同理。
 *       对不上就 400，不悄悄改写。</li>
 *   <li><b>留痕 append-only</b>：行调整与锁定请求各有一张不可改写的留痕表。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@code OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}；业务日历（到货日 / 周期边界）用 {@code date}。</li>
 * </ol>
 */
@Service
public class PurchasePlanService {

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QUANTITY_SCALE = 6;

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 单次汇总最多读多少条补货建议。
   *
   * <p>超出就<b>如实报出截断</b>（结果 truncated=true + 说明），而不是静默少汇一批 ——
   * 一个被悄悄截断的计划比一个明确报出截断的计划危险得多。
   */
  static final int MAX_AGGREGATE_SUGGESTIONS = 2000;

  private final DSLContext sql;
  private final PurchasePlanWarehousePort warehouses;
  private final PurchasePlanLockPort lockPort;

  public PurchasePlanService(
      DSLContext sql, PurchasePlanWarehousePort warehouses, PurchasePlanLockPort lockPort) {
    this.sql = sql;
    this.warehouses = warehouses;
    this.lockPort = lockPort;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>输入分属三个地方：补货建议在 6.2.1.2（trade，同库）、目标仓与法人在 warehouse、
   * 锁库的物理事实在 warehouse 的库存预占。哪一个接通了必须由接口自己报出。
   */
  public PurchasePlanCapabilities capabilities() {
    return new PurchasePlanCapabilities(
        true,
        "6.2.1.2 补货建议（trade 服务，集成头已交付）",
        "补货建议读 6.2.1.2 的 replenishment_suggestion / replenishment_suggestion_batch ——"
            + "本模块只 SELECT，不改它一个字。汇总时只有 advice='REPLENISH' 且未被忽略的建议"
            + "才会变成计划行：「答不出来」的建议（缺日均销量 / 缺报价 / 缺箱规）与「无需补货」"
            + "都不能变成要下单的量。已在上游被调整过的建议，用**处置后的数量**作为汇总基数。",
        true,
        "目标仓与法人经 warehouse 的授权只读接口（GET /warehouse/warehouses、"
            + "GET /warehouse/legal-entities）读取。本服务不建仓库 / 法人的影子表 —— "
            + "仓库与法人是 warehouse 服务的事实。法人是必填项：锁库预占要落法人货权。",
        lockPort.available(),
        lockPort.unavailableNote(),
        true,
        "大促日历**由本模块自带**（purchase_plan_campaign 表，租户可维护）。"
            + "实查集成头：backend/services/*/src/main/java/** 下没有任何 Campaign / Promo 类，"
            + "大促日历在本仓没有任何已交付来源 —— 与其假装有个上游服务，不如把这段档期与系数"
            + "作为租户自己维护的真实输入落在本服务里。",
        false,
        "6.2.2.1 采购申请**未交付** ⇒ 本模块只产出计划行与锁定留痕，不代它建申请单、不代它走审批。",
        "日常计划量 = 6.2.1.2 的建议量（上游已调整过的，取处置后的数量）。"
            + "大促计划量 = 建议量 × 大促的预期销量系数 × 备货系数（6 位 HALF_UP）。"
            + "两条算式的原文都写在计划行的 quantity_basis 里。",
        "日常期望到货日 = 6.2.1.2 给出的预计到货日（取不到时退回计划周期起点）。"
            + "大促期望到货日 = 大促开始日 − 档期登记的提前天数（大促备货量大、周期长，必须更早到）。",
        "本模块的职责边界：建计划 → 从补货建议汇总成计划行 → 调整计划行 → 锁定（走 warehouse 预占）"
            + "→ 导出 CSV 给采购员。大促与日常**分开**建、分开算、分开看。");
  }

  // ================================================================ 参照下拉

  /** 建计划需要的目标仓 / 法人 / 大促下拉项。 */
  public PurchasePlanOptions options(String bearer) {
    List<PurchasePlanWarehouseOption> warehouseRows = new ArrayList<>();
    List<PurchasePlanLegalEntityOption> legalRows = new ArrayList<>();
    String warehouseNote = "";
    try {
      for (PurchasePlanWarehousePort.WarehouseView view : warehouses.warehouses(bearer)) {
        warehouseRows.add(
            new PurchasePlanWarehouseOption(view.id(), view.code(), view.name(), view.status()));
      }
      for (PurchasePlanWarehousePort.LegalEntityView view : warehouses.legalEntities(bearer)) {
        legalRows.add(
            new PurchasePlanLegalEntityOption(view.id(), view.code(), view.name(), view.status()));
      }
    } catch (ResponseStatusException e) {
      // 下拉取不到时**如实报出**，但不让整个页面挂掉：清单与已建计划照常可读。
      warehouseNote = e.getReason() == null ? "仓库参照暂时读不到" : e.getReason();
    }
    List<PurchasePlanCampaignOption> campaignRows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,code,name,start_date,end_date from purchase_plan_campaign"
                + " where tenant_id = ?::uuid and status <> 'CLOSED' order by start_date desc,id limit 200",
            currentTenant())) {
      campaignRows.add(
          new PurchasePlanCampaignOption(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("start_date", LocalDate.class),
              row.get("end_date", LocalDate.class)));
    }
    return new PurchasePlanOptions(
        warehouseRows,
        legalRows,
        campaignRows,
        warehouseNote,
        warehouseNote.isBlank()
            ? "目标仓与法人来自 warehouse 的授权只读接口；大促来自本模块自带的日历。"
            : "目标仓 / 法人下拉当前不可用（见 warehouseNote）—— 界面必须显示这条说明，"
                + "不能让运营从一个空下拉里以为「本租户没有仓」。");
  }

  // ================================================================ 大促日历

  /** 登记一条大促档期。 */
  @Transactional
  public PurchasePlanCampaignRow createCampaign(PurchasePlanCampaignInput input, UUID actorId) {
    if (input.endDate().isBefore(input.startDate())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "大促档期的结束日不能早于开始日");
    }
    UUID tenantId = currentTenant();
    var exists =
        sql.fetchOne(
            "select id from purchase_plan_campaign where tenant_id = ?::uuid and code = ?::varchar",
            tenantId,
            input.code().trim());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "大促档期编码已存在：" + input.code().trim());
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into purchase_plan_campaign(id,tenant_id,code,name,shop_code,start_date,end_date,"
            + " sales_multiplier,stock_factor,arrival_lead_days,status,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::date,?::date,"
            + " ?::numeric,?::numeric,?::int,?::varchar,?,?::uuid,?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        input.code().trim(),
        input.name().trim(),
        blankToNull(input.shopCode()),
        input.startDate(),
        input.endDate(),
        factor(input.salesMultiplier()),
        factor(input.stockFactor()),
        input.arrivalLeadDays() == null ? 0 : input.arrivalLeadDays(),
        (input.status() == null ? PurchasePlanCampaignStatus.PLANNED : input.status()).name(),
        input.note() == null ? "" : input.note(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
    return campaignRow(id);
  }

  /** 更新一条大促档期。 */
  @Transactional
  public PurchasePlanCampaignRow updateCampaign(
      UUID id, PurchasePlanCampaignInput input, UUID actorId) {
    if (input.endDate().isBefore(input.startDate())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "大促档期的结束日不能早于开始日");
    }
    UUID tenantId = currentTenant();
    var existing =
        sql.fetchOne(
            "select id from purchase_plan_campaign where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "大促档期不存在");
    }
    var clash =
        sql.fetchOne(
            "select id from purchase_plan_campaign where tenant_id = ?::uuid and code = ?::varchar"
                + " and id <> ?::uuid",
            tenantId,
            input.code().trim(),
            id);
    if (clash != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "大促档期编码已存在：" + input.code().trim());
    }
    sql.execute(
        "update purchase_plan_campaign set code = ?::varchar, name = ?::varchar, shop_code = ?::varchar,"
            + " start_date = ?::date, end_date = ?::date, sales_multiplier = ?::numeric,"
            + " stock_factor = ?::numeric, arrival_lead_days = ?::int, status = ?::varchar,"
            + " note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.code().trim(),
        input.name().trim(),
        blankToNull(input.shopCode()),
        input.startDate(),
        input.endDate(),
        factor(input.salesMultiplier()),
        factor(input.stockFactor()),
        input.arrivalLeadDays() == null ? 0 : input.arrivalLeadDays(),
        (input.status() == null ? PurchasePlanCampaignStatus.PLANNED : input.status()).name(),
        input.note() == null ? "" : input.note(),
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        id);
    return campaignRow(id);
  }

  /**
   * 删除一条大促档期。
   *
   * <p><b>被计划引用时删不掉</b>（FK ON DELETE RESTRICT）：大促档期是计划的一部分依据，
   * 删掉它会让「这份大促计划是按哪场大促算的」永远答不回来。
   */
  @Transactional
  public void deleteCampaign(UUID id) {
    UUID tenantId = currentTenant();
    var existing =
        sql.fetchOne(
            "select id from purchase_plan_campaign where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "大促档期不存在");
    }
    var used =
        sql.fetchOne(
            "select count(*) from purchase_plan where tenant_id = ?::uuid and campaign_id = ?::uuid",
            tenantId,
            id);
    long usedCount = used == null ? 0L : used.get(0, Long.class);
    if (usedCount > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条大促档期已被 " + usedCount + " 张采购计划引用，删不掉 —— 先把那些计划处理完。"
              + "改成「已结束（CLOSED）」即可停止它再被选中。");
    }
    sql.execute(
        "delete from purchase_plan_campaign where tenant_id = ?::uuid and id = ?::uuid", tenantId, id);
  }

  /** 大促日历列表（真分页 + 真筛选）。 */
  public PurchasePlanCampaignPage pageCampaigns(
      String code, String status, String shopCode, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (code != null && !code.isBlank()) {
      where.add("code ilike ?::varchar");
      binds.add("%" + code.trim() + "%");
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?::varchar");
      binds.add(status.trim());
    }
    if (shopCode != null && !shopCode.isBlank()) {
      where.add("shop_code = ?::varchar");
      binds.add(shopCode.trim());
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from purchase_plan_campaign where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,code,name,shop_code,start_date,end_date,sales_multiplier,stock_factor,"
                + " arrival_lead_days,status,note,created_at,updated_at from purchase_plan_campaign"
                + " where "
                + clause
                + " order by start_date desc,id desc limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<PurchasePlanCampaignRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(campaignItem(row));
    }
    return new PurchasePlanCampaignPage(items, total, pageNo, pageSize);
  }

  private PurchasePlanCampaignRow campaignRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,shop_code,start_date,end_date,sales_multiplier,stock_factor,"
                + " arrival_lead_days,status,note,created_at,updated_at from purchase_plan_campaign"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "大促档期不存在");
    }
    return campaignItem(row);
  }

  private static PurchasePlanCampaignRow campaignItem(Record row) {
    String status = row.get("status", String.class);
    return new PurchasePlanCampaignRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("shop_code", String.class),
        row.get("start_date", LocalDate.class),
        row.get("end_date", LocalDate.class),
        row.get("sales_multiplier", BigDecimal.class),
        row.get("stock_factor", BigDecimal.class),
        row.get("arrival_lead_days", Integer.class),
        status,
        campaignStatusLabel(status),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 建计划

  /** 建一张采购计划（**不含计划行** —— 计划行由汇总或后续维护产生）。 */
  @Transactional
  public PurchasePlanRow createPlan(PurchasePlanCreateInput input, UUID actorId, String bearer) {
    validatePeriod(input.periodType(), input.periodStart(), input.periodEnd());
    UUID tenantId = currentTenant();

    // 大促：必须带 campaign_id，且周期必须覆盖整个档期。
    CampaignFact campaign = null;
    if (input.planType().isCampaign()) {
      if (input.campaignId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "大促备货计划必须指定大促档期 —— 备货量与到货日都按它算");
      }
      campaign = campaignFact(input.campaignId(), tenantId);
      validateCampaignCovered(
          input.periodStart(), input.periodEnd(), campaign.startDate(), campaign.endDate());
    } else if (input.campaignId() != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "日常补货计划不能挂大促档期 —— 大促与日常必须分开");
    }

    // 目标仓与法人：经 warehouse 的授权只读接口解析（不建影子表）。
    String warehouseNote = "";
    String warehouseCode = "";
    String warehouseName = "";
    try {
      PurchasePlanWarehousePort.WarehouseView view = findWarehouse(input.targetWarehouseId(), bearer);
      if (view != null) {
        warehouseCode = view.code();
        warehouseName = view.name();
      } else {
        warehouseNote = "目标仓 " + input.targetWarehouseId() + " 在仓库服务里查不到（可能已归档）";
      }
    } catch (ResponseStatusException e) {
      warehouseNote = e.getReason() == null ? "仓库服务暂时不可达，目标仓名称未解析" : e.getReason();
    }
    String entityCode = "";
    String entityName = "";
    try {
      PurchasePlanWarehousePort.LegalEntityView view = findLegalEntity(input.legalEntityId(), bearer);
      if (view != null) {
        entityCode = view.code();
        entityName = view.name();
      } else {
        warehouseNote =
            appendNote(
                warehouseNote, "法人 " + input.legalEntityId() + " 在仓库服务里查不到（可能已停用）");
      }
    } catch (ResponseStatusException e) {
      warehouseNote =
          appendNote(
              warehouseNote,
              e.getReason() == null ? "仓库服务暂时不可达，法人名称未解析" : e.getReason());
    }

    UUID id = UUID.randomUUID();
    String planNo =
        "PP-"
            + input.periodStart().toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String note = input.note() == null ? "" : input.note();
    if (!warehouseNote.isBlank()) {
      note = appendNote(note, warehouseNote);
    }
    sql.execute(
        "insert into purchase_plan(id,tenant_id,plan_no,plan_type,campaign_id,period_type,"
            + " period_start,period_end,target_warehouse_id,target_warehouse_code,"
            + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,status,"
            + " priority,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::date,?::date,"
            + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?,"
            + " ?::uuid,?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        planNo,
        input.planType().name(),
        campaign == null ? null : campaign.id(),
        input.periodType().name(),
        input.periodStart(),
        input.periodEnd(),
        input.targetWarehouseId(),
        warehouseCode,
        warehouseName,
        input.legalEntityId(),
        entityCode,
        entityName,
        PurchasePlanStatus.DRAFT.name(),
        (input.priority() == null ? PurchasePlanPriority.NORMAL : input.priority()).name(),
        note,
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
    return planRow(id);
  }

  /** 确认一张计划（草稿 → 已确认）。**没有计划行不许确认** —— 空计划确认了也没法导出。 */
  @Transactional
  public PurchasePlanRow confirmPlan(UUID planId, UUID actorId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    String status = plan.get("status", String.class);
    if (!PurchasePlanStatus.DRAFT.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿计划可以确认，当前状态：" + planStatusLabel(status));
    }
    long lineCount = lineCount(planId, tenantId);
    if (lineCount == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这张计划还没有计划行 —— 先从补货建议汇总出计划行再确认");
    }
    sql.execute(
        "update purchase_plan set status = ?::varchar, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchasePlanStatus.CONFIRMED.name(),
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        planId);
    return planRow(planId);
  }

  // ================================================================ 从补货建议汇总成计划行

  /**
   * 从 6.2.1.2 的补货建议汇总成计划行。
   *
   * <p><b>只汇「真建议」</b>：{@code advice='REPLENISH'} 且未被忽略的建议。
   * 「缺日均销量 / 缺报价 / 缺箱规 / 无需补货」的建议都<b>不能</b>变成要下单的量 ——
   * 把「算不出来」变成一个采购单上的数字，比不给数字更糟。
   *
   * <p><b>不覆盖已存在的行</b>：同一个 SKU 已经在计划里 ⇒ 跳过并计数。
   * 悄悄覆盖会把运营刚调好的数量抹回去。
   */
  @Transactional
  public PurchasePlanAggregateResult aggregate(
      UUID planId, PurchasePlanAggregateInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    String planType = plan.get("plan_type", String.class);
    PurchasePlanType type = PurchasePlanType.valueOf(planType);
    LocalDate periodStart = plan.get("period_start", LocalDate.class);
    UUID targetWarehouseId = plan.get("target_warehouse_id", UUID.class);
    String warehouseCode = plan.get("target_warehouse_code", String.class);
    String warehouseName = plan.get("target_warehouse_name", String.class);

    CampaignFact campaign =
        type.isCampaign()
            ? campaignFact(plan.get("campaign_id", UUID.class), tenantId)
            : null;

    // 来源批次必须存在（6.2.1.2 的表，读不改）。
    var batch =
        sql.fetchOne(
            "select batch_no,as_of_date from replenishment_suggestion_batch"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            input.sourceBatchId());
    if (batch == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "补货建议批次不存在 —— 请先在 6.2.1.2 跑一次计算，再把它的批次汇总成采购计划");
    }
    String batchNo = batch.get("batch_no", String.class);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("batch_id = ?::uuid");
    binds.add(input.sourceBatchId());
    where.add("advice = ?::varchar");
    binds.add("REPLENISH");
    where.add("status <> ?::varchar");
    binds.add("IGNORED");
    if (input.skuCode() != null && !input.skuCode().isBlank()) {
      where.add("sku_code ilike ?::varchar");
      binds.add("%" + input.skuCode().trim() + "%");
    }
    if (input.keyword() != null && !input.keyword().isBlank()) {
      where.add("(sku_code ilike ?::varchar or sku_name ilike ?::varchar)");
      binds.add("%" + input.keyword().trim() + "%");
      binds.add("%" + input.keyword().trim() + "%");
    }
    String clause = String.join(" and ", where);
    long candidateCount =
        sql.fetchOne("select count(*) from replenishment_suggestion where " + clause, binds.toArray())
            .get(0, Long.class);
    boolean truncated = candidateCount > MAX_AGGREGATE_SUGGESTIONS;

    // 同一批次里**不能**变成计划行的建议数（答不出来的 / 无需补货的 / 被忽略的）——
    // 如实报出来，别让运营以为「这一批建议全都进计划了」。
    long notReplenishCount =
        sql.fetchOne(
                "select count(*) from replenishment_suggestion where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid and (advice <> ?::varchar or status = ?::varchar)",
                tenantId,
                input.sourceBatchId(),
                "REPLENISH",
                "IGNORED")
            .get(0, Long.class);

    List<Record> suggestions =
        sql.fetch(
            "select id,sku_id,sku_code,sku_name,suggested_quantity,decided_quantity,"
                + " expected_arrival_date,status from replenishment_suggestion where "
                + clause
                + " order by sort_rank,sku_code limit ?",
            append(binds, MAX_AGGREGATE_SUGGESTIONS).toArray());

    PurchasePlanPriority priority =
        input.defaultPriority() == null ? PurchasePlanPriority.NORMAL : input.defaultPriority();
    int created = 0;
    int skippedExisting = 0;
    int sortRank = (int) lineCount(planId, tenantId);
    for (Record suggestion : suggestions) {
      UUID skuId = suggestion.get("sku_id", UUID.class);
      var exists =
          sql.fetchOne(
              "select id from purchase_plan_line where tenant_id = ?::uuid and plan_id = ?::uuid"
                  + " and sku_id = ?::uuid",
              tenantId,
              planId,
              skuId);
      if (exists != null) {
        skippedExisting++;
        continue;
      }
      BigDecimal suggested = suggestion.get("suggested_quantity", BigDecimal.class);
      BigDecimal decided = suggestion.get("decided_quantity", BigDecimal.class);
      BigDecimal base = decided != null ? decided : suggested;
      if (base == null || base.signum() <= 0) {
        // 上游给不出量（缺报价 / 缺箱规）⇒ 不汇。给不出量不是 0，也不是「按 1 件算」。
        skippedExisting++;
        continue;
      }
      BigDecimal planQuantity =
          planQuantity(base, type, campaign == null ? null : campaign.salesMultiplier(),
              campaign == null ? null : campaign.stockFactor());
      LocalDate arrival =
          expectedArrivalDate(
              suggestion.get("expected_arrival_date", LocalDate.class),
              periodStart,
              type,
              campaign == null ? null : campaign.startDate(),
              campaign == null ? 0 : campaign.arrivalLeadDays());
      String basis =
          quantityBasis(
              sourceText(suggested, decided, batchNo),
              planQuantity,
              type,
              campaign == null ? null : campaign.salesMultiplier(),
              campaign == null ? null : campaign.stockFactor(),
              campaign == null ? null : campaign.code());
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into purchase_plan_line(id,tenant_id,plan_id,sku_id,sku_code,sku_name,"
              + " source_suggestion_id,source_batch_id,suggested_quantity,plan_quantity,quantity_basis,"
              + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
              + " expected_arrival_date,priority,status,lock_status,note,sort_rank,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,"
              + " ?::numeric,?::numeric,?::varchar,?::uuid,?::varchar,?::varchar,?::date,?::varchar,"
              + " ?::varchar,?::varchar,'',?::int,?::timestamptz,?::timestamptz)",
          lineId,
          tenantId,
          planId,
          skuId,
          suggestion.get("sku_code", String.class),
          suggestion.get("sku_name", String.class),
          suggestion.get("id", UUID.class),
          input.sourceBatchId(),
          suggested,
          planQuantity,
          basis,
          targetWarehouseId,
          warehouseCode,
          warehouseName,
          arrival,
          priority.name(),
          PurchasePlanLineStatus.PLANNED.name(),
          PurchasePlanLockStatus.NOT_REQUESTED.name(),
          sortRank,
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC));
      created++;
      sortRank++;
    }

    sql.execute(
        "update purchase_plan set updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        planId);

    List<String> notes = new ArrayList<>();
    if (truncated) {
      notes.add(
          "可汇总的建议有 "
              + candidateCount
              + " 条，超过单次上限 "
              + MAX_AGGREGATE_SUGGESTIONS
              + " 条 ⇒ 本次**只汇了前 "
              + MAX_AGGREGATE_SUGGESTIONS
              + " 条**，剩下的需要再跑一次（已汇过的 SKU 会被跳过）");
    }
    if (skippedExisting > 0) {
      notes.add(
          "有 "
              + skippedExisting
              + " 条没有汇进来：要么该 SKU 已经在本计划里（不覆盖已调整的行），"
              + "要么上游给不出可下单的数量");
    }
    if (notReplenishCount > 0) {
      notes.add(
          "同一批次里还有 "
              + notReplenishCount
              + " 条建议**不能**变成计划行（答不出来 / 无需补货 / 已被忽略）—— "
              + "把它们变成要下单的量，比不给数量更糟");
    }
    if (campaign != null) {
      notes.add(
          "大促计划：计划量 = 建议量 × 销量系数 "
              + factor(campaign.salesMultiplier()).stripTrailingZeros().toPlainString()
              + " × 备货系数 "
              + factor(campaign.stockFactor()).stripTrailingZeros().toPlainString()
              + "；期望到货日 = 大促开始日 "
              + campaign.startDate()
              + " − 提前 "
              + campaign.arrivalLeadDays()
              + " 天");
    }
    String note = notes.isEmpty() ? "" : String.join("；", notes);
    return new PurchasePlanAggregateResult(
        planId,
        plan.get("plan_no", String.class),
        input.sourceBatchId(),
        batchNo,
        (int) Math.min(candidateCount, Integer.MAX_VALUE),
        created,
        skippedExisting,
        (int) Math.min(notReplenishCount, Integer.MAX_VALUE),
        truncated,
        truncated
            ? "可汇总的建议超过单次上限 " + MAX_AGGREGATE_SUGGESTIONS + " 条，本次清单不完整"
            : "",
        note);
  }

  /** 汇总基数的说明原文。 */
  private static String sourceText(BigDecimal suggested, BigDecimal decided, String batchNo) {
    String source = "6.2.1.2 补货建议批次 " + batchNo;
    if (suggested == null) {
      return "建议量 无数据（" + source + "）";
    }
    String base = "建议量 " + suggested.stripTrailingZeros().toPlainString();
    if (decided != null && decided.compareTo(suggested) != 0) {
      base = base + "（上游处置后调整为 " + decided.stripTrailingZeros().toPlainString() + "）";
    }
    return base + "（" + source + "）";
  }

  // ================================================================ 计划行调整

  /**
   * 调整一个计划行。
   *
   * <p><b>已提交或已确认锁定的行不许调整</b>：预占的数量是按当时的计划量登记的，
   * 改了计划量却不改预占，账上就出现了两个不一致的数。
   */
  @Transactional
  public PurchasePlanLineRow adjustLine(
      UUID planId, UUID lineId, PurchasePlanLineAdjustInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var line = requireLine(planId, lineId, tenantId);
    String lockStatus = line.get("lock_status", String.class);
    if (PurchasePlanLockStatus.REQUESTED.name().equals(lockStatus)
        || PurchasePlanLockStatus.CONFIRMED.name().equals(lockStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这一行已经发起过锁定（当前锁定状态："
              + lockStatusLabel(lockStatus)
              + "）—— 计划量改了却不动预占，账上会出现两个不一致的数。"
              + "请先在仓库侧释放该预占再调整。");
    }
    boolean changed = false;
    // 每次改动记一条（字段, 改前, 改后）—— 留痕是给人看的，不能靠复现字符串。
    List<String[]> changes = new ArrayList<>();

    if (input.planQuantity() != null) {
      BigDecimal before = line.get("plan_quantity", BigDecimal.class);
      if (before == null || before.compareTo(input.planQuantity()) != 0) {
        changes.add(
            new String[] {"plan_quantity", plain(before), plain(input.planQuantity())});
        sql.execute(
            "update purchase_plan_line set plan_quantity = ?::numeric, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.planQuantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
            OffsetDateTime.now(ZoneOffset.UTC),
            tenantId,
            lineId);
        changed = true;
      }
    }
    if (input.expectedArrivalDate() != null) {
      LocalDate before = line.get("expected_arrival_date", LocalDate.class);
      if (!input.expectedArrivalDate().equals(before)) {
        changes.add(
            new String[] {
              "expected_arrival_date", before == null ? "" : before.toString(),
              input.expectedArrivalDate().toString()
            });
        sql.execute(
            "update purchase_plan_line set expected_arrival_date = ?::date, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.expectedArrivalDate(),
            OffsetDateTime.now(ZoneOffset.UTC),
            tenantId,
            lineId);
        changed = true;
      }
    }
    if (input.priority() != null) {
      String before = line.get("priority", String.class);
      if (!input.priority().name().equals(before)) {
        changes.add(
            new String[] {"priority", before, input.priority().name()});
        sql.execute(
            "update purchase_plan_line set priority = ?::varchar, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.priority().name(),
            OffsetDateTime.now(ZoneOffset.UTC),
            tenantId,
            lineId);
        changed = true;
      }
    }
    if (input.targetWarehouseId() != null) {
      UUID before = line.get("target_warehouse_id", UUID.class);
      if (!input.targetWarehouseId().equals(before)) {
        String code = "";
        String name = "";
        String nameNote = "";
        try {
          PurchasePlanWarehousePort.WarehouseView view =
              findWarehouse(input.targetWarehouseId(), bearer);
          if (view != null) {
            code = view.code();
            name = view.name();
          } else {
            nameNote = "（仓库服务里查不到这个仓，名称留空）";
          }
        } catch (ResponseStatusException e) {
          // 名称解析不到就留空 + 如实记下原因，不编造。
          nameNote = "（仓库服务暂时不可达，名称未解析）";
        }
        changes.add(
            new String[] {
              "target_warehouse_id", before == null ? "" : before.toString(),
              input.targetWarehouseId().toString() + nameNote
            });
        sql.execute(
            "update purchase_plan_line set target_warehouse_id = ?::uuid, target_warehouse_code = ?::varchar,"
                + " target_warehouse_name = ?::varchar, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.targetWarehouseId(),
            code,
            name,
            OffsetDateTime.now(ZoneOffset.UTC),
            tenantId,
            lineId);
        changed = true;
      }
    }
    if (input.note() != null) {
      String before = line.get("note", String.class);
      if (!input.note().equals(before)) {
        changes.add(new String[] {"note", before == null ? "" : before, input.note()});
        sql.execute(
            "update purchase_plan_line set note = ?::varchar, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.note(),
            OffsetDateTime.now(ZoneOffset.UTC),
            tenantId,
            lineId);
        changed = true;
      }
    }
    if (!changed) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "没有给出任何要改的字段 —— 一次空的调整不是调整");
    }

    // 留痕（append-only）：每个改动的字段一行。
    for (String[] change : changes) {
      sql.execute(
          "insert into purchase_plan_adjustment(id,tenant_id,plan_line_id,field,before_value,"
              + " after_value,reason,adjusted_by,adjusted_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::uuid,"
              + " ?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          lineId,
          change[0],
          change[1],
          change[2],
          input.reason(),
          actorId,
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC));
    }

    // 状态与锁定状态复位：调整过的行回到「已调整 / 未请求」，失败原因清掉（留痕表里还留着）。
    sql.execute(
        "update purchase_plan_line set status = ?::varchar, lock_status = ?::varchar,"
            + " lock_failure_reason = '', lock_reservation_no = null, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchasePlanLineStatus.ADJUSTED.name(),
        PurchasePlanLockStatus.NOT_REQUESTED.name(),
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        lineId);
    sql.execute(
        "update purchase_plan set updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        planId);
    return lineRow(planId, lineId);
  }

  // ================================================================ 锁定

  /**
   * 锁定一个计划行 —— 经 warehouse 的授权写接口**真登记一笔库存预占**。
   *
   * <p>失败时**如实记成锁定失败**并把原文带给调用方；绝不降级成「先记着稍后补锁」。
   */
  @Transactional
  public PurchasePlanLockResult lockLine(
      UUID planId, UUID lineId, PurchasePlanLockInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var line = requireLine(planId, lineId, tenantId);
    var plan = requirePlan(planId, tenantId);
    String lockStatus = line.get("lock_status", String.class);
    if (PurchasePlanLockStatus.CONFIRMED.name().equals(lockStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这一行已经锁定了，不重复发起预占");
    }
    if (PurchasePlanLockStatus.REQUESTED.name().equals(lockStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这一行的锁定请求已经发出去了，等 warehouse 确认");
    }

    // 锁库策略：当前生效的那条（日常 / 大促各一条）。
    LockPolicy policy = effectiveLockPolicy(plan.get("plan_type", String.class), tenantId);
    if (!policy.lockEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "按当前生效的锁库策略，"
              + planTypeLabel(plan.get("plan_type", String.class))
              + "计划不允许锁库"
              + (policy.holdDays() == 0 ? "" : "（持有 " + policy.holdDays() + " 天）")
              + " —— 先去改锁库策略，或直接导出计划给采购员");
    }
    String planStatus = plan.get("status", String.class);
    if (policy.requireConfirmed() && !PurchasePlanStatus.CONFIRMED.name().equals(planStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "按当前生效的锁库策略，只有**已确认**的计划才能锁库，当前计划状态："
              + planStatusLabel(planStatus));
    }

    UUID skuId = line.get("sku_id", UUID.class);
    BigDecimal quantity = line.get("plan_quantity", BigDecimal.class);
    UUID legalEntityId = plan.get("legal_entity_id", UUID.class);
    String planNo = plan.get("plan_no", String.class);
    String skuCode = line.get("sku_code", String.class);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expiresAt = policy.holdDays() > 0 ? now.plusDays(policy.holdDays()) : null;

    // ---- 1. 先记「已提交锁定请求」（append-only 留痕 + 行状态） ----
    sql.execute(
        "insert into purchase_plan_lock_log(id,tenant_id,plan_line_id,action,reservation_id,"
            + " reservation_no,message,requested_by,requested_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,null,null,?::varchar,?::uuid,?::timestamptz,"
            + " ?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        lineId,
        PurchasePlanLockAction.REQUEST.name(),
        "向 warehouse 7.2.1.1 发起库存预占（持有 "
            + policy.holdDays()
            + " 天）："
            + quantity
            + " × "
            + skuCode,
        actorId,
        now,
        now);
    sql.execute(
        "update purchase_plan_line set status = ?::varchar, lock_status = ?::varchar,"
            + " lock_requested_at = ?::timestamptz, lock_failure_reason = '', updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchasePlanLineStatus.LOCK_REQUESTED.name(),
        PurchasePlanLockStatus.REQUESTED.name(),
        now,
        now,
        tenantId,
        lineId);

    // ---- 2. 真去 warehouse 登记预占 ----
    PurchasePlanLockPort.LockOutcome outcome;
    try {
      outcome =
          lockPort.reserve(
              new PurchasePlanLockPort.LockRequest(
                  "TRADE",
                  "OTHER_HOLD",
                  lineId,
                  planNo + "/" + skuCode,
                  legalEntityId,
                  skuId,
                  quantity,
                  now,
                  expiresAt,
                  input.note() == null || input.note().isBlank()
                      ? ("采购计划 " + planNo + " 锁库")
                      : input.note()),
              bearer);
    } catch (ResponseStatusException e) {
      // **不抛**：整条链路跑在一个事务里（租户上下文绑定就在事务内），抛出去会回滚掉
      // 「已提交请求」与失败原因 —— 那样就变成「仓库拒了但本地一点痕迹都没有」。
      // 这里如实记成失败并返回，由控制器在事务提交之后翻成对应的 HTTP 状态码。
      String reason = e.getReason() == null ? "锁定失败" : e.getReason();
      sql.execute(
          "insert into purchase_plan_lock_log(id,tenant_id,plan_line_id,action,reservation_id,"
              + " reservation_no,message,requested_by,requested_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::varchar,null,null,?::varchar,?::uuid,?::timestamptz,"
              + " ?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          lineId,
          PurchasePlanLockAction.FAIL.name(),
          reason,
          actorId,
          now,
          now);
      sql.execute(
          "update purchase_plan_line set status = ?::varchar, lock_status = ?::varchar,"
              + " lock_failure_reason = ?::varchar, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchasePlanLineStatus.LOCK_FAILED.name(),
          PurchasePlanLockStatus.FAILED.name(),
          reason,
          now,
          tenantId,
          lineId);
      refreshPlanStatus(planId, tenantId);
      return new PurchasePlanLockResult(
          lineId,
          PurchasePlanLockStatus.FAILED.name(),
          PurchasePlanLockStatus.FAILED.label(),
          PurchasePlanLineStatus.LOCK_FAILED.name(),
          null,
          null,
          now,
          null,
          true,
          e.getStatusCode().value(),
          reason);
    }

    // ---- 3. warehouse 确认了 ⇒ 才写 CONFIRMED ----
    OffsetDateTime confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into purchase_plan_lock_log(id,tenant_id,plan_line_id,action,reservation_id,"
            + " reservation_no,message,requested_by,requested_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::uuid,"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        lineId,
        PurchasePlanLockAction.CONFIRM.name(),
        outcome.id(),
        outcome.reservationNo(),
        "warehouse 7.2.1.1 已登记预占 "
            + outcome.reservationNo()
            + "（状态 "
            + outcome.status()
            + "）",
        actorId,
        confirmedAt,
        confirmedAt);
    sql.execute(
        "update purchase_plan_line set status = ?::varchar, lock_status = ?::varchar,"
            + " lock_reservation_id = ?::uuid, lock_reservation_no = ?::varchar,"
            + " lock_confirmed_at = ?::timestamptz, lock_failure_reason = '', updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchasePlanLineStatus.LOCKED.name(),
        PurchasePlanLockStatus.CONFIRMED.name(),
        outcome.id(),
        outcome.reservationNo(),
        confirmedAt,
        confirmedAt,
        tenantId,
        lineId);
    refreshPlanStatus(planId, tenantId);
    return new PurchasePlanLockResult(
        lineId,
        PurchasePlanLockStatus.CONFIRMED.name(),
        PurchasePlanLockStatus.CONFIRMED.label(),
        PurchasePlanLineStatus.LOCKED.name(),
        outcome.id(),
        outcome.reservationNo(),
        now,
        confirmedAt,
        false,
        null,
        "warehouse 7.2.1.1 已登记预占 " + outcome.reservationNo());
  }

  /** 锁库策略：当前生效的那条（没有登记过 ⇒ 一条**保守的默认**：允许锁、持有 30 天、要求已确认）。 */
  private LockPolicy effectiveLockPolicy(String planType, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select lock_enabled,hold_days,require_confirmed from purchase_plan_lock_policy"
                + " where tenant_id = ?::uuid and plan_type = ?::varchar and effective_to is null",
            tenantId,
            planType);
    if (row == null) {
      return new LockPolicy(true, 30, true, false);
    }
    return new LockPolicy(
        Boolean.TRUE.equals(row.get("lock_enabled", Boolean.class)),
        row.get("hold_days", Integer.class),
        Boolean.TRUE.equals(row.get("require_confirmed", Boolean.class)),
        true);
  }

  /** 计划状态由行状态派生，避免「头说已锁定、行说没锁」这种自相矛盾。 */
  private void refreshPlanStatus(UUID planId, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where lock_status = 'CONFIRMED') as locked,"
                + " count(*) filter (where lock_status = 'FAILED') as failed"
                + " from purchase_plan_line where tenant_id = ?::uuid and plan_id = ?::uuid",
            tenantId,
            planId);
    int total = row == null ? 0 : row.get("total", Integer.class);
    int locked = row == null ? 0 : row.get("locked", Integer.class);
    int failed = row == null ? 0 : row.get("failed", Integer.class);
    var plan =
        sql.fetchOne(
            "select status from purchase_plan where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            planId);
    if (plan == null) {
      return;
    }
    PurchasePlanStatus current = PurchasePlanStatus.valueOf(plan.get("status", String.class));
    PurchasePlanStatus derived = deriveStatus(current, locked, failed, total);
    if (derived != current) {
      sql.execute(
          "update purchase_plan set status = ?::varchar, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          derived.name(),
          OffsetDateTime.now(ZoneOffset.UTC),
          tenantId,
          planId);
    }
  }

  // ================================================================ 锁库策略

  /** 登记一条锁库策略（版本化：同一类型已有未收口的策略时，新策略把旧的**收口**）。 */
  @Transactional
  public PurchasePlanLockPolicyRow upsertLockPolicy(
      PurchasePlanLockPolicyInput input, UUID actorId) {
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效日不能早于生效起点");
    }
    UUID tenantId = currentTenant();
    var open =
        sql.fetchOne(
            "select id,effective_from from purchase_plan_lock_policy"
                + " where tenant_id = ?::uuid and plan_type = ?::varchar and effective_to is null",
            tenantId,
            input.planType().name());
    if (open != null) {
      LocalDate previousFrom = open.get("effective_from", LocalDate.class);
      if (!input.effectiveFrom().isAfter(previousFrom)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "同一计划类型已有一条从 "
                + previousFrom
                + " 起生效的锁库策略。新策略的生效起点必须晚于它 —— 否则同一时间点会有两套锁库规则。");
      }
      sql.execute(
          "update purchase_plan_lock_policy set effective_to = ?::date"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          input.effectiveFrom(),
          tenantId,
          open.get("id", UUID.class));
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into purchase_plan_lock_policy(id,tenant_id,plan_type,lock_enabled,hold_days,"
            + " require_confirmed,effective_from,effective_to,note,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::boolean,?::int,?::boolean,?::date,?::date,?,"
            + " ?::uuid,?::timestamptz)",
        id,
        tenantId,
        input.planType().name(),
        Boolean.TRUE.equals(input.lockEnabled()),
        input.holdDays(),
        Boolean.TRUE.equals(input.requireConfirmed()),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return lockPolicyRow(id);
  }

  /** 锁库策略列表（真分页）。 */
  public PurchasePlanLockPolicyPage pageLockPolicies(String planType, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (planType != null && !planType.isBlank()) {
      where.add("plan_type = ?::varchar");
      binds.add(planType.trim());
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from purchase_plan_lock_policy where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,plan_type,lock_enabled,hold_days,require_confirmed,effective_from,"
                + " effective_to,note,created_at from purchase_plan_lock_policy where "
                + clause
                + " order by plan_type,effective_from desc,id desc limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<PurchasePlanLockPolicyRow> items = new ArrayList<>();
    for (Record row : rows) {
      String type = row.get("plan_type", String.class);
      LocalDate effectiveTo = row.get("effective_to", LocalDate.class);
      items.add(
          new PurchasePlanLockPolicyRow(
              row.get("id", UUID.class),
              type,
              planTypeLabel(type),
              Boolean.TRUE.equals(row.get("lock_enabled", Boolean.class)),
              row.get("hold_days", Integer.class),
              Boolean.TRUE.equals(row.get("require_confirmed", Boolean.class)),
              row.get("effective_from", LocalDate.class),
              effectiveTo,
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class),
              effectiveTo == null));
    }
    return new PurchasePlanLockPolicyPage(items, total, pageNo, pageSize);
  }

  private PurchasePlanLockPolicyRow lockPolicyRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,plan_type,lock_enabled,hold_days,require_confirmed,effective_from,"
                + " effective_to,note,created_at from purchase_plan_lock_policy"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "锁库策略不存在");
    }
    String type = row.get("plan_type", String.class);
    LocalDate effectiveTo = row.get("effective_to", LocalDate.class);
    return new PurchasePlanLockPolicyRow(
        row.get("id", UUID.class),
        type,
        planTypeLabel(type),
        Boolean.TRUE.equals(row.get("lock_enabled", Boolean.class)),
        row.get("hold_days", Integer.class),
        Boolean.TRUE.equals(row.get("require_confirmed", Boolean.class)),
        row.get("effective_from", LocalDate.class),
        effectiveTo,
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        effectiveTo == null);
  }

  // ================================================================ 读取

  /** 采购计划列表（真分页 + 真筛选 + 真排序）。 */
  public PurchasePlanPage pagePlans(PurchasePlanQuery query) {
    int pageNo = Math.max(0, query.page());
    int pageSize = pageSize(query.size());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("p.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (query.planType() != null && !query.planType().isBlank()) {
      where.add("p.plan_type = ?::varchar");
      binds.add(query.planType().trim());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("p.status = ?::varchar");
      binds.add(query.status().trim());
    }
    if (query.campaignId() != null) {
      where.add("p.campaign_id = ?::uuid");
      binds.add(query.campaignId());
    }
    if (query.targetWarehouseId() != null) {
      where.add("p.target_warehouse_id = ?::uuid");
      binds.add(query.targetWarehouseId());
    }
    if (query.priority() != null && !query.priority().isBlank()) {
      where.add("p.priority = ?::varchar");
      binds.add(query.priority().trim());
    }
    if (query.periodStart() != null) {
      where.add("p.period_end >= ?::date");
      binds.add(query.periodStart());
    }
    if (query.periodEnd() != null) {
      where.add("p.period_start <= ?::date");
      binds.add(query.periodEnd());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(p.plan_no ilike ?::varchar or p.note ilike ?::varchar)");
      binds.add("%" + query.keyword().trim() + "%");
      binds.add("%" + query.keyword().trim() + "%");
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from purchase_plan p where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select p.id,p.plan_no,p.plan_type,p.campaign_id,p.period_type,p.period_start,p.period_end,"
                + " p.target_warehouse_id,p.target_warehouse_code,p.target_warehouse_name,"
                + " p.legal_entity_id,p.legal_entity_code,p.legal_entity_name,p.status,p.priority,p.note,"
                + " p.created_by,p.created_at,p.updated_at,"
                + " c.code as campaign_code,c.name as campaign_name,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id) as line_count,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id and l.lock_status = 'CONFIRMED') as locked_line_count,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id and l.lock_status = 'FAILED') as failed_line_count,"
                + " (select coalesce(sum(l.plan_quantity),0) from purchase_plan_line l"
                + "    where l.tenant_id = p.tenant_id and l.plan_id = p.id) as total_plan_quantity,"
                + " (select coalesce(sum(l.suggested_quantity),0) from purchase_plan_line l"
                + "    where l.tenant_id = p.tenant_id and l.plan_id = p.id) as total_suggested_quantity"
                + " from purchase_plan p"
                + " left join purchase_plan_campaign c on c.tenant_id = p.tenant_id and c.id = p.campaign_id"
                + " where "
                + clause
                + " order by "
                + planSortClause(query.sort())
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<PurchasePlanRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(planItem(row));
    }
    String note =
        items.isEmpty()
            ? "这一页没有符合条件的采购计划 —— 可能是筛选条件太窄，也可能是还没建过计划"
            : "";
    return new PurchasePlanPage(items, total, pageNo, pageSize, note);
  }

  /**
   * 排序白名单：调用方不能塞任意 SQL 进来。
   *
   * <p>优先级是<b>枚举</b>而不是数字，字典序不等于业务序（LOW 会排在 NORMAL 前面），
   * 所以用 CASE 显式映射成业务序。
   */
  private static String planSortClause(String sort) {
    String key = sort == null ? "" : sort.trim().toLowerCase();
    return switch (key) {
      case "created" -> "p.created_at desc, p.id desc";
      case "priority" ->
          "case p.priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end,"
              + " p.period_start desc, p.id desc";
      case "status" -> "p.status asc, p.period_start desc, p.id desc";
      case "type" -> "p.plan_type asc, p.period_start desc, p.id desc";
      default -> "p.period_start desc, p.created_at desc, p.id desc";
    };
  }

  /** 计划详情：头 + 计划行。 */
  public PurchasePlanDetail planDetail(UUID planId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    List<PurchasePlanLineRow> lines = new ArrayList<>();
    for (Record row : lineRecords(planId, tenantId, null, null, null, null)) {
      lines.add(lineItem(row, plan.get("plan_no", String.class), plan.get("plan_type", String.class)));
    }
    return new PurchasePlanDetail(
        planItem(plan), lines, lines.isEmpty() ? "这张计划还没有计划行" : "");
  }

  /** 计划行列表（真分页 + 真筛选）。 */
  public PurchasePlanLinePage pageLines(
      UUID planId, String skuCode, String keyword, String status, String lockStatus, int page, int size) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("plan_id = ?::uuid");
    binds.add(planId);
    if (skuCode != null && !skuCode.isBlank()) {
      where.add("sku_code ilike ?::varchar");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(sku_code ilike ?::varchar or sku_name ilike ?::varchar)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?::varchar");
      binds.add(status.trim());
    }
    if (lockStatus != null && !lockStatus.isBlank()) {
      where.add("lock_status = ?::varchar");
      binds.add(lockStatus.trim());
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from purchase_plan_line where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,plan_id,sku_id,sku_code,sku_name,source_suggestion_id,source_batch_id,"
                + " suggested_quantity,plan_quantity,quantity_basis,target_warehouse_id,"
                + " target_warehouse_code,target_warehouse_name,expected_arrival_date,priority,status,"
                + " lock_status,lock_reservation_id,lock_reservation_no,lock_requested_at,"
                + " lock_confirmed_at,lock_failure_reason,note,sort_rank from purchase_plan_line where "
                + clause
                + " order by sort_rank,sku_code limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<PurchasePlanLineRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(lineItem(row, plan.get("plan_no", String.class), plan.get("plan_type", String.class)));
    }
    String note =
        items.isEmpty() ? "没有符合条件的计划行 —— 可以放宽筛选，或从补货建议汇总一批进来" : "";
    return new PurchasePlanLinePage(items, total, pageNo, pageSize, note);
  }

  /** 计划行详情：行 + 调整留痕 + 锁定留痕。 */
  public PurchasePlanLineDetail lineDetail(UUID planId, UUID lineId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    var line = requireLine(planId, lineId, tenantId);
    List<PurchasePlanAdjustmentRow> adjustments = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,plan_line_id,field,before_value,after_value,reason,adjusted_by,adjusted_at"
                + " from purchase_plan_adjustment where tenant_id = ?::uuid and plan_line_id = ?::uuid"
                + " order by adjusted_at desc,id desc",
            tenantId,
            lineId)) {
      adjustments.add(
          new PurchasePlanAdjustmentRow(
              row.get("id", UUID.class),
              row.get("plan_line_id", UUID.class),
              row.get("field", String.class),
              row.get("before_value", String.class),
              row.get("after_value", String.class),
              row.get("reason", String.class),
              row.get("adjusted_by", UUID.class),
              row.get("adjusted_at", OffsetDateTime.class)));
    }
    List<PurchasePlanLockLogRow> lockLogs = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,plan_line_id,action,reservation_id,reservation_no,message,requested_by,"
                + " requested_at from purchase_plan_lock_log where tenant_id = ?::uuid"
                + " and plan_line_id = ?::uuid order by requested_at desc,id desc",
            tenantId,
            lineId)) {
      String action = row.get("action", String.class);
      lockLogs.add(
          new PurchasePlanLockLogRow(
              row.get("id", UUID.class),
              row.get("plan_line_id", UUID.class),
              action,
              lockActionLabel(action),
              row.get("reservation_id", UUID.class),
              row.get("reservation_no", String.class),
              row.get("message", String.class),
              row.get("requested_by", UUID.class),
              row.get("requested_at", OffsetDateTime.class)));
    }
    return new PurchasePlanLineDetail(
        lineItem(line, plan.get("plan_no", String.class), plan.get("plan_type", String.class)),
        adjustments,
        lockLogs,
        adjustments.isEmpty() && lockLogs.isEmpty() ? "这一行还没有调整与锁定记录" : "");
  }

  // ================================================================ 汇总视图

  /**
   * 汇总视图：<b>日常一段、大促一段</b>。
   *
   * <p>刻意<b>不</b>给一个把两种备货量加在一起的总数字 —— 那个数字会让日常补货失焦，
   * 正是蓝图加粗句「大促备货与日常补货要分开」要防的事。
   */
  public PurchasePlanSummary summary(
      LocalDate periodStart, LocalDate periodEnd, UUID targetWarehouseId) {
    UUID tenantId = currentTenant();
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("p.tenant_id = ?::uuid");
    binds.add(tenantId);
    if (periodStart != null) {
      where.add("p.period_end >= ?::date");
      binds.add(periodStart);
    }
    if (periodEnd != null) {
      where.add("p.period_start <= ?::date");
      binds.add(periodEnd);
    }
    if (targetWarehouseId != null) {
      where.add("p.target_warehouse_id = ?::uuid");
      binds.add(targetWarehouseId);
    }
    String clause = String.join(" and ", where);
    Map<String, PurchasePlanSummaryBucket> buckets = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select p.plan_type,"
                + " count(distinct p.id) as plan_count,"
                + " count(l.id) as line_count,"
                + " coalesce(sum(l.plan_quantity),0) as total_plan_quantity,"
                + " coalesce(sum(l.suggested_quantity),0) as total_suggested_quantity,"
                + " count(l.id) filter (where l.status = 'ADJUSTED') as adjusted_line_count,"
                + " count(l.id) filter (where l.lock_status = 'CONFIRMED') as locked_line_count,"
                + " count(l.id) filter (where l.lock_status = 'FAILED') as failed_line_count,"
                + " min(l.expected_arrival_date) as earliest_arrival_date,"
                + " max(l.expected_arrival_date) as latest_arrival_date"
                + " from purchase_plan p left join purchase_plan_line l"
                + "   on l.tenant_id = p.tenant_id and l.plan_id = p.id"
                + " where "
                + clause
                + " group by p.plan_type",
            binds.toArray())) {
      String type = row.get("plan_type", String.class);
      buckets.put(
          type,
          new PurchasePlanSummaryBucket(
              type,
              planTypeLabel(type),
              row.get("plan_count", Long.class),
              row.get("line_count", Long.class),
              row.get("total_plan_quantity", BigDecimal.class),
              row.get("total_suggested_quantity", BigDecimal.class),
              row.get("adjusted_line_count", Long.class),
              row.get("locked_line_count", Long.class),
              row.get("failed_line_count", Long.class),
              row.get("earliest_arrival_date", LocalDate.class),
              row.get("latest_arrival_date", LocalDate.class)));
    }
    PurchasePlanSummaryBucket routine =
        buckets.getOrDefault(PurchasePlanType.ROUTINE.name(), emptyBucket(PurchasePlanType.ROUTINE));
    PurchasePlanSummaryBucket campaign =
        buckets.getOrDefault(
            PurchasePlanType.CAMPAIGN.name(), emptyBucket(PurchasePlanType.CAMPAIGN));
    return new PurchasePlanSummary(
        routine,
        campaign,
        periodStart,
        periodEnd,
        targetWarehouseId,
        "日常补货与大促备货**分开统计**：两段的备货量不相加 —— 相加之后的总数会让日常补货失焦。");
  }

  private static PurchasePlanSummaryBucket emptyBucket(PurchasePlanType type) {
    return new PurchasePlanSummaryBucket(
        type.name(), type.label(), 0L, 0L, ZERO, ZERO, 0L, 0L, 0L, null, null);
  }

  // ================================================================ 导出

  /**
   * 导出 CSV 给采购员。
   *
   * <p><b>真导出</b>：内容就是这张计划的全部计划行，一行一条，字段与蓝图输出句一一对应。
   * 计划类型与周期也各占一列 —— 大促与日常必须能在文件里分开看。
   */
  public byte[] exportCsv(UUID planId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    var campaign =
        plan.get("campaign_id", UUID.class) == null
            ? null
            : campaignFact(plan.get("campaign_id", UUID.class), tenantId);
    var out = new StringBuilder("﻿");
    out.append(
        String.join(
            ",",
            List.of(
                "计划号",
                "计划类型",
                "大促档期",
                "周期类型",
                "周期开始",
                "周期结束",
                "目标仓编码",
                "目标仓名称",
                "SKU编码",
                "SKU名称",
                "建议量",
                "计划量",
                "算式",
                "期望到货日",
                "优先级",
                "行状态",
                "锁定状态",
                "预占单号",
                "备注")));
    out.append('\n');
    for (Record row : lineRecords(planId, tenantId, null, null, null, null)) {
      String lockStatus = row.get("lock_status", String.class);
      List<String> cells = new ArrayList<>();
      cells.add(plan.get("plan_no", String.class));
      cells.add(planTypeLabel(plan.get("plan_type", String.class)));
      cells.add(campaign == null ? "" : campaign.code());
      cells.add(periodTypeLabel(plan.get("period_type", String.class)));
      cells.add(String.valueOf(plan.get("period_start", LocalDate.class)));
      cells.add(String.valueOf(plan.get("period_end", LocalDate.class)));
      cells.add(plan.get("target_warehouse_code", String.class));
      cells.add(plan.get("target_warehouse_name", String.class));
      cells.add(row.get("sku_code", String.class));
      cells.add(row.get("sku_name", String.class));
      cells.add(quantityText(row.get("suggested_quantity", BigDecimal.class)));
      cells.add(quantityText(row.get("plan_quantity", BigDecimal.class)));
      cells.add(row.get("quantity_basis", String.class));
      cells.add(String.valueOf(row.get("expected_arrival_date", LocalDate.class)));
      cells.add(priorityLabel(row.get("priority", String.class)));
      cells.add(lineStatusLabel(row.get("status", String.class)));
      cells.add(lockStatusLabel(lockStatus));
      cells.add(
          row.get("lock_reservation_no", String.class) == null
              ? ""
              : row.get("lock_reservation_no", String.class));
      cells.add(row.get("note", String.class));
      out.append(String.join(",", cells.stream().map(PurchasePlanService::escape).toList()));
      out.append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  // ================================================================ 内部结构

  /** 大促档期事实（只读）。 */
  private record CampaignFact(
      UUID id,
      String code,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal salesMultiplier,
      BigDecimal stockFactor,
      int arrivalLeadDays) {}

  /** 锁库策略事实（只读）。 */
  private record LockPolicy(
      boolean lockEnabled, int holdDays, boolean requireConfirmed, boolean configured) {}

  /** 采购计划列表的查询条件。全部可空，null = 不限。 */
  public record PurchasePlanQuery(
      String planType,
      String status,
      UUID campaignId,
      UUID targetWarehouseId,
      String priority,
      LocalDate periodStart,
      LocalDate periodEnd,
      String keyword,
      String sort,
      int page,
      int size) {}

  // ================================================================ 取数辅助

  /**
   * 取一张计划（**唯一的取数形状**）。
   *
   * <p>列表页与详情页共用这一条查询，所以「列表上看到的行数 / 锁定数」与「详情里看到的」永远一致 ——
   * 两处各写一份查询，早晚会出现「列表说 3 行、点开只有 2 行」。
   */
  private Record requirePlan(UUID planId, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select p.id,p.plan_no,p.plan_type,p.campaign_id,p.period_type,p.period_start,p.period_end,"
                + " p.target_warehouse_id,p.target_warehouse_code,p.target_warehouse_name,"
                + " p.legal_entity_id,p.legal_entity_code,p.legal_entity_name,p.status,p.priority,p.note,"
                + " p.created_by,p.created_at,p.updated_at,"
                + " c.code as campaign_code,c.name as campaign_name,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id) as line_count,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id and l.lock_status = 'CONFIRMED') as locked_line_count,"
                + " (select count(*) from purchase_plan_line l where l.tenant_id = p.tenant_id"
                + "    and l.plan_id = p.id and l.lock_status = 'FAILED') as failed_line_count,"
                + " (select coalesce(sum(l.plan_quantity),0) from purchase_plan_line l"
                + "    where l.tenant_id = p.tenant_id and l.plan_id = p.id) as total_plan_quantity,"
                + " (select coalesce(sum(l.suggested_quantity),0) from purchase_plan_line l"
                + "    where l.tenant_id = p.tenant_id and l.plan_id = p.id) as total_suggested_quantity"
                + " from purchase_plan p"
                + " left join purchase_plan_campaign c on c.tenant_id = p.tenant_id and c.id = p.campaign_id"
                + " where p.tenant_id = ?::uuid and p.id = ?::uuid",
            tenantId,
            planId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采购计划不存在");
    }
    return row;
  }

  private Record requireLine(UUID planId, UUID lineId, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select id,plan_id,sku_id,sku_code,sku_name,source_suggestion_id,source_batch_id,"
                + " suggested_quantity,plan_quantity,quantity_basis,target_warehouse_id,"
                + " target_warehouse_code,target_warehouse_name,expected_arrival_date,priority,status,"
                + " lock_status,lock_reservation_id,lock_reservation_no,lock_requested_at,"
                + " lock_confirmed_at,lock_failure_reason,note,sort_rank from purchase_plan_line"
                + " where tenant_id = ?::uuid and plan_id = ?::uuid and id = ?::uuid",
            tenantId,
            planId,
            lineId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计划行不存在");
    }
    return row;
  }

  private CampaignFact campaignFact(UUID campaignId, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select id,code,name,start_date,end_date,sales_multiplier,stock_factor,arrival_lead_days"
                + " from purchase_plan_campaign where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            campaignId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "大促档期不存在");
    }
    return new CampaignFact(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("start_date", LocalDate.class),
        row.get("end_date", LocalDate.class),
        row.get("sales_multiplier", BigDecimal.class),
        row.get("stock_factor", BigDecimal.class),
        row.get("arrival_lead_days", Integer.class));
  }

  private PurchasePlanWarehousePort.WarehouseView findWarehouse(UUID id, String bearer) {
    for (PurchasePlanWarehousePort.WarehouseView view : warehouses.warehouses(bearer)) {
      if (id.equals(view.id())) {
        return view;
      }
    }
    return null;
  }

  private PurchasePlanWarehousePort.LegalEntityView findLegalEntity(UUID id, String bearer) {
    for (PurchasePlanWarehousePort.LegalEntityView view : warehouses.legalEntities(bearer)) {
      if (id.equals(view.id())) {
        return view;
      }
    }
    return null;
  }

  private List<Record> lineRecords(
      UUID planId,
      UUID tenantId,
      String skuCode,
      String keyword,
      String status,
      String lockStatus) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("plan_id = ?::uuid");
    binds.add(planId);
    if (skuCode != null && !skuCode.isBlank()) {
      where.add("sku_code ilike ?::varchar");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(sku_code ilike ?::varchar or sku_name ilike ?::varchar)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?::varchar");
      binds.add(status.trim());
    }
    if (lockStatus != null && !lockStatus.isBlank()) {
      where.add("lock_status = ?::varchar");
      binds.add(lockStatus.trim());
    }
    String clause = String.join(" and ", where);
    return sql.fetch(
        "select id,plan_id,sku_id,sku_code,sku_name,source_suggestion_id,source_batch_id,"
            + " suggested_quantity,plan_quantity,quantity_basis,target_warehouse_id,"
            + " target_warehouse_code,target_warehouse_name,expected_arrival_date,priority,status,"
            + " lock_status,lock_reservation_id,lock_reservation_no,lock_requested_at,"
            + " lock_confirmed_at,lock_failure_reason,note,sort_rank from purchase_plan_line where "
            + clause
            + " order by sort_rank,sku_code",
        binds.toArray());
  }

  private long lineCount(UUID planId, UUID tenantId) {
    return sql.fetchOne(
            "select count(*) from purchase_plan_line where tenant_id = ?::uuid and plan_id = ?::uuid",
            tenantId,
            planId)
        .get(0, Long.class);
  }

  /** 计划头（走 {@link #requirePlan} 这一条取数形状）。 */
  private PurchasePlanRow planRow(UUID planId) {
    return planItem(requirePlan(planId, currentTenant()));
  }

  /** 计划行（走 {@link #requireLine} 这一条取数形状）。 */
  private PurchasePlanLineRow lineRow(UUID planId, UUID lineId) {
    UUID tenantId = currentTenant();
    var plan = requirePlan(planId, tenantId);
    return lineItem(
        requireLine(planId, lineId, tenantId),
        plan.get("plan_no", String.class),
        plan.get("plan_type", String.class));
  }

  private PurchasePlanRow planItem(Record row) {
    String planType = row.get("plan_type", String.class);
    String status = row.get("status", String.class);
    String priority = row.get("priority", String.class);
    return new PurchasePlanRow(
        row.get("id", UUID.class),
        row.get("plan_no", String.class),
        planType,
        planTypeLabel(planType),
        row.get("campaign_id", UUID.class),
        row.get("campaign_code", String.class),
        row.get("campaign_name", String.class),
        row.get("period_type", String.class),
        periodTypeLabel(row.get("period_type", String.class)),
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("target_warehouse_id", UUID.class),
        row.get("target_warehouse_code", String.class),
        row.get("target_warehouse_name", String.class),
        row.get("legal_entity_id", UUID.class),
        row.get("legal_entity_code", String.class),
        row.get("legal_entity_name", String.class),
        status,
        planStatusLabel(status),
        priority,
        priorityLabel(priority),
        intOf(row, "line_count"),
        intOf(row, "locked_line_count"),
        intOf(row, "failed_line_count"),
        row.get("total_plan_quantity", BigDecimal.class),
        row.get("total_suggested_quantity", BigDecimal.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** count(*) 这类聚合列在 PG 里是 bigint，取成 int 时要显式转换（别让 jOOQ 在运行期抛类型错）。 */
  private static int intOf(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value != null) {
      return value.intValue();
    }
    Integer boxed = row.get(field, Integer.class);
    return boxed == null ? 0 : boxed;
  }

  private PurchasePlanLineRow lineItem(Record row, String planNo, String planType) {
    String status = row.get("status", String.class);
    String lockStatus = row.get("lock_status", String.class);
    String priority = row.get("priority", String.class);
    return new PurchasePlanLineRow(
        row.get("id", UUID.class),
        row.get("plan_id", UUID.class),
        planNo,
        planType,
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("source_suggestion_id", UUID.class),
        row.get("source_batch_id", UUID.class),
        row.get("suggested_quantity", BigDecimal.class),
        row.get("plan_quantity", BigDecimal.class),
        row.get("quantity_basis", String.class),
        row.get("target_warehouse_id", UUID.class),
        row.get("target_warehouse_code", String.class),
        row.get("target_warehouse_name", String.class),
        row.get("expected_arrival_date", LocalDate.class),
        priority,
        priorityLabel(priority),
        status,
        lineStatusLabel(status),
        lockStatus,
        lockStatusLabel(lockStatus),
        row.get("lock_reservation_id", UUID.class),
        row.get("lock_reservation_no", String.class),
        row.get("lock_requested_at", OffsetDateTime.class),
        row.get("lock_confirmed_at", OffsetDateTime.class),
        row.get("lock_failure_reason", String.class),
        row.get("note", String.class),
        row.get("sort_rank", Integer.class));
  }

  // ================================================================ 口径工具

  /**
   * 当前租户（来自数据库里绑定的 {@code app.current_tenant}）。
   *
   * <p>刻意<b>不</b>从请求头或参数取租户：写入的 {@code tenant_id} 必须与 RLS 绑定的租户是同一个。
   */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  /** 数量的 CSV 文本：null 一律给「无数据」—— 空格会被读成「数量是 0」。 */
  private static String quantityText(BigDecimal value) {
    return value == null ? "无数据" : value.stripTrailingZeros().toPlainString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String appendNote(String base, String extra) {
    if (extra == null || extra.isBlank()) {
      return base;
    }
    return base == null || base.isBlank() ? extra : base + "；" + extra;
  }

  /** CSV 渲染：字段按 RFC 4180 转义，中文表头让采购员拿到文件就能看懂。 */
  private static String escape(String value) {
    String text = value == null ? "" : value;
    // 前导 =/+/-/@ 会被 Excel 当成公式执行：加前缀单引号切断，这是 CSV 注入的常规处置。
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  // ---- 标签（中文名） ----

  static String planTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    return PurchasePlanType.valueOf(type).label();
  }

  static String periodTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    return PurchasePlanPeriodType.valueOf(type).label();
  }

  static String planStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    return PurchasePlanStatus.valueOf(status).label();
  }

  static String lineStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    return PurchasePlanLineStatus.valueOf(status).label();
  }

  static String lockStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    return PurchasePlanLockStatus.valueOf(status).label();
  }

  static String priorityLabel(String priority) {
    if (priority == null) {
      return "";
    }
    return PurchasePlanPriority.valueOf(priority).label();
  }

  static String lockActionLabel(String action) {
    if (action == null) {
      return "";
    }
    return PurchasePlanLockAction.valueOf(action).label();
  }

  static String campaignStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    return PurchasePlanCampaignStatus.valueOf(status).label();
  }
}
