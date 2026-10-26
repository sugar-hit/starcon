package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 读取 **finance 服务 12.6.1.2 申报底稿**的<b>跨服务只读端口</b>。
 *
 * <p>蓝图 16.3.1.2 的输入之一是「12.6.1.2 数据就绪状态」，
 * 而 12.6.1.2 的申报底稿住在 **finance 服务**（另一个库、另一个 schema）。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 finance 的授权 HTTP API</b>读：{@code GET /finance/filing-draft/drafts}。
 * 本服务<b>不建</b>任何指向 finance 的外键 / 视图 / 影子表。
 *
 * <p>🔴 <b>读不到就是读不到</b>：本端口<b>不抛异常</b>，而是返回
 * {@code available=false} + 原因。上层据此把 {@code dataReadinessAvailable=false}
 * 并显示「读不到」—— <b>绝不把「读不到」显示成「数据没就绪」</b>：
 * 前者是要去查上游，后者是「已经查过，确实没有」，两者的处置动作完全相反。
 *
 * <p>🔴 <b>本端口不取金额</b>：底稿的金额留在 finance。日历只存**状态**（有没有一份
 * 生效中的底稿），抄金额进来就是两个真值源。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * finance 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>。
 */
@Service
public class FilingDraftReadinessPort {

  /** 上游只读形状：{@code GET /finance/filing-draft/drafts}（12.6.1.2 申报底稿）。 */
  static final String DRAFTS_PATH = "/finance/filing-draft/drafts";

  /** 一次读取最多取多少份底稿（12.6.1.2 单租户同期只留一份生效底稿，取 200 足够覆盖一个日历页）。 */
  private static final int MAX_DRAFTS = 200;

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * 一份底稿的<b>最小引用</b>（只读、只用于判断「有没有一份生效中的底稿」）。
   *
   * @param id 底稿 ID
   * @param jurisdictionCode 辖区编码
   * @param taxType 申报线（税种）
   * @param periodStart 期间起
   * @param periodEnd 期间止
   * @param filingCycle 申报周期
   * @param status 底稿状态（GENERATED 生效 / SUPERSEDED 已被取代）
   */
  public record DraftRef(
      UUID id,
      String jurisdictionCode,
      String taxType,
      String periodStart,
      String periodEnd,
      String filingCycle,
      String status) {}

  /**
   * 一次读取的结果。
   *
   * @param available 上游读到了吗（false ⇒ 界面显示「读不到」，**不是**「没有底稿」）
   * @param drafts 读到的底稿
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record DraftSet(boolean available, List<DraftRef> drafts, String note) {}

  /** 上游返回体（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DraftRow(
      UUID id,
      String jurisdictionCode,
      String taxType,
      String periodStart,
      String periodEnd,
      String filingCycle,
      String status) {}

  /** 上游分页体。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DraftPage(List<DraftRow> items, Integer page, Integer size, Integer total) {}

  private final RestClient client;

  public FilingDraftReadinessPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 探一下上游通不通（能力声明用；**只取一条**，不搬整页数据）。
   *
   * @param bearer 调用方自己的会话
   * @return 上游可达 = available
   */
  public DraftSet probe(String bearer) {
    try {
      DraftPage body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder.path(DRAFTS_PATH).queryParam("page", 0).queryParam("size", 1).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(DraftPage.class);
      if (body == null) {
        return new DraftSet(
            false, List.of(), "finance 服务没有返回申报底稿 —— **读不到**，不是「没有底稿」。");
      }
      return new DraftSet(true, List.of(), "已连通 finance 的申报底稿只读接口。");
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 403) {
        return new DraftSet(
            false,
            List.of(),
            "调用方无权读取 finance 的申报底稿（" + status + "）—— **读不到**，不是「没就绪」。");
      }
      return new DraftSet(
          false, List.of(), "finance 服务返回 " + status + " —— 申报底稿**读不到**，不是「没就绪」。");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new DraftSet(false, List.of(), "finance 服务不可达 —— 申报底稿**读不到**，不是「没就绪」。");
    } catch (RuntimeException e) {
      return new DraftSet(
          false, List.of(), "读取申报底稿失败：" + e.getMessage() + " —— **读不到**，不是「没就绪」。");
    }
  }

  /**
   * 读某个（辖区 × 税种）下**生效中**的申报底稿。
   *
   * @param jurisdictionCode 辖区编码
   * @param taxType 申报线（税种）
   * @param bearer 调用方自己的会话（租户由 finance 侧按已认证成员关系解析）
   * @return 底稿集合（读不到时 available=false + 原因）
   */
  public DraftSet liveDrafts(String jurisdictionCode, String taxType, String bearer) {
    try {
      DraftPage body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(DRAFTS_PATH)
                          .queryParam("jurisdictionCode", jurisdictionCode)
                          .queryParam("taxType", taxType)
                          .queryParam("status", "GENERATED")
                          .queryParam("page", 0)
                          .queryParam("size", MAX_DRAFTS)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(DraftPage.class);
      if (body == null || body.items() == null) {
        return new DraftSet(
            false, List.of(), "finance 服务没有返回申报底稿 —— **读不到**，不是「没有底稿」。");
      }
      List<DraftRef> drafts = new ArrayList<>();
      for (DraftRow row : body.items()) {
        if (row == null || row.id() == null) {
          continue;
        }
        drafts.add(
            new DraftRef(
                row.id(),
                row.jurisdictionCode() == null ? "" : row.jurisdictionCode(),
                row.taxType() == null ? "" : row.taxType(),
                row.periodStart() == null ? "" : row.periodStart(),
                row.periodEnd() == null ? "" : row.periodEnd(),
                row.filingCycle() == null ? "" : row.filingCycle(),
                row.status() == null ? "" : row.status()));
      }
      return new DraftSet(true, drafts, "已从 finance 读到该辖区该申报线的生效底稿。");
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 403) {
        return new DraftSet(
            false,
            List.of(),
            "调用方无权读取 finance 的申报底稿（" + status + "）—— **读不到**，不是「没就绪」。");
      }
      return new DraftSet(
          false, List.of(), "finance 服务返回 " + status + " —— 申报底稿**读不到**，不是「没就绪」。");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new DraftSet(false, List.of(), "finance 服务不可达 —— 申报底稿**读不到**，不是「没就绪」。");
    } catch (RuntimeException e) {
      return new DraftSet(
          false, List.of(), "读取申报底稿失败：" + e.getMessage() + " —— **读不到**，不是「没就绪」。");
    }
  }
}
