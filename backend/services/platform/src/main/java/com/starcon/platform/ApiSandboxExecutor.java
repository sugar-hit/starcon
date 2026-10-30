package com.starcon.platform;

import static com.starcon.platform.ApiCatalogModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 18.1.1.2 的<b>沙箱执行器</b>：真去上游发一次请求，把**真响应**带回来。
 *
 * <p>蓝图：「支持在线测试（沙箱环境）……<b>输出：接口文档 + 测试结果</b>。」
 * 所以这里**必须**是一次真实的 HTTP 调用 —— 写死的成功样例会让接入方
 * 「以为接口通了」，而现场那个接口根本没被调过。
 *
 * <p><b>四条纪律：</b>
 *
 * <ol>
 *   <li>🔴 <b>只发读请求</b>：GET / HEAD。写方法一律 409 拒发。
 *       <br>理由：<b>沙箱必须与生产数据隔离</b>（本任务书明写「不得写真实业务表」）。
 *       本仓没有独立的沙箱部署，写请求打出去就是打在**生产业务表**上 ——
 *       一个「测试」按钮改了真单据，是比不提供测试更糟的结果。
 *       <br>所以这条不是「半成品」，是<b>隔离要求</b>；写操作的正确用法是
 *       在 18.1.1.1 里用真实凭证对真实环境调用（那里有配额、IP 白名单与审计兜着）。
 *   <li>🔴 <b>路径与查询参数必须来自 spec</b>：目标路径必须能匹配上 spec 里声明的路径形状，
 *       查询参数的键必须是该操作声明过的 query 参数。
 *       <br>理由：这既是「接口目录」的意义（目录说的就是这些接口），
 *       也挡住了「拿测试接口当任意代理」这件事（否则它能探测内网任意地址）。
 *   <li>🔴 <b>转发调用方自己的 bearer</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *       沙箱因此**不会**看到调用方看不到的数据 —— 它不是后门。
 *   <li>⚠️ <b>取不到就显式失败</b>：上游不可达 = 503（是「读不到」，不是「没有数据」）。
 *       上游回了 404 / 500 时，沙箱**自己**返回 200 并把上游的真实状态码放在结果里 ——
 *       那次测试**成功执行了**，上游的状态码正是要看的结论。
 * </ol>
 */
@Service
public class ApiSandboxExecutor {

  /** 响应摘要保留的最大字符数（迁移里 response_summary 的上限是 8000，这里留一半余量）。 */
  static final int MAX_SUMMARY_CHARS = 4000;

  /** 读取响应体的最大字节数（超出部分丢弃并标 truncated）。 */
  static final int MAX_READ_BYTES = 256 * 1024;

  /** 允许在沙箱里执行的方法（**只读**）。 */
  static final List<String> READ_METHODS = List.of("GET", "HEAD");

  /** 一次沙箱尝试的结论。 */
  public record Outcome(
      String outcome,
      Integer statusCode,
      String contentType,
      String bodySummary,
      boolean truncated,
      long responseBytes,
      long durationMs,
      String message) {}

  private final ApiSpecSource specs;
  private final RestClient client;

