package com.starcon.channel;

import static com.starcon.channel.SyncHealthModels.*;
import static com.starcon.channel.SyncHealthRules.*;

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
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.5.1.1 同步与 API 健康 —— channel 侧<b>只读</b>投影层。
 *
 * <p>蓝图：「一屏回答"现在能不能正常同步"：各平台连接状态、最近同步时间、失败计数、
 * 限流命中、队列积压，按店铺/连接展示红黄绿。输出：连接健康卡片 + 需处理项跳转
 * （重授权、看失败队列、查积压）。」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>只读</b>：本类**没有任何写操作**。卷 01 的边界写死「工作台只读 + 跳转」，
 *       所以「重试同步 / 重新授权 / 改状态」一律表现为**跳转到归属系统**。</li>
 *   <li><b>不新建事实表</b>：一切数字都从 channel 自己的事实表现场聚合
 *       （{@code channel_connection} / {@code shop} / {@code sync_cursor} / {@code sync_run} /
 *       {@code sync_failure} / {@code outbox_event} + 三张任务队列）。</li>
 *   <li><b>「读不到」≠「没有」</b>：① 上游读不到 ⇒ 整个端点 503，**不**折成空集合；
 *       ② 当前角色的<b>数据范围</b>看不到同步游标 / 失败队列时，那一维度标
 *       {@code SYNC_DATA_NOT_VISIBLE}，判不了就写判不了，**不**写成「没有失败」。</li>
 *   <li><b>两个「最近同步时刻」分开</b>：连接级声明值（{@code channel_connection.last_sync_at}）
 *       与运行级完成时刻（{@code sync_run.finished_at}）不是一个东西，卡片两个字段都报，
 *       并用 {@code lastSyncAtSource} 说清当前用的是哪一个。</li>
 *   <li><b>积压必须说得清统计了哪几张表</b>：每张表一行，含「算作积压」的判定条件，
 *       不允许出现一个来源不明的总数。</li>
 * </ol>
 */
@Service
public class SyncHealthService {

  /** 权限码：复用 2.4 同步运行已有的 {@code channel.sync.read}，不新增权限码。 */
  static final String READ = "channel.sync.read";

  /** 每页行数上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 失败计数的口径说明（能力声明与页面共用同一句话）。 */
  private static final String FAILURE_WINDOW_SEMANTICS =
      "🔴 失败计数**同时报两个口径**：①「近 "
          + FAILURE_WINDOW_HOURS
          + " 小时」= sync_failure.occurred_at 落在最近 "
          + FAILURE_WINDOW_HOURS
          + " 小时窗口内；②「累计」= 该租户 sync_failure 表里的全部行。"
          + "两个口径**不合并成一个数** —— 合并之后再也分不清「刚刚爆发」与「历史积压」。";

  /** 「最近同步时间」的口径说明。 */
  private static final String LAST_SYNC_SEMANTICS =
      "🔴「最近同步时间」有**两个来源**，本模块两个都报、并标明当前用的是哪一个："
          + "① **连接级** = channel_connection.last_sync_at（这条连接自己声明的最后同步时刻）；"
          + "② **运行级** = 该连接下 sync_run.finished_at 的最大值（最近一次**跑完**的运行）。"
          + "🔴 本仓当前**没有任何代码写 channel_connection.last_sync_at**，它恒为 null —— "
          + "所以生效值实际来自运行级。卡片上的 lastSyncAtSource 字段就说清了当前取的是哪一个，"
          + "不会让人把「这一列没人写」误读成「这条连接从没同步过」。";

  /** 「读不到」的口径说明。 */
  private static final String READ_FAILURE_SEMANTICS =
      "🔴「读不到」与「没有」是**两件事**，本模块绝不把前者折成后者："
          + "① 上游读不到 ⇒ 整个端点返回 503，**不**回空集合冒充「一切正常」；"
          + "② 当前角色的数据范围（SELF / DEPARTMENT）看不到同步游标与失败队列时，"
          + "卡片上标 SYNC_DATA_NOT_VISIBLE（判不了），**不**写成「没有失败」；"
          + "③ 某个平台压根没有连接 ⇒ 落在 platformsWithoutConnection 里，"
          + "那是「这个平台还没接」，**不是**「连接健康」。";

