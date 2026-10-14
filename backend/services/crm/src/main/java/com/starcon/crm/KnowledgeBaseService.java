package com.starcon.crm;

import static com.starcon.crm.KnowledgeBaseModels.*;

import com.starcon.crm.KnowledgeBaseModels.CrmCloseVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmCreateEntryInput;
import com.starcon.crm.KnowledgeBaseModels.CrmCreateVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeCapabilities;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeEntry;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeEntryDetail;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeReference;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeScope;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeVersion;
import com.starcon.crm.KnowledgeBaseModels.CrmPublishVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmRecordReferenceInput;
import com.starcon.crm.KnowledgeBaseModels.CrmReviewDueEntry;
import com.starcon.crm.KnowledgeBaseModels.CrmReviewDueReport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.4.2.1 客服知识库：按店铺 / 类目 / 语言组织的客服标准答法，支持版本与生效日期， 记录每一次引用，并支持「平台政策改版后定位待复核条目」。
 *
 * <p>蓝图 15 卷 15.4.2.1 原话：
 *
 * <blockquote>
 *
 * 职责：维护客服知识库：常见问题标准答法、平台政策说明、产品使用指导、退换货规则， 按店铺/类目/语言组织，支持版本与生效日期。 输入：知识条目编辑；版本管理。输出：知识库内容 + 引用记录。
 * 关系：上游 = 3.4.2.2 文案资产；下游 = 15.1.1.2 发送、15.5.1 自动回复。 <b>知识条目必须标注适用平台与生效时间</b>，平台政策变更时才能快速定位过期内容。
 *
 * </blockquote>
 *
 * <p><b>与 11.4.2.2「知识库与话术」（catalog V100，已交付）不是同一个模块</b>： 那边按平台规则版本组织，本模块按店铺 / 类目 / 语言组织。理由与逐条差异见
 * V99 文件头第零节。
 *
 * <p><b>生效区间沿用 catalog V23 / V25 / V95 的 R1–R5，不另立一套</b>： 半开区间 {@code [from,
 * to)}、重叠一律拒绝不做自动裁决、只有已发布版本参与判定、 当前生效版本确定性查询、已发布版本只能向前关闭。本服务**不重算**这些规则 —— 区间重叠由 V99 的
 * guard_crm_knowledge_version_interval 触发器在数据库层挡住， 服务层只负责把可读的原因带给用户。
 */
@Service
public class KnowledgeBaseService {

  /** 蓝图的四类知识（职责句逐字拆开）。DB 有 CHECK，这里同样拒一遍以给出可读原因。 */
  private static final List<String> TOPIC_TYPES =
      List.of("FAQ", "PLATFORM_POLICY", "PRODUCT_USAGE", "RETURN_POLICY");

  /** 两个消费方：15.1.1.2 人工发送 / 15.5.1.1 自动回复（两者当前都未开发，词表先按蓝图钉死）。 */
  private static final List<String> CONSUMERS = List.of("MANUAL_REPLY", "AUTO_REPLY");

  /**
   * review-due 的判定口径，写进响应里让页面能自证它按什么查的。
   *
   * <p>不用「最后更新时间」这种更宽松的判据：改备注 / 改标题都会刷新 updated_at， 与答法正文是否按新政策重写毫无关系；用它会让只改过备注的条目冒充「已按新政策复核过」。
   */
  private static final String REVIEW_CRITERION =
      "当前生效的那一版写于改版之前（effective_from < policyChangedAt），"
          + "且至今仍是开放区间（effective_to 为空，即没有接替版本）——"
          + "即内容仍停留在改版前的政策口径下";

  private final DSLContext sql;

  public KnowledgeBaseService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  public CrmKnowledgeCapabilities capabilities() {
    return new CrmKnowledgeCapabilities(true, true, true, true, TOPIC_TYPES, CONSUMERS);
  }

  // ================================================================ 组织范围（三个维度的真实数据源）

