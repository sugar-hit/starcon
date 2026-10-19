package com.starcon.channel;

import static com.starcon.channel.ListingTemplateModels.*;

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
 * 4.2.2.1 刊登模板。
 *
 * <p>蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」
 *
 * <p>🔴 本类唯一的核心职责是**让模板只存规则、并在套用时实时求值**。落法是三层，缺一层都不算交付：
 *
 * <ol>
 *   <li><b>存储形状</b>：模板表只有适用范围，规则表只有「来源引用 + 取值表达式」。值唯一存在的地方是
 *       {@code listing_template_apply_field} —— 那是**套用日志**（历史事实），不是模板。
 *   <li><b>DB 约束</b>：V195 的 CHECK 要求来源引用只能是标识符、取值表达式里的占位符只能是标识符，
 *       非 LITERAL 来源必须至少引用一个占位符。于是「把商品现在的值抄进模板」在 DB 层写不进去。
 *   <li><b>实时求值</b>：{@link #apply} 每次都**重新**读源商品（属性 / 素材 / 文案），
 *       所以源商品改价或换图后重新套用，产出的草稿值跟着变。
 * </ol>
 *
 * <p>🔴 套用**只产出草稿**（V177 的 {@code listing_content_draft}），写事务打开
 * {@code app.draft_only}，期间线上 listing 的内容列被 V177 的 {@code guard_listing_live_content}
 * 触发器拒写。整个类里**没有**任何一个能写线上内容的路径 —— 发布是 4.3.1.2 的事。
 *
 * <p>🔴 「不知道」是一个状态，不是一个 0：跨服务上游取不到时该字段是
 * {@code UNKNOWN_SOURCE}（读不到）/ {@code SOURCE_MISSING}（引用不存在），
 * 两者都带 {@code ownerModule} 点名归属模块，绝不回退成「没有值」。
 *
 * <p>🔴 必填校验是**算出来的**：每条规则的 {@code required} 为真且求值失败 ⇒ 该字段记违规，
 * 违规指到具体字段与具体规则（形如 {@code PRODUCT_ATTRIBUTE:price}），批次级结论 PASS / FAIL。
 */
@Service
public class ListingTemplateService {

  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 取值来源的五个值（与 {@link ListingTemplateModels#SOURCE_KINDS} 逐字一致）。 */
  private static final Set<String> SOURCE_KIND_KEYS =
      Set.of("LISTING_FIELD", "PRODUCT_ATTRIBUTE", "CONTENT_TEMPLATE", "IMAGE_GROUP", "LITERAL");

  /** 可填字段的五个值（与草稿表的内容列逐字一致）。 */
  private static final Set<String> FIELD_KEYS =
      Set.of("TITLE", "BULLETS", "IMAGES", "CATEGORY_PATH", "PLATFORM_CATEGORY_ID");

  /** 平台维度：03 商品主数据（SKU 详情 / 生效属性）。 */
  private static final String OWNER_PRODUCT = "3.1.2.2";

  /** 平台维度：03 描述与卖点库（文案模板与渲染）。 */
  private static final String OWNER_CONTENT = "3.4.2.2";

  /** 平台维度：03 媒体库（素材与标签）。 */
  private static final String OWNER_MEDIA = "3.4.1.1";

  /** 草稿表内容列的物理宽度（listing_content_draft / listing 的列宽，不是随便定的数）。 */
  private static final int TITLE_MAX = 500;

  private static final int CATEGORY_PATH_MAX = 500;
  private static final int PLATFORM_CATEGORY_ID_MAX = 128;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ListingTemplateSourcePort sources;

  public ListingTemplateService(
      DSLContext sql, ObjectMapper json, ListingTemplateSourcePort sources) {
    this.sql = sql;
    this.json = json;
    this.sources = sources;
  }

  // ==================================================================
  // 小工具（照抄 ListingBulkEditService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 空串与 null 同义：前端契约里 UUID 字段是非空字符串，空串就是「没给」。 */
  private static UUID blankToNullUuid(String value) {
    String trimmed = blankToNull(value);
    return trimmed == null ? null : UUID.fromString(trimmed);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select " + TENANT);
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private static int codePointLength(String value) {
    return value.codePointCount(0, value.length());
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw notFound();
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
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

  /**
   * 🔴 PG 数组必须以**文本**绑定，不能直接绑 {@code UUID[]}。
   *
   * <p>直接绑数组时驱动按元素类型把它送成 {@code uuid}，于是 {@code ?::uuid[]} 会得到
   * 「cannot cast type uuid to uuid[]」—— 实测踩过（4.2.1.2 批量编辑已记）。
   */
  private static String pgUuidArray(List<UUID> ids) {
    return "{"
        + ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))
        + "}";
  }

  /** PG 的 text 数组字面量（引号与反斜杠要转义，卖点文案里引号很常见）。 */
  private static String pgTextArray(List<String> values) {
    return "{"
        + values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(","))
        + "}";
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>三个上游（03 商品主数据 / 03 描述与卖点库 / 03 媒体库）**都已交付**，
   * 所以它们的维度 available=true；唯一 available=false 的是
   * <b>编辑器侧的套用入口</b>（ownerModule=4.2.1.1）—— 那个模块在集成头此刻尚未交付，
   * 而本模块提供的模板与套用能力**已经就绪**，只差编辑器里的一个入口。
   */
  public ListingTemplateCapabilities capabilities() {
    return new ListingTemplateCapabilities(
        List.of(
            new ListingTemplateCapabilityDimension(
                "template_scope",
                "🔴 类目级 / 店铺级两种作用域都真做：适用范围由 DB 约束互斥，"
                    + "判定在真实 listing 数据上做，并可查出真的命中的那批 listing",
                true, "listing_template（V195）+ ListingTemplateService.matches", "",
                "两个作用域都写死成一种的话，「换个类目就套不上」这类问题要到线上才发现"),
            new ListingTemplateCapabilityDimension(
                "rules_not_snapshots",
                "🔴 模板只存规则（来源引用 + 取值表达式），不存值：来源引用与占位符都被 DB 限制成标识符，"
                    + "值一律在套用时实时求值",
                true, "listing_template_field（V195）+ guard_listing_template_field_expression", "",
                "模板一旦吸收了商品快照，商品改价后模板不会跟着变，而且没有任何报错"),
            new ListingTemplateCapabilityDimension(
                "live_evaluation",
                "🔴 套用时实时求值：源商品改价 / 换图之后重新套用，产出的草稿值跟着变",
                true, "ListingTemplateSourcePort + ListingTemplateService.apply", "",
                "不实时求值的话，「一键套用」就退化成一次性的手工拷贝"),
            new ListingTemplateCapabilityDimension(
                "draft_only",
                "🔴 套用只产出草稿（V177 的 listing_content_draft），写事务打开 app.draft_only，"
                    + "线上 listing 的内容列在事务期间被 DB 拒写",
                true, "listing_content_draft（V177）+ guard_listing_live_content", "",
                "没有它，一个带筛选器的模板套用就等价于一次全表 UPDATE"),
            new ListingTemplateCapabilityDimension(
                "required_validation",
                "🔴 必填校验真的算出来：违规指到具体字段与具体规则（形如 PRODUCT_ATTRIBUTE:price）",
                true, "listing_template_apply_field.violation / violation_code", "",
                "把必填校验做成一个「人填的标记」的话，漏填会一路走到发布"),
            new ListingTemplateCapabilityDimension(
                "unknown_state",
                "🔴「不知道」是一个状态：上游读不到 ⇒ UNKNOWN_SOURCE + ownerModule；"
                    + "引用不存在 ⇒ SOURCE_MISSING + ownerModule。绝不回退成「没有值」或 0",
                true, "listing_template_apply_field.status / owner_module", "",
                "回退成空值的话，运营会以为「这个属性就是空的」，而真相是「我们查不到」"),
            new ListingTemplateCapabilityDimension(
                "product_attributes",
                "取自商品属性（03 商品主数据，跨服务只读，走 catalog 授权 API）",
                true, "catalog GET /catalog/skus/{id}（ListingTemplateSourcePort）", "",
                "取不到时该字段是 UNKNOWN_SOURCE / SOURCE_MISSING，不影响其余字段求值"),
            new ListingTemplateCapabilityDimension(
                "content_library",
                "引用文案库（03 描述与卖点库，跨服务只读，走 catalog 授权 API）",
                true, "catalog GET /catalog/description-templates/templates + POST /renders", "",
                "取不到时该字段是 UNKNOWN_SOURCE / SOURCE_MISSING，不影响其余字段求值"),
            new ListingTemplateCapabilityDimension(
                "media_library",
                "套用图片组（03 媒体库，跨服务只读，走 catalog 授权 API）",
                true, "catalog GET /catalog/media/{id}、GET /catalog/media?tagCode=", "",
                "取不到时该字段是 UNKNOWN_SOURCE / SOURCE_MISSING，不影响其余字段求值"),
            new ListingTemplateCapabilityDimension(
                "platform_isolation",
                "🔴 内容按平台 + 站点隔离：模板带平台（必填）与站点（可空），跨平台套用直接拒",
                true, "listing_template.platform_id / marketplace_id + guard_listing_template_scope", "",
                "不隔离的话，同一条商品在两个平台的内容草稿会互相覆盖"),
            new ListingTemplateCapabilityDimension(
                "editor_entry",
                "🔴 编辑器侧的套用入口：模板能力已就绪（定义 / 规则 / 作用域 / 套用 / 产出草稿全部可用），"
                    + "只差 4.2.1.1 刊登编辑器里的那个入口",
                false, "—", "4.2.1.1",
                "4.2.1.1 与本模块在同一批并行交付，集成头此刻尚未落地；"
                    + "本模块的 API 已可直接被它调用，不需要等它先有表或端点"),
            new ListingTemplateCapabilityDimension(
                "publish",
                "🔴 发布：把草稿推到线上",
                false, "—", "4.3.1.2",
                "本模块只产出待发布草稿。发布流程不打开 app.draft_only 标记，它本来就要写线上 —— "
                    + "这也是「模板不直接改线上」与「发布能改线上」能同时成立的原因"),
            new ListingTemplateCapabilityDimension(
                "description_field",
                "🔴 描述正文（DESCRIPTION）字段：草稿表目前只有 title / bullets / images / "
                    + "category_path / platform_category_id 五个内容列，描述正文是 4.2.1.1 的内容域",
                false, "listing_content_draft（V177 五个内容列）", "4.2.1.1 刊登编辑器",
                "模板能填一个草稿存不下的字段的话，那条规则只会永远「取到了值但落不下去」")),
        FIELD_KEYS.stream().sorted().toList(),
        SOURCE_KIND_KEYS.stream().sorted().toList(),
        EVALUATION_STATUSES,
        SCOPES,
        true,
        "🔴 模板里**没有值**：只有适用范围、来源引用（属性码 / 内容模板编码 / 图片组编码 / listing 字段名）"
            + "与取值表达式（常量前后缀 + {{占位符}}）。值一律在套用时实时求值 —— "
            + "源商品改价或换图之后重新套用，产出的草稿值跟着变。",
        true,
        "🔴 套用只产出**草稿**（listing_content_draft），**不写线上 listing**。"
            + "写事务打开 app.draft_only 标记，DB 触发器 guard_listing_live_content 在此期间拒绝对"
            + "线上内容列的任何改动。发布是 4.3.1.2 的另一条路径。",
        "🔴 重复套用的语义：**不带幂等键**重复套用 = 覆盖草稿（重新求值后覆盖同一份 DRAFT，"
            + "每次都留一条套用记录）；**带同一个幂等键**重复套用 = 返回第一次的那一份结果，"
            + "不重新求值、不再写草稿、不再落日志（结果里 idempotentReplay=true）。",
        "上游 03 商品主数据 / 03 描述与卖点库 / 03 媒体库全部**跨服务只读**，"
            + "只经 catalog 的授权 HTTP API 读（不跨服务 SQL、不建跨库 FK、不建共享视图）；"
            + "下游 4.2.1.1 刊登编辑器消费本模块产出的草稿，4.3.1.2 批量发布负责把草稿推到线上。"
            + "本模块无金额列：刊登模板填的是内容字段，售价归 05 定价。");
  }

  // ==================================================================
  // 筛选词表
  // ==================================================================

  /** 筛选项词表（不硬编码在前端）。 */
  public ListingTemplateFilterOptions filterOptions() {
    List<PlatformOption> platforms =
        sql.fetch("select id, code, name from platform order by code").stream()
            .map(r -> new PlatformOption(r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<ShopOption> shops =
        sql.fetch(
                "select id, code, name from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> new ShopOption(r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<MarketplaceOption> marketplaces =
        sql.fetch("select id, code, platform_id from marketplace order by platform_id, code").stream()
            .map(
                r ->
                    new MarketplaceOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("platform_id", UUID.class)))
            .toList();
    return new ListingTemplateFilterOptions(platforms, shops, marketplaces);
  }

  // ==================================================================
  // 模板列表 / 详情
  // ==================================================================

  /** 模板列表（真分页 + 真筛选）。 */
  public ListingTemplatePage list(
      int page,
      int size,
      String scope,
      String platformCode,
      String shopCode,
      String categoryPath,
      String status,
      String keyword) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    StringBuilder where =
        new StringBuilder(" where t.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String wantedScope = blankToNull(scope);
    if (wantedScope != null) {
      if (!SCOPES.contains(wantedScope)) {
        throw unprocessable("未知的适用范围：" + scope + "。可选：" + String.join(" / ", SCOPES));
      }
      where.append(" and t.scope = ?");
      binds.add(wantedScope);
    }
    String wantedPlatform = blankToNull(platformCode);
    if (wantedPlatform != null) {
      where.append(" and p.code = ?");
      binds.add(wantedPlatform);
    }
    String wantedShop = blankToNull(shopCode);
    if (wantedShop != null) {
      where.append(" and s.code = ?");
      binds.add(wantedShop);
    }
    String wantedCategory = blankToNull(categoryPath);
    if (wantedCategory != null) {
      where.append(" and t.category_path = ?");
      binds.add(wantedCategory);
    }
    String wantedStatus = blankToNull(status);
    if (wantedStatus != null) {
      if (!STATUSES.contains(wantedStatus)) {
        throw unprocessable("未知的模板状态：" + status + "。可选：" + String.join(" / ", STATUSES));
      }
      where.append(" and t.status = ?");
      binds.add(wantedStatus);
    }
    String wantedKeyword = blankToNull(keyword);
    if (wantedKeyword != null) {
      where.append(" and (t.code ilike ? or t.name ilike ?)");
      binds.add("%" + wantedKeyword + "%");
      binds.add("%" + wantedKeyword + "%");
    }

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_template t"
                        + " left join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id"
                        + " join platform p on p.id=t.platform_id"
                        + where,
                    concat(binds)))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingTemplateRow> items =
        sql.fetch(
                """
                select t.id,t.code,t.name,t.description,t.scope,t.platform_id,p.code as platform_code,
                       t.marketplace_id,m.code as marketplace_code,t.category_path,s.code as shop_code,
                       t.language,t.status,t.version,t.created_at,t.updated_at,
                       (select count(*) from listing_template_field f
                         where f.tenant_id=t.tenant_id and f.template_id=t.id) as field_count,
                       (select count(*) from listing_template_field f
                         where f.tenant_id=t.tenant_id and f.template_id=t.id and f.required) as required_count,
                       (select count(*) from listing_template_asset_group g
                         where g.tenant_id=t.tenant_id and g.template_id=t.id) as group_count
                from listing_template t
                left join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id
                join platform p on p.id=t.platform_id
                left join marketplace m on m.id=t.marketplace_id
                """
                    + where
                    + " order by t.updated_at desc, t.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::templateRow)
            .toList();
    return new ListingTemplatePage(items, total, safePage, safeSize);
  }

  /** 模板详情（本体 + 规则 + 图片组）。跨租户与不存在返回逐字相同的 404。 */
  public ListingTemplateDetail load(String rawId) {
    UUID id = parseId(rawId);
    UUID tenant = currentTenant();
    Record r =
        sql.fetchOne(
            """
            select t.id,t.code,t.name,t.description,t.scope,t.platform_id,p.code as platform_code,
                   t.marketplace_id,m.code as marketplace_code,t.category_path,s.code as shop_code,
                   t.language,t.status,t.version,t.created_at,t.updated_at,
                   (select count(*) from listing_template_field f
                     where f.tenant_id=t.tenant_id and f.template_id=t.id) as field_count,
                   (select count(*) from listing_template_field f
                     where f.tenant_id=t.tenant_id and f.template_id=t.id and f.required) as required_count,
                   (select count(*) from listing_template_asset_group g
                     where g.tenant_id=t.tenant_id and g.template_id=t.id) as group_count
            from listing_template t
            left join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id
            join platform p on p.id=t.platform_id
            left join marketplace m on m.id=t.marketplace_id
            where t.tenant_id=? and t.id=?
            """,
            tenant, id);
    if (r == null) {
      throw notFound();
    }
    return new ListingTemplateDetail(
        templateRow(r), fieldRows(tenant, id), groupRows(tenant, id));
  }

  private List<ListingTemplateFieldRow> fieldRows(UUID tenant, UUID templateId) {
    return sql.fetch(
            """
            select id,field,source_kind,source_ref,value_expression,required,slot_no
            from listing_template_field
            where tenant_id=? and template_id=?
            order by slot_no, id
            """,
            tenant, templateId)
        .stream()
        .map(
            r ->
                new ListingTemplateFieldRow(
                    r.get("id", UUID.class),
                    r.get("field", String.class),
                    r.get("source_kind", String.class),
                    r.get("source_ref", String.class),
                    r.get("value_expression", String.class),
                    Boolean.TRUE.equals(r.get("required", Boolean.class)),
                    r.get("slot_no", Integer.class) == null ? 0 : r.get("slot_no", Integer.class)))
        .toList();
  }

  private List<ListingTemplateAssetGroupRow> groupRows(UUID tenant, UUID templateId) {
    return sql.fetch(
            """
            select id,group_code,group_kind,tag_code,asset_ids,max_count,slot_no
            from listing_template_asset_group
            where tenant_id=? and template_id=?
            order by slot_no, id
            """,
            tenant, templateId)
        .stream()
        .map(
            r -> {
              UUID[] ids = r.get("asset_ids", UUID[].class);
              return new ListingTemplateAssetGroupRow(
                  r.get("id", UUID.class),
                  r.get("group_code", String.class),
                  r.get("group_kind", String.class),
                  r.get("tag_code", String.class),
                  ids == null ? List.of() : List.of(ids),
                  r.get("max_count", Integer.class) == null ? 9 : r.get("max_count", Integer.class),
                  r.get("slot_no", Integer.class) == null ? 0 : r.get("slot_no", Integer.class));
            })
        .toList();
  }

  private ListingTemplateRow templateRow(Record r) {
    String scope = r.get("scope", String.class);
    String scopeTarget = "CATEGORY".equals(scope) ? r.get("category_path", String.class) : r.get("shop_code", String.class);
    return new ListingTemplateRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        scope,
        scopeTarget == null ? "" : scopeTarget,
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("marketplace_id", UUID.class),
        r.get("marketplace_code", String.class),
        r.get("language", String.class),
        r.get("status", String.class),
        r.get("field_count", Integer.class) == null ? 0 : r.get("field_count", Integer.class),
        r.get("group_count", Integer.class) == null ? 0 : r.get("group_count", Integer.class),
        r.get("required_count", Integer.class) == null ? 0 : r.get("required_count", Integer.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 模板定义（输入：模板定义 = 字段规则 + 图片组 + 必填校验）
  // ==================================================================

  /**
   * 新建 / 更新模板。
   *
   * <p>🔴 字段规则与图片组**整体替换**：增删式合并会让「当前有哪些规则」有两个答案。
   */
  public ListingTemplateDetail save(SaveListingTemplateRequest request) {
    UUID tenant = currentTenant();
    String code = text(request.code());
    String name = text(request.name());
    if (code.isEmpty() || name.isEmpty()) {
      throw unprocessable("模板编码与名称都不能为空");
    }
    String scope = text(request.scope()).toUpperCase(Locale.ROOT);
    if (!SCOPES.contains(scope)) {
      throw unprocessable("未知的适用范围：" + request.scope() + "。可选：" + String.join(" / ", SCOPES));
    }
    String categoryPath = blankToNull(request.categoryPath());
    UUID shopId = blankToNullUuid(request.shopId());
    UUID marketplaceId = blankToNullUuid(request.marketplaceId());
    if ("CATEGORY".equals(scope)) {
      if (categoryPath == null) {
        throw unprocessable("类目级模板必须给出 class 适用范围：categoryPath");
      }
      if (shopId != null) {
        throw unprocessable("类目级模板不能给店铺 id（两种适用范围互斥：给了就说不清按哪个生效）");
      }
    } else {
      if (shopId == null) {
        throw unprocessable("店铺级模板必须给出店铺 id");
      }
      if (categoryPath != null) {
        throw unprocessable("店铺级模板不能给类目路径（两种适用范围互斥：给了就说不清按哪个生效）");
      }
    }
    String status = blankToNull(request.status());
    if (status == null) {
      status = "DRAFT";
    }
    if (!STATUSES.contains(status)) {
      throw unprocessable("未知的模板状态：" + request.status() + "。可选：" + String.join(" / ", STATUSES));
    }
    String language = blankToNull(request.language());
    if (language == null) {
      language = "en-US";
    }

    List<ListingTemplateFieldInput> fields = safe(request.fields());
    if (fields.isEmpty()) {
      throw unprocessable("模板必须至少有一条字段填充规则（没有规则的模板套不出任何东西）");
    }
    List<ListingTemplateAssetGroupInput> groups = safe(request.assetGroups());
    validateFields(fields);
    validateGroups(groups);

    UUID requestedId = blankToNullUuid(request.id());
    UUID id = requestedId == null ? Ids.next() : requestedId;
    boolean created = requestedId == null;
    if (created) {
      sql.execute(
          """
          insert into listing_template
            (id,tenant_id,code,name,description,scope,platform_id,marketplace_id,
             category_path,shop_id,language,status,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
          on conflict (tenant_id, code) do update set
            name=excluded.name, description=excluded.description, scope=excluded.scope,
            platform_id=excluded.platform_id, marketplace_id=excluded.marketplace_id,
            category_path=excluded.category_path, shop_id=excluded.shop_id,
            language=excluded.language, status=excluded.status,
            version=listing_template.version+1, updated_at=now()
          """,
          id, tenant, code, name, text(request.description()), scope, request.platformId(),
          marketplaceId, categoryPath, shopId, language, status);
    } else {
      int updated =
          sql.execute(
              """
              update listing_template set
                name=?, description=?, scope=?, platform_id=?, marketplace_id=?,
                category_path=?, shop_id=?, language=?, status=?,
                version=version+1, updated_at=now()
              where tenant_id=? and id=?
              """,
              name, text(request.description()), scope, request.platformId(), marketplaceId,
              categoryPath, shopId, language, status, tenant, id);
      if (updated == 0) {
        throw notFound();
      }
    }

    // 规则与图片组整体替换。
    // 🔴 图片组**先**于字段规则写入：V195 的 guard_listing_template_field_group 触发器要求
    //    IMAGE_GROUP 来源引用的图片组已经存在（DB 层复核「引用不许指向不存在的东西」）。
    sql.execute("delete from listing_template_field where tenant_id=? and template_id=?", tenant, id);
    sql.execute("delete from listing_template_asset_group where tenant_id=? and template_id=?", tenant, id);
    for (ListingTemplateAssetGroupInput group : groups) {
      sql.execute(
          """
          insert into listing_template_asset_group
            (id,tenant_id,template_id,group_code,group_kind,tag_code,asset_ids,max_count,slot_no,created_at,updated_at)
          values (?,?,?,?,?,?,?::uuid[],?,?,now(),now())
          """,
          Ids.next(), tenant, id, text(group.groupCode()),
          text(group.groupKind()).toUpperCase(Locale.ROOT),
          blankToNull(group.tagCode()),
          pgUuidArray(safe(group.assetIds())),
          group.maxCount() == null ? 9 : group.maxCount(),
          group.slotNo() == null ? 0 : group.slotNo());
    }
    for (ListingTemplateFieldInput field : fields) {
      sql.execute(
          """
          insert into listing_template_field
            (id,tenant_id,template_id,field,source_kind,source_ref,value_expression,required,slot_no,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,now(),now())
          """,
          Ids.next(), tenant, id, text(field.field()).toUpperCase(Locale.ROOT),
          text(field.sourceKind()).toUpperCase(Locale.ROOT), text(field.sourceRef()),
          field.valueExpression() == null ? "" : field.valueExpression(),
          field.required(), field.slotNo() == null ? 0 : field.slotNo());
    }
    return load(id.toString());
  }

  private void validateFields(List<ListingTemplateFieldInput> fields) {
    Set<String> seen = new LinkedHashSet<>();
    for (ListingTemplateFieldInput field : fields) {
      String name = text(field.field()).toUpperCase(Locale.ROOT);
      if (!FIELD_KEYS.contains(name)) {
        throw unprocessable(
            "未知的字段：" + field.field() + "。可选：" + String.join(" / ", FIELD_KEYS.stream().sorted().toList()));
      }
      if (!seen.add(name)) {
        throw unprocessable("同一个字段「" + name + "」配了两条规则（一条规则一个字段，否则说不清最终值）");
      }
      String kind = text(field.sourceKind()).toUpperCase(Locale.ROOT);
      if (!SOURCE_KIND_KEYS.contains(kind)) {
        throw unprocessable(
            "未知的取值来源：" + field.sourceKind() + "。可选：" + String.join(" / ", SOURCE_KIND_KEYS.stream().sorted().toList()));
      }
      String ref = text(field.sourceRef());
      if ("LITERAL".equals(kind)) {
        if (text(field.valueExpression()).isEmpty()) {
          throw unprocessable("LITERAL 来源必须给出常量（valueExpression）");
        }
      } else if (ref.isEmpty()) {
        throw unprocessable(kind + " 来源必须给出引用（sourceRef）：属性码 / 内容模板编码 / 图片组编码 / listing 字段名");
      }
      if (!allowedSource(name, kind)) {
        throw unprocessable("字段「" + name + "」不支持来源「" + kind + "」");
      }
    }
  }

  private void validateGroups(List<ListingTemplateAssetGroupInput> groups) {
    Set<String> seen = new LinkedHashSet<>();
    for (ListingTemplateAssetGroupInput group : groups) {
      String groupCode = text(group.groupCode());
      if (groupCode.isEmpty()) {
        throw unprocessable("图片组必须有组编码");
      }
      if (!seen.add(groupCode)) {
        throw unprocessable("图片组编码「" + groupCode + "」重复了（模板内唯一）");
      }
      String kind = text(group.groupKind()).toUpperCase(Locale.ROOT);
      if (!Set.of("ASSET_LIST", "TAG").contains(kind)) {
        throw unprocessable("未知的取组方式：" + group.groupKind() + "。可选：ASSET_LIST / TAG");
      }
      if ("TAG".equals(kind)) {
        if (blankToNull(group.tagCode()) == null) {
          throw unprocessable("TAG 取组方式必须给出媒体库标签编码");
        }
      } else if (safe(group.assetIds()).isEmpty()) {
        throw unprocessable("ASSET_LIST 取组方式必须给出素材 id 列表");
      }
    }
  }

  /** 字段与来源的合法组合（与 V195 的 CHECK 逐字一致）。 */
  private static boolean allowedSource(String field, String kind) {
    return switch (field) {
      case "TITLE", "BULLETS" ->
          Set.of("LISTING_FIELD", "PRODUCT_ATTRIBUTE", "CONTENT_TEMPLATE", "LITERAL").contains(kind);
      case "IMAGES" -> Set.of("LISTING_FIELD", "IMAGE_GROUP", "LITERAL").contains(kind);
      case "CATEGORY_PATH" -> Set.of("LISTING_FIELD", "PRODUCT_ATTRIBUTE", "LITERAL").contains(kind);
      case "PLATFORM_CATEGORY_ID" -> Set.of("LISTING_FIELD", "LITERAL").contains(kind);
      default -> false;
    };
  }

  /** 启停模板。 */
  public ListingTemplateDetail setStatus(String rawId, String status) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    String wanted = text(status).toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(wanted)) {
      throw unprocessable("未知的模板状态：" + status + "。可选：" + String.join(" / ", STATUSES));
    }
    int updated =
        sql.execute(
            "update listing_template set status=?, version=version+1, updated_at=now() where tenant_id=? and id=?",
            wanted, tenant, id);
    if (updated == 0) {
      throw notFound();
    }
    return load(id.toString());
  }

  /** 删除模板：被套用过的模板不可删（历史套用记录要能回溯到用的是哪个模板）。 */
  public void remove(String rawId) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    Record t =
        sql.fetchOne(
            "select code from listing_template where tenant_id=? and id=?", tenant, id);
    if (t == null) {
      throw notFound();
    }
    long runs =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_template_apply where tenant_id=? and template_id=?",
                    tenant,
                    id))
            .longValue();
    if (runs > 0) {
      throw unprocessable(
          "刊登模板「"
              + t.get("code", String.class)
              + "」已被 "
              + runs
              + " 次套用引用，不可删除：请改用停用（停用后不能再套用，历史套用记录照常可读）");
    }
    int updated = sql.execute("delete from listing_template where tenant_id=? and id=?", tenant, id);
    if (updated == 0) {
      throw notFound();
    }
  }

  // ==================================================================
  // 适用范围：真数据判定（不是写死的字符串比较）
  // ==================================================================

  /**
   * 🔴 列出**真的落在**模板适用范围内的 listing。
   *
   * <p>这条查询是「适用范围判定有真实数据支撑」的证据：它是按作用域在真实 listing 上查出来的，
   * 界面上能看到「这个模板到底能套到哪些商品」。
   */
  public ListingTemplateMatchPage matches(String rawId, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    Record t =
        sql.fetchOne(
            """
            select t.id,t.scope,t.platform_id,t.marketplace_id,t.category_path,t.shop_id,s.code as shop_code
            from listing_template t
            left join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id
            where t.tenant_id=? and t.id=?
            """,
            tenant, id);
    if (t == null) {
      throw notFound();
    }

    StringBuilder where =
        new StringBuilder(
            """
             from listing l
             join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
             join platform p on p.id=s.platform_id
             where l.tenant_id=?
               and s.platform_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(t.get("platform_id", UUID.class));
    if (t.get("marketplace_id", UUID.class) != null) {
      where.append(" and l.marketplace_id = ?");
      binds.add(t.get("marketplace_id", UUID.class));
    }
    if ("CATEGORY".equals(t.get("scope", String.class))) {
      where.append(" and l.category_path = ?");
      binds.add(t.get("category_path", String.class));
    } else {
      where.append(" and l.shop_id = ?");
      binds.add(t.get("shop_id", UUID.class));
    }

    long total =
        ((Number) sql.fetchValue("select count(*)" + where, binds.toArray())).longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingTemplateMatchRow> items =
        sql.fetch(
                """
                select l.id,l.external_item_id,l.title,l.category_path,s.code as shop_code,p.code as platform_code,
                       exists(select 1 from listing_content_draft d
                                where d.tenant_id=l.tenant_id and d.listing_id=l.id and d.state='DRAFT') as has_draft
                """
                    + where
                    + " order by l.updated_at desc, l.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingTemplateMatchRow(
                        r.get("id", UUID.class),
                        r.get("external_item_id", String.class),
                        r.get("title", String.class),
                        r.get("shop_code", String.class),
                        r.get("platform_code", String.class),
                        r.get("category_path", String.class),
                        Boolean.TRUE.equals(r.get("has_draft", Boolean.class))))
            .toList();

    String scopeNote =
        "CATEGORY".equals(t.get("scope", String.class))
            ? "类目级：平台 "
                + t.get("platform_id", UUID.class)
                + " 上类目路径为「"
                + t.get("category_path", String.class)
                + "」的 listing"
            : "店铺级：店铺 "
                + (t.get("shop_code", String.class) == null ? t.get("shop_id", UUID.class) : t.get("shop_code", String.class))
                + " 下的 listing";
    return new ListingTemplateMatchPage(id, scopeNote, items, total, safePage, safeSize);
  }

  // ==================================================================
  // 🔴 套用（输出：套用后的内容草稿）
  // ==================================================================

  /**
   * 🔴 一键套用到目标商品。
   *
   * <p>整个写事务打开 {@code app.draft_only}（V177 的 {@code guard_listing_live_content}
   * 触发器在此期间拒绝对线上 listing 内容列的任何改动）—— 于是将来有人在模板路径里手滑加了句
   * {@code update listing set title=…} 会在测试期就炸，而不是等到全店标题被改完。
   *
   * <p>幂等语义（两条，互不打架）：
   *
   * <ul>
   *   <li>不带 idempotencyKey 重复套用 ⇒ **覆盖草稿**（重新求值后覆盖同一份 DRAFT），每次留一条记录；
   *   <li>带同一个 idempotencyKey 重复套用 ⇒ **返回第一次的那一份结果**，不重新求值、不再写草稿。
   * </ul>
   */
  public ListingTemplateApplyResult apply(ApplyListingTemplateRequest request, String bearer) {
    UUID tenant = currentTenant();
    UUID templateId = request.templateId();
    UUID listingId = request.listingId();
    String idempotencyKey = blankToNull(request.idempotencyKey());

    // ---- 幂等：同一个键命中 ⇒ 直接返回第一次的那一份结果
    if (idempotencyKey != null) {
      Record existing =
          sql.fetchOne(
              """
              select id from listing_template_apply
              where tenant_id=? and template_id=? and listing_id=? and idempotency_key=?
              """,
              tenant, templateId, listingId, idempotencyKey);
      if (existing != null) {
        return applyResult(existing.get("id", UUID.class), true);
      }
    }

    Template template = loadTemplate(tenant, templateId);
    if (!"ACTIVE".equals(template.status())) {
      throw unprocessable(
          "模板还没启用（当前状态 " + template.status() + "）：只有 ACTIVE 的模板能套用");
    }
    Target target = loadTarget(tenant, listingId);

    // ---- 🔴 作用域判定（真实数据，不是写死的字符串比较）
    String scopeProblem = scopeProblem(template, target);
    if (scopeProblem != null) {
      throw unprocessable(scopeProblem);
    }

    // ---- 🔴 实时求值（源商品改了，这次求值就会跟着变）
    Evaluation evaluation = evaluate(template, target, bearer);

    // ---- 写草稿（只写草稿表，线上 listing 一列都不碰）
    //      applyId 先出来：草稿的 last_operation_id 要指回这次套用（V177 的该列没有外键，
    //      批量编辑与模板套用都写它，指向各自那次改动）。
    UUID applyId = Ids.next();
    UUID existingDraftId = currentDraftId(tenant, listingId);
    UUID finalDraftId = existingDraftId == null ? Ids.next() : existingDraftId;
    boolean[] wrote = new boolean[1];
    withDraftOnly(
        () -> {
          wrote[0] = writeDraft(tenant, target, evaluation, finalDraftId, applyId);
        });
    // 一条都没求出来且本来就没有草稿 ⇒ 不指向任何草稿（这次套用没有产出任何内容）
    UUID targetDraftId = (wrote[0] || existingDraftId != null) ? finalDraftId : null;

    // ---- 落套用记录（append-only）
    boolean fail = evaluation.fields().stream().anyMatch(ListingTemplateApplyFieldRow::violation);
    int applied =
        (int) evaluation.fields().stream().filter(f -> "RESOLVED".equals(f.status())).count();
    int unknown =
        (int)
            evaluation.fields().stream()
                .filter(f -> "UNKNOWN_SOURCE".equals(f.status()) || "SOURCE_MISSING".equals(f.status()))
                .count();
    int violations = (int) evaluation.fields().stream().filter(ListingTemplateApplyFieldRow::violation).count();

    sql.execute(
        """
        insert into listing_template_apply
          (id,tenant_id,template_id,listing_id,target_draft_id,idempotency_key,
           validation_status,applied_count,unknown_count,violation_count,actor_id,note,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,now())
        """,
        applyId, tenant, templateId, listingId, targetDraftId, idempotencyKey,
        fail ? "FAIL" : "PASS", applied, unknown, violations, currentActor(), text(request.note()));

    for (ListingTemplateApplyFieldRow field : evaluation.fields()) {
      sql.execute(
          """
          insert into listing_template_apply_field
            (id,tenant_id,apply_id,field,source_kind,rule_ref,source_ref,status,value,
             owner_module,reason,violation,violation_code,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
          """,
          Ids.next(), tenant, applyId, field.field(), field.sourceKind(), field.ruleRef(),
          field.sourceRef(), field.status(), field.value(), field.ownerModule(), field.reason(),
          field.violation(), field.violationCode());
    }
    return applyResult(applyId, false);
  }

  /** 套用结果（含逐字段明细与违规清单）。 */
  public ListingTemplateApplyResult applyResult(UUID applyId, boolean replay) {
    Record r =
        sql.fetchOne(
            """
            select a.id,a.template_id,a.listing_id,a.target_draft_id,a.validation_status,
                   a.applied_count,a.unknown_count,a.violation_count,a.idempotency_key,a.note,a.created_at,
                   t.code as template_code,t.name as template_name,
                   l.external_item_id,s.code as shop_code,p.code as platform_code,
                   d.state as draft_state
            from listing_template_apply a
            join listing_template t on t.tenant_id=a.tenant_id and t.id=a.template_id
            join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            left join listing_content_draft d on d.tenant_id=a.tenant_id and d.id=a.target_draft_id
            where a.tenant_id = """
                + TENANT
                + " and a.id=?",
            applyId);
    if (r == null) {
      throw notFound();
    }
    List<ListingTemplateApplyFieldRow> fields =
        sql.fetch(
                """
                select field,source_kind,rule_ref,source_ref,status,value,owner_module,reason,violation,violation_code
                from listing_template_apply_field
                where tenant_id = """
                    + TENANT
                    + " and apply_id=? order by field",
                applyId)
            .stream()
            .map(
                x ->
                    new ListingTemplateApplyFieldRow(
                        x.get("field", String.class),
                        x.get("source_kind", String.class),
                        x.get("rule_ref", String.class),
                        x.get("source_ref", String.class),
                        x.get("status", String.class),
                        x.get("value", String.class),
                        x.get("owner_module", String.class),
                        x.get("reason", String.class),
                        Boolean.TRUE.equals(x.get("violation", Boolean.class)),
                        x.get("violation_code", String.class)))
            .toList();
    List<ListingTemplateApplyFieldRow> violations =
        fields.stream().filter(ListingTemplateApplyFieldRow::violation).toList();
    return new ListingTemplateApplyResult(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_code", String.class),
        r.get("template_name", String.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("target_draft_id", UUID.class),
        r.get("draft_state", String.class),
        r.get("validation_status", String.class),
        r.get("applied_count", Integer.class) == null ? 0 : r.get("applied_count", Integer.class),
        r.get("unknown_count", Integer.class) == null ? 0 : r.get("unknown_count", Integer.class),
        r.get("violation_count", Integer.class) == null ? 0 : r.get("violation_count", Integer.class),
        fields,
        violations,
        replay,
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 单次套用的详情（含逐字段求值明细与必填违规清单）。 */
  public ListingTemplateApplyResult applyDetail(String rawId) {
    return applyResult(parseId(rawId), false);
  }

  /** 套用记录列表（真分页 + 真筛选）。 */
  public ListingTemplateApplyPage applies(int page, int size, String templateId, String listingId) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    StringBuilder where = new StringBuilder(" where a.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (blankToNull(templateId) != null) {
      where.append(" and a.template_id = ?");
      binds.add(parseId(templateId));
    }
    if (blankToNull(listingId) != null) {
      where.append(" and a.listing_id = ?");
      binds.add(parseId(listingId));
    }
    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_template_apply a" + where, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingTemplateApplyRow> items =
        sql.fetch(
                """
                select a.id,a.template_id,a.listing_id,a.target_draft_id,a.validation_status,
                       a.applied_count,a.unknown_count,a.violation_count,a.idempotency_key,a.note,a.created_at,
                       t.code as template_code,t.name as template_name,l.external_item_id,
                       (select count(*) from listing_template_apply_field f
                         where f.tenant_id=a.tenant_id and f.apply_id=a.id) as field_count
                from listing_template_apply a
                join listing_template t on t.tenant_id=a.tenant_id and t.id=a.template_id
                join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
                """
                    + where
                    + " order by a.created_at desc, a.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                x ->
                    new ListingTemplateApplyRow(
                        x.get("id", UUID.class),
                        x.get("template_id", UUID.class),
                        x.get("template_code", String.class),
                        x.get("template_name", String.class),
                        x.get("listing_id", UUID.class),
                        x.get("external_item_id", String.class),
                        x.get("target_draft_id", UUID.class),
                        x.get("validation_status", String.class),
                        x.get("applied_count", Integer.class) == null ? 0 : x.get("applied_count", Integer.class),
                        x.get("unknown_count", Integer.class) == null ? 0 : x.get("unknown_count", Integer.class),
                        x.get("violation_count", Integer.class) == null ? 0 : x.get("violation_count", Integer.class),
                        x.get("field_count", Integer.class) == null ? 0 : x.get("field_count", Integer.class),
                        x.get("idempotency_key", String.class),
                        x.get("note", String.class),
                        x.get("created_at", OffsetDateTime.class)))
            .toList();
    return new ListingTemplateApplyPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 求值引擎
  // ==================================================================

  /** 模板（本体 + 规则 + 图片组）。 */
  private record Template(
      UUID id,
      String code,
      String name,
      String scope,
      UUID platformId,
      UUID marketplaceId,
      String categoryPath,
      UUID shopId,
      String language,
      String status,
      List<ListingTemplateFieldRow> fields,
      List<ListingTemplateAssetGroupRow> groups) {}

  /** 套用目标（listing + 它的店铺 / 平台 / 站点 / 内部 SKU / 当前内容快照）。 */
  private record Target(
      UUID id,
      String externalItemId,
      String title,
      String categoryPath,
      String platformCategoryId,
      UUID shopId,
      String shopCode,
      UUID platformId,
      String platformCode,
      UUID marketplaceId,
      long version,
      OffsetDateTime updatedAt,
      UUID internalSkuId,
      String snapshotTitle,
      List<String> snapshotBullets,
      List<String> snapshotImages) {}

  /** 一次求值的结果。 */
  private record Evaluation(List<ListingTemplateApplyFieldRow> fields) {}

  /** 平台维度上限（标题字数 / 图片张数 / 卖点数）。 */
  private record Limits(int titleMax, int imageMax, int bulletMax) {}

  private Template loadTemplate(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            "select id,code,name,scope,platform_id,marketplace_id,category_path,shop_id,language,status"
                + " from listing_template where tenant_id=? and id=?",
            tenant, id);
    if (r == null) {
      throw notFound();
    }
    return new Template(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("scope", String.class),
        r.get("platform_id", UUID.class),
        r.get("marketplace_id", UUID.class),
        r.get("category_path", String.class),
        r.get("shop_id", UUID.class),
        r.get("language", String.class),
        r.get("status", String.class),
        fieldRows(tenant, id),
        groupRows(tenant, id));
  }

  private Target loadTarget(UUID tenant, UUID listingId) {
    Record r =
        sql.fetchOne(
            """
            select l.id,l.external_item_id,l.title,l.category_path,l.platform_category_id,
                   l.shop_id,s.code as shop_code,s.platform_id,p.code as platform_code,
                   l.marketplace_id,l.version,l.updated_at,
                   (select lv.internal_sku_id from listing_variant lv
                     where lv.tenant_id=l.tenant_id and lv.listing_id=l.id and lv.internal_sku_id is not null
                     order by lv.external_variant_id, lv.id limit 1) as internal_sku_id
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            where l.tenant_id=? and l.id=?
            """,
            tenant, listingId);
    if (r == null) {
      throw notFound();
    }
    Record snap =
        sql.fetchOne(
            """
            select title,bullet_points,images from listing_content_snapshot
            where tenant_id=? and listing_id=? and is_current
            order by captured_at desc, id desc limit 1
            """,
            tenant, listingId);
    String[] bullets = snap == null ? null : snap.get("bullet_points", String[].class);
    String images = snap == null ? null : jsonText(snap.get("images", Object.class));
    return new Target(
        r.get("id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("category_path", String.class),
        r.get("platform_category_id", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("marketplace_id", UUID.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("internal_sku_id", UUID.class),
        snap == null ? null : snap.get("title", String.class),
        bullets == null ? List.of() : List.of(bullets),
        images == null ? List.of() : arrayValue(images));
  }

  /** 🔴 作用域判定：返回 null 表示通过，否则返回一条能直接给人看的理由。 */
  private String scopeProblem(Template template, Target target) {
    if (!template.platformId().equals(target.platformId())) {
      return "模板属于平台 "
          + template.platformId()
          + "，目标商品属于平台 "
          + target.platformId()
          + "：内容按平台 + 站点隔离，跨平台套用会把两个平台的内容搅在一起";
    }
    if (template.marketplaceId() != null && !template.marketplaceId().equals(target.marketplaceId())) {
      return "模板限定站点 "
          + template.marketplaceId()
          + "，目标商品在站点 "
          + (target.marketplaceId() == null ? "（未设站点）" : target.marketplaceId())
          + "：跨站点套用会带上不属于该站点的内容";
    }
    if ("CATEGORY".equals(template.scope())) {
      if (!template.categoryPath().equals(target.categoryPath())) {
        return "模板适用于类目路径「"
            + template.categoryPath()
            + "」，目标商品的类目路径是「"
            + target.categoryPath()
            + "」";
      }
      return null;
    }
    if (!template.shopId().equals(target.shopId())) {
      return "模板适用于店铺 "
          + template.shopId()
          + "，目标商品在店铺 "
          + target.shopId()
          + "：店铺级模板只对本店铺生效";
    }
    return null;
  }

  /**
   * 🔴 逐条规则实时求值。
   *
   * <p>这是「模板存规则不存快照」的落点：每条规则都在**这一次**读源商品（属性 / 素材 / 文案），
   * 所以源商品改了，下一次套用算出来的值就跟着变。
   */
  private Evaluation evaluate(Template template, Target target, String bearer) {
    Limits limits = platformLimits(template.platformId());
    List<ListingTemplateApplyFieldRow> rows = new ArrayList<>();

    // 商品属性只读一次（多条规则可能引用不同属性）
    ListingTemplateSourcePort.ProductResult product = null;
    boolean productLoaded = false;

    for (ListingTemplateFieldRow rule : template.fields()) {
      String ruleRef = rule.sourceKind() + ":" + rule.sourceRef();
      String sourceValue = null;
      String status = "RESOLVED";
      String ownerModule = null;
      String reason = "";
      List<String> listValue = null;

      switch (rule.sourceKind()) {
        case "LITERAL" -> {
          sourceValue = rule.valueExpression();
          if (text(sourceValue).isEmpty()) {
            status = "EMPTY";
            reason = "LITERAL 规则没有给出常量";
          }
        }
        case "LISTING_FIELD" -> {
          switch (rule.sourceRef()) {
            case "TITLE" -> {
              sourceValue = target.title();
              if (text(sourceValue).isEmpty()) {
                status = "EMPTY";
                reason = "目标 listing 的标题是空的";
              }
            }
            case "CATEGORY_PATH" -> {
              sourceValue = target.categoryPath();
              if (text(sourceValue).isEmpty()) {
                status = "EMPTY";
                reason = "目标 listing 的类目路径是空的";
              }
            }
            case "PLATFORM_CATEGORY_ID" -> {
              sourceValue = target.platformCategoryId();
              if (text(sourceValue).isEmpty()) {
                status = "EMPTY";
                reason = "目标 listing 的平台类目 ID 是空的";
              }
            }
            case "BULLETS" -> {
              listValue = target.snapshotBullets();
              if (listValue.isEmpty()) {
                status = "EMPTY";
                reason = "目标 listing 没有当前内容快照，卖点取不到（快照由 02 同步 / 4.4.1 采集写入）";
              }
            }
            case "IMAGES" -> {
              listValue = target.snapshotImages();
              if (listValue.isEmpty()) {
                status = "EMPTY";
                reason = "目标 listing 没有当前内容快照，图片取不到（快照由 02 同步 / 4.4.1 采集写入）";
              }
            }
            default -> {
              status = "SOURCE_MISSING";
              ownerModule = "4.2.1.1 刊登编辑器";
              reason = "未知的 listing 字段：" + rule.sourceRef();
            }
          }
        }
        case "PRODUCT_ATTRIBUTE" -> {
          if (target.internalSkuId() == null) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_PRODUCT;
            reason = "目标 listing 没有映射到内部 SKU，取不到商品属性";
          } else {
            if (!productLoaded) {
              product = sources.productAttributes(target.internalSkuId(), bearer);
              productLoaded = true;
            }
            if (!product.available()) {
              status = "UNKNOWN_SOURCE";
              ownerModule = OWNER_PRODUCT;
              reason = product.reason();
            } else if (!product.attributes().containsKey(rule.sourceRef())) {
              status = "SOURCE_MISSING";
              ownerModule = OWNER_PRODUCT;
              reason =
                  "属性码「"
                      + rule.sourceRef()
                      + "」在这个 SKU 上不存在（存在的属性："
                      + (product.attributes().isEmpty() ? "无" : String.join("、", product.attributes().keySet()))
                      + "）";
            } else {
              sourceValue = product.attributes().get(rule.sourceRef());
              if (text(sourceValue).isEmpty()) {
                status = "EMPTY";
                reason = "属性「" + rule.sourceRef() + "」在这个 SKU 上没有生效值";
              }
            }
          }
        }
        case "CONTENT_TEMPLATE" -> {
          if (target.internalSkuId() == null) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_CONTENT;
            reason = "目标 listing 没有映射到内部 SKU，文案库渲染不了";
          } else {
            ListingTemplateSourcePort.ContentResult content =
                sources.renderContentTemplate(
                    rule.sourceRef(), target.internalSkuId(), template.language(), bearer);
            if (!content.available()) {
              status = "UNKNOWN_SOURCE";
              ownerModule = OWNER_CONTENT;
              reason = content.reason();
            } else if (content.templateMissing()) {
              status = "SOURCE_MISSING";
              ownerModule = OWNER_CONTENT;
              reason = "文案模板编码「" + rule.sourceRef() + "」在文案库里不存在";
            } else if (content.issueCount() > 0) {
              status = "EMPTY";
              ownerModule = OWNER_CONTENT;
              reason =
                  "文案模板「"
                      + rule.sourceRef()
                      + "」渲染出 "
                      + content.issueCount()
                      + " 条缺失变量，正文不完整（变量未绑定必须显式提示，不允许把占位符原样发到平台）";
            } else if (text(content.text()).isEmpty()) {
              status = "EMPTY";
              reason = "文案模板「" + rule.sourceRef() + "」渲染出空正文";
            } else {
              sourceValue = content.text();
            }
          }
        }
        case "IMAGE_GROUP" -> {
          ListingTemplateAssetGroupRow group =
              template.groups().stream()
                  .filter(g -> g.groupCode().equals(rule.sourceRef()))
                  .findFirst()
                  .orElse(null);
          if (group == null) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_MEDIA;
            reason = "图片组「" + rule.sourceRef() + "」不在本模板里";
          } else {
            List<String> keys = new ArrayList<>();
            if ("TAG".equals(group.groupKind())) {
              ListingTemplateSourcePort.MediaResult media =
                  sources.mediaAssetsByTag(group.tagCode(), group.maxCount(), bearer);
              if (!media.available()) {
                status = "UNKNOWN_SOURCE";
                ownerModule = OWNER_MEDIA;
                reason = media.reason();
              } else {
                List<String> enabled = enabledImageKeys(media.assets());
                if (enabled.isEmpty()) {
                  status = "EMPTY";
                  reason = "媒体库标签「" + group.tagCode() + "」下没有启用的图片素材";
                } else {
                  keys.addAll(enabled);
                  if (keys.size() > group.maxCount()) {
                    reason =
                        "标签「"
                            + group.tagCode()
                            + "」下共 "
                            + keys.size()
                            + " 张启用素材，按该组上限 "
                            + group.maxCount()
                            + " 张取前 "
                            + group.maxCount()
                            + " 张（按媒体库返回顺序）";
                    keys = new ArrayList<>(keys.subList(0, group.maxCount()));
                  }
                }
              }
            } else {
              ListingTemplateSourcePort.MediaResult media =
                  sources.mediaAssets(group.assetIds(), bearer);
              if (!media.available()) {
                status = "UNKNOWN_SOURCE";
                ownerModule = OWNER_MEDIA;
                reason = media.reason();
              } else {
                List<String> enabled = enabledImageKeys(media.assets());
                if (enabled.size() < group.assetIds().size()) {
                  reason =
                      "该组列了 "
                          + group.assetIds().size()
                          + " 个素材，其中 "
                          + (group.assetIds().size() - enabled.size())
                          + " 个在上游读不到或不是启用的图片";
                }
                if (enabled.isEmpty()) {
                  status = "EMPTY";
                  reason = "该图片组里的素材在上游都读不到或不是启用的图片";
                } else if (enabled.size() > group.maxCount()) {
                  status = "LIMIT_EXCEEDED";
                  ownerModule = OWNER_MEDIA;
                  reason =
                      "该图片组有 "
                          + enabled.size()
                          + " 张启用的图片，超出该组上限 "
                          + group.maxCount()
                          + " 张；显式跳过而不是截断（截断会让运营以为只放了一张）";
                } else {
                  keys.addAll(enabled);
                }
              }
            }
            if ("RESOLVED".equals(status)) {
              listValue = keys;
              if (listValue.size() > limits.imageMax()) {
                status = "LIMIT_EXCEEDED";
                ownerModule = OWNER_MEDIA;
                reason =
                    "该组取到 "
                        + listValue.size()
                        + " 张图片，超出该平台上限 "
                        + limits.imageMax()
                        + " 张；显式跳过而不是截断";
                listValue = null;
              }
            }
          }
        }
        default -> {
          status = "SOURCE_MISSING";
          ownerModule = "4.2.2.1 刊登模板";
          reason = "未知的取值来源：" + rule.sourceKind();
        }
      }

      // ---- 取值表达式：常量前后缀 + {{占位符}}
      String finalValue = null;
      List<String> finalList = listValue;
      if (listValue != null && sourceValue == null) {
        // 列表型来源（卖点 / 图片）在表达式里以「多行文本」出现
        sourceValue = String.join("\n", listValue);
      }
      if ("RESOLVED".equals(status) && !"LITERAL".equals(rule.sourceKind())) {
        String expression = rule.valueExpression() == null ? "" : rule.valueExpression();
        if (expression.isEmpty()) {
          finalValue = sourceValue;
        } else {
          ExpressionResult resolved = resolveExpression(expression, sourceValue, product);
          if (resolved.missing() != null) {
            status = "EMPTY";
            reason = "取值表达式里的占位符 {{" + resolved.missing() + "}} 取不到值";
          } else {
            finalValue = resolved.value();
          }
        }
        if ("RESOLVED".equals(status) && "BULLETS".equals(rule.field()) && finalList == null) {
          finalList = splitLines(finalValue);
        }
        if ("RESOLVED".equals(status) && "IMAGES".equals(rule.field()) && finalList == null) {
          finalList = splitLines(finalValue);
        }
      } else if ("RESOLVED".equals(status) && "LITERAL".equals(rule.sourceKind())) {
        finalValue = sourceValue;
        if ("BULLETS".equals(rule.field()) || "IMAGES".equals(rule.field())) {
          finalList = splitLines(finalValue);
        }
      } else if ("RESOLVED".equals(status)) {
        finalValue = sourceValue;
      }
      if ("RESOLVED".equals(status) && finalValue == null && finalList == null) {
        status = "EMPTY";
        reason = "规则求值结果是空的（没有任何可写入草稿的内容）";
        finalValue = null;
      }

      // ---- 物理上限（草稿表的列宽 / 平台上限）
      if ("RESOLVED".equals(status)) {
        if ("TITLE".equals(rule.field()) && codePointLength(finalValue) > limits.titleMax()) {
          status = "LIMIT_EXCEEDED";
          reason =
              "标题 "
                  + codePointLength(finalValue)
                  + " 字，超出该平台上限 "
                  + limits.titleMax()
                  + " 字；显式跳过而不是截断（截断会让运营以为标题没超长）";
        }
        if ("BULLETS".equals(rule.field())
            && finalList != null
            && finalList.size() > limits.bulletMax()) {
          status = "LIMIT_EXCEEDED";
          reason =
              "卖点 "
                  + finalList.size()
                  + " 条，超出该平台上限 "
                  + limits.bulletMax()
                  + " 条；显式跳过而不是截断";
          finalList = null;
        }
        if ("CATEGORY_PATH".equals(rule.field())
            && codePointLength(finalValue) > CATEGORY_PATH_MAX) {
          status = "LIMIT_EXCEEDED";
          reason = "类目路径超出草稿的 " + CATEGORY_PATH_MAX + " 字上限；显式跳过而不是截断";
        }
        if ("PLATFORM_CATEGORY_ID".equals(rule.field())
            && codePointLength(finalValue) > PLATFORM_CATEGORY_ID_MAX) {
          status = "LIMIT_EXCEEDED";
          reason =
              "平台类目 ID 超出草稿的 " + PLATFORM_CATEGORY_ID_MAX + " 字上限；显式跳过而不是截断";
        }
      }

      String storedValue =
          finalList != null ? writeJson(finalList) : (finalValue == null ? null : finalValue);
      boolean violation = rule.required() && !"RESOLVED".equals(status);
      String violationCode = "";
      if (violation) {
        violationCode =
            switch (status) {
              case "EMPTY" -> "REQUIRED_EMPTY";
              case "SOURCE_MISSING" -> "REQUIRED_SOURCE_MISSING";
              case "UNKNOWN_SOURCE" -> "REQUIRED_SOURCE_UNKNOWN";
              case "LIMIT_EXCEEDED" -> "REQUIRED_LIMIT_EXCEEDED";
              default -> "REQUIRED_FAILED";
            };
      }
      rows.add(
          new ListingTemplateApplyFieldRow(
              rule.field(),
              rule.sourceKind(),
              ruleRef,
              rule.sourceRef(),
              status,
              "RESOLVED".equals(status) ? storedValue : null,
              ownerModule,
              reason,
              violation,
              violationCode));
    }
    return new Evaluation(rows);
  }

  /** 取值表达式的求值结果（{@code missing} 非空表示某个占位符取不到值）。 */
  private record ExpressionResult(String value, String missing) {}

  /**
   * 求值表达式：把 {@code {{value}}} 换成来源值，把 {@code {{属性码}}} 换成商品属性。
   *
   * <p>🔴 占位符取不到值时返回 missing（**不是**把占位符原样留着）——
   * 「变量未绑定必须显式提示」是 4.2.2.2 的硬要求，这里对取值表达式同样适用。
   */
  private ExpressionResult resolveExpression(
      String expression, String sourceValue, ListingTemplateSourcePort.ProductResult product) {
    String result = expression;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)}}").matcher(expression);
    StringBuffer buffer = new StringBuffer();
    while (matcher.find()) {
      String name = matcher.group(1);
      String replacement;
      if ("value".equals(name)) {
        replacement = sourceValue == null ? "" : sourceValue;
      } else {
        if (product == null || !product.available()) {
          return new ExpressionResult(null, name);
        }
        String attribute = product.attributes().get(name);
        if (attribute == null) {
          return new ExpressionResult(null, name);
        }
        replacement = attribute;
      }
      matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    return new ExpressionResult(buffer.toString(), null);
  }

  /** 启用的图片素材的当前存储键（顺序即媒体库返回顺序）。 */
  private static List<String> enabledImageKeys(List<ListingTemplateSourcePort.MediaAssetView> assets) {
    List<String> keys = new ArrayList<>();
    for (ListingTemplateSourcePort.MediaAssetView asset : safe(assets)) {
      if (!asset.enabled()) {
        continue;
      }
      if (!"IMAGE".equals(asset.mediaType())) {
        continue;
      }
      if (blankToNull(asset.storageKey()) == null) {
        continue;
      }
      keys.add(asset.storageKey());
    }
    return keys;
  }

  /** 多行文本拆成多值（卖点 / 图片组按行拆）。 */
  private static List<String> splitLines(String value) {
    List<String> out = new ArrayList<>();
    if (value == null) {
      return out;
    }
    for (String line : value.split("\\r?\\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /**
   * 平台维度上限：取 V177 {@code listing_bulk_template} 里该平台**最严**的一组上限。
   *
   * <p>那个表是本仓已交付的「按平台差异化上限」配置（同一套平台在 Amazon 允许 200 字标题、
   * 在 TikTok 只允许 60 字）。没有该平台的行时用它的列默认值（500 / 9 / 10 ——
   * 那正是草稿表与 listing 的物理列宽与既有默认，不是随便定的数）。
   */
  private Limits platformLimits(UUID platformId) {
    Record r =
        sql.fetchOne(
            """
            select min(title_max_length) as title_max,
                   min(image_max_count) as image_max,
                   min(bullet_max_count) as bullet_max
            from listing_bulk_template
            where platform_id=?
            """,
            platformId);
    if (r == null || r.get("title_max", Integer.class) == null) {
      return new Limits(TITLE_MAX, 9, 10);
    }
    return new Limits(
        r.get("title_max", Integer.class),
        r.get("image_max", Integer.class) == null ? 9 : r.get("image_max", Integer.class),
        r.get("bullet_max", Integer.class) == null ? 10 : r.get("bullet_max", Integer.class));
  }

  // ==================================================================
  // 草稿写入（🔴 唯一的写路径，且只写草稿表）
  // ==================================================================

  private UUID currentDraftId(UUID tenant, UUID listingId) {
    Object id =
        sql.fetchValue(
            "select id from listing_content_draft where tenant_id=? and listing_id=? and state='DRAFT'",
            tenant, listingId);
    return id == null ? null : UUID.fromString(String.valueOf(id));
  }

  /**
   * 把求值结果并进草稿。
   *
   * <p>ON CONFLICT 命中的是 V177 的 {@code listing_content_draft_one_active}（部分唯一索引，
   * 只约束 DRAFT 行），所以已发布 / 已废弃的草稿会作为历史留档，本次新开一份 DRAFT。
   * 合并时 {@code touched_fields} 取并集，且**只覆盖本次真的拟改的列** ——
   * 没拟改的列保持原样（发布时不会被误覆盖）。
   */
  private boolean writeDraft(
      UUID tenant, Target target, Evaluation evaluation, UUID draftId, UUID applyId) {
    String title = null;
    String[] bullets = null;
    String images = null;
    String categoryPath = null;
    String platformCategoryId = null;
    List<String> touched = new ArrayList<>();

    for (ListingTemplateApplyFieldRow field : evaluation.fields()) {
      if (!"RESOLVED".equals(field.status())) {
        continue;
      }
      switch (field.field()) {
        case "TITLE" -> {
          title = field.value();
          touched.add("TITLE");
        }
        case "BULLETS" -> {
          bullets = arrayValue(field.value()).toArray(String[]::new);
          touched.add("BULLETS");
        }
        case "IMAGES" -> {
          images = field.value();
          touched.add("IMAGES");
        }
        case "CATEGORY_PATH" -> {
          categoryPath = field.value();
          touched.add("CATEGORY_PATH");
        }
        case "PLATFORM_CATEGORY_ID" -> {
          platformCategoryId = field.value();
          touched.add("PLATFORM_CATEGORY_ID");
        }
        default -> { }
      }
    }
    if (touched.isEmpty()) {
      return false;
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
        draftId, tenant, target.id(), title,
        bullets == null ? null : pgTextArray(List.of(bullets)),
        images, categoryPath, platformCategoryId,
        pgTextArray(touched), target.version(), target.updatedAt(), applyId);
    return true;
  }

  /**
   * 🔴 打开 {@code app.draft_only}，期间线上 listing 的内容列由 DB 触发器拒写。
   *
   * <p>用 {@code set_config(...,true)} —— 第三个参数 true 表示**事务局部**：连接归还池子后不会把
   * 这个标记带给下一个租户。
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

  private String jsonText(Object raw) {
    if (raw == null) {
      return "[]";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value == null || value.isBlank()) {
      return "[]";
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static Object[] concat(List<Object> binds) {
    return binds.toArray();
  }

  /** 供前端做「模板引用的是规则不是快照」的说明用：模板表里没有值列。 */
  public Map<String, Object> invariants() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rulesOnly", true);
    out.put("draftOnly", true);
    out.put("ownerModules", List.of(OWNER_PRODUCT, OWNER_CONTENT, OWNER_MEDIA));
    return out;
  }
}
