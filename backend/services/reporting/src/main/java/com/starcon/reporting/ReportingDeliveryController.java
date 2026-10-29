package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.2.1「导出与订阅」的**投递**端点。
 *
 * <pre>
 *   投递详情   GET  /reporting/custom-report/deliveries/{id}
 *   执行投递   POST /reporting/custom-report/deliveries/dispatch
 * </pre>
 *
 * <p>🔴 **单条详情是上游 13.6.1.1 刻意没放行的那条形状**：它只给了台账列表，
 * 「这一条到底投递了没有、失败原因是什么」答不上来。
 *
 * <p>🔴 **投递记录的状态是真的投递结果**：只有传输层回报成功才会是 DELIVERED。
 * 没配置 SMTP / Webhook 时它会是 **FAILED** 并写明缺什么 ——
 * **绝不允许在没发出去的情况下标成 DELIVERED**。
 *
 * <p>⚠️ **本服务不装 {@code @Scheduled}**（与上游 13.6.1.1、channel / identity 同一纪律）⇒
 * 投递由**外部调度**触发 {@code POST /deliveries/dispatch}。
 */
@RestController
@RequestMapping("/reporting/custom-report")
@Tag(name = "13.6.2.1 导出与订阅")
public class ReportingDeliveryController {

  private final AccessService access;
  private final ReportingDeliveryService deliveries;

  public ReportingDeliveryController(AccessService access, ReportingDeliveryService deliveries) {
    this.access = access;
    this.deliveries = deliveries;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  @GetMapping("/deliveries/{deliveryId}")
  @Operation(
      summary = "一条投递记录的详情（真的投了没有、失败原因是什么）",
      description =
          "🔴 **「待投递」不是「已发送」**：status 是真的投递结果 —— "
              + "只有传输层回报成功才是 DELIVERED，没配置通道或被拒收是 FAILED 并写明原因。")
  public ReportingExportModels.ExportSubscriptionDeliveryView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String deliveryId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> deliveries.detail(actor.tenantId(), uuid(deliveryId, "投递记录 id")));
  }

  @PostMapping("/deliveries/dispatch")
  @Operation(
      summary = "跑一轮投递：把待投递记录真的送出去",
      description =
          "⚠️ **本服务不装 @Scheduled**（多副本下自带定时器要做主节点选举与分布式锁）"
              + "⇒ 这一轮由**外部调度**触发。"
              + "🔴 **没配置 SMTP / Webhook 时，投递会被记为 FAILED 并写明缺什么** —— "
              + "不会假装「已发送」。")
  public ReportingExportModels.ExportSubscriptionDispatchResult dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) ReportingExportModels.ExportSubscriptionDispatchRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor ->
            deliveries.dispatch(
                actor.tenantId(), request == null ? null : request.limit()));
  }

  // ================================================================ 参数解析

  /** uuid 路径参数（格式不对 ⇒ 400，不是 404）。 */
  private UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }
}
