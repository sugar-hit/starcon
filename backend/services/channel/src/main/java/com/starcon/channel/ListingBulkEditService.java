package com.starcon.channel;

import static com.starcon.channel.ListingBulkEditModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.1.2 批量编辑。
 *
 * <p>蓝图：「对一批 listing 执行统一的字段修改（追加/替换/正则替换标题、批量换图、统一加卖点、批量改类目），支持按平台差异化模板与操作预览（改前 vs 改后）。输入：目标
 * listing 集合；批量操作定义；预览确认。输出：批量修改草稿（待发布）；修改记录。关系：上游 = 4.1.1.1；下游 =
 * 4.3.1.2。<b>批量编辑只改草稿不直接改线上</b>，必须走发布流程——这是"误改全店标题"事故的唯一防线。」
 *
 * <p>🔴 本类唯一的核心职责是**把那次"批量改全店"挡在草稿态**。落法是三层，缺一层都不算交付：
 *
 * <ol>
 *   <li><b>API 形状</b>：没有"保存到线上"的方法。整个类里唯一会改变持久状态的写路径是
 *       {@link #confirm}，而它的产出是 {@code listing_content_draft} 草稿行。
 *   <li><b>事务标记</b>：{@link #confirm} 的整个写事务打开 {@code app.draft_only}；V177 的
 *       {@code guard_listing_live_content} 触发器在此期间拒绝对线上 listing 内容列的任何改动。
 *       于是将来有人在批量编辑路径里手滑加了句 {@code update listing set title=…} 会在测试期就炸。
 *   <li><b>物理分表</b>：草稿是另一张表，跨表写入只可能出现在 4.3.1.2 的发布流程里那条被 review 的路径上。
 * </ol>
 *
 * <p>🔴 预览与执行<b>共用同一批 {@code listing_bulk_change} 行</b>：preview 把逐条 before/after 落库为
 * {@code PREVIEWED}，confirm 只是把同一批行推进成已应用并把它们写进草稿。所以"预览一套、执行另一套"
 * 这种最伤信任的偏差在结构上不可能出现，集成测试逐条比对两者。
 *
 * <p>🔴 跨租户的目标集合是**自动切分 + 显式列出**，不是静默丢弃也不是整批失败：调用者勾了 100 条、
 * 其中 30 条属于别的租户时，可见的那 70 条照常进草稿，不可见的 30 个 id 在
 * {@code outOfScopeIds} 里逐个回显。全部不可见时才整体拒绝 —— 那时没有任何东西可以改。
 */
@Service
public class ListingBulkEditService {

  private static final Set<String> OPERATION_TYPES =
      Set.of(
          "TITLE_APPEND", "TITLE_REPLACE", "TITLE_REGEX_REPLACE",
          "REPLACE_IMAGES", "BULLET_APPEND", "REPLACE_CATEGORY");

  private static final String TITLE = "TITLE";
  private static final String BULLETS = "BULLETS";
  private static final String IMAGES = "IMAGES";
  private static final String CATEGORY_PATH = "CATEGORY_PATH";
  private static final String PLATFORM_CATEGORY_ID = "PLATFORM_CATEGORY_ID";

  /** 未套模板时的兜底上限；它们同时是 listing.title 的物理列宽，不是"随便定的数"。 */
  private static final int DEFAULT_TITLE_MAX = 500;

  private static final int DEFAULT_IMAGE_MAX = 9;
  private static final int DEFAULT_BULLET_MAX = 10;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingBulkEditService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if ("{}".equals(value) || value.isBlank()) {
      return value;
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static int codePointLength(String value) {
    return value.codePointCount(0, value.length());
  }

  /** 数组 ↔ JSON 数组文本。变更记录里以文本存，读出来仍是稳定的展示形状。 */
  private String arrayJson(List<String> values) {
    try {
      return json.writeValueAsString(values == null ? List.of() : values);
    } catch (Exception e) {
      return "[]";
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> arrayValue(String raw) {
    if (blankToNull(raw) == null) {
      return List.of();
    }
    try {
      return json.readValue(raw, List.class);
    } catch (Exception e) {
      return List.of();
    }
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>标题与类目的 {@code onlineBaselineAvailable=true}（before 值真的来自线上 listing）；
   * 卖点与图片是 {@code false} —— 它们的线上内容由 4.2.1.1 刊登编辑器拥有，而它在集成头尚未交付。
   * 前端据此显示「线上基线未接入」，**不编一个假基线**。
   */
  public BulkEditCapabilities capabilities() {
    return new BulkEditCapabilities(
        List.of(
            new BulkEditCapabilityDimension(
                "title",
                "追加 / 替换 / 正则替换标题；before 值取自线上 listing.title",
                true, true, "listing.title（V171）", "",
                "标题是批量误改事故里最常被改坏的字段，所以它的 before/after 必须逐条可查"),
            new BulkEditCapabilityDimension(
                "category",
                "批量改类目（类目路径 + 平台类目 id 一起改，两者是同一次决定）",
                true, true, "listing.category_path / platform_category_id（V171）", "",
                "只改路径不改平台类目 id 会让发布后平台侧仍按旧类目审核"),
            new BulkEditCapabilityDimension(
                "bullets",
                "🔴 统一加卖点；本模块做的是真实批量，但 **线上**卖点基线由 4.2.1.1 拥有",
                true, false, "listing_content_draft.bullets（V177 草稿态）", "4.2.1.1 刊登编辑器",
                "没有线上卖点基线时，「追加」若照做会在发布时把 listing 原有的卖点覆盖掉；"
                    + "所以本模块在该情况下**显式跳过**并写明原因，而不是产出一份会清空卖点的草稿"),
            new BulkEditCapabilityDimension(
                "images",
                "🔴 批量换图（整组替换）；本模块做的是真实批量，但 **线上**图片基线由 4.2.1.1 拥有",
                true, false, "listing_content_draft.images（V177 草稿态）", "4.2.1.1 刊登编辑器",
                "换图是整组替换语义，没有基线也能确定结果，所以本模块照做；"
                    + "但改前值只能显示为空集而不是线上原图，运营需知道这一点"),
            new BulkEditCapabilityDimension(
                "platform_template",
                "🔴 按平台差异化模板：标题长度 / 图片数 / 卖点数上限按平台配，各平台段各走各的上限",
                true, true, "listing_bulk_template（V177）", "",
                "上限写死成 500 会让发到短标题平台的商品直到被平台驳回才发现超长"),
            new BulkEditCapabilityDimension(
                "draft_isolation",
                "🔴 草稿态与线上态物理分离：批量编辑只写草稿表，线上 listing 的内容列在批量事务期间被 DB 拒写",
                true, true, "listing_content_draft + guard_listing_live_content（V177）", "",
                "这是「误改全店标题」事故的唯一防线；没有它，批量编辑就是一个带筛选器的全表 UPDATE"),
            new BulkEditCapabilityDimension(
                "regex_safety",
                "🔴 正则替换的 ReDoS 防线：静态结构分析 + 对抗语料实测计时，危险模式直接拒绝",
                true, true, "SafeRegex + guard_bulk_change_not_oversized（V177）", "",
                "批量把同一个正则施加到一批标题上，一个灾难性回溯模式就够卡死整次批量"),
            new BulkEditCapabilityDimension(
                "publish",
                "🔴 发布：把草稿推到线上",
                false, false, "—", "4.3.1.2 批量发布",
                "本模块只产出待发布草稿。发布流程不打开 app.draft_only 标记，它本来就要写线上 —— "
                    + "这也是「批量编辑不直接改线上」与「发布能改线上」能同时成立的原因")),
        OPERATION_TYPES.stream().sorted().toList(),
        List.of(TITLE, BULLETS, IMAGES, CATEGORY_PATH, PLATFORM_CATEGORY_ID),
        List.of("APPLIED", "NO_CHANGE", "SKIPPED_LIMIT", "SKIPPED_NO_BASELINE"),
        List.of("DRAFT", "PUBLISHED", "DISCARDED"),
        "🔴 批量编辑只写草稿表 listing_content_draft，**绝不** UPDATE 线上 listing 的 title / category_path / "
            + "platform_category_id。确认写入草稿的事务会打开 app.draft_only 标记，DB 触发器 "
            + "guard_listing_live_content 在此期间拒绝对这三列的任何改动 —— 界面不应提供任何「直接上线」入口，"
            + "草稿要上线必须走 4.3.1.2 的发布流程。",
        "卖点的**线上**基线与图片的**线上**基线由 4.2.1.1 刊登编辑器拥有，尚未交付。因此："
            + "统一加卖点在没有已有草稿时会显式跳过（照做会在发布时清空原有卖点）；"
            + "批量换图的改前值显示为空集而不是线上原图。两者都请按「基线未接入」理解，不要当成商品本来就没卖点 / 没图。",
        "上游 4.1.1.1 的 listing（channel 同库，本模块不复制它的任何一列）；"
            + "下游 4.3.1.2 批量发布消费本模块产出的草稿。本模块无金额列：批量改价归 05 定价，"
            + "与 4.1.1.1 显式拒绝批量改价同一纪律。");
  }

  // ==================================================================
  // 平台差异化模板
  // ==================================================================

  public List<BulkTemplateRow> templates(String platformCode) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select t.id,t.platform_id,p.code as platform_code,t.code,t.name,t.operation_type,t.params,
                   t.title_max_length,t.image_max_count,t.bullet_max_count,t.enabled,t.updated_at
            from listing_bulk_template t
            join platform p on p.id=t.platform_id
            where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            """);
    List<Object> binds = new ArrayList<>();
    String platform = blankToNull(platformCode);
    if (platform != null) {
      sqlText.append(" and p.code = ?");
      binds.add(platform);
    }
    sqlText.append(" order by p.code, t.code");
    return sql.fetch(sqlText.toString(), binds.toArray()).stream().map(this::templateRow).toList();
  }

  public BulkTemplateRow createTemplate(BulkTemplateRequest request) {
    UUID tenant = currentTenant();
    String type = text(request.operationType()).toUpperCase(Locale.ROOT);
    if (!OPERATION_TYPES.contains(type)) {
      throw unprocessable("未知的批量操作类型：" + request.operationType());
    }
    BulkEditParams params = request.params() == null ? emptyParams() : request.params();
    validateParams(type, params);

    UUID platformId =
        one(
            UUID.class,
            "select id from platform where id=?",
            request.platformId());
    if (platformId == null) {
      throw unprocessable("平台不存在：" + request.platformId());
    }

    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_bulk_template
          (id,tenant_id,platform_id,code,name,operation_type,params,
           title_max_length,image_max_count,bullet_max_count,enabled,created_at,updated_at)
        values (?,?,?,?,?,?,?::jsonb,?,?,?,true,now(),now())
        on conflict (tenant_id, platform_id, code) do update set
          name=excluded.name, operation_type=excluded.operation_type, params=excluded.params,
          title_max_length=excluded.title_max_length, image_max_count=excluded.image_max_count,
          bullet_max_count=excluded.bullet_max_count, version=listing_bulk_template.version+1,
          updated_at=now()
        """,
        id, tenant, request.platformId(), text(request.code()), text(request.name()), type,
        writeJson(params), limit(request.titleMaxLength(), DEFAULT_TITLE_MAX),
        limit(request.imageMaxCount(), DEFAULT_IMAGE_MAX), limit(request.bulletMaxCount(), DEFAULT_BULLET_MAX));

    return loadTemplate(tenant, id);
  }

  private BulkTemplateRow loadTemplate(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            """
            select t.id,t.platform_id,p.code as platform_code,t.code,t.name,t.operation_type,t.params,
                   t.title_max_length,t.image_max_count,t.bullet_max_count,t.enabled,t.updated_at
            from listing_bulk_template t
            join platform p on p.id=t.platform_id
            where t.tenant_id=? and t.id=?
            """,
            tenant, id);
    if (r == null) {
      throw notFound();
    }
    return templateRow(r);
  }

  private BulkTemplateRow templateRow(Record r) {
    return new BulkTemplateRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("operation_type", String.class),
        readParams(r.get("params", Object.class)),
        r.get("title_max_length", Integer.class),
        r.get("image_max_count", Integer.class),
        r.get("bullet_max_count", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 预览（输入：目标 listing 集合 + 批量操作定义）
  // ==================================================================

  /**
   * 🔴 操作预览：逐条真实的 before/after，落库为 {@code PREVIEWED}。
   *
   * <p>这里**没有**任何写线上表的语句 —— 连 UPDATE 都没有。整个方法只往
   * {@code listing_bulk_operation} 与 {@code listing_bulk_change} 写，而这两张表是修改记录，不是线上内容。
   */
  public BulkEditPreviewResponse preview(BulkEditRequest request) {
    UUID tenant = currentTenant();
    Definition definition = resolveDefinition(request);

    List<UUID> requested = new ArrayList<>(new LinkedHashSet<>(safe(request.listingIds())));
    List<Target> visible = loadTargets(requested);
    List<UUID> outOfScope =
        requested.stream().filter(id -> visible.stream().noneMatch(t -> t.id().equals(id))).toList();

    if (visible.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "选中的 "
              + requested.size()
              + " 条 listing 没有一条在当前租户的数据范围内（跨租户与不存在都表现为这一条）");
    }

    // 🔴 按平台差异化模板切分：套了模板时，模板只对它自己那个平台生效。
    //    对别的平台照套会把"这个平台允许多少字"的答案用错 —— 那正是模板存在的理由。
    List<Target> targeted = new ArrayList<>();
    List<BulkEditPlatformSplit> splits = new ArrayList<>();
    Map<String, List<Target>> byPlatform = new LinkedHashMap<>();
    for (Target target : visible) {
      byPlatform.computeIfAbsent(target.platformCode(), key -> new ArrayList<>()).add(target);
    }
    for (Map.Entry<String, List<Target>> entry : byPlatform.entrySet()) {
      List<Target> group = entry.getValue();
      Target first = group.get(0);
      boolean excluded = definition.templateId() != null && !definition.platformId().equals(first.platformId());
      if (!excluded) {
        targeted.addAll(group);
      }
      splits.add(
          new BulkEditPlatformSplit(
              first.platformCode(),
              first.platformName(),
              excluded ? null : definition.templateId(),
              group.size(),
              definition.titleMaxLength(),
              excluded,
              excluded
                  ? "模板限定平台 "
                      + definition.platformCode()
                      + "，本批 "
                      + group.size()
                      + " 条属于 "
                      + first.platformCode()
                      + "，未被本次批量改动"
                  : ""));
    }

    if (targeted.isEmpty()) {
      throw unprocessable(
          "目标集合与模板平台不一致：模板适用于 "
              + definition.platformCode()
              + "，但选中的 "
              + visible.size()
              + " 条 listing 没有一条属于该平台");
    }

    // 逐条真实计算 —— 预览与执行读的是同一批结果，落进同一张表
    List<ListingBulkChangeRow> changes = new ArrayList<>();
    for (Target target : targeted) {
      changes.addAll(compute(definition, target));
    }

    UUID operationId = Ids.next();
    int applied =
        (int) targeted.stream()
            .filter(t -> changes.stream().anyMatch(c -> c.listingId().equals(t.id()) && "APPLIED".equals(c.outcome())))
            .count();
    int skipped =
        (int) targeted.stream()
            .filter(t -> changes.stream().anyMatch(c -> c.listingId().equals(t.id())))
            .filter(t -> changes.stream().noneMatch(c -> c.listingId().equals(t.id()) && "APPLIED".equals(c.outcome())))
            .count();

    sql.execute(
        """
        insert into listing_bulk_operation
          (id,tenant_id,template_id,operation_type,params,listing_ids,requested_count,
           applied_count,skipped_count,state,platform_splits,actor_id,reason,created_at)
        values (?,?,?,?,?::jsonb,?::uuid[],?,?,?,'PREVIEWED',?::jsonb,?,?,now())
        """,
        operationId, tenant, definition.templateId(), definition.operationType(),
        writeJson(definition.params()),
        pgUuidArray(targeted.stream().map(Target::id).toList()), requested.size(),
        applied, skipped, writeJson(splits), currentActor(), text(request.note()));

    for (ListingBulkChangeRow row : changes) {
      sql.execute(
          """
          insert into listing_bulk_change
            (id,tenant_id,operation_id,listing_id,field,before_value,after_value,changed,outcome,reason)
          values (?,?,?,?,?,?,?,?,?,?)
          """,
          Ids.next(), tenant, operationId, row.listingId(), row.field(),
          row.beforeValue(), row.afterValue(), row.changed(), row.outcome(), text(row.reason()));
    }

    return new BulkEditPreviewResponse(
        operationId, "PREVIEWED", definition.operationType(), requested.size(),
        outOfScope, splits, changes, capabilities().draftIsolationNote());
  }

  /**
   * 🔴 预览确认：把同一批 before/after 写进<b>草稿</b>。
   *
   * <p>整个写事务被 {@code app.draft_only} 包住（{@link #withDraftOnly}）：期间对线上 listing
   * 内容列的任何 UPDATE 都会被 V177 的触发器拒掉。这不是"我们没写那条 SQL"，是**写不了**。
   */
  public ListingBulkEditOperation confirm(BulkEditConfirmRequest request) {
    UUID tenant = currentTenant();
    Record op =
        sql.fetchOne(
            "select id, operation_type, listing_ids, requested_count, state, reason"
                + " from listing_bulk_operation"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id = ? for update",
            request.operationId());
    // 跨租户与不存在返回逐字相同的 404
    if (op == null) {
      throw notFound();
    }
    if (!"PREVIEWED".equals(op.get("state", String.class))) {
      throw unprocessable(
          "该操作已经是 " + op.get("state", String.class) + " 状态，不能重复确认（确认凭 operationId，"
              + "所以确认的一定是同一份预览结果）");
    }
    UUID operationId = op.get("id", UUID.class);

    List<Record> changeRows =
        sql.fetch(
            """
            select c.listing_id, l.external_item_id, c.field, c.before_value, c.after_value,
                   c.changed, c.outcome, c.reason, l.version, l.updated_at, s.platform_id,
                   p.code as platform_code
            from listing_bulk_change c
            join listing l on l.tenant_id=c.tenant_id and l.id=c.listing_id
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and c.operation_id = ?
            order by l.external_item_id, c.field
            """,
            operationId);

    Map<UUID, List<ListingBulkChangeRow>> byListing = new LinkedHashMap<>();
    Map<UUID, Long> baseVersion = new LinkedHashMap<>();
    Map<UUID, OffsetDateTime> baseUpdated = new LinkedHashMap<>();
    for (Record r : changeRows) {
      ListingBulkChangeRow row = changeRow(r);
      byListing.computeIfAbsent(r.get("listing_id", UUID.class), key -> new ArrayList<>()).add(row);
      baseVersion.put(r.get("listing_id", UUID.class), r.get("version", Long.class));
      baseUpdated.put(r.get("listing_id", UUID.class), r.get("updated_at", OffsetDateTime.class));
    }

    // 🔴 只写草稿表。整个 for 循环里没有一句碰 listing 内容列的 UPDATE。
    withDraftOnly(
        () -> {
          for (Map.Entry<UUID, List<ListingBulkChangeRow>> entry : byListing.entrySet()) {
            UUID listingId = entry.getKey();
            if (entry.getValue().stream().noneMatch(row -> "APPLIED".equals(row.outcome()))) {
              continue;
            }
            writeDraft(
                tenant,
                listingId,
                entry.getValue(),
                baseVersion.getOrDefault(listingId, 0L),
                baseUpdated.getOrDefault(listingId, OffsetDateTime.now()),
                operationId);
          }
        });

    int applied =
        (int) byListing.values().stream()
            .filter(rows -> rows.stream().anyMatch(row -> "APPLIED".equals(row.outcome())))
            .count();
    int skipped = byListing.size() - applied;
    sql.execute(
        """
        update listing_bulk_operation
           set state='APPLIED', applied_at=?::timestamptz, applied_count=?, skipped_count=?,
               reason=case when ?<>'' then ? else reason end
         where tenant_id=? and id=?
        """,
        OffsetDateTime.now(), applied, skipped, text(request.note()), text(request.note()),
        tenant, operationId);

    return load(operationId.toString());
  }

  // ==================================================================
  // 修改记录（输出二）
  // ==================================================================

  public List<ListingBulkEditOperation> operations(int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    return sql.fetch(
            """
            select id from listing_bulk_operation
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            order by created_at desc, id desc
            limit ? offset ?
            """,
            safeSize, safePage * safeSize)
        .stream()
        .map(row -> load(row.get("id", UUID.class).toString()))
        .toList();
  }

  public ListingBulkEditOperation load(String rawId) {
    UUID id = parseId(rawId);
    Record r =
        sql.fetchOne(
            """
            select id, operation_type, template_id, state, requested_count, applied_count,
                   skipped_count, listing_ids, platform_splits, actor_id, reason, created_at, applied_at
            from listing_bulk_operation
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id=?
            """,
            id);
    if (r == null) {
      throw notFound();
    }
    List<ListingBulkChangeRow> changes =
        sql.fetch(
                """
                select c.listing_id, l.external_item_id, c.field, c.before_value, c.after_value,
                       c.changed, c.outcome, c.reason
                from listing_bulk_change c
                join listing l on l.tenant_id=c.tenant_id and l.id=c.listing_id
                where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and c.operation_id=?
                order by l.external_item_id, c.field
                """,
                id)
            .stream()
            .map(this::changeRow)
            .toList();
    return operationRow(r, changes);
  }

  private ListingBulkEditOperation operationRow(Record r, List<ListingBulkChangeRow> changes) {
    UUID[] ids = r.get("listing_ids", UUID[].class);
    return new ListingBulkEditOperation(
        r.get("id", UUID.class),
        r.get("operation_type", String.class),
        r.get("template_id", UUID.class),
        r.get("state", String.class),
        nvl(r.get("requested_count", Integer.class)),
        nvl(r.get("applied_count", Integer.class)),
        nvl(r.get("skipped_count", Integer.class)),
        ids == null ? List.of() : List.of(ids),
        readSplits(r.get("platform_splits", Object.class)),
        changes,
        r.get("actor_id", UUID.class),
        r.get("reason", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("applied_at", OffsetDateTime.class));
  }

  private ListingBulkChangeRow changeRow(Record r) {
    return new ListingBulkChangeRow(
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("field", String.class),
        r.get("before_value", String.class),
        r.get("after_value", String.class),
        Boolean.TRUE.equals(r.get("changed", Boolean.class)),
        r.get("outcome", String.class),
        r.get("reason", String.class));
  }

  // ==================================================================
  // 草稿（输出一：待发布）
  // ==================================================================

  public List<ListingContentDraftRow> drafts(int page, int size, String state) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    StringBuilder where =
        new StringBuilder(
            " from listing_content_draft d"
                + " join listing l on l.tenant_id=d.tenant_id and l.id=d.listing_id"
                + " join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id"
                + " join platform p on p.id=s.platform_id"
                + " where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    List<Object> binds = new ArrayList<>();
    String wanted = blankToNull(state);
    if (wanted != null) {
      if (!Set.of("DRAFT", "PUBLISHED", "DISCARDED").contains(wanted)) {
        throw unprocessable("未知的草稿状态：" + wanted);
      }
      where.append(" and d.state = ?");
      binds.add(wanted);
    }
    return sql.fetch(
            """
            select d.id,d.listing_id,l.external_item_id,p.code as platform_code,
                   d.title,d.bullets,d.images,d.category_path,d.platform_category_id,d.touched_fields,
                   l.title as live_title,l.category_path as live_category_path,
                   d.state,d.base_version,l.version as live_version,d.last_operation_id,
                   d.created_at,d.updated_at
            """
                + where
                + " order by d.updated_at desc, d.id desc limit ? offset ?",
            concat(binds, safeSize, safePage * safeSize))
        .stream()
        .map(this::draftRow)
        .toList();
  }

  public ListingContentDraftRow draft(String rawListingId) {
    UUID listingId = parseId(rawListingId);
    Record r =
        sql.fetchOne(
            """
            select d.id,d.listing_id,l.external_item_id,p.code as platform_code,
                   d.title,d.bullets,d.images,d.category_path,d.platform_category_id,d.touched_fields,
                   l.title as live_title,l.category_path as live_category_path,
                   d.state,d.base_version,l.version as live_version,d.last_operation_id,
                   d.created_at,d.updated_at
            from listing_content_draft d
            join listing l on l.tenant_id=d.tenant_id and l.id=d.listing_id
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and d.listing_id=? and d.state='DRAFT'
            """,
            listingId);
    if (r == null) {
      throw notFound();
    }
    return draftRow(r);
  }

  private ListingContentDraftRow draftRow(Record r) {
    long base = r.get("base_version", Long.class) == null ? 0L : r.get("base_version", Long.class);
    long live = r.get("live_version", Long.class) == null ? 0L : r.get("live_version", Long.class);
    String[] bullets = r.get("bullets", String[].class);
    return new ListingContentDraftRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("platform_code", String.class),
        r.get("title", String.class),
        bullets == null ? null : List.of(bullets),
        r.get("images", Object.class) == null ? null : arrayValue(jsonText(r.get("images", Object.class))),
        r.get("category_path", String.class),
        r.get("platform_category_id", String.class),
        touchedFields(r.get("touched_fields", String[].class)),
        r.get("live_title", String.class),
        r.get("live_category_path", String.class),
        r.get("state", String.class),
        base,
        live != base,
        r.get("last_operation_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 废弃草稿：允许重来，但**不删除** —— 废弃本身是一条可回溯的事实。 */
  public ListingContentDraftRow discardDraft(String rawListingId) {
    UUID tenant = currentTenant();
    UUID listingId = parseId(rawListingId);
    int updated =
        sql.execute(
            "update listing_content_draft set state='DISCARDED', updated_at=now()"
                + " where tenant_id=? and listing_id=? and state='DRAFT'",
            tenant, listingId);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "没有待发布的草稿（跨租户与不存在都表现为这一条）");
    }
    return draftRow(
        sql.fetchOne(
            """
            select d.id,d.listing_id,l.external_item_id,p.code as platform_code,
                   d.title,d.bullets,d.images,d.category_path,d.platform_category_id,d.touched_fields,
                   l.title as live_title,l.category_path as live_category_path,
                   d.state,d.base_version,l.version as live_version,d.last_operation_id,
                   d.created_at,d.updated_at
            from listing_content_draft d
            join listing l on l.tenant_id=d.tenant_id and l.id=d.listing_id
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            where d.tenant_id=? and d.listing_id=?
            order by d.created_at desc limit 1
            """,
            tenant, listingId));
  }

  // ==================================================================
  // 正则预检
  // ==================================================================

  /** 前端提交前预检：让用户在点确认之前就知道这个模式会被拒，而不是在批量执行中途才发现。 */
  public RegexSafetyVerdict checkRegex(String pattern) {
    return SafeRegex.check(pattern);
  }

  /**
   * 目标集合的可见性预检：返回**不在**当前租户数据范围内的 id。
   *
   * <p>跨租户与不存在的 id 都在这里 —— 两者对调用方没有区别，这正是期望的行为
   * （能区分「你的权限不够」与「这条不存在」本身就是一条信息泄露）。
   */
  public List<UUID> scopeCheck(List<UUID> listingIds) {
    List<UUID> requested = new ArrayList<>(new LinkedHashSet<>(safe(listingIds)));
    List<Target> visible = loadTargets(requested);
    return requested.stream().filter(id -> visible.stream().noneMatch(t -> t.id().equals(id))).toList();
  }

  // ==================================================================
  // 定义解析 / 逐条计算
  // ==================================================================

  private record Definition(
      String operationType,
      BulkEditParams params,
      int titleMaxLength,
      int imageMaxCount,
      int bulletMaxCount,
      UUID templateId,
      UUID platformId,
      String platformCode,
      String platformName) {}

  private record Target(
      UUID id,
      String externalItemId,
      UUID platformId,
      String platformCode,
      String platformName,
      String title,
      String categoryPath,
      String platformCategoryId,
      List<String> draftBullets,
      List<String> draftImages) {}

  private static BulkEditParams emptyParams() {
    return new BulkEditParams(null, null, null, null, null, null, null, null, null);
  }

  /** 模板优先：给了模板就以模板的操作类型、参数与上限为准，平台**不**匹配的目标在切分里被显式排除。 */
  private Definition resolveDefinition(BulkEditRequest request) {
    if (request.templateId() != null) {
      Record t =
          sql.fetchOne(
              """
              select t.id,t.operation_type,t.params,t.title_max_length,t.image_max_count,
                     t.bullet_max_count,t.platform_id,p.code as platform_code,p.name as platform_name,t.enabled
              from listing_bulk_template t
              join platform p on p.id=t.platform_id
              where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and t.id=?
              """,
              request.templateId());
      if (t == null) {
        throw notFound();
      }
      if (!Boolean.TRUE.equals(t.get("enabled", Boolean.class))) {
        throw unprocessable("模板已停用：" + request.templateId());
      }
      BulkEditParams params = readParams(t.get("params", Object.class));
      String type = t.get("operation_type", String.class);
      validateParams(type, params);
      return new Definition(
          type,
          params,
          limit(request.titleMaxLength(), t.get("title_max_length", Integer.class)),
          limit(request.imageMaxCount(), t.get("image_max_count", Integer.class)),
          limit(request.bulletMaxCount(), t.get("bullet_max_count", Integer.class)),
          t.get("id", UUID.class),
          t.get("platform_id", UUID.class),
          t.get("platform_code", String.class),
          t.get("platform_name", String.class));
    }

    String type = text(request.operationType()).toUpperCase(Locale.ROOT);
    if (type.isEmpty()) {
      throw unprocessable(
          "批量操作类型不能为空。可选：" + String.join(" / ", OPERATION_TYPES.stream().sorted().toList()));
    }
    if (!OPERATION_TYPES.contains(type)) {
      throw unprocessable(
          "未知的批量操作类型："
              + request.operationType()
              + "。可选："
              + String.join(" / ", OPERATION_TYPES.stream().sorted().toList()));
    }
    BulkEditParams params = request.params() == null ? emptyParams() : request.params();
    validateParams(type, params);
    return new Definition(
        type, params,
        limit(request.titleMaxLength(), DEFAULT_TITLE_MAX),
        limit(request.imageMaxCount(), DEFAULT_IMAGE_MAX),
        limit(request.bulletMaxCount(), DEFAULT_BULLET_MAX),
        null, null, "", "");
  }

  /** 按操作类型校验用到的参数必须给全 —— 少给一个就跑出一条"什么都没改"的批量，是最坏的结果。 */
  private void validateParams(String type, BulkEditParams params) {
    switch (type) {
      case "TITLE_APPEND" -> {
        if (blankToNull(params.titleSuffix()) == null) {
          throw unprocessable("追加标题必须给出 titleSuffix");
        }
      }
      case "TITLE_REPLACE" -> {
        if (blankToNull(params.find()) == null) {
          throw unprocessable("替换标题必须给出 find（要替换的字面量片段）");
        }
      }
      case "TITLE_REGEX_REPLACE" -> {
        if (blankToNull(params.pattern()) == null) {
          throw unprocessable("正则替换标题必须给出 pattern");
        }
        RegexSafetyVerdict verdict = SafeRegex.check(params.pattern());
        if (!verdict.safe()) {
          throw unprocessable("正则不安全，已拒绝执行：" + verdict.reason());
        }
        if (params.replacement() == null) {
          throw unprocessable("正则替换标题必须给出 replacement");
        }
      }
      case "REPLACE_IMAGES" -> {
        if (safe(params.images()).isEmpty()) {
          throw unprocessable("批量换图必须给出 images（整组替换语义，不接受空列表）");
        }
        for (String image : safe(params.images())) {
          if (blankToNull(image) == null) {
            throw unprocessable("图片项不能为空串");
          }
        }
      }
      case "BULLET_APPEND" -> {
        if (safe(params.bullets()).isEmpty()) {
          throw unprocessable("统一加卖点必须给出 bullets");
        }
        for (String bullet : safe(params.bullets())) {
          if (blankToNull(bullet) == null) {
            throw unprocessable("卖点项不能为空串");
          }
        }
      }
      case "REPLACE_CATEGORY" -> {
        if (blankToNull(params.categoryPath()) == null && blankToNull(params.platformCategoryId()) == null) {
          throw unprocessable("批量改类目必须至少给出 categoryPath 或 platformCategoryId 之一");
        }
      }
      default -> throw unprocessable("未知的批量操作类型：" + type);
    }
  }

  /**
   * 取当前租户 + 数据范围内的目标 listing，并带上它**已有的草稿**。
   *
   * <p>RLS 与 data_scope RESTRICTIVE 策略在这一句里生效，所以跨租户与越范围的 id 天然查不出来 ——
   * 「自动按租户切分」不是服务层的 if，是 DB 层的行级策略。
   */
  private List<Target> loadTargets(List<UUID> ids) {
    return sql.fetch(
            """
            select l.id, l.external_item_id, s.platform_id, p.code as platform_code, p.name as platform_name,
                   l.title, l.category_path, l.platform_category_id,
                   d.bullets as draft_bullets, d.images as draft_images
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            left join listing_content_draft d
                   on d.tenant_id=l.tenant_id and d.listing_id=l.id and d.state='DRAFT'
            where l.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and l.id = any(?::uuid[])
            order by l.id
            """,
            pgUuidArray(ids))
        .stream()
        .map(
            r -> {
              Object rawBullets = r.get("draft_bullets", Object.class);
              Object rawImages = r.get("draft_images", Object.class);
              return new Target(
                  r.get("id", UUID.class),
                  r.get("external_item_id", String.class),
                  r.get("platform_id", UUID.class),
                  r.get("platform_code", String.class),
                  r.get("platform_name", String.class),
                  r.get("title", String.class),
                  r.get("category_path", String.class),
                  r.get("platform_category_id", String.class),
                  rawBullets == null ? List.of() : List.of(r.get("draft_bullets", String[].class)),
                  rawImages == null ? List.of() : arrayValue(jsonText(rawImages)));
            })
        .toList();
  }

  /** 逐条计算 before/after。**这是预览与执行共用的唯一实现**，两处不可能算出不同结果。 */
  private List<ListingBulkChangeRow> compute(Definition definition, Target target) {
    List<ListingBulkChangeRow> rows = new ArrayList<>();
    switch (definition.operationType()) {
      case "TITLE_APPEND" -> {
        String before = target.title();
        String after = before + definition.params().titleSuffix();
        rows.add(titleRow(target, before, after, definition.titleMaxLength()));
      }
      case "TITLE_REPLACE" -> {
        String before = target.title();
        String find = definition.params().find();
        // 字面量替换：用 String.replace 而不是 replaceAll / 正则 —— 用户给的 "c++" 就是字面量
        String after = before.replace(find, text(definition.params().replace()));
        rows.add(titleRow(target, before, after, definition.titleMaxLength()));
      }
      case "TITLE_REGEX_REPLACE" -> {
        String before = target.title();
        String after;
        try {
          after = SafeRegex.replaceAll(before, definition.params().pattern(), definition.params().replacement());
        } catch (IllegalArgumentException e) {
          throw unprocessable("正则替换被拒绝：" + e.getMessage());
        }
        rows.add(titleRow(target, before, after, definition.titleMaxLength()));
      }
      case "REPLACE_IMAGES" -> {
        List<String> before = target.draftImages();
        List<String> after = safe(definition.params().images());
        if (after.size() > definition.imageMaxCount()) {
          rows.add(change(target, IMAGES, arrayJson(before), arrayJson(after), false, "SKIPPED_LIMIT",
              "换图后 " + after.size() + " 张，超出该平台上限 " + definition.imageMaxCount() + " 张；"
                  + "显式跳过而不是截断，截断会让运营以为只换了一张"));
        } else {
          rows.add(change(target, IMAGES, arrayJson(before), arrayJson(after), !after.equals(before),
              after.equals(before) ? "NO_CHANGE" : "APPLIED",
              before.isEmpty() ? "改前为空集：该字段的线上基线未接入（4.2.1.1 未交付），显示为空集而非线上原图" : ""));
        }
      }
      case "BULLET_APPEND" -> {
        List<String> before = target.draftBullets();
        // 🔴 判据是「有没有卖点**基线**」，不是「有没有草稿」：
        //    一份只换过图的草稿同样没有卖点基线，照做会产出一份只含新卖点的草稿，
        //    发布时（touched_fields 含 BULLETS）listing 原有的卖点会被清空 —— 那是一次没人要求的线上改动。
        if (before.isEmpty()) {
          rows.add(change(target, BULLETS, arrayJson(List.of()), arrayJson(safe(definition.params().bullets())),
              false, "SKIPPED_NO_BASELINE",
              "没有可比的卖点基线，卖点的线上基线未接入（4.2.1.1 未交付）；"
                  + "此时照做会在发布时清空原有卖点，因此显式跳过"));
        } else {
          List<String> after = new ArrayList<>(before);
          after.addAll(safe(definition.params().bullets()));
          if (after.size() > definition.bulletMaxCount()) {
            rows.add(change(target, BULLETS, arrayJson(before), arrayJson(after), false, "SKIPPED_LIMIT",
                "追加后 " + after.size() + " 条，超出该平台上限 " + definition.bulletMaxCount() + " 条；显式跳过而不是截断"));
          } else {
            rows.add(change(target, BULLETS, arrayJson(before), arrayJson(after), !after.equals(before),
                after.equals(before) ? "NO_CHANGE" : "APPLIED", ""));
          }
        }
      }
      case "REPLACE_CATEGORY" -> {
        String path = definition.params().categoryPath();
        String categoryId = definition.params().platformCategoryId();
        if (blankToNull(path) != null) {
          String before = target.categoryPath();
          rows.add(change(target, CATEGORY_PATH, before, path, !before.equals(path),
              before.equals(path) ? "NO_CHANGE" : "APPLIED", ""));
        }
        if (blankToNull(categoryId) != null) {
          String before = target.platformCategoryId();
          rows.add(change(target, PLATFORM_CATEGORY_ID, before, categoryId, !before.equals(categoryId),
              before.equals(categoryId) ? "NO_CHANGE" : "APPLIED", ""));
        }
      }
      default -> throw unprocessable("未知的批量操作类型：" + definition.operationType());
    }
    return rows;
  }

  /** 标题专用：多一道平台长度上限判定，超限是**显式跳过**，绝不静默截断。 */
  private ListingBulkChangeRow titleRow(Target target, String before, String after, int maxLength) {
    if (codePointLength(after) > maxLength) {
      return change(target, TITLE, before, after, false, "SKIPPED_LIMIT",
          "改后 " + codePointLength(after) + " 字，超出该平台上限 " + maxLength
              + " 字；显式跳过而不是截断，截断会让运营以为标题没超长");
    }
    return change(target, TITLE, before, after, !before.equals(after),
        before.equals(after) ? "NO_CHANGE" : "APPLIED", "");
  }

  private ListingBulkChangeRow change(
      Target target, String field, String before, String after, boolean changed, String outcome, String reason) {
    return new ListingBulkChangeRow(
        target.id(), target.externalItemId(), field,
        before == null ? "" : before, after == null ? "" : after,
        changed, outcome, reason);
  }

  // ==================================================================
  // 草稿写入（🔴 唯一的写路径，且只写草稿表）
  // ==================================================================

  /**
   * 把一次操作在一条 listing 上的 APPLIED 结果并进草稿。
   *
   * <p>ON CONFLICT 命中的是 {@code listing_content_draft_one_active}（部分唯一索引，只约束 DRAFT 行），
   * 所以已发布 / 已废弃的草稿会作为历史留档，本次新开一份 DRAFT —— 不会被静默吞掉。
   * 合并时 {@code touched_fields} 取并集，且只覆盖本次真正拟改的列：没拟改的列保持原样，
   * 发布时不会被误覆盖。
   */
  private void writeDraft(
      UUID tenant,
      UUID listingId,
      List<ListingBulkChangeRow> rows,
      long baseVersion,
      OffsetDateTime baseUpdatedAt,
      UUID operationId) {

    String title = null;
    String[] bullets = null;
    String images = null;
    String categoryPath = null;
    String platformCategoryId = null;
    List<String> touched = new ArrayList<>();

    for (ListingBulkChangeRow row : rows) {
      if (!"APPLIED".equals(row.outcome())) {
        continue;
      }
      switch (row.field()) {
        case TITLE -> {
          title = row.afterValue();
          touched.add(TITLE);
        }
        case BULLETS -> {
          bullets = arrayValue(row.afterValue()).toArray(String[]::new);
          touched.add(BULLETS);
        }
        case IMAGES -> {
          images = row.afterValue();
          touched.add(IMAGES);
        }
        case CATEGORY_PATH -> {
          categoryPath = row.afterValue();
          touched.add(CATEGORY_PATH);
        }
        case PLATFORM_CATEGORY_ID -> {
          platformCategoryId = row.afterValue();
          touched.add(PLATFORM_CATEGORY_ID);
        }
        default -> { }
      }
    }
    if (touched.isEmpty()) {
      return;
    }

    sql.execute(
        """
        insert into listing_content_draft
          (id,tenant_id,listing_id,title,bullets,images,category_path,platform_category_id,
           touched_fields,state,base_version,base_listing_updated_at,last_operation_id,created_at,updated_at)
        values (?,?,?,?,?::text[],?::jsonb,?,?,?::text[],'DRAFT',?::bigint,?::timestamptz,?,now(),now())
        on conflict (tenant_id, listing_id) where state='DRAFT' do update set
          title = case when 'TITLE' = any(excluded.touched_fields) then excluded.title else listing_content_draft.title end,
          bullets = case when 'BULLETS' = any(excluded.touched_fields) then excluded.bullets else listing_content_draft.bullets end,
          images = case when 'IMAGES' = any(excluded.touched_fields) then excluded.images else listing_content_draft.images end,
          category_path = case when 'CATEGORY_PATH' = any(excluded.touched_fields) then excluded.category_path else listing_content_draft.category_path end,
          platform_category_id = case when 'PLATFORM_CATEGORY_ID' = any(excluded.touched_fields) then excluded.platform_category_id else listing_content_draft.platform_category_id end,
          touched_fields = ARRAY(SELECT DISTINCT unnest(listing_content_draft.touched_fields || excluded.touched_fields)),
          last_operation_id = excluded.last_operation_id,
          base_version = excluded.base_version,
          base_listing_updated_at = excluded.base_listing_updated_at,
          version = listing_content_draft.version + 1,
          updated_at = now()
        """,
        Ids.next(), tenant, listingId, title,
        bullets == null ? null : pgTextArray(List.of(bullets)),
        images, categoryPath, platformCategoryId,
        pgTextArray(touched), baseVersion, baseUpdatedAt, operationId);
  }

  /**
   * 🔴 打开 {@code app.draft_only}，期间线上 listing 的内容列由 DB 触发器拒写。
   *
   * <p>用 {@code set_config(...,true)} —— 第三个参数 true 表示**事务局部**：连接归还池子后不会把
   * 这个标记带给下一个租户（这正是 AGENTS.md 里"连接池归还后不串租户"那条要求在本模块的落点）。
   */
  private void withDraftOnly(Runnable work) {
    sql.execute("select set_config('app.draft_only','true',true)");
    try {
      work.run();
    } finally {
      sql.execute("select set_config('app.draft_only','false',true)");
    }
  }

  // ==================================================================
  // 其余小工具
  // ==================================================================

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static int limit(Integer requested, Integer fallback) {
    if (requested != null) {
      return requested;
    }
    return fallback == null ? DEFAULT_TITLE_MAX : fallback;
  }

  private static int nvl(Integer value) {
    return value == null ? 0 : value;
  }

  private static List<String> touchedFields(String[] fields) {
    return fields == null ? List.of() : List.of(fields);
  }

  /**
   * 🔴 PG 数组必须以**文本**绑定，不能直接绑 {@code UUID[]}。
   *
   * <p>直接绑数组时驱动按元素类型把它送成 {@code uuid}，于是 {@code ?::uuid[]} 会得到
   * 「cannot cast type uuid to uuid[]」—— 实测踩过。所以先拼成 PG 的数组字面量
   * {@code {a,b,c}} 再让 PG 自己做 text→uuid[] 的转换。
   */
  private static String pgUuidArray(List<UUID> ids) {
    return "{" + ids.stream().map(UUID::toString)
        .collect(java.util.stream.Collectors.joining(",")) + "}";
  }

  /** 同上；PG 的 text 数组字面量要求引号与反斜杠转义，卖点文案里的引号很常见。 */
  private static String pgTextArray(List<String> values) {
    return "{" + values.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .collect(java.util.stream.Collectors.joining(",")) + "}";
  }

  private static List<BulkEditPlatformSplit> readSplits(Object raw) {
    if (raw == null) {
      return List.of();
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value.isBlank() || "[]".equals(value)) {
      return List.of();
    }
    try {
      return new ObjectMapper().readValue(value,
          new com.fasterxml.jackson.core.type.TypeReference<List<BulkEditPlatformSplit>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private BulkEditParams readParams(Object raw) {
    if (raw == null) {
      return emptyParams();
    }
    String value = jsonText(raw);
    if ("{}".equals(value)) {
      return emptyParams();
    }
    try {
      return json.readValue(value, BulkEditParams.class);
    } catch (Exception e) {
      return emptyParams();
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw notFound();
    }
  }
}
