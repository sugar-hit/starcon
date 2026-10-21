package com.starcon.catalog;

import static com.starcon.catalog.PlatformPriceSyncModels.*;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceDiffOutcome;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceDiffSource;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleOutcome;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleStatus;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceSyncOutcome;
import com.starcon.catalog.PlatformPriceSyncPort.ChannelUnavailableException;
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
import org.springframework.stereotype.Service;

/**
 * 5.4.2.1 平台价格同步。
 *
 * <p>蓝图加粗句：「**平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，
 * 不能假设回写即成功。」 —— 这句话是本模块存在的全部理由，所以下面每一步都围着它转：
 *
 * <ol>
 *   <li><b>消费</b>上游（5.4.1.1 / 5.4.1.2）的待回写事实，按 {@code idempotency_key} 去重；</li>
 *   <li><b>判定</b>租户登记的平台定价规则（最低价 / 自动比价 / 多站点联动）——
 *       最低价触发要**真的被拦下**并显性回报；</li>
 *   <li><b>投递</b>到 channel 的 02 队列（2.5.1.1 {@code platform_operation}），
 *       <b>不自己造平台 HTTP 客户端</b>；</li>
 *   <li><b>记录</b>回写结果与平台调整后的实际价 —— 差异落成可查、可算、可显示的事实；</li>
 *   <li><b>回执</b>：把结果回执给 02 队列，并回调 5.4.1.1 的 ack 端点把这条事实结掉。</li>
 * </ol>
 *
 * <p><b>🔴 写入所有权</b>：{@code price_adjustment_writeback} 是 5.4.1.1 的唯一写入所有者。
 * 本模块**只读**它（同一服务、同一张库），结掉一条事实只能走 5.4.1.1 自己的 ack 端点
 * （本服务内直接调它的服务方法 —— 自我 HTTP 调用会在单连接连接池下把自己堵死）。
 *
 * <p><b>🔴 不自造第二套队列</b>：回写任务住在 channel 的 {@code platform_operation}
 * （蓝图说的「02 队列」）。本模块只负责投递与回执，重试语义（attempts / verification）
 * 住在 channel 那张表里。本模块的表**没有** attempts、**没有**调度器。
 *
 * <p><b>🔴 不假设回写即成功</b>：投递出去之后状态是 {@code PENDING}，
 * **既不是成功也不是失败**；只有收到平台回执（或被规则明确拒收）才落终态。
 */
@Service
public class PlatformPriceSyncService {

  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 插入时绑定的租户：<b>从会话上下文取</b>，不接受调用方传参（那等于新建一条「自称租户」的旁路）。 */
  private static final String TENANT_VALUE = TENANT;

  private final DSLContext sql;
  private final AccessService access;
  private final PriceAdjustmentService adjustments;
  private final PlatformPriceSyncPort channel;

