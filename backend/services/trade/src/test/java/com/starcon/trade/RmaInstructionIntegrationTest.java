package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RmaInstructionModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
 * 11.2.1.2 退货指令下发。
 *
 * <p>跑的是蓝图职责句的三个动词与那条加粗句：
 *
 * <blockquote>
 * 职责：向买家发出退货指引（地址、要求、时限），生成退货标签，跟踪买家回寄物流。
 * 输入：退货授权；退货地址（3.6.3 或平台 RMA 地址）；渠道选择。
 * 输出：退货指令 + 标签文件 + 回寄物流轨迹跟踪。
 * <b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。
 * </blockquote>
 *
 * <p><b>本类最看重的八组断言</b>：
 *
 * <ol>
 *   <li><b>地址与时限是真快照</b>：建单后改授权单的地址，指令上的地址**纹丝不动</b> ——
 *       指引是发给买家的一份文件，买家已经照着它寄了。</li>
 *   <li><b>只有已授权的授权单能发指引</b>：拒绝 / 撤销 / 超时关闭的单一律发不出去，
 *       而且<b>绕过服务层直连 SQL 也发不出去</b>（DB 触发器那一道）。</li>
 *   <li><b>下发即冻结</b>：下发后改地址 / 加行，服务层与 DB 触发器都拒。</li>
 *   <li><b>标签文件是真字节 + SHA-256</b>：内容真含指令号 / 退货地址 / SKU，
 *       校验和与**读回的内容**逐字节算出来的一致（<b>断言写回的值</b>），
 *       而且被改过之后读取会报 {@code checksumVerified=false}。</li>
 *   <li><b>「生成」不等于「交付」</b>，而且自出模板标签**不许带运单号** ——
 *       带了就等于声称已对接 10 物流的承运商接口（未交付）。</li>
 *   <li><b>轨迹是追加式事实</b>：按运单号分组算当前状态、重复推送去重、
 *       运行角色 UPDATE/DELETE 权限已收回。</li>
 *   <li><b>时限到期只判「时限过了」，不判「货没回来」</b>：有轨迹的单照常失效，
 *       逐条结果带 {@code trackingStarted} 让运营知道这条是被时限带走的。</li>
 *   <li><b>时间写回零漂移</b>：夹具刻意用 +08:00 非 UTC 偏移，
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，只有这样才能测出来。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RmaInstructionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
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

  @Autowired RmaInstructionService instructions;
  @Autowired RmaAuthorizationService rma;
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

  /**
   * 寄回时限：**带非 UTC 偏移的将来时刻**。
   *
   * <p>偏移仍是 +08:00（这样零漂移断言才真的能测出 {@code Timestamp.valueOf(LocalDateTime)}
   * 的 −28800s 静默偏移），但落在将来 —— 11.2.1.1 会拒掉「寄回时限必须晚于当前时刻」，
   * 用一个写死的过去时刻做夹具会在时间往前走之后整体塌掉。
   */
  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate rma_instruction_event, rma_return_tracking_event, rma_label,"
              + " rma_instruction_line, rma_instruction,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具：订单 → 售后单 → 退货授权单（已授权）
  // ==========================================================================

  /** 一张已授权的退货授权单（含订单行、履约进度、售后商品行）。 */
  record Seeded(UUID rmaId, UUID caseId, UUID orderId, UUID orderLineId, UUID rmaLineId,
      String orderNo, OffsetDateTime dueAt) {}

  /** 建一张订单 + 一张售后单（RETURN_REFUND）+ 一行商品，并返回 id。 */
  record CaseSeeded(UUID caseId, UUID lineId, BigDecimal qty, BigDecimal amount) {}

  CaseSeeded seedCase(String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(() -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenant, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, "买家-" + orderNo, "buyer-" + orderNo + "@example.com",
          T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
          "买家-" + orderNo, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "碎了",
                    amount, "USD", qty, "PLAT-" + orderNo, null, null,
                    "买家诉求-" + orderNo, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                user)
            .id());
    as(() -> details.addLine(
            caseId,
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                lineId, qty, amount, "USD", "第 1 件"),
            user));
    return new CaseSeeded(caseId, lineId, qty, amount);
  }

  /** 建一张**已授权**的退货授权单（4 件授权 3 件，留出「部分授权」的口子）。 */
  Seeded seedAuthorized(String orderNo) {
    return seedAuthorized(orderNo, due());
  }

  Seeded seedAuthorized(String orderNo, OffsetDateTime due) {
    CaseSeeded c = seedCase(orderNo);
    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    c.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    var detail = as(() -> rma.detail(rmaId));
    var rmaLineId = detail.lines().get(0).id();
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "PARTIALLY_APPROVED", due, "外包装压损，只退 3 件",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        user));
    return new Seeded(rmaId, c.caseId(), null, c.lineId(), rmaLineId, orderNo, due);
  }

  /** 建一张**草稿**退货授权单（未审批）。 */
  Seeded seedDraft(String orderNo) {
    CaseSeeded c = seedCase(orderNo);
    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    c.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    return new Seeded(rmaId, c.caseId(), null, c.lineId(), null, orderNo, null);
  }

  private RmaInstructionCreate createRequest(UUID rmaId, String email) {
    return new RmaInstructionCreate(
        rmaId, "BUYER_EMAIL", "买家-" + rmaId, email, "+1-213-555-0100",
        "外箱请加固，配件一并寄回", "压损件请拍照后单独装袋", true, null);
  }

  private RmaInstructionDetail issue(UUID rmaId, String email) {
    UUID id = as(() -> instructions.create(createRequest(rmaId, email), user)).head().id();
    return as(() -> instructions.issue(id, new RmaInstructionIssue(null, 0L), user));
  }

  private static String sha256Hex(String content) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
  }

  // ==========================================================================
  // 1. 建单：地址与时限是真快照
  // ==========================================================================

  @Test
  void 建单时地址与时限从退货授权单真复制过来() {
    Seeded s = seedAuthorized("SNAP-1");

    var created = as(() -> instructions.create(createRequest(s.rmaId(), "b1@example.com"), user));

    // 地址与时限来自授权单，不是调用方传的（建单请求里根本没这两项）。
    assertThat(created.head().returnRecipientName()).isEqualTo("退货仓-SNAP-1");
    assertThat(created.head().returnRecipientPhone()).isEqualTo("+1-213-555-0199");
    assertThat(created.head().returnAddressLine1()).isEqualTo("88 Yonge St");
    assertThat(created.head().returnCity()).isEqualTo("ONTARIO");
    assertThat(created.head().returnCountryCode()).isEqualTo("US");
    // 来源**连着一起复制**：只复制地址不复制来源，这条指引就会假装地址是租户自己敲的。
    assertThat(created.head().addressSource()).isEqualTo("TENANT_MAINTAINED");

    // 逐行应寄数量默认取授权量（本夹具授权 3 件），且 ≤ 授权量。
    assertThat(created.lines()).hasSize(1);
    assertThat(created.lines().get(0).expectedQty()).isEqualByComparingTo("3");
    assertThat(created.lines().get(0).authorizedQty()).isEqualByComparingTo("3");
    assertThat(created.lines().get(0).skuCode()).isEqualTo("SKU-SNAP-1");
    assertThat(created.expectedQty()).isEqualByComparingTo("3");

    // **寄回时限的时间写回零漂移**：授权单写的是一个 +08:00 偏移的时刻，
    // 指令上必须逐秒一致（含偏移），而不是被 Timestamp.valueOf 悄悄偏 −28800s。
    assertThat(created.head().returnByAt()).isEqualTo(s.dueAt());
    assertThat(created.head().returnByAt().getOffset().getTotalSeconds())
        .as("偏移必须原样带回来，不能被换成 UTC 或本机时区").isEqualTo(8 * 3600);
  }

  @Test
  void 授权单改了地址指令上的快照纹丝不动() {
    Seeded s = seedAuthorized("SNAP-2");
    var created = as(() -> instructions.create(createRequest(s.rmaId(), "b2@example.com"), user));
    UUID id = created.head().id();
    String before = created.head().returnAddressLine1();

    // 授权单是 11.2.1.1 的写入所有者；本模块**只读**它。这里直接改库模拟它被改了。
    as(() -> {
      sql.execute(
          "update rma_authorization set return_address_line1 = ? where tenant_id = ?::uuid",
          "999 Changed Ave", tenant);
      return null;
    });

    var after = as(() -> instructions.detail(id));
    assertThat(after.head().returnAddressLine1())
        .as("指引是发给买家的一份文件，买家已经照着旧地址寄了 —— 快照不许跟着授权单变")
        .isEqualTo(before)
        .isEqualTo("88 Yonge St");
    // 授权单自己确实变了（证明上一条不是因为没改成功）。
    assertThat(as(() -> sql.fetchOne(
                "select return_address_line1 from rma_authorization where tenant_id = ?::uuid and id = ?::uuid",
                tenant, s.rmaId()).get(0, String.class))).isEqualTo("999 Changed Ave");
  }

  // ==========================================================================
  // 2. 只有已授权的授权单能发指引
  // ==========================================================================

  @Test
  void 只有已授权的退货授权单才能发指引() {
    Seeded draft = seedDraft("AUTH-DRAFT");
    assertThatThrownBy(() -> as(() -> instructions.create(createRequest(draft.rmaId(), "d@example.com"), user)))
        .as("草稿还没审批就发指引 = 通知买家「可以寄回来了」而我们并不打算收")
        .hasMessageContaining("只有已授权 / 部分授权的退货授权单");

    Seeded ok = seedAuthorized("AUTH-OK");
    var okInstruction =
        as(() -> instructions.create(createRequest(ok.rmaId(), "ok@example.com"), user));
    assertThat(okInstruction.head().rmaStatus()).isEqualTo("PARTIALLY_AUTHORIZED");

    // 撤销授权单 → 再下发已建好的那张草稿，必须发不出去
    //（建单到下发之间授权被撤是真实场景）。
    as(() -> rma.cancel(ok.rmaId(), new RmaAuthorizationModels.RmaCancel("买家改主意了", 1L), user));
    assertThatThrownBy(() -> as(() ->
            instructions.issue(okInstruction.head().id(), new RmaInstructionIssue(null, 0L), user)))
        .as("下发前必须再查一次上游授权单还在不在授权状态")
        .hasMessageContaining("已不在授权状态");
  }

  @Test
  void 绕过服务层直连Sql也发不出指引() throws Exception {
    Seeded draft = seedDraft("RAW-SQL");
    // DB 触发器那一道：直连超级用户 INSERT 一条指向草稿授权单的指令行。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "insert into rma_instruction(id,tenant_id,instruction_no,rma_id,instruction_status,"
              + " delivery_channel,recipient_name,address_source,return_recipient_name,"
              + " return_country_code,return_address_line1,return_by_at,created_by,created_at,"
              + " updated_at) values(gen_random_uuid(),'" + tenant + "','RAWSQL','" + draft.rmaId()
              + "','DRAFT','TENANT_DOWNLOAD_PRINT','买家','TENANT_MAINTAINED','仓','US','x',"
              + "now()+interval '10 days',gen_random_uuid(),now(),now())");
      var rs = s.executeQuery("select id from rma_instruction where instruction_no='RAWSQL'");
      // ⚠️ 必须先 next() 再取值：不调的话 getObject 报的是一句与真实原因毫无关系的
      //   「查询结果指标位置不正确，您也许需要呼叫 ResultSet 的 next() 方法」。
      rs.next();
      UUID instructionId = rs.getObject("id", java.util.UUID.class);
      s.execute(
          "insert into rma_instruction_line(id,tenant_id,instruction_id,rma_line_id,expected_qty,"
              + " created_at,updated_at) values(gen_random_uuid(),'" + tenant + "','"
              + instructionId + "',gen_random_uuid(),1,now(),now())");
    } catch (Exception e) {
      assertThat(e.getMessage())
          .as("绕过服务层直连 SQL 也必须被 DB 触发器挡下")
          .contains("只有 AUTHORIZED / PARTIALLY_AUTHORIZED");
    }
  }

  // ==========================================================================
  // 3. 下发即冻结
  // ==========================================================================

  @Test
  void 下发后内容冻结服务层与Db触发器都拒() throws Exception {
    Seeded s = seedAuthorized("FREEZE");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "f@example.com"), user)).head().id();

    // 草稿期可以改。
    as(() -> instructions.update(id,
        new RmaInstructionUpdate("BUYER_EMAIL", "买家", "f@example.com", "",
            "改过的要求", "改过的备注", null, 0L)));

    var issued = as(() -> instructions.issue(id, new RmaInstructionIssue(null, 1L), user));
    assertThat(issued.head().instructionStatus()).isEqualTo("ISSUED");
    assertThat(issued.head().frozen()).isTrue();
    assertThat(issued.head().contentFrozenAt())
        .as("冻结时刻 = 下发时刻（同一列，别造第二个真相）").isEqualTo(issued.head().issuedAt());

    // 服务层：改草稿被拒。
    assertThatThrownBy(() -> as(() -> instructions.update(id,
        new RmaInstructionUpdate("BUYER_EMAIL", "买家", "f@example.com", "", "x", "y", null, 2L))))
        .hasMessageContaining("只有草稿");

    // DB 触发器：直连 SQL 改内容也被拒。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var stmt = c.createStatement()) {
      stmt.execute("update rma_instruction set return_address_line1='改了' where instruction_no="
          + quote(issued.head().instructionNo()));
    } catch (Exception e) {
      assertThat(e.getMessage()).contains("已冻结");
    }
    assertThat(as(() -> instructions.detail(id)).head().returnAddressLine1()).isEqualTo("88 Yonge St");
  }

  @Test
  void 留痕记录每次状态变化且用了各自那一刻() {
    Seeded s = seedAuthorized("TRAIL");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "t@example.com"), user)).head().id();
    as(() -> instructions.issue(id, new RmaInstructionIssue("已通过邮件发出", 0L), user));
    as(() -> instructions.acknowledge(id, new RmaInstructionAcknowledge("买家回复收到了", 1L), user));

    var detail = as(() -> instructions.detail(id));
    assertThat(detail.events()).hasSize(3);
    assertThat(detail.events().get(0).eventType()).isEqualTo("CREATED");
    assertThat(detail.events().get(1).eventType()).isEqualTo("ISSUED");
    assertThat(detail.events().get(1).fromStatus()).isEqualTo("DRAFT");
    assertThat(detail.events().get(2).eventType()).isEqualTo("ACKNOWLEDGED");

    // ⚠️ 每类事件用它自己那一刻 —— 不是 coalesce 出一列通用值。
    //   早期版本写 coalesce(...) 会把确认事件的时刻记成下发的那一刻，
    //   两条 occurred_at 完全相同、排序退化成按随机 UUID 排，**时好时坏**。
    assertThat(detail.events().get(1).occurredAt()).isEqualTo(detail.head().issuedAt());
    assertThat(detail.events().get(2).occurredAt()).isEqualTo(detail.head().acknowledgedAt());
    assertThat(detail.events()).isSortedAccordingTo(
        java.util.Comparator.comparing(RmaInstructionEvent::occurredAt));

    // 撤销必须带理由；重复确认要被拒。
    assertThatThrownBy(() -> as(() ->
            instructions.acknowledge(id, new RmaInstructionAcknowledge("再确认一次", 2L), user)))
        .hasMessageContaining("已经确认过");
    assertThatThrownBy(() -> as(() ->
            instructions.cancel(id, new RmaInstructionCancel("  ", 2L), user)))
        .hasMessageContaining("撤销理由");
  }

  @Test
  void 运行角色对追加式表连更新删除权限都没有() {
    assertThat(as(() -> sql.fetchOne(
            "select has_table_privilege('trade_app','rma_return_tracking_event','UPDATE')")
        .get(0, Boolean.class)))
        .as("承运商重推就是「同一条」，改掉它等于让轨迹和真实物流对不上")
        .isFalse();
    assertThat(as(() -> sql.fetchOne(
            "select has_table_privilege('trade_app','rma_instruction_event','DELETE')")
        .get(0, Boolean.class)))
        .as("买家投诉「你们根本没通知过我」时，这是唯一的证据")
        .isFalse();
  }

  // ==========================================================================
  // 4. 标签文件是真字节 + SHA-256
  // ==========================================================================

  @Test
  void 自出模板标签内容真含指令号地址与Sku且校验和与读回一致() throws Exception {
    Seeded s = seedAuthorized("LBL-TPL");
    var issued = issue(s.rmaId(), "lbl@example.com");
    UUID id = issued.head().id();

    var after = as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "TEXT_TEMPLATE",
            null, null, null, null, null, null), user));
    RmaLabel label = after.label();
    assertThat(label.labelNo()).startsWith("RML");
    assertThat(label.labelStatus()).isEqualTo("GENERATED");
    assertThat(label.trackingNo())
        .as("本模块出的模板标签**没有**运单号 —— 带了就等于声称已对接 10 物流（未交付）")
        .isNull();

    // 内容真来自库里真值，不是调用方传的字符串（建标签的请求里根本没有 content）。
    var content = as(() -> instructions.labelContent(id));
    assertThat(content.content())
        .contains(issued.head().instructionNo())
        .contains("RMA No: RMA" )
        .contains("退货仓-LBL-TPL")
        .contains("88 Yonge St")
        .contains("SKU-LBL-TPL")
        .contains("x 3")
        .contains("请加固");
    assertThat(content.contentType()).isEqualTo("text/plain; charset=utf-8");

    // ⚠️ **断言写回的值**：校验和必须等于**库里存的那段内容**重算出来的，
    //   不是重算自己手上刚渲染的那份（那永远相等，测不出任何东西）。
    String stored =
        as(() -> sql.fetchOne(
                "select label_content from rma_label where tenant_id = ?::uuid and label_no = ?",
                tenant, label.labelNo())
            .get(0, String.class));
    assertThat(content.content()).isEqualTo(stored);
    String recomputed = sha256Hex(stored);
    assertThat(content.computedChecksum()).isEqualTo(recomputed);
    assertThat(content.storedChecksum()).isEqualTo(recomputed);
    assertThat(label.labelChecksum()).isEqualTo(recomputed);
    assertThat(content.checksumVerified()).isTrue();
    assertThat(label.contentLength()).isEqualTo(stored.length());
  }

  @Test
  void 标签文件被改过之后读取会报完整性校验失败() throws Exception {
    Seeded s = seedAuthorized("LBL-TAMPER");
    var issued = issue(s.rmaId(), "tamper@example.com");
    UUID id = issued.head().id();
    as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "TEXT_TEMPLATE",
            null, null, null, null, null, null), user));

    // 绕过服务层直接改内容（用超级用户，FORCE RLS 挡不住 owner）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      st.execute("update rma_label set label_content = 'RETURN TO: 攻击者的仓库' where tenant_id='"
          + tenant + "' and instruction_id='" + id + "'");
    }

    var content = as(() -> instructions.labelContent(id));
    assertThat(content.checksumVerified())
        .as("买家照着这份地址寄，货寄到谁家由篡改者决定 —— 必须报出来而不是照常渲染")
        .isFalse();
    assertThat(content.computedChecksum()).isNotEqualTo(content.storedChecksum());
    assertThat(content.note()).contains("完整性校验失败");
  }

  @Test
  void 租户登记的外部标签必带承运商与运单号() {
    Seeded s = seedAuthorized("LBL-TENANT");
    var issued = issue(s.rmaId(), "ext@example.com");
    UUID id = issued.head().id();

    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("TENANT_PROVIDED_FILE", "FedEx", "EXPRESS", "TEXT_TEMPLATE",
            null, new BigDecimal("1.5"), new BigDecimal("30"), new BigDecimal("20"),
            new BigDecimal("10"), "FedEx RETURN LABEL"), user)))
        .as("两者皆空的话，买家手上那张贴纸无从核对，实收时也定位不到包裹")
        .hasMessageContaining("承运商与运单号");

    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("TENANT_PROVIDED_FILE", "FedEx", "EXPRESS", "TEXT_TEMPLATE",
            "T-EXT-1", new BigDecimal("1.5"), new BigDecimal("30"), new BigDecimal("20"),
            new BigDecimal("10"), null), user)))
        .as("登记外部标签必须给出文件内容，不能只登记一个编号")
        .hasMessageContaining("标签文件内容");

    var after = as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("TENANT_PROVIDED_FILE", "FedEx", "EXPRESS", "TEXT_TEMPLATE",
            "T-EXT-1", new BigDecimal("1.5"), new BigDecimal("30"), new BigDecimal("20"),
            new BigDecimal("10"), "FedEx RETURN LABEL 退回仓库"), user));
    assertThat(after.label().carrierCode()).isEqualTo("FedEx");
    assertThat(after.label().trackingNo()).isEqualTo("T-EXT-1");
    assertThat(after.label().serviceLevelLabel()).isEqualTo("快递");
    assertThat(as(() -> instructions.labelContent(id)).content()).contains("退回仓库");
  }

  @Test
  void 自出模板标签不许带运单号也不许代打面单() {
    Seeded s = seedAuthorized("LBL-NOWAYBILL");
    var issued = issue(s.rmaId(), "nb@example.com");
    UUID id = issued.head().id();

    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, "STANDARD", "TEXT_TEMPLATE",
            "FAKE-TRACK-1", null, null, null, null, null), user)))
        .as("在标签上写运单号会让它被当成已对接承运商")
        .hasMessageContaining("不带运单号");

    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("MODULE_10_CARRIER_LABEL_API", null, null, "TEXT_TEMPLATE",
            null, null, null, null, null, null), user)))
        .as("10 物流面单接口未交付，本服务不代打面单")
        .hasMessageContaining("未交付");
  }

  @Test
  void 包裹尺寸三个必须一起给() {
    Seeded s = seedAuthorized("LBL-DIM");
    var issued = issue(s.rmaId(), "dim@example.com");
    UUID id = issued.head().id();
    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "TEXT_TEMPLATE",
            null, new BigDecimal("1.5"), new BigDecimal("30"), new BigDecimal("20"),
            null, null), user)))
        .as("只给长宽不给高，承运商算出来的运费是错的")
        .hasMessageContaining("三个一起给");
  }

  @Test
  void 未下发的指令出不了标签() {
    Seeded s = seedAuthorized("LBL-DRAFT");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "nd@example.com"), user))
        .head().id();
    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "TEXT_TEMPLATE",
            null, null, null, null, null, null), user)))
        .as("买家没收到指引，标签寄给谁？")
        .hasMessageContaining("只有已下发的指令才需要出标签");
  }

  // ==========================================================================
  // 5. 「生成」不等于「交付」
  // ==========================================================================

  @Test
  void 生成不等于交付而且作废后可重出() {
    Seeded s = seedAuthorized("LBL-LIFE");
    var issued = issue(s.rmaId(), "life@example.com");
    UUID id = issued.head().id();

    // 生成。
    var generated = as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, "STANDARD", "TEXT_TEMPLATE",
            null, null, null, null, null, null), user));
    assertThat(generated.label().labelStatus()).isEqualTo("GENERATED");
    assertThat(generated.label().deliveredAt()).isNull();

    // 第二张未作废标签发不出去。
    assertThatThrownBy(() -> as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "TEXT_TEMPLATE",
            null, null, null, null, null, null), user)))
        .hasMessageContaining("已经有一张未作废的标签");

    // 交付：贴纸真的到买家手上。
    var delivered = as(() -> instructions.deliverLabel(id,
        new RmaLabelDeliver("BUYER_EMAIL"), user));
    assertThat(delivered.label().labelStatus()).isEqualTo("DELIVERED");
    assertThat(delivered.label().deliveredAt()).isNotNull();
    assertThat(delivered.label().deliveredVia()).isEqualTo("BUYER_EMAIL");

    // 重复交付：会让人以为贴了两张，而买家只贴了一张。
    assertThatThrownBy(() -> as(() ->
            instructions.deliverLabel(id, new RmaLabelDeliver("BUYER_EMAIL"), user)))
        .hasMessageContaining("已经交付过");

    // 作废必须带理由；作废后可重出一张。
    assertThatThrownBy(() -> as(() -> instructions.voidLabel(id, new RmaLabelVoid(" "), user)))
        .hasMessageContaining("作废理由");
    var voided = as(() -> instructions.voidLabel(id, new RmaLabelVoid("承运商取消了运单"), user));
    assertThat(voided.label()).as("作废后当前标签为空（未作废的那张才是「当前」标签）").isNull();

    var again = as(() -> instructions.generateLabel(id,
        new RmaLabelGenerate("SELF_GENERATED_TEMPLATE", null, null, "HTML_TEMPLATE",
            null, null, null, null, null, null), user));
    assertThat(again.label().labelNo()).isNotEqualTo(generated.label().labelNo());
    assertThat(again.label().labelStatus()).isEqualTo("GENERATED");
    assertThat(as(() -> instructions.labelContent(id)).content()).contains("<br>");
  }

  // ==========================================================================
  // 6. 轨迹是追加式事实
  // ==========================================================================

  @Test
  void 轨迹按运单号分组算当前状态且重复推送被去重() {
    Seeded s = seedAuthorized("TRK");
    var issued = issue(s.rmaId(), "trk@example.com");
    UUID id = issued.head().id();

    var none = as(() -> instructions.tracking(id));
    assertThat(none.events()).isEmpty();
    assertThat(none.parcels()).isEmpty();
    assertThat(none.latestEventCode()).isNull();

    // 包裹 A 的三条（**乱序登记**：时间线必须按发生时刻排，不按登记时刻排）。
    append(id, "FedEx", "T-A", "IN_TRANSIT", T1.plusHours(1), T1.plusHours(2));
    append(id, "FedEx", "T-A", "ACCEPTED", T1, T1.plusHours(3));
    append(id, "FedEx", "T-A", "OUT_FOR_DELIVERY", T1.plusHours(20), T1.plusHours(21));

    // 包裹 B（换承运商重寄：真实且常见，所以承运商挂在事件上而不是指令头上）。
    append(id, "UPS", "T-B", "ACCEPTED", T1.plusDays(1), T1.plusDays(1));

    var timeline = as(() -> instructions.tracking(id));
    assertThat(timeline.events()).hasSize(4);
    assertThat(timeline.events()).isSortedAccordingTo(
        java.util.Comparator.comparing(RmaTrackingEvent::occurredAt));
    assertThat(timeline.parcels()).hasSize(2);

    // 当前状态 = 每个运单号 occurred_at 最大的那条，**不单独存列**。
    var parcelA = timeline.parcels().stream()
        .filter(p -> p.trackingNo().equals("T-A")).findFirst().orElseThrow();
    assertThat(parcelA.currentEventCode()).isEqualTo("OUT_FOR_DELIVERY");
    assertThat(parcelA.eventCount()).isEqualTo(3);
    assertThat(parcelA.firstOccurredAt()).isEqualTo(T1);
    // 承运商重推让「知道时刻」晚于「发生时刻」：延迟仍然算得出来（两列分开记）。
    assertThat(timeline.events().get(0).lagSeconds())
        .as("ACCEPTED 在 T1 发生、T1+3h 才登记（承运商晚回传）")
        .isEqualTo(3 * 3600);

    // 重复推送同一条扫描：必须被拒（否则「这单退了几件」出现两个答案）。
    assertThatThrownBy(() -> as(() -> instructions.appendTracking(id,
        new RmaTrackingAppend("FedEx", "T-A", "OUT_FOR_DELIVERY", null, null,
            T1.plusHours(20), T1.plusHours(25), "TENANT_MANUAL_ENTRY"), user)))
        .hasMessageContaining("已经登记过");

    // 列表行也能按承运商与运单号反查（客服手上只有一个单号时的入口）。
    var page = as(() -> instructions.list(
        new RmaInstructionListQuery(null, null, null, null, null, null, "ups", null, null, null)));
    assertThat(page.rows()).extracting(RmaInstructionRow::id).containsExactly(id);
    var byTracking = as(() -> instructions.list(
        new RmaInstructionListQuery(null, null, null, null, null, null, null, "T-A", null, null)));
    assertThat(byTracking.rows()).extracting(RmaInstructionRow::id).containsExactly(id);
  }

  @Test
  void 系统接入通道不接受人工登记() {
    Seeded s = seedAuthorized("TRK-SRC");
    var issued = issue(s.rmaId(), "src@example.com");
    UUID id = issued.head().id();
    assertThatThrownBy(() -> as(() -> instructions.appendTracking(id,
        new RmaTrackingAppend("FedEx", "T-X", "IN_TRANSIT", null, null, T1, null,
            "MODULE_10_LOGISTICS_POLL"), user)))
        .as("10 物流轮询未交付，人工入口只接受 TENANT_MANUAL_ENTRY")
        .hasMessageContaining("未交付");
  }

  @Test
  void 未下发的指令不可能收到包裹() {
    Seeded s = seedAuthorized("TRK-DRAFT");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "td@example.com"), user))
        .head().id();
    assertThatThrownBy(() -> as(() -> instructions.appendTracking(id,
        new RmaTrackingAppend("FedEx", "T-Z", "IN_TRANSIT", null, null, T1, null, null), user)))
        .as("未下发时买家手上没有指引，不会有包裹寄回来")
        .hasMessageContaining("只有已下发的指令才可能收到包裹");
  }

  @Test
  void 轨迹事件时间写回零漂移() {
    Seeded s = seedAuthorized("TRK-TIME");
    var issued = issue(s.rmaId(), "time@example.com");
    UUID id = issued.head().id();
    append(id, "FedEx", "T-TIME", "IN_TRANSIT", T1, T1.plusMinutes(5));

    var event = as(() -> instructions.tracking(id)).events().get(0);
    assertThat(event.occurredAt())
        .as("夹具是 +08:00；Timestamp.valueOf 会静默偏 −28800s")
        .isEqualTo(T1);
    assertThat(event.occurredAt().getOffset().getTotalSeconds()).isEqualTo(8 * 3600);
    assertThat(event.trackedAt()).isEqualTo(T1.plusMinutes(5));
    assertThat(event.lagSeconds()).isEqualTo(300L);
  }

  // ==========================================================================
  // 7. 时限到期只判「时限过了」，不判「货没回来」
  // ==========================================================================

  @Test
  void 时限到期自动失效有轨迹的单也照常失效() {
    Seeded s = seedAuthorized("EXP-1");
    // 建指令时快照的是**当时**的时限，所以先建再把指令上的时限改成过去，
    // 才造得出「已过期」的指令（直接在授权单上改会先被 11.2.1.1 的时限校验拒掉）。
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "exp1@example.com"), user))
        .head().id();
    // ⚠️ 改时限**必须在草稿期**做：下发之后内容即冻结，冻结触发器会拒
    //   （这正是它该做的 —— 买家可能已经照着旧时限在算日子了）。
    as(() -> {
      sql.execute(
          "update rma_instruction set return_by_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          T1.minusDays(1), tenant, id);
      return null;
    });
    as(() -> instructions.issue(id, new RmaInstructionIssue(null, 0L), user));
    // 期间有物流活动。
    append(id, "FedEx", "T-EXP", "IN_TRANSIT", T1, T1);

    var result = as(() -> instructions.expireOverdue());
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.expired()).isEqualTo(1);
    var item = result.items().get(0);
    assertThat(item.outcome()).isEqualTo("EXPIRED");
    assertThat(item.trackingStarted())
        .as("时限到期只判「时限过了」不判「货没回来」，所以有物流活动也要如实标出来")
        .isTrue();
    assertThat(item.detail()).contains("有物流活动");
    assertThat(item.expiredAt()).isNotNull();

    // 终态不可再流转；时间写回零漂移。
    var after = as(() -> instructions.detail(id));
    assertThat(after.head().instructionStatus()).isEqualTo("EXPIRED");
    assertThat(after.head().frozen()).isTrue();
    assertThat(after.goodsReceived())
        .as("**已下发 ≠ 已寄回**：没有 RECEIVED 状态（11.2.2.1 未交付）").isFalse();
    assertThatThrownBy(() -> as(() ->
            instructions.cancel(id, new RmaInstructionCancel("想撤销", after.head().version()), user)))
        .hasMessageContaining("已结案");

    // 再扫一次不重复改。
    assertThat(as(() -> instructions.expireOverdue()).scanned()).isZero();
  }

  @Test
  void 在时限内的单不会被扫到() {
    Seeded s = seedAuthorized("EXP-2");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "exp2@example.com"), user))
        .head().id();
    as(() -> instructions.issue(id, new RmaInstructionIssue(null, 0L), user));
    assertThat(as(() -> instructions.expireOverdue()).expired()).isZero();
    assertThat(as(() -> instructions.detail(id)).head().instructionStatus()).isEqualTo("ISSUED");
  }

  // ==========================================================================
  // 8. 分页 / 排序 / 检索都是真的
  // ==========================================================================

  @Test
  void 分页不重叠且汇总与排序白名单都是真的() {
    Seeded a = seedAuthorized("PAGE-A");
    Seeded b = seedAuthorized("PAGE-B");
    Seeded c = seedAuthorized("PAGE-C");
    as(() -> instructions.issue(
        as(() -> instructions.create(createRequest(a.rmaId(), "pa@example.com"), user))
            .head().id(), new RmaInstructionIssue(null, 0L), user));
    as(() -> instructions.create(createRequest(b.rmaId(), "pb@example.com"), user));
    as(() -> instructions.create(createRequest(c.rmaId(), "pc@example.com"), user));

    var first = as(() -> instructions.list(
        new RmaInstructionListQuery(0, 2, null, null, null, null, null, null, null, null)));
    var second = as(() -> instructions.list(
        new RmaInstructionListQuery(1, 2, null, null, null, null, null, null, null, null)));
    assertThat(first.total()).isEqualTo(3);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.rows()).doesNotContainAnyElementsOf(second.rows());

    // 汇总不受分页影响，且能按退货授权单反查。
    assertThat(first.summary().total()).isEqualTo(3);
    assertThat(first.summary().issued()).isEqualTo(1);
    assertThat(first.summary().draft()).isEqualTo(2);
    assertThat(first.summary().deliveredGoods())
        .as("已收货恒 0：登记实收属 11.2.2.1 未交付").isZero();

    var byRma = as(() -> instructions.list(
        new RmaInstructionListQuery(null, null, null, null, a.rmaId(), null, null, null, null, null)));
    assertThat(byRma.total()).isEqualTo(1);

    // 关键字：指令号 / 授权单号 / 售后单号 / 订单号 / 买家邮箱都命中。
    assertThat(as(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, null, null, "pb@example.com", null))).total())
        .isEqualTo(1);
    assertThat(as(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, null, null, "不存在的关键词zzz", null))).total())
        .isZero();

    // 排序白名单：未知键落回默认键，**不接受任意表达式**。
    var sorted = as(() -> instructions.list(new RmaInstructionListQuery(
        0, 10, null, null, null, null, null, null, null, "due_asc")));
    assertThat(sorted.rows()).hasSize(3);
    var injected = as(() -> instructions.list(new RmaInstructionListQuery(
        0, 10, null, null, null, null, null, null, null, "created_at; drop table sales_order")));
    assertThat(injected.rows()).hasSize(3);
    assertThat(as(() -> sql.fetch("select * from sales_order").size()))
        .as("注入串必须被忽略成默认排序，而不是被执行").isEqualTo(3);

    // 每行都带 goodsReceived=false + 归属模块（不填 0 也不给 null 让界面猜）。
    assertThat(sorted.rows()).allSatisfy(row -> {
      assertThat(row.goodsReceived()).isFalse();
      assertThat(row.goodsReceivedOwnerModule()).isEqualTo("11.2.2.1 退货入库");
      assertThat(row.action().code()).isEqualTo("ISSUE");
    });
  }

  @Test
  void 未知筛选值与不存在或跨租户的id都给出明确报错() {
    Seeded s = seedAuthorized("ERR");
    assertThatThrownBy(() -> as(() -> instructions.list(
        new RmaInstructionListQuery(null, null, "NOT_A_STATUS", null, null, null, null, null,
            null, null))))
        .as("拼错的状态值必须被当成筛选条件错误，而不是静默返回零行"
            + "（表里没有匹配行时表级 CHECK 根本不会被求值）")
        .hasMessageContaining("指令状态只能是");

    var created = as(() -> instructions.create(createRequest(s.rmaId(), "err@example.com"), user));
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> as(() -> instructions.detail(missing)))
        .hasMessageContaining("不存在或不属于当前租户");

    // 乐观锁：版本不符必须报，且说明当前版本。
    assertThatThrownBy(() -> as(() -> instructions.issue(
        created.head().id(), new RmaInstructionIssue(null, 99L), user)))
        .as("两个人同时点「下发」时各写一次而表里只留得下后一个")
        .hasMessageContaining("版本不符")
        .hasMessageContaining("当前 0");
    assertThatThrownBy(() -> as(() ->
            instructions.issue(created.head().id(), new RmaInstructionIssue(null, null), user)))
        .hasMessageContaining("必须给出 expectedVersion");
  }

  @Test
  void 同一张退货授权单同时只能有一张在途指令() {
    Seeded s = seedAuthorized("ONE-LIVE");
    as(() -> instructions.create(createRequest(s.rmaId(), "1@example.com"), user));
    assertThatThrownBy(() -> as(() -> instructions.create(createRequest(s.rmaId(), "2@example.com"), user)))
        .as("撤销后可以再发一张，但同时只能有一张在途")
        .hasMessageContaining("rma_instruction_one_live_per_rma");

    // 撤销后允许重发。
    var first = as(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, s.rmaId(), null, null, null, null, null))).rows().get(0);
    as(() -> instructions.cancel(first.id(), new RmaInstructionCancel("买家改主意了", 0L), user));
    assertThat(as(() -> instructions.create(createRequest(s.rmaId(), "3@example.com"), user))
        .head().id()).isNotNull();
  }

  // ==========================================================================
  // 9. 能力声明与端点
  // ==========================================================================

  @Test
  void 能力声明把三个动词报可用并把未交付的下游逐条报出来() {
    var caps = as(() -> instructions.capabilities());

    var bySection = caps.sections().stream()
        .collect(java.util.stream.Collectors.toMap(RmaInstructionSectionCapability::section, s -> s));
    assertThat(bySection.get("returnInstruction").available()).isTrue();
    assertThat(bySection.get("addressSnapshot").available()).isTrue();
    assertThat(bySection.get("channelSelection").available()).isTrue();
    assertThat(bySection.get("returnLabel").available()).isTrue();
    assertThat(bySection.get("returnTracking").available()).isTrue();

    // 未交付的一律 available=false + 归属模块，且**不建影子表**。
    for (String missing : List.of("carrierWaybill", "buyerNotification", "goodsReceived",
        "receivedAllocation", "inspection")) {
      var section = bySection.get(missing);
      assertThat(section.available()).as(missing + " 未交付").isFalse();
      assertThat(section.ownerModule()).as(missing + " 必须报归属模块").isNotBlank();
      assertThat(section.landsIn()).as(missing + " 不该声称有落点").isNull();
    }
    assertThat(bySection.get("returnLabel").reason()).contains("不生成 PDF");

    // 未交付的关联模块 href 必须是 null —— 一个点进去 404 的链接比没有链接更糟。
    var related = caps.relatedModules().stream()
        .collect(java.util.stream.Collectors.toMap(RmaInstructionRelatedModule::id, s -> s));
    assertThat(related.get("RMA_AUTHORIZATION").delivered()).isTrue();
    assertThat(related.get("RMA_AUTHORIZATION").href()).isNotBlank();
    for (RmaInstructionRelatedModule m : caps.relatedModules()) {
      if (!m.delivered()) {
        assertThat(m.href()).as(m.id() + " 未交付，不该有链接").isNull();
        assertThat(m.ownerModule()).as(m.id() + " 必须报归属模块").isNotBlank();
      }
    }
    assertThat(caps.boundaries()).isNotEmpty();
  }

  @Test
  void 端点经Http真的跑得通() throws Exception {
    Seeded s = seedAuthorized("HTTP");
    UUID id = as(() -> instructions.create(createRequest(s.rmaId(), "h@example.com"), user))
        .head().id();

    http.perform(get("/trade/rma-instructions/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(10));

    http.perform(get("/trade/rma-instructions").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].goodsReceived").value(false))
        .andExpect(jsonPath("$.rows[0].goodsReceivedOwnerModule").value("11.2.2.1 退货入库"));

    http.perform(get("/trade/rma-instructions/" + id).header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.instructionStatus").value("DRAFT"));

    http.perform(get("/trade/rma-instructions/" + id + "/tracking")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(0));

    http.perform(post("/trade/rma-instructions/" + id + "/issue")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("expectedVersion", 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.instructionStatus").value("ISSUED"))
        .andExpect(jsonPath("$.head.frozen").value(true));

    // 没有会话 → 401（租户**不**来自任何请求头）。
    http.perform(get("/trade/rma-instructions/" + id)).andExpect(status().isUnauthorized());
    // 伪造租户头开不了写入口（租户只来自会话）。
    http.perform(post("/trade/rma-instructions/" + id + "/cancel")
            .header("Authorization", "Bearer tenant-other")
            .header("X-Tenant-Id", tenant)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                Map.of("reason", "越权撤销", "expectedVersion", 1))))
        .andExpect(status().isBadRequest());
    assertThat(as(() -> instructions.detail(id)).head().instructionStatus()).isEqualTo("ISSUED");
  }

  // ==========================================================================

  private void append(UUID id, String carrier, String trackingNo, String eventCode,
      OffsetDateTime occurred, OffsetDateTime tracked) {
    as(() -> instructions.appendTracking(id,
        new RmaTrackingAppend(carrier, trackingNo, eventCode, "洛杉矶", "测试轨迹",
            occurred, tracked, "TENANT_MANUAL_ENTRY"), user));
  }

  private static String quote(String no) {
    return "'" + no + "'";
  }
}
