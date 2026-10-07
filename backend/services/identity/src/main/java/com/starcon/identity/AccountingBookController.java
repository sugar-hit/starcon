package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.1.2.2 本位币与账簿。
 *
 * <p>账簿挂在**法人**上而不是租户上：一个租户可以有多个法人，每个法人都有自己的记账基准。
 * 本位币同样归法人——租户的默认币种只是界面展示默认，两者不能混（实体目录 §5）。
 * 所以这里读出的 {@code baseCurrency} 必须等于 {@code legalEntityCurrency}，
 * 两者不一致时 {@code currencyAligned} 为 false：真相源在法人档案，本表只是落地副本。
 *
 * <p>独立成 {@code AccountingBookController} 而不是并进 {@code AdministrationController}：
 * 17.1.2.1 的法人档案与本模块是两个 L4 单元，同一个文件里各自生长会让两侧的改动
 * 在合并时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class AccountingBookController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public AccountingBookController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 账簿配置记录。
   *
   * <p>三块职责各有一个真实读出口：本位币（{@code baseCurrency} 与法人的 {@code
   * legalEntityCurrency} 并列给出）、记账规则（金额与数量各自的精度与舍入）、
   * 期间设置（粒度、起始月、首期起点，以及按规则算出的当前会计年度区间）。
   *
   * <p>{@code fiscalYearStart}/{@code fiscalYearEnd} 是**按本账期间设置算出来的**当前会计年度
   * 边界，不是存储字段：它让"这套期间设置是否合理"在列表里就能读出来，而不必等到 12.5
   * 生成期间行才发现起始月配错。
   */
  public record AccountingBookView(
      UUID id,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String code,
      String name,
      String bookType,
      String baseCurrency,
      String legalEntityCurrency,
      boolean currencyAligned,
      int amountScale,
      String amountRounding,
      int quantityScale,
      String quantityRounding,
      String periodType,
      int fiscalYearStartMonth,
      OffsetDateTime firstPeriodStart,
      OffsetDateTime baseCurrencyChangedAt,
      String currentFiscalYear,
      String fiscalYearStart,
      String fiscalYearEnd,
      String status,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      // ---- 17.6.1.1 账簿与本位币：跨币种多账簿 ----
      String bookCategory,
      String baseCurrencyMode,
      String chartOfAccountsSource,
      boolean defaultBook) {}

  /**
   * 账簿配置输入。
   *
   * <p>{@code code} 在法人内唯一而不是租户内唯一：账簿编码是"这个法人这本账"的编号，
   * 跨法人同码是正常的（每家法人都从 BK-001 开始编）。
   */
  public record AccountingBookInput(
      @NotBlank
          @Pattern(
              regexp = "[A-Z0-9_-]{1,64}",
              message = "账簿编码只允许大写字母数字下划线中划线")
          String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = "BOOK|CASH|BANK", message = "账簿类型只能是总账、现金账簿或银行账簿")
          String bookType,
      @NotBlank
          @Pattern(regexp = "[A-Z]{3}", message = "本位币需为三位大写币种代码")
          String baseCurrency,
      @Min(0) @Max(6) Integer amountScale,
      @Pattern(regexp = "HALF_UP|HALF_EVEN|DOWN|UP") String amountRounding,
      @Min(0) @Max(6) Integer quantityScale,
      @Pattern(regexp = "HALF_UP|HALF_EVEN|DOWN|UP") String quantityRounding,
      @NotNull @Pattern(regexp = "MONTH|WEEK|QUARTER") String periodType,
      @Min(1) @Max(12) Integer fiscalYearStartMonth,
      @NotNull OffsetDateTime firstPeriodStart,
      @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
      // ---- 17.6.1.1 新增入参，全部可空且缺省与 17.1.2.2 行为一致 ----
      @Pattern(regexp = "BUSINESS|STATUTORY", message = "账簿分类只能是业务账或法定账")
          String bookCategory,
      @Pattern(regexp = "INHERIT|OWN", message = "本位币来源只能是 INHERIT（继承法人）或 OWN（账簿自定）")
          String baseCurrencyMode,
      @Pattern(regexp = "STANDARD|LOCAL|GROUP", message = "科目体系来源只能是 STANDARD、LOCAL 或 GROUP")
          String chartOfAccountsSource,
      Boolean defaultBook) {}

  private static final String BOOK_READ = "identity.accounting_book.read";
  private static final String BOOK_CREATE = "identity.accounting_book.create";
  private static final String BOOK_UPDATE = "identity.accounting_book.update";
  private static final String BOOK_DELETE = "identity.accounting_book.delete";

  /**
   * 与 {@code AdministrationController.call} 同口径：解析会话 → 切到该会话的租户事务 →
   * 锁会话 → 二次校验租户未切换 → 校验权限码 → 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(
      String auth, String permission, boolean mutation, Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    return tenants.run(
        initial.tenantId(),
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", initial.tenantId().toString());
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          require(
              a.grants().stream()
                  .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "账簿配置变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /** 业务规则冲突（不是字段格式问题）：本位币归属与唯一约束，带可读原因。 */
  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC);
  }

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /**
   * 本租户全部账簿，可按法人过滤。
   *
   * <p>{@code legalEntityId} 缺失时返回全租户账簿：跨法人看账簿清单是配置页的常态
   * （"这个法人有几本账、哪本是总账"），过滤只是收窄，不是越权——租户边界仍然由 RLS 兜住。
   */
  @GetMapping("/accounting-books")
  public List<AccountingBookView> accountingBooks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID legalEntityId) {
    return call(
        auth,
        BOOK_READ,
        false,
        a ->
            sql.fetch(selectBooks() + (legalEntityId == null ? "" : " where b.legal_entity_id=?")
                    + " order by e.code,b.book_type='BOOK' desc,b.code",
                legalEntityId == null ? new Object[] {} : new Object[] {legalEntityId})
                .stream()
                .map(AccountingBookController::toView)
                .toList());
  }

  @PostMapping("/legal-entities/{legalEntityId}/accounting-books")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legalEntityId,
      @Valid @RequestBody AccountingBookInput input) {
    return call(
        auth,
        BOOK_CREATE,
        true,
        a -> {
          var entity = legalEntity(a.tenantId(), legalEntityId);
          String currency = input.baseCurrency().trim().toUpperCase();
          // 本位币归法人：INHERIT 模式下账簿上的币种必须与法人一致，否则"这个法人按哪个币记账"
          // 就有了两个答案；OWN 模式下账簿自己决定本位币（跨境多币种记账，见 17.6.1.1）。
          String currencyMode = currencyMode(input.baseCurrencyMode());
          checkBaseCurrency(currencyMode, currency, entity.get("base_currency", String.class));
          int startMonth = input.fiscalYearStartMonth() == null ? 1 : input.fiscalYearStartMonth();
          String periodType = input.periodType();
          checkPeriodAlignment(periodType, startMonth, input.firstPeriodStart());

          UUID id = Ids.next();
          try {
            sql.execute(
                "insert into accounting_book(id,tenant_id,legal_entity_id,code,name,book_type,"
                    + " base_currency,amount_scale,amount_rounding,quantity_scale,quantity_rounding,"
                    + " period_type,fiscal_year_start_month,first_period_start,base_currency_changed_at,status,"
                    + " book_category,base_currency_mode,chart_of_accounts_source,is_default)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?)",
                id,
                a.tenantId(),
                legalEntityId,
                input.code().trim(),
                input.name().trim(),
                input.bookType(),
                currency,
                input.amountScale() == null ? 2 : input.amountScale(),
                input.amountRounding() == null ? "HALF_UP" : input.amountRounding(),
                input.quantityScale() == null ? 3 : input.quantityScale(),
                input.quantityRounding() == null ? "HALF_UP" : input.quantityRounding(),
                periodType,
                startMonth,
                input.firstPeriodStart(),
                input.firstPeriodStart(),
                input.status(),
                bookCategory(input.bookCategory()),
                currencyMode,
                chartOfAccountsSource(input.chartOfAccountsSource()),
                input.defaultBook() != null && input.defaultBook());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw duplicate(e);
          }
          audit(a, auth, id, input.name().trim(), "AccountingBookCreated", null, snapshot(id));
          return id;
        });
  }

  @PutMapping("/accounting-books/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AccountingBookInput input) {
    call(
        auth,
        BOOK_UPDATE,
        true,
        a -> {
          var before = snapshot(id);
          check(before != null, "账簿不存在或不属于当前租户");
          // 法人 id 从表里直读，不从快照字符串再解析一次：快照是给人看的审计载荷，
          // 授权判定不该建立在它上面。
          var entity =
              legalEntity(
                  a.tenantId(),
                  sql.fetchOne("select legal_entity_id from accounting_book where tenant_id=? and id=?", a.tenantId(), id)
                      .get("legal_entity_id", UUID.class));
          String currency = input.baseCurrency().trim().toUpperCase();
          String currencyMode = currencyMode(input.baseCurrencyMode());
          checkBaseCurrency(currencyMode, currency, entity.get("base_currency", String.class));
          int startMonth = input.fiscalYearStartMonth() == null ? 1 : input.fiscalYearStartMonth();
          checkPeriodAlignment(input.periodType(), startMonth, input.firstPeriodStart());

          // 换币留痕：base_currency_changed_at 只在本位币真的变了才前进，
          // 否则改个名字也会把"从哪一刻起按哪个币记"的答案抹掉。
          int updated =
              sql.execute(
                  "update accounting_book set code=?,name=?,book_type=?,base_currency=?,"
                      + " amount_scale=?,amount_rounding=?,quantity_scale=?,quantity_rounding=?,"
                      + " period_type=?,fiscal_year_start_month=?,first_period_start=?::timestamptz,status=?,"
                      + " book_category=?,base_currency_mode=?,chart_of_accounts_source=?,is_default=?,"
                      + " base_currency_changed_at=case when base_currency=? then base_currency_changed_at"
                      + " else ?::timestamptz end,"
                      + " version=version+1,updated_at=now() where tenant_id=? and id=?",
                  input.code().trim(),
                  input.name().trim(),
                  input.bookType(),
                  currency,
                  input.amountScale() == null ? 2 : input.amountScale(),
                  input.amountRounding() == null ? "HALF_UP" : input.amountRounding(),
                  input.quantityScale() == null ? 3 : input.quantityScale(),
                  input.quantityRounding() == null ? "HALF_UP" : input.quantityRounding(),
                  input.periodType(),
                  startMonth,
                  input.firstPeriodStart(),
                  input.status(),
                  bookCategory(input.bookCategory()),
                  currencyMode,
                  chartOfAccountsSource(input.chartOfAccountsSource()),
                  input.defaultBook() != null && input.defaultBook(),
                  currency,
                  utcNow(),
                  a.tenantId(),
                  id);
          check(updated == 1, "账簿不存在或不属于当前租户");
          audit(
              a,
              auth,
              id,
              input.name().trim(),
              "AccountingBookUpdated",
              before,
              snapshot(id));
          return null;
        });
  }

  @DeleteMapping("/accounting-books/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    call(
        auth,
        BOOK_DELETE,
        true,
        a -> {
          var before = snapshot(id);
          check(before != null, "账簿不存在或不属于当前租户");
          int deleted = sql.execute("delete from accounting_book where tenant_id=? and id=?", a.tenantId(), id);
          check(deleted == 1, "账簿不存在或不属于当前租户");
          audit(a, auth, id, (String) before.get("name"), "AccountingBookDeleted", before, null);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 校验与快照
  // ------------------------------------------------------------------

  /**
   * 本位币必须等于所属法人的本位币——租户默认币种只是展示默认，两者不能混。
   *
   * <p>17.6.1.1 起这条只在 INHERIT 模式下生效：跨境常见同一主体同时用人民币与美元记账，
   * OWN 模式下账簿自己决定本位币（"海外账 USD"），再拿法人本位币去卡它就把多账簿堵死了。
   * 两种模式都在同一列上留痕，所以事后能回答"这本账的币是继承来的还是自定的"。
   */
  private void checkBaseCurrency(String mode, String currency, String entityCurrency) {
    if ("OWN".equals(mode)) return;
    if (!currency.equals(entityCurrency))
      reject(
          "账簿本位币必须等于所属法人的本位币（法人本位币为 "
              + entityCurrency
              + "）；本位币归法人，请先在法人档案中调整，而不是在本账上单独改。"
              + "若这本账确需用与法人不同的币（如海外账 USD），请把本位币来源改为 OWN。");
  }

  /** 17.6.1.1 新字段缺省值：与 17.1.2.2 已交付行为一致，不因为没传就改变既有语义。 */
  private static String currencyMode(String value) {
    return value == null || value.isBlank() ? "INHERIT" : value.trim();
  }

  private static String bookCategory(String value) {
    return value == null || value.isBlank() ? "BUSINESS" : value.trim();
  }

  private static String chartOfAccountsSource(String value) {
    return value == null || value.isBlank() ? "STANDARD" : value.trim();
  }

  /**
   * 首期起点必须落在会计年度的期界上。
   *
   * <p>MONTH 看 12、QUARTER 看 3、WEEK 不看（周不对齐月份）。迁移里的触发器是同一规则的
   * 数据库侧兜底，这里先拦一次是为了给出能照着改的中文原因，而不是抛一条 409。
   */
  private void checkPeriodAlignment(String periodType, int startMonth, OffsetDateTime firstPeriodStart) {
    if ("WEEK".equals(periodType)) return;
    int step = "QUARTER".equals(periodType) ? 3 : 12;
    int month = firstPeriodStart.atZoneSameInstant(ZoneOffset.UTC).getMonthValue();
    if (Math.floorMod(month - startMonth, step) != 0)
      check(
          false,
          "首期起点月份（"
              + month
              + "）与会计年度起始月（"
              + startMonth
              + "）在"
              + ("QUARTER".equals(periodType) ? "季度" : "月度")
              + "期间粒度下不对齐，请调整其一");
  }

  /**
   * 唯一约束冲突翻译成能照着改的原因：一个法人只能一本总账，编码在法人内唯一。
   *
   * <p>读 PG 报错文本里的约束名而不是靠异常类型猜：部分唯一索引与普通唯一约束抛出的是
   * 同一个 {@code DataIntegrityViolationException}，不区分就会把"第二本总账"报成"编码重复"。
   */
  private RuntimeException duplicate(Exception e) {
    String detail = String.valueOf(e.getMessage() == null ? "" : e.getMessage());
    if (detail.contains("accounting_book_main"))
      return IdentityController.error(
          HttpStatus.CONFLICT,
          "该法人在该本位币下已有一本总账（BOOK）；另一本请换一个本位币（如海外账 USD 走 OWN 模式）"
              + "或改用现金账簿/银行账簿");
    if (detail.contains("accounting_book_default"))
      return IdentityController.error(
          HttpStatus.CONFLICT, "该法人已有默认账簿，请先取消原默认账簿或改选其他账簿作为默认账簿");
    // 默认账簿必须启用：触发器抛的 23514 别被误报成"编码重复"，那会把人引向错误方向
    if (detail.contains("默认账簿必须处于启用状态"))
      return IdentityController.error(
          HttpStatus.CONFLICT, "默认账簿必须处于启用状态，请先启用该账簿或改选其他账簿作为默认账簿");
    return IdentityController.error(HttpStatus.CONFLICT, "该法人下已存在同编码的账簿，请更换编码");
  }

  private Record legalEntity(UUID tenantId, UUID id) {
    var row = sql.fetchOne("select * from legal_entity where tenant_id=? and id=?", tenantId, id);
    if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "法人不存在或不属于当前租户");
    return row;
  }

  /** 账簿快照：记账规则（精度、舍入、期间设置）改动必须可追溯。 */
  private Map<String, Object> snapshot(UUID id) {
    var row = sql.fetchOne("select * from accounting_book where id=?", id);
    if (row == null) return null;
    var snap = new java.util.LinkedHashMap<String, Object>();
    snap.put("legalEntityId", str(row.get("legal_entity_id", UUID.class)));
    snap.put("code", row.get("code", String.class));
    snap.put("name", row.get("name", String.class));
    snap.put("bookType", row.get("book_type", String.class));
    snap.put("baseCurrency", row.get("base_currency", String.class));
    snap.put("amountScale", row.get("amount_scale", Integer.class));
    snap.put("amountRounding", row.get("amount_rounding", String.class));
    snap.put("quantityScale", row.get("quantity_scale", Integer.class));
    snap.put("quantityRounding", row.get("quantity_rounding", String.class));
    snap.put("periodType", row.get("period_type", String.class));
    snap.put("fiscalYearStartMonth", row.get("fiscal_year_start_month", Integer.class));
    snap.put("firstPeriodStart", str(row.get("first_period_start", OffsetDateTime.class)));
    snap.put("baseCurrencyChangedAt", str(row.get("base_currency_changed_at", OffsetDateTime.class)));
    snap.put("status", row.get("status", String.class));
    snap.put("bookCategory", row.get("book_category", String.class));
    snap.put("baseCurrencyMode", row.get("base_currency_mode", String.class));
    snap.put("chartOfAccountsSource", row.get("chart_of_accounts_source", String.class));
    snap.put("defaultBook", row.get("is_default", Boolean.class) != null && row.get("is_default", Boolean.class));
    snap.put("version", row.get("version", Long.class));
    return snap;
  }

  private void audit(
      IdentityController.Access a,
      String auth,
      UUID id,
      String label,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    audit.record(a, auth, "identity.accounting_book", id, label, action, before, after);
    sql.execute(
        "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        id,
        action);
  }

  private static String selectBooks() {
    return "select b.id,b.code,b.name,b.book_type,b.base_currency,b.amount_scale,b.amount_rounding,"
        + " b.quantity_scale,b.quantity_rounding,b.period_type,b.fiscal_year_start_month,"
        + " b.first_period_start,b.base_currency_changed_at,b.status,b.version,b.created_at,b.updated_at,"
        + " b.book_category,b.base_currency_mode,b.chart_of_accounts_source,b.is_default,"
        + " e.id as legal_entity_id,e.code as legal_entity_code,e.legal_name as legal_entity_name,"
        + " e.base_currency as legal_entity_currency"
        + " from accounting_book b"
        + " join legal_entity e on e.tenant_id=b.tenant_id and e.id=b.legal_entity_id";
  }

  private static AccountingBookView toView(Record r) {
    String bookCurrency = r.get("base_currency", String.class);
    String entityCurrency = r.get("legal_entity_currency", String.class);
    int startMonth = r.get("fiscal_year_start_month", Integer.class);
    String periodType = r.get("period_type", String.class);
    var span = fiscalYear(periodType, startMonth, utcNow());
    return new AccountingBookView(
        r.get("id", UUID.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("book_type", String.class),
        bookCurrency,
        entityCurrency,
        bookCurrency.equals(entityCurrency),
        r.get("amount_scale", Integer.class),
        r.get("amount_rounding", String.class),
        r.get("quantity_scale", Integer.class),
        r.get("quantity_rounding", String.class),
        periodType,
        startMonth,
        r.get("first_period_start", OffsetDateTime.class),
        r.get("base_currency_changed_at", OffsetDateTime.class),
        span.year(),
        span.start(),
        span.end(),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("book_category", String.class),
        r.get("base_currency_mode", String.class),
        r.get("chart_of_accounts_source", String.class),
        r.get("is_default", Boolean.class) != null && r.get("is_default", Boolean.class));
  }

  private record FiscalYearSpan(String year, String start, String end) {}

  /**
   * 按本账的期间设置算出当前会计年度区间。
   *
   * <p>月/季粒度按起始月切：起始月 4 时，2026-05 属于 2026 财年（2026-04-01 起）。
   * 周粒度下起始月无意义，改用 ISO 周历年——周不对齐月份，硬套起始月会算出不存在的边界。
   */
  private static FiscalYearSpan fiscalYear(String periodType, int startMonth, OffsetDateTime now) {
    if ("WEEK".equals(periodType)) {
      var iso = WeekFields.ISO;
      int year = now.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().get(iso.weekBasedYear());
      LocalDate start = LocalDate.of(year, 1, 4).with(iso.dayOfWeek(), 1);
      LocalDate end = LocalDate.of(year, 12, 28).with(iso.dayOfWeek(), 7);
      return new FiscalYearSpan(String.valueOf(year), start.toString(), end.toString());
    }
    var today = now.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    int year = today.getMonthValue() >= startMonth ? today.getYear() : today.getYear() - 1;
    LocalDate start = LocalDate.of(year, startMonth, 1);
    LocalDate end = start.plusYears(1).minusDays(1);
    return new FiscalYearSpan(String.valueOf(year), start.toString(), end.toString());
  }
}