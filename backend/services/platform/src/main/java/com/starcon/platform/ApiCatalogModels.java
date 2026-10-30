package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 18.1.1.2「接口目录」的模型（卷 18 → 18.1 开放平台 → 18.1.1 API 管理）。
 *
 * <p>蓝图原文：「<b>职责</b>：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
 * 支持在线测试（沙箱环境）。<b>输入</b>：接口定义（OpenAPI 生成物）；测试请求。
 * <b>输出</b>：接口文档 + 测试结果。<b>关系</b>：上游 = 后端 OpenAPI spec（单一真相源）；下游 = 外部系统。
 * <b>接口契约由后端 spec 生成，文档不手写</b>——手写文档必然与实现漂移。」
 *
 * <p><b>五条纪律（与本仓既有模块同构）：</b>
 *
 * <ol>
 *   <li><b>接口清单不入库</b>：它是各服务 OpenAPI spec 的<b>投影</b>，每次真解析。
 *       库里只落「测试结果」——那是本模块自己产生的事实。
 *   <li><b>示例必须能说清出处</b>：{@code exampleSource} 区分「spec 自带」与
 *       「按 schema 生成」——把后者冒充前者，就是把手写文档伪装成契约。
 *   <li><b>限流说明挂在应用上，不挂在接口上</b>：配额是 18.1.1.1 的能力，
 *       这里如实说明「按哪个应用、哪个窗口限」，不替网关编一套按接口的限流。
 *   <li><b>沙箱拒发写请求</b>：沙箱必须与生产数据隔离（详见 {@link ApiSandboxExecutor}）。
 *   <li><b>读写不到就显式说读不到</b>：某个服务的 spec 读不出来时，
 *       那一格标成 UNAVAILABLE 并写明原因，**不**悄悄消失（消失 = 「它没有接口」）。
 * </ol>
 */
public final class ApiCatalogModels {

  private ApiCatalogModels() {}

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了归谁」。 */
  public record ApiCatalogCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record ApiCatalogGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 18.1.1.2 的能力声明。 */
  public record ApiCatalogCapabilities(
      List<ApiCatalogCapability> capabilities, List<ApiCatalogGap> downstreamGaps) {}

  // ================================================================ 业务域

  /**
   * 一个业务域（= 对外路径的<b>首段</b>，例如 {@code catalog} / {@code custom-report}）。
   *
   * <p>🔴 分组口径是**从 spec 里真读出来的路径首段**，不是本模块写死的清单 ——
   * 哪个服务对外暴露了什么形状，只有 spec 知道。
   *
   * @param services 贡献这个域的服务（一个域可能由多个服务共同暴露）
   */
  public record ApiCatalogDomain(
      String code, List<String> services, int pathCount, int interfaceCount) {}

  /**
   * 一个服务的 spec 可读状态。
   *
   * <p>🔴 <b>读不到的服务必须出现在这里</b>（带原因）—— 悄悄消失 = 「它没有对外接口」，
   * 那是假的。
   */
  public record ApiCatalogServiceStatus(
      String service,
      String title,
      String baseUrl,
      boolean available,
      @Schema(nullable = true) String unavailableReason,
      int interfaceCount) {}

  // ================================================================ 接口清单

  /** 接口清单里的一行。 */
  public record ApiCatalogInterface(
      String key,
      String service,
      String domain,
      String resourceGroup,
      String method,
      String path,
      String summary,
      String description,
      boolean deprecated,
      List<String> tags,
      int parameterCount,
      int responseCount,
      boolean sandboxTestable) {}

  /**
   * 接口清单页（真分页 + 真筛选）。
   *
   * @param domains 业务域清单（**按对外路径首段分组**）
   * @param services 每个服务的 spec 可读状态（读不到的那些也在里面，带原因）
   */
  public record ApiCatalogInterfacePage(
      List<ApiCatalogInterface> items,
      int page,
      int size,
      long total,
      List<ApiCatalogDomain> domains,
      List<ApiCatalogServiceStatus> services) {}

  // ================================================================ 接口详情

  /** 一个参数（路径 / 查询 / 头 / cookie）。 */
  public record ApiCatalogParameter(
      String name,
      String location,
      boolean required,
      String type,
      String description,
      boolean deprecated,
      @Schema(nullable = true) String example) {}

