package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.1.2 SKU 利润的**拉取适配器**：把上游的退款事实读成 SKU 级事实，落到本模块自己的
 * 投影表（{@code reporting_sku_profit_refund_fact}）。
 *
 * <p>⚠️ **本模块是「退款损失」这笔钱的唯一写入所有者**：
 * 13.4.1.1 的 {@code reporting_profit_fact} 里压根没有退款（它的职责句与四路上游都不含退款），
 * 本模块也不扩那张表的 {@code component} 值域（扩了会让 13.4.1.1 的毛利口径**静默漏掉退款**）。
 *
 * <p>⚠️ **口径三件套**：
 *
 * <ol>
 *   <li><b>什么算损失</b>：只有 {@code application_status = 'SUCCEEDED'} 的退款申请单
 *       （钱真的退出去了）。申请中 / 审批中 / 执行中都**不算**。</li>
 *   <li><b>算在哪一天</b>：退款执行结果被记录的时刻（{@code outcomeRecordedAt}）——
 *       钱退出去的那一天，而不是申请日。</li>
 *   <li><b>挂到哪个 SKU</b>：退款申请单挂在**订单**上（不挂 SKU）⇒ 用售后单的**涉及行**
 *       才有 SKU。**只有当退款金额与该售后单涉及行的申请金额之和逐字相等**时才按行归属
 *       （那是同一笔钱的行级拆分，不是分摊）；**对不上就不挂到任何 SKU** ——
 *       部分退款 / 上游改了金额时摊下去就是编造。</li>
 * </ol>
 *
 * <p>⚠️ **不重算费率、不重算分摊**：本类只**读**上游写好的金额与行级拆分。
 */
@Service
public class ReportingSkuProfitRefreshService {

  /** 一次刷新里最多取多少张售后单的明细（**逐单取**，有上限）。 */
  static final int MAX_CASE_FETCHES = 200;

  /** 金额比较的标度（上游金额是 numeric(19,4)）。 */
  private static final int MONEY_SCALE = 4;

  /** 上游退款申请单里「钱真的退出去了」的状态。 */
  static final String SUCCEEDED = "SUCCEEDED";

