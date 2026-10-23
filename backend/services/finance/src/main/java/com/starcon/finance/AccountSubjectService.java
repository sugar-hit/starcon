package com.starcon.finance;

import static com.starcon.finance.AccountSubjectModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.1.1 会计科目。
 *
 * <p>蓝图 12-finance.md：「维护**法人账簿**的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。」输入：「科目定义；业务事件映射（如发货 → 收入 + 结转成本）。」输出：「科目表 + 映射规则。」关系：「上游 = 用户会计体系；下游 = 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，系统提供模板但必须允许完全自定义。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>科目体系是用户自定的。</b> 系统只提供**一份模板**（V356 播种的
 *       {@code finance_account_subject_template}，运行期只读），模板也只是**导入源**：
 *       导入后用户可以改名 / 改类别 / 改余额方向 / 停用 / 增删 —— 服务层**没有任何**
 *       「标准科目必须存在」的校验，类别与余额方向也不互相联动（累计折旧是资产类却记贷方余额）。
 *   <li><b>科目编码是稳定引用键。</b> 编码**租户内唯一**且**一经入账不可改**（DB 触发器钉死），
 *       下游 12.5.2.1 的凭证按编码**软引用**本表 —— 本模块因此提供
 *       {@code by-code} 查询作为它们的集成点。
 *   <li><b>业务事件 → 科目的映射可配置、落库、可查。</b> 规则头 + 分录行两张表；
 *       事件编码由用户自定义（**不是枚举**）；启用前必须**借贷齐备**（DB 守卫钉死）。
 *   <li><b>本模块没有金额。</b> 科目是配置，凭证（12.5.2.1，未交付）才是金额的载体 ——
 *       本模块**不造凭证、不造分录金额**，唯一的数值是税率。
 * </ol>
 */
@Service
public class AccountSubjectService {

  private static final Logger logger = LoggerFactory.getLogger(AccountSubjectService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 科目编码格式：允许中文会计常见的层级编码（{@code 1122.01}）。 */
  private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

  /** 业务事件编码格式：大写字母开头 + 大写字母数字下划线。 */
  private static final Pattern EVENT_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  private final DSLContext sql;

  public AccountSubjectService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 下游缺口必须分开说：会计期间（12.5.1.2）与关账（12.5.1.3）是**本服务内部**的后续模块，
   * 凭证（12.5.2.1）与报表（12.5.2.2）是**别的车道** —— 混成一句「不可用」会让现场不知道该催谁。
   */
  public FinanceAccountSubjectCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceAccountSubjectCapability>();
    var gaps = new ArrayList<FinanceAccountSubjectDownstreamGap>();

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.1.1",
            "会计科目表（法人账簿维度）",
            true,
            "finance_account_subject（科目编码 / 名称 / 类别 / 余额方向 / 是否明细 / 税务属性 / 科目树）",
            "",
            "数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL。科目**按账簿**组织"
                + "（accounting_book_id + legal_entity_id 是 identity 17.1.2.2 / 17.1.2.1 的逻辑 ID，"
                + "跨服务不建 FK、不直连对方库）。"
                + "🔴 **科目编码租户内唯一且不可修改** —— 下游 12.5.2.1 按编码软引用它。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.1.1",
            "科目体系完全自定义（模板只是导入源）",
            true,
            "finance_account_subject_template（系统参考数据，运行期只读）+ 用户自有科目行",
            "",
            "蓝图加粗句「**科目体系是用户自定的**，系统提供模板但必须允许完全自定义」的落地："
                + "模板提供一份企业会计准则通用科目表，导入后**每个字段都能改**（名称 / 类别 / 余额方向 / 税务属性），"
                + "也能停用和新增 —— 服务层**没有**「标准科目必须存在」这类校验，"
                + "类别与余额方向**不联动**（累计折旧是资产类却记贷方余额，强行联动会堵死真实账）。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.1.1",
            "业务事件 → 科目的映射规则",
            true,
            "finance_account_subject_event_mapping（规则头）+ finance_account_subject_event_mapping_line（分录行）",
            "",
            "蓝图输入里的「业务事件映射（如发货 → 收入 + 结转成本）」：一个事件一条规则，规则下若干分录行。"
                + "事件编码**由用户自定义**（不是枚举 —— 写死就等于把用户的业务事件体系写死）。"
                + "🔴 **启用前必须借贷齐备**（至少一条借方 + 一条贷方），"
                + "且启用后增删改行仍要保持齐备（DB 触发器钉死，服务层绕不过）。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.1.1",
            "科目变更留痕（append-only）",
            true,
            "finance_account_subject_event（运行角色的 UPDATE / DELETE 已收回）",
            "",
            "科目是**会被引用**的配置：改了它，已经按老科目编好的凭证解释就变了。"
                + "所以「改了什么」有独立出处，事件流只能追加。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "17.1.2.2 / 17.1.2.1",
            "法人账簿与法人档案（上游 identity）",
            true,
            "identity 的 accounting_book / legal_entity（本表只存逻辑 ID 与快照，跨服务不建 FK）",
            "",
            "账簿挂在**法人**上，一个租户可以有多个法人，每个法人一套记账基准。"
                + "identity 的 V43 注释明确「identity 不越界替 finance 建账」⇒ "
                + "**本模块只管科目**，不生成会计期间行（那是 12.5.1.2，未交付）。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.1.2",
            "会计期间",
            false,
            "本模块不生成期间行",
            "12.5.1.2",
            "会计期间（自然月 / 4-4-5 周期）与期间状态属 12.5.1.2，**未交付**。"
                + "本模块**不生成**任何期间行，也不做关账检查。"));

    capabilities.add(
        new FinanceAccountSubjectCapability(
            "12.5.2.1",
            "凭证生成与过账",
            false,
            "本模块只提供科目与映射模板，不产生凭证",
            "12.5.2.1",
            "凭证（生成 / 过账 / 校验 / 红冲）属 12.5.2.1，**未交付**。"
                + "本模块**不造凭证、不造分录金额**：金额相等由凭证侧按业务事件实算。"
                + "凭证行按**科目编码软引用**本表（编码租户内唯一且不可改）。"));

    gaps.add(
        new FinanceAccountSubjectDownstreamGap(
            "12.5.1.2",
            "会计期间",
            "finance",
            false,
            "12.5 账簿与科目",
            "期间行未交付：本期尚无「期间开放/关闭」概念，凭证过账期间校验（12.5.2.1）因此也无从谈起。"));

    gaps.add(
        new FinanceAccountSubjectDownstreamGap(
            "12.5.1.3",
            "关账",
            "finance",
            false,
            "12.5 账簿与科目",
            "关账流程未交付；本模块的科目不参与任何关账检查。"));

    gaps.add(
        new FinanceAccountSubjectDownstreamGap(
            "12.5.2.1",
            "凭证管理",
            "finance",
            false,
            "12.5 凭证与报表",
            "凭证未交付：映射规则只是**模板**（取哪个量），不含金额，"
                + "接上动作是凭证侧按业务事件实算借贷金额。"
                + "🔴 集成点：凭证行引用 finance_account_subject.subject_code（租户内唯一、不可修改）。"));

    gaps.add(
        new FinanceAccountSubjectDownstreamGap(
            "12.5.2.2",
            "报表",
            "finance",
            false,
            "12.5 凭证与报表",
            "报表未交付：余额表 / 利润表要按科目汇总，本模块已把科目表按类别分好，"
                + "但**不产出任何报表数字**。"));

    return new FinanceAccountSubjectCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceAccountSubjectOptions options() {
    var categories =
        sql.fetch("select distinct category from finance_account_subject order by category")
            .map(r -> r.get(0, String.class));
    var directions =
        sql.fetch("select distinct balance_direction from finance_account_subject order by balance_direction")
            .map(r -> r.get(0, String.class));
    var treatments =
        sql.fetch("select distinct tax_treatment from finance_account_subject order by tax_treatment")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_account_subject order by status")
            .map(r -> r.get(0, String.class));
    var sources =
        sql.fetch("select distinct source from finance_account_subject order by source")
            .map(r -> r.get(0, String.class));
    var books =
        sql.fetch("select distinct accounting_book_id from finance_account_subject order by accounting_book_id")
            .map(r -> r.get(0, UUID.class));
    var parents =
        sql.fetch(
                "select distinct parent_subject_code from finance_account_subject"
                    + " where parent_subject_code is not null order by parent_subject_code")
            .map(r -> r.get(0, String.class));
    return new FinanceAccountSubjectOptions(
        categories, directions, treatments, statuses, sources, books, parents);
  }

  // ================================================================ 列表 / 详情

  /**
   * 科目列表：真分页 + 真筛选。
   *
   * <p>⚠️ **汇总覆盖整个筛选结果集**，不是当前页 —— 分页一变汇总就变的话，
   * 「翻到第二页看到总数变小」会被读成「数据丢了」。
   */
  public FinanceAccountSubjectPage page(AccountSubjectQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_account_subject s" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select s.*,"
                    + " (select count(*) from finance_account_subject_event_mapping_line l"
                    + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                    + " as mapping_reference_count"
                    + " from finance_account_subject s"
                    + clause
                    + " order by s.level, s.subject_code limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summary =
        sql.fetch(
                "select s.category,"
                    + " count(*) as cnt,"
                    + " count(*) filter (where s.is_detail) as detail_count,"
                    + " count(*) filter (where not s.is_detail) as group_count"
                    + " from finance_account_subject s"
                    + clause
                    + " group by s.category order by s.category",
                binds.toArray())
            .map(
                r -> {
                  var category = AccountSubjectCategory.valueOf(r.get("category", String.class));
                  return new FinanceAccountSubjectCategorySummary(
                      category,
                      category.label(),
                      r.get("cnt", Long.class),
                      r.get("detail_count", Long.class),
                      r.get("group_count", Long.class));
                });

    return new FinanceAccountSubjectPage(items, p, s, total, summary);
  }

  /** 科目详情：科目 + 变更留痕 + 引用它的映射规则。 */
  public FinanceAccountSubjectDetail detail(UUID id) {
    var subject =
        sql.fetchOne(
            "select s.*,"
                + " (select count(*) from finance_account_subject_event_mapping_line l"
                + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                + " as mapping_reference_count"
                + " from finance_account_subject s where s.id = ?",
            id);
    if (subject == null) throw notFound("科目 " + id + " 不存在或无权查看");
    var events =
        sql.fetch(
                "select * from finance_account_subject_event"
                    + " where target_type = 'SUBJECT' and target_id = ?"
                    + " order by occurred_at desc, id desc",
                id)
            .map(this::eventRow);
    var references =
        sql.fetch(
                "select m.id, m.event_type, m.event_label, m.status,"
                    + " l.direction, l.amount_basis"
                    + " from finance_account_subject_event_mapping_line l"
                    + " join finance_account_subject_event_mapping m"
                    + "   on m.tenant_id = l.tenant_id and m.id = l.mapping_id"
                    + " where l.subject_code = ?"
                    + " order by m.event_type, l.sequence",
                subject.get("subject_code", String.class))
            .map(
                r ->
                    new FinanceAccountSubjectMappingRef(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        nz(r.get("event_label", String.class)),
                        r.get("status", String.class),
                        r.get("direction", String.class),
                        r.get("amount_basis", String.class)));
    return new FinanceAccountSubjectDetail(row(subject), events, references);
  }

  /**
   * 按科目编码查 —— **下游 12.5.2.1 凭证的集成点**。
   *
   * <p>编码租户内唯一 ⇒ 命中时至多一条。没命中时如实返回 {@code found=false}，
   * 不返回一条「默认科目」顶替（那会让凭证记到一个用户从没建过的科目上）。
   */
  public FinanceAccountSubjectByCodeResult byCode(String subjectCode) {
    String code = subjectCode == null ? "" : subjectCode.trim();
    if (code.isEmpty()) throw badRequest("科目编码不能为空");
    var found =
        sql.fetchOne(
            "select s.*,"
                + " (select count(*) from finance_account_subject_event_mapping_line l"
                + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                + " as mapping_reference_count"
                + " from finance_account_subject s where s.subject_code = ?",
            code);
    if (found == null) {
      return new FinanceAccountSubjectByCodeResult(
          false, null, "科目编码在当前租户内不存在；科目体系由用户自定，请先在本模块建立该科目。");
    }
    return new FinanceAccountSubjectByCodeResult(true, row(found), "科目编码租户内唯一 ⇒ 命中时至多一条。");
  }

  private void applyFilters(AccountSubjectQuery q, List<String> where, List<Object> binds) {
    if (q.accountingBookId() != null) {
      where.add("s.accounting_book_id = ?");
      binds.add(q.accountingBookId());
    }
    if (q.category() != null) {
      where.add("s.category = ?");
      binds.add(q.category().name());
    }
    if (q.balanceDirection() != null) {
      where.add("s.balance_direction = ?");
      binds.add(q.balanceDirection().name());
    }
    if (q.taxTreatment() != null) {
      where.add("s.tax_treatment = ?");
      binds.add(q.taxTreatment().name());
    }
    if (q.status() != null) {
      where.add("s.status = ?");
      binds.add(q.status().name());
    }
    if (q.detail() != null) {
      where.add("s.is_detail = ?");
      binds.add(q.detail());
    }
    if (q.parentSubjectCode() != null && !q.parentSubjectCode().isBlank()) {
      where.add("s.parent_subject_code = ?");
      binds.add(q.parentSubjectCode().trim());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(s.subject_code ilike ? or s.subject_name ilike ? or s.tax_note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 科目写入

  /**
   * 新增科目。
   *
   * <p>⚠️ 编码重复时给的是**能照着改的原因**，不是一条 409。
   */
  @Transactional
  public FinanceAccountSubjectRow create(FinanceAccountSubjectInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("科目内容不能为空");
    if (input.accountingBookId() == null) throw badRequest("必须指定科目所属的法人账簿");
    if (input.legalEntityId() == null) throw badRequest("必须指定科目所属的法人");
    if (input.category() == null) throw badRequest("科目类别不能为空");
    if (input.balanceDirection() == null) throw badRequest("余额方向不能为空");
    String code = requireCode(input.subjectCode());
    String name = requireName(input.subjectName());
    String parent = blankToNull(input.parentSubjectCode());
    AccountTaxTreatment treatment =
        input.taxTreatment() == null ? AccountTaxTreatment.TAXABLE : input.taxTreatment();
    BigDecimal taxRate = checkTaxRate(input.taxRate());
    boolean detail = input.detail() == null || input.detail();

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_account_subject"
              + "(id,tenant_id,accounting_book_id,legal_entity_id,subject_code,subject_name,category,"
              + " balance_direction,is_detail,parent_subject_code,level,tax_treatment,tax_rate,tax_note,"
              + " status,source,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,1,?,?,?,'ACTIVE','CUSTOM',0,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          input.accountingBookId(),
          input.legalEntityId(),
          code,
          name,
          input.category().name(),
          input.balanceDirection().name(),
          detail,
          parent,
          treatment.name(),
          taxRate,
          nz(input.taxNote()),
          now,
          now);
    } catch (DataIntegrityViolationException e) {
      throw duplicateSubject(e, code);
    }
    appendEvent(
        actor.tenantId(),
        "SUBJECT",
        id,
        "CREATED",
        "",
        "",
        "",
        "新建科目 " + code,
        actor.userId(),
        now);
    return row(
        sql.fetchOne(
            "select s.*,"
                + " (select count(*) from finance_account_subject_event_mapping_line l"
                + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                + " as mapping_reference_count"
                + " from finance_account_subject s where s.id = ?",
            id));
  }

  /**
   * 改科目。
   *
   * <p>⚠️ **没有编码字段**：编码一经入账不可改（下游凭证按编码软引用它）。
   * 名字 / 类别 / 余额方向 / 税务属性都能改 —— 蓝图说「科目体系是用户自定的」。
   */
  @Transactional
  public FinanceAccountSubjectRow update(
      UUID id, FinanceAccountSubjectUpdateInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("修改内容不能为空");
    var before =
        sql.fetchOne("select * from finance_account_subject where id = ?", id);
    if (before == null) throw notFound("科目 " + id + " 不存在或无权修改");
    if (input.category() == null) throw badRequest("科目类别不能为空");
    if (input.balanceDirection() == null) throw badRequest("余额方向不能为空");
    String name = requireName(input.subjectName());
    String parent = blankToNull(input.parentSubjectCode());
    AccountTaxTreatment treatment =
        input.taxTreatment() == null ? AccountTaxTreatment.TAXABLE : input.taxTreatment();
    BigDecimal taxRate = checkTaxRate(input.taxRate());
    boolean detail = input.detail() == null || input.detail();
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    try {
      sql.execute(
          "update finance_account_subject set subject_name = ?, category = ?, balance_direction = ?,"
              + " is_detail = ?, parent_subject_code = ?, tax_treatment = ?, tax_rate = ?, tax_note = ?,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          name,
          input.category().name(),
          input.balanceDirection().name(),
          detail,
          parent,
          treatment.name(),
          taxRate,
          nz(input.taxNote()),
          now,
          id);
    } catch (DataIntegrityViolationException e) {
      throw conflict(e, "科目修改被数据库规则拒绝：" + rootMessage(e));
    }

    // 留痕：逐字段记录「改了什么」—— 科目被引用过，改动必须能自证。
    recordChange(actor, id, "subject_name", before.get("subject_name", String.class), name, now);
    recordChange(actor, id, "category", before.get("category", String.class), input.category().name(), now);
    recordChange(
        actor, id, "balance_direction", before.get("balance_direction", String.class), input.balanceDirection().name(), now);
    recordChange(actor, id, "is_detail", String.valueOf(before.get("is_detail", Boolean.class)), String.valueOf(detail), now);
    recordChange(
        actor, id, "parent_subject_code", nz(before.get("parent_subject_code", String.class)), nz(parent), now);
    recordChange(
        actor, id, "tax_treatment", before.get("tax_treatment", String.class), treatment.name(), now);
    recordChange(
        actor,
        id,
        "tax_rate",
        before.get("tax_rate", BigDecimal.class) == null ? "" : before.get("tax_rate", BigDecimal.class).toPlainString(),
        taxRate == null ? "" : taxRate.toPlainString(),
        now);
    recordChange(actor, id, "tax_note", nz(before.get("tax_note", String.class)), nz(input.taxNote()), now);

    return row(
        sql.fetchOne(
            "select s.*,"
                + " (select count(*) from finance_account_subject_event_mapping_line l"
                + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                + " as mapping_reference_count"
                + " from finance_account_subject s where s.id = ?",
            id));
  }

  /**
   * 停用科目。
   *
   * <p>⚠️ **停用不是删除**：历史凭证仍要能引用到它（下游按科目编码软引用）。
   * 已停用的科目不能再被新的映射行引用（DB 守卫）。
   */
  @Transactional
  public FinanceAccountSubjectRow disable(UUID id, AccessService.Access actor) {
    return setStatus(id, AccountSubjectStatus.DISABLED, "停用科目", actor);
  }

  /** 重新启用科目。 */
  @Transactional
  public FinanceAccountSubjectRow enable(UUID id, AccessService.Access actor) {
    return setStatus(id, AccountSubjectStatus.ACTIVE, "启用科目", actor);
  }

  private FinanceAccountSubjectRow setStatus(
      UUID id, AccountSubjectStatus status, String reason, AccessService.Access actor) {
    var before = sql.fetchOne("select * from finance_account_subject where id = ?", id);
    if (before == null) throw notFound("科目 " + id + " 不存在或无权修改");
    String from = nz(before.get("status", String.class));
    if (from.equals(status.name())) {
      throw badRequest("科目 " + before.get("subject_code", String.class) + " 已经是「" + status.label() + "」状态");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "update finance_account_subject set status = ?, version = version + 1, updated_at = ?::timestamptz"
              + " where id = ?",
          status.name(),
          now,
          id);
    } catch (DataIntegrityViolationException e) {
      // ⚠️ 停用被**已启用**的映射规则引用的科目会被 DB 守卫拒 ——
      //   这里把原因翻译成能照着改的话，而不是抛一条 500。
      throw conflict(e, "停用被拒：" + rootMessage(e));
    }
    appendEvent(actor.tenantId(), "SUBJECT", id, "STATUS_CHANGED", "status", from, status.name(), reason, actor.userId(), now);
    return row(
        sql.fetchOne(
            "select s.*,"
                + " (select count(*) from finance_account_subject_event_mapping_line l"
                + "   where l.tenant_id = s.tenant_id and l.subject_code = s.subject_code)"
                + " as mapping_reference_count"
                + " from finance_account_subject s where s.id = ?",
            id));
  }

  // ================================================================ 系统模板

  /** 模板目录（读的是真实模板表，运行期只读）。 */
  public FinanceAccountSubjectTemplateCatalog templates() {
    var subjects =
        sql.fetch(
                "select * from finance_account_subject_template order by template_key, sort_order, subject_code")
            .map(
                r -> {
                  var category = AccountSubjectCategory.valueOf(r.get("category", String.class));
                  var direction = AccountBalanceDirection.valueOf(r.get("balance_direction", String.class));
                  var treatment = AccountTaxTreatment.valueOf(r.get("tax_treatment", String.class));
                  return new FinanceAccountSubjectTemplateRow(
                      r.get("template_key", String.class),
                      r.get("subject_code", String.class),
                      r.get("subject_name", String.class),
                      category,
                      category.label(),
                      direction,
                      direction.label(),
                      r.get("is_detail", Boolean.class),
                      r.get("parent_subject_code", String.class),
                      treatment,
                      treatment.label(),
                      r.get("tax_rate", BigDecimal.class),
                      nz(r.get("tax_note", String.class)),
                      r.get("sort_order", Integer.class));
                });
    var summaries = new ArrayList<FinanceAccountSubjectTemplateSummary>();
    var keys =
        sql.fetch("select distinct template_key from finance_account_subject_template order by template_key")
            .map(r -> r.get(0, String.class));
    for (String key : keys) {
      var counts =
          sql.fetchOne(
              "select count(*) as cnt,"
                  + " count(*) filter (where is_detail) as detail_count,"
                  + " count(*) filter (where not is_detail) as group_count"
                  + " from finance_account_subject_template where template_key = ?",
              key);
      summaries.add(
          new FinanceAccountSubjectTemplateSummary(
              key,
              templateName(key),
              counts.get("cnt", Long.class),
              counts.get("detail_count", Long.class),
              counts.get("group_count", Long.class)));
    }
    return new FinanceAccountSubjectTemplateCatalog(
        summaries,
        subjects,
        "模板是**导入源**，不是约束：导入后每个字段都能改，也能停用和另建科目；"
            + "系统不强制「标准科目必须存在」。");
  }

  /**
   * 从模板导入科目到指定账簿。
   *
   * <p>⚠️ **已存在的编码被跳过而不是覆盖** —— 用户改过的科目不能被模板冲掉；
   * 被跳过的编码逐个报出来，现场才知道「哪几个没进来」。
   */
  @Transactional
  public FinanceAccountSubjectTemplateApplyResult applyTemplate(
      FinanceAccountSubjectTemplateApplyInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("导入内容不能为空");
    if (input.accountingBookId() == null) throw badRequest("必须指定导入到哪本法人账簿");
    if (input.legalEntityId() == null) throw badRequest("必须指定科目所属的法人");
    String templateKey = input.templateKey() == null ? "" : input.templateKey().trim();
    if (templateKey.isEmpty()) throw badRequest("模板标识不能为空");
    var exists =
        sql.fetchOne(
            "select count(*) from finance_account_subject_template where template_key = ?", templateKey);
    if (exists == null || exists.get(0, Long.class) == 0L) {
      throw notFound("科目模板 " + templateKey + " 不存在");
    }

    var rows =
        sql.fetch(
            "select * from finance_account_subject_template where template_key = ? order by sort_order, subject_code",
            templateKey);
    int created = 0;
    var skipped = new ArrayList<String>();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var r : rows) {
      String code = r.get("subject_code", String.class);
      var duplicate =
          sql.fetchOne(
              "select count(*) from finance_account_subject where subject_code = ?", code);
      if (duplicate != null && duplicate.get(0, Long.class) > 0L) {
        skipped.add(code);
        continue;
      }
      var id = UUID.randomUUID();
      try {
        sql.execute(
            "insert into finance_account_subject"
                + "(id,tenant_id,accounting_book_id,legal_entity_id,subject_code,subject_name,category,"
                + " balance_direction,is_detail,parent_subject_code,level,tax_treatment,tax_rate,tax_note,"
                + " status,source,template_key,version,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,1,?,?,?,'ACTIVE','TEMPLATE',?,0,?::timestamptz,?::timestamptz)",
            id,
            actor.tenantId(),
            input.accountingBookId(),
            input.legalEntityId(),
            code,
            r.get("subject_name", String.class),
            r.get("category", String.class),
            r.get("balance_direction", String.class),
            r.get("is_detail", Boolean.class),
            r.get("parent_subject_code", String.class),
            r.get("tax_treatment", String.class),
            r.get("tax_rate", BigDecimal.class),
            nz(r.get("tax_note", String.class)),
            templateKey,
            now,
            now);
      } catch (DataIntegrityViolationException e) {
        // 上级科目尚未导入（模板顺序异常）或编码撞车：如实跳过，不吞掉。
        skipped.add(code);
        continue;
      }
      created++;
      appendEvent(
          actor.tenantId(),
          "SUBJECT",
          id,
          "CREATED",
          "",
          "",
          "",
          "从模板 " + templateKey + " 导入科目 " + code,
          actor.userId(),
          now);
    }
    logger.info("会计科目模板导入完成：模板 {}，新建 {} 个，跳过 {} 个", templateKey, created, skipped.size());
    return new FinanceAccountSubjectTemplateApplyResult(
        templateKey, input.accountingBookId(), created, skipped.size(), List.copyOf(skipped));
  }

  /** 模板显示名：库里只有标识，显示名给一个可读的说明。 */
  private static String templateName(String key) {
    if ("CN_STANDARD_V1".equals(key)) return "企业会计准则通用科目表";
    return key;
  }

  // ================================================================ 映射规则

  /** 映射规则列表：真分页 + 真筛选。 */
  public FinanceAccountSubjectMappingPage mappings(AccountSubjectMappingQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.accountingBookId() != null) {
      where.add("m.accounting_book_id = ?");
      binds.add(query.accountingBookId());
    }
    if (query.status() != null) {
      where.add("m.status = ?");
      binds.add(query.status().name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(m.event_type ilike ? or m.event_label ilike ? or m.note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_account_subject_event_mapping m" + clause, binds.toArray())
            .get(0, Long.class);
    var ids =
        sql.fetch(
                "select m.id from finance_account_subject_event_mapping m"
                    + clause
                    + " order by m.accounting_book_id, m.event_type, m.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(r -> r.get("id", UUID.class));
    var items = new ArrayList<FinanceAccountSubjectMappingRow>();
    for (UUID id : ids) {
      items.add(mappingRow(id));
    }
    return new FinanceAccountSubjectMappingPage(items, p, s, total);
  }

  /** 映射规则详情：头 + 分录行。 */
  public FinanceAccountSubjectMappingRow mappingDetail(UUID id) {
    var head =
        sql.fetchOne("select * from finance_account_subject_event_mapping where id = ?", id);
    if (head == null) throw notFound("映射规则 " + id + " 不存在或无权查看");
    return mappingRow(id);
  }

  /**
   * 新建映射规则（头 + 分录行一次给全）。
   *
   * <p>⚠️ 新建出来一定是**草稿**：分录行要先落库，「借贷齐备」才判得出来 ——
   * 启用走另一个端点，启用时由 DB 守卫校验齐备。
   */
  @Transactional
  public FinanceAccountSubjectMappingRow createMapping(
      FinanceAccountSubjectMappingInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("映射规则内容不能为空");
    if (input.accountingBookId() == null) throw badRequest("必须指定映射规则所属的法人账簿");
    String eventType = requireEventType(input.eventType());
    var lines = input.lines() == null ? List.<FinanceAccountSubjectMappingLineInput>of() : input.lines();
    checkLines(lines);

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_account_subject_event_mapping"
              + "(id,tenant_id,accounting_book_id,event_type,event_label,status,effective_from,note,"
              + " version,created_at,updated_at)"
              + " values(?,?,?,?,?,'DRAFT',?::timestamptz,?,0,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          input.accountingBookId(),
          eventType,
          nz(input.eventLabel()),
          input.effectiveFrom(),
          nz(input.note()),
          now,
          now);
    } catch (DataIntegrityViolationException e) {
      throw conflict(e, "同一本账里业务事件 " + eventType + " 已经有映射规则了，请直接改那条规则");
    }
    writeLines(id, lines, actor, now);
    appendEvent(
        actor.tenantId(),
        "MAPPING",
        id,
        "MAPPING_CREATED",
        "",
        "",
        "",
        "新建业务事件 " + eventType + " 的映射规则",
        actor.userId(),
        now);
    return mappingRow(id);
  }

  /**
   * 改映射规则（头字段 + **全量替换**分录行）。
   *
   * <p>⚠️ **已启用的规则不能改分录行** —— 先停用再改。这条不是服务层的洁癖：
   * DB 触发器同样会拒（启用态下删行会破坏借贷齐备），两层一致。
   */
  @Transactional
  public FinanceAccountSubjectMappingRow updateMapping(
      UUID id, FinanceAccountSubjectMappingUpdateInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("修改内容不能为空");
    var before = sql.fetchOne("select * from finance_account_subject_event_mapping where id = ?", id);
    if (before == null) throw notFound("映射规则 " + id + " 不存在或无权修改");
    String status = nz(before.get("status", String.class));
    // lines == null ⇒ 「不传分录行」，只改头字段；lines 非空 ⇒ 全量替换。
    var lines = input.lines();
    boolean replaceLines = lines != null;
    if (replaceLines) {
      if ("ACTIVE".equals(status)) {
        throw conflict(
            "已启用的映射规则不能改分录行（启用后仍必须保持借贷齐备）；请先停用再改");
      }
      checkLines(lines);
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_account_subject_event_mapping set event_label = ?, effective_from = ?::timestamptz,"
            + " note = ?, version = version + 1, updated_at = ?::timestamptz where id = ?",
        nz(input.eventLabel()),
        input.effectiveFrom(),
        nz(input.note()),
        now,
        id);
    if (replaceLines) {
      sql.execute(
          "delete from finance_account_subject_event_mapping_line where mapping_id = ?", id);
      writeLines(id, lines, actor, now);
    }
    appendEvent(
        actor.tenantId(),
        "MAPPING",
        id,
        "MAPPING_UPDATED",
        "",
        "",
        "",
        "修改映射规则（分录行 " + (replaceLines ? lines.size() : 0) + " 条）",
        actor.userId(),
        now);
    return mappingRow(id);
  }

  /**
   * 启用映射规则。
   *
   * <p>⚠️ **借贷齐备**才让启用（DB 守卫钉死，服务层也先判一次给可读原因）。
   */
  @Transactional
  public FinanceAccountSubjectMappingRow activateMapping(UUID id, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_account_subject_event_mapping where id = ?", id);
    if (head == null) throw notFound("映射规则 " + id + " 不存在或无权修改");
    String status = nz(head.get("status", String.class));
    if ("ACTIVE".equals(status)) throw badRequest("该映射规则已经是启用状态");
    var counts = lineCounts(id);
    if (counts.debit() == 0 || counts.credit() == 0) {
      throw badRequest(
          "映射规则启用前必须借贷齐备：当前借方 " + counts.debit() + " 条 / 贷方 " + counts.credit() + " 条");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "update finance_account_subject_event_mapping set status = 'ACTIVE', version = version + 1,"
              + " updated_at = ?::timestamptz where id = ?",
          now,
          id);
    } catch (DataIntegrityViolationException e) {
      throw conflict(e, "映射规则启用被数据库规则拒绝：" + rootMessage(e));
    }
    appendEvent(
        actor.tenantId(),
        "MAPPING",
        id,
        "MAPPING_ACTIVATED",
        "status",
        status,
        "ACTIVE",
        "启用业务事件 " + nz(head.get("event_type", String.class)) + " 的映射规则",
        actor.userId(),
        now);
    return mappingRow(id);
  }

  /** 停用映射规则（停用后分录行可以再改）。 */
  @Transactional
  public FinanceAccountSubjectMappingRow deactivateMapping(UUID id, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_account_subject_event_mapping where id = ?", id);
    if (head == null) throw notFound("映射规则 " + id + " 不存在或无权修改");
    String status = nz(head.get("status", String.class));
    if ("INACTIVE".equals(status)) throw badRequest("该映射规则已经是停用状态");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_account_subject_event_mapping set status = 'INACTIVE', version = version + 1,"
            + " updated_at = ?::timestamptz where id = ?",
        now,
        id);
    appendEvent(
        actor.tenantId(),
        "MAPPING",
        id,
        "MAPPING_DEACTIVATED",
        "status",
        status,
        "INACTIVE",
        "停用业务事件 " + nz(head.get("event_type", String.class)) + " 的映射规则",
        actor.userId(),
        now);
    return mappingRow(id);
  }

  /** 写分录行（顺序按入参 sequence，缺省按数组下标）。 */
  private void writeLines(
      UUID mappingId,
      List<FinanceAccountSubjectMappingLineInput> lines,
      AccessService.Access actor,
      OffsetDateTime now) {
    int index = 0;
    for (var line : lines) {
      index++;
      int sequence = line.sequence() == null || line.sequence() <= 0 ? index : line.sequence();
      String code = line.subjectCode() == null ? "" : line.subjectCode().trim();
      if (code.isEmpty()) throw badRequest("映射分录行的科目编码不能为空");
      if (line.direction() == null) throw badRequest("映射分录行的借贷方向不能为空");
      if (line.amountBasis() == null) throw badRequest("映射分录行的金额口径不能为空");
      try {
        sql.execute(
            "insert into finance_account_subject_event_mapping_line"
                + "(id,tenant_id,mapping_id,subject_code,direction,amount_basis,sequence,note,"
                + " created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            mappingId,
            code,
            line.direction().name(),
            line.amountBasis().name(),
            sequence,
            nz(line.note()),
            now,
            now);
      } catch (DataIntegrityViolationException e) {
        throw conflict(e, "映射分录行被数据库规则拒绝（科目 " + code + "）：" + rootMessage(e));
      }
    }
  }

  /** 分录行入参自检：同一规则里不允许重复行序。 */
  private void checkLines(List<FinanceAccountSubjectMappingLineInput> lines) {
    var seen = new java.util.HashSet<Integer>();
    int index = 0;
    for (var line : lines) {
      index++;
      int sequence = line.sequence() == null || line.sequence() <= 0 ? index : line.sequence();
      if (!seen.add(sequence)) {
        throw badRequest("映射分录行的行序重复：" + sequence + "（同一规则内行序必须唯一）");
      }
    }
  }

  private record LineCounts(int debit, int credit) {}

  private LineCounts lineCounts(UUID mappingId) {
    var r =
        sql.fetchOne(
            "select count(*) filter (where direction = 'DEBIT') as debit,"
                + " count(*) filter (where direction = 'CREDIT') as credit"
                + " from finance_account_subject_event_mapping_line where mapping_id = ?",
            mappingId);
    if (r == null) return new LineCounts(0, 0);
    return new LineCounts(
        r.get("debit", Integer.class) == null ? 0 : r.get("debit", Integer.class),
        r.get("credit", Integer.class) == null ? 0 : r.get("credit", Integer.class));
  }

  private FinanceAccountSubjectMappingRow mappingRow(UUID id) {
    var head = sql.fetchOne("select * from finance_account_subject_event_mapping where id = ?", id);
    if (head == null) throw notFound("映射规则 " + id + " 不存在或无权查看");
    var lines =
        sql.fetch(
                "select l.*, s.subject_name from finance_account_subject_event_mapping_line l"
                    + " left join finance_account_subject s"
                    + "   on s.tenant_id = l.tenant_id and s.subject_code = l.subject_code"
                    + " where l.mapping_id = ? order by l.sequence, l.id",
                id)
            .map(
                r -> {
                  var direction = AccountMappingDirection.valueOf(r.get("direction", String.class));
                  var basis = AccountAmountBasis.valueOf(r.get("amount_basis", String.class));
                  return new FinanceAccountSubjectMappingLineRow(
                      r.get("id", UUID.class),
                      r.get("mapping_id", UUID.class),
                      r.get("subject_code", String.class),
                      r.get("subject_name", String.class),
                      direction,
                      direction.label(),
                      basis,
                      basis.label(),
                      r.get("sequence", Integer.class),
                      nz(r.get("note", String.class)));
                });
    int debit = 0;
    int credit = 0;
    for (var line : lines) {
      if (line.direction() == AccountMappingDirection.DEBIT) debit++;
      else credit++;
    }
    var status = AccountMappingStatus.valueOf(head.get("status", String.class));
    return new FinanceAccountSubjectMappingRow(
        head.get("id", UUID.class),
        head.get("accounting_book_id", UUID.class),
        head.get("event_type", String.class),
        nz(head.get("event_label", String.class)),
        status,
        status.label(),
        head.get("effective_from", OffsetDateTime.class),
        nz(head.get("note", String.class)),
        lines,
        debit,
        credit,
        debit > 0 && credit > 0,
        head.get("created_at", OffsetDateTime.class),
        head.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 留痕

  private void appendEvent(
      UUID tenantId,
      String targetType,
      UUID targetId,
      String eventType,
      String fieldName,
      String fromValue,
      String toValue,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_account_subject_event"
            + "(id,tenant_id,target_type,target_id,event_type,field_name,from_value,to_value,reason,"
            + " actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        targetType,
        targetId,
        eventType,
        nz(fieldName),
        nz(fromValue),
        nz(toValue),
        nz(reason),
        actorId,
        at);
  }

  /** 逐字段留痕：值没变就不记（避免留痕被无意义的改动刷屏）。 */
  private void recordChange(
      AccessService.Access actor,
      UUID targetId,
      String field,
      String from,
      String to,
      OffsetDateTime at) {
    if (from.equals(to)) return;
    appendEvent(
        actor.tenantId(),
        "SUBJECT",
        targetId,
        "FIELD_CHANGED",
        field,
        from,
        to,
        "修改科目字段",
        actor.userId(),
        at);
  }

  private FinanceAccountSubjectEventRow eventRow(Record r) {
    return new FinanceAccountSubjectEventRow(
        r.get("id", UUID.class),
        r.get("target_type", String.class),
        r.get("target_id", UUID.class),
        r.get("event_type", String.class),
        nz(r.get("field_name", String.class)),
        nz(r.get("from_value", String.class)),
        nz(r.get("to_value", String.class)),
        nz(r.get("reason", String.class)),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 映射

  private FinanceAccountSubjectRow row(Record r) {
    var category = AccountSubjectCategory.valueOf(r.get("category", String.class));
    var direction = AccountBalanceDirection.valueOf(r.get("balance_direction", String.class));
    var treatment = AccountTaxTreatment.valueOf(r.get("tax_treatment", String.class));
    var status = AccountSubjectStatus.valueOf(r.get("status", String.class));
    var count = r.get("mapping_reference_count", Long.class);
    return new FinanceAccountSubjectRow(
        r.get("id", UUID.class),
        r.get("accounting_book_id", UUID.class),
        r.get("legal_entity_id", UUID.class),
        r.get("subject_code", String.class),
        r.get("subject_name", String.class),
        category,
        category.label(),
        direction,
        direction.label(),
        r.get("is_detail", Boolean.class),
        r.get("parent_subject_code", String.class),
        r.get("level", Integer.class),
        treatment,
        treatment.label(),
        r.get("tax_rate", BigDecimal.class),
        nz(r.get("tax_note", String.class)),
        status,
        status.label(),
        nz(r.get("source", String.class)),
        r.get("template_key", String.class),
        count == null ? 0L : count,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  private static String requireCode(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (code.isEmpty()) throw badRequest("科目编码不能为空");
    if (!CODE_PATTERN.matcher(code).matches()) {
      throw badRequest(
          "科目编码只允许字母、数字、点、下划线和中划线，且必须以字母或数字开头（支持 1122.01 这样的层级编码）");
    }
    return code;
  }

  private static String requireName(String raw) {
    String name = raw == null ? "" : raw.trim();
    if (name.isEmpty()) throw badRequest("科目名称不能为空");
    if (name.length() > 200) throw badRequest("科目名称不能超过 200 个字符");
    return name;
  }

  private static String requireEventType(String raw) {
    String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (type.isEmpty()) throw badRequest("业务事件编码不能为空");
    if (!EVENT_PATTERN.matcher(type).matches()) {
      throw badRequest("业务事件编码只能是大写字母开头的字母数字下划线组合（如 ORDER_SHIPPED）");
    }
    return type;
  }

  private static BigDecimal checkTaxRate(BigDecimal rate) {
    if (rate == null) return null;
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw badRequest("税率必须在 0 到 1 之间（0.13 表示 13%），不能传百分数");
    }
    return rate;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 唯一约束冲突翻译成能照着改的原因。 */
  private static ResponseStatusException duplicateSubject(Exception e, String code) {
    String detail = rootMessage(e);
    if (detail.contains("finance_account_subject_subject_code_key")
        || detail.contains("finance_account_subject_tenant_id_subject_code_key")) {
      return conflict(
          e, "科目编码 " + code + " 已存在（编码在本租户内唯一）：请换一个编码，或改用「停用 + 另建」的方式调整科目");
    }
    if (detail.contains("finance_account_subject_parent")) {
      return conflict(e, "上级科目不存在或与本租户不匹配：请先建上级科目，或把上级科目清空");
    }
    return conflict(e, "科目写入被数据库规则拒绝：" + detail);
  }

  private static ResponseStatusException conflict(Exception e, String detail) {
    return conflict(detail);
  }

  private static ResponseStatusException conflict(String detail) {
    return new ResponseStatusException(HttpStatus.CONFLICT, detail);
  }

  private static String rootMessage(Throwable e) {
    Throwable current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null ? current.getClass().getSimpleName() : message;
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    var out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    System.arraycopy(tail, 0, out, binds.size(), tail.length);
    return out;
  }
}
