package com.starcon.crm;

import static com.starcon.crm.ReviewRequestModels.*;

import com.starcon.crm.ReviewRequestModels.CrmReviewBatchInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewBatchItem;
import com.starcon.crm.ReviewRequestModels.CrmReviewBatchResult;
import com.starcon.crm.ReviewRequestModels.CrmReviewBuyerBinding;
import com.starcon.crm.ReviewRequestModels.CrmReviewCandidate;
import com.starcon.crm.ReviewRequestModels.CrmReviewCandidatePage;
import com.starcon.crm.ReviewRequestModels.CrmReviewCapabilityDimension;
import com.starcon.crm.ReviewRequestModels.CrmReviewCapabilities;
import com.starcon.crm.ReviewRequestModels.CrmReviewCreateInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewFrequencyPolicy;
import com.starcon.crm.ReviewRequestModels.CrmReviewPolicyInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewRatingBucket;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecovery;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecoveryInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecoveryStats;
import com.starcon.crm.ReviewRequestModels.CrmReviewRequestDetail;
import com.starcon.crm.ReviewRequestModels.CrmReviewRequestRow;
import com.starcon.crm.ReviewRequestModels.CrmReviewRuleInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewSendRecord;
import com.starcon.crm.ReviewRequestModels.CrmReviewSendRecordInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewShopBucket;
import com.starcon.crm.ReviewRequestModels.CrmReviewShopView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 15.2.2.1 Request a Review（索评）：按规则向**已签收订单的买家**发起索评，
 * 控制频次避免打扰，支持按店铺 / 时间 / 物流状态批量执行。
 *
 * <p>蓝图 15 卷 15.2.2.1 原话：
 *
 * <blockquote>
 *
 * 职责：按规则向已签收订单的买家发起索评（平台允许范围内），控制频次避免打扰， 支持按店铺/时间/物流状态批量执行。 输入：索评规则（时点、范围、频次上限）；已签收订单集合。 输出：索评任务 + 发送记录 + 评价回收统计。 关系：上游 = 10.3.2.2 签收；下游 = 15.2.1.1 评价。 <b>索评频次必须受限</b>，过度索评会被买家投诉甚至平台处罚。
 *
 * </blockquote>
 *
 * <p><b>加粗句「索评频次必须受限」在本服务落成三层</b>：
 *
 * <ol>
 *   <li><b>租户级硬上限</b>（{@code crm_review_frequency_policy}）：每买家上限、每订单上限、冷却窗口下限、时点下限、单批上限，每个值在 DB 层另有天花板。
 *   <li><b>DB 触发器</b>（{@code guard_crm_review_request_frequency}）：写入索评请求时在数据库层判定三件事（每订单上限 / 每买家上限 / 冷却窗口），并强制「本次规则只能比硬上限更严」。
 *   <li><b>服务端预检 + 留痕</b>（本类）：用**同样的口径**预检一遍，给出可读原因；超限的请求<b>不丢弃</b>，而是以 {@code status='REJECTED'} + 原因落库。
 * </ol>
 *
 * <p><b>跨服务纪律</b>：上游 10.3.2.2 在 <b>channel</b> 服务（V217），本服务<b>一条 SQL 都不碰</b>对方的库，
 * 只经 {@link SignoffDeliveredOrderPort} 走授权 HTTP API 读。本库只存上游的逻辑引用 + 快照。
 * 下游 15.2.1.1 评价**未交付** ⇒ 本服务只落**评价回收桶**，不假装已交付评价管理。
 */
@Service
public class ReviewRequestService {

  // ================================================================ 常量

  /** 索评请求状态（与 V220 的 CHECK 同词表）。 */
  static final List<String> REQUEST_STATUSES =
      List.of("SCHEDULED", "SENT", "CANCELLED", "REJECTED");

  /** 发送记录状态。 */
  static final List<String> SEND_STATUSES = List.of("QUEUED", "SENT", "FAILED", "CANCELLED");

  /** 触达通道。PLATFORM_API 词表先钉死，将来平台接口接入时不需要改这里的语义。 */
  static final List<String> TRANSPORTS = List.of("PLATFORM_API", "MANUAL_HANDOFF");

  /** 评价回收来源。PLATFORM_SYNC 词表先钉死（15.2.1.1 未交付）。 */
  static final List<String> RECOVERY_SOURCES = List.of("MANUAL_ENTRY", "PLATFORM_SYNC");

  /** 拒绝原因码。 */
  static final List<String> REJECT_CODES =
      List.of(
          "PER_ORDER_LIMIT",
          "PER_BUYER_LIMIT",
          "COOLDOWN_ACTIVE",
          "ALREADY_ACTIVE_FOR_CASE",
          "BATCH_SIZE_EXCEEDED",
          "NO_BUYER_BINDING",
          "NO_SALES_ORDER_NO");

  /** 统计默认时间窗（天）。 */
  static final int DEFAULT_STATS_DAYS = 30;

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort port;

