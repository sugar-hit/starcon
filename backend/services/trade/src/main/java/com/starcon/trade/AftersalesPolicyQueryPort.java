package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取 17.5.1.1 业务参数的<b>跨服务端口</b> —— 11.1.1.3 售后受理「政策判定」的唯一真身。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 89 行把「售后政策配置（期限、条件、必退情形）」列为本模块的
 * <b>输入</b>，而它在 <b>identity 服务的独立库</b>里（identity V48
 * {@code business_parameter_definition} / {@code business_parameter_override}，
 * 代码 {@code backend/services/identity/.../BusinessParameterController.java}）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 identity 的授权只读端点</b>读，
 * <b>不在 trade 库里落一份影子副本</b> ——
 * 影子副本会让「参数已改」这件事在两个服务里给出两个答案，而受理判定是平台判责的计时基准。
 *
 * <p>用法与 {@link DealerPartyQueryPort}（读 catalog）、{@link PackingQueryPort}（读 warehouse）逐字同构：
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回一份编造的默认值冒充
 * 「这个租户用的是出厂默认」—— 编造的期限会让一张早该判超窗的单被放行。
 *
 * <p><b>本模块读的两条参数</b>（实测 identity V48 的 8 个出厂默认值里 AFTERSALE 类恰好只有这两条）：
 *
 * <ul>
 *   <li>{@code AFTERSALE_RETURN_WINDOW_DAYS}（INTEGER，出厂默认 30，单位「天」）——
 *       「判定是否在售后期内」的天数。</li>
 *   <li>{@code AFTERSALE_MUST_REFUND_REASONS}（MULTI_ENUM，出厂默认
 *       {@code DAMAGED_ON_ARRIVAL,MISSING_ITEM,WRONG_ITEM}，合法取值 6 个）——
 *       「必退情形」词表，蓝图加粗句「必退情形（平台强制、收到即退）要自动判定」的数据来源。</li>
 * </ul>
 *
 * <p>⚠️ <b>刻意不读「回复时限」参数</b>：identity V48 里**没有**这条参数（实测确认），
 * 所以回复时限落在本服务自己的 {@code aftersales_reply_sla}（V164，租户可维护）。
 * 在这里编一个「回复时限参数」出来会让运营在业务参数页改一个根本不存在的东西。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * identity 侧仍按已认证成员关系解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>
 * （trade 侧 RLS + identity 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class AftersalesPolicyQueryPort {

  /** 出厂默认的售后期限参数码。 */
  public static final String PARAM_RETURN_WINDOW_DAYS = "AFTERSALE_RETURN_WINDOW_DAYS";

  /** 出厂默认的售后必退情形参数码。 */
  public static final String PARAM_MUST_REFUND_REASONS = "AFTERSALE_MUST_REFUND_REASONS";

  /** 身份服务侧的生效档（与 identity V48 的 {@code source_scope} 同一词表）。 */
  public record EffectiveParam(
      String parameterCode,
      String valueType,
      String value,
      BigDecimal valueNumeric,
      List<String> valueCodes,
      String sourceScope) {}

  private final RestClient client;

  public AftersalesPolicyQueryPort(@Value("${IDENTITY_URL}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时 5 秒：受理是一次交互动作，运营在页面上等。
    // 但仍然必须有上限 —— 没有上限时身份服务一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一条参数的**生效值**（按 identity 侧同一套优先级 店铺 &gt; 法人 &gt; 全局 &gt; 出厂默认解析）。
   *
   * <p>本模块不传 {@code legalEntityId} / {@code shopId}：售后单只到平台级（{@code order_source}），
   * 拿不到法人与店铺维度 ⇒ 生效档自然落在 GLOBAL 与 DEFAULT 两档，
   * 返回值里的 {@code sourceScope} 会**如实**报出实际落在哪一档（见 {@link #scopeOf}）。
   */
  public EffectiveParam effective(String parameterCode, String bearer) {
    return allEffective(bearer).stream()
        .filter(p -> parameterCode.equals(p.parameterCode()))
        .findFirst()
        .orElseThrow(() -> missing(parameterCode));
  }

  /** 一次请求拿回全部参数的生效值（identity 的 {@code /effective} 本来就是全量返回）。 */
  private List<EffectiveParam> allEffective(String bearer) {
    List<EffectiveParam> all;
    try {
      all =
          client
              .get()
              .uri("/identity/business-parameters/effective")
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<EffectiveParam>>() {});
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取售后政策参数，受理暂时做不了");
      }
      throw unavailable("身份服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("身份服务暂时不可达");
    }
    if (all == null) {
      throw unavailable("身份服务没有返回业务参数生效值");
    }
    return all;
  }

  private ResponseStatusException missing(String parameterCode) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "身份服务没有返回参数 "
            + parameterCode
            + " 的生效值，售后政策判定无法进行：受理期限与必退词表的唯一真身是"
            + "17.5.1.1 业务参数，本服务不会凭空造一份期限常量");
  }

  /**
   * 把 identity 的 {@code sourceScope} 映射到判定凭据上落库的取值。
   *
   * <p>identity 侧未命中任何覆盖档时返回 {@code DEFAULT}，本表用 {@code DEFINITION_DEFAULT} 表达同一件事 ——
   * 「用的是出厂默认值」这句话在两个服务里应当一眼能对上，而不是同一个意思两个词。
   */
  public static String scopeOf(String sourceScope) {
    if (sourceScope == null) return "DEFINITION_DEFAULT";
    return switch (sourceScope) {
      case "SHOP" -> "SHOP";
      case "LEGAL_ENTITY" -> "LEGAL_ENTITY";
      case "GLOBAL" -> "GLOBAL";
      default -> "DEFINITION_DEFAULT";
    };
  }

  /** 不可达一律 503 并把「为什么」如实报出，绝不返回编造默认值。 */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：售后政策的唯一真身是 17.5.1.1 业务参数"
            + "（identity 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份售后期限或必退词表");
  }

  /** 一次读取得到的两条参数（受理判定只读这一次，保证同一张单的两个判定用同一份政策）。 */
  public PolicySnapshot snapshot(String bearer) {
    // ⚠️ **只发一次请求**：两条参数必须来自**同一次**读取。
    //   分两次读的话，运营恰好在两次之间改了必退词表，同一张单就会拿到
    //   「旧期限 + 新词表」这种现实中不存在的组合，而判定凭据是把两者一起冻结的。
    List<EffectiveParam> all = allEffective(bearer);
    EffectiveParam window = pick(all, PARAM_RETURN_WINDOW_DAYS);
    EffectiveParam mustRefund = pick(all, PARAM_MUST_REFUND_REASONS);

    BigDecimal days = window.valueNumeric();
    if (days == null) {
      throw unavailable("参数 " + PARAM_RETURN_WINDOW_DAYS + " 没有数值型生效值");
    }
    int dayCount;
    try {
      dayCount = days.intValueExact();
    } catch (ArithmeticException e) {
      // 非整数天数（如 30.5）会让「窗口截止时刻」变成一个说不清的中间点，
      // 而 identity 侧的 validation_pattern 本就只允许整数；这里显式拒绝而不是悄悄截断。
      throw unavailable("参数 " + PARAM_RETURN_WINDOW_DAYS + " 的生效值不是整数天：" + days);
    }
    if (dayCount <= 0) {
      throw unavailable("参数 " + PARAM_RETURN_WINDOW_DAYS + " 的生效值不是正数：" + days);
    }

    // 词表归一（去空、去重、排序后存）：冻结下来的凭据要能在两个月的两次判定之间逐字比对，
    // 顺序随 identity 侧返回顺序抖动会让「改没改过词表」这件事看不出来。
    List<String> reasons =
        mustRefund.valueCodes() == null
            ? List.of()
            : mustRefund.valueCodes().stream()
                .filter(r -> r != null && !r.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();

    return new PolicySnapshot(
        dayCount, reasons, scopeOf(window.sourceScope()), scopeOf(mustRefund.sourceScope()));
  }

  private EffectiveParam pick(List<EffectiveParam> all, String code) {
    return all.stream()
        .filter(p -> code.equals(p.parameterCode()))
        .findFirst()
        .orElseThrow(() -> missing(code));
  }

  /** 一次读取得到的两条参数：期限天数、必退词表，以及各自来自哪一档。 */
  public record PolicySnapshot(
      int returnWindowDays, List<String> mustRefundReasons, String windowScope, String reasonsScope) {}
}