  public PlatformPriceSyncService(
      DSLContext sql,
      AccessService access,
      PriceAdjustmentService adjustments,
      PlatformPriceSyncPort channel) {
    this.sql = sql;
    this.access = access;
    this.adjustments = adjustments;
    this.channel = channel;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>页面上把它固定显示在顶部 —— 运营要拿这页的口径去决定「这个价推上去会怎样」，
   * 所以口径必须摆在数字旁边。
   */
  public PlatformPriceSyncCapabilities capabilities() {
    return new PlatformPriceSyncCapabilities(
        "5.4.2.1",
        List.of("MIN_PRICE", "AUTO_COMPARE", "MULTI_SITE_LINK"),
        List.of(
            "MIN_PRICE 最低价：期望价低于平台最低价 ⇒ 平台拒收（本次不回写，直接显性回报原因）",
            "AUTO_COMPARE 自动比价：期望价高于对比平台价允许的价差上限 ⇒ 平台压到对比价（差异显性记录）",
            "MULTI_SITE_LINK 多站点联动：期望价越出参照站点价格的联动比例带 ⇒ 平台拉到带边界（差异显性记录）"),
        List.of("PENDING", "ACCEPTED", "ADJUSTED", "REJECTED", "FAILED"),
        List.of("RULE_EVALUATION", "PLATFORM_RECEIPT"),
        "5.4.1.1 / 5.4.1.2",
        "price_adjustment_writeback",
        "5.4.1.1（本模块只读，结事实只能走它的 ack 端点）",
        "channel",
        "platform_operation（2.5.1.1 02 队列）",
        "UPDATE_PRICE",
        "price_anomaly_observation（5.5.2.1 价格观测台账 —— 本仓唯一的真实平台价来源）",
        List.of(
            "🔴 平台可能拒绝或调整价格：回写结果必须是结构化事实（成功 / 被平台改价 / 被平台拒绝 + 原因），"
                + "「实际价 vs 期望价」的差异落成 platform_price_sync_diff，可查、可算、可显示。",
            "🔴 PENDING 是「已投递、等平台回执」，既不是成功也不是失败 —— 收到回执前不假设回写成功。",
            "🔴 回写任务复用 channel 的 02 队列（platform_operation），本模块不自造第二套队列；"
                + "重试语义（attempts / verification）住在 channel 那张表里。",
            "🔴 最低价规则触发时本次**不回写**：平台根本不收这个价，发过去只是浪费一次平台调用。"
                + "但原因会显性记进差异台账，并回执给上游 5.4.1.1 把这条事实结掉。",
            "🔴 对比价取不到时该规则**不参与判定**并在原因里写明 —— 判不出来不等于判成通过。"));
  }

  // ================================================================ 平台定价规则

  /** 规则列表（真分页 + 真筛选，筛选全部落在 SQL 谓词上）。 */
  public PlatformPriceRulePage listRules(
      String ruleType, String platformCode, String siteCode, String status, UUID skuId, int page, int size) {
    var where = new StringBuilder(" where tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (ruleType != null && !ruleType.isBlank()) {
      where.append(" and rule_type=?");
      binds.add(ruleType.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and platform_code=?");
      binds.add(PlatformPriceSyncRules.norm(platformCode));
    }
    if (siteCode != null && !siteCode.isBlank()) {
      where.append(" and site_code=?");
      binds.add(PlatformPriceSyncRules.norm(siteCode));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status=?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (skuId != null) {
      where.append(" and sku_id=?::uuid");
      binds.add(skuId);
    }
    long total =
        sql.fetchOne("select count(*) from platform_price_rule" + where, binds.toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), PlatformPriceSyncRules.MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select id, rule_type, platform_code, site_code, sku_id, status, priority, min_price, currency,"
                + " compare_platform_code, compare_site_code, max_gap_ratio, reference_site_code,"
                + " min_ratio, max_ratio, note, created_at, updated_at"
                + " from platform_price_rule"
                + where
                + " order by priority, created_at, id limit ? offset ?",
            concat(binds, List.of(limited, offset)).toArray());
    var items = new ArrayList<PlatformPriceRuleView>();
    for (var row : rows) items.add(ruleView(row));
    return new PlatformPriceRulePage(items, Math.max(page, 0), limited, total, pages(total, limited));
  }

  /** 规则详情。 */
  public PlatformPriceRuleView getRule(UUID id) {
    var row =
        sql.fetchOne(
            "select id, rule_type, platform_code, site_code, sku_id, status, priority, min_price, currency,"
                + " compare_platform_code, compare_site_code, max_gap_ratio, reference_site_code,"
                + " min_ratio, max_ratio, note, created_at, updated_at"
                + " from platform_price_rule where tenant_id="
                + TENANT
                + " and id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("平台定价规则不存在");
    return ruleView(row);
  }

  /** 建规则。 */
  public PlatformPriceRuleView createRule(PlatformPriceRuleInput input) {
    validateRule(input);
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    try {
      sql.execute(
          "insert into platform_price_rule(id,tenant_id,rule_type,platform_code,site_code,sku_id,status,"
              + " priority,min_price,currency,compare_platform_code,compare_site_code,max_gap_ratio,"
              + " reference_site_code,min_ratio,max_ratio,note,created_at,updated_at)"
              + " values (?::uuid,"
              + TENANT_VALUE
              + ",?,?,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          input.ruleType().name(),
          PlatformPriceSyncRules.norm(input.platformCode()),
          PlatformPriceSyncRules.norm(input.siteCode()),
          input.skuId(),
          (input.status() == null ? PlatformPriceRuleStatus.ACTIVE : input.status()).name(),
          input.priority() == null ? 100 : input.priority(),
          input.minPrice(),
          input.currency(),
          normOrEmpty(input.comparePlatformCode()),
          normOrEmpty(input.compareSiteCode()),
          input.maxGapRatio(),
          normOrEmpty(input.referenceSiteCode()),
          input.minRatio(),
          input.maxRatio(),
          trim(input.note(), 400),
          now,
          now);
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) throw e;
      throw ProfitCalculatorService.badRequest(
          "同一作用域（平台 / 站点 / SKU）下已经有**生效**的同类型规则了 —— 两条最低价规则会互相矛盾，"
              + "判定结果将取决于插入顺序。请先停用或改掉原来那条");
    }
    return getRule(id);
  }

  /** 改规则（参数齐备性重新校验 —— 改一半的规则同样会判不出结果）。 */
  public PlatformPriceRuleView updateRule(UUID id, PlatformPriceRuleInput input) {
    var current = getRule(id);
    validateRule(input);
    OffsetDateTime now = OffsetDateTime.now();
    try {
      sql.execute(
          "update platform_price_rule set rule_type=?,platform_code=?,site_code=?,sku_id=?,status=?,"
              + " priority=?,min_price=?,currency=?,compare_platform_code=?,compare_site_code=?,max_gap_ratio=?,"
              + " reference_site_code=?,min_ratio=?,max_ratio=?,note=?,updated_at=?::timestamptz"
              + " where tenant_id="
              + TENANT
              + " and id=?",
          input.ruleType().name(),
          PlatformPriceSyncRules.norm(input.platformCode()),
          PlatformPriceSyncRules.norm(input.siteCode()),
          input.skuId(),
          (input.status() == null ? current.status() : input.status()).name(),
          input.priority() == null ? current.priority() : input.priority(),
          input.minPrice(),
          input.currency(),
          normOrEmpty(input.comparePlatformCode()),
          normOrEmpty(input.compareSiteCode()),
          input.maxGapRatio(),
          normOrEmpty(input.referenceSiteCode()),
          input.minRatio(),
          input.maxRatio(),
          trim(input.note(), 400),
          now,
          id);
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) throw e;
      throw ProfitCalculatorService.badRequest(
          "同一作用域（平台 / 站点 / SKU）下已经有**生效**的同类型规则了 —— 请先停用或改掉原来那条");
    }
    return getRule(id);
  }

  // ================================================================ 回写结果与差异

  /** 回写结果列表（真分页 + 真筛选）。 */
  public PlatformPriceSyncRecordPage listRecords(
      String outcome, String platformCode, String siteCode, UUID skuId, int page, int size) {
    var where = new StringBuilder(" where s.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and s.outcome=?");
      binds.add(outcome.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and s.platform_code=?");
      binds.add(PlatformPriceSyncRules.norm(platformCode));
    }
    if (siteCode != null && !siteCode.isBlank()) {
      where.append(" and s.site_code=?");
      binds.add(PlatformPriceSyncRules.norm(siteCode));
    }
    if (skuId != null) {
      where.append(" and s.sku_id=?::uuid");
      binds.add(skuId);
    }
    long total =
        sql.fetchOne("select count(*) from platform_price_sync s" + where, binds.toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), PlatformPriceSyncRules.MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            selectRecordColumns() + recordFrom() + where
                + " order by s.created_at desc, s.id limit ? offset ?",
            concat(binds, List.of(limited, offset)).toArray());
    var items = new ArrayList<PlatformPriceSyncRecordView>();
    for (var row : rows) items.add(recordView(row));
    return new PlatformPriceSyncRecordPage(items, Math.max(page, 0), limited, total, pages(total, limited));
  }

