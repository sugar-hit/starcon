package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.6.1.1 账簿与本位币（D6）。
 *
 * <p>17.1.2.2 已经交付了"一本账怎么建"，本条要证明的是跨境多账簿下真正成立的四条口径：
 *
 * <ol>
 *   <li><b>一个法人多本账、本位币不同</b>：业务账 CNY 与海外账 USD 并存，且各账本位币可读回；
 *   <li><b>业务账 / 法定账分组</b>：分类与 17.1.2.2 的账簿类型是两条正交的轴，不互相覆盖；
 *   <li><b>默认账簿显式</b>：没有默认时 409 而不是静默挑一本；一个法人至多一本默认；
 *   <li><b>跨账簿不混记</b>：同一笔业务事实只能归属一本账，第二本账再登记被数据库拒掉。
 * </ol>
 *
 * <p>金额按 {@code BigDecimal} 往返断言（0.1 + 0.2 这类不能变成 0.30000000000000004）， 时间字段按值断言偏移不漂移 —— 用 {@code
 * Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，这个坑本模块也踩不得。
 */
class LedgerBookIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  private UUID entity(String token, String code, String currency) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/legal-entities", legalEntityInput(code, currency), 200)
            .asText());
  }

  /** 账簿输入。17.6.1.1 的四个新字段都可选：不传就是 17.1.2.2 的老行为。 */
  private Map<String, Object> bookInput(String code, String currency) {
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("code", code);
    input.put("name", "账簿 " + code);
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

  private Map<String, Object> crossCurrencyBook(String code, String currency) {
    var input = new java.util.LinkedHashMap<String, Object>(bookInput(code, currency));
    // OWN：账簿自己决定本位币，跨境多币种记账的前提
    input.put("baseCurrencyMode", "OWN");
    return input;
  }

  private UUID createBook(String token, UUID entityId, Map<String, Object> input) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/legal-entities/" + entityId + "/accounting-books",
                input,
                200)
            .asText());
  }

  private JsonNode find(JsonNode books, String code) {
    for (JsonNode b : books) if (code.equals(b.get("code").asText())) return b;
    return null;
  }

  private String currentTenantId(String token) throws Exception {
    return request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText();
  }

  private String childOwnerToken(String ownerToken, String tag) throws Exception {
    String username = "lb-child-" + tag;
    request(
        ownerToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "LBC_" + tag,
            "name",
            "账簿子租户",
            "ownerUsername",
            username,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(username);
  }

  private Map<String, Object> factInput(String source, UUID factRef) {
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("factSource", source);
    input.put("factRef", factRef.toString());
    return input;
  }

  /**
   * 发一次必然 409 的请求，返回业务错误文案。
   *
   * <p>不能读响应体：identity 的业务错误按本模块惯例抛 {@code ResponseStatusException} （文案在 {@code getReason()}
   * 上），MockMvc 不会把它渲染成 JSON body，读 body 只会拿到空串。 这条断言恰恰要验的就是那句文案，所以从异常上取。
   */
  private String conflictReason(String token, String path, Object body) throws Exception {
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", UUID.randomUUID())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isConflict())
            .andReturn();
    var thrown = response.getResolvedException();
    assertThat(thrown)
        .as("跨账簿混记应当抛出带文案的 ResponseStatusException")
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    return ((org.springframework.web.server.ResponseStatusException) thrown).getReason();
  }

  // ------------------------------------------------------------------
  // 多账簿与本位币
  // ------------------------------------------------------------------

  /**
   * 蓝图的核心场景：一个法人同时用人民币业务账与美元海外账记账。
   *
   * <p>同时校验 INHERIT 老纪律没被破坏——不声明 OWN 时本位币仍必须等于法人本位币， 否则"这个法人按哪个币记账"会重新出现两个答案。
   */
  @Test
  void oneLegalEntityKeepsBusinessCnyAndOverseasUsdBooks() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");

    UUID cny = createBook(token, entityId, bookInput("CNY_" + tag, "CNY"));
    // 海外账 USD：OWN 模式下允许与法人本位币不同，否则多币种记账根本建不起来
    UUID usd = createBook(token, entityId, crossCurrencyBook("USD_" + tag, "USD"));

    // 不声明 OWN 时仍受 17.1.2.2 纪律约束
    request(
        token,
        "POST",
        "/identity/legal-entities/" + entityId + "/accounting-books",
        bookInput("BAD_" + tag, "EUR"),
        409);

    var books = request(token, "GET", "/identity/accounting-books", null, 200);
    var cnyBook = find(books, "CNY_" + tag);
    var usdBook = find(books, "USD_" + tag);
    assertThat(cnyBook).isNotNull();
    assertThat(usdBook).isNotNull();
    assertThat(cnyBook.get("baseCurrency").asText()).isEqualTo("CNY");
    assertThat(cnyBook.get("baseCurrencyMode").asText()).isEqualTo("INHERIT");
    assertThat(cnyBook.get("currencyAligned").asBoolean()).isTrue();
    assertThat(usdBook.get("baseCurrency").asText()).isEqualTo("USD");
    assertThat(usdBook.get("baseCurrencyMode").asText()).isEqualTo("OWN");
    // 本位币归法人这条纪律对 INHERIT 仍然生效，所以两本都读得到
    assertThat(usdBook.get("legalEntityCurrency").asText()).isEqualTo("CNY");

    // 同一本位币下仍然只允许一本总账
    request(
        token,
        "POST",
        "/identity/legal-entities/" + entityId + "/accounting-books",
        crossCurrencyBook("USD2_" + tag, "USD"),
        409);
    assertThat(cny).isNotEqualTo(usd);
  }

  /** 业务账与法定账是两条正交的轴：一本法定账同时可以是银行账簿。 */
  @Test
  void businessAndStatutoryBooksAreGroupedSeparately() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");

    createBook(token, entityId, bookInput("BIZ_" + tag, "CNY"));

    var statutory = new java.util.LinkedHashMap<String, Object>(bookInput("TAX_" + tag, "CNY"));
    statutory.put("bookType", "BANK");
    statutory.put("bookCategory", "STATUTORY");
    statutory.put("chartOfAccountsSource", "LOCAL");
    createBook(token, entityId, statutory);

    var groups =
        request(token, "GET", "/identity/legal-entities/" + entityId + "/ledger-books", null, 200);
    assertThat(groups.get("businessBooks")).hasSize(1);
    assertThat(groups.get("statutoryBooks")).hasSize(1);
    assertThat(groups.get("statutoryBooks").get(0).get("code").asText()).isEqualTo("TAX_" + tag);
    assertThat(groups.get("statutoryBooks").get(0).get("chartOfAccountsSource").asText())
        .isEqualTo("LOCAL");
    assertThat(groups.get("hasDefault").asBoolean()).isFalse();

    // 分类非法 → 400，不静默兜底
    var bad = new java.util.LinkedHashMap<String, Object>(bookInput("BAD_" + tag, "CNY"));
    bad.put("bookCategory", "WHATEVER");
    request(token, "POST", "/identity/legal-entities/" + entityId + "/accounting-books", bad, 400);
  }

  // ------------------------------------------------------------------
  // 默认账簿
  // ------------------------------------------------------------------

  /**
   * 没有默认账簿时显式 409。
   *
   * <p>这是本条最容易被做漏的一条：静默挑一本会让同一笔业务在不同环境下落到不同的账上， 而且没人会发现 —— 所以宁可直接报错。
   */
  @Test
  void missingDefaultBookIsRejectedInsteadOfGuessed() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");
    UUID bookId = createBook(token, entityId, bookInput("BK_" + tag, "CNY"));

    request(token, "GET", "/identity/legal-entities/" + entityId + "/default-book", null, 409);

    // 取本法人自己的账簿 id：/accounting-books 不带过滤时返回全租户账簿，
    // 本模块的用例与 17.1.2.2 共用同一个租户，get(0) 会捞到别的用例建的账，
    // 设默认时就会因为"账簿不属于本法人"而误报 409。
    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entityId + "/default-book",
        Map.of("bookId", bookId.toString()),
        200);

    var resolved =
        request(token, "GET", "/identity/legal-entities/" + entityId + "/default-book", null, 200);
    assertThat(resolved.get("bookId").asText()).isEqualTo(bookId.toString());
    assertThat(resolved.get("baseCurrency").asText()).isEqualTo("CNY");
    assertThat(resolved.get("hasDefault").asBoolean()).isTrue();
  }

  /** 一个法人至多一本默认账簿：改选后旧的不再是默认，停用账簿不能当默认。 */
  @Test
  void onlyOneDefaultBookPerLegalEntityAndItMustBeActive() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");
    UUID first = createBook(token, entityId, bookInput("A_" + tag, "CNY"));

    var cash = new java.util.LinkedHashMap<String, Object>(bookInput("B_" + tag, "CNY"));
    cash.put("bookType", "CASH");
    UUID second = createBook(token, entityId, cash);

    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entityId + "/default-book",
        Map.of("bookId", first.toString()),
        200);
    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entityId + "/default-book",
        Map.of("bookId", second.toString()),
        200);

    var books =
        request(token, "GET", "/identity/accounting-books?legalEntityId=" + entityId, null, 200);
    long defaults =
        java.util.stream.StreamSupport.stream(books.spliterator(), false)
            .filter(b -> b.get("defaultBook").asBoolean())
            .count();
    assertThat(defaults).isEqualTo(1);
    assertThat(find(books, "B_" + tag).get("defaultBook").asBoolean()).isTrue();
    assertThat(find(books, "A_" + tag).get("defaultBook").asBoolean()).isFalse();

    // 跨法子的账簿不能被设成这个法人的默认账簿
    UUID otherEntity = entity(token, "LE2_" + tag, "CNY");
    UUID otherBook = createBook(token, otherEntity, bookInput("OTH_" + tag, "CNY"));
    request(
        token,
        "PUT",
        "/identity/legal-entities/" + entityId + "/default-book",
        Map.of("bookId", otherBook.toString()),
        409);

    // 停用账簿不能当默认账簿
    var suspended = new java.util.LinkedHashMap<String, Object>(bookInput("C_" + tag, "CNY"));
    suspended.put("bookType", "BANK");
    suspended.put("status", "SUSPENDED");
    suspended.put("defaultBook", true);
    request(
        token,
        "POST",
        "/identity/legal-entities/" + entityId + "/accounting-books",
        suspended,
        409);
  }

  // ------------------------------------------------------------------
  // 跨账簿不混记
  // ------------------------------------------------------------------

  /**
   * 同一笔业务事实只能在归属账簿记一次 —— 蓝图标粗的那句硬约束。
   *
   * <p>第一本账登记成功后，第二本账登记同一笔事实必须被数据库拒绝，且错误信息要指明 事实已经在哪本账上（调用方真正需要知道的是"我该找谁"）。解除归属后可以改记到另一本账，
   * 这才是"可纠错"而不是变成死账。
   */
  @Test
  void theSameBusinessFactCannotBeRecordedInTwoBooks() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");
    UUID cny = createBook(token, entityId, bookInput("CNY_" + tag, "CNY"));
    UUID usd = createBook(token, entityId, crossCurrencyBook("USD_" + tag, "USD"));
    UUID factRef = UUID.randomUUID();

    var withAmount = factInput("PURCHASE_ORDER", factRef);
    withAmount.put("amount", "1234.56");
    request(
        token, "POST", "/identity/accounting-books/" + cny + "/fact-assignments", withAmount, 200);

    // 第二本账登记同一笔事实 → 409，且消息指向原账簿
    var mixed =
        conflictReason(
            token,
            "/identity/accounting-books/" + usd + "/fact-assignments",
            factInput("PURCHASE_ORDER", factRef));
    assertThat(mixed).contains("跨账簿不混记").contains("CNY_" + tag);

    // 金额按 BigDecimal 往返，不能变成浮点尾巴
    var assignments =
        request(token, "GET", "/identity/accounting-books/" + cny + "/fact-assignments", null, 200);
    assertThat(assignments).hasSize(1);
    assertThat(assignments.get(0).get("factRef").asText()).isEqualTo(factRef.toString());
    assertThat(new BigDecimal(assignments.get(0).get("amount").asText()))
        .isEqualByComparingTo(new BigDecimal("1234.56"));
    // 未显式指定币种时按该账的本位币记，归属登记要能回答"这笔按哪个币记"
    assertThat(assignments.get(0).get("currency").asText()).isEqualTo("CNY");
    assertThat(assignments.get(0).get("baseCurrency").asText()).isEqualTo("CNY");

    // 换一本账重新登记仍然被拒 —— 解除归属前事实是"已认领"的
    request(
        token,
        "POST",
        "/identity/accounting-books/" + usd + "/fact-assignments",
        factInput("PURCHASE_ORDER", factRef),
        409);

    // 解除归属后可改记到另一本账
    UUID assignmentId = UUID.fromString(assignments.get(0).get("id").asText());
    request(
        token,
        "DELETE",
        "/identity/accounting-books/" + cny + "/fact-assignments/" + assignmentId,
        null,
        200);
    request(
        token,
        "POST",
        "/identity/accounting-books/" + usd + "/fact-assignments",
        factInput("PURCHASE_ORDER", factRef),
        200);

    var moved =
        request(token, "GET", "/identity/accounting-books/" + usd + "/fact-assignments", null, 200);
    assertThat(moved).hasSize(1);
    assertThat(moved.get(0).get("bookId").asText()).isEqualTo(usd.toString());
    assertThat(moved.get(0).get("currency").asText()).isEqualTo("USD");
    assertThat(
            request(
                    token,
                    "GET",
                    "/identity/accounting-books/" + cny + "/fact-assignments",
                    null,
                    200)
                .toString())
        .doesNotContain(factRef.toString());

    // 负金额与非法来源域 → 400，不静默接受
    var negative = factInput("PURCHASE_ORDER", UUID.randomUUID());
    negative.put("amount", "-1");
    request(
        token, "POST", "/identity/accounting-books/" + usd + "/fact-assignments", negative, 400);
    var lowercaseSource = factInput("purchase_order", UUID.randomUUID());
    request(
        token,
        "POST",
        "/identity/accounting-books/" + usd + "/fact-assignments",
        lowercaseSource,
        400);
  }

  /** 归属登记的时间与币种字段都按真实值落库并读回。 */
  @Test
  void factAssignmentKeepsItsTimestampAndCurrency() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");
    UUID book = createBook(token, entityId, bookInput("BK_" + tag, "CNY"));
    UUID factRef = UUID.randomUUID();

    OffsetDateTime before = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);
    OffsetDateTime after = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);
    var input = factInput("SALES_ORDER", factRef);
    input.put("currency", "JPY");
    request(token, "POST", "/identity/accounting-books/" + book + "/fact-assignments", input, 200);

    var row =
        request(
            token, "GET", "/identity/accounting-books/" + book + "/fact-assignments", null, 200);
    assertThat(row.get(0).get("currency").asText()).isEqualTo("JPY");
    // booked_at 是真 timestamptz：读出的**瞬时值**必须落在这个窗口内。
    // 判瞬时值而不是判偏移量——响应里的偏移取决于服务所在时区（本机 +08:00），
    // 拿偏移等于 0 当断言只会在换时区的机器上假红，而真正要防的 8 小时偏移
    // 已经被这个 ±1 分钟窗口抓住了。
    var bookedAt = OffsetDateTime.parse(row.get(0).get("bookedAt").asText());
    assertThat(bookedAt.toInstant()).isAfter(before.toInstant()).isBefore(after.toInstant());
  }

  // ------------------------------------------------------------------
  // 权限
  // ------------------------------------------------------------------

  /** 组织变更要求 OWNER：只有读权限的成员不能设默认账簿、也不能登记归属。 */
  @Test
  void mutationsRequireOwner() throws Exception {
    String token = login(), tag = suffix();
    UUID entityId = entity(token, "LE_" + tag, "CNY");
    UUID book = createBook(token, entityId, bookInput("BK_" + tag, "CNY"));

    var roleId =
        UUID.fromString(
            request(
                    token,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "LBR_" + tag,
                        "name",
                        "账簿只读角色",
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("identity.accounting_book.read")),
                    200)
                .asText());
    String username = "lb-member-" + tag;
    request(
        token,
        "POST",
        "/identity/members",
        memberInput(username, null, List.of(Map.of("roleId", roleId, "scopeMode", "ALL"))),
        200);
    String memberToken = loginUser(username);

    request(
        memberToken,
        "PUT",
        "/identity/legal-entities/" + entityId + "/default-book",
        Map.of("bookId", book.toString()),
        403);
    request(
        memberToken,
        "POST",
        "/identity/accounting-books/" + book + "/fact-assignments",
        factInput("SALES_ORDER", UUID.randomUUID()),
        403);
    // 读权限有，账簿清单读得到
    request(memberToken, "GET", "/identity/accounting-books", null, 200);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  /**
   * 租户隔离三断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
   *
   * <p>基类把连接池压到 1，所以每个请求必然复用同一条物理连接——租户设置泄漏到连接上， 第二个租户就会读到第一个租户的账簿与归属登记。
   */
  @Test
  void tenantIsolationHoldsAcrossConnectionReuse() throws Exception {
    String ownerToken = login(), tag = suffix();
    String childToken = childOwnerToken(ownerToken, tag);
    UUID parentId = UUID.fromString(currentTenantId(ownerToken));
    UUID childId = UUID.fromString(currentTenantId(childToken));
    assertThat(parentId).isNotEqualTo(childId);

    UUID parentEntity = entity(ownerToken, "LEP_" + tag, "CNY");
    UUID childEntity = entity(childToken, "LEC_" + tag, "CNY");
    String parentCode = "P_" + tag;
    String childCode = "C_" + tag;
    UUID parentBook = createBook(ownerToken, parentEntity, bookInput(parentCode, "CNY"));
    UUID childBook = createBook(childToken, childEntity, bookInput(childCode, "CNY"));

    UUID parentFact = UUID.randomUUID();
    UUID childFact = UUID.randomUUID();
    request(
        ownerToken,
        "POST",
        "/identity/accounting-books/" + parentBook + "/fact-assignments",
        factInput("SALES_ORDER", parentFact),
        200);
    request(
        childToken,
        "POST",
        "/identity/accounting-books/" + childBook + "/fact-assignments",
        factInput("SALES_ORDER", childFact),
        200);

    // 1) 两租户互不可见（交替 3 次，覆盖连接复用）
    for (int i = 0; i < 3; i++) {
      var parentBooks = request(ownerToken, "GET", "/identity/accounting-books", null, 200);
      assertThat(find(parentBooks, parentCode)).isNotNull();
      assertThat(find(parentBooks, childCode)).isNull();

      var childBooks = request(childToken, "GET", "/identity/accounting-books", null, 200);
      assertThat(find(childBooks, childCode)).isNotNull();
      assertThat(find(childBooks, parentCode)).isNull();

      var parentFacts =
          request(
              ownerToken,
              "GET",
              "/identity/accounting-books/" + parentBook + "/fact-assignments",
              null,
              200);
      assertThat(parentFacts).hasSize(1);
      assertThat(parentFacts.get(0).get("factRef").asText()).isEqualTo(parentFact.toString());
    }

    // 2) 伪造 X-Tenant-Id：即使写成一个真实存在的别家租户，也换不来它的账簿
    var forged =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/identity/accounting-books")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("X-Tenant-Id", childId.toString()))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).doesNotContain(childCode).doesNotContain(childId.toString());

    // 3) 连接归还后不串租户：直连表按当前租户设置过滤，池里只有一条连接
    var parentRows =
        tx().run(
                parentId,
                () -> sql.fetch("select id from book_fact_assignment").getValues("id", UUID.class));
    var childRows =
        tx().run(
                childId,
                () -> sql.fetch("select id from book_fact_assignment").getValues("id", UUID.class));
    assertThat(parentRows).isNotEmpty().doesNotContainAnyElementsOf(childRows);
    assertThat(childRows).isNotEmpty();
    // 没有 app.current_tenant 时一条都读不到：租户判定不在连接上残留
    assertThat(sql.fetch("select id from book_fact_assignment")).isEmpty();

    // 运行时角色绕不过 FORCE RLS
    var runtime =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(runtime.get("rolsuper", Boolean.class)).isFalse();
    assertThat(runtime.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                    + " where n.nspname='public' and relname='book_fact_assignment'"
                    + " and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
  }
}
