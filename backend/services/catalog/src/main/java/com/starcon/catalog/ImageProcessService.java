package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.1.2 图片处理：处理配方（目标平台规格）、异步处理任务、逐素材结果、产物版本。
 *
 * <p>蓝图原文：「对图片做刊登前的加工：白底、去背景、水印添加、尺寸裁剪到平台要求、
 * 格式转换、生成多比例版本（如主图 1:1 + 缩略图）。」「处理是异步任务，长批量不阻塞页面；
 * 处理结果不可逆覆盖原图，原图始终保留。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>提交只写元数据，像素加工交给 {@link ImageProcessExecutor}</b>。
 *       {@link #submit} 在一次事务里写任务与条目就返回（毫秒级），
 *       所以「长批量不阻塞页面」是结构性的，而不是靠前端转圈扛过去的。
 *   <li><b>原图从 media_asset_version 取，绝不读文件名</b>。提交时把来源版本号、
 *       存储键、哈希**快照**进 {@code media_process_job_item}：批量跑到一半素材被人
 *       换了新版本时，处理的对象必须仍是提交那一刻定下的那一版。
 *   <li><b>产物写新对象 + 新版本行</b>，由 V18 的 sync_media_asset_from_version 推进
 *       {@code media_asset.current_version}。本类不回写素材的媒体元数据——
 *       两处各写一遍就等于有第二条写入路径，而触发器只挡得住其中一条。
 *   <li><b>失败必须留下原因</b>（蓝图点名的输出：成功/失败原因）。单个素材失败不让整批回滚：
 *       200 张里 3 张不合法，用户要的是「哪 3 张、为什么」，不是全部白跑。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.assets.read}，写 {@code catalog.assets.update}，
 * 与 3.4.1.1 媒体库同一词表（不新造——新词表要改 identity 服务的迁移）。
 */
@Service
public class ImageProcessService {
  /** 单页最大行数。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final MediaStorage storage;
  private final ImageProcessExecutor executor;

  public ImageProcessService(DSLContext sql, MediaStorage storage, ImageProcessExecutor executor) {
    this.sql = sql;
    this.storage = storage;
    this.executor = executor;
  }

  // ============================================================ 配方

  /** 配方列表（带比例档）。按编码排序，停用的也在列表里（要看历史配置）。 */
  public List<MediaProcessRecipeRow> recipes() {
    var rows =
        sql.fetch(
            "select id,code,name,note,target_platform,target_format,remove_background,"
                + "background_tolerance,white_background,watermark_text,watermark_position,"
                + "watermark_opacity,target_width_px,target_height_px,crop_mode,enabled,version,"
                + "created_at,updated_at from media_process_recipe where tenant_id=? order by code",
            currentTenantId());
    if (rows.isEmpty()) return List.of();
    Map<UUID, List<MediaProcessRatio>> byRecipe = ratiosOf(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    List<MediaProcessRecipeRow> out = new ArrayList<>(rows.size());
    for (Record r : rows)
      out.add(
          toRecipeRow(
              r, byRecipe.getOrDefault(r.get("id", UUID.class), List.of())));
    return out;
  }

  /** 单个配方（含比例档）。 */
  public MediaProcessRecipeRow recipe(UUID id) {
    var row = recipeRow(id);
    return toRecipeRow(row, ratiosOf(List.of(id)).getOrDefault(id, List.of()));
  }

  private MediaProcessRecipeRow toRecipeRow(Record r, List<MediaProcessRatio> ratios) {
    return new MediaProcessRecipeRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("note", String.class),
        r.get("target_platform", String.class),
        r.get("target_format", String.class),
        Boolean.TRUE.equals(r.get("remove_background", Boolean.class)),
        r.get("background_tolerance", Integer.class),
        Boolean.TRUE.equals(r.get("white_background", Boolean.class)),
        r.get("watermark_text", String.class),
        r.get("watermark_position", String.class),
        r.get("watermark_opacity", Integer.class),
        r.get("target_width_px", BigDecimal.class),
        r.get("target_height_px", BigDecimal.class),
        r.get("crop_mode", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        ratios,
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 建配方或改配方（id 为空即新建），比例档整体替换。
   *
   * <p>比例档用**整体替换**而不是增量增删：档位集合是「这批产物长什么样」的完整口径，
   * 增删式会让「当前有哪些档」这个问题有两个答案。
   *
   * <p>编码建后不可改：任务的产物记录里存的是档位 label，编码变了会让历史配方的
   * 引用对不上——与 {@code MediaService.saveTag} 里那条纪律相同。
   */
  public MediaProcessRecipeSaved saveRecipe(AccessService.Access actor, UUID id, MediaProcessRecipeInput input) {
    String problem =
        firstNonNull(
            ImageProcessRules.recipeCodeProblem(input.code()),
            ImageProcessRules.recipeNameProblem(input.name()),
            ImageProcessRules.targetFormatProblem(input.targetFormat()),
            ImageProcessRules.cropModeProblem(input.cropMode()),
            ImageProcessRules.watermarkPositionProblem(input.watermarkPosition()),
            ImageProcessRules.toleranceProblem(input.backgroundTolerance()),
            ImageProcessRules.opacityProblem(input.watermarkOpacity()),
            ImageProcessRules.watermarkTextProblem(input.watermarkText()),
            ImageProcessRules.targetSizeProblem(input.targetWidthPx(), input.targetHeightPx()),
            ImageProcessRules.ratiosSizeProblem(input.ratios() == null ? 0 : input.ratios().size()),
            ImageProcessRules.backgroundFormatProblem(
                input.removeBackground(), input.targetFormat()),
            ratiosProblem(input.ratios()));
    CatalogService.check(problem == null, problem);

    String code = MediaRules.normalizeUpper(input.code());
    List<MediaProcessRatioInput> ratios = input.ratios() == null ? List.of() : input.ratios();
    // 尺寸与容差在 null 时给默认值：这几列 NOT NULL，null 会变成一条没有意义的
    // 「不做去背景但容差 24」的行，让读配方的人以为容差在起作用。
    int tolerance = input.backgroundTolerance() == null ? 24 : input.backgroundTolerance();
    int opacity = input.watermarkOpacity() == null ? 30 : input.watermarkOpacity();

    if (id == null) {
      if (sql.fetchOne("select id from media_process_recipe where code=?", code) != null)
        throw conflict("配方编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into media_process_recipe(id,tenant_id,code,name,note,target_platform,"
                + "target_format,remove_background,background_tolerance,white_background,"
                + "watermark_text,watermark_position,watermark_opacity,target_width_px,"
                + "target_height_px,crop_mode,enabled) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            newId, currentTenantId(), code, MediaRules.normalize(input.name()),
            MediaRules.normalize(input.note()), MediaRules.normalize(input.targetPlatform()),
            MediaRules.normalize(input.targetFormat()).toLowerCase(Locale.ROOT),
            input.removeBackground(), tolerance, input.whiteBackground(),
            MediaRules.normalize(input.watermarkText()),
            MediaRules.normalizeUpper(input.watermarkPosition()), opacity,
            input.targetWidthPx(), input.targetHeightPx(),
            MediaRules.normalizeUpper(input.cropMode()), input.enabled());
        replaceRatios(newId, ratios);
      } catch (DataAccessException e) {
        throw conflict("配方保存失败：请检查参数后重试（" + rootMessage(e) + "）");
      }
      audit(actor, newId, "ImageProcessRecipeSaved", code);
      return recipeSaved(newId);
    }

    var row = recipeRow(id);
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "配方编码建后不可修改：产物的比例档是按这套规格解释的，改编码会让历史产物无法回溯。要改请新建一个配方");
    int updated;
    try {
      updated =
          sql.execute(
              "update media_process_recipe set name=?,note=?,target_platform=?,target_format=?,"
                  + "remove_background=?,background_tolerance=?,white_background=?,watermark_text=?,"
                  + "watermark_position=?,watermark_opacity=?,target_width_px,target_height_px=?,"
                  + "crop_mode=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              MediaRules.normalize(input.name()), MediaRules.normalize(input.note()),
              MediaRules.normalize(input.targetPlatform()),
              MediaRules.normalize(input.targetFormat()).toLowerCase(Locale.ROOT),
              input.removeBackground(), tolerance, input.whiteBackground(),
              MediaRules.normalize(input.watermarkText()),
              MediaRules.normalizeUpper(input.watermarkPosition()), opacity,
              input.targetWidthPx(), input.targetHeightPx(),
              MediaRules.normalizeUpper(input.cropMode()), input.enabled(),
              id, input.version());
    } catch (DataAccessException e) {
      throw conflict("配方保存失败：" + rootMessage(e));
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "配方已被他人修改，请刷新后重试");
    replaceRatios(id, ratios);
    audit(actor, id, "ImageProcessRecipeSaved", code);
    return recipeSaved(id);
  }

  /** 比例档校验：每档的标签、尺寸、适配方式，以及档内标签重复。 */
  private String ratiosProblem(List<MediaProcessRatioInput> ratios) {
    if (ratios == null || ratios.isEmpty()) return null;
    var labels = new ArrayList<String>();
    for (MediaProcessRatioInput ratio : ratios) {
      String problem =
          firstNonNull(
              ImageProcessRules.ratioLabelProblem(ratio.label()),
              ImageProcessRules.ratioSizeProblem(ratio.widthPx(), ratio.heightPx()),
              ImageProcessRules.cropModeProblem(ratio.cropMode()));
      if (problem != null) return problem;
      labels.add(ratio.label());
    }
    return ImageProcessRules.duplicateRatioProblem(labels);
  }

  /** 整体替换比例档：先删后插，与 replaceTags 同一形状。 */
  private void replaceRatios(UUID recipeId, List<MediaProcessRatioInput> ratios) {
    sql.execute("delete from media_process_recipe_ratio where recipe_id=?", recipeId);
    int sort = 0;
    for (MediaProcessRatioInput ratio : ratios) {
      sql.execute(
          "insert into media_process_recipe_ratio(id,tenant_id,recipe_id,label,width_px,height_px,"
              + "crop_mode,sort_no) values(?,?,?,?,?,?,?,?)",
          Ids.next(), currentTenantId(), recipeId,
          MediaRules.normalize(ratio.label()).toUpperCase(Locale.ROOT),
          ratio.widthPx(), ratio.heightPx(),
          MediaRules.normalizeUpper(ratio.cropMode()), sort);
      sort++;
    }
  }

  /** 配方启用停用。停用后不能再用它新建任务，历史任务与产物照常可读。 */
  public MediaProcessRecipeSaved setRecipeEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update media_process_recipe set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled, id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "ImageProcessRecipeEnabledChanged", null);
    return recipeSaved(id);
  }

  /**
   * 删除配方。仍被任务引用时被拒（V19 的 refuse_media_process_recipe_delete 兜底），
   * 服务层先给可读原因：要清理请改用停用。
   */
  public void deleteRecipe(AccessService.Access actor, UUID id, long version) {
    var row = recipeRow(id);
    CatalogService.check(
        row.get("version", Long.class) == version, "配方已被他人修改，请刷新后重试");
    long jobs = recipeJobCount(id);
    CatalogService.check(
        jobs == 0,
        "配方「" + row.get("code", String.class) + "」已被 " + jobs
            + " 个处理任务引用，不可删除：请改用停用（停用后不能再用它新建任务，历史任务与产物照常可读）");
    sql.execute("delete from media_process_recipe where id=?", id);
    audit(actor, id, "ImageProcessRecipeDeleted", null);
  }

  // ============================================================ 提交处理任务

  /**
   * 提交一个处理任务（异步）。**立刻返回**，像素加工在线程池里跑。
   *
   * <p>这一段是「长批量不阻塞页面」的落点：这里只做四件很快的事——查配方、
   * 逐个查素材并快照来源版本、写任务行、写条目行。200 个素材大约几十毫秒。
   *
   * <p><b>素材快照在提交时取</b>：来源版本号、存储键、哈希全部写进条目行。
   * 这样即使处理跑到一半素材被追加了新版本，处理用的仍是提交那一刻的字节。
   *
   * <p>非图片素材直接跳过（记 SKIPPED 并给出原因），而不是让整批失败：
   * 运营从素材库勾选时很容易把视频一起勾上。
   */
  public MediaProcessJob submit(AccessService.Access actor, MediaProcessJobInput input) {
    String problem =
        firstNonNull(
            ImageProcessRules.assetsSizeProblem(input.assetIds() == null ? 0 : input.assetIds().size()));
    CatalogService.check(problem == null, problem);
    CatalogService.check(input.recipeId() != null, "请选择一个处理配方");

    var recipe = recipeRow(input.recipeId());
    CatalogService.check(
        Boolean.TRUE.equals(recipe.get("enabled", Boolean.class)),
        "配方「" + recipe.get("name", String.class) + "」已停用，不能用它新建处理任务。请换一个启用的配方，或先启用它");
    List<MediaProcessRatio> ratios = ratiosOf(List.of(input.recipeId())).getOrDefault(input.recipeId(), List.of());

    // 先把所有条目查出来再决定：提交阶段就要知道会跳过几个，
    // 否则用户点了提交后才发现「8 张里 3 张是视频」，而任务已经建了。
    List<PendingAsset> pending = new ArrayList<>();
    List<String> skips = new ArrayList<>();
    for (UUID assetId : input.assetIds()) {
      // 拼接点是 " and a.id=?" 而不是 " where a.id=?"：SELECT_SOURCE 里已经带了
      // where a.tenant_id=?，再拼一个 where 会得到 `where ... where ...` 的语法错误。
      var asset = sql.fetchOne(SELECT_SOURCE + " and a.id=?", currentTenantId(), assetId);
      if (asset == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "素材不存在或不属于当前租户：" + assetId);
      }
      if (!"IMAGE".equals(asset.get("media_type", String.class))) {
        skips.add("「" + asset.get("name", String.class) + "」是"
            + MediaRules.mediaTypeLabel(asset.get("media_type", String.class))
            + "，图片处理只处理图片（视频转码不在本模块范围）");
        continue;
      }
      pending.add(
          new PendingAsset(
              assetId,
              asset.get("current_version", Integer.class),
              asset.get("storage_key", String.class),
              asset.get("content_sha256", String.class),
              asset.get("name", String.class)));
    }
    CatalogService.check(
        !pending.isEmpty(),
        skips.isEmpty()
            ? "选中的素材都不存在"
            : "选中的素材没有可处理的图片：" + String.join("；", skips));

    UUID jobId = Ids.next();
    sql.execute(
        "insert into media_process_job(id,tenant_id,recipe_id,requested_by,status,asset_count,"
            + "skipped_count,error) values(?,?,?,?,?,?,?,?)",
        jobId, currentTenantId(), input.recipeId(), actor.userId(),
            "PENDING",
        pending.size(), skips.size(),
        skips.isEmpty() ? "" : String.join("；", skips));
    for (PendingAsset asset : pending)
      sql.execute(
          "insert into media_process_job_item(id,tenant_id,job_id,asset_id,source_version,"
              + "source_storage_key,source_sha256,status) values(?,?,?,?,?,?,?,?)",
          Ids.next(), currentTenantId(), jobId, asset.assetId(), asset.version(),
          asset.storageKey(), asset.sha256(), "PENDING");
    audit(actor, jobId, "ImageProcessJobSubmitted",
        "配方 " + recipe.get("code", String.class) + "，" + pending.size() + " 个素材，"
            + ratios.size() + " 个比例档");
    // 事务提交后才入队：工作线程一进来就开自己的租户事务，
    // 若在这里（提交事务还没结束）入队，它可能看不到刚写的条目行。
    pendingEnqueue(jobId);
    return job(jobId);
  }

  /**
   * 提交事务结束后再把任务扔进线程池。
   *
   * <p>注册一个「提交后」回调而不是在这里直接入队：直接入队的话工作线程会比提交先一步，
   * 它的 {@code select ... from media_process_job where id=?} 在另一个连接上根本看不到
   * 这条还没提交的行，于是任务被当成「不存在」而静默丢弃——页面显示永远「排队中」。
   *
   * <p><b>tenant_id 必须在事务内取好</b>，不能在回调里再查：
   * {@code set_config('app.current_tenant', ..., true)} 里的 {@code true} 表示
   * 「只在本次事务内有效」，而 afterCommit 恰好在事务结束**之后**执行——
   * 那时连接上已经没有租户上下文，FORCE RLS 会把这行直接藏起来，
   * 查到 null 然后空指针。所以这里在事务里把 id 捕获成局部变量传出去。
   */
  private void pendingEnqueue(UUID jobId) {
    UUID tenantId = currentTenantId();
    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.enqueue(jobId, tenantId);
            }
          });
    } else {
      executor.enqueue(jobId, tenantId);
    }
  }

  /** 提交阶段的素材快照。 */
  private record PendingAsset(UUID assetId, int version, String storageKey, String sha256, String name) {}

  /** 素材的来源版本行。必须显式列字段（推导列 reference_count/version_count 读不到会抛）。 */
  private static final String SELECT_SOURCE =
      "select a.id,a.name,a.media_type,a.current_version,a.storage_key,a.content_sha256"
          + " from media_asset a where a.tenant_id=?";

  // ============================================================ 任务查询

  /** 任务列表：分页 + 状态筛选。按提交时间倒序（页面上最常见的是「我刚点的那个」）。 */
  public MediaProcessJobPage listJobs(MediaProcessJobQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.status() != null && !query.status().isBlank()) {
      String status = MediaRules.normalizeUpper(query.status());
      CatalogService.check(
          ImageProcessRules.JOB_STATUSES.contains(status),
          "任务状态只能是 " + String.join(" / ", ImageProcessRules.JOB_STATUSES));
      where.add("j.status=?");
      binds.add(status);
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 关键词搜任务与它涉及的素材：运营记得的是「那批白底图」，不是任务 id。
      where.add(
          "(exists (select 1 from media_process_recipe r where r.tenant_id=j.tenant_id"
              + "   and r.id=j.recipe_id and (r.name ilike ? escape '!' or r.code ilike ? escape '!'))"
              + " or exists (select 1 from media_process_job_item i join media_asset a"
              + "   on a.tenant_id=i.tenant_id and a.id=i.asset_id"
              + "  where i.tenant_id=j.tenant_id and i.job_id=j.id"
              + "   and a.name ilike ? escape '!'))");
      String pattern = escapeLike(query.keyword().trim());
      for (int i = 0; i < 3; i++) binds.add(pattern);
    }
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from media_process_job j where j.tenant_id=?" + sqlWhere,
                concat(List.of(tenantId), binds).toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select j.id,j.recipe_id,j.requested_by,j.status,j.asset_count,j.succeeded_count,"
                + "j.failed_count,j.skipped_count,j.cancel_requested,j.error,j.created_at,"
                + "j.started_at,j.finished_at,j.version,r.name as recipe_name,r.code as recipe_code,"
                + "r.target_platform,r.target_format"
                + " from media_process_job j join media_process_recipe r"
                + " on r.tenant_id=j.tenant_id and r.id=j.recipe_id"
                + " where j.tenant_id=?" + sqlWhere
                + " order by j.created_at desc,j.id desc limit ? offset ?",
            concat(
                    List.of(tenantId),
                    concat(binds, List.of(size, (long) page * size)))
                .toArray());
    var counts = statusCounts(tenantId);
    return new MediaProcessJobPage(
        rows.stream().map(this::toJobRow).toList(),
        total,
        page,
        size,
        counts.getOrDefault("PENDING", 0L),
        counts.getOrDefault("RUNNING", 0L),
        counts.getOrDefault("PARTIAL", 0L),
        counts.getOrDefault("FAILED", 0L),
        "处理是异步任务：提交后立刻返回，像素加工在线程池里跑，进度从这张表查。"
            + "原图始终保留——产物写新对象与新版本行，素材的当前版本指针前进，历史版本不动。");
  }

  /** 各状态任务数：概览与列表页的进度徽标。 */
  private Map<String, Long> statusCounts(UUID tenantId) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select status,count(*) as total from media_process_job where tenant_id=? group by status",
            tenantId))
      counts.put(r.get("status", String.class), r.get("total", Long.class));
    return counts;
  }

  private MediaProcessJobRow toJobRow(Record r) {
    return new MediaProcessJobRow(
        r.get("id", UUID.class),
        r.get("recipe_id", UUID.class),
        r.get("recipe_name", String.class),
        r.get("recipe_code", String.class),
        r.get("target_platform", String.class),
        r.get("target_format", String.class),
        r.get("requested_by", UUID.class),
        r.get("status", String.class),
        ImageProcessRules.jobStatusLabel(r.get("status", String.class)),
        r.get("asset_count", Integer.class),
        r.get("succeeded_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("skipped_count", Integer.class),
        Boolean.TRUE.equals(r.get("cancel_requested", Boolean.class)),
        r.get("error", String.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("started_at", OffsetDateTime.class)),
        instant(r.get("finished_at", OffsetDateTime.class)),
        r.get("version", Long.class));
  }

  /**
   * 任务详情：任务 + 配方 + 条目（含产物）。
   *
   * <p>一次查询取齐条目与产物而不是逐条查：200 个条目 × 3 档产物，
   * 逐条查就是 800 次往返，而页面上它们是一张表。
   */
  public MediaProcessJobDetail detail(UUID jobId) {
    MediaProcessJobRow job = toJobRow(jobRow(jobId));
    List<MediaProcessRatio> ratios = ratiosOf(List.of(job.recipeId())).getOrDefault(job.recipeId(), List.of());
    Map<UUID, List<MediaProcessOutput>> outputsByItem = outputsByItem(jobId);
    List<MediaProcessJobItem> items = items(jobId, outputsByItem);
    return new MediaProcessJobDetail(
        job,
        ratios,
        items,
        job.succeededCount() + job.failedCount() + job.skippedCount(),
        "原图始终保留：每个产物都是一个**新版本行 + 新对象**，素材的当前版本指针前进，"
            + "作为处理来源的原始版本不会被覆盖（V19 的版本不可变触发器与删除保护在 DB 层钉死这一点）。");
  }

  /** 任务条目（按创建顺序）。 */
  public List<MediaProcessJobItem> items(UUID jobId) {
    jobRow(jobId);
    return items(jobId, outputsByItem(jobId));
  }

  private List<MediaProcessJobItem> items(UUID jobId, Map<UUID, List<MediaProcessOutput>> outputs) {
    var rows =
        sql.fetch(
            "select i.id,i.job_id,i.asset_id,i.source_version,i.source_storage_key,i.source_sha256,"
                + "i.status,i.produced_count,i.error,i.started_at,i.finished_at,i.created_at,"
                + "a.name as asset_name,a.current_version as asset_current_version,"
                + "a.file_format as source_format"
                + " from media_process_job_item i join media_asset a"
                + " on a.tenant_id=i.tenant_id and a.id=i.asset_id"
                + " where i.job_id=? order by i.created_at,i.id",
            jobId);
    return rows.stream()
        .map(
            r -> {
              UUID itemId = r.get("id", UUID.class);
              String status = r.get("status", String.class);
              return new MediaProcessJobItem(
                  itemId,
                  jobId,
                  r.get("asset_id", UUID.class),
                  r.get("asset_name", String.class),
                  r.get("source_version", Integer.class),
                  r.get("source_storage_key", String.class),
                  r.get("source_format", String.class),
                  status,
                  ImageProcessRules.itemStatusLabel(status),
                  r.get("produced_count", Integer.class),
                  r.get("error", String.class),
                  outputs.getOrDefault(itemId, List.of()),
                  r.get("asset_current_version", Integer.class),
                  instant(r.get("started_at", OffsetDateTime.class)),
                  instant(r.get("finished_at", OffsetDateTime.class)));
            })
        .toList();
  }

  /** 一个任务的产物按条目分组（一次查询，避免 N+1）。 */
  private Map<UUID, List<MediaProcessOutput>> outputsByItem(UUID jobId) {
    Map<UUID, List<MediaProcessOutput>> byItem = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,item_id,asset_id,ratio_label,from_version,to_version,storage_key,"
                + "file_format,mime_type,byte_size,width_px,height_px,content_sha256,created_at"
                + " from media_process_output where job_id=? order by created_at,id",
            jobId)) {
      UUID itemId = r.get("item_id", UUID.class);
      byItem
          .computeIfAbsent(itemId, k -> new ArrayList<>())
          .add(
              new MediaProcessOutput(
                  r.get("id", UUID.class),
                  itemId,
                  r.get("asset_id", UUID.class),
                  r.get("ratio_label", String.class),
                  r.get("from_version", Integer.class),
                  r.get("to_version", Integer.class),
                  r.get("storage_key", String.class),
                  r.get("file_format", String.class),
                  r.get("mime_type", String.class),
                  r.get("byte_size", BigDecimal.class),
                  r.get("width_px", BigDecimal.class),
                  r.get("height_px", BigDecimal.class),
                  r.get("content_sha256", String.class),
                  instant(r.get("created_at", OffsetDateTime.class))));
    }
    return byItem;
  }

  /** 某个素材的处理历史（详情页的「处理记录」区）。 */
  public List<MediaProcessJobRow> historyOfAsset(UUID assetId) {
    var rows =
        sql.fetch(
            "select j.id,j.recipe_id,j.requested_by,j.status,j.asset_count,j.succeeded_count,"
                + "j.failed_count,j.skipped_count,j.cancel_requested,j.error,j.created_at,"
                + "j.started_at,j.finished_at,j.version,r.name as recipe_name,r.code as recipe_code,"
                + "r.target_platform,r.target_format"
                + " from media_process_job j join media_process_recipe r"
                + " on r.tenant_id=j.tenant_id and r.id=j.recipe_id"
                + " where j.tenant_id=? and exists (select 1 from media_process_job_item i"
                + " where i.tenant_id=j.tenant_id and i.job_id=j.id and i.asset_id=?)"
                + " order by j.created_at desc,j.id desc limit 20",
            currentTenantId(), assetId);
    return rows.stream().map(this::toJobRow).toList();
  }

  /** 处理概览：任务量与产出量。 */
  public MediaProcessOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select count(*) as job_count,"
                + "count(*) filter (where status='SUCCEEDED') as succeeded_jobs,"
                + "count(*) filter (where status='PARTIAL') as partial_jobs,"
                + "count(*) filter (where status='FAILED') as failed_jobs,"
                + "count(*) filter (where status in ('PENDING','RUNNING')) as open_jobs,"
                + "coalesce(sum(asset_count),0) as asset_total"
                + " from media_process_job where tenant_id=?",
            tenantId);
    long recipeCount =
        sql.fetchOne(
                "select count(*) from media_process_recipe where tenant_id=? and enabled", tenantId)
            .get(0, Long.class);
    var bytes =
        sql.fetchOne(
            "select coalesce(sum(byte_size),0),count(*) from media_process_output where tenant_id=?",
            tenantId);
    return new MediaProcessOverview(
        counts.get("job_count", Long.class),
        counts.get("succeeded_jobs", Long.class),
        counts.get("partial_jobs", Long.class),
        counts.get("failed_jobs", Long.class),
        counts.get("open_jobs", Long.class),
        counts.get("asset_total", Long.class),
        recipeCount,
        bytes.get(1, Long.class),
        bytes.get(0, BigDecimal.class).longValue(),
        "产出按 media_asset 的新版本计入素材库：刊登侧读素材的当前版本即可，不需要知道处理细节。"
            + "原图版本永远保留，所以处理过多少次都不会丢原图。");
  }

  // ============================================================ 取消

  /**
   * 请求取消。**不阻塞**：只置标志位，工作线程在条目之间检查它。
   *
   * <p>已经在加工的那一张会做完——像素处理没有「做一半」的中间态，
   * 强行中断会留下一个写了一半的对象。取消的语义是「剩下的别做了」。
   */
  public MediaProcessJob cancel(AccessService.Access actor, UUID jobId) {
    var row = jobRow(jobId);
    CatalogService.check(
        !ImageProcessRules.terminal(row.get("status", String.class)),
        "任务已结束（" + ImageProcessRules.jobStatusLabel(row.get("status", String.class))
            + "），无法取消");
    // media_process_job 没有 updated_at：它的时间轴是 created_at（业务：几点点的）
    // 与 started_at / finished_at（系统：什么时候真的开始与结束），
    // 取消请求既不是业务时间也不是加工完成时间，所以只推进 version 乐观锁。
    sql.execute(
        "update media_process_job set cancel_requested=true,version=version+1"
            + " where id=? and not cancel_requested",
        jobId);
    audit(actor, jobId, "ImageProcessJobCancelled", null);
    return job(jobId);
  }

  // ============================================================ 工具

  private Record jobRow(UUID id) {
    var row =
        sql.fetchOne(
            "select j.id,j.recipe_id,j.requested_by,j.status,j.asset_count,j.succeeded_count,"
                + "j.failed_count,j.skipped_count,j.cancel_requested,j.error,j.created_at,"
                + "j.started_at,j.finished_at,j.version,r.name as recipe_name,r.code as recipe_code,"
                + "r.target_platform,r.target_format"
                + " from media_process_job j join media_process_recipe r"
                + " on r.tenant_id=j.tenant_id and r.id=j.recipe_id where j.id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private MediaProcessJob job(UUID id) {
    var row = jobRow(id);
    long items = sql.fetchOne("select count(*) from media_process_job_item where job_id=?", id).get(0, Long.class);
    // cancel_requested 必须**读回真实值**：写死 false 会让「取消已受理」在响应里
    // 显示成「没取消」，页面上的「正在取消」提示也就永远不出现。
    // （这不是为了好看——运维据此判断要不要重跑。）
    return new MediaProcessJob(
        id,
        row.get("recipe_id", UUID.class),
        row.get("recipe_name", String.class),
        row.get("status", String.class),
        ImageProcessRules.jobStatusLabel(row.get("status", String.class)),
        row.get("asset_count", Integer.class),
        items,
        row.get("error", String.class),
        Boolean.TRUE.equals(row.get("cancel_requested", Boolean.class)),
        ImageProcessRules.terminal(row.get("status", String.class))
            ? "任务已结束。产物作为新版本写入素材库，原图版本保留；详情里能看到每个素材的成功与失败原因。"
            : "处理已提交，正在排队。进度从任务详情查；原图会保留，产物作为新版本写入。");
  }

  private Record recipeRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,note,target_platform,target_format,remove_background,"
                + "background_tolerance,white_background,watermark_text,watermark_position,"
                + "watermark_opacity,target_width_px,target_height_px,crop_mode,enabled,version,"
                + "created_at,updated_at from media_process_recipe where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** 比例档按配方分组（一次查询取齐，避免逐配方查一次）。 */
  private Map<UUID, List<MediaProcessRatio>> ratiosOf(List<UUID> recipeIds) {
    if (recipeIds.isEmpty()) return Map.of();
    Map<UUID, List<MediaProcessRatio>> byRecipe = new LinkedHashMap<>();
    var rows =
        sql.fetch(
            "select id,recipe_id,label,width_px,height_px,crop_mode,sort_no"
                + " from media_process_recipe_ratio where recipe_id in ("
                + placeholders(recipeIds) + ") order by sort_no,id",
            recipeIds.toArray());
    for (Record r : rows)
      byRecipe
          .computeIfAbsent(r.get("recipe_id", UUID.class), k -> new ArrayList<>())
          .add(
              new MediaProcessRatio(
                  r.get("id", UUID.class),
                  r.get("label", String.class),
                  r.get("width_px", BigDecimal.class),
                  r.get("height_px", BigDecimal.class),
                  r.get("crop_mode", String.class),
                  r.get("sort_no", Integer.class)));
    return byRecipe;
  }

  private long recipeJobCount(UUID recipeId) {
    return sql.fetchOne("select count(*) from media_process_job where recipe_id=?", recipeId)
        .get(0, Long.class);
  }

  private MediaProcessRecipeSaved recipeSaved(UUID id) {
    var row = recipeRow(id);
    return new MediaProcessRecipeSaved(
        id,
        row.get("code", String.class),
        row.get("version", Long.class),
        ratiosOf(List.of(id)).getOrDefault(id, List.of()).size(),
        recipeJobCount(id));
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 占位符串。与 {@code MediaService} 同一形状：参数个数由调用方保证，SQL 里不会拼进用户输入。 */
  static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  static <T> List<T> concat(List<T> first, List<T> second) {
    var all = new ArrayList<T>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  /** 取第一个非空原因：多个校验同时不合法时，先报最贴近用户输入的那一条。 */
  static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  // ============================================================ 工作线程用的存储与键

  /** 读原图字节。供 {@link ImageProcessExecutor} 取来源版本的那份字节。 */
  byte[] readSource(String key) throws java.io.IOException {
    return storage.read(key);
  }

  /** 写产物字节。供 {@link ImageProcessExecutor} 落盘。 */
  long writeOutput(String key, byte[] content) throws java.io.IOException {
    return storage.put(key, content);
  }

  /** 删除产物对象（写库失败后的回滚清理）。 */
  void storageDelete(String key) throws java.io.IOException {
    storage.delete(key);
  }

  /**
   * 造产物存储键。
   *
   * <p>复用 {@link MediaRules#storageKey}：{@code media/<tenant>/<年月>/<uuid>.<ext>}，
   * 键里带着租户段，所以一个租户算不出另一个租户的键——隔离在**键的构造**上就成立。
   *
   * <p>tenantId 由调用方显式传入而不是这里查 {@code currentTenantId()}：
   * 工作线程在事务外造键，而事务外没有租户上下文（FORCE RLS 下也查不到）。
   */
  String outputKey(UUID tenantId, String format) {
    return MediaRules.storageKey(tenantId, OffsetDateTime.now(ZoneOffset.UTC), format);
  }

  /** 同步驱动一个任务跑完（测试与运维「现在就等结果」的场景）。 */
  void runJobNow(UUID jobId, UUID tenantId) {
    executor.runJobNow(jobId, tenantId);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 取出数据库异常里最有信息量的原因（V19 的 CHECK 只有一句人话，没有约束名可用）。 */
  private static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ImageProcessService.class);
}