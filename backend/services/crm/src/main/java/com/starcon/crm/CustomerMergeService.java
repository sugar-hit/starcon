package com.starcon.crm;

import static com.starcon.crm.CustomerMergeModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.3.1.3 跨店合并。
 *
 * <p>蓝图 15 卷 15.3.1.3 原话：
 *
 * <blockquote>
 * 职责：把同一自然人在不同店铺的身份合并到一个逻辑客户，支持按邮箱/电话/姓名+地址/平台买家标识匹配，给出置信度并需人工确认，保留合并前映射可拆分。
 * 输入：候选匹配（自动建议）；人工确认；拆分请求。
 * 输出：合并后的逻辑客户 + 映射关系 + 操作记录。
 * 关系：上游 = 15.3.1.1；下游 = 15.3.1.2、13 分析（客户价值跨店汇总）。<b>自动合并风险高</b>（同名不同人、同人不同邮箱），因此只给建议不自动执行，且必须可拆分。
 * </blockquote>
 *
 * <p><b>本类落六条纪律</b>：
 *
 * <ol>
 *   <li><b>只给建议，不自动合并</b>：{@link #scan} 只会写 {@code crm_customer_merge_candidate}，
 *       <b>一行映射都不会写</b>。逻辑客户与 ACTIVE 映射只能由 {@link #confirm}（人工确认）写出。
 *       DB 层另有触发器兜底（绕过服务层直接写 SQL 同样被挡）。</li>
 *   <li><b>必须可拆分</b>：{@link #split} 把映射从 ACTIVE 改成 SPLIT，<b>不删行</b>。
 *       来源行 {@code crm_customer_profile} <b>一个字都没被改过</b> ——
 *       这正是「拆开后能还原回原来的来源行」得以成立的前提。</li>
 *   <li><b>置信度要能解释</b>：不是一个神秘分数。四级信号逐条列出命中了哪个、
 *       证据强度多少、比对的是什么（<b>脱敏形态</b>）。</li>
 *   <li><b>同名不同人不产出候选</b>：只有姓名相同、地址对不上（或地址取不到）
 *       ⇒ <b>不</b>进候选队列，只在运行台账里计数。</li>
 *   <li><b>金额按币种分行</b>：逻辑客户的累计消费一币种一行，跨币种<b>不相加</b>。</li>
 *   <li><b>租户不来自客户端头</b>：见 {@link AccessService}；本模块的每张表都带
 *       {@code tenant_id NOT NULL} + FORCE RLS。</li>
 * </ol>
 */
@Service
public class CustomerMergeService {

  /** 单页最大条数（与 15.3.1.1 同口径）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一轮扫描最多看多少位来源客户（服务端钳住，防呆）。 */
  static final int MAX_SCAN_CUSTOMERS = 2000;

  /** 一轮扫描的默认名单规模。 */
  static final int DEFAULT_SCAN_CUSTOMERS = 300;

  /**
   * 🔴 置信度下限：**低于**它就不产出候选（只进运行台账的计数）。
   *
   * <p>四级信号的基准权重里最弱的是「姓名 + 地址」0.6000 —— 它<b>正好</b>压在线上：
   * 姓名 + 地址全中 ⇒ 达到下限 ⇒ 进队列；只有姓名、地址对不上 ⇒ 无信号 ⇒ 只计数。
   */
  static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.6000");

  /** 每多命中一个旁证加多少（照抄蓝图「给出置信度」的直觉：旁证越多越可信）。 */
  static final BigDecimal CORROBORATION_BONUS = new BigDecimal("0.05");

  /** 置信度上限（留 0.01 的余量：满分 1.0000 会被读成「机器判定百分之百正确」）。 */
  static final BigDecimal CONFIDENCE_CEILING = new BigDecimal("0.9900");

  /**
   * 🔴 「同一个信号命中的人太多」时的上限。
   *
   * <p>公司总机号、家庭号被几十个人共用是常态 —— 那种桶里的两两组合**不是候选**，
   * 是噪声。全量两两组合还会把一轮扫描拖成平方级。
   * 超限的桶**整桶跳过**并如实记进运行台账的 {@code facts}（不静默丢弃）。
   */
  static final int BROAD_SIGNAL_LIMIT = 50;

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final DSLContext sql;
  private final CustomerMergePartyPort parties;

  public CustomerMergeService(DSLContext sql, CustomerMergePartyPort parties) {
    this.sql = sql;
    this.parties = parties;
  }

  // ================================================================ 内部形状

  /** 一位来源客户（15.3.1.1 的 {@code crm_customer_profile} 行）+ 本轮解析出的地址。 */
  private record SourceCustomer(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      UUID partyId,
      String email,
      String phone,
      String emailMasked,
      String phoneMasked,
      String optOutState,
      int orderCount,
      OffsetDateTime firstOrderAt,
      OffsetDateTime lastOrderAt,
      List<String> addresses,
      boolean addressAvailable,
      String addressReason) {}

  /** 一条命中信号的**判定结果**（还没落库前）。 */
  private record SignalHit(
      CustomerMergeMatchType type, BigDecimal strength, String maskedValue, String detail) {}

  /** 一对客户行的比对结果。 */
  private record PairVerdict(
      SourceCustomer left,
      SourceCustomer right,
      List<SignalHit> hits,
      BigDecimal confidence,
      List<String> exclusionFlags,
      boolean optOutInvolved) {

    /** 最强命中信号（强度降序；同强度按枚举顺序 ⇒ 结果稳定）。 */
    SignalHit strongest() {
      return hits.get(0);
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面明确写清两件事：
   * 「只给建议不自动合并」与「必须可拆分」——
   * 这是蓝图里加粗的两句，界面上必须让操作的人看见，否则他们会以为
   * 点一下扫描就等于合并了。
   */
  public CrmCustomerMergeCapabilities capabilities() {
    return new CrmCustomerMergeCapabilities(
        List.of(
            new CrmCustomerMergeCapabilityDimension(
                "候选匹配",
                "按平台买家标识 / 邮箱 / 电话 / 姓名+地址四种依据给出候选与置信度",
                true,
                "本服务自算（信号比对在本服务内完成）",
                "15.3.1.3",
                "四种依据全部可用；只有姓名相同不产出候选（同名不同人）。"),
            new CrmCustomerMergeCapabilityDimension(
                "地址比对",
                "「姓名 + 地址」这一档需要地址；地址在 catalog 客户档案里",
                true,
                "catalog 服务 /catalog/parties/{id}/addresses（授权 HTTP API 只读）",
                "catalog（3.6.1.1 往来单位档案）",
                "地址读不到时「姓名+地址」这一档不产出候选，运行台账如实报出取不到几个。"),
            new CrmCustomerMergeCapabilityDimension(
                "人工确认",
                "候选必须经人工确认才合并；确认与拒绝都要写理由",
                true,
                "本服务自算",
                "15.3.1.3",
                "拒绝也是决定：事后要能回答「为什么不是同一个人」。"),
            new CrmCustomerMergeCapabilityDimension(
                "拆分",
                "合并后的逻辑客户可以把成员拆出去，映射行不删、留痕",
                true,
                "本服务自算",
                "15.3.1.3",
                "拆开后来源行原样还在（合并不改来源行）。"),
            new CrmCustomerMergeCapabilityDimension(
                "客户价值跨店汇总",
                "按逻辑客户汇总跨店订单数与累计消费",
                true,
                "本服务自算（金额一币种一行）",
                "15.3.1.3",
                "金额**按币种分行**：USD 与 EUR 加起来不是任何一个金额。"),
            new CrmCustomerMergeCapabilityDimension(
                "自动合并",
                "系统**不会**自动执行合并",
                false,
                "—",
                "—",
                "蓝图加粗句「自动合并风险高（同名不同人、同人不同邮箱）」⇒ 只给建议不自动执行。"
                    + "DB 层有触发器兜底：候选表的映射行只能由人工确认动作写出。")),
        List.of("PLATFORM_BUYER_ID", "EMAIL", "PHONE", "NAME_ADDRESS"),
        "信号分级：平台买家标识 0.95 > 邮箱 0.85 > 电话 0.75 > 姓名+地址 0.60；"
            + "置信度 = 最强命中信号 + 0.05 × 旁证个数，上限 0.99；"
            + "低于 0.60 不产出候选（只有姓名相同属于这一档）。",
        "🔴 只给建议不自动执行：生成候选**不会**写出任何逻辑客户或映射；"
            + "合并只能由人工确认端点触发，确认必须写理由。",
        "🔴 必须可拆分：拆分把映射从 ACTIVE 改成 SPLIT 并留痕（时刻 + 理由），"
            + "映射行不删；已拆分的映射不允许直接改回 ACTIVE（重新合并要新写一行）。",
        "🔴 联系方式默认脱敏：证据里存的邮箱 / 电话只有脱敏形态（a***@***.com / 138****1234）；"
            + "明文要**另外**的权限码（crm.customers.pii.read）并显式要求，否则 403。",
        "边界：本模块**一个字都不写** 15.3.1.1 的 crm_customer_profile —— "
            + "合并只在自己的映射表里把来源行挂到同一个逻辑客户下；"
            + "也不读写 15.3.1.2 的 crm_customer_detail_* 系列表。",
        "「同名不同人」和「同人不同邮箱」是两种不同的漏判，前者靠「只有姓名不产出候选」挡住，"
            + "后者靠人工确认挡住 —— 系统**不替人判断**。");
  }

  // ================================================================ 扫描（只给建议）

  /**
   * 生成候选匹配（<b>只给建议，绝不自动合并</b>）。
   *
   * <p>本方法<b>只写</b> {@code crm_customer_merge_candidate} 与 {@code crm_customer_merge_run} 两张表。
   * 逻辑客户与映射表（{@code crm_customer_merge_logical} / {@code crm_customer_merge_mapping}）
   * <b>一行都不会被写</b> —— 那是人工确认之后才发生的事。
   */
  @Transactional
  public CrmCustomerMergeScanResult scan(
      CrmCustomerMergeScanInput input, UUID operatorId, String bearer) {

    int limit =
        Math.min(
            Math.max(
                input == null || input.maxCustomers() == null
                    ? DEFAULT_SCAN_CUSTOMERS
                    : input.maxCustomers(),
                1),
            MAX_SCAN_CUSTOMERS);
    boolean force = input != null && Boolean.TRUE.equals(input.force());

    OffsetDateTime startedAt = now();
    String runNo = nextRunNo();
    UUID runId = UUID.randomUUID();

    // ---- 1. 取名单 -------------------------------------------------------
    // 多取一条用来判断「名单是不是被截断了」—— 如实标出来，不假装扫全了。
    List<SourceCustomer> profiles = loadSourceCustomers(limit + 1);
    boolean truncated = profiles.size() > limit;
    if (truncated) profiles = profiles.subList(0, limit);

    // ---- 2. 解析地址（catalog，只读）--------------------------------------
    Map<UUID, CustomerMergePartyPort.AddressLookup> addressCache = new HashMap<>();
    int addressResolved = 0;
    int addressUnavailable = 0;
    int partyUnavailable = 0;
    List<SourceCustomer> resolved = new ArrayList<>(profiles.size());
    for (SourceCustomer p : profiles) {
      CustomerMergePartyPort.AddressLookup lookup;
      if (p.partyId == null) {
        lookup =
            new CustomerMergePartyPort.AddressLookup(
                false, List.of(), "上游订单没有带客户档案标识", "NONE");
      } else {
        lookup =
            addressCache.computeIfAbsent(p.partyId, id -> parties.addresses(id, bearer));
      }
      if (!lookup.available()) {
        partyUnavailable++;
        addressUnavailable++;
      } else if (lookup.addresses().isEmpty()) {
        addressUnavailable++;
      } else {
        addressResolved++;
      }
      resolved.add(
          new SourceCustomer(
              p.id(),
              p.customerRef(),
              p.sourceShop(),
              p.customerName(),
              p.partyId(),
              p.email(),
              p.phone(),
              p.emailMasked(),
              p.phoneMasked(),
              p.optOutState(),
              p.orderCount(),
              p.firstOrderAt(),
              p.lastOrderAt(),
              lookup.addresses(),
              lookup.available(),
              lookup.unavailableReason()));
    }

    // ---- 3. 已经合并过的来源行不再提建议 ----------------------------------
    Set<UUID> mergedCustomers = activeMappedCustomerIds();

    // ---- 4. 按信号分桶 ---------------------------------------------------
    BucketReport buckets = bucket(resolved);

    // ---- 5. 逐对判定 -----------------------------------------------------
    int sameShop = 0;
    int alreadyMerged = 0;
    int belowThreshold = 0;
    int optOutCandidates = 0;
    int produced = 0;
    int refreshed = 0;
    int skippedReviewed = 0;
    Map<String, Integer> belowReasons = new TreeMap<>();

    for (PairVerdict verdict : buckets.pairs()) {
      SourceCustomer left = verdict.left();
      SourceCustomer right = verdict.right();
      if (Objects.equals(left.sourceShop(), right.sourceShop())) {
        sameShop++;
        continue;
      }
      if (mergedCustomers.contains(left.id()) || mergedCustomers.contains(right.id())) {
        alreadyMerged++;
        continue;
      }
      if (verdict.hits().isEmpty()) {
        // 🔴 「只有姓名相同」⇒ 同名不同人那一档 ⇒ **不产出候选**，只计数。
        belowThreshold++;
        String reason =
            left.addressAvailable() && right.addressAvailable()
                ? "NAME_ONLY_ADDRESS_DIFFERENT"
                : "NAME_ONLY_ADDRESS_UNAVAILABLE";
        belowReasons.merge(reason, 1, Integer::sum);
        continue;
      }
      BigDecimal confidence = verdict.confidence();
      if (confidence.compareTo(CONFIDENCE_THRESHOLD) < 0) {
        belowThreshold++;
        belowReasons.merge("BELOW_THRESHOLD", 1, Integer::sum);
        continue;
      }
      if (verdict.optOutInvolved()) optOutCandidates++;

      WriteOutcome outcome = writeCandidate(runId, verdict, confidence, force);
      if (outcome == WriteOutcome.INSERTED) produced++;
      else if (outcome == WriteOutcome.REFRESHED) refreshed++;
      else skippedReviewed++;
    }

    // ---- 6. 落运行台账 ---------------------------------------------------
    OffsetDateTime finishedAt = now();
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("scannedSourceShops", distinctShops(resolved));
    facts.put("belowThresholdByReason", belowReasons);
    facts.put("broadSignalBuckets", buckets.broadBuckets());
    facts.put("refreshedCandidateCount", refreshed);
    facts.put("skippedReviewedCandidateCount", skippedReviewed);
    facts.put("signalPolicy", "平台买家标识 0.95 > 邮箱 0.85 > 电话 0.75 > 姓名+地址 0.60");

    sql.execute(
        "insert into crm_customer_merge_run"
            + " (id,tenant_id,run_no,status,scanned_customer_count,candidate_count,"
            + " below_threshold_count,excluded_already_merged_count,excluded_same_shop_count,"
            + " opt_out_candidate_count,address_resolved_count,address_unavailable_count,"
            + " party_unavailable_count,truncated,failure_reason,facts,operator_id,started_at,finished_at)"
            + " values (?,?,?,'SUCCEEDED',?,?,?,?,?,?,?,?,?,?,'',?::jsonb,?,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        runNo,
        resolved.size(),
        produced,
        belowThreshold,
        alreadyMerged,
        sameShop,
        optOutCandidates,
        addressResolved,
        addressUnavailable,
        partyUnavailable,
        truncated,
        toJson(facts),
        operatorId,
        startedAt,
        finishedAt);

    return new CrmCustomerMergeScanResult(
        runId,
        runNo,
        "SUCCEEDED",
        resolved.size(),
        produced,
        belowThreshold,
        alreadyMerged,
        sameShop,
        optOutCandidates,
        addressResolved,
        addressUnavailable,
        partyUnavailable,
        truncated,
        "",
        startedAt.toString(),
        finishedAt.toString(),
        "🔴 本轮**只产出建议**，没有合并任何客户："
            + "逻辑客户与映射表一行都没写。"
            + (truncated
                ? "名单被截断了（只看前 " + resolved.size() + " 位来源客户），不是全量。"
                : "本轮名单是全量。")
            + "只有姓名相同、地址对不上的 "
            + belowThreshold
            + " 对没有进候选队列。");
  }

  private enum WriteOutcome {
    INSERTED,
    REFRESHED,
    SKIPPED_REVIEWED
  }

  /**
   * 写一条候选。
   *
   * <p>🔴 <b>不自动合并</b>：这里写的是候选表，<b>不写</b>映射表。
   * 候选的 {@code status} 恒为 {@code 'SUGGESTED'} —— 变成 {@code 'CONFIRMED'}
   * 只能由 {@link #confirm}（人工确认）写入，而那需要 {@code reviewed_by} + {@code reviewed_at}。
   */
  private WriteOutcome writeCandidate(
      UUID runId, PairVerdict verdict, BigDecimal confidence, boolean force) {
    SourceCustomer left = verdict.left();
    SourceCustomer right = verdict.right();

    Record existing =
        sql.fetchOne(
            "select id, status from crm_customer_merge_candidate"
                + " where tenant_id = ? and left_customer_id = ? and right_customer_id = ?",
            currentTenant(),
            left.id(),
            right.id());
    if (existing != null) {
      String status = existing.get("status", String.class);
      if (!"SUGGESTED".equals(status)) {
        // 🔴 复核结论是终态：CONFIRMED / REJECTED / STALE 的候选**不重复提出**。
        return WriteOutcome.SKIPPED_REVIEWED;
      }
      if (!force) {
        // 默认不动已存在的 SUGGESTED 候选：反复点「生成候选」不该把运营的队列搅乱。
        return WriteOutcome.SKIPPED_REVIEWED;
      }
      sql.execute(
          "update crm_customer_merge_candidate"
              + " set match_type = ?, confidence = ?, signal_summary = ?, evidence = ?::jsonb,"
              + " exclusion_flags = ?::jsonb, opt_out_involved = ?, detected_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          verdict.strongest().type().name(),
          confidence,
          signalSummary(verdict),
          toJson(evidenceOf(verdict)),
          toJson(verdict.exclusionFlags()),
          verdict.optOutInvolved(),
          now(),
          now(),
          currentTenant(),
          existing.get("id", UUID.class));
      return WriteOutcome.REFRESHED;
    }

    sql.execute(
        "insert into crm_customer_merge_candidate"
            + " (id,tenant_id,left_customer_id,right_customer_id,left_source_shop,left_customer_ref,"
            + " left_customer_name,right_source_shop,right_customer_ref,right_customer_name,match_type,"
            + " confidence,signal_summary,evidence,exclusion_flags,opt_out_involved,status,detected_at,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,'SUGGESTED',?::timestamptz,"
            + " now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        left.id(),
        right.id(),
        left.sourceShop(),
        left.customerRef(),
        left.customerName(),
        right.sourceShop(),
        right.customerRef(),
        right.customerName(),
        verdict.strongest().type().name(),
        confidence,
        signalSummary(verdict),
        toJson(evidenceOf(verdict)),
        toJson(verdict.exclusionFlags()),
        verdict.optOutInvolved(),
        now());
    return WriteOutcome.INSERTED;
  }

  // ================================================================ 分桶与比对

  /** 分桶结果。 */
  private record BucketReport(List<PairVerdict> pairs, List<Map<String, Object>> broadBuckets) {}

  /**
   * 按四种信号分桶，再逐对判定。
   *
   * <p>🔴 为什么先分桶而不是两两比：租户里有几千位来源客户，两两比是平方级的；
   * 分桶后只有「共享了某个信号」的客户对才会进入判定，代价与真实候选量成正比。
   */
  private BucketReport bucket(List<SourceCustomer> customers) {
    Map<String, List<SourceCustomer>> byPlatform = new LinkedHashMap<>();
    Map<String, List<SourceCustomer>> byEmail = new LinkedHashMap<>();
    Map<String, List<SourceCustomer>> byPhone = new LinkedHashMap<>();
    Map<String, List<SourceCustomer>> byName = new LinkedHashMap<>();
    Map<String, List<SourceCustomer>> byNameAddress = new LinkedHashMap<>();

    for (SourceCustomer c : customers) {
      if (c.partyId() != null) {
        byPlatform.computeIfAbsent(c.partyId().toString(), k -> new ArrayList<>()).add(c);
      }
      String email = normalizeEmail(c.email());
      if (!email.isEmpty()) {
        byEmail.computeIfAbsent(email, k -> new ArrayList<>()).add(c);
      }
      String phone = normalizePhone(c.phone());
      if (!phone.isEmpty()) {
        byPhone.computeIfAbsent(phone, k -> new ArrayList<>()).add(c);
      }
      String name = normalizeName(c.customerName());
      if (!name.isEmpty()) {
        byName.computeIfAbsent(name, k -> new ArrayList<>()).add(c);
        if (!c.addresses().isEmpty()) {
          for (String address : c.addresses()) {
            String normalized = normalizeAddress(address);
            if (normalized.isEmpty()) continue;
            byNameAddress.computeIfAbsent(name + "|" + normalized, k -> new ArrayList<>()).add(c);
          }
        }
      }
    }

    List<Map<String, Object>> broad = new ArrayList<>();
    Map<String, PairVerdict> pairs = new LinkedHashMap<>();

    collect(byPlatform, "PLATFORM_BUYER_ID", broad, pairs, customers);
    collect(byEmail, "EMAIL", broad, pairs, customers);
    collect(byPhone, "PHONE", broad, pairs, customers);
    collect(byNameAddress, "NAME_ADDRESS", broad, pairs, customers);
    // 「只有姓名相同」的桶也要进来：它决定「同名不同人」那一档被**排除**掉多少。
    collect(byName, "NAME_ONLY", broad, pairs, customers);

    return new BucketReport(List.copyOf(pairs.values()), broad);
  }

  /** 把一个桶里的两两组合并进候选对表（超宽的桶整桶跳过并如实记数）。 */
  private void collect(
      Map<String, List<SourceCustomer>> buckets,
      String signalKey,
      List<Map<String, Object>> broad,
      Map<String, PairVerdict> pairs,
      List<SourceCustomer> all) {
    for (Map.Entry<String, List<SourceCustomer>> entry : buckets.entrySet()) {
      List<SourceCustomer> members = entry.getValue();
      if (members.size() < 2) continue;
      if (members.size() > BROAD_SIGNAL_LIMIT) {
        // 🔴 公司总机 / 家庭号被几十个人共用 —— 那一桶里的两两组合是噪声，不是候选。
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("signalType", signalKey);
        record.put("memberCount", members.size());
        record.put("reason", "同一信号命中的人过多（疑似共用联系方式），整桶不产出候选");
        broad.add(record);
        continue;
      }
      for (int i = 0; i < members.size(); i++) {
        for (int j = i + 1; j < members.size(); j++) {
          SourceCustomer a = members.get(i);
          SourceCustomer b = members.get(j);
          SourceCustomer left = a.id().compareTo(b.id()) <= 0 ? a : b;
          SourceCustomer right = a.id().compareTo(b.id()) <= 0 ? b : a;
          String key = left.id() + "|" + right.id();
          if (!pairs.containsKey(key)) {
            pairs.put(key, judge(left, right));
          }
        }
      }
    }
  }

  /** 判定一对客户行：命中了哪些信号、置信度多少、有什么排除信号。 */
  private PairVerdict judge(SourceCustomer left, SourceCustomer right) {
    List<SignalHit> hits = new ArrayList<>();

    // 平台买家标识（最强）：同一个 catalog 往来单位 ⇒ 同一个自然人。
    if (left.partyId() != null && left.partyId().equals(right.partyId())) {
      hits.add(
          new SignalHit(
              CustomerMergeMatchType.PLATFORM_BUYER_ID,
              CustomerMergeMatchType.PLATFORM_BUYER_ID.weight(),
              null,
              "两个来源店铺指向同一个 catalog 客户档案"));
    }

    // 邮箱（次强）。
    String email = normalizeEmail(left.email());
    if (!email.isEmpty() && email.equals(normalizeEmail(right.email()))) {
      hits.add(
          new SignalHit(
              CustomerMergeMatchType.EMAIL,
              CustomerMergeMatchType.EMAIL.weight(),
              nz(left.emailMasked()),
              "两侧邮箱归一化后完全相同"));
    }

    // 电话（再次）：家庭号 / 公司号会被多个人共用，所以它**不能**单独定案。
    String phone = normalizePhone(left.phone());
    if (!phone.isEmpty() && phone.equals(normalizePhone(right.phone()))) {
      hits.add(
          new SignalHit(
              CustomerMergeMatchType.PHONE,
              CustomerMergeMatchType.PHONE.weight(),
              nz(left.phoneMasked()),
              "两侧电话归一化后完全相同（电话可能被多人共用，证据强度低于邮箱）"));
    }

    // 姓名 + 地址（最弱）：**两个条件都要满足**。
    String name = normalizeName(left.customerName());
    if (!name.isEmpty() && name.equals(normalizeName(right.customerName()))) {
      String shared = sharedAddress(left, right);
      if (shared != null) {
        hits.add(
            new SignalHit(
                CustomerMergeMatchType.NAME_ADDRESS,
                CustomerMergeMatchType.NAME_ADDRESS.weight(),
                shared,
                "两侧姓名相同，且地址一致"));
      }
    }

    hits.sort(
        Comparator.comparing((SignalHit h) -> h.type().weight()).reversed().thenComparing(h -> h.type().name()));

    BigDecimal confidence = BigDecimal.ZERO;
    if (!hits.isEmpty()) {
      confidence =
          hits.get(0).type().weight().add(CORROBORATION_BONUS.multiply(BigDecimal.valueOf(hits.size() - 1)));
      if (confidence.compareTo(CONFIDENCE_CEILING) > 0) confidence = CONFIDENCE_CEILING;
      confidence = confidence.setScale(4, RoundingMode.HALF_UP);
    }

    List<String> flags = new ArrayList<>();
    boolean optOut = "ACTIVE".equals(left.optOutState()) || "ACTIVE".equals(right.optOutState());
    if (optOut) flags.add("OPT_OUT");
    if (!normalizeName(left.customerName()).equals(normalizeName(right.customerName()))
        && !normalizeName(left.customerName()).isEmpty()
        && !normalizeName(right.customerName()).isEmpty()) {
      flags.add("CONFLICTING_NAME");
    }
    if (!left.addressAvailable() || !right.addressAvailable()) {
      flags.add("ADDRESS_UNAVAILABLE");
    } else if (left.addresses().isEmpty() || right.addresses().isEmpty()) {
      flags.add("NO_ADDRESS");
    }

    return new PairVerdict(left, right, List.copyOf(hits), confidence, List.copyOf(flags), optOut);
  }

  /** 两侧共有的地址（归一化后逐个比对，返回第一条共有地址的脱敏形态）。 */
  private String sharedAddress(SourceCustomer left, SourceCustomer right) {
    for (String a : left.addresses()) {
      String na = normalizeAddress(a);
      if (na.isEmpty()) continue;
      for (String b : right.addresses()) {
        if (na.equals(normalizeAddress(b))) return a;
      }
    }
    return null;
  }

  // ================================================================ 候选查询

  /** 候选列表：真分页 + 多维筛选 + 与分页无关的状态计数。 */
  public CrmCustomerMergeCandidatePage candidatePage(
      String status, String matchType, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(status) != null) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(matchType) != null) {
      where.append(" and match_type = ?");
      binds.add(matchType.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(left_customer_ref), lower(?)) > 0"
              + " or strpos(lower(right_customer_ref), lower(?)) > 0"
              + " or strpos(lower(left_customer_name), lower(?)) > 0"
              + " or strpos(lower(right_customer_name), lower(?)) > 0"
              + " or strpos(lower(signal_summary), lower(?)) > 0)");
      for (int i = 0; i < 5; i++) binds.add(keyword.trim());
    }

    long total =
        sql.fetchOne("select count(*) from crm_customer_merge_candidate" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_customer_merge_candidate"
                + where
                + " order by confidence desc, detected_at desc, id"
                + " limit ? offset ?",
            pageBinds.toArray());

    long suggested =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_candidate where tenant_id = ? and status = 'SUGGESTED'",
                currentTenant())
            .get(0, Long.class);
    long confirmed =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_candidate where tenant_id = ? and status = 'CONFIRMED'",
                currentTenant())
            .get(0, Long.class);
    long rejected =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_candidate where tenant_id = ? and status = 'REJECTED'",
                currentTenant())
            .get(0, Long.class);

    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerMergeCandidatePage(
        records.stream().map(this::candidateRow).toList(),
        safePage,
        safeSize,
        total,
        totalPages,
        suggested,
        confirmed,
        rejected,
        "候选是**建议**，不是结论：生成候选**不会**合并任何客户，"
            + "合并只能由人工确认触发（确认要写理由）。"
            + "只有姓名相同、地址对不上的对子不会出现在这里（同名不同人）。");
  }

  /**
   * 候选详情：在列表那一行之上补上<b>证据全文</b>与两侧的联系方式口径。
   *
   * @param revealContact 是否已授权并显式要求明文
   */
  public CrmCustomerMergeCandidateDetail candidateDetail(
      UUID candidateId, boolean revealContact, AccessService.Access actor) {
    if (revealContact && !actor.permissions().contains("crm.customers.pii.read")) {
      throw forbidden(
          "查看明文联系方式需要额外的权限码 crm.customers.pii.read："
              + "候选证据默认脱敏显示（a***@***.com / 138****1234），完整值必须显式授权。");
    }
    Record candidate =
        sql.fetchOne(
            "select * from crm_customer_merge_candidate where tenant_id = ? and id = ?",
            currentTenant(),
            candidateId);
    if (candidate == null) throw notFound("候选不存在：" + candidateId);

    Map<String, Object> evidence = parseJson(candidate.get("evidence", String.class));
    List<CrmCustomerMergeSignal> signals = signalsOf(evidence);

    UUID leftId = candidate.get("left_customer_id", UUID.class);
    UUID rightId = candidate.get("right_customer_id", UUID.class);
    Record left = profileRow(leftId);
    Record right = profileRow(rightId);

    boolean masked = !revealContact;
    return new CrmCustomerMergeCandidateDetail(
        candidateRow(candidate),
        signals,
        evidence,
        masked ? nz(left.get("contact_email_masked", String.class)) : nz(left.get("contact_email", String.class)),
        masked ? nz(left.get("contact_phone_masked", String.class)) : nz(left.get("contact_phone", String.class)),
        masked ? nz(right.get("contact_email_masked", String.class)) : nz(right.get("contact_email", String.class)),
        masked ? nz(right.get("contact_phone_masked", String.class)) : nz(right.get("contact_phone", String.class)),
        masked,
        "证据里存的联系方式**只有脱敏形态** —— 匹配在服务内用明文比对，落库的只有脱敏值。"
            + (masked
                ? "本次展示的也是脱敏形态。"
                : "本次已按显式授权展示明文。"));
  }

  // ================================================================ 人工确认（合并）

  /**
   * 人工确认一条候选 ⇒ <b>真的合并</b>。
   *
   * <p>这是全模块唯一能写逻辑客户与映射的入口（{@link #scan} 写不了）。
   * 合并**不改来源行**：只在映射表里把两条来源行挂到同一个逻辑客户下。
   */
  @Transactional
  public CrmCustomerMergeLogicalDetail confirm(
      UUID candidateId, CrmCustomerMergeConfirmInput input, AccessService.Access actor) {

    Record candidate =
        sql.fetchOne(
            "select * from crm_customer_merge_candidate where tenant_id = ? and id = ?",
            currentTenant(),
            candidateId);
    if (candidate == null) throw notFound("候选不存在：" + candidateId);
    String status = candidate.get("status", String.class);
    if (!"SUGGESTED".equals(status)) {
      throw bad("候选已经复核过（" + status + "），复核结论是终态，不能再改。");
    }

    UUID leftId = candidate.get("left_customer_id", UUID.class);
    UUID rightId = candidate.get("right_customer_id", UUID.class);
    Record left = profileRow(leftId);
    Record right = profileRow(rightId);
    if (left == null || right == null) {
      throw bad("候选的一侧来源客户行已经不存在，无法合并。请重新生成候选。");
    }

    // ---- 目标逻辑客户 ---------------------------------------------------
    Map<UUID, UUID> existing = activeMappingByCustomer(); // customer_profile_id → logical_customer_id
    UUID leftLogical = existing.get(leftId);
    UUID rightLogical = existing.get(rightId);

    UUID logicalId = input.logicalCustomerId();
    if (logicalId == null) {
      if (leftLogical != null && rightLogical != null && !leftLogical.equals(rightLogical)) {
        throw conflict(
            "两侧来源行分别属于两个不同的逻辑客户（"
                + leftLogical
                + " 与 "
                + rightLogical
                + "）。请先拆分其中一个，或在请求里显式指定要合并进的逻辑客户。");
      }
      logicalId = leftLogical != null ? leftLogical : rightLogical;
    } else {
      var exists =
          sql.fetchOne(
              "select id, status from crm_customer_merge_logical where tenant_id = ? and id = ?",
              currentTenant(),
              logicalId);
      if (exists == null) throw notFound("逻辑客户不存在：" + logicalId);
      if ("DISSOLVED".equals(exists.get("status", String.class))) {
        throw bad(
            "逻辑客户 "
                + logicalId
                + " 已经解散（成员全被拆走了），不能往里合并：已解散的逻辑客户是**终态**，"
                + "重新合并请走新的候选，由系统新建一个逻辑客户。");
      }
      for (UUID other : List.of(leftLogical, rightLogical)) {
        if (other != null && !other.equals(logicalId)) {
          throw conflict(
              "其中一侧来源行已经属于逻辑客户 "
                  + other
                  + "。一个来源行同时只能属于一个逻辑客户 —— 请先把它拆出来。");
        }
      }
    }

    boolean created = false;
    if (logicalId == null) {
      logicalId = UUID.randomUUID();
      created = true;
      sql.execute(
          "insert into crm_customer_merge_logical"
              + " (id,tenant_id,logical_ref,display_name,member_count,source_shop_count,total_order_count,"
              + " opt_out_state,status,created_at,updated_at)"
              + " values (?,?,?,'',0,0,0,'NONE','ACTIVE',now(),now())",
          logicalId,
          currentTenant(),
          nextLogicalRef());
    }

    // ---- 写映射（只写 ACTIVE；已拆分的映射不能改回 ACTIVE，所以是新行）----
    OffsetDateTime mergedAt = now();
    int added = 0;
    for (Record profile : List.of(left, right)) {
      UUID customerId = profile.get("id", UUID.class);
      if (existing.containsKey(customerId)) {
        // 已经是目标逻辑客户的成员 ⇒ 不重复写（部分唯一索引也会挡）。
        continue;
      }
      boolean primary = added == 0 && created;
      sql.execute(
          "insert into crm_customer_merge_mapping"
              + " (id,tenant_id,logical_customer_id,customer_profile_id,source_shop,customer_ref,"
              + " customer_name,role,state,merged_at,merged_by,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,'ACTIVE',?::timestamptz,?,now(),now())",
          UUID.randomUUID(),
          currentTenant(),
          logicalId,
          customerId,
          profile.get("source_shop", String.class),
          profile.get("customer_ref", String.class),
          nz(profile.get("customer_name", String.class)),
          primary ? "PRIMARY" : "MEMBER",
          mergedAt,
          actor.userId());
      added++;
    }

    // ---- 候选落终态 ------------------------------------------------------
    sql.execute(
        "update crm_customer_merge_candidate"
            + " set status = 'CONFIRMED', logical_customer_id = ?, reviewed_by = ?,"
            + " reviewed_at = ?::timestamptz, review_note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        logicalId,
        actor.userId(),
        mergedAt,
        input.reason().trim(),
        mergedAt,
        currentTenant(),
        candidateId);

    // ---- 重算逻辑客户聚合 --------------------------------------------------
    refreshLogical(logicalId);

    // ---- 操作记录 ----------------------------------------------------------
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("candidateId", candidateId.toString());
    details.put("matchType", candidate.get("match_type", String.class));
    details.put("confidence", candidate.get("confidence", BigDecimal.class));
    details.put("signalSummary", candidate.get("signal_summary", String.class));
    details.put("leftCustomerRef", candidate.get("left_customer_ref", String.class));
    details.put("leftSourceShop", candidate.get("left_source_shop", String.class));
    details.put("rightCustomerRef", candidate.get("right_customer_ref", String.class));
    details.put("rightSourceShop", candidate.get("right_source_shop", String.class));
    details.put("logicalCustomerCreated", created);
    details.put("addedMemberCount", added);
    int memberCount = logicalMemberCount(logicalId);
    sql.execute(
        "insert into crm_customer_merge_operation"
            + " (id,tenant_id,operation,logical_customer_id,candidate_id,target_customer_id,"
            + " member_count,reason,operator_id,occurred_at,details,created_at)"
            + " values (?,?,'MERGE',?,?,null,?,?,?,?::timestamptz,?::jsonb,now())",
        UUID.randomUUID(),
        currentTenant(),
        logicalId,
        candidateId,
        memberCount,
        input.reason().trim(),
        actor.userId(),
        mergedAt,
        toJson(details));

    return logicalDetail(logicalId);
  }

  /** 拒绝一条候选 —— 理由必填（拒绝也是决定，事后要能回答「为什么不是同一个人」）。 */
  @Transactional
  public CrmCustomerMergeCandidateRow reject(
      UUID candidateId, CrmCustomerMergeRejectInput input, AccessService.Access actor) {
    Record candidate =
        sql.fetchOne(
            "select * from crm_customer_merge_candidate where tenant_id = ? and id = ?",
            currentTenant(),
            candidateId);
    if (candidate == null) throw notFound("候选不存在：" + candidateId);
    String status = candidate.get("status", String.class);
    if (!"SUGGESTED".equals(status)) {
      throw bad("候选已经复核过（" + status + "），复核结论是终态，不能再改。");
    }
    OffsetDateTime at = now();
    sql.execute(
        "update crm_customer_merge_candidate"
            + " set status = 'REJECTED', reviewed_by = ?, reviewed_at = ?::timestamptz,"
            + " review_note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        actor.userId(),
        at,
        input.reason().trim(),
        at,
        currentTenant(),
        candidateId);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("candidateId", candidateId.toString());
    details.put("matchType", candidate.get("match_type", String.class));
    details.put("confidence", candidate.get("confidence", BigDecimal.class));
    details.put("leftCustomerRef", candidate.get("left_customer_ref", String.class));
    details.put("rightCustomerRef", candidate.get("right_customer_ref", String.class));
    sql.execute(
        "insert into crm_customer_merge_operation"
            + " (id,tenant_id,operation,logical_customer_id,candidate_id,target_customer_id,"
            + " member_count,reason,operator_id,occurred_at,details,created_at)"
            + " values (?,?,'REJECT',null,?,null,0,?,?,?::timestamptz,?::jsonb,now())",
        UUID.randomUUID(),
        currentTenant(),
        candidateId,
        "候选被拒绝：" + input.reason().trim(),
        actor.userId(),
        at,
        toJson(details));

    return candidateRow(
        sql.fetchOne(
            "select * from crm_customer_merge_candidate where tenant_id = ? and id = ?",
            currentTenant(),
            candidateId));
  }

  // ================================================================ 逻辑客户

  /** 逻辑客户列表：真分页。 */
  public CrmCustomerMergeLogicalPage logicalPage(int page, int size) {
    long total =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_logical where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(safeSize);
    binds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select l.*,"
                + " (select array_agg(distinct m.source_shop) from crm_customer_merge_mapping m"
                + "  where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE') as shops"
                + " from crm_customer_merge_logical l where l.tenant_id = ?"
                + " order by l.created_at desc, l.id limit ? offset ?",
            binds.toArray());

    long merged =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_logical where tenant_id = ? and status = 'ACTIVE'",
                currentTenant())
            .get(0, Long.class);
    long dissolved =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_logical where tenant_id = ? and status = 'DISSOLVED'",
                currentTenant())
            .get(0, Long.class);

    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerMergeLogicalPage(
        records.stream().map(this::logicalRow).toList(),
        safePage,
        safeSize,
        total,
        totalPages,
        merged,
        dissolved,
        "逻辑客户是**合并后**的那个「人」：一个逻辑客户下挂着若干条来源店铺的客户行。"
            + "已拆分干净的逻辑客户标为「已解散」并保留（审计流水与映射行都要留着）。");
  }

  /** 逻辑客户详情：成员 + 按币种分开的金额 + 操作记录。 */
  public CrmCustomerMergeLogicalDetail logicalDetail(UUID logicalId) {
    Record logical =
        sql.fetchOne(
            "select * from crm_customer_merge_logical where tenant_id = ? and id = ?",
            currentTenant(),
            logicalId);
    if (logical == null) throw notFound("逻辑客户不存在：" + logicalId);

    List<CrmCustomerMergeMember> members =
        sql.fetch(
                "select m.id,m.customer_profile_id,m.customer_ref,m.source_shop,m.customer_name,"
                    + " m.role,m.state,m.merged_at,m.merged_by,m.split_at,m.split_by,m.split_reason"
                    + " from crm_customer_merge_mapping m"
                    + " where m.tenant_id = ? and m.logical_customer_id = ?"
                    + " order by (m.state = 'ACTIVE') desc, m.role asc, m.merged_at asc, m.id",
                currentTenant(),
                logicalId)
            .map(
                r ->
                    new CrmCustomerMergeMember(
                        r.get("id", UUID.class),
                        r.get("customer_profile_id", UUID.class),
                        nz(r.get("customer_ref", String.class)),
                        nz(r.get("source_shop", String.class)),
                        nz(r.get("customer_name", String.class)),
                        nz(r.get("role", String.class)),
                        nz(r.get("state", String.class)),
                        mappingStateLabel(r.get("state", String.class)),
                        moment(r.get("merged_at", OffsetDateTime.class)),
                        text(r.get("merged_by", UUID.class)),
                        moment(r.get("split_at", OffsetDateTime.class)),
                        text(r.get("split_by", UUID.class)),
                        nz(r.get("split_reason", String.class))));

    List<CrmCustomerMergeAmount> amounts = amountsOf(logicalId);
    List<CrmCustomerMergeOperationRow> operations = operationRows(logicalId, 50);

    return new CrmCustomerMergeLogicalDetail(
        logicalRow(logical), members, amounts, operations, logicalNote());
  }

  /**
   * 拆分：把某一个成员从逻辑客户里拆出去。
   *
   * <p>🔴 <b>映射行不删</b>：只把 {@code state} 从 ACTIVE 改成 SPLIT 并留痕。
   * 来源行 {@code crm_customer_profile} <b>一个字都没被改过</b>，
   * 所以拆开之后那位客户**原样**回到「按来源店铺的客户行」状态。
   */
  @Transactional
  public CrmCustomerMergeLogicalDetail split(
      UUID logicalId, CrmCustomerMergeSplitInput input, AccessService.Access actor) {

    Record logical =
        sql.fetchOne(
            "select * from crm_customer_merge_logical where tenant_id = ? and id = ?",
            currentTenant(),
            logicalId);
    if (logical == null) throw notFound("逻辑客户不存在：" + logicalId);

    Record mapping =
        sql.fetchOne(
            "select * from crm_customer_merge_mapping"
                + " where tenant_id = ? and logical_customer_id = ? and customer_profile_id = ?"
                + " and state = 'ACTIVE'",
            currentTenant(),
            logicalId,
            input.customerProfileId());
    if (mapping == null) {
      throw bad(
          "来源客户行 "
              + input.customerProfileId()
              + " 不是逻辑客户 "
              + logical.get("logical_ref", String.class)
              + " 的生效成员（或已经被拆出过一次）。");
    }

    OffsetDateTime at = now();
    sql.execute(
        "update crm_customer_merge_mapping"
            + " set state = 'SPLIT', split_at = ?::timestamptz, split_by = ?, split_reason = ?,"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        at,
        actor.userId(),
        input.reason().trim(),
        at,
        currentTenant(),
        mapping.get("id", UUID.class));

    refreshLogical(logicalId);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("customerProfileId", input.customerProfileId().toString());
    details.put("customerRef", nz(mapping.get("customer_ref", String.class)));
    details.put("sourceShop", nz(mapping.get("source_shop", String.class)));
    details.put("mergedAt", moment(mapping.get("merged_at", OffsetDateTime.class)));
    int memberCount = logicalMemberCount(logicalId);
    sql.execute(
        "insert into crm_customer_merge_operation"
            + " (id,tenant_id,operation,logical_customer_id,candidate_id,target_customer_id,"
            + " member_count,reason,operator_id,occurred_at,details,created_at)"
            + " values (?,?,'SPLIT',?,null,?,?,?,?,?::timestamptz,?::jsonb,now())",
        UUID.randomUUID(),
        currentTenant(),
        logicalId,
        input.customerProfileId(),
        memberCount,
        input.reason().trim(),
        actor.userId(),
        at,
        toJson(details));

    return logicalDetail(logicalId);
  }

  // ================================================================ 操作记录 / 运行台账

  /** 操作记录列表：真分页。 */
  public CrmCustomerMergeOperationPage operationPage(UUID logicalId, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (logicalId != null) {
      where.append(" and logical_customer_id = ?");
      binds.add(logicalId);
    }
    long total =
        sql.fetchOne("select count(*) from crm_customer_merge_operation" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select o.*, l.logical_ref from crm_customer_merge_operation o"
                + " left join crm_customer_merge_logical l"
                + " on l.tenant_id = o.tenant_id and l.id = o.logical_customer_id"
                + where
                + " order by o.occurred_at desc, o.id desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerMergeOperationPage(
        records.stream().map(this::operationRow).toList(), safePage, safeSize, total, totalPages);
  }

  /** 候选生成运行台账：真分页。 */
  public CrmCustomerMergeRunPage runPage(int page, int size) {
    long total =
        sql.fetchOne("select count(*) from crm_customer_merge_run where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(safeSize);
    binds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_customer_merge_run where tenant_id = ?"
                + " order by created_at desc, id desc limit ? offset ?",
            binds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerMergeRunPage(
        records.stream().map(this::runRow).toList(), safePage, safeSize, total, totalPages);
  }

  // ================================================================ 聚合重算

  /**
   * 重算逻辑客户的聚合值（成员数、来源店铺数、订单数、退订状态、展示名、首末购买时间）。
   *
   * <p>🔴 聚合是**派生值**：从 ACTIVE 映射行现算，而不是每次合并时手工加减 ——
   * 手工加减迟早会和实际成员对不上。
   */
  private void refreshLogical(UUID logicalId) {
    // ---- 1. 选主成员 ------------------------------------------------------
    // 🔴 主成员可能刚被拆走 ⇒ 换一个顶上，而不是让逻辑客户指着一个已拆走的来源行。
    UUID primary = null;
    Record primaryRow =
        sql.fetchOne(
            "select m.customer_profile_id from crm_customer_merge_mapping m"
                + " where m.tenant_id = ? and m.logical_customer_id = ? and m.state = 'ACTIVE'"
                + " order by (m.role = 'PRIMARY') desc, m.merged_at, m.id limit 1",
            currentTenant(),
            logicalId);
    if (primaryRow != null) primary = primaryRow.get(0, UUID.class);

    // ---- 2. 角色归位（只有一个 PRIMARY）----------------------------------
    sql.execute(
        "update crm_customer_merge_mapping set role = 'MEMBER'"
            + " where tenant_id = ? and logical_customer_id = ? and state = 'ACTIVE'",
        currentTenant(),
        logicalId);
    if (primary != null) {
      sql.execute(
          "update crm_customer_merge_mapping set role = 'PRIMARY'"
              + " where tenant_id = ? and logical_customer_id = ? and state = 'ACTIVE'"
              + " and customer_profile_id = ?",
          currentTenant(),
          logicalId,
          primary);
    }

    // ---- 3. 更新逻辑客户聚合 ----------------------------------------------
    sql.execute(
        "update crm_customer_merge_logical l set"
            + " member_count = (select count(*) from crm_customer_merge_mapping m"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'),"
            + " source_shop_count = (select count(distinct m.source_shop) from crm_customer_merge_mapping m"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'),"
            + " total_order_count = (select coalesce(sum(p.order_count),0)"
            + "   from crm_customer_merge_mapping m join crm_customer_profile p"
            + "   on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'),"
            + " opt_out_state = (select case"
            + "   when exists (select 1 from crm_customer_merge_mapping m join crm_customer_profile p"
            + "     on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "    where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'"
            + "      and p.opt_out_state = 'ACTIVE') then 'ACTIVE'"
            + "   when exists (select 1 from crm_customer_merge_mapping m join crm_customer_profile p"
            + "     on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "    where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'"
            + "      and p.opt_out_state = 'REVOKED') then 'REVOKED'"
            + "   else 'NONE' end),"
            + " display_name = coalesce("
            + "   (select p.customer_name from crm_customer_merge_mapping m join crm_customer_profile p"
            + "     on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "    where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'"
            + "      and m.role = 'PRIMARY' order by m.merged_at, m.id limit 1),"
            + "   (select p.customer_name from crm_customer_merge_mapping m join crm_customer_profile p"
            + "     on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "    where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'"
            + "    order by m.merged_at, m.id limit 1), ''),"
            + " primary_customer_id = ?,"
            + " first_order_at = (select min(p.first_order_at) from crm_customer_merge_mapping m"
            + "   join crm_customer_profile p on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'),"
            + " last_order_at = (select max(p.last_order_at) from crm_customer_merge_mapping m"
            + "   join crm_customer_profile p on p.tenant_id = m.tenant_id and p.id = m.customer_profile_id"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE'),"
            + " status = case when (select count(*) from crm_customer_merge_mapping m"
            + "   where m.tenant_id = l.tenant_id and m.logical_customer_id = l.id and m.state = 'ACTIVE') = 0"
            + "   then 'DISSOLVED' else 'ACTIVE' end,"
            + " updated_at = now()"
            + " where l.tenant_id = ? and l.id = ?",
        primary,
        currentTenant(),
        logicalId);
  }

  private int logicalMemberCount(UUID logicalId) {
    Long count =
        sql.fetchOne(
                "select count(*) from crm_customer_merge_mapping"
                    + " where tenant_id = ? and logical_customer_id = ? and state = 'ACTIVE'",
                currentTenant(),
                logicalId)
            .get(0, Long.class);
    return count == null ? 0 : count.intValue();
  }

  /** 逻辑客户的累计消费 —— <b>一币种一行，跨币种不相加</b>。 */
  private List<CrmCustomerMergeAmount> amountsOf(UUID logicalId) {
    List<CrmCustomerMergeAmount> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.currency, sum(a.total_amount), sum(a.order_count)"
                + " from crm_customer_amount a"
                + " where a.tenant_id = ? and a.customer_id in"
                + "   (select m.customer_profile_id from crm_customer_merge_mapping m"
                + "    where m.tenant_id = ? and m.logical_customer_id = ? and m.state = 'ACTIVE')"
                + " group by a.currency order by a.currency",
            currentTenant(),
            currentTenant(),
            logicalId)) {
      String currency = r.get(0, String.class);
      BigDecimal total = nz(r.get(1, BigDecimal.class));
      int orders = r.get(2, Integer.class) == null ? 0 : r.get(2, Integer.class);
      // 客单价是派生值：分母为 0 时是 null（不可判定），不是 0。
      BigDecimal avg =
          orders == 0 ? null : total.divide(BigDecimal.valueOf(orders), 4, RoundingMode.HALF_UP);
      out.add(new CrmCustomerMergeAmount(currency, total, orders, avg));
    }
    return out;
  }

  // ================================================================ 数据读取

  /** 取来源客户名单（15.3.1.1 的表，<b>只读</b>）。 */
  private List<SourceCustomer> loadSourceCustomers(int limit) {
    return sql.fetch(
            "select p.id,p.customer_ref,p.source_shop,p.customer_name,p.customer_party_id,"
                + " p.contact_email,p.contact_phone,p.contact_email_masked,p.contact_phone_masked,"
                + " p.opt_out_state,p.order_count,p.first_order_at,p.last_order_at"
                + " from crm_customer_profile p where p.tenant_id = ?"
                + " order by p.source_shop, p.customer_ref limit ?",
            currentTenant(),
            limit)
        .map(
            r ->
                new SourceCustomer(
                    r.get("id", UUID.class),
                    nz(r.get("customer_ref", String.class)),
                    nz(r.get("source_shop", String.class)),
                    nz(r.get("customer_name", String.class)),
                    r.get("customer_party_id", UUID.class),
                    nz(r.get("contact_email", String.class)),
                    nz(r.get("contact_phone", String.class)),
                    nz(r.get("contact_email_masked", String.class)),
                    nz(r.get("contact_phone_masked", String.class)),
                    nz(r.get("opt_out_state", String.class)),
                    r.get("order_count", Integer.class) == null ? 0 : r.get("order_count", Integer.class),
                    r.get("first_order_at", OffsetDateTime.class),
                    r.get("last_order_at", OffsetDateTime.class),
                    List.of(),
                    false,
                    "尚未解析"));
  }

  /** 已经属于某个逻辑客户的来源行 id。 */
  private Set<UUID> activeMappedCustomerIds() {
    Set<UUID> out = new LinkedHashSet<>();
    for (Record r :
        sql.fetch(
            "select customer_profile_id from crm_customer_merge_mapping"
                + " where tenant_id = ? and state = 'ACTIVE'",
            currentTenant())) {
      out.add(r.get(0, UUID.class));
    }
    return out;
  }

  /** 来源行 id → 它所属的逻辑客户 id（只取 ACTIVE 映射）。 */
  private Map<UUID, UUID> activeMappingByCustomer() {
    Map<UUID, UUID> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select customer_profile_id, logical_customer_id from crm_customer_merge_mapping"
                + " where tenant_id = ? and state = 'ACTIVE'",
            currentTenant())) {
      out.put(r.get(0, UUID.class), r.get(1, UUID.class));
    }
    return out;
  }

  private Record profileRow(UUID customerId) {
    return sql.fetchOne(
        "select * from crm_customer_profile where tenant_id = ? and id = ?",
        currentTenant(),
        customerId);
  }

  private List<String> distinctShops(List<SourceCustomer> customers) {
    Set<String> shops = new LinkedHashSet<>();
    for (SourceCustomer c : customers) shops.add(c.sourceShop());
    return List.copyOf(shops);
  }

  // ================================================================ 行装配

  private CrmCustomerMergeCandidateRow candidateRow(Record r) {
    String status = nz(r.get("status", String.class));
    String matchType = nz(r.get("match_type", String.class));
    BigDecimal confidence = r.get("confidence", BigDecimal.class);
    UUID logicalId = r.get("logical_customer_id", UUID.class);
    String logicalRef = null;
    if (logicalId != null) {
      Record logical =
          sql.fetchOne(
              "select logical_ref from crm_customer_merge_logical where tenant_id = ? and id = ?",
              currentTenant(),
              logicalId);
      if (logical != null) logicalRef = logical.get(0, String.class);
    }
    Map<String, Object> evidence = parseJson(r.get("evidence", String.class));
    return new CrmCustomerMergeCandidateRow(
        r.get("id", UUID.class),
        status,
        candidateStatusLabel(status),
        matchType,
        matchTypeLabel(matchType),
        confidence,
        nz(r.get("signal_summary", String.class)),
        signalsOf(evidence),
        jsonStringList(r.get("exclusion_flags", String.class)),
        r.get("opt_out_involved", Boolean.class) != null && r.get("opt_out_involved", Boolean.class),
        nz(r.get("left_customer_ref", String.class)),
        nz(r.get("left_source_shop", String.class)),
        nz(r.get("left_customer_name", String.class)),
        nz(r.get("right_customer_ref", String.class)),
        nz(r.get("right_source_shop", String.class)),
        nz(r.get("right_customer_name", String.class)),
        logicalId,
        logicalRef,
        text(r.get("reviewed_by", UUID.class)),
        moment(r.get("reviewed_at", OffsetDateTime.class)),
        nz(r.get("review_note", String.class)),
        moment(r.get("detected_at", OffsetDateTime.class)));
  }

  private CrmCustomerMergeLogicalRow logicalRow(Record r) {
    String status = nz(r.get("status", String.class));
    String optOut = nz(r.get("opt_out_state", String.class));
    List<String> shops = new ArrayList<>();
    // 🔴 列表页多查了 shops 这一列，详情页没有 —— 取之前先确认字段在不在，
    //   否则详情页会因为「找不到列」直接炸掉。
    if (r.field("shops") != null) {
      Object array = r.get("shops");
      if (array instanceof String[] raw) {
        for (String s : raw) shops.add(s);
      } else if (array instanceof Object[] raw) {
        for (Object o : raw) shops.add(String.valueOf(o));
      } else if (array instanceof List<?> list) {
        for (Object o : list) shops.add(String.valueOf(o));
      }
    }
    return new CrmCustomerMergeLogicalRow(
        r.get("id", UUID.class),
        nz(r.get("logical_ref", String.class)),
        nz(r.get("display_name", String.class)),
        r.get("member_count", Integer.class) == null ? 0 : r.get("member_count", Integer.class),
        r.get("source_shop_count", Integer.class) == null ? 0 : r.get("source_shop_count", Integer.class),
        r.get("total_order_count", Integer.class) == null ? 0 : r.get("total_order_count", Integer.class),
        optOut,
        optOutStateLabel(optOut),
        status,
        logicalStatusLabel(status),
        moment(r.get("first_order_at", OffsetDateTime.class)),
        moment(r.get("last_order_at", OffsetDateTime.class)),
        shops,
        moment(r.get("created_at", OffsetDateTime.class)));
  }

  private CrmCustomerMergeOperationRow operationRow(Record r) {
    String operation = nz(r.get("operation", String.class));
    return new CrmCustomerMergeOperationRow(
        r.get("id", UUID.class),
        operation,
        operationLabel(operation),
        r.get("logical_customer_id", UUID.class),
        r.get("logical_ref", String.class),
        r.get("candidate_id", UUID.class),
        r.get("target_customer_id", UUID.class),
        r.get("member_count", Integer.class) == null ? 0 : r.get("member_count", Integer.class),
        nz(r.get("reason", String.class)),
        text(r.get("operator_id", UUID.class)),
        moment(r.get("occurred_at", OffsetDateTime.class)),
        parseJson(r.get("details", String.class)));
  }

  private CrmCustomerMergeRunRow runRow(Record r) {
    return new CrmCustomerMergeRunRow(
        r.get("id", UUID.class),
        nz(r.get("run_no", String.class)),
        nz(r.get("status", String.class)),
        intOf(r, "scanned_customer_count"),
        intOf(r, "candidate_count"),
        intOf(r, "below_threshold_count"),
        intOf(r, "excluded_already_merged_count"),
        intOf(r, "excluded_same_shop_count"),
        intOf(r, "opt_out_candidate_count"),
        intOf(r, "address_resolved_count"),
        intOf(r, "address_unavailable_count"),
        intOf(r, "party_unavailable_count"),
        r.get("truncated", Boolean.class) != null && r.get("truncated", Boolean.class),
        nz(r.get("failure_reason", String.class)),
        parseJson(r.get("facts", String.class)),
        text(r.get("operator_id", UUID.class)),
        moment(r.get("started_at", OffsetDateTime.class)),
        moment(r.get("finished_at", OffsetDateTime.class)));
  }

  private List<CrmCustomerMergeOperationRow> operationRows(UUID logicalId, int limit) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(logicalId);
    binds.add(limit);
    return sql.fetch(
            "select o.*, l.logical_ref from crm_customer_merge_operation o"
                + " left join crm_customer_merge_logical l"
                + " on l.tenant_id = o.tenant_id and l.id = o.logical_customer_id"
                + " where o.tenant_id = ? and o.logical_customer_id = ?"
                + " order by o.occurred_at desc, o.id desc limit ?",
            binds.toArray())
        .stream()
        .map(this::operationRow)
        .toList();
  }

  // ================================================================ 证据

  /** 证据明细（落库的只有脱敏形态）。 */
  private Map<String, Object> evidenceOf(PairVerdict verdict) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    List<Map<String, Object>> signals = new ArrayList<>();
    for (SignalHit hit : verdict.hits()) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("signalType", hit.type().name());
      one.put("label", hit.type().label());
      one.put("matched", true);
      one.put("strength", hit.strength());
      one.put("maskedValue", hit.maskedValue());
      one.put("detail", hit.detail());
      signals.add(one);
    }
    evidence.put("signals", signals);
    Map<String, Object> left = new LinkedHashMap<>();
    left.put("customerRef", verdict.left().customerRef());
    left.put("sourceShop", verdict.left().sourceShop());
    left.put("customerName", verdict.left().customerName());
    left.put("emailMasked", verdict.left().emailMasked());
    left.put("phoneMasked", verdict.left().phoneMasked());
    left.put("addressCount", verdict.left().addresses().size());
    evidence.put("left", left);
    Map<String, Object> right = new LinkedHashMap<>();
    right.put("customerRef", verdict.right().customerRef());
    right.put("sourceShop", verdict.right().sourceShop());
    right.put("customerName", verdict.right().customerName());
    right.put("emailMasked", verdict.right().emailMasked());
    right.put("phoneMasked", verdict.right().phoneMasked());
    right.put("addressCount", verdict.right().addresses().size());
    evidence.put("right", right);
    return evidence;
  }

  /** 从证据 JSON 里读回信号列表（前端逐条展示「凭什么认为是同一个人」）。 */
  private List<CrmCustomerMergeSignal> signalsOf(Map<String, Object> evidence) {
    List<CrmCustomerMergeSignal> out = new ArrayList<>();
    Object raw = evidence.get("signals");
    if (!(raw instanceof List<?> list)) return out;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) continue;
      Object strength = map.get("strength");
      out.add(
          new CrmCustomerMergeSignal(
              String.valueOf(map.get("signalType")),
              String.valueOf(map.get("label")),
              Boolean.TRUE.equals(map.get("matched")),
              strength instanceof BigDecimal decimal
                  ? decimal
                  : strength instanceof Number number
                      ? new BigDecimal(number.toString())
                      : BigDecimal.ZERO,
              map.get("maskedValue") == null ? null : String.valueOf(map.get("maskedValue")),
              map.get("detail") == null ? "" : String.valueOf(map.get("detail"))));
    }
    return out;
  }

  /** 一句人话：「凭什么认为这两个人是同一个人」。 */
  private String signalSummary(PairVerdict verdict) {
    if (verdict.hits().isEmpty()) return "";
    List<String> parts = new ArrayList<>();
    for (SignalHit hit : verdict.hits()) parts.add(hit.type().label());
    return String.join("；", parts);
  }

  // ================================================================ 归一化

  static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  static String normalizePhone(String phone) {
    if (phone == null) return "";
    StringBuilder digits = new StringBuilder();
    for (char c : phone.toCharArray()) {
      if (Character.isDigit(c)) digits.append(c);
    }
    return digits.toString();
  }

  static String normalizeName(String name) {
    if (name == null) return "";
    return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  static String normalizeAddress(String address) {
    if (address == null) return "";
    return address.trim().replaceAll("[\\s,，、]+", "").toLowerCase(Locale.ROOT);
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 逻辑客户号：LC-yyyyMMdd-000001。 */
  private String nextLogicalRef() {
    var row =
        sql.fetchOne(
            "select nextval('crm_customer_merge_logical_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "LC-" + day + "-" + String.format("%06d", seq);
  }

  /** 运行号：CM-yyyyMMdd-000001。 */
  private String nextRunNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_customer_merge_run_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CM-" + day + "-" + String.format("%06d", seq);
  }

  private static int intOf(Record r, String column) {
    Integer value = r.get(column, Integer.class);
    return value == null ? 0 : value;
  }

  private static String text(UUID value) {
    return value == null ? null : value.toString();
  }

  private static String moment(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private static String candidateStatusLabel(String status) {
    if (status == null) return "";
    try {
      return CustomerMergeCandidateStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String matchTypeLabel(String type) {
    if (type == null) return "";
    try {
      return CustomerMergeMatchType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String mappingStateLabel(String state) {
    if (state == null) return "";
    try {
      return CustomerMergeMappingState.valueOf(state).label();
    } catch (IllegalArgumentException e) {
      return state;
    }
  }

  private static String logicalStatusLabel(String status) {
    return "DISSOLVED".equals(status) ? "已解散" : "生效中";
  }

  private static String operationLabel(String operation) {
    if (operation == null) return "";
    try {
      return CustomerMergeOperationType.valueOf(operation).label();
    } catch (IllegalArgumentException e) {
      return operation;
    }
  }

  private static String optOutStateLabel(String state) {
    if (state == null) return "";
    return switch (state) {
      case "NONE" -> "无退订记录";
      case "ACTIVE" -> "退订生效中";
      case "REVOKED" -> "退订已撤销";
      default -> state;
    };
  }

  private static String logicalNote() {
    return "逻辑客户是**合并后**的那个「人」：成员行是各来源店铺的客户行（来源行本身没被改过）。"
        + "金额**按币种分行**，跨币种不相加。拆分把成员移出去并留痕（映射行不删）。";
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("合并依据快照无法序列化", e);
    }
  }

  private static Map<String, Object> parseJson(String raw) {
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new LinkedHashMap<>();
    }
  }

  private static List<String> jsonStringList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return JSON.readValue(
          raw, new TypeReference<List<String>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException forbidden(String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
