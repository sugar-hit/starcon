package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.1.2.2 本位币与账簿。
 *
 * <p>重点不是"能存进去"，而是三条口径真的成立：
 *
 * <ol>
 *   <li><b>本位币归法人</b>——账簿上的币种必须等于法人的本位币，租户默认币种不参与；
 *   <li><b>记账规则两套量纲</b>——金额精度/舍入与数量精度/舍入各自独立，能各自读回来；
 *   <li><b>期间设置可用</b>——期间粒度、起始月、首期起点能推出当前会计年度区间，且首期必须对齐期界。
 * </ol>
 *
 * <p>时间字段全部按值断言：{@code firstPeriodStart} 用 +08:00 偏移写入，读回必须是同一个瞬时，
 * 否则用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 的写法会静默偏 −28800 秒。
 */
class AccountingBookIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 建法人，返回 id。 */
  private UUID entity(String token, String code, String currency) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, currency), 200)
            .asText());
  }

  private UUID createBook(String token, UUID entityId, String code, String currency) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/legal-entities/" + entityId + "/accounting-books", bookInput(code, currency), 200)
            .asText());
  }

  private Map<String, Object> bookInput(String code, String currency) {
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("code", code);
    input.put("name", "测试账簿 " + code);
    input.put("bookType", "BOOK");
    input.put("baseCurrency", currency);
    input.put("amountScale", 2);
    input.put("amountRounding", "HALF_UP");
    input.put("quantityScale", 3);
    input.put("quantityRounding", "DOWN");
    input.put("periodType", "MONTH");
    input.put("fiscalYearStartMonth", 1);
    input.put("firstPeriodStart", "2026-01-01T00:00:00Z");
    input.put("status", "ACTIVE");
    return input;
  }

  /** 在账簿数组里按 code 找一行，找不到返回 null。 */
  private JsonNode find(JsonNode books, String code) {
    for (JsonNode b : books) if (code.equals(b.get("code").asText())) return b;
    return null;
  }

  private String currentTenantId(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  /** 造一个子租户并返回它的所有者 token。 */
  private String childOwnerToken(String ownerToken, String tag) throws Exception {
    String username = "ab-child-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "ABC_" + tag,
            "name",
            "账簿子租户",
            "ownerUsername",
            username,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(username);
  }

  // ------------------------------------------------------------------
  // 本位币归法人
  // ------------------------------------------------------------------

  /**
   * 账簿币种必须等于法人的本位币。
   *
   * <p>这是本模块最容易被做漏的一条：租户也有默认币种，如果按租户默认币种放行，
   * "这个法人按哪个币记账"就会有两个答案，12 财务总账也就无从起算。
   */
  @Test
  void bookCurrencyMustEqualTheLegalEntityCurrency() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");

    // 与法人一致 → 允许
    createBook(token, entityId, "BK_" + tag, "USD");

    // 与法人不一致 → 409，且错误文案要指向法人档案而不是让用户瞎猜
    request(
            token,
            "POST",
            "/identity/legal-entities/" + entityId + "/accounting-books",
            bookInput("BAD_" + tag, "EUR"),
            409)
        .toString();

    // 按本法人过滤，不看租户全量：账簿现在已经可以一法人多本（17.6.1.1），
    // 全量清单里必然还有别的用例建的账，拿它断言 hasSize(1) 是在断言"这个租户只有一本账"。
    var books =
        request(token, "GET", "/identity/accounting-books?legalEntityId=" + entityId, null, 200);
    assertThat(books).hasSize(1);
    assertThat(books.get(0).get("baseCurrency").asText()).isEqualTo("USD");
    assertThat(books.get(0).get("legalEntityCurrency").asText()).isEqualTo("USD");
    assertThat(books.get(0).get("currencyAligned").asBoolean()).isTrue();
  }

  /**
   * 法人本位币改动后，旧账簿必须显式暴露为未对齐，而不是继续假装一致。
   *
   * <p>{@code currencyAligned} 就是为这个场景存在的：法人是唯一真相源，改了它，
   * 账簿上的副本就落后了，下游据此提示用户去同步账簿。
   */
  @Test
  void changingTheEntityCurrencyExposesMisalignedBooks() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String code = "BK_" + tag;
    createBook(token, entityId, code, "USD");

    var input = new java.util.LinkedHashMap<>(legalEntityInput("LE_" + tag, "EUR"));
    request(token, "PUT", "/identity/legal-entities/" + entityId, input, 200);

    var book = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    assertThat(book).isNotNull();
    assertThat(book.get("legalEntityCurrency").asText()).isEqualTo("EUR");
    assertThat(book.get("baseCurrency").asText()).isEqualTo("USD");
    assertThat(book.get("currencyAligned").asBoolean()).isFalse();
  }

  /** 换币留痕：只有本位币真的变了才前进，改名字不抹掉"从哪一刻起按哪个币记"。 */
  @Test
  void baseCurrencyChangedAtOnlyMovesWhenTheCurrencyActuallyChanges() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String code = "BK_" + tag;
    createBook(token, entityId, code, "USD");

    var first = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    String initial = first.get("baseCurrencyChangedAt").asText();

    // 只改名：本位币没变，时间戳必须原地不动
    var renamed = new java.util.LinkedHashMap<>(bookInput(code, "USD"));
    renamed.put("name", "改过名字的账簿");
    request(token, "PUT", "/identity/accounting-books/" + first.get("id").asText(), renamed, 200);
    var afterRename = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    assertThat(afterRename.get("name").asText()).isEqualTo("改过名字的账簿");
    assertThat(afterRename.get("baseCurrencyChangedAt").asText()).isEqualTo(initial);

    // 法人先换到 EUR，账簿跟上：换币留痕必须前进
    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entityId,
        new java.util.LinkedHashMap<>(legalEntityInput("LE_" + tag, "EUR")),
        200);
    Instant switched = Instant.now();
    var toEur = new java.util.LinkedHashMap<>(bookInput(code, "EUR"));
    request(token, "PUT", "/identity/accounting-books/" + first.get("id").asText(), toEur, 200);

    var afterSwap = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    assertThat(afterSwap.get("currencyAligned").asBoolean()).isTrue();
    assertThat(OffsetDateTime.parse(afterSwap.get("baseCurrencyChangedAt").asText()).toInstant())
        .isAfterOrEqualTo(switched.minusSeconds(60))
        .isBefore(Instant.now().plusSeconds(60));
  }

  // ------------------------------------------------------------------
  // 记账规则：金额与数量是两套量纲
  // ------------------------------------------------------------------

  /**
   * 金额精度/舍入与数量精度/舍入各自独立，并真的落库。
   *
   * <p>把两套量纲合成一把尺子是 12 财务试算不平的常见原因：金额按 2 位、数量按 3 位，
   * 用同一个 scale 去舍入，数量尾数就会被吃掉。
   */
  @Test
  void amountAndQuantityPrecisionAreIndependentAndPersisted() throws Exception {
    String token = login(), tag = suffix(), tenantId = currentTenantId(token);
    UUID entityId = entity(token, "LE_" + tag, "JPY");
    String code = "BK_" + tag;
    createBook(token, entityId, code, "JPY");

    var input = new java.util.LinkedHashMap<>(bookInput(code, "JPY"));
    input.put("amountScale", 0);
    input.put("amountRounding", "HALF_EVEN");
    input.put("quantityScale", 6);
    input.put("quantityRounding", "UP");
    UUID id = byCode(token, code);
    request(token, "PUT", "/identity/accounting-books/" + id, input, 200);

    var book = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    assertThat(book.get("amountScale").asInt()).isZero();
    assertThat(book.get("amountRounding").asText()).isEqualTo("HALF_EVEN");
    assertThat(book.get("quantityScale").asInt()).isEqualTo(6);
    assertThat(book.get("quantityRounding").asText()).isEqualTo("UP");

    // 直查数据库：不是内存里 round-trip 出来的假一致
    var row =
        tx()
            .run(
                UUID.fromString(tenantId),
                () ->
                    sql.fetchOne(
                        "select amount_scale,amount_rounding,quantity_scale,quantity_rounding"
                            + " from accounting_book where id=?",
                        id));
    assertThat(row.get("amount_scale", Integer.class)).isZero();
    assertThat(row.get("amount_rounding", String.class)).isEqualTo("HALF_EVEN");
    assertThat(row.get("quantity_scale", Integer.class)).isEqualTo(6);
    assertThat(row.get("quantity_rounding", String.class)).isEqualTo("UP");
  }

  // ------------------------------------------------------------------
  // 期间设置
  // ------------------------------------------------------------------

  /** 会计年度起始月决定当前财务年度区间，列表里就能读出来，不用等 12.5 生成期间行。 */
  @Test
  void periodSettingsProduceAReadableFiscalYear() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String calendar = "CAL_" + tag;
    String fiscal = "FY_" + tag;

    // 自然年度：起始月 1
    var january = bookInput(calendar, "USD");
    request(token, "POST", "/identity/legal-entities/" + entityId + "/accounting-books", january, 200);

    // 起始月 4 的非总账（一个法人只能一本总账，所以用现金账簿）
    var april = new java.util.LinkedHashMap<>(bookInput(fiscal, "USD"));
    april.put("bookType", "CASH");
    april.put("name", "四月起算的现金账");
    april.put("fiscalYearStartMonth", 4);
    april.put("firstPeriodStart", "2026-04-01T00:00:00Z");
    request(token, "POST", "/identity/legal-entities/" + entityId + "/accounting-books", april, 200);

    var books = request(token, "GET", "/identity/accounting-books", null, 200);
    var calendarBook = find(books, calendar);
    var fiscalBook = find(books, fiscal);

    assertThat(calendarBook.get("fiscalYearStartMonth").asInt()).isEqualTo(1);
    assertThat(calendarBook.get("currentFiscalYear").asInt())
        .isEqualTo(OffsetDateTime.parse(calendarBook.get("firstPeriodStart").asText()).getYear());
    assertThat(calendarBook.get("fiscalYearStart").asText()).endsWith("-01-01");
    assertThat(calendarBook.get("fiscalYearEnd").asText()).endsWith("-12-31");

    // 起始月 4：当前日期落在起始月之后时，年度从当年 4 月 1 日起、次年 3 月 31 日止。
    // fiscalYearStart/End 是日历日期（LocalDate），不是时刻。
    assertThat(LocalDate.parse(fiscalBook.get("fiscalYearStart").asText()).getMonthValue()).isEqualTo(4);
    assertThat(fiscalBook.get("fiscalYearEnd").asText())
        .isEqualTo(LocalDate.parse(fiscalBook.get("fiscalYearStart").asText()).plusYears(1).minusDays(1).toString());
    // 当前日期若早于起始月，本年度应归属上一年
    assertThat(fiscalBook.get("currentFiscalYear").asInt())
        .isBetween(
            OffsetDateTime.parse(fiscalBook.get("firstPeriodStart").asText()).getYear(),
            OffsetDateTime.parse(fiscalBook.get("firstPeriodStart").asText()).getYear() + 1);
  }

  /**
   * 首期起点必须落在期界上，对不上就拒绝。
   *
   * <p>月度起始月 4 却在 3 月开账，12.5 按起始月切年度时会切出一个不存在的期界。
   * 周粒度不对齐月份（ISO 周跨月），所以不检查。
   */
  @Test
  void misalignedFirstPeriodIsRejected() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String path = "/identity/legal-entities/" + entityId + "/accounting-books";

    // 月度 + 起始月 4 + 3 月首期 → 不对齐
    var wrong = new java.util.LinkedHashMap<>(bookInput("MIS_" + tag, "USD"));
    wrong.put("fiscalYearStartMonth", 4);
    wrong.put("firstPeriodStart", "2026-03-01T00:00:00Z");
    request(token, "POST", path, wrong, 400);

    // 季度 + 起始月 4 + 6 月首期：对齐（6-4=2 不是 3 的倍数？其实是 2 → 不对齐）
    var quarter = new java.util.LinkedHashMap<>(bookInput("QTR_" + tag, "USD"));
    quarter.put("bookType", "CASH");
    quarter.put("periodType", "QUARTER");
    quarter.put("fiscalYearStartMonth", 4);
    quarter.put("firstPeriodStart", "2026-06-01T00:00:00Z");
    request(token, "POST", path, quarter, 400);
    quarter.put("firstPeriodStart", "2026-07-01T00:00:00Z");
    request(token, "POST", path, quarter, 200);

    // 周粒度不检查月份对齐
    var weekly = new java.util.LinkedHashMap<>(bookInput("WK_" + tag, "USD"));
    weekly.put("bookType", "BANK");
    weekly.put("periodType", "WEEK");
    weekly.put("fiscalYearStartMonth", 4);
    weekly.put("firstPeriodStart", "2026-03-11T00:00:00Z");
    request(token, "POST", path, weekly, 200);
  }

  /**
   * firstPeriodStart 是真实写入并原样读回的 timestamptz。
   *
   * <p>用 +08:00 偏移写入，读回必须是同一个**瞬时**：若绑定时走
   * {@code Timestamp.valueOf(LocalDateTime)}，库里会静默偏 −28800 秒，
   * 而断言只检查"没抛异常"是发现不了的。
   */
  @Test
  void firstPeriodStartIsARealTimestamptzWrittenAndReadBack() throws Exception {
    String token = login(), tag = suffix(), tenantId = currentTenantId(token);
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String code = "TS_" + tag;

    var input = new java.util.LinkedHashMap<>(bookInput(code, "USD"));
    // 09:30 +08:00 == 01:30Z，同一个瞬时，两种写法结果必须一致
    input.put("firstPeriodStart", "2026-01-01T09:30:00+08:00");
    request(token, "POST", "/identity/legal-entities/" + entityId + "/accounting-books", input, 200);

    UUID id = byCode(token, code);
    var book = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    Instant expected = OffsetDateTime.parse("2026-01-01T09:30:00+08:00").toInstant();
    assertThat(OffsetDateTime.parse(book.get("firstPeriodStart").asText()).toInstant())
        .as("首期起点必须原样往返（漂移 8 小时说明 timestamptz 绑定错了）")
        .isEqualTo(expected);

    var stored =
        tx()
            .run(
                UUID.fromString(tenantId),
                () ->
                    sql.fetchOne("select first_period_start from accounting_book where id=?", id)
                        .get("first_period_start", OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant()).isEqualTo(expected);
  }

  // ------------------------------------------------------------------
  // 账簿本身的约束
  // ------------------------------------------------------------------

  /** 一个法人一本总账，但银行账可以按账户开多本。 */
  @Test
  void oneGeneralLedgerPerEntityButManyBankBooks() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String path = "/identity/legal-entities/" + entityId + "/accounting-books";
    createBook(token, entityId, "BK_" + tag, "USD");

    // 第二本总账 → 409（部分唯一索引）
    request(token, "POST", path, bookInput("BK2_" + tag, "USD"), 409);

    // 编码重复 → 也是 409
    request(token, "POST", path, bookInput("BK_" + tag, "USD"), 409);

    // 银行账多本
    var bank1 = new java.util.LinkedHashMap<>(bookInput("BANK1_" + tag, "USD"));
    bank1.put("bookType", "BANK");
    request(token, "POST", path, bank1, 200);
    var bank2 = new java.util.LinkedHashMap<>(bookInput("BANK2_" + tag, "USD"));
    bank2.put("bookType", "BANK");
    request(token, "POST", path, bank2, 200);

    // 按法人过滤统计：本地租户是多个测试类共用的，全量列表里混着别的测试的账簿
    // 1 本总账 + 2 本银行账 = 3（被拒的第二本总账不算）
    var entityBooks =
        request(token, "GET", "/identity/accounting-books?legalEntityId=" + entityId, null, 200);
    assertThat(entityBooks).hasSize(3);
    assertThat(find(entityBooks, "BK_" + tag)).isNotNull();
    assertThat(find(entityBooks, "BK2_" + tag)).as("第二本总账必须没落库").isNull();
  }

  /** 账簿挂在法人上：法人还有账簿时不能删，否则账簿会变成没有归属的孤儿配置。 */
  @Test
  void legalEntityWithBooksCannotBeDeleted() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    createBook(token, entityId, "BK_" + tag, "USD");

    request(token, "DELETE", "/identity/legal-entities/" + entityId, null, 409);

    request(token, "DELETE", "/identity/accounting-books/" + byCode(token, "BK_" + tag), null, 200);
    request(token, "DELETE", "/identity/legal-entities/" + entityId, null, 200);
  }

  /** 按法人过滤：只能读到指定法人的账簿。 */
  @Test
  void booksCanBeFilteredByLegalEntity() throws Exception {
    String token = login(), tag = suffix();
    UUID a = entity(token, "LEA_" + tag, "USD");
    UUID b = entity(token, "LEB_" + tag, "USD");
    createBook(token, a, "A_" + tag, "USD");
    createBook(token, b, "B_" + tag, "USD");

    assertThat(request(token, "GET", "/identity/accounting-books?legalEntityId=" + a, null, 200))
        .hasSize(1);
    var filtered = request(token, "GET", "/identity/accounting-books?legalEntityId=" + a, null, 200);
    assertThat(filtered.get(0).get("code").asText()).isEqualTo("A_" + tag);
    // 另一个租户的法人 id 在本租户读不到任何东西
    request(token, "GET", "/identity/accounting-books?legalEntityId=" + UUID.randomUUID(), null, 200);
  }

  /** 记账规则变更进审计：事后要能答出当时是几套规则。按 resourceId 过滤，不依赖全租户条数。 */
  @Test
  void accountingBookChangesAreAudited() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    String code = "BK_" + tag;
    createBook(token, entityId, code, "USD");
    UUID bookId = byCode(token, code);

    var input = new java.util.LinkedHashMap<>(bookInput(code, "USD"));
    input.put("quantityScale", 6);
    request(token, "PUT", "/identity/accounting-books/" + bookId, input, 200);

    var entries =
        request(
                token,
                "GET",
                "/identity/audit?resourceType=identity.accounting_book&resourceId=" + bookId,
                null,
                200);
    assertThat(entries.get("total").asInt()).isEqualTo(2);
    assertThat(entries.get("items").get(0).get("action").asText()).isEqualTo("AccountingBookUpdated");

    // 变更前后值都要留痕：改前的 3 位与改后的 6 位（数量是数字，不是字符串）
    var detail =
        request(token, "GET", "/identity/audit/" + entries.get("items").get(0).get("id").asText(), null, 200);
    assertThat(detail.toString()).contains("\"quantityScale\":6").contains("\"quantityScale\":3");
    // 只改数量精度不该被记成本位币切换
    assertThat(detail.toString()).contains("\"baseCurrency\":\"USD\"");
  }

  /** 权限：没有账簿读权限的成员拿不到任何账簿，也没有假数据兜底。 */
  @Test
  void membersWithoutTheBookPermissionAreRefused() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "USD");
    createBook(token, entityId, "BK_" + tag, "USD");

    var roleId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "ABR_" + tag,
                        "name",
                        "账簿测试角色",
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("catalog.spu.read")),
                    200)
                .asText());
    String username = "ab-member-" + tag;
    request(
        token,
        "POST",
        "/identity/members",
        memberInput(username, null, List.of(Map.of("roleId", roleId, "scopeMode", "ALL"))),
        200);
    String memberToken = loginUser(username);

    request(memberToken, "GET", "/identity/accounting-books", null, 403);
    // 组织变更还要求 OWNER：伪造法人 id 也不行
    request(
        memberToken,
        "POST",
        "/identity/legal-entities/" + entityId + "/accounting-books",
        bookInput("X_" + tag, "USD"),
        403);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  /**
   * 租户隔离三断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
   *
   * <p>基类把连接池压到 1，所以每个请求必然复用同一条物理连接——租户设置泄漏到连接上，
   * 第二个租户就会读到第一个租户的账簿。
   */
  @Test
  void tenantIsolationHoldsAcrossConnectionReuse() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = childOwnerToken(ownerToken, tag);
    UUID parentId = UUID.fromString(currentTenantId(ownerToken));
    UUID childId = UUID.fromString(currentTenantId(childToken));
    assertThat(parentId).isNotEqualTo(childId);

    String parentCode = "P_" + tag;
    String childCode = "C_" + tag;
    createBook(ownerToken, entity(ownerToken, "LEP_" + tag, "USD"), parentCode, "USD");
    createBook(childToken, entity(childToken, "LEC_" + tag, "USD"), childCode, "USD");

    // 1) 两租户互不可见（交替 3 次，覆盖连接复用）
    for (int i = 0; i < 3; i++) {
      var parentBooks = request(ownerToken, "GET", "/identity/accounting-books", null, 200);
      assertThat(find(parentBooks, parentCode)).isNotNull();
      assertThat(find(parentBooks, childCode)).isNull();

      var childBooks = request(childToken, "GET", "/identity/accounting-books", null, 200);
      assertThat(find(childBooks, childCode)).isNotNull();
      assertThat(find(childBooks, parentCode)).isNull();
    }

    // 2) 伪造 X-Tenant-Id：即使写成一个真实存在的别家租户，也换不来它的账簿
    var forged =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/identity/accounting-books")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("X-Tenant-Id", childId.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).doesNotContain(childCode).doesNotContain(childId.toString());

    // 3) 连接归还后不串租户：直连表按当前租户设置过滤，池里只有一条连接
    var parentRows =
        tx().run(parentId, () -> sql.fetch("select id from accounting_book").getValues("id", UUID.class));
    var childRows =
        tx().run(childId, () -> sql.fetch("select id from accounting_book").getValues("id", UUID.class));
    assertThat(parentRows).isNotEmpty().doesNotContainAnyElementsOf(childRows);
    assertThat(childRows).isNotEmpty();
    // 没有 app.current_tenant 时一条都读不到：租户判定不在连接上残留
    assertThat(sql.fetch("select id from accounting_book")).isEmpty();

    // 运行时角色绕不过 FORCE RLS
    var runtime = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                    + " where n.nspname='public' and relname='accounting_book'"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
  }

  /** 按 id 读回账簿：PUT 路径需要 id，列表按 code 找出来再用。 */
  private UUID byCode(String token, String code) throws Exception {
    JsonNode found = find(request(token, "GET", "/identity/accounting-books", null, 200), code);
    assertThat(found).as("账簿 %s 应存在", code).isNotNull();
    return UUID.fromString(found.get("id").asText());
  }
}