package com.starcon.trade;

import static com.starcon.trade.PlatformDisputeAppealModels.*;

import java.util.UUID;
import java.util.function.Function;
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

/**
 * 11.4.1.2 证据与申诉。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「组织举证材料（物流签收、沟通记录、商品照片、检测报告）
 * 打包提交申诉，记录平台回复与结果。」输入：「举证材料；申诉文本（可用模板）；提交动作。」
 * 输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
 * 加粗句：「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
 *
 * <p><b>命名空间独立于 {@code /trade/platform-disputes}（11.4.1.1）</b>：
 * 本模块的产物是**另一种单据**（申诉 = 一次向平台的申辩 + 一份证据包 + 一条平台回复），
 * 不是纠纷单的另一个视图。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}；写 {@code trade.aftersales.write}；
 * <b>收回平台回复与结果</b>用独立的 {@code trade.aftersales.platform} ——
 * 平台回复是**平台侧回写**的事实，登记它的人不该是随手改申诉文本的人。
 *
 * <p><b>刻意不提供</b>：任何「上传文件当举证材料」的端点 ——
 * 举证材料只能引用系统里已有的一条事实记录。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} / {@link #platform} 先向 identity
 * 确认已认证成员关系，再在一个事务里绑定 {@code app.current_tenant}，
 * 由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的申诉 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误。
 */
@RestController
@RequestMapping("/trade/platform-dispute-appeals")
public class PlatformDisputeAppealController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";
  /** 收回平台回复与结果用的**独立权限**：平台侧回写的事实不该由随手改文本的人登记。 */
  private static final String PLATFORM = "aftersales.platform";

  private final AccessService access;
  private final PlatformDisputeAppealService appeals;

  public PlatformDisputeAppealController(
      AccessService access, PlatformDisputeAppealService appeals) {
    this.access = access;
    this.appeals = appeals;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  /** 收回平台回复入口：需要<b>独立</b>的 {@code trade.aftersales.platform}。 */
  private <T> T platform(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + PLATFORM);
    return access.run(actor, "trade." + PLATFORM, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明（租户级静态事实，不带 id 段）。
   *
   * <p>⚠️ 这里**如实报出两个缺口**：channel 服务**没有申诉提交端点**（本模块只落待提交平台事实），
   * 16 合规（账号绩效）**未交付**（本模块不产出绩效指标）。
   */
  @GetMapping("/capabilities")
  public PlatformAppealCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> appeals.capabilities());
  }

  // ================================================================ 申诉单

  /**
   * 申诉单列表（真分页 + 多维筛选 + 排序白名单 + 按币种分组的金额口径）。
   *
   * <p>⚠️ 列表里的「能不能提交」由 **DB 判据函数**给出（与 DB 触发器同一个函数），
   * 不是服务层自己算的。
   */
  @GetMapping
  public PlatformAppealPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String disputeId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String resolutionCode,
      @RequestParam(required = false) String submissionStatus,
      @RequestParam(required = false) Boolean submittableOnly,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            appeals.listAppeals(
                new PlatformAppealListQuery(
                    page, size, status, disputeId, currency, resolutionCode, submissionStatus,
                    submittableOnly, from, to, keyword, sort)));
  }

  /** 单张申诉单全景（头 + 材料 + 提交记录 + 留痕 + 判据 + 可执行动作）。 */
  @GetMapping("/{id}")
  public PlatformAppealDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> appeals.appealDetail(id));
  }

  /**
   * 建申诉单（草稿）。
   *
   * <p>⚠️ <b>没有金额入参</b>：主张金额由服务端从纠纷单上读（快照），不信客户端。
   */
  @PostMapping
  public PlatformAppealDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PlatformAppealCreate request) {
    return write(auth, actor -> appeals.createAppeal(request, actor.userId()));
  }

  /** 改申诉文本（仅草稿态）。 */
  @PutMapping("/{id}/text")
  public PlatformAppealDetail updateText(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealTextUpdate request) {
    return write(auth, actor -> appeals.updateText(id, request, actor.userId()));
  }

  // ================================================================ 证据材料

  /**
   * 挂一份证据材料。
   *
   * <p>⚠️ <b>没有「上传文件」入参</b>：材料必须指向系统里已有的一条事实记录
   * （{@code sourceModule} + {@code sourceRefId}）；
   * <b>证据类型由来源记录推导</b>，调用方不能自由填。
   */
  @PostMapping("/{id}/materials")
  public PlatformAppealDetail attachMaterial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealMaterialCreate request) {
    return write(auth, actor -> appeals.attachMaterial(id, request, actor.userId()));
  }

  /** 移除一份证据材料（仅草稿态）。 */
  @DeleteMapping("/{id}/materials/{materialId}")
  public PlatformAppealDetail removeMaterial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID materialId) {
    return write(auth, actor -> appeals.removeMaterial(id, materialId, actor.userId()));
  }

  // ================================================================ 提交与结果

  /**
   * 提交申诉（草稿 → 已提交平台）。
   *
   * <p>⚠️ **时限判定只有一个判据**（DB 函数 {@code platform_dispute_appeal_gate}），
   * 与 DB 触发器守卫① 调的是同一个函数 ⇒ 规则改一次两边同时改。
   * ⚠️ **不调平台**：channel 服务没有申诉端点，本端点只落**待提交平台事实**。
   */
  @PostMapping("/{id}/submit")
  public PlatformAppealDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealSubmit request) {
    return write(auth, actor -> appeals.submitAppeal(id, request, actor.userId()));
  }

  /** 重试提交到平台（把失败的尝试重新排队；**复用同一行**，幂等键保持稳定）。 */
  @PostMapping("/{id}/retry-submission")
  public PlatformAppealDetail retrySubmission(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PlatformAppealRetrySubmission request) {
    return write(
        auth,
        actor ->
            appeals.retrySubmission(
                id, request == null ? new PlatformAppealRetrySubmission(null) : request,
                actor.userId()));
  }

  /**
   * 记录一次提交尝试的结果（已发出 / 失败 / 平台已受理）。
   *
   * <p>需要<b>独立</b>的 {@code trade.aftersales.platform} 权限 ——
   * 「平台收下了」这句话不该由随手改申诉文本的人写。
   * ⚠️ channel 侧没有申诉适配器 ⇒ 当前由平台侧经授权 API 或人工调用。
   */
  @PostMapping("/{id}/submission-attempts")
  public PlatformAppealDetail recordSubmissionAttempt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealSubmissionAttempt request) {
    return platform(auth, actor -> appeals.recordSubmissionAttempt(id, request, actor.userId()));
  }

  /**
   * 收回平台回复与结果（**授权 API**：平台侧 / channel 适配器回写）。
   *
   * <p>需要<b>独立</b>的 {@code trade.aftersales.platform} 权限。
   * ⚠️ 「部分」这一档必须给裁定金额，且必须小于主张金额。
   */
  @PostMapping("/{id}/platform-reply")
  public PlatformAppealDetail platformReply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealPlatformReply request) {
    return platform(auth, actor -> appeals.recordPlatformReply(id, request, actor.userId()));
  }

  /** 我方撤诉。必须写理由。 */
  @PostMapping("/{id}/withdraw")
  public PlatformAppealDetail withdraw(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformAppealWithdraw request) {
    return write(auth, actor -> appeals.withdrawAppeal(id, request, actor.userId()));
  }
}
