package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 11.1.1.2 售后单详情。
 *
 * <p>跑的是蓝图职责句里的九个区块与那条加粗句
 * 「<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础」，
 * 外加三条硬性要求：金额数量全 BigDecimal、时间列写回的是同一绝对时刻、未交付的下游如实报 false。
 *
 * <p>⚠️ **时间夹具刻意用 +08:00 非 UTC 偏移**：{@code Timestamp.valueOf(LocalDateTime)} 会静默偏
 *   −28800s，只有拿一个非 UTC 偏移的时刻写进去再读回来断言，才能测出这个偏移。
 *   本类对每个写入的时间列都断言**写回值**，不是只断言「没抛异常」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesCaseDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
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
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-main", actor(user, tenant));
      actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-03-02T09:30:00+08:00");
  static final OffsetDateTime T3 = OffsetDateTime.parse("2026-03-03T10:15:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_case_line, aftersales_message, aftersales_attachment,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  <T> T asTenant(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  <T> T asOther(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-other"), "trade.aftersales.write", work);
  }

  /** 夹具：一张真实订单（带商品行 / 地址快照 / 客户快照）+ 一张真实售后单。 */
  record Fixture(UUID caseId, UUID orderId, UUID lineId) {}

  Fixture seed(String orderNo, String amount, String buyerText) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    asTenant(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,"
                  + "0,0,10.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, T1, T1, T1);
          // ⚠️ 行小计必须等于头汇总（V99 的延迟约束触发器）：2 × 5.0000 = 10.0000。
          //   写成 2 × 10.0000 而头留 10.0000 会被触发器拒，错误文案说的是「头与行两个答案」。
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',2,5.0000,0,0,10.0000,"
                  + "'',?::timestamptz)",
              lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo, T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + orderNo, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家" + orderNo, T1);
          return null;
        });
    UUID caseId =
        asTenant(
            () ->
                cases.create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId,
                            "RETURN_REFUND",
                            "QUALITY_DEFECT",
                            "外箱完好但里面碎了",
                            new BigDecimal(amount),
                            "USD",
                            new BigDecimal("2.000000"),
                            "PLAT-" + orderNo,
                            "https://example.com/case/1",
                            T1,
                            buyerText,
                            // 早于「现在」⇒ OVERDUE
                            T1,
                            null,
                            T1,
                            user.toString(),
                            "客服小林",
                            "售后专员",
                            "TENANT_MAINTAINED",
                            null,
                            T1),
                        user)
                    .id());
    return new Fixture(caseId, orderId, lineId);
  }

  // ==========================================================================
  // 迁移落地：三张新表 + RLS 强制 + 运行角色真能读写
  // ==========================================================================
  @Test
  void 三张新表都落地了而且rls是强制的() {
    for (String table :
        List.of("aftersales_case_line", "aftersales_message", "aftersales_attachment")) {
      var row = sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V157 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table).get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'INSERT')", table).get(0, Boolean.class))
          .as("trade_app 必须真能往 " + table + " 写")
          .isTrue();
    }
    // 三张表全部 append-only：改掉或删掉一行就是毁掉纠纷证据。
    for (String table : List.of("aftersales_case_line", "aftersales_message", "aftersales_attachment")) {
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'UPDATE')", table).get(0, Boolean.class))
          .as(table + " 的 UPDATE 权限必须被收回（纠纷证据不可改写）").isFalse();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'DELETE')", table).get(0, Boolean.class))
          .as(table + " 的 DELETE 权限必须被收回").isFalse();
    }
  }

  // ==========================================================================
  // 详情聚合：九个区块
  // ==========================================================================
  @Test
  void 详情把原订单与买家信息一起带出来() {
    var f = seed("MO-AGG", "18.5000", "买家原话：收到就碎了");
    var d = asTenant(() -> details.detail(f.caseId()));

    assertThat(d.header().caseNo()).isNotBlank();
    assertThat(d.header().caseTypeLabel()).isEqualTo("退货退款");
    assertThat(d.header().buyerRequest()).isEqualTo("买家原话：收到就碎了");
    // 金额与数量原样返回（BigDecimal，禁浮点）
    assertThat(d.header().requestedAmount()).isEqualByComparingTo("18.5000");
    assertThat(d.header().requestedQuantity()).isEqualByComparingTo("2.000000");

    // 原订单信息来自 sales_order 簇（V99），不是复制品
    assertThat(d.order()).isNotNull();
    assertThat(d.order().orderNo()).isEqualTo("MO-AGG");
    assertThat(d.order().orderSource()).isEqualTo("AMAZON");
    assertThat(d.order().customerName()).isEqualTo("买家MO-AGG");
    assertThat(d.order().recipientName()).isEqualTo("买家MO-AGG");
    assertThat(d.order().addressLine()).contains("LOS ANGELES").contains("100 Main St");
    // 店铺维度不可得：恒为 null 并在 capabilities 里报 available=false
    assertThat(d.order().platformShopCode()).isNull();
    assertThat(d.platform().platformShopCodeAvailable()).isFalse();

    // 订单行带 SKU 快照，且带 involved 标记
    assertThat(d.orderLines()).hasSize(1);
    assertThat(d.orderLines().get(0).skuCode()).isEqualTo("SKU-MO-AGG");
    assertThat(d.orderLines().get(0).specSnapshot()).isEqualTo("黑色 / L");
    assertThat(d.orderLines().get(0).quantity()).isEqualByComparingTo("2.000000");
    assertThat(d.orderLines().get(0).involved()).isFalse();

    // 平台侧状态：受理号、受理时刻、两个时限都是真数据
    assertThat(d.platform().platformCaseNo()).isEqualTo("PLAT-MO-AGG");
    assertThat(d.platform().platformCaseUrl()).isEqualTo("https://example.com/case/1");
    assertThat(d.platform().replyDueAt()).isNotNull();
    assertThat(d.platform().timelinessState()).isEqualTo("OVERDUE");
    // 平台最终判定属 2 平台同步 + 11.4（未交付）⇒ 恒为 null 且报 false
    assertThat(d.platform().platformDecisionStatus()).isNull();
    assertThat(d.platform().decisionStatusAvailable()).isFalse();
    assertThat(d.platform().decisionStatusOwnerModule()).isNotBlank();
  }

  @Test
  void 已结案的单剩余秒数是null而不是0() {
    var f = seed("MO-DONE", "10.0000", "x");
    asTenant(
        () -> {
          sql.execute(
              "update aftersales_case set status='RESOLVED', resolved_at=?::timestamptz where id=?::uuid",
              T3,
              f.caseId());
          return null;
        });
    var d = asTenant(() -> details.detail(f.caseId()));
    assertThat(d.header().terminal()).isTrue();
    // 「剩余 0 秒」会被读成「现在就超时了」；null 才是「倒计时已停止」
    assertThat(d.header().remainingSeconds()).isNull();
    assertThat(d.platform().remainingSeconds()).isNull();
    assertThat(d.header().timelinessState()).isEqualTo("RESOLVED");
  }

  // ==========================================================================
  // 加粗句：时间线对齐「买家说了什么」与「我们做了什么」
  // ==========================================================================
  @Test
  void 时间线按发生时刻排序并标出说话方() {
    var f = seed("MO-TL", "10.0000", "买家原话");
    // 交错登记：T1 买家 → T2 我方 → T3 买家。**sentAt 刻意与录入顺序相反**。
    asTenant(
        () -> {
          // 最后登记的这条 sentAt **最早**：补录的历史消息必须落在它真实发生的位置上
          details.addMessage(
              f.caseId(),
              new AftersalesCaseDetailModels.AftersalesMessageCreate(
                  "BUYER_TO_US", "PLATFORM", "最早：包裹到时就是破的", "EXT-1",
                  "TENANT_MAINTAINED", null, T1, T1));
          details.addAttachment(
              f.caseId(),
              new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
                  "PRODUCT_PHOTO", "box.jpg", "image/jpeg", "obj://case/box.jpg", 1024L,
                  "外箱照片", "BUYER", "TENANT_MAINTAINED", null, T1),
              user);
          details.addLine(
              f.caseId(),
              new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                  f.lineId(), new BigDecimal("2.000000"), new BigDecimal("9.0000"), "USD", "两件都要退"),
              user);
          details.addNote(
              f.caseId(), new AftersalesCaseDetailModels.AftersalesNoteCreate("已联系买家", null));
          details.addMessage(
              f.caseId(),
              new AftersalesCaseDetailModels.AftersalesMessageCreate(
                  "US_TO_BUYER", "EMAIL", "已收到，我们先赔付", null,
                  "TENANT_MAINTAINED", null, T3, T3));
          return null;
        });

    var d = asTenant(() -> details.detail(f.caseId()));
    var tl = d.timeline();

    // 顺序按 occurred_at 升序 ⇒ 最后登记的 T1 消息必须在最前
    assertThat(tl).isNotEmpty();
    var times = tl.stream().map(AftersalesCaseDetailModels.AftersalesTimelineEvent::occurredAt).toList();
    var sorted = times.stream().sorted().toList();
    assertThat(times).as("时间线必须按发生时刻升序，而不是按录入顺序").isEqualTo(sorted);

    // T1 上同时有 MESSAGE 与 ATTACHMENT 两条（同刻按 kind 字母序排），
    // 所以**不能**假设第 0 条是 MESSAGE —— 那样断言的是巧合不是契约。
    assertThat(tl.get(0).occurredAt()).isEqualTo(T1);
    assertThat(tl.get(0).actorSide()).isEqualTo("BUYER");
    var firstMessage = tl.stream().filter(e -> "MESSAGE".equals(e.kind())).findFirst().orElseThrow();
    assertThat(firstMessage.body()).isEqualTo("最早：包裹到时就是破的");
    assertThat(firstMessage.actorSide()).isEqualTo("BUYER");
    assertThat(firstMessage.occurredAt()).isEqualTo(T1);
    // T1 的消息必须排在 T3 的我方回复之前（补录的历史消息落在它真实发生的位置上）
    var ourReply = tl.stream()
        .filter(e -> "MESSAGE".equals(e.kind()) && "US".equals(e.actorSide()))
        .findFirst().orElseThrow();
    assertThat(firstMessage.occurredAt()).isBefore(ourReply.occurredAt());

    // 说话方必须齐全：买家 / 我方 / 系统三类都在
    assertThat(tl.stream().map(AftersalesCaseDetailModels.AftersalesTimelineEvent::actorSide).collect(java.util.stream.Collectors.toSet()))
        .contains("BUYER", "US", "SYSTEM");
    assertThat(d.buyerEventCount()).as("买家侧事件条数").isGreaterThanOrEqualTo(2);
    assertThat(d.ourEventCount()).as("我方侧事件条数").isGreaterThanOrEqualTo(2);

    // 平台受理也是一个独立事件（PLATFORM 侧）
    assertThat(tl.stream().anyMatch(e -> "PLATFORM_RECEIVED".equals(e.kind()))).isTrue();
    // 商品行 / 凭证 / 备注都在
    assertThat(tl.stream().map(AftersalesCaseDetailModels.AftersalesTimelineEvent::kind))
        .contains("LINE_ADDED", "ATTACHMENT", "NOTE_ADDED", "CASE_RECEIVED");

    // 写入的时间值要**原样读回**（+08:00 夹具能测出 Timestamp.valueOf 的 −28800s 偏移）
    var msg = asTenant(() -> details.addMessage(
        f.caseId(),
        new AftersalesCaseDetailModels.AftersalesMessageCreate(
            "BUYER_TO_US", "CHAT", "偏移夹具", null, null, null, T2, T2)));
    assertThat(msg.sentAt()).isEqualTo(T2);
    assertThat(msg.sentAt().toInstant()).isEqualTo(T2.toInstant());
    assertThat(msg.capturedAt()).isEqualTo(T2);
  }

  @Test
  void 时间线是真分页() {
    var f = seed("MO-TLP", "10.0000", "x");
    asTenant(
        () -> {
          for (int i = 0; i < 6; i++) {
            details.addNote(
                f.caseId(),
                new AftersalesCaseDetailModels.AftersalesNoteCreate("备注-" + i, null));
          }
          return null;
        });
    var all = asTenant(() -> details.timelinePage(
        f.caseId(), new AftersalesCaseDetailModels.AftersalesTimelineQuery(0, 50)));
    var total = all.total();
    // 构成：受理 1 + 平台受理 1 + 责任人 1 + 备注 6。
    // 按 kind 逐类核对而不是写死一个总数 —— 写死的总数在多加一种事件源时会静默失效。
    var kinds = all.events().stream()
        .map(AftersalesCaseDetailModels.AftersalesTimelineEvent::kind)
        .collect(java.util.stream.Collectors.toList());
    assertThat(kinds.stream().filter("CASE_RECEIVED"::equals).count()).isEqualTo(1);
    assertThat(kinds.stream().filter("PLATFORM_RECEIVED"::equals).count()).isEqualTo(1);
    assertThat(kinds.stream().filter("OWNER_ASSIGNED"::equals).count()).isEqualTo(1);
    assertThat(kinds.stream().filter("NOTE_ADDED"::equals).count()).isEqualTo(6);
    assertThat(total).isEqualTo(kinds.size());

    var first = asTenant(() -> details.timelinePage(
        f.caseId(), new AftersalesCaseDetailModels.AftersalesTimelineQuery(0, 3)));
    var second = asTenant(() -> details.timelinePage(
        f.caseId(), new AftersalesCaseDetailModels.AftersalesTimelineQuery(1, 3)));
    assertThat(first.events()).hasSize(3);
    assertThat(second.events()).hasSize(3);
    assertThat(first.size()).isEqualTo(3);
    assertThat(second.page()).isEqualTo(1);
    assertThat(second.total()).isEqualTo(total);
    // 两侧计数是**全量**的（与分页无关）：买家侧 + 我方侧 ≤ 总数，且与全量页逐类一致
    assertThat(all.buyerEventCount()).isEqualTo(all.events().stream()
        .filter(e -> "BUYER".equals(e.actorSide())).count());
    assertThat(all.ourEventCount()).isEqualTo(all.events().stream()
        .filter(e -> "US".equals(e.actorSide())).count());
    assertThat(all.buyerEventCount() + all.ourEventCount()).isLessThanOrEqualTo(all.total());
    // 两页不重不漏。
    // ⚠️ 时间线事件的 **id 允许为 null**：受理 / 平台受理 / 结案三类事件没有对应的
    //   单行主键（它们是聚合事实，不是某张表的一行），真正的指向由 reference 承担。
    //   所以判重必须用 (kind, occurredAt, summary) 这个三元组，而不是 row id ——
    //   拿 id 判重会把「三个 null」误判成重复。
    //   同一事务里追加的多条备注还共享同一个 created_at（PG 的 now() 是事务局部的），
    //   所以三元组里必须带上 reference（各产物主键）才唯一。
    var key = java.util.stream.Stream.concat(first.events().stream(), second.events().stream())
        .map(e -> e.kind() + "|" + e.occurredAt() + "|" + e.reference() + "|" + e.summary())
        .toList();
    assertThat(key).doesNotHaveDuplicates();
    assertThat(key).hasSize(6);
  }

  // ==========================================================================
  // 商品行与数量
  // ==========================================================================
  @Test
  void 登记商品行后订单行的involved为真且详情带出行级数量与金额() {
    var f = seed("MO-LINE", "18.0000", "x");
    var line = asTenant(
        () ->
            details.addLine(
                f.caseId(),
                new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                    f.lineId(), new BigDecimal("1.000000"), new BigDecimal("9.0000"), "USD", "只有一件碎了"),
                user));

    assertThat(line.quantity()).isEqualByComparingTo("1.000000");
    assertThat(line.requestedAmount()).isEqualByComparingTo("9.0000");
    assertThat(line.currency()).isEqualTo("USD");
    // SKU 从 sales_order_line 读（V99 快照），不在本表复制
    assertThat(line.skuCode()).isEqualTo("SKU-MO-LINE");
    assertThat(line.specSnapshot()).isEqualTo("黑色 / L");

    var d = asTenant(() -> details.detail(f.caseId()));
    assertThat(d.lines()).hasSize(1);
    assertThat(d.orderLines().get(0).involved()).isTrue();
    assertThat(d.orderLines().get(0).aftersalesQuantity()).isEqualByComparingTo("1.000000");
    assertThat(d.orderLines().get(0).aftersalesAmount()).isEqualByComparingTo("9.0000");
  }

  @Test
  void 商品行的三条硬规则由db触发器钉死() {
    var f = seed("MO-GUARD", "18.0000", "x");
    // ① 跨订单的商品行必须被拒：另建一张订单并取它的商品行
    UUID otherOrder = UUID.randomUUID();
    UUID otherLine = UUID.randomUUID();
    asTenant(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','DRAFT',null,10.0000,0,0,0,0,"
                  + "10.0000,0,?::timestamptz,?::timestamptz)",
              otherOrder, tenant, "MO-OTHER", "EXT-O", T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,'别的单的商品',1,10.0000,0,0,10.0000,'',"
                  + "?::timestamptz)",
              otherLine, tenant, otherOrder, UUID.randomUUID(), "SKU-O", T1);
          return null;
        });
    // 对照组：证明上面这条插入语句本身没写坏（同一条语句、换成同订单的行就能成功）
    assertThat(asTenant(() -> details.addLine(
            f.caseId(),
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                f.lineId(), new BigDecimal("1.000000"), null, null, "对照组"),
            user).quantity()))
        .isEqualByComparingTo("1.000000");
    // 绕过服务层直写：跨订单的行仍然被 DB 挡住
    assertThatThrownBy(
            () ->
                asTenant(
                    () -> {
                      sql.execute(
                          "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,"
                              + " quantity,requested_amount,currency,buyer_note,created_at)"
                              + " values(?,?,?,?,1.000000,null,null,'',?::timestamptz)",
                          UUID.randomUUID(), tenant, f.caseId(), otherLine, T1);
                      return null;
                    }))
        .hasMessageContaining("不属于本售后单所关联的订单");

    // ② 数量累计不得超过单头申请数量（申请 2.000000，已登记 1.000000，再登记 2.000000 要被拒）
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addLine(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                                f.lineId(), new BigDecimal("2.000000"), null, null, "超量"),
                            user)))
        .hasMessageContaining("超过售后单申请数量");

    // ③ 换货 / 补发 / 维修类不许带金额
    UUID exOrder = UUID.randomUUID();
    UUID exLine = UUID.randomUUID();
    UUID exCase = asTenant(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,"
                  + "0,0,10.0000,0,?::timestamptz,?::timestamptz)",
              exOrder, tenant, "MO-EX", "EXT-EX", T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,'换货商品',1,10.0000,0,0,10.0000,'',"
                  + "?::timestamptz)",
              exLine, tenant, exOrder, UUID.randomUUID(), "SKU-EX", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING','买家EX','+1-213-555-0100',"
                  + "'ex@example.com','','US','CA','LOS ANGELES','','90001','100 Main St','','','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, exOrder, T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,'买家EX','INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, exOrder, UUID.randomUUID(), "C-EX", T1);
          return cases.create(
                  new AftersalesCaseModels.AftersalesCaseCreate(
                      exOrder, "EXCHANGE", "SIZE_MISMATCH", "", new BigDecimal("0.0000"), "USD",
                      new BigDecimal("1.000000"), null, null, null, "换货", T1, null, T1,
                      null, null, null, null, null, null),
                  user)
              .id();
        });
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addLine(
                            exCase,
                            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                                exLine, new BigDecimal("1.000000"), new BigDecimal("9.0000"), "USD", null),
                            user)))
        .hasMessageContaining("不带金额");
  }

  // ==========================================================================
  // 沟通记录 / 凭证 / 备注
  // ==========================================================================
  @Test
  void 沟通记录校验方向渠道与来源枚举() {
    var f = seed("MO-MSG", "10.0000", "x");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addMessage(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesMessageCreate(
                                "SIDEWAYS", "EMAIL", "x", null, null, null, T1, null))))
        .hasMessageContaining("沟通方向");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addMessage(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesMessageCreate(
                                "BUYER_TO_US", "SMS", "x", null, null, null, T1, null))))
        .hasMessageContaining("沟通渠道");
    // 空正文会被拒：一条没有内容的对话记录在时间线上等于噪声
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addMessage(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesMessageCreate(
                                "BUYER_TO_US", "EMAIL", "   ", null, null, null, T1, null))))
        .hasMessageContaining("沟通正文不能为空");
    // 写成 MODULE_* 时必须给出 sourceReference
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addMessage(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesMessageCreate(
                                "BUYER_TO_US", "PLATFORM", "x", null,
                                "MODULE_2_PLATFORM_MESSAGE", null, T1, null))))
        .hasMessageContaining("sourceReference");

    // 两条都没有外部号的消息都要能进（PG 的 UNIQUE 把 '' 当普通值，
    // 靠 external_message_id_key 生成列把哨兵值补上才不冲突）
    var a = asTenant(() -> details.addMessage(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesMessageCreate(
            "BUYER_TO_US", "CHAT", "第一条", null, null, null, T1, T1)));
    var b = asTenant(() -> details.addMessage(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesMessageCreate(
            "BUYER_TO_US", "CHAT", "第二条", null, null, null, T2, T2)));
    assertThat(a.id()).isNotEqualTo(b.id());
    assertThat(a.directionLabel()).isEqualTo("买家 → 我方");
    // 我方发出的消息必须有作者
    assertThat(asTenant(() -> details.addMessage(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesMessageCreate(
            "US_TO_BUYER", "EMAIL", "我们回复", null, null, null, T2, T2))).authorId())
        .isEqualTo(user);
  }

  @Test
  void 凭证只存引用不存内容且必须给存储定位符() {
    var f = seed("MO-ATT", "10.0000", "x");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        details.addAttachment(
                            f.caseId(),
                            new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
                                "PRODUCT_PHOTO", "a.jpg", "image/jpeg", "  ", null, null, "BUYER",
                                null, null, T1),
                            user)))
        .hasMessageContaining("storageRef");

    var att = asTenant(() -> details.addAttachment(
        f.caseId(),
        new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
            "DELIVERY_PROOF", "signed.png", "image/png", "obj://case/signed.png", 2048L,
            "签收单", "BUYER", "TENANT_MAINTAINED", null, T1),
        user));
    assertThat(att.evidenceKindLabel()).isEqualTo("物流签收凭据");
    assertThat(att.attachedBySide()).isEqualTo("BUYER");
    assertThat(att.storageRef()).isEqualTo("obj://case/signed.png");
    assertThat(att.capturedAt()).isEqualTo(T1);
    // 库里真的只有引用，没有字节列
    assertThat(sql.fetchOne(
        "select count(*) from information_schema.columns where table_name='aftersales_attachment'"
            + " and column_name in ('content','bytes','data','blob')").get(0, Long.class))
        .as("凭证表不允许有存文件内容的列").isZero();
  }

  @Test
  void 详情页的单条备注写在V156预留的tenantsingle槽位上() {
    var f = seed("MO-NOTE", "10.0000", "x");
    var note = asTenant(() -> details.addNote(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesNoteCreate("详情页加的备注", null)));
    assertThat(note.noteSource()).isEqualTo("TENANT_SINGLE");
    assertThat(note.batchId()).as("单条备注没有批次").isNull();
    assertThat(note.authorId()).isEqualTo(user);
    // 空备注是请求级错误
    assertThatThrownBy(() -> asTenant(() -> details.addNote(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesNoteCreate("   ", null))))
        .hasMessageContaining("备注正文不能为空");
  }

  // ==========================================================================
  // 结案后不再收新证据（DB 触发器 + 服务层双挡）
  // ==========================================================================
  @Test
  void 结案后追加沟通记录凭证与商品行一律被拒() {
    var f = seed("MO-CLOSED", "10.0000", "x");
    asTenant(() -> {
      sql.execute(
          "update aftersales_case set status='CLOSED', resolved_at=?::timestamptz where id=?::uuid",
          T3, f.caseId());
      return null;
    });
    assertThatThrownBy(() -> asTenant(() -> details.addMessage(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesMessageCreate(
            "BUYER_TO_US", "CHAT", "x", null, null, null, T2, T2))))
        .hasMessageContaining("已结案");
    assertThatThrownBy(() -> asTenant(() -> details.addAttachment(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
            "OTHER", "a.txt", "text/plain", "obj://a", null, null, "US", null, null, T2), user)))
        .hasMessageContaining("已结案");
    assertThatThrownBy(() -> asTenant(() -> details.addLine(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            f.lineId(), new BigDecimal("1.000000"), null, null, null), user)))
        .hasMessageContaining("已结案");
    // 绕过服务层直写同样被 DB 挡住
    assertThatThrownBy(() -> asTenant(() -> {
          sql.execute(
              "insert into aftersales_message(id,tenant_id,case_id,direction,channel,message_body,"
                  + " external_message_id,message_source,source_reference,sent_at,captured_at,author_id,"
                  + " created_at) values(?,?,?,'BUYER_TO_US','CHAT','x','','TENANT_MAINTAINED','',"
                  + "?::timestamptz,?::timestamptz,null,now())",
              UUID.randomUUID(), tenant, f.caseId(), T2, T2);
          return null;
        }))
        .hasMessageContaining("已结案");
  }

  // ==========================================================================
  // 未交付的下游：如实报 false + 归属模块号
  // ==========================================================================
  @Test
  void 下游退货退款纠纷与受理全部如实报未交付() {
    var f = seed("MO-CAPS", "10.0000", "x");
    var caps = asTenant(() -> details.capabilities());
    assertThat(caps.crossServiceReadAvailable()).isFalse();

    for (String code : List.of("RETURNS", "REFUNDS", "DISPUTES", "INTAKE", "ATTRIBUTION")) {
      var rel = caps.relatedModules().stream().filter(m -> code.equals(m.code())).findFirst();
      assertThat(rel).as("capabilities 必须逐条报出 " + code).isPresent();
      assertThat(rel.get().delivered()).as(code + " 当前未交付").isFalse();
      assertThat(rel.get().ownerModule()).as(code + " 必须带归属模块号").isNotBlank();
      // 未交付的模块没有页面可跳：href 恒为 null，点进去 404 比没有链接更糟
      assertThat(rel.get().href()).isNull();
    }
    // 退款/退货区块在区块能力表里也报 available=false
    var relatedSection = caps.sections().stream()
        .filter(s -> "relatedRefunds".equals(s.section())).findFirst().orElseThrow();
    assertThat(relatedSection.available()).isFalse();
    assertThat(relatedSection.ownerModule()).contains("11.2").contains("11.3");

    // 操作入口：未交付的动作 available=false 且带 ownerModule，不渲染成能点的按钮
    for (String code : List.of("INTAKE", "CREATE_RETURN", "CREATE_REFUND", "FILE_DISPUTE")) {
      var action = details.capabilities().actionEntries().stream()
          .filter(a -> code.equals(a.code())).findFirst();
      assertThat(action).as("capabilities 必须报出操作入口 " + code).isPresent();
      assertThat(action.get().available()).as(code + " 未交付").isFalse();
      assertThat(action.get().ownerModule()).isNotBlank();
    }

    // 责任判定：认了没认可得，环节级归因未交付
    var d = asTenant(() -> details.detail(f.caseId()));
    assertThat(d.liability().decisionCode()).isEqualTo("UNDECIDED");
    assertThat(d.liability().attributionDelivered()).isFalse();
    assertThat(d.liability().attributionOwner()).isEqualTo("11.1.2.2 商品问题归因");

    // 写入来源如实标注：平台消息与凭证都是租户登记的
    var msgSource = caps.writeSources().stream()
        .filter(s -> "aftersales_message".equals(s.table())).findFirst().orElseThrow();
    assertThat(msgSource.upstreamDelivered()).isFalse();
    assertThat(msgSource.ownerModule()).isEqualTo("2 平台消息");
  }

  @Test
  void 有授权记录之后责任判定变成已认() {
    var f = seed("MO-LIAB", "18.0000", "x");
    asTenant(() -> cases.bulk(
        new AftersalesCaseModels.AftersalesBulkRequest(
            "AUTHORIZE", List.of(f.caseId()), null, "APPROVED", new BigDecimal("15.0000"), "全额赔付", null),
        user));
    var d = asTenant(() -> details.detail(f.caseId()));
    assertThat(d.liability().decisionCode()).isEqualTo("APPROVED");
    assertThat(d.liability().decisionLabel()).isEqualTo("批准");
    assertThat(d.liability().liability()).contains("我方承认");
    assertThat(d.liability().authorizedAmount()).isEqualByComparingTo("15.0000");
    assertThat(d.liability().ownerCurrent()).contains("客服小林");
    // 授权记录本身也在详情里
    assertThat(d.authorizations()).hasSize(1);
    assertThat(d.authorizations().get(0).authorizationKindLabel()).isEqualTo("退货退款授权");
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void http边界_详情时间线与四个追加端点都通() throws Exception {
    var f = seed("MO-HTTP", "10.0000", "买家原话");
    String body =
        http.perform(get("/trade/aftersales/cases/" + f.caseId())
                .header("Authorization", "Bearer tenant-main"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("header").get("caseNo").asText()).isNotBlank();
    assertThat(parsed.get("order").get("orderNo").asText()).isEqualTo("MO-HTTP");
    assertThat(parsed.get("timeline").isArray()).isTrue();
    assertThat(parsed.get("actions").isArray()).isTrue();
    assertThat(parsed.get("relatedModules").isArray()).isTrue();

    http.perform(get("/trade/aftersales/cases/" + f.caseId() + "/timeline")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    http.perform(get("/trade/aftersales/case-detail/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());

    // 四个追加端点
    http.perform(post("/trade/aftersales/cases/" + f.caseId() + "/notes")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("noteBody", "HTTP 备注"))))
        .andExpect(status().isOk());
    http.perform(post("/trade/aftersales/cases/" + f.caseId() + "/messages")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of(
                "direction", "BUYER_TO_US", "channel", "CHAT",
                "messageBody", "HTTP 消息", "sentAt", T1.toString()))))
        .andExpect(status().isOk());
    http.perform(post("/trade/aftersales/cases/" + f.caseId() + "/attachments")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of(
                "evidenceKind", "OTHER", "fileName", "a.txt", "storageRef", "obj://http/a",
                "attachedBySide", "US", "capturedAt", T1.toString()))))
        .andExpect(status().isOk());
    http.perform(post("/trade/aftersales/cases/" + f.caseId() + "/lines")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of(
                "orderLineId", f.lineId().toString(), "quantity", "1.000000"))))
        .andExpect(status().isOk());

    // 不存在的 id 与没有会话：分别是 400 与 401
    http.perform(get("/trade/aftersales/cases/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isBadRequest());
    http.perform(get("/trade/aftersales/cases/" + f.caseId())).andExpect(status().isUnauthorized());
    // 刻意没有 PUT / DELETE：留痕不可改写
    http.perform(put("/trade/aftersales/cases/" + f.caseId())
        .header("Authorization", "Bearer tenant-main")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(delete("/trade/aftersales/cases/" + f.caseId())
        .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void 写入的时间在库里原样读回() {
    var f = seed("MO-TZ", "10.0000", "x");
    asTenant(() -> details.addAttachment(f.caseId(),
        new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
            "OTHER", "a.txt", "text/plain", "obj://tz/a", null, null, "US", null, null, T2), user));
    // ⚠️ 直查**必须在租户事务里**跑：FORCE RLS 在没有 app.current_tenant 时返回零行，
    //   而「读到零行」会被误读成「没写进去」，从而把真正的写回问题掩盖掉。
    var readBack = asTenant(() -> sql.fetchOne(
        "select captured_at from aftersales_attachment where tenant_id = ?::uuid"
            + " and case_id = ?::uuid",
        tenant, f.caseId()).get(0, OffsetDateTime.class));
    assertThat(readBack.toInstant()).as("写入的绝对时刻必须原样读回").isEqualTo(T2.toInstant());
    assertThat(readBack).isEqualTo(T2);
  }
}