  /**
   * 列出本租户真实存在过的「店铺 × 类目 × 语言」格子。
   *
   * <p>页面上三个下拉框的数据源必须是**这条查询**，不是前端写死的常量 —— 否则「按店铺/类目/语言组织」就退化成一个摆设（V99 第二节）。
   *
   * <p>{@code entryCount} 只统计 ACTIVE 档案：归档的知识不该继续出现在新建下拉框里， 否则运营会建出一个与已归档知识同名的格子（UNIQUE
   * 会拒绝，报错原因还很难看懂）。
   */
  public List<CrmKnowledgeScope> scopes() {
    return sql.fetch(
            "select shop_id, max(shop_name) as shop_name,"
                + " category_id, max(category_name) as category_name,"
                + " locale, count(*) as entry_count"
                + " from crm_knowledge_entry"
                + " where tenant_id = ? and status = 'ACTIVE'"
                + " group by shop_id, category_id, locale"
                + " order by shop_id, category_id, locale",
            currentTenant())
        .map(
            r ->
                new CrmKnowledgeScope(
                    r.get("shop_id", UUID.class),
                    nz(r.get("shop_name", String.class)),
                    r.get("category_id", UUID.class),
                    nz(r.get("category_name", String.class)),
                    r.get("locale", String.class),
                    r.get("entry_count", Long.class)));
  }

  // ================================================================ 条目查询（规则 R4）

  /**
   * 按格子取条目，并带上「此刻生效的那一版」（规则 R4）。
   *
   * <p>这就是蓝图那句话「这家店这个类目中文版当前有效的条目有哪些」的落点。 {@code at} 省略时取此刻；传了就按那一刻判定 —— 下游 15.1.1.2 回溯历史消息时要用它。
   *
   * <p>生效版本用 LATERAL 取：规则 R2 保证同一档案下已发布版本区间不重叠， 所以这个子查询**至多命中一行**；仍然写 LIMIT 1 并按 effective_from 倒序，
   * 是为了让「万一重叠了」（例如有人手工改过库）时的行为是确定的 newest-wins 而不是报错。
   */
  public List<CrmKnowledgeEntry> list(
      UUID shopId,
      UUID categoryId,
      String locale,
      String platformCode,
      String topicType,
      String at,
      boolean includeArchived) {
    OffsetDateTime moment = at == null || at.isBlank() ? now() : parseMoment(at, "查询时刻");
    var rows =
        sql.fetch(
            "select e.id,e.topic_key,e.title,e.shop_id,e.shop_name,e.category_id,e.category_name,"
                + " e.locale,e.platform_code,e.topic_type,e.status,e.note,e.created_at,e.updated_at,"
                + " v.id as version_id,v.version_no,v.question,v.answer,v.status as version_status,"
                + " v.effective_from,v.effective_to,v.published_by,v.published_at,"
                + " v.note as version_note,v.created_at as version_created_at,"
                + " v.updated_at as version_updated_at,"
                + " vc.version_count,coalesce(rc.reference_count,0) as reference_count"
                + " from crm_knowledge_entry e"
                + " left join lateral ("
                + "   select v1.id,v1.version_no,v1.question,v1.answer,v1.status,v1.effective_from,"
                + "          v1.effective_to,v1.published_by,v1.published_at,v1.note,"
                + "          v1.created_at,v1.updated_at"
                + "     from crm_knowledge_version v1"
                + "    where v1.tenant_id = e.tenant_id and v1.entry_id = e.id"
                + "      and v1.status = 'PUBLISHED'"
                + "      and v1.effective_from <= ?::timestamptz"
                + "      and (v1.effective_to is null or ?::timestamptz < v1.effective_to)"
                + "    order by v1.effective_from desc, v1.id limit 1) v on true"
                + " left join lateral ("
                + "   select count(*) as version_count from crm_knowledge_version v2"
                + "    where v2.tenant_id = e.tenant_id and v2.entry_id = e.id) vc on true"
                + " left join lateral ("
                + "   select count(*) as reference_count from crm_knowledge_reference r"
                + "     join crm_knowledge_version rv on rv.tenant_id = r.tenant_id and rv.id = r.version_id"
                + "    where rv.tenant_id = e.tenant_id and rv.entry_id = e.id) rc on true"
                + " where e.tenant_id = ?"
                + (shopId == null ? "" : " and e.shop_id = ?")
                + (categoryId == null ? "" : " and e.category_id = ?")
                + (locale == null || locale.isBlank() ? "" : " and e.locale = ?")
                + (platformCode == null || platformCode.isBlank() ? "" : " and e.platform_code = ?")
                + (topicType == null || topicType.isBlank() ? "" : " and e.topic_type = ?")
                + (includeArchived ? "" : " and e.status = 'ACTIVE'")
                + " order by e.updated_at desc, e.id",
            bindings(
                moment,
                moment,
                currentTenant(),
                shopId,
                categoryId,
                blankToNull(locale),
                blankToNull(platformCode),
                blankToNull(topicType)));
    return rows.map(KnowledgeBaseService::entry);
  }

