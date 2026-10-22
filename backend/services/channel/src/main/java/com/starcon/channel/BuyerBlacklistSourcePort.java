package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.5.1.1 黑名单的<b>跨服务只读端口</b>：9.2 审单（trade 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 trade 的
 * 授权 HTTP API</b>读 9.2.1.2 的租户自维护买家画像，不直连对方库。与
 * {@link BuyerRiskScoreSourcePort}（16.5.1.2）、{@link CompliancePrecheckSourcePort}（16.2.2.1）
 * 同一手法。
 *
 * <p>🔴🔴 <b>本端口把「读不到」表达成一个状态，而不是一个空集合</b> ——
 * 理由与 16.5.1.2 一致，但这里的后果更重：
 * 本模块的产出是<b>「这个买家被拉黑了 / 没被拉黑」</b>，一条上游读面读不到时，
 * 正确答案必须是<b>「读不到」</b>（服务层回 503），**不是**「没查到 ⇒ 他没风险」。
 * 把读不到折成「没风险」是这类名单系统最致命的一种撒谎：<b>一次误放行就是一件真实的欺诈损失</b>。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>端口号</b>：trade 是 <b>8085</b>。
 *
 * <p>🔴 <b>主体键的算法不在这里另写一份</b>：直接用 16.5.1.2 的
 * {@link BuyerRiskScoreSourcePort#buyerKey(String, String, String)}（同服务同包）。
 * 「同一个人的判定必须一致」是这两条模块之间最要紧的一条不变量 ——
 * 两处各写一份 md5，早晚会漂移一次，而漂移的后果是「9.2.1.2 拉黑了这个人、
 * 16.5.1.1 判定不是黑名单买家」这种无法排查的漏拦。
 */
@Service
public class BuyerBlacklistSourcePort {

  /** 09 订单 / 9.2 审单（trade 服务）。 */
  static final String TRADE_URL = "http://127.0.0.1:8085";

  /** 9.2.1.2 租户自维护买家画像（带 buyer_key）。 */
  static final String BUYER_PROFILES_PATH = "/trade/order-review/buyer-profiles";

  /** 单页条数上限。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多读多少页。
   *
   * <p>🔴 <b>达到上限时结果标成「不完整」而不是悄悄截断</b>：截断出来的名册会漏掉
   * 某个买家 —— 那等于「这个人没被拉黑」，是一次静默的漏拦。
   */
  static final int MAX_PAGES = 100;

  // ================================================================ 结果形状

  /** 9.2 审单侧的买家画像一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceBuyerProfile(
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason) {}

  /**
   * 买家画像读出结果。
   *
   * @param available 上游**读得到**吗（false ⇒ 调用方必须回 503，不许当成「没人被拉黑」）
   * @param profiles 读到的画像（available=false 时**必须**是空列表 + note 写明原因）
   */
  public record SourceProfiles(boolean available, List<SourceBuyerProfile> profiles, long total, String note) {}

  private final RestClient client;

  public BuyerBlacklistSourcePort(@Value("${TRADE_URL:" + TRADE_URL + "}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读 9.2.1.2 的租户自维护买家名册（**整册读**，不在上游侧做模糊过滤）。
   *
   * <p>🔴 为什么整册读而不用上游的 {@code keyword} 过滤：keyword 是**模糊**匹配，
   * 而本模块要的是<b>主体键精确匹配</b>。用模糊过滤再在本地精确匹配，会漏掉
   * 「姓名拼写与画像不同、但邮箱与主体键都对得上」的那一类人（V149 自己也为邮箱建了反查索引，
   * 正是因为收货地址快照里姓名拼写常常不一致）。
   */
  public SourceProfiles profiles(String bearer) {
    List<SourceBuyerProfile> all = new ArrayList<>();
    long total = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      BuyerProfilePage result;
      try {
        result =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(BUYER_PROFILES_PATH)
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(BuyerProfilePage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          return new SourceProfiles(
              false, List.of(), 0, "调用方无权读取 9.2 审单的买家画像（trade 拒绝）");
        }
        return new SourceProfiles(false, List.of(), 0, "trade 服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceProfiles(false, List.of(), 0, "trade 服务不可达");
      }
      if (result == null || result.items() == null) {
        return new SourceProfiles(false, List.of(), 0, "trade 服务没有返回买家画像列表");
      }
      for (BuyerProfileRow row : result.items()) {
        if (row == null || row.buyerKey() == null || row.buyerKey().isBlank()) continue;
        all.add(
            new SourceBuyerProfile(
                row.buyerKey().trim().toLowerCase(java.util.Locale.ROOT),
                nz(row.buyerName()),
                nz(row.buyerPhone()),
                nz(row.buyerEmail()),
                nz(row.buyerLevel()),
                row.blacklisted(),
                nz(row.blacklistReason())));
      }
      total = result.total();
      if (all.size() >= total || result.items().isEmpty()) {
        return new SourceProfiles(true, all, total, "");
      }
    }
    return new SourceProfiles(
        false,
        List.of(),
        total,
        "9.2 买家名册读到的页数达到上限 " + MAX_PAGES + "，结果不完整 —— 本次不据此判定黑名单");
  }

  // ================================================================ 上游响应形状

  /** 上游的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BuyerProfileRow(
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason) {}

  /** 上游的一页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BuyerProfilePage(List<BuyerProfileRow> items, long total, int page, int size) {}

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
