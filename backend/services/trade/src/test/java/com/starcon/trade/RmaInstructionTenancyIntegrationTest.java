package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RmaInstructionModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * 11.2.1.2 退货指令下发的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的五张表上**
 * （rma_instruction / rma_instruction_line / rma_label / rma_return_tracking_event /
 * rma_instruction_event），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：指令带着<b>退货地址</b>（收货人姓名 + 电话）、
 * <b>买家邮箱</b>、<b>买家回寄运单号</b>与<b>包裹轨迹</b>，而详情 / 下发 / 取标签文件端点
 * 都是<b>按 id 直达</b>的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
 * 取标签文件端点尤其危险：它返回的是**买家会照着寄的地址文本**。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RmaInstructionTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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
      actors.put("Bearer tenant-a", actor(userA, a));
      actors.put("Bearer tenant-b", actor(userB, b));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
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

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  /** 寄回时限：**带非 UTC 偏移的将来时刻**（11.2.1.1 会拒掉过去时限，写死会整体塌掉）。 */
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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID tid, java.util.function.Supplier<T> work) {
    return tid.equals(a) ? asA(work) : asB(work);
  }

  /** 一张真实订单 + 售后单 + 商品行 + **已授权**的退货授权单 + 一张已下发的退货指令 + 标签。 */
  record Seeded(UUID instructionId, UUID rmaId, UUID caseId, String orderNo, String labelNo) {}

  Seeded seed(UUID tid, String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(tid, () -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + orderNo, "buyer-" + orderNo + "@example.com",
          T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + orderNo,
          "买家-" + orderNo, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(tid, () -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "碎了",
                    amount, "USD", qty, "PLAT-" + orderNo, null, null,
                    "买家诉求-" + orderNo, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                userA).id());
    as(tid, () -> details.addLine(
        caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            lineId, qty, amount, "USD", "第 1 件"),
        userA));

    UUID rmaId =
        as(tid, () -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                userA).head().id());
    var rmaLineId = as(tid, () -> rma.detail(rmaId).lines().get(0).id());
    as(tid, () -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "APPROVED", due(), null,
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        userA));

    // 建指令 → 下发 → 出标签（自带退货地址与买家邮箱，是跨租户泄露的高价值目标）。
    UUID instructionId =
        as(tid, () -> instructions.create(
                new RmaInstructionCreate(
                    rmaId, "BUYER_EMAIL", "买家-" + orderNo, "b-" + orderNo + "@example.com",
                    "+1-213-555-0100", "外箱请加固", "", true, null),
                userA).head().id());
    as(tid, () -> instructions.issue(instructionId, new RmaInstructionIssue(null, 0L), userA));
    String labelNo =
        as(tid, () -> instructions.generateLabel(instructionId,
                new RmaLabelGenerate("TENANT_PROVIDED_FILE", "FedEx", "EXPRESS", "TEXT_TEMPLATE",
                    "T-" + orderNo, new BigDecimal("1.5"), new BigDecimal("30"), new BigDecimal("20"),
                    new BigDecimal("10"), "RETURN LABEL 退回仓库-" + orderNo),
                userA).label().labelNo());
    as(tid, () -> instructions.appendTracking(instructionId,
        new RmaTrackingAppend("FedEx", "T-" + orderNo, "IN_TRANSIT", "洛杉矶", "运输中",
            T1, T1, "TENANT_MANUAL_ENTRY"), userA));

    return new Seeded(instructionId, rmaId, caseId, orderNo, labelNo);
  }

  private RmaInstructionDetail detail(UUID tid, UUID instructionId) {
    return as(tid, () -> instructions.detail(instructionId));
  }

  private long rowCount(UUID tid, String table) {
    return as(tid, () -> sql.fetch("select * from " + table).size());
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var sa = seed(a, "MO-TA");
    var sb = seed(b, "MO-TB");

    // 正对照：各自都读得到自己的。
    assertThat(detail(a, sa.instructionId()).head().returnRecipientName())
        .isEqualTo("退货仓-MO-TA");
    assertThat(detail(b, sb.instructionId()).head().returnRecipientName())
        .isEqualTo("退货仓-MO-TB");

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    // 这些端点是按 id 直达的 URL，拿不到才是真的拿不到。
    assertThatThrownBy(() -> detail(b, sa.instructionId()))
        .as("B 家读 A 家的退货指令必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> detail(a, sb.instructionId()))
        .as("A 家读 B 家的退货指令必须失败")
        .hasMessageContaining("不存在或不属于当前租户");

    // ⚠️ **取标签文件端点必须单独隔离**：它返回的是买家会照着寄的地址文本，
    //   是本模块里泄露后果最直接的一个。
    assertThatThrownBy(() -> asB(() -> instructions.labelContent(sa.instructionId())))
        .as("B 家读 A 家的退货标签文件必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> instructions.labelContent(sb.instructionId())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 轨迹时间线同样按指令隔离。
    assertThatThrownBy(() -> asB(() -> instructions.tracking(sa.instructionId())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 列表也隔离：B 看不到 A 的任何指令。
    assertThat(asA(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, null, null, null, null)).total()))
        .isEqualTo(1);
    assertThat(asB(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, null, null, null, null)).total()))
        .isEqualTo(1);
    assertThat(asB(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, sa.rmaId(), null, null, null, null, null)).total()))
        .as("按退货授权单反查同样隔离")
        .isZero();

    // ⚠️ **按承运商 / 运单号反查不能变成跨租户搜索引擎**：
    //   客服手上只有一个运单号，而运单号是可猜的。
    assertThat(asB(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, "FedEx", "T-MO-TA", null, null)).total()))
        .as("B 拿 A 的运单号反查必须查不到")
        .isZero();
    assertThat(asA(() -> instructions.list(new RmaInstructionListQuery(
        null, null, null, null, null, null, "FedEx", "T-MO-TA", null, null)).total()))
        .as("A 用同样写法查得到自己的（防假绿的正对照）")
        .isEqualTo(1);

    // 五张表按主键直查也互不可见，**并且每张都有正对照**
    //（正对照缺席时，「两边都是 0」这种假绿会被当成隔离生效）。
    for (String table : List.of("rma_instruction", "rma_instruction_line", "rma_label",
        "rma_return_tracking_event", "rma_instruction_event")) {
      // ⚠️ 主键列名逐表不同：头表叫 id，行表与两张留痕/事件表叫 instruction_id。
      //   写死一个列名会让五张表里有一张直接语法错 —— 而错的那张
      //   恰恰是「查对方记录返回零行」这条断言的表，测出来的是报错不是隔离。
      String pk = table.equals("rma_instruction") ? "id" : "instruction_id";
      // ⚠️ rma_instruction_event 是**状态留痕**：建单（CREATED）+ 下发（ISSUED）各一条，
      //   所以它有两行而其余四张各一行 —— 写死 1 会让这一格永远红，而红的原因与隔离无关。
      int expected = table.equals("rma_instruction_event") ? 2 : 1;
      assertThat(rowCount(a, table)).as("A 自己的 " + table).isEqualTo(expected);
      assertThat(rowCount(b, table)).as("B 自己的 " + table).isEqualTo(expected);
      assertThat(asB(() -> sql.fetch(
                  "select * from " + table + " where " + pk + " = ?::uuid", sa.instructionId()).size()))
          .as("B 按主键直查 A 的 " + table)
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from " + table + " where " + pk + " = ?::uuid", sa.instructionId()).size()))
          .as("A 按同样写法直查自己的 " + table + " 必须查得到（防假绿的正对照）")
          .isEqualTo(expected);
    }

    // 写也一样：拿对方的 id 去下发 / 登记轨迹，必须落到会话租户名下而不是对方的单上。
    // ⚠️ 这里**刻意不在 asB 的 lambda 里嵌 asA**：连接池被压到 1，内层 asA 会把
    //   app.current_tenant 重新设成 A 并提交，于是外层这次「跨租户下发」实际是在
    //   A 的租户下跑的、必然查得到那条指令 —— 这条断言就变成了永远为真的假绿。
    assertThatThrownBy(() ->
            asB(() -> instructions.issue(sa.instructionId, new RmaInstructionIssue(null, 1L), userB)))
        .as("B 家拿 A 家的指令 id 去下发，必须落到「不存在或不属于当前租户」")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asB(() -> instructions.appendTracking(sa.instructionId,
        new RmaTrackingAppend("FedEx", "T-FORGED", "IN_TRANSIT", null, null, T1, null,
            "TENANT_MANUAL_ENTRY"), userB)))
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(detail(a, sa.instructionId).head().instructionStatus()).isEqualTo("ISSUED");
    assertThat(asA(() -> sql.fetch(
                "select * from rma_return_tracking_event where tenant_id = ?::uuid"
                    + " and tracking_no = ?", a, "T-FORGED").size()))
        .as("B 的越权登记不许落到 A 名下").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  //
  // ⚠️ **正确预期是「按会话里的真实成员关系正常执行并 200」**，不是 4xx：
  //   伪造的 X-Tenant-Id 头根本进不到租户解析里（租户来自已认证成员关系），
  //   请求仍然写进会话租户名下，对方租户逐字节未变。断言要比对两侧数据库，不比对状态码。
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var sa = seed(a, "MO-HDR");

    // A 的会话 + 伪造的 X-Tenant-Id（B）⇒ 拿到的仍然是 A 的指令，而且**成功**。
    var body =
        http.perform(
                get("/trade/rma-instructions/" + sa.instructionId)
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("head").get("returnRecipientName").asText()).isEqualTo("退货仓-MO-HDR");
    assertThat(parsed.get("head").get("recipientEmail").asText()).isEqualTo("b-MO-HDR@example.com");
    assertThat(parsed.get("head").get("caseNo").asText()).isNotBlank();

    // ⚠️ **标签文件端点同理**：伪造头拿不到 B 的任何东西。
    http.perform(
            get("/trade/rma-instructions/" + sa.instructionId + "/label")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checksumVerified").value(true))
        .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("退回仓库-MO-HDR")));

    // ⚠️ **伪造头不能把别人的单读出来**：B 的会话 + 伪造 A 的头去读 A 的 id，
    //   得到的仍然必须是「不存在或不属于当前租户」——租户**只**来自会话。
    http.perform(
            get("/trade/rma-instructions/" + sa.instructionId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isBadRequest());

    // 伪造头也开不了写入口：B 的会话 + 伪造 A 的头想撤销 A 的指令，必须被拒。
    var cancel = new LinkedHashMap<String, Object>();
    cancel.put("reason", "越权撤销");
    cancel.put("expectedVersion", 1L);
    http.perform(
            post("/trade/rma-instructions/" + sa.instructionId + "/cancel")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cancel)))
        .andExpect(status().isBadRequest());
    assertThat(detail(a, sa.instructionId).head().instructionStatus()).as("A 的指令仍然是已下发")
        .isEqualTo("ISSUED");

    // 伪造头也不能把货写进别人的租户：A 名下的轨迹事件数仍然是它自己那一条。
    assertThat(rowCount(a, "rma_return_tracking_event")).isEqualTo(1);
    assertThat(rowCount(b, "rma_return_tracking_event")).isZero();
    assertThat(rowCount(b, "rma_instruction")).isZero();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/rma-instructions/" + sa.instructionId))
        .andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每轮都复用同一条连接，只有「事务局部绑定 + 提交即清空」
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      // ⚠️ 每轮**新建实体**：复用上一轮的记录，测的就是幂等而不是隔离了。
      final int round = i;
      var sa = seed(a, "MO-POOL-A-" + i);
      var sb = seed(b, "MO-POOL-B-" + i);

      assertThat(detail(a, sa.instructionId).head().caseNo()).as("第 " + round + " 轮 A 读到的是 A 的单")
          .isEqualTo(asA(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  a, sa.caseId)
              .get(0, String.class)));
      assertThat(detail(b, sb.instructionId).head().caseNo()).as("第 " + round + " 轮 B 读到的是 B 的单")
          .isEqualTo(asB(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  b, sb.caseId)
              .get(0, String.class)));

      // 交替写：A 先追加一条轨迹、B 再读 —— 必须读不到。
      asA(() -> instructions.appendTracking(sa.instructionId,
          new RmaTrackingAppend("FedEx", "T-EXTRA-" + round, "ACCEPTED", null, null,
              T1.plusMinutes(round), null, "TENANT_MANUAL_ENTRY"), userA));
      assertThat(asB(() -> sql.fetch(
                  "select * from rma_return_tracking_event where instruction_id = ?::uuid",
                  sa.instructionId).size()))
          .as("第 " + round + " 轮：B 读不到 A 的回寄运单号")
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from rma_return_tracking_event where instruction_id = ?::uuid",
                  sa.instructionId).size()))
          .as("第 " + round + " 轮：A 用同样写法读得到自己的（防假绿的正对照）")
          .isEqualTo(2);

      // 交替读标签文件：内容必须始终是本租户那一份（**地址文本本身**不能串）。
      assertThat(asA(() -> instructions.labelContent(sa.instructionId).content()))
          .contains("退回仓库-MO-POOL-A-" + round);
      assertThat(asB(() -> instructions.labelContent(sb.instructionId).content()))
          .contains("退回仓库-MO-POOL-B-" + round);
    }

    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到。
    assertThat(sql.fetch("select * from rma_instruction").size()).as("事务外读必须零行").isZero();
    assertThat(sql.fetch("select * from rma_instruction_line").size()).isZero();
    assertThat(sql.fetch("select * from rma_label").size()).isZero();
    assertThat(sql.fetch("select * from rma_return_tracking_event").size()).isZero();
    assertThat(sql.fetch("select * from rma_instruction_event").size()).isZero();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 五张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("rma_instruction", "rma_instruction_line", "rma_label",
        "rma_return_tracking_event", "rma_instruction_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V165 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
              .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'INSERT')", table)
              .get(0, Boolean.class))
          .as("trade_app 必须真能往 " + table + " 写")
          .isTrue();
    }
  }
}
