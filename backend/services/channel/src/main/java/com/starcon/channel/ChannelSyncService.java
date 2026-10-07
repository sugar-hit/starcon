package com.starcon.channel;

import static com.starcon.channel.ChannelSyncModels.*;

import com.starcon.tenancy.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.4 同步运行：游标、运行日志与失败队列。
 *
 * <p>本类唯一不可让步的不变量是 2.4.1.3 的"游标只在处理提交后推进"。它靠三件事共同保证，
 * 缺一件就会变成"看起来能续传、实际会漏单"：
 *
 * <ol>
 *   <li>{@link #commitRun} 在调用方已开启的同一个事务里同时写运行终态与推进游标。任何一步失败
 *       整体回滚，运行仍是 RUNNING、游标原封不动。
 *   <li>推进量不得早于本次起始水位，游标表上的方向守卫触发器再兜一层，
 *       因此正常路径不可能把水位拨回过去。
 *   <li>FAILED 运行只累计 consecutive_failures，不碰 cursor_value——下次从原水位重跑。
 * </ol>
 */
@Service
public class ChannelSyncService {
  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

  private final DSLContext sql;

  public ChannelSyncService(DSLContext sql) {
    this.sql = sql;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  /** jOOQ 3.19 只把 LocalDateTime 按原生类型发送；时区显式选 UTC，不依赖 JVM 默认时区。 */
  private static LocalDateTime utc(Instant at) {
    return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  /**
   * timestamptz 的绑定格式，带显式偏移量。
   *
   * <p>不能直接把 LocalDateTime 绑进 timestamptz：那样 PG 会按会话时区解释裸时间戳，JVM 在
   * Asia/Shanghai 而容器在 UTC 时，水位会整整偏 8 小时——表现为"游标推进到了错误的时间点"，
   * 断点续传从此开始漏单或重复拉。字符串自带偏移量，PG 按字面解析，与会话时区无关。
   * 写入处一律配合 {@code ?::timestamptz} 使用。
   */
  private static String ts(Instant at) {
    return at == null ? null : ISO_UTC.format(at);
  }

  private static Instant tsOf(Record r, String column) {
    OffsetDateTime v = r.get(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static LocalDateTime now() {
    return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  // ------------------------------------------------------------------
  // 2.4.1.1 同步任务列表
  // ------------------------------------------------------------------

  /**
   * 2.4.1.1 同步任务列表：每个（连接, 站点, 资源, 分区）一行。
   *
   * <p>粒度在数据库唯一键里已经保证，这里只负责把游标、最近运行与积压量拼成任务视图。
   */
  public List<SyncCursorView> cursors(String resourceType) {
    boolean filtered = resourceType != null && !resourceType.isBlank();
    Result<?> rows =
        sql.fetch(
            "select c.id,c.connection_id,c.marketplace_id,c.resource_type,c.partition_key,"
                + " c.cursor_value,c.external_marker,c.consecutive_failures,c.version,"
                + " r.status run_status,r.started_at run_started_at,"
                + " (select count(*) from sync_failure f"
                + "  where f.tenant_id=c.tenant_id and f.cursor_id=c.id and f.status in ('OPEN','REPLAYING')) backlog"
                + " from sync_cursor c"
                + " left join sync_run r on r.tenant_id=c.tenant_id and r.id=c.last_run_id"
                + " where true"
                + (filtered ? " and c.resource_type=?" : "")
                + " order by r.started_at desc nulls last,c.created_at",
            filtered ? new Object[] {resourceType} : new Object[0]);
    return rows.stream().map(r -> mapCursor(r)).toList();
  }

  private static SyncCursorView mapCursor(Record r) {
    return new SyncCursorView(
        r.get("id", UUID.class),
        new SyncTaskKey(
            r.get("connection_id", UUID.class),
            r.get("marketplace_id", UUID.class),
            ResourceType.valueOf(r.get("resource_type", String.class)),
            r.get("partition_key", String.class)),
        new SyncWatermark(tsOf(r, "cursor_value"), r.get("external_marker", String.class)),
        r.get("backlog", Integer.class),
        r.get("consecutive_failures", Integer.class),
        r.get("run_status", String.class),
        tsOf(r, "run_started_at"),
        r.get("version", Long.class));
  }

  // ------------------------------------------------------------------
  // 2.4.1.2 运行日志
  // ------------------------------------------------------------------

  public List<SyncRunView> runs(UUID cursorId, int limit) {
    int capped = Math.clamp(limit, 1, 500);
    Result<?> rows =
        sql.fetch(
            "select r.*,c.partition_key,"
                + " (select count(*) from sync_failure f"
                + "  where f.tenant_id=r.tenant_id and f.run_id=r.id and f.status in ('OPEN','REPLAYING')) open_failures"
                + " from sync_run r join sync_cursor c on c.tenant_id=r.tenant_id and c.id=r.cursor_id"
                + (cursorId == null ? "" : " where r.cursor_id=?")
                + " order by r.started_at desc limit ?",
            cursorId == null ? new Object[] {capped} : new Object[] {cursorId, capped});
    return rows.stream().map(r -> mapRun(r)).toList();
  }

  private static SyncRunView mapRun(Record r) {
    return new SyncRunView(
        r.get("id", UUID.class),
        new SyncTaskKey(
            r.get("connection_id", UUID.class),
            r.get("marketplace_id", UUID.class),
            ResourceType.valueOf(r.get("resource_type", String.class)),
            r.get("partition_key", String.class)),
        r.get("trigger_kind", String.class),
        r.get("status", String.class),
        tsOf(r, "started_at"),
        tsOf(r, "finished_at"),
        r.get("duration_ms", Long.class),
        r.get("fetched_count", Integer.class),
        r.get("written_count", Integer.class),
        r.get("updated_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("warning_count", Integer.class),
        r.get("error_count", Integer.class),
        r.get("error_message", String.class),
        new SyncWatermark(tsOf(r, "cursor_before"), r.get("cursor_marker_before", String.class)),
        tsOf(r, "cursor_after") == null
            ? null
            : new SyncWatermark(tsOf(r, "cursor_after"), r.get("cursor_marker_after", String.class)),
        r.get("open_failures", Integer.class));
  }

  // ------------------------------------------------------------------
  // 2.4.1.3 断点续传
  // ------------------------------------------------------------------

  /**
   * 2.4.1.3 开始一次运行。
   *
   * <p>同一游标已有 RUNNING 时直接拒绝：两个并发运行会各自推进水位，彼此跳过对方没覆盖的区间，
   * 而这种漏单在日志里看不出来。
   *
   * <p>{@code from} 早于当前水位时按 ROLLBACK_REPLAY 处理——重拉窗口不拥有水位，水位只由正常提交
   * 或显式回退端点改变。这样"往回拉数据"不会绕过游标方向守卫。
   */
  public UUID beginRun(AccessService.Access actor, SyncRunInput input) {
    String resourceType = input.resourceType().name();
    String partition = nz(input.partitionKey());
    requireSyncedSite(input.connectionId(), input.marketplaceId());

    UUID cursorId = cursorId(input.connectionId(), input.marketplaceId(), resourceType, partition);
    if (cursorId == null) {
      // on conflict do nothing：并发首次运行时只有一个赢家，另一个随后读到同一行，
      // 而不是在唯一键上炸成 500。
      sql.execute(
          "insert into sync_cursor(id,tenant_id,connection_id,marketplace_id,resource_type,partition_key)"
              + " values (?,?,?,?,?,?) on conflict do nothing",
          Ids.next(),
          actor.tenantId(),
          input.connectionId(),
          input.marketplaceId(),
          resourceType,
          partition);
      cursorId = cursorId(input.connectionId(), input.marketplaceId(), resourceType, partition);
    }

    UUID active =
        one(
            UUID.class,
            "select id from sync_run where tenant_id=? and cursor_id=? and status='RUNNING'",
            actor.tenantId(),
            cursorId);
    if (active != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该同步任务已有进行中的运行，请等待其结束");

    Record cursor =
        sql.fetchOne(
            "select cursor_value,external_marker from sync_cursor where tenant_id=? and id=?",
            actor.tenantId(),
            cursorId);
    Instant stored = tsOf(cursor, "cursor_value");
    boolean replayWindow = input.from() != null && input.from().isBefore(stored);
    String trigger =
        replayWindow
            ? "ROLLBACK_REPLAY"
            : nz(input.triggerKind()).isBlank() ? "SCHEDULED" : input.triggerKind();

    UUID runId = Ids.next();
    sql.execute(
        "insert into sync_run(id,tenant_id,cursor_id,connection_id,marketplace_id,resource_type,"
            + " trigger_kind,status,cursor_before,cursor_marker_before,started_at)"
            + " values (?,?,?,?,?,?,?,'RUNNING',?::timestamptz,?,?)",
        runId,
        actor.tenantId(),
        cursorId,
        input.connectionId(),
        input.marketplaceId(),
        resourceType,
        trigger,
        ts(stored),
        cursor.get("external_marker", String.class),
        now());
    return runId;
  }

  /**
   * 2.4.1.3 提交运行：运行终态与游标推进在同一个事务里完成。
   *
   * <p>这是"游标只在处理提交后推进"真正落地的地方——调用方通过 {@link AccessService#run} 提供的
   * 事务执行本方法，方法内任何异常都会把运行状态与游标一起回滚。
   */
  public SyncRunView commitRun(AccessService.Access actor, UUID runId, SyncRunCommit commit) {
    Record run =
        sql.fetchOne(
            "select id,cursor_id,connection_id,marketplace_id,resource_type,trigger_kind,status,cursor_before,started_at"
                + " from sync_run where tenant_id=? and id=?",
            actor.tenantId(),
            runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步运行不存在");
    if (!"RUNNING".equals(run.get("status", String.class)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "同步运行已结束，不能重复提交");

    String status = normalizeStatus(commit.status());
    Instant cursorBefore = tsOf(run, "cursor_before");
    Instant startedAt = tsOf(run, "started_at");
    boolean replayWindow = "ROLLBACK_REPLAY".equals(run.get("trigger_kind", String.class));
    LocalDateTime finishedAt = now();

    Long duration = Duration.between(startedAt, finishedAt.toInstant(ZoneOffset.UTC)).toMillis();
    Instant cursorAfter = null;
    Instant target = commit.cursorValue();

    if (status.equals("FAILED")) {
      // 失败不动水位：下一次从原处重跑，已成功的部分不重复拉。
      if (target != null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失败的运行不得推进游标，续传会由下次运行接管");
    } else if (replayWindow) {
      // 重拉窗口是幂等补数，不拥有水位。
      if (target != null && target.isAfter(cursorBefore))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重放窗口不得推进游标，请先回退游标再运行");
    } else {
      if (target == null) {
        cursorAfter = cursorBefore;
      } else if (target.isBefore(cursorBefore)) {
        // 水位只进不退。静默忽略会掩盖调用方丢失运行上下文的 bug，所以显式拒绝。
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "提交的游标早于本次起始水位，请先通过回退端点重置游标");
      } else {
        cursorAfter = target;
      }
    }

    sql.execute(
        "update sync_run set status=?,finished_at=?,duration_ms=?,fetched_count=?,written_count=?,"
            + " updated_count=?,skipped_count=?,warning_count=?,error_count=?,error_message=?,"
            + " cursor_after=?::timestamptz,cursor_marker_after=?,version=version+1 where tenant_id=? and id=?",
        status,
        finishedAt,
        duration,
        commit.fetchedCount(),
        commit.writtenCount(),
        commit.updatedCount(),
        commit.skippedCount(),
        commit.warningCount(),
        commit.errorCount(),
        nz(commit.errorMessage()),
        cursorAfter == null ? null : ts(cursorAfter),
        cursorAfter == null ? "" : nz(commit.externalMarkerTo()),
        actor.tenantId(),
        runId);

    if (cursorAfter != null) {
      // 与运行终态同一事务：这里抛错会连带回滚运行状态，游标原地不动。
      sql.execute(
          "update sync_cursor set cursor_value=?::timestamptz,external_marker=?,consecutive_failures=0,last_run_id=?,"
              + " version=version+1,updated_at=? where tenant_id=? and id=?",
          ts(cursorAfter),
          nz(commit.externalMarkerTo()),
          runId,
          now(),
          actor.tenantId(),
          run.get("cursor_id", UUID.class));
    } else {
      sql.execute(
          "update sync_cursor set consecutive_failures=case when ?='FAILED' then consecutive_failures+1 else consecutive_failures end,"
              + " last_run_id=?,version=version+1,updated_at=? where tenant_id=? and id=?",
          status,
          runId,
          now(),
          actor.tenantId(),
          run.get("cursor_id", UUID.class));
    }

    return runs(run.get("cursor_id", UUID.class), 1).stream()
        .filter(v -> v.id().equals(runId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "同步运行不存在"));
  }

  /** 2.4.1.3 标记运行失败：只累计连续失败数，水位保持不动，等下次从断点续传。 */
  public void failRun(AccessService.Access actor, UUID runId, String error) {
    commitRun(actor, runId, new SyncRunCommit("FAILED", 0, 0, 0, 0, 0, 1, nz(error), null, ""));
  }

  /**
   * 2.4.1.3 游标回退：处理"漏了一小时数据"与"游标跑飞"两类事故。
   *
   * <p>回退是改事实来源的动作，因此显式声明意图让触发器放行，并留下可审计的一行：回退后
   * consecutive_failures 清零，避免一次人工回退被后续诊断误读成"连接已经好了"。
   */
  public void rewindCursor(AccessService.Access actor, UUID cursorId, SyncCursorRewindInput input) {
    Instant current =
        one(
            Instant.class,
            "select cursor_value from sync_cursor where tenant_id=? and id=?",
            actor.tenantId(),
            cursorId);
    if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "游标不存在");
    if (input.to().isAfter(current))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回退目标晚于当前水位，回退只能向过去");
    sql.fetch(
        "select set_config('app.cursor_rollback','true',true)");
    sql.execute(
        "update sync_cursor set cursor_value=?::timestamptz,external_marker=?,consecutive_failures=0,"
            + " version=version+1,updated_at=? where tenant_id=? and id=?",
        ts(input.to()),
        nz(input.externalMarker()),
        now(),
        actor.tenantId(),
        cursorId);
  }

  // ------------------------------------------------------------------
  // 2.4.2 异常处理
  // ------------------------------------------------------------------

  /** 2.4.2.1 失败队列。 */
  public List<SyncFailureView> failures(String reason, boolean openOnly) {
    boolean filtered = reason != null && !reason.isBlank();
    StringBuilder where = new StringBuilder(" where true");
    List<Object> binds = new java.util.ArrayList<>();
    if (openOnly) where.append(" and f.status in ('OPEN','REPLAYING')");
    if (filtered) {
      where.append(" and f.failure_reason=?");
      binds.add(reason);
    }
    where.append(" order by f.created_at desc limit 500");
    String query =
        "select f.id,f.run_id,f.cursor_id,f.connection_id,c.marketplace_id,f.resource_type,"
            + " f.failure_reason,f.external_record_key,f.external_payload_ref,f.attempt_count,"
            + " f.status,f.escalated,f.last_error,f.created_at,f.resolved_at"
            + " from sync_failure f"
            + " join sync_cursor c on c.tenant_id=f.tenant_id and c.id=f.cursor_id"
            + where;
    // jOOQ 的纯文本 SQL 接口在绑定值数量与 ? 个数不匹配时会把多余的值内联进语句，
    // 产生 "limit 500[Ljava.lang.Object;@..." 这种语法错误，因此无过滤条件时必须整体不传绑定值。
    Result<?> rows =
        binds.isEmpty() ? sql.fetch(query) : sql.fetch(query, binds.toArray());
    return rows.stream().map(r -> mapFailure(r)).toList();
  }

  private static SyncFailureView mapFailure(Record r) {
    return new SyncFailureView(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        new SyncTaskKey(
            r.get("connection_id", UUID.class),
            r.get("marketplace_id", UUID.class),
            ResourceType.valueOf(r.get("resource_type", String.class)),
            ""),
        r.get("failure_reason", String.class),
        r.get("external_record_key", String.class),
        r.get("external_payload_ref", String.class),
        r.get("attempt_count", Integer.class),
        r.get("status", String.class),
        r.get("escalated", Boolean.class),
        r.get("last_error", String.class),
        tsOf(r, "created_at"),
        tsOf(r, "resolved_at"));
  }

  /**
   * 2.4.2.1 登记失败，按外部记录幂等。
   *
   * <p>同租户同连接同资源的同一条外部记录只保留一条未关闭的失败：重复处理（比如 webhook 重投加
   * 定时补拉撞车）只会累加尝试次数，不会堆出第二张待办。
   */
  public UUID recordFailure(AccessService.Access actor, SyncFailureRecordInput input) {
    Record run =
        sql.fetchOne(
            "select cursor_id,connection_id,marketplace_id,resource_type"
                + " from sync_run where tenant_id=? and id=?",
            actor.tenantId(),
            input.runId());
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步运行不存在");

    // 2.4.2.1：授权失效必然升级为工作台待办——它导致持续漏单而不是单条失败。
    boolean escalated = input.failureReason() == FailureReason.AUTH_EXPIRED;
    UUID id = Ids.next();
    Result<?> inserted =
        sql.fetch(
            "insert into sync_failure(id,tenant_id,run_id,cursor_id,connection_id,"
                + " resource_type,failure_reason,external_record_key,external_payload_ref,escalated,last_error)"
                + " values (?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing returning id",
            id,
            actor.tenantId(),
            input.runId(),
            run.get("cursor_id", UUID.class),
            run.get("connection_id", UUID.class),
            run.get("resource_type", String.class),
            input.failureReason().name(),
            input.externalRecordKey(),
            nz(input.externalPayloadRef()),
            escalated,
            nz(input.lastError()));

    if (!inserted.isEmpty()) return id;

    // 已有未关闭的同一条：复用这一行，只累加尝试次数。
    UUID existing =
        one(
            UUID.class,
            "select id from sync_failure where tenant_id=? and connection_id=? and resource_type=?"
                + " and external_record_key=? and status in ('OPEN','REPLAYING')",
            actor.tenantId(),
            run.get("connection_id", UUID.class),
            run.get("resource_type", String.class),
            input.externalRecordKey());
    if (existing == null)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该外部记录已有已关闭的失败记录，请先重新同步或显式重开");
    sql.execute(
        "update sync_failure set attempt_count=attempt_count+1,last_error=?,escalated=escalated or ?,"
            + " version=version+1,updated_at=? where tenant_id=? and id=?",
        nz(input.lastError()),
        escalated,
        now(),
        actor.tenantId(),
        existing);
    return existing;
  }

  /** 2.4.2.1 失败原因分布：按聚类维度汇总，授权失效单独带出待办量。 */
  public List<FailureClusterView> failureClusters() {
    Result<?> rows =
        sql.fetch(
            "select failure_reason,"
                + " count(*) filter (where status in ('OPEN','REPLAYING')) open,"
                + " count(*) filter (where escalated and status in ('OPEN','REPLAYING')) escalated,"
                + " count(*) total"
                + " from sync_failure group by failure_reason order by open desc,total desc");
    return rows.stream()
        .map(
            r ->
                new FailureClusterView(
                    r.get("failure_reason", String.class),
                    r.get("open", Long.class),
                    r.get("escalated", Long.class),
                    r.get("total", Long.class)))
        .toList();
  }

  /**
   * 2.4.2.2 人工重放。
   *
   * <p>重放只改这一行的状态，不新建失败行：重复点重放不会产生第二张待办，attempt_count 如实累加，
   * 重放仍失败则回到 OPEN 并保留新原因，便于按原因聚类。
   */
  public SyncFailureView replayFailure(AccessService.Access actor, UUID failureId, SyncFailureReplayInput input) {
    Record row =
        sql.fetchOne(
            "select f.id,f.run_id,f.cursor_id,f.connection_id,c.marketplace_id,f.resource_type,"
                + " f.failure_reason,f.external_record_key,f.external_payload_ref,f.escalated,f.status"
                + " from sync_failure f"
                + " join sync_cursor c on c.tenant_id=f.tenant_id and c.id=f.cursor_id"
                + " where f.tenant_id=? and f.id=?",
            actor.tenantId(),
            failureId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "失败记录不存在");
    String status = row.get("status", String.class);
    if (!"OPEN".equals(status) && !"REPLAYING".equals(status))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该失败记录已关闭，不能重放");

    String outcome = normalizeOutcome(input.outcome());
    int attempts = input.attemptCount() == null ? 1 : Math.max(1, input.attemptCount());
    String nextStatus = switch (outcome) {
      case "SUCCESS" -> "RESOLVED";
      case "IGNORE" -> "IGNORED";
      default -> "OPEN";
    };
    sql.execute(
        "update sync_failure set status=?,attempt_count=attempt_count+?,last_error=?,resolved_at=?,"
            + " version=version+1,updated_at=? where tenant_id=? and id=?",
        nextStatus,
        attempts,
        outcome.equals("STILL_FAILING") ? nz(input.lastError()) : "",
        outcome.equals("SUCCESS") || outcome.equals("IGNORE") ? now() : null,
        now(),
        actor.tenantId(),
        failureId);

    return failures("", false).stream()
        .filter(f -> f.id().equals(failureId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "失败记录不存在"));
  }

  // ------------------------------------------------------------------
  // 内部校验
  // ------------------------------------------------------------------

  /**
   * 站点必须是该连接真正启用的站点。
   *
   * <p>否则就允许"拿美国站的连接去跑德国站订单"这种组合：连接与站点各自合法，拼在一起却把 A 站
   * 的订单写进 B 站的归属，事后只能靠对账发现。
   */
  private void requireSyncedSite(UUID connectionId, UUID marketplaceId) {
    long enabled =
        count(
            "select count(*) from shop_marketplace sm"
                + " join shop s on s.tenant_id=sm.tenant_id and s.id=sm.shop_id"
                + " where sm.tenant_id=? and sm.marketplace_id=? and sm.status='ACTIVE'"
                + " and s.connection_id=?",
            currentTenant(),
            marketplaceId,
            connectionId);
    if (enabled == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该连接未在此站点启用店铺，无法按此粒度同步");
  }

  private UUID cursorId(UUID connectionId, UUID marketplaceId, String resourceType, String partition) {
    return one(
        UUID.class,
        "select id from sync_cursor where connection_id=? and marketplace_id=? and resource_type=?"
            + " and partition_key=?",
        connectionId,
        marketplaceId,
        resourceType,
        partition);
  }

  private UUID currentTenant() {
    return one(
        UUID.class,
        "select nullif(current_setting('app.current_tenant',true),'')::uuid");
  }

  private static String normalizeStatus(String status) {
    String s = nz(status).toUpperCase();
    if (!List.of("SUCCEEDED", "PARTIAL", "FAILED").contains(s))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行终态只能是 SUCCEEDED / PARTIAL / FAILED");
    return s;
  }

  private static String normalizeOutcome(String outcome) {
    String s = nz(outcome).toUpperCase();
    if (!List.of("SUCCESS", "STILL_FAILING", "IGNORE").contains(s))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重放结果只能是 SUCCESS / STILL_FAILING / IGNORE");
    return s;
  }
}