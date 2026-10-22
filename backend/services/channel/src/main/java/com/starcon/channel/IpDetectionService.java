package com.starcon.channel;

import static com.starcon.channel.IpDetectionModels.*;
import static com.starcon.channel.IpDetectionRules.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.4.1.2 检测与预警。
 *
 * <p>蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * <b>图片侵权检测难度高于文本</b>，误判与漏判都要留人工复核环节。」
 *
 * <p>🔴🔴 本类里有七处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>风险等级沿用上游口径</b>（HIGH / MEDIUM / LOW，另加 NONE），
 *       DB 层 CHECK 钉死（迁移 V317），服务层<b>先</b>按同一份规则拒（422，带人话）。
 *       <b>不是</b> 0..100 的裸数字。</li>
 *   <li><b>命中项必须可解释</b>：每条命中点名<b>哪一条目</b>（编码快照）、
 *       <b>哪些匹配特征</b>、<b>哪个字段</b>、<b>命中处原文</b>。
 *       条目后来被停用 / 改写，历史报告仍解释得清。</li>
 *   <li><b>图片命中一律进人工复核</b>：{@code needsManualReview=true}，
 *       且<b>不单独构成「可判定命中」</b> ⇒ 结论只会是 {@code REVIEW_REQUIRED}。</li>
 *   <li><b>结论三态</b>：CLEAR（无命中）/ REVIEW_REQUIRED（全部待复核）/ ACTION_REQUIRED（有可判定命中）。
 *       「判不了」与「判了没问题」是<b>两件事</b>。</li>
 *   <li><b>本模块不拦截</b>：只产出 {@code recommendedAction}（**建议**）+
 *       预警 + 整改建议，<b>不写</b> 16.2.1.2 的策略表、<b>不代</b>它判放行 / 阻断。</li>
 *   <li><b>只读上游侵权库</b>：{@code ip_risk_entry} / {@code ip_risk_match_term} /
 *       {@code ip_risk_entry_platform} <b>零写入、零 ALTER、零 FK</b>。</li>
 *   <li><b>外部商标 / 专利查询是「登记承接」</b>：本仓无凭据、无网络出口 ⇒
 *       <b>不写打真实域名的假 HTTP 客户端</b>；外部结果由租户登记进来，
 *       检测时与本地侵权库<b>一起</b>参与比对。</li>
 * </ol>
 */
