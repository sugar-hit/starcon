package com.starcon.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.1.2.2 邮件规则三个测试类共用的<b>夹具</b>。
 *
 * <p><b>为什么三个测试类共用一个容器</b>：本车道的三个测试类
 * （{@link EmailRuleIntegrationTest} / {@link EmailRuleRulesTest} /
 * {@link EmailRuleTenancyIntegrationTest}）跑的是**同一个业务库**上的**同一批表** ——
 * 每个类各起一个 PG 容器只会把同一套迁移跑三遍，容器数翻倍、启动时间翻倍，
 * 而断言强度一点不变。共享容器不改变任何一条断言的强度（角色仍然是
 * {@code NOSUPERUSER NOBYPASSRLS}，表仍然是 FORCE RLS 的真实表）。
 *
 * <p>⚠️ 共享容器的代价：三个测试类共用一个库 ⇒ **类之间必须自己清干净**。
 * 每个测试类的 {@code @BeforeEach} 都会 TRUNCATE 自己用到的表
 * （用迁移用户连接执行 —— RLS 对超管不生效，所以不需要先绑租户）。
 */
final class EmailRuleTestSupport {

  private EmailRuleTestSupport() {}

  /** 本车道三个测试类共用的 PostgreSQL 16 容器。 */
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** identity 桩：按 bearer 返回已认证成员关系与权限。 */
  static final Map<String, AccessService.Access> ACTORS = new ConcurrentHashMap<>();

  static HttpServer identity;

  /** 本模块的权限码。 */
  static final List<String> PERMISSIONS =
      List.of("crm.emailrule.read", "crm.emailrule.update");

  static {
    PG.start();
    try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = ACTORS.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
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

  /** 建一个已认证成员（权限组必须同时含动作与对应的 {@code .read}）。 */
  static AccessService.Access actor(
      UUID userId, UUID tenantId, String tenantName, List<String> permissions) {
    return new AccessService.Access(
        userId,
        tenantId,
        tenantName,
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  /** 用迁移用户（超管）连接执行一条 SQL —— RLS 对超管不生效，不需要先绑租户。 */
  static void asMigrationUser(String sql) {
    try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 清空本车道用到的全部表（类之间共用一个库 ⇒ 必须自己清干净）。 */
  static void truncate() {
    asMigrationUser(
        "truncate table crm_email_rule_assignment, crm_email_rule_hit,"
            + " crm_email_ticket_tag_assignment, crm_email_ticket_tag, crm_email_rule,"
            + " crm_message_classification_feedback, crm_message_classification,"
            + " crm_message_attachment, crm_message_intent, crm_message,"
            + " crm_buyer_email_attachment, crm_buyer_email_send_record, crm_buyer_email_template,"
            + " crm_buyer_email");
  }

  /**
   * 插一封**收到的**邮件（15.1.2.1 的表，本模块只读）。
   *
   * @param sourceMessageId 关联的站内信主键（软引用，用于关联上游分类结果）
   */
  static void insertEmail(
      DSLContext sql,
      com.starcon.tenancy.TenantTransactions tenants,
      UUID tenant,
      UUID id,
      String buyerEmail,
      String subject,
      String body,
      UUID sourceMessageId,
      OffsetDateTime receivedAt) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_buyer_email"
                  + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,"
                  + " buyer_ref,buyer_name,buyer_email,subject,body,conversation_key,"
                  + " source_message_id,sales_order_no,external_message_id,received_at,status,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'测试店铺','AMAZON','INBOUND','MANUAL_ENTRY',"
                  + " ?,'测试买家',?,?,?,?,?,'SO-T','',?::timestamptz,'RECEIVED',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "BE-" + id.toString().substring(0, 8),
              UUID.randomUUID(),
              "buyer-" + tenant.toString().substring(0, 8),
              buyerEmail,
              subject,
              body,
              "shop|buyer|so",
              sourceMessageId,
              receivedAt,
              receivedAt,
              receivedAt);
          return null;
        });
  }

  /** 插一条站内信（15.1.1.1 的表），供分类结果关联。 */
  static void insertMessage(
      DSLContext sql,
      com.starcon.tenancy.TenantTransactions tenants,
      UUID tenant,
      UUID id,
      String subject,
      String body) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,priority,read_status,"
                  + " platform_time,attachment_count,source,created_at,updated_at)"
                  + " values (?,?,?,?,'测试店铺','AMAZON',?,'SO-T',?,?,'INBOUND',"
                  + " 'BUYER_MESSAGE','NORMAL','UNREAD',?::timestamptz,0,'MANUAL_ENTRY',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "MSG-" + id.toString().substring(0, 8),
              UUID.randomUUID(),
              "buyer-" + tenant.toString().substring(0, 8),
              subject,
              body,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 插一条分类结论（15.1.1.3 的表，本模块只读）。 */
  static void insertClassification(
      DSLContext sql,
      com.starcon.tenancy.TenantTransactions tenants,
      UUID tenant,
      UUID id,
      UUID messageId,
      String label) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message_classification"
                  + "(id,tenant_id,classification_no,message_id,label,suggested_priority,"
                  + " manual_priority,effective_priority,priority_conflict,auto_reply_eligible,"
                  + " matched_rule_id,matched_rule_code,matched_rule_name,match_basis,"
                  + " classifier_version,classified_at,created_at,updated_at)"
                  + " values (?,?,?,?,?,'NORMAL','NORMAL','NORMAL',false,false,"
                  + " null,'','分类规则','{}'::jsonb,'RULE_MATCH_V1',?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "CLS-" + id.toString().substring(0, 8),
              messageId,
              label,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }
}
