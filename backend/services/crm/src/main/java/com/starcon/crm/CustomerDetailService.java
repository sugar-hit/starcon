package com.starcon.crm;

import static com.starcon.crm.CustomerDetailModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerDetailFactsPort.CasePage;
import com.starcon.crm.CustomerDetailFactsPort.CaseRow;
import com.starcon.crm.CustomerDetailFactsPort.OrderPage;
import com.starcon.crm.CustomerDetailFactsPort.OrderRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * 15.3.1.2 客户详情。
 *
 * <p>蓝图 15 卷 15.3.1.2 原话：
 *
 * <blockquote>
 * 职责：客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、标签、接触记录、风险与价值分层。
 * 输入：{@code customer_id}；聚合各域数据。
 * 输出：详情视图 + 时间线（订单/消息/售后合并时间线）。
 * 关系：上游 = 15.3.1.1；下游 = 9 订单、11 售后、15.1 消息。
 * <b>统一时间线是客服理解客户的唯一高效方式</b> —— 跨系统翻找会显著拖慢响应。
 * </blockquote>
 *
 * <p><b>本类落六条纪律</b>：
 *
 * <ol>
 *   <li><b>时间线是物化的合并投影</b>：五个来源落到同一张时间线
 *       （{@code crm_customer_timeline_entry}），排序键统一为
 *       {@code occurred_at DESC, indexed_at DESC, id DESC} —— 后两个键都由
 *       <b>DB 时钟</b>写 ⇒ 同一时钟域，可比；不拿 JVM 墙钟和 DB 时钟比大小。</li>
 *   <li><b>「跨店铺身份关系」按未合并的形态实现</b>：合并映射由 15.3.1.3 拥有，
 *       本模块<b>不建</b>合并表、<b>不读</b>合并表，只给<b>线索</b>
 *       （同名 / 同联系方式指纹）；线索<b>不是</b>合并结论。</li>
 *   <li><b>跨服务只经授权 HTTP API</b>：订单与售后在 trade 服务
 *       （{@link CustomerDetailFactsPort}）；本服务<b>一条跨库 SQL 都没有</b>。</li>
 *   <li><b>读不到 ≠ 没有数据</b>：上游读不到 ⇒ 该来源标 {@code available=false}
 *       并写明原因与归属模块，<b>绝不</b>编一条时间线条目。</li>
 *   <li><b>幂等重建</b>：唯一键 (租户, 客户, 来源类别, 来源标识)；
 *       重建前先删掉该来源的旧条目再落新的（上游撤掉的单子不该留在时间线上）。</li>
 *   <li><b>联系方式默认脱敏</b>：沿用 15.3.1.1 的口径，明文要额外的
 *       {@code crm.customers.pii.read} 权限<b>并</b>显式要求。</li>
 * </ol>
 *
 * <p><b>同服务只读的表</b>：客户主档与金额（15.3.1.1 / V432）、标签（15.3.2.1 / V295）、
 * 消息（15.1.1.1 / V293）、评价（15.2.1.1 / V294）、接触记录与退订（15.3.2.2 / V299）。
 * 本模块<b>一个字都不写</b>它们的表。
 */
@Service
public class CustomerDetailService {

  // ================================================================ 常量

  /** 详情页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 时间线默认每页条数。 */
  static final int DEFAULT_TIMELINE_PAGE_SIZE = 50;

  /**
   * 身份线索最多列多少条。
   *
   * <p>同名是**弱线索**（「张伟」能匹配上一堆人），列太多会把详情页变成名单。
   */
  static final int MAX_IDENTITY_CANDIDATES = 20;

  /**
   * 售后归因扫描窗口：订单时刻**之前**放宽的天数。
   *
   * <p>放宽是必要的：订单时刻与售后受理时刻不在同一天、且上游用不同的时区偏移，
   * 卡死窗口会把「下了一单、第二天申请退货」这种最常见的组合漏掉。
   */
  static final int AFTERSALES_LOOKBACK_DAYS = 7;

  /**
   * 售后归因扫描窗口：最后一张订单之后**继续放宽**的天数。
   *
   * <p>🔴 这一段是**退货期**，不是缓冲：买家下单后**几周**才申请退货是常态
   * （跨境物流更久）。如果窗口只按「订单时刻 ± 1 天」算，
   * 「这一单后来退了」这条事实**根本不会出现在时间线上** ——
   * 而售后恰恰是客服最需要看到的那一段。
   *
   * <p>窗口越宽 ⇒ 一次扫描要翻的页越多 ⇒ 触到 {@code MAX_PAGES} 时如实报
   * {@code truncated}（时间线不是全量），而不是悄悄少算。
   */
  static final int AFTERSALES_RETURN_WINDOW_DAYS = 90;

  /** 客户没有首末订单时刻时的兜底扫描窗口（天）。 */
  static final int FALLBACK_WINDOW_DAYS = 365;

  private final DSLContext sql;
  private final CustomerDetailFactsPort facts;

  public CustomerDetailService(DSLContext sql, CustomerDetailFactsPort facts) {
    this.sql = sql;
    this.facts = facts;
  }

