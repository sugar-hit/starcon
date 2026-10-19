package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.trade.ReturnReceiptAllocationModels.*;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptCreate;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptLineDraft;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptPost;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.2.2 实收分配的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且实跑在本模块自己的五张表上
 * （return_receipt_allocation / return_receipt_allocation_line /
 * return_receipt_allocation_variance / order_line_returned_qty /
 * return_receipt_allocation_event），<b>不是</b>占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：分配行带着<b>逐条订单行与 SKU 快照</b>，
 * 差额说明带着<b>金额差</b>（财务敏感），已退数量带着<b>每行退了多少钱</b> ——
 * 而详情 / 对账 / 撤销端点都是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p>⚠️ <b>身份桩用注入式 stub，不用 JDK 内置 HttpServer</b>：
 * {@code HttpServer.create(new InetSocketAddress(0), 0)} 在全量并发下偶发端口被抢占
 * → AccessService 抛 503（本仓已知 flake）。这里用 {@link StubAccessService}
 * 覆写 {@code require()}，完全不经过 HTTP、不占端口，
 * 而 {@code run()}（租户事务 + RLS 绑定）仍是<b>真实实现</b>。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnReceiptAllocationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "测试租户", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
  }

  /**
   * 注入式身份桩：{@code require()} 从内存 map 取，<b>不发起任何 HTTP</b>。
   *
   * <p>⚠️ 判据与生产 {@link AccessService#require} <b>同一条</b>：
   * 权限码必须在某个 grant 里命中，且同一 grant 里必须同时有该动作与对应资源的
   * {@code .read}，否则「能写不能读」这种组合会被静默放行。
   */
  @TestConfiguration
  static class StubConfig {
    @Bean
    @Primary
    AccessService stubAccessService(
        DSLContext sql,
        PlatformTransactionManager manager,
        ObjectMapper json,
        @org.springframework.beans.factory.annotation.Value("${IDENTITY_URL}") String url,
        @org.springframework.beans.factory.annotation.Value("${INTERNAL_KEY}") String key) {
      return new StubAccessService(sql, manager, json, url, key);
    }
  }

  static final class StubAccessService extends AccessService {
    StubAccessService(
        DSLContext sql,
        PlatformTransactionManager manager,
        ObjectMapper json,
        String url,
        String key) {
      super(sql, manager, json, url, key);
    }

    @Override
    public Access require(String bearer, String permission) {
      Access found = actors.get(bearer);
      if (found == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效或身份服务不可用");
      }
      if (found.grants().stream()
          .noneMatch(
              g ->
                  g.permissions().contains(permission)
                      && g.permissions()
                          .contains(permission.substring(0, permission.lastIndexOf('.')) + ".read"))) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
      }
      return found;
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("DB_POOL_MAX_SIZE", () -> "1");
  }

  @Autowired ReturnReceiptAllocationService allocations;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  /** ⚠️ 必须相对当前时刻：V161 校验「寄回时限必须晚于当前时刻」，写死日历日期会随日历漂移成假失败。 */
  static final OffsetDateTime DUE = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_receipt_allocation_event, return_receipt_allocation_variance,"
              + " order_line_returned_qty, return_receipt_allocation_line,"
              + " return_receipt_allocation, return_receipt_photo, return_inventory_posting,"
              + " return_receipt_line, return_receipt_event, return_receipt,"
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
  // 夹具：每个租户各自建一条「订单 → 售后单 → 退货授权单 → 实收入库 → 分配」
  // ==========================================================================

  record Seeded(UUID orderId, UUID orderLineId, UUID rmaId, UUID receiptId, UUID allocId,
      UUID allocLineId, String orderNo, String receiptNo, String allocationNo) {}

  Seeded seed(UUID tid, String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal ordered = new BigDecimal("5");
    BigDecimal authorized = new BigDecimal("3");
    BigDecimal received = new BigDecimal("2");
    BigDecimal unitPrice = new BigDecimal("12.5000");
    BigDecimal amount = unitPrice.multiply(ordered);

    as(tid, () -> {
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
              + "created_at) values(?,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
          ordered, unitPrice, amount, T1);
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
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + orderNo, "买家-" + orderNo, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, orderId, lineId, ordered, ordered, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(tid, () -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", authorized, "PLAT-" + orderNo, null, null,
                    "买家诉求-" + orderNo, T1, null, T1, null, null, null, "TENANT_MAINTAINED",
                    null, T1),
                userA)
            .id());
    as(tid, () -> details.addLine(
        caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            lineId, authorized, amount, "USD", "第 1 行"),
        userA));

    UUID rmaId =
        as(tid, () -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                userA)
            .head()
            .id());
    UUID rmaLineId = as(tid, () -> rma.detail(rmaId)).lines().get(0).id();
    as(tid, () -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "APPROVED", DUE, "同意退货",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, authorized, null)),
            0L),
        userA));

    var draft = as(tid, () -> receipts.create(
        new ReturnReceiptCreate(
            rmaId, null, "CARRIER", "FedEx", "RT-" + orderNo, "外箱压损-" + orderNo, T1,
            List.of(new ReturnReceiptLineDraft(
                rmaLineId, "SKU-" + orderNo, "SN-" + orderNo, null, received,
                "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
            List.of()),
        userA));
    var posted = as(tid, () -> receipts.post(
        draft.head().id(), new ReturnReceiptPost(null, draft.head().version()), userA));
    UUID receiptId = posted.head().id();
    UUID receiptLineId = as(tid, () -> receipts.detail(receiptId)).lines().get(0).id();

    var alloc = as(tid, () -> allocations.create(
        new ReturnAllocationCreate(receiptId,
            List.of(new ReturnAllocationLineDraft(receiptLineId, null, null, received))),
        userA));

    return new Seeded(orderId, lineId, rmaId, receiptId, alloc.head().id(),
        alloc.lines().get(0).id(), orderNo, posted.head().receiptNo(), alloc.head().allocationNo());
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var sa = seed(a, "MO-TA");
    var sb = seed(b, "MO-TB");

    // 正对照：各自都读得到自己的。
    assertThat(asA(() -> allocations.detail(sa.allocId)).head().orderNo()).isEqualTo("MO-TA");
    assertThat(asB(() -> allocations.detail(sb.allocId)).head().orderNo()).isEqualTo("MO-TB");

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    assertThatThrownBy(() -> asB(() -> allocations.detail(sa.allocId)))
        .as("B 家读 A 家的实收分配单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> allocations.detail(sb.allocId)))
        .as("A 家读 B 家的实收分配单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");

    // ⚠️ **已退数量同样按租户隔离**：它带金额语义，B 读不到才是真的读不到。
    assertThatThrownBy(() -> asB(() -> allocations.orderReturns(sa.orderId)))
        .as("B 家读 A 家的订单行已退数量必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(asA(() -> allocations.orderReturns(sa.orderId)).returnedQty())
        .isEqualByComparingTo("2");
    assertThat(asB(() -> allocations.orderReturns(sb.orderId)).returnedQty())
        .isEqualByComparingTo("2");

    // ⚠️ **写动作同样隔离**：B 不能把 A 的分配单对账或撤销
    //   （对账会写差额说明、撤销会回冲已退数量，都是资金动作）。
    assertThatThrownBy(() -> asB(() -> allocations.reconcile(
            sa.allocId,
            new ReturnAllocationReconcile(
                null, null, null, null, null, null, "B 家不该能结转", 0L),
            userB)))
        .as("B 家不能对账 A 家的分配单")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asB(() -> allocations.reverse(
            sa.allocId, new ReturnAllocationReverse("B 家不该能撤销", 0L), userB)))
        .as("B 家不能撤销 A 家的分配单")
        .hasMessageContaining("不存在或不属于当前租户");
    // 正对照：A 的已退数量没被动过。
    assertThat(asA(() -> allocations.orderReturns(sa.orderId)).returnedQty())
        .isEqualByComparingTo("2");

    // ⚠️ **B 不能拿 A 的收货事件去分配**（否则会在 A 的账上凭空多出一张单）。
    // ⚠️ 夹具 id **必须在 lambda 外**取好：`asA(...)` 是嵌套在 `asB(...)` 里的一次
    //   租户事务（run → set_config → 复原），把另一个租户的读取写在 B 的事务内部，
    //   会让整段断言在「当前租户已被换成 A」的状态下执行 ——
    //   于是 B 读到了 A 的收货事件、又命中按收货事件的幂等返回，
    //   **不抛异常**，测试假绿。这不是产品缺陷，是夹具写法踩到了嵌套事务的边界。
    UUID aReceiptLineId = asA(() -> receipts.detail(sa.receiptId)).lines().get(0).id();
    assertThatThrownBy(() -> asB(() -> allocations.create(
            new ReturnAllocationCreate(sa.receiptId,
                List.of(new ReturnAllocationLineDraft(
                    aReceiptLineId, null, null, new BigDecimal("2")))),
            userB)))
        .as("B 家按 A 的收货事件分配必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================
  @Test
  void 断言2_伪造tenant_header无效() throws Exception {
    var sa = seed(a, "MO-TA");
    var sb = seed(b, "MO-TB");

    var json = new ObjectMapper().findAndRegisterModules();
    UUID aReceiptLineId = asA(() -> receipts.detail(sa.receiptId)).lines().get(0).id();
    var body = json.writeValueAsBytes(
        new ReturnAllocationCreate(
            sa.receiptId,
            List.of(new ReturnAllocationLineDraft(
                aReceiptLineId, null, null, new BigDecimal("2")))));

    // ⚠️ **正确预期是「仍然按会话正常执行并 200」**，不是 403：
    //   伪造头根本进不到业务逻辑里（租户只来自已认证成员关系），
    //   断言状态码的话，写成「返回 200」和写成「返回 400」都能骗过测试。
    //   真正要断言的是**数据落在会话租户名下** —— 比对两侧数据库。
    http.perform(post("/trade/receipt-allocations")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk());

    // A 家仍然只有一张分配单（幂等：重复提交同一收货事件返回同一张）。
    assertThat(asA(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, sa.receiptId, null, null, null, null, null, null, null))).rows())
        .as("伪造头不得新建第二张分配单（按收货事件去重）")
        .hasSize(1);
    // B 家一条也没有。
    assertThat(asB(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, sa.receiptId, null, null, null, null, null, null, null))).rows())
        .as("伪造头写入的行绝不能落在 B 家名下")
        .isEmpty();
    // 正对照：B 家自己的数据没被动过。
    assertThat(asB(() -> allocations.orderReturns(sb.orderId)).returnedQty())
        .isEqualByComparingTo("2");

    // ⚠️ **B 家会话 + 伪造 A 家头，改不动 A 家的分配单**。
    var before = asA(() -> allocations.detail(sa.allocId));
    http.perform(post("/trade/receipt-allocations/" + sa.allocId + "/reverse")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content(json.writeValueAsBytes(
                new ReturnAllocationReverse("B 家 + 伪造 A 家头", 0L))))
        // ⚠️ 期望 **400** 不是 404：本服务的 ApiErrorHandler 把 IllegalArgumentException
        //   统一映射成 400（「字段格式不正确」），不区分 404 / 400 ——
        //   断言 404 会让这条测试与全局错误约定绑死，而它要证明的其实是
        //   「B 家 + 伪造 A 家头**改不动** A 的数据」，下面逐项未变才是重点。
        .andExpect(status().isBadRequest());

    var after = asA(() -> allocations.detail(sa.allocId));
    assertThat(after.head().allocationStatus())
        .as("归属 / 状态逐项未变")
        .isEqualTo(before.head().allocationStatus());
    assertThat(after.head().version()).isEqualTo(before.head().version());
    assertThat(after.head().reverseReason())
        // ⚠️ 空串不是 null：reverse_reason 是 NOT NULL DEFAULT ''，
        //   「没被撤销」在库里表现为空串（照抄 V172 cancel_reason 的同一口径）。
        .as("撤销理由必须仍为空")
        .isEmpty();
    assertThat(asA(() -> allocations.orderReturns(sa.orderId)).returnedQty())
        .as("已退数量没被回冲")
        .isEqualByComparingTo("2");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // ⚠️ 5 轮交替读写，**每轮都新建实体** —— 否则测的是幂等不是隔离。
    //   连接池在 DB_POOL_MAX_SIZE=1（见 properties），只有复用同一条连接，
    //   「归还后不串租户」才真的被证明过。
    for (int round = 1; round <= 5; round++) {
      UUID ta = UUID.randomUUID();
      UUID tb = UUID.randomUUID();
      var sa = seed(a, "MO-A-" + round);
      var sb = seed(b, "MO-B-" + round);

      assertThat(asA(() -> allocations.detail(sa.allocId)).head().orderNo())
          .as("第 %d 轮 A 家读 A 家", round).isEqualTo("MO-A-" + round);
      assertThat(asB(() -> allocations.detail(sb.allocId)).head().orderNo())
          .as("第 %d 轮 B 家读 B 家", round).isEqualTo("MO-B-" + round);

      assertThatThrownBy(() -> asB(() -> allocations.detail(sa.allocId)))
          .as("第 %d 轮 B 家读 A 家必须失败", round)
          .hasMessageContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asA(() -> allocations.detail(sb.allocId)))
          .as("第 %d 轮 A 家读 B 家必须失败", round)
          .hasMessageContaining("不存在或不属于当前租户");

      // 交替顺序反过来再跑一遍，覆盖「上一轮是 B、这一轮先跑 A」的归还路径。
      assertThat(asA(() -> allocations.orderReturns(sa.orderId)).returnedQty())
          .as("第 %d 轮 A 家已退数量", round).isEqualByComparingTo("2");
      assertThat(asB(() -> allocations.orderReturns(sb.orderId)).returnedQty())
          .as("第 %d 轮 B 家已退数量", round).isEqualByComparingTo("2");
      assertThatThrownBy(() -> asB(() -> allocations.orderReturns(sa.orderId)))
          .as("第 %d 轮 B 家读 A 家已退数量必须失败", round)
          .hasMessageContaining("不存在或不属于当前租户");

      // 汇总按累计条数断言：第 N 轮两家各 1 条，本租户读到的是自己的 N 条。
      long ownA = asA(() -> allocations.list(new ReturnAllocationListQuery(
          null, null, null, null, null, null, null, null, null, null, null))).summary().total();
      long ownB = asB(() -> allocations.list(new ReturnAllocationListQuery(
          null, null, null, null, null, null, null, null, null, null, null))).summary().total();
      assertThat(ownA).as("第 %d 轮 A 家只看到自己的", round).isEqualTo(round);
      assertThat(ownB).as("第 %d 轮 B 家只看到自己的", round).isEqualTo(round);
      assertThat(ta).isNotEqualTo(tb);
    }
  }

  // ==========================================================================
  // HTTP 契约
  // ==========================================================================
  @Test
  void 无凭证读列表返回401() throws Exception {
    http.perform(get("/trade/receipt-allocations")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/receipt-allocations/capabilities"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 列表与能力声明端点返回200且带租户判据() throws Exception {
    http.perform(get("/trade/receipt-allocations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());
    http.perform(get("/trade/receipt-allocations/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedupRule").isNotEmpty())
        .andExpect(jsonPath("$.overReceiveRule").isNotEmpty());
  }
}
