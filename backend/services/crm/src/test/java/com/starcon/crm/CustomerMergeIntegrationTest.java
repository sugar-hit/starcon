package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
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
 * 15.3.1.3 跨店合并的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>，上游 catalog 用
 * {@link HttpServer} 起**真 HTTP**（不是内存替身）：地址是「姓名+地址」这一档的依据，
 * 不给它真地址这一档就没法验。
 *
 * <p>本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>只给建议不自动执行</b>：扫描之后逻辑客户与映射表<b>一行都没写</b>；
 *       而且<b>绕过服务层直接写 SQL 同样被挡</b>（候选不能以 CONFIRMED 诞生、
 *       映射必须有已确认的候选）。</li>
 *   <li><b>四级信号都能命中</b>：平台买家标识 / 邮箱 / 电话 / 姓名+地址。</li>
 *   <li><b>同名不同人不产出候选</b>：只有姓名相同、地址对不上 ⇒ 只计数，不进队列。</li>
 *   <li><b>同店不产出候选</b>：本模块管的是「跨店合并」。</li>
 *   <li><b>必须可拆分</b>：拆分把映射 ACTIVE → SPLIT 并留痕，<b>来源行一个字都没改</b>；
 *       已拆分的映射改回 ACTIVE 被 DB 挡；映射行删不掉。</li>
 *   <li><b>金额按币种分行</b>：USD 与 EUR 分两行，跨币种不相加。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：置信度是 numeric(5,4)；
 *       带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 *
 * <p>🔴 直接写库的断言<b>一律在租户上下文里</b>：本模块的表全是 FORCE RLS，
 * 没有绑租户时连插入都会被策略挡掉 —— 那样断言就变成了「被 RLS 挡了」，
 * 而不是「被业务约束挡了」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerMergeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final Map<String, String> catalogAddresses = new ConcurrentHashMap<>();

  static HttpServer identity;
  static HttpServer catalog;

  // ------------------------------------------------------------ 名单

  static final UUID partyAliceA = UUID.randomUUID();
  static final UUID partyAliceB = UUID.randomUUID();
  static final UUID partyBob = UUID.randomUUID();
  static final UUID partyCarol = UUID.randomUUID();
  static final UUID partyDave = UUID.randomUUID();
  static final UUID partyEve = UUID.randomUUID();
  static final UUID partyEve2 = UUID.randomUUID();
  static final UUID partyFrank = UUID.randomUUID();
  static final UUID partyFrank2 = UUID.randomUUID();
  static final UUID partyGraceA = UUID.randomUUID();
  static final UUID partyGraceB = UUID.randomUUID();
  static final UUID partyFredA = UUID.randomUUID();
  static final UUID partyFredB = UUID.randomUUID();

  /** A1 / A2：同邮箱 + 同姓名 + 同地址 ⇒ EMAIL + NAME_ADDRESS。 */
  static final UUID a1 = UUID.randomUUID();
  static final UUID a2 = UUID.randomUUID();
  /** B1 / B2：同平台买家标识 + 同姓名 + 同地址 ⇒ PLATFORM_BUYER_ID + NAME_ADDRESS。 */
  static final UUID b1 = UUID.randomUUID();
  static final UUID b2 = UUID.randomUUID();
  /** C1 / C2：同姓名、地址不同 ⇒ 同名不同人，**不产出候选**。 */
  static final UUID c1 = UUID.randomUUID();
  static final UUID c2 = UUID.randomUUID();
  /** D1 / D2：同电话，姓名同但地址不同 ⇒ 只命中 PHONE。 */
  static final UUID d1 = UUID.randomUUID();
  static final UUID d2 = UUID.randomUUID();
  /** E1 / E2：同邮箱，其中一侧退订生效中 ⇒ OPT_OUT 排除信号。 */
  static final UUID e1 = UUID.randomUUID();
  static final UUID e2 = UUID.randomUUID();
  /** F1 / F2：同邮箱，但**同一个来源店铺** ⇒ 不算跨店，不产出候选。 */
  static final UUID f1 = UUID.randomUUID();
  static final UUID f2 = UUID.randomUUID();
  /** G1 / G2：同姓名 + 同地址，但邮箱 / 电话 / 平台买家标识都不同 ⇒ 只命中 NAME_ADDRESS。 */
  static final UUID g1 = UUID.randomUUID();
  static final UUID g2 = UUID.randomUUID();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.customers.read", "crm.customers.update");
    List<String> withPii =
        List.of("crm.customers.read", "crm.customers.update", "crm.customers.pii.read");
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
    actors.put(
        "session-pii",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            withPii,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", withPii))));
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
    try {
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/parties",
          e -> {
            // 🔴 上游路径是 /catalog/parties/{id}/addresses ⇒ 主体 id 是**中间**那一段，
            //   取最后一段会拿到 "addresses"，于是所有地址都查不到（而页面还看不出来）。
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/parties/".length());
            String id = rest.split("/")[0];
            String oneLine = catalogAddresses.get(id);
            Map<String, Object> page = new HashMap<>();
            List<Map<String, Object>> items = new ArrayList<>();
            if (oneLine != null) {
              Map<String, Object> address = new HashMap<>();
              address.put("id", id + "-addr");
              address.put("partyId", id);
              address.put("oneLine", oneLine);
              address.put("countryCode", "US");
              address.put("city", "Springfield");
              address.put("streetLine1", oneLine);
              address.put("deleted", false);
              address.put("defaultUsages", List.of("SHIP_TO"));
              items.add(address);
            }
            page.put("items", items);
            page.put("total", items.size());
            page.put("page", 0);
            page.put("size", 50);
            byte[] body = json.writeValueAsBytes(page);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(4));
      catalog.start();
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
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ------------------------------------------------------------ 脚手架

  /** 在**租户上下文**里执行一段 SQL（本模块的表全是 FORCE RLS，不绑租户连写都写不进去）。 */
  private <T> T inTenant(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  private void insertProfile(
      UUID id,
      String ref,
      String shop,
      String name,
      UUID party,
      String email,
      String phone,
      String optOut,
      int orderCount,
      OffsetDateTime firstOrderAt) {
    inTenant(
        () -> {
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into crm_customer_profile"
                  + " (id,tenant_id,customer_ref,source_shop,customer_name,customer_party_id,"
                  + " first_order_at,last_order_at,order_count,refund_case_count,value_tier,risk_flag,"
                  + " risk_reason,contact_email,contact_phone,contact_email_masked,contact_phone_masked,"
                  + " contact_source,opt_out_state,window_from,window_to,synced_at,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,0,'NORMAL','NONE','',?,?,?,?,'CATALOG_PARTY',"
                  + " ?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              ref,
              shop,
              name,
              party,
              firstOrderAt,
              firstOrderAt,
              orderCount,
              email,
              phone,
              mask(email),
              mask(phone),
              optOut,
              now.minusDays(30),
              now,
              now,
              now,
              now);
          return null;
        });
  }

  private static String mask(String value) {
    if (value == null || value.isBlank()) return "";
    if (value.contains("@")) {
      int dot = value.indexOf('@');
      return value.charAt(0) + "***" + value.substring(dot);
    }
    if (value.length() <= 4) return "****";
    return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
  }

  private void insertAmount(UUID customerId, String currency, String total, int orders) {
    inTenant(
        () -> {
          BigDecimal amount = new BigDecimal(total);
          BigDecimal avg =
              orders == 0
                  ? null
                  : amount.divide(BigDecimal.valueOf(orders), 4, java.math.RoundingMode.HALF_UP);
          sql.execute(
              "insert into crm_customer_amount"
                  + " (id,tenant_id,customer_id,currency,total_amount,order_count,avg_order_amount)"
                  + " values (?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              customerId,
              currency,
              amount,
              orders,
              avg);
          return null;
        });
  }

  @BeforeEach
  void reset() throws Exception {
    catalogAddresses.clear();
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_customer_merge_operation, crm_customer_merge_candidate,"
              + " crm_customer_merge_mapping, crm_customer_merge_logical, crm_customer_merge_run,"
              + " crm_customer_amount, crm_customer_profile cascade");
    }

    OffsetDateTime at = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");
    catalogAddresses.put(partyAliceA.toString(), "1 Main St, Springfield");
    catalogAddresses.put(partyAliceB.toString(), "1 Main St, Springfield");
    catalogAddresses.put(partyBob.toString(), "2 Oak Ave, Shelbyville");
    catalogAddresses.put(partyCarol.toString(), "9 Pine Rd, Capital City");
    catalogAddresses.put(partyDave.toString(), "8 Pine Rd, Capital City");
    catalogAddresses.put(partyEve.toString(), "3 Elm St, Ogdenville");
    catalogAddresses.put(partyEve2.toString(), "4 Elm St, Ogdenville");
    catalogAddresses.put(partyFrank.toString(), "5 Cedar St, North Haverbrook");
    catalogAddresses.put(partyFrank2.toString(), "6 Cedar St, North Haverbrook");
    catalogAddresses.put(partyGraceA.toString(), "7 Birch Ln, South Haverbrook");
    catalogAddresses.put(partyGraceB.toString(), "7 Birch Ln, South Haverbrook");
    // 🔴 F1 / F2 用**自己**的主体 id：复用 A1 / A2 的主体会让它们与 A 组成对
    //   命中「平台买家标识」，凭空多出候选（测试数据自己造出来的信号最难查）。
    catalogAddresses.put(partyFredA.toString(), "11 Willow Way, West Haverbrook");
    catalogAddresses.put(partyFredB.toString(), "12 Willow Way, West Haverbrook");

    insertProfile(
        a1, "C-AMZ-1", "AMAZON", "Alice Chen", partyAliceA, "alice@example.com", "13800000001", "NONE", 3, at);
    insertProfile(
        a2, "C-SHP-1", "SHOPIFY", "Alice Chen", partyAliceB, "alice@example.com", "13900000002", "NONE", 2, at);
    insertProfile(
        b1, "C-AMZ-2", "AMAZON", "Bob Smith", partyBob, "bob@example.com", "13800000003", "NONE", 5, at);
    insertProfile(
        b2, "C-EBAY-2", "EBAY", "Bob Smith", partyBob, "bob2@example.com", "13900000004", "NONE", 1, at);
    insertProfile(
        c1, "C-ETSY-1", "ETSY", "Bob Smith", partyCarol, "carol@example.com", "13800000005", "NONE", 1, at);
    insertProfile(
        c2, "C-WMT-1", "WALMART", "Bob Smith", partyDave, "dave@example.com", "13900000006", "NONE", 1, at);
    insertProfile(
        d1, "C-AMZ-3", "AMAZON", "Dave Jones", partyEve, "frank@example.com", "13700000007", "NONE", 1, at);
    insertProfile(
        d2, "C-SHP-3", "SHOPIFY", "Dave Jones", partyEve2, "frank2@example.com", "13700000007", "NONE", 1, at);
    insertProfile(
        e1, "C-AMZ-4", "AMAZON", "Eve Opt", partyFrank, "eve@example.com", "13600000008", "ACTIVE", 1, at);
    insertProfile(
        e2, "C-EBAY-4", "EBAY", "Eve Opt", partyFrank2, "eve@example.com", "13600000009", "NONE", 1, at);
    insertProfile(
        f1, "C-ETSY-5", "ETSY", "Fred Same", partyFredA, "fred@example.com", "13500000010", "NONE", 1, at);
    insertProfile(
        f2, "C-ETSY-6", "ETSY", "Fred Same", partyFredB, "fred@example.com", "13500000011", "NONE", 1, at);
    insertProfile(
        g1, "C-ZB-1", "ZEBRA", "Grace Lee", partyGraceA, "grace1@example.com", "13400000020", "NONE", 1, at);
    insertProfile(
        g2, "C-YH-2", "YOOHOO", "Grace Lee", partyGraceB, "grace2@example.com", "13400000021", "NONE", 1, at);

    // 金额：USD 与 EUR 分开（跨币种不相加）。
    insertAmount(a1, "USD", "100.10", 3);
    insertAmount(a2, "EUR", "20.00", 2);
  }

  private java.sql.Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  // ------------------------------------------------------------ 请求助手

  private String body(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
      throws Exception {
    return http.perform(builder.accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String scan() throws Exception {
    return body(
        post("/crm/customer-merge/scan")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private List<Map<String, Object>> candidates() throws Exception {
    String raw =
        body(get("/crm/customer-merge/candidates?size=100").header("Authorization", "session"));
    return rowsOf(raw, "rows");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rowsOf(String raw, String key) throws Exception {
    Map<String, Object> parsed = json.readValue(raw, Map.class);
    return (List<Map<String, Object>>) parsed.get(key);
  }

  /**
   * 找一对候选（<b>顺序无关</b>）。
   *
   * <p>🔴 服务端按 **PostgreSQL 的 uuid 字节序**决定谁是左谁是右，
   *   而测试里的 id 是随机 UUID ⇒ 左边是谁**不可预测**。
   *   断言若写死左右，遇到随机顺序就会假红。
   */
  private Map<String, Object> candidateOf(UUID left, UUID right) throws Exception {
    String leftRef = refOf(left);
    String rightRef = refOf(right);
    for (Map<String, Object> row : candidates()) {
      String rowLeft = (String) row.get("leftCustomerRef");
      String rowRight = (String) row.get("rightCustomerRef");
      if ((rowLeft.equals(leftRef) && rowRight.equals(rightRef))
          || (rowLeft.equals(rightRef) && rowRight.equals(leftRef))) {
        return row;
      }
    }
    throw new AssertionError("没有找到候选：" + leftRef + " / " + rightRef);
  }

  /** 候选列表里**有没有**这一对（顺序无关）。 */
  private boolean hasCandidate(String refA, String refB) throws Exception {
    for (Map<String, Object> row : candidates()) {
      String rowLeft = (String) row.get("leftCustomerRef");
      String rowRight = (String) row.get("rightCustomerRef");
      if ((rowLeft.equals(refA) && rowRight.equals(refB))
          || (rowLeft.equals(refB) && rowRight.equals(refA))) {
        return true;
      }
    }
    return false;
  }

  private String refOf(UUID profileId) {
    return inTenant(
        () ->
            sql.fetchOne("select customer_ref from crm_customer_profile where id = ?", profileId)
                .get(0, String.class));
  }

  // ==========================================================================
  // 🔴 只给建议，不自动执行
  // ==========================================================================

  @Test
  void 扫描只产出建议_不写任何逻辑客户与映射() throws Exception {
    scan();
    assertThat(inTenant(() -> sql.fetch("select id from crm_customer_merge_logical").size()))
        .as("扫描**不得**创建逻辑客户")
        .isZero();
    assertThat(inTenant(() -> sql.fetch("select id from crm_customer_merge_mapping").size()))
        .as("扫描**不得**创建合并映射")
        .isZero();
    assertThat(inTenant(() -> sql.fetch("select id from crm_customer_merge_candidate").size()))
        .as("扫描应当产出候选")
        .isPositive();
    // 候选的初值必须是 SUGGESTED（待确认）。
    assertThat(
            inTenant(
                () ->
                    sql.fetch("select status from crm_customer_merge_candidate").stream()
                        .map(r -> r.get(0, String.class))
                        .distinct()
                        .toList()))
        .containsExactly("SUGGESTED");
  }

  @Test
  void 绕过服务层直接写确认候选被db挡下() {
    // 🔴 直接 INSERT 一条 CONFIRMED 候选（没有人工复核）⇒ DB 必须挡下来。
    //   ⚠️ 两侧必须按**数据库的 uuid 字节序**排好，否则先撞上的是「左侧必须小于右侧」那条约束。
    UUID left = CustomerMergeService.compareUuid(a1, a2) <= 0 ? a1 : a2;
    UUID right = CustomerMergeService.compareUuid(a1, a2) <= 0 ? a2 : a1;
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "insert into crm_customer_merge_candidate"
                              + " (id,tenant_id,left_customer_id,right_customer_id,left_source_shop,left_customer_ref,"
                              + " left_customer_name,right_source_shop,right_customer_ref,right_customer_name,match_type,"
                              + " confidence,signal_summary,evidence,exclusion_flags,opt_out_involved,status,"
                              + " logical_customer_id,reviewed_by,reviewed_at,review_note,detected_at,created_at,updated_at)"
                              + " values (?,?,?,?,'AMAZON','X','X','SHOPIFY','Y','Y','EMAIL',0.9,'邮箱相同','{}','[]',false,"
                              + " 'CONFIRMED',null,?,?::timestamptz,'绕过服务层',now(),now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          left,
                          right,
                          userId,
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .hasMessageContaining("SUGGESTED");
  }

  @Test
  void 绕过服务层直接写合并映射被db挡下() throws Exception {
    scan();
    UUID logicalId = UUID.randomUUID();
    inTenant(
        () -> {
          sql.execute(
              "insert into crm_customer_merge_logical"
                  + " (id,tenant_id,logical_ref,display_name,member_count,source_shop_count,total_order_count,"
                  + " opt_out_state,status,created_at,updated_at)"
                  + " values (?,?,?,'偷建的',0,0,0,'NONE','ACTIVE',now(),now())",
              logicalId,
              tenant,
              "LC-BYPASS-000001");
          return null;
        });
    // 🔴 没有已确认候选 ⇒ 直接写映射必须被挡。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "insert into crm_customer_merge_mapping"
                              + " (id,tenant_id,logical_customer_id,customer_profile_id,source_shop,customer_ref,"
                              + " customer_name,role,state,merged_at,merged_by,created_at,updated_at)"
                              + " values (?,?,?,?,'AMAZON','X','X','PRIMARY','ACTIVE',now(),?,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          logicalId,
                          a1,
                          userId);
                      return null;
                    }))
        .hasMessageContaining("已人工确认");
  }

  // ==========================================================================
  // 四级信号
  // ==========================================================================

  @Test
  void 四种匹配依据都能产出候选且置信度可解释() throws Exception {
    scan();

    Map<String, Object> email = candidateOf(a1, a2);
    assertThat(email.get("matchType")).isEqualTo("EMAIL");
    assertThat((BigDecimal) email.get("confidence")).isEqualByComparingTo("0.9000");
    assertThat((String) email.get("signalSummary")).contains("邮箱相同");

    Map<String, Object> party = candidateOf(b1, b2);
    assertThat(party.get("matchType")).isEqualTo("PLATFORM_BUYER_ID");
    // 平台买家标识 0.95 + 旁证（姓名+地址）0.05 = 1.0000 ⇒ 封顶 0.9900。
    assertThat((BigDecimal) party.get("confidence")).isEqualByComparingTo("0.9900");

    Map<String, Object> phone = candidateOf(d1, d2);
    assertThat(phone.get("matchType")).isEqualTo("PHONE");
    assertThat((BigDecimal) phone.get("confidence")).isEqualByComparingTo("0.7500");

    // 「姓名 + 地址」这一档：邮箱 / 电话 / 平台买家标识都不同，只有姓名与地址相同。
    Map<String, Object> nameAddress = candidateOf(g1, g2);
    assertThat(nameAddress.get("matchType")).isEqualTo("NAME_ADDRESS");
    assertThat((BigDecimal) nameAddress.get("confidence")).isEqualByComparingTo("0.6000");
    assertThat((String) nameAddress.get("signalSummary")).contains("姓名与地址都相同");
  }

  @Test
  void 同名不同人不产出候选_只计数() throws Exception {
    String raw = scan();
    Map<String, Object> result = json.readValue(raw, Map.class);
    assertThat((Integer) result.get("belowThresholdCount"))
        .as("同名但地址对不上的对子只计数，不进候选队列")
        .isPositive();
    // 候选列表里**没有** C-ETSY-1 / C-WMT-1 这一对。
    assertThat(hasCandidate("C-ETSY-1", "C-WMT-1"))
        .as("同名但地址不同的那一对不得进候选队列")
        .isFalse();
  }

  @Test
  void 同一个来源店铺不产出候选() throws Exception {
    scan();
    assertThat(hasCandidate("C-ETSY-5", "C-ETSY-6"))
        .as("同店的两行不算「跨店合并」")
        .isFalse();
  }

  @Test
  void 退订生效中的一侧会被标成排除信号() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(e1, e2);
    assertThat(candidate.get("optOutInvolved")).isEqualTo(Boolean.TRUE);
    List<String> flags =
        ((List<?>) candidate.get("exclusionFlags")).stream().map(String::valueOf).toList();
    assertThat(flags).containsExactly("OPT_OUT");
  }

  // ==========================================================================
  // 人工确认（合并）
  // ==========================================================================

  @Test
  void 人工确认后写出逻辑客户与映射_且来源行一个字都没改() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");

    String before =
        inTenant(
            () ->
                sql.fetchOne(
                        "select customer_ref, source_shop, customer_name, contact_email"
                            + " from crm_customer_profile where id = ?",
                        a1)
                    .toString());

    String raw =
        body(
            post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"邮箱与姓名地址都一致，人工确认是同一个人\"}"));
    Map<String, Object> detail = json.readValue(raw, Map.class);

    Map<String, Object> logical = castMap(detail.get("logical"));
    assertThat((String) logical.get("logicalRef")).startsWith("LC-");
    assertThat((Integer) logical.get("memberCount")).isEqualTo(2);
    assertThat((Integer) logical.get("sourceShopCount")).isEqualTo(2);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members = (List<Map<String, Object>>) detail.get("members");
    assertThat(members).hasSize(2);
    assertThat(members).allSatisfy(m -> assertThat(m.get("state")).isEqualTo("ACTIVE"));

    // 🔴 来源行**一个字都没被改过** —— 这正是「拆分能还原」的根据。
    String after =
        inTenant(
            () ->
                sql.fetchOne(
                        "select customer_ref, source_shop, customer_name, contact_email"
                            + " from crm_customer_profile where id = ?",
                        a1)
                    .toString());
    assertThat(after).isEqualTo(before);

    // 候选落终态且带复核留痕。
    Map<String, Object> row = candidateOf(a1, a2);
    assertThat(row.get("status")).isEqualTo("CONFIRMED");
    assertThat((String) row.get("reviewedBy")).isNotBlank();
    assertThat((String) row.get("reviewNote")).contains("人工确认");
    assertThat((String) row.get("logicalRef")).startsWith("LC-");
  }

  @Test
  void 确认必须写理由_没理由直接400() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");
    http.perform(
            post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 请求体带租户字段直接400_租户只认已认证成员关系() throws Exception {
    http.perform(
            post("/crm/customer-merge/scan")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxCustomers\":10,\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 🔴 必须可拆分
  // ==========================================================================

  @Test
  void 拆分把成员移出去并留痕_来源行原样() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");
    String raw =
        body(
            post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"人工确认\"}"));
    Map<String, Object> detail = json.readValue(raw, Map.class);
    Map<String, Object> logical = castMap(detail.get("logical"));
    String logicalId = (String) logical.get("id");

    // 拆 a2 出去。
    String splitRaw =
        body(
            post("/crm/customer-merge/logical/" + logicalId + "/split")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"customerProfileId\":\""
                        + a2
                        + "\",\"reason\":\"复核后发现不是同一个人\"}"));
    Map<String, Object> after = json.readValue(splitRaw, Map.class);
    Map<String, Object> afterLogical = castMap(after.get("logical"));
    assertThat((Integer) afterLogical.get("memberCount")).isEqualTo(1);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members = (List<Map<String, Object>>) after.get("members");
    assertThat(members).hasSize(2);
    assertThat(members).filteredOn(m -> "SPLIT".equals(m.get("state"))).hasSize(1);
    Map<String, Object> splitMember =
        members.stream().filter(m -> "SPLIT".equals(m.get("state"))).findFirst().orElseThrow();
    assertThat((String) splitMember.get("splitReason")).isEqualTo("复核后发现不是同一个人");
    assertThat((String) splitMember.get("splitAt")).isNotBlank();
    assertThat((String) splitMember.get("splitBy")).isNotBlank();

    // 🔴 拆开后来源行**仍然原样**在 crm_customer_profile 里（它从头到尾没被改过）。
    assertThat(refOf(a2)).isEqualTo("C-SHP-1");
    // 映射行**不删**：历史那一行原样留着。
    assertThat(
            inTenant(
                () ->
                    sql.fetch("select state from crm_customer_merge_mapping").stream()
                        .map(r -> r.get(0, String.class))
                        .toList()))
        .containsExactlyInAnyOrder("ACTIVE", "SPLIT");
  }

  @Test
  void 已拆分的映射不能改回生效_且映射行删不掉() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");
    Map<String, Object> detail =
        json.readValue(
            body(
                post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"人工确认\"}")),
            Map.class);
    String logicalId = (String) castMap(detail.get("logical")).get("id");
    body(
        post("/crm/customer-merge/logical/" + logicalId + "/split")
            .header("Authorization", "session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerProfileId\":\"" + a2 + "\",\"reason\":\"拆开\"}"));

    UUID splitMapping =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from crm_customer_merge_mapping"
                            + " where tenant_id = ? and customer_profile_id = ? and state = 'SPLIT'",
                        tenant,
                        a2)
                    .get(0, UUID.class));

    // 🔴 SPLIT → ACTIVE 被 DB 挡（重新合并要新写一行，历史那行原样留着）。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "update crm_customer_merge_mapping set state = 'ACTIVE' where id = ?",
                          splitMapping);
                      return null;
                    }))
        .hasMessageContaining("不能直接改回生效");

    // 🔴 映射行**删不掉**（删掉就等于抹掉合并痕迹）。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute("delete from crm_customer_merge_mapping where id = ?", splitMapping);
                      return null;
                    }))
        .hasMessageContaining("不可删除");
  }

  // ==========================================================================
  // 金额按币种分行 / 禁浮点 / 时区
  // ==========================================================================

  @Test
  void 逻辑客户的金额按币种分行_跨币种不相加() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");
    Map<String, Object> detail =
        json.readValue(
            body(
                post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"人工确认\"}")),
            Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> amounts = (List<Map<String, Object>>) detail.get("amounts");
    assertThat(amounts).hasSize(2);
    assertThat(amounts).extracting(a -> a.get("currency")).containsExactly("EUR", "USD");
    assertThat((BigDecimal) amounts.get(0).get("totalAmount")).isEqualByComparingTo("20.0000");
    assertThat((BigDecimal) amounts.get(1).get("totalAmount")).isEqualByComparingTo("100.1000");
    // 客单价：USD 100.10 ÷ 3 = 33.3667；EUR 20.00 ÷ 2 = 10.0000。
    assertThat((BigDecimal) amounts.get(1).get("avgOrderAmount")).isEqualByComparingTo("33.3667");
    assertThat((BigDecimal) amounts.get(0).get("avgOrderAmount")).isEqualByComparingTo("10.0000");
  }

  @Test
  void 置信度是numeric定点_不是浮点() throws Exception {
    scan();
    for (Map<String, Object> row : candidates()) {
      assertThat(row.get("confidence")).isInstanceOf(BigDecimal.class);
      assertThat((BigDecimal) row.get("confidence"))
          .isBetween(new BigDecimal("0.6"), new BigDecimal("0.99"));
    }
    // DB 列本身是 numeric(5,4)。
    var type =
        sql.fetchOne(
                "select data_type from information_schema.columns"
                    + " where table_name = 'crm_customer_merge_candidate' and column_name = 'confidence'")
            .get(0, String.class);
    assertThat(type).isEqualTo("numeric");
  }

  @Test
  void 带偏移量的时刻写回等于同一绝对时刻_不静默偏8小时() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");
    Map<String, Object> detail =
        json.readValue(
            body(
                post("/crm/customer-merge/candidates/" + candidateId + "/confirm")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"人工确认\"}")),
            Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members = (List<Map<String, Object>>) detail.get("members");
    String mergedAt = (String) members.get(0).get("mergedAt");
    assertThat(mergedAt).contains("T");
    // 写库时刻与读回时刻是同一个绝对时刻（不是本地时间）。
    OffsetDateTime written =
        inTenant(
            () ->
                sql.fetchOne("select merged_at from crm_customer_merge_mapping limit 1")
                    .get(0, OffsetDateTime.class));
    assertThat(written.toInstant()).isEqualTo(OffsetDateTime.parse(mergedAt).toInstant());
  }

  // ==========================================================================
  // 脱敏 / 权限 / 分页
  // ==========================================================================

  @Test
  void 候选详情默认脱敏_要明文得有额外权限码() throws Exception {
    scan();
    Map<String, Object> candidate = candidateOf(a1, a2);
    String candidateId = (String) candidate.get("id");

    String masked =
        body(get("/crm/customer-merge/candidates/" + candidateId).header("Authorization", "session"));
    Map<String, Object> detail = json.readValue(masked, Map.class);
    assertThat((String) detail.get("leftEmail")).isEqualTo("a***@example.com");
    assertThat(detail.get("contactMasked")).isEqualTo(Boolean.TRUE);

    // 有权限码但**没显式要求** ⇒ 仍然脱敏。
    String stillMasked =
        body(
            get("/crm/customer-merge/candidates/" + candidateId).header("Authorization", "session-pii"));
    assertThat((String) json.readValue(stillMasked, Map.class).get("leftEmail"))
        .isEqualTo("a***@example.com");

    // 显式要求 + 有权限码 ⇒ 给明文。
    String plain =
        body(
            get("/crm/customer-merge/candidates/" + candidateId + "?revealContact=true")
                .header("Authorization", "session-pii"));
    assertThat((String) json.readValue(plain, Map.class).get("leftEmail")).isEqualTo("alice@example.com");

    // 🔴 显式要求但**没有**权限码 ⇒ 403，不是悄悄退回脱敏。
    http.perform(
            get("/crm/customer-merge/candidates/" + candidateId + "?revealContact=true")
                .header("Authorization", "session"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 候选分页是真的() throws Exception {
    scan();
    String raw =
        body(get("/crm/customer-merge/candidates?page=0&size=2").header("Authorization", "session"));
    Map<String, Object> page = json.readValue(raw, Map.class);
    assertThat((Integer) page.get("size")).isEqualTo(2);
    assertThat((Integer) page.get("page")).isEqualTo(0);
    assertThat(((Number) page.get("total")).longValue()).isGreaterThan(2L);
    assertThat((Integer) page.get("totalPages")).isGreaterThan(1);
    assertThat((List<?>) page.get("rows")).hasSize(2);

    String second =
        body(get("/crm/customer-merge/candidates?page=1&size=2").header("Authorization", "session"));
    List<Object> firstIds = rowsOf(raw, "rows").stream().map(r -> r.get("id")).toList();
    List<Object> secondIds = rowsOf(second, "rows").stream().map(r -> r.get("id")).toList();
    assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    assertThat(secondRows(second)).isNotEmpty();
  }

  private List<Map<String, Object>> secondRows(String raw) throws Exception {
    return rowsOf(raw, "rows");
  }

  @Test
  void 伪造的租户头无效_看到的仍是会话租户的数据() throws Exception {
    scan();
    String plain =
        body(get("/crm/customer-merge/candidates?size=100").header("Authorization", "session"));
    String forged =
        body(
            get("/crm/customer-merge/candidates?size=100")
                .header("Authorization", "session")
                .header("X-Tenant-Id", UUID.randomUUID().toString()));
    // 🔴 伪造头**一点都不能**改变看到的数据：结果逐条相同。
    assertThat(rowsOf(forged, "rows")).isEqualTo(rowsOf(plain, "rows"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
