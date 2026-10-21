package com.starcon.channel;

import static com.starcon.channel.ListingClaimModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 4.4.2.1 商品认领。
 *
 * <p>蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * <p>🔴 <b>归属 channel 而不是 catalog</b>：源与目标都是 channel 自己的事实（{@code listing} 必须按
 * {@code shop} / {@code shop_marketplace} 收敛可见性，那两张表带 data_scope RESTRICTIVE 策略读
 * {@code app.grants}）。放 catalog 就得为店铺维度数据范围去读 channel 的表 —— 那是跨服务 SQL。
 * 与 4.1.1.1 / 4.1.1.2 / 4.3.1.2 / 4.3.2.1 同一先例。
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>输入：源 listing</b> → {@link #claim} 的第一步：读 V171 {@code listing}（含店铺 / 站点 /
 *       平台），并取它的**当前内容快照**（V175 {@code listing_content_snapshot}）与**当前映射**
 *       （V176 {@code listing_mapping} / {@code listing_mapping_version}）。
 *   <li><b>输入：目标店铺与站点</b> → 目标店铺必须存在、在数据范围内、<b>与源店铺不同</b>、
 *       <b>与源店铺同平台</b>；目标站点必须已被目标店铺<b>启用</b>（{@code shop_marketplace.status='ACTIVE'}）。
 *   <li><b>输入：保留/重写的内容字段选择</b> → 逐字段 {@code KEEP} / {@code REWRITE} / {@code SKIP}，
 *       决策逐条落库（{@code listing_claim_field}），并写进目标草稿。
 *   <li><b>输出：目标店铺的 listing 草稿</b> → 目标 listing（{@code status='DRAFT'}、
 *       {@code review_status='NOT_SUBMITTED'}）+ 复制的内容快照 + 复制的变体与映射。
 *   <li><b>输出：待发布任务</b> → <b>复用</b> 4.3.1.2 的 V183 {@code publish_batch} / {@code publish_task}，
 *       <b>不另建平行发布任务表</b>。
 * </ul>
 *
 * 🔴 <b>三条边界在本类里各有一层物理落点，不是靠注释提醒：</b>
 *
 * <ol>
 *   <li><b>源 listing 不受影响</b>：本类对源 listing <b>只读</b>（全类没有一条 UPDATE/DELETE 打
 *       在源上），认领记录落库时 V190 的 {@code guard_claim_source_untouched} 触发器再复核一次
 *       （status / review_status / version 逐字相等）。
 *   <li><b>目标是草稿态、尚未过平台审核</b>：目标 listing 的 status / review_status /
 *       平台可售状态 / 评分 / 币种 / 上架时间由本类写死（草稿 + 未提交 + UNKNOWN + 全 NULL）；
 *       <b>不写 {@code listing_publish_record}</b>（那张表是"发布动作结果"，写了等于宣称发过了）。
 *   <li><b>不跨服务</b>：03 商品主数据只经逻辑 id {@code internal_sku_id} 引用，<b>不建 FK、不复制
 *       catalog 任何一列</b>；本类<b>不读也不建列</b>任何价格 / 内部可售量 / 销量事实。
 * </ol>
 */
@Service
public class ListingClaimService {

  /** 🔴 站点绑定的字段：跨站点认领时**不能**沿用源值（否则在目标站点写下假事实）。 */
  private static final Set<String> SITE_BOUND_FIELDS =
      Set.of("PLATFORM_CATEGORY_ID", "CATEGORY_ATTRIBUTES", "COMPLIANCE_STATEMENTS");

  /** JSON 字段与其 JSON 类型（array / object）。写进 V175 的快照时 DB 的 CHECK 会再兜一道。 */
  private static final Map<String, String> JSON_FIELDS =
      Map.of(
          "BULLETS", "array",
          "IMAGES", "array",
          "A_PLUS", "array",
          "CATEGORY_ATTRIBUTES", "object",
          "COMPLIANCE_STATEMENTS", "object");

  /** 内容字段在 V175 快照里的列名（其余字段在 V171 listing 行上）。 */
  private static final Map<String, String> SNAPSHOT_COLUMNS =
      Map.of(
          "BULLETS", "bullet_points",
          "IMAGES", "images",
          "DESCRIPTION", "description",
          "A_PLUS", "a_plus_modules",
          "VIDEO_URL", "video_url",
          "CATEGORY_ATTRIBUTES", "category_attributes",
          "COMPLIANCE_STATEMENTS", "compliance_statements");

  /** 纯文本字段（不是 JSON）：读的时候不能走 JSON 归一。 */
  private static final Set<String> TEXT_FIELDS = Set.of("DESCRIPTION", "VIDEO_URL");

  private static final String MODE_KEEP = "KEEP";
  private static final String MODE_REWRITE = "REWRITE";
  private static final String MODE_SKIP = "SKIP";

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingClaimService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingDetailService 的同名写法，两个类不共享私有方法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** jsonb 的 JDBC 形态随驱动而变，优先取 PGObject.getValue()。 */
  private String jsonText(Object raw) {
    if (raw == null) return "";
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if ("{}".equals(value) || "[]".equals(value) || value.isBlank()) return value;
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  /** 校验并归一请求里的 JSON 字段：写进 V175 快照前必须是对应类型的合法 JSON。 */
  private String jsonOfKind(String raw, String kind, String label) {
    String value = blankToNull(raw);
    if (value == null) return "array".equals(kind) ? "[]" : "{}";
    JsonNode node;
    try {
      node = json.readTree(value);
    } catch (Exception e) {
      throw unprocessable(label + " 必须是合法的 JSON");
    }
    if (node == null) return "array".equals(kind) ? "[]" : "{}";
    boolean ok = "array".equals(kind) ? node.isArray() : node.isObject();
    if (!ok) {
      throw unprocessable(
          label + " 必须是 JSON " + ("array".equals(kind) ? "数组" : "对象") + "，收到的是 " + node.getNodeType());
    }
    try {
      return json.writeValueAsString(node);
    } catch (Exception e) {
      throw unprocessable(label + " 不是可序列化的 JSON");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>未交付段一律 {@code available=false} + ownerModule + impact：售价（05）、内部可售量（07）、
   * 平台审核结果（4.3.1.1 / 4.3.2.2）、内部内容草稿（4.2.1.1 / 4.2.1.2）、跨平台认领。
   */
  public ListingClaimCapabilities capabilities() {
    return new ListingClaimCapabilities(
        List.of(
            new ListingClaimCapabilityDimension(
                "source_listing",
                "源 listing（平台商品、所属店铺与站点、平台状态）",
                true,
                "listing + shop / marketplace（V171 + V1，同库同服务）",
                "",
                "缺了它整条认领无从谈起"),
            new ListingClaimCapabilityDimension(
                "source_content_snapshot",
                "🔴 复用的源内容快照（标题 / 卖点 / 描述 / 图片 / 视频 / A+ / 类目属性 / 合规声明）",
                true,
                "listing_content_snapshot（V175）",
                "",
                "没有它就没有「原商品内容」可复用 —— 认领会退化成建一条空壳 listing"),
            new ListingClaimCapabilityDimension(
                "reuse_mapping",
                "🔴 复用的内部 SKU 映射（平台变体 → 内部 SKU 逻辑 id，含套装组件数量）",
                true,
                "listing_variant（V171）+ listing_mapping / listing_mapping_version（V176）",
                "",
                "映射不复用 = 目标商品全部未映射，订单落到它上面时没有内部商品可对"),
            new ListingClaimCapabilityDimension(
                "target_draft",
                "🔴 目标店铺的 listing 草稿（草稿态，尚未过平台审核）",
                true,
                "listing + listing_content_snapshot（V171 + V175）",
                "",
                "新店铺 listing 是全新平台资源：平台会重新审核，不继承源商品的审核结果"),
            new ListingClaimCapabilityDimension(
                "pending_publish_task",
                "🔴 待发布任务（复用 4.3.1.2 的发布队列与平台限流，不另建平行任务表）",
                true,
                "publish_batch / publish_task（V183）",
                "",
                "另建一张任务表 = 平台上出现两套待发布队列，4.3.1.2 的限流只管一套"),
            new ListingClaimCapabilityDimension(
                "internal_content_draft",
                "内部内容草稿（按平台 + 站点 + 语言保存的可发布内容）",
                false,
                "—",
                "4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑",
                "两者尚未交付 ⇒ 本模块登记的待发布任务 draftId 为空，"
                    + "内容以**本次认领登记的内容快照**为准。"
                    + "**不预建草稿表**——一张没有写入方的空表会被当成「草稿系统已交付」，比明写未接入危险得多"),
            new ListingClaimCapabilityDimension(
                "platform_approval",
                "平台审核结果（通过 / 驳回 / 抑制）",
                false,
                "—",
                "4.3.1.1 一键刊登 / 4.3.2.2 平台状态回流",
                "本模块只产出草稿与待发布任务，**不发起发布也不判审核**。"
                    + "认领状态里的 PUBLISHED 只表示「目标已离开草稿态」，不代表平台已批准"),
            new ListingClaimCapabilityDimension(
                "price",
                "listing 售价与币种",
                false,
                "—",
                "05 定价与价目系统",
                "卷 05 整卷未交付 ⇒ 目标草稿不带售价，源商品的币种也**不复制**"
                    + "（源店铺的币种在目标店铺不成立）"),
            new ListingClaimCapabilityDimension(
                "internal_stock",
                "内部可售量",
                false,
                "—",
                "07 库存系统",
                "卷 07 未交付 ⇒ 目标草稿的平台可售状态恒为 UNKNOWN（平台上还不存在这个商品），"
                    + "不复制源商品的平台可售量与可售状态"),
            new ListingClaimCapabilityDimension(
                "cross_platform_claim",
                "跨平台认领（A 店铺的平台 → B 店铺的另一个平台）",
                false,
                "—",
                "本模块范围外（按蓝图「跨店搬家」理解）",
                "平台类目 ID、站点、发布限流都按平台绑定；跨平台认领会被显式拒绝，"
                    + "不会悄悄生成一个目标站点不成立的类目 ID")),
        FIELDS,
        DECISIONS,
        CLAIM_STATES,
        true,
        "🔴 源 listing 不受影响：认领只读源商品，**不修改也不下架**。"
            + "V190 的 DB 触发器在认领记录落库时复核源商品的 status / review_status / version 逐字未变。",
        true,
        "🔴 目标恒为草稿态：status=DRAFT、review_status=NOT_SUBMITTED、平台可售状态=UNKNOWN。"
            + "**新店铺 listing 是全新平台资源，需重新过平台审核**；本模块不写发布记录，不假装已发布。",
        true,
        "待发布任务直接进 4.3.1.2 的发布批次（V183），沿用它的平台限流、暂停、重排与重试。",
        false,
        "05 定价与价目系统",
        "🔴 卷 05 定价整卷未交付：认领**不带售价**，也不复制源商品的币种 —— "
            + "源店铺的币种在目标店铺不成立。",
        "上游 = 4.1.1.2（listing + 平台内容快照 + 变体映射）；下游 = 4.3.1.1（一键刊登）与 4.3.1.2（批量发布派发）。"
            + "03 商品主数据只经逻辑 id 引用，**不跨服务读表**。");
  }

  // ==================================================================
  // 认领（核心动作）
  // ==================================================================

  /**
   * 🔴 认领：把源 listing 搬到目标店铺，生成目标草稿 + 待发布任务。
   *
   * <p>整个动作在**一个事务**里完成（{@code AccessService.run} 开的那个）：源商品只读、
   * 目标商品、内容快照、变体映射、待发布任务与认领记录一起落库 —— 出现半个结果（草稿建好了但
   * 认领记录没落）会让"这次搬家发生过没有"永远答不出来。
   */
  public ListingClaimRow claim(ClaimListingRequest request, UUID actorId) {
    UUID tenant = currentTenant();

    // ---- 1. 源 listing（只读）
    Record source =
        sql.fetchOne(
            """
            select l.id, l.shop_id, l.marketplace_id, l.external_item_id, l.title, l.category_path,
                   l.platform_category_id, l.status, l.review_status, l.version,
                   s.code as shop_code, s.name as shop_name, s.platform_id
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            where l.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
              and l.id=?
            """,
            request.sourceListingId());
    if (source == null) throw notFound();

    // ---- 2. 目标店铺
    Record targetShop =
        sql.fetchOne(
            """
            select id, platform_id, code, name from shop
            where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?
            """,
            request.targetShopId());
    if (targetShop == null) {
      throw unprocessable("目标店铺不存在或不在可见范围内：" + request.targetShopId());
    }
    UUID sourceShopId = source.get("shop_id", UUID.class);
    UUID sourcePlatformId = source.get("platform_id", UUID.class);
    UUID targetPlatformId = targetShop.get("platform_id", UUID.class);

    if (sourceShopId.equals(request.targetShopId())) {
      throw unprocessable("认领目标必须是**另一个店铺**：把商品认领到它自己所在的店铺不构成跨店搬家。");
    }
    if (!sourcePlatformId.equals(targetPlatformId)) {
      throw unprocessable(
          "🔴 跨平台认领不在本模块范围：源店铺在平台 "
              + platformCode(sourcePlatformId)
              + "，目标店铺在平台 "
              + platformCode(targetPlatformId)
              + "。平台类目 ID、站点与发布限流都按平台绑定，本模块只做**同平台跨店**搬家。");
    }

    // ---- 3. 目标站点（必须已被目标店铺启用）
    Record site =
        sql.fetchOne(
            """
            select m.id, m.code, m.name, sm.status
            from marketplace m
            join shop_marketplace sm on sm.tenant_id=? and sm.shop_id=? and sm.marketplace_id=m.id
            where m.id=?
            """,
            tenant, request.targetShopId(), request.targetMarketplaceId());
    if (site == null) {
      throw unprocessable(
          "目标店铺未启用该站点：" + request.targetMarketplaceId() + "（站点必须先在 2.3.1.1 启用）");
    }
    if (!"ACTIVE".equals(site.get("status", String.class))) {
      throw unprocessable(
          "目标店铺在该站点的状态是 " + site.get("status", String.class) + "（已停用），不能作为认领目标。");
    }
    UUID targetMarketplaceId = site.get("id", UUID.class);
    UUID sourceMarketplaceId = source.get("marketplace_id", UUID.class);
    boolean sameSite = sourceMarketplaceId != null && sourceMarketplaceId.equals(targetMarketplaceId);

    // ---- 4. 源内容快照（复用内容的唯一可信来源）
    Record snapshot = sourceSnapshot(tenant, request.sourceListingId(), request.language());
    if (snapshot == null) {
      throw unprocessable(
          "源 listing 尚未登记平台内容快照（listing_content_snapshot），没有「原商品内容」可复用。"
              + "请先让 02 同步或 4.4.1 采集补齐快照后再认领。");
    }
    String language = snapshot.get("language", String.class);

    // ---- 5. 字段决策
    Map<String, String> modes = new LinkedHashMap<>();
    Map<String, String> rewrites = new LinkedHashMap<>();
    for (ListingClaimFieldDecision decision : request.fieldDecisions()) {
      String field = text(decision.field()).toUpperCase(Locale.ROOT);
      if (!FIELDS.contains(field)) {
        throw unprocessable("未知的字段：" + field + "。可选：" + String.join(" / ", FIELDS));
      }
      String mode = text(decision.mode()).toUpperCase(Locale.ROOT);
      if (!DECISIONS.contains(mode)) {
        throw unprocessable("未知的决策：" + mode + "。可选：" + String.join(" / ", DECISIONS));
      }
      if (modes.containsKey(field)) {
        throw unprocessable("字段重复决策：" + field + "（每个字段只能给一个决策）");
      }
      if (MODE_REWRITE.equals(mode)) {
        String value = decision.value();
        if (value == null || value.trim().isEmpty()) {
          throw unprocessable("重写字段 " + field + " 必须给出非空的值");
        }
        rewrites.put(field, value);
      }
      modes.put(field, mode);
    }
    if (!modes.containsKey("TITLE")) {
      throw unprocessable("必须给出标题（TITLE）的决策：目标草稿不能没有标题。");
    }

    // ---- 6. 逐字段算出源值 / 目标值
    List<ListingClaimFieldRow> fieldRows = new ArrayList<>();
    Map<String, String> targetValues = new LinkedHashMap<>();
    for (String field : FIELDS) {
      String mode = modes.get(field);
      if (mode == null) {
        // 没给决策的字段一律跳过（不是"保留"）：没有决策就没有复用授权。
        continue;
      }
      SourceValue sourceValue = sourceValue(field, source, snapshot);
      String targetValue;
      String materialized;
      String note;
      if (MODE_SKIP.equals(mode)) {
        targetValue = null;
        // 🔴 跳过的字段在目标草稿上**留空**，但"空"必须是该字段类型的合法空值：
        //    JSON 字段留空数组 / 空对象（V175 的 CHECK 会拒掉别的形状），
        //    文本字段留空串。写成空串会让 jsonb 列直接插不进去。
        materialized = emptyValueFor(field);
        note = "本次认领跳过该字段，目标草稿留空。";
      } else if (MODE_REWRITE.equals(mode)) {
        targetValue = rewriteValue(field, rewrites.get(field));
        materialized = targetValue;
        note = rewriteNote(field);
      } else {
        // KEEP
        if (SITE_BOUND_FIELDS.contains(field) && !sameSite && hasValue(sourceValue)) {
          throw unprocessable(
              "🔴 "
                  + field
                  + " 与**站点**绑定：源站点 "
                  + siteLabel(sourceMarketplaceId)
                  + " 与目标站点 "
                  + siteLabel(targetMarketplaceId)
                  + " 不同，源值在目标站点不成立（沿用它等于在目标 listing 上写一个假事实）。"
                  + "请改为 REWRITE（显式给目标站点的值）或 SKIP（不写入目标草稿）。");
        }
        targetValue = sourceValue.value() == null ? "" : sourceValue.value();
        materialized = targetValue;
        note = keepNote(field, sameSite, hasValue(sourceValue));
        if (!hasValue(sourceValue) && MODE_KEEP.equals(mode)) {
          note = "源侧没有这个字段，目标草稿留空。";
        }
      }
      targetValues.put(field, materialized);
      fieldRows.add(
          new ListingClaimFieldRow(
              field, mode, sourceValue.origin(), sourceValue.value(), targetValue, note));
    }

    String targetTitle = targetValues.get("TITLE");
    if (targetTitle == null || targetTitle.isBlank()) {
      throw unprocessable("目标草稿的标题不能为空。");
    }
    String targetCategoryPath = targetValues.getOrDefault("CATEGORY_PATH", "");
    String targetPlatformCategoryId = targetValues.getOrDefault("PLATFORM_CATEGORY_ID", "");

    // ---- 7. 目标 listing（草稿态：尚未过平台审核）
    UUID claimId = Ids.next();
    UUID targetListingId = Ids.next();
    // 🔴 目标 listing 还没有平台商品 ID（草稿未发布，平台不分配 ASIN / item_id）。
    //    用「CLAIM-PENDING:」前缀明示这是保留位而不是平台原文；真实 ID 由发布流程回写。
    String targetExternalItemId = "CLAIM-PENDING:" + claimId;
    sql.execute(
        """
        insert into listing
          (id,tenant_id,shop_id,marketplace_id,external_item_id,title,category_path,
           platform_category_id,status,review_status,review_message,platform_stock_state,
           platform_stock_qty,rating,rating_count,currency,launched_at,last_synced_at,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,'DRAFT','NOT_SUBMITTED','','UNKNOWN',
                null,null,null,null,null,null,now(),now())
        """,
        targetListingId,
        tenant,
        request.targetShopId(),
        targetMarketplaceId,
        targetExternalItemId,
        targetTitle,
        targetCategoryPath,
        targetPlatformCategoryId);

    // ---- 8. 复制变体与映射（复用源的内部 SKU 映射）
    int[] counts = copyVariantsAndMappings(tenant, request.sourceListingId(), targetListingId);

    // ---- 9. 目标的内容快照（V175 既有事实表，不是新表）
    UUID targetSnapshotId = Ids.next();
    sql.execute(
        """
        insert into listing_content_snapshot
          (id,tenant_id,listing_id,language,title,bullet_points,description,images,video_url,
           a_plus_modules,category_attributes,compliance_statements,source,is_current,
           captured_at,created_at,updated_at)
        values (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?::jsonb,?::jsonb,?::jsonb,'MANUAL',true,now(),now(),now())
        """,
        targetSnapshotId,
        tenant,
        targetListingId,
        language,
        targetTitle,
        targetValues.getOrDefault("BULLETS", "[]"),
        targetValues.getOrDefault("DESCRIPTION", ""),
        targetValues.getOrDefault("IMAGES", "[]"),
        targetValues.getOrDefault("VIDEO_URL", ""),
        targetValues.getOrDefault("A_PLUS", "[]"),
        targetValues.getOrDefault("CATEGORY_ATTRIBUTES", "{}"),
        targetValues.getOrDefault("COMPLIANCE_STATEMENTS", "{}"));

    // ---- 10. 待发布任务：复用 4.3.1.2 的 V183 批次与任务（**不另建平行发布任务表**）
    UUID batchId = Ids.next();
    UUID taskId = Ids.next();
    String filterSnapshot =
        writeJson(
            Map.of(
                "source", "LISTING_CLAIM",
                "claimId", claimId.toString(),
                "sourceListingId", request.sourceListingId().toString(),
                "targetShopCode", targetShop.get("code", String.class),
                "targetMarketplaceCode", site.get("code", String.class)));
    String strategySnapshot =
        writeJson(
            Map.of(
                "mode", "CLAIMED_DRAFT",
                "contentSnapshotId", targetSnapshotId.toString(),
                "internalDraftId", "",
                "internalDraftOwner", "4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑（未交付）"));
    sql.execute(
        """
        insert into publish_batch
          (id,tenant_id,state,filter_rule,strategy,listing_ids,limit_count,total_count,pending_count,
           dispatched_count,succeeded_count,failed_count,actor_id,note,created_at,updated_at)
        values (?,?,'RUNNING',?::jsonb,?::jsonb,?,1,1,1,0,0,0,?,?,now(),now())
        """,
        batchId,
        tenant,
        filterSnapshot,
        strategySnapshot,
        new UUID[] {targetListingId},
        actorId,
        text(request.note()));
    sql.execute(
        """
        insert into publish_task
          (id,tenant_id,batch_id,listing_id,draft_id,platform_id,shop_id,position,state,attempts,
           created_at,updated_at)
        values (?,?,?,?,null,?,?,0,'PENDING',0,now(),now())
        """,
        taskId,
        tenant,
        batchId,
        targetListingId,
        targetPlatformId,
        request.targetShopId());

    // ---- 11. 认领记录（AFTER INSERT 触发器在此复核源未被改动 + 目标仍是草稿态）
    Long sourceVersion = source.get("version", Long.class);
    sql.execute(
        """
        insert into listing_claim
          (id,tenant_id,source_listing_id,target_listing_id,source_shop_id,target_shop_id,
           source_marketplace_id,target_marketplace_id,source_snapshot_id,source_language,
           source_status,source_review_status,source_version,state,target_external_item_id,
           variant_count,mapped_variant_count,publish_batch_id,publish_task_id,actor_id,note,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,now(),now())
        """,
        claimId,
        tenant,
        request.sourceListingId(),
        targetListingId,
        sourceShopId,
        request.targetShopId(),
        sourceMarketplaceId,
        targetMarketplaceId,
        snapshot.get("id", UUID.class),
        language,
        source.get("status", String.class),
        source.get("review_status", String.class),
        sourceVersion == null ? 0L : sourceVersion,
        targetExternalItemId,
        counts[0],
        counts[1],
        batchId,
        taskId,
        actorId,
        text(request.note()));

    // ---- 12. 逐字段决策落库
    for (ListingClaimFieldRow row : fieldRows) {
      sql.execute(
          """
          insert into listing_claim_field
            (id,tenant_id,claim_id,field,decision,source_origin,source_value,target_value,note,
             created_at)
          values (?,?,?,?,?,?,?,?,?,now())
          """,
          Ids.next(),
          tenant,
          claimId,
          row.field(),
          row.decision(),
          row.sourceOrigin(),
          row.sourceValue(),
          row.targetValue(),
          row.note());
    }

    return load(tenant, claimId);
  }

  // ==================================================================
  // 读取：列表 / 详情 / 按商品
  // ==================================================================

  /** 认领列表（真分页 + 状态与目标店铺筛选）。 */
  public List<ListingClaimRow> list(String state, String targetShopCode, int page, int size) {
    UUID tenant = currentTenant();
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where c.tenant_id=?");
    binds.add(tenant);
    String s = blankToNull(state);
    if (s != null) {
      if (!CLAIM_STATES.contains(s.toUpperCase(Locale.ROOT))) {
        throw unprocessable("未知的认领状态：" + s + "。可选：" + String.join(" / ", CLAIM_STATES));
      }
      where.append(" and c.state=?");
      binds.add(s.toUpperCase(Locale.ROOT));
    }
    String shop = blankToNull(targetShopCode);
    if (shop != null) {
      where.append(" and tshop.code=?");
      binds.add(shop);
    }
    int lim = clampSize(size);
    where.append(" order by c.created_at desc, c.id limit ? offset ?");
    binds.add(lim);
    binds.add(Math.max(0, page) * lim);

    List<UUID> ids =
        sql.fetch(
                "select c.id from listing_claim c"
                    + " join shop tshop on tshop.tenant_id=c.tenant_id and tshop.id=c.target_shop_id"
                    + where,
                binds.toArray())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    return ids.stream().map(id -> load(tenant, id)).toList();
  }

  /** 单次认领的详情。 */
  public ListingClaimRow detail(String claimId) {
    return load(currentTenant(), parseId(claimId));
  }

  /**
   * 某个商品的认领历史（作为源或作为目标都算）。
   *
   * <p>4.1.1.2 的详情页要能答「这个商品被认领到哪去了」与「这个草稿是从哪来的」。
   */
  public List<ListingClaimRow> forListing(String listingId) {
    UUID tenant = currentTenant();
    UUID id = parseId(listingId);
    List<UUID> ids =
        sql.fetch(
                "select id from listing_claim where tenant_id=? and (source_listing_id=? or target_listing_id=?)"
                    + " order by created_at desc, id",
                tenant,
                id,
                id)
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    return ids.stream().map(claim -> load(tenant, claim)).toList();
  }

  // ==================================================================
  // 内部实现
  // ==================================================================

  private static final String CLAIM_SELECT =
      """
      select c.id, c.source_listing_id, c.target_listing_id, c.state, c.variant_count,
             c.mapped_variant_count, c.publish_batch_id,
             c.publish_task_id, c.actor_id, c.note, c.created_at, c.updated_at,
             c.source_snapshot_id, c.source_language, c.source_status, c.source_review_status,
             c.source_version, c.target_external_item_id,
             src.external_item_id as source_external_item_id, src.title as source_title,
             src.status as source_current_status, src.review_status as source_current_review_status,
             src.version as source_current_version,
             sshop.code as source_shop_code, sshop.name as source_shop_name,
             smp.code as source_marketplace_code,
             tgt.title as target_title, tgt.status as target_status,
             tgt.review_status as target_review_status,
             tgt.platform_stock_state as target_platform_stock_state,
             tshop.code as target_shop_code, tshop.name as target_shop_name,
             tmp.code as target_marketplace_code
      from listing_claim c
      join listing src on src.tenant_id=c.tenant_id and src.id=c.source_listing_id
      join shop sshop on sshop.tenant_id=c.tenant_id and sshop.id=c.source_shop_id
      left join marketplace smp on smp.id=c.source_marketplace_id
      join listing tgt on tgt.tenant_id=c.tenant_id and tgt.id=c.target_listing_id
      join shop tshop on tshop.tenant_id=c.tenant_id and tshop.id=c.target_shop_id
      join marketplace tmp on tmp.id=c.target_marketplace_id
      """;

  private ListingClaimRow load(UUID tenant, UUID claimId) {
    Record r = sql.fetchOne(CLAIM_SELECT + " where c.tenant_id=? and c.id=?", tenant, claimId);
    if (r == null) throw notFound();
    List<ListingClaimFieldRow> fields =
        sql.fetch(
                """
                select field, decision, source_origin, source_value, target_value, note
                from listing_claim_field where tenant_id=? and claim_id=? order by field
                """,
                tenant,
                claimId)
            .stream()
            .map(
                f ->
                    new ListingClaimFieldRow(
                        f.get("field", String.class),
                        f.get("decision", String.class),
                        f.get("source_origin", String.class),
                        f.get("source_value", String.class),
                        f.get("target_value", String.class),
                        f.get("note", String.class)))
            .toList();
    Long claimedVersion = r.get("source_version", Long.class);
    Long currentVersion = r.get("source_current_version", Long.class);
    return new ListingClaimRow(
        r.get("id", UUID.class),
        r.get("source_listing_id", UUID.class),
        r.get("source_external_item_id", String.class),
        r.get("source_title", String.class),
        r.get("source_shop_code", String.class),
        r.get("source_shop_name", String.class),
        r.get("source_marketplace_code", String.class),
        r.get("source_status", String.class),
        r.get("source_current_status", String.class),
        r.get("source_review_status", String.class),
        r.get("source_current_review_status", String.class),
        claimedVersion == null ? 0L : claimedVersion,
        currentVersion == null ? 0L : currentVersion,
        r.get("target_listing_id", UUID.class),
        r.get("target_external_item_id", String.class),
        r.get("target_status", String.class),
        r.get("target_review_status", String.class),
        r.get("target_platform_stock_state", String.class),
        r.get("target_title", String.class),
        r.get("target_shop_code", String.class),
        r.get("target_shop_name", String.class),
        r.get("target_marketplace_code", String.class),
        r.get("state", String.class),
        r.get("variant_count", Integer.class),
        r.get("mapped_variant_count", Integer.class),
        r.get("publish_batch_id", UUID.class),
        r.get("publish_task_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        fields);
  }

  /**
   * 源 listing 的当前内容快照；给了语言就只取那一种语言的当前快照。
   *
   * <p>🔴 同一 listing 的<b>每一种语言</b>各有一份「当前」快照（V175 的唯一部分索引按
   * {@code (tenant_id, listing_id, language)} 建），所以不给语言时取的是**最近采集的那一份**
   * —— 认定领内容时用的是哪一种语言，写进认领记录的 {@code source_language}，界面上可见。
   */
  private Record sourceSnapshot(UUID tenant, UUID listingId, String language) {
    String wanted = blankToNull(language);
    if (wanted == null) {
      return sql.fetchOne(
          """
          select id, language, title, bullet_points, description, images, video_url, a_plus_modules,
                 category_attributes, compliance_statements
          from listing_content_snapshot
          where tenant_id=? and listing_id=? and is_current
          order by captured_at desc, id
          limit 1
          """,
          tenant,
          listingId);
    }
    return sql.fetchOne(
        """
        select id, language, title, bullet_points, description, images, video_url, a_plus_modules,
               category_attributes, compliance_statements
        from listing_content_snapshot
        where tenant_id=? and listing_id=? and is_current and language=?
        order by captured_at desc, id
        limit 1
        """,
        tenant,
        listingId,
        wanted);
  }

  /**
   * 源值：来自平台内容快照还是 listing 行，如实记下。
   *
   * <p>🔴 {@code NOT_PRESENT} = 源侧**根本没有**这个字段（值记 null，不记空串 —— 一边说"没有"一边
   * 给值会让逐字段对照出现两个答案）。
   */
  private record SourceValue(String value, String origin) {}

  private boolean hasValue(SourceValue value) {
    return value.value() != null && !value.value().isEmpty();
  }

  private SourceValue sourceValue(String field, Record source, Record snapshot) {
    switch (field) {
      case "TITLE": {
        String fromSnapshot = text(snapshot.get("title", String.class));
        if (!fromSnapshot.isEmpty()) return new SourceValue(fromSnapshot, "PLATFORM_SNAPSHOT");
        String fromListing = text(source.get("title", String.class));
        return fromListing.isEmpty()
            ? new SourceValue(null, "NOT_PRESENT")
            : new SourceValue(fromListing, "LISTING");
      }
      case "CATEGORY_PATH": {
        String value = text(source.get("category_path", String.class));
        return value.isEmpty() ? new SourceValue(null, "NOT_PRESENT") : new SourceValue(value, "LISTING");
      }
      case "PLATFORM_CATEGORY_ID": {
        String value = text(source.get("platform_category_id", String.class));
        return value.isEmpty() ? new SourceValue(null, "NOT_PRESENT") : new SourceValue(value, "LISTING");
      }
      default: {
        String column = SNAPSHOT_COLUMNS.get(field);
        if (column == null) return new SourceValue(null, "NOT_PRESENT");
        if (TEXT_FIELDS.contains(field)) {
          String value = text(snapshot.get(column, String.class));
          return value.isEmpty()
              ? new SourceValue(null, "NOT_PRESENT")
              : new SourceValue(value, "PLATFORM_SNAPSHOT");
        }
        Object raw = snapshot.get(column);
        String value = raw == null ? "" : jsonText(raw);
        boolean empty =
            value.isEmpty()
                || "[]".equals(value)
                || "{}".equals(value)
                || "null".equals(value);
        return empty ? new SourceValue(null, "NOT_PRESENT") : new SourceValue(value, "PLATFORM_SNAPSHOT");
      }
    }
  }

  /** 重写值：JSON 字段必须是合法 JSON 且类型对得上。 */
  private String rewriteValue(String field, String raw) {
    String kind = JSON_FIELDS.get(field);
    if (kind == null) return raw.trim();
    return jsonOfKind(raw, kind, "字段 " + field);
  }

  /**
   * 字段的「空值」形态。
   *
   * <p>🔴 JSON 字段的空值必须是**空数组 / 空对象**而不是空串：V175 的 CHECK
   * （{@code jsonb_typeof(bullet_points)='array'} 等）会拒掉别的形状，空串更是连 jsonb 都插不进去。
   */
  private static String emptyValueFor(String field) {
    String kind = JSON_FIELDS.get(field);
    if (kind == null) return "";
    return "array".equals(kind) ? "[]" : "{}";
  }

  /** 「保留」时写进 note 的提示：哪些字段跨站点要复核。 */
  private String keepNote(String field, boolean sameSite, boolean hasSourceValue) {
    if (!hasSourceValue) return "源侧没有这个字段，目标草稿留空。";
    if (sameSite) {
      return switch (field) {
        case "TITLE" -> "复用源标题。源与目标同站点，标题沿用后仍建议按目标店铺的搜索词复核。";
        case "CATEGORY_PATH" -> "复用源类目路径（平台侧原文）；目标站点的类目 ID 见平台类目 ID 字段。";
        default -> "";
      };
    }
    return switch (field) {
      case "TITLE" ->
          "🔴 标题与源逐字相同：平台可能判定为重复铺货并抑制 listing。"
              + "标题差异化是 4.4.2.2 批量搬迁的职责，单条认领这里只提示，不代改。";
      case "A_PLUS" -> "🔴 A+ 模块结构与站点绑定，跨站点沿用后需按目标站点复核。";
      case "CATEGORY_PATH" -> "类目路径是平台侧原文，跨站点沿用后需复核到目标站点的类目。";
      default -> "";
    };
  }

  /** 「重写」时写进 note 的提示。 */
  private String rewriteNote(String field) {
    return switch (field) {
      case "PLATFORM_CATEGORY_ID" -> "使用显式给出的目标站点平台类目 ID。";
      case "CATEGORY_ATTRIBUTES" -> "使用显式给出的目标站点类目属性（JSON 对象）。";
      case "COMPLIANCE_STATEMENTS" -> "使用显式给出的目标站点合规声明（JSON 对象）。";
      default -> "使用显式给出的重写值。";
    };
  }

  /**
   * 复制源 listing 的变体与映射到目标 listing。
   *
   * <p>🔴 复用的是**内部 SKU 逻辑 id**（{@code internal_sku_id}）—— 03 商品主数据的跨服务纪律：
   * 不建 FK、不复制 catalog 任何一列。映射按 V176 的既有规则写版本 1
   * （{@code effective_from=now()}、{@code effective_to=NULL}）。
   *
   * @return {@code [变体条数, 已映射条数]}
   */
  private int[] copyVariantsAndMappings(UUID tenant, UUID sourceListingId, UUID targetListingId) {
    List<Record> variants =
        sql.fetch(
            """
            select id, external_variant_id, attributes, mapping_status, internal_sku_id,
                   component_quantity
            from listing_variant where tenant_id=? and listing_id=? order by id
            """,
            tenant,
            sourceListingId);
    List<Record> mappings =
        sql.fetch(
            """
            select m.listing_variant_id, m.relation_type, m.mapping_status, m.internal_sku_id,
                   m.component_quantity
            from listing_mapping m
            where m.tenant_id=? and m.listing_id=? and m.status='ACTIVE'
            """,
            tenant,
            sourceListingId);
    Map<UUID, Record> mappingByVariant = new LinkedHashMap<>();
    for (Record m : mappings) {
      mappingByVariant.put(m.get("listing_variant_id", UUID.class), m);
    }

    int variantCount = 0;
    int mappedCount = 0;
    for (Record variant : variants) {
      UUID newVariantId = Ids.next();
      sql.execute(
          """
          insert into listing_variant
            (id,tenant_id,listing_id,external_variant_id,attributes,mapping_status,internal_sku_id,
             component_quantity,created_at,updated_at)
          values (?,?,?,?,?::jsonb,'UNMAPPED',null,null,now(),now())
          """,
          newVariantId,
          tenant,
          targetListingId,
          variant.get("external_variant_id", String.class),
          jsonText(variant.get("attributes")));
      variantCount++;

      // 🔴 映射的真相优先取 listing_mapping（V176 的写入所有者）；
      //    源 listing 只有变体上的投影位（4.1.1.1 的登记路径）时，回退读投影 ——
      //    否则一条真实的映射会在认领时悄悄丢掉，目标商品变成未映射。
      Record mapping = mappingByVariant.get(variant.get("id", UUID.class));
      String relationType;
      String mappingStatus;
      UUID internalSkuId;
      BigDecimal quantity;
      if (mapping != null) {
        relationType = mapping.get("relation_type", String.class);
        mappingStatus = mapping.get("mapping_status", String.class);
        internalSkuId = mapping.get("internal_sku_id", UUID.class);
        quantity = mapping.get("component_quantity", BigDecimal.class);
      } else {
        mappingStatus = variant.get("mapping_status", String.class);
        if (mappingStatus == null || "UNMAPPED".equals(mappingStatus)) continue;
        relationType = "BUNDLE".equals(mappingStatus) ? "ONE_TO_MANY" : "ONE_TO_ONE";
        internalSkuId = variant.get("internal_sku_id", UUID.class);
        quantity = variant.get("component_quantity", BigDecimal.class);
      }

      UUID newMappingId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping
            (id,tenant_id,listing_id,listing_variant_id,relation_type,status,mapping_status,
             internal_sku_id,component_quantity,current_version_id,version,created_at,updated_at)
          values (?,?,?,?,?,'ACTIVE',?,?,?,null,0,now(),now())
          """,
          newMappingId,
          tenant,
          targetListingId,
          newVariantId,
          relationType,
          mappingStatus,
          internalSkuId,
          quantity);
      // V176 的 AFTER INSERT 触发器把映射状态投影到变体上（现状位由映射拥有）
      mappedCount++;

      UUID newVersionId = Ids.next();
      // 🔴 effective_from 绑 **DB 的事务时刻**（PG 的 now() 在一个事务里是常数），
      //    再用 ?::timestamptz 写回：这样「认领时刻」在 DB 与服务层是**同一个瞬时**，
      //    集成测试可以断言它与认领记录的 created_at 逐字相等（timestamptz 往返断言）。
      sql.execute(
          """
          insert into listing_mapping_version
            (id,tenant_id,mapping_id,version_no,relation_type,mapping_status,internal_sku_id,
             component_quantity,effective_from,effective_to,change_reason,change_source,created_at)
          values (?,?,?,1,?,?,?,?,?::timestamptz,null,?,'MANUAL',now())
          """,
          newVersionId,
          tenant,
          newMappingId,
          relationType,
          mappingStatus,
          internalSkuId,
          quantity,
          transactionNow(),
          "认领自源 listing " + sourceListingId + "（4.4.2.1 商品认领）");
      sql.execute(
          "update listing_mapping set current_version_id=? where tenant_id=? and id=?",
          newVersionId,
          tenant,
          newMappingId);
    }
    return new int[] {variantCount, mappedCount};
  }

  private String platformCode(UUID platformId) {
    String code = one(String.class, "select code from platform where id=?", platformId);
    return code == null ? String.valueOf(platformId) : code;
  }

  private String siteLabel(UUID marketplaceId) {
    if (marketplaceId == null) return "（源 listing 未登记站点）";
    String code = one(String.class, "select code from marketplace where id=?", marketplaceId);
    return code == null ? String.valueOf(marketplaceId) : code;
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 当前事务的时刻（PG 的 {@code now()}，一个事务里是常数）。
   *
   * <p>用它而不是 JVM 的 {@code OffsetDateTime.now()}：PG 的 {@code now()} 就是本事务的开始时刻，
   * 与本事务写入的 {@code created_at}（DB 侧 {@code now()}）是**同一个瞬时** ——
   * 集成测试据此断言「认领时刻」在两处逐字相等。
   */
  private OffsetDateTime transactionNow() {
    Object raw = sql.fetchValue("select now()", OffsetDateTime.class);
    if (raw instanceof OffsetDateTime value) return value;
    // 驱动把 timestamptz 交成 Timestamp 时，toInstant() 仍还原出同一个瞬时
    if (raw instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    throw new IllegalStateException("无法读取事务时刻：" + raw);
  }
}
