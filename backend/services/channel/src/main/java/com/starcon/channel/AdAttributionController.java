package com.starcon.channel;

import static com.starcon.channel.AdAttributionModels.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 14.3.2.1 归因分析。
 *
 * <p>蓝图：「广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），计算广告后净利、
 * TACOS、增量效果估计。输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
 * 输出：归因明细 + 广告后利润报表。关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
 * <b>归因规则必须固定并公示</b>，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。」
 *
 * <p>端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口按职责句里的四个词分成四组：
 *
 * <ul>
 *   <li>{@code GET /rules} + {@code GET /rules/current} + {@code POST /rules} ——
 *       「归因规则配置」。规则是租户级配置，<b>一个租户同时只能有一套生效口径</b>；
 *       改规则 = 版本 +1，<b>已算出的归因结果不重算</b>。
 *   <li>{@code POST /import} —— 「平台广告归因数据」入口（订单级归因候选 + 点击触点）。
 *       幂等：重复导入是刷新，不是插重复行。
 *   <li>{@code POST /run} —— 跑一次归因计算（末次点击 / 平台归因窗口）。
 *   <li>{@code GET /records} —— 「归因明细」。真分页 + 真筛选 + 真排序 + 汇总 +
 *       <b>规则快照随每一行走</b>。
 *   <li>{@code GET /report} —— 「广告后利润报表」。广告后净利 / TACOS / <b>增量效果估计</b>，
 *       口径与规则原文随报表一起返回。
 *   <li>{@code GET /export} + {@code GET /exports} —— 导出与导出留痕（留痕**带规则快照**）。
 * </ul>
 *
 * <p>🔴 时间范围（{@code from} / {@code to}）在读端点上**都是必填**：没有区间就是全表扫。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read}（与 14.3.1.1 / 14.3.1.3 同族），
 * identity 服务零改动。
 *
 * <p>🔴 <b>跨服务调用在数据库事务之外</b>：本模块要读 13.4.1.2（reporting）与 03（catalog），
 * 那两次 HTTP 不该占着连接池里的连接等 —— 所以服务层把「数据库部分」和「跨服务部分」分开跑。
 */
@RestController
@RequestMapping("/channel/ad-attributions")
public class AdAttributionController {

  private final AccessService access;
  private final AdAttributionService service;

