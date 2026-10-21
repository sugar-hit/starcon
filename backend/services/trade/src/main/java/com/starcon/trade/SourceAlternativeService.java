package com.starcon.trade;

import static com.starcon.trade.SourceAlternativeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * 6.1.2.2 同款货源推荐。
 *
 * <p>蓝图原话：「当某 SKU 缺货或质量不达标时，推荐可替代的同款/相似货源：同款不同供应商、历史采购过该货的
 * 供应商、价格对比。输入：SKU 编码与图片；历史采购记录；货源库匹配结果。输出：候选货源列表（供应商、价格、
 * 交期、历史质量评分）+ 一键生成采购申请。」
 *
 * <p><b>本类的三条纪律</b>：
 *
 * <ol>
 *   <li><b>同款判定给置信度，不给「拍脑袋的相似」</b>：每条候选都带 {@code confidence}（是不是同一个货）
 *       与 {@code matchBasis}（凭什么这么算）。三种证据强度不同：人工关联过的 SKU（确证，1.0000）、
 *       历史采购过该货的供应商（强线索，0.5000~0.7500）、只有标题与规格一致（弱线索，0.3000/0.4000）。
 *       <b>图片相似不等于同款</b> —— 本仓没有视觉相似度服务，本模块**不做**图片相似度自动判定，
 *       只把目标 SKU 的参考图与候选图**摆在一起给人看**。</li>
 *   <li><b>弱匹配必须人工确认才能生成采购申请</b>：DB 层 CHECK 钉死（{@code
 *       source_alternative_candidate_confirmation}），服务层再挡一道。疑似同款绕过确认去下单，
 *       插不进候选表、也建不出申请单。</li>
 *   <li><b>排序分 ≠ 置信度</b>：{@code score} 是按<b>租户级权重</b>（价格 / 评级 / 历史 / 交期）合成的
 *       0~1 分，回答「先看哪一条」；{@code confidence} 回答「是不是同款」。便宜的错货一样要人工确认。</li>
 * </ol>
 *
 * <p><b>上游事实从哪来</b>（全部同库同租户，只读；跨服务的 SKU / 供应商 / 仓与法人只经授权只读接口）：
 *
 * <ul>
 *   <li>6.1.2.1 货源池：{@code supplier_source_item} / {@code supplier_source_stall} /
 *       {@code supplier_source_snapshot}（**最新价按 snapshot_seq 取最新那一版**）/
 *       {@code supplier_source_sku_link}（**同款判定的最强证据**）。</li>
 *   <li>6.1.1.3 评级：{@code supplier_rating_result}（**客观分与最终分并排取**，人工调整单独标记）。</li>
 *   <li>6.3 采购历史：{@code purchase_order} / {@code purchase_order_line}（历史采购过该货的供应商 + 交期）。</li>
 * </ul>
 *
 * <p><b>下游</b>：6.2.2.1 采购申请（一键生成，{@code source_type='SUGGESTION'}）。快照价**只是参考**，
 * 实际下单金额以后续采购单为准 —— 所以本模块生成的申请行**不填预估单价**，
 * 只把参考价区间写进备注。
 */
@Service
public class SourceAlternativeService {

  /** 读权限（看推荐清单、候选、规则）。 */
  public static final String READ_PERMISSION = "trade.alternatives.read";

  /** 写权限（发起推荐、人工判定、生成采购申请、改规则）。 */
  public static final String UPDATE_PERMISSION = "trade.alternatives.update";

  /** 单页最大条数。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 货源池一次最多算多少条。
   *
   * <p>刻意**不静默截断**：超了就显式报错让人先收敛货源池 —— 悄悄截掉一半候选，
   * 页面却显示「推荐完成」，那是在编造结果。
   */
  private static final int MAX_POOL = 5000;

  /** 置信度刻度（与 DB 的 numeric(5,4) 对齐）。 */
  private static final int CONFIDENCE_SCALE = 4;

  /** 排序分刻度（与 DB 的 numeric(9,6) 对齐）。 */
  private static final int SCORE_SCALE = 6;

  /** 金额刻度（与 DB 的 numeric(19,4) 对齐）。 */
  private static final int PRICE_SCALE = 4;

  /** 数量刻度（与 DB 的 numeric(19,6) 对齐）。 */
  private static final int QTY_SCALE = 6;

  // ---- 内置默认规则（库里没有策略行时用这一套；一旦改了以库里的为准）----

  private static final BigDecimal DEFAULT_WEAK_MIN = new BigDecimal("0.3000");
  private static final BigDecimal DEFAULT_CONFIRMED_MIN = new BigDecimal("0.9000");
  private static final boolean DEFAULT_REQUIRE_MANUAL = true;
  private static final int DEFAULT_MAX_CANDIDATES = 50;
  private static final BigDecimal DEFAULT_PRICE_WEIGHT = new BigDecimal("0.300000");
  private static final BigDecimal DEFAULT_RATING_WEIGHT = new BigDecimal("0.250000");
  private static final BigDecimal DEFAULT_HISTORY_WEIGHT = new BigDecimal("0.200000");
  private static final BigDecimal DEFAULT_LEAD_TIME_WEIGHT = new BigDecimal("0.250000");

  /** 确证（人工关联过的 SKU）的置信度。 */
  private static final BigDecimal CONFIDENCE_CONFIRMED = new BigDecimal("1.0000");

  /** 历史采购过该货的供应商：基础置信度（第一笔）。 */
  private static final BigDecimal CONFIDENCE_HISTORY_BASE = new BigDecimal("0.5000");

  /** 历史采购过该货的供应商：每多一笔加多少（最多加到 0.7500）。 */
  private static final BigDecimal CONFIDENCE_HISTORY_STEP = new BigDecimal("0.0500");

  /** 历史采购过该货的供应商：置信度上限。 */
  private static final BigDecimal CONFIDENCE_HISTORY_MAX = new BigDecimal("0.7500");

  /** 标题与规格**完全一致**的疑似同款。 */
  private static final BigDecimal CONFIDENCE_TITLE_EXACT = new BigDecimal("0.4000");

  /** 标题**包含**已关联货源标题的疑似同款。 */
  private static final BigDecimal CONFIDENCE_TITLE_CONTAINS = new BigDecimal("0.3000");

  /** 历史笔数达到这个数就按满分算（再多也不加分：十笔与五笔在「供过这个货」上没区别）。 */
  private static final int HISTORY_FULL_SCORE_ORDERS = 5;

  /** 「无依据」时的中性分（0.5）：不是 0 分，也不是满分。 */
  private static final BigDecimal NEUTRAL = new BigDecimal("0.500000");

  private final DSLContext sql;
  private final SourceSkuQueryPort skus;
  private final PurchasePlanWarehousePort warehouses;
  private final PurchaseRequestService requests;

