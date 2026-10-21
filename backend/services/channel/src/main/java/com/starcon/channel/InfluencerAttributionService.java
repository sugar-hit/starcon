package com.starcon.channel;

import static com.starcon.channel.InfluencerAttributionModels.*;
import static com.starcon.channel.InfluencerAttributionRules.*;

import com.starcon.channel.vendor.InfluencerAttributionDataPort;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.5.2.1 带货归因。
 *
 * <p>蓝图：「达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
 * 对比达人投入产出，沉淀优质达人库。输入：达人带货数据同步；订单归因；佣金数据。
 * 输出：达人效果报表 + ROI + 评级更新。关系：上游 = 14.5.1.1、平台数据；
 * 下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
 * <b>达人佣金是真实成本，必须进利润表</b>，否则会高估达人带货的净收益。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>达人带来的订单量 / GMV / 退款率 / 佣金成本 / 有效粉丝转化</b> →
 *       {@link #attributions}（归因记录）+ {@link #report}（达人效果报表）。
 *   <li><b>订单归因</b> → {@link #run}：按<b>归因规则</b>把合作记录归因到达人，
 *       规则本身是可配置、可公示的事实（{@link #rules} / {@link #saveRule}）。
 *   <li><b>对比达人投入产出</b> → 报表里的 {@code roi} 与
 *       {@code netGmvAfterCommission}（<b>只扣佣金</b>，不含样品 / 履约 / 平台费）。
 *   <li><b>沉淀优质达人库</b> → 归因批次写完后<b>触发一次评级重算</b>
 *       （走 14.5.1.1 的既有服务方法，同服务内直接调用，不跨服务 HTTP 自调）。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的四个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>把三个 GMV 口径混成一个数</b>。档案 GMV（14.5.1.1 入库值）、
 *       合作历史合计（本地按合作行求和）、归因口径（本模块按规则分摊后的净额）
 *       在报表里<b>分列三栏</b>。本模块<b>不覆盖 14.5.1.1 的任何一列</b>。
 *   <li><b>声称佣金成本已进利润表</b>。13.4 利润（reporting 服务）<b>未交付</b>，
 *       集成头里根本没有 reporting 服务 ⇒ 佣金成本<b>只在本服务内留痕</b>
 *       （金额 / 口径 / 计提状态），能力声明里 {@code profitIntegration.available=false}。
 *   <li><b>编一个平台带货数据接口</b>。平台适配层只有 OAuth 适配器 ⇒ 同步端口对所有平台
 *       答「未接入」，同步入口据此<b>不写任何数据</b>。
 *   <li><b>把「没录」写成 0</b>。GMV / 佣金 / ROI 为 {@code null} 表示<b>不可比</b>
 *       （还没录），0 会被读成「这个达人不用付佣金、ROI 无穷大」。
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读**同库的 {@code shop} / {@code platform} /
 * {@code ad_campaign} / {@code influencer} / {@code influencer_cooperation}
 * （全部是 14.5.1.1 与 14.1.1.1 的既有事实），
 * 写入一律落在自己的两张表（{@code influencer_attribution_rule} /
 * {@code influencer_attribution}）上。
 */
@Service
public class InfluencerAttributionService {

  // ==================================================================
  // 常量：取值域与口径
  // ==================================================================

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 排序白名单：**只认这几个键**，其余一律回落到默认排序（防注入）。 */
  private static final Map<String, String> SORTS =
      Map.of(
          "ATTRIBUTED_GMV_DESC", "att.attributed_gmv_total DESC NULLS LAST, i.id",
          "ATTRIBUTED_GMV_ASC", "att.attributed_gmv_total ASC NULLS LAST, i.id",
          "COMMISSION_DESC", "att.commission_total DESC NULLS LAST, i.id",
          "ROI_DESC",
              "(case when att.commission_total > 0"
                  + " then att.attributed_gmv_total / att.commission_total end) DESC NULLS LAST, i.id",
          "EFFECTIVE_ORDERS_DESC", "att.effective_order_total DESC NULLS LAST, i.id",
          "FOLLOWERS_DESC", "i.follower_count DESC, i.id",
          "CREATED_DESC", "i.created_at DESC, i.id");

  /** 归因记录列表的排序白名单。 */
  private static final Map<String, String> ROW_SORTS =
      Map.of(
          "ATTRIBUTED_GMV_DESC", "a.attributed_gmv DESC NULLS LAST, a.id",
          "ATTRIBUTED_GMV_ASC", "a.attributed_gmv ASC NULLS LAST, a.id",
          "COMMISSION_DESC", "a.commission_amount DESC NULLS LAST, a.id",
          "ROI_DESC",
              "(case when a.commission_amount > 0"
                  + " then a.attributed_gmv / a.commission_amount end) DESC NULLS LAST, a.id",
          "EFFECTIVE_ORDERS_DESC", "a.effective_order_count DESC NULLS LAST, a.id",
          "ATTRIBUTED_AT_DESC", "a.attributed_at DESC, a.id",
          "CREATED_DESC", "a.created_at DESC, a.id");

  // ==================================================================
  // 口径说明（随响应返回，界面上的表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String CALIBER_NOTE =
      "🔴 **三个 GMV 口径分列三栏，不能混成一个数**："
          + "①**档案 GMV**（14.5.1.1 达人档案里的入库值，手动录入 / 未来平台同步）；"
          + "②**合作历史合计**（本地按 14.5.1.1 的合作记录行求和，是合作口径）；"
          + "③**归因口径**（本模块：按归因规则分摊到达人、并按退款口径处理后的净额）。"
          + "三者**谁也不覆盖谁** —— 报表里分别成列，看板与跨部门沟通也必须分别引用。"
          + "「没录」一律是 null（不可比），**不是 0.00**：0 会被读成「这个达人一单没带过货」。";

  static final String RULE_NOTE =
      "🔴 **归因规则必须固定并公示**（规则变了历史报表不可比）："
          + "一个租户**同一时刻只有一套生效口径**；每条归因记录都自带**规则快照**"
          + "（归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径与比例）与**基数快照**，"
          + "所以「归因 GMV = 基数 × 权重」永远可复核。"
          + "归因窗口从**合作结束时刻**起算：超窗的合作**不允许新增归因**（账期过了不能补记），"
          + "但**已经归因过的记录不会被抹掉** —— 已归因是事实（佣金按它计提过）。";

  static final String COMMISSION_NOTE =
      "🔴 **佣金成本是真实成本，但它还没进利润表**："
          + "13.4 利润（reporting 服务）**未交付**，集成头里根本没有 reporting 服务，"
          + "所以佣金目前**只在本服务内真实留痕**（归因行上的金额 / 计提基数 / 成本口径 / "
          + "计提状态与时刻），**没有进任何利润报表** —— 能力声明里 "
          + "profitIntegration.available = false。"
          + "🔴 **佣金不按归因权重缩放**：佣金是已经发生的成本，成本不会因为归因口径变了就变。"
          + "「扣佣金后净额」**只扣佣金**（样品 / 履约 / 平台费都不在里面），"
          + "所以它**不是净收益**。佣金**没录** ⇒ ROI 与净额都是 null（不可比），不是 0。";

  static final String SYNC_NOTE =
      "🔴 **平台带货数据同步未接入**：channel 的平台适配层（channel/vendor）目前**只有 OAuth "
          + "授权适配器**，**没有任何平台的达人带货数据接口**；订单事实又在 trade 服务"
          + "（跨服务不可读）。所以归因数据**只来自本地已录的合作记录**"
          + "（14.5.1.1 的 influencer_cooperation）与达人档案；"
          + "同步入口会明确返回「未接入」并**不写任何数据** —— "
          + "本模块**不编造一个假的平台接口，也不返回硬编码的归因列表**。";

  static final String UPSTREAM_NOTE =
      "上游 = 14.5.1.1 达人库（influencer / influencer_cooperation / 评级口径与档位，"
          + "全部同库同服务，复合 FK 带 tenant_id）+ 14.1.1.1 广告活动（ad_campaign）+ "
          + "2.3.1.1 店铺（shop）；平台带货数据适配器**未交付** ⇒ 同步维度 available=false。";

  static final String BOUNDARY_NOTE =
      "下游 = 14.5.1.1 评级（归因批次写完后触发一次评级重算；**评级口径的增删改归 14.5.1.1**，"
          + "本模块不改它的表结构与规则表）与 13.4 利润（**未交付** ⇒ 佣金成本只在本服务内留痕）。"
          + "本模块**不实现**建联与样品（14.5.1.2）与利润分析（13.4）。";

  private final DSLContext sql;
  private final InfluencerAttributionDataPort platformData;
  private final InfluencerService influencerService;

  public InfluencerAttributionService(
      DSLContext sql,
      InfluencerAttributionDataPort platformData,
      InfluencerService influencerService) {
    this.sql = sql;
    this.platformData = platformData;
    this.influencerService = influencerService;
  }

  // ==================================================================
  // 小工具（照抄 InfluencerService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public InfluencerAttributionCapabilities capabilities() {
    return new InfluencerAttributionCapabilities(
        List.of(
            new InfluencerAttributionCapabilityDimension(
                "attribution_rule",
                "归因规则：归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径（**一个租户一套生效**）",
                true,
                "influencer_attribution_rule（V290）",
                "",
                "没有规则就没有「归因」这个口径；两套口径同时生效时归因 GMV 没有答案"),
            new InfluencerAttributionCapabilityDimension(
                "attribution_record",
                "🔴 **归因记录**：按规则把合作记录分摊到达人，带规则快照与基数快照",
                true,
                "influencer_attribution（V290）",
                "",
                "没有归因记录就只剩「合作历史合计」，答不出「这个达人的效果有多少是归因口径的」"),
            new InfluencerAttributionCapabilityDimension(
                "effect_report",
                "达人效果报表：**三个 GMV 口径分列三栏** + ROI + 有效粉丝转化",
                true,
                "influencer_attribution / influencer_cooperation / influencer（V290 + V288）",
                "",
                "口径混成一个数就会高估达人带货的净收益"),
            new InfluencerAttributionCapabilityDimension(
                "commission_cost",
                "🔴 **佣金成本留痕**：金额 / 计提基数 / 成本口径 / 计提状态与时刻",
                true,
                "influencer_attribution.commission_* / accrual_*（V290）",
                "",
                "佣金不记下来就没有「投入产出」可言；本模块**不声称已进利润表**"),
            new InfluencerAttributionCapabilityDimension(
                "rating_update",
                "评级更新：归因批次写完后触发一次评级重算（**口径归 14.5.1.1**）",
                true,
                "14.5.1.1 influencer_rating_rule / influencer_rating_tier（只读引用）",
                "",
                "不重算评级，达人库里的档位会停留在旧数据上"),
            new InfluencerAttributionCapabilityDimension(
                "platform_sync",
                "🔴 **平台带货数据同步（平台接口那一半）**",
                false,
                "—",
                "平台适配层（channel/vendor：只有 OAuth 适配器，没有带货数据适配器）",
                "归因数据目前**只来自本地已录的合作记录**。同步端口对所有平台返回「未接入」，"
                    + "同步入口**不写任何数据**；平台适配器接上后同一个端点自动放行"),
            new InfluencerAttributionCapabilityDimension(
                "profit_integration",
                "🔴 **佣金成本进利润表（13.4 利润 / reporting 服务）**",
                false,
                "—",
                "13.4 利润（reporting 服务未交付）",
                "佣金成本目前**只在本服务内留痕**，**没有进任何利润报表** —— "
                    + "算净收益时必须自己再扣样品 / 履约 / 平台费"),
            new InfluencerAttributionCapabilityDimension(
                "order_facts",
                "🔴 **订单级事实（订单归因的另一半）**",
                false,
                "—",
                "订单在 trade 服务（跨服务不可读）",
                "归因目前落在**合作记录**粒度；订单级归因需要订单事实跨服务可读")),
        profitIntegration(),
        ATTRIBUTION_MODELS,
        POOL_WEIGHT_BASES,
        REFUND_TREATMENTS,
        COMMISSION_BASES,
        ACCRUAL_STATUSES,
        CALIBER_NOTE,
        RULE_NOTE,
        COMMISSION_NOTE,
        SYNC_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  /**
   * 🔴 利润侧对接状态。
   *
   * <p><b>{@code available} 恒为 false</b>：13.4 利润（reporting 服务）未交付，
   * 集成头里没有该服务 ⇒ 佣金成本<b>没有进任何利润报表</b>。
   */
  public InfluencerAttributionProfitIntegration profitIntegration() {
    return new InfluencerAttributionProfitIntegration(
        false,
        "13.4",
        "13.4 利润（reporting 服务）未交付，集成头里没有该服务 ⇒ 达人佣金成本目前"
            + "**只在本服务内真实留痕**（归因行上的金额 / 计提基数 / 成本口径 / 计提状态与时刻），"
            + "**没有进利润表**。算净收益时要自己再扣样品 / 履约 / 平台费，"
            + "本模块的「扣佣金后净额」**只是扣了佣金**。");
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public InfluencerAttributionOptions options() {
    List<InfluencerAttributionPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new InfluencerAttributionPlatformOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)))
            .toList();

    List<String> shopCodes =
        sql.fetch("select distinct code from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> currencies =
        sql.fetch(
                "select distinct currency from influencer_attribution where tenant_id = "
                    + TENANT
                    + " and currency <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new InfluencerAttributionOptions(
        attributionModelOptions(),
        poolWeightBasisOptions(),
        refundTreatmentOptions(),
        commissionBasisOptions(),
        accrualStatusOptions(),
        platforms,
        platforms.stream().map(InfluencerAttributionPlatformOption::code).toList(),
        shopCodes,
        currencies,
        CALIBER_NOTE,
        COMMISSION_NOTE);
  }

  private static List<InfluencerAttributionOption> attributionModelOptions() {
    return List.of(
        new InfluencerAttributionOption("COOPERATION_FULL", "合作全额归因（一条合作的效果全额归到该达人）"),
        new InfluencerAttributionOption("CAMPAIGN_POOL_SPLIT", "同活动内分摊（活动池子按权重分给该活动下的各位达人）"));
  }

  private static List<InfluencerAttributionOption> poolWeightBasisOptions() {
    return List.of(
        new InfluencerAttributionOption("FOLLOWER_SHARE", "按粉丝数占比分摊（触达口径）"),
        new InfluencerAttributionOption("COOPERATION_GMV_SHARE", "按各达人自己的合作 GMV 占比分摊（业绩口径）"));
  }

  private static List<InfluencerAttributionOption> refundTreatmentOptions() {
    return List.of(
        new InfluencerAttributionOption("GROSS", "毛额（归因 GMV 不扣退款）"),
        new InfluencerAttributionOption("NET", "净额（归因 GMV 扣掉退款额）"));
  }

  private static List<InfluencerAttributionOption> commissionBasisOptions() {
    return List.of(
        new InfluencerAttributionOption("COOPERATION_COMMISSION", "用合作记录里已录的佣金"),
        new InfluencerAttributionOption("RATE_ON_ATTRIBUTED_GMV", "按归因 GMV × 佣金比例计提"));
  }

  private static List<InfluencerAttributionOption> accrualStatusOptions() {
    return List.of(
        new InfluencerAttributionOption("PENDING", "待计提"),
        new InfluencerAttributionOption("ACCRUED", "已计提"),
        new InfluencerAttributionOption("REVERSED", "已冲销"));
  }

  // ==================================================================
  // 平台同步能力
  // ==================================================================

  /** 逐平台说清带货数据同步是否已接入（**未接入就说未接入**，不假装已接入）。 */
  public InfluencerAttributionSyncStatus syncStatus() {
    List<Record> rows =
        sql.fetch(
            "select pf.code platform_code, pf.name platform_name,"
                + " (select count(*) from influencer_attribution a where a.tenant_id = "
                + TENANT
                + " and a.shop_code in (select code from shop s2 where s2.tenant_id = "
                + TENANT
                + " and s2.platform_id = pf.id)) registered_count"
                + " from platform pf"
                + " where exists (select 1 from shop s where s.tenant_id = "
                + TENANT
                + " and s.platform_id = pf.id)"
                + " order by pf.code");

    List<InfluencerAttributionPlatformSyncAvailability> items = new ArrayList<>();
    for (Record r : rows) {
      String code = r.get("platform_code", String.class);
      InfluencerAttributionDataPort.Availability availability = platformData.availability(code);
      items.add(
          new InfluencerAttributionPlatformSyncAvailability(
              code,
              r.get("platform_name", String.class),
              availability.available(),
              availability.status(),
              availability.reason(),
              r.get("registered_count", Integer.class) == null
                  ? 0
                  : r.get("registered_count", Integer.class)));
    }
    return new InfluencerAttributionSyncStatus(items, SYNC_NOTE);
  }

  /**
   * 触发一次平台带货数据同步。
   *
   * <p>🔴 **当前所有平台的结论都是「未接入」**：端口返回 {@code available=false} 时
   * 本方法<b>不写任何数据</b>，并把端口给的原因原样返回（409）。
   *
   * <p>适配器接上之后的落库口径：平台侧的带货数据**落到本地合作记录的效果数据上**
   * （14.5.1.1 的 {@code influencer_cooperation}）—— 归因记录必须挂在一条真实存在的
   * 合作记录上（DB 复合 FK），平台订单事实不能凭空生成合作记录。
   * 平台没给的数（null）**不覆盖**本地已录的值。
   */
  public InfluencerAttributionSyncResult sync(InfluencerAttributionSyncRequest request) {
    String platformCode = text(request.platformCode()).toUpperCase(Locale.ROOT);
    String shopCode = text(request.shopCode());

    InfluencerAttributionDataPort.Availability availability = platformData.availability(platformCode);
    if (!availability.available()) {
      throw conflict(availability.reason());
    }

    InfluencerAttributionDataPort.FetchResult fetched =
        platformData.fetch(platformCode, shopCode, null, null, "");
    if (!fetched.available()) {
      throw conflict(fetched.reason());
    }

    int synced = 0;
    int skipped = 0;
    for (InfluencerAttributionDataPort.RawAttribution raw : fetched.attributions()) {
      if (applyPlatformAttribution(shopCode, raw)) {
        synced++;
      } else {
        skipped++;
      }
    }
    return new InfluencerAttributionSyncResult(
        true, "已同步 " + synced + " 条带货数据（跳过 " + skipped + " 条未匹配到本地合作记录）", synced, skipped);
  }

  /**
   * 把平台侧的带货数据落到**本地合作记录**的效果数据上。
   *
   * <p>匹配键 = 店铺 + 平台侧达人 id（{@code influencer.external_influencer_id}）
   * + 平台侧活动 id（{@code ad_campaign.external_campaign_id}）。
   * 匹配不上就跳过（**不新建合作记录** —— 合作是 14.5.1.1 的事实，平台数据不替代它）。
   */
  private boolean applyPlatformAttribution(
      String shopCode, InfluencerAttributionDataPort.RawAttribution raw) {
    UUID cooperationId =
        one(
            UUID.class,
            "select c.id from influencer_cooperation c"
                + " join influencer i on i.tenant_id = c.tenant_id and i.id = c.influencer_id"
                + " join ad_campaign cp on cp.tenant_id = c.tenant_id and cp.id = c.campaign_id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.shop_code = ? and i.external_influencer_id = ?"
                + " and cp.external_campaign_id = ?",
            shopCode,
            text(raw.externalInfluencerId()),
            text(raw.externalCampaignId()));
    if (cooperationId == null) {
      return false;
    }
    sql.execute(
        "update influencer_cooperation set"
            + " gmv = coalesce(?, gmv), order_count = coalesce(?, order_count),"
            + " commission = coalesce(?, commission), refund_rate = coalesce(?, refund_rate),"
            + " currency = coalesce(nullif(?, ''), currency),"
            + " updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        raw.orderGmv(),
        raw.orderCount(),
        raw.commissionAmount(),
        raw.refundRate(),
        text(raw.currency()),
        currentTenant(),
        cooperationId);
    return true;
  }

  // ==================================================================
  // 归因规则
  // ==================================================================

  /** 归因规则列表（真分页 + 真筛选）。 */
  public InfluencerAttributionRulePage rules(String status, String keyword, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = clampSize(size);
    StringBuilder where = new StringBuilder(" where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and rule_code ilike ?");
      binds.add("%" + escapeLike(word) + "%");
    }
    long total = count("select count(*) from influencer_attribution_rule" + where, binds.toArray());
    List<InfluencerAttributionRuleRow> items =
        sql.fetch(
                "select id, rule_code, attribution_model, pool_weight_basis, attribution_window_days,"
                    + " refund_treatment, commission_basis, commission_rate, status, note, version,"
                    + " created_at, updated_at from influencer_attribution_rule"
                    + where
                    + " order by case when status = 'ACTIVE' then 0 else 1 end, created_at desc, id"
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::ruleRow)
            .toList();
    return new InfluencerAttributionRulePage(items, safePage, safeSize, total, RULE_NOTE);
  }

  /** 生效的归因规则（**没有配置返回 null** —— 「没配口径」不是「口径是 0」）。 */
  public InfluencerAttributionRuleRow currentRule() {
    Record r =
        sql.fetchOne(
            "select id, rule_code, attribution_model, pool_weight_basis, attribution_window_days,"
                + " refund_treatment, commission_basis, commission_rate, status, note, version,"
                + " created_at, updated_at from influencer_attribution_rule where tenant_id = "
                + TENANT
                + " and status = 'ACTIVE'");
    return r == null ? null : ruleRow(r);
  }

  private InfluencerAttributionRuleRow ruleRow(Record r) {
    return new InfluencerAttributionRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("attribution_model", String.class),
        r.get("pool_weight_basis", String.class),
        r.get("attribution_window_days", Integer.class) == null
            ? 0
            : r.get("attribution_window_days", Integer.class),
        r.get("refund_treatment", String.class),
        r.get("commission_basis", String.class),
        r.get("commission_rate", BigDecimal.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 配置归因规则。
   *
   * <p>🔴 保存新规则时**旧的自动停用**（一个租户同一时刻只能有一套生效口径，
   * DB partial unique index 钉死）。历史归因记录**不会被改写** ——
   * 它们带着自己那次的规则快照，报表按规则过滤即可对比。
   */
  public InfluencerAttributionRuleRow saveRule(SaveInfluencerAttributionRuleRequest request) {
    String ruleCode = text(request.ruleCode());
    if (ruleCode.isEmpty()) {
      throw badRequest("规则编码不能为空");
    }
    String model = text(request.attributionModel()).toUpperCase(Locale.ROOT);
    if (!ATTRIBUTION_MODELS.contains(model)) {
      throw badRequest("归因模型取值域是 " + ATTRIBUTION_MODELS);
    }
    String basis = text(request.poolWeightBasis()).toUpperCase(Locale.ROOT);
    if (basis.isEmpty()) {
      basis = "FOLLOWER_SHARE";
    }
    if (!POOL_WEIGHT_BASES.contains(basis)) {
      throw badRequest("分摊权重基准取值域是 " + POOL_WEIGHT_BASES);
    }
    String refund = text(request.refundTreatment()).toUpperCase(Locale.ROOT);
    if (!REFUND_TREATMENTS.contains(refund)) {
      throw badRequest("退款口径取值域是 " + REFUND_TREATMENTS);
    }
    String commission = text(request.commissionBasis()).toUpperCase(Locale.ROOT);
    if (!COMMISSION_BASES.contains(commission)) {
      throw badRequest("佣金计提口径取值域是 " + COMMISSION_BASES);
    }
    if (request.attributionWindowDays() < 0 || request.attributionWindowDays() > 365) {
      throw badRequest("归因窗口必须在 0 ~ 365 天之间");
    }
    BigDecimal rate = request.commissionRate();
    if ("RATE_ON_ATTRIBUTED_GMV".equals(commission) && rate == null) {
      throw badRequest("按归因 GMV 计提时必须给佣金比例（0 ~ 1）");
    }
    if (!"RATE_ON_ATTRIBUTED_GMV".equals(commission)) {
      rate = null;
    }
    if (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
      throw badRequest("佣金比例必须在 0 ~ 1 之间");
    }
    String status = text(request.status()).toUpperCase(Locale.ROOT);
    if (status.isEmpty()) {
      status = "ACTIVE";
    }
    if (!"ACTIVE".equals(status) && !"PAUSED".equals(status)) {
      throw badRequest("状态取值域是 ACTIVE / PAUSED");
    }

    // 🔴 旧的生效规则先停用（partial unique index：同一租户只能有一套 ACTIVE）。
    sql.execute(
        "update influencer_attribution_rule set status = 'PAUSED', updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and status = 'ACTIVE'",
        currentTenant());

    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_attribution_rule (tenant_id,id,rule_code,attribution_model,"
            + " pool_weight_basis,attribution_window_days,refund_treatment,commission_basis,"
            + " commission_rate,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        ruleCode,
        model,
        basis,
        request.attributionWindowDays(),
        refund,
        commission,
        rate,
        status,
        text(request.note()));
    return currentRuleOr(id);
  }

  private InfluencerAttributionRuleRow currentRuleOr(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, rule_code, attribution_model, pool_weight_basis, attribution_window_days,"
                + " refund_treatment, commission_basis, commission_rate, status, note, version,"
                + " created_at, updated_at from influencer_attribution_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            id);
    return r == null ? null : ruleRow(r);
  }

  // ==================================================================
  // 归因记录
  // ==================================================================

  /** 归因记录列表（真分页 + 真筛选 + 真排序）。 */
  public InfluencerAttributionPage attributions(
      UUID influencerId,
      UUID campaignId,
      String shopCode,
      String ruleCode,
      String accrualStatus,
      String currency,
      BigDecimal minAttributedGmv,
      BigDecimal maxAttributedGmv,
      String keyword,
      String sort,
      int page,
      int size) {
    int safePage = Math.max(page, 0);
    int safeSize = clampSize(size);
    StringBuilder where = new StringBuilder(" where a.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (influencerId != null) {
      where.append(" and a.influencer_id = ?");
      binds.add(influencerId);
    }
    if (campaignId != null) {
      where.append(" and a.campaign_id = ?");
      binds.add(campaignId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and a.shop_code = ?");
      binds.add(shop);
    }
    String rule = blankToNull(ruleCode);
    if (rule != null) {
      where.append(" and r.rule_code = ?");
      binds.add(rule);
    }
    String accrual = blankToNull(accrualStatus);
    if (accrual != null) {
      where.append(" and a.accrual_status = ?");
      binds.add(accrual.toUpperCase(Locale.ROOT));
    }
    String money = blankToNull(currency);
    if (money != null) {
      where.append(" and a.currency = ?");
      binds.add(money.toUpperCase(Locale.ROOT));
    }
    if (minAttributedGmv != null) {
      where.append(" and a.attributed_gmv >= ?");
      binds.add(minAttributedGmv);
    }
    if (maxAttributedGmv != null) {
      where.append(" and a.attributed_gmv <= ?");
      binds.add(maxAttributedGmv);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (i.display_name ilike ? or i.handle ilike ?)");
      String like = "%" + escapeLike(word) + "%";
      binds.add(like);
      binds.add(like);
    }
    String orderBy = ROW_SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), ROW_SORTS.get("ATTRIBUTED_GMV_DESC"));
    long total = count("select count(*) from influencer_attribution a" + attributionJoins() + where, binds.toArray());
    List<InfluencerAttributionRow> items =
        sql.fetch(
                attributionSelect() + where + " order by " + orderBy + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::attributionRow)
            .toList();
    return new InfluencerAttributionPage(items, safePage, safeSize, total, CALIBER_NOTE, COMMISSION_NOTE);
  }

  private String attributionJoins() {
    return " join influencer i on i.tenant_id = a.tenant_id and i.id = a.influencer_id"
        + " join influencer_cooperation coop on coop.tenant_id = a.tenant_id and coop.id = a.cooperation_id"
        + " join ad_campaign cp on cp.tenant_id = a.tenant_id and cp.id = a.campaign_id"
        + " join influencer_attribution_rule r on r.tenant_id = a.tenant_id and r.id = a.rule_id";
  }

  private String attributionSelect() {
    return "select a.id, a.influencer_id, i.display_name influencer_name, i.handle influencer_handle,"
        + " i.category influencer_category, i.follower_count, i.follower_tier,"
        + " a.cooperation_id, a.campaign_id, cp.campaign_name, a.shop_code,"
        + " r.rule_code, a.attribution_model, a.pool_weight_basis, a.attribution_window_days,"
        + " a.refund_treatment, a.commission_basis, a.commission_rate, a.attribution_weight,"
        + " a.source_gmv, a.source_order_count, a.source_commission, a.source_refund_rate,"
        + " a.source_currency, a.attribution_base_gmv, a.attribution_base_order_count,"
        + " a.attribution_base_refund_rate,"
        + " a.attributed_gmv, a.attributed_order_count, a.attributed_refund_rate,"
        + " a.attributed_refund_amount, a.effective_order_count, a.follower_conversion_rate,"
        + " a.follower_count_snapshot, a.currency, a.commission_amount, a.commission_basis_amount,"
        + " a.commission_variance, a.accrual_status, a.accrued_at, a.accrual_note, a.attributed_at,"
        + " a.note, a.version, a.created_at, a.updated_at"
        + " from influencer_attribution a" + attributionJoins();
  }

  private InfluencerAttributionRow attributionRow(Record r) {
    return new InfluencerAttributionRow(
        r.get("id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("influencer_handle", String.class),
        r.get("influencer_category", String.class),
        r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class),
        r.get("follower_tier", String.class),
        r.get("cooperation_id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("shop_code", String.class),
        r.get("rule_code", String.class),
        r.get("attribution_model", String.class),
        r.get("pool_weight_basis", String.class),
        r.get("attribution_window_days", Integer.class) == null
            ? 0
            : r.get("attribution_window_days", Integer.class),
        r.get("refund_treatment", String.class),
        r.get("commission_basis", String.class),
        r.get("commission_rate", BigDecimal.class),
        r.get("attribution_weight", BigDecimal.class),
        r.get("source_gmv", BigDecimal.class),
        r.get("source_order_count", BigDecimal.class),
        r.get("source_commission", BigDecimal.class),
        r.get("source_refund_rate", BigDecimal.class),
        r.get("source_currency", String.class),
        r.get("attribution_base_gmv", BigDecimal.class),
        r.get("attribution_base_order_count", BigDecimal.class),
        r.get("attribution_base_refund_rate", BigDecimal.class),
        r.get("attributed_gmv", BigDecimal.class),
        r.get("attributed_order_count", BigDecimal.class),
        r.get("attributed_refund_rate", BigDecimal.class),
        r.get("attributed_refund_amount", BigDecimal.class),
        r.get("effective_order_count", BigDecimal.class),
        r.get("follower_conversion_rate", BigDecimal.class),
        r.get("follower_count_snapshot", Long.class) == null
            ? 0L
            : r.get("follower_count_snapshot", Long.class),
        r.get("currency", String.class),
        r.get("commission_amount", BigDecimal.class),
        r.get("commission_basis_amount", BigDecimal.class),
        r.get("commission_variance", BigDecimal.class),
        r.get("accrual_status", String.class),
        r.get("accrued_at", OffsetDateTime.class),
        r.get("accrual_note", String.class),
        r.get("attributed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 归因详情：归因记录 + 达人的三个口径 + 源合作记录 + 规则快照。 */
  public InfluencerAttributionDetail attribution(String id) {
    UUID attributionId = parseId(id);
    Record row = sql.fetchOne(attributionSelect() + " where a.tenant_id = " + TENANT + " and a.id = ?", attributionId);
    if (row == null) {
      throw notFound("归因记录");
    }
    InfluencerAttributionRow attributionRow = attributionRow(row);
    UUID ruleId =
        one(
            UUID.class,
            "select rule_id from influencer_attribution where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            attributionId);
    return new InfluencerAttributionDetail(
        attributionRow,
        influencerSummary(attributionRow.influencerId(), ruleId),
        cooperationSource(attributionRow.cooperationId()),
        ruleSnapshot(ruleId),
        CALIBER_NOTE,
        COMMISSION_NOTE,
        profitIntegration());
  }

  private InfluencerAttributionCooperationSource cooperationSource(UUID cooperationId) {
    Record r =
        sql.fetchOne(
            "select c.id cooperation_id, c.campaign_id, cp.campaign_name, c.status, c.started_at,"
                + " c.ended_at, c.gmv, c.order_count, c.commission, c.refund_rate, c.currency"
                + " from influencer_cooperation c"
                + " join ad_campaign cp on cp.tenant_id = c.tenant_id and cp.id = c.campaign_id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.id = ?",
            cooperationId);
    if (r == null) {
      return null;
    }
    return new InfluencerAttributionCooperationSource(
        r.get("cooperation_id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("status", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("ended_at", OffsetDateTime.class),
        r.get("gmv", BigDecimal.class),
        r.get("order_count", BigDecimal.class),
        r.get("commission", BigDecimal.class),
        r.get("refund_rate", BigDecimal.class),
        r.get("currency", String.class));
  }

  private InfluencerAttributionRuleSnapshot ruleSnapshot(UUID ruleId) {
    if (ruleId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select id, rule_code, attribution_model, pool_weight_basis, attribution_window_days,"
                + " refund_treatment, commission_basis, commission_rate, status, note"
                + " from influencer_attribution_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            ruleId);
    if (r == null) {
      return null;
    }
    return new InfluencerAttributionRuleSnapshot(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("attribution_model", String.class),
        r.get("pool_weight_basis", String.class),
        r.get("attribution_window_days", Integer.class) == null
            ? 0
            : r.get("attribution_window_days", Integer.class),
        r.get("refund_treatment", String.class),
        r.get("commission_basis", String.class),
        r.get("commission_rate", BigDecimal.class),
        r.get("status", String.class),
        r.get("note", String.class));
  }

  /**
   * 一个达人的三个口径并排（档案 / 合作合计 / 归因）。
   *
   * @param ruleId 归因口径按哪套规则汇总（null ⇒ 没有归因口径）
   */
  public InfluencerAttributionInfluencerSummary influencerSummary(UUID influencerId, UUID ruleId) {
    Record r =
        sql.fetchOne(
            "select i.id influencer_id, i.display_name, i.handle, i.category, i.follower_count,"
                + " i.follower_tier, i.shop_code, i.gmv profile_gmv, i.gmv_currency profile_gmv_currency,"
                + " i.rating_score, rt.tier_code rating_tier_code, rt.tier_name rating_tier_name,"
                + " (select sum(c.gmv) from influencer_cooperation c where c.tenant_id = i.tenant_id"
                + "   and c.influencer_id = i.id) cooperation_gmv_total,"
                + " (select sum(c.order_count) from influencer_cooperation c where c.tenant_id = i.tenant_id"
                + "   and c.influencer_id = i.id) cooperation_order_total,"
                + " (select sum(c.commission) from influencer_cooperation c where c.tenant_id = i.tenant_id"
                + "   and c.influencer_id = i.id) cooperation_commission_total,"
                + " (select sum(a.attributed_gmv) from influencer_attribution a where a.tenant_id = i.tenant_id"
                + "   and a.influencer_id = i.id and a.rule_id = ?) attributed_gmv_total,"
                + " (select sum(a.attributed_order_count) from influencer_attribution a where a.tenant_id = i.tenant_id"
                + "   and a.influencer_id = i.id and a.rule_id = ?) attributed_order_total,"
                + " (select sum(a.commission_amount) from influencer_attribution a where a.tenant_id = i.tenant_id"
                + "   and a.influencer_id = i.id and a.rule_id = ?) commission_total,"
                + " (select sum(a.effective_order_count) from influencer_attribution a where a.tenant_id = i.tenant_id"
                + "   and a.influencer_id = i.id and a.rule_id = ?) effective_order_total"
                + " from influencer i"
                + " left join influencer_rating_tier rt on rt.tenant_id = i.tenant_id and rt.id = i.rating_tier_id"
                + " where i.tenant_id = "
                + TENANT
                + " and i.id = ?",
            ruleId,
            ruleId,
            ruleId,
            ruleId,
            influencerId);
    if (r == null) {
      return null;
    }
    BigDecimal attributedGmv = r.get("attributed_gmv_total", BigDecimal.class);
    BigDecimal commission = r.get("commission_total", BigDecimal.class);
    BigDecimal effectiveOrders = r.get("effective_order_total", BigDecimal.class);
    long followers = r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class);
    return new InfluencerAttributionInfluencerSummary(
        r.get("influencer_id", UUID.class),
        r.get("display_name", String.class),
        r.get("handle", String.class),
        r.get("category", String.class),
        followers,
        r.get("follower_tier", String.class),
        r.get("shop_code", String.class),
        r.get("profile_gmv", BigDecimal.class),
        r.get("profile_gmv_currency", String.class),
        r.get("cooperation_gmv_total", BigDecimal.class),
        r.get("cooperation_order_total", BigDecimal.class),
        r.get("cooperation_commission_total", BigDecimal.class),
        attributedGmv,
        r.get("attributed_order_total", BigDecimal.class),
        commission,
        effectiveOrders,
        InfluencerAttributionRules.roi(attributedGmv, commission),
        InfluencerAttributionRules.followerConversionRate(effectiveOrders, followers),
        r.get("rating_score", BigDecimal.class),
        r.get("rating_tier_code", String.class),
        r.get("rating_tier_name", String.class));
  }

  // ==================================================================
  // 归因批次
  // ==================================================================

  /** 一个活动（同一币种）的池子（分摊基数）。 */
  private record Pool(BigDecimal gmv, BigDecimal orderCount, BigDecimal refundRate, long followers) {}

  /** 池子键：活动 + 币种（**不跨币种求和** —— 不同币种的金额加在一起没有意义）。 */
  private static String poolKey(UUID campaignId, String currency) {
    return campaignId + "|" + (currency == null ? "" : currency);
  }

  /**
   * 触发一次归因批次：按**当前生效的归因规则**把合作记录归因到达人。
   *
   * <p>🔴 三件事必须说清：
   *
   * <ol>
   *   <li><b>没有生效规则 ⇒ 409</b>：没有口径就没有归因。
   *   <li><b>归因窗口只挡「新增」</b>：超窗的合作不允许新增归因，但**已经归因过的
   *       记录不会被抹掉**（已归因是事实，佣金按它计提过）。
   *   <li><b>已计提的佣金不自动改写</b>：重算只更新差额（{@code commission_variance}），
   *       计提是记账事实，要改得人显式冲销再计提。
   * </ol>
   */
  public InfluencerAttributionRunResult run(InfluencerAttributionRunRequest request) {
    InfluencerAttributionRuleRow rule = currentRule();
    if (rule == null) {
      throw conflict("归因规则未配置：请先配置一套生效的归因规则（归因模型 / 归因窗口 / 退款口径 / 佣金口径）");
    }

    StringBuilder where = new StringBuilder(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (request.influencerId() != null) {
      where.append(" and c.influencer_id = ?");
      binds.add(request.influencerId());
    }
    if (request.campaignId() != null) {
      where.append(" and c.campaign_id = ?");
      binds.add(request.campaignId());
    }
    String shop = blankToNull(request.shopCode());
    if (shop != null) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    if (request.from() != null) {
      where.append(" and c.started_at >= ?::timestamptz");
      binds.add(request.from());
    }
    if (request.to() != null) {
      where.append(" and c.started_at <= ?::timestamptz");
      binds.add(request.to());
    }
    // 🔴 已取消的合作没有效果，不参与归因（也不进池子）。
    where.append(" and c.status <> 'CANCELLED'");

    List<Record> cooperations =
        sql.fetch(
            "select c.id cooperation_id, c.influencer_id, c.campaign_id, c.shop_code, c.started_at,"
                + " c.ended_at, c.gmv source_gmv, c.order_count source_order_count,"
                + " c.commission source_commission, c.refund_rate source_refund_rate,"
                + " c.currency source_currency, i.follower_count, i.display_name"
                + " from influencer_cooperation c"
                + " join influencer i on i.tenant_id = c.tenant_id and i.id = c.influencer_id"
                + where
                + " order by c.id",
            binds.toArray());

    Map<String, Pool> pools = pools(cooperations);

    OffsetDateTime now = OffsetDateTime.now();
    int processed = 0;
    int created = 0;
    int refreshed = 0;
    int skippedOutOfWindow = 0;
    int skippedNoEffectData = 0;
    int skippedNoSplitBasis = 0;
    Set<UUID> touched = new LinkedHashSet<>();

    for (Record c : cooperations) {
      processed++;
      UUID cooperationId = c.get("cooperation_id", UUID.class);
      UUID influencerId = c.get("influencer_id", UUID.class);
      BigDecimal sourceGmv = c.get("source_gmv", BigDecimal.class);
      BigDecimal sourceOrderCount = c.get("source_order_count", BigDecimal.class);
      BigDecimal sourceRefundRate = c.get("source_refund_rate", BigDecimal.class);
      BigDecimal sourceCommission = c.get("source_commission", BigDecimal.class);
      long followers = c.get("follower_count", Long.class) == null ? 0L : c.get("follower_count", Long.class);
      OffsetDateTime endedAt = c.get("ended_at", OffsetDateTime.class);

      boolean exists =
          count(
                  "select count(*) from influencer_attribution where tenant_id = ?::uuid"
                      + " and influencer_id = ?::uuid and cooperation_id = ?::uuid and rule_id = ?::uuid",
                  currentTenant(),
                  influencerId,
                  cooperationId,
                  rule.id())
              > 0;

      // 🔴 窗口只挡「新增归因」：已归因过的记录继续刷新（它已经是事实）。
      if (!exists && !InfluencerAttributionRules.withinWindow(endedAt, rule.attributionWindowDays(), now)) {
        skippedOutOfWindow++;
        continue;
      }

      // 🔴 没有任何效果数据 ⇒ 归因不出东西（不是「归因 GMV = 0」）。
      if (sourceGmv == null && sourceOrderCount == null) {
        skippedNoEffectData++;
        continue;
      }

      BigDecimal baseGmv;
      BigDecimal baseOrderCount;
      BigDecimal baseRefundRate;
      BigDecimal weight;
      if ("CAMPAIGN_POOL_SPLIT".equals(rule.attributionModel())) {
        Pool pool =
            pools.get(
                poolKey(
                    c.get("campaign_id", UUID.class), c.get("source_currency", String.class)));
        if (pool == null) {
          skippedNoSplitBasis++;
          continue;
        }
        baseGmv = pool.gmv();
        baseOrderCount = pool.orderCount();
        baseRefundRate = pool.refundRate();
        weight =
            InfluencerAttributionRules.weight(
                rule.attributionModel(),
                rule.poolWeightBasis(),
                followers,
                pool.followers(),
                sourceGmv,
                pool.gmv());
      } else {
        baseGmv = sourceGmv;
        baseOrderCount = sourceOrderCount;
        baseRefundRate = sourceRefundRate;
        weight = InfluencerAttributionRules.weight(rule.attributionModel(), rule.poolWeightBasis(), followers, 0L, null, null);
      }
      if (weight == null) {
        skippedNoSplitBasis++;
        continue;
      }

      BigDecimal grossGmv = InfluencerAttributionRules.grossGmv(baseGmv, weight);
      BigDecimal refundAmount = InfluencerAttributionRules.refundAmount(grossGmv, baseRefundRate);
      BigDecimal attributedGmv =
          InfluencerAttributionRules.attributedGmv(grossGmv, refundAmount, rule.refundTreatment());
      BigDecimal attributedOrders =
          InfluencerAttributionRules.attributedOrderCount(baseOrderCount, weight);
      BigDecimal effectiveOrders =
          InfluencerAttributionRules.effectiveOrderCount(attributedOrders, baseRefundRate);
      BigDecimal conversionRate =
          InfluencerAttributionRules.followerConversionRate(effectiveOrders, followers);
      BigDecimal commissionAmount =
          InfluencerAttributionRules.commissionAmount(
              rule.commissionBasis(), sourceCommission, attributedGmv, rule.commissionRate());
      BigDecimal commissionBasisAmount =
          InfluencerAttributionRules.commissionBasisAmount(
              rule.commissionBasis(), sourceCommission, attributedGmv);

      upsertAttribution(
          rule,
          c,
          influencerId,
          cooperationId,
          weight,
          baseGmv,
          baseOrderCount,
          baseRefundRate,
          grossGmv,
          refundAmount,
          attributedGmv,
          attributedOrders,
          effectiveOrders,
          conversionRate,
          commissionAmount,
          commissionBasisAmount,
          sourceGmv,
          sourceOrderCount,
          sourceCommission,
          sourceRefundRate,
          c.get("source_currency", String.class));
      touched.add(influencerId);
      if (exists) {
        refreshed++;
      } else {
        created++;
      }
    }

    // 🔴 归因写完后触发一次评级重算（**评级口径归 14.5.1.1**，这里只调用既有服务方法，
    //   不跨服务 HTTP 自调，也不改它的表结构与规则表）。
    for (UUID influencerId : touched) {
      influencerService.applyRating(influencerId);
    }

    return new InfluencerAttributionRunResult(
        true,
        rule.ruleCode(),
        rule.attributionModel(),
        processed,
        created,
        refreshed,
        skippedOutOfWindow,
        skippedNoEffectData,
        skippedNoSplitBasis,
        touched.size(),
        "按规则 "
            + rule.ruleCode()
            + "（"
            + rule.attributionModel()
            + "）处理 "
            + processed
            + " 条合作记录：新建 "
            + created
            + " 条、刷新 "
            + refreshed
            + " 条、跳过出窗 "
            + skippedOutOfWindow
            + " 条、跳过无效果数据 "
            + skippedNoEffectData
            + " 条、跳过分摊基准不可用 "
            + skippedNoSplitBasis
            + " 条。");
  }

  /** 活动池子（分摊基数）：按活动 + 币种聚合合作 GMV / 订单量 / 加权退款率 / 粉丝数合计。 */
  private Map<String, Pool> pools(List<Record> cooperations) {
    Map<String, Pool> pools = new LinkedHashMap<>();
    Set<String> keys = new LinkedHashSet<>();
    for (Record c : cooperations) {
      keys.add(poolKey(c.get("campaign_id", UUID.class), c.get("source_currency", String.class)));
    }
    if (keys.isEmpty()) {
      return pools;
    }
    List<Object> binds = new ArrayList<>();
    StringBuilder in = new StringBuilder("(");
    boolean first = true;
    for (String key : keys) {
      int bar = key.indexOf('|');
      if (!first) {
        in.append(" or ");
      }
      // 🔴 用显式 OR 而不是行值 IN：PG 无法从行构造器里推断 `?` 的类型，
      //   jOOQ 3.19 会把它绑成 unknown ⇒ "could not determine data type of parameter"。
      in.append("(c.campaign_id = ?::uuid and c.currency = ?::text)");
      binds.add(UUID.fromString(key.substring(0, bar)));
      binds.add(key.substring(bar + 1));
      first = false;
    }
    in.append(")");
    List<Record> rows =
        sql.fetch(
            "select c.campaign_id, c.currency, c.gmv, c.order_count, c.refund_rate, i.follower_count"
                + " from influencer_cooperation c"
                + " join influencer i on i.tenant_id = c.tenant_id and i.id = c.influencer_id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.status <> 'CANCELLED'"
                + " and "
                + in,
            binds.toArray());
    Map<String, List<Record>> grouped = new LinkedHashMap<>();
    for (Record r : rows) {
      grouped
          .computeIfAbsent(
              poolKey(r.get("campaign_id", UUID.class), r.get("currency", String.class)),
              k -> new ArrayList<>())
          .add(r);
    }
    for (Map.Entry<String, List<Record>> entry : grouped.entrySet()) {
      BigDecimal gmv = null;
      BigDecimal orders = null;
      BigDecimal refundWeighted = BigDecimal.ZERO;
      BigDecimal gmvTotal = BigDecimal.ZERO;
      long followers = 0L;
      boolean anyGmv = false;
      boolean anyOrders = false;
      for (Record r : entry.getValue()) {
        BigDecimal rowGmv = r.get("gmv", BigDecimal.class);
        BigDecimal rowOrders = r.get("order_count", BigDecimal.class);
        BigDecimal rowRefund = r.get("refund_rate", BigDecimal.class);
        long rowFollowers = r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class);
        followers += rowFollowers;
        if (rowGmv != null) {
          anyGmv = true;
          gmv = gmv == null ? rowGmv : gmv.add(rowGmv);
          gmvTotal = gmvTotal.add(rowGmv);
          if (rowRefund != null) {
            refundWeighted = refundWeighted.add(rowGmv.multiply(rowRefund));
          }
        }
        if (rowOrders != null) {
          anyOrders = true;
          orders = orders == null ? rowOrders : orders.add(rowOrders);
        }
      }
      BigDecimal refundRate =
          anyGmv && gmvTotal.signum() > 0
              ? refundWeighted.divide(gmvTotal, RATIO_SCALE, RoundingMode.HALF_UP)
              : null;
      pools.put(
          entry.getKey(),
          new Pool(
              anyGmv ? gmv : null, anyOrders ? orders : null, refundRate, followers));
    }
    return pools;
  }

  /** 写一条归因记录（幂等：同一条合作 × 同一个规则只有一行）。 */
  private void upsertAttribution(
      InfluencerAttributionRuleRow rule,
      Record cooperation,
      UUID influencerId,
      UUID cooperationId,
      BigDecimal weight,
      BigDecimal baseGmv,
      BigDecimal baseOrderCount,
      BigDecimal baseRefundRate,
      BigDecimal grossGmv,
      BigDecimal refundAmount,
      BigDecimal attributedGmv,
      BigDecimal attributedOrders,
      BigDecimal effectiveOrders,
      BigDecimal conversionRate,
      BigDecimal commissionAmount,
      BigDecimal commissionBasisAmount,
      BigDecimal sourceGmv,
      BigDecimal sourceOrderCount,
      BigDecimal sourceCommission,
      BigDecimal sourceRefundRate,
      String sourceCurrency) {
    UUID id = Ids.next();
    // 🔴 占位符数量用代码数出来（38 个绑定 + version/created_at/updated_at 三个常量），
    //   手写一长串 "?,?,?..." 很容易数错一个 —— 数错就是「绑定与列对不上」的运行期报错。
    // 🔴 两个 timestamptz 位（accrued_at / attributed_at）必须显式 ?::timestamptz：
    //   jOOQ 3.19 把 OffsetDateTime 绑成 varchar，PG 直接拒收
    //   （"column attributed_at is of type timestamp with time zone but expression is of type character varying"）。
    String[] slots = new String[38];
    java.util.Arrays.fill(slots, "?");
    slots[34] = "?::timestamptz"; // accrued_at
    slots[36] = "?::timestamptz"; // attributed_at
    String values = "(" + String.join(",", slots) + ",0,now(),now())";
    sql.execute(
        "insert into influencer_attribution (tenant_id,id,influencer_id,cooperation_id,rule_id,"
            + " campaign_id,shop_code,attribution_model,pool_weight_basis,attribution_window_days,"
            + " refund_treatment,commission_basis,commission_rate,attribution_weight,"
            + " source_gmv,source_order_count,source_commission,source_refund_rate,source_currency,"
            + " attribution_base_gmv,attribution_base_order_count,attribution_base_refund_rate,"
            + " attributed_gmv,attributed_order_count,attributed_refund_rate,attributed_refund_amount,"
            + " effective_order_count,follower_conversion_rate,follower_count_snapshot,currency,"
            + " commission_amount,commission_basis_amount,commission_variance,"
            + " accrual_status,accrued_at,accrual_note,attributed_at,note,version,created_at,updated_at)"
            + " values "
            + values
            + " on conflict (tenant_id,influencer_id,cooperation_id,rule_id) do update set"
            + " attribution_model = excluded.attribution_model,"
            + " pool_weight_basis = excluded.pool_weight_basis,"
            + " attribution_window_days = excluded.attribution_window_days,"
            + " refund_treatment = excluded.refund_treatment,"
            + " commission_basis = excluded.commission_basis,"
            + " commission_rate = excluded.commission_rate,"
            + " attribution_weight = excluded.attribution_weight,"
            + " source_gmv = excluded.source_gmv,"
            + " source_order_count = excluded.source_order_count,"
            + " source_commission = excluded.source_commission,"
            + " source_refund_rate = excluded.source_refund_rate,"
            + " source_currency = excluded.source_currency,"
            + " attribution_base_gmv = excluded.attribution_base_gmv,"
            + " attribution_base_order_count = excluded.attribution_base_order_count,"
            + " attribution_base_refund_rate = excluded.attribution_base_refund_rate,"
            + " attributed_gmv = excluded.attributed_gmv,"
            + " attributed_order_count = excluded.attributed_order_count,"
            + " attributed_refund_rate = excluded.attributed_refund_rate,"
            + " attributed_refund_amount = excluded.attributed_refund_amount,"
            + " effective_order_count = excluded.effective_order_count,"
            + " follower_conversion_rate = excluded.follower_conversion_rate,"
            + " follower_count_snapshot = excluded.follower_count_snapshot,"
            + " currency = excluded.currency,"
            // 🔴 已计提的佣金**不自动改写**（计提是记账事实），只更新差额。
            + " commission_amount = case when influencer_attribution.accrual_status = 'PENDING'"
            + "   then excluded.commission_amount else influencer_attribution.commission_amount end,"
            + " commission_basis_amount = case when influencer_attribution.accrual_status = 'PENDING'"
            + "   then excluded.commission_basis_amount"
            + "   else influencer_attribution.commission_basis_amount end,"
            + " commission_variance = excluded.commission_amount - case"
            + "   when influencer_attribution.accrual_status = 'PENDING' then excluded.commission_amount"
            + "   else influencer_attribution.commission_amount end,"
            + " updated_at = now(), version = influencer_attribution.version + 1",
        currentTenant(),
        id,
        influencerId,
        cooperationId,
        rule.id(),
        cooperation.get("campaign_id", UUID.class),
        cooperation.get("shop_code", String.class),
        rule.attributionModel(),
        rule.poolWeightBasis(),
        rule.attributionWindowDays(),
        rule.refundTreatment(),
        rule.commissionBasis(),
        rule.commissionRate(),
        weight,
        sourceGmv,
        sourceOrderCount,
        sourceCommission,
        sourceRefundRate,
        sourceCurrency,
        baseGmv,
        baseOrderCount,
        baseRefundRate,
        attributedGmv,
        attributedOrders,
        baseRefundRate,
        refundAmount,
        effectiveOrders,
        conversionRate,
        cooperation.get("follower_count", Long.class) == null
            ? 0L
            : cooperation.get("follower_count", Long.class),
        cooperation.get("source_currency", String.class),
        commissionAmount,
        commissionBasisAmount,
        null,
        "PENDING",
        null,
        "",
        OffsetDateTime.now(),
        "");
  }

  // ==================================================================
  // 佣金计提
  // ==================================================================

  /**
   * 变更佣金计提状态（计提 / 冲销 / 退回待计提）。
   *
   * <p>🔴 计提是**记账事实**：状态变更会留痕（时刻 + 备注），
   * 归因批次重跑<b>不会</b>自动改写已计提的金额（只更新差额）。
   */
  public InfluencerAttributionDetail accrue(String id, InfluencerAttributionAccrualRequest request) {
    UUID attributionId = parseId(id);
    if (count(
            "select count(*) from influencer_attribution where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            attributionId)
        == 0) {
      throw notFound("归因记录");
    }
    String status = text(request.status()).toUpperCase(Locale.ROOT);
    if (!ACCRUAL_STATUSES.contains(status)) {
      throw badRequest("计提状态取值域是 " + ACCRUAL_STATUSES);
    }
    if ("PENDING".equals(status)) {
      sql.execute(
          "update influencer_attribution set accrual_status = 'PENDING', accrued_at = null,"
              + " accrual_note = ?, updated_at = now(), version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          text(request.note()),
          currentTenant(),
          attributionId);
    } else if ("REVERSED".equals(status)) {
      sql.execute(
          "update influencer_attribution set accrual_status = 'REVERSED',"
              + " accrued_at = coalesce(accrued_at, ?::timestamptz), accrual_note = ?,"
              + " updated_at = now(), version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          OffsetDateTime.now(),
          text(request.note()),
          currentTenant(),
          attributionId);
    } else {
      sql.execute(
          "update influencer_attribution set accrual_status = 'ACCRUED', accrued_at = ?::timestamptz,"
              + " accrual_note = ?, updated_at = now(), version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          OffsetDateTime.now(),
          text(request.note()),
          currentTenant(),
          attributionId);
    }
    return attribution(id);
  }

  // ==================================================================
  // 达人效果报表
  // ==================================================================

  /**
   * 达人效果报表：**三个 GMV 口径分列三栏** + ROI + 有效粉丝转化 + 评级。
   *
   * <p>🔴 归因口径按**生效的规则**汇总（口径变了要能分开看历史），
   * 所以报表默认只统计当前生效规则下的归因记录；换规则后的报表按新规则重算，
   * 旧规则下的归因仍可在归因记录列表里按 {@code ruleCode} 查到。
   */
  public InfluencerAttributionReportPage report(
      UUID influencerId,
      UUID campaignId,
      String shopCode,
      String platformCode,
      String category,
      String followerTier,
      String keyword,
      BigDecimal minAttributedGmv,
      BigDecimal maxAttributedGmv,
      String sort,
      int page,
      int size) {
    int safePage = Math.max(page, 0);
    int safeSize = clampSize(size);
    InfluencerAttributionRuleRow rule = currentRule();
    UUID ruleId = rule == null ? null : rule.id();

    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where i.tenant_id = " + TENANT);
    if (influencerId != null) {
      where.append(" and i.id = ?");
      binds.add(influencerId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and i.shop_code = ?");
      binds.add(shop);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and pf.code = ?");
      binds.add(platform.toUpperCase(Locale.ROOT));
    }
    String cat = blankToNull(category);
    if (cat != null) {
      where.append(" and i.category = ?");
      binds.add(cat);
    }
    String tier = blankToNull(followerTier);
    if (tier != null) {
      where.append(" and i.follower_tier = ?");
      binds.add(tier.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (i.display_name ilike ? or i.handle ilike ?)");
      String like = "%" + escapeLike(word) + "%";
      binds.add(like);
      binds.add(like);
    }
    if (campaignId != null) {
      where.append(
          " and exists (select 1 from influencer_attribution a where a.tenant_id = i.tenant_id"
              + " and a.influencer_id = i.id and a.campaign_id = ?"
              + (ruleId == null ? "" : " and a.rule_id = ?::uuid") + ")");
      binds.add(campaignId);
      if (ruleId != null) {
        binds.add(ruleId);
      }
    }
    if (minAttributedGmv != null) {
      where.append(" and att.attributed_gmv_total >= ?");
      binds.add(minAttributedGmv);
    }
    if (maxAttributedGmv != null) {
      where.append(" and att.attributed_gmv_total <= ?");
      binds.add(maxAttributedGmv);
    }

    String cte = reportCte(ruleId);
    // 🔴 绑定顺序：CTE 在 FROM 里，它的占位符**先于** where 里的占位符。
    List<Object> cteBinds = ruleId == null ? List.of() : List.of(ruleId);
    List<Object> allBinds = new ArrayList<>(cteBinds);
    allBinds.addAll(binds);
    String orderBy =
        SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), SORTS.get("ATTRIBUTED_GMV_DESC"));
    long total =
        count("select count(*) from influencer i" + cte + where, allBinds.toArray());
    List<InfluencerAttributionReportRow> items =
        sql.fetch(
                "select i.id influencer_id, i.display_name, i.handle, i.category, i.follower_count,"
                    + " i.follower_tier, i.shop_code, pf.code platform_code,"
                    + " i.gmv profile_gmv, i.gmv_currency profile_gmv_currency,"
                    + " coop.cooperation_gmv_total, coop.cooperation_order_total,"
                    + " coop.cooperation_commission_total,"
                    + " att.attributed_gmv_total, att.attributed_order_total,"
                    + " (case when att.gross_total > 0 and att.attributed_gmv_total is not null"
                    + "   then round(att.refund_amount_total / att.gross_total, 6) end)"
                    + "   attributed_refund_rate,"
                    + " att.commission_total, att.commission_accrued_total, att.commission_pending_total,"
                    + " att.effective_order_total,"
                    + " (case when i.follower_count > 0 and att.effective_order_total is not null"
                    + "   then round(att.effective_order_total / i.follower_count, 6) end)"
                    + "   follower_conversion_rate,"
                    + " (case when att.commission_total > 0 and att.attributed_gmv_total is not null"
                    + "   then round(att.attributed_gmv_total / att.commission_total, 6) end) roi,"
                    + " (case when att.commission_total is not null and att.attributed_gmv_total is not null"
                    + "   then round(att.attributed_gmv_total - att.commission_total, 4) end)"
                    + "   net_gmv_after_commission,"
                    + " coalesce(att.attribution_count, 0) attribution_count,"
                    + " i.rating_score, rt.tier_code rating_tier_code, rt.tier_name rating_tier_name"
                    + " from influencer i"
                    + " join platform pf on pf.id = i.platform_id"
                    + " left join influencer_rating_tier rt on rt.tenant_id = i.tenant_id"
                    + "   and rt.id = i.rating_tier_id"
                    + cte
                    + where
                    + " order by "
                    + orderBy
                    + " limit ? offset ?",
                concat(allBinds, safeSize, safePage * safeSize))
            .stream()
            .map(this::reportRow)
            .toList();

    return new InfluencerAttributionReportPage(
        items,
        safePage,
        safeSize,
        total,
        reportSummary(cte, where.toString(), allBinds, rule),
        CALIBER_NOTE,
        RULE_NOTE,
        COMMISSION_NOTE,
        profitIntegration());
  }

  /** 报表的 CTE：归因口径与合作口径各一份聚合。 */
  private String reportCte(UUID ruleId) {
    String ruleFilter = ruleId == null ? " and false" : " and a.rule_id = ?::uuid";
    return " left join (select a.influencer_id,"
        + " sum(a.attributed_gmv) attributed_gmv_total,"
        + " sum(a.attributed_order_count) attributed_order_total,"
        + " sum(coalesce(a.attributed_refund_amount,0)) refund_amount_total,"
        // 🔴 归因毛额 = 归因基数 × 权重（GROSS 与 NET 两种退款口径下都成立），
        //   所以加权退款率可以用「退款额合计 ÷ 毛额合计」算，不必再存一个毛额列。
        + " sum(a.attribution_base_gmv * a.attribution_weight) gross_total,"
        + " sum(a.commission_amount) commission_total,"
        + " (case when sum(a.commission_amount) is null then null else sum(case"
        + "   when a.accrual_status = 'ACCRUED' then coalesce(a.commission_amount,0) else 0 end) end)"
        + "   commission_accrued_total,"
        + " (case when sum(a.commission_amount) is null then null else sum(case"
        + "   when a.accrual_status = 'PENDING' then coalesce(a.commission_amount,0) else 0 end) end)"
        + "   commission_pending_total,"
        + " sum(a.effective_order_count) effective_order_total,"
        + " count(*) attribution_count"
        + " from influencer_attribution a where a.tenant_id = "
        + TENANT
        + ruleFilter
        + " group by a.influencer_id) att on att.influencer_id = i.id"
        + " left join (select c.influencer_id, sum(c.gmv) cooperation_gmv_total,"
        + " sum(c.order_count) cooperation_order_total, sum(c.commission) cooperation_commission_total"
        + " from influencer_cooperation c where c.tenant_id = "
        + TENANT
        + " group by c.influencer_id) coop on coop.influencer_id = i.id";
  }

  private InfluencerAttributionReportRow reportRow(Record r) {
    return new InfluencerAttributionReportRow(
        r.get("influencer_id", UUID.class),
        r.get("display_name", String.class),
        r.get("handle", String.class),
        r.get("category", String.class),
        r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class),
        r.get("follower_tier", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("profile_gmv", BigDecimal.class),
        r.get("profile_gmv_currency", String.class),
        r.get("cooperation_gmv_total", BigDecimal.class),
        r.get("cooperation_order_total", BigDecimal.class),
        r.get("cooperation_commission_total", BigDecimal.class),
        r.get("attributed_gmv_total", BigDecimal.class),
        r.get("attributed_order_total", BigDecimal.class),
        r.get("attributed_refund_rate", BigDecimal.class),
        r.get("commission_total", BigDecimal.class),
        r.get("commission_accrued_total", BigDecimal.class),
        r.get("commission_pending_total", BigDecimal.class),
        r.get("effective_order_total", BigDecimal.class),
        r.get("follower_conversion_rate", BigDecimal.class),
        r.get("roi", BigDecimal.class),
        r.get("net_gmv_after_commission", BigDecimal.class),
        r.get("attribution_count", Long.class) == null ? 0 : r.get("attribution_count", Long.class).intValue(),
        r.get("rating_score", BigDecimal.class),
        r.get("rating_tier_code", String.class),
        r.get("rating_tier_name", String.class));
  }

  private InfluencerAttributionReportSummary reportSummary(
      String cte, String where, List<Object> binds, InfluencerAttributionRuleRow rule) {
    Record r =
        sql.fetchOne(
                "select count(*) filter (where coalesce(att.attribution_count,0) > 0) influencer_count,"
                    + " coalesce(sum(att.attribution_count),0) attribution_count,"
                    + " sum(att.attributed_gmv_total) attributed_gmv_total,"
                    + " sum(att.commission_total) commission_total,"
                    + " sum(att.commission_accrued_total) commission_accrued_total,"
                    + " sum(att.commission_pending_total) commission_pending_total,"
                    + " sum(att.effective_order_total) effective_order_total,"
                    + " (case when sum(att.commission_total) > 0"
                    + "    and sum(att.attributed_gmv_total) is not null"
                    + "   then round(sum(att.attributed_gmv_total) / sum(att.commission_total), 6) end) roi,"
                    + " (case when sum(i.follower_count) > 0 and sum(att.effective_order_total) is not null"
                    + "   then round(sum(att.effective_order_total) / sum(i.follower_count), 6) end)"
                    + "   follower_conversion_rate"
                    + " from influencer i"
                    + cte
                    + where,
                binds.toArray());
    return new InfluencerAttributionReportSummary(
        r.get("influencer_count", Long.class) == null ? 0 : r.get("influencer_count", Long.class).intValue(),
        r.get("attribution_count", Long.class) == null ? 0L : r.get("attribution_count", Long.class),        r.get("attributed_gmv_total", BigDecimal.class),
        r.get("commission_total", BigDecimal.class),
        r.get("commission_accrued_total", BigDecimal.class),
        r.get("commission_pending_total", BigDecimal.class),
        r.get("roi", BigDecimal.class),
        r.get("effective_order_total", BigDecimal.class),
        r.get("follower_conversion_rate", BigDecimal.class),
        rule == null ? null : rule.ruleCode(),
        rule == null ? null : rule.attributionModel(),
        profitIntegration());
  }
}
