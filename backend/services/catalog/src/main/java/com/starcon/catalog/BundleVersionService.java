package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.5.1.1 套装版本管理：组合套装（多个 SKU 按数量捆绑销售）的版本。
 *
 * <p>蓝图原文：「职责：管理组合套装（多个 SKU 按数量捆绑销售）的版本：创建版本、设置生效区间、发布后不可变。」
 * 「输入：套装名称、组件清单、版本号、生效区间；发布操作。」
 * 「输出：套装版本记录（草稿/已发布）；供 04 刊登为独立商品、供 09 订单展开组件。」
 * 「下游 = 3.1.2.2（套装作为可售 SKU）、09 订单（组件展开）。**虚拟套装不持有独立库存**，扣的是组件库存。」
 *
 * <p>本类的纪律（与 V23 文件头同源）：
 *
 * <ol>
 *   <li><b>生效区间是半开区间</b> {@code [from, to)}，判定全走 {@link BundleRules#overlaps}。
 *       「当前生效版本」是一个纯时刻查询（{@link BundleRules#covers}），不是「取最新一版」——
 *       后者会在区间有缝或重叠时报出错误答案。
 *   <li><b>重叠一律拒绝，不做裁决</b>。发布冲突时把与哪一版（版本号 + 区间）撞了写进报错，
 *       让运营能自己决定是调本版还是调接替版，而不是让系统挑一个生效。
 *   <li><b>发布后不可变由 DB 触发器兜底</b>，服务层先给可执行的中文原因；两层都在，
 *       因为触发器拦得住绕过应用层的写入但给不出人话原因。
 *   <li><b>唯一的例外是「向前关闭生效区间」</b>（{@link #closeVersion}）。若连这一条都禁掉，
 *       第一个开放区间的版本发布后就永远无法被接替。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 套装挂在商品域下（下游是 3.1.2.2 的可售 SKU 与 07/09 的组件展开），
 * 所以沿用已有词表而不新造 —— 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p>边界：组件的<b>循环引用检测</b>与独立组件工作台归 3.5.1.2；本模块交付的是
 * 「随草稿版本编辑组件清单 + 发布时校验组件齐备」，因为组件清单是蓝图写在本模块输入里的。
 */
@Service
public class BundleVersionService {
  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 版本列表页的读形状：按套装读全部版本。 */
  private static final String VERSION_SELECT =
      "select v.id,v.bundle_id,v.version_no,v.name,v.status,v.effective_from,v.effective_to,"
          + "v.published_by,v.published_at,v.note,v.version,v.created_at,v.updated_at,"
          + "b.code as bundle_code,"
          + "(select count(*) from bundle_component c where c.tenant_id=v.tenant_id and c.version_id=v.id) as component_count,"
          + "(select coalesce(sum(c.quantity),0) from bundle_component c where c.tenant_id=v.tenant_id and c.version_id=v.id) as total_quantity "
          + "from bundle_version v join bundle b on b.tenant_id=v.tenant_id and b.id=v.bundle_id";

  private final DSLContext sql;

  public BundleVersionService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 概览

  /**
   * 套装版本概览。
   *
   * <p>{@code openEndedCount} 是这个模块唯一需要每天盯的数字：一个开放区间（永远有效）的
   * 已发布版本如果没有被接替，这个套装就永远停在一个旧版本上，而运营不会收到任何提示。
   */
  public BundleOverview overview() {
    UUID tenantId = currentTenantId();
    var bundles =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='ACTIVE') as active_count,"
                + "count(*) filter (where status='ARCHIVED') as archived_count"
                + " from bundle where tenant_id=?",
            tenantId);
    var versions =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='DRAFT') as draft_count,"
                + "count(*) filter (where status='PUBLISHED') as published_count,"
                + "count(*) filter (where status='PUBLISHED' and effective_to is null) as open_ended "
                + " from bundle_version where tenant_id=?",
            tenantId);
    // 「此刻生效」在 SQL 里算，而不是取回全部版本在 Java 里比 —— 后者会漏掉区间有缝的情况。
    long effectiveNow =
        sql.fetchOne(
                "select count(*) from bundle_version where tenant_id=? and status='PUBLISHED'"
                    + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)",
                tenantId,
                utcNow(),
                utcNow())
            .get(0, Long.class);
    // 草稿没有组件：这些是「建了版本但填不出能发布的内容」，发布时会被拒。
    long draftWithoutComponent =
        sql.fetchOne(
                "select count(*) from bundle_version v where v.tenant_id=? and v.status='DRAFT'"
                    + " and not exists (select 1 from bundle_component c"
                    + " where c.tenant_id=v.tenant_id and c.version_id=v.id)",
                tenantId)
            .get(0, Long.class);
    return new BundleOverview(
        bundles.get("total", Long.class),
        bundles.get("active_count", Long.class),
        bundles.get("archived_count", Long.class),
        versions.get("total", Long.class),
        versions.get("draft_count", Long.class),
        versions.get("published_count", Long.class),
        effectiveNow,
        versions.get("open_ended", Long.class),
        draftWithoutComponent,
        "「开放区间」= 已发布且生效终点为空，也就是「此后一直有效」。这类版本如果没有被下一个版本接替，"
            + "这个套装会永远停在一个旧版本上，而页面上不会有任何提示 —— 所以它是这个模块唯一需要每天盯的数字。"
            + "生效区间是左闭右开的 [起点, 终点)：终点那一瞬间归下一个版本，重叠一律拒绝不做裁决。");
  }

  // ============================================================ 套装档案

  /** 套装列表：分页 + 关键词 + 状态。每行自带版本存量与此刻生效的版本号。 */
  public BundlePage bundles(BundleQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where b.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BundleRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(" and lower(b.code) like ? escape '!'");
      binds.add(escapeLike(keyword.toLowerCase(Locale.ROOT)));
    }
    String status = BundleRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      String problem = BundleRules.bundleStatusProblem(status);
      check(problem == null, problem);
      where.append(" and b.status=?");
      binds.add(status);
    }
    long total =
        sql.fetchOne("select count(*) from bundle b" + where, binds.toArray()).get(0, Long.class);
    if (total == 0) return new BundlePage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select b.id,b.code,b.status,b.note,b.version,b.created_at,b.updated_at,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id) as version_count,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='DRAFT') as draft_count,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='PUBLISHED') as published_count,"
                + "e.id as effective_id,e.version_no as effective_version_no "
                + "from bundle b left join lateral ("
                + "  select v.id,v.version_no from bundle_version v"
                + "  where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='PUBLISHED'"
                + "    and v.effective_from<=?::timestamptz and (v.effective_to is null or ?::timestamptz <v.effective_to)"
                + "  limit 1) e on true"
                + where
                + " order by b.code limit ? offset ?",
            concat(List.of(utcNow(), utcNow()), pageBinds).toArray());
    List<BundleRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(bundleRow(r));
    return new BundlePage(items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 套装详情：套装 + 全部版本（含此刻生效的是哪一版）。 */
  public BundleDetail bundle(UUID id) {
    UUID tenantId = currentTenantId();
    var b =
        sql.fetchOne(
            "select b.id,b.code,b.status,b.note,b.version,b.created_at,b.updated_at,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id) as version_count,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='DRAFT') as draft_count,"
                + "(select count(*) from bundle_version v where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='PUBLISHED') as published_count,"
                + "e.id as effective_id,e.version_no as effective_version_no "
                + "from bundle b left join lateral ("
                + "  select v.id,v.version_no from bundle_version v"
                + "  where v.tenant_id=b.tenant_id and v.bundle_id=b.id and v.status='PUBLISHED'"
                + "    and v.effective_from<=?::timestamptz and (v.effective_to is null or ?::timestamptz <v.effective_to)"
                + "  limit 1) e on true"
                + " where b.tenant_id=? and b.id=?",
            utcNow(), utcNow(), tenantId, id);
    if (b == null) throw notFound("套装不存在");
    var versions =
        sql.fetch(
            VERSION_SELECT
                + " where v.tenant_id=? and v.bundle_id=?"
                + " order by v.effective_from desc, v.version_no",
            tenantId, id);
    var effective = effectiveVersionRow(tenantId, id, Instant.now());
    List<BundleVersionSummary> summaries = new ArrayList<>(versions.size());
    for (Record r : versions) {
      boolean isEffective =
          effective != null && effective.get("id", UUID.class).equals(r.get("id", UUID.class));
      summaries.add(
          new BundleVersionSummary(
              r.get("id", UUID.class),
              r.get("version_no", String.class),
              r.get("name", String.class),
              r.get("status", String.class),
              BundleRules.statusLabel(r.get("status", String.class)),
              instant(r.get("effective_from", OffsetDateTime.class)),
              instant(r.get("effective_to", OffsetDateTime.class)),
              r.get("component_count", Long.class),
              isEffective));
    }
    return new BundleDetail(
        bundleRow(b),
        summaries,
        effective == null ? null : effective.get("id", UUID.class),
        effective == null ? null : effective.get("version_no", String.class),
        effective == null
            ? "这个套装此刻没有生效版本：可能只有草稿，或已发布版本的区间都还没到。"
                + "刊登与订单展开都取不到版本，所以现在不能卖这个套装"
            : "此刻生效的是「"
                + effective.get("version_no", String.class)
                + "」。生效区间是左闭右开的 [起点, 终点)：终点那一瞬间归下一个版本，"
                + "重叠一律拒绝不做裁决");
  }

  /** 建套装或改套装（id 为空即新建）。编码建后不可改。 */
  public BundleRow saveBundle(AccessService.Access actor, UUID id, BundleInput input) {
    UUID tenantId = actor.tenantId();
    String code = BundleRules.normalizeCode(input.code());
    String problem = BundleRules.codeProblem(code);
    check(problem == null, problem);
    String note = BundleRules.normalize(input.note());
    problem = BundleRules.noteProblem(note);
    check(problem == null, problem);
    if (id == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into bundle(id,tenant_id,code,note,version) values(?,?,?,?,0)",
            newId, tenantId, code, note);
      } catch (DataAccessException e) {
        throw conflict(
            "套装编码「"
                + code
                + "」已经存在（"
                + rootMessage(e)
                + "）：同一租户同一编码只能有一个套装，两条时「刊登里的这个套装指哪个」无法回答");
      }
      audit(actor, newId, "BundleCreated");
      return bundle(newId).bundle();
    }
    var current =
        sql.fetchOne("select code,version from bundle where tenant_id=? and id=?", tenantId, id);
    if (current == null) throw notFound("套装不存在");
    checkVersion(current.get("version", Long.class), input.version());
    String currentCode = current.get("code", String.class);
    check(
        currentCode.equals(code),
        "套装编码建后不可改：它是刊登与订单里的稳定引用，改编码等于让"
            + currentCode
            + "的历史归属失效，而且这个套装已经发布的版本都会被引用过旧编码");
    int affected =
        sql.execute(
            "update bundle set note=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and version=?",
            note, tenantId, id, input.version());
    check(affected == 1, "这条套装刚被别人改过（版本不是 " + input.version() + "），请刷新后重试");
    audit(actor, id, "BundleUpdated");
    return bundle(id).bundle();
  }

  /**
   * 归档/启用套装。
   *
   * <p>归档只表示「不再新建版本」，历史版本照常可读 —— 09 订单要读已归档套装的组件展开结果。
   */
  public BundleRow setBundleStatus(AccessService.Access actor, UUID id, String status) {
    String normalized = BundleRules.normalizeUpper(status);
    String problem = BundleRules.bundleStatusProblem(normalized);
    check(problem == null, problem);
    int affected =
        sql.execute(
            "update bundle set status=?,version=version+1,updated_at=now() where tenant_id=? and id=?",
            normalized, actor.tenantId(), id);
    if (affected == 0) throw notFound("套装不存在");
    audit(actor, id, "ARCHIVED".equals(normalized) ? "BundleArchived" : "BundleActivated");
    return bundle(id).bundle();
  }

  /**
   * 删除套装。
   *
   * <p>含任何版本时拒绝：已发布版本更会被 V23 的触发器直接挡下（删版本等于让「这单当时按哪一版
   * 展开的」无从查证）。所以本方法只在「只有草稿」时真的会删。
   */
  public void deleteBundle(AccessService.Access actor, UUID id, long version) {
    UUID tenantId = actor.tenantId();
    var current = sql.fetchOne("select version from bundle where tenant_id=? and id=?", tenantId, id);
    if (current == null) throw notFound("套装不存在");
    checkVersion(current.get("version", Long.class), version);
    long published =
        sql.fetchOne(
                "select count(*) from bundle_version where tenant_id=? and bundle_id=? and status='PUBLISHED'",
                tenantId, id)
            .get(0, Long.class);
    check(
        published == 0,
        "这个套装已经发布过 "
            + published
            + " 个版本，不能删除：历史订单冻结的是当时的组件展开结果，删掉套装会让"
            + "「这单当时包含哪些组件」无从查证。要停止使用请归档，而不是删除");
    long drafts =
        sql.fetchOne(
                "select count(*) from bundle_version where tenant_id=? and bundle_id=? and status='DRAFT'",
                tenantId, id)
            .get(0, Long.class);
    check(
        drafts == 0,
        "这个套装还有 " + drafts + " 个草稿版本，请先删掉草稿再建新的：草稿是待办不是历史，"
            + "但连它一起删掉会让「这个套装当时规划过哪些版本」无从查证");
    try {
      sql.execute("delete from bundle where tenant_id=? and id=? and version=?", tenantId, id, version);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleDeleted");
  }

  private BundleRow bundleRow(Record r) {
    String status = r.get("status", String.class);
    return new BundleRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        status,
        BundleRules.bundleStatusLabel(status),
        r.get("note", String.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)),
        r.get("version_count", Long.class),
        r.get("draft_count", Long.class),
        r.get("published_count", Long.class),
        r.get("effective_id", UUID.class),
        r.get("effective_version_no", String.class));
  }

  // ============================================================ 版本

  /** 版本列表（跨套装）：分页 + 关键词 + 状态 + 具体套装。 */
  public BundleVersionPage versions(BundleVersionQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where v.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BundleRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(" and (lower(v.version_no) like ? escape '!' or lower(v.name) like ? escape '!'"
          + " or lower(b.code) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String status = BundleRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      String problem = BundleRules.statusProblem(status);
      check(problem == null, problem);
      where.append(" and v.status=?");
      binds.add(status);
    }
    if (query.bundleId() != null) {
      where.append(" and v.bundle_id=?");
      binds.add(query.bundleId());
    }
    long total =
        sql.fetchOne(
                "select count(*) from bundle_version v join bundle b"
                    + " on b.tenant_id=v.tenant_id and b.id=v.bundle_id"
                    + where,
                binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new BundleVersionPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            VERSION_SELECT + where + " order by v.effective_from desc, v.version_no limit ? offset ?",
            pageBinds.toArray());
    return new BundleVersionPage(
        versionRows(rows, Instant.now()), page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 版本详情：版本 + 组件清单 + 现在能不能改以及为什么。 */
  public BundleVersionDetail version(UUID id) {
    UUID tenantId = currentTenantId();
    var r = sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id);
    if (r == null) throw notFound("套装版本不存在");
    List<BundleComponentRow> components = components(tenantId, id);
    boolean editable = "DRAFT".equals(r.get("status", String.class));
    return new BundleVersionDetail(
        versionRows(List.of(r), Instant.now()).get(0),
        components,
        totalQuantity(components),
        editable,
        editable
            ? "草稿可以随便改：改完发布，发布后这一版就冻结了"
            : "已发布的版本不可修改（改名、改版本号、改生效起点、改组件一律拒绝）——"
                + "历史订单冻结的是当时的组件展开结果。要改内容请新建一个版本并让它接替本版；"
                + "唯一还能做的是把生效终点收紧到未来（提前停用本版），那不会改写历史");
  }

  /** 某个套装的版本列表。 */
  public List<BundleVersionSummary> versionsOf(UUID bundleId) {
    return bundle(bundleId).versions();
  }

  /** 建草稿版本。 */
  public BundleVersionRow createVersion(AccessService.Access actor, UUID bundleId, BundleVersionInput input) {
    UUID tenantId = actor.tenantId();
    requireBundle(tenantId, bundleId);
    var current = sql.fetchOne("select status from bundle where tenant_id=? and id=?", tenantId, bundleId);
    check(
        !"ARCHIVED".equals(current.get("status", String.class)),
        "这个套装已归档，不能新建版本：归档表示「不再新增」，历史版本仍可读。"
            + "要先建版本请把套装改回启用");
    String versionNo = BundleRules.normalizeVersionNo(input.versionNo());
    String name = BundleRules.normalize(input.name());
    String note = BundleRules.normalize(input.note());
    checkRule(BundleRules.versionNoProblem(versionNo));
    checkRule(BundleRules.nameProblem(name));
    checkRule(BundleRules.noteProblem(note));
    Instant from = BundleRules.parseInstant(input.effectiveFrom(), "生效起点");
    Instant to = BundleRules.parseInstant(input.effectiveTo(), "生效终点");
    checkRule(BundleRules.rangeProblem(from, to));
    UUID newId = Ids.next();
    try {
      // 显式 cast：OffsetDateTime 直接绑 timestamptz 参数，不经过 Timestamp.valueOf
      // （那会按 JVM 默认时区二次偏移，静默错 -28800s 且不报错）。
      sql.execute(
          "insert into bundle_version(id,tenant_id,bundle_id,version_no,name,effective_from,effective_to,status,note,version)"
              + " values(?,?,?,?,?,?::timestamptz,?::timestamptz,'DRAFT',?,0)",
          newId,
          tenantId,
          bundleId,
          versionNo,
          name,
          utcOf(from),
          to == null ? null : utcOf(to),
          note);
    } catch (DataAccessException e) {
      String message = rootMessage(e);
      throw "同套装同版本号".equals(message) || message.contains("bundle_version_tenant_id_bundle_id_version_no_key")
          ? conflict(
              "这个套装下已经有版本「"
                  + versionNo
                  + "」：同套装同版本号只能有一条，两条时「v2 是哪一个」无法回答，"
                  + "而生效区间判定会两边都算数")
          : conflict(message);
    }
    audit(actor, newId, "BundleVersionCreated");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, newId));
  }

  /** 改草稿版本。已发布版本一律拒绝（服务层给原因，DB 触发器兜底）。 */
  public BundleVersionRow updateVersion(AccessService.Access actor, UUID id, BundleVersionInput input) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,effective_to,version from bundle_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("套装版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    checkVersion(current.get("version", Long.class), input.version());
    String versionNo = BundleRules.normalizeVersionNo(input.versionNo());
    String name = BundleRules.normalize(input.name());
    String note = BundleRules.normalize(input.note());
    checkRule(BundleRules.versionNoProblem(versionNo));
    checkRule(BundleRules.nameProblem(name));
    checkRule(BundleRules.noteProblem(note));
    Instant from = BundleRules.parseInstant(input.effectiveFrom(), "生效起点");
    Instant to = BundleRules.parseInstant(input.effectiveTo(), "生效终点");
    checkRule(BundleRules.rangeProblem(from, to));
    try {
      int affected =
          sql.execute(
              "update bundle_version set version_no=?,name=?,effective_from=?::timestamptz,"
                  + "effective_to=?::timestamptz,note=?,version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version=?",
              versionNo,
              name,
              utcOf(from),
              to == null ? null : utcOf(to),
              note,
              tenantId,
              id,
              input.version());
      check(affected == 1, "这个版本刚被别人改过，请刷新后重试");
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleVersionUpdated");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /**
   * 发布版本。
   *
   * <p>三道关，按「先说最根本的那条」排序：
   *
   * <ol>
   *   <li><b>组件齐备</b>：至少一个组件，数量都大于 0。没有组件的套装在 04 刊登里是一个
   *       「能买的商品」，展开到订单里却什么都不是。
   *   <li><b>区间不与已发布版本重叠</b>（规则 R2）：这里先查一遍是为了把「与哪一版撞了」
   *       说清楚，DB 触发器在事务里兜最后一道。
   *   <li><b>发布留痕</b>：记发布人与发布时刻（V23 的 CHECK 会兜）。
   * </ol>
   */
  public BundleVersionRow publishVersion(AccessService.Access actor, UUID id) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select bundle_id,version_no,status,effective_from,effective_to,version"
                + " from bundle_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("套装版本不存在");
    String versionNo = current.get("version_no", String.class);
    check(
        "DRAFT".equals(current.get("status", String.class)),
        "版本「"
            + versionNo
            + "」已经是已发布状态：发布是单向的，撤回发布会让这一版从 09 订单的展开依据里消失，"
            + "而历史订单冻结的是当时的版本。要改内容请新建一个版本");
    long componentCount =
        sql.fetchOne(
                "select count(*) from bundle_component where tenant_id=? and version_id=?",
                tenantId, id)
            .get(0, Long.class);
    checkRule(BundleRules.componentCountProblem((int) componentCount));
    Instant from = toInstant(current.get("effective_from", OffsetDateTime.class));
    Instant to = toInstant(current.get("effective_to", OffsetDateTime.class));
    checkNoOverlap(tenantId, current.get("bundle_id", UUID.class), id, versionNo, from, to);

    try {
      sql.execute(
          "update bundle_version set status='PUBLISHED',published_by=?,published_at=?::timestamptz,"
              + "version=version+1,updated_at=now() where tenant_id=? and id=?",
          actor.userId(), utcNow(), tenantId, id);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleVersionPublished");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /**
   * 向前关闭生效区间（规则 R5）：把已发布版本的终点收紧到某个仍在未来的时刻。
   *
   * <p>这是已发布版本上<b>唯一</b>合法的写操作，也是唯一让版本机制能运转的操作 ——
   * 若连它都禁掉，第一个开放区间的版本发布后就永远无法被接替。
   */
  public BundleVersionRow closeVersion(AccessService.Access actor, UUID id, String effectiveTo) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,effective_to,version from bundle_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("套装版本不存在");
    String versionNo = current.get("version_no", String.class);
    check(
        "PUBLISHED".equals(current.get("status", String.class)),
        "只有已发布的版本需要关闭生效区间：草稿直接改生效区间就行（草稿还没有生效，谈不上停用）");
    Instant newTo = BundleRules.parseInstant(effectiveTo, "生效终点");
    Instant currentTo = toInstant(current.get("effective_to", OffsetDateTime.class));
    String problem = BundleRules.forwardCloseProblem(currentTo, newTo, Instant.now());
    check(problem == null, problem);
    try {
      sql.execute(
          "update bundle_version set effective_to=?::timestamptz,version=version+1,updated_at=now()"
              + " where tenant_id=? and id=?",
          utcOf(newTo), tenantId, id);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleVersionClosed");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /** 删草稿版本。已发布版本由 V23 触发器拒绝（并说明为什么）。 */
  public void deleteVersion(AccessService.Access actor, UUID id, long version) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne("select version_no,status,version from bundle_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("套装版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    try {
      sql.execute(
          "delete from bundle_version where tenant_id=? and id=? and version=?", tenantId, id, version);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleVersionDeleted");
  }

  /** 当前生效版本（规则 R4）。这是 04 刊登与 09 订单的读形状。 */
  public BundleEffectiveView effectiveVersion(UUID bundleId, String at) {
    UUID tenantId = currentTenantId();
    var bundleRow =
        sql.fetchOne("select code from bundle where tenant_id=? and id=?", tenantId, bundleId);
    if (bundleRow == null) throw notFound("套装不存在");
    Instant moment = BundleRules.parseInstant(at, "查询时刻");
    // 不传 at 就是查「此刻」。空值默认成现在而不是让 moment 变成 null：
    // 「不传参数」是「查此刻」的最常见写法，让它一路流到 moment.toString() 会变成 500。
    // 与 BomVersionService.effectiveVersion 同一处裁决，两个模块的 R4 入口行为必须一致。
    if (moment == null) moment = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Record row = effectiveVersionRow(tenantId, bundleId, moment);
    String bundleCode = bundleRow.get("code", String.class);
    if (row == null) {
      return new BundleEffectiveView(
          bundleId,
          bundleCode,
          moment.toString(),
          false,
          null,
          null,
          null,
          List.of(),
          "在 "
              + moment
              + " 这一刻这个套装没有生效版本：可能只有草稿，或已发布版本的区间都还没到。"
                  + "刊登与订单展开都取不到版本，所以这一刻不能卖这个套装。"
                  + "这不是「查不到」而是真实状态 —— 用不带 at 的请求查「此刻」是最常用的一种情况");
    }
    UUID versionId = row.get("id", UUID.class);
    return new BundleEffectiveView(
        bundleId,
        bundleCode,
        moment.toString(),
        true,
        versionId,
        row.get("version_no", String.class),
        row.get("name", String.class),
        components(tenantId, versionId),
        "在 "
            + moment
            + " 这一刻生效的是版本「"
            + row.get("version_no", String.class)
            + "」。生效区间是左闭右开的 [起点, 终点)，重叠区间一律拒绝，"
            + "所以这个时刻至多命中一个版本 —— 这不是「取最新一版」");
  }

  /** 查这个套装在 {@code at} 这一刻生效的是哪个已发布版本行（没有则 null）。 */
  private Record effectiveVersionRow(UUID tenantId, UUID bundleId, Instant at) {
    // 判定放在 SQL 里（tstzrange 半开区间），判定口径与 V23 触发器完全一致：
    // Java 侧再用 BundleRules.covers 复核一次，两边判定必须给出同一个答案。
    return sql.fetchOne(
        "select * from bundle_version where tenant_id=? and bundle_id=? and status='PUBLISHED'"
            + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)"
            + " order by effective_from desc limit 1",
        tenantId, bundleId, utcOf(at), utcOf(at));
  }

  /** 发布前的重叠预检，把「与哪一版撞了」说清楚（R2）。 */
  private void checkNoOverlap(
      UUID tenantId, UUID bundleId, UUID versionId, String versionNo, Instant from, Instant to) {
    var clash =
        sql.fetchOne(
            "select version_no,effective_from,effective_to from bundle_version"
                + " where tenant_id=? and bundle_id=? and id<>? and status='PUBLISHED'"
                + "   and tstzrange(effective_from,effective_to,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')"
                + " order by effective_from limit 1",
            tenantId,
            bundleId,
            versionId,
            utcOf(from),
            to == null ? null : utcOf(to));
    if (clash == null) return;
    throw conflict(
        "版本「"
            + versionNo
            + "」的生效区间与已发布的「"
            + clash.get("version_no", String.class)
            + "」（"
            + clash.get("effective_from", OffsetDateTime.class)
            + " → "
            + (clash.get("effective_to", OffsetDateTime.class) == null
                ? "一直有效"
                : clash.get("effective_to", OffsetDateTime.class).toInstant().toString())
            + "）重叠。重叠一律拒绝不做裁决："
            + "若让某一版自动生效，「某天卖的是哪一版」就会有两个答案，"
            + "而订单冻结的正是当时的版本。请调整本版区间，或让接替版本从「"
            + clash.get("effective_from", OffsetDateTime.class)
            + "」开始");
  }

  // ============================================================ 组件

  /**
   * 某个版本的组件清单。
   *
   * <p>版本不存在时返回 404 而不是空列表：空列表会被读成「这个套装没有组件」，
   * 而真相是「这个套装你看不到」。与 {@link #version} 同一口径。
   */
  public List<BundleComponentRow> components(UUID versionId) {
    UUID tenantId = currentTenantId();
    var exists =
        sql.fetchOne("select id from bundle_version where tenant_id=? and id=?", tenantId, versionId);
    if (exists == null) throw notFound("套装版本不存在");
    return components(tenantId, versionId);
  }

  private List<BundleComponentRow> components(UUID tenantId, UUID versionId) {
    var rows =
        sql.fetch(
            "select c.id,c.version_id,c.sku_id,c.quantity,c.role,c.note,s.code as sku_code,s.name as sku_name"
                + " from bundle_component c join sku s on s.tenant_id=c.tenant_id and s.id=c.sku_id"
                + " where c.tenant_id=? and c.version_id=? order by s.code",
            tenantId, versionId);
    List<BundleComponentRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(componentRow(r));
    return items;
  }

  /**
   * 整体替换组件清单。
   *
   * <p>整体替换而不是增删式：清单有「同版本内一个 SKU 只有一行」的约束，增删式提交时
   * 前端必须自己算出最终集合，漏对账的表现是页面上剩一条幽灵组件。
   *
   * <p>校验分三层，缺一不可：
   *
   * <ol>
   *   <li>提交里的同一 SKU 出现两次 —— 直接拒（「每件套装要几件」无法回答）；
   *   <li>数量 > 0 且精度不超过 {@code numeric(19,6)}；
   *   <li>SKU 必须是本租户真实存在的（一次查询问齐，缺哪个点名哪个，而不是靠外键报错）。
   * </ol>
   *
   * <p>已发布版本的组件由 V23 触发器拒绝（这里先给一句人话）。
   */
  public BundleVersionDetail replaceComponents(AccessService.Access actor, UUID versionId, BundleComponentsInput input) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,version from bundle_version where tenant_id=? and id=?",
            tenantId, versionId);
    if (current == null) throw notFound("套装版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    checkVersion(current.get("version", Long.class), input.version());

    List<BundleComponentInput> components = input.components();
    checkRule(BundleRules.componentCountProblem(components.size()));
    Set<UUID> seen = new LinkedHashSet<>();
    for (BundleComponentInput c : components) {
      checkRule(BundleRules.quantityProblem(c.quantity()));
      check(
          seen.add(c.skuId()),
          "同一个 SKU 在这份组件清单里出现了两次：每个组件「每件套装要几件」只能有一个答案，"
              + "两行时订单展开只能取其中一条，而扣库存会按取到的那条走");
    }
    // 一次问齐，别逐条查（组件最多 50 个，逐条查就是 50 次往返）。
    var known =
        sql.fetch(
                "select id,code from sku where tenant_id=? and id in "
                    + placeholders(components.size()),
                concat(List.of(tenantId), components.stream().map(BundleComponentInput::skuId).toList())
                    .toArray())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .collect(java.util.stream.Collectors.toSet());
    List<String> missing = new ArrayList<>();
    for (BundleComponentInput c : components) {
      if (!known.contains(c.skuId())) missing.add(c.skuId().toString());
    }
    check(
        missing.isEmpty(),
        "这些组件 SKU 在本租户不存在：" + String.join("、", missing)
            + "。套装组件必须指向真实存在的 SKU，否则扣组件库存时扣的是一个不存在的东西");

    sql.execute("delete from bundle_component where tenant_id=? and version_id=?", tenantId, versionId);
    for (BundleComponentInput c : components) {
      sql.execute(
          "insert into bundle_component(id,tenant_id,version_id,sku_id,quantity,role,note)"
              + " values(?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          versionId,
          c.skuId(),
          c.quantity(),
          BundleRules.normalize(c.role()),
          BundleRules.normalize(c.note()));
    }
    audit(actor, versionId, "BundleComponentsReplaced");
    return version(versionId);
  }

  private BundleComponentRow componentRow(Record r) {
    return new BundleComponentRow(
        r.get("id", UUID.class),
        r.get("version_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("role", String.class),
        r.get("note", String.class));
  }

  /** 组件数量合计（BigDecimal，禁浮点）。 */
  private BigDecimal totalQuantity(List<BundleComponentRow> components) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BundleComponentRow c : components) sum = sum.add(c.quantity());
    return sum;
  }

  // ============================================================ 行映射

  private BundleVersionRow versionRow(Record r) {
    return versionRows(List.of(r), Instant.now()).get(0);
  }

  private List<BundleVersionRow> versionRows(List<Record> rows, Instant at) {
    if (rows.isEmpty()) return List.of();
    // 「此刻生效」按 version_id 一次性取回，而不是每行查一次。
    Set<UUID> effective = effectiveSet(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    List<BundleVersionRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String status = r.get("status", String.class);
      BigDecimal total =
          r.get("total_quantity", BigDecimal.class) == null
              ? BigDecimal.ZERO
              : r.get("total_quantity", BigDecimal.class);
      items.add(
          new BundleVersionRow(
              r.get("id", UUID.class),
              r.get("bundle_id", UUID.class),
              r.get("bundle_code", String.class),
              r.get("version_no", String.class),
              r.get("name", String.class),
              status,
              BundleRules.statusLabel(status),
              instant(r.get("effective_from", OffsetDateTime.class)),
              instant(r.get("effective_to", OffsetDateTime.class)),
              r.get("published_by", UUID.class),
              instant(r.get("published_at", OffsetDateTime.class)),
              r.get("note", String.class),
              r.get("component_count", Long.class),
              total,
              effective.contains(r.get("id", UUID.class)),
              "DRAFT".equals(status),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return items;
  }

  /** 哪些版本在 {@code at} 这一刻生效。半开区间判定，与 V23 触发器同口径。 */
  private Set<UUID> effectiveSet(List<UUID> versionIds) {
    Set<UUID> effective = new LinkedHashSet<>();
    if (versionIds.isEmpty()) return effective;
    UUID tenantId = currentTenantId();
    OffsetDateTime now = utcNow();
    var rows =
        sql.fetch(
            "select id,effective_from,effective_to from bundle_version where tenant_id=? and status='PUBLISHED'"
                + " and id in "
                + placeholders(versionIds.size())
                + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)",
            concat(concat(List.of(tenantId), versionIds), List.of(now, now)).toArray());
    for (Record r : rows) {
      // SQL 已经判过一遍，这里再用同一个纯函数复核：两边判定必须一致，
      // 不一致说明半开区间的口径在某一边写错了，而那正是「哪一版生效」出错的地方。
      if (BundleRules.covers(
          toInstant(r.get("effective_from", OffsetDateTime.class)),
          toInstant(r.get("effective_to", OffsetDateTime.class)),
          now.toInstant())) {
        effective.add(r.get("id", UUID.class));
      }
    }
    return effective;
  }

  private void requireBundle(UUID tenantId, UUID bundleId) {
    var row = sql.fetchOne("select id from bundle where tenant_id=? and id=?", tenantId, bundleId);
    if (row == null) throw notFound("套装不存在");
  }

  /** 已发布版本的统一拒绝理由。服务层与 DB 触发器给的是同一件事，只是详略不同。 */
  private static void checkEditable(String status, String versionNo) {
    check(
        "DRAFT".equals(status),
        "版本「"
            + versionNo
            + "」已发布，不可修改：发布后名称、版本号、生效起点与组件一律冻结 —— "
            + "历史订单冻结的是当时的组件展开结果，改了就让「这单当时包含哪些组件」无从查证。"
            + "要改内容请新建一个版本并让它接替本版；唯一还能做的是把生效终点收紧到未来（提前停用本版）");
  }

  private static void checkRule(String problem) {
    check(problem == null, problem);
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /**
   * {@code IN} 列表的占位符，每个都带 {@code ::uuid}。
   *
   * <p>jOOQ 的 {@code fetch(String, Object...)} 把 UUID 按 varchar 送过去，
   * 而 {@code id in (?)} 这种单元素列表让 PG 无法从上下文反推参数类型，
   * 于是报 {@code operator does not exist: uuid = character varying}。
   * 显式 cast 掉这个问题（{@code =} 之所以没事是因为 PG 能从等号另一侧反推）。
   */
  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  private static List<Object> concat(List<Object> head, List<?> tail) {
    List<Object> all = new ArrayList<>(head);
    all.addAll(tail);
    return all;
  }

  private static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw MediaRules.bad(message);
  }

  private static void checkVersion(long current, long submitted) {
    check(
        current == submitted,
        "这条记录刚被别人改过（版本是 " + current + "，你提交的是 " + submitted + "），请刷新后重试");
  }

  /** 当前 UTC 时刻。写 timestamptz 列一律配 {@code ?::timestamptz}，不经过 Timestamp.valueOf。 */
  static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  /** 显式 UTC 绑值。禁用 {@code Timestamp.valueOf(LocalDateTime)}——那会二次偏移。 */
  private static OffsetDateTime utcOf(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }

  void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }
}