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
 * 3.5.2.1 BOM 版本管理：物料清单（成品由哪些组件组成）的版本。
 *
 * <p>蓝图原文：「职责：维护物料清单（成品由哪些组件组成），支持多版本、版本生效区间、单位用量与损耗率，
 * 服务于委外加工与生产型卖家。」「输入：BOM 版本、组件行（SKU、单位用量、损耗率、生效区间）；发布操作。」
 * 「输出：BOM 版本记录；供 06 采购建议与 08 加工单引用。」
 * 「下游 = 06 采购与供应（加工采购）、08 仓储作业（组装单）。」
 *
 * <p><b>与 3.5.1.1 套装版本管理的边界</b>（V25 文件头第零节，逐条）——
 * 本类<b>不</b>复用 BundleVersionService，因为两者不是同一个东西：
 *
 * <ol>
 *   <li><b>套装不指向任何 SKU，BOM 指向成品 SKU。</b>虚拟组合不占 SKU 行（扣的是组件库存），
 *       而 BOM 的存在意义就是「这个<b>成品</b>由哪些组件组成」。所以本类有
 *       {@code output_sku_id}，且建后不可改。
 *   <li><b>组件行带损耗率。</b>生产会损耗（裁切边角、烧制破损、来料不良），销售套装不损耗。
 *       所以 {@code scrap_rate} 是 BOM 独有的列，套装组件行没有也不该有。
 *   <li><b>BOM 冻结的是「实物领料依据」。</b>套装冻结的是 09 订单展开出的子行；
 *       BOM 冻结的是 08 加工单当时按哪一版、什么损耗率领的料。所以触发器比 V23 更严。
 * </ol>
 *
 * <p>本类的纪律（与 V25 文件头同源）：
 *
 * <ol>
 *   <li><b>生效区间 R1–R5 与 3.5.1.1 逐字同口径</b>，判定转发 {@link BomRules} 而不是复制 ——
 *       同一套「区间重叠」写两份，迟早有一份被改松。
 *   <li><b>损耗率是百分数</b>（{@code 5} = 5%，规则 R6）。存比率会让「填 5」变成 500% 损耗
 *       而不报错，采购量翻 6 倍。
 *   <li><b>单层需求量</b>（规则 R7）由本模块算，<b>但不取整</b>：取整、补安全库存、判缺口
 *       是 3.5.2.2 与 06 的决定。
 *   <li><b>多级递归展开不做</b>：那是 3.5.2.2（BOM 展开）的独立职责。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * BOM 挂在商品域下（下游是 06 采购建议与 08 加工单领料），沿用已有词表而不新造 ——
 * 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@Service
public class BomVersionService {
  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** BOM 列表页的读形状。 */
  private static final String BOM_SELECT =
      "select b.id,b.code,b.output_sku_id,b.status,b.note,b.version,b.created_at,b.updated_at,"
          + "s.code as output_sku_code,s.name as output_sku_name,"
          + "(select count(*) from bom_version v where v.tenant_id=b.tenant_id and v.bom_id=b.id) as version_count,"
          + "(select count(*) from bom_version v where v.tenant_id=b.tenant_id and v.bom_id=b.id and v.status='DRAFT') as draft_count,"
          + "(select count(*) from bom_version v where v.tenant_id=b.tenant_id and v.bom_id=b.id and v.status='PUBLISHED') as published_count,"
          + "e.id as effective_id,e.version_no as effective_version_no "
          + "from bom b join sku s on s.tenant_id=b.tenant_id and s.id=b.output_sku_id"
          + " left join lateral ("
          + "  select v.id,v.version_no from bom_version v"
          + "  where v.tenant_id=b.tenant_id and v.bom_id=b.id and v.status='PUBLISHED'"
          + "    and v.effective_from<=?::timestamptz and (v.effective_to is null or ?::timestamptz <v.effective_to)"
          + "  limit 1) e on true";

  /**
   * 版本列表页的读形状。
   *
   * <p>清单计数与清单<b>复用同一段 FROM</b>（bom_component），否则「行数按版本过滤」与
   * 「用量合计按版本过滤」会给出两个不同的数字，翻页页数对不上。
   * {@code scrap_percent} 取本版本里损耗率最高的一行 —— 列表上一眼看出
   * 「这一版比上一版费料多少」，这是 BOM 独有的可见量。
   */
  private static final String VERSION_SELECT =
      "select v.id,v.bom_id,v.version_no,v.name,v.status,v.effective_from,v.effective_to,"
          + "v.published_by,v.published_at,v.note,v.version,v.created_at,v.updated_at,"
          + "b.code as bom_code,b.output_sku_id,"
          + "s.code as output_sku_code,s.name as output_sku_name,"
          + "(select count(*) from bom_component c where c.tenant_id=v.tenant_id and c.version_id=v.id) as component_count,"
          + "(select coalesce(sum(c.unit_quantity),0) from bom_component c where c.tenant_id=v.tenant_id and c.version_id=v.id) as total_unit_quantity,"
          + "(select max(c.scrap_rate) from bom_component c where c.tenant_id=v.tenant_id and c.version_id=v.id) as scrap_percent "
          + "from bom_version v join bom b on b.tenant_id=v.tenant_id and b.id=v.bom_id"
          + " join sku s on s.tenant_id=b.tenant_id and s.id=b.output_sku_id";

  private final DSLContext sql;

  public BomVersionService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 概览

  /**
   * BOM 版本概览。
   *
   * <p>{@code openEndedCount} 是这个模块唯一需要每天盯的数字：一个开放区间（永远有效）的
   * 已发布版本如果没有被接替，这个 BOM 会永远停在一个旧版本上，而页面上不会有任何提示。
   * {@code publishedWithoutComponentCount} 是<b>必须恒为 0</b>的体检指标
   * （没有任何组件的已发布版本会让 08 组装单领不出料）—— 写出来是为了让「一条都没坏」可核对。
   */
  public BomOverview overview() {
    UUID tenantId = currentTenantId();
    var boms =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='ACTIVE') as active_count,"
                + "count(*) filter (where status='ARCHIVED') as archived_count"
                + " from bom where tenant_id=?",
            tenantId);
    var versions =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='DRAFT') as draft_count,"
                + "count(*) filter (where status='PUBLISHED') as published_count,"
                + "count(*) filter (where status='PUBLISHED' and effective_to is null) as open_ended "
                + " from bom_version where tenant_id=?",
            tenantId);
    // 「此刻生效」在 SQL 里算，而不是取回全部版本在 Java 里比 —— 后者会漏掉区间有缝的情况。
    long effectiveNow =
        sql.fetchOne(
                "select count(*) from bom_version where tenant_id=? and status='PUBLISHED'"
                    + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)",
                tenantId,
                utcNow(),
                utcNow())
            .get(0, Long.class);
    long draftWithoutComponent = countVersionsWithoutComponent(tenantId, "DRAFT");
    long publishedWithoutComponent = countVersionsWithoutComponent(tenantId, "PUBLISHED");
    return new BomOverview(
        boms.get("total", Long.class),
        boms.get("active_count", Long.class),
        boms.get("archived_count", Long.class),
        versions.get("total", Long.class),
        versions.get("draft_count", Long.class),
        versions.get("published_count", Long.class),
        effectiveNow,
        versions.get("open_ended", Long.class),
        draftWithoutComponent,
        publishedWithoutComponent,
        "「开放区间」= 已发布且生效终点为空，也就是「此后一直有效」。这类版本如果没有被下一个版本接替，"
            + "这个 BOM 会永远停在一个旧版本上，而页面上不会有任何提示 —— 所以它是这个模块唯一需要每天盯的数字。"
            + "生效区间是左闭右开的 [起点, 终点)：终点那一瞬间归下一个版本，重叠一律拒绝不做裁决。"
            + "「已发布但没有组件」必须是 0：有的话组装单按它领不出任何料。");
  }

  private long countVersionsWithoutComponent(UUID tenantId, String status) {
    return sql.fetchOne(
            "select count(*) from bom_version v where v.tenant_id=? and v.status=?"
                + " and not exists (select 1 from bom_component c"
                + " where c.tenant_id=v.tenant_id and c.version_id=v.id)",
            tenantId,
            status)
        .get(0, Long.class);
  }

  // ================================================================ BOM 档案

  /** BOM 列表：分页 + 关键词 + 状态。每行自带版本存量与此刻生效的版本号。 */
  public BomPage boms(BomQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where b.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BomRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      // 关键词同时搜 BOM 编码与成品 SKU 编码/名称：运营手上往往只有成品编码。
      where.append(" and (lower(b.code) like ? escape '!' or lower(s.code) like ? escape '!'"
          + " or lower(s.name) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String status = BomRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      String problem = BomRules.bomStatusProblem(status);
      check(problem == null, problem);
      where.append(" and b.status=?");
      binds.add(status);
    }
    long total =
        sql.fetchOne("select count(*) from bom b join sku s"
                + " on s.tenant_id=b.tenant_id and s.id=b.output_sku_id" + where,
                binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new BomPage(List.of(), page, size, 0, 0);
    // BOM_SELECT 的 lateral 子查询里有两处 ?::timestamptz，它们在 FROM 子句中，
    // 所以参数顺序是「先两个时刻、再 where 的绑定、最后分页」——不是 where 之后再补。
    List<Object> pageBinds = concat(List.of(utcNow(), utcNow()), binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            BOM_SELECT + where + " order by b.code limit ? offset ?",
            pageBinds.toArray());
    List<BomRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(bomRow(r));
    return new BomPage(items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** BOM 详情：BOM + 全部版本（含此刻生效的是哪一版）。 */
  public BomDetail bom(UUID id) {
    UUID tenantId = currentTenantId();
    var b =
        sql.fetchOne(
            BOM_SELECT + " where b.tenant_id=? and b.id=?", utcNow(), utcNow(), tenantId, id);
    if (b == null) throw notFound("BOM 不存在");
    var versions =
        sql.fetch(
            VERSION_SELECT + " where v.tenant_id=? and v.bom_id=?"
                + " order by v.effective_from desc, v.version_no",
            tenantId, id);
    var effective = effectiveVersionRow(tenantId, id, Instant.now());
    List<BomVersionSummary> summaries = new ArrayList<>(versions.size());
    for (Record r : versions) {
      boolean isEffective =
          effective != null && effective.get("id", UUID.class).equals(r.get("id", UUID.class));
      summaries.add(
          new BomVersionSummary(
              r.get("id", UUID.class),
              r.get("version_no", String.class),
              r.get("name", String.class),
              r.get("status", String.class),
              BomRules.statusLabel(r.get("status", String.class)),
              instant(r.get("effective_from", OffsetDateTime.class)),
              instant(r.get("effective_to", OffsetDateTime.class)),
              r.get("component_count", Long.class),
              isEffective));
    }
    return new BomDetail(
        bomRow(b),
        summaries,
        effective == null ? null : effective.get("id", UUID.class),
        effective == null ? null : effective.get("version_no", String.class),
        effective == null
            ? "这个 BOM 此刻没有生效版本：可能只有草稿，或已发布版本的区间都还没到。"
                + "08 加工单取不到版本，所以现在按这份 BOM 领不出料"
            : "此刻生效的是「"
                + effective.get("version_no", String.class)
                + "」。生效区间是左闭右开的 [起点, 终点)：终点那一瞬间归下一个版本，"
                + "重叠一律拒绝不做裁决");
  }

  /**
   * 建 BOM 或改 BOM（id 为空即新建）。编码与成品 SKU 建后都不可改。
   *
   * <p>成品不可改的理由比编码更强：改成品等于让「这一版算出来的料要装到别的东西上」，
   * 而已发布版本算出来的需求量已经被 08 加工单按实物领走了。
   */
  public BomRow saveBom(AccessService.Access actor, UUID id, BomInput input) {
    UUID tenantId = actor.tenantId();
    String code = BomRules.normalizeCode(input.code());
    String problem = BomRules.codeProblem(code);
    check(problem == null, problem);
    String note = BomRules.normalize(input.note());
    problem = BomRules.noteProblem(note);
    check(problem == null, problem);
    // 先判空再查存在性：空值报「不存在」会让运营以为是自己填错了编码，
    // 而真实原因是「BOM 必须有一个成品」——这是 V25「差异 1」的第一条纪律。
    check(
        input.outputSkuId() != null,
        "成品 SKU 不能为空：BOM 的存在意义是「这个成品由哪些组件组成」，"
            + "没有成品就没有物料清单（虚拟套装才没有成品 —— 那边不占 SKU 行）");
    requireSku(tenantId, input.outputSkuId(), "成品");
    if (id == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into bom(id,tenant_id,code,output_sku_id,note,version) values(?,?,?,?,?,0)",
            newId, tenantId, code, input.outputSkuId(), note);
      } catch (DataAccessException e) {
        throw conflict(
            "BOM 编码「"
                + code
                + "」已经存在（"
                + rootMessage(e)
                + "）：同一租户同一编码只能有一个 BOM，两条时「加工单里的这个 BOM 指哪个」无法回答");
      }
      audit(actor, newId, "BomCreated");
      return bom(newId).bom();
    }
    var current =
        sql.fetchOne("select code,output_sku_id,version from bom where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("BOM 不存在");
    checkVersion(current.get("version", Long.class), input.version());
    String currentCode = current.get("code", String.class);
    check(
        currentCode.equals(code),
        "BOM 编码建后不可改：它是 06 采购建议与 08 加工单里的稳定引用，改编码等于让"
            + currentCode
            + "的历史归属失效，而且这个 BOM 已经发布的版本都被引用过旧编码");
    UUID currentOutput = current.get("output_sku_id", UUID.class);
    check(
        currentOutput.equals(input.outputSkuId()),
        "BOM 成品建后不可改：已发布版本算出来的需求量已经被 08 加工单按实物领走了，"
            + "换成品等于让「这批料本来要装到哪个产品上」无从查证。要换成品请新建一个 BOM");
    int affected =
        sql.execute(
            "update bom set note=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and version=?",
            note, tenantId, id, input.version());
    check(affected == 1, "这条 BOM 刚被别人改过（版本不是 " + input.version() + "），请刷新后重试");
    audit(actor, id, "BomUpdated");
    return bom(id).bom();
  }

  /**
   * 归档/启用 BOM。
   *
   * <p>归档只表示「不再新建版本」，历史版本照常可读 —— 08 加工单要读已归档 BOM 的历史版本。
   */
  public BomRow setBomStatus(AccessService.Access actor, UUID id, String status) {
    String normalized = BomRules.normalizeUpper(status);
    String problem = BomRules.bomStatusProblem(normalized);
    check(problem == null, problem);
    int affected =
        sql.execute(
            "update bom set status=?,version=version+1,updated_at=now() where tenant_id=? and id=?",
            normalized, actor.tenantId(), id);
    if (affected == 0) throw notFound("BOM 不存在");
    audit(actor, id, "ARCHIVED".equals(normalized) ? "BomArchived" : "BomActivated");
    return bom(id).bom();
  }

  /**
   * 删除 BOM。
   *
   * <p>含任何版本时拒绝：已发布版本更会被 V25 的触发器直接挡下（删版本等于让「这批料当时
   * 按哪一版、什么损耗率算的」无从查证）。所以本方法只在「只有草稿」时真的会删。
   */
  public void deleteBom(AccessService.Access actor, UUID id, long version) {
    UUID tenantId = actor.tenantId();
    var current = sql.fetchOne("select version from bom where tenant_id=? and id=?", tenantId, id);
    if (current == null) throw notFound("BOM 不存在");
    checkVersion(current.get("version", Long.class), version);
    long published =
        sql.fetchOne(
                "select count(*) from bom_version where tenant_id=? and bom_id=? and status='PUBLISHED'",
                tenantId, id)
            .get(0, Long.class);
    check(
        published == 0,
        "这个 BOM 已经发布过 "
            + published
            + " 个版本，不能删除：08 加工单已按当时的版本领过料，删掉 BOM 会让"
            + "「这批料当时按哪一版算的」无从查证。要停止使用请归档，而不是删除");
    long drafts =
        sql.fetchOne(
                "select count(*) from bom_version where tenant_id=? and bom_id=? and status='DRAFT'",
                tenantId, id)
            .get(0, Long.class);
    check(
        drafts == 0,
        "这个 BOM 还有 " + drafts + " 个草稿版本，请先删掉草稿再建新的：草稿是待办不是历史，"
            + "但连它一起删掉会让「这个 BOM 当时规划过哪些版本」无从查证");
    try {
      sql.execute("delete from bom where tenant_id=? and id=? and version=?", tenantId, id, version);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BomDeleted");
  }

  private BomRow bomRow(Record r) {
    String status = r.get("status", String.class);
    return new BomRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("output_sku_id", UUID.class),
        r.get("output_sku_code", String.class),
        r.get("output_sku_name", String.class),
        status,
        BomRules.bomStatusLabel(status),
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

  // ================================================================ 版本

  /** BOM 版本列表（跨 BOM）：分页 + 关键词 + 状态 + 具体 BOM。 */
  public BomVersionPage versions(BomVersionQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where v.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BomRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(" and (lower(v.version_no) like ? escape '!' or lower(v.name) like ? escape '!'"
          + " or lower(b.code) like ? escape '!' or lower(s.code) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String status = BomRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      String problem = BomRules.statusProblem(status);
      check(problem == null, problem);
      where.append(" and v.status=?");
      binds.add(status);
    }
    if (query.bomId() != null) {
      where.append(" and v.bom_id=?");
      binds.add(query.bomId());
    }
    long total =
        sql.fetchOne(
                "select count(*) from bom_version v join bom b"
                    + " on b.tenant_id=v.tenant_id and b.id=v.bom_id"
                    + " join sku s on s.tenant_id=b.tenant_id and s.id=b.output_sku_id" + where,
                binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new BomVersionPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            VERSION_SELECT + where + " order by v.effective_from desc, v.version_no limit ? offset ?",
            pageBinds.toArray());
    return new BomVersionPage(
        versionRows(rows, Instant.now()), page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** BOM 版本详情：版本 + 组件清单 + 现在能不能改以及为什么。 */
  public BomVersionDetail version(UUID id) {
    UUID tenantId = currentTenantId();
    var r = sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id);
    if (r == null) throw notFound("BOM 版本不存在");
    List<BomComponentRow> components = components(tenantId, id);
    boolean editable = "DRAFT".equals(r.get("status", String.class));
    return new BomVersionDetail(
        versionRows(List.of(r), Instant.now()).get(0),
        components,
        totalUnitQuantity(components),
        editable,
        editable
            ? "草稿可以随便改：改完发布，发布后这一版就冻结了"
            : "已发布的版本不可修改（改名、改版本号、改生效起点、改组件一律拒绝）——"
                + "08 加工单领的料是按当时的单位用量与损耗率算的，改了就让"
                + "「这批料当时为什么是这个数」无从查证。要改内容请新建一个版本并让它接替本版；"
                + "唯一还能做的是把生效终点收紧到未来（提前停用本版），那不会改写历史");
  }

  /** 某个 BOM 的版本列表。 */
  public List<BomVersionSummary> versionsOf(UUID bomId) {
    return bom(bomId).versions();
  }

  /** 建草稿版本。名称、版本号、生效区间都是版本自己的属性。 */
  public BomVersionRow createVersion(AccessService.Access actor, UUID bomId, BomVersionInput input) {
    UUID tenantId = actor.tenantId();
    requireActiveBom(tenantId, bomId);
    String versionNo = BomRules.normalizeVersionNo(input.versionNo());
    String name = BomRules.normalize(input.name());
    String note = BomRules.normalize(input.note());
    checkRule(BomRules.versionNoProblem(versionNo));
    checkRule(BomRules.nameProblem(name));
    checkRule(BomRules.noteProblem(note));
    Instant from = BomRules.parseInstant(input.effectiveFrom(), "生效起点");
    Instant to = BomRules.parseInstant(input.effectiveTo(), "生效终点");
    checkRule(BomRules.rangeProblem(from, to));
    UUID newId = insertDraft(tenantId, bomId, versionNo, name, from, to, note);
    audit(actor, newId, "BomVersionCreated");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, newId));
  }

  private UUID insertDraft(
      UUID tenantId,
      UUID bomId,
      String versionNo,
      String name,
      Instant from,
      Instant to,
      String note) {
    UUID newId = Ids.next();
    try {
      // 显式 cast：OffsetDateTime 直接绑 timestamptz 参数，不经过 Timestamp.valueOf
      // （那会按 JVM 默认时区二次偏移，静默错 -28800s 且不报错）。
      sql.execute(
          "insert into bom_version(id,tenant_id,bom_id,version_no,name,effective_from,effective_to,status,note,version)"
              + " values(?,?,?,?,?,?::timestamptz,?::timestamptz,'DRAFT',?,0)",
          newId,
          tenantId,
          bomId,
          versionNo,
          name,
          utcOf(from),
          to == null ? null : utcOf(to),
          note);
    } catch (DataAccessException e) {
      String message = rootMessage(e);
      throw message.contains("bom_version_tenant_id_bom_id_version_no_key")
              || message.contains("同 BOM 同版本号")
          ? conflict(
              "这个 BOM 下已经有版本「"
                  + versionNo
                  + "」：同 BOM 同版本号只能有一条，两条时「v2 是哪一个」无法回答，"
                  + "而生效区间判定会两边都算数")
          : conflict(message);
    }
    return newId;
  }

  /** 改草稿版本。已发布版本一律拒绝（服务层给原因，DB 触发器兜底）。 */
  public BomVersionRow updateVersion(AccessService.Access actor, UUID id, BomVersionInput input) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,effective_to,version from bom_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("BOM 版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    checkVersion(current.get("version", Long.class), input.version());
    String versionNo = BomRules.normalizeVersionNo(input.versionNo());
    String name = BomRules.normalize(input.name());
    String note = BomRules.normalize(input.note());
    checkRule(BomRules.versionNoProblem(versionNo));
    checkRule(BomRules.nameProblem(name));
    checkRule(BomRules.noteProblem(note));
    Instant from = BomRules.parseInstant(input.effectiveFrom(), "生效起点");
    Instant to = BomRules.parseInstant(input.effectiveTo(), "生效终点");
    checkRule(BomRules.rangeProblem(from, to));
    try {
      int affected =
          sql.execute(
              "update bom_version set version_no=?,name=?,effective_from=?::timestamptz,"
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
    audit(actor, id, "BomVersionUpdated");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /**
   * 发布版本。
   *
   * <p>三道关，按「先说最根本的那条」排序：
   *
   * <ol>
   *   <li><b>组件齐备</b>：至少一个组件。没有组件的 BOM 算不出任何需求量，
   *       08 组装单按它领不出任何料。
   *   <li><b>区间不与已发布版本重叠</b>（规则 R2）：这里先查一遍是为了把「与哪一版撞了」
   *       说清楚，DB 触发器在事务里兜最后一道。BOM 比套装更不能自动裁决 ——
   *       用错一版 BOM 就是领错料、算错成本，而且错在仓库里撤不回来。
   *   <li><b>发布留痕</b>：记发布人与发布时刻（V25 的 CHECK 会兜）。
   * </ol>
   */
  public BomVersionRow publishVersion(AccessService.Access actor, UUID id) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select bom_id,version_no,status,effective_from,effective_to,version"
                + " from bom_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("BOM 版本不存在");
    String versionNo = current.get("version_no", String.class);
    check(
        "DRAFT".equals(current.get("status", String.class)),
        "版本「"
            + versionNo
            + "」已经是已发布状态：发布是单向的，撤回发布会让这一版从 08 加工单与 06 采购建议的依据里消失，"
            + "而加工单已经在仓库里按它领过料了，撤不回来。要改内容请新建一个版本");
    long componentCount =
        sql.fetchOne(
                "select count(*) from bom_component where tenant_id=? and version_id=?",
                tenantId, id)
            .get(0, Long.class);
    checkRule(BomRules.componentCountProblem((int) componentCount));
    Instant from = toInstant(current.get("effective_from", OffsetDateTime.class));
    Instant to = toInstant(current.get("effective_to", OffsetDateTime.class));
    checkNoOverlap(tenantId, current.get("bom_id", UUID.class), id, versionNo, from, to);
    try {
      sql.execute(
          "update bom_version set status='PUBLISHED',published_by=?,published_at=?::timestamptz,"
              + "version=version+1,updated_at=now() where tenant_id=? and id=?",
          actor.userId(), utcNow(), tenantId, id);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BomVersionPublished");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /**
   * 向前关闭生效区间（规则 R5）：把已发布版本的终点收紧到某个仍在未来的时刻。
   *
   * <p>这是已发布版本上<b>唯一</b>合法的写操作，也是唯一让版本机制能运转的操作 ——
   * 若连它都禁掉，第一个开放区间的版本发布后就永远无法被接替。
   */
  public BomVersionRow closeVersion(AccessService.Access actor, UUID id, String effectiveTo) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,effective_to,version from bom_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("BOM 版本不存在");
    String versionNo = current.get("version_no", String.class);
    check(
        "PUBLISHED".equals(current.get("status", String.class)),
        "只有已发布的版本需要关闭生效区间：草稿直接改生效区间就行（草稿还没有生效，谈不上停用）");
    Instant newTo = BomRules.parseInstant(effectiveTo, "生效终点");
    Instant currentTo = toInstant(current.get("effective_to", OffsetDateTime.class));
    String problem = BomRules.forwardCloseProblem(currentTo, newTo, Instant.now());
    check(problem == null, problem);
    try {
      sql.execute(
          "update bom_version set effective_to=?::timestamptz,version=version+1,updated_at=now()"
              + " where tenant_id=? and id=?",
          utcOf(newTo), tenantId, id);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BomVersionClosed");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, id));
  }

  /**
   * 版本复制：把一个已有版本（草稿或已发布）整份复制成一个<b>新草稿</b>。
   *
   * <p>「换一版就整份复制再改几行」是这个模块最常见的工作方式（改一个损耗率、换一个组件），
   * 手工重录 30 行既慢又会漏。复制出来的永远是草稿：源版本可能已发布，
   * 已发布版本的组件被 V25 触发器冻着，复制是为了改出新的一版。
   *
   * <p>{@code effectiveFrom} 默认「此刻」而不是沿用源版本的起点：沿用已发布版本的起点
   * 会直接撞上规则 R2（区间重叠），而且运营要的是「改完这一版从现在起接替」。
   * 需要别的起点就显式传。
   */
  public BomVersionRow copyVersion(AccessService.Access actor, UUID sourceId, String newVersionNo,
      String effectiveFrom) {
    UUID tenantId = actor.tenantId();
    var source = sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, sourceId);
    if (source == null) throw notFound("BOM 版本不存在");
    UUID bomId = source.get("bom_id", UUID.class);
    var bomRow = sql.fetchOne("select status from bom where tenant_id=? and id=?", tenantId, bomId);
    if (bomRow == null) throw notFound("BOM 不存在");
    check(
        !"ARCHIVED".equals(bomRow.get("status", String.class)),
        "这个 BOM 已归档，不能新建版本：归档表示「不再新增」，历史版本仍可读"
            + "（08 加工单要读）。要先建版本请把 BOM 改回启用");
    String versionNo = BomRules.normalizeVersionNo(newVersionNo);
    checkRule(BomRules.versionNoProblem(versionNo));
    Instant from =
        BomRules.parseInstant(effectiveFrom, "生效起点") == null
            ? Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            : BomRules.parseInstant(effectiveFrom, "生效起点");
    // 复制出来的名字带源版本号，免得复制完分不清哪份是哪份。
    String name =
        BomRules.normalize(source.get("name", String.class)) + "（复制自 " + source.get("version_no", String.class) + "）";
    checkRule(BomRules.nameProblem(name));
    UUID newId = insertDraft(tenantId, bomId, versionNo, name, from, null, "");
    // 组件逐行复制，保留单位用量与损耗率 —— 这两个数就是复制这个动作的全部意义。
    var sourceComponents =
        sql.fetch(
            "select sku_id,unit_quantity,scrap_rate,role,note from bom_component"
                + " where tenant_id=? and version_id=? order by id",
            tenantId, sourceId);
    for (Record c : sourceComponents) {
      sql.execute(
          "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity,scrap_rate,role,note)"
              + " values(?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          newId,
          c.get("sku_id", UUID.class),
          c.get("unit_quantity", BigDecimal.class),
          c.get("scrap_rate", BigDecimal.class),
          c.get("role", String.class),
          c.get("note", String.class));
    }
    audit(actor, newId, "BomVersionCopied");
    return versionRow(sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, newId));
  }

  /** 删草稿版本。已发布版本由 V25 触发器拒绝（并说明为什么）。 */
  public void deleteVersion(AccessService.Access actor, UUID id, long version) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne("select version_no,status,version from bom_version where tenant_id=? and id=?",
            tenantId, id);
    if (current == null) throw notFound("BOM 版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    try {
      sql.execute(
          "delete from bom_version where tenant_id=? and id=? and version=?", tenantId, id, version);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BomVersionDeleted");
  }

  /**
   * 当前生效版本（规则 R4）。这是 06 采购建议与 08 加工单取版本的读形状。
   */
  public BomEffectiveView effectiveVersion(UUID bomId, String at) {
    UUID tenantId = currentTenantId();
    var bomRow =
        sql.fetchOne(
            "select code,output_sku_id from bom where tenant_id=? and id=?", tenantId, bomId);
    if (bomRow == null) throw notFound("BOM 不存在");
    var outputSku =
        sql.fetchOne(
            "select code from sku where tenant_id=? and id=?",
            tenantId,
            bomRow.get("output_sku_id", UUID.class));
    Instant moment = BomRules.parseInstant(at, "查询时刻");
    // 不传 at 就是查「此刻」。空值默认成现在而不是让 moment 变成 null：
    // 「不传参数」是「查此刻」的最常见写法，让它 NPE 等于把最常用的调用方式变成 500。
    if (moment == null) moment = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Record row = effectiveVersionRow(tenantId, bomId, moment);
    String code = bomRow.get("code", String.class);
    String outputCode = outputSku == null ? null : outputSku.get("code", String.class);
    UUID outputSkuId = bomRow.get("output_sku_id", UUID.class);
    if (row == null) {
      return new BomEffectiveView(
          bomId,
          code,
          outputSkuId,
          outputCode,
          moment.toString(),
          false,
          null,
          null,
          null,
          List.of(),
          "在 "
              + moment
              + " 这一刻这个 BOM 没有生效版本：可能只有草稿，或已发布版本的区间都还没到。"
                  + "08 加工单取不到版本，所以这一刻按这份 BOM 领不出料。"
                  + "这不是「查不到」而是真实状态 —— 用不带 at 的请求查「此刻」是最常用的一种情况");
    }
    UUID versionId = row.get("id", UUID.class);
    return new BomEffectiveView(
        bomId,
        code,
        outputSkuId,
        outputCode,
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

  /** 查这个 BOM 在 {@code at} 这一刻生效的是哪个已发布版本行（没有则 null）。 */
  private Record effectiveVersionRow(UUID tenantId, UUID bomId, Instant at) {
    // 判定放在 SQL 里（半开区间比较），判定口径与 V25 触发器完全一致：
    // Java 侧再用 BomRules.covers 复核一次，两边判定必须给出同一个答案。
    return sql.fetchOne(
        "select * from bom_version where tenant_id=? and bom_id=? and status='PUBLISHED'"
            + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)"
            + " order by effective_from desc limit 1",
        tenantId, bomId, utcOf(at), utcOf(at));
  }

  /** 发布前的重叠预检，把「与哪一版撞了」说清楚（规则 R2）。 */
  private void checkNoOverlap(
      UUID tenantId, UUID bomId, UUID versionId, String versionNo, Instant from, Instant to) {
    var clash =
        sql.fetchOne(
            "select version_no,effective_from,effective_to from bom_version"
                + " where tenant_id=? and bom_id=? and id<>? and status='PUBLISHED'"
                + "   and tstzrange(effective_from,effective_to,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')"
                + " order by effective_from limit 1",
            tenantId,
            bomId,
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
            + "）重叠。重叠一律拒绝不做裁决：用错一版 BOM 就是领错料、算错成本，"
            + "而错在仓库里撤不回来，所以「某个时刻该按哪一版算料」必须只有一个答案。"
            + "请调整本版区间，或让接替版本从「"
            + clash.get("effective_from", OffsetDateTime.class)
            + "」开始");
  }

  // ================================================================ 组件

  /**
   * 某个版本的组件清单。
   *
   * <p>版本不存在时返回 404 而不是空列表：空列表会被读成「这个 BOM 没有组件」，
   * 而真相是「这个 BOM 你看不到」。
   */
  public List<BomComponentRow> components(UUID versionId) {
    UUID tenantId = currentTenantId();
    var exists =
        sql.fetchOne("select id from bom_version where tenant_id=? and id=?", tenantId, versionId);
    if (exists == null) throw notFound("BOM 版本不存在");
    return components(tenantId, versionId);
  }

  private List<BomComponentRow> components(UUID tenantId, UUID versionId) {
    var rows =
        sql.fetch(
            "select c.id,c.version_id,c.sku_id,c.unit_quantity,c.scrap_rate,c.role,c.note,"
                + "s.code as sku_code,s.name as sku_name"
                + " from bom_component c join sku s on s.tenant_id=c.tenant_id and s.id=c.sku_id"
                + " where c.tenant_id=? and c.version_id=? order by s.code",
            tenantId, versionId);
    List<BomComponentRow> items = new ArrayList<>(rows.size());
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
   *   <li>提交里的同一 SKU 出现两次 —— 直接拒（「每个组件要多少」无法回答）；
   *   <li>单位用量 &gt; 0、损耗率在 [0,100] 且精度不超过列声明；
   *   <li>SKU 必须是本租户真实存在的（一次查询问齐，缺哪个点名哪个，而不是靠外键报错）。
   * </ol>
   *
   * <p>已发布版本的组件由 V25 触发器拒绝（这里先给一句人话）。
   */
  public BomVersionDetail replaceComponents(
      AccessService.Access actor, UUID versionId, BomComponentsInput input) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select version_no,status,version from bom_version where tenant_id=? and id=?",
            tenantId, versionId);
    if (current == null) throw notFound("BOM 版本不存在");
    checkEditable(current.get("status", String.class), current.get("version_no", String.class));
    checkVersion(current.get("version", Long.class), input.version());

    List<BomComponentInput> components = input.components();
    checkRule(BomRules.componentCountProblem(components.size()));
    Set<UUID> seen = new LinkedHashSet<>();
    for (BomComponentInput c : components) {
      checkRule(BomRules.quantityProblem(c.unitQuantity()));
      // 损耗率缺省按 0（无损耗）处理：委外加工里「不损耗」是绝大多数情况，必填只会让人填一堆 0。
      checkRule(BomRules.scrapRateProblem(c.scrapRate() == null ? BigDecimal.ZERO : c.scrapRate()));
      check(
          seen.add(c.skuId()),
          "同一个 SKU 在这份组件清单里出现了两次：每个组件「每 1 个成品要多少」只能有一个答案，"
              + "两行时组装单只能取其中一条，而采购建议会按取到的那条走");
    }
    // 一次问齐，别逐条查（组件最多 50 个，逐条查就是 50 次往返）。
    var known =
        sql.fetch(
                "select id from sku where tenant_id=? and id in "
                    + placeholders(components.size()),
                concat(List.of(tenantId), components.stream().map(BomComponentInput::skuId).toList())
                    .toArray())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .collect(java.util.stream.Collectors.toSet());
    List<String> missing = new ArrayList<>();
    for (BomComponentInput c : components) {
      if (!known.contains(c.skuId())) missing.add(c.skuId().toString());
    }
    check(
        missing.isEmpty(),
        "这些组件 SKU 在本租户不存在：" + String.join("、", missing)
            + "。BOM 组件必须指向真实存在的 SKU，否则组装单按它领料时领的是一个不存在的东西");

    sql.execute("delete from bom_component where tenant_id=? and version_id=?", tenantId, versionId);
    for (BomComponentInput c : components) {
      sql.execute(
          "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity,scrap_rate,role,note)"
              + " values(?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          versionId,
          c.skuId(),
          c.unitQuantity(),
          c.scrapRate() == null ? BigDecimal.ZERO : c.scrapRate(),
          BomRules.normalize(c.role()),
          BomRules.normalize(c.note()));
    }
    audit(actor, versionId, "BomComponentsReplaced");
    return version(versionId);
  }

  private BomComponentRow componentRow(Record r) {
    return new BomComponentRow(
        r.get("id", UUID.class),
        r.get("version_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("unit_quantity", BigDecimal.class),
        r.get("scrap_rate", BigDecimal.class),
        r.get("role", String.class),
        r.get("note", String.class));
  }

  /** 单位用量合计（BigDecimal，禁浮点）。 */
  private BigDecimal totalUnitQuantity(List<BomComponentRow> components) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BomComponentRow c : components) sum = sum.add(c.unitQuantity());
    return sum;
  }

  // ================================================================ R7 单层需求量

  /**
   * 单层需求量：生产 {@code outputQuantity} 个成品需要哪些料（规则 R7）。
   *
   * <p><b>只算一层</b>：多级递归展开是 3.5.2.2（BOM 展开）的职责，
   * 它要在展开树上把子 BOM 的成品 SKU 当作父 BOM 的组件继续往下走。
   * 本方法只把本版本<b>直接列出</b>的组件乘一遍，所以返回值里 {@code singleLevel=true}，
   * 页面据此把「这里不含子 BOM 里的料」写在结果上方。
   *
   * <p>草稿版本也能算：这是纯计算不是事实，草稿正是拿来试算的。
   * 但发布过的版本与草稿的算法完全一样 —— 不给两种口径。
   */
  public BomRequirement requirement(UUID versionId, String outputQuantity) {
    UUID tenantId = currentTenantId();
    var r = sql.fetchOne(VERSION_SELECT + " where v.tenant_id=? and v.id=?", tenantId, versionId);
    if (r == null) throw notFound("BOM 版本不存在");
    BigDecimal qty;
    try {
      qty = new BigDecimal(BomRules.normalize(outputQuantity));
    } catch (NumberFormatException e) {
      throw MediaRules.bad("成品数量不是合法数字：" + outputQuantity);
    }
    checkRule(BomRules.outputQuantityProblem(qty));

    List<BomComponentRow> components = components(tenantId, versionId);
    List<BomRequirementLine> lines = new ArrayList<>(components.size());
    BigDecimal total = BigDecimal.ZERO;
    for (BomComponentRow c : components) {
      BigDecimal required = BomRules.requiredQuantity(c.unitQuantity(), c.scrapRate(), qty);
      total = total.add(required);
      lines.add(
          new BomRequirementLine(
              c.skuId(),
              c.skuCode(),
              c.skuName(),
              c.unitQuantity(),
              c.scrapRate(),
              required));
    }
    return new BomRequirement(
        r.get("bom_id", UUID.class),
        versionId,
        r.get("version_no", String.class),
        r.get("output_sku_id", UUID.class),
        r.get("output_sku_code", String.class),
        qty.setScale(0, java.math.RoundingMode.UNNECESSARY),
        lines,
        total,
        true,
        "按「单位用量 × " + qty.toPlainString() + " × (1 + 损耗率/100)」算的，损耗率是百分数（5 = 5%）。"
            + "结果是 6 位小数的精确需求量，**不取整**：取整、补安全库存、判缺口是 3.5.2.2 与 06 的决定"
            + "（蓝图原话「展开结果是建议不是事实」）。"
            + "这里是**单层**结果 —— 如果某个组件的 SKU 本身又是另一个 BOM 的成品，"
            + "它下面的料不在这份清单里，那属于 3.5.2.2 的多级展开。");
  }

  // ================================================================ 倒排查询

  /**
   * 倒排：某个 SKU 被哪些 BOM 版本当组件用着。
   *
   * <p>这是 BOM 相对 3.5.1.2 的独有价值：改组件用量之前必须先看这个方向 ——
   * 06 采购建议算「这批料要买给谁」也读它。每一行都带 {@code editable}，
   * 让「改了这一行会动到哪几个已冻结的历史版本」在列表上一眼可见。
   */
  public BomUsagePage usage(BomUsageQuery query) {
    UUID tenantId = currentTenantId();
    UUID skuId = query.skuId();
    requireSku(tenantId, skuId, "组件");
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where c.tenant_id=? and c.sku_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    binds.add(skuId);
    String keyword = BomRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(" and (lower(b.code) like ? escape '!' or lower(v.version_no) like ? escape '!'"
          + " or lower(v.name) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    // onlyEffective 走**同一条**区间判定（半开区间），不复用 Java 侧的另一套比较：
    // 两边判定必须给出同一个答案，否则「此刻在用」会在页面上和版本列表里对不上。
    // 计数与取行复用**同一段** where + effectiveClause，否则翻页页数会和行数对不上。
    String effectiveClause = "";
    if (Boolean.TRUE.equals(query.onlyEffective())) {
      effectiveClause =
          " and v.status='PUBLISHED' and v.effective_from<=?::timestamptz"
              + " and (v.effective_to is null or ?::timestamptz <v.effective_to)";
      binds.add(utcNow());
      binds.add(utcNow());
    }
    String from =
        " from bom_component c join bom_version v on v.tenant_id=c.tenant_id and v.id=c.version_id"
            + " join bom b on b.tenant_id=v.tenant_id and b.id=v.bom_id";
    var sku = sql.fetchOne("select code,name from sku where tenant_id=? and id=?", tenantId, skuId);
    String skuCode = sku == null ? null : sku.get("code", String.class);
    String skuName = sku == null ? null : sku.get("name", String.class);

    long total =
        sql.fetchOne("select count(*)" + from + where + effectiveClause, binds.toArray())
            .get(0, Long.class);
    if (total == 0) {
      return new BomUsagePage(skuId, skuCode, skuName, List.of(), page, size, 0, 0);
    }
    List<Object> pageArgs = new ArrayList<>(binds);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    var rows =
        sql.fetch(
            "select v.id,v.bom_id,b.code as bom_code,v.version_no,v.name,v.status,"
                + "v.effective_from,v.effective_to,c.unit_quantity,c.scrap_rate "
                + from
                + where
                + effectiveClause
                + " order by v.effective_from desc, b.code, v.version_no limit ? offset ?",
            pageArgs.toArray());
    // 「此刻生效」按 version_id 一次性取回，而不是每行查一次。
    Set<UUID> effective =
        effectiveSet(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    List<BomUsageRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String status = r.get("status", String.class);
      items.add(
          new BomUsageRow(
              r.get("id", UUID.class),
              r.get("bom_id", UUID.class),
              r.get("bom_code", String.class),
              r.get("version_no", String.class),
              status,
              BomRules.statusLabel(status),
              "DRAFT".equals(status),
              r.get("unit_quantity", BigDecimal.class),
              r.get("scrap_rate", BigDecimal.class),
              instant(r.get("effective_from", OffsetDateTime.class)),
              instant(r.get("effective_to", OffsetDateTime.class)),
              effective.contains(r.get("id", UUID.class))));
    }
    return new BomUsagePage(
        skuId,
        skuCode,
        skuName,
        items,
        page,
        size,
        total,
        (int) Math.ceil(total / (double) size));
  }

  // ================================================================ 行映射

  private BomVersionRow versionRow(Record r) {
    return versionRows(List.of(r), Instant.now()).get(0);
  }

  private List<BomVersionRow> versionRows(List<Record> rows, Instant at) {
    if (rows.isEmpty()) return List.of();
    // 「此刻生效」按 version_id 一次性取回，而不是每行查一次。
    Set<UUID> effective = effectiveSet(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    List<BomVersionRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String status = r.get("status", String.class);
      BigDecimal total =
          r.get("total_unit_quantity", BigDecimal.class) == null
              ? BigDecimal.ZERO
              : r.get("total_unit_quantity", BigDecimal.class);
      items.add(
          new BomVersionRow(
              r.get("id", UUID.class),
              r.get("bom_id", UUID.class),
              r.get("bom_code", String.class),
              r.get("output_sku_id", UUID.class),
              r.get("output_sku_code", String.class),
              r.get("output_sku_name", String.class),
              r.get("version_no", String.class),
              r.get("name", String.class),
              status,
              BomRules.statusLabel(status),
              instant(r.get("effective_from", OffsetDateTime.class)),
              instant(r.get("effective_to", OffsetDateTime.class)),
              r.get("published_by", UUID.class),
              instant(r.get("published_at", OffsetDateTime.class)),
              r.get("note", String.class),
              r.get("component_count", Long.class),
              total,
              r.get("scrap_percent", BigDecimal.class),
              effective.contains(r.get("id", UUID.class)),
              "DRAFT".equals(status),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return items;
  }

  /** 哪些版本在 {@code at} 这一刻生效。半开区间判定，与 V25 触发器同口径。 */
  private Set<UUID> effectiveSet(List<UUID> versionIds) {
    Set<UUID> effective = new LinkedHashSet<>();
    if (versionIds.isEmpty()) return effective;
    UUID tenantId = currentTenantId();
    OffsetDateTime now = utcNow();
    var rows =
        sql.fetch(
            "select id,effective_from,effective_to from bom_version where tenant_id=? and status='PUBLISHED'"
                + " and id in "
                + placeholders(versionIds.size())
                + " and effective_from<=?::timestamptz and (effective_to is null or ?::timestamptz <effective_to)",
            concat(concat(List.of(tenantId), versionIds), List.of(now, now)).toArray());
    for (Record r : rows) {
      // SQL 已经判过一遍，这里再用同一个纯函数复核：两边判定必须一致，
      // 不一致说明半开区间的口径在某一边写错了，而那正是「哪一版生效」出错的地方。
      if (BomRules.covers(
          toInstant(r.get("effective_from", OffsetDateTime.class)),
          toInstant(r.get("effective_to", OffsetDateTime.class)),
          now.toInstant())) {
        effective.add(r.get("id", UUID.class));
      }
    }
    return effective;
  }

  private void requireSku(UUID tenantId, UUID skuId, String role) {
    var row = sql.fetchOne("select id from sku where tenant_id=? and id=?", tenantId, skuId);
    check(
        row != null,
        "这个" + role + " SKU 在本租户不存在：BOM 必须指向真实存在的 SKU，"
            + "否则「按这份 BOM 生产/领料」指向的是一个不存在的东西");
  }

  private void requireActiveBom(UUID tenantId, UUID bomId) {
    var row = sql.fetchOne("select status from bom where tenant_id=? and id=?", tenantId, bomId);
    if (row == null) throw notFound("BOM 不存在");
    check(
        !"ARCHIVED".equals(row.get("status", String.class)),
        "这个 BOM 已归档，不能新建版本：归档表示「不再新增」，历史版本仍可读"
            + "（08 加工单要读）。要先建版本请把 BOM 改回启用");
  }

  /** 已发布版本的统一拒绝理由。服务层与 DB 触发器给的是同一件事，只是详略不同。 */
  private static void checkEditable(String status, String versionNo) {
    check(
        "DRAFT".equals(status),
        "版本「"
            + versionNo
            + "」已发布，不可修改：发布后名称、版本号、生效起点与组件一律冻结 —— "
            + "08 加工单领的料是按当时的单位用量与损耗率算的，改了就让"
            + "「这批料当时为什么是这个数」无从查证。要改内容请新建一个版本并让它接替本版；"
            + "唯一还能做的是把生效终点收紧到未来（提前停用本版）");
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
