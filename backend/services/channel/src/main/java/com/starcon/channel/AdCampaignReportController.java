package com.starcon.channel;

import static com.starcon.channel.AdCampaignReportModels.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 14.3.1.1 活动报表。
 *
 * <p>蓝图：「活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
 * 按日/周/月与多维对比，支持导出。输入：时间范围；维度；广告数据。
 * 输出：报表表 + 趋势图 + 口径说明。关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
 * <b>ACOS/ROAS/TACOS 三个口径要在表头写明定义</b>，否则跨部门沟通会各说各话。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口按职责句里的三个词分成三组：
 *
 * <ul>
 *   <li>{@code GET /rows} —— 「报表表」。真分页 + 真筛选 + 真排序 + **汇总单独一条聚合查询**
 *       + **口径说明随报表一起返回**。
 *   <li>{@code GET /trend} —— 「趋势图」。按日/周/月分桶的时间序列。
 *   <li>{@code GET /export} —— 「支持导出」。CSV 走 GET + query（导出结果与当前筛选完全一致），
 *       **口径写进 CSV 表头**，并且每次导出都留痕。
 * </ul>
 *
 * <p>🔴 时间范围（{@code from} / {@code to}）在三个读端点上**都是必填**：没有区间就是全表扫，
 * 而蓝图加粗句点名「不能实时全表扫」。不给区间直接 400，不给默认区间。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read}（与 14.1.1.1 广告活动列表同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-reports")
public class AdCampaignReportController {

  private final AccessService access;
  private final AdCampaignReportService service;

  public AdCampaignReportController(AccessService access, AdCampaignReportService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（TACOS、跨币种统一、关键词/商品维度、归因、物化缓存）显式 available=false。
   *
   * <p>🔴 TACOS 在这里被显式标成「没有分母」：总销售额口径（13.4 利润模块）未接入。
   * 不许用 0 当分母，也不许用广告销售额顶替总销售额 —— 那算出来的是 ACOS 不是 TACOS。
   */
  @GetMapping("/capabilities")
  public AdCampaignReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项与口径定义（下拉项 + 表头要写明的三个口径）。 */
  @GetMapping("/options")
  public AdCampaignReportOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 报表表 ----

  /**
   * 报表表：真分页 + 真筛选 + 真排序 + 汇总 + 口径说明 + 数据截至时刻。
   *
   * <p>🔴 响应里三样东西不能省：
   *
   * <ul>
   *   <li>{@code summaries} —— 统计的是**筛选后的全集**，不是当前页；且**按币种拆开**（没有汇率源）。
   *   <li>{@code metricDefinitions} —— 三个口径的定义（表头照它渲染），TACOS 的「不可用」也在里面。
   *   <li>{@code dataAsOf} + {@code emptyReason} —— 「数据截至 X 时间」与三种「空」的区别。
   * </ul>
   */
  @GetMapping("/rows")
  public AdCampaignReportPage rows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "CAMPAIGN") String dimension,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.report(
                service.normalize(
                    from,
                    to,
                    granularity,
                    dimension,
                    sort,
                    blankUuid(platformId),
                    shopCode,
                    internalAdType,
                    status,
                    page,
                    size)));
  }

  /** 趋势图：按日 / 周 / 月分桶的时间序列（按币种拆成多条线）。 */
  @GetMapping("/trend")
  public AdCampaignReportTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "CAMPAIGN") String dimension,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.trend(
                service.normalize(
                    from,
                    to,
                    granularity,
                    dimension,
                    "SPEND_DESC",
                    blankUuid(platformId),
                    shopCode,
                    internalAdType,
                    status,
                    0,
                    50)));
  }

  // ---- 导出 ----

  /**
   * 导出 CSV（走 GET + query，导出结果与当前筛选完全一致）。
   *
   * <p>🔴 两件事不能省：
   *
   * <ul>
   *   <li><b>口径写进表头</b>：列名里带着公式（{@code ACOS(Σ花费/Σ销售额)}），
   *       拿去做跨部门沟通时 CSV 自己就说明了「ACOS 是哪个除哪个」。
   *   <li><b>留痕</b>：谁、什么时候、什么筛选条件、导出了多少行，全部落
   *       {@code ad_campaign_report_export}（V279）。
   * </ul>
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "CAMPAIGN") String dimension,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status) {
    var actor = access.require(auth, "channel.operation.read");
    var query =
        service.normalize(
            from, to, granularity, dimension, "SPEND_DESC", blankUuid(platformId), shopCode,
            internalAdType, status, 0, 50);
    // 先在租户事务里校验筛选条件并登记导出留痕，别等到已经开始写流才失败。
    access.run(actor, "channel.operation.read", () -> service.recordExport(query));
    StreamingResponseBody body =
        out ->
            service.streamCsv(
                actor, query, chunk -> write(out, chunk), AdCampaignReportService.dimensionLabel(query.dimension()));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"ad-campaign-report-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  /** 导出留痕历史（谁在什么时候按什么条件导出了多少行）。 */
  @GetMapping("/exports")
  public AdCampaignReportExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.exports(page, size));
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