  /** 请求体 / 响应体里的一个字段。 */
  public record ApiCatalogField(
      String name, String type, boolean required, String description, @Schema(nullable = true) String example) {}

  /** 请求体。 */
  public record ApiCatalogRequestBody(
      boolean present,
      @Schema(nullable = true) String contentType,
      boolean required,
      List<ApiCatalogField> fields,
      @Schema(nullable = true) String exampleJson) {}

  /** 一个响应。 */
  public record ApiCatalogResponse(
      String statusCode,
      String description,
      @Schema(nullable = true) String contentType,
      List<ApiCatalogField> fields,
      @Schema(nullable = true) String exampleJson) {}

  /**
   * 一个示例。
   *
   * @param source {@code SPEC}（spec 里自带）/ {@code GENERATED_FROM_SCHEMA}（按 schema 生成）/
   *     {@code NONE}（两者都没有）
   */
  public record ApiCatalogExample(String source, @Schema(nullable = true) String json) {}

  /** 一个配额窗口（上限一律 BigDecimal —— 禁浮点）。 */
  public record ApiCatalogQuotaWindow(
      String windowType, BigDecimal limitCount, boolean enabled) {}

  /**
   * 一个被授权的应用及其配额。
   *
   * @param grantedActions 该应用在这个业务域上被授予的操作（READ / WRITE）
   */
  public record ApiCatalogAppQuota(
      String appId,
      String appCode,
      String appName,
      List<String> grantedActions,
      List<ApiCatalogQuotaWindow> windows) {}

  /**
   * 限流说明。
   *
   * @param appQuotas 被授权到这个业务域的应用及其配额（**真实数据**，来自 18.1.1.1 的表）
   * @param grantedResourceCodes 本租户已授权过的资源码（原样取回，不做解释性改写）
   */
  public record ApiCatalogRateLimit(
      String policy, List<ApiCatalogAppQuota> appQuotas, List<String> grantedResourceCodes, String note) {}

  /** 接口详情：参数 / 返回 / 限流说明 / 示例。 */
  public record ApiCatalogInterfaceDetail(
      ApiCatalogInterface api,
      List<ApiCatalogParameter> parameters,
      ApiCatalogRequestBody requestBody,
      List<ApiCatalogResponse> responses,
      ApiCatalogExample requestExample,
      ApiCatalogExample responseExample,
      ApiCatalogRateLimit rateLimit) {}

  // ================================================================ 概览

  /** 概览：几个数。 */
  public record ApiCatalogOverview(
      long serviceCount,
      long availableServiceCount,
      long unavailableServiceCount,
      long domainCount,
      long interfaceCount,
      long sandboxTestableCount,
      long sandboxRunCount,
      long completedSandboxRunCount,
      OffsetDateTime generatedAt) {}

  // ================================================================ 沙箱

  /** 一个可测试的目标服务（沙箱能往哪儿发请求）。 */
  public record ApiCatalogSandboxTarget(
      String service,
      String title,
      String baseUrl,
      boolean available,
      @Schema(nullable = true) String unavailableReason,
      int interfaceCount,
      int testableInterfaceCount) {}

  /**
   * 沙箱测试请求。
   *
   * <p>🔴 {@code query} 的键**必须**是该接口在 spec 里声明过的查询参数 ——
   * 不声明的参数一律拒发（否则「测试」就成了一个能随便探测的代理）。
   */
  public record ApiCatalogSandboxInput(
      String targetService,
      String httpMethod,
      String targetPath,
      Map<String, String> query,
      @Schema(nullable = true) String body) {}

  /** 沙箱测试的结果（**真响应**，不是写死的样例）。 */
  public record ApiCatalogSandboxResult(
      String runId,
      String targetService,
      String httpMethod,
      String targetPath,
      String queryString,
      @Schema(nullable = true) Integer statusCode,
      @Schema(nullable = true) String contentType,
      String bodySummary,
      boolean truncated,
      long responseBytes,
      long durationMs,
      String outcome,
      String message,
      OffsetDateTime createdAt) {}

  /** 沙箱测试历史页（真分页）。 */
  public record ApiCatalogSandboxRunPage(
      List<ApiCatalogSandboxResult> items,
      int page,
      int size,
      long total,
      long completedCount,
      long refusedCount) {}
}
