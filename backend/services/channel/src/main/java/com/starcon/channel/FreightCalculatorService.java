package com.starcon.channel;

import static com.starcon.channel.FreightCalculatorModels.*;
import static com.starcon.channel.FreightRatingEngine.Band;
import static com.starcon.channel.FreightRatingEngine.ChannelTerms;
import static com.starcon.channel.FreightRatingEngine.PackageSpec;
import static com.starcon.channel.FreightRatingEngine.RateCard;
import static com.starcon.channel.FreightRatingEngine.RatingException;
import static com.starcon.channel.FreightRatingEngine.Surcharge;
import static com.starcon.channel.LogisticsChannelModels.ChannelEligibilityRequest;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.1.2.1 运费计算器。
 *
 * <p>蓝图：「输入收货国家、重量、体积、件数、申报价值、是否带电/液体等，输出各可用渠道的
 * 运费与预计时效，供人工决策或规则匹配使用。输入：包裹参数；渠道规则集；可能的附加费。
 * 输出：渠道 × (运费、时效、可承运性) 对照表。关系：上游 = 10.1.1.2；下游 = 9.3.2.1 分仓、
 * 5.2.3.2 费用测算。<b>试算用规则、结算用实际</b>，两者差异进费用差异项 —— 不能拿试算
 * 当实际成本。」
 *
 * <p>本类做的事，按职责句逐项落点：
 *
 * <ul>
 *   <li><b>输入</b>：包裹参数（{@link FreightEstimateRequest}）+ 渠道规则集（本服务的
 *       {@code freight_rate_card} / {@code freight_rate_band} / {@code freight_channel_country}）
 *       + 附加费（上游 10.1.1.2 的 {@code logistics_channel_surcharge}）。
 *   <li><b>算</b>：{@link FreightRatingEngine} —— 纯函数，无数据库、无费率常量。
 *   <li><b>输出</b>：{@link FreightEstimateResult}，一条渠道一行（运费、时效、可承运性）。
 *   <li><b>可承运性</b>：<b>不在本类重写</b>，直接用上游 10.1.1.2 的服务端判定端点
 *       （{@link LogisticsChannelService#eligibility}）。限制规则只有一份 ——
 *       本类再写一份就意味着总有一份会漏掉「磁力」这类限制。
 * </ul>
 *
 * <p>🔴 两条不能妥协的口径：
 *
 * <ol>
 *   <li><b>试算 ≠ 实际成本</b>：每条响应都带 {@code costBasis=RULE_ESTIMATE}，每张落库的
 *       试算单都带 {@code cost_basis='RULE_ESTIMATE'}（DB 层 CHECK 只允许这一个取值）。
 *       结算要花钱的地方必须走承运商账单 —— 把这一列的数字当实收成本入账，差异会静默
 *       吞掉利润，而那正是下游 5.2.3.2 费用差异项要追问的事。
 *   <li><b>算不出就留空，不给 0</b>：{@code rateSource=NO_RULE} 的行运费各列是
 *       {@code null}。一条 0 元运费会被当成「这家最便宜」选进 9.3.2.1 分仓。
 * </ol>
 *
 * <p>数据来源纪律：渠道配置与附加费<b>同库同服务</b>读（10.1.1.2 已落在 channel，V136 建表），
 * 本类**不复制**渠道任何字段；跨服务方向一律经授权 HTTP 端点，**一条 SQL 都不碰别的服务的库**。
 */
@Service
public class FreightCalculatorService {

  private final DSLContext sql;
  private final LogisticsChannelService channels;

  public FreightCalculatorService(DSLContext sql, LogisticsChannelService channels) {
    this.sql = sql;
    this.channels = channels;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**同一个 404 且理由逐字相同**（照抄 10.1.1.2 同款口径）。 */
  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "试算单不存在");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public FreightCalculatorCapabilities capabilities() {
    return new FreightCalculatorCapabilities(
        List.of(
            new FreightCapabilityDimension(
                "package_input",
                "包裹参数（收货国家 / 重量 / 体积 / 件数 / 申报价值 / 带电带液带磁）",
                true,
                "FreightEstimateRequest + freight_quote 回显列",
                "",
                "缺任一项都会让算出来的运费只在一个方向上成立（没申报价值就没有保费）"),
            new FreightCapabilityDimension(
                "rate_card",
                "计分档位（按重量段 / 按体积折 kg / 按件分段取价）",
                true,
                "本服务 V140 freight_rate_card + freight_rate_band",
                "",
                "没有档位就只剩渠道单值线性估价，真实跨境报价的偏差全在这里"),
            new FreightCapabilityDimension(
                "first_plus_additional",
                "首重 + 续重（余量向上进位）",
                true,
                "本服务 V140 charge_model=FIRST_PLUS_ADDITIONAL",
                "",
                "首重/续重算错是「少收承运商一段运费」的直接来源，进位方向由引擎钉死"),
            new FreightCapabilityDimension(
                "volumetric_weight",
                "体积重折算（按重量计费时取 max(实重, 体积重)）",
                true,
                "本服务 V140 freight_rate_card.volumetric_divisor",
                "",
                "不折算体积重 = 轻泡货系统性少收；折算基数由费率卡给，不是代码常量"),
            new FreightCapabilityDimension(
                "surcharges",
                "附加费（燃油按百分比 / 偏远 / 旺季带生效区间）",
                true,
                "上游 10.1.1.2 V136 logistics_channel_surcharge（本模块只消费，不重复实现）",
                "",
                "旺季费按试算时刻判定，判定用的时刻会写进试算单以便事后复算"),
            new FreightCapabilityDimension(
                "transit_estimate",
                "试算时效（分国家）",
                true,
                "本服务 V140 freight_channel_country.transit_min_days / transit_max_days",
                "",
                "未登记时效时返回空值而不是编一个天数 —— 假时效会被当承诺用"),
            new FreightCapabilityDimension(
                "eligibility",
                "可承运性（禁限品类 / 电池液体磁力 / 保价上限 / 申报上下限）",
                true,
                "上游 10.1.1.2 服务端判定端点 /channel/logistics-channels/eligibility",
                "",
                "判定规则只有一份，本模块**不重写**；重写就意味着总有一份会漏掉某种限制"),
            new FreightCapabilityDimension(
                "channel_comparison",
                "多目标比价排序（成本 / 时效 / 成功率）",
                false,
                "下游 10.1.2.2 渠道比价未开发",
                "10.1.2.2",
                "本模块按运费升序给出对照表，但不按时效达成率 / 成功率排序 —— "
                    + "只看价格选渠道会推高售后与差评成本"),
            new FreightCapabilityDimension(
                "actual_cost_settlement",
                "实际结算成本回填与费用差异项",
                false,
                "结算口径不在本模块：运单实际成本归 trade（10.2.1.2）",
                "10.2.1.2 / 12 财务",
                "本模块只出估算；试算与实际的差异要由下游费用差异项承接，"
                    + "**不能拿本模块的数字当实际成本**")),
        List.of("FLAT_RATE", "FIRST_PLUS_ADDITIONAL"),
        List.of("WEIGHT", "VOLUME", "PIECE"),
        List.of("HALF_UP", "HALF_EVEN", "HALF_DOWN", "CEILING", "FLOOR"),
        List.of("RATE_CARD", "CHANNEL_FLAT", "NO_RULE"),
        List.of("PASS", "REVIEW", "BLOCK"),
        "试算用规则、结算用实际：cost_basis 恒为 RULE_ESTIMATE 且在 DB 层写不进实际结算口径，"
            + "两者差异由下游费用差异项承接。本模块的输出不能当实际成本入账。",
        "规则来自本服务 V140 的 freight_rate_card / freight_rate_band / freight_channel_country，"
            + "渠道计费方式与附加费来自上游 10.1.1.2（V136，同库同服务），"
            + "可承运性来自上游 10.1.1.2 的服务端判定端点。本服务不碰其它服务的库。");
  }

  // ==================================================================
  // 试算（无副作用）
  // ==================================================================

  /** 试算但**不落库**：给人工决策看对照表。 */
  public FreightEstimateResult estimate(FreightEstimateRequest request) {
    return compute(request, false);
  }

  /** 试算并**落库**：生成一张试算单（下游 9.3.2.1 分仓 / 5.2.3.2 费用测算引用的是它）。 */
  public FreightEstimateResult saveQuote(FreightEstimateRequest request) {
    return compute(request, true);
  }

  // ------------------------------------------------------------------

  private FreightEstimateResult compute(FreightEstimateRequest request, boolean persist) {
    UUID tenant = currentTenant();

    String country = text(request.destCountry()).toUpperCase(Locale.ROOT);
    String shop = text(request.shopCode());
    if (!country.matches("^[A-Z]{2}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收货国家必须是两位大写字母（如 DE / US）");
    }

    // 尺寸要么三个都给要么都不给：半套尺寸会算出一个看起来正常的体积重
    boolean anyDim =
        request.lengthCm() != null || request.widthCm() != null || request.heightCm() != null;
    boolean allDim =
        request.lengthCm() != null && request.widthCm() != null && request.heightCm() != null;
    if (anyDim && !allDim) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "长宽高要么三个都给，要么都不给");
    }

    int pieces = request.pieces() == null ? 1 : request.pieces();
    if (pieces <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "件数必须 ≥ 1");
    }
    OffsetDateTime quotedAt = request.quotedAt() == null ? OffsetDateTime.now() : request.quotedAt();

    List<String> categories =
        request.categoryCodes() == null
            ? List.of()
            : request.categoryCodes().stream()
                .map(FreightCalculatorService::text)
                .filter(v -> !v.isEmpty())
                .map(v -> v.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

    // ---- ① 候选渠道：适用该目的国、且是启用中的（草稿与停用不参与承运）----
    List<Record> candidates = candidateChannels(country, shop, request.channelIds());
    if (candidates.isEmpty()) {
      return emptyResult(request, country, shop, pieces, quotedAt, categories, persist);
    }
    List<UUID> channelIds = candidates.stream().map(r -> r.get("id", UUID.class)).toList();

    // ---- ② 可承运性：直接用上游 10.1.1.2 的服务端判定，规则不重写 ----
    var eligibility =
        channels.eligibility(
            new ChannelEligibilityRequest(
                channelIds, country, shop, categories, request.containsBattery(),
                request.containsLiquid(), request.containsMagnet(), request.declaredValue(), null));
    Map<UUID, LogisticsChannelModels.ChannelEligibilityVerdict> verdicts =
        eligibility.verdicts().stream()
            .collect(
                Collectors.toMap(
                    LogisticsChannelModels.ChannelEligibilityVerdict::channelId,
                    v -> v,
                    (a, b) -> a,
                    LinkedHashMap::new));

    // ---- ③ 规则集：一次性取回，避免逐渠道查（N+1）----
    Map<UUID, RateCard> cards = activeCards(tenant, channelIds);
    Map<UUID, Record> countryRules = countryRules(tenant, channelIds, country);
    Map<UUID, List<Surcharge>> surcharges = surcharges(tenant, channelIds);

    PackageSpec pkg =
        new PackageSpec(request.weightKg(), request.lengthCm(), request.widthCm(), request.heightCm(), pieces);

    // ---- ④ 逐渠道算钱 ----
    List<FreightEstimateLine> lines = new ArrayList<>();
    for (Record row : candidates) {
      UUID channelId = row.get("id", UUID.class);
      var verdict = verdicts.get(channelId);
      String eligibilityCode = verdict == null ? "REVIEW" : verdict.verdict();
      List<String> reasons = new ArrayList<>();
      if (verdict != null) {
        reasons.addAll(verdict.blockingReasons());
        reasons.addAll(verdict.reviewReasons());
      }

      ChannelTerms terms =
          new ChannelTerms(
              row.get("billing_mode", String.class),
              row.get("billing_currency", String.class),
              row.get("base_rate", BigDecimal.class),
              row.get("charge_divisor", BigDecimal.class),
              row.get("min_charge", BigDecimal.class),
              row.get("insurance_mode", String.class),
              row.get("insurance_rate_pct", BigDecimal.class),
              row.get("insurance_min_fee", BigDecimal.class));

      RuleCountry rule =
          countryRules.containsKey(channelId)
              ? ruleFrom(countryRules.get(channelId))
              : new RuleCountry(false, null, null);

      FreightEstimateLine line;
      try {
        var rating =
            FreightRatingEngine.INSTANCE.rate(
                pkg, terms, cards.get(channelId), rule.remoteArea(),
                surcharges.getOrDefault(channelId, List.of()), request.declaredValue(), quotedAt);
        List<String> trace = new ArrayList<>(rating.trace());
        trace.add(0, "渠道计费方式 " + terms.billingMode() + "，币种 " + terms.currency());
        List<String> notes = new ArrayList<>(rating.notes());
        if (rule.transitMinDays() == null) {
          notes.add("该渠道未登记 " + country + " 的试算时效区间，本次不给时效（不编天数）");
        }
        line =
            new FreightEstimateLine(
                channelId,
                row.get("channel_code", String.class),
                row.get("channel_name", String.class),
                row.get("party_name", String.class),
                eligibilityCode,
                reasons,
                rating.rateSource(),
                rating.basis(),
                rating.chargeableQty(),
                rating.volumetricQty(),
                rating.baseCharge(),
                rating.fuelFee(),
                rating.remoteFee(),
                rating.peakFee(),
                rating.insuranceFee(),
                rating.totalCharge(),
                terms.currency(),
                rule.transitMinDays(),
                rule.transitMaxDays(),
                true,
                trace,
                notes);
      } catch (RatingException failure) {
        // 🔴 一条渠道算不出钱**不让整张对照表失败**，但也绝不给 0：
        //    运费各列留空（DB 层 freight_quote_line 的 CHECK 保证 NO_RULE 行没有合计）。
        line =
            new FreightEstimateLine(
                channelId,
                row.get("channel_code", String.class),
                row.get("channel_name", String.class),
                row.get("party_name", String.class),
                eligibilityCode,
                reasons,
                "NO_RULE",
                basisOf(terms.billingMode()),
                null, null, null, null, null, null, null, null,
                terms.currency(),
                rule.transitMinDays(),
                rule.transitMaxDays(),
                false,
                List.of("渠道计费方式 " + terms.billingMode() + "，币种 " + terms.currency()),
                List.of("算不出运费：" + failure.getMessage()));
      }
      lines.add(line);
    }

    lines.sort(lineOrder());

    Summaries summary = summarize(lines);
    UUID savedId = persist ? persist(tenant, request, country, shop, pieces, quotedAt, categories, lines, summary) : null;

    BigDecimal volume =
        allDim
            ? request.lengthCm().multiply(request.widthCm()).multiply(request.heightCm())
            : null;

    return new FreightEstimateResult(
        quotedAt,
        country,
        shop,
        request.weightKg(),
        volume,
        pieces,
        lines.size(),
        (int) lines.stream().filter(FreightEstimateLine::quoteable).count(),
        summary.pass,
        summary.review,
        summary.block,
        summary.currency,
        summary.mixedCurrency,
        summary.cheapest,
        summary.dearest,
        "RULE_ESTIMATE",
        savedId,
        lines,
        "运费按规则试算（" + summary.currency + (summary.mixedCurrency ? "，另有其它币种渠道未参与比价" : "")
            + "）：试算用规则、结算用实际，这个数字不是承运商账单，"
            + "与实际运费的差异要进下游费用差异项，不能拿它当实际成本。");
  }

  /** 对照表默认按「能算出钱的在前、运费升序」，算不出的沉底。 */
  private static Comparator<FreightEstimateLine> lineOrder() {
    return Comparator.comparing(FreightEstimateLine::quoteable, Comparator.reverseOrder())
        .thenComparing(
            line -> line.totalCharge() == null ? new BigDecimal("-1") : line.totalCharge())
        .thenComparing(FreightEstimateLine::channelCode);
  }

  private record RuleCountry(boolean remoteArea, Integer transitMinDays, Integer transitMaxDays) {}

  private record Summaries(
      long pass, long review, long block, String currency, boolean mixedCurrency,
      BigDecimal cheapest, BigDecimal dearest) {}

  /**
   * 汇总：最便宜 / 最贵**只在主币种内**统计。
   *
   * <p>主币种 = 能算出钱的行里运费最低那条的币种。不同币种的金额直接比大小是没有意义的
   * （0.9 CNY 的运费比 0.13 USD 便宜，但不是同一个东西），所以这里不混排，而是
   * {@code mixedCurrency=true} 把这件事显式说出来。
   */
  private Summaries summarize(List<FreightEstimateLine> lines) {
    long pass = lines.stream().filter(l -> "PASS".equals(l.eligibility())).count();
    long review = lines.stream().filter(l -> "REVIEW".equals(l.eligibility())).count();
    long block = lines.stream().filter(l -> "BLOCK".equals(l.eligibility())).count();
    List<FreightEstimateLine> priced =
        lines.stream().filter(l -> l.quoteable() && l.totalCharge() != null).toList();
    if (priced.isEmpty()) {
      return new Summaries(pass, review, block, "", false, null, null);
    }
    FreightEstimateLine cheapest = priced.get(0);
    String currency = cheapest.currency();
    List<BigDecimal> inCurrency =
        priced.stream().filter(l -> currency.equals(l.currency())).map(FreightEstimateLine::totalCharge).toList();
    boolean mixed = priced.stream().anyMatch(l -> !currency.equals(l.currency()));
    return new Summaries(
        pass, review, block, currency, mixed,
        inCurrency.stream().min(BigDecimal::compareTo).orElse(null),
        inCurrency.stream().max(BigDecimal::compareTo).orElse(null));
  }

  private FreightEstimateResult emptyResult(
      FreightEstimateRequest request, String country, String shop, int pieces,
      OffsetDateTime quotedAt, List<String> categories, boolean persist) {
    // 空结果也要能落库：一条「这次没候选渠道」的试算单本身就是有价值的记录
    // （否则「当时为什么没得选」永远查不到）。
    UUID savedId =
        persist
            ? persistEmpty(currentTenant(), request, country, shop, pieces, quotedAt, categories)
            : null;
    return new FreightEstimateResult(
        quotedAt, country, shop, request.weightKg(), null, pieces, 0, 0, 0, 0, 0, "", false,
        null, null, "RULE_ESTIMATE", savedId, List.of(),
        "本租户没有匹配这些条件的渠道：渠道要么没启用、要么适用范围不含目的国 / 店铺。"
            + "空结果**不代表**可以随便选一条 —— 它代表「这些条件下没有候选渠道」。");
  }

  private static String basisOf(String billingMode) {
    return switch (billingMode == null ? "" : billingMode) {
      case "BY_VOLUME" -> "VOLUME";
      case "BY_PIECE" -> "PIECE";
      default -> "WEIGHT";
    };
  }

  // ==================================================================
  // 规则读取（jOOQ 显式 SQL）
  // ==================================================================

  private static final String CANDIDATE_SQL =
      "select c.id,c.channel_code,c.channel_name,c.billing_mode,c.billing_currency,c.base_rate,"
          + "c.charge_divisor,c.min_charge,c.insurance_mode,c.insurance_rate_pct,c.insurance_min_fee,"
          + "p.party_name"
          + " from logistics_channel_config c"
          + " join carrier_logistics_profile p"
          + "   on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id"
          + " where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
          + " and c.status = 'ACTIVE'"
          + " and exists (select 1 from logistics_channel_scope s"
          + "   where s.tenant_id = c.tenant_id and s.channel_id = c.id"
          + "   and s.scope_kind = 'COUNTRY' and s.scope_value = ?)";

  private List<Record> candidateChannels(String country, String shop, List<UUID> channelIds) {
    StringBuilder where = new StringBuilder(CANDIDATE_SQL);
    List<Object> binds = new ArrayList<>();
    binds.add(country);

    if (!shop.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_scope s"
              + " where s.tenant_id = c.tenant_id and s.channel_id = c.id"
              + " and s.scope_kind = 'SHOP' and s.scope_value = ?)");
      binds.add(shop);
    }
    if (channelIds != null && !channelIds.isEmpty()) {
      where.append(
          " and c.id in ("
              + String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"))
              + ")");
      binds.addAll(channelIds);
    }
    where.append(" order by c.channel_code asc");
    return sql.fetch(where.toString(), binds.toArray());
  }

  /** 生效中的费率卡（含档位）；一个渠道最多一张（V140 部分唯一索引保证）。 */
  private Map<UUID, RateCard> activeCards(UUID tenant, List<UUID> channelIds) {
    if (channelIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, RateCard> result = new LinkedHashMap<>();

    // ① 先取卡（按渠道）：卡 id 与渠道 id **不是**同一个东西，档位必须按卡 id 取
    List<Record> cards =
        sql.fetch(
            "select id,channel_id,charge_model,basis,rounding_mode,rounding_scale,volumetric_divisor,"
                + "first_qty,first_amount,additional_qty,additional_amount"
                + " from freight_rate_card"
                + " where tenant_id = ? and status = 'ACTIVE' and channel_id in ("
                + placeholders(channelIds.size()) + ")",
            prepend(tenant, channelIds));
    if (cards.isEmpty()) {
      return result;
    }

    // ② 再按卡 id 一次性取回全部档位（避免逐卡查的 N+1）
    List<UUID> cardIds = cards.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<Band>> bands = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select card_id,band_no,from_qty,to_qty,to_qty_inclusive,rate_amount"
                + " from freight_rate_band"
                + " where tenant_id = ? and card_id in ("
                + placeholders(cardIds.size())
                + ") order by card_id, band_no",
            prepend(tenant, cardIds))) {
      bands
          .computeIfAbsent(row.get("card_id", UUID.class), k -> new ArrayList<>())
          .add(
              new Band(
                  row.get("band_no", Integer.class),
                  row.get("from_qty", BigDecimal.class),
                  row.get("to_qty", BigDecimal.class),
                  Boolean.TRUE.equals(row.get("to_qty_inclusive", Boolean.class)),
                  row.get("rate_amount", BigDecimal.class)));
    }

    for (Record row : cards) {
      result.put(
          row.get("channel_id", UUID.class),
          new RateCard(
              row.get("charge_model", String.class),
              row.get("basis", String.class),
              row.get("rounding_mode", String.class),
              row.get("rounding_scale", Integer.class),
              row.get("volumetric_divisor", BigDecimal.class),
              row.get("first_qty", BigDecimal.class),
              row.get("first_amount", BigDecimal.class),
              row.get("additional_qty", BigDecimal.class),
              row.get("additional_amount", BigDecimal.class),
              bands.getOrDefault(row.get("id", UUID.class), List.of())));
    }
    return result;
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private Map<UUID, Record> countryRules(UUID tenant, List<UUID> channelIds, String country) {
    if (channelIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"));
    Map<UUID, Record> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select channel_id,country_code,remote_area,transit_min_days,transit_max_days"
                + " from freight_channel_country"
                + " where tenant_id = ? and country_code = ? and channel_id in ("
                + placeholders
                + ")",
            args(tenant, country, channelIds))) {
      result.put(row.get("channel_id", UUID.class), row);
    }
    return result;
  }

  private static RuleCountry ruleFrom(Record row) {
    return new RuleCountry(
        Boolean.TRUE.equals(row.get("remote_area", Boolean.class)),
        row.get("transit_min_days", Integer.class),
        row.get("transit_max_days", Integer.class));
  }

  /** 附加费来自上游 10.1.1.2（V136）—— 本模块只消费，不重复实现附加费规则。 */
  private Map<UUID, List<Surcharge>> surcharges(UUID tenant, List<UUID> channelIds) {
    if (channelIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"));
    Map<UUID, List<Surcharge>> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select channel_id,surcharge_kind,charge_mode,amount,effective_from,effective_to"
                + " from logistics_channel_surcharge"
                + " where tenant_id = ? and channel_id in ("
                + placeholders
                + ")",
            prepend(tenant, channelIds))) {
      result
          .computeIfAbsent(row.get("channel_id", UUID.class), k -> new ArrayList<>())
          .add(
              new Surcharge(
                  row.get("surcharge_kind", String.class),
                  row.get("charge_mode", String.class),
                  row.get("amount", BigDecimal.class),
                  row.get("effective_from", OffsetDateTime.class),
                  row.get("effective_to", OffsetDateTime.class)));
    }
    return result;
  }

  private static Object[] prepend(Object first, List<UUID> rest) {
    Object[] args = new Object[rest.size() + 1];
    args[0] = first;
    for (int i = 0; i < rest.size(); i++) {
      args[i + 1] = rest.get(i);
    }
    return args;
  }

  private static Object[] args(Object first, Object second, List<UUID> rest) {
    Object[] args = new Object[rest.size() + 2];
    args[0] = first;
    args[1] = second;
    for (int i = 0; i < rest.size(); i++) {
      args[i + 2] = rest.get(i);
    }
    return args;
  }

  // ==================================================================
  // 试算单落库
  // ==================================================================

  private UUID persist(
      UUID tenant,
      FreightEstimateRequest request,
      String country,
      String shop,
      int pieces,
      OffsetDateTime quotedAt,
      List<String> categories,
      List<FreightEstimateLine> lines,
      Summaries summary) {

    UUID quoteId = Ids.next();
    insertQuote(
        tenant, quoteId, request, country, shop, pieces, quotedAt, categories,
        lines.size(), summary);
    for (FreightEstimateLine line : lines) {
      sql.execute(
          "insert into freight_quote_line"
              + " (tenant_id,quote_id,channel_id,channel_code,channel_name,carrier_name,"
              + "  eligibility,eligibility_reason,rate_source,basis,chargeable_qty,volumetric_qty,"
              + "  base_charge,fuel_fee,remote_fee,peak_fee,insurance_fee,total_charge,currency,"
              + "  transit_min_days,transit_max_days,calc_trace)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          tenant, quoteId, line.channelId(), line.channelCode(), line.channelName(), line.carrierName(),
          line.eligibility(), String.join("；", line.eligibilityReasons()), line.rateSource(), line.basis(),
          line.chargeableQty(), line.volumetricQty(), line.baseCharge(), line.fuelFee(),
          line.remoteFee(), line.peakFee(), line.insuranceFee(), line.totalCharge(), line.currency(),
          line.transitMinDays(), line.transitMaxDays(),
          String.join("；", line.trace()) + joinNotes(line.notes()));
    }
    return quoteId;
  }

  private UUID persistEmpty(
      UUID tenant, FreightEstimateRequest request, String country, String shop, int pieces,
      OffsetDateTime quotedAt, List<String> categories) {
    UUID quoteId = Ids.next();
    insertQuote(tenant, quoteId, request, country, shop, pieces, quotedAt, categories, 0,
        new Summaries(0, 0, 0, "", false, null, null));
    return quoteId;
  }

  private void insertQuote(
      UUID tenant,
      UUID quoteId,
      FreightEstimateRequest request,
      String country,
      String shop,
      int pieces,
      OffsetDateTime quotedAt,
      List<String> categories,
      int channelCount,
      Summaries summary) {
    sql.execute(
        "insert into freight_quote"
            + " (tenant_id,id,cost_basis,dest_country,shop_code,weight_kg,length_cm,width_cm,height_cm,"
            + "  pieces,declared_value,declared_currency,contains_battery,contains_liquid,contains_magnet,"
            + "  category_codes,quoted_at,currency,mixed_currency,channel_count,pass_count,review_count,"
            + "  block_count,cheapest_total,dearest_total,note)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        tenant, quoteId, "RULE_ESTIMATE", country, shop, request.weightKg(), request.lengthCm(),
        request.widthCm(), request.heightCm(), pieces, request.declaredValue(),
        blankToNull(request.declaredCurrency()), request.containsBattery(), request.containsLiquid(),
        request.containsMagnet(), codeList(categories), timestamp(quotedAt),
        // 一条都算不出运费时币种留空（而不是编一个 USD 出来）
        summary.currency == null ? "" : summary.currency,
        summary.mixedCurrency, channelCount, (int) summary.pass, (int) summary.review,
        (int) summary.block, summary.cheapest, summary.dearest, text(request.note()));
  }

  /**
   * timestamptz 列的绑定值。
   *
   * <p>🔴 刻意转成 {@link java.sql.Timestamp}：把 {@link OffsetDateTime} 直接交给
   * {@code setObject} 时，驱动会按 varchar 发送，PG 报
   * 「column quoted_at is of type timestamp with time zone but expression is of
   * type character varying」—— 试算时刻算错旺季费会静默错，所以这里一次性钉死。
   */
  private static java.sql.Timestamp timestamp(OffsetDateTime value) {
    return value == null ? null : java.sql.Timestamp.from(value.toInstant());
  }

  private static String joinNotes(List<String> notes) {
    return notes == null || notes.isEmpty() ? "" : "；注：" + String.join("；", notes);
  }

  /**
   * 品类编码列表 → 逗号串（DB 层是 text 列 + 同口径 CHECK）。
   *
   * <p>🔴 每个值都要匹配 {@code [A-Z0-9_]{1,64}}，与 {@code freight_quote} 上那条
   * CHECK 同一个正则：服务层挡住拼进 SQL 的自由文本，DB 层兜住绕过服务层的写入。
   */
  static String codeList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    for (String value : values) {
      if (value == null || !value.matches("^[A-Z0-9_]{1,64}$")) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "品类编码只接受字母数字下划线（大写，最多 64 位）：" + value);
      }
    }
    return String.join(",", values);
  }

  // ==================================================================
  // 试算单查询
  // ==================================================================

  private static final String QUOTE_COLUMNS =
      "q.id,q.cost_basis,q.dest_country,q.shop_code,q.weight_kg,q.length_cm,q.width_cm,q.height_cm,"
          + "q.pieces,q.declared_value,q.declared_currency,q.contains_battery,q.contains_liquid,"
          + "q.contains_magnet,q.category_codes,q.quoted_at,q.currency,q.mixed_currency,q.channel_count,"
          + "q.pass_count,q.review_count,q.block_count,q.cheapest_total,q.dearest_total,q.note,q.created_at";

  private static final String QUOTE_FROM =
      " from freight_quote q"
          + " where q.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final List<String> SORTS =
      List.of("TIME_DESC", "TIME_ASC", "CHEAPEST_ASC", "CHEAPEST_DESC");

  /** 筛选条件的 SQL 片段（不带租户条件 —— 租户条件由两个固定片段各自提供）。 */
  private record QuoteFilter(String where, List<Object> binds) {}

  private QuoteFilter quoteFilter(
      String destCountry, String eligibility, String quotedFrom, String quotedTo) {
    StringBuilder where = new StringBuilder();
    List<Object> binds = new ArrayList<>();

    String country = text(destCountry).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(" and q.dest_country = ?");
      binds.add(country);
    }
    if (!text(eligibility).isEmpty()) {
      // 按可承运性筛：命中任意一行为该档的试算单（存在性判定，不 join 放大行数）
      where.append(
          " and exists (select 1 from freight_quote_line l"
              + " where l.tenant_id = q.tenant_id and l.quote_id = q.id and l.eligibility = ?)");
      binds.add(text(eligibility));
    }
    OffsetDateTime from = parseInstant(quotedFrom, "quotedFrom");
    if (from != null) {
      where.append(" and q.quoted_at >= ?");
      binds.add(from);
    }
    OffsetDateTime to = parseInstant(quotedTo, "quotedTo");
    if (to != null) {
      where.append(" and q.quoted_at <= ?");
      binds.add(to);
    }
    return new QuoteFilter(where.toString(), binds);
  }

  public FreightQuotePage quotes(
      String destCountry, String eligibility, String quotedFrom, String quotedTo,
      String sort, int page, int size) {

    if (!SORTS.contains(sort)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序方式只能是 " + String.join(" / ", SORTS));
    }
    QuoteFilter filter = quoteFilter(destCountry, eligibility, quotedFrom, quotedTo);

    // 汇总走独立查询，不随 limit/offset 变
    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where block_count > 0) as with_block,"
                + " count(*) filter (where review_count > 0) as with_review,"
                + " count(*) filter (where mixed_currency) as mixed_currency,"
                + " count(*) filter (where cheapest_total is not null) as quoteable"
                + QUOTE_FROM
                + filter.where(),
            filter.binds().toArray());
    long total = summary == null ? 0 : summary.get("total", Long.class);
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    int totalPages = (int) Math.ceil((double) total / safeSize);

    String orderBy =
        switch (sort) {
          case "TIME_ASC" -> " order by q.quoted_at asc, q.id asc";
          case "CHEAPEST_ASC" -> " order by q.cheapest_total asc nulls last, q.quoted_at desc";
          case "CHEAPEST_DESC" -> " order by q.cheapest_total desc nulls last, q.quoted_at desc";
          default -> " order by q.quoted_at desc, q.id desc";
        };

    List<FreightQuoteRow> items =
        sql.fetch(
                "select " + QUOTE_COLUMNS + QUOTE_FROM + filter.where() + orderBy + " limit ? offset ?",
                concat(filter.binds(), safeSize, safePage * safeSize))
            .stream()
            .map(FreightCalculatorService::quoteRow)
            .toList();

    return new FreightQuotePage(
        items,
        safePage,
        safeSize,
        total,
        totalPages,
        new FreightQuoteSummary(
            total,
            summary == null ? 0 : nz(summary.get("with_block", Long.class)),
            summary == null ? 0 : nz(summary.get("with_review", Long.class)),
            summary == null ? 0 : nz(summary.get("mixed_currency", Long.class)),
            summary == null ? 0 : nz(summary.get("quoteable", Long.class))));
  }

  private static long nz(Number value) {
    return value == null ? 0L : value.longValue();
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** 路径参数不是 uuid 时给 400（而不是把 NumberFormatException 抛成 500）。 */
  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 必须是合法的 uuid：" + raw);
    }
  }

  private static OffsetDateTime parseInstant(String raw, String field) {    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是 ISO-8601 时间（如 2026-10-15T09:00:00+08:00）");
    }
  }

  public FreightQuoteDetail quote(String id) {
    UUID tenant = currentTenant();
    UUID quoteId = parseUuid(id);
    FreightQuoteRow row =
        quoteRow(sql.fetchOne("select " + QUOTE_COLUMNS + QUOTE_FROM + " and q.id = ?", quoteId));
    if (row == null) {
      throw notFound();
    }
    List<FreightQuoteLineRow> lines =
        sql.fetch(
                "select channel_id,channel_code,channel_name,carrier_name,eligibility,"
                    + "eligibility_reason,rate_source,basis,chargeable_qty,volumetric_qty,base_charge,"
                    + "fuel_fee,remote_fee,peak_fee,insurance_fee,total_charge,currency,"
                    + "transit_min_days,transit_max_days,calc_trace"
                    + " from freight_quote_line where tenant_id = ? and quote_id = ?"
                    + " order by (total_charge is null), total_charge asc, channel_code asc",
                tenant, quoteId)
            .stream()
            .map(
                r ->
                    new FreightQuoteLineRow(
                        r.get("channel_id", UUID.class),
                        r.get("channel_code", String.class),
                        r.get("channel_name", String.class),
                        r.get("carrier_name", String.class),
                        r.get("eligibility", String.class),
                        r.get("eligibility_reason", String.class),
                        r.get("rate_source", String.class),
                        r.get("basis", String.class),
                        r.get("chargeable_qty", BigDecimal.class),
                        r.get("volumetric_qty", BigDecimal.class),
                        r.get("base_charge", BigDecimal.class),
                        r.get("fuel_fee", BigDecimal.class),
                        r.get("remote_fee", BigDecimal.class),
                        r.get("peak_fee", BigDecimal.class),
                        r.get("insurance_fee", BigDecimal.class),
                        r.get("total_charge", BigDecimal.class),
                        r.get("currency", String.class),
                        r.get("transit_min_days", Integer.class),
                        r.get("transit_max_days", Integer.class),
                        r.get("total_charge", BigDecimal.class) != null,
                        r.get("calc_trace", String.class)))
            .toList();
    return new FreightQuoteDetail(row, lines);
  }

  private static FreightQuoteRow quoteRow(Record r) {
    if (r == null) {
      return null;
    }
    String categories = r.get("category_codes", String.class);
    return new FreightQuoteRow(
        r.get("id", UUID.class),
        r.get("cost_basis", String.class),
        r.get("dest_country", String.class),
        r.get("shop_code", String.class),
        r.get("weight_kg", BigDecimal.class),
        r.get("length_cm", BigDecimal.class),
        r.get("width_cm", BigDecimal.class),
        r.get("height_cm", BigDecimal.class),
        r.get("pieces", Integer.class),
        r.get("declared_value", BigDecimal.class),
        r.get("declared_currency", String.class),
        Boolean.TRUE.equals(r.get("contains_battery", Boolean.class)),
        Boolean.TRUE.equals(r.get("contains_liquid", Boolean.class)),
        Boolean.TRUE.equals(r.get("contains_magnet", Boolean.class)),
        categories == null || categories.isBlank() ? List.of() : List.of(categories.split(",")),
        r.get("quoted_at", OffsetDateTime.class),
        r.get("currency", String.class),
        Boolean.TRUE.equals(r.get("mixed_currency", Boolean.class)),
        r.get("channel_count", Integer.class),
        r.get("pass_count", Integer.class),
        r.get("review_count", Integer.class),
        r.get("block_count", Integer.class),
        r.get("cheapest_total", BigDecimal.class),
        r.get("dearest_total", BigDecimal.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 费率卡与分国家规则（规则的写入口 —— 规则不该只靠手写 SQL 维护）
  // ==================================================================

  public RateCardOverview rateCards(
      String cardId, String channelId, String status, String country) {
    UUID cardFilter = text(cardId).isEmpty() ? null : parseUuid(cardId);
    UUID channelFilter = text(channelId).isEmpty() ? null : parseUuid(channelId);
    String statusFilter = text(status);

    Map<UUID, List<RateBandItem>> bands = new LinkedHashMap<>();
    StringBuilder bandWhere =
        new StringBuilder(
            " from freight_rate_band b"
                + " join freight_rate_card c on c.tenant_id = b.tenant_id and c.id = b.card_id"
                + " where b.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    List<Object> bandBinds = new ArrayList<>();
    if (cardFilter != null) {
      bandWhere.append(" and b.card_id = ?");
      bandBinds.add(cardFilter);
    }
    if (channelFilter != null) {
      bandWhere.append(" and c.channel_id = ?");
      bandBinds.add(channelFilter);
    }
    if (!statusFilter.isEmpty()) {
      bandWhere.append(" and c.status = ?");
      bandBinds.add(statusFilter);
    }
    for (Record r :
        sql.fetch(
            "select b.card_id,b.band_no,b.from_qty,b.to_qty,b.to_qty_inclusive,b.rate_amount,b.note"
                + bandWhere
                + " order by b.card_id, b.band_no",
            bandBinds.toArray())) {
      bands
          .computeIfAbsent(r.get("card_id", UUID.class), k -> new ArrayList<>())
          .add(
              new RateBandItem(
                  r.get("band_no", Integer.class),
                  r.get("from_qty", BigDecimal.class),
                  r.get("to_qty", BigDecimal.class),
                  Boolean.TRUE.equals(r.get("to_qty_inclusive", Boolean.class)),
                  r.get("rate_amount", BigDecimal.class),
                  r.get("note", String.class)));
    }

    List<RateCardRow> cards =
        sql.fetch(
                "select c.id,c.channel_id,ch.channel_code,ch.channel_name,p.party_name,ch.billing_mode,"
                    + "ch.billing_currency,c.status,c.charge_model,c.basis,c.rounding_mode,c.rounding_scale,"
                    + "c.volumetric_divisor,c.first_qty,c.first_amount,c.additional_qty,c.additional_amount,"
                    + "c.note,c.version,c.created_at,c.updated_at"
                    + " from freight_rate_card c"
                    + " join logistics_channel_config ch"
                    + "   on ch.tenant_id = c.tenant_id and ch.id = c.channel_id"
                    + " join carrier_logistics_profile p"
                    + "   on p.tenant_id = ch.tenant_id and p.id = ch.carrier_profile_id"
                    + " where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + (cardFilter == null ? "" : " and c.id = ?")
                    + (channelFilter == null ? "" : " and c.channel_id = ?")
                    + (statusFilter.isEmpty() ? "" : " and c.status = ?")
                    + " order by ch.channel_code, c.status, c.id",
                cardFilter == null
                    ? (channelFilter == null
                        ? (statusFilter.isEmpty() ? new Object[0] : new Object[] {statusFilter})
                        : (statusFilter.isEmpty()
                            ? new Object[] {channelFilter}
                            : new Object[] {channelFilter, statusFilter}))
                    : (channelFilter == null
                        ? (statusFilter.isEmpty()
                            ? new Object[] {cardFilter}
                            : new Object[] {cardFilter, statusFilter})
                        : (statusFilter.isEmpty()
                            ? new Object[] {cardFilter, channelFilter}
                            : new Object[] {cardFilter, channelFilter, statusFilter})))
            .stream()
            .map(
                r ->
                    new RateCardRow(
                        r.get("id", UUID.class),
                        r.get("channel_id", UUID.class),
                        r.get("channel_code", String.class),
                        r.get("channel_name", String.class),
                        r.get("party_name", String.class),
                        r.get("billing_mode", String.class),
                        r.get("billing_currency", String.class),
                        r.get("status", String.class),
                        r.get("charge_model", String.class),
                        r.get("basis", String.class),
                        r.get("rounding_mode", String.class),
                        r.get("rounding_scale", Integer.class),
                        r.get("volumetric_divisor", BigDecimal.class),
                        r.get("first_qty", BigDecimal.class),
                        r.get("first_amount", BigDecimal.class),
                        r.get("additional_qty", BigDecimal.class),
                        r.get("additional_amount", BigDecimal.class),
                        bands.getOrDefault(r.get("id", UUID.class), List.of()),
                        r.get("note", String.class),
                        r.get("version", Long.class),
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();

    String countryFilter = text(country);
    List<ChannelCountryRule> rules =
        sql.fetch(
                "select r.channel_id,ch.channel_code,r.country_code,r.remote_area,"
                    + "r.transit_min_days,r.transit_max_days,r.note"
                    + " from freight_channel_country r"
                    + " join logistics_channel_config ch"
                    + "   on ch.tenant_id = r.tenant_id and ch.id = r.channel_id"
                    + " where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + (countryFilter.isEmpty() ? "" : " and r.country_code = ?")
                    + " order by r.country_code, ch.channel_code",
                countryFilter.isEmpty() ? new Object[0] : new Object[] {countryFilter})
            .stream()
            .map(
                r ->
                    new ChannelCountryRule(
                        r.get("channel_id", UUID.class),
                        r.get("channel_code", String.class),
                        r.get("country_code", String.class),
                        Boolean.TRUE.equals(r.get("remote_area", Boolean.class)),
                        r.get("transit_min_days", Integer.class),
                        r.get("transit_max_days", Integer.class),
                        r.get("note", String.class)))
            .toList();

    return new RateCardOverview(
        cards,
        rules,
        "规则来自数据库而不是代码常量：改承运商费率是改这张表（新增/停用费率卡、调整档位），"
            + "不用改代码发版。生效中的卡一个渠道只能有一张；生效中的卡不允许换计费模型或量纲。");
  }

  public RateCardRow saveRateCard(SaveRateCardRequest request) {
    UUID tenant = currentTenant();
    String model = text(request.chargeModel()).toUpperCase(Locale.ROOT);
    String basis = text(request.basis()).toUpperCase(Locale.ROOT);
    String status = text(request.status()).toUpperCase(Locale.ROOT);
    String rounding = text(request.roundingMode()).toUpperCase(Locale.ROOT);

    if (!List.of("FLAT_RATE", "FIRST_PLUS_ADDITIONAL").contains(model)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "计费模型只能是 FLAT_RATE（计分档位）或 FIRST_PLUS_ADDITIONAL（首重续重）");
    }
    if (!List.of("WEIGHT", "VOLUME", "PIECE").contains(basis)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "量纲只能是 WEIGHT / VOLUME / PIECE");
    }
    if (!FreightRatingEngine.isRoundingMode(rounding)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "舍入方式只能是 HALF_UP / HALF_EVEN / HALF_DOWN / CEILING / FLOOR");
    }
    if (!List.of("DRAFT", "ACTIVE", "RETIRED").contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能是 DRAFT / ACTIVE / RETIRED");
    }
    if ("FLAT_RATE".equals(model)) {
      if (request.firstQty() != null || request.additionalQty() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "计分档位模型不带首重/续重参数（那是另一套取价规则）");
      }
    } else if (request.firstQty() == null || request.additionalQty() == null
        || request.firstAmount() == null || request.additionalAmount() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "首重/续重模型必须给齐首重量、首重费、续重单位与续重费");
    }
    if (request.bands() != null && !request.bands().isEmpty() && "FIRST_PLUS_ADDITIONAL".equals(model)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "首重/续重模型不分档：档位与首重续重同时生效时，运费无法解释是哪条规则算的");
    }

    UUID cardId = request.cardId();
    // 渠道必须在本租户下真的存在：否则 FK 报错会变成一条看不懂的 500
    if (sql.fetchOne(
            "select 1 from logistics_channel_config where tenant_id=? and id=?",
            tenant, request.channelId())
        == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "渠道配置不存在：请先在 /logistics/channels 登记渠道配置");
    }
    if (cardId == null) {
      // 先以 DRAFT 落库：启用时的完整性校验（trigger c）要看档位，而档位在后面才写。
      cardId = Ids.next();
      sql.execute(
          "insert into freight_rate_card"
              + " (tenant_id,id,channel_id,status,charge_model,basis,rounding_mode,rounding_scale,"
              + "  volumetric_divisor,first_qty,first_amount,additional_qty,additional_amount,note)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          tenant, cardId, request.channelId(), "DRAFT", model, basis, rounding, request.roundingScale(),
          request.volumetricDivisor(), request.firstQty(), request.firstAmount(),
          request.additionalQty(), request.additionalAmount(), text(request.note()));
    } else {
      Record existing =
          sql.fetchOne(
              "select version from freight_rate_card where tenant_id=? and id=?", tenant, cardId);
      if (existing == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "费率卡不存在");
      }
      long current = existing.get("version", Long.class);
      if (request.version() != current) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "费率卡已被他人修改（当前版本 " + current + "），请重新载入后再保存");
      }
    }

    // 🔴 档位重叠与覆盖连续性在**服务层先查一遍**，V140 的触发器再兜一次底。
    //   为什么两层都要：DB 层是硬边界（绕过服务层写 SQL 也拒），但它经 ApiErrorHandler
    //   会被翻译成一条 409「店铺编码重复、平台账号已被绑定…」—— 对「档位重叠」这种
    //   运营配置错误来说那句话是错的，而运营看到错的理由就不会改。
    checkBands(request.bands(), "ACTIVE".equals(status));

    // 档位整体替换：少发一档就是静默丢一档（丢掉的那一段货会算出 0 元运费）
    sql.execute("delete from freight_rate_band where tenant_id=? and card_id=?", tenant, cardId);
    if (request.bands() != null) {
      for (RateBandItem band : request.bands()) {
        if (band.toQty() != null && band.toQty().compareTo(band.fromQty()) <= 0) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "第 " + band.bandNo() + " 段上界必须大于下界（空上界表示无上限）");
        }
        sql.execute(
            "insert into freight_rate_band"
                + " (tenant_id,card_id,band_no,from_qty,to_qty,to_qty_inclusive,rate_amount,note)"
                + " values (?,?,?,?,?,?,?,?)",
            tenant, cardId, band.bandNo(), band.fromQty(), band.toQty(), band.toQtyInclusive(),
            band.rateAmount(), text(band.note()));
      }
    }

    // 主表最后一次写入：此时档位已是最终形态，启用校验看到的也是最终形态
    sql.execute(
        "update freight_rate_card set status=?,charge_model=?,basis=?,rounding_mode=?,rounding_scale=?,"
            + "volumetric_divisor=?,first_qty=?,first_amount=?,additional_qty=?,additional_amount=?,"
            + "note=?,updated_at=now(),version=version+1 where tenant_id=? and id=?",
        status, model, basis, rounding, request.roundingScale(), request.volumetricDivisor(),
        request.firstQty(), request.firstAmount(), request.additionalQty(), request.additionalAmount(),
        text(request.note()), tenant, cardId);

    UUID savedCardId = cardId;
    return rateCards(savedCardId.toString(), null, null, "").cards().stream()
        .filter(c -> c.id().equals(savedCardId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "费率卡不存在"));
  }

  /**
   * 档位重叠 / 覆盖连续性校验（与 V140 trigger (b)(c) 同一套语义）。
   *
   * <p>段区间：下界恒闭；上界闭开由 {@code toQtyInclusive} 决定；{@code toQty} 空 = 无上限。
   * 判定与报错文案刻意与迁移里的 {@code guard_freight_band_overlap} /
   * {@code guard_freight_rate_card_activate} 保持一致 —— 两处结论不同会让「界面上说
   * 不重叠、库里却拒」这种最难查的问题出现。
   *
   * @param active 目标状态是否 ACTIVE（激活时才要求连续覆盖到无上限；草稿允许留洞，
   *     因为「先加两段再补中间」是正常的编辑流程）
   */
  static void checkBands(List<RateBandItem> bands, boolean active) {
    if (bands == null || bands.isEmpty()) {
      return;
    }
    List<RateBandItem> sorted =
        bands.stream()
            .sorted(Comparator.comparing(RateBandItem::fromQty).thenComparingInt(RateBandItem::bandNo))
            .toList();

    for (RateBandItem left : sorted) {
      for (RateBandItem right : sorted) {
        if (left.bandNo() == right.bandNo()) {
          continue;
        }
        if (pastOrTouching(left, right) && pastOrTouching(right, left)) {
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "档位重叠：第 " + left.bandNo() + " 段 " + bandRange(left)
                  + " 与第 " + right.bandNo() + " 段 " + bandRange(right)
                  + " 相交 —— 同一个重量会被算两遍钱，请把两段首尾改成严格衔接"
                  + "（下界恒闭，上界闭开由 toQtyInclusive 决定）");
        }
      }
    }

    if (!active) {
      return;
    }
    if (sorted.get(0).fromQty().signum() != 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "分段费率卡的最下一档从 " + sorted.get(0).fromQty() + " 起（必须从 0 起）——"
              + "低于它的重量落在任何一段之外，试算会算出 0 元运费");
    }
    for (RateBandItem band : sorted) {
      if (band.fromQty().signum() == 0) {
        continue;
      }
      boolean covered =
          sorted.stream()
              .anyMatch(
                  other ->
                      other != band
                          && other.toQty() != null
                          && other.toQty().compareTo(band.fromQty()) == 0);
      if (!covered) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "档位必须连续覆盖：第 " + band.bandNo() + " 段从 " + band.fromQty()
                + " 起，而前面没有任何一段的上界落到这个值 —— 落在这个区间的货算不出钱");
      }
    }
    if (sorted.get(sorted.size() - 1).toQty() != null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "最高一段必须有上界留空（表示无上限）—— 否则超过这个重量的货落在所有档位之外");
    }
  }

  /**
   * {@code a} 的下界是否已越过 {@code b} 的上界（含「恰好落在 b 的闭合上界」。
   *
   * <p>{@code toQty == null} 表示 +∞ —— 所以那一侧<b>永远不可能</b>被越过。
   * 这里显式判空而不是拿 null 去 {@code compareTo}：那样会 NPE，
   * 而 NPE 出现在「提交一张最高档无上限的卡」这种最正常的操作上。
   */
  private static boolean pastOrTouching(RateBandItem a, RateBandItem b) {
    if (b.toQty() == null) {
      return false;
    }
    int cmp = a.fromQty().compareTo(b.toQty());
    return cmp < 0 || (cmp == 0 && b.toQtyInclusive());
  }

  private static String bandRange(RateBandItem band) {
    String end = band.toQty() == null ? "∞" : band.toQty().toPlainString();
    return "[" + band.fromQty().toPlainString() + " , " + end
        + (band.toQty() == null || band.toQtyInclusive() ? "]" : ")");
  }

  public ChannelCountryRule saveCountryRule(SaveCountryRuleRequest request) {
    UUID tenant = currentTenant();
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    if (!country.matches("^[A-Z]{2}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "国家必须是两位大写字母");
    }
    if ((request.transitMinDays() == null) != (request.transitMaxDays() == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时效区间要么两个都给，要么都不给（只给下限报出来的时效是假的）");
    }
    var existing =
        sql.fetchOne(
            "select 1 from freight_channel_country where tenant_id=? and channel_id=? and country_code=?",
            tenant, request.channelId(), country);
    if (existing == null) {
      sql.execute(
          "insert into freight_channel_country"
              + " (tenant_id,channel_id,country_code,remote_area,transit_min_days,transit_max_days,note)"
              + " values (?,?,?,?,?,?,?)",
          tenant, request.channelId(), country, request.remoteArea(), request.transitMinDays(),
          request.transitMaxDays(), text(request.note()));
    } else {
      sql.execute(
          "update freight_channel_country set remote_area=?,transit_min_days=?,transit_max_days=?,"
              + "note=? where tenant_id=? and channel_id=? and country_code=?",
          request.remoteArea(), request.transitMinDays(), request.transitMaxDays(), text(request.note()),
          tenant, request.channelId(), country);
    }
    return rateCards("", "", "", country).countryRules().stream()
        .filter(r -> r.channelId().equals(request.channelId()) && r.countryCode().equals(country))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分国家规则不存在"));
  }
}
