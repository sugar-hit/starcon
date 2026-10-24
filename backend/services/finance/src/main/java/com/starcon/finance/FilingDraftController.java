package com.starcon.finance;

import static com.starcon.finance.FilingDraftModels.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.6.1.2 VAT/IOSS 申报数据的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/filing-draft}</b>：与同服务的 12.6.1.1
 * （{@code /finance/tax-rates}）、12.5.2.1（{@code /finance/vouchers}）刻意不共用前缀 ——
 * 首 token 也刻意不同（{@code filing-draft} ≠ {@code tax-rates} ≠ {@code vouchers}），
 * 免得「申报底稿」被别的模块的前缀吃掉。
 *
 * <p>🔴 <b>本控制器不做「向税务机构提交申报」这个动作</b>，也不假装提交成功。
 * 它只产出底稿、汇总报表与**真文件**导出（CSV / JSON 的真实字节，不是占位文件）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.filing_draft.read / .create}：生成底稿不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/filing-draft")
public class FilingDraftController {

  private final AccessService access;
  private final FilingDraftService filingDrafts;

  public FilingDraftController(AccessService access, FilingDraftService filingDrafts) {
    this.access = access;
    this.filingDrafts = filingDrafts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.filing_draft." + permission);
    return access.run(actor, "finance.filing_draft." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（channel / trade / catalog）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.filing_draft." + permission);
    return access.run(
        actor, "finance.filing_draft." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些上游真接通、哪些读不到、职责怎么切、哪些还没交付、归谁。
   *
   * <p>🔴 里面有一条是**产品边界**：本模块不提交申报。
   */
  @GetMapping("/capabilities")
  public FinanceFilingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> filingDrafts.capabilities());
  }

  // ================================================================ 选项

  /**
   * 筛选项：申报线 / 申报周期 / 状态 / 币种（来自真实底稿）+ 辖区选项。
   *
   * <p>辖区选项**实时**读 channel 的受控辖区码表，读不到一律 503（如实说读不到）。
   */
  @GetMapping("/options")
  public FinanceFilingOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> filingDrafts.options(bearer));
  }

  // ================================================================ 底稿

  /** 底稿列表（真分页 + 真筛选）。 */
  @GetMapping("/drafts")
  public FinanceFilingDraftPage drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String taxType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) java.time.LocalDate periodFrom,
      @RequestParam(required = false) java.time.LocalDate periodTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            filingDrafts.drafts(
                jurisdictionCode, taxType, status, periodFrom, periodTo, keyword, page, size));
  }

  /**
   * 生成一份申报底稿（**取数 + 落库 + 返回详情**）。
   *
   * <p>⚠️ 辖区码**实时**校验（受控码表）；税率**按生效时间取**（命中不到直接报错）；
   * 订单数据读不到 ⇒ 整个取数失败（503），**不做成「0 张订单的底稿」**。
   */
  @PostMapping("/drafts")
  public FinanceFilingDraftDetail generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceFilingDraftInput input) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> filingDrafts.generate(input, actor, bearer));
  }

  /** 底稿详情（头 + 汇总行 + 数据来源可用性 + 口径）。 */
  @GetMapping("/drafts/{id}")
  public FinanceFilingDraftDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> filingDrafts.detail(id));
  }

  /** 汇总报表（**与底稿行是同一份数据**的另一种呈现，按币种分组）。 */
  @GetMapping("/drafts/{id}/summary")
  public FinanceFilingSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> filingDrafts.summary(id));
  }

  // ================================================================ 导出

  /**
   * 导出申报底稿（**真文件**：CSV / JSON 的真实字节，不是占位文件）。
   *
   * <p>🔴 导出是**取证动作** ⇒ 每次导出都写一条留痕（行数 / 字节数 / 内容 sha-256）。
   */
  @GetMapping("/drafts/{id}/export")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "CSV") String format) {
    var actor = access.require(auth, "finance.filing_draft.read");
    return access.run(
        actor,
        "finance.filing_draft.read",
        () -> {
          var result = filingDrafts.export(id, format, actor);
          var headers = new HttpHeaders();
          headers.setContentType(MediaType.parseMediaType(result.contentType()));
          headers.setContentDisposition(
              ContentDisposition.attachment()
                  .filename(result.fileName(), StandardCharsets.UTF_8)
                  .build());
          headers.set("Cache-Control", "no-store");
          headers.set("X-Filing-Export-Id", result.exportId().toString());
          headers.set("X-Filing-Export-Row-Count", String.valueOf(result.rowCount()));
          headers.set("X-Filing-Export-Content-Hash", result.contentHash());
          return new ResponseEntity<>(result.content(), headers, org.springframework.http.HttpStatus.OK);
        });
  }

  /** 导出留痕列表（真分页 + 真筛选）。 */
  @GetMapping("/exports")
  public FinanceFilingExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID draftId,
      @RequestParam(required = false) String format,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> filingDrafts.exports(draftId, format, page, size));
  }
}
