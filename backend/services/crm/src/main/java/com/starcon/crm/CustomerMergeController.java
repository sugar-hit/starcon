package com.starcon.crm;

import static com.starcon.crm.CustomerMergeModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.3.1.3 跨店合并。
 *
 * <p>命名空间 {@code /crm/customer-merge}：前端入口 {@code /crm/customer-merge}，
 * 浏览器只经 {@code /api/crm} 代理访问。
 *
 * <p>⚠️ 与同卷兄弟模块**刻意不共用命名空间**：
 * {@code /crm/customers}（15.3.1.1 客户列表）、{@code /crm/customer-tags}（15.3.2.1 客户标签）、
 * {@code /crm/customer-contacts}（15.3.2.2 接触记录）回答的都是「这位客户是谁 / 属于哪一类」，
 * 本模块回答的是「这两个身份是不是同一个人、要不要合并、能不能拆开」。
 *
 * <p>🔴 <b>权限码复用 {@code crm.customers.*}</b>：
 * 本模块是 15.3.1.1「客户主档」的下游，管理的还是同一批客户的身份关系，
 * 刻意**不新开** {@code crm.merge.*} 一套权限码 ——
 * 多开一套只会让「能看客户列表但不能看合并队列」这种组合配置出现，
 * 而它在业务上说不通（看不懂客户的人不需要看合并队列，但能看合并队列的人必然看得懂客户）。
 * 读用 {@code crm.customers.read}，写（扫描 / 确认 / 拒绝 / 拆分）用 {@code crm.customers.update}，
 * 看明文联系方式仍然要**另外**的 {@code crm.customers.pii.read}。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 <b>请求体不接受 tenantId / userId / membershipId</b>：
 * 租户与操作人一律来自已认证成员关系。客户端自称的租户是<b>不可信输入</b> ——
 * Jackson 的 fail-on-unknown-properties 直接把它变成 400，而不是「默默忽略」。
 */
@RestController
@RequestMapping("/crm/customer-merge")
public class CustomerMergeController {

  private final AccessService access;
  private final CustomerMergeService merge;

  public CustomerMergeController(AccessService access, CustomerMergeService merge) {
    this.access = access;
    this.merge = merge;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.customers." + permission);
    return access.run(actor, "crm.customers." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面把蓝图加粗的两句写进了界面可见的文案：
   * 「只给建议不自动执行」与「必须可拆分」。
   */
  @GetMapping("/capabilities")
  public CrmCustomerMergeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> merge.capabilities());
  }

  // ================================================================ 候选（只给建议）

  /**
   * 候选匹配队列：真分页 + 真筛选。
   *
   * <p>🔴 这里返回的**全是建议**，一条都还没合并。
   */
  @GetMapping("/candidates")
  public CrmCustomerMergeCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String matchType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> merge.candidatePage(status, matchType, keyword, page, size));
  }

  /**
   * 候选详情：证据全文 + 两侧联系方式。
   *
   * @param revealContact 是否显式要求明文联系方式（还要**另有**
   *     {@code crm.customers.pii.read} 权限才给；没有就是 403，不是悄悄退回脱敏）
   */
  @GetMapping("/candidates/{id}")
  public CrmCustomerMergeCandidateDetail candidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean revealContact) {
    return call(auth, "read", a -> merge.candidateDetail(id, revealContact, a));
  }

  /**
   * 生成候选匹配（<b>只给建议，不会合并任何客户</b>）。
   *
   * <p>蓝图加粗句「自动合并风险高 ⇒ 只给建议不自动执行」在这一行落地：
   * 本端点**只写候选表**，逻辑客户与映射表一行都不写。
   */
  @PostMapping("/scan")
  public CrmCustomerMergeScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) CrmCustomerMergeScanInput input) {
    return call(auth, "update", a -> merge.scan(input, a.userId(), auth));
  }

  /**
   * 人工确认候选 ⇒ 合并。
   *
   * <p>这是全模块<b>唯一</b>能写逻辑客户与映射的入口。理由必填。
   */
  @PostMapping("/candidates/{id}/confirm")
  public CrmCustomerMergeLogicalDetail confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmCustomerMergeConfirmInput input) {
    return call(auth, "update", a -> merge.confirm(id, input, a));
  }

  /** 拒绝候选 —— 理由必填（拒绝也是决定，事后要能回答「为什么不是同一个人」）。 */
  @PostMapping("/candidates/{id}/reject")
  public CrmCustomerMergeCandidateRow reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmCustomerMergeRejectInput input) {
    return call(auth, "update", a -> merge.reject(id, input, a));
  }

  // ================================================================ 逻辑客户

  /** 逻辑客户列表：真分页。 */
  @GetMapping("/logical")
  public CrmCustomerMergeLogicalPage logical(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> merge.logicalPage(page, size));
  }

  /** 逻辑客户详情：成员 + 按币种分开的金额 + 操作记录。 */
  @GetMapping("/logical/{id}")
  public CrmCustomerMergeLogicalDetail logicalDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", a -> merge.logicalDetail(id));
  }

  /**
   * 拆分：把某一个成员从逻辑客户里拆出去。
   *
   * <p>🔴 蓝图加粗句「必须可拆分」在这一行落地：映射行**不删**，只标 SPLIT 并留痕；
   * 来源客户行本身**一个字都没被改过**，拆开后原样回到「按来源店铺的客户行」。
   */
  @PostMapping("/logical/{id}/split")
  public CrmCustomerMergeLogicalDetail split(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CrmCustomerMergeSplitInput input) {
    return call(auth, "update", a -> merge.split(id, input, a));
  }

  // ================================================================ 操作记录 / 运行台账

  /** 操作记录（合并 / 拆分 / 拒绝候选）：真分页。 */
  @GetMapping("/operations")
  public CrmCustomerMergeOperationPage operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID logicalCustomerId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> merge.operationPage(logicalCustomerId, page, size));
  }

  /** 候选生成运行台账（真分页）：扫了多少、产出多少、被砍掉多少。 */
  @GetMapping("/runs")
  public CrmCustomerMergeRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> merge.runPage(page, size));
  }
}
