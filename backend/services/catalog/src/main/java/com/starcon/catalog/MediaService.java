package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.MediaRules.*;

import com.starcon.tenancy.Ids;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.1.1 媒体库：上传、分类标签、引用计数、版本、失效检测、删除保护。
 *
 * <p>蓝图原文：「集中管理图片与视频：上传、分类标签、引用计数（被哪些 SPU/SKU/listing 使用）、版本、失效检测。支撑批量刊登时的"素材库复用"。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>二进制不进业务库</b>。{@link MediaStorage} 端口负责字节，本类只写元数据。 写盘与写库之间必须能对账，所以失效检测（{@link
 *       #verify}）是真实存在的读回来算哈希， 不是「相信元数据里那行 SHA」。
 *   <li><b>元数据从字节探测，不接受调用方填写</b>（{@link MediaRules#probe}）。 格式、宽高、时长、哈希都是「这份文件是什么」的事实。
 *   <li><b>同内容复用</b>：同租户内相同 {@code content_sha256} 只有一行素材 （V18 的
 *       UNIQUE），重复上传直接返回既有素材——「素材库复用」的字面实现。
 *   <li><b>当前版本字段由 DB 触发器同步</b>（V18 的 sync_media_asset_from_version）。 本类插入版本行后**不**自己回写 media_asset
 *       的存储键/字节数/哈希： 两处各写一遍就等于有两个写入路径，而触发器只挡得住其中一个。
 *   <li><b>删除保护先在应用层判一次</b>，因为 DB 触发器抛的 23503 只有约束名、 没有「被哪个 SPU 引用着」这种可执行信息；DB 层那道是为了堵「判完之后、
 *       删除之前」被并发挂上引用的窗口。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.assets.read}，写 {@code catalog.assets.update}。 素材库在 V3
 * 里就已经有独立的权限词表（{@code catalog.assets}「素材与翻译」）， 沿用它而不是新造——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@Service
public class MediaService {
  /** 单页最大行数。素材库是租户级资产，行数量级在千位；超过 200 一页基本是脚本在拉全量。 */
  private static final int MAX_PAGE_SIZE = 200;

  private static final String SELECT_COLUMNS =
      "select a.id,a.name,a.description,a.media_type,a.purpose,a.file_format,a.mime_type,"
          + "a.byte_size,a.width_px,a.height_px,a.duration_seconds,a.content_sha256,"
          + "a.storage_key,a.original_filename,a.alt_text,a.current_version,"
          + "a.integrity_status,a.integrity_detail,a.integrity_checked_at,a.enabled,a.version,"
          + "a.created_at,a.updated_at,"
          // 引用计数：列表页要据此显示「被引用着、删不掉」，不必点开详情才知道。
          + "(select count(*) from media_reference r where r.tenant_id=a.tenant_id"
          + "  and r.asset_id=a.id) as reference_count,"
          + "(select count(*) from media_asset_version v where v.tenant_id=a.tenant_id"
          + "  and v.asset_id=a.id) as version_count";

  private static final String FROM_ASSET = " from media_asset a where a.tenant_id=?";

  private final DSLContext sql;
  private final MediaStorage storage;

  public MediaService(DSLContext sql, MediaStorage storage) {
    this.sql = sql;
    this.storage = storage;
  }

  // ============================================================ 上传

  /**
   * 上传一个媒体对象（新建素材）。
   *
   * <p><b>同内容复用</b>：探测出的 sha256 若在本租户已有素材，则**不新建行也不写盘**， 直接返回既有素材并带上 {@code
   * deduplicated=true}。这既是「素材库复用」， 也避免批量上传时把同一张图存成 N 份对象。
   *
   * <p>标签整体替换：{@code tagCodes} 提交什么就等于什么（与 BrandInput.names 同一口径）。
   * 标签编码不存在时自动建标签——运营上传时顺手打一个新标签是正常动作， 为此先退出上传去建标签再回来重传，体验上等于强迫人工两步。
   */
  public MediaSaved upload(AccessService.Access actor, MultipartFile file, MediaUpload input) {
    String problem =
        firstNonNull(
            nameProblem(input.name()),
            mediaTypeProblem(input.mediaType()),
            purposeProblem(input.purpose()));
    CatalogService.check(problem == null, problem);
    byte[] content = bytes(file);

    Probe probe = probe(content, input.mediaType(), file.getOriginalFilename());
    // 调用方声明的类型必须与探测结果一致。不一致就拒：让人拿 IMAGE 的表单传视频，
    // 会得到一个宽度为空却在 media_type 上写着 IMAGE 的素材行，下游按类型选处理管线时全错。
    CatalogService.check(
        probe.mediaType().equals(normalizeUpper(input.mediaType())),
        "声明的媒体类型（"
            + mediaTypeLabel(input.mediaType())
            + "）与文件内容不符：探测到的是"
            + mediaTypeLabel(probe.mediaType())
            + "。请检查是否选错了文件");

    UUID tenantId = actor.tenantId();
    Optional<Record> existing = existingBySha(probe.sha256());
    if (existing.isPresent()) return savedResult(existing.get().get("id", UUID.class), true);

    String extension = probe.fileFormat();
    String key = storageKey(tenantId, OffsetDateTime.now(ZoneOffset.UTC), extension);
    UUID id = Ids.next();
    long written = writeStorage(key, content);

    try {
      sql.execute(
          "insert into media_asset(id,tenant_id,name,description,media_type,purpose,file_format,"
              + "mime_type,byte_size,width_px,height_px,duration_seconds,content_sha256,"
              + "storage_key,original_filename,alt_text,enabled)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          tenantId,
          normalize(input.name()),
          input.description() == null ? "" : input.description().trim(),
          probe.mediaType(),
          normalizeUpper(input.purpose()),
          probe.fileFormat(),
          probe.mimeType(),
          BigDecimal.valueOf(probe.byteSize()),
          probe.width() == null ? null : BigDecimal.valueOf(probe.width()),
          probe.height() == null ? null : BigDecimal.valueOf(probe.height()),
          null, // 时长不探测：需要解容器，JDK 不提供。留空表示「未知」而不是 0 秒
          probe.sha256(),
          key,
          originalFilename(file),
          emptyToNull(input.altText()),
          input.enabled());
      insertVersion(tenantId, id, 1, key, originalFilename(file), probe, "初始上传");
      replaceTags(tenantId, id, input.tagCodes());
    } catch (DataAccessException e) {
      // 元数据写失败就把刚写进去的对象撤掉：留下一个没有元数据的孤儿文件，
      // 之后谁也不知道它是什么、能不能删。清理失败只记日志——DB 事务已经回滚，
      // 拦住异常反而会把真正的失败原因盖掉。
      discardQuietly(key);
      throw conflict("媒体保存失败：同一租户内已存在内容相同的素材，或写入被并发占用。请刷新后重试");
    }
    audit(actor, id, "MediaUploaded", "字节数 " + written);
    return savedResult(id, false);
  }

  /**
   * 追加一个新版本。
   *
   * <p>版本号由服务层取当前最大值 +1，不接受调用方指定：让调用方传版本号等于允许 两个人同时传 3，而 {@code
   * UNIQUE(tenant_id,asset_id,version_no)} 只会把其中一个 变成 409 冲突，而不是告诉用户「当前已经到 v3 了」。
   *
   * <p>旧版本永远保留（3.4.1.2 要求「原图始终保留」）。新版本的元数据由 V18 的 {@code sync_media_asset_from_version}
   * 触发器同步到素材本体，本类不回写。
   */
  public MediaSaved addVersion(
      AccessService.Access actor, UUID assetId, MultipartFile file, MediaVersionUpload input) {
    assetRow(assetId);
    byte[] content = bytes(file);
    Probe probe = probe(content, null, file.getOriginalFilename());

    int next =
        sql.fetchOne(
                "select coalesce(max(version_no),0)+1 from media_asset_version"
                    + " where tenant_id=? and asset_id=?",
                currentTenantId(),
                assetId)
            .get(0, Integer.class);
    String extension = probe.fileFormat();
    String key = storageKey(currentTenantId(), OffsetDateTime.now(ZoneOffset.UTC), extension);
    long written = writeStorage(key, content);
    try {
      insertVersion(
          currentTenantId(),
          assetId,
          next,
          key,
          originalFilename(file),
          probe,
          input.note() == null ? "" : input.note().trim());
      sql.execute("update media_asset set version=version+1,updated_at=now() where id=?", assetId);
    } catch (DataAccessException e) {
      discardQuietly(key);
      throw conflict("新版本保存失败（并发写入或版本号冲突）。请刷新后重试");
    }
    audit(actor, assetId, "MediaVersionAdded", "v" + next + "，字节数 " + written);
    return savedResult(assetId, false);
  }

  private void insertVersion(
      UUID tenantId,
      UUID assetId,
      int versionNo,
      String key,
      String filename,
      Probe probe,
      String note) {
    sql.execute(
        "insert into media_asset_version(id,tenant_id,asset_id,version_no,storage_key,"
            + "original_filename,file_format,mime_type,byte_size,width_px,height_px,"
            + "duration_seconds,content_sha256,note)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        tenantId,
        assetId,
        versionNo,
        key,
        filename,
        probe.fileFormat(),
        probe.mimeType(),
        BigDecimal.valueOf(probe.byteSize()),
        probe.width() == null ? null : BigDecimal.valueOf(probe.width()),
        probe.height() == null ? null : BigDecimal.valueOf(probe.height()),
        null, // 时长不探测：需要解容器，JDK 不提供。留空表示「未知」而不是 0 秒
        probe.sha256(),
        note == null ? "" : note);
  }

  // ============================================================ 列表 / 详情

  /**
   * 素材列表：关键词 + 媒体类型 / 用途 / 标签 / 完整性 / 引用状态 / 启用状态筛选。
   *
   * <p>关键词覆盖名称、描述、原始文件名与标签：运营手上往往只有一个文件名 （供应商给的「IMG_8823.jpg」），只搜显示名会搜不到。
   */
  public MediaPage list(MediaQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    UUID tenantId = currentTenantId();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds);

    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*)" + FROM_ASSET + sqlWhere,
                concat(List.of(tenantId), binds).toArray())
            .get(0, Long.class);
    long imageCount =
        sql.fetchOne(
                "select count(*) from media_asset where tenant_id=? and media_type='IMAGE'",
                tenantId)
            .get(0, Long.class);
    long videoCount =
        sql.fetchOne(
                "select count(*) from media_asset where tenant_id=? and media_type='VIDEO'",
                tenantId)
            .get(0, Long.class);
    long failing =
        sql.fetchOne(
                "select count(*) from media_asset where tenant_id=?"
                    + " and integrity_status in ('MISSING','SIZE_MISMATCH','CONTENT_MISMATCH')",
                tenantId)
            .get(0, Long.class);
    long unreferenced =
        sql.fetchOne(
                "select count(*) from media_asset a where a.tenant_id=? and not exists"
                    + " (select 1 from media_reference r where r.tenant_id=a.tenant_id and r.asset_id=a.id)",
                tenantId)
            .get(0, Long.class);
    long disabled =
        sql.fetchOne("select count(*) from media_asset where tenant_id=? and not enabled", tenantId)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            SELECT_COLUMNS
                + FROM_ASSET
                + sqlWhere
                + " order by a.updated_at desc,a.id desc limit ? offset ?",
            concat(List.of(tenantId), concat(binds, List.of(size, (long) page * size))).toArray());
    List<MediaRow> items = withTags(rows.stream().map(this::toRow).toList());
    return new MediaPage(
        items,
        total,
        page,
        size,
        imageCount,
        videoCount,
        failing,
        unreferenced,
        disabled,
        "失效检测覆盖对象存储这一侧：MISSING=文件已不在存储里，SIZE_MISMATCH=字节数变了，"
            + "CONTENT_MISMATCH=同尺寸但内容变了。三者都会挡住删除保护之外的下游使用。");
  }

  private void conditions(MediaQuery query, List<String> where, List<Object> binds) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      String pattern = escapeLike(query.keyword().trim());
      // exists 而不是 join media_asset_tag：join 会让一个挂了 3 个标签的素材在结果里出现 3 次。
      where.add(
          "(a.name ilike ? escape '!' or a.description ilike ? escape '!'"
              + " or a.original_filename ilike ? escape '!'"
              + " or a.storage_key ilike ? escape '!'"
              + " or exists (select 1 from media_asset_tag t join media_tag g"
              + "   on g.tenant_id=t.tenant_id and g.id=t.tag_id"
              + "  where t.tenant_id=a.tenant_id and t.asset_id=a.id"
              + "   and (g.code ilike ? escape '!' or g.name ilike ? escape '!')))");
      for (int i = 0; i < 6; i++) binds.add(pattern);
    }
    if (query.mediaType() != null && !query.mediaType().isBlank()) {
      String problem = mediaTypeProblem(query.mediaType());
      CatalogService.check(problem == null, problem);
      where.add("a.media_type=?");
      binds.add(normalizeUpper(query.mediaType()));
    }
    if (query.purpose() != null && !query.purpose().isBlank()) {
      String problem = purposeProblem(query.purpose());
      CatalogService.check(problem == null, problem);
      where.add("a.purpose=?");
      binds.add(normalizeUpper(query.purpose()));
    }
    if (query.tagCode() != null && !query.tagCode().isBlank()) {
      where.add(
          "exists (select 1 from media_asset_tag t join media_tag g"
              + " on g.tenant_id=t.tenant_id and g.id=t.tag_id"
              + " where t.tenant_id=a.tenant_id and t.asset_id=a.id and g.code=?)");
      binds.add(normalizeUpper(query.tagCode()));
    }
    if (query.integrityStatus() != null && !query.integrityStatus().isBlank()) {
      String status = normalizeUpper(query.integrityStatus());
      CatalogService.check(
          INTEGRITY_STATUSES.contains(status),
          "完整性状态只能是 " + String.join(" / ", INTEGRITY_STATUSES));
      where.add("a.integrity_status=?");
      binds.add(status);
    }
    if (query.referenced() != null) {
      String exists =
          "exists (select 1 from media_reference r where r.tenant_id=a.tenant_id and r.asset_id=a.id)";
      where.add(query.referenced() ? exists : "not " + exists);
    }
    if (query.enabled() != null) {
      where.add("a.enabled=?");
      binds.add(query.enabled());
    }
  }

  /** 素材详情：本体 + 版本 + 标签 + 引用 + 完整性历史 + 删除保护判定，同一时刻的一致快照。 */
  public MediaDetail detail(UUID id) {
    MediaRow asset = toRow(assetRow(id));
    List<MediaReference> references = references(id);
    List<MediaVersion> versions = versions(id);
    boolean deletable = references.isEmpty();
    return new MediaDetail(
        asset,
        versions,
        tagsOf(id),
        references,
        integrityChecks(id),
        deletable,
        deletable ? null : deleteBlockedReason(references.size()),
        "版本历史永远保留：3.4.1.2 的处理结果落成新版本，原图不被覆盖。" + "格式、宽高、时长与哈希都是从文件字节探测的，不接受填写。");
  }

  private MediaRow toRow(Record r) {
    return new MediaRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("media_type", String.class),
        mediaTypeLabel(r.get("media_type", String.class)),
        r.get("purpose", String.class),
        purposeLabel(r.get("purpose", String.class)),
        r.get("file_format", String.class),
        r.get("mime_type", String.class),
        r.get("byte_size", BigDecimal.class),
        r.get("width_px", BigDecimal.class),
        r.get("height_px", BigDecimal.class),
        r.get("duration_seconds", BigDecimal.class),
        r.get("content_sha256", String.class),
        r.get("storage_key", String.class),
        r.get("original_filename", String.class),
        r.get("alt_text", String.class),
        r.get("current_version", Integer.class),
        r.get("version_count", Integer.class),
        r.get("reference_count", Long.class),
        List.of(),
        r.get("integrity_status", String.class),
        integrityLabel(r.get("integrity_status", String.class)),
        r.get("integrity_detail", String.class),
        instant(r.get("integrity_checked_at", OffsetDateTime.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 批量补标签编码：一次查询按素材归拢，避免每行各查一次（N+1）。 */
  private List<MediaRow> withTags(List<MediaRow> rows) {
    if (rows.isEmpty()) return rows;
    Map<UUID, List<String>> byAsset = new LinkedHashMap<>();
    for (Record r : tagsOfRows(rows.stream().map(MediaRow::id).toList()))
      byAsset
          .computeIfAbsent(r.get("asset_id", UUID.class), k -> new ArrayList<>())
          .add(r.get("code", String.class));
    return rows.stream()
        .map(row -> withTags(row, byAsset.getOrDefault(row.id(), List.of())))
        .toList();
  }

  private static MediaRow withTags(MediaRow row, List<String> tags) {
    return new MediaRow(
        row.id(),
        row.name(),
        row.description(),
        row.mediaType(),
        row.mediaTypeLabel(),
        row.purpose(),
        row.purposeLabel(),
        row.fileFormat(),
        row.mimeType(),
        row.byteSize(),
        row.widthPx(),
        row.heightPx(),
        row.durationSeconds(),
        row.contentSha256(),
        row.storageKey(),
        row.originalFilename(),
        row.altText(),
        row.currentVersion(),
        row.versionCount(),
        row.referenceCount(),
        tags,
        row.integrityStatus(),
        row.integrityLabel(),
        row.integrityDetail(),
        row.integrityCheckedAt(),
        row.enabled(),
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }

  // ============================================================ 编辑

  /**
   * 改素材：名称 / 用途 / 描述 / 替代文本 / 标签 / 启停，走 version 乐观锁。
   *
   * <p><b>媒体类型与文件本体建后不可改</b>。媒体类型决定下游 3.4.1.2 选哪条处理管线， 改它等于让一个素材在历史上既是图片又是视频；文件本体要走「新版本」（{@link
   * #addVersion}）， 因为旧版本必须保留（3.4.1.2：原图始终保留）。
   */
  public MediaSaved update(AccessService.Access actor, UUID id, MediaUpdate input) {
    String problem =
        firstNonNull(
            nameProblem(input.name()),
            purposeProblem(input.purpose()),
            tagsSizeProblem(input.tagCodes() == null ? 0 : input.tagCodes().size()));
    CatalogService.check(problem == null, problem);
    assetRow(id);
    int updated;
    try {
      updated =
          sql.execute(
              "update media_asset set name=?,purpose=?,description=?,alt_text=?,enabled=?,"
                  + "version=version+1,updated_at=now() where id=? and version=?",
              normalize(input.name()),
              normalizeUpper(input.purpose()),
              input.description() == null ? "" : input.description().trim(),
              emptyToNull(input.altText()),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("素材保存失败：请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "素材已被他人修改，请刷新后重试");
    replaceTags(currentTenantId(), id, input.tagCodes());
    audit(actor, id, "MediaUpdated", null);
    return savedResult(id, false);
  }

  /** 启用停用。停用后不再出现在新建引用的候选里，已有的引用与版本照常可读。 */
  public void setEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update media_asset set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "MediaEnabledChanged", null);
  }

  /**
   * 删除素材：要求**没有引用**（蓝图点名的「有引用不可删」）。
   *
   * <p>先在应用层判一次并给出「被哪个 SPU/SKU 引用着」这种可执行信息—— DB 触发器（V18 的 refuse_media_asset_delete）会兜住并发窗口，但它抛的
   * 23503 对运营没有可读内容。
   *
   * <p>删除时把**所有版本**的对象一起删掉：只删当前版本会留下一堆没人知道是什么的 孤儿文件。顺序是先删元数据再删对象——反过来时元数据还指着已被删掉的键， 列表页会显示一个点开 404
   * 的素材。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = assetRow(id);
    CatalogService.check(row.get("version", Long.class) == version, "素材已被他人修改，请刷新后重试");
    List<MediaReference> references = references(id);
    CatalogService.check(references.isEmpty(), deleteBlockedReason(references.size()));
    List<String> keys =
        sql.fetch("select storage_key from media_asset_version where asset_id=?", id).stream()
            .map(r -> r.get("storage_key", String.class))
            .toList();
    sql.execute("delete from media_asset where id=?", id);
    audit(actor, id, "MediaDeleted", "连带删除 " + keys.size() + " 个版本对象");
    for (String key : keys) discardQuietly(key);
  }

  private String deleteBlockedReason(int referenceCount) {
    return "素材仍被 "
        + referenceCount
        + " 处引用，不可删除：请先在对应商品上解除引用。"
        + "（刊登侧的 listing 引用由 04 交付后写入，届时这里会计入）";
  }

  // ============================================================ 引用计数（被哪些 SPU/SKU 使用）

  /** 引用列表：带被引用对象的编码与名称（当前名称，重命名后自动跟着变）。 */
  public List<MediaReference> references(UUID assetId) {
    assetRow(assetId);
    var rows =
        sql.fetch(
            "select r.id,r.owner_type,r.spu_id,r.sku_id,r.note,r.created_at,"
                + "coalesce(p.code,s.code) as owner_code, coalesce(p.name,s.name) as owner_name"
                + " from media_reference r"
                + " left join spu p on r.spu_id is not null and p.tenant_id=r.tenant_id and p.id=r.spu_id"
                + " left join sku s on r.sku_id is not null and s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + " where r.asset_id=? order by r.created_at desc,r.id desc",
            assetId);
    return rows.stream()
        .map(
            r ->
                new MediaReference(
                    r.get("id", UUID.class),
                    assetId,
                    r.get("owner_type", String.class),
                    "SPU".equals(r.get("owner_type", String.class)) ? "SPU 商品" : "SKU",
                    r.get("spu_id", UUID.class),
                    r.get("sku_id", UUID.class),
                    r.get("owner_code", String.class),
                    r.get("owner_name", String.class),
                    r.get("note", String.class),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .toList();
  }

  /**
   * 「这个 SPU / SKU 用了哪些素材」——商品详情页与刊登侧反向查引用。
   *
   * <p>join 要写在 {@code FROM_ASSET} <b>之后</b>：{@code FROM_ASSET} 已经是完整的 {@code from media_asset a
   * where a.tenant_id=?}，把 join 拼在它前面会得到 {@code ... as version_count join media_reference ...}
   * 这种语法错误。
   */
  public List<MediaRow> referencesOf(String ownerType, UUID spuId, UUID skuId) {
    String problem = referenceProblem(ownerType, spuId, skuId);
    CatalogService.check(problem == null, problem);
    var rows =
        sql.fetch(
            SELECT_COLUMNS
                + FROM_ASSET
                + " and exists (select 1 from media_reference r"
                + " where r.tenant_id=a.tenant_id and r.asset_id=a.id and r.owner_type=? and"
                + (spuId != null ? " r.spu_id=?" : " r.sku_id=?")
                + ") order by a.updated_at desc,a.id desc",
            spuId != null
                ? new Object[] {currentTenantId(), ownerType, spuId}
                : new Object[] {currentTenantId(), ownerType, skuId});
    return withTags(rows.stream().map(this::toRow).toList());
  }

  /**
   * 登记一条引用。
   *
   * <p>引用必须指向**本租户真实存在**的 SPU/SKU：V18 的复合外键会拒跨租户引用， 但报出来的是约束名；这里先给「商品 / SKU 不存在或不属于当前租户」这种可读原因。
   *
   * <p>重复引用被 V18 的 {@code media_reference_dedupe} 表达式唯一索引拒成 409—— 引用计数必须是被引对象的真实条数，重复挂一次会让计数翻倍。
   */
  public MediaReferenceSaved addReference(
      AccessService.Access actor, UUID assetId, MediaReferenceInput input) {
    String problem = referenceProblem(input.ownerType(), input.spuId(), input.skuId());
    CatalogService.check(problem == null, problem);
    assetRow(assetId);
    String ownerType = normalizeUpper(input.ownerType());
    if ("SPU".equals(ownerType)) {
      var row =
          sql.fetchOne(
              "select id from spu where tenant_id=? and id=?", currentTenantId(), input.spuId());
      CatalogService.check(row != null, "商品不存在或不属于当前租户");
    } else {
      var row =
          sql.fetchOne(
              "select id from sku where tenant_id=? and id=?", currentTenantId(), input.skuId());
      CatalogService.check(row != null, "SKU 不存在或不属于当前租户");
    }
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into media_reference(id,tenant_id,asset_id,owner_type,spu_id,sku_id,note)"
              + " values(?,?,?,?,?,?,?)",
          id,
          currentTenantId(),
          assetId,
          ownerType,
          input.spuId(),
          input.skuId(),
          input.note() == null ? "" : input.note().trim());
    } catch (DataAccessException e) {
      throw conflict("该素材已被这个" + ("SPU".equals(ownerType) ? "商品" : "SKU") + "引用，无需重复登记");
    }
    audit(
        actor,
        assetId,
        "MediaReferenced",
        ownerType + " " + (input.spuId() == null ? input.skuId() : input.spuId()));
    return referenceSaved(assetId);
  }

  /** 解除一条引用。删引用行是正常动作（商品下架后不再用它），不是「删素材」。 */
  public MediaReferenceSaved removeReference(
      AccessService.Access actor, UUID assetId, UUID referenceId) {
    assetRow(assetId);
    int updated =
        sql.execute("delete from media_reference where asset_id=? and id=?", assetId, referenceId);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, assetId, "MediaUnreferenced", null);
    return referenceSaved(assetId);
  }

  private MediaReferenceSaved referenceSaved(UUID assetId) {
    long count = referenceCount(assetId);
    return new MediaReferenceSaved(
        null, assetId, count, count == 0, count == 0 ? null : deleteBlockedReason((int) count));
  }

  // ============================================================ 版本

  /** 版本历史：按版本号倒序。旧版本永远保留，可随时回读。 */
  public List<MediaVersion> versions(UUID assetId) {
    assetRow(assetId);
    var rows =
        sql.fetch(
            "select id,version_no,storage_key,original_filename,file_format,mime_type,"
                + "byte_size,width_px,height_px,duration_seconds,content_sha256,note,created_at"
                + " from media_asset_version where asset_id=? order by version_no desc",
            assetId);
    int current =
        sql.fetchOne("select current_version from media_asset where id=?", assetId)
            .get(0, Integer.class);
    return rows.stream()
        .map(
            r ->
                new MediaVersion(
                    r.get("id", UUID.class),
                    r.get("version_no", Integer.class),
                    r.get("storage_key", String.class),
                    r.get("original_filename", String.class),
                    r.get("file_format", String.class),
                    r.get("mime_type", String.class),
                    r.get("byte_size", BigDecimal.class),
                    r.get("width_px", BigDecimal.class),
                    r.get("height_px", BigDecimal.class),
                    r.get("duration_seconds", BigDecimal.class),
                    r.get("content_sha256", String.class),
                    r.get("note", String.class),
                    r.get("version_no", Integer.class) == current,
                    instant(r.get("created_at", OffsetDateTime.class))))
        .toList();
  }

  // ============================================================ 失效检测

  /**
   * 校验单个素材的存储完整性：真的把对象读回来算哈希。
   *
   * <p>不信任元数据里那行 {@code content_sha256}——它记录的是**上传当时**的字节， 文件在之后被删、被覆盖、被迁移都只有读回来才知道。这正是蓝图说的「失效检测」。
   *
   * <p>每次结果落一行 {@code media_integrity_check}（不覆盖历史）：什么时候发现 文件不见了是排查素材库故障时要回溯的事实。
   */
  public MediaIntegrityResult verify(UUID assetId) {
    var row = assetRow(assetId);
    String key = row.get("storage_key", String.class);
    long expected = row.get("byte_size", BigDecimal.class).longValue();
    String expectedSha = row.get("content_sha256", String.class);

    String status;
    Long actualSize = null;
    String actualSha = null;
    String detail;
    if (!storage.exists(key)) {
      status = "MISSING";
      detail = "存储里找不到这个键（文件被删除，或对象存储迁移后键失效）";
    } else {
      actualSize = storage.size(key).orElse(-1L);
      if (actualSize != expected) {
        status = "SIZE_MISMATCH";
        detail = "字节数变了：元数据记 " + expected + "，存储里是 " + actualSize;
      } else {
        try {
          actualSha = sha256(storage.read(key));
        } catch (IOException | IllegalArgumentException e) {
          // 读失败**不等于**文件缺失：可能是存储临时不可用、权限问题或键不合法。
          // 把它报成 MISSING 会让运维去清理一个其实还在的文件——比误报更危险的方向。
          // 这里不新增 CHECK 取值，而是明确报 503 并说明原因：
          // 结论只能是「验不了」，不能是「验过了，是坏的」。
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "无法读取媒体对象（键 " + key + "）：" + e.getMessage() + "。这是存储侧故障，不是文件缺失：请修复存储后重试，本次不记为校验结论");
        }
        if (!actualSha.equals(expectedSha)) {
          status = "CONTENT_MISMATCH";
          detail = "同尺寸但内容变了：元数据记 " + expectedSha + "，实际 " + actualSha;
        } else {
          status = "VERIFIED";
          detail = "字节数与 SHA-256 均一致";
        }
      }
    }
    return recordIntegrity(assetId, status, detail, expected, actualSize, expectedSha, actualSha);
  }

  /**
   * 批量巡检：整个租户的素材逐个验一遍。
   *
   * <p>上限 200 个：一次扫几万个素材会把存储读打成 IO 风暴，而且这个动作是运维手动触发的， 需要的话按页分批跑。返回里明确给出上限，避免调用方以为扫全了。
   */
  public MediaIntegritySweep verifyAll() {
    var ids =
        sql
            .fetch(
                "select id from media_asset where tenant_id=? order by updated_at desc limit 200",
                currentTenantId())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    List<MediaIntegrityResult> results = new ArrayList<>(ids.size());
    for (UUID id : ids) results.add(verify(id));
    long healthy = results.stream().filter(r -> "VERIFIED".equals(r.integrityStatus())).count();
    long missing = results.stream().filter(r -> "MISSING".equals(r.integrityStatus())).count();
    long sizeMismatch =
        results.stream().filter(r -> "SIZE_MISMATCH".equals(r.integrityStatus())).count();
    long contentMismatch =
        results.stream().filter(r -> "CONTENT_MISMATCH".equals(r.integrityStatus())).count();
    return new MediaIntegritySweep(
        results.size(),
        healthy,
        missing,
        sizeMismatch,
        contentMismatch,
        results,
        "单次最多校验 200 个素材（按最近更新排序）。存量更大时请分批调用。");
  }

  private MediaIntegrityResult recordIntegrity(
      UUID assetId,
      String status,
      String detail,
      long expectedSize,
      Long actualSize,
      String expectedSha,
      String actualSha) {
    UUID checkId = Ids.next();
    // 只在真的重算过哈希时存实际值：MISSING 时把上一个成功值抄一遍冒充本次结果，
    // 是比留空更糟的一种记录方式。
    sql.execute(
        "insert into media_integrity_check(id,tenant_id,asset_id,integrity_status,"
            + "byte_size_expected,byte_size_actual,sha256_expected,sha256_actual,detail)"
            + " values(?,?,?,?,?,?,?,?,?)",
        checkId,
        currentTenantId(),
        assetId,
        status,
        BigDecimal.valueOf(expectedSize),
        actualSize == null || actualSize < 0 ? null : BigDecimal.valueOf(actualSize),
        expectedSha,
        actualSha,
        detail);
    sql.execute(
        "update media_asset set integrity_status=?,integrity_detail=?,integrity_checked_at=now(),"
            + "version=version+1,updated_at=now() where id=?",
        status,
        detail,
        assetId);
    return new MediaIntegrityResult(
        assetId,
        status,
        integrityLabel(status),
        referenceCount(assetId),
        "VERIFIED".equals(status),
        detail,
        integrityCheck(checkId));
  }

  /** 完整性校验历史（最近若干次），供详情页回溯「什么时候开始不对的」。 */
  public List<MediaIntegrityCheck> integrityChecks(UUID assetId) {
    assetRow(assetId);
    return sql
        .fetch(
            "select id,integrity_status,byte_size_expected,byte_size_actual,sha256_expected,"
                + "sha256_actual,detail,checked_at from media_integrity_check"
                + " where asset_id=? order by checked_at desc,id desc limit 20",
            assetId)
        .stream()
        .map(
            r ->
                new MediaIntegrityCheck(
                    r.get("id", UUID.class),
                    assetId,
                    r.get("integrity_status", String.class),
                    integrityLabel(r.get("integrity_status", String.class)),
                    r.get("byte_size_expected", BigDecimal.class),
                    r.get("byte_size_actual", BigDecimal.class),
                    r.get("sha256_expected", String.class),
                    r.get("sha256_actual", String.class),
                    r.get("detail", String.class),
                    instant(r.get("checked_at", OffsetDateTime.class))))
        .toList();
  }

  private MediaIntegrityCheck integrityCheck(UUID id) {
    var row =
        sql.fetchOne(
            "select id,integrity_status,byte_size_expected,byte_size_actual,sha256_expected,"
                + "sha256_actual,detail,checked_at from media_integrity_check where id=?",
            id);
    if (row == null) throw CatalogService.missing();
    return new MediaIntegrityCheck(
        row.get("id", UUID.class),
        null,
        row.get("integrity_status", String.class),
        integrityLabel(row.get("integrity_status", String.class)),
        row.get("byte_size_expected", BigDecimal.class),
        row.get("byte_size_actual", BigDecimal.class),
        row.get("sha256_expected", String.class),
        row.get("sha256_actual", String.class),
        row.get("detail", String.class),
        instant(row.get("checked_at", OffsetDateTime.class)));
  }

  // ============================================================ 分类标签

  /** 标签字典（带使用数）。按标签筛素材的候选来源。 */
  public List<MediaTagRow> tags() {
    return sql
        .fetch(
            "select g.id,g.code,g.name,g.note,g.enabled,g.version,g.created_at,g.updated_at,"
                + "(select count(*) from media_asset_tag t where t.tenant_id=g.tenant_id"
                + "  and t.tag_id=g.id) as usage_count"
                + " from media_tag g where g.tenant_id=? order by g.code",
            currentTenantId())
        .stream()
        .map(
            r ->
                new MediaTagRow(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("note", String.class),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                    r.get("usage_count", Long.class),
                    r.get("version", Long.class),
                    instant(r.get("created_at", OffsetDateTime.class)),
                    instant(r.get("updated_at", OffsetDateTime.class))))
        .toList();
  }

  /** 建标签或改标签（id 为空即新建），走 version 乐观锁。编码建后不可改。 */
  public MediaTagSaved saveTag(AccessService.Access actor, UUID id, MediaTagInput input) {
    String problem = firstNonNull(tagCodeProblem(input.code()), tagNameProblem(input.name()));
    CatalogService.check(problem == null, problem);
    String code = normalizeUpper(input.code());
    String name = normalize(input.name());

    if (id == null) {
      if (sql.fetchOne("select id from media_tag where code=?", code) != null)
        throw conflict("标签编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into media_tag(id,tenant_id,code,name,note,enabled) values(?,?,?,?,?,?)",
            newId,
            currentTenantId(),
            code,
            name,
            input.note() == null ? "" : input.note().trim(),
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("标签编码已被并发创建或占用，请刷新后重试");
      }
      audit(actor, newId, "MediaTagSaved", code);
      return tagSaved(newId);
    }

    var row = tagRow(id);
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "标签编码建后不可修改：筛选条件与批量刊登按 code 对齐这个标签，改编码会让已有筛选静默失效。要改请新建一个标签");
    int updated;
    try {
      updated =
          sql.execute(
              "update media_tag set name=?,note=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              name,
              input.note() == null ? "" : input.note().trim(),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("标签保存失败，请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "标签已被他人修改，请刷新后重试");
    audit(actor, id, "MediaTagSaved", code);
    return tagSaved(id);
  }

  /** 删除标签。仍被素材使用时被拒（V18 的 refuse_media_tag_delete 兜底）， 服务层先给可读原因：要清理用「停用」——停用后不再出现在新建筛选里，历史关联保留。 */
  public void deleteTag(AccessService.Access actor, UUID id, long version) {
    var row = tagRow(id);
    CatalogService.check(row.get("version", Long.class) == version, "标签已被他人修改，请刷新后重试");
    long used = tagUsage(id);
    CatalogService.check(
        used == 0,
        "标签「"
            + row.get("code", String.class)
            + "」仍被 "
            + used
            + " 个素材使用，不可删除：请改用停用（停用后不再出现在新建筛选里，历史关联保留）");
    sql.execute("delete from media_tag where id=?", id);
    audit(actor, id, "MediaTagDeleted", null);
  }

  /** 标签启用停用。停用后不再出现在新建素材的标签候选里，已有挂载照常显示。 */
  public void setTagEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    int updated =
        sql.execute(
            "update media_tag set enabled=?,version=version+1,updated_at=now() where id=?",
            enabled,
            id);
    if (updated != 1) throw CatalogService.missing();
    audit(actor, id, "MediaTagEnabledChanged", null);
  }

  /**
   * 整体替换素材的标签挂载。
   *
   * <p>编码不存在时自动建标签：运营上传时顺手打一个新标签是正常动作， 为此先退出上传去建标签再重传，体验上等于强迫人工两步。 自动建的标签 {@code enabled=true}
   * 且与手工建的没有区别——它们在字典里是同一批。
   */
  private void replaceTags(UUID tenantId, UUID assetId, List<String> tagCodes) {
    sql.execute("delete from media_asset_tag where asset_id=?", assetId);
    if (tagCodes == null || tagCodes.isEmpty()) return;
    for (String raw : tagCodes) {
      String code = normalizeUpper(raw);
      String problem = tagCodeProblem(code);
      CatalogService.check(problem == null, problem);
      UUID tagId = resolveTagId(tenantId, code);
      try {
        sql.execute(
            "insert into media_asset_tag(tenant_id,asset_id,tag_id) values(?,?,?)",
            tenantId,
            assetId,
            tagId);
      } catch (DataAccessException e) {
        // 同一个编码在提交列表里出现两次（大小写不同也算同一个）：后一条覆盖前一条，
        // 而不是留下两条重复挂载让标签筛选出现同一素材两次。
        CatalogService.check(false, "标签重复：" + code + "（同一个素材上同一个标签只能挂一次）");
      }
    }
  }

  private UUID resolveTagId(UUID tenantId, String code) {
    var row = sql.fetchOne("select id from media_tag where tenant_id=? and code=?", tenantId, code);
    if (row != null) return row.get("id", UUID.class);
    UUID id = Ids.next();
    try {
      // 自动建的标签用**编码**当显示名，并按 name 列的上限截断：
      // 编码最长 32、名称最长 100，写满长度刚好能过 CHECK，但 tagCodeProblem
      // 允许 32 位编码而这里不截断就会在 DB 层被 media_tag_check 拒掉——
      // 一个「编码合法但自动建标签失败」的组合是纯粹的缺陷。
      sql.execute(
          "insert into media_tag(id,tenant_id,code,name) values(?,?,?,?)",
          id,
          tenantId,
          code,
          code.length() > 100 ? code.substring(0, 100) : code);
    } catch (DataAccessException e) {
      // 并发建同名标签：取回别人刚建的那条，不要因为竞态让整个上传失败。
      var again =
          sql.fetchOne("select id from media_tag where tenant_id=? and code=?", tenantId, code);
      if (again == null) throw conflict("标签创建失败，请重试");
      return again.get("id", UUID.class);
    }
    return id;
  }

  /** 素材的标签（code → name）。 */
  private Map<String, String> tagsOf(UUID assetId) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (Record r : tagsOfRows(List.of(assetId)))
      tags.put(r.get("code", String.class), r.get("name", String.class));
    return tags;
  }

  private List<Record> tagsOfRows(List<UUID> assetIds) {
    if (assetIds.isEmpty()) return List.of();
    return sql.fetch(
        "select t.asset_id,g.code,g.name from media_asset_tag t join media_tag g"
            + " on g.tenant_id=t.tenant_id and g.id=t.tag_id"
            + " where t.asset_id in ("
            + placeholders(assetIds)
            + ") order by g.code",
        assetIds.toArray());
  }

  // ============================================================ 概览

  /** 概览：规模与治理缺口指标。 */
  public MediaOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select count(*) as asset_count,"
                + "count(*) filter (where enabled) as enabled_count,"
                + "count(*) filter (where not enabled) as disabled_count,"
                + "count(*) filter (where media_type='IMAGE') as image_count,"
                + "count(*) filter (where media_type='VIDEO') as video_count,"
                + "coalesce(sum(byte_size),0) as byte_total,"
                + "count(*) filter (where integrity_status in"
                + " ('MISSING','SIZE_MISMATCH','CONTENT_MISMATCH')) as failing_count,"
                + "count(*) filter (where integrity_status='MISSING') as missing_count"
                + " from media_asset where tenant_id=?",
            tenantId);
    long references =
        sql.fetchOne("select count(*) from media_reference where tenant_id=?", tenantId)
            .get(0, Long.class);
    long referencedAssets =
        sql.fetchOne(
                "select count(*) from (select distinct asset_id from media_reference where tenant_id=?) x",
                tenantId)
            .get(0, Long.class);
    long assetCount = counts.get("asset_count", Long.class);
    long tagCount =
        sql.fetchOne("select count(*) from media_tag where tenant_id=?", tenantId)
            .get(0, Long.class);
    long versionCount =
        sql.fetchOne("select count(*) from media_asset_version where tenant_id=?", tenantId)
            .get(0, Long.class);
    return new MediaOverview(
        assetCount,
        counts.get("enabled_count", Long.class),
        counts.get("disabled_count", Long.class),
        counts.get("image_count", Long.class),
        counts.get("video_count", Long.class),
        counts.get("byte_total", BigDecimal.class).longValue(),
        references,
        referencedAssets,
        assetCount - referencedAssets,
        counts.get("failing_count", Long.class),
        counts.get("missing_count", Long.class),
        tagCount,
        versionCount,
        storageWritable() ? 1L : 0L,
        "二进制在对象存储，元数据在本地库（本服务的数据库）。"
            + "missingCount 是「元数据说有、存储里已经没有」的素材数——"
            + "这是素材库最该被盯住的数字：刊登侧引用了它，用户点开却是 404。");
  }

  /**
   * 概览里的存储健康探针：写一个极小的对象再删掉，验证存储真的可写。
   *
   * <p>只在概览里做，且只做一次极小的往返。不验的话，「存储挂了就素材库整体不可用」 这件事要等到运营上传失败时才发现，而那时他们已经整理好了一整批素材。
   *
   * <p>键必须落在**本租户的前缀下**：用固定的 {@code media/health/probe} 会让所有租户 共用同一个键，两个租户同时点概览就会互相删掉对方的探针对象，
   * 于是健康检查会随机报「不可写」——一个只在多租户下偶发、且极难定位的假故障。
   */
  private boolean storageWritable() {
    String key = "media/" + currentTenantId() + "/health/probe";
    try {
      storage.put(key, new byte[] {0});
      storage.delete(key);
      return true;
    } catch (IOException e) {
      log.warn("媒体存储不可写：{}", e.getMessage());
      return false;
    }
  }

  // ============================================================ 工具

  private byte[] bytes(MultipartFile file) {
    CatalogService.check(file != null && !file.isEmpty(), "请选择一个要上传的文件");
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw bad("读取上传文件失败：" + e.getMessage());
    }
  }

  private long writeStorage(String key, byte[] content) {
    try {
      return storage.put(key, content);
    } catch (IOException | IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "媒体存储写入失败（键 " + key + "）：" + e.getMessage());
    }
  }

  private void discardQuietly(String key) {
    try {
      storage.delete(key);
    } catch (IOException e) {
      // 对象删不掉时只记日志：DB 事务已经回滚，再抛异常会把真正的失败原因盖掉，
      // 而残留的是一个没人知道是什么的孤儿文件（比报错更需要被人知道，所以留日志）。
      log.warn("回滚后未能删除媒体对象 {}：{}", key, e.getMessage());
    }
  }

  private Optional<Record> existingBySha(String sha) {
    return Optional.ofNullable(
        sql.fetchOne(
            "select id,version from media_asset where tenant_id=? and content_sha256=?",
            currentTenantId(),
            sha));
  }

  /**
   * 取素材行，**必须用 {@link #SELECT_COLUMNS} 而不是 {@code select *}**。
   *
   * <p>{@link #toRow} 要读 {@code reference_count} 与 {@code version_count} 两个派生列， 它们只在 SELECT_COLUMNS
   * 里存在；用 {@code select *} 拿到的 Record 读它们会抛 「Field "version_count" is not contained in row
   * type」——一个与真正原因毫无关系的报错。
   */
  private Record assetRow(UUID id) {
    var row = sql.fetchOne(SELECT_COLUMNS + FROM_ASSET + " and a.id=?", currentTenantId(), id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record tagRow(UUID id) {
    var row = sql.fetchOne("select * from media_tag where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private long referenceCount(UUID assetId) {
    return sql.fetchOne("select count(*) from media_reference where asset_id=?", assetId)
        .get(0, Long.class);
  }

  private long tagUsage(UUID tagId) {
    return sql.fetchOne("select count(*) from media_asset_tag where tag_id=?", tagId)
        .get(0, Long.class);
  }

  private MediaSaved savedResult(UUID id, boolean deduplicated) {
    var row = assetRow(id);
    return new MediaSaved(
        id,
        row.get("version", Long.class),
        row.get("current_version", Integer.class),
        deduplicated,
        referenceCount(id),
        row.get("integrity_status", String.class),
        integrityLabel(row.get("integrity_status", String.class)));
  }

  private MediaTagSaved tagSaved(UUID id) {
    var row = tagRow(id);
    return new MediaTagSaved(
        id, row.get("code", String.class), row.get("version", Long.class), tagUsage(id));
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action, String detail) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  private static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private static String originalFilename(MultipartFile file) {
    String name = file.getOriginalFilename();
    String value = name == null || name.isBlank() ? "未命名文件" : normalize(name);
    return value.length() > 255 ? value.substring(0, 255) : value;
  }

  private static String emptyToNull(String value) {
    String trimmed = normalize(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var all = new ArrayList<Object>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MediaService.class);
}
