package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.4.2.2 的<b>库存 / 在途只读 Port</b> 语义测试。
 *
 * <p>🔴 这两个 Port 身上只有一条纪律值得钉死：**「查不到」与「确实是 0」必须分得开**。
 *
 * <ul>
 *   <li>上游<b>不可达 / 报错</b> → 抛 503 且理由写明「读不到」。<b>绝不</b>返回 0 ——
 *       0 会被读成「没有库存需求」，那正是会真的少备一批货的路径。</li>
 *   <li>上游<b>正常返回、但没有这个 SKU 的行</b> → {@code known=false} + 值是 {@code null}。
 *       「无记录」≠「可售量为 0」。</li>
 *   <li>上游<b>模糊命中</b>（warehouse 的 keyword 是模糊匹配）→ 必须<b>逐字相等</b>才算命中，
 *       否则「SKU-1」会吃进「SKU-10」的可售量。</li>
 * </ul>
 *
 * <p>本类**不需要数据库、不需要 Spring**：直接对着一个本地 stub HTTP server 测端口语义。
 */
class StockForecastAvailabilityPortTest {
  private HttpServer upstream;
  private String base;

  /** stub 按 keyword 回应的内容；由每个用例设置。 */
  private final AtomicReference<String> body = new AtomicReference<>("{\"rows\":[]}");

  /** stub 回应的 HTTP 状态码；默认 200。 */
  private final AtomicReference<Integer> status = new AtomicReference<>(200);

  /** stub 实际收到的 Authorization 头（用来证明端口转发的是调用方自己的 bearer）。 */
  private final AtomicReference<String> seenAuth = new AtomicReference<>("");

  /** stub 实际收到的请求路径 + query。 */
  private final AtomicReference<String> seenUri = new AtomicReference<>("");

  @BeforeEach
  void startUpstream() throws Exception {
    // 🔴 **必须显式绑 127.0.0.1，不能用通配地址 new InetSocketAddress(0)。**
    //
    //    macOS 的 JDK 默认开 SO_REUSEADDR：通配地址（0.0.0.0）的 bind 在
    //    同一端口已被**更具体的** 127.0.0.1 绑定占用时**照样成功**，
    //    而客户端连上的是那台更具体的（= 别的测试的 stub）——
    //    于是本测试收到的是**别的 stub 的响应**（404 No context found），
    //    端口把它翻成 503「读不到」，表现为一条**顺序/时序型的假红**。
    //
    //    显式绑回环后，同端口冲突会**响亮地抛 BindException**，
    //    把「静默给出错误答案」变成「明确报错」。
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        e -> {
          seenAuth.set(String.valueOf(e.getRequestHeaders().getFirst("Authorization")));
          seenUri.set(String.valueOf(e.getRequestURI()));
          byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(status.get(), payload.length);
          try (OutputStream out = e.getResponseBody()) {
            out.write(payload);
          }
        });
    upstream.start();
    // base 与绑定地址保持一致（127.0.0.1）：localhost 在本机可能解析到 ::1，
    // 而桩只绑了 IPv4 回环 ⇒ 会变成「连接被拒」，而不是连上桩。
    base = "http://127.0.0.1:" + upstream.getAddress().getPort();
  }

  @AfterEach
  void stopUpstream() {
    // 桩起不来时（端口冲突 ⇒ BindException）upstream 为 null：
    // 这里必须容错，否则会把真正的原因（端口冲突）盖成一个 NPE。
    if (upstream != null) upstream.stop(0);
  }

  // ==================================================================
  // 库存端口
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 可售量命中时原样取回（逐字相等才算命中，模糊命中不算）")
  void sellableMatchesExactSkuCodeOnly() {
    // warehouse 的 keyword 是模糊匹配：这里刻意回一个含 SKU-1 与 SKU-10 的列表
    body.set(
        "{\"rows\":[{\"skuCode\":\"SKU-10\",\"sellable\":999},"
            + "{\"skuCode\":\"SKU-1\",\"sellable\":42.500000}],\"total\":2,\"page\":0,\"size\":20}");
    var port = new ForecastInventoryQueryPort(base);

    var hit = port.lookup("SKU-1", "Bearer t");
    assertThat(hit.sellableKnown()).isTrue();
    assertThat(hit.sellable()).isEqualByComparingTo("42.500000");

    // SKU-10 不是 SKU-1：模糊命中必须被挡掉，否则会把别人的可售量算进这张预报单
    var miss = port.lookup("SKU-11", "Bearer t");
    assertThat(miss.sellableKnown()).isFalse();
    assertThat(miss.sellable()).isNull();
    assertThat(miss.note()).contains("≠ 可售量为 0");
  }

  @Test
  @DisplayName("10.4.2.2 上游报错 ⇒ 503 且理由写明「读不到」（不用 0 冒充）")
  void sellableReportsUnavailableInsteadOfZero() {
    status.set(500);
    body.set("{\"rows\":[]}");
    var port = new ForecastInventoryQueryPort(base);

    var thrown = catchThrowable(() -> port.lookup("SKU-1", "Bearer t"));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    var e = (ResponseStatusException) thrown;
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(e.getReason()).contains("读不到");
    assertThat(e.getReason()).doesNotContain("可售量为 0");
  }

  @Test
  @DisplayName("10.4.2.2 上游不可达 ⇒ 503（绝不降级成可售量 0）")
  void sellableHandlesUnreachableUpstream() {
    upstream.stop(0); // 端口关掉 = 不可达
    var port = new ForecastInventoryQueryPort(base);

    var thrown = catchThrowable(() -> port.lookup("SKU-1", "Bearer t"));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // ==================================================================
  // 在途端口
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 在途按 SKU 汇总未结腿；没有腿时是 null 而不是 0")
  void inTransitSumsOutstandingLegsAndKeepsMissingAsNull() {
    body.set(
        "{\"rows\":["
            + "{\"skuCode\":\"SKU-2\",\"outstandingQuantity\":10.000000,\"transitKind\":\"FIRST_MILE\"},"
            + "{\"skuCode\":\"SKU-2\",\"outstandingQuantity\":5.500000,\"transitKind\":\"FIRST_MILE\"},"
            + "{\"skuCode\":\"SKU-20\",\"outstandingQuantity\":88.000000,\"transitKind\":\"FIRST_MILE\"}"
            + "],\"total\":3,\"page\":0,\"size\":20}");
    var port = new ForecastInTransitQueryPort(base);

    var hit = port.lookup("SKU-2", "Bearer t");
    assertThat(hit.inTransitKnown()).isTrue();
    assertThat(hit.inTransitQuantity()).isEqualByComparingTo("15.500000");

    var miss = port.lookup("SKU-3", "Bearer t");
    assertThat(miss.inTransitKnown()).isFalse();
    assertThat(miss.inTransitQuantity()).isNull();
    assertThat(miss.note()).contains("≠ 在途为 0");
  }

  @Test
  @DisplayName("10.4.2.2 在途上游不可达 ⇒ 503（绝不降级成在途 0）")
  void inTransitReportsUnavailableInsteadOfZero() {
    upstream.stop(0);
    var port = new ForecastInTransitQueryPort(base);

    var thrown = catchThrowable(() -> port.lookup("SKU-1", "Bearer t"));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("10.4.2.2 端口转发调用方自己的 bearer，读的是 warehouse 的授权只读形状")
  void portsForwardCallerBearerAndUseAuthorisedReadPaths() {
    var port = new ForecastInventoryQueryPort(base);
    port.lookup("SKU-1", "Bearer caller-token");

    // 🔴 端口**不**传租户号：转发的是调用方自己的 bearer，
    //    warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
    assertThat(seenAuth.get()).isEqualTo("Bearer caller-token");
    assertThat(seenUri.get()).startsWith("/warehouse/inventory/sellable/skus");
    assertThat(seenUri.get()).contains("keyword=SKU-1");

    var transit = new ForecastInTransitQueryPort(base);
    transit.lookup("SKU-1", "Bearer caller-token");
    assertThat(seenAuth.get()).isEqualTo("Bearer caller-token");
    assertThat(seenUri.get()).startsWith("/warehouse/inventory/in-transit");
    // 只取头程在途：调拨腿 / 采购腿 / FBA 出库腿不是同一个口径
    assertThat(seenUri.get()).contains("transitKind=FIRST_MILE");

    assertThat(ForecastInventoryQueryPort.SELLABLE_SKUS_PATH)
        .isEqualTo("/warehouse/inventory/sellable/skus");
    assertThat(ForecastInTransitQueryPort.IN_TRANSIT_PATH)
        .isEqualTo("/warehouse/inventory/in-transit");
    assertThat(ForecastInTransitQueryPort.TRANSIT_KIND).isEqualTo("FIRST_MILE");
    // 单次查询的 SKU 数上限：不给上限时一张预报单能把上游线程占住
    assertThat(ForecastInventoryQueryPort.MAX_SKUS_PER_CALL).isPositive();
    assertThat(List.of(ForecastInTransitQueryPort.TRANSIT_KIND)).contains("FIRST_MILE");
  }
}
