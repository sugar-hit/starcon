package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * 17.5.2.1 数据字典。
 *
 * <p>蓝图点名的三条硬要求在这里各有明确落点：
 *
 * <ul>
 *   <li><b>租户级自定义扩展</b>：{@code dict_catalog} 是平台随代码发布的字典目录 （全局码表，只读），{@code dict_item}
 *       是租户私有的字典项。目录不出租户、 项才出租户，租户往目录里加自己的条目就是「自定义扩展」。
 *   <li><b>不允许修改已被历史数据引用的编码</b>：{@code /dict-items/{id}} 允许改名称、 说明、排序，但 {@code itemCode}
 *       一旦被引用就拒改（409）。数据库层还有 {@code dict_item_update_guard()} 触发器兜底，不依赖调用方自觉。
 *   <li><b>被引用后只能停用不能删除</b>：本控制器**没有 DELETE 端点**， 停用走 {@code POST /dict-items/{id}/deactivate}、恢复走
 *       {@code POST /dict-items/{id}/reactivate}。删除防线不止一处： 控制器无 DELETE 端点、迁移不授予 {@code
 *       identity_app} DELETE、 {@code dict_item_usage.item_id} 外键 RESTRICT、{@code
 *       dict_item_delete_guard()} 触发器在数据库层挡掉一切 DELETE。
 * </ul>
 *
 * <p><b>引用统计不是常数</b>：{@code referenceCount} 与 {@code referencingSystems} 来自 {@code dict_item_usage}
 * 的 {@code left join + sum} 聚合，字典项从未被下游登记 引用时才是 0。下游经 {@code POST /dict-items/{id}/usage} 登记「我这边有 N
 * 条记录 用了这个字典项」，identity 不去查它们的库（跨服务读表是 AGENTS.md 明令禁止的）。
 *
 * <p>独立成 {@code DictionaryController} 而不并进 {@code BusinessParameterController}： 17.5.2.1 与 17.5.1.1
 * 是两个独立 L4 单元，约束完全不同（一个是分档生效值， 一个是禁删字典项），同文件各自生长会让两侧改动在合并时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class DictionaryController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public DictionaryController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // DTO
  // ------------------------------------------------------------------

  /**
   * 出厂字典目录。
   *
   * <p>{@code codeLocked} 是显式字段而不是由调用方猜：这决定了该字典下的字典项 被引用后编码能不能改，页面上要能直接看到「这类字典的编码是锁死的」。
   */
  public record DictCatalogView(
      String code,
      String name,
      String itemKind,
      String description,
      boolean codeLocked,
      String status,
      long itemCount,
      long activeItemCount) {}

  /**
   * 字典项 + 引用统计。
   *
   * <p>{@code referenceCount} 是各来源系统登记的引用条数之和， {@code referencingSystems} 是有登记记录的来源系统个数， {@code
   * referenced} 由「是否存在登记记录」判定——不是按当前计数是否大于 0。 一个字典项的计数归零只说明当下没人用它，历史单据里还留着它， 编码依然锁死、依然不能删。
   */
  public record DictItemView(
      UUID id,
      String dictCode,
      String dictName,
      String itemKind,
      String itemCode,
      String name,
      String description,
      int sortOrder,
      String status,
      long referenceCount,
      long referencingSystems,
      boolean referenced,
      boolean codeLocked,
      long version,
      String disabledReason,
      @Schema(nullable = true) OffsetDateTime disabledAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 引用台账一行：一个来源系统对某个字典项登记的引用条数。 */
  public record DictItemUsageView(
      UUID id,
      String sourceSystem,
      long usageCount,
      String note,
      OffsetDateTime firstReferencedAt,
      OffsetDateTime lastReferencedAt,
      OffsetDateTime updatedAt) {}

  /**
   * 变更历史一行。
   *
   * <p>停用/恢复额外记录 {@code beforeStatus}/{@code afterStatus} 与原因； 改名称与说明时记录前后名称，其余字段为空——每一行的含义由 action
   * 决定。
   */
  public record DictHistoryView(
      UUID id,
      String dictCode,
      UUID itemId,
      String itemCode,
      String itemName,
      String action,
      @Schema(nullable = true) String beforeName,
      @Schema(nullable = true) String beforeDescription,
      @Schema(nullable = true) String afterName,
      @Schema(nullable = true) String afterDescription,
      @Schema(nullable = true) String beforeStatus,
      @Schema(nullable = true) String afterStatus,
      String reason,
      @Schema(nullable = true) String actorName,
      OffsetDateTime changedAt) {}

  /**
   * 字典项输入。
   *
   * <p>{@code itemCode} 在新建时必填、更新时可空：更新时留空表示不改编码， 显式传一个不同的值则走「已被引用则 409」这条判定，而不是静默忽略。
   */
  public record DictItemInput(
      @NotBlank @Size(max = 64) String dictCode,
      @Size(max = 64) String itemCode,
      @NotBlank @Size(max = 100) String name,
      @Size(max = 300) String description,
      Integer sortOrder) {}

  /**
   * 停用/恢复输入。
   *
   * <p>停用必须带原因：事后无法解释「为什么这个字典项不能选了」。{@code status} 用 {@code ACTIVE|DISABLED} 的 Pattern 约束，与 {@code
   * BusinessParameterOverrideInput} 的 {@code scopeType} 同一写法；端点再各自校验方向（停用端点只收 DISABLED）。
   */
  public record DictItemStatusInput(
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @NotBlank @Size(max = 200) String reason) {}

  /** 引用登记输入：下游服务登记「我这边有 N 条记录用了这个字典项」。 */
  public record DictItemUsageInput(
      @NotBlank @Pattern(regexp = "[a-z_]{2,32}") String sourceSystem,
      long usageCount,
      @Size(max = 200) String note) {}

  private static final String DICT_READ = "identity.dictionary.read";
  private static final String DICT_CREATE = "identity.dictionary.create";
  private static final String DICT_UPDATE = "identity.dictionary.update";

  /**
   * 与 {@code BusinessParameterController.call} 同口径：解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码 →
   * 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(
      String auth,
      String permission,
      boolean mutation,
      Function<IdentityController.Access, T> work) {
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
                  .anyMatch(
                      g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "数据字典变更需由当前租户所有者执行");
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

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /** timestamptz 一律绑 {@link OffsetDateTime}；见 {@link AuditLog#utc} 关于 −28800s 的说明。 */
  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /**
   * 出厂字典目录 + 每个字典下的字典项计数。
   *
   * <p>全租户共用一份目录，不带租户上下文；计数来自本租户的字典项， 所以同一个目录在不同租户下看到的 itemCount 可以不同——这正是租户级扩展的体现。
   */
  @GetMapping("/dict-catalog")
  public List<DictCatalogView> catalog(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        DICT_READ,
        false,
        a ->
            sql.fetch(CATALOG_SQL, a.tenantId()).stream()
                .map(DictionaryController::toCatalog)
                .toList());
  }

  /**
   * 字典项列表，带真实引用统计。
   *
   * <p>可按字典、状态过滤，可按编码或名称搜索。不带过滤时返回本租户全部字典项， 因为配置页常态就是「这个字典里现在有哪些项」。
   *
   * <p>默认<b>包含</b>停用项：停用项要看得见才能判断「这个值还在用不用」， 把它藏起来等于让用户以为被删了。下拉选项场景请走 {@code
   * /dict-items/options/{dictCode}}，那里只给 ACTIVE。
   */
  @GetMapping("/dict-items")
  public List<DictItemView> items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dictCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search) {
    return call(
        auth,
        DICT_READ,
        false,
        a -> {
          var condition = new Condition(" where i.tenant_id=?");
          condition.bind(a.tenantId());
          if (dictCode != null && !dictCode.isBlank()) {
            catalogRow(dictCode.trim());
            condition.add(" and i.dict_code=?");
            condition.bind(dictCode.trim());
          }
          if (status != null && !status.isBlank()) {
            check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
            condition.add(" and i.status=?");
            condition.bind(status);
          }
          if (search != null && !search.isBlank()) {
            condition.add(" and (lower(i.item_code) like ? or lower(i.name) like ?)");
            var like = "%" + search.trim().toLowerCase() + "%";
            condition.bind(like);
            condition.bind(like);
          }
          return sql
              .fetch(ITEM_SELECT + condition.sql() + ITEM_ORDER, condition.bindings())
              .stream()
              .map(DictionaryController::toItem)
              .toList();
        });
  }

  /**
   * 下拉选项：某个字典下所有 ACTIVE 的字典项。
   *
   * <p>这是「下游 = 各业务系统下拉选项」的读出口：停用项不进这里， 所以停用一个字典项不会让新单据选不到——而历史单据仍能读回它（见 {@code /dict-items} 与
   * {@code /dict-items/{id}}）。
   *
   * <p>字典不存在或已下线返回 404，而不是空数组：空数组会被调用方渲染成 「这个字典没有可选项」，与「这个字典配错了」是两种完全不同的事故。
   */
  @GetMapping("/dict-items/options/{dictCode}")
  public List<DictItemView> options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String dictCode) {
    return call(
        auth,
        DICT_READ,
        false,
        a -> {
          var catalog = sql.fetchOne("select * from dict_catalog where code=?", dictCode.trim());
          // 404 而不是 400：调用方拼错字典编码与字典真的不存在，是「找不到」而不是「请求体不合法」。
          if (catalog == null)
            throw IdentityController.error(HttpStatus.NOT_FOUND, "字典 " + dictCode + " 不存在");
          check(
              catalog.get("status", String.class).equals("ACTIVE"),
              "字典 " + dictCode + " 已下线，不能再取选项");
          return sql
              .fetch(
                  ITEM_SELECT
                      + " where i.tenant_id=? and i.dict_code=? and i.status='ACTIVE'"
                      + ITEM_ORDER,
                  a.tenantId(),
                  dictCode.trim())
              .stream()
              .map(DictionaryController::toItem)
              .toList();
        });
  }

  /**
   * 单个字典项（含引用统计）。
   *
   * <p>停用项也读得到——这是历史单据能把字典项渲染成可读标签的前提。
   */
  @GetMapping("/dict-items/{id}")
  public DictItemView item(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        DICT_READ,
        false,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          // 404 而不是 403：跨租户拿到的 id 与真正不存在的 id 在这里不可区分，
          // 返回 403 会泄露「这个 id 存在」。
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          return toItem(row);
        });
  }

  /** 某个字典项的引用台账明细，按来源系统列出。 */
  @GetMapping("/dict-items/{id}/usage")
  public List<DictItemUsageView> usages(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        DICT_READ,
        false,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          return sql
              .fetch(
                  "select id,source_system,usage_count,note,first_referenced_at,last_referenced_at,"
                      + " updated_at from dict_item_usage where tenant_id=? and item_id=? order by source_system",
                  a.tenantId(),
                  id)
              .stream()
              .map(
                  r ->
                      new DictItemUsageView(
                          r.get("id", UUID.class),
                          r.get("source_system", String.class),
                          r.get("usage_count", Long.class),
                          r.get("note", String.class),
                          r.get("first_referenced_at", OffsetDateTime.class),
                          r.get("last_referenced_at", OffsetDateTime.class),
                          r.get("updated_at", OffsetDateTime.class)))
              .toList();
        });
  }

  /** 变更历史（只追加、不可改），可按字典与字典项过滤。 */
  @GetMapping("/dict-history")
  public List<DictHistoryView> history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dictCode,
      @RequestParam(required = false) UUID itemId) {
    return call(
        auth,
        DICT_READ,
        false,
        a -> {
          var condition = new Condition(" where h.tenant_id=?");
          condition.bind(a.tenantId());
          if (dictCode != null && !dictCode.isBlank()) {
            condition.add(" and h.dict_code=?");
            condition.bind(dictCode.trim());
          }
          if (itemId != null) {
            condition.add(" and h.item_id=?");
            condition.bind(itemId);
          }
          return sql
              .fetch(
                  "select h.id,h.dict_code,h.item_id,h.item_code,h.item_name,h.action,"
                      + " h.before_name,h.before_description,h.after_name,h.after_description,"
                      + " h.before_status,h.after_status,h.reason,h.actor_name,h.changed_at"
                      + " from dict_history h"
                      + condition.sql()
                      + " order by h.changed_at desc,h.id desc",
                  condition.bindings())
              .stream()
              .map(DictionaryController::toHistory)
              .toList();
        });
  }

  // ------------------------------------------------------------------
  // 写出口
  // ------------------------------------------------------------------

  /**
   * 新建字典项（租户级自定义扩展的主入口）。
   *
   * <p>同一字典下编码唯一：撞码返回 409 而不是覆盖——覆盖会让历史单据里 同一个编码在今天与昨天指向两个不同的东西。
   */
  @PostMapping("/dict-items")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DictItemInput input) {
    return call(
        auth,
        DICT_CREATE,
        true,
        a -> {
          check(input.itemCode() != null && !input.itemCode().isBlank(), "字典项编码不能为空");
          var catalog = catalogRow(input.dictCode());
          check(
              catalog.get("status", String.class).equals("ACTIVE"),
              "字典 " + input.dictCode() + " 已下线，不能再新增字典项");
          String code = input.itemCode().trim();
          checkCode(code);
          UUID id = Ids.next();
          try {
            // cast 刻意避开 values 末位：jOOQ 会把末位的 ? 按整体错位一格解析。
            sql.execute(
                "insert into dict_item(id,tenant_id,dict_code,item_code,name,description,sort_order,"
                    + " status,created_at,updated_at,created_by_membership_id)"
                    + " values(?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,?::timestamptz,?)",
                id,
                a.tenantId(),
                input.dictCode().trim(),
                code,
                input.name().trim(),
                note(input.description()),
                input.sortOrder() == null ? 0 : input.sortOrder(),
                utcNow(),
                utcNow(),
                a.membershipId());
          } catch (DataIntegrityViolationException e) {
            reject("字典 " + input.dictCode() + " 下已存在编码「" + code + "」，请直接编辑现有字典项");
          }
          // CREATED 的「变更前」为空，「变更后」就是这次新建的值——
          // 只记 after 不记 before 的历史读起来会像"这个字典项一直叫这个名字"。
          history(
              a,
              auth,
              id,
              input.dictCode().trim(),
              code,
              input.name().trim(),
              note(input.description()),
              "CREATED",
              null,
              null,
              input.name().trim(),
              note(input.description()),
              null,
              "ACTIVE",
              "新建字典项");
          audit.record(
              a,
              auth,
              "identity.dictionary",
              id,
              input.dictCode().trim() + " · " + code,
              "DictionaryItemCreated",
              null,
              itemSnapshot(id));
          return id;
        });
  }

  /**
   * 更新字典项。
   *
   * <p>名称、说明、排序随时可改；<b>编码只在未被引用时可改</b>。被引用后显式传新编码 返回 409，而不是静默忽略——静默忽略会让用户以为编码改成功了， 几天后在别处发现编码没变。
   *
   * <p>停用中的字典项可以改名称与说明（停用只是不能选，不是冻结）， 但改完仍保持停用状态，这里不偷偷把它放回 ACTIVE。
   */
  @PutMapping("/dict-items/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DictItemInput input) {
    call(
        auth,
        DICT_UPDATE,
        true,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          var usage = usageCount(a.tenantId(), id);
          String existingCode = row.get("item_code", String.class);
          String nextCode = existingCode;
          if (input.itemCode() != null && !input.itemCode().isBlank()) {
            nextCode = input.itemCode().trim();
            checkCode(nextCode);
            if (!nextCode.equals(existingCode) && usage > 0)
              reject(
                  "字典项「" + existingCode + "」已被历史数据引用（" + usage + " 个来源系统登记），不能修改编码；名称、说明、排序仍可修改");
          }
          // 字典归属不可改：换字典等于换一条记录，否则历史引用统计会被切成两半。
          check(
              input.dictCode().trim().equals(row.get("dict_code", String.class)),
              "不能把字典项移到另一个字典，请在新字典下新建");
          var name = input.name().trim();
          var description = note(input.description());
          try {
            sql.execute(
                "update dict_item set item_code=?,name=?,description=?,sort_order=?,"
                    + " version=version+1,updated_at=?::timestamptz where tenant_id=? and id=?",
                nextCode,
                name,
                description,
                input.sortOrder() == null
                    ? row.get("sort_order", Integer.class)
                    : input.sortOrder(),
                utcNow(),
                a.tenantId(),
                id);
          } catch (DataIntegrityViolationException e) {
            reject("字典 " + row.get("dict_code", String.class) + " 下已存在编码「" + nextCode + "」，请换一个编码");
          }
          if (!name.equals(row.get("name", String.class))
              || !description.equals(row.get("description", String.class))
              || !nextCode.equals(existingCode))
            history(
                a,
                auth,
                id,
                row.get("dict_code", String.class),
                nextCode,
                name,
                description,
                "UPDATED",
                row.get("name", String.class),
                row.get("description", String.class),
                name,
                description,
                row.get("status", String.class),
                row.get("status", String.class),
                "编辑字典项");
          audit.record(
              a,
              auth,
              "identity.dictionary",
              id,
              row.get("dict_code", String.class) + " · " + existingCode,
              "DictionaryItemUpdated",
              rowSnapshot(row),
              itemSnapshot(id));
          return null;
        });
  }

  /**
   * 停用字典项。
   *
   * <p>停用 = 不再出现在新单据的下拉里，历史单据照常能读回它并渲染成标签—— 这正是「不能删」的原因。停用必须带原因，且是幂等的：已停用的再停一次直接 409，
   * 而不是悄悄改一次时刻、让变更历史里出现两条看不出差别的记录。
   */
  @PostMapping("/dict-items/{id}/deactivate")
  public void deactivate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DictItemStatusInput input) {
    call(
        auth,
        DICT_UPDATE,
        true,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          // 方向先于状态判定：拿 ACTIVE 去调停用端点是请求本身写错了（400），
          // 与「这个字典项当前是什么状态」无关。先查状态会把调用方的笔误报成 409。
          check(input.status() == null || input.status().equals("DISABLED"), "停用端点只接受 DISABLED");
          if (!row.get("status", String.class).equals("ACTIVE"))
            reject("字典项「" + row.get("item_code", String.class) + "」已经是停用状态");
          String reason = requiredReason(input.reason());
          var usage = usageCount(a.tenantId(), id);
          sql.execute(
              "update dict_item set status='DISABLED',disabled_at=?::timestamptz,"
                  + " disabled_by_membership_id=?,disabled_reason=?,version=version+1,"
                  + " updated_at=?::timestamptz where tenant_id=? and id=?",
              utcNow(),
              a.membershipId(),
              reason,
              utcNow(),
              a.tenantId(),
              id);
          history(
              a,
              auth,
              id,
              row.get("dict_code", String.class),
              row.get("item_code", String.class),
              row.get("name", String.class),
              row.get("description", String.class),
              "DEACTIVATED",
              row.get("name", String.class),
              row.get("description", String.class),
              row.get("name", String.class),
              row.get("description", String.class),
              "ACTIVE",
              "DISABLED",
              reason);
          audit.record(
              a,
              auth,
              "identity.dictionary",
              id,
              row.get("dict_code", String.class) + " · " + row.get("item_code", String.class),
              "DictionaryItemDeactivated",
              rowSnapshot(row),
              Map.of(
                  "itemCode",
                  row.get("item_code", String.class),
                  "status",
                  "DISABLED",
                  "reason",
                  reason,
                  "referenceCount",
                  usage));
          return null;
        });
  }

  /**
   * 恢复字典项。
   *
   * <p>停用是可逆的：停用错了就恢复，而不是删掉重建——重建会换一个 id， 把已经登记的引用统计与变更历史一起割断。
   */
  @PostMapping("/dict-items/{id}/reactivate")
  public void reactivate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DictItemStatusInput input) {
    call(
        auth,
        DICT_UPDATE,
        true,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          // 方向先于状态判定，理由同上。
          check(input.status() == null || input.status().equals("ACTIVE"), "恢复端点只接受 ACTIVE");
          if (!row.get("status", String.class).equals("DISABLED"))
            reject("字典项「" + row.get("item_code", String.class) + "」当前不是停用状态");
          String reason = requiredReason(input.reason());
          sql.execute(
              "update dict_item set status='ACTIVE',disabled_at=null,"
                  + " disabled_by_membership_id=null,disabled_reason='',version=version+1,"
                  + " updated_at=?::timestamptz where tenant_id=? and id=?",
              utcNow(),
              a.tenantId(),
              id);
          history(
              a,
              auth,
              id,
              row.get("dict_code", String.class),
              row.get("item_code", String.class),
              row.get("name", String.class),
              row.get("description", String.class),
              "REACTIVATED",
              row.get("name", String.class),
              row.get("description", String.class),
              row.get("name", String.class),
              row.get("description", String.class),
              "DISABLED",
              "ACTIVE",
              reason);
          audit.record(
              a,
              auth,
              "identity.dictionary",
              id,
              row.get("dict_code", String.class) + " · " + row.get("item_code", String.class),
              "DictionaryItemReactivated",
              rowSnapshot(row),
              Map.of(
                  "itemCode",
                  row.get("item_code", String.class),
                  "status",
                  "ACTIVE",
                  "reason",
                  reason));
          return null;
        });
  }

  /**
   * 登记 / 更新引用计数。
   *
   * <p>下游各业务服务的表在各自库里，identity 不能查（跨服务读表是 AGENTS.md 明令禁止的），
   * 所以引用统计靠下游经这个端点登记。这也是「引用统计是真实数据而非写死数字」的来源： 计数来自下游的真实登记，字典项从未被登记时才是 0。
   *
   * <p>同一个「字典项 × 来源系统」只有一行，重复登记是覆盖而不是追加—— 追加会让同一个来源系统的计数累加成一个永远对不上的天文数字。 {@code
   * first_referenced_at} 保留首次登记时刻，所以统计能回答 「这个字典项最早从什么时候起被用过」。
   *
   * <p>停用中的字典项也允许登记引用：停用是配置决定，已经发生的历史引用是事实， 不能因为「现在不让选了」就假装它没被用过。
   */
  @PostMapping("/dict-items/{id}/usage")
  public void recordUsage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DictItemUsageInput input) {
    call(
        auth,
        DICT_UPDATE,
        true,
        a -> {
          var row = fetchItem(a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "字典项不存在或不属于当前租户");
          check(input.usageCount() >= 0, "引用条数不能为负数");
          String source = input.sourceSystem().trim();
          // upsert：first_referenced_at 用 min() 保住首次登记时刻，
          // 这样重复登记不会把「最早何时被引用」往后推。
          sql.execute(
              "insert into dict_item_usage(id,tenant_id,item_id,source_system,usage_count,"
                  + " first_referenced_at,last_referenced_at,note,updated_at)"
                  + " values(?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz)"
                  + " on conflict (tenant_id,item_id,source_system) do update set"
                  + " usage_count=excluded.usage_count,"
                  + " first_referenced_at=least(dict_item_usage.first_referenced_at,excluded.first_referenced_at),"
                  + " last_referenced_at=excluded.last_referenced_at,"
                  + " note=excluded.note,updated_at=excluded.updated_at",
              Ids.next(),
              a.tenantId(),
              id,
              source,
              input.usageCount(),
              utcNow(),
              utcNow(),
              note(input.note()),
              utcNow());
          audit.record(
              a,
              auth,
              "identity.dictionary",
              id,
              row.get("dict_code", String.class) + " · " + row.get("item_code", String.class),
              "DictionaryItemUsageRecorded",
              null,
              Map.of(
                  "sourceSystem", source,
                  "usageCount", input.usageCount(),
                  "totalReferenceCount", usageCount(a.tenantId(), id)));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // SQL 与映射
  // ------------------------------------------------------------------

  /**
   * 目录 + 字典项计数。
   *
   * <p>{@code count(i.id)} 在 {@code left join} 上聚合，所以没有字典项的目录也返回一行 （计数为 0），而不是从结果里消失——「这个字典一个项都没配」和
   * 「这个字典不存在」在页面上要能区分开。
   */
  private static final String CATALOG_SQL =
      "select c.code,c.name,c.item_kind,c.description,c.code_locked,c.status,"
          + " count(i.id) as item_count,"
          + " count(i.id) filter (where i.status='ACTIVE') as active_item_count"
          + " from dict_catalog c"
          + " left join dict_item i on i.dict_code=c.code and i.tenant_id=?"
          + " where c.status='ACTIVE' group by c.code,c.name,c.item_kind,c.description,c.code_locked,c.status"
          + " order by c.sort_order,c.code";

  /**
   * 字典项 + 引用统计的唯一查询入口。
   *
   * <p>引用统计是 {@code left join dict_item_usage} 后的 {@code sum}： 没有登记过时 {@code sum} 为 NULL，用 {@code
   * coalesce} 归 0。 {@code referencing_systems} 是 {@code count} ——「有几个来源系统登记过」，
   * 与「一共引用了多少条」是两件事，停用一个引用了 9000 条的字典项和 停用一个引用了 1 条的字典项，风险完全不同。
   *
   * <p>{@code referenced} 按「有没有登记记录」判定（{@code count(u.id)>0}）， 不是按 {@code usage_count} 是否大于
   * 0：计数归零只说明当下没人用它， 历史单据里还留着它，编码依然锁死、依然不能删。
   */
  private static final String ITEM_SELECT =
      "select i.id,i.dict_code,c.name as dict_name,c.item_kind,c.code_locked,i.item_code,i.name,"
          + " i.description,i.sort_order,i.status,i.version,i.disabled_reason,i.disabled_at,"
          + " i.created_at,i.updated_at,"
          + " coalesce(sum(u.usage_count),0) as reference_count,"
          + " count(u.id) as referencing_systems,"
          + " (count(u.id)>0) as referenced"
          + " from dict_item i"
          + " join dict_catalog c on c.code=i.dict_code"
          + " left join dict_item_usage u on u.item_id=i.id and u.tenant_id=i.tenant_id";

  private static final String ITEM_ORDER =
      " group by i.id,c.name,c.item_kind,c.code_locked"
          + " order by i.dict_code,i.sort_order,i.item_code";

  private Record fetchItem(UUID tenantId, UUID id) {
    return sql.fetchOne(ITEM_SELECT + " where i.tenant_id=? and i.id=?" + ITEM_ORDER, tenantId, id);
  }

  private static DictCatalogView toCatalog(Record r) {
    return new DictCatalogView(
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("item_kind", String.class),
        r.get("description", String.class),
        r.get("code_locked", Boolean.class),
        r.get("status", String.class),
        r.get("item_count", Long.class),
        r.get("active_item_count", Long.class));
  }

  private static DictItemView toItem(Record r) {
    return new DictItemView(
        r.get("id", UUID.class),
        r.get("dict_code", String.class),
        r.get("dict_name", String.class),
        r.get("item_kind", String.class),
        r.get("item_code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("sort_order", Integer.class),
        r.get("status", String.class),
        r.get("reference_count", Long.class),
        r.get("referencing_systems", Long.class),
        r.get("referenced", Boolean.class),
        r.get("code_locked", Boolean.class),
        r.get("version", Long.class),
        r.get("disabled_reason", String.class),
        r.get("disabled_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DictHistoryView toHistory(Record r) {
    return new DictHistoryView(
        r.get("id", UUID.class),
        r.get("dict_code", String.class),
        r.get("item_id", UUID.class),
        r.get("item_code", String.class),
        r.get("item_name", String.class),
        r.get("action", String.class),
        r.get("before_name", String.class),
        r.get("before_description", String.class),
        r.get("after_name", String.class),
        r.get("after_description", String.class),
        r.get("before_status", String.class),
        r.get("after_status", String.class),
        r.get("reason", String.class),
        r.get("actor_name", String.class),
        r.get("changed_at", OffsetDateTime.class));
  }

  // ------------------------------------------------------------------
  // 校验
  // ------------------------------------------------------------------

  /** 读字典目录；不存在或已下线时给出可读原因，而不是让调用方拿到一个空值。 */
  private Record catalogRow(String code) {
    var row = sql.fetchOne("select * from dict_catalog where code=?", code.trim());
    if (row == null) throw IdentityController.error(HttpStatus.BAD_REQUEST, "字典 " + code + " 不存在");
    return row;
  }

  /**
   * 字典项编码合法性。
   *
   * <p>编码会进单据、报表与导出，口径与 V48 编号前缀一致：大写字母开头、 字母数字下划线、2–64 位。小写与空格会让「同一个字典项」在不同系统里 出现两种写法，跨服务对齐时永远对不上。
   */
  private static void checkCode(String code) {
    check(
        code.matches("[A-Z][A-Z0-9_]{1,63}"),
        "字典项编码需为大写字母开头的标识符（2–64 位字母、数字或下划线），当前为「" + code + "」");
  }

  /** 停用/恢复必须带原因：事后无法解释「为什么这个字典项不能选了」。 */
  private static String requiredReason(String value) {
    check(value != null && !value.isBlank(), "必须填写原因：事后要能解释这次停用或恢复的依据");
    return value.trim();
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * 引用登记条数：有多少个来源系统为这个字典项留过记录。
   *
   * <p>按「登记记录是否存在」计数而不是按 {@code usage_count} 求和： 计数归零的字典项同样算被引用过——它进过历史单据，编码不能改、项不能删。
   */
  private long usageCount(UUID tenantId, UUID itemId) {
    var row =
        sql.fetchOne(
            "select count(*) as n from dict_item_usage where tenant_id=? and item_id=?",
            tenantId,
            itemId);
    return row == null ? 0 : row.get("n", Long.class);
  }

  // ------------------------------------------------------------------
  // 留痕
  // ------------------------------------------------------------------

  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  private void history(
      IdentityController.Access a,
      String auth,
      UUID itemId,
      String dictCode,
      String itemCode,
      String itemName,
      String description,
      String action,
      String beforeName,
      String beforeDescription,
      String afterName,
      String afterDescription,
      String beforeStatus,
      String afterStatus,
      String reason) {
    // changed_at 的 cast 刻意不在 values 末位：末位的 cast 会让 jOOQ 整体错位一格。
    sql.execute(
        "insert into dict_history(id,tenant_id,dict_code,item_id,item_code,item_name,action,"
            + " before_name,before_description,after_name,after_description,before_status,"
            + " after_status,reason,actor_membership_id,actor_name,changed_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        Ids.next(),
        a.tenantId(),
        dictCode,
        itemId,
        itemCode,
        itemName,
        action,
        beforeName,
        beforeDescription,
        afterName,
        afterDescription,
        beforeStatus,
        afterStatus,
        note(reason),
        a.membershipId(),
        actorName(auth),
        utcNow());
    sql.execute(
        "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        itemId,
        "Dictionary" + action);
  }

  private Map<String, Object> itemSnapshot(UUID id) {
    var row = sql.fetchOne("select * from dict_item where id=?", id);
    return row == null ? null : rowSnapshot(row);
  }

  private Map<String, Object> rowSnapshot(Record r) {
    var snap = new LinkedHashMap<String, Object>();
    snap.put("dictCode", r.get("dict_code", String.class));
    snap.put("itemCode", r.get("item_code", String.class));
    snap.put("name", r.get("name", String.class));
    snap.put("description", r.get("description", String.class));
    snap.put("sortOrder", r.get("sort_order", Integer.class));
    snap.put("status", r.get("status", String.class));
    snap.put("disabledReason", r.get("disabled_reason", String.class));
    snap.put("version", r.get("version", Long.class));
    return snap;
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位（与 BusinessParameterController.Condition 同一做法）。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    Condition(String first) {
      clauses.add(first);
    }

    void add(String clause) {
      clauses.add(clause);
    }

    void bind(Object value) {
      bindings.add(value);
    }

    String sql() {
      return String.join(" ", clauses);
    }

    Object[] bindings() {
      return bindings.toArray();
    }
  }
}
