package com.starcon.platform;

import static com.starcon.platform.FinanceSoftwareModels.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.2.1.1「财务软件」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>五条纪律：</b>
 *
 * <ol>
 *   <li><b>外部适配器未接入就一个字都不写</b>：{@link #push} 拿到「未接入」直接翻成 409，
 *       <b>不</b>落推送记录、<b>不</b>落回执、<b>不</b>落任何「已经推给金蝶了」的痕迹。
 *       理由：一条返回成功结果的假通道，会让「凭证已经推给金蝶了」在数据上看起来发生过，
 *       而现场一个字都没推出去 —— 财务会拿着一张<b>不存在的</b>法定凭证去做账。
 *   <li><b>科目映射是真的在用</b>：导出凭证文件时本地科目被换成外部科目编码；
 *       余额核对时外部科目被换回本地科目编码。没配映射的行**标出来**（UNMAPPED），
 *       绝不静默丢行 —— 静默丢行比标出来危险得多。
 *   <li><b>余额核对是算出来的</b>：本地余额真读上游 12.5.2.2 试算平衡表，
 *       外部余额真从导入的快照行来，两者<b>同口径</b>后才相减。
 *   <li><b>导出的文件能原样取回</b>：内容落库 + sha256 落库 —— 只记「导出过一张文件」
 *       没有用，事后要能证明「对方系统里那张单据对应的就是这一份」。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 * </ol>
 */
@Service
public class FinanceSoftwareService {

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /**
   * 一次导出最多处理多少张凭证。
   *
   * <p>🔴 导出要**逐张**去上游取分录行（N+1 次跨服务调用）—— 上限是**必须**的：
   * 没有上限时一个「整年」的导出请求会把上游打满，而调用方在等一个几分钟的响应。
   * 超限直接报错并让调用方缩小区间，而不是**偷偷只导一部分**（那会让导出的文件
   * 看起来是完整的，而它不是）。
   */
  static final int MAX_EXPORT_VOUCHERS = 500;

  private final DSLContext sql;
  private final FinanceVoucherPort finance;
  private final FinanceSoftwarePushPort push;

  public FinanceSoftwareService(
      DSLContext sql, FinanceVoucherPort finance, FinanceSoftwarePushPort push) {
    this.sql = sql;
    this.finance = finance;
    this.push = push;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 状态冲突的判定式写法：参数合法、但**当前状态**不允许这个动作 ⇒ 409。 */
  private static void conflictIf(boolean violated, String message) {
    if (violated) conflict(message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 可选字段：空串合法，超长不合法。 */
  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 币种：三个大写字母。 */
  private static String currency(String raw, String label) {
    var trimmed = required(raw, label, 3).toUpperCase(Locale.ROOT);
    check(trimmed.length() == 3 && trimmed.chars().allMatch(Character::isLetter),
        label + "必须是三个字母的币种代码（如 USD / CNY）");
    return trimmed;
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  /** 金额转成不带尾随零的纯数字串（**不丢精度**，也不引入浮点）。 */
  private static String plain(BigDecimal value) {
    if (value == null) return "0";
    if (value.signum() == 0) return "0";
    return value.stripTrailingZeros().toPlainString();
  }

  // ================================================================ 能力声明

  /**
   * 18.2.1.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **直连推送与直连同步两维如实答「没有」** —— 本仓没有任何真实的财务软件适配器。
   * 能力声明把这两格写清楚，「缺在谁身上」才是一条**可查的事实**，而不是一句注释。
   */
  public FinanceSoftwareCapabilities capabilities() {
    var capabilities =
        List.of(
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "外部财务软件连接档案（金蝶 / 用友 / SAP / Odoo）",
                true,
                "platform_finance_account 表 + 连接档案端点；记厂商 / 账套 / 本位币 / 启用状态，"
                    + "**不存任何账号口令**（接入凭据走 secret manager）",
                "",
                ""),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "科目映射（本系统科目 ↔ 外部科目）",
                true,
                "platform_finance_subject_map 表 + 映射端点；**真的在用** —— 导出凭证文件时"
                    + "本地科目被换成外部科目编码，余额核对时外部科目被换回本地科目编码",
                "",
                "没配映射的行在导出结果里被标为 UNMAPPED（计入 unmapped_line_count），"
                    + "绝不静默丢行"),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "主数据映射（客户 / 供应商 / 平台 / 资金账户 / 商品 / 部门）",
                true,
                "platform_finance_master_map 表 + 映射端点；类型词表与 12.5.2.1 凭证行的"
                    + "**辅助核算类型**对齐，导出时按辅助核算名称匹配出外部编码",
                "",
                ""),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "凭证文件导出（CSV / Excel / XML）",
                true,
                "真读上游 12.5.2.1 已过账凭证 → 套科目映射 → 生成**真文件内容** → 落 sha256 与"
                    + "字节数到 platform_finance_voucher_file；内容**落库**，事后能原样取回",
                "",
                ""),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "余额核对差异（本地试算平衡表 × 导入的外部余额）",
                true,
                "本地余额真读上游 12.5.2.2 试算平衡表（按科目余额方向取正负）；"
                    + "外部余额来自导入的快照行（带方向）；两者同口径后逐科目相减，"
                    + "差异落 platform_finance_reconcile_diff",
                "",
                "四种差异原因：金额不等 / 仅本地有 / 仅外部有 / 币种不一致"),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "跨服务读取凭证与余额（12.5.2.1 / 12.5.2.2）",
                true,
                "FinanceVoucherPort（finance 8087）只读端口；只转发调用方自己的 bearer，"
                    + "取不到一律 503（是「读不到」，不是「没有数据」）",
                "",
                ""),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "**直连推送凭证到外部财务软件**",
                false,
                "本仓目前没有任何真实的财务软件适配器"
                    + "（backend / services / tools 下不存在 kingdee / yonyou / sap / odoo 之类的客户端）",
                "18.2",
                "推送端点如实答 409 且**一次数据都不写**；"
                    + "在此之前走「导出凭证文件 → 导入对方系统」这条完整路径"),
            new FinanceSoftwareCapability(
                "18.2.1.1",
                "**直连同步主数据到外部财务软件**",
                false,
                "与直连推送同一条外部通道（同一段适配器缺口）",
                "18.2",
                "同步端点如实答 409 且**一次数据都不写**；"
                    + "主数据映射本身已可用（真落库、真参与凭证文件导出）"));
    var gaps =
        List.of(
            new FinanceSoftwareGap(
                "18.2.2.1",
                "支付与资金",
                "platform",
                false,
                "同卷兄弟",
                "本车道**不依赖**它，也**没有**顺手实现它 —— 支付与资金是另一套数据与口径"),
            new FinanceSoftwareGap(
                "18.2.3.1",
                "其他对接",
                "platform",
                false,
                "同卷兄弟",
                "「其他对接」按蓝图是税务 / HR / 物流等零散外部系统，与财务软件不是一条通道"),
            new FinanceSoftwareGap(
                "18.1.2.1",
                "事件订阅",
                "platform",
                false,
                "上游",
                "事件订阅管的是「外部系统推事件进来」；本模块是「本系统把凭证交出去」，"
                    + "方向相反，不复用那条通道"));
    return new FinanceSoftwareCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public FinanceSoftwareOverview overview() {
    return new FinanceSoftwareOverview(
        count("select count(*) from platform_finance_account"),
        count("select count(*) from platform_finance_account where status = 'ACTIVE'"),
        count("select count(*) from platform_finance_subject_map"),
        count("select count(*) from platform_finance_master_map"),
        count("select count(*) from platform_finance_voucher_file"),
        count("select count(*) from platform_finance_reconcile_task"),
        count("select count(*) from platform_finance_reconcile_task where status = 'COMPUTED'"),
        count("select count(*) from platform_finance_reconcile_diff"),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 连接档案

  /** 连接档案列表（真数据 + 真实计数）。 */
  public List<FinanceSoftwareAccountRow> accounts(String vendor, String status) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (vendor != null && !vendor.isBlank()) {
      where.append(" and a.vendor = ?");
      params.add(vendor.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and a.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select a.id,a.name,a.vendor,a.book_code,a.company_name,a.currency,"
                + " a.fiscal_start_month,a.status,a.remark,a.created_by,a.created_at,a.updated_at,"
                + " (select count(*) from platform_finance_subject_map m where m.account_id = a.id)"
                + "   as subject_map_count,"
                + " (select count(*) from platform_finance_master_map m where m.account_id = a.id)"
                + "   as master_map_count,"
                + " (select count(*) from platform_finance_voucher_file f where f.account_id = a.id)"
                + "   as voucher_file_count"
                + " from platform_finance_account a"
                + where
                + " order by a.created_at desc, a.id",
            params.toArray())
        .map(FinanceSoftwareService::toAccountRow);
  }

  /** 新增或修改一个连接档案。 */
  public FinanceSoftwareAccountRow saveAccount(
      FinanceSoftwareAccountInput input, AccessService.Access actor) {
    var name = required(input.name(), "连接名", 120);
    var vendor = FinanceSoftwareVendor.parse(input.vendor());
    check(vendor != null, "厂商只能是 KINGDEE（金蝶）/ YONYOU（用友）/ SAP / ODOO");
    var bookCode = required(input.bookCode(), "账套号", 64);
    var companyName = optional(input.companyName(), "外部主体名称", 200);
    var ccy = currency(input.currency(), "本位币");
    var fiscalStartMonth =
        input.fiscalStartMonth() == null ? 1 : input.fiscalStartMonth();
    check(fiscalStartMonth >= 1 && fiscalStartMonth <= 12, "起始月份只能是 1 到 12");
    var status =
        input.status() == null || input.status().isBlank()
            ? "ACTIVE"
            : input.status().trim().toUpperCase(Locale.ROOT);
    check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
    var remark = optional(input.remark(), "备注", 500);

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var existing =
        sql.fetchOne("select id from platform_finance_account where name = ?", name);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_finance_account(id,tenant_id,name,vendor,book_code,company_name,"
              + " currency,fiscal_start_month,status,remark,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          name,
          vendor.name(),
          bookCode,
          companyName,
          ccy,
          fiscalStartMonth,
          status,
          remark,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_finance_account set vendor = ?, book_code = ?, company_name = ?,"
              + " currency = ?, fiscal_start_month = ?, status = ?, remark = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          vendor.name(),
          bookCode,
          companyName,
          ccy,
          fiscalStartMonth,
          status,
          remark,
          now,
          id);
    }
    return accountRow(id);
  }

  /** 停用一个连接档案（**不删**：历史凭证文件与核对任务要能复现）。 */
  public FinanceSoftwareAccountRow disableAccount(UUID id, AccessService.Access actor) {
    var row = sql.fetchOne("select id from platform_finance_account where id = ?", id);
    check(row != null, "连接档案不存在");
    sql.execute(
        "update platform_finance_account set status = 'DISABLED', updated_at = ?::timestamptz"
            + " where id = ?",
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    return accountRow(id);
  }

  private FinanceSoftwareAccountRow accountRow(UUID id) {
    return accounts(null, null).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "连接档案不存在"));
  }

  private static FinanceSoftwareAccountRow toAccountRow(org.jooq.Record r) {
    var vendor = FinanceSoftwareVendor.parse(r.get("vendor", String.class));
    var status = r.get("status", String.class);
    return new FinanceSoftwareAccountRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        vendor == null ? "" : vendor.name(),
        vendor == null ? "" : vendor.label(),
        r.get("book_code", String.class),
        r.get("company_name", String.class),
        r.get("currency", String.class),
        r.get("fiscal_start_month", Integer.class),
        status,
        "ACTIVE".equals(status) ? "启用" : "停用",
        r.get("subject_map_count", Long.class),
        r.get("master_map_count", Long.class),
        r.get("voucher_file_count", Long.class),
        r.get("remark", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 科目映射

  /** 科目映射列表（真筛选 + 真关键词）。 */
  public List<FinanceSoftwareSubjectMapRow> subjectMaps(UUID accountId, String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (accountId != null) {
      where.append(" and m.account_id = ?");
      params.add(accountId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (m.local_subject_code ilike ? or m.external_subject_code ilike ?"
          + " or m.local_subject_name ilike ? or m.external_subject_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return sql.fetch(
            "select m.id,m.account_id,a.name as account_name,m.local_subject_code,"
                + " m.local_subject_name,m.external_subject_code,m.external_subject_name,m.tax_code,"
                + " m.direction,m.enabled,m.remark,m.created_at,m.updated_at"
                + " from platform_finance_subject_map m"
                + " join platform_finance_account a on a.tenant_id = m.tenant_id and a.id = m.account_id"
                + where
                + " order by m.local_subject_code, m.external_subject_code",
            params.toArray())
        .map(FinanceSoftwareService::toSubjectMapRow);
  }

  /** 新增或修改一条科目映射。 */
  public FinanceSoftwareSubjectMapRow saveSubjectMap(
      FinanceSoftwareSubjectMapInput input, AccessService.Access actor) {
    var accountId = input.accountId();
    requireAccount(accountId);
    var localCode = required(input.localSubjectCode(), "本系统科目编码", 64);
    var localName = optional(input.localSubjectName(), "本系统科目名称", 200);
    var externalCode = required(input.externalSubjectCode(), "外部科目编码", 64);
    var externalName = optional(input.externalSubjectName(), "外部科目名称", 200);
    var taxCode = optional(input.taxCode(), "税码", 32);
    var direction = input.direction() == null ? "" : input.direction().trim().toUpperCase(Locale.ROOT);
    if (!direction.isEmpty()) {
      check(
          List.of("DEBIT", "CREDIT").contains(direction),
          "方向只能是 DEBIT（借方）或 CREDIT（贷方），留空表示跟随本地科目余额方向");
    }
    var enabled = input.enabled() == null || input.enabled();
    var remark = optional(input.remark(), "备注", 500);

    // 🔴 **先查再写**：一个外部科目不能映射到两个本地科目（导出后两笔业务会并到
    //   同一张法定凭证的同一行）。让数据库的 unique 约束抛错会变成一个没有解释的 500，
    //   所以这里先查并给一句能直接展示的话。
    var clash =
        sql.fetchOne(
            "select id from platform_finance_subject_map"
                + " where account_id = ? and external_subject_code = ? and local_subject_code <> ?",
            accountId,
            externalCode,
            localCode);
    check(clash == null, "外部科目编码「" + externalCode + "」已经映射到别的本系统科目了");

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var existing =
        sql.fetchOne(
            "select id from platform_finance_subject_map where account_id = ? and local_subject_code = ?",
            accountId,
            localCode);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_finance_subject_map(id,tenant_id,account_id,local_subject_code,"
              + " local_subject_name,external_subject_code,external_subject_name,tax_code,direction,"
              + " enabled,remark,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          accountId,
          localCode,
          localName,
          externalCode,
          externalName,
          taxCode,
          direction,
          enabled,
          remark,
          now,
          now);
    } else {
      sql.execute(
          "update platform_finance_subject_map set local_subject_name = ?, external_subject_code = ?,"
              + " external_subject_name = ?, tax_code = ?, direction = ?, enabled = ?, remark = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          localName,
          externalCode,
          externalName,
          taxCode,
          direction,
          enabled,
          remark,
          now,
          id);
    }
    return subjectMapRow(id);
  }

  /** 删除一条科目映射。 */
  public void deleteSubjectMap(UUID id) {
    var row = sql.fetchOne("select id from platform_finance_subject_map where id = ?", id);
    check(row != null, "科目映射不存在");
    sql.execute("delete from platform_finance_subject_map where id = ?", id);
  }

  private FinanceSoftwareSubjectMapRow subjectMapRow(UUID id) {
    return subjectMaps(null, null).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "科目映射不存在"));
  }

  private static FinanceSoftwareSubjectMapRow toSubjectMapRow(org.jooq.Record r) {
    var direction = FinanceSoftwareDirection.parse(r.get("direction", String.class));
    return new FinanceSoftwareSubjectMapRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        r.get("account_name", String.class),
        r.get("local_subject_code", String.class),
        r.get("local_subject_name", String.class),
        r.get("external_subject_code", String.class),
        r.get("external_subject_name", String.class),
        r.get("tax_code", String.class),
        direction == null ? "" : direction.name(),
        direction == null ? "跟随本地科目" : direction.label(),
        r.get("enabled", Boolean.class),
        r.get("remark", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 主数据映射

  /** 主数据映射列表（真筛选）。 */
  public List<FinanceSoftwareMasterMapRow> masterMaps(UUID accountId, String masterType) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (accountId != null) {
      where.append(" and m.account_id = ?");
      params.add(accountId);
    }
    if (masterType != null && !masterType.isBlank()) {
      where.append(" and m.master_type = ?");
      params.add(masterType.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select m.id,m.account_id,a.name as account_name,m.master_type,m.local_code,"
                + " m.local_name,m.external_code,m.external_name,m.enabled,m.remark,"
                + " m.created_at,m.updated_at"
                + " from platform_finance_master_map m"
                + " join platform_finance_account a on a.tenant_id = m.tenant_id and a.id = m.account_id"
                + where
                + " order by m.master_type, m.local_code",
            params.toArray())
        .map(FinanceSoftwareService::toMasterMapRow);
  }

  /** 新增或修改一条主数据映射。 */
  public FinanceSoftwareMasterMapRow saveMasterMap(
      FinanceSoftwareMasterMapInput input, AccessService.Access actor) {
    var accountId = input.accountId();
    requireAccount(accountId);
    var type = FinanceSoftwareMasterType.parse(input.masterType());
    check(
        type != null,
        "主数据类型只能是 CUSTOMER / SUPPLIER / PLATFORM / ACCOUNT / PRODUCT / DEPARTMENT");
    var localCode = required(input.localCode(), "本地编码", 64);
    var localName = required(input.localName(), "本地名称", 200);
    var externalCode = required(input.externalCode(), "外部编码", 64);
    var externalName = optional(input.externalName(), "外部名称", 200);
    var enabled = input.enabled() == null || input.enabled();
    var remark = optional(input.remark(), "备注", 500);

    var clashCode =
        sql.fetchOne(
            "select id from platform_finance_master_map"
                + " where account_id = ? and master_type = ? and external_code = ?"
                + " and not (local_code = ? and local_name = ?)",
            accountId,
            type.name(),
            externalCode,
            localCode,
            localName);
    check(clashCode == null, "外部编码「" + externalCode + "」已经映射到别的本地主数据了");

    var clashName =
        sql.fetchOne(
            "select id from platform_finance_master_map"
                + " where account_id = ? and master_type = ? and local_name = ?"
                + " and not (local_code = ?)",
            accountId,
            type.name(),
            localName,
            localCode);
    check(
        clashName == null,
        "本地名称「" + localName + "」在这个账套的同类主数据里已经有一条了 —— "
            + "导出凭证文件时按名称匹配，重名会对不上");

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var existing =
        sql.fetchOne(
            "select id from platform_finance_master_map"
                + " where account_id = ? and master_type = ? and local_code = ?",
            accountId,
            type.name(),
            localCode);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_finance_master_map(id,tenant_id,account_id,master_type,local_code,"
              + " local_name,external_code,external_name,enabled,remark,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          accountId,
          type.name(),
          localCode,
          localName,
          externalCode,
          externalName,
          enabled,
          remark,
          now,
          now);
    } else {
      sql.execute(
          "update platform_finance_master_map set local_name = ?, external_code = ?,"
              + " external_name = ?, enabled = ?, remark = ?, updated_at = ?::timestamptz where id = ?",
          localName,
          externalCode,
          externalName,
          enabled,
          remark,
          now,
          id);
    }
    return masterMapRow(id);
  }

  /** 删除一条主数据映射。 */
  public void deleteMasterMap(UUID id) {
    var row = sql.fetchOne("select id from platform_finance_master_map where id = ?", id);
    check(row != null, "主数据映射不存在");
    sql.execute("delete from platform_finance_master_map where id = ?", id);
  }

  private FinanceSoftwareMasterMapRow masterMapRow(UUID id) {
    return masterMaps(null, null).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "主数据映射不存在"));
  }

  private static FinanceSoftwareMasterMapRow toMasterMapRow(org.jooq.Record r) {
    var type = FinanceSoftwareMasterType.parse(r.get("master_type", String.class));
    return new FinanceSoftwareMasterMapRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        r.get("account_name", String.class),
        type == null ? "" : type.name(),
        type == null ? "" : type.label(),
        r.get("local_code", String.class),
        r.get("local_name", String.class),
        r.get("external_code", String.class),
        r.get("external_name", String.class),
        r.get("enabled", Boolean.class),
        r.get("remark", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 凭证文件导出

  /** 凭证文件导出记录列表（真数据）。 */
  public List<FinanceSoftwareVoucherFileRow> voucherFiles(UUID accountId) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (accountId != null) {
      where.append(" and f.account_id = ?");
      params.add(accountId);
    }
    return sql.fetch(
            "select f.id,f.account_id,a.name as account_name,f.file_no,f.format,f.filename,"
                + " f.content_sha256,f.content_bytes,f.voucher_count,f.line_count,"
                + " f.unmapped_line_count,f.total_debit,f.total_credit,f.currency,"
                + " f.date_from,f.date_to,f.created_by,f.created_at"
                + " from platform_finance_voucher_file f"
                + " join platform_finance_account a on a.tenant_id = f.tenant_id and a.id = f.account_id"
                + where
                + " order by f.created_at desc, f.id",
            params.toArray())
        .map(FinanceSoftwareService::toVoucherFileRow);
  }

  /** 凭证文件详情（**含真内容** —— 事后要能原样取回当初导出的那一份）。 */
  public FinanceSoftwareVoucherFileDetail voucherFile(UUID id) {
    var row =
        sql.fetchOne(
            "select f.id,f.account_id,a.name as account_name,f.file_no,f.format,f.filename,"
                + " f.content_sha256,f.content_bytes,f.voucher_count,f.line_count,"
                + " f.unmapped_line_count,f.total_debit,f.total_credit,f.currency,"
                + " f.date_from,f.date_to,f.created_by,f.created_at,f.content"
                + " from platform_finance_voucher_file f"
                + " join platform_finance_account a on a.tenant_id = f.tenant_id and a.id = f.account_id"
                + " where f.id = ?",
            id);
    check(row != null, "凭证文件不存在");
    var format = FinanceSoftwareFileFormat.parse(row.get("format", String.class));
    return new FinanceSoftwareVoucherFileDetail(
        toVoucherFileRow(row),
        contentTypeOf(format),
        row.get("content", String.class));
  }

  /**
   * 导出凭证文件。
   *
   * <p>🔴 **真内容、真留痕**：真读上游已过账凭证 → 套科目映射 → 生成文件内容 →
   * 落 sha256 / 字节数 / 借贷合计 / 未映射行数 → 内容一并落库（事后能原样取回）。
   *
   * <p>🔴 **只导已过账的凭证**：草稿不是账。
   */
  public FinanceSoftwareVoucherExportResult exportVoucherFile(
      FinanceSoftwareVoucherExportInput input, AccessService.Access actor, String bearer) {
    var account = requireAccount(input.accountId());
    var format = FinanceSoftwareFileFormat.parse(input.format());
    check(format != null, "文件格式只能是 CSV / EXCEL / XML");
    check(input.dateFrom() != null, "起始日期不能为空");
    check(input.dateTo() != null, "截止日期不能为空");
    check(!input.dateFrom().isAfter(input.dateTo()), "起始日期不能晚于截止日期");

    var ccy = account.currency();
    var vouchers = fetchPostedVouchers(input.dateFrom(), input.dateTo(), ccy, bearer);
    check(
        !vouchers.isEmpty(),
        "这个区间里没有已过账的凭证（草稿不是账）—— 换个日期区间，或先在 12.5.2.1 把凭证过账");

    var subjectMap = subjectMapIndex(account.id());
    var masterMap = masterMapIndex(account.id());

    var lines = new ArrayList<ExportLine>();
    var totalDebit = BigDecimal.ZERO;
    var totalCredit = BigDecimal.ZERO;
    var unmapped = 0;
    for (var voucher : vouchers) {
      var detail = finance.voucher(voucher.id(), bearer);
      var head = detail.voucher() == null ? voucher : detail.voucher();
      for (var line : detail.lines()) {
        var externalSubject = subjectMap.get(line.subjectCode());
        var auxiliaryCode = "";
        var masterType = masterTypeOf(line.auxiliaryType());
        if (masterType != null && line.auxiliaryName() != null && !line.auxiliaryName().isBlank()) {
          auxiliaryCode = masterMap.getOrDefault(masterType.name() + "|" + line.auxiliaryName().trim().toLowerCase(Locale.ROOT), "");
        }
        var isDebit = "DEBIT".equalsIgnoreCase(line.lineType() == null ? "" : line.lineType());
        var amount = line.amount() == null ? BigDecimal.ZERO : line.amount();
        if (isDebit) {
          totalDebit = totalDebit.add(amount);
        } else {
          totalCredit = totalCredit.add(amount);
        }
        // 🔴 没映射到外部科目的行**标出来**（计入 unmapped），绝不静默丢掉 ——
        //   丢掉的行会让导出的文件看起来是完整的，而它不是。
        if (externalSubject == null || externalSubject.isBlank()) unmapped++;
        lines.add(
            new ExportLine(
                head.voucherNo() == null ? "" : head.voucherNo(),
                head.voucherDate(),
                head.periodCode() == null ? "" : head.periodCode(),
                line.lineNo(),
                line.subjectCode() == null ? "" : line.subjectCode(),
                line.subjectName() == null ? "" : line.subjectName(),
                externalSubject == null ? "" : externalSubject,
                line.summary() == null ? "" : line.summary(),
                isDebit,
                amount,
                line.currency() == null ? ccy : line.currency(),
                line.auxiliaryType() == null ? "" : line.auxiliaryType(),
                line.auxiliaryName() == null ? "" : line.auxiliaryName(),
                auxiliaryCode));
      }
    }

    var filename =
        "vouchers-"
            + account.bookCode()
            + "-"
            + input.dateFrom()
            + "-"
            + input.dateTo()
            + "."
            + switch (format) {
              case CSV -> "csv";
              case EXCEL -> "xml";
              case XML -> "xml";
            };
    var content = switch (format) {
      case CSV -> buildCsv(lines);
      case EXCEL -> buildExcel(lines, account, input.dateFrom(), input.dateTo());
      case XML -> buildXml(lines, account, input.dateFrom(), input.dateTo());
    };
    var bytes = content.getBytes(StandardCharsets.UTF_8);
    var sha256 = sha256(bytes);

    var fileNo = nextFileNo();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_finance_voucher_file(id,tenant_id,account_id,file_no,format,filename,"
            + " content,content_sha256,content_bytes,voucher_count,line_count,unmapped_line_count,"
            + " total_debit,total_credit,currency,date_from,date_to,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        account.id(),
        fileNo,
        format.name(),
        filename,
        content,
        sha256,
        bytes.length,
        vouchers.size(),
        lines.size(),
        unmapped,
        totalDebit,
        totalCredit,
        ccy,
        input.dateFrom(),
        input.dateTo(),
        actor.userId(),
        now);

    return new FinanceSoftwareVoucherExportResult(
        id,
        fileNo,
        format.name(),
        filename,
        contentTypeOf(format),
        content,
        sha256,
        bytes.length,
        vouchers.size(),
        lines.size(),
        unmapped,
        totalDebit,
        totalCredit,
        ccy,
        input.dateFrom(),
        input.dateTo());
  }

  /** 真拉已过账凭证（分页拉完；超上限直接报错，不偷偷截断）。 */
  private List<FinanceVoucherPort.VoucherRow> fetchPostedVouchers(
      LocalDate dateFrom, LocalDate dateTo, String currency, String bearer) {
    var all = new ArrayList<FinanceVoucherPort.VoucherRow>();
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      var result =
          finance.vouchers("POSTED", dateFrom, dateTo, currency, page, PlatformUpstreamClient.PAGE_SIZE, bearer);
      if (result.items() != null) all.addAll(result.items());
      if (all.size() > MAX_EXPORT_VOUCHERS) {
        check(
            false,
            "这个区间里的已过账凭证超过 "
                + MAX_EXPORT_VOUCHERS
                + " 张 —— 请缩小日期区间分批导出（导出要逐张取分录行，一次拉太多会把上游打满）");
      }
      if (result.items() == null || result.items().isEmpty() || all.size() >= result.total()) break;
      page++;
    }
    return all;
  }

  /** 科目映射索引：本地科目编码 → 外部科目编码（只取启用的）。 */
  private Map<String, String> subjectMapIndex(UUID accountId) {
    var index = new LinkedHashMap<String, String>();
    for (var row : subjectMaps(accountId, null)) {
      if (!row.enabled()) continue;
      index.put(row.localSubjectCode(), row.externalSubjectCode());
    }
    return index;
  }

  /** 主数据映射索引：类型 + 名称（小写） → 外部编码（只取启用的）。 */
  private Map<String, String> masterMapIndex(UUID accountId) {
    var index = new LinkedHashMap<String, String>();
    for (var row : masterMaps(accountId, null)) {
      if (!row.enabled()) continue;
      index.put(row.masterType() + "|" + row.localName().trim().toLowerCase(Locale.ROOT), row.externalCode());
    }
    return index;
  }

  /** 凭证行的辅助核算类型 → 主数据映射的类型。 */
  private static FinanceSoftwareMasterType masterTypeOf(String auxiliaryType) {
    if (auxiliaryType == null) return null;
    return switch (auxiliaryType.trim().toUpperCase(Locale.ROOT)) {
      case "CUSTOMER" -> FinanceSoftwareMasterType.CUSTOMER;
      case "SUPPLIER" -> FinanceSoftwareMasterType.SUPPLIER;
      case "PLATFORM" -> FinanceSoftwareMasterType.PLATFORM;
      case "ACCOUNT" -> FinanceSoftwareMasterType.ACCOUNT;
      default -> null;
    };
  }

  /** 导出用的一行。 */
  private record ExportLine(
      String voucherNo,
      LocalDate voucherDate,
      String periodCode,
      int lineNo,
      String localSubjectCode,
      String localSubjectName,
      String externalSubjectCode,
      String summary,
      boolean debit,
      BigDecimal amount,
      String currency,
      String auxiliaryType,
      String auxiliaryName,
      String externalAuxiliaryCode) {}

  private static FinanceSoftwareVoucherFileRow toVoucherFileRow(org.jooq.Record r) {
    return new FinanceSoftwareVoucherFileRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        r.get("account_name", String.class),
        r.get("file_no", String.class),
        r.get("format", String.class),
        r.get("filename", String.class),
        r.get("content_sha256", String.class),
        r.get("content_bytes", Integer.class),
        r.get("voucher_count", Long.class),
        r.get("line_count", Long.class),
        r.get("unmapped_line_count", Long.class),
        r.get("total_debit", BigDecimal.class),
        r.get("total_credit", BigDecimal.class),
        r.get("currency", String.class),
        r.get("date_from", LocalDate.class),
        r.get("date_to", LocalDate.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 文件的 content type。 */
  static String contentTypeOf(FinanceSoftwareFileFormat format) {
    if (format == null) return "application/octet-stream";
    return switch (format) {
      case CSV -> "text/csv; charset=utf-8";
      case EXCEL -> "application/vnd.ms-excel";
      case XML -> "application/xml; charset=utf-8";
    };
  }

  /** CSV 内容（**带 UTF-8 BOM** —— Excel 双击打开中文不乱码）。 */
  private static String buildCsv(List<ExportLine> lines) {
    var sb = new StringBuilder("﻿");
    sb.append(
        "凭证号,凭证日期,会计期间,行号,本系统科目编码,本系统科目名称,外部科目编码,摘要,"
            + "借方金额,贷方金额,币种,辅助核算类型,辅助核算名称,外部辅助核算编码\n");
    for (var line : lines) {
      sb.append(csvCell(line.voucherNo())).append(',')
          .append(csvCell(line.voucherDate() == null ? "" : line.voucherDate().toString()))
          .append(',')
          .append(csvCell(line.periodCode())).append(',')
          .append(csvCell(String.valueOf(line.lineNo()))).append(',')
          .append(csvCell(line.localSubjectCode())).append(',')
          .append(csvCell(line.localSubjectName())).append(',')
          .append(csvCell(line.externalSubjectCode())).append(',')
          .append(csvCell(line.summary())).append(',')
          .append(csvCell(line.debit() ? plain(line.amount()) : "0")).append(',')
          .append(csvCell(line.debit() ? "0" : plain(line.amount()))).append(',')
          .append(csvCell(line.currency())).append(',')
          .append(csvCell(line.auxiliaryType())).append(',')
          .append(csvCell(line.auxiliaryName())).append(',')
          .append(csvCell(line.externalAuxiliaryCode()))
          .append('\n');
    }
    return sb.toString();
  }

  private static String csvCell(String raw) {
    if (raw == null) return "";
    if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0 && raw.indexOf('\n') < 0) return raw;
    return "\"" + raw.replace("\"", "\"\"") + "\"";
  }

  /** Excel 内容（SpreadsheetML 2003 —— Excel 与金蝶都能读）。 */
  private static String buildExcel(
      List<ExportLine> lines,
      FinanceSoftwareAccountRow account,
      LocalDate dateFrom,
      LocalDate dateTo) {
    var sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\"?>\n");
    sb.append("<?mso-application progid=\"Excel.Sheet\"?>\n");
    sb.append(
        "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\""
            + " xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
    sb.append(" <Worksheet ss:Name=\"凭证\">\n  <Table>\n");
    sb.append("   <Row>");
    for (var header :
        List.of(
            "凭证号",
            "凭证日期",
            "会计期间",
            "行号",
            "本系统科目编码",
            "本系统科目名称",
            "外部科目编码",
            "摘要",
            "借方金额",
            "贷方金额",
            "币种",
            "辅助核算类型",
            "辅助核算名称",
            "外部辅助核算编码")) {
      sb.append("<Cell><Data ss:Type=\"String\">").append(xmlEscape(header)).append("</Data></Cell>");
    }
    sb.append("</Row>\n");
    for (var line : lines) {
      sb.append("   <Row>");
      sb.append(cell(line.voucherNo()));
      sb.append(cell(line.voucherDate() == null ? "" : line.voucherDate().toString()));
      sb.append(cell(line.periodCode()));
      sb.append(cell(String.valueOf(line.lineNo())));
      sb.append(cell(line.localSubjectCode()));
      sb.append(cell(line.localSubjectName()));
      sb.append(cell(line.externalSubjectCode()));
      sb.append(cell(line.summary()));
      sb.append(cell(line.debit() ? plain(line.amount()) : "0"));
      sb.append(cell(line.debit() ? "0" : plain(line.amount())));
      sb.append(cell(line.currency()));
      sb.append(cell(line.auxiliaryType()));
      sb.append(cell(line.auxiliaryName()));
      sb.append(cell(line.externalAuxiliaryCode()));
      sb.append("</Row>\n");
    }
    sb.append("  </Table>\n </Worksheet>\n");
    sb.append(
        " <Worksheet ss:Name=\"文件信息\">\n  <Table>\n"
            + "   <Row><Cell><Data ss:Type=\"String\">账套</Data></Cell><Cell><Data ss:Type=\"String\">"
            + xmlEscape(account.bookCode())
            + "</Data></Cell></Row>\n"
            + "   <Row><Cell><Data ss:Type=\"String\">本位币</Data></Cell><Cell><Data ss:Type=\"String\">"
            + xmlEscape(account.currency())
            + "</Data></Cell></Row>\n"
            + "   <Row><Cell><Data ss:Type=\"String\">起始日期</Data></Cell><Cell><Data ss:Type=\"String\">"
            + xmlEscape(dateFrom.toString())
            + "</Data></Cell></Row>\n"
            + "   <Row><Cell><Data ss:Type=\"String\">截止日期</Data></Cell><Cell><Data ss:Type=\"String\">"
            + xmlEscape(dateTo.toString())
            + "</Data></Cell></Row>\n"
            + "  </Table>\n </Worksheet>\n");
    sb.append("</Workbook>\n");
    return sb.toString();
  }

  private static String cell(String value) {
    return "<Cell><Data ss:Type=\"String\">" + xmlEscape(value) + "</Data></Cell>";
  }

  /** 通用凭证 XML 内容。 */
  private static String buildXml(
      List<ExportLine> lines,
      FinanceSoftwareAccountRow account,
      LocalDate dateFrom,
      LocalDate dateTo) {
    var sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append(
        "<Vouchers account=\""
            + xmlEscape(account.name())
            + "\" vendor=\""
            + xmlEscape(account.vendor())
            + "\" bookCode=\""
            + xmlEscape(account.bookCode())
            + "\" currency=\""
            + xmlEscape(account.currency())
            + "\" dateFrom=\""
            + xmlEscape(dateFrom.toString())
            + "\" dateTo=\""
            + xmlEscape(dateTo.toString())
            + "\">\n");
    var currentVoucher = "";
    for (var line : lines) {
      if (!line.voucherNo().equals(currentVoucher)) {
        if (!currentVoucher.isEmpty()) sb.append("  </Voucher>\n");
        sb.append("  <Voucher no=\"")
            .append(xmlEscape(line.voucherNo()))
            .append("\" date=\"")
            .append(line.voucherDate() == null ? "" : xmlEscape(line.voucherDate().toString()))
            .append("\" period=\"")
            .append(xmlEscape(line.periodCode()))
            .append("\">\n");
        currentVoucher = line.voucherNo();
      }
      sb.append("    <Entry no=\"")
          .append(line.lineNo())
          .append("\" type=\"")
          .append(line.debit() ? "DEBIT" : "CREDIT")
          .append("\" localSubjectCode=\"")
          .append(xmlEscape(line.localSubjectCode()))
          .append("\" subjectCode=\"")
          .append(xmlEscape(line.externalSubjectCode()))
          .append("\" subjectName=\"")
          .append(xmlEscape(line.localSubjectName()))
          .append("\" summary=\"")
          .append(xmlEscape(line.summary()))
          .append("\" amount=\"")
          .append(plain(line.amount()))
          .append("\" currency=\"")
          .append(xmlEscape(line.currency()))
          .append("\" auxiliaryType=\"")
          .append(xmlEscape(line.auxiliaryType()))
          .append("\" auxiliaryName=\"")
          .append(xmlEscape(line.auxiliaryName()))
          .append("\" auxiliaryCode=\"")
          .append(xmlEscape(line.externalAuxiliaryCode()))
          .append("\"/>\n");
    }
    if (!currentVoucher.isEmpty()) sb.append("  </Voucher>\n");
    sb.append("</Vouchers>\n");
    return sb.toString();
  }

  private static String xmlEscape(String raw) {
    if (raw == null) return "";
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String sha256(byte[] bytes) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var out = new StringBuilder();
      for (var b : digest.digest(bytes)) out.append(String.format("%02x", b));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 导出单号：租户短码 + 全局序号（**租户号段看得见**，避免跨租户互相撞号）。 */
  private String nextFileNo() {
    var tenant = currentTenant();
    var seq =
        sql.fetchOne("select nextval('platform_finance_voucher_file_no_seq')").get(0, Long.class);
    return "FS-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq);
  }

  // ================================================================ 余额核对

  /** 核对任务列表（真数据）。 */
  public List<FinanceSoftwareReconcileTaskRow> reconcileTasks(UUID accountId, String status) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (accountId != null) {
      where.append(" and t.account_id = ?");
      params.add(accountId);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select t.id,t.account_id,a.name as account_name,t.reconcile_no,t.period_from_id,"
                + " t.period_to_id,t.currency,t.status,t.local_subject_count,t.external_subject_count,"
                + " t.matched_count,t.difference_count,t.total_difference,t.computed_at,t.remark,"
                + " t.created_by,t.created_at,t.updated_at"
                + " from platform_finance_reconcile_task t"
                + " join platform_finance_account a on a.tenant_id = t.tenant_id and a.id = t.account_id"
                + where
                + " order by t.created_at desc, t.id",
            params.toArray())
        .map(FinanceSoftwareService::toReconcileTaskRow);
  }

  /** 建一个核对任务。 */
  public FinanceSoftwareReconcileTaskRow createReconcileTask(
      FinanceSoftwareReconcileTaskInput input, AccessService.Access actor, String bearer) {
    var account = requireAccount(input.accountId());
    check(input.periodFromId() != null, "起始会计期间不能为空");
    check(input.periodToId() != null, "截止会计期间不能为空");
    check(
        !input.periodFromId().equals(input.periodToId()),
        "起始会计期间与截止会计期间不能是同一个（那不是一段区间）");
    var ccy = currency(input.currency(), "币种");
    var remark = optional(input.remark(), "备注", 500);

    // 🔴 **期间必须是上游真有的**：本地校验一次比让上游返回一个读不懂的错误强。
    var options = finance.options(bearer);
    var known =
        options.periods() == null
            ? List.<FinanceVoucherPort.PeriodRef>of()
            : options.periods();
    check(
        known.stream().anyMatch(p -> p.id().equals(input.periodFromId().toString())),
        "起始会计期间在 12.5.2.1 里不存在");
    check(
        known.stream().anyMatch(p -> p.id().equals(input.periodToId().toString())),
        "截止会计期间在 12.5.2.1 里不存在");

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    var tenant = currentTenant();
    var seq =
        sql.fetchOne("select nextval('platform_finance_reconcile_no_seq')").get(0, Long.class);
    sql.execute(
        "insert into platform_finance_reconcile_task(id,tenant_id,account_id,reconcile_no,"
            + " period_from_id,period_to_id,currency,status,remark,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'DRAFT',?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        account.id(),
        "RC-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq),
        input.periodFromId(),
        input.periodToId(),
        ccy,
        remark,
        actor.userId(),
        now,
        now);
    return reconcileTaskRow(id);
  }

  /** 删除一个**还没算**的核对任务（算过的要留着 —— 它是一次核对的结论）。 */
  public void deleteReconcileTask(UUID id) {
    var row = sql.fetchOne("select status from platform_finance_reconcile_task where id = ?", id);
    check(row != null, "核对任务不存在");
    conflictIf(
        "COMPUTED".equals(row.get("status", String.class)),
        "已计算过的核对任务不能删（它是一次核对的结论）—— 重新建一个任务就好");
    sql.execute("delete from platform_finance_reconcile_task where id = ?", id);
  }

  /** 导入外部余额（一批科目；同科目重复导入**覆盖**）。 */
  public int importBalances(
      UUID taskId, FinanceSoftwareBalanceImportInput input, AccessService.Access actor) {
    var task = requireReconcileTask(taskId);
    check(
        input.balances() != null && !input.balances().isEmpty(),
        "导入的余额不能为空");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var tenant = currentTenant();
    var count = 0;
    for (var balance : input.balances()) {
      var code = required(balance.externalSubjectCode(), "外部科目编码", 64);
      var name = optional(balance.externalSubjectName(), "外部科目名称", 200);
      var ccy = currency(balance.currency(), "币种");
      var direction =
          balance.direction() == null
              ? ""
              : balance.direction().trim().toUpperCase(Locale.ROOT);
      check(
          List.of("DEBIT", "CREDIT").contains(direction),
          "余额方向只能是 DEBIT（借方）或 CREDIT（贷方）—— 方向缺了，同一个余额会差出一倍");
      var amount =
          balance.externalBalance() == null ? BigDecimal.ZERO : balance.externalBalance();
      check(
          amount.signum() >= 0,
          "余额请填**绝对值**，正负由方向列承载（填负数会让核对凭空差出一倍）");
      var existing =
          sql.fetchOne(
              "select id from platform_finance_reconcile_balance"
                  + " where task_id = ? and external_subject_code = ?",
              taskId,
              code);
      if (existing == null) {
        sql.execute(
            "insert into platform_finance_reconcile_balance(id,tenant_id,task_id,"
                + " external_subject_code,external_subject_name,currency,direction,external_balance,"
                + " created_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            taskId,
            code,
            name,
            ccy,
            direction,
            amount,
            now);
      } else {
        sql.execute(
            "update platform_finance_reconcile_balance set external_subject_name = ?, currency = ?,"
                + " direction = ?, external_balance = ? where id = ?",
            name,
            ccy,
            direction,
            amount,
            existing.get("id", UUID.class));
      }
      count++;
    }
    return count;
  }

  /** 外部余额快照行。 */
  public List<FinanceSoftwareReconcileBalanceRow> reconcileBalances(UUID taskId) {
    return sql.fetch(
            "select id,task_id,external_subject_code,external_subject_name,currency,direction,"
                + " external_balance,created_at from platform_finance_reconcile_balance"
                + " where task_id = ? order by external_subject_code",
            taskId)
        .map(
            r ->
                new FinanceSoftwareReconcileBalanceRow(
                    r.get("id", UUID.class),
                    r.get("task_id", UUID.class),
                    r.get("external_subject_code", String.class),
                    r.get("external_subject_name", String.class),
                    r.get("currency", String.class),
                    r.get("direction", String.class),
                    "DEBIT".equals(r.get("direction", String.class)) ? "借方" : "贷方",
                    r.get("external_balance", BigDecimal.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  /** 核对差异行。 */
  public List<FinanceSoftwareReconcileDiffRow> reconcileDiffs(UUID taskId) {
    return sql.fetch(
            "select id,task_id,external_subject_code,external_subject_name,local_subject_code,"
                + " local_subject_name,currency,external_balance,local_balance,difference,reason,detail,"
                + " created_at from platform_finance_reconcile_diff where task_id = ?"
                + " order by reason, external_subject_code",
            taskId)
        .map(
            r -> {
              var reason = r.get("reason", String.class);
              var label =
                  switch (reason) {
                    case "BALANCE_DIFF" -> "金额不等";
                    case "ONLY_LOCAL" -> "仅本地有";
                    case "ONLY_EXTERNAL" -> "仅外部有";
                    case "CURRENCY_MISMATCH" -> "币种不一致";
                    default -> "";
                  };
              return new FinanceSoftwareReconcileDiffRow(
                  r.get("id", UUID.class),
                  r.get("task_id", UUID.class),
                  r.get("external_subject_code", String.class),
                  r.get("external_subject_name", String.class),
                  r.get("local_subject_code", String.class),
                  r.get("local_subject_name", String.class),
                  r.get("currency", String.class),
                  r.get("external_balance", BigDecimal.class),
                  r.get("local_balance", BigDecimal.class),
                  r.get("difference", BigDecimal.class),
                  reason,
                  label,
                  r.get("detail", String.class),
                  r.get("created_at", OffsetDateTime.class));
            });
  }

  /** 核对详情：任务 + 导入的外部余额 + 算出来的差异。 */
  public FinanceSoftwareReconcileDetail reconcileDetail(UUID taskId) {
    return new FinanceSoftwareReconcileDetail(
        reconcileTaskRow(taskId), reconcileBalances(taskId), reconcileDiffs(taskId));
  }

  /**
   * **算**核对差异。
   *
   * <p>本地余额真读上游 12.5.2.2 试算平衡表（按科目余额方向取正负）；
   * 外部余额来自导入的快照行（带方向）；两者**同口径**后逐科目相减。
   *
   * <p>🔴 每次重算**替换**该任务的旧差异（同事务内 DELETE + INSERT）：
   * 差异是「这一次核对的现状」，不是历史流水。
   */
  public FinanceSoftwareReconcileTaskRow computeReconcile(
      UUID taskId, AccessService.Access actor, String bearer) {
    var task = requireReconcileTask(taskId);
    var balances = reconcileBalances(taskId);
    check(
        !balances.isEmpty(),
        "还没导入外部余额 —— 先把对方系统的余额表导进来，才能算出差异");

    var trial = finance.trialBalance(task.periodFromId(), task.periodToId(), task.currency(), bearer);
    var localBalances = new LinkedHashMap<String, LocalBalance>();
    for (var group : trial.currencyGroups() == null ? List.<FinanceVoucherPort.ReportGroup>of() : trial.currencyGroups()) {
      if (group.currency() != null && !group.currency().equals(task.currency())) continue;
      for (var row : group.rows() == null ? List.<FinanceVoucherPort.ReportRow>of() : group.rows()) {
        if (row.totalRow()) continue;
        if (row.subjectCode() == null || row.subjectCode().isBlank()) continue;
        localBalances.put(
            row.subjectCode(),
            new LocalBalance(
                row.subjectName() == null ? "" : row.subjectName(),
                row.balanceDirection() == null ? "" : row.balanceDirection(),
                row.closingBalance() == null ? BigDecimal.ZERO : row.closingBalance()));
      }
    }

    // 外部科目 → 本地科目（走**科目映射**；没有映射就无从比起）。
    var externalToLocal = new LinkedHashMap<String, String>();
    for (var row : subjectMaps(task.accountId(), null)) {
      if (!row.enabled()) continue;
      externalToLocal.put(row.externalSubjectCode(), row.localSubjectCode());
    }

    var diffs = new ArrayList<DiffLine>();
    var covered = new LinkedHashMap<String, Boolean>();
    var matched = 0;
    var totalDifference = BigDecimal.ZERO;
    for (var balance : balances) {
      var localCode = externalToLocal.get(balance.externalSubjectCode());
      var local = localCode == null ? null : localBalances.get(localCode);
      BigDecimal externalSigned;
      BigDecimal localSigned = BigDecimal.ZERO;
      String reason;
      String detail;
      if (!balance.currency().equals(task.currency())) {
        reason = "CURRENCY_MISMATCH";
        detail =
            "外部余额的币种是 "
                + balance.currency()
                + "，本次核对的口径是 "
                + task.currency()
                + " —— 币种不同不能相减，请先换算或分币种核对";
        externalSigned = signed(balance.externalBalance(), balance.direction());
      } else if (local == null) {
        reason = "ONLY_EXTERNAL";
        detail =
            localCode == null
                ? "外部有这个科目，但它没有配科目映射 —— 本系统这边不知道该跟哪个本地科目比"
                : "外部有这个科目，本地试算平衡表里没有对应科目（本地本期可能没有发生额）";
        externalSigned = signed(balance.externalBalance(), balance.direction());
        // 🔴 外部余额是 0 且本地没有对应科目 ⇒ 两边都是 0，**不是差异**。
        //   把 0 报成差异只会让差异表被噪声淹没，真正的差异反而看不见。
        if (externalSigned.signum() == 0) continue;
      } else {
        covered.put(localCode, Boolean.TRUE);
        localSigned = local.balance();
        // 🔴 **不把外部余额翻到本地方向**：两边的「借正贷负」是同一个自然口径。
        //   本地科目是借方 +100、外部说是贷方 100 ⇒ 事实就是差 200（一边是应收、
        //   一边是应付，或映射配错了科目性质）—— 翻一下就把 200 的差额抹成 0，
        //   那是在**抹平**一个真实存在的差异，不是「归一化」。
        externalSigned = signed(balance.externalBalance(), balance.direction());
        if (!local.direction().isEmpty()
            && !local.direction().equalsIgnoreCase(balance.direction())) {
          detail =
              "两边余额方向不同（本地 "
                  + local.direction()
                  + " / 外部 "
                  + balance.direction()
                  + "）—— 请先核对科目性质或映射是否配错";
          reason = "BALANCE_DIFF";
        } else {
          detail = "";
          reason = externalSigned.compareTo(localSigned) == 0 ? "MATCHED" : "BALANCE_DIFF";
        }
      }
      var difference = externalSigned.subtract(localSigned);
      if ("MATCHED".equals(reason)) {
        matched++;
      } else {
        totalDifference = totalDifference.add(difference.abs());
        diffs.add(
            new DiffLine(
                balance.externalSubjectCode(),
                balance.externalSubjectName(),
                localCode == null ? "" : localCode,
                local == null ? "" : local.name(),
                balance.currency(),
                externalSigned,
                localSigned,
                difference,
                reason,
                detail));
      }
    }

    // 本地有、外部没有的科目（走**反向**映射：本地科目没被任何外部余额覆盖）。
    for (var entry : localBalances.entrySet()) {
      if (covered.containsKey(entry.getKey())) continue;
      var local = entry.getValue();
      // 🔴 本地余额是 0 且外部没这一行 ⇒ 两边都是 0，**不是差异**（同上：别让噪声淹没差异）。
      if (local.balance().signum() == 0) continue;
      var externalCode = "";
      for (var row : subjectMaps(task.accountId(), null)) {
        if (row.enabled() && row.localSubjectCode().equals(entry.getKey())) {
          externalCode = row.externalSubjectCode();
          break;
        }
      }
      var difference = BigDecimal.ZERO.subtract(local.balance());
      totalDifference = totalDifference.add(difference.abs());
      diffs.add(
          new DiffLine(
              externalCode,
              "",
              entry.getKey(),
              local.name(),
              task.currency(),
              BigDecimal.ZERO,
              local.balance(),
              difference,
              "ONLY_LOCAL",
              "本地试算平衡表里有这个科目，但导入的外部余额里没有它 —— "
                  + "外部软件可能还没收到这部分业务"));
    }

    // 每次重算替换旧差异。
    sql.execute("delete from platform_finance_reconcile_diff where task_id = ?", taskId);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var tenant = currentTenant();
    for (var diff : diffs) {
      sql.execute(
          "insert into platform_finance_reconcile_diff(id,tenant_id,task_id,external_subject_code,"
              + " external_subject_name,local_subject_code,local_subject_name,currency,"
              + " external_balance,local_balance,difference,reason,detail,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          taskId,
          diff.externalSubjectCode(),
          diff.externalSubjectName(),
          diff.localSubjectCode(),
          diff.localSubjectName(),
          diff.currency(),
          diff.externalBalance(),
          diff.localBalance(),
          diff.difference(),
          diff.reason(),
          diff.detail(),
          now);
    }
    sql.execute(
        "update platform_finance_reconcile_task set status = 'COMPUTED', local_subject_count = ?,"
            + " external_subject_count = ?, matched_count = ?, difference_count = ?,"
            + " total_difference = ?, computed_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where id = ?",
        localBalances.size(),
        balances.size(),
        matched,
        diffs.size(),
        totalDifference,
        now,
        now,
        taskId);
    return reconcileTaskRow(taskId);
  }

  /** 余额按方向带符号：借方为正、贷方为负。 */
  private static BigDecimal signed(BigDecimal amount, String direction) {
    var value = amount == null ? BigDecimal.ZERO : amount;
    return "CREDIT".equalsIgnoreCase(direction) ? value.negate() : value;
  }

  /** 本地科目余额（按科目余额方向取正负，与上游试算平衡表同口径）。 */
  private record LocalBalance(String name, String direction, BigDecimal balance) {}

  /** 一条待落库的差异。 */
  private record DiffLine(
      String externalSubjectCode,
      String externalSubjectName,
      String localSubjectCode,
      String localSubjectName,
      String currency,
      BigDecimal externalBalance,
      BigDecimal localBalance,
      BigDecimal difference,
      String reason,
      String detail) {}

  private FinanceSoftwareReconcileTaskRow reconcileTaskRow(UUID id) {
    return reconcileTasks(null, null).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "核对任务不存在"));
  }

  private static FinanceSoftwareReconcileTaskRow toReconcileTaskRow(org.jooq.Record r) {
    var status = r.get("status", String.class);
    return new FinanceSoftwareReconcileTaskRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        r.get("account_name", String.class),
        r.get("reconcile_no", String.class),
        r.get("period_from_id", UUID.class),
        r.get("period_to_id", UUID.class),
        r.get("currency", String.class),
        status,
        "COMPUTED".equals(status) ? "已计算" : "待计算",
        r.get("local_subject_count", Long.class),
        r.get("external_subject_count", Long.class),
        r.get("matched_count", Long.class),
        r.get("difference_count", Long.class),
        r.get("total_difference", BigDecimal.class),
        r.get("computed_at", OffsetDateTime.class),
        r.get("remark", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 直连推送（未接入）

  /**
   * 直连推送凭证到外部财务软件。
   *
   * <p>🔴 **本仓当前恒 409，且一次数据都不写**：外部财务软件适配器未接入。
   */
  public FinanceSoftwarePushOutcome push(
      FinanceSoftwarePushInput input, AccessService.Access actor, String bearer) {
    var account = requireAccount(input.accountId());
    check(input.dateFrom() != null, "起始日期不能为空");
    check(input.dateTo() != null, "截止日期不能为空");
    check(!input.dateFrom().isAfter(input.dateTo()), "起始日期不能晚于截止日期");

    var outcome =
        push.push(
            new FinanceSoftwarePushPort.PushRequest(
                account.id(),
                account.vendor(),
                account.bookCode(),
                account.currency(),
                List.of(),
                input.dateFrom(),
                input.dateTo()),
            bearer);
    if (!outcome.connected() || !outcome.pushed()) {
      // 🔴 **这里不落任何数据**：一条假的成功回执会让「凭证已经推给金蝶了」在数据上
      // 看起来发生过，而现场一个字都没推出去。
      conflict(outcome.reason());
    }
    return new FinanceSoftwarePushOutcome(
        outcome.connected(),
        outcome.pushed(),
        account.id().toString(),
        account.vendor(),
        outcome.externalReference(),
        outcome.reason(),
        outcome.ownerModule());
  }

  // ================================================================ 上游（跨服务只读）

  /** 会计期间清单（来自 12.5.2.1 凭证管理）—— 余额核对要按期间区间取本地余额。 */
  public List<FinanceSoftwarePeriodOption> periods(String bearer) {
    var options = finance.options(bearer);
    if (options.periods() == null) return List.of();
    return options.periods().stream()
        .map(
            p ->
                new FinanceSoftwarePeriodOption(
                    p.id(),
                    p.name() == null ? "" : p.name(),
                    "",
                    "",
                    "",
                    ""))
        .toList();
  }

  /** 可选币种（来自 12.5.2.1 的真实取值，不是写死的词表）。 */
  public List<String> currencies(String bearer) {
    var options = finance.options(bearer);
    return options.currencies() == null ? List.of() : options.currencies();
  }

  // ================================================================ 夹具

  private FinanceSoftwareAccountRow requireAccount(UUID accountId) {
    check(accountId != null, "外部财务软件连接不能为空");
    var row = sql.fetchOne("select id from platform_finance_account where id = ?", accountId);
    check(row != null, "外部财务软件连接不存在");
    return accountRow(accountId);
  }

  private FinanceSoftwareReconcileTaskRow requireReconcileTask(UUID taskId) {
    check(taskId != null, "核对任务不能为空");
    var exists =
        sql.fetchOne("select id from platform_finance_reconcile_task where id = ?", taskId);
    check(exists != null, "核对任务不存在");
    return reconcileTaskRow(taskId);
  }
}
