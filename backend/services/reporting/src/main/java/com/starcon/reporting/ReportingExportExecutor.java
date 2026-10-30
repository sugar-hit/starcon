package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 13.6.2.1 导出的**异步执行器**：把「已受理」的导出任务真正跑完，产出文件。
 *
 * <p>🔴 **大文件异步是蓝图原话**（「支持…大文件异步导出」），
 * 所以受理接口只推进任务状态就返回，文件由固定线程池在请求之外生成。
 * 前端**绝不能**在受理返回后显示「导出完成」—— 记录上的 row_count 与文件
 * 是同一次执行写出来的，不是这次响应的承诺，要轮询任务详情才拿得到。
 *
 * <p>🔴 **领取互斥由 DB 裁决**（{@code worker_claimed_at}）：两个执行者各写各的
 * 局部 row_count，后写的会把前一个的进度抹掉，任务显示「导出 0 行」而文件里其实有 120 行。
 * 命中 0 行就说明别人已经在跑，本线程直接退出。
 *
 * <p>⚠️ **每批单独开租户事务**：租户上下文与数据范围都是**事务级 GUC**
 * （{@code app.current_tenant}），提交请求那个事务早就结束了。
 * 工作线程必须按 {@code (tenantId, actorUserId)} 重开事务。
 */
@Service
public class ReportingExportExecutor {
  private static final Logger log = LoggerFactory.getLogger(ReportingExportExecutor.class);

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingCustomReportService reports;
  private final ReportingExportTableBuilder tables;
  private final ReportingExportStorage storage;
  private final ReportingExportRenderer renderer;
  private final Clock clock;
  private final ReportingExportSettings settings;
  private final ExecutorService pool;

  public ReportingExportExecutor(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingCustomReportService reports,
      ReportingExportTableBuilder tables,
      ReportingExportStorage storage,
      ReportingExportRenderer renderer,
      Clock clock,
      ReportingExportSettings settings) {
    this.sql = sql;
    this.tenants = tenants;
    this.reports = reports;
    this.tables = tables;
    this.storage = storage;
    this.renderer = renderer;
    this.clock = clock;
    this.settings = settings;
    var seq = new AtomicInteger();
    // 并行度 2：导出是只读作业，两个并发足够把磁盘写满，再高只会让每个任务分到的批次更小。
    this.pool =
        Executors.newFixedThreadPool(
            2,
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "reporting-export-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
              }
            });
  }

  /** 受理一次导出：推进到 RUNNING 并丢进线程池。本方法返回时文件通常还没生成。 */
  public void accept(UUID tenantId, UUID actorUserId, UUID jobId) {
    var claimed =
        tenants.run(
            tenantId,
            () ->
                sql.fetchOne(
                    "update reporting_export_job set status='RUNNING',worker_claimed_at=now()"
                        + " where tenant_id=? and id=? and status='PENDING' and worker_claimed_at is null"
                        + " returning id",
                    tenantId,
                    jobId));
    if (claimed == null) {
      // 已被另一个执行者领取，或任务已不在 PENDING。两种都不是错误，静默退出即可。
      log.debug("导出任务 {} 未被本执行器领取，跳过", jobId);
      return;
    }
    pool.submit(() -> run(tenantId, actorUserId, jobId));
  }

  /** 真正跑一遍：取数 → 铺表 → 渲染 → 写盘 → 回写状态。 */
  private void run(UUID tenantId, UUID actorUserId, UUID jobId) {
    try {
      var snapshot = ReportingExportService.load(sql, tenants, tenantId, jobId);
      var view = reports.get(tenantId, actorUserId, snapshot.reportId());
      var selected =
          tables.resolveSelection(snapshot.selectedFields(), tables.resolveColumns(view));
      var built =
          tables.build(
              tenantId,
              actorUserId,
              view,
              selected,
              snapshot.windowFrom(),
              snapshot.windowTo(),
              settings.maxRows(),
              false);
      var format = ReportingExportCatalog.ExportFormat.of(snapshot.format());
      var rendered =
          renderer.render(
              format, built.table(), ReportingExportService.tableTitle(view), built.notes());
      var content = rendered.content();
      var fileName =
          ReportingExportService.fileName(view.reportCode(), format, LocalDate.now(clock));
      var storageKey = storageKey(tenantId, jobId, format);
      var bytes = storage.put(storageKey, content);
      var sha256 = sha256(content);
      var rowCount = built.table().rows().size();
      // expires_at 用 SQL 侧的 now() 计算：有效期是「生成完成之后 N 小时」，
      // 用应用侧的墙钟会在跨时区部署时与库里其它时间列差一个偏移。
      var done =
          tenants.run(
              tenantId,
              () ->
                  sql.fetchOne(
                      "update reporting_export_job set status='SUCCEEDED',row_count=?,file_name=?,"
                          + " storage_key=?,byte_size=?,content_sha256=?,error='',"
                          + " expires_at=now() + (? * interval '1 hour')"
                          + " where tenant_id=? and id=? and status='RUNNING' returning id",
                      rowCount,
                      fileName,
                      storageKey,
                      java.math.BigDecimal.valueOf(bytes),
                      sha256,
                      settings.downloadTtlHours(),
                      tenantId,
                      jobId));
      if (done == null) {
        log.warn("导出任务 {} 在写回结果时已不在 RUNNING（可能被取消），文件保留供审计", jobId);
      }
    } catch (Exception e) {
      log.warn("导出任务 {} 执行失败：{}", jobId, e.toString());
      fail(tenantId, jobId, e);
    }
  }

  /** 执行失败：把原因写进任务行，让任务详情页能显示而不是永远停在「生成中」。 */
  private void fail(UUID tenantId, UUID jobId, Exception cause) {
    var message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    try {
      tenants.run(
          tenantId,
          () ->
              sql.execute(
                  "update reporting_export_job set status='FAILED',error=?"
                      + " where tenant_id=? and id=? and status='RUNNING'",
                  message.length() > 500 ? message.substring(0, 500) : message,
                  tenantId,
                  jobId));
    } catch (RuntimeException ignored) {
      // 连失败都写不进去（数据库不可用）时只能记日志：再抛会把工作线程打死，
      // 而线程池被死线程打满会让后续导出全部排队等死。
      log.error("导出任务 {} 失败且无法回写状态", jobId, ignored);
    }
  }

  /**
   * 存储键：{@code exports/<tenant>/<uuid>.<ext>}。
   *
   * <p>键里带租户段，所以一个租户算不出另一个租户的键；实际隔离仍由 RLS 保证，
   * 这里是第二道，不是唯一一道。
   */
  static String storageKey(UUID tenantId, UUID jobId, ReportingExportCatalog.ExportFormat format) {
    return "exports/" + tenantId + "/" + jobId + format.extension();
  }

  /** sha256（回答「两次导出得到的是不是同一份文件」）。 */
  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("当前 JVM 缺少 SHA-256", e);
    }
  }
}
