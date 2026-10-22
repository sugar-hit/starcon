package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.5.2.1 登录与访问安全的<b>跨服务只读端口</b>：17 identity。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 identity 的
 * 授权 HTTP API 读</b>，不直连对方的库。与 {@link LegalEntitySourcePort}（16.3.1.1 读法人档案）、
 * {@link BuyerRiskScoreSourcePort}（16.5.1.2 读订单）同一手法。
 *
 * <p>🔴🔴 <b>登录事件的真源头是 identity 的 {@code operation_log}，不是 {@code identity_audit}</b>
 * （本仓实查结论，V49 与 IdentityController.login 为证）：
 * <ul>
 *   <li>{@code identity_audit} 记的是**业务变更**（成员、角色、字典…），由 {@code AuditLog.record} 写；</li>
 *   <li><b>登录 / 登出事件由 {@code OperationLog.login} / {@code OperationLog.logout} 写进
 *       {@code operation_log}（event_type = LOGIN / LOGOUT）</b>，读面是
 *       {@code GET /identity/operation-log}（要 {@code identity.operation_log.read}）。</li>
 * </ul>
 * 本端口因此读 {@code operation_log}。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 但<b>调用方不许把它
 * 折成空集合</b>：空集合会被界面读成「没有异常登录」。
 * 正确处理是：<b>上游不可达 ⇒ 503；上游正常但查无记录 ⇒ {@code known=false} + 空集合。</b>
 * 所以本端口额外区分了三类失败：
 * <ul>
 *   <li>{@link Failure#UNAUTHORIZED} —— 调用方的令牌在 identity 侧失效 ⇒ 401；</li>
 *   <li>{@link Failure#FORBIDDEN} —— 调用方在 identity 侧缺少 {@code identity.operation_log.read}
 *       ⇒ 403（**这是权限问题，不是「没有数据」**，不能折成空集合）；</li>
 *   <li>{@link Failure#UNAVAILABLE} —— 网络不通 / 超时 / 5xx ⇒ 503。</li>
 * </ul>
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，identity 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + identity 侧 RLS）。
 */
@Service
public class AccessSecuritySourcePort {

  /** 17 的操作日志读面（登录 / 登出事件在这里）。 */
  static final String OPERATION_LOG_PATH = "/identity/operation-log";

  /** 17 的当前会话读面。 */
  static final String SESSION_PATH = "/identity/auth/session";

  /** 单页条数上限（与 identity 侧 OperationLogController.MAX_PAGE_SIZE 同一档）。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多读多少页。
   *
   * <p>🔴 <b>达到上限时结果标成「不完整」而不是悄悄截断</b>：截断出来的失败次数会是一个
   * 看起来有数、实则缺了尾巴的统计 —— 那比「读不到」更危险。
   */
  static final int MAX_PAGES = 20;

  /** 失败类别。 */
  public enum Failure {
    /** 上游正常。 */
    NONE,
    /** 调用方令牌在 identity 侧失效。 */
    UNAUTHORIZED,
    /** 调用方在 identity 侧缺少读权限。 */
    FORBIDDEN,
    /** 上游不可达 / 5xx。 */
    UNAVAILABLE
  }

  /**
   * 一条上游登录 / 登出事件。
   *
   * @param id identity {@code operation_log.id}（本地登录审计用它去重）
   * @param sourceIp 来源 IP —— 🔴 <b>identity 的登录事件里恒为空</b>
   *     （{@code OperationLog.login} 的 remoteHint 参数在 IdentityController.login 里恒传 null）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceLoginEvent(
      String id,
      String eventType,
      String level,
      String requestId,
      String actorId,
      String actorName,
      String summary,
      Instant occurredAt,
      String sourceIp) {}

  /** 一次上游读取的结果。 */
  public record SourceLoginEvents(
      boolean available, Failure failure, List<SourceLoginEvent> events, long total, String note) {}

  /** 当前会话的上游事实。 */
  public record SourceSession(
      boolean available, Failure failure, boolean sessionActive, String membershipId, String note) {}

  /** identity 的操作日志列表项（只声明本模块要用的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OperationLogRow(
      String id,
      String eventType,
      String level,
      String requestId,
      String actorId,
      String actorName,
      String summary,
      Instant occurredAt,
      JsonNode detail) {}

  /** identity 的操作日志分页信封。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OperationLogPage(List<OperationLogRow> items, long total, int page, int size) {}

  /** identity 的会话视图（只需要成员关系那几项）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SessionRow(String userId, String tenantId, String membershipId) {}

  private final RestClient identity;

  public AccessSecuritySourcePort(@Value("${IDENTITY_URL}") String identityUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.identity = RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
  }

  // ================================================================ 登录 / 登出事件

  /**
   * 读一段时间内的登录 / 登出事件。
   *
   * @param bearer 调用方自己的 bearer（转发它，租户由 identity 按成员关系解析）
   * @param eventType {@code LOGIN} 或 {@code LOGOUT}
   */
  public SourceLoginEvents loginEvents(
      String bearer, String eventType, Instant from, Instant to, int maxPages) {
    List<SourceLoginEvent> all = new ArrayList<>();
    long total = 0;
    int pages = Math.max(1, maxPages);
    for (int page = 0; page < pages; page++) {
      final int pageIndex = page;
      OperationLogPage result;
      try {
        result =
            identity
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(OPERATION_LOG_PATH)
                            .queryParam("eventType", eventType)
                            .queryParam("from", iso(from))
                            .queryParam("to", iso(to))
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(OperationLogPage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401) {
          return new SourceLoginEvents(
              false, Failure.UNAUTHORIZED, List.of(), 0, "登录已失效或身份服务拒绝了本次请求");
        }
        if (code == 403) {
          return new SourceLoginEvents(
              false,
              Failure.FORBIDDEN,
              List.of(),
              0,
              "调用方在身份服务侧没有读操作日志的权限（需要 identity.operation_log.read）");
        }
        return new SourceLoginEvents(
            false, Failure.UNAVAILABLE, List.of(), 0, "身份服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceLoginEvents(false, Failure.UNAVAILABLE, List.of(), 0, "身份服务不可达");
      } catch (RuntimeException e) {
        return new SourceLoginEvents(
            false, Failure.UNAVAILABLE, List.of(), 0, "读取登录事件失败：" + e.getMessage());
      }
      if (result == null || result.items() == null) {
        return new SourceLoginEvents(
            false, Failure.UNAVAILABLE, List.of(), 0, "身份服务没有返回操作日志列表");
      }
      for (OperationLogRow row : result.items()) {
        if (row == null || row.id() == null || row.id().isBlank()) continue;
        all.add(
            new SourceLoginEvent(
                row.id(),
                nz(row.eventType()),
                nz(row.level()),
                nz(row.requestId()),
                nz(row.actorId()),
                nz(row.actorName()),
                nz(row.summary()),
                row.occurredAt(),
                // 🔴 identity 的登录事件**不带来源 IP**（remoteHint 恒为 null），
                //    这里仍然去 detail 里读一次：将来 identity 补上 remote 就能自动用上，
                //    现在读出来就是空 —— 如实表达，不编造。
                readRemote(row.detail())));
      }
      total = result.total();
      if (all.size() >= total || result.items().isEmpty()) {
        return new SourceLoginEvents(true, Failure.NONE, all, total, "");
      }
    }
    return new SourceLoginEvents(
        false,
        Failure.UNAVAILABLE,
        List.of(),
        total,
        "登录事件读到的页数达到上限 " + pages + "，结果不完整 —— 本轮不据此判定异常");
  }

  /**
   * 从 identity 的 {@code detail} 里读来源 IP。
   *
   * <p>🔴 现在恒为 null（identity 的 {@code OperationLog.login} 在 {@code IdentityController.login}
   * 里传的是 {@code remoteHint = null}）。这里保留读取逻辑是为了 identity 将来补上 {@code remote}
   * 时不用改本模块 —— 那时它会自动生效。
   */
  private static String readRemote(JsonNode detail) {
    if (detail == null || detail.isNull()) return "";
    var node = detail.get("remote");
    if (node == null || node.isNull()) return "";
    var value = node.asText("").trim();
    return value.isEmpty() ? "" : value;
  }

  // ================================================================ 当前会话

  /**
   * 读调用方当前的会话。
   *
   * <p>🔴 <b>identity 的 {@code GET /identity/auth/session} 只返回成员关系解析结果</b>
   * （user_id / tenant_id / membership_id / 权限），<b>不返回会话到期时刻、空闲时长、并发数</b>
   * —— {@code auth_session} 表只有 token_hash / user_id / membership_id / expires_at，
   * 而 expires_at **没有任何读端点**。
   * ⇒ 本端口只如实报告「会话是否有效」，到期 / 空闲 / 并发三个维度由能力声明标
   * {@code available=false}（归属 17 identity）。
   */
  public SourceSession currentSession(String bearer) {
    try {
      SessionRow row =
          identity
              .get()
              .uri(SESSION_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(SessionRow.class);
      if (row == null) {
        return new SourceSession(
            true, Failure.NONE, false, "", "身份服务没有返回会话信息（当前请求没有可用会话）");
      }
      return new SourceSession(true, Failure.NONE, true, nz(row.membershipId()), "");
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401) {
        return new SourceSession(false, Failure.UNAUTHORIZED, false, "", "登录已失效或身份服务拒绝了本次请求");
      }
      if (code == 403) {
        return new SourceSession(false, Failure.FORBIDDEN, false, "", "身份服务拒绝了会话查询");
      }
      return new SourceSession(false, Failure.UNAVAILABLE, false, "", "身份服务返回 " + code);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SourceSession(false, Failure.UNAVAILABLE, false, "", "身份服务不可达");
    } catch (RuntimeException e) {
      return new SourceSession(
          false, Failure.UNAVAILABLE, false, "", "读取会话失败：" + e.getMessage());
    }
  }

  // ================================================================ 工具

  /** ISO-8601 时刻（UTC，带 Z）—— identity 的入参解析用 {@code Instant.parse}。 */
  private static String iso(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC).toString();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 把上游 id 字符串转 UUID；非法就返回 null（不抛异常打断整页）。 */
  static UUID uuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
