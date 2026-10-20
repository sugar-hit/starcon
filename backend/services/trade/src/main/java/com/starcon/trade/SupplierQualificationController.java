package com.starcon.trade;

import static com.starcon.trade.SupplierQualificationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.1.2.3 供应商资质。
 *
 * <p>命名空间 {@code /trade/supplier-qualifications}：卷 06 的前端入口是 {@code /supply/qualifications}，
 * 浏览器只经 /api/trade 代理访问；后端路径按**服务**命名空间走，与 6.1.2.1（前端 /supply/sources、
 * 后端 /trade/sources）、6.4.1.1（前端 /purchase/processing、后端 /trade/processing）同一纪律。
 *
 * <p>权限码用 {@code trade.qualifications.read} / {@code trade.qualifications.update}：
 * 登记资质、上传文件、审核流转、生成提醒都是写动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，再在一个事务里绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/supplier-qualifications")
public class SupplierQualificationController {

  private final AccessService access;
  private final SupplierQualificationService qualifications;

  public SupplierQualificationController(
      AccessService access, SupplierQualificationService qualifications) {
    this.access = access;
    this.qualifications = qualifications;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」「阻断的口径是什么」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  @GetMapping("/capabilities")
  public SupplierQualificationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "qualifications.read", a -> qualifications.capabilities());
  }

  /** 词表选项（前端下拉的数据源，与后端同源）。 */
  @GetMapping("/options")
  public SupplierQualificationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "qualifications.read", a -> qualifications.options());
  }

  /** 概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public QualificationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "qualifications.read", a -> qualifications.overview());
  }

  // ================================================================ 策略（提醒窗口 + 必备资质）

  /** 租户级策略：「即将过期」窗口（天）与必备资质类型。 */
  @GetMapping("/policy")
  public QualificationPolicyRow policy(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "qualifications.read", a -> qualifications.policy());
  }

  /** 修改租户级策略。 */
  @PutMapping("/policy")
  public QualificationPolicyRow putPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody QualificationPolicyInput input) {
    return call(auth, "qualifications.update", a -> qualifications.putPolicy(input));
  }

  // ================================================================ 资质档案

  /** 资质列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping("/qualifications")
  public QualificationPage qualifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRoleId,
      @RequestParam(required = false) String qualificationType,
      @RequestParam(required = false) String reviewStatus,
      @RequestParam(required = false) String expiryState,
      @RequestParam(required = false) UUID brandId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") Boolean desc,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "qualifications.read",
        a ->
            qualifications.pageQualifications(
                new QualificationQuery(
                    supplierRoleId,
                    qualificationType,
                    reviewStatus,
                    expiryState,
                    brandId,
                    keyword,
                    sort,
                    Boolean.TRUE.equals(desc),
                    page,
                    size)));
  }

  /** 登记资质。 */
  @PostMapping("/qualifications")
  public QualificationRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody QualificationInput input) {
    return call(auth, "qualifications.update", a -> qualifications.createQualification(input, auth));
  }

  /** 资质详情：档案 + 全部文件 + 全部审核流转。 */
  @GetMapping("/qualifications/{qualificationId}")
  public QualificationDetail qualification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID qualificationId) {
    return call(auth, "qualifications.read", a -> qualifications.qualification(qualificationId));
  }

  /** 修改资质（仅 DRAFT / REJECTED / REVOKED 可改内容；乐观锁）。 */
  @PutMapping("/qualifications/{qualificationId}")
  public QualificationRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID qualificationId,
      @Valid @RequestBody QualificationUpdateInput input) {
    return call(
        auth, "qualifications.update", a -> qualifications.updateQualification(qualificationId, input, auth));
  }

  /**
   * 审核流转（状态机）。
   *
   * <p>动作：SUBMIT / APPROVE / REJECT / REVOKE / RESUBMIT。允许的转换只有六条，
   * 服务层与 DB 触发器各钉一次 —— 直连数据库改状态也会被拒。
   */
  @PostMapping("/qualifications/{qualificationId}/review")
  public QualificationReviewSaved review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID qualificationId,
      @Valid @RequestBody QualificationReviewInput input) {
    return call(auth, "qualifications.update", a -> qualifications.review(qualificationId, input));
  }

  /** 上传资质文件（文件本体不入库，只存对象存储的 key）。 */
  @PostMapping("/qualifications/{qualificationId}/documents")
  public QualificationDocumentSaved uploadDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID qualificationId,
      @Valid @RequestBody QualificationDocumentInput input) {
    return call(
        auth, "qualifications.update", a -> qualifications.uploadDocument(qualificationId, input));
  }

  // ================================================================ 过期提醒

  /** 过期提醒清单（真分页 + 真筛选）。「即将过期」与「已过期」分列显示。 */
  @GetMapping("/reminders")
  public QualificationReminderPage reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRoleId,
      @RequestParam(required = false) String reminderKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "qualifications.read",
        a ->
            qualifications.pageReminders(
                new QualificationReminderQuery(supplierRoleId, reminderKind, status, page, size)));
  }

  /**
   * 生成 / 刷新过期提醒（幂等）。
   *
   * <p>刻意**不是**自动的：这是一个显式的写动作，由界面或定时任务调用。生成提醒**不等于**阻断 ——
   * 阻断由阻断判定端点给出。
   */
  @PostMapping("/reminders/scan")
  public QualificationReminderScanResult scanReminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "qualifications.update", a -> qualifications.scanReminders());
  }

  /** 处理一条提醒（确认 / 关闭）。 */
  @PutMapping("/reminders/{reminderId}")
  public QualificationReminderRow handleReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reminderId,
      @Valid @RequestBody QualificationReminderHandleInput input) {
    return call(auth, "qualifications.update", a -> qualifications.handleReminder(reminderId, input));
  }

  // ================================================================ 候选（跨服务只读）

  /** 供应商候选（catalog 的 SUPPLIER 角色，只读）。 */
  @GetMapping("/suppliers")
  public List<QualificationSupplierCandidateRow> suppliers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "qualifications.read", a -> qualifications.supplierCandidates(keyword, auth));
  }

  /** 品牌候选（03 品牌，只读）。 */
  @GetMapping("/brands")
  public List<QualificationBrandCandidateRow> brands(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "qualifications.read", a -> qualifications.brandCandidates(keyword, auth));
  }

  // ================================================================ 阻断判定

  /**
   * 供应商维度的阻断判定。
   *
   * <p>这是蓝图加粗句「资质过期必须阻断相关商品的刊登与发货，不能只做提醒」的落点：
   * 直接给 {@code blocked} + 原因码 + 人话，供 04 刊登与发货链路调用。
   */
  @GetMapping("/suppliers/{supplierRoleId}/blocking-decision")
  public BlockingDecision supplierBlocking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID supplierRoleId) {
    return call(auth, "qualifications.read", a -> qualifications.supplierBlocking(supplierRoleId, null, auth));
  }

  /**
   * 供应商 × 品牌的阻断判定（判的是「这个供应商现在还能不能卖这个牌子」）。
   *
   * <p>品牌放在**路径段**而不是查询参数：查询参数上的空串会在 Spring 绑 UUID 时报 400，
   * 而「没传品牌」与「传了个空品牌」在这里是**两件事**。
   */
  @GetMapping("/suppliers/{supplierRoleId}/brands/{brandId}/blocking-decision")
  public BlockingDecision brandBlocking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID supplierRoleId,
      @PathVariable UUID brandId) {
    return call(
        auth, "qualifications.read", a -> qualifications.supplierBlocking(supplierRoleId, brandId, auth));
  }

  /**
   * 商品维度的阻断判定。
   *
   * <p>「相关商品」的供应商取自 6.1.2.1 已交付的货源关联（只读），逐个判资质后汇总。
   */
  @GetMapping("/skus/{skuId}/blocking-decision")
  public BlockingDecision skuBlocking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "qualifications.read", a -> qualifications.skuBlocking(skuId, auth));
  }

  // 裸路径 /trade/supplier-qualifications **刻意不放行**到 api-proxy：后端没有
  // 「一条不存在的汇总列表接口」，放行它会让界面以为可用。
}
