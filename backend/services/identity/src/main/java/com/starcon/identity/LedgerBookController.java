package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.6.1.1 账簿与本位币（D6）。
 *
 * <p>17.1.2.2 已经把"账簿是什么"交付了（挂法人、本位币副本、记账规则、期间设置），
 * 本控制器只补齐跨境多账簿下真正缺的那三件事，每件都有一个可读的读出口：
 *
 * <ul>
 *   <li><b>默认账簿</b> —— 一个法人一本默认账簿。缺默认时**显式 409**，不静默挑一本：
 *       单据落账的落点必须由人指定，"系统随便给你一本"会让同一笔业务在不同环境下落到不同的账。
 *   <li><b>跨账簿不混记</b> —— {@code book_fact_assignment} 把"同一笔业务事实只在归属账簿记一次"
 *       落成数据库唯一约束（租户 + 来源域 + 事实 ID 全局只能有一行归属）。第二本账再登记同一笔事实
 *       会被数据库拒掉，这里翻译成"该业务事实已归属账簿 X"。业务事实本身的生成属于下游卷 12，
 *       本模块只登记归属，不越界实现它们的记账逻辑。
 *   <li><b>业务账 / 法定账与跨本位币</b> —— 分类与本位币来源写在 {@code accounting_book} 上
 *       （见 {@link AccountingBookController}），本控制器负责按法人把账簿按分类归拢并读出。
 * </ul>
 *
 * <p>独立成 {@code LedgerBookController}：17.1.2.2 已经明确把两个 L4 单元分文件，
 * 同一文件各自生长会在并入时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class LedgerBookController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public LedgerBookController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  private static final String BOOK_READ = "identity.accounting_book.read";
  private static final String BOOK_UPDATE = "identity.accounting_book.update";
  private static final String BOOK_DELETE = "identity.accounting_book.delete";

  /** 默认账簿读出口：账簿基础信息 + 该法人是否有默认账簿，缺默认由调用方显式决定行为。 */
  public record DefaultBookView(UUID bookId, String bookCode, String bookName, String bookCategory,
      String baseCurrency, String baseCurrencyMode, UUID legalEntityId, boolean hasDefault) {}

  /**
   * 业务事实归属账簿的读出口。
   *
   * <p>{@code amount} 用 {@code BigDecimal} + NUMERIC，禁浮点；{@code currency} 是该事实实际
   * 记账币，归属登记要能回答"这笔按哪本账的哪个币记"，否则汇算时无从判断该用哪本账的汇率。
   */
  public record FactAssignmentView(UUID id, UUID bookId, String bookCode, String bookName,
      String bookCategory, String factSource, UUID factRef, BigDecimal amount, String currency,
      String baseCurrency, OffsetDateTime bookedAt, OffsetDateTime createdAt) {}

  /** 设定默认账簿入参。{@code bookId} 必须属于该法人且处于启用状态。 */
  public record DefaultBookInput(@NotNull UUID bookId) {}

  /**
   * 业务事实归属入参。
   *
   * <p>{@code factSource} 不做闭集枚举：订单 / 采购单 / 供应商账单这些来源域由下游卷 12 约定，
   * identity 只校验格式并登记归属，不替 finance 定义它的业务词汇表。这里要求大写，
   * 是为了让唯一约束的冲突信息可读（"PURCHASE_ORDER/xxx 已归属账簿 Y"）。
   */
  public record FactAssignmentInput(
      @NotBlank @Size(max = 64)
          @Pattern(regexp = "[A-Z0-9_]+", message = "业务事实来源域只允许大写字母数字与下划线")
              String factSource,
      @NotNull UUID factRef,
      BigDecimal amount,
      @Pattern(regexp = "[A-Z]{3}", message = "记账币种需为三位大写币种代码") String currency) {}

  /**
   * 与 {@code AccountingBookController.call} 同口径：解析会话 → 切到该会话的租户事务 →
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

  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private static RuntimeException conflict(String message) {
    return IdentityController.error(HttpStatus.CONFLICT, message);
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
   * 读某法人的账簿分组：业务账与法定账各自成列，附本位币与是否默认。
   *
   * <p>一个法人同时存在人民币业务账与美元海外账时，这个分组就是"按账簿区分业务流水与法定账"
   * 的界面依据；两本账本位币不同时都能看到，不做静默合并。
   */
  @GetMapping("/legal-entities/{legalEntityId}/ledger-books")
  public LedgerBookGroups ledgerBooks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legalEntityId) {
    return call(
        auth,
        BOOK_READ,
        false,
        a -> {
          requireLegalEntity(a.tenantId(), legalEntityId);
          var rows =
              sql.fetch(
                  "select b.id,b.code,b.name,b.book_category,b.base_currency,b.base_currency_mode,"
                      + " b.status,b.is_default,b.chart_of_accounts_source"
                      + " from accounting_book b where b.tenant_id=? and b.legal_entity_id=?"
                      + " order by b.is_default desc,b.book_category,b.code",
                  a.tenantId(),
                  legalEntityId);
          List<LedgerBookItem> books =
              rows.stream()
                  .map(LedgerBookController::toItem)
                  .toList();
          boolean hasDefault =
              rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("is_default", Boolean.class)));
          return new LedgerBookGroups(
              legalEntityId, hasDefault, books.stream().filter(b -> "BUSINESS".equals(b.bookCategory())).toList(),
              books.stream().filter(b -> "STATUTORY".equals(b.bookCategory())).toList());
        });
  }

  public record LedgerBookItem(UUID id, String code, String name, String bookCategory,
      String baseCurrency, String baseCurrencyMode, String chartOfAccountsSource, String status,
      boolean defaultBook) {}

  public record LedgerBookGroups(UUID legalEntityId, boolean hasDefault,
      List<LedgerBookItem> businessBooks, List<LedgerBookItem> statutoryBooks) {}

  private static LedgerBookItem toItem(Record r) {
    return new LedgerBookItem(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("book_category", String.class),
        r.get("base_currency", String.class),
        r.get("base_currency_mode", String.class),
        r.get("chart_of_accounts_source", String.class),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("is_default", Boolean.class)));
  }

  /**
   * 解析某法人的默认账簿。
   *
   * <p>**没有默认账簿时显式 409**，不返回"随便一本"：单据落账的落点必须是人指定的，
   * 静默挑一本会让同一笔业务在不同环境下落到不同的账上，而且没人会发现。
   */
  @GetMapping("/legal-entities/{legalEntityId}/default-book")
  public DefaultBookView defaultBook(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legalEntityId) {
    return call(
        auth,
        BOOK_READ,
        false,
        a -> {
          requireLegalEntity(a.tenantId(), legalEntityId);
          Record row =
              sql.fetchOne(
                  "select id,code,name,book_category,base_currency,base_currency_mode,legal_entity_id"
                      + " from accounting_book where tenant_id=? and legal_entity_id=? and is_default",
                  a.tenantId(),
                  legalEntityId);
          // 显式 throw 而不是走 reject()：reject 返回 void，控制流分析看不出它一定抛出，
          // row 会被当成可能为 null，后面 row.get(...) 反而编不过。
          if (row == null)
            throw conflict(
                "该法人尚未设置默认账簿；请显式指定归属账簿，或先为该法人设置一本默认账簿后再落账");
          // 逐字段取到显式类型的局部变量再装配：直接把这些 row.get(...) 塞进 record 构造器
          // 会让泛型推断变量跨实参传递，编不过；先落到有明确目标类型的局部变量上就干净了。
          UUID bookId = row.get("id", UUID.class);
          String code = row.get("code", String.class);
          String name = row.get("name", String.class);
          String category = row.get("book_category", String.class);
          String baseCurrency = row.get("base_currency", String.class);
          String currencyMode = row.get("base_currency_mode", String.class);
          return new DefaultBookView(
              bookId, code, name, category, baseCurrency, currencyMode, legalEntityId, true);
        });
  }

  /**
   * 设定某法人的默认账簿。
   *
   * <p>先清掉同法人的旧默认再置新的，在同一事务内完成：数据库上的部分唯一索引
   * {@code accounting_book_default} 会兜住并发，但先清后置才能让并发请求里"最后一个到的人赢"
   * 而不是两个人都撞 409。
   */
  @PutMapping("/legal-entities/{legalEntityId}/default-book")
  public void setDefaultBook(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legalEntityId,
      @Valid @RequestBody DefaultBookInput input) {
    call(
        auth,
        BOOK_UPDATE,
        true,
        a -> {
          requireLegalEntity(a.tenantId(), legalEntityId);
          var book =
              sql.fetchOne(
                  "select id,code,name,status,legal_entity_id from accounting_book"
                      + " where tenant_id=? and id=?",
                  a.tenantId(),
                  input.bookId());
          if (book == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "账簿不存在或不属于当前租户");
          if (!book.get("legal_entity_id", UUID.class).equals(legalEntityId))
            reject("该账簿不属于本法人，无法设为本法人的默认账簿");
          if (!"ACTIVE".equals(book.get("status", String.class)))
            reject("停用账簿不能作为默认账簿，请先启用该账簿或改选其他账簿");

          sql.execute(
              "update accounting_book set is_default=(id=?),version=version+1,updated_at=now()"
                  + " where tenant_id=? and legal_entity_id=?",
              input.bookId(),
              a.tenantId(),
              legalEntityId);
          audit(a, auth, input.bookId(), (String) book.get("name"), "DefaultBookSet", null, null);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 跨账簿不混记
  // ------------------------------------------------------------------

  /** 某本账已归属的业务事实清单。 */
  @GetMapping("/accounting-books/{bookId}/fact-assignments")
  public List<FactAssignmentView> factAssignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID bookId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String factSource) {
    return call(
        auth,
        BOOK_READ,
        false,
        a ->
            sql.fetch(
                selectAssignments() + " where a.tenant_id=? and a.book_id=?"
                    + (factSource == null || factSource.isBlank()
                        ? ""
                        : " and a.fact_source=?")
                    + " order by a.booked_at desc,a.fact_source,a.fact_ref",
                factSource == null || factSource.isBlank()
                    ? new Object[] {a.tenantId(), bookId}
                    : new Object[] {a.tenantId(), bookId, factSource.trim().toUpperCase()})
                .stream()
                .map(LedgerBookController::toAssignment)
                .toList());
  }

  /**
   * 登记一笔业务事实的归属账簿。
   *
   * <p>跨账簿不混记的落点：数据库上 {@code UNIQUE(tenant_id, fact_source, fact_ref)} 保证同一笔事实
   * 在整个租户只有一本账认领。重复登记时读 PG 报错里的约束名而不是靠异常类型猜，把"混记"
   * 报成"编码重复"会让人查错方向。
   */
  @PostMapping("/accounting-books/{bookId}/fact-assignments")
  public UUID assignFact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID bookId,
      @Valid @RequestBody FactAssignmentInput input) {
    return call(
        auth,
        BOOK_UPDATE,
        true,
        a -> {
          var book =
              sql.fetchOne(
                  "select id,code,name,status,legal_entity_id,base_currency from accounting_book"
                      + " where tenant_id=? and id=?",
                  a.tenantId(),
                  bookId);
          if (book == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "账簿不存在或不属于当前租户");
          if (!"ACTIVE".equals(book.get("status", String.class)))
            reject("停用账簿不能接收新的业务事实归属");

          String source = input.factSource().trim().toUpperCase();
          BigDecimal amount = input.amount();
          check(amount == null || amount.signum() >= 0, "金额不能为负");
          String currency =
              input.currency() == null || input.currency().isBlank()
                  ? book.get("base_currency", String.class)
                  : input.currency().trim().toUpperCase();

          UUID id = Ids.next();
          try {
            sql.execute(
                "insert into book_fact_assignment(id,tenant_id,book_id,fact_source,fact_ref,amount,"
                    + " currency,booked_at) values(?,?,?,?,?,?,?,?::timestamptz)",
                id,
                a.tenantId(),
                bookId,
                source,
                input.factRef(),
                amount,
                currency,
                utcNow());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw mixedBook(e, a.tenantId(), source, input.factRef());
          }
          audit(a, auth, id, source + "/" + input.factRef(), "BookFactAssigned", null, null);
          return id;
        });
  }

  /**
   * 解除业务事实的账簿归属。
   *
   * <p>存在是因为下游录入发现归属错了要能改：把归属登记删掉后该事实重新可被认领，
   * 下一本账就能登记它——这正是"不混记"可纠错而不是变成死账的原因。
   */
  @DeleteMapping("/accounting-books/{bookId}/fact-assignments/{assignmentId}")
  public void releaseFact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID bookId,
      @PathVariable UUID assignmentId) {
    call(
        auth,
        BOOK_DELETE,
        true,
        a -> {
          Record row =
              sql.fetchOne(
                  "select fact_source,fact_ref from book_fact_assignment"
                      + " where tenant_id=? and id=? and book_id=?",
                  a.tenantId(),
                  assignmentId,
                  bookId);
          if (row == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "该归属登记不存在，或不属于这本账簿 / 当前租户");
          int deleted =
              sql.execute(
                  "delete from book_fact_assignment where tenant_id=? and id=? and book_id=?",
                  a.tenantId(),
                  assignmentId,
                  bookId);
          check(deleted == 1, "该归属登记不存在，或不属于这本账簿 / 当前租户");
          audit(
              a,
              auth,
              assignmentId,
              row.get("fact_source", String.class) + "/" + row.get("fact_ref", UUID.class),
              "BookFactReleased",
              null,
              null);
          return null;
        });
  }

  /**
   * 把唯一约束冲突翻译成"跨账簿混记"，并指出事实已经在哪本账上。
   *
   * <p>先查现有归属再报错：调用方真正需要知道的是"我该找谁"，而不是一个 409。
   */
  private RuntimeException mixedBook(Exception e, UUID tenantId, String source, UUID factRef) {
    String detail = String.valueOf(e.getMessage() == null ? "" : e.getMessage());
    if (detail.contains("book_fact_assignment_tenant_id_fact_source_fact_ref_key")
        || detail.contains("book_fact_assignment")) {
      var owner =
          sql.fetchOne(
              "select b.code,b.name,b.book_category from book_fact_assignment a"
                  + " join accounting_book b on b.tenant_id=a.tenant_id and b.id=a.book_id"
                  + " where a.tenant_id=? and a.fact_source=? and a.fact_ref=?",
              tenantId,
              source,
              factRef);
      if (owner != null)
        return IdentityController.error(
            HttpStatus.CONFLICT,
            "该业务事实（"
                + source
                + "/"
                + factRef
                + "）已归属账簿「"
                + owner.get("name", String.class)
                + "（"
                + owner.get("code", String.class)
                + "）」；同一笔业务事实只能在归属账簿记一次，跨账簿不混记。"
                + "如需改归属，请先在原账簿上解除归属。");
    }
    if (detail.contains("book_fact_assignment_book_id_fkey"))
      return IdentityController.error(HttpStatus.CONFLICT, "该账簿已被其他账簿引用或已不存在，无法登记归属");
    return IdentityController.error(HttpStatus.CONFLICT, "登记业务事实归属失败，请检查归属登记是否冲突");
  }

  private void requireLegalEntity(UUID tenantId, UUID legalEntityId) {
    Record row = sql.fetchOne("select id from legal_entity where tenant_id=? and id=?", tenantId, legalEntityId);
    if (row == null)
      throw IdentityController.error(HttpStatus.NOT_FOUND, "法人不存在或不属于当前租户");
  }

  private static String selectAssignments() {
    return "select a.id,a.book_id,a.fact_source,a.fact_ref,a.amount,a.currency,a.booked_at,a.created_at,"
        + " b.code as book_code,b.name as book_name,b.book_category,b.base_currency"
        + " from book_fact_assignment a"
        + " join accounting_book b on b.tenant_id=a.tenant_id and b.id=a.book_id";
  }

  private static FactAssignmentView toAssignment(Record r) {
    return new FactAssignmentView(
        r.get("id", UUID.class),
        r.get("book_id", UUID.class),
        r.get("book_code", String.class),
        r.get("book_name", String.class),
        r.get("book_category", String.class),
        r.get("fact_source", String.class),
        r.get("fact_ref", UUID.class),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("base_currency", String.class),
        r.get("booked_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
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
}