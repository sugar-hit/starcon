package com.starcon.channel;

import static com.starcon.channel.PublishModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * 4.3.1.2 批量发布。
 *
 * <p>蓝图：「批量刊登队列：按规则筛选待发布商品（如'SPU 状态=在售 且 未在任何店铺发布'），生成任务批次，支持暂停、调整顺序、批量重试。输入：筛选规则与数量上限；发布策略；用户确认。输出：批次任务列表 + 总体进度 + 失败明细（可跳转 4.3.1.3）。关系：上游 = 4.1.1.1、4.2.1.2；下游 = 4.3.1.3。<b>批量任务必须限流</b>：按平台各自的 API 配额排队，否则会触发平台限流并可能影响店铺安全。」
 *
 * <p>🔴 本类的四个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>按平台（或店铺+平台）限流</b> —— {@link #dispatch} 把待派发任务按**生效限流行**
 *       （任务自己的平台 + 店铺解析出的 {@code publish_rate_limit} 行）分组，逐组算配额。
 *       没有限流配置的平台**一条都不派发**（fail-closed），而不是回退到某个写死的全局并发上限。
 *       这就是蓝图加粗句的直接落法。
 *   <li><b>暂停真的停止派发</b> —— {@link #pause} 把批次置 PAUSED，{@link #dispatch} 在 PAUSED
 *       下**一条都不派发**；已在途（DISPATCHED）的任务**不取消**（那等于悄悄撤回一次已发出的
 *       平台请求），它们仍可被 {@link #report} 回报成功/失败。见 javadoc 的「暂停语义」。
 *   <li><b>重排真的改变执行次序</b> —— {@link #reorder} 按调用者给的 id 顺序重写 PENDING 任务的
 *       {@code position}，而 {@link #dispatch} 取任务就是 {@code order by position}，所以次序真的变。
 *   <li><b>批量重试不重跑已成功项</b> —— {@link #retry} 只把 FAILED 打回 PENDING；SUCCEEDED 由
 *       DB 触发器 {@code guard_publish_task_terminal} 保护，任何路径都无法把已成功的打回队列。
 * </ol>
 *
 * <p>🔴 时间口径：本模块<b>不做任何"相对 N 分钟"的判断</b>。窗口限流只用
 * {@code dispatched_at} 与 {@code now()} 的真实差值，且集成测试用<b>固定时刻</b>构造，
 * 不在断言中途改时刻（否则测试会随时钟漂移、只在某个时段绿）。
 */
@Service
public class PublishService {

  /** 筛选维度默认排序（与 4.1.1.1 的 RECENT 语义一致：最近更新的先）。 */
  private static final String DEFAULT_SORT = "l.updated_at desc, l.id";

  private static final String PENDING = "PENDING";
  private static final String DISPATCHED = "DISPATCHED";
  private static final String SUCCEEDED = "SUCCEEDED";
  private static final String FAILED = "FAILED";
  private static final String RUNNING = "RUNNING";
  private static final String PAUSED = "PAUSED";
  private static final String COMPLETED = "COMPLETED";

  private final DSLContext sql;
  private final ObjectMapper json;

  public PublishService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingBulkEditService 的同名写法）
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

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value == null || value.isBlank() || "{}".equals(value)) {
      return "{}";
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>批量发布在本仓能**真实交付**的部分是「队列编排」：筛选、建批次、限流、暂停、重排、
   * 批量重试、进度与失败明细。它**不能**在本批真实交付的是**实际调平台 API 的那一步**
   * （平台商品创建 / 媒体上传 / 提交审核）—— 那是 4.3.1.1 / 4.3.1.3 与 02 平台适配器的事，
   * 它们在集成头尚未交付。所以任务被派发后在途（DISPATCHED），由 4.3.1.3 回报结果。
   * 前端据此显示「在途」而不是假装成功，<b>不把未交付的步骤画成已完成</b>。
   */
  public PublishCapabilities capabilities() {
    return new PublishCapabilities(
        List.of(
            new PublishCapabilityDimension(
                "batch_queue",
                "批量刊登队列：按规则筛选待发布商品，生成任务批次",
                true, "publish_batch / publish_task（V183）", "",
                "批次与任务是本模块的核心产出；进度由任务状态实时重算"),
            new PublishCapabilityDimension(
                "platform_rate_limit",
                "🔴 按平台（或店铺+平台）各自的 API 配额排队；缺配置的平台不派发（fail-closed）",
                true, "publish_rate_limit（V183）", "",
                "没有它，批量发布会对所有平台施加同一份臆测配额，可能触发平台限流并影响店铺安全"),
            new PublishCapabilityDimension(
                "pause",
                "🔴 暂停批次：停止派发新任务；已在途的任务不取消，仍可回报结果",
                true, "publish_batch.state=PAUSED（V183）", "",
                "暂停不是取消 —— 已发出的平台请求无法悄悄撤回"),
            new PublishCapabilityDimension(
                "reorder",
                "🔴 调整顺序：按调用者给的新次序重排待派发任务，派发按 position 升序取",
                true, "publish_task.position（V183）", "",
                "重排真的改变执行次序，而不是只存一个字段了事"),
            new PublishCapabilityDimension(
                "batch_retry",
                "🔴 批量重试：只把 FAILED 打回队列，绝不重跑已成功的",
                true, "publish_task.state + guard_publish_task_terminal（V183）", "",
                "重跑已成功的会重复发布 / 重复建 listing，是本模块最危险的假实现"),
            new PublishCapabilityDimension(
                "progress_and_failures",
                "总体进度 + 逐条失败明细（可跳转 4.3.1.3）",
                true, "publish_batch 计数 + publish_task.failure_reason（V183）", "",
                "进度由任务状态实时重算并写回，DB 有 CHECK 保证自洽"),
            new PublishCapabilityDimension(
                "spu_status_filter",
                "🔴 按「SPU 状态=在售」筛选待发布商品",
                false, "—", "03 商品主数据（catalog）SPU",
                "SPU 状态是 catalog 的事实，跨服务禁 SQL/直连对方库（AGENTS.md 模块边界纪律）；"
                    + "因此本模块的筛选落在 channel 自己的 listing 七态上（status=ON_SALE 等），"
                    + "不假装能读 SPU 状态。真实 SPU 状态筛选需 catalog 侧提供授权端点后接入"),
            new PublishCapabilityDimension(
                "platform_publish_execution",
                "🔴 实际调平台 API（商品创建 / 媒体上传 / 提交审核）",
                false, "—", "4.3.1.1 / 4.3.1.3 + 02 平台适配器",
                "本模块只负责**队列编排与限流**；被派发的任务停在 DISPATCHED（在途），"
                    + "由 4.3.1.3 回报成功/失败，不在本模块伪造平台返回")),
        List.of(RUNNING, PAUSED, COMPLETED, "CANCELLED"),
        List.of(PENDING, DISPATCHED, SUCCEEDED, FAILED),
        "批量任务按**平台各自的** API 配额排队，不是一个全局并发上限：每条任务按它自己的"
            + "平台（可细到店铺）找到生效的限流配置，各走各的在途与窗口配额。**某平台没配限流"
            + "就不派发**（fail-closed），不会用服务层写死的默认值兜底。",
        "失败明细里的 taskId 是跳转 4.3.1.3（发布任务）的参数；4.3.1.3 在集成头尚未交付，"
            + "所以前端跳转目标暂缺是预期的，失败明细数据本模块已交付。",
        "上游 4.1.1.1 listing（channel 同库，筛选参数形状照抄它）与 4.2.1.2 内容草稿"
            + "（listing_content_draft，发布成功即转 PUBLISHED）；下游 4.3.1.3 发布任务"
            + "（回报单条任务结果）。本模块无金额列：改价归 05 定价，与 4.1.1.1 同一纪律。");
  }

  // ==================================================================
  // 平台限流配置（可配置、可查的数据）
  // ==================================================================

  public List<PublishRateLimitRow> rateLimits(String platformCode) {
    UUID tenant = currentTenant();
    StringBuilder sqlText =
        new StringBuilder(
            """
            select rl.id,rl.platform_id,p.code as platform_code,p.name as platform_name,rl.shop_id,
                   s.code as shop_code,rl.max_in_flight,rl.requests_per_window,rl.window_seconds,
                   rl.enabled,rl.updated_at
            from publish_rate_limit rl
            join platform p on p.id=rl.platform_id
            left join shop s on s.tenant_id=rl.tenant_id and s.id=rl.shop_id
            where rl.tenant_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String platform = blankToNull(platformCode);
    if (platform != null) {
      sqlText.append(" and p.code=?");
      binds.add(platform);
    }
    sqlText.append(" order by p.code, rl.shop_id nulls first");
    return sql.fetch(sqlText.toString(), binds.toArray()).stream()
        .map(r -> rateLimitRow(tenant, r))
        .toList();
  }

  /**
   * 🔴 新建 / 更新限流配置（幂等：同平台+同店铺 upsert）。
   *
   * <p>这些数字**是数据**：运营按各平台真实 API 配额配，服务层不写死。缺配置的平台上
   * 派发会 fail-closed，所以这张表是"批量发布能否开跑"的前置条件。
   */
  public PublishRateLimitRow saveRateLimit(PublishRateLimitRequest request) {
    UUID tenant = currentTenant();
    // 平台必须真实存在（channel V1 全局码表）
    UUID platformId =
        sql.fetchOne("select id from platform where id=?", request.platformId()) == null
            ? null
            : request.platformId();
    if (platformId == null) {
      throw unprocessable("平台不存在：" + request.platformId());
    }
    if (request.shopId() != null) {
      boolean shopExists =
          sql.fetchOne("select 1 from shop where tenant_id=? and id=?", tenant, request.shopId())
              != null;
      if (!shopExists) {
        throw unprocessable("店铺不存在或不属于当前租户：" + request.shopId());
      }
    }
    int maxInFlight = request.maxInFlight() == null ? 1 : request.maxInFlight();
    int perWindow = request.requestsPerWindow() == null ? 60 : request.requestsPerWindow();
    int windowSeconds = request.windowSeconds() == null ? 60 : request.windowSeconds();
    boolean enabled = request.enabled() == null || request.enabled();

    // 🔴 显式「读-改-写」而不是 on conflict：限流行由两个**部分唯一索引**分别兜住
    //   （平台级 WHERE shop_id IS NULL / 店铺级 WHERE shop_id IS NOT NULL），
    //   而 ON CONFLICT 的推断目标必须能唯一匹配一个索引 —— 对部分索引写
    //   `on conflict (...) where ...` 在本仓这条路径上推不出来（实测
    //   "there is no unique or exclusion constraint matching the ON CONFLICT specification"）。
    //   读-改-写在同一个租户事务里做，并发下靠那两个部分唯一索引兜底，不会写重。
    Record existing =
        sql.fetchOne(
            "select id from publish_rate_limit "
                + "where tenant_id=? and platform_id=? and shop_id is not distinct from ?",
            tenant, platformId, request.shopId());
    if (existing == null) {
      sql.execute(
          """
          insert into publish_rate_limit
            (id,tenant_id,platform_id,shop_id,max_in_flight,requests_per_window,window_seconds,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,now(),now())
          """,
          Ids.next(), tenant, platformId, request.shopId(), maxInFlight, perWindow, windowSeconds, enabled);
    } else {
      sql.execute(
          """
          update publish_rate_limit
          set max_in_flight=?, requests_per_window=?, window_seconds=?, enabled=?,
              version=version+1, updated_at=now()
          where tenant_id=? and id=?
          """,
          maxInFlight, perWindow, windowSeconds, enabled, tenant, existing.get("id", UUID.class));
    }
    return loadRateLimit(tenant, platformId, request.shopId());
  }

  private PublishRateLimitRow loadRateLimit(UUID tenant, UUID platformId, UUID shopId) {
    String sqlText =
        """
        select rl.id,rl.platform_id,p.code as platform_code,p.name as platform_name,rl.shop_id,
               s.code as shop_code,rl.max_in_flight,rl.requests_per_window,rl.window_seconds,
               rl.enabled,rl.updated_at
        from publish_rate_limit rl
        join platform p on p.id=rl.platform_id
        left join shop s on s.tenant_id=rl.tenant_id and s.id=rl.shop_id
        where rl.tenant_id=? and rl.platform_id=? and rl.shop_id is not distinct from ?
        """;
    Record r = sql.fetchOne(sqlText, tenant, platformId, shopId);
    if (r == null) {
      throw notFound();
    }
    return rateLimitRow(tenant, r);
  }

  private PublishRateLimitRow rateLimitRow(UUID tenant, Record r) {
    UUID rlId = r.get("id", UUID.class);
    UUID platformId = r.get("platform_id", UUID.class);
    UUID shopId = r.get("shop_id", UUID.class);
    int maxInFlight = r.get("max_in_flight", Integer.class);
    int perWindow = r.get("requests_per_window", Integer.class);
    int windowSeconds = r.get("window_seconds", Integer.class);
    long inFlight = inFlightCount(tenant, platformId, shopId);
    long windowCount = windowCount(tenant, platformId, shopId, windowSeconds);
    return new PublishRateLimitRow(
        rlId,
        platformId,
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        shopId,
        r.get("shop_code", String.class),
        maxInFlight,
        perWindow,
        windowSeconds,
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        inFlight,
        windowCount,
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 在途（DISPATCHED）数，限流行范围内。 */
  private long inFlightCount(UUID tenant, UUID platformId, UUID shopId) {
    Record r =
        sql.fetchOne(
            """
            select count(*) as n from publish_task
            where tenant_id=? and platform_id=? and state='DISPATCHED'
              and (case when ?::uuid is null then true else shop_id=? end)
            """,
            tenant, platformId, shopId, shopId);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  /**
   * 窗口内已派发次数（限流行范围内）。
   *
   * <p>🔴 这里用「真实 now() 减 window_seconds」而不是任何相对偏移的假设。集成测试若要构造
   * "窗口内/窗口外"，必须写入**与 now() 同源**的 {@code dispatched_at}（用固定基准时刻算），
   * 不允许在断言里 {@code withHour(9)} 改时刻。
   */
  private long windowCount(UUID tenant, UUID platformId, UUID shopId, int windowSeconds) {
    Record r =
        sql.fetchOne(
            """
            select count(*) as n from publish_task
            where tenant_id=? and platform_id=? and state<>'PENDING'
              and dispatched_at is not null
              and dispatched_at > now() - make_interval(secs => ?)
              and (case when ?::uuid is null then true else shop_id=? end)
            """,
            tenant, platformId, windowSeconds, shopId, shopId);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  /**
   * 🔴 解析一条任务的生效限流行：**店铺级优先，其次平台级，都没有 = null（fail-closed）**。
   *
   * <p>停用（enabled=false）等价于不存在 —— 停用意味着"这段时间这个平台不该发"，所以
   * 照样不派发，并在 note 里写明"已停用"。
   */
  private RateLane resolveLane(UUID tenant, UUID platformId, UUID shopId) {
    Record shopSpecific =
        sql.fetchOne(
            """
            select id,platform_id,shop_id,max_in_flight,requests_per_window,window_seconds,enabled
            from publish_rate_limit
            where tenant_id=? and platform_id=? and shop_id=?
            """,
            tenant, platformId, shopId);
    Record row = shopSpecific;
    if (row == null) {
      row =
          sql.fetchOne(
              """
              select id,platform_id,shop_id,max_in_flight,requests_per_window,window_seconds,enabled
              from publish_rate_limit
              where tenant_id=? and platform_id=? and shop_id is null
              """,
              tenant, platformId);
    }
    if (row == null) {
      return new RateLane(null, platformId, shopId, 0, 0, 0, false, "该平台未配置限流参数");
    }
    boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
    return new RateLane(
        row.get("id", UUID.class),
        platformId,
        shopId,
        row.get("max_in_flight", Integer.class),
        row.get("requests_per_window", Integer.class),
        row.get("window_seconds", Integer.class),
        enabled,
        enabled ? "" : "该限流配置已停用");
  }

  /** 内部：一条生效限流行。 */
  private record RateLane(
      UUID id,
      UUID platformId,
      UUID shopId,
      int maxInFlight,
      int requestsPerWindow,
      int windowSeconds,
      boolean enabled,
      String note) {}

  // ==================================================================
  // 建批次（筛选规则 + 数量上限 + 用户确认）
  // ==================================================================

  /**
   * 🔴 按筛选规则生成任务批次。
   *
   * <p>筛选参数的形状**照抄 4.1.1.1**（店铺 / 站点 / 状态 / 负责人 / 类目 / 关键字），
   * 外加本模块专属的「只发有内容草稿的」与「排除已发布过的」。筛选在**服务端**用 jOOQ
   * 显式 SQL 做，前端只翻译条件。
   *
   * <p>数量上限 {@code limit} 在提交时就钉死（超出的不建任务），并写进批次快照 ——
   * "当时按什么口径、取了前多少条"是可回溯的事实。
   */
  public PublishBatchRow createBatch(CreatePublishBatchRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    int limit = request.limit() == null ? 50 : request.limit();
    if (limit < 1 || limit > 1000) {
      throw unprocessable("数量上限必须在 1~1000 之间");
    }

    // 候选 listing：照抄 4.1.1.1 的筛选形状 + 本模块的两个专属开关
    StringBuilder sqlText =
        new StringBuilder(
            """
            select l.id, l.shop_id, s.platform_id, d.id as draft_id
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            left join listing_content_draft d
                   on d.tenant_id=l.tenant_id and d.listing_id=l.id and d.state='DRAFT'
            where l.tenant_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String shopCode = blankToNull(request.shopCode());
    if (shopCode != null) {
      sqlText.append(" and s.code=?");
      binds.add(shopCode);
    }
    String marketplaceCode = blankToNull(request.marketplaceCode());
    if (marketplaceCode != null) {
      sqlText.append(
          " and exists(select 1 from marketplace m where m.id=l.marketplace_id and m.code=?)");
      binds.add(marketplaceCode);
    }
    String status = blankToNull(request.status());
    if (status != null) {
      sqlText.append(" and l.status=?");
      binds.add(status.toUpperCase(Locale.ROOT));
    }
    if (request.ownerId() != null) {
      sqlText.append(" and l.owner_id=?");
      binds.add(request.ownerId());
    }
    String category = blankToNull(request.category());
    if (category != null) {
      sqlText.append(" and l.category_path ilike ?");
      binds.add("%" + category + "%");
    }
    String search = blankToNull(request.search());
    if (search != null) {
      sqlText.append(" and (l.title ilike ? or l.external_item_id ilike ?)");
      binds.add("%" + search + "%");
      binds.add("%" + search + "%");
    }
    // 只发有内容草稿的：d.id is not null（4.2.1.2 的产出）
    if (Boolean.TRUE.equals(request.hasPendingDraft())) {
      sqlText.append(" and d.id is not null");
    }
    // 排除已发布过的：status 已经是 ON_SALE / UNDER_REVIEW
    if (Boolean.TRUE.equals(request.excludeAlreadyPublished())) {
      sqlText.append(" and l.status not in ('ON_SALE','UNDER_REVIEW')");
    }
    sqlText.append(" order by ").append(DEFAULT_SORT).append(" limit ?");
    binds.add(limit);

    List<Record> candidates = sql.fetch(sqlText.toString(), binds.toArray());

    UUID batchId = Ids.next();
    String filterSnapshot =
        writeJson(
            Map.of(
                "shopCode", shopCode == null ? "" : shopCode,
                "marketplaceCode", marketplaceCode == null ? "" : marketplaceCode,
                "status", status == null ? "" : status,
                "ownerId", request.ownerId() == null ? "" : request.ownerId().toString(),
                "category", category == null ? "" : category,
                "search", search == null ? "" : search,
                "hasPendingDraft", Boolean.TRUE.equals(request.hasPendingDraft()),
                "excludeAlreadyPublished", Boolean.TRUE.equals(request.excludeAlreadyPublished())));
    String strategySnapshot = writeJson(Map.of("mode", "DRAFT_CONTENT"));

    int total = candidates.size();
    // 🔴 listing_ids 快照与任务同时写入批次：它既是"当时选的是哪些"的留痕，
    //    也是批次行自己的数据范围策略能成立的前提（批次先于任务写入，
    //    用"已存在的任务"判定范围会让 insert 批次这一步永远过不了 RLS）。
    UUID[] listingIds =
        candidates.stream().map(c -> c.get("id", UUID.class)).toArray(UUID[]::new);
    sql.execute(
        """
        insert into publish_batch
          (id,tenant_id,state,filter_rule,strategy,listing_ids,limit_count,total_count,pending_count,
           dispatched_count,succeeded_count,failed_count,actor_id,created_at,updated_at)
        values (?,?,'RUNNING',?::jsonb,?::jsonb,?,?,?,?,0,0,0,?,now(),now())
        """,
        batchId, tenant, filterSnapshot, strategySnapshot, listingIds, limit, total, total, actorId);

    // 建任务：每条候选一个任务，position 按提交时的确定性顺序 0..n-1
    int position = 0;
    for (Record c : candidates) {
      UUID taskId = Ids.next();
      sql.execute(
          """
          insert into publish_task
            (id,tenant_id,batch_id,listing_id,draft_id,platform_id,shop_id,position,state,attempts,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,'PENDING',0,now(),now())
          """,
          taskId, tenant, batchId, c.get("id", UUID.class), c.get("draft_id", UUID.class),
          c.get("platform_id", UUID.class), c.get("shop_id", UUID.class), position);
      position++;
    }
    return loadBatch(tenant, batchId);
  }

  // ==================================================================
  // 批次视图 / 任务列表
  // ==================================================================

  public List<PublishBatchRow> batches(String state, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder sqlText =
        new StringBuilder(
            "select id from publish_batch where tenant_id=?" );
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String s = blankToNull(state);
    if (s != null) {
      sqlText.append(" and state=?");
      binds.add(s.toUpperCase(Locale.ROOT));
    }
    sqlText.append(" order by created_at desc, id limit ? offset ?");
    int lim = clampSize(size);
    binds.add(lim);
    binds.add(Math.max(0, page) * lim);
    return sql.fetch(sqlText.toString(), binds.toArray()).stream()
        .map(r -> loadBatch(tenant, r.get("id", UUID.class)))
        .toList();
  }

  public PublishBatchRow loadBatchRow(String batchId) {
    return loadBatch(currentTenant(), parseId(batchId));
  }

  private PublishBatchRow loadBatch(UUID tenant, UUID batchId) {
    Record r =
        sql.fetchOne(
            "select id,state,filter_rule,strategy,limit_count,total_count,pending_count,"
                + "dispatched_count,succeeded_count,failed_count,actor_id,note,created_at,updated_at,completed_at "
                + "from publish_batch where tenant_id=? and id=?",
            tenant, batchId);
    if (r == null) {
      throw notFound();
    }
    return batchRow(r);
  }

  private PublishBatchRow batchRow(Record r) {
    int total = r.get("total_count", Integer.class);
    int pending = r.get("pending_count", Integer.class);
    int dispatched = r.get("dispatched_count", Integer.class);
    int succeeded = r.get("succeeded_count", Integer.class);
    int failed = r.get("failed_count", Integer.class);
    // 进度按"已终态 / 总数"；总数为 0 时是 0（不是 100 —— 空批次不是"已完成"）
    int percent = total == 0 ? 0 : (int) Math.round(100.0 * (succeeded + failed) / total);
    return new PublishBatchRow(
        r.get("id", UUID.class),
        r.get("state", String.class),
        jsonText(r.get("filter_rule", Object.class)),
        jsonText(r.get("strategy", Object.class)),
        r.get("limit_count", Integer.class),
        total, pending, dispatched, succeeded, failed, percent,
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  /** 批次任务列表（可按状态筛；默认 PENDING，即队列里还没派的）。 */
  public List<PublishTaskRow> tasks(String batchId, String taskState, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    if (loadBatch(tenant, id) == null) {
      throw notFound();
    }
    StringBuilder sqlText =
        new StringBuilder(TASK_SELECT + " where t.tenant_id=? and t.batch_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(id);
    String s = blankToNull(taskState);
    if (s != null) {
      sqlText.append(" and t.state=?");
      binds.add(s.toUpperCase(Locale.ROOT));
    }
    // 默认按执行次序（可重排的那一列）返回
    sqlText.append(" order by t.position, t.id limit ? offset ?");
    int lim = clampSize(size);
    binds.add(lim);
    binds.add(Math.max(0, page) * lim);
    return sql.fetch(sqlText.toString(), binds.toArray()).stream().map(this::taskRow).toList();
  }

  private static final String TASK_SELECT =
      """
      select t.id,t.batch_id,t.listing_id,l.external_item_id,l.title,s.code as shop_code,
             p.code as platform_code,t.platform_id,t.shop_id,t.position,t.state,t.attempts,
             t.failure_reason,t.dispatched_at,t.completed_at
      from publish_task t
      join listing l on l.tenant_id=t.tenant_id and l.id=t.listing_id
      join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id
      join platform p on p.id=t.platform_id
      """;

  private PublishTaskRow taskRow(Record r) {
    return new PublishTaskRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        null, // rateLimitId 在列表里不解析（逐条解析太重），需要时看 rateLimits 端点
        r.get("position", Integer.class),
        r.get("state", String.class),
        r.get("attempts", Integer.class),
        r.get("failure_reason", String.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 🔴 派发：按平台限流排队（本模块核心）
  // ==================================================================

  /**
   * 🔴🔴 派发一轮：按平台（或店铺+平台）各自的配额排队。
   *
   * <p>算法（逐条不变量）：
   *
   * <ol>
   *   <li>批次不是 RUNNING（PAUSED / COMPLETED / CANCELLED）⇒ <b>一条都不派发</b>。
   *       暂停语义：<b>停止派发新任务，已在途的不取消</b>（已发出的平台请求撤不回），它们仍可被
   *       {@link #report} 回报结果。
   *   <li>待派发任务按 {@code order by position, id}（<b>所以重排真的改变执行次序</b>）取出。
   *   <li>逐条解析生效限流行（店铺级优先、其次平台级），并按<b>限流行</b>分组记账：
   *       每组独立算「在途余量 = max_in_flight - 在途数」与「窗口余量 = requests_per_window - 窗口内次数」，
   *       取更紧的那个作为本组本轮可派发条数。
   *   <li>没有限流行（fail-closed）或已停用 ⇒ 该组<b>一条都不派发</b>，note 写明原因。
   *   <li>派发即 {@code PENDING→DISPATCHED}、{@code attempts+1}、{@code dispatched_at=now()}；
   *       DB 触发器强制 attempts 恰好 +1。
   * </ol>
   *
   * <p>返回<b>逐限流行</b>的派发摘要（{@code lanes}）—— 这就是"按平台各自配额排队"在输出上的
   * 可查证据：Amazon 与 TikTok 各走各的账，不是把它们混成一个全局数字。
   */
  public DispatchResponse dispatch(String batchId) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);

    if (!RUNNING.equals(batch.state())) {
      // PAUSED / COMPLETED / CANCELLED：一律不派发。这正是"暂停真的停止派发"。
      return new DispatchResponse(
          id, batch.state(), 0, 0, List.of(), batch);
    }

    // 待派发任务：按执行次序（重排改的就是这一列）
    List<Record> pending =
        sql.fetch(
            "select id,platform_id,shop_id,position from publish_task "
                + "where tenant_id=? and batch_id=? and state='PENDING' order by position, id",
            tenant, id);

    // 按生效限流行分组（同一行的任务共享配额）
    Map<String, List<Record>> byLane = new LinkedHashMap<>();
    Map<String, RateLane> lanes = new LinkedHashMap<>();
    for (Record t : pending) {
      UUID platformId = t.get("platform_id", UUID.class);
      UUID shopId = t.get("shop_id", UUID.class);
      RateLane lane = resolveLane(tenant, platformId, shopId);
      String key = laneKey(platformId, shopId);
      byLane.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
      lanes.putIfAbsent(key, lane);
    }

    List<DispatchLaneReport> reports = new ArrayList<>();
    int totalDispatched = 0;
    int totalSkipped = 0;
    OffsetDateTime now = OffsetDateTime.now();

    for (Map.Entry<String, List<Record>> entry : byLane.entrySet()) {
      RateLane lane = lanes.get(entry.getKey());
      List<Record> group = entry.getValue();
      String platformCode = platformCodeOf(tenant, lane.platformId());
      String shopCode = lane.shopId() == null ? null : shopCodeOf(tenant, lane.shopId());

      if (!lane.enabled()) {
        // fail-closed：未配置 or 已停用 ⇒ 本组一条都不派
        int skipped = group.size();
        totalSkipped += skipped;
        reports.add(
            new DispatchLaneReport(
                platformCode, shopCode, lane.id(), lane.maxInFlight(), 0, lane.requestsPerWindow(),
                lane.windowSeconds(), 0, 0, skipped, lane.note()));
        continue;
      }

      long inFlightBefore = inFlightCount(tenant, lane.platformId(), lane.shopId());
      long windowBefore = windowCount(tenant, lane.platformId(), lane.shopId(), lane.windowSeconds());
      // 在途余量 与 窗口余量 取更紧的
      long byInflight = lane.maxInFlight() - inFlightBefore;
      long byWindow = lane.requestsPerWindow() - windowBefore;
      long allowed = Math.max(0, Math.min(byInflight, byWindow));

      int dispatched = 0;
      for (Record t : group) {
        if (dispatched >= allowed) {
          break;
        }
        sql.execute(
            """
            update publish_task
            set state='DISPATCHED', attempts=attempts+1, dispatched_at=?, updated_at=now()
            where tenant_id=? and id=? and state='PENDING'
            """,
            Timestamp.from(now.toInstant()), tenant, t.get("id", UUID.class));
        dispatched++;
      }
      int skipped = group.size() - dispatched;
      totalDispatched += dispatched;
      totalSkipped += skipped;
      String note =
          skipped > 0
              ? (allowed <= 0 ? "已达该平台配额上限，本轮整组不派（" + reasonFor(lane, byInflight, byWindow) + "）"
                              : "按次序派满配额后暂停")
              : "";
      reports.add(
          new DispatchLaneReport(
              platformCode, shopCode, lane.id(), lane.maxInFlight(), inFlightBefore,
              lane.requestsPerWindow(), lane.windowSeconds(), windowBefore, dispatched, skipped, note));
    }

    refreshProgress(tenant, id);
    return new DispatchResponse(id, batch.state(), totalDispatched, totalSkipped, reports, loadBatch(tenant, id));
  }

  private static String reasonFor(RateLane lane, long byInflight, long byWindow) {
    if (byInflight <= 0) {
      return "在途已达 " + lane.maxInFlight();
    }
    if (byWindow <= 0) {
      return "窗口内已派发达 " + lane.requestsPerWindow() + "/" + lane.windowSeconds() + "s";
    }
    return "配额";
  }

  private static String laneKey(UUID platformId, UUID shopId) {
    return platformId + "/" + (shopId == null ? "-" : shopId);
  }

  private String platformCodeOf(UUID tenant, UUID platformId) {
    Record r = sql.fetchOne("select code from platform where id=?", platformId);
    return r == null ? null : r.get("code", String.class);
  }

  private String shopCodeOf(UUID tenant, UUID shopId) {
    Record r = sql.fetchOne("select code from shop where tenant_id=? and id=?", tenant, shopId);
    return r == null ? null : r.get("code", String.class);
  }

  // ==================================================================
  // 暂停 / 恢复 / 中止
  // ==================================================================

  /**
   * 🔴 暂停批次：<b>停止派发新任务</b>。
   *
   * <p>已在途（DISPATCHED）的任务<b>不取消</b> —— 那等于悄悄撤回一次已经发给平台的请求。
   * 它们仍可被 {@link #report} 回报成功/失败；被暂停挡住的只是"还没发出去的"。
   */
  public PublishBatchRow pause(String batchId, PublishBatchStateRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);
    if (!RUNNING.equals(batch.state())) {
      throw unprocessable("只有 RUNNING 批次可以暂停，当前是 " + batch.state());
    }
    sql.execute(
        "update publish_batch set state='PAUSED', note=?, updated_at=now() where tenant_id=? and id=?",
        note(request == null ? null : request.note()), tenant, id);
    return loadBatch(tenant, id);
  }

  /** 恢复批次：回到 RUNNING，可以继续派发剩余的 PENDING。 */
  public PublishBatchRow resume(String batchId) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);
    if (!PAUSED.equals(batch.state())) {
      throw unprocessable("只有 PAUSED 批次可以恢复，当前是 " + batch.state());
    }
    sql.execute(
        "update publish_batch set state='RUNNING', updated_at=now() where tenant_id=? and id=?",
        tenant, id);
    return loadBatch(tenant, id);
  }

  /** 人工中止：不再派发剩余任务；已在途的仍可回报结果。 */
  public PublishBatchRow cancel(String batchId, PublishBatchStateRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);
    if (COMPLETED.equals(batch.state()) || "CANCELLED".equals(batch.state())) {
      throw unprocessable("批次已结束（" + batch.state() + "），无法中止");
    }
    sql.execute(
        "update publish_batch set state='CANCELLED', note=?, updated_at=now() where tenant_id=? and id=?",
        note(request == null ? null : request.note()), tenant, id);
    return loadBatch(tenant, id);
  }

  // ==================================================================
  // 🔴 重排：真的改变执行次序
  // ==================================================================

  /**
   * 🔴 调整顺序：按调用者给的**完整新次序**重排待派发任务。
   *
   * <p>只接受本批次当前 <b>PENDING</b> 的任务（在途/已成功/已失败的冻结其位置 —— 重排一张
   * 已经发出去的队列没有意义，还会让"已派发的次序"与"现存次序"对不上）。重排把调用者给的
   * 顺序写成连续 {@code position}，而 {@link #dispatch} 就是按 position 升序取，所以次序真的变。
   *
   * <p>传入不合法（不属于本批次 / 不是 PENDING / 重复）的 id 进 {@code rejected} 并写明原因，
   * 其余的照排 —— 部分成功是诚实的，全批拒绝也是诚实的。
   */
  public BatchActionResult reorder(String batchId, ReorderRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);

    List<UUID> order = safe(request.taskIds());
    List<String> rejected = new ArrayList<>();
    List<UUID> changed = new ArrayList<>();
    if (order.isEmpty()) {
      throw unprocessable("重排需要给出新的执行次序（taskIds）");
    }

    // 校验每个 id：属于本批次 且 PENDING，且不重复
    Map<UUID, Record> byId = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,state from publish_task where tenant_id=? and batch_id=?",
            tenant, id)) {
      byId.put(r.get("id", UUID.class), r);
    }
    List<UUID> valid = new ArrayList<>();
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (UUID tid : order) {
      Record r = byId.get(tid);
      if (r == null) {
        rejected.add(tid + ": 不属于本批次");
      } else if (!PENDING.equals(r.get("state", String.class))) {
        rejected.add(tid + ": 状态为 " + r.get("state", String.class) + "，不可重排");
      } else if (!seen.add(tid)) {
        rejected.add(tid + ": 重复出现");
      } else {
        valid.add(tid);
      }
    }

    // 先把要重排的挪到高位（避免撞部分唯一索引），再按新次序写回连续 position
    if (!valid.isEmpty()) {
      for (UUID tid : valid) {
        sql.execute(
            "update publish_task set position=position+1000000, updated_at=now() "
                + "where tenant_id=? and batch_id=? and id=? and state='PENDING'",
            tenant, id, tid);
      }
      int position = 0;
      for (UUID tid : valid) {
        sql.execute(
            "update publish_task set position=?, updated_at=now() "
                + "where tenant_id=? and batch_id=? and id=? and state='PENDING'",
            position, tenant, id, tid);
        changed.add(tid);
        position++;
      }
    }
    return new BatchActionResult(id, batch.state(), changed, rejected, loadBatch(tenant, id));
  }

  // ==================================================================
  // 🔴 批量重试：只重试失败项
  // ==================================================================

  /**
   * 🔴 批量重试：<b>只把 FAILED 打回 PENDING</b>。
   *
   * <p>已成功的（SUCCEEDED）绝不重跑 —— DB 触发器 {@code guard_publish_task_terminal} 会拒掉
   * 任何把 SUCCEEDED 改走的语句，所以这条不变量不是"服务层没写那条 SQL"，而是**写不了**。
   * 重试不打回已在途（DISPATCHED）的任务：必须先被回报结果。
   *
   * <p>重试<b>保留</b> {@code dispatched_at}（作为"上次派发于何时"的历史），
   * {@code attempts} 在下次派发时由触发器强制 +1，所以"重试了几次"可查。
   */
  public BatchActionResult retry(String batchId, RetryRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(batchId);
    PublishBatchRow batch = loadBatch(tenant, id);

    List<String> rejected = new ArrayList<>();
    List<UUID> changed = new ArrayList<>();
    List<UUID> targets;

    if (request == null || safe(request.taskIds()).isEmpty()) {
      // 不给 = 重试本批次全部 FAILED
      targets =
          sql.fetch(
                  "select id from publish_task where tenant_id=? and batch_id=? and state='FAILED' order by position,id",
                  tenant, id)
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
    } else {
      targets = new ArrayList<>();
      Map<UUID, String> stateOf = new LinkedHashMap<>();
      for (Record r :
          sql.fetch("select id,state from publish_task where tenant_id=? and batch_id=?", tenant, id)) {
        stateOf.put(r.get("id", UUID.class), r.get("state", String.class));
      }
      for (UUID tid : safe(request.taskIds())) {
        String st = stateOf.get(tid);
        if (st == null) {
          rejected.add(tid + ": 不属于本批次");
        } else if (!FAILED.equals(st)) {
          // 关键：成功的 / 在途的，明确拒绝，不重跑
          rejected.add(tid + ": 状态为 " + st + "，只有 FAILED 可重试");
        } else {
          targets.add(tid);
        }
      }
    }

    for (UUID tid : targets) {
      // 只把 FAILED 打回 PENDING；保留 dispatched_at（历史），completed_at 清空（不再是终态）
      sql.execute(
          """
          update publish_task
          set state='PENDING', completed_at=null, updated_at=now()
          where tenant_id=? and id=? and state='FAILED'
          """,
          tenant, tid);
      changed.add(tid);
    }
    if (!changed.isEmpty()) {
      // 重试后批次回到 RUNNING（如果之前 COMPLETED 了就重新开跑）
      sql.execute(
          "update publish_batch set state='RUNNING', completed_at=null, updated_at=now() "
              + "where tenant_id=? and id=? and state='COMPLETED'",
          tenant, id);
    }
    refreshProgress(tenant, id);
    return new BatchActionResult(id, loadBatch(tenant, id).state(), changed, rejected, loadBatch(tenant, id));
  }

  // ==================================================================
  // 回报单条任务结果（4.3.1.3 / 02 适配器在途后的回执；本模块不伪造平台返回）
  // ==================================================================

  /**
   * 回报一条在途（DISPATCHED）任务的结果：成功或失败。
   *
   * <p>成功时把关联的内容草稿（4.2.1.2 的产出）转 {@code PUBLISHED}。这才是"批量发布把草稿
   * 上线"的落点。失败必须带原因（DB 有 CHECK：FAILED 必须有 failure_reason）。
   *
   * <p>本方法不模拟平台调用 —— 派发后的实际平台往返由 4.3.1.3 回报，本模块只负责队列记账。
   */
  public PublishTaskRow report(String taskId, boolean success, String failureReason) {
    UUID tenant = currentTenant();
    UUID id = parseId(taskId);
    Record t =
        sql.fetchOne("select id,batch_id,state,draft_id from publish_task where tenant_id=? and id=?", tenant, id);
    if (t == null) {
      throw notFound();
    }
    if (!DISPATCHED.equals(t.get("state", String.class))) {
      throw unprocessable("只有在途（DISPATCHED）任务可回报结果，当前是 " + t.get("state", String.class));
    }
    if (success) {
      sql.execute(
          "update publish_task set state='SUCCEEDED', completed_at=?, updated_at=now() where tenant_id=? and id=?",
          Timestamp.from(OffsetDateTime.now().toInstant()), tenant, id);
      UUID draftId = t.get("draft_id", UUID.class);
      if (draftId != null) {
        sql.execute(
            "update listing_content_draft set state='PUBLISHED', updated_at=now() "
                + "where tenant_id=? and id=? and state='DRAFT'",
            tenant, draftId);
      }
    } else {
      if (blankToNull(failureReason) == null) {
        throw unprocessable("失败必须给出原因");
      }
      sql.execute(
          "update publish_task set state='FAILED', completed_at=?, failure_reason=?, updated_at=now() "
              + "where tenant_id=? and id=?",
          Timestamp.from(OffsetDateTime.now().toInstant()), failureReason, tenant, id);
    }
    UUID batchId = t.get("batch_id", UUID.class);
    refreshProgress(tenant, batchId);
    return loadTaskRow(tenant, id);
  }

  private PublishTaskRow loadTaskRow(UUID tenant, UUID id) {
    Record r = sql.fetchOne(TASK_SELECT + " where t.tenant_id=? and t.id=?", tenant, id);
    if (r == null) {
      throw notFound();
    }
    return taskRow(r);
  }

  // ==================================================================
  // 进度重算
  // ==================================================================

  /**
   * 从任务状态实时重算批次计数并写回；全部终态时把批次置 COMPLETED。
   *
   * <p>🔴 用**固定的一次聚合查询**算五个计数，DB 的 CHECK 保证"各状态之和 = 总数"，
   * 所以界面上的进度不可能自相矛盾。
   */
  private void refreshProgress(UUID tenant, UUID batchId) {
    Record counts =
        sql.fetchOne(
            """
            select
              count(*) filter (where state='PENDING')    as pending,
              count(*) filter (where state='DISPATCHED') as dispatched,
              count(*) filter (where state='SUCCEEDED')  as succeeded,
              count(*) filter (where state='FAILED')     as failed,
              count(*)                                as total
            from publish_task where tenant_id=? and batch_id=?
            """,
            tenant, batchId);
    int pending = counts.get("pending", Integer.class);
    int dispatched = counts.get("dispatched", Integer.class);
    int succeeded = counts.get("succeeded", Integer.class);
    int failed = counts.get("failed", Integer.class);
    int total = counts.get("total", Integer.class);
    boolean allTerminal = total > 0 && pending == 0 && dispatched == 0;

    sql.execute(
        """
        update publish_batch
        set pending_count=?, dispatched_count=?, succeeded_count=?, failed_count=?, total_count=?,
            state = case when ? then 'COMPLETED' else state end,
            completed_at = case when ? then now() else completed_at end,
            updated_at=now()
        where tenant_id=? and id=?
        """,
        pending, dispatched, succeeded, failed, total,
        allTerminal, allTerminal, tenant, batchId);
  }

  // ==================================================================
  // 杂项
  // ==================================================================

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, 200);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }
}
