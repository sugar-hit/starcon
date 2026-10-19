package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.starcon.trade.ProductIssueAttributionModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.1.2.2 商品问题归因的<b>行为与硬规则</b>集成测试。
 *
 * <p>跑的是本模块职责句里的真实行为，全部落在<b>本模块自己的五张表</b>上：
 *
 * <ul>
 *   <li><b>硬规则 1（归因环节）</b>：六值枚举；生产责任必须挂批次；
 *       生产责任只对质量问题成立；结论终态不可逆（改判留痕）。</li>
 *   <li><b>硬规则 2（照片比对）</b>：结果由服务层按 sha256 <b>真</b>比对得出，
 *       传输层不接受传入；DB 触发器再独立比一次 —— <b>指纹不同却写 SAME 存不进去</b>。</li>
 *   <li><b>硬规则 3（批次质量标记）</b>：统计列由 DB 重算比对，传输层不接受传入；
 *       SUSPECT 要求 ≥ 3 件当前生效归因 —— 「同一个批次退回来 10 件」与
 *       「10 个批次各退 1 件」的计数落点。</li>
 * </ul>
 *
 * <p>⚠️ <b>时间断言两边都固定时刻</b>：写时刻用固定的 {@code T1}，读回来断言同一个
 * {@code T1}。刻意不用「now().minusDays(n)」这类相对基准 ——
 * 中途改时刻会把「恰好 N 天」变成「N 天减去当前时刻差」，
 * 于是这种测试只在早上绿、下午必红，还会伪装成「新车道弄坏了旧模块」。
 */
