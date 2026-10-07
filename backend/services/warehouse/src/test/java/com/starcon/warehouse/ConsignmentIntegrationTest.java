package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.7.1.2 寄售与代销：协议 → 寄售可用量 → 销售事实 → 结算触发单 → 未售退货与期末清理，
 * <b>以及「寄售未售出不产生采购成本与应付」这条财务分叉</b>，外加租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试的核心是 {@link #未售出不产生任何应付()} 与 {@link #数据库层挡住把寄售货算成自有资产()} 两条</b>。
 * 蓝图原话：「寄售库存不计入我方采购入库成本，仅在售出时结转」，并且把「寄售未售出不产生
 * 采购成本与应付」称为寄售与普通采购在财务上的根本分叉。只测「卖出去了会算应付」证明不了
 * 这条红线 —— 那条路在「随便按寄售量写个应付」面前一样成立。所以必须**真的**去数据库里
 * 硬写不该存在的应付行，验证 V71 的闸门会当场拒绝：
 * 一条只写在服务层注释里的红线，等于没有红线。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsignmentIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID skuOne = idFor(a, 2);
  static final UUID skuTwo = idFor(a, 3);
  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);
  static final UUID supplier = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-consignment-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
      }
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ConsignmentService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate consignment_return_line, consignment_return, consignment_settlement_line,"
              + " consignment_settlement, consignment_sale, consignment_agreement_line,"
              + " consignment_agreement, consignment_audit, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-ONE", "一仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-TWO", "二仓", "CN", "Asia/Shanghai");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具

  /** 建一份已启用的寄售协议：SKU-1 寄售 100 件 @ 12.5，SKU-2 寄售 40 件 @ 30。 */
  UUID activeAgreement(String cycle, String endTreatment) {
    var input =
        new ConsignmentAgreementInput(
            "CONSIGNMENT", supplier, "SUP-1", "供应商一", le, "CNY", cycle, endTreatment,
            LocalDate.now().minusDays(3), null, "寄售协议",
            List.of(
                new ConsignmentLineInput(skuOne, whOne, new BigDecimal("100"), new BigDecimal("12.5")),
                new ConsignmentLineInput(skuTwo, whOne, new BigDecimal("40"), new BigDecimal("30"))));
    var id =
        access.run(actorA, "warehouse.inventory.update", () -> service.createAgreement(input, user))
            .agreement().id();
    return access.run(actorA, "warehouse.inventory.update", () -> service.activate(id, null, user)).id();
  }

  UUID lineOf(UUID agreementId, UUID skuId) {
    return access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreementId)).lines()
        .stream()
        .filter(l -> l.skuId().equals(skuId))
        .findFirst()
        .orElseThrow()
        .id();
  }

  ConsignmentSale sale(UUID agreementId, UUID lineId, String qty, String ref, OffsetDateTime at) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordSale(
                agreementId, lineId, new ConsignmentSaleInput(new BigDecimal(qty), ref, null, null, at, null),
                user));
  }

  // ================================================================ 数据库闸门直写

  /**
   * 绕过服务层直接写数据库 —— 这才是验证「红线真的在数据库里」的正确姿势。
   *
   * <p>必须在租户事务里做：RLS 开着 FORCE，事务外裸写连行都看不见（UPDATE 静默影响 0 行），
   * 那会让「数据库挡住了我」变成一句自欺欺人的话。
   */
  void rawAsA(Runnable write) {
    access.run(actorA, "warehouse.inventory.update", () -> {
      write.run();
      return null;
    });
  }

  /**
   * 断言「被**数据库**拒绝」，而不是被应用层挡住。
   *
   * <p>沿异常链找数据库的错误消息：应用层的 409 与数据库的约束拒绝都是 DataAccessException，
   * 只断言异常类型分不出「我自己在服务层拦的」和「数据库真的不让我写」。
   */
  void assertDbRejects(String expectedFragment, Runnable write) {
    assertThatThrownBy(() -> rawAsA(write))
        .satisfies(
            e -> {
              for (Throwable c = e; c != null; c = c.getCause()) {
                if (c.getMessage() != null && c.getMessage().contains(expectedFragment)) return;
              }
              fail("期望被数据库拒绝（错误消息含 '" + expectedFragment + "'），实际异常链：" + e);
            });
  }

  // ================================================================ 能力与货主

  @Test
  void 能力如实说明应付口径与货主() {
    var cap = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    // 应付口径必须说清楚：只有已售 × 结算价。
    assertThat(cap.payableBasis()).contains("已售数量 × 结算价");
    // 采购入库成本口径「算不出来」是因为按设计就不该有，不是还没做。
    assertThat(cap.costBasisAvailable()).isFalse();
    assertThat(cap.costBasisNote()).contains("寄售货不进我方采购入库成本");
    // 货主恒为供应商。
    assertThat(cap.ownershipNote()).contains("货主恒为供应商");
    // 交接口径如实说明未交付的服务。
    assertThat(cap.payableHandoffNote()).contains("12 应付尚未交付");
    assertThat(cap.saleFactNote()).contains("9 订单尚未交付");
    assertThat(cap.modes()).hasSize(2);
    assertThat(cap.endTreatments())
        .filteredOn(o -> o.value().equals("CONVERT_TO_PURCHASE"))
        .singleElement()
        .satisfies(o -> assertThat(o.createsPayable()).isTrue());
  }

  @Test
  void 数据库层挡住把寄售货算成自有资产() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    // 直连数据库硬写 counts_as_owned_asset = true：V71 的 CHECK 必须挡住。
    // 07-inventory.md:24 的硬约束「不记为自有资产」因此不是应用层自觉。
    assertDbRejects(
        "violates check constraint",
        () ->
            sql.execute(
                "update consignment_agreement_line set counts_as_owned_asset = true"
                    + " where tenant_id = ? and id = ?",
                a, line));
    var read = access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreement)).lines()
        .stream().filter(l -> l.id().equals(line)).findFirst().orElseThrow();
    assertThat(read.countsAsOwnedAsset()).isFalse();
    assertThat(read.stockOwnerType()).isEqualTo("SUPPLIER");
  }

  // ================================================================ 协议与寄售可用量

  @Test
  void 寄售可用量视图如实给出寄售量已售剩余() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "30", "SO-1", now());

    var view = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
    assertThat(view.totalConsigned()).isEqualByComparingTo("140.000000");
    assertThat(view.totalSold()).isEqualByComparingTo("30.000000");
    assertThat(view.totalRemaining()).isEqualByComparingTo("110.000000");
    // 售出过但还没进结算单 —— 这才是「销售触发结算数量」的待结算部分。
    assertThat(view.unsettledQuantity()).isEqualByComparingTo("30.000000");
    assertThat(view.unsettledAmount()).isEqualByComparingTo("375.000000"); // 30 × 12.5
    // 寄售货不是自有资产，且自有货值口径不存在（null 而不是 0）。
    assertThat(view.countsAsOwnedAsset()).isFalse();
    assertThat(view.ownedAssetValue()).isNull();
    assertThat(view.ownershipNote()).contains("不记为自有资产");
  }

  @Test
  void 售出不得超过剩余寄售量() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "90", "SO-1", now());
    // 剩 10 件，卖 11 件：在卖供应商没给我们的货之前不能记销售 —— 那会凭空生成一笔应付。
    assertThatThrownBy(() -> sale(agreement, line, "11", "SO-2", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过该行剩余寄售量");
  }

  @Test
  void 同一条销售事实不会重复计入() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "10", "SO-DUP", now());
    // 同一 saleRef 重复推送 → 409。数据库的 UNIQUE(tenant_id, agreement_line_id, sale_ref) 兜底。
    assertThatThrownBy(() -> sale(agreement, line, "10", "SO-DUP", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经记过");
    var view = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
    assertThat(view.totalSold()).isEqualByComparingTo("10.000000");
  }

  @Test
  void 草稿协议不能记销售() {
    var input =
        new ConsignmentAgreementInput(
            "CONSIGNMENT", supplier, "SUP-1", "供应商一", le, "CNY", "MONTHLY", "RETURN_TO_SUPPLIER",
            LocalDate.now(), null, null,
            List.of(new ConsignmentLineInput(skuOne, whOne, new BigDecimal("10"), new BigDecimal("5"))));
    var draft =
        access.run(actorA, "warehouse.inventory.update", () -> service.createAgreement(input, user))
            .agreement().id();
    UUID line = lineOf(draft, skuOne);
    assertThatThrownBy(() -> sale(draft, line, "1", "SO-1", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有生效中的协议能记销售");
  }

  @Test
  void 关闭协议后明细被数据库冻结() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "5", "SO-1", now());
    access.run(actorA, "warehouse.inventory.update", () -> service.close(agreement, null, user));
    // 绕过服务层直接改数量：V71 的 consignment_line_frozen 触发器必须挡住。
    assertDbRejects(
        "belongs to a CLOSED agreement and is frozen",
        () ->
            sql.execute(
                "update consignment_agreement_line set sold_quantity = 999 where tenant_id = ? and id = ?",
                a, line));
    assertThatThrownBy(() -> sale(agreement, line, "1", "SO-2", now()))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 结算触发单

  @Test
  void 结算触发单只收已售事实且合计等于明细之和() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID lineOne = lineOf(agreement, skuOne);
    UUID lineTwo = lineOf(agreement, skuTwo);
    sale(agreement, lineOne, "30", "SO-1", now());
    sale(agreement, lineOne, "20", "SO-2", now());
    sale(agreement, lineTwo, "10", "SO-3", now());

    var detail =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.createSettlement(
                    agreement,
                    new ConsignmentSettlementInput(
                        LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), "10 月结算"),
                    user));
    var settlement = detail.settlement();
    assertThat(settlement.status()).isEqualTo("OPEN");
    // 合计 60 = 30+20+10，金额 = 30×12.5 + 20×12.5 + 10×30 = 925。
    assertThat(settlement.totalQuantity()).isEqualByComparingTo("60.000000");
    assertThat(settlement.totalAmount()).isEqualByComparingTo("925.000000");
    assertThat(detail.lines()).hasSize(3);
    // 每一行都指回触发它的那条销售事实：应付从哪来一眼可追。
    assertThat(detail.lines()).allSatisfy(l -> assertThat(l.saleId()).isNotNull());

    // 再出一次单：这三条销售事实已进结算单，不能被收走第二次。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.createSettlement(
                            agreement,
                            new ConsignmentSettlementInput(
                                LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有「已售出且尚未结算」");
  }

  @Test
  void 同一条销售事实不能进两张结算单() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "10", "SO-1", now());
    var window = access.run(actorA, "warehouse.inventory.read", () -> service.settlementWindow(agreement));
    var settlement =
        access.run(
                actorA,
                "warehouse.inventory.update",
                () ->
                    service.createSettlement(
                        agreement,
                        new ConsignmentSettlementInput(window.periodStart(), window.periodEnd(), null),
                        user))
            .settlement();
    // 绕过服务层，硬把同一条销售事实塞进另一张结算单 → UNIQUE(tenant_id, sale_id) 挡住。
    UUID other = UUID.randomUUID();
    rawAsA(
        () ->
            sql.execute(
                "insert into consignment_settlement(id,tenant_id,settlement_code,agreement_id,period_start,"
                    + "period_end,currency,status,created_by_user_id) values(?,?,?,?,?,?,'CNY','OPEN',?)",
                other, a, "ST-OTHER", agreement, LocalDate.now().minusDays(90),
                LocalDate.now().minusDays(60), user));
    var saleId = access.run(actorA, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 10))
        .sales().get(0).id();
    assertDbRejects(
        "violates unique constraint",
        () ->
            sql.execute(
                "insert into consignment_settlement_line(id,tenant_id,settlement_id,agreement_line_id,"
                    + "sale_id,quantity,unit_price) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), a, other, line, saleId, new BigDecimal("10"), new BigDecimal("12.5")));
    assertThat(settlement.totalQuantity()).isEqualByComparingTo("10.000000");
  }

  @Test
  void 已确认结算单明细被数据库冻结() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "10", "SO-1", now());
    var settlement =
        access.run(
                actorA,
                "warehouse.inventory.update",
                () ->
                    service.createSettlement(
                        agreement,
                        new ConsignmentSettlementInput(
                            LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), null),
                        user))
            .settlement();
    var confirmed =
        access.run(
            actorA, "warehouse.inventory.update", () -> service.confirmSettlement(settlement.id(), null, user));
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    // reference_id 是交给 12 应付的定位键。
    assertThat(confirmed.referenceId()).isNotBlank();
    // 应付既已出去，改它等于篡改对外账：DB 触发器挡住。
    assertDbRejects(
        "its lines are frozen",
        () ->
            sql.execute(
                "update consignment_settlement_line set quantity = 999"
                    + " where tenant_id = ? and settlement_id = ?",
                a, settlement.id()));
    // 已确认的单据不能重复确认。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update", () -> service.confirmSettlement(settlement.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 结算区间按结算周期真算() {
    UUID weekly = activeAgreement("WEEKLY", "RETURN_TO_SUPPLIER");
    var w = access.run(actorA, "warehouse.inventory.read", () -> service.settlementWindow(weekly));
    assertThat(w.settlementCycle()).isEqualTo("WEEKLY");
    assertThat(w.periodEnd()).isAfter(w.periodStart());
    // 周一到周日：起始日是周一。
    assertThat(w.periodStart().getDayOfWeek().getValue()).isEqualTo(1);
    assertThat(w.periodEnd().getDayOfWeek().getValue()).isEqualTo(7);

    UUID monthly = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    var m = access.run(actorA, "warehouse.inventory.read", () -> service.settlementWindow(monthly));
    assertThat(m.periodStart().getDayOfMonth()).isEqualTo(1);
    assertThat(m.periodEnd()).isEqualTo(m.periodStart().withDayOfMonth(m.periodStart().lengthOfMonth()));
  }

  // ================================================================ 红线：未售出不产生应付

  @Test
  void 未售出不产生任何应付() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    var line = lineOf(agreement, skuOne);
    // 只寄售、**一件不卖**。
    var view = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
    assertThat(view.totalConsigned()).isEqualByComparingTo("140.000000");
    assertThat(view.totalSold()).isEqualByComparingTo("0.000000");
    assertThat(view.unsettledAmount()).isEqualByComparingTo("0.000000");

    // 区间内没有销售事实 → 出不了结算单。这正是寄售该有的结果：
    // 140 件寄售货摆在仓库里，供应商一分钱都还没赚到，我方也不欠他一分钱。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.createSettlement(
                            agreement,
                            new ConsignmentSettlementInput(
                                LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有「已售出且尚未结算」");

    // 试图绕过服务层：直接按「寄售量」硬写一张结算单，再往里塞一条没有销售事实的明细。
    // consignment_settlement_line.sale_id NOT NULL + 外键到 consignment_sale 挡住 ——
    // 全表没有「不指向销售事实」的行，所以按寄售量算应付这条路在数据层不存在。
    UUID forged = UUID.randomUUID();
    rawAsA(
        () ->
            sql.execute(
                "insert into consignment_settlement(id,tenant_id,settlement_code,agreement_id,period_start,"
                    + "period_end,currency,status,created_by_user_id) values(?,?,?,?,?,?,'CNY','OPEN',?)",
                forged, a, "ST-FORGED", agreement, LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(1), user));
    assertDbRejects(
        "violates foreign key constraint",
        () ->
            sql.execute(
                "insert into consignment_settlement_line(id,tenant_id,settlement_id,agreement_line_id,"
                    + "sale_id,quantity,unit_price) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), a, forged, line, UUID.randomUUID(), new BigDecimal("140"),
                new BigDecimal("12.5")));
    // 应付合计仍是 0：那 140 件寄售货一笔应付都没产生。
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.settlementDetail(forged))
            .settlement().totalAmount()).isEqualByComparingTo("0.000000");
  }

  // ================================================================ 未售退货与期末清理

  @Test
  void 未售退回不产生应付且退回后剩余减少() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "30", "SO-1", now());
    var draft =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.createReturn(
                    agreement,
                    new ConsignmentReturnInput(
                        "UNSOLD_RETURN", "RETURN_TO_SUPPLIER",
                        List.of(new ConsignmentReturnLineInput(line, new BigDecimal("20"))), "退未售"),
                    user))
            .returnOrder();
    // 草稿期不动数量：卖出 30 后两条行合计剩余 = (100-30) + 40 = 110。
    assertThat(
            access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement))
                .totalRemaining()).isEqualByComparingTo("110.000000");

    var executed =
        access.run(actorA, "warehouse.inventory.update", () -> service.executeReturn(draft.id(), null, user));
    assertThat(executed.status()).isEqualTo("EXECUTED");
    // 退回供应商：不产生任何应付。
    assertThat(executed.totalPayableAmount()).isEqualByComparingTo("0.000000");
    // 退回的货我方不再持有：SKU-1 剩余从 70 减到 50，合计 50 + 40 = 90。
    var view = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
    assertThat(view.totalRemaining()).isEqualByComparingTo("90.000000");
    assertThat(view.totalReturned()).isEqualByComparingTo("20.000000");
    // 重复执行会被状态机挡住（数量早已动过）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update", () -> service.executeReturn(draft.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 期末转采购才产生应付且必须与协议一致() {
    UUID agreement = activeAgreement("MONTHLY", "CONVERT_TO_PURCHASE");
    UUID line = lineOf(agreement, skuOne);
    var draft =
        access.run(
                actorA,
                "warehouse.inventory.update",
                () ->
                    service.createReturn(
                        agreement,
                        new ConsignmentReturnInput(
                            "PERIOD_END_CLEANUP", "CONVERT_TO_PURCHASE",
                            List.of(new ConsignmentReturnLineInput(line, new BigDecimal("25"))), "期末买断"),
                        user))
            .returnOrder();
    var executed =
        access.run(actorA, "warehouse.inventory.update", () -> service.executeReturn(draft.id(), null, user));
    // 转入采购是「未售出不产生应付」的唯一例外：25 × 12.5 = 312.5。
    assertThat(executed.totalPayableAmount()).isEqualByComparingTo("312.500000");
    var view = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
    assertThat(view.totalConverted()).isEqualByComparingTo("25.000000");
    assertThat(view.totalReturned()).isEqualByComparingTo("0.000000");
    // 转采购只从剩余里扣，不动「已售」：两者的财务性质不同。
    assertThat(view.totalSold()).isEqualByComparingTo("0.000000");

    // 协议约定转采购，却按「退回」做期末清理 → 409：
    // 否则「协议写转采购、账上却退回」，或者反过来，就凭空多一笔或少一笔应付。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.createReturn(
                            agreement,
                            new ConsignmentReturnInput(
                                "PERIOD_END_CLEANUP", "RETURN_TO_SUPPLIER",
                                List.of(new ConsignmentReturnLineInput(line, new BigDecimal("1"))), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("协议约定的期末处理是");
  }

  @Test
  void 退货数量不得超过剩余() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "95", "SO-1", now());
    var draft =
        access.run(
                actorA,
                "warehouse.inventory.update",
                () ->
                    service.createReturn(
                        agreement,
                        new ConsignmentReturnInput(
                            "UNSOLD_RETURN", "RETURN_TO_SUPPLIER",
                            List.of(new ConsignmentReturnLineInput(line, new BigDecimal("10"))), null),
                        user))
            .returnOrder();
    // 剩 5 件却要退 10 件：执行时挡住，提示按当前剩余重新拟单。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update", () -> service.executeReturn(draft.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过当前剩余");
  }

  // ================================================================ 时间绑定

  @Test
  void 售出时刻写回不被时区偏移() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    // 东八区的一个时刻：若用 Timestamp.valueOf(LocalDateTime) 绑定会静默偏 −28800s。
    var at = OffsetDateTime.of(2026, 10, 7, 15, 30, 0, 0, ZoneOffset.ofHours(8));
    var written = sale(agreement, line, "1", "SO-TZ", at);
    // 断言写回的值，而不是断言「没报错」。
    var readBack =
        access.run(actorA, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 10))
            .sales().stream()
            .filter(s -> s.id().equals(written.id()))
            .findFirst()
            .orElseThrow();
    assertThat(readBack.occurredAt().toInstant()).isEqualTo(at.toInstant());
    assertThat(readBack.occurredAt().getHour()).isEqualTo(15);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "10", "SO-1", now());

    // B 看不到 A 的任何东西：连详情都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.agreement(agreement)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.stockView(agreement)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.auditTrail(agreement, null)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20))
        .total()).isZero();
    // B 不能往 A 的协议行上写销售。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.recordSale(
                            agreement, line,
                            new ConsignmentSaleInput(new BigDecimal("1"), "SO-X", null, null, now(), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    // B 自己的协议与 A 的互不可见。
    var bInput =
        new ConsignmentAgreementInput(
            "CONSIGNMENT", supplier, "SUP-B", "B 的供应商", idFor(b, 1), "CNY", "MONTHLY",
            "RETURN_TO_SUPPLIER", LocalDate.now(), null, null,
            List.of(new ConsignmentLineInput(idFor(b, 2), idFor(b, 4), new BigDecimal("5"), new BigDecimal("2"))));
    var bAgreement =
        access.run(actorB, "warehouse.inventory.update", () -> service.createAgreement(bInput, user))
            .agreement().id();
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20))
        .agreements().stream().map(ConsignmentAgreement::id)).containsExactly(bAgreement);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    UUID line = lineOf(agreement, skuOne);
    sale(agreement, line, "10", "SO-1", now());

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(get("/warehouse/inventory/consignments")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(get("/warehouse/inventory/consignments/{id}", agreement)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/warehouse/inventory/consignments/{id}/stock-view", agreement)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 把 A 的单确认（确认=把应付交出去）。
    var settlement =
        access.run(
                actorA,
                "warehouse.inventory.update",
                () ->
                    service.createSettlement(
                        agreement,
                        new ConsignmentSettlementInput(
                            LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), null),
                        user))
            .settlement();
    http.perform(post("/warehouse/inventory/consignments/settlements/{id}/confirm", settlement.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动：应付仍未确认。
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.settlementDetail(settlement.id()))
        .settlement().status()).isEqualTo("OPEN");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID agreement = activeAgreement("MONTHLY", "RETURN_TO_SUPPLIER");
    sale(agreement, lineOf(agreement, skuOne), "10", "SO-1", now());
    var bInput =
        new ConsignmentAgreementInput(
            "CONSIGNMENT", supplier, "SUP-B", "B 的供应商", idFor(b, 1), "CNY", "MONTHLY",
            "RETURN_TO_SUPPLIER", LocalDate.now(), null, null,
            List.of(new ConsignmentLineInput(idFor(b, 2), idFor(b, 4), new BigDecimal("5"), new BigDecimal("2"))));
    access.run(actorB, "warehouse.inventory.update", () -> service.createAgreement(bInput, user));

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = access.run(actorA, "warehouse.inventory.read", () -> service.stockView(agreement));
      assertThat(a1.totalSold()).isEqualByComparingTo("10.000000");
      var b1 = access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20));
      assertThat(b1.total()).isEqualTo(1);
      var a2 = access.run(actorA, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20));
      assertThat(a2.total()).isEqualTo(1);
    }
    // 八张表逐张确认 FORCE RLS
    for (var table : List.of(
        "consignment_agreement", "consignment_agreement_line", "consignment_sale",
        "consignment_settlement", "consignment_settlement_line", "consignment_return",
        "consignment_return_line", "consignment_audit")) {
      assertThat(sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
          .get("relforcerowsecurity", Boolean.class)).isTrue();
      assertThat(sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
          .get("relrowsecurity", Boolean.class)).isTrue();
    }
  }

  // ================================================================ 权限

  @Test
  void 只读身份不能写() throws Exception {
    var readOnly = actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read"))));
    actors.put("Bearer consignment-read-only", readOnly);
    var cap = access.run(readOnly, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(cap.modes()).hasSize(2);
    // 只读身份走 HTTP 写路径被 403 挡住。
    http.perform(post("/warehouse/inventory/consignments")
            .header("Authorization", "Bearer consignment-read-only")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isForbidden());
  }
}