  public ApiSandboxExecutor(ApiSpecSource specs) {
    this.specs = specs;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  // ================================================================ 执行

  /**
   * 执行一次沙箱测试。
   *
   * @param service 目标服务名（在册且读到了 spec）
   * @param baseUrl 目标服务的基地址
   * @param spec 目标服务的 spec
   * @param input 测试请求
   * @param bearer 调用方自己的会话（**只转发它，不传租户号**）
   */
  public Outcome execute(
      String service, String baseUrl, JsonNode spec, ApiCatalogSandboxInput input, String bearer) {
    var method = input.httpMethod() == null ? "" : input.httpMethod().trim().toUpperCase(Locale.ROOT);
    if (method.isEmpty()) {
      return invalid("必须指定 HTTP 方法", 0);
    }
    var path = input.targetPath() == null ? "" : input.targetPath().trim();
    if (path.isEmpty()) {
      return invalid("必须指定目标路径", 0);
    }
    if (path.contains("?")) {
      return invalid("目标路径里不能带查询串（?…）—— 查询参数请放在 query 字段里", 0);
    }
    if (!path.startsWith("/")) {
      return invalid("目标路径必须以 / 开头", 0);
    }
    if (path.contains("..") || path.contains("//")) {
      return invalid("目标路径含有非法的路径片段", 0);
    }
    if (path.contains("://")) {
      return invalid("目标路径只能是服务内的相对路径", 0);
    }

    // 路径必须能匹配上 spec 里声明过的形状。
    var template = matchTemplate(spec, path);
    if (template == null) {
      return invalid("目标路径不在该服务的接口清单里（spec 中没有这个形状）", 0);
    }
    var item = spec.path("paths").path(template);
    var operation = item.path(method.toLowerCase(Locale.ROOT));
    if (operation.isMissingNode()) {
      return invalid("该接口没有 " + method + " 方法（spec 中只有 " + methodsOf(item) + "）", 0);
    }

    // 🔴 写方法拒发（沙箱不写真实业务表）。
    if (!READ_METHODS.contains(method)) {
      return new Outcome(
          "REFUSED_WRITE",
          null,
          null,
          "",
          false,
          0,
          0,
          "沙箱只放行读请求（GET / HEAD）："
              + method
              + " 会写到真实业务表，沙箱必须与生产数据隔离。"
              + "写操作请在「应用与凭证」里用真实凭证对真实环境调用。");
    }

    // 查询参数必须都是该操作声明过的。
    var declared = queryParameters(operation);
    var query = input.query() == null ? Map.<String, String>of() : input.query();
    var unknown = new ArrayList<String>();
    for (var key : query.keySet()) {
      if (!declared.contains(key)) unknown.add(key);
    }
    if (!unknown.isEmpty()) {
      return invalid(
          "查询参数 " + String.join("、", unknown) + " 不在该接口的 spec 里（可用：" + declared + "）", 0);
    }
    var missing = new ArrayList<String>();
    for (var parameter : operation.path("parameters")) {
      if (!"query".equals(parameter.path("in").asText(""))) continue;
      if (!parameter.path("required").asBoolean(false)) continue;
      var name = parameter.path("name").asText("");
      if (!name.isEmpty() && !query.containsKey(name)) missing.add(name);
    }
    if (!missing.isEmpty()) {
      return invalid("缺少必填查询参数：" + String.join("、", missing), 0);
    }

    // 真发一次。
    var url =
        UriComponentsBuilder.fromUriString(baseUrl)
            .path(path)
            .query(encodeQuery(query))
            .build()
            .toUriString();
    var started = System.nanoTime();
    try {
      var entity =
          client
              .method(HttpMethod.valueOf(method))
              .uri(url)
              .header("Authorization", bearer == null ? "" : bearer)
              // 状态码**就是**测试结果：不抛，让它原样回来。
              .retrieve()
              .onStatus(
                  status -> true,
                  (request, response) -> {
                    // no-op：4xx / 5xx 也要拿到真实状态码与响应体。
                  })
              .toEntity(byte[].class);
      var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
      var bytes = entity.getBody() == null ? new byte[0] : entity.getBody();
      var contentType =
          entity.getHeaders().getContentType() == null
              ? ""
              : entity.getHeaders().getContentType().toString();
      var readTruncated = bytes.length > MAX_READ_BYTES;
      var text =
          new String(bytes, 0, Math.min(bytes.length, MAX_READ_BYTES), StandardCharsets.UTF_8);
      var summaryTruncated = text.length() > MAX_SUMMARY_CHARS;
      var summary = truncate(text, MAX_SUMMARY_CHARS);
      return new Outcome(
          "COMPLETED",
          entity.getStatusCode().value(),
          contentType,
          summary,
          readTruncated || summaryTruncated,
          bytes.length,
          elapsedMs,
          "上游真的回了 " + entity.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
      return new Outcome(
          "UPSTREAM_UNREACHABLE",
          null,
          null,
          "",
          false,
          0,
          elapsedMs,
          "上游不可达（连接失败或超时）—— 是「读不到」，不是「没有数据」");
    } catch (Exception e) {
      var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
      return new Outcome(
          "UPSTREAM_UNREACHABLE",
          null,
          null,
          "",
          false,
          0,
          elapsedMs,
          "上游调用失败：" + e.getClass().getSimpleName());
    }
  }

  // ================================================================ 工具

  private static Outcome invalid(String message, long elapsedMs) {
    return new Outcome("INVALID_TARGET", null, null, "", false, 0, elapsedMs, message);
  }

  /** 截断并标注。 */
  private static String truncate(String text, int max) {
    if (text.length() <= max) return text;
    return text.substring(0, max) + "\n…（已截断，共 " + text.length() + " 字符）";
  }

  /**
   * 把查询参数编成 {@code a=1&b=2}。
   *
   * <p>⚠️ 值里可能有空格、中文、{@code &}、{@code =} —— 不编码就会把查询串拆错，
   * 上游收到的是**另一个**参数（\"测试\" 会被截成 \"测\"）。
   */
  static String encodeQuery(Map<String, String> query) {
    if (query == null || query.isEmpty()) return "";
    var builder = new StringBuilder();
    for (var entry : query.entrySet()) {
      if (entry.getValue() == null) continue;
      if (!builder.isEmpty()) builder.append('&');
      builder
          .append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return builder.toString();
  }

  /** 该路径声明过的方法（给错误信息用）。 */
  static String methodsOf(JsonNode pathItem) {
    var methods = new ArrayList<String>();
    for (var method : ApiSpecSource.HTTP_METHODS) {
      if (pathItem.has(method)) methods.add(method.toUpperCase(Locale.ROOT));
    }
    return methods.isEmpty() ? "（无）" : String.join("、", methods);
  }

  /**
   * 把一个具体路径匹配到 spec 里的路径形状。
   *
   * <p>形状里的 <code>{xxx}</code> 段是通配：{@code /catalog/spus/abc} 匹配
   * {@code /catalog/spus/{spuId}}。段数必须一样 —— 多一层少一层都不是这个接口。
   */
  static String matchTemplate(JsonNode spec, String concretePath) {
    var concrete = segments(concretePath);
    var paths = spec.path("paths");
    if (!paths.isObject()) return null;
    var iterator = paths.fieldNames();
    while (iterator.hasNext()) {
      var template = iterator.next();
      var shape = segments(template);
      if (shape.size() != concrete.size()) continue;
      var matched = true;
      for (var i = 0; i < shape.size(); i++) {
        var one = shape.get(i);
        if (one.startsWith("{") && one.endsWith("}")) continue;
        if (!one.equals(concrete.get(i))) {
          matched = false;
          break;
        }
      }
      if (matched) return template;
    }
    return null;
  }

  /** 拆路径段（去掉首尾的 /）。 */
  static List<String> segments(String path) {
    var trimmed = path;
    while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    if (trimmed.isEmpty()) return List.of();
    return List.of(trimmed.split("/"));
  }

  /** 该操作声明过的 query 参数名。 */
  static List<String> queryParameters(JsonNode operation) {
    var names = new ArrayList<String>();
    var parameters = operation.path("parameters");
    if (!parameters.isArray()) return names;
    for (var parameter : parameters) {
      if (!"query".equals(parameter.path("in").asText(""))) continue;
      var name = parameter.path("name").asText("");
      if (!name.isEmpty()) names.add(name);
    }
    return names;
  }

  /** 该操作声明过的路径参数名（沙箱里这些段要自己填）。 */
  static Map<String, String> pathParameters(JsonNode operation) {
    var result = new LinkedHashMap<String, String>();
    var parameters = operation.path("parameters");
    if (!parameters.isArray()) return result;
    for (var parameter : parameters) {
      if (!"path".equals(parameter.path("in").asText(""))) continue;
      var name = parameter.path("name").asText("");
      if (!name.isEmpty()) result.put(name, parameter.path("description").asText(""));
    }
    return result;
  }
}