  /** 判定依据快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          // 🔴 读回来也必须是 BigDecimal：默认会把 JSON 数字读成 double，
          // 那一行金额就会掉精度（禁浮点纪律的另一半）。
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 详情视图

  /**
   * 客户详情视图。
   *
   * @param revealContact 调用方是否显式要求明文联系方式（还要**另有**
   *     {@code crm.customers.pii.read} 权限才给；没有就是 403，不是悄悄退回脱敏）
   */
  public CrmCustomerDetailView detail(
      UUID customerId, boolean revealContact, AccessService.Access actor) {
    Record profile = profileOf(customerId);
    if (profile == null) {
      throw notFound("客户不存在或不属于当前租户：" + customerId);
    }
    if (revealContact && !actor.permissions().contains("crm.customers.pii.read")) {
      throw forbidden(
          "查看明文联系方式需要额外的权限码 crm.customers.pii.read："
              + "能看客户列表 ≠ 能看手机号。");
    }
    String customerRef = nz(profile.get("customer_ref", String.class));
    List<CrmCustomerDetailAmount> amounts = amountsOf(customerId);
    List<CrmCustomerDetailTag> tags = tagsOf(customerRef);
    CrmCustomerDetailIdentity identity = identityOf(profile);
    CrmCustomerDetailTimelinePage timeline =
        timelinePage(customerId, null, 0, DEFAULT_TIMELINE_PAGE_SIZE, actor);
    return new CrmCustomerDetailView(
        profileOfModel(profile, revealContact),
        amounts,
        tags,
        identity,
        sourceStates(customerId, timeline),
        timeline,
        dataScopeOf(actor),
        "数据范围取调用方最宽松的那一档（ALL / SELF / DEPARTMENT）；"
            + "本模块的租户隔离由 crm 侧 FORCE RLS + trade 侧 RLS **两道**强制。",
        "本客户**按来源店铺**标识，**未跨店合并** —— 合并能力由 15.3.1.3 负责；"
            + "本模块只给**线索**（同名 / 同联系方式指纹），线索不是合并结论。");
  }

  // ================================================================ 时间线

  /**
   * 统一时间线（真分页：LIMIT/OFFSET + 稳定全序）。
   *
   * @param sourceKind 只看某一个来源（空 = 全部）
   */
  public CrmCustomerDetailTimelinePage timeline(
      UUID customerId, String sourceKind, int page, int size, AccessService.Access actor) {
    if (profileOf(customerId) == null) {
      throw notFound("客户不存在或不属于当前租户：" + customerId);
    }
    return timelinePage(customerId, sourceKind, page, size, actor);
  }

