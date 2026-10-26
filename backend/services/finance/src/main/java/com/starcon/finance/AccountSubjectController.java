package com.starcon.finance;

import static com.starcon.finance.AccountSubjectModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 12.5.1.1 会计科目的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/account-subjects}</b>：首 token 与既有 finance 端点
 * （{@code receivables} / {@code settlement} / {@code transactions} / {@code bad-debt} …）全不相同，
 * 免得「科目」被别的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.account_subject.read / .create / .update}：
 * 建科目、改科目、启用映射规则都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/account-subjects")
public class AccountSubjectController {

  private final AccessService access;
  private final AccountSubjectService subjects;

  public AccountSubjectController(AccessService access, AccountSubjectService subjects) {
    this.access = access;
    this.subjects = subjects;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.account_subject." + permission);
    return access.run(actor, "finance.account_subject." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceAccountSubjectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> subjects.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceAccountSubjectOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> subjects.options());
  }

  // ================================================================ 模板

  /** 系统科目模板目录（模板只是导入源，不是约束）。 */
  @GetMapping("/templates")
  public FinanceAccountSubjectTemplateCatalog templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> subjects.templates());
  }

  /**
   * 从模板导入科目到指定账簿。
   *
   * <p>⚠️ **已存在的编码被跳过而不是覆盖** —— 用户改过的科目不能被模板冲掉；
   * 被跳过的编码逐个报出来。
   */
  @PostMapping("/templates/apply")
  public FinanceAccountSubjectTemplateApplyResult applyTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceAccountSubjectTemplateApplyInput input) {
    return call(auth, "create", actor -> subjects.applyTemplate(input, actor));
  }

  // ================================================================ 映射规则

  /**
   * 业务事件 → 科目的映射规则清单：真分页 + 真筛选。
   *
   * <p>⚠️ 路径刻意排在 {@code /{id}} **之前** —— 字面量段先判别，
   * 否则 {@code mappings} 会被 uuid 分支当成一个 id。
   */
  @GetMapping("/mappings")
  public FinanceAccountSubjectMappingPage mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountingBookId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            subjects.mappings(
                new AccountSubjectMappingQuery(
                    accountingBookId,
                    enumOf(status, AccountMappingStatus.class, "映射规则状态"),
                    keyword,
                    page,
                    size)));
  }

  /** 新建映射规则（头 + 分录行）。 */
  @PostMapping("/mappings")
  public FinanceAccountSubjectMappingRow createMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceAccountSubjectMappingInput input) {
    return call(auth, "create", actor -> subjects.createMapping(input, actor));
  }

  /** 映射规则详情（头 + 分录行）。 */
  @GetMapping("/mappings/{id}")
  public FinanceAccountSubjectMappingRow mappingDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> subjects.mappingDetail(id));
  }

  /** 改映射规则（分录行全量替换；启用态要先停用）。 */
  @PutMapping("/mappings/{id}")
  public FinanceAccountSubjectMappingRow updateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountSubjectMappingUpdateInput input) {
    return call(auth, "update", actor -> subjects.updateMapping(id, input, actor));
  }

  /** 启用映射规则（借贷齐备才让启用）。 */
  @PostMapping("/mappings/{id}/activate")
  public FinanceAccountSubjectMappingRow activateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> subjects.activateMapping(id, actor));
  }

  /** 停用映射规则。 */
  @PostMapping("/mappings/{id}/deactivate")
  public FinanceAccountSubjectMappingRow deactivateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> subjects.deactivateMapping(id, actor));
  }

  // ================================================================ 按编码查

  /**
   * 按科目编码查 —— **下游 12.5.2.1 凭证的集成点**。
   *
   * <p>编码在本租户内唯一 ⇒ 命中时至多一条。没命中时如实返回 {@code found=false}。
   */
  @GetMapping("/by-code")
  public FinanceAccountSubjectByCodeResult byCode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String subjectCode) {
    return call(auth, "read", actor -> subjects.byCode(subjectCode));
  }

  // ================================================================ 科目

  /** 科目列表：真分页 + 真筛选 + 分类汇总（汇总覆盖整个筛选结果集）。 */
  @GetMapping
  public FinanceAccountSubjectPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountingBookId,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String balanceDirection,
      @RequestParam(required = false) String taxTreatment,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean detail,
      @RequestParam(required = false) String parentSubjectCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            subjects.page(
                new AccountSubjectQuery(
                    accountingBookId,
                    enumOf(category, AccountSubjectCategory.class, "科目类别"),
                    enumOf(balanceDirection, AccountBalanceDirection.class, "余额方向"),
                    enumOf(taxTreatment, AccountTaxTreatment.class, "税务属性"),
                    enumOf(status, AccountSubjectStatus.class, "科目状态"),
                    detail,
                    parentSubjectCode,
                    keyword,
                    page,
                    size)));
  }

  /** 新增科目。 */
  @PostMapping
  public FinanceAccountSubjectRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceAccountSubjectInput input) {
    return call(auth, "create", actor -> subjects.create(input, actor));
  }

  /** 科目详情：科目 + 变更留痕 + 引用它的映射规则。 */
  @GetMapping("/{id}")
  public FinanceAccountSubjectDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> subjects.detail(id));
  }

  /** 改科目（⚠️ 编码不可改 —— 下游凭证按编码软引用它）。 */
  @PutMapping("/{id}")
  public FinanceAccountSubjectRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountSubjectUpdateInput input) {
    return call(auth, "update", actor -> subjects.update(id, input, actor));
  }

  /** 停用科目（停用不是删除：历史凭证仍要能引用到它）。 */
  @PostMapping("/{id}/disable")
  public FinanceAccountSubjectRow disable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> subjects.disable(id, actor));
  }

  /** 重新启用科目。 */
  @PostMapping("/{id}/enable")
  public FinanceAccountSubjectRow enable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> subjects.enable(id, actor));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
