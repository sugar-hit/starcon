package com.starcon.channel;

import static com.starcon.channel.ListingBulkCollectModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.4.1.2 批量/插件采集。
 *
 * <p>蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。<b>抓取必须有频率限制与去重</b>
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * <p>🔴 <b>复用 4.4.1.1 的采集模型，不另起一套</b>：本类产出的每一条结果都写进
 * {@code listing_collect_task}（一次抓取）+ {@code listing_collect_draft}（采集草稿），
 * 下游 4.4.1.3 采集箱认领的就是那张草稿。归一化用 4.4.1.1 的 {@link ListingCollectUrl}，
 * 商品详情页解析用 4.4.1.1 的 {@link ListingCollectHtmlParser}，白名单用 4.4.1.1 的
 * {@code listing_collect_source} 码表 —— **本类一行代码都不重复实现**已有的判定。
 *
 * <p>🔴 <b>三条纪律在本类里各有一层物理落点，不是靠注释提醒：</b>
 *
 * <ol>
 *   <li><b>限频</b>：三层（并发数 / 任务启动间隔 / 同 host 抓取间隔），每一层都有<b>明确的拒绝路径</b>
 *       （429 + 可读原因），不是排队等死。运行中被打断的任务停在 {@code RATE_LIMITED}，
 *       <b>已采到的结果全部保留</b>，点「继续」接着采。
 *   <li><b>去重</b>：去重键 = 4.4.1.1 的规范化 URL，判据 = 该租户该 URL 是否已有
 *       {@code SUCCEEDED} 的采集任务。命中 ⇒ 结果清单记 {@code SKIPPED_DUPLICATE}，
 *       <b>不发出第二次 HTTP 请求</b>。三种入口（链接采集 / 批量 / 插件）共享同一个去重事实。
 *   <li><b>只产生草稿</b>：本类<b>只</b>写 {@code listing_collect_task} /
 *       {@code listing_collect_draft} / {@code listing_bulk_collect_task} /
 *       {@code listing_bulk_collect_result} 四张表，<b>没有一条</b> insert/update 打向
 *       {@code listing} / {@code listing_variant} / 任何 catalog 的表。
 * </ol>
 */
@Service
public class ListingBulkCollectService {

  /** 抓取超时：外部站点慢是常态，但也不能无限等。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** 响应体上限：列表页可能是几十 MB，截断保护服务自身。 */
  private static final int MAX_CONTENT_LENGTH = 4 * 1024 * 1024;

  private static final int MAX_PAGE_SIZE = 200;

  /** 单次运行最多重定向几跳。 */
  private static final int MAX_REDIRECTS = 5;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final RestClient http;

  public ListingBulkCollectService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(READ_TIMEOUT);
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前时刻（截断到微秒，与 PostgreSQL 的 timestamptz 精度一致）。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException tooMany(String message) {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw notFound();
    }
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 当前用户的部门（数据范围用；没有部门就是 null，与 V1 shop 同一纪律）。 */
  private UUID currentDepartment() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.department',true),'')");
    if (raw == null) return null;
    String value = String.valueOf(raw);
    return value.isBlank() ? null : UUID.fromString(value);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==================================================================
  // 白名单与配置（复用 4.4.1.1 的码表 + 本模块的两张全局码表）
  // ==================================================================

  /** 白名单里命中的那一行；没命中返回 null。 */
  private BulkCollectSourceRow matchSource(String host) {
    List<BulkCollectSourceRow> sorted = new ArrayList<>(listSources());
    sorted.sort((a, b) -> b.host().length() - a.host().length());
    for (BulkCollectSourceRow source : sorted) {
      if (ListingCollectUrl.hostMatches(host, source.host())) return source;
    }
    return null;
  }

