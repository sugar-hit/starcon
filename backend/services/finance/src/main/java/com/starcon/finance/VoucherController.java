package com.starcon.finance;

import static com.starcon.finance.VoucherModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 12.5.2.1 凭证管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/vouchers}</b>：与 12.2.2.2 的 {@code /finance/payment-executions}、
 * 12.4.2.1 的 {@code /finance/cost-collections} 各自独立前缀 —— 「哪个模块的哪个端点」必须说得清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 <b>没有 DELETE 端点</b>：凭证是会计凭据，错了只能作废（草稿）或红冲（已过账）。
 * 与 finance 其它模块「API 出口刻意不导出 DELETE」的约定一致。
 */
@RestController
@RequestMapping("/finance/vouchers")
public class VoucherController {

  private final AccessService access;
  private final VoucherService vouchers;

  public VoucherController(AccessService access, VoucherService vouchers) {
    this.access = access;
    this.vouchers = vouchers;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与筛选项

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceVoucherCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.voucher", "read", actor -> vouchers.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceVoucherOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.voucher", "read", actor -> vouchers.options());
  }

  // ================================================================ 凭证

  /** 凭证列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping
  public FinanceVoucherPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID periodId,
      @RequestParam(required = false) LocalDate dateFrom,
      @RequestParam(required = false) LocalDate dateTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.voucher",
        "read",
        actor ->
            vouchers.page(
                new VoucherQuery(
                    enumOf(status, VoucherStatus.class, "凭证状态"),
                    enumOf(sourceType, VoucherSourceType.class, "来源类型"),
                    currency,
                    periodId,
                    dateFrom,
                    dateTo,
                    keyword,
                    page,
                    size)));
  }

  /** 凭证详情：凭证 + 分录行 + 审计留痕。 */
  @GetMapping("/{id}")
  public FinanceVoucherDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.voucher", "read", actor -> vouchers.detail(id));
  }

  /** 手工录入一张凭证（草稿）。 */
  @PostMapping
  public FinanceVoucherResult create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceVoucherInput input) {
    return call(auth, "finance.voucher", "create", actor -> vouchers.create(input, actor));
  }

  /** 修改一张**草稿**凭证（乐观并发：必须带 expected_version）。 */
  @PutMapping("/{id}")
  public FinanceVoucherResult update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceVoucherInput input) {
    return call(auth, "finance.voucher", "update", actor -> vouchers.update(id, input, actor));
  }

  /**
   * 过账。
   *
   * <p>🔴 服务端在**锁住凭证头**的事务里校验四条（至少两行 / 金额有效 / 期间开放 / 借贷平衡）
   * 再落 POSTED；数据层另有一道触发器挡住「已过账后改行」。
   */
  @PostMapping("/{id}/post")
  public FinanceVoucherResult post(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) FinanceVoucherPostInput input) {
    return call(auth, "finance.voucher", "update", actor -> vouchers.post(id, input, actor));
  }

  /**
   * 红冲一张**已过账**的凭证。
   *
   * <p>🔴 生成一张**反向凭证**（借贷方向对调、金额相同），原凭证的原始事实**一字不改**。
   */
  @PostMapping("/{id}/reverse")
  public FinanceVoucherResult reverse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceVoucherReverseInput input) {
    return call(auth, "finance.voucher", "update", actor -> vouchers.reverse(id, input, actor));
  }

  /** 作废一张**草稿**凭证。 */
  @PostMapping("/{id}/void")
  public FinanceVoucherResult voidVoucher(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceVoucherVoidInput input) {
    return call(
        auth, "finance.voucher", "update", actor -> vouchers.voidVoucher(id, input, actor));
  }

  /**
   * 由业务事件**自动生成**一张凭证（草稿）。
   *
   * <p>🔴 科目来自租户自己登记的**业务事件映射**；没有登记 ⇒ 如实报错，绝不内置科目常量。
   */
  @PostMapping("/generate")
  public FinanceVoucherResult generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceVoucherGenerateInput input) {
    var actor = access.require(auth, "finance.voucher.create");
    return access.run(
        actor, "finance.voucher.create", () -> vouchers.generate(input, actor, auth));
  }

  /**
   * 导出标准凭证文件。
   *
   * <p>🔴 只导**已过账**的凭证（草稿不是账）；返回的是**真文件内容**（CSV 带 UTF-8 BOM）。
   */
  @GetMapping("/export")
  public ResponseEntity<String> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "CSV") String format,
      @RequestParam(required = false) UUID periodId,
      @RequestParam(required = false) LocalDate dateFrom,
      @RequestParam(required = false) LocalDate dateTo,
      @RequestParam(required = false) String status) {
    var actor = access.require(auth, "finance.voucher.read");
    var file =
        access.run(
            actor,
            "finance.voucher.read",
            () ->
                vouchers.exportFile(
                    new FinanceVoucherExportInput(format, periodId, dateFrom, dateTo, status),
                    actor));
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
        .header("Content-Type", file.contentType())
        .body(file.content());
  }

  // ================================================================ 会计期间

  /** 会计期间列表（真实数据）。 */
  @GetMapping("/periods")
  public List<FinanceVoucherPeriodRow> periods(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.voucher", "read", actor -> vouchers.periods());
  }

  /** 登记一个会计期间。 */
  @PostMapping("/periods")
  public FinanceVoucherPeriodRow createPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceVoucherPeriodInput input) {
    return call(
        auth, "finance.voucher", "create", actor -> vouchers.createPeriod(input, actor));
  }

  /**
   * 关闭一个会计期间。
   *
   * <p>⚠️ <b>只能关、不能重开</b>：重开已关期间是高风险操作且必须审批留痕（属 12.5.1.2，未交付）。
   */
  @PostMapping("/periods/{id}/close")
  public FinanceVoucherPeriodRow closePeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceVoucherPeriodCloseInput input) {
    return call(
        auth, "finance.voucher", "update", actor -> vouchers.closePeriod(id, input, actor));
  }

  // ================================================================ 业务事件映射

  /** 业务事件 → 借贷科目映射列表。 */
  @GetMapping("/mappings")
  public List<FinanceVoucherMappingRow> mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.voucher", "read", actor -> vouchers.mappings());
  }

  /** 登记一条映射（系统不预置任何一行）。 */
  @PostMapping("/mappings")
  public FinanceVoucherMappingRow createMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceVoucherMappingInput input) {
    return call(
        auth, "finance.voucher", "create", actor -> vouchers.createMapping(input, actor));
  }

  /** 修改一条映射。 */
  @PutMapping("/mappings/{id}")
  public FinanceVoucherMappingRow updateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceVoucherMappingInput input) {
    return call(
        auth, "finance.voucher", "update", actor -> vouchers.updateMapping(id, input, actor));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
