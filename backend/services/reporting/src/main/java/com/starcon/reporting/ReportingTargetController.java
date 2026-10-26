package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 13.1.2.1 目标设定的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/targets}：13.1.1.1 用的是 {@code /reporting/overview}，
 * 13.1.1.2 用的是 {@code /reporting/store-comparison}，两个兄弟模块各有各的前缀。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。请求体也**不接受** {@code tenantId} /
 * {@code userId} / {@code membershipId}。
 *
 * <p>权限码：{@code reporting.targets.read}（读）与 {@code reporting.targets.write}（写）。
 */
@RestController
@RequestMapping("/reporting/targets")
@Tag(name = "13.1.2.1 目标设定")
public class ReportingTargetController {

  private final AccessService access;
  private final ReportingTargetService targets;

  public ReportingTargetController(AccessService access, ReportingTargetService targets) {
    this.access = access;
    this.targets = targets;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：归属对象从哪来、历史基线的边界在哪、蓝图点名的指标里哪些口径没登记",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与归属模块 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingTargetModels.TargetCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "reporting.targets.read", actor -> targets.capabilities(actor.tenantId()));
  }

  // ================================================================ 目标指标清单

  @GetMapping("/metrics")
  @Operation(
      summary = "蓝图点名的目标指标清单（按归属维度）：可引用哪些口径码、哪些没登记",
      description =
          "⚠️ **销售额在店铺维度是三个并列口径**（平台侧 / 还原口径 / 口径差异额）—— "
              + "取其一改名叫「销售额」就是抹掉口径差异。"
              + "⚠️ **广告 ROI 与库存周转**在没有登记口径码的维度上如实报「未登记」，"
              + "本模块不自己造公式。")
  public List<ReportingTargetModels.TargetMetricView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String scopeType) {
    return call(
        auth,
        "reporting.targets.read",
        actor -> targets.metrics(actor.tenantId(), scopeType));
  }

  // ================================================================ 归属对象清单

  @GetMapping("/owners")
  @Operation(
      summary = "归属对象清单（部门 / 人员 / 店铺 / 产品）",
      description =
          "部门与人员来自 identity，产品来自 catalog，店铺来自本服务自己的店铺档案投影 —— "
              + "组织本体不在本服务，**本模块不造一张组织表顶替**。")
  public ReportingTargetModels.TargetOwnerList owners(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String scopeType) {
    return call(
        auth,
        "reporting.targets.read",
        actor -> targets.owners(actor.tenantId(), scopeType, auth));
  }

  // ================================================================ 当前周期

  @GetMapping("/current-period")
  @Operation(
      summary = "当前周期边界（**按本租户时区算**，不写死 UTC）",
      description =
          "时区取自本租户投影事实上的分桶时区；本租户还没有投影事实时暂按 UTC，"
              + "并在 timezoneSource 里说清楚。")
  public ReportingTargetModels.TargetCurrentPeriod currentPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "MONTHLY") String granularity) {
    return call(
        auth,
        "reporting.targets.read",
        actor -> targets.currentPeriod(actor.tenantId(), granularity));
  }

  // ================================================================ 分解预览

  @GetMapping("/decomposition")
  @Operation(
      summary = "分解预览（不落库）：给定周期与目标值，算出分解到月 / 周的分解计划",
      description =
          "⚠️ **分解项合计精确等于目标值**：均分时差额归最后一个分解项；按权重时用最大余额法补齐。"
              + "预览与真正落库走**同一套计算**，所以页面上看到的与存进去的逐分一致。")
  public ReportingTargetModels.TargetDecompositionPreview decomposition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      @RequestParam(defaultValue = "MONTHLY") String granularity,
      @RequestParam(defaultValue = "EVEN") String method,
      @RequestParam(defaultValue = "USD") String currency,
      @RequestParam BigDecimal targetValue,
      @RequestParam(required = false) List<BigDecimal> weights) {
    return call(
        auth,
        "reporting.targets.read",
        actor ->
            targets.decompositionPreview(
                periodStart,
                periodEnd,
                granularity,
                method,
                currency(currency),
                targetValue,
                weights));
  }

  // ================================================================ 目标清单

  @GetMapping
  @Operation(
      summary = "目标清单（真分页 + 真计数）：目标记录 + 分解计划概括 + 按币种合计",
      description =
          "⚠️ **合计按币种分列**：不同币种的目标**绝不相加**。"
              + "⚠️ 分解项合计与目标值是否一致由 reconciled 字段说明（数据层另有延迟约束触发器兜底）。")
  public ReportingTargetModels.TargetListResponse list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String ownerId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.targets.read",
        actor ->
            targets.list(
                actor.tenantId(), scopeType, metricCode, status, ownerId, page, size));
  }

  // ================================================================ 目标详情

  @GetMapping("/{id}")
  @Operation(
      summary = "目标详情（含完整分解计划与历史基线快照）",
      description =
          "⚠️ **「没有基线」与「基线为 0」是两件事**：取不到时 baseline.value 为 null、"
              + "baseline.status = NO_DATA，并写明原因。")
  public ReportingTargetModels.TargetView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "reporting.targets.read", actor -> targets.detail(actor.tenantId(), id));
  }

  // ================================================================ 设定目标

  @PostMapping
  @Operation(
      summary = "设定一个目标（同时写下分解计划）",
      description =
          "目标 = **已登记的口径码 + 目标值 + 币种 + 周期 + 归属对象**。"
              + "⚠️ 归属对象由服务端回查权威来源并取**快照**（组织改名后历史目标仍认得出是谁）。"
              + "⚠️ 周期必须与分解粒度对齐（按月 ⇒ 起是 1 日、止是月末日；按周 ⇒ 周一 / 周日）。"
              + "⚠️ 同一归属对象 + 指标 + 币种 + 周期只能有一个目标（重复会 409）。")
  public ReportingTargetModels.TargetView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReportingTargetModels.TargetInput input) {
    return call(
        auth, "reporting.targets.write", actor -> targets.create(actor.tenantId(), input, auth));
  }

  @PutMapping("/{id}")
  @Operation(
      summary = "修改一个目标（目标值 / 周期 / 归属对象 / 分解方式都可改，分解计划随之重写）",
      description =
          "⚠️ 归属对象没变时沿用原快照（不去打扰上游）；变了才重新回查权威来源取快照。")
  public ReportingTargetModels.TargetView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReportingTargetModels.TargetInput input) {
    return call(
        auth,
        "reporting.targets.write",
        actor -> targets.update(actor.tenantId(), id, input, auth));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "删除一个目标（分解计划随外键级联删除）")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "reporting.targets.write",
        actor -> {
          targets.delete(actor.tenantId(), id);
          return null;
        });
  }

  // ================================================================ 刷新历史基线

  @PostMapping("/{id}/baseline")
  @Operation(
      summary = "重新读一次历史基线（事实投影进来之后，基线会变）",
      description =
          "基线窗口 = 目标周期的去年同期。⚠️ 店铺维度取该**店铺自己**的历史事实；"
              + "部门 / 人员 / 产品维度的历史事实**尚未投影到 reporting**，"
              + "那里给的是**租户级参考值**（baseline.scope = TENANT），并写明它不是该归属对象自己的历史。")
  public ReportingTargetModels.TargetView refreshBaseline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth, "reporting.targets.write", actor -> targets.refreshBaseline(actor.tenantId(), id));
  }

  // ================================================================ 参数解析

  /** 币种归一（3 位大写）。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种不能为空。");
    }
    var value = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!value.matches("[A-Z]{3}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位大写字母：" + raw);
    }
    return value;
  }
}
