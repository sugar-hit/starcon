package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ApiCatalogModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.1.1.2「接口目录」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>🔴 <b>「接口目录」的上游是真的</b>：本测试起了一个真的 HTTP 服务，它
 * <ul>
 *   <li>在 {@code /v3/api-docs} 上返回一份**真的 OpenAPI 契约**（本服务就是去那儿取 catalog 的）；
 *   <li>在 {@code /catalog/spus} 等路径上返回**真的业务响应**（沙箱测试打的就是它）。
 * </ul>
 * 所以「目录来自 spec」「沙箱返回真响应」这两条不是断言一句注释，
 * 而是断言**一次真的 HTTP 往返**。
 *
 * <p>🔴 <b>「目录不是写死的」也在这里被证伪一次</b>：{@code 目录会跟着上游 spec 变} 那个用例
 * 直接改了上游服务的契约，等 TTL 过期后断言目录里多出了新接口 ——
 * 写死的清单过不了这一关。
 *
 * <p>租户隔离的三条断言在 {@code ApiCatalogTenancyIntegrationTest} 里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiCatalogIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游服务收到的真实请求（证明沙箱真的发出去过）。 */
  static final List<String> upstreamCalls = new CopyOnWriteArrayList<>();

  /** 上游收到的 Authorization 头（证明转发的是调用方自己的会话）。 */
  static final Map<String, String> upstreamAuth = new ConcurrentHashMap<>();

  static HttpServer identity;
  static HttpServer catalogUpstream;
  static HttpServer platformUpstream;
  static HttpServer warehouseUpstream;

  /** warehouse 上游的契约（可变，用来证明「目录跟着 spec 变」）。 */
  static volatile String warehouseSpec;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  // ================================================================ 契约夹具

  /** 商品主数据服务的契约：4 个操作 / 3 条路径 / 1 个业务域（catalog）。 */
  static final String CATALOG_SPEC =
      """
      {
        "openapi": "3.0.1",
        "info": {"title": "catalog 服务", "version": "0.1.0"},
        "paths": {
          "/catalog/spus": {
            "get": {
              "summary": "SPU 列表",
              "description": "分页列出 SPU 档案",
              "tags": ["商品主数据"],
              "parameters": [
                {
                  "name": "keyword",
                  "in": "query",
                  "required": false,
                  "description": "按编码或名称模糊匹配",
                  "schema": {"type": "string"}
                },
                {
                  "name": "page",
                  "in": "query",
                  "required": false,
                  "description": "页码",
                  "schema": {"type": "integer", "default": 0}
                }
              ],
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": {"$ref": "#/components/schemas/SpuPage"}
                    }
                  }
                },
                "404": {"description": "没有这样的 SPU"}
              }
            },
            "post": {
              "summary": "登记 SPU",
              "requestBody": {
                "required": true,
                "content": {
                  "application/json": {
                    "schema": {"$ref": "#/components/schemas/SpuInput"}
                  }
                }
              },
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": {"$ref": "#/components/schemas/Spu"}
                    }
                  }
                }
              }
            }
          },
          "/catalog/spus/{spuId}": {
            "get": {
              "summary": "SPU 详情",
              "tags": ["商品主数据"],
              "parameters": [
                {
                  "name": "spuId",
                  "in": "path",
                  "required": true,
                  "description": "SPU 主键",
                  "schema": {"type": "string", "format": "uuid"}
                }
              ],
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": {"$ref": "#/components/schemas/Spu"}
                    }
                  }
                }
              }
            }
          },
          "/catalog/orders": {
            "get": {
              "summary": "订单列表",
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": {"type": "array", "items": {"type": "object"}}
                    }
                  }
                }
              }
            }
          }
        },
        "components": {
          "schemas": {
            "SpuPage": {
              "type": "object",
              "properties": {
                "items": {"type": "array", "items": {"$ref": "#/components/schemas/Spu"}},
                "total": {"type": "integer"}
              },
              "required": ["items", "total"]
            },
            "Spu": {
              "type": "object",
              "properties": {
                "id": {"type": "string", "format": "uuid"},
                "code": {"type": "string"},
                "price": {"type": "number"}
              },
              "required": ["id", "code"]
            },
            "SpuInput": {
              "type": "object",
              "properties": {
                "code": {"type": "string"},
                "name": {"type": "string"}
              },
              "required": ["code"]
            }
          }
        }
      }
      """;

  /** 开放平台自己的契约：1 个操作，带一个**必填**查询参数。 */
  static final String PLATFORM_SPEC =
      """
      {
        "openapi": "3.0.1",
        "info": {"title": "platform 服务", "version": "0.1.0"},
        "paths": {
          "/platform/apps": {
            "get": {
              "summary": "应用列表",
              "parameters": [
                {"name": "appType", "in": "query", "required": true,
                 "description": "应用类型", "schema": {"type": "string", "enum": ["INTERNAL", "THIRD_PARTY"]}}
              ],
              "responses": {"200": {"description": "OK"}}
            }
          }
        }
      }
      """;

  /** 仓储服务的最小契约（可变）。 */
  static final String WAREHOUSE_SPEC_MINIMAL =
      """
      {
        "openapi": "3.0.1",
        "info": {"title": "warehouse 服务", "version": "0.1.0"},
        "paths": {
          "/warehouse/warehouses": {
            "get": {"summary": "仓库列表", "responses": {"200": {"description": "OK"}}}
          }
        }
      }
      """;

  /** 加上一个接口后的契约（用来证明目录跟着 spec 变）。 */
  static final String WAREHOUSE_SPEC_EXTRA =
      """
      {
        "openapi": "3.0.1",
        "info": {"title": "warehouse 服务", "version": "0.1.0"},
        "paths": {
          "/warehouse/warehouses": {
            "get": {"summary": "仓库列表", "responses": {"200": {"description": "OK"}}}
          },
          "/warehouse/locations": {
            "get": {"summary": "库位列表", "responses": {"200": {"description": "OK"}}}
          }
        }
      }
      """;

  // ================================================================ 启动

  static {
    pg.start();
    warehouseSpec = WAREHOUSE_SPEC_MINIMAL;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of("platform.api-catalog.read", "platform.api-catalog.test");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    // 只有读权限（没有沙箱测试权限）⇒ 沙箱端点必须 403。
    var readOnly = List.of("platform.api-catalog.read");
    actors.put(
        "session-readonly",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            readOnly,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", readOnly))));
    // 完全没有本模块权限 ⇒ 403。
    var none = List.of("platform.app.read");
    actors.put(
        "session-noperm",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            none,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", none))));
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      catalogUpstream = specServer(CATALOG_SPEC);
      platformUpstream = specServer(PLATFORM_SPEC);
      warehouseUpstream = specServer(null);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 起一个「上游服务」：{@code /v3/api-docs} 吐契约，业务路径吐**真响应**。
   *
   * @param spec 该服务的契约（null = 每次都取当前的可变契约）
   */
  static HttpServer specServer(String spec) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v3/api-docs",
        e -> {
          var body = (spec != null ? spec : warehouseSpec).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.createContext(
        "/",
        e -> {
          var path = e.getRequestURI().getPath();
          upstreamCalls.add(e.getRequestMethod() + " " + e.getRequestURI());
          upstreamAuth.put(e.getRequestMethod() + " " + path, e.getRequestHeaders().getFirst("Authorization"));
          int status;
          String payload;
          if (path.equals("/catalog/spus")) {
            status = 200;
            payload = "{\"items\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"code\":\"SKU-REAL\"}],\"total\":1}";
          } else if (path.startsWith("/catalog/spus/")) {
            status = 200;
            payload = "{\"id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"code\":\"SKU-REAL\"}";
          } else if (path.equals("/catalog/orders")) {
            status = 404;
            payload = "{\"detail\":\"没有这样的订单\"}";
          } else {
            status = 404;
            payload = "{\"detail\":\"上游没有这个路径\"}";
          }
          var body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.getResponseHeaders().add("X-Upstream-Marker", "real-upstream");
          e.sendResponseHeaders(status, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.setExecutor(Executors.newFixedThreadPool(2));
    server.start();
    return server;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // TTL 压到 1 秒：「目录跟着 spec 变」那个用例要等它过期。
    r.add("platform.api-catalog.spec-ttl-seconds", () -> 1);
    // ⚠️ **九个服务逐个覆盖**：application.yml 里那九行是生产默认（端口 8081–8089），
    //   本测试里那些端口没人监听 ⇒ 目录会多出六个「读不到」的服务，计数就不确定了。
    //   逐个覆盖 ⇒ 登记表完全由本测试说了算（八个可读 + 一个故意不可达）。
    r.add("platform.api-catalog.services.catalog", () -> url(catalogUpstream));
    r.add("platform.api-catalog.services.platform", () -> url(platformUpstream));
    r.add("platform.api-catalog.services.warehouse", () -> url(warehouseUpstream));
    r.add("platform.api-catalog.services.identity", () -> url(platformUpstream));
    r.add("platform.api-catalog.services.channel", () -> url(platformUpstream));
    r.add("platform.api-catalog.services.trade", () -> url(platformUpstream));
    r.add("platform.api-catalog.services.crm", () -> url(platformUpstream));
    r.add("platform.api-catalog.services.reporting", () -> url(platformUpstream));
    // 故意指向一个没人监听的端口 ⇒ 这一格必须显式标成「读不到」。
    r.add("platform.api-catalog.services.finance", () -> "http://127.0.0.1:1");
  }

  static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  @BeforeEach
  void reset() throws Exception {
    upstreamCalls.clear();
    upstreamAuth.clear();
    warehouseSpec = WAREHOUSE_SPEC_MINIMAL;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app, platform_api_sandbox_run restart identity cascade");
    }
  }

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  // ================================================================ 工具

  private ApiCatalogInterfacePage interfaces(String query) throws Exception {
    return json.readValue(
        http.perform(get("/platform/api-catalog/interfaces" + query).header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        ApiCatalogInterfacePage.class);
  }

  private ApiCatalogSandboxResult runSandbox(String body) throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), ApiCatalogSandboxResult.class);
  }

  /**
   * 把后端拒绝的**原因**挖出来。
   *
   * <p>⚠️ MockMvc 里 {@code ResponseStatusException} 走的是 {@code sendError}：
   *   响应体是**空的**（真实容器里会由 /error 派发补上，MockMvc 不会）。
   *   所以断言「消息里写了什么」必须看被抛出的异常，而不是看响应体。
   */
  static String reasonOf(org.springframework.test.web.servlet.MvcResult result) {
    var ex = result.getResolvedException();
    if (ex == null) return "";
    var message = ex.getMessage();
    return message == null ? ex.getClass().getSimpleName() : message;
  }

  // ================================================================ 目录来自 spec

  @Test
  void 接口清单真从上游spec解析而不是写死() throws Exception {
    var page = interfaces("");
    var catalogItems =
        page.items().stream().filter(i -> i.domain().equals("catalog")).toList();
    assertThat(catalogItems).hasSize(4);
    assertThat(catalogItems.stream().map(ApiCatalogInterface::path))
        .containsExactlyInAnyOrder(
            "/catalog/spus", "/catalog/spus", "/catalog/spus/{spuId}", "/catalog/orders");
    assertThat(catalogItems.stream().filter(i -> i.method().equals("POST")).count()).isEqualTo(1);
    // 业务域 = 对外路径首段。
    assertThat(page.domains().stream().map(ApiCatalogDomain::code)).contains("catalog", "platform");
  }

  @Test
  void 目录会跟着上游spec变_写死的清单过不了这一关() throws Exception {
    var before = interfaces("?domain=warehouse");
    assertThat(before.items()).hasSize(1);

    warehouseSpec = WAREHOUSE_SPEC_EXTRA;
    Thread.sleep(1200); // 等 TTL（1 秒）过期。

    var after = interfaces("?domain=warehouse");
    assertThat(after.items())
        .as("上游 spec 多了一个接口，目录必须跟着多一个")
        .hasSize(2);
    assertThat(after.items().stream().map(ApiCatalogInterface::path))
        .contains("/warehouse/locations");
  }

  @Test
  void 读不到的服务显式标出来而不是悄悄消失() throws Exception {
    var page = interfaces("");
    var finance =
        page.services().stream().filter(s -> s.service().equals("finance")).findFirst().orElse(null);
    assertThat(finance).as("读不到的服务必须在列表里").isNotNull();
    assertThat(finance.available()).isFalse();
    assertThat(finance.unavailableReason()).isNotBlank();
    assertThat(finance.interfaceCount()).isZero();
  }

  @Test
  void 接口清单是真分页真筛选真搜索() throws Exception {
    var all = interfaces("");
    var catalogTotal =
        all.items().stream().filter(i -> i.domain().equals("catalog")).count();
    assertThat(catalogTotal).isEqualTo(4);

    var filtered = interfaces("?domain=catalog&method=GET");
    assertThat(filtered.items()).hasSize(3);
    assertThat(filtered.items().stream().allMatch(i -> i.method().equals("GET"))).isTrue();

    var searched = interfaces("?keyword=SPU");
    assertThat(searched.items()).isNotEmpty();
    assertThat(searched.items().stream().allMatch(i -> i.path().contains("spus")
            || i.summary().contains("SPU")
            || i.description().contains("SPU")))
        .isTrue();

    var paged = interfaces("?domain=catalog&size=2");
    assertThat(paged.items()).hasSize(2);
    assertThat(paged.size()).isEqualTo(2);
    assertThat(paged.page()).isZero();
    var paged2 = interfaces("?domain=catalog&size=2&page=1");
    assertThat(paged2.items()).hasSize(2);
    assertThat(paged2.items()).doesNotContainAnyElementsOf(paged.items());
  }

  // ================================================================ 接口详情

  @Test
  void 接口详情给出参数返回限流说明与示例() throws Exception {
    var result =
        http.perform(
                get("/platform/api-catalog/interfaces/detail")
                    .header("Authorization", "session-a")
                    .param("service", "catalog")
                    .param("method", "GET")
                    .param("path", "/catalog/spus"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).as(reasonOf(result)).isEqualTo(200);
    var detail = json.readValue(result.getResponse().getContentAsString(), ApiCatalogInterfaceDetail.class);

    // 参数来自 spec 的 parameters（path / query / header 全取）。
    assertThat(detail.parameters()).extracting(ApiCatalogParameter::name)
        .contains("keyword", "page");
    assertThat(detail.parameters()).extracting(ApiCatalogParameter::location)
        .contains("query");

    // 返回来自 spec 的 responses。
    assertThat(detail.responses()).extracting(ApiCatalogResponse::statusCode)
        .contains("200", "404");
    var ok = detail.responses().stream().filter(r -> r.statusCode().equals("200")).findFirst().orElseThrow();
    assertThat(ok.fields()).as("详情原文：" + json.writeValueAsString(detail))
        .extracting(ApiCatalogField::name).contains("items", "total");

    // 示例：spec 没给官方示例时按 schema 生成，并**标明出处**。
    assertThat(detail.responseExample().source()).isEqualTo("GENERATED_FROM_SCHEMA");
    assertThat(detail.responseExample().json()).contains("items").contains("total");

    // 限流说明：口径写在上面，且如实说明本租户没有授权到这个域的应用。
    assertThat(detail.rateLimit().policy()).contains("按**应用**判定");
    assertThat(detail.rateLimit().appQuotas()).isEmpty();
    assertThat(detail.rateLimit().note()).contains("没有应用被授权到这个业务域");
  }

  @Test
  void 接口不存在时如实报404() throws Exception {
    http.perform(
            get("/platform/api-catalog/interfaces/detail")
                .header("Authorization", "session-a")
                .param("service", "catalog")
                .param("method", "GET")
                .param("path", "/catalog/not-a-real-path"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 限流说明读的是18_1_1_1的真实配额与授权() throws Exception {
    // 造一个应用：授权到 catalog 域 + 配一个窗口的配额。
    var appId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    inTenant(
        () -> {
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,status,"
                  + " created_at,updated_at) values(?,?,?,'配额应用','THIRD_PARTY','','ACTIVE',"
                  + " ?::timestamptz,?::timestamptz)",
              appId,
              tenant(),
              "quota-app",
              now,
              now);
          sql.execute(
              "insert into platform_app_scope(id,tenant_id,app_id,resource_code,action_code,granted_at)"
                  + " values(?,?,?,'catalog','READ',?::timestamptz)",
              UUID.randomUUID(),
              tenant(),
              appId,
              now);
          sql.execute(
              "insert into platform_app_quota(id,tenant_id,app_id,window_type,limit_count,enabled,"
                  + " created_at,updated_at) values(?,?,?,'MINUTE',120,true,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant(),
              appId,
              now,
              now);
          return null;
        });

    var result2 =
        http.perform(
                get("/platform/api-catalog/interfaces/detail")
                    .header("Authorization", "session-a")
                    .param("service", "catalog")
                    .param("method", "GET")
                    .param("path", "/catalog/spus"))
            .andReturn();
    assertThat(result2.getResponse().getStatus()).as(reasonOf(result2)).isEqualTo(200);
    var detail =
        json.readValue(result2.getResponse().getContentAsString(), ApiCatalogInterfaceDetail.class);

    assertThat(detail.rateLimit().appQuotas()).hasSize(1);
    var quota = detail.rateLimit().appQuotas().get(0);
    assertThat(quota.appCode()).isEqualTo("quota-app");
    assertThat(quota.grantedActions()).containsExactly("READ");
    assertThat(quota.windows()).hasSize(1);
    assertThat(quota.windows().get(0).windowType()).isEqualTo("MINUTE");
    // 数量是 BigDecimal（禁浮点）。
    assertThat(quota.windows().get(0).limitCount()).isEqualByComparingTo("120");
    assertThat(detail.rateLimit().grantedResourceCodes()).contains("catalog");
  }

  // ================================================================ 沙箱

  @Test
  void 沙箱真发一次请求并回真实响应() throws Exception {
    var result = runSandbox(
        """
        {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/spus",
         "query":{"keyword":"SKU-REAL"}}
        """);
    assertThat(result.outcome()).isEqualTo("COMPLETED");
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.bodySummary()).contains("SKU-REAL").contains("total");
    assertThat(result.responseBytes()).isPositive();
    assertThat(result.contentType()).contains("application/json");

    // 上游**真的**收到了这一次请求（带调用方自己的会话）。
    assertThat(upstreamCalls).contains("GET /catalog/spus?keyword=SKU-REAL");
    assertThat(upstreamAuth.get("GET /catalog/spus")).isEqualTo("session-a");

    // 留痕落库（租户归属 = 会话租户）。
    var row =
        inTenant(
            () ->
                sql.fetchOne(
                    "select target_service, status_code, outcome, tenant_id from platform_api_sandbox_run"));
    assertThat(row).isNotNull();
    assertThat(row.get("target_service", String.class)).isEqualTo("catalog");
    assertThat(row.get("status_code", Integer.class)).isEqualTo(200);
    assertThat(row.get("outcome", String.class)).isEqualTo("COMPLETED");
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(tenant());
  }

  @Test
  void 上游真的回404时沙箱如实带回那个状态码() throws Exception {
    var result = runSandbox(
        """
        {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/orders"}
        """);
    assertThat(result.outcome()).isEqualTo("COMPLETED");
    assertThat(result.statusCode()).isEqualTo(404);
    assertThat(result.bodySummary()).contains("没有这样的订单");
  }

  @Test
  void 沙箱拒发写请求且一次都不发出去() throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"catalog","httpMethod":"POST","targetPath":"/catalog/spus",
                         "body":"{}"}
                        """))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(409);
    var result = json.readValue(response.getContentAsString(), ApiCatalogSandboxResult.class);
    assertThat(result.outcome()).isEqualTo("REFUSED_WRITE");
    assertThat(result.statusCode()).isNull();
    assertThat(result.message()).contains("沙箱只放行读请求");

    // 🔴 上游**一次都没被写过**。
    assertThat(upstreamCalls).noneMatch(call -> call.startsWith("POST"));
    // 但「被拒」这件事本身留痕了。
    var rows = inTenant(() -> sql.fetch("select outcome from platform_api_sandbox_run"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("outcome", String.class)).isEqualTo("REFUSED_WRITE");
  }

  @Test
  void 沙箱拒绝不在spec里的路径() throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/not-in-spec"}
                        """))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    var result = json.readValue(response.getContentAsString(), ApiCatalogSandboxResult.class);
    assertThat(result.outcome()).isEqualTo("INVALID_TARGET");
    assertThat(result.message()).contains("不在该服务的接口清单里");
    assertThat(upstreamCalls).noneMatch(call -> call.contains("not-in-spec"));
  }

  @Test
  void 沙箱拒绝spec里没声明的查询参数() throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/spus",
                         "query":{"not-declared":"1"}}
                        """))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    var result = json.readValue(response.getContentAsString(), ApiCatalogSandboxResult.class);
    assertThat(result.message()).contains("not-declared");
  }

  @Test
  void 沙箱要求必填查询参数齐全() throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"platform","httpMethod":"GET","targetPath":"/platform/apps"}
                        """))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    var result = json.readValue(response.getContentAsString(), ApiCatalogSandboxResult.class);
    assertThat(result.message()).contains("appType");
  }

  @Test
  void 沙箱目标服务不在册时如实报400() throws Exception {
    var result =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"not-registered","httpMethod":"GET","targetPath":"/whatever"}
                        """))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(reasonOf(result)).contains("不在册");
  }

  @Test
  void 沙箱目标服务读不到spec时如实报503() throws Exception {
    var result =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"finance","httpMethod":"GET","targetPath":"/whatever"}
                        """))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(reasonOf(result)).contains("读不到");
  }

  @Test
  void 沙箱历史是真分页() throws Exception {
    runSandbox(
        """
        {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/spus"}
        """);
    runSandbox(
        """
        {"targetService":"platform","httpMethod":"GET","targetPath":"/platform/apps",
         "query":{"appType":"INTERNAL"}}
        """);

    var page =
        json.readValue(
            http.perform(
                    get("/platform/api-catalog/sandbox/runs")
                        .header("Authorization", "session-a")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogSandboxRunPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.completedCount()).isEqualTo(2);

    var filtered =
        json.readValue(
            http.perform(
                    get("/platform/api-catalog/sandbox/runs")
                        .header("Authorization", "session-a")
                        .param("service", "platform"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogSandboxRunPage.class);
    assertThat(filtered.items()).hasSize(1);
    assertThat(filtered.items().get(0).targetService()).isEqualTo("platform");
  }

  @Test
  void 沙箱目标清单如实标出哪些服务读不到() throws Exception {
    var targets =
        json.readValue(
            http.perform(get("/platform/api-catalog/sandbox/targets").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<ApiCatalogSandboxTarget>>() {});
    var finance =
        targets.stream().filter(t -> t.service().equals("finance")).findFirst().orElseThrow();
    assertThat(finance.available()).isFalse();
    assertThat(finance.unavailableReason()).isNotBlank();
    var catalog =
        targets.stream().filter(t -> t.service().equals("catalog")).findFirst().orElseThrow();
    assertThat(catalog.available()).isTrue();
    assertThat(catalog.interfaceCount()).isEqualTo(4);
    assertThat(catalog.testableInterfaceCount()).isEqualTo(3);
  }

  // ================================================================ 鉴权与能力声明

  @Test
  void 没有会话是401() throws Exception {
    http.perform(get("/platform/api-catalog/interfaces")).andExpect(status().isUnauthorized());
  }

  @Test
  void 没有本模块权限是403() throws Exception {
    http.perform(get("/platform/api-catalog/interfaces").header("Authorization", "session-noperm"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 没有沙箱权限不能跑沙箱() throws Exception {
    http.perform(
            post("/platform/api-catalog/sandbox/runs")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"targetService":"catalog","httpMethod":"GET","targetPath":"/catalog/spus"}
                    """))
        .andExpect(status().isForbidden());
    assertThat(upstreamCalls).as("没权限就一次都不该发出去").isEmpty();
  }

  @Test
  void 能力声明把沙箱写请求如实答成没有() throws Exception {
    var capabilities =
        json.readValue(
            http.perform(get("/platform/api-catalog/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogCapabilities.class);
    var write =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("写请求"))
            .findFirst()
            .orElseThrow();
    assertThat(write.available()).isFalse();
    assertThat(write.ownerModule()).isEqualTo("18.1");
    assertThat(write.impact()).contains("409");
    assertThat(capabilities.downstreamGaps().stream().anyMatch(g -> g.module().equals("18.1.1.3")))
        .isTrue();
  }

  @Test
  void 概览的数是真数() throws Exception {
    var overview =
        json.readValue(
            http.perform(get("/platform/api-catalog/overview").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogOverview.class);
    assertThat(overview.serviceCount()).as("登记在册的服务 = 生产默认的九个").isEqualTo(9);
    assertThat(overview.availableServiceCount()).isEqualTo(8);
    assertThat(overview.unavailableServiceCount()).isEqualTo(1);
    assertThat(overview.domainCount()).isGreaterThanOrEqualTo(3);
    assertThat(overview.interfaceCount()).isGreaterThanOrEqualTo(10);
    assertThat(overview.sandboxTestableCount()).isGreaterThan(0);
  }
}
