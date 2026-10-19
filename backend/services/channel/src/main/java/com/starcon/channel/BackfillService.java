package com.starcon.channel;

import static com.starcon.channel.BackfillModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.6.2 增量补拉。
 *
 * <p>蓝图 §2.6.2.1 的四条硬纪律，逐条落到具体机制：
 *
 * <ol>
 *   <li><b>补拉必须幂等</b> —— {@link #submit} 对每条外部记录先按
 *       (租户, 连接, 资源类型, 外部记录 ID) 抢 {@code backfill_record} 的唯一索引。
 *       抢到 = 新增；抢不到 = 比对内容指纹，指纹相同判 DEDUPLICATED、指纹不同判 UPDATED。
 *       两条路径都<b>不产生第二行业务记录</b>，这是 DB 层保证，不是应用层的自觉。
 *   <li><b>只给"最近 N 条"的平台</b> —— {@code PaginationMode.RECENT_N_SCAN} 强制
 *       {@code delayedDiscovery=true}（DB CHECK 兜底），并按 {@link #submit} 提交的
 *       分段水位逐段往窗口起点推进；平台推新数据会顶掉"最近 N 条"，所以补拉可能延迟发现，
 *       这一点如实标记而不是假装完整。
 *   <li><b>四个数字分开记</b> —— 拉取 / 新增 / 更新 / 去重，四个独立计数，
 *       由 {@code guard_backfill_counts} 触发器保证自洽（拉取 = 新增+更新+去重+失败）。
 *   <li><b>幂等键是入参</b> —— {@link #create} 用 (租户, idempotency_key) 唯一索引，
 *       相同 key 重复发起返回既有任务，不产生第二个补拉任务。
 * </ol>
 *
 * <p>模块边界：下游是 09 订单 / 04 刊登 / 12 结算，属其它服务。本模块只负责
 * 「把缺失区间可靠、幂等地重新捞一遍并留下可审计的处理记录」，
 * <b>不读它们的表、不建跨库 FK、不替它们记账</b>。
 */
@Service
public class BackfillService {

  /** 与 2.6.1.1 同一套脱敏键名：凭据类字段入库前剔除（AGENTS.md：明文 secret 不入库）。 */
  private static final Set<String> REDACTED_KEYS =
      Set.of(
          "access_token", "refresh_token", "signing_secret", "password", "authorization",
          "api_key", "apikey", "secret", "client_secret", "x-starcon-signature");

  private static final int MAX_PAYLOAD_CHARS = 8000;

  private final DSLContext sql;
  private final ObjectMapper json;

  public BackfillService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  // ------------------------------------------------------------------
  // 能力声明
  // ------------------------------------------------------------------

  /**
   * 02.6.2.1 能力声明。
   *
   * <p>下游同步处理器（09 订单 / 04 刊登 / 12 结算）不在本服务，
   * {@code downstream_dispatch} 显式 available=false 并点名 ownerModule，
   * 界面据此禁用而不是让用户点了才报错。
   */
  public BackfillCapabilities capabilities() {
    return new BackfillCapabilities(
        List.of(
            new BackfillCapability(
                "incremental_backfill", true, "按连接+站点+资源类型+时间窗主动补拉缺失区间", null),
            new BackfillCapability(
                "idempotent_ingest", true, "同一条外部记录重复补拉只计去重，不产生第二条业务记录", null),
            new BackfillCapability(
                "segmented_scan",
                true,
                "平台只提供最近 N 条时分段轮询，并以「补拉可能延迟发现」显式标注",
                null),
            new BackfillCapability(
                "webhook_gap_detection", true, "按 2.6.1.1 接收记录的时间窗静默识别漏推区间", null),
            new BackfillCapability(
                "downstream_dispatch",
                false,
                "补拉结果分发给 09 订单 / 04 刊登 / 12 结算的同步处理器 —— 跨服务走事件，本服务不建跨库 FK",
                "9.1.1.1")),
        List.of(
            new BackfillResourceSupport(
                "ORDER", List.of("TIME_INCREMENTAL", "RECENT_N_SCAN"), "按订单创建时间增量；部分平台只给最近 N 条"),
            new BackfillResourceSupport(
                "LISTING", List.of("TIME_INCREMENTAL", "RECENT_N_SCAN"), "按商品更新时刻增量"),
            new BackfillResourceSupport(
                "INVENTORY", List.of("TIME_INCREMENTAL"), "库存快照类接口通常按更新时间增量"),
            new BackfillResourceSupport(
                "SETTLEMENT", List.of("TIME_INCREMENTAL", "RECENT_N_SCAN"), "结算单按账期生成，窗口跨度较大"),
            new BackfillResourceSupport(
                "PRODUCT", List.of("TIME_INCREMENTAL", "RECENT_N_SCAN"), "平台商品目录接口形态差异最大")));
  }

  // ------------------------------------------------------------------
  // 02.6.2.1 发起补拉（幂等键是入参契约）
  // ------------------------------------------------------------------

  /**
   * 发起一次补拉。
   *
   * <p>幂等键重复时返回<b>既有任务 ID</b>，不产生第二个补拉任务 —— 漏推检测与人工发起
   * 很容易对同一段区间各发一次，这条唯一索引让第二次直接落到既有任务上。
   */
  public UUID create(AccessService.Access actor, BackfillTaskInput input) {
    validateWindow(input);

    // RECENT_N_SCAN 的任务必须承认自己可能延迟发现。应用层忘了置位也在 DB CHECK 被拒，
    // 但这里先给出可读的错误，而不是让人去看约束违反信息。
    boolean delayed =
        input.paginationMode() == PaginationMode.RECENT_N_SCAN
            || Boolean.TRUE.equals(input.delayedDiscovery());

    Record existing =
        sql.fetchOne(
            "select id,status from backfill_task where tenant_id=? and idempotency_key=?",
            actor.tenantId(),
            input.idempotencyKey());
    if (existing != null) return existing.get("id", UUID.class);

    UUID id = Ids.next();
    sql.execute(
        "insert into backfill_task(id,tenant_id,connection_id,marketplace_id,resource_type,"
            + " window_start,window_end,trigger_source,idempotency_key,cursor_id,pagination_mode,"
            + " delayed_discovery,page_size,max_pages,rate_limit_per_minute,status)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?, 'PENDING')",
        id,
        actor.tenantId(),
        input.connectionId(),
        input.marketplaceId(),
        input.resourceType(),
        toUtc(input.windowStart()),
        toUtc(input.windowEnd()),
        input.triggerSource().name(),
        input.idempotencyKey(),
        input.cursorId(),
        input.paginationMode().name(),
        delayed,
        input.pageSize() == null ? 100 : input.pageSize(),
        input.maxPages() == null ? 20 : input.maxPages(),
        input.rateLimitPerMinute() == null ? 60 : input.rateLimitPerMinute());
    return id;
  }

  private void validateWindow(BackfillTaskInput input) {
    if (!input.windowEnd().isAfter(input.windowStart()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补拉时间窗右端必须晚于左端");
    if (input.windowEnd().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补拉时间窗不能延伸到未来");
  }

  // ------------------------------------------------------------------
  // 02.6.2.1 提交补拉结果（幂等判定在这里）
  // ------------------------------------------------------------------

  /**
   * 提交一批补拉结果，逐条判定新增 / 更新 / 去重 / 失败。
   *
   * <p>幂等性由 DB 层的唯一索引裁决，不靠应用层的自觉：每条外部记录都要抢
   * {@code backfill_record(tenant_id, connection_id, resource_type, external_key)} 这一个唯一锚点。
   * 抢到 = CREATED；抢不到 = 看内容指纹 —— 相同判 DEDUPLICATED、不同判 UPDATED。
   * 两条路径都不会产生第二行，因此同一条外部记录补拉多次不会产生第二张订单。
   */
  public BackfillSubmitResult submit(
      AccessService.Access actor, UUID taskId, BackfillSubmitInput input) {

    Record task = loadTask(actor, taskId);
    if ("CANCELLED".equals(task.get("status", String.class)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "补拉任务已中止，无法继续提交");

    // 分页上限：单批条数不得超过任务声明的 page_size。
    // 这就是蓝图「识别平台接口的分页上限」的落点 —— 超出说明执行端忽略了平台限制。
    int pageSize = task.get("page_size", Integer.class);
    if (input.records().size() > pageSize)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "单批提交 " + input.records().size() + " 条，超过平台分页上限 " + pageSize + " 条");

    String connectionId = task.get("connection_id", UUID.class).toString();
    String resourceType = task.get("resource_type", String.class);
    UUID connectionUuid = task.get("connection_id", UUID.class);

    List<BackfillRecordResult> results = new ArrayList<>();
    int created = 0, updated = 0, dedup = 0, failed = 0;

    for (BackfillRecordInput r : input.records()) {
      BackfillRecordResult outcome = ingestOne(actor, taskId, connectionUuid, resourceType, r);
      results.add(outcome);
      switch (outcome.disposition()) {
        case "CREATED" -> created++;
        case "UPDATED" -> updated++;
        case "DEDUPLICATED" -> dedup++;
        default -> failed++;
      }
    }

    int fetched = input.records().size();

    // 分段轮询：RECENT_N_SCAN 时按水位逐段留痕。没有这张留痕，
    // 「分段轮询」就只是一句口号 —— 出了漏单无从证明轮询到哪一段。
    int segmentsDone = task.get("segments_done", Integer.class);
    int segmentTotal = task.get("segment_total", Integer.class);
    if (input.segmentCoveredFrom() != null) {
      int nextNo = segmentsDone + 1;
      sql.execute(
          "insert into backfill_segment(id,tenant_id,task_id,segment_no,covered_from,covered_to,"
              + " reached_window_start,record_count,duplicate_count,rate_limited)"
              + " values (?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?)"
              + " on conflict (tenant_id,task_id,segment_no) do update"
              + " set covered_from=excluded.covered_from,covered_to=excluded.covered_to,"
              + " reached_window_start=excluded.reached_window_start,"
              + " record_count=excluded.record_count,duplicate_count=excluded.duplicate_count,"
              + " rate_limited=excluded.rate_limited,polled_at=now()",
          Ids.next(),
          actor.tenantId(),
          taskId,
          nextNo,
          toUtc(input.segmentCoveredFrom()),
          toUtc(input.segmentCoveredFrom()),
          Boolean.TRUE.equals(input.segmentReachedWindowStart()),
          fetched,
          dedup,
          Boolean.TRUE.equals(input.rateLimited()));
      segmentTotal = nextNo;
      if (Boolean.TRUE.equals(input.segmentReachedWindowStart())) segmentTotal = nextNo;
    }

    // 四个数字分开累加。触发器 guard_backfill_counts 保证拉取 = 新增+更新+去重+失败。
    sql.execute(
        "update backfill_task set fetched_count=fetched_count+?,created_count=created_count+?,"
            + " updated_count=updated_count+?,deduplicated_count=deduplicated_count+?,"
            + " failed_count=failed_count+?,segments_done=?,segment_total=?,status='RUNNING',"
            + " started_at=coalesce(started_at,now()),version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?",
        fetched,
        created,
        updated,
        dedup,
        failed,
        segmentsDone + (input.segmentCoveredFrom() != null ? 1 : 0),
        segmentTotal,
        actor.tenantId(),
        taskId);

    return new BackfillSubmitResult(taskId, fetched, created, updated, dedup, failed, results);
  }

  /**
   * 单条记录的幂等判定。
   *
   * <p>先抢唯一索引，抢到就是首次（CREATED）；抢不到说明这条之前已补拉过，
   * 于是比对内容指纹 —— 指纹相同是"重复推送"（DEDUPLICATED），指纹不同是"平台改了内容"（UPDATED）。
   * 二者必须分得开：混为一谈会把真实变更当成重复丢掉。
   */
  private BackfillRecordResult ingestOne(
      AccessService.Access actor,
      UUID taskId,
      UUID connectionId,
      String resourceType,
      BackfillRecordInput r) {

    String digest = nullToEmpty(r.payloadDigest());

    if (r.failureReason() != null && !r.failureReason().isBlank())
      return new BackfillRecordResult(r.externalKey(), "FAILED", null, null, r.failureReason());

    // 抢首次入库权。唯一索引是唯一裁判，并发下只有一个能抢到：
    // 抢到 = 首次（CREATED），抢不到 = 之前已补拉过（UPDATED / DEDUPLICATED）。
    boolean inserted =
        sql.fetchOne(
                """
                insert into backfill_record(id,tenant_id,first_task_id,connection_id,resource_type,
                  external_key,payload_digest,payload,external_occurred_at)
                values (?,?,?,?,?,?,?,?::jsonb,?::timestamptz)
                on conflict (tenant_id,connection_id,resource_type,external_key) do nothing
                returning id::text
                """,
                Ids.next(),
                actor.tenantId(),
                taskId,
                connectionId,
                resourceType,
                r.externalKey(),
                digest,
                redact(r.payload()),
                toUtc(r.externalOccurredAt()))
            != null;

    if (inserted)
      return new BackfillRecordResult(
          r.externalKey(),
          "CREATED",
          firstRecordId(actor, taskId, connectionId, resourceType, r.externalKey()),
          1,
          null);

    // 之前已补拉过：读回既有记录并比对指纹。
    Record prior =
        sql.fetchOne(
            "select id,payload_digest,seen_count from backfill_record"
                + " where tenant_id=? and connection_id=? and resource_type=? and external_key=?",
            actor.tenantId(),
            connectionId,
            resourceType,
            r.externalKey());
    if (prior == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "补拉记录台账状态异常，无法判定幂等结果");

    UUID priorId = prior.get("id", UUID.class);
    String priorDigest = nullToEmpty(prior.get("payload_digest", String.class));
    boolean sameContent = priorDigest.equals(digest);

    if (sameContent) {
      // 重复：只累加重见次数，不新增、不更新业务事实。
      sql.execute(
          "update backfill_record set seen_count=seen_count+1,last_seen_at=now()"
              + " where tenant_id=? and id=?",
          actor.tenantId(),
          priorId);
    } else {
      // 内容变了：更新指纹与报文，但**仍然不新增行** —— 外部记录 ID 没变，
      // 下游按外部键更新即可，第二次补拉不得变成第二张订单。
      sql.execute(
          "update backfill_record set payload_digest=?,payload=?::jsonb,external_occurred_at=?::timestamptz,"
              + " seen_count=seen_count+1,last_seen_at=now() where tenant_id=? and id=?",
          digest,
          redact(r.payload()),
          toUtc(r.externalOccurredAt()),
          actor.tenantId(),
          priorId);
    }

    int seen =
        prior.get("seen_count", Integer.class) == null
            ? 2
            : prior.get("seen_count", Integer.class) + 1;
    return new BackfillRecordResult(
        r.externalKey(), sameContent ? "DEDUPLICATED" : "UPDATED", priorId, seen, null);
  }

  private UUID firstRecordId(
      AccessService.Access actor, UUID taskId, UUID connectionId, String resourceType, String key) {
    return sql.fetchOne(
            "select id from backfill_record where tenant_id=? and connection_id=? and resource_type=?"
                + " and external_key=?",
            actor.tenantId(),
            connectionId,
            resourceType,
            key)
        .get("id", UUID.class);
  }

  // ------------------------------------------------------------------
  // 02.6.2.1 收尾与游标推进
  // ------------------------------------------------------------------

  /**
   * 收尾一次补拉。
   *
   * <p>只有 SUCCEEDED / PARTIAL 才推进关联游标：失败时保持原位，让下一次从同一处重来 ——
   * 与 2.4.1.2 完成一次运行同款纪律。补拉失败却把游标推过去，那段数据就永久丢了。
   */
  public void complete(AccessService.Access actor, UUID taskId, BackfillCompleteInput input) {
    Record task = loadTask(actor, taskId);
    String status = input.status().name();
    if ("PENDING".equals(status) || "RUNNING".equals(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收尾状态必须是终态");

    sql.execute(
        "update backfill_task set status=?,finished_at=now(),cursor_after=?,last_error=?,duration_ms=?,"
            + " version=version+1,updated_at=now() where tenant_id=? and id=?",
        status,
        nullToEmpty(input.cursorAfter()),
        nullToEmpty(input.lastError()),
        input.durationMs(),
        actor.tenantId(),
        taskId);

    boolean advance = "SUCCEEDED".equals(status) || "PARTIAL".equals(status);
    UUID cursorId = task.get("cursor_id", UUID.class);
    if (advance && cursorId != null && input.cursorAfter() != null && !input.cursorAfter().isBlank()) {
      sql.execute(
          "update sync_cursor set cursor_value=?,last_success_at=now(),consecutive_failures=0,"
              + " version=version+1,updated_at=now() where tenant_id=? and id=?",
          input.cursorAfter(),
          actor.tenantId(),
          cursorId);
    }
  }

  /** 人工中止。中止后任务不可再提交（终结记录由 DB 触发器保护）。 */
  public void cancel(AccessService.Access actor, UUID taskId) {
    int updated =
        sql.execute(
            "update backfill_task set status='CANCELLED',finished_at=now(),version=version+1,"
                + " updated_at=now() where tenant_id=? and id=? and status in ('PENDING','RUNNING')",
            actor.tenantId(),
            taskId);
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "补拉任务已终结，无法中止");
  }

  // ------------------------------------------------------------------
  // 02.6.2.1 查询
  // ------------------------------------------------------------------

  public List<BackfillTaskView> tasks(
      AccessService.Access actor, UUID connectionId, String status, int limit) {
    boolean byConnection = connectionId != null;
    boolean byStatus = status != null && !status.isBlank();
    int capped = Math.min(Math.max(limit, 1), 200);
    StringBuilder sqlText =
        new StringBuilder(
            "select t.id,t.connection_id,co.authorized_account,p.code platform_code,t.marketplace_id,"
                + " m.code marketplace_code,t.resource_type,t.window_start,t.window_end,t.trigger_source,"
                + " t.idempotency_key,t.cursor_id,t.pagination_mode,t.delayed_discovery,t.page_size,"
                + " t.max_pages,t.rate_limit_per_minute,t.status,t.fetched_count,t.created_count,"
                + " t.updated_count,t.deduplicated_count,t.failed_count,t.segments_done,t.segment_total,"
                + " t.cursor_after,t.last_error,t.duration_ms,t.started_at,t.finished_at,t.created_at"
                + " from backfill_task t join channel_connection co on co.tenant_id=t.tenant_id"
                + " and co.id=t.connection_id join platform p on p.id=co.platform_id"
                + " left join marketplace m on m.id=t.marketplace_id"
                + " where t.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(actor.tenantId());
    if (byConnection) {
      sqlText.append(" and t.connection_id=?");
      binds.add(connectionId);
    }
    if (byStatus) {
      sqlText.append(" and t.status=?");
      binds.add(status);
    }
    sqlText.append(" order by t.created_at desc limit ?");
    binds.add(capped);

    return sql.fetch(sqlText.toString(), binds.toArray()).stream().map(BackfillService::mapTask)
        .toList();
  }

  public BackfillTaskView task(AccessService.Access actor, UUID taskId) {
    loadTask(actor, taskId);
    return tasks(actor, null, null, 200).stream()
        .filter(t -> t.id().equals(taskId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补拉任务不存在"));
  }

  private Record loadTask(AccessService.Access actor, UUID taskId) {
    Record r =
        sql.fetchOne(
            "select id,connection_id,resource_type,status,page_size,segments_done,segment_total,"
                + "cursor_id,cursor_after from backfill_task where tenant_id=? and id=?",
            actor.tenantId(),
            taskId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "补拉任务不存在");
    return r;
  }

  private static BackfillTaskView mapTask(Record r) {
    return new BackfillTaskView(
        r.get("id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("authorized_account", String.class),
        r.get("platform_code", String.class),
        r.get("marketplace_id", UUID.class),
        r.get("marketplace_code", String.class),
        r.get("resource_type", String.class),
        r.get("window_start", Instant.class),
        r.get("window_end", Instant.class),
        r.get("trigger_source", String.class),
        r.get("idempotency_key", String.class),
        r.get("cursor_id", UUID.class),
        r.get("pagination_mode", String.class),
        Boolean.TRUE.equals(r.get("delayed_discovery", Boolean.class)),
        orZero(r.get("page_size", Integer.class)),
        orZero(r.get("max_pages", Integer.class)),
        orZero(r.get("rate_limit_per_minute", Integer.class)),
        r.get("status", String.class),
        orZero(r.get("fetched_count", Integer.class)),
        orZero(r.get("created_count", Integer.class)),
        orZero(r.get("updated_count", Integer.class)),
        orZero(r.get("deduplicated_count", Integer.class)),
        orZero(r.get("failed_count", Integer.class)),
        orZero(r.get("segments_done", Integer.class)),
        orZero(r.get("segment_total", Integer.class)),
        r.get("cursor_after", String.class),
        r.get("last_error", String.class),
        r.get("duration_ms", Integer.class),
        r.get("started_at", Instant.class),
        r.get("finished_at", Instant.class),
        r.get("created_at", Instant.class));
  }

  private static int orZero(Integer v) {
    return v == null ? 0 : v;
  }

  /** 补拉状态分布：让"补拉大面积失败"这类事故一眼可见。 */
  public List<BackfillStatusSummary> statusSummary(AccessService.Access actor) {
    return sql.fetch(
            "select status,count(*)::bigint total,min(created_at) oldest from backfill_task"
                + " where tenant_id=? group by status order by total desc",
            actor.tenantId())
        .stream()
        .map(
            r ->
                new BackfillStatusSummary(
                    r.get("status", String.class),
                    r.get("total", Long.class),
                    r.get("oldest", Instant.class)))
        .toList();
  }

  /**
   * 逐条处理记录：一条外部记录在台账里只有一行，重复补拉只让 {@code seenCount} 增长。
   * 「补到过几次」本身就是排查重复投递的第一现场。
   */
  public List<Record> records(AccessService.Access actor, UUID taskId, int limit) {
    int capped = Math.min(Math.max(limit, 1), 200);
    return sql.fetch(
            "select r.id,r.external_key,r.resource_type,r.payload_digest,r.external_occurred_at,"
                + "r.seen_count,r.last_seen_at,r.first_task_id,t.idempotency_key,"
                + " case when r.seen_count>1 then 'DEDUPLICATED' else 'CREATED' end disposition"
                + " from backfill_record r join backfill_task t on t.tenant_id=r.tenant_id"
                + " and t.id=r.first_task_id where r.tenant_id=? and r.first_task_id=?"
                + " order by r.created_at desc limit ?",
            actor.tenantId(),
            taskId,
            capped)
        .stream()
        .toList();
  }

  /** 分段轮询留痕：证明分段轮询真的发生，而不是一句口号。 */
  public List<Record> segments(AccessService.Access actor, UUID taskId) {
    return sql.fetch(
            "select id,segment_no,covered_from,covered_to,reached_window_start,record_count,"
                + "duplicate_count,rate_limited,polled_at from backfill_segment"
                + " where tenant_id=? and task_id=? order by segment_no",
            actor.tenantId(),
            taskId)
        .stream()
        .toList();
  }

  // ------------------------------------------------------------------
  // 02.6.2.1 漏推检测（触发源之一）
  // ------------------------------------------------------------------

  /**
   * 漏推检测：按 2.6.1.1 的接收记录找时间窗缺口。
   *
   * <p>判据是「窗口内一条推送都没收到」—— 只看这个连接在窗口之前最近一次推送的静默时长，
   * 静默越久越可疑。读的是同库 {@code webhook_receipt}（上游已交付），不跨服务。
   */
  public List<BackfillGap> gaps(
      AccessService.Access actor, UUID connectionId, String resourceType, int windowHours) {
    int hours = Math.min(Math.max(windowHours, 1), 720);
    long maxSilenceSeconds = (long) hours * 3600;
    return sql.fetch(
            """
            select ?::uuid connection_id, r.shop_id, ?::text resource_type,
                   ?::timestamptz window_start, ?::timestamptz window_end,
                   0::bigint receipts_in_window,
                   (extract(epoch from (?::timestamptz - coalesce(max(r.received_at),?::timestamptz))))::bigint silence_seconds
            from webhook_receipt r
            where r.tenant_id=? and r.connection_id=?
            having coalesce(max(r.received_at),?::timestamptz) < ?::timestamptz
                or max(r.received_at) is null
            """,
            connectionId,
            resourceType,
            Instant.now().minusSeconds(maxSilenceSeconds),
            Instant.now(),
            Instant.now().minusSeconds(maxSilenceSeconds),
            actor.tenantId(),
            connectionId,
            Instant.now().minusSeconds(maxSilenceSeconds),
            Instant.now())
        .stream()
        .map(
            r ->
                new BackfillGap(
                    connectionId,
                    r.get("shop_id", UUID.class),
                    resourceType,
                    r.get("window_start", Instant.class),
                    r.get("window_end", Instant.class),
                    0L,
                    r.get("silence_seconds", Long.class)))
        .toList();
  }

  // ------------------------------------------------------------------
  // 脱敏
  // ------------------------------------------------------------------

  /** 凭据类字段入库前剔除（与 2.6.1.1 同一套键名与纪律）。 */
  private String redact(String raw) {
    if (raw == null || raw.isBlank()) return "{}";
    try {
      JsonNode parsed = json.readTree(raw);
      return truncate(json.writeValueAsString(scrub(parsed)));
    } catch (Exception e) {
      // 报文不是合法 JSON：原样截断保留，不因为解析失败就丢线索
      return truncate(raw);
    }
  }

  private JsonNode scrub(JsonNode node) {
    if (node.isObject()) {
      ObjectNode copy = json.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (REDACTED_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                  copy.put(e.getKey(), "***");
                } else {
                  copy.set(e.getKey(), scrub(e.getValue()));
                }
              });
      return copy;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode copy = json.createArrayNode();
      node.forEach(item -> copy.add(scrub(item)));
      return copy;
    }
    return node;
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() <= MAX_PAYLOAD_CHARS ? s : s.substring(0, MAX_PAYLOAD_CHARS);
  }

  /**
   * timestamptz 一律用 OffsetDateTime + {@code ?::timestamptz} 绑定。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s —— 补拉的时间窗边界
   * 正是本模块的核心事实，偏 8 小时的窗口等于漏补/重补。
   */
  private static OffsetDateTime toUtc(OffsetDateTime v) {
    return v.toInstant().atOffset(ZoneOffset.UTC);
  }
}
