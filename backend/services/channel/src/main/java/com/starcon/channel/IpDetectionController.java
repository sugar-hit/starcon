package com.starcon.channel;

import static com.starcon.channel.IpDetectionModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.4.1.2 检测与预警。
 *
 * <p>蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * <b>图片侵权检测难度高于文本</b>，误判与漏判都要留人工复核环节。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、各条口径、未交付的外部边界逐条列出。
 *   <li>{@code POST /detections} —— 🔴 <b>发起一次检测</b>（刊登前 / 已上架商品 / 手工内容）。
 *   <li>{@code GET /detections} —— 检测报告清单（**真分页 + 真筛选**）。
 *   <li>{@code GET /detections/{id}} —— 报告详情（命中项 + 预警 + 整改建议 + 复核任务）。
 *   <li>{@code GET /alerts} —— 预警清单（真分页）。
 *   <li>{@code PUT /alerts/{id}/status} —— 确认 / 处置预警（**终态不可回退**）。
 *   <li>{@code GET /remediations} —— 整改建议清单（真分页）。
 *   <li>{@code POST /remediations} —— 手工新增一条整改建议。
 *   <li>{@code PUT /remediations/{id}/status} —— 推进整改建议状态（**终态不可回退**）。
 *   <li>{@code GET /reviews} —— 🔴 <b>人工复核队列</b>（图片命中自动进这里）。
 *   <li>{@code POST /reviews} —— 提一条复核（文本命中被判误判时的申诉）。
 *   <li>{@code PUT /reviews/{id}} —— 复核结论（CONFIRMED / DISMISSED，**终态不可回退**）。
 *   <li>{@code GET /external-queries} —— 外部商标 / 专利查询清单。
 *   <li>{@code POST /external-queries} —— 登记一条外部查询（**按查询编码幂等**）。
 *   <li>{@code GET /external-queries/{id}} —— 外部查询详情（含登记回来的结果）。
 *   <li>{@code POST /external-queries/{id}/hits} —— 🔴 登记查回来的外部结果（append-only）。
 *   <li>{@code PUT /external-queries/{id}/status} —— 推进查询状态（查无结果 / 查询失败）。
 *   <li>{@code GET /inspection-plans} —— 巡检计划清单。
 *   <li>{@code POST /inspection-plans} —— 新建巡检计划（**按计划编码幂等**）。
 *   <li>{@code PUT /inspection-plans/{id}} —— 改巡检计划。
 *   <li>{@code GET /inspection-runs} —— 巡检运行清单。
 *   <li>{@code GET /inspection-runs/{id}} —— 巡检运行详情（这一轮产出的报告）。
 *   <li>{@code POST /inspection-runs} —— 🔴 <b>跑一轮巡检</b>（外部调度触发的一次入口）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：
 *
 * <ul>
 *   <li><b>没有拦截 / 放行端点</b> —— 拦截归 16.2.1.2（已交付）。
 *       本模块只产出<b>建议</b>（{@code recommended_action}）+ 预警 + 整改建议。</li>
 *   <li><b>没有删除端点</b> —— 检测结论与命中项是<b>证据</b>（DB 层 append-only 钉死），
 *       复核任务与预警也是<b>终态不可回退</b>。</li>
 *   <li><b>没有「以图搜图」端点</b> —— 本仓<b>没有</b>图像识别引擎，
 *       图片比对是<b>标识文本比对</b>，命中<b>一律</b>进人工复核队列。</li>
 *   <li><b>没有「自动外呼外部商标库」端点</b> —— 本仓<b>没有</b>凭据与网络出口，
 *       外部结果靠<b>登记承接</b>。</li>
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ip-detections")
public class IpDetectionController {

  private final AccessService access;
  private final IpDetectionService service;

  public IpDetectionController(AccessService access, IpDetectionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、各条口径，以及**未交付的外部边界逐条列出**（available=false + ownerModule + 影响）。 */
  @GetMapping("/capabilities")
  public IpDetectionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 检测 ----

  /** 🔴 发起一次检测（刊登前 / 已上架商品 / 手工内容）。 */
  @PostMapping("/detections")
  public IpDetectionReportDetail detect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpDetectionSubjectInput input) {
    return call(auth, "channel.operation.create", a -> service.detect(input, auth));
  }

