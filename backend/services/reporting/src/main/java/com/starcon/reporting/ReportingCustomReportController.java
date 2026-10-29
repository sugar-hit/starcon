package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.1.1 自定义报表的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/custom-report}：13.1.1.1 用的是 {@code /reporting/overview}、
 * 13.2.2.2 用的是 {@code /reporting/product-sales}，每个兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.custom-report.read}（读报表与结果）与
 * {@code reporting.custom-report.write}（保存 / 分享 / 订阅）。
 *
 * <p>🔴 **本模块没有任何「导出」或「投递」端点**：那属 13.6.2.1（未交付）⇒
 * 能力声明里显式标 {@code available=false}，界面上写清「未交付」。
 */
@RestController
@RequestMapping("/reporting/custom-report")
@Tag(name = "13.6.1.1 自定义报表")
public class ReportingCustomReportController {

  private final AccessService access;
  private final ReportingCustomReportService reports;

  public ReportingCustomReportController(AccessService access, ReportingCustomReportService reports) {
    this.access = access;
    this.reports = reports;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些真能跑、哪些未交付、判定规则是什么",
      description =
          "🔴 **导出（Excel / CSV / PDF）与邮件 / IM 的实际投递都属 13.6.2.1，未交付** —— "
              + "这两项 available=false，并写明归属模块与影响。界面照着显示，"
              + "**不提供假按钮**。")
  public ReportingCustomReportModels.CustomReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.capabilities());
  }

  // ================================================================ 语义层目录

  @GetMapping("/metrics")
  @Operation(
      summary = "可用的语义层指标清单（口径唯一的展示形态）",
      description =
          "指标定义是**租户级**的 ⇒ 这里只给**当前租户**登记的指标，"
              + "别的租户登记的指标不会出现在清单里。")
  public List<ReportingCustomReportModels.CustomReportMetricView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.metricCatalog(actor.tenantId()));
  }

  @GetMapping("/dimensions")
  @Operation(
      summary = "可用维度白名单（只有这 5 个）",
      description =
          "🔴 自定义报表**不开放任意列名**：维度只能是这张白名单里的码，"
              + "它们对应投影事实上真实存在的列。")
  public List<ReportingCustomReportModels.CustomReportDimensionView> dimensions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.custom-report.read", actor -> reports.dimensionCatalog());
  }

  // ================================================================ 报表清单

  @GetMapping("/reports")
  @Operation(summary = "报表清单（真分页 + 真搜索 + 真筛选）")
  public ReportingCustomReportModels.CustomReportPage reports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String visibility,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.list(actor.tenantId(), actor.userId(), keyword, visibility, page, size));
  }

  @PostMapping("/reports")
  @Operation(
      summary = "新建一份报表配置",
      description =
          "🔴 度量只能引用**已登记的 metric_code**（口径唯一），维度只能命中白名单；"
              + "创建者来自**已认证会话**（不在入参里）。")
  public ReportingCustomReportModels.CustomReportView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingCustomReportModels.CustomReportSaveRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor -> reports.save(actor.tenantId(), actor.userId(), null, request));
  }

  @GetMapping("/reports/{reportId}")
  @Operation(summary = "读一份报表配置")
  public ReportingCustomReportModels.CustomReportView report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.get(actor.tenantId(), actor.userId(), uuid(reportId, "报表 id")));
  }

  @PutMapping("/reports/{reportId}")
  @Operation(summary = "更新一份报表配置（只有创建者能改）")
  public ReportingCustomReportModels.CustomReportView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId,
      @RequestBody ReportingCustomReportModels.CustomReportSaveRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor ->
            reports.save(actor.tenantId(), actor.userId(), uuid(reportId, "报表 id"), request));
  }

  @DeleteMapping("/reports/{reportId}")
  @Operation(summary = "删除一份报表（连同它的度量 / 维度 / 筛选 / 分享 / 订阅）")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId) {
    call(
        auth,
        "reporting.custom-report.write",
        actor -> {
          reports.delete(actor.tenantId(), actor.userId(), uuid(reportId, "报表 id"));
          return null;
        });
  }

  // ================================================================ 报表结果

  @PostMapping("/reports/{reportId}/run")
  @Operation(
      summary = "跑一份报表，返回结果集",
      description =
          "🔴 **派生型指标按登记口径在服务端算**：按口径表里的分子分母分别取数再算，"
              + "**分母为 0 ⇒ 比率为 NULL（不可比），不是 0**。"
              + "🔴 **涉币指标按币种分别报**（绝不跨币种相加）；与货币无关的量给一个跨币种合计。"
              + "报表数字与业务系统冲突时，**以业务系统为准**。")
  public ReportingCustomReportModels.CustomReportRunResponse run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId,
      @RequestBody(required = false) ReportingCustomReportModels.CustomReportRunRequest request) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> {
          var from = request == null ? null : request.from();
          var to = request == null ? null : request.to();
          var page = request == null || request.page() == null ? 0 : request.page();
          var size = request == null || request.size() == null ? 50 : request.size();
          return reports.run(
              actor.tenantId(), uuid(reportId, "报表 id"), actor.userId(), from, to, page, size);
        });
  }

  // ================================================================ 分享

  @GetMapping("/reports/{reportId}/shares")
  @Operation(summary = "某张报表的分享链接清单（只有创建者能看）")
  public List<ReportingCustomReportModels.CustomReportShareView> shares(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.listShares(actor.tenantId(), actor.userId(), uuid(reportId, "报表 id")));
  }

  @PostMapping("/reports/{reportId}/shares")
  @Operation(
      summary = "生成分享链接",
      description =
          "🔴 **分享链接不等于公开访问**：令牌绑定租户 + 绑定报表归属（个人 / 团队）；"
              + "未授权访问与「报表不存在」返回**逐字相同**的 404。")
  public ReportingCustomReportModels.CustomReportShareView createShare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId,
      @RequestBody(required = false) ReportingCustomReportModels.CustomReportShareRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor ->
            reports.createShare(
                actor.tenantId(),
                uuid(reportId, "报表 id"),
                actor.userId(),
                request == null ? null : request.visibility()));
  }

  @DeleteMapping("/shares/{shareId}")
  @Operation(summary = "撤销一条分享链接")
  public void revokeShare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String shareId) {
    call(
        auth,
        "reporting.custom-report.write",
        actor -> {
          reports.revokeShare(actor.tenantId(), actor.userId(), uuid(shareId, "分享 id"));
          return null;
        });
  }

  @GetMapping("/shared/{token}")
  @Operation(
      summary = "通过分享链接读报表（配置 + 结果）",
      description =
          "🔴 未授权访问与「报表不存在」返回**逐字相同**的 404 —— "
              + "这个响应体只在**确实有权看**的时候才会出现。")
  public ReportingCustomReportModels.CustomReportSharedResponse shared(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String token,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.shared(actor.tenantId(), actor.userId(), token, page, size));
  }

  // ================================================================ 订阅与投递

  @GetMapping("/reports/{reportId}/subscriptions")
  @Operation(summary = "某张报表的订阅计划清单（只有创建者能看）")
  public List<ReportingCustomReportModels.CustomReportSubscriptionView> subscriptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor ->
            reports.listSubscriptions(actor.tenantId(), actor.userId(), uuid(reportId, "报表 id")));
  }

  @PostMapping("/reports/{reportId}/subscriptions")
  @Operation(
      summary = "保存一条订阅计划",
      description =
          "cron 是 5 段式（分 时 日 月 周），只支持明确写死的子集；"
              + "**不支持的写法直接报错**（不静默回落成「每天一次」）。"
              + "🔴 **实际投递属 13.6.2.1，未交付** —— 这里只登记计划。")
  public ReportingCustomReportModels.CustomReportSubscriptionView createSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String reportId,
      @RequestBody ReportingCustomReportModels.CustomReportSubscriptionRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor ->
            reports.saveSubscription(
                actor.tenantId(), uuid(reportId, "报表 id"), actor.userId(), request));
  }

  @PutMapping("/subscriptions/{subscriptionId}")
  @Operation(summary = "切换一条订阅的启停")
  public ReportingCustomReportModels.CustomReportSubscriptionView toggleSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String subscriptionId,
      @RequestBody(required = false) ReportingCustomReportModels.CustomReportSubscriptionToggleRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor ->
            reports.toggleSubscription(
                actor.tenantId(),
                actor.userId(),
                uuid(subscriptionId, "订阅 id"),
                request == null ? null : request.enabled()));
  }

  @DeleteMapping("/subscriptions/{subscriptionId}")
  @Operation(summary = "删除一条订阅计划（它的待投递记录收口成 CANCELLED）")
  public void deleteSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String subscriptionId) {
    call(
        auth,
        "reporting.custom-report.write",
        actor -> {
          reports.deleteSubscription(
              actor.tenantId(), actor.userId(), uuid(subscriptionId, "订阅 id"));
          return null;
        });
  }

  @GetMapping("/deliveries")
  @Operation(
      summary = "待投递记录台账（真分页 + 真计数）",
      description =
          "🔴 这些记录是「**待投递**」（PENDING），**不是「已发送」** —— "
              + "真正的邮件 / IM 投递属 13.6.2.1（未交付）。")
  public ReportingCustomReportModels.CustomReportDeliveryPage deliveries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> reports.listDeliveries(actor.tenantId(), status, page, size));
  }

  @PostMapping("/deliveries/tick")
  @Operation(
      summary = "跑一轮：把到期的订阅计划落成待投递记录",
      description =
          "⚠️ **本服务不装 @Scheduled**（与 channel / identity 同一纪律：多副本下自带定时器"
              + "要做主节点选举与分布式锁）⇒ 这一轮由**外部调度**触发。"
              + "落的是「待投递」，**不是「已发送」**。")
  public ReportingCustomReportModels.CustomReportTickResult tick(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor -> reports.tick(actor.tenantId()));
  }

  // ================================================================ 参数解析

  /** uuid 路径参数（格式不对 ⇒ 400，不是 404 —— 那是**请求**坏了，不是资源不存在）。 */
  private UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }
}
