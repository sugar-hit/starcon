package com.starcon.channel;

import static com.starcon.channel.ListingPublishJobModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 4.3.1.1 一键刊登的<b>内容来源解析器</b>。
 *
 * <p>蓝图职责句里的「自动套模板、填充内容」落在这里。两个来源，各有各的真相：
 *
 * <ol>
 *   <li><b>EDITOR_SUBMISSION</b>（4.2.1.1 刊登编辑器）—— 读 {@code listing_editor_submission}
 *       在<b>入队那一刻</b>冻结的内容快照。本模块**不重新求值**：重新求值得到的是
 *       「现在长什么样」，而快照回答的是「我们当时发的是什么」。
 *   <li><b>TEMPLATE</b>（4.2.2.1 刊登模板）—— 读模板的<b>取值规则</b>（{@code listing_template_field} /
 *       {@code listing_template_asset_group}），对源 SKU 实时求值。模板存的是规则不是值，
 *       所以商品换图换属性后重新发布求出来的是新值 —— 这正是 4.2.2.1 那句
 *       「模板引用的是『取值规则』而非一次性拷贝」的落法。
 * </ol>
 *
 * <p>🔴 <b>上游只经授权 HTTP API 读</b>：商品属性 / 文案库 / 媒体库都走 4.2.2.1 已交付的
 * {@link ListingTemplateSourcePort}（转发调用方自己的 bearer，租户仍由上游按已认证成员关系解析）。
 * 本模块**不跨库读 catalog**。
 *
 * <p>🔴 <b>「取不到」与「取到但是空」必须分得开</b>：本类每个字段都带求值状态
 * （RESOLVED / EMPTY / SOURCE_MISSING / UNAVAILABLE / NOT_APPLICABLE），取不到不会被折成空串。
 */
@Service
public class ListingPublishContentResolver {

  private static final String OWNER_PRODUCT = "3.1.2.2";
  private static final String OWNER_CONTENT = "3.4.2.2";
  private static final String OWNER_MEDIA = "3.4.1.1";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ListingTemplateSourcePort sources;

  public ListingPublishContentResolver(
      DSLContext sql, ObjectMapper json, ListingTemplateSourcePort sources) {
    this.sql = sql;
    this.json = json;
    this.sources = sources;
  }

  // ==================================================================
  // 来源一：4.2.1.1 刊登编辑器的入队快照
  // ==================================================================

