package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **12.5.2.1 凭证管理 / 12.5.2.2 财务报表**（finance 服务，端口 8087）的**跨服务只读端口**。
 *
 * <p>蓝图 18.2.1.1 的关系写明「上游 = 12.5.2.1 凭证」—— 要导出的凭证**本身就住在**
 * finance 的凭证表里（{@code finance_voucher} / {@code finance_voucher_line}）。
 * 本端口只读它，不抄它。
 *
 * <p>⚠️ **本服务不建凭证表、不抄凭证**（与 reporting 的 {@code ReportingCatalogPort}、
 * platform 的 {@code CatalogTranslationPort} 同一纪律）：凭证是会计事实，抄一份就会漂移 ——
 * finance 那边改了凭证，本服务这边还留着旧的，导出的法定凭证会与真实账对不上。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（本服务 RLS + 上游 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就 503</b>，不返回空集合冒充「上游没有凭证」。
 *
 * <p>🔴 **只读、只认对象形状的端点**：本端口刻意只调**返回 JSON 对象**的端点
 * （{@code /options} / {@code /vouchers} / {@code /vouchers/{id}} / {@code /trial-balance}），
 * 不调返回裸数组的端点 —— {@link PlatformUpstreamClient} 只按 {@code Class<T>} 反序列化，
 * 裸数组会被 Jackson 拆成 {@code List<Object>}，字段全丢。
 */
@Service
public class FinanceVoucherPort {

  private final PlatformUpstreamClient client;

  public FinanceVoucherPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.client = PlatformUpstreamClient.to("finance 服务", url);
  }

  // ================================================================ 筛选项与会计期间

  /** 一个会计期间（id + 期间编码）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PeriodRef(String id, String name) {}

  /** 上游筛选项（真实数据，不是写死的词表）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OptionsRow(
      List<String> currencies,
      List<String> statuses,
      List<String> sourceTypes,
      List<PeriodRef> periods,
      List<String> subjectCodes) {}

  /** 拉筛选项（含会计期间清单 —— 余额核对要按期间区间取本地余额）。 */
  public OptionsRow options(String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/finance/vouchers/options"), bearer, OptionsRow.class);
  }

  // ================================================================ 凭证

  /** 上游一张凭证（导出文件用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VoucherRow(
      UUID id,
      String voucherNo,
      String periodCode,
      LocalDate voucherDate,
      String currency,
      String status,
      String description,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      int lineCount) {}

  /** 上游一页凭证。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VoucherPage(
      List<VoucherRow> items, int page, int size, long total, long draftCount, long postedCount) {}

  /**
   * 拉一页凭证。
   *
   * @param status 凭证状态（导出只取 {@code POSTED} —— 草稿不是账）
   */
  public VoucherPage vouchers(
      String status, LocalDate dateFrom, LocalDate dateTo, String currency, int page, int size,
      String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/finance/vouchers").queryParam("page", Math.max(page, 0));
          uriBuilder.queryParam("size", Math.max(size, 1));
          if (status != null && !status.isBlank()) uriBuilder.queryParam("status", status);
          if (dateFrom != null) uriBuilder.queryParam("dateFrom", dateFrom.toString());
          if (dateTo != null) uriBuilder.queryParam("dateTo", dateTo.toString());
          if (currency != null && !currency.isBlank()) uriBuilder.queryParam("currency", currency);
          return uriBuilder;
        },
        bearer,
        VoucherPage.class);
  }

  /** 上游一条分录行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LineRow(
      UUID id,
      int lineNo,
      String lineType,
      String subjectCode,
      String subjectName,
      String summary,
      BigDecimal amount,
      String currency,
      BigDecimal quantity,
      String auxiliaryType,
      String auxiliaryName) {}

  /** 上游一张凭证的详情（凭证 + 分录行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VoucherDetail(VoucherRow voucher, List<LineRow> lines) {}

  /** 拉一张凭证的详情（导出文件要逐行写外部科目编码 ⇒ 必须逐行取）。 */
  public VoucherDetail voucher(UUID id, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/finance/vouchers/" + id.toString()),
        bearer,
        VoucherDetail.class);
  }

  // ================================================================ 试算平衡表（本地余额）

  /** 上游试算平衡表的一行（**按科目余额方向取正负**的期末余额）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportRow(
      String subjectCode,
      String subjectName,
      String currency,
      String balanceDirection,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      boolean totalRow) {}

  /** 上游一个币种的分组（**不同币种绝不合并**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportGroup(String currency, List<ReportRow> rows) {}

  /** 上游一张报表的结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportResult(
      String reportType,
      String currencyScope,
      List<ReportGroup> currencyGroups,
      long voucherCount,
      long lineCount,
      long draftExcludedCount,
      long reversedVoucherCount) {}

  /**
   * 拉试算平衡表（余额核对的**本地一侧**）。
   *
   * <p>🔴 只取期末余额（{@code closingBalance}），且它是**按科目余额方向取正负**的口径 ——
   * 与导入的外部余额归一化后的口径一致，两边才能直接相减。
   */
  public ReportResult trialBalance(
      UUID periodFromId, UUID periodToId, String currency, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/finance/financial-reports/trial-balance")
              .queryParam("periodFromId", periodFromId.toString())
              .queryParam("periodToId", periodToId.toString());
          if (currency != null && !currency.isBlank()) uriBuilder.queryParam("currency", currency);
          return uriBuilder;
        },
        bearer,
        ReportResult.class);
  }
}
