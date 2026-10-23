package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.jooq.Record;
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
 * 12.5.1.1 会计科目的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 里 12.5.1.1 的那几条：
 *
 * <ul>
 *   <li>「维护**法人账簿**的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、余额方向、是否明细科目、关联税务属性」⇒ 七个字段**全部落库**且读得出来；
 *   <li>「**支持科目映射到业务事件**（如发货 → 收入 + 结转成本）」⇒ 映射规则真的落库、可查、借贷齐备才让启用；
 *   <li>「输出：科目表 + 映射规则」⇒ 两样都是真实数据，没有占位；
 *   <li>「**科目体系是用户自定的**，系统提供模板但必须允许完全自定义」⇒ 模板只是导入源，导入后每个字段都能改；
 *   <li>「下游 = 12.5.2.1 凭证」⇒ 科目编码**唯一且不可改**，凭证能按编码软引用。
 * </ul>
 *
 * <p>⚠️ 断言分两类：**服务层能拒绝的**（走 HTTP 端点）与**服务层绕不过的**（直连 SQL），
 * 后者才证明「绕过服务层直连数据库也造不出假数据」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSubjectIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  /** 法人账簿（identity 的逻辑 id，跨服务不建 FK）。 */
  static final UUID BOOK = UUID.randomUUID();

  /** 法人（identity 的逻辑 id）。 */
  static final UUID ENTITY = UUID.randomUUID();

  /** 另一本账簿（用来验证「规则与科目必须同一本账簿」）。 */
  static final UUID OTHER_BOOK = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.account_subject.read",
          "finance.account_subject.create",
          "finance.account_subject.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static HttpServer identity;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
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
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_account_subject_event_mapping_line,"
              + " finance_account_subject_event_mapping, finance_account_subject_event,"
              + " finance_account_subject cascade");
    }
  }

  /**
   * 直连查（**必须在租户事务里**读）。
   *
   * <p>⚠️ 事务外没有任何租户上下文，RLS 会诚实地把行全挡掉 ——
   * 那恰好又是一次隔离生效的证明，但会让「行到底写没写进去」这个问题答不了。
   */
  Record fetchOneInTenant(String statement, Object... binds) {
    return tenants.run(tenant, () -> sql.fetchOne(statement, binds));
  }

  /** 直连查多行（同上，必须在租户事务里读）。 */
  java.util.List<Record> fetchInTenant(String statement, Object... binds) {
    return tenants.run(tenant, () -> sql.fetch(statement, binds));
  }

  /** 建一个科目（走服务层 ⇒ 真走鉴权 + 事务 + RLS）。 */
  UUID createSubject(String code, String name, String category, String direction, boolean detail)
      throws Exception {
    return createSubject(code, name, category, direction, detail, null);
  }

  /** 建科目，可指定上级科目编码（科目树的父子关系靠它建立）。 */
  UUID createSubject(
      String code,
      String name,
      String category,
      String direction,
      boolean detail,
      String parentSubjectCode)
      throws Exception {
    var body =
        """
        {"accountingBookId":"%s","legalEntityId":"%s","subjectCode":"%s","subjectName":"%s",
         "category":"%s","balanceDirection":"%s","detail":%s,"parentSubjectCode":%s}
        """
            .formatted(
                BOOK,
                ENTITY,
                code,
                name,
                category,
                direction,
                detail,
                parentSubjectCode == null ? "null" : "\"" + parentSubjectCode + "\"");
    var result =
        http.perform(
                post("/finance/account-subjects")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(result).get("id").asText());
  }

  // ================================================================
  // 迁移确实落地了
  // ================================================================

  @Test
  void 迁移落地_表与模板都在() throws Exception {
    for (String table :
        List.of(
            "finance_account_subject",
            "finance_account_subject_event_mapping",
            "finance_account_subject_event_mapping_line",
            "finance_account_subject_event",
            "finance_account_subject_template")) {
      var row =
          sql.fetchOne("select count(*) from pg_class where relname = ?", table);
      assertThat(row.get(0, Long.class)).as("V356 必须真的建出 " + table).isPositive();
    }
    // 模板是**真实落库的数据**，不是代码里的常量数组。
    var templates =
        sql.fetch("select distinct template_key from finance_account_subject_template order by template_key");
    assertThat(templates).isNotEmpty();
    var subjects =
        sql.fetchOne(
            "select count(*) from finance_account_subject_template where template_key = 'CN_STANDARD_V1'");
    assertThat(subjects.get(0, Long.class)).as("模板里必须有真实科目").isGreaterThan(20);
  }

  @Test
  void 模板里带一个汇总科目和两个明细子科目() throws Exception {
    var group =
        sql.fetchOne(
            "select is_detail from finance_account_subject_template"
                + " where template_key = 'CN_STANDARD_V1' and subject_code = '1122'");
    assertThat(group.get(0, Boolean.class)).as("1122 应收账款应是汇总科目").isFalse();
    var children =
        sql.fetch(
            "select subject_code from finance_account_subject_template"
                + " where template_key = 'CN_STANDARD_V1' and parent_subject_code = '1122' order by subject_code");
    assertThat(children.map(r -> r.get(0, String.class)))
        .containsExactly("1122.01", "1122.02");
  }

  // ================================================================
  // 科目：字段齐全 + 真落库
  // ================================================================

  @Test
  void 新增科目_七个字段全部落库且读得出来() throws Exception {
    var body =
        """
        {"accountingBookId":"%s","legalEntityId":"%s","subjectCode":"1122.01","subjectName":"应收账款-境内",
         "category":"ASSET","balanceDirection":"DEBIT","detail":true,
         "taxTreatment":"TAXABLE","taxRate":0.13,"taxNote":"增值税税率"}
        """
            .formatted(BOOK, ENTITY);
    var response =
        http.perform(
                post("/finance/account-subjects")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(response);
    assertThat(node.get("subjectCode").asText()).isEqualTo("1122.01");
    assertThat(node.get("subjectName").asText()).isEqualTo("应收账款-境内");
    assertThat(node.get("category").asText()).isEqualTo("ASSET");
    assertThat(node.get("categoryLabel").asText()).isEqualTo("资产");
    assertThat(node.get("balanceDirection").asText()).isEqualTo("DEBIT");
    assertThat(node.get("balanceDirectionLabel").asText()).isEqualTo("借方");
    assertThat(node.get("detail").asBoolean()).isTrue();
    assertThat(node.get("taxTreatment").asText()).isEqualTo("TAXABLE");
    assertThat(node.get("taxRate").decimalValue()).isEqualByComparingTo(new BigDecimal("0.13"));
    assertThat(node.get("taxNote").asText()).isEqualTo("增值税税率");

    // 真落库：直连查得到。
    var stored =
        fetchOneInTenant(
            "select subject_code, subject_name, category, balance_direction, is_detail,"
                + " tax_treatment, tax_rate, tax_note, accounting_book_id, legal_entity_id"
                + " from finance_account_subject where subject_code = '1122.01'");
    assertThat(stored).as("科目必须真的落库").isNotNull();
    assertThat(stored.get("category", String.class)).isEqualTo("ASSET");
    assertThat(stored.get("accounting_book_id", UUID.class)).isEqualTo(BOOK);
    assertThat(stored.get("legal_entity_id", UUID.class)).isEqualTo(ENTITY);
  }

  @Test
  void 科目编码租户内唯一_重复编码被拒() throws Exception {
    createSubject("1001", "库存现金", "ASSET", "DEBIT", true);
    var body =
        """
        {"accountingBookId":"%s","legalEntityId":"%s","subjectCode":"1001","subjectName":"另一个库存现金",
         "category":"ASSET","balanceDirection":"DEBIT","detail":true}
        """
            .formatted(BOOK, ENTITY);
    http.perform(
            post("/finance/account-subjects")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 科目编码不可改_直连SQL也改不了() throws Exception {
    var id = createSubject("1001", "库存现金", "ASSET", "DEBIT", true);
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_account_subject set subject_code = '1002' where id = ?", id)))
        .as("科目编码一经入账不可修改（下游凭证按编码软引用它）")
        .hasStackTraceContaining("科目编码一经入账不可修改");
  }

  @Test
  void 科目树_层级由数据库派生且子类必须与上级同类() throws Exception {
    createSubject("1122", "应收账款", "ASSET", "DEBIT", false);
    var child = createSubject("1122.01", "应收账款-境内", "ASSET", "DEBIT", true, "1122");
    var stored =
        fetchOneInTenant("select level from finance_account_subject where id = ?", child);
    assertThat(stored.get("level", Integer.class)).as("层级由 DB 触发器派生").isEqualTo(2);

    // 下级类别必须与上级一致：资产下面不能挂负债。
    var body =
        """
        {"accountingBookId":"%s","legalEntityId":"%s","subjectCode":"1122.02","subjectName":"应收账款-境外",
         "category":"LIABILITY","balanceDirection":"CREDIT","detail":true,"parentSubjectCode":"1122"}
        """
            .formatted(BOOK, ENTITY);
    http.perform(
            post("/finance/account-subjects")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 科目编码不可成环() throws Exception {
    createSubject("1122", "应收账款", "ASSET", "DEBIT", false);
    var child = createSubject("1122.01", "应收账款-境内", "ASSET", "DEBIT", true, "1122");
    // 让 1122 挂到自己的子科目下面 ⇒ 成环。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_account_subject set parent_subject_code = '1122.01'"
                                + " where subject_code = '1122'")))
        .hasStackTraceContaining("成环");
  }

  // ================================================================
  // 模板：只是导入源，导入后完全自定义
  // ================================================================

  @Test
  void 模板导入_真的落库且已存在的编码被跳过并报出来() throws Exception {
    createSubject("1001", "我自己改过的库存现金", "ASSET", "DEBIT", true);
    var body =
        """
        {"accountingBookId":"%s","legalEntityId":"%s","templateKey":"CN_STANDARD_V1"}
        """
            .formatted(BOOK, ENTITY);
    var response =
        http.perform(
                post("/finance/account-subjects/templates/apply")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(response);
    assertThat(node.get("created").asInt()).isPositive();
    assertThat(node.get("skipped").asInt()).isEqualTo(1);
    assertThat(node.get("skippedCodes").get(0).asText()).isEqualTo("1001");

    // 已存在的那个**没有被模板覆盖** —— 用户改过的科目不能被模板冲掉。
    var kept =
        fetchOneInTenant("select subject_name from finance_account_subject where subject_code = '1001'");
    assertThat(kept.get("subject_name", String.class)).isEqualTo("我自己改过的库存现金");
    // 模板里的科目真的落库了。
    assertThat(
            fetchOneInTenant("select count(*) from finance_account_subject where source = 'TEMPLATE'")
                .get(0, Long.class))
        .isPositive();
    // 汇总科目与明细子科目都在，父子关系成立。
    var imported =
        fetchOneInTenant(
            "select parent_subject_code, level from finance_account_subject"
                + " where subject_code = '1122.01'");
    assertThat(imported.get("parent_subject_code", String.class)).isEqualTo("1122");
    assertThat(imported.get("level", Integer.class)).isEqualTo(2);
  }

  @Test
  void 导入后每个字段都能改_科目体系完全自定义() throws Exception {
    var id = createSubject("9001", "我的自定义科目", "ASSET", "DEBIT", true);
    http.perform(
            put("/finance/account-subjects/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"subjectName":"我的自定义科目（改过名）","category":"LIABILITY","balanceDirection":"CREDIT",
                     "detail":true,"taxTreatment":"EXEMPT","taxRate":null,"taxNote":"免税"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subjectName").value("我的自定义科目（改过名）"))
        .andExpect(jsonPath("$.category").value("LIABILITY"))
        .andExpect(jsonPath("$.balanceDirection").value("CREDIT"))
        .andExpect(jsonPath("$.taxTreatment").value("EXEMPT"));
  }

  // ================================================================
  // 业务事件 → 科目的映射
  // ================================================================

  @Test
  void 映射规则_借贷齐备才让启用() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    createSubject("6401", "主营业务成本", "COST", "DEBIT", true);

    // 只有借方一行 ⇒ 启用被拒。
    var only =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED","eventLabel":"订单发货",
         "lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1}]}
        """
            .formatted(BOOK);
    var id =
        UUID.fromString(
            json.readTree(
                    http.perform(
                            post("/finance/account-subjects/mappings")
                                .header("Authorization", "session")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(only))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText());
    http.perform(
            post("/finance/account-subjects/mappings/" + id + "/activate")
                .header("Authorization", "session"))
        .andExpect(status().isBadRequest());

    // 补上贷方一行（发货 → 结转成本 + 确认收入）⇒ 启用成功。
    http.perform(
            put("/finance/account-subjects/mappings/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"eventLabel":"订单发货",
                     "lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1},
                              {"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":2}]}
                    """))
        .andExpect(status().isOk());
    var activated =
        http.perform(
                post("/finance/account-subjects/mappings/" + id + "/activate")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.balanced").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(activated).get("debitLineCount").asInt()).isEqualTo(1);
    assertThat(json.readTree(activated).get("creditLineCount").asInt()).isEqualTo(1);

    // 真落库。
    var lines =
        fetchInTenant(
            "select subject_code, direction, amount_basis from"
                + " finance_account_subject_event_mapping_line where mapping_id = ? order by sequence",
            id);
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("direction", String.class)).isEqualTo("DEBIT");
    assertThat(lines.get(1).get("amount_basis", String.class)).isEqualTo("ORDER_ITEM_AMOUNT");
  }

  @Test
  void 映射行不能指向汇总科目() throws Exception {
    createSubject("1122", "应收账款", "ASSET", "DEBIT", false);
    createSubject("1122.01", "应收账款-境内", "ASSET", "DEBIT", true, "1122");
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_PAID","eventLabel":"订单收款",
         "lines":[{"subjectCode":"1122","direction":"DEBIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1},
                  {"subjectCode":"1122.01","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":2}]}
        """
            .formatted(BOOK);
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 映射规则与科目必须同一本账簿() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED",
         "lines":[{"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1}]}
        """
            .formatted(OTHER_BOOK);
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 停用科目后不能被新的映射行引用() throws Exception {
    var id = createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    http.perform(
            post("/finance/account-subjects/" + id + "/disable").header("Authorization", "session"))
        .andExpect(status().isOk());
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED",
         "lines":[{"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1}]}
        """
            .formatted(BOOK);
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 启用后不能改分录行_先停用再改() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    createSubject("6401", "主营业务成本", "COST", "DEBIT", true);
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED",
         "lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1},
                  {"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":2}]}
        """
            .formatted(BOOK);
    var id =
        UUID.fromString(
            json.readTree(
                    http.perform(
                            post("/finance/account-subjects/mappings")
                                .header("Authorization", "session")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText());
    http.perform(
            post("/finance/account-subjects/mappings/" + id + "/activate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    // 启用态下改行被拒。
    http.perform(
            put("/finance/account-subjects/mappings/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1}]}
                    """))
        .andExpect(status().isConflict());
    // 停用之后就能改。
    http.perform(
            post("/finance/account-subjects/mappings/" + id + "/deactivate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(
            put("/finance/account-subjects/mappings/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1}]}
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void 停用被启用规则引用的科目被拒() throws Exception {
    var revenue = createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    createSubject("6401", "主营业务成本", "COST", "DEBIT", true);
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED",
         "lines":[{"subjectCode":"6401","direction":"DEBIT","amountBasis":"ITEM_COST","sequence":1},
                  {"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":2}]}
        """
            .formatted(BOOK);
    var id =
        UUID.fromString(
            json.readTree(
                    http.perform(
                            post("/finance/account-subjects/mappings")
                                .header("Authorization", "session")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText());
    http.perform(
            post("/finance/account-subjects/mappings/" + id + "/activate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    // 科目正被已启用的规则引用 ⇒ 停用被拒（DB 守卫 + 服务层翻译成可读原因）。
    http.perform(
            post("/finance/account-subjects/" + revenue + "/disable")
                .header("Authorization", "session"))
        .andExpect(status().isConflict());
    // 停用规则之后就可以停用科目了。
    http.perform(
            post("/finance/account-subjects/mappings/" + id + "/deactivate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(
            post("/finance/account-subjects/" + revenue + "/disable")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 同一本账里一个业务事件只能有一条映射规则() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED",
         "lines":[{"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1}]}
        """
            .formatted(BOOK);
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 业务事件编码由用户自定义_不是写死的枚举() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    var body =
        """
        {"accountingBookId":"%s","eventType":"MY_CUSTOM_EVENT_42","eventLabel":"我自己定义的事件",
         "lines":[{"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1}]}
        """
            .formatted(BOOK);
    http.perform(
            post("/finance/account-subjects/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventType").value("MY_CUSTOM_EVENT_42"));
  }

  // ================================================================
  // 按科目编码查（下游 12.5.2.1 的集成点）
  // ================================================================

  @Test
  void 按科目编码查_命中唯一_没命中如实说没有() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    http.perform(
            get("/finance/account-subjects/by-code").param("subjectCode", "6001")
                .header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(true))
        .andExpect(jsonPath("$.subject.subjectCode").value("6001"))
        .andExpect(jsonPath("$.subject.balanceDirection").value("CREDIT"));
    http.perform(
            get("/finance/account-subjects/by-code").param("subjectCode", "9999")
                .header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.subject").doesNotExist());
  }

  // ================================================================
  // 列表：真分页 + 真筛选 + 汇总覆盖整个筛选结果集
  // ================================================================

  @Test
  void 列表是真分页真筛选且汇总覆盖整个筛选结果集() throws Exception {
    for (int i = 1; i <= 5; i++) {
      createSubject(
          String.format("600%d", i),
          "收入科目 " + i,
          "PROFIT_LOSS",
          "CREDIT",
          true);
    }
    createSubject("1001", "库存现金", "ASSET", "DEBIT", true);

    var page1 =
        json.readTree(
            http.perform(
                    get("/finance/account-subjects").param("page", "0").param("size", "2")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page1.get("items").size()).isEqualTo(2);
    assertThat(page1.get("total").asLong()).isEqualTo(6);
    // 汇总覆盖**整个筛选结果集**（6 笔），不是当前页（2 笔）。
    var summaryTotal = 0L;
    for (var node : page1.get("summaryByCategory")) summaryTotal += node.get("count").asLong();
    assertThat(summaryTotal).isEqualTo(6);

    var filtered =
        json.readTree(
            http.perform(
                    get("/finance/account-subjects").param("category", "ASSET")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(filtered.get("total").asLong()).isEqualTo(1);
    assertThat(filtered.get("items").get(0).get("subjectCode").asText()).isEqualTo("1001");
  }

  @Test
  void 停用科目后仍可查到_历史凭证要能引用它() throws Exception {
    var id = createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    http.perform(
            post("/finance/account-subjects/" + id + "/disable").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    var found =
        json.readTree(
            http.perform(
                    get("/finance/account-subjects/by-code").param("subjectCode", "6001")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(found.get("found").asBoolean()).isTrue();
    assertThat(found.get("subject").get("status").asText()).isEqualTo("DISABLED");
  }

  // ================================================================
  // 留痕：追加式
  // ================================================================

  @Test
  void 变更留痕是追加式的() throws Exception {
    var id = createSubject("1001", "库存现金", "ASSET", "DEBIT", true);
    http.perform(
            put("/finance/account-subjects/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"subjectName":"库存现金（改过名）","category":"ASSET","balanceDirection":"DEBIT","detail":true}
                    """))
        .andExpect(status().isOk());
    var detail =
        json.readTree(
            http.perform(
                    get("/finance/account-subjects/" + id).header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var events = detail.get("events");
    assertThat(events.size()).as("建科目 + 改字段都要留痕").isGreaterThanOrEqualTo(2);
    boolean sawNameChange = false;
    for (var event : events) {
      if ("subject_name".equals(event.get("fieldName").asText())) {
        sawNameChange = true;
        assertThat(event.get("fromValue").asText()).isEqualTo("库存现金");
        assertThat(event.get("toValue").asText()).isEqualTo("库存现金（改过名）");
      }
    }
    assertThat(sawNameChange).as("字段级留痕必须能看出改了什么").isTrue();

    // 运行角色对留痕表没有 UPDATE / DELETE 权 —— 事件只能追加。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "finance_app", "test-only")) {
      assertThatThrownBy(
              () -> {
                try (var ps =
                    c.prepareStatement(
                        "update finance_account_subject_event set reason = 'x' where tenant_id = ?")) {
                  ps.setObject(1, tenant);
                  ps.executeUpdate();
                }
              })
          .hasStackTraceContaining("permission denied");
    }
  }

  // ================================================================
  // 数值与时间：不是浮点、不是本地时间
  // ================================================================

  @Test
  void 税率是numeric而不是浮点_时间是timestamptz且写回零漂移() throws Exception {
    var columns =
        sql.fetch(
            "select column_name, data_type, numeric_precision, numeric_scale"
                + " from information_schema.columns where table_name = 'finance_account_subject'"
                + " and column_name in ('tax_rate')");
    assertThat(columns).hasSize(1);
    assertThat(columns.get(0).get("data_type", String.class)).isEqualTo("numeric");
    assertThat(columns.get(0).get("numeric_precision", Integer.class)).isEqualTo(9);
    assertThat(columns.get(0).get("numeric_scale", Integer.class)).isEqualTo(6);

    var timeColumns =
        sql.fetch(
            "select column_name, data_type from information_schema.columns"
                + " where table_name = 'finance_account_subject'"
                + " and column_name in ('created_at','updated_at')");
    assertThat(timeColumns).hasSize(2);
    for (var column : timeColumns) {
      assertThat(column.get("data_type", String.class)).isEqualTo("timestamp with time zone");
    }
    var mappingTime =
        sql.fetch(
            "select column_name, data_type from information_schema.columns"
                + " where table_name = 'finance_account_subject_event_mapping'"
                + " and column_name = 'effective_from'");
    assertThat(mappingTime.get(0).get("data_type", String.class)).isEqualTo("timestamp with time zone");
  }

  @Test
  void 生效时间写回逐位相等不偏八小时() throws Exception {
    createSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", true);
    var effectiveFrom = "2026-10-01T08:30:00Z";
    var body =
        """
        {"accountingBookId":"%s","eventType":"ORDER_SHIPPED","effectiveFrom":"%s",
         "lines":[{"subjectCode":"6001","direction":"CREDIT","amountBasis":"ORDER_ITEM_AMOUNT","sequence":1}]}
        """
            .formatted(BOOK, effectiveFrom);
    var response =
        http.perform(
                post("/finance/account-subjects/mappings")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var stored =
        fetchOneInTenant(
            "select effective_from from finance_account_subject_event_mapping where event_type = 'ORDER_SHIPPED'");
    var value = stored.get("effective_from", OffsetDateTime.class);
    assertThat(value).isNotNull();
    assertThat(value.toInstant())
        .as("写回的时刻必须与传入的时刻逐位相等（不能偏 −28800s）")
        .isEqualTo(OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.UTC).toInstant());
    // 接口返回的也是同一时刻。
    // ⚠️ 比的是**时刻**不是字符串：JSON 里的偏移量取决于 JVM 时区（东八区会写成 16:30+08:00），
    //   拿字符串前缀比会把「同一时刻」误判成「偏了 8 小时」。
    var returned = OffsetDateTime.parse(json.readTree(response).get("effectiveFrom").asText());
    assertThat(returned.toInstant())
        .isEqualTo(OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.UTC).toInstant());
  }

  // ================================================================
  // 能力声明
  // ================================================================

  @Test
  void 能力声明如实报出下游缺口() throws Exception {
    var node =
        json.readTree(
            http.perform(
                    get("/finance/account-subjects/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var gaps = node.get("downstreamGaps");
    var modules = new java.util.ArrayList<String>();
    for (var gap : gaps) modules.add(gap.get("module").asText());
    assertThat(modules).contains("12.5.1.2", "12.5.1.3", "12.5.2.1", "12.5.2.2");
    for (var gap : gaps) {
      assertThat(gap.get("available").asBoolean()).as("缺口必须如实报成不可用").isFalse();
    }
  }
}
