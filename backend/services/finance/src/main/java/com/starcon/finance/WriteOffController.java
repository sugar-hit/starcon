package com.starcon.finance;

import static com.starcon.finance.WriteOffModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.2.1.2 核销的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/write-off}</b>：与同服务的 12.1.1.1（{@code /finance/receivables}）、
 * 12.2.1.1（{@code /finance/receipts}）刻意不共用一个前缀 —— 核销是**跨两张单据的动作**
 * （收款 ↔ 应收明细行），挤在任何一个前缀下都会让「哪个模块的哪个端点」说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.writeoff.read / .create / .update}：登记核销、撤销核销、
 * 释放预收挂账都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/write-off")
public class WriteOffController {

  private final AccessService access;
  private final WriteOffService writeOffs;

  public WriteOffController(AccessService access, WriteOffService writeOffs) {
    this.access = access;
    this.writeOffs = writeOffs;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.writeoff." + permission);
    return access.run(actor, "finance.writeoff." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceWriteOffCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> writeOffs.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceWriteOffOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> writeOffs.options());
  }

  // ================================================================ 列表 / 详情

  /** 核销记录列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping
  public FinanceWriteOffPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            writeOffs.page(
                new WriteOffQuery(
                    enumOf(status, WriteOffStatus.class, "核销状态"),
                    enumOf(mode, WriteOffMode.class, "核销方式"),
                    currency,
                    receiptId,
                    keyword,
                    page,
                    size)));
  }

  /** 核销记录详情：单头 + 分配明细 + 事件流。 */
  @GetMapping("/{id}")
  public FinanceWriteOffDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> writeOffs.detail(id));
  }

  // ================================================================ 待核销视图

  /**
   * 待核销视图：可核销的收款单 + 可核销的应收明细行。
   *
   * <p>核销是「一笔收款 ↔ 若干条应收明细行」的关系 ⇒ **两侧都要能选**。
   */
  @GetMapping("/pending")
  public FinanceWriteOffPending pending(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> writeOffs.pending());
  }

  // ================================================================ 预收余额

  /**
   * 预收余额（按币种分开）。
   *
   * <p>⚠️ 两个口径互不重复计数：本模块挂账的预收 + 12.2.1.1 预收标记收款单的未核销余额。
   */
  @GetMapping("/advance-balance")
  public FinanceWriteOffAdvanceBalance advanceBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> writeOffs.advanceBalance());
  }

  /** 预收挂账行列表。 */
  @GetMapping("/advance")
  public List<FinanceWriteOffAdvanceRow> advanceRows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency) {
    return call(auth, "read", actor -> writeOffs.advanceRows(status, currency));
  }

  // ================================================================ 自动匹配

  /**
   * 自动匹配的**候选**（只读，不落库）：先看清「凭什么匹配上」再决定要不要核销。
   *
   * <p>⚠️ 三个条件缺一不可：订单号完全相等 + 明细行未核销余额等于目标金额 +
   * 应收单确认日期落在比对日期的容差窗口内。
   */
  @GetMapping("/auto-match/candidates")
  public List<FinanceWriteOffMatchCandidate> autoMatchCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID receiptId,
      @RequestParam String orderNo,
      @RequestParam(required = false) BigDecimal amount,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate matchDate,
      @RequestParam(required = false) Integer dateToleranceDays) {
    return call(
        auth,
        "read",
        actor -> writeOffs.autoMatchCandidates(receiptId, orderNo, amount, matchDate, dateToleranceDays));
  }

  // ================================================================ 核销

  /**
   * 人工指定核销。
   *
   * <p>⚠️ 分配里可以混两种类型：{@code SETTLEMENT}（核销到应收**明细行**）与
   * {@code ADVANCE}（预收挂账）。全是 ADVANCE 时方式记成「预收挂账」。
   */
  @PostMapping
  public FinanceWriteOffDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceWriteOffInput input) {
    return call(auth, "create", actor -> writeOffs.create(input, actor));
  }

  /**
   * 自动匹配核销。
   *
   * <p>⚠️ 命中 0 条就整体失败（400）——「自动匹配」如果总能匹配上，它就只是按金额排序而已。
   */
  @PostMapping("/auto-match")
  public FinanceWriteOffAutoMatchResult autoMatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceWriteOffAutoMatchInput input) {
    return call(auth, "create", actor -> writeOffs.autoMatch(input, actor));
  }

  // ================================================================ 核销撤销

  /**
   * 核销撤销：把明细行的已核销金额**真的退回去**。
   *
   * <p>⚠️ 应收单**整单核销完成**（单头 = SETTLED）后是 12.1.1.1 写死的终态 ⇒ 那时撤销会被拒，
   * 替代路径是「作废 + 新单」（红冲），属 12.1.1.3 的域。
   */
  @PostMapping("/{id}/reverse")
  public FinanceWriteOffDetail reverse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceWriteOffReverseInput input) {
    return call(auth, "update", actor -> writeOffs.reverse(id, input, actor));
  }

  // ================================================================ 释放预收挂账

  /**
   * 释放一笔预收挂账：这笔钱不再专用，可以拿去核销应收。
   *
   * <p>⚠️ 挂着的钱是**专用**的（核销时按「未核销余额 − 未释放挂账额」封顶），
   * 所以要动用它必须先显式释放 —— 不静默改变口径。
   */
  @PostMapping("/advance/{ledgerId}/release")
  public FinanceWriteOffAdvanceRow releaseAdvance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ledgerId) {
    return call(auth, "update", actor -> writeOffs.releaseAdvance(ledgerId, actor));
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