  /** 检测报告清单（真分页 + 真筛选）。 */
  @GetMapping("/detections")
  public IpDetectionReportPage detections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String conclusion,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String triggerType,
      @RequestParam(required = false, defaultValue = "false") boolean pendingOnly,
      @RequestParam(required = false) String inspectionRunId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.reports(
                riskLevel,
                conclusion,
                subjectType,
                triggerType,
                pendingOnly,
                inspectionRunId,
                keyword,
                page,
                size));
  }

  /** 报告详情（命中项 + 预警 + 整改建议 + 复核任务）。 */
  @GetMapping("/detections/{id}")
  public IpDetectionReportDetail detection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.report(id));
  }

  // ---- 预警 ----

  /** 预警清单（真分页 + 真筛选）。 */
  @GetMapping("/alerts")
  public IpDetectionAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.alerts(status, alertType, page, size));
  }

  /** 确认 / 处置一条预警（终态不可回退）。 */
  @PutMapping("/alerts/{id}/status")
  public IpDetectionAlert setAlertStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpDetectionAlertStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.setAlertStatus(id, input));
  }

  // ---- 整改建议 ----

  /** 整改建议清单（真分页 + 真筛选）。 */
  @GetMapping("/remediations")
  public IpDetectionRemediationPage remediations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String actionCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.remediations(status, actionCode, page, size));
  }

  /** 手工新增一条整改建议。 */
  @PostMapping("/remediations")
  public IpDetectionRemediation addRemediation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpDetectionRemediationInput input) {
    return call(auth, "channel.operation.create", a -> service.addRemediation(input));
  }

  /** 推进整改建议状态（终态不可回退）。 */
  @PutMapping("/remediations/{id}/status")
  public IpDetectionRemediation setRemediationStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpDetectionRemediationStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.setRemediationStatus(id, input));
  }

  // ---- 🔴 人工复核 ----

  /** 人工复核队列（图片命中自动进这里）。 */
  @GetMapping("/reviews")
  public IpDetectionReviewTaskPage reviews(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.reviews(status, page, size));
  }

  /** 提一条复核（文本命中被判误判时的申诉）。 */
  @PostMapping("/reviews")
  public IpDetectionReviewTask requestReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpDetectionReviewInput input) {
    return call(auth, "channel.operation.create", a -> service.requestReview(input));
  }

  /** 复核结论（CONFIRMED / DISMISSED，终态不可回退）。 */
  @PutMapping("/reviews/{id}")
  public IpDetectionReviewTask decideReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpDetectionReviewDecisionInput input) {
    return call(auth, "channel.operation.update", a -> service.decideReview(id, input));
  }

  // ---- 外部商标 / 专利查询（登记承接） ----

  /** 外部查询清单（真分页）。 */
  @GetMapping("/external-queries")
  public IpDetectionExternalQueryPage externalQueries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.externalQueries(status, page, size));
  }

  /** 登记一条外部查询（按查询编码幂等）。 */
  @PostMapping("/external-queries")
  public IpDetectionExternalQuery createExternalQuery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpDetectionExternalQueryInput input) {
    return call(auth, "channel.operation.create", a -> service.createExternalQuery(input));
  }

  /** 外部查询详情（含登记回来的结果）。 */
  @GetMapping("/external-queries/{id}")
  public IpDetectionExternalQueryDetail externalQuery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.externalQuery(id));
  }

  /** 🔴 登记查回来的外部结果（append-only）。 */
  @PostMapping("/external-queries/{id}/hits")
  public IpDetectionExternalQueryDetail registerExternalHits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody List<IpDetectionExternalHitInput> hits) {
    return call(auth, "channel.operation.create", a -> service.registerExternalHits(id, hits));
  }

  /** 推进查询状态（查无结果 / 查询失败）。 */
  @PutMapping("/external-queries/{id}/status")
  public IpDetectionExternalQuery setExternalQueryStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String errorMessage) {
    return call(
        auth, "channel.operation.update", a -> service.setExternalQueryStatus(id, status, errorMessage));
  }

  // ---- 🔴 巡检 ----

  /** 巡检计划清单。 */
  @GetMapping("/inspection-plans")
  public List<IpDetectionInspectionPlan> inspectionPlans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.plans());
  }

  /** 新建巡检计划（按计划编码幂等）。 */
  @PostMapping("/inspection-plans")
  public IpDetectionInspectionPlan createInspectionPlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpDetectionInspectionPlanInput input) {
    return call(auth, "channel.operation.create", a -> service.createPlan(input));
  }

  /** 改巡检计划（计划编码是身份，不可改）。 */
  @PutMapping("/inspection-plans/{id}")
  public IpDetectionInspectionPlan updateInspectionPlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpDetectionInspectionPlanInput input) {
    return call(auth, "channel.operation.update", a -> service.updatePlan(id, input));
  }

  /** 巡检运行清单（真分页）。 */
  @GetMapping("/inspection-runs")
  public IpDetectionInspectionRunPage inspectionRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.inspectionRuns(page, size));
  }

  /** 巡检运行详情（这一轮产出的报告）。 */
  @GetMapping("/inspection-runs/{id}")
  public IpDetectionInspectionRunDetail inspectionRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.inspectionRun(id));
  }

  /** 🔴 跑一轮巡检（外部调度触发的一次入口；本服务**不装 @Scheduled**）。 */
  @PostMapping("/inspection-runs")
  public IpDetectionInspectionRunDetail runInspection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String planId) {
    return call(auth, "channel.operation.create", a -> service.runInspection(planId, auth));
  }
}