  /** 积压的口径说明。 */
  private static final String BACKLOG_NOTE =
      "🔴 积压**逐张表**统计，每一行都带「算作积压」的判定条件（与后端 SQL 一字不差）。"
          + "⚠️ 这四张表**性质不同**（事件发件箱 / 内容回写 / 平台状态动作 / 批量发布），"
          + "合计只作一个粗看，处置要**按行分别去** —— "
          + "「事件压住」和「发布任务排着」是两件事，不是一个数字。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 02 渠道与店铺（channel_connection / shop / platform）+ "
          + "02.4 同步运行（sync_cursor / sync_run / sync_failure）+ "
          + "事件发件箱（outbox_event）+ 04 刊登侧的三张任务队列。全部是**本服务自己库里的表**，"
          + "本模块**不跨服务读别人的表**。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 2.2.1.2 授权向导（重授权）、2.4.1.1 同步任务列表（看运行）、"
          + "2.4.2.1 失败队列（看失败）。🔴 2.4.2.1 失败队列在本仓**没有独立页面**"
          + "（台账路由为 —），它的内容在「渠道与店铺 → 同步运行」Tab 的失败原因分布里，"
          + "所以待处理项指向那里并写明这件事。"
          + "本模块**不提供**「重试同步 / 重新授权」这类写端点 —— 那是归属系统的动作。";

  private final DSLContext sql;

