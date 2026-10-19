package com.starcon.channel;

import static com.starcon.channel.ListingLifecycleModels.*;
import static com.starcon.channel.ListingLifecycleStateMachine.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.1.1.3 生命周期与下架。
 *
 * <p>蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。输出：状态变更记录
 * （来源：平台 or 人工）+ 可选的平台操作任务。关系：上游 = 02 同步、4.3.2.2；下游 = 02 回写队列（5.1.1）。
 * <b>平台下架与内部下架是两个事实</b>，分别记录并展示差异 —— 平台把商品下架了而内部不知情，
 * 是最常见的漏单原因。」
 *
 * <p>🔴 本类的六条不变量，每一条都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 事件记录不交给服务层写</b>：状态一变，V171 的
 *       {@code record_listing_status_change()} 触发器就地落一条事件。本服务**从不**直接
 *       {@code INSERT listing_status_event} —— 只负责 {@code UPDATE listing.status}，
 *       并通过事务级 GUC {@code app.lifecycle_context} 告诉触发器「这次的来源是平台还是人工」。
 *       手工 INSERT 会与触发器重复记，也会编出一条事实。
 *   <li><b>🔴🔴 平台下架与内部下架是两个事实</b>：事件来源落在
 *       {@code listing_status_event.source}（MANUAL / PLATFORM），人工动作的意图落在
 *       {@code listing_lifecycle_action}。两者都**不混**在一个字段里。
 *   <li><b>🔴 差异是算出来的，不是文案</b>：内部状态与平台侧最新事实的可售性口径逐项比对，
 *       结论落在 {@code divergenceKind} / {@code divergenceNote} 上。判定在 **SQL 侧**做
 *       （这样差异区才能真分页），Java 侧另有一份等价实现，集成测试专门钉住两者不跑偏。
 *   <li><b>🔴 非法迁移显式拒绝并给出可读原因</b>：不是 500、不是「默默不动」。
 *       理由由状态机给出，前端原样显示。
 *   <li><b>🔴 主动下架的回写复用 02 回写队列</b>：{@code writeback_batch(kind='DELIST')} +
 *       {@code writeback_batch_item} + {@code writeback_target}，
 *       <b>不新建平行任务表、不改 kind 的 CHECK</b>。
 *   <li><b>🔴 本模块不派发平台任务</b>：只**生成**任务（{@code platform_operation}），
 *       派发归 2.5.1.1。能力表里如实声明。
 * </ol>
 *
 * <p>🔴 时间口径：{@code timestamptz} 一律以 <b>ISO-8601 带偏移量的文本</b>绑定
 * （{@code ?::timestamptz}），存进去的就是那个瞬间，<b>与 JVM 时区无关</b>。
 * 用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s ——
 * 页面上看不出异常，平台侧却收货时间对不上。
 */
@Service
public class ListingLifecycleService {

  /** 七态在 SQL 里的字面量（由常量拼出来，避免两处各写一份）。 */
  private static final String SEVEN_STATES_SQL =
      "('" + String.join("','", LIFECYCLE_STATUSES) + "')";

  /**
   * 「这条事件是**内部状态变更**」的判据：起始与目标状态**都**落在七态里。
   *
   * <p>🔴 为什么两个都要看：4.3.2.2 的平台事件把 {@code to_status} 写成**平台自己的 token**
   * （ACTIVE / INACTIVE / SUPPRESSED…），而内部状态变更的 to_status 一定是七态之一。
   * 只看 to_status 的话，平台恰好用了一个与七态同名的 token（例如 {@code REJECTED}）
   * 就会被误判成内部状态变更。两个都看，误判面只剩「平台连续两个 token 都与七态同名」。
   */
  private static final String INTERNAL_EVENT_PREDICATE =
      "(coalesce(e.from_status,'') in "
          + SEVEN_STATES_SQL
          + " and coalesce(e.to_status,'') in "
          + SEVEN_STATES_SQL
          + ")";

  /** 内部可售性口径（与 4.3.2.2 的 {@code internalSellability} 同款，集成测试钉住两者一致）。 */
  private static final String INTERNAL_SELLABILITY_SQL =
      "case l.status when 'ON_SALE' then 'SELLABLE' when 'UNDER_REVIEW' then 'PENDING' "
          + "when 'REJECTED' then 'PENDING' else 'NOT_SELLABLE' end";

  /** 平台可售性口径（与 4.3.2.2 的 {@code platformSellability} 同款，集成测试钉住两者一致）。 */
  private static final String PLATFORM_SELLABILITY_SQL =
      "case plat.event_type when 'APPROVED' then 'SELLABLE' "
          + "when 'REJECTED' then 'NOT_SELLABLE' when 'DELISTED' then 'NOT_SELLABLE' "
          + "when 'SUPPRESSED' then 'NOT_SELLABLE' when 'THROTTLED' then 'RESTRICTED' "
          + "when 'CATEGORY_REVIEW' then 'RESTRICTED' when 'PRICE_ALERT' then 'RESTRICTED' "
          + "else 'PENDING' end";

  /**
   * 🔴 差异判定的 SQL 表达式。
   *
   * <p>四种差异（顺序即严重程度）：
   *
   * <ol>
   *   <li>{@code PLATFORM_DELISTED_INTERNAL_ON_SALE} —— 平台不在售、内部在售。
   *       <b>最常见的漏单原因</b>：平台把商品下架了而内部不知情。
   *   <li>{@code INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE} —— 内部不在售、平台在售。
   *       内部说下了（或封了），平台还在卖。
   *   <li>{@code INTERNAL_DELISTED_NOT_WRITEBACK} —— 内部已主动下架，且那次下架**明确没有**
   *       选择回写平台 ⇒ 平台侧不知情。
   *   <li>{@code INTERNAL_DELISTED_NO_WRITEBACK_RECORD} —— 内部已主动下架，但**找不到**下架
   *       动作留痕（多半走的是 4.1.1.1 的批量下架，那条路径不回写平台）。
   * </ol>
   */
  private static final String DIVERGENCE_SQL =
      "case"
          + " when plat.event_type is not null and ("
          + INTERNAL_SELLABILITY_SQL
          + ")='SELLABLE' and ("
          + PLATFORM_SELLABILITY_SQL
          + ")='NOT_SELLABLE' then 'PLATFORM_DELISTED_INTERNAL_ON_SALE'"
          + " when plat.event_type is not null and ("
          + INTERNAL_SELLABILITY_SQL
          + ")='NOT_SELLABLE' and ("
          + PLATFORM_SELLABILITY_SQL
          + ")='SELLABLE' then 'INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE'"
          + " when l.status='DELISTED_BY_US' and delist.writeback_requested = false"
          + " then 'INTERNAL_DELISTED_NOT_WRITEBACK'"
          + " when l.status='DELISTED_BY_US' and delist.writeback_requested is null"
          + " then 'INTERNAL_DELISTED_NO_WRITEBACK_RECORD'"
          + " else '' end";

