package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.8.2.1 导出：任务受理、记录查询、下载（带有效期）与作废。
 *
 * <p>四件事的分工：
 *
 * <ul>
 *   <li>{@link #create} 校验并冻结快照 → 落 {@code export_job} → 交给执行器（已受理）；</li>
 *   <li>{@link #jobs} / {@link #job} 读导出记录（条件、字段、行数、时间、操作人）；</li>
 *   <li>{@link #file} 下载：先判有效期，过期就把状态推进到 EXPIRED 并删掉存储对象；</li>
 *   <li>{@link #cancel} 作废一份不该再被下载的导出（导错范围的 PII 导出第一件事就是它）。</li>
 * </ul>
 *
 * <p><b>不写 audit_event</b>：与 3.8.1.1 的 {@code ImportJobService} 同一口径 ——
 * {@code export_job} 这一行本身就是蓝图点名的那条「导出记录」，
 * 再往 audit_event 抄一份只会多出一个可能与它不一致的副本。
 */
@Service
public class ExportJobService {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext sql;
  private final AccessService access;
  private final ExportDataSources sources;
  private final ExportJobExecutor executor;
  private final MediaStorage storage;
  private final int downloadTtlHours;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ExportJobService(
      DSLContext sql,
      AccessService access,
      ExportDataSources sources,
      ExportJobExecutor executor,
      MediaStorage storage,
      @Value("${EXPORT_DOWNLOAD_TTL_HOURS:24}") int downloadTtlHours) {
    this.sql = sql;
    this.access = access;
    this.sources = sources;
    this.executor = executor;
    this.storage = storage;
    this.downloadTtlHours = downloadTtlHours;
  }

  // ================================================================ 能力声明

  /**
   * 可导出数据源清单（每个自带字段、排序与筛选词表）。
   *
   * <p>前端据此渲染「选数据源 → 勾字段 → 填条件 → 选排序」四步，
   * 全部由后端声明，不在前端硬编码一份字段列表。
   */
  public ExportSourcePage sourcePage() {
    var items =
        sources.all().stream()
            .map(
                source ->
                    new ExportSourceInfo(
                        source.key(),
                        source.label(),
                        source.upstreamModule(),
                        source.permission(),
                        source.available(),
                        source.available() ? "" : source.unavailableNote(),
                        source.columns().stream().map(ExportColumn::info).toList(),
                        source.sorts().stream().map(ExportSort::info).toList(),
                        source.filters(),
                        source.defaultSortKey(),
                        source.maxRows(),
                        downloadTtlHours))
            .toList();
    return new ExportSourcePage(items);
  }

  // ================================================================ 受理

  /**
   * 受理一次导出，返回任务行（status=PENDING/RUNNING）。
   *
   * <p><b>返回即「已受理」，不是「已完成」</b>：蓝图写死大文件异步导出，
   * 文件由 {@link ExportJobExecutor} 在请求之外生成。行数与文件要轮询任务详情才拿得到。
   *
   * <p>校验全部在**开事务之前**做（{@link ExportRules} 是纯函数）：
   * 一次注定被拒的请求不该先占着一个租户事务。
   */
  public ExportJobRow create(AccessService.Access actor, ExportJobInput input) {
    var source = sources.require(input.sourceKey());
    var columns = ExportRules.resolveFields(source, input.fields());
    var sort = ExportRules.resolveSort(source, input.sort());
    boolean desc = ExportRules.resolveDesc(input.sort());
    ExportRules.requireSupportedFilters(source, input.filters());
    var piiFields = ExportRules.requirePiiAuthorization(columns, input.piiPurpose(), source.label());
    ExportRules.expiresInSeconds(downloadTtlHours);

    UUID id = Ids.next();
    var fieldKeys = columns.stream().map(ExportColumn::key).toList();
    var headers = columns.stream().map(ExportColumn::label).toList();
    var sortSnapshot = new ExportSortInput(sort.key(), desc ? "desc" : "asc");
    var permission = source.permission();

    // 条件为 null 时存 {} 而不是 null：读侧一律按「空对象」处理，
    // 「没有筛选条件」与「筛选条件快照丢了」不该是两种需要分别判断的形状。
    var filterSnapshot = input.filters() == null ? Map.of() : input.filters();
    sql.execute(
        "insert into export_job(id,tenant_id,source_key,status,filter_snapshot,selected_fields,"
            + "field_headers,sort_snapshot,includes_pii,pii_fields,pii_purpose,actor_id,department_id)"
            + " values(?,?,?,'PENDING',?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?,?)",
        id,
        actor.tenantId(),
        source.key(),
        json(filterSnapshot),
        json(fieldKeys),
        json(headers),
        json(sortSnapshot),
        !piiFields.isEmpty(),
        json(piiFields),
        piiFields.isEmpty() ? "" : input.piiPurpose().trim(),
        actor.userId(),
        // department_id 可空。用带类型的 NULL 而不是裸 null：裸 null 在批量绑定里
        // 会被 jOOQ 当成「不知道类型的空值」，而这一列要写进 uuid。
        actor.departmentId() == null
            ? org.jooq.impl.DSL.val(null, UUID.class)
            : actor.departmentId());

    executor.accept(actor, permission, id);
    return row(sql.fetchOne("select * from export_job where tenant_id=? and id=?", actor.tenantId(), id), sources);
  }

  // ================================================================ 查询

  /** 导出记录分页（条件、字段、行数、时间、操作人都在行上）。 */
  public ExportJobPage jobs(
      UUID tenantId, String sourceKey, String status, int page, int size) {
    sweepExpired(tenantId);
    int effectiveSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int effectivePage = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("tenant_id=?");
    binds.add(tenantId);
    if (sourceKey != null && !sourceKey.isBlank()) {
      where.add("source_key=?");
      binds.add(sourceKey.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.add("status=?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*) from export_job" + clause, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select * from export_job"
                + clause
                + " order by requested_at desc,id desc limit ? offset ?",
            append(binds, effectiveSize, (long) effectivePage * effectiveSize).toArray());
    return new ExportJobPage(rows.stream().map(this::row).toList(), total, effectivePage, effectiveSize);
  }

  public ExportJobRow job(UUID tenantId, UUID id) {
    sweepExpired(tenantId);
    var found = sql.fetchOne("select * from export_job where tenant_id=? and id=?", tenantId, id);
    if (found == null) throw notFound(id);
    return row(found);
  }

  // ================================================================ 下载

  /**
   * 取导出文件。
   *
   * <p><b>有效期在这里生效，而不是只在界面上显示</b>：过了 {@code expires_at} 就把状态
   * 推进到 EXPIRED、删掉存储对象，并回 410 Gone。一份含 PII 的文件过了有效期还留在磁盘上，
   * 等于有效期没生效。
   *
   * <p>RLS 在此生效：取不到就是「不存在或没有访问权限」，不区分二者，避免泄露
   * 「这个 id 在别的租户确实存在过」。
   */
  public ExportFile file(AccessService.Access actor, UUID id) {
    var found = sql.fetchOne("select * from export_job where tenant_id=? and id=?", actor.tenantId(), id);
    if (found == null) throw notFound(id);

    String status = found.get("status", String.class);
    if ("SUCCEEDED".equals(status)) {
      OffsetDateTime expiresAt = found.get("expires_at", OffsetDateTime.class);
      if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now(java.time.ZoneOffset.UTC))) {
        expire(actor, found);
        throw new ResponseStatusException(
            HttpStatus.GONE,
            "导出文件已过下载有效期（" + expiresAt.toInstant() + "），已清理；请重新发起一次导出。");
      }
    } else if ("EXPIRED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.GONE, "导出文件已过下载有效期并被清理；请重新发起一次导出。");
    } else if ("CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.GONE, "这份导出已被作废，不再提供下载。");
    } else {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "导出尚未完成（当前状态：" + statusLabel(status) + "），请稍候再下载。");
    }

    String storageKey = found.get("storage_key", String.class);
    if (!storage.exists(storageKey)) {
      // 记录说成功、存储里却没有：文件被外部清理了。推进到 EXPIRED 让它不再冒充成功。
      expire(actor, found);
      throw new ResponseStatusException(
          HttpStatus.GONE, "导出文件已不在存储中（可能被部署侧清理），请重新发起一次导出。");
    }
    byte[] content;
    try {
      content = storage.read(storageKey);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "读取导出文件失败，请联系管理员");
    }
    return new ExportFile(found.get("file_name", String.class), content.length, content);
  }

  /** 把一条已过期或已丢文件的 SUCCEEDED 记录推进到 EXPIRED，并删掉存储对象。 */
  private void expire(AccessService.Access actor, Record found) {
    var moved =
        sql.fetchOne(
            "update export_job set status='EXPIRED' where tenant_id=? and id=? and status='SUCCEEDED' returning id",
            actor.tenantId(),
            found.get("id", UUID.class));
    if (moved == null) return; // 已被别的请求抢先推进过了
    var key = found.get("storage_key", String.class);
    if (key == null) return;
    try {
      storage.delete(key);
    } catch (IOException e) {
      // 文件删不掉是运维问题，不是请求问题：记录已经是 EXPIRED、下载已被挡住，
      // 这里只记日志，残留文件由存储侧的清理任务兜底。
      org.slf4j.LoggerFactory.getLogger(ExportJobService.class)
          .warn("过期导出文件删除失败：{}", key, e);
    }
  }

  /**
   * 惰性过期扫描：把「已过 expires_at 但状态还没推进」的记录推进到 EXPIRED，**并删掉文件**。
   *
   * <p>不靠定时任务（本服务没有调度器，也不该为了一个状态位引入一个常驻线程）：
   * 读列表或读详情时顺手扫一次就够了。
   *
   * <p><b>删除是这个方法的一部分，不是可选项</b>：只把状态改成 EXPIRED 而把文件留在磁盘上，
   * 等于「有效期过了但文件还在」—— 对一份含联系方式的导出，那是一份没人认领的数据。
   * 所以这里先取出待过期行的 storage_key，批量推进状态后再逐个删除。
   * 删除失败只记日志：记录已是 EXPIRED、下载已被挡住，残留由存储侧清理兜底。
   */
  private void sweepExpired(UUID tenantId) {
    var keys =
        sql.fetch(
            "select storage_key from export_job where tenant_id=? and status='SUCCEEDED'"
                + " and expires_at is not null and expires_at < now() and storage_key is not null",
            tenantId);
    if (keys.isEmpty()) return;
    sql.execute(
        "update export_job set status='EXPIRED'"
            + " where tenant_id=? and status='SUCCEEDED' and expires_at is not null and expires_at < now()",
        tenantId);
    for (var key : keys) {
      try {
        storage.delete(key.get("storage_key", String.class));
      } catch (IOException e) {
        org.slf4j.LoggerFactory.getLogger(ExportJobService.class)
            .warn("过期导出文件删除失败：{}", key.get("storage_key", String.class), e);
      }
    }
  }

  // ================================================================ 作废

  /**
   * 作废一份导出。
   *
   * <p>两种入口：还没跑完的（PENDING/RUNNING）直接取消；已经生成文件的 SUCCEEDED
   * 也允许作废 —— 「导错范围了」时第一件事就是让它不能再下载，而不是等有效期自然过期。
   * 作废后清空 storage_key（DB CHECK 要求非成功态无键），而 file_name / row_count /
   * content_sha256 保留：这些是「当初导出了什么」的事实，删掉它们等于把审计也作废掉。
   */
  public ExportJobRow cancel(AccessService.Access actor, UUID id) {
    var found = sql.fetchOne("select * from export_job where tenant_id=? and id=?", actor.tenantId(), id);
    if (found == null) throw notFound(id);
    String status = found.get("status", String.class);
    if (List.of("FAILED", "CANCELLED", "EXPIRED").contains(status))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该导出已处于终态（" + statusLabel(status) + "），无法再作废。");

    sql.execute(
        "update export_job set status='CANCELLED',storage_key=null"
            + " where tenant_id=? and id=? and status=?",
        actor.tenantId(),
        id,
        status);
    var key = found.get("storage_key", String.class);
    if (key != null) {
      try {
        storage.delete(key);
      } catch (IOException e) {
        org.slf4j.LoggerFactory.getLogger(ExportJobService.class)
            .warn("作废导出文件删除失败：{}", key, e);
      }
    }
    return row(sql.fetchOne("select * from export_job where tenant_id=? and id=?", actor.tenantId(), id));
  }

  // ================================================================ 行组装

  static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "导出记录不存在或没有访问权限：" + id);
  }

  private ExportJobRow row(Record r) {
    return row(r, sources);
  }

  private ExportJobRow row(Record r, ExportDataSources registry) {
    String sourceKey = r.get("source_key", String.class);
    var source = registry.find(sourceKey);
    String status = r.get("status", String.class);
    var applied = new ArrayList<ExportAppliedFilter>();
    for (var entry : readFilterSnapshot(r).entrySet()) {
      var label =
          source == null
              ? entry.getKey()
              : source.filters().stream()
                  .filter(f -> f.key().equals(entry.getKey()))
                  .findFirst()
                  .map(ExportFilterInfo::label)
                  .orElse(entry.getKey());
      applied.add(new ExportAppliedFilter(entry.getKey(), label, String.valueOf(entry.getValue())));
    }
    var sort = readSort(r);
    var expiresAt = instant(r.get("expires_at", OffsetDateTime.class));
    boolean expired =
        expiresAt != null && OffsetDateTime.parse(expiresAt).isBefore(OffsetDateTime.now(java.time.ZoneOffset.UTC));
    boolean downloadable = "SUCCEEDED".equals(status) && !expired;
    return new ExportJobRow(
        r.get("id", UUID.class),
        sourceKey,
        source == null ? sourceKey : source.label(),
        status,
        statusLabel(status),
        readStrings(r, "selected_fields"),
        readStrings(r, "field_headers"),
        applied,
        sort.key(),
        sort.order(),
        r.get("row_count", Integer.class),
        r.get("file_name", String.class),
        r.get("byte_size", java.math.BigDecimal.class),
        r.get("content_sha256", String.class),
        r.get("includes_pii", Boolean.class),
        readStrings(r, "pii_fields"),
        r.get("pii_purpose", String.class),
        r.get("actor_id", UUID.class),
        r.get("department_id", UUID.class),
        instant(r.get("requested_at", OffsetDateTime.class)),
        instant(r.get("started_at", OffsetDateTime.class)),
        instant(r.get("finished_at", OffsetDateTime.class)),
        expiresAt,
        downloadable,
        downloadable ? "在有效期内可下载" : downloadNote(status, expired),
        r.get("error", String.class));
  }

  private static String downloadNote(String status, boolean expired) {
    if (expired) return "已过下载有效期";
    return switch (status) {
      case "PENDING" -> "排队中，尚未开始";
      case "RUNNING" -> "正在生成文件";
      case "FAILED" -> "导出失败";
      case "CANCELLED" -> "已被作废";
      case "EXPIRED" -> "已过下载有效期并被清理";
      default -> "当前不可下载";
    };
  }

  static String statusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "排队中";
      case "RUNNING" -> "生成中";
      case "SUCCEEDED" -> "已完成";
      case "FAILED" -> "失败";
      case "CANCELLED" -> "已作废";
      case "EXPIRED" -> "已过期";
      default -> status;
    };
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  // ================================================================ JSONB 读写

  private static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("导出快照序列化失败", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static LinkedHashMap<String, Object> readFilterSnapshot(Record r) {
    var raw = r.get("filter_snapshot", String.class);
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      return MAPPER.readValue(raw, LinkedHashMap.class);
    } catch (IOException e) {
      throw new IllegalStateException("导出条件快照解析失败", e);
    }
  }

  private static ExportSortInput readSort(Record r) {
    var raw = r.get("sort_snapshot", String.class);
    if (raw == null || raw.isBlank()) return new ExportSortInput(null, "desc");
    try {
      var node = MAPPER.readTree(raw);
      return new ExportSortInput(
          node.hasNonNull("key") ? node.get("key").asText() : null,
          node.hasNonNull("order") ? node.get("order").asText() : "desc");
    } catch (IOException e) {
      throw new IllegalStateException("导出排序快照解析失败", e);
    }
  }

  private static List<String> readStrings(Record r, String column) {
    var raw = r.get(column, String.class);
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return MAPPER.readValue(raw, List.class);
    } catch (IOException e) {
      throw new IllegalStateException("导出字段清单解析失败：" + column, e);
    }
  }

  /** 供执行器复用：读取一次执行所需的全部快照。 */
  static ExportSnapshot load(DSLContext sql, UUID tenantId, UUID id) {
    var r = sql.fetchOne("select * from export_job where tenant_id=? and id=?", tenantId, id);
    if (r == null) throw notFound(id);
    var raw = r.get("filter_snapshot", String.class);
    try {
      return new ExportSnapshot(
          r.get("id", UUID.class),
          r.get("source_key", String.class),
          readStrings(r, "selected_fields"),
          readStrings(r, "field_headers"),
          raw == null || raw.isBlank()
              ? new ExportFilterInput(null, null, null, null, null, null, null, null, null, null, null, null,
                  null, null, null, null, null, null)
              : MAPPER.readValue(raw, ExportFilterInput.class),
          readSort(r),
          r.get("requested_at", OffsetDateTime.class));
    } catch (IOException e) {
      throw new IllegalStateException("导出快照解析失败", e);
    }
  }

  /** 执行器需要的快照。刻意只含执行要用的字段，不把整行传过去。 */
  record ExportSnapshot(
      UUID id,
      String sourceKey,
      List<String> selectedFields,
      List<String> fieldHeaders,
      ExportFilterInput filters,
      ExportSortInput sort,
      OffsetDateTime requestedAt) {}

  /** CSV 转义按 RFC 4180；前导 =/+/-/@ 会被 Excel 当成公式执行，加前缀单引号切断。 */
  static String csv(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all;
  }
}