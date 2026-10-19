package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.starcon.trade.ReturnReceiptAllocationModels.*;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptCreate;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptLineDraft;
import com.starcon.trade.ReturnReceiptModels.ReturnReceiptPost;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.2.2 实收分配的功能集成测试。
 *
 * <p>跑的是 AGENTS.md 明文要求的「每个新行为必须有测试」，且实跑在本模块自己的五张表上
 * （return_receipt_allocation / return_receipt_allocation_line /
 * return_receipt_allocation_variance / order_line_returned_qty /
 * return_receipt_allocation_event），<b>不是</b>占位探针表。
 *
 * <p><b>为什么不用 JDK 内置 HttpServer 起身份桩</b>：那条路在全量并发下偶发端口被抢占
 * → AccessService 抛 503（本仓已知 flake）。本类全部走<b>服务层</b>，
 * 租户事务用 {@link AccessService#run} 真实绑定，<b>不经过 HTTP、不占端口</b>。
 * 需要 HTTP 的三断言放在 {@link ReturnReceiptAllocationTenancyIntegrationTest}，
 * 那边用的是注入式 stub，同样不占端口。
 */
@SpringBootTest
class ReturnReceiptAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();

  static {
    pg.start();
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
    r.add("IDENTITY_URL", () -> "http://localhost:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ReturnReceiptAllocationService allocations;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired AccessService access;
  @Autowired DSLContext sql;

  static AccessService.Access actor() {
    var perms = List.of("trade.aftersales.read", "trade.aftersales.write");
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(
        userA, tenantA, "测试租户", UUID.randomUUID(), null, perms, grants);
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actor(), "trade.aftersales.write", work);
  }

  // ⚠️ **刻意带非零时区偏移**：OffsetDateTime 写回断言的价值就在这里。
  //   用 Z 的话，任何一次静默偏 8 小时的绑定错误都测不出来。
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  /**
   * 当前时刻（+08:00）。
   *
   * <p>⚠️ 寄回时限**必须用相对时间**：V161 有一条「买家寄回时限必须晚于当前时刻」的校验，
   * 写死一个日历日期会在某一天之后让整个夹具报「时限已过期」——
   * 而那与被测的实收分配毫无关系，是一个随日历漂移的假失败。
   */
  static OffsetDateTime now8() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 默认时限：两周后（未超期场景）。 */
  static OffsetDateTime dueSoon() {
    return now8().plusDays(14);
  }

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

  /** 夹具结果。 */
  record Seeded(
      UUID orderId, UUID orderLineId, UUID caseId, UUID caseLineId, UUID rmaId,
      UUID rmaLineId, UUID receiptId, UUID receiptLineId, String orderNo, String skuCode,
      /** 跨行分配场景下的第二条授权行 / 第二条订单行（单行夹具为 null）。 */
      UUID targetRmaLineId, UUID targetOrderLineId) {}

  /**
   * 稳定的 64 位十六进制校验和。
   *
   * <p>⚠️ 刻意不用 {@code seed.repeat(64 / seed.length())}：长度除不尽时得到 55 或 66 字符，
   * 而列是 {@code char(64)} —— 服务层当场拒掉，而错误消息说的是「必须给出 64 位十六进制」，
   * 看起来像是夹具写错了而不是长度算错了。
   */
  static String sha(String seed) {
    String base = Integer.toHexString(seed.hashCode());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; sb.length() < 64; i++) {
      sb.append(base);
    }
    return sb.substring(0, 64);
  }

  /**
   * 建「订单 → 售后单 → 退货授权单（授权量 authorized）→ 实收入库（实收 received）」一条链。
   *
   * <p>⚠️ {@code authorized} 与 {@code received} <b>刻意分开</b>：超收 / 少收两个方向
   * 都从这个夹具派生，而 V172 的额度闸门会拒掉 received &gt; 授权 ——
   * 所以「实收多于授权」在夹具里造不出来（见各测试的说明）。
   */
  Seeded seed(String tag, BigDecimal ordered, BigDecimal authorized, BigDecimal received,
      OffsetDateTime receivedAt, OffsetDateTime returnByAt) {
    UUID orderId = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    BigDecimal unitPrice = new BigDecimal("12.5000");
    BigDecimal amount = unitPrice.multiply(ordered);
    String orderNo = "MO-" + tag;

    as(() -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenantA, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          orderLineId, tenantA, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag,
          ordered, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, orderLineId, ordered, ordered, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", authorized, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1, null, null, null, "TENANT_MAINTAINED",
                    null, T1),
                userA)
            .id());
    as(() -> details.addLine(
        caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            orderLineId, authorized, amount, "USD", "第 1 件"),
        userA));

    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                userA)
            .head()
            .id());
    UUID rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();
    // ⚠️ **寄回时限直接写成 returnByAt**：超期退回的判定锚点就是它，
    //   写死比 now()+14d 更能让超期测试不随日历漂移。
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "APPROVED", returnByAt, "同意退货",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, authorized, null)),
            0L),
        userA));

    var draft = as(() -> receipts.create(
        new ReturnReceiptCreate(
            rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱压损-" + tag, receivedAt,
            List.of(new ReturnReceiptLineDraft(
                rmaLineId, "SKU-" + tag, "SN-" + tag, null, received,
                "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
            List.of()),
        userA));
    var posted = as(() -> receipts.post(
        draft.head().id(), new ReturnReceiptPost(null, draft.head().version()), userA));
    UUID receiptLineId =
        as(() -> receipts.detail(posted.head().id())).lines().get(0).id();

    return new Seeded(orderId, orderLineId, caseId, as(() -> rma.detail(rmaId)).lines().get(0)
        .caseLineId(), rmaId, rmaLineId, posted.head().id(), receiptLineId, orderNo, "SKU-" + tag,
        null, null);
  }

  Seeded seed(String tag) {
    return seed(tag, new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"), T1, dueSoon());
  }

  ReturnAllocationDetail allocate(Seeded s, BigDecimal qty) {
    return as(() -> allocations.create(
        new ReturnAllocationCreate(s.receiptId,
            List.of(new ReturnAllocationLineDraft(s.receiptLineId, null, null, qty))),
        userA));
  }

  // ==========================================================================
  // 硬规则①：按收货事件去重分配（资损防线）
  // ==========================================================================

  @Test
  void 同一收货事件重复提交返回同一张分配单且不重复累加已退数量() {
    Seeded s = seed("DEDUP");
    var first = allocate(s, new BigDecimal("2"));

    // 第二次提交**同样的**收货事件。
    var second = allocate(s, new BigDecimal("2"));

    assertThat(second.head().id())
        .as("重复提交同一收货事件必须返回同一张分配单（幂等），不能新建")
        .isEqualTo(first.head().id());
    assertThat(second.head().allocationNo()).isEqualTo(first.head().allocationNo());
    assertThat(second.head().version())
        .as("幂等返回不得推版本，否则界面会误以为发生了变更")
        .isEqualTo(first.head().version());

    // ★ 真正的断言在已退数量上：多提交一次不能多记一次。
    var returned = as(() -> allocations.orderReturns(s.orderId()));
    assertThat(returned.returnedQty())
        .as("重复提交后已退数量必须是 2（实收数），不是 4")
        .isEqualByComparingTo("2");
    assertThat(returned.lines()).as("一个订单行只有一行已退数量（UNIQUE 钉死）").hasSize(1);
    assertThat(returned.lines().get(0).allocationCount())
        .as("累加次数是 1 —— 重复提交没有触发第二次累加")
        .isEqualTo(1);
  }

  @Test
  void 绕过服务层直连SQL硬插第二条分配被唯一约束拒绝() {
    Seeded s = seed("DEDUP-RAW");
    allocate(s, new BigDecimal("2"));

    // ⚠️ **绕过服务层直连 SQL**：唯一约束是第一道闸门，服务层的先查只是体验。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "insert into return_receipt_allocation(id,tenant_id,allocation_no,idempotency_key,"
                + "receipt_id,rma_id,case_id,order_id,received_at,overdue_returned,days_overdue,"
                + "allocation_status,allocated_qty,over_received_qty,short_received_qty,"
                + "variance_amount,allocated_by,allocated_at,version,created_at,updated_at)"
                + " values(?::uuid,?::uuid,'RAW-DUP','RECEIPT:'||?::text,?::uuid,?::uuid,?::uuid,"
                + "?::uuid,?::timestamptz,false,0,'ALLOCATED',2,0,0,0,?::uuid,?::timestamptz,0,"
                + "?::timestamptz,?::timestamptz)",
            UUID.randomUUID(), tenantA, s.receiptId, s.receiptId, s.rmaId, s.caseId,
            s.orderId, T1, userA, T1, T1, T1)))
        // ⚠️ 断言**约束名**而不是中文「重复键」：容器是 C locale，
        //   PG 的报错文本是英文的（"duplicate key value violates unique constraint ..."），
        //   按中文断言会在任何非中文 locale 的环境里假失败，
        //   而约束名是 locale 无关的。
        .as("绕过服务层直连 SQL 也插不进第二条分配单")
        .hasMessageContaining("idempotency_key");

    // ★ 并且已退数量**没有被**动过：唯一约束是整条语句失败，不是部分写入。
    assertThat(as(() -> allocations.orderReturns(s.orderId())).returnedQty())
        .isEqualByComparingTo("2");
  }

  @Test
  void 已退数量只能由DB触发器改运行角色改不动() {
    Seeded s = seed("REVO");
    allocate(s, new BigDecimal("2"));

    // ⚠️ 正对照：运行角色**能读**（否则下面的拒绝证明不了什么）。
    assertThat(as(() -> sql.fetchOne(
            "select returned_qty from order_line_returned_qty"
                + " where tenant_id = ?::uuid and order_line_id = ?::uuid",
            tenantA, s.orderLineId)).get("returned_qty", BigDecimal.class))
        .isEqualByComparingTo("2");

    // ⚠️ 断言「抛的是数据库权限异常」+「数据没变」，而不是匹配中文「权限」：
    //   PG 的报错文本随 locale 变（测试容器是 C locale，报英文），
    //   按中文匹配会在任何别的 locale 下假失败；而类型与结果都是 locale 无关的。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update order_line_returned_qty set returned_qty = 99"
                + " where tenant_id = ?::uuid and order_line_id = ?::uuid",
            tenantA, s.orderLineId)))
        .as("已退数量是账：运行角色的 UPDATE 必须在 DB 层被拒")
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> as(() -> sql.execute(
            "delete from order_line_returned_qty where tenant_id = ?::uuid and order_line_id = ?::uuid",
            tenantA, s.orderLineId)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 正对照：值仍是 2，两次越权写入都没生效。
    assertThat(as(() -> sql.fetchOne(
            "select returned_qty from order_line_returned_qty"
                + " where tenant_id = ?::uuid and order_line_id = ?::uuid",
            tenantA, s.orderLineId)).get("returned_qty", BigDecimal.class))
        .as("越权写入后已退数量必须仍是 2")
        .isEqualByComparingTo("2");
  }

  @Test
  void 差额说明与留痕对运行角色是追加式的() {
    Seeded s = seed("APPEND");
    var d = allocate(s, new BigDecimal("2"));
    as(() -> allocations.reconcile(
        d.head().id(),
        new ReturnAllocationReconcile(
            null, null, true, "少收已核实", null, null, "差额已结转", d.head().version()),
        userA));

    assertThatThrownBy(() -> as(() -> sql.execute(
            "update return_receipt_allocation_variance set explanation = '改掉了'"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            tenantA, d.head().id())))
        .as("差额说明是财务证据：运行角色改不动")
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> as(() -> sql.execute(
            "delete from return_receipt_allocation_event where tenant_id = ?::uuid and allocation_id = ?::uuid",
            tenantA, d.head().id())))
        .as("分配留痕是资损追责的证据：运行角色删不掉")
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // ==========================================================================
  // 硬规则②：超收按实际退回、少收同理、不得静默截断
  // ==========================================================================

  @Test
  void 少收按实际分摊记账并产出说明() {
    // 实收 3 件、只分配 2 件到订单行 ⇒ 少收 1 件（单件单价 12.50 ⇒ 金额差 −12.50）。
    Seeded s = seed("UNDER", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("3"), T1,
        dueSoon());
    var d = allocate(s, new BigDecimal("2"));

    var line = d.lines().get(0);
    assertThat(line.allocatedQty()).as("分配数就是实际落账数，不补齐").isEqualByComparingTo("2");
    assertThat(line.shortReceivedQty()).as("少收 1 件").isEqualByComparingTo("1");
    assertThat(line.overReceivedQty()).isEqualByComparingTo("0");
    // ⚠️ 金额差是**负数**（少收的钱要补），且由 DB 触发器算、单件 12.50。
    assertThat(line.varianceAmount()).as("少收的金额差为负").isEqualByComparingTo("-12.5000");

    assertThat(d.head().shortReceivedQty()).isEqualByComparingTo("1");
    assertThat(d.head().overReceivedQty()).isEqualByComparingTo("0");

    // 少收**不静默**：必须有一条说明才能对账。
    var reconciled = as(() -> allocations.reconcile(
        d.head().id(),
        new ReturnAllocationReconcile(
            null, null, true, "少收 1 件已与买家确认不再寄回", null, null,
            "少收已结转，差额不再追", d.head().version()),
        userA));
    assertThat(reconciled.variances()).hasSize(1);
    var v = reconciled.variances().get(0);
    assertThat(v.varianceType()).isEqualTo("UNDER_RECEIVED");
    assertThat(v.varianceQty()).isEqualByComparingTo("1");
    assertThat(v.varianceAmount()).as("少收金额差为负").isEqualByComparingTo("-12.5000");
    assertThat(v.resolution()).isEqualTo("ACCEPTED_AS_ACTUAL");
    assertThat(v.explanation()).contains("不静默补齐");
    assertThat(v.currency()).isEqualTo("USD");
  }

  @Test
  void 超收按实际退回记账不被截断且金额差落NUMERIC() {
    // ⚠️ **超收的真实来源是跨行分配**，不是「同一授权行上实收多于授权」：
    //   后者在 V172 的登记闸门（trg_guard_return_receipt_line）就已经被拒，
    //   任何夹具都造不出来 —— 那是 11.2.2.1 的职责边界。
    //   真实场景是：源授权行额度充足**不代表**目标授权行额度充足。
    //   夹具：同一条退货授权单下两条授权行 —— A 授权 3 件（实收 3 件），
    //         B 只授权 1 件；把 A 的这 3 件**改分配**到 B ⇒ 超收 2 件。
    Seeded s = seedCrossLine("OVER");

    var d = as(() -> allocations.create(
        new ReturnAllocationCreate(s.receiptId,
            List.of(new ReturnAllocationLineDraft(
                s.receiptLineId, s.targetRmaLineId(), s.targetOrderLineId(), new BigDecimal("3")))),
        userA));

    var line = d.lines().get(0);
    // ★ **按实际退回**：`allocatedQty` 是实收数 3，**不是** clamp 成的 1。
    assertThat(line.allocatedQty()).as("分配数不被截断：授权 1 件、货来了 3 件，记账仍然是 3")
        .isEqualByComparingTo("3");
    assertThat(line.targetAuthorizedQty()).isEqualByComparingTo("1");
    assertThat(line.remainingBeforeAlloc()).as("目标行只剩 1 件可收").isEqualByComparingTo("1");
    assertThat(line.overReceivedQty()).as("超收 2 件").isEqualByComparingTo("2");
    assertThat(line.shortReceivedQty()).isEqualByComparingTo("0");
    // ★ 金额差 = 2 × 单价 12.50 = 25.00，落 NUMERIC(19,4)，**禁浮点记账**。
    assertThat(line.varianceAmount()).isEqualByComparingTo("25.0000");

    assertThat(d.head().overReceivedQty()).isEqualByComparingTo("2");
    assertThat(d.head().allocatedQty()).as("头部的分配数同样是实收数，不截断")
        .isEqualByComparingTo("3");
    assertThat(d.head().varianceAmount()).isEqualByComparingTo("25.0000");

    // 超收**必须**能结转成一条有正文的说明，否则「多退的钱」在库里不可追。
    var reconciled = as(() -> allocations.reconcile(
        d.head().id(),
        new ReturnAllocationReconcile(
            true, "买家多寄了 2 件，同意按实际退回多退",
            null, null, null, null, "超收已按实际退回结转", d.head().version()),
        userA));
    var v = reconciled.variances().get(0);
    assertThat(v.varianceType()).isEqualTo("OVER_RECEIVED");
    assertThat(v.varianceQty()).isEqualByComparingTo("2");
    assertThat(v.varianceAmount()).isEqualByComparingTo("25.0000");
    assertThat(v.explanation()).contains("按**实际退回**记账（不截断）");
    assertThat(v.resolution()).isEqualTo("ACCEPTED_AS_ACTUAL");

    // 退得比订购还多：目标订单行只订了 2 件，却退了 3 件。
    var returned = as(() -> allocations.orderReturns(s.orderId()));
    var targetLine = returned.lines().stream()
        .filter(l -> l.orderLineId().equals(s.targetOrderLineId())).findFirst().orElseThrow();
    assertThat(targetLine.returnedQty()).isEqualByComparingTo("3");
    assertThat(targetLine.orderedQty()).isEqualByComparingTo("2");
    assertThat(targetLine.overOrderedQty()).as("退 3 件但只订 2 件 ⇒ 超出订购 1 件")
        .isEqualByComparingTo("1");
    assertThat(targetLine.exceedsOrderedQty()).isTrue();
  }

  @Test
  void 跨订单分配被拒而跨行分配允许() {
    Seeded s = seedCrossLine("CROSS");
    UUID foreignOrderLine = as(() -> {
      UUID orderId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      BigDecimal unitPrice = new BigDecimal("12.5000");
      BigDecimal amount = unitPrice.multiply(new BigDecimal("5"));
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              // ⚠️ 状态取 DRAFT：SUBMITTED 会触发「没有收货地址快照」的下单守卫，
              //   而这里只需要一条**属于别的订单**的商品行，不必走完下单流程。
              + " values(?::uuid,?::uuid,'MO-FOREIGN','AMAZON','EXT-FOREIGN','USD','PREPAID',"
              + "'DRAFT',null,?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,"
              + "?::timestamptz)",
          orderId, tenantA, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,'SKU-FOREIGN','别家商品',"
              + "'',?::numeric,?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tenantA, orderId, UUID.randomUUID(), new BigDecimal("5"),
          new BigDecimal("1.0000"), new BigDecimal("5.0000"), T1);
      return lineId;
    });

    assertThatThrownBy(() -> as(() -> allocations.create(
            new ReturnAllocationCreate(s.receiptId,
                List.of(new ReturnAllocationLineDraft(
                    s.receiptLineId, null, foreignOrderLine, new BigDecimal("3")))),
            userA)))
        .as("跨订单分配会让「这批货退给了谁」变成两笔资金事实")
        .hasMessageContaining("同一张销售订单");
  }

  @Test
  void 目标授权行不属于本单时分配被拒() {
    Seeded s = seedCrossLine("OTHER-RMA");
    Seeded other = seed("OTHER-RMA-2");
    assertThatThrownBy(() -> as(() -> allocations.create(
            new ReturnAllocationCreate(s.receiptId,
                List.of(new ReturnAllocationLineDraft(
                    s.receiptLineId, other.rmaLineId, null, new BigDecimal("3")))),
            userA)))
        .as("「A 单的分配指向 B 单的授权行」在库里必须不合法")
        .hasMessageContaining("不属于本分配单所关联的退货授权单");
  }

  @Test
  void 同一收货事件内的多行彼此不占用目标额度() {
    // ⚠️ 一张分配单里的两行若互相占用，每一行都会被判成超收。
    //   口径 = 其它**收货事件**的分配量，同一单内的行彼此不占。
    //   形状：实收 3 件拆成两行 —— 1 件落第一条授权行（授权 3）、2 件落第二条（授权 1）。
    Seeded s = seedCrossLine("SAME-RECEIPT");
    var d = as(() -> allocations.create(
        new ReturnAllocationCreate(s.receiptId,
            List.of(
                new ReturnAllocationLineDraft(s.receiptLineId, s.rmaLineId, s.orderLineId(),
                    new BigDecimal("1")),
                new ReturnAllocationLineDraft(s.receiptLineId, s.targetRmaLineId(),
                    s.targetOrderLineId(), new BigDecimal("2")))),
        userA));

    assertThat(d.lines()).hasSize(2);
    assertThat(d.lines()).allSatisfy(l ->
        assertThat(l.targetTakenQty()).as("同一收货事件内的行彼此不占用额度")
            .isEqualByComparingTo("0"));
    // 第一行落在授权 3 的行上：不满，不超。
    assertThat(d.lines().get(0).overReceivedQty()).isEqualByComparingTo("0");
    // 第二行落在只授权 1 的行上：超 1 件。
    assertThat(d.lines().get(1).overReceivedQty()).isEqualByComparingTo("1");
    assertThat(d.head().allocatedQty()).isEqualByComparingTo("3");
    assertThat(d.head().overReceivedQty()).isEqualByComparingTo("1");
  }

  @Test
  void 超收由DB触发器算出来调用方传什么都不影响() {
    // 直接改分配行试图伪造 over_received_qty：触发器会在写时刻重算。
    Seeded s = seed("FORGE");
    var d = allocate(s, new BigDecimal("2"));

    assertThatThrownBy(() -> as(() -> sql.execute(
            "update return_receipt_allocation_line set allocated_qty = 999::numeric,"
                + " over_received_qty = 0::numeric"
                + " where tenant_id = ?::uuid and allocation_id = ?::uuid",
            tenantA, d.head().id())))
        .as("分摊数量不得超过该实收行的实收数（否则就是凭空多记已退数量）")
        .hasMessageContaining("超过该实收行的实收数");
  }

  @Test
  void 超额分摊被拒而静默截断不可能发生() {
    Seeded s = seed("CLAMP", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"), T1,
        dueSoon());
    assertThatThrownBy(() -> allocate(s, new BigDecimal("3")))
        .as("实收 2 件时不能分摊 3 件 —— 截断成 2 件同样是错的（会掩盖实收数量）")
        .hasMessageContaining("超过该实收行的实收数");
  }

  // ==========================================================================
  // 超期退回：OffsetDateTime 写回值必须被断言
  // ==========================================================================

  @Test
  void 超期退回按收货时刻与寄回时限判定且时刻零漂移() {
    OffsetDateTime returnByAt = now8().plusDays(10);
    OffsetDateTime receivedAt = returnByAt.plusDays(4).withHour(9).withMinute(30);
    Seeded s = seed("OVERDUE", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"),
        receivedAt, returnByAt);
    var d = allocate(s, new BigDecimal("2"));

    assertThat(d.head().overdueReturned()).as("收货晚于寄回时限 ⇒ 超期退回").isTrue();
    assertThat(d.head().daysOverdue()).isEqualTo(4);
    // ⚠️ **写回值断言**（AGENTS.md 时间纪律）：用 +08:00 写入、读回必须是同一个瞬间。
    //   若绑定时用 Timestamp.valueOf(LocalDateTime)，这里会偏 −28800s（−8 小时），
    //   daysOverdue 也会跟着算成 -4 —— 而超期判定完全依赖这两个值。
    assertThat(d.head().returnByAt())
        .as("寄回时限写回必须零漂移")
        .isEqualTo(returnByAt);
    assertThat(d.head().receivedAt())
        .as("收货时刻写回必须零漂移（带 +08:00 偏移）")
        .isEqualTo(receivedAt);
    assertThat(d.lines().get(0).allocatedAt().getOffset())
        .as("分配时刻以 UTC 绑定，读回偏移可不同但瞬时值相同")
        .isNotNull();

    var reconciled = as(() -> allocations.reconcile(
        d.head().id(),
        new ReturnAllocationReconcile(
            null, null, null, null, true, "买家晚寄，已接受",
            "超期退回已结转", d.head().version()),
        userA));
    var overdue = reconciled.variances().stream()
        .filter(v -> v.varianceType().equals("OVERDUE_RETURNED")).findFirst().orElseThrow();
    assertThat(overdue.explanation()).contains("不拒收");
    assertThat(overdue.resolution()).isEqualTo("ACCEPTED_AS_ACTUAL");
  }

  @Test
  void 未超期的收货不被标成超期() {
    OffsetDateTime returnByAt = dueSoon();
    OffsetDateTime receivedAt = T1;
    Seeded s = seed("ONTIME", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"),
        receivedAt, returnByAt);
    var d = allocate(s, new BigDecimal("2"));
    assertThat(d.head().overdueReturned()).isFalse();
    assertThat(d.head().daysOverdue()).isZero();
  }

  // ==========================================================================
  // 状态机 / 撤销 / 对账纪律
  // ==========================================================================

  @Test
  void 撤销分配回冲已退数量但保留已分配量() {
    Seeded s = seed("REV");
    var d = allocate(s, new BigDecimal("2"));

    var reversed = as(() -> allocations.reverse(
        d.head().id(), new ReturnAllocationReverse("跨行分配选错了商品行", d.head().version()),
        userA));

    assertThat(reversed.head().allocationStatus()).isEqualTo("REVERSED");
    var returned = as(() -> allocations.orderReturns(s.orderId()));
    assertThat(returned.returnedQty()).as("撤销后已退数量回冲到 0").isEqualByComparingTo("0");
    assertThat(returned.allocatedQty())
        .as("已分配量**保留** —— 两者之差就是财务要追问的部分")
        .isEqualByComparingTo("2");
    assertThat(returned.lines().get(0).unreconciledQty()).isEqualByComparingTo("2");
  }

  @Test
  void 已对账或已撤销的分配单不可再改且必须写理由() {
    Seeded s = seed("FINAL");
    var d = allocate(s, new BigDecimal("2"));
    var reversed = as(() -> allocations.reverse(
        d.head().id(), new ReturnAllocationReverse("录错了", d.head().version()), userA));

    assertThatThrownBy(() -> as(() -> allocations.reverse(
            reversed.head().id(), new ReturnAllocationReverse("再撤一次", 0L), userA)))
        .as("终态不能再流转（已退数量是资金相关的累计量）")
        .hasMessageContaining("终态");

    Seeded s2 = seed("FINAL2");
    var d2 = allocate(s2, new BigDecimal("2"));
    assertThatThrownBy(() -> as(() -> allocations.reverse(
            d2.head().id(), new ReturnAllocationReverse("  ", d2.head().version()), userA)))
        .as("撤销必须说明理由：没有理由的撤销等于给已退数量凭空开一个洞")
        .hasMessageContaining("必须说明撤销理由");
  }

  @Test
  void 版本不符与缺失版本都拒绝() {
    Seeded s = seed("VER");
    var d = allocate(s, new BigDecimal("2"));
    assertThatThrownBy(() -> as(() -> allocations.reverse(
            d.head().id(), new ReturnAllocationReverse("撤", 99L), userA)))
        .hasMessageContaining("版本不符");
    assertThatThrownBy(() -> as(() -> allocations.reverse(
            d.head().id(), new ReturnAllocationReverse("撤", null), userA)))
        .hasMessageContaining("expectedVersion");
  }

  @Test
  void 认可差额必须写清依据() {
    Seeded s = seed("ACCEPT", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("3"), T1,
        dueSoon());
    var d = allocate(s, new BigDecimal("2"));
    assertThatThrownBy(() -> as(() -> allocations.reconcile(
            d.head().id(),
            new ReturnAllocationReconcile(
                null, null, true, "   ", null, null, "结转", d.head().version()),
            userA)))
        .as("「认可」必须写清依据（表级 CHECK 同一条纪律）")
        .hasMessageContaining("必须写清依据");
  }

  @Test
  void 对账必须给出结论文字() {
    Seeded s = seed("NOTE", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("3"), T1,
        dueSoon());
    var d = allocate(s, new BigDecimal("2"));
    assertThatThrownBy(() -> as(() -> allocations.reconcile(
            d.head().id(),
            new ReturnAllocationReconcile(null, null, false, "待核实", null, null, "  ",
                d.head().version()),
            userA)))
        .hasMessageContaining("必须给出对账说明");
  }

  @Test
  void 只有已入库的实收单才能分配() {
    // 草稿状态的实收单：货没进待检，已退数量先走一步会让两份真相。
    Seeded s = seed("DRAFT");
    UUID draftId = as(() -> receipts.create(
        new ReturnReceiptCreate(
            s.rmaId, null, "CARRIER", "FedEx", "RT-DRAFT", "草稿", T1,
            List.of(new ReturnReceiptLineDraft(
                s.rmaLineId, "SKU-X", "SN-DRAFT", null, BigDecimal.ONE,
                "INTACT", "UNOPENED", "")),
            List.of()),
        userA)).head().id();
    UUID draftLineId = as(() -> receipts.detail(draftId)).lines().get(0).id();

    assertThatThrownBy(() -> as(() -> allocations.create(
            new ReturnAllocationCreate(draftId, List.of(
                new ReturnAllocationLineDraft(draftLineId, null, null, BigDecimal.ONE))),
            userA)))
        .as("未入库的实收单不能分配")
        .hasMessageContaining("只有已入库的退货实收单才能分配");
  }

  // ==========================================================================
  // 多租户：RLS / NOT NULL / GRANT 的结构性断言
  // ==========================================================================

  @Test
  void 五张表逐张断言tenant_id_NOT_NULL与FORCE_RLS() throws Exception {
    List<String> tables = List.of(
        "return_receipt_allocation", "return_receipt_allocation_line",
        "return_receipt_allocation_variance", "order_line_returned_qty",
        "return_receipt_allocation_event");

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (String t : tables) {
        var rs = s.executeQuery(
            "select relforcerowsecurity, relrowsecurity from pg_class where relname = '"
                + t + "'");
        assertThat(rs.next()).as("表存在：" + t).isTrue();
        assertThat(rs.getBoolean("relrowsecurity")).as("RLS 开着：" + t).isTrue();
        assertThat(rs.getBoolean("relforcerowsecurity"))
            .as("FORCE RLS（表所有者也走隔离）：" + t).isTrue();
      }

      for (String t : tables) {
        var rs = s.executeQuery(
            "select is_nullable from information_schema.columns"
                + " where table_name = '" + t + "' and column_name = 'tenant_id'");
        assertThat(rs.next()).as("tenant_id 存在：" + t).isTrue();
        assertThat(rs.getString("is_nullable"))
            .as("tenant_id NOT NULL（AGENTS.md 明文要求）：" + t).isEqualTo("NO");
      }
    }
  }

  @Test
  void 数量与金额列都是NUMERIC没有浮点() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var rs = s.executeQuery(
          "select table_name, column_name, data_type, numeric_precision, numeric_scale"
              + " from information_schema.columns"
              + " where (table_name, column_name) in ("
              + " ('return_receipt_allocation','variance_amount'),"
              + " ('return_receipt_allocation','allocated_qty'),"
              + " ('return_receipt_allocation_line','over_received_qty'),"
              + " ('order_line_returned_qty','returned_qty'))"
              + " order by table_name, column_name");
      int n = 0;
      while (rs.next()) {
        assertThat(rs.getString("data_type"))
            .as(rs.getString("table_name") + "." + rs.getString("column_name") + " 必须是 NUMERIC（禁浮点）")
            .isEqualTo("numeric");
        n++;
      }
      assertThat(n).as("四列都查到了").isEqualTo(4);
    }
  }

  @Test
  void 幂等键形状由CHECK钉死() {
    Seeded s = seed("KEY");
    var d = allocate(s, new BigDecimal("2"));
    assertThat(d.head().idempotencyKey())
        .as("幂等键恒为 'RECEIPT:' + receiptId（不接受自由文本）")
        .isEqualTo("RECEIPT:" + s.receiptId);

    assertThatThrownBy(() -> as(() -> sql.execute(
            "update return_receipt_allocation set idempotency_key = '随便写一个'"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantA, d.head().id())))
        .as("自由文本幂等键会让「同一收货事件用了两个键」成为可能")
        .hasMessageContaining("idempotency_key");
  }

  // ==========================================================================
  // 列表：真分页 / 排序白名单 / 模糊检索
  // ==========================================================================

  @Test
  void 真分页两页不重叠且汇总不受分页() {
    Seeded a = seed("P1");
    Seeded b = seed("P2");
    allocate(a, new BigDecimal("2"));
    allocate(b, new BigDecimal("1"));

    var p1 = as(() -> allocations.list(new ReturnAllocationListQuery(
        0, 1, null, null, null, null, null, null, null, null, null)));
    var p2 = as(() -> allocations.list(new ReturnAllocationListQuery(
        1, 1, null, null, null, null, null, null, null, null, null)));

    assertThat(p1.total()).isEqualTo(2);
    assertThat(p1.rows()).hasSize(1);
    assertThat(p2.rows()).hasSize(1);
    assertThat(p1.rows().get(0).id()).isNotEqualTo(p2.rows().get(0).id());
    assertThat(p1.summary().total())
        .as("汇总跨全量算，翻页不跳")
        .isEqualTo(2);
    assertThat(p1.summary().allocatedQty()).isEqualByComparingTo("3");
  }

  @Test
  void 排序白名单拒绝任意表达式() {
    Seeded s = seed("SORT");
    allocate(s, new BigDecimal("2"));
    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, null, null, null,
            "allocated_at; drop table return_receipt_allocation"))))
        .as("排序键落回白名单默认值，不进 SQL 文本")
        .isNotNull();
  }

  @Test
  void 真实模糊检索命中分配单号与订单号() {
    Seeded s = seed("KW");
    var d = allocate(s, new BigDecimal("2"));
    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, null, null, d.head().allocationNo(), null)))
            .rows()).as("按分配单号命中").hasSize(1);
    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, null, null, s.orderNo, null)))
            .rows()).as("按订单号命中").hasSize(1);
    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, null, null, "不存在的关键词", null)))
            .rows()).isEmpty();
  }

  @Test
  void 非法状态筛选值被拒而不是静默返回零行() {
    Seeded s = seed("BADF");
    allocate(s, new BigDecimal("2"));
    assertThatThrownBy(() -> as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, "NOT_A_STATUS", null, null, null, null, null, null, null, null))))
        .as("筛选值先过枚举：表里恰好没匹配行时 CHECK 不会被求值，会静默返回零行")
        .hasMessageContaining("取值非法");
  }

  @Test
  void 仅超期与仅差额筛选生效() {
    OffsetDateTime lateDue = now8().plusDays(2);
    Seeded over = seed("FILTER-OVERDUE", new BigDecimal("5"), new BigDecimal("3"),
        new BigDecimal("2"), lateDue.plusDays(3), lateDue);
    allocate(over, new BigDecimal("2"));
    Seeded variance = seed("FILTER-VAR", new BigDecimal("5"), new BigDecimal("3"),
        new BigDecimal("3"), T1, dueSoon());
    allocate(variance, new BigDecimal("2"));

    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, true, null, null, null))).rows())
        .as("仅超期：只命中迟到那批").hasSize(1);
    assertThat(as(() -> allocations.list(new ReturnAllocationListQuery(
            null, null, null, null, null, null, null, null, true, null, null))).rows())
        .as("仅差额：只命中少收那批").hasSize(1);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明把两条硬规则与未交付下游如实报出来() {
    var c = as(() -> allocations.capabilities());
    assertThat(c.dedupRule()).contains("按收货事件去重");
    assertThat(c.overReceiveRule()).contains("不被截断");
    assertThat(c.overdueRule()).contains("return_by_at");
    assertThat(c.returnedQtyRule()).contains("order_line_returned_qty");

    var finance = c.relatedModules().stream()
        .filter(m -> m.id().equals("MODULE_11_2_3_3")).findFirst().orElseThrow();
    assertThat(finance.delivered()).as("11.2.3.3 未交付必须如实报 false").isFalse();
    assertThat(finance.ownerModule()).isEqualTo("11.2.3.3");

    var duplicate = c.actions().stream()
        .filter(a -> a.code().equals("DUPLICATE")).findFirst().orElseThrow();
    assertThat(duplicate.allowed())
        .as("重复分配入口永远不存在（传输层刻意没有 force 开关）").isFalse();

    var markResolved = c.actions().stream()
        .filter(a -> a.code().equals("MARK_RESOLVED")).findFirst().orElseThrow();
    assertThat(markResolved.allowed()).as("「已解决」刻意不存在").isFalse();
  }

  /**
   * 跨行分配夹具：一条订单 **两条商品行**、一条售后单 **两条售后行**、
   * 一张退货授权单 **两条授权行**（第一条授权 3 件、第二条只授权 1 件）、
   * 一个收货事件实收 3 件且挂在**第一条**授权行上。
   *
   * <p>这是**超收唯一真实可达**的形状：V172 的登记闸门已经拒掉了
   * 「同一授权行上实收多于授权」，而源授权行额度充足**不代表**目标授权行额度充足。
   */
  Seeded seedCrossLine(String tag) {
    UUID orderId = UUID.randomUUID();
    UUID line1 = UUID.randomUUID();
    UUID line2 = UUID.randomUUID();
    BigDecimal unitPrice = new BigDecimal("12.5000");
    BigDecimal ordered1 = new BigDecimal("5");
    BigDecimal ordered2 = new BigDecimal("2");
    String orderNo = "MO-" + tag;
    OffsetDateTime returnByAt = dueSoon();

    as(() -> {
      BigDecimal amount = unitPrice.multiply(ordered1).add(unitPrice.multiply(ordered2));
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenantA, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          line1, tenantA, orderId, UUID.randomUUID(), "SKU-" + tag + "-1", "商品一号",
          ordered1, unitPrice, unitPrice.multiply(ordered1), T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,2,?::uuid,?,?,'黑色 / XL',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          line2, tenantA, orderId, UUID.randomUUID(), "SKU-" + tag + "-2", "商品二号",
          ordered2, unitPrice, unitPrice.multiply(ordered2), T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, line1, ordered1, ordered1, T1, T1, T1, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenantA, orderId, line2, ordered2, ordered2, T1, T1, T1, T1);
      return null;
    });

    BigDecimal caseQty = new BigDecimal("4");
    BigDecimal caseAmount = unitPrice.multiply(caseQty);
    UUID caseId =
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    caseAmount, "USD", caseQty, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1, null, null, null, "TENANT_MAINTAINED",
                    null, T1),
                userA)
            .id());
    as(() -> details.addLine(caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            line1, new BigDecimal("3"), unitPrice.multiply(new BigDecimal("3")), "USD", "第 1 行"),
        userA));
    as(() -> details.addLine(caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
            line2, BigDecimal.ONE, unitPrice, "USD", "第 2 行"),
        userA));

    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                userA)
            .head()
            .id());

    var rmaLines = as(() -> rma.detail(rmaId)).lines();
    assertThat(rmaLines).as("授权单自动按售后行生成两条授权行").hasSize(2);
    UUID rmaLine1 = rmaLines.get(0).id();
    UUID rmaLine2 = rmaLines.get(1).id();

    // ★ 第一条授权 3 件、第二条**只授权 1 件** —— 超收的来源就在这里。
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "APPROVED", returnByAt, "同意退货",
            List.of(
                new RmaAuthorizationModels.RmaLineDecision(rmaLine1, new BigDecimal("3"), null),
                new RmaAuthorizationModels.RmaLineDecision(rmaLine2, BigDecimal.ONE, null)),
            0L),
        userA));

    var draft = as(() -> receipts.create(
        new ReturnReceiptCreate(
            rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱压损-" + tag, T1,
            List.of(new ReturnReceiptLineDraft(
                rmaLine1, "SKU-" + tag + "-1", "SN-" + tag, null, new BigDecimal("3"),
                "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
            List.of()),
        userA));
    var posted = as(() -> receipts.post(
        draft.head().id(), new ReturnReceiptPost(null, draft.head().version()), userA));
    UUID receiptLineId = as(() -> receipts.detail(posted.head().id())).lines().get(0).id();

    return new Seeded(orderId, line1, caseId, rmaLines.get(0).caseLineId(), rmaId, rmaLine1,
        posted.head().id(), receiptLineId, orderNo, "SKU-" + tag + "-1", rmaLine2, line2);
  }
}