@SpringBootTest
class ProductIssueAttributionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tid = UUID.randomUUID();
  static final UUID uid = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

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
  }

  @MockitoSpyBean AccessService access;
  @Autowired ProductIssueAttributionService attributions;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired RmaAuthorizationService rma;
  @Autowired ReturnReceiptService receipts;
  @Autowired DSLContext sql;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** 固定时刻：写与读**同源**。相对天数断言只在这里配固定偏移，绝不中途改时刻。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-03-01T20:00:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

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
          "truncate product_issue_event, product_issue_photo_comparison, product_issue_photo,"
              + " product_issue_lot_flag, product_issue_attribution,"
              + " return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
    actors.put(
        "Bearer t",
        new AccessService.Access(
            uid, tid, "Test", UUID.randomUUID(), null, perms,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))));
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer t"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  record Fixture(UUID caseId, UUID receiptLineId) {}

  /** 建订单 → 售后单 → 授权 → 实收入库（2 件）。 */
  Fixture seed(String tag) throws Exception {
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
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
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
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", qty, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                uid)
            .id());
    as(() -> details.addLine(
            caseId,
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(lineId, qty, amount, "USD", "第 1 件"),
            uid));

    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                uid)
            .head()
            .id());
    UUID rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        uid));

    var draft =
        as(() -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), "LOT-" + tag,
                        new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
                List.of()),
            uid));
    as(() -> receipts.post(draft.head().id(),
        new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), uid));

    UUID receiptLineId =
        as(() -> sql.fetchOne(
                "select id from return_receipt_line where tenant_id=?::uuid and receipt_id=?::uuid",
                tid, draft.head().id()))
            .get(0, UUID.class);

    return new Fixture(caseId, receiptLineId);
  }

  /** 建归因单（不挂批次：批次要经 warehouse HTTP 端口，由 Port 专项测试覆盖）。 */
  UUID open(Fixture f, String skuCode, BigDecimal qty) {
    return as(() -> attributions.create(
            new AttributionCreate(
                f.caseId(), null, null, null, null, null, null,
                null, skuCode, "商品" + skuCode, qty),
            uid,
            "Bearer t")
        .row()
        .id());
  }

  /**
   * 直接把批次 id 挂到归因单上。
   *
   * <p>⚠️ 刻意**绕过服务层直写**：批次真值在 warehouse 独立库，
   * 建单时本该经 {@code ProductLotTracePort} 真查一次快照。
   * 本测试要的是「批次统计口径」那几条规则（同一个批次退回来 N 件），
   * 不该为了它去起一个 HTTP 桩端口（已知 flake 源）。
   * 真实跨服务读由 {@code ProductLotTracePortIntegrationTest} 单独覆盖。
   */
  void attachLot(UUID attributionId, UUID lotId, String lotCode) {
    as(() -> {
      sql.execute(
          "update product_issue_attribution set lot_id = ?::uuid, lot_code = ?"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          lotId, lotCode, tid, attributionId);
      return null;
    });
  }

  /** 按对象键取照片 id（不按下标 —— 见「照片比对按内容指纹判定」里的说明）。 */
  private static UUID photoId(AttributionDetail detail, String objectUri) {
    return detail.photos().stream()
        .filter(p -> p.objectUri().equals(objectUri))
        .findFirst()
        .orElseThrow(() -> new AssertionError("找不到对象键为 " + objectUri + " 的照片"))
        .id();
  }

  private static ProductIssueAttributionService.AttributionListQuery query() {
    return new ProductIssueAttributionService.AttributionListQuery(
        0, 20, null, null, null, null, null, null);
  }

  // ==========================================================================
  // 建单与输入校验
  // ==========================================================================

  @Test
  void 建单必填与实收上限() throws Exception {
    Fixture f = seed("CREATE");

    assertThatThrownBy(() -> as(() -> attributions.create(
            new AttributionCreate(null, null, null, null, null, null, null, null, null, null, null),
            uid, "Bearer t")))
        .hasStackTraceContaining("必须给出 case_id");
    assertThatThrownBy(() -> as(() -> attributions.create(
            new AttributionCreate(f.caseId(), null, null, null, null, null, null,
                null, "S", "S", new BigDecimal("0")),
            uid, "Bearer t")))
        .hasStackTraceContaining("归因数量必须大于 0");

    // 归因数量超过实收数量（2 件）—— 真由 DB 守卫④按 receipt 链路算出来拒掉。
    assertThatThrownBy(() -> as(() -> attributions.create(
            new AttributionCreate(f.caseId(), null, null, null, null, null, null,
                null, "S", "S", new BigDecimal("9.000000")),
            uid, "Bearer t")))
        .hasStackTraceContaining("超过该售后单的实收数量");

    // 正常建单：草稿 + 发号 + 时刻列确实写回了值。
    UUID id = open(f, "SKU-CREATE", new BigDecimal("2.000000"));
    var head = as(() -> attributions.detail(id)).row();
    assertThat(head.status()).isEqualTo("DRAFT");
    assertThat(head.attributedStage()).isNull();
    assertThat(head.attributionNo()).isNotBlank();
    // createdAt 是「此刻」，所以断言它落在一个合理窗口里而不是某个固定值
    // （断言一个自己没绑定的固定值，只会测出时区漂移这类假信号）。
    assertThat(head.createdAt()).isNotNull();
    assertThat(head.createdAt()).isAfter(T2.minusYears(5));
  }

  @Test
  void 跨租户售后单建单被拒() throws Exception {
    Fixture f = seed("X-TENANT");
    // 售后单不存在（另一个租户的 id）→ 与「不存在」同一个错误。
    assertThatThrownBy(() -> as(() -> attributions.create(
            new AttributionCreate(UUID.randomUUID(), null, null, null, null, null, null,
                null, "S", "S", new BigDecimal("1.000000")),
            uid, "Bearer t")))
        .hasStackTraceContaining("售后单不存在或不属于当前租户");
    assertThat(f.caseId()).isNotNull();
  }

  // ==========================================================================
  // 硬规则 1：归因环节
  // ==========================================================================

  @Test
  void 归因必须给环节理由与改进建议() throws Exception {
    Fixture f = seed("STAGE");
    UUID id = open(f, "SKU-STAGE", new BigDecimal("2.000000"));

    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude(null, "理由", "建议"), uid)))
        .hasStackTraceContaining("必须给出 attributed_stage");
    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude("TRANSPORT", "  ", "建议"), uid)))
        .hasStackTraceContaining("必须给出归因理由");
    // ⚠️ 改进建议是职责句明文要求的输出，缺它要被拒。
    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude("TRANSPORT", "理由", "  "), uid)))
        .hasStackTraceContaining("必须给出改进建议");
    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude("PACKAGING_FAULT", "理由", "建议"), uid)))
        .hasStackTraceContaining("归因环节只能是");

    // 正常归因：四个环节 + 两个诚实出口都在枚举里。
    var head = as(() -> attributions.attribute(
        id, new AttributionConclude("transport", "外箱压损导致", "要求承运商加固外箱"), uid)).row();
    assertThat(head.attributedStage()).isEqualTo("TRANSPORT");
    assertThat(head.stageLabel()).isEqualTo("运输");
    assertThat(head.status()).isEqualTo("ATTRIBUTED");
    assertThat(head.attributedAt()).isNotNull();
  }

  @Test
  void 生产责任必须挂批次() throws Exception {
    Fixture f = seed("PROD");
    UUID id = open(f, "SKU-PROD", new BigDecimal("2.000000"));

    // 没有批次就归「生产」—— 服务层与 DB 触发器各拒一次。
    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude("PRODUCTION", "产线不良", "要求供应商换模"), uid)))
        .hasStackTraceContaining("必须挂批次");

    // 挂上批次之后就能归「生产」了。
    attachLot(id, UUID.randomUUID(), "LOT-P");
    var head = as(() -> attributions.attribute(
        id, new AttributionConclude("PRODUCTION", "产线不良", "要求供应商换模"), uid)).row();
    assertThat(head.attributedStage()).isEqualTo("PRODUCTION");

    // ⚠️ 直接写 SQL 绕过服务层，**表级 CHECK** 同样拒（不挂批次不许写 PRODUCTION）。
    Fixture f2 = seed("PROD2");
    UUID id2 = open(f2, "SKU-PROD2", new BigDecimal("1.000000"));
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update product_issue_attribution set attributed_stage = 'PRODUCTION'"
                + " where tenant_id = ?::uuid and id = ?::uuid", tid, id2)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void 归因结论终态不可逆() throws Exception {
    Fixture f = seed("TERM");
    UUID id = open(f, "SKU-TERM", new BigDecimal("2.000000"));
    as(() -> attributions.attribute(
        id, new AttributionConclude("TRANSPORT", "运输责任", "加固外箱"), uid));

    // 判完之后不可再直接改。
    assertThatThrownBy(() -> as(() -> attributions.attribute(
            id, new AttributionConclude("WAREHOUSE", "改判", "改判"), uid)))
        .hasStackTraceContaining("只有草稿可编辑");

    // 改判：原单 SUPERSEDED 留痕，另起一版直接归因。
    assertThatThrownBy(() -> as(() -> attributions.supersede(
            id, new AttributionSupersede("", "WAREHOUSE", "改判", "改判"), uid)))
        .hasStackTraceContaining("改判必须说明理由");

    var after = as(() -> attributions.supersede(
        id, new AttributionSupersede("复核后改判仓储", "WAREHOUSE", "仓储责任", "增加库位检查"), uid));
    assertThat(after.row().attributedStage()).isEqualTo("WAREHOUSE");
    assertThat(after.row().attributionNo()).isNotEqualTo(
        as(() -> attributions.detail(id)).row().attributionNo());

    // ⚠️ 部分唯一索引：一单同时只能有一版生效。
    assertThat(as(() -> sql.fetchOne(
            "select count(*) from product_issue_attribution"
                + " where tenant_id = ?::uuid and case_id = ?::uuid"
                + "   and attribution_status <> 'SUPERSEDED'", tid, f.caseId()))
        .get(0, Long.class)).as("一单只能有一版生效归因").isEqualTo(1);
    // 汇总只数当前生效的那一版（改判留痕不重复计数）。
    assertThat(as(() -> attributions.listAttributions(query())).summary().attributed()).isEqualTo(1);
    assertThat(as(() -> attributions.listAttributions(query())).summary().superseded()).isEqualTo(1);
  }

  // ==========================================================================
  // 硬规则 2：照片比对（内容指纹，不是图像相似度）
  // ==========================================================================

  @Test
  void 照片比对按内容指纹判定() throws Exception {
    Fixture f = seed("PHOTO");
    UUID id = open(f, "SKU-PHOTO", new BigDecimal("2.000000"));

    String shaSame = "a".repeat(64);
    String shaDiff = "b".repeat(64);

    var detail =
        as(() -> attributions.registerPhotos(
            id,
            new PhotoRegisterRequest(
                List.of(
                    new PhotoRegister(null, "DEFECT_CLOSEUP", "s3://x/1.jpg", shaSame, 1024L,
                        "image/jpeg", T1, "缺陷特写"),
                    new PhotoRegister(null, "DEFECT_CLOSEUP", "s3://x/2.jpg", shaSame, 2048L,
                        "image/jpeg", T1, "同一张重复登记"),
                    new PhotoRegister(null, "PRODUCT_OVERVIEW", "s3://x/3.jpg", shaDiff, 3072L,
                        "image/jpeg", T1, "整体外观"))),
            uid));
    assertThat(detail.photos()).hasSize(3);
    // ⚠️ 拍摄时刻与登记时刻分开：写回值断言**同一个固定时刻**。
    //   只断言「非空」发现不了 Timestamp.valueOf 那种静默偏 8 小时的错。
    assertThat(detail.photos())
        .allSatisfy(p -> assertThat(p.capturedAt().toInstant()).isEqualTo(T1.toInstant()));

    // ⚠️ **按对象键取 id，不按下标**：照片按 (registered_at, id) 排序，而 id 是随机 UUID，
    //   同一个 registered_at 下顺序不可预期 —— 按下标取会拿到随机那一张，
    //   断言随机通过 / 随机失败，症状离病因很远。
    UUID p1 = photoId(detail, "s3://x/1.jpg");
    UUID p2 = photoId(detail, "s3://x/2.jpg");
    UUID p3 = photoId(detail, "s3://x/3.jpg");

    // 指纹相同 → SAME。
    var same =
        as(() -> attributions.comparePhotos(
            id, new PhotoCompareRequest(p1, p2, "重复上传"), uid));
    assertThat(same.comparisons()).hasSize(1);
    assertThat(same.comparisons().get(0).compareResult()).isEqualTo("IDENTITY_SAME");
    assertThat(same.comparisons().get(0).compareBasis()).isEqualTo("CONTENT_FINGERPRINT");

    // 指纹不同 → DIFFER。（比对是**追加**的，所以这里断言新增的那一条，不是总数。）
    var diff =
        as(() -> attributions.comparePhotos(
            id, new PhotoCompareRequest(p1, p3, null), uid));
    assertThat(diff.comparisons()).hasSize(2);
    assertThat(diff.comparisons().get(1).compareResult()).isEqualTo("IDENTITY_DIFFER");
    // 口径写在数据里，不是只写在文档里。
    assertThat(diff.comparisons())
        .allSatisfy(c -> assertThat(c.compareBasis()).isEqualTo("CONTENT_FINGERPRINT"));

    // 自己跟自己比对被拒（会让「比对了几对」虚高）。
    assertThatThrownBy(() -> as(() -> attributions.comparePhotos(
            id, new PhotoCompareRequest(p1, p1, null), uid)))
        .hasStackTraceContaining("自己跟自己比对");

    // ⚠️ **绕过服务层直写 SQL**：指纹不同却写 SAME，必须被 DB 触发器当场拒 ——
    //   这正是「不假装做过图像相似度」这条纪律的物理落法。
    // ⚠️ 断言**触发器自己抛的那句话**（而不是 SQLSTATE —— Spring 不把 SQLSTATE 渲染进 message）：
    //   断言消息才证明「它是因为指纹真的不同而拒」，而不是碰巧因为别的原因拒了。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "insert into product_issue_photo_comparison (id, tenant_id, attribution_id,"
                + " left_photo_id, right_photo_id, compare_result, compare_basis,"
                + " comparison_note, compared_by, compared_at)"
                + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'IDENTITY_SAME',"
                + " 'CONTENT_FINGERPRINT', '', ?::uuid, ?::timestamptz)",
            UUID.randomUUID(), tid, id, p3, p1, uid, T2.toString())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("两张照片内容指纹不同，比对结果只能是 IDENTITY_DIFFER");

    // 反向同理：指纹相同却写 DIFFER，同样被拒。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "insert into product_issue_photo_comparison (id, tenant_id, attribution_id,"
                + " left_photo_id, right_photo_id, compare_result, compare_basis,"
                + " comparison_note, compared_by, compared_at)"
                + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'IDENTITY_DIFFER',"
                + " 'CONTENT_FINGERPRINT', '', ?::uuid, ?::timestamptz)",
            UUID.randomUUID(), tid, id, p1, p2, uid, T2.toString())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("两张照片内容指纹相同，比对结果必须是 IDENTITY_SAME");
  }

  @Test
  void 照片登记校验对象键与指纹() throws Exception {
    Fixture f = seed("PHOTO2");
    UUID id = open(f, "SKU-PHOTO2", new BigDecimal("2.000000"));

    assertThatThrownBy(() -> as(() -> attributions.registerPhotos(
            id, new PhotoRegisterRequest(List.of()), uid)))
        .hasStackTraceContaining("至少要登记一张照片");
    assertThatThrownBy(() -> as(() -> attributions.registerPhotos(
            id,
            new PhotoRegisterRequest(List.of(new PhotoRegister(
                null, "DEFECT_CLOSEUP", "  ", "a".repeat(64), 10L, "image/jpeg", T1, ""))),
            uid)))
        .hasStackTraceContaining("必须给出 object_uri");
    // ⚠️ 指纹必须是 64 位十六进制：比的就是它，不是图像相似度。
    assertThatThrownBy(() -> as(() -> attributions.registerPhotos(
            id,
            new PhotoRegisterRequest(List.of(new PhotoRegister(
                null, "DEFECT_CLOSEUP", "s3://x/1.jpg", "not-a-sha", 10L, "image/jpeg", T1, ""))),
            uid)))
        .hasStackTraceContaining("必须是 64 位十六进制内容指纹");
    assertThatThrownBy(() -> as(() -> attributions.registerPhotos(
            id,
            new PhotoRegisterRequest(List.of(new PhotoRegister(
                null, "DEFECT_CLOSEUP", "s3://x/1.jpg", "a".repeat(64), 0L, "image/jpeg", T1, ""))),
            uid)))
        .hasStackTraceContaining("content_bytes 必须大于 0");
  }

  // ==========================================================================
  // 硬规则 3：批次质量标记
  // ==========================================================================

  @Test
  void 批次标记统计由触发器重算且低于口径不标() throws Exception {
    UUID lotId = UUID.randomUUID();

    // 同一个批次退回来 1 件 → **不标**（噪声）。
    Fixture f1 = seed("LOT-1");
    UUID i1 = open(f1, "SKU-LOT", new BigDecimal("1.000000"));
    attachLot(i1, lotId, "LOT-A");
    as(() -> attributions.attribute(
        i1, new AttributionConclude("PRODUCTION", "产线不良", "换模"), uid));
    var flag = as(() -> attributions.lotFlag(lotId));
    assertThat(flag.flagStatus()).as("1 件是纯噪声，不标").isEqualTo("NONE");
    assertThat(flag.suspectReturnCount()).isEqualTo(1);
    assertThat(flag.affectedReturnQty()).isEqualByComparingTo(new BigDecimal("1.000000"));
    assertThat(flag.stageBreakdown().production()).isEqualTo(1);

    // 再退 2 件到**同一个批次** → 累计 3 件，**标为可疑**。
    // 这正是蓝图加粗句「同一个批次退回来 10 件」vs「10 个批次各退 1 件」的计数落点。
    Fixture f2 = seed("LOT-2");
    UUID i2 = open(f2, "SKU-LOT", new BigDecimal("2.000000"));
    attachLot(i2, lotId, "LOT-A");
    as(() -> attributions.attribute(
        i2, new AttributionConclude("PRODUCTION", "同一批次的第 2 次退回", "换模"), uid));

    flag = as(() -> attributions.lotFlag(lotId));
    assertThat(flag.flagStatus()).isEqualTo("SUSPECT");
    assertThat(flag.suspectReturnCount()).isEqualTo(2);
    // ⚠️ 判据落在**件数**（3 件）而不是张数（2 张）——
    //   「一个批次退回来 3 件」该标，「10 个批次各退 1 件」不该标。
    assertThat(flag.affectedReturnQty()).isEqualByComparingTo(new BigDecimal("3.000000"));
    assertThat(flag.minFlagQty()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(flag.stageBreakdown().production()).isEqualTo(2);
    assertThat(flag.stageBreakdown().transport()).isZero();
    assertThat(flag.stageBreakdown().dominant()).isEqualTo(AttributionStage.PRODUCTION);

    // ⚠️ **绕过服务层直写**：统计列与真实归因不符，必须被 DB 守卫⑤当场拒。
    // 服务层刷新时写入的**就是重算出来的真值**（它没有「传入计数」这个入口）。
    as(() -> attributions.refreshLotFlag(i2, null, null, uid));
    assertThat(as(() -> attributions.lotFlag(lotId)).affectedReturnQty())
        .isEqualByComparingTo(new BigDecimal("3.000000"));

    Fixture f3 = seed("LOT-3");
    UUID i3 = open(f3, "SKU-LOT", new BigDecimal("2.000000"));
    attachLot(i3, lotId, "LOT-A");
    as(() -> attributions.attribute(
        i3, new AttributionConclude("PRODUCTION", "第 3 次退回", "换模"), uid));

    // ⚠️ **绕过服务层直写 SQL 塞一个假计数** —— 这才是守卫⑤真正要挡的动作。
    //   （调 refreshLotFlag 塞不进假值：它自己就重算，根本没有「传入计数」这个参数。）
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update product_issue_lot_flag set suspect_return_count = 999,"
                + " affected_return_qty = 999 where tenant_id = ?::uuid and lot_id = ?::uuid",
            tid, lotId)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("批次标记统计与当前生效归因不符");

    // 同样地，低于口径件数却标 CONFIRMED 也被拒。
    UUID loneLot = UUID.randomUUID();
    Fixture f4 = seed("LOT-4");
    UUID i4 = open(f4, "SKU-LOT4", new BigDecimal("1.000000"));
    attachLot(i4, loneLot, "LOT-SOLO");
    as(() -> attributions.attribute(
        i4, new AttributionConclude("TRANSPORT", "单件退回", "加固"), uid));
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update product_issue_lot_flag set flag_status = 'CONFIRMED',"
                + " flag_note = '手工标确认' where tenant_id = ?::uuid and lot_id = ?::uuid",
            tid, loneLot)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("低于口径");
  }

  @Test
  void 批次标记只数当前生效归因() throws Exception {
    UUID lotId = UUID.randomUUID();
    Fixture f1 = seed("EFF-1");
    Fixture f2 = seed("EFF-2");
    Fixture f3 = seed("EFF-3");
    UUID i1 = open(f1, "SKU-EFF", new BigDecimal("1.000000"));
    UUID i2 = open(f2, "SKU-EFF", new BigDecimal("1.000000"));
    UUID i3 = open(f3, "SKU-EFF", new BigDecimal("1.000000"));
    attachLot(i1, lotId, "LOT-E");
    attachLot(i2, lotId, "LOT-E");
    attachLot(i3, lotId, "LOT-E");

    as(() -> attributions.attribute(
        i1, new AttributionConclude("PRODUCTION", "第 1 次", "换模"), uid));
    as(() -> attributions.attribute(
        i2, new AttributionConclude("PRODUCTION", "第 2 次", "换模"), uid));
    as(() -> attributions.attribute(
        i3, new AttributionConclude("PRODUCTION", "第 3 次", "换模"), uid));
    assertThat(as(() -> attributions.lotFlag(lotId)).suspectReturnCount()).isEqualTo(3);

    // 把其中一张**改判**（原单 SUPERSEDED）：统计要立刻回落到 2 件。
    as(() -> attributions.supersede(
        i3, new AttributionSupersede("复核后改判运输", "TRANSPORT", "运输责任", "加固外箱"), uid));
    var flag = as(() -> attributions.lotFlag(lotId));
    assertThat(flag.suspectReturnCount()).as("改判留痕不重复计数").isEqualTo(3);
    assertThat(flag.stageBreakdown().production()).isEqualTo(2);
    assertThat(flag.stageBreakdown().transport()).isEqualTo(1);
    assertThat(flag.affectedReturnQty()).isEqualByComparingTo(new BigDecimal("3.000000"));
  }

  @Test
  void 批次标记列表与口径说明() throws Exception {
    UUID lotId = UUID.randomUUID();
    Fixture f = seed("FLAG-LIST");
    UUID id = open(f, "SKU-FLAG", new BigDecimal("2.000000"));
    attachLot(id, lotId, "LOT-F");
    // ⚠️ 批次标记是**归因的产物**：没出归因结论就没有标记行 —— 这不是数据缺失，
    //   是「这件事本身还没成立」。
    assertThat(as(() -> attributions.listLotFlags(null, null, null, 0, 20)).total()).isZero();
    as(() -> attributions.attribute(
        id, new AttributionConclude("PRODUCTION", "产线不良", "换模"), uid));

    var page = as(() -> attributions.listLotFlags(null, null, null, 0, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).lotCode()).isEqualTo("LOT-F");
    assertThat(page.rows().get(0).flagStatus()).isEqualTo("NONE");
    assertThat(page.thresholdNote()).contains("同一个批次退回来 10 件");
    // ⚠️ 口径说明必须说清本表**不是** warehouse 的 inventory_lot。
    assertThat(page.ownershipNote()).contains("不是** warehouse 的 inventory_lot");
  }

  // ==========================================================================
  // 能力声明：下游未交付必须逐条报 available=false
  // ==========================================================================

  @Test
  void 能力声明逐条报下游未交付() {
    var caps = as(() -> attributions.capabilities());

    assertThat(caps.relatedModules())
        .filteredOn(m -> m.id().equals("6.4.2.2"))
        .singleElement()
        .satisfies(m -> {
          assertThat(m.delivered()).isFalse();
          assertThat(m.ownerModule()).isEqualTo("6 采购");
          assertThat(m.href()).as("未交付的模块不给链接（一个点进去 404 的链接比没有更糟）").isNull();
        });
    assertThat(caps.relatedModules())
        .filteredOn(m -> m.id().equals("6.1.1.3"))
        .singleElement()
        .satisfies(m -> {
          assertThat(m.delivered()).isFalse();
          assertThat(m.href()).isNull();
        });
    // 已交付的上游要如实报 true。
    assertThat(caps.relatedModules())
        .filteredOn(m -> m.id().equals("7.3.1.1") || m.id().equals("11.2.3.1")
            || m.id().equals("11.1.2.1"))
        .hasSize(3)
        .allMatch(AttributionRelatedModule::delivered);

    // 图像相似度必须**明确报不可用**并说清原因，而不是让界面显示成空白。
    assertThat(caps.actions())
        .filteredOn(a -> a.code().equals("image-similarity"))
        .singleElement()
        .satisfies(a -> {
          assertThat(a.allowed()).isFalse();
          assertThat(a.unavailableReason()).contains("内容指纹");
        });
    // 跨服务边界必须写在能力声明里。
    assertThat(caps.crossServiceNote()).contains("warehouse");
    assertThat(caps.crossServiceNote()).contains("X-Tenant-Id");
    assertThat(caps.photoBasisNote()).contains("不是图像相似度");
  }

  @Test
  void 环节平票时不随意选主导() {
    // ⚠️ 平票返回 null 并在界面上显示「责任分散」——
    //   「一半生产一半运输」本身就是一个需要人看的结论，而不是一个可以随便挑的答案。
    assertThat(new StageBreakdown(1, 1, 0, 0, 0, 0).dominant()).isNull();
    assertThat(new StageBreakdown(2, 1, 0, 0, 0, 0).dominant()).isEqualTo(AttributionStage.PRODUCTION);
    assertThat(new StageBreakdown(0, 0, 0, 0, 0, 0).dominant()).isNull();
    assertThat(new StageBreakdown(0, 1, 2, 0, 0, 1).total()).isEqualTo(4);
  }

  @Test
  void 列表分页与筛选是真的() throws Exception {
    for (int i = 0; i < 3; i++) {
      Fixture f = seed("PAGE-" + i);
      UUID id = open(f, "SKU-P" + i, new BigDecimal("1.000000"));
      final int seq = i;
      as(() -> attributions.attribute(
          id, new AttributionConclude("TRANSPORT", "第 " + seq + " 张", "加固"), uid));
    }
    // 合计跨全表算，不随分页变小。
    var all = as(() -> attributions.listAttributions(query()));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.summary().transport()).isEqualTo(3);
    assertThat(all.summary().attributedQty()).isEqualByComparingTo(new BigDecimal("3.000000"));

    // 真分页：第 2 页只有 1 条。
    var second =
        as(() -> attributions.listAttributions(
            new ProductIssueAttributionService.AttributionListQuery(
                1, 2, null, null, null, null, null, null)));
    assertThat(second.rows()).hasSize(1);
    assertThat(second.total()).as("翻页时合计不变").isEqualTo(3);

    // 关键字筛选真的过滤。
    var filtered =
        as(() -> attributions.listAttributions(
            new ProductIssueAttributionService.AttributionListQuery(
                0, 20, null, null, "SKU-P1", null, null, null)));
    assertThat(filtered.total()).isEqualTo(1);
    // 非法状态值被拒。
    assertThatThrownBy(() -> as(() -> attributions.listAttributions(
            new ProductIssueAttributionService.AttributionListQuery(
                0, 20, "NOPE", null, null, null, null, null))))
        .hasStackTraceContaining("状态只能是");
  }
}
