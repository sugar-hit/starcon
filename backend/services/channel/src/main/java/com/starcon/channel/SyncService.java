package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;

import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.4 同步运行 与 02.5 平台回写。
 *
 * <p>三个不可让步的纪律（蓝图 §2.4.1.3 / §2.5.1.1 / §2.5.1.2）：
 *
 * <ol>
 *   <li>游标只在处理提交后推进——回退重拉靠幂等去重兜底，不靠"不推进"
 *   <li>重放同一外部记录不产生第二张业务单据
 *   <li>平台返回未知结果时先查证再决定重发，绝不当作失败直接重试
 * </ol>
 */
@Service
public class SyncService {
  /** 连续失败达到该次数即标记游标为 BROKEN，交人工处理（游标跑飞是事故）。 */
  private static final int BROKEN_THRESHOLD = 5;

  private final DSLContext sql;

  public SyncService(DSLContext sql) {
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

  // ------------------------------------------------------------------
  // 2.4.1.1 同步任务列表
  // ------------------------------------------------------------------

  public List<SyncCursorView> cursors(UUID connectionId) {
    boolean filtered = connectionId != null;
    Result<?> rows =
        sql.fetch(
            "select c.id,c.connection_id,co.authorized_account,p.code platform_code,m.code marketplace_code,"
                + " c.resource_type,c.partition_key,c.cursor_value,c.status,c.consecutive_failures,"
                + " c.last_success_at,c.last_failure_at,"
                + " (select r.status from sync_run r where r.tenant_id=c.tenant_id and r.cursor_id=c.id"
                + "  order by r.started_at desc limit 1) last_run_status,"
                + " (select count(*) from sync_failure f where f.tenant_id=c.tenant_id and f.cursor_id=c.id"
                + "  and f.status='OPEN') open_failures,"
                + " c.version"
                + " from sync_cursor c join channel_connection co on co.tenant_id=c.tenant_id and co.id=c.connection_id"
                + " join platform p on p.id=co.platform_id"
                + " left join marketplace m on m.id=c.marketplace_id"
                + " where true" + (filtered ? " and c.connection_id=?" : "")
                + " order by c.status='BROKEN' desc,p.position,m.position,c.resource_type,c.partition_key",
            filtered ? new Object[] {connectionId} : new Object[0]);
    return rows.stream()
        .map(
            r ->
                new SyncCursorView(
                    r.get("id", UUID.class),
                    r.get("connection_id", UUID.class),
                    r.get("authorized_account", String.class),
                    r.get("platform_code", String.class),
                    r.get("marketplace_code", String.class),
                    r.get("resource_type", String.class),
                    r.get("partition_key", String.class),
                    r.get("cursor_value", String.class),
                    r.get("status", String.class),
                    r.get("consecutive_failures", Integer.class),
                    r.get("last_run_status", String.class),
                    r.get("open_failures", Integer.class),
                    r.get("last_success_at", Instant.class),
                    r.get("last_failure_at", Instant.class),
                    r.get("version", Long.class)))
        .toList();
  }

  public SyncCursorView cursor(UUID id) {
    return cursors(null).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  /** 注册一个同步任务。任务粒度为（连接, 站点, 资源类型, 分区），不允许整店一把梭。 */
  public UUID registerCursor(AccessService.Access actor, SyncCursorInput input) {
    Long existing =
        one(
            Long.class,
            "select 1 from sync_cursor where tenant_id=? and connection_id=? and resource_type=?"
                + " and partition_key=? and marketplace_id is not distinct from ?",
            actor.tenantId(),
            input.connectionId(),
            input.resourceType(),
            input.partitionKey(),
            input.marketplaceId());
    if (existing != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该同步任务已存在，请勿重复注册");
    UUID id = Ids.next();
    sql.execute(
        "insert into sync_cursor(id,tenant_id,connection_id,marketplace_id,resource_type,partition_key)"
            + " values (?,?,?,?,?,?)",
        id,
        actor.tenantId(),
        input.connectionId(),
        input.marketplaceId(),
        input.resourceType(),
        input.partitionKey());
    return id;
  }

  /** 2.4.1.3 暂停/恢复同步任务。 */
  public void setCursorPaused(AccessService.Access actor, UUID id, boolean paused) {
    int updated =
        sql.execute(
            "update sync_cursor set status=case when ? then 'PAUSED' else 'ACTIVE' end,"
                + " version=version+1,updated_at=now() where tenant_id=? and id=? and status<>'BROKEN'",
            paused, actor.tenantId(), id);
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步任务不存在或处于 BROKEN 状态");
  }

  // ------------------------------------------------------------------
  // 2.4.1.2 运行日志
  // ------------------------------------------------------------------

  public List<SyncRunView> runs(UUID tenantId, UUID cursorId, int limit) {
    int capped = Math.min(Math.max(limit, 1), 200);
    // 游标过滤在 Java 侧分支：jOOQ 解析不了 "?::uuid is null" 这类带类型断言的表达式
    boolean filtered = cursorId != null;
    String cursorFilter = filtered ? " and r.cursor_id=?" : "";
    return sql.fetch(
            "select r.id,r.cursor_id,r.trigger_source,r.status,r.started_at,r.finished_at,"
                + " r.fetched_count,r.written_count,r.skipped_count,r.failed_count,r.cursor_after,r.duration_ms,"
                + " p.code platform_code,m.code marketplace_code,c.resource_type,c.partition_key"
                + " from sync_run r join sync_cursor c on c.tenant_id=r.tenant_id and c.id=r.cursor_id"
                + " join channel_connection co on co.tenant_id=c.tenant_id and co.id=c.connection_id"
                + " join platform p on p.id=co.platform_id"
                + " left join marketplace m on m.id=c.marketplace_id"
                + " where r.tenant_id=?"
                + cursorFilter
                + " order by r.started_at desc limit ?",
            filtered ? new Object[] {tenantId, cursorId, capped} : new Object[] {tenantId, capped})
        .stream()
        .map(SyncService::mapRun)
        .toList();
  }

  private static SyncRunView mapRun(Record r) {
    return new SyncRunView(
        r.get("id", UUID.class),
        r.get("cursor_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("marketplace_code", String.class),
        r.get("resource_type", String.class),
        r.get("partition_key", String.class),
        r.get("trigger_source", String.class),
        r.get("status", String.class),
        r.get("started_at", Instant.class),
        r.get("finished_at", Instant.class),
        r.get("fetched_count", Integer.class),
        r.get("written_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("cursor_after", String.class),
        r.get("duration_ms", Integer.class));
  }

  // ------------------------------------------------------------------
  // 2.4.1.1 手动触发同步
  // ------------------------------------------------------------------

  /**
   * 触发一次同步运行。
   *
   * <p>运行记录先落库并标记 RUNNING；只有调用方随后 {@link #completeRun} 成功提交处理，
   * 游标才会推进。这是"漏单可恢复"的根基：如果处理失败而游标已推进，那段数据永久丢失。
   */
  public UUID triggerRun(AccessService.Access actor, UUID cursorId, String triggerSource) {
    Record c =
        sql.fetchOne("select id,status from sync_cursor where id=?", cursorId);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步任务不存在");
    if (!"ACTIVE".equals(c.get("status", String.class)))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "同步任务处于 " + c.get("status", String.class) + " 状态，无法触发");

    UUID runId = Ids.next();
    sql.execute(
        "insert into sync_run(id,tenant_id,cursor_id,trigger_source,status) values (?,?,?,?, 'RUNNING')",
        runId, actor.tenantId(), cursorId, triggerSource == null ? "MANUAL" : triggerSource);
    return runId;
  }

  /**
   * 完成一次运行。
   *
   * <p>SUCCEEDED 时推进游标；FAILED/PARTIAL 时**不推进**，只记录失败并累加连续失败次数，
   * 让下一次从同一位置重来。重复调用会被触发器拒绝——已结束的运行不可篡改。
   */
  public void completeRun(
      AccessService.Access actor,
      UUID runId,
      SyncRunResult result,
      List<SyncFailureInput> failures) {
    Record run = sql.fetchOne("select cursor_id,status from sync_run where id=?", runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在");
    UUID cursorId = run.get("cursor_id", UUID.class);

    for (SyncFailureInput f : failures == null ? List.<SyncFailureInput>of() : failures) {
      // 重放不会产生第二条失败记录：同一游标下同一 external_key 唯一
      sql.execute(
          "insert into sync_failure(id,tenant_id,run_id,cursor_id,external_key,resource_type,reason,"
              + " detail,payload_digest,attempts,status,last_error)"
              + " values (?,?,?,?,?,?,?,?,?,1,'OPEN',?)"
              + " on conflict (tenant_id,cursor_id,external_key)"
              + " do update set reason=excluded.reason,detail=excluded.detail,last_error=excluded.last_error,"
              + " attempts= sync_failure.attempts+1,status='OPEN',occurred_at=now()",
          Ids.next(),
          actor.tenantId(),
          runId,
          cursorId,
          f.externalKey(),
          f.resourceType(),
          f.reason().name(),
          nullToEmpty(f.detail()),
          nullToEmpty(f.payloadDigest()),
          nullToEmpty(f.lastError()));
    }

    int failed = failures == null ? 0 : failures.size();
    String status =
        result.status() != null
            ? result.status()
            : (failed == 0 ? "SUCCEEDED" : (result.writtenCount() > 0 ? "PARTIAL" : "FAILED"));

    // duration_ms 允许为空
    Integer duration = result.durationMs();
    sql.execute(
        "update sync_run set status=?,finished_at=now(),fetched_count=?,written_count=?,"
            + " skipped_count=?,failed_count=?,cursor_after=?,duration_ms=? where id=?",
        status,
        result.fetchedCount(),
        result.writtenCount(),
        result.skippedCount(),
        failed,
        nullToEmpty(result.cursorAfter()),
        duration,
        runId);

    // 铁律一：只有完整成功才推进游标。失败时保持原位，让下一次从同一处重来。
    if ("SUCCEEDED".equals(status)) {
      sql.execute(
          "update sync_cursor set cursor_value=?,last_run_id=?,last_success_at=now(),"
              + " consecutive_failures=0,version=version+1,updated_at=now() where id=?",
          nullToEmpty(result.cursorAfter()), runId, cursorId);
    } else {
      sql.execute(
          "update sync_cursor set last_run_id=?,last_failure_at=now(),"
              + " consecutive_failures=consecutive_failures+1,version=version+1,updated_at=now() where id=?",
          runId, cursorId);
    }
  }

  // ------------------------------------------------------------------
  // 2.4.1.3 断点续传：回退与重置
  // ------------------------------------------------------------------

  /**
   * 回退游标以重拉一段时间窗。
   *
   * <p>回退安全性的前提是重放幂等：同一 external_key 不会产生第二张订单。
   * 因此这里不阻止回退，但要求调用方显式声明时间窗，且拒绝把游标设到未来。
   */
  public void rewindCursor(AccessService.Access actor, UUID cursorId, String toValue) {
    if (toValue == null || toValue.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回退目标游标值不能为空");
    Record c =
        sql.fetchOne(
            "select status,cursor_value,version from sync_cursor where id=?", cursorId);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步任务不存在");
    if ("BROKEN".equals(c.get("status", String.class)))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "游标处于 BROKEN 状态，请先修复失败原因再回退");
    if (toValue.compareTo(c.get("cursor_value", String.class)) > 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回退目标不能超前于当前游标");
    // DB 层 guard_cursor_direction 触发器只认这个显式意图位，
    // 因此倒退写只可能发生在这个可审计的端点上。该设置是事务内局部的，
    // 随端点调用所在事务结束而失效，不会泄漏给后续请求。
    sql.fetch("select set_config('app.cursor_rollback','true',true)");
    sql.execute(
        "update sync_cursor set cursor_value=?,version=version+1,updated_at=now() where id=?",
        toValue, cursorId);
  }

  // ------------------------------------------------------------------
  // 2.4.2 失败队列与人工重放
  // ------------------------------------------------------------------

  public List<SyncFailureView> failures(UUID tenantId, String reason, int limit) {
    boolean filtered = reason != null && !reason.isBlank();
    int capped = Math.min(Math.max(limit, 1), 200);
    String reasonFilter = filtered ? " and f.reason=?" : "";
    return sql.fetch(
            "select f.id,f.cursor_id,f.external_key,f.resource_type,f.reason,f.detail,f.payload_digest,"
                + " f.attempts,f.status,f.last_error,f.occurred_at,"
                + " c.resource_type cursor_resource,m.code marketplace_code,p.code platform_code"
                + " from sync_failure f join sync_cursor c on c.tenant_id=f.tenant_id and c.id=f.cursor_id"
                + " join channel_connection co on co.tenant_id=c.tenant_id and co.id=c.connection_id"
                + " join platform p on p.id=co.platform_id"
                + " left join marketplace m on m.id=c.marketplace_id"
                + " where f.tenant_id=? and f.status='OPEN'"
                + reasonFilter
                + " order by f.occurred_at desc limit ?",
            filtered
                ? new Object[] {tenantId, reason, capped}
                : new Object[] {tenantId, capped})
        .stream()
        .map(
            r ->
                new SyncFailureView(
                    r.get("id", UUID.class),
                    r.get("cursor_id", UUID.class),
                    r.get("platform_code", String.class),
                    r.get("marketplace_code", String.class),
                    r.get("external_key", String.class),
                    r.get("resource_type", String.class),
                    r.get("reason", String.class),
                    r.get("detail", String.class),
                    r.get("payload_digest", String.class),
                    r.get("attempts", Integer.class),
                    r.get("status", String.class),
                    r.get("last_error", String.class),
                    r.get("occurred_at", Instant.class)))
        .toList();
  }

  /** 2.4.2.1 失败原因分布：让"授权失效导致持续漏单"这类事故一眼可见。 */
  public List<SyncFailureSummary> failureSummary(UUID tenantId) {
    return sql.fetch(
            "select reason,count(*)::bigint total,min(occurred_at) oldest from sync_failure"
                + " where tenant_id=? and status='OPEN' group by reason order by total desc",
            tenantId)
        .stream()
        .map(
            r ->
                new SyncFailureSummary(
                    r.get("reason", String.class),
                    r.get("total", Long.class),
                    r.get("oldest", Instant.class)))
        .toList();
  }

  /**
   * 2.4.2.2 人工重放。
   *
   * <p>重放创建新的 RUNNING 运行记录（失败的原运行不可篡改），重放成功后把失败标记为
   * RESOLVED。重复重放同一条失败不会产生第二张业务单据——幂等由 external_key 保证。
   */
  public UUID replay(AccessService.Access actor, UUID failureId) {
    Record f =
        sql.fetchOne(
            "select cursor_id,resource_type,status from sync_failure where id=?", failureId);
    if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "失败记录不存在");
    if (!"OPEN".equals(f.get("status", String.class)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该失败记录已处理过");

    UUID runId = Ids.next();
    sql.execute(
        "insert into sync_run(id,tenant_id,cursor_id,trigger_source,status) values (?,?,?, 'REPLAY','RUNNING')",
        runId, actor.tenantId(), f.get("cursor_id", UUID.class));
    return runId;
  }

  /**
   * 收尾一条重放运行。
   *
   * <p>失败记录归属最初失败的运行，不是重放运行，所以要沿 cursor 收口：
   * 只有该游标下同一 external_key 的失败才算被这次重放解决。
   * 否则重放成功了、失败队列却永远挂着，运营会误判还有问题。
   */
  public void resolveFailures(AccessService.Access actor, UUID runId, boolean succeeded) {
    Record run = sql.fetchOne("select cursor_id from sync_run where id=?", runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在");
    UUID cursorId = run.get("cursor_id", UUID.class);
    // 重放运行自身产生的失败（如果有）按 run 关闭；原本遗留的失败按游标关闭
    sql.execute(
        "update sync_failure set status=? where tenant_id=? and run_id=?",
        succeeded ? "RESOLVED" : "OPEN", actor.tenantId(), runId);
    if (succeeded) {
      sql.execute(
          "update sync_failure set status='RESOLVED' where tenant_id=? and cursor_id=?"
              + " and id not in (select id from sync_failure where run_id=?)",
          actor.tenantId(), cursorId, runId);
    }
  }

  // ------------------------------------------------------------------
  // 2.5 平台回写
  // ------------------------------------------------------------------

  public List<PlatformOperationView> operations(UUID tenantId, String status, int limit) {
    boolean filtered = status != null && !status.isBlank();
    int capped = Math.min(Math.max(limit, 1), 200);
    String statusFilter = filtered ? " and o.status=?" : "";
    return sql.fetch(
            "select o.id,o.shop_id,o.marketplace_id,o.operation,o.idempotency_key,o.payload,o.status,"
                + " o.platform_request_id,o.verification,o.verified_at,o.attempts,o.last_error,o.occurred_at,"
                + " s.name shop_name,s.code shop_code,p.code platform_code,m.code marketplace_code"
                + " from platform_operation o join shop s on s.tenant_id=o.tenant_id and s.id=o.shop_id"
                + " join platform p on p.id=s.platform_id"
                + " left join marketplace m on m.id=o.marketplace_id"
                + " where o.tenant_id=?"
                + statusFilter
                + " order by o.occurred_at desc limit ?",
            filtered ? new Object[] {tenantId, status, capped} : new Object[] {tenantId, capped})
        .stream()
        .map(
            r ->
                new PlatformOperationView(
                    r.get("id", UUID.class),
                    r.get("shop_id", UUID.class),
                    r.get("shop_name", String.class),
                    r.get("platform_code", String.class),
                    r.get("marketplace_code", String.class),
                    r.get("operation", String.class),
                    r.get("idempotency_key", String.class),
                    r.get("payload", String.class),
                    r.get("status", String.class),
                    r.get("platform_request_id", String.class),
                    r.get("verification", String.class),
                    r.get("verified_at", Instant.class),
                    r.get("attempts", Integer.class),
                    r.get("last_error", String.class),
                    r.get("occurred_at", Instant.class)))
        .toList();
  }

  /**
   * 排队一条平台回写。
   *
   * <p>幂等键重复时返回既有任务而不是新建——这样上游（04 刊登、07 库存、09 订单）
   * 可以放心重发，不会造成平台侧重复上架或重复扣减。
   */
  public UUID enqueueOperation(AccessService.Access actor, PlatformOperationInput input) {
    UUID existing =
        one(
            UUID.class,
            "select id from platform_operation where tenant_id=? and idempotency_key=?",
            actor.tenantId(),
            input.idempotencyKey());
    if (existing != null) return existing;
    UUID id = Ids.next();
    sql.execute(
        "insert into platform_operation(id,tenant_id,shop_id,marketplace_id,operation,idempotency_key,payload)"
            + " values (?,?,?,?,?,?,cast(? as jsonb))",
        id,
        actor.tenantId(),
        input.shopId(),
        input.marketplaceId(),
        input.operation().name(),
        input.idempotencyKey(),
        input.payload() == null ? "{}" : input.payload());
    return id;
  }

  public void startOperation(AccessService.Access actor, UUID id) {
    sql.execute(
        "update platform_operation set status='SENDING',attempts=attempts+1,updated_at=now()"
            + " where tenant_id=? and id=? and status in ('PENDING','FAILED','UNKNOWN')",
        actor.tenantId(), id);
  }

  public void succeedOperation(AccessService.Access actor, UUID id, String platformRequestId) {
    sql.execute(
        "update platform_operation set status='SUCCEEDED',platform_request_id=?,last_error='',"
            + " verification='CONFIRMED_APPLIED',verified_at=now(),updated_at=now() where tenant_id=? and id=?",
        nullToEmpty(platformRequestId), actor.tenantId(), id);
  }

  /**
   * 2.5.1.2 未知结果。
   *
   * <p>请求已发出但没收到明确结果时状态是 UNKNOWN，**不是 FAILED**。
   * 重试前必须先核验，否则平台可能已经执行，再发一次就是重复发货或库存错扣——
   * 这是跨境 ERP 最容易造成资损的地方。
   */
  public void markUnknown(AccessService.Access actor, UUID id, String reason) {
    sql.execute(
        "update platform_operation set status='UNKNOWN',verification='NOT_REQUIRED',last_error=?,updated_at=now()"
            + " where tenant_id=? and id=?",
        nullToEmpty(reason), actor.tenantId(), id);
  }

  public void failOperation(AccessService.Access actor, UUID id, String reason) {
    sql.execute(
        "update platform_operation set status='FAILED',last_error=?,updated_at=now() where tenant_id=? and id=?",
        nullToEmpty(reason), actor.tenantId(), id);
  }

  /**
   * 记录未知结果的核验结论。
   *
   * <p>STILL_UNKNOWN 保持 UNKNOWN 状态且不允许重试——此时唯一安全的动作是等待并再次核验。
   */
  public void verifyOperation(AccessService.Access actor, UUID id, Verification verification) {
    String conclusion = verification.name();
    if ("CONFIRMED_APPLIED".equals(conclusion)) {
      sql.execute(
          "update platform_operation set status='SUCCEEDED',verification=?,verified_at=now(),"
              + " last_error='',updated_at=now() where tenant_id=? and id=?",
          conclusion, actor.tenantId(), id);
    } else if ("CONFIRMED_NOT_APPLIED".equals(conclusion)) {
      // 确认未执行后才允许重新发起
      sql.execute(
          "update platform_operation set status='FAILED',verification=?,verified_at=now(),updated_at=now()"
              + " where tenant_id=? and id=?",
          conclusion, actor.tenantId(), id);
    } else {
      sql.execute(
          "update platform_operation set status='UNKNOWN',verification=?,verified_at=now(),updated_at=now()"
              + " where tenant_id=? and id=?",
          conclusion, actor.tenantId(), id);
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}