package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
 * 13.4.3.1 利润预警的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/profit-alert}</b>：卷 13 的兄弟模块
 * （13.4.1.1 利润报表 / 13.4.2.1 成本结构 / 13.4.2.2 费用差异 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码有两个：{@code reporting.profit-alert.read}（本模块**只读**预警与阈值规则）、
 * {@code reporting.profit-alert.write}（**只改本模块自己的规则表与通知状态**，
 * 不写任何上游口径、也不写任何业务事实）。
 */
@RestController
@RequestMapping("/reporting/profit-alert")
@Tag(name = "13.4.3.1 利润预警")
public class ReportingProfitAlertController {

  private final AccessService access;
  private final ReportingProfitAlertService alerts;

  public ReportingProfitAlertController(AccessService access, ReportingProfitAlertService alerts) {
    this.access = access;
    this.alerts = alerts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：五类预警分别算什么、哪些确实算不出来",
      description =
          "notDelivered 列出的是**确实拆不出来**的部分（SKU 级成本、订单级成本、广告归因收入等），"
              + "每条都写清原因与影响 —— 「判不出来」与「没异常」在页面上长得一样，含义却相反。")
  public ReportingProfitAlertModels.ProfitAlertCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.profit-alert.read", actor -> alerts.capabilities());
  }

  // ================================================================ 预警清单

  @GetMapping("/alerts")
  @Operation(
      summary = "预警清单（SKU 毛利转负 / 店铺利润率骤降 / 费用占比异常 / 广告亏损超标 / 亏损订单占比突增）",
      description =
          "预警是**从利润投影算出来的读模型**（上游 = 13.4.1.1 的 reporting_profit_fact），"
              + "不是业务事实 —— 与业务系统冲突时以业务系统为准。"
              + "阈值落在租户自己的规则表里，**不是写死的百分比**。"
              + "判不出来的条目单独列在 undecided 里并写明原因，**绝不写成「没异常」**。")
  public ReportingProfitAlertModels.ProfitAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String scopeKey,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.profit-alert.read",
        actor -> {
          var window = resolveWindow(from, to);
          return alerts.alerts(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveAlertType(alertType),
              resolveSeverity(severity),
              resolveScopeType(scopeType),
              scopeKey,
              page,
              size);
        });
  }

  // ================================================================ 阈值规则

  @GetMapping("/rules")
  @Operation(
      summary = "预警阈值规则（可配置项）",
      description =
          "阈值落在**租户自己的规则表**里：固定阈值线（FIXED）或相对历史基线的变化量（BASELINE，单位是百分点）；"
              + "最少历史期数决定基线要前几期同口径窗口。"
              + "阈值的**单位由 (预警类型 × 阈值口径) 派生**，不落库 —— 存副本就会与口径漂移。")
  public List<ReportingProfitAlertModels.ProfitAlertRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.profit-alert.read", actor -> alerts.rules(actor.tenantId()));
  }

  @PutMapping("/rules/{alertType}")
  @Operation(
      summary = "改某一类预警的阈值",
      description =
          "只改写进来的那几个字段；本模块**只改自己的规则表**，不写任何上游口径"
              + "（费率规则在 catalog、归集与分摊结果在 finance）。"
              + "取值范围会被校验（例：「SKU 毛利转负」的阈值必须 ≤ 0）。")
  public ReportingProfitAlertModels.ProfitAlertRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String alertType,
      @RequestBody ReportingProfitAlertModels.ProfitAlertRuleInput input) {
    return call(
        auth,
        "reporting.profit-alert.write",
        actor -> alerts.updateRule(actor.tenantId(), alertType, input));
  }

  // ================================================================ 通知

  @PostMapping("/notifications")
  @Operation(
      summary = "把命中的预警登记成通知投递记录（幂等）",
      description =
          "⚠️ **不真发邮件 / webhook**：那需要地址与凭据，而 AGENTS.md 规定 secret 一律不入仓库。"
              + "本模块做的是**可观测的通知投递记录**（落库 + 查询端点），通道只有站内（CONSOLE）。"
              + "同一个窗口反复登记同一条预警**不会**产生第二条记录（通知轰炸比没有通知更糟）。")
  public ReportingProfitAlertModels.ProfitAlertNotificationSummary registerNotifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.profit-alert.write",
        actor -> {
          var window = resolveWindow(from, to);
          return alerts.registerNotifications(actor.tenantId(), window.from(), window.to());
        });
  }

  @GetMapping("/notifications")
  @Operation(
      summary = "通知投递记录台账（真分页 + 真计数）",
      description = "记录「谁在什么时候被通知过、确认过没有」；终态（已知悉 / 已忽略）**不可复活**。")
  public ReportingProfitAlertModels.ProfitAlertNotificationPage notifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.profit-alert.read",
        actor ->
            alerts.notifications(
                actor.tenantId(), status, resolveAlertType(alertType), page, size));
  }

  @PutMapping("/notifications/{id}")
  @Operation(
      summary = "处理一条通知（已知悉 / 误报忽略）",
      description =
          "终态不可复活：已确认 / 已忽略的记录不能改回待处理 —— "
              + "否则「处理过的预警」会重新出现在待办里，那比不处理更糟。")
  public ReportingProfitAlertModels.ProfitAlertNotification updateNotification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReportingProfitAlertModels.ProfitAlertNotificationInput input) {
    return call(
        auth,
        "reporting.profit-alert.write",
        actor -> alerts.updateNotification(actor.tenantId(), actor.userId(), id, input));
  }

  // ================================================================ 参数解析

  /** 时间窗口。默认「最近 30 天」（含今天）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 预警类型（空 ⇒ 全部）。 */
  private String resolveAlertType(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var type = ReportingProfitAlertModels.AlertType.parse(raw);
    if (type == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "alertType 只能是 "
              + java.util.Arrays.stream(ReportingProfitAlertModels.AlertType.values())
                  .map(Enum::name)
                  .toList()
              + "，收到："
              + raw);
    }
    return type.name();
  }

  /** 严重度（空 ⇒ 全部）。 */
  private String resolveSeverity(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var severity = ReportingProfitAlertModels.Severity.parse(raw);
    if (severity == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "severity 只能是 INFO / WARNING / CRITICAL，收到：" + raw);
    }
    return severity.name();
  }

  /** 归属维度（空 ⇒ 全部）。 */
  private String resolveScopeType(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    if (!List.of("SKU", "SHOP").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeType 只能是 SKU / SHOP，收到：" + raw);
    }
    return value;
  }
}
