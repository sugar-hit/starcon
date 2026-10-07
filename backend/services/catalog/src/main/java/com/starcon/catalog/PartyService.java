package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PartyRules.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.6.1.1 往来单位档案：统一业务主体（企业或个人）+ 角色指派（供应商 / 客户 / 承运商）、
 * 状态流转、软删除，以及蓝图点名的输出「往来单位档案 + 关联的角色档案」。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>一个主体一份记录，身份靠角色区分</b>。蓝图那句「同一主体可同时是供应商和客户，
 *       用角色档案区分，不用多份记录」是本模块存在的理由，所以
 *       {@code party} 只有一张表，角色在 {@code party_role} 上，且
 *       {@code UNIQUE(tenant_id,party_id,role_type)} 挡住同角色两条。
 *       界面与列表<b>直接显示角色</b>：如果列表上看不出来，运营就会去建第二条主体 ——
 *       那正是本模块要消灭的形态。
 *   <li><b>可用性判定只有一处实现</b>。{@link PartyRules#usableBlock} 是唯一的
 *       「这个主体能不能被下游选中」判定，列表行、详情、引用候选三个出口都调它。
 *       前端只把原因码翻成话（{@code party-model.ts}），不在浏览器里再判一次。
 *   <li><b>建档与可用是两件事</b>。允许「先建主体、后指派角色」（服务层不阻止建档），
 *       但<b>启用</b>时要求至少一个 ACTIVE 角色（规则 P5）——
 *       被拒的应该是「让它在下游可用」，不是「建档」。与 V16 对「建档不要求凭证、
 *       被拒的应该是刊登」同源。
 *   <li><b>停用不是删除，删除是软删除</b>。停用（{@code status='INACTIVE'}）保留行、
 *       历史单据照常显示；删除打 {@code deleted_at}，行仍在 ——
 *       06 采购单、12 应付、11 售后单将来都会引用这个 id，物理删除会让历史单据
 *       指向一个不存在的行，而「对手方是谁」是财务凭证上必须显示的东西。
 *       本类<b>没有</b>物理删除方法。
 *   <li><b>角色特有字段一概不放这里</b>。账期 / MOQ / 客户分级 / 信用额度 / 接口类型
 *       归 3.6.1.2，它按蓝图要求「每个角色一张 1:1 档案表」落地。
 *       塞进本表就是蓝图点名否决的「一个巨大可空字段表」（理由见 V27 迁移第三节）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 往来单位是商品主数据域的一部分（蓝图卷 03），与品牌（3.3.2.1）、BOM（3.5.2.x）同域；
 * 不新造权限词表 —— 那需要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@Service
public class PartyService {
  /** 单页最大行数。往来单位是租户级主数据，行数量级在千位；超过 500 一页基本是脚本在拉全量。 */
  private static final int MAX_PAGE_SIZE = 200;

  private static final String SELECT_COLUMNS =
      "select p.id,p.code,p.name,p.party_type,p.country_code,p.email,p.phone,p.website,"
          + "p.status,p.status_reason,p.status_changed_at,p.deleted_at,p.version,"
          + "p.created_at,p.updated_at,"
          // 角色聚合：ACTIVE 角色类型集合（列表页要直接显示「同时是供应商和客户」这件事）、
          // 角色总条数与 ACTIVE 条数。三个值都取自同一次查询，避免 N+1。
          + "coalesce((select array_agg(r.role_type order by r.role_type) from party_role r"
          + "  where r.tenant_id=p.tenant_id and r.party_id=p.id and r.status='ACTIVE'),"
          + "  '{}') as roles,"
          + "(select count(*) from party_role r where r.tenant_id=p.tenant_id"
          + "  and r.party_id=p.id) as role_count,"
          + "(select count(*) from party_role r where r.tenant_id=p.tenant_id"
          + "  and r.party_id=p.id and r.status='ACTIVE') as active_role_count";

  private static final String FROM_PARTY = " from party p where p.tenant_id=?";

  /**
   * 「至少有一个 ACTIVE 角色」的子查询。与 {@link PartyRules#usableBlock} 的角色判据一致。
   */
  private static final String HAS_ACTIVE_ROLE =
      "exists (select 1 from party_role r where r.tenant_id=p.tenant_id"
          + " and r.party_id=p.id and r.status='ACTIVE')";

  /**
   * 「这个主体当前可用」的 SQL 表达式：未删除 + ACTIVE + 至少一个 ACTIVE 角色。
   *
   * <p>与 {@link PartyRules#usableBlock} 同一口径、同一优先级。
   * 列表筛选、概览统计与引用候选三个出口都引<b>这一处字符串</b>，
   * 而不是各写一遍 —— 两处各写一遍的症状是「概览说 20 个可用、候选列表只给 12 个」，
   * 而运营没法判断该信哪个。
   */
  private static final String USABLE =
      "(p.deleted_at is null and p.status='ACTIVE' and " + HAS_ACTIVE_ROLE + ")";

  /**
   * 「状态可用却没挂任何角色」—— 规则 P5 那一侧，也是 3.9.2.2 僵尸主数据清理的候选来源。
   *
   * <p>刻意<b>不是</b> {@code not USABLE}：那个写法会把**已软删除**的主体也算进来
   * （软删除的主体当然不满足 USABLE），而概览里的 noRoleCount 只统计未删除的。
   * 两处一旦用不同表达式，列表页与概览页的同一个数字就会对不上，
   * 而界面上没人能判断该信哪个。
   */
  private static final String NO_ACTIVE_ROLE =
      "(p.deleted_at is null and p.status='ACTIVE' and not " + HAS_ACTIVE_ROLE + ")";

  private final DSLContext sql;

  public PartyService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 列表（分页 + 搜索 + 筛选）

  /**
   * 往来单位列表：关键词搜索（编码 / 名称 / 邮箱 / 电话）+ 主体类型 / 状态 / 角色 / 国家筛选。
   *
   * <p>关键词覆盖邮箱与电话：运营收到对方公司的一封邮件后要能直接搜到，
   * 而那封邮件里通常只有邮箱、没有公司名。通配符按 {@code escape '!'} 转义，
   * 否则运营搜 {@code 100%} 会变成「匹配所有」而他自己不知道。
   *
   * <p>软删除行默认不出现（{@code includeDeleted=true} 才带上）——
   * 软删除是默认语义而不是可选项，见 {@code V27} 第一节的 {@code deleted_at}。
   */
  public PartyPage list(PartyQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    // SELECT_COLUMNS 的子查询全部锚在 p 上，没有独立占位符；
    // 所以主查询的绑定顺序就是 [tenantId] + 筛选 binds + limit/offset。
    List<Object> base = new ArrayList<>();
    base.add(currentTenantId());

    long total =
        sql.fetchOne("select count(*)" + FROM_PARTY + sqlWhere, concat(base, binds).toArray())
            .get(0, Long.class);
    long activeCount =
        sql.fetchOne(
                "select count(*)" + FROM_PARTY + " and p.deleted_at is null and p.status='ACTIVE'",
                currentTenantId())
            .get(0, Long.class);
    long blacklistedCount =
        sql.fetchOne(
                "select count(*)" + FROM_PARTY + " and p.deleted_at is null and p.status='BLACKLISTED'",
                currentTenantId())
            .get(0, Long.class);
    // 「有多少条可用状态却没挂任何角色」：3.9.2.2 僵尸主数据清理的候选来源。
    // 与 overview 同一套表达式（NO_ACTIVE_ROLE），两处各写一遍的话两个数字会悄悄对不上。
    long noRoleCount =
        sql.fetchOne("select count(*)" + FROM_PARTY + " and " + NO_ACTIVE_ROLE, currentTenantId())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            SELECT_COLUMNS + FROM_PARTY + sqlWhere + " order by p.code,p.id limit ? offset ?",
            concat(base, concat(binds, List.of(size, (long) page * size))).toArray());
    List<PartyRow> items = rows.stream().map(this::toRow).toList();
    return new PartyPage(
        items,
        total,
        page,
        size,
        activeCount,
        blacklistedCount,
        noRoleCount,
        "统计（可用 / 拉黑 / 无角色）不受筛选条件影响，方便对照「筛出来的几条」与「全量有多少」。"
            + "软删除的主体默认不显示（includeDeleted=true 才带上）。"
            + "角色的判定与 PartyRules.usableBlock 同一口径，前端不重算。");
  }

  private void conditions(PartyQuery query, List<String> where, List<Object> binds) {
    if (!Boolean.TRUE.equals(query.includeDeleted())) where.add("p.deleted_at is null");
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(p.code ilike ? escape '!' or p.name ilike ? escape '!'"
              + " or p.email ilike ? escape '!' or p.phone ilike ? escape '!')");
      String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
      // 四列各自绑一次：四个 OR 分支各自能用上一条 pg_trgm GIN 索引（见 V27 第四节）。
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.partyType() != null && !query.partyType().isBlank()) {
      String problem = partyTypeProblem(query.partyType());
      CatalogService.check(problem == null, problem);
      where.add("p.party_type=?");
      binds.add(query.partyType().trim());
    }
    if (query.status() != null && !query.status().isBlank()) {
      String problem = statusProblem(query.status());
      CatalogService.check(problem == null, problem);
      where.add("p.status=?");
      binds.add(query.status().trim());
    }
    if (query.countryCode() != null && !query.countryCode().isBlank()) {
      String problem = countryProblem(query.countryCode());
      CatalogService.check(problem == null, problem);
      where.add("p.country_code=?");
      binds.add(normalizeCountry(query.countryCode()));
    }
    if (query.roleType() != null && !query.roleType().isBlank()) {
      String problem = roleTypeProblem(query.roleType());
      CatalogService.check(problem == null, problem);
      // 按角色筛主体：只认 ACTIVE 的角色指派。
      // 「这个主体的供应商身份已经停用」时它不该出现在「谁是供应商」的筛选结果里 ——
      // 那会让运营挑到一个当下用不了的供应商。
      where.add(
          "exists (select 1 from party_role r where r.tenant_id=p.tenant_id"
              + " and r.party_id=p.id and r.status='ACTIVE' and r.role_type=?)");
      binds.add(normalizeRoleType(query.roleType()));
    }
    if (query.usable() != null) {
      where.add(query.usable() ? USABLE : "not " + USABLE);
    }
  }

  private PartyRow toRow(Record r) {
    List<String> roles = roleList(r);
    int activeRoleCount = intOf(r.get("active_role_count", Long.class));
    boolean deleted = r.get("deleted_at", OffsetDateTime.class) != null;
    String block = usableBlock(new UsableInputs(deleted, r.get("status", String.class), activeRoleCount));
    return new PartyRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("party_type", String.class),
        partyTypeLabel(r.get("party_type", String.class)),
        r.get("country_code", String.class),
        r.get("email", String.class),
        r.get("phone", String.class),
        r.get("status", String.class),
        statusLabel(r.get("status", String.class)),
        roles,
        roleSummary(roles),
        intOf(r.get("role_count", Long.class)),
        activeRoleCount,
        block == null,
        block,
        usableBlockMessage(block, r.get("name", String.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  // ============================================================ 详情

  /** 往来单位详情：主体 + 角色指派 + 可用性判定，同一时刻的一致快照。 */
  public PartyDetail detail(UUID id) {
    var row = partyRow(id);
    List<PartyRole> roles = rolesOf(id);
    List<String> roleTypes =
        roles.stream().filter(r -> "ACTIVE".equals(r.status())).map(PartyRole::roleType).toList();
    boolean deleted = row.get("deleted_at", OffsetDateTime.class) != null;
    int activeRoleCount = (int) roleTypes.size();
    String name = row.get("name", String.class);
    String block = usableBlock(new UsableInputs(deleted, row.get("status", String.class), activeRoleCount));
    return new PartyDetail(
        row.get("id", UUID.class),
        row.get("code", String.class),
        name,
        row.get("party_type", String.class),
        partyTypeLabel(row.get("party_type", String.class)),
        row.get("country_code", String.class),
        row.get("email", String.class),
        row.get("phone", String.class),
        row.get("website", String.class),
        row.get("status", String.class),
        statusLabel(row.get("status", String.class)),
        row.get("status_reason", String.class),
        instant(row.get("status_changed_at", OffsetDateTime.class)),
        row.get("version", Long.class),
        roles,
        roleSummary(roleTypes),
        activeRoleCount,
        deleted,
        block == null,
        block,
        usableBlockMessage(block, name),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  /** 角色指派列表：按角色类型固定顺序输出，前端 key 不抖动。 */
  public List<PartyRole> rolesOf(UUID partyId) {
    partyRow(partyId);
    var rows =
        sql.fetch(
            "select id,party_id,role_type,status,note,version,created_at,updated_at from party_role"
                + " where tenant_id=? and party_id=? order by role_type,id",
            currentTenantId(), partyId);
    return rows.stream().map(PartyService::toRole).toList();
  }

  private static PartyRole toRole(Record r) {
    String roleType = r.get("role_type", String.class);
    String status = r.get("status", String.class);
    return new PartyRole(
        r.get("id", UUID.class),
        r.get("party_id", UUID.class),
        roleType,
        roleLabel(roleType),
        status,
        "ACTIVE".equals(status) ? "使用中" : "已停用",
        emptyToNull(r.get("note", String.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  // ============================================================ 写入：主体

  /**
   * 建主体或改主体（id 为空即新建），走 version 乐观锁。
   *
   * <p><b>roles 是整体替换</b>（与 BrandService 的 categories、BomVersionService 的
   * components 同一口径）：提交什么就等于什么，{@code null} 等价于「清空全部角色」。
   * 增量语义下前端必须先取回当前全集再算差集，少发一条就是静默的身份丢失。
   *
   * <p>编码建后不可改（规则 P1）：它是 06 采购单 / 12 应付 / 11 售后单对齐这个主体的锚点。
   */
  public PartySaved save(AccessService.Access actor, UUID id, PartyInput input) {
    String problem =
        firstNonNull(
            codeProblem(input.code()),
            nameProblem(input.name()),
            partyTypeProblem(input.partyType()),
            countryProblem(input.countryCode()),
            contactProblem(input.email(), input.phone(), input.website()),
            statusProblem(input.status()),
            rolesProblem(input.roles()));
    CatalogService.check(problem == null, problem);

    String code = normalizeCode(input.code());
    String status = normalizeStatus(input.status());
    String name = normalizeName(input.name());
    String email = emptyToNull(input.email());
    String phone = emptyToNull(normalizePhone(input.phone()));
    String website = emptyToNull(input.website());
    UUID tenantId = actor.tenantId();
    // 提交的角色集合（null == 空），P5 的判定基于它 —— 而不是基于库里现有的，
    // 因为整体替换之后库里会变成的就是它。
    List<PartyRoleInput> roles = input.roles() == null ? List.of() : input.roles();
    int submittedActiveRoles = submittedActiveRoleCount(roles);

    if (id == null) {
      if (sql.fetchOne("select id from party where code=?", code) != null)
        throw conflict("往来单位编码已存在：" + code);
      String needRole = activateRoleRequirementProblem(status, submittedActiveRoles);
      CatalogService.check(needRole == null, needRole);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into party(id,tenant_id,code,name,party_type,country_code,email,phone,website,"
                + "status,status_reason,status_changed_at) values(?,?,?,?,?,?,?,?,?,?,?,now())",
            newId,
            tenantId,
            code,
            name,
            input.partyType().trim(),
            normalizeCountry(input.countryCode()),
            email,
            phone,
            website,
            status,
            "");
      } catch (DataAccessException e) {
        throw conflict("往来单位编码已被并发创建或占用，请刷新后重试");
      }
      replaceRoles(tenantId, newId, roles, status, List.of());
      audit(actor, newId, "PartySaved");
      return savedResult(newId);
    }

    var row = partyRow(id);
    String storedCode = row.get("code", String.class);
    String storedStatus = row.get("status", String.class);
    // 不可改字段给 400（提交的内容不合法），409 留给 version 乐观锁与唯一键冲突 ——
    // 与 3.3.2.1 的品牌编码、3.3.1.1 的分类编码同一口径。
    CatalogService.check(codeChangeProblem(storedCode, code) == null, codeChangeProblem(storedCode, code));
    // 状态流转只在**真的改了**时判定。
    // 保存是「编辑」不是「流转」：前端把当前状态原样带回来是每次编辑的常态，
    // 拿它去判 P4 会让「改个名字」报「状态没有变化」。
    // 「状态没有变化」这个自查只属于 setStatus 端点 —— 那是一次明确的状态动作。
    if (!storedStatus.equals(status)) {
      String transition = transitionProblem(storedStatus, status, row.get("status_reason", String.class));
      CatalogService.check(transition == null, transition);
    }
    String needRole = activateRoleRequirementProblem(status, submittedActiveRoles);
    CatalogService.check(needRole == null, needRole);

    int updated;
    try {
      updated =
          sql.execute(
              "update party set name=?,party_type=?,country_code=?,email=?,phone=?,website=?,"
                  + "status=?,version=version+1,updated_at=now() where id=? and version=?",
              name,
              input.partyType().trim(),
              normalizeCountry(input.countryCode()),
              email,
              phone,
              website,
              status,
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("往来单位保存失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "往来单位已被他人修改，请刷新后重试");
    replaceRoles(tenantId, id, roles, status, existingRoleTypes(id));
    audit(actor, id, "PartySaved");
    return savedResult(id);
  }

  /**
   * 状态流转（规则 **P4** / **P5**）。
   *
   * <p>独立端点而不是复用保存：流转只改状态，走保存路径会让人以为「改状态要重传全部字段」，
   * 而那正好是并发覆盖的来源。理由由请求体带（进出拉黑必填）。
   */
  public PartySaved setStatus(AccessService.Access actor, UUID id, PartyStatusInput input) {
    var row = partyRow(id);
    String from = row.get("status", String.class);
    String problem = transitionProblem(from, input.status(), input.reason());
    CatalogService.check(problem == null, problem);
    String to = normalizeStatus(input.status());
    if ("ACTIVE".equals(to)) {
      int activeRoles = activeRoleCount(rolesOf(id));
      String needRole = activateRoleRequirementProblem(to, activeRoles);
      CatalogService.check(needRole == null, needRole);
    }
    String reason = emptyToNull(input.reason());
    int updated =
        sql.execute(
            "update party set status=?,status_reason=?,status_changed_at=now(),"
                + "version=version+1,updated_at=now() where id=? and version=?",
            to, reason == null ? "" : reason, id, input.version());
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "往来单位已被他人修改，请刷新后重试");
    audit(actor, id, "PartyStatusChanged");
    return savedResult(id);
  }

  /**
   * 软删除（规则 **P7**）：打 {@code deleted_at}，行仍在。
   *
   * <p>删除前必须解掉全部角色，否则会留下「角色还在、主体不见了、详情页 404」这种
   * 没人能解释的状态，运营也收拾不干净。报错逐个点名还挂着哪些角色。
   *
   * <p>刻意<b>没有</b>物理删除：06 采购单、12 应付、11 售后单将来都会引用这个 id，
   * 删掉行之后那些单据只能显示一个 uuid，而「对手方是谁」是财务凭证上必须显示的东西。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = partyRow(id);
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "往来单位已被他人修改，请刷新后重试");
    if (row.get("deleted_at", OffsetDateTime.class) != null) throw CatalogService.missing();
    List<PartyRole> roles = rolesOf(id);
    String problem = deleteProblem(roles.stream().map(PartyRole::roleType).toList());
    CatalogService.check(problem == null, problem);
    sql.execute(
        "update party set deleted_at=now(),deleted_by=?,version=version+1,updated_at=now()"
            + " where id=?",
        actor.userId(), id);
    audit(actor, id, "PartyDeleted");
  }

  /**
   * 指派一个新角色（{@code roleId} 为空即新建）。
   *
   * <p>这是蓝图「输入：角色指派」的那个动作。**同一主体同一角色只允许一条**
   * （DB 的 {@code UNIQUE(tenant_id,party_id,role_type)} 兜底，这里给可读的 409）——
   * 重复指派的症状是「采购单挂了这个供应商」与「供应商档案里有两条」对不上，
   * 而两者没有任何稳定键可以互相定位。
   *
   * <p>停用 / 拉黑的主体不允许<b>新增</b>角色（规则 P6）；解指派与改备注仍允许，
   * 因为清理停用主体的角色正是停用之后要做的动作。
   */
  public PartySaved saveRole(AccessService.Access actor, UUID partyId, UUID roleId, PartyRoleEditInput input) {
    var row = partyRow(partyId);
    String partyStatus = row.get("status", String.class);
    String problem =
        firstNonNull(roleTypeProblem(input.roleType()), roleStatusProblem(input.status()), roleNoteProblem(input.note()));
    CatalogService.check(problem == null, problem);
    String roleType = normalizeRoleType(input.roleType());
    String roleStatus = input.status().trim();

    if (roleId == null) {
      boolean already = exists(partyId, roleType);
      String blocked = assignRoleProblem(partyStatus, roleType, already);
      CatalogService.check(blocked == null, blocked);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into party_role(id,tenant_id,party_id,role_type,status,note) values(?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            partyId,
            roleType,
            roleStatus,
            normalizeText(input.note()));
      } catch (DataAccessException e) {
        throw conflict("该主体已经指派过 " + roleLabel(roleType) + " 角色，请直接编辑那一条而不是重复指派");
      }
      audit(actor, partyId, "PartyRoleAssigned");
      return savedResult(partyId);
    }

    var existing = roleRow(partyId, roleId);
    String storedType = existing.get("role_type", String.class);
    CatalogService.check(
        storedType.equals(roleType),
        "角色类型建后不可修改：主体可以同时是供应商和客户，但每个角色类型只有一条档案。"
            + "要换角色请先解指派现有角色再指派新的");
    // 从 INACTIVE 重新启用角色，等于在非 ACTIVE 主体上「新增」一个可用身份，
    // 所以 P6 同样适用（改备注与解指派不受限）。
    if ("ACTIVE".equals(roleStatus) && !"ACTIVE".equals(existing.get("status", String.class))) {
      String blocked = assignRoleProblem(partyStatus, roleType, false);
      CatalogService.check(blocked == null, blocked);
    }
    int updated;
    try {
      updated =
          sql.execute(
              "update party_role set status=?,note=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              roleStatus,
              normalizeText(input.note()),
              roleId,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("角色保存失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "角色指派已被他人修改，请刷新后重试");
    audit(actor, partyId, "PartyRoleSaved");
    return savedResult(partyId);
  }

  /**
   * 解除一个角色指派（物理删除这一行）。
   *
   * <p>与主体不同，角色指派允许物理删除：它是「这个主体扮演这个身份」的<b>当前事实</b>，
   * 没有「曾经有过供应商身份、后来解除了」这种回溯需求 ——
   * 与 V16 对品牌授权凭证「撤销不是删除」的理由正好相反（那边要回溯侵权责任）。
   * 已发生单据里的角色是单据自己的冻结快照，不靠这张表回溯。
   */
  public PartySaved removeRole(AccessService.Access actor, UUID partyId, UUID roleId, long version) {
    var existing = roleRow(partyId, roleId);
    if (existing.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "角色指派已被他人修改，请刷新后重试");
    sql.execute("delete from party_role where id=? and version=?", roleId, version);
    audit(actor, partyId, "PartyRoleRemoved");
    return savedResult(partyId);
  }

  // ============================================================ 引用候选（下游下拉）

  /**
   * 06 采购 / 11 售后 / 12 财务选往来单位时的引用候选。
   *
   * <p>与列表端点的区别是<b>语义</b>：列表是给运营维护用的（带停用项、带已删除项开关），
   * 候选是给下游单据用的 —— 默认只给<b>可用</b>的主体（ACTIVE 且至少一个 ACTIVE 角色）。
   *
   * <p>{@code includeBlocked=true} 时连不可用的也返回（带原因码），用于
   * 「历史单据上的这个供应商为什么现在选不到了」要显示出来，而不是让它凭空消失。
   */
  public List<PartyRow> candidates(String roleType, String keyword, boolean includeBlocked) {
    // 用 list 的 SQL（同一套筛选与同一个 USABLE 表达式）而不是取一页再在内存里过滤：
    // 内存过滤会让「第 1 页 20 条里有 12 条不可用」的空结果说谎 —— 明明后面还有可用主体，
    // 而单据的选择器会显示「没有可用的供应商」。那正是「假分页」的一种。
    PartyQuery query =
        new PartyQuery(keyword, null, null, roleType, null, null, includeBlocked ? null : Boolean.TRUE, 0, MAX_PAGE_SIZE);
    // 候选上限就是单页上限，单据的选择器不需要更多。
    return list(query).items();
  }

  // ============================================================ 概览

  /** 概览：规模、状态分布、角色分布与两个治理缺口指标。 */
  public PartyOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select count(*) filter (where deleted_at is null) as party_count,"
                + "count(*) filter (where deleted_at is null and status='ACTIVE') as active_count,"
                + "count(*) filter (where deleted_at is null and status='INACTIVE') as inactive_count,"
                + "count(*) filter (where deleted_at is null and status='BLACKLISTED') as blacklisted_count,"
                + "count(*) filter (where deleted_at is not null) as deleted_count,"
                + "count(*) filter (where deleted_at is null and party_type='ORGANIZATION') as organization_count,"
                + "count(*) filter (where deleted_at is null and party_type='INDIVIDUAL') as individual_count"
                + " from party where tenant_id=?",
            tenantId);
    // 角色分布按「至少有一个 ACTIVE 该角色的主体」计，与列表按角色筛选同一口径。
    long supplier = roleDistinct(tenantId, "SUPPLIER");
    long customer = roleDistinct(tenantId, "CUSTOMER");
    long carrier = roleDistinct(tenantId, "CARRIER");
    // 「同时是供应商和客户」的主体数：ACTIVE 角色数 > 1 的主体。
    // 判据是角色**条数**而不是角色**类型数** —— party_role 上同一类型本来就只有一行
    // （UNIQUE(tenant_id,party_id,role_type)），所以两者等价，按条数写更直白。
    long multiRole =
        sql.fetchOne(
                "select count(*) from ("
                    + "select p.tenant_id,p.id from party p join party_role r"
                    + "  on r.tenant_id=p.tenant_id and r.party_id=p.id"
                    + " where p.tenant_id=? and p.deleted_at is null and p.status='ACTIVE'"
                    + " and r.status='ACTIVE'"
                    + " group by p.tenant_id,p.id having count(*)>1) x",
                tenantId)
            .get(0, Long.class);
    // 「可用」= USABLE 表达式的同一口径（规则 P5，与 usableBlock 同一处）。
    long usable =
        sql.fetchOne("select count(*)" + FROM_PARTY + " and " + USABLE, tenantId).get(0, Long.class);
    // noRoleCount 与 list 的 noRoleCount 引**同一处表达式**，而不是用 activeCount - usable 推算 ——
    // 推算式在有人改了其中一条的状态条件时会静默偏移，而界面上看不出。
    long noRole =
        sql.fetchOne("select count(*)" + FROM_PARTY + " and " + NO_ACTIVE_ROLE, tenantId)
            .get(0, Long.class);
    return new PartyOverview(
        counts.get("party_count", Long.class),
        counts.get("active_count", Long.class),
        counts.get("inactive_count", Long.class),
        counts.get("blacklisted_count", Long.class),
        counts.get("deleted_count", Long.class),
        counts.get("organization_count", Long.class),
        counts.get("individual_count", Long.class),
        supplier,
        customer,
        carrier,
        multiRole,
        usable,
        noRole,
        "「同时是供应商和客户」的主体数（multiRoleCount）是本模块最该被盯住的数字之一："
            + "它证明同一主体一份记录就够用（蓝图实体目录 §3.5），它下降说明有人开始建重复主体了。"
            + "usableCount 是当前能被 06 采购 / 11 售后 / 12 财务选中的主体数；"
            + "noRoleCount 是「状态可用却没挂任何角色」的那些，是 3.9.2.2 僵尸主数据清理的候选来源。");
  }

  private long roleDistinct(UUID tenantId, String roleType) {
    return sql.fetchOne(
            "select count(distinct p.id) from party p join party_role r"
                + " on r.tenant_id=p.tenant_id and r.party_id=p.id"
                + " where p.tenant_id=? and p.deleted_at is null and p.status='ACTIVE'"
                + " and r.status='ACTIVE' and r.role_type=?",
            tenantId, roleType)
        .get(0, Long.class);
  }

  // ============================================================ 工具

  private PartySaved savedResult(UUID id) {
    var detail = detail(id);
    return new PartySaved(
        detail.id(),
        detail.version(),
        detail.status(),
        detail.statusLabel(),
        detail.roles().stream()
            .filter(r -> "ACTIVE".equals(r.status()))
            .map(PartyRole::roleType)
            .toList(),
        detail.activeRoleCount());
  }

  /**
   * 取一行主体。
   *
   * <p>刻意<b>不</b>过滤 {@code deleted_at}：软删除之后详情端点仍要能读到这行
   * （并带 {@code deleted=true}），否则运营删完回头看一眼只会看到凭空消失的条目。
   * 列表端点才是默认隐藏的那一层。
   *
   * <p>不加 {@code tenant_id} 条件：RLS（FORCE）已经把别的租户的行挡掉了，
   * 加不加语义等价，但加了就多一次索引查找。与 {@code BrandService.brandRow} 同一做法。
   */
  private Record partyRow(UUID id) {
    var row = sql.fetchOne("select * from party where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private Record roleRow(UUID partyId, UUID roleId) {
    var row =
        sql.fetchOne(
            "select * from party_role where tenant_id=? and party_id=? and id=?",
            currentTenantId(), partyId, roleId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private boolean exists(UUID partyId, String roleType) {
    return sql.fetchOne(
            "select 1 from party_role where tenant_id=? and party_id=? and role_type=?",
            currentTenantId(), partyId, roleType)
        != null;
  }

  private List<String> existingRoleTypes(UUID partyId) {
    List<String> types = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select role_type from party_role where tenant_id=? and party_id=?",
            currentTenantId(), partyId))
      types.add(r.get("role_type", String.class));
    return types;
  }

  /**
   * 整体替换角色指派。
   *
   * <p>用 {@code on conflict (tenant_id,party_id,role_type) do update} 一条语句同时覆盖
   * 「新增」与「更新」：整体替换要的是「提交集合的最终态」，逐行先删后插会在
   * {@code UNIQUE} 上撞自己（先删把同类型行提前腾空，插回时与已插入的新行相撞），
   * 而先插后删又要多一轮往返。upsert 一次到位，最后再把提交集合里没有的角色删掉。
   *
   * <p>{@code targetStatus} 不是 ACTIVE 时不<b>新增</b>角色类型（规则 P6），但**保留**
   * 已有的角色行：停用主体上的角色要留给运营在重新启用时继续用。
   *
   * @param storedRoles 库里当前已有的角色类型（判断哪些是「新增」）
   */
  private void replaceRoles(
      UUID tenantId,
      UUID partyId,
      List<PartyRoleInput> roles,
      String targetStatus,
      List<String> storedRoles) {
    Set<String> stored = new LinkedHashSet<>(storedRoles);
    for (PartyRoleInput role : roles) {
      String type = normalizeRoleType(role.roleType());
      if (!stored.contains(type) && !"ACTIVE".equals(targetStatus)) {
        String blocked = assignRoleProblem(targetStatus, type, false);
        CatalogService.check(blocked == null, blocked);
      }
      try {
        sql.execute(
            "insert into party_role(id,tenant_id,party_id,role_type,status,note)"
                + " values(?,?,?,?,?,?) on conflict (tenant_id,party_id,role_type)"
                + " do update set status=excluded.status,note=excluded.note,"
                + " version=party_role.version+1,updated_at=now()",
            Ids.next(),
            tenantId,
            partyId,
            type,
            role.status().trim(),
            normalizeText(role.note()));
      } catch (DataAccessException e) {
        throw conflict("角色指派保存失败，请刷新后重试");
      }
    }
    // 提交集合里没有的角色一律解指派（整体替换语义）。
    List<String> submitted = roles.stream().map(r -> normalizeRoleType(r.roleType())).toList();
    for (String type : stored)
      if (!submitted.contains(type))
        sql.execute(
            "delete from party_role where tenant_id=? and party_id=? and role_type=?",
            tenantId, partyId, type);
  }

  private static int submittedActiveRoleCount(List<PartyRoleInput> roles) {
    int count = 0;
    for (PartyRoleInput role : roles) if (role != null && "ACTIVE".equals(normalizeStatus(role.status()))) count++;
    return count;
  }

  private static int activeRoleCount(List<PartyRole> roles) {
    int count = 0;
    for (PartyRole role : roles) if ("ACTIVE".equals(role.status())) count++;
    return count;
  }

  /** jOOQ 把 {@code text[]} 读成 {@code String[]}；空数组要归一成空集合而不是 null。 */
  private static List<String> roleList(Record r) {
    String[] raw = r.get("roles", String[].class);
    if (raw == null) return List.of();
    List<String> roles = new ArrayList<>(raw.length);
    Collections.addAll(roles, raw);
    return roles;
  }

  private static int intOf(Long value) {
    return value == null ? 0 : value.intValue();
  }

  /**
   * LIKE 模式里的通配符转义。
   *
   * <p>与 {@code escape '!'} 成对：不转义的话运营搜 {@code 100%} 会变成「匹配所有」，
   * 而界面上看不出来 —— 那一页返回几百条看起来还挺像对的。
   */
  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 AccessService.run 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var all = new ArrayList<Object>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
