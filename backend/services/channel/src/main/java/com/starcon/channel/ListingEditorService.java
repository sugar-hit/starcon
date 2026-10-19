package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.1.1 刊登编辑器。
 *
 * <p>蓝图：「单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、A+
 * 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。输入：源商品（03
 * SKU/SPU）、目标店铺与站点、平台字段要求（02 能力矩阵）；用户编辑内容；已填内容的草稿。输出：内容草稿（按平台 +
 * 站点 + 语言分别保存）；提交发布或保存模板的动作；校验结果。关系：上游 = 03 商品主数据、03 媒体与文案、02 平台字段要求；下游 =
 * 4.3.1.1 发布。<b>表单字段由平台要求驱动生成</b>，不是固定一套表单套所有平台——平台能力矩阵变化时表单自动变化。」
 *
 * <p>🔴 本类里有三处**结构**上的不变量：
 *
 * <ol>
 *   <li><b>表单字段由数据推导</b>：字段集来自 {@code platform_listing_field_requirement}
 *       （V194）+ {@code platform.capabilities}（2.1.1.2 已交付），推导逻辑在
 *       {@link ListingEditorFieldRules}（纯函数）。**本类里没有一处平台分支** ——
 *       平台改版 = 改注册表 / 改能力矩阵，表单自动跟着变。
 *   <li><b>草稿与线上物理分离</b>：本类**只**写 {@code listing_editor_draft} /
 *       {@code listing_editor_submission}，**没有任何一句写线上 {@code listing} 的 SQL**；
 *       并且整个写事务都打开 {@code app.draft_only}，配合 V194 的
 *       {@code guard_listing_editor_live_content} 触发器，将来谁在编辑路径上手滑加了句
 *       {@code update listing set …} 会在测试期就炸掉。
 *   <li><b>提交审核只是入队</b>：{@link #submit} 的产出是 QUEUED 的入队记录，
 *       <b>不是"已发布"</b> —— 真正发给平台是 4.3.1.1 一键刊登（集成头未交付）。
 * </ol>
 *
 * <p>跨服务只经授权 HTTP API 读（{@link EditorSourceQueryPort}）：03 商品主数据与 03 媒体库。
 * 租户不由本服务传递 —— 转发的是调用方自己的 bearer，上游仍按已认证成员关系解析租户。
 */
@Service
public class ListingEditorService {

  private static final String DRAFT_ISOLATION_NOTE =
      "本模块只写草稿表（listing_editor_draft），线上 listing 的内容列在编辑事务期间由 DB 触发器拒写；"
          + "发布是 4.3.1.1 一键刊登的职责，界面上没有「直接上线」这个动作。";

  private static final String SUBMIT_NOTE =
      "提交审核 = 入队：内容与校验证据冻结进 listing_editor_submission（QUEUED）。"
          + "真正发给平台是 4.3.1.1 一键刊登（尚未交付），所以这里的状态是「已入队」，不是「已发布」。";

  private static final String TEMPLATE_NOTE =
      "一键套用刊登模板暂不可用：4.2.2.1 刊登模板尚未交付（它的表与端点都不存在），"
          + "本模块不读它的数据也不调它的端点。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final EditorSourceQueryPort source;

  public ListingEditorService(DSLContext sql, ObjectMapper json, EditorSourceQueryPort source) {
    this.sql = sql;
    this.json = json;
    this.source = source;
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

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("内容序列化失败", e);
    }
  }

  /** 读 jsonb → JsonNode；读不出来就是 null（**不编一个空对象冒充有值**）。 */
  private com.fasterxml.jackson.databind.JsonNode readNode(Object raw) {
    try {
      String text = jsonText(raw);
      if (text == null || text.isBlank()) {
        return null;
      }
      return json.readTree(text);
    } catch (Exception e) {
      return null;
    }
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 未交付的三段如实报出：刊登模板套用（4.2.2.1）、一键刊登派发（4.3.1.1）、合规预检（4.2.3.2）、
   * 类目属性细则（4.2.3.1，在 catalog 侧）、03 文案库套用（3.4.2.2）。
   */
  public EditorCapabilities capabilities() {
    return new EditorCapabilities(
        List.of(
            new EditorCapabilityDimension(
                "platform_field_requirements",
                "🔴 平台字段要求：哪些栏要填、必填与否、长度 / 条数上限",
                true,
                "platform_listing_field_requirement（V194）+ platform.capabilities（2.1.1.2）",
                "",
                "字段集与上限都从注册表推导，代码里没有平台分支；平台改版改注册表，表单自动跟着变"),
            new EditorCapabilityDimension(
                "dynamic_form",
                "🔴 按目标平台动态渲染字段（不是固定一套表单套所有平台）",
                true,
                "ListingEditorFieldRules.resolve",
                "",
                "每个平台的字段集、必填性、上限都是各自解出来的；TikTok 没有 A+ 模块、SHOPIFY 没有要点，"
                    + "都由注册表决定"),
            new EditorCapabilityDimension(
                "requirement_unknown_state",
                "🔴 「不知道」是一个状态：要求取不到时显式 UNKNOWN + ownerModule，不回退成「没有约束」",
                true,
                "ListingEditorFieldRules.capabilityState / constraintState",
                "",
                "能力矩阵没声明 ⇒ UNKNOWN；细则归别的模块 ⇒ DELEGATED。两者都带归属模块，界面照原样显示"),
            new EditorCapabilityDimension(
                "draft_isolation",
                "🔴 草稿态与线上态物理分离：编辑器只写草稿表，线上 listing 内容列在编辑事务期间被 DB 拒写",
                true,
                "listing_editor_draft + guard_listing_editor_live_content（V194）",
                "",
                "这是「编辑器误改线上」的唯一防线；API 形状上也没有任何「保存到线上」的端点"),
            new EditorCapabilityDimension(
                "platform_site_locale_isolation",
                "🔴 内容按平台 + 站点 + 语言分别保存，同一商品在不同平台的内容互不污染",
                true,
                "listing_editor_draft 的两条部分唯一索引（V194）",
                "",
                "唯一约束在 DB 层表达，不靠应用层自觉；草稿的平台必须等于目标店铺的平台，由触发器钉住"),
            new EditorCapabilityDimension(
                "multi_language",
                "多语言 Tab：每个语言一份独立草稿（真存多语言版本，不是界面上摆几个 Tab）",
                true,
                "listing_editor_draft.locale",
                "",
                "一个语言改内容不影响另一个语言的草稿"),
            new EditorCapabilityDimension(
                "validation",
                "校验结果真算：必填缺失、长度超限、条数超限，逐条指到具体字段",
                true,
                "ListingEditorFieldRules.validate",
                "",
                "违规项带着字段键、限值与实际值，前端可以直接定位到那一栏"),
            new EditorCapabilityDimension(
                "source_product",
                "源商品（03 SKU / SPU 主数据）只读引用与属性预填",
                true,
                "catalog GET /catalog/skus/{id}（3.1.2.2，经 EditorSourceQueryPort）",
                "",
                "源商品在 catalog，本服务不跨库读，只经授权 HTTP API"),
            new EditorCapabilityDimension(
                "media_library",
                "媒体库素材检索（图片集字段的素材来源）",
                true,
                "catalog GET /catalog/media（3.4.1.1，经 EditorSourceQueryPort）",
                "",
                "图片集填的是素材存储键；素材本体在 catalog 侧"),
            new EditorCapabilityDimension(
                "publish_dispatch",
                "🔴 提交审核后的派发（真正发给平台）",
                false,
                "4.3.1.1 一键刊登",
                "4.3.1.1",
                "本模块只入队；界面上的状态是「已入队」，说成「已发布」就是撒谎"),
            new EditorCapabilityDimension(
                "listing_template_apply",
                "🔴 一键套用刊登模板（类目级 / 店铺级字段填充规则）",
                false,
                "4.2.2.1 刊登模板",
                "4.2.2.1",
                "4.2.2.1 尚未交付，它的表与端点都不存在；本模块不读它的数据也不调它的端点，"
                    + "所以这个动作标为暂不可用并归属 4.2.2.1"),
            new EditorCapabilityDimension(
                "compliance_precheck",
                "🔴 合规预检（违禁词 / 侵权 / 图片不合规）",
                false,
                "4.2.3.2 合规预检",
                "4.2.3.2",
                "本模块只做**字段级**校验（必填 / 长度 / 条数），不做内容合规扫描；"
                    + "违禁词与侵权判定归 4.2.3.2"),
            new EditorCapabilityDimension(
                "category_attribute_requirements",
                "🔴 平台类目必填属性的**具体清单**",
                false,
                "4.2.3.1 平台类目映射（catalog 侧）",
                "4.2.3.1",
                "类目属性在 catalog 侧，本服务不跨库读；所以这一栏的约束状态是 DELEGATED，"
                    + "界面显示「细则归 4.2.3.1」，而不是假装本模块知道要填哪几个属性"),
            new EditorCapabilityDimension(
                "copy_library_apply",
                "🔴 从 03 文案库 / 描述模板套用文案",
                false,
                "3.4.2.2 描述与卖点库（catalog 侧）",
                "3.4.2.2",
                "本模块未接入文案库的填充接口：标题 / 要点 / 描述在编辑器内手填")),
        ListingEditorFieldRules.FIELD_ORDER,
        ListingEditorFieldRules.CONSTRAINT_STATES,
        ListingEditorFieldRules.CAPABILITY_STATES,
        ListingEditorFieldRules.REQUIREMENTS_STATES,
        DRAFT_ISOLATION_NOTE,
        SUBMIT_NOTE,
        TEMPLATE_NOTE,
        "上游 = 03 商品主数据（catalog，只读）、03 媒体库（catalog，只读）、02 平台字段要求（platform.capabilities）；"
            + "下游 = 4.3.1.1 一键刊登（本模块只把内容入队）。");
  }

  // ==================================================================
  // 表单 schema：🔴 字段集由平台要求推导
  // ==================================================================

  /** 目标店铺 + 站点 + 平台（一次查全，前端要显示的都在这行里）。 */
  private record Target(
      UUID shopId,
      String shopCode,
      UUID platformId,
      String platformCode,
      String platformName,
      String supportStatus,
      String integration,
      Map<String, Boolean> capabilities,
      UUID marketplaceId,
      String marketplaceCode,
      String marketplaceName,
      String countryCode,
      String defaultLocale) {}

  private Target target(UUID shopId, UUID marketplaceId) {
    Record row =
        sql.fetchOne(
            """
            select s.id as shop_id, s.code as shop_code, s.platform_id,
                   p.code as platform_code, p.name as platform_name, p.support_status, p.integration,
                   p.capabilities,
                   m.id as marketplace_id, m.code as marketplace_code, m.name as marketplace_name,
                   m.country_code, m.default_locale
            from shop s
            join platform p on p.id = s.platform_id
            join marketplace m on m.id = ? and m.platform_id = s.platform_id
            join shop_marketplace sm on sm.tenant_id = s.tenant_id and sm.shop_id = s.id
                 and sm.marketplace_id = m.id and sm.status = 'ACTIVE'
            where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and s.id = ?
            """,
            marketplaceId,
            shopId);
    if (row == null) {
      throw unprocessable(
          "目标店铺不存在，或该站点不是这个店铺已启用的站点（站点必须先在店铺设置里启用）");
    }
    return new Target(
        row.get("shop_id", UUID.class),
        row.get("shop_code", String.class),
        row.get("platform_id", UUID.class),
        row.get("platform_code", String.class),
        row.get("platform_name", String.class),
        row.get("support_status", String.class),
        row.get("integration", String.class),
        capabilityMap(row.get("capabilities")),
        row.get("marketplace_id", UUID.class),
        row.get("marketplace_code", String.class),
        row.get("marketplace_name", String.class),
        row.get("country_code", String.class),
        row.get("default_locale", String.class));
  }

  /** 能力矩阵：{@code platform.capabilities} jsonb → 键 → 布尔。**键缺失 = 未声明**（不是 false）。 */
  private Map<String, Boolean> capabilityMap(Object raw) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    String text = jsonText(raw);
    if (text == null || text.isBlank() || "{}".equals(text)) {
      return result;
    }
    try {
      var node = json.readTree(text);
      if (node == null || !node.isObject()) {
        return result;
      }
      node.fields()
          .forEachRemaining(
              entry -> {
                var value = entry.getValue();
                if (value.isBoolean()) {
                  result.put(entry.getKey(), value.booleanValue());
                } else if (value.isTextual()) {
                  result.put(entry.getKey(), Boolean.parseBoolean(value.textValue()));
                }
              });
    } catch (Exception e) {
      return result;
    }
    return result;
  }

  /**
   * 🔴 从平台字段要求注册表解出字段集（**就近覆盖**：站点 + 类目 &gt; 站点 &gt; 类目 &gt; 平台）。
   *
   * <p>覆盖顺序在 SQL 里做（{@code distinct on + order by}），不在 Java 里做 —— 两处各做一次就会漂。
   */
  private List<ListingEditorFieldRules.Requirement> requirements(
      UUID platformId, UUID marketplaceId, String categoryPath) {
    List<Record> rows =
        sql.fetch(
            """
            select distinct on (r.field_key)
                   r.field_key, r.value_kind, r.requirement, r.min_length, r.max_length,
                   r.min_items, r.max_items, r.requires_capability, r.editable, r.value_source,
                   r.constraint_owner_module, r.note
            from platform_listing_field_requirement r
            where r.platform_id = ? and r.enabled
              and (r.marketplace_id is null or r.marketplace_id = ?)
              and (r.category_path is null or r.category_path = ?)
            order by r.field_key, (r.marketplace_id is not null) desc, (r.category_path is not null) desc
            """,
            platformId,
            marketplaceId,
            categoryPath == null ? "" : categoryPath);
    List<ListingEditorFieldRules.Requirement> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingEditorFieldRules.Requirement(
              row.get("field_key", String.class),
              row.get("value_kind", String.class),
              row.get("requirement", String.class),
              row.get("min_length", Integer.class),
              row.get("max_length", Integer.class),
              row.get("min_items", Integer.class),
              row.get("max_items", Integer.class),
              row.get("requires_capability", String.class),
              Boolean.TRUE.equals(row.get("editable", Boolean.class)),
              row.get("value_source", String.class),
              row.get("constraint_owner_module", String.class),
              row.get("note", String.class)));
    }
    return result;
  }

  /** 表单 schema（动态渲染的契约）。 */
  public EditorFormSchema formSchema(
      UUID shopId, UUID marketplaceId, String categoryPath, String locale) {
    Target target = target(shopId, marketplaceId);
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(target.platformId(), target.marketplaceId(), categoryPath),
            target.capabilities());
    String requirementsState = ListingEditorFieldRules.requirementsState(fields);
    String ownerModule =
        "UNKNOWN".equals(requirementsState)
            ? ListingEditorFieldRules.FIELD_REQUIREMENT_OWNER_MODULE
            : null;

    List<EditorFieldSchema> renderable = new ArrayList<>();
    List<EditorFieldSchema> unsupported = new ArrayList<>();
    List<EditorFieldSchema> delegated = new ArrayList<>();
    List<EditorFieldSchema> unknown = new ArrayList<>();
    for (ListingEditorFieldRules.Field field : fields) {
      EditorFieldSchema schema = toSchema(field);
      if ("UNSUPPORTED".equals(field.requirement())
          || "UNSUPPORTED".equals(field.capabilityState())) {
        unsupported.add(schema);
        continue;
      }
      renderable.add(schema);
      if ("DELEGATED".equals(field.constraintState())) {
        delegated.add(schema);
      } else if ("UNKNOWN".equals(field.constraintState())
          || "UNKNOWN".equals(field.capabilityState())) {
        unknown.add(schema);
      }
    }

    return new EditorFormSchema(
        target.platformCode(),
        target.platformName(),
        target.supportStatus(),
        target.integration(),
        target.marketplaceCode(),
        target.marketplaceName(),
        target.countryCode(),
        target.defaultLocale(),
        requirementsState,
        ownerModule,
        renderable,
        unsupported,
        delegated,
        unknown,
        locales(target, locale),
        DRAFT_ISOLATION_NOTE);
  }

  private EditorFieldSchema toSchema(ListingEditorFieldRules.Field field) {
    return new EditorFieldSchema(
        field.field(),
        field.label(),
        field.valueKind(),
        field.requirement(),
        field.constraintState(),
        field.capabilityState(),
        field.minLength(),
        field.maxLength(),
        field.minItems(),
        field.maxItems(),
        field.editable(),
        field.valueSource(),
        field.ownerModule(),
        field.note(),
        field.unavailableReason());
  }

  /** 多语言页签：已有草稿的语言 + 站点默认语言 + 当前请求的语言。 */
  private List<EditorLocaleTab> locales(Target target, String locale) {
    Map<String, String> states = new LinkedHashMap<>();
    List<Record> rows =
        sql.fetch(
            """
            select d.locale, d.state, d.id
            from listing_editor_draft d
            where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and d.shop_id = ? and d.marketplace_id = ?
              and d.state <> 'DISCARDED'
            order by d.locale
            """,
            target.shopId(),
            target.marketplaceId());
    for (Record row : rows) {
      states.put(row.get("locale", String.class), row.get("state", String.class));
    }
    if (target.defaultLocale() != null && !target.defaultLocale().isBlank()) {
      states.putIfAbsent(target.defaultLocale(), "");
    }
    if (locale != null && !locale.isBlank()) {
      states.putIfAbsent(locale, "");
    }
    List<EditorLocaleTab> tabs = new ArrayList<>();
    for (Map.Entry<String, String> entry : states.entrySet()) {
      boolean hasDraft = !entry.getValue().isEmpty();
      tabs.add(
          new EditorLocaleTab(entry.getKey(), hasDraft, hasDraft ? entry.getValue() : "", ""));
    }
    return tabs;
  }

  // ==================================================================
  // 值 ⇄ jsonb
  // ==================================================================

  /** 字段键 → 取值形状（value_kind）。 */
  private Map<String, String> kinds(List<ListingEditorFieldRules.Field> fields) {
    Map<String, String> kinds = new LinkedHashMap<>();
    for (ListingEditorFieldRules.Field field : fields) {
      kinds.put(field.field(), field.valueKind());
    }
    return kinds;
  }

  /** 传输模型 → 落库的 jsonb（按 value_kind 择一形状）。未登记的字段直接丢弃（校验会另行报阻断）。 */
  private Map<String, Object> toValueMap(
      List<EditorFieldValue> values, Map<String, String> kinds) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (EditorFieldValue value : safe(values)) {
      String kind = kinds.get(value.field());
      if (kind == null) {
        continue;
      }
      switch (kind) {
        case "TEXT", "LONG_TEXT", "VIDEO_REF" -> result.put(value.field(), value.text());
        case "TEXT_LIST", "IMAGE_LIST" -> result.put(value.field(), safe(value.items()));
        case "MODULE_LIST" -> {
          List<Map<String, Object>> modules = new ArrayList<>();
          for (EditorModule module : safe(value.modules())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", module.type());
            item.put("heading", module.heading());
            item.put("body", module.body());
            item.put("imageRef", module.imageRef());
            modules.add(item);
          }
          result.put(value.field(), modules);
        }
        case "ATTRIBUTE_MAP", "STATEMENT_MAP" -> {
          Map<String, Object> map = new LinkedHashMap<>();
          for (EditorKeyValue pair : safe(value.pairs())) {
            map.put(pair.key(), pair.value());
          }
          result.put(value.field(), map);
        }
        default -> { }
      }
    }
    return result;
  }

  /** 落库的 jsonb → 传输模型。 */
  private List<EditorFieldValue> toFieldValues(Object raw, Map<String, String> kinds) {
    List<EditorFieldValue> result = new ArrayList<>();
    String text = jsonText(raw);
    if (text == null || text.isBlank() || "{}".equals(text)) {
      return result;
    }
    try {
      var node = json.readTree(text);
      if (node == null || !node.isObject()) {
        return result;
      }
      node.fields()
          .forEachRemaining(
              entry -> {
                String field = entry.getKey();
                String kind = kinds.getOrDefault(field, "TEXT");
                var value = entry.getValue();
                switch (kind) {
                  case "TEXT_LIST", "IMAGE_LIST" -> {
                    List<String> items = new ArrayList<>();
                    if (value.isArray()) {
                      value.forEach(item -> items.add(item.isTextual() ? item.textValue() : item.toString()));
                    }
                    result.add(new EditorFieldValue(field, null, items, null, null));
                  }
                  case "MODULE_LIST" -> {
                    List<EditorModule> modules = new ArrayList<>();
                    if (value.isArray()) {
                      for (var item : value) {
                        modules.add(
                            new EditorModule(
                                item.path("type").asText(""),
                                item.path("heading").asText(""),
                                item.path("body").asText(""),
                                item.path("imageRef").asText("")));
                      }
                    }
                    result.add(new EditorFieldValue(field, null, null, modules, null));
                  }
                  case "ATTRIBUTE_MAP", "STATEMENT_MAP" -> {
                    List<EditorKeyValue> pairs = new ArrayList<>();
                    if (value.isObject()) {
                      value.fields()
                          .forEachRemaining(
                              pair ->
                                  pairs.add(
                                      new EditorKeyValue(
                                          pair.getKey(),
                                          pair.getValue().isTextual()
                                              ? pair.getValue().textValue()
                                              : pair.getValue().toString())));
                    }
                    result.add(new EditorFieldValue(field, null, null, null, pairs));
                  }
                  default -> result.add(
                      new EditorFieldValue(field, value.isTextual() ? value.textValue() : value.toString(), null, null, null));
                }
              });
    } catch (Exception e) {
      return result;
    }
    return result;
  }

  /** 传输模型 → 校验用的形状摘要。 */
  private Map<String, ListingEditorFieldRules.ValueShape> toShapes(
      List<EditorFieldValue> values, Map<String, String> kinds) {
    Map<String, ListingEditorFieldRules.ValueShape> shapes = new LinkedHashMap<>();
    for (EditorFieldValue value : safe(values)) {
      String kind = kinds.get(value.field());
      if (kind == null) {
        shapes.put(value.field(), new ListingEditorFieldRules.ValueShape(true, true, 0, 0));
        continue;
      }
      switch (kind) {
        case "TEXT", "LONG_TEXT", "VIDEO_REF" -> {
          String text = value.text() == null ? "" : value.text();
          shapes.put(
              value.field(),
              new ListingEditorFieldRules.ValueShape(
                  true, !text.isBlank(), text.codePointCount(0, text.length()), 0));
        }
        case "TEXT_LIST", "IMAGE_LIST" -> {
          List<String> items = safe(value.items());
          int longest = 0;
          for (String item : items) {
            longest = Math.max(longest, item == null ? 0 : item.codePointCount(0, item.length()));
          }
          shapes.put(
              value.field(),
              new ListingEditorFieldRules.ValueShape(true, !items.isEmpty(), longest, items.size()));
        }
        case "MODULE_LIST" -> {
          List<EditorModule> modules = safe(value.modules());
          int longest = 0;
          for (EditorModule module : modules) {
            String body = module.body() == null ? "" : module.body();
            longest = Math.max(longest, body.codePointCount(0, body.length()));
          }
          shapes.put(
              value.field(),
              new ListingEditorFieldRules.ValueShape(true, !modules.isEmpty(), longest, modules.size()));
        }
        case "ATTRIBUTE_MAP", "STATEMENT_MAP" -> {
          List<EditorKeyValue> pairs = safe(value.pairs());
          int longest = 0;
          for (EditorKeyValue pair : pairs) {
            String text = pair.value() == null ? "" : pair.value();
            longest = Math.max(longest, text.codePointCount(0, text.length()));
          }
          shapes.put(
              value.field(),
              new ListingEditorFieldRules.ValueShape(true, !pairs.isEmpty(), longest, pairs.size()));
        }
        default -> shapes.put(
            value.field(), new ListingEditorFieldRules.ValueShape(true, true, 0, 0));
      }
    }
    return shapes;
  }

  // ==================================================================
  // 校验
  // ==================================================================

  /** 试算校验（不落库）。 */
  public EditorValidation validate(EditorValidateRequest request) {
    Target target = target(request.shopId(), request.marketplaceId());
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(target.platformId(), target.marketplaceId(), request.categoryPath()),
            target.capabilities());
    Map<String, String> kinds = kinds(fields);
    return toValidation(
        ListingEditorFieldRules.validate(fields, toShapes(request.fieldValues(), kinds)));
  }

  private EditorValidation toValidation(ListingEditorFieldRules.Validation validation) {
    List<EditorViolation> violations = new ArrayList<>();
    for (ListingEditorFieldRules.Violation violation : validation.violations()) {
      violations.add(
          new EditorViolation(
              violation.field(),
              violation.label(),
              violation.severity(),
              violation.code(),
              violation.message(),
              violation.ownerModule(),
              violation.limit(),
              violation.actual()));
    }
    return new EditorValidation(
        validation.valid(),
        validation.blockingCount(),
        validation.warningCount(),
        violations,
        validation.summary());
  }

  // ==================================================================
  // 草稿
  // ==================================================================

  /** 草稿列表。 */
  public List<EditorDraftRow> drafts(UUID shopId, String locale, int page, int size) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select d.*, s.code as shop_code, p.code as platform_code, m.code as marketplace_code,
                   l.external_item_id, l.version as live_version
            from listing_editor_draft d
            join shop s on s.tenant_id = d.tenant_id and s.id = d.shop_id
            join platform p on p.id = d.platform_id
            join marketplace m on m.id = d.marketplace_id
            left join listing l on l.tenant_id = d.tenant_id and l.id = d.listing_id
            where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            """);
    List<Object> params = new ArrayList<>();
    if (shopId != null) {
      sqlText.append(" and d.shop_id = ?");
      params.add(shopId);
    }
    if (locale != null && !locale.isBlank()) {
      sqlText.append(" and d.locale = ?");
      params.add(locale);
    }
    sqlText.append(" order by d.updated_at desc, d.id limit ? offset ?");
    params.add(size);
    params.add(page * size);

    List<Record> rows = sql.fetch(sqlText.toString(), params.toArray());
    List<EditorDraftRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(toDraft(row));
    }
    return result;
  }

  /** 单条草稿详情（含线上对照列）。 */
  public EditorDraftRow draft(String id) {
    Record row = draftRecord(id);
    if (row == null) {
      throw notFound();
    }
    return toDraft(row);
  }

  private Record draftRecord(String id) {
    UUID draftId;
    try {
      draftId = UUID.fromString(id);
    } catch (RuntimeException e) {
      throw notFound();
    }
    return draftRecordById(draftId);
  }

  private Record draftRecordById(UUID draftId) {
    return sql.fetchOne(
        """
        select d.*, s.code as shop_code, p.code as platform_code, m.code as marketplace_code,
               l.external_item_id, l.version as live_version
        from listing_editor_draft d
        join shop s on s.tenant_id = d.tenant_id and s.id = d.shop_id
        join platform p on p.id = d.platform_id
        join marketplace m on m.id = d.marketplace_id
        left join listing l on l.tenant_id = d.tenant_id and l.id = d.listing_id
        where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and d.id = ?
        """,
        draftId);
  }

  private EditorDraftRow toDraft(Record row) {
    UUID platformId = row.get("platform_id", UUID.class);
    UUID marketplaceId = row.get("marketplace_id", UUID.class);
    String categoryPath = row.get("category_path", String.class);
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(platformId, marketplaceId, categoryPath),
            capabilityMap(
                sql.fetchValue("select capabilities from platform where id = ?", platformId)));
    Map<String, String> kinds = kinds(fields);

    List<EditorFieldValue> values = toFieldValues(row.get("field_values"), kinds);
    EditorValidation validation = validationOf(row.get("validation_snapshot"));
    Long liveVersion = row.get("live_version", Long.class);
    long baseVersion = row.get("base_version", Long.class);
    return new EditorDraftRow(
        row.get("id", UUID.class),
        row.get("shop_id", UUID.class),
        row.get("shop_code", String.class),
        platformId,
        row.get("platform_code", String.class),
        marketplaceId,
        row.get("marketplace_code", String.class),
        row.get("locale", String.class),
        row.get("source_sku_id", UUID.class),
        row.get("listing_id", UUID.class),
        row.get("external_item_id", String.class) == null ? "" : row.get("external_item_id", String.class),
        categoryPath == null ? "" : categoryPath,
        values,
        row.get("state", String.class),
        baseVersion,
        liveVersion != null && liveVersion > baseVersion,
        validation,
        liveValues(row.get("listing_id", UUID.class), row.get("locale", String.class), kinds),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        row.get("submitted_at", OffsetDateTime.class));
  }

  private EditorValidation validationOf(Object raw) {
    String text = jsonText(raw);
    if (text == null || text.isBlank() || "{}".equals(text)) {
      return new EditorValidation(true, 0, 0, List.of(), "尚未校验");
    }
    try {
      var node = json.readTree(text);
      List<EditorViolation> violations = new ArrayList<>();
      for (var item : node.path("violations")) {
        violations.add(
            new EditorViolation(
                item.path("field").asText(""),
                item.path("label").asText(""),
                item.path("severity").asText(""),
                item.path("code").asText(""),
                item.path("message").asText(""),
                item.path("ownerModule").isNull() ? null : item.path("ownerModule").asText(""),
                item.path("limit").isNull() ? null : item.path("limit").asInt(),
                item.path("actual").isNull() ? null : item.path("actual").asInt()));
      }
      return new EditorValidation(
          node.path("valid").asBoolean(false),
          node.path("blockingCount").asInt(0),
          node.path("warningCount").asInt(0),
          violations,
          node.path("summary").asText(""));
    } catch (Exception e) {
      return new EditorValidation(true, 0, 0, List.of(), "尚未校验");
    }
  }

  /** 线上当前内容（对照列）：优先逐语言快照，没有快照就用 listing 自身的语言无关列。 */
  private List<EditorFieldValue> liveValues(UUID listingId, String locale, Map<String, String> kinds) {
    if (listingId == null) {
      return List.of();
    }
    Record snapshot =
        sql.fetchOne(
            """
            select title, bullet_points, description, images, video_url, a_plus_modules,
                   category_attributes, compliance_statements
            from listing_content_snapshot
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and listing_id = ? and language = ? and is_current
            """,
            listingId,
            locale);
    Record live = null;
    if (snapshot == null) {
      live =
          sql.fetchOne(
              """
              select title, bullets, images
              from listing
              where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                and id = ?
              """,
              listingId);
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (snapshot != null) {
      values.put("TITLE", snapshot.get("title", String.class));
      values.put("BULLETS", readNode(snapshot.get("bullet_points")));
      values.put("DESCRIPTION", snapshot.get("description", String.class));
      values.put("IMAGES", readNode(snapshot.get("images")));
      values.put("VIDEO", snapshot.get("video_url", String.class));
      values.put("A_PLUS", readNode(snapshot.get("a_plus_modules")));
      values.put("CATEGORY_ATTRIBUTES", readNode(snapshot.get("category_attributes")));
      values.put("COMPLIANCE_STATEMENTS", readNode(snapshot.get("compliance_statements")));
    } else if (live != null) {
      values.put("TITLE", live.get("title", String.class));
      values.put("BULLETS", readNode(live.get("bullets")));
      values.put("IMAGES", readNode(live.get("images")));
    }
    values.put("VARIANT_DIMENSIONS", variantDimensions(listingId));

    List<EditorFieldValue> result = new ArrayList<>();
    for (Map.Entry<String, String> entry : kinds.entrySet()) {
      Object value = values.get(entry.getKey());
      if (value == null) {
        continue;
      }
      result.add(toFieldValue(entry.getKey(), entry.getValue(), value));
    }
    return result;
  }

  private Object variantDimensions(UUID listingId) {
    List<Record> rows =
        sql.fetch(
            """
            select v.external_variant_id, v.attributes
            from listing_variant v
            where v.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and v.listing_id = ?
            order by v.external_variant_id
            """,
            listingId);
    if (rows.isEmpty()) {
      return new LinkedHashMap<String, Object>();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Record row : rows) {
      String variantId = row.get("external_variant_id", String.class);
      var node = readNode(row.get("attributes"));
      result.put(variantId, node == null ? "" : node);
    }
    return result;
  }

  private EditorFieldValue toFieldValue(String field, String kind, Object value) {
    switch (kind) {
      case "TEXT_LIST", "IMAGE_LIST" -> {
        List<String> items = new ArrayList<>();
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node && node.isArray()) {
          node.forEach(item -> items.add(item.isTextual() ? item.textValue() : item.toString()));
        }
        return new EditorFieldValue(field, null, items, null, null);
      }
      case "MODULE_LIST" -> {
        List<EditorModule> modules = new ArrayList<>();
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node && node.isArray()) {
          for (var item : node) {
            modules.add(
                new EditorModule(
                    item.path("type").asText(""),
                    item.path("heading").asText(""),
                    item.path("body").asText(""),
                    item.path("imageRef").asText("")));
          }
        }
        return new EditorFieldValue(field, null, null, modules, null);
      }
      case "ATTRIBUTE_MAP", "STATEMENT_MAP" -> {
        List<EditorKeyValue> pairs = new ArrayList<>();
        if (value instanceof com.fasterxml.jackson.databind.JsonNode node && node.isObject()) {
          node.fields()
              .forEachRemaining(
                  pair ->
                      pairs.add(
                          new EditorKeyValue(
                              pair.getKey(),
                              pair.getValue().isTextual()
                                  ? pair.getValue().textValue()
                                  : pair.getValue().toString())));
        }
        return new EditorFieldValue(field, null, null, null, pairs);
      }
      default -> {
        String text = value instanceof String s ? s : String.valueOf(value);
        return new EditorFieldValue(field, text, null, null, null);
      }
    }
  }

  // ==================================================================
  // 保存草稿
  // ==================================================================

  /**
   * 🔴 保存草稿（唯一的持久化写路径之一）。
   *
   * <p>整个写事务打开 {@code app.draft_only}：期间任何对线上 listing 内容列的改动由 DB 触发器拒。
   * 本方法**不写 listing**。
   */
  public EditorDraftRow save(EditorDraftRequest request) {
    UUID tenant = currentTenant();
    Target target = target(request.shopId(), request.marketplaceId());
    if (request.listingId() == null && request.sourceSkuId() == null) {
      throw unprocessable("必须给出源商品 SKU 或关联的线上 listing 之一");
    }
    String categoryPath = request.categoryPath() == null ? "" : request.categoryPath().trim();
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(target.platformId(), target.marketplaceId(), categoryPath),
            target.capabilities());
    Map<String, String> kinds = kinds(fields);

    // 🔴 载荷里出现该平台**没有**的栏 ⇒ 明确拒（不是静默丢掉）。
    //    静默丢掉是最坏的一种"成功"：运营以为填了，库里其实没有。
    for (EditorFieldValue value : safe(request.fieldValues())) {
      ListingEditorFieldRules.Field field =
          fields.stream().filter(f -> f.field().equals(value.field())).findFirst().orElse(null);
      if (field == null) {
        throw unprocessable(
            "该平台没有「"
                + ListingEditorFieldRules.LABELS.getOrDefault(value.field(), value.field())
                + "」这一栏（字段 " + value.field() + "），不能保存");
      }
      if ("UNSUPPORTED".equals(field.requirement())
          || "UNSUPPORTED".equals(field.capabilityState())) {
        throw unprocessable(
            "该平台不支持「"
                + ListingEditorFieldRules.LABELS.getOrDefault(value.field(), value.field())
                + "」这一栏（"
                + (field.unavailableReason() == null ? "" : field.unavailableReason())
                + "），不能保存");
      }
    }

    Map<String, Object> values = toValueMap(request.fieldValues(), kinds);
    EditorValidation validation =
        toValidation(ListingEditorFieldRules.validate(fields, toShapes(request.fieldValues(), kinds)));

    long liveVersion = 0L;
    if (request.listingId() != null) {
      Record live =
          sql.fetchOne(
              """
              select version from listing
              where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                and id = ? and shop_id = ?
              """,
              request.listingId(),
              request.shopId());
      if (live == null) {
        throw unprocessable("关联的 listing 不存在，或不属于目标店铺");
      }
      liveVersion = live.get("version", Long.class) == null ? 0L : live.get("version", Long.class);
    }
    final long baseVersion = liveVersion;

    String valuesJson = writeJson(values);
    String validationJson = writeJson(validation);
    UUID draftId = UUID.randomUUID();

    withDraftOnly(
        () -> {
          if (request.listingId() != null) {
            sql.execute(
                """
                insert into listing_editor_draft
                  (id,tenant_id,shop_id,platform_id,marketplace_id,locale,source_sku_id,listing_id,
                   category_path,field_values,state,base_version,validation_snapshot,validation_passed,
                   validated_at,version,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?::bigint,?::jsonb,?,?::timestamptz,0,now(),now())
                on conflict (tenant_id, shop_id, marketplace_id, locale) where listing_id is not null
                do update set field_values = excluded.field_values,
                              category_path = excluded.category_path,
                              validation_snapshot = excluded.validation_snapshot,
                              validation_passed = excluded.validation_passed,
                              validated_at = excluded.validated_at,
                              base_version = excluded.base_version,
                              version = listing_editor_draft.version + 1,
                              updated_at = now()
                """,
                draftId,
                tenant,
                request.shopId(),
                target.platformId(),
                request.marketplaceId(),
                request.locale().trim(),
                request.sourceSkuId(),
                request.listingId(),
                categoryPath,
                valuesJson,
                baseVersion,
                validationJson,
                validation.valid(),
                OffsetDateTime.now());
          } else {
            sql.execute(
                """
                insert into listing_editor_draft
                  (id,tenant_id,shop_id,platform_id,marketplace_id,locale,source_sku_id,listing_id,
                   category_path,field_values,state,base_version,validation_snapshot,validation_passed,
                   validated_at,version,created_at,updated_at)
                values (?,?,?,?,?,?,?,null,?,?::jsonb,'DRAFT',?::bigint,?::jsonb,?,?::timestamptz,0,now(),now())
                on conflict (tenant_id, shop_id, marketplace_id, locale, source_sku_id)
                  where listing_id is null and source_sku_id is not null
                do update set field_values = excluded.field_values,
                              category_path = excluded.category_path,
                              validation_snapshot = excluded.validation_snapshot,
                              validation_passed = excluded.validation_passed,
                              validated_at = excluded.validated_at,
                              version = listing_editor_draft.version + 1,
                              updated_at = now()
                """,
                draftId,
                tenant,
                request.shopId(),
                target.platformId(),
                request.marketplaceId(),
                request.locale().trim(),
                request.sourceSkuId(),
                categoryPath,
                valuesJson,
                baseVersion,
                validationJson,
                validation.valid(),
                OffsetDateTime.now());
          }
          // 🔴 内容改了 ⇒ 之前入队的那一份冻结的是**旧内容**，必须撤销，
          //    否则 4.3.1.1 派发出去的是没人要的旧版本。
          sql.execute(
              """
              update listing_editor_submission
                 set state = 'CANCELLED'
               where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                 and state = 'QUEUED'
                 and draft_id = any (select id from listing_editor_draft
                                     where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                                       and shop_id = ? and marketplace_id = ? and locale = ?
                                       and ((listing_id is not null and listing_id = ?)
                                         or (listing_id is null and source_sku_id = ?)))
              """,
              request.shopId(),
              request.marketplaceId(),
              request.locale().trim(),
              request.listingId(),
              request.sourceSkuId());
        });

    Record saved = draftRecordById(findDraftId(request));
    if (saved == null) {
      throw new IllegalStateException("草稿保存后读不回来");
    }
    return toDraft(saved);
  }

  private UUID findDraftId(EditorDraftRequest request) {
    Record row =
        sql.fetchOne(
            """
            select id from listing_editor_draft
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and shop_id = ? and marketplace_id = ? and locale = ?
              and ((listing_id is not null and listing_id = ?)
                or (listing_id is null and source_sku_id = ?))
            """,
            request.shopId(),
            request.marketplaceId(),
            request.locale().trim(),
            request.listingId(),
            request.sourceSkuId());
    return row == null ? null : row.get("id", UUID.class);
  }

  // ==================================================================
  // 提交审核（入队）
  // ==================================================================

  /**
   * 🔴 提交审核 = **入队**。
   *
   * <p>产出是 {@code listing_editor_submission}（QUEUED），**不是**"已发布"：真正发给平台是 4.3.1.1
   * 一键刊登（尚未交付）。
   *
   * <p>幂等：同一个 {@code idempotencyKey} 重复提交返回同一条入队记录。
   */
  public EditorSubmissionRow submit(String draftId, String idempotencyKey) {
    UUID tenant = currentTenant();
    Record draftRow = draftRecord(draftId);
    if (draftRow == null) {
      throw notFound();
    }
    UUID shopId = draftRow.get("shop_id", UUID.class);
    UUID marketplaceId = draftRow.get("marketplace_id", UUID.class);
    UUID platformId = draftRow.get("platform_id", UUID.class);
    String locale = draftRow.get("locale", String.class);
    String categoryPath = draftRow.get("category_path", String.class);

    // 幂等：同一个键已经入过队 ⇒ 返回同一条
    Record existing =
        sql.fetchOne(
            """
            select sub.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_editor_submission sub
            join platform p on p.id = sub.platform_id
            join shop s on s.tenant_id = sub.tenant_id and s.id = sub.shop_id
            join marketplace m on m.id = sub.marketplace_id
            where sub.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and sub.idempotency_key = ?
            """,
            idempotencyKey.trim());
    if (existing != null) {
      return toSubmission(existing);
    }

    // 重新算一次校验：入队的必须是**当下**这份内容的校验结果
    Target target = target(shopId, marketplaceId);
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(platformId, marketplaceId, categoryPath), target.capabilities());
    Map<String, String> kinds = kinds(fields);
    List<EditorFieldValue> values = toFieldValues(draftRow.get("field_values"), kinds);
    EditorValidation validation =
        toValidation(ListingEditorFieldRules.validate(fields, toShapes(values, kinds)));
    if (!validation.valid()) {
      List<String> labels =
          validation.violations().stream()
              .filter(v -> "BLOCKING".equals(v.severity()))
              .map(EditorViolation::label)
              .distinct()
              .toList();
      throw unprocessable(
          "还有 " + validation.blockingCount() + " 条阻断项没修掉（" + String.join("、", labels)
              + "），提交审核前必须先修完");
    }

    UUID submissionId = UUID.randomUUID();
    String contentJson = writeJson(toValueMap(values, kinds));
    String validationJson = writeJson(validation);
    withDraftOnly(
        () -> {
          sql.execute(
              """
              insert into listing_editor_submission
                (id,tenant_id,draft_id,platform_id,shop_id,marketplace_id,locale,state,
                 content_snapshot,validation_snapshot,idempotency_key,queued_at)
              values (?,?,?,?,?,?,?,'QUEUED',?::jsonb,?::jsonb,?,now())
              on conflict (tenant_id, idempotency_key) do nothing
              """,
              submissionId,
              tenant,
              draftRow.get("id", UUID.class),
              platformId,
              shopId,
              marketplaceId,
              locale,
              contentJson,
              validationJson,
              idempotencyKey.trim());
          sql.execute(
              """
              update listing_editor_draft
                 set state = 'SUBMITTED', submitted_at = now(), version = version + 1, updated_at = now()
               where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                 and id = ?
              """,
              draftRow.get("id", UUID.class));
        });

    Record row =
        sql.fetchOne(
            """
            select sub.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_editor_submission sub
            join platform p on p.id = sub.platform_id
            join shop s on s.tenant_id = sub.tenant_id and s.id = sub.shop_id
            join marketplace m on m.id = sub.marketplace_id
            where sub.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and sub.idempotency_key = ?
            """,
            idempotencyKey.trim());
    if (row == null) {
      throw new IllegalStateException("入队记录写完读不回来");
    }
    return toSubmission(row);
  }

  /** 入队队列（"提交发布动作"的可查视图）。 */
  public List<EditorSubmissionRow> submissions(int page, int size) {
    List<Record> rows =
        sql.fetch(
            """
            select sub.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_editor_submission sub
            join platform p on p.id = sub.platform_id
            join shop s on s.tenant_id = sub.tenant_id and s.id = sub.shop_id
            join marketplace m on m.id = sub.marketplace_id
            where sub.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            order by sub.queued_at desc, sub.id
            limit ? offset ?
            """,
            size,
            page * size);
    List<EditorSubmissionRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(toSubmission(row));
    }
    return result;
  }

  private EditorSubmissionRow toSubmission(Record row) {
    UUID platformId = row.get("platform_id", UUID.class);
    UUID marketplaceId = row.get("marketplace_id", UUID.class);
    List<ListingEditorFieldRules.Field> fields =
        ListingEditorFieldRules.resolve(
            requirements(platformId, marketplaceId, ""),
            capabilityMap(sql.fetchValue("select capabilities from platform where id = ?", platformId)));
    Map<String, String> kinds = kinds(fields);
    return new EditorSubmissionRow(
        row.get("id", UUID.class),
        row.get("draft_id", UUID.class),
        row.get("platform_code", String.class) == null ? "" : row.get("platform_code", String.class),
        row.get("shop_code", String.class) == null ? "" : row.get("shop_code", String.class),
        row.get("marketplace_code", String.class) == null ? "" : row.get("marketplace_code", String.class),
        row.get("locale", String.class),
        row.get("state", String.class),
        toFieldValues(row.get("content_snapshot"), kinds),
        validationOf(row.get("validation_snapshot")),
        row.get("idempotency_key", String.class),
        row.get("queued_at", OffsetDateTime.class),
        row.get("dispatched_at", OffsetDateTime.class),
        "真正发给平台是 4.3.1.1 一键刊登的职责（尚未交付）；本条只是入队。");
  }

  // ==================================================================
  // 上游只读（03 商品主数据 / 03 媒体库）
  // ==================================================================

  /** 源商品（03 SKU 主数据，跨服务只读）。 */
  public EditorSourceProduct sourceProduct(String skuId, String bearer) {
    EditorSourceQueryPort.SkuDetail detail = source.sku(skuId, bearer);
    List<EditorKeyValue> specs = new ArrayList<>();
    for (EditorSourceQueryPort.SkuSpec spec : detail.specs()) {
      specs.add(new EditorKeyValue(spec.axis(), spec.value()));
    }
    List<EditorKeyValue> attributes = new ArrayList<>();
    for (EditorSourceQueryPort.SkuAttribute attribute : detail.attributes()) {
      attributes.add(
          new EditorKeyValue(
              attribute.name() == null ? attribute.code() : attribute.name(),
              attribute.effectiveValues() == null
                  ? ""
                  : String.join(" / ", attribute.effectiveValues())));
    }
    List<EditorMediaAsset> media = new ArrayList<>();
    for (EditorSourceQueryPort.SkuMedia asset : detail.media()) {
      media.add(
          new EditorMediaAsset(
              asset.id(),
              asset.title(),
              "",
              asset.reference(),
              null,
              null,
              asset.status()));
    }
    return new EditorSourceProduct(
        detail.id(),
        detail.code(),
        detail.name(),
        detail.status(),
        detail.spuCode(),
        detail.spuName(),
        detail.brand(),
        detail.category(),
        specs,
        attributes,
        media);
  }

  /** 媒体库素材（03 媒体库，跨服务只读）。 */
  public List<EditorMediaAsset> mediaAssets(String keyword, String bearer) {
    List<EditorSourceQueryPort.MediaAsset> assets = source.media(keyword, bearer);
    List<EditorMediaAsset> result = new ArrayList<>();
    for (EditorSourceQueryPort.MediaAsset asset : assets) {
      result.add(
          new EditorMediaAsset(
              asset.id(),
              asset.name(),
              asset.mediaType(),
              asset.storageKey(),
              asset.widthPx(),
              asset.heightPx(),
              asset.integrityStatus()));
    }
    return result;
  }

  // ==================================================================
  // 事务标记
  // ==================================================================

  /**
   * 🔴 打开 {@code app.draft_only}，期间线上 listing 的内容列由 DB 触发器拒写。
   *
   * <p>用 {@code set_config(...,true)} —— 第三个参数 true 表示**事务局部**：连接归还池子后不会把
   * 标记带出去（4.1.1.1 已踩过这个坑：连接池复用导致标记泄漏到别的请求）。
   */
  private void withDraftOnly(Runnable work) {
    sql.execute("select set_config('app.draft_only','true',true)");
    try {
      work.run();
    } finally {
      try {
        sql.execute("select set_config('app.draft_only','false',true)");
      } catch (RuntimeException e) {
        // 🔴 事务本身已经失败时，清标记这条语句也会跟着失败（PG：当前事务已中止）。
        //    那时**原异常才是根因**，不能被清标记的异常盖掉 —— 所以这里吞掉它。
      }
    }
  }
}