  /** 列表 / 差异区共用的 FROM 子句。 */
  private static final String LIST_FROM =
      """
      from listing l
      join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
      join platform p on p.id=s.platform_id
      left join lateral (
        select e.source, e.from_status, e.to_status, e.occurred_at
        from listing_status_event e
        where e.tenant_id=l.tenant_id and e.listing_id=l.id
        order by e.occurred_at desc, e.id desc
        limit 1
      ) last on true
      left join lateral (
        select e.event_type, e.platform_status, e.occurred_at
        from listing_status_event e
        where e.tenant_id=l.tenant_id and e.listing_id=l.id and e.source='PLATFORM'
          and not %s
        order by e.occurred_at desc, e.id desc
        limit 1
      ) plat on true
      left join lateral (
        select a.writeback_requested
        from listing_lifecycle_action a
        where a.tenant_id=l.tenant_id and a.listing_id=l.id and a.action='DELIST'
        order by a.created_at desc, a.id desc
        limit 1
      ) delist on true
      where l.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
      """
          .formatted(INTERNAL_EVENT_PREDICATE);

  private static final String LIST_COLUMNS =
      """
      select l.id, l.external_item_id, l.title, l.status, l.updated_at,
             s.code as shop_code, s.name as shop_name, p.code as platform_code,
             last.source as last_source, last.from_status as last_from_status,
             last.to_status as last_to_status, last.occurred_at as last_at,
             plat.event_type as plat_event_type, plat.platform_status as plat_status,
             plat.occurred_at as plat_at,
             (select count(*) from listing_status_event e
               where e.tenant_id=l.tenant_id and e.listing_id=l.id) as event_count,
             %s as divergence_kind
      """
          .formatted(DIVERGENCE_SQL);

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingLifecycleService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static int clampSize(int size) {
    return size <= 0 ? 50 : Math.min(size, 200);
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  /** timestamptz 绑定：ISO-8601 带偏移量的文本 ⇒ 存进去的就是那个瞬间，与 JVM 时区无关。 */
  private static String ts(OffsetDateTime value) {
    return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块能**真实交付**的是：七态状态机与显式拒绝、人工动作（主动下架 / 重新上架 /
   * 提交平台审核 / 标记被封禁）、平台状态被动同步（单条 + 批量）、状态变更记录与来源标注、
   * 平台侧与内部侧的差异区、主动下架的可选平台回写（**生成**任务）。
   *
   * <p>它**不能**在本批真实交付的有五段，逐条声明而不是假装具备：平台任务的实际派发
   * （2.5.1.1）、重新上架的平台回写（4.3.1.2 / 4.3.2.2）、平台状态的轮询调度器
   * （02 同步运行时）、批量生命周期动作（4.1.1.1）、被封禁的申诉工单（4.3.2.2 / 16.2.1.2）。
   */
  public ListingLifecycleCapabilities capabilities() {
    return new ListingLifecycleCapabilities(
        List.of(
            new ListingLifecycleCapabilityDimension(
                "state_machine",
                "🔴 七态状态机：草稿 / 在售 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 / 被封禁；"
                    + "非法迁移**显式拒绝并给出可读原因**（不是 500、不是默默不动）",
                true, "listing.status 的七态 CHECK（V171）+ ListingLifecycleStateMachine", "",
                "拒绝理由里逐条写明「当前状态是什么、合法后继有哪些」—— "
                    + "运营看到的是「现在这样不行，可以这样」，而不是一个 500"),
            new ListingLifecycleCapabilityDimension(
                "manual_actions",
                "🔴 人工动作：主动下架 / 重新上架 / 提交平台审核 / 标记被封禁；"
                    + "动因（为什么下架）逐条留痕",
                true, "listing_lifecycle_action（V201 新表）+ listing.status", "",
                "动作只改内部状态，**不碰价格与库存**（那归 05 / 07）"),
            new ListingLifecycleCapabilityDimension(
                "platform_status_sync",
                "🔴 平台侧状态变更**被动同步**：平台说审核通过 / 驳回 / 下架 / 抑制时，"
                    + "内部状态随之联动；限流 / 类目审核 / 价格异常**不改变**内部状态"
                    + "（商品仍在售，平台只是加了限制）",
                true, "listing_status_event（V199）+ POST /listings/{uuid}/platform-status + POST /sync", "",
                "平台路径比人工路径宽松：平台说的是**事实**，内部状态要做的是跟上，"
                    + "而不是「因为不合我的状态机就拒绝」—— 否则就是蓝图点名的漏单"),
            new ListingLifecycleCapabilityDimension(
                "status_event_log",
                "🔴 状态变更记录：真分页 + 按来源 / 事件类型 / 时间筛选；"
                    + "**每条事件显式标注来源（平台 / 人工）**",
                true, "listing_status_event（V171 建表，V199 扩展，V201 扩展触发器的来源语义）", "",
                "事件由 **DB 触发器**落 ⇒ 服务层漏记不了；来源由触发器按 "
                    + "app.lifecycle_context 决定，平台联动不会被记成「人工下架」"),
            new ListingLifecycleCapabilityDimension(
                "platform_vs_internal_divergence",
                "🔴 平台下架 vs 内部下架的**差异区**：内部状态与平台侧最新事实的可售性口径"
                    + "逐项比对，四类差异逐条列出并写明两边状态",
                true, "listing.status + listing_status_event + listing_lifecycle_action", "",
                "判定在 SQL 侧做（差异区才能真分页），Java 侧另有一份等价实现，"
                    + "集成测试钉住两者不跑偏"),
            new ListingLifecycleCapabilityDimension(
                "delist_writeback",
                "🔴 主动下架时**选择是否回写平台**：选了就真的产生一个平台操作任务"
                    + "（writeback_batch kind='DELIST' + 批次条目 + 平台队列任务）",
                true, "writeback_target / writeback_batch / writeback_batch_item（V169）+ platform_operation（V2）", "",
                "复用 02 回写队列，**不新建平行下架任务表**、不改 kind 的 CHECK；"
                    + "没选回写时只改内部状态，平台侧不知情 —— 这一点在差异区里显式提示"),
            new ListingLifecycleCapabilityDimension(
                "platform_dispatch",
                "🔴 平台操作任务的**实际派发**（把 writeback_batch 发往平台并收回执）",
                false, "—", "2.5.1.1 平台回写队列",
                "本模块只**生成**任务（platform_operation 入队），不负责派发 —— "
                    + "派发与重试、未知结果核验是 2.5.1.1 / 2.5.1.2 的主权"),
            new ListingLifecycleCapabilityDimension(
                "republish_writeback",
                "🔴 重新上架的**平台回写**（把商品真的推回平台）",
                false, "—", "4.3.1.2 批量发布 / 4.3.2.2 一键修正重发",
                "02 回写队列的 writeback_batch.kind 值域里**没有**「重新上架」"
                    + "（既有 CHECK 不能动），平台侧的上架动作归上面两个模块 —— "
                    + "宁可少给一个必然 500 的选项，也不给"),
            new ListingLifecycleCapabilityDimension(
                "platform_poll_scheduler",
                "🔴 平台状态的**轮询调度器**（定时去平台捞状态变化的那个调度器）",
                false, "—", "02 同步（channel sync 运行时）",
                "本模块提供平台状态的**写入入口**（单条 + 批量同步），"
                    + "但没有那个定时调度器 —— 平台限流与店铺安全都由 02 的配额统一裁决，"
                    + "本模块不自建轮询"),
            new ListingLifecycleCapabilityDimension(
                "batch_lifecycle_actions",
                "🔴 **批量**生命周期动作（一次勾选多条商品下架 / 上架）",
                false, "—", "4.1.1.1 Listing 列表（批量操作通道）",
                "4.1.1.1 已有批量操作通道，但那条路径**不经过本模块的状态机与回写通道** —— "
                    + "批量下架不会产生平台回写任务，所以「内部下架了平台还在卖」"
                    + "正是本模块差异区要显式提示的一种情况"),
            new ListingLifecycleCapabilityDimension(
                "block_appeal_workflow",
                "🔴 被封禁商品的**申诉工单**（申诉流程、时限、跟进责任人）",
                false, "—", "4.3.2.2 需处理队列 / 16.2.1.2 合规拦截",
                "本模块只把「被封禁」如实记成内部状态并留痕，"
                    + "不擅自把一个状态变成一个申诉工单")),
        LIFECYCLE_STATUSES,
        ACTION_SOURCES,
        ACTIONS,
        PLATFORM_EVENT_TYPES,
        INGEST_CHANNELS,
        rule(),
        divergenceRule(),
        "上游 = 02 同步（平台适配器把平台状态推过来 / 轮询补捞）、4.3.2.2 平台状态回流"
            + "（平台侧状态的权威来源与事件落库处）。下游 = 02 回写队列（5.1.1 / 2.5.1.1）—— "
            + "主动下架选择回写平台时，平台操作任务落在既有的 writeback_batch 上，"
            + "**不新建平行任务表**。本模块**无任何金额列**：生命周期与价格、库存无关。");
  }

  /** 筛选面板词表 —— 不硬编码在前端。 */
  public ListingLifecycleFilterOptions filterOptions() {
    return new ListingLifecycleFilterOptions(
        LIFECYCLE_STATUSES,
        ACTION_SOURCES,
        ACTIONS,
        PLATFORM_EVENT_TYPES,
        INGEST_CHANNELS,
        List.of(
            "PLATFORM_DELISTED_INTERNAL_ON_SALE",
            "INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE",
            "INTERNAL_DELISTED_NOT_WRITEBACK",
            "INTERNAL_DELISTED_NO_WRITEBACK_RECORD"));
  }

  // ==================================================================
  // 列表
  // ==================================================================

  /**
   * 生命周期列表（真分页 + 真筛选）。
   *
   * @param status 七态之一；不给 = 全部
   * @param source 按**最近一次状态变更的来源**筛（MANUAL / PLATFORM）
   */
  public ListingLifecyclePage list(
      String status,
      String source,
      String shopCode,
      String platformCode,
      String keyword,
      Boolean onlyDivergence,
      int page,
      int size) {
    int p = clampPage(page);
    int s = clampSize(size);

    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder();

    String st = blankToNull(status);
    if (st != null) {
      if (!LIFECYCLE_STATUSES.contains(st)) {
        throw unprocessable(
            "未知的生命周期状态："
                + st
                + "。七态："
                + String.join(" / ", LIFECYCLE_STATUSES));
      }
      where.append(" and l.status = ?");
      binds.add(st);
    }

    String src = blankToNull(source);
    if (src != null) {
      if (!ACTION_SOURCES.contains(src)) {
        throw unprocessable(
            "未知的事件来源：" + src + "。只有 " + String.join(" / ", ACTION_SOURCES));
      }
      where.append(" and last.source = ?");
      binds.add(src);
    }

    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code = ?");
      binds.add(shop);
    }

    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code = ?");
      binds.add(platform);
    }

    String q = blankToNull(keyword);
    if (q != null) {
      where.append(" and (l.title ilike ? escape '!' or l.external_item_id ilike ? escape '!')");
      String like = "%" + q.replace("!", "!!") + "%";
      binds.add(like);
      binds.add(like);
    }

    if (Boolean.TRUE.equals(onlyDivergence)) {
      where.append(" and (" + DIVERGENCE_SQL + ") <> ''");
    }

    String from = LIST_FROM + where;
    long total =
        sql.fetchOne("select count(*)" + from, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<Record> rows =
        sql.fetch(
            LIST_COLUMNS + from + " order by l.updated_at desc, l.id desc limit ? offset ?",
            pageBinds.toArray());

    return new ListingLifecyclePage(
        rows.stream().map(ListingLifecycleService::mapRow).toList(), p, s, total);
  }

  /**
   * 🔴 差异区：平台侧与内部侧说法相反的清单（真分页）。
   *
   * <p>🔴 「主动下架但没回写平台」也在这里 —— 内部说下了、平台不知道，
   * 平台可能还在卖，而这件事只写在 {@code listing_lifecycle_action} 里，
   * 不看动作留痕就发现不了。
   */
  public ListingLifecycleDivergencePage divergences(int page, int size) {
    int p = clampPage(page);
    int s = clampSize(size);

    String from = LIST_FROM + " and (" + DIVERGENCE_SQL + ") <> ''";
    long total = sql.fetchOne("select count(*)" + from).get(0, Long.class);

    List<Record> rows =
        sql.fetch(
            LIST_COLUMNS
                + from
                + " order by l.updated_at desc, l.id desc limit ? offset ?",
            s,
            p * s);

    return new ListingLifecycleDivergencePage(
        rows.stream().map(ListingLifecycleService::mapDivergence).toList(), p, s, total);
  }

  // ==================================================================
  // 单条视图 / 时间线
  // ==================================================================

  /** 单个 listing 的生命周期视图。 */
  public ListingLifecycleDetail detail(String rawId) {
    UUID listingId = parseId(rawId);
    ListingLifecycleRow row = loadRow(listingId);
    if (row == null) {
      throw notFound();
    }
    return new ListingLifecycleDetail(
        row,
        timeline(listingId.toString(), null, 0, 50).items(),
        actions(listingId),
        allowedActions(row.status()),
        rule());
  }

  /** 单个 listing 的列表行（与列表同一个查询，保证两处口径一致）。 */
  private ListingLifecycleRow loadRow(UUID listingId) {
    Record r =
        sql.fetchOne(
            LIST_COLUMNS + LIST_FROM + " and l.id = ?",
            listingId);
    return r == null ? null : mapRow(r);
  }

  /**
   * 状态变更时间线（真分页 + 按来源筛）。
   *
   * <p>🔴 倒序（{@code occurred_at desc}）—— 最新���在最上面。
   *
   * <p>🔴 来源（MANUAL / PLATFORM）逐条落在 {@code source} 上，页面**必须**显式标注。
   */
  public ListingLifecycleEventPage timeline(
      String rawId, String source, int page, int size) {
    UUID listingId = parseId(rawId);
    int p = clampPage(page);
    int s = clampSize(size);

    String src = blankToNull(source);
    if (src != null && !ACTION_SOURCES.contains(src)) {
      throw unprocessable(
          "未知的事件来源：" + src + "。只有 " + String.join(" / ", ACTION_SOURCES));
    }

    String from =
        """
        from listing_status_event e
        join listing l on l.tenant_id=e.tenant_id and l.id=e.listing_id
        join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
        join platform p on p.id=s.platform_id
        where e.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and e.listing_id = ?
        """;
    List<Object> binds = new ArrayList<>();
    binds.add(listingId);
    if (src != null) {
      from += " and e.source = ?";
      binds.add(src);
    }

    long total =
        sql.fetchOne("select count(*)" + from, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<Record> rows =
        sql.fetch(
            """
            select e.id, e.listing_id, e.from_status, e.to_status, e.source, e.event_type,
                   e.platform_status, e.internal_status, e.conflict_detected, e.conflict_note,
                   e.resolution, e.rejection_reason, e.note, e.occurred_at, e.received_at,
                   l.external_item_id, l.title, s.code as shop_code, p.code as platform_code
            """
                + from
                + " order by e.occurred_at desc, e.id desc limit ? offset ?",
            pageBinds.toArray());

    return new ListingLifecycleEventPage(
        rows.stream().map(ListingLifecycleService::mapEvent).toList(), p, s, total);
  }

  /** 人工动作留痕（谁 / 为什么 / 是否回写平台）。 */
  public List<ListingLifecycleActionRow> actions(UUID listingId) {
    return sql.fetch(
            """
            select a.id, a.listing_id, a.action, a.from_status, a.to_status, a.source,
                   a.platform_event_type, a.platform_status, a.rejection_reason, a.reason,
                   a.writeback_requested, a.writeback_batch_id, a.writeback_item_id,
                   a.platform_event_id, a.actor_id, a.created_at,
                   b.batch_no as batch_no, i.operation_id as operation_id
            from listing_lifecycle_action a
            left join writeback_batch b on b.tenant_id=a.tenant_id and b.id=a.writeback_batch_id
            left join writeback_batch_item i on i.tenant_id=a.tenant_id and i.id=a.writeback_item_id
            where a.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and a.listing_id = ?
            order by a.created_at desc, a.id desc
            """,
            listingId)
        .stream()
        .map(ListingLifecycleService::mapAction)
        .toList();
  }

  // ==================================================================
  // 人工动作
  // ==================================================================

  /**
   * 🔴 人工动作：主动下架 / 重新上架 / 提交平台审核 / 标记被封禁。
   *
   * <p>流程（顺序有意义）：
   *
   * <ol>
   *   <li>定位商品（跨租户与不存在都表现为 404）。
   *   <li>状态机判定：非法 ⇒ <b>422 + 可读原因</b>，一个字节都不写。
   *   <li>若这次动作选择了回写平台 ⇒ <b>先</b>把平台任务准备好
   *       （目标登记 → 批次 → 平台队列任务 → 批次条目）。任一步不成 ⇒ 整次动作失败，
   *       **不会**出现「内部已下架但平台任务没生成」的半截状态。
   *   <li>{@code UPDATE listing.status} ⇒ 状态变更事件**由 DB 触发器落**。
   *   <li>落一条动作留痕（意图 + 回执）。
   * </ol>
   */
  public LifecycleActionReceipt act(
      String rawId, LifecycleActionRequest request, UUID actorId, UUID membershipId) {
    UUID tenant = currentTenant();
    UUID listingId = parseId(rawId);
    Record listing = loadListing(tenant, listingId);
    if (listing == null) {
      throw notFound();
    }

    String action = text(request == null ? null : request.action()).toUpperCase(Locale.ROOT);
    String current = listing.get("status", String.class);

    String rejection = actionRejection(action, current);
    if (rejection != null) {
      throw unprocessable(rejection);
    }
    String target = targetOf(action);

    String reason = note(request == null ? null : request.reason());
    // 🔴 只有「主动下架」提供回写选项：重新上架没有平台回写通道（writeback_batch.kind
    //    的值域里没有它，既有 CHECK 不能动），给一个必然 500 的选项比不给更糟。
    boolean writebackRequested =
        "DELIST".equals(action) && Boolean.TRUE.equals(request == null ? null : request.writebackToPlatform());

    UUID actionId = Ids.next();
    UUID batchId = null;
    UUID itemId = null;
    UUID operationId = null;
    String batchNo = "";

    if (writebackRequested) {
      WritebackCreated created =
          createDelistWriteback(
              tenant, listing, actionId, reason, membershipId);
      batchId = created.batchId();
      itemId = created.itemId();
      operationId = created.operationId();
      batchNo = created.batchNo();
    }

    // ---- 改状态：事件由触发器落（服务层不直接 INSERT listing_status_event）
    UUID eventId = applyStatus(tenant, listingId, current, target, manualContext(reason), null);

    sql.execute(
        """
        insert into listing_lifecycle_action
          (id,tenant_id,listing_id,action,from_status,to_status,source,reason,
           writeback_requested,writeback_batch_id,writeback_item_id,actor_id,created_at)
        values (?,?,?,?,?,?,'MANUAL',?,?,?,?,?,now())
        """,
        actionId,
        tenant,
        listingId,
        action,
        current,
        target,
        reason,
        writebackRequested,
        batchId,
        itemId,
        actorId);

    String message =
        "状态已由「"
            + current
            + "」改为「"
            + target
            + "」，状态变更事件已由数据库触发器记录"
            + (writebackRequested
                ? "；同时已生成平台操作任务（批次 " + batchNo + "，队列任务 " + operationId + "）"
                : "；**没有**回写平台，平台侧不知情");

    return new LifecycleActionReceipt(
        actionId,
        listingId,
        action,
        current,
        target,
        "MANUAL",
        writebackRequested,
        batchId,
        batchNo,
        operationId,
        eventId,
        message);
  }

  // ==================================================================
  // 平台状态被动同步
  // ==================================================================

  /**
   * 🔴 平台侧状态变更被动同步（单个商品）。
   *
   * <p>两种用法：
   *
   * <ul>
   *   <li>给 {@code platformEventId} ⇒ 用 4.3.2.2 已落库的那条平台事件作为事实来源。
   *   <li>不给 ⇒ 用 body 里的字段作为平台事实（平台适配器直接推过来）。
   * </ul>
   */
  public LifecycleActionReceipt applyPlatformStatus(
      String rawId, PlatformStatusRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    UUID listingId = parseId(rawId);
    Record listing = loadListing(tenant, listingId);
    if (listing == null) {
      throw notFound();
    }
    String current = listing.get("status", String.class);

    PlatformFact fact = resolvePlatformFact(tenant, request, listingId);
    String rejection = platformRejection(fact.eventType(), current);
    if (rejection != null) {
      throw unprocessable(rejection);
    }

    String target = platformTarget(fact.eventType());
    UUID eventId = null;
    if (target != null && !target.equals(current)) {
      eventId =
          applyStatus(
              tenant,
              listingId,
              current,
              target,
              platformContext(fact, note(request == null ? null : request.note()), actorId),
              fact.occurredAt() == null ? null : ts(fact.occurredAt()));
    }

    // 🔴 平台事实**总是**留痕：即使内部状态没有变化（限流 / 类目审核 / 价格异常），
    //    「平台说了什么」也是事实，不能因为内部状态没动就不记。
    UUID actionId = Ids.next();
    sql.execute(
        """
        insert into listing_lifecycle_action
          (id,tenant_id,listing_id,action,from_status,to_status,source,platform_event_type,
           platform_status,rejection_reason,reason,writeback_requested,platform_event_id,
           actor_id,created_at)
        values (?,?,?,'APPLY_PLATFORM_STATUS',?,?,'PLATFORM',?,?,?,?,false,?,?,now())
        """,
        actionId,
        tenant,
        listingId,
        current,
        target == null ? current : target,
        fact.eventType(),
        fact.platformStatus(),
        fact.rejectionReason(),
        note(request == null ? null : request.note()),
        fact.eventId() == null ? eventId : fact.eventId(),
        actorId);

    String message =
        target == null
            ? "平台事件「"
                + fact.eventType()
                + "」已记录；该事件**不改变**内部状态（商品仍在售，平台只是加了限制），内部状态仍是「"
                + current
                + "」"
            : target.equals(current)
                ? "内部状态已经是「" + current + "」，平台确认了同样的状态，没有变化"
                : "内部状态已由「"
                    + current
                    + "」跟随平台改为「"
                    + target
                    + "」，状态变更事件已由数据库触发器记录（来源：平台）";

    return new LifecycleActionReceipt(
        actionId,
        listingId,
        "APPLY_PLATFORM_STATUS",
        current,
        target == null ? current : target,
        "PLATFORM",
        false,
        null,
        "",
        null,
        eventId,
        message);
  }

  /**
   * 🔴 批量同步：把**还没有联动过**的平台事件应用到内部状态。
   *
   * <p>「联动过没有」由 {@code listing_lifecycle_action.platform_event_id} 判定
   * （同租户内唯一）⇒ 重复跑不会把同一条平台事件应用两遍。
   */
  public LifecycleSyncReceipt sync(UUID actorId) {
    UUID tenant = currentTenant();

    List<Record> pending =
        sql.fetch(
            """
            select e.id, e.listing_id, e.event_type, e.platform_status, e.rejection_reason,
                   e.occurred_at, e.ingest_channel
            from listing_status_event e
            where e.tenant_id = ?
              and e.source='PLATFORM'
              and not %s
              and not exists (select 1 from listing_lifecycle_action a
                              where a.tenant_id=e.tenant_id and a.platform_event_id=e.id)
            order by e.occurred_at asc, e.id asc
            limit 200
            """
                .formatted(INTERNAL_EVENT_PREDICATE),
            tenant);

    List<String> applied = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (Record event : pending) {
      UUID listingId = event.get("listing_id", UUID.class);
      String eventType = event.get("event_type", String.class);
      Record listing = loadListing(tenant, listingId);
      if (listing == null) {
        skipped.add("事件 " + event.get("id", UUID.class) + "：商品已不存在或不在数据范围内");
        continue;
      }
      String current = listing.get("status", String.class);
      String rejection = platformRejection(eventType, current);
      if (rejection != null) {
        skipped.add("事件 " + event.get("id", UUID.class) + "：" + rejection);
        continue;
      }
      String target = platformTarget(eventType);
      if (target == null) {
        skipped.add(
            "事件 "
                + event.get("id", UUID.class)
                + "：平台事件「"
                + eventType
                + "」不改变内部状态（商品仍在售，平台只是加了限制）");
        continue;
      }
      PlatformFact fact =
          new PlatformFact(
              event.get("id", UUID.class),
              eventType,
              event.get("platform_status", String.class),
              event.get("rejection_reason", String.class),
              event.get("occurred_at", OffsetDateTime.class),
              event.get("ingest_channel", String.class));
      UUID eventId =
          applyStatus(
              tenant,
              listingId,
              current,
              target,
              platformContext(fact, "平台状态回流联动", actorId),
              fact.occurredAt() == null ? null : ts(fact.occurredAt()));

      UUID actionId = Ids.next();
      sql.execute(
          """
          insert into listing_lifecycle_action
            (id,tenant_id,listing_id,action,from_status,to_status,source,platform_event_type,
             platform_status,rejection_reason,reason,writeback_requested,platform_event_id,
             actor_id,created_at)
          values (?,?,?,'APPLY_PLATFORM_STATUS',?,?,'PLATFORM',?,?,?,'平台状态回流联动',false,?,?,now())
          """,
          actionId,
          tenant,
          listingId,
          current,
          target,
          eventType,
          fact.platformStatus(),
          fact.rejectionReason(),
          event.get("id", UUID.class),
          actorId);
      applied.add(
          "商品 "
              + listing.get("external_item_id", String.class)
              + "：内部状态「"
              + current
              + "」→「"
              + target
              + "」（平台事件 "
              + event.get("id", UUID.class)
              + "，状态变更事件 "
              + eventId
              + "）");
    }

    return new LifecycleSyncReceipt(
        applied.size(), skipped.size(), applied, skipped,
        "本次联动 " + applied.size() + " 条，未联动 " + skipped.size() + " 条");
  }

  // ==================================================================
  // 内部实现
  // ==================================================================

  /** 平台事实（内部结构）。 */
  private record PlatformFact(
      UUID eventId,
      String eventType,
      String platformStatus,
      String rejectionReason,
      OffsetDateTime occurredAt,
      String ingestChannel) {}

  /** 一次平台回写创建的产物。 */
  private record WritebackCreated(UUID batchId, String batchNo, UUID itemId, UUID operationId) {}

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  private Record loadListing(UUID tenant, UUID listingId) {
    // 🔴 不 join shop：listing 自己的 RLS data_scope 策略已经按店铺的 owner / department
    //    收敛过可见性，再 join 一次只会让「为什么这条看不见」多一个说不清的原因。
    return sql.fetchOne(
        """
        select l.id, l.shop_id, l.status, l.external_item_id, l.title, l.marketplace_id
        from listing l
        where l.tenant_id = ? and l.id = ?
        """,
        tenant,
        listingId);
  }

  /**
   * 🔴 改状态并取回落库的事件 id。
   *
   * <p>状态一变，V171 的触发器 {@code record_listing_status_change()} 就地落一条事件。
   * 本方法**只**做两件事：设置 {@code app.lifecycle_context}（告诉触发器来源）、
   * {@code UPDATE listing.status}。事件 id 是事后按「同一事务 + 同一对状态 + 同一时刻」精确取回的。
   *
   * @param expectedOccurredAt 事件应有的发生时刻；null = 用 {@code now()}（人工路径的默认）
   */
  private UUID applyStatus(
      UUID tenant,
      UUID listingId,
      String from,
      String to,
      Map<String, Object> context,
      String expectedOccurredAt) {
    // GUC 是**事务局部**的（第三个参数 true）：不会漏到下一个请求的连接上。
    sql.execute("select set_config('app.lifecycle_context',?,true)", writeJson(context));
    int updated =
        sql.execute(
            "update listing set status=?, updated_at=now(), version=version+1"
                + " where tenant_id=? and id=?",
            to,
            tenant,
            listingId);
    if (updated == 0) {
      throw notFound();
    }
    // 触发器用 ctx 里的 occurredAt（缺省 now() = 事务开始时刻）写 occurred_at
    // ⇒ 同一事务内按「同一对状态 + 同一时刻」可精确取回那一条。
    String source = String.valueOf(context.getOrDefault("source", "MANUAL"));
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(listingId);
    binds.add(from);
    binds.add(to);
    binds.add(source);
    String occurredCond = "occurred_at = now()";
    if (expectedOccurredAt != null) {
      occurredCond = "occurred_at = ?::timestamptz";
      binds.add(expectedOccurredAt);
    }
    Record event =
        sql.fetchOne(
            """
            select id from listing_status_event
            where tenant_id=? and listing_id=? and from_status=? and to_status=?
              and source=? and %s
            order by id desc limit 1
            """
                .formatted(occurredCond),
            binds.toArray());
    return event == null ? null : event.get("id", UUID.class);
  }

  /** 人工动作的上下文（source=MANUAL，其余取默认值 —— 与 V171 触发器的默认逐字段一致）。 */
  private static Map<String, Object> manualContext(String reason) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("source", "MANUAL");
    ctx.put("note", reason);
    return ctx;
  }

  /** 平台联动的上下文（source=PLATFORM，必须带平台事件类型与平台权威状态原文）。 */
  private static Map<String, Object> platformContext(PlatformFact fact, String reason, UUID actorId) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("source", "PLATFORM");
    ctx.put("eventType", fact.eventType());
    ctx.put("platformStatus", fact.platformStatus());
    ctx.put("note", reason);
    ctx.put("ingestChannel", fact.ingestChannel() == null || fact.ingestChannel().isBlank()
        ? "API" : fact.ingestChannel());
    if (fact.occurredAt() != null) {
      ctx.put("occurredAt", fact.occurredAt().toString());
    }
    if (actorId != null) {
      ctx.put("ingestedBy", actorId.toString());
    }
    return ctx;
  }