  private final ReportingSkuProfitPort refunds;
  private final ReportingSalesTrendPort salesTrend;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingSkuProfitRefreshService(
      ReportingSkuProfitPort refunds,
      ReportingSalesTrendPort salesTrend,
      DSLContext sql,
      TenantTransactions tenants) {
    this.refunds = refunds;
    this.salesTrend = salesTrend;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新退款损失事实。
   *
   * @param from 窗口起
   * @param to 窗口止
   */
  public ReportingSkuProfitModels.SkuProfitRefreshResult refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    // 订单 → 店铺（平台单回挂）：用来给退款事实标经营模式。
    var shopByOrder = fetchShopByOrder(bearer);
    // 店铺 → 经营模式（**读 DB**：上游那一份已投影成档案表）。
    var modeByShop = shopModes(tenantId);

    var facts = new ArrayList<RefundFact>();
    var fetched = 0;
    var succeeded = 0;
    var unattributable = 0;
    var truncated = false;
    var caseFetches = 0;

    for (int page = 0; page < ReportingSkuProfitPort.MAX_PAGES; page++) {
      var pageData = refunds.refundApplications(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        if (row == null || row.head() == null) continue;
        fetched++;
        var head = row.head();
        if (!SUCCEEDED.equals(head.applicationStatus())) {
          // ⚠️ **申请了不等于退了**：只有成功执行的退款才是损失。
          continue;
        }
        succeeded++;
        if (head.refundAmount() == null || head.caseId() == null) {
          unattributable++;
          continue;
        }
        var currency = currency(head.currency());
        if (currency == null) {
          unattributable++;
          continue;
        }
        if (caseFetches >= MAX_CASE_FETCHES) {
          truncated = true;
          unattributable++;
          continue;
        }
        caseFetches++;
        var detail = refunds.aftersalesCase(head.caseId(), bearer);
        if (detail == null || detail.lines() == null || detail.lines().isEmpty()) {
          unattributable++;
          continue;
        }

        // ---- 行级拆分：金额必须逐字对得上，否则不归属 ----
        var lines = new ArrayList<LineAmount>();
        var lineTotal = BigDecimal.ZERO;
        for (var line : detail.lines()) {
          if (line == null || line.requestedAmount() == null) continue;
          var skuCode = line.skuCode() == null ? "" : line.skuCode().trim();
          if (skuCode.isBlank()) continue;
          var lineCurrency = currency(line.currency());
          if (lineCurrency == null || !lineCurrency.equals(currency)) {
            // ⚠️ **币种对不上就不能加**（跨币种相加是仓库铁律）。
            unattributable++;
            continue;
          }
          lines.add(new LineAmount(skuCode, line.requestedAmount(), line.quantity()));
          lineTotal = lineTotal.add(line.requestedAmount());
        }
        var amount = head.refundAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (lines.isEmpty() || lineTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP).compareTo(amount) != 0) {
          // 🔴 **对不上就不摊**：宁可这一格显示「不可归属」，也不要造一个 SKU 出来。
          unattributable++;
          continue;
        }

        var day = lossDay(head);
        if (day == null) {
          // 🔴 **连落桶日期都没有** ⇒ 不落事实（凭空取「今天」是编造）。
          unattributable++;
          continue;
        }
        var shopCode = shopByOrder.get(head.orderNo() == null ? "" : head.orderNo().trim());
        var mode = modeOf(modeByShop, shopCode);
        var totalQty = BigDecimal.ZERO;
        for (var line : lines) {
          totalQty = totalQty.add(line.quantity() == null ? BigDecimal.ZERO : line.quantity());
          facts.add(
              new RefundFact(
                  line.skuCode(),
                  mode,
                  head.orderNo() == null ? "" : head.orderNo().trim(),
                  head.caseNo() == null ? "" : head.caseNo().trim(),
                  head.applicationNo() == null ? "" : head.applicationNo().trim(),
                  day,
                  currency,
                  line.amount(),
                  line.quantity() == null ? BigDecimal.ZERO : line.quantity()));
        }
      }
      if (pageData.rows().size() < ReportingSkuProfitPort.PAGE_SIZE) break;
    }

