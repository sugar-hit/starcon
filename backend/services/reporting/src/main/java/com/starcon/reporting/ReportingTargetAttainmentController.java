package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 13.1.2.2 达成追踪的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/target-attainment}：13.1.1.1 用的是 {@code /reporting/overview}，
 * 13.1.2.1 用的是 {@code /reporting/targets}，两个兄弟模块各有各的前缀。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。请求参数也**不接受** {@code tenantId}。
 *
 * <p>权限码：{@code reporting.target-attainment.read}（读）与
 * {@code reporting.target-attainment.write}（落快照）。
 */
@RestController
@RequestMapping("/reporting/target-attainment")
@Tag(name = "13.1.2.2 达成追踪")
public class ReportingTargetAttainmentController {

  private final AccessService access;
  private final ReportingTargetAttainmentService attainment;

  public ReportingTargetAttainmentController(
      AccessService access, ReportingTargetAttainmentService attainment) {
    this.access = access;
    this.attainment = attainment;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：实际值从哪来、哪些维度确实拿不到、判定规则与阈值是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与归属模块 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。"
              + "⚠️ **部门 / 人员维度的实际值尚未投影到 reporting** ⇒ 那两个维度如实报「不可得」；"
              + "产品维度取决于 13.2.2.2 的商品维度投影是否跑过。")
  public ReportingTargetAttainmentModels.AttainmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.target-attainment.read",
        actor -> attainment.capabilities(actor.tenantId()));
  }

  // ================================================================ 达成追踪看板

  @GetMapping("/board")
  @Operation(
      summary = "达成追踪看板：达成率表 + 预测达成 + 未达标高亮 + 排名 + 预警",
      description =
          "🔴 **「算不出来」≠「等于 0」**：拿不到实际值时 currentValue / attainmentRate 为 null，"
              + "状态是 UNAVAILABLE / NOT_STARTED / NOT_COMPARABLE —— "
              + "**0% 达成率是有业务含义的断言（确实一件都没做成），「不可得」不是**。"
              + "⚠️ **预测达成 = 按当前速度直线推算期末值**（当前值 ÷ 时间进度）；"
              + "时间进度为 0 时预测算不出来（null，不是 0）。"
              + "⚠️ **评估日取自数据库时钟**（按本租户时区折算成日历日），不是 JVM 墙钟。"
              + "⚠️ **未达标高亮只认 AT_RISK 与 BEHIND** —— 「算不出来」的那三类不是「没达标」。"
              + "⚠️ **绝不跨币种相加**：实际值按目标币种取数，排名不跨币种、不跨指标。"
              + "⚠️ 概览计数与预警清单覆盖**全量**符合条件的目标（不随分页截断）；"
              + "排名位次在全量上计算，只有达成率行会被切进本页。")
  public ReportingTargetAttainmentModels.AttainmentBoard board(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.target-attainment.read",
        actor ->
            attainment.board(
                actor.tenantId(), scopeType, metricCode, currency, status, page, size));
  }

  // ================================================================ 排名

  @GetMapping("/ranking")
  @Operation(
      summary = "排名（人员 / 产品维度）：同归属维度 + 同口径 + 同币种 + 同周期内比",
      description =
          "🔴 **算不出达成率的目标不进排名** —— 给「不可得」一个名次等于替它编了一个成绩。"
              + "⚠️ **不跨币种、不跨指标排名**：销售额达成率与毛利率达成率不是一回事。")
  public ReportingTargetAttainmentModels.AttainmentRanking ranking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.target-attainment.read",
        actor ->
            attainment.ranking(
                actor.tenantId(), scopeType, metricCode, currency, status, page, size));
  }

  // ================================================================ 快照清单

  @GetMapping("/snapshots")
  @Operation(
      summary = "已落快照清单（真分页 + 真计数）：下游 17 绩效核算 / 1 工作台预警读的就是它",
      description =
          "快照是**一次评估的落库形态**（派生读模型，不是业务事实）："
              + "记的是「在某个评估日、按当时已投影的事实，达成率与预测达成是多少」。"
              + "「当前值 / 达成率」为 null 表示**当时拿不到实际值**（不是 0%）。")
  public ReportingTargetAttainmentModels.AttainmentSnapshotPage snapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String evaluatedOn,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.target-attainment.read",
        actor ->
            attainment.snapshots(
                actor.tenantId(), scopeType, metricCode, evaluatedOn, page, size));
  }

  // ================================================================ 评估（落快照）

  @PostMapping("/evaluate")
  @Operation(
      summary = "评估并落快照：把当前的达成率 / 预测达成冻成一个可回看的时间点",
      description =
          "🔴 **写的是派生读模型，不是业务事实**：同一目标同一天重复评估是**覆盖**，不是堆积。"
              + "「月初就知道会不会达标」这句话要求的是**一个可回看的时间点**，"
              + "而不是一个随时会变的实时值 —— 所以落快照。"
              + "⚠️ 评估日取自数据库时钟（按本租户时区折算成日历日）。")
  public ReportingTargetAttainmentModels.AttainmentEvaluateResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status) {
    return call(
        auth,
        "reporting.target-attainment.write",
        actor ->
            attainment.evaluate(actor.tenantId(), scopeType, metricCode, currency, status));
  }
}
