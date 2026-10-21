package com.starcon.channel;

import static com.starcon.channel.AdPromotionAnalysisModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 14.4.2.1 促销效果分析。
 *
 * <p>蓝图：「促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，与活动前的基期对比。
 * 输入：促销数据；历史基线；利润数据。输出：效果报表 + 增量估算 + ROI。
 * 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
 * <strong>促销期间毛利可能大幅下滑</strong>，评估必须看毛利而不是销售额。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成五组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里**平台归因 / 13.4 利润 / 汇率换算**三个维度显式 available=false
 *       （各自带 ownerModule 与影响说明）—— 界面上必须让人看见这些状态，
 *       不许用假数字顶替。</li>
 *   <li>{@code POST /runs}、{@code GET /runs} —— 「分析」本身：
 *       真算促销期与基期、真落库、真留痕（跳过的促销不许静默消失）。</li>
 *   <li>{@code GET /report} —— 「效果报表 + 增量估算 + ROI」：
 *       真聚合（活动维度 GROUP BY）、真分页、真筛选、真排序（默认按毛利排序）。</li>
 *   <li>{@code POST /exports}、{@code GET /exports}、{@code GET /exports/{id}/csv} ——
 *       导出与**导出留痕**（谁、什么时候、什么筛选条件、导出了多少行）。</li>
 *   <li>{@code GET|POST /cost-snapshots} —— 成本与售价快照
 *       （catalog 的成本**复制**进本服务的唯一通道，channel 永不 join catalog）。</li>
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.2.2.1 预算分配、14.4.1.1 促销列表同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-promotion-analysis")
public class AdPromotionAnalysisController {

  private final AccessService access;
  private final AdPromotionAnalysisService service;

  public AdPromotionAnalysisController(AccessService access, AdPromotionAnalysisService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 平台归因、13.4 利润、汇率换算三个维度 unavailable 且带 ownerModule ——
   * 界面上必须让人看见这些状态，而不是用假数字顶替。
   */
  @GetMapping("/capabilities")
  public AdPromotionAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项 + 销量 / 毛利 / 基期口径说明（界面据此预填筛选与表头）。 */
  @GetMapping("/options")
  public AdPromotionAnalysisOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 分析运行 ----

  /**
   * 跑一次分析：算促销期与基期，落基期快照与效果结果表。
   *
   * <p>🔴 跳过的促销（没起止时间 / 没有适用商品）会记进 {@code skipped}，
   * 运行状态标成 PARTIAL —— **不许静默消失**。
   */
  @PostMapping("/runs")
  public AdPromotionAnalysisRunRow run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RunAnalysisRequest request) {
    return call(auth, "channel.operation.update", a -> service.runAnalysis(request));
  }

  /** 分析运行历史（跑了什么、算了几条、跳过几条、为什么跳过）。 */
  @GetMapping("/runs")
  public List<AdPromotionAnalysisRunRow> runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.runs(page, size));
  }

  // ---- 效果报表 ----

  /**
   * 效果报表：真聚合 / 真分页 / 真筛选 / 真排序。
   *
   * <p>🔴 默认排序是**按毛利**（{@code GROSS_PROFIT_DESC}）—— 蓝图加粗句
   * 「促销期间毛利可能大幅下滑，评估必须看毛利而不是销售额」的直接落点。
   */
  @GetMapping("/report")
  public AdPromotionAnalysisPage report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String promotionId,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String incrementVerdict,
      @RequestParam(required = false) Boolean marginAlertOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) LocalDate dateFrom,
      @RequestParam(required = false) LocalDate dateTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.report(
                service.normalize(
                    new AdPromotionAnalysisQuery(
                        dimension,
                        sort,
                        blankUuid(promotionId, "促销 id"),
                        blankUuid(platformId, "平台 id"),
                        shopCode,
                        currency,
                        incrementVerdict,
                        marginAlertOnly,
                        keyword,
                        dateFrom,
                        dateTo,
                        page,
                        size))));
  }

  // ---- 导出 ----

  /**
   * 登记一次导出（留痕：谁、什么时候、什么筛选条件、导出了多少行）。
   *
   * <p>🔴 走 POST + body 而不是 GET + query：导出是**动作**（一次导出），
   * 不是「读一个集合」。
   */
  @PostMapping("/exports")
  public AdPromotionAnalysisExportRow createExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdPromotionAnalysisExportRequest request) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.recordExport(
                service.normalize(
                    new AdPromotionAnalysisQuery(
                        request.dimension(),
                        null,
                        request.promotionId(),
                        request.platformId(),
                        request.shopCode(),
                        request.currency(),
                        request.incrementVerdict(),
                        null,
                        null,
                        request.dateFrom(),
                        request.dateTo(),
                        0,
                        50))));
  }

  /** 导出留痕历史。 */
  @GetMapping("/exports")
  public AdPromotionAnalysisExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.exports(page, size));
  }

  /**
   * 下载某次导出的 CSV。
   *
   * <p>🔴 <b>口径写进表头</b>：列名里带着公式，跨部门沟通时 CSV 自己说得清；
   * 不可比的比率写「不可比」（不是 0）。
   */
  @GetMapping(value = "/exports/{exportId}/csv", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportCsv(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID exportId) {
    var actor = access.require(auth, "channel.operation.read");
    var row = call(auth, "channel.operation.read", a -> service.exportById(exportId));
    StreamingResponseBody body =
        out -> service.streamCsv(actor, service.queryOf(row), chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"ad-promotion-analysis-"
                + Instant.now().toEpochMilli()
                + ".csv\"")
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

  // ---- 成本与售价快照 ----

  /** 成本快照列表：真分页 + 真筛选。 */
  @GetMapping("/cost-snapshots")
  public AdPromotionAnalysisCostSnapshotPage costSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String promotionId,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.costSnapshots(
                blankUuid(promotionId, "促销 id"),
                blankUuid(listingId, "商品 id"),
                page,
                size));
  }

  /**
   * 登记成本与售价快照。
   *
   * <p>🔴 这是**成本进入本服务的唯一通道**：catalog 的成本被复制进来，
   * channel 永不 join catalog。没登记成本 ⇒ 毛利**不可比**（空值），不是 0。
   */
  @PostMapping("/cost-snapshots")
  public AdPromotionAnalysisCostSnapshotRow saveCostSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveCostSnapshotRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveCostSnapshot(request));
  }

  /** 空串与 null 同义，避免把 {@code ?promotionId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value, String what) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, what + " 不是合法 UUID");
    }
  }
}
