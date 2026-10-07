package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PartyAddressRules.*;

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
 * 3.6.3.1 往来单位的收发货地址：地址本体 + 用途 / 默认标记 + <b>内容变更历史版本链</b>。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>当前态可变，历史 append-only</b>。地址行是「现在长什么样」，
 *       {@code party_address_version} 是「每次内容变更当时长什么样」。
 *       版本行只追加、不修改、不删除。这么分层的理由（以及为什么不选「地址行不可变」）
 *       写在 {@code V31__party_addresses_contacts.sql} 第四节 —— 简版是：
 *       「默认地址」在不可变写法下没有落点（当前态会变成「没有后继的那一行」），
 *       而单据要的是按 {@code (address_id, version_no)} <b>精确取回</b>某一版，
 *       差异日志要求读者自己重放历史。
 *   <li><b>用途与默认是标签，不是内容</b>。只改用途 / 默认标记走
 *       {@link #saveUsages}，那条路径<b>不</b>产生版本行（规则 A3）——
 *       快照要冻结的是印在面单上的那个地址，而不是这条地址今天挂着什么标签。
 *   <li><b>选中阻断只有一处实现</b>。{@link PartyAddressRules#selectionBlock} 是唯一的
 *       「这条地址能不能被下游新单据选中」判定，列表行、详情、默认地址、候选四个出口都调它。
 *       前端只把原因码翻成话，不在浏览器里再判一次。
 *   <li><b>软删除，且删之前必须先改默认</b>。已发生的 06 采购单 / 10 物流单引用的是
 *       {@code (address_id, version_no)} 指路牌，删掉地址会让指路牌指向不存在的行。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 地址属于商品主数据域（蓝图卷 03），沿用往来单位的同一套词表，不新造权限词表 ——
 * 那要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@Service
public class PartyAddressService {
  /** 单页最大行数。单个主体的地址数量级在两位数；超过 200 一页基本是脚本在拉全量。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 地址读出口径的 SELECT 列。
   *
   * <p>四个聚合子查询全部锚在 {@code a}（当前行）上，没有独立占位符，
   * 所以主查询的绑定顺序恒为 {@code [tenantId] + 筛选 + limit/offset}。
   * 全部取自同一次查询：列表每行要显示用途徽章、默认标记与最新版本号，
   * 拆成四个端点会让「列表上看到的默认」与「详情里看到的默认」有两次实现。
   */
  private static final String SELECT_COLUMNS =
      "select a.id,a.party_id,a.label,a.country_code,a.state_province,a.city,a.district,"
          + "a.postal_code,a.street_line1,a.street_line2,a.version,a.deleted_at,a.created_at,a.updated_at,"
          + "coalesce((select array_agg(u.usage order by u.usage) from party_address_usage u"
          + "  where u.tenant_id=a.tenant_id and u.address_id=a.id),'{}') as usages,"
          + "coalesce((select array_agg(u.usage order by u.usage) from party_address_usage u"
          + "  where u.tenant_id=a.tenant_id and u.address_id=a.id and u.is_default),'{}') as default_usages,"
          + "(select count(*) from party_address_version v"
          + "  where v.tenant_id=a.tenant_id and v.address_id=a.id) as version_count,"
          + "(select max(v.version_no) from party_address_version v"
          + "  where v.tenant_id=a.tenant_id and v.address_id=a.id) as latest_version_no,"
          + "pp.status as party_status,pp.deleted_at as party_deleted_at";

  /**
   * FROM 子句。
   *
   * <p>与 {@code party} 做内连接是为了在<b>同一次查询</b>里拿到主体状态与删除标记 ——
   * 选中阻断判定需要它们（规则 A6）。连接条件带 {@code tenant_id}：
   * 只按 id 连的话，跨租户的地址会连到别的租户的主体上。
   */
  private static final String FROM_ADDRESS =
      " from party_address a join party pp on pp.tenant_id=a.tenant_id and pp.id=a.party_id"
          + " where a.tenant_id=?";

  /** 「这条地址挂着该用途」的子查询（列表按用途筛 + 默认地址查询共用）。 */
  private static final String HAS_USAGE =
      "exists (select 1 from party_address_usage u where u.tenant_id=a.tenant_id"
          + " and u.address_id=a.id and u.usage=?)";

  /** 「这条地址没有任何用途」—— 界面上要能一眼看出哪些地址还没归到收发 / 提货上。 */
  private static final String NO_USAGE =
      "not exists (select 1 from party_address_usage u where u.tenant_id=a.tenant_id"
          + " and u.address_id=a.id)";

  /** 「至少是一个用途的默认地址」—— 页面上的「已设默认」计数用它。 */
  private static final String IS_DEFAULT =
      "exists (select 1 from party_address_usage u where u.tenant_id=a.tenant_id"
          + " and u.address_id=a.id and u.is_default)";

  /**
   * 「这条地址当前可选中」的 SQL 表达式：未删除 + 主体未删除 + 主体 ACTIVE。
   *
   * <p>与 {@link PartyAddressRules#selectionBlock} 同一口径、同一优先级，
   * 列表筛选与候选列表都引<b>这一处字符串</b> —— 两处各写一遍的症状是
   * 「候选列表说没有可用提货地址、详情页却显示可��」。
   */
  private static final String SELECTABLE =
      "(a.deleted_at is null and pp.deleted_at is null and pp.status='ACTIVE')";

  private final DSLContext sql;

  public PartyAddressService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 列表（分页 + 搜索 + 筛选）

  /**
   * 地址列表：关键词（地址名 / 街道 / 城市）+ 用途 / 国家筛选。
   *
   * <p>关键词覆盖城市与街道而不只覆盖地址名：运营手里常常只有一张名片上的
   * 一个城市名，而通配符按 {@code escape '!'} 转义，否则搜 {@code 100%} 会变成「匹配所有」。
   */
  public PartyAddressPage list(UUID partyId, PartyAddressQuery query) {
    return listInternal(partyId, query, false);
  }

  /**
   * 列表的共同实现。
   *
   * @param onlySelectable 只返回「当前可选中」的地址（候选列表用）。这个开关是<b>进 SQL</b> 的，
   *     不是取一页回来在内存里过滤 —— 见 {@link #candidates} 的说明。
   */
  private PartyAddressPage listInternal(UUID partyId, PartyAddressQuery query, boolean onlySelectable) {
    partyRow(partyId);
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds);
    if (onlySelectable) where.add(SELECTABLE);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    List<Object> base = new ArrayList<>();
    base.add(currentTenantId());
    base.add(partyId);

    long total =
        sql.fetchOne(
                "select count(*)" + FROM_ADDRESS + " and a.party_id=?" + sqlWhere,
                concat(base, binds).toArray())
            .get(0, Long.class);
    // 两个统计都**不受筛选条件影响**：界面上把它们放在筛选结果旁边，
    // 用户才知道「筛出来的 2 条」之外还有多少地址没设默认、没标用途。
    long defaultCount =
        sql.fetchOne(
                "select count(*)" + FROM_ADDRESS + " and a.party_id=? and a.deleted_at is null and " + IS_DEFAULT,
                currentTenantId(), partyId)
            .get(0, Long.class);
    long unassignedCount =
        sql.fetchOne(
                "select count(*)" + FROM_ADDRESS + " and a.party_id=? and a.deleted_at is null and " + NO_USAGE,
                currentTenantId(), partyId)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            SELECT_COLUMNS + FROM_ADDRESS + " and a.party_id=?" + sqlWhere
                + " order by a.id limit ? offset ?",
            concat(base, concat(binds, List.of(size, (long) page * size))).toArray());
    return new PartyAddressPage(
        rows.stream().map(this::toAddress).toList(),
        total,
        page,
        size,
        defaultCount,
        unassignedCount,
        "统计（已设默认 / 未标用途）不受筛选条件影响，方便对照筛选结果与全量。"
            + "软删除的地址默认不显示（includeDeleted=true 才带上），但详情仍可读并带 deleted=true。"
            + "默认标记按用途分别设置：同一主体可以有多个默认，分别对应收货 / 发货 / 提货。");
  }

  private void conditions(PartyAddressQuery query, List<String> where, List<Object> binds) {
    if (!Boolean.TRUE.equals(query.includeDeleted())) where.add("a.deleted_at is null");
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(a.label ilike ? escape '!' or a.street_line1 ilike ? escape '!'"
              + " or a.street_line2 ilike ? escape '!' or a.city ilike ? escape '!'"
              + " or a.state_province ilike ? escape '!' or a.postal_code ilike ? escape '!')");
      String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
      // 六列各自绑一次：六个 OR 分支各自能用上 V31 建的那几条 pg_trgm GIN 索引。
      for (int i = 0; i < 6; i++) binds.add(pattern);
    }
    if (query.usage() != null && !query.usage().isBlank()) {
      String problem = usageProblem(query.usage());
      CatalogService.check(problem == null, problem);
      where.add(HAS_USAGE);
      binds.add(normalizeUsage(query.usage()));
    }
    if (query.countryCode() != null && !query.countryCode().isBlank()) {
      String problem = countryProblem(query.countryCode());
      CatalogService.check(problem == null, problem);
      where.add("a.country_code=?");
      binds.add(normalizeCountry(query.countryCode()));
    }
  }

  // ================================================================ 详情 / 默认 / 候选

  /** 地址详情。已软删除的地址**仍可读**并带 {@code deleted=true}（删完回头看一眼要看得见）。 */
  public PartyAddress detail(UUID partyId, UUID addressId) {
    partyRow(partyId);
    return addressRow(partyId, addressId);
  }

  /**
   * 某个用途的默认地址 —— 06 采购（发货 / 收货）与 10 物流（提货）取默认地址走的就是它。
   *
   * <p>没有默认时 404 而不是返回一个「空的地址」：单据侧要能区分
   * 「这个主体有地址但没人设默认」（提示去设）与「这个主体一条地址都没有」。
   */
  public PartyAddress defaultAddress(UUID partyId, String usage) {
    partyRow(partyId);
    String problem = usageProblem(usage);
    CatalogService.check(problem == null, problem);
    var row =
        sql.fetchOne(
            SELECT_COLUMNS + FROM_ADDRESS + " and a.party_id=? and a.deleted_at is null and " + HAS_USAGE
                + " and exists (select 1 from party_address_usage d where d.tenant_id=a.tenant_id"
                + " and d.address_id=a.id and d.is_default and d.usage=?) order by a.id limit 1",
            currentTenantId(), partyId, normalizeUsage(usage), normalizeUsage(usage));
    if (row == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "该往来单位还没有" + usageLabel(usage) + "默认地址");
    return toAddress(row);
  }

  /**
   * 引用候选：下游单据的下拉数据源。
   *
   * <p>用 list 的 SQL（同一套筛选与同一个 {@code SELECTABLE} 表达式）而不是
   * 取一页再在内存里过滤 —— 内存过滤会让「第 1 页 20 条里有 12 条不可选」的空结果说谎，
   * 而单据的选择器会显示「没有可用的提货地址」。那正是「假分页」的一种。
   */
  public List<PartyAddress> candidates(UUID partyId, String usage, String keyword, boolean includeBlocked) {
    // **软删除的地址在两种模式下都不返回**（includeDeleted 固定为 false）：
    // includeBlocked 只放宽「主体状态」，不放松「已删除」——
    // 新单据永远不该选中一个已删的地址，而它存在的意义只是让历史单据上的地址显示出来。
    // 这与 3.6.1.1 的 candidates 同一口径（软删除行只有详情与 includeDeleted 列表能读到）。
    PartyAddressQuery query =
        new PartyAddressQuery(keyword, usage, null, Boolean.FALSE, 0, MAX_PAGE_SIZE);
    return listInternal(partyId, query, !includeBlocked).items();
  }

  // ================================================================ 保存

  /**
   * 建 / 改地址（{@code addressId} 为空即新建）。
   *
   * <p>三层内容分工：{@code input} 里的地址列是<b>内容</b>（内容真的变了才追加版本行，规则 A3），
   * {@code input.usages} 是<b>标签</b>（整体替换，不产生版本行）。
   * 建档时写入版本 1；改地址内容时追加下一版；只改用途时既不追加版本也不动内容。
   */
  public PartyAddressSaved save(AccessService.Access actor, UUID partyId, UUID addressId, PartyAddressInput input) {
    var party = partyRow(partyId);
    String contentProblem =
        addressProblem(
            input.countryCode(),
            input.city(),
            input.streetLine1(),
            input.stateProvince(),
            input.district(),
            input.postalCode(),
            input.streetLine2(),
            input.label(),
            input.changeNote());
    String usageProblem = usagesProblem(input.usages());
    CatalogService.check(contentProblem == null, contentProblem);
    CatalogService.check(usageProblem == null, usageProblem);

    UUID tenantId = actor.tenantId();
    String country = normalizeCountry(input.countryCode());
    String label = blankToNull(input.label());
    String city = input.city().trim();
    String streetLine1 = input.streetLine1().trim();
    String streetLine2 = blankToNull(input.streetLine2());
    String stateProvince = blankToNull(input.stateProvince());
    String district = blankToNull(input.district());
    String postalCode = blankToNull(input.postalCode());

    if (addressId == null) {
      String blocked = createUnderPartyProblem(party.get("status", String.class), "新增地址");
      CatalogService.check(blocked == null, blocked);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into party_address(id,tenant_id,party_id,label,country_code,state_province,city,"
                + "district,postal_code,street_line1,street_line2)"
                + " values(?,?,?,?,?,?,?,?,?,?,?)",
            newId,
            tenantId,
            partyId,
            label,
            country,
            stateProvince,
            city,
            district,
            postalCode,
            streetLine1,
            streetLine2);
      } catch (DataAccessException e) {
        throw conflict("地址保存失败，请刷新后重试");
      }
      // 建档即版本 1：链上不能没有起点 —— 「第 1 版」正是 06 采购单建立时快照的那一版。
      appendVersion(
          actor,
          partyId,
          newId,
          1,
          country,
          stateProvince,
          city,
          district,
          postalCode,
          streetLine1,
          streetLine2,
          label,
          defaultChangeNote(input.changeNote(), "建档"));
      replaceUsages(tenantId, partyId, newId, input.usages() == null ? List.of() : input.usages());
      audit(actor, newId, "PartyAddressSaved");
      return savedResult(partyId, newId);
    }

    var stored = addressRecord(partyId, addressId);
    if (stored.get("deleted_at", OffsetDateTime.class) != null)
      throw CatalogService.missing();
    String storedFingerprint =
        contentFingerprint(
            stored.get("country_code", String.class),
            stored.get("state_province", String.class),
            stored.get("city", String.class),
            stored.get("district", String.class),
            stored.get("postal_code", String.class),
            stored.get("street_line1", String.class),
            stored.get("street_line2", String.class),
            stored.get("label", String.class));
    String submittedFingerprint =
        contentFingerprint(country, stateProvince, city, district, postalCode, streetLine1, streetLine2, label);

    int updated;
    try {
      updated =
          sql.execute(
              "update party_address set label=?,country_code=?,state_province=?,city=?,district=?,"
                  + "postal_code=?,street_line1=?,street_line2=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              label,
              country,
              stateProvince,
              city,
              district,
              postalCode,
              streetLine1,
              streetLine2,
              addressId,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("地址保存失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "地址已被他人修改，请刷新后重试");

    // 内容真的变了才追加版本（规则 A3）：保存时一字未改、或只改了标签，
    // 都不该在版本链上留下一条无法区分的行。
    if (appendsVersion(storedFingerprint, submittedFingerprint)) {
      int next = latestVersionNo(addressId) + 1;
      appendVersion(
          actor,
          partyId,
          addressId,
          next,
          country,
          stateProvince,
          city,
          district,
          postalCode,
          streetLine1,
          streetLine2,
          label,
          defaultChangeNote(input.changeNote(), "内容更新"));
    }
    // 整体替换用途（标签变化，不产生版本行）。
    replaceUsages(tenantId, partyId, addressId, input.usages() == null ? List.of() : input.usages());
    audit(actor, addressId, "PartyAddressSaved");
    return savedResult(partyId, addressId);
  }

  /**
   * 只改用途与默认标记（{@code PUT /{id}/usages}）。
   *
   * <p>不碰地址内容，因此<b>不产生版本行</b>（规则 A3）：把默认提货地址改到 3 号仓
   * 不是「地址变了」，版本列表要回答的是「这个地址被改过几次、每次改成什么样」。
   * 仍然走地址行的乐观锁 —— 用途与内容共享一个版本号，少一次并发覆盖的入口。
   */
  public PartyAddressSaved saveUsages(
      AccessService.Access actor, UUID partyId, UUID addressId, PartyAddressUsageSaveInput input) {
    partyRow(partyId);
    var stored = addressRecord(partyId, addressId);
    if (stored.get("deleted_at", OffsetDateTime.class) != null) throw CatalogService.missing();
    String problem = usagesProblem(input.usages());
    CatalogService.check(problem == null, problem);

    int updated =
        sql.execute(
            "update party_address set version=version+1,updated_at=now() where id=? and version=?",
            addressId,
            input.version());
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "地址已被他人修改，请刷新后重试");
    replaceUsages(actor.tenantId(), partyId, addressId, input.usages());
    audit(actor, addressId, "PartyAddressUsagesSaved");
    return savedResult(partyId, addressId);
  }

  /**
   * 设 / 取消某个用途的默认地址（{@code PUT /{id}/default}）。
   *
   * <p>「默认」按用途分别设，所以要带 {@code usage}；{@code isDefault=false} 是显式取消。
   * 设成默认时会先把同一主体同一用途下的其它地址取消默认 ——
   * 「一个主体一个默认提货地址」由 V31 第六节的部分唯一索引在 DB 层钉死，
   * 这里先清一遍是为了让报错落在服务层（可读中文）而不是索引冲突上。
   */
  public PartyAddressSaved setDefault(
      AccessService.Access actor, UUID partyId, UUID addressId, PartyAddressDefaultInput input) {
    partyRow(partyId);
    var stored = addressRecord(partyId, addressId);
    if (stored.get("deleted_at", OffsetDateTime.class) != null)
      throw conflict("地址已删除，不能设为默认：请先恢复或换一个地址");
    String problem = usageProblem(input.usage());
    CatalogService.check(problem == null, problem);
    String usage = normalizeUsage(input.usage());

    // 必须是这条地址先挂着该用途，否则「设默认」会顺带改标签 ——
    // 那是一次两件事的保存，运营回滚时说不清自己回滚了什么。
    boolean hasUsage =
        sql.fetchOne(
                "select 1 from party_address_usage where tenant_id=? and party_id=? and address_id=? and usage=?",
                currentTenantId(), partyId, addressId, usage)
            != null;
    if (!hasUsage)
      throw conflict(
          "这条地址还没有标"
              + usageLabel(usage)
              + "用途：先在「用途」里勾选它，再把它设为该用途的默认（一次做两件事会让回滚说不清）");

    if (input.isDefault()) {
      // 先取消同一主体同一用途下的其它默认。
      sql.execute(
          "update party_address_usage set is_default=false,updated_at=now()"
              + " where tenant_id=? and party_id=? and usage=? and is_default and address_id<>?",
          currentTenantId(), partyId, usage, addressId);
      sql.execute(
          "update party_address_usage set is_default=true,updated_at=now()"
              + " where tenant_id=? and party_id=? and address_id=? and usage=?",
          currentTenantId(), partyId, addressId, usage);
    } else {
      sql.execute(
          "update party_address_usage set is_default=false,updated_at=now()"
              + " where tenant_id=? and party_id=? and address_id=? and usage=? and is_default",
          currentTenantId(), partyId, addressId, usage);
    }
    // 标签变化走乐观锁，但**不**动地址内容，也不产生版本行。
    sql.execute(
        "update party_address set version=version+1,updated_at=now() where id=? and version=?",
        addressId,
        input.version());
    audit(actor, addressId, input.isDefault() ? "PartyAddressDefaultSet" : "PartyAddressDefaultCleared");
    return savedResult(partyId, addressId);
  }

  /**
   * 软删除地址（规则 A4）。
   *
   * <p>删除前必须先改默认：报错点名是哪个用途的默认，
   * 并告诉运营先把默认改到别的地址 —— 直接删掉的后果是「这个供应商没有提货地址了」
   * 而 10 物流的单子上已经填好了原地址。
   *
   * <p>删掉之后它的用途行<b>保留</b>（{@code is_default=false}），
   * 这样「它曾经是提货地址」在数据上仍然说得通；版本链一行不动 ——
   * 版本行是历史快照，删除当前态不该改写历史。
   */
  public void delete(AccessService.Access actor, UUID partyId, UUID addressId, long version) {
    partyRow(partyId);
    var stored = addressRecord(partyId, addressId);
    if (stored.get("deleted_at", OffsetDateTime.class) != null) throw CatalogService.missing();
    List<String> defaultUsages = defaultUsagesOf(addressId);
    String blocked = deleteProblem(defaultUsages);
    CatalogService.check(blocked == null, blocked);

    int updated =
        sql.execute(
            "update party_address set deleted_at=now(),deleted_by=?,version=version+1,updated_at=now()"
                + " where id=? and version=? and deleted_at is null",
            actor.userId(), addressId, version);
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "地址已被他人修改，请刷新后重试");
    sql.execute(
        "update party_address_usage set is_default=false,updated_at=now()"
            + " where tenant_id=? and address_id=? and is_default",
        currentTenantId(), addressId);
    audit(actor, addressId, "PartyAddressDeleted");
  }

  // ================================================================ 版本链（冻结快照）

  /** 版本列表：按版本号倒序（最新在前），界面上第一行就是「现在这一版」。 */
  public List<PartyAddressVersion> versions(UUID partyId, UUID addressId) {
    partyRow(partyId);
    addressRow(partyId, addressId);
    return fetchVersions(addressId);
  }

  /**
   * 取回<b>某一版</b> —— 单据按 {@code (address_id, version_no)} 回放「当时的内容」走的就是它。
   *
   * <p>这是本模块交给 06 采购 / 10 物流的只读兜底出口：**权威仍然是单据自己的快照列**
   * （V31 第四节的三层说明），这里只在快照缺失时才用。
   */
  public PartyAddressVersion version(UUID partyId, UUID addressId, int versionNo) {
    partyRow(partyId);
    addressRow(partyId, addressId);
    int latest = latestVersionNo(addressId);
    var row =
        sql.fetchOne(
            "select * from party_address_version where tenant_id=? and address_id=? and version_no=?",
            currentTenantId(), addressId, versionNo);
    if (row == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "该地址没有第 " + versionNo + " 版（当前最新是第 " + latest + " 版）");
    return toVersion(row, latest);
  }

  private List<PartyAddressVersion> fetchVersions(UUID addressId) {
    int latest = latestVersionNo(addressId);
    return sql.fetch(
            "select * from party_address_version where tenant_id=? and address_id=? order by version_no desc",
            currentTenantId(), addressId)
        .stream()
        .map(r -> toVersion(r, latest))
        .toList();
  }

  private int latestVersionNo(UUID addressId) {
    Integer latest =
        sql.fetchOne(
                "select max(version_no) from party_address_version where tenant_id=? and address_id=?",
                currentTenantId(), addressId)
            .get(0, Integer.class);
    return latest == null ? 0 : latest;
  }

  /**
   * 追加一条版本行（append-only）。
   *
   * <p>{@code changed_at} 用<b>数据库自己的时钟</b>（{@code now()}）而不是服务层传来的时间：
   * 版本链要回答「第 N 版是什么时候定下来的」，而多实例部署下应用节点的时钟并不对齐。
   * 读回时用 {@code OffsetDateTime} 取（jOOQ 按 timestamptz 映射），
   * <b>不能</b>用 {@code Timestamp.valueOf(LocalDateTime)} —— 那会静默偏 −28800s（东八区）。
   */
  private void appendVersion(
      AccessService.Access actor,
      UUID partyId,
      UUID addressId,
      int versionNo,
      String countryCode,
      String stateProvince,
      String city,
      String district,
      String postalCode,
      String streetLine1,
      String streetLine2,
      String label,
      String changeNote) {
    sql.execute(
        "insert into party_address_version(id,tenant_id,party_id,address_id,version_no,label,"
            + "country_code,state_province,city,district,postal_code,street_line1,street_line2,"
            + "changed_at,changed_by,change_note)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?)",
        Ids.next(),
        actor.tenantId(),
        partyId,
        addressId,
        versionNo,
        label,
        countryCode,
        stateProvince,
        city,
        district,
        postalCode,
        streetLine1,
        streetLine2,
        actor.userId(),
        changeNote);
  }

  /** 变更说明兜底：创建那条固定是「建档」，其余给「内容更新」，版本列表里不留空白行。 */
  private static String defaultChangeNote(String submitted, String fallback) {
    String note = submitted == null ? "" : submitted.trim();
    return note.isEmpty() ? fallback : note;
  }

  // ================================================================ 用途整体替换

  /**
   * 整体替换一条地址的用途与默认标记。
   *
   * <p>用 {@code on conflict do update} 一条语句同时覆盖「新增」与「更新」：
   * 整体替换要的是「提交集合的最终态」，逐行先删后插会在
   * {@code UNIQUE(tenant_id,address_id,usage)} 上撞自己
   * （先删把同用途行提前腾空，插回时与刚插入的新行相撞）。
   *
   * <p>提交集合里没有的用途一律删掉（整体替换语义）。
   */
  private void replaceUsages(
      UUID tenantId, UUID partyId, UUID addressId, List<PartyAddressUsageInput> usages) {
    Set<String> submitted = new LinkedHashSet<>();
    for (PartyAddressUsageInput usage : usages) {
      if (usage == null) continue;
      String value = normalizeUsage(usage.usage());
      submitted.add(value);
      // 先把同主体同用途的其它地址取消默认：部分唯一索引只允许一个默认，
      // 而整体替换允许一次提交里带多个 isDefault=true（界面允许这么填），
      // 这时要按提交顺序取最后一条 —— 界面已经把前一条显示成未选中了。
      if (usage.isDefault())
        sql.execute(
            "update party_address_usage set is_default=false,updated_at=now()"
                + " where tenant_id=? and party_id=? and usage=? and is_default and address_id<>?",
            tenantId, partyId, value, addressId);
      sql.execute(
          "insert into party_address_usage(id,tenant_id,party_id,address_id,usage,is_default)"
              + " values(?,?,?,?,?,?) on conflict (tenant_id,address_id,usage)"
              + " do update set is_default=excluded.is_default,updated_at=now()",
          Ids.next(),
          tenantId,
          partyId,
          addressId,
          value,
          usage.isDefault());
    }
    List<String> stored = storedUsages(addressId);
    for (String usage : stored)
      if (!submitted.contains(usage))
        sql.execute(
            "delete from party_address_usage where tenant_id=? and address_id=? and usage=?",
            tenantId, addressId, usage);
  }

  private List<String> storedUsages(UUID addressId) {
    List<String> values = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select usage from party_address_usage where tenant_id=? and address_id=?",
            currentTenantId(), addressId))
      values.add(r.get("usage", String.class));
    return values;
  }

  private List<String> defaultUsagesOf(UUID addressId) {
    List<String> values = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select usage from party_address_usage where tenant_id=? and address_id=? and is_default",
            currentTenantId(), addressId))
      values.add(r.get("usage", String.class));
    return orderedUsages(values);
  }

  // ================================================================ 映射与工具

  private PartyAddress toAddress(Record r) {
    List<String> usages = stringArray(r, "usages");
    List<String> defaults = orderedUsages(stringArray(r, "default_usages"));
    String oneLine =
        oneLine(
            r.get("street_line1", String.class),
            r.get("street_line2", String.class),
            r.get("district", String.class),
            r.get("city", String.class),
            r.get("state_province", String.class),
            r.get("postal_code", String.class),
            r.get("country_code", String.class));
    // 选中阻断只有这一处实现（规则 A6）：列表行、详情、默认地址、候选四个出口共用。
    String block =
        selectionBlock(
            new SelectionInputs(
                r.get("deleted_at", OffsetDateTime.class) != null,
                r.get("party_deleted_at", OffsetDateTime.class) != null,
                r.get("party_status", String.class)));
    return new PartyAddress(
        r.get("id", UUID.class),
        r.get("party_id", UUID.class),
        r.get("label", String.class),
        r.get("country_code", String.class),
        r.get("state_province", String.class),
        r.get("city", String.class),
        r.get("district", String.class),
        r.get("postal_code", String.class),
        r.get("street_line1", String.class),
        r.get("street_line2", String.class),
        oneLine,
        orderedUsages(usages),
        defaults,
        usageSummary(usages, defaults),
        intOf(r.get("latest_version_no", Long.class)),
        intOf(r.get("version_count", Long.class)),
        r.get("version", Long.class),
        block,
        selectionBlockMessage(block, oneLine),
        r.get("deleted_at", OffsetDateTime.class) != null,
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private static PartyAddressVersion toVersion(Record r, int latestVersionNo) {
    int versionNo = r.get("version_no", Integer.class);
    return new PartyAddressVersion(
        r.get("address_id", UUID.class),
        versionNo,
        versionNo == latestVersionNo,
        r.get("label", String.class),
        r.get("country_code", String.class),
        r.get("state_province", String.class),
        r.get("city", String.class),
        r.get("district", String.class),
        r.get("postal_code", String.class),
        r.get("street_line1", String.class),
        r.get("street_line2", String.class),
        oneLine(
            r.get("street_line1", String.class),
            r.get("street_line2", String.class),
            r.get("district", String.class),
            r.get("city", String.class),
            r.get("state_province", String.class),
            r.get("postal_code", String.class),
            r.get("country_code", String.class)),
        r.get("change_note", String.class),
        instant(r.get("changed_at", OffsetDateTime.class)),
        r.get("changed_by", UUID.class),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  /** 取一行地址（带主体状态，供选中阻断用）。刻意不过滤 deleted_at：删后详情仍要可读。 */
  private PartyAddress addressRow(UUID partyId, UUID addressId) {
    return toAddress(addressRecord(partyId, addressId));
  }

  /** 与 {@link #addressRow} 同一条查询，返回未映射的 {@code Record} —— 写路径要读原始列做比对。 */
  private Record addressRecord(UUID partyId, UUID addressId) {
    var row =
        sql.fetchOne(
            SELECT_COLUMNS + FROM_ADDRESS + " and a.party_id=? and a.id=?", currentTenantId(), partyId, addressId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** 取一行主体：校验归属 + 拿到状态（规则 A5）与删除标记。 */
  private Record partyRow(UUID partyId) {
    var row = sql.fetchOne("select id,status,deleted_at from party where id=?", partyId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private PartyAddressSaved savedResult(UUID partyId, UUID addressId) {
    PartyAddress address = addressRow(partyId, addressId);
    return new PartyAddressSaved(
        address.id(),
        address.version(),
        address.usages(),
        address.defaultUsages(),
        address.latestVersionNo(),
        address.oneLine());
  }

  /** jOOQ 把 {@code text[]} 读成 {@code String[]}；空数组归一成空集合而不是 null。 */
  private static List<String> stringArray(Record r, String field) {
    String[] raw = r.get(field, String[].class);
    if (raw == null) return List.of();
    List<String> values = new ArrayList<>(raw.length);
    Collections.addAll(values, raw);
    return values;
  }

  private static int intOf(Long value) {
    return value == null ? 0 : value.intValue();
  }

  /** LIKE 模式里的通配符转义。与 PartyService 同一口径：不转义的话搜 100% 会变成「匹配所有」。 */
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
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }
}