package com.starcon.trade;

import static com.starcon.trade.DropshipMarginModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.6.2.1 代发差价与对账。
 *
 * <p><b>八条实现纪律</b>（照抄 9.6.1.1 / 9.6.1.2 / 9.6.1.3 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>「无法判定」不是「不亏」。</b>平台费规则来自 5.2.3.1（未交付），
 *       本模块把它建成<b>可登记的费率来源</b>；未登记时结论是
 *       {@code UNDETERMINED_NO_FEE}，<b>绝不</b>按 0 算。按 0 算会同时犯两个错：
 *       把真实亏损放过去，又把所有代发单染成亏损把真亏损淹掉。
 *       判定本身是 DB 生成列 {@code loss_verdict}，应用层算错也改不动。</li>
 *   <li><b>差价只有一个真相。</b>{@code dropship_cost / spread_before_fee /
 *       spread_after_fee / loss_verdict} 四个数都是 V130 的 GENERATED 列，
 *       本服务<b>不另算第二份</b>；对账行把它们<b>拷贝</b>成冻结快照，
 *       事后改费率改不动历史对账单。</li>
 *   <li><b>利润口径隔离。</b>报表与对账每一行都带 {@code businessModel=DROPSHIP}
 *       与 {@code costBasis=PURCHASE_SPREAD}（DB CHECK 钉死）。
 *       代发的成本是<b>采购差价</b>，不是自有库存成本 —— 蓝图原话
 *       「否则代发店铺利润会被算错」。本服务一条自营单都不碰。</li>
 *   <li><b>跨币种不相加。</b>报表按 (维度, 币种) 分组；对账按结算币种折算，
 *       汇率未登记的行整组为 NULL 并被 {@code is_in_totals} 排除在合计外，
 *       单独计入 undetermined_* 桶。<b>不用 1.0 假装</b>。</li>
 *   <li><b>禁浮点。</b>全部金额 {@link BigDecimal}，明确 scale（金额 4 位、
 *       数量 6 位）与 {@link RoundingMode}，无 double / float 中转。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@link OffsetDateTime}。用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 −28800s。</li>
 *   <li><b>周期端点由调用方 pin 住。</b>对账周期不从墙上时钟推导 ——
 *       「上个月」这种键在月初与月末圈出不同范围，既没法复算也没法断言。</li>
 *   <li><b>下游 12.1.2.1 未交付：落真待办。</b>应付单写进
 *       {@code fulfillment_dropship_payable_task}（有状态机、有载荷、有幂等键），
 *       状态从 OPEN 起；{@code SENT}（已发出）与 {@code ACKNOWLEDGED}（下游已受理）
 *       是<b>两个</b>状态，任务在下游接手前不会被当成已办。</li>
 * </ol>
 *
 * <p><b>跨服务一律不读表</b>：5.2.3.1 / 5.2.3.2（catalog）、12.1.2.1 / 13.4.1.x
 * （finance）各在自己的库里，本模块一条 SQL 都不碰。上游 9.6.1.2 的代发单、
 * 9.6.1.1 的代发配置与 9.1 的销售订单<b>同属 trade 库</b>，因此可以只读 join。
 */
@Service
public class DropshipMarginService {

  /** 单页最大行数（与 9.6.1.1 / 9.6.1.2 / 9.6.1.3 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 口径说明：随每一行报表与每一张对账单走，下游 13 利润报表据此分组。 */
  static final String BASIS_NOTE =
      "代发模式的成本是采购差价（供应商结算价 + 供应商运费），不是自有库存成本；"
          + "利润口径与自营不同，下游利润报表必须按经营模式分组，不得并入自营成本口径。";

  /** 成本已经落定、可以算差价的代发单状态（未落定的成本还会变）。 */
  private static final List<String> SETTLED_ORDER_STATUSES = List.of("ACCEPTED", "SHIPPED");

  private static final String FACT_COLUMNS =
      "id,order_id,order_no,sales_order_no,shop_code,supplier_code,currency_code,business_model,"
          + "cost_basis,sell_amount,purchase_amount,supplier_shipping_amount,dropship_cost,"
          + "spread_before_fee,platform_fee_amount,spread_after_fee,margin_rate,"
          + "fee_registration_status,loss_verdict,loss_evidence,computed_at";

  private static final String ALERT_COLUMNS =
      "id,alert_no,order_id,order_no,shop_code,supplier_code,currency_code,alert_kind,severity,"
          + "rule_code,rule_evidence,fee_registration_status,platform_fee_amount,sell_amount,"
          + "dropship_cost,spread_before_fee,spread_after_fee,period_start,period_end,status,"
          + "acknowledged_at,resolution_note,detected_at";

  private static final String STATEMENT_COLUMNS =
      "id,statement_no,supplier_code,period_start,period_end,cycle_kind,currency_code,"
          + "business_model,cost_basis,status,order_count,loss_order_count,total_sell_amount,"
          + "total_purchase_amount,total_supplier_shipping,total_platform_fee,total_dropship_cost,"
          + "total_spread,total_payable,undetermined_order_count,undetermined_payable,"
          + "fee_unregistered_order_count,fx_rate_status,fx_rate_value,rule_evidence,basis_note,"
          + "dispute_note,finalized_at,closed_at,created_at,version";

  private static final String LINE_COLUMNS =
      "id,order_no,sales_order_no,shop_code,original_currency_code,statement_currency_code,"
          + "sell_amount,purchase_amount,supplier_shipping_amount,fee_registration_status,"
          + "platform_fee_amount,loss_verdict,fx_rate_status,fx_rate_value,sell_amount_in_statement,"
          + "purchase_amount_in_statement,supplier_shipping_in_statement,platform_fee_in_statement,"
          + "dropship_cost_in_statement,spread_in_statement,payable_in_statement,is_in_totals,"
          + "rule_evidence";

  private static final String TASK_COLUMNS =
      "id,task_no,task_kind,target_system,task_status,statement_id,statement_no,supplier_code,"
          + "payable_amount,payable_currency_code,payload,idempotency_key,attempt_count,"
          + "last_attempt_at,last_error,claimed_at,sent_at,acknowledged_at,created_at";

  private static final String EVENT_COLUMNS =
      "id,event_kind,fact_id,statement_id,alert_id,task_id,supplier_code,detail,occurred_at";

  private final DSLContext sql;
  private final ObjectMapper json;

  public DropshipMarginService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>四个维度显式 {@code available=false} 并点名 ownerModule：
   * <b>平台费用规则</b>（5.2.3.1）、<b>物流与头程费用</b>（5.2.3.2）、
   * <b>应付单</b>（12.1.2.1）、<b>利润报表</b>（13.4.1.1 / 13.4.1.2）。
   *
   * <p>前两个在本模块里的真实形态是<b>可登记的费率来源</b>，不是已通的能力 ——
   * 报成 true 会让人以为亏损判据已经完整，而它缺的那一项会让判定退化成
   * 「无法判定」。后两个是<b>真待办</b>，不是已完成的动作。
   */
  public DropshipMarginCapabilities capabilities() {
    return new DropshipMarginCapabilities(
        Arrays.stream(DropshipLossVerdict.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipFeeChargeBasis.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipFxStatus.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipReportGroupBy.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipStatementStatus.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipPayableTaskStatus.values()).map(Enum::name).toList(),
        List.of(
            new DropshipMarginCapabilityRule(
                "DROPSHIP_COST_BASIS",
                "代发成本口径",
                "代发成本 = 供应商结算价 + 供应商运费（蓝图原话）",
                "dropship_cost = purchase_amount + supplier_shipping_amount",
                "核对供应商结算单据上的采购价与运费"),
            new DropshipMarginCapabilityRule(
                "SPREAD_BEFORE_FEE",
                "不含平台费的差价",
                "差价（未含平台费）= 我方售价 − 采购结算价 − 供应商运费",
                "spread_before_fee = sell_amount − purchase_amount − supplier_shipping_amount",
                "看整体采购议价能力，不受平台费率影响"),
            new DropshipMarginCapabilityRule(
                "LOSS_CONFIRMED",
                "已确认亏损",
                "售价低于代发成本（已登记平台费时含平台费）。平台费恒 ≥ 0，"
                    + "所以只按代发成本就已亏损的单，即使平台费未登记也算确认亏损",
                "sell_amount < dropship_cost(+ platform_fee)",
                "停投该 SKU / 改售价 / 换供应商 / 与供应商重谈结算价"),
            new DropshipMarginCapabilityRule(
                "UNDETERMINED_NO_FEE",
                "平台费未登记，无法判定",
                "售价不低于代发成本，但平台费规则未登记 —— 真实结论既可能是不亏、"
                    + "也可能是亏损，**无法判定**。不等于不亏，也不按 0 算",
                "sell_amount ≥ dropship_cost 且 platform_fee IS NULL",
                "去登记平台费率（5.2.3.1 交付后自动供给，或租户自行登记）"),
            new DropshipMarginCapabilityRule(
                "RECONCILIATION_BASIS",
                "对账与应付口径",
                "应付供应商 = 采购结算价 + 供应商运费（折算到结算币种）；"
                    + "汇率未登记的行不计入合计，单独进未登记桶",
                "payable = (purchase_amount + supplier_shipping_amount) × fx_rate",
                "补登汇率后重开一张对账单"),
            new DropshipMarginCapabilityRule(
                "MODE_ISOLATION",
                "经营模式口径隔离",
                "代发单的成本是采购差价，与自营的自有库存成本不是同一口径；"
                    + "本模块只出代发口径，不出也不合并自营口径",
                "business_model = 'DROPSHIP' AND cost_basis = 'PURCHASE_SPREAD'",
                "下游 13 利润报表按经营模式分组呈现")),
        List.of(
            new DropshipMarginCapabilityDimension(
                "DROPSHIP_ORDER_COST",
                "代发单成本与售价（9.6.1.2）",
                true,
                "MODULE_9_6_1_2",
                null,
                "同库只读：fulfillment_dropship_order 的冻结采购价/运费/售价，"
                    + "供应商回执给了实际成本时以实际为准"),
            new DropshipMarginCapabilityDimension(
                "DROPSHIP_CONFIG",
                "代发模式配置（9.6.1.1）",
                true,
                "MODULE_9_6_1_1",
                null,
                "同库只读：取 shop_code 作为报表的店铺维度"),
            new DropshipMarginCapabilityDimension(
                "PLATFORM_FEE_RATE",
                "平台费用规则（5.2.3.1）",
                false,
                "MODULE_5_2_3_1",
                "5.2.3.1",
                "**未交付**。本模块把平台费建模为可登记的费率来源"
                    + "（fulfillment_dropship_fee_rate）；未登记时亏损判定结论是"
                    + "「无法判定」而不是按 0 算 —— 按 0 算会同时漏判真实亏损"
                    + "并把所有代发单染成亏损"),
            new DropshipMarginCapabilityDimension(
                "FIRST_MILE_FEE_RATE",
                "物流与头程费用（5.2.3.2）",
                false,
                "MODULE_5_2_3_2",
                "5.2.3.2",
                "**未交付**。头程与物流费本模块不并入代发成本：它是另一段物流的"
                    + "成本，与供应商运费不是同一个数；需要时经 fx_rate 登记口径后"
                    + "由下游 13 利润报表处理"),
            new DropshipMarginCapabilityDimension(
                "FX_RATE",
                "折算汇率",
                false,
                "TENANT_MAINTAINED",
                "5.2.3.2 / 12 财务",
                "**未交付自动供给**。本模块提供可登记的汇率（fulfillment_dropship_fx_rate）；"
                    + "未登记时对账行标 UNSET、不计入合计，不用 1.0 假装"),
            new DropshipMarginCapabilityDimension(
                "PAYABLE_ORDER",
                "应付单管理（12.1.2.1）",
                false,
                "MODULE_12_1_2_1",
                "12.1.2.1",
                "**未交付**（finance 服务）。供应商对账单在本模块生成，"
                    + "应付单以 fulfillment_dropship_payable_task 真待办交给下游；"
                    + "任务在下游受理前不显示为已办"),
            new DropshipMarginCapabilityDimension(
                "PROFIT_REPORT",
                "利润报表（13.4.1.1 / 13.4.1.2）",
                false,
                "MODULE_13_4_1_1",
                "13.4.1.1 / 13.4.1.2",
                "**未交付**。本模块只出代发口径的差价与毛利，不冒充下游利润报表；"
                    + "下游必须按经营模式分组，否则代发店铺利润会被算错")),
        false,
        "代发差价与对账：差价毛利唯一真相在 DB 生成列；平台费未登记 = 无法判定，不按 0 算；"
            + "代发成本是采购差价，与自营成本口径隔离；跨币种不相加，汇率未登记单列未登记桶。",
        BASIS_NOTE);
  }

  // ================================================================ 平台费率登记

  public DropshipFeeRatePage pageFeeRates(String keyword, int page, int size) {
    int sizeNo = pageSize(size);
    int pageNo = Math.max(0, page);
    List<Object> binds = new ArrayList<>();
    String where = "";
    if (notBlank(keyword)) {
      where = " where (rate_code ilike ? or rate_name ilike ? or platform_code ilike ?)";
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_fee_rate" + where, binds.toArray())
            .get("c", Long.class);
    List<DropshipFeeRateRow> items =
        sql.fetch(
                "select id,rate_code,rate_name,platform_code,fee_type,charge_basis,rate_percent,"
                    + "fixed_amount,fixed_currency_code,settlement_currency_code,effective_from,"
                    + "effective_to,source,observed_at,note,version from fulfillment_dropship_fee_rate"
                    + where
                    + " order by platform_code, rate_code limit ? offset ?",
                append(binds, sizeNo, (long) pageNo * sizeNo))
            .map(DropshipMarginService::feeRateRow);
    return new DropshipFeeRatePage(items, total, pageNo, sizeNo);
  }

  /**
   * 登记平台费率（upsert）。
   *
   * <p>校验刻意比 DB 更严：{@code charge_basis} 与两个金额列的对应关系在 DB 有 CHECK，
   * 这里提前拦是为了给出可读的错误消息，而不是让运营撞一条 23514。
   */
  @Transactional
  public DropshipFeeRateRow saveFeeRate(DropshipFeeRateInput input, UUID membershipId) {
    if (input == null) {
      throw bad("平台费率登记必须提供完整内容");
    }
    if (input.chargeBasis() == DropshipFeeChargeBasis.PERCENT_OF_SELL) {
      if (input.ratePercent() == null) {
        throw bad("按售价比例计费必须提供 ratePercent（比例是 0..1 之间的小数，不是百分数）");
      }
      if (input.fixedAmount() != null || notBlank(input.fixedCurrencyCode())) {
        throw bad("按售价比例计费不能同时提供 fixedAmount / fixedCurrencyCode："
            + "两种计价形态只能选一种，否则「这条费率到底怎么算」没有答案");
      }
    } else {
      if (input.fixedAmount() == null) {
        throw bad("按单固定额计费必须提供 fixedAmount");
      }
      if (input.ratePercent() != null) {
        throw bad("按单固定额计费不能同时提供 ratePercent：两种计价形态只能选一种");
      }
      if (!notBlank(input.fixedCurrencyCode())) {
        throw bad("按单固定额必须提供 fixedCurrencyCode：固定额没有币种就只是一个数");
      }
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw bad("生效结束时间必须晚于生效开始时间");
    }
    if (input.observedAt().isAfter(input.effectiveFrom())) {
      throw bad("观测时点不得晚于生效起点：「从下周生效、却观测于上周」的费率说明登记方抄错了行，"
          + "而它会直接决定亏损判定");
    }
    UUID id =
        sql.fetchOptional(
                "select id from fulfillment_dropship_fee_rate where tenant_id = "
                    + "nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and rate_code = ?",
                input.rateCode())
            .map(r -> r.get("id", UUID.class))
            .orElse(null);
    if (id == null) {
      id = UUID.randomUUID();
    }
    sql.execute(
        "insert into fulfillment_dropship_fee_rate (id,tenant_id,rate_code,rate_name,"
            + "platform_code,fee_type,charge_basis,rate_percent,fixed_amount,fixed_currency_code,"
            + "settlement_currency_code,effective_from,effective_to,source,observed_at,note,"
            + "registered_by_membership_id,version,updated_at) "
            + "values (?::uuid,nullif(current_setting('app.current_tenant',true),'')::uuid,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,"
            + "'TENANT_MAINTAINED',?::timestamptz,?,?,1,?::timestamptz) "
            + "on conflict (tenant_id,rate_code) do update set rate_name = excluded.rate_name,"
            + "platform_code = excluded.platform_code, fee_type = excluded.fee_type,"
            + "charge_basis = excluded.charge_basis, rate_percent = excluded.rate_percent,"
            + "fixed_amount = excluded.fixed_amount,"
            + "fixed_currency_code = excluded.fixed_currency_code,"
            + "settlement_currency_code = excluded.settlement_currency_code,"
            + "effective_from = excluded.effective_from, effective_to = excluded.effective_to,"
            + "observed_at = excluded.observed_at, note = excluded.note,"
            + "registered_by_membership_id = excluded.registered_by_membership_id,"
            + "version = fulfillment_dropship_fee_rate.version + 1,"
            + "updated_at = excluded.updated_at",
        id, input.rateCode(), input.rateName(), input.platformCode(), input.feeType(),
        input.chargeBasis().name(), input.ratePercent(), input.fixedAmount(),
        blankToNull(input.fixedCurrencyCode()), input.settlementCurrencyCode(),
        input.effectiveFrom(), input.effectiveTo(), input.observedAt(),
        blankToEmpty(input.note()), membershipId, OffsetDateTime.now());
    event("FEE_RATE_REGISTERED", null, null, null, null, input.rateCode(),
        "登记平台费率 " + input.rateCode() + "（" + input.chargeBasis().label() + "）", membershipId);
    return feeRateRow(
        sql.fetchOne(
            "select id,rate_code,rate_name,platform_code,fee_type,charge_basis,rate_percent,"
                + "fixed_amount,fixed_currency_code,settlement_currency_code,effective_from,"
                + "effective_to,source,observed_at,note,version from fulfillment_dropship_fee_rate"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            id));
  }

  // ================================================================ 汇率登记

  public DropshipFxRatePage pageFxRates(String baseCurrency, int page, int size) {
    int sizeNo = pageSize(size);
    int pageNo = Math.max(0, page);
    List<Object> binds = new ArrayList<>();
    String where = "";
    if (notBlank(baseCurrency)) {
      where = " where (base_currency = ? or quote_currency = ?)";
      binds.add(baseCurrency.trim().toUpperCase(Locale.ROOT));
      binds.add(baseCurrency.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_fx_rate" + where, binds.toArray())
            .get("c", Long.class);
    List<DropshipFxRateRow> items =
        sql.fetch(
                "select id,base_currency,quote_currency,rate_value,effective_from,effective_to,"
                    + "source,observed_at,note,version from fulfillment_dropship_fx_rate"
                    + where
                    + " order by base_currency, quote_currency, effective_from desc"
                    + " limit ? offset ?",
                append(binds, sizeNo, (long) pageNo * sizeNo))
            .map(DropshipMarginService::fxRateRow);
    return new DropshipFxRatePage(items, total, pageNo, sizeNo);
  }

  /** 登记汇率（upsert）：1 base = rateValue quote。未登记时对账行标 UNSET，不用 1.0 假装。 */
  @Transactional
  public DropshipFxRateRow saveFxRate(DropshipFxRateInput input, UUID membershipId) {
    if (input == null) {
      throw bad("汇率登记必须提供完整内容");
    }
    if (input.baseCurrency().equals(input.quoteCurrency())) {
      throw bad("本币对本币不需要汇率：原币即结算币的行会自动标 SAME_CURRENCY");
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw bad("生效结束时间必须晚于生效开始时间");
    }
    if (input.observedAt().isAfter(input.effectiveFrom())) {
      throw bad("观测时点不得晚于生效起点：观测值必须来自真实发生过的时点");
    }
    sql.execute(
        "insert into fulfillment_dropship_fx_rate (id,tenant_id,base_currency,quote_currency,"
            + "rate_value,effective_from,effective_to,source,observed_at,note,"
            + "registered_by_membership_id,version,updated_at) "
            + "values (gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,?,?,?,?::timestamptz,?::timestamptz,?,"
            + "?::timestamptz,?,?,1,?::timestamptz) "
            + "on conflict (tenant_id,base_currency,quote_currency) do update set "
            + "rate_value = excluded.rate_value, effective_from = excluded.effective_from,"
            + "effective_to = excluded.effective_to, source = excluded.source,"
            + "observed_at = excluded.observed_at, note = excluded.note,"
            + "registered_by_membership_id = excluded.registered_by_membership_id,"
            + "version = fulfillment_dropship_fx_rate.version + 1,"
            + "updated_at = excluded.updated_at",
        input.baseCurrency(), input.quoteCurrency(), input.rateValue(), input.effectiveFrom(),
        input.effectiveTo(), input.source(), input.observedAt(), blankToEmpty(input.note()),
        membershipId, OffsetDateTime.now());
    event("FX_RATE_REGISTERED", null, null, null, null, null,
        "登记汇率 " + input.baseCurrency() + "→" + input.quoteCurrency() + " = "
            + input.rateValue(), membershipId);
    return fxRateRow(
        sql.fetchOne(
            "select id,base_currency,quote_currency,rate_value,effective_from,effective_to,"
                + "source,observed_at,note,version from fulfillment_dropship_fx_rate "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and base_currency = ? and quote_currency = ?",
            input.baseCurrency(), input.quoteCurrency()));
  }

  // ================================================================ 结算周期

  public DropshipSettlementCyclePage pageCycles(String supplierCode, int page, int size) {
    int sizeNo = pageSize(size);
    int pageNo = Math.max(0, page);
    List<Object> binds = new ArrayList<>();
    String where = "";
    if (notBlank(supplierCode)) {
      where = " where supplier_code = ?";
      binds.add(supplierCode.trim());
    }
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_settlement_cycle" + where,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipSettlementCycleRow> items =
        sql.fetch(
                "select id,cycle_code,supplier_code,currency_code,cycle_kind,settle_day,"
                    + "effective_from,effective_to,status,note,version "
                    + "from fulfillment_dropship_settlement_cycle"
                    + where
                    + " order by supplier_code, effective_from desc limit ? offset ?",
                append(binds, sizeNo, (long) pageNo * sizeNo))
            .map(DropshipMarginService::cycleRow);
    return new DropshipSettlementCyclePage(items, total, pageNo, sizeNo);
  }

  @Transactional
  public DropshipSettlementCycleRow saveCycle(
      DropshipSettlementCycleInput input, UUID membershipId) {
    if (input == null) {
      throw bad("结算周期登记必须提供完整内容");
    }
    String kind = input.cycleKind().trim().toUpperCase(Locale.ROOT);
    if (("WEEKLY".equals(kind) || "BIWEEKLY".equals(kind)) && input.settleDay() != null) {
      throw bad("周结 / 双周结不看「每月几号」，settleDay 必须留空："
          + "留着它会让「周结的每月 15 号结算」这种自相矛盾的登记混进来");
    }
    if (("MONTHLY".equals(kind) || "CUSTOM".equals(kind)) && input.settleDay() == null) {
      throw bad("月结 / 自定义结必须提供 settleDay：没有结算日的周期无从对账");
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw bad("生效结束时间必须晚于生效开始时间");
    }
    sql.execute(
        "insert into fulfillment_dropship_settlement_cycle (id,tenant_id,cycle_code,supplier_code,"
            + "currency_code,cycle_kind,settle_day,effective_from,effective_to,status,note,"
            + "created_by_membership_id,version,updated_at) "
            + "values (gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,?,?,?,?,?,?::timestamptz,?::timestamptz,"
            + "'ACTIVE',?,?,1,?::timestamptz) "
            + "on conflict (tenant_id,cycle_code) do update set supplier_code = excluded.supplier_code,"
            + "currency_code = excluded.currency_code, cycle_kind = excluded.cycle_kind,"
            + "settle_day = excluded.settle_day, effective_from = excluded.effective_from,"
            + "effective_to = excluded.effective_to, note = excluded.note,"
            + "version = fulfillment_dropship_settlement_cycle.version + 1,"
            + "updated_at = excluded.updated_at",
        input.cycleCode(), input.supplierCode(), input.currencyCode(), kind, input.settleDay(),
        input.effectiveFrom(), input.effectiveTo(), blankToEmpty(input.note()), membershipId,
        OffsetDateTime.now());
    event("SETTLEMENT_CYCLE_REGISTERED", null, null, null, null, input.supplierCode(),
        "登记结算周期 " + input.cycleCode() + "（" + kind + " / " + input.currencyCode() + "）",
        membershipId);
    return cycleRow(
        sql.fetchOne(
            "select id,cycle_code,supplier_code,currency_code,cycle_kind,settle_day,"
                + "effective_from,effective_to,status,note,version "
                + "from fulfillment_dropship_settlement_cycle "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and cycle_code = ?",
            input.cycleCode()));
  }

  // ================================================================ 差价毛利计算

  /**
   * 跑一轮差价计算。
   *
   * <p>只有<b>成本已落定</b>的代发单（ACCEPTED / SHIPPED）才算差价：其余状态的成本
   * 还会变，此刻算出来的差价是假的。跳过的单数如实报出 —— 把「没算」和
   * 「算了不亏」混成一个数字，正是「看不出来到底漏没漏」的开始。
   *
   * <p>⚠️ 已写入的结论是<b>可重算</b>的（供应商回执改了成本、费率后补登记了，
   * 都要求重算）；DB 只在被**定稿对账单引用**之后才冻结它（见 V130 触发器 a）。
   */
  @Transactional
  public DropshipMarginEvaluateResult evaluateMarginFacts(
      OffsetDateTime periodStart, OffsetDateTime periodEnd, UUID membershipId) {
    if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
      throw bad("计算差价必须给出有效的周期区间（periodEnd 必须晚于 periodStart）");
    }
    long scanned =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_order "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and coalesce(accepted_at, created_at) "
                    + ">= ?::timestamptz and coalesce(accepted_at, created_at) < ?::timestamptz "
                    + "and status in (?,?)",
                prebind(new Object[] {periodStart, periodEnd}, List.of(statusBinds())))
            .get("c", Long.class);
    long skipped =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_order "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and coalesce(accepted_at, created_at) "
                    + ">= ?::timestamptz and coalesce(accepted_at, created_at) < ?::timestamptz "
                    + "and status not in (?,?)",
                prebind(new Object[] {periodStart, periodEnd}, List.of(statusBinds())))
            .get("c", Long.class);

    int computed =
        sql.execute(
            "insert into fulfillment_dropship_margin_fact (id,tenant_id,order_id,order_no,"
                + "sales_order_id,sales_order_no,shop_code,supplier_code,config_code,currency_code,"
                + "sell_amount,purchase_amount,supplier_shipping_amount,fee_registration_status,"
                + "platform_fee_rate_id,platform_fee_amount,loss_evidence,computed_at,"
                + "computed_by_membership_id,note,version,updated_at) "
                + "select gen_random_uuid(),o.tenant_id,o.id,o.order_no,o.sales_order_id,"
                + "o.sales_order_no,coalesce(c.shop_code,''),o.supplier_code,o.config_code,"
                + "o.currency_code,o.sell_amount,"
                + "coalesce(o.actual_purchase_amount,o.purchase_amount),"
                + "coalesce(o.actual_supplier_shipping_amount,o.supplier_shipping_amount),"
                + "case when fr.id is null then 'UNREGISTERED' else 'REGISTERED' end,fr.id,"
                // 平台费按**结算币种即代发单币种**匹配；固定额若币种不同则不匹配
                // （宁可判「未登记」，也不用 1.0 假装把两个币种混在一起）
                + "case when fr.id is null then null "
                + "when fr.charge_basis = 'PERCENT_OF_SELL' "
                + "then round(o.sell_amount * fr.rate_percent, 4) "
                + "else round(fr.fixed_amount, 4) end,"
                + "concat('售价 ', to_char(o.sell_amount,'FM9999999990.0000'), "
                + "' − 采购结算价 ', "
                + "to_char(coalesce(o.actual_purchase_amount,o.purchase_amount),'FM9999999990.0000'),"
                + "'（', case when o.actual_purchase_amount is null then '下单时冻结值' "
                + "else '供应商回执实际值' end, '）− 供应商运费 ', "
                + "to_char(coalesce(o.actual_supplier_shipping_amount,o.supplier_shipping_amount),"
                + "'FM9999999990.0000'), '；平台费 ', "
                + "case when fr.id is null then '**未登记**（5.2.3.1 未交付，判定为无法判定，不按 0 算）' "
                + "else concat('已按 ', fr.rate_code, ' 登记（', "
                + "case when fr.charge_basis='PERCENT_OF_SELL' "
                + "then concat('售价 × ', to_char(fr.rate_percent,'FM0.000000')) "
                + "else concat('每单固定 ', to_char(fr.fixed_amount,'FM9999999990.0000'), ' ', "
                + "fr.fixed_currency_code) end, '）') end, '；币种 ', o.currency_code), "
                + "now(),?::uuid,'',1,now() "
                + "from fulfillment_dropship_order o "
                + "join sales_order so on so.tenant_id = o.tenant_id and so.id = o.sales_order_id "
                + "left join fulfillment_dropship_config c "
                + "on c.tenant_id = o.tenant_id and c.id = o.config_id "
                + "left join lateral ( "
                + "   select fr.id, fr.rate_code, fr.charge_basis, fr.rate_percent, fr.fixed_amount, "
                + "          fr.fixed_currency_code "
                + "   from fulfillment_dropship_fee_rate fr "
                + "   where fr.tenant_id = o.tenant_id "
                + "     and fr.platform_code = so.order_source "
                + "     and fr.settlement_currency_code = o.currency_code "
                + "     and fr.effective_from <= coalesce(o.accepted_at, o.created_at) "
                + "     and (fr.effective_to is null "
                + "          or fr.effective_to > coalesce(o.accepted_at, o.created_at)) "
                + "     and (fr.charge_basis <> 'FIXED_PER_ORDER' "
                + "          or fr.fixed_currency_code = fr.settlement_currency_code) "
                + "   order by fr.effective_from desc limit 1) fr on true "
                + "where o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid "
                + "  and coalesce(o.accepted_at, o.created_at) >= ?::timestamptz "
                + "  and coalesce(o.accepted_at, o.created_at) < ?::timestamptz "
                + "  and o.status in (?,?) "
                + "on conflict (tenant_id, order_id) do update set "
                + "sell_amount = excluded.sell_amount, "
                + "purchase_amount = excluded.purchase_amount, "
                + "supplier_shipping_amount = excluded.supplier_shipping_amount, "
                + "fee_registration_status = excluded.fee_registration_status, "
                + "platform_fee_rate_id = excluded.platform_fee_rate_id, "
                + "platform_fee_amount = excluded.platform_fee_amount, "
                + "loss_evidence = excluded.loss_evidence, "
                + "computed_at = excluded.computed_at, "
                + "computed_by_membership_id = excluded.computed_by_membership_id, "
                + "version = fulfillment_dropship_margin_fact.version + 1, "
                + "updated_at = excluded.updated_at",
            prebind(
                new Object[] {membershipId, periodStart, periodEnd}, List.of(statusBinds())));

    long loss =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_margin_fact "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and loss_verdict = 'LOSS_CONFIRMED' "
                    + "and computed_at >= ?::timestamptz and computed_at <= now()",
                periodStart)
            .get("c", Long.class);
    long undetermined =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_margin_fact "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and loss_verdict = 'UNDETERMINED_NO_FEE' "
                    + "and computed_at >= ?::timestamptz and computed_at <= now()",
                periodStart)
            .get("c", Long.class);
    event("MARGIN_FACTS_EVALUATED", null, null, null, null, null,
        "差价计算：周期 " + periodStart + " ~ " + periodEnd + "，计算 " + computed + " 张，"
            + "跳过未落定 " + skipped + " 张", membershipId);
    return new DropshipMarginEvaluateResult(
        scanned, computed, (int) skipped, (int) loss, (int) undetermined);
  }

  public DropshipMarginFactPage pageFacts(DropshipMarginFactQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.orderNo())) {
      where.add("order_no ilike ?");
      binds.add("%" + query.orderNo().trim() + "%");
    }
    if (notBlank(query.lossVerdict())) {
      where.add("loss_verdict = ?");
      binds.add(lossVerdict(query.lossVerdict()).name());
    }
    if (notBlank(query.keyword())) {
      where.add("(order_no ilike ? or sales_order_no ilike ? or supplier_code ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_margin_fact" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    // 排序：先看有没有问题，再看时间。NOT_LESS 排最后 —— 它是「已判定不亏」，
    // 不该和有问题的行挤在同一屏的前面。
    List<DropshipMarginFactRow> items =
        sql.fetch(
                "select " + FACT_COLUMNS + " from fulfillment_dropship_margin_fact" + sqlWhere
                    + " order by case loss_verdict when 'LOSS_CONFIRMED' then 0 "
                    + "when 'UNDETERMINED_NO_FEE' then 1 else 2 end, computed_at desc "
                    + "limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipMarginService::factRow);
    return new DropshipMarginFactPage(items, total, pageNo, size);
  }

  // ================================================================ 差价毛利报表

  /**
   * 差价与毛利报表（按店铺 / 供应商 / SKU / 代发单）。
   *
   * <p>三条硬约束在这一条方法里同时生效：
   * <ol>
   *   <li><b>按经营模式分组</b>：每一行都带 {@code businessModel=DROPSHIP}
   *       与 {@code costBasis=PURCHASE_SPREAD}，下游不得并入自营口径。</li>
   *   <li><b>跨币种不相加</b>：分组键是 (维度, 币种)。同一家供应商同时用人民币和
   *       美元结算时会出来两行，而不是一个加起来的假数。</li>
   *   <li><b>未登记单列</b>：{@code undeterminedOrderCount} 与
   *       {@code platformFeeUnregisteredCountAware} 让「平台费没登记」在报表上
   *       看得见，而不是被一个 0 抹平。</li>
   * </ol>
   *
   * <p>⚠️ <b>SKU 维度不摊供应商运费与平台费</b>：这两项在代发单上是<b>单级</b>金额，
   * 摊到行上必须假设一个分摊规则，而那个假设会直接改变「哪个 SKU 在赚钱」的结论。
   * 因此 SKU 行只出该 SKU 自己的售价与采购差价，并在该行 note 里写明未摊。
   */
  public DropshipMarginReport report(
      String groupByRaw,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String supplierCode,
      String shopCode,
      String currencyCode) {
    if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
      throw bad("报表必须给出有效的周期区间（periodEnd 必须晚于 periodStart）");
    }
    DropshipReportGroupBy groupBy = reportGroupBy(groupByRaw);
    List<Object> binds = new ArrayList<>();
    binds.add(periodStart);
    binds.add(periodEnd);
    List<String> filters = new ArrayList<>();
    if (notBlank(supplierCode)) {
      filters.add("and o.supplier_code = ?");
      binds.add(supplierCode.trim());
    }
    if (notBlank(shopCode)) {
      filters.add("and coalesce(c.shop_code,'') = ?");
      binds.add(shopCode.trim());
    }
    if (notBlank(currencyCode)) {
      filters.add("and o.currency_code = ?");
      binds.add(currencyCode.trim().toUpperCase(Locale.ROOT));
    }
    String filterSql = String.join(" ", filters);

    List<DropshipMarginReportGroup> groups;
    if (groupBy == DropshipReportGroupBy.SKU) {
      groups = skuGroups(periodStart, periodEnd, filterSql, binds);
    } else {
      String dim =
          switch (groupBy) {
            case SHOP -> "coalesce(c.shop_code,'')";
            case SUPPLIER -> "o.supplier_code";
            default -> "o.order_no";
          };
      groups =
          sql.fetch(
                  "select " + dim + " as gkey, o.currency_code as ccy, count(*) as orders,"
                      + "sum(o.sell_amount) as sell,"
                      + "sum(coalesce(o.actual_purchase_amount,o.purchase_amount)) as pur,"
                      + "sum(coalesce(o.actual_supplier_shipping_amount,"
                      + "o.supplier_shipping_amount)) as ship,"
                      + "sum(case when f.fee_registration_status='UNREGISTERED' then 1 else 0 end)"
                      + " as fee_unreg,"
                      + "sum(case when f.loss_verdict='LOSS_CONFIRMED' then 1 else 0 end) as loss,"
                      + "sum(case when f.loss_verdict='UNDETERMINED_NO_FEE' then 1 else 0 end)"
                      + " as undet,"
                      + "sum(case when f.loss_verdict='NOT_LOSS' then 1 else 0 end) as notloss,"
                      + "sum(f.platform_fee_amount) as fee,"
                      + "sum(f.dropship_cost) as cost,"
                      + "sum(f.spread_before_fee) as spread_before,"
                      + "sum(f.spread_after_fee) as spread_after "
                      + "from fulfillment_dropship_order o "
                      + "join sales_order so on so.tenant_id=o.tenant_id and so.id=o.sales_order_id "
                      + "left join fulfillment_dropship_config c "
                      + "on c.tenant_id=o.tenant_id and c.id=o.config_id "
                      + "left join fulfillment_dropship_margin_fact f "
                      + "on f.tenant_id=o.tenant_id and f.order_id=o.id "
                      + "where o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid "
                      + "  and o.status in (?,?) "
                      + "  and coalesce(o.accepted_at,o.created_at) >= ?::timestamptz "
                      + "  and coalesce(o.accepted_at,o.created_at) < ?::timestamptz " + filterSql
                      + " group by " + dim + ", o.currency_code "
                      + " order by " + dim + ", o.currency_code",
                  prebind(statusBinds(), binds))
              .map(r -> orderLevelGroup(groupBy, r));
    }

    long totalOrders = groups.stream().mapToLong(DropshipMarginReportGroup::orderCount).sum();
    long totalUndetermined =
        groups.stream().mapToLong(DropshipMarginReportGroup::undeterminedOrderCount).sum();
    long totalLoss =
        groups.stream().mapToLong(DropshipMarginReportGroup::lossConfirmedOrderCount).sum();
    String feeNote =
        "平台费来自可登记的费率来源（5.2.3.1 未交付前由租户登记）；"
            + "未登记的单结论为「无法判定」，**不按 0 算** —— 把未登记当 0 会同时漏判真实亏损"
            + "并把所有代发单染成亏损。";
    String currencyNote =
        "金额按币种分组，**跨币种不相加**；需要合并时先登记汇率，折算口径与未登记桶见对账单。";
    return new DropshipMarginReport(
        groupBy.name(), "DROPSHIP", "PURCHASE_SPREAD", periodStart, periodEnd,
        notBlank(currencyCode) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "按币种分组",
        totalOrders, groups.size(), groups, BASIS_NOTE, feeNote, currencyNote);
  }

  /** 单级（店铺 / 供应商 / 代发单）分组：直接读差价事实的生成列，不另算。 */
  private static DropshipMarginReportGroup orderLevelGroup(
      DropshipReportGroupBy groupBy, Record r) {
    BigDecimal sell = nz(r.get("sell", BigDecimal.class));
    BigDecimal pur = nz(r.get("pur", BigDecimal.class));
    BigDecimal ship = nz(r.get("ship", BigDecimal.class));
    BigDecimal cost = nz(r.get("cost", BigDecimal.class));
    BigDecimal spreadBefore = nz(r.get("spread_before", BigDecimal.class));
    BigDecimal spreadAfter = r.get("spread_after", BigDecimal.class);
    BigDecimal fee = r.get("fee", BigDecimal.class);
    String key = r.get("gkey", String.class);
    return new DropshipMarginReportGroup(
        key, groupLabel(groupBy, key, r), groupBy.name(), "DROPSHIP", "PURCHASE_SPREAD",
        r.get("ccy", String.class),
        r.get("orders", Long.class), sell, pur, ship, fee,
        r.get("fee_unreg", Integer.class), cost, spreadBefore, spreadAfter, spreadBefore,
        r.get("undet", Integer.class), r.get("loss", Integer.class),
        r.get("notloss", Integer.class),
        marginRate(spreadAfter, sell), "");
  }

  /**
   * SKU 分组：只出该 SKU 自己的售价与采购差价。
   *
   * <p>供应商运费与平台费是<b>单级</b>金额，摊到 SKU 必须假设分摊规则 ——
   * 假设错一次，「哪个 SKU 在赚钱」的结论就整体反过来。宁可少给一个数，
   * 也不给一个看起来精确、实际是假设的数。
   */
  private List<DropshipMarginReportGroup> skuGroups(
      OffsetDateTime periodStart, OffsetDateTime periodEnd, String filterSql, List<Object> binds) {
    return sql.fetch(
            "select l.sku_ref as gkey, max(l.sku_name) as gname, o.currency_code as ccy,"
                + "count(distinct o.id) as orders, sum(l.quantity) as qty,"
                + "sum(l.sell_amount) as sell, sum(l.purchase_amount) as pur,"
                + "sum(l.sell_amount - l.purchase_amount) as spread_before "
                + "from fulfillment_dropship_order o "
                + "join sales_order so on so.tenant_id=o.tenant_id and so.id=o.sales_order_id "
                + "join fulfillment_dropship_order_line l "
                + "on l.tenant_id=o.tenant_id and l.order_id=o.id "
                + "left join fulfillment_dropship_config c "
                + "on c.tenant_id=o.tenant_id and c.id=o.config_id "
                + "where o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and o.status in (?,?) "
                + "  and coalesce(o.accepted_at,o.created_at) >= ?::timestamptz "
                + "  and coalesce(o.accepted_at,o.created_at) < ?::timestamptz " + filterSql
                + " group by l.sku_ref, o.currency_code order by l.sku_ref, o.currency_code",
            prebind(statusBinds(), binds))
        .map(
            r -> {
              String key = r.get("gkey", String.class);
              String label = blankToEmpty(r.get("gname", String.class));
              BigDecimal sell = nz(r.get("sell", BigDecimal.class));
              BigDecimal spreadBefore = nz(r.get("spread_before", BigDecimal.class));
              return new DropshipMarginReportGroup(
                  key,
                  label.isEmpty() ? key : label + "（" + key + "）",
                  DropshipReportGroupBy.SKU.name(), "DROPSHIP", "PURCHASE_SPREAD",
                  r.get("ccy", String.class),
                  r.get("orders", Long.class), sell, nz(r.get("pur", BigDecimal.class)), BigDecimal.ZERO, null,
                  0L, sell.subtract(nz(r.get("pur", BigDecimal.class))), spreadBefore, null, spreadBefore,
                  0L, 0L, 0L, null,
                  "SKU 维度不摊供应商运费与平台费：这两项在代发单上是单级金额，"
                      + "摊到行上必须假设分摊规则，而假设会直接改变「哪个 SKU 在赚钱」的结论");
            });
  }

  private static String groupLabel(DropshipReportGroupBy groupBy, String key, Record r) {
    return switch (groupBy) {
      case SHOP -> key.isEmpty() ? "（未登记店铺）" : key;
      case SUPPLIER -> key;
      default -> key;
    };
  }

  // ================================================================ 亏损预警

  /**
   * 跑一轮亏损预警检出。
   *
   * <p>两条判据，<b>不合并</b>：
   * <ol>
   *   <li>{@code LOSS_CONFIRMED} —— 已确认亏损（含「只按代发成本就已亏」这一支）。
   *       处置是停投 / 改售价 / 换供应商。</li>
   *   <li>{@code UNDETERMINED_NO_FEE} —— 平台费未登记，<b>无法判定</b>。
   *       处置是去登记费率。把它并进正常桶，这条待办就永远不会有人做，
   *       而真实亏损会一直躲在里面。</li>
   * </ol>
   *
   * <p>严重度按<b>证据完整度</b>给，不按金额大小发散：含平台费仍亏 = CRITICAL
   * （算全了还是亏），只按代发成本就亏 = WARNING（还没算平台费，实际可能更亏）。
   */
  @Transactional
  public DropshipMarginEvaluateResult evaluateLossAlerts(
      OffsetDateTime periodStart, OffsetDateTime periodEnd, UUID membershipId) {
    if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
      throw bad("检出亏损预警必须给出有效的周期区间（periodEnd 必须晚于 periodStart）");
    }
    long scanned =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_margin_fact "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and computed_at >= ?::timestamptz "
                    + "and computed_at < ?::timestamptz",
                periodStart, periodEnd)
            .get("c", Long.class);
    int loss =
        sql.execute(
            "insert into fulfillment_dropship_loss_alert (id,tenant_id,alert_no,margin_fact_id,"
                + "order_id,order_no,shop_code,supplier_code,currency_code,alert_kind,severity,"
                + "rule_code,rule_evidence,fee_registration_status,platform_fee_rate_id,"
                + "platform_fee_amount,sell_amount,dropship_cost,spread_before_fee,"
                + "spread_after_fee,period_start,period_end,status,detected_at) "
                + "select gen_random_uuid(),f.tenant_id,"
                + no("DLA", "dropship_margin_alert_no_seq")
                + ",f.id,f.order_id,f.order_no,f.shop_code,f.supplier_code,f.currency_code,"
                + "f.loss_verdict,"
                + "case when f.loss_verdict <> 'LOSS_CONFIRMED' then 'UNDETERMINED' "
                + "when f.spread_after_fee is not null then 'CRITICAL' else 'WARNING' end,"
                + "case when f.loss_verdict <> 'LOSS_CONFIRMED' then 'FEE_NOT_REGISTERED' "
                + "when f.spread_after_fee is not null then 'SELL_BELOW_COST_PLUS_FEE' "
                + "else 'SELL_BELOW_DROPSHIP_COST' end,"
                + "case when f.loss_verdict <> 'LOSS_CONFIRMED' "
                + "then concat('售价 ', to_char(f.sell_amount,'FM9999999990.0000'), ' ≥ 代发成本 ', "
                + "to_char(f.dropship_cost,'FM9999999990.0000'), "
                + "'，但平台费未登记（5.2.3.1 未交付）→ 真实结论既可能不亏也可能亏损，**无法判定**。"
                + "不做「不亏」结论，也不按 0 算') "
                + "when f.spread_after_fee is not null "
                + "then concat('售价 ', to_char(f.sell_amount,'FM9999999990.0000'), ' < 代发成本 ', "
                + "to_char(f.dropship_cost,'FM9999999990.0000'), ' + 平台费 ', "
                + "to_char(f.platform_fee_amount,'FM9999999990.0000'), '，含平台费差价 ', "
                + "to_char(f.spread_after_fee,'FM9999999990.0000'), '（已按登记费率计算）') "
                + "else concat('售价 ', to_char(f.sell_amount,'FM9999999990.0000'), ' < 代发成本 ', "
                + "to_char(f.dropship_cost,'FM9999999990.0000'), '，未含平台费差价 ', "
                + "to_char(f.spread_before_fee,'FM9999999990.0000'), "
                + "'（平台费恒 ≥ 0，只按代发成本就已亏损，结论成立且实际亏损只会更大）') end,"
                + "f.fee_registration_status,f.platform_fee_rate_id,f.platform_fee_amount,"
                + "f.sell_amount,f.dropship_cost,f.spread_before_fee,f.spread_after_fee,"
                + "?::timestamptz,?::timestamptz,'OPEN',now() "
                + "from fulfillment_dropship_margin_fact f "
                + "where f.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and f.computed_at >= ?::timestamptz "
                + "and f.computed_at < ?::timestamptz and f.loss_verdict <> 'NOT_LOSS' "
                + "and not exists (select 1 from fulfillment_dropship_loss_alert a "
                + "where a.margin_fact_id = f.id and a.alert_kind = f.loss_verdict "
                + "and a.status <> 'DISMISSED')",
            periodStart, periodEnd, periodStart, periodEnd);
    event("LOSS_ALERTS_EVALUATED", null, null, null, null, null,
        "亏损预警检出：周期 " + periodStart + " ~ " + periodEnd + "，扫 " + scanned
            + " 条，检出 " + loss + " 条", membershipId);
    long undetermined =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_loss_alert "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and status <> 'DISMISSED' "
                    + "and alert_kind = 'UNDETERMINED_NO_FEE'")
            .get("c", Long.class);
    return new DropshipMarginEvaluateResult(scanned, loss, 0, loss, (int) undetermined);
  }

  public DropshipLossAlertPage pageAlerts(DropshipLossAlertQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.alertKind())) {
      where.add("alert_kind = ?");
      binds.add(lossVerdict(query.alertKind()).name());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.severity())) {
      where.add("severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_loss_alert" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    // 两个计数分列在整个结果集上（不受当前筛选影响）：界面上要能同时看到
    // 「确认亏了多少」与「多少条因为没登记费率而判不了」，后者才是被漏掉的亏损。
    long lossConfirmed =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_loss_alert "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and status <> 'DISMISSED' "
                    + "and alert_kind = 'LOSS_CONFIRMED'")
            .get("c", Long.class);
    long undetermined =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_loss_alert "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and status <> 'DISMISSED' "
                    + "and alert_kind = 'UNDETERMINED_NO_FEE'")
            .get("c", Long.class);
    List<DropshipLossAlertRow> items =
        sql.fetch(
                "select " + ALERT_COLUMNS + " from fulfillment_dropship_loss_alert" + sqlWhere
                    + " order by case severity when 'CRITICAL' then 0 when 'WARNING' then 1 "
                    + "else 2 end, detected_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipMarginService::alertRow);
    return new DropshipLossAlertPage(items, total, lossConfirmed, undetermined, pageNo, size);
  }

  @Transactional
  public DropshipLossAlertRow acknowledgeAlert(
      UUID alertId, DropshipLossAlertAckInput input, UUID membershipId) {
    if (input == null || !notBlank(input.resolutionNote())) {
      throw bad("处置亏损预警必须写明依据：没有依据的「已处理」和没处理过没有区别");
    }
    requireAlert(alertId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_loss_alert set status = case when ? then 'RESOLVED' "
            + "else 'ACKNOWLEDGED' end, acknowledged_at = coalesce(acknowledged_at, ?::timestamptz),"
            + "acknowledged_by_membership_id = coalesce(acknowledged_by_membership_id, ?),"
            + "resolution_note = ?, "
            + "resolved_at = case when ? then ?::timestamptz else resolved_at end,"
            + "resolved_by_membership_id = case when ? then ? else resolved_by_membership_id end "
            + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
        input.resolved(), now, membershipId, input.resolutionNote().trim(),
        input.resolved(), now, input.resolved(), membershipId, alertId);
    event("LOSS_ALERT_ACKNOWLEDGED", null, null, alertId, null, null,
        (input.resolved() ? "处置并结案：" : "已确认待处置：") + input.resolutionNote().trim(),
        membershipId);
    return alertRow(
        sql.fetchOne(
            "select " + ALERT_COLUMNS + " from fulfillment_dropship_loss_alert "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            alertId));
  }

  /** 判为误报。必须写明理由 —— 否则下一次同类判定会一并被放过。 */
  @Transactional
  public DropshipLossAlertRow dismissAlert(
      UUID alertId, DropshipLossAlertDismissInput input, UUID membershipId) {
    if (input == null || !notBlank(input.reason())) {
      throw bad("判为误报必须写明理由：没有理由的误报会让下一次同类判定也一并被放过");
    }
    requireAlert(alertId);
    sql.execute(
        "update fulfillment_dropship_loss_alert set status = 'DISMISSED',"
            + "acknowledged_at = coalesce(acknowledged_at, ?::timestamptz),"
            + "acknowledged_by_membership_id = coalesce(acknowledged_by_membership_id, ?),"
            + "resolution_note = ? where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
        OffsetDateTime.now(), membershipId, input.reason().trim(), alertId);
    event("LOSS_ALERT_DISMISSED", null, null, alertId, null, null,
        "判为误报：" + input.reason().trim(), membershipId);
    return alertRow(
        sql.fetchOne(
            "select " + ALERT_COLUMNS + " from fulfillment_dropship_loss_alert "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            alertId));
  }

  // ================================================================ 对账单

  /**
   * 生成一张供应商对账单（周期由调用方显式给出）。
   *
   * <p>三条纪律在这一条方法里同时生效：
   * <ol>
   *   <li><b>周期端点显式 pin 住</b>，不从墙上时钟推导。</li>
   *   <li><b>明细行是冻结快照</b>：把差价事实的构成项<b>拷贝</b>进来，
   *       事后改费率改不动历史对账单（DB 另有行不可改触发器兜底）。</li>
   *   <li><b>汇率未登记 → 该行整组折算列为 NULL</b>，被 {@code is_in_totals}
   *       排除在合计外，单独进未登记桶；<b>不用 1.0 假装</b>。</li>
   * </ol>
   */
  @Transactional
  public DropshipReconciliationRow buildReconciliation(
      DropshipReconciliationInput input, UUID membershipId) {
    if (input == null) {
      throw bad("生成对账单必须提供完整内容");
    }
    if (!input.periodEnd().isAfter(input.periodStart())) {
      throw bad("对账周期的结束时间必须晚于开始时间");
    }
    // 结算周期：找到就带上，找不到**如实**说明而不是假装有周期
    Record cycle =
        sql.fetchOne(
            "select id, cycle_kind from fulfillment_dropship_settlement_cycle "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and supplier_code = ? and currency_code = ? "
                + "and status = 'ACTIVE' and effective_from <= ?::timestamptz "
                + "and (effective_to is null or effective_to > ?::timestamptz) "
                + "order by effective_from desc limit 1",
            input.supplierCode(), input.currencyCode(), input.periodStart(), input.periodStart());
    UUID cycleId = cycle == null ? null : cycle.get("id", UUID.class);
    String cycleKind = cycle != null
        ? cycle.get("cycle_kind", String.class)
        : (notBlank(input.cycleKind())
            ? input.cycleKind().trim().toUpperCase(Locale.ROOT)
            : "MONTHLY");
    String cycleNote = cycle == null
        ? "（该供应商**未登记结算周期**，本单按调用方给出的周期区间生成）"
        : "";

    UUID existing =
        sql.fetchOptional(
                "select id from fulfillment_dropship_reconciliation "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and supplier_code = ? and period_start = ?::timestamptz "
                    + "and period_end = ?::timestamptz and currency_code = ?",
                input.supplierCode(), input.periodStart(), input.periodEnd(), input.currencyCode())
            .map(r -> r.get("id", UUID.class))
            .orElse(null);
    if (existing != null) {
      throw bad("该供应商该周期已有一张对账单（"
          + sql.fetchOne("select statement_no from fulfillment_dropship_reconciliation "
              + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid", existing)
              .get("statement_no", String.class)
          + "）：同一供应商同一周期只允许一张，两个答案会让「这个月该付多少」没有定论");
    }

    UUID statementId = UUID.randomUUID();
    // 先落行、再落头（头尾合计的约束触发器是 DEFERRABLE 的，顺序不影响校验）
    sql.execute(
        "insert into fulfillment_dropship_reconciliation_line (id,tenant_id,statement_id,order_id,"
            + "order_no,sales_order_no,shop_code,supplier_code,period_start,period_end,"
            + "original_currency_code,statement_currency_code,sell_amount,purchase_amount,"
            + "supplier_shipping_amount,fee_registration_status,platform_fee_rate_id,"
            + "platform_fee_amount,loss_verdict,fx_rate_status,fx_rate_value,fx_rate_id,"
            + "sell_amount_in_statement,purchase_amount_in_statement,"
            + "supplier_shipping_in_statement,platform_fee_in_statement,"
            + "dropship_cost_in_statement,spread_in_statement,payable_in_statement,"
            + "rule_evidence) "
            + "select gen_random_uuid(),f.tenant_id,?::uuid,f.order_id,f.order_no,f.sales_order_no,"
            + "f.shop_code,f.supplier_code,?::timestamptz,?::timestamptz,f.currency_code,?,"
            + "f.sell_amount,f.purchase_amount,f.supplier_shipping_amount,"
            + "f.fee_registration_status,f.platform_fee_rate_id,f.platform_fee_amount,"
            + "f.loss_verdict,"
            + "case when f.currency_code = ? then 'SAME_CURRENCY' "
            + "when fx.id is null then 'UNSET' else 'APPLIED' end,"
            + "case when f.currency_code = ? or fx.id is null then null else fx.rate_value end,"
            + "case when f.currency_code = ? then null else fx.id end,"
            + "case when f.currency_code = ? then f.sell_amount "
            + "when fx.id is null then null "
            + "else round(f.sell_amount * fx.rate_value, 4) end,"
            + "case when f.currency_code = ? then f.purchase_amount "
            + "when fx.id is null then null "
            + "else round(f.purchase_amount * fx.rate_value, 4) end,"
            + "case when f.currency_code = ? then f.supplier_shipping_amount "
            + "when fx.id is null then null "
            + "else round(f.supplier_shipping_amount * fx.rate_value, 4) end,"
            + "case when f.platform_fee_amount is null then null "
            + "when f.currency_code = ? then f.platform_fee_amount "
            + "when fx.id is null then null "
            + "else round(f.platform_fee_amount * fx.rate_value, 4) end,"
            + "case when f.currency_code = ? then f.dropship_cost "
            + "when fx.id is null then null "
            + "else round(f.dropship_cost * fx.rate_value, 4) end,"
            + "case when f.spread_after_fee is null then null "
            + "when f.currency_code = ? then f.spread_after_fee "
            + "when fx.id is null then null "
            + "else round(f.spread_after_fee * fx.rate_value, 4) end,"
            + "case when f.currency_code = ? then f.purchase_amount + f.supplier_shipping_amount "
            + "when fx.id is null then null "
            + "else round((f.purchase_amount + f.supplier_shipping_amount) * fx.rate_value, 4) end,"
            + "concat(f.loss_evidence, '｜折算：', "
            + "case when f.currency_code = ? then concat('原币即结算币（', f.currency_code, '）') "
            + "when fx.id is null then concat('**汇率未登记**，无法折算到 ', ?, "
            + "'—— 本行不计入合计，单独进未登记桶，不用 1.0 假装') "
            + "else concat('按汇率 1 ', f.currency_code, ' = ', "
            + "to_char(fx.rate_value,'FM0.00000000'), ' ', ?, ' 折算') end) "
            + "from fulfillment_dropship_margin_fact f "
            + "left join lateral (select id, rate_value from fulfillment_dropship_fx_rate x "
            + "where x.tenant_id = f.tenant_id and x.base_currency = f.currency_code "
            + "and x.quote_currency = ? and x.effective_from <= ?::timestamptz "
            + "and (x.effective_to is null or x.effective_to > ?::timestamptz) "
            + "order by x.effective_from desc limit 1) fx on true "
            + "where f.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and f.supplier_code = ? "
            + "and f.computed_at >= ?::timestamptz and f.computed_at < ?::timestamptz",
        reconLineBinds(statementId, input));

    // 头：合计只对 is_in_totals 的行求和（汇率 UNSET 的行被排除）
    Record sums =
        sql.fetchOne(
            "select count(*) as c, "
                + "coalesce(sum(case when loss_verdict='LOSS_CONFIRMED' then 1 else 0 end),0) as l, "
                + "coalesce(sum(case when fee_registration_status='UNREGISTERED' "
                + "then 1 else 0 end),0) as fu, "
                + "coalesce(sum(sell_amount_in_statement),0) as s, "
                + "coalesce(sum(purchase_amount_in_statement),0) as p, "
                + "coalesce(sum(supplier_shipping_in_statement),0) as sh, "
                + "coalesce(sum(coalesce(platform_fee_in_statement,0)),0) as fe, "
                + "coalesce(sum(dropship_cost_in_statement),0) as dc, "
                + "coalesce(sum(spread_in_statement),0) as sp, "
                + "coalesce(sum(payable_in_statement),0) as pay "
                + "from fulfillment_dropship_reconciliation_line "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and statement_id = ?::uuid and is_in_totals",
            statementId);
    Record undet =
        sql.fetchOne(
            "select count(*) as c, coalesce(sum(purchase_amount + supplier_shipping_amount),0) as p "
                + "from fulfillment_dropship_reconciliation_line "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and statement_id = ?::uuid "
                + "and not is_in_totals",
            statementId);
    int undeterminedCount = undet.get("c", Integer.class);
    BigDecimal undeterminedPayable = nz(undet.get("p", BigDecimal.class));
    int feeUnreg = sums.get("fu", Integer.class);
    BigDecimal totalFee = nz(sums.get("fe", BigDecimal.class));

    // 头的折算口径按**行**实测得出，不靠猜：有任何一行 UNSET 就是 UNSET；
    // 全程同一个汇率才 APPLIED —— 多币种混用时头侧留空，每一行各自留证。
    String fxStatus = "SAME_CURRENCY";
    if (undeterminedCount > 0) {
      fxStatus = "UNSET";
    } else {
      long applied =
          sql.fetchOne(
                  "select count(*) c from fulfillment_dropship_reconciliation_line "
                      + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')"
                      + "::uuid and statement_id = ?::uuid and fx_rate_status = 'APPLIED'",
                  statementId)
              .get("c", Long.class);
      if (applied > 0) {
        fxStatus = "APPLIED";
      }
    }
    UUID headerFxId = null;
    BigDecimal headerFxValue = null;
    if ("APPLIED".equals(fxStatus)) {
      Record one =
          sql.fetchOne(
              "select fx_rate_id, fx_rate_value from fulfillment_dropship_reconciliation_line "
                  + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid "
                  + "and statement_id = ?::uuid and fx_rate_status = 'APPLIED' "
                  + "limit 1",
              statementId);
      if (one != null) {
        headerFxId = one.get("fx_rate_id", UUID.class);
        headerFxValue = one.get("fx_rate_value", BigDecimal.class);
      }
    }

    String evidence =
        "应付供应商 = 采购结算价 + 供应商运费"
            + (undeterminedCount > 0
                ? "；⚠️ 本单有 " + undeterminedCount + " 行**汇率未登记**，已排除在合计外"
                    + "（未登记应付 " + undeterminedPayable.toPlainString() + "，按各自原币计）"
                : "")
            + "；⚠️ 本单有 " + feeUnreg + " 行**平台费未登记**，total_platform_fee "
            + totalFee.toPlainString() + " 不含这 " + feeUnreg + " 行的平台费"
                + "（未登记不等于 0）"
            + "；计价口径 " + cycleKind + cycleNote;

    sql.execute(
        "insert into fulfillment_dropship_reconciliation (id,tenant_id,statement_no,supplier_code,"
            + "period_start,period_end,cycle_kind,settlement_cycle_id,currency_code,business_model,"
            + "cost_basis,status,order_count,loss_order_count,total_sell_amount,"
            + "total_purchase_amount,total_supplier_shipping,total_platform_fee,"
            + "total_dropship_cost,total_spread,total_payable,undetermined_order_count,"
            + "undetermined_payable,fee_unregistered_order_count,fx_rate_status,fx_rate_value,"
            + "fx_rate_id,rule_evidence,basis_note,created_by_membership_id,version,created_at,"
            + "updated_at) values (?::uuid,nullif(current_setting('app.current_tenant',true),'')::uuid,"
            + no("DRC", "dropship_reconciliation_no_seq")
            + ",?,?::timestamptz,?::timestamptz,?,?::uuid,?,'DROPSHIP','PURCHASE_SPREAD','DRAFT',"
            + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid,1,now(),now())",
        statementId, input.supplierCode(), input.periodStart(), input.periodEnd(), cycleKind,
        cycleId, input.currencyCode(), sums.get("c", Integer.class), sums.get("l", Integer.class),
        sums.get("s", BigDecimal.class), sums.get("p", BigDecimal.class), sums.get("sh", BigDecimal.class), sums.get("fe", BigDecimal.class), sums.get("dc", BigDecimal.class),
        sums.get("sp", BigDecimal.class), sums.get("pay", BigDecimal.class), undeterminedCount, undeterminedPayable, feeUnreg,
        fxStatus, headerFxValue, headerFxId, evidence, BASIS_NOTE, membershipId);

    event("RECONCILIATION_BUILT", null, statementId, null, null, input.supplierCode(),
        "生成对账单：" + input.supplierCode() + " " + input.periodStart() + " ~ "
            + input.periodEnd() + "，应付 " + sums.get("pay", BigDecimal.class), membershipId);

    // 应付待办：真待办，不是假结果（幂等键按对账单 id，重复提交不会造出第二条）
    String idempotencyKey = "SUPPLIER_PAYABLE:" + statementId;
    sql.execute(
        "insert into fulfillment_dropship_payable_task (id,tenant_id,task_no,task_kind,"
            + "target_system,task_status,statement_id,statement_no,supplier_code,payable_amount,"
            + "payable_currency_code,payload,idempotency_key,attempt_count,created_at,updated_at) "
            + "select gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,"
            + no("DPT", "dropship_payable_task_no_seq")
            + ",'SUPPLIER_PAYABLE','FINANCE','OPEN',s.id,s.statement_no,s.supplier_code,"
            + "s.total_payable,s.currency_code,?::jsonb,?,0,now(),now() "
            + "from fulfillment_dropship_reconciliation s "
            + "where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and s.id = ?::uuid "
            + "on conflict (tenant_id,idempotency_key) do nothing",
        jsonPayload(statementId), idempotencyKey, statementId);

    return reconciliationRow(
        sql.fetchOne(
            "select " + STATEMENT_COLUMNS + " from fulfillment_dropship_reconciliation "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            statementId));
  }

  public DropshipReconciliationPage pageReconciliations(DropshipReconciliationQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.currencyCode())) {
      where.add("currency_code = ?");
      binds.add(query.currencyCode().trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_reconciliation" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipReconciliationRow> items =
        sql.fetch(
                "select " + STATEMENT_COLUMNS + " from fulfillment_dropship_reconciliation"
                    + sqlWhere
                    + " order by period_start desc, created_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipMarginService::reconciliationRow);
    return new DropshipReconciliationPage(items, total, pageNo, size);
  }

  /** 对账单详情：头 + 冻结明细行。跨租户 → 404。 */
  public DropshipReconciliationDetail reconciliationDetail(UUID statementId) {
    Record head =
        sql.fetchOne(
            "select " + STATEMENT_COLUMNS + " from fulfillment_dropship_reconciliation "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            statementId);
    if (head == null) {
      throw notFound("对账单不存在或不属于当前租户：" + statementId);
    }
    List<DropshipReconciliationLineRow> lines =
        sql.fetch(
                "select " + LINE_COLUMNS + " from fulfillment_dropship_reconciliation_line "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and statement_id = ?::uuid "
                    + "order by order_no",
                statementId)
            .map(DropshipMarginService::lineRow);
    DropshipReconciliationRow row = reconciliationRow(head);
    String fxNote = "折算口径：" + fxStatusLabel(row.fxStatus())
        + (row.undeterminedOrderCount() > 0
            ? "；⚠️ " + row.undeterminedOrderCount() + " 行**汇率未登记**，已排除在合计外"
                + "（未登记应付 " + row.undeterminedPayable().toPlainString() + "，按各自原币计）"
            : "")
        + (row.feeUnregisteredOrderCount() > 0
            ? "；⚠️ " + row.feeUnregisteredOrderCount() + " 行**平台费未登记**，"
                + "total_platform_fee 不含这部分的平台费（未登记不等于 0）"
            : "");
    String taskNote =
        "应付单（12.1.2.1）未交付：本单只生成真待办（task_status=OPEN），"
            + "「已交给下游」与「下游已受理」是两个状态，下游接手前不显示为已办。";
    return new DropshipReconciliationDetail(row, lines, lines.size(), fxNote, taskNote);
  }

  /**
   * 对账单状态流转。
   *
   * <p>「有争议」可以再回到 FINALIZED（供应商补齐材料后继续推进），而 CLOSED 是终态 ——
   * 供应商说「这单我收 13」时，要能把已关闭的单拉出来重开，而不是新建一张把历史丢掉。
   */
  @Transactional
  public DropshipReconciliationRow changeStatementStatus(
      UUID statementId, DropshipReconciliationStatusInput input, UUID membershipId) {
    if (input == null || !notBlank(input.status())) {
      throw bad("必须给出目标状态");
    }
    DropshipStatementStatus target = statementStatus(input.status());
    if (target == DropshipStatementStatus.DRAFT) {
      throw bad("对账单不能回到草稿态：已离开草稿的期间已被下游看到过");
    }
    if (target == DropshipStatementStatus.DISPUTED && !notBlank(input.disputeNote())) {
      throw bad("置为有争议必须写明争议内容：否则「有争议」三个字没人能处理");
    }
    requireStatement(statementId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_reconciliation set status = ?,"
            + "finalized_at = case when ? = 'FINALIZED' and finalized_at is null "
            + "then ?::timestamptz else finalized_at end,"
            + "finalized_by_membership_id = case when ? = 'FINALIZED' "
            + "and finalized_by_membership_id is null then ? else finalized_by_membership_id end,"
            + "closed_at = case when ? = 'CLOSED' then ?::timestamptz else closed_at end,"
            + "dispute_note = case when ? = 'DISPUTED' then ? else dispute_note end,"
            + "version = version + 1, updated_at = ?::timestamptz "
            + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
        // 11 个占位符：status / finalized_at 的条件+时刻 / finalized_by 的条件+操作人 /
        // closed_at 的条件+时刻 / dispute_note 的条件+说明 / updated_at / id
        target.name(), target.name(), now, target.name(), membershipId, target.name(), now,
        target.name(), input.disputeNote(), now, statementId);
    event("RECONCILIATION_STATUS", null, statementId, null, null, null,
        "对账单状态 → " + target.label(), membershipId);
    return reconciliationRow(
        sql.fetchOne(
            "select " + STATEMENT_COLUMNS + " from fulfillment_dropship_reconciliation "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            statementId));
  }

  // ================================================================ 应付待办

  public DropshipPayableTaskPage pageTasks(DropshipPayableTaskQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.taskStatus())) {
      where.add("task_status = ?");
      binds.add(query.taskStatus().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_payable_task" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipPayableTaskRow> items =
        sql.fetch(
                "select " + TASK_COLUMNS + " from fulfillment_dropship_payable_task" + sqlWhere
                    + " order by case task_status when 'FAILED' then 0 when 'OPEN' then 1 "
                    + "when 'CLAIMED' then 2 when 'SENT' then 3 else 4 end, created_at desc "
                    + "limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(r -> taskRow(r));
    return new DropshipPayableTaskPage(items, total, pageNo, size);
  }

  /** 下游领取（OPEN → CLAIMED）。 */
  @Transactional
  public DropshipPayableTaskRow claimTask(UUID taskId, UUID membershipId) {
    requireTask(taskId);
    sql.execute(
        "update fulfillment_dropship_payable_task set task_status = 'CLAIMED',"
            + "claimed_by_membership_id = ?, claimed_at = ?::timestamptz, "
            + "attempt_count = attempt_count + 1, last_attempt_at = ?::timestamptz, "
            + "last_error = '', updated_at = ?::timestamptz "
            + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
        membershipId, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), taskId);
    event("PAYABLE_TASK_CLAIMED", null, null, null, taskId, null,
        "下游领取应付待办", membershipId);
    return taskRow(
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from fulfillment_dropship_payable_task "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            taskId));
  }

  /**
   * 下游回执：{@code SENT}（已交给下游）/ {@code ACKNOWLEDGED}（下游已受理）/ {@code FAILED}。
   *
   * <p>只有走到 {@code ACKNOWLEDGED}，对账单的应付才算真的有人接了 ——
   * 「已生成应付单」必须有一个下游真的接手过它，否则只能是待办。
   */
  @Transactional
  public DropshipPayableTaskRow completeTask(
      UUID taskId, DropshipPayableTaskResultInput input, UUID membershipId) {
    if (input == null || !notBlank(input.result())) {
      throw bad("必须给出下游回执结果（SENT / ACKNOWLEDGED / FAILED）");
    }
    String result = input.result().trim().toUpperCase(Locale.ROOT);
    if (!List.of("SENT", "ACKNOWLEDGED", "FAILED").contains(result)) {
      throw bad("下游回执结果只能是 SENT / ACKNOWLEDGED / FAILED，收到：" + result);
    }
    if ("FAILED".equals(result) && !notBlank(input.note())) {
      throw bad("下游回执为失败必须写明失败原因：没有原因的失败会让人以为已经处理过");
    }
    requireTask(taskId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_payable_task set task_status = ?,"
            + "sent_at = case when ? in ('SENT','ACKNOWLEDGED') and sent_at is null "
            + "then ?::timestamptz else sent_at end,"
            + "acknowledged_at = case when ? = 'ACKNOWLEDGED' then ?::timestamptz "
            + "else acknowledged_at end,"
            + "finished_at = case when ? in ('ACKNOWLEDGED','FAILED') then ?::timestamptz "
            + "else finished_at end,"
            + "last_error = case when ? = 'FAILED' then ? else '' end,"
            + "last_attempt_at = ?::timestamptz, attempt_count = attempt_count + 1, "
            + "updated_at = ?::timestamptz where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
        result, result, now, result, now, result, now, result,
        blankToEmpty(input.note()), now, now, taskId);
    event("PAYABLE_TASK_RESULT", null, null, null, taskId, null,
        "应付待办回执 → " + result, membershipId);
    return taskRow(
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from fulfillment_dropship_payable_task "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            taskId));
  }

  // ================================================================ 事件流

  public DropshipMarginEventPage pageEvents(UUID statementId, int page, int size) {
    int sizeNo = pageSize(size);
    int pageNo = Math.max(0, page);
    List<Object> binds = new ArrayList<>();
    String where = "";
    if (statementId != null) {
      where = " where statement_id = ?";
      binds.add(statementId);
    }
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_margin_event" + where,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipMarginEventRow> items =
        sql.fetch(
                "select " + EVENT_COLUMNS + " from fulfillment_dropship_margin_event" + where
                    + " order by occurred_at desc, id desc limit ? offset ?",
                append(binds, sizeNo, (long) pageNo * sizeNo))
            .map(r -> new DropshipMarginEventRow(
                r.get("id", UUID.class), r.get("event_kind", String.class),
                r.get("fact_id", UUID.class), r.get("statement_id", UUID.class),
                r.get("alert_id", UUID.class), r.get("task_id", UUID.class),
                blankToEmpty(r.get("supplier_code", String.class)),
                blankToEmpty(r.get("detail", String.class)),
                r.get("occurred_at", OffsetDateTime.class)));
    return new DropshipMarginEventPage(items, total, pageNo, sizeNo);
  }

  // ================================================================ 内部

  private String jsonPayload(UUID statementId) {
    Record s =
        sql.fetchOne(
            "select statement_no, supplier_code, currency_code, period_start, period_end,"
                + "total_payable, total_sell_amount, total_spread, order_count,"
                + "undetermined_order_count from fulfillment_dropship_reconciliation "
                + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
            statementId);
    DropshipPayablePayload payload =
        new DropshipPayablePayload(
            s.get("statement_no", String.class), s.get("supplier_code", String.class),
            "DROPSHIP", "PURCHASE_SPREAD", s.get("currency_code", String.class),
            s.get("period_start", OffsetDateTime.class),
            s.get("period_end", OffsetDateTime.class),
            s.get("total_payable", BigDecimal.class), s.get("total_sell_amount", BigDecimal.class),
            s.get("total_spread", BigDecimal.class), s.get("order_count", Integer.class),
            s.get("undetermined_order_count", Integer.class), BASIS_NOTE);
    try {
      return json.writeValueAsString(payload);
    } catch (Exception e) {
      throw bad("应付待办载荷序列化失败：" + e.getMessage());
    }
  }

  private void event(
      String kind, UUID factId, UUID statementId, UUID alertId, UUID taskId, String supplierCode,
      String detail, UUID membershipId) {
    sql.execute(
        "insert into fulfillment_dropship_margin_event (id,tenant_id,event_kind,fact_id,"
            + "statement_id,alert_id,task_id,supplier_code,detail,actor_membership_id,occurred_at) "
            + "values (gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,"
            + "now())",
        kind, factId, statementId, alertId, taskId, blankToEmpty(supplierCode),
        blankToEmpty(detail), membershipId);
  }

  private static String no(String prefix, String sequence) {
    return "'" + prefix + "-' || to_char(now(),'YYYYMMDD') || '-'"
        + " || lpad(nextval('" + sequence + "')::text,6,'0')";
  }

  /**
   * 对账明细插入的绑定序列 —— **顺序必须与 SQL 占位符逐位对应**。
   *
   * <p>顺序：statement_id → period_start / period_end → 结算币种 ×15（各折算列、
   * 折算状态、算式文案、lateral 的 quote_currency）→ lateral 生效窗口两端 →
   * supplier_code → 计算时刻窗口两端。写成一个方法而不是一长串裸实参，是因为这里
   * 错一位**不会编译失败**，只会在运行期报「invalid input syntax for timestamp」。
   */
  private static Object[] reconLineBinds(UUID statementId, DropshipReconciliationInput input) {
    List<Object> binds = new ArrayList<>();
    binds.add(statementId);
    binds.add(input.periodStart());
    binds.add(input.periodEnd());
    for (int i = 0; i < 15; i++) {
      binds.add(input.currencyCode());
    }
    binds.add(input.periodEnd());
    binds.add(input.periodEnd());
    binds.add(input.supplierCode());
    binds.add(input.periodStart());
    binds.add(input.periodEnd());
    return binds.toArray();
  }


  /** 展开数组绑定：jOOQ 不会把 {@code String[]} 绑成 PG 数组类型，必须逐个摊平。 */
  private Object[] prebind(Object[] head, List<Object> rest) {
    List<Object> all = new ArrayList<>();
    for (Object o : head) {
      if (o instanceof Object[] arr) {
        all.addAll(List.of(arr));
      } else {
        all.add(o);
      }
    }
    all.addAll(rest);
    return all.toArray();
  }

  /** 把状态词表摊平成 {@code in (?,?)} 的绑定值。 */
  private static Object[] statusBinds() {
    return SETTLED_ORDER_STATUSES.toArray();
  }

  private void requireAlert(UUID id) {
    if (id == null
        || sql.fetchOne(
                "select count(*) c from fulfillment_dropship_loss_alert "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
                id)
            .get("c", Integer.class)
            == 0) {
      throw notFound("亏损预警不存在或不属于当前租户：" + id);
    }
  }

  private void requireStatement(UUID id) {
    if (id == null
        || sql.fetchOne(
                "select count(*) c from fulfillment_dropship_reconciliation "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
                id)
            .get("c", Integer.class)
            == 0) {
      throw notFound("对账单不存在或不属于当前租户：" + id);
    }
  }

  private void requireTask(UUID id) {
    if (id == null
        || sql.fetchOne(
                "select count(*) c from fulfillment_dropship_payable_task "
                    + "where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?::uuid",
                id)
            .get("c", Integer.class)
            == 0) {
      throw notFound("应付待办不存在或不属于当前租户：" + id);
    }
  }

  // ------------------------------------------------------------ 参数解析

  private static DropshipLossVerdict lossVerdict(String raw) {
    try {
      return DropshipLossVerdict.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的亏损判定：" + raw + "（可选 "
          + Arrays.toString(DropshipLossVerdict.values()) + "）");
    }
  }

  private static DropshipReportGroupBy reportGroupBy(String raw) {
    String value = notBlank(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "SUPPLIER";
    try {
      return DropshipReportGroupBy.valueOf(value);
    } catch (RuntimeException e) {
      throw bad("未知的报表分组维度：" + raw + "（可选 "
          + Arrays.toString(DropshipReportGroupBy.values()) + "）");
    }
  }

  private static DropshipStatementStatus statementStatus(String raw) {
    try {
      return DropshipStatementStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的对账单状态：" + raw + "（可选 "
          + Arrays.toString(DropshipStatementStatus.values()) + "）");
    }
  }

  private static String fxStatusLabel(String status) {
    try {
      return DropshipFxStatus.valueOf(status).label();
    } catch (RuntimeException e) {
      return status;
    }
  }

  private static int pageSize(int size) {
    return size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static Object[] append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return notBlank(value) ? value.trim() : null;
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal marginRate(BigDecimal spread, BigDecimal sell) {
    if (spread == null || sell == null || sell.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return spread.divide(sell, 6, RoundingMode.HALF_UP);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  // ------------------------------------------------------------ 行映射

  private static DropshipFeeRateRow feeRateRow(Record r) {
    return new DropshipFeeRateRow(
        r.get("id", UUID.class), r.get("rate_code", String.class),
        r.get("rate_name", String.class), r.get("platform_code", String.class),
        r.get("fee_type", String.class), r.get("charge_basis", String.class),
        r.get("rate_percent", BigDecimal.class), r.get("fixed_amount", BigDecimal.class),
        r.get("fixed_currency_code", String.class),
        r.get("settlement_currency_code", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class), r.get("source", String.class),
        r.get("observed_at", OffsetDateTime.class), r.get("note", String.class),
        r.get("version", Integer.class));
  }

  private static DropshipFxRateRow fxRateRow(Record r) {
    return new DropshipFxRateRow(
        r.get("id", UUID.class), r.get("base_currency", String.class),
        r.get("quote_currency", String.class), r.get("rate_value", BigDecimal.class),
        r.get("effective_from", OffsetDateTime.class), r.get("effective_to", OffsetDateTime.class),
        r.get("source", String.class), r.get("observed_at", OffsetDateTime.class),
        r.get("note", String.class), r.get("version", Integer.class));
  }

  private static DropshipSettlementCycleRow cycleRow(Record r) {
    return new DropshipSettlementCycleRow(
        r.get("id", UUID.class), r.get("cycle_code", String.class),
        r.get("supplier_code", String.class), r.get("currency_code", String.class),
        r.get("cycle_kind", String.class), r.get("settle_day", Integer.class),
        r.get("effective_from", OffsetDateTime.class), r.get("effective_to", OffsetDateTime.class),
        r.get("status", String.class), r.get("note", String.class),
        r.get("version", Integer.class));
  }

  private static DropshipMarginFactRow factRow(Record r) {
    String verdict = r.get("loss_verdict", String.class);
    return new DropshipMarginFactRow(
        r.get("id", UUID.class), r.get("order_id", UUID.class),
        r.get("order_no", String.class), r.get("sales_order_no", String.class),
        blankToEmpty(r.get("shop_code", String.class)), r.get("supplier_code", String.class),
        r.get("currency_code", String.class), r.get("business_model", String.class),
        r.get("cost_basis", String.class), r.get("sell_amount", BigDecimal.class),
        r.get("purchase_amount", BigDecimal.class),
        r.get("supplier_shipping_amount", BigDecimal.class),
        r.get("dropship_cost", BigDecimal.class), r.get("spread_before_fee", BigDecimal.class),
        r.get("platform_fee_amount", BigDecimal.class),
        r.get("spread_after_fee", BigDecimal.class), r.get("margin_rate", BigDecimal.class),
        r.get("fee_registration_status", String.class), verdict, verdictLabel(verdict),
        r.get("loss_evidence", String.class), r.get("computed_at", OffsetDateTime.class));
  }

  private static DropshipLossAlertRow alertRow(Record r) {
    String kind = r.get("alert_kind", String.class);
    String status = r.get("status", String.class);
    return new DropshipLossAlertRow(
        r.get("id", UUID.class), r.get("alert_no", String.class),
        r.get("order_id", UUID.class), r.get("order_no", String.class),
        blankToEmpty(r.get("shop_code", String.class)), r.get("supplier_code", String.class),
        r.get("currency_code", String.class), kind, verdictLabel(kind),
        r.get("severity", String.class), r.get("rule_code", String.class),
        r.get("rule_evidence", String.class), r.get("fee_registration_status", String.class),
        r.get("platform_fee_amount", BigDecimal.class), r.get("sell_amount", BigDecimal.class),
        r.get("dropship_cost", BigDecimal.class), r.get("spread_before_fee", BigDecimal.class),
        r.get("spread_after_fee", BigDecimal.class), r.get("period_start", OffsetDateTime.class),
        r.get("period_end", OffsetDateTime.class), status, alertStatusLabel(status),
        r.get("detected_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        blankToEmpty(r.get("resolution_note", String.class)));
  }

  private static DropshipReconciliationRow reconciliationRow(Record r) {
    return new DropshipReconciliationRow(
        r.get("id", UUID.class), r.get("statement_no", String.class),
        r.get("supplier_code", String.class), r.get("period_start", OffsetDateTime.class),
        r.get("period_end", OffsetDateTime.class), r.get("cycle_kind", String.class),
        r.get("currency_code", String.class), r.get("business_model", String.class),
        r.get("cost_basis", String.class), r.get("status", String.class),
        r.get("order_count", Integer.class), r.get("loss_order_count", Integer.class),
        r.get("total_sell_amount", BigDecimal.class),
        r.get("total_purchase_amount", BigDecimal.class),
        r.get("total_supplier_shipping", BigDecimal.class),
        r.get("total_platform_fee", BigDecimal.class),
        r.get("total_dropship_cost", BigDecimal.class), r.get("total_spread", BigDecimal.class),
        r.get("total_payable", BigDecimal.class),
        r.get("undetermined_order_count", Integer.class),
        r.get("undetermined_payable", BigDecimal.class),
        r.get("fee_unregistered_order_count", Integer.class),
        r.get("fx_rate_status", String.class), r.get("fx_rate_value", BigDecimal.class),
        blankToEmpty(r.get("rule_evidence", String.class)),
        blankToEmpty(r.get("basis_note", String.class)),
        blankToEmpty(r.get("dispute_note", String.class)),
        r.get("finalized_at", OffsetDateTime.class), r.get("closed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class), r.get("version", Integer.class));
  }

  private static DropshipReconciliationLineRow lineRow(Record r) {
    return new DropshipReconciliationLineRow(
        r.get("id", UUID.class), r.get("order_no", String.class),
        r.get("sales_order_no", String.class), blankToEmpty(r.get("shop_code", String.class)),
        r.get("original_currency_code", String.class),
        r.get("statement_currency_code", String.class), r.get("sell_amount", BigDecimal.class),
        r.get("purchase_amount", BigDecimal.class),
        r.get("supplier_shipping_amount", BigDecimal.class),
        r.get("fee_registration_status", String.class),
        r.get("platform_fee_amount", BigDecimal.class), r.get("loss_verdict", String.class),
        r.get("fx_rate_status", String.class), r.get("fx_rate_value", BigDecimal.class),
        r.get("sell_amount_in_statement", BigDecimal.class),
        r.get("purchase_amount_in_statement", BigDecimal.class),
        r.get("supplier_shipping_in_statement", BigDecimal.class),
        r.get("platform_fee_in_statement", BigDecimal.class),
        r.get("dropship_cost_in_statement", BigDecimal.class),
        r.get("spread_in_statement", BigDecimal.class),
        r.get("payable_in_statement", BigDecimal.class), r.get("is_in_totals", Boolean.class),
        r.get("rule_evidence", String.class));
  }

  private DropshipPayableTaskRow taskRow(Record r) {
    String status = r.get("task_status", String.class);
    DropshipPayablePayload payload;
    try {
      payload = json.readValue(r.get("payload", String.class), DropshipPayablePayload.class);
    } catch (Exception e) {
      payload = null;
    }
    return new DropshipPayableTaskRow(
        r.get("id", UUID.class), r.get("task_no", String.class),
        r.get("task_kind", String.class), r.get("target_system", String.class), status,
        payableStatusLabel(status), r.get("statement_id", UUID.class),
        r.get("statement_no", String.class), r.get("supplier_code", String.class),
        r.get("payable_amount", BigDecimal.class),
        r.get("payable_currency_code", String.class), payload, r.get("idempotency_key", String.class),
        r.get("attempt_count", Integer.class), r.get("last_attempt_at", OffsetDateTime.class),
        blankToEmpty(r.get("last_error", String.class)), r.get("claimed_at", OffsetDateTime.class),
        r.get("sent_at", OffsetDateTime.class), r.get("acknowledged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String verdictLabel(String verdict) {
    try {
      return DropshipLossVerdict.valueOf(verdict).label();
    } catch (RuntimeException e) {
      return verdict;
    }
  }

  private static String alertStatusLabel(String status) {
    return switch (status) {
      case "OPEN" -> "待处理";
      case "ACKNOWLEDGED" -> "已确认待处置";
      case "RESOLVED" -> "已结案";
      case "DISMISSED" -> "已判误报";
      default -> status;
    };
  }

  private static String payableStatusLabel(String status) {
    try {
      return DropshipPayableTaskStatus.valueOf(status).label();
    } catch (RuntimeException e) {
      return status;
    }
  }
}
