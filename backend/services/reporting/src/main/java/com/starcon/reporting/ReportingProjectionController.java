package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.1.1.1 的**投影摄取**端点（事件 → 投影管道）。
 *
 * <p>⚠️ **这里写的不是业务事实**（卷 13 卷首「不产生业务事实、不做业务写入」）：
 * 写进去的是**投影读模型**，随时可以清空重建。真正的业务事实仍然在 trade / finance /
 * warehouse / channel / catalog 那边；两边冲突时**以业务系统为准**。
 *
 * <p>两条入口（推 / 拉）共用**一份幂等键、一份口径表、一套重建逻辑**：
 *
 * <ul>
 *   <li><b>推</b>（本类的 {@code /events}）是蓝图设计的**主路径**：上游把事实作为事件投递过来。
 *   <li><b>拉</b>（本类的 {@code /refresh}）是**同一条管道的另一种入口**：本服务自己去上游的
 *       授权只读 API 取事实，归一化成同一套 envelope 后交给同一个投影器。
 * </ul>
 *
 * <p>⚠️ **租户绝不来自请求体或请求头**：本服务按已认证成员关系解析租户。
 * 事件体里**没有** tenantId 字段 —— 「客户端自称的租户」与 {@code X-Tenant-Id} 头是同一种
 * 不可信输入。所以伪造 {@code X-Tenant-Id} 头改不动落库的租户（RLS 在 DB 层兜底）。
 *
 * <p>权限码：{@code reporting.projection.write}（摄取）与 {@code reporting.projection.read}
 * （查看投影状态）。**没有任何「创建业务事实」的权限码**。
 */
@RestController
@RequestMapping("/reporting/projection")
@Tag(name = "13.1.1.1 经营总览 · 投影摄取")
public class ReportingProjectionController {

  private final AccessService access;
  private final ReportingProjectionService projection;
  private final ReportingRefreshService refresh;

  public ReportingProjectionController(
      AccessService access, ReportingProjectionService projection, ReportingRefreshService refresh) {
    this.access = access;
    this.projection = projection;
    this.refresh = refresh;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 推（主路径）

  @PostMapping("/events")
  @Operation(
      summary = "摄取一批规范化事件（投影主路径）",
      description =
          "同一事件重复投递会被幂等跳过（reporting_projection_event 的唯一键），投影不会翻倍。"
              + "事件体里**没有** tenantId：租户由已认证成员关系解析。")
  public ReportingProjectionModels.ProjectionBatchResult events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody List<ReportingProjectionModels.ProjectionEvent> events) {
    if (events == null || events.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件批次不能为空");
    }
    return call(
        auth,
        "reporting.projection.write",
        actor -> projection.applyBatch(actor.tenantId(), events));
  }

  // ================================================================ 拉（同一条管道）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取事实并投影（与推共用同一套幂等与口径）",
      description =
          "来源取值：trade-orders / trade-returns / channel-ads / finance-receivables / "
              + "finance-cost / finance-profit / warehouse-inventory / catalog-rates。"
              + "任一来源读不到时**那一路失败**（503），不会用空集合冒充「上游没有数据」。")
  public List<ReportingProjectionModels.RefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<String> sources,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时区无法解析：" + timezone);
    }
    return call(
        auth,
        "reporting.projection.write",
        actor -> refresh.refresh(actor.tenantId(), auth, from, to, sources, zone));
  }

  // ================================================================ 重建

  @PostMapping("/rebuild")
  @Operation(
      summary = "清空投影并重新拉取（可重建性）",
      description =
          "只清投影（事实 + 构成明细 + 游标），**不碰业务事实** —— "
              + "业务事实在上游服务那边。清空后重放同一批事件即可回到同一状态。"
              + "⚠️ **事件账本不清**：账本是举证凭据，DB 层 REVOKE 了 UPDATE/DELETE，删不动；"
              + "「这批事件投过了」这件事靠**重建纪元 +1** 抹掉（同一 source_event_id 在新纪元里"
              + "是一条全新的账本行），所以旧账本原样留着当上一轮投过什么的证据。")
  public RebuildResult rebuild(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时区无法解析：" + timezone);
    }
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var cleared = projection.clearProjection(actor.tenantId());
          var results = refresh.refresh(actor.tenantId(), auth, from, to, List.of(), zone);
          return new RebuildResult(cleared, results);
        });
  }

  /** 重建结果。 */
  public record RebuildResult(int clearedFacts, List<ReportingProjectionModels.RefreshResult> refreshed) {}
}
