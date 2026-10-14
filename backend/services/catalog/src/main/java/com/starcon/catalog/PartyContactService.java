package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PartyContactRules.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.6.3.1 往来单位的联系人：带姓名的那个人是谁、怎么联系到他、谁是默认联系人。
 *
 * <p>与 {@code party.email / phone / website} 的分工写在 {@code V31__party_addresses_contacts.sql}
 * 第零节：主体那三列回答「怎么找到这个主体」 （公司总机 / info@ 邮箱），本表回答「跟这个主体对接的**那个人**是谁」。 生命周期也不同 ——
 * 总机不会因为对接人离职而失效，对接人会，所以不能合成一列。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>邮箱 / 电话 / 手机至少给一项</b>（规则 C1）。三个都空的联系人是一行 在 06 采购问交期、10 物流约提货、11 售后约退货时都用不了的记录，
 *       而它看上去和真联系人完全一样。被拒的应该是**建这一行**。
 *   <li><b>默认联系人只有一个，删之前必须先改默认</b>（规则 C2）。
 *   <li><b>联系人允许物理删除</b>，而地址不允许（软删除）：联系人是「当前正在对接的那个人」的 <b>当前事实</b>，没有回溯需求 ——
 *       已发生单据里的联系人姓名与电话是单据自己的冻结快照 （与 V27 第七节对 06/11/12 的约定同源）。地址不能这么删，是因为下游单据引用的是 {@code
 *       (address_id, version_no)} 指路牌，删掉地址会让指路牌指向不存在的行。
 *   <li><b>选中阻断只有一处实现</b>：{@link PartyContactRules#selectionBlock}。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，与地址同一套词表。
 */
@Service
public class PartyContactService {
  /** 候选上限。一个主体的对接人数量级在两位数，单据的选择器不需要更多。 */
  private static final int MAX_CANDIDATES = 200;

  private static final String SELECT_COLUMNS =
      "select c.id,c.party_id,c.name,c.position,c.email,c.phone,c.mobile,c.is_primary,c.note,"
          + "c.version,c.created_at,c.updated_at,pp.status as party_status,pp.deleted_at as party_deleted_at";

  private static final String FROM_CONTACT =
      " from party_contact c join party pp on pp.tenant_id=c.tenant_id and pp.id=c.party_id"
          + " where c.tenant_id=?";

  /**
   * 「这个联系人当前可选中」的 SQL 表达式：主体未删除 + 主体 ACTIVE。
   *
   * <p>与 {@link PartyContactRules#selectionBlock} 同一口径，候选列表引<b>这一处字符串</b>。
   * 联系人没有自己的软删除字段，所以比地址少一个分支。
   */
  private static final String SELECTABLE = "(pp.deleted_at is null and pp.status='ACTIVE')";

  private final DSLContext sql;

  public PartyContactService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 列表

  /**
   * 联系人列表（按主体，不分页）。
   *
   * <p>刻意**不**分页：这是「某个往来单位的对接人」这一层，扇出量是人决定的（两三个人）， 不是数据量决定的；与 {@code PartyService.rolesOf} 同一取舍 ——
   * 给一个最多五条的集合加分页控件只会让人以为后面还有东西。
   *
   * <p>关键词覆盖姓名 / 邮箱 / 手机：运营从一封邮件的签名里抄下一个名字或号码就来找人， 通配符按 {@code escape '!'} 转义。
   */
  public List<PartyContact> list(UUID partyId, String keyword, Boolean primaryOnly) {
    partyRow(partyId);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("c.party_id=?");
    binds.add(partyId);
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(c.name ilike ? escape '!' or c.position ilike ? escape '!'"
              + " or c.email ilike ? escape '!' or c.phone ilike ? escape '!' or c.mobile ilike ? escape '!')");
      String pattern = "%" + escapeLike(keyword.trim()) + "%";
      for (int i = 0; i < 5; i++) binds.add(pattern);
    }
    if (Boolean.TRUE.equals(primaryOnly)) where.add("c.is_primary");
    return sql
        .fetch(
            SELECT_COLUMNS
                + FROM_CONTACT
                + " and "
                + String.join(" and ", where)
                + " order by c.is_primary desc,c.name,c.id",
            concat(List.of(currentTenantId()), binds).toArray())
        .stream()
        .map(PartyContactService::toContact)
        .toList();
  }

  /** 联系人详情。 */
  public PartyContact detail(UUID partyId, UUID contactId) {
    partyRow(partyId);
    return contactRow(partyId, contactId);
  }

  /**
   * 默认联系人 —— 06 采购 / 11 售后 / 12 财务要「找谁签字」时走的就是它。
   *
   * <p>没有默认联系人时 404：单据侧要能区分「有人但没人被设为默认」（提示去设一个） 与「这个主体一条联系人都没有」。
   */
  public PartyContact primary(UUID partyId) {
    partyRow(partyId);
    var row =
        sql.fetchOne(
            SELECT_COLUMNS + FROM_CONTACT + " and c.party_id=? and c.is_primary",
            currentTenantId(),
            partyId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该往来单位还没有默认联系人");
    return toContact(row);
  }

  /**
   * 引用候选：下游单据的下拉数据源。
   *
   * <p>与 {@link PartyAddressService#candidates} 同一纪律：过滤<b>进 SQL</b>， 而不是取一批回来在内存里挑 ——
   * 内存过滤会让「这一批里恰好没有可选的」显示成 「这个主体没有对接人」。
   */
  public List<PartyContact> candidates(UUID partyId, String keyword, boolean includeBlocked) {
    partyRow(partyId);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("c.party_id=?");
    binds.add(partyId);
    if (!includeBlocked) where.add(SELECTABLE);
    return sql
        .fetch(
            SELECT_COLUMNS
                + FROM_CONTACT
                + " and "
                + String.join(" and ", where)
                + " order by c.is_primary desc,c.name,c.id limit ?",
            concat(concat(List.of(currentTenantId()), binds), List.of(MAX_CANDIDATES)).toArray())
        .stream()
        .map(PartyContactService::toContact)
        .toList();
  }

  // ================================================================ 保存

  /**
   * 建 / 改联系人（{@code contactId} 为空即新建）。
   *
   * <p>拉黑的主体不允许<b>新增</b>联系人（规则 A5，与 3.6.1.1 的 P6 同源）； 改联系人、设默认在拉黑状态下仍允许 —— 清理一个已拉黑对手方的对接人
   * 正是之后要做的动作。
   */
  public PartyContactSaved save(
      AccessService.Access actor, UUID partyId, UUID contactId, PartyContactInput input) {
    var party = partyRow(partyId);
    String problem =
        contactProblem(
            input.name(),
            input.position(),
            input.email(),
            input.phone(),
            input.mobile(),
            input.note());
    CatalogService.check(problem == null, problem);
    if (contactId == null) {
      String blocked =
          PartyAddressRules.createUnderPartyProblem(party.get("status", String.class), "新增联系人");
      CatalogService.check(blocked == null, blocked);
    }

    String name = input.name().trim();
    String position = blankToNull(input.position());
    String email = blankToNull(input.email());
    // normalizePhone 对 null 返回的是**空串**而不是 null；空串会被 V31 的
    // CHECK(btrim(phone)<>'') 判成脏数据，所以这里必须再走一次「空串归一成 null」。
    String phone = blankToNull(PartyRules.normalizePhone(input.phone()));
    String mobile = blankToNull(PartyRules.normalizePhone(input.mobile()));
    String note = input.note() == null ? "" : input.note().trim();

    if (contactId == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into party_contact(id,tenant_id,party_id,name,position,email,phone,mobile,note)"
                + " values(?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            partyId,
            name,
            position,
            email,
            phone,
            mobile,
            note);
      } catch (DataAccessException e) {
        throw conflict("联系人保存失败，请刷新后重试");
      }
      // 默认联系人只有一个（部分唯一索引 V31 第六节）：整体设默认时先清同主体其它默认。
      if (input.isPrimary()) {
        sql.execute(
            "update party_contact set is_primary=false where tenant_id=? and party_id=? and is_primary",
            actor.tenantId(),
            partyId);
        sql.execute("update party_contact set is_primary=true where id=?", newId);
      }
      audit(actor, newId, "PartyContactSaved");
      return savedResult(partyId, newId);
    }

    contactRow(partyId, contactId);
    int updated;
    try {
      updated =
          sql.execute(
              "update party_contact set name=?,position=?,email=?,phone=?,mobile=?,note=?,"
                  + "version=version+1,updated_at=now() where id=? and version=?",
              name,
              position,
              email,
              phone,
              mobile,
              note,
              contactId,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("联系人保存失败，请刷新后重试");
    }
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "联系人已被他人修改，请刷新后重试");
    if (input.isPrimary()) makePrimary(partyId, contactId);
    audit(actor, contactId, "PartyContactSaved");
    return savedResult(partyId, contactId);
  }

  /**
   * 设 / 取消默认联系人（{@code PUT /{id}/primary}）。
   *
   * <p>与地址的默认用法一样：设默认要走<b>独立端点</b>而不是在保存里顺手改 —— 「换默认签字人」是一次明确的动作，混进「改个电话号码」里会让运营在编辑别的字段时
   * 悄悄换掉了默认联系人。
   */
  public PartyContactSaved setPrimary(
      AccessService.Access actor, UUID partyId, UUID contactId, PartyContactPrimaryInput input) {
    partyRow(partyId);
    contactRow(partyId, contactId);
    if (input.isPrimary()) {
      String blocked = primaryProblem(currentPartyStatus(partyId));
      CatalogService.check(blocked == null, blocked);
      makePrimary(partyId, contactId);
    } else {
      int updated =
          sql.execute(
              "update party_contact set is_primary=false,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              contactId,
              input.version());
      if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "联系人已被他人修改，请刷新后重试");
    }
    audit(
        actor,
        contactId,
        input.isPrimary() ? "PartyContactPrimarySet" : "PartyContactPrimaryCleared");
    return savedResult(partyId, contactId);
  }

  /**
   * 删除联系人（<b>物理删除</b>这一行，理由见类注释第 3 条）。
   *
   * <p>删之前必须先改默认（规则 C2）：报错要说明该做什么。
   */
  public void delete(AccessService.Access actor, UUID partyId, UUID contactId, long version) {
    partyRow(partyId);
    var stored = contactRow(partyId, contactId);
    String blocked = deleteProblem(stored.isPrimary());
    CatalogService.check(blocked == null, blocked);
    int deleted =
        sql.execute("delete from party_contact where id=? and version=?", contactId, version);
    if (deleted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "联系人已被他人修改，请刷新后重试");
    audit(actor, contactId, "PartyContactDeleted");
  }

  private void makePrimary(UUID partyId, UUID contactId) {
    // 先清同主体其它默认：部分唯一索引只允许一个默认，而这里不在同一条 SQL 里做完，
    // 避免一次 update 同时改两行时命中自己刚写的值。
    sql.execute(
        "update party_contact set is_primary=false,updated_at=now()"
            + " where tenant_id=? and party_id=? and is_primary and id<>?",
        currentTenantId(),
        partyId,
        contactId);
    sql.execute(
        "update party_contact set is_primary=true,version=version+1,updated_at=now() where id=?",
        contactId);
  }

  // ================================================================ 映射与工具

  private static PartyContact toContact(Record r) {
    String name = r.get("name", String.class);
    // 选中阻断只有这一处实现（规则 A6）。
    String block =
        selectionBlock(
            new SelectionInputs(
                r.get("party_deleted_at", OffsetDateTime.class) != null,
                r.get("party_status", String.class)));
    return new PartyContact(
        r.get("id", UUID.class),
        r.get("party_id", UUID.class),
        name,
        r.get("position", String.class),
        r.get("email", String.class),
        r.get("phone", String.class),
        r.get("mobile", String.class),
        Boolean.TRUE.equals(r.get("is_primary", Boolean.class)),
        r.get("note", String.class),
        block,
        selectionBlockMessage(block, displayName(name, r.get("position", String.class))),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private PartyContact contactRow(UUID partyId, UUID contactId) {
    var row =
        sql.fetchOne(
            SELECT_COLUMNS + FROM_CONTACT + " and c.party_id=? and c.id=?",
            currentTenantId(),
            partyId,
            contactId);
    if (row == null) throw CatalogService.missing();
    return toContact(row);
  }

  /** 取一行主体：校验归属 + 拿到状态（规则 A5）与删除标记。 */
  private Record partyRow(UUID partyId) {
    var row = sql.fetchOne("select id,status,deleted_at from party where id=?", partyId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private String currentPartyStatus(UUID partyId) {
    return partyRow(partyId).get("status", String.class);
  }

  private PartyContactSaved savedResult(UUID partyId, UUID contactId) {
    PartyContact contact = contactRow(partyId, contactId);
    return new PartyContactSaved(contact.id(), contact.version(), contact.isPrimary());
  }

  /** LIKE 模式里的通配符转义。与 PartyService 同一口径。 */
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

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }
}
