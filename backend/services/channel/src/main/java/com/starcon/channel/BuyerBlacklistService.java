package com.starcon.channel;

import static com.starcon.channel.BuyerBlacklistModels.*;
import static com.starcon.channel.BuyerBlacklistRules.*;

import com.starcon.channel.BuyerBlacklistRules.EventType;
import com.starcon.channel.BuyerBlacklistRules.FlagAction;
import com.starcon.channel.BuyerBlacklistRules.RiskLevel;
import com.starcon.channel.BuyerBlacklistRules.Scope;
import com.starcon.channel.BuyerBlacklistRules.Source;
import com.starcon.channel.BuyerBlacklistRules.Status;
import com.starcon.channel.BuyerBlacklistSourcePort.SourceBuyerProfile;
import com.starcon.channel.BuyerBlacklistSourcePort.SourceProfiles;
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
 * 16.5.1.1 黑名单。
 *
 * <p>蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * <b>黑名单应支持申诉解除</b>，永久不解除会让误判的正常买家永久损失。」
 *
 * <p>🔴🔴 本类里有<b>六条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>主体键是 {@code buyer_key}</b>（收件人三件套的 md5），与 trade 的
 *       {@code order_review_buyer_profile.buyer_key}、channel 的 {@code buyer_risk_score.buyer_key}
 *       逐字同一公式（用 {@link BuyerRiskScoreSourcePort#buyerKey}，**不另写一份** ——
 *       两份 md5 早晚会漂移一次，而漂移的后果是一次静默漏拦）。</li>
 *   <li><b>登记不可改，解除只追加事件</b>：登记行 append-only；解除 / 升级 / 申诉裁决
 *       全部追加到 {@code buyer_blacklist_status_event}（谁、何时、为什么，一条不少）。</li>
 *   <li><b>生效范围参与判定</b>：仅本店铺的黑名单**不会**在全店铺判定里命中 ——
 *       判定按 scope 过滤，不只按 tenant 过滤。</li>
 *   <li><b>读不到 ≠ 没风险</b>：9.2 审单读不到时**整个请求 503**，绝不返回
 *       「这个买家没有风险」。空集合会被读成「干净」，那是一次真实的欺诈损失。</li>
 *   <li><b>只出建议，不改订单</b>：本模块产出「建议拦截 / 建议人工复核 / 建议观察」，
 *       <b>不写订单状态</b>（那是 9.2 / 9.2.1.3 的事），也<b>不代劳 11 售后</b>。</li>
 *   <li><b>下游未交付就说未交付</b>：9.2.1.3 拦截与 11 售后在本仓各有归属模块，
 *       能力声明里逐条写明本模块**不代劳**什么。</li>
 * </ol>
 */
@Service
public class BuyerBlacklistService {

  // ================================================================ 口径文案

  /** 主体键口径。 */
  private static final String SUBJECT_SEMANTICS =
      "🔴 主体键是 **buyer_key**（收件人三件套的 md5，32 位十六进制），"
          + "与 trade 的 order_review_buyer_profile.buyer_key、channel 的 buyer_risk_score.buyer_key "
          + "**逐字同一公式**（md5(utf8(姓名 ‖ U+001F ‖ 电话 ‖ U+001F ‖ 邮箱))）。"
          + "分隔符用**不可见字符**而不是 '|' —— 收件人字段由运营自由录入，"
          + "'|' 会让两组不同字段拼出同一个串，那是一次**跨买家的错误合并**。";

  /** 生效范围口径。 */
  private static final String SCOPE_SEMANTICS =
      "🔴 生效范围是**受控值域**（DB 层 CHECK 钉死）：SHOP_ONLY（仅本店铺，此时必须指定店铺）"
          + "/ ALL_SHOPS（全店铺，此时店铺必须为空）。"
          + "判定时**按范围过滤**：仅本店铺的黑名单不会在全店铺口径下命中，反之亦然。"
          + "同一个买家在不同范围下**可以各有一条**（A 店严、B 店宽），"
          + "但**同一范围下只能有一条生效中**（DB 层延迟约束钉死）。";

  /** 解除口径。 */
  private static final String RELEASE_SEMANTICS =
      "🔴 解除**不是**删掉登记行，也不是把登记行改成「已解除」：登记行 append-only，"
          + "解除会**追加一条状态流转事件**（谁、何时、为什么），历史一条不少。"
          + "**每一条事件都必须带理由** —— 没有理由的解除等于「悄悄放过」，客服无从解释，"
          + "买家也无从申诉。解除后复发走 REINSTATED 事件，**历史仍然一条不少**。";

  /** 申诉口径。 */
  private static final String APPEAL_SEMANTICS =
      "🔴 蓝图加粗句「黑名单应支持申诉解除，永久不解除会让误判的正常买家永久损失」的落点："
          + "申诉是**一张单子**（谁提交、什么时候、什么理由、被谁裁决、裁决理由），"
          + "裁决**只许走一次**（PENDING → APPROVED / REJECTED，DB 层钉死）。"
          + "申诉通过 ⇒ 追加一条 APPEAL_APPROVED 事件并解除；申诉驳回 ⇒ 追加 APPEAL_REJECTED 事件并维持。"
          + "🔴 **申诉单上的事实部分不可改**（提交人 / 提交时刻 / 申诉理由）—— 提交之后改口等于没申诉过。";

  /** 拦截标记口径。 */
  private static final String FLAG_SEMANTICS =
      "🔴 订单拦截标记是**时点事实**（append-only）：下单那一刻命中黑名单就落一行，"
          + "后来解除了黑名单**不改写这一行**（那会是一次事后改写事实）。"
          + "标记上的 recommended_action 只是**建议** —— 真的拦不拦单是 9.2.1.3 的职责，"
          + "本模块**不写订单状态**。";

  /** 下游口径。 */
  private static final String DOWNSTREAM_NOTE =
      "🔴 下游两个模块**本模块一律不代劳**："
          + "9.2.1.3 异常拦截（trade 服务，**已交付**）—— 它有自己的检查 / 人工处置闭环，"
          + "本模块只产出「建议动作」与拦截标记，**不写它的检查与处置表**（跨服务不写对方库）；"
          + "11 售后（trade 服务）—— 欺诈售后单由 11 自己的模块创建，本模块**不代建售后单**。"
          + "本模块**只出建议，不改订单状态**。";

  /** 边界口径。 */
  private static final String BOUNDARY_NOTE =
      "🔴 三条边界写死在能力声明里："
          + "① **不改 16.5.1.2 的 buyer_risk_score** —— 它的 blacklisted / blacklist_reason 两列"
          + "是评分时刻从 trade 画像抄来的**快照**，且该表被上游的 append-only 触发器钉死不可 UPDATE；"
          + "本模块**自建**黑名单登记表承载权威事实，将来由 16.5.1.2 自己来读（同服务同库 jOOQ 直读）。"
          + "② **读不到就是读不到**：9.2 审单不可达时本模块回 503，"
          + "**绝不**把「读不到」说成「这个买家没有风险」。"
          + "③ **不碰别的模块的表**：本模块的四张表都在自己的 buyer_blacklist_* 命名空间里，"
          + "跨表引用由触发器校验，不建任何跨服务外键。";

  private final DSLContext sql;
  private final BuyerBlacklistSourcePort source;

  public BuyerBlacklistService(DSLContext sql, BuyerBlacklistSourcePort source) {
    this.sql = sql;
    this.source = source;
  }

  // ================================================================ 能力声明

  /** 能力声明：逐项 available + ownerModule + 逐个上游读面的可用性 + 五条口径说明。 */
  public BuyerBlacklistCapabilities capabilities() {
    List<BuyerBlacklistCapability> capabilities =
        List.of(
            new BuyerBlacklistCapability(
                "MANUAL_REGISTER",
                "手工录入黑名单",
                true,
                "",
                "能：收件人三件套 ⇒ 主体键，登记时校验店铺真实存在；"
                    + "登记行 append-only，原始登记永远不被改写。"),
            new BuyerBlacklistCapability(
                "AUTO_REGISTER",
                "自动录入（来自风控信号）",
                true,
                "",
                "能：信号自带主体键与信号引用（**必须可追溯**）；登记人记为系统。"),
            new BuyerBlacklistCapability(
                "SCOPE_FILTER",
                "生效范围（仅本店铺 / 全店铺）",
                true,
                "",
                "能：受控值域由 DB 层 CHECK 钉死；判定按范围过滤，不只按租户过滤。"),
            new BuyerBlacklistCapability(
                "RELEASE",
                "解除（留痕）",
                true,
                "",
                "能：解除追加一条状态流转事件（谁 / 何时 / 为什么），登记行不可改。"),
            new BuyerBlacklistCapability(
                "APPEAL",
                "申诉解除",
                true,
                "",
                "能：申诉是一张单子，裁决只许走一次；通过 ⇒ 解除，驳回 ⇒ 维持。"),
            new BuyerBlacklistCapability(
                "ORDER_FLAG",
                "下单时自动标记（订单拦截标记）",
                true,
                "",
                "能：判定命中就落一条拦截标记（时点事实，append-only）。"
                    + "🔴 **只出建议动作**，真的拦不拦单是 9.2.1.3 的事。"),
            new BuyerBlacklistCapability(
                "INTERCEPT_ACTION",
                "执行拦截（改订单状态）",
                false,
                "9.2.1.3",
                "🔴 **本模块不代劳**：9.2.1.3 异常拦截（trade 服务）有自己的检查 / 人工处置闭环，"
                    + "本模块**不写它的表**（跨服务不写对方库），只产出建议与标记。"),
            new BuyerBlacklistCapability(
                "AFTERSALES_CASE",
                "对黑名单买家开售后单",
                false,
                "11.1.1.1",
                "🔴 **本模块不代劳**：售后单由 11 自己的模块创建，本模块**不代建售后单**。"));

    List<BuyerBlacklistSourceState> sources =
        List.of(
            new BuyerBlacklistSourceState(
                "ORDER_REVIEW_BUYER_PROFILES",
                "9.2.1.2 买家画像（trade）",
                true,
                "跨服务**只读**（授权 HTTP API）。读不到时本模块回 503，"
                    + "**不把「读不到」说成「没被拉黑」**；上游正常但查无此人时按「未知」处理，"
                    + "**无记录 ≠ 没风险**。"),
            new BuyerBlacklistSourceState(
                "BUYER_RISK_SCORE",
                "16.5.1.2 风险评分（channel，同库）",
                true,
                "同服务同库 jOOQ 直读（只读）：下单检查的结果里附上这个买家最近一次评分，"
                    + "让运营能对照「分数 ↔ 黑名单」。**没评过是 null，不是 0 分。**"));

    return new BuyerBlacklistCapabilities(
        capabilities,
        sources,
        SUBJECT_SEMANTICS,
        SCOPE_SEMANTICS,
        RELEASE_SEMANTICS,
        APPEAL_SEMANTICS,
        FLAG_SEMANTICS,
        DOWNSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ================================================================ 清单

  /** 黑名单清单（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public BuyerBlacklistEntryPage entries(
      String status, String scope, String riskLevel, String shopCode, String source, String keyword,
      int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("e.tenant_id = ?");
    binds.add(tenant);
    if (status != null && !status.isBlank()) {
      where.add("s.to_status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (scope != null && !scope.isBlank()) {
      where.add("e.scope = ?");
      binds.add(scope.trim().toUpperCase(Locale.ROOT));
    }
    if (riskLevel != null && !riskLevel.isBlank()) {
      where.add("s.risk_level = ?");
      binds.add(riskLevel.trim().toUpperCase(Locale.ROOT));
    }
    if (shopCode != null && !shopCode.isBlank()) {
      where.add("e.shop_code = ?");
      binds.add(shopCode.trim());
    }
    if (source != null && !source.isBlank()) {
      where.add("e.source = ?");
      binds.add(source.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      String like = "%" + keyword.trim() + "%";
      where.add("(e.buyer_key = ? or e.buyer_name ilike ? or e.buyer_email ilike ? or e.buyer_phone ilike ?)");
      binds.add(keyword.trim().toLowerCase(Locale.ROOT));
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from buyer_blacklist_entry e"
                        + latestStatusJoin()
                        + clause,
                    binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<BuyerBlacklistEntryView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select e.id, e.buyer_key, e.buyer_name, e.buyer_phone, e.buyer_email,"
                + " e.scope, e.shop_code, e.risk_level, e.reason as entry_reason,"
                + " e.source, e.source_ref, e.registered_at, e.registered_by, e.note, e.created_at,"
                + " s.to_status, s.event_type, s.actor, s.reason as status_reason,"
                + " s.occurred_at, s.risk_level as current_level,"
                + " (select count(*) from buyer_blacklist_appeal a"
                + "   where a.tenant_id = e.tenant_id and a.entry_id = e.id) as appeal_count,"
                + " (select count(*) from buyer_blacklist_appeal a"
                + "   where a.tenant_id = e.tenant_id and a.entry_id = e.id"
                + "   and a.decision = 'PENDING') as open_appeal_count,"
                + " (select count(*) from buyer_blacklist_order_flag f"
                + "   where f.tenant_id = e.tenant_id and f.entry_id = e.id) as flag_count"
                + " from buyer_blacklist_entry e"
                + latestStatusJoin()
                + clause
                + " order by e.registered_at desc, e.id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(toEntryView(row));
    }

    Record countsRow =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where s.to_status = 'ACTIVE') as active,"
                + " count(*) filter (where s.to_status = 'RELEASED') as released,"
                + " count(*) filter (where e.scope = 'SHOP_ONLY') as shop_only,"
                + " count(*) filter (where e.scope = 'ALL_SHOPS') as all_shops,"
                + " count(*) filter (where s.risk_level = 'CRITICAL') as critical,"
                + " count(*) filter (where s.risk_level = 'HIGH') as high"
                + " from buyer_blacklist_entry e"
                + latestStatusJoin()
                + clause,
            binds.toArray());
    BuyerBlacklistCounts counts =
        new BuyerBlacklistCounts(
            countsRow.get("total", Long.class),
            countsRow.get("active", Long.class),
            countsRow.get("released", Long.class),
            countsRow.get("shop_only", Long.class),
            countsRow.get("all_shops", Long.class),
            countsRow.get("critical", Long.class),
            countsRow.get("high", Long.class));

    return new BuyerBlacklistEntryPage(items, safePage, safeSize, total, counts);
  }

  /** 当前状态 = 最新一条状态事件（派生，不是登记行上的一个可改字段）。 */
  private static String latestStatusJoin() {
    return " join lateral ("
        + " select ev.to_status, ev.event_type, ev.actor, ev.reason, ev.occurred_at, ev.risk_level"
        + "   from buyer_blacklist_status_event ev"
        + "  where ev.tenant_id = e.tenant_id and ev.entry_id = e.id"
        + "  order by ev.event_seq desc limit 1"
        + " ) s on true";
  }

  /** 登记详情：头 + 状态流转留痕 + 申诉单。 */
  public BuyerBlacklistEntryDetail entry(String id) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) throw notFound();
    Record row =
        sql.fetchOne(
            "select e.id, e.buyer_key, e.buyer_name, e.buyer_phone, e.buyer_email,"
                + " e.scope, e.shop_code, e.risk_level, e.reason as entry_reason,"
                + " e.source, e.source_ref, e.registered_at, e.registered_by, e.note, e.created_at,"
                + " s.to_status, s.event_type, s.actor, s.reason as status_reason,"
                + " s.occurred_at, s.risk_level as current_level,"
                + " (select count(*) from buyer_blacklist_appeal a"
                + "   where a.tenant_id = e.tenant_id and a.entry_id = e.id) as appeal_count,"
                + " (select count(*) from buyer_blacklist_appeal a"
                + "   where a.tenant_id = e.tenant_id and a.entry_id = e.id"
                + "   and a.decision = 'PENDING') as open_appeal_count,"
                + " (select count(*) from buyer_blacklist_order_flag f"
                + "   where f.tenant_id = e.tenant_id and f.entry_id = e.id) as flag_count"
                + " from buyer_blacklist_entry e"
                + latestStatusJoin()
                + " where e.tenant_id = ? and e.id = ?",
            tenant,
            target);
    if (row == null) throw notFound();

    List<BuyerBlacklistStatusEventView> events = new ArrayList<>();
    for (Record event :
        sql.fetch(
            "select id, event_seq, event_type, from_status, to_status, risk_level, actor, reason,"
                + " appeal_id, occurred_at"
                + " from buyer_blacklist_status_event"
                + " where tenant_id = ? and entry_id = ? order by event_seq asc",
            tenant,
            target)) {
      events.add(
          new BuyerBlacklistStatusEventView(
              event.get("id", UUID.class),
              event.get("event_seq", Integer.class),
              event.get("event_type", String.class),
              eventLabel(event.get("event_type", String.class)),
              event.get("from_status", String.class),
              event.get("to_status", String.class),
              event.get("risk_level", String.class),
              event.get("actor", String.class),
              event.get("reason", String.class),
              event.get("appeal_id", UUID.class),
              event.get("occurred_at", OffsetDateTime.class)));
    }

    List<BuyerBlacklistAppealView> appeals = new ArrayList<>();
    for (Record appeal :
        sql.fetch(
            "select id, entry_id, appeal_reason, submitted_by, submitted_at, decision,"
                + " decided_by, decided_at, decision_reason"
                + " from buyer_blacklist_appeal where tenant_id = ? and entry_id = ?"
                + " order by submitted_at asc",
            tenant,
            target)) {
      appeals.add(
          new BuyerBlacklistAppealView(
              appeal.get("id", UUID.class),
              appeal.get("entry_id", UUID.class),
              appeal.get("appeal_reason", String.class),
              appeal.get("submitted_by", String.class),
              appeal.get("submitted_at", OffsetDateTime.class),
              appeal.get("decision", String.class),
              appeal.get("decided_by", String.class),
              appeal.get("decided_at", OffsetDateTime.class),
              appeal.get("decision_reason", String.class)));
    }

    return new BuyerBlacklistEntryDetail(toEntryView(row), events, appeals);
  }

  // ================================================================ 录入

  /**
   * 手工录入黑名单。
   *
   * <p>🔴 <b>先读 9.2 审单解析主体键</b>：读不到 ⇒ <b>503</b>（绝不按本地算出的键硬写一条）；
   * 上游正常但查无此人 ⇒ 按「未知」处理（**无记录 ≠ 没风险**，仍然允许登记 ——
   * 一个还没下过单的买家也可能被拉黑）。
   */
  public BuyerBlacklistEntryView register(String bearer, BuyerBlacklistRegisterInput input, String actor) {
    UUID tenant = currentTenant();
    Scope scope = parseScope(input.scope());
    RiskLevel level = parseLevel(input.riskLevel());
    checkScopeConsistency(scope, input.shopCode());
    String reason = input.reason().trim();

    ResolvedBuyer buyer =
        resolveBuyer(
            bearer,
            input.buyerName().trim(),
            input.buyerPhone().trim(),
            input.buyerEmail().trim());

    UUID entryId = Ids.next();
    OffsetDateTime now = dbNow();
    String shopCode = scope == Scope.ALL_SHOPS ? "" : input.shopCode().trim();
    String note = nz(input.note());

    rejectDuplicate(tenant, buyer.buyerKey(), scope, shopCode);
    requireShopExists(tenant, scope, shopCode);

    sql.execute(
        "insert into buyer_blacklist_entry"
            + " (id, tenant_id, buyer_key, buyer_name, buyer_phone, buyer_email,"
            + "  scope, shop_code, risk_level, reason, source, source_ref,"
            + "  registered_at, registered_by, note, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', '',"
            + "  ?::timestamptz, ?, ?, now())",
        entryId,
        tenant,
        buyer.buyerKey(),
        buyer.buyerName(),
        buyer.buyerPhone(),
        buyer.buyerEmail(),
        scope.name(),
        shopCode,
        level.name(),
        reason,
        now,
        actor,
        note);
    appendEvent(
        tenant, entryId, 1, Status.ACTIVE, Status.ACTIVE, EventType.REGISTERED, level, actor, reason,
        null, now);

    return entry(entryId.toString()).entry();
  }

  /**
   * 自动录入黑名单（来自风控信号）。
   *
   * <p>🔴 信号自带主体键与信号引用：<b>不</b>去 9.2 反查（信号方已经知道这个人的主体键），
   * 但**必须**把信号引用记下来（{@code source_ref}）—— 自动来源的黑名单必须可追溯。
   */
  public BuyerBlacklistEntryView registerAuto(BuyerBlacklistAutoInput input, String actor) {
    UUID tenant = currentTenant();
    Scope scope = parseScope(input.scope());
    RiskLevel level = parseLevel(input.riskLevel());
    checkScopeConsistency(scope, input.shopCode());
    String reason = input.reason().trim();
    String signalRef = input.signalRef().trim();

    UUID entryId = Ids.next();
    OffsetDateTime now = dbNow();
    String shopCode = scope == Scope.ALL_SHOPS ? "" : input.shopCode().trim();
    String note = nz(input.note());

    rejectDuplicate(tenant, input.buyerKey().trim().toLowerCase(Locale.ROOT), scope, shopCode);
    requireShopExists(tenant, scope, shopCode);

    sql.execute(
        "insert into buyer_blacklist_entry"
            + " (id, tenant_id, buyer_key, buyer_name, buyer_phone, buyer_email,"
            + "  scope, shop_code, risk_level, reason, source, source_ref,"
            + "  registered_at, registered_by, note, created_at)"
            + " values (?, ?, ?, '', '', '', ?, ?, ?, ?, 'RISK_SIGNAL', ?,"
            + "  ?::timestamptz, ?, ?, now())",
        entryId,
        tenant,
        input.buyerKey().trim().toLowerCase(Locale.ROOT),
        scope.name(),
        shopCode,
        level.name(),
        reason,
        signalRef,
        now,
        actor,
        note);
    appendEvent(
        tenant, entryId, 1, Status.ACTIVE, Status.ACTIVE, EventType.REGISTERED, level, actor, reason,
        null, now);

    return entry(entryId.toString()).entry();
  }

  // ================================================================ 解除

  /**
   * 解除黑名单（**追加一条事件**，登记行不可改）。
   *
   * @param reason 解除理由（**必填** —— 没有理由的解除等于「悄悄放过」）
   */
  public BuyerBlacklistEntryView release(String id, BuyerBlacklistReleaseInput input, String actor) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) throw notFound();
    String reason = input.reason().trim();

    BuyerBlacklistEntryDetail current = entry(id);
    if (!Status.ACTIVE.name().equals(current.entry().status())) {
      throw conflict("这条黑名单已经解除过了（当前状态：" + current.entry().statusLabel() + "）");
    }

    int nextSeq = nextSeq(tenant, target);
    appendEvent(
        tenant,
        target,
        nextSeq,
        Status.ACTIVE,
        Status.RELEASED,
        EventType.RELEASED,
        levelOf(current.entry().riskLevel()),
        actor,
        reason,
        null,
        dbNow());

    return entry(id).entry();
  }

  // ================================================================ 申诉

  /** 提交申诉（针对一条**生效中**的黑名单）。 */
  public BuyerBlacklistAppealView submitAppeal(
      String id, BuyerBlacklistAppealInput input, String actor) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) throw notFound();
    String reason = input.appealReason().trim();

    BuyerBlacklistEntryDetail current = entry(id);
    if (!Status.ACTIVE.name().equals(current.entry().status())) {
      throw conflict("这条黑名单已经解除过了，不需要再申诉。");
    }

    UUID appealId = Ids.next();
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into buyer_blacklist_appeal"
            + " (id, tenant_id, entry_id, appeal_reason, submitted_by, submitted_at,"
            + "  decision, decided_by, decided_at, decision_reason, created_at)"
            + " values (?, ?, ?, ?, ?, ?::timestamptz, 'PENDING', '', null, '', now())",
        appealId,
        tenant,
        target,
        reason,
        actor,
        now);

    return new BuyerBlacklistAppealView(
        appealId, target, reason, actor, now, "PENDING", "", null, "");
  }

  /**
   * 申诉裁决。
   *
   * <p>APPROVED ⇒ 追加一条 {@code APPEAL_APPROVED} 事件并解除；
   * REJECTED ⇒ 追加一条 {@code APPEAL_REJECTED} 事件并维持黑名单。
   */
  public BuyerBlacklistAppealView decideAppeal(
      String appealId, BuyerBlacklistAppealDecisionInput input, String actor) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(appealId);
    if (target == null) throw notFound();

    Record row =
        sql.fetchOne(
            "select id, entry_id, appeal_reason, submitted_by, submitted_at, decision,"
                + " decided_by, decided_at, decision_reason"
                + " from buyer_blacklist_appeal where tenant_id = ? and id = ?",
            tenant,
            target);
    if (row == null) throw notFound();
    if (!"PENDING".equals(row.get("decision", String.class))) {
      throw conflict("这张申诉单已经裁决过了（" + row.get("decision", String.class) + "），不可二次裁决。");
    }
    UUID entryId = row.get("entry_id", UUID.class);
    String decision = input.decision().trim().toUpperCase(Locale.ROOT);
    String decisionReason = input.decisionReason().trim();

    BuyerBlacklistEntryDetail current = entry(entryId.toString());
    if (!Status.ACTIVE.name().equals(current.entry().status())) {
      throw conflict("这条黑名单已经解除过了，不需要再裁决申诉。");
    }

    OffsetDateTime now = dbNow();
    int nextSeq = nextSeq(tenant, entryId);
    boolean approved = "APPROVED".equals(decision);
    appendEvent(
        tenant,
        entryId,
        nextSeq,
        Status.ACTIVE,
        approved ? Status.RELEASED : Status.ACTIVE,
        approved ? EventType.APPEAL_APPROVED : EventType.APPEAL_REJECTED,
        levelOf(current.entry().riskLevel()),
        actor,
        decisionReason,
        target,
        now);

    sql.execute(
        "update buyer_blacklist_appeal set decision = ?, decided_by = ?,"
            + " decided_at = ?::timestamptz, decision_reason = ?"
            + " where tenant_id = ? and id = ?",
        decision,
        actor,
        now,
        decisionReason,
        tenant,
        target);

    return new BuyerBlacklistAppealView(
        target,
        entryId,
        row.get("appeal_reason", String.class),
        row.get("submitted_by", String.class),
        row.get("submitted_at", OffsetDateTime.class),
        decision,
        actor,
        now,
        decisionReason);
  }

  // ================================================================ 下单检查

  /**
   * 下单时检查：判定 + 落一条订单拦截标记（命中才落）。
   *
   * <p>🔴 9.2 审单读不到 ⇒ <b>整个请求 503</b>：本模块**绝不**把「读不到」说成
   * 「这个买家没有风险」—— 那是一次真实的欺诈损失。
   */
  public BuyerBlacklistCheckResult check(String bearer, BuyerBlacklistCheckInput input) {
    UUID tenant = currentTenant();
    String shopCode = nz(input.shopCode()).trim();
    String orderRef = nz(input.orderRef()).trim();

    ResolvedBuyer buyer =
        resolveBuyer(
            bearer,
            input.buyerName().trim(),
            input.buyerPhone().trim(),
            input.buyerEmail().trim());

    Record match =
        sql.fetchOne(
            "select e.id, e.scope, e.shop_code, s.risk_level, s.reason as status_reason,"
                + " s.occurred_at"
                + " from buyer_blacklist_entry e"
                + latestStatusJoin()
                + " where e.tenant_id = ? and e.buyer_key = ? and s.to_status = 'ACTIVE'"
                + "   and (e.scope = 'ALL_SHOPS' or e.shop_code = ?)"
                + " order by case s.risk_level when 'CRITICAL' then 4 when 'HIGH' then 3"
                + "          when 'MEDIUM' then 2 else 1 end desc, s.occurred_at desc limit 1",
            tenant,
            buyer.buyerKey(),
            shopCode);

    Record score =
        sql.fetchOne(
            "select id, risk_score, evaluation_status, recommended_action"
                + " from buyer_risk_score where tenant_id = ? and buyer_key = ?"
                + " order by created_at desc limit 1",
            tenant,
            buyer.buyerKey());

    if (match == null) {
      return new BuyerBlacklistCheckResult(
          false,
          buyer.buyerKey(),
          buyer.buyerName(),
          shopCode,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          true,
          buyer.known() ? "9.2 审单已确认这个人。" : "9.2 审单里没有这个人的画像（**无记录 ≠ 没风险**）。",
          buyer.known(),
          score == null ? null : score.get("id", UUID.class),
          score == null ? null : score.get("risk_score", BigDecimal.class),
          score == null ? null : score.get("evaluation_status", String.class),
          score == null ? null : score.get("recommended_action", String.class));
    }

    RiskLevel level = levelOf(match.get("risk_level", String.class));
    FlagAction action = level.action();
    UUID entryId = match.get("id", UUID.class);
    OffsetDateTime matchedAt = match.get("occurred_at", OffsetDateTime.class);
    UUID flagId = Ids.next();

    sql.execute(
        "insert into buyer_blacklist_order_flag"
            + " (id, tenant_id, buyer_key, shop_code, order_ref, entry_id, matched_scope,"
            + "  risk_level, recommended_action, flagged_at, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, now())",
        flagId,
        tenant,
        buyer.buyerKey(),
        shopCode,
        orderRef,
        entryId,
        match.get("scope", String.class),
        level.name(),
        action.name(),
        matchedAt);

    return new BuyerBlacklistCheckResult(
        true,
        buyer.buyerKey(),
        buyer.buyerName(),
        shopCode,
        entryId,
        match.get("scope", String.class),
        scopeLabel(match.get("scope", String.class)),
        match.get("shop_code", String.class),
        level.name(),
        level.label(),
        match.get("status_reason", String.class),
        action.name(),
        action.label(),
        matchedAt,
        flagId,
        true,
        true,
        buyer.known() ? "9.2 审单已确认这个人。" : "9.2 审单里没有这个人的画像（**无记录 ≠ 没风险**）。",
        buyer.known(),
        score == null ? null : score.get("id", UUID.class),
        score == null ? null : score.get("risk_score", BigDecimal.class),
        score == null ? null : score.get("evaluation_status", String.class),
        score == null ? null : score.get("recommended_action", String.class));
  }

  // ================================================================ 订单拦截标记

  /** 订单拦截标记清单（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public BuyerBlacklistFlagPage flags(String buyerKey, String action, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("f.tenant_id = ?");
    binds.add(tenant);
    if (buyerKey != null && !buyerKey.isBlank()) {
      where.add("f.buyer_key = ?");
      binds.add(buyerKey.trim().toLowerCase(Locale.ROOT));
    }
    if (action != null && !action.isBlank()) {
      where.add("f.recommended_action = ?");
      binds.add(action.trim().toUpperCase(Locale.ROOT));
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from buyer_blacklist_order_flag f" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<BuyerBlacklistOrderFlagView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select f.id, f.buyer_key, f.shop_code, f.order_ref, f.entry_id, f.matched_scope,"
                + " f.risk_level, f.recommended_action, f.flagged_at, e.buyer_name"
                + " from buyer_blacklist_order_flag f"
                + " join buyer_blacklist_entry e on e.tenant_id = f.tenant_id and e.id = f.entry_id"
                + clause
                + " order by f.flagged_at desc, f.id desc limit ? offset ?",
            pageBinds.toArray())) {
      String actionValue = row.get("recommended_action", String.class);
      items.add(
          new BuyerBlacklistOrderFlagView(
              row.get("id", UUID.class),
              row.get("buyer_key", String.class),
              row.get("buyer_name", String.class),
              row.get("shop_code", String.class),
              row.get("order_ref", String.class),
              row.get("entry_id", UUID.class),
              row.get("matched_scope", String.class),
              row.get("risk_level", String.class),
              actionValue,
              actionLabel(actionValue),
              row.get("flagged_at", OffsetDateTime.class)));
    }

    Record countsRow =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where recommended_action = 'BLOCK') as block,"
                + " count(*) filter (where recommended_action = 'REVIEW') as review,"
                + " count(*) filter (where recommended_action = 'WATCH') as watch"
                + " from buyer_blacklist_order_flag where tenant_id = ?",
            tenant);
    BuyerBlacklistFlagCounts counts =
        new BuyerBlacklistFlagCounts(
            countsRow.get("total", Long.class),
            countsRow.get("block", Long.class),
            countsRow.get("review", Long.class),
            countsRow.get("watch", Long.class));

    return new BuyerBlacklistFlagPage(items, safePage, safeSize, total, counts);
  }

  // ================================================================ 内部

  /** 解析出来的买家主体（**known=false 不等于「没风险」**）。 */
  private record ResolvedBuyer(
      String buyerKey, String buyerName, String buyerPhone, String buyerEmail, boolean known) {}

  /**
   * 解析买家主体：先按主体键精确匹配，再按邮箱精确反查。
   *
   * <p>🔴 上游不可达 ⇒ <b>503</b>：本模块**绝不**把「读不到」说成「没被拉黑」。
   */
  private ResolvedBuyer resolveBuyer(String bearer, String name, String phone, String email) {
    String key = BuyerRiskScoreSourcePort.buyerKey(name, phone, email);
    SourceProfiles profiles = source.profiles(bearer);
    if (!profiles.available()) {
      throw upstreamUnavailable(profiles.note());
    }
    for (SourceBuyerProfile profile : profiles.profiles()) {
      if (profile.buyerKey().equals(key)) {
        return new ResolvedBuyer(
            profile.buyerKey(),
            profile.buyerName(),
            profile.buyerPhone(),
            profile.buyerEmail(),
            true);
      }
    }
    // 邮箱精确反查（V149 同款：同一个人的姓名拼写可能不同）。
    if (!email.isEmpty()) {
      for (SourceBuyerProfile profile : profiles.profiles()) {
        if (email.equalsIgnoreCase(profile.buyerEmail())) {
          return new ResolvedBuyer(
              profile.buyerKey(),
              profile.buyerName(),
              profile.buyerPhone(),
              profile.buyerEmail(),
              true);
        }
      }
    }
    return new ResolvedBuyer(key, name, phone, email, false);
  }

  /** 追加一条状态流转事件（append-only）。 */
  private void appendEvent(
      UUID tenant,
      UUID entryId,
      int seq,
      Status from,
      Status to,
      EventType type,
      RiskLevel level,
      String actor,
      String reason,
      UUID appealId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into buyer_blacklist_status_event"
            + " (id, tenant_id, entry_id, event_seq, from_status, to_status, event_type,"
            + "  risk_level, actor, reason, appeal_id, occurred_at, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, now())",
        Ids.next(),
        tenant,
        entryId,
        seq,
        from.name(),
        to.name(),
        type.name(),
        level.name(),
        actor,
        reason,
        appealId,
        occurredAt);
  }

  /** 下一条事件的序号（当前最大序号 + 1）。 */
  private int nextSeq(UUID tenant, UUID entryId) {
    Object raw =
        sql.fetchValue(
            "select coalesce(max(event_seq), 0) from buyer_blacklist_status_event"
                + " where tenant_id = ? and entry_id = ?",
            tenant,
            entryId);
    return ((Number) raw).intValue() + 1;
  }

  /**
   * 「仅本店铺」的店铺必须**真实存在且属于当前租户**。
   *
   * <p>🔴 挂在一个不存在的店铺上的黑名单**永远不会生效**（判定按店铺过滤，过滤不到）——
   * 运营会以为「已经拉黑了 A 店」，其实一条都拦不住。DB 层有触发器兜底，
   * 这里先查一次是为了把「店铺不存在」说成一句人话。
   */
  private void requireShopExists(UUID tenant, Scope scope, String shopCode) {
    if (scope != Scope.SHOP_ONLY) return;
    String code = nz(shopCode).trim();
    Object raw =
        sql.fetchValue(
            "select count(*) from shop where tenant_id = ? and code = ?", tenant, code);
    if (((Number) raw).intValue() == 0) {
      throw unprocessable(
          "店铺 " + code + " 不存在或不属于当前租户 —— 仅本店铺生效的黑名单必须挂在一个真实店铺上。");
    }
  }

  /** 同一买家 + 同一生效范围下**只能有一条生效中**的黑名单（服务层先拦，DB 层再兜）。 */
  private void rejectDuplicate(UUID tenant, String buyerKey, Scope scope, String shopCode) {    Object raw =
        sql.fetchValue(
            "select count(*) from buyer_blacklist_entry e"
                + latestStatusJoin()
                + " where e.tenant_id = ? and e.buyer_key = ? and e.scope = ? and e.shop_code = ?"
                + "   and s.to_status = 'ACTIVE'",
            tenant,
            buyerKey,
            scope.name(),
            shopCode);
    if (((Number) raw).intValue() > 0) {
      throw conflict(
          "这个买家在同一生效范围下已经有一条生效中的黑名单了 —— 先解除那一条，或者换一个生效范围。");
    }
  }

  /** 一行 → 视图。 */
  private BuyerBlacklistEntryView toEntryView(Record row) {
    String scope = row.get("scope", String.class);
    String level = row.get("current_level", String.class);
    String source = row.get("source", String.class);
    String eventType = row.get("event_type", String.class);
    String status = row.get("to_status", String.class);
    return new BuyerBlacklistEntryView(
        row.get("id", UUID.class),
        row.get("buyer_key", String.class),
        row.get("buyer_name", String.class),
        row.get("buyer_phone", String.class),
        row.get("buyer_email", String.class),
        scope,
        scopeLabel(scope),
        row.get("shop_code", String.class),
        level,
        levelLabel(level),
        row.get("entry_reason", String.class),
        source,
        sourceLabel(source),
        row.get("source_ref", String.class),
        status,
        statusLabel(status),
        eventType,
        eventLabel(eventType),
        row.get("actor", String.class),
        row.get("status_reason", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("registered_at", OffsetDateTime.class),
        row.get("registered_by", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("appeal_count", Long.class),
        row.get("open_appeal_count", Long.class),
        row.get("flag_count", Long.class));
  }

  // ================================================================ 小工具

  private static Scope parseScope(String raw) {
    try {
      return Scope.parse(raw);
    } catch (IllegalArgumentException e) {
      throw unprocessable(e.getMessage());
    }
  }

  private static RiskLevel parseLevel(String raw) {
    try {
      return RiskLevel.parse(raw);
    } catch (IllegalArgumentException e) {
      throw unprocessable(e.getMessage());
    }
  }

  private static void checkScopeConsistency(Scope scope, String shopCode) {
    try {
      BuyerBlacklistRules.requireScopeConsistent(scope, shopCode);
    } catch (IllegalArgumentException e) {
      throw unprocessable(e.getMessage());
    }
  }

  private static RiskLevel levelOf(String raw) {
    for (RiskLevel value : RiskLevel.values()) {
      if (value.name().equals(raw)) return value;
    }
    return RiskLevel.LOW;
  }

  private static String scopeLabel(String raw) {
    for (Scope value : Scope.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private static String levelLabel(String raw) {
    for (RiskLevel value : RiskLevel.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private static String sourceLabel(String raw) {
    for (Source value : Source.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private static String statusLabel(String raw) {
    for (Status value : Status.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private static String eventLabel(String raw) {
    for (EventType value : EventType.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private static String actionLabel(String raw) {
    for (FlagAction value : FlagAction.values()) {
      if (value.name().equals(raw)) return value.label();
    }
    return raw;
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  /**
   * 🔴 上游读不到 ⇒ <b>503</b>。
   *
   * <p>理由与 16.5.1.2 一致但后果更重：本模块的产出是「这个买家被拉黑了 / 没被拉黑」，
   * 把「读不到」折成「没风险」是一次真实的欺诈损失。
   */
  private static ResponseStatusException upstreamUnavailable(String note) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "读不到 9.2 审单的买家画像（"
            + nz(note)
            + "）—— 本模块不会用「读不到」冒充「这个买家没有风险」，请稍后重试。");
  }

  private static UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
