package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * catalog 服务（端口 8082）的**跨服务端口**，承担商品域的两件事：
 *
 * <ul>
 *   <li><b>导出</b>：{@code GET /catalog/skus} 逐页拉 SKU（通道 B：授权只读）；
 *   <li><b>导入</b>：{@code POST /catalog/import-jobs} 文件型导入作业（通道 C：委托属主执行）。
 * </ul>
 *
 * <p>⚠️ <b>本服务不建商品表、不抄商品</b>：商品主数据是 catalog 的事实，
 * 抄一份就会漂移（catalog 那边改了 SKU，本服务这边还留着旧的，导出的清单与真实商品对不上）。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（本服务 RLS + 上游 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就 503</b>，不返回空集合冒充「上游没有商品」——
 * 一个空清单在界面上会长成「这个类目真的没有商品」，而真相是「这一格没读出来」。
 */
@Service
public class CatalogProductPort {

  private final PlatformUpstreamClient client;

  public CatalogProductPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    this.client = PlatformUpstreamClient.to("catalog 服务", url);
  }

  // ================================================================ 导出：SKU 清单

  /** 上游一行 SKU（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuRow(
      UUID id,
      UUID spuId,
      String spuCode,
      String spuName,
      String category,
      String code,
      String name,
      String barcode,
      String baseUnit,
      String status,
      long version,
      String variantSignature,
      String updatedAt) {}

  /** 上游一页 SKU。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  /**
   * 拉一页 SKU。
   *
   * <p>⚠️ <b>字段名必须与上游逐字一致</b>：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
   * 写错字段名不会报错，只会静默变成 null —— 那会让导出文件出现整列空白，比不写还危险。
   */
  public SkuPage skus(
      int page,
      int size,
      String q,
      String category,
      String status,
      String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/catalog/skus").queryParam("page", Math.max(page, 0));
          uriBuilder.queryParam("size", Math.max(size, 1));
          if (q != null && !q.isBlank()) uriBuilder.queryParam("q", q.trim());
          if (category != null && !category.isBlank()) {
            uriBuilder.queryParam("category", category.trim());
          }
          if (status != null && !status.isBlank()) {
            uriBuilder.queryParam("status", status.trim().toUpperCase(java.util.Locale.ROOT));
          }
          return uriBuilder;
        },
        bearer,
        SkuPage.class);
  }

  // ================================================================ 导入：委托 catalog 的文件型作业

  /** catalog 的一个导入模板（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TemplateRow(
      UUID id, String code, String name, String note, String targetEntity, boolean enabled) {}

  /** catalog 的模板列表。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TemplatePage(List<TemplateRow> items, int total) {}

  /**
   * 拉 catalog 的导入模板列表。
   *
   * <p>🔴 <b>商品域的导入模板归 catalog 所有</b>（{@code fieldKey} / {@code matchKey} 是它的词汇），
   * 本通道**不复制一份**到自己的模板表里 —— 复制两份必然漂移。
   * ⇒ 通道只是**代理**它的模板下载。
   */
  public TemplatePage templates(String targetEntity, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/catalog/import-templates");
          if (targetEntity != null && !targetEntity.isBlank()) {
            uriBuilder.queryParam("targetEntity", targetEntity.trim().toUpperCase(java.util.Locale.ROOT));
          }
          return uriBuilder;
        },
        bearer,
        TemplatePage.class);
  }

  /** 取 catalog 的模板 CSV（**二进制**，不走 JSON 通道）。 */
  public byte[] templateFile(UUID templateId, String bearer) {
    return client.getBytes(
        uriBuilder -> uriBuilder.path("/catalog/import-templates/" + templateId + "/file"), bearer);
  }

  /** 上传文件后的回执（catalog 解析出的摘要）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UploadSummary(
      UUID jobId,
      String fileName,
      Long byteSize,
      String contentSha256,
      int rowCount,
      int columnCount,
      List<String> headers) {}

  /**
   * 把文件**委托**给 catalog 的导入作业。
   *
   * <p>🔴 <b>为什么必须委托而不是自己写</b>：导入要写商品主数据，
   * 而商品主数据住在 catalog 的库里。本模块若跨库写，就是 AGENTS.md 明令禁止的「跨服务 SQL」。
   * ⇒ 只能由属主服务执行；本通道负责转发文件、轮询作业、把逐行错误回灌成自己的失败行报告。
   */
  public UploadSummary uploadImport(
      UUID templateId, String importMode, String filename, byte[] content, String bearer) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("templateId", templateId.toString());
    parts.add("importMode", importMode);
    parts.add(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    return client.postMultipart(
        uriBuilder -> uriBuilder.path("/catalog/import-jobs"), bearer, parts, UploadSummary.class);
  }

  /** catalog 的导入作业行（状态与四段计数）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImportJobRow(
      UUID id,
      String templateName,
      String targetEntity,
      String importMode,
      String fileName,
      int rowCount,
      String status,
      int validCount,
      int invalidCount,
      int appliedCount,
      int failedCount,
      int skippedCount,
      String error,
      String prevalidatedAt,
      String startedAt,
      String finishedAt) {}

  /** 拉一次导入作业的当前状态。 */
  public ImportJobRow importJob(UUID jobId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/catalog/import-jobs/" + jobId.toString()),
        bearer,
        ImportJobRow.class);
  }

  /**
   * 请求**预校验**（catalog 的导入作业要「先预校验、再执行」两步）。
   *
   * <p>🔴 预校验会把有错误的行挑出来，避免半截数据入库 —— 所以通道**替属主跑完这两步**，
   * 而不是把文件丢过去就不管了。
   */
  public ImportJobRow prevalidate(UUID jobId, String bearer) {
    return client.postEmpty(
        uriBuilder -> uriBuilder.path("/catalog/import-jobs/" + jobId.toString() + "/prevalidate"),
        bearer,
        ImportJobRow.class);
  }

  /** 请求**执行**。 */
  public ImportJobRow executeImport(UUID jobId, String bearer) {
    return client.postEmpty(
        uriBuilder -> uriBuilder.path("/catalog/import-jobs/" + jobId.toString() + "/execute"),
        bearer,
        ImportJobRow.class);
  }

  /** 上游的一行导入结果（逐行结论）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImportRowLine(
      UUID id, int rowNo, String outcome, int errorCount, Map<String, String> values) {}

  /** 上游的逐行结果分页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImportRowPage(
      List<ImportRowLine> items, int page, int size, long total) {}

  /** 拉一页逐行结果（回执用：哪一行变成了哪条记录）。 */
  public ImportRowPage importRows(UUID jobId, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/catalog/import-jobs/" + jobId.toString() + "/rows")
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          return uriBuilder;
        },
        bearer,
        ImportRowPage.class);
  }

  /** 上游的一处失败（逐行逐列）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImportRowErrorLine(
      UUID id, int rowNo, String fieldKey, String code, String message, String severity) {}

  /** 上游的失败行分页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImportRowErrorPage(
      List<ImportRowErrorLine> items, int page, int size, long total, int errorCount, int warningCount) {}

  /** 拉一页失败行（**逐行逐列**：哪一行、哪一列、为什么）。 */
  public ImportRowErrorPage importErrors(UUID jobId, String severity, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/catalog/import-jobs/" + jobId.toString() + "/errors")
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          if (severity != null && !severity.isBlank()) {
            uriBuilder.queryParam("severity", severity.trim().toUpperCase(java.util.Locale.ROOT));
          }
          return uriBuilder;
        },
        bearer,
        ImportRowErrorPage.class);
  }
}