    var written = writeFacts(tenantId, facts);
    var note = new StringBuilder();
    note.append("退款申请单 ")
        .append(fetched)
        .append(" 张，其中 ")
        .append(succeeded)
        .append(" 张已成功执行（**只有钱真的退出去的才算损失**，申请中 / 审批中 / 执行中都不算）。");
    note.append(" SKU 归属来自售后单的**涉及行**：退款金额与行级申请金额之和逐字相等才按行归属。");
    if (unattributable > 0) {
      note
          .append(" ⚠️ 有 ")
          .append(unattributable)
          .append(" 笔退款**挂不到唯一 SKU**（部分退款 / 售后单没有涉及行 / 币种对不上），")
          .append("未计入任何 SKU —— 摊下去就是编造。");
    }
    if (truncated) {
      note.append(" ⚠️ 售后单明细取到 ").append(MAX_CASE_FETCHES).append(" 张为止，超出的未投影。");
    }
    return new ReportingSkuProfitModels.SkuProfitRefreshResult(
        "trade-refund-applications",
        fetched,
        written,
        unattributable,
        truncated ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  // ==========================================================================
  // 内部
  // ==========================================================================

  /** 一条退款事实（写库前的内存形态）。 */
  private record RefundFact(
      String skuCode,
      String operatingMode,
      String orderRef,
      String caseRef,
      String applicationRef,
      LocalDate periodStart,
      String currency,
      BigDecimal amount,
      BigDecimal quantity) {}

  /** 售后单的一行金额。 */
  private record LineAmount(String skuCode, BigDecimal amount, BigDecimal quantity) {}

  /**
   * 事实 upsert：同一「SKU + 模式 + 订单 + 售后单 + 退款申请单 + 期间 + 币种 + 来源服务 + 上游行」只留一条。
   *
   * <p>⚠️ 维度列全部 {@code NOT NULL DEFAULT ''} ⇒ 冲突目标可以逐列列出，
   * 不用 coalesce 把 NULL 归一（PostgreSQL 的 UNIQUE 把 NULL 视为互不相等）。
   */
  private static final String FACT_UPSERT =
      """
      insert into reporting_sku_profit_refund_fact
        (id,tenant_id,sku_code,operating_mode,order_ref,case_ref,application_ref,
         period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
      values (?,?,?,?,?,?,?,?,?,?,?,?,'trade',?,now())
      on conflict (tenant_id,sku_code,operating_mode,order_ref,case_ref,application_ref,
                   period_start,period_end,currency,source_service,source_reference)
      do update set amount = excluded.amount,
                    quantity = excluded.quantity,
                    updated_at = now()
      """;

  /** 写一批事实（**整批在一个事务里**）。 */
  private int writeFacts(UUID tenantId, List<RefundFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          int written = 0;
          for (var fact : facts) {
            sql.execute(
                FACT_UPSERT,
                Ids.next(),
                tenantId,
                fact.skuCode(),
                fact.operatingMode(),
                fact.orderRef(),
                fact.caseRef(),
                fact.applicationRef(),
                fact.periodStart(),
                fact.periodStart(),
                fact.currency(),
                fact.amount(),
                fact.quantity(),
                "refund-application:" + fact.applicationRef() + "#" + fact.skuCode());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 上游辅助
  // ==========================================================================

  /**
   * 订单号 → 店铺编码（平台单回挂）。
   *
   * <p>⚠️ **销售订单本身没有店铺字段** —— 唯一来源是 trade 的平台单。挂不上的订单归「未归属」。
   */
  private Map<String, String> fetchShopByOrder(String bearer) {
    var out = new HashMap<String, String>();
    for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
      var pageData = salesTrend.platformOrders(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        if (row == null || row.shopCode() == null || row.shopCode().isBlank()) continue;
        if (row.orderNo() == null || row.orderNo().isBlank()) continue;
        out.putIfAbsent(row.orderNo().trim(), row.shopCode().trim());
      }
      if (pageData.rows().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
    }
    return out;
  }

  /** 店铺 → 经营模式（**读 DB**，不是上游 —— 上游那一份已投影成档案表）。 */
  private Map<String, String> shopModes(UUID tenantId) {
    var rows =
        tenants.run(
            tenantId,
            () ->
                sql.fetch(
                    "select shop_code, operating_mode from reporting_profit_shop_profile where tenant_id = ?",
                    tenantId));
    var out = new HashMap<String, String>();
    for (var row : rows) {
      out.put(row.get(0, String.class), row.get(1, String.class));
    }
    return out;
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 店铺 → 经营模式（**挂不上店铺就是「未归属」**，不猜）。 */
  private static String modeOf(Map<String, String> modes, String shopCode) {
    if (shopCode == null || shopCode.isBlank()) return "UNKNOWN";
    var mode = modes.get(shopCode);
    return mode == null || mode.isBlank() ? "UNKNOWN" : mode;
  }

  /** 币种（**只收 3 位大写码**；不合规就当「这条没有币种」，不硬塞一个）。 */
  private static String currency(String raw) {
    if (raw == null) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  /**
   * 损失发生的日期（**钱退出去的那一天**）。
   *
   * <p>⚠️ 取「执行结果被记录的时刻」，不是申请日 —— 申请了没退的那段时间钱还在我方账上。
   *
   * @return 落桶日期；**上游连时刻都没给时返回 {@code null}**（那就不落事实 ——
   *     凭空取「今天」当损失日期是编造）
   */
  private static LocalDate lossDay(ReportingSkuProfitPort.RefundApplicationHead head) {
    OffsetDateTime at = head.outcomeRecordedAt();
    if (at == null) at = head.updatedAt();
    if (at == null) at = head.createdAt();
    if (at == null) return null;
    return at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }
}