  public AdAttributionController(AccessService access, AdAttributionService service) {
    this.access = access;
    this.service = service;
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（因果增量 / 跨币种统一 / 增量订单数估计）显式 available=false。
   */
  @GetMapping("/capabilities")
  public AdAttributionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.capabilities());
  }

  /** 筛选项与口径定义（下拉项 + 表头要写明的口径 + 归因规则原文 + 增量效果口径原文）。 */
  @GetMapping("/options")
  public AdAttributionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.options());
  }

  // ---- 归因规则（固定并公示） ----

  /** 归因规则列表（真分页 + 真筛选）：一个租户同时只能有一套生效口径。 */
  @GetMapping("/rules")
  public AdAttributionRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.rules(status, keyword, page, size));
  }

  /** 当前生效的归因规则（没有生效规则时返回 null —— 不是空规则）。 */
  @GetMapping("/rules/current")
  public AdAttributionRuleRow currentRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.currentRule());
  }

  /**
   * 保存归因规则（新建或改）。
   *
   * <p>🔴 改规则 = <b>版本 +1</b>；已算出的归因结果**不重算、不改写**
   * （每条归因结果自带规则快照 ⇒ 历史报表仍然能被「当时那套口径」解释）。
   */
  @PostMapping("/rules")
  public AdAttributionRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SaveAdAttributionRuleRequest request) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.saveRule(request));
  }

  // ---- 平台广告归因数据 ----

  /**
   * 导入平台广告归因数据（订单级归因候选 + 点击触点）。
   *
   * <p>🔴 幂等：同一订单重复导入是刷新。🔴 被拒绝的行**逐条列明原因**，不静默丢弃。
   */
  @PostMapping("/import")
  public AdAttributionImportResult importAttribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdAttributionImportRequest request) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.importAttribution(request));
  }

  // ---- 归因计算 ----

  /**
   * 跑一次归因计算（末次点击 / 平台归因窗口）。
   *
   * <p>🔴 归因结果按 <b>(规则, 规则版本, 订单)</b> 落库 ⇒ 改规则后重跑产生的是新版本的事实，
   * 旧版本的事实原样保留。默认<b>不重算</b>（已归因的是事实）。
   */
  @PostMapping("/run")
  public AdAttributionRunResult run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AdAttributionRunRequest request) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.run(request));
  }

  // ---- 归因明细 ----

  /**
   * 归因明细：真分页 + 真筛选 + 真排序 + 汇总 + 规则快照 + 口径原文。
   *
   * <p>🔴 响应里三样东西不能省：
   *
   * <ul>
   *   <li>{@code summaries} —— 统计的是**筛选后的全集**，不是当前页；且**按币种拆开**（没有汇率源）。
   *   <li>{@code attributionNote} —— **归因规则原文**（规则变了历史报表不可比，必须公示）。
   *   <li>每一行的**规则快照**（{@code ruleCode} / {@code ruleVersion} / {@code attributionModel} /
   *       {@code attributionWindowDays}）—— 事后能解释「这一行是按哪套口径归出来的」。
   * </ul>
   */
  @GetMapping("/records")
  public AdAttributionRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) Long ruleVersion,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "ORDER_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(
        actor,
        "channel.operation.read",
        () ->
            service.records(
                service.normalizeRecordQuery(
                    blankUuid(ruleId),
                    ruleVersion,
                    from,
                    to,
                    status,
                    shopCode,
                    blankUuid(campaignId),
                    blankUuid(skuId),
                    platformCode,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /** 单条归因结果详情（跨租户在 RLS 下查不到 → 404）。 */
  @GetMapping("/records/{id}")
  public AdAttributionRecordRow record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.record(id));
  }

  // ---- 广告后利润报表 ----

  /**
   * 广告后利润报表：归因广告花费 / 广告销售额 + 总销售额 / 净利 ⇒ 广告后净利、TACOS、
   * 广告销售占比、<b>增量效果估计</b>。
   *
   * <p>🔴 响应里四样东西不能省：
   *
   * <ul>
   *   <li>{@code summaries} —— 统计的是**筛选后的全集**，不是当前页；且**按币种拆开**。
   *   <li>{@code metricDefinitions} —— 逐指标的口径定义（表头照它渲染）。
   *   <li>{@code attributionNote} + {@code incrementalityNote} —— 归因规则原文 + 增量估计口径原文。
   *   <li>{@code profitAvailable} / {@code catalogAvailable} —— 上游读不到时如实说，
   *       那一段的格子是**空**而不是 0。
   * </ul>
   */
  @GetMapping("/report")
  public AdAttributionReportPage report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) Long ruleVersion,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    var actor = access.require(auth, "channel.operation.read");
    return service.report(
        service.normalizeReportQuery(
            blankUuid(ruleId),
            ruleVersion,
            from,
            to,
            shopCode,
            blankUuid(campaignId),
            blankUuid(skuId),
            currency,
            sort,
            page,
            size),
        actor,
        auth);
  }

  // ---- 导出 ----

  /**
   * 导出 CSV（走 GET + query，导出结果与当前筛选完全一致）。
   *
   * <p>🔴 三件事不能省：
   *
   * <ul>
   *   <li><b>口径写进表头</b>：归因规则原文、逐指标公式、增量估计口径、规则快照全部写进去。
   *   <li><b>规则快照写进留痕</b>：{@code ad_attribution_export} 存当时的规则编码 / 版本 / 模型 / 窗口。
   *   <li><b>留痕</b>：谁、什么时候、什么筛选条件、导出了多少行，全部落库。
   * </ul>
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) Long ruleVersion,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String skuId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort) {
    var actor = access.require(auth, "channel.operation.read");
    var query =
        service.normalizeReportQuery(
            blankUuid(ruleId),
            ruleVersion,
            from,
            to,
            shopCode,
            blankUuid(campaignId),
            blankUuid(skuId),
            currency,
            sort,
            0,
            50);
    // 先在租户事务里校验筛选条件并登记导出留痕，别等到已经开始写流才失败。
    var trace = service.recordExport(query, actor);
    StreamingResponseBody body =
        out -> {
          int rows = service.streamCsv(query, actor, chunk -> write(out, chunk), auth);
          service.finishExport(trace.id(), rows);
        };
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"ad-attribution-report-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  /** 导出留痕历史（谁在什么时候按什么条件、按哪套口径导出了多少行）。 */
  @GetMapping("/exports")
  public AdAttributionExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.exports(page, size));
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

  /** 空串与 null 同义，避免把 {@code ?ruleId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }
}
