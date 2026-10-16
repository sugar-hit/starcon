package com.starcon.trade;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 下游 2.5.1.1 / 2.5.1.2 的**跨服务端口** —— 「经 2 队列」那句话在本模块的落点。
 *
 * <p>蓝图 9.4.2.1 的输出句写死「回传任务与状态（<b>经 2 队列</b>），平台侧确认状态」，
 * 而 2 队列就是 channel 服务的 {@code platform_operation}（UNIQUE(tenant_id,
 * idempotency_key)）。AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本模块<b>只经 channel 的授权 HTTP 端点</b>投递与查证，不直连对方库。
 *
 * <p>用法与 {@link ShipmentHandoverQueryPort}（9.4.1.2 读 8.3.4.1）逐字同构：
 * <b>取不到就显式失败</b>，绝不把「平台没回执」记成「平台已受理」。
 *
 * <p>用 2.5.1.1 / 2.5.1.2 已交付的形状（channel V2 + {@code SyncController}）：
 *
 * <ul>
 *   <li>{@code POST /platform-operations} —— 入队（幂等：键命中复用既有任务），
 *       返回既有/新建的 {@code platform_operation.id}；</li>
 *   <li>{@code POST /platform-operations/{id}/start} —— 标记开始发送；</li>
 *   <li>{@code POST /platform-operations/{id}/succeed} —— 平台已受理（成功）；</li>
 *   <li>{@code POST /platform-operations/{id}/fail} —— 平台明确拒绝（失败）；</li>
 *   <li>{@code POST /platform-operations/{id}/unknown} —— <b>结果未知</b>；</li>
 *   <li>{@code POST /platform-operations/{id}/verify} —— 2.5.1.2 查证结论三选一。</li>
 * </ul>
 *
 * <p><b>三态为什么必须走三个不同的端点</b>：channel 侧把「超时 / 连接中断」与
 * 「平台拒绝」分成 {@code UNKNOWN} 与 {@code FAILED} 两个状态，正是因为
 * UNKNOWN 表示<b>平台可能已经执行</b>。本模块的职责句原样承接：
 * 未知既不能当失败重发（平台侧重复发货），也不能当成功（迟发判定成立而无人知晓）。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，channel 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（trade 侧 RLS + channel 侧 RLS）。
 */
@Service
public class ShipmentWritebackPort {

  private final RestClient client;

  public ShipmentWritebackPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：投递要等平台侧应答，比本地写库慢一截，
    // 但仍然必须有上限 —— 没有上限时平台一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 入队一次平台发货回写（2.5.1.1）。
   *
   * <p>返回的是 channel 侧 {@code platform_operation.id}：<b>幂等键命中时 channel 会
   * 返回既有任务的 id 而不是新建</b>，所以同一版重复投递在本端口这层就已经是幂等的，
   * 不依赖调用方「记得别点两次」。
   *
   * <p>刻意返回裸 {@code UUID}：它就是「这一次动作在平台侧的定位符」，
   * 后续 start / succeed / fail / unknown / verify 全部按它走。
   */
  public UUID enqueue(
      UUID shopId,
      UUID marketplaceId,
      String operationKind,
      String idempotencyKey,
      String payloadJson,
      String bearer) {
    Map<String, Object> body =
        Map.of(
            "shopId", shopId.toString(),
            "marketplaceId", marketplaceId == null ? "" : marketplaceId.toString(),
            "operation", operationKind,
            "idempotencyKey", idempotencyKey,
            "payload", payloadJson == null ? "{}" : payloadJson);
    try {
      UUID id =
          client
              .post()
              .uri("/platform-operations")
              .header("Authorization", bearer)
              .body(body)
              .retrieve()
              .body(UUID.class);
      if (id == null) {
        throw unavailable("平台对接服务没有返回队列任务号，无法确认这次回传落在哪一条动作上");
      }
      return id;
    } catch (RestClientResponseException e) {
      throw translate(e, "入队失败");
    } catch (RestClientException e) {
      throw unavailable("平台对接服务暂时不可达");
    }
  }

  /** 标记开始发送（2.5.1.1）。 */
  public void start(UUID operationId, String bearer) {
    post(operationId, "/start", null, bearer, "标记开始发送失败");
  }

  /** 平台已受理 —— 成功态（2.5.1.1）。 */
  public void succeed(UUID operationId, String platformRequestId, String bearer) {
    post(
        operationId,
        "/succeed",
        Map.of("platformRequestId", platformRequestId == null ? "" : platformRequestId),
        bearer,
        "登记平台已受理失败");
  }

  /** 平台明确拒绝 —— 失败态（2.5.1.1）。可补偿重试。 */
  public void fail(UUID operationId, String reason, String bearer) {
    post(operationId, "/fail", Map.of("reason", reason), bearer, "登记平台拒绝失败");
  }

  /**
   * 标记<b>结果未知</b>（2.5.1.2）。
   *
   * <p>超时或连接中断时必须走这里而不是 {@link #fail}：UNKNOWN 表示平台可能已经执行，
   * 直接当作失败重试会导致重复发货。
   */
  public void markUnknown(UUID operationId, String reason, String bearer) {
    post(operationId, "/unknown", Map.of("reason", reason), bearer, "登记结果未知失败");
  }

  /**
   * 2.5.1.2 查证结论三选一：{@code CONFIRMED_APPLIED} /
   * {@code CONFIRMED_NOT_APPLIED} / {@code STILL_UNKNOWN}。
   *
   * <p>取值域与 channel {@code Verification} 枚举逐字一致；{@code STILL_UNKNOWN}
   * 会保持 UNKNOWN 且<b>不允许</b>直接重试 —— 与蓝图「结果未知要先查证再重发」同义。
   */
  public void verify(UUID operationId, String conclusion, String bearer) {
    post(
        operationId,
        "/verify",
        Map.of("verification", conclusion),
        bearer,
        "登记查证结论失败");
  }

  /**
   * 投递一个「按队列动作 id 的后续动作」。
   *
   * <p>{@code body} 为 null 时发空体（{@code /start} 就是这种），
   * 否则原样序列化 —— channel 侧 {@code /fail}、{@code /unknown} 的
   * {@code reason} 与 {@code /verify} 的 {@code verification} 都从体里读，
   * 漏掉体的话请求会 400 而不是被静默忽略。
   */
  private void post(
      UUID operationId, String suffix, Object body, String bearer, String whatFailed) {
    try {
      var request = client.post().uri("/platform-operations/" + operationId + suffix);
      if (bearer != null) {
        request.header("Authorization", bearer);
      }
      if (body != null) {
        request.body(body);
      }
      request.retrieve().toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw translate(e, whatFailed);
    } catch (RestClientException e) {
      throw unavailable("平台对接服务暂时不可达");
    }
  }

  /**
   * 401/403 如实透传（登录失效 / 权限不足就是这两件事，不能说成「平台挂了」），
   * 其余一律 503 并说清是「队列侧不可达」。
   *
   * <p>刻意<b>不</b>返回「已受理」冒充成功：投递失败时最怕的就是把一次没发出去的
   * 回传记成成功 —— 平台会一直显示未发货，而账面上看不出任何异常。
   */
  private RuntimeException translate(RestClientResponseException e, String whatFailed) {
    HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    if (status == HttpStatus.UNAUTHORIZED) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    if (status == HttpStatus.FORBIDDEN) {
      return new ResponseStatusException(HttpStatus.FORBIDDEN, "当前身份无权向平台回写发货信息");
    }
    return unavailable(
        whatFailed + "：平台对接服务返回 " + e.getStatusCode().value()
            + "（" + e.getResponseBodyAsString() + "）");
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：平台回传队列的唯一归属是 2.5.1.1 的 platform_operation"
            + "（channel 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空记一次「平台已受理」");
  }
}
