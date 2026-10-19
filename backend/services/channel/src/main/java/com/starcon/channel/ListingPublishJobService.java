package com.starcon.channel;

import static com.starcon.channel.ListingPublishJobModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * 4.3.1.1 一键刊登。
 *
 * <p>蓝图：「单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；{@code idempotency_key}。输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。关系：上游 = 4.2.1.1、5 定价（取价）、7 库存（取可售量）、02 平台能力；下游 = 2.5.1.1 回写任务。<b>跨平台发布不是一个事务</b>：某个平台失败不影响其他平台，各自独立重试。」
 *
 * <p>🔴 本类里有三处**结构**上的不变量：
 *
 * <ol>
 *   <li><b>跨平台发布不是一个事务</b>：一次作业 = 1 个 {@code listing_publish_job} + N 个
 *       {@code listing_publish_job_target}。派发时每个目标<b>各自</b>一个 SAVEPOINT：
 *       某个目标派发失败 → 回滚到它自己的 SAVEPOINT → 只把**那一行**标成 FAILED，
 *       其余目标照旧派发成功。作业表上**没有**"整体成功/失败"列 ——
 *       总体结论每次都从目标行聚合出来，所以"一个平台失败把整批标失败"写不出来。
 *   <li><b>幂等</b>：{@code listing_publish_job} 上 {@code UNIQUE(tenant_id, idempotency_key)}；
 *       同一个键重复提交返回同一条作业，不产生第二条。
 *   <li><b>内容快照</b>：目标行的 {@code content_snapshot} 与作业行的价格 / 库存快照
 *       在写入后**不可变**（DB 触发器钉死）。事后源商品变了、模板改了、店铺改了，
 *       快照仍然是"当时发的是什么"。
 * </ol>
 *
 * <p>跨服务只经授权 HTTP API 读：07 库存可售量走 {@link SellableQuantityQueryPort}
 * （warehouse），03 商品主数据 / 文案库 / 媒体库走 {@link ListingTemplateSourcePort}（catalog）。
 * 租户不由本服务传递 —— 转发的是调用方自己的 bearer，上游仍按已认证成员关系解析租户。
 */
@Service
public class ListingPublishJobService {

  /** 一次一键刊登最多几个目标：不给上限时，多选 200 个店铺能把一次请求撑爆。 */
  public static final int MAX_TARGETS = 50;

  /** 5 定价未交付 ⇒ 取价没有数据源（ownerModule）。 */
  static final String OWNER_PRICING = "5.1.1.1";

  /** 4.2.3.2 合规预检（与本模块并行开发，此刻还不存在）。 */
  static final String OWNER_COMPLIANCE = "4.2.3.2";

  /** 4.3.1.3 发布任务（未交付）：单次发布任务的时间线页面不是本模块的。 */
  static final String OWNER_PUBLISH_TASK = "4.3.1.3";

  /** 4.1.1.3 生命周期与下架（未交付）。 */
  static final String OWNER_LIFECYCLE = "4.1.1.3";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ListingPublishContentResolver resolver;
  private final SellableQuantityQueryPort sellable;

