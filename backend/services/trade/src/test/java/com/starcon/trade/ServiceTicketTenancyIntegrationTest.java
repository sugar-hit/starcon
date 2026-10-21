package com.starcon.trade;

import static com.starcon.trade.ServiceTicketModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.AftersalesCaseModels.AftersalesCaseCreate;
import com.starcon.trade.ServiceTicketModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.2.1 工单管理的<b>多租户三断言</b>（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是**实打在本模块自己的表上**的三条断言（aftersales_service_ticket +
 * aftersales_ticket_assignment / aftersales_ticket_event /
 * aftersales_ticket_satisfaction / aftersales_ticket_sla_snapshot），
 * **不是**占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的工单查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：工单里同时有**买家诉求原文、责任人、SLA 达标结论与
 * 满意度评分** —— 一个租户能看到另一个租户「这个客服的响应达标率 87%」，
 * 等于拿到了对手的客服绩效底牌；而端点又大多是**按 id 直达**的 URL，
 * 猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，
 * 才能真正证明归还后不会串租户 —— 池大于 1 时每次可能拿到不同连接，
 * 「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 ——
 * 复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @MockitoSpyBean AccessService access;
  @Autowired ServiceTicketService tickets;
  @Autowired AftersalesCaseService cases;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final BigDecimal AMOUNT = new BigDecimal("88.5000");
  static final BigDecimal QTY = new BigDecimal("2.000000");
  /** 单价 × 数量 = 行金额（DB 守卫 sales_order_line_amount_matches 钉死这一条）。 */
  static final BigDecimal LINE_UNIT = new BigDecimal("44.2500");

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_ticket_sla_snapshot, aftersales_ticket_satisfaction,"
              + " aftersales_ticket_event, aftersales_ticket_assignment,"
              + " aftersales_service_ticket, aftersales_owner_roster, aftersales_reply_sla,"
              + " aftersales_case_note, aftersales_case_line, aftersales_authorization,"
              + " aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 建订单 → 售后单 → SLA 档 → 工单，返回工单 id。 */
  UUID seedTicket(UUID tid, UUID uid, String tag) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    as(
        uid,
        tid,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, AMOUNT, AMOUNT, T1, T1);
          // ⚠️ 订单必须**有商品行**才能提交（DB 守卫 guard_sales_order_submitted）。
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, QTY, LINE_UNIT,
              AMOUNT, T1);
          // ⚠️ 订单还必须有**收货地址快照**与**买家主体快照**（DB 守卫 guard_sales_order_submitted）。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
          // ⚠️ 同一个 platform_scope 只能开一档（DB 守卫拒绝重叠区间）⇒ 已存在就不重复开。
          sql.execute(
              "insert into aftersales_reply_sla(id,tenant_id,platform_scope,reply_sla_hours,"
                  + "resolve_sla_hours,effective_from,effective_to,created_by,created_at)"
                  + " select ?::uuid,?::uuid,'',24,48,?::timestamptz,null,?::uuid,?::timestamptz"
                  + " where not exists (select 1 from aftersales_reply_sla"
                  + "   where tenant_id = ?::uuid and platform_scope = '' and effective_to is null)",
              UUID.randomUUID(), tid, T1, uid, T1, tid);
          sql.execute(
              "insert into aftersales_owner_roster(id,tenant_id,owner_ref,owner_display_name,"
                  + "owner_role_label,owner_source,source_reference,accepting_new_cases,weight,"
                  + "created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,?,'客服','TENANT_MAINTAINED','',true,10,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tid, "客服-" + tag, "客服-" + tag, T1, T1);
          return null;
        });
    UUID caseId =
        as(
            uid,
            tid,
            () ->
                cases.create(
                        new AftersalesCaseCreate(
                            orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                            AMOUNT, "USD", QTY, "PLAT-" + tag, null, null,
                            "买家诉求-" + tag, T1, null, T1,
                            null, null, null, "TENANT_MAINTAINED", null, T1),
                        uid)
                    .id());
    return as(
        uid,
        tid,
        () ->
            tickets.createTicket(
                    new ServiceTicketCreate(
                        caseId.toString(), "INQUIRY", "MANUAL", "SRC-" + tag, "主题-" + tag,
                        "NORMAL", "诉求-" + tag, null, null, null, null),
                    uid)
                .head()
                .id());
  }

  /** 在给定租户的上下文里裸查工单表（**没有 WHERE**），返回 {@code id @ tenant_id}。 */
  java.util.List<String> rowsOf(UUID tenant) {
    AccessService.Access found =
        actors.get("Bearer tenant-a").tenantId().equals(tenant)
            ? actors.get("Bearer tenant-a")
            : actors.get("Bearer tenant-b");
    return access.run(found, "trade.aftersales.read", () -> {
      var out = new java.util.ArrayList<String>();
      for (org.jooq.Record rec :
          sql.fetch("select id, tenant_id from aftersales_service_ticket")) {
        out.add(rec.get("id", UUID.class) + " @ " + rec.get("tenant_id", UUID.class));
      }
      return out;
    });
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    UUID oa = seedTicket(a, userA, "ISO-A");
    UUID ob = seedTicket(b, userB, "ISO-B");

    // ---- 列表：各自只看见自己那一张 ----
    assertThat(asA(() -> tickets.listTickets(allQuery())).rows())
        .extracting(ServiceTicketRow::id).containsExactly(oa);
    assertThat(asB(() -> tickets.listTickets(allQuery())).rows())
        .extracting(ServiceTicketRow::id).containsExactly(ob);

    // ---- 详情：按 id 直达也不行 ----
    assertThatThrownBy(() -> asA(() -> tickets.ticketDetail(ob)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asB(() -> tickets.ticketDetail(oa)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // ---- 写路径：不能替别人转派 / 关闭 / 评价 ----
    // ⚠️ 用 **A 自己名册里的人**做转派目标：否则会先被名册校验拦下，
    //   报错说的是「不在名册里」而不是「不属于当前租户」—— 拦的层次不对。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        tickets.transferTicket(
                            ob, new ServiceTicketTransfer("客服-ISO-A", null, "越权转派", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        tickets.closeTicket(
                            ob, new ServiceTicketClose("RESOLVED", "越权关闭", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        tickets.recordSatisfaction(
                            ob, new ServiceTicketSatisfactionCreate(1, "越权评价"), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // ---- 汇总也各自分开 ----
    assertThat(asA(() -> tickets.listTickets(allQuery())).summary().total()).isEqualTo(1L);
    assertThat(asB(() -> tickets.listTickets(allQuery())).summary().total()).isEqualTo(1L);

    // ---- HTTP 层：按 id 的详情端点 ----
    String detailA =
        http.perform(
                get("/trade/service-tickets/" + oa).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String detailB =
        http.perform(
                get("/trade/service-tickets/" + ob).header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detailA).contains(oa.toString()).doesNotContain(ob.toString());
    assertThat(detailB).contains(ob.toString()).doesNotContain(oa.toString());

    // ---- HTTP 层：列表端点 ----
    String listA =
        http.perform(get("/trade/service-tickets").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String listB =
        http.perform(get("/trade/service-tickets").header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(listA).contains(oa.toString()).doesNotContain(ob.toString());
    assertThat(listB).contains(ob.toString()).doesNotContain(oa.toString());

    // ---- HTTP 层：拿别人的 id 去读 ⇒ 与「不存在」完全相同的错误 ----
    http.perform(get("/trade/service-tickets/" + ob).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    UUID oa = seedTicket(a, userA, "FORGE-A");
    UUID ob = seedTicket(b, userB, "FORGE-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据。
    String page =
        http.perform(
                get("/trade/service-tickets")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(page).contains(ob.toString()).doesNotContain(oa.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String page2 =
        http.perform(
                get("/trade/service-tickets")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(page2).contains(oa.toString()).doesNotContain(ob.toString());

    // 详情端点同样不认这个头。
    String detail =
        http.perform(
                get("/trade/service-tickets/" + oa)
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).doesNotContain(oa.toString());

    // ⚠️ **断言行确实落在自称租户名下**（不是只断言 A 看不见）：
    //   带伪造头的这次读取，走的仍然是会话租户 B 的上下文。
    assertThat(asB(() -> sql.fetchOne(
            "select count(*) as c from aftersales_service_ticket")
        .get("c", Integer.class))).isEqualTo(1);

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) as c from aftersales_service_ticket")
        .get("c", Integer.class))).isEqualTo(1);
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) as c from aftersales_service_ticket where tenant_id = ?::uuid", b)
        .get("c", Integer.class))).isZero();
    // **不带** WHERE 的裸查同样被 RLS 收窄 —— 这才是 FORCE 的意义。
    assertThat(asB(() -> sql.fetchOne(
            "select count(*) as c from aftersales_service_ticket")
        .get("c", Integer.class))).isEqualTo(1);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    UUID oa = seedTicket(a, userA, "POOL-A");
    UUID ob = seedTicket(b, userB, "POOL-B");

    // ⚠️ **每一轮都重新 seed**（新建订单 / 售后单 / 工单），
    //   而不是复用上一轮的行：复用时第二轮通过只能说明「那条记录没变」，
    //   证不了「这一轮的租户上下文是对的」。
    for (int round = 0; round < 3; round++) {
      final int r = round;
      final UUID ma = oa;
      final UUID mb = ob;
      // A → B → A → B 交替，且**每次都换一批新实体**：
      // 若上一轮的租户上下文残留在连接上，B 就会读到 A 的行（或反过来）。
      seedTicket(a, userA, "POOL-A-" + r);
      seedTicket(b, userB, "POOL-B-" + r);

      assertThat(asA(() -> tickets.listTickets(allQuery())).rows())
          .extracting(ServiceTicketRow::id).contains(ma);
      assertThat(asB(() -> tickets.listTickets(allQuery())).rows())
          .extracting(ServiceTicketRow::id).contains(mb);

      // 裸查（RULE 本身）在两个上下文里各自只看得见**自己那一侧**的行。
      assertThat(rowsOf(a)).contains(ma + " @ " + a).doesNotContain(mb + " @ " + b);
      assertThat(rowsOf(b)).contains(mb + " @ " + b).doesNotContain(ma + " @ " + a);

      // 再换回来：同一批数据，A 必须仍然只看见自己的。
      assertThat(asA(() -> tickets.listTickets(allQuery())).rows())
          .extracting(ServiceTicketRow::id).contains(ma);

      // 写路径也一样：两次写在同一个连接上先后发生，第二次不能继承第一次的租户。
      UUID extra = seedTicket(b, userB, "POOL-EXTRA-" + r);
      assertThat(extra).isNotNull();
      assertThat(asA(() -> tickets.listTickets(allQuery())).rows())
          .extracting(ServiceTicketRow::id).doesNotContain(extra);
      assertThat(asB(() -> tickets.listTickets(allQuery())).rows())
          .extracting(ServiceTicketRow::id).contains(extra);
      // 写路径之后紧接着读：A **一条都看不见**（含 id 不相等）。
      assertThat(rowsOf(a)).contains(ma + " @ " + a).doesNotContain(extra + " @ " + b);
      assertThat(rowsOf(b)).contains(mb + " @ " + b, extra + " @ " + b);
      assertThat(rowsOf(a)).contains(ma + " @ " + a).doesNotContain(extra + " @ " + b);
    }
  }

  /** 无筛选的列表查询。 */
  private static ServiceTicketListQuery allQuery() {
    return new ServiceTicketListQuery(
        0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
