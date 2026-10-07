package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 3.8.2.1 导出的**异步执行器**：把「已受理」的导出任务真正跑完，产出文件。
 *
 * <p><b>大文件异步是蓝图原话</b>（「支持…大文件异步导出」），所以 POST 只推进任务状态就返回，
 * 文件由固定线程池在请求之外生成。前端**绝不能**在 POST 返回后显示「导出完成」——
 * 记录上的 row_count 与文件是同一次执行写出来的，不是这次响应的承诺。
 *
 * <p><b>每批单独开租户事务</b>：租户上下文与数据范围都是**事务级 GUC**
 * （{@code app.current_tenant} / {@code app.actor} / {@code app.grants}），
 * 提交请求那个事务早就结束了。工作线程必须按 {@code AccessService.Access} 重开事务，
 * 而且必须是**带 Access 的重开**：导出的 SELECT 打在 spu / sku / party_contact 上，
 * 这三张表带 V3 的 {@code data_scope} RESTRICTIVE 策略，只绑 tenant_id 而不绑
 * grants 的话，SELF / DEPARTMENT 范围会被逐行拒绝，或者更糟——在没绑对的上下文里读出了不该读的行。
 *
 * <p><b>领取互斥由 DB 裁决</b>（{@code worker_claimed_at}）：与 V29 import_job 同一条经验，
 * 两个执行者各写各的局部 row_count，后写的会把前一个的进度抹掉，任务显示「导出 0 行」
 * 而文件里其实有 120 行。命中 0 行就说明别人已经在跑，本线程直接退出。
 *
 * <p><b>CSV 增量拼装</b>：分批 append 进 {@link ByteArrayOutputStream}，写完一次性交给
 * {@link MediaStorage}。峰值内存与结果集大小同阶，但**不是**无界的 —— 超出行数上限在
 * 计数阶段就被拒（见 {@code ExportJobService}），所以这里的上界是已知的。
 */
@Service
public class ExportJobExecutor {
  private static final Logger log = LoggerFactory.getLogger(ExportJobExecutor.class);

  /** 分批读取的行数。分批而不是一次读完，避免大结果集在堆里堆成一个大数组。 */
  static final int CHUNK_ROWS = 1000;

  private final DSLContext sql;
  private final AccessService access;
  private final ExportDataSources sources;
  private final MediaStorage storage;
  private final int downloadTtlHours;
  private final ExecutorService pool;

  public ExportJobExecutor(
      DSLContext sql,
      AccessService access,
      ExportDataSources sources,
      MediaStorage storage,
      @Value("${EXPORT_DOWNLOAD_TTL_HOURS:24}") int downloadTtlHours) {
    this.sql = sql;
    this.access = access;
    this.sources = sources;
    this.storage = storage;
    this.downloadTtlHours = downloadTtlHours;
    var seq = new AtomicInteger();
    // 并行度 2：与 3.8.1.1 导入向导同一档。导出是只读作业，两个并发足够把磁盘写满，
    // 再高只会让每个任务分到的批次更小、总时长更长。
    this.pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "catalog-export-" + seq.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
  }

  /** 受理一次导出：推进到 RUNNING 并丢进线程池。本方法返回时文件通常还没生成。 */
  public void accept(AccessService.Access actor, String permission, UUID jobId) {
    var claimed =
        access.run(
            actor,
            permission,
            () ->
                sql.fetchOne(
                    "update export_job set status='RUNNING',worker_claimed_at=now()"
                        + " where tenant_id=? and id=? and status='PENDING' and worker_claimed_at is null"
                        + " returning id",
                    actor.tenantId(),
                    jobId));
    if (claimed == null) {
      // 已被另一个执行者领取，或任务已不在 PENDING。两种都不是错误，静默退出即可。
      log.debug("导出任务 {} 未被本执行器领取，跳过", jobId);
      return;
    }
    pool.submit(() -> run(actor, permission, jobId));
  }