  public ListingPublishJobService(
      DSLContext sql,
      ObjectMapper json,
      ListingPublishContentResolver resolver,
      SellableQuantityQueryPort sellable) {
    this.sql = sql;
    this.json = json;
    this.resolver = resolver;
    this.sellable = sellable;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean blank(String value) {
    return text(value).isEmpty();
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

  private UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw notFound();
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("内容序列化失败", e);
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 未交付的四段如实报出：取价（5.1.1.1）、合规预检（4.2.3.2）、
   * 单次发布任务的时间线页面（4.3.1.3）、下架联动（4.1.1.3）。
   */
  public ListingPublishCapabilities capabilities() {
    return new ListingPublishCapabilities(
        List.of(
            new ListingPublishCapabilityDimension(
                "multi_target_publish",
                "🔴 一个源 SKU 一次性发布到多个目标店铺/站点（每个目标一个发布任务）",
                true,
                "listing_publish_job + listing_publish_job_target（V196）",
                "",
                "N 个目标 N 行，各自的状态 / 尝试次数 / 失败原因 / 回写任务都分得开"),
            new ListingPublishCapabilityDimension(
                "non_transactional_targets",
                "🔴 跨平台发布不是一个事务：某个平台失败不影响其他平台，各自独立重试",
                true,
                "listing_publish_job_target 逐行状态机 + 派发时的逐目标 SAVEPOINT",
                "",
                "平台 A 驳回只改 A 那一行；B、C 照旧派发；重试只重试失败的那一个目标"),
            new ListingPublishCapabilityDimension(
                "idempotent_create",
                "🔴 幂等：同一个 idempotency_key 重复提交返回同一条，不产生第二条",
                true,
                "listing_publish_job UNIQUE(tenant_id, idempotency_key)（V196）",
                "",
                "并发提交也只会有一条（唯一索引 + ON CONFLICT DO NOTHING 兜底）"),
            new ListingPublishCapabilityDimension(
                "content_snapshot",
                "🔴 内容快照：入队/发布那一刻冻结标题/描述/图片/价格/库存的依据",
                true,
                "listing_publish_job_target.content_snapshot + listing_publish_job 的价格/库存快照",
                "",
                "事后源商品变了也能解释「当时发的是什么」；快照写入后由 DB 触发器钉死不可改"),
            new ListingPublishCapabilityDimension(
                "editor_draft_content_source",
                "内容来源一：4.2.1.1 刊登编辑器的入队记录（读它冻结的快照，不重新求值）",
                true,
                "listing_editor_submission（V194，4.2.1.1 已交付）",
                "",
                "发布的就是运营当时提交审核的那一份，不是现在重新算出来的一份"),
            new ListingPublishCapabilityDimension(
                "template_content_source",
                "内容来源二：4.2.2.1 刊登模板（读取值规则，对源 SKU 实时求值）",
                true,
                "listing_template / listing_template_field / listing_template_asset_group（V195，4.2.2.1 已交付）",
                "",
                "模板存规则不存值，所以换图换属性后重新发布求出来的是新值"),
            new ListingPublishCapabilityDimension(
                "price_source",
                "🔴 价格来源策略里的「取价」（05 定价）",
                false,
                "05 定价（5.1.1.1 未开发）",
                OWNER_PRICING,
                "取价没有数据源：选 PRICING_SERVICE 时价格状态是 UNAVAILABLE + 归属 5.1.1.1，"
                    + "**不编一个价**；本次发布不带平台售价，要等 05 定价给出后再补"),
            new ListingPublishCapabilityDimension(
                "compliance_precheck",
                "🔴 合规预检（违禁词 / 侵权 / 图片不合规）",
                false,
                "4.2.3.2 合规预检（与本模块并行开发，此刻还不存在）",
                OWNER_COMPLIANCE,
                "本模块**不读它的表也不调它的端点**；阻断项校验只到字段级（4.2.1.1 的平台字段要求注册表"
                    + "与刊登模板的必填规则），内容合规扫描归 4.2.3.2"),
            new ListingPublishCapabilityDimension(
                "publish_task_timeline",
                "🔴 单次发布任务的步骤时间线页面",
                false,
                "4.3.1.3 发布任务（未开发）",
                OWNER_PUBLISH_TASK,
                "本模块只落步骤数据（listing_publish_job_step）+ 提供进度/结果 API，"
                    + "不提供单次发布任务的时间线页面"),
            new ListingPublishCapabilityDimension(
                "listing_lifecycle_delist",
                "🔴 下架联动（listing 生命周期与下架）",
                false,
                "4.1.1.3 生命周期与下架（未开发）",
                OWNER_LIFECYCLE,
                "发布成功不联动下架；listing 的状态流转归 4.1.1.3"),
            new ListingPublishCapabilityDimension(
                "platform_operation_handoff",
                "🔴 提交平台审核：落成 2.5.1.1 的回写任务，由下游接手真正发给平台",
                true,
                "platform_operation（V2，2.5.1.1 已交付），operation='PUBLISH'",
                "",
                "每个目标一条回写任务，幂等键按目标拆开，所以某个平台的回执不会波及其它目标"),
            new ListingPublishCapabilityDimension(
                "listing_mapping_binding",
                "🔴 建立映射：内部 SKU ↔ 目标店铺/站点（平台商品 id 由平台回执回填）",
                true,
                "listing_publish_job_target（作业的 source_sku_id + 目标行 + external_item_id）",
                "",
                "本模块记录**发布任务级**的映射绑定；4.1.2.1 的 listing_mapping 行由它自己写"
                    + "（每张事实表只有一个写入所有者），平台商品 id 回填后由 4.1.2.1 建档"),
            new ListingPublishCapabilityDimension(
                "sellable_quantity",
                "库存来源：07 库存可售量（跨服务只读）",
                true,
                "warehouse GET /warehouse/inventory/sellable/skus（7.1.1.3，经 SellableQuantityQueryPort）",
                "",
                "读不到就是读不到（503），**绝不**降级成「可售量 = 0」或「全部可售」"),
            new ListingPublishCapabilityDimension(
                "platform_field_requirements",
                "🔴 目标平台的字段要求与能力矩阵（决定这一栏该不该有值、能不能填）",
                true,
                "platform_listing_field_requirement（V194）+ platform.capabilities（2.1.1.2）",
                "",
                "能力矩阵键缺失 = UNKNOWN（不是 false）；本模块用它校对发布内容的长度 / 条数上限")),
        ListingPublishJobModels.CONTENT_SOURCES,
        ListingPublishJobModels.PRICE_SOURCES,
        ListingPublishJobModels.STOCK_SOURCES,
        ListingPublishJobModels.JOB_STATES,
        ListingPublishJobModels.TARGET_STATES,
        ListingPublishJobModels.STEP_STATES,
        ListingPublishJobModels.STEP_KEYS,
        MAX_TARGETS,
        "跨平台发布不是一个事务：一次一键刊登给每个目标建一行任务，某个平台失败（提交失败 / 被驳回）"
            + "只改它自己那一行，其余目标照旧派发；每个目标各自记尝试次数，重试只重试失败的那一个。",
        "幂等：同一个 idempotency_key 重复提交返回同一条作业，不产生第二条（并发提交也只有一条）。",
        "内容快照：入队/发布那一刻冻结了标题 / 描述 / 图片 / 价格 / 库存的依据，"
            + "之后源商品改了也能解释「当时发的是什么」。",
        "上游 = 4.2.1.1 刊登编辑器（入队快照）、4.2.2.1 刊登模板（取值规则）、"
            + "7 库存可售量（warehouse 只读）、02 平台能力矩阵；下游 = 2.5.1.1 回写任务"
            + "（platform_operation，operation='PUBLISH'）。");
  }

  // ==================================================================
  // 选目标 / 选内容来源
  // ==================================================================

  /**
   * 可选的目标（店铺 + 已启用的站点）。
   *
   * <p>数据来自 {@code shop} / {@code shop_marketplace} / {@code platform} / {@code marketplace}
   * 四张真实表，**只列 ACTIVE 的站点** —— 没启用的站点发不过去。
   */
  public List<ListingPublishTargetOption> targetOptions() {
    List<Record> rows =
        sql.fetch(
            """
            select s.id as shop_id, s.code as shop_code, s.platform_id,
                   p.code as platform_code, p.name as platform_name, p.support_status, p.capabilities,
                   m.id as marketplace_id, m.code as marketplace_code, m.name as marketplace_name,
                   m.country_code, m.default_locale
            from shop s
            join platform p on p.id = s.platform_id
            join shop_marketplace sm on sm.tenant_id = s.tenant_id and sm.shop_id = s.id
                 and sm.status = 'ACTIVE'
            join marketplace m on m.id = sm.marketplace_id and m.platform_id = s.platform_id
            where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            order by p.code, s.code, m.code
            """);
    List<ListingPublishTargetOption> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingPublishTargetOption(
              row.get("shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("platform_id", UUID.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              row.get("support_status", String.class),
              row.get("marketplace_id", UUID.class),
              row.get("marketplace_code", String.class),
              row.get("marketplace_name", String.class),
              row.get("country_code", String.class),
              row.get("default_locale", String.class),
              capabilityMap(row.get("capabilities"))));
    }
    return result;
  }

  /** 内容来源候选：4.2.1.1 的入队记录 + 4.2.2.1 的可用模板。 */
  public ListingPublishContentSources contentSources() {
    List<Record> submissions =
        sql.fetch(
            """
            select sub.id, sub.state, sub.locale, sub.content_snapshot, sub.queued_at,
                   p.code as platform_code, s.id as shop_id, s.code as shop_code,
                   m.id as marketplace_id, m.code as marketplace_code
            from listing_editor_submission sub
            join platform p on p.id = sub.platform_id
            join shop s on s.tenant_id = sub.tenant_id and s.id = sub.shop_id
            join marketplace m on m.id = sub.marketplace_id
            where sub.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and sub.state in ('QUEUED','DISPATCHED')
            order by sub.queued_at desc, sub.id
            limit 200
            """);
    List<ListingPublishEditorSubmissionOption> submissionRows = new ArrayList<>();
    for (Record row : submissions) {
      submissionRows.add(
          new ListingPublishEditorSubmissionOption(
              row.get("id", UUID.class),
              row.get("platform_code", String.class),
              row.get("shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("marketplace_id", UUID.class),
              row.get("marketplace_code", String.class),
              row.get("locale", String.class),
              row.get("state", String.class),
              titleOf(row.get("content_snapshot")),
              imageCountOf(row.get("content_snapshot")),
              row.get("queued_at", OffsetDateTime.class)));
    }

    List<Record> templates =
        sql.fetch(
            """
            select t.id, t.code, t.name, t.scope, t.category_path, t.shop_id, t.platform_id,
                   t.marketplace_id, t.language, t.status,
                   p.code as platform_code, m.code as marketplace_code,
                   (select count(*) from listing_template_field f
                     where f.tenant_id = t.tenant_id and f.template_id = t.id) as field_count,
                   (select count(*) from listing_template_asset_group g
                     where g.tenant_id = t.tenant_id and g.template_id = t.id) as group_count
            from listing_template t
            join platform p on p.id = t.platform_id
            left join marketplace m on m.id = t.marketplace_id
            where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and t.status = 'ACTIVE'
            order by t.code
            """);
    List<ListingPublishTemplateOption> templateRows = new ArrayList<>();
    for (Record row : templates) {
      templateRows.add(
          new ListingPublishTemplateOption(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("scope", String.class),
              scopeTarget(row),
              row.get("platform_id", UUID.class),
              row.get("platform_code", String.class),
              row.get("marketplace_id", UUID.class),
              row.get("marketplace_code", String.class),
              row.get("language", String.class),
              row.get("status", String.class),
              row.get("field_count", Integer.class) == null ? 0 : row.get("field_count", Integer.class),
              row.get("group_count", Integer.class) == null ? 0 : row.get("group_count", Integer.class)));
    }
    return new ListingPublishContentSources(submissionRows, templateRows);
  }

  // ==================================================================
  // 建作业
  // ==================================================================

  /**
   * 建一键刊登作业。
   *
   * <p>🔴 幂等：同一个 {@code idempotency_key} 直接返回已存在的那条作业。
   */
  public ListingPublishJobRow create(
      CreateListingPublishJobRequest request, String bearer, UUID actorId) {
    UUID tenant = currentTenant();
    String key = request.idempotencyKey().trim();

    // ---- 幂等：同一个键 ⇒ 返回同一条 ----
    Record existing = jobRecordByKey(tenant, key);
    if (existing != null) {
      return toJob(existing);
    }

    // ---- 目标集合 ----
    List<ListingPublishTargetInput> inputs = request.targets();
    if (inputs.isEmpty()) {
      throw unprocessable("至少要选一个目标店铺与站点");
    }
    if (inputs.size() > MAX_TARGETS) {
      throw unprocessable("一次最多发布到 " + MAX_TARGETS + " 个目标店铺/站点");
    }
    List<ListingPublishTargetInput> distinct = new ArrayList<>();
    for (ListingPublishTargetInput input : inputs) {
      boolean duplicated =
          distinct.stream()
              .anyMatch(
                  existingInput ->
                      existingInput.shopId().equals(input.shopId())
                          && existingInput.marketplaceId().equals(input.marketplaceId())
                          && existingInput.locale().equals(input.locale().trim()));
      if (!duplicated) {
        distinct.add(input);
      }
    }
    if (distinct.isEmpty()) {
      throw unprocessable("目标店铺与站点集合是空的");
    }

    // ---- 目标逐个解析（店铺 / 站点 / 平台）----
    List<TargetContext> contexts = new ArrayList<>();
    for (ListingPublishTargetInput input : distinct) {
      contexts.add(target(tenant, input));
    }

    // ---- 内容来源 ----
    String contentSource = request.contentSource().trim().toUpperCase();
    if (!ListingPublishJobModels.CONTENT_SOURCES.contains(contentSource)) {
      throw unprocessable("未知的内容来源：" + request.contentSource());
    }
    List<UUID> sourceIds = request.contentSourceIds() == null ? List.of() : request.contentSourceIds();
    if (sourceIds.isEmpty()) {
      throw unprocessable(
          contentSource.equals("EDITOR_SUBMISSION")
              ? "内容来源是编辑器草稿时必须选至少一条入队记录"
              : "内容来源是刊登模板时必须选至少一个模板");
    }
    Map<String, ListingPublishContentSnapshot> snapshots = new LinkedHashMap<>();
    List<String> unmatched = new ArrayList<>();
    for (TargetContext context : contexts) {
      UUID sourceId = null;
      if (contentSource.equals("EDITOR_SUBMISSION")) {
        sourceId = matchSubmission(tenant, sourceIds, context);
        if (sourceId == null) {
          unmatched.add(context.label() + "（没有匹配的编辑器入队记录）");
          continue;
        }
        snapshots.put(context.key(), resolver.fromEditorSubmission(tenant, sourceId));
      } else {
        sourceId = matchTemplate(tenant, sourceIds, context);
        if (sourceId == null) {
          unmatched.add(context.label() + "（没有适用于该目标的刊登模板）");
          continue;
        }
        ListingPublishContentSnapshot snapshot =
            resolver.fromTemplate(sourceId, request.sourceSkuId(), context.locale, bearer);
        String blocking = blockingTemplateField(snapshot);
        if (blocking != null) {
          throw unprocessable(context.label() + "：" + blocking);
        }
        snapshots.put(context.key(), snapshot);
      }
      context.contentSourceId = sourceId;
      String limit = limitViolation(context, snapshots.get(context.key()));
      if (limit != null) {
        throw unprocessable(context.label() + "：" + limit);
      }
    }
    if (!unmatched.isEmpty()) {
      throw unprocessable(
          "以下目标找不到内容来源，请补选或改内容来源：" + String.join("；", unmatched));
    }

    // ---- 价格来源策略 ----
    String priceSource = request.priceSource().trim().toUpperCase();
    if (!ListingPublishJobModels.PRICE_SOURCES.contains(priceSource)) {
      throw unprocessable("未知的价格来源策略：" + request.priceSource());
    }
    BigDecimal priceAmount = null;
    String priceCurrency = null;
    String priceState;
    String priceReason = "";
    if (priceSource.equals("MANUAL")) {
      if (request.priceAmount() == null) {
        throw unprocessable("价格来源是「运营手填」时必须给 priceAmount");
      }
      if (blank(request.priceCurrency())) {
        throw unprocessable("价格来源是「运营手填」时必须给 priceCurrency（币种）");
      }
      priceAmount = request.priceAmount();
      priceCurrency = request.priceCurrency().trim().toUpperCase();
      priceState = "SET";
    } else {
      // 🔴 5 定价（5.1.1.1）未交付 ⇒ 取价没有数据源。**不编一个价**，也不折成 0。
      priceState = "UNAVAILABLE";
      priceReason =
          "取价来源 05 定价（"
              + OWNER_PRICING
              + "）尚未交付：本次一键刊登不带平台售价，价格要等 05 给出后再补";
    }

    // ---- 库存来源策略 ----
    String stockSource = request.stockSource().trim().toUpperCase();
    if (!ListingPublishJobModels.STOCK_SOURCES.contains(stockSource)) {
      throw unprocessable("未知的库存来源策略：" + request.stockSource());
    }
    BigDecimal stockQuantity = null;
    String stockState;
    String stockReason = "";
    if (stockSource.equals("WAREHOUSE_AVAILABLE")) {
      // 🔴 读不到就是读不到：上游不可达时本方法直接抛 503，**不**把可售量折成 0。
      var page = sellable.sellable(request.warehouseId(), List.of(request.sourceSkuId()), bearer);
      BigDecimal quantity = null;
      for (var row : page.rows()) {
        if (request.sourceSkuId().equals(row.skuId())) {
          quantity = row.sellable();
          break;
        }
      }
      if (quantity == null) {
        quantity = BigDecimal.ZERO;
        stockReason = "warehouse 没有这个 SKU 的可售量行（= 该 SKU 在可见仓库没有可售量）";
      }
      stockQuantity = quantity;
      stockState = "SET";
    } else {
      stockState = "NOT_SET";
      stockReason = "本次不设置库存（由平台侧自行维护）";
    }

    // ---- 写库 ----
    UUID jobId = UUID.randomUUID();
    UUID primaryShopId = contexts.get(0).shopId;
    Map<String, Object> jobSnapshot = new LinkedHashMap<>();
    jobSnapshot.put("contentSource", contentSource);
    jobSnapshot.put("sourceIds", sourceIds.stream().map(UUID::toString).toList());
    jobSnapshot.put("sourceSkuId", request.sourceSkuId().toString());
    jobSnapshot.put("targets", contexts.size());
    jobSnapshot.put("resolvedAt", OffsetDateTime.now().toString());
    // 🔴 作业行记的是**依据**（内容来源是什么、源 SKU 是谁、解析给了哪些目标）；
    //    冻结的**内容本身**在每个目标行上（按平台 + 站点 + 语言各不相同）。
    jobSnapshot.put(
        "note",
        "冻结的内容在 listing_publish_job_target.content_snapshot（每个目标一份）；"
            + "价格与可售量的快照在作业行上。");

    sql.execute(
        """
        insert into listing_publish_job
          (id,tenant_id,source_sku_id,content_source,content_source_ids,price_source,stock_source,
           warehouse_id,idempotency_key,state,content_snapshot,
           price_amount,price_currency,price_state,price_state_reason,
           stock_quantity,stock_state,stock_state_reason,primary_shop_id,note,created_by,
           created_at,updated_at)
        values (?,?,?,?,?::jsonb,?,?,?,?,'DRAFT',?::jsonb,
                ?,?,?,?,
                ?,?,?,
                ?,?,?,now(),now())
        on conflict (tenant_id, idempotency_key) do nothing
        """,
        jobId,
        tenant,
        request.sourceSkuId(),
        contentSource,
        writeJson(sourceIds.stream().map(UUID::toString).toList()),
        priceSource,
        stockSource,
        request.warehouseId(),
        key,
        writeJson(jobSnapshot),
        priceAmount,
        priceCurrency,
        priceState,
        priceReason,
        stockQuantity,
        stockState,
        stockReason,
        primaryShopId,
        text(request.note()),
        actorId);

    // 🔴 并发提交同一个键时，唯一索引可能已经把作业建走了 ⇒ 读回那一条，不产生第二条。
    Record job = jobRecordByKey(tenant, key);
    if (job == null) {
      throw new IllegalStateException("作业写完读不回来");
    }
    if (!job.get("id", UUID.class).equals(jobId)) {
      return toJob(job);
    }

    int position = 0;
    for (TargetContext context : contexts) {
      ListingPublishContentSnapshot snapshot = snapshots.get(context.key());
      UUID targetId = UUID.randomUUID();
      sql.execute(
          """
          insert into listing_publish_job_target
            (id,tenant_id,job_id,position,platform_id,shop_id,marketplace_id,locale,category_path,
             state,content_source,content_source_id,content_snapshot,platform_request_id,failure_reason,
             attempts,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,
                  'DRAFT',?,?,?::jsonb,'','',0,now(),now())
          """,
          targetId,
          tenant,
          jobId,
          position,
          context.platformId,
          context.shopId,
          context.marketplaceId,
          context.locale,
          context.categoryPath,
          contentSource,
          context.contentSourceId,
          writeJson(snapshot));

      // 目标级步骤：套模板 / 填充内容 / 建立映射 / 上传媒体 / 提交平台审核 / 平台结果
      insertStep(tenant, jobId, targetId, "TEMPLATE", 0, "SUCCEEDED",
          contentSource.equals("TEMPLATE")
              ? "已按刊登模板对源 SKU 求值并冻结"
              : "内容来源是编辑器入队记录，模板步骤不适用（内容已由 4.2.1.1 冻结）");
      insertStep(tenant, jobId, targetId, "CONTENT", 1, "SUCCEEDED",
          "内容快照已冻结（标题 / 描述 / 卖点 / 图片）");
      insertStep(tenant, jobId, targetId, "MAPPING", 2, "SUCCEEDED",
          "映射已建立：内部 SKU "
              + request.sourceSkuId()
              + " → 目标 "
              + context.label()
              + "（平台商品 id 由平台回执回填）");
      insertStep(tenant, jobId, targetId, "MEDIA", 3, "PENDING", "媒体待随发布请求提交");
      insertStep(tenant, jobId, targetId, "SUBMIT", 4, "PENDING", "待提交平台审核");
      insertStep(tenant, jobId, targetId, "RESULT", 5, "PENDING", "等平台回执");
      position++;
    }

    // 作业级步骤：设置价格 / 设置库存
    insertStep(tenant, jobId, null, "PRICE", 0,
        "SET".equals(priceState) ? "SUCCEEDED" : "BLOCKED",
        "SET".equals(priceState)
            ? "价格已冻结（运营手填）"
            : priceReason);
    insertStep(tenant, jobId, null, "STOCK", 1,
        "SET".equals(stockState) ? "SUCCEEDED" : "SKIPPED",
        "SET".equals(stockState)
            ? "可售量已冻结（warehouse 读数）"
            : stockReason);

    return toJob(job);
  }

  // ==================================================================
  // 读
  // ==================================================================

  /** 作业列表（真分页 + 真筛选）。 */
  public List<ListingPublishJobRow> jobs(String state, int page, int size) {
    List<Record> rows;
    if (blank(state)) {
      rows =
          sql.fetch(
              """
              select j.*, s.code as primary_shop_code
              from listing_publish_job j
              join shop s on s.tenant_id = j.tenant_id and s.id = j.primary_shop_id
              where j.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              order by j.created_at desc, j.id
              limit ? offset ?
              """,
              size,
              page * size);
    } else {
      rows =
          sql.fetch(
              """
              select j.*, s.code as primary_shop_code
              from listing_publish_job j
              join shop s on s.tenant_id = j.tenant_id and s.id = j.primary_shop_id
              where j.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                and j.state = ?
              order by j.created_at desc, j.id
              limit ? offset ?
              """,
              state.trim(),
              size,
              page * size);
    }
    List<ListingPublishJobRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(toJob(row));
    }
    return result;
  }

  /** 单个作业（含聚合进度）。 */
  public ListingPublishJobRow job(String rawId) {
    Record row = jobRecord(currentTenant(), parseId(rawId));
    if (row == null) {
      throw notFound();
    }
    return toJob(row);
  }

  /** 作业下的目标列表（每个目标一个发布任务）。 */
  public List<ListingPublishTargetRow> targets(String rawJobId, String state) {
    UUID tenant = currentTenant();
    UUID jobId = parseId(rawJobId);
    List<Record> rows =
        blank(state)
            ? sql.fetch(
                """
                select t.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
                from listing_publish_job_target t
                join platform p on p.id = t.platform_id
                join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
                join marketplace m on m.id = t.marketplace_id
                where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and t.job_id = ?
                order by t.position, t.id
                """,
                jobId)
            : sql.fetch(
                """
                select t.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
                from listing_publish_job_target t
                join platform p on p.id = t.platform_id
                join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
                join marketplace m on m.id = t.marketplace_id
                where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and t.job_id = ? and t.state = ?
                order by t.position, t.id
                """,
                jobId,
                state.trim());
    List<ListingPublishTargetRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(toTarget(row));
    }
    return result;
  }