  /** 白名单全部行（启用的在前，按 position 排），并带上批量采集参数。 */
  public List<BulkCollectSourceRow> listSources() {
    List<BulkCollectSourceRow> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select s.code, s.name, s.host, s.kind, s.min_interval_seconds,
                   coalesce(b.bulk_min_interval_seconds, 0) as bulk_min_interval_seconds,
                   coalesce(b.search_url_template, '') as search_url_template,
                   coalesce(b.category_page_param, 'page') as category_page_param,
                   coalesce(b.page_origin, 1) as page_origin
            from listing_collect_source s
            left join listing_bulk_collect_search b on b.source_code=s.code
            where s.enabled
            order by s.position, s.code
            """)) {
      out.add(
          new BulkCollectSourceRow(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("host", String.class),
              row.get("kind", String.class),
              row.get("min_interval_seconds", Integer.class),
              row.get("bulk_min_interval_seconds", Integer.class),
              row.get("search_url_template", String.class),
              row.get("category_page_param", String.class),
              row.get("page_origin", Integer.class)));
    }
    return out;
  }

  /** 按编码取白名单行；没命中返回 null。 */
  private BulkCollectSourceRow sourceByCode(String code) {
    String normalized = text(code).toUpperCase(Locale.ROOT);
    for (BulkCollectSourceRow source : listSources()) {
      if (source.code().equalsIgnoreCase(normalized)) return source;
    }
    return null;
  }

  /** 当前限频策略。 */
  public BulkCollectPolicyRow policy() {
    Record row =
        sql.fetchOne(
            "select code, max_concurrent_tasks, min_task_start_interval_seconds, max_pages_per_task,"
                + " max_items_per_task, max_items_per_report, max_task_seconds"
                + " from listing_bulk_collect_policy where enabled order by code limit 1");
    if (row == null) {
      throw new IllegalStateException("批量采集限频策略缺失（listing_bulk_collect_policy 没有启用行）");
    }
    return new BulkCollectPolicyRow(
        row.get("code", String.class),
        row.get("max_concurrent_tasks", Integer.class),
        row.get("min_task_start_interval_seconds", Integer.class),
        row.get("max_pages_per_task", Integer.class),
        row.get("max_items_per_task", Integer.class),
        row.get("max_items_per_report", Integer.class),
        row.get("max_task_seconds", Integer.class));
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>未交付段一律 {@code available=false} + ownerModule + impact：采集箱（4.4.1.3）、
   * 浏览器扩展本体（不在本仓）、认领为 SPU（3.1.1.1）、媒体库（03）、我方售价（05）、内部可售量（07）。
   */
  public ListingBulkCollectCapabilities capabilities() {
    return new ListingBulkCollectCapabilities(
        List.of(
            new BulkCollectCapabilityDimension(
                "category_page_collect",
                "🔴 分类页批量采集（分类页 URL + 页数范围）",
                true,
                "listing_bulk_collect_task + 4.4.1.1 的 listing_collect_draft",
                "",
                "缺了它就只能一条一条粘链接 —— 大批量找货场景直接不可用"),
            new BulkCollectCapabilityDimension(
                "keyword_collect",
                "🔴 关键词搜索结果批量采集（关键词 + 页数范围）",
                true,
                "listing_bulk_collect_search 的平台搜索模板 + listing_bulk_collect_task",
                "",
                "缺了它就找不到「搜某个词看看有什么货」，只能自己一个个点进分类页"),
            new BulkCollectCapabilityDimension(
                "plugin_report",
                "🔴 浏览器插件上报入口（插件在用户浏览时抓当前页商品）",
                true,
                "listing_bulk_collect_task（mode=PLUGIN）+ listing_collect_draft",
                "",
                "缺了它用户浏览时看到的好货就没法直接进采集箱"),
            new BulkCollectCapabilityDimension(
                "bulk_result_list",
                "🔴 结果清单（真分页 + 状态筛选）",
                true,
                "listing_bulk_collect_result",
                "",
                "没有清单就只剩一个计数，运营答不出「这批到底抓到了什么」"),
            new BulkCollectCapabilityDimension(
                "rate_limit",
                "🔴 频率限制（并发数 / 任务启动间隔 / 同 host 抓取间隔，超限明确拒绝）",
                true,
                "listing_bulk_collect_policy + listing_bulk_collect_search.bulk_min_interval_seconds"
                    + " + listing_collect_task 的抓取历史",
                "",
                "缺了它批量采集会打爆外部站点 —— 抓取先于对方限流被封 IP"),
            new BulkCollectCapabilityDimension(
                "dedup",
                "🔴 去重（同 URL 不重复抓、已存在的商品跳过）",
                true,
                "listing_collect_task.normalized_url（部分唯一索引）+ listing_collect_draft",
                "",
                "缺了它同一件商品会被反复抓，采集箱里塞满重复草稿"),
            new BulkCollectCapabilityDimension(
                "collection_box",
                "采集箱（暂存分组 / 批量认领 / 批量删除 / 批量打标）",
                false,
                "—",
                "4.4.1.3 采集箱",
                "本模块只负责**产出**采集草稿；暂存与批量认领是 4.4.1.3 的范围"),
            new BulkCollectCapabilityDimension(
                "browser_extension",
                "浏览器扩展本体（插件的 UI 与采集脚本）",
                false,
                "—",
                "浏览器插件客户端（不在本仓）",
                "本模块只交付插件的**上报入口**（服务端接收端）：插件怎么在页面上取数、"
                    + "怎么装、怎么升级，都不在本仓范围。服务端按上报的 URL 与数据落成采集草稿"),
            new BulkCollectCapabilityDimension(
                "claim_to_spu",
                "认领为内部商品（生成 SPU / SKU 草稿并预填内容）",
                false,
                "—",
                "3.1.1.1 商品建档（经 4.4.1.3 采集箱）",
                "🔴 **采集只产生草稿**。进入正式商品库必须走 03 的建档流程并补全属性与合规信息"),
            new BulkCollectCapabilityDimension(
                "media_library",
                "🔴 图片下载入媒体库",
                false,
                "—",
                "03 商品主数据（catalog）媒体库",
                "🔴 媒体库归 03 catalog，AGENTS.md **禁止跨服务数据库访问** —— "
                    + "本模块**只记录图片 URL**，不下载二进制、不写媒体表"),
            new BulkCollectCapabilityDimension(
                "price",
                "我方售价与币种",
                false,
                "—",
                "05 定价与价目系统",
                "卷 05 整卷未交付 ⇒ 采集草稿**不产出我方售价**。"
                    + "清单上的 priceAmount 是**外部页面原文标价**，不是我们的定价"),
            new BulkCollectCapabilityDimension(
                "internal_stock",
                "内部可售量",
                false,
                "—",
                "07 库存系统",
                "卷 07 未交付 ⇒ 采集草稿不带任何库存事实")),
        listSources(),
        policy(),
        MODES,
        STATES,
        RESULT_STATES,
        true,
        "🔴 批量采集**只产生草稿**：本模块不建 SPU / SKU、不写 listing、不改任何商品主数据。"
            + "草稿要进正式商品库，必须走 4.4.1.3 采集箱 → 3.1.1.1 商品建档，并补全属性与合规信息。",
        true,
        "🔴 同一个商品不会被抓两遍：规范化 URL（去 utm 之类跟踪参数、归一 host、排序查询参数）"
            + "后若该租户已采过，直接跳过并记一条「已存在」的结果，**不会再次请求来源站点**。"
            + "链接采集、批量采集、插件上报三种入口共享同一个去重事实。",
        true,
        "🔴 批量抓取有频率限制：同一租户同时可进行的批量任务数、两次发起任务的最小间隔、"
            + "以及同一来源的最小抓取间隔都有限制，超限会**明确拒绝**并告诉你还要等多少秒。",
        "上游 = 4.4.1.1 链接采集（**复用**它的白名单、URL 规范化、商品页解析与采集任务/草稿结构）；"
            + "下游 = 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。"
            + "03 商品主数据只经下游流程引用，**本模块不跨服务读表、不跨服务写表**。");
  }

  // ==================================================================
  // 读取
  // ==================================================================

  /** 批量采集任务列表（真分页 + 模式/状态/来源筛选 + 时间倒序）。 */
  public BulkCollectPage listTasks(String mode, String state, String sourceCode, int page, int size) {
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    String normalizedMode = blankToNull(mode);
    String normalizedState = blankToNull(state);
    String normalizedSource = blankToNull(sourceCode);

    if (normalizedMode != null && !MODES.contains(normalizedMode.toUpperCase(Locale.ROOT))) {
      throw unprocessable("未知的批量采集模式：" + mode + "。可选：" + String.join(" / ", MODES));
    }
    if (normalizedState != null && !STATES.contains(normalizedState.toUpperCase(Locale.ROOT))) {
      throw unprocessable("未知的批量采集状态：" + state + "。可选：" + String.join(" / ", STATES));
    }

    List<Object> filterBinds = new ArrayList<>();
    String where =
        " where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid";
    if (normalizedMode != null) {
      where += " and t.mode=?";
      filterBinds.add(normalizedMode.toUpperCase(Locale.ROOT));
    }
    if (normalizedState != null) {
      where += " and t.state=?";
      filterBinds.add(normalizedState.toUpperCase(Locale.ROOT));
    }
    if (normalizedSource != null) {
      where += " and s.code=?";
      filterBinds.add(normalizedSource);
    }

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_bulk_collect_task t"
                        + " join listing_collect_source s on s.id=t.source_id"
                        + where,
                    filterBinds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(filterBinds);
    pageBinds.add(limit);
    pageBinds.add(offset);

    List<BulkCollectTaskRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select t.id, t.mode, t.category_url, t.keyword, t.page_from, t.page_to, t.state,
                   t.error_message, t.total_pages, t.discovered_count, t.fetched_count,
                   t.skipped_duplicate_count, t.failed_count, t.skipped_rate_limited_count,
                   t.next_page, t.started_at, t.finished_at, t.created_at,
                   s.code as source_code, s.name as source_name
            from listing_bulk_collect_task t
            join listing_collect_source s on s.id=t.source_id
            """
                + where
                + " order by t.created_at desc, t.id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(taskRow(row));
    }
    return new BulkCollectPage(items, total, Math.max(0, page), limit);
  }

  /** 单个批量采集任务详情。 */
  public BulkCollectTaskRow detail(String id) {
    Record row = fetchTask(parseId(id));
    if (row == null) throw notFound();
    return taskRow(row);
  }

  /** 结果清单（真分页 + 状态筛选）。 */
  public BulkCollectResultPage listResults(String id, String state, int page, int size) {
    UUID taskId = parseId(id);
    if (fetchTask(taskId) == null) throw notFound();
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    String normalizedState = blankToNull(state);
    if (normalizedState != null && !RESULT_STATES.contains(normalizedState.toUpperCase(Locale.ROOT))) {
      throw unprocessable("未知的结果状态：" + state + "。可选：" + String.join(" / ", RESULT_STATES));
    }

    List<Object> filterBinds = new ArrayList<>();
    filterBinds.add(taskId);
    String where =
        " where r.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
            + " and r.bulk_task_id=?";
    if (normalizedState != null) {
      where += " and r.state=?";
      filterBinds.add(normalizedState.toUpperCase(Locale.ROOT));
    }

    long total =
        ((Number) sql.fetchValue("select count(*) from listing_bulk_collect_result r" + where, filterBinds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(filterBinds);
    pageBinds.add(limit);
    pageBinds.add(offset);

    List<BulkCollectResultRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select r.id, r.bulk_task_id, r.origin, r.page_no, r.item_index, r.source_url,
                   r.normalized_url, r.title, r.image_url, r.price_amount, r.price_currency,
                   r.state, r.skip_reason, r.error_message, r.collect_task_id, r.draft_id,
                   r.captured_at, r.created_at
            from listing_bulk_collect_result r
            """
                + where
                + " order by r.id limit ? offset ?",
            pageBinds.toArray())) {
      items.add(resultRow(row));
    }
    return new BulkCollectResultPage(items, total, Math.max(0, page), limit);
  }

  // ==================================================================
  // 发起批量采集
  // ==================================================================

  /**
   * 🔴 发起一次批量采集（**只建任务，不抓**）。
   *
   * <p>把「建任务」与「执行」分成两步，是为了让限频的拒绝发生在**发起时**（明确的 429 +
   * 可读原因），而不是让调用方在一次长请求里等半天最后拿到一句「太频繁了」。
   */
  public BulkCollectTaskRow start(StartBulkCollectRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    BulkCollectPolicyRow policy = policy();

    String mode = text(request.mode()).toUpperCase(Locale.ROOT);
    if (!MODES.contains(mode)) {
      throw badRequest(
          "🔴 批量采集模式只能是 " + String.join(" / ", MODES) + "，收到：" + request.mode());
    }
    if ("PLUGIN".equals(mode)) {
      throw badRequest(
          "🔴 PLUGIN 模式不是通过「发起批量采集」进入的 —— 浏览器插件请走插件上报入口。");
    }

    BulkCollectSourceRow source = sourceByCode(request.sourceCode());
    if (source == null) {
      throw badRequest(
          "🔴 来源平台 "
              + request.sourceCode()
              + " 不在平台白名单内，只允许这些来源："
              + String.join("、", listSources().stream().map(BulkCollectSourceRow::code).toList()));
    }

    // ---- 输入校验（模式相关）
    String categoryUrl = text(request.categoryUrl());
    String keyword = text(request.keyword());
    String template = text(request.pageUrlTemplate());
    int pageFrom = request.pageFrom() == null ? 1 : request.pageFrom();
    int pageTo = request.pageTo() == null ? pageFrom : request.pageTo();

    if (pageFrom < 1 || pageTo < pageFrom) {
      throw badRequest("🔴 页码范围不合法：起始页 " + pageFrom + "、结束页 " + pageTo + "（起始页从 1 起，且不能大于结束页）。");
    }
    if (pageTo - pageFrom + 1 > policy.maxPagesPerTask()) {
      throw badRequest(
          "🔴 单个批量采集任务最多采 " + policy.maxPagesPerTask() + " 页，本次请求了 "
              + (pageTo - pageFrom + 1) + " 页。请缩小页码范围，或分几次采。");
    }

    if ("CATEGORY_PAGE".equals(mode)) {
      if (categoryUrl.isEmpty()) {
        throw badRequest("🔴 分类页批量采集必须给出分类页 URL。");
      }
      String host = normalizeOrReject(categoryUrl).host();
      if (matchSource(host) == null) {
        throw badRequest(
            "🔴 分类页域名 " + host + " 不在平台白名单内，只允许采集这些来源："
                + String.join("、", listSources().stream().map(BulkCollectSourceRow::host).toList())
                + "。如需新增来源，请运维在平台白名单（listing_collect_source）中登记。");
      }
    } else {
      if (keyword.isEmpty()) {
        throw badRequest("🔴 关键词批量采集必须给出关键词。");
      }
      if (template.isEmpty()) {
        template = source.searchUrlTemplate();
      }
      if (template.isEmpty()) {
        throw badRequest(
            "🔴 平台 "
                + source.code()
                + " 没有配置关键词搜索模板，无法按关键词批量采集。"
                + "请在请求里带上 pageUrlTemplate，或让运维在平台搜索模板（listing_bulk_collect_search）中登记。");
      }
      if (!template.contains("{page}")) {
        throw badRequest(
            "🔴 分页 URL 模板里必须有 {page} 占位符，收到：" + template);
      }
    }
    if (!template.isEmpty() && !template.contains("{page}")) {
      throw badRequest("🔴 分页 URL 模板里必须有 {page} 占位符，收到：" + template);
    }

    // ---- 🔴 限频 ①：同一租户同时可进行的批量任务数
    long running =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_bulk_collect_task"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                        + " and state in ('PENDING','RUNNING')"))
            .longValue();
    if (running >= policy.maxConcurrentTasks()) {
      throw tooMany(
          "🔴 你的租户同时已有 "
              + running
              + " 个批量采集任务在跑（上限 "
              + policy.maxConcurrentTasks()
              + " 个）。等当前任务跑完再发起新的，别把外部站点打爆。");
    }

    // ---- 🔴 限频 ②：同一租户两次发起采集任务的最小间隔
    long wait = secondsUntilNextTaskAllowed(policy.minTaskStartIntervalSeconds());
    if (wait > 0) {
      throw tooMany(
          "🔴 发起得太密了：同一租户两次发起采集任务至少间隔 "
              + policy.minTaskStartIntervalSeconds()
              + " 秒，请再等 "
              + wait
              + " 秒。");
    }

    // ---- 🔴 限频 ③：同 host 的最小抓取间隔
    if (!"KEYWORD".equals(mode)) {
      long hostWait = secondsUntilHostAllowed(source.host(), source.bulkMinIntervalSeconds());
      if (hostWait > 0) {
        throw tooMany(
            "🔴 抓取过于频繁：来源 "
                + source.name()
                + "（"
                + source.host()
                + "）的批量采集最小间隔是 "
                + source.bulkMinIntervalSeconds()
                + " 秒，请再等 "
                + hostWait
                + " 秒。");
      }
    }

    UUID taskId = Ids.next();
    int totalPages = pageTo - pageFrom + 1;
    sql.execute(
        """
        insert into listing_bulk_collect_task
          (id,tenant_id,source_id,mode,category_url,keyword,page_from,page_to,page_url_template,
           state,error_message,total_pages,discovered_count,fetched_count,skipped_duplicate_count,
           failed_count,skipped_rate_limited_count,next_page,actor_id,owner_id,department_id,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,'PENDING','',?,0,0,0,0,0,?,?,?,?,now(),now())
        """,
        taskId,
        tenant,
        sourceRowId(source),
        mode,
        categoryUrl,
        keyword,
        pageFrom,
        pageTo,
        template,
        totalPages,
        pageFrom,
        actorId,
        actorId,
        currentDepartment());
    return detail(taskId.toString());
  }

  // ==================================================================
  // 执行批量采集
  // ==================================================================

  /**
   * 🔴 执行一次批量采集（**真的发 HTTP 请求**）。
   *
   * <p>执行模型刻意做成「<b>一次运行 = 一段时间预算</b>」：
   *
   * <ol>
   *   <li>从断点 {@code next_page} 开始，一页一页翻（分类页 / 搜索结果页）。
   *   <li>每翻一页就把页面上发现的商品链接逐条处理：先查去重，再抓详情页，最后落成
   *       {@code listing_collect_draft}。
   *   <li>每一次抓取都要满足「同 host 最小间隔」；等待时间超出本次运行的时间预算
   *       （{@code max_task_seconds}）时，任务停在 {@code RATE_LIMITED}，
   *       <b>已采到的结果全部保留</b>，用户点「继续」接着采。
   * </ol>
   *
   * <p>🔴 这不是「排队等死」：被限频打断时任务会**明确告诉你**还要等多少秒、停在哪一页。
   */
  public BulkCollectTaskRow run(String id, UUID actorId) {
    UUID tenant = currentTenant();
    UUID taskId = parseId(id);
    Record task = fetchTask(taskId);
    if (task == null) throw notFound();

    String state = task.get("state", String.class);
    if ("SUCCEEDED".equals(state)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "🔴 这个批量采集任务已经采完了，不能再运行。要重来请发起一个新任务。");
    }

    BulkCollectPolicyRow policy = policy();
    String mode = task.get("mode", String.class);
    BulkCollectSourceRow source =
        sourceByCode(task.get("source_code", String.class));
    if (source == null) {
      throw badRequest("🔴 任务来源平台已不在白名单内，无法继续采。");
    }

    int pageFrom = task.get("page_from", Integer.class);
    int pageTo = task.get("page_to", Integer.class);
    int nextPage = task.get("next_page", Integer.class);
    String categoryUrl = task.get("category_url", String.class);
    String keyword = task.get("keyword", String.class);
    String template = task.get("page_url_template", String.class);

    int discovered = task.get("discovered_count", Integer.class);
    int fetched = task.get("fetched_count", Integer.class);
    int skippedDuplicate = task.get("skipped_duplicate_count", Integer.class);
    int failed = task.get("failed_count", Integer.class);
    int skippedRateLimited = task.get("skipped_rate_limited_count", Integer.class);

    OffsetDateTime startedAt = task.get("started_at", OffsetDateTime.class);
    if (startedAt == null) startedAt = now();
    long deadlineNanos = System.nanoTime() + policy.maxTaskSeconds() * 1_000_000_000L;

    String errorMessage = "";
    String finalState = "SUCCEEDED";
    boolean stopped = false;
    // 断点：下一次运行从哪一页接着采
    int cursor = nextPage;
    int resumePage = nextPage;

    // 标记 RUNNING（可重入：崩在 RUNNING 的任务也能接着跑）
    updateTaskState(taskId, "RUNNING", "", cursor, discovered, fetched, skippedDuplicate, failed, skippedRateLimited, null, null);

    for (int pageNo = nextPage; pageNo <= pageTo && !stopped; pageNo++) {
      if (System.nanoTime() > deadlineNanos) {
        finalState = "RATE_LIMITED";
        errorMessage =
            "🔴 这次运行的时间预算（"
                + policy.maxTaskSeconds()
                + " 秒）用完了，已采到的 "
                + fetched
                + " 条结果全部保留。点「继续采集」从第 "
                + pageNo
                + " 页接着采。";
        skippedRateLimited++;
        recordResult(taskId, "BULK", pageNo, 0, categoryUrl, categoryUrl, "", "", null, null,
            "SKIPPED_RATE_LIMITED",
            "🔴 本次运行的时间预算用完，需要下次接着采。", "", null, null, now());
        resumePage = pageNo;
        break;
      }

      String pageUrl = buildPageUrl(mode, source, categoryUrl, keyword, template, pageNo);
      if (pageUrl == null) {
        finalState = "FAILED";
        errorMessage =
            "🔴 拼不出第 " + pageNo + " 页的地址（分类页模式缺少翻页参数，或关键词模式没有搜索模板）。";
        break;
      }

      // ---- 翻页请求本身也要过「同 host 最小间隔」
      long pageWait = secondsUntilHostAllowed(source.host(), source.bulkMinIntervalSeconds());
      if (pageWait > 0) {
        long remainingSeconds = (deadlineNanos - System.nanoTime()) / 1_000_000_000L;
        if (pageWait > remainingSeconds) {
          finalState = "RATE_LIMITED";
          errorMessage =
              "🔴 抓取过于频繁：来源 "
                  + source.name()
                  + "（"
                  + source.host()
                  + "）的批量采集最小间隔是 "
                  + source.bulkMinIntervalSeconds()
                  + " 秒，还需要等 "
                  + pageWait
                  + " 秒才能翻第 "
                  + pageNo
                  + " 页（本次运行只剩 "
                  + Math.max(0, remainingSeconds)
                  + " 秒预算）。已采到的 "
                  + fetched
                  + " 条结果全部保留，点「继续采集」接着采。";
          skippedRateLimited++;
          recordResult(taskId, "BULK", pageNo, 0, pageUrl, pageUrl, "", "", null, null,
              "SKIPPED_RATE_LIMITED", errorMessage, "", null, null, now());
          resumePage = pageNo;
          break;
        }
        sleepSeconds(pageWait);
      }

      FetchOutcome pageOutcome = fetch(pageUrl);
      markGrab(taskId, source.host());
      cursor = pageNo + 1;
      if (pageOutcome.error() != null) {
        errorMessage = "🔴 第 " + pageNo + " 页抓取失败：" + pageOutcome.error();
        failed++;
        recordResult(taskId, "BULK", pageNo, 0, pageUrl, pageUrl, "", "", null, null,
            "FAILED", "", errorMessage, null, null, now());
        // 一页抓不到不该毁掉整批：继续翻下一页
        if ("SUCCEEDED".equals(finalState)) finalState = "PARTIAL";
        updateTaskState(taskId, "RUNNING", errorMessage, cursor, discovered, fetched,
            skippedDuplicate, failed, skippedRateLimited, null, null);
        continue;
      }

      ListingBulkCollectHtmlParser.ParsedListing parsed =
          ListingBulkCollectHtmlParser.parse(pageOutcome.body(), pageOutcome.finalUrl());
      List<ListingBulkCollectHtmlParser.ListingItem> items = parsed.items();
      discovered += items.size();

      int itemIndex = 0;
      for (ListingBulkCollectHtmlParser.ListingItem item : items) {
        if (fetched + skippedDuplicate + failed + skippedRateLimited >= policy.maxItemsPerTask()) {
          errorMessage =
              "🔴 已达到单任务上限（"
                  + policy.maxItemsPerTask()
                  + " 条），本批剩下的条目没有再抓。缩小页码范围或分几次采可以采全。";
          if ("SUCCEEDED".equals(finalState)) finalState = "PARTIAL";
          stopped = true;
          break;
        }
        itemIndex++;

        ListingCollectUrl.Normalized normalized;
        try {
          normalized = ListingCollectUrl.normalize(item.url());
        } catch (IllegalArgumentException e) {
          failed++;
          recordResult(taskId, "BULK", pageNo, itemIndex, item.url(), item.url(), item.title(),
              item.imageUrl(), item.priceAmount(), item.priceCurrency(), "FAILED", "",
              "🔴 商品链接不合法：" + e.getMessage(), null, null, now());
          continue;
        }

        // ---- 🔴 去重：同 URL 不重复抓、已存在的商品跳过
        Record existing = findExistingCollect(normalized.normalized());
        if (existing != null) {
          skippedDuplicate++;
          recordResult(taskId, "BULK", pageNo, itemIndex, item.url(), normalized.normalized(),
              item.title(), item.imageUrl(), item.priceAmount(), item.priceCurrency(),
              "SKIPPED_DUPLICATE",
              "🔴 这个商品已经采过了（规范化后是同一个页面），已跳过，**没有再次请求来源站点**。",
              "", existing.get("task_id", UUID.class), existing.get("draft_id", UUID.class), now());
          continue;
        }

        // ---- 详情页抓取也要过同 host 最小间隔
        long itemWait =
            secondsUntilHostAllowed(normalized.host(), source.bulkMinIntervalSeconds());
        if (itemWait > 0) {
          long remainingSeconds = (deadlineNanos - System.nanoTime()) / 1_000_000_000L;
          if (itemWait > remainingSeconds) {
            finalState = "RATE_LIMITED";
            errorMessage =
                "🔴 抓取过于频繁：来源 "
                    + source.name()
                    + "（"
                    + normalized.host()
                    + "）的批量采集最小间隔是 "
                    + source.bulkMinIntervalSeconds()
                    + " 秒，还需要等 "
                    + itemWait
                    + " 秒（本次运行只剩 "
                    + Math.max(0, remainingSeconds)
                    + " 秒预算）。已采到的 "
                    + fetched
                    + " 条结果全部保留，点「继续采集」接着采。";
            skippedRateLimited++;
            recordResult(taskId, "BULK", pageNo, itemIndex, item.url(), normalized.normalized(),
                item.title(), item.imageUrl(), item.priceAmount(), item.priceCurrency(),
                "SKIPPED_RATE_LIMITED", errorMessage, "", null, null, now());
            resumePage = pageNo;
            stopped = true;
            break;
          }
          sleepSeconds(itemWait);
        }

        GrabOutcome outcome = grabDetail(normalized, source, actorId);
        if (outcome.error() != null) {
          failed++;
          recordResult(taskId, "BULK", pageNo, itemIndex, item.url(), normalized.normalized(),
              item.title(), item.imageUrl(), item.priceAmount(), item.priceCurrency(), "FAILED",
              "", outcome.error(), outcome.collectTaskId(), outcome.draftId(), now());
          if ("SUCCEEDED".equals(finalState)) finalState = "PARTIAL";
          continue;
        }
        fetched++;
        recordResult(taskId, "BULK", pageNo, itemIndex, item.url(), normalized.normalized(),
            outcome.title(), outcome.imageUrl(), outcome.priceAmount(), outcome.priceCurrency(),
            "FETCHED", "", "", outcome.collectTaskId(), outcome.draftId(), outcome.capturedAt());
      }

      updateTaskState(taskId, "RUNNING", errorMessage, cursor, discovered, fetched,
          skippedDuplicate, failed, skippedRateLimited, null, null);
    }

    OffsetDateTime finishedAt = now();
    if ("RATE_LIMITED".equals(finalState) && errorMessage.isEmpty()) {
      errorMessage = "🔴 被限频打断，已采到的结果全部保留，点「继续采集」接着采。";
    }
    if ("SUCCEEDED".equals(finalState) && failed > 0) {
      finalState = "PARTIAL";
    }
    if ("SUCCEEDED".equals(finalState) && fetched == 0 && discovered == 0) {
      // 一个商品都没发现：多半是页面结构变了（不是商品列表页）—— 说清楚，别装作采完了
      finalState = "FAILED";
      if (errorMessage.isEmpty()) {
        errorMessage =
            "🔴 这批页面里没有解析出任何商品链接 —— 页面结构可能变了，或它根本不是商品列表页。";
      }
    }
    int nextCursor = "RATE_LIMITED".equals(finalState) ? resumePage : cursor;
    updateTaskState(taskId, finalState, errorMessage, nextCursor,
        discovered, fetched, skippedDuplicate, failed, skippedRateLimited, startedAt, finishedAt);
    return detail(taskId.toString());
  }

  // ==================================================================
  // 浏览器插件上报
  // ==================================================================

  /**
   * 🔴 浏览器插件上报当前页商品。
   *
   * <p>🔴 <b>服务端不会再去抓一遍这些商品页</b> —— 插件已经在用户的浏览器里把数据取到了，
   * 再抓一遍等于把外部站点的压力翻倍（而这正是蓝图要避免的）。服务端只做三件事：
   * <b>白名单校验</b>（插件不能把任意 URL 塞进采集箱）、<b>去重</b>、<b>落成采集草稿</b>。
   *
   * <p>插件上报同样受<b>任务启动间隔</b>限频（插件连续翻页时会被明确拒绝并告知还要等多久），
   * 单次上报的商品条数受 {@code max_items_per_report} 限制。
   */
  public PluginReportResult reportPlugin(PluginReportRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    BulkCollectPolicyRow policy = policy();

    if (request.items() == null || request.items().isEmpty()) {
      throw badRequest("🔴 插件上报里没有商品。");
    }
    if (request.items().size() > policy.maxItemsPerReport()) {
      throw unprocessable(
          "🔴 一次插件上报最多带 "
              + policy.maxItemsPerReport()
              + " 条商品，这次带了 "
              + request.items().size()
              + " 条。请分批上报。");
    }

    // ---- 白名单：页面 URL 与每个商品 URL 都要在白名单内
    String pageHost = normalizeOrReject(request.pageUrl()).host();
    BulkCollectSourceRow source = matchSource(pageHost);
    if (source == null) {
      throw badRequest(
          "🔴 插件所在页面的域名 "
              + pageHost
              + " 不在平台白名单内，只允许采集这些来源："
              + String.join("、", listSources().stream().map(BulkCollectSourceRow::host).toList()));
    }

    // ---- 🔴 限频 ②：任务启动间隔（插件连着翻页时会被明确拒绝）
    long wait = secondsUntilNextTaskAllowed(policy.minTaskStartIntervalSeconds());
    if (wait > 0) {
      throw tooMany(
          "🔴 上报得太密了：同一租户两次发起采集至少间隔 "
              + policy.minTaskStartIntervalSeconds()
              + " 秒，请再等 "
              + wait
              + " 秒。");
    }

    // ---- 建任务（插件上报是一次性完成的 ⇒ 直接落终态）
    UUID taskId = Ids.next();
    OffsetDateTime reportedAt = now();
    sql.execute(
        """
        insert into listing_bulk_collect_task
          (id,tenant_id,source_id,mode,category_url,keyword,page_from,page_to,page_url_template,
           state,error_message,total_pages,discovered_count,fetched_count,skipped_duplicate_count,
           failed_count,skipped_rate_limited_count,next_page,actor_id,owner_id,department_id,
           started_at,finished_at,created_at,updated_at)
        values (?,?,?,'PLUGIN',?,'',1,1,'','SUCCEEDED','',1,?,0,0,0,0,2,?,?,?,?::timestamptz,?::timestamptz,now(),now())
        """,
        taskId,
        tenant,
        sourceRowId(source),
        text(request.pageUrl()),
        request.items().size(),
        actorId,
        actorId,
        currentDepartment(),
        reportedAt,
        reportedAt);

    int fetched = 0;
    int skippedDuplicate = 0;
    int rejected = 0;
    int itemIndex = 0;

    for (PluginReportItem item : request.items()) {
      itemIndex++;
      String rawUrl = text(item.url());
      String title = text(item.title());
      String imageUrl = text(item.imageUrl());

      if (rawUrl.isEmpty()) {
        rejected++;
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, rawUrl, title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "FAILED", "",
            "🔴 插件没有报出商品链接。", null, null, reportedAt);
        continue;
      }

      ListingCollectUrl.Normalized normalized;
      try {
        normalized = ListingCollectUrl.normalize(rawUrl);
      } catch (IllegalArgumentException e) {
        rejected++;
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, rawUrl, title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "FAILED", "",
            "🔴 商品链接不合法：" + e.getMessage(), null, null, reportedAt);
        continue;
      }

      if (matchSource(normalized.host()) == null) {
        rejected++;
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, normalized.normalized(), title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "FAILED", "",
            "🔴 商品链接的域名 " + normalized.host() + " 不在平台白名单内，已拒绝。", null, null, reportedAt);
        continue;
      }

      if (title.isEmpty()) {
        rejected++;
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, normalized.normalized(), title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "FAILED", "",
            "🔴 插件没有报出商品标题 —— 没有标题的草稿在采集箱里认领时无从判断认领的是什么。", null, null,
            reportedAt);
        continue;
      }

      // ---- 🔴 去重：已存在的商品跳过（不重复建草稿）
      Record existing = findExistingCollect(normalized.normalized());
      if (existing != null) {
        skippedDuplicate++;
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, normalized.normalized(), title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "SKIPPED_DUPLICATE",
            "🔴 这个商品已经采过了，已跳过，**没有重复建草稿**。", "",
            existing.get("task_id", UUID.class), existing.get("draft_id", UUID.class), reportedAt);
        continue;
      }

      // ---- 落成采集草稿（复用 4.4.1.1 的采集任务 + 草稿结构）
      UUID collectTaskId = Ids.next();
      UUID draftId = Ids.next();
      try {
        sql.execute(
            """
            insert into listing_collect_task
              (id,tenant_id,source_id,source_url,normalized_url,final_url,host,state,error_message,
               http_status,attempt_no,actor_id,owner_id,department_id,started_at,finished_at,captured_at,
               created_at,updated_at)
            values (?,?,?,?,?,?,?,'SUCCEEDED','',0,1,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,now(),now())
            """,
            collectTaskId,
            tenant,
            sourceRowId(matchSource(normalized.host())),
            rawUrl,
            normalized.normalized(),
            normalized.normalized(),
            normalized.host(),
            actorId,
            actorId,
            currentDepartment(),
            reportedAt,
            reportedAt,
            reportedAt);
        sql.execute(
            """
            insert into listing_collect_draft
              (id,tenant_id,task_id,source_id,source_url,normalized_url,title,main_image_url,
               image_urls,specs,attributes,description,source_price_amount,source_price_currency,
               captured_at,created_at,updated_at)
            values (?,?,?,?,?,?,?,?,'[]'::jsonb,'{}'::jsonb,'{}'::jsonb,'',?,?,?::timestamptz,now(),now())
            """,
            draftId,
            tenant,
            collectTaskId,
            sourceRowId(matchSource(normalized.host())),
            rawUrl,
            normalized.normalized(),
            title,
            imageUrl,
            item.priceAmount(),
            item.priceCurrency(),
            reportedAt);
      } catch (org.springframework.dao.DuplicateKeyException e) {
        // 🔴 并发下另一个请求先采到了同一个 URL ⇒ 退化成「已存在」，不是失败
        skippedDuplicate++;
        Record race = findExistingCollect(normalized.normalized());
        recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, normalized.normalized(), title, imageUrl,
            item.priceAmount(), item.priceCurrency(), "SKIPPED_DUPLICATE",
            "🔴 这个商品刚刚已被采过，已跳过。", "",
            race == null ? null : race.get("task_id", UUID.class),
            race == null ? null : race.get("draft_id", UUID.class), reportedAt);
        continue;
      }

      fetched++;
      recordResult(taskId, "PLUGIN", 0, itemIndex, rawUrl, normalized.normalized(), title, imageUrl,
          item.priceAmount(), item.priceCurrency(), "FETCHED", "", "", collectTaskId, draftId, reportedAt);
    }

    sql.execute(
        """
        update listing_bulk_collect_task
           set discovered_count=?, fetched_count=?, skipped_duplicate_count=?, failed_count=?, updated_at=now()
         where id=? and tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
        """,
        request.items().size(),
        fetched,
        skippedDuplicate,
        rejected,
        taskId);

    return new PluginReportResult(detail(taskId.toString()), fetched, skippedDuplicate, rejected);
  }

  // ==================================================================
  // 内部：抓取与落库
  // ==================================================================

  /**
   * 一次抓取的结果。
   *
   * @param body 响应体；失败时为 null
   * @param error 失败原因；成功时为 null
   * @param httpStatus 外部站点的 HTTP 状态码（网络层失败为 0）
   * @param finalUrl 跟随重定向后的最终 URL
   * @param capturedAt 页面被读到的时刻
   */
  record FetchOutcome(String body, String error, int httpStatus, String finalUrl, OffsetDateTime capturedAt) {}

  /** 一次商品详情抓取的结果。 */
  record GrabOutcome(
      String title,
      String imageUrl,
      BigDecimal priceAmount,
      String priceCurrency,
      String error,
      UUID collectTaskId,
      UUID draftId,
      OffsetDateTime capturedAt) {}

  /**
   * 🔴 真的发 HTTP 请求抓页面。
   *
   * <p>🔴 这里**不 mock**：真的连、真的收、真的解析。失败时把原因如实带回去。
   */
  private FetchOutcome fetch(String url) {
    String current = url;
    try {
      for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
        var response =
            http
                .get()
                .uri(current)
                .header("User-Agent", "starcon-collect/1.0 (+https://starcon.internal)")
                .header("Accept", "text/html,application/xhtml+xml")
                .retrieve()
                .toEntity(String.class);
        int status = response.getStatusCode().value();
        String body = response.getBody() == null ? "" : response.getBody();
        if (body.length() > MAX_CONTENT_LENGTH) {
          body = body.substring(0, MAX_CONTENT_LENGTH);
        }
        if (status >= 300 && status < 400) {
          String location = response.getHeaders().getFirst("Location");
          if (location == null || location.isBlank()) {
            return new FetchOutcome(
                null,
                "外部站点返回 " + status + " 但没有 Location 头，无法确定最终页面地址。",
                status,
                current,
                now());
          }
          String next = location.startsWith("http") ? location : resolveAgainst(current, location);
          // 🔴 重定向后的目标必须**重新过一次白名单**
          String nextHost = ListingCollectUrl.hostOf(next);
          if (matchSource(nextHost) == null) {
            throw badRequest(
                "🔴 来源站点把请求重定向到了不在平台白名单内的域名 "
                    + nextHost
                    + "（目标："
                    + next
                    + "），已拒绝继续抓取。");
          }
          current = next;
          continue;
        }
        if (status >= 400) {
          return new FetchOutcome(
              null,
              "外部站点返回 HTTP "
                  + status
                  + "（来源："
                  + ListingCollectUrl.hostOf(current)
                  + "），页面可能已下架或需要登录。",
              status,
              current,
              now());
        }
        return new FetchOutcome(body, null, status, current, now());
      }
      return new FetchOutcome(
          null,
          "🔴 重定向次数超过 " + MAX_REDIRECTS + " 次，放弃抓取（目标：" + url + "）。",
          0,
          current,
          now());
    } catch (ResponseStatusException e) {
      // 🔴 白名单拒绝（400）必须**原样抛出**
      throw e;
    } catch (RestClientResponseException e) {
      return new FetchOutcome(
          null,
          "外部站点返回 HTTP " + e.getStatusCode().value() + "（来源：" + ListingCollectUrl.hostOf(current) + "）。",
          e.getStatusCode().value(),
          current,
          now());
    } catch (Exception e) {
      String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new FetchOutcome(
          null,
          "🔴 抓取失败：" + reason + "（来源：" + ListingCollectUrl.hostOf(current) + "）",
          0,
          current,
          now());
    }
  }

  /**
   * 🔴 抓一个商品详情页并落成采集草稿。
   *
   * <p>复用 4.4.1.1 的 {@link ListingCollectHtmlParser} 解析商品页，写进 4.4.1.1 的
   * {@code listing_collect_task} + {@code listing_collect_draft}。
   */
  private GrabOutcome grabDetail(
      ListingCollectUrl.Normalized normalized, BulkCollectSourceRow taskSource, UUID actorId) {
    UUID tenant = currentTenant();
    BulkCollectSourceRow source = matchSource(normalized.host());
    if (source == null) {
      return new GrabOutcome("", "", null, null,
          "🔴 商品链接的域名 " + normalized.host() + " 不在平台白名单内，已跳过。", null, null, now());
    }

    FetchOutcome outcome = fetch(normalized.normalized());
    if (outcome.error() != null) {
      return new GrabOutcome("", "", null, null, outcome.error(), null, null, now());
    }

    ListingCollectHtmlParser.ParsedProduct parsed =
        ListingCollectHtmlParser.parse(outcome.body(), outcome.finalUrl());
    if (parsed.title().isBlank() || !parsed.productSignal()) {
      return new GrabOutcome("", "", null, null,
          "🔴 页面里没有解析出商品信息（既没有 JSON-LD Product，也没有 og:title / og:image）—— "
              + "抓到的多半是错误页 / 列表页，不是商品详情页。",
          null, null, now());
    }

    OffsetDateTime capturedAt = outcome.capturedAt() == null ? now() : outcome.capturedAt();
    UUID collectTaskId = Ids.next();
    UUID draftId = Ids.next();
    int attemptNo =
        ((Number)
                sql.fetchValue(
                    "select count(*)+1 from listing_collect_task"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                        + " and normalized_url=?",
                    normalized.normalized()))
            .intValue();

    sql.execute(
        """
        insert into listing_collect_task
          (id,tenant_id,source_id,source_url,normalized_url,final_url,host,state,error_message,
           http_status,attempt_no,actor_id,owner_id,department_id,started_at,finished_at,captured_at,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,'SUCCEEDED','',?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,now(),now())
        """,
        collectTaskId,
        tenant,
        sourceRowId(source),
        normalized.normalized(),
        normalized.normalized(),
        outcome.finalUrl() == null ? normalized.normalized() : outcome.finalUrl(),
        normalized.host(),
        outcome.httpStatus(),
        attemptNo,
        actorId,
        actorId,
        currentDepartment(),
        capturedAt,
        capturedAt,
        capturedAt);

    sql.execute(
        """
        insert into listing_collect_draft
          (id,tenant_id,task_id,source_id,source_url,normalized_url,title,main_image_url,
           image_urls,specs,attributes,description,source_price_amount,source_price_currency,
           captured_at,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?::timestamptz,now(),now())
        """,
        draftId,
        tenant,
        collectTaskId,
        sourceRowId(source),
        normalized.normalized(),
        normalized.normalized(),
        parsed.title(),
        parsed.mainImageUrl(),
        writeJson(parsed.imageUrls()),
        writeJson(parsed.specs()),
        writeJson(parsed.attributes()),
        parsed.description(),
        parsed.priceAmount(),
        parsed.priceCurrency(),
        capturedAt);

    return new GrabOutcome(
        parsed.title(),
        parsed.mainImageUrl(),
        parsed.priceAmount(),
        parsed.priceCurrency(),
        null,
        collectTaskId,
        draftId,
        capturedAt);
  }

  // ==================================================================
  // 内部：限频
  // ==================================================================

  /** 同一租户两次发起采集任务的最小间隔：还需等多少秒（0 = 可以发）。 */
  private long secondsUntilNextTaskAllowed(int minIntervalSeconds) {
    if (minIntervalSeconds <= 0) return 0;
    Object raw =
        sql.fetchValue(
            "select max(created_at) from listing_bulk_collect_task"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (raw == null) return 0;
    OffsetDateTime last = toOffsetDateTime(raw);
    if (last == null) return 0;
    long elapsed = Duration.between(last, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds();
    return minIntervalSeconds - elapsed;
  }

  /**
   * 同一租户同一 host 的最小抓取间隔：还需等多少秒（0 = 可以抓）。
   *
   * <p>基准是**抓取历史本身**的两份投影（不另建限频表）：
   * <ul>
   *   <li>{@code listing_collect_task} —— 商品详情请求（4.4.1.1 的同一份事实）
   *   <li>{@code listing_bulk_collect_task.last_grab_at} —— 翻页请求
   * </ul>
   */
  private long secondsUntilHostAllowed(String host, int minIntervalSeconds) {
    if (minIntervalSeconds <= 0) return 0;
    Object raw =
        sql.fetchValue(
            """
            select greatest(
              (select max(started_at) from listing_collect_task
                where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and host=?),
              (select max(last_grab_at) from listing_bulk_collect_task
                where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
                  and last_grab_host=?)
            )
            """,
            host,
            host);
    if (raw == null) return 0;
    OffsetDateTime last = toOffsetDateTime(raw);
    if (last == null) return 0;
    long elapsed = Duration.between(last, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds();
    return minIntervalSeconds - elapsed;
  }

  private static OffsetDateTime toOffsetDateTime(Object raw) {
    if (raw == null) return null;
    if (raw instanceof OffsetDateTime value) return value;
    if (raw instanceof java.time.Instant value) return value.atOffset(ZoneOffset.UTC);
    return null;
  }

  /** 等待若干秒（限频间隔）。 */
  private static void sleepSeconds(long seconds) {
    if (seconds <= 0) return;
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ==================================================================
  // 内部：查与写
  // ==================================================================

  private Record fetchTask(UUID taskId) {
    return sql.fetchOne(
        """
        select t.id, t.mode, t.category_url, t.keyword, t.page_from, t.page_to, t.state,
               t.error_message, t.total_pages, t.discovered_count, t.fetched_count,
               t.skipped_duplicate_count, t.failed_count, t.skipped_rate_limited_count,
               t.next_page, t.started_at, t.finished_at, t.created_at, t.page_url_template,
               s.code as source_code, s.name as source_name
        from listing_bulk_collect_task t
        join listing_collect_source s on s.id=t.source_id
        where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
          and t.id=?
        """,
        taskId);
  }

  private BulkCollectTaskRow taskRow(Record row) {
    return new BulkCollectTaskRow(
        row.get("id", UUID.class),
        row.get("mode", String.class),
        row.get("source_code", String.class),
        row.get("source_name", String.class),
        row.get("category_url", String.class),
        row.get("keyword", String.class),
        row.get("page_from", Integer.class),
        row.get("page_to", Integer.class),
        row.get("state", String.class),
        row.get("error_message", String.class),
        row.get("total_pages", Integer.class),
        row.get("discovered_count", Integer.class),
        row.get("fetched_count", Integer.class),
        row.get("skipped_duplicate_count", Integer.class),
        row.get("failed_count", Integer.class),
        row.get("skipped_rate_limited_count", Integer.class),
        row.get("next_page", Integer.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private BulkCollectResultRow resultRow(Record row) {
    return new BulkCollectResultRow(
        row.get("id", UUID.class),
        row.get("bulk_task_id", UUID.class),
        row.get("origin", String.class),
        row.get("page_no", Integer.class),
        row.get("item_index", Integer.class),
        row.get("source_url", String.class),
        row.get("normalized_url", String.class),
        row.get("title", String.class),
        row.get("image_url", String.class),
        row.get("price_amount", BigDecimal.class),
        row.get("price_currency", String.class),
        row.get("state", String.class),
        row.get("skip_reason", String.class),
        row.get("error_message", String.class),
        row.get("collect_task_id", UUID.class),
        row.get("draft_id", UUID.class),
        row.get("captured_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 白名单行的 id（按编码查一次；白名单是全局码表，量很小）。 */
  private UUID sourceRowId(BulkCollectSourceRow source) {
    if (source == null) {
      throw new IllegalStateException("白名单行不存在");
    }
    UUID id =
        (UUID) sql.fetchValue("select id from listing_collect_source where code=?", source.code());
    if (id == null) {
      throw new IllegalStateException("白名单行不存在：" + source.code());
    }
    return id;
  }

  /** 该租户该规范化 URL 是否已有成功的采集（4.4.1.1 的去重事实）。 */
  private Record findExistingCollect(String normalizedUrl) {
    return sql.fetchOne(
        """
        select t.id as task_id, t.normalized_url, t.captured_at,
               d.id as draft_id, d.title, d.main_image_url, d.captured_at as draft_captured_at
        from listing_collect_task t
        left join listing_collect_draft d on d.tenant_id=t.tenant_id and d.task_id=t.id
        where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
          and t.normalized_url=?
          and t.state='SUCCEEDED'
        order by t.created_at desc
        limit 1
        """,
        normalizedUrl);
  }

  private void updateTaskState(
      UUID taskId,
      String state,
      String errorMessage,
      int nextPage,
      int discovered,
      int fetched,
      int skippedDuplicate,
      int failed,
      int skippedRateLimited,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {
    sql.execute(
        """
        update listing_bulk_collect_task
           set state=?,
               error_message=?,
               next_page=?,
               discovered_count=?,
               fetched_count=?,
               skipped_duplicate_count=?,
               failed_count=?,
               skipped_rate_limited_count=?,
               started_at=coalesce(?::timestamptz, started_at),
               finished_at=coalesce(?::timestamptz, finished_at),
               updated_at=now()
         where id=? and tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
        """,
        state,
        errorMessage == null ? "" : errorMessage,
        nextPage,
        discovered,
        fetched,
        skippedDuplicate,
        failed,
        skippedRateLimited,
        startedAt,
        finishedAt,
        taskId);
  }

  /** 记下一次翻页请求的时刻（同 host 抓取间隔的另一半基准）。 */
  private void markGrab(UUID taskId, String host) {
    sql.execute(
        """
        update listing_bulk_collect_task
           set last_grab_at=now(), last_grab_host=?, updated_at=now()
         where id=? and tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
        """,
        host,
        taskId);
  }

  private void recordResult(
      UUID taskId,
      String origin,
      int pageNo,
      int itemIndex,
      String sourceUrl,
      String normalizedUrl,
      String title,
      String imageUrl,
      BigDecimal priceAmount,
      String priceCurrency,
      String state,
      String skipReason,
      String errorMessage,
      UUID collectTaskId,
      UUID draftId,
      OffsetDateTime capturedAt) {
    sql.execute(
        """
        insert into listing_bulk_collect_result
          (id,tenant_id,bulk_task_id,origin,page_no,item_index,source_url,normalized_url,title,
           image_url,price_amount,price_currency,state,skip_reason,error_message,collect_task_id,
           draft_id,captured_at,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())
        """,
        Ids.next(),
        currentTenant(),
        taskId,
        origin,
        pageNo,
        itemIndex,
        sourceUrl == null ? "" : sourceUrl,
        normalizedUrl == null ? "" : normalizedUrl,
        title == null ? "" : title,
        imageUrl == null ? "" : imageUrl,
        priceAmount,
        priceCurrency,
        state,
        skipReason == null ? "" : skipReason,
        errorMessage == null ? "" : errorMessage,
        collectTaskId,
        draftId,
        capturedAt);
  }

  // ==================================================================
  // 内部：地址拼装
  // ==================================================================

  /**
   * 拼出第 {@code pageNo} 页的地址。
   *
   * <p>🔴 返回 null 表示**拼不出来**（关键词模式没有搜索模板）—— 调用方据此给出一条
   * 可读的失败原因，而不是拿一个半成品 URL 去抓。
   */
  private String buildPageUrl(
      String mode,
      BulkCollectSourceRow source,
      String categoryUrl,
      String keyword,
      String template,
      int pageNo) {
    if ("KEYWORD".equals(mode)) {
      String effective = template == null || template.isBlank() ? source.searchUrlTemplate() : template;
      if (effective == null || effective.isBlank()) return null;
      String url = effective.replace("{keyword}", urlEncode(keyword));
      return url.replace("{page}", String.valueOf(source.pageOrigin() + pageNo - 1));
    }
    // CATEGORY_PAGE
    if (template != null && !template.isBlank()) {
      return template.replace("{page}", String.valueOf(pageNo));
    }
    if (categoryUrl == null || categoryUrl.isBlank()) return null;
    if (pageNo <= 1) return categoryUrl;
    return withPageParam(categoryUrl, source.categoryPageParam(),
        String.valueOf(source.pageOrigin() + pageNo - 1));
  }

  /** 给 URL 换一个 query 参数（保留其余参数与顺序）。 */
  static String withPageParam(String url, String param, String value) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      return url;
    }
    String rawQuery = uri.getRawQuery();
    StringBuilder out = new StringBuilder();
    boolean replaced = false;
    if (rawQuery != null && !rawQuery.isEmpty()) {
      for (String part : rawQuery.split("&")) {
        if (part.isEmpty()) continue;
        int eq = part.indexOf('=');
        String name = eq < 0 ? part : part.substring(0, eq);
        if (name.equals(param)) {
          if (out.length() > 0) out.append('&');
          out.append(name).append('=').append(value);
          replaced = true;
        } else {
          if (out.length() > 0) out.append('&');
          out.append(part);
        }
      }
    }
    if (!replaced) {
      if (out.length() > 0) out.append('&');
      out.append(param).append('=').append(value);
    }
    String base =
        uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
    return base + "?" + out;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** 把相对 Location 解析成绝对地址。 */
  private String resolveAgainst(String base, String location) {
    try {
      return URI.create(base).resolve(location).toString();
    } catch (RuntimeException e) {
      return location;
    }
  }

  /** 规范化 URL，非法时给出可读的 400。 */
  private ListingCollectUrl.Normalized normalizeOrReject(String raw) {
    try {
      return ListingCollectUrl.normalize(raw);
    } catch (IllegalArgumentException e) {
      throw badRequest("🔴 " + e.getMessage());
    }
  }

  /** 供能力声明与前端使用的键值映射（保持落库顺序）。 */
  static Map<String, String> describeSources(List<BulkCollectSourceRow> sources) {
    Map<String, String> out = new LinkedHashMap<>();
    for (BulkCollectSourceRow source : sources) {
      out.put(source.code(), source.name());
    }
    return out;
  }
}
