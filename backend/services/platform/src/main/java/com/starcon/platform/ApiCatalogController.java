package com.starcon.platform;

import static com.starcon.platform.ApiCatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.1.2「接口目录」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/api-catalog}</b>：与同服务的兄弟模块（{@code /platform/apps} /
 * {@code /platform/text} / {@code /platform/finance-software}）刻意不共用首段 ——
 * 四个模块各管各的路径前缀，前端的 api-proxy 白名单也按首 token 天然互斥。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.api-catalog.read / .test}：
 * 「沙箱测试」是**独立**的一个码 —— 能看接口文档的人不该顺手对生产发请求。
 * AccessService 的判据要求同一个 grant 里同时有该动作与 {@code .read}，
 * 所以能测试的人至少看得懂接口是什么。
 */
@RestController
@RequestMapping("/platform/api-catalog")
public class ApiCatalogController {

  private final AccessService access;
  private final ApiCatalogService catalog;

  public ApiCatalogController(AccessService access, ApiCatalogService catalog) {
    this.access = access;
    this.catalog = catalog;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.api-catalog." + permission);
    return access.run(actor, "platform.api-catalog." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public ApiCatalogCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> catalog.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public ApiCatalogOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> catalog.overview());
  }

  // ================================================================ 目录

  /** 业务域清单（按对外路径首段分组 —— 口径来自 spec 本身）。 */
  @GetMapping("/domains")
  public List<ApiCatalogDomain> domains(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> catalog.domains());
  }

  /** 接口清单（真分页 + 真筛选 + 真搜索）。 */
  @GetMapping("/interfaces")
  public ApiCatalogInterfacePage interfaces(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String domain,
      @RequestParam(required = false) String service,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String method,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> catalog.interfaces(domain, service, keyword, method, page, size));
  }

  /**
   * 一个接口的详情：参数 / 返回 / 限流说明 / 示例。
   *
   * <p>路径参数传的是 **spec 里声明的路径形状**（例如 {@code /catalog/spus/{spuId}}），
   * 不是具体实例 —— 目录里列的就是形状。
   */
  @GetMapping("/interfaces/detail")
  public ApiCatalogInterfaceDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String service,
      @RequestParam String method,
      @RequestParam String path) {
    return call(auth, "read", actor -> catalog.detail(service, method, path));
  }

  // ================================================================ 沙箱

  /** 可测试的目标服务清单（每个服务能不能读到 spec、有多少接口可测）。 */
  @GetMapping("/sandbox/targets")
  public List<ApiCatalogSandboxTarget> sandboxTargets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> catalog.sandboxTargets());
  }

  /**
   * 在沙箱里真发一次请求。
   *
   * <p>🔴 **返回的是真实结果**：上游真的回了什么状态码、什么响应体摘要、耗时多少。
   * 「没发出去」的那几种结论（写请求被拒 / 目标不在 spec / 上游不可达）
   * 也用**真实的状态码**回给调用方（409 / 400 / 503），
   * 而不是一律 200 —— 一律 200 会让「被拒了」看起来像「跑通了」。
   *
   * <p>⚠️ 沙箱只放行 GET / HEAD：写请求会打到真实业务表，沙箱必须与生产数据隔离。
   */
  @PostMapping("/sandbox/runs")
  public ResponseEntity<ApiCatalogSandboxResult> sandboxRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApiCatalogSandboxInput input) {
    var result =
        call(auth, "test", actor -> catalog.sandboxRun(input, actor, auth));
    return switch (result.outcome()) {
      case "COMPLETED" -> ResponseEntity.ok(result);
      case "REFUSED_WRITE" -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
      case "INVALID_TARGET" -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
      default -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    };
  }

  /** 沙箱测试历史（真分页 + 真筛选）。 */
  @GetMapping("/sandbox/runs")
  public ApiCatalogSandboxRunPage sandboxRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String service,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> catalog.sandboxRuns(service, page, size));
  }
}
