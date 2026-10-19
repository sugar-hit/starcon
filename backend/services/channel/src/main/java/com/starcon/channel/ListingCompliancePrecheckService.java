package com.starcon.channel;

import static com.starcon.channel.ListingComplianceModels.*;
import static com.starcon.channel.ListingComplianceRules.*;
import static com.starcon.channel.ListingComplianceSourcePort.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.3.2 合规预检（违禁词/侵权）。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。输入：内容草稿；租户违禁词库；平台规则库；16 合规的侵权与认证状态；
 * 03 品牌授权状态。输出：风险清单（风险等级、命中位置、规则来源、建议替换）；阻断项（未修复不允许发布）与提醒项。
 * 关系：上游 = 16 合规与风控、03 品牌、02 平台规则；下游 = 4.3.1.1（阻断发布）。
 * <b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 *
 * <p>🔴 本类里有四件**结构**上的不变量：
 *
 * <ol>
 *   <li><b>扫描的内容来源是编辑器草稿</b>（4.2.1.1 的 {@code listing_editor_draft}），
 *       不是线上 listing —— 发布前要检的是「即将发出去的那一份」。
 *   <li><b>阻断项在服务端强制校验</b>：{@link #publishGate} 真算一遍并返回 allowed=false +
 *       逐条阻断项；DB 层还有一条触发器把 4.2.1.1 的入队动作直接拒掉（见 V198 第三节 (c)）。
 *       <b>前端把阻断项和提醒项分区显示 ≠ 拦截</b>。
 *   <li><b>「不知道」是一个状态</b>：量不到的维度是 UNKNOWN + ownerModule，
 *       <b>不计入已判定维度的分母、也不算通过</b> —— 结论因此有三态（PASS / BLOCKED / INCOMPLETE）。
 *   <li><b>跨服务只经授权 HTTP API</b>（{@link ListingComplianceSourcePort}），
 *       租户由上游按已认证成员关系解析，**两道 RLS**。
 * </ol>
 */
@Service
public class ListingCompliancePrecheckService {

  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 授权到期多少天以内算提醒项。 */
  private static final int AUTHORIZATION_EXPIRY_WARN_DAYS = 30;

  /** 认证维度的归属模块（16.2.2.2 未交付）。 */
  private static final String OWNER_CERTIFICATION = "16.2.2.2";

  /** 合规规则库的归属模块（16.2.1.1 未交付）。 */
  private static final String OWNER_RULE_LIBRARY = "16.2.1.1";

  /** 同批并行的合规预检（体检报告）的归属模块。 */
  private static final String OWNER_COMPLIANCE_REPORT = "16.2.2.1";

  /** 一键刊登的归属模块（同批并行，尚未交付）。 */
  private static final String OWNER_PUBLISH = "4.3.1.1";

  private static final String SERVER_ENFORCEMENT_NOTE =
      "阻断项在服务端强制校验：发布闸门（GET /channel/listing-compliance-precheck/drafts/{id}/publish-gate）"
          + "由服务端真算，另有 DB 触发器在 4.2.1.1 的入队动作上直接拒写未修复阻断项的内容。"
          + "页面上的分区与提示只是显示，**前端提示不能作为唯一防线** —— 拦截在服务端。";

  private static final String BOUNDARY_NOTE =
      "本模块是 04 刊登与内容域的「发布前风险清单」（阻断项 + 提醒项），"
          + "与 16 合规域的 16.2.2.1 合规预检（体检报告，分维度得分 + 整改建议）是两件事："
          + "两边不共表、不共端点、不共页面。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ListingComplianceSourcePort source;

  public ListingCompliancePrecheckService(
      DSLContext sql, ObjectMapper json, ListingComplianceSourcePort source) {
    this.sql = sql;
    this.json = json;
    this.source = source;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 四段未交付的一律 {@code available=false} + {@code ownerModule} + 影响说明：
   * 16.2.1.1 合规规则库、16.2.2.2 认证与资质、16.2.2.1 合规预检（体检报告，同批并行）、
   * 4.3.1.1 一键刊登（同批并行）。
   */
  public ListingComplianceCapabilities capabilities() {
    return new ListingComplianceCapabilities(
        List.of(
            new ListingComplianceCapabilityDimension(
                "prohibited_words",
                "🔴 违禁词 / 敏感词命中（租户级词表）",
                true,
                "listing_compliance_word（本模块自有的租户级词表）",
                "",
                "词表由运营自己维护，带规则来源与建议替换；命中后逐条指到具体字段与第几项"),
            new ListingComplianceCapabilityDimension(
                "superlative_words",
                "🔴 极限词命中（租户级词表）",
                true,
                "listing_compliance_word（本模块自有的租户级词表）",
                "",
                "极限词与违禁词同表、靠 category 区分；平台侧的极限词规则库不在这里"),
            new ListingComplianceCapabilityDimension(
                "brand_infringement",
                "🔴 品牌侵权（外观 / 商标）",
                true,
                "catalog GET /catalog/brands、/catalog/brands/{id}/listing-eligibility、"
                    + "/catalog/brands/{id}/authorizations（03 品牌授权状态）",
                "",
                "品牌未登记 / 无有效授权 ⇒ 阻断项；授权快到期 ⇒ 提醒项"),
            new ListingComplianceCapabilityDimension(
                "category_required",
                "🔴 平台类目必填缺失",
                true,
                "4.2.3.1 平台类目映射（catalog GET /catalog/category-mappings/resolve）",
                "",
                "必填属性缺失或还没绑内部属性 ⇒ 阻断项；推荐属性缺失 ⇒ 提醒项"),
            new ListingComplianceCapabilityDimension(
                "image_compliance",
                "🔴 图片不合规（尺寸 / 白底 / 水印）",
                true,
                "catalog GET /catalog/media + GET /catalog/media/processing/recipes（03 媒体库与图片规格）",
                "",
                "尺寸不足 / 素材失效 ⇒ 阻断项；白底、水印、替代文本 ⇒ 提醒项"),
            new ListingComplianceCapabilityDimension(
                "certification",
                "🔴 认证缺失",
                false,
                "16.2.2.2 认证与资质（未交付）",
                OWNER_CERTIFICATION,
                "16.2.2.2 认证与资质在集成头尚未交付，没有认证档案来源。"
                    + "本维度判为 UNKNOWN，**不计入已判定维度的分母、也不算通过** —— "
                    + "量不到不等于合规。"),
            new ListingComplianceCapabilityDimension(
                "platform_rule_library",
                "平台规则库（受版本治理的规则原文与出处）",
                false,
                "16.2.1.1 合规规则库（未交付）",
                OWNER_RULE_LIBRARY,
                "16.2.1.1 未交付 ⇒ 没有平台规则库来源。本模块**不建**规则库表，"
                    + "只做扫描与出清单；租户违禁词库是本模块自己的租户级词表，与受版本治理的规则库是两回事。"),
            new ListingComplianceCapabilityDimension(
                "compliance_health_report",
                "合规体检报告（多维度检查 + 分维度得分 + 整改建议）",
                false,
                "16.2.2.1 合规预检（合规域，同批并行车道）",
                OWNER_COMPLIANCE_REPORT,
                "16.2.2.1 与本模块同批并行开发，**此刻尚不存在**。两边不共表、不共端点、不共页面；"
                    + "本模块不读它的数据也不调它的端点。"),
            new ListingComplianceCapabilityDimension(
                "publish_dispatch",
                "🔴 一键刊登（真正发给平台）",
                false,
                "4.3.1.1 一键刊登（同批并行车道，尚未交付）",
                OWNER_PUBLISH,
                "4.3.1.1 是本模块的下游，尚未交付 ⇒ 本模块不读它的表也不调它的端点。"
                    + "但**阻断项在服务端强制校验**这件事已经做实（服务层发布闸门 + DB 触发器），"
                    + "它接上来时直接读发布闸门即可。"),
            new ListingComplianceCapabilityDimension(
                "server_side_enforcement",
                "🔴 阻断项在服务端强制校验（前端提示不是唯一防线）",
                true,
                "ListingCompliancePrecheckService.publishGate + V198 的 listing_editor_submission 触发器",
                "",
                "服务端真算发布闸门；DB 层在 4.2.1.1 的入队动作上直接拒写有未修复阻断项的内容"),
            new ListingComplianceCapabilityDimension(
                "tenant_word_library",
                "租户级违禁词库（运营自己维护）",
                true,
                "listing_compliance_word（V198）",
                "",
                "带平台 / 语言维度、规则来源与建议替换；**不是** 16.2.1.1 的受版本治理规则库")),
        ListingComplianceRules.DIMENSIONS,
        ListingComplianceRules.RISK_LEVELS,
        ListingComplianceRules.SEVERITIES,
        ListingComplianceRules.CONCLUSIONS,
        ListingComplianceRules.DIMENSION_STATES,
        SERVER_ENFORCEMENT_NOTE,
        BOUNDARY_NOTE,
        "上游 = 16 合规与风控（16.2.1.1 规则库 / 16.2.2.2 认证，均未交付）、03 品牌授权状态、"
            + "02 平台规则与 4.2.3.1 平台类目映射；下游 = 4.3.1.1 一键刊登（阻断发布）。");
  }

  // ==================================================================
  // 扫描
  // ==================================================================

  /**
   * 🔴 触发一次扫描。
   *
   * <p>扫描的内容来源是**编辑器草稿**。产出：一条扫描记录 + 一份风险清单。
   */
  public ListingComplianceScanResult scan(UUID draftId, String triggerSource, String bearer) {
    String trigger = triggerSource == null || triggerSource.isBlank() ? "MANUAL" : triggerSource.trim();
    if (!List.of("MANUAL", "PRE_PUBLISH").contains(trigger)) {
      throw unprocessable("未知的触发来源：" + triggerSource + "。可选：MANUAL / PRE_PUBLISH");
    }
    Record draft = loadDraft(draftId);
    if (draft == null) {
      throw notFound();
    }
    String platformCode = draft.get("platform_code", String.class);
    String marketplaceCode = draft.get("marketplace_code", String.class);
    String locale = draft.get("locale", String.class);
    Map<String, Object> fieldValues = readMap(draft.get("field_values"));
    List<DraftField> fields = extractFields(fieldValues);

    List<RiskDraft> risks = new ArrayList<>();
    List<DimensionOutcome> outcomes = new ArrayList<>();

    // ---- 词维度（三个类型各算一个维度）----
    List<WordEntry> words = loadWords(platformCode, locale);
    Map<String, List<RiskDraft>> wordsByDimension = new LinkedHashMap<>();
    for (RiskDraft risk : wordRisks(words, fields)) {
      wordsByDimension.computeIfAbsent(risk.dimension(), key -> new ArrayList<>()).add(risk);
    }
    for (String dimension : List.of("PROHIBITED_WORD", "SENSITIVE_WORD", "SUPERLATIVE_WORD")) {
      List<RiskDraft> found = wordsByDimension.getOrDefault(dimension, List.of());
      risks.addAll(found);
      outcomes.add(
          DimensionResult.checked(
                  dimension,
                  found,
                  "listing_compliance_word（本模块自有的租户级词表）",
                  found.isEmpty() ? "租户词表里没有这一类词，未命中" : "命中 " + found.size() + " 处")
              .outcome());
    }

    // ---- 品牌侵权维度 ----
    DimensionResult brand = brandDimension(draft, bearer);
    risks.addAll(brand.risks());
    outcomes.add(brand.outcome());

    // ---- 平台类目必填维度 ----
    DimensionResult category =
        categoryDimension(
            platformCode, marketplaceCode, draft.get("category_path", String.class), fieldValues, bearer);
    risks.addAll(category.risks());
    outcomes.add(category.outcome());

    // ---- 图片合规维度 ----
    DimensionResult image =
        imageDimension(platformCode, fields, bearer);
    risks.addAll(image.risks());
    outcomes.add(image.outcome());

    // ---- 认证缺失维度：16.2.2.2 未交付 ⇒ 恒为 UNKNOWN，**不计入分母、也不算通过** ----
    outcomes.add(
        DimensionResult.unknown(
                "CERTIFICATION",
                "16.2.2.2 认证与资质（未交付）",
                OWNER_CERTIFICATION,
                "16.2.2.2 认证与资质在集成头尚未交付，没有认证档案来源；"
                    + "本维度量不到，**不计入已判定维度的分母、也不算通过**")
            .outcome());

    // ---- 落库 ----
    int unknownDimensions = (int) outcomes.stream().filter(o -> "UNKNOWN".equals(o.state())).count();
    int evaluatedDimensions = outcomes.size() - unknownDimensions;
    OffsetDateTime scannedAt = OffsetDateTime.now(ZoneOffset.UTC);
    UUID scanId = UUID.randomUUID();
    String summary = writeJson(outcomes);
    // 先按「还没有风险行」的比例插一行；风险行落库后由 DB 触发器把计数与结论一起算出来。
    String provisional =
        unknownDimensions > 0 ? "INCOMPLETE" : "PASS";
    sql.execute(
        """
        insert into listing_compliance_scan(
          id, tenant_id, draft_id, shop_id, platform_id, marketplace_id, locale,
          state, conclusion, trigger_source, risk_count, blocking_count, warning_count,
          dimension_summary, evaluated_dimension_count, total_dimension_count,
          unknown_dimension_count, scanned_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?::jsonb, ?, ?, ?, ?::timestamptz)
        """,
        scanId,
        draft.get("tenant_id", UUID.class),
        draftId,
        draft.get("shop_id", UUID.class),
        draft.get("platform_id", UUID.class),
        draft.get("marketplace_id", UUID.class),
        locale,
        unknownDimensions > 0 ? "PARTIAL" : "COMPLETED",
        provisional,
        trigger,
        summary,
        evaluatedDimensions,
        outcomes.size(),
        unknownDimensions,
        scannedAt);
    insertRisks(scanId, risks);

    return loadScanResult(scanId);
  }

  /** 品牌侵权维度：源商品 → 品牌名 → 租户品牌库 → 授权凭证。 */
  private DimensionResult brandDimension(Record draft, String bearer) {
    String sourceNote = "catalog GET /catalog/skus/{id} + /catalog/brands + /catalog/brands/{id}/authorizations";
    UUID skuId = draft.get("source_sku_id", UUID.class);
    if (skuId == null) {
      return DimensionResult.unknown(
          "BRAND_INFRINGEMENT",
          sourceNote,
          "3.2.1.1",
          "该草稿没有关联源商品，品牌授权状态这一维度判不了（不算通过）");
    }
    Upstream<SkuDetail> sku = source.sku(skuId.toString(), bearer);
    if (!sku.available()) {
      return DimensionResult.unknown("BRAND_INFRINGEMENT", sourceNote, "3.1.1.1", sku.reason());
    }
    String brandName = sku.orNull().brand();
    if (brandName == null || brandName.isBlank()) {
      return DimensionResult.checked(
          "BRAND_INFRINGEMENT",
          List.of(),
          sourceNote,
          "源商品没有品牌，本维度不适用");
    }
    Upstream<List<BrandRow>> brands = source.brands(brandName, bearer);
    if (!brands.available()) {
      return DimensionResult.unknown("BRAND_INFRINGEMENT", sourceNote, "3.2.1.1", brands.reason());
    }
    BrandRow matched = pickBrand(brands.orNull(), brandName);
    if (matched == null) {
      return brandRisks(brandName, null, List.of(), AUTHORIZATION_EXPIRY_WARN_DAYS);
    }
    Upstream<List<BrandAuthorization>> authorizations =
        source.brandAuthorizations(matched.id(), bearer);
    if (!authorizations.available()) {
      return DimensionResult.unknown("BRAND_INFRINGEMENT", sourceNote, "3.2.1.1", authorizations.reason());
    }
    return brandRisks(brandName, matched, authorizations.orNull(), AUTHORIZATION_EXPIRY_WARN_DAYS);
  }

  /** 品牌匹配：优先名称完全相等（大小写无关），否则取第一条关键词命中。 */
  private static BrandRow pickBrand(List<BrandRow> brands, String brandName) {
    if (brands == null || brands.isEmpty()) {
      return null;
    }
    for (BrandRow brand : brands) {
      if (brand.name() != null && brand.name().trim().equalsIgnoreCase(brandName.trim())) {
        return brand;
      }
    }
    return brands.get(0);
  }

  /** 平台类目必填维度：平台 + 站点 + 类目路径 → 4.2.3.1 的映射 → 必填属性清单。 */
  private DimensionResult categoryDimension(
      String platformCode,
      String marketplaceCode,
      String categoryPath,
      Map<String, Object> fieldValues,
      String bearer) {
    String sourceNote =
        "4.2.3.1 平台类目映射（catalog GET /catalog/category-mappings + /resolve）";
    if (categoryPath == null || categoryPath.isBlank()) {
      return DimensionResult.unknown(
          "CATEGORY_REQUIRED",
          sourceNote,
          "4.2.3.1",
          "草稿没有平台类目路径，解析不出平台类目必填清单（不算通过）");
    }
    Upstream<List<CategoryMappingRow>> mappings =
        source.categoryMappings(platformCode, marketplaceCode, bearer);
    if (!mappings.available()) {
      return DimensionResult.unknown("CATEGORY_REQUIRED", sourceNote, "4.2.3.1", mappings.reason());
    }
    CategoryMappingRow matched = null;
    for (CategoryMappingRow mapping : mappings.orNull()) {
      if (mapping.enabled()
          && mapping.platformCategoryPath() != null
          && mapping.platformCategoryPath().trim().equals(categoryPath.trim())) {
        matched = mapping;
        break;
      }
    }
    if (matched == null) {
      return DimensionResult.unknown(
          "CATEGORY_REQUIRED",
          sourceNote,
          "4.2.3.1",
          "该平台 / 站点下没有匹配类目路径「" + categoryPath + "」的映射（不算通过）");
    }
    Upstream<CategoryResolveResult> resolved =
        source.categoryResolve(matched.internalCategoryId(), platformCode, marketplaceCode, bearer);
    if (!resolved.available()) {
      return DimensionResult.unknown("CATEGORY_REQUIRED", sourceNote, "4.2.3.1", resolved.reason());
    }
    Map<String, String> attributes = attributePairs(fieldValues);
    return categoryRisks(attributes, resolved.orNull().requiredAttributes());
  }

  /** 草稿里已填的类目属性（键值）。 */
  private static Map<String, String> attributePairs(Map<String, Object> fieldValues) {
    Object raw = fieldValues == null ? null : fieldValues.get("CATEGORY_ATTRIBUTES");
    Map<String, String> pairs = new LinkedHashMap<>();
    if (raw instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        pairs.put(
            String.valueOf(entry.getKey()),
            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
      }
    }
    return pairs;
  }

  /** 图片合规维度：图片集引用 → 媒体库素材 → 目标平台图片规格。 */
  private DimensionResult imageDimension(String platformCode, List<DraftField> fields, String bearer) {
    String sourceNote =
        "catalog GET /catalog/media（03 媒体库）+ GET /catalog/media/processing/recipes（目标平台图片规格）";
    List<String> refs = new ArrayList<>();
    for (DraftField field : fields) {
      if ("IMAGES".equals(field.key())) {
        refs.addAll(field.texts());
      }
    }
    if (refs.isEmpty()) {
      return DimensionResult.checked("IMAGE", List.of(), sourceNote, "该草稿没有配图，本维度不适用");
    }
    Upstream<List<ImageRecipe>> recipes = source.imageRecipes(bearer);
    if (!recipes.available()) {
      return DimensionResult.unknown("IMAGE", sourceNote, "3.4.1.2", recipes.reason());
    }
    ImageRecipe recipe = pickRecipe(recipes.orNull(), platformCode);
    Map<String, MediaAsset> assets = new LinkedHashMap<>();
    for (String ref : refs) {
      Upstream<List<MediaAsset>> media = source.media(ref, bearer);
      if (!media.available()) {
        return DimensionResult.unknown("IMAGE", sourceNote, "3.4.1.1", media.reason());
      }
      for (MediaAsset asset : media.orNull()) {
        if (asset.storageKey() != null && asset.storageKey().equals(ref)) {
          assets.put(asset.id() == null ? ref : asset.id(), asset);
        } else if (asset.id() != null && asset.id().equals(ref)) {
          assets.put(asset.id(), asset);
        }
      }
    }
    Map<String, List<AssetJobRow>> histories = new LinkedHashMap<>();
    for (MediaAsset asset : assets.values()) {
      if (asset.id() == null) {
        continue;
      }
      Upstream<List<AssetJobRow>> history = source.assetHistory(asset.id(), bearer);
      if (history.available()) {
        histories.put(asset.id(), history.orNull());
      }
    }
    return imageRisks(refs, List.copyOf(assets.values()), recipe, histories);
  }

  /** 目标平台的图片处理配方（取第一条启用的）。 */
  private static ImageRecipe pickRecipe(List<ImageRecipe> recipes, String platformCode) {
    if (recipes == null) {
      return null;
    }
    for (ImageRecipe recipe : recipes) {
      if (!recipe.enabled()) {
        continue;
      }
      String target = recipe.targetPlatform();
      if (target != null && target.trim().equalsIgnoreCase(platformCode == null ? "" : platformCode.trim())) {
        return recipe;
      }
    }
    return null;
  }

  // ==================================================================
  // 扫描结果读出
  // ==================================================================

  /** 扫描历史（真分页 + 真筛选）。 */
  public ListingComplianceScanPage scans(int page, int size, UUID draftId, String state, String conclusion) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    StringBuilder where = new StringBuilder(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (draftId != null) {
      where.append(" and c.draft_id = ?");
      binds.add(draftId);
    }
    String wantedState = blankToNull(state);
    if (wantedState != null) {
      if (!List.of("COMPLETED", "PARTIAL").contains(wantedState)) {
        throw unprocessable("未知的扫描状态：" + state + "。可选：COMPLETED / PARTIAL");
      }
      where.append(" and c.state = ?");
      binds.add(wantedState);
    }
    String wantedConclusion = blankToNull(conclusion);
    if (wantedConclusion != null) {
      if (!ListingComplianceRules.CONCLUSIONS.contains(wantedConclusion)) {
        throw unprocessable(
            "未知的结论：" + conclusion + "。可选：" + String.join(" / ", ListingComplianceRules.CONCLUSIONS));
      }
      where.append(" and c.conclusion = ?");
      binds.add(wantedConclusion);
    }
    long total =
        ((Number) sql.fetchValue("select count(*) from listing_compliance_scan c" + where, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingComplianceScanRow> items =
        sql.fetch(
                """
                select c.id,c.draft_id,c.shop_id,p.code as platform_code,m.code as marketplace_code,
                       c.locale,c.state,c.conclusion,c.trigger_source,c.risk_count,c.blocking_count,
                       c.warning_count,c.evaluated_dimension_count,c.total_dimension_count,c.scanned_at
                from listing_compliance_scan c
                join platform p on p.id=c.platform_id
                join marketplace m on m.id=c.marketplace_id
                """
                    + where
                    + " order by c.scanned_at desc, c.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::scanRow)
            .toList();
    return new ListingComplianceScanPage(items, total, safePage, safeSize);
  }

  /** 单次扫描的详情（含风险清单）。 */
  public ListingComplianceScanResult scanDetail(UUID scanId) {
    Record row = sql.fetchOne("select * from listing_compliance_scan where tenant_id = " + TENANT + " and id = ?", scanId);
    if (row == null) {
      throw notFound();
    }
    return loadScanResult(scanId);
  }

  // ==================================================================
  // 🔴 发布闸门（服务端强制校验的那一半）
  // ==================================================================

  /**
   * 🔴 发布闸门：**服务端真算**的「这份内容能不能发」。
   *
   * <p>这不是前端的提示，是服务端对「未修复不允许发布」这一条的执行：
   * 有未修复的阻断项 ⇒ {@code allowed=false} + 逐条阻断项。
   * DB 层还有一道触发器把 4.2.1.1 的入队动作直接拒掉。
   */
  public ListingCompliancePublishGate publishGate(UUID draftId) {
    Record scan =
        sql.fetchOne(
            """
            select * from listing_compliance_scan
             where tenant_id = """
                + TENANT
                + " and draft_id = ? order by scanned_at desc, id desc limit 1",
            draftId);
    if (scan == null) {
      return new ListingCompliancePublishGate(
          draftId,
          true,
          "NOT_SCANNED",
          "INCOMPLETE",
          0,
          0,
          0,
          ListingComplianceRules.DIMENSIONS.size(),
          List.of(),
          List.of(),
          "",
          "这份内容还没有扫描过：服务端没有任何已知的阻断项，但也没有任何预检证据。"
              + "「没扫过」不等于「合规通过」——请先做一次合规预检。");
    }
    List<ListingComplianceRiskRow> risks = loadRisks(scan.get("id", UUID.class));
    List<ListingComplianceRiskRow> blockers = new ArrayList<>();
    List<ListingComplianceRiskRow> warnings = new ArrayList<>();
    for (ListingComplianceRiskRow risk : risks) {
      if ("BLOCKING".equals(risk.severity()) && "OPEN".equals(risk.status())) {
        blockers.add(risk);
      } else if ("WARNING".equals(risk.severity()) && "OPEN".equals(risk.status())) {
        warnings.add(risk);
      }
    }
    String conclusion = scan.get("conclusion", String.class);
    return new ListingCompliancePublishGate(
        draftId,
        blockers.isEmpty(),
        "SCANNED",
        conclusion,
        blockers.size(),
        warnings.size(),
        scan.get("evaluated_dimension_count", Integer.class),
        scan.get("total_dimension_count", Integer.class),
        blockers,
        warnings,
        scan.get("id", UUID.class).toString(),
        blockers.isEmpty()
            ? "服务端已判定：没有未修复的阻断项。"
                + (conclusion != null && "INCOMPLETE".equals(conclusion)
                    ? "但仍有维度未能判定（结论是 INCOMPLETE，不是「合规通过」）。"
                    : "")
            : "服务端已拦截：这份内容有 "
                + blockers.size()
                + " 条未修复的阻断项，**未修复不允许发布**。");
  }

  // ==================================================================
  // 风险处置
  // ==================================================================

  /** 人工处置一批风险（标记已修复 / 已忽略 / 重新打开）。 */
  public int decideRisks(List<UUID> riskIds, String status, String note) {
    if (riskIds == null || riskIds.isEmpty()) {
      throw unprocessable("请至少选择一条风险");
    }
    if (!List.of("OPEN", "RESOLVED", "IGNORED").contains(status)) {
      throw unprocessable("未知的处置状态：" + status + "。可选：OPEN / RESOLVED / IGNORED");
    }
    int changed = 0;
    for (UUID riskId : riskIds) {
      changed +=
          sql.execute(
              """
              update listing_compliance_risk
                 set status = ?,
                     resolved_at = case when ? = 'OPEN' then null else now() end,
                     evidence = evidence || ?::jsonb
               where tenant_id = """
                  + TENANT
                  + " and id = ?",
              status,
              status,
              writeJson(Map.of("decision", status, "note", note == null ? "" : note)),
              riskId);
    }
    return changed;
  }

  // ==================================================================
  // 违禁词库
  // ==================================================================

  /** 词库列表（真分页 + 真筛选）。 */
  public ListingComplianceWordPage words(
      int page,
      int size,
      String category,
      String severity,
      String riskLevel,
      String platformCode,
      String keyword,
      Boolean enabled) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    StringBuilder where = new StringBuilder(" where w.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String wantedCategory = blankToNull(category);
    if (wantedCategory != null) {
      if (!ListingComplianceRules.WORD_CATEGORIES.contains(wantedCategory)) {
        throw unprocessable(
            "未知的词类型：" + category + "。可选：" + String.join(" / ", ListingComplianceRules.WORD_CATEGORIES));
      }
      where.append(" and w.category = ?");
      binds.add(wantedCategory);
    }
    String wantedSeverity = blankToNull(severity);
    if (wantedSeverity != null) {
      if (!ListingComplianceRules.SEVERITIES.contains(wantedSeverity)) {
        throw unprocessable(
            "未知的处置：" + severity + "。可选：" + String.join(" / ", ListingComplianceRules.SEVERITIES));
      }
      where.append(" and w.severity = ?");
      binds.add(wantedSeverity);
    }
    String wantedLevel = blankToNull(riskLevel);
    if (wantedLevel != null) {
      if (!ListingComplianceRules.RISK_LEVELS.contains(wantedLevel)) {
        throw unprocessable(
            "未知的风险等级：" + riskLevel + "。可选：" + String.join(" / ", ListingComplianceRules.RISK_LEVELS));
      }
      where.append(" and w.risk_level = ?");
      binds.add(wantedLevel);
    }
    String wantedPlatform = blankToNull(platformCode);
    if (wantedPlatform != null) {
      where.append(" and w.platform_code = ?");
      binds.add(wantedPlatform);
    }
    String wantedKeyword = blankToNull(keyword);
    if (wantedKeyword != null) {
      where.append(" and (w.word ilike ? or w.display_word ilike ? or w.rule_source ilike ?)");
      binds.add("%" + wantedKeyword + "%");
      binds.add("%" + wantedKeyword + "%");
      binds.add("%" + wantedKeyword + "%");
    }
    if (enabled != null) {
      where.append(" and w.enabled = ?");
      binds.add(enabled);
    }
    long total =
        ((Number) sql.fetchValue("select count(*) from listing_compliance_word w" + where, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingComplianceWordRow> items =
        sql.fetch(
                "select * from listing_compliance_word w"
                    + where
                    + " order by w.updated_at desc, w.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::wordRow)
            .toList();
    return new ListingComplianceWordPage(items, total, safePage, safeSize);
  }

  /** 新建 / 更新一个词。 */
  public ListingComplianceWordRow saveWord(UUID id, ListingComplianceWordInput input) {
    String display = input.word().trim();
    String normalized = normalizeWord(display);
    if (normalized.isEmpty()) {
      throw unprocessable("词不能为空");
    }
    if (!ListingComplianceRules.WORD_CATEGORIES.contains(input.category())) {
      throw unprocessable(
          "未知的词类型：" + input.category() + "。可选：" + String.join(" / ", ListingComplianceRules.WORD_CATEGORIES));
    }
    if (!ListingComplianceRules.SEVERITIES.contains(input.severity())) {
      throw unprocessable(
          "未知的处置：" + input.severity() + "。可选：" + String.join(" / ", ListingComplianceRules.SEVERITIES));
    }
    if (!ListingComplianceRules.RISK_LEVELS.contains(input.riskLevel())) {
      throw unprocessable(
          "未知的风险等级：" + input.riskLevel() + "。可选：" + String.join(" / ", ListingComplianceRules.RISK_LEVELS));
    }
    if (input.ruleSource() == null || input.ruleSource().trim().isEmpty()) {
      throw unprocessable("规则来源不能为空：风险清单四要素之一，缺了运营就不知道依据什么");
    }
    if (input.suggestion() == null || input.suggestion().trim().isEmpty()) {
      throw unprocessable("建议替换不能为空：风险清单四要素之一，缺了运营就不知道该怎么改");
    }
    String platformCode = blankToNull(input.platformCode());
    if (platformCode != null) {
      boolean known =
          sql.fetchValue(
                  "select count(*) from platform where code = ?", platformCode)
              != null;
      if (!known) {
        throw unprocessable("平台编码不存在：" + platformCode);
      }
    }
    if (id == null) {
      UUID wordId = UUID.randomUUID();
      sql.execute(
          """
          insert into listing_compliance_word(
            id, tenant_id, word, display_word, category, severity, risk_level,
            rule_source, suggestion, platform_code, locale, enabled, note)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
          """,
          wordId,
          currentTenant(),
          normalized,
          display,
          input.category(),
          input.severity(),
          input.riskLevel(),
          input.ruleSource().trim(),
          input.suggestion().trim(),
          platformCode,
          blankToNull(input.locale()),
          input.note() == null ? "" : input.note());
      return loadWord(wordId);
    }
    int changed =
        sql.execute(
            """
            update listing_compliance_word
               set word = ?, display_word = ?, category = ?, severity = ?, risk_level = ?,
                   rule_source = ?, suggestion = ?, platform_code = ?, locale = ?, note = ?,
                   updated_at = now()
             where tenant_id = """
                + TENANT
                + " and id = ?",
            normalized,
            display,
            input.category(),
            input.severity(),
            input.riskLevel(),
            input.ruleSource().trim(),
            input.suggestion().trim(),
            platformCode,
            blankToNull(input.locale()),
            input.note() == null ? "" : input.note(),
            id);
    if (changed == 0) {
      throw notFound();
    }
    return loadWord(id);
  }

  /** 启停一个词。 */
  public ListingComplianceWordRow setWordEnabled(UUID id, boolean enabled) {
    int changed =
        sql.execute(
            "update listing_compliance_word set enabled = ?, updated_at = now() where tenant_id = "
                + TENANT
                + " and id = ?",
            enabled,
            id);
    if (changed == 0) {
      throw notFound();
    }
    return loadWord(id);
  }

  /** 删除一个词。 */
  public void deleteWord(UUID id) {
    int changed =
        sql.execute("delete from listing_compliance_word where tenant_id = " + TENANT + " and id = ?", id);
    if (changed == 0) {
      throw notFound();
    }
  }

  // ==================================================================
  // 可扫描的草稿 + 筛选项
  // ==================================================================

  /** 可扫描的编辑器草稿（真分页 + 真搜索）。 */
  public ListingComplianceDraftPage drafts(int page, int size, UUID shopId, String keyword) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    StringBuilder where = new StringBuilder(" where d.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (shopId != null) {
      where.append(" and d.shop_id = ?");
      binds.add(shopId);
    }
    String wantedKeyword = blankToNull(keyword);
    if (wantedKeyword != null) {
      where.append(" and (d.locale ilike ? or d.category_path ilike ?)");
      binds.add("%" + wantedKeyword + "%");
      binds.add("%" + wantedKeyword + "%");
    }
    long total =
        ((Number) sql.fetchValue("select count(*) from listing_editor_draft d" + where, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<ListingComplianceDraftRow> items =
        sql.fetch(
                """
                select d.id,d.shop_id,p.code as platform_code,m.code as marketplace_code,d.locale,
                       d.source_sku_id,d.listing_id,d.category_path,d.state,d.updated_at,
                       (select c.conclusion from listing_compliance_scan c
                         where c.tenant_id=d.tenant_id and c.draft_id=d.id
                         order by c.scanned_at desc, c.id desc limit 1) as last_conclusion,
                       (select c.blocking_count from listing_compliance_scan c
                         where c.tenant_id=d.tenant_id and c.draft_id=d.id
                         order by c.scanned_at desc, c.id desc limit 1) as last_blocking_count
                from listing_editor_draft d
                join platform p on p.id=d.platform_id
                join marketplace m on m.id=d.marketplace_id
                """
                    + where
                    + " order by d.updated_at desc, d.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::draftRow)
            .toList();
    return new ListingComplianceDraftPage(items, total, safePage, safeSize);
  }

  /** 筛选项词表（真实数据，不硬编码在前端）。 */
  public ListingComplianceFilterOptions filterOptions() {
    List<ListingCompliancePlatformOption> platforms =
        sql.fetch("select code,name from platform order by position, code").stream()
            .map(r -> new ListingCompliancePlatformOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<ListingComplianceShopOption> shops =
        sql.fetch(
                "select s.id,s.code,s.name,p.code as platform_code from shop s"
                    + " join platform p on p.id=s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by s.code")
            .stream()
            .map(
                r ->
                    new ListingComplianceShopOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("platform_code", String.class)))
            .toList();
    return new ListingComplianceFilterOptions(
        ListingComplianceRules.WORD_CATEGORIES,
        ListingComplianceRules.RISK_LEVELS,
        ListingComplianceRules.SEVERITIES,
        ListingComplianceRules.DIMENSIONS,
        platforms,
        shops);
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private Record loadDraft(UUID draftId) {
    return sql.fetchOne(
        """
        select d.id,d.tenant_id,d.shop_id,d.platform_id,d.marketplace_id,d.locale,d.source_sku_id,
               d.listing_id,d.category_path,d.state,d.field_values,d.updated_at,
               p.code as platform_code,m.code as marketplace_code
          from listing_editor_draft d
          join platform p on p.id=d.platform_id
          join marketplace m on m.id=d.marketplace_id
         where d.tenant_id = """
            + TENANT
            + " and d.id = ?",
        draftId);
  }

  private void insertRisks(UUID scanId, List<RiskDraft> risks) {
    for (RiskDraft risk : risks) {
      sql.execute(
          """
          insert into listing_compliance_risk(
            id, tenant_id, scan_id, dimension, severity, risk_level, code,
            field_key, field_label, matched_text, occurrence_index,
            rule_source, rule_owner_module, suggestion, message, owner_module,
            evidence, status)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'OPEN')
          """,
          UUID.randomUUID(),
          currentTenant(),
          scanId,
          risk.dimension(),
          risk.severity(),
          risk.riskLevel(),
          risk.code(),
          risk.fieldKey(),
          risk.fieldLabel(),
          risk.matchedText(),
          risk.occurrenceIndex(),
          risk.ruleSource(),
          risk.ruleOwnerModule(),
          risk.suggestion(),
          risk.message(),
          risk.ownerModule(),
          writeJson(risk.evidenceOrEmpty()));
    }
  }

  private ListingComplianceScanResult loadScanResult(UUID scanId) {
    Record row =
        sql.fetchOne(
            """
            select c.id,c.draft_id,c.shop_id,p.code as platform_code,m.code as marketplace_code,
                   c.locale,c.state,c.conclusion,c.trigger_source,c.risk_count,c.blocking_count,
                   c.warning_count,c.evaluated_dimension_count,c.total_dimension_count,
                   c.unknown_dimension_count,c.dimension_summary,c.scanned_at
              from listing_compliance_scan c
              join platform p on p.id=c.platform_id
              join marketplace m on m.id=c.marketplace_id
             where c.tenant_id = """
                + TENANT
                + " and c.id = ?",
            scanId);
    if (row == null) {
      throw notFound();
    }
    return new ListingComplianceScanResult(
        row.get("id", UUID.class),
        row.get("draft_id", UUID.class),
        row.get("shop_id", UUID.class),
        row.get("platform_code", String.class),
        row.get("marketplace_code", String.class),
        row.get("locale", String.class),
        row.get("state", String.class),
        row.get("conclusion", String.class),
        row.get("trigger_source", String.class),
        row.get("risk_count", Integer.class),
        row.get("blocking_count", Integer.class),
        row.get("warning_count", Integer.class),
        row.get("evaluated_dimension_count", Integer.class),
        row.get("total_dimension_count", Integer.class),
        readDimensions(row.get("dimension_summary")),
        loadRisks(scanId),
        row.get("scanned_at", OffsetDateTime.class));
  }

  private List<ListingComplianceRiskRow> loadRisks(UUID scanId) {
    return sql.fetch(
            """
            select r.* from listing_compliance_risk r
             where r.tenant_id = """
                + TENANT
                + " and r.scan_id = ? order by (r.severity='BLOCKING') desc, r.risk_level, r.field_key, r.id",
            scanId)
        .stream()
        .map(this::riskRow)
        .toList();
  }

  private List<ListingComplianceDimension> readDimensions(Object raw) {
    List<ListingComplianceDimension> out = new ArrayList<>();
    JsonNode node = readNode(raw);
    if (node == null || !node.isArray()) {
      return out;
    }
    for (JsonNode item : node) {
      out.add(
          new ListingComplianceDimension(
              item.path("dimension").asText(""),
              item.path("label").asText(""),
              item.path("state").asText("UNKNOWN"),
              item.path("riskCount").asInt(0),
              item.path("blockingCount").asInt(0),
              item.path("warningCount").asInt(0),
              item.path("source").asText(""),
              item.path("ownerModule").isNull() || item.path("ownerModule").asText("").isBlank()
                  ? null
                  : item.path("ownerModule").asText(""),
              item.path("note").asText("")));
    }
    return out;
  }

  private ListingComplianceScanRow scanRow(Record r) {
    return new ListingComplianceScanRow(
        r.get("id", UUID.class),
        r.get("draft_id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("marketplace_code", String.class),
        r.get("locale", String.class),
        r.get("state", String.class),
        r.get("conclusion", String.class),
        r.get("trigger_source", String.class),
        r.get("risk_count", Integer.class),
        r.get("blocking_count", Integer.class),
        r.get("warning_count", Integer.class),
        r.get("evaluated_dimension_count", Integer.class),
        r.get("total_dimension_count", Integer.class),
        r.get("scanned_at", OffsetDateTime.class));
  }

  private ListingComplianceRiskRow riskRow(Record r) {
    return new ListingComplianceRiskRow(
        r.get("id", UUID.class),
        r.get("scan_id", UUID.class),
        r.get("dimension", String.class),
        ListingComplianceRules.dimensionLabel(r.get("dimension", String.class)),
        r.get("severity", String.class),
        r.get("risk_level", String.class),
        r.get("code", String.class),
        r.get("field_key", String.class),
        r.get("field_label", String.class),
        r.get("matched_text", String.class),
        r.get("occurrence_index", Integer.class),
        r.get("rule_source", String.class),
        r.get("rule_owner_module", String.class),
        r.get("suggestion", String.class),
        r.get("message", String.class),
        r.get("owner_module", String.class),
        readEvidence(r.get("evidence")),
        r.get("status", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private ListingComplianceWordRow wordRow(Record r) {
    return new ListingComplianceWordRow(
        r.get("id", UUID.class),
        r.get("word", String.class),
        r.get("display_word", String.class),
        r.get("category", String.class),
        r.get("severity", String.class),
        r.get("risk_level", String.class),
        r.get("rule_source", String.class),
        r.get("suggestion", String.class),
        r.get("platform_code", String.class),
        r.get("locale", String.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private ListingComplianceDraftRow draftRow(Record r) {
    Integer blocking = r.get("last_blocking_count", Integer.class);
    return new ListingComplianceDraftRow(
        r.get("id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("marketplace_code", String.class),
        r.get("locale", String.class),
        r.get("source_sku_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("category_path", String.class),
        r.get("state", String.class),
        r.get("last_conclusion", String.class),
        blocking == null ? 0 : blocking,
        r.get("updated_at", OffsetDateTime.class));
  }

  private ListingComplianceWordRow loadWord(UUID id) {
    Record r =
        sql.fetchOne("select * from listing_compliance_word where tenant_id = " + TENANT + " and id = ?", id);
    if (r == null) {
      throw notFound();
    }
    return wordRow(r);
  }

  /** 扫描用的词表：启用 + 平台维度（空 = 全平台）+ 语言维度（空 = 全语言）。 */
  private List<WordEntry> loadWords(String platformCode, String locale) {
    return sql.fetch(
            """
            select word, display_word, category, severity, risk_level, rule_source, suggestion,
                   platform_code, locale
              from listing_compliance_word
             where tenant_id = """
                + TENANT
                + " and enabled"
                + " and (platform_code is null or platform_code = ?)"
                + " and (locale is null or locale = ?)"
                + " order by category, word",
            platformCode == null ? "" : platformCode,
            locale == null ? "" : locale)
        .stream()
        .map(
            r ->
                new WordEntry(
                    r.get("word", String.class),
                    r.get("display_word", String.class),
                    r.get("category", String.class),
                    r.get("severity", String.class),
                    r.get("risk_level", String.class),
                    r.get("rule_source", String.class),
                    r.get("suggestion", String.class),
                    r.get("platform_code", String.class),
                    r.get("locale", String.class)))
        .toList();
  }

  // ---- 小工具 ----

  private static String normalizeWord(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

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

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("序列化失败", e);
    }
  }

  private JsonNode readNode(Object raw) {
    try {
      String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
      if (value == null || value.isBlank()) {
        return null;
      }
      return json.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMap(Object raw) {
    JsonNode node = readNode(raw);
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    return json.convertValue(node, Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readEvidence(Object raw) {
    JsonNode node = readNode(raw);
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    return json.convertValue(node, Map.class);
  }
}
