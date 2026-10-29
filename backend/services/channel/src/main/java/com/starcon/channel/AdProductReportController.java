package com.starcon.channel;

import static com.starcon.channel.AdProductReportModels.*;

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
 * 14.3.1.3 商品维度报表。
 *
 * <p>蓝图：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
 * ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
 * 输出：商品广告报表 + 广告销售占比 + 广告后净利。
 * 关系：上游 = 14.3.1.1、13.4.1.2；下游 = 13.4 利润。
 * <b>广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键</b>，
 * 只看广告 ROAS 无法回答这个问题。」
 *
 * <p>端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口按职责句里的三个词分成三组：
 *
 * <ul>
 *   <li>{@code GET /rows} —— 「商品广告报表」。真分页 + 真筛选 + 真排序 +
 *       **汇总单独一条聚合查询** + **口径与归因规则随报表一起返回**。
 *   <li>{@code GET /export} —— 「导出」。CSV 走 GET + query（导出结果与当前筛选完全一致），
 *       **口径与归因规则写进 CSV 表头**，并且每次导出都留痕。
 *   <li>{@code GET /exports} —— 导出留痕历史（谁在什么时候按什么条件导出了多少行）。
 * </ul>
 *
 * <p>🔴 时间范围（{@code from} / {@code to}）在两个读端点上**都是必填**：没有区间就是全表扫。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read}（与 14.3.1.1 广告活动报表同族），
 * identity 服务零改动。
 *
 * <p>🔴 <b>跨服务调用在数据库事务之外</b>：本模块要读 13.4.1.2（reporting）与 03（catalog），
 * 那两次 HTTP 不该占着连接池里的连接等 —— 所以服务层把「数据库部分」和「跨服务部分」分开跑。
 */
@RestController
@RequestMapping("/channel/ad-product-reports")
public class AdProductReportController {

  private final AccessService access;
  private final AdProductReportService service;

  public AdProductReportController(AccessService access, AdProductReportService service) {
    this.access = access;
    this.service = service;
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（单量口径的广告销售占比、归因、跨币种统一）显式 available=false。
   */
  @GetMapping("/capabilities")
  public AdProductReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.capabilities());
  }

  /** 筛选项与口径定义（下拉项 + 表头要写明的口径 + 归因规则原文）。 */
  @GetMapping("/options")
  public AdProductReportOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var actor = access.require(auth, "channel.operation.read");
    return access.run(actor, "channel.operation.read", () -> service.options());
  }

  // ---- 商品广告报表 ----

  /**
   * 商品广告报表：真分页 + 真筛选 + 真排序 + 汇总 + 口径说明 + 归因规则 + 数据截至时刻。
   *
   * <p>🔴 响应里四样东西不能省：
   *
   * <ul>
   *   <li>{@code summaries} —— 统计的是**筛选后的全集**，不是当前页；且**按币种拆开**（没有汇率源）。
   *   <li>{@code metricDefinitions} —— 逐指标的口径定义（表头照它渲染）。
   *   <li>{@code attributionNote} —— **分摊规则原文**（规则变了历史报表不可比，必须公示）。
   *   <li>{@code profitAvailable} / {@code catalogAvailable} —— 上游读不到时如实说，
   *       那一段的格子是**空**而不是 0。
   * </ul>
   */
  @GetMapping("/rows")
  public AdProductReportPage rows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "LISTING") String dimension,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    var actor = access.require(auth, "channel.operation.read");
    return service.report(
        service.normalize(
            from,
            to,
            dimension,
            sort,
            blankUuid(platformId),
            shopCode,
            internalAdType,
            status,
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
   *   <li><b>口径写进表头</b>：列名里带着公式（{@code ACOS(Σ花费/Σ销售额)}），
   *       拿去做跨部门沟通时 CSV 自己就说明了「ACOS 是哪个除哪个」。
   *   <li><b>归因规则写进留痕</b>：{@code ad_product_report_export.attribution_note}。
   *   <li><b>留痕</b>：谁、什么时候、什么筛选条件、导出了多少行，全部落库。
   * </ul>
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "LISTING") String dimension,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String status) {
    var actor = access.require(auth, "channel.operation.read");
    var query =
        service.normalize(
            from, to, dimension, sort, blankUuid(platformId), shopCode, internalAdType, status, 0, 50);
    // 先在租户事务里校验筛选条件并登记导出留痕，别等到已经开始写流才失败。
    service.recordExport(query, actor);
    StreamingResponseBody body =
        out -> service.streamCsv(actor, query, chunk -> write(out, chunk), auth);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"ad-product-report-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(body);
  }

  /** 导出留痕历史（谁在什么时候按什么条件导出了多少行）。 */
  @GetMapping("/exports")
  public AdProductReportExportPage exports(
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