  public ReviewRequestService(DSLContext sql, SignoffDeliveredOrderPort port) {
    this.sql = sql;
    this.port = port;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code candidate_shop_attribution} —— 上游确认单**不带店铺维度**，候选单据归属哪家店铺不可从上游核实；
   *   <li>{@code buyer_identity} —— 本仓**没有买家档案**（15.3.1.1 未交付），买家标识由运营提供；
   *   <li>{@code platform_review_api} —— 平台侧索评接口**未接入**，本模块**刻意不放假 vendor 实现**。
   * </ul>
   */
  public CrmReviewCapabilities capabilities() {
    List<CrmReviewCapabilityDimension> dimensions =
        List.of(
            new CrmReviewCapabilityDimension(
                "delivered_order_source",
                "🔴 已签收订单集合（索评候选集的事实来源）",
                true,
                "上游 channel V217 logistics_pickup_signoff_case，经授权 HTTP API 读（GET /channel/logistics-pickup-signoff/cases?status=DELIVERED）",
                "10.3.2.2",
                "候选集**只**来自真实签收确认：未签收的件进不来，索评时点也从上游签收时刻起算"),
            new CrmReviewCapabilityDimension(
                "shop_directory",
                "店铺目录（索评活动的目标店铺）",
                true,
                "上游 channel V1 shop，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "目标店铺从**真实清单**里选，不是前端写死的常量"),
            new CrmReviewCapabilityDimension(
                "candidate_shop_attribution",
                "候选单据的店铺归属",
                false,
                "上游确认单**不带店铺维度**（只有物流渠道 channel_id）",
                "10.3.2.2",
                "批次按「本次索评活动的目标店铺」记录，**不能**按店铺过滤上游候选；"
                    + "页面必须如实说明这一点，不能让运营以为筛过了"),
            new CrmReviewCapabilityDimension(
                "buyer_identity",
                "买家标识（每买家上限与冷却窗口的去重键）",
                false,
                "本仓**没有买家档案**（15.3.1.1 客户档案未交付），trade V135 同款纪律：买家联系方式归 15 / 客户档案所有",
                "15.3.1.1",
                "买家标识由运营按店铺提供，**只作频次去重键**；"
                    + "批量执行时缺买家标识的候选单会被**跳过并列出原因**，不会被悄悄放行"),
            new CrmReviewCapabilityDimension(
                "platform_review_api",
                "平台侧索评接口（Amazon Request a Review 之类）",
                false,
                "平台适配器在 channel 服务，本仓**没有**该 vendor 实现",
                "2",
                "发送记录只允许 MANUAL_HANDOFF（人工交接）；"
                    + "一个「返回成功回执」的假通道会让「买家已经被索评过」在数据上看起来发生过，"
                    + "而过度索评的代价正是买家投诉与平台处罚"),
            new CrmReviewCapabilityDimension(
                "frequency_control",
                "🔴 索评频次受限（每买家上限 / 每订单上限 / 冷却窗口 / 时点下限）",
                true,
                "本服务 V220 crm_review_frequency_policy + guard_crm_review_request_frequency 触发器 + 服务端预检",
                "",
                "上限在 **DB 层**判定：规则快照只能比租户硬上限更严；"
                    + "超限的请求被**拒绝并留下原因**（status=REJECTED + reject_reason），不是界面上的一行提示"),
            new CrmReviewCapabilityDimension(
                "review_recovery",
                "评价回收桶（收到的评价原文 + 关联到索评请求）",
                true,
                "本服务 V220 crm_review_recovery",
                "",
                "回收明细可算平均星级、星级分布与回收率；**本模块只落回收桶**，"
                    + "评价的回复 / 申诉 / 标签都不在这里"),
            new CrmReviewCapabilityDimension(
                "review_sync",
                "平台评价同步（自动把平台评价拉进来）",
                false,
                "2 同步未交付平台评价；15.2.1.1 评价列表未交付",
                "15.2.1.1",
                "回收只能**人工登记**（source=MANUAL_ENTRY）；"
                    + "本模块**不假装**已交付评价管理（无回复、无申诉、无标签）"),
            new CrmReviewCapabilityDimension(
                "review_policy_config",
                "索评策略配置（按店铺 / 按物流方式的延后时间、避开节假日、A/B 话术）",
                false,
                "15.2.2.2 索评规则未交付",
                "15.2.2.2",
                "本模块的规则是**一次请求自带的快照**；租户级频次硬上限是**兜底闸门**，"
                    + "15.2.2.2 落地后也改不动那份硬天花板"));
    return new CrmReviewCapabilities(
        dimensions,
        REQUEST_STATUSES,
        SEND_STATUSES,
        TRANSPORTS,
        RECOVERY_SOURCES,
        REJECT_CODES,
        "上游 10.3.2.2 在 **channel** 服务，本服务一条 SQL 都不碰对方的库，只经授权 HTTP API 读；"
            + "本库只存上游的逻辑引用 + 快照（V220 第一节）；"
            + "下游 15.2.1.1 评价未交付，**只落回收桶**。",
        "🔴 索评频次必须受限：上限在 DB 层判定，规则快照只能比租户硬上限更严，"
            + "超限的索评请求**被拒绝并留下原因**（status=REJECTED + reject_reason），"
            + "而不是界面上的一行提示。");
  }

  // ================================================================ 频次硬上限（租户级）

  /** 当前租户的频次硬上限；没配置过返回 null（**不返回默认值冒充已配置**）。 */
  public CrmReviewFrequencyPolicy frequencyPolicy() {
    var row =
        sql.fetchOne(
            "select id,max_per_buyer_per_window,window_days,min_cooldown_hours,max_per_order,"
                + " min_delay_hours,max_batch_size,updated_at"
                + " from crm_review_frequency_policy where tenant_id = ?",
            currentTenant());
    return row == null ? null : policy(row);
  }

  /**
   * 配置 / 更新本租户的频次硬上限（幂等 upsert，一租户一行）。
   *
   * <p>每个值在 DB 层另有天花板（每买家上限最多 10 次/窗口等），
   * 所以「把上限调到 999」这条路本身也被堵死 —— 这是加粗句的兜底。
   */
  @Transactional
  public CrmReviewFrequencyPolicy savePolicy(CrmReviewPolicyInput input) {
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_frequency_policy"
            + "(id,tenant_id,max_per_buyer_per_window,window_days,min_cooldown_hours,max_per_order,"
            + " min_delay_hours,max_batch_size,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id) do update set"
            + " max_per_buyer_per_window=excluded.max_per_buyer_per_window,"
            + " window_days=excluded.window_days,"
            + " min_cooldown_hours=excluded.min_cooldown_hours,"
            + " max_per_order=excluded.max_per_order,"
            + " min_delay_hours=excluded.min_delay_hours,"
            + " max_batch_size=excluded.max_batch_size,"
            + " updated_at=excluded.updated_at",
        id,
        currentTenant(),
        input.maxPerBuyerPerWindow(),
        input.windowDays(),
        input.minCooldownHours(),
        input.maxPerOrder(),
        input.minDelayHours(),
        input.maxBatchSize(),
        moment,
        moment);
    return frequencyPolicy();
  }

  // ================================================================ 上游读（跨服务端口）

  /** 已签收候选单（真分页）。上游读不到一律 503，**不返回空集合冒充「没有已签收订单」**。 */
  public CrmReviewCandidatePage candidates(
      String carrierCode, String batchNo, String keyword, int page, int size, String bearer) {
    var upstream = port.deliveredCases(carrierCode, batchNo, keyword, page, size, bearer);
    List<CrmReviewCandidate> items = new ArrayList<>();
    for (var row : upstream.items()) {
      items.add(candidate(row));
    }
    return new CrmReviewCandidatePage(
        items, upstream.page(), upstream.size(), upstream.totalElements(), upstream.totalPages(), false);
  }