  public SourceAlternativeService(
      DSLContext sql,
      SourceSkuQueryPort skus,
      PurchasePlanWarehousePort warehouses,
      PurchaseRequestService requests) {
    this.sql = sql;
    this.skus = skus;
    this.warehouses = warehouses;
    this.requests = requests;
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」显式报出来，
   * 而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SourceAlternativeCapabilities capabilities() {
    return new SourceAlternativeCapabilities(
        List.of(
            new SourceAlternativeCapability(
                "6.1.2.1",
                "档口与货源池",
                "trade",
                true,
                "货源条目、价格区间、起订量与**人工确认的 SKU 关联**都来自 6.1.2.1；"
                    + "本模块的「确证同款」只认那条人工关联。"),
            new SourceAlternativeCapability(
                "6.1.1.3",
                "供应商评级",
                "trade",
                true,
                "供应商的历史质量评分取自评级结果；**客观分与最终分并排给出**，"
                    + "人工调整过的供应商带单独标记（人工加权分不当成客观分用）。"),
            new SourceAlternativeCapability(
                "6.3.1.1",
                "采购单（历史采购记录）",
                "trade",
                true,
                "「历史采购过该货的供应商」与「历史平均交期」取自已下过的采购单事实。"),
            new SourceAlternativeCapability(
                "6.2.2.1",
                "采购申请",
                "trade",
                true,
                "一键生成会真的写采购申请单（source_type=SUGGESTION），并把来源推荐批次写进备注。"),
            new SourceAlternativeCapability(
                "图片相似度判定",
                "同款自动判定",
                "—",
                false,
                "**未交付**：本仓没有视觉相似度服务。本模块**不做**图片相似度自动判定 —— "
                    + "「图片像」不等于「是同款」，误判会把采购单指向错误商品。"
                    + "页面上把目标 SKU 的参考图与候选图摆在一起，**由人确认**。"),
            new SourceAlternativeCapability(
                "12.1.1",
                "汇率快照",
                "finance",
                false,
                "汇率属 12 财务：本模块**不跨币种比价**，价格对比按币种分组，排序里的价格维度也只在同币种内归一。")),
        List.of(
            SourceAlternativeMatchType.SKU_LINK.name(),
            SourceAlternativeMatchType.PURCHASE_HISTORY.name(),
            SourceAlternativeMatchType.TITLE_SPEC_MATCH.name()),
        List.of(
            "同款判定给**置信度**（confidence）与**判定依据**（matchBasis），不给一个拍脑袋的相似度。",
            "**排序分（score）与置信度（confidence）是两回事**：排序分回答「先看哪一条」，"
                + "置信度回答「是不是同款」；便宜的错货一样要人工确认。",
            "弱匹配（疑似同款、历史供应商）**必须人工确认**才能生成采购申请，DB 层与��务层各挡一道。",
            "货源价格是**外部快照**：区间 + 采集时刻，永不改写；生成采购申请时**不填预估单价**，"
                + "实际下单金额以后续采购单为准。",
            "历史平均交期取自「建单 → 承诺到货」；没有历史就是**没有**（不是 0 天）。"));
  }

  /** 词表选项：前端下拉的数据源，与后端同源。 */
  public SourceAlternativeOptions options() {
    return new SourceAlternativeOptions(
        List.of(
            new SourceAlternativeOption(
                SourceAlternativeReason.STOCKOUT.name(),
                SourceAlternativeReason.STOCKOUT.label(),
                "这个 SKU 断货了，要找能补上的同款货源"),
            new SourceAlternativeOption(
                SourceAlternativeReason.QUALITY.name(),
                SourceAlternativeReason.QUALITY.label(),
                "这个 SKU 质量不达标，要换一家供货")),
        List.of(
            new SourceAlternativeOption(
                SourceAlternativeMatchType.SKU_LINK.name(),
                SourceAlternativeMatchType.SKU_LINK.label(),
                "确证：6.1.2.1 里已人工确认「这个货源就是这个 SKU」"),
            new SourceAlternativeOption(
                SourceAlternativeMatchType.PURCHASE_HISTORY.name(),
                SourceAlternativeMatchType.PURCHASE_HISTORY.label(),
                "强线索：这家供应商历史采购过这个 SKU（仍需人工确认是不是同款）"),
            new SourceAlternativeOption(
                SourceAlternativeMatchType.TITLE_SPEC_MATCH.name(),
                SourceAlternativeMatchType.TITLE_SPEC_MATCH.label(),
                "弱线索：只有标题与规格一致（**图片相似不等于同款**，必须人工确认）")),
        List.of(
            new SourceAlternativeOption(
                SourceAlternativeRunStatus.OPEN.name(),
                SourceAlternativeRunStatus.OPEN.label(),
                "还没下单"),
            new SourceAlternativeOption(
                SourceAlternativeRunStatus.REQUESTED.name(),
                SourceAlternativeRunStatus.REQUESTED.label(),
                "已生成采购申请"),
            new SourceAlternativeOption(
                SourceAlternativeRunStatus.CLOSED.name(),
                SourceAlternativeRunStatus.CLOSED.label(),
                "人工关闭")),
        List.of(
            new SourceAlternativeOption(
                SourceAlternativeDecisionType.CONFIRMED.name(),
                SourceAlternativeDecisionType.CONFIRMED.label(),
                "人工确认这条是同款"),
            new SourceAlternativeOption(
                SourceAlternativeDecisionType.REJECTED.name(),
                SourceAlternativeDecisionType.REJECTED.label(),
                "否决这条（**必须写理由**）")),
        List.of(
            "置信度只衡量「是不是同款」；排序分另算，两者不要混看。",
            "价格对比按币种分组，不做汇率换算。"));
  }

  /** 目标仓与法人（生成采购申请时必填；仓库服务未接线时如实报「不可用」）。 */
  public SourceAlternativeTargetOptions targets(String bearer) {
    try {
      List<SourceAlternativeWarehouseOption> ws =
          warehouses.warehouses(bearer).stream()
              .map(w -> new SourceAlternativeWarehouseOption(w.id(), w.code(), w.name(), w.status()))
              .toList();
      List<SourceAlternativeLegalEntityOption> es =
          warehouses.legalEntities(bearer).stream()
              .map(e -> new SourceAlternativeLegalEntityOption(e.id(), e.code(), e.name(), e.status()))
              .toList();
      return new SourceAlternativeTargetOptions(ws, es, true, "");
    } catch (ResponseStatusException e) {
      return new SourceAlternativeTargetOptions(
          List.of(), List.of(), false, e.getReason() == null ? "仓库服务暂时不可用" : e.getReason());
    }
  }

  /** 概览统计（不受筛选与分页影响）。 */
  public SourceAlternativeOverview overview() {
    UUID tenantId = currentTenant();
    Record runSummary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='OPEN') as open,"
                + " count(*) filter (where status='REQUESTED') as requested"
                + " from source_alternative_run where tenant_id = ?::uuid",
            tenantId);
    Record candidateSummary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where requires_manual_confirmation) as weak"
                + " from source_alternative_candidate where tenant_id = ?::uuid",
            tenantId);
    long confirmed =
        sql.fetchOne(
                "select count(*) from source_alternative_decision"
                    + " where tenant_id = ?::uuid and decision_type='CONFIRMED'",
                tenantId)
            .get(0, Long.class);
    OffsetDateTime last =
        sql.fetchOne(
                "select max(created_at) from source_alternative_run where tenant_id = ?::uuid",
                tenantId)
            .get(0, OffsetDateTime.class);
    return new SourceAlternativeOverview(
        count(runSummary, "total"),
        count(runSummary, "open"),
        count(candidateSummary, "total"),
        confirmed,
        count(candidateSummary, "weak"),
        count(runSummary, "requested"),
        last,
        "概览只统计**本租户**的推荐批次与候选（RLS 在数据库层强制隔离）。");
  }

  // ================================================================ 规则与权重

  /** 当前生效的规则与权重（库里没有就用内置默认值，并把 configured=false 如实报出来）。 */
  public SourceAlternativePolicy policy() {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select weak_match_min_confidence, confirmed_match_min_confidence,"
                + " require_manual_confirmation, max_candidates, price_weight, rating_weight,"
                + " history_weight, lead_time_weight, version, updated_at"
                + " from source_alternative_policy where tenant_id = ?::uuid",
            tenantId);
    if (row == null) {
      return new SourceAlternativePolicy(
          false,
          DEFAULT_WEAK_MIN,
          DEFAULT_CONFIRMED_MIN,
          DEFAULT_REQUIRE_MANUAL,
          DEFAULT_MAX_CANDIDATES,
          DEFAULT_PRICE_WEIGHT,
          DEFAULT_RATING_WEIGHT,
          DEFAULT_HISTORY_WEIGHT,
          DEFAULT_LEAD_TIME_WEIGHT,
          0L,
          null);
    }
    return new SourceAlternativePolicy(
        true,
        row.get("weak_match_min_confidence", BigDecimal.class),
        row.get("confirmed_match_min_confidence", BigDecimal.class),
        Boolean.TRUE.equals(row.get("require_manual_confirmation", Boolean.class)),
        row.get("max_candidates", Integer.class),
        row.get("price_weight", BigDecimal.class),
        row.get("rating_weight", BigDecimal.class),
        row.get("history_weight", BigDecimal.class),
        row.get("lead_time_weight", BigDecimal.class),
        row.get("version", Long.class) == null ? 0L : row.get("version", Long.class),
        instant(row, "updated_at"));
  }

  /** 改规则与权重（每租户一行；权重和必须精确等于 1）。 */
  @Transactional
  public SourceAlternativePolicy savePolicy(SourceAlternativePolicyInput input) {
    UUID tenantId = currentTenant();
    BigDecimal sum =
        input.priceWeight().add(input.ratingWeight()).add(input.historyWeight()).add(input.leadTimeWeight());
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "四个维度的权重之和必须精确等于 1，当前是 " + sum.toPlainString());
    }
    OffsetDateTime now = now();
    sql.execute(
        "insert into source_alternative_policy(id,tenant_id,weak_match_min_confidence,"
            + " confirmed_match_min_confidence,require_manual_confirmation,max_candidates,"
            + " price_weight,rating_weight,history_weight,lead_time_weight,version,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::numeric,?::numeric,?,?,?::numeric,?::numeric,?::numeric,?::numeric,"
            + " 0,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id) do update set"
            + " weak_match_min_confidence = excluded.weak_match_min_confidence,"
            + " confirmed_match_min_confidence = excluded.confirmed_match_min_confidence,"
            + " require_manual_confirmation = excluded.require_manual_confirmation,"
            + " max_candidates = excluded.max_candidates,"
            + " price_weight = excluded.price_weight, rating_weight = excluded.rating_weight,"
            + " history_weight = excluded.history_weight, lead_time_weight = excluded.lead_time_weight,"
            + " version = source_alternative_policy.version + 1, updated_at = excluded.updated_at",
        UUID.randomUUID(),
        tenantId,
        input.weakMatchMinConfidence(),
        input.confirmedMatchMinConfidence(),
        Boolean.TRUE.equals(input.requireManualConfirmation()),
        input.maxCandidates(),
        input.priceWeight(),
        input.ratingWeight(),
        input.historyWeight(),
        input.leadTimeWeight(),
        now,
        now);
    return policy();
  }

  // ================================================================ 推荐批次

  /** 推荐批次列表（真分页 + 真筛选）。 */
  public SourceAlternativeRunPage pageRuns(
      UUID skuId, String reason, String status, String keyword, int page, int size) {
    UUID tenantId = currentTenant();
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    if (skuId != null) {
      where.add("sku_id = ?::uuid");
      binds.add(skuId);
    }
    if (reason != null && !reason.isBlank()) {
      where.add("reason = ?::varchar");
      binds.add(reason(reason).name());
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?::varchar");
      binds.add(runStatus(status).name());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(run_no ilike ?::varchar or sku_code ilike ?::varchar or sku_name ilike ?::varchar)");
      String pattern = "%" + keyword.trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from source_alternative_run" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<Record> rows =
        sql.fetch(
            "select id,run_no,sku_id,sku_code,sku_name,reason,required_quantity,reference_image_url,"
                + " status,candidate_count,confirmed_count,target_warehouse_id,target_warehouse_code,"
                + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,note,"
                + " created_by,created_at"
                + " from source_alternative_run"
                + clause
                + " order by created_at desc, id desc limit ? offset ?",
            pageBinds.toArray());
    return new SourceAlternativeRunPage(
        rows.stream().map(SourceAlternativeService::runRow).toList(), total, p, s);
  }

  /** 批次详情：批次头 + 候选 + 判定留痕 + 价格对比。 */
  public SourceAlternativeRunDetail run(UUID runId) {
    UUID tenantId = currentTenant();
    Record head =
        sql.fetchOne(
            "select id,run_no,sku_id,sku_code,sku_name,reason,required_quantity,reference_image_url,"
                + " status,candidate_count,confirmed_count,target_warehouse_id,target_warehouse_code,"
                + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,note,"
                + " created_by,created_at"
                + " from source_alternative_run where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            runId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "推荐批次不存在或不属于当前租户");
    }
    List<Record> candidateRows =
        sql.fetch(
            "select id,run_id,item_id,stall_id,stall_name,platform,platform_item_id,supplier_ref_id,"
                + " supplier_code,supplier_name,title,spec,image_url,price_min,price_max,currency,moq,"
                + " collected_at,on_sale,match_type,confidence,match_basis,requires_manual_confirmation,"
                + " history_order_count,history_quantity,lead_time_days,rating_objective_score,"
                + " rating_final_score,rating_grade,rating_manually_adjusted,rating_period_start,"
                + " rating_period_end,price_rank,is_lowest_price,score,sort_rank"
                + " from source_alternative_candidate where tenant_id = ?::uuid and run_id = ?::uuid"
                + " order by sort_rank, id",
            tenantId,
            runId);
    List<Record> decisionRows =
        sql.fetch(
            "select id,candidate_id,decision_type,reason,purchase_request_id,purchase_request_no,"
                + " decided_by,decided_at from source_alternative_decision"
                + " where tenant_id = ?::uuid and run_id = ?::uuid order by decided_at, id",
            tenantId,
            runId);
    Map<UUID, Record> latestDecision = new HashMap<>();
    for (Record d : decisionRows) {
      latestDecision.put(d.get("candidate_id", UUID.class), d);
    }
    List<SourceAlternativeCandidateRow> candidates =
        candidateRows.stream()
            .map(r -> candidateRow(r, latestDecision.get(r.get("id", UUID.class))))
            .toList();
    List<SourceAlternativeDecisionRow> decisions =
        decisionRows.stream().map(SourceAlternativeService::decisionRow).toList();
    return new SourceAlternativeRunDetail(
        runRow(head), candidates, decisions, priceComparison(candidateRows), "");
  }

  /**
   * 发起一次推荐：实时算出候选并**落成一批快照**。
   *
   * <p>为什么落快照而不是每次都实时算：候选的证据（价格区间、交期、评分）都是**那一刻**的事实。
   * 三天后运营回看「当时推荐了什么、凭什么」，必须能看到当时的值 —— 实时重算只会给他一个
   * 今天的答案。
   */
  @Transactional
  public SourceAlternativeRunDetail createRun(
      SourceAlternativeCreateRunInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var sku = skus.require(input.skuId(), bearer);
    SourceAlternativeReason why = reason(input.reason());
    SourceAlternativePolicy policy = policy();
    List<Computed> computed = compute(input.skuId(), policy);
    OffsetDateTime now = now();
    UUID runId = UUID.randomUUID();
    String runNo =
        "ALT-"
            + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String note = input.note() == null ? "" : input.note().trim();
    sql.execute(
        "insert into source_alternative_run(id,tenant_id,run_no,sku_id,sku_code,sku_name,reason,"
            + " required_quantity,reference_image_url,status,candidate_count,confirmed_count,note,"
            + " created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,"
            + " ?::numeric,?::varchar,'OPEN',?,0,?::varchar,?::uuid,?::timestamptz,?::timestamptz)",
        runId,
        tenantId,
        runNo,
        sku.id(),
        sku.code(),
        sku.name() == null ? "" : sku.name(),
        why.name(),
        input.requiredQuantity(),
        computed.isEmpty() ? "" : computed.get(0).referenceImageUrl,
        computed.size(),
        note,
        actorId,
        now,
        now);
    int rank = 0;
    for (Computed c : computed) {
      sql.execute(
          "insert into source_alternative_candidate("
              + "id,tenant_id,run_id,item_id,snapshot_id,snapshot_seq,stall_id,platform,stall_name,"
              + "platform_item_id,supplier_ref_id,supplier_code,supplier_name,title,spec,image_url,"
              + "price_min,price_max,currency,moq,collected_at,on_sale,match_type,confidence,"
              + "match_basis,requires_manual_confirmation,history_order_count,history_quantity,"
              + "lead_time_days,rating_objective_score,rating_final_score,rating_grade,"
              + "rating_manually_adjusted,rating_period_start,rating_period_end,price_rank,"
              + "is_lowest_price,score,sort_rank,created_at) values ("
              + "?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid,?::varchar,?::varchar,"
              + "?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
              + "?::numeric,?::numeric,?::varchar,?::numeric,?::timestamptz,?,?::varchar,?::numeric,"
              + "?::varchar,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?::varchar,?,?::date,"
              + "?::date,?,?,?::numeric,?,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          runId,
          c.itemId,
          c.snapshotId,
          c.snapshotSeq,
          c.stallId,
          c.platform,
          c.stallName,
          c.platformItemId,
          c.supplierId,
          c.supplierCode,
          c.supplierName,
          c.title,
          c.spec,
          c.imageUrl,
          c.priceMin,
          c.priceMax,
          c.currency,
          c.moq,
          c.collectedAt,
          c.onSale,
          c.matchType,
          c.confidence,
          c.matchBasis,
          c.requiresManualConfirmation,
          c.historyOrderCount,
          c.historyQuantity,
          c.leadTimeDays,
          c.ratingObjectiveScore,
          c.ratingFinalScore,
          c.ratingGrade,
          c.ratingManuallyAdjusted,
          c.ratingPeriodStart,
          c.ratingPeriodEnd,
          c.priceRank,
          c.lowestPrice,
          c.score,
          rank++,
          now);
    }
    return run(runId);
  }

  /**
   * 实时算一份候选清单（**不落库**）：给运营在正式发起推荐之前先看比价。
   *
   * <p>与 {@link #createRun} 用的是同一套计算，所以「预览」与「落库」看到的不会不一样。
   */
  public List<SourceAlternativeCandidateRow> preview(UUID skuId, String bearer) {
    skus.require(skuId, bearer);
    List<Computed> computed = compute(skuId, policy());
    return computed.stream()
        .map(c -> new SourceAlternativeCandidateRow(
                null,
                null,
                c.itemId,
                c.stallId,
                c.stallName,
                c.platform,
                c.platformItemId,
                c.supplierId,
                c.supplierCode,
                c.supplierName,
                c.title,
                c.spec,
                c.imageUrl,
                c.priceMin,
                c.priceMax,
                c.currency,
                c.moq,
                c.collectedAt,
                c.onSale,
                c.matchType,
                matchType(c.matchType).label(),
                c.confidence,
                c.matchBasis,
                c.requiresManualConfirmation,
                c.historyOrderCount,
                c.historyQuantity,
                c.leadTimeDays,
                c.ratingObjectiveScore,
                c.ratingFinalScore,
                c.ratingGrade,
                c.ratingManuallyAdjusted,
                c.ratingPeriodStart,
                c.ratingPeriodEnd,
                c.priceRank,
                c.lowestPrice,
                c.score,
                c.sortRank,
                null,
                null,
                null))
        .toList();
  }

  // ================================================================ 判定

  /**
   * 人工判定一条候选：确认是同款 / 否决。
   *
   * <p>🔴 否决**必须写理由**：说不出理由的否决等于没否决，三个月后没人知道为什么弃掉这条。
   */
  @Transactional
  public SourceAlternativeDecisionRow decide(
      UUID candidateId, SourceAlternativeDecisionInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    Record candidate =
        sql.fetchOne(
            "select c.id,c.run_id,c.match_type,c.requires_manual_confirmation,c.supplier_ref_id,"
                + " c.confidence,c.match_basis,r.run_no,r.sku_id,r.sku_code"
                + " from source_alternative_candidate c"
                + " join source_alternative_run r on r.tenant_id = c.tenant_id and r.id = c.run_id"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            tenantId,
            candidateId);
    if (candidate == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选不存在或不属于当前租户");
    }
    String type = input.decisionType() == null ? "" : input.decisionType().trim();
    String reason = input.reason() == null ? "" : input.reason().trim();
    if (!"CONFIRMED".equals(type) && !"REJECTED".equals(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "判定类型只能是 CONFIRMED / REJECTED");
    }
    if ("REJECTED".equals(type) && reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "否决必须写理由");
    }
    boolean already =
        sql.fetchOne(
                "select count(*) from source_alternative_decision"
                    + " where tenant_id = ?::uuid and candidate_id = ?::uuid and decision_type = ?::varchar",
                tenantId,
                candidateId,
                type)
            .get(0, Long.class)
            > 0;
    if (already) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条候选已经有「" + type + "」的判定记录了");
    }
    OffsetDateTime now = now();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into source_alternative_decision(id,tenant_id,run_id,candidate_id,decision_type,reason,"
            + " purchase_request_id,purchase_request_no,decided_by,decided_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,null,'',?::uuid,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        candidate.get("run_id", UUID.class),
        candidateId,
        type,
        reason,
        actorId,
        now,
        now);
    if ("CONFIRMED".equals(type)) {
      sql.execute(
          "update source_alternative_run set confirmed_count ="
              + " (select count(*) from source_alternative_decision d"
              + "  where d.tenant_id = source_alternative_run.tenant_id and d.run_id = source_alternative_run.id"
              + "  and d.decision_type = 'CONFIRMED'),"
              + " updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          now,
          tenantId,
          candidate.get("run_id", UUID.class));
    }
    return decisionRow(
        sql.fetchOne(
            "select id,candidate_id,decision_type,reason,purchase_request_id,purchase_request_no,"
                + " decided_by,decided_at from source_alternative_decision"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id));
  }

  /**
   * 一键生成采购申请（6.2.2.1）。
   *
   * <p>🔴 三道前置校验，缺一不可：
   *
   * <ol>
   *   <li>**弱匹配必须已被人工确认** —— 疑似同款直接下单就是把采购单指向错误商品。</li>
   *   <li>**候选必须有供应商** —— 没有供应商就没有下单对象。</li>
   *   <li>**数量不得低于起订量** —— 起订量是真实约束，低于它的采购单到外部平台会被拒。</li>
   * </ol>
   *
   * <p>金额口径：申请行**不填预估单价**（快照价只是参考，实际下单金额以后续采购单为准），
   * 参考价区间与采集时刻写进备注。
   */
  @Transactional
  public SourceAlternativePurchaseRequestResult createPurchaseRequest(
      UUID candidateId, SourceAlternativePurchaseRequestInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    Record candidate =
        sql.fetchOne(
            "select c.id,c.run_id,c.item_id,c.supplier_ref_id,c.supplier_code,"
                + " c.supplier_name,c.title,c.platform,c.price_min,c.price_max,c.currency,c.moq,"
                + " c.collected_at,c.requires_manual_confirmation,c.confidence,c.match_type,"
                + " r.run_no,r.sku_id,r.sku_code,r.sku_name"
                + " from source_alternative_candidate c"
                + " join source_alternative_run r on r.tenant_id = c.tenant_id and r.id = c.run_id"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            tenantId,
            candidateId);
    if (candidate == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选不存在或不属于当前租户");
    }
    boolean confirmed =
        sql.fetchOne(
                "select count(*) from source_alternative_decision"
                    + " where tenant_id = ?::uuid and candidate_id = ?::uuid and decision_type='CONFIRMED'",
                tenantId,
                candidateId)
            .get(0, Long.class)
            > 0;
    if (Boolean.TRUE.equals(candidate.get("requires_manual_confirmation", Boolean.class)) && !confirmed) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这条候选是「"
              + matchType(candidate.get("match_type", String.class)).label()
              + "」（置信度 "
              + candidate.get("confidence", BigDecimal.class)
              + "），**必须先人工确认是同款**才能生成采购申请 —— "
              + "误判会把采购单指向错误商品");
    }
    UUID supplierId = candidate.get("supplier_ref_id", UUID.class);
    if (supplierId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这个货源没有关联供应商（档口上没登记），没有下单对象 —— "
              + "请先在 6.1.2.1 给档口关联供应商");
    }
    BigDecimal quantity = input.quantity();
    BigDecimal moq = candidate.get("moq", BigDecimal.class);
    if (moq != null && quantity.compareTo(moq) < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "申请数量 " + quantity.toPlainString() + " 低于该货源的起订量 " + moq.toPlainString()
              + " —— 起订量是外部平台的硬约束，低于它的采购单会被拒");
    }
    String priceRef = priceReference(candidate);
    String note =
        "来源：同款货源推荐 "
            + candidate.get("run_no", String.class)
            + "（候选 "
            + candidate.get("match_type", String.class)
            + "，置信度 "
            + candidate.get("confidence", BigDecimal.class)
            + "）。"
            + priceRef
            + " **快照价仅作参考，实际下单金额以后续采购单为准。**";
    String lineNote =
        "货源："
            + candidate.get("platform", String.class)
            + " / "
            + candidate.get("title", String.class)
            + "；"
            + priceRef;
    var result =
        requests.createFromSuggestion(
            new PurchaseRequestModels.PurchaseRequestSuggestionInput(
                candidate.get("run_id", UUID.class),
                input.targetWarehouseId(),
                input.legalEntityId(),
                candidate.get("currency", String.class),
                List.of(
                    new PurchaseRequestModels.PurchaseRequestSuggestionLineInput(
                        candidate.get("sku_id", UUID.class),
                        candidate.get("sku_code", String.class),
                        candidate.get("sku_name", String.class),
                        quantity,
                        null,
                        null,
                        "NORMAL",
                        lineNote,
                        supplierId,
                        candidate.get("supplier_code", String.class),
                        candidate.get("supplier_name", String.class))),
                note),
            actorId,
            bearer);
    OffsetDateTime now = now();
    sql.execute(
        "insert into source_alternative_decision(id,tenant_id,run_id,candidate_id,decision_type,reason,"
            + " purchase_request_id,purchase_request_no,decided_by,decided_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,'REQUESTED',?::varchar,?::uuid,?::varchar,"
            + " ?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        candidate.get("run_id", UUID.class),
        candidateId,
        "已生成采购申请 " + result.requestNo(),
        result.id(),
        result.requestNo(),
        actorId,
        now,
        now);
    sql.execute(
        "update source_alternative_run set status='REQUESTED', updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        now,
        tenantId,
        candidate.get("run_id", UUID.class));
    return new SourceAlternativePurchaseRequestResult(
        result.id(),
        result.requestNo(),
        result.status(),
        candidateId,
        candidate.get("run_no", String.class),
        note);
  }

  // ================================================================ 候选计算（核心）

  /**
   * 算候选（同一套计算同时服务「预览」与「落库」）。
   *
   * <p>顺序很重要：<b>先按证据分类 → 再按置信度过滤 → 再算排序分 → 最后按分数截断</b>。
   * 反过来做（先截断再过滤）会让一个低置信度的候选占掉名额，
   * 真正的同款反而被挤出去。
   */
  private List<Computed> compute(UUID skuId, SourceAlternativePolicy policy) {
    // 1) 参考基线：这个 SKU 已经**人工确认**关联过的货源（同款判定的锚点）。
    List<Record> referenceRows =
        sql.fetch(
            "select l.sku_id,l.sku_code,l.sku_name,i.id as item_id,sn.title,sn.spec,sn.image_url,sn.on_sale"
                + " from supplier_source_sku_link l"
                + " join supplier_source_item i on i.tenant_id = l.tenant_id and i.id = l.item_id"
                + " join lateral ("
                + "   select ss.title, ss.spec, ss.image_url, ss.on_sale"
                + "   from supplier_source_snapshot ss"
                + "   where ss.tenant_id = i.tenant_id and ss.item_id = i.id"
                + "   order by ss.snapshot_seq desc limit 1) sn on true"
                + " where l.sku_id = ?::uuid",
            skuId);
    List<String> referenceTitles = new ArrayList<>();
    List<String> referenceSpecs = new ArrayList<>();
    String referenceImage = "";
    for (Record r : referenceRows) {
      referenceTitles.add(normalize(r.get("title", String.class)));
      referenceSpecs.add(normalize(r.get("spec", String.class)));
      if (referenceImage.isEmpty()) {
        referenceImage = orEmpty(r.get("image_url", String.class));
      }
    }

    // 2) 候选池：租户的全部**在售**货源（最新一版快照，价格区间永远是区间）。
    List<Record> pool =
        sql.fetch(
            "select i.id as item_id,i.stall_id,i.platform_item_id,s.platform,s.name as stall_name,"
                + " s.supplier_party_id,s.supplier_name_snapshot,"
                + " sn.id as snapshot_id,sn.snapshot_seq,sn.title,sn.spec,sn.image_url,sn.item_url,"
                + " sn.price_min,sn.price_max,sn.currency,sn.moq,sn.collected_at,sn.on_sale,"
                + " (select count(*) from supplier_source_sku_link lk"
                + "   where lk.tenant_id = i.tenant_id and lk.item_id = i.id and lk.sku_id = ?::uuid)"
                + "   as link_count"
                + " from supplier_source_item i"
                + " join supplier_source_stall s on s.tenant_id = i.tenant_id and s.id = i.stall_id"
                + " join lateral ("
                + "   select ss.id, ss.snapshot_seq, ss.title, ss.spec, ss.image_url, ss.item_url,"
                + "          ss.price_min, ss.price_max, ss.currency, ss.moq, ss.collected_at, ss.on_sale"
                + "   from supplier_source_snapshot ss"
                + "   where ss.tenant_id = i.tenant_id and ss.item_id = i.id"
                + "   order by ss.snapshot_seq desc limit 1) sn on true"
                + " where sn.on_sale",
            skuId);
    if (pool.size() > MAX_POOL) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "货源池里有 " + pool.size() + " 条在售货源，超过一次可算的上限 " + MAX_POOL
              + " —— 请先按档口收敛货源池（静默截断会让推荐结果不完整而页面看不出来）");
    }

    // 3) 历史采购事实：这个 SKU 找哪些供应商买过、买了几次、平均交期多久。
    Map<UUID, History> history = new HashMap<>();
    for (Record r :
        sql.fetch(
            "select po.supplier_ref_id, count(distinct po.id) as order_count,"
                + " sum(pol.quantity) as total_quantity,"
                + " avg((po.promised_arrival_date - po.created_at::date)) as lead_time_days"
                + " from purchase_order_line pol"
                + " join purchase_order po on po.tenant_id = pol.tenant_id and po.id = pol.order_id"
                + " where pol.sku_id = ?::uuid"
                + "   and po.status in ('APPROVED','IN_FULFILLMENT','CLOSED')"
                + " group by po.supplier_ref_id",
            skuId)) {
      history.put(
          r.get("supplier_ref_id", UUID.class),
          new History(
              r.get("order_count", Integer.class) == null ? 0 : r.get("order_count", Integer.class),
              r.get("total_quantity", BigDecimal.class) == null
                  ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                  : r.get("total_quantity", BigDecimal.class),
              r.get("lead_time_days", BigDecimal.class)));
    }

    // 4) 评级：取每个供应商**最新一期**的结果（客观分与最终分并排，人工调整单独标记）。
    List<UUID> supplierIds =
        pool.stream()
            .map(r -> r.get("supplier_party_id", UUID.class))
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, Rating> ratings = new HashMap<>();
    if (!supplierIds.isEmpty()) {
      // ⚠️ 逐个占位符（不用 `any(?::uuid[])` —— 驱动会把数组当成单个 uuid 送，类型直接对不上）。
      String placeholders = String.join(",", java.util.Collections.nCopies(supplierIds.size(), "?::uuid"));
      for (Record r :
          sql.fetch(
              "select distinct on (r.supplier_ref_id) r.supplier_ref_id, r.objective_score,"
                  + " r.final_score, r.grade, r.manually_adjusted, r.period_start, r.period_end"
                  + " from supplier_rating_result r"
                  + " where r.supplier_ref_id in ("
                  + placeholders
                  + ")"
                  + " order by r.supplier_ref_id, r.period_end desc, r.period_start desc",
              supplierIds.toArray())) {
        ratings.put(
            r.get("supplier_ref_id", UUID.class),
            new Rating(
                r.get("objective_score", BigDecimal.class),
                r.get("final_score", BigDecimal.class),
                r.get("grade", String.class),
                Boolean.TRUE.equals(r.get("manually_adjusted", Boolean.class)),
                r.get("period_start", LocalDate.class),
                r.get("period_end", LocalDate.class)));
      }
    }

    // 5) 分类 + 打分。
    List<Computed> computed = new ArrayList<>();
    for (Record row : pool) {
      UUID itemId = row.get("item_id", UUID.class);
      int linkCount = row.get("link_count", Integer.class) == null ? 0 : row.get("link_count", Integer.class);
      UUID supplierId = row.get("supplier_party_id", UUID.class);
      History hist = supplierId == null ? null : history.get(supplierId);
      String title = orEmpty(row.get("title", String.class));
      String spec = orEmpty(row.get("spec", String.class));

      String matchType;
      BigDecimal confidence;
      String basis;
      boolean manual;
      if (linkCount > 0) {
        // 确证：6.1.2.1 里已**人工确认**「这个货源就是这个 SKU」。
        matchType = SourceAlternativeMatchType.SKU_LINK.name();
        confidence = CONFIDENCE_CONFIRMED;
        basis = "已建立货源关联（人工确认的同款）—— 6.1.2.1 的 SKU 关联里已经挂上了这个货源";
        manual = false;
      } else if (hist != null && hist.orderCount() > 0) {
        // 强线索：这家供过这个货，但**没说这次是同款**。
        matchType = SourceAlternativeMatchType.PURCHASE_HISTORY.name();
        BigDecimal step = CONFIDENCE_HISTORY_STEP.multiply(BigDecimal.valueOf(hist.orderCount() - 1L));
        confidence =
            CONFIDENCE_HISTORY_BASE.add(step).min(CONFIDENCE_HISTORY_MAX).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
        basis =
            "该供应商历史采购过这个 SKU（"
                + hist.orderCount()
                + " 笔、合计 "
                + hist.totalQuantity().toPlainString()
                + " 件）—— 说明供过这个货，但**仍需人工确认是不是同款**";
        manual = true;
      } else {
        TitleMatch match = titleMatch(title, spec, referenceTitles, referenceSpecs);
        if (match == null) {
          // 三种证据都不成立 ⇒ **不是候选**。宁可少推，也不推一条说不清依据的货。
          continue;
        }
        matchType = SourceAlternativeMatchType.TITLE_SPEC_MATCH.name();
        confidence = match.exact() ? CONFIDENCE_TITLE_EXACT : CONFIDENCE_TITLE_CONTAINS;
        basis =
            (match.exact()
                    ? "标题与规格与已关联货源**完全一致**"
                    : "标题包含已关联货源的标题且规格一致")
                + " —— **未经人工确认**：图片相似不等于同款，采购前必须人工核对";
        manual = true;
      }

      BigDecimal minConfidence =
          "SKU_LINK".equals(matchType)
              ? policy.confirmedMatchMinConfidence()
              : policy.weakMatchMinConfidence();
      if (confidence.compareTo(minConfidence) < 0) {
        continue;
      }
      Rating rating = supplierId == null ? null : ratings.get(supplierId);
      computed.add(
          new Computed(
              itemId,
              row.get("snapshot_id", UUID.class),
              row.get("snapshot_seq", Integer.class),
              row.get("stall_id", UUID.class),
              orEmpty(row.get("stall_name", String.class)),
              orEmpty(row.get("platform", String.class)),
              orEmpty(row.get("platform_item_id", String.class)),
              supplierId,
              "",
              orEmpty(row.get("supplier_name_snapshot", String.class)),
              title,
              spec,
              orEmpty(row.get("image_url", String.class)),
              row.get("price_min", BigDecimal.class),
              row.get("price_max", BigDecimal.class),
              orEmpty(row.get("currency", String.class)),
              row.get("moq", BigDecimal.class),
              instant(row, "collected_at"),
              Boolean.TRUE.equals(row.get("on_sale", Boolean.class)),
              matchType,
              confidence,
              basis,
              manual,
              hist == null ? 0 : hist.orderCount(),
              hist == null ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP) : hist.totalQuantity(),
              hist == null ? null : hist.leadTimeDays(),
              rating == null ? null : rating.objectiveScore(),
              rating == null ? null : rating.finalScore(),
              rating == null ? null : rating.grade(),
              rating == null ? null : rating.manuallyAdjusted(),
              rating == null ? null : rating.periodStart(),
              rating == null ? null : rating.periodEnd(),
              referenceImage));
    }

    // 6) 同币种内的价格序与价格分（**不跨币种换算** —— 汇率属 12 财务）。
    Map<String, List<Computed>> byCurrency = new LinkedHashMap<>();
    for (Computed c : computed) {
      byCurrency.computeIfAbsent(c.currency, k -> new ArrayList<>()).add(c);
    }
    for (List<Computed> group : byCurrency.values()) {
      group.sort(Comparator.comparing(c -> c.priceMin, Comparator.nullsLast(Comparator.naturalOrder())));
      int rank = 0;
      for (Computed c : group) {
        rank++;
        c.priceRank = rank;
        c.lowestPrice = rank == 1;
      }
      // 价格分：同币种内最低价 1 分、最高价 0 分；**没有可比对象时给中性 0.5**（不是 0 分）。
      BigDecimal min = null;
      BigDecimal max = null;
      for (Computed c : group) {
        if (c.priceMin == null) continue;
        if (min == null || c.priceMin.compareTo(min) < 0) min = c.priceMin;
        if (max == null || c.priceMin.compareTo(max) > 0) max = c.priceMin;
      }
      for (Computed c : group) {
        if (min == null || max == null || min.compareTo(max) == 0 || c.priceMin == null) {
          c.priceScore = NEUTRAL;
        } else {
          c.priceScore =
              max.subtract(c.priceMin)
                  .divide(max.subtract(min), SCORE_SCALE, RoundingMode.HALF_UP);
        }
      }
    }

    // 7) 交期分：交期越短分越高；没有历史交期 = 中性 0.5（**不是 0 天、也不是最差**）。
    BigDecimal minLead = null;
    BigDecimal maxLead = null;
    for (Computed c : computed) {
      if (c.leadTimeDays == null) continue;
      if (minLead == null || c.leadTimeDays.compareTo(minLead) < 0) minLead = c.leadTimeDays;
      if (maxLead == null || c.leadTimeDays.compareTo(maxLead) > 0) maxLead = c.leadTimeDays;
    }
    boolean leadComparable = minLead != null && maxLead != null && minLead.compareTo(maxLead) != 0;

    // 8) 排序分：四个维度按租户级权重合成。
    for (Computed c : computed) {
      BigDecimal leadTimeScore = NEUTRAL;
      if (c.leadTimeDays != null && leadComparable) {
        leadTimeScore =
            maxLead.subtract(c.leadTimeDays).divide(maxLead.subtract(minLead), SCORE_SCALE, RoundingMode.HALF_UP);
      }
      BigDecimal ratingScore =
          c.ratingFinalScore == null
              ? NEUTRAL
              : c.ratingFinalScore.divide(BigDecimal.valueOf(100), SCORE_SCALE, RoundingMode.HALF_UP);
      BigDecimal historyScore =
          BigDecimal.valueOf(Math.min(c.historyOrderCount, HISTORY_FULL_SCORE_ORDERS))
              .divide(BigDecimal.valueOf(HISTORY_FULL_SCORE_ORDERS), SCORE_SCALE, RoundingMode.HALF_UP);
      BigDecimal score =
          policy.priceWeight().multiply(c.priceScore)
              .add(policy.ratingWeight().multiply(ratingScore))
              .add(policy.historyWeight().multiply(historyScore))
              .add(policy.leadTimeWeight().multiply(leadTimeScore))
              .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
      c.score = score.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    // 8) 排序 + 截断（截断在过滤之后）。
    computed.sort(
        Comparator.comparing((Computed c) -> c.score).reversed()
            .thenComparing(c -> c.confidence, Comparator.reverseOrder())
            .thenComparing(c -> c.priceMin, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.itemId.toString()));
    int limit = Math.max(1, policy.maxCandidates());
    List<Computed> kept = computed.size() > limit ? computed.subList(0, limit) : computed;
    List<Computed> result = new ArrayList<>();
    int rank = 0;
    for (Computed c : kept) {
      c.sortRank = rank++;
      result.add(c);
    }
    return result;
  }

  /** 标题 + 规格的弱匹配。 */
  private static TitleMatch titleMatch(
      String title, String spec, List<String> referenceTitles, List<String> referenceSpecs) {
    if (referenceTitles.isEmpty()) return null;
    String normTitle = normalize(title);
    String normSpec = normalize(spec);
    if (normTitle.isEmpty()) return null;
    for (int i = 0; i < referenceTitles.size(); i++) {
      String refTitle = referenceTitles.get(i);
      if (refTitle.isEmpty()) continue;
      String refSpec = referenceSpecs.get(i);
      boolean specSame = normSpec.equals(refSpec);
      if (normTitle.equals(refTitle) && specSame) return new TitleMatch(true);
      if (!specSame) continue;
      if (normTitle.contains(refTitle) || refTitle.contains(normTitle)) return new TitleMatch(false);
    }
    return null;
  }

  /** 归一化：去空白 + 小写。 */
  private static String normalize(String value) {
    if (value == null) return "";
    return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
  }

  /** 快照价参考语（写进备注；区间 + 采集时刻，**不给一个假精确价**）。 */
  private static String priceReference(Record candidate) {
    BigDecimal min = candidate.get("price_min", BigDecimal.class);
    BigDecimal max = candidate.get("price_max", BigDecimal.class);
    String currency = orEmpty(candidate.get("currency", String.class));
    OffsetDateTime collectedAt = instant(candidate, "collected_at");
    if (min == null) return "货源快照没有价格。";
    String range =
        min.compareTo(max) == 0
            ? currency + " " + min.toPlainString()
            : currency + " " + min.toPlainString() + "~" + max.toPlainString();
    return "货源快照价区间 " + range + "（采集于 " + (collectedAt == null ? "未知时刻" : collectedAt) + "）。";
  }

  // ================================================================ 读出口

  private static SourceAlternativeRunRow runRow(Record r) {
    String reason = orEmpty(r.get("reason", String.class));
    String status = orEmpty(r.get("status", String.class));
    return new SourceAlternativeRunRow(
        r.get("id", UUID.class),
        orEmpty(r.get("run_no", String.class)),
        r.get("sku_id", UUID.class),
        orEmpty(r.get("sku_code", String.class)),
        orEmpty(r.get("sku_name", String.class)),
        reason,
        reason(reason).label(),
        r.get("required_quantity", BigDecimal.class),
        orEmpty(r.get("reference_image_url", String.class)),
        status,
        runStatus(status).label(),
        r.get("candidate_count", Integer.class) == null ? 0 : r.get("candidate_count", Integer.class),
        r.get("confirmed_count", Integer.class) == null ? 0 : r.get("confirmed_count", Integer.class),
        r.get("target_warehouse_id", UUID.class),
        orEmpty(r.get("target_warehouse_code", String.class)),
        orEmpty(r.get("target_warehouse_name", String.class)),
        r.get("legal_entity_id", UUID.class),
        orEmpty(r.get("legal_entity_code", String.class)),
        orEmpty(r.get("legal_entity_name", String.class)),
        orEmpty(r.get("note", String.class)),
        r.get("created_by", UUID.class),
        instant(r, "created_at"));
  }

  private static SourceAlternativeCandidateRow candidateRow(Record r, Record decision) {
    String matchType = orEmpty(r.get("match_type", String.class));
    String decisionType = decision == null ? null : orEmpty(decision.get("decision_type", String.class));
    return new SourceAlternativeCandidateRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("item_id", UUID.class),
        r.get("stall_id", UUID.class),
        orEmpty(r.get("stall_name", String.class)),
        orEmpty(r.get("platform", String.class)),
        orEmpty(r.get("platform_item_id", String.class)),
        r.get("supplier_ref_id", UUID.class),
        orEmpty(r.get("supplier_code", String.class)),
        orEmpty(r.get("supplier_name", String.class)),
        orEmpty(r.get("title", String.class)),
        orEmpty(r.get("spec", String.class)),
        orEmpty(r.get("image_url", String.class)),
        r.get("price_min", BigDecimal.class),
        r.get("price_max", BigDecimal.class),
        orEmpty(r.get("currency", String.class)),
        r.get("moq", BigDecimal.class),
        instant(r, "collected_at"),
        Boolean.TRUE.equals(r.get("on_sale", Boolean.class)),
        matchType,
        matchType(matchType).label(),
        r.get("confidence", BigDecimal.class),
        orEmpty(r.get("match_basis", String.class)),
        Boolean.TRUE.equals(r.get("requires_manual_confirmation", Boolean.class)),
        r.get("history_order_count", Integer.class) == null
            ? 0
            : r.get("history_order_count", Integer.class),
        r.get("history_quantity", BigDecimal.class),
        r.get("lead_time_days", BigDecimal.class),
        r.get("rating_objective_score", BigDecimal.class),
        r.get("rating_final_score", BigDecimal.class),
        r.get("rating_grade", String.class),
        r.get("rating_manually_adjusted", Boolean.class),
        r.get("rating_period_start", LocalDate.class),
        r.get("rating_period_end", LocalDate.class),
        r.get("price_rank", Integer.class),
        Boolean.TRUE.equals(r.get("is_lowest_price", Boolean.class)),
        r.get("score", BigDecimal.class),
        r.get("sort_rank", Integer.class) == null ? 0 : r.get("sort_rank", Integer.class),
        decisionType,
        decisionType == null ? null : decisionType(decisionType).label(),
        decision == null ? null : orEmpty(decision.get("purchase_request_no", String.class)));
  }

  private static SourceAlternativeDecisionRow decisionRow(Record r) {
    String type = orEmpty(r.get("decision_type", String.class));
    return new SourceAlternativeDecisionRow(
        r.get("id", UUID.class),
        r.get("candidate_id", UUID.class),
        type,
        decisionType(type).label(),
        orEmpty(r.get("reason", String.class)),
        r.get("purchase_request_id", UUID.class),
        orEmpty(r.get("purchase_request_no", String.class)),
        r.get("decided_by", UUID.class),
        instant(r, "decided_at"));
  }

  /** 价格对比：按币种分组给出区间（**不跨币种换算**）。 */
  private static List<SourceAlternativePriceComparison> priceComparison(List<Record> candidates) {
    Map<String, List<Record>> byCurrency = new LinkedHashMap<>();
    for (Record c : candidates) {
      byCurrency.computeIfAbsent(orEmpty(c.get("currency", String.class)), k -> new ArrayList<>()).add(c);
    }
    List<SourceAlternativePriceComparison> out = new ArrayList<>();
    for (Map.Entry<String, List<Record>> entry : byCurrency.entrySet()) {
      BigDecimal min = null;
      BigDecimal max = null;
      for (Record c : entry.getValue()) {
        BigDecimal p = c.get("price_min", BigDecimal.class);
        if (p == null) continue;
        if (min == null || p.compareTo(min) < 0) min = p;
        if (max == null || p.compareTo(max) > 0) max = p;
      }
      out.add(
          new SourceAlternativePriceComparison(
              entry.getKey(), min, max, entry.getValue().size()));
    }
    return out;
  }

  // ================================================================ 词表解析

  private static SourceAlternativeReason reason(String value) {
    try {
      return SourceAlternativeReason.valueOf(value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "推荐理由只能是 STOCKOUT / QUALITY");
    }
  }

  private static SourceAlternativeMatchType matchType(String value) {
    try {
      return SourceAlternativeMatchType.valueOf(value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的判定类型：" + value);
    }
  }

  private static SourceAlternativeRunStatus runStatus(String value) {
    try {
      return SourceAlternativeRunStatus.valueOf(value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的批次状态：" + value);
    }
  }

  private static SourceAlternativeDecisionType decisionType(String value) {
    try {
      return SourceAlternativeDecisionType.valueOf(value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的判定类型：" + value);
    }
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 读时刻：驱动可能给 OffsetDateTime，也可能给 Timestamp —— 两种都要按**时刻**读，不做本地时区偏移。 */
  private static OffsetDateTime instant(Record r, String column) {
    Object value = r.get(column);
    if (value == null) return null;
    if (value instanceof OffsetDateTime o) return o;
    if (value instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    return OffsetDateTime.parse(value.toString());
  }

  private static long count(Record r, String column) {
    Object value = r.get(column);
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  // ================================================================ 内部结构

  /** 历史采购事实。 */
  private record History(int orderCount, BigDecimal totalQuantity, BigDecimal leadTimeDays) {}

  /** 评级快照。 */
  private record Rating(
      BigDecimal objectiveScore,
      BigDecimal finalScore,
      String grade,
      boolean manuallyAdjusted,
      LocalDate periodStart,
      LocalDate periodEnd) {}

  /** 标题弱匹配结果。 */
  private record TitleMatch(boolean exact) {}

  /**
   * 算出来的候选（落库前的形态）。
   *
   * <p>刻意用「普通类 + 字段」而不是 record：排序分、价格序这些是**算完才知道**的，
   * record 的不可变语义在这里只会逼出一堆 withXxx 拷贝。
   */
  private static final class Computed {
    final UUID itemId;
    final UUID snapshotId;
    final Integer snapshotSeq;
    final UUID stallId;
    final String stallName;
    final String platform;
    final String platformItemId;
    final UUID supplierId;
    final String supplierCode;
    final String supplierName;
    final String title;
    final String spec;
    final String imageUrl;
    final BigDecimal priceMin;
    final BigDecimal priceMax;
    final String currency;
    final BigDecimal moq;
    final OffsetDateTime collectedAt;
    final boolean onSale;
    final String matchType;
    final BigDecimal confidence;
    final String matchBasis;
    final boolean requiresManualConfirmation;
    final int historyOrderCount;
    final BigDecimal historyQuantity;
    final BigDecimal leadTimeDays;
    final BigDecimal ratingObjectiveScore;
    final BigDecimal ratingFinalScore;
    final String ratingGrade;
    final Boolean ratingManuallyAdjusted;
    final LocalDate ratingPeriodStart;
    final LocalDate ratingPeriodEnd;
    final String referenceImageUrl;

    Integer priceRank;
    boolean lowestPrice;
    BigDecimal score = BigDecimal.ZERO;
    BigDecimal priceScore = NEUTRAL;
    int sortRank;

    private Computed(
        UUID itemId,
        UUID snapshotId,
        Integer snapshotSeq,
        UUID stallId,
        String stallName,
        String platform,
        String platformItemId,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String title,
        String spec,
        String imageUrl,
        BigDecimal priceMin,
        BigDecimal priceMax,
        String currency,
        BigDecimal moq,
        OffsetDateTime collectedAt,
        boolean onSale,
        String matchType,
        BigDecimal confidence,
        String matchBasis,
        boolean requiresManualConfirmation,
        int historyOrderCount,
        BigDecimal historyQuantity,
        BigDecimal leadTimeDays,
        BigDecimal ratingObjectiveScore,
        BigDecimal ratingFinalScore,
        String ratingGrade,
        Boolean ratingManuallyAdjusted,
        LocalDate ratingPeriodStart,
        LocalDate ratingPeriodEnd,
        String referenceImageUrl) {
      this.itemId = itemId;
      this.snapshotId = snapshotId;
      this.snapshotSeq = snapshotSeq;
      this.stallId = stallId;
      this.stallName = stallName;
      this.platform = platform;
      this.platformItemId = platformItemId;
      this.supplierId = supplierId;
      this.supplierCode = supplierCode;
      this.supplierName = supplierName;
      this.title = title;
      this.spec = spec;
      this.imageUrl = imageUrl;
      this.priceMin = priceMin;
      this.priceMax = priceMax;
      this.currency = currency;
      this.moq = moq;
      this.collectedAt = collectedAt;
      this.onSale = onSale;
      this.matchType = matchType;
      this.confidence = confidence;
      this.matchBasis = matchBasis;
      this.requiresManualConfirmation = requiresManualConfirmation;
      this.historyOrderCount = historyOrderCount;
      this.historyQuantity = historyQuantity;
      this.leadTimeDays = leadTimeDays;
      this.ratingObjectiveScore = ratingObjectiveScore;
      this.ratingFinalScore = ratingFinalScore;
      this.ratingGrade = ratingGrade;
      this.ratingManuallyAdjusted = ratingManuallyAdjusted;
      this.ratingPeriodStart = ratingPeriodStart;
      this.ratingPeriodEnd = ratingPeriodEnd;
      this.referenceImageUrl = referenceImageUrl;
    }
  }
}
