package com.starcon.catalog;

import com.starcon.tenancy.Ids;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 3.4.1.2 的异步处理执行器：把 {@code media_process_job} 里排队的工作真的做完。
 *
 * <p>蓝图要求「**处理是异步任务**，长批量不阻塞页面」。本类是这条要求的落点， 它做四件事：
 *
 * <ol>
 *   <li><b>提交后立刻返回</b>。{@link ImageProcessService#submit} 只写元数据就返回， 本类在请求线程之外消费；两者合起来才是「不阻塞页面」。
 *   <li><b>逐条目推进 + 每步落库</b>。每处理完一个素材就更新条目状态与任务计数， 所以进度是**库里可查的事实**，不是内存里的计数器（换进程/重启后进度不丢）。
 *   <li><b>单个失败不影响整批</b>。200 张里 3 张不合法时，用户要的是「哪 3 张、为什么」， 而不是全部白跑——蓝图点名了「处理任务记录（成功/失败原因）」这个输出。
 *   <li><b>取消在条目之间生效</b>。已开工的那一张会做完（像素处理没有中间态， 强行中断只会留下写了一半的对象），剩下的条目置 CANCELLED。
 * </ol>
 *
 * <p><b>租户上下文必须显式绑定。</b>FORCE RLS 下每个事务都要有 {@code app.current_tenant}， 而工作线程没有 HTTP
 * 请求带来的上下文。所以每次访问库都用 {@link ImageProcessService#submit} 记下的 <b>tenant_id</b> 开事务—— 那个 tenant_id
 * 来自已认证的成员关系（提交瞬间写入），不是客户端能指定的字段。 与 {@code AccessService.run} / {@code TenantTransactions} 是同一套机制。
 *
 * <p><b>像素加工不占着数据库事务。</b>解码、缩放、编码、算哈希都是纯 CPU 运算， 在事务外做；只有「写对象 + 插版本行 + 更新计数」进事务。
 * 这样一条连接不会被一张大图的编码时间占住。
 */
@Component
public class ImageProcessExecutor {
  private static final Logger log = LoggerFactory.getLogger(ImageProcessExecutor.class);

  /**
   * 并行度取 2，而不是 CPU 核数。
   *
   * <p>像素加工是内存与 GC 密集的：一张 4000×3000 的 ARGB 位图就是 48MB， 再加解码后的原图与编码中的目标图，单条流水线峰值过百 MB。
   * 并行度一高就会把堆打满、让所有任务一起变慢。2 条够让一张大图的 IO 等待 被另一张的计算填上。
   */
  private static final int PARALLELISM = 2;

  private final DSLContext sql;
  private final MediaStorage storage;
  private final AccessService access;
  private final ExecutorService pool;

  public ImageProcessExecutor(DSLContext sql, MediaStorage storage, AccessService access) {
    this.sql = sql;
    this.storage = storage;
    this.access = access;
    this.pool =
        Executors.newFixedThreadPool(
            PARALLELISM,
            new ThreadFactory() {
              private final AtomicInteger seq = new AtomicInteger();

              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "image-process-" + seq.incrementAndGet());
                t.setDaemon(false); // 让应用停机时把在跑的任务做完（Spring 会关闭 ExecutorService）
                return t;
              }
            });
  }

  // ============================================================ 入口

  /**
   * 把一个任务排进线程池。
   *
   * <p><b>入队失败不抛</b>：任务行已经在库里了，抛异常只会给出「处理提交失败」的假象， 而任务其实好好地排在那里等人重跑。这里只记日志。 因此返回 {@code false}
   * 让调用方知道「尚未开工」，页面上就显示「排队中」而不是「处理中」。
   */
  public boolean enqueue(UUID jobId, UUID tenantId) {
    try {
      pool.execute(() -> runJob(jobId, tenantId));
      return true;
    } catch (RejectedExecutionException e) {
      log.warn("处理任务 {} 未能入队（线程池已满），任务仍留在库里可重新提交：{}", jobId, e.getMessage());
      return false;
    }
  }

  /**
   * 同步跑完一个任务（测试与「立刻要结果」的场景用）。
   *
   * <p>它跑的是与异步路径**同一段代码**（{@link #runJob}），不是另写一份： 测试里同步驱动、并发的就是线上那条路径，因此测试通过就说明异步路径也通。
   */
  public void runJobNow(UUID jobId, UUID tenantId) {
    runJob(jobId, tenantId);
  }

  /** 关掉线程池（Spring 停机时调；让在跑的任务做完）。 */
  @jakarta.annotation.PreDestroy
  void shutdown() {
    pool.shutdown();
  }

  // ============================================================ 执行一个任务

  /**
   * 处理一个任务直到没有可处理的条目。包住异常，保证任务一定会走到终态。
   *
   * <p><b>一个任务同时只有一个工作线程在跑。</b>「抢到」的动作就是 {@link #markRunning}：它只在状态还是 PENDING 时才生效，所以两个线程 （线程池并行度
   * 2，加上同一任务可能被重复入队）里只有一个能拿到所有权， 另一个直接返回。
   *
   * <p>这条纪律不是可有可无的：早期版本让两个线程同时推进同一个任务， 于是各自需要「等对方把条目处理完」才能收尾，而每一轮等待都要开事务拿连接。 测试把连接池固定成 1
   * 条连接（为了证明归还后不串租户）时， 两个线程互相等对方让出连接，直接死锁、整个测试挂住。 「一个任务一个主人」把并发从「需要互相等待」变成「后者直接退出」。
   */
  public void runJob(UUID jobId, UUID tenantId) {
    RecipeSnapshot recipe;
    try {
      recipe = access.tenants.run(tenantId, () -> loadRecipeFor(jobId));
      if (recipe == null) {
        log.warn("处理任务 {} 不存在或配方缺失，已跳过", jobId);
        return;
      }
      boolean claimed = access.tenants.run(tenantId, () -> markRunning(jobId));
      if (!claimed) {
        // 别人已经在跑（或已经跑完）。不参与，也不去等它——等它就是上面那个死锁。
        log.debug("处理任务 {} 已被其他工作线程接手，本次跳过", jobId);
        return;
      }

      while (true) {
        // 取消检查必须在**领取条目之前**。反过来的话（先领再判），一旦在判定为取消时
        // break，那一条已经被标成 RUNNING 的条目就没人再碰它了：既不会被处理，
        // 也不会被 cancelRemaining 落成 CANCELLED（它只处理 PENDING）。
        // 结果是 finish() 永远看到一条 RUNNING、拒绝落终态，任务卡在「处理中」直到天荒地老。
        if (access.tenants.run(tenantId, () -> isCancelled(jobId))) break;
        PendingItem item = access.tenants.run(tenantId, () -> nextPendingItem(jobId));
        if (item == null) break;
        processOne(jobId, tenantId, recipe, item);
      }
    } catch (RuntimeException e) {
      log.error("处理任务 {} 执行异常", jobId, e);
      finishAfterFailure(jobId, tenantId, "处理任务中断：" + readable(e));
      return;
    }
    try {
      access.tenants.run(
          tenantId,
          () -> {
            cancelRemaining(jobId);
            finish(jobId);
            return null;
          });
    } catch (RuntimeException e) {
      log.error("处理任务 {} 收尾失败", jobId, e);
    }
  }

  /** 执行中断的收尾：未处理的条目落成失败，任务进终态。 */
  private void finishAfterFailure(UUID jobId, UUID tenantId, String reason) {
    try {
      access.tenants.run(
          tenantId,
          () -> {
            failRemaining(jobId, reason);
            finish(jobId);
            return null;
          });
    } catch (RuntimeException e) {
      log.error("处理任务 {} 失败收尾失败", jobId, e);
    }
  }

  // ============================================================ 配方快照

  /**
   * 配方快照（执行期不再读配方表）。
   *
   * <p>执行开始后配方可能被人改掉；产物必须能对着**提交时那套规格**解释， 否则「这批图为什么被裁成这个尺寸」在几分钟后就答不出来了。
   */
  record RecipeSnapshot(
      String targetFormat,
      boolean removeBackground,
      int backgroundTolerance,
      boolean whiteBackground,
      String watermarkText,
      String watermarkPosition,
      int watermarkOpacity,
      Integer targetWidth,
      Integer targetHeight,
      String cropMode,
      List<RatioSnapshot> ratios) {}

  record RatioSnapshot(String label, int width, int height, String cropMode) {}

  private RecipeSnapshot loadRecipeFor(UUID jobId) {
    var row =
        sql.fetchOne(
            "select r.target_format,r.remove_background,r.background_tolerance,r.white_background,"
                + "r.watermark_text,r.watermark_position,r.watermark_opacity,r.target_width_px,"
                + "r.target_height_px,r.crop_mode from media_process_job j"
                + " join media_process_recipe r on r.tenant_id=j.tenant_id and r.id=j.recipe_id"
                + " where j.id=?",
            jobId);
    if (row == null) return null;
    var ratioRows =
        sql.fetch(
            "select q.label,q.width_px,q.height_px,q.crop_mode from media_process_recipe_ratio q"
                + " join media_process_job j on j.recipe_id=q.recipe_id"
                + " where j.id=? order by q.sort_no,q.id",
            jobId);
    List<RatioSnapshot> ratios = new ArrayList<>();
    for (var r : ratioRows)
      ratios.add(
          new RatioSnapshot(
              r.get("label", String.class),
              r.get("width_px", BigDecimal.class).intValue(),
              r.get("height_px", BigDecimal.class).intValue(),
              r.get("crop_mode", String.class)));
    BigDecimal width = r(row, "target_width_px");
    BigDecimal height = r(row, "target_height_px");
    return new RecipeSnapshot(
        row.get("target_format", String.class),
        Boolean.TRUE.equals(row.get("remove_background", Boolean.class)),
        row.get("background_tolerance", Integer.class),
        Boolean.TRUE.equals(row.get("white_background", Boolean.class)),
        row.get("watermark_text", String.class),
        row.get("watermark_position", String.class),
        row.get("watermark_opacity", Integer.class),
        width == null ? null : width.intValue(),
        height == null ? null : height.intValue(),
        row.get("crop_mode", String.class),
        ratios);
  }

  private static BigDecimal r(org.jooq.Record row, String field) {
    return row.get(field, BigDecimal.class);
  }

  // ============================================================ 条目

  /**
   * 取下一个待处理条目，并**在同一次事务里**把它标成 RUNNING。
   *
   * <p>用 {@code for update} 而不是先查后改：两个工作线程同时扫同一个任务时， 「先查后改」会让两边都查到同一条 PENDING，于是同一个素材被处理两遍、
   * 产出两套版本。先取行锁再标 RUNNING 让第二个线程直接等不到行。
   */
  private PendingItem nextPendingItem(UUID jobId) {
    var row =
        sql.fetchOne(
            "select id,asset_id,source_version,source_storage_key from media_process_job_item"
                + " where job_id=? and status='PENDING' order by created_at,id limit 1 for update",
            jobId);
    if (row == null) return null;
    sql.execute(
        "update media_process_job_item set status='RUNNING',started_at=now() where id=?",
        row.get("id", UUID.class));
    return new PendingItem(
        row.get("id", UUID.class),
        row.get("asset_id", UUID.class),
        row.get("source_version", Integer.class),
        row.get("source_storage_key", String.class));
  }

  record PendingItem(UUID id, UUID assetId, int sourceVersion, String sourceKey) {}

  /** 一个已算好的产物：像素加工的输出，尚未落库。 */
  private record Produced(
      String label, String format, byte[] content, int width, int height, String sha256) {}

  /**
   * 处理一个条目。
   *
   * <p>顺序刻意是「先在事务外把像素全算完，再在一个事务里落库」： 一次事务要么写完这批产物版本，要么一个都不写。避免出现 「3 个比例档写进去了、第 4 个失败」而素材停在半处理状态。
   *
   * <p>失败分两类：源对象读不到 / 加工失败 → 本素材 FAILED + 原因，继续下一条（不整批回滚）； 写库失败 → 抛给 {@link #runJob}
   * 收尾（那是系统级问题，整批标失败才诚实）。
   */
  private void processOne(UUID jobId, UUID tenantId, RecipeSnapshot recipe, PendingItem item) {
    List<Produced> produced;
    try {
      produced = compute(item, recipe);
    } catch (IOException e) {
      // 原图对象读不到：素材的存储键已失效（3.4.1.1 的失效检测会报 MISSING）。
      failItem(
          jobId,
          tenantId,
          item,
          "读取原图失败（存储键 " + item.sourceKey() + "）：" + e.getMessage() + "。请先在素材库做完整性校验，确认文件还在存储里");
      return;
    } catch (RuntimeException e) {
      failItem(jobId, tenantId, item, readable(e));
      return;
    }

    List<String> writtenKeys = new ArrayList<>();
    try {
      access.tenants.run(
          tenantId,
          () -> {
            for (Produced out : produced) {
              String key = outputKey(tenantId, out.format());
              try {
                storage.put(key, out.content());
              } catch (IOException e) {
                throw new IllegalStateException("写入处理产物对象失败（键 " + key + "）：" + e.getMessage(), e);
              }
              writtenKeys.add(key);
              // 版本行 + 产物行。版本行插入后 V18 的 sync_media_asset_from_version
              // 会把素材的 current_version 推进过去——这里**不回写素材本体**。
              recordOutput(tenantId, jobId, item, out, key);
            }
            sql.execute(
                "update media_process_job_item set status='SUCCEEDED',produced_count=?,finished_at=now()"
                    + " where id=?",
                produced.size(),
                item.id());
            sql.execute(
                "update media_process_job set succeeded_count=succeeded_count+1,version=version+1"
                    + " where id=?",
                jobId);
            return null;
          });
    } catch (RuntimeException e) {
      // 写库失败：已写出的对象是没人知道是什么的孤儿文件，清掉。
      for (String key : writtenKeys) discardQuietly(key);
      failItem(jobId, tenantId, item, "落库失败（产物已回滚清理）：" + readable(e));
    }
  }

  /**
   * 像素加工（纯计算，不碰数据库与存储）。
   *
   * <p>六项能力在这里串成一条流水线： 去背景 → 白底 → 水印 → 每档尺寸裁剪 → 格式转换（写出时才转，顺带算哈希）。
   *
   * <p>配方配了比例档时逐档产出；没配就用配方自身的目标尺寸产出**一档**（label='DEFAULT'）—— 「只裁剪不生成多比例」也是合法用法（缩略图常常就是原图加个水印）。
   */
  private List<Produced> compute(PendingItem item, RecipeSnapshot recipe) throws IOException {
    byte[] source = storage.read(item.sourceKey());
    BufferedImage image = ImageProcessor.decode(source);
    if (recipe.removeBackground())
      image = ImageProcessor.removeBackground(image, recipe.backgroundTolerance());
    if (recipe.whiteBackground()) image = ImageProcessor.flattenToWhite(image);
    image =
        ImageProcessor.applyWatermark(
            image, recipe.watermarkText(), recipe.watermarkPosition(), recipe.watermarkOpacity());
    // 配方自身带了目标尺寸时先缩一次，再按比例档各自缩。
    if (recipe.targetWidth() != null)
      image =
          ImageProcessor.resize(
              image,
              new ImageProcessor.Canvas(
                  recipe.targetWidth(), recipe.targetHeight(), recipe.cropMode()));

    List<Produced> out = new ArrayList<>();
    if (recipe.ratios().isEmpty()) {
      out.add(encode("DEFAULT", image, recipe.targetFormat()));
      return out;
    }
    for (RatioSnapshot ratio : recipe.ratios()) {
      var canvas = new ImageProcessor.Canvas(ratio.width(), ratio.height(), ratio.cropMode());
      out.add(encode(ratio.label(), ImageProcessor.resize(image, canvas), recipe.targetFormat()));
    }
    return out;
  }

  private Produced encode(String label, BufferedImage image, String format) {
    var output = ImageProcessor.write(image, format);
    return new Produced(
        label, output.format(), output.content(), output.width(), output.height(), output.sha256());
  }

  /**
   * 写一条 media_asset_version（产物本体）+ 一条 media_process_output（产物溯源）。
   *
   * <p>版本号取 max+1，不接受指定：并发时 UNIQUE(tenant_id,asset_id,version_no) 会把其中一个 变成冲突，而让用户看到「当前已经到 v3
   * 了」比让他重试更有用。
   */
  private void recordOutput(UUID tenantId, UUID jobId, PendingItem item, Produced out, String key) {
    int nextVersion = nextVersionNo(item.assetId());
    sql.execute(
        "insert into media_asset_version(id,tenant_id,asset_id,version_no,storage_key,"
            + "original_filename,file_format,mime_type,byte_size,width_px,height_px,"
            + "duration_seconds,content_sha256,note) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        tenantId,
        item.assetId(),
        nextVersion,
        key,
        key.substring(key.lastIndexOf('/') + 1),
        out.format(),
        ImageProcessor.mimeTypeOf(out.format()),
        BigDecimal.valueOf(out.content().length),
        BigDecimal.valueOf(out.width()),
        BigDecimal.valueOf(out.height()),
        null,
        out.sha256(),
        "3.4.1.2 处理产物（比例档 " + out.label() + "，基于 v" + item.sourceVersion() + "）");
    sql.execute(
        "insert into media_process_output(id,tenant_id,job_id,item_id,asset_id,ratio_label,"
            + "from_version,to_version,storage_key,file_format,mime_type,byte_size,width_px,"
            + "height_px,content_sha256) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        tenantId,
        jobId,
        item.id(),
        item.assetId(),
        out.label(),
        item.sourceVersion(),
        nextVersion,
        key,
        out.format(),
        ImageProcessor.mimeTypeOf(out.format()),
        BigDecimal.valueOf(out.content().length),
        BigDecimal.valueOf(out.width()),
        BigDecimal.valueOf(out.height()),
        out.sha256());
  }

  private int nextVersionNo(UUID assetId) {
    return sql.fetchOne(
            "select coalesce(max(version_no),0)+1 from media_asset_version where asset_id=?",
            assetId)
        .get(0, Integer.class);
  }

  // ============================================================ 状态推进

  private void failItem(UUID jobId, UUID tenantId, PendingItem item, String reason) {
    access.tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "update media_process_job_item set status='FAILED',error=?,finished_at=now() where id=?",
              reason,
              item.id());
          sql.execute(
              "update media_process_job set failed_count=failed_count+1,version=version+1"
                  + " where id=?",
              jobId);
          return null;
        });
  }

  private boolean isCancelled(UUID jobId) {
    return Boolean.TRUE.equals(
        sql.fetchOne("select cancel_requested from media_process_job where id=?", jobId)
            .get(0, Boolean.class));
  }

  /** 取消请求生效后，把还没开工的条目落成 CANCELLED。 */
  private void cancelRemaining(UUID jobId) {
    sql.execute(
        "update media_process_job_item set status='CANCELLED',"
            + "error=case when btrim(error)='' then '任务已取消，这个素材没有处理' else error end,"
            + "finished_at=now() where job_id=? and status='PENDING'",
        jobId);
  }

  /** 执行中断：未处理的条目落成失败，计数按实际重算（不靠累加，免得重复计数）。 */
  private void failRemaining(UUID jobId, String reason) {
    sql.execute(
        "update media_process_job_item set status='FAILED',error=?,finished_at=now()"
            + " where job_id=? and status in ('PENDING','RUNNING')",
        reason,
        jobId);
    sql.execute(
        "update media_process_job set failed_count=(select count(*) from media_process_job_item"
            + " where job_id=? and status='FAILED'),version=version+1 where id=?",
        jobId,
        jobId);
  }

  /**
   * 收尾：算终态并写 finished_at。
   *
   * <p>终态判定只看计数：全成功 SUCCEEDED，部分失败 PARTIAL，全失败 FAILED。 「全失败」时 asset_count 可能大于 0（全是失败条目），所以不能拿
   * succeeded==asset_count 判。
   *
   * <p><b>还有 PENDING / RUNNING 的条目时直接不收尾</b>：此刻计数还没反映全部工作， 算出来的终态会是假的（一个素材都没处理也可能算出「全部成功」）。 留
   * PENDING 条目由 {@link #cancelRemaining} 处理，RUNNING 的由那个线程自己收尾。
   */
  private void finish(UUID jobId) {
    var row =
        sql.fetchOne(
            "select succeeded_count,failed_count from media_process_job where id=?", jobId);
    if (row == null) return;
    long unsettled =
        sql.fetchOne(
                "select count(*) from media_process_job_item"
                    + " where job_id=? and status in ('PENDING','RUNNING')",
                jobId)
            .get(0, Long.class);
    if (unsettled > 0) return;
    boolean cancelled = isCancelled(jobId);
    String status;
    if (cancelled) status = "CANCELLED";
    else {
      int succeeded = row.get("succeeded_count", Integer.class);
      int failed = row.get("failed_count", Integer.class);
      status = failed > 0 ? (succeeded > 0 ? "PARTIAL" : "FAILED") : "SUCCEEDED";
    }
    sql.execute(
        "update media_process_job set status=?,version=version+1,finished_at=now()"
            + " where id=? and not (status in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED'))",
        status,
        jobId);
  }

  /**
   * 抢占任务：只在状态还是 PENDING 时推进到 RUNNING。
   *
   * <p>返回 true 表示**当前线程拿到了这个任务的所有权**；false 表示别人已经在跑 （或任务已到终态），调用方应当立刻退出而不是接手。 {@code where
   * status='PENDING'} 就是这一行 SQL 提供的原子抢占： 并发时只有一个 UPDATE 能命中，另一条更新 0 行。
   *
   * @return 是否抢到
   */
  private boolean markRunning(UUID jobId) {
    return sql.execute(
            "update media_process_job set status='RUNNING',started_at=coalesce(started_at,now()),"
                + "version=version+1 where id=? and status='PENDING'",
            jobId)
        == 1;
  }

  private void discardQuietly(String key) {
    try {
      storage.delete(key);
    } catch (IOException e) {
      // 残留的是一个没人知道是什么的孤儿文件（比报错更需要被人知道），所以留日志。
      log.warn("处理失败后未能删除产物对象 {}：{}", key, e.getMessage());
    }
  }

  private static String readable(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) return "处理失败：" + e.getClass().getSimpleName();
    return message;
  }

  /**
   * 造产物存储键：复用 {@link MediaRules#storageKey} 的 {@code media/<tenant>/<年月>/<uuid>.<ext>}。
   *
   * <p>tenantId 显式传入而不是查 {@code currentTenant_tenant}：这里在事务外调用， 而事务外没有 {@code
   * app.current_tenant}（FORCE RLS 下也查不出来）。
   */
  private static String outputKey(UUID tenantId, String format) {
    return MediaRules.storageKey(
        tenantId, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC), format);
  }
}