  private CrmCustomerDetailTimelinePage timelinePage(
      UUID customerId, String sourceKind, int page, int size, AccessService.Access actor) {
    int safePage = Math.max(page, 0);
    int safeSize = size <= 0 ? DEFAULT_TIMELINE_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    CustomerTimelineSourceKind kind =
        sourceKind == null || sourceKind.isBlank()
            ? null
            : CustomerTimelineSourceKind.fromName(sourceKind);
    if (sourceKind != null && !sourceKind.isBlank() && kind == null) {
      throw bad("未知的时间线来源：" + sourceKind);
    }
    String where =
        " where tenant_id = ? and customer_id = ?" + (kind == null ? "" : " and source_kind = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(customerId);
    if (kind != null) binds.add(kind.name());

    long total =
        sql.fetchOne("select count(*) from crm_customer_timeline_entry" + where, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select id, source_kind, source_ref, source_module, occurred_at, title, summary,"
                + " status, category, amount, currency, quantity, payload"
                + " from crm_customer_timeline_entry"
                + where
                + " order by occurred_at desc, indexed_at desc, id desc"
                + " limit ? offset ?",
            pageBinds.toArray());

    List<CrmCustomerDetailTimelineEntry> entries = new ArrayList<>();
    for (Record r : records) {
      entries.add(
          new CrmCustomerDetailTimelineEntry(
              r.get("id", UUID.class),
              nz(r.get("source_kind", String.class)),
              sourceLabel(nz(r.get("source_kind", String.class))),
              nz(r.get("source_ref", String.class)),
              nz(r.get("source_module", String.class)),
              str(r.get("occurred_at", OffsetDateTime.class)),
              nz(r.get("title", String.class)),
              nz(r.get("summary", String.class)),
              nz(r.get("status", String.class)),
              nz(r.get("category", String.class)),
              r.get("amount", BigDecimal.class),
              r.get("currency", String.class),
              r.get("quantity", BigDecimal.class),
              payloadOf(r.get("payload", String.class))));
    }
    CrmCustomerDetailTimelineRun lastRun = lastRunOf(customerId);
    boolean needsBuild = lastRun == null;
    int totalPages = safeSize == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
    return new CrmCustomerDetailTimelinePage(
        entries,
        safePage,
        safeSize,
        total,
        totalPages,
        kind == null ? "" : kind.name(),
        needsBuild,
        actor.permissions().contains("crm.customers.update"),
        lastRun);
  }

  // ================================================================ 重建

  /**
   * 重建这位客户的统一时间线（幂等）。
   *
   * <p>🔴 <b>「读不到」与「没有数据」分开</b>：某个来源读不到时，
   * 该来源标为 {@code available=false} 并写明原因，<b>其余来源照常落库</b>，
   * 整次运行的状态是 {@code PARTIAL}。
   * 五个来源全部读不到才是 {@code FAILED}。
   *
   * <p>🔴 <b>不编数据</b>：读不到的来源**一条条目都不落**。
   * 「这位客户没有订单」与「订单读不到」是两件事，混起来会让客服误判。
   */
  public CrmCustomerDetailTimelineRun rebuild(
      UUID customerId, AccessService.Access actor, String bearer) {
    Record profile = profileOf(customerId);
    if (profile == null) {
      throw notFound("客户不存在或不属于当前租户：" + customerId);
    }
    OffsetDateTime startedAt = dbNow();
    String customerRef = nz(profile.get("customer_ref", String.class));
    UUID partyId = profile.get("customer_party_id", UUID.class);

    Map<String, String> unavailable = new LinkedHashMap<>();
    List<TimelineDraft> drafts = new ArrayList<>();
    Set<CustomerTimelineSourceKind> refreshed = new LinkedHashSet<>();
    boolean truncated = false;

    // ---------------------------------------------------------- 订单（9 订单 · trade）
    List<OrderRow> orders = List.of();
    if (partyId == null) {
      unavailable.put(
          CustomerTimelineSourceKind.ORDER.name(),
          "客户主档没有上游标识（customer_party_id 为空），无法按客户读取订单事实");
    } else {
      try {
        OrderScan scan = scanOrders(partyId, bearer);
        orders = scan.orders();
        truncated |= scan.truncated();
        refreshed.add(CustomerTimelineSourceKind.ORDER);
      } catch (ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
            || e.getStatusCode() == HttpStatus.FORBIDDEN) {
          throw e;
        }
        unavailable.put(
            CustomerTimelineSourceKind.ORDER.name(), reasonOf(e, "上游订单事实读不到"));
      }
    }
    for (OrderRow order : orders) {
      if (order.id() == null) continue;
      OffsetDateTime occurred =
          order.submittedAt() != null ? order.submittedAt() : order.createdAt();
      drafts.add(
          new TimelineDraft(
              CustomerTimelineSourceKind.ORDER,
              order.id().toString(),
              "9",
              occurred,
              "订单 " + nz(order.orderNo()),
              orderSummary(order),
              nz(order.documentStatus()),
              nz(order.orderSource()),
              order.grandTotalAmount(),
              order.currency(),
              order.lineCount() == null ? null : BigDecimal.valueOf(order.lineCount()),
              Map.of(
                  "orderNo", nz(order.orderNo()),
                  "orderSource", nz(order.orderSource()),
                  "documentStatus", nz(order.documentStatus()),
                  "platformStatus", nz(order.platformStatus()),
                  "paymentStatus", nz(order.paymentStatus()),
                  "fulfillmentStatus", nz(order.fulfillmentStatus()),
                  "buyerName", nz(order.buyerName()))));
    }

    // ---------------------------------------------------------- 售后（11 售后 · trade）
    Set<UUID> orderIds = new LinkedHashSet<>();
    for (OrderRow order : orders) {
      if (order.id() != null) orderIds.add(order.id());
    }
    if (!orderIds.isEmpty()) {
      try {
        CaseScan scan = scanCases(orders, profile, bearer);
        truncated |= scan.truncated();
        refreshed.add(CustomerTimelineSourceKind.AFTERSALES);
        for (CaseRow c : scan.cases()) {
          if (c.id() == null) continue;
          drafts.add(
              new TimelineDraft(
                  CustomerTimelineSourceKind.AFTERSALES,
                  c.id().toString(),
                  "11",
                  c.receivedAt(),
                  "售后单 " + nz(c.caseNo()),
                  caseSummary(c),
                  nz(c.status()),
                  nz(c.caseType()),
                  c.requestedAmount(),
                  c.currency(),
                  c.requestedQuantity(),
                  Map.of(
                      "caseNo", nz(c.caseNo()),
                      "orderNo", nz(c.orderNo()),
                      "caseType", nz(c.caseType()),
                      "caseTypeLabel", nz(c.caseTypeLabel()),
                      "status", nz(c.status()),
                      "statusLabel", nz(c.statusLabel()),
                      "reasonCode", nz(c.reasonCode()))));
        }
      } catch (ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
            || e.getStatusCode() == HttpStatus.FORBIDDEN) {
          throw e;
        }
        unavailable.put(
            CustomerTimelineSourceKind.AFTERSALES.name(), reasonOf(e, "上游售后事实读不到"));
      }
    } else {
      unavailable.put(
          CustomerTimelineSourceKind.AFTERSALES.name(),
          "这位客户没有可归因的订单（售后单只带订单维度，没有订单就归不到人）");
    }

    // ---------------------------------------------------------- 消息（15.1 消息 · 本服务）
    drafts.addAll(messageDrafts(customerRef));
    refreshed.add(CustomerTimelineSourceKind.MESSAGE);

    // ---------------------------------------------------------- 评价（15.2 评价 · 本服务）
    drafts.addAll(reviewDrafts(customerRef));
    refreshed.add(CustomerTimelineSourceKind.REVIEW);

    // ---------------------------------------------------------- 接触记录（15.3.2.2 · 本服务）
    drafts.addAll(contactDrafts(customerRef));
    refreshed.add(CustomerTimelineSourceKind.CONTACT);

    // ---------------------------------------------------------- 落库
    OffsetDateTime indexedAt = dbNow();
    for (CustomerTimelineSourceKind kind : refreshed) {
      sql.execute(
          "delete from crm_customer_timeline_entry"
              + " where tenant_id = ? and customer_id = ? and source_kind = ?",
          currentTenant(),
          customerId,
          kind.name());
    }
    for (TimelineDraft draft : drafts) {
      sql.execute(
          "insert into crm_customer_timeline_entry"
              + " (id,tenant_id,customer_id,source_kind,source_ref,source_module,occurred_at,"
              + "  indexed_at,title,summary,status,category,amount,currency,quantity,payload)"
              + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          currentTenant(),
          customerId,
          draft.kind().name(),
          draft.sourceRef(),
          draft.sourceModule(),
          draft.occurredAt() == null ? indexedAt : draft.occurredAt(),
          indexedAt,
          draft.title(),
          draft.summary(),
          draft.status(),
          draft.category(),
          draft.amount(),
          draft.currency(),
          draft.quantity(),
          json(draft.payload()));
    }