  /** 单个目标。 */
  public ListingPublishTargetRow target(String rawId) {
    Record row =
        sql.fetchOne(
            """
            select t.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_publish_job_target t
            join platform p on p.id = t.platform_id
            join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
            join marketplace m on m.id = t.marketplace_id
            where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and t.id = ?
            """,
            parseId(rawId));
    if (row == null) {
      throw notFound();
    }
    return toTarget(row);
  }

  /** 作业的步骤时间线（作业级 + 目标级）。 */
  public List<ListingPublishStepRow> steps(String rawJobId) {
    List<Record> rows =
        sql.fetch(
            """
            select * from listing_publish_job_step
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and job_id = ?
            order by (target_id is null) desc, target_id, position, id
            """,
            parseId(rawJobId));
    List<ListingPublishStepRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingPublishStepRow(
              row.get("id", UUID.class),
              row.get("job_id", UUID.class),
              row.get("target_id", UUID.class),
              row.get("step_key", String.class),
              row.get("position", Integer.class) == null ? 0 : row.get("position", Integer.class),
              row.get("state", String.class),
              row.get("detail", String.class),
              row.get("started_at", OffsetDateTime.class),
              row.get("finished_at", OffsetDateTime.class)));
    }
    return result;
  }

  // ==================================================================
  // 派发（🔴 逐目标独立，一个失败不影响其他）
  // ==================================================================

  /**
   * 派发：把草稿态的目标逐个提交平台。
   *
   * <p>🔴 <b>每个目标一个 SAVEPOINT</b>：某个目标派发失败 → 回滚到它自己的 SAVEPOINT →
   * 只把那一行标成 FAILED，<b>其余目标照旧派发成功</b>。这就是「跨平台发布不是一个事务」
   * 在代码里的落法。
   */
  public ListingPublishDispatchResponse dispatch(String rawJobId) {
    UUID tenant = currentTenant();
    UUID jobId = parseId(rawJobId);
    Record job = jobRecord(tenant, jobId);
    if (job == null) {
      throw notFound();
    }
    List<Record> targets =
        sql.fetch(
            """
            select t.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
            from listing_publish_job_target t
            join platform p on p.id = t.platform_id
            join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
            join marketplace m on m.id = t.marketplace_id
            where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and t.job_id = ? and t.state = 'DRAFT'
            order by t.position, t.id
            """,
            jobId);

    List<ListingPublishDispatchEntry> entries = new ArrayList<>();
    for (Record target : targets) {
      UUID targetId = target.get("id", UUID.class);
      sql.execute("savepoint publish_target");
      try {
        dispatchTarget(tenant, job, target);
        sql.execute("release savepoint publish_target");
        entries.add(
            new ListingPublishDispatchEntry(
                targetId,
                target.get("shop_code", String.class),
                target.get("marketplace_code", String.class),
                target.get("platform_code", String.class),
                "DISPATCHED",
                "已提交平台审核，等回执（下游 2.5.1.1 的回写任务已生成）"));
      } catch (RuntimeException e) {
        // 🔴 只回滚**这一个**目标的写入，其它目标不受影响
        sql.execute("rollback to savepoint publish_target");
        String reason = e.getMessage() == null ? "派发失败" : e.getMessage();
        markTargetFailed(tenant, jobId, targetId, reason);
        entries.add(
            new ListingPublishDispatchEntry(
                targetId,
                target.get("shop_code", String.class),
                target.get("marketplace_code", String.class),
                target.get("platform_code", String.class),
                "FAILED",
                "派发失败（不影响其它目标）：" + reason));
      }
    }

    if (!entries.isEmpty()) {
      sql.execute(
          """
          update listing_publish_job
          set state = 'DISPATCHED', dispatched_at = coalesce(dispatched_at, now()), updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and id = ? and state = 'DRAFT'
          """,
          jobId);
    }
    refreshCompletedAt(tenant, jobId);
    Record refreshed = jobRecord(tenant, jobId);
    return new ListingPublishDispatchResponse(
        jobId,
        refreshed == null ? "DRAFT" : refreshed.get("state", String.class),
        entries,
        progress(tenant, jobId));
  }

  /** 一个目标的派发：生成 2.5.1.1 的回写任务 + 把目标推进到「审核中」。 */
  private void dispatchTarget(UUID tenant, Record job, Record target) {
    UUID targetId = target.get("id", UUID.class);
    UUID operationId = enqueuePlatformOperation(tenant, job, target);

    sql.execute(
        """
        update listing_publish_job_target
        set state = 'SUBMITTED', attempts = attempts + 1, dispatched_at = now(),
            failure_reason = '', platform_operation_id = ?, updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and id = ? and state = 'DRAFT'
        """,
        operationId,
        targetId);

    sql.execute(
        """
        update listing_publish_job_step
        set state = 'RUNNING', started_at = coalesce(started_at, now()), finished_at = null,
            detail = ?, updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and target_id = ? and step_key = 'MEDIA'
        """,
        "媒体清单已随发布请求提交，等平台确认",
        targetId);
    sql.execute(
        """
        update listing_publish_job_step
        set state = 'RUNNING', started_at = coalesce(started_at, now()), finished_at = null,
            detail = ?, updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and target_id = ? and step_key = 'SUBMIT'
        """,
        "已提交平台审核，等回执",
        targetId);
  }

  /**
   * 生成 2.5.1.1 的回写任务（{@code platform_operation}，operation='PUBLISH'）。
   *
   * <p>🔴 幂等键**按目标拆开**（{@code OP1-{作业幂等键}-{目标次序}}）：
   * 平台回执落到某一个目标上时，不会波及其它目标的重试计数。
   */
  private UUID enqueuePlatformOperation(UUID tenant, Record job, Record target) {
    String operationKey =
        "OP1-" + job.get("idempotency_key", String.class) + "-" + target.get("position", Integer.class);
    Object existing =
        sql.fetchValue(
            """
            select id from platform_operation
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and idempotency_key = ?
            """,
            operationKey);
    if (existing != null) {
      UUID operationId = UUID.fromString(String.valueOf(existing));
      sql.execute(
          """
          update platform_operation
          set status = 'SENDING', attempts = attempts + 1, updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and id = ?
          """,
          operationId);
      return operationId;
    }

    UUID operationId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("oneClickPublish", true);
    payload.put("publishJobId", job.get("id", UUID.class).toString());
    payload.put("publishTargetId", target.get("id", UUID.class).toString());
    payload.put("sourceSkuId", job.get("source_sku_id", UUID.class).toString());
    payload.put("platformCode", target.get("platform_code", String.class));
    payload.put("shopCode", target.get("shop_code", String.class));
    payload.put("marketplaceCode", target.get("marketplace_code", String.class));
    payload.put("locale", target.get("locale", String.class));
    payload.put("contentSnapshot", readJson(target.get("content_snapshot")));
    payload.put("priceAmount", job.get("price_amount", BigDecimal.class));
    payload.put("priceCurrency", job.get("price_currency", String.class));
    payload.put("stockQuantity", job.get("stock_quantity", BigDecimal.class));

    sql.execute(
        """
        insert into platform_operation
          (id,tenant_id,shop_id,marketplace_id,operation,idempotency_key,payload,status,
           platform_request_id,verification,verified_at,attempts,last_error,occurred_at,updated_at)
        values (?,?,?,?,'PUBLISH',?,?::jsonb,'PENDING','','NOT_REQUIRED',null,0,'',now(),now())
        on conflict (tenant_id, idempotency_key) do nothing
        """,
        operationId,
        tenant,
        target.get("shop_id", UUID.class),
        target.get("marketplace_id", UUID.class),
        operationKey,
        writeJson(payload));
    sql.execute(
        """
        update platform_operation
        set status = 'SENDING', attempts = attempts + 1, updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and idempotency_key = ?
        """,
        operationKey);
    Object id =
        sql.fetchValue(
            """
            select id from platform_operation
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and idempotency_key = ?
            """,
            operationKey);
    if (id == null) {
      throw new IllegalStateException("回写任务写完读不回来");
    }
    return UUID.fromString(String.valueOf(id));
  }

  // ==================================================================
  // 平台回执 / 独立重试
  // ==================================================================

  /**
   * 平台回执（由平台适配器 / 2.5.1.2 核验通道调用）。
   *
   * @param rawTargetId 目标 id
   * @param request 回执（成功 / 驳回 / 失败 + 原因 + 平台商品 id）
   */
  public ListingPublishTargetRow report(String rawTargetId, ListingPublishTargetReportRequest request) {
    UUID tenant = currentTenant();
    UUID targetId = parseId(rawTargetId);
    Record target = targetRecord(tenant, targetId);
    if (target == null) {
      throw notFound();
    }
    String result = request.result() == null ? "" : request.result().trim().toUpperCase();
    if (!List.of("SUCCEEDED", "REJECTED", "FAILED").contains(result)) {
      throw unprocessable("回执结果只能是 SUCCEEDED / REJECTED / FAILED");
    }
    if (!"SUCCEEDED".equals(result) && blank(request.failureReason())) {
      throw unprocessable("驳回 / 失败必须带原因（failureReason）");
    }
    if (!"SUBMITTED".equals(target.get("state", String.class))) {
      throw unprocessable(
          "只有「审核中」的目标能收回执，当前状态是 " + target.get("state", String.class));
    }
    UUID jobId = target.get("job_id", UUID.class);

    sql.execute(
        """
        update listing_publish_job_target
        set state = ?, failure_reason = ?, external_item_id = coalesce(?, external_item_id),
            platform_request_id = coalesce(nullif(?,''), platform_request_id),
            completed_at = now(), updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and id = ?
        """,
        result,
        "SUCCEEDED".equals(result) ? "" : request.failureReason().trim(),
        blank(request.externalItemId()) ? null : request.externalItemId().trim(),
        blank(request.platformRequestId()) ? "" : request.platformRequestId().trim(),
        targetId);

    if ("SUCCEEDED".equals(result)) {
      finishStep(tenant, targetId, "MEDIA", "SUCCEEDED", "媒体已被平台确认入库");
      finishStep(tenant, targetId, "SUBMIT", "SUCCEEDED", "平台审核通过");
      finishStep(tenant, targetId, "RESULT", "SUCCEEDED",
          "发布成功，平台商品 id " + text(request.externalItemId()));
      updateOperation(tenant, target, "SUCCEEDED", request);
    } else if ("REJECTED".equals(result)) {
      failStep(tenant, targetId, "MEDIA", "平台驳回，媒体未能完成入库确认");
      failStep(tenant, targetId, "SUBMIT", "平台驳回：" + request.failureReason().trim());
      failStep(tenant, targetId, "RESULT", "平台驳回：" + request.failureReason().trim());
      updateOperation(tenant, target, "FAILED", request);
    } else {
      failStep(tenant, targetId, "MEDIA", "平台提交失败，媒体未能完成入库确认");
      failStep(tenant, targetId, "SUBMIT", "平台提交失败：" + request.failureReason().trim());
      failStep(tenant, targetId, "RESULT", "平台提交失败：" + request.failureReason().trim());
      updateOperation(tenant, target, "FAILED", request);
    }
    refreshCompletedAt(tenant, jobId);
    return target(targetId.toString());
  }

  /**
   * 🔴 独立重试：只重试**这一个**失败目标。
   *
   * <p>内容快照**不变** —— 重试发的是同一份内容（DB 触发器钉死）。
   * 已驳回（REJECTED）的目标不能重试：内容没改，重发只会再被驳回一次。
   */
  public ListingPublishTargetRow retry(String rawTargetId) {
    UUID tenant = currentTenant();
    UUID targetId = parseId(rawTargetId);
    Record target = targetRecord(tenant, targetId);
    if (target == null) {
      throw notFound();
    }
    String state = target.get("state", String.class);
    if ("REJECTED".equals(state)) {
      throw unprocessable("平台已驳回：内容需要修改后重新发起，重发同一份内容没有意义");
    }
    if (!"FAILED".equals(state)) {
      throw unprocessable("只有失败的目标能重试，当前状态是 " + state);
    }
    UUID jobId = target.get("job_id", UUID.class);
    Record job = jobRecord(tenant, jobId);
    if (job == null) {
      throw notFound();
    }

    sql.execute("savepoint publish_target");
    try {
      UUID operationId = enqueuePlatformOperation(tenant, job, target);
      sql.execute(
          """
          update listing_publish_job_target
          set state = 'SUBMITTED', attempts = attempts + 1, dispatched_at = now(),
              failure_reason = '', completed_at = null, platform_operation_id = ?, updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and id = ? and state = 'FAILED'
          """,
          operationId,
          targetId);
      sql.execute(
          """
          update listing_publish_job_step
          set state = 'RUNNING', started_at = coalesce(started_at, now()), finished_at = null,
              detail = ?, updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and target_id = ? and step_key = 'MEDIA'
          """,
          "重试：媒体清单已随发布请求再次提交",
          targetId);
      sql.execute(
          """
          update listing_publish_job_step
          set state = 'RUNNING', started_at = coalesce(started_at, now()), finished_at = null,
              detail = ?, updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and target_id = ? and step_key = 'SUBMIT'
          """,
          "重试：已再次提交平台审核",
          targetId);
      sql.execute(
          """
          update listing_publish_job_step
          set state = 'RUNNING', started_at = coalesce(started_at, now()), finished_at = null,
              detail = ?, updated_at = now()
          where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            and target_id = ? and step_key = 'RESULT'
          """,
          "重试：等平台回执",
          targetId);
      sql.execute("release savepoint publish_target");
    } catch (RuntimeException e) {
      sql.execute("rollback to savepoint publish_target");
      throw unprocessable("重试失败：" + (e.getMessage() == null ? "未知原因" : e.getMessage()));
    }
    refreshCompletedAt(tenant, jobId);
    return target(targetId.toString());
  }

  // ==================================================================
  // 内部：目标上下文 / 匹配 / 步骤
  // ==================================================================

  /** 一个已解析的目标（店铺 / 站点 / 平台 / 语言 / 类目路径）。 */
  private static final class TargetContext {
    final UUID shopId;
    final UUID marketplaceId;
    final UUID platformId;
    final String locale;
    final String categoryPath;
    UUID contentSourceId;

    TargetContext(
        UUID shopId,
        UUID marketplaceId,
        UUID platformId,
        String locale,
        String categoryPath) {
      this.shopId = shopId;
      this.marketplaceId = marketplaceId;
      this.platformId = platformId;
      this.locale = locale;
      this.categoryPath = categoryPath;
    }

    String key() {
      return shopId + "|" + marketplaceId + "|" + locale;
    }

    String label() {
      return "店铺 " + shopId + " / 站点 " + marketplaceId + " / 语言 " + locale;
    }
  }

  /** 解析一个目标（店铺 + 站点必须是该店铺**已启用**的站点）。 */
  private TargetContext target(UUID tenant, ListingPublishTargetInput input) {
    Record row =
        sql.fetchOne(
            """
            select s.id as shop_id, s.platform_id, m.id as marketplace_id
            from shop s
            join shop_marketplace sm on sm.tenant_id = s.tenant_id and sm.shop_id = s.id
                 and sm.marketplace_id = ? and sm.status = 'ACTIVE'
            join marketplace m on m.id = sm.marketplace_id and m.platform_id = s.platform_id
            where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and s.id = ?
            """,
            input.marketplaceId(),
            input.shopId());
    if (row == null) {
      throw unprocessable(
          "目标店铺 " + input.shopId() + " 没有启用站点 " + input.marketplaceId()
              + "（站点必须先在店铺设置里启用）");
    }
    return new TargetContext(
        row.get("shop_id", UUID.class),
        row.get("marketplace_id", UUID.class),
        row.get("platform_id", UUID.class),
        input.locale().trim(),
        text(input.categoryPath()));
  }

  /** 匹配编辑器入队记录（店铺 + 站点 + 语言三者都对上）。 */
  private UUID matchSubmission(UUID tenant, List<UUID> candidates, TargetContext context) {
    for (UUID candidate : candidates) {
      Object id =
          sql.fetchValue(
              """
              select sub.id
              from listing_editor_submission sub
              where sub.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                and sub.id = ? and sub.shop_id = ? and sub.marketplace_id = ? and sub.locale = ?
                and sub.state in ('QUEUED','DISPATCHED')
              """,
              candidate,
              context.shopId,
              context.marketplaceId,
              context.locale);
      if (id != null) {
        return UUID.fromString(String.valueOf(id));
      }
    }
    return null;
  }

  /**
   * 匹配刊登模板（按作用域就近覆盖）。
   *
   * <p>🔴 优先级：SHOP 作用域 &gt; CATEGORY 作用域；限定站点 &gt; 平台全站点；语言对上 &gt; 对不上。
   */
  private UUID matchTemplate(UUID tenant, List<UUID> candidates, TargetContext context) {
    UUID best = null;
    int bestScore = -1;
    for (UUID candidate : candidates) {
      Record row =
          sql.fetchOne(
              """
              select id, scope, platform_id, marketplace_id, category_path, shop_id, language
              from listing_template
              where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                and id = ? and status = 'ACTIVE'
              """,
              candidate);
      if (row == null) {
        continue;
      }
      if (!context.platformId.equals(row.get("platform_id", UUID.class))) {
        continue;
      }
      UUID marketplaceId = row.get("marketplace_id", UUID.class);
      if (marketplaceId != null && !marketplaceId.equals(context.marketplaceId)) {
        continue;
      }
      String scope = row.get("scope", String.class);
      if ("SHOP".equals(scope)) {
        if (!context.shopId.equals(row.get("shop_id", UUID.class))) {
          continue;
        }
      } else {
        // CATEGORY 作用域：目标必须带类目路径，否则模板无从判断适用性
        if (context.categoryPath.isEmpty()) {
          continue;
        }
        if (!context.categoryPath.equals(text(row.get("category_path", String.class)))) {
          continue;
        }
      }
      int score = 0;
      if ("SHOP".equals(scope)) {
        score += 4;
      }
      if (marketplaceId != null) {
        score += 2;
      }
      if (context.locale.equals(text(row.get("language", String.class)))) {
        score += 1;
      }
      if (score > bestScore) {
        bestScore = score;
        best = row.get("id", UUID.class);
      }
    }
    return best;
  }

  /** 模板来源里被标记为必填、却没取到值的字段（阻断）。 */
  private static String blockingTemplateField(ListingPublishContentSnapshot snapshot) {
    for (ListingPublishContentField field : snapshot.fields()) {
      if (!"RESOLVED".equals(field.status()) && field.note() != null && field.note().contains("必填")) {
        return field.field() + " 取不到值：" + field.note();
      }
    }
    return null;
  }

  /**
   * 用目标平台的字段要求注册表校对内容的长度 / 条数上限。
   *
   * <p>🔴 只校**长度与条数**：必填缺失由编辑器（4.2.1.1 入队前的校验）与模板的必填规则负责，
   * 本模块不重复判定，免得两个口径打架。
   */
  private String limitViolation(TargetContext context, ListingPublishContentSnapshot snapshot) {
    List<Record> rows =
        sql.fetch(
            """
            select distinct on (r.field_key)
                   r.field_key, r.min_length, r.max_length, r.min_items, r.max_items
            from platform_listing_field_requirement r
            where r.platform_id = ? and r.enabled
              and (r.marketplace_id is null or r.marketplace_id = ?)
              and (r.category_path is null or r.category_path = ?)
              and r.field_key in ('TITLE','DESCRIPTION','BULLETS','IMAGES')
            order by r.field_key, (r.marketplace_id is not null) desc, (r.category_path is not null) desc
            """,
            context.platformId,
            context.marketplaceId,
            context.categoryPath);
    for (Record row : rows) {
      String field = row.get("field_key", String.class);
      Integer minLength = row.get("min_length", Integer.class);
      Integer maxLength = row.get("max_length", Integer.class);
      Integer minItems = row.get("min_items", Integer.class);
      Integer maxItems = row.get("max_items", Integer.class);
      switch (field) {
        case "TITLE" -> {
          String value = snapshot.title();
          if (value == null) {
            continue;
          }
          int length = value.codePointCount(0, value.length());
          if (maxLength != null && length > maxLength) {
            return "标题 " + length + " 字，超过该平台上限 " + maxLength + " 字";
          }
          if (minLength != null && length < minLength) {
            return "标题 " + length + " 字，低于该平台下限 " + minLength + " 字";
          }
        }
        case "DESCRIPTION" -> {
          String value = snapshot.description();
          if (value == null) {
            continue;
          }
          int length = value.codePointCount(0, value.length());
          if (maxLength != null && length > maxLength) {
            return "描述 " + length + " 字，超过该平台上限 " + maxLength + " 字";
          }
          if (minLength != null && length < minLength) {
            return "描述 " + length + " 字，低于该平台下限 " + minLength + " 字";
          }
        }
        case "BULLETS" -> {
          List<String> values = snapshot.bullets();
          if (values == null || values.isEmpty()) {
            continue;
          }
          if (maxItems != null && values.size() > maxItems) {
            return "卖点 " + values.size() + " 条，超过该平台上限 " + maxItems + " 条";
          }
          if (minItems != null && values.size() < minItems) {
            return "卖点 " + values.size() + " 条，低于该平台下限 " + minItems + " 条";
          }
        }
        case "IMAGES" -> {
          List<String> values = snapshot.images();
          if (values == null || values.isEmpty()) {
            continue;
          }
          if (maxItems != null && values.size() > maxItems) {
            return "图片 " + values.size() + " 张，超过该平台上限 " + maxItems + " 张";
          }
          if (minItems != null && values.size() < minItems) {
            return "图片 " + values.size() + " 张，低于该平台下限 " + minItems + " 张";
          }
        }
        default -> { }
      }
    }
    return null;
  }

  private void insertStep(
      UUID tenant,
      UUID jobId,
      UUID targetId,
      String stepKey,
      int position,
      String state,
      String detail) {
    sql.execute(
        """
        insert into listing_publish_job_step
          (id,tenant_id,job_id,target_id,step_key,position,state,detail,started_at,finished_at,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,now(),?::timestamptz,now(),now())
        """,
        UUID.randomUUID(),
        tenant,
        jobId,
        targetId,
        stepKey,
        position,
        state,
        detail,
        "SUCCEEDED".equals(state) || "FAILED".equals(state) || "BLOCKED".equals(state)
                || "SKIPPED".equals(state)
            ? OffsetDateTime.now()
            : null);
  }

  private void finishStep(UUID tenant, UUID targetId, String stepKey, String state, String detail) {
    sql.execute(
        """
        update listing_publish_job_step
        set state = ?, detail = ?, finished_at = now(), updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and target_id = ? and step_key = ?
        """,
        state,
        detail,
        targetId,
        stepKey);
  }

  private void failStep(UUID tenant, UUID targetId, String stepKey, String detail) {
    finishStep(tenant, targetId, stepKey, "FAILED", detail);
  }

  /** 目标派发失败：只改**这一行**（其它目标不受影响）。 */
  private void markTargetFailed(UUID tenant, UUID jobId, UUID targetId, String reason) {
    sql.execute(
        """
        update listing_publish_job_target
        set state = 'FAILED', failure_reason = ?, attempts = attempts + 1,
            completed_at = now(), updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and id = ?
        """,
        reason,
        targetId);
    failStep(tenant, targetId, "SUBMIT", "派发失败：" + reason);
    failStep(tenant, targetId, "RESULT", "派发失败：" + reason);
    refreshCompletedAt(tenant, jobId);
  }

  /** 全部目标都有结果时，给作业盖上完成时刻。 */
  private void refreshCompletedAt(UUID tenant, UUID jobId) {
    sql.execute(
        """
        update listing_publish_job j
        set completed_at = now(), updated_at = now()
        where j.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and j.id = ?
          and j.completed_at is null
          and not exists (
            select 1 from listing_publish_job_target t
            where t.tenant_id = j.tenant_id and t.job_id = j.id
              and t.state in ('DRAFT','SUBMITTED')
          )
        """,
        jobId);
  }

  private void updateOperation(
      UUID tenant, Record target, String status, ListingPublishTargetReportRequest request) {
    Object operationId = target.get("platform_operation_id", UUID.class);
    if (operationId == null) {
      return;
    }
    sql.execute(
        """
        update platform_operation
        set status = ?, last_error = ?, platform_request_id = coalesce(nullif(?,''), platform_request_id),
            updated_at = now()
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and id = ?
        """,
        status,
        "SUCCEEDED".equals(status) ? "" : text(request.failureReason()),
        text(request.platformRequestId()),
        operationId);
  }

  // ==================================================================
  // 内部：读
  // ==================================================================

  private Record jobRecord(UUID tenant, UUID jobId) {
    return sql.fetchOne(
        """
        select j.*, s.code as primary_shop_code
        from listing_publish_job j
        join shop s on s.tenant_id = j.tenant_id and s.id = j.primary_shop_id
        where j.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and j.id = ?
        """,
        jobId);
  }

  private Record jobRecordByKey(UUID tenant, String key) {
    return sql.fetchOne(
        """
        select j.*, s.code as primary_shop_code
        from listing_publish_job j
        join shop s on s.tenant_id = j.tenant_id and s.id = j.primary_shop_id
        where j.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and j.idempotency_key = ?
        """,
        key);
  }

  private Record targetRecord(UUID tenant, UUID targetId) {
    return sql.fetchOne(
        """
        select t.*, p.code as platform_code, s.code as shop_code, m.code as marketplace_code
        from listing_publish_job_target t
        join platform p on p.id = t.platform_id
        join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
        join marketplace m on m.id = t.marketplace_id
        where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and t.id = ?
        """,
        targetId);
  }

  /** 🔴 进度与总体结论：从目标行**聚合**出来，不落库（落库就会和目标行漂）。 */
  private ListingPublishProgress progress(UUID tenant, UUID jobId) {
    Record row =
        sql.fetchOne(
            """
            select count(*) as total,
                   count(*) filter (where state = 'DRAFT') as draft,
                   count(*) filter (where state = 'SUBMITTED') as in_review,
                   count(*) filter (where state = 'SUCCEEDED') as succeeded,
                   count(*) filter (where state = 'REJECTED') as rejected,
                   count(*) filter (where state = 'FAILED') as failed
            from listing_publish_job_target
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and job_id = ?
            """,
            jobId);
    int total = row == null ? 0 : intOf(row, "total");
    int draft = row == null ? 0 : intOf(row, "draft");
    int inReview = row == null ? 0 : intOf(row, "in_review");
    int succeeded = row == null ? 0 : intOf(row, "succeeded");
    int rejected = row == null ? 0 : intOf(row, "rejected");
    int failed = row == null ? 0 : intOf(row, "failed");
    int settled = succeeded + rejected + failed;
    int percent = total == 0 ? 0 : (int) Math.round((settled * 100.0) / total);
    String outcome;
    if (total == 0 || settled == 0) {
      outcome = "NOT_DISPATCHED";
    } else if (succeeded == total) {
      outcome = "COMPLETED";
    } else if (settled == total) {
      outcome = succeeded == 0 ? "FAILED" : "PARTIAL";
    } else {
      outcome = "NOT_DISPATCHED";
    }
    return new ListingPublishProgress(
        total, draft, inReview, succeeded, rejected, failed, percent, outcome);
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private ListingPublishJobRow toJob(Record row) {
    UUID jobId = row.get("id", UUID.class);
    return new ListingPublishJobRow(
        jobId,
        row.get("source_sku_id", UUID.class),
        row.get("content_source", String.class),
        readStringList(row.get("content_source_ids")),
        row.get("price_source", String.class),
        row.get("price_amount", BigDecimal.class),
        row.get("price_currency", String.class),
        row.get("price_state", String.class),
        row.get("price_state_reason", String.class),
        row.get("stock_source", String.class),
        row.get("stock_quantity", BigDecimal.class),
        row.get("stock_state", String.class),
        row.get("stock_state_reason", String.class),
        row.get("state", String.class),
        row.get("primary_shop_id", UUID.class),
        row.get("idempotency_key", String.class),
        row.get("note", String.class),
        progress(currentTenant(), jobId),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        row.get("dispatched_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  private ListingPublishTargetRow toTarget(Record row) {
    return new ListingPublishTargetRow(
        row.get("id", UUID.class),
        row.get("job_id", UUID.class),
        row.get("position", Integer.class) == null ? 0 : row.get("position", Integer.class),
        row.get("platform_id", UUID.class),
        row.get("platform_code", String.class),
        row.get("shop_id", UUID.class),
        row.get("shop_code", String.class),
        row.get("marketplace_id", UUID.class),
        row.get("marketplace_code", String.class),
        row.get("locale", String.class),
        row.get("category_path", String.class),
        row.get("state", String.class),
        row.get("content_source", String.class),
        row.get("content_source_id", UUID.class),
        readSnapshot(row.get("content_snapshot")),
        row.get("external_item_id", String.class),
        row.get("platform_request_id", String.class),
        row.get("failure_reason", String.class),
        row.get("attempts", Integer.class) == null ? 0 : row.get("attempts", Integer.class),
        row.get("platform_operation_id", UUID.class),
        row.get("dispatched_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 内部：jsonb 读写
  // ==================================================================

  private Object readJson(Object raw) {
    try {
      if (raw == null) {
        return null;
      }
      String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
      return json.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  private ListingPublishContentSnapshot readSnapshot(Object raw) {
    try {
      if (raw == null) {
        return null;
      }
      String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
      return json.readValue(value, ListingPublishContentSnapshot.class);
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> readStringList(Object raw) {
    List<String> out = new ArrayList<>();
    Object node = readJson(raw);
    if (node instanceof com.fasterxml.jackson.databind.JsonNode array
        && array.isArray()) {
      for (com.fasterxml.jackson.databind.JsonNode item : array) {
        out.add(item.asText());
      }
    }
    return out;
  }

  private String titleOf(Object raw) {
    Object node = readJson(raw);
    if (node instanceof com.fasterxml.jackson.databind.JsonNode object) {
      var title = object.get("TITLE");
      return title == null || title.isNull() ? "" : title.asText();
    }
    return "";
  }

  private int imageCountOf(Object raw) {
    Object node = readJson(raw);
    if (node instanceof com.fasterxml.jackson.databind.JsonNode object) {
      var images = object.get("IMAGES");
      return images != null && images.isArray() ? images.size() : 0;
    }
    return 0;
  }

  /** 能力矩阵：{@code platform.capabilities} jsonb → 键 → 布尔。**键缺失 = 未声明**（不是 false）。 */
  private Map<String, Boolean> capabilityMap(Object raw) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    Object node = readJson(raw);
    if (!(node instanceof com.fasterxml.jackson.databind.JsonNode object) || !object.isObject()) {
      return result;
    }
    object.fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isBoolean()) {
                result.put(entry.getKey(), entry.getValue().asBoolean());
              }
            });
    return result;
  }

  private String scopeTarget(Record row) {
    if ("SHOP".equals(row.get("scope", String.class))) {
      return "店铺 " + row.get("shop_id", UUID.class);
    }
    return "类目路径 " + text(row.get("category_path", String.class));
  }
}
