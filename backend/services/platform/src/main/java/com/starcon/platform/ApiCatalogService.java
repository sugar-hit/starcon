package com.starcon.platform;

import static com.starcon.platform.ApiCatalogModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.1.2「接口目录」的服务层。
 *
 * <p>蓝图原文：「<b>职责</b>：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
 * 支持在线测试（沙箱环境）。<b>输入</b>：接口定义（OpenAPI 生成物）；测试请求。
 * <b>输出</b>：接口文档 + 测试结果。<b>关系</b>：上游 = 后端 OpenAPI spec（单一真相源）；下游 = 外部系统。
 * <b>接口契约由后端 spec 生成，文档不手写</b>——手写文档必然与实现漂移。」
 *
 * <p><b>五条纪律：</b>
 *
 * <ol>
 *   <li><b>目录 = spec 的投影，不入库</b>：每次真去各服务取 {@code /v3/api-docs} 再解析。
 *       接口清单一变，目录立刻跟着变 —— 因为它**就是**那份 spec。
 *   <li><b>业务域 = 对外路径的首段</b>：口径来自 spec 本身，不是本模块写死的词表。
 *   <li><b>限流说明挂在应用上</b>：配额是 18.1.1.1 的能力（{@code platform_app_quota} /
 *       {@code platform_app_scope}，**同一个服务、同一套库**，不是跨服务读取）。
 *       这里如实说「哪个应用、哪个窗口、限多少」，<b>不</b>替网关编一套按接口的限流。
 *   <li><b>沙箱拒发写请求</b>：沙箱必须与生产数据隔离（见 {@link ApiSandboxExecutor}）。
 *   <li><b>读不到就显式说读不到</b>：某个服务的 spec 读不出来时，那一格标成读不到并写明原因，
 *       <b>不</b>悄悄消失（消失 = 「它没有对外接口」，那是假的）。
 * </ol>
 *
 * <p>⚠️ <b>数据访问一律走 jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 */