  /**
   * 解析平台事实：给了 {@code platformEventId} 就以库里那条为准，否则用 body 里的字段。
   */
  private PlatformFact resolvePlatformFact(UUID tenant, PlatformStatusRequest request, UUID listingId) {
    UUID eventId = request == null ? null : request.platformEventId();
    if (eventId != null) {
      Record event =
          sql.fetchOne(
              """
              select id, listing_id, event_type, platform_status, rejection_reason,
                     occurred_at, ingest_channel, source
              from listing_status_event
              where tenant_id = ? and id = ?
              """,
              tenant,
              eventId);
      if (event == null) {
        throw notFound();
      }
      if (!"PLATFORM".equals(event.get("source", String.class))) {
        throw unprocessable(
            "事件 " + eventId + " 不是平台来源（source=MANUAL）—— "
                + "内部人工改状态不是平台状态，不能拿它做平台联动");
      }
      if (!listingId.equals(event.get("listing_id", UUID.class))) {
        throw unprocessable(
            "事件 " + eventId + " 属于另一个商品，不能应用到本商品上");
      }
      return new PlatformFact(
          eventId,
          event.get("event_type", String.class),
          event.get("platform_status", String.class),
          event.get("rejection_reason", String.class),
          event.get("occurred_at", OffsetDateTime.class),
          event.get("ingest_channel", String.class));
    }

    String eventType = text(request == null ? null : request.eventType()).toUpperCase(Locale.ROOT);
    if (!PLATFORM_EVENT_TYPES.contains(eventType)) {
      throw unprocessable(
          "未知的平台事件类型「"
              + eventType
              + "」。平台状态只认这七种："
              + String.join(" / ", PLATFORM_EVENT_TYPES)
              + "（拼错一个词就是平台侧的一个新状态，猜一个最像的去执行比报错危险得多）");
    }
    String platformStatus = text(request == null ? null : request.platformStatus());
    if (platformStatus.isEmpty()) {
      throw unprocessable("必须给出平台的权威状态原文（platformStatus）—— 平台状态的价值全在它");
    }
    String rejectionReason = text(request == null ? null : request.rejectionReason());
    if ("REJECTED".equals(eventType) && rejectionReason.isEmpty()) {
      throw unprocessable("驳回事件必须带驳回原因 —— 「按驳回原因批量修正重发」全靠它");
    }
    String channel = text(request == null ? null : request.ingestChannel()).toUpperCase(Locale.ROOT);
    if (channel.isEmpty()) {
      channel = "API";
    }
    if (!INGEST_CHANNELS.contains(channel)) {
      throw unprocessable("未知的事件通道「" + channel + "」。只认这三条：" + String.join(" / ", INGEST_CHANNELS));
    }
    OffsetDateTime occurredAt = request == null ? null : request.occurredAt();
    return new PlatformFact(
        null, eventType, platformStatus, rejectionReason, occurredAt, channel);
  }

