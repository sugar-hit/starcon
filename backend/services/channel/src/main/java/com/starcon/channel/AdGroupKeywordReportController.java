package com.starcon.channel;

import static com.starcon.channel.AdGroupKeywordReportModels.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 14.3.1.2 广告组与词报表。
 *
 * <p>蓝图：「广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，支持批量选中导出。
 * 输入：维度选择（组/词/搜索词）；时间范围。输出：明细报表 + 汇总。
 * 关系：上游 = 14.1.3.1；下游 = 14.1.3.1 否定决策。
 * <b>关键词级数据量极大</b>，查询必须分页与缓存，不能实时全表扫。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口按职责句分成三组：
 *
 * <ul>
 *   <li>{@code GET /rows} —— 「明细报表 + 汇总」。一个端点，四个维度
 *       （{@code dimension} = GROUP / KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT）：
 *       真分页 + 真筛选 + 真排序 + **汇总单独一条聚合查询** + **口径说明随报表一起返回**
 *       + **缓存的命中状态与失效依据**。
 *   <li>{@code POST /exports} + {@code GET /exports/{id}/csv} —— 「支持批量选中导出」：
 *       选中哪几条也留痕（{@code selection}），CSV 从留痕重建条件后重算。
 *   <li>{@code GET /capabilities} / {@code GET /options} —— 能力声明与筛选项。
 * </ul>
 *
 * <p>🔴 时间范围（{@code from} / {@code to}）在读端点上**都是必填**：没有区间就是全表扫，
 * 而蓝图加粗句点名「不能实时全表扫」。不给区间直接 400，不给默认区间。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read}（与 14.1.1.1 广告活动列表同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-group-keyword-reports")
public class AdGroupKeywordReportController {

  private final AccessService access;
  private final AdGroupKeywordReportService service;

  public AdGroupKeywordReportController(AccessService access, AdGroupKeywordReportService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（<b>组级指标</b>、跨币种统一、归因）显式 available=false。
   *
   * <p>🔴 组级指标在这里被显式标成「<b>没有事实源</b>」：现有事实表里没有组级指标表，
   * 而且 {@code ad_keyword} / {@code ad_search_term} 根本没有组的引用列 ⇒
   * 词指标在物理上无法归到组。不许拿活动级指标冒充组级指标（那是假事实），
   * 也不许建一张空表冒充「没有数据」。
   */
  @GetMapping("/capabilities")
  public AdGroupKeywordReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项与口径定义（下拉项 + 表头要写明的口径 + 缓存说明）。 */
  @GetMapping("/options")
  public AdGroupKeywordReportOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 报表表 ----

  /**
   * 报表表：真分页 + 真筛选 + 真排序 + 汇总 + 口径说明 + 数据截至时刻 + 缓存状态。
   *
   * <p>🔴 响应里四样东西不能省：
   *
   * <ul>
   *   <li>{@code summaries} —— 统计的是**筛选后的全集**，不是当前页；且**按币种拆开**（没有汇率源）。
   *   <li>{@code metricDefinitions} —— 口径的定义（表头照它渲染）；组级指标的「不可用」也在里面。
   *   <li>{@code negativeEffectNote} —— 否定效果评估的口径（否定生效日怎么算、「否定后仍有数据」怎么判）。
   *   <li>{@code cache} —— 命中状态 + 失效依据 + <b>明确的过期时刻</b>（不会出现「缓存了但不知道什么时候过期」）。
   * </ul>
   */
  @GetMapping("/rows")
  public AdGroupKeywordReportPage rows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "KEYWORD") String dimension,
      @RequestParam(required = false, defaultValue = "TOTAL") String granularity,
      // 🔴 排序不给默认值：排序白名单是**按维度**分的（组按名称、否定按生效时间、词按花费），
      //   统一默认值会让「不传 sort」在某些维度上被判成非法值。不传时服务端按维度取默认。
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false) String keywordText,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.report(
                service.normalize(
                    dimension,
                    granularity,
                    sort,
                    from,
                    to,
                    blankUuid(platformId),
                    shopCode,
                    internalAdType,
                    status,
                    externalCampaignId,
                    keywordText,
                    List.of(),
                    page,
                    size)));
  }

  // ---- 导出 ----

  /**
   * 批量选中导出：**登记**一次导出（留痕：谁、什么时候、什么筛选条件、选了哪几条、导出了多少行）。
   *
   * <p>🔴 走 POST + body 而不是 GET + query，因为「批量选中」是一串 id ——
   * 塞进 URL 会有长度上限，而且「选了哪几条」是**动作**（一次导出），
   * 不是「读一个集合」。留痕落在 {@code ad_group_keyword_report_export}（V283），
   * 形状与 14.3.1.1 的 {@code ad_campaign_report_export} 一一对应。
   */
  @PostMapping("/exports")
  public AdGroupKeywordReportExportRow createExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdGroupKeywordReportExportRequest request) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.recordExport(
                service.normalize(
                    request.dimension(),
                    request.granularity(),
                    request.sort(),
                    request.dateFrom(),
                    request.dateTo(),
                    request.platformId(),
                    request.shopCode(),
                    request.internalAdType(),
                    request.status(),
                    request.externalCampaignId(),
                    request.keywordText(),
                    request.selectedKeys(),
                    0,
                    50)));
  }

  /** 导出留痕历史（谁在什么时候按什么条件导出了多少行、选了哪几条）。 */
  @GetMapping("/exports")
  public AdGroupKeywordReportExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.exports(page, size));
  }

  /** 单条导出留痕详情。 */
  @GetMapping("/exports/{exportId}")
  public AdGroupKeywordReportExportRow exportDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID exportId) {
    return call(auth, "channel.operation.read", a -> service.exportById(exportId));
  }

  /**
   * 下载某次导出的 CSV。
   *
   * <p>🔴 <b>口径写进表头</b>：列名里带着公式（{@code ACOS(Σ花费/Σ销售额)}），
   * 拿去做跨部门沟通时 CSV 自己就说明了「ACOS 是哪个除哪个」；
   * 否定维度还把「否定生效日」与「否定后仍有数据」的判定写进列名。
   *
   * <p>🔴 CSV 是按**留痕里记下的条件**重算的（不是数据快照）：报表随时可以按同样的条件重算，
   * 而数据快照会在下一次同步后与「当时的报表」对不上 —— 留一份反而是假事实。
   */
  @GetMapping(value = "/exports/{exportId}/csv", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportCsv(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID exportId) {
    var actor = access.require(auth, "channel.operation.read");
    var row =
        access.run(actor, "channel.operation.read", () -> service.exportById(exportId));
    var query = service.queryOf(row);
    StreamingResponseBody body =
        out -> service.streamCsv(actor, query, chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"ad-group-keyword-report-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  private static void write(OutputStream out, String chunk) {
    try {
      out.write(chunk.getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      // 客户端提前断开（关掉下载页）是正常行为，不该当成服务端错误往上抛。
      throw new org.springframework.web.client.RestClientException("导出已中断", e);
    }
  }

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "平台 id 不是合法 UUID");
    }
  }
}