  /** 回写结果详情（含它产生的全部差异记录）。 */
  public PlatformPriceSyncRecordDetail getRecord(UUID id) {
    var row =
        sql.fetchOne(
            selectRecordColumns() + recordFrom() + " where s.tenant_id=" + TENANT + " and s.id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("价格回写记录不存在");
    var diffs = new ArrayList<PlatformPriceDiffView>();
    for (var d :
        sql.fetch(
            "select id,sync_id,writeback_id,sku_id,platform_code,site_code,currency,expected_price,"
                + " actual_price,price_delta,deviation_ratio,outcome,source,reason_code,reason_message,created_at"
                + " from platform_price_sync_diff where tenant_id="
                + TENANT
                + " and sync_id=? order by created_at, id",
            id)) {
      diffs.add(diffView(d));
    }
    return new PlatformPriceSyncRecordDetail(recordView(row), diffs);
  }

  /** 差异台账（真分页 + 真筛选）。 */
  public PlatformPriceDiffPage listDiffs(
      String outcome, String platformCode, String siteCode, UUID skuId, UUID syncId, int page, int size) {
    var where = new StringBuilder(" where tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and outcome=?");
      binds.add(outcome.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and platform_code=?");
      binds.add(PlatformPriceSyncRules.norm(platformCode));
    }
    if (siteCode != null && !siteCode.isBlank()) {
      where.append(" and site_code=?");
      binds.add(PlatformPriceSyncRules.norm(siteCode));
    }
    if (skuId != null) {
      where.append(" and sku_id=?::uuid");
      binds.add(skuId);
    }
    if (syncId != null) {
      where.append(" and sync_id=?::uuid");
      binds.add(syncId);
    }
    long total =
        sql.fetchOne("select count(*) from platform_price_sync_diff" + where, binds.toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), PlatformPriceSyncRules.MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select id,sync_id,writeback_id,sku_id,platform_code,site_code,currency,expected_price,"
                + " actual_price,price_delta,deviation_ratio,outcome,source,reason_code,reason_message,created_at"
                + " from platform_price_sync_diff"
                + where
                + " order by created_at desc, id limit ? offset ?",
            concat(binds, List.of(limited, offset)).toArray());
    var items = new ArrayList<PlatformPriceDiffView>();
    for (var row : rows) items.add(diffView(row));
    return new PlatformPriceDiffPage(items, Math.max(page, 0), limited, total, pages(total, limited));
  }

  // ================================================================ 同步（消费待回写事实）

  /**
   * 消费上游的待回写事实并投递到平台。
   *
   * <p>🔴 <b>每条事实一个独立事务</b>：一条处理失败不许拖垮其余 ——
   * 事实之间互不相关，批量里一条失败就整批回滚等于「这一轮白跑了」。
   * 所以本方法**不开外层事务**，而是逐条 {@code access.run}（各自一个事务）。
   */
  public PlatformPriceSyncRunView sync(
      AccessService.Access actor, PlatformPriceSyncRequest request, String bearer) {
    var req = request == null ? new PlatformPriceSyncRequest(null, null, null, null) : request;
    int limit = req.limit() == null ? 50 : Math.min(Math.max(req.limit(), 1), 500);

    List<PendingFact> facts =
        access.run(actor, "catalog.spu.update", () -> pendingFacts(req, limit));

    int scanned = facts.size();
    int created = 0;
    int dispatched = 0;
    int rejected = 0;
    int skipped = 0;
    int failed = 0;
    var items = new ArrayList<PlatformPriceSyncRecordView>();

    for (var fact : facts) {
      PlatformPriceSyncRecordView outcome;
      try {
        outcome = access.run(actor, "catalog.spu.update", () -> processFact(actor, fact, bearer));
      } catch (RuntimeException e) {
        // 一条失败不影响其余（各自独立事务）。
        failed++;
        continue;
      }
      if (outcome == null) {
        // 已经处理过：同一把幂等键不产生第二条回写。
        skipped++;
        continue;
      }
      items.add(outcome);
      switch (outcome.outcome()) {
        case PENDING -> dispatched++;
        case ACCEPTED, ADJUSTED -> dispatched++;
        case REJECTED -> rejected++;
        case FAILED -> failed++;
      }
    }
    return new PlatformPriceSyncRunView(scanned, created, dispatched, rejected, skipped, failed, items);
  }

  /** 待回写事实（只读上游 5.4.1.1 的表 —— 本模块**不写**那张表）。 */
  private List<PendingFact> pendingFacts(PlatformPriceSyncRequest request, int limit) {
    var where = new StringBuilder(" where w.tenant_id=" + TENANT + " and w.status='PENDING'");
    var binds = new ArrayList<Object>();
    if (request.platformCode() != null && !request.platformCode().isBlank()) {
      where.append(" and w.platform_code=?");
      binds.add(PlatformPriceSyncRules.norm(request.platformCode()));
    }
    if (request.siteCode() != null && !request.siteCode().isBlank()) {
      where.append(" and w.site_code=?");
      binds.add(PlatformPriceSyncRules.norm(request.siteCode()));
    }
    if (request.skuId() != null) {
      where.append(" and w.sku_id=?::uuid");
      binds.add(request.skuId());
    }
    var rows =
        sql.fetch(
            "select w.id,w.order_id,w.order_item_id,w.sku_id,k.code as sku_code,w.platform_code,w.site_code,"
                + " w.currency,w.expected_price,w.previous_price,w.idempotency_key,w.created_at"
                + " from price_adjustment_writeback w"
                + " join sku k on k.tenant_id=w.tenant_id and k.id=w.sku_id"
                + where
                + " order by w.created_at, w.id limit ?",
            concat(binds, List.of(limit)).toArray());
    var facts = new ArrayList<PendingFact>();
    for (var row : rows) {
      facts.add(
          new PendingFact(
              row.get("id", UUID.class),
              row.get("order_id", UUID.class),
              row.get("order_item_id", UUID.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("platform_code", String.class),
              row.get("site_code", String.class),
              row.get("currency", String.class),
              row.get("expected_price", BigDecimal.class),
              row.get("previous_price", BigDecimal.class),
              row.get("idempotency_key", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return facts;
  }

  /**
   * 处理一条事实。返回 null 表示「已处理过，本轮跳过」。
   *
   * <p>幂等：同一个 {@code idempotency_key} 重复投递**不产生第二条回写** ——
   * 本表对 (tenant_id, idempotency_key) 有唯一约束，02 队列那边也是同一把钥匙。
   */
  private PlatformPriceSyncRecordView processFact(
      AccessService.Access actor, PendingFact fact, String bearer) {
    var existing =
        sql.fetchOne(
            selectRecordColumns() + recordFrom() + " where s.tenant_id=" + TENANT
                + " and s.idempotency_key=?",
            fact.idempotencyKey());
    if (existing != null) {
      return resume(actor, existing, fact, bearer);
    }
    return dispatch(actor, fact, bearer, null);
  }

  /**
   * 已处理过的事实：按当前状态决定是「跳过」还是「重试」。
   *
   * <p>🔴 重试语义住在 channel 的 02 队列里：已投递的（{@code platform_operation_id} 有值）
   * 由那边重试，本模块不重复投递 —— 重复投递会造成平台侧重复改价。
   * 只有「还没投出去」（渠道不可达）的那条才在这里重投。
   *
   * <p>🔴 重投**复用同一条回写记录**（不新建）：差异台账只追加不删，
   * 上一轮的 FAILED 差异留在历史里，重投再追加一条 —— 「试过几次、每次为什么没成」看得见。
   */
  private PlatformPriceSyncRecordView resume(
      AccessService.Access actor, Record existing, PendingFact fact, String bearer) {
    var outcome = PlatformPriceSyncOutcome.valueOf(existing.get("outcome", String.class));
    UUID operationId = existing.get("platform_operation_id", UUID.class);
    if (outcome == PlatformPriceSyncOutcome.PENDING && operationId != null) {
      // 已经进了 02 队列：队列自己负责重试，本轮不重复投递。
      return null;
    }
    if (outcome == PlatformPriceSyncOutcome.FAILED) {
      // 上一轮没发出去：重新走一遍判定与投递（复用同一条记录）。
      return dispatch(actor, fact, bearer, existing.get("id", UUID.class));
    }
    // ACCEPTED / ADJUSTED / REJECTED 都是终态：同一把幂等键再来一次也不重做。
    return null;
  }

  /** 判定 → 投递 → 记录结果与差异。 */
  private PlatformPriceSyncRecordView dispatch(
      AccessService.Access actor, PendingFact fact, String bearer, UUID existingSyncId) {

    // ---- ① 登记式边界：这个平台 / 站点在 channel 侧有没有已登记的店铺与站点 ----
    // 没有落点 ⇒ 平台侧根本没有这个改价对象 ⇒ 显性回报，不凭空造一个任务出去。
    // 🔴 渠道不可达与「没有落点」是**两件事**：前者是这次没发出去（可重试），
    //    后者是平台侧没有这个对象（拒收）。所以这里要分开处理 ——
    //    把不可达当成「没有落点」会把一次临时故障写成一条永久的拒收结论。
    java.util.Optional<PlatformPriceSyncPort.Target> target;
    try {
      target = channel.resolveTarget(bearer, fact.platformCode(), fact.siteCode());
    } catch (ChannelUnavailableException e) {
      return failFast(fact, existingSyncId, e.getMessage());
    }
    if (target.isEmpty()) {
      return reject(
          fact,
          "NOT_REGISTERED",
          "平台 "
              + fact.platformCode()
              + " / 站点 "
              + fact.siteCode()
              + " 在渠道服务里没有已登记的店铺（登记式边界），本次没有可回写的落点");
    }

    // ---- ② 规则判定 ----
    var rules = activeRules(fact);
    var comparisonPrices = comparisonPrices(fact.skuId());
    var decision =
        PlatformPriceSyncRules.evaluate(
            fact.expectedPrice(),
            fact.currency(),
            fact.platformCode(),
            fact.siteCode(),
            fact.skuId(),
            rules,
            comparisonPrices);

    if (decision.outcome() == PlatformPriceRuleOutcome.REJECTED) {
      // 平台最低价触发 ⇒ 平台根本不收这个价 ⇒ 本次**不回写**（发过去只是浪费一次平台调用），
      // 但原因要显性：记进结果与差异台账，并回执给上游把这条事实结掉。
      return reject(fact, decision.reasonCode(), decision.reasonMessage());
    }

    // 🔴 没参与判定的规则（对比价缺观测 / 币种不同）**也写进理由** ——
    //   运营看到「规则都通过了」却不知道为什么最低价规则没拦下来，那正是「显性反馈」要避免的事。
    String ruleText = decisionText(decision.reasonMessage(), decision.notes());

    // ---- ③ 落回写记录（PENDING）+ 规则判定产生的差异 ----
    UUID syncId = existingSyncId == null ? UUID.randomUUID() : existingSyncId;
    OffsetDateTime now = OffsetDateTime.now();
    var predicted = decision.predictedPrice();
    boolean adjusted = predicted != null && predicted.compareTo(fact.expectedPrice()) != 0;
    BigDecimal predictedDelta = adjusted ? predicted.subtract(fact.expectedPrice()) : BigDecimal.ZERO;

    if (existingSyncId == null) {
      sql.execute(
          "insert into platform_price_sync(id,tenant_id,writeback_id,order_id,order_item_id,sku_id,"
              + " platform_code,site_code,currency,expected_price,predicted_price,actual_price,price_delta,"
              + " outcome,rule_outcome,rule_id,rule_reason,platform_operation_id,platform_request_id,"
              + " idempotency_key,last_error,dispatched_at,completed_at,created_at,updated_at)"
              + " values (?::uuid,"
              + TENANT_VALUE
              + ",?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,null,null,'PENDING',?,?,?,null,'',?,'',null,null,"
              + "?::timestamptz,?::timestamptz)",
          syncId,
          fact.writebackId(),
          fact.orderId(),
          fact.orderItemId(),
          fact.skuId(),
          fact.platformCode(),
          fact.siteCode(),
          fact.currency(),
          fact.expectedPrice(),
          predicted,
          decision.outcome().name(),
          decision.ruleId(),
          trim(ruleText, 500),
          fact.idempotencyKey(),
          now,
          now);
    } else {
      // 重投：同一条记录回到 PENDING（差异台账只追加，历史不动）。
      sql.execute(
          "update platform_price_sync set outcome='PENDING', predicted_price=?, actual_price=null,"
              + " price_delta=null, rule_outcome=?, rule_id=?, rule_reason=?, platform_operation_id=null,"
              + " platform_request_id='', last_error='', dispatched_at=null, completed_at=null,"
              + " updated_at=?::timestamptz where tenant_id="
              + TENANT
              + " and id=?",
          predicted,
          decision.outcome().name(),
          decision.ruleId(),
          trim(ruleText, 500),
          now,
          syncId);
    }

    insertDiff(
        syncId,
        fact,
        adjusted ? PlatformPriceDiffOutcome.ADJUSTED : PlatformPriceDiffOutcome.MATCHED,
        PlatformPriceDiffSource.RULE_EVALUATION,
        predicted,
        adjusted ? predictedDelta : null,
        adjusted ? "PLATFORM_WILL_ADJUST" : decision.reasonCode(),
        ruleText);

    // ---- ④ 投递到 02 队列（channel 的 platform_operation）----
    try {
      UUID operationId =
          channel.enqueueWriteback(
              bearer,
              new PlatformPriceSyncPort.WritebackCommand(
                  target.get().shopId(),
                  target.get().marketplaceId(),
                  fact.idempotencyKey(),
                  fact.platformCode(),
                  fact.siteCode(),
                  fact.skuCode(),
                  fact.expectedPrice(),
                  fact.currency(),
                  fact.previousPrice()));
      sql.execute(
          "update platform_price_sync set platform_operation_id=?::uuid, dispatched_at=?::timestamptz,"
              + " updated_at=?::timestamptz where tenant_id=" + TENANT + " and id=?",
          operationId,
          now,
          now,
          syncId);
    } catch (ChannelUnavailableException e) {
      // 🔴 渠道不可达 ⇒ 本次没发出去。**不**结掉上游事实（它还得留在 PENDING 里等下一轮重试），
      //    也**不**假装成功。记录失败原因，让运营看得见。
      sql.execute(
          "update platform_price_sync set outcome='FAILED', last_error=?, completed_at=?::timestamptz,"
              + " updated_at=?::timestamptz where tenant_id=" + TENANT + " and id=?",
          trim(e.getMessage(), 500),
          now,
          now,
          syncId);
      insertDiff(
          syncId,
          fact,
          PlatformPriceDiffOutcome.FAILED,
          PlatformPriceDiffSource.RULE_EVALUATION,
          null,
          null,
          "CHANNEL_UNAVAILABLE",
          e.getMessage());
    }

    return getRecord(syncId).record();
  }

  /**
   * 这一轮**没发出去**（渠道不可达）。
   *
   * <p>🔴 关键取舍：**不**结掉上游事实 —— 它还得留在 PENDING 里等下一轮重试；
   * 也**不**假装成功。记录失败原因，让运营看得见。
   *
   * <p>🔴 与「没有登记落点」严格分开：后者是平台侧没有这个对象（拒收，终态），
   * 前者是一次临时故障（失败，可重试）。混在一起会把一次抖动写成永久结论。
   */
  private PlatformPriceSyncRecordView failFast(
      PendingFact fact, UUID existingSyncId, String reason) {
    UUID syncId = existingSyncId == null ? UUID.randomUUID() : existingSyncId;
    OffsetDateTime now = OffsetDateTime.now();
    if (existingSyncId == null) {
      sql.execute(
          "insert into platform_price_sync(id,tenant_id,writeback_id,order_id,order_item_id,sku_id,"
              + " platform_code,site_code,currency,expected_price,predicted_price,actual_price,price_delta,"
              + " outcome,rule_outcome,rule_id,rule_reason,platform_operation_id,platform_request_id,"
              + " idempotency_key,last_error,dispatched_at,completed_at,created_at,updated_at)"
              + " values (?::uuid,"
              + TENANT_VALUE
              + ",?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,null,null,null,'FAILED','NOT_EVALUATED',null,"
              + " '尚未判定：渠道服务不可达',null,'',?,?,null,?::timestamptz,?::timestamptz,?::timestamptz)",
          syncId,
          fact.writebackId(),
          fact.orderId(),
          fact.orderItemId(),
          fact.skuId(),
          fact.platformCode(),
          fact.siteCode(),
          fact.currency(),
          fact.expectedPrice(),
          fact.idempotencyKey(),
          trim(reason, 500),
          now,
          now,
          now);
    } else {
      sql.execute(
          "update platform_price_sync set outcome='FAILED', rule_outcome='NOT_EVALUATED',"
              + " rule_reason='尚未判定：渠道服务不可达', last_error=?, completed_at=?::timestamptz,"
              + " updated_at=?::timestamptz where tenant_id="
              + TENANT
              + " and id=?",
          trim(reason, 500),
          now,
          now,
          syncId);
    }
    insertDiff(
        syncId,
        fact,
        PlatformPriceDiffOutcome.FAILED,
        PlatformPriceDiffSource.RULE_EVALUATION,
        null,
        null,
        "CHANNEL_UNAVAILABLE",
        reason);
    return getRecord(syncId).record();
  }

  /** 规则拒收（最低价触发 / 没有登记落点）：记结果 + 记差异 + 回执上游把事实结掉。 */
  private PlatformPriceSyncRecordView reject(PendingFact fact, String reasonCode, String reasonMessage) {
    UUID syncId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into platform_price_sync(id,tenant_id,writeback_id,order_id,order_item_id,sku_id,"
            + " platform_code,site_code,currency,expected_price,predicted_price,actual_price,price_delta,"
            + " outcome,rule_outcome,rule_id,rule_reason,platform_operation_id,platform_request_id,"
            + " idempotency_key,last_error,dispatched_at,completed_at,created_at,updated_at)"
            + " values (?::uuid,"
            + TENANT_VALUE
            + ",?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,null,null,null,'REJECTED','REJECTED',null,?,"
            + " null,'',?,'',null,?::timestamptz,?::timestamptz,?::timestamptz)",
        syncId,
        fact.writebackId(),
        fact.orderId(),
        fact.orderItemId(),
        fact.skuId(),
        fact.platformCode(),
        fact.siteCode(),
        fact.currency(),
        fact.expectedPrice(),
        trim(reasonMessage, 500),
        fact.idempotencyKey(),
        now,
        now,
        now);

    insertDiff(
        syncId,
        fact,
        PlatformPriceDiffOutcome.REJECTED,
        PlatformPriceDiffSource.RULE_EVALUATION,
        null,
        null,
        reasonCode,
        reasonMessage);

    // 结掉上游事实：5.4.1.1 的 ack 端点（同一服务 ⇒ 直接调它的服务方法；
    // 自我 HTTP 调用在单连接连接池下会把自己堵死）。
    ackUpstream(fact.writebackId(), "平台拒收：" + reasonMessage);
    return getRecord(syncId).record();
  }

  // ================================================================ 平台回执

  /**
   * 平台回执（由平台适配器 / 核验通道调用）。
   *
   * <p>🔴 收不到回执时记录停在 {@code PENDING} —— <b>不假设回写即成功</b>。
   */
  public PlatformPriceSyncRecordDetail receipt(
      UUID id, PlatformPriceReceiptRequest request, String bearer) {
    var row =
        sql.fetchOne(
            selectRecordColumns() + recordFrom() + " where s.tenant_id=" + TENANT + " and s.id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("价格回写记录不存在");
    if (request == null || request.outcome() == null) {
      throw ProfitCalculatorService.badRequest("回执必须给出平台结论");
    }
    var outcome = request.outcome();
    if (outcome == PlatformPriceSyncOutcome.PENDING) {
      throw ProfitCalculatorService.badRequest(
          "回执里不能写 PENDING —— PENDING 是「还没结论」。没有结论就不要提交回执");
    }
    var current = PlatformPriceSyncOutcome.valueOf(row.get("outcome", String.class));
    if (current != PlatformPriceSyncOutcome.PENDING) {
      throw ProfitCalculatorService.badRequest(
          "这条回写已经是终态（" + current + "），不能再收回执 —— 收两次会让「平台到底收了多少」变成两个答案");
    }

    var expected = row.get("expected_price", BigDecimal.class);
    var actual = request.actualPrice();
    BigDecimal delta = null;
    if (outcome == PlatformPriceSyncOutcome.ACCEPTED) {
      if (actual == null) {
        throw ProfitCalculatorService.badRequest("平台按期望价收下的回执必须带实际价");
      }
      if (actual.compareTo(expected) != 0) {
        throw ProfitCalculatorService.badRequest(
            "平台按 "
                + PlatformPriceSyncRules.plain(actual)
                + " 收的，与期望价 "
                + PlatformPriceSyncRules.plain(expected)
                + " 不同 —— 这不是「按期望价收下」，请用 ADJUSTED 回执（差异要显性记下来）");
      }
      delta = BigDecimal.ZERO;
    } else if (outcome == PlatformPriceSyncOutcome.ADJUSTED) {
      if (actual == null) {
        throw ProfitCalculatorService.badRequest("平台改价的回执必须带实际价（否则差异无从算起）");
      }
      if (actual.compareTo(expected) == 0) {
        throw ProfitCalculatorService.badRequest("实际价与期望价相同就不是「被平台改价」—— 请用 ACCEPTED 回执");
      }
      delta = actual.subtract(expected);
    } else {
      // REJECTED：没有实际价。
      if (actual != null) {
        throw ProfitCalculatorService.badRequest("拒收回执不能带实际价 —— 平台根本没收这个价");
      }
    }

    var writebackId = row.get("writeback_id", UUID.class);
    var platformCode = row.get("platform_code", String.class);
    var siteCode = row.get("site_code", String.class);
    var currency = row.get("currency", String.class);
    var skuId = row.get("sku_id", UUID.class);

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update platform_price_sync set outcome=?, actual_price=?, price_delta=?, platform_request_id=?,"
            + " completed_at=?::timestamptz, updated_at=?::timestamptz"
            + " where tenant_id=" + TENANT + " and id=?",
        outcome.name(),
        actual,
        delta,
        trim(request.platformRequestId(), 128),
        now,
        now,
        id);

    var diffOutcome =
        switch (outcome) {
          case ACCEPTED -> PlatformPriceDiffOutcome.MATCHED;
          case ADJUSTED -> PlatformPriceDiffOutcome.ADJUSTED;
          case REJECTED -> PlatformPriceDiffOutcome.REJECTED;
          default -> PlatformPriceDiffOutcome.FAILED;
        };
    insertDiff(
        id,
        new PendingFact(
            writebackId,
            row.get("order_id", UUID.class),
            row.get("order_item_id", UUID.class),
            skuId,
            row.get("sku_code", String.class),
            platformCode,
            siteCode,
            currency,
            expected,
            null,
            row.get("idempotency_key", String.class),
            now),
        diffOutcome,
        PlatformPriceDiffSource.PLATFORM_RECEIPT,
        actual,
        delta,
        outcome == PlatformPriceSyncOutcome.REJECTED ? "PLATFORM_REJECTED" : "PLATFORM_RECEIPT",
        request.reason() == null || request.reason().isBlank() ? defaultReason(outcome) : request.reason());

    // 回执 02 队列（channel 的 platform_operation）：成功 / 失败。
    UUID operationId = row.get("platform_operation_id", UUID.class);
    if (operationId != null) {
      try {
        channel.completeWriteback(
            bearer,
            operationId,
            outcome != PlatformPriceSyncOutcome.REJECTED,
            request.platformRequestId(),
            request.reason() == null || request.reason().isBlank() ? defaultReason(outcome) : request.reason());
      } catch (ChannelUnavailableException e) {
        // 回执发不出去**不改**本模块的结论：平台到底收了多少已经是既成事实，
        // 02 队列那边的状态由它自己的核验通道（2.5.1.2）补齐。
      }
    }

    // 结掉上游事实。
    ackUpstream(writebackId, upstreamNote(outcome, expected, actual, delta, currency));
    return getRecord(id);
  }

  // ================================================================ 内部

  /** 上游待回写事实（只读）。 */
  private record PendingFact(
      UUID writebackId,
      UUID orderId,
      UUID orderItemId,
      UUID skuId,
      String skuCode,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal expectedPrice,
      BigDecimal previousPrice,
      String idempotencyKey,
      OffsetDateTime createdAt) {}

  /** 生效规则（作用域已在 SQL 里收敛）。 */
  private List<PlatformPriceSyncRules.Rule> activeRules(PendingFact fact) {
    var rows =
        sql.fetch(
            "select id, rule_type, platform_code, site_code, sku_id, min_price, currency,"
                + " compare_platform_code, compare_site_code, max_gap_ratio, reference_site_code,"
                + " min_ratio, max_ratio, priority"
                + " from platform_price_rule where tenant_id="
                + TENANT
                + " and status='ACTIVE' and platform_code=?"
                + " and (site_code='' or site_code=?) and (sku_id is null or sku_id=?::uuid)"
                + " order by priority, created_at, id",
            fact.platformCode(),
            fact.siteCode(),
            fact.skuId());
    var rules = new ArrayList<PlatformPriceSyncRules.Rule>();
    for (var row : rows) {
      rules.add(
          new PlatformPriceSyncRules.Rule(
              row.get("id", UUID.class),
              PlatformPriceRuleType.valueOf(row.get("rule_type", String.class)),
              row.get("platform_code", String.class),
              row.get("site_code", String.class),
              row.get("sku_id", UUID.class),
              row.get("min_price", BigDecimal.class),
              row.get("currency", String.class),
              row.get("compare_platform_code", String.class),
              row.get("compare_site_code", String.class),
              row.get("max_gap_ratio", BigDecimal.class),
              row.get("reference_site_code", String.class),
              row.get("min_ratio", BigDecimal.class),
              row.get("max_ratio", BigDecimal.class),
              row.get("priority", Integer.class)));
    }
    return rules;
  }

  /**
   * 对比价：取该 SKU 在各平台 / 站点的**最新一条**平台价观测。
   *
   * <p>🔴 取不到就是取不到（不拿 0 顶）：没有观测 ⇒ 对应规则不参与判定并写进原因里。
   * 🔴 币种一起带上：规则判定时只认**同币种**的对比价（绝不按 1:1 折算）。
   */
  private Map<String, PlatformPriceSyncRules.ComparisonPrice> comparisonPrices(UUID skuId) {
    var rows =
        sql.fetch(
            "select distinct on (platform_code, site_code) platform_code, site_code, currency, platform_price"
                + " from price_anomaly_observation where tenant_id="
                + TENANT
                + " and sku_id=?::uuid and platform_price is not null"
                + " order by platform_code, site_code, observed_at desc, id desc",
            skuId);
    var prices = new LinkedHashMap<String, PlatformPriceSyncRules.ComparisonPrice>();
    for (var row : rows) {
      prices.put(
          PlatformPriceSyncRules.key(
              row.get("platform_code", String.class), row.get("site_code", String.class)),
          new PlatformPriceSyncRules.ComparisonPrice(
              row.get("currency", String.class), row.get("platform_price", BigDecimal.class)));
    }
    return prices;
  }

  /** 写一条差异记录（只追加）。 */
  private void insertDiff(
      UUID syncId,
      PendingFact fact,
      PlatformPriceDiffOutcome outcome,
      PlatformPriceDiffSource source,
      BigDecimal actual,
      BigDecimal delta,
      String reasonCode,
      String reasonMessage) {
    BigDecimal ratio = null;
    if (delta != null && fact.expectedPrice() != null && fact.expectedPrice().signum() != 0) {
      ratio = delta.abs().divide(fact.expectedPrice(), PlatformPriceSyncRules.RATE_SCALE, RoundingMode.HALF_UP);
    }
    String message = reasonMessage;
    sql.execute(
        "insert into platform_price_sync_diff(id,tenant_id,sync_id,writeback_id,sku_id,platform_code,"
            + " site_code,currency,expected_price,actual_price,price_delta,deviation_ratio,outcome,source,"
            + " reason_code,reason_message,created_at)"
            + " values (?::uuid,"
            + TENANT_VALUE
            + ",?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        syncId,
        fact.writebackId(),
        fact.skuId(),
        fact.platformCode(),
        fact.siteCode(),
        fact.currency(),
        fact.expectedPrice(),
        actual,
        delta,
        ratio,
        outcome.name(),
        source.name(),
        trim(reasonCode, 64),
        trim(message, 500),
        OffsetDateTime.now());
  }

  /** 回执给上游 5.4.1.1：把这条待回写事实结掉（**只能走它的 ack 端点**）。 */
  private void ackUpstream(UUID writebackId, String note) {
    try {
      adjustments.ackWriteback(
          writebackId, new PriceAdjustmentModels.PriceAdjustmentWritebackAckRequest(note));
    } catch (RuntimeException e) {
      // 上游事实结不掉**不能**反过来推翻本模块的结论：平台到底收了多少已经是既成事实，
      // 差异台账里有据可查。下一轮同步会再次尝试结它（ack 端点本身幂等：
      // 它只把 PENDING 改成 ACKNOWLEDGED）。
    }
  }

  /**
   * 是不是「唯一约束冲突」（SQLSTATE 23505）。
   *
   * <p>🔴 Spring Boot 的 jOOQ 异常翻译器（{@code DefaultExceptionTranslatorExecuteListener}）
   * 会把 jOOQ 的 {@code DataAccessException} **翻成** Spring 的 {@code DuplicateKeyException} ——
   * 只 catch jOOQ 那个类型是 catch 不到的（异常已经换了类型）。所以这里按 SQLSTATE 判，
   * 两套异常体系都覆盖得到。
   */
  private static boolean isUniqueViolation(Throwable error) {
    for (var t = error; t != null; t = t.getCause()) {
      if (t instanceof org.springframework.dao.DuplicateKeyException) return true;
      if (t instanceof org.jooq.exception.DataAccessException dae && "23505".equals(dae.sqlState())) {
        return true;
      }
      if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
    }
    return false;
  }

  /** 判定理由 + 没参与判定的规则说明（拼成一段人可读的话）。 */
  private static String decisionText(String reasonMessage, List<String> notes) {
    String text = reasonMessage == null ? "" : reasonMessage;
    if (notes != null && !notes.isEmpty()) {
      text = text + "；" + String.join("；", notes);
    }
    return text;
  }

  /** 回执给上游的一句话摘要。 */
  private String upstreamNote(
      PlatformPriceSyncOutcome outcome,
      BigDecimal expected,
      BigDecimal actual,
      BigDecimal delta,
      String currency) {
    return switch (outcome) {
      case ACCEPTED ->
          "平台已按期望价 " + PlatformPriceSyncRules.plain(expected) + " " + currency + " 收价";
      case ADJUSTED ->
          "平台改价：期望 "
              + PlatformPriceSyncRules.plain(expected)
              + "，实际 "
              + PlatformPriceSyncRules.plain(actual)
              + "，差额 "
              + PlatformPriceSyncRules.plain(delta)
              + " "
              + currency;
      case REJECTED -> "平台拒收（详见价格同步的差异记录）";
      default -> "平台回执：" + outcome;
    };
  }

  private String defaultReason(PlatformPriceSyncOutcome outcome) {
    return switch (outcome) {
      case ACCEPTED -> "平台按期望价收下";
      case ADJUSTED -> "平台把价改了（差异见差异记录）";
      case REJECTED -> "平台拒收";
      default -> outcome.name();
    };
  }

  private String selectRecordColumns() {
    return "select s.id,s.writeback_id,s.order_id,s.order_item_id,s.sku_id,k.code as sku_code,"
        + " s.platform_code,s.site_code,s.currency,s.expected_price,s.predicted_price,s.actual_price,"
        + " s.price_delta,s.outcome,s.rule_outcome,s.rule_id,s.rule_reason,s.platform_operation_id,"
        + " s.platform_request_id,s.idempotency_key,s.last_error,s.dispatched_at,s.completed_at,"
        + " s.created_at,s.updated_at";
  }

  private String recordFrom() {
    return " from platform_price_sync s join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id";
  }

  private PlatformPriceSyncRecordView recordView(Record row) {
    var expected = row.get("expected_price", BigDecimal.class);
    var delta = row.get("price_delta", BigDecimal.class);
    BigDecimal ratio = null;
    if (delta != null && expected != null && expected.signum() != 0) {
      ratio = delta.abs().divide(expected, PlatformPriceSyncRules.RATE_SCALE, RoundingMode.HALF_UP);
    }
    return new PlatformPriceSyncRecordView(
        row.get("id", UUID.class),
        row.get("writeback_id", UUID.class),
        row.get("order_id", UUID.class),
        row.get("order_item_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        expected,
        row.get("predicted_price", BigDecimal.class),
        row.get("actual_price", BigDecimal.class),
        delta,
        ratio,
        PlatformPriceSyncOutcome.valueOf(row.get("outcome", String.class)),
        PlatformPriceRuleOutcome.valueOf(row.get("rule_outcome", String.class)),
        row.get("rule_id", UUID.class),
        row.get("rule_reason", String.class),
        row.get("platform_operation_id", UUID.class),
        row.get("platform_request_id", String.class),
        row.get("idempotency_key", String.class),
        row.get("last_error", String.class),
        row.get("dispatched_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private PlatformPriceDiffView diffView(Record row) {
    return new PlatformPriceDiffView(
        row.get("id", UUID.class),
        row.get("sync_id", UUID.class),
        row.get("writeback_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        row.get("expected_price", BigDecimal.class),
        row.get("actual_price", BigDecimal.class),
        row.get("price_delta", BigDecimal.class),
        row.get("deviation_ratio", BigDecimal.class),
        PlatformPriceDiffOutcome.valueOf(row.get("outcome", String.class)),
        PlatformPriceDiffSource.valueOf(row.get("source", String.class)),
        row.get("reason_code", String.class),
        row.get("reason_message", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private PlatformPriceRuleView ruleView(Record row) {
    return new PlatformPriceRuleView(
        row.get("id", UUID.class),
        PlatformPriceRuleType.valueOf(row.get("rule_type", String.class)),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("sku_id", UUID.class),
        PlatformPriceRuleStatus.valueOf(row.get("status", String.class)),
        row.get("priority", Integer.class),
        row.get("min_price", BigDecimal.class),
        row.get("currency", String.class),
        row.get("compare_platform_code", String.class),
        row.get("compare_site_code", String.class),
        row.get("max_gap_ratio", BigDecimal.class),
        row.get("reference_site_code", String.class),
        row.get("min_ratio", BigDecimal.class),
        row.get("max_ratio", BigDecimal.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private void validateRule(PlatformPriceRuleInput input) {
    if (input == null) throw ProfitCalculatorService.badRequest("规则不能为空");
    PlatformPriceSyncRules.validate(
        input.ruleType(),
        input.minPrice(),
        input.currency(),
        input.comparePlatformCode(),
        input.maxGapRatio(),
        input.referenceSiteCode(),
        input.minRatio(),
        input.maxRatio());
  }

  private static int pages(long total, int size) {
    return size <= 0 ? 0 : (int) ((total + size - 1) / size);
  }

  private static String trim(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String normOrEmpty(String value) {
    return value == null ? "" : PlatformPriceSyncRules.norm(value);
  }

  private static <T> List<Object> concat(List<Object> head, List<T> tail) {
    var out = new ArrayList<Object>(head);
    out.addAll(tail);
    return out;
  }
}