  /**
   * 读一条入队记录并把它冻结的内容快照搬进本模块的快照形状。
   *
   * @param tenantId 当前租户
   * @param submissionId 入队记录 id
   * @return 那一刻的内容快照
   */
  public ListingPublishContentSnapshot fromEditorSubmission(UUID tenantId, UUID submissionId) {
    Record row =
        sql.fetchOne(
            """
            select sub.id, sub.state, sub.locale, sub.content_snapshot,
                   p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_editor_submission sub
            join platform p on p.id = sub.platform_id
            join shop s on s.tenant_id = sub.tenant_id and s.id = sub.shop_id
            join marketplace m on m.id = sub.marketplace_id
            where sub.tenant_id = ? and sub.id = ?
            """,
            tenantId,
            submissionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
    JsonNode node = readNode(row.get("content_snapshot"));
    List<ListingPublishContentField> fields = new ArrayList<>();
    String title = textValue(node, "TITLE");
    fields.add(field("TITLE", title == null ? "EMPTY" : "RESOLVED", "", title == null ? "入队快照里没有标题" : "取自入队那一刻的标题"));
    String description = textValue(node, "DESCRIPTION");
    fields.add(
        field(
            "DESCRIPTION",
            description == null ? "EMPTY" : "RESOLVED",
            "",
            description == null ? "入队快照里没有描述" : "取自入队那一刻的描述"));
    List<String> bullets = listValue(node, "BULLETS");
    fields.add(
        field(
            "BULLETS",
            bullets.isEmpty() ? "EMPTY" : "RESOLVED",
            "",
            bullets.isEmpty() ? "入队快照里没有卖点" : "取自入队那一刻的卖点"));
    List<String> images = listValue(node, "IMAGES");
    fields.add(
        field(
            "IMAGES",
            images.isEmpty() ? "EMPTY" : "RESOLVED",
            "",
            images.isEmpty() ? "入队快照里没有图片" : "取自入队那一刻的图片（素材存储键）"));

    return new ListingPublishContentSnapshot(
        title,
        bullets,
        description,
        images,
        null,
        null,
        "EDITOR_SUBMISSION",
        row.get("id", UUID.class),
        "4.2.1.1 刊登编辑器的入队记录 "
            + row.get("shop_code", String.class)
            + " / "
            + row.get("marketplace_code", String.class)
            + " / "
            + row.get("locale", String.class)
            + "（入队状态 "
            + row.get("state", String.class)
            + "）",
        fields);
  }

  // ==================================================================
  // 来源二：4.2.2.1 刊登模板的取值规则
  // ==================================================================

  /**
   * 对源 SKU 实时求值一个刊登模板。
   *
   * <p>🔴 这条路径<b>只读</b> 4.2.2.1 的规则表，**不写**它的 {@code listing_template_apply}
   * 与 {@code listing_content_draft}（那是 4.2.2.1 自己的写入所有权）。
   *
   * @param templateId 模板 id
   * @param skuId 源 SKU（03 逻辑 id）
   * @param locale 语言（文案库渲染用）
   * @param bearer 调用方自己的 bearer（转发给 catalog）
   */
  public ListingPublishContentSnapshot fromTemplate(
      UUID templateId, UUID skuId, String locale, String bearer) {
    Record template =
        sql.fetchOne(
            """
            select id, code, name, scope, platform_id, marketplace_id, category_path, shop_id,
                   language, status
            from listing_template
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?
            """,
            templateId);
    if (template == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
    String templateLanguage = template.get("language", String.class);
    String renderLocale = templateLanguage == null || templateLanguage.isBlank() ? locale : templateLanguage;

    List<Record> rules =
        sql.fetch(
            """
            select field, source_kind, source_ref, value_expression, required, slot_no
            from listing_template_field
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and template_id = ?
            order by slot_no, id
            """,
            templateId);
    List<Record> groups =
        sql.fetch(
            """
            select group_code, group_kind, tag_code, asset_ids, max_count, slot_no
            from listing_template_asset_group
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and template_id = ?
            order by slot_no, id
            """,
            templateId);

    // 商品属性只读一次（多条规则可能引用不同属性）
    ListingTemplateSourcePort.ProductResult product = null;
    boolean productLoaded = false;

    Map<String, String> textValues = new LinkedHashMap<>();
    Map<String, List<String>> listValues = new LinkedHashMap<>();
    List<ListingPublishContentField> fields = new ArrayList<>();

    for (Record rule : rules) {
      String field = rule.get("field", String.class);
      String sourceKind = rule.get("source_kind", String.class);
      String sourceRef = rule.get("source_ref", String.class);
      String expression = rule.get("value_expression", String.class);
      boolean required = Boolean.TRUE.equals(rule.get("required", Boolean.class));

      String status = "RESOLVED";
      String ownerModule = "";
      String note = "";
      String textValue = null;
      List<String> listValue = null;

      switch (sourceKind) {
        case "LITERAL" -> {
          textValue = expression;
          if (isBlank(textValue)) {
            status = "EMPTY";
            note = "LITERAL 规则没有给出常量";
          }
        }
        case "LISTING_FIELD" -> {
          // 🔴 一键刊登是"把源 SKU 发到还没发过的目标"：目标侧**没有**线上 listing 可取。
          //     这条来源在 4.2.2.1 套用线上商品时才有意义，在这里如实标成不适用。
          status = "NOT_APPLICABLE";
          ownerModule = "4.2.2.1";
          note = "一键刊登的目标还没有线上商品，取不到目标自身的内容（LISTING_FIELD 只在 4.2.2.1 套用线上商品时可用）";
        }
        case "PRODUCT_ATTRIBUTE" -> {
          if (!productLoaded) {
            product = sources.productAttributes(skuId, bearer);
            productLoaded = true;
          }
          if (!product.available()) {
            status = "UNAVAILABLE";
            ownerModule = OWNER_PRODUCT;
            note = product.reason();
          } else if (!product.attributes().containsKey(sourceRef)) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_PRODUCT;
            note =
                "属性码「"
                    + sourceRef
                    + "」在这个 SKU 上不存在（存在的属性："
                    + (product.attributes().isEmpty()
                        ? "无"
                        : String.join("、", product.attributes().keySet()))
                    + "）";
          } else {
            String attribute = product.attributes().get(sourceRef);
            if (isBlank(attribute)) {
              status = "EMPTY";
              ownerModule = OWNER_PRODUCT;
              note = "属性「" + sourceRef + "」在这个 SKU 上没有生效值";
            } else {
              var resolved = resolveExpression(expression, attribute, product);
              if (resolved.missing() != null) {
                status = "SOURCE_MISSING";
                ownerModule = OWNER_PRODUCT;
                note = "取值表达式里的占位符「" + resolved.missing() + "」取不到值";
              } else {
                textValue = resolved.value();
              }
            }
          }
        }
        case "CONTENT_TEMPLATE" -> {
          ListingTemplateSourcePort.ContentResult content =
              sources.renderContentTemplate(sourceRef, skuId, renderLocale, bearer);
          if (!content.available()) {
            status = "UNAVAILABLE";
            ownerModule = OWNER_CONTENT;
            note = content.reason();
          } else if (content.templateMissing()) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_CONTENT;
            note = "文案模板编码「" + sourceRef + "」在文案库里不存在";
          } else if (content.issueCount() > 0) {
            status = "EMPTY";
            ownerModule = OWNER_CONTENT;
            note =
                "文案模板「"
                    + sourceRef
                    + "」渲染出 "
                    + content.issueCount()
                    + " 条缺失变量，正文不完整（变量未绑定必须显式提示，不允许把占位符原样发到平台）";
          } else if (isBlank(content.text())) {
            status = "EMPTY";
            note = "文案模板「" + sourceRef + "」渲染出空正文";
          } else {
            var resolved = resolveExpression(expression, content.text(), product);
            if (resolved.missing() != null) {
              status = "SOURCE_MISSING";
              ownerModule = OWNER_CONTENT;
              note = "取值表达式里的占位符「" + resolved.missing() + "」取不到值";
            } else {
              textValue = resolved.value();
            }
          }
        }
        case "IMAGE_GROUP" -> {
          Record group = null;
          for (Record candidate : groups) {
            if (sourceRef.equals(candidate.get("group_code", String.class))) {
              group = candidate;
              break;
            }
          }
          if (group == null) {
            status = "SOURCE_MISSING";
            ownerModule = OWNER_MEDIA;
            note = "图片组「" + sourceRef + "」不在本模板里";
          } else {
            String groupKind = group.get("group_kind", String.class);
            int maxCount = group.get("max_count", Integer.class) == null ? 9 : group.get("max_count", Integer.class);
            List<String> keys = new ArrayList<>();
            if ("TAG".equals(groupKind)) {
              ListingTemplateSourcePort.MediaResult media =
                  sources.mediaAssetsByTag(group.get("tag_code", String.class), maxCount, bearer);
              if (!media.available()) {
                status = "UNAVAILABLE";
                ownerModule = OWNER_MEDIA;
                note = media.reason();
              } else {
                keys.addAll(enabledImageKeys(media.assets()));
              }
            } else {
              UUID[] assetIds = group.get("asset_ids", UUID[].class);
              ListingTemplateSourcePort.MediaResult media =
                  sources.mediaAssets(assetIds == null ? List.of() : List.of(assetIds), bearer);
              if (!media.available()) {
                status = "UNAVAILABLE";
                ownerModule = OWNER_MEDIA;
                note = media.reason();
              } else {
                keys.addAll(enabledImageKeys(media.assets()));
              }
            }
            if ("RESOLVED".equals(status)) {
              if (keys.size() > maxCount) {
                keys = new ArrayList<>(keys.subList(0, maxCount));
                note = "图片组最多 " + maxCount + " 张，已按上限截断";
              }
              listValue = keys;
              if (listValue.isEmpty()) {
                status = "EMPTY";
                ownerModule = OWNER_MEDIA;
                note = "图片组「" + sourceRef + "」里没有可用的图片素材";
              }
            }
          }
        }
        default -> {
          status = "SOURCE_MISSING";
          ownerModule = "4.2.2.1";
          note = "未知的取值来源：" + sourceKind;
        }
      }

      // 列表型字段（卖点 / 图片）按行拆；文本型字段直接取值
      if ("RESOLVED".equals(status) && "BULLETS".equals(field) && listValue == null) {
        listValue = splitLines(textValue);
        textValue = null;
        if (listValue.isEmpty()) {
          status = "EMPTY";
          note = isBlank(note) ? "取到的值为空" : note;
        }
      }
      if ("RESOLVED".equals(status) && "IMAGES".equals(field) && listValue == null) {
        listValue = splitLines(textValue);
        textValue = null;
        if (listValue.isEmpty()) {
          status = "EMPTY";
          note = isBlank(note) ? "取到的值为空" : note;
        }
      }
      // 🔴 必填规则取不到值 ⇒ 这一栏是**阻断**（不是提醒）：填不上去就不该发到平台
      if (required && !"RESOLVED".equals(status)) {
        note = (isBlank(note) ? "必填项没有取到值" : note) + "（该规则标记为必填）";
      }

      fields.add(field(field, status, ownerModule, note));
      if ("RESOLVED".equals(status)) {
        switch (field) {
          case "TITLE" -> textValues.put("TITLE", textValue);
          case "DESCRIPTION" -> textValues.put("DESCRIPTION", textValue);
          case "CATEGORY_PATH" -> textValues.put("CATEGORY_PATH", textValue);
          case "PLATFORM_CATEGORY_ID" -> textValues.put("PLATFORM_CATEGORY_ID", textValue);
          case "BULLETS" -> listValues.put("BULLETS", listValue);
          case "IMAGES" -> listValues.put("IMAGES", listValue);
          default -> { }
        }
      }
    }

    fields.add(
        field(
            "DESCRIPTION",
            "NOT_APPLICABLE",
            "4.2.2.2",
            "刊登模板的字段集里没有描述（描述模板归 4.2.2.2 / 编辑器 4.2.1.1），模板来源不发描述"));

    return new ListingPublishContentSnapshot(
        textValues.get("TITLE"),
        listValues.getOrDefault("BULLETS", List.of()),
        textValues.get("DESCRIPTION"),
        listValues.getOrDefault("IMAGES", List.of()),
        textValues.get("CATEGORY_PATH"),
        textValues.get("PLATFORM_CATEGORY_ID"),
        "TEMPLATE",
        template.get("id", UUID.class),
        "4.2.2.1 刊登模板「"
            + template.get("name", String.class)
            + "」（"
            + template.get("code", String.class)
            + "，状态 "
            + template.get("status", String.class)
            + "）对源 SKU 实时求值",
        fields);
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  /** 取值表达式的求值结果（{@code missing} 非空表示某个占位符取不到值）。 */
  private record ExpressionResult(String value, String missing) {}

  /**
   * 求值表达式：把 {@code {{value}}} 换成来源值，把 {@code {{属性码}}} 换成商品属性。
   *
   * <p>🔴 占位符取不到值时返回 missing（<b>不是</b>把占位符原样留着）——
   * 「变量未绑定必须显式提示」是 4.2.2.2 的硬要求，这里对取值表达式同样适用。
   */
  private ExpressionResult resolveExpression(
      String expression, String sourceValue, ListingTemplateSourcePort.ProductResult product) {
    String result = expression == null ? "" : expression;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)}}").matcher(result);
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
  private static List<String> enabledImageKeys(
      List<ListingTemplateSourcePort.MediaAssetView> assets) {
    List<String> keys = new ArrayList<>();
    if (assets == null) {
      return keys;
    }
    for (ListingTemplateSourcePort.MediaAssetView asset : assets) {
      if (!asset.enabled() || !"IMAGE".equals(asset.mediaType())) {
        continue;
      }
      if (isBlank(asset.storageKey())) {
        continue;
      }
      keys.add(asset.storageKey());
    }
    return keys;
  }

  /** 多行文本拆成多值（卖点按行拆）。 */
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

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static ListingPublishContentField field(
      String field, String status, String ownerModule, String note) {
    return new ListingPublishContentField(field, "TEMPLATE", status, ownerModule, note);
  }

  private JsonNode readNode(Object raw) {
    try {
      if (raw == null) {
        return null;
      }
      String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
      if (value == null || value.isBlank()) {
        return null;
      }
      return json.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  private static String textValue(JsonNode node, String key) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.isValueNode() ? value.asText() : value.toString();
  }

  private static List<String> listValue(JsonNode node, String key) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    JsonNode value = node.get(key);
    if (value == null || !value.isArray()) {
      return out;
    }
    for (JsonNode item : value) {
      out.add(item.isValueNode() ? item.asText() : item.toString());
    }
    return out;
  }
}