    // ---------------------------------------------------------- 运行记录
    int orderCount = countKind(drafts, CustomerTimelineSourceKind.ORDER);
    int aftersalesCount = countKind(drafts, CustomerTimelineSourceKind.AFTERSALES);
    int messageCount = countKind(drafts, CustomerTimelineSourceKind.MESSAGE);
    int reviewCount = countKind(drafts, CustomerTimelineSourceKind.REVIEW);
    int contactCount = countKind(drafts, CustomerTimelineSourceKind.CONTACT);
    int entryCount = drafts.size();

    String status;
    String failureReason = "";
    if (unavailable.isEmpty()) {
      status = "SUCCEEDED";
    } else if (entryCount == 0) {
      status = "FAILED";
      failureReason = String.join("；", unavailable.values());
    } else {
      status = "PARTIAL";
    }
    // 🔴 运行结果必须自洽（DB 触发器也会再校验一道）：
    //   FAILED ⇒ entry_count 必须为 0；SUCCEEDED / PARTIAL ⇒ 至少有一条。
    if ("FAILED".equals(status) && entryCount > 0) {
      status = "PARTIAL";
      failureReason = "";
    }
    if (!"FAILED".equals(status) && entryCount == 0) {
      status = "FAILED";
      failureReason =
          unavailable.isEmpty()
              ? "五个来源都没有取到任何记录"
              : String.join("；", unavailable.values());
    }
    if ("FAILED".equals(status) && failureReason.isBlank()) {
      failureReason = "五个来源都没有取到任何记录";
    }

    String runNo = nextRunNo();
    Map<String, Object> factsSnapshot = new LinkedHashMap<>();
    factsSnapshot.put("customerRef", customerRef);
    factsSnapshot.put("sourceShop", nz(profile.get("source_shop", String.class)));
    factsSnapshot.put("rebuiltAt", indexedAt.toString());
    factsSnapshot.put("truncated", truncated);

    UUID runId = UUID.randomUUID();
    sql.execute(
        "insert into crm_customer_timeline_run"
            + " (id,tenant_id,customer_id,run_no,status,entry_count,order_count,"
            + "  aftersales_count,message_count,review_count,contact_count,unavailable_sources,"
            + "  truncated,failure_reason,facts,operator_id,started_at,finished_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        customerId,
        runNo,
        status,
        entryCount,
        orderCount,
        aftersalesCount,
        messageCount,
        reviewCount,
        contactCount,
        json(unavailable),
        truncated,
        failureReason,
        json(factsSnapshot),
        actor.userId(),
        startedAt,
        dbNow());

