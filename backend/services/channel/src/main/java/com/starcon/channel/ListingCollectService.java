package com.starcon.channel;

import static com.starcon.channel.ListingCollectModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
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
 * 4.4.1.1 链接采集。
 *
 * <p>蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * <p>🔴 <b>归属 channel 而不是 catalog</b>：本模块的输入是外部页面 URL、输出是**采集草稿**，
 * 中间不碰任何一张 catalog 的事实表（建档是 03 的 3.1.1.1，认领是 4.4.1.3 采集箱）。
 * 与 4.1.1.1 / 4.4.2.1 / 4.3.1.2 同一先例。
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>输入：商品 URL（支持平台白名单）</b> → {@link #collect}：先规范化，再查
 *       {@code listing_collect_source} 白名单；<b>不在白名单一律 400 并说明原因</b>。
 *   <li><b>输入：抓取任务</b> → {@code listing_collect_task}：每次抓取留一条（成功与失败都留）。
 *   <li><b>输出：采集到的商品草稿</b> → {@code listing_collect_draft}：标题 / 主图 / 附图 / 规格 /
 *       属性 / 描述。🔴 <b>图片只记 URL，不下载二进制、不写 03 的媒体表</b>（跨服务写表被禁止）。
 *   <li><b>输出：来源 URL 与抓取时间</b> → 任务的 {@code source_url} / {@code normalized_url} 与
 *       {@code captured_at}（{@code timestamptz}）。
 * </ul>
 *
 * <p>🔴 <b>三条纪律在本类里各有一层物理落点，不是靠注释提醒：</b>
 *
 * <ol>
 *   <li><b>只产生草稿</b>：本类**只**写 {@code listing_collect_task} 与
 *       {@code listing_collect_draft} 两张表。类里**没有一条** insert/update 打向
 *       {@code listing} / {@code listing_variant} / {@code listing_content_snapshot}，
 *       更没有 catalog 的建档调用 —— 采集品要进商品库必须走 4.4.1.3 → 3.1.1.1。
 *   <li><b>白名单外 400</b>：白名单读的是 {@code listing_collect_source}（全局码表，只授
 *       SELECT）—— 租户不能自行加白名单域名，抓取面不会因为单个租户而变大。
 *   <li><b>限频 + 去重</b>：限频基准是**抓取历史本身**（{@code listing_collect_task} 里该租户
 *       该 host 最近一次抓取），不另建限频表 —— 另建就会出现「限频表说没抓过、抓取历史说抓过」
 *       的两份事实。去重是规范化 URL 上的**部分唯一索引**，失败的抓取不占名额（否则一次网络
 *       抖动就把这个 URL 永久锁死）。
 * </ol>
 */
@Service
public class ListingCollectService {

  /** 抓取超时：外部站点慢是常态，但也不能无限等 —— 一次采集最多占住一个请求线程 20 秒。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** 响应体上限：外部页面可能是几十 MB 的图片页，截断保护服务自身。 */
  private static final int MAX_CONTENT_LENGTH = 4 * 1024 * 1024;

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final RestClient http;

  public ListingCollectService(DSLContext sql, ObjectMapper json) {
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

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>未交付段一律 {@code available=false} + ownerModule + impact：媒体库下载（03）、
   * 采集箱（4.4.1.3）、批量/插件采集（4.4.1.2）、认领为 SPU（3.1.1.1）、我方售价（05 定价）。
   */
  public ListingCollectCapabilities capabilities() {
    return new ListingCollectCapabilities(
        List.of(
            new ListingCollectCapabilityDimension(
                "source_allowlist",
                "🔴 平台白名单（只允许配置内的来源域名；白名单外一律 400）",
                true,
                "listing_collect_source（全局码表，只授 SELECT）",
                "",
                "缺了它抓取面就没有上界 —— 一个能随便填 URL 的采集端点就是 SSRF 入口"),
            new ListingCollectCapabilityDimension(
                "collect_task",
                "🔴 抓取任务留痕（成功与失败都留，失败带原因）",
                true,
                "listing_collect_task",
                "",
                "没有它就答不出「这条链接为什么采不到」—— 失败被吞掉后运营只能反复重试"),
            new ListingCollectCapabilityDimension(
                "collect_draft",
                "🔴 采集草稿（标题 / 主图 / 附图 / 规格 / 属性 / 描述）",
                true,
                "listing_collect_draft",
                "",
                "它不是商品：进商品库必须走 4.4.1.3 采集箱 → 3.1.1.1 建档"),
            new ListingCollectCapabilityDimension(
                "rate_limit",
                "🔴 同 host 限频（同一租户同一 host 的最小抓取间隔）",
                true,
                "listing_collect_source.min_interval_seconds + listing_collect_task 的抓取历史",
                "",
                "缺了它批量采集会打爆外部站点 —— 抓取先于对方限流被封 IP"),
            new ListingCollectCapabilityDimension(
                "dedup",
                "🔴 同 URL 去重（规范化后已有成功记录则直接返回既有草稿，不重复请求）",
                true,
                "listing_collect_task.normalized_url（部分唯一索引）+ listing_collect_draft",
                "",
                "缺了它同一件商品会被反复抓，采集箱里塞满重复草稿"),
            new ListingCollectCapabilityDimension(
                "media_library",
                "🔴 图片下载入媒体库",
                false,
                "—",
                "03 商品主数据（catalog）媒体库",
                "🔴 媒体库归 03 catalog，AGENTS.md **禁止跨服务数据库访问** —— "
                    + "本模块**只记录图片 URL**，不下载二进制、不写媒体表。"
                    + "认领时由下游把 URL 交给 03 落库。"),
            new ListingCollectCapabilityDimension(
                "collection_box",
                "采集箱（暂存分组 / 批量认领 / 批量删除 / 批量打标）",
                false,
                "—",
                "4.4.1.3 采集箱",
                "本模块只负责产出**单个 URL 的一次采集草稿**；暂存与批量认领是 4.4.1.3 的范围"),
            new ListingCollectCapabilityDimension(
                "batch_collect",
                "批量 / 插件采集（分类页 URL / 关键词 / 浏览器插件上报）",
                false,
                "—",
                "4.4.1.2 批量/插件采集",
                "本模块只处理**单个商品链接**；分类页与关键词批量是 4.4.1.2 的范围"),
            new ListingCollectCapabilityDimension(
                "claim_to_spu",
                "认领为内部商品（生成 SPU / SKU 草稿并预填内容）",
                false,
                "—",
                "3.1.1.1 商品建档（经 4.4.1.3 采集箱）",
                "🔴 **采集只产生草稿**。进入正式商品库必须走 03 的建档流程并补全属性与合规信息"),
            new ListingCollectCapabilityDimension(
                "price",
                "我方售价与币种",
                false,
                "—",
                "05 定价与价目系统",
                "卷 05 整卷未交付 ⇒ 采集草稿**不产出我方售价**。"
                    + "草稿上的 sourcePriceAmount 是**外部页面原文标价**，不是我们的定价"),
            new ListingCollectCapabilityDimension(
                "internal_stock",
                "内部可售量",
                false,
                "—",
                "07 库存系统",
                "卷 07 未交付 ⇒ 采集草稿不带任何库存事实（外部页面的库存数也不是我方可售量）")
        ),
        listSources(),
        STATES,
        true,
        "🔴 采集**只产生草稿**：本模块不建 SPU / SKU、不写 listing、不改任何商品主数据。"
            + "草稿要进正式商品库，必须走 4.4.1.3 采集箱 → 3.1.1.1 商品建档，并补全属性与合规信息。",
        false,
        "🔴 图片**只记 URL**（主图 + 附图地址），不下载二进制、不写媒体表 —— "
            + "媒体库归 03 catalog，跨服务写表被 AGENTS.md 禁止。",
        true,
        "🔴 同一 URL 不重复抓：规范化 URL（去 utm 之类跟踪参数、归一 host、排序查询参数）后"
            + "若已有成功记录，直接返回既有采集草稿，**不再发出第二次 HTTP 请求**。",
        true,
        "🔴 同一租户同一 host 的抓取有最小间隔（按平台配置），超限会明确告诉你还需等多少秒。",
        "上游 = 外部商品页面（真实 HTTP 抓取 + JSON-LD / Open Graph / 规格表解析）；"
            + "下游 = 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。"
            + "03 商品主数据只经下游流程引用，**本模块不跨服务读表、不跨服务写表**。");
  }

  // ==================================================================
  // 白名单
  // ==================================================================

  /** 白名单全部行（启用的在前，按 position 排）。 */
  public List<ListingCollectSourceRow> listSources() {
    List<ListingCollectSourceRow> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select code,name,host,kind,min_interval_seconds from listing_collect_source"
                + " where enabled order by position, code")) {
      out.add(
          new ListingCollectSourceRow(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("host", String.class),
              row.get("kind", String.class),
              row.get("min_interval_seconds", Integer.class)));
    }
    return out;
  }

  /** 白名单里命中的那一行；没命中返回 null。 */
  private ListingCollectSourceRow matchSource(String host) {
    List<ListingCollectSourceRow> sources = listSources();
    // 最长的白名单域名优先命中（"1688.com" 不该盖住更具体的条目）
    List<ListingCollectSourceRow> sorted = new ArrayList<>(sources);
    sorted.sort((a, b) -> b.host().length() - a.host().length());
    for (ListingCollectSourceRow source : sorted) {
      if (ListingCollectUrl.hostMatches(host, source.host())) return source;
    }
    return null;
  }

  // ==================================================================
  // 采集（核心动作）
  // ==================================================================

  /**
   * 🔴 采集一次外部商品链接。
   *
   * <p>顺序是刻意的，每一步都有理由：
   *
   * <ol>
   *   <li><b>规范化</b>（{@link ListingCollectUrl#normalize}）—— 先把"同一个页面的不同写法"合并，
   *       后面的去重才有意义。
   *   <li><b>白名单</b> —— 不在白名单**直接 400**，一个 HTTP 请求都不发。
   *   <li><b>去重</b> —— 规范化 URL 已有成功记录 ⇒ 直接返回既有草稿，**不发 HTTP**。
   *   <li><b>限频</b> —— 该租户该 host 距上次抓取不足最小间隔 ⇒ 429（明确的等待秒数）。
   *   <li><b>真抓</b> —— 发真实 HTTP 请求、真实解析、真实落库。
   * </ol>
   *
   * <p>🔴 注意第 3、4 步在第 5 步<b>之前</b>：去重命中的请求**根本不该占限频额度**
   * （它没打外部站点），而限频命中的请求更不该先打站点再被拒。
   */
  public CollectListingResult collect(CollectListingRequest request, UUID actorId) {
    UUID tenant = currentTenant();

    // ---- 1. 规范化
    ListingCollectUrl.Normalized normalized;
    try {
      normalized = ListingCollectUrl.normalize(request.url());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // ---- 2. 白名单
    ListingCollectSourceRow source = matchSource(normalized.host());
    if (source == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "🔴 来源域名 "
              + normalized.host()
              + " 不在平台白名单内，只允许采集这些来源："
              + String.join("、", listSources().stream().map(ListingCollectSourceRow::host).toList())
              + "。如需新增来源，请运维在平台白名单（listing_collect_source）中登记。");
    }

    // ---- 3. 去重（同一 URL 不重复抓）
    Record existing =
        sql.fetchOne(
            """
            select t.id as task_id, t.source_url, t.normalized_url, t.final_url, t.host,
                   t.state, t.error_message, t.http_status, t.attempt_no, t.captured_at,
                   t.started_at, t.finished_at, t.created_at,
                   s.code as source_code, s.name as source_name,
                   d.id as draft_id, d.title, d.main_image_url, d.image_urls, d.specs,
                   d.attributes, d.description, d.source_price_amount, d.source_price_currency,
                   d.captured_at as draft_captured_at, d.created_at as draft_created_at
            from listing_collect_task t
            join listing_collect_source s on s.id=t.source_id
            left join listing_collect_draft d
              on d.tenant_id=t.tenant_id and d.task_id=t.id
            where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
              and t.normalized_url=?
              and t.state='SUCCEEDED'
            order by t.created_at desc
            limit 1
            """,
            normalized.normalized());
    if (existing != null) {
      // 🔴 已采过 ⇒ 直接返回既有草稿，**不发第二次 HTTP 请求**
      return new CollectListingResult(
          taskRow(existing, source), draftRow(existing), true);
    }

    // ---- 4. 限频（同一租户同一 host 的最小间隔）
    Record last =
        sql.fetchOne(
            """
            select started_at from listing_collect_task
            where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
              and host=?
            order by started_at desc
            limit 1
            """,
            normalized.host());
    if (last != null) {
      OffsetDateTime previous = last.get("started_at", OffsetDateTime.class);
      if (previous != null) {
        long elapsed = java.time.Duration.between(previous, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds();
        long wait = source.minIntervalSeconds() - elapsed;
        if (wait > 0) {
          throw new ResponseStatusException(
              HttpStatus.TOO_MANY_REQUESTS,
              "🔴 抓取过于频繁：来源 "
                  + source.name()
                  + "（"
                  + normalized.host()
                  + "）要求同一租户至少间隔 "
                  + source.minIntervalSeconds()
                  + " 秒，请再等 "
                  + wait
                  + " 秒。");
        }
      }
    }

    // ---- 5. 真抓
    // 🔴 截断到微秒：PostgreSQL 的 timestamptz 精度是微秒，纳秒部分存不进去。
    //    不截断的话写回值与读回值永远差几百纳秒，"往返逐字相等"的断言会假失败。
    OffsetDateTime startedAt = now();
    FetchOutcome outcome = fetch(normalized.normalized());
    OffsetDateTime finishedAt = now();
    OffsetDateTime capturedAt = outcome.capturedAt() == null ? finishedAt : outcome.capturedAt();

    int attemptNo =
        ((Number)
                sql.fetchValue(
                    "select count(*)+1 from listing_collect_task"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                        + " and normalized_url=?",
                    normalized.normalized()))
            .intValue();

    String state = outcome.error() == null ? "SUCCEEDED" : "FAILED";
    String errorMessage = outcome.error() == null ? "" : outcome.error();

    // ---- 6. 落库：任务行（成功与失败都留）
    UUID taskId = Ids.next();
    sql.execute(
        """
        insert into listing_collect_task
          (id,tenant_id,source_id,source_url,normalized_url,final_url,host,state,error_message,
           http_status,attempt_no,actor_id,owner_id,department_id,started_at,finished_at,captured_at,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,now(),now())
        """,
        taskId,
        tenant,
        sourceRowId(source),
        normalized.normalized(),
        normalized.normalized(),
        outcome.finalUrl() == null ? normalized.normalized() : outcome.finalUrl(),
        normalized.host(),
        state,
        errorMessage,
        outcome.httpStatus(),
        attemptNo,
        actorId,
        actorId,
        currentDepartment(),
        startedAt,
        finishedAt,
        capturedAt);

    // ---- 7. 落库：草稿行（只在成功时产生）
    UUID draftId = null;
    if ("SUCCEEDED".equals(state)) {
      draftId = Ids.next();
      ListingCollectHtmlParser.ParsedProduct parsed = outcome.parsed();
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
          taskId,
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
    }

    Record stored = fetchTask(taskId);
    return new CollectListingResult(taskRow(stored, source), draftRow(stored), false);
  }

  // ==================================================================
  // 读取
  // ==================================================================

  /** 采集历史（真分页 + 状态与来源筛选 + 时间倒序）。 */
  public ListingCollectPage list(String state, String sourceCode, int page, int size) {
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    String normalizedState = blankToNull(state);
    if (normalizedState != null && !STATES.contains(normalizedState.toUpperCase(Locale.ROOT))) {
      throw unprocessable("未知的抓取状态：" + state + "。可选：" + String.join(" / ", STATES));
    }
    String normalizedSource = blankToNull(sourceCode);

    // 🔴 绑定顺序必须与占位符出现顺序一致：where 里的筛选参数在 limit/offset **之前**
    List<Object> filterBinds = new ArrayList<>();
    if (normalizedState != null) filterBinds.add(normalizedState.toUpperCase(Locale.ROOT));
    if (normalizedSource != null) filterBinds.add(normalizedSource);

    String where =
        " where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
            + (normalizedState != null ? " and t.state=?" : "")
            + (normalizedSource != null ? " and s.code=?" : "");

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_collect_task t"
                        + " join listing_collect_source s on s.id=t.source_id"
                        + where,
                    filterBinds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(filterBinds);
    pageBinds.add(limit);
    pageBinds.add(offset);

    List<ListingCollectSummaryRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select t.id, t.source_url, t.normalized_url, t.host, t.state, t.error_message,
                   t.http_status, t.attempt_no, t.captured_at, t.created_at,
                   s.code as source_code, s.name as source_name,
                   d.title as draft_title
            from listing_collect_task t
            join listing_collect_source s on s.id=t.source_id
            left join listing_collect_draft d on d.tenant_id=t.tenant_id and d.task_id=t.id
            """
                + where
                + " order by t.created_at desc, t.id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(
          new ListingCollectSummaryRow(
              row.get("id", UUID.class),
              row.get("source_url", String.class),
              row.get("normalized_url", String.class),
              row.get("host", String.class),
              row.get("source_code", String.class),
              row.get("source_name", String.class),
              row.get("state", String.class),
              row.get("error_message", String.class),
              row.get("http_status", Integer.class),
              row.get("attempt_no", Integer.class),
              row.get("draft_title", String.class),
              row.get("captured_at", OffsetDateTime.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new ListingCollectPage(items, total, Math.max(0, page), limit);
  }

  /** 单次采集详情（任务 + 草稿）。 */
  public CollectListingResult detail(String id) {
    UUID taskId = parseId(id);
    Record row = fetchTask(taskId);
    if (row == null) throw notFound();
    return new CollectListingResult(taskRow(row, null), draftRow(row), false);
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private Record fetchTask(UUID taskId) {
    return sql.fetchOne(
        """
        select t.id as task_id, t.source_url, t.normalized_url, t.final_url, t.host,
               t.state, t.error_message, t.http_status, t.attempt_no, t.captured_at,
               t.started_at, t.finished_at, t.created_at,
               s.code as source_code, s.name as source_name,
               d.id as draft_id, d.title, d.main_image_url, d.image_urls, d.specs,
               d.attributes, d.description, d.source_price_amount, d.source_price_currency,
               d.captured_at as draft_captured_at, d.created_at as draft_created_at
        from listing_collect_task t
        join listing_collect_source s on s.id=t.source_id
        left join listing_collect_draft d on d.tenant_id=t.tenant_id and d.task_id=t.id
        where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
          and t.id=?
        """,
        taskId);
  }

  private ListingCollectTaskRow taskRow(Record row, ListingCollectSourceRow source) {
    String code = row.get("source_code", String.class);
    String name = row.get("source_name", String.class);
    if (source != null) {
      code = source.code();
      name = source.name();
    }
    return new ListingCollectTaskRow(
        row.get("task_id", UUID.class),
        row.get("source_url", String.class),
        row.get("normalized_url", String.class),
        row.get("final_url", String.class),
        row.get("host", String.class),
        code,
        name,
        row.get("state", String.class),
        row.get("error_message", String.class),
        row.get("http_status", Integer.class),
        row.get("attempt_no", Integer.class),
        row.get("draft_id", UUID.class),
        row.get("captured_at", OffsetDateTime.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private ListingCollectDraftRow draftRow(Record row) {
    UUID draftId = row.get("draft_id", UUID.class);
    if (draftId == null) return null;
    return new ListingCollectDraftRow(
        draftId,
        row.get("task_id", UUID.class),
        row.get("source_url", String.class),
        row.get("normalized_url", String.class),
        row.get("title", String.class),
        row.get("main_image_url", String.class),
        readStringList(row.get("image_urls")),
        readKeyValues(row.get("specs")),
        readKeyValues(row.get("attributes")),
        row.get("description", String.class),
        row.get("source_price_amount", BigDecimal.class),
        row.get("source_price_currency", String.class),
        row.get("draft_captured_at", OffsetDateTime.class),
        row.get("draft_created_at", OffsetDateTime.class));
  }

  @SuppressWarnings("unchecked")
  private List<String> readStringList(Object raw) {
    if (raw == null) return List.of();
    String value = raw instanceof org.jooq.JSONB jsonb ? jsonb.data() : String.valueOf(raw);
    try {
      var node = json.readTree(value);
      if (node == null || !node.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      node.forEach(item -> out.add(item.asText()));
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** jsonb 对象 → 键值项列表（保持落库时的顺序）。 */
  private List<ListingCollectKeyValue> readKeyValues(Object raw) {
    if (raw == null) return List.of();
    String value = raw instanceof org.jooq.JSONB jsonb ? jsonb.data() : String.valueOf(raw);
    try {
      var node = json.readTree(value);
      if (node == null || !node.isObject()) return List.of();
      List<ListingCollectKeyValue> out = new ArrayList<>();
      node.fields()
          .forEachRemaining(
              entry -> out.add(new ListingCollectKeyValue(entry.getKey(), entry.getValue().asText())));
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** 白名单行的 id（按 code 查一次；白名单是全局码表，量很小）。 */
  private UUID sourceRowId(ListingCollectSourceRow source) {
    UUID id =
        (UUID)
            sql.fetchValue(
                "select id from listing_collect_source where code=?", source.code());
    if (id == null) {
      throw new IllegalStateException("白名单行不存在：" + source.code());
    }
    return id;
  }

  /** 当前用户的部门（数据范围用；没有部门就是 null，与 V1 shop 同一纪律）。 */
  private UUID currentDepartment() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.department',true),'')");
    if (raw == null) return null;
    String value = String.valueOf(raw);
    return value.isBlank() ? null : UUID.fromString(value);
  }

  // ==================================================================
  // 真抓（HTTP）
  // ==================================================================

  /**
   * 一次抓取的结果。
   *
   * @param parsed 解析结果；失败时为 null
   * @param error 失败原因；成功时为 null
   * @param httpStatus 外部站点的 HTTP 状态码（网络层失败为 0）
   * @param finalUrl 跟随重定向后的最终 URL
   * @param capturedAt 外部页面被读到的时刻
   */
  record FetchOutcome(
      ListingCollectHtmlParser.ParsedProduct parsed,
      String error,
      int httpStatus,
      String finalUrl,
      OffsetDateTime capturedAt) {}

  /**
   * 🔴 真的发 HTTP 请求抓页面。
   *
   * <p>🔴 这里**不 mock**：真的连、真的收、真的解析。失败时把原因如实带回去
   * （超时 / DNS / 4xx / 5xx / 内容里没有商品信息），前端据此显示"可重试"。
   */
  private FetchOutcome fetch(String url) {
    String current = url;
    try {
      for (int hop = 0; hop < 5; hop++) {
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
          // 🔴 重定向后的目标必须**重新过一次白名单**：否则白名单域名可以把请求引到任意站点
          String nextHost = ListingCollectUrl.hostOf(next);
          if (matchSource(nextHost) == null) {
            // 🔴 白名单外一律 400：重定向到白名单外与直接提交白名单外 URL 是同一件事，
            //    不能因为"绕了一圈"就把它降级成一次普通的抓取失败。
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "🔴 来源站点把请求重定向到了不在平台白名单内的域名 "
                    + nextHost
                    + "（目标："
                    + next
                    + "），已拒绝继续抓取。"
                    + "当前允许的来源："
                    + String.join(
                        "、", listSources().stream().map(ListingCollectSourceRow::host).toList()));
          }
          current = next;
          continue;
        }
        if (status >= 400) {
          return new FetchOutcome(
              null,
              "外部站点返回 HTTP " + status + "（来源："
                  + ListingCollectUrl.hostOf(current)
                  + "），页面可能已下架或需要登录。",
              status,
              current,
              now());
        }
        ListingCollectHtmlParser.ParsedProduct parsed =
            ListingCollectHtmlParser.parse(body, current);
        if (parsed.title().isBlank()) {
          return new FetchOutcome(
              null,
              "🔴 页面里没有解析出商品标题（JSON-LD Product / og:title / <title> 都没有）—— "
                  + "这个链接可能不是商品详情页，或该站点改版了。",
              status,
              current,
              now());
        }
        if (!parsed.productSignal()) {
          // 🔴 光有 <title> 不算"这是一个商品页"：404 页、列表页、登录页也都有 <title>。
          //    判据是**商品信号**（JSON-LD Product / og:title / og:image）——
          //    真实商品页必然至少命中一个，而错误页一个都不命中。
          return new FetchOutcome(
              null,
              "🔴 页面里没有任何商品信号（既没有 JSON-LD Product，也没有 og:title / og:image）—— "
                  + "抓到的「"
                  + parsed.title()
                  + "」只是页面的 <title>，多半是错误页 / 列表页 / 登录页，不是商品详情页。",
              status,
              current,
              now());
        }
        return new FetchOutcome(
            parsed, null, status, current, now());
      }
      return new FetchOutcome(
          null,
          "🔴 重定向次数超过 5 次，放弃抓取（目标："
              + url
              + "）。这通常意味着该链接指向一个跳转环。",
          0,
          current,
          now());
    } catch (ResponseStatusException e) {
      // 🔴 白名单拒绝（400）必须**原样抛出**：它不是"抓取失败"，而是一次请求根本没被允许。
      //    被下面的兜底 catch 吞掉的话，调用方只会看到"抓取失败"，白名单就形同虚设。
      throw e;
    } catch (RestClientResponseException e) {
      return new FetchOutcome(
          null,
          "外部站点返回 HTTP " + e.getStatusCode().value() + "（来源："
              + ListingCollectUrl.hostOf(current) + "）。",
          e.getStatusCode().value(),
          current,
          now());
    } catch (Exception e) {
      // 网络层失败：DNS、超时、连接被拒、TLS 错误……
      String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new FetchOutcome(
          null,
          "🔴 抓取失败：" + reason + "（来源：" + ListingCollectUrl.hostOf(current) + "）",
          0,
          current,
          now());
    }
  }

  /** 把相对 Location 解析成绝对地址。 */
  private String resolveAgainst(String base, String location) {
    try {
      return java.net.URI.create(base).resolve(location).toString();
    } catch (RuntimeException e) {
      return location;
    }
  }
}