  /** 按格子取「此刻生效的条目」—— 只返回有生效版本的那些。 */
  public List<CrmKnowledgeEntry> effectiveEntries(
      UUID shopId, UUID categoryId, String locale, String at) {
    return list(shopId, categoryId, locale, null, null, at, false).stream()
        .filter(e -> e.effectiveVersion() != null)
        .toList();
  }

  /** 档案详情：档案本体 + 全部版本（按版本号倒序）。 */
  public CrmKnowledgeEntryDetail detail(UUID entryId) {
    CrmKnowledgeEntry entry = requireEntry(entryId);
    return new CrmKnowledgeEntryDetail(entry, versions(entryId));
  }

  /** 某条知识的全部版本（按版本号倒序）。 */
  public List<CrmKnowledgeVersion> versions(UUID entryId) {
    requireEntry(entryId);
    return versionRows(entryId);
  }

  // ================================================================ 条目编辑

  /** 新建知识档案。建档案**不**自动建版本：草稿内容由运营在建完档案后按需补上。 */
  @Transactional
  public CrmKnowledgeEntry createEntry(CrmCreateEntryInput input, UUID actorId) {
    String topicType = oneOf(TOPIC_TYPES, input.topicType(), "知识类型");
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_knowledge_entry"
              + "(id,tenant_id,topic_key,title,shop_id,shop_name,category_id,category_name,"
              + " locale,platform_code,topic_type,status,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.topicKey().trim(),
          input.title().trim(),
          input.shopId(),
          nz(input.shopName()),
          input.categoryId(),
          nz(input.categoryName()),
          input.locale().trim(),
          input.platformCode().trim(),
          topicType,
          "ACTIVE",
          nz(input.note()),
          moment,
          moment);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这个「店铺 × 类目 × 语言」格子里已经有同一条知识（"
              + input.topicKey().trim()
              + "）了。一个格子只能有一条标准答法，否则客服和自动回复都答不出"
              + "「这个格子此刻该给买家哪一条」。请换一个业务键，或在既有条目上新建版本。");
    }
    return requireEntry(id);
  }

  // ================================================================ 版本管理

  /**
   * 新建一个草稿版本。
   *
   * <p>草稿的区间可以随便设（规则 R3：只有已发布版本参与重叠判定）， 但**空区间与倒挂区间**在这里就被拒掉：CHECK 也会挡，但 CHECK 只会说 constraint
   * failed， 这里给出人话。
   */
  @Transactional
  public CrmKnowledgeVersion createVersion(UUID entryId, CrmCreateVersionInput input) {
    requireEntry(entryId);
    OffsetDateTime from = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime to =
        input.effectiveTo() == null || input.effectiveTo().isBlank()
            ? null
            : parseMoment(input.effectiveTo(), "生效终点");
    if (to != null && !to.isAfter(from)) {
      throw bad(
          "生效区间是空的：终点「"
              + to
              + "」不晚于起点「"
              + from
              + "」。本模块用半开区间 [起点, 终点)，终点必须晚于起点；"
              + "要表达「一直有效」请把终点留空。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_knowledge_version"
              + "(id,tenant_id,entry_id,version_no,question,answer,status,"
              + " effective_from,effective_to,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,'DRAFT',?::timestamptz,?::timestamptz,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          entryId,
          input.versionNo(),
          input.question().trim(),
          input.answer().trim(),
          from,
          to,
          nz(input.note()),
          moment,
          moment);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "同一条知识下已经有版本号 "
              + input.versionNo()
              + " 了。两行时「v"
              + input.versionNo()
              + " 是哪一个」无法回答，而生效区间判定会两边都算数。请换一个版本号。");
    }
    return requireVersion(id);
  }

  /**
   * 发布一个草稿版本（不可逆）。
   *
   * <p>发布那一刻才检查区间重叠（规则 R3 → R2）：数据库层的 guard_crm_knowledge_version_interval 会拒绝并点名与哪一版撞了， 这里把它翻译成
   * 409 + 可读原因。
   */
  @Transactional
  public CrmKnowledgeVersion publish(UUID versionId, CrmPublishVersionInput input, UUID actorId) {
    CrmKnowledgeVersion version = requireVersion(versionId);
    if ("PUBLISHED".equals(version.status())) {
      throw bad(
          "版本 "
              + version.versionNo()
              + " 已经是已发布状态。发布是单向的：买家已经按这一版收到了答复，"
              + "撤回会让「当时答复的是哪一版」无从查证。要换答法请新建一版并让新版本接替它。");
    }
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "update crm_knowledge_version set status='PUBLISHED', published_by=?,"
              + " published_at=?::timestamptz, note=?, updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          actorId,
          moment,
          input == null || input.note() == null || input.note().isBlank()
              ? version.note()
              : input.note().trim(),
          moment,
          currentTenant(),
          versionId);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这一版的生效区间与同一条知识下**另一条已发布版本**重叠。"
              + "同一条知识在同一时刻只能有一个生效版本，否则客服与自动回复都答不出"
              + "「这个格子此刻该给买家哪一条答法」。请把本版本的区间挪开，"
              + "或把接替版本的终点设为当前生效版本的起点。");
    }
    return requireVersion(versionId);
  }

  /**
   * 向前关闭生效区间（规则 R5）—— 已发布版本上唯一合法的写操作。
   *
   * <p>只允许收紧到一个**仍在未来**的时刻：收回到 null 或往后延都会被 V99 的触发器拒绝。
   */
  @Transactional
  public CrmKnowledgeVersion close(UUID versionId, CrmCloseVersionInput input) {
    CrmKnowledgeVersion version = requireVersion(versionId);
    if (!"PUBLISHED".equals(version.status())) {
      throw bad("版本 " + version.versionNo() + " 还是草稿，不需要关闭生效区间 —— 草稿还没生效。请先发布。");
    }
    OffsetDateTime to = parseMoment(input.effectiveTo(), "生效终点");
    if (!to.isAfter(now())) {
      throw bad(
          "生效终点「"
              + to
              + "」已经过去了。已发布的版本只能**向前关闭**到一个仍在未来的时刻："
              + "往前关会追溯改写历史上这个格子生效的是哪一版，而买家已经按当时的版本收到了答复。");
    }
    try {
      sql.execute(
          "update crm_knowledge_version set effective_to=?::timestamptz, updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          to,
          now(),
          currentTenant(),
          versionId);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "无法关闭：已发布的版本只能向前关闭到一个仍在未来的时刻，" + "不能重开（改回一直有效）也不能往后延 —— 那两种都会与接替它的版本重叠。");
    }
    return requireVersion(versionId);
  }

  // ================================================================ 引用记录（真实落库）

  /**
   * 记录一次引用。
   *
   * <p>{@code referencedBy} 取服务端已认证的 actor，不接受客户端传值；{@code referencedAt} 取服务端 now() ——
   * 引用记录的时间必须是系统事实，不能由调用方编造。
   *
   * <p>引用是**只追加**的（V99 第五节）：本模块刻意不提供修改 / 删除入口。
   */
  @Transactional
  public CrmKnowledgeReference recordReference(
      UUID versionId, CrmRecordReferenceInput input, UUID actorId) {
    CrmKnowledgeVersion version = requireVersion(versionId);
    String consumer = oneOf(CONSUMERS, input.consumer(), "引用来源");
    if (!"PUBLISHED".equals(version.status())) {
      throw bad("版本 " + version.versionNo() + " 还是草稿，草稿没有生效过，引用它等于在引用一段从未给过买家的内容。" + "请先发布这一版。");
    }
    // 生效起点直接从库里读成 OffsetDateTime 再比 —— 不要拿传输层的字符串去比时间，
    // 那等于把「时刻」退化成「长得像时刻的字符串」。
    OffsetDateTime effectiveFrom =
        sql.fetchOne(
                "select effective_from from crm_knowledge_version where tenant_id = ? and id = ?",
                currentTenant(),
                versionId)
            .get("effective_from", OffsetDateTime.class);
    if (effectiveFrom.isAfter(now())) {
      throw bad(
          "版本 "
              + version.versionNo()
              + " 的生效起点是 "
              + effectiveFrom
              + "，还没生效，现在引用它会让「买家当时收到了哪一版」变成假记录。");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_knowledge_reference"
            + "(id,tenant_id,version_id,referenced_by,referenced_at,consumer,source_ref,note)"
            + " values (?,?,?,?,?::timestamptz,?,?,?)",
        id,
        currentTenant(),
        versionId,
        actorId,
        moment,
        consumer,
        blankToNull(input.sourceRef()),
        nz(input.note()));
    return referenceById(id);
  }

  /** 某**版本**的引用记录（谁在什么时候引用了它）。 */
  public List<CrmKnowledgeReference> references(UUID versionId) {
    requireVersion(versionId);
    return referenceRows(" and r.version_id = ?", versionId);
  }

  /** 某**档案**全部版本的引用记录（这条知识历史上被用过没有）。 */
  public List<CrmKnowledgeReference> entryReferences(UUID entryId) {
    requireEntry(entryId);
    return referenceRows(" and v.entry_id = ?", entryId);
  }

  // ================================================================ 平台改版定位过期内容

  /**
   * 「某平台改版后哪些条目需要复核」—— 蓝图粗体句的正面回答。
   *
   * <p>判据：给定平台 P 与该平台新政策的生效时刻 T，返回 「<b>当前生效</b>的那一版写于 T <b>之前</b>、且至今仍是<b>开放区间</b>（没有接替版本）」的条目。
   * 换句话说：**内容还停留在改版前的政策口径下、而且从来没人改过**。
   *
   * <p>为什么「开放区间」是必要条件：如果某一版已经被接替（effective_to 非空）， 那么它已经退出历史舞台，此刻生效的是接替它的那一版 —— 那**不是**过期内容，
   * 把它们列出来只会让运营去复核一批早已不再生效的答案。
   *
   * <p>这依赖 (platform_code) × (effective_from, effective_to) 的倒排形状， V99 第五节为它建了 {@code
   * crm_knowledge_version_open_ended} 部分索引。
   */
  public CrmReviewDueReport reviewDue(String platformCode, String policyChangedAt) {
    String platform = platformCode == null ? null : platformCode.trim();
    if (platform == null || platform.isEmpty()) {
      throw bad("必须指定平台码：改版定位是「按平台」问的，没有平台的答法不适用任何平台的政策。");
    }
    if (!platform.matches("^[A-Z][A-Z0-9_]*$")) {
      throw bad("平台码「" + platform + "」格式不对：要用大写字母开头，例如 AMAZON / SHOPIFY。");
    }
    OffsetDateTime changedAt = parseMoment(policyChangedAt, "政策改版生效时刻");
    var rows =
        sql.fetch(
            "select e.id,e.topic_key,e.title,e.shop_id,e.shop_name,e.category_id,e.category_name,"
                + " e.locale,e.platform_code,e.topic_type,"
                + " v.id as version_id,v.version_no,v.question,v.effective_from,"
                + " (select count(*) from crm_knowledge_reference r"
                + "   where r.tenant_id = v.tenant_id and r.version_id = v.id) as reference_count"
                + " from crm_knowledge_entry e"
                + " join crm_knowledge_version v on v.tenant_id = e.tenant_id and v.entry_id = e.id"
                + " where e.tenant_id = ? and e.platform_code = ? and e.status = 'ACTIVE'"
                + "   and v.status = 'PUBLISHED'"
                + "   and v.effective_from < ?::timestamptz"
                + "   and v.effective_to is null"
                + " order by v.effective_from, e.id",
            currentTenant(),
            platform,
            changedAt);
    OffsetDateTime moment = now();
    var entries = new ArrayList<CrmReviewDueEntry>();
    for (Record r : rows) {
      var from = r.get("effective_from", OffsetDateTime.class);
      entries.add(
          new CrmReviewDueEntry(
              r.get("id", UUID.class),
              r.get("topic_key", String.class),
              r.get("title", String.class),
              r.get("shop_id", UUID.class),
              nz(r.get("shop_name", String.class)),
              r.get("category_id", UUID.class),
              nz(r.get("category_name", String.class)),
              r.get("locale", String.class),
              r.get("platform_code", String.class),
              r.get("topic_type", String.class),
              r.get("version_id", UUID.class),
              r.get("version_no", Integer.class),
              r.get("question", String.class),
              from == null ? null : from.toString(),
              from == null ? 0L : Duration.between(from, moment).toDays(),
              "WRITTEN_BEFORE_CHANGE",
              r.get("reference_count", Long.class)));
    }
    return new CrmReviewDueReport(
        platform, changedAt.toString(), REVIEW_CRITERION, entries.size(), List.copyOf(entries));
  }

  // ================================================================ 内部

  private CrmKnowledgeEntry requireEntry(UUID entryId) {
    var row =
        sql.fetchOne(
            "select e.id,e.topic_key,e.title,e.shop_id,e.shop_name,e.category_id,e.category_name,"
                + " e.locale,e.platform_code,e.topic_type,e.status,e.note,e.created_at,e.updated_at,"
                + " v.id as version_id,v.version_no,v.question,v.answer,v.status as version_status,"
                + " v.effective_from,v.effective_to,v.published_by,v.published_at,"
                + " v.note as version_note,v.created_at as version_created_at,"
                + " v.updated_at as version_updated_at,"
                + " vc.version_count,coalesce(rc.reference_count,0) as reference_count"
                + " from crm_knowledge_entry e"
                + " left join lateral ("
                + "   select v1.id,v1.version_no,v1.question,v1.answer,v1.status,v1.effective_from,"
                + "          v1.effective_to,v1.published_by,v1.published_at,v1.note,"
                + "          v1.created_at,v1.updated_at"
                + "     from crm_knowledge_version v1"
                + "    where v1.tenant_id = e.tenant_id and v1.entry_id = e.id"
                + "      and v1.status = 'PUBLISHED'"
                + "      and v1.effective_from <= ?::timestamptz"
                + "      and (v1.effective_to is null or ?::timestamptz < v1.effective_to)"
                + "    order by v1.effective_from desc, v1.id limit 1) v on true"
                + " left join lateral ("
                + "   select count(*) as version_count from crm_knowledge_version v2"
                + "    where v2.tenant_id = e.tenant_id and v2.entry_id = e.id) vc on true"
                + " left join lateral ("
                + "   select count(*) as reference_count from crm_knowledge_reference r"
                + "     join crm_knowledge_version rv on rv.tenant_id = r.tenant_id and rv.id = r.version_id"
                + "    where rv.tenant_id = e.tenant_id and rv.entry_id = e.id) rc on true"
                + " where e.tenant_id = ? and e.id = ?",
            now(),
            now(),
            currentTenant(),
            entryId);
    if (row == null) throw notFound("知识条目不存在");
    return entry(row);
  }

  private CrmKnowledgeVersion requireVersion(UUID versionId) {
    var row =
        sql.fetchOne(
            versionColumns() + " where tenant_id = ? and id = ?", currentTenant(), versionId);
    if (row == null) throw notFound("知识版本不存在");
    return version(row);
  }

  private List<CrmKnowledgeVersion> versionRows(UUID entryId) {
    return sql.fetch(
            versionColumns() + " where tenant_id = ? and entry_id = ? order by version_no desc",
            currentTenant(),
            entryId)
        .map(KnowledgeBaseService::version);
  }

  private CrmKnowledgeReference referenceById(UUID id) {
    var row =
        sql.fetchOne(
            referenceColumns() + " where r.tenant_id = ? and r.id = ?", currentTenant(), id);
    if (row == null) throw notFound("引用记录不存在");
    return reference(row);
  }

  private List<CrmKnowledgeReference> referenceRows(String extraCondition, UUID argument) {
    return sql.fetch(
            referenceColumns()
                + " where r.tenant_id = ?"
                + extraCondition
                + " order by r.referenced_at desc, r.id",
            currentTenant(),
            argument)
        .map(KnowledgeBaseService::reference);
  }

  private static String versionColumns() {
    return "select v.id,v.entry_id,v.version_no,v.question,v.answer,v.status,"
        + " v.effective_from,v.effective_to,v.published_by,v.published_at,v.note,"
        + " v.created_at,v.updated_at,"
        + " (select count(*) from crm_knowledge_reference r"
        + "   where r.tenant_id = v.tenant_id and r.version_id = v.id) as reference_count"
        + " from crm_knowledge_version v";
  }

  private static String referenceColumns() {
    return "select r.id,r.version_id,r.referenced_by,r.referenced_at,r.consumer,r.source_ref,r.note,"
        + " v.version_no,v.entry_id,e.title as entry_title"
        + " from crm_knowledge_reference r"
        + " join crm_knowledge_version v on v.tenant_id = r.tenant_id and v.id = r.version_id"
        + " join crm_knowledge_entry e on e.tenant_id = v.tenant_id and e.id = v.entry_id";
  }

  private static CrmKnowledgeEntry entry(Record r) {
    return new CrmKnowledgeEntry(
        r.get("id", UUID.class),
        r.get("topic_key", String.class),
        r.get("title", String.class),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        r.get("category_id", UUID.class),
        nz(r.get("category_name", String.class)),
        r.get("locale", String.class),
        r.get("platform_code", String.class),
        r.get("topic_type", String.class),
        r.get("status", String.class),
        r.get("version_id", UUID.class) == null ? null : versionOf(r, "version_"),
        r.get("version_count", Integer.class),
        r.get("reference_count", Long.class),
        r.get("note", String.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 从条目查询的行里取生效版本。
   *
   * <p>列名必须与 list()/requireEntry() 里那两段 SELECT <b>逐字对齐</b>： 只有会与档案列撞名的才加了 {@code version_} 前缀（id /
   * status / note / created_at /
   * updated_at），其余（version_no、question、answer、effective_*、published_*）保持原名， 因为档案侧没有同名列，不需要前缀。
   */
  private static CrmKnowledgeVersion versionOf(Record r, String prefix) {
    return new CrmKnowledgeVersion(
        r.get(prefix + "id", UUID.class),
        r.get("id", UUID.class),
        r.get("version_no", Integer.class),
        r.get("question", String.class),
        r.get("answer", String.class),
        r.get(prefix + "status", String.class),
        str(r.get("effective_from", OffsetDateTime.class)),
        str(r.get("effective_to", OffsetDateTime.class)),
        r.get("published_by", UUID.class),
        str(r.get("published_at", OffsetDateTime.class)),
        0L,
        nz(r.get(prefix + "note", String.class)),
        str(r.get(prefix + "created_at", OffsetDateTime.class)),
        str(r.get(prefix + "updated_at", OffsetDateTime.class)));
  }

  private static CrmKnowledgeVersion version(Record r) {
    return new CrmKnowledgeVersion(
        r.get("id", UUID.class),
        r.get("entry_id", UUID.class),
        r.get("version_no", Integer.class),
        r.get("question", String.class),
        r.get("answer", String.class),
        r.get("status", String.class),
        str(r.get("effective_from", OffsetDateTime.class)),
        str(r.get("effective_to", OffsetDateTime.class)),
        r.get("published_by", UUID.class),
        str(r.get("published_at", OffsetDateTime.class)),
        r.get("reference_count", Long.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmKnowledgeReference reference(Record r) {
    return new CrmKnowledgeReference(
        r.get("id", UUID.class),
        r.get("version_id", UUID.class),
        r.get("version_no", Integer.class),
        r.get("entry_id", UUID.class),
        r.get("entry_title", String.class),
        r.get("referenced_by", UUID.class),
        str(r.get("referenced_at", OffsetDateTime.class)),
        r.get("consumer", String.class),
        r.get("source_ref", String.class),
        nz(r.get("note", String.class)));
  }

  /** 拼 list() 的绑定值：跳过 null，让可选过滤条件自然消失。 */
  private static Object[] bindings(
      OffsetDateTime moment,
      OffsetDateTime momentAgain,
      UUID tenant,
      UUID shopId,
      UUID categoryId,
      String locale,
      String platformCode,
      String topicType) {
    var values = new java.util.ArrayList<Object>();
    values.add(moment);
    values.add(momentAgain);
    values.add(tenant);
    if (shopId != null) values.add(shopId);
    if (categoryId != null) values.add(categoryId);
    if (locale != null) values.add(locale);
    if (platformCode != null) values.add(platformCode);
    if (topicType != null) values.add(topicType);
    return values.toArray();
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区**静默偏
   * −28800 秒**，写进去的生效起点与运营填的差 8 小时， 而页面看上去一切正常。缺偏移量时 parse 会抛 DateTimeParseException → 400。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  private static String oneOf(List<String> allowed, String value, String label) {
    String candidate = nz(value).trim();
    if (!allowed.contains(candidate)) {
      throw bad(label + "「" + candidate + "」不在允许取值里：" + String.join(" / ", allowed));
    }
    return candidate;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }
}