@Service
public class ApiCatalogService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 限流说明的固定口径（每一条限流说明都会带上它）。 */
  static final String RATE_LIMIT_POLICY =
      "限流按**应用**判定（配额挂在应用上，不在接口上）：网关用凭证所属的应用查配额，"
          + "同一个应用调本域的任何一个接口都共用同一套配额。";

  private final DSLContext sql;
  private final ApiSpecSource specs;
  private final ApiSandboxExecutor sandbox;

  public ApiCatalogService(DSLContext sql, ApiSpecSource specs, ApiSandboxExecutor sandbox) {
    this.sql = sql;
    this.specs = specs;
    this.sandbox = sandbox;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  // ================================================================ 能力声明

  /**
   * 18.1.1.2 的能力声明：逐维说清「有没有、依据是什么、缺了归谁」。
   *
   * <p>🔴 <b>「沙箱的写请求」这一维如实答「没有」</b> —— 本仓没有独立的沙箱部署，
   * 写请求打出去就是打在真实业务表上。沙箱因此只放行读方法，写方法如实答 409。
   */
  public ApiCatalogCapabilities capabilities() {
    var capabilities =
        List.of(
            new ApiCatalogCapability(
                "18.1.1.2",
                "接口目录（按业务域分组的对外接口清单）",
                true,
                "ApiSpecSource 真去各服务的 /v3/api-docs 取活着的契约并解析；"
                    + "业务域 = 对外路径的首段（口径来自 spec 本身，不是写死的词表）；"
                    + "目录**不入库**（入库就等于把 spec 抄一份，抄的那份迟早与实现漂移）",
                "",
                ""),
            new ApiCatalogCapability(
                "18.1.1.2",
                "每个接口的参数 / 返回 / 示例",
                true,
                "参数取 spec 的 parameters（含 path / query / header），返回取 spec 的 responses；"
                    + "示例优先用 spec 自带的，spec 没给时按 schema 生成并**明确标注出处**"
                    + "（GENERATED_FROM_SCHEMA ≠ SPEC）",
                "",
                "schema 生成出来的示例不是官方契约，页面上标着"),
            new ApiCatalogCapability(
                "18.1.1.2",
                "每个接口的限流说明",
                true,
                "真读 18.1.1.1 的 platform_app_quota（配额配置）与 platform_app_scope（授权范围）"
                    + "——同一个服务、同一套库，不是跨服务读取；"
                    + "限流是**按应用**判定的，本模块如实说明「哪个应用、哪个窗口、限多少」",
                "",
                "没有授权到这个业务域的应用时如实说没有，不编一个「默认无限」"),
            new ApiCatalogCapability(
                "18.1.1.2",
                "沙箱在线测试（真发一次请求并回真实响应）",
                true,
                "ApiSandboxExecutor 真发 HTTP（带调用方自己的 bearer，上游仍按成员关系解析租户并强制 RLS）；"
                    + "回真实状态码 + 响应体摘要 + 真实耗时；结果落 platform_api_sandbox_run（可复现、可追责）",
                "",
                ""),
            new ApiCatalogCapability(
                "18.1.1.2",
                "**沙箱的写请求（POST / PUT / PATCH / DELETE）**",
                false,
                "本仓没有独立的沙箱部署，写请求打出去就是打在**真实业务表**上 —— "
                    + "沙箱必须与生产数据隔离，因此只放行 GET / HEAD",
                "18.1",
                "沙箱对写请求如实答 409 且**一次请求都不发出去**；"
                    + "写操作请在「应用与凭证」里用真实凭证对真实环境调用（那里有配额 / IP 白名单 / 审计兜着）"));
    var gaps =
        List.of(
            new ApiCatalogGap(
                "18.1.1.1",
                "应用与凭证",
                "platform",
                true,
                "上游",
                "本模块的限流说明以它的配额与授权范围为上游（同一个服务、同一套库的直接读取）"),
            new ApiCatalogGap(
                "18.1.1.3",
                "调用监控",
                "platform",
                false,
                "同卷兄弟",
                "调用监控是「调了多少、有没有超限」的运行态；本模块是「有哪些接口、怎么调」的目录 —— "
                    + "本车道**不依赖**它，也没有顺手实现它"),
            new ApiCatalogGap(
                "18.1.2.1",
                "事件订阅",
                "platform",
                false,
                "同批兄弟",
                "事件订阅是「外部系统把事件推进来」，与本模块「本系统对外提供接口」方向相反，不复用那条通道"));
    return new ApiCatalogCapabilities(capabilities, gaps);
  }

  // ================================================================ 概览

  /** 概览：几个数。 */
  public ApiCatalogOverview overview() {
    var services = serviceStatuses();
    var all = allInterfaces();
    var available =
        services.stream().filter(ApiCatalogServiceStatus::available).count();
    var domains = domainIndex(all);
    return new ApiCatalogOverview(
        services.size(),
        available,
        services.size() - available,
        domains.size(),
        all.size(),
        all.stream().filter(ApiCatalogInterface::sandboxTestable).count(),
        count("select count(*) from platform_api_sandbox_run"),
        count("select count(*) from platform_api_sandbox_run where outcome = 'COMPLETED'"),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 业务域

  /** 业务域清单（真数据：按对外路径首段分组）。 */
  public List<ApiCatalogDomain> domains() {
    return new ArrayList<>(domainIndex(allInterfaces()).values());
  }

  // ================================================================ 接口清单

  /**
   * 接口清单（真分页 + 真筛选 + 真搜索）。
   *
   * <p>⚠️ 分页是**对真解析出来的清单**切片：清单来自各服务的活 spec，
   * 每一页都是同一份清单的不同切片（不是假分页 —— 没有任何写死的数组）。
   */
  public ApiCatalogInterfacePage interfaces(
      String domain, String service, String keyword, String method, int page, int size) {
    var all = allInterfaces();
    var filtered = new ArrayList<ApiCatalogInterface>();
    var wantedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    var wantedDomain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
    var wantedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
    var needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    for (var item : all) {
      if (!wantedDomain.isEmpty() && !item.domain().toLowerCase(Locale.ROOT).equals(wantedDomain)) {
        continue;
      }
      if (!wantedService.isEmpty() && !item.service().toLowerCase(Locale.ROOT).equals(wantedService)) {
        continue;
      }
      if (!wantedMethod.isEmpty() && !item.method().equals(wantedMethod)) continue;
      if (!needle.isEmpty() && !matches(item, needle)) continue;
      filtered.add(item);
    }
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var from = Math.min(page2 * size2, filtered.size());
    var to = Math.min(from + size2, filtered.size());
    return new ApiCatalogInterfacePage(
        filtered.subList(from, to),
        page2,
        size2,
        filtered.size(),
        new ArrayList<>(domainIndex(all).values()),
        serviceStatuses());
  }

  /** 关键词命中：路径 / 摘要 / 说明 / 操作 ID / 标签。 */
  private static boolean matches(ApiCatalogInterface item, String needle) {
    if (item.path().toLowerCase(Locale.ROOT).contains(needle)) return true;
    if (item.summary().toLowerCase(Locale.ROOT).contains(needle)) return true;
    if (item.description().toLowerCase(Locale.ROOT).contains(needle)) return true;
    if (item.resourceGroup().toLowerCase(Locale.ROOT).contains(needle)) return true;
    for (var tag : item.tags()) {
      if (tag.toLowerCase(Locale.ROOT).contains(needle)) return true;
    }
    return false;
  }

  // ================================================================ 接口详情

  /**
   * 一个接口的详情：参数 / 返回 / 限流说明 / 示例。
   *
   * @param templatePath spec 里声明的路径形状（例如 {@code /catalog/spus/{spuId}}）
   */
  public ApiCatalogInterfaceDetail detail(String service, String method, String templatePath) {
    check(service != null && !service.isBlank(), "必须指定服务");
    check(templatePath != null && !templatePath.isBlank(), "必须指定路径");
    var entry = specs.byService(service.trim());
    if (entry == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务不在册：" + service);
    }
    if (!entry.hasSpec()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "该服务的接口清单读不到：" + entry.unavailableReason());
    }
    var method2 = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    var item = entry.spec().path("paths").path(templatePath.trim());
    if (!item.isObject()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在：" + templatePath);
    }
    var operation = item.path(method2.toLowerCase(Locale.ROOT));
    if (!operation.isObject()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "该接口没有 " + method2 + " 方法（只有 " + ApiSandboxExecutor.methodsOf(item) + "）");
    }
    var spec = entry.spec();
    var api = toInterface(entry.service(), templatePath.trim(), method2, operation);

    // 参数：path / query / header / cookie（**全取**，不只取 query）。
    var parameters = new ArrayList<ApiCatalogParameter>();
    for (var parameter : operation.path("parameters")) {
      if (!parameter.isObject()) continue;
      var schema = parameter.path("schema");
      parameters.add(
          new ApiCatalogParameter(
              parameter.path("name").asText(""),
              parameter.path("in").asText(""),
              parameter.path("required").asBoolean(false),
              ApiSchemaReader.typeName(spec, schema),
              parameter.path("description").asText(""),
              parameter.path("deprecated").asBoolean(false),
              parameter.has("example")
                  ? parameter.path("example").asText()
                  : ApiSchemaReader.scalarExample(spec, schema)));
    }

    // 请求体：取第一个 content 类型（spec 里通常只有 application/json）。
    var requestBody = operation.path("requestBody");
    var bodyPresent = requestBody.isObject();
    var bodyContentType = "";
    var bodySchema = (JsonNode) null;
    if (bodyPresent) {
      var content = requestBody.path("content");
      var iterator = content.fields();
      if (iterator.hasNext()) {
        var entryContent = iterator.next();
        bodyContentType = entryContent.getKey();
        bodySchema = entryContent.getValue().path("schema");
      }
    }
    var requestExample =
        bodySchema == null ? new ApiCatalogExample("NONE", null) : ApiSchemaReader.example(spec, bodySchema);

    // 返回：逐个状态码读出来。
    var responses = new ArrayList<ApiCatalogResponse>();
    var responseExample = new ApiCatalogExample("NONE", null);
    var responsesNode = operation.path("responses");
    var responseIterator = responsesNode.fields();
    while (responseIterator.hasNext()) {
      var responseEntry = responseIterator.next();
      var statusCode = responseEntry.getKey();
      var response = responseEntry.getValue();
      var contentType = "";
      var schema = (JsonNode) null;
      var content = response.path("content");
      var contentIterator = content.fields();
      if (contentIterator.hasNext()) {
        var one = contentIterator.next();
        contentType = one.getKey();
        schema = one.getValue().path("schema");
      }
      var example =
          schema == null ? new ApiCatalogExample("NONE", null) : ApiSchemaReader.example(spec, schema);
      // 成功响应（2xx）的示例作为「返回示例」；失败响应给的是错误形状，不是成功样例。
      if (responseExample.source().equals("NONE") && statusCode.startsWith("2")) {
        responseExample = example;
      }
      responses.add(
          new ApiCatalogResponse(
              statusCode,
              response.path("description").asText(""),
              contentType.isEmpty() ? null : contentType,
              schema == null ? List.of() : ApiSchemaReader.fields(spec, schema),
              example.json()));
    }

    return new ApiCatalogInterfaceDetail(
        api,
        parameters,
        new ApiCatalogRequestBody(
            bodyPresent,
            bodyContentType.isEmpty() ? null : bodyContentType,
            requestBody.path("required").asBoolean(false),
            bodySchema == null ? List.of() : ApiSchemaReader.fields(spec, bodySchema),
            requestExample.json()),
        responses,
        requestExample,
        responseExample,
        rateLimit(api.domain()));
  }

  // ================================================================ 限流说明

  /**
   * 一个业务域的限流说明（**真实数据**，来自 18.1.1.1 的表）。
   *
   * <p>🔴 授权范围与接口的对应关系用一条**写明的约定**：<b>资源码 = 业务域码</b>。
   * 18.1.1.1 的资源码是运营自由登记的文本，本模块**不**去猜「哪个资源码对应哪个接口」——
   * 猜错会让「这个应用能不能调这个接口」变成一句假话。约定写在说明里，匹配不到就说匹配不到。
   */
  public ApiCatalogRateLimit rateLimit(String domain) {
    var code = domain == null ? "" : domain.trim();
    var apps =
        sql.fetch(
                "select a.id, a.app_code, a.app_name, s.action_code"
                    + " from platform_app_scope s"
                    + " join platform_app a on a.tenant_id = s.tenant_id and a.id = s.app_id"
                    + " where lower(s.resource_code) = lower(?) and a.status <> 'REVOKED'"
                    + " order by a.app_code, s.action_code",
                code)
            .map(
                r ->
                    new Object[] {
                      r.get("id", UUID.class),
                      r.get("app_code", String.class),
                      r.get("app_name", String.class),
                      r.get("action_code", String.class)
                    });
    var byApp = new LinkedHashMap<UUID, List<Object[]>>();
    for (var row : apps) {
      byApp.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add(row);
    }
    var appQuotas = new ArrayList<ApiCatalogAppQuota>();
    for (var entry : byApp.entrySet()) {
      var actions = new ArrayList<String>();
      for (var row : entry.getValue()) {
        actions.add((String) row[3]);
      }
      var windows =
          sql.fetch(
                  "select window_type, limit_count, enabled from platform_app_quota"
                      + " where app_id = ? order by window_type",
                  entry.getKey())
              .map(
                  r ->
                      new ApiCatalogQuotaWindow(
                          r.get("window_type", String.class),
                          r.get("limit_count", BigDecimal.class),
                          r.get("enabled", Boolean.class)));
      appQuotas.add(
          new ApiCatalogAppQuota(
              entry.getKey().toString(),
              (String) entry.getValue().get(0)[1],
              (String) entry.getValue().get(0)[2],
              actions,
              windows));
    }
    var resourceCodes =
        sql.fetch("select distinct resource_code from platform_app_scope order by resource_code")
            .map(r -> r.get(0, String.class));
    var note =
        appQuotas.isEmpty()
            ? "本租户当前没有应用被授权到这个业务域（授权资源码按「资源码 = 业务域码」的约定登记在"
                + "「应用与凭证」里；匹配不到就是真的没有，不是「默认无限」）"
            : appQuotas.stream().allMatch(a -> a.windows().isEmpty())
                ? "已授权的应用没有配置配额 ⇒ 不限流（不是限 0 次）"
                : "限流按上面这些应用的配额窗口判定；同一应用调本域的任何一个接口都共用同一套配额";
    return new ApiCatalogRateLimit(RATE_LIMIT_POLICY, appQuotas, resourceCodes, note);
  }

  // ================================================================ 沙箱

  /** 可测试的目标服务清单。 */
  public List<ApiCatalogSandboxTarget> sandboxTargets() {
    var all = allInterfaces();
    var result = new ArrayList<ApiCatalogSandboxTarget>();
    for (var entry : specs.all()) {
      var testable =
          all.stream()
              .filter(i -> i.service().equals(entry.service()) && i.sandboxTestable())
              .count();
      result.add(
          new ApiCatalogSandboxTarget(
              entry.service(),
              entry.title(),
              entry.baseUrl(),
              entry.available(),
              entry.unavailableReason().isEmpty() ? null : entry.unavailableReason(),
              entry.interfaceCount(),
              (int) testable));
    }
    return result;
  }

  /**
   * 跑一次沙箱测试（真发请求 + 落一条留痕）。
   *
   * <p>⚠️ 「没发出去」的几种结论（写请求被拒 / 目标不在 spec / 上游不可达）
   * **也要落痕** —— 否则「我明明点了测试，怎么什么都没发生」没有答案。
   * 留痕之后由**控制器**把结论翻成真实的状态码（事务已经提交，行已经在库里）。
   */
  public ApiCatalogSandboxResult sandboxRun(
      ApiCatalogSandboxInput input, AccessService.Access actor, String bearer) {
    check(input != null, "缺少请求体");
    var service = input.targetService() == null ? "" : input.targetService().trim();
    check(!service.isEmpty(), "必须指定目标服务");
    var entry = specs.byService(service);
    if (entry == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标服务不在册：" + service);
    }
    if (!entry.hasSpec()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "目标服务的接口清单读不到（" + entry.unavailableReason() + "）—— 读不到，不是没有");
    }
    var outcome = sandbox.execute(service, entry.baseUrl(), entry.spec(), input, bearer);
    var queryString = queryStringOf(input.query());
    var requestBody = input.body() == null ? "" : input.body();
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into platform_api_sandbox_run(id,tenant_id,target_service,http_method,target_path,"
            + " query_string,request_body,status_code,response_summary,response_bytes,duration_ms,"
            + " outcome,message,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        service,
        input.httpMethod() == null ? "" : input.httpMethod().trim().toUpperCase(Locale.ROOT),
        input.targetPath() == null ? "" : input.targetPath().trim(),
        queryString,
        requestBody.length() > 8000 ? requestBody.substring(0, 8000) : requestBody,
        outcome.statusCode(),
        outcome.bodySummary() == null ? "" : outcome.bodySummary(),
        outcome.responseBytes(),
        outcome.durationMs(),
        outcome.outcome(),
        outcome.message() == null ? "" : outcome.message(),
        actor.userId(),
        now);
    return new ApiCatalogSandboxResult(
        id.toString(),
        service,
        input.httpMethod() == null ? "" : input.httpMethod().trim().toUpperCase(Locale.ROOT),
        input.targetPath() == null ? "" : input.targetPath().trim(),
        queryString,
        outcome.statusCode(),
        outcome.contentType(),
        outcome.bodySummary() == null ? "" : outcome.bodySummary(),
        outcome.truncated(),
        outcome.responseBytes(),
        outcome.durationMs(),
        outcome.outcome(),
        outcome.message(),
        now);
  }

  /** 沙箱测试历史（真分页 + 真筛选）。 */
  public ApiCatalogSandboxRunPage sandboxRuns(String service, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var filters = new ArrayList<Object>();
    if (service != null && !service.isBlank()) {
      where.append(" and target_service = ?");
      filters.add(service.trim());
    }
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var total =
        sql.fetchOne("select count(*) from platform_api_sandbox_run" + where, filters.toArray())
            .get(0, Long.class);
    var completed =
        sql.fetchOne(
                "select count(*) from platform_api_sandbox_run" + where + " and outcome = 'COMPLETED'",
                filters.toArray())
            .get(0, Long.class);
    var refused =
        sql.fetchOne(
                "select count(*) from platform_api_sandbox_run" + where
                    + " and outcome = 'REFUSED_WRITE'",
                filters.toArray())
            .get(0, Long.class);
    var paging = new ArrayList<Object>(filters);
    paging.add(size2);
    paging.add(page2 * size2);
    var rows =
        sql.fetch(
            "select id,target_service,http_method,target_path,query_string,status_code,"
                + " response_summary,response_bytes,duration_ms,outcome,message,created_at"
                + " from platform_api_sandbox_run"
                + where
                + " order by created_at desc, id"
                + " limit ? offset ?",
            paging.toArray());
    return new ApiCatalogSandboxRunPage(
        rows.map(ApiCatalogService::toSandboxRow), page2, size2, total, completed, refused);
  }

  private static ApiCatalogSandboxResult toSandboxRow(org.jooq.Record r) {
    var status = r.get("status_code", Integer.class);
    return new ApiCatalogSandboxResult(
        r.get("id", UUID.class).toString(),
        r.get("target_service", String.class),
        r.get("http_method", String.class),
        r.get("target_path", String.class),
        r.get("query_string", String.class),
        status,
        null,
        r.get("response_summary", String.class),
        false,
        r.get("response_bytes", Long.class),
        r.get("duration_ms", Integer.class),
        r.get("outcome", String.class),
        r.get("message", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String queryStringOf(Map<String, String> query) {
    // 与真正发出去的那一份用**同一个**编码函数（留痕里的查询串必须等于实际发出去的）。
    return ApiSandboxExecutor.encodeQuery(query);
  }

  // ================================================================ spec → 目录

  /** 各服务的 spec 可读状态（**读不到的那些也在里面**，带原因）。 */
  public List<ApiCatalogServiceStatus> serviceStatuses() {
    var result = new ArrayList<ApiCatalogServiceStatus>();
    for (var entry : specs.all()) {
      result.add(
          new ApiCatalogServiceStatus(
              entry.service(),
              entry.title(),
              entry.baseUrl(),
              entry.available(),
              entry.unavailableReason().isEmpty() ? null : entry.unavailableReason(),
              entry.interfaceCount()));
    }
    return result;
  }

  /** 全部接口（真解析出来的，不是写死的清单）。 */
  public List<ApiCatalogInterface> allInterfaces() {
    var result = new ArrayList<ApiCatalogInterface>();
    for (var entry : specs.all()) {
      if (!entry.hasSpec()) continue;
      var paths = entry.spec().path("paths");
      if (!paths.isObject()) continue;
      var iterator = paths.fields();
      while (iterator.hasNext()) {
        var pathEntry = iterator.next();
        var path = pathEntry.getKey();
        var item = pathEntry.getValue();
        if (!item.isObject()) continue;
        for (var method : ApiSpecSource.HTTP_METHODS) {
          if (!item.has(method)) continue;
          var operation = item.path(method);
          if (!operation.isObject()) continue;
          result.add(toInterface(entry.service(), path, method.toUpperCase(Locale.ROOT), operation));
        }
      }
    }
    result.sort(
        java.util.Comparator.comparing(ApiCatalogInterface::domain)
            .thenComparing(ApiCatalogInterface::resourceGroup)
            .thenComparing(ApiCatalogInterface::path)
            .thenComparing(ApiCatalogInterface::method));
    return result;
  }

  /** 业务域索引（按对外路径首段分组）。 */
  private Map<String, ApiCatalogDomain> domainIndex(List<ApiCatalogInterface> all) {
    var paths = new LinkedHashMap<String, Integer>();
    var services = new LinkedHashMap<String, LinkedHashSet<String>>();
    for (var item : all) {
      paths.merge(item.domain(), 1, Integer::sum);
      services.computeIfAbsent(item.domain(), key -> new LinkedHashSet<>()).add(item.service());
    }
    var result = new LinkedHashMap<String, ApiCatalogDomain>();
    for (var domain : paths.keySet()) {
      var pathCount =
          all.stream().filter(i -> i.domain().equals(domain)).map(i -> i.path()).distinct().count();
      result.put(
          domain,
          new ApiCatalogDomain(
              domain, new ArrayList<>(services.get(domain)), (int) pathCount, paths.get(domain)));
    }
    return result;
  }

  /** spec 的一个操作 → 接口清单里的一行。 */
  private static ApiCatalogInterface toInterface(
      String service, String path, String method, JsonNode operation) {
    var segments = ApiSandboxExecutor.segments(path);
    var domain = segments.isEmpty() ? "" : segments.get(0);
    var group = segments.size() < 2 ? "" : segments.get(1);
    var tags = new ArrayList<String>();
    for (var tag : operation.path("tags")) {
      tags.add(tag.asText(""));
    }
    var parameterCount = operation.path("parameters").size();
    if (operation.path("requestBody").isObject()) parameterCount++;
    return new ApiCatalogInterface(
        service + " " + method + " " + path,
        service,
        domain,
        group,
        method,
        path,
        operation.path("summary").asText(""),
        operation.path("description").asText(""),
        operation.path("deprecated").asBoolean(false),
        tags,
        parameterCount,
        operation.path("responses").size(),
        ApiSandboxExecutor.READ_METHODS.contains(method));
  }
}