    return new CrmCustomerDetailTimelineRun(
        runId,
        runNo,
        status,
        entryCount,
        orderCount,
        aftersalesCount,
        messageCount,
        reviewCount,
        contactCount,
        unavailable,
        truncated,
        failureReason,
        startedAt.toString(),
        dbNow().toString());
  }

  // ================================================================ 上游扫描

  private record OrderScan(List<OrderRow> orders, boolean truncated) {}

  private record CaseScan(List<CaseRow> cases, boolean truncated) {}

  /** 翻页拉这位客户的订单（最多 {@code MAX_PAGES} 页，触顶如实报 truncated）。 */
  private OrderScan scanOrders(UUID partyId, String bearer) {
    List<OrderRow> all = new ArrayList<>();
    boolean truncated = false;
    for (int page = 0; page < CustomerDetailFactsPort.MAX_PAGES; page++) {
      OrderPage result = facts.ordersByCustomer(partyId, page, bearer);
      if (result.rows() != null) all.addAll(result.rows());
      long total = result.total();
      if (all.size() >= total) return new OrderScan(all, false);
      if (result.rows() == null || result.rows().isEmpty()) return new OrderScan(all, false);
      if (page == CustomerDetailFactsPort.MAX_PAGES - 1) truncated = true;
    }
    return new OrderScan(all, truncated);
  }

  /**
   * 扫售后：售后单**不带买家维度**（只有 orderId）⇒ 归因链路是
   * 「售后单 → orderId → 订单 → customerPartyId → 客户」。
   *
   * <p>做法：按客户首末订单时刻（并放宽窗口）整段拉售后单，再按该客户的订单 id 过滤。
   * 本库**不建任何指向 trade 的外键**。
   */
  private CaseScan scanCases(List<OrderRow> orders, Record profile, String bearer) {
    Set<UUID> orderIds = new LinkedHashSet<>();
    for (OrderRow order : orders) {
      if (order.id() != null) orderIds.add(order.id());
    }
    OffsetDateTime lo = profile.get("first_order_at", OffsetDateTime.class);
    OffsetDateTime hi = profile.get("last_order_at", OffsetDateTime.class);
    for (OrderRow order : orders) {
      OffsetDateTime at = order.submittedAt() != null ? order.submittedAt() : order.createdAt();
      if (at == null) continue;
      if (lo == null || at.isBefore(lo)) lo = at;
      if (hi == null || at.isAfter(hi)) hi = at;
    }
    OffsetDateTime now = dbNow();
    if (lo == null) lo = now.minusDays(FALLBACK_WINDOW_DAYS);
    if (hi == null) hi = now;
    lo = lo.minusDays(AFTERSALES_LOOKBACK_DAYS);
    hi = hi.plusDays(AFTERSALES_RETURN_WINDOW_DAYS);

    List<CaseRow> mine = new ArrayList<>();
    boolean truncated = false;
    for (int page = 0; page < CustomerDetailFactsPort.MAX_PAGES; page++) {
      CasePage result = facts.casesInWindow(lo, hi, page, bearer);
      if (result.rows() != null) {
        for (CaseRow c : result.rows()) {
          if (c.id() != null && c.orderId() != null && orderIds.contains(c.orderId())) {
            mine.add(c);
          }
        }
      }
      if (result.rows() == null || result.rows().isEmpty()) return new CaseScan(mine, false);
      if (page == CustomerDetailFactsPort.MAX_PAGES - 1) truncated = true;
    }
    return new CaseScan(mine, truncated);
  }

  // ================================================================ 同服务来源（只读）

  private List<TimelineDraft> messageDrafts(String customerRef) {
    List<TimelineDraft> drafts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, message_no, shop_name, platform_code, subject, direction, message_type,"
                + " read_status, priority, platform_time"
                + " from crm_message"
                + " where tenant_id = ? and buyer_reference = ?"
                + " order by platform_time desc, id desc limit 500",
            currentTenant(),
            customerRef)) {
      drafts.add(
          new TimelineDraft(
              CustomerTimelineSourceKind.MESSAGE,
              r.get("id", UUID.class).toString(),
              "15.1",
              r.get("platform_time", OffsetDateTime.class),
              "消息 " + nz(r.get("message_no", String.class)),
              nz(r.get("shop_name", String.class)) + " · " + nz(r.get("subject", String.class)),
              nz(r.get("read_status", String.class)),
              nz(r.get("message_type", String.class)),
              null,
              null,
              null,
              Map.of(
                  "messageNo", nz(r.get("message_no", String.class)),
                  "shopName", nz(r.get("shop_name", String.class)),
                  "platformCode", nz(r.get("platform_code", String.class)),
                  "subject", nz(r.get("subject", String.class)),
                  "direction", nz(r.get("direction", String.class)),
                  "messageType", nz(r.get("message_type", String.class)),
                  "priority", nz(r.get("priority", String.class)),
                  "readStatus", nz(r.get("read_status", String.class)))));
    }
    return drafts;
  }

  private List<TimelineDraft> reviewDrafts(String customerRef) {
    List<TimelineDraft> drafts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, review_no, shop_name, platform_code, rating, title, content,"
                + " product_title, replied, platform_reviewed_at"
                + " from crm_product_review"
                + " where tenant_id = ? and buyer_ref = ?"
                + " order by platform_reviewed_at desc, id desc limit 500",
            currentTenant(),
            customerRef)) {
      Integer rating = r.get("rating", Integer.class);
      drafts.add(
          new TimelineDraft(
              CustomerTimelineSourceKind.REVIEW,
              r.get("id", UUID.class).toString(),
              "15.2",
              r.get("platform_reviewed_at", OffsetDateTime.class),
              "评价 " + nz(r.get("review_no", String.class)),
              (rating == null ? "" : rating + " 星 · ") + nz(r.get("title", String.class)),
              r.get("replied", Boolean.class) == Boolean.TRUE ? "REPLIED" : "UNREPLIED",
              nz(r.get("platform_code", String.class)),
              null,
              null,
              null,
              Map.of(
                  "reviewNo", nz(r.get("review_no", String.class)),
                  "shopName", nz(r.get("shop_name", String.class)),
                  "platformCode", nz(r.get("platform_code", String.class)),
                  "rating", rating == null ? 0 : rating,
                  "title", nz(r.get("title", String.class)),
                  "content", nz(r.get("content", String.class)),
                  "productTitle", nz(r.get("product_title", String.class)),
                  "replied", r.get("replied", Boolean.class) == Boolean.TRUE)));
    }
    return drafts;
  }

  private List<TimelineDraft> contactDrafts(String customerRef) {
    List<TimelineDraft> drafts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, contact_no, contact_type, direction, origin, subject, summary,"
                + " occurred_at, source_module, source_label, shop_name, order_reference"
                + " from crm_customer_contact"
                + " where tenant_id = ? and customer_ref = ?"
                + " order by occurred_at desc, id desc limit 500",
            currentTenant(),
            customerRef)) {
      drafts.add(
          new TimelineDraft(
              CustomerTimelineSourceKind.CONTACT,
              r.get("id", UUID.class).toString(),
              "15.3.2.2",
              r.get("occurred_at", OffsetDateTime.class),
              "接触 " + nz(r.get("contact_no", String.class)),
              nz(r.get("subject", String.class)),
              nz(r.get("contact_type", String.class)),
              nz(r.get("direction", String.class)),
              null,
              null,
              null,
              Map.of(
                  "contactNo", nz(r.get("contact_no", String.class)),
                  "contactType", nz(r.get("contact_type", String.class)),
                  "direction", nz(r.get("direction", String.class)),
                  "origin", nz(r.get("origin", String.class)),
                  "subject", nz(r.get("subject", String.class)),
                  "summary", nz(r.get("summary", String.class)),
                  "sourceModule", nz(r.get("source_module", String.class)),
                  "sourceLabel", nz(r.get("source_label", String.class)),
                  "shopName", nz(r.get("shop_name", String.class)),
                  "orderReference", nz(r.get("order_reference", String.class)))));
    }
    return drafts;
  }

  // ================================================================ 详情各段

  private Record profileOf(UUID customerId) {
    return sql.fetchOne(
        "select p.id,p.tenant_id,p.customer_ref,p.source_shop,p.customer_name,p.customer_party_id,"
            + " p.first_order_at,p.last_order_at,p.order_count,p.refund_case_count,p.refund_rate,"
            + " p.value_tier,p.risk_flag,p.risk_reason,p.contact_email,p.contact_phone,"
            + " p.contact_email_masked,p.contact_phone_masked,p.contact_source,p.opt_out_state,"
            + " p.window_from,p.window_to,p.synced_at,p.created_at,p.updated_at"
            + " from crm_customer_profile p"
            + " where p.tenant_id = ? and p.id = ?",
        currentTenant(),
        customerId);
  }

  private CrmCustomerDetailProfile profileOfModel(Record p, boolean revealContact) {
    boolean masked = !revealContact;
    String tier = nz(p.get("value_tier", String.class));
    String risk = nz(p.get("risk_flag", String.class));
    String contactSource = nz(p.get("contact_source", String.class));
    String optOut = nz(p.get("opt_out_state", String.class));
    UUID partyId = p.get("customer_party_id", UUID.class);
    return new CrmCustomerDetailProfile(
        p.get("id", UUID.class),
        nz(p.get("customer_ref", String.class)),
        nz(p.get("source_shop", String.class)),
        nz(p.get("customer_name", String.class)),
        partyId == null ? null : partyId.toString(),
        str(p.get("first_order_at", OffsetDateTime.class)),
        str(p.get("last_order_at", OffsetDateTime.class)),
        intOf(p.get("order_count", Integer.class)),
        intOf(p.get("refund_case_count", Integer.class)),
        p.get("refund_rate", BigDecimal.class),
        tier,
        valueTierLabel(tier),
        risk,
        riskFlagLabel(risk),
        nz(p.get("risk_reason", String.class)),
        masked
            ? nz(p.get("contact_email_masked", String.class))
            : nz(p.get("contact_email", String.class)),
        masked
            ? nz(p.get("contact_phone_masked", String.class))
            : nz(p.get("contact_phone", String.class)),
        masked,
        contactSource,
        contactSourceLabel(contactSource),
        optOut,
        optOutStateLabel(optOut),
        str(p.get("window_from", OffsetDateTime.class)),
        str(p.get("window_to", OffsetDateTime.class)),
        str(p.get("synced_at", OffsetDateTime.class)));
  }

  private List<CrmCustomerDetailAmount> amountsOf(UUID customerId) {
    List<CrmCustomerDetailAmount> amounts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select currency, total_amount, order_count, avg_order_amount"
                + " from crm_customer_amount where tenant_id = ? and customer_id = ?"
                + " order by currency",
            currentTenant(),
            customerId)) {
      amounts.add(
          new CrmCustomerDetailAmount(
              nz(r.get("currency", String.class)),
              r.get("total_amount", BigDecimal.class),
              intOf(r.get("order_count", Integer.class)),
              r.get("avg_order_amount", BigDecimal.class)));
    }
    return amounts;
  }

  private List<CrmCustomerDetailTag> tagsOf(String customerRef) {
    List<CrmCustomerDetailTag> tags = new ArrayList<>();
    if (customerRef.isEmpty()) return tags;
    for (Record r :
        sql.fetch(
            "select t.tag_code, t.name, t.category"
                + " from crm_customer_tag_assignment a"
                + " join crm_customer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
                + " where a.tenant_id = ? and a.state = 'ACTIVE' and a.customer_ref = ?"
                + " order by t.tag_code",
            currentTenant(),
            customerRef)) {
      String category = nz(r.get("category", String.class));
      tags.add(
          new CrmCustomerDetailTag(
              nz(r.get("tag_code", String.class)),
              nz(r.get("name", String.class)),
              category,
              tagCategoryLabel(category)));
    }
    return tags;
  }

  /**
   * 跨店铺身份关系段 —— 🔴 <b>按「合并映射尚未交付」的形态实现</b>。
   *
   * <p>合并映射（逻辑客户 / 合并映射表）由 <b>15.3.1.3 跨店合并</b>拥有，
   * 本模块**不建、不读、不写**那些表。因此这里：
   *
   * <ol>
   *   <li>合并状态恒为 {@code NOT_MERGED}，并写明「合并能力由 15.3.1.3 负责」；</li>
   *   <li>只列**线索**：同租户下另一行客户主档，联系方式**脱敏指纹**或买家名与本客户一致。</li>
   * </ol>
   *
   * <p>🔴 <b>线索不是合并结论</b>：同名可能是两个人，掩码邮箱也可能是同一个人。
   * 本模块**不判定**，判定与落库是 15.3.1.3 的事。
   */
  private CrmCustomerDetailIdentity identityOf(Record p) {
    UUID customerId = p.get("id", UUID.class);
    String maskedEmail = nz(p.get("contact_email_masked", String.class));
    String maskedPhone = nz(p.get("contact_phone_masked", String.class));
    String customerName = nz(p.get("customer_name", String.class));
    String sourceShop = nz(p.get("source_shop", String.class));

    List<CrmCustomerDetailIdentityCandidate> candidates = new ArrayList<>();
    if (!maskedEmail.isEmpty() || !maskedPhone.isEmpty() || !customerName.isEmpty()) {
      for (Record r :
          sql.fetch(
              "select id, customer_ref, source_shop, customer_name, contact_email_masked,"
                  + " contact_phone_masked"
                  + " from crm_customer_profile"
                  + " where tenant_id = ? and id <> ?"
                  + " and ((? <> '' and contact_email_masked = ?)"
                  + "   or (? <> '' and contact_phone_masked = ?)"
                  + "   or (? <> '' and lower(customer_name) = lower(?)))"
                  + " order by source_shop, customer_ref limit "
                  + MAX_IDENTITY_CANDIDATES,
              currentTenant(),
              customerId,
              maskedEmail,
              maskedEmail,
              maskedPhone,
              maskedPhone,
              customerName,
              customerName)) {
        String otherEmail = nz(r.get("contact_email_masked", String.class));
        String otherPhone = nz(r.get("contact_phone_masked", String.class));
        String otherName = nz(r.get("customer_name", String.class));
        List<String> clues = new ArrayList<>();
        if (!maskedEmail.isEmpty() && maskedEmail.equals(otherEmail)) clues.add("联系方式指纹一致");
        if (!maskedPhone.isEmpty() && maskedPhone.equals(otherPhone)) clues.add("手机号指纹一致");
        if (!customerName.isEmpty() && customerName.equalsIgnoreCase(otherName)) clues.add("买家名一致");
        if (clues.isEmpty()) continue;
        String maskedContact =
            !otherEmail.isEmpty() ? otherEmail : (!otherPhone.isEmpty() ? otherPhone : "");
        candidates.add(
            new CrmCustomerDetailIdentityCandidate(
                r.get("id", UUID.class),
                nz(r.get("customer_ref", String.class)),
                nz(r.get("source_shop", String.class)),
                otherName,
                String.join("、", clues),
                maskedContact,
                sourceShop.equals(nz(r.get("source_shop", String.class)))));
      }
    }
    return new CrmCustomerDetailIdentity(
        CustomerMergeState.NOT_MERGED.name(),
        CustomerMergeState.NOT_MERGED.label(),
        "15.3.1.3",
        "本客户**按来源店铺**标识，**未跨店合并**。逻辑客户与合并映射由 15.3.1.3 跨店合并拥有；"
            + "本模块只列**线索**（同名 / 同联系方式指纹），**线索不是合并结论**，也不自动做任何合并。",
        candidates);
  }

  /**
   * 逐段的接入状态。
   *
   * <p>🔴 每一段都要能回答三件事：<b>有没有、缺什么、归谁</b>。
   */
  private List<CrmCustomerDetailSourceState> sourceStates(
      UUID customerId, CrmCustomerDetailTimelinePage timeline) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select source_kind, count(*) as n from crm_customer_timeline_entry"
                + " where tenant_id = ? and customer_id = ? group by source_kind",
            currentTenant(),
            customerId)) {
      counts.put(nz(r.get("source_kind", String.class)), r.get("n", Long.class));
    }
    Map<String, String> unavailable =
        timeline.lastRun() == null
            ? Map.of()
            : (timeline.lastRun().unavailableSources() == null
                ? Map.of()
                : timeline.lastRun().unavailableSources());
    boolean built = timeline.lastRun() != null;

    List<CrmCustomerDetailSourceState> states = new ArrayList<>();
    for (CustomerTimelineSourceKind kind : CustomerTimelineSourceKind.values()) {
      long count = counts.getOrDefault(kind.name(), 0L);
      String reason;
      boolean available;
      if (!built) {
        available = false;
        reason = "尚未建立时间线（需要 crm.customers.update 权限建立）";
      } else {
        String unavailableReason = unavailable.get(kind.name());
        available = unavailableReason == null;
        reason = available ? "" : unavailableReason;
      }
      states.add(
          new CrmCustomerDetailSourceState(
              kind.name(), kind.label(), available, reason, kind.ownerModule(), count));
    }
    return states;
  }

  private CrmCustomerDetailTimelineRun lastRunOf(UUID customerId) {
    Record r =
        sql.fetchOne(
            "select id, run_no, status, entry_count, order_count, aftersales_count,"
                + " message_count, review_count, contact_count, unavailable_sources, truncated,"
                + " failure_reason, started_at, finished_at"
                + " from crm_customer_timeline_run"
                + " where tenant_id = ? and customer_id = ?"
                + " order by created_at desc, id desc limit 1",
            currentTenant(),
            customerId);
    if (r == null) return null;
    return new CrmCustomerDetailTimelineRun(
        r.get("id", UUID.class),
        nz(r.get("run_no", String.class)),
        nz(r.get("status", String.class)),
        intOf(r.get("entry_count", Integer.class)),
        intOf(r.get("order_count", Integer.class)),
        intOf(r.get("aftersales_count", Integer.class)),
        intOf(r.get("message_count", Integer.class)),
        intOf(r.get("review_count", Integer.class)),
        intOf(r.get("contact_count", Integer.class)),
        unavailableOf(r.get("unavailable_sources", String.class)),
        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
        nz(r.get("failure_reason", String.class)),
        str(r.get("started_at", OffsetDateTime.class)),
        str(r.get("finished_at", OffsetDateTime.class)));
  }

  /** 数据范围：取调用方最宽松的那一档（与其它服务的口径一致）。 */
  private static String dataScopeOf(AccessService.Access actor) {
    String scope = "ALL";
    if (actor.grants() != null) {
      for (AccessService.Grant grant : actor.grants()) {
        if ("ALL".equals(grant.scopeMode())) return "ALL";
        if ("SELF".equals(grant.scopeMode())) scope = "SELF";
        else if ("DEPARTMENT".equals(grant.scopeMode()) && "ALL".equals(scope)) scope = "DEPARTMENT";
      }
    }
    return scope;
  }

  /** JSON 序列化（BigDecimal 按原样写出，不转浮点）。 */
  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("时间线快照序列化失败", e);
    }
  }

  private Map<String, String> unavailableOf(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private Map<String, Object> payloadOf(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  // ================================================================ 草稿与工具

  /** 一条待落库的时间线条目（落库前先算好，避免边写边读）。 */
  private record TimelineDraft(
      CustomerTimelineSourceKind kind,
      String sourceRef,
      String sourceModule,
      OffsetDateTime occurredAt,
      String title,
      String summary,
      String status,
      String category,
      BigDecimal amount,
      String currency,
      BigDecimal quantity,
      Map<String, Object> payload) {}

  private static int countKind(List<TimelineDraft> drafts, CustomerTimelineSourceKind kind) {
      int n = 0;
      for (TimelineDraft d : drafts) {
        if (d.kind() == kind) n++;
      }
      return n;
    }

  private static String orderSummary(OrderRow order) {
    StringBuilder sb = new StringBuilder();
    sb.append(nz(order.orderSource()));
    if (!nz(order.documentStatus()).isEmpty()) {
      sb.append(" · ").append(order.documentStatus());
    }
    if (order.grandTotalAmount() != null) {
      sb.append(" · ").append(order.grandTotalAmount().toPlainString());
      if (!nz(order.currency()).isEmpty()) sb.append(" ").append(order.currency());
    }
    if (!nz(order.buyerName()).isEmpty()) sb.append(" · ").append(order.buyerName());
    return sb.toString();
  }

  private static String caseSummary(CaseRow c) {
    StringBuilder sb = new StringBuilder();
    sb.append(nz(orderNoOf(c)));
    if (!nz(c.caseTypeLabel()).isEmpty()) sb.append(" · ").append(c.caseTypeLabel());
    else if (!nz(c.caseType()).isEmpty()) sb.append(" · ").append(c.caseType());
    if (!nz(c.statusLabel()).isEmpty()) sb.append(" · ").append(c.statusLabel());
    else if (!nz(c.status()).isEmpty()) sb.append(" · ").append(c.status());
    if (c.requestedAmount() != null) {
      sb.append(" · ").append(c.requestedAmount().toPlainString());
      if (!nz(c.currency()).isEmpty()) sb.append(" ").append(c.currency());
    }
    return sb.toString();
  }

  private static String orderNoOf(CaseRow c) {
    return nz(c.orderNo());
  }

  /** 运行号：TL-yyyyMMdd-000001（序列在迁移里单独授权）。 */
  private String nextRunNo() {
    Long no = sql.fetchOne("select nextval('crm_customer_timeline_run_no_seq')").get(0, Long.class);
    return "TL-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
        + "-"
        + String.format("%06d", no == null ? 0 : no);
  }

  /**
   * 🔴 <b>DB 时钟</b>：统一时钟域用它，不拿 JVM 墙钟和 DB 时钟比大小。
   */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  // ================================================================ 文案与工具

  private static String sourceLabel(String kind) {
    CustomerTimelineSourceKind k = CustomerTimelineSourceKind.fromName(kind);
    return k == null ? kind : k.label();
  }

  private static String valueTierLabel(String value) {
    return switch (nz(value)) {
      case "VIP" -> "高价值";
      case "HIGH" -> "较高价值";
      case "NORMAL" -> "常规";
      case "LOW" -> "无有效消费";
      default -> nz(value);
    };
  }

  private static String riskFlagLabel(String value) {
    return switch (nz(value)) {
      case "NONE" -> "无风险标记";
      case "HIGH_RETURN_RATE" -> "退款率显著高于租户基线";
      case "OPT_OUT" -> "已登记生效中的退订";
      case "HIGH_RETURN_RATE_AND_OPT_OUT" -> "退款率偏高且已退订";
      case "INSUFFICIENT_SAMPLE" -> "样本不足，不可判定";
      default -> nz(value);
    };
  }

  private static String contactSourceLabel(String value) {
    return switch (nz(value)) {
      case "NONE" -> "无联系方式";
      case "CATALOG_PARTY" -> "catalog 客户档案带回来的";
      case "MANUAL" -> "人工登记";
      default -> nz(value);
    };
  }

  private static String optOutStateLabel(String value) {
    return switch (nz(value)) {
      case "NONE" -> "无退订记录";
      case "ACTIVE" -> "退订生效中";
      case "REVOKED" -> "退订已撤销";
      default -> nz(value);
    };
  }

  private static String tagCategoryLabel(String value) {
    return switch (nz(value)) {
      case "BEHAVIOR" -> "行为";
      case "VALUE" -> "价值";
      case "LIFECYCLE" -> "生命周期";
      case "LOYALTY" -> "忠诚度";
      case "RISK" -> "风险";
      case "PREFERENCE" -> "偏好";
      case "CUSTOM" -> "自定义";
      default -> nz(value);
    };
  }

  private static String reasonOf(ResponseStatusException e, String fallback) {
    String reason = e.getReason();
    return reason == null || reason.isBlank() ? fallback : reason;
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private static int intOf(Integer value) {
    return value == null ? 0 : value;
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException forbidden(String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }
}