  private void run(AccessService.Access actor, String permission, UUID jobId) {
    try {
      var job = ExportJobService.load(sql, actor.tenantId(), jobId);
      var source = sources.require(job.sourceKey());
      var columns = resolveColumns(source, job.selectedFields());
      var spec = job.filters();
      var sort = ExportRules.resolveSort(source, job.sort());
      boolean desc = ExportRules.resolveDesc(job.sort());

      long total = access.run(actor, permission, () -> source.count(spec));
      long capped = Math.min(total, source.maxRows());

      // BOM 让 Excel 正确识别 UTF-8 中文表头（与 3.1.2.1 / 3.8.1.1 同一处理）。
      // 必须按 UTF-8 字节写：ByteArrayOutputStream.write(int) 只写低 8 位，
      // 写 U+FEFF 的整数会得到一个 0xFF 字节而不是三字节 BOM，Excel 就会按本地编码猜。
      var buffer = new ByteArrayOutputStream();
      buffer.write("﻿".getBytes(StandardCharsets.UTF_8));
      buffer.write(String.join(",", job.fieldHeaders().stream().map(ExportJobService::csv).toList())
          .getBytes(StandardCharsets.UTF_8));
      buffer.write('\n');

      long written = 0;
      long offset = 0;
      while (offset < capped) {
        int limit = (int) Math.min(CHUNK_ROWS, capped - offset);
        final long at = offset;
        // 每批单独开一次租户事务：事务级 GUC 不会跨批次存活，而一个横跨整个导出的
        // 长事务会把连接长期占住、期间任何写都被推迟到导出结束。
        var batch =
            access.run(
                actor,
                permission,
                () -> {
                  var rows = new ArrayList<String[]>();
                  source.stream(spec, sort, desc, columns, at, limit, rows::addAll);
                  return rows;
                });
        if (batch.isEmpty()) break;
        for (String[] row : batch) {
          buffer.write(String.join(",", java.util.Arrays.stream(row).map(ExportJobService::csv).toList())
              .getBytes(StandardCharsets.UTF_8));
          buffer.write('\n');
        }
        written += batch.size();
        offset += batch.size();
      }

      byte[] content = buffer.toByteArray();
      var requestedOn = job.requestedAt().atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
      String fileName =
          "catalog-"
              + source.key().toLowerCase(java.util.Locale.ROOT)
              + "-"
              + String.format("%04d%02d%02d", requestedOn.getYear(), requestedOn.getMonthValue(), requestedOn.getDayOfMonth())
              + "-"
              + job.id().toString().substring(0, 8)
              + ".csv";
      String storageKey = storageKey(actor.tenantId(), job.id());
      long bytes = storage.put(storageKey, content);
      String sha256 = sha256(content);
      // expires_at 用 SQL 侧的 now() 计算：有效期是「生成完成之后 N 小时」，
      // 用应用侧的墙钟会在跨时区部署时与库里其它时间列差一个偏移。
      // written 在循环里累加，不能直接被 lambda 捕获——固定成本轮终值的不可变视图。
      final long finalWritten = written;
      var done =
          access.run(
              actor,
              permission,
              () ->
                  sql.fetchOne(
                      "update export_job set status='SUCCEEDED',row_count=?,file_name=?,storage_key=?,"
                          + "byte_size=?,content_sha256=?,"
                          + "expires_at=now() + (? * interval '1 hour')"
                          + " where tenant_id=? and id=? and status='RUNNING' returning id",
                      finalWritten,
                      fileName,
                      storageKey,
                      java.math.BigDecimal.valueOf(bytes),
                      sha256,
                      downloadTtlHours,
                      actor.tenantId(),
                      jobId));
      if (done == null) log.warn("导出任务 {} 在写回结果时已不在 RUNNING（可能被取消），文件保留供审计", jobId);
    } catch (Exception e) {
      log.warn("导出任务 {} 执行失败：{}", jobId, e.toString());
      fail(actor, permission, jobId, e);
    }
  }

  /** 执行失败：把原因写进任务行，让任务详情页能显示而不是永远停在「导出中」。 */
  private void fail(AccessService.Access actor, String permission, UUID jobId, Exception cause) {
    var message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    try {
      access.run(
          actor,
          permission,
          () ->
              sql.execute(
                  "update export_job set status='FAILED',error=? where tenant_id=? and id=? and status='RUNNING'",
                  message.length() > 500 ? message.substring(0, 500) : message,
                  actor.tenantId(),
                  jobId));
    } catch (RuntimeException ignored) {
      // 连失败都写不进去（数据库不可用）时只能记日志：再抛会把工作线程打死，
      // 而线程池被死线程打满会让后续导出全部排队等死。
      log.error("导出任务 {} 失败且无法回写状态", jobId, ignored);
    }
  }

  /** 按保存下来的字段键还原字段对象（顺序即落库顺序）。 */
  private List<ExportColumn> resolveColumns(ExportDataSource source, List<String> keys) {
    var byKey = new java.util.HashMap<String, ExportColumn>();
    for (var column : source.columns()) byKey.put(column.key(), column);
    var out = new ArrayList<ExportColumn>();
    for (var key : keys) {
      var column = byKey.get(key);
      if (column == null)
        // 字段清单在创建时校验过；取不到只可能是字段定义被改过。
        // 明确失败而不是少导一列 —— 少一列的文件看上去完全正常。
        throw new IllegalStateException("字段「" + key + "」已不属于数据源 " + source.key());
      out.add(column);
    }
    return out;
  }

  /**
   * 存储键：{@code exports/<tenant>/<uuid>.csv}。
   *
   * <p>键里带租户段，所以一个租户算不出另一个租户的键；
   * 实际隔离仍由 {@code export_job} 的 RLS 与查询里的 tenant_id 保证，
   * 这里是第二道，不是唯一一道。
   */
  static String storageKey(UUID tenantId, UUID jobId) {
    return "exports/" + tenantId + "/" + jobId + ".csv";
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("当前 JVM 缺少 SHA-256", e);
    }
  }
}