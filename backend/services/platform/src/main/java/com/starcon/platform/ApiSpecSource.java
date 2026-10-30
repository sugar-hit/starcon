package com.starcon.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 18.1.1.2「接口目录」的<b>上游 spec 端口</b>：真去各服务的 {@code /v3/api-docs} 把契约取回来。
 *
 * <p>蓝图的关系栏写死：「<b>上游 = 后端 OpenAPI spec（单一真相源）</b>；
 * <b>接口契约由后端 spec 生成，文档不手写</b>——手写文档必然与实现漂移。」
 * 所以本类**只做一件事**：把各服务**活着的** spec 拉回来。它不解释、不改写、不补全 ——
 * 任何一行文档内容都必须能在 spec 里找到出处。
 *
 * <p>🔴 <b>为什么走 HTTP 而不是读 {@code backend/target/openapi/*.openapi.json} 那份快照</b>：
 * 那份快照是<b>各服务自己的契约测试</b>在构建阶段导出的产物（{@code OpenApiContractTest}），
 * 运行时**不一定存在**，而且它比活着的 spec 旧。活着的 {@code /v3/api-docs} 是同一份契约的
 * <b>运行时等价物</b>，而且永远是当前代码的那一份 —— 「文档与实现漂移」正是要防这件事。
 *
 * <p>🔴 <b>取不到就显式失败，绝不返回空目录冒充「这个服务没有接口」</b>：
 * 一个服务读不出来时，它在目录里那一格标成 {@code UNAVAILABLE} 并写明原因，
 * 而不是<b>悄悄消失</b>（消失 = 「它没对外接口」，那是假的）。
 *
 * <p>⚠️ <b>显式超时</b>：连接 3 秒 / 读 10 秒。没有上限时一个挂掉的服务会把本服务的请求线程全占住。
 *
 * <p>⚠️ <b>TTL 缓存</b>：目录页面一次要读全部服务的 spec。九个服务 × 每个页面一次 = 把上游打满。
 * 缓存 TTL 默认 60 秒（{@code platform.api-catalog.spec-ttl-seconds} 可覆盖）。
 * 缓存的是**解析后的 spec 节点**，不是文件 —— 进程内，不落盘。
 */
@Service
public class ApiSpecSource {

  /** OpenAPI 里代表「一个操作」的 HTTP 方法。 */
  static final List<String> HTTP_METHODS =
      List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /**
   * 一个服务的 spec。
   *
   * @param available 这个服务读到了没有
   * @param unavailableReason 读不到时的原因（**写进目录里那一格**，页面要显示出来）
   */
  public record SpecEntry(
      String service,
      String baseUrl,
      String title,
      String version,
      JsonNode spec,
      boolean available,
      String unavailableReason,
      int interfaceCount) {

    /** 读到了才有 spec。 */
    public boolean hasSpec() {
      return available && spec != null;
    }
  }

  private final Map<String, String> registry;
  private final long ttlMillis;
  private final RestClient client;
  private final ObjectMapper json;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  /** 缓存里的一条（值 + 取到的时刻）。 */
  private record Cached(SpecEntry entry, long fetchedAtMillis) {}

  public ApiSpecSource(
      Environment environment,
      @Value("${platform.api-catalog.spec-ttl-seconds:60}") long ttlSeconds,
      ObjectMapper json) {
    // ⚠️ 用 Binder 而不是 @Value("${...}")：@Value 只能把一个属性绑成一个标量，
    //   绑不成「服务名 → 基地址」这张表。Binder 走的是 Spring Boot 的宽松绑定，
    //   `platform.api-catalog.services.identity=...` 这类逐条声明都能进表。
    this.registry =
        new LinkedHashMap<>(
            Binder.get(environment)
                .bind("platform.api-catalog.services", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of));
    this.ttlMillis = Math.max(ttlSeconds, 1) * 1000L;
    this.json = json;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  // ================================================================ 登记表

  /** 登记在册的服务（名字 → 基地址），按名字排序，保证输出顺序稳定。 */
  public List<String> registeredServices() {
    return registry.keySet().stream().sorted().toList();
  }

  /** 某个服务的基地址（不在册 = null）。 */
  public String baseUrlOf(String service) {
    return registry.get(service);
  }

  // ================================================================ 取 spec

  /** 全部服务的 spec（**读不到的也在列表里**，带 unavailableReason）。 */
  public List<SpecEntry> all() {
    var result = new ArrayList<SpecEntry>();
    for (var service : registeredServices()) {
      result.add(byService(service));
    }
    return result;
  }

  /**
   * 一个服务的 spec（带 TTL 缓存）。
   *
   * <p>不在册的服务返回 {@code null} —— 调用方要把它翻成 400（「这个服务不在册」），
   * 而不是「读不到」（那是两回事：不在册是**配置问题**，读不到是**上游问题**）。
   */
  public SpecEntry byService(String service) {
    if (service == null || !registry.containsKey(service)) return null;
    var now = System.currentTimeMillis();
    var hit = cache.get(service);
    if (hit != null && now - hit.fetchedAtMillis() < ttlMillis) return hit.entry();
    var entry = fetch(service);
    cache.put(service, new Cached(entry, now));
    return entry;
  }

  /** 丢掉缓存（下次调用重新去上游取）。 */
  public void invalidate() {
    cache.clear();
  }

  /** 真去上游取一次。 */
  private SpecEntry fetch(String service) {
    var baseUrl = registry.get(service);
    try {
      var body =
          client
              .get()
              .uri(baseUrl + "/v3/api-docs")
              .retrieve()
              .body(String.class);
      if (body == null || body.isBlank()) {
        return unavailable(service, baseUrl, "上游返回了空的 spec");
      }
      var spec = json.readTree(body);
      if (spec == null || !spec.isObject()) {
        return unavailable(service, baseUrl, "上游返回的不是 JSON 对象");
      }
      var info = spec.path("info");
      var title = info.path("title").asText("");
      var version = info.path("version").asText("");
      return new SpecEntry(
          service, baseUrl, title, version, spec, true, "", countOperations(spec));
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return unavailable(service, baseUrl, "上游不可达（连接失败或超时）");
    } catch (org.springframework.web.client.RestClientResponseException e) {
      return unavailable(service, baseUrl, "上游返回 " + e.getStatusCode().value());
    } catch (Exception e) {
      return unavailable(service, baseUrl, "上游 spec 读不到：" + e.getClass().getSimpleName());
    }
  }

  private static SpecEntry unavailable(String service, String baseUrl, String reason) {
    return new SpecEntry(service, baseUrl, "", "", null, false, reason, 0);
  }

  /** 数一数这份 spec 里有多少个操作（= 接口数）。 */
  static int countOperations(JsonNode spec) {
    var paths = spec.path("paths");
    if (!paths.isObject()) return 0;
    var count = 0;
    var iterator = paths.fields();
    while (iterator.hasNext()) {
      var item = iterator.next().getValue();
      if (!item.isObject()) continue;
      for (var method : HTTP_METHODS) {
        if (item.has(method)) count++;
      }
    }
    return count;
  }
}
