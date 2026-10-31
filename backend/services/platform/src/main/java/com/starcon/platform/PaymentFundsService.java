package com.starcon.platform;

import static com.starcon.platform.PaymentFundsModels.*;

import com.starcon.platform.PaymentProviderPort.BalanceQuery;
import com.starcon.platform.PaymentProviderPort.FeeQuery;
import com.starcon.platform.PaymentProviderPort.PaymentCommand;
import com.starcon.platform.PaymentProviderPort.ProviderContext;
import com.starcon.platform.PaymentProviderPort.RateCommand;
import com.starcon.platform.PaymentProviderPort.ReceiptCommand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.2.2.1「支付与资金」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>幂等键是硬约束</b>：每个资金操作都带幂等键落库（{@code UNIQUE(tenant_id,
 *       idempotency_key)}）。同键重复提交 ⇒ <b>原样返回同一结果，绝不第二次调用服务商</b>。
 *       同键但请求内容不同 ⇒ 409（那不是重放，是另一个请求借用了旧键）。
 *   <li><b>超时先查证再重试</b>：发起超时 / 不可达 ⇒ 操作进 <b>UNKNOWN</b>（**不是 FAILED**），
 *       然后<b>自动向服务商查证这一笔</b>。查证说「成功」⇒ <b>不重发</b>（重复付款是资损事故）；
 *       查证说「服务商侧确实没有」⇒ 才允许重试；查证**查不出来**⇒ <b>不允许重试</b>
 *       （查不到 ≠ 没付出去）。
 *   <li><b>没接通就如实说没接通</b>：服务商适配器未接入 ⇒ 状态 NOT_CONNECTED、
 *       HTTP 409、<b>不写成功流水、不写服务商流水号</b>。
 *       ⚠️ 这里与 18.2.1.1 的「一次数据都不写」<b>刻意不同</b>：本模块的操作台账是
 *       <b>请求账本</b>，它记的是「谁在什么时候发起了一次付款请求、服务商侧结论是什么」，
 *       状态字段<b>不可能</b>被误读成成功（NOT_CONNECTED ≠ SUCCEEDED，
 *       provider_notified=false，failure_class=NOT_CONFIGURED）。
 *       而 18.2.1.1 拒绝写的是<b>「凭证已推给金蝶」这种成功痕迹</b> —— 那种记录才是危险的。
 *   <li><b>密钥只有引用</b>：服务商配置里存的是密钥管理器的<b>引用 ID</b>
 *       （照 18.1.1.1 应用凭证的纪律），<b>库里没有任何密钥明文</b>。
 *   <li><b>不抄 finance 的表</b>：上游 12.2 收付款 / 12.7 资金账户只经
 *       {@link FinanceFundPort} 的授权 HTTP 端点<b>只读</b>，不建跨库 FK、不抄余额历史。
 *   <li><b>金额与汇率一律 BigDecimal</b>：库里 {@code NUMERIC(20,6)}，汇率 {@code NUMERIC(24,12)}，
 *       本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class PaymentFundsService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 一次导入最多处理多少行（导入手续费 / 余额时防一手）。 */
  static final int MAX_IMPORT_ROWS = 500;

  private final DSLContext sql;
  private final PaymentProviderRegistry providers;
  private final FinanceFundPort finance;
  private final CatalogRatePort catalog;

  public PaymentFundsService(
      DSLContext sql,
      PaymentProviderRegistry providers,
      FinanceFundPort finance,
      CatalogRatePort catalog) {
    this.sql = sql;
    this.providers = providers;
    this.finance = finance;
    this.catalog = catalog;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 币种：三个大写字母。 */
  private static String currency(String raw, String label) {
    var trimmed = required(raw, label, 3).toUpperCase(Locale.ROOT);
    check(
        trimmed.length() == 3 && trimmed.chars().allMatch(Character::isLetter),
        label + "必须是三个字母的币种代码（如 USD / CNY）");
    return trimmed;
  }

  /**
   * 金额：必须为正，且<b>小数位不得超过 6 位</b>。
   *
   * <p>🔴 <b>超位直接报错，不静默四舍五入</b>：库里是 {@code NUMERIC(20,6)}，
   * 让 PG 去舍入会把「1.2345678 美元」悄悄变成「1.234568 美元」——
   * 钱的事上，悄悄改一位比报错糟得多。
   */
  private static BigDecimal money(BigDecimal raw, String label, boolean required) {
    if (raw == null) {
      if (required) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
      return null;
    }
    check(raw.signum() > 0, label + "必须大于 0");
    check(raw.stripTrailingZeros().scale() <= MONEY_SCALE, label + "最多 " + MONEY_SCALE + " 位小数");
    return raw;
  }

  /** 汇率：必须为正，最多 12 位小数（库列 {@code NUMERIC(24,12)}）。 */
  private static BigDecimal rate(BigDecimal raw, String label) {
    if (raw == null) return null;
    check(raw.signum() > 0, label + "必须大于 0");
    check(raw.stripTrailingZeros().scale() <= 12, label + "最多 12 位小数");
    return raw;
  }

  /** 数量（超时秒数 / 重试次数）。 */
  private static int count(Integer raw, String label, int min, int max) {
    var value = raw == null ? min : raw;
    check(value >= min && value <= max, label + "只能在 " + min + " 到 " + max + " 之间");
    return value;
  }

  /** SHA-256 十六进制摘要。 */
  static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 当前会话绑定的租户（**不**来自请求头）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** **DB 时钟**（与落库的时间列同一个钟域）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  private long count(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  // ================================================================ 能力声明

  /**
   * 18.2.2.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **五个服务商全部如实答「未接入」** —— 本仓没有任何真实的服务商凭据与网络通道。
   * 能力声明把这几格写清楚，「缺在谁身上」才是一条**可查的事实**，而不是一句注释。
   */
  public PaymentFundsCapabilities capabilities() {
    var capabilities =
        List.of(
            new PaymentFundsCapability(
                "18.2.2.1",
                "支付服务商连接档案（PayPal / Stripe / PingPong / 连连 / 万里汇）",
                true,
                "platform_payment_provider 表 + 连接档案端点；记服务商 / 接口地址 / 结算币种 / 启用状态，"
                    + "🔴 **只存密钥的引用**（credential_ref + 其 SHA-256 定位摘要 + 末四位提示），"
                    + "**库里没有任何密钥明文**（照 18.1.1.1 应用凭证的纪律）",
                "",
                ""),
            new PaymentFundsCapability(
                "18.2.2.1",
                "资金操作台账（**幂等键** + 请求摘要 + 状态机）",
                true,
                "platform_payment_operation 表 + 幂等键端点；"
                    + "UNIQUE(tenant_id, idempotency_key) ⇒ 同键重复提交**原样返回同一结果**，"
                    + "**绝不第二次调用服务商**；同键不同请求内容 ⇒ 409",
                "",
                "状态机把 UNKNOWN（结果未知）与 FAILED（确定性失败）分成两个值 —— "
                    + "超时不等于失败，混在一起会让重试跳过查证直接二次付款"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**查证兜底**（超时先查证再重试）",
                true,
                "platform_payment_attempt 表（attempt_kind=SEND / QUERY）+ 发起后自动查证；"
                    + "查证说「成功」⇒ **不重发**；查证说「服务商侧确实没有」⇒ 才允许重试；"
                    + "查证**查不出来**⇒ 不允许重试",
                "",
                "重试端点强制「必须先有一条 QUERY 留痕」—— 没有查证就不许重发"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "资金流水台账（输出里的「资金流水同步」）",
                true,
                "platform_fund_flow 表（**append-only**，触发器钉死）+ 流水台账端点；"
                    + "付款成功 / 收款登记 / 账单导入都会产生真流水行",
                "",
                ""),
            new PaymentFundsCapability(
                "18.2.2.1",
                "余额观测（服务商账单导入）",
                true,
                "platform_payment_balance 表（append-only）+ 导入与查询端点",
                "",
                "服务商直查余额（source=PROVIDER）未接入 ⇒ 该来源恒为空，"
                    + "但**账单导入**这条路径今天就能用"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "手续费明细（服务商账单导入）",
                true,
                "platform_payment_fee 表（append-only）+ 导入与查询端点",
                "",
                "服务商直拉明细（source=SYNC）未接入 ⇒ 该来源恒为空，"
                    + "但**账单导入**这条路径今天就能用"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "汇率查询（参考汇率）",
                true,
                "CatalogRatePort（catalog 8083 的 5.2.2.1 汇率事实）+ platform_payment_rate 表"
                    + "（append-only）+ 查询端点；**真读上游**并把观测值落库",
                "",
                "反方向推导出来的汇率会打 inverted=true —— 页面必须让人看见它是推导值"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "上游只读连接（12.2 收付款 / 12.7 资金账户）",
                true,
                "FinanceFundPort（finance 8087）只读端口；**只转发调用方自己的 bearer**、不传租户号、"
                    + "不建跨库 FK、不抄余额历史；取不到一律 503（是「读不到」不是「没有数据」）",
                "",
                "🔴 **只读**：本模块**不回写** finance 的资金流水与余额历史 —— "
                    + "finance 没有提供接受外部流水写入的授权端点，"
                    + "而「一张事实表只有一个写入所有者」不允许本模块去写 finance 的账"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**直连 PayPal**（余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细）",
                false,
                "本仓目前没有任何真实的 PayPal 适配器"
                    + "（backend / services / tools 下不存在 paypal 之类的客户端），"
                    + "也没有任何真实凭据与网络通道",
                "18.2.2",
                "对应端点如实答 409（状态 NOT_CONNECTED）且**不写成功流水**；"
                    + "在此之前走「服务商账单导入」这条完整路径"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**直连 Stripe**（余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细）",
                false,
                "与 PayPal 同一条外部通道的缺口（本仓不存在 stripe 适配器）",
                "18.2.2",
                "对应端点如实答 409（状态 NOT_CONNECTED）且**不写成功流水**"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**直连 PingPong**（余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细）",
                false,
                "与 PayPal 同一条外部通道的缺口（本仓不存在 pingpong 适配器）",
                "18.2.2",
                "对应端点如实答 409（状态 NOT_CONNECTED）且**不写成功流水**"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**直连连连国际**（余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细）",
                false,
                "与 PayPal 同一条外部通道的缺口（本仓不存在 lianlian 适配器）",
                "18.2.2",
                "对应端端点如实答 409（状态 NOT_CONNECTED）且**不写成功流水**"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**直连万里汇（WorldFirst）**（余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细）",
                false,
                "与 PayPal 同一条外部通道的缺口（本仓不存在 worldfirst 适配器）",
                "18.2.2",
                "对应端点如实答 409（状态 NOT_CONNECTED）且**不写成功流水**"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**服务商侧汇率报价（锁汇）**",
                false,
                "与直连服务商同一段外部通道（quoteRate 走的是同一个适配器）",
                "18.2.2",
                "汇率查询端点**改用 catalog 汇率事实**（参考汇率，真实可读），"
                    + "并在响应里如实标出「服务商报价未接入」"),
            new PaymentFundsCapability(
                "18.2.2.1",
                "**资金流水回写上游 12.7 / 12.2**",
                false,
                "finance 服务**没有**提供接受外部资金流水写入的授权端点；"
                    + "而 AGENTS.md「每张事实表只有一个写入所有者」不允许本模块去写 finance 的账",
                "12.2 / 12.7",
                "本模块的资金流水台账（platform_fund_flow）是**本系统自己那一层**；"
                    + "与 finance 的关系是**只读**（查得到上游资金账户与付款单），不回写"));
    var gaps =
        List.of(
            new PaymentFundsGap(
                "18.2.1.1",
                "财务软件",
                "platform",
                true,
                "同卷兄弟",
                "「财务软件」管的是**凭证与法定账**的衔接（金蝶 / 用友 / SAP / Odoo），"
                    + "与本模块的**资金通道**不是一条路；两边唯一的交集是「都不写对方的表」"),
            new PaymentFundsGap(
                "18.2.3.1",
                "其他对接",
                "platform",
                false,
                "同卷兄弟",
                "「其他对接」按蓝图是货源平台 / 物流 / 认证等零散外部系统，与支付资金通道不同"),
            new PaymentFundsGap(
                "12.2.2.2",
                "付款执行（finance 服务）",
                "finance",
                true,
                "上游",
                "🔴 finance 自己**也有**一套付款执行（带渠道配置、幂等键与查证兜底）。"
                    + "本模块**不**重复实现它、也**不**改它的表："
                    + "本模块是**服务商侧**的适配与台账层（PayPal / Stripe / PingPong / 连连 / 万里汇），"
                    + "finance 是**业务单据侧**（12.2 付款单 / 审批 / 付款执行）；"
                    + "两边的连接是**只读**的"));
    return new PaymentFundsCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public PaymentFundsOverview overview() {
    return new PaymentFundsOverview(
        count("select count(*) from platform_payment_provider"),
        count("select count(*) from platform_payment_provider where status = 'ACTIVE'"),
        count("select count(*) from platform_payment_operation"),
        count("select count(*) from platform_payment_operation where status = 'UNKNOWN'"),
        count("select count(*) from platform_payment_balance"),
        count("select count(*) from platform_fund_flow"),
        count("select count(*) from platform_payment_fee"),
        count("select count(*) from platform_payment_rate"),
        dbNow());
  }

  // ================================================================ 服务商配置

  /** 逐服务商的通道可用性（**逐家如实答**）。 */
  public List<PaymentProviderAvailability> availability() {
    var out = new ArrayList<PaymentProviderAvailability>();
    for (var adapter : providers.all()) {
      out.add(
          new PaymentProviderAvailability(
              adapter.providerCode(),
              adapter.providerLabel(),
              adapter.connected(),
              adapter.unavailableReason(),
              AbstractUnavailablePaymentProviderAdapter.OWNER_MODULE));
    }
    return out;
  }

  /** 服务商配置列表（真筛选）。 */
  public List<PaymentProviderRow> providerRows(String providerCode, String status) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerCode != null && !providerCode.isBlank()) {
      where.append(" and p.provider_code = ?");
      params.add(providerCode.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and p.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select p.id,p.provider_code,p.name,p.base_url,p.credential_ref,p.credential_hint,"
                + " p.settlement_currency,p.status,p.timeout_seconds,p.max_attempts,p.remark,"
                + " p.created_by,p.created_at,p.updated_at,"
                + " (select count(*) from platform_payment_operation o where o.provider_id = p.id)"
                + "   as operation_count,"
                + " (select count(*) from platform_fund_flow f where f.provider_id = p.id)"
                + "   as fund_flow_count"
                + " from platform_payment_provider p"
                + where
                + " order by p.provider_code, p.created_at",
            params.toArray())
        .map(PaymentFundsService::toProviderRow);
  }

  /** 新增或修改一个服务商配置（**密钥只存引用**）。 */
  public PaymentProviderRow saveProvider(
      PaymentProviderInput input, AccessService.Access actor) {
    var code = PaymentProviderCode.parse(input.providerCode());
    check(code != null, "服务商只能是 PAYPAL / STRIPE / PINGPONG / LIANLIAN / WORLDFIRST");
    var name = required(input.name(), "配置名", 120);
    var baseUrl = optional(input.baseUrl(), "接口地址", 300);
    if (!baseUrl.isEmpty()) {
      check(
          baseUrl.startsWith("http://") || baseUrl.startsWith("https://"),
          "接口地址必须是 http:// 或 https:// 开头的完整地址");
    }
    var credentialRef = optional(input.credentialRef(), "密钥引用", 200);
    var credentialHint = optional(input.credentialHint(), "密钥末位提示", 16);
    check(
        credentialRef.isEmpty() == credentialHint.isEmpty(),
        "密钥引用与末位提示必须同时填或同时不填 —— 三者（同一条凭据的三个面）缺一个就说不清配的是哪条");
    var settlementCurrency =
        input.settlementCurrency() == null || input.settlementCurrency().isBlank()
            ? ""
            : currency(input.settlementCurrency(), "结算币种");
    var status =
        input.status() == null || input.status().isBlank()
            ? "ACTIVE"
            : input.status().trim().toUpperCase(Locale.ROOT);
    check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
    var timeoutSeconds = count(input.timeoutSeconds(), "超时秒数", 1, 120);
    var maxAttempts = count(input.maxAttempts(), "最大重试次数", 1, 10);
    var remark = optional(input.remark(), "备注", 500);

    var now = dbNow();
    var lookup = credentialRef.isEmpty() ? "" : sha256Hex(credentialRef);
    var id = input.id();
    if (id == null) {
      var existing =
          sql.fetchOne(
              "select id from platform_payment_provider where name = ? and provider_code = ?",
              name,
              code.name());
      id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
      if (existing == null) {
        sql.execute(
            "insert into platform_payment_provider(id,tenant_id,provider_code,name,base_url,"
                + " credential_ref,credential_ref_lookup,credential_hint,settlement_currency,status,"
                + " timeout_seconds,max_attempts,remark,created_by,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            id,
            currentTenant(),
            code.name(),
            name,
            baseUrl,
            credentialRef,
            lookup,
            credentialHint,
            settlementCurrency,
            status,
            timeoutSeconds,
            maxAttempts,
            remark,
            actor.userId(),
            now,
            now);
      } else {
        sql.execute(
            "update platform_payment_provider set base_url = ?, credential_ref = ?,"
                + " credential_ref_lookup = ?, credential_hint = ?, settlement_currency = ?, status = ?,"
                + " timeout_seconds = ?, max_attempts = ?, remark = ?, updated_at = ?::timestamptz"
                + " where id = ?",
            baseUrl,
            credentialRef,
            lookup,
            credentialHint,
            settlementCurrency,
            status,
            timeoutSeconds,
            maxAttempts,
            remark,
            now,
            id);
      }
    } else {
      var updated =
          sql.execute(
              "update platform_payment_provider set provider_code = ?, name = ?, base_url = ?,"
                  + " credential_ref = ?, credential_ref_lookup = ?, credential_hint = ?,"
                  + " settlement_currency = ?, status = ?, timeout_seconds = ?, max_attempts = ?,"
                  + " remark = ?, updated_at = ?::timestamptz where id = ?",
              code.name(),
              name,
              baseUrl,
              credentialRef,
              lookup,
              credentialHint,
              settlementCurrency,
              status,
              timeoutSeconds,
              maxAttempts,
              remark,
              now,
              id);
      check(updated > 0, "服务商配置不存在");
    }
    return providerRow(id);
  }

  /** 停用一个服务商配置（**不删**：历史流水与操作记录要能复现）。 */
  public PaymentProviderRow disableProvider(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select id from platform_payment_provider where id = ?", id);
    check(row != null, "服务商配置不存在");
    sql.execute(
        "update platform_payment_provider set status = 'DISABLED', updated_at = ?::timestamptz"
            + " where id = ?",
        dbNow(),
        id);
    return providerRow(id);
  }

  private PaymentProviderRow providerRow(UUID id) {
    return providerRows(null, null).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "服务商配置不存在"));
  }

  private static PaymentProviderRow toProviderRow(org.jooq.Record r) {
    var code = PaymentProviderCode.parse(r.get("provider_code", String.class));
    var status = r.get("status", String.class);
    var credentialRef = r.get("credential_ref", String.class);
    return new PaymentProviderRow(
        r.get("id", UUID.class),
        code == null ? "" : code.name(),
        code == null ? "" : code.label(),
        r.get("name", String.class),
        r.get("base_url", String.class),
        credentialRef,
        r.get("credential_hint", String.class),
        r.get("settlement_currency", String.class),
        status,
        "ACTIVE".equals(status) ? "启用" : "停用",
        !credentialRef.isEmpty(),
        r.get("timeout_seconds", BigDecimal.class) == null
            ? 0
            : r.get("timeout_seconds", BigDecimal.class).intValue(),
        r.get("max_attempts", BigDecimal.class) == null
            ? 0
            : r.get("max_attempts", BigDecimal.class).intValue(),
        r.get("operation_count", Long.class),
        r.get("fund_flow_count", Long.class),
        r.get("remark", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 取一个**启用中**的服务商配置。 */
  private PaymentProviderRow requireProvider(UUID id) {
    var row = providerRow(id);
    check("ACTIVE".equals(row.status()), "服务商配置已停用，不能对它发起资金操作");
    return row;
  }

  /** 适配器（按服务商代码找；找不到就是配置写错了）。 */
  private PaymentProviderPort adapterOf(PaymentProviderRow provider) {
    var adapter = providers.find(provider.providerCode());
    check(adapter != null, "没有该服务商的适配器：" + provider.providerCode());
    return adapter;
  }

  private ProviderContext contextOf(PaymentProviderRow provider, String bearer) {
    return new ProviderContext(
        provider.providerCode(),
        provider.baseUrl(),
        provider.credentialRef(),
        provider.timeoutSeconds(),
        provider.maxAttempts(),
        bearer);
  }

  // ================================================================ 操作台账（幂等键）

  /** 一次资金操作的全部入参（内部聚合）。 */
  private record OperationSpec(
      PaymentOperationType type,
      PaymentDirection direction,
      PaymentProviderRow provider,
      BigDecimal amount,
      String currency,
      BigDecimal baseAmount,
      String baseCurrency,
      BigDecimal exchangeRate,
      String reference,
      String counterpartyName,
      String counterpartyRef,
      String counterpartyType,
      String idempotencyKey,
      String requestDigest,
      String source,
      String remark) {}

  /** 幂等键判定的结果。 */
  private record BeginResult(PaymentOperationRow row, boolean replayed) {}

  /**
   * 幂等键判定 + 操作落库。
   *
   * <p>🔴 <b>同键重复提交 ⇒ 原样返回既有行，绝不第二次调用服务商</b>。
   * 同键但请求内容不同 ⇒ 409。
   */
  private BeginResult begin(OperationSpec spec, AccessService.Access actor) {
    var existing =
        sql.fetchOne(
            "select id from platform_payment_operation where idempotency_key = ?",
            spec.idempotencyKey());
    if (existing != null) {
      var row = operationRow(existing.get("id", UUID.class));
      if (!row.requestDigest().equals(spec.requestDigest())) {
        conflict(
            "同一个幂等键已经用于另一个请求（幂等键 "
                + spec.idempotencyKey()
                + " 已登记过不同的请求内容）—— 请换一个幂等键，或用原请求的参数重放");
      }
      // 🔴 同键同请求 ⇒ 原样返回既有结果，**绝不第二次调用服务商**。
      return new BeginResult(toOperationRow(rowRecord(row.id()), true), true);
    }
    var now = dbNow();
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_payment_operation(id,tenant_id,idempotency_key,operation_type,"
            + " provider_id,provider_code,direction,status,amount,currency,base_amount,base_currency,"
            + " exchange_rate,reference,counterparty_name,counterparty_ref,counterparty_type,"
            + " request_digest,provider_notified,provider_notice,source,attempt_count,retry_allowed,"
            + " created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,false,'',?,0,false,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        spec.idempotencyKey(),
        spec.type().name(),
        spec.provider().id(),
        spec.provider().providerCode(),
        spec.direction() == null ? "" : spec.direction().name(),
        spec.amount(),
        spec.currency(),
        spec.baseAmount(),
        spec.baseCurrency(),
        spec.exchangeRate(),
        spec.reference(),
        spec.counterpartyName(),
        spec.counterpartyRef(),
        spec.counterpartyType(),
        spec.requestDigest(),
        spec.source(),
        actor.userId(),
        now,
        now);
    return new BeginResult(operationRow(id), false);
  }

  /** 请求内容摘要（SHA-256）：钉住「同一个幂等键必须对应同一个请求」。 */
  private static String digestOf(String... parts) {
    return sha256Hex(String.join("|", parts));
  }

  /**
   * 幂等键：调用方可自带；不带时按请求内容**派生**。
   *
   * <p>🔴 派生口径必须是**请求内容本身**（不含时间、不含随机数）——
   * 否则两次一模一样的提交会拿到两个不同的键，幂等就形同虚设。
   */
  private static String deriveKey(String... parts) {
    return "auto-" + sha256Hex(String.join("|", parts)).substring(0, 32);
  }

  /** 操作记录列表（真筛选 + 真关键词）。 */
  public List<PaymentOperationRow> operationRows(
      String providerCode, String operationType, String status, String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerCode != null && !providerCode.isBlank()) {
      where.append(" and o.provider_code = ?");
      params.add(providerCode.trim().toUpperCase(Locale.ROOT));
    }
    if (operationType != null && !operationType.isBlank()) {
      where.append(" and o.operation_type = ?");
      params.add(operationType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and o.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (o.idempotency_key ilike ? or o.reference ilike ?"
          + " or o.provider_serial_no ilike ? or o.counterparty_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return sql.fetch(
            "select o.id from platform_payment_operation o"
                + where
                + " order by o.created_at desc, o.id desc limit "
                + MAX_PAGE_SIZE,
            params.toArray())
        .map(r -> operationRow(r.get("id", UUID.class)));
  }

  /** 一条操作记录。 */
  public PaymentOperationRow operationRow(UUID id) {
    var r =
        sql.fetchOne(
            "select id,idempotency_key,operation_type,provider_id,provider_code,direction,status,"
                + " amount,currency,base_amount,base_currency,exchange_rate,reference,"
                + " counterparty_name,counterparty_ref,counterparty_type,provider_serial_no,"
                + " failure_reason,failure_class,provider_notified,provider_notice,source,"
                + " attempt_count,retry_allowed,request_digest,created_by,created_at,updated_at"
                + " from platform_payment_operation where id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资金操作记录不存在");
    }
    return toOperationRow(r);
  }

  /** 一次操作的尝试留痕（按序号）。 */
  public List<PaymentAttemptRow> attempts(UUID operationId) {
    return sql.fetch(
            "select id,operation_id,attempt_no,attempt_kind,idempotency_key,outcome,"
                + " provider_serial_no,failure_reason,request_summary,response_summary,"
                + " started_at,finished_at from platform_payment_attempt where operation_id = ?"
                + " order by attempt_no",
            operationId)
        .map(r -> toAttemptRow(r));
  }

  private static PaymentOperationRow toOperationRow(org.jooq.Record r) {
    return toOperationRow(r, false);
  }

  /** 取一条操作记录的原样行（供幂等重放标记用）。 */
  private org.jooq.Record rowRecord(UUID id) {
    return sql.fetchOne("select * from platform_payment_operation where id = ?", id);
  }

  private static PaymentOperationRow toOperationRow(org.jooq.Record r, boolean replayed) {
    var type = PaymentOperationType.valueOf(r.get("operation_type", String.class));
    var status = PaymentOperationStatus.valueOf(r.get("status", String.class));
    var code = PaymentProviderCode.parse(r.get("provider_code", String.class));
    var direction = r.get("direction", String.class);
    return new PaymentOperationRow(
        r.get("id", UUID.class),
        r.get("idempotency_key", String.class),
        type.name(),
        type.label(),
        r.get("provider_id", UUID.class),
        code == null ? "" : code.name(),
        code == null ? "" : code.label(),
        direction,
        status.name(),
        status.label(),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("base_amount", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("reference", String.class),
        r.get("counterparty_name", String.class),
        r.get("counterparty_ref", String.class),
        r.get("counterparty_type", String.class),
        r.get("provider_serial_no", String.class),
        r.get("failure_reason", String.class),
        r.get("failure_class", String.class),
        r.get("provider_notified", Boolean.class),
        r.get("provider_notice", String.class),
        r.get("source", String.class),
        r.get("attempt_count", Integer.class),
        r.get("retry_allowed", Boolean.class),
        r.get("request_digest", String.class),
        replayed,
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static PaymentAttemptRow toAttemptRow(org.jooq.Record r) {
    var kind = PaymentAttemptKind.valueOf(r.get("attempt_kind", String.class));
    var outcome = PaymentAttemptOutcome.valueOf(r.get("outcome", String.class));
    return new PaymentAttemptRow(
        r.get("id", UUID.class),
        r.get("operation_id", UUID.class),
        r.get("attempt_no", Integer.class),
        kind.name(),
        kind.label(),
        r.get("idempotency_key", String.class),
        outcome.name(),
        outcome.label(),
        r.get("provider_serial_no", String.class),
        r.get("failure_reason", String.class),
        r.get("request_summary", String.class),
        r.get("response_summary", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  /** 下一个尝试序号（SEND 与 QUERY 共用一条递增序列）。 */
  private int nextAttemptNo(UUID operationId) {
    return sql.fetchOne(
            "select coalesce(max(attempt_no), 0) + 1 from platform_payment_attempt"
                + " where operation_id = ?",
            operationId)
        .get(0, Integer.class);
  }

  /** 记一条尝试留痕。 */
  private void recordAttempt(
      UUID operationId,
      int attemptNo,
      PaymentAttemptKind kind,
      String idempotencyKey,
      PaymentAttemptOutcome outcome,
      String providerSerialNo,
      String failureReason,
      String requestSummary,
      String responseSummary) {
    var now = dbNow();
    sql.execute(
        "insert into platform_payment_attempt(id,tenant_id,operation_id,attempt_no,attempt_kind,"
            + " idempotency_key,outcome,provider_serial_no,failure_reason,request_summary,"
            + " response_summary,started_at,finished_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        operationId,
        attemptNo,
        kind.name(),
        idempotencyKey,
        outcome.name(),
        providerSerialNo == null ? "" : providerSerialNo,
        failureReason == null ? "" : failureReason,
        requestSummary == null ? "" : requestSummary,
        responseSummary == null ? "" : responseSummary,
        now,
        now);
  }

  /** 更新操作状态。 */
  private void updateOperation(
      UUID id,
      PaymentOperationStatus status,
      String providerSerialNo,
      String failureReason,
      String failureClass,
      boolean providerNotified,
      String providerNotice,
      boolean retryAllowed) {
    sql.execute(
        "update platform_payment_operation set status = ?, provider_serial_no = ?,"
            + " failure_reason = ?, failure_class = ?, provider_notified = ?, provider_notice = ?,"
            + " retry_allowed = ?, attempt_count = (select count(*) from platform_payment_attempt"
            + "   where operation_id = ?), updated_at = ?::timestamptz where id = ?",
        status.name(),
        providerSerialNo == null ? "" : providerSerialNo,
        failureReason == null ? "" : failureReason,
        failureClass == null ? "" : failureClass,
        providerNotified,
        providerNotice == null ? "" : providerNotice,
        retryAllowed,
        id,
        dbNow(),
        id);
  }

  // ================================================================ 付款执行

  /**
   * **付款执行**（蓝图 18.2.2.1 的核心动作）。
   *
   * <p>🔴 三条硬规则：
   * <ol>
   *   <li><b>幂等键</b>：同键重复提交 ⇒ 原样返回既有结果，**绝不第二次调用服务商**。
   *   <li><b>超时先查证</b>：发起超时 / 不可达 ⇒ 操作进 UNKNOWN，**自动查证**这一笔。
   *       查证说「成功」⇒ **不重发**；说「服务商侧确实没有」⇒ 才允许重试；
   *       查不出来 ⇒ **不允许重试**。
   *   <li><b>没接通就说没接通</b>：适配器未接入 ⇒ 状态 NOT_CONNECTED、**不写成功流水**。
   * </ol>
   */
  public PaymentOperationRow executePayment(
      PaymentExecutionInput input, AccessService.Access actor, String bearer) {
    var provider = requireProvider(input.providerId());
    var amount = money(input.amount(), "付款金额", true);
    var ccy = currency(input.currency(), "付款币种");
    var baseCurrency =
        input.baseCurrency() == null || input.baseCurrency().isBlank()
            ? ""
            : currency(input.baseCurrency(), "本位币");
    var exchangeRate = rate(input.exchangeRate(), "汇率");
    var baseAmount = money(input.baseAmount(), "本位币金额", false);
    var reference = required(input.reference(), "业务单号（付款单号）", 120);
    var counterpartyName = required(input.counterpartyName(), "收款方名称", 200);
    var counterpartyRef = optional(input.counterpartyRef(), "收款方账号", 200);
    var counterpartyType = optional(input.counterpartyType(), "收款方类型", 40);
    var note = optional(input.note(), "备注", 500);

    var digest =
        digestOf(
            provider.providerCode(),
            reference,
            amount.toPlainString(),
            ccy,
            baseCurrency,
            exchangeRate == null ? "" : exchangeRate.toPlainString(),
            counterpartyName,
            counterpartyRef);
    var key =
        input.idempotencyKey() == null || input.idempotencyKey().isBlank()
            ? deriveKey(
                provider.providerCode(), "PAYMENT_EXECUTE", reference, amount.toPlainString(), ccy)
            : required(input.idempotencyKey(), "幂等键", 120);

    var begin =
        begin(
            new OperationSpec(
                PaymentOperationType.PAYMENT_EXECUTE,
                PaymentDirection.OUTBOUND,
                provider,
                amount,
                ccy,
                baseAmount,
                baseCurrency,
                exchangeRate,
                reference,
                counterpartyName,
                counterpartyRef,
                counterpartyType,
                key,
                digest,
                "DIRECT",
                note),
            actor);
    if (begin.replayed()) {
      // 🔴 同键重复提交：**不调用服务商**，原样返回既有结果。
      return begin.row();
    }
    var operation = begin.row();
    var adapter = adapterOf(provider);
    var context = contextOf(provider, bearer);
    var command =
        new PaymentCommand(
            key,
            reference,
            ccy,
            amount,
            baseCurrency.isEmpty() ? null : baseCurrency,
            exchangeRate,
            baseAmount,
            counterpartyName,
            counterpartyRef,
            counterpartyType,
            note);

    var attemptNo = nextAttemptNo(operation.id());
    var outcome = adapter.executePayment(context, command);
    recordAttempt(
        operation.id(),
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        outcome.outcome(),
        outcome.providerSerialNo(),
        outcome.reason(),
        "付款 " + amount.toPlainString() + " " + ccy + " → " + counterpartyName,
        outcome.outcome().name());

    switch (outcome.outcome()) {
      case SUCCESS -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.SUCCEEDED,
            outcome.providerSerialNo(),
            "",
            "",
            true,
            "",
            false);
        appendFundFlow(
            operation,
            "PAYMENT",
            PaymentDirection.OUTBOUND,
            amount,
            ccy,
            baseAmount,
            baseCurrency,
            exchangeRate,
            outcome.providerSerialNo(),
            reference,
            counterpartyName,
            note);
        return operationRow(operation.id());
      }
      case REJECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.FAILED,
            "",
            outcome.reason(),
            "REJECTED",
            true,
            "",
            true);
        return operationRow(operation.id());
      }
      case NOT_CONNECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.NOT_CONNECTED,
            "",
            outcome.reason(),
            "NOT_CONFIGURED",
            false,
            outcome.reason(),
            false);
        return operationRow(operation.id());
      }
      default -> {
        // 🔴 TIMEOUT / UNKNOWN ⇒ 结果未知，**先查证再决定要不要重试**。
        updateOperation(
            operation.id(),
            PaymentOperationStatus.UNKNOWN,
            "",
            outcome.reason(),
            outcome.outcome() == PaymentAttemptOutcome.TIMEOUT ? "TIMEOUT" : "UNAVAILABLE",
            false,
            outcome.reason(),
            false);
        return verifyBeforeRetry(operation.id(), key, provider, adapter, context, bearer);
      }
    }
  }

  /**
   * **查证兜底**：向服务商确认这一笔到底有没有发生。
   *
   * <p>🔴 这是蓝图加粗句「超时先查证再重试」的落点：
   * <ul>
   *   <li>查证说 <b>成功</b> ⇒ 操作转成功，<b>不重发</b>（重复付款是严重资损事故）；
   *   <li>查证说 <b>服务商侧确实没有</b>（NOT_FOUND）⇒ 仍记 UNKNOWN，但<b>允许重试</b>；
   *   <li>查证说 <b>明确失败</b> ⇒ 转 FAILED（确定性失败）；
   *   <li>查证<b>查不出来</b> ⇒ 保持 UNKNOWN 且<b>不允许重试</b>（查不到 ≠ 没付出去）。
   * </ul>
   */
  private PaymentOperationRow verifyBeforeRetry(
      UUID operationId,
      String key,
      PaymentProviderRow provider,
      PaymentProviderPort adapter,
      ProviderContext context,
      String bearer) {
    var attemptNo = nextAttemptNo(operationId);
    var verified = adapter.queryPayment(context, key);
    recordAttempt(
        operationId,
        attemptNo,
        PaymentAttemptKind.QUERY,
        key,
        verified.outcome(),
        verified.providerSerialNo(),
        verified.reason(),
        "查证幂等键 " + key,
        verified.outcome().name());
    switch (verified.outcome()) {
      case SUCCESS -> {
        var operation = operationRow(operationId);
        updateOperation(
            operationId,
            PaymentOperationStatus.SUCCEEDED,
            verified.providerSerialNo(),
            "",
            "",
            true,
            "超时后经服务商查证确认已支付，未重发",
            false);
        appendFundFlow(
            operation,
            "PAYMENT",
            PaymentDirection.OUTBOUND,
            operation.amount(),
            operation.currency(),
            operation.baseAmount(),
            operation.baseCurrency(),
            operation.exchangeRate(),
            verified.providerSerialNo(),
            operation.reference(),
            operation.counterpartyName(),
            operation.providerNotice());
        return operationRow(operationId);
      }
      case REJECTED -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.FAILED,
            "",
            verified.reason(),
            "REJECTED",
            true,
            "",
            true);
        return operationRow(operationId);
      }
      case NOT_FOUND -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.UNKNOWN,
            "",
            "服务商侧确认没有这一笔（查证结果 NOT_FOUND），允许重试",
            "UNAVAILABLE",
            false,
            "",
            true);
        return operationRow(operationId);
      }
      default -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.UNKNOWN,
            "",
            "查证未得到结论（" + verified.reason() + "）：结果仍未知，不允许重试",
            "UNAVAILABLE",
            false,
            "",
            false);
        return operationRow(operationId);
      }
    }
  }

  /**
   * **重发**一次付款（只在查证确认「服务商侧没有这笔」之后才允许）。
   *
   * <p>🔴 复用**同一个幂等键**：服务商侧既然没有这笔记录，
   * 用同一个键重发才是「同一笔业务的重试」；本服务这一侧也仍然只有一行操作记录。
   */
  public PaymentOperationRow retryPayment(UUID operationId, AccessService.Access actor, String bearer) {
    var operation = operationRow(operationId);
    if (!PaymentOperationStatus.UNKNOWN.name().equals(operation.status())) {
      conflict("只有「结果未知」的操作可以重试（当前状态：" + operation.statusLabel() + "）");
    }
    if (!operation.retryAllowed()) {
      conflict(
          "不允许重试：上一次**查证没有确认**服务商侧没有这一笔。"
              + "「查不到」不等于「没付出去」—— 盲目重发可能付两次。"
              + "请先在服务商侧核对，或等查证给出结论");
    }
    var provider = requireProvider(operation.providerId());
    var adapter = adapterOf(provider);
    var context = contextOf(provider, bearer);
    var key = operation.idempotencyKey();

    var attemptNo = nextAttemptNo(operationId);
    var outcome =
        adapter.executePayment(
            context,
            new PaymentCommand(
                key,
                operation.reference(),
                operation.currency(),
                operation.amount(),
                operation.baseCurrency().isEmpty() ? null : operation.baseCurrency(),
                operation.exchangeRate(),
                operation.baseAmount(),
                operation.counterpartyName(),
                operation.counterpartyRef(),
                operation.counterpartyType(),
                ""));
    recordAttempt(
        operationId,
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        outcome.outcome(),
        outcome.providerSerialNo(),
        outcome.reason(),
        "重发（已查证服务商侧无此笔）",
        outcome.outcome().name());

    switch (outcome.outcome()) {
      case SUCCESS -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.SUCCEEDED,
            outcome.providerSerialNo(),
            "",
            "",
            true,
            "",
            false);
        appendFundFlow(
            operation,
            "PAYMENT",
            PaymentDirection.OUTBOUND,
            operation.amount(),
            operation.currency(),
            operation.baseAmount(),
            operation.baseCurrency(),
            operation.exchangeRate(),
            outcome.providerSerialNo(),
            operation.reference(),
            operation.counterpartyName(),
            "");
        return operationRow(operationId);
      }
      case REJECTED -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.FAILED, "", outcome.reason(), "REJECTED", true, "", true);
        return operationRow(operationId);
      }
      case NOT_CONNECTED -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.NOT_CONNECTED,
            "",
            outcome.reason(),
            "NOT_CONFIGURED",
            false,
            outcome.reason(),
            false);
        return operationRow(operationId);
      }
      default -> {
        updateOperation(
            operationId,
            PaymentOperationStatus.UNKNOWN,
            "",
            outcome.reason(),
            outcome.outcome() == PaymentAttemptOutcome.TIMEOUT ? "TIMEOUT" : "UNAVAILABLE",
            false,
            outcome.reason(),
            false);
        return verifyBeforeRetry(operationId, key, provider, adapter, context, bearer);
      }
    }
  }

  // ================================================================ 收款登记

  /**
   * **收款登记**：把一笔收到的钱登记进本系统的资金流水台账，并**尝试**通知服务商侧。
   *
   * <p>🔴 与付款执行的**不同**（这条差异是刻意的）：
   * <ul>
   *   <li>付款执行**必须**动钱 ⇒ 服务商未接入就是 409 + <b>不写流水</b>；
   *   <li>收款登记的**主体效果是本系统的台账**（蓝图输出写的是「操作结果 + 资金流水同步」）
   *       ⇒ 服务商未接入时**照样登记本地流水**，但
   *       {@code provider_notified=false} 且 {@code provider_notice} 写明「服务商未接入，
   *       未向服务商登记」。一条 status=SUCCEEDED 且 provider_notified=false 的行
   *       <b>不可能</b>被误读成「服务商侧已经登记过了」。
   * </ul>
   */
  public PaymentOperationRow registerReceipt(
      ReceiptRegistrationInput input, AccessService.Access actor, String bearer) {
    var provider = requireProvider(input.providerId());
    var amount = money(input.amount(), "收款金额", true);
    var ccy = currency(input.currency(), "收款币种");
    var baseCurrency =
        input.baseCurrency() == null || input.baseCurrency().isBlank()
            ? ""
            : currency(input.baseCurrency(), "本位币");
    var exchangeRate = rate(input.exchangeRate(), "汇率");
    var baseAmount = money(input.baseAmount(), "本位币金额", false);
    var reference = required(input.reference(), "业务单号（收款单号）", 120);
    var counterpartyName = required(input.counterpartyName(), "付款方名称", 200);
    var counterpartyRef = optional(input.counterpartyRef(), "付款方账号", 200);
    var counterpartyType = optional(input.counterpartyType(), "付款方类型", 40);
    var providerSerialNo = optional(input.providerSerialNo(), "服务商流水号", 120);
    var note = optional(input.note(), "备注", 500);

    var digest =
        digestOf(
            provider.providerCode(),
            reference,
            amount.toPlainString(),
            ccy,
            counterpartyName,
            providerSerialNo);
    var key =
        input.idempotencyKey() == null || input.idempotencyKey().isBlank()
            ? deriveKey(
                provider.providerCode(), "RECEIPT_REGISTER", reference, amount.toPlainString(), ccy)
            : required(input.idempotencyKey(), "幂等键", 120);

    var begin =
        begin(
            new OperationSpec(
                PaymentOperationType.RECEIPT_REGISTER,
                PaymentDirection.INBOUND,
                provider,
                amount,
                ccy,
                baseAmount,
                baseCurrency,
                exchangeRate,
                reference,
                counterpartyName,
                counterpartyRef,
                counterpartyType,
                key,
                digest,
                "DIRECT",
                note),
            actor);
    if (begin.replayed()) {
      return begin.row();
    }
    var operation = begin.row();
    var adapter = adapterOf(provider);
    var context = contextOf(provider, bearer);
    var attemptNo = nextAttemptNo(operation.id());
    var outcome =
        adapter.registerReceipt(
            context,
            new ReceiptCommand(
                key,
                reference,
                ccy,
                amount,
                baseCurrency.isEmpty() ? null : baseCurrency,
                exchangeRate,
                baseAmount,
                counterpartyName,
                counterpartyRef,
                counterpartyType,
                note));
    recordAttempt(
        operation.id(),
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        outcome.outcome(),
        outcome.providerSerialNo(),
        outcome.reason(),
        "收款登记 " + amount.toPlainString() + " " + ccy + " ← " + counterpartyName,
        outcome.outcome().name());

    switch (outcome.outcome()) {
      case SUCCESS -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.SUCCEEDED,
            outcome.providerSerialNo(),
            "",
            "",
            true,
            "",
            false);
        appendFundFlow(
            operation,
            "RECEIPT",
            PaymentDirection.INBOUND,
            amount,
            ccy,
            baseAmount,
            baseCurrency,
            exchangeRate,
            outcome.providerSerialNo(),
            reference,
            counterpartyName,
            note);
        return operationRow(operation.id());
      }
      case REJECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.FAILED,
            "",
            outcome.reason(),
            "REJECTED",
            true,
            "",
            false);
        return operationRow(operation.id());
      }
      case NOT_CONNECTED -> {
        // ⚠️ 本地台账**照样登记**（钱确实收到了），但明确标注服务商侧未登记。
        updateOperation(
            operation.id(),
            PaymentOperationStatus.SUCCEEDED,
            providerSerialNo,
            "",
            "",
            false,
            outcome.reason(),
            false);
        appendFundFlow(
            operation,
            "RECEIPT",
            PaymentDirection.INBOUND,
            amount,
            ccy,
            baseAmount,
            baseCurrency,
            exchangeRate,
            providerSerialNo,
            reference,
            counterpartyName,
            note);
        return operationRow(operation.id());
      }
      default -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.UNKNOWN,
            "",
            outcome.reason(),
            outcome.outcome() == PaymentAttemptOutcome.TIMEOUT ? "TIMEOUT" : "UNAVAILABLE",
            false,
            outcome.reason(),
            false);
        return operationRow(operation.id());
      }
    }
  }

  // ================================================================ 余额

  /** 余额观测列表（真筛选）。 */
  public List<PaymentBalanceRow> balanceRows(UUID providerId, String currency) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerId != null) {
      where.append(" and b.provider_id = ?");
      params.add(providerId);
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and b.currency = ?");
      params.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select b.id,b.provider_id,b.currency,b.available_amount,b.balance_amount,b.source,"
                + " b.observed_at,b.source_ref,b.created_at,"
                + " p.provider_code,p.name as provider_name"
                + " from platform_payment_balance b"
                + " join platform_payment_provider p on p.tenant_id = b.tenant_id and p.id = b.provider_id"
                + where
                + " order by b.observed_at desc, b.id desc limit "
                + MAX_PAGE_SIZE,
            params.toArray())
        .map(
            r ->
                new PaymentBalanceRow(
                    r.get("id", UUID.class),
                    r.get("provider_id", UUID.class),
                    r.get("provider_code", String.class),
                    r.get("provider_name", String.class),
                    r.get("currency", String.class),
                    r.get("available_amount", BigDecimal.class),
                    r.get("balance_amount", BigDecimal.class),
                    r.get("source", String.class),
                    r.get("observed_at", OffsetDateTime.class),
                    r.get("source_ref", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  /**
   * **余额查询**（服务商直查）。
   *
   * <p>🔴 服务商未接入 ⇒ 状态 NOT_CONNECTED、**不写余额行**（没有真实观测值就不该有观测行）。
   */
  public PaymentOperationRow queryBalance(
      BalanceQueryInput input, AccessService.Access actor, String bearer) {
    var provider = requireProvider(input.providerId());
    var ccy =
        input.currency() == null || input.currency().isBlank()
            ? ""
            : currency(input.currency(), "币种");
    var key =
        input.idempotencyKey() == null || input.idempotencyKey().isBlank()
            ? deriveKey(provider.providerCode(), "BALANCE_QUERY", ccy)
            : required(input.idempotencyKey(), "幂等键", 120);
    var digest = digestOf(provider.providerCode(), ccy);

    var begin =
        begin(
            new OperationSpec(
                PaymentOperationType.BALANCE_QUERY,
                null,
                provider,
                null,
                ccy,
                null,
                "",
                null,
                "",
                "",
                "",
                "",
                key,
                digest,
                "DIRECT",
                ""),
            actor);
    if (begin.replayed()) {
      return begin.row();
    }
    var operation = begin.row();
    var adapter = adapterOf(provider);
    var attemptNo = nextAttemptNo(operation.id());
    var outcome =
        adapter.queryBalance(contextOf(provider, bearer), new BalanceQuery(ccy));
    recordAttempt(
        operation.id(),
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        outcome.outcome(),
        outcome.providerSerialNo(),
        outcome.reason(),
        "余额查询 " + (ccy.isEmpty() ? "全部币种" : ccy),
        outcome.outcome().name());

    switch (outcome.outcome()) {
      case SUCCESS -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.SUCCEEDED,
            outcome.providerSerialNo(),
            "",
            "",
            true,
            "",
            false);
        sql.execute(
            "insert into platform_payment_balance(id,tenant_id,provider_id,currency,"
                + " available_amount,balance_amount,source,observed_at,source_ref,created_by,created_at)"
                + " values(?,?,?,?,?,?,'PROVIDER',?::timestamptz,'',?,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            provider.id(),
            outcome.currency() == null || outcome.currency().isEmpty() ? ccy : outcome.currency(),
            outcome.availableAmount(),
            outcome.balanceAmount(),
            dbNow(),
            actor.userId(),
            dbNow());
        return operationRow(operation.id());
      }
      case REJECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.FAILED, "", outcome.reason(), "REJECTED", true, "", true);
        return operationRow(operation.id());
      }
      case NOT_CONNECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.NOT_CONNECTED,
            "",
            outcome.reason(),
            "NOT_CONFIGURED",
            false,
            outcome.reason(),
            false);
        return operationRow(operation.id());
      }
      default -> {
        // ⚠️ 只读操作：重发**没有资损风险**，所以允许重试（与付款执行的严格口径不同）。
        updateOperation(
            operation.id(),
            PaymentOperationStatus.UNKNOWN,
            "",
            outcome.reason(),
            outcome.outcome() == PaymentAttemptOutcome.TIMEOUT ? "TIMEOUT" : "UNAVAILABLE",
            false,
            outcome.reason(),
            true);
        return operationRow(operation.id());
      }
    }
  }

  /**
   * **余额导入**（从服务商账单 / 对账单）。
   *
   * <p>这是服务商未接入时**今天就能用**的路径：把对账单上的余额导进来，
   * 余额历史就在本系统里可查（append-only）。
   */
  public PaymentBalanceRow importBalance(
      BalanceImportInput input, AccessService.Access actor) {
    var provider = requireProvider(input.providerId());
    var ccy = currency(input.currency(), "币种");
    var balanceAmount = money(input.balanceAmount(), "账面余额", true);
    var availableAmount = money(input.availableAmount(), "可用余额", false);
    var sourceRef = optional(input.sourceRef(), "来源说明（账单名 / 批次号）", 200);
    var observedAt = parseInstant(input.observedAt(), "观测时刻", dbNow());

    sql.execute(
        "insert into platform_payment_balance(id,tenant_id,provider_id,currency,available_amount,"
            + " balance_amount,source,observed_at,source_ref,created_by,created_at)"
            + " values(?,?,?,?,?,?,'IMPORT',?::timestamptz,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        provider.id(),
        ccy,
        availableAmount,
        balanceAmount,
        observedAt,
        sourceRef,
        actor.userId(),
        dbNow());
    return balanceRows(provider.id(), ccy).get(0);
  }

  // ================================================================ 手续费明细

  /** 手续费明细列表（真筛选）。 */
  public List<PaymentFeeRow> feeRows(UUID providerId, String currency) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerId != null) {
      where.append(" and f.provider_id = ?");
      params.add(providerId);
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and f.currency = ?");
      params.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select f.id,f.provider_id,f.operation_id,f.fee_type,f.amount,f.currency,"
                + " f.charged_amount,f.provider_serial_no,f.provider_reference,f.occurred_at,"
                + " f.source,f.summary,f.created_at,p.provider_code,p.name as provider_name"
                + " from platform_payment_fee f"
                + " join platform_payment_provider p on p.tenant_id = f.tenant_id and p.id = f.provider_id"
                + where
                + " order by f.occurred_at desc, f.id desc limit "
                + MAX_PAGE_SIZE,
            params.toArray())
        .map(
            r ->
                new PaymentFeeRow(
                    r.get("id", UUID.class),
                    r.get("provider_id", UUID.class),
                    r.get("provider_code", String.class),
                    r.get("provider_name", String.class),
                    r.get("operation_id", UUID.class),
                    r.get("fee_type", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("currency", String.class),
                    r.get("charged_amount", BigDecimal.class),
                    r.get("provider_serial_no", String.class),
                    r.get("provider_reference", String.class),
                    r.get("occurred_at", OffsetDateTime.class),
                    r.get("source", String.class),
                    r.get("summary", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  /**
   * **手续费明细同步**（服务商直拉）。
   *
   * <p>🔴 服务商未接入 ⇒ 状态 NOT_CONNECTED、**不写手续费行**。
   */
  public PaymentOperationRow syncFees(
      UUID providerId, String currency, String idempotencyKey, AccessService.Access actor, String bearer) {
    var provider = requireProvider(providerId);
    var ccy =
        currency == null || currency.isBlank() ? "" : currency(currency, "币种");
    var key =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? deriveKey(provider.providerCode(), "FEE_SYNC", ccy, dbNow().toString())
            : required(idempotencyKey, "幂等键", 120);
    var digest = digestOf(provider.providerCode(), ccy);

    var begin =
        begin(
            new OperationSpec(
                PaymentOperationType.FEE_SYNC,
                null,
                provider,
                null,
                ccy,
                null,
                "",
                null,
                "",
                "",
                "",
                "",
                key,
                digest,
                "DIRECT",
                ""),
            actor);
    if (begin.replayed()) {
      return begin.row();
    }
    var operation = begin.row();
    var adapter = adapterOf(provider);
    var attemptNo = nextAttemptNo(operation.id());
    var outcome =
        adapter.fetchFeeDetails(
            contextOf(provider, bearer),
            new FeeQuery(ccy, null, null));
    recordAttempt(
        operation.id(),
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        outcome.outcome(),
        "",
        outcome.reason(),
        "手续费明细同步 " + (ccy.isEmpty() ? "全部币种" : ccy),
        outcome.outcome().name());

    switch (outcome.outcome()) {
      case SUCCESS -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.SUCCEEDED,
            "",
            "",
            "",
            true,
            "",
            false);
        writeFeeRows(provider, operation.id(), outcome.fees(), "SYNC", actor);
        return operationRow(operation.id());
      }
      case REJECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.FAILED, "", outcome.reason(), "REJECTED", true, "", true);
        return operationRow(operation.id());
      }
      case NOT_CONNECTED -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.NOT_CONNECTED,
            "",
            outcome.reason(),
            "NOT_CONFIGURED",
            false,
            outcome.reason(),
            false);
        return operationRow(operation.id());
      }
      default -> {
        updateOperation(
            operation.id(),
            PaymentOperationStatus.UNKNOWN,
            "",
            outcome.reason(),
            outcome.outcome() == PaymentAttemptOutcome.TIMEOUT ? "TIMEOUT" : "UNAVAILABLE",
            false,
            outcome.reason(),
            true);
        return operationRow(operation.id());
      }
    }
  }

  /** 写手续费明细行（append-only）。 */
  private void writeFeeRows(
      PaymentProviderRow provider,
      UUID operationId,
      List<PaymentProviderPort.FeeDetail> fees,
      String source,
      AccessService.Access actor) {
    if (fees == null || fees.isEmpty()) return;
    check(fees.size() <= MAX_IMPORT_ROWS, "一次最多同步 " + MAX_IMPORT_ROWS + " 条手续费明细");
    for (var fee : fees) {
      sql.execute(
          "insert into platform_payment_fee(id,tenant_id,provider_id,operation_id,fee_type,amount,"
              + " currency,charged_amount,provider_serial_no,provider_reference,occurred_at,source,"
              + " summary,created_by,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          provider.id(),
          operationId,
          fee.feeType(),
          fee.amount(),
          fee.currency(),
          fee.chargedAmount(),
          fee.providerSerialNo(),
          fee.providerReference(),
          fee.occurredAt(),
          source,
          fee.summary(),
          actor.userId(),
          dbNow());
    }
  }

  /**
   * **手续费明细导入**（从服务商账单 / 对账单）。
   *
   * <p>服务商未接入时**今天就能用**的路径。
   */
  public PaymentFeeRow importFee(FeeImportInput input, AccessService.Access actor) {
    var provider = requireProvider(input.providerId());
    var feeType = required(input.feeType(), "费用类型", 80);
    var amount = money(input.amount(), "手续费金额", true);
    var ccy = currency(input.currency(), "币种");
    var chargedAmount = money(input.chargedAmount(), "被收取金额", false);
    var providerSerialNo = optional(input.providerSerialNo(), "服务商流水号", 120);
    var providerReference = optional(input.providerReference(), "服务商关联单号", 120);
    var summary = optional(input.summary(), "摘要", 500);
    var occurredAt = parseInstant(input.occurredAt(), "发生时刻", dbNow());

    sql.execute(
        "insert into platform_payment_fee(id,tenant_id,provider_id,fee_type,amount,currency,"
            + " charged_amount,provider_serial_no,provider_reference,occurred_at,source,summary,"
            + " created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,'IMPORT',?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        provider.id(),
        feeType,
        amount,
        ccy,
        chargedAmount,
        providerSerialNo,
        providerReference,
        occurredAt,
        summary,
        actor.userId(),
        dbNow());
    return feeRows(provider.id(), ccy).get(0);
  }

  // ================================================================ 汇率

  /** 汇率观测列表（真筛选）。 */
  public List<PaymentRateRow> rateRows(UUID providerId, String baseCurrency, String quoteCurrency) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerId != null) {
      where.append(" and r.provider_id = ?");
      params.add(providerId);
    }
    if (baseCurrency != null && !baseCurrency.isBlank()) {
      where.append(" and r.base_currency = ?");
      params.add(baseCurrency.trim().toUpperCase(Locale.ROOT));
    }
    if (quoteCurrency != null && !quoteCurrency.isBlank()) {
      where.append(" and r.quote_currency = ?");
      params.add(quoteCurrency.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select r.id,r.provider_id,r.base_currency,r.quote_currency,r.rate,r.source,r.inverted,"
                + " r.effective_date,r.observed_at,r.source_ref,r.created_at,"
                + " p.provider_code,p.name as provider_name"
                + " from platform_payment_rate r"
                + " join platform_payment_provider p on p.tenant_id = r.tenant_id and p.id = r.provider_id"
                + where
                + " order by r.observed_at desc, r.id desc limit "
                + MAX_PAGE_SIZE,
            params.toArray())
        .map(
            r ->
                new PaymentRateRow(
                    r.get("id", UUID.class),
                    r.get("provider_id", UUID.class),
                    r.get("provider_code", String.class),
                    r.get("provider_name", String.class),
                    r.get("base_currency", String.class),
                    r.get("quote_currency", String.class),
                    r.get("rate", BigDecimal.class),
                    r.get("source", String.class),
                    r.get("inverted", Boolean.class),
                    r.get("effective_date", LocalDate.class),
                    r.get("observed_at", OffsetDateTime.class),
                    r.get("source_ref", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  /**
   * **汇率查询**。
   *
   * <p>🔴 两个来源，**口径分开**：
   * <ol>
   *   <li><b>参考汇率</b> —— 真读 catalog 5.2.2.1 汇率事实（{@link CatalogRatePort}），
   *       读不到就 <b>503</b>（是「读不到」不是「没有汇率」），**不**回退到「最近的一版」；
   *   <li><b>服务商报价</b> —— {@link PaymentProviderPort#quoteRate}（含点差的锁汇价）。
   *       服务商未接入时**如实标注**，但**不影响**上面那条参考汇率落地。
   * </ol>
   */
  public PaymentOperationRow queryRate(
      RateQueryInput input, AccessService.Access actor, String bearer) {
    var provider = requireProvider(input.providerId());
    var base = currency(input.baseCurrency(), "基准币种");
    var quote = currency(input.quoteCurrency(), "报价币种");
    check(!base.equals(quote), "基准币种与报价币种不能相同");
    var asOf =
        input.asOf() == null || input.asOf().isBlank()
            ? null
            : LocalDate.parse(input.asOf().trim());

    // 🔴 参考汇率：真读上游。读不到 ⇒ 503（PlatformUpstreamClient 抛）。
    var reference = catalog.latest(base, quote, asOf, bearer);

    var key =
        input.idempotencyKey() == null || input.idempotencyKey().isBlank()
            ? deriveKey(provider.providerCode(), "RATE_QUERY", base, quote, String.valueOf(asOf))
            : required(input.idempotencyKey(), "幂等键", 120);
    var digest = digestOf(provider.providerCode(), base, quote, String.valueOf(asOf));

    var begin =
        begin(
            new OperationSpec(
                PaymentOperationType.RATE_QUERY,
                null,
                provider,
                null,
                quote,
                null,
                "",
                reference.rate(),
                "",
                "",
                "",
                "",
                key,
                digest,
                "DIRECT",
                ""),
            actor);
    if (begin.replayed()) {
      return begin.row();
    }
    var operation = begin.row();
    var adapter = adapterOf(provider);
    var attemptNo = nextAttemptNo(operation.id());
    var quoted = adapter.quoteRate(contextOf(provider, bearer), new RateCommand(base, quote, asOf));
    recordAttempt(
        operation.id(),
        attemptNo,
        PaymentAttemptKind.SEND,
        key,
        quoted.outcome(),
        "",
        quoted.reason(),
        "汇率报价 " + base + "→" + quote,
        quoted.outcome().name());

    // 🔴 优先用**服务商报价**（含点差）；服务商未接入就用**参考汇率**，并如实标注来源。
    var source = "CATALOG";
    var rateValue = reference.rate();
    var inverted = false;
    var effectiveDate = reference.effectiveDate();
    var sourceRef =
        reference.sourceCode() == null ? "" : "catalog:" + reference.sourceCode();
    var providerNotified = false;
    var providerNotice = quoted.reason();
    if (quoted.outcome() == PaymentAttemptOutcome.SUCCESS
        && quoted.rate() != null
        && quoted.rate().signum() > 0) {
      source = "PROVIDER";
      rateValue = quoted.rate();
      inverted = quoted.inverted();
      effectiveDate = quoted.effectiveDate();
      sourceRef = quoted.sourceRef() == null ? "" : quoted.sourceRef();
      providerNotified = true;
      providerNotice = "";
    }

    sql.execute(
        "insert into platform_payment_rate(id,tenant_id,provider_id,base_currency,quote_currency,"
            + " rate,source,inverted,effective_date,observed_at,source_ref,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        provider.id(),
        base,
        quote,
        rateValue,
        source,
        inverted,
        effectiveDate,
        dbNow(),
        sourceRef,
        actor.userId(),
        dbNow());

    updateOperation(
        operation.id(),
        PaymentOperationStatus.SUCCEEDED,
        "",
        "",
        "",
        providerNotified,
        providerNotice,
        false);
    return operationRow(operation.id());
  }

  // ================================================================ 资金流水台账

  /** 资金流水列表（真筛选）。 */
  public List<FundFlowRow> fundFlowRows(UUID providerId, String flowType, String currency) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (providerId != null) {
      where.append(" and f.provider_id = ?");
      params.add(providerId);
    }
    if (flowType != null && !flowType.isBlank()) {
      where.append(" and f.flow_type = ?");
      params.add(flowType.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and f.currency = ?");
      params.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select f.id,f.flow_no,f.provider_id,f.operation_id,f.flow_type,f.direction,f.amount,"
                + " f.currency,f.base_amount,f.base_currency,f.exchange_rate,f.provider_serial_no,"
                + " f.reference,f.counterparty_name,f.occurred_at,f.source,f.remark,f.created_at,"
                + " p.provider_code,p.name as provider_name"
                + " from platform_fund_flow f"
                + " join platform_payment_provider p on p.tenant_id = f.tenant_id and p.id = f.provider_id"
                + where
                + " order by f.occurred_at desc, f.id desc limit "
                + MAX_PAGE_SIZE,
            params.toArray())
        .map(
            r ->
                new FundFlowRow(
                    r.get("id", UUID.class),
                    r.get("flow_no", String.class),
                    r.get("provider_id", UUID.class),
                    r.get("provider_code", String.class),
                    r.get("provider_name", String.class),
                    r.get("operation_id", UUID.class),
                    r.get("flow_type", String.class),
                    flowTypeLabel(r.get("flow_type", String.class)),
                    r.get("direction", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("currency", String.class),
                    r.get("base_amount", BigDecimal.class),
                    r.get("base_currency", String.class),
                    r.get("exchange_rate", BigDecimal.class),
                    r.get("provider_serial_no", String.class),
                    r.get("reference", String.class),
                    r.get("counterparty_name", String.class),
                    r.get("occurred_at", OffsetDateTime.class),
                    r.get("source", String.class),
                    r.get("remark", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  private static String flowTypeLabel(String raw) {
    return switch (raw) {
      case "RECEIPT" -> "收款";
      case "PAYMENT" -> "付款";
      case "FEE" -> "手续费";
      case "ADJUSTMENT" -> "调整";
      default -> raw;
    };
  }

  /** 追加一条资金流水（**append-only**）。 */
  private void appendFundFlow(
      PaymentOperationRow operation,
      String flowType,
      PaymentDirection direction,
      BigDecimal amount,
      String ccy,
      BigDecimal baseAmount,
      String baseCurrency,
      BigDecimal exchangeRate,
      String providerSerialNo,
      String reference,
      String counterpartyName,
      String remark) {
    var flowNo =
        sql.fetchOne("select 'FF-' || lpad(nextval('platform_fund_flow_no_seq')::text, 10, '0')")
            .get(0, String.class);
    sql.execute(
        "insert into platform_fund_flow(id,tenant_id,flow_no,provider_id,operation_id,flow_type,"
            + " direction,amount,currency,base_amount,base_currency,exchange_rate,provider_serial_no,"
            + " reference,counterparty_name,occurred_at,source,remark,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,'OPERATION',?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        flowNo,
        operation.providerId(),
        operation.id(),
        flowType,
        direction.name(),
        amount,
        ccy,
        baseAmount,
        baseCurrency == null ? "" : baseCurrency,
        exchangeRate,
        providerSerialNo == null ? "" : providerSerialNo,
        reference == null ? "" : reference,
        counterpartyName == null ? "" : counterpartyName,
        dbNow(),
        remark == null ? "" : remark,
        operation.createdBy(),
        dbNow());
  }

  // ================================================================ 上游（只读）

  /** 上游 12.7 资金账户（**只读**；读不到 ⇒ 503）。 */
  public List<UpstreamFundAccountRow> upstreamFundAccounts(
      String currency, String keyword, String bearer) {
    var page = finance.fundAccounts(currency, keyword, 0, MAX_PAGE_SIZE, bearer);
    if (page == null || page.items() == null) return List.of();
    return page.items().stream()
        .map(
            r ->
                new UpstreamFundAccountRow(
                    r.id(),
                    r.accountNo(),
                    r.accountName(),
                    r.accountType(),
                    r.currency(),
                    r.currentBalance(),
                    r.currentBalanceAt(),
                    r.status(),
                    r.note()))
        .toList();
  }

  /** 上游 12.2 收付款（**只读**；读不到 ⇒ 503）。 */
  public List<UpstreamPaymentOrderRow> upstreamPaymentOrders(
      String status, String currency, String keyword, String bearer) {
    var page = finance.paymentOrders(status, currency, keyword, 0, MAX_PAGE_SIZE, bearer);
    if (page == null || page.items() == null) return List.of();
    return page.items().stream()
        .map(
            r ->
                new UpstreamPaymentOrderRow(
                    r.id(),
                    r.orderNo(),
                    r.counterpartyName(),
                    r.currency(),
                    r.amount(),
                    r.baseAmount(),
                    r.baseCurrency(),
                    r.exchangeRate(),
                    r.status(),
                    r.paymentMethod(),
                    r.paymentAccountName()))
        .toList();
  }

  // ================================================================ 工具

  /** 解析一个 ISO-8601 时刻；空 ⇒ 用默认。 */
  private static OffsetDateTime parseInstant(String raw, String label, OffsetDateTime fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (java.time.format.DateTimeParseException e) {
      try {
        return OffsetDateTime.of(LocalDate.parse(raw.trim()).atStartOfDay(), ZoneOffset.UTC);
      } catch (java.time.format.DateTimeParseException e2) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, label + "必须是 ISO-8601 时刻（如 2026-10-31T08:00:00+08:00）");
      }
    }
  }
}