  /** 店铺目录（来自 channel 的真实清单）。 */
  public List<CrmReviewShopView> shops(String bearer) {
    List<CrmReviewShopView> out = new ArrayList<>();
    for (var shop : port.shops(bearer)) {
      out.add(
          new CrmReviewShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.status())));
    }
    return out;
  }

  // ================================================================ 索评任务读取

  /** 索评任务列表：真分页 + 真筛选（店铺 / 平台 / 状态 / 买家 / 订单 / 承运商 / 创建时间窗）。 */
  public List<CrmReviewRequestRow> list(
      UUID shopId,
      String platformCode,
      String status,
      String buyerRef,
      String salesOrderNo,
      String carrierCode,
      String createdFrom,
      String createdTo,
      int page,
      int size) {
    OffsetDateTime from = optionalMoment(createdFrom, "创建起点");
    OffsetDateTime to = optionalMoment(createdTo, "创建终点");
    var rows =
        sql.fetch(
            requestColumns()
                + " where r.tenant_id = ?"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + (blankToNull(platformCode) == null ? "" : " and r.platform_code = ?")
                + (blankToNull(status) == null ? "" : " and r.status = ?")
                + (blankToNull(buyerRef) == null ? "" : " and r.buyer_ref = ?")
                + (blankToNull(salesOrderNo) == null ? "" : " and r.sales_order_no = ?")
                + (blankToNull(carrierCode) == null ? "" : " and r.upstream_carrier_code = ?")
                + (from == null ? "" : " and r.created_at >= ?::timestamptz")
                + (to == null ? "" : " and r.created_at < ?::timestamptz")
                + " order by r.created_at desc, r.id"
                + " limit ? offset ?",
            listBindings(
                shopId,
                platformCode,
                status,
                buyerRef,
                salesOrderNo,
                carrierCode,
                from,
                to,
                size <= 0 ? 50 : Math.min(size, 200),
                Math.max(page, 0) * (size <= 0 ? 50 : Math.min(size, 200))));
    return rows.map(ReviewRequestService::request);
  }

  /** 索评任务总数（与 {@link #list} 同一套筛选口径）。 */
  public long count(
      UUID shopId,
      String platformCode,
      String status,
      String buyerRef,
      String salesOrderNo,
      String carrierCode,
      String createdFrom,
      String createdTo) {
    OffsetDateTime from = optionalMoment(createdFrom, "创建起点");
    OffsetDateTime to = optionalMoment(createdTo, "创建终点");
    return sql.fetchOne(
            "select count(*) from crm_review_request r"
                + " where r.tenant_id = ?"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + (blankToNull(platformCode) == null ? "" : " and r.platform_code = ?")
                + (blankToNull(status) == null ? "" : " and r.status = ?")
                + (blankToNull(buyerRef) == null ? "" : " and r.buyer_ref = ?")
                + (blankToNull(salesOrderNo) == null ? "" : " and r.sales_order_no = ?")
                + (blankToNull(carrierCode) == null ? "" : " and r.upstream_carrier_code = ?")
                + (from == null ? "" : " and r.created_at >= ?::timestamptz")
                + (to == null ? "" : " and r.created_at < ?::timestamptz"),
            listBindings(
                shopId, platformCode, status, buyerRef, salesOrderNo, carrierCode, from, to))
        .get(0, Long.class);
  }

  /** 单条索评任务详情 = 请求本体 + 发送记录 + 评价回收。 */
  public CrmReviewRequestDetail detail(UUID requestId) {
    CrmReviewRequestRow row = requireRequest(requestId);
    return new CrmReviewRequestDetail(
        row, sendRecords(requestId), recoveries(requestId));
  }

  // ================================================================ 单条发起索评

  /**
   * 单条发起索评。
   *
   * <p>返回的行里 {@code status} 可能是 {@code REJECTED}：那表示**这次索评被频次规则挡住了**，
   * 原因写在 {@code rejectReason} 里，而这一行**已经落库**（留痕）。
   * 这不是「调用失败」—— 请求被记录了，结论是「不允许发」。
   *
   * <p>与「调用失败」区分开的是**输入本身不合法**（上游没签收 / 店铺不存在 / 规则比硬上限松）：
   * 那些直接抛 4xx，**不写行**。
   */
  @Transactional
  public CrmReviewRequestRow create(CrmReviewCreateInput input, UUID actorId, String bearer) {
    var upstream = port.caseDetail(input.upstreamCaseId(), bearer);
    if (!"DELIVERED".equals(upstream.status())
        || !"CONFIRMED".equals(upstream.deliveryState())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "上游签收确认单 "
              + nz(upstream.caseNo())
              + " 尚未签收（当前状态 "
              + nz(upstream.status())
              + "），不能对它发起索评：没签收就索评等于骚扰还没收到货的买家。");
    }
    if (nz(upstream.salesOrderNo()).isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "上游签收确认单 " + nz(upstream.caseNo()) + " 没有订单号，无法确定索评对象。");
    }
    CrmReviewShopView shop = requireShop(input.shopId(), bearer);
    CrmReviewFrequencyPolicy policy = requirePolicy();
    OffsetDateTime deliveredAt = upstream.deliveryConfirmedAt();
    if (deliveredAt == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "上游签收确认单 " + nz(upstream.caseNo()) + " 没有签收时刻，无法按时点规则排期。");
    }

    CrmReviewRuleInput rule = input.rule();
    OffsetDateTime scheduledAt = deliveredAt.plus(hours(rule.delayHours()));

    // 规则必须比租户硬上限更严 —— 与 DB 触发器同一判据，先在服务层给出可读原因。
    String looser = looserThanPolicy(rule, policy);
    if (looser != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, looser);
    }

    var verdict =
        evaluateFrequency(
            upstream.salesOrderNo(), input.buyerRef(), rule, policy, new BatchCounters());
    if (verdict.code() != null) {
      return insertRequest(
          upstream,
          shop,
          upstream.salesOrderNo(),
          input.buyerRef().trim(),
          rule,
          scheduledAt,
          deliveredAt,
          "REJECTED",
          verdict.code(),
          verdict.reason(),
          null,
          actorId,
          input.note(),
          bearer);
    }
    return insertRequest(
        upstream,
        shop,
        upstream.salesOrderNo(),
        input.buyerRef().trim(),
        rule,
        scheduledAt,
        deliveredAt,
        "SCHEDULED",
        "",
        "",
        null,
        actorId,
        input.note(),
        bearer);
  }

  // ================================================================ 批量执行

  /**
   * 批量执行：按店铺 / 时间 / 物流状态批量索评。
   *
   * <p>逐条结果都带结论：{@code CREATED} / {@code REJECTED} / {@code SKIPPED}，
   * 被频次规则挡住的与缺买家标识的**都列出原因** —— 批量执行最怕的就是
   * 「一半发出去了，另一半不知道去哪了」。
   */
  @Transactional
  public CrmReviewBatchResult batch(CrmReviewBatchInput input, UUID actorId, String bearer) {
    CrmReviewFrequencyPolicy policy = requirePolicy();
    CrmReviewShopView shop = requireShop(input.shopId(), bearer);
    OffsetDateTime from = optionalMoment(input.deliveredFrom(), "签收起点");
    OffsetDateTime to = optionalMoment(input.deliveredTo(), "签收终点");
    if (from != null && to != null && !to.isAfter(from)) {
      throw bad("签收时间窗是空的：终点「" + to + "」不晚于起点「" + from + "」。");
    }

    String looser = looserThanPolicy(input.rule(), policy);
    if (looser != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, looser);
    }

    // 买家绑定：订单号 → 买家标识。
    Map<String, String> buyers = new LinkedHashMap<>();
    for (CrmReviewBuyerBinding binding : input.buyers()) {
      buyers.put(binding.salesOrderNo().trim(), binding.buyerRef().trim());
    }

    // 扫上游已签收确认单（翻页有上限，触顶如实报 truncated）。
    List<SignoffDeliveredOrderPort.DeliveredCaseView> scanned = new ArrayList<>();
    boolean truncated = false;
    int maxPages = Math.max(1, policy.maxBatchSize() / SignoffDeliveredOrderPort.PAGE_SIZE + 1);
    for (int page = 0; page < maxPages; page++) {
      var upstream =
          port.deliveredCases(
              input.carrierCode(), input.batchNo(), null, page, SignoffDeliveredOrderPort.PAGE_SIZE, bearer);
      scanned.addAll(upstream.items());
      if (upstream.items().isEmpty() || scanned.size() >= upstream.totalElements()) {
        break;
      }
      if (page == maxPages - 1) {
        truncated = true;
      }
    }

    UUID batchId = UUID.randomUUID();
    BatchCounters counters = new BatchCounters();
    List<CrmReviewBatchItem> items = new ArrayList<>();
    int candidateCount = 0;
    int created = 0;
    int rejected = 0;
    int skipped = 0;

    for (var upstream : scanned) {
      // 时间窗过滤：按上游的**签收时刻**（闭开区间 [from, to)）。
      OffsetDateTime deliveredAt = upstream.deliveryConfirmedAt();
      if (deliveredAt == null) {
        continue;
      }
      if (from != null && deliveredAt.isBefore(from)) {
        continue;
      }
      if (to != null && !deliveredAt.isBefore(to)) {
        continue;
      }
      candidateCount++;

      String orderNo = nz(upstream.salesOrderNo()).trim();
      if (orderNo.isEmpty()) {
        skipped++;
        items.add(
            new CrmReviewBatchItem(
                "",
                "",
                nz(upstream.caseNo()),
                "SKIPPED",
                "",
                "上游签收确认单没有订单号，无法确定索评对象"));
        continue;
      }
      String buyerRef = buyers.get(orderNo);
      if (buyerRef == null || buyerRef.isBlank()) {
        skipped++;
        items.add(
            new CrmReviewBatchItem(
                orderNo,
                "",
                nz(upstream.caseNo()),
                "SKIPPED",
                "",
                "未提供该订单的买家标识：本仓没有买家档案（15.3.1.1 未交付），"
                    + "买家标识是频次去重键，缺了就没法判定「这个买家有没有被索评过」"));
        continue;
      }

      OffsetDateTime scheduledAt = deliveredAt.plus(hours(input.rule().delayHours()));
      var verdict = evaluateFrequency(orderNo, buyerRef, input.rule(), policy, counters);
      String status = verdict.code() == null ? "SCHEDULED" : "REJECTED";
      var row =
          insertRequest(
              upstream,
              shop,
              orderNo,
              buyerRef,
              input.rule(),
              scheduledAt,
              deliveredAt,
              status,
              verdict.code() == null ? "" : verdict.code(),
              verdict.code() == null ? "" : verdict.reason(),
              batchId,
              actorId,
              input.note(),
              bearer);
      if (verdict.code() == null) {
        created++;
        counters.accept(orderNo, buyerRef, deliveredAt);
      } else {
        rejected++;
      }
      items.add(
          new CrmReviewBatchItem(
              orderNo, buyerRef, nz(upstream.caseNo()), status, row.requestNo(), verdict.reason()));
    }

    return new CrmReviewBatchResult(
        batchId,
        candidateCount,
        created,
        rejected,
        skipped,
        truncated,
        truncated
            ? "本次只扫描了上游已签收确认单的前 "
                + scanned.size()
                + " 件（翻页有上限）：候选集**不完整**，请缩小时间窗或承运商范围后重跑。"
            : "本次扫描了上游已签收确认单的全部 "
                + scanned.size()
                + " 件。",
        items);
  }

  // ================================================================ 发送记录

  /**
   * 登记一次触达（发送记录）。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 一律拒绝：平台侧索评接口**未接入**。
   * 一个「返回成功回执」的假通道会让「买家已经被索评过」在数据上看起来发生过 ——
   * 而过度索评的代价正是买家投诉与平台处罚。
   */
  @Transactional
  public CrmReviewSendRecord addSendRecord(
      UUID requestId, CrmReviewSendRecordInput input, UUID actorId) {
    CrmReviewRequestRow request = requireRequest(requestId);
    String transport = oneOf(TRANSPORTS, input.transport(), "触达通道");
    if ("PLATFORM_API".equals(transport)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "平台侧索评接口**未接入**（capabilities 里 platform_review_api 维度 available=false）："
              + "本模块不允许登记一条「已发到平台」的记录 —— 那会让「买家已经被索评过」"
              + "在数据上看起来发生过。请改用 MANUAL_HANDOFF 登记人工交接。");
    }
    String outcome = oneOf(SEND_STATUSES, input.outcome(), "触达结果");
    if ("FAILED".equals(outcome) && nz(input.failureReason()).isBlank()) {
      throw bad("登记失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
    }
    if ("SENT".equals(outcome)) {
      if ("SENT".equals(request.status())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "索评请求 " + request.requestNo() + " 已经是「已发出」，不能再登记一条「已发出」："
                + "「这个买家到底收到几次」必须只有一个答案。");
      }
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_send_record"
            + "(id,tenant_id,request_id,transport,status,attempted_at,failure_reason,"
            + " operator_id,note,created_at)"
            + " values (?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        requestId,
        transport,
        outcome,
        "SENT".equals(outcome) ? moment : null,
        nz(input.failureReason()),
        actorId,
        nz(input.note()),
        moment);
    if ("SENT".equals(outcome)) {
      sql.execute(
          "update crm_review_request set status='SENT', updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          moment,
          currentTenant(),
          requestId);
    }
    return sendRecordById(id);
  }

  // ================================================================ 评价回收

  /**
   * 登记一条评价回收（收到的评价原文 + 关联到索评请求）。
   *
   * <p>🔴 {@code source=PLATFORM_SYNC} 一律拒绝：15.2.1.1 评价列表未交付，
   * 本模块**不假装**已交付评价管理。
   */
  @Transactional
  public CrmReviewRecovery addRecovery(
      UUID requestId, CrmReviewRecoveryInput input, UUID actorId) {
    CrmReviewRequestRow request = requireRequest(requestId);
    if ("REJECTED".equals(request.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "索评请求 " + request.requestNo() + " 是被频次规则**拒绝**的（" + request.rejectReason() + "），"
              + "没有真正发出去过，不可能有评价回收。");
    }
    OffsetDateTime receivedAt = parseMoment(input.receivedAt(), "评价时刻");
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_recovery"
            + "(id,tenant_id,request_id,rating,content,has_image,has_video,platform_code,source,"
            + " received_at,recorded_by,note,created_at)"
            + " values (?,?,?,?,?,?,?,?,'MANUAL_ENTRY',?::timestamptz,?,?,?::timestamptz)",
        id,
        currentTenant(),
        requestId,
        input.rating(),
        nz(input.content()),
        Boolean.TRUE.equals(input.hasImage()),
        Boolean.TRUE.equals(input.hasVideo()),
        request.platformCode(),
        receivedAt,
        actorId,
        nz(input.note()),
        moment);
    // 有评价回收 ⇒ 这次索评确实发生过（否则买家无从评价）。
    if ("SCHEDULED".equals(request.status())) {
      sql.execute(
          "update crm_review_request set status='SENT', updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          moment,
          currentTenant(),
          requestId);
    }
    return recoveryById(id);
  }

  // ================================================================ 评价回收统计

  /**
   * 评价回收统计（= 蓝图输出句里的「评价回收统计」）。
   *
   * <p>口径全部来自**真实明细**：请求数来自 {@code crm_review_request}，
   * 回收数来自 {@code crm_review_recovery}。回收率的分母是**已发出**的索评数，
   * 分母为 0 时**如实报 null**（「尚无已发送的索评」）而不是报 0% ——
   * 0% 会被读成「发出去了但一个评价都没回来」，那是两件完全不同的事。
   */
  public CrmReviewRecoveryStats recoveryStats(Integer days, UUID shopId) {
    int window = days == null || days <= 0 ? DEFAULT_STATS_DAYS : Math.min(days, 365);
    OffsetDateTime from = now().minus(Duration.ofDays(window));
    OffsetDateTime to = now();

    String shopClause = shopId == null ? "" : " and shop_id = ?";
    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='SCHEDULED') as scheduled,"
                + " count(*) filter (where status='SENT') as sent,"
                + " count(*) filter (where status='CANCELLED') as cancelled,"
                + " count(*) filter (where status='REJECTED') as rejected"
                + " from crm_review_request"
                + " where tenant_id = ? and created_at >= ?::timestamptz and created_at < ?::timestamptz"
                + shopClause,
            countBindings(from, to, shopId));
    long total = nz(counts.get("total", Long.class));
    long scheduled = nz(counts.get("scheduled", Long.class));
    long sent = nz(counts.get("sent", Long.class));
    long cancelled = nz(counts.get("cancelled", Long.class));
    long rejected = nz(counts.get("rejected", Long.class));

    var recovery =
        sql.fetchOne(
            "select count(*) as records, count(distinct request_id) as requests,"
                + " avg(rating) as average"
                + " from crm_review_recovery rc"
                + " join crm_review_request r on r.tenant_id = rc.tenant_id and r.id = rc.request_id"
                + " where r.tenant_id = ? and r.created_at >= ?::timestamptz and r.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and r.shop_id = ?"),
            countBindings(from, to, shopId));
    long recoveryRecords = nz(recovery.get("records", Long.class));
    long recoveredRequests = nz(recovery.get("requests", Long.class));
    var average = recovery.get("average", BigDecimal.class);
    BigDecimal averageRating =
        average == null ? null : average.setScale(2, RoundingMode.HALF_UP);

    BigDecimal recoveryRate =
        sent <= 0
            ? null
            : BigDecimal.valueOf(recoveredRequests)
                .divide(BigDecimal.valueOf(sent), 4, RoundingMode.HALF_UP);

    List<CrmReviewRatingBucket> distribution = new ArrayList<>();
    var buckets =
        sql.fetch(
            "select rc.rating as rating, count(*) as count"
                + " from crm_review_recovery rc"
                + " join crm_review_request r on r.tenant_id = rc.tenant_id and r.id = rc.request_id"
                + " where r.tenant_id = ? and r.created_at >= ?::timestamptz and r.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + " group by rc.rating order by rc.rating",
            countBindings(from, to, shopId));
    Map<Integer, Long> byRating = new HashMap<>();
    for (Record r : buckets) {
      byRating.put(r.get("rating", Integer.class), nz(r.get("count", Long.class)));
    }
    for (int rating = 1; rating <= 5; rating++) {
      distribution.add(new CrmReviewRatingBucket(rating, byRating.getOrDefault(rating, 0L)));
    }

    List<CrmReviewShopBucket> byShop = new ArrayList<>();
    var shopRows =
        sql.fetch(
            "select r.shop_id, max(r.shop_name) as shop_name, max(r.platform_code) as platform_code,"
                + " count(*) as requests,"
                + " count(distinct rc.request_id) as recovered,"
                + " avg(rc.rating) as average"
                + " from crm_review_request r"
                + " left join crm_review_recovery rc"
                + "   on rc.tenant_id = r.tenant_id and rc.request_id = r.id"
                + " where r.tenant_id = ? and r.created_at >= ?::timestamptz and r.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + " group by r.shop_id order by requests desc, r.shop_id",
            countBindings(from, to, shopId));
    for (Record r : shopRows) {
      var avg = r.get("average", BigDecimal.class);
      byShop.add(
          new CrmReviewShopBucket(
              r.get("shop_id", UUID.class),
              nz(r.get("shop_name", String.class)),
              nz(r.get("platform_code", String.class)),
              nz(r.get("requests", Long.class)),
              nz(r.get("recovered", Long.class)),
              avg == null ? null : avg.setScale(2, RoundingMode.HALF_UP)));
    }

    return new CrmReviewRecoveryStats(
        window,
        from.toString(),
        to.toString(),
        total,
        scheduled,
        sent,
        cancelled,
        rejected,
        recoveredRequests,
        recoveryRecords,
        recoveryRate,
        averageRating,
        distribution,
        byShop,
        sent <= 0
            ? "本次时间窗内**没有已发出的索评**，回收率**不计算**（报空而不是报 0%："
                + "0% 会被读成「发出去了但一个评价都没回来」）。"
            : "回收率 = 已有回收的索评数 / 已发出的索评数；"
                + "被频次规则拒绝的请求**不计入**分母（它们没有发出去）。");
  }

  // ================================================================ 内部

  /** 频次判定的结论：{@code code == null} 表示通过。 */
  private record FrequencyVerdict(String code, String reason) {}

  /** 批次内已受理的计数（与已落库的行叠加，DB 触发器在同一事务里也能看到它们）。 */
  private static final class BatchCounters {
    final Map<String, Integer> byOrder = new HashMap<>();
    final Map<String, Integer> byBuyer = new HashMap<>();
    final Map<String, OffsetDateTime> lastBuyerAt = new HashMap<>();

    void accept(String salesOrderNo, String buyerRef, OffsetDateTime at) {
      byOrder.merge(salesOrderNo, 1, Integer::sum);
      byBuyer.merge(buyerRef, 1, Integer::sum);
      lastBuyerAt.merge(buyerRef, at, (a, b) -> a.isAfter(b) ? a : b);
    }
  }

  /**
   * 频次预检 —— 与 V220 的 {@code guard_crm_review_request_frequency} **同一判据**。
   *
   * <p>为什么服务端要再判一遍：DB 触发器抛的异常只有一句人话，而运营需要知道
   * 「是每买家上限挡的、还是冷却窗口没到、还是每订单上限」—— 三者的处置动作完全不同。
   * 服务层先判一遍给出**可分类的原因码**，DB 触发器仍然是最后一道闸。
   */
  private FrequencyVerdict evaluateFrequency(
      String salesOrderNo,
      String buyerRef,
      CrmReviewRuleInput rule,
      CrmReviewFrequencyPolicy policy,
      BatchCounters counters) {
    UUID tenant = currentTenant();

    long orderCountDb =
        sql.fetchOne(
                "select count(*) from crm_review_request"
                    + " where tenant_id = ? and sales_order_no = ? and status in ('SCHEDULED','SENT')",
                tenant,
                salesOrderNo)
            .get(0, Long.class);
    int orderCount = (int) orderCountDb + counters.byOrder.getOrDefault(salesOrderNo, 0);
    if (orderCount >= rule.maxPerOrder()) {
      return new FrequencyVerdict(
          "PER_ORDER_LIMIT",
          "订单 "
              + salesOrderNo
              + " 已经索评 "
              + orderCount
              + " 次，达到本次规则的每订单上限 "
              + rule.maxPerOrder()
              + " 次：同一张单子反复索评会被买家投诉，也可能触发平台处罚。"
              + "请跳过该订单，或由运营显式调整上限（不得超过租户硬上限 "
              + policy.maxPerOrder()
              + " 次）。");
    }

    long buyerCountDb =
        sql.fetchOne(
                "select count(*) from crm_review_request"
                    + " where tenant_id = ? and buyer_ref = ? and status in ('SCHEDULED','SENT')"
                    + "   and created_at >= now() - make_interval(days => ?::int)",
                tenant,
                buyerRef,
                policy.windowDays())
            .get(0, Long.class);
    int buyerCount = (int) buyerCountDb + counters.byBuyer.getOrDefault(buyerRef, 0);
    if (buyerCount >= rule.maxPerBuyer()) {
      return new FrequencyVerdict(
          "PER_BUYER_LIMIT",
          "买家 "
              + buyerRef
              + " 在最近 "
              + policy.windowDays()
              + " 天内已经被索评 "
              + buyerCount
              + " 次，达到本次规则的每买家上限 "
              + rule.maxPerBuyer()
              + " 次：过度索评会被买家投诉甚至平台处罚。"
              + "请延长冷却窗口、跳过该买家，或由运营显式调整上限（不得超过租户硬上限 "
              + policy.maxPerBuyerPerWindow()
              + " 次）。");
    }

    var lastRow =
        sql.fetchOne(
            "select max(created_at) as last_at from crm_review_request"
                + " where tenant_id = ? and buyer_ref = ? and status in ('SCHEDULED','SENT')",
            tenant,
            buyerRef);
    OffsetDateTime lastAt = lastRow == null ? null : lastRow.get("last_at", OffsetDateTime.class);
    OffsetDateTime inBatch = counters.lastBuyerAt.get(buyerRef);
    if (inBatch != null && (lastAt == null || inBatch.isAfter(lastAt))) {
      lastAt = inBatch;
    }
    if (lastAt != null) {
      OffsetDateTime unlockAt = lastAt.plus(hours(rule.cooldownHours()));
      if (now().isBefore(unlockAt)) {
        return new FrequencyVerdict(
            "COOLDOWN_ACTIVE",
            "买家 "
                + buyerRef
                + " 最近一次索评在 "
                + lastAt
                + "，冷却窗口 "
                + rule.cooldownHours()
                + " 小时还没到（最早可再索评时刻 "
                + unlockAt
                + "）：冷却窗口内再索评就是打扰买家，过度索评会被买家投诉甚至平台处罚。");
      }
    }
    return new FrequencyVerdict(null, "");
  }

  /** 规则快照比租户硬上限更松时给出可读原因（返回 null 表示没问题）。 */
  private String looserThanPolicy(CrmReviewRuleInput rule, CrmReviewFrequencyPolicy policy) {
    if (rule.maxPerBuyer() > policy.maxPerBuyerPerWindow()) {
      return "本次规则的每买家上限 "
          + rule.maxPerBuyer()
          + " 次比本租户的硬上限 "
          + policy.maxPerBuyerPerWindow()
          + " 次更松：规则只能比硬上限更严，不能更松。";
    }
    if (rule.maxPerOrder() > policy.maxPerOrder()) {
      return "本次规则的每订单上限 "
          + rule.maxPerOrder()
          + " 次比本租户的硬上限 "
          + policy.maxPerOrder()
          + " 次更松：规则只能比硬上限更严，不能更松。";
    }
    if (rule.cooldownHours().compareTo(policy.minCooldownHours()) < 0) {
      return "本次规则的冷却窗口 "
          + rule.cooldownHours()
          + " 小时比本租户的下限 "
          + policy.minCooldownHours()
          + " 小时更短：冷却窗口只能更长，不能更短。";
    }
    if (rule.delayHours().compareTo(policy.minDelayHours()) < 0) {
      return "本次规则的时点 "
          + rule.delayHours()
          + " 小时比本租户的下限 "
          + policy.minDelayHours()
          + " 小时更早：刚签收就索评会打扰买家，时点只能更晚，不能更早。";
    }
    return null;
  }

  private CrmReviewFrequencyPolicy requirePolicy() {
    CrmReviewFrequencyPolicy policy = frequencyPolicy();
    if (policy == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "本租户尚未配置索评频次策略：索评频次必须受限，没有硬上限就不允许发起索评。"
              + "请先调用 PUT /crm/review-requests/frequency-policy 配置每买家上限、冷却窗口与时点下限。");
    }
    return policy;
  }

  /** 目标店铺必须在 channel 的真实店铺清单里（否则运营会对一家不存在的店发起索评）。 */
  private CrmReviewShopView requireShop(UUID shopId, String bearer) {
    for (CrmReviewShopView shop : shops(bearer)) {
      if (shop.id().equals(shopId)) {
        return shop;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "店铺 " + shopId + " 不在上游的店铺清单里：请从真实店铺目录里选一个目标店铺。");
  }

  private CrmReviewRequestRow insertRequest(
      SignoffDeliveredOrderPort.DeliveredCaseView upstream,
      CrmReviewShopView shop,
      String salesOrderNo,
      String buyerRef,
      CrmReviewRuleInput rule,
      OffsetDateTime scheduledAt,
      OffsetDateTime deliveredAt,
      String status,
      String rejectCode,
      String rejectReason,
      UUID batchId,
      UUID actorId,
      String note,
      String bearer) {
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    String requestNo = nextRequestNo();
    try {
      sql.execute(
          "insert into crm_review_request"
              + "(id,tenant_id,request_no,upstream_case_id,upstream_case_no,upstream_waybill_no,"
              + " upstream_carrier_code,upstream_source_ref,shop_id,shop_name,platform_code,"
              + " sales_order_no,buyer_ref,logistics_status,delivered_at,status,rule_delay_hours,"
              + " rule_cooldown_hours,rule_max_per_buyer,rule_max_per_order,scheduled_at,"
              + " reject_code,reject_reason,batch_id,created_by,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?::timestamptz,"
              + "?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          requestNo,
          upstream.id(),
          nz(upstream.caseNo()),
          nz(upstream.masterWaybillNo()),
          nz(upstream.carrierCode()),
          SignoffDeliveredOrderPort.CASES_PATH + "/" + upstream.id(),
          shop.id(),
          shop.name(),
          shop.platformCode(),
          salesOrderNo,
          buyerRef,
          nz(upstream.status()),
          deliveredAt,
          status,
          rule.delayHours(),
          rule.cooldownHours(),
          rule.maxPerBuyer(),
          rule.maxPerOrder(),
          scheduledAt,
          rejectCode,
          rejectReason,
          batchId,
          actorId,
          nz(note),
          moment,
          moment);
    } catch (org.jooq.exception.DataAccessException e) {
      // DB 触发器是最后一道闸：它拦下来的是服务端预检与并发写入之间的差异。
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "索评请求被数据库层的频次规则拒绝：" + rootMessage(e),
          e);
    }
    return requireRequest(id);
  }

  /** 请求号：RR-yyyyMMdd-000001（序列单独授权，见 V220 授权段）。 */
  private String nextRequestNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_review_request_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "RR-" + day + "-" + String.format("%06d", seq);
  }

  private CrmReviewRequestRow requireRequest(UUID requestId) {
    var row =
        sql.fetchOne(
            requestColumns() + " where r.tenant_id = ? and r.id = ?", currentTenant(), requestId);
    if (row == null) throw notFound("索评请求不存在");
    return request(row);
  }

  private List<CrmReviewSendRecord> sendRecords(UUID requestId) {
    return sql.fetch(
            "select s.id,s.request_id,s.transport,s.status,s.attempted_at,s.failure_reason,"
                + " s.operator_id,s.note,s.created_at,r.request_no"
                + " from crm_review_send_record s"
                + " join crm_review_request r on r.tenant_id = s.tenant_id and r.id = s.request_id"
                + " where s.tenant_id = ? and s.request_id = ? order by s.created_at desc, s.id",
            currentTenant(),
            requestId)
        .map(ReviewRequestService::sendRecord);
  }

  private List<CrmReviewRecovery> recoveries(UUID requestId) {
    return sql.fetch(
            "select rc.id,rc.request_id,rc.rating,rc.content,rc.has_image,rc.has_video,"
                + " rc.platform_code,rc.source,rc.received_at,rc.recorded_by,rc.note,rc.created_at,"
                + " r.request_no"
                + " from crm_review_recovery rc"
                + " join crm_review_request r on r.tenant_id = rc.tenant_id and r.id = rc.request_id"
                + " where rc.tenant_id = ? and rc.request_id = ? order by rc.received_at desc, rc.id",
            currentTenant(),
            requestId)
        .map(ReviewRequestService::recovery);
  }

  private CrmReviewSendRecord sendRecordById(UUID id) {
    var row =
        sql.fetchOne(
            "select s.id,s.request_id,s.transport,s.status,s.attempted_at,s.failure_reason,"
                + " s.operator_id,s.note,s.created_at,r.request_no"
                + " from crm_review_send_record s"
                + " join crm_review_request r on r.tenant_id = s.tenant_id and r.id = s.request_id"
                + " where s.tenant_id = ? and s.id = ?",
            currentTenant(),
            id);
    if (row == null) throw notFound("发送记录不存在");
    return sendRecord(row);
  }

  private CrmReviewRecovery recoveryById(UUID id) {
    var row =
        sql.fetchOne(
            "select rc.id,rc.request_id,rc.rating,rc.content,rc.has_image,rc.has_video,"
                + " rc.platform_code,rc.source,rc.received_at,rc.recorded_by,rc.note,rc.created_at,"
                + " r.request_no"
                + " from crm_review_recovery rc"
                + " join crm_review_request r on r.tenant_id = rc.tenant_id and r.id = rc.request_id"
                + " where rc.tenant_id = ? and rc.id = ?",
            currentTenant(),
            id);
    if (row == null) throw notFound("评价回收记录不存在");
    return recovery(row);
  }

  private static String requestColumns() {
    return "select r.id,r.request_no,r.upstream_case_id,r.upstream_case_no,r.upstream_waybill_no,"
        + " r.upstream_carrier_code,r.upstream_source_ref,r.shop_id,r.shop_name,r.platform_code,"
        + " r.sales_order_no,r.buyer_ref,r.logistics_status,r.delivered_at,r.status,"
        + " r.rule_delay_hours,r.rule_cooldown_hours,r.rule_max_per_buyer,r.rule_max_per_order,"
        + " r.scheduled_at,r.reject_code,r.reject_reason,r.batch_id,r.created_by,r.note,"
        + " r.created_at,r.updated_at,"
        + " (select count(*) from crm_review_send_record s"
        + "   where s.tenant_id = r.tenant_id and s.request_id = r.id) as send_record_count,"
        + " (select count(*) from crm_review_recovery rc"
        + "   where rc.tenant_id = r.tenant_id and rc.request_id = r.id) as recovery_count"
        + " from crm_review_request r";
  }

  private static CrmReviewRequestRow request(Record r) {
    return new CrmReviewRequestRow(
        r.get("id", UUID.class),
        r.get("request_no", String.class),
        r.get("upstream_case_id", UUID.class),
        nz(r.get("upstream_case_no", String.class)),
        nz(r.get("upstream_waybill_no", String.class)),
        nz(r.get("upstream_carrier_code", String.class)),
        nz(r.get("upstream_source_ref", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        r.get("sales_order_no", String.class),
        r.get("buyer_ref", String.class),
        nz(r.get("logistics_status", String.class)),
        str(r.get("delivered_at", OffsetDateTime.class)),
        r.get("status", String.class),
        r.get("rule_delay_hours", BigDecimal.class),
        r.get("rule_cooldown_hours", BigDecimal.class),
        r.get("rule_max_per_buyer", Integer.class),
        r.get("rule_max_per_order", Integer.class),
        str(r.get("scheduled_at", OffsetDateTime.class)),
        nz(r.get("reject_code", String.class)),
        nz(r.get("reject_reason", String.class)),
        r.get("batch_id", UUID.class),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)),
        nz(r.get("send_record_count", Long.class)),
        nz(r.get("recovery_count", Long.class)));
  }

  private static CrmReviewSendRecord sendRecord(Record r) {
    return new CrmReviewSendRecord(
        r.get("id", UUID.class),
        r.get("request_id", UUID.class),
        nz(r.get("request_no", String.class)),
        r.get("transport", String.class),
        r.get("status", String.class),
        str(r.get("attempted_at", OffsetDateTime.class)),
        nz(r.get("failure_reason", String.class)),
        r.get("operator_id", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static CrmReviewRecovery recovery(Record r) {
    return new CrmReviewRecovery(
        r.get("id", UUID.class),
        r.get("request_id", UUID.class),
        nz(r.get("request_no", String.class)),
        r.get("rating", Integer.class),
        nz(r.get("content", String.class)),
        Boolean.TRUE.equals(r.get("has_image", Boolean.class)),
        Boolean.TRUE.equals(r.get("has_video", Boolean.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("source", String.class)),
        str(r.get("received_at", OffsetDateTime.class)),
        r.get("recorded_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static CrmReviewFrequencyPolicy policy(Record r) {
    return new CrmReviewFrequencyPolicy(
        r.get("id", UUID.class),
        r.get("max_per_buyer_per_window", Integer.class),
        r.get("window_days", Integer.class),
        r.get("min_cooldown_hours", BigDecimal.class),
        r.get("max_per_order", Integer.class),
        r.get("min_delay_hours", BigDecimal.class),
        r.get("max_batch_size", Integer.class),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewCandidate candidate(SignoffDeliveredOrderPort.DeliveredCaseView v) {
    return new CrmReviewCandidate(
        v.id(),
        nz(v.caseNo()),
        nz(v.masterWaybillNo()),
        nz(v.carrierCode()),
        nz(v.sourceBatchNo()),
        nz(v.salesOrderNo()),
        v.channelId(),
        nz(v.destinationCountry()),
        str(v.handoverAt()),
        str(v.deliveryConfirmedAt()),
        nz(v.status()),
        nz(v.deliveryState()));
  }

  private Object[] listBindings(
      UUID shopId,
      String platformCode,
      String status,
      String buyerRef,
      String salesOrderNo,
      String carrierCode,
      OffsetDateTime from,
      OffsetDateTime to,
      Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    if (shopId != null) values.add(shopId);
    String platform = blankToNull(platformCode);
    if (platform != null) values.add(platform);
    String st = blankToNull(status);
    if (st != null) values.add(st);
    String buyer = blankToNull(buyerRef);
    if (buyer != null) values.add(buyer);
    String order = blankToNull(salesOrderNo);
    if (order != null) values.add(order);
    String carrier = blankToNull(carrierCode);
    if (carrier != null) values.add(carrier);
    if (from != null) values.add(from);
    if (to != null) values.add(to);
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] countBindings(OffsetDateTime from, OffsetDateTime to, UUID shopId) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    values.add(from);
    values.add(to);
    if (shopId != null) values.add(shopId);
    return values.toArray();
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 小时数 → Duration（BigDecimal 小时 → 纳秒，不经 float，保留 6 位小数精度）。 */
  private static Duration hours(BigDecimal value) {
    return Duration.ofNanos(
        value.multiply(BigDecimal.valueOf(3_600_000_000_000L)).longValueExact());
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，写进去的签收时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  /** 可选时刻：空白即「不筛」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  private static String oneOf(List<String> allowed, String value, String label) {
    String candidate = nz(value).trim();
    if (!allowed.contains(candidate)) {
      throw bad(label + "「" + candidate + "」不在允许取值里：" + String.join(" / ", allowed));
    }
    return candidate;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  /** 从 DataAccessException 里取最内层的人话（PG 的 RAISE EXCEPTION 消息）。 */
  private static String rootMessage(Throwable e) {
    Throwable current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null ? e.getMessage() : message;
  }
}
