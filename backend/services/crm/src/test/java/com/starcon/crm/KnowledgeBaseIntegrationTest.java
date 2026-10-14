package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 15.4.2.1 客服知识库的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的**真实 PostgreSQL 16 + FORCE RLS**，租户断言在 {@link CrmTenancyIntegrationTest}
 * 里（同一套夹具思路），本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>半开区间 {@code [from, to)}（规则 R1）</b>：在终点那一刻<b>旧版本必须已经失效</b>，
 *       在起点那一刻新版本<b>必须已经生效</b>。用两个相邻区间在同一个时刻各查一次， 断言命中的版本号正好换人 —— 这是整条时间语义的验收点。
 *   <li><b>时区不静默偏移（硬性要求 6）</b>：用 {@code +08:00} 的偏移量写入， 再<b>断言写回的值</b>等于同一个绝对时刻（而不是偏了 −28800
 *       秒的东八区墙钟）。 缺偏移量的输入直接 400。
 *   <li><b>重叠一律拒绝（规则 R2 / R3）</b>：草稿区间随便重叠，<b>发布那一刻</b>才被拒。
 *   <li><b>已发布版本只能向前关闭（规则 R5）</b>：关回 null、往后延、关到过去，三条都拒绝。
 *   <li><b>「平台改版 → 定位过期条目」</b>（蓝图粗体句）：改版时刻之前写下、且至今仍无 接替版本的条目要被列出；已经被接替过的、别的平台的，都要<b>不</b>被列出。
 *   <li><b>引用记录真实落库</b>：谁、什么时候、引用了哪一版都要能查回来； 引用行<b>不可改不可删</b>（否则「没人用过」可以被做出来）。
 *   <li><b>按店铺/类目/语言组织</b>：三个维度都是真过滤，不是摆设。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeBaseIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shop = UUID.randomUUID();
  static final UUID category = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 固定的判定时刻，避免测试依赖「此刻」。 */
  static final String V1_FROM = "2026-01-01T00:00:00Z";

  static final String HANDOFF = "2026-06-01T00:00:00Z";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.knowledge.read", "crm.knowledge.update");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 每个用例前清空三张表。
   *
   * <p>本类共享同一个容器与同一个库且<b>不回滚</b>，所以「三个组织维度」建出来的 {@code scope-en} 会留在库里，让别的用例的 {@code
   * containsExactly} 与 {@code hasSize(1)} 变成永远失败 —— 那是夹具的脏，不是业务被破坏。 用迁移用户（超管）连接 TRUNCATE：RLS
   * 对超管不生效。
   */
  @BeforeEach
  void resetDatabase() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_knowledge_reference, crm_knowledge_version,"
              + " crm_knowledge_entry restart identity cascade");
    }
  }

  // ------------------------------------------------------------------ HTTP 小工具

  private MockHttpResponse postJson(String path, String body) throws Exception {
    var response =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse();
    return new MockHttpResponse(response.getStatus(), response.getContentAsString());
  }

  private MockHttpResponse getJson(String path) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session")).andReturn().getResponse();
    return new MockHttpResponse(response.getStatus(), response.getContentAsString());
  }

  private record MockHttpResponse(int status, String body) {}

  private String entryJson(String topicKey, String platform, String locale) {
    return """
        {"topicKey":"%s","title":"标题 %s","shopId":"%s","shopName":"店铺",
         "categoryId":"%s","categoryName":"类目","locale":"%s","platformCode":"%s",
         "topicType":"FAQ"}
        """
        .formatted(topicKey, topicKey, shop, category, locale, platform);
  }

  /** 建档案 → 建草稿版本 → 发布，返回版本 id。 */
  private String publishedVersion(
      String topicKey, String platform, String versionNo, String from, String to) throws Exception {
    var entry = postJson("/crm/knowledge/entries", entryJson(topicKey, platform, "zh-CN"));
    assertThat(entry.status()).as("建档案：%s", entry.body()).isEqualTo(200);
    String entryId = json.readTree(entry.body()).get("id").asText();

    String body =
        """
        {"versionNo":%s,"question":"问题 %s","answer":"答法 %s",
         "effectiveFrom":"%s","effectiveTo":%s}
        """
            .formatted(versionNo, topicKey, topicKey, from, to == null ? "null" : "\"" + to + "\"");
    var draft = postJson("/crm/knowledge/entries/" + entryId + "/versions", body);
    assertThat(draft.status()).as("建草稿：%s", draft.body()).isEqualTo(200);
    String versionId = json.readTree(draft.body()).get("id").asText();

    var published = postJson("/crm/knowledge/versions/" + versionId + "/publish", "{}");
    assertThat(published.status()).as("发布：%s", published.body()).isEqualTo(200);
    return versionId;
  }

  private String entryIdOf(String topicKey) throws Exception {
    var found = getJson("/crm/knowledge/entries");
    for (var node : json.readTree(found.body())) {
      if (node.get("topicKey").asText().equals(topicKey)) return node.get("id").asText();
    }
    throw new AssertionError("档案 " + topicKey + " 不在列表里");
  }

  /** 读某格子在 at 时刻生效的版本号（null = 无生效版本）。 */
  private Integer effectiveVersionNo(String topicKey, String at) throws Exception {
    var response =
        getJson(
            "/crm/knowledge/entries/effective?shopId="
                + shop
                + "&categoryId="
                + category
                + "&locale=zh-CN&at="
                + at);
    assertThat(response.status()).as("effective 查询：%s", response.body()).isEqualTo(200);
    for (var node : json.readTree(response.body())) {
      if (node.get("topicKey").asText().equals(topicKey)) {
        return node.get("effectiveVersion").get("versionNo").asInt();
      }
    }
    return null;
  }

  // ==========================================================================
  // 规则 R1：半开区间 [from, to)
  // ==========================================================================

  /** 半开区间的**边界**才是验收点：区间内部的点谁都能猜对，边界上差一毫秒就会让 客服在换版那一瞬间答错。 */
  @Test
  void 半开区间_终点那一刻旧版本失效起点那一刻新版本生效() throws Exception {
    // 同一条知识上先发 v1（[2026-01-01, 2026-06-01)），再发 v2（[2026-06-01, +∞)）。
    publishedVersion("half-open", "AMAZON", "1", V1_FROM, HANDOFF);
    String entryId = entryIdOf("half-open");
    var draft =
        postJson(
            "/crm/knowledge/entries/" + entryId + "/versions",
            """
            {"versionNo":2,"question":"问题 v2","answer":"新答法","effectiveFrom":"%s"}
            """
                .formatted(HANDOFF));
    String versionId = json.readTree(draft.body()).get("id").asText();
    postJson("/crm/knowledge/versions/" + versionId + "/publish", "{}");

    // 交班前：v1 生效。
    assertThat(effectiveVersionNo("half-open", "2026-05-31T23:59:59Z"))
        .as("交接前必须命中 v1")
        .isEqualTo(1);
    // **恰好在交班那一刻**：半开区间右端不含 —— 命中 v2，不是 v1。
    assertThat(effectiveVersionNo("half-open", HANDOFF))
        .as("半开区间 [from,to)：在 to 那一刻 v1 已失效")
        .isEqualTo(2);
    // 交班后：仍然是 v2。
    assertThat(effectiveVersionNo("half-open", "2026-07-01T00:00:00Z")).isEqualTo(2);

    // 往回退到交班前一瞬间：v2 的生效起点尚未到，命中 v1 —— 同一批数据、两个时刻、
    // 两个不同答案，这正是「某一时刻该用哪一版」的意义。
    assertThat(effectiveVersionNo("half-open", "2026-05-31T23:59:59Z")).isEqualTo(1);
  }

  /** to 为 NULL = +∞：不设终点的版本永远生效，直到有人接替它。 */
  @Test
  void 生效终点为空等于正无穷() throws Exception {
    publishedVersion("open-ended", "AMAZON", "1", V1_FROM, null);
    assertThat(effectiveVersionNo("open-ended", "2030-01-01T00:00:00Z"))
        .as("to 为空 = 一直有效")
        .isEqualTo(1);
  }

  // ==========================================================================
  // 硬性要求 6：timestamptz 往返不静默偏 −28800s
  // ==========================================================================

  /**
   * 用东八区偏移量写入，再<b>断言写回的值</b>是同一个绝对时刻。
   *
   * <p>如果任何一层用 {@code Timestamp.valueOf(LocalDateTime)} 解析，这里会偏 −28800 秒 —— 页面看上去一切正常，只是所有生效时间都错了 8
   * 小时。
   */
  @Test
  void 生效时间带偏移量写入后往返不变() throws Exception {
    // 2026-06-01T08:00:00+08:00 就是 2026-06-01T00:00:00Z（同一绝对时刻）。
    String versionId =
        publishedVersion("tz-round-trip", "AMAZON", "1", "2026-06-01T08:00:00+08:00", null);

    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select effective_from from crm_knowledge_version"
                            + " where tenant_id = ? and id = ?",
                        tenant,
                        UUID.fromString(versionId))
                    .get("effective_from", OffsetDateTime.class));

    assertThat(stored.toInstant())
        .as("带 +08:00 的偏移量写入后必须等于同一绝对时刻，不能被当成东八区墙钟再偏一次")
        .isEqualTo(OffsetDateTime.parse("2026-06-01T00:00:00Z").toInstant());

    // 读接口回来的字符串也必须是同一个绝对时刻。
    var read = getJson("/crm/knowledge/entries/" + entryIdOf("tz-round-trip"));
    String effectiveFrom =
        json.readTree(read.body()).get("versions").get(0).get("effectiveFrom").asText();
    assertThat(OffsetDateTime.parse(effectiveFrom).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-06-01T00:00:00Z").toInstant());
  }

  /** 缺偏移量的输入必须被拒，而不是猜一个默认时区。 */
  @Test
  void 缺偏移量的时间被拒绝而不是猜时区() throws Exception {
    var entry = postJson("/crm/knowledge/entries", entryJson("no-offset", "AMAZON", "zh-CN"));
    String entryId = json.readTree(entry.body()).get("id").asText();
    var response =
        postJson(
            "/crm/knowledge/entries/" + entryId + "/versions",
            """
            {"versionNo":1,"question":"q","answer":"a","effectiveFrom":"2026-06-01T00:00:00"}
            """);
    assertThat(response.status()).as("缺偏移量会被当成某个默认时区猜，静默偏 8 小时").isEqualTo(400);
  }

  // ==========================================================================
  // 规则 R2 / R3：重叠一律拒绝，只有发布那一刻才检查
  // ==========================================================================

  @Test
  void 草稿区间可以重叠但发布时必须被拒() throws Exception {
    publishedVersion("overlap", "AMAZON", "1", V1_FROM, null);

    var entry = postJson("/crm/knowledge/entries", entryJson("overlap", "AMAZON", "zh-CN"));
    assertThat(entry.status()).isEqualTo(409);

    String entryId = entryIdOf("overlap");
    // 草稿与已发布区间完全重叠 —— **建草稿时允许**（规则 R3）。
    var draft =
        postJson(
            "/crm/knowledge/entries/" + entryId + "/versions",
            """
            {"versionNo":2,"question":"q2","answer":"a2","effectiveFrom":"2026-03-01T00:00:00Z"}
            """);
    assertThat(draft.status()).as("草稿区间随便重叠（规则 R3）").isEqualTo(200);
    String versionId = json.readTree(draft.body()).get("id").asText();

    // **发布那一刻**必须被拒（规则 R2）：同一时刻不能有两个生效版本。
    var published = postJson("/crm/knowledge/versions/" + versionId + "/publish", "{}");
    assertThat(published.status()).as("重叠区间在发布那一刻必须被拒，不能自动裁决").isEqualTo(409);

    // 旧版本仍然完好——拒的是新版本，不是把旧的也弄坏。
    assertThat(effectiveVersionNo("overlap", "2026-04-01T00:00:00Z")).isEqualTo(1);
  }

  /** 相邻不重叠的区间（[a,b) 与 [b,c)）必须能发布 —— 半开区间就是为了这个。 */
  @Test
  void 相邻区间可以依次发布() throws Exception {
    publishedVersion("adjacent", "AMAZON", "1", V1_FROM, HANDOFF);
    // 复用 publishedVersion 建好的档案（同一个格子同一业务键只能有一条 —— 再建一次是 409）。
    String entryId = entryIdOf("adjacent");
    var draft =
        postJson(
            "/crm/knowledge/entries/" + entryId + "/versions",
            """
            {"versionNo":2,"question":"q2","answer":"a2","effectiveFrom":"%s"}
            """
                .formatted(HANDOFF));
    String versionId = json.readTree(draft.body()).get("id").asText();
    assertThat(postJson("/crm/knowledge/versions/" + versionId + "/publish", "{}").status())
        .as("[a,b) 与 [b,c) 在 b 处不重叠，必须能发布")
        .isEqualTo(200);
  }

  // ==========================================================================
  // 规则 R5：已发布版本只能向前关闭
  // ==========================================================================

  @Test
  void 已发布版本只能向前关闭不能重开或延后() throws Exception {
    String versionId = publishedVersion("close-only", "AMAZON", "1", V1_FROM, null);
    String base = "/crm/knowledge/versions/" + versionId + "/close";

    // 关回 null = 重开：拒。
    assertThat(postJson(base, "{\"effectiveTo\":null}").status())
        .as("重开会让接替它的版本与它同时生效")
        .isEqualTo(400);

    // 关到已经过去的时刻：拒。
    assertThat(postJson(base, "{\"effectiveTo\":\"2020-01-01T00:00:00Z\"}").status())
        .as("关到过去会追溯改写历史")
        .isEqualTo(400);

    // 往前关到仍在未来的时刻：允许。
    assertThat(postJson(base, "{\"effectiveTo\":\"2099-01-01T00:00:00Z\"}").status())
        .as("向前关闭是已发布版本上唯一合法的写操作")
        .isEqualTo(200);

    // 往后延（比当前终点更晚）：拒。
    assertThat(postJson(base, "{\"effectiveTo\":\"2099-06-01T00:00:00Z\"}").status())
        .as("往后延会与接替版本重叠")
        .isEqualTo(409);
  }

  /** 已发布版本的正文不可改（服务层没有改版本的端点，这里直接绕到 DB 层验证触发器仍然拦得住）。 */
  @Test
  void 已发布版本的答法正文不可改() throws Exception {
    publishedVersion("immutable", "AMAZON", "1", V1_FROM, null);
    var message =
        tenants.run(
            tenant,
            () -> {
              var id =
                  sql.fetchOne(
                          "select v.id from crm_knowledge_version v"
                              + " join crm_knowledge_entry e"
                              + "   on e.tenant_id = v.tenant_id and e.id = v.entry_id"
                              + " where v.tenant_id = ? and v.status='PUBLISHED' and e.topic_key = ?",
                          tenant,
                          "immutable")
                      .get(0, UUID.class);
              try {
                sql.execute(
                    "update crm_knowledge_version set answer = '偷偷改掉的答法'"
                        + " where tenant_id = ? and id = ?",
                    tenant,
                    id);
                return "no-exception";
              } catch (Exception e) {
                return rootMessage(e);
              }
            });
    assertThat(message).as("已发布版本的正文必须由触发器挡住").contains("已发布的知识版本不能改答法正文");

    // 正文确实还是原来那句。
    var current = getJson("/crm/knowledge/entries/" + entryIdOf("immutable"));
    assertThat(json.readTree(current.body()).get("versions").get(0).get("answer").asText())
        .isEqualTo("答法 immutable");
  }

  // ==========================================================================
  // 「平台改版 → 定位过期条目」（蓝图粗体句）
  // ==========================================================================

  @Test
  void 平台改版后能定位待复核条目且不误报() throws Exception {
    // E1：改版前写下，至今仍是开放区间 → **待复核**。
    publishedVersion("stale-amazon", "AMAZON", "1", V1_FROM, null);

    // E2：同样写在改版前，但后来已经接替过一版 → **不该**出现在待复核里。
    // 接替必须「先向前关闭 v1，再发 v2」：v1 是开放区间（+∞），直接发 v2 会与它重叠
    // 而被规则 R2 拒绝 —— 这正是半开区间 + R5 组合起来的工作方式。
    String revisedV1 = publishedVersion("revised-amazon", "AMAZON", "1", V1_FROM, null);
    String revisedEntry = entryIdOf("revised-amazon");
    String futureHandoff = OffsetDateTime.now(ZoneOffset.UTC).plusDays(60).withNano(0).toString();
    assertThat(
            postJson(
                    "/crm/knowledge/versions/" + revisedV1 + "/close",
                    "{\"effectiveTo\":\"" + futureHandoff + "\"}")
                .status())
        .as("向前关闭 v1 是已发布版本上唯一合法的写操作")
        .isEqualTo(200);
    var v2 =
        postJson(
            "/crm/knowledge/entries/" + revisedEntry + "/versions",
            """
            {"versionNo":2,"question":"q2","answer":"按新政策写的答法","effectiveFrom":"%s"}
            """
                .formatted(futureHandoff));
    String v2Id = json.readTree(v2.body()).get("id").asText();
    assertThat(postJson("/crm/knowledge/versions/" + v2Id + "/publish", "{}").status())
        .as("关闭之后再发接替版不应重叠")
        .isEqualTo(200);

    // 另一个平台：同样开放区间，但查的是 AMAZON → **不该**出现。
    publishedVersion("stale-shopify", "SHOPIFY", "1", V1_FROM, null);

    var report =
        getJson("/crm/knowledge/review-due?platformCode=AMAZON&policyChangedAt=" + HANDOFF);
    assertThat(report.status()).as("review-due：%s", report.body()).isEqualTo(200);
    var node = json.readTree(report.body());

    var topicKeys = new java.util.ArrayList<String>();
    node.get("entries").forEach(n -> topicKeys.add(n.get("topicKey").asText()));

    assertThat(topicKeys).as("只应列出「当前生效版写于改版之前且仍无接替版本」的 AMAZON 条目").containsExactly("stale-amazon");
    assertThat(topicKeys).as("已经被接替过的条目不是过期内容").doesNotContain("revised-amazon");
    assertThat(topicKeys).as("别的平台不受本次改版影响").doesNotContain("stale-shopify");

    // 判定口径随结果一起返回，页面能自证它按什么查的。
    assertThat(node.get("criterion").asText())
        .contains("effective_from < policyChangedAt")
        .contains("effective_to");
    assertThat(node.get("platformCode").asText()).isEqualTo("AMAZON");

    // 换 SHOPIFY 查，命中的是另一条 —— 证明过滤真的按平台走。
    var shopify =
        getJson("/crm/knowledge/review-due?platformCode=SHOPIFY&policyChangedAt=" + HANDOFF);
    assertThat(json.readTree(shopify.body()).get("entries").get(0).get("topicKey").asText())
        .isEqualTo("stale-shopify");
  }

  // ==========================================================================
  // 引用记录：真实落库，不是内存计数
  // ==========================================================================

  @Test
  void 引用记录真实落库且不可改不可删() throws Exception {
    String versionId = publishedVersion("ref-target", "AMAZON", "1", V1_FROM, null);

    var recorded =
        postJson(
            "/crm/knowledge/versions/" + versionId + "/references",
            """
            {"consumer":"MANUAL_REPLY","sourceRef":"MSG-2026-0001","note":"回复买家退换货问题"}
            """);
    assertThat(recorded.status()).as("记录引用：%s", recorded.body()).isEqualTo(200);

    // 行确实在库里，且「谁 / 什么时候 / 哪一版」都对。
    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select referenced_by, referenced_at, consumer, source_ref"
                        + " from crm_knowledge_reference where tenant_id = ? and version_id = ?",
                    tenant,
                    UUID.fromString(versionId)));
    assertThat(rows).as("引用必须真的落库").hasSize(1);
    assertThat(rows.get(0).get("referenced_by", UUID.class))
        .as("引用人取自已认证成员，不接受客户端自称")
        .isEqualTo(userId);
    assertThat(rows.get(0).get("consumer", String.class)).isEqualTo("MANUAL_REPLY");
    assertThat(rows.get(0).get("source_ref", String.class)).isEqualTo("MSG-2026-0001");

    // 列表接口能查回来。
    var list = getJson("/crm/knowledge/versions/" + versionId + "/references");
    assertThat(list.status()).isEqualTo(200);
    assertThat(json.readTree(list.body()).get(0).get("versionNo").asInt()).isEqualTo(1);

    // 条目上的引用计数来自 DB（不是内存累加）。
    var entry = getJson("/crm/knowledge/entries/" + entryIdOf("ref-target"));
    assertThat(json.readTree(entry.body()).get("entry").get("referenceCount").asLong())
        .as("引用次数必须从库里数出来")
        .isEqualTo(1L);

    // 不可改。
    var updated =
        tenants.run(
            tenant,
            () -> {
              try {
                sql.execute(
                    "update crm_knowledge_reference set consumer='AUTO_REPLY'"
                        + " where tenant_id = ? and version_id = ?",
                    tenant,
                    UUID.fromString(versionId));
                return "no-exception";
              } catch (Exception e) {
                return rootMessage(e);
              }
            });
    assertThat(updated).as("引用行不可改").contains("知识引用记录不可修改或删除");

    // 不可删 —— 删掉就等于把「没人用过」这个答案做出来。
    var deleted =
        tenants.run(
            tenant,
            () -> {
              try {
                sql.execute(
                    "delete from crm_knowledge_reference where tenant_id = ? and version_id = ?",
                    tenant,
                    UUID.fromString(versionId));
                return "no-exception";
              } catch (Exception e) {
                return rootMessage(e);
              }
            });
    assertThat(deleted).as("引用行不可删").contains("知识引用记录不可修改或删除");
  }

  /** 草稿版本没生效过，引用它等于在引用一段从未给过买家的内容。 */
  @Test
  void 草稿版本不能被引用() throws Exception {
    var entry = postJson("/crm/knowledge/entries", entryJson("draft-only", "AMAZON", "zh-CN"));
    String entryId = json.readTree(entry.body()).get("id").asText();
    var draft =
        postJson(
            "/crm/knowledge/entries/" + entryId + "/versions",
            """
            {"versionNo":1,"question":"q","answer":"a","effectiveFrom":"%s"}
            """
                .formatted(V1_FROM));
    String versionId = json.readTree(draft.body()).get("id").asText();
    var recorded =
        postJson(
            "/crm/knowledge/versions/" + versionId + "/references",
            "{\"consumer\":\"MANUAL_REPLY\"}");
    assertThat(recorded.status()).as("草稿不能被引用").isEqualTo(400);
  }

  // ==========================================================================
  // 按店铺 / 类目 / 语言组织
  // ==========================================================================

  @Test
  void 三个组织维度都是真过滤() throws Exception {
    publishedVersion("scope-a", "AMAZON", "1", V1_FROM, null);
    publishedVersion("scope-b", "AMAZON", "1", V1_FROM, null);

    // 语言不同 → 不是同一条。
    var other = postJson("/crm/knowledge/entries", entryJson("scope-en", "AMAZON", "en-US"));
    assertThat(other.status()).isEqualTo(200);

    var zh =
        getJson(
            "/crm/knowledge/entries?shopId=" + shop + "&categoryId=" + category + "&locale=zh-CN");
    var zhKeys = new java.util.ArrayList<String>();
    json.readTree(zh.body()).forEach(n -> zhKeys.add(n.get("topicKey").asText()));
    assertThat(zhKeys).containsExactlyInAnyOrder("scope-a", "scope-b");

    var en =
        getJson(
            "/crm/knowledge/entries?shopId=" + shop + "&categoryId=" + category + "&locale=en-US");
    assertThat(json.readTree(en.body()).get(0).get("topicKey").asText()).isEqualTo("scope-en");

    // 换一个类目 → 中文那两条都不该出现（证明类目是真过滤而不是摆设）。
    var otherCategory =
        getJson(
            "/crm/knowledge/entries?shopId="
                + shop
                + "&categoryId="
                + UUID.randomUUID()
                + "&locale=zh-CN");
    assertThat(json.readTree(otherCategory.body()).size()).as("换一个类目就必须没有结果，类目维度不能是摆设").isZero();

    // 组织范围列表来自真实数据（页面上三个下拉框的数据源）。
    var scopes = getJson("/crm/knowledge/scopes");
    assertThat(scopes.status()).isEqualTo(200);
    var scopeNodes = json.readTree(scopes.body());
    assertThat(scopeNodes.size()).isGreaterThanOrEqualTo(2);
    assertThat(scopeNodes.get(0).get("shopId").asText()).isEqualTo(shop.toString());
    assertThat(scopeNodes.get(0).has("entryCount")).isTrue();
  }

  /** 同一个格子里的同一个业务键不能建两次。 */
  @Test
  void 同格子同业务键不能建两次() throws Exception {
    assertThat(postJson("/crm/knowledge/entries", entryJson("dup", "AMAZON", "zh-CN")).status())
        .isEqualTo(200);
    assertThat(postJson("/crm/knowledge/entries", entryJson("dup", "AMAZON", "zh-CN")).status())
        .as("一个格子只能有一条标准答法")
        .isEqualTo(409);
  }

  private static String rootMessage(Throwable e) {
    var current = e;
    while (current.getCause() != null) current = current.getCause();
    return current.getMessage() == null ? current.toString() : current.getMessage();
  }
}
