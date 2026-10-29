package com.starcon.channel;

import static com.starcon.channel.LogisticsTimelinessModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.6.2.1 时效分析。
 *
 * <p>本测试类钉住几组事：
 *
 * <ol>
 *   <li><b>🔴 报表按「渠道 × 国家」交叉</b>（蓝图加粗句）：矩阵里每一行都是
 *       一个「渠道 × 国家」的格，<b>没有</b>「某个渠道总体」那一行；
 *       总体口径只出现在 {@code totals} 里。</li>
 *   <li><b>🔴 比率的分母是「可比数」不是「签收数」</b>：已签收但没有承诺时效可比的
 *       那批票单列，不算准点也不算超时。</li>
 *   <li><b>🔴 除零不炸</b>：某格一票都没签收时，达成率 / 超时率是
 *       <b>null（不可比）</b>而不是 0，也不是 ArithmeticException。</li>
 *   <li><b>🔴 平均时效带时区</b>：timestamptz 相减，跨时区渠道的时效不会偏。</li>
 *   <li><b>🔴 样本不足 ⇒ 评分不可比（null）而不是 0 分</b>。</li>
 *   <li><b>异常集中分析</b>：贡献率 / 累计贡献率在「渠道 × 国家」的格上算，
 *       帕累托前沿能直接读出来。</li>
 *   <li><b>落库幂等 + 写回值被断言</b>：同一窗口重算两次不堆快照，
 *       且比率由 DB 生成列算 —— 绕过服务层写 SQL 也写不进一个与计数对不上的比率。</li>
 * </ol>
 *
 * <p><b>时间一律 pin 住</b>：发货、承诺、签收时刻都是夹具里的固定值，
 * 不取墙上时钟；写回的时刻<b>断言实际读回的值</b>，不只断言没抛异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsTimelinessIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  // ---- 夹具 pin 住的时间点 ----
  static final OffsetDateTime SHIPPED = OffsetDateTime.parse("2026-09-02T08:00:00Z");
  static final OffsetDateTime PROMISED = OffsetDateTime.parse("2026-09-05T00:00:00Z");
  static final OffsetDateTime DELIVERED_ON_TIME = OffsetDateTime.parse("2026-09-04T12:00:00Z");
  static final OffsetDateTime DELIVERED_LATE = OffsetDateTime.parse("2026-09-07T12:00:00Z");
  static final OffsetDateTime AUG_SHIPPED = OffsetDateTime.parse("2026-08-10T08:00:00Z");
  static final OffsetDateTime AUG_PROMISED = OffsetDateTime.parse("2026-08-13T00:00:00Z");
  static final OffsetDateTime AUG_DELIVERED = OffsetDateTime.parse("2026-08-15T12:00:00Z");

  /** 统计窗口：2026-09-01 ~ 2026-09-30（含首含尾，UTC）。 */
  static final String FROM = "2026-09-01";
  static final String TO = "2026-09-30";
  /** 跨两个月的窗口：旺季用例。 */
  static final String WIDE_FROM = "2026-08-01";

  UUID carrierId, channelA, channelB;
  AccessService.Access actor;
  String bearer;

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired LogisticsTimelinessService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;
  @Autowired DSLContext sql;

  @BeforeEach
  void seedFixture() {
    bearer = "Bearer timeliness-" + UUID.randomUUID();
    actors.put(
        bearer,
        new AccessService.Access(
            user,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            allPermissions,
            List.of(
                new AccessService.Grant(
                    UUID.randomUUID(), "TEST", "ALL", allPermissions))));
    actor = actors.get(bearer);

    truncate();

    carrierId = UUID.randomUUID();
    exec(
        "insert into carrier_logistics_profile"
            + " (id,tenant_id,role_id,party_id,party_code,party_name)"
            + " values ('" + carrierId + "','" + tenant + "','" + UUID.randomUUID() + "','"
            + UUID.randomUUID() + "','SF','顺丰速运')");

    channelA = UUID.randomUUID();
    channelB = UUID.randomUUID();
    seedChannel(channelA, "CH-A", "渠道甲", "US", "DE");
    seedChannel(channelB, "CH-B", "渠道乙", "US", "DE", "FR");

    // CH-A × US：6 票准点 + 4 票超时（可比 10 票 ⇒ 默认门槛下评分可比）
    for (int i = 0; i < 6; i++) {
      seedSubject(channelA, "US", "SHIPPED", PROMISED, DELIVERED_ON_TIME, "DELIVERED");
    }
    for (int i = 0; i < 4; i++) {
      seedSubject(channelA, "US", "SHIPPED", PROMISED, DELIVERED_LATE, "DELIVERED");
    }
    // CH-A × DE：4 票全超时（可比 4 票 ⇒ 样本不足）
    for (int i = 0; i < 4; i++) {
      seedSubject(channelA, "DE", "SHIPPED", PROMISED, DELIVERED_LATE, "DELIVERED");
    }
    // CH-B × US：2 票准点 + 1 票超时 + 1 票在途
    for (int i = 0; i < 2; i++) {
      seedSubject(channelB, "US", "SHIPPED", PROMISED, DELIVERED_ON_TIME, "DELIVERED");
    }
    seedSubject(channelB, "US", "SHIPPED", PROMISED, DELIVERED_LATE, "DELIVERED");
    seedSubject(channelB, "US", "SHIPPED", null, null, "ACTIVE");
    // CH-B × FR：2 票全在途（分母为 0 ⇒ 达成率 / 超时率必须是 null，不是 0）
    for (int i = 0; i < 2; i++) {
      seedSubject(channelB, "FR", "SHIPPED", null, null, "ACTIVE");
    }
    // CH-B × DE：8 月的 5 票全超时（旺季用例）
    for (int i = 0; i < 5; i++) {
      seedSubject(channelB, "DE", "AUG_SHIPPED", AUG_PROMISED, AUG_DELIVERED, "DELIVERED");
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  void truncate() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：logistics_tracking_event 等表以外键引用 logistics_tracking_subject，
      // 不级联截断会被 PG 拒（cannot truncate a table referenced in a foreign key constraint）。
      s.execute("truncate logistics_timeliness_score, logistics_tracking_subject,"
          + " logistics_channel_config, carrier_logistics_profile cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void exec(String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 落一条渠道配置，并让它<b>真正可用</b>（readiness=READY）。
   *
   * <p>🔴 可用性由 DB 触发器算（基础单价 / 适用维度 / 状态三项），不接受手工指定：
   * 建渠道时还没有适用维度，算出来是 NO_SCOPE；补上国家维度后再更新成 READY。
   * 直接写一个错的 readiness 会被 trigger 拒（这也是仓库既有的一致性纪律）。
   */
  void seedChannel(UUID channelId, String code, String name, String... countries) {
    exec(
        "insert into logistics_channel_config"
            + " (id,tenant_id,carrier_profile_id,channel_code,channel_name,status,billing_mode,"
            + "  billing_currency,base_rate,readiness)"
            + " values ('" + channelId + "','" + tenant + "','" + carrierId + "','" + code + "','" + name
            + "','ACTIVE','BY_WEIGHT','USD',10,'NO_SCOPE')");
    for (String country : countries) {
      exec(
          "insert into logistics_channel_scope"
              + " (tenant_id,channel_id,scope_kind,scope_value)"
              + " values ('" + tenant + "','" + channelId + "','COUNTRY','" + country + "')");
    }
    exec(
        "update logistics_channel_config set readiness = 'READY' where id = '" + channelId + "'");
  }

  /**
   * 落一票轨迹主体。
   *
   * <p>承诺四件套（天数 min / max + 最早 / 最晚到达时刻）要么都给要么都不给 ——
   * 这是 V212 的表级 CHECK，给一半会被 DB 拒。
   */
  void seedSubject(
      UUID channel,
      String country,
      String shippedMarker,
      OffsetDateTime promised,
      OffsetDateTime delivered,
      String status) {
    OffsetDateTime shipped =
        "AUG_SHIPPED".equals(shippedMarker) ? AUG_SHIPPED : SHIPPED;
    UUID id = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    StringBuilder sql =
        new StringBuilder(
            "insert into logistics_tracking_subject"
                + " (id,tenant_id,source_shipment_record_id,source_record_no,sales_order_no,"
                + "  outbound_order_no,warehouse_ref,carrier_code,master_waybill_no,"
                + "  destination_country,channel_id,gross_weight_kg,service_level,parcel_count,"
                + "  shipped_at,promised_transit_days_min,promised_transit_days_max,"
                + "  promised_eta_earliest_at,promised_eta_at,matched_promise_count,"
                + "  latest_node,latest_event_code,latest_description,latest_occurred_at,"
                + "  latest_received_at,event_count,exception_flag,exception_count,status,"
                + "  created_at,updated_at) values (");
    sql.append("'").append(id).append("','").append(tenant).append("','").append(source).append("',");
    sql.append("'REC-").append(source.toString().substring(0, 8)).append("','SO-1','OB-1','WH-1',");
    sql.append("'SF','WB-").append(source.toString().substring(0, 8)).append("',");
    sql.append("'").append(country).append("','").append(channel).append("',1.5,'STANDARD',1,");
    sql.append("'").append(shipped).append("'::timestamptz,");
    if (promised == null) {
      sql.append("null,null,null,null,");
    } else {
      sql.append("3,3,'")
          .append(promised.minusDays(2))
          .append("'::timestamptz,'")
          .append(promised)
          .append("'::timestamptz,");
    }
    sql.append("1,");
    if (delivered == null) {
      sql.append("'IN_TRANSIT','','',null,null,0,false,0,'ACTIVE',now(),now())");
    } else {
      sql.append("'DELIVERED','DELIVERED','已签收','")
          .append(delivered)
          .append("'::timestamptz,'")
          .append(delivered)
          .append("'::timestamptz,1,false,0,'DELIVERED',now(),now())");
    }
    exec(sql.toString());
  }

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  // ==================================================================
  // 断言一：报表按「渠道 × 国家」交叉
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言一：矩阵按渠道 × 国家交叉，每格一行，没有「渠道总体」行")
  void matrixIsCrossedByChannelAndCountry() {
    TimelinessMatrixPage page =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, "OVERDUE_DESC", 0, 50));

    assertThat(page.rows()).hasSize(4);
    assertThat(page.totalElements()).isEqualTo(4);
    // 每一行都必须落在某一个「渠道 × 国家」的格上
    assertThat(page.rows())
        .allSatisfy(
            row -> {
              assertThat(row.channelId()).isNotNull();
              assertThat(row.channelCode()).isNotBlank();
              assertThat(row.countryCode()).hasSize(2);
            });
    // 四个格子的 (渠道, 国家) 组合互不重复
    assertThat(
            page.rows().stream()
                .map(r -> r.channelCode() + "/" + r.countryCode())
                .toList())
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder("CH-A/US", "CH-A/DE", "CH-B/US", "CH-B/FR");
  }

  @Test
  @DisplayName("10.6.2.1 断言二：比率分母是「可比数」；没有承诺的票单列，不算准点也不算超时")
  void ratesUseComparableDenominator() {
    TimelinessMatrixPage page =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
    TimelinessMatrixRow chAUs = rowOf(page, "CH-A", "US");
    TimelinessMatrixRow chAde = rowOf(page, "CH-A", "DE");

    // CH-A/US：10 票发出、10 票签收、6 票准点、4 票超时
    assertThat(chAUs.shippedCount()).isEqualTo(10);
    assertThat(chAUs.deliveredCount()).isEqualTo(10);
    assertThat(chAUs.comparableCount()).isEqualTo(10);
    assertThat(chAUs.onTimeCount()).isEqualTo(6);
    assertThat(chAUs.overdueCount()).isEqualTo(4);
    assertThat(chAUs.deliveryRate()).isEqualByComparingTo(bd("1.0000"));
    assertThat(chAUs.onTimeRate()).isEqualByComparingTo(bd("0.6000"));
    assertThat(chAUs.overdueRate()).isEqualByComparingTo(bd("0.4000"));

    // CH-A/DE：4 票全超时 ⇒ 达成率 0、超时率 1
    assertThat(chAde.comparableCount()).isEqualTo(4);
    assertThat(chAde.onTimeRate()).isEqualByComparingTo(bd("0.0000"));
    assertThat(chAde.overdueRate()).isEqualByComparingTo(bd("1.0000"));
  }

  @Test
  @DisplayName("10.6.2.1 断言三：除零不炸 —— 没有签收样本的格，达成率 / 超时率是 null 而不是 0")
  void zeroDenominatorYieldsNullNotZero() {
    TimelinessMatrixPage page =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
    TimelinessMatrixRow chBFr = rowOf(page, "CH-B", "FR");

    assertThat(chBFr.shippedCount()).isEqualTo(2);
    assertThat(chBFr.deliveredCount()).isZero();
    assertThat(chBFr.comparableCount()).isZero();
    assertThat(chBFr.pendingCount()).isEqualTo(2);
    assertThat(chBFr.deliveryRate()).isEqualByComparingTo(bd("0.0000"));
    // 🔴 不可比 = null，绝不是 0（0 会被读成「比过了，确实最差」）
    assertThat(chBFr.onTimeRate()).isNull();
    assertThat(chBFr.overdueRate()).isNull();
    assertThat(chBFr.avgTransitHours()).isNull();
    assertThat(chBFr.avgDeviationHours()).isNull();
  }

  @Test
  @DisplayName("10.6.2.1 断言四：平均时效与偏差按 timestamptz 相减（带时区），单位小时")
  void averageTransitHoursIsTimezoneAware() {
    TimelinessMatrixPage page =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));
    TimelinessMatrixRow chAUs = rowOf(page, "CH-A", "US");

    // 准点：09-02T08:00 → 09-04T12:00 = 52 小时；超时：→ 09-07T12:00 = 124 小时
    // 平均 = (6×52 + 4×124) / 10 = 80.8 小时
    assertThat(chAUs.avgTransitHours()).isEqualByComparingTo(bd("80.8000"));
    // 偏差：准点 −12 小时、超时 +60 小时 ⇒ (6×−12 + 4×60) / 10 = 16.8 小时
    assertThat(chAUs.avgDeviationHours()).isEqualByComparingTo(bd("16.8000"));
    assertThat(chAUs.maxOverdueHours()).isEqualByComparingTo(bd("60.0000"));
    // P90：10 票里 4 票是 124 小时 ⇒ P90 落在 124 小时（长尾那一截）
    assertThat(chAUs.p90TransitHours()).isEqualByComparingTo(bd("124.0000"));
    assertThat(chAUs.stddevTransitHours()).isNotNull();
    assertThat(chAUs.stddevTransitHours()).isPositive();
  }

  @Test
  @DisplayName("10.6.2.1 断言五：总体口径只出现在合计里，不混进任何一格")
  void totalsAreSeparateFromCells() {
    TimelinessMatrixPage page =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 50));

    // 9 月窗口：CH-A/US 10 + CH-A/DE 4 + CH-B/US 4 + CH-B/FR 2 = 20 票
    assertThat(page.totals().shippedCount()).isEqualTo(20);
    assertThat(page.totals().deliveredCount()).isEqualTo(17);
    assertThat(page.totals().comparableCount()).isEqualTo(17);
    assertThat(page.totals().overdueCount()).isEqualTo(9);
    // 合计的超时率 9/17，与任何一格都不同 —— 证明它不是「某一格的值」
    assertThat(page.totals().overdueRate()).isEqualByComparingTo(bd("0.5294"));
    assertThat(page.rows())
        .allSatisfy(row -> assertThat(row.overdueRate()).isNotEqualTo(page.totals().overdueRate()));
  }

  // ==================================================================
  // 断言六：旺季变化
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言六：月度分桶按 UTC，旺季 = 该月超时率高于窗口基线")
  void monthlyDetectsPeakSeason() {
    TimelinessMonthlyReport report =
        as("channel.operation.read", () -> service.monthly(WIDE_FROM, TO, null, null));

    assertThat(report.rows()).hasSize(2);
    TimelinessMonthlyRow august = report.rows().get(0);
    TimelinessMonthlyRow september = report.rows().get(1);
    assertThat(august.monthKey()).isEqualTo("2026-08");
    assertThat(september.monthKey()).isEqualTo("2026-09");

    // 8 月：CH-B/DE 5 票全超时 ⇒ 超时率 1.0000
    assertThat(august.shippedCount()).isEqualTo(5);
    assertThat(august.overdueRate()).isEqualByComparingTo(bd("1.0000"));
    // 9 月：20 票发出、17 票签收、9 票超时 ⇒ 超时率 9/17
    assertThat(september.shippedCount()).isEqualTo(20);
    assertThat(september.deliveredCount()).isEqualTo(17);
    assertThat(september.overdueRate()).isEqualByComparingTo(bd("0.5294"));

    // 基线 = 整个窗口的加权口径（8 月 5 + 9 月 9 = 14 票超时 / 22 票可比）
    assertThat(report.baseline().overdueRate()).isEqualByComparingTo(bd("0.6364"));
    assertThat(august.peakMonth()).isTrue();
    assertThat(september.peakMonth()).isFalse();
    assertThat(report.peakMonthCount()).isEqualTo(1);
  }

  // ==================================================================
  // 断言七：异常集中分析
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言七：异常集中分析在「渠道 × 国家」的格上算贡献率与帕累托前沿")
  void anomalyConcentrationIsCrossed() {
    TimelinessAnomalyReport report =
        as("channel.operation.read", () -> service.anomalies(FROM, TO));

    // 9 月超时共 4(CH-A/DE) + 4(CH-A/US) + 1(CH-B/US) = 9 票
    assertThat(report.totalOverdue()).isEqualTo(9);
    assertThat(report.cells()).hasSize(3);

    TimelinessAnomalyCell first = report.cells().get(0);
    TimelinessAnomalyCell second = report.cells().get(1);
    TimelinessAnomalyCell third = report.cells().get(2);

    assertThat(first.channelCode() + "/" + first.countryCode()).isEqualTo("CH-A/DE");
    assertThat(first.overdueCount()).isEqualTo(4);
    assertThat(first.contributionRate()).isEqualByComparingTo(bd("0.4444"));
    assertThat(first.cumulativeRate()).isEqualByComparingTo(bd("0.4444"));

    assertThat(second.channelCode() + "/" + second.countryCode()).isEqualTo("CH-A/US");
    assertThat(second.cumulativeRate()).isEqualByComparingTo(bd("0.8889"));

    assertThat(third.channelCode() + "/" + third.countryCode()).isEqualTo("CH-B/US");
    assertThat(third.contributionRate()).isEqualByComparingTo(bd("0.1111"));
    assertThat(third.cumulativeRate()).isEqualByComparingTo(bd("1.0000"));

    // 帕累托前沿：累计到 80% 的那两格（含跨过阈值的那一格）
    assertThat(report.paretoCellCount()).isEqualTo(2);
    assertThat(report.paretoOverdueCount()).isEqualTo(8);
    assertThat(first.inPareto()).isTrue();
    assertThat(second.inPareto()).isTrue();
    assertThat(third.inPareto()).isFalse();

    // 渠道维度与国家维度的集中度
    assertThat(report.byChannel()).isNotEmpty();
    assertThat(report.byCountry().get(0).key()).isEqualTo("US");
    assertThat(report.byCountry().get(0).overdueCount()).isEqualTo(5);
  }

  // ==================================================================
  // 断言八：渠道评分
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言八：样本不足 ⇒ 评分不可比（null）而不是 0 分；样本够 ⇒ 算出分与等级")
  void scoreIsNullWhenSampleIsInsufficient() {
    TimelinessScorePage page =
        as("channel.operation.read", () -> service.scores(FROM, TO, null, null, 0, 50));

    TimelinessScoreRow chAUs = scoreRowOf(page, "CH-A", "US");
    // 可比 10 票 ≥ 默认门槛 10 ⇒ 可比
    // 30×1.0000 + 50×0.6000 + 20×clamp(1−16.8/24,0,1)=20×0.3000 = 66.0000 ⇒ FAIR
    assertThat(chAUs.sampleSufficient()).isTrue();
    assertThat(chAUs.score()).isEqualByComparingTo(bd("66.0000"));
    assertThat(chAUs.scoreGrade()).isEqualTo("FAIR");

    // 可比 3 票 < 门槛 10 ⇒ 不可比，评分是 null（不是 0）
    TimelinessScoreRow chAde = scoreRowOf(page, "CH-A", "DE");
    assertThat(chAde.sampleSufficient()).isFalse();
    assertThat(chAde.score()).isNull();
    assertThat(chAde.scoreGrade()).isEqualTo("INSUFFICIENT");
  }

  // ==================================================================
  // 断言九：落库幂等 + 写回值被断言
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言九：重算并落库幂等，落库比率与实时口径逐位相同，写回时刻被断言")
  void snapshotWriteIsIdempotentAndMatchesLive() {
    TimelinessScoreSnapshotResult first =
        as(
            "channel.operation.create",
            () -> service.snapshotScores(FROM, TO, null));
    assertThat(first.computed()).isEqualTo(4);
    assertThat(first.written()).isEqualTo(4);
    assertThat(first.insufficient()).isEqualTo(3);

    // 再算一次：同一窗口不堆快照
    TimelinessScoreSnapshotResult second =
        as(
            "channel.operation.create",
            () -> service.snapshotScores(FROM, TO, null));
    assertThat(second.written()).isEqualTo(4);

    TimelinessScoreSnapshotPage page =
        as(
            "channel.operation.read",
            () -> service.scoreSnapshots(FROM, TO, null, null, 0, 50));
    assertThat(page.rows()).hasSize(4);
    assertThat(page.totalElements()).isEqualTo(4);

    TimelinessScoreSnapshotRow chAUs =
        page.rows().stream()
            .filter(r -> "CH-A".equals(r.channelCode()) && "US".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();

    // 窗口边界写回的是**实际值**（左闭右开，UTC 自然日）
    assertThat(chAUs.windowStart()).isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
    assertThat(chAUs.windowEnd()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    assertThat(chAUs.windowDays()).isEqualTo(30);
    assertThat(chAUs.computedAt()).isNotNull();

    // 比率是 DB 生成列算的，必须与实时口径逐位相同
    assertThat(chAUs.deliveryRate()).isEqualByComparingTo(bd("1.0000"));
    assertThat(chAUs.onTimeRate()).isEqualByComparingTo(bd("0.6000"));
    assertThat(chAUs.overdueRate()).isEqualByComparingTo(bd("0.4000"));
    assertThat(chAUs.avgTransitHours()).isEqualByComparingTo(bd("80.8000"));
    assertThat(chAUs.avgDeviationHours()).isEqualByComparingTo(bd("16.8000"));
    assertThat(chAUs.score()).isEqualByComparingTo(bd("66.0000"));
    assertThat(chAUs.scoreGrade()).isEqualTo("FAIR");
    assertThat(chAUs.sampleSufficient()).isTrue();
    assertThat(chAUs.minSampleThreshold()).isEqualTo(10);

    // 样本不足的那一格：分数是 null，等级是 INSUFFICIENT
    TimelinessScoreSnapshotRow chAde =
        page.rows().stream()
            .filter(r -> "CH-A".equals(r.channelCode()) && "DE".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    assertThat(chAde.score()).isNull();
    assertThat(chAde.scoreGrade()).isEqualTo("INSUFFICIENT");
    assertThat(chAde.sampleSufficient()).isFalse();
  }

  @Test
  @DisplayName("10.6.2.1 断言十：绕过服务层写 SQL 也写不进一个与计数对不上的比率（生成列）")
  void generatedRateColumnsRejectDirectWrites() {
    // 先落一份快照
    as("channel.operation.create", () -> service.snapshotScores(FROM, TO, null));

    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                exec(
                    "update logistics_timeliness_score set delivery_rate = 0.9999"
                        + " where country_code = 'US'"));
    assertThat(failure).isNotNull();
    assertThat(failure.getMessage()).contains("delivery_rate");
  }

  @Test
  @DisplayName("10.6.2.1 断言十一：样本门槛可调 —— 门槛降到 3 时原来不可比的格变得可比")
  void minSampleThresholdIsTenantTunable() {
    as("channel.operation.create", () -> service.snapshotScores(FROM, TO, 3));
    TimelinessScoreSnapshotPage page =
        as("channel.operation.read", () -> service.scoreSnapshots(FROM, TO, null, null, 0, 50));
    TimelinessScoreSnapshotRow chAde =
        page.rows().stream()
            .filter(r -> "CH-A".equals(r.channelCode()) && "DE".equals(r.countryCode()))
            .findFirst()
            .orElseThrow();
    // 30×1.0000 + 50×0.0000 + 20×clamp(1−60/24,0,1)=0 ⇒ 30.0000 ⇒ POOR
    assertThat(chAde.sampleSufficient()).isTrue();
    assertThat(chAde.score()).isEqualByComparingTo(bd("30.0000"));
    assertThat(chAde.scoreGrade()).isEqualTo("POOR");
    assertThat(chAde.minSampleThreshold()).isEqualTo(3);
  }

  // ==================================================================
  // 断言十二：真分页 + 真筛选
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言十二：真分页真筛选，汇总不受分页影响")
  void paginationAndFiltersAreReal() {
    TimelinessMatrixPage firstPage =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 0, 2));
    assertThat(firstPage.rows()).hasSize(2);
    assertThat(firstPage.totalElements()).isEqualTo(4);
    assertThat(firstPage.totalPages()).isEqualTo(2);
    // 汇总走独立查询，不受分页影响
    assertThat(firstPage.totals().shippedCount()).isEqualTo(20);

    TimelinessMatrixPage secondPage =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, null, null, 1, 2));
    assertThat(secondPage.rows()).hasSize(2);
    assertThat(
            firstPage.rows().stream().map(r -> r.channelCode()).toList())
        .doesNotContainAnyElementsOf(secondPage.rows().stream().map(r -> r.channelCode()).toList());

    // 按国家筛选
    TimelinessMatrixPage de =
        as("channel.operation.read", () -> service.matrix(FROM, TO, null, "DE", null, 0, 50));
    assertThat(de.rows()).hasSize(1);
    assertThat(de.rows().get(0).countryCode()).isEqualTo("DE");

    // 按渠道筛选
    TimelinessMatrixPage chB =
        as(
            "channel.operation.read",
            () -> service.matrix(FROM, TO, channelB.toString(), null, null, 0, 50));
    assertThat(chB.rows()).hasSize(2);
    assertThat(chB.rows()).allSatisfy(r -> assertThat(r.channelCode()).isEqualTo("CH-B"));
  }

  @Test
  @DisplayName("10.6.2.1 断言十三：逐票明细能核实某一格为什么超时")
  void subjectDetailExplainsACell() {
    TimelinessSubjectPage page =
        as("channel.operation.read", () -> service.subjects(FROM, TO, null, "DE", "OVERDUE", 0, 50));
    assertThat(page.rows()).hasSize(4);
    assertThat(page.totalElements()).isEqualTo(4);
    TimelinessSubjectRow row = page.rows().get(0);
    assertThat(row.countryCode()).isEqualTo("DE");
    assertThat(row.timelinessStatus()).isEqualTo("OVERDUE");
    assertThat(row.transitHours()).isEqualByComparingTo(bd("124.0000"));
    assertThat(row.deviationHours()).isEqualByComparingTo(bd("60.0000"));

    // 在途的那批：没有承诺也没有签收 ⇒ 状态是「在途未签收」，时长是 null
    TimelinessSubjectPage inTransit =
        as("channel.operation.read", () -> service.subjects(FROM, TO, null, "FR", "IN_TRANSIT", 0, 50));
    assertThat(inTransit.rows()).hasSize(2);
    assertThat(inTransit.rows().get(0).transitHours()).isNull();
    assertThat(inTransit.rows().get(0).deviationHours()).isNull();
  }

  // ==================================================================
  // 断言十四：非法入参被拒
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言十四：非法窗口 / 非法状态被拒（400），不静默降级")
  void invalidInputsAreRejected() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as("channel.operation.read", () -> service.matrix(TO, FROM, null, null, null, 0, 50))))
        .as("from 晚于 to 必须被拒")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () -> service.matrix("not-a-date", TO, null, null, null, 0, 50))))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () ->
                            service.subjects(
                                FROM, TO, null, null, "NOT_A_STATUS", 0, 50))))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.read",
                        () -> service.matrix("2020-01-01", TO, null, null, null, 0, 50))))
        .as("超过 730 天的窗口必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 断言十五：能力声明
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言十五：capabilities 里未交付的维度标 available=false + ownerModule + 影响")
  void capabilitiesDeclareGapsHonestly() {
    LogisticsTimelinessCapabilities capabilities =
        as("channel.operation.read", () -> service.capabilities());
    assertThat(capabilities.dimensions()).isNotEmpty();
    capabilities
        .dimensions()
        .forEach(
            dimension -> {
              if (!dimension.available()) {
                assertThat(dimension.ownerModule())
                    .as(dimension.dimension() + " 必须点名归属模块")
                    .isNotBlank();
                assertThat(dimension.impact())
                    .as(dimension.dimension() + " 必须说明缺了会怎样")
                    .isNotBlank();
              }
            });
    assertThat(
            capabilities.dimensions().stream()
                .filter(d -> d.dimension().equals("rating_backfill"))
                .findFirst()
                .orElseThrow()
                .available())
        .isFalse();
    assertThat(
            capabilities.dimensions().stream()
                .filter(d -> d.dimension().equals("cost_analysis_link"))
                .findFirst()
                .orElseThrow()
                .ownerModule())
        .isEqualTo("10.6.1.1");
  }

  // ==================================================================
  // 断言十六：HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("10.6.2.1 断言十六：HTTP 端点返回真实数据（不是空壳）")
  void httpEndpointsReturnRealData() throws Exception {
    http.perform(get("/channel/logistics-timeliness/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    http.perform(
            get("/channel/logistics-timeliness/overview")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.shippedCount").value(20))
        .andExpect(jsonPath("$.totals.overdueRate").value(0.5294));

    http.perform(
            get("/channel/logistics-timeliness/reports/matrix")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(4))
        .andExpect(jsonPath("$.rows[0].channelCode").isNotEmpty())
        .andExpect(jsonPath("$.rows[0].countryCode").isNotEmpty());

    http.perform(
            get("/channel/logistics-timeliness/reports/monthly")
                .header("Authorization", bearer)
                .param("from", WIDE_FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(2));

    http.perform(
            get("/channel/logistics-timeliness/reports/anomalies")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalOverdue").value(9));

    http.perform(
            get("/channel/logistics-timeliness/reports/scores")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(4));

    http.perform(
            post("/channel/logistics-timeliness/reports/score-snapshots")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"from\":\"" + FROM + "\",\"to\":\"" + TO + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.written").value(4));

    http.perform(
            get("/channel/logistics-timeliness/reports/score-snapshots")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(4));

    http.perform(
            get("/channel/logistics-timeliness/subjects")
                .header("Authorization", bearer)
                .param("from", FROM)
                .param("to", TO)
                .param("status", "OVERDUE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(9));

    // 未认证 ⇒ 401
    http.perform(get("/channel/logistics-timeliness/overview")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 工具
  // ==================================================================

  private static TimelinessMatrixRow rowOf(TimelinessMatrixPage page, String channel, String country) {
    return page.rows().stream()
        .filter(r -> channel.equals(r.channelCode()) && country.equals(r.countryCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("矩阵里没有 " + channel + "/" + country));
  }

  private static TimelinessScoreRow scoreRowOf(TimelinessScorePage page, String channel, String country) {
    return page.rows().stream()
        .filter(r -> channel.equals(r.channelCode()) && country.equals(r.countryCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("评分里没有 " + channel + "/" + country));
  }
}