  /**
   * 🔴 主动下架回写平台：复用 02 回写队列的既有表。
   *
   * <p>四步，缺一不可：
   *
   * <ol>
   *   <li>{@code writeback_target}：登记（或复用）这个 listing 作为可回写目标。
   *       <b>已封禁的目标不放行</b> —— 它被封是有原因的，批量回写时也会被逐条拒。
   *   <li>{@code platform_operation}：入队一个平台任务（operation='PUBLISH'，
   *       payload 带 delist=true —— V169 第四节对 DELIST 的映射）。
   *   <li>{@code writeback_batch}：kind='DELIST' 的批次头（**不改** kind 的 CHECK）。
   *   <li>{@code writeback_batch_item}：批次里的一条（QUEUED + operation_id）。
   * </ol>
   */
  private WritebackCreated createDelistWriteback(
      UUID tenant, Record listing, UUID actionId, String reason, UUID membershipId) {
    UUID shopId = listing.get("shop_id", UUID.class);
    String externalRef = listing.get("external_item_id", String.class);
    String listingId = listing.get("id", UUID.class).toString();

    Record target =
        sql.fetchOne(
            """
            insert into writeback_target
              (id,tenant_id,shop_id,target_type,external_ref,listing_ref,sku_code,status,source_ref)
            values (?,?,?,'LISTING',?,?,'','ACTIVE','listing-lifecycle')
            on conflict (tenant_id,shop_id,target_type,external_ref) do update
              set listing_ref=excluded.listing_ref,
                  version=writeback_target.version+1, updated_at=now()
            returning id, status, blocked_reason
            """,
            Ids.next(),
            tenant,
            shopId,
            externalRef,
            listingId);
    if (target == null) {
      throw unprocessable("可回写目标登记失败");
    }
    if ("BLOCKED".equals(target.get("status", String.class))) {
      throw unprocessable(
          "该商品的可回写目标已被封禁（"
              + target.get("blocked_reason", String.class)
              + "），不能发起平台回写。请先解除封禁，或只改内部状态（不勾选「回写平台」）。");
    }
    UUID targetId = target.get("id", UUID.class);

    // 幂等键由服务端从「动作 id」派生：同一个动作重试不会产生第二个平台任务。
    String idempotencyKey = "lifecycle-delist-" + actionId;
    UUID operationId = Ids.next();
    sql.execute(
        """
        insert into platform_operation
          (id,tenant_id,shop_id,marketplace_id,operation,idempotency_key,payload)
        values (?,?,?,?,'PUBLISH',?,cast(? as jsonb))
        """,
        operationId,
        tenant,
        shopId,
        listing.get("marketplace_id", UUID.class),
        idempotencyKey,
        writeJson(
            Map.of(
                "batchKind", "DELIST",
                "targetType", "LISTING",
                "externalRef", externalRef,
                "delist", true,
                "active", false,
                "listingId", listingId)));

    UUID batchId = Ids.next();
    String batchNo = nextBatchNo();
    sql.execute(
        """
        insert into writeback_batch
          (id,tenant_id,batch_no,kind,shop_id,filter_snapshot,param_snapshot,idempotency_key,
           selected_count,rejected_count,created_by_membership_id,reason,created_at)
        values (?,?,?,'DELIST',?,cast(? as jsonb),cast(? as jsonb),?,1,0,?,?,now())
        """,
        batchId,
        tenant,
        batchNo,
        shopId,
        writeJson(Map.of("listingIds", List.of(listingId), "lifecycleActionId", actionId.toString())),
        writeJson(Map.of("delist", true, "active", false)),
        idempotencyKey,
        membershipId,
        reason);

    UUID itemId = Ids.next();
    sql.execute(
        """
        insert into writeback_batch_item
          (id,tenant_id,batch_id,position,target_id,external_ref,shop_id,target_type,
           item_result,operation_id,reason,created_at)
        values (?,?,?,1,?,?,?,'LISTING','QUEUED',?,'',now())
        """,
        itemId,
        tenant,
        batchId,
        targetId,
        externalRef,
        shopId,
        operationId);

    return new WritebackCreated(batchId, batchNo, itemId, operationId);
  }