  public SyncHealthService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
  }

  /**
   * 🔴 <b>上游读不到 ⇒ 503</b>，而不是空集合。
   *
   * <p>把 DB 异常折成「没有连接 / 没有失败」是最危险的一种撒谎：页面会显示一片绿，
   * 而真相是「服务挂了，什么都没查成」。
   */
  private static ResponseStatusException unreadable(Throwable cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "同步健康数据读不到：上游事实表暂时无法访问，请稍后重试（本页不显示任何结论，"
            + "以免把「读不到」误读成「一切正常」）",
        cause);
  }

  /** 🔴 同源 DB 时钟：判定时刻 / 数据截至时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 🔴 <b>同步维度是否可见</b>。
   *
   * <p>{@code sync_cursor} / {@code sync_failure} 上挂着 RESTRICTIVE 的
   * {@code cursor_scope} / {@code failure_scope} 策略，数据范围是 SELF / DEPARTMENT 时
   * 这两张表**一行都看不到**。那时候「失败计数为 0」是**假象**（不是「没有失败」，
   * 是「没权限看到失败」），所以这里显式问一次数据库。
   */
  private boolean syncScopeVisible() {
    Object raw = sql.fetchValue("select channel_scope(channel_operation(), null, null)");
    return raw instanceof Boolean value ? value : true;
  }

  private static String placeholders(int count) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < count; i++) {
      builder.append(builder.length() == 0 ? "?" : ",?");
    }
    return builder.toString();
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：红黄绿规则表、阈值、计数口径、积压统计了哪几张表、未接通的下游。
   *
   * <p>🔴 本模块**没有**未交付的下游事实来源（上游全在 channel 同库），
   * 但有两个**本模块未接入**的兄弟模块，照旧逐条列出来（available=false +
   * ownerModule + 影响说明）—— 标的是「本模块没有接入」，不是「那个模块没交付」。
   */
  public SyncHealthCapabilities capabilities() {
    List<SyncHealthCapabilityGap> gaps =
        List.of(
            new SyncHealthCapabilityGap(
                "1.5.1.2",
                "数据一致性体检",
                false,
                "1.5.1.2",
                "数据一致性体检**未交付**（库存准确率 / 对账差异率 / 账实核对）。"
                    + "本模块**不做**这些判定，也不返回任何一致性数字 —— "
                    + "跨域一致性要等 07 库存、12 财务各自的事实齐了才能算。"),
            new SyncHealthCapabilityGap(
                "2.4.2.1",
                "失败队列独立页面",
                false,
                "2.4.2.1",
                "失败队列**已交付**（GET /channel/sync-failures + /sync-failures/summary）"
                    + "⇒ 但**本仓没有它的独立前端页面**（台账路由为 —）。"
                    + "它的内容在「渠道与店铺 → 同步运行」Tab 的失败原因分布里，"
                    + "所以本模块的待处理项指向那里并写明这件事，"
                    + "**不**指向一个不存在的路由。"),
            new SyncHealthCapabilityGap(
                "1.5.2.1",
                "服务与任务状态",
                false,
                "1.5.2.1",
                "服务与任务状态**未交付**（后台任务 / 计划任务是否停滞）。"
                    + "本模块只答「**同步**能不能正常跑」，不答「服务本身健不健康」。"));
    return new SyncHealthCapabilities(
        ruleTable(),
        thresholds(),
        SEVERITY_LEVELS,
        gaps,
        backlogSources(),
        BACKLOG_NOTE,
        FAILURE_WINDOW_SEMANTICS,
        LAST_SYNC_SEMANTICS,
        READ_FAILURE_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE,
        dbNow());
  }

  // ==================================================================
  // 连接健康卡片
  // ==================================================================

  /**
   * 连接健康卡片列表（真分页 + 真筛选）。
   *
   * @param platformCode 平台码筛选（空 = 全部）
   * @param status 连接状态筛选（空 = 全部）
   * @param keyword 关键字（连接显示名 / 平台账号 / 平台名）
   * @param page 页号（从 0 开始）
   * @param size 每页行数
   */
  public SyncHealthConnectionPage connections(
      String platformCode, String status, String keyword, int page, int size) {
    try {
      int safePage = Math.max(0, page);
      int safeSize = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
      Filters filters = new Filters(text(platformCode), text(status), text(keyword));
      int total = countConnections(filters);
      List<SyncHealthConnectionCard> cards =
          loadCards(filters, safePage, safeSize);
      return new SyncHealthConnectionPage(cards, total, safePage, safeSize);
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  /** 单条连接的健康卡片。跨租户与不存在返回逐字相同的 404。 */
  public SyncHealthConnectionCard connection(String rawId) {
    UUID id = parseUuid(rawId);
    if (id == null) {
      throw notFound();
    }
    try {
      Filters filters = new Filters("", "", "");
      List<SyncHealthConnectionCard> cards = loadCards(filters, 0, MAX_PAGE_SIZE);
      return cards.stream()
          .filter(card -> card.connectionId().equals(id.toString()))
          .findFirst()
          .orElseThrow(SyncHealthService::notFound);
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  /** 筛选条件。 */
  private record Filters(String platformCode, String status, String keyword) {
    /** 追加 WHERE 片段与参数。 */
    void append(StringBuilder statement, List<Object> params) {
      if (!platformCode.isEmpty()) {
        statement.append(" and p.code = ?");
        params.add(platformCode.toUpperCase());
      }
      if (!status.isEmpty()) {
        statement.append(" and c.status = ?");
        params.add(status.toUpperCase());
      }
      if (!keyword.isEmpty()) {
        statement.append(" and (c.display_name ilike ? or c.authorized_account ilike ? or p.name ilike ?)");
        String like = "%" + keyword + "%";
        params.add(like);
        params.add(like);
        params.add(like);
      }
    }
  }

  private int countConnections(Filters filters) {
    StringBuilder statement =
        new StringBuilder(
            "select count(*) from channel_connection c join platform p on p.id = c.platform_id"
                + " where c.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(currentTenant());
    filters.append(statement, params);
    Object raw = sql.fetchValue(statement.toString(), params.toArray());
    return raw instanceof Number number ? number.intValue() : 0;
  }

  /** 🔴 同源 DB 时钟之外，租户上下文只来自已认证成员关系（AccessService 绑定）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 装载一页卡片（含它依赖的全部事实聚合）。 */
  private List<SyncHealthConnectionCard> loadCards(Filters filters, int page, int size)
      {
    StringBuilder statement =
        new StringBuilder(
            "select c.id, p.code platform_code, p.name platform_name, c.authorized_account,"
                + " c.display_name, c.status, c.sync_paused, c.last_sync_at"
                + " from channel_connection c join platform p on p.id = c.platform_id"
                + " where c.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(currentTenant());
    filters.append(statement, params);
    statement.append(" order by p.position, c.created_at, c.id limit ? offset ?");
    params.add(size);
    params.add(page * size);

    List<Record> rows = sql.fetch(statement.toString(), params.toArray());
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>();
    for (Record row : rows) {
      ids.add(row.get("id", UUID.class));
    }

    Map<UUID, List<SyncHealthShopRef>> shops = loadShops(ids);
    Map<UUID, RunSnapshot> runs = loadLastRuns(ids);
    Map<UUID, Map<String, Long>> failuresWindow = loadFailureCounts(ids, true);
    Map<UUID, Map<String, Long>> failuresTotal = loadFailureCounts(ids, false);
    Map<UUID, Long> openFailures = loadOpenFailureCounts(ids);
    boolean syncVisible = syncScopeVisible();
    OffsetDateTime now = dbNow();

    List<SyncHealthConnectionCard> cards = new ArrayList<>();
    for (Record row : rows) {
      UUID id = row.get("id", UUID.class);
      OffsetDateTime declared = row.get("last_sync_at", OffsetDateTime.class);
      RunSnapshot run = runs.get(id);
      OffsetDateTime runFinished = run == null ? null : run.finishedAt();
      Object[] effective = effectiveLastSyncAt(declared, runFinished);
      OffsetDateTime effectiveAt = (OffsetDateTime) effective[0];
      String source = (String) effective[1];

      Map<String, Long> window = failuresWindow.getOrDefault(id, Map.of());
      Map<String, Long> total = failuresTotal.getOrDefault(id, Map.of());

      SyncHealthRules.SyncHealthFacts facts =
          new SyncHealthRules.SyncHealthFacts(
              row.get("status", String.class),
              Boolean.TRUE.equals(row.get("sync_paused", Boolean.class)),
              declared,
              effectiveAt,
              now,
              run == null ? null : run.status(),
              window,
              total);
      SyncHealthRules.SyncHealthEvaluation evaluation = evaluate(facts, syncVisible);

      List<SyncHealthReason> reasons = new ArrayList<>(evaluation.reasons());
      if (!syncVisible) {
        reasons.add(notVisibleReason());
      }
      String severity = severityOf(reasons, syncVisible);

      long windowTotal = sum(window);
      long totalTotal = sum(total);
      long rateWindow = countOf(window, FAILURE_RATE_LIMITED);
      long rateTotal = countOf(total, FAILURE_RATE_LIMITED);

      cards.add(
          new SyncHealthConnectionCard(
              id.toString(),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              row.get("authorized_account", String.class),
              row.get("display_name", String.class),
              row.get("status", String.class),
              Boolean.TRUE.equals(row.get("sync_paused", Boolean.class)),
              severity,
              List.copyOf(reasons),
              shops.getOrDefault(id, List.of()),
              shops.getOrDefault(id, List.of()).size(),
              declared,
              source,
              effectiveAt,
              effectiveAt == null ? null : staleHours(facts, effectiveAt),
              run == null ? null : run.status(),
              run == null ? null : run.triggerSource(),
              run == null ? null : run.startedAt(),
              run == null ? null : run.finishedAt(),
              run == null ? null : run.durationMs(),
              windowTotal,
              totalTotal,
              rateWindow,
              rateTotal,
              openFailures.getOrDefault(id, 0L),
              failureBreakdown(window, total)));
    }
    return List.copyOf(cards);
  }

  /** 🔴 同步维度不可见时的依据（判不了 ≠ 没有）。 */
  private static SyncHealthReason notVisibleReason() {
    return new SyncHealthReason(
        "SYNC_DATA_NOT_VISIBLE",
        SEVERITY_YELLOW,
        "同步运行数据读不到",
        "当前角色的数据范围（SELF / DEPARTMENT）看不到同步游标与失败队列 —— "
            + "这一维度**判不了**，不代表没有失败、也不代表同步正常。");
  }

  /** 综合严重度：任一红 ⇒ 红；否则有黄或「判不了」⇒ 黄；否则绿。 */
  private static String severityOf(List<SyncHealthReason> reasons, boolean syncVisible) {
    boolean red = reasons.stream().anyMatch(r -> SEVERITY_RED.equals(r.severity()));
    if (red) {
      return SEVERITY_RED;
    }
    boolean yellow = reasons.stream().anyMatch(r -> SEVERITY_YELLOW.equals(r.severity()));
    if (yellow || !syncVisible) {
      return SEVERITY_YELLOW;
    }
    return SEVERITY_GREEN;
  }

  /** 一次运行的关键字段。 */
  private record RunSnapshot(
      String status,
      String triggerSource,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      Integer durationMs) {}

  private Map<UUID, List<SyncHealthShopRef>> loadShops(List<UUID> connectionIds) {
    Map<UUID, List<SyncHealthShopRef>> result = new LinkedHashMap<>();
    if (connectionIds.isEmpty()) {
      return result;
    }
    List<Object> params = new ArrayList<>();
    params.add(currentTenant());
    params.addAll(connectionIds);
    String statement =
        "select s.connection_id, s.id, s.code, s.name from shop s"
            + " where s.tenant_id = ? and s.connection_id in ("
            + placeholders(connectionIds.size())
            + ") and s.status <> 'ARCHIVED' order by s.code";
    for (Record row : sql.fetch(statement, params.toArray())) {
      UUID connectionId = row.get("connection_id", UUID.class);
      result
          .computeIfAbsent(connectionId, key -> new ArrayList<>())
          .add(
              new SyncHealthShopRef(
                  row.get("id", UUID.class).toString(),
                  row.get("code", String.class),
                  row.get("name", String.class)));
    }
    return result;
  }

  /** 每个连接最近一次运行（按 started_at desc, id desc 排序取第一条）。 */
  private Map<UUID, RunSnapshot> loadLastRuns(List<UUID> connectionIds) {
    Map<UUID, RunSnapshot> result = new LinkedHashMap<>();
    if (connectionIds.isEmpty()) {
      return result;
    }
    List<Object> params = new ArrayList<>();
    params.add(currentTenant());
    params.addAll(connectionIds);
    String statement =
        "select distinct on (cu.connection_id) cu.connection_id, r.status, r.trigger_source,"
            + " r.started_at, r.finished_at, r.duration_ms"
            + " from sync_cursor cu join sync_run r on r.tenant_id = cu.tenant_id and r.cursor_id = cu.id"
            + " where cu.tenant_id = ? and cu.connection_id in ("
            + placeholders(connectionIds.size())
            + ") order by cu.connection_id, r.started_at desc, r.id desc";
    for (Record row : sql.fetch(statement, params.toArray())) {
      result.put(
          row.get("connection_id", UUID.class),
          new RunSnapshot(
              row.get("status", String.class),
              row.get("trigger_source", String.class),
              row.get("started_at", OffsetDateTime.class),
              row.get("finished_at", OffsetDateTime.class),
              row.get("duration_ms", Integer.class)));
    }
    return result;
  }

  /**
   * 每个连接的逐原因失败计数。
   *
   * @param window true = 近窗口；false = 累计
   */
  private Map<UUID, Map<String, Long>> loadFailureCounts(List<UUID> connectionIds, boolean window)
      {
    Map<UUID, Map<String, Long>> result = new LinkedHashMap<>();
    if (connectionIds.isEmpty()) {
      return result;
    }
    List<Object> params = new ArrayList<>();
    if (window) {
      params.add(windowStart());
    }
    params.add(currentTenant());
    params.addAll(connectionIds);
    String statement =
        "select cu.connection_id, f.reason,"
            + (window
                ? " count(*) filter (where f.occurred_at >= ?::timestamptz) cnt"
                : " count(*) cnt")
            + " from sync_failure f join sync_cursor cu"
            + " on cu.tenant_id = f.tenant_id and cu.id = f.cursor_id"
            + " where f.tenant_id = ? and cu.connection_id in ("
            + placeholders(connectionIds.size())
            + ") group by cu.connection_id, f.reason";
    for (Record row : sql.fetch(statement, params.toArray())) {
      UUID connectionId = row.get("connection_id", UUID.class);
      result
          .computeIfAbsent(connectionId, key -> new LinkedHashMap<>())
          .put(row.get("reason", String.class), row.get("cnt", Long.class));
    }
    return result;
  }

  /** 每个连接的待处理（status='OPEN'）失败条数。 */
  private Map<UUID, Long> loadOpenFailureCounts(List<UUID> connectionIds) {
    Map<UUID, Long> result = new LinkedHashMap<>();
    if (connectionIds.isEmpty()) {
      return result;
    }
    List<Object> params = new ArrayList<>();
    params.add(currentTenant());
    params.addAll(connectionIds);
    String statement =
        "select cu.connection_id, count(*) cnt from sync_failure f"
            + " join sync_cursor cu on cu.tenant_id = f.tenant_id and cu.id = f.cursor_id"
            + " where f.tenant_id = ? and f.status = 'OPEN' and cu.connection_id in ("
            + placeholders(connectionIds.size())
            + ") group by cu.connection_id";
    for (Record row : sql.fetch(statement, params.toArray())) {
      result.put(row.get("connection_id", UUID.class), row.get("cnt", Long.class));
    }
    return result;
  }

  /** 失败计数的窗口起点。 */
  private OffsetDateTime windowStart() {
    return dbNow().minusHours(FAILURE_WINDOW_HOURS);
  }

  private static List<SyncHealthFailureReasonCount> failureBreakdown(
      Map<String, Long> window, Map<String, Long> total) {
    List<SyncHealthFailureReasonCount> breakdown = new ArrayList<>();
    for (String reason : FAILURE_REASONS) {
      long windowCount = countOf(window, reason);
      long totalCount = countOf(total, reason);
      if (windowCount == 0 && totalCount == 0) {
        continue;
      }
      breakdown.add(new SyncHealthFailureReasonCount(reason, windowCount, totalCount));
    }
    return List.copyOf(breakdown);
  }

  // ==================================================================
  // 总览
  // ==================================================================

  /** 总览：红黄绿分布、失败计数、限流命中、积压、没有连接的平台。 */
  public SyncHealthSummary summary() {
    try {
      Filters filters = new Filters("", "", "");
      int realTotal = countConnections(filters);
      List<SyncHealthConnectionCard> cards = loadCards(filters, 0, MAX_PAGE_SIZE);
      // 🔴 装载上限：真的装不下时必须**说**，不能拿前 N 条的分布冒充全量分布。
      boolean truncated = cards.size() < realTotal;
      int red = 0;
      int yellow = 0;
      int green = 0;
      long failureWindow = 0L;
      long failureTotal = 0L;
      long rateWindow = 0L;
      long rateTotal = 0L;
      long openFailures = 0L;
      Map<String, long[]> byReason = new LinkedHashMap<>();
      for (SyncHealthConnectionCard card : cards) {
        if (SEVERITY_RED.equals(card.severity())) {
          red++;
        } else if (SEVERITY_YELLOW.equals(card.severity())) {
          yellow++;
        } else {
          green++;
        }
        failureWindow += card.failureCountWindow();
        failureTotal += card.failureCountTotal();
        rateWindow += card.rateLimitedCountWindow();
        rateTotal += card.rateLimitedCountTotal();
        openFailures += card.openFailureCount();
        for (SyncHealthFailureReasonCount entry : card.failureByReason()) {
          long[] slot = byReason.computeIfAbsent(entry.reason(), key -> new long[2]);
          slot[0] += entry.windowCount();
          slot[1] += entry.totalCount();
        }
      }
      List<SyncHealthFailureReasonCount> reasonCounts = new ArrayList<>();
      for (String reason : FAILURE_REASONS) {
        long[] slot = byReason.get(reason);
        if (slot == null || (slot[0] == 0L && slot[1] == 0L)) {
          continue;
        }
        reasonCounts.add(new SyncHealthFailureReasonCount(reason, slot[0], slot[1]));
      }
      List<SyncHealthBacklogSource> backlog = backlogSources();
      long backlogTotal = 0L;
      for (SyncHealthBacklogSource source : backlog) {
        backlogTotal += source.pendingCount();
      }
      return new SyncHealthSummary(
          realTotal,
          truncated,
          red,
          yellow,
          green,
          failureWindow,
          failureTotal,
          rateWindow,
          rateTotal,
          openFailures,
          List.copyOf(reasonCounts),
          backlog,
          backlogTotal,
          platformsWithoutConnection(),
          dbNow());
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  // ==================================================================
  // 积压
  // ==================================================================

  /**
   * 🔴 积压：**逐张表**报，每张表带「算作积压」的判定条件。
   *
   * <p>不许出现一个来源不明的总数 —— 那样的话，没人会知道该去处理哪张表。
   */
  public List<SyncHealthBacklogSource> backlogSources() {
    try {
      UUID tenant = currentTenant();

      long outbox = count(
          "select count(*) from outbox_event where tenant_id = ? and published_at is null", tenant);
      long content =
          count(
              "select count(*) from listing_content_writeback_task where tenant_id = ? and state = 'PENDING'",
              tenant);
      long statusAction =
          count(
              "select count(*) from listing_status_action_queue where tenant_id = ?"
                  + " and state in ('OPEN','ACKNOWLEDGED','DISPATCHED')",
              tenant);
      long publish =
          count(
              "select count(*) from publish_task where tenant_id = ? and state = 'PENDING'", tenant);

      return List.of(
          new SyncHealthBacklogSource(
              "outbox_event",
              "事件发件箱积压",
              "published_at IS NULL",
              outbox,
              "已产生但**还没发布**的领域事件。这是同步链路的下游：事件压住 = 下游服务看不到最新数据。"),
          new SyncHealthBacklogSource(
              "listing_content_writeback_task",
              "内容回写任务积压",
              "state = 'PENDING'",
              content,
              "已排队但还没派发的内容回写任务（4.2 刊登内容改后回写平台）。"),
          new SyncHealthBacklogSource(
              "listing_status_action_queue",
              "平台状态动作队列积压",
              "state IN ('OPEN','ACKNOWLEDGED','DISPATCHED')",
              statusAction,
              "平台通知引发的待处理动作（重发 / 复核 / 冲突告警）。"
                  + "DISPATCHED 仍在途，所以也算积压。"),
          new SyncHealthBacklogSource(
              "publish_task",
              "发布任务积压",
              "state = 'PENDING'",
              publish,
              "批量发布里排队等派发的任务（4.3 批量发布）。"));
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  private long count(String statement, Object... params) {
    Object raw = sql.fetchValue(statement, params);
    return raw instanceof Number number ? number.longValue() : 0L;
  }

  // ==================================================================
  // 没有连接的平台
  // ==================================================================

  /**
   * 🔴 <b>「这个平台没有连接」不是「连接健康」</b>。
   *
   * <p>它单列一份：把「还没接这个平台」混进健康卡片，运营会以为平台侧出了问题。
   */
  public List<SyncHealthPlatformGap> platformsWithoutConnection() {
    try {
      List<Object> params = new ArrayList<>();
      params.add(currentTenant());
      String statement =
          "select p.code, p.name, p.support_status from platform p"
              + " where not exists (select 1 from channel_connection c"
              + " where c.tenant_id = ? and c.platform_id = p.id)"
              + " order by p.position";
      List<SyncHealthPlatformGap> gaps = new ArrayList<>();
      for (Record row : sql.fetch(statement, params.toArray())) {
        String support = row.get("support_status", String.class);
        String code = row.get("code", String.class);
        String name = row.get("name", String.class);
        String state;
        String message;
        if ("AVAILABLE".equals(support)) {
          state = "NO_CONNECTION";
          message =
              name + "（"
                  + code
                  + "）在平台目录里是已接入的，但这个租户**还没有建立任何连接** —— "
                  + "这是「还没接」，不是「连接有问题」。";
        } else if ("BETA".equals(support)) {
          state = "NO_CONNECTION_BETA";
          message =
              name + "（"
                  + code
                  + "）处于灰度接入状态，这个租户还没有建立连接 —— 没接是预期内的，不算缺口。";
        } else {
          state = "NOT_INTEGRATED";
          message =
              name + "（"
                  + code
                  + "）尚未接入，本仓没有它的适配器 —— 没有连接是**预期之内**，不是缺口。";
        }
        gaps.add(new SyncHealthPlatformGap(code, name, support, state, message));
      }
      return List.copyOf(gaps);
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  // ==================================================================
  // 需处理项
  // ==================================================================

  /**
   * 需处理项（只给**本模块真实存在**的待处理项）。
   *
   * <p>🔴 本模块**只读**，所以每条待处理项都指向**归属系统**的页面，
   * 本模块自己**不**提供「重试 / 重新授权」这类写端点。
   */
  public SyncHealthPendingList pendingItems() {
    try {
      Filters filters = new Filters("", "", "");
      int realTotal = countConnections(filters);
      List<SyncHealthConnectionCard> cards = loadCards(filters, 0, MAX_PAGE_SIZE);
      boolean truncated = cards.size() < realTotal;
      List<SyncHealthPendingItem> items = new ArrayList<>();

      for (SyncHealthConnectionCard card : cards) {
        String label = card.platformName() + " · " + card.displayName();
        if ("EXPIRED".equals(card.status())
            || "REVOKED".equals(card.status())
            || "EXPIRING".equals(card.status())) {
          items.add(
              new SyncHealthPendingItem(
                  "REAUTHORIZE",
                  SEVERITY_RED,
                  "重新授权：" + label,
                  "连接状态是 " + card.status() + "，不重新授权它就不会再同步。",
                  "2.2.1.2",
                  "/channels",
                  "渠道与店铺 → 授权连接",
                  true,
                  "授权向导在「渠道与店铺」页的「授权连接」Tab 里。"
                      + "⚠️ 本仓的渠道工作台用的是**页内 Tab**，没有可深链的 URL，"
                      + "所以跳转只能到 /channels 整页，落点写在提示里。"));
        }
        if ("FAILED".equals(card.lastRunStatus())
            || "PARTIAL".equals(card.lastRunStatus())
            || "CANCELLED".equals(card.lastRunStatus())) {
          items.add(
              new SyncHealthPendingItem(
                  "VIEW_SYNC_RUNS",
                  "FAILED".equals(card.lastRunStatus()) ? SEVERITY_RED : SEVERITY_YELLOW,
                  "看同步运行：" + label,
                  "最近一次同步运行的 status=" + card.lastRunStatus() + "。",
                  "2.4.1.1",
                  "/channels",
                  "渠道与店铺 → 同步运行",
                  true,
                  "同步任务列表与运行日志在「渠道与店铺」页的「同步运行」Tab 里。"));
        }
        if (card.openFailureCount() > 0) {
          items.add(
              new SyncHealthPendingItem(
                  "VIEW_FAILURE_QUEUE",
                  SEVERITY_YELLOW,
                  "看失败队列：" + label,
                  "这条连接下有 " + card.openFailureCount() + " 条待处理（OPEN）失败记录。",
                  "2.4.2.1",
                  "/channels",
                  "渠道与店铺 → 同步运行（失败原因分布）",
                  true,
                  "⚠️ 失败队列（2.4.2.1）在本仓**没有独立前端页面**"
                      + "（台账路由为 —），它的内容在「渠道与店铺 → 同步运行」Tab 的"
                      + "失败原因分布里 —— 所以指向那里，而不是指向一个不存在的路由。"));
        }
      }

      long backlogTotal = 0L;
      for (SyncHealthBacklogSource source : backlogSources()) {
        backlogTotal += source.pendingCount();
      }
      if (backlogTotal > 0) {
        items.add(
            new SyncHealthPendingItem(
                "VIEW_BACKLOG",
                SEVERITY_YELLOW,
                "查队列积压：" + backlogTotal + " 条",
                "事件发件箱与三张任务队列里有 " + backlogTotal + " 条待处理。",
                "1.5.1.1",
                "/system-health",
                "本页 · 队列积压",
                true,
                "积压就统计在本页的「队列积压」段里（逐张表列出），"
                    + "所以指向本页自己而不是别处。"));
      }

      return new SyncHealthPendingList(List.copyOf(items), items.size(), truncated);
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  // ==================================================================
  // 筛选项
  // ==================================================================

  /** 筛选项下拉（平台码来自**真实连接**，不是写死的常量）。 */
  public SyncHealthOptions options() {
    try {
      List<String> platforms = new ArrayList<>();
      String statement =
          "select distinct p.code from channel_connection c join platform p on p.id = c.platform_id"
              + " where c.tenant_id = ? order by p.position";
      for (Record row : sql.fetch(statement, currentTenant())) {
        platforms.add(row.get("code", String.class));
      }
      return new SyncHealthOptions(
          List.copyOf(platforms),
          List.of("ACTIVE", "EXPIRING", "EXPIRED", "REVOKED", "PAUSED"),
          SEVERITY_LEVELS,
          FAILURE_REASONS);
    } catch (org.jooq.exception.DataAccessException e) {
      throw unreadable(e);
    }
  }

  private static long countOf(Map<String, Long> counts, String reason) {
    if (counts == null) {
      return 0L;
    }
    Long value = counts.get(reason);
    return value == null ? 0L : value;
  }

  private static long sum(Map<String, Long> counts) {
    if (counts == null) {
      return 0L;
    }
    long total = 0L;
    for (Long value : counts.values()) {
      if (value != null) {
        total += value;
      }
    }
    return total;
  }
}
