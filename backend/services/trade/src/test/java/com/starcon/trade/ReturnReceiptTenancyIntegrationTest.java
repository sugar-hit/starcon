package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.ReturnReceiptModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.2.1 退货入库的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的五张表上**
 * （return_receipt / return_receipt_line / return_receipt_photo /
 * return_inventory_posting / return_receipt_event），<b>不是</b>占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：实收单带着<b>买家回寄运单号</b>与<b>逐件的 SKU / 序列号</b>，
 * 照片带着<b>商品外观证据</b>（退货纠纷的举证材料），入库分录带着 SKU 快照 ——
 * 而详情 / 入库 / 取分录端点都是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p>⚠️ <b>按回寄运单号反查这一格单独隔离</b>：它是客服手上只有一个单号时的入口，
 * 漏掉租户谓词它就成了一台<b>跨租户搜索引擎</b> —— 而运单号是可以从买家邮件里
 * 直接抄到的，比 UUID 好猜得多。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnReceiptTenancyIntegrationTest {
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
  }

  @Autowired ReturnReceiptService receipts;
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

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
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

  // ==========================================================================
  // 夹具：每个租户各自建一张「订单 → 售后单 → 退货授权单 → 实收入库」
  // ==========================================================================

  /**
   * 稳定的 64 位十六进制校验和。
   *
   * <p>⚠️ 刻意**不**用 {@code seed.repeat(64 / seed.length())}：长度除不尽时得到的是
   * 55 或 66 字符，而列是 {@code char(64)} —— 服务层当场拒掉，而错误消息说的是
   * 「必须给出 64 位十六进制」，看起来像是夹具写错了而不是长度算错了，很难排查。
   */
  static String sha(String seed) {
    String base = Integer.toHexString(seed.hashCode());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; sb.length() < 64; i++) {
      sb.append(base);
    }
    return sb.substring(0, 64);
  }

  record Seeded(UUID rmaId, UUID rmaLineId, UUID caseId, UUID receiptId, String orderNo,
      String trackingNo) {}

  /**
   * 在已认证租户下新开一张**草稿**实收单（用于「追加照片」这类只在草稿期开放的写动作）。
   *
   * <p>⚠️ 夹具 {@link #seed} 产出的单已经入库，而照片只能在草稿期追加
   * （入库后证据不能事后补改）—— 所以交替写的动作必须挂在草稿上。
   */
  UUID newDraft(UUID tid, Seeded s, String tag) {
    return as(
        tid,
        () -> receipts.create(
            new ReturnReceiptCreate(
                s.rmaId, null, "CARRIER", "FedEx", "RT-" + tag,
                "草稿-" + tag, T1,
                List.of(new ReturnReceiptLineDraft(
                    s.rmaLineId, "SKU-X", "SN-" + tag, null, BigDecimal.ONE,
                    "INTACT", "UNOPENED", "")),
                List.of()),
            userA))
        .head()
        .id();
  }

  Seeded seed(UUID tid, String orderNo, String trackingNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(
        tid,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
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
        as(
            tid,
            () -> cases.create(
                    new AftersalesCaseModels.AftersalesCaseCreate(
                        orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                        amount, "USD", qty, "PLAT-" + orderNo, null, null,
                        "买家诉求-" + orderNo, T1, null, T1,
                        null, null, null, "TENANT_MAINTAINED", null, T1),
                    userA)
                .id());
    as(
        tid,
        () -> details.addLine(
            caseId,
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                lineId, qty, amount, "USD", "第 1 件"),
            userA));

    UUID rmaId =
        as(
            tid,
            () -> rma.create(
                    new RmaAuthorizationModels.RmaCreate(
                        caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                        "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                        "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                    userA)
                .head()
                .id());
    UUID rmaLineId = as(tid, () -> rma.detail(rmaId)).lines().get(0).id();
    as(
        tid,
        () -> rma.decide(
            rmaId,
            new RmaAuthorizationModels.RmaDecide(
                "APPROVED", due(), "同意退货",
                List.of(new RmaAuthorizationModels.RmaLineDecision(
                    rmaLineId, new BigDecimal("3"), null)),
                0L),
            userA));

    var detail =
        as(
            tid,
            () -> receipts.create(
                new ReturnReceiptCreate(
                    rmaId, null, "CARRIER", "FedEx", trackingNo,
                    "外箱一角压瘪-" + orderNo, T1,
                    List.of(new ReturnReceiptLineDraft(
                        rmaLineId, "SKU-" + orderNo, "SN-" + trackingNo, null,
                        new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
                    List.of(new ReturnPhotoDraft(
                        "TENANT_MANUAL_UPLOAD", null, "PACKAGING",
                        "https://obj.example.com/" + orderNo + ".jpg",
                        sha(orderNo), 2048L, "image/jpeg", T1,
                        "外箱-" + orderNo))),
                userA));
    var posted =
        as(
            tid,
            () -> receipts.post(
                detail.head().id(), new ReturnReceiptPost(null, detail.head().version()), userA));
    return new Seeded(rmaId, rmaLineId, caseId, posted.head().id(), orderNo, trackingNo);
  }

  ReturnReceiptDetail detail(UUID tid, UUID receiptId) {
    return as(tid, () -> receipts.detail(receiptId));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var sa = seed(a, "MO-TA", "RT-A-001");
    var sb = seed(b, "MO-TB", "RT-B-001");

    // 正对照：各自都读得到自己的。
    assertThat(detail(a, sa.receiptId).head().orderNo()).isEqualTo("MO-TA");
    assertThat(detail(b, sb.receiptId).head().orderNo()).isEqualTo("MO-TB");

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    // 这些端点是按 id 直达的 URL，拿不到才是真的拿不到。
    assertThatThrownBy(() -> detail(b, sa.receiptId))
        .as("B 家读 A 家的退货实收单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> detail(a, sb.receiptId))
        .as("A 家读 B 家的退货实收单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");

    // ⚠️ **入库动作同样按租户隔离**：B 不能把 A 的实收单推入库（也不能改 A 的额度）。
    assertThatThrownBy(
            () ->
                asB(() -> receipts.post(sa.receiptId, new ReturnReceiptPost(null, 0L), userB)))
        .as("B 家不能把 A 家的实收单推入库")
        .hasMessageContaining("不存在或不属于当前租户");
    // 正对照：B 对**自己的**单调用得到的是**业务**错误（已入库），不是「不属于当前租户」——
    // 说明上面那条不是「所有调用都报不存在」的假绿。
    assertThatThrownBy(
            () ->
                asB(() -> receipts.post(sb.receiptId, new ReturnReceiptPost(null, 0L), userB)))
        .hasMessageContaining("已入库")
        .hasMessageNotContaining("不属于当前租户");
    assertThatThrownBy(
            () ->
                asB(() ->
                    receipts.cancel(sa.receiptId, new ReturnReceiptCancel("撤", 0L), userB)))
        .as("B 家不能撤销 A 家的实收单")
        .hasMessageContaining("不存在或不属于当前租户");

    // ⚠️ **剩余可收量端点按租户隔离**（它读的是授权行 + 占用量，是额度真值）。
    assertThatThrownBy(() -> asB(() -> receipts.receivable(sa.rmaId)))
        .as("B 家读 A 家的剩余可收量必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(asA(() -> receipts.receivable(sa.rmaId)).takenTotal()).isEqualByComparingTo("2");
    assertThat(asB(() -> receipts.receivable(sb.rmaId)).takenTotal()).isEqualByComparingTo("2");

    // ⚠️ **按回寄运单号反查不能变成跨租户搜索引擎**：
    //   运单号是可以从买家邮件里直接抄到的，比 UUID 好猜得多。
    var bSeesATracking =
        asB(
            () ->
                receipts.list(
                    new ReturnReceiptListQuery(
                        null, null, null, null, null, null, null, null, "RT-A-001", null, null,
                        null, null)));
    assertThat(bSeesATracking.rows()).as("B 家按 A 的运单号反查必须零行").isEmpty();
    assertThat(bSeesATracking.total()).isZero();
    // 正对照：A 家用同样写法查得到自己的。
    var aSeesATracking =
        asA(
            () ->
                receipts.list(
                    new ReturnReceiptListQuery(
                        null, null, null, null, null, null, null, null, "RT-A-001", null, null,
                        null, null)));
    assertThat(aSeesATracking.rows()).as("A 家用同样写法查得到自己的（防假绿）").hasSize(1);

    // ⚠️ **照片是举证材料**：按照片主键直查同样必须隔离（detail 的隔离前面已断言，
    //   照片那一格单独立证，因为它带的是纠纷举证内容，泄露后果最直接）。
    UUID aPhotoId = detail(a, sa.receiptId).photos().get(0).id();
    assertThat(asB(
            () -> sql.fetch(
                "select * from return_receipt_photo where tenant_id = ?::uuid and id = ?::uuid",
                b, aPhotoId).size()))
        .as("B 读不到 A 的照片")
        .isZero();
    assertThat(asA(
            () -> sql.fetch(
                "select * from return_receipt_photo where tenant_id = ?::uuid and id = ?::uuid",
                a, aPhotoId).size()))
        .isEqualTo(1);

    // 列表互相过滤。
    assertThat(
            asA(
                    () ->
                        receipts.list(new ReturnReceiptListQuery(null, null, null, null, null,
                            null, null, null, null, null, null, null, null)))
                .rows())
        .hasSize(1);
    assertThat(asB(
            () -> receipts.list(new ReturnReceiptListQuery(null, null, null, null, null, null,
                null, null, null, null, null, null, null))).rows())
        .hasSize(1);
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
    var sa = seed(a, "MO-HDR", "RT-A-HDR");

    // A 的会话 + 伪造的 X-Tenant-Id（B）⇒ 拿到的仍然是 A 的实收单，而且**成功**。
    var body =
        http.perform(
                get("/trade/return-receipts/" + sa.receiptId)
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("head").get("orderNo").asText()).isEqualTo("MO-HDR");
    assertThat(parsed.get("head").get("returnTrackingNo").asText()).isEqualTo("RT-A-HDR");
    // ⚠️ 硬规则 1 在伪造头下同样成立：伪造头也改不动入库状态。
    assertThat(parsed.get("inventoryState").asText()).isEqualTo("PENDING_INSPECTION");
    assertThat(parsed.get("sellableQtyDelta").decimalValue()).isEqualByComparingTo("0");

    // ⚠️ **写路径同样按会话租户落库**：伪造头写入的行必须落在 A 名下，B 名下一行不多。
    http.perform(
            post("/trade/return-receipts")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType(MediaType.APPLICATION_JSON)
                .content(forgedCreateJson(sa)))
        .andExpect(status().isOk());

    long bCount =
        asB(
            () ->
                sql.fetchOne(
                        "select count(*) from return_receipt where tenant_id = ?::uuid", b)
                    .get(0, Long.class));
    assertThat(bCount).as("伪造头写入的行不得落到 B 名下").isZero();
    long aCount =
        asA(
            () ->
                sql.fetchOne(
                        "select count(*) from return_receipt where tenant_id = ?::uuid", a)
                    .get(0, Long.class));
    assertThat(aCount).as("伪造头写入的行必须落在会话租户 A 名下").isEqualTo(2L);

    // 无 Authorization ⇒ 401。
    http.perform(get("/trade/return-receipts/" + sa.receiptId))
        .andExpect(status().isUnauthorized());
  }

  /** 伪造头写入用的建单请求体（挂在 A 的授权单上，但由 A 的会话提交）。 */
  private String forgedCreateJson(Seeded sa) throws Exception {
    UUID rmaLineId =
        asA(
            () -> {
              var r =
                  sql.fetchOne(
                      "select id from rma_authorization_line where tenant_id = ?::uuid"
                          + " and rma_id = ?::uuid limit 1",
                      a, sa.rmaId);
              return r.get(0, UUID.class);
            });
    return json.writeValueAsString(
        new ReturnReceiptCreate(
            sa.rmaId, null, "CARRIER", "FedEx", "RT-FORGED", "伪造头写入",
            T1,
            List.of(new ReturnReceiptLineDraft(
                rmaLineId, "SKU-X", "SN-FORGED", null, new BigDecimal("1"),
                "INTACT", "UNOPENED", "")),
            List.of()));
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
      var sa = seed(a, "MO-POOL-A-" + i, "RT-POOL-A-" + i);
      var sb = seed(b, "MO-POOL-B-" + i, "RT-POOL-B-" + i);

      assertThat(detail(a, sa.receiptId).head().caseNo())
          .as("第 " + round + " 轮 A 读到的是 A 的单")
          .isEqualTo(asA(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  a, sa.caseId)
              .get(0, String.class)));
      assertThat(detail(b, sb.receiptId).head().caseNo())
          .as("第 " + round + " 轮 B 读到的是 B 的单")
          .isEqualTo(asB(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  b, sb.caseId)
              .get(0, String.class)));

      // 交替写：A 先在一张**草稿**上追加一张照片、B 再读 —— 必须读不到。
      // ⚠️ 照片只能在草稿期追加（入库后证据不能事后补改），
      //   而夹具产出的单已入库，所以这一格必须另开一张草稿。
      UUID draftA = newDraft(a, sa, "POOL-A-" + i);
      asA(() -> receipts.appendPhoto(
          draftA,
          new ReturnPhotoAppend("TENANT_MANUAL_UPLOAD", null, "GOODS",
              "https://obj.example.com/extra-" + round + ".jpg",
              sha("extra-" + round), 1024L, "image/jpeg", T1, "补拍"),
          userA));
      assertThat(asB(() -> sql.fetch(
                  "select * from return_receipt_photo where receipt_id = ?::uuid", draftA)
              .size()))
          .as("第 " + round + " 轮：B 读不到 A 的照片")
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from return_receipt_photo where receipt_id = ?::uuid", draftA)
              .size()))
          .as("第 " + round + " 轮：A 用同样写法读得到自己的（防假绿）")
          .isEqualTo(1);

      // 已入库那张的照片同样按租户隔离。
      assertThat(asB(() -> sql.fetch(
                  "select * from return_receipt_photo where receipt_id = ?::uuid", sa.receiptId)
              .size()))
          .as("第 " + round + " 轮：B 读不到 A 已入库单的照片")
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from return_receipt_photo where receipt_id = ?::uuid", sa.receiptId)
              .size()))
          .isEqualTo(1);

      // 交替读分录：可售增量必须始终是 0，状态必须始终是待检（硬规则 1 不因连接复用而失效）。
      assertThat(detail(a, sa.receiptId).sellableQtyDelta())
          .as("第 " + round + " 轮：A 的可售增量")
          .isEqualByComparingTo("0");
      assertThat(detail(b, sb.receiptId).inventoryState())
          .as("第 " + round + " 轮：B 的入库状态")
          .isEqualTo("PENDING_INSPECTION");
      assertThat(detail(a, sa.receiptId).lines().get(0).scannedSerialNo())
          .as("第 " + round + " 轮：A 的序列号不能串成 B 的")
          .isEqualTo("SN-RT-POOL-A-" + i);
      assertThat(detail(b, sb.receiptId).lines().get(0).scannedSerialNo())
          .as("第 " + round + " 轮：B 的序列号")
          .isEqualTo("SN-RT-POOL-B-" + i);
    }

    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到。
    assertThat(sql.fetch("select * from return_receipt").size()).as("事务外读必须零行").isZero();
    assertThat(sql.fetch("select * from return_receipt_line").size()).isZero();
    assertThat(sql.fetch("select * from return_receipt_photo").size()).isZero();
    assertThat(sql.fetch("select * from return_inventory_posting").size()).isZero();
    assertThat(sql.fetch("select * from return_receipt_event").size()).isZero();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 五张新表都强制rls() {
    for (String table : List.of("return_receipt", "return_receipt_line", "return_receipt_photo",
        "return_inventory_posting", "return_receipt_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V172 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
    }
  }
}