  private String nextBatchNo() {
    long next = sql.fetchOne("select nextval('writeback_batch_no_seq')").get(0, Long.class);
    return "WB-"
        + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
        + "-"
        + String.format("%06d", next);
  }

  // ==================================================================
  // 映射
  // ==================================================================

  private static ListingLifecycleRow mapRow(Record r) {
    String kind = nullToEmpty(r.get("divergence_kind", String.class));
    return new ListingLifecycleRow(
        r.get("id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("status", String.class),
        nullToEmpty(r.get("last_source", String.class)),
        r.get("last_at", OffsetDateTime.class),
        nullToEmpty(r.get("last_from_status", String.class)),
        nullToEmpty(r.get("last_to_status", String.class)),
        nullToEmpty(r.get("plat_status", String.class)),
        nullToEmpty(r.get("plat_event_type", String.class)),
        r.get("plat_at", OffsetDateTime.class),
        !kind.isEmpty(),
        kind,
        kind.isEmpty() ? "" : divergenceNote(kind, r.get("status", String.class),
            nullToEmpty(r.get("plat_event_type", String.class)),
            nullToEmpty(r.get("plat_status", String.class))),
        // 🔴 合法动作由服务端算：页面据此渲染按钮，**不自己猜**该允许什么。
        //    非法动作即使被构造出来，服务端也会显式拒绝并给出可读理由。
        allowedActions(r.get("status", String.class)),
        count(r, "event_count"),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static ListingLifecycleDivergenceRow mapDivergence(Record r) {
    String kind = nullToEmpty(r.get("divergence_kind", String.class));
    return new ListingLifecycleDivergenceRow(
        r.get("id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("status", String.class),
        nullToEmpty(r.get("plat_status", String.class)),
        nullToEmpty(r.get("plat_event_type", String.class)),
        kind,
        divergenceNote(
            kind,
            r.get("status", String.class),
            nullToEmpty(r.get("plat_event_type", String.class)),
            nullToEmpty(r.get("plat_status", String.class))),
        severityOf(kind),
        r.get("last_at", OffsetDateTime.class),
        r.get("plat_at", OffsetDateTime.class));
  }

  private static int count(Record r, String column) {
    Number value = r.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static ListingLifecycleEventRow mapEvent(Record r) {
    return new ListingLifecycleEventRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        nullToEmpty(r.get("from_status", String.class)),
        nullToEmpty(r.get("to_status", String.class)),
        nullToEmpty(r.get("source", String.class)),
        nullToEmpty(r.get("event_type", String.class)),
        nullToEmpty(r.get("platform_status", String.class)),
        nullToEmpty(r.get("internal_status", String.class)),
        Boolean.TRUE.equals(r.get("conflict_detected", Boolean.class)),
        nullToEmpty(r.get("conflict_note", String.class)),
        nullToEmpty(r.get("resolution", String.class)),
        nullToEmpty(r.get("rejection_reason", String.class)),
        nullToEmpty(r.get("note", String.class)),
        isInternalStateChange(
            nullToEmpty(r.get("from_status", String.class)),
            nullToEmpty(r.get("to_status", String.class))),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("received_at", OffsetDateTime.class));
  }

  /**
   * 🔴 这条事件是「内部状态变更」还是「平台事件原文」。
   *
   * <p>判据：起始与目标状态**都**落在七态里 ⇒ 内部状态变更；否则 ⇒ 平台事件原文
   * （4.3.2.2 把 to_status 写成平台自己的 token，如 ACTIVE / INACTIVE / SUPPRESSED）。
   *
   * <p>🔴 判定放在**服务端**：前端不自己判断「这个 token 算不算七态」——
   * 两处各写一份必然随版本漂移，而这一列直接决定时间线上那条事件被标成
   * 「我们改了状态」还是「平台这么说」。
   */
  static boolean isInternalStateChange(String fromStatus, String toStatus) {
    return LIFECYCLE_STATUSES.contains(fromStatus) && LIFECYCLE_STATUSES.contains(toStatus);
  }

  private static ListingLifecycleActionRow mapAction(Record r) {
    return new ListingLifecycleActionRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("action", String.class)),
        nullToEmpty(r.get("from_status", String.class)),
        nullToEmpty(r.get("to_status", String.class)),
        nullToEmpty(r.get("source", String.class)),
        nullToEmpty(r.get("platform_event_type", String.class)),
        nullToEmpty(r.get("platform_status", String.class)),
        nullToEmpty(r.get("rejection_reason", String.class)),
        nullToEmpty(r.get("reason", String.class)),
        Boolean.TRUE.equals(r.get("writeback_requested", Boolean.class)),
        r.get("writeback_batch_id", UUID.class),
        nullToEmpty(r.get("batch_no", String.class)),
        r.get("writeback_item_id", UUID.class),
        r.get("operation_id", UUID.class),
        r.get("platform_event_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  // ==================================================================
  // 差异说明
  // ==================================================================

  /**
   * 差异说明（逐字写出两边状态）。
   *
   * <p>🔴 说明是**散文**，判定（kind）才是规则 —— 规则在 SQL 侧（{@link #DIVERGENCE_SQL}），
   * 这样差异区才能真分页；这里只把已判定的结论组织成人话。
   */
  static String divergenceNote(
      String kind, String internalStatus, String platformEventType, String platformStatus) {
    String internal = nullToEmpty(internalStatus);
    String platform = platformStatus == null || platformStatus.isEmpty()
        ? nullToEmpty(platformEventType)
        : platformStatus;
    return switch (kind) {
      case "PLATFORM_DELISTED_INTERNAL_ON_SALE" ->
          "内部状态「"
              + internal
              + "」，平台状态「"
              + platform
              + "」：平台把商品下架了而内部不知情 —— 这是最常见的漏单原因。";
      case "INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE" ->
          "内部状态「" + internal + "」，平台状态「" + platform + "」：内部说商品不在售，平台还在卖。";
      case "INTERNAL_DELISTED_NOT_WRITEBACK" ->
          "内部状态「"
              + internal
              + "」，那次主动下架**明确没有回写平台** —— 平台侧不知情，平台可能还在卖。";
      case "INTERNAL_DELISTED_NO_WRITEBACK_RECORD" ->
          "内部状态「"
              + internal
              + "」，但找不到下架动作留痕（多半走的是 4.1.1.1 的批量下架，那条路径不回写平台）"
              + "—— 平台侧可能还在卖。";
      default -> "";
    };
  }

  /** 差异的严重程度。 */
  static String severityOf(String kind) {
    return switch (kind) {
      case "PLATFORM_DELISTED_INTERNAL_ON_SALE" -> "HIGH";
      case "INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE",
              "INTERNAL_DELISTED_NOT_WRITEBACK",
              "INTERNAL_DELISTED_NO_WRITEBACK_RECORD" -> "MEDIUM";
      default -> "";
    };
  }

  /**
   * 🔴 Java 侧的等价差异判定 —— 与 {@link #DIVERGENCE_SQL} **同一套口径**。
   *
   * <p>可售性口径直接**调用 4.3.2.2 的 {@code internalSellability} / {@code platformSellability}**
   * （同包可见）—— 不在这里复刻一份，免得两处口径随版本漂移。
   *
   * <p>集成测试拿它与 SQL 侧的结果逐行比对：两边不跑偏才算过。
   */
  static String divergenceOf(String internalStatus, String platformEventType, Boolean writebackRequested) {
    String internal = ListingStatusSyncService.internalSellability(internalStatus);
    String platform =
        platformEventType == null || platformEventType.isEmpty()
            ? "PENDING"
            : ListingStatusSyncService.platformSellability(platformEventType);
    if (!platformEventType.isEmpty()) {
      if ("SELLABLE".equals(internal) && "NOT_SELLABLE".equals(platform)) {
        return "PLATFORM_DELISTED_INTERNAL_ON_SALE";
      }
      if ("NOT_SELLABLE".equals(internal) && "SELLABLE".equals(platform)) {
        return "INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE";
      }
    }
    if ("DELISTED_BY_US".equals(internalStatus)) {
      if (writebackRequested == null) {
        return "INTERNAL_DELISTED_NO_WRITEBACK_RECORD";
      }
      if (!writebackRequested) {
        return "INTERNAL_DELISTED_NOT_WRITEBACK";
      }
    }
    return "";
  }
}