@Service
public class IpDetectionService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一轮巡检最多扫多少个商品（避免一次巡检把服务拖垮）。 */
  private static final int MAX_LISTINGS_PER_RUN = 500;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String RISK_LEVEL_NOTE =
      "🔴 风险等级**沿用上游 16.4.1.1 侵权库的 HIGH / MEDIUM / LOW 口径**"
          + "（高 / 中 / 低），另加 **NONE**（一条命中都没有）。"
          + "🔴 **不用 0..100 的裸数字** —— 裸数字没有口径，"
          + "「72 分」没法解释成「高风险还是中风险」，与上游条目也对不上。";

  private static final String MATCH_NOTE =
      "🔴 比对是**拿 16.4.1.1 登记的匹配特征**去比商品内容："
          + "**品牌名**（可空）、**关键词 / 短语集合**、**标识符特征**（如商标注册号）。"
          + "🔴 关键词按**词边界**匹配（「air」不命中「hair」），短语按**包含**匹配，"
          + "全部**大小写不敏感**。"
          + "🔴 **命中必须可解释**：每条命中点名命中了**哪一条目**（编码快照）、"
          + "**哪些匹配特征**、**在哪个字段**、**命中处原文片段** —— "
          + "「为什么这个商品被判高风险」永远答得出来。";

  private static final String IMAGE_NOTE =
      "🔴🔴 **图片检测的口径必须说清楚**：本模块的图片比对是"
          + "**标识文本比对**（文件名 / 替代文本 / URL 里能读到的文字），"
          + "**不是**图像指纹比对 —— 本仓**没有图像识别引擎**，"
          + "**没有**真正的以图搜图能力。"
          + "🔴 因此**图片命中一律进人工复核队列**（DB 层 CHECK 钉死），"
          + "**不单独构成可判定的高风险**（结论只会是「待人工复核」）。"
          + "蓝图原话：「**图片侵权检测难度高于文本**，误判与漏判都要留人工复核环节。」"
          + "🔴 图片维度**默认开启**；关掉它等于承认「图片不用查」，那是漏判，"
          + "页面与能力声明都显式标出来。";

  private static final String REVIEW_NOTE =
      "🔴 人工复核是**必经环节**，不是可选步骤："
          + "**图片命中自动进队列**；文本命中也可由运营提复核（误判申诉）。"
          + "🔴 复核结论是**证据**：PENDING → CONFIRMED / DISMISSED，**终态不可回退、不可删除**。"
          + "🔴 **还在等复核的命中不算「可判定命中」** ⇒ 一份全是图片命中的报告，"
          + "结论是「待人工复核」而**不是**「已判定侵权」—— "
          + "把「判不了」写成「判了有问题」和写成「没问题」一样是撒谎。";

  private static final String EXTERNAL_NOTE =
      "🔴 **外部商标 / 专利查询：本仓没有真实凭据、也没有网络出口** ⇒ "
          + "本模块**不写打真实域名的假 HTTP 客户端**。"
          + "照抄本仓承接外部边界的既有形状（10.4.2.2 用「租户登记的报送表」承接平台货件编号、"
          + "16.4.2.2 用文件存储承接外部授权文件）："
          + "**租户登记外部查询单 → 登记查回来的结果 → 检测时与本地侵权库一起比对**。"
          + "🔴 「自动外呼外部商标库」这件事**未交付**，能力声明里如实标 available=false。";

  private static final String INSPECTION_NOTE =
      "🔴 巡检对象是**已上架商品**（listing.status = ON_SALE）的**当前线上内容**"
          + "（listing + listing_content_snapshot），不是草稿。"
          + "🔴 与本仓既有纪律一致（V212 物流轨迹拉取）：**本服务不装 @Scheduled** —— "
          + "多副本下自带定时器要做主节点选举与分布式锁，那是平台能力不是领域能力；"
          + "**外部调度**（K8s CronJob）按 interval_days 调一次运行入口，"
          + "天然单次、可重试、可审计。";

  private static final String UPSTREAM_NOTE =
      "上游 = **16.4.1.1 侵权库**（**同库**，V315，**已交付**）："
          + "本模块**只读** `ip_risk_entry` / `ip_risk_match_term` / `ip_risk_entry_platform`，"
          + "**零写入、零 ALTER、零 FK**（那些表是别的车道交付的）。"
          + "命中项只存**逻辑 id + 快照** ⇒ 上游条目后来被停用 / 改写，历史报告仍解释得清。"
          + "上游 = **4.2.1.1 刊登编辑器**（**同库**，V194，**已交付**）："
          + "`listing_editor_draft.field_values` 的 TITLE / BULLETS / DESCRIPTION / IMAGES "
          + "是「刊登前检测」的内容来源，**只读**。"
          + "上游 = **3.3.2 品牌**（catalog 服务，**已交付**）：品牌名**只经授权 HTTP API**读，"
          + "**零跨库 FK、零影子表**；🔴 **读不到就显式说读不到**"
          + "（available=false + 原因），**绝不返回空串冒充「这个商品没有品牌」**。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **16.2.1.2 拦截执行**（V313，**已交付**）：**拦截权归它**。"
          + "本模块**只产出建议**（recommended_action = INTERCEPT / REMEDIATE / NOTIFY），"
          + "**不写它的策略表、不代它判放行 / 阻断**。"
          + "下游 = **16.4.2 授权核验**（16.4.2.1 V248 / 16.4.2.2 V255，**已交付**）："
          + "品牌类命中的整改建议指向它（VERIFY_BRAND_AUTH），本模块**不代它核验授权**。"
          + "🔴 本模块**不越界**做刊登拦截，也**不写**任何下游的表。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final IpDetectionSourcePort source;

  public IpDetectionService(DSLContext sql, ObjectMapper json, IpDetectionSourcePort source) {
    this.sql = sql;
    this.json = json;
    this.source = source;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
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

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：一切时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 路径里的 id 非法 ⇒ 与「不存在」同等处理（不泄露「格式对但没有」）。 */
  private static UUID requireUuid(String raw) {
    UUID parsed = parseUuid(raw);
    if (parsed == null) {
      throw notFound();
    }
    return parsed;
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  private static OffsetDateTime time(Record row, String column) {
    return row.get(column, OffsetDateTime.class);
  }

  private static String idText(UUID id) {
    return id == null ? null : id.toString();
  }

  private String jsonText(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = raw instanceof org.jooq.JSONB jsonb ? jsonb.data() : String.valueOf(raw);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private JsonNode readNode(Object raw) {
    try {
      String value = jsonText(raw);
      if (value == null || value.isBlank()) {
        return null;
      }
      return json.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：值域、各条口径，以及**哪些下游 / 外部边界未交付**。
   *
   * <p>🔴 「自动外呼外部商标 / 专利库」**未交付**（本仓无凭据、无网络出口），
   * 如实报出 ownerModule 与影响；**16.2.1.2 / 16.4.2** 已交付但**不是本模块的活**。
   */
  public IpDetectionCapabilities capabilities() {
    List<IpDetectionCapabilityGap> gaps =
        List.of(
            new IpDetectionCapabilityGap(
                "16.2.1.2",
                "拦截执行",
                "16.2.1.2 拦截执行（channel 服务）",
                true,
                "拦截执行**已交付**（V313）⇒ **拦截权归它**。"
                    + "本模块**只产出建议**（recommended_action），"
                    + "**不写它的策略表、不代它判放行 / 阻断**。"),
            new IpDetectionCapabilityGap(
                "16.4.2",
                "品牌授权核验",
                "16.4.2.1 品牌授权 / 16.4.2.2 授权凭证",
                true,
                "品牌授权与授权凭证**已交付**（V248 / V255）⇒ "
                    + "品牌类命中的整改建议指向它们（VERIFY_BRAND_AUTH），"
                    + "本模块**不代它们核验授权**。"),
            new IpDetectionCapabilityGap(
                "EXTERNAL_TM_DB",
                "外部商标 / 专利库自动查询",
                "外部数据源接入（平台能力，非领域能力）",
                false,
                "🔴 本仓**没有**外部商标 / 专利库的凭据与网络出口 ⇒ "
                    + "本模块**不写打真实域名的假 HTTP 客户端**。"
                    + "照抄本仓承接外部边界的既有形状（10.4.2.2 报送表 / 16.4.2.2 凭证文件）："
                    + "**租户登记外部查询单 → 登记查回来的结果 → 检测时一起比对**。"
                    + "影响：**外部结果要人工登记**，不会自动同步；"
                    + "登记进来的结果与本地侵权库<b>同等参与</b>比对。"),
            new IpDetectionCapabilityGap(
                "IMAGE_RECOGNITION",
                "图像指纹比对（以图搜图）",
                "图像识别引擎（平台能力，非领域能力）",
                false,
                "🔴 本仓**没有**图像识别引擎 ⇒ 图片比对是**标识文本比对**"
                    + "（文件名 / 替代文本 / URL），**不是**真正的以图搜图。"
                    + "🔴 因此**图片命中一律进人工复核队列**，"
                    + "**不单独构成可判定的高风险**。"
                    + "影响：图片维度**能查但不能定性**，结论要等人工复核。"));
    return new IpDetectionCapabilities(
        gaps,
        RISK_LEVELS,
        RISK_LEVEL_LABELS,
        CONCLUSIONS,
        CONCLUSION_LABELS,
        RECOMMENDED_ACTIONS,
        RECOMMENDED_ACTION_LABELS,
        SUBJECT_TYPES,
        SUBJECT_TYPE_LABELS,
        FIELDS,
        FIELD_LABELS,
        FEATURE_KINDS,
        FEATURE_KIND_LABELS,
        ALERT_TYPES,
        ALERT_TYPE_LABELS,
        ALERT_STATUSES,
        ALERT_STATUS_LABELS,
        REMEDIATION_ACTIONS,
        REMEDIATION_ACTION_LABELS,
        REMEDIATION_STATUSES,
        REMEDIATION_STATUS_LABELS,
        REVIEW_REASONS,
        REVIEW_REASON_LABELS,
        REVIEW_STATUSES,
        REVIEW_STATUS_LABELS,
        QUERY_STATUSES,
        QUERY_STATUS_LABELS,
        PLAN_SCOPES,
        PLAN_SCOPE_LABELS,
        RISK_LEVEL_NOTE,
        MATCH_NOTE,
        IMAGE_NOTE,
        REVIEW_NOTE,
        EXTERNAL_NOTE,
        INSPECTION_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 🔴 内容解析（检测对象 → 可比对字段）
  // ==================================================================

  /**
   * 🔴 解析检测对象的**真实内容**。
   *
   * <p>读不到就是读不到：{@code fields} 里<b>没有</b>的字段就是没有，
   * <b>不塞占位符</b>；品牌解析不到时 {@code brandAvailable=false} + 原因。
   */
  private IpDetectionResolvedSubject resolveSubject(
      UUID tenant, IpDetectionSubjectInput input, String bearer) {
    String subjectType = text(input.subjectType()).toUpperCase();
    if (!knownSubjectType(subjectType)) {
      throw unprocessable("未知的检测对象类型：" + text(input.subjectType())
          + "（只能是 LISTING / DRAFT / MANUAL）");
    }
    boolean includeImage = input.includeImage() == null || input.includeImage();
    return switch (subjectType) {
      case "LISTING" -> resolveListing(tenant, input, bearer, includeImage);
      case "DRAFT" -> resolveDraft(tenant, input, bearer, includeImage);
      default -> resolveManual(input, includeImage);
    };
  }

  /** 已上架商品：读 listing + 当前线上内容快照。 */
  private IpDetectionResolvedSubject resolveListing(
      UUID tenant, IpDetectionSubjectInput input, String bearer, boolean includeImage) {
    UUID listingId = requireUuid(input.listingId());
    Record row =
        sql.fetchOne(
            "select l.id, l.title, l.shop_id, p.code as platform_code"
                + " from listing l"
                + " join shop s on s.tenant_id = l.tenant_id and s.id = l.shop_id"
                + " join platform p on p.id = s.platform_id"
                + " where l.tenant_id = ? and l.id = ?",
            tenant,
            listingId);
    if (row == null) {
      throw notFound();
    }
    String title = row.get("title", String.class);
    String platformCode = row.get("platform_code", String.class);
    UUID shopId = row.get("shop_id", UUID.class);

    List<IpDetectionField> fields = new ArrayList<>();
    if (title != null && !title.isBlank()) {
      fields.add(new IpDetectionField("TITLE", title, "listing.title"));
    }
    StringBuilder sourceNote = new StringBuilder();

    Record snapshot =
        sql.fetchOne(
            "select title, description, bullet_points, images, language"
                + " from listing_content_snapshot"
                + " where tenant_id = ? and listing_id = ? and is_current"
                + " order by captured_at desc limit 1",
            tenant,
            listingId);
    if (snapshot == null) {
      sourceNote.append("🔴 这个商品**没有当前线上内容快照**，");
      sourceNote.append("描述 / 卖点 / 图片**没查到**（不是「没有违规」，是「没查」）。");
    } else {
      String snapTitle = snapshot.get("title", String.class);
      if (snapTitle != null && !snapTitle.isBlank() && !snapTitle.equals(title)) {
        fields.add(new IpDetectionField("TITLE", snapTitle, "listing_content_snapshot.title"));
      }
      String description = snapshot.get("description", String.class);
      if (description != null && !description.isBlank()) {
        fields.add(new IpDetectionField("DESCRIPTION", description, "listing_content_snapshot.description"));
      }
      for (String bullet : stringList(snapshot.get("bullet_points", Object.class))) {
        if (!bullet.isBlank()) {
          fields.add(new IpDetectionField("BULLET", bullet, "listing_content_snapshot.bullet_points"));
        }
      }
      sourceNote.append("内容来自 listing + 当前线上内容快照（语言 ")
          .append(snapshot.get("language", String.class))
          .append("）。");
    }

    boolean brandAvailable = false;
    String brand = "";
    String brandNote = "";
    Record variant =
        sql.fetchOne(
            "select internal_sku_id from listing_variant"
                + " where tenant_id = ? and listing_id = ? and internal_sku_id is not null"
                + " order by id limit 1",
            tenant,
            listingId);
    if (variant == null) {
      brandNote = "🔴 这个商品**没有映射到内部 SKU** ⇒ 品牌维度**没查**（不是「没有品牌风险」）。";
    } else {
      IpDetectionSourcePort.BrandLookup lookup =
          source.brandOfSku(variant.get("internal_sku_id", UUID.class).toString(), bearer);
      brandAvailable = lookup.available();
      brand = lookup.brand();
      brandNote = lookup.note();
    }
    if (brandAvailable) {
      fields.add(new IpDetectionField("BRAND", brand, "catalog 源商品品牌"));
    }
    if (includeImage && snapshot != null) {
      for (ImageRef image : imageRefs(snapshot.get("images", Object.class))) {
        String descriptor = imageDescriptor(image.url(), image.altText(), image.originalFilename());
        if (!descriptor.isBlank()) {
          fields.add(new IpDetectionField("IMAGE", descriptor, image.reference()));
        }
      }
    }

    return new IpDetectionResolvedSubject(
        "LISTING",
        listingId.toString(),
        title == null ? "" : title,
        idText(shopId),
        platformCode,
        fields,
        brandAvailable,
        brandNote,
        sourceNote.toString());
  }

  /** 刊登前草稿：读 4.2.1.1 的 listing_editor_draft.field_values。 */
  private IpDetectionResolvedSubject resolveDraft(
      UUID tenant, IpDetectionSubjectInput input, String bearer, boolean includeImage) {
    UUID draftId = requireUuid(input.draftId());
    Record row =
        sql.fetchOne(
            "select d.id, d.shop_id, d.source_sku_id, d.field_values, d.state,"
                + " p.code as platform_code"
                + " from listing_editor_draft d"
                + " join shop s on s.tenant_id = d.tenant_id and s.id = d.shop_id"
                + " join platform p on p.id = s.platform_id"
                + " where d.tenant_id = ? and d.id = ?",
            tenant,
            draftId);
    if (row == null) {
      throw notFound();
    }
    if ("DISCARDED".equals(row.get("state", String.class))) {
      throw unprocessable("🔴 这个草稿已废弃（DISCARDED），不参与检测。");
    }
    UUID shopId = row.get("shop_id", UUID.class);
    String platformCode = row.get("platform_code", String.class);
    JsonNode values = readNode(row.get("field_values", Object.class));

    List<IpDetectionField> fields = new ArrayList<>();
    String title = "";
    if (values != null) {
      JsonNode titleNode = values.get("TITLE");
      if (titleNode != null && !titleNode.isNull()) {
        title = titleNode.asText("");
        if (!title.isBlank()) {
          fields.add(new IpDetectionField("TITLE", title, "draft.field_values.TITLE"));
        }
      }
      JsonNode descriptionNode = values.get("DESCRIPTION");
      if (descriptionNode != null && !descriptionNode.isNull()) {
        String description = descriptionNode.asText("");
        if (!description.isBlank()) {
          fields.add(new IpDetectionField("DESCRIPTION", description, "draft.field_values.DESCRIPTION"));
        }
      }
      JsonNode bulletsNode = values.get("BULLETS");
      if (bulletsNode != null && bulletsNode.isArray()) {
        for (JsonNode bullet : bulletsNode) {
          String value = bullet.asText("");
          if (!value.isBlank()) {
            fields.add(new IpDetectionField("BULLET", value, "draft.field_values.BULLETS"));
          }
        }
      }
      if (includeImage) {
        JsonNode imagesNode = values.get("IMAGES");
        if (imagesNode != null && imagesNode.isArray()) {
          for (JsonNode image : imagesNode) {
            ImageRef ref = imageRef(image);
            String descriptor = imageDescriptor(ref.url(), ref.altText(), ref.originalFilename());
            if (!descriptor.isBlank()) {
              fields.add(new IpDetectionField("IMAGE", descriptor, ref.reference()));
            }
          }
        }
      }
    }

    boolean brandAvailable = false;
    String brandNote = "";
    UUID sourceSkuId = row.get("source_sku_id", UUID.class);
    if (sourceSkuId == null) {
      brandNote = "🔴 这个草稿**没有源商品** ⇒ 品牌维度**没查**（不是「没有品牌风险」）。";
    } else {
      IpDetectionSourcePort.BrandLookup lookup =
          source.brandOfSku(sourceSkuId.toString(), bearer);
      brandAvailable = lookup.available();
      if (brandAvailable) {
        fields.add(new IpDetectionField("BRAND", lookup.brand(), "catalog 源商品品牌"));
      }
      brandNote = lookup.note();
    }

    return new IpDetectionResolvedSubject(
        "DRAFT",
        draftId.toString(),
        title,
        idText(shopId),
        platformCode,
        fields,
        brandAvailable,
        brandNote,
        "内容来自 4.2.1.1 刊登编辑器草稿的 field_values（TITLE / BULLETS / DESCRIPTION / IMAGES）。");
  }

  /** 手工提交内容。 */
  private IpDetectionResolvedSubject resolveManual(
      IpDetectionSubjectInput input, boolean includeImage) {
    String title = text(input.title());
    if (title.isEmpty()) {
      throw unprocessable("🔴 手工检测必须给标题（它是比对内容之一）。");
    }
    List<IpDetectionField> fields = new ArrayList<>();
    fields.add(new IpDetectionField("TITLE", title, "调用方提交"));
    String description = text(input.description());
    if (!description.isEmpty()) {
      fields.add(new IpDetectionField("DESCRIPTION", description, "调用方提交"));
    }
    if (input.bulletPoints() != null) {
      for (String bullet : input.bulletPoints()) {
        String value = text(bullet);
        if (!value.isEmpty()) {
          fields.add(new IpDetectionField("BULLET", value, "调用方提交"));
        }
      }
    }
    String brand = text(input.brandName());
    if (!brand.isEmpty()) {
      fields.add(new IpDetectionField("BRAND", brand, "调用方声明"));
    }
    if (includeImage && input.imageUrls() != null) {
      for (String image : input.imageUrls()) {
        String value = text(image);
        if (!value.isEmpty()) {
          String descriptor = imageDescriptor(value, null, null);
          fields.add(new IpDetectionField("IMAGE", descriptor, value));
        }
      }
    }
    return new IpDetectionResolvedSubject(
        "MANUAL",
        null,
        title,
        null,
        null,
        fields,
        !brand.isEmpty(),
        brand.isEmpty() ? "🔴 调用方**没有声明品牌** ⇒ 品牌维度**没查**。" : "",
        "内容由调用方直接提交（刊登编辑器之外的一次性检测）。");
  }

  // ==================================================================
  // 🔴 特征装载（本地侵权库 + 外部查询结果）
  // ==================================================================

  /**
   * 🔴 装载待比对特征。
   *
   * <p>两个来源：<b>16.4.1.1 侵权库</b>（同库只读）与<b>登记回来的外部查询结果</b>。
   *
   * <p>🔴 **适用平台过滤**：一条侵权条目如果登记了适用平台，
   * 那么它<b>只</b>在那些平台上比对；没登记平台的条目视为**不限平台**。
   */
  private List<Feature> loadFeatures(UUID tenant, String platformCode) {
    List<Feature> features = new ArrayList<>();

    Map<UUID, List<String>> platformsByEntry = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select entry_id, platform_code from ip_risk_entry_platform where tenant_id = ?",
            tenant)) {
      platformsByEntry
          .computeIfAbsent(row.get("entry_id", UUID.class), k -> new ArrayList<>())
          .add(row.get("platform_code", String.class));
    }

    Map<UUID, List<Record>> termsByEntry = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select entry_id, term, term_kind from ip_risk_match_term where tenant_id = ?",
            tenant)) {
      termsByEntry
          .computeIfAbsent(row.get("entry_id", UUID.class), k -> new ArrayList<>())
          .add(row);
    }

    for (Record entry :
        sql.fetch(
            "select id, entry_code, right_type, risk_level, brand_name, match_pattern"
                + " from ip_risk_entry where tenant_id = ? and status = 'ACTIVE'"
                + " order by id",
            tenant)) {
      UUID entryId = entry.get("id", UUID.class);
      List<String> platformCodes = platformsByEntry.getOrDefault(entryId, List.of());
      if (!platformCodes.isEmpty()
          && platformCode != null
          && !platformCode.isBlank()
          && !platformCodes.contains(platformCode)) {
        // 🔴 这条条目不适用于本平台 ⇒ 本次不参与比对。
        continue;
      }
      String code = entry.get("entry_code", String.class);
      String rightType = entry.get("right_type", String.class);
      String riskLevel = entry.get("risk_level", String.class);

      String brandName = text(entry.get("brand_name", String.class));
      if (!brandName.isEmpty()) {
        features.add(
            new Feature("ENTRY", entryId.toString(), code, rightType, riskLevel, "BRAND_NAME", "CONTAINS", brandName));
      }
      String pattern = text(entry.get("match_pattern", String.class));
      if (!pattern.isEmpty()) {
        features.add(
            new Feature("ENTRY", entryId.toString(), code, rightType, riskLevel, "PATTERN", "CONTAINS", pattern));
      }
      for (Record term : termsByEntry.getOrDefault(entryId, List.of())) {
        String value = text(term.get("term", String.class));
        if (value.isEmpty()) {
          continue;
        }
        String kind = term.get("term_kind", String.class);
        String mode = "KEYWORD".equals(kind) ? "KEYWORD" : "PHRASE";
        features.add(
            new Feature("ENTRY", entryId.toString(), code, rightType, riskLevel, "TERM", mode, value));
      }
    }

    // 🔴 外部查询结果：只有**已查回结果**（RETURNED）的查询才参与比对 ——
    //   「已登记但还没查」的查询单不产生命中（否则会把「还没查」说成「查了没有」）。
    for (Record row :
        sql.fetch(
            "select h.id, h.matched_key, h.right_type, h.risk_level, q.query_code"
                + " from ip_detection_external_hit h"
                + " join ip_detection_external_query q"
                + "   on q.tenant_id = h.tenant_id and q.id = h.query_id"
                + " where h.tenant_id = ? and q.query_status = 'RETURNED'"
                + " order by h.id",
            tenant)) {
      String key = text(row.get("matched_key", String.class));
      if (key.isEmpty()) {
        continue;
      }
      features.add(
          new Feature(
              "EXTERNAL",
              row.get("id", UUID.class).toString(),
              row.get("query_code", String.class),
              row.get("right_type", String.class),
              row.get("risk_level", String.class),
              "EXTERNAL_TERM",
              "CONTAINS",
              key));
    }
    return features;
  }

  // ==================================================================
  // 🔴 检测（对外主入口）
  // ==================================================================

  /**
   * 🔴 发起一次检测。
   *
   * @param input 检测对象
   * @param bearer 调用方自己的 bearer（转发给 catalog 解析品牌）
   */
  public IpDetectionReportDetail detect(IpDetectionSubjectInput input, String bearer) {
    return detectInternal(input, bearer, "MANUAL", null, true);
  }

  /** 检测的内部实现（对外入口与巡检共用）。 */
  private IpDetectionReportDetail detectInternal(
      IpDetectionSubjectInput input,
      String bearer,
      String triggerType,
      UUID inspectionRunId,
      boolean allowImage) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();

    IpDetectionSubjectInput effective =
        new IpDetectionSubjectInput(
            input.subjectType(),
            input.listingId(),
            input.draftId(),
            input.title(),
            input.description(),
            input.bulletPoints(),
            input.imageUrls(),
            input.brandName(),
            allowImage ? (input.includeImage() == null || input.includeImage()) : Boolean.FALSE);

    IpDetectionResolvedSubject subject = resolveSubject(tenant, effective, bearer);
    List<Feature> features = loadFeatures(tenant, subject.platformCode());
    List<FieldText> comparable = new ArrayList<>();
    for (IpDetectionField field : subject.fields()) {
      comparable.add(new FieldText(field.field(), field.text()));
    }
    List<Match> matches = matchFeatures(features, comparable);

    // ---- ① 先把结论算出来（纯内存）----
    List<HitSummary> summaries = new ArrayList<>();
    Set<String> sources = new LinkedHashSet<>();
    int textHits = 0;
    int imageHits = 0;
    int pending = 0;
    String riskLevel = "NONE";
    for (Match match : matches) {
      Feature feature = match.feature();
      // 🔴 图片命中一律要人工复核 ⇒ 它**不是**可判定命中。
      boolean hitPending = needsManualReview(match.field());
      if (hitPending) {
        imageHits++;
        pending++;
      } else {
        textHits++;
      }
      sources.add(feature.sourceId());
      riskLevel = maxRisk(riskLevel, feature.riskLevel());
      summaries.add(new HitSummary(feature.riskLevel(), match.field(), hitPending));
    }
    String conclusion = conclusionOf(summaries);
    String recommended = recommendedActionOf(summaries);

    String note = subject.sourceNote();
    if (subject.brandNote() != null && !subject.brandNote().isBlank()) {
      note = (note.isBlank() ? "" : note + " ") + subject.brandNote();
    }

    // ---- ② 写报告（命中项是 append-only，必须先有 report_id 才能写命中项）----
    UUID reportId = UUID.randomUUID();
    sql.execute(
        """
        insert into ip_detection_report
          (id, tenant_id, subject_type, subject_id, subject_label, shop_id, platform_code,
           trigger_type, inspection_run_id, risk_level, conclusion, recommended_action,
           text_hit_count, image_hit_count, entry_hit_count, pending_review_count,
           scanned_field_count, source_note, created_by, created_at, updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
        """,
        reportId,
        tenant,
        subject.subjectType(),
        parseUuid(subject.subjectId()),
        subject.label(),
        parseUuid(subject.shopId()),
        subject.platformCode(),
        triggerType,
        inspectionRunId,
        riskLevel,
        conclusion,
        recommended,
        textHits,
        imageHits,
        sources.size(),
        pending,
        subject.fields().size(),
        note,
        actor);

    // ---- ③ 写命中项（append-only：一条都不可改、不可删）----
    List<IpDetectionHit> hits = new ArrayList<>();
    for (Match match : matches) {
      hits.add(persistHit(tenant, actor, reportId, subject, match));
    }

    // ---- ④ 预警：只有**有命中**才发（「没查出错」不是预警）----
    if (!"CLEAR".equals(conclusion)) {
      String alertType = alertTypeFor(hits, triggerType);
      sql.execute(
          """
          insert into ip_detection_alert
            (id, tenant_id, report_id, alert_level, alert_type, title, detail, status,
             created_by, created_at, updated_at)
          values (?,?,?,?,?,?,?,'OPEN',?,now(),now())
          """,
          UUID.randomUUID(),
          tenant,
          reportId,
          riskLevel,
          alertType,
          alertTitle(subject, conclusion, riskLevel),
          alertDetail(subject, hits, conclusion),
          actor);
    }

    // ---- ⑤ 整改建议：一条命中一条建议（**逐条可追**）----
    for (IpDetectionHit hit : hits) {
      sql.execute(
          """
          insert into ip_detection_remediation
            (id, tenant_id, report_id, hit_id, action_code, suggestion, priority, status,
             created_by, created_at, updated_at)
          values (?,?,?,?,?,?,?,'OPEN',?,now(),now())
          """,
          UUID.randomUUID(),
          tenant,
          reportId,
          UUID.fromString(hit.hitId()),
          remediationActionOf(hit.field(), hit.featureKind()),
          remediationSuggestionOf(
              hit.field(), hit.featureKind(), hit.matchedFeature(), hit.entryCode()),
          hit.riskLevel(),
          actor);
    }

    return reportDetail(tenant, reportId);
  }

  /** 写一条命中项（append-only），并为图片命中建复核任务。 */
  private IpDetectionHit persistHit(
      UUID tenant, UUID actor, UUID reportId, IpDetectionResolvedSubject subject, Match match) {
    UUID hitId = UUID.randomUUID();
    Feature feature = match.feature();
    boolean manualReview = needsManualReview(match.field());
    boolean external = "EXTERNAL".equals(feature.sourceType());
    String evidence =
        writeJson(Map.of("field", match.field(), "reference", referenceOf(subject, match.field())));

    sql.execute(
        """
        insert into ip_detection_hit
          (id, tenant_id, report_id, entry_id, external_hit_id, entry_code, right_type,
           risk_level, field, feature_kind, matched_feature, matched_text, evidence,
           needs_manual_review, created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,now())
        """,
        hitId,
        tenant,
        reportId,
        external ? null : UUID.fromString(feature.sourceId()),
        external ? UUID.fromString(feature.sourceId()) : null,
        feature.code(),
        feature.rightType(),
        feature.riskLevel(),
        match.field(),
        feature.featureKind(),
        feature.text(),
        match.matchedText(),
        evidence,
        manualReview);

    // 🔴🔴 图片命中**自动进人工复核队列**（蓝图：误判与漏判都要留人工复核环节）。
    if (manualReview) {
      sql.execute(
          """
          insert into ip_detection_review_task
            (id, tenant_id, report_id, hit_id, reason, status, created_by, created_at, updated_at)
          values (?,?,?,?,'IMAGE_MATCH','PENDING',?,now(),now())
          """,
          UUID.randomUUID(),
          tenant,
          reportId,
          hitId,
          actor);
    }
    return new IpDetectionHit(
        hitId.toString(),
        reportId.toString(),
        external ? null : feature.sourceId(),
        external ? feature.sourceId() : null,
        feature.code(),
        feature.rightType(),
        feature.riskLevel(),
        match.field(),
        fieldLabel(match.field()),
        feature.featureKind(),
        featureKindLabel(feature.featureKind()),
        feature.text(),
        match.matchedText(),
        manualReview,
        manualReview ? "PENDING" : null,
        dbNow());
  }

  private String referenceOf(IpDetectionResolvedSubject subject, String field) {
    for (IpDetectionField candidate : subject.fields()) {
      if (field.equals(candidate.field())) {
        return candidate.reference();
      }
    }
    return "";
  }


  private String alertTypeFor(List<IpDetectionHit> hits, String triggerType) {
    boolean pending = false;
    boolean external = false;
    for (IpDetectionHit hit : hits) {
      if (hit.needsManualReview()) {
        pending = true;
      }
      if (hit.externalHitId() != null) {
        external = true;
      }
    }
    if (pending) {
      return "PENDING_REVIEW";
    }
    if (external) {
      return "EXTERNAL_MATCH";
    }
    if ("INSPECTION".equals(triggerType)) {
      return "INSPECTION_FOUND";
    }
    return "INFRINGEMENT_HIT";
  }

  private String alertTitle(
      IpDetectionResolvedSubject subject, String conclusion, String riskLevel) {
    String what = subject.label().isBlank() ? subject.subjectType() : subject.label();
    return "🔴 侵权检测" + riskLevelLabel(riskLevel) + "：" + what;
  }

  private String alertDetail(
      IpDetectionResolvedSubject subject, List<IpDetectionHit> hits, String conclusion) {
    StringBuilder out = new StringBuilder();
    out.append("结论：").append(conclusionLabel(conclusion)).append("。");
    out.append("命中 ").append(hits.size()).append(" 项。");
    if ("REVIEW_REQUIRED".equals(conclusion)) {
      out.append("🔴 **命中都还在等人工复核**，别当结论用。");
    }
    return out.toString();
  }

  // ==================================================================
  // 报告查询
  // ==================================================================

  /**
   * 检测报告清单（**真分页 + 真筛选**）。
   *
   * @param riskLevel 风险等级筛选
   * @param conclusion 结论筛选
   * @param subjectType 对象类型筛选
   * @param triggerType 触发方式筛选
   * @param pendingOnly 只看待复核的
   * @param inspectionRunId 按巡检运行筛选
   * @param keyword 关键字（对象名）
   */
  public IpDetectionReportPage reports(
      String riskLevel,
      String conclusion,
      String subjectType,
      String triggerType,
      boolean pendingOnly,
      String inspectionRunId,
      String keyword,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);

    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String level = text(riskLevel).toUpperCase();
    if (!level.isEmpty()) {
      if (!knownRiskLevel(level)) {
        throw unprocessable("未知的风险等级：" + level + "（只能是 HIGH / MEDIUM / LOW / NONE）");
      }
      where.append(" and r.risk_level = ?");
      params.add(level);
    }
    String concl = text(conclusion).toUpperCase();
    if (!concl.isEmpty()) {
      if (!knownConclusion(concl)) {
        throw unprocessable(
            "未知的结论：" + concl + "（只能是 CLEAR / REVIEW_REQUIRED / ACTION_REQUIRED）");
      }
      where.append(" and r.conclusion = ?");
      params.add(concl);
    }
    String subject = text(subjectType).toUpperCase();
    if (!subject.isEmpty()) {
      if (!knownSubjectType(subject)) {
        throw unprocessable("未知的检测对象类型：" + subject);
      }
      where.append(" and r.subject_type = ?");
      params.add(subject);
    }
    String trigger = text(triggerType).toUpperCase();
    if (!trigger.isEmpty()) {
      if (!"MANUAL".equals(trigger) && !"INSPECTION".equals(trigger)) {
        throw unprocessable("未知的触发方式：" + trigger);
      }
      where.append(" and r.trigger_type = ?");
      params.add(trigger);
    }
    if (pendingOnly) {
      where.append(" and r.pending_review_count > 0");
    }
    UUID runId = parseUuid(inspectionRunId);
    if (runId != null) {
      where.append(" and r.inspection_run_id = ?");
      params.add(runId);
    }
    String word = text(keyword);
    if (!word.isEmpty()) {
      where.append(" and r.subject_label ilike ?");
      params.add("%" + word + "%");
    }

    String base = " from ip_detection_report r" + where;
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));

    Map<String, Long> riskLevelCounts = new LinkedHashMap<>();
    for (String code : RISK_LEVELS) {
      riskLevelCounts.put(code, 0L);
    }
    Map<String, Long> conclusionCounts = new LinkedHashMap<>();
    for (String code : CONCLUSIONS) {
      conclusionCounts.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select r.risk_level, r.conclusion, count(*) as c" + base + " group by r.risk_level, r.conclusion",
            params.toArray())) {
      riskLevelCounts.merge(row.get("risk_level", String.class), count(row.get("c")), Long::sum);
      conclusionCounts.merge(row.get("conclusion", String.class), count(row.get("c")), Long::sum);
    }
    long pendingTotal =
        count(
            sql.fetchValue(
                "select coalesce(sum(r.pending_review_count),0)" + base, params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionReport> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.*" + base + " order by r.created_at desc, r.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(reportOf(row));
    }

    return new IpDetectionReportPage(
        items,
        total,
        safePage,
        safeSize,
        riskLevelCounts,
        conclusionCounts,
        pendingTotal,
        "🔴 结论三态：**CLEAR**（无命中）/ **REVIEW_REQUIRED**（有命中但**全部**还在等人工复核）/ "
            + "**ACTION_REQUIRED**（存在**可判定**的命中）。"
            + "🔴 **判不了 ≠ 没问题**，也 ≠ 判了有问题。");
  }

  /** 报告详情（报告 + 命中项 + 预警 + 整改建议 + 复核任务）。 */
  public IpDetectionReportDetail report(String reportId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(reportId);
    Record row =
        sql.fetchOne("select r.* from ip_detection_report r where r.tenant_id = ? and r.id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    return reportDetail(tenant, id);
  }

  private IpDetectionReportDetail reportDetail(UUID tenant, UUID reportId) {
    Record row =
        sql.fetchOne("select r.* from ip_detection_report r where r.tenant_id = ? and r.id = ?", tenant, reportId);
    if (row == null) {
      throw notFound();
    }
    IpDetectionReport report = reportOf(row);

    Map<UUID, String> reviewStatusByHit = new LinkedHashMap<>();
    for (Record review :
        sql.fetch(
            "select hit_id, status from ip_detection_review_task where tenant_id = ? and report_id = ?",
            tenant,
            reportId)) {
      UUID hitId = review.get("hit_id", UUID.class);
      if (hitId != null) {
        reviewStatusByHit.put(hitId, review.get("status", String.class));
      }
    }

    List<IpDetectionHit> hits = new ArrayList<>();
    for (Record hit :
        sql.fetch(
            "select * from ip_detection_hit where tenant_id = ? and report_id = ? order by field, id",
            tenant,
            reportId)) {
      UUID hitId = hit.get("id", UUID.class);
      String field = hit.get("field", String.class);
      hits.add(
          new IpDetectionHit(
              hitId.toString(),
              reportId.toString(),
              idText(hit.get("entry_id", UUID.class)),
              idText(hit.get("external_hit_id", UUID.class)),
              hit.get("entry_code", String.class),
              hit.get("right_type", String.class),
              hit.get("risk_level", String.class),
              field,
              fieldLabel(field),
              hit.get("feature_kind", String.class),
              featureKindLabel(hit.get("feature_kind", String.class)),
              hit.get("matched_feature", String.class),
              hit.get("matched_text", String.class),
              hit.get("needs_manual_review", Boolean.class),
              reviewStatusByHit.get(hitId),
              time(hit, "created_at")));
    }

    List<IpDetectionAlert> alerts = new ArrayList<>();
    for (Record alert :
        sql.fetch(
            "select * from ip_detection_alert where tenant_id = ? and report_id = ? order by created_at, id",
            tenant,
            reportId)) {
      String status = alert.get("status", String.class);
      String level = alert.get("alert_level", String.class);
      String type = alert.get("alert_type", String.class);
      alerts.add(
          new IpDetectionAlert(
              alert.get("id", UUID.class).toString(),
              reportId.toString(),
              level,
              alertLevelLabel(level),
              type,
              alertTypeLabel(type),
              alert.get("title", String.class),
              alert.get("detail", String.class),
              status,
              alertStatusLabel(status),
              idText(alert.get("acknowledged_by", UUID.class)),
              time(alert, "acknowledged_at"),
              alert.get("resolution_note", String.class),
              time(alert, "created_at")));
    }

    List<IpDetectionRemediation> remediations = new ArrayList<>();
    for (Record item :
        sql.fetch(
            "select * from ip_detection_remediation where tenant_id = ? and report_id = ? order by created_at, id",
            tenant,
            reportId)) {
      String status = item.get("status", String.class);
      String action = item.get("action_code", String.class);
      remediations.add(
          new IpDetectionRemediation(
              item.get("id", UUID.class).toString(),
              reportId.toString(),
              idText(item.get("hit_id", UUID.class)),
              action,
              remediationActionLabel(action),
              item.get("suggestion", String.class),
              item.get("priority", String.class),
              status,
              remediationStatusLabel(status),
              idText(item.get("assignee_id", UUID.class)),
              time(item, "due_at"),
              time(item, "completed_at"),
              time(item, "created_at")));
    }

    List<IpDetectionReviewTask> reviews = new ArrayList<>();
    for (Record review :
        sql.fetch(
            "select * from ip_detection_review_task where tenant_id = ? and report_id = ? order by created_at, id",
            tenant,
            reportId)) {
      String status = review.get("status", String.class);
      String reason = review.get("reason", String.class);
      reviews.add(
          new IpDetectionReviewTask(
              review.get("id", UUID.class).toString(),
              reportId.toString(),
              idText(review.get("hit_id", UUID.class)),
              reason,
              reviewReasonLabel(reason),
              status,
              reviewStatusLabel(status),
              idText(review.get("reviewer_id", UUID.class)),
              time(review, "reviewed_at"),
              review.get("review_comment", String.class),
              time(review, "created_at")));
    }

    return new IpDetectionReportDetail(
        report,
        hits,
        alerts,
        remediations,
        reviews,
        "🔴 命中项是 **append-only**（DB 层钉死）："
            + "条目后来被停用 / 改写，这里仍显示**命中当时的快照**。"
            + "🔴 复核结论会**回写报告的结论**（判定误报的命中不再计入）。");
  }

  private IpDetectionReport reportOf(Record row) {
    String riskLevel = row.get("risk_level", String.class);
    String conclusion = row.get("conclusion", String.class);
    String recommended = row.get("recommended_action", String.class);
    String subjectType = row.get("subject_type", String.class);
    return new IpDetectionReport(
        row.get("id", UUID.class).toString(),
        subjectType,
        subjectTypeLabel(subjectType),
        idText(row.get("subject_id", UUID.class)),
        row.get("subject_label", String.class),
        idText(row.get("shop_id", UUID.class)),
        row.get("platform_code", String.class),
        row.get("trigger_type", String.class),
        idText(row.get("inspection_run_id", UUID.class)),
        riskLevel,
        riskLevelLabel(riskLevel),
        conclusion,
        conclusionLabel(conclusion),
        recommended,
        recommendedActionLabel(recommended),
        intOf(row, "text_hit_count"),
        intOf(row, "image_hit_count"),
        intOf(row, "entry_hit_count"),
        intOf(row, "pending_review_count"),
        intOf(row, "scanned_field_count"),
        row.get("source_note", String.class),
        time(row, "created_at"));
  }

  // ==================================================================
  // 预警
  // ==================================================================

  /** 预警清单（真分页 + 真筛选）。 */
  public IpDetectionAlertPage alerts(String status, String alertType, int page, int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String state = text(status).toUpperCase();
    if (!state.isEmpty()) {
      if (!knownAlertStatus(state)) {
        throw unprocessable("未知的预警状态：" + state);
      }
      where.append(" and a.status = ?");
      params.add(state);
    }
    String type = text(alertType).toUpperCase();
    if (!type.isEmpty()) {
      where.append(" and a.alert_type = ?");
      params.add(type);
    }
    String base = " from ip_detection_alert a" + where;
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionAlert> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select a.*" + base + " order by a.created_at desc, a.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(alertOf(row));
    }
    return new IpDetectionAlertPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 预警**不是拦截**：处置动作归 16.2.1.2，本模块只负责把「发现了什么、建议怎么办」说清楚。");
  }

  private IpDetectionAlert alertOf(Record row) {
    String level = row.get("alert_level", String.class);
    String type = row.get("alert_type", String.class);
    String status = row.get("status", String.class);
    return new IpDetectionAlert(
        row.get("id", UUID.class).toString(),
        idText(row.get("report_id", UUID.class)),
        level,
        alertLevelLabel(level),
        type,
        alertTypeLabel(type),
        row.get("title", String.class),
        row.get("detail", String.class),
        status,
        alertStatusLabel(status),
        idText(row.get("acknowledged_by", UUID.class)),
        time(row, "acknowledged_at"),
        row.get("resolution_note", String.class),
        time(row, "created_at"));
  }

  /** 确认 / 处置一条预警（终态不可回退）。 */
  public IpDetectionAlert setAlertStatus(String alertId, IpDetectionAlertStatusInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID id = requireUuid(alertId);
    String status = text(input.status()).toUpperCase();
    if (!knownAlertStatus(status)) {
      throw unprocessable("未知的预警状态：" + text(input.status()));
    }
    if ("OPEN".equals(status)) {
      throw unprocessable("🔴 预警不能退回「待处理」—— 终态不可回退（DB 层也钉着）。");
    }
    String note = text(input.resolutionNote());
    if ("RESOLVED".equals(status) && note.isEmpty()) {
      throw unprocessable("🔴 处置预警必须写处置说明（否则事后没人说得清为什么放过）。");
    }
    int updated =
        sql.execute(
            "update ip_detection_alert set status = ?, acknowledged_by = ?,"
                + " acknowledged_at = now(), resolution_note = ?, updated_at = now()"
                + " where tenant_id = ? and id = ? and status = 'OPEN'",
            status,
            actor,
            note,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    Record row =
        sql.fetchOne("select a.* from ip_detection_alert a where a.tenant_id = ? and a.id = ?", tenant, id);
    return alertOf(row);
  }

  // ==================================================================
  // 整改建议
  // ==================================================================

  /** 整改建议清单（真分页 + 真筛选）。 */
  public IpDetectionRemediationPage remediations(String status, String actionCode, int page, int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);
    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String state = text(status).toUpperCase();
    if (!state.isEmpty()) {
      if (!knownRemediationStatus(state)) {
        throw unprocessable("未知的整改状态：" + state);
      }
      where.append(" and r.status = ?");
      params.add(state);
    }
    String action = text(actionCode).toUpperCase();
    if (!action.isEmpty()) {
      if (!knownRemediationAction(action)) {
        throw unprocessable("未知的整改动作：" + action);
      }
      where.append(" and r.action_code = ?");
      params.add(action);
    }
    String base = " from ip_detection_remediation r" + where;
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionRemediation> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.*" + base + " order by r.created_at desc, r.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(remediationOf(row));
    }
    return new IpDetectionRemediationPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 建议里的动作**指向**下游（16.4.2 授权核验 / 16.2.1.2 豁免），本模块**不代下游执行**。");
  }

  private IpDetectionRemediation remediationOf(Record row) {
    String status = row.get("status", String.class);
    String action = row.get("action_code", String.class);
    return new IpDetectionRemediation(
        row.get("id", UUID.class).toString(),
        idText(row.get("report_id", UUID.class)),
        idText(row.get("hit_id", UUID.class)),
        action,
        remediationActionLabel(action),
        row.get("suggestion", String.class),
        row.get("priority", String.class),
        status,
        remediationStatusLabel(status),
        idText(row.get("assignee_id", UUID.class)),
        time(row, "due_at"),
        time(row, "completed_at"),
        time(row, "created_at"));
  }

  /** 手工新增一条整改建议。 */
  public IpDetectionRemediation addRemediation(IpDetectionRemediationInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID reportId = requireUuid(input.reportId());
    String action = text(input.actionCode()).toUpperCase();
    if (!knownRemediationAction(action)) {
      throw unprocessable("未知的整改动作：" + text(input.actionCode()));
    }
    String suggestion = text(input.suggestion());
    if (suggestion.isEmpty()) {
      throw unprocessable("🔴 整改建议必须写正文（没有内容的建议没人会去做）。");
    }
    String priority = text(input.priority()).toUpperCase();
    if (priority.isEmpty()) {
      priority = "MEDIUM";
    }
    if (!ALERT_LEVELS.contains(priority)) {
      throw unprocessable("未知的优先级：" + priority);
    }
    UUID hitId = parseUuid(input.hitId());
    if (hitId != null
        && count(
                sql.fetchValue(
                    "select count(*) from ip_detection_hit where tenant_id = ? and id = ? and report_id = ?",
                    tenant,
                    hitId,
                    reportId))
            == 0) {
      throw unprocessable("🔴 指定的命中不属于这份报告。");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into ip_detection_remediation
          (id, tenant_id, report_id, hit_id, action_code, suggestion, priority, status,
           assignee_id, due_at, created_by, created_at, updated_at)
        values (?,?,?,?,?,?,?,'OPEN',?,?::timestamptz,?,now(),now())
        """,
        id,
        tenant,
        reportId,
        hitId,
        action,
        suggestion,
        priority,
        parseUuid(input.assigneeId()),
        input.dueAt(),
        actor);
    Record row =
        sql.fetchOne("select r.* from ip_detection_remediation r where r.tenant_id = ? and r.id = ?", tenant, id);
    return remediationOf(row);
  }

  /** 推进整改建议的状态（终态不可回退）。 */
  public IpDetectionRemediation setRemediationStatus(
      String remediationId, IpDetectionRemediationStatusInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(remediationId);
    String status = text(input.status()).toUpperCase();
    if (!knownRemediationStatus(status)) {
      throw unprocessable("未知的整改状态：" + text(input.status()));
    }
    if ("OPEN".equals(status)) {
      throw unprocessable("🔴 整改建议不能退回「待处理」—— 终态不可回退（DB 层也钉着）。");
    }
    int updated =
        sql.execute(
            "update ip_detection_remediation set status = ?,"
                + " completed_at = case when ? in ('DONE','DISMISSED') then now() else null end,"
                + " updated_at = now()"
                + " where tenant_id = ? and id = ? and status in ('OPEN','IN_PROGRESS')",
            status,
            status,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    Record row =
        sql.fetchOne("select r.* from ip_detection_remediation r where r.tenant_id = ? and r.id = ?", tenant, id);
    return remediationOf(row);
  }

  // ==================================================================
  // 🔴 人工复核
  // ==================================================================

  /** 复核队列（真分页 + 真筛选）。 */
  public IpDetectionReviewTaskPage reviews(String status, int page, int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);
    StringBuilder where = new StringBuilder(" where v.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String state = text(status).toUpperCase();
    if (!state.isEmpty()) {
      if (!knownReviewStatus(state)) {
        throw unprocessable("未知的复核状态：" + state);
      }
      where.append(" and v.status = ?");
      params.add(state);
    }
    String base = " from ip_detection_review_task v" + where;
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionReviewTask> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select v.*" + base + " order by v.created_at desc, v.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(reviewOf(row));
    }
    return new IpDetectionReviewTaskPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 **待复核的命中不算「可判定命中」** ⇒ 一份全是图片命中的报告，"
            + "结论是「待人工复核」而**不是**「已判定侵权」。");
  }

  private IpDetectionReviewTask reviewOf(Record row) {
    String status = row.get("status", String.class);
    String reason = row.get("reason", String.class);
    return new IpDetectionReviewTask(
        row.get("id", UUID.class).toString(),
        idText(row.get("report_id", UUID.class)),
        idText(row.get("hit_id", UUID.class)),
        reason,
        reviewReasonLabel(reason),
        status,
        reviewStatusLabel(status),
        idText(row.get("reviewer_id", UUID.class)),
        time(row, "reviewed_at"),
        row.get("review_comment", String.class),
        time(row, "created_at"));
  }

  /** 提一条复核（文本命中被判误判时的申诉）。 */
  public IpDetectionReviewTask requestReview(IpDetectionReviewInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID reportId = requireUuid(input.reportId());
    UUID hitId = requireUuid(input.hitId());
    String reason = text(input.reason()).toUpperCase();
    if (!knownReviewReasons(reason) || "IMAGE_MATCH".equals(reason)) {
      throw unprocessable(
          "未知的复核原因：" + text(input.reason()) + "（手工提交通常是 TEXT_MISMATCH / APPEAL；"
              + "IMAGE_MATCH 由系统自动建立）");
    }
    String comment = text(input.comment());
    if (comment.isEmpty()) {
      throw unprocessable("🔴 提复核必须写说明（申诉什么都不说，复核人没法判）。");
    }
    Object exists =
        sql.fetchValue(
            "select id from ip_detection_hit where tenant_id = ? and id = ? and report_id = ?",
            tenant,
            hitId,
            reportId);
    if (exists == null) {
      throw notFound();
    }
    UUID id = UUID.randomUUID();
    // 🔴 用 `on conflict do nothing` 而不是「先查后插」：整个服务调用跑在**一个事务**里
    //   （AccessService.run → TenantTransactions.run），唯一约束冲突会把事务置为 aborted，
    //   之后再执行任何语句都会失败 —— 那种写法会把「已在队列里」变成一句看不懂的 500。
    //   冲突时返回 null ⇒ 报 422（一个命中只允许一条复核任务）。
    Object inserted =
        sql.fetchValue(
            """
            insert into ip_detection_review_task
              (id, tenant_id, report_id, hit_id, reason, status, review_comment, created_by,
               created_at, updated_at)
            values (?,?,?,?,?,'PENDING',?,?,now(),now())
            on conflict (tenant_id, hit_id) do nothing
            returning id
            """,
            id,
            tenant,
            reportId,
            hitId,
            reason,
            comment,
            actor);
    if (inserted == null) {
      throw unprocessable("🔴 这条命中**已经在复核队列里**了（一个命中只允许一条复核任务）。");
    }
    recomputeReport(tenant, reportId);
    Record row =
        sql.fetchOne("select v.* from ip_detection_review_task v where v.tenant_id = ? and v.id = ?", tenant, id);
    return reviewOf(row);
  }

  /** 复核结论（终态不可回退）。 */
  public IpDetectionReviewTask decideReview(
      String reviewTaskId, IpDetectionReviewDecisionInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID id = requireUuid(reviewTaskId);
    String status = text(input.status()).toUpperCase();
    if (!"CONFIRMED".equals(status) && !"DISMISSED".equals(status)) {
      throw unprocessable("🔴 复核结论只能是 CONFIRMED / DISMISSED（不能退回 PENDING）。");
    }
    String comment = text(input.comment());
    if (comment.isEmpty()) {
      throw unprocessable("🔴 复核必须写意见（没意见的复核等于没复核）。");
    }
    int updated =
        sql.execute(
            "update ip_detection_review_task set status = ?, reviewer_id = ?, reviewed_at = now(),"
                + " review_comment = ?, updated_at = now()"
                + " where tenant_id = ? and id = ? and status = 'PENDING'",
            status,
            actor,
            comment,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    Record row =
        sql.fetchOne("select v.* from ip_detection_review_task v where v.tenant_id = ? and v.id = ?", tenant, id);
    IpDetectionReviewTask result = reviewOf(row);
    // 🔴 复核结论**回写报告的结论**（判误报的命中不再计入；确认的命中变成可判定命中）。
    UUID reportId = row.get("report_id", UUID.class);
    if (reportId != null) {
      recomputeReport(tenant, reportId);
    }
    return result;
  }

  /**
   * 🔴 按复核结果**重算**一份报告的结论与计数。
   *
   * <p>判定误报（DISMISSED）的命中不再计入；确认（CONFIRMED）的命中变成可判定命中。
   */
  private void recomputeReport(UUID tenant, UUID reportId) {
    Map<UUID, String> reviewStatusByHit = new LinkedHashMap<>();
    for (Record review :
        sql.fetch(
            "select hit_id, status from ip_detection_review_task where tenant_id = ? and report_id = ?",
            tenant,
            reportId)) {
      UUID hitId = review.get("hit_id", UUID.class);
      if (hitId != null) {
        reviewStatusByHit.put(hitId, review.get("status", String.class));
      }
    }
    List<HitSummary> summaries = new ArrayList<>();
    int textHits = 0;
    int imageHits = 0;
    int pending = 0;
    Set<String> sources = new LinkedHashSet<>();
    for (Record hit :
        sql.fetch(
            "select id, entry_id, external_hit_id, risk_level, field from ip_detection_hit"
                + " where tenant_id = ? and report_id = ? order by id",
            tenant,
            reportId)) {
      UUID hitId = hit.get("id", UUID.class);
      String reviewStatus = reviewStatusByHit.get(hitId);
      if ("DISMISSED".equals(reviewStatus)) {
        // 🔴 判定误报 ⇒ 不再计入任何计数。
        continue;
      }
      String field = hit.get("field", String.class);
      boolean isImage = "IMAGE".equals(field);
      if (isImage) {
        imageHits++;
      } else {
        textHits++;
      }
      UUID entryId = hit.get("entry_id", UUID.class);
      UUID externalHitId = hit.get("external_hit_id", UUID.class);
      sources.add(entryId != null ? entryId.toString() : String.valueOf(externalHitId));
      boolean hitPending = "PENDING".equals(reviewStatus);
      if (hitPending) {
        pending++;
      }
      summaries.add(new HitSummary(hit.get("risk_level", String.class), field, hitPending));
    }
    String riskLevel = "NONE";
    for (HitSummary summary : summaries) {
      riskLevel = maxRisk(riskLevel, summary.riskLevel());
    }
    String conclusion = conclusionOf(summaries);
    String recommended = recommendedActionOf(summaries);
    sql.execute(
        "update ip_detection_report set risk_level = ?, conclusion = ?, recommended_action = ?,"
            + " text_hit_count = ?, image_hit_count = ?, entry_hit_count = ?,"
            + " pending_review_count = ?, updated_at = now()"
            + " where tenant_id = ? and id = ?",
        riskLevel,
        conclusion,
        recommended,
        textHits,
        imageHits,
        sources.size(),
        pending,
        tenant,
        reportId);
  }

  private static boolean knownReviewReasons(String code) {
    return REVIEW_REASONS.contains(code);
  }

  // ==================================================================
  // 外部商标 / 专利查询（登记承接）
  // ==================================================================

  /** 外部查询清单（真分页）。 */
  public IpDetectionExternalQueryPage externalQueries(String status, int page, int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);
    StringBuilder where = new StringBuilder(" where q.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String state = text(status).toUpperCase();
    if (!state.isEmpty()) {
      if (!knownQueryStatus(state)) {
        throw unprocessable("未知的外部查询状态：" + state);
      }
      where.append(" and q.query_status = ?");
      params.add(state);
    }
    String base = " from ip_detection_external_query q" + where;
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionExternalQuery> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select q.*" + base + " order by q.created_at desc, q.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(externalQueryOf(row));
    }
    return new IpDetectionExternalQueryPage(
        items,
        total,
        safePage,
        safeSize,
        EXTERNAL_NOTE);
  }

  private IpDetectionExternalQuery externalQueryOf(Record row) {
    String status = row.get("query_status", String.class);
    String rightType = row.get("right_type", String.class);
    return new IpDetectionExternalQuery(
        row.get("id", UUID.class).toString(),
        row.get("query_code", String.class),
        rightType,
        rightTypeLabel(rightType),
        row.get("keyword", String.class),
        row.get("platform_code", String.class),
        row.get("source_name", String.class),
        status,
        queryStatusLabel(status),
        intOf(row, "result_count"),
        row.get("error_message", String.class),
        time(row, "queried_at"),
        row.get("note", String.class),
        time(row, "created_at"));
  }

  /** 登记一条外部查询（**按查询编码幂等**）。 */
  public IpDetectionExternalQuery createExternalQuery(IpDetectionExternalQueryInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    String code = text(input.queryCode());
    if (code.isEmpty()) {
      throw unprocessable("🔴 查询编码不能为空（它是幂等锚点）。");
    }
    String rightType = text(input.rightType()).toUpperCase();
    if (!RIGHT_TYPES.contains(rightType)) {
      throw unprocessable(
          "未知的权利类型：" + text(input.rightType()) + "（只能是 TRADEMARK / PATENT / COPYRIGHT / IMAGE_RIGHT）");
    }
    String keyword = text(input.keyword());
    if (keyword.isEmpty()) {
      throw unprocessable("🔴 查询词不能为空（它就是要比对的关键词）。");
    }
    UUID id = UUID.randomUUID();
    Object inserted =
        sql.fetchValue(
            """
            insert into ip_detection_external_query
              (id, tenant_id, query_code, right_type, keyword, platform_code, source_name,
               query_status, result_count, error_message, queried_at, note, created_by,
               created_at, updated_at)
            values (?,?,?,?,?,?,?,'REGISTERED',0,'',null,?,?,now(),now())
            on conflict (tenant_id, query_code) do nothing
            returning id
            """,
            id,
            tenant,
            code,
            rightType,
            keyword,
            text(input.platformCode()).isEmpty() ? null : text(input.platformCode()).toUpperCase(),
            text(input.sourceName()),
            text(input.note()),
            actor);
    if (inserted == null) {
      Record existing =
          sql.fetchOne(
              "select q.* from ip_detection_external_query q where q.tenant_id = ? and q.query_code = ?",
              tenant,
              code);
      if (existing == null) {
        throw new IllegalStateException("查询编码命中唯一约束但读不到该行");
      }
      return externalQueryOf(existing);
    }
    Record row =
        sql.fetchOne("select q.* from ip_detection_external_query q where q.tenant_id = ? and q.id = ?", tenant, id);
    return externalQueryOf(row);
  }

  /** 外部查询详情（查询 + 登记回来的结果）。 */
  public IpDetectionExternalQueryDetail externalQuery(String queryId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(queryId);
    Record row =
        sql.fetchOne(
            "select q.* from ip_detection_external_query q where q.tenant_id = ? and q.id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    List<IpDetectionExternalHit> hits = new ArrayList<>();
    for (Record hit :
        sql.fetch(
            "select * from ip_detection_external_hit where tenant_id = ? and query_id = ? order by id",
            tenant,
            id)) {
      hits.add(externalHitOf(hit));
    }
    return new IpDetectionExternalQueryDetail(externalQueryOf(row), hits, EXTERNAL_NOTE);
  }

  private IpDetectionExternalHit externalHitOf(Record row) {
    return new IpDetectionExternalHit(
        row.get("id", UUID.class).toString(),
        idText(row.get("query_id", UUID.class)),
        row.get("matched_key", String.class),
        row.get("right_type", String.class),
        row.get("risk_level", String.class),
        row.get("registration_no", String.class),
        row.get("rights_holder", String.class),
        row.get("source_name", String.class),
        time(row, "created_at"));
  }

  /**
   * 🔴 登记外部查询**查回来的结果**（append-only），并把查询单推进到 RETURNED。
   *
   * @param queryId 查询 id
   * @param hits 登记回来的命中
   */
  public IpDetectionExternalQueryDetail registerExternalHits(
      String queryId, List<IpDetectionExternalHitInput> hits) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID id = requireUuid(queryId);
    Record row =
        sql.fetchOne(
            "select q.* from ip_detection_external_query q where q.tenant_id = ? and q.id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    if (hits == null || hits.isEmpty()) {
      throw unprocessable("🔴 登记外部结果至少要给一条（一条都没有就登记成「查无结果」）。");
    }
    int inserted = 0;
    for (IpDetectionExternalHitInput hit : hits) {
      String key = text(hit.matchedKey());
      if (key.isEmpty()) {
        throw unprocessable("🔴 外部命中的关键词不能为空（它就是要比对的东西）。");
      }
      String rightType = text(hit.rightType()).toUpperCase();
      if (!RIGHT_TYPES.contains(rightType)) {
        throw unprocessable("未知的权利类型：" + text(hit.rightType()));
      }
      String riskLevel = text(hit.riskLevel()).toUpperCase();
      if (!ALERT_LEVELS.contains(riskLevel)) {
        throw unprocessable("未知的风险等级：" + text(hit.riskLevel()));
      }
      sql.execute(
          """
          insert into ip_detection_external_hit
            (id, tenant_id, query_id, matched_key, right_type, risk_level, registration_no,
             rights_holder, source_name, created_at)
          values (?,?,?,?,?,?,?,?,?,now())
          """,
          UUID.randomUUID(),
          tenant,
          id,
          key,
          rightType,
          riskLevel,
          text(hit.registrationNo()),
          text(hit.rightsHolder()),
          text(hit.sourceName()));
      inserted++;
    }
    sql.execute(
        "update ip_detection_external_query set query_status = 'RETURNED', result_count = ?,"
            + " queried_at = now(), error_message = '', updated_at = now()"
            + " where tenant_id = ? and id = ?",
        inserted,
        tenant,
        id);
    return externalQuery(id.toString());
  }

  /**
   * 把外部查询单推进到「查无结果」或「查询失败」。
   *
   * @param queryId 查询 id
   * @param status NO_RESULT / FAILED
   * @param errorMessage 失败原因（FAILED 时必填）
   */
  public IpDetectionExternalQuery setExternalQueryStatus(
      String queryId, String status, String errorMessage) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(queryId);
    String state = text(status).toUpperCase();
    if (!"NO_RESULT".equals(state) && !"FAILED".equals(state)) {
      throw unprocessable(
          "🔴 查询状态只能推进到 NO_RESULT / FAILED（查回结果请用「登记外部结果」）。");
    }
    String message = text(errorMessage);
    if ("FAILED".equals(state) && message.isEmpty()) {
      throw unprocessable("🔴 查询失败必须写原因（不然就是静默丢了一次查询）。");
    }
    int updated =
        sql.execute(
            "update ip_detection_external_query set query_status = ?, error_message = ?,"
                + " queried_at = now(), updated_at = now()"
                + " where tenant_id = ? and id = ? and query_status = 'REGISTERED'",
            state,
            message,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    Record row =
        sql.fetchOne("select q.* from ip_detection_external_query q where q.tenant_id = ? and q.id = ?", tenant, id);
    return externalQueryOf(row);
  }

  // ==================================================================
  // 🔴 巡检
  // ==================================================================

  /** 巡检计划清单。 */
  public List<IpDetectionInspectionPlan> plans() {
    UUID tenant = currentTenant();
    List<IpDetectionInspectionPlan> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select p.* from ip_detection_inspection_plan p where p.tenant_id = ?"
                + " order by p.created_at desc, p.id desc",
            tenant)) {
      items.add(planOf(row));
    }
    return items;
  }

  private IpDetectionInspectionPlan planOf(Record row) {
    String scope = row.get("scope", String.class);
    return new IpDetectionInspectionPlan(
        row.get("id", UUID.class).toString(),
        row.get("plan_code", String.class),
        scope,
        planScopeLabel(scope),
        idText(row.get("shop_id", UUID.class)),
        intOf(row, "interval_days"),
        row.get("include_image", Boolean.class),
        row.get("enabled", Boolean.class),
        time(row, "last_run_at"),
        time(row, "next_run_at"),
        row.get("note", String.class),
        time(row, "created_at"));
  }

  /** 新建巡检计划（**按计划编码幂等**）。 */
  public IpDetectionInspectionPlan createPlan(IpDetectionInspectionPlanInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    String code = text(input.planCode());
    if (code.isEmpty()) {
      throw unprocessable("🔴 计划编码不能为空（它是幂等锚点）。");
    }
    String scope = text(input.scope()).toUpperCase();
    if (!PLAN_SCOPES.contains(scope)) {
      throw unprocessable("未知的巡检范围：" + text(input.scope()) + "（只能是 ALL / SHOP）");
    }
    UUID shopId = parseUuid(input.shopId());
    if ("SHOP".equals(scope) && shopId == null) {
      throw unprocessable("🔴 范围选了「指定店铺」就必须给店铺。");
    }
    int intervalDays = input.intervalDays() == null ? 7 : input.intervalDays();
    if (intervalDays < 1 || intervalDays > 365) {
      throw unprocessable("🔴 巡检间隔必须在 1..365 天之间。");
    }
    boolean includeImage = input.includeImage() == null || input.includeImage();
    boolean enabled = input.enabled() == null || input.enabled();

    UUID id = UUID.randomUUID();
    Object inserted =
        sql.fetchValue(
            """
            insert into ip_detection_inspection_plan
              (id, tenant_id, plan_code, scope, shop_id, interval_days, include_image, enabled,
               note, created_by, created_at, updated_at)
            values (?,?,?,?,?,?,?,?,?,?,now(),now())
            on conflict (tenant_id, plan_code) do nothing
            returning id
            """,
            id,
            tenant,
            code,
            scope,
            shopId,
            intervalDays,
            includeImage,
            enabled,
            text(input.note()),
            actor);
    if (inserted == null) {
      Record existing =
          sql.fetchOne(
              "select p.* from ip_detection_inspection_plan p where p.tenant_id = ? and p.plan_code = ?",
              tenant,
              code);
      if (existing == null) {
        throw new IllegalStateException("计划编码命中唯一约束但读不到该行");
      }
      return planOf(existing);
    }
    Record row =
        sql.fetchOne(
            "select p.* from ip_detection_inspection_plan p where p.tenant_id = ? and p.id = ?", tenant, id);
    return planOf(row);
  }

  /** 改巡检计划（计划编码是身份，不可改）。 */
  public IpDetectionInspectionPlan updatePlan(
      String planId, IpDetectionInspectionPlanInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(planId);
    Record existing =
        sql.fetchOne(
            "select p.* from ip_detection_inspection_plan p where p.tenant_id = ? and p.id = ?", tenant, id);
    if (existing == null) {
      throw notFound();
    }
    String scope = text(input.scope()).toUpperCase();
    if (scope.isEmpty()) {
      scope = existing.get("scope", String.class);
    }
    if (!PLAN_SCOPES.contains(scope)) {
      throw unprocessable("未知的巡检范围：" + text(input.scope()));
    }
    UUID shopId = parseUuid(input.shopId());
    if (shopId == null && "SHOP".equals(scope)) {
      shopId = existing.get("shop_id", UUID.class);
    }
    if ("SHOP".equals(scope) && shopId == null) {
      throw unprocessable("🔴 范围选了「指定店铺」就必须给店铺。");
    }
    int intervalDays =
        input.intervalDays() == null ? intOf(existing, "interval_days") : input.intervalDays();
    if (intervalDays < 1 || intervalDays > 365) {
      throw unprocessable("🔴 巡检间隔必须在 1..365 天之间。");
    }
    boolean includeImage =
        input.includeImage() == null
            ? existing.get("include_image", Boolean.class)
            : input.includeImage();
    boolean enabled = input.enabled() == null ? existing.get("enabled", Boolean.class) : input.enabled();
    String note = input.note() == null ? existing.get("note", String.class) : text(input.note());

    int updated =
        sql.execute(
            "update ip_detection_inspection_plan set scope = ?, shop_id = ?, interval_days = ?,"
                + " include_image = ?, enabled = ?, note = ?, updated_at = now()"
                + " where tenant_id = ? and id = ?",
            scope,
            shopId,
            intervalDays,
            includeImage,
            enabled,
            note,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    Record row =
        sql.fetchOne(
            "select p.* from ip_detection_inspection_plan p where p.tenant_id = ? and p.id = ?", tenant, id);
    return planOf(row);
  }

  /** 巡检运行清单（真分页）。 */
  public IpDetectionInspectionRunPage inspectionRuns(int page, int size) {
    UUID tenant = currentTenant();
    int safeSize = clampSize(size);
    int safePage = clampPage(page);
    String base = " from ip_detection_inspection_run r where r.tenant_id = ?";
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    long total = count(sql.fetchValue("select count(*)" + base, params.toArray()));
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<IpDetectionInspectionRun> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.*" + base + " order by r.started_at desc, r.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(runOf(row));
    }
    return new IpDetectionInspectionRunPage(
        items, total, safePage, safeSize, INSPECTION_NOTE);
  }

  private IpDetectionInspectionRun runOf(Record row) {
    String status = row.get("status", String.class);
    Object planCode =
        sql.fetchValue(
            "select p.plan_code from ip_detection_inspection_plan p where p.tenant_id = ? and p.id = ?",
            currentTenant(),
            row.get("plan_id", UUID.class));
    return new IpDetectionInspectionRun(
        row.get("id", UUID.class).toString(),
        idText(row.get("plan_id", UUID.class)),
        planCode == null ? "" : String.valueOf(planCode),
        status,
        runStatusLabel(status),
        intOf(row, "listing_scanned"),
        intOf(row, "report_count"),
        intOf(row, "hit_listing_count"),
        row.get("error_message", String.class),
        time(row, "started_at"),
        time(row, "finished_at"));
  }

  /** 巡检运行详情（运行 + 这一轮产出的报告）。 */
  public IpDetectionInspectionRunDetail inspectionRun(String runId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(runId);
    Record row =
        sql.fetchOne(
            "select r.* from ip_detection_inspection_run r where r.tenant_id = ? and r.id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    List<IpDetectionReport> reports = new ArrayList<>();
    for (Record report :
        sql.fetch(
            "select r.* from ip_detection_report r where r.tenant_id = ? and r.inspection_run_id = ?"
                + " order by r.created_at, r.id",
            tenant,
            id)) {
      reports.add(reportOf(report));
    }
    return new IpDetectionInspectionRunDetail(runOf(row), reports, INSPECTION_NOTE);
  }

  /**
   * 🔴 **跑一轮巡检**（外部调度触发的一次入口）。
   *
   * <p>逐个已上架商品跑检测，产出报告；个别商品失败**不打断整轮**
   * （整轮状态记 PARTIAL 并写明失败原因）—— 一件商品的快照读不到，
   * 不该让整轮巡检白跑。
   */
  public IpDetectionInspectionRunDetail runInspection(String planId, String bearer) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID id = requireUuid(planId);
    Record planRow =
        sql.fetchOne(
            "select p.* from ip_detection_inspection_plan p where p.tenant_id = ? and p.id = ?", tenant, id);
    if (planRow == null) {
      throw notFound();
    }
    if (!planRow.get("enabled", Boolean.class)) {
      throw unprocessable("🔴 这个巡检计划**已停用** ⇒ 不能跑（停用的计划不该产出新一轮报告）。");
    }
    String scope = planRow.get("scope", String.class);
    UUID shopId = planRow.get("shop_id", UUID.class);
    boolean includeImage = planRow.get("include_image", Boolean.class);

    UUID runId = UUID.randomUUID();
    OffsetDateTime startedAt = dbNow();

    List<Object> params = new ArrayList<>();
    params.add(tenant);
    StringBuilder listingSql =
        new StringBuilder(
            "select l.id from listing l where l.tenant_id = ? and l.status = 'ON_SALE'");
    if ("SHOP".equals(scope) && shopId != null) {
      listingSql.append(" and l.shop_id = ?");
      params.add(shopId);
    }
    listingSql.append(" order by l.id limit ?");
    params.add(MAX_LISTINGS_PER_RUN);
    List<UUID> listingIds = new ArrayList<>();
    for (Record row : sql.fetch(listingSql.toString(), params.toArray())) {
      listingIds.add(row.get("id", UUID.class));
    }

    int scanned = 0;
    int reportCount = 0;
    int hitListings = 0;
    StringBuilder failures = new StringBuilder();
    for (UUID listing : listingIds) {
      scanned++;
      try {
        IpDetectionSubjectInput subject =
            new IpDetectionSubjectInput("LISTING", listing.toString(), null, null, null, null, null, null, includeImage);
        IpDetectionReportDetail detail = detectInternal(subject, bearer, "INSPECTION", runId, includeImage);
        reportCount++;
        if (!"CLEAR".equals(detail.report().conclusion())) {
          hitListings++;
        }
      } catch (RuntimeException e) {
        if (!failures.isEmpty()) {
          failures.append("；");
        }
        failures.append(listing).append("：").append(e.getMessage());
      }
    }

    String status = failures.isEmpty() ? "COMPLETED" : "PARTIAL";
    // 🔴 巡检运行是 **append-only** 的回执：跑完**才落一行**（跑的过程中不落行）。
    //   中途崩溃 ⇒ 这一轮没有回执，计划的 last_run_at 也不会前进 ⇒ 下次调度会重跑。
    sql.execute(
        """
        insert into ip_detection_inspection_run
          (id, tenant_id, plan_id, status, listing_scanned, report_count, hit_listing_count,
           error_message, started_at, finished_at, created_by, created_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,now(),?,now())
        """,
        runId,
        tenant,
        id,
        status,
        scanned,
        reportCount,
        hitListings,
        failures.toString(),
        startedAt,
        actor);

    // 🔴 计划的运行时刻只能**前进**（DB 触发器钉着）。
    sql.execute(
        "update ip_detection_inspection_plan set last_run_at = now(),"
            + " next_run_at = now() + (interval_days || ' days')::interval, updated_at = now()"
            + " where tenant_id = ? and id = ?",
        tenant,
        id);

    return inspectionRun(runId.toString());
  }

  // ==================================================================
  // 小工具（jsonb / 图片）
  // ==================================================================

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  /** jsonb 数组 → 字符串列表（元素可能是字符串，也可能是对象 → 取它的文本）。 */
  private List<String> stringList(Object raw) {
    List<String> out = new ArrayList<>();
    JsonNode node = readNode(raw);
    if (node == null || !node.isArray()) {
      return out;
    }
    for (JsonNode item : node) {
      if (item.isTextual()) {
        out.add(item.asText());
      } else if (item.isObject()) {
        JsonNode value = item.get("value");
        if (value != null && value.isTextual()) {
          out.add(value.asText());
        } else if (item.get("text") != null && item.get("text").isTextual()) {
          out.add(item.get("text").asText());
        }
      }
    }
    return out;
  }

  /** 一张图片的标识（URL / 替代文本 / 原始文件名）。 */
  private record ImageRef(String url, String altText, String originalFilename, String reference) {}

  /** jsonb 数组 → 图片标识列表（元素是字符串或对象，两种都支持）。 */
  private List<ImageRef> imageRefs(Object raw) {
    List<ImageRef> out = new ArrayList<>();
    JsonNode node = readNode(raw);
    if (node == null || !node.isArray()) {
      return out;
    }
    for (JsonNode item : node) {
      if (item.isTextual()) {
        String value = item.asText("");
        if (!value.isBlank()) {
          out.add(new ImageRef(value, null, null, value));
        }
      } else if (item.isObject()) {
        ImageRef ref = imageRef(item);
        if (ref != null) {
          out.add(ref);
        }
      }
    }
    return out;
  }

  /** jsonb 对象 → 图片标识。 */
  private ImageRef imageRef(JsonNode item) {
    if (item == null || !item.isObject()) {
      return null;
    }
    String url = firstText(item, "url", "imageUrl", "src", "storageKey", "originalFilename");
    String alt = firstText(item, "altText", "alt", "title", "caption");
    String filename = firstText(item, "originalFilename", "fileName", "filename", "name");
    if (url == null && alt == null && filename == null) {
      return null;
    }
    String reference = url != null ? url : (filename != null ? filename : String.valueOf(alt));
    return new ImageRef(url, alt, filename, reference);
  }

  private static String firstText(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode value = node.get(key);
      if (value != null && !value.isNull() && value.isValueNode()) {
        String text = value.asText("");
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }
}
