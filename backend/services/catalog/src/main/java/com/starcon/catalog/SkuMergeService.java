package com.starcon.catalog;

import static com.starcon.catalog.SkuMergeModels.*;
import static com.starcon.catalog.SkuMergeRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.9.1.2 SKU 合并：执行归并、迁移「当前引用」、原记录标记合并并停用、可回溯合并来源。
 *
 * <p>蓝图原文：「职责：<b>执行 SKU/SPU 合并：把重复记录归并到主记录，迁移关联</b>
 * （listing 映射、库存 bucket、采购历史、价格、订单快照保留指向），
 * <b>原记录标记合并并停用而非物理删除</b>；可回溯合并来源。」
 * 「输入：合并决定（主记录、被合并记录）；{@code expected_version}。」
 * 「输出：<b>合并结果；引用迁移报告；原记录合并标记。</b>」
 *
 * <p>本类<b>不重新判断「像不像」</b>：3.9.1.1 的 {@code sku_duplicate_decision} 已经把
 * 「主记录 / 被合并记录 / 理由 / 登记时刻」记成了一条可查的事实，本类照着执行。
 * 所以本类的入口是<b>候选 ID</b>而不是一对 SKU：手输两个 SKU 绕开决定登记，
 * 等于跳过「谁在什么时候凭什么判的合并」这段可回溯的链路。
 *
 * <h2>一次合并做的事（顺序即依赖顺序）</h2>
 *
 * <ol>
 *   <li>比对 {@code expected_version}：决定里登记的 {@code sku.version} 与此刻的必须一致，
 *       否则 409（登记之后商品被人改过，合并的前提已不成立）；
 *   <li>按 {@link SkuMergeRules} 的策略表逐维度迁移<b>当前引用</b>（12 个维度）；
 *   <li>把被合并记录标记为已合并 + 停用（{@code status='INACTIVE'}），并<b>追加</b>一条
 *       {@code sku_status_event}（不动已有的任何一条事件）；
 *   <li>跨 SPU 时把源商品停产（{@code DISCONTINUED}，3.1.1.3 生命周期的终态）并打合并标记；
 *   <li>落 {@code sku_merge}（合并结果 + 溯源）与 {@code sku_merge_reference}（引用迁移报告）。
 * </ol>
 *
 * <h2>为什么预演与执行共用一条代码路径</h2>
 *
 * <p>合并是本仓库里少数会改写十几张表的写操作。页面必须能在按下确认之前看到
 * 「会搬多少行、哪几行搬不动、哪些是刻意不动的历史」。如果预演是另一份实现，
 * 就会出现「预演说 3 行冲突、实际搬了 5 行」，而运营已经按预演结果做了心理准备 ——
 * 那比不给预演更糟。所以 {@link #migrate} 只用 {@code execute} 一个布尔区分「数」与「动」，
 * 两条路径的 SQL 完全同源。
 *
 * <h2>租户只来自已认证成员关系</h2>
 *
 * <p>本类所有查询都带 {@code tenant_id=?}，而那个值来自 {@code AccessService.Access.tenantId()}；
 * 事务里的 {@code app.current_tenant} 由 {@code AccessService.run} 绑定，
 * {@code FORCE RLS} 再独立兜一道。客户端传 {@code X-Tenant-Id} 不会被读。
 */
@Service
public class SkuMergeService {
  static final int MAX_PAGE_SIZE = 100;

  private final DSLContext sql;

  public SkuMergeService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 预演

  /**
   * 合并前推演：<b>一个字节都不写</b>。
   *
   * <p>它回答三个问题，且必须在按确认之前就能回答：这份决定还成立吗（版本比对）、
   * 到底要把哪两条并起来（两端身份与状态）、会动多少引用（逐维度报告）。
   */
  public SkuMergePreview preview(UUID candidateId) {
    return previewOf(plan(candidateId));
  }

  // ================================================================ 执行

  /**
   * 执行合并。
   *
   * <p>{@code input.confirm} 不为 {@code true} 时拒绝：预演有独立的只读端点，
   * 而「误调一次执行端点」的代价必须是零。
   *
   * <p>整个方法在 {@code AccessService.run} 的一个事务内：12 个维度的迁移、
   * 被合并记录的停用与标记、源商品的停产、合并记录与报告的落库要么全成要么全不成。
   * 中途失败时半搬的引用是本模块最坏的结局 —— 那会留下「报价已经在主记录上、
   * 素材还在被合并记录上」的不一致状态，而没有任何一条记录显示这次合并发生过。
   */
  public SkuMergeRow execute(AccessService.Access actor, UUID candidateId, SkuMergeInput input) {
    CatalogService.check(
        input != null && Boolean.TRUE.equals(input.confirm()),
        "合并是破坏性操作，必须显式确认执行（confirm=true）；只想看影响面请用预演接口");
    Plan plan = plan(candidateId);
    // 复用 versionCheck：预演与执行的门禁必须是同一套判定，
    // 否则「预演说可以、点确认却被拒」会让运营以为系统出错。
    CatalogService.check(
        CHECK_MATCHED.equals(plan.versionCheck()),
        SkuMergeRules.versionCheckLabel(plan.versionCheck()));

    UUID tenantId = actor.tenantId();
    UUID mergeId = Ids.next();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // S1/S2：源商品必须能停产 + 合并链保持一层。放在迁移之前，
    // 因为这两条判定的代价是「什么都没做」，而放在之后就只能回滚十几张表了。
    UUID secondarySpuId = plan.secondarySpuId();
    boolean crossSpu = plan.crossSpu();
    String spuBefore = "";
    String spuAfter = "";
    int remainingOnSpu = 0;
    if (crossSpu) {
      spuBefore = plan.secondarySpuStatus();
      spuAfter = "DISCONTINUED";
      // 3.1.1.3 生命周期矩阵：PENDING_LISTING 到不了 DISCONTINUED（要先经过 ON_SALE/SUSPENDED）。
      // 与其偷偷走两步（那会留下一条运营没做过的上架/停售流转），不如明确拒绝并说清怎么解。
      SpuLifecycle from = SpuLifecycle.parse(spuBefore);
      CatalogService.check(
          from.canTransitionTo(SpuLifecycle.DISCONTINUED),
          "源商品当前是「"
              + from.label()
              + "」，无法直接停产（3.1.1.3 生命周期里待上架要先上架或退回草稿）。"
              + "请先把源商品退回草稿或走完上架，再执行这次合并。");
      remainingOnSpu = count("select count(*) from sku where tenant_id=? and spu_id=?", tenantId, secondarySpuId);
    }

    // S3：逐维度迁移。顺序即 SkuMergeRules.allPolicies() 的顺序（先搬得动的，最后冻结的）。
    List<SkuMergeReferenceRow> rows = new ArrayList<>();
    int[] totals = new int[5];
    for (RefPolicy policy : SkuMergeRules.allPolicies()) {
      Counts c =
          migrate(policy, tenantId, plan.primarySkuId(), plan.secondarySkuId(), crossSpu, true);
      rows.add(c.toRow(policy));
      totals[0] += c.migrated;
      totals[1] += c.summed;
      totals[2] += c.deduplicated;
      totals[3] += c.conflict;
      totals[4] += c.frozen;
    }

    // S4：原记录标记合并并停用（不物理删除）。
    // 乐观锁用行数断言：并发两个合并请求时第二个 updated=0，直接 409 而不是两个都成功。
    int marked =
        sql.execute(
            "update sku set merged_into_sku_id=?,merged_at=?::timestamptz,merged_by=?::uuid,"
                + "status='INACTIVE',version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and merged_into_sku_id is null and version=?",
            plan.primarySkuId(), now, actor.userId(), tenantId, plan.secondarySkuId(),
            plan.actualSecondaryVersion());
    CatalogService.check(marked == 1, "被合并记录已被他人修改，请刷新后重试");
    // 追加状态事件：只加一条，不动已有的（生命周期时间线不能因为合并而丢一段）。
    // reason 在 Java 里拼好再绑，而不是写成 SQL 的 {@code '...'||?}：
    // 字符串拼接里的绑定参数类型推断在不同驱动上行为不一致，
    // 而这句 reason 是这次合并唯一留在事件流里的人话，不值得冒这个险。
    // occurred_at 用 SQL 的 now()：它是「合并这一刻」的系统时间，不接受调用方传入。
    sql.execute(
        "insert into sku_status_event(id,tenant_id,sku_id,from_status,to_status,reason,source,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,'MANUAL',?,now())",
        Ids.next(), tenantId, plan.secondarySkuId(), plan.secondaryStatus(), "INACTIVE",
        "合并到主记录 " + plan.primaryCode() + "（" + plan.primarySkuId() + "）", actor.userId());

    if (crossSpu) {
      int spuMarked =
          sql.execute(
              "update spu set merged_into_spu_id=?,merged_at=?::timestamptz,merged_by=?::uuid,"
                  + "status='DISCONTINUED',version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and merged_into_spu_id is null and version=?",
              plan.primarySpuId(), now, actor.userId(), tenantId, secondarySpuId,
              plan.actualSecondarySpuVersion());
      CatalogService.check(spuMarked == 1, "源商品已被他人修改，请刷新后重试");
      sql.execute(
          "insert into spu_lifecycle_event(id,tenant_id,spu_id,from_status,to_status,reason,source,actor_id,occurred_at)"
              + " values(?,?,?,?,?,?,'MANUAL',?,now())",
          Ids.next(), tenantId, secondarySpuId, spuBefore, spuAfter,
          "合并到主商品 " + plan.primarySpuCode() + "（" + plan.primarySpuId() + "）", actor.userId());
    }

    // S5：落合并事实与引用迁移报告。
    // expected_* 与 actual_* 必须逐字相等（V41 有 CHECK）：比对着才允许写这一行。
    sql.execute(
        "insert into sku_merge(id,tenant_id,decision_id,candidate_id,scan_id,primary_sku_id,"
            + "secondary_sku_id,primary_spu_id,secondary_spu_id,scope,expected_primary_version,"
            + "expected_secondary_version,actual_primary_version,actual_secondary_version,primary_code,"
            + "primary_name,secondary_code,secondary_name,secondary_status_before,secondary_status_after,"
            + "secondary_spu_status_before,secondary_spu_status_after,secondary_spu_remaining_sku_count,"
            + "migrated_count,summed_count,deduplicated_count,conflict_count,frozen_count,note,"
            + "executed_at,executed_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        mergeId, tenantId, plan.decisionId(), candidateId, plan.scanId(), plan.primarySkuId(),
        plan.secondarySkuId(), plan.primarySpuId(), secondarySpuId, crossSpu ? "SPU" : "SKU",
        plan.actualPrimaryVersion(), plan.actualSecondaryVersion(), plan.actualPrimaryVersion(),
        plan.actualSecondaryVersion(), plan.primaryCode(), plan.primaryName(), plan.secondaryCode(),
        plan.secondaryName(), plan.secondaryStatus(), "INACTIVE", spuBefore, spuAfter, remainingOnSpu,
        totals[0], totals[1], totals[2], totals[3], totals[4],
        // executed_at 在 executed_by 之前：timestamptz 列必须显式 ?::timestamptz，
        // 而实测把 cast 放在 values 的**最后一个**位置时 jOOQ 的绑定会整体错位一格
        // （PG 报「cannot cast type uuid to timestamp」）。放在中间位置即稳定。
        input.note() == null ? "" : input.note().trim(), now, actor.userId());
    writeReport(tenantId, mergeId, rows);
    audit(actor, mergeId, "SkuMerged");
    return mergeById(mergeId);
  }

  // ================================================================ 计划（预演与执行共用的判定）

  /**
   * 一次合并的全部前置判定，两个出口（预演 / 执行）都先跑它。
   *
   * <p>它把「决定、两个 SKU、两个 SPU、版本比对」一次性读齐，
   * 之后 {@link #migrate} 与执行步骤都只读这个不可变快照 ——
   * 否则「预演时算出的引用数」与「执行时算出的引用数」可能来自两个不同瞬间。
   */
  private Plan plan(UUID candidateId) {
    UUID tenantId = currentTenantId();
    Record candidate =
        sql.fetchOne(
            "select id,scan_id,left_sku_id,right_sku_id from sku_duplicate_candidate where tenant_id=? and id=?",
            tenantId, candidateId);
    if (candidate == null) throw CatalogService.missing();

    Record decision =
        sql.fetchOne(
            "select id,scan_id,decision,primary_sku_id,secondary_sku_id,primary_version,secondary_version"
                + " from sku_duplicate_decision where tenant_id=? and candidate_id=?",
            tenantId, candidateId);

    UUID leftId = candidate.get("left_sku_id", UUID.class);
    UUID rightId = candidate.get("right_sku_id", UUID.class);
    UUID primaryId = decision == null ? null : decision.get("primary_sku_id", UUID.class);
    UUID secondaryId = decision == null ? null : decision.get("secondary_sku_id", UUID.class);
    // 决定必须指向这一对里的两条（V36 的 CHECK 只保证 primary/secondary 同时有值，
    // 「它们属于本候选」是跨表约束，这里第一次真正校验它）。
    if (primaryId != null) {
      CatalogService.check(
          primaryId.equals(leftId) || primaryId.equals(rightId),
          "已登记决定的主记录不属于这个候选，无法执行合并");
      CatalogService.check(
          secondaryId.equals(leftId) || secondaryId.equals(rightId),
          "已登记决定的被合并记录不属于这个候选，无法执行合并");
    }

    Record primary = primaryId == null ? null : skuRow(tenantId, primaryId);
    Record secondary = secondaryId == null ? null : skuRow(tenantId, secondaryId);
    long actualPrimary = primary == null ? -1 : primary.get("version", Long.class);
    long actualSecondary = secondary == null ? -1 : secondary.get("version", Long.class);
    boolean primaryMerged = primary != null && primary.get("merged_into_sku_id", UUID.class) != null;
    boolean secondaryMerged = secondary != null && secondary.get("merged_into_sku_id", UUID.class) != null;

    boolean executed =
        decision != null
            && count(
                    "select count(*) from sku_merge where tenant_id=? and decision_id=?",
                    tenantId, decision.get("id", UUID.class))
                > 0;

    System.err.println("PLANDBG decision=" + (decision == null ? null : decision.get("decision", String.class))
        + " executed=" + executed + " pMerged=" + primaryMerged + " sMerged=" + secondaryMerged
        + " expP=" + (decision == null ? null : decision.get("primary_version", Long.class))
        + " expS=" + (decision == null ? null : decision.get("secondary_version", Long.class))
        + " actP=" + actualPrimary + " actS=" + actualSecondary
        + " primaryNull=" + (primary == null) + " secondaryNull=" + (secondary == null)
        + " primaryId=" + primaryId + " secondaryId=" + secondaryId);
    String check =
        SkuMergeRules.versionCheck(
            decision == null ? null : decision.get("decision", String.class),
            executed,
            primaryMerged,
            secondaryMerged,
            decision == null ? null : decision.get("primary_version", Long.class),
            decision == null ? null : decision.get("secondary_version", Long.class),
            actualPrimary,
            actualSecondary);

    UUID primarySpuId = primary == null ? null : primary.get("spu_id", UUID.class);
    UUID secondarySpuId = secondary == null ? null : secondary.get("spu_id", UUID.class);
    // 源商品的 version 只在跨 SPU 时才要（合并要改它），同 SPU 时是 0，不参与任何比对。
    long actualSecondarySpu = secondarySpuId == null ? 0 : spuVersion(tenantId, secondarySpuId);
    int remainingOnSecondarySpu =
        secondarySpuId == null
            ? 0
            : count("select count(*) from sku where tenant_id=? and spu_id=?", tenantId, secondarySpuId);

    return new Plan(
        candidateId,
        decision == null ? null : decision.get("id", UUID.class),
        decision == null ? null : decision.get("scan_id", UUID.class),
        decision == null ? null : decision.get("decision", String.class),
        check,
        primaryId,
        secondaryId,
        primary == null ? null : primary.get("code", String.class),
        primary == null ? null : primary.get("name", String.class),
        primary == null ? null : primary.get("status", String.class),
        actualPrimary,
        decision == null ? null : decision.get("primary_version", Long.class),
        secondary == null ? null : secondary.get("code", String.class),
        secondary == null ? null : secondary.get("name", String.class),
        secondary == null ? null : secondary.get("status", String.class),
        actualSecondary,
        decision == null ? null : decision.get("secondary_version", Long.class),
        primarySpuId,
        primary == null ? null : spuField(tenantId, primarySpuId, "code"),
        secondarySpuId,
        secondarySpuId == null ? null : spuField(tenantId, secondarySpuId, "code"),
        secondarySpuId == null ? null : spuField(tenantId, secondarySpuId, "status"),
        actualSecondarySpu,
        remainingOnSecondarySpu);
  }

  /** 一次合并的前置判定快照（不可变：预演与执行读的是同一份）。 */
  private record Plan(
      UUID candidateId,
      UUID decisionId,
      UUID scanId,
      String decision,
      String versionCheck,
      UUID primarySkuId,
      UUID secondarySkuId,
      String primaryCode,
      String primaryName,
      String primaryStatus,
      long actualPrimaryVersion,
      Long expectedPrimaryVersion,
      String secondaryCode,
      String secondaryName,
      String secondaryStatus,
      long actualSecondaryVersion,
      Long expectedSecondaryVersion,
      UUID primarySpuId,
      String primarySpuCode,
      UUID secondarySpuId,
      String secondarySpuCode,
      String secondarySpuStatus,
      long actualSecondarySpuVersion,
      int secondarySpuRemainingSkuCount) {

    boolean crossSpu() {
      return primarySpuId != null && !primarySpuId.equals(secondarySpuId);
    }
  }

  /**
   * 由 {@link Plan} 造出预演视图。
   *
   * <p>放在服务里而不是 {@code Plan} 上：预演要跑 {@link #migrate} 与 {@link #report}，
   * 两者都要 {@code DSLContext}（读真实引用数），而 record 的成员方法隐式是 static。
   */
  private SkuMergePreview previewOf(Plan plan) {
    boolean cross = plan.crossSpu();
    UUID tenantId = currentTenantId();
    List<SkuMergeReferenceRow> rows = new ArrayList<>();
    int[] totals = new int[5];
    int blocked = 0;
    for (RefPolicy policy : SkuMergeRules.allPolicies()) {
      Counts c = migrate(policy, tenantId, plan.primarySkuId(), plan.secondarySkuId(), cross, false);
      if (c.blocked) blocked++;
      rows.add(c.toRow(policy));
      totals[0] += c.migrated;
      totals[1] += c.summed;
      totals[2] += c.deduplicated;
      totals[3] += c.conflict;
      totals[4] += c.frozen;
    }
    boolean matched = CHECK_MATCHED.equals(plan.versionCheck());
    return new SkuMergePreview(
        plan.candidateId(),
        plan.decisionId(),
        plan.decision(),
        plan.versionCheck(),
        SkuMergeRules.versionCheckLabel(plan.versionCheck()),
        matched,
        matched ? null : SkuMergeRules.versionCheckLabel(plan.versionCheck()),
        cross ? "SPU" : "SKU",
        SkuMergeRules.scopeLabel(cross ? "SPU" : "SKU"),
        plan.primarySkuId(), plan.primaryCode(), plan.primaryName(), plan.primarySpuId(),
        plan.primarySpuCode(), plan.primaryStatus(), plan.actualPrimaryVersion(),
        plan.secondarySkuId(), plan.secondaryCode(), plan.secondaryName(), plan.secondarySpuId(),
        plan.secondarySpuCode(), plan.secondaryStatus(), plan.actualSecondaryVersion(),
        plan.expectedPrimaryVersion(), plan.expectedSecondaryVersion(),
        cross ? plan.secondarySpuRemainingSkuCount() : 0,
        report(rows, totals, blocked, cross ? plan.secondarySpuRemainingSkuCount() : 0));
  }

  // ================================================================ 引用迁移

  /** 一个维度在本次合并里的结果。 */
  private record Counts(
      int source, int migrated, int summed, int deduplicated, int conflict, int frozen, boolean blocked) {

    SkuMergeReferenceRow toRow(RefPolicy policy) {
      return new SkuMergeReferenceRow(
          policy.dimension(),
          policy.label(),
          policy.action(),
          SkuMergeRules.actionLabel(policy.action()),
          source,
          migrated,
          summed,
          deduplicated,
          conflict,
          frozen,
          blocked,
          blocked
              ? "跨 SPU 合并：这一维度的行带着源商品的 spu_id，改指主记录会让它指向"
                  + "「主商品的 SKU + 源商品的属性定义」这样一个不存在的组合，"
                  + "所以整维度不搬 —— 这些值继续留在被合并记录上（该记录是保留的、可回查的）。"
                  + policy.note()
              : policy.note());
    }
  }

  /**
   * 按策略迁移（或只数）一个维度。
   *
   * <p>{@code execute=false} 时<b>只跑 SELECT</b>：预演路径上一次写都不会发生。
   * 两条路径共用同一段 SQL 拼接，所以「预演报 N 行、执行动 N 行」是结构上保证的，
   * 不是靠两处代码恰好写得一样。
   *
   * <p>冲突判定用 {@code exists(... t.<主记录的列> and 键 is not distinct from ...)}：
   * 用 {@code IS NOT DISTINCT FROM} 而不是 {@code =}，因为
   * {@code supplier_quote.valid_to}、{@code sku_unit_conversion.effective_to} 这类键列可空，
   * 用 {@code =} 的话 {@code NULL = NULL} 为假，冲突会被漏检，
   * 然后由唯一约束在更晚的地方炸成一个看不懂的 500。
   */
  private Counts migrate(
      RefPolicy policy, UUID tenantId, UUID primary, UUID secondary, boolean crossSpu, boolean execute) {
    // 决定缺两端（还没人判 / 判成 KEEP）时没有可迁的引用：全部报 0，
    // 让预演页面显示「等决定登记完成」而不是给出一堆 0 与一堆告警。
    if (primary == null || secondary == null) {
      return new Counts(0, 0, 0, 0, 0, 0, false);
    }

    if (FROZEN.equals(policy.action())) {
      int total = 0;
      for (TableRef ref : policy.frozenSources()) {
        // 3.9.1.1 的两张表没有 sku_id（它们记的是「这一对」），所以按两侧列统计。
        if (ref.sideCols() != null) {
          total +=
              count(
                  "select count(*) from " + ref.table() + " where tenant_id=? and ("
                      + ref.sideCols()[0] + "=? or " + ref.sideCols()[1] + "=?)",
                  tenantId, secondary, secondary);
        } else {
          total +=
              count(
                  "select count(*) from " + ref.table() + " where tenant_id=? and "
                      + (ref.skuCol() == null ? "sku_id" : ref.skuCol()) + "=?" + andExtra(ref.extra()),
                  tenantId, secondary);
        }
      }
      return new Counts(total, 0, 0, 0, 0, total, false);
    }

    String table = policy.table();
    String col = policy.skuCol();
    // 源行侧的 where：s 是别���，所以列名要带 s. 前缀。
    String sourceWhere =
        " from " + table + " s where s.tenant_id=? and s." + col + "=?" + andExtra(policy.extra());

    // 跨 SPU 且这一维度带 (tenant_id,spu_id,sku_id) 复合外键：整维度拒迁。
    if (policy.sameSpuOnly() && crossSpu) {
      return new Counts(count("select count(*)" + sourceWhere, tenantId, secondary), 0, 0, 0,
          count("select count(*)" + sourceWhere, tenantId, secondary), 0, true);
    }

    int source = count("select count(*)" + sourceWhere, tenantId, secondary);
    if (source == 0) return new Counts(0, 0, 0, 0, 0, 0, false);

    String conflictExists = conflictExists(policy);
    int conflict =
        count("select count(*)" + sourceWhere + " and " + conflictExists, tenantId, secondary, primary);

    if (!execute) {
      // 预演：SUM 维度里与主记录同版本的那几行是「相加」而不是「搬不动」，
      // 所以那一类不计 conflictCount；去重维度的冲突行会被删掉，也不计。
      if (SUM.equals(policy.action())) {
        return new Counts(source, source - conflict, conflict, 0, 0, 0, false);
      }
      return policy.dropConflicts()
          ? new Counts(source, source - conflict, 0, conflict, 0, 0, false)
          : new Counts(source, source - conflict, 0, 0, conflict, 0, false);
    }

    if (SUM.equals(policy.action())) {
      int summed = sumInto(policy, tenantId, primary, secondary);
      int moved = moveRest(policy, tenantId, primary, secondary, true);
      return new Counts(source, moved, summed, 0, 0, 0, false);
    }

    int deduplicated = 0;
    if (policy.dropConflicts() && conflict > 0) {
      // 去重语义：冲突行与主记录的那条引用完全相同，删掉不丢任何信息。
      deduplicated =
          sql.execute(
              "delete from " + table + " where id in (select s.id" + sourceWhere + " and "
                  + conflictExists + ")",
              tenantId, secondary, primary);
    }
    // 去重语义下冲突行已删，剩下的全部改指；否则只改指不冲突的那些，冲突行留在原处。
    int moved = moveRest(policy, tenantId, primary, secondary, !policy.dropConflicts());
    return policy.dropConflicts()
        ? new Counts(source, moved, 0, deduplicated, 0, 0, false)
        : new Counts(source, moved, 0, 0, conflict, 0, false);
  }

  /** 冲突存在的判据（{@code s} 已在作用域内；主记录 id 由调用方绑定）。 */
  private static String conflictExists(RefPolicy policy) {
    return "exists (select 1 from " + policy.table() + " t where t.tenant_id=s.tenant_id and t."
        + policy.skuCol() + "=? and " + policy.conflictJoin() + ")";
  }

  /**
   * SUM 维度：把与主记录同键的被合并记录那一行的<b>用量加进</b>主记录的同行，再删掉它。
   *
   * <p>先取成对的行 id 再逐对更新，而不是一条 {@code update ... set qty = qty + (select ...)}：
   * 那种写法在两个版本行同时存在时会自引用报错，而且出了错没人能从 SQL 里看出是哪一对出了问题。
   * 逐对的形状在审计时是「这一对加了多少」一眼可见。
   */
  private int sumInto(RefPolicy policy, UUID tenantId, UUID primary, UUID secondary) {
    String table = policy.table();
    String col = policy.skuCol();
    String q = policy.qtyCol();
    var pairs =
        sql.fetch(
            "select s.id as src_id,t.id as tgt_id,s." + q + " as src_qty from " + table + " s"
                + " join " + table + " t on t.tenant_id=s.tenant_id and t." + col + "=?"
                + " and " + policy.conflictJoin()
                + " where s.tenant_id=? and s." + col + "=?" + andExtra(policy.extra()),
            primary, tenantId, secondary);
    for (Record pair : pairs) {
      BigDecimal qty = pair.get("src_qty", BigDecimal.class);
      // 两个 qtyCol 都是 NUMERIC(19,6) NOT NULL CHECK>0，和必然仍是正的；
      // 仍显式判一次：让「源行为空」变成一句能读懂的话，而不是一个 CHECK 违约。
      CatalogService.check(
          qty != null,
          "被合并记录的组件用量为空，无法与主记录合并（这是数据问题，请先修正该组件行）");
      sql.execute(
          "update " + table + " set " + q + "=" + q + "+? where tenant_id=? and id=?",
          qty, tenantId, pair.get("tgt_id", UUID.class));
      sql.execute(
          "delete from " + table + " where tenant_id=? and id=?",
          tenantId, pair.get("src_id", UUID.class));
    }
    return pairs.size();
  }

  /** 把「不冲突的那些行」改指主记录；{@code excludeConflicts=false} 表示冲突行已删、全搬。 */
  private int moveRest(
      RefPolicy policy, UUID tenantId, UUID primary, UUID secondary, boolean excludeConflicts) {
    String table = policy.table();
    String sourceWhere =
        " from " + table + " s where s.tenant_id=? and s." + policy.skuCol() + "=?"
            + andExtra(policy.extra());
    if (excludeConflicts) {
      return sql.execute(
          "update " + table + " set " + policy.skuCol() + "=? where id in (select s.id" + sourceWhere
              + " and not " + conflictExists(policy) + ")",
          primary, tenantId, secondary, primary);
    }
    return sql.execute(
        "update " + table + " set " + policy.skuCol() + "=? where id in (select s.id" + sourceWhere + ")",
        primary, tenantId, secondary);
  }

  private static String andExtra(String extra) {
    return extra == null || extra.isBlank() || extra.equals("1=1") ? "" : " and " + extra;
  }

  // ================================================================ 报告

  private SkuMergeReport report(
      List<SkuMergeReferenceRow> rows, int[] totals, int blocked, int remainingOnSpu) {
    String note =
        "历史事实一律不搬：采购单/入库单的行冻结快照、状态事件、导入历史、渲染产物与"
            + "3.9.1.1 的候选与决定都继续指向被合并记录 —— 它们回答的是「当时长什么样」，"
            + "改写它们等于让证据随结论一起变。";
    if (remainingOnSpu > 0) note = SkuMergeRules.crossSpuNote(remainingOnSpu);
    return new SkuMergeReport(
        List.copyOf(rows), totals[0], totals[1], totals[2], totals[3], totals[4], blocked, note);
  }

  private void writeReport(UUID tenantId, UUID mergeId, List<SkuMergeReferenceRow> rows) {
    for (SkuMergeReferenceRow row : rows) {
      sql.execute(
          "insert into sku_merge_reference(id,tenant_id,merge_id,dimension,label,action,source_count,"
              + "migrated_count,summed_count,deduplicated_count,conflict_count,frozen_count,blocked,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(), tenantId, mergeId, row.dimension(), row.label(), row.action(),
          row.sourceCount(), row.migratedCount(), row.summedCount(), row.deduplicatedCount(),
          row.conflictCount(), row.frozenCount(), row.blocked(), row.note());
    }
  }

  /**
   * 报告的合计与备注从落库的分项重算，而不是复用执行时的内存值。
   *
   * <p>blocked 直接读 V41 那一列，<b>不从计数反推</b>：反推（「有冲突且没搬动」）
   * 会把「这一维度全部撞了唯一约束」误报成「整维度被拒」，两者在页面上的处置完全不同。
   */
  private SkuMergeReport reportOf(UUID tenantId, UUID mergeId, int remainingOnSpu) {
    var refs =
        sql.fetch(
            "select * from sku_merge_reference where tenant_id=? and merge_id=? order by id",
            tenantId, mergeId);
    List<SkuMergeReferenceRow> rows = new ArrayList<>();
    int[] totals = new int[5];
    int blocked = 0;
    for (Record r : refs) {
      boolean isBlocked = r.get("blocked", Boolean.class);
      if (isBlocked) blocked++;
      String action = r.get("action", String.class);
      rows.add(
          new SkuMergeReferenceRow(
              r.get("dimension", String.class), r.get("label", String.class), action,
              SkuMergeRules.actionLabel(action),
              r.get("source_count", Integer.class), r.get("migrated_count", Integer.class),
              r.get("summed_count", Integer.class), r.get("deduplicated_count", Integer.class),
              r.get("conflict_count", Integer.class), r.get("frozen_count", Integer.class),
              isBlocked, r.get("note", String.class)));
      totals[0] += r.get("migrated_count", Integer.class);
      totals[1] += r.get("summed_count", Integer.class);
      totals[2] += r.get("deduplicated_count", Integer.class);
      totals[3] += r.get("conflict_count", Integer.class);
      totals[4] += r.get("frozen_count", Integer.class);
    }
    return report(rows, totals, blocked, remainingOnSpu);
  }

  // ================================================================ 查询

  /** 合并历史（治理动作的审计视角），分页。 */
  public SkuMergePage list(int page, int size) {
    UUID tenantId = currentTenantId();
    int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int from = Math.max(page, 0) * limit;
    long total = count("select count(*) from sku_merge where tenant_id=?", tenantId);
    int pending =
        count(
            "select count(*) from sku_duplicate_decision d where d.tenant_id=? and d.decision='MERGE'"
                + " and not exists (select 1 from sku_merge m where m.tenant_id=d.tenant_id"
                + " and m.decision_id=d.id)",
            tenantId);
    var ids =
        sql.fetch(
            "select id from sku_merge where tenant_id=? order by executed_at desc,id desc limit ? offset ?",
            tenantId, limit, from);
    List<SkuMergeRow> items = new ArrayList<>();
    for (Record r : ids) items.add(mergeById(r.get("id", UUID.class)));
    return new SkuMergePage(items, Math.max(page, 0), limit, total, pending);
  }

  /** 按候选读回那次合并（页面上「这一对合并过了吗」）。 */
  public SkuMergeRow mergeByCandidate(UUID candidateId) {
    UUID tenantId = currentTenantId();
    Record r =
        sql.fetchOne(
            "select id from sku_merge where tenant_id=? and candidate_id=?", tenantId, candidateId);
    if (r == null) throw CatalogService.missing();
    return mergeById(r.get("id", UUID.class));
  }

  public SkuMergeRow mergeById(UUID mergeId) {
    UUID tenantId = currentTenantId();
    Record r = sql.fetchOne("select * from sku_merge where tenant_id=? and id=?", tenantId, mergeId);
    if (r == null) throw CatalogService.missing();
    // 报告里的「原记录合并标记」读的是 sku 表此刻的真实状态，而不是 merge 行里的快照 ——
    // 合并后原记录仍可被再次编辑，页面要看到的是「现在」的状态。
    // 三个值先取到有类型的局部变量再进构造器：jOOQ 的 get(String, Class) 是泛型方法，
    // 直接写 `cond ? null : row.get(c, UUID.class)` 会让编译器把 null 那一支推成 Long。
    Record live = skuRow(tenantId, r.get("secondary_sku_id", UUID.class));
    String liveStatus = live == null ? r.get("secondary_status_after", String.class) : live.get("status", String.class);
    UUID liveMergedInto = live == null ? null : live.<UUID>get("merged_into_sku_id", UUID.class);
    java.time.Instant liveMergedAt = instant(live == null ? null : live.<OffsetDateTime>get("merged_at", OffsetDateTime.class));
    UUID liveMergedBy = live == null ? null : live.<UUID>get("merged_by", UUID.class);
    return new SkuMergeRow(
        r.get("id", UUID.class),
        r.get("candidate_id", UUID.class),
        r.get("decision_id", UUID.class),
        r.get("scan_id", UUID.class),
        r.get("scope", String.class),
        SkuMergeRules.scopeLabel(r.get("scope", String.class)),
        r.get("primary_sku_id", UUID.class),
        r.get("primary_code", String.class),
        r.get("primary_name", String.class),
        r.get("secondary_sku_id", UUID.class),
        r.get("secondary_code", String.class),
        r.get("secondary_name", String.class),
        r.get("secondary_status_before", String.class),
        liveStatus,
        liveMergedInto,
        liveMergedAt,
        liveMergedBy,
        r.get("secondary_spu_id", UUID.class),
        r.get("secondary_spu_status_before", String.class),
        r.get("secondary_spu_status_after", String.class),
        r.get("secondary_spu_remaining_sku_count", Integer.class),
        r.get("migrated_count", Integer.class),
        r.get("summed_count", Integer.class),
        r.get("deduplicated_count", Integer.class),
        r.get("conflict_count", Integer.class),
        r.get("frozen_count", Integer.class),
        r.get("note", String.class),
        r.get("executed_by", UUID.class),
        instant(r.get("executed_at", OffsetDateTime.class)),
        reportOf(tenantId, mergeId, r.get("secondary_spu_remaining_sku_count", Integer.class)));
  }

  /**
   * 「可回溯合并来源」。
   *
   * <p>双向：{@code mergedInto} 答「这条被并进了哪一条」，{@code absorbed} 答
   * 「有哪几条被并进了它」。两条都要有 —— 只查前者的话，运营站在主记录上
   * 看不到「我这条底下归并了几条重复」，而那正是治理成果本身。
   */
  public SkuMergeTrace trace(UUID skuId) {
    UUID tenantId = currentTenantId();
    Record sku = skuRow(tenantId, skuId);
    if (sku == null) throw CatalogService.missing();
    UUID mergedInto = sku.get("merged_into_sku_id", UUID.class);

    Record parent = null;
    SkuMergeReport lastReport = null;
    if (mergedInto != null) {
      parent = skuRow(tenantId, mergedInto);
      Record m =
          sql.fetchOne(
              "select id from sku_merge where tenant_id=? and secondary_sku_id=?", tenantId, skuId);
      if (m != null) lastReport = mergeById(m.get("id", UUID.class)).report();
    }

    List<SkuMergeTrace> absorbed = new ArrayList<>();
    var children =
        sql.fetch(
            "select id from sku where tenant_id=? and merged_into_sku_id=? order by merged_at,id",
            tenantId, skuId);
    for (Record c : children) {
      UUID childId = c.get("id", UUID.class);
      Record child = skuRow(tenantId, childId);
      absorbed.add(
          new SkuMergeTrace(
              childId, child.get("code", String.class), child.get("name", String.class),
              child.get("status", String.class), true, skuId, sku.get("code", String.class),
              sku.get("name", String.class),
              instant(child.get("merged_at", OffsetDateTime.class)),
              child.get("merged_by", UUID.class), List.of(), List.of(), null,
              "这一条已被并进本记录，状态已停用，引用已随合并迁移（历史事实除外）。"));
    }

    // 合并链保持一层（服务层拒绝链式合并），所以正常只有一跳；
    // 仍按清单返回是为了页面不必假设这个不变量 —— 将来放开链式合并时出口形状不用改。
    List<SkuMergeTrace> chain = new ArrayList<>();
    if (parent != null) {
      chain.add(
          new SkuMergeTrace(
              mergedInto, parent.get("code", String.class), parent.get("name", String.class),
              parent.get("status", String.class),
              parent.get("merged_into_sku_id", UUID.class) != null,
              parent.get("merged_into_sku_id", UUID.class), null, null,
              instant(parent.get("merged_at", OffsetDateTime.class)),
              parent.get("merged_by", UUID.class), List.of(), List.of(), null,
              "本记录已被并进这一条。"));
    }

    return new SkuMergeTrace(
        skuId,
        sku.get("code", String.class),
        sku.get("name", String.class),
        sku.get("status", String.class),
        mergedInto != null,
        mergedInto,
        parent == null ? null : parent.get("code", String.class),
        parent == null ? null : parent.get("name", String.class),
        instant(sku.get("merged_at", OffsetDateTime.class)),
        sku.get("merged_by", UUID.class),
        absorbed,
        chain,
        lastReport,
        mergedInto == null
            ? (absorbed.isEmpty()
                ? "这一条从未参与合并。"
                : "这一条是主记录，吸收了 " + absorbed.size() + " 条重复记录。")
            : "这一条已被并进主记录，历史订单行仍指向它（快照冻结，不随合并改写）。");
  }

  /** 概览：治理规模 + 待执行的合并决定。 */
  public SkuMergeOverview overview() {
    UUID tenantId = currentTenantId();
    Record latest =
        sql.fetchOne(
            "select id from sku_duplicate_scan where tenant_id=? order by started_at desc,id desc limit 1",
            tenantId);
    int totalMerges = count("select count(*) from sku_merge where tenant_id=?", tenantId);
    int mergedAway =
        count("select count(*) from sku where tenant_id=? and merged_into_sku_id is not null", tenantId);
    int pending =
        count(
            "select count(*) from sku_duplicate_decision d where d.tenant_id=? and d.decision='MERGE'"
                + " and not exists (select 1 from sku_merge m where m.tenant_id=d.tenant_id"
                + " and m.decision_id=d.id)",
            tenantId);
    int conflicts =
        sql.fetchOne(
                "select coalesce(sum(conflict_count),0) as c from sku_merge_reference where tenant_id=?",
                tenantId)
            .get("c", Integer.class);
    int frozen =
        sql.fetchOne(
                "select coalesce(sum(frozen_count),0) as c from sku_merge_reference where tenant_id=?",
                tenantId)
            .get("c", Integer.class);
    // mergedSince 取最早那次合并的时刻（0 = 还没合并过）：页面用它显示「治理从哪天开始」。
    OffsetDateTime first =
        sql.fetchOne(
                "select min(merged_at) as m from sku where tenant_id=? and merged_at is not null",
                tenantId)
            .get("m", OffsetDateTime.class);
    return new SkuMergeOverview(
        latest == null ? null : latest.get("id", UUID.class),
        totalMerges,
        mergedAway,
        pending,
        conflicts,
        frozen,
        first == null ? 0 : first.toInstant().toEpochMilli(),
        "已执行的每一次合并都留下两份可回溯的事实：合并记录（当时的两条记录、依据的决定、"
            + "expected_version 比对结果）与原记录上的合并标记。历史事实不随合并改写。");
  }

  // ================================================================ 公共

  private Record skuRow(UUID tenantId, UUID skuId) {
    return sql.fetchOne("select * from sku where tenant_id=? and id=?", tenantId, skuId);
  }

  private long spuVersion(UUID tenantId, UUID spuId) {
    Record r = sql.fetchOne("select version from spu where tenant_id=? and id=?", tenantId, spuId);
    return r == null ? 0 : r.get("version", Long.class);
  }

  private String spuField(UUID tenantId, UUID spuId, String column) {
    // column 只来自本类的两个字面量（"code" / "status"），不是请求输入。
    Record r = sql.fetchOne("select " + column + " from spu where tenant_id=? and id=?", tenantId, spuId);
    return r == null ? null : r.get(column, String.class);
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  /**
   * {@code select count(*)} 走 {@code fetchOne} 取第一列。
   *
   * <p>本仓库的 jOOQ 版本没有 {@code DSLContext.fetchCount(String, Object...)} 那个重载，
   * 而 {@code fetchOne(...).get(0, Integer.class)} 是本模块与 3.9.1.1 一致的既有写法。
   */
  private int count(String statement, Object... bindings) {
    return sql.fetchOne(statement, bindings).get(0, Integer.class);
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid").get(0, UUID.class);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
