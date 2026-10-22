package com.starcon.channel;

import static com.starcon.channel.TaxRegistrationModels.*;
import static com.starcon.channel.TaxRegistrationRules.*;

import com.starcon.channel.TaxRegistrationRules.Gap;
import com.starcon.channel.TaxRegistrationRules.JurisdictionFact;
import com.starcon.channel.TaxRegistrationRules.RegistrationFact;
import com.starcon.channel.TaxRegistrationRules.RequirementFact;
import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.3.1.1 税号与辖区。
 *
 * <p>蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * <b>未注册辖区的销售额是合规炸弹</b>：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>店铺清单来自本服务自己的店铺目录</b>（{@code shop} / {@code platform} /
 *       {@code shop_marketplace} / {@code marketplace}），<b>不写死店铺常量、不造平行店铺表</b>。</li>
 *   <li><b>辖区值域是受控的</b>：辖区码必须落在 {@code tax_registration_jurisdiction} 码表里
 *       （DB 触发器钉死），本服务**不解释自由文本**。</li>
 *   <li><b>缺失项是算出来的、不是写死的</b>：要求来自「店铺启用的站点 ⇒ 国家 ⇒ 辖区」，
 *       结论来自「这家店 / 它的法人在那个辖区到底有没有在册登记」。
 *       推导链上每一段都是本仓真实存在的上游。</li>
 *   <li><b>推导不出要求时不等于「都齐了」</b>：美国的登记在州一级，本服务**推不出**
 *       该注册哪些州 ⇒ 不产出美国的自动缺口，这条边界写进能力声明，
 *       <b>不拿空集合冒充「没问题」</b>。</li>
 *   <li><b>法人主体只存逻辑 ID</b>：法人档案归 identity（17），本服务经<b>授权只读端口</b>
 *       现读编码与名称，<b>不复制任何一列</b>；读不到就报「读不到」。</li>
 *   <li><b>本模块不含任何金额</b>：税额与申报底稿归 12.6 财务（该服务在本仓**尚未落地**），
 *       所以本模块**不建财务表、不做浮点记账</b>。</li>
 * </ol>
 */
@Service
public class TaxRegistrationService {

  /** 缺失项的判定口径（能力声明与缺口清单共用同一句话）。 */
  private static final String GAP_SEMANTICS =
      "🔴 缺失项是**算出来的**，不是写死的文案。推导链：店铺启用的站点 → 站点国家 → 辖区 → "
          + "这家店 / 它的法人在那个辖区到底有没有在册登记。"
          + "四类核心缺口：**未注册该辖区**（没在册 —— 在这儿卖了货却查不到登记号，稽查最直接的抓手）、"
          + "**缺 VAT 号**（登记状态写着「已注册」但号是空的 —— 号没填就等于没登记）、"
          + "**缺 EPR 注册号**（该辖区要求 EPR 时才算）、**登记已过有效期**（过期登记和没登记一样经不起稽查）。"
          + "另有两类辅助缺口：即将到期、没记有效期（判不了有没有过期 —— 没记**不等于**没过期）。"
          + "「未注册」与「缺号」**不重复报**：没注册时号为空是正常的。";

  /** 主体语义。 */
  private static final String SUBJECT_SEMANTICS =
      "🔴 登记的主体是**店铺或法人**：店铺用本服务 channel 自家库里的 shop.id；"
          + "法人用 identity 拥有的**逻辑 ID**（本库**不建跨库外键、也不复制 identity 的任何一列**）。"
          + "一家店在某个辖区**算不算有登记**，看两处：这家店自己的登记，"
          + "以及**它的法人**在该辖区的登记（法人注册了 VAT，店就在覆盖范围内）。"
          + "两处都有时优先取**登记在册**的那一条。";

  /** 辖区语义。 */
  private static final String JURISDICTION_SEMANTICS =
      "🔴 辖区是**受控值域**（tax_registration_jurisdiction 码表，DB 触发器钉死，不留自由文本）："
          + "欧盟 27 个成员国 + 英国 + 美国（联邦与 50 州 + DC）+ 站点目录里出现过且要求 VAT 的其他国家。"
          + "每个辖区带三件事：**税制**（VAT / GST / 销售税 / 消费税 / 无）、"
          + "**是否要求登记号**（VAT 税制的才要求）、**是否要求 EPR 注册号**（欧盟成员国与英国）。"
          + "另有一列 derivation_scope 说明「店铺启用的站点国家」**能不能**推导出该辖区的登记要求："
          + "COUNTRY（国家本身就是税务辖区，能推导）/ SUBNATIONAL（登记在州一级，推不出）/ NONE（不跟踪）。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 2 店铺（**本服务自己的 `shop` / `shop_marketplace` / `marketplace`** —— "
          + "「应当登记哪些辖区」就是从站点目录推出来的，**直接读它，不造平行店铺表**）"
          + "+ 17 法人（identity 的 `legal_entity`，**跨服务只读**，只拿编码与名称，不复制任何一列）。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 16.3.1.2 申报日历（**未交付** ⇒ 本模块只**存**申报周期，不做日历与提醒）、"
          + "16.3.2.1 VAT/IOSS（**未交付** ⇒ 不编造 IOSS 订单清单与代收代缴统计）、"
          + "16.3.2.2 EPR/包装法（**未交付** ⇒ 只存 EPR 注册号，不做包装法费用与刊登限制）、"
          + "12.6 税务（**finance 服务在本仓尚未落地** ⇒ 本模块**不建任何财务表**、不做税额计算）。";

  /** 申报范围的边界。 */
  private static final String REPORTING_SCOPE_NOTE =
      "🔴 本模块管的是**「有没有在册登记、登记齐不齐」**，不管税额、不做申报、不算成本。"
          + "三条边界如实写明：① **美国各州销售税**：本仓**没有销售事实表**，"
          + "推不出「该在哪个州注册」⇒ 美国各州只登记、不自动派生要求；"
          + "② **申报日历与提醒**归 16.3.1.2，本模块只存申报周期；"
          + "③ **EPR 未注册就限制刊登**归 16.3.2.2 与 4 刊登，本模块**不编造**这个联动。";

  private final DSLContext sql;
  private final LegalEntitySourcePort legalEntities;

  public TaxRegistrationService(DSLContext sql, LegalEntitySourcePort legalEntities) {
    this.sql = sql;
    this.legalEntities = legalEntities;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻 / 创建时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String offsetText(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  /** 🔴 非法 UUID 按「筛不出东西」处理而不是报错（筛选参数是用户敲进来的）。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  /**
   * 🔴 解析带偏移量的 ISO 时间串。
   *
   * <p>时间列是 {@code timestamptz}，值用 {@code OffsetDateTime} 绑定并在 values 位置写
   * {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)} 会静默偏 −28800s。
   */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法时间（应为带偏移量的 ISO-8601，例如 2026-12-31T23:59:59+08:00）");
    }
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 店铺目录里的一行（含绑定法人与启用的站点）。 */
  private record ShopRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      UUID legalEntityId,
      List<String> marketplaceCodes,
      List<String> requiredJurisdictionCodes) {}

  /** 登记档案的一行（含辖区事实）。 */
  private record RegistrationRow(
      UUID id,
      String subjectType,
      UUID subjectId,
      String jurisdictionCode,
      String taxNumber,
      String vatStatus,
      boolean registered,
      String vatNumber,
      String eprRegistrationNo,
      String filingCycle,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      String status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {

    RegistrationFact toFact() {
      return new RegistrationFact(
          id.toString(),
          subjectType,
          subjectId.toString(),
          jurisdictionCode,
          registered,
          vatStatus,
          vatNumber,
          eprRegistrationNo,
          validFrom,
          validUntil,
          "ARCHIVED".equals(status));
    }
  }

  /** 一条待展示的缺失项（已补齐主体与辖区信息）。 */
  private record GapRow(
      Gap gap, String subjectCode, String subjectName, String shopId, String shopCode, String shopName,
      String jurisdictionName) {}

  // ==================================================================
  // 辖区码表
  // ==================================================================

  /** 全部辖区（码表是全局的，不带租户）。 */
  private List<TaxJurisdictionView> loadJurisdictions() {
    List<TaxJurisdictionView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select code, name, region, country_code, subdivision_code, tax_regime,
                   vat_required, requires_epr, default_filing_cycle, derivation_scope
            from tax_registration_jurisdiction
            order by position, code
            """)) {
      items.add(
          new TaxJurisdictionView(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("region", String.class),
              row.get("country_code", String.class),
              row.get("subdivision_code", String.class),
              row.get("tax_regime", String.class),
              Boolean.TRUE.equals(row.get("vat_required", Boolean.class)),
              Boolean.TRUE.equals(row.get("requires_epr", Boolean.class)),
              row.get("default_filing_cycle", String.class),
              row.get("derivation_scope", String.class)));
    }
    return items;
  }

  /** 辖区码 → 业务事实（判定用）。 */
  private Map<String, JurisdictionFact> jurisdictionFacts(List<TaxJurisdictionView> jurisdictions) {
    Map<String, JurisdictionFact> facts = new LinkedHashMap<>();
    for (TaxJurisdictionView jurisdiction : jurisdictions) {
      facts.put(
          jurisdiction.code(),
          new JurisdictionFact(
              jurisdiction.code(),
              jurisdiction.name(),
              jurisdiction.vatRequired(),
              jurisdiction.requiresEpr()));
    }
    return facts;
  }

  // ==================================================================
  // 店铺目录 + 「应当登记哪些辖区」
  // ==================================================================

  /**
   * 🔴 店铺清单**直接读本服务自己的店铺目录**（{@code shop} / {@code platform} /
   * {@code shop_marketplace} / {@code marketplace}），不写死店铺常量、不造平行店铺表。
   *
   * <p>「应当登记哪些辖区」的推导：店铺启用的、且 {@code marketplace.vat_required=true} 的站点 ⇒
   * 站点国家 ⇒ 该国家里 {@code derivation_scope='COUNTRY'} 的辖区。
   */
  private List<ShopRow> loadShops(UUID tenant, String keyword, String platformCode) {
    Map<UUID, List<String>> marketplaces = new LinkedHashMap<>();
    Map<UUID, List<String>> required = new LinkedHashMap<>();
    Map<String, String> countryJurisdiction = new LinkedHashMap<>();
    for (TaxJurisdictionView jurisdiction : loadJurisdictions()) {
      if (!"COUNTRY".equals(jurisdiction.derivationScope())) {
        continue;
      }
      if (!jurisdiction.subdivisionCode().isEmpty()) {
        continue;
      }
      countryJurisdiction.put(jurisdiction.countryCode().toUpperCase(Locale.ROOT), jurisdiction.code());
    }

    for (Record row :
        sql.fetch(
            """
            select sm.shop_id, m.code as marketplace_code, trim(m.country_code) as country_code
            from shop_marketplace sm
            join marketplace m on m.id = sm.marketplace_id
            where sm.tenant_id = ? and sm.status = 'ACTIVE'
            order by sm.shop_id, m.code
            """,
            tenant)) {
      UUID shopId = row.get("shop_id", UUID.class);
      String marketplaceCode = row.get("marketplace_code", String.class);
      marketplaces.computeIfAbsent(shopId, key -> new ArrayList<>()).add(marketplaceCode);
      String country = row.get("country_code", String.class);
      if (country == null) {
        continue;
      }
      String code = countryJurisdiction.get(country.trim().toUpperCase(Locale.ROOT));
      if (code != null) {
        List<String> codes = required.computeIfAbsent(shopId, key -> new ArrayList<>());
        if (!codes.contains(code)) {
          codes.add(code);
        }
      }
    }

    StringBuilder statement =
        new StringBuilder(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name, s.legal_entity_id
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(platformCode).isEmpty()) {
      statement.append(" and p.code = ?");
      params.add(text(platformCode).toUpperCase(Locale.ROOT));
    }
    if (!text(keyword).isEmpty()) {
      statement.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    statement.append(" order by s.code");

    List<ShopRow> shops = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      UUID id = row.get("id", UUID.class);
      shops.add(
          new ShopRow(
              id,
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              row.get("legal_entity_id", UUID.class),
              List.copyOf(marketplaces.getOrDefault(id, List.of())),
              List.copyOf(required.getOrDefault(id, List.of()))));
    }
    return shops;
  }

  // ==================================================================
  // 登记档案
  // ==================================================================

  private List<RegistrationRow> loadRegistrations(UUID tenant) {
    List<RegistrationRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select id, subject_type, subject_id, jurisdiction_code, tax_number, vat_status,
                   is_registered, vat_number, epr_registration_no, filing_cycle,
                   registration_valid_from, registration_valid_until, status, note,
                   created_at, updated_at
            from tax_registration_record
            where tenant_id = ?
            order by subject_type, subject_id, jurisdiction_code
            """,
            tenant)) {
      items.add(
          new RegistrationRow(
              row.get("id", UUID.class),
              row.get("subject_type", String.class),
              row.get("subject_id", UUID.class),
              row.get("jurisdiction_code", String.class),
              row.get("tax_number", String.class),
              row.get("vat_status", String.class),
              Boolean.TRUE.equals(row.get("is_registered", Boolean.class)),
              row.get("vat_number", String.class),
              row.get("epr_registration_no", String.class),
              row.get("filing_cycle", String.class),
              row.get("registration_valid_from", OffsetDateTime.class),
              row.get("registration_valid_until", OffsetDateTime.class),
              row.get("status", String.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return items;
  }

  /** 主体解析结果：编码 / 名称 / 读到了吗。 */
  private record SubjectRef(String code, String name, boolean available, String note) {}

  private SubjectRef resolveSubject(
      String subjectType,
      UUID subjectId,
      Map<UUID, ShopRow> shops,
      LegalEntitySourcePort.LegalEntitySet entities) {
    if (SUBJECT_SHOP.equals(subjectType)) {
      ShopRow shop = shops.get(subjectId);
      if (shop == null) {
        return new SubjectRef("", "", false, "店铺档案在本服务里读不到（超出数据范围或已被删除）");
      }
      return new SubjectRef(shop.code(), shop.name(), true, "");
    }
    if (!entities.available()) {
      return new SubjectRef("", "", false, "法人档案读不到：" + entities.note());
    }
    for (LegalEntitySourcePort.LegalEntityRef entity : entities.items()) {
      if (entity.id().equals(subjectId)) {
        return new SubjectRef(entity.code(), entity.legalName(), true, "");
      }
    }
    return new SubjectRef("", "", false, "法人档案里没有这个法人（可能已不属于本租户）");
  }

  private TaxRegistrationView view(
      RegistrationRow row,
      Map<String, JurisdictionFact> facts,
      Map<String, String> regimes,
      Map<UUID, ShopRow> shops,
      LegalEntitySourcePort.LegalEntitySet entities,
      OffsetDateTime now) {
    JurisdictionFact jurisdiction = facts.get(row.jurisdictionCode());
    SubjectRef subject = resolveSubject(row.subjectType(), row.subjectId(), shops, entities);
    return new TaxRegistrationView(
        row.id().toString(),
        row.subjectType(),
        row.subjectId().toString(),
        subject.code(),
        subject.name(),
        subject.available(),
        subject.note(),
        row.jurisdictionCode(),
        jurisdiction == null ? "" : jurisdiction.name(),
        regimes.getOrDefault(row.jurisdictionCode(), ""),
        row.taxNumber(),
        row.vatStatus(),
        vatStatusLabel(row.vatStatus()),
        row.registered(),
        row.vatNumber(),
        row.eprRegistrationNo(),
        row.filingCycle(),
        offsetText(row.validFrom()),
        offsetText(row.validUntil()),
        row.status(),
        effectiveStatus(row, now),
        daysToExpiry(row.toFact(), now),
        row.note(),
        offsetText(row.createdAt()),
        offsetText(row.updatedAt()));
  }

  /** 该辖区的税制（码表列）。 */
  private Map<String, String> taxRegimes(List<TaxJurisdictionView> jurisdictions) {
    Map<String, String> regimes = new LinkedHashMap<>();
    for (TaxJurisdictionView jurisdiction : jurisdictions) {
      regimes.put(jurisdiction.code(), jurisdiction.taxRegime());
    }
    return regimes;
  }

  private static String effectiveStatus(RegistrationRow row, OffsetDateTime now) {
    if ("ARCHIVED".equals(row.status())) {
      return "ARCHIVED";
    }
    if (row.validUntil() != null && row.validUntil().isBefore(now)) {
      return "EXPIRED";
    }
    return "ACTIVE";
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些下游未交付、量不到归谁、缺失项的判定口径是什么。
   *
   * <p>🔴 四个下游当前<b>都未交付</b>，如实报出 ownerModule 与影响：
   * 16.3.1.2 申报日历、16.3.2.1 VAT/IOSS、16.3.2.2 EPR/包装法、12.6 税务。
   */
  public TaxRegistrationCapabilities capabilities(String bearer) {
    List<TaxRegistrationCapabilityGap> gaps =
        List.of(
            new TaxRegistrationCapabilityGap(
                "16.3.1.2",
                "申报日历",
                "16.3.1.2",
                false,
                "申报日历未交付 ⇒ 本模块**只存**申报周期，不做申报日历、不做截止日提醒、"
                    + "不做「未开始 / 数据就绪 / 已提交 / 已确认」的状态跟踪。"),
            new TaxRegistrationCapabilityGap(
                "16.3.2.1",
                "VAT/IOSS",
                "16.3.2.1",
                false,
                "VAT/IOSS 未交付 ⇒ 本模块**不编造** IOSS 订单清单、代收代缴统计与申报状态。"
                    + "本模块只回答「有没有在册登记、登记齐不齐」。"),
            new TaxRegistrationCapabilityGap(
                "16.3.2.2",
                "EPR/包装法",
                "16.3.2.2",
                false,
                "EPR/包装法未交付 ⇒ 本模块**只存** EPR 注册号，不做包装法费用记录、"
                    + "不做「EPR 未注册就限制刊登」的联动（那要 4 刊登与 12 财务）。"),
            new TaxRegistrationCapabilityGap(
                "12.6",
                "税务",
                "12.6",
                false,
                "12 财务服务在本仓**尚未落地** ⇒ 本模块**不建任何财务表**、不做税额计算、"
                    + "不做申报底稿。本模块**不含任何金额**，也就没有浮点记账的风险。"));

    LegalEntitySourcePort.LegalEntitySet entities = legalEntities.legalEntities(bearer);
    List<TaxRegistrationUpstream> upstreams =
        List.of(
            new TaxRegistrationUpstream(
                "2",
                "店铺目录（channel 自家库）",
                true,
                "2",
                "店铺与站点目录**直接读本服务自己的 `shop` / `shop_marketplace` / `marketplace`** —— "
                    + "「应当登记哪些辖区」就是从站点目录推出来的，不写死店铺常量、不造平行店铺表。"),
            new TaxRegistrationUpstream(
                "17.1.2.1",
                "法人档案（identity）",
                entities.available(),
                "17.1.2.1",
                entities.available()
                    ? "法人档案经**授权只读端口**读取，只拿编码与名称，**不复制任何一列**到本库；"
                        + "法人主体在本库只存逻辑 ID。读不到时显示「读不到」，**不显示成「没绑定法人」**。"
                    : "法人档案**读不到**：" + entities.note()
                        + " ⇒ 法人主体的编码与名称显示为「读不到」，**不会**被显示成空白或「未注册」。"));

    return new TaxRegistrationCapabilities(
        gaps,
        upstreams,
        GAP_TYPES,
        GAP_SEVERITIES,
        VAT_STATUSES,
        FILING_CYCLES,
        SUBJECT_TYPES,
        GAP_SEMANTICS,
        SUBJECT_SEMANTICS,
        JURISDICTION_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE,
        REPORTING_SCOPE_NOTE);
  }

  // ==================================================================
  // 辖区码表端点
  // ==================================================================

  public TaxJurisdictionList jurisdictions() {
    List<TaxJurisdictionView> items = loadJurisdictions();
    return new TaxJurisdictionList(items, items.size(), JURISDICTION_SEMANTICS);
  }

  // ==================================================================
  // 店铺目录端点
  // ==================================================================

  public TaxShopList shops(String keyword, String platformCode) {
    UUID tenant = currentTenant();
    List<ShopRow> shops = loadShops(tenant, keyword, platformCode);
    List<TaxShopView> items = new ArrayList<>();
    for (ShopRow shop : shops) {
      items.add(
          new TaxShopView(
              shop.id().toString(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              shop.platformName(),
              shop.legalEntityId() == null ? "" : shop.legalEntityId().toString(),
              shop.marketplaceCodes(),
              shop.requiredJurisdictionCodes()));
    }
    return new TaxShopList(
        items,
        items.size(),
        "🔴 店铺清单**直接读本服务自己的店铺目录**，不写死店铺常量、不造平行店铺表。"
            + "「应当登记的辖区」由**启用的站点**推导：站点国家在码表里且属于国家级税务辖区（COUNTRY）时才推导得出来。");
  }

  // ==================================================================
  // 登记档案端点
  // ==================================================================

  public TaxRegistrationPage registrations(
      String subjectType,
      String subjectId,
      String jurisdictionCode,
      String vatStatus,
      String registered,
      String status,
      int page,
      int size,
      String bearer) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String type = text(subjectType).toUpperCase(Locale.ROOT);
    if (!type.isEmpty() && !SUBJECT_TYPES.contains(type)) {
      throw unprocessable("主体类型只能是 " + String.join(" / ", SUBJECT_TYPES));
    }
    String state = text(vatStatus).toUpperCase(Locale.ROOT);
    if (!state.isEmpty() && !VAT_STATUSES.contains(state)) {
      throw unprocessable("VAT 状态只能是 " + String.join(" / ", VAT_STATUSES));
    }
    String lifecycle = text(status).toUpperCase(Locale.ROOT);
    if (!lifecycle.isEmpty() && !List.of("ACTIVE", "ARCHIVED").contains(lifecycle)) {
      throw unprocessable("登记状态只能是 ACTIVE / ARCHIVED");
    }
    UUID subject = parseFilterUuid(subjectId);
    String code = text(jurisdictionCode).toUpperCase(Locale.ROOT);
    Boolean registeredFlag =
        text(registered).isEmpty() ? null : Boolean.valueOf(text(registered).toLowerCase(Locale.ROOT));

    List<TaxJurisdictionView> jurisdictions = loadJurisdictions();
    Map<String, JurisdictionFact> facts = jurisdictionFacts(jurisdictions);
    Map<String, String> regimes = taxRegimes(jurisdictions);
    Map<UUID, ShopRow> shops = shopIndex(tenant);
    LegalEntitySourcePort.LegalEntitySet entities = legalEntities.legalEntities(bearer);
    OffsetDateTime now = dbNow();

    List<RegistrationRow> filtered = new ArrayList<>();
    for (RegistrationRow row : loadRegistrations(tenant)) {
      if (!type.isEmpty() && !type.equals(row.subjectType())) {
        continue;
      }
      if (subject != null && !subject.equals(row.subjectId())) {
        continue;
      }
      if (!code.isEmpty() && !code.equals(row.jurisdictionCode())) {
        continue;
      }
      if (!state.isEmpty() && !state.equals(row.vatStatus())) {
        continue;
      }
      if (registeredFlag != null && registeredFlag != row.registered()) {
        continue;
      }
      if (!lifecycle.isEmpty() && !lifecycle.equals(row.status())) {
        continue;
      }
      filtered.add(row);
    }

    int offset = Math.max(0, page) * limit;
    List<TaxRegistrationView> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      items.add(view(filtered.get(index), facts, regimes, shops, entities, now));
    }
    return new TaxRegistrationPage(items, filtered.size(), Math.max(0, page), limit);
  }

  public TaxRegistrationView registration(String id, String bearer) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    List<TaxJurisdictionView> jurisdictions = loadJurisdictions();
    for (RegistrationRow row : loadRegistrations(tenant)) {
      if (row.id().equals(target)) {
        return view(
            row,
            jurisdictionFacts(jurisdictions),
            taxRegimes(jurisdictions),
            shopIndex(tenant),
            legalEntities.legalEntities(bearer),
            dbNow());
      }
    }
    throw notFound();
  }

  /**
   * 新建一条税务登记。
   *
   * <p>🔴 「一个主体在一个辖区只有一条登记」由 DB 唯一约束兜底；重复时返回 409 而不是插第二条 ——
   * 重录是**改这一行**（订正笔误），不是另起一条。
   */
  public TaxRegistrationView create(TaxRegistrationInput input, String bearer) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String type = text(input.subjectType()).toUpperCase(Locale.ROOT);
    if (!SUBJECT_TYPES.contains(type)) {
      throw unprocessable("主体类型只能是 " + String.join(" / ", SUBJECT_TYPES));
    }
    UUID subject = parseUuid(input.subjectId());
    if (subject == null) {
      throw unprocessable("主体 ID 不合法");
    }
    String jurisdiction = text(input.jurisdictionCode()).toUpperCase(Locale.ROOT);
    if (jurisdiction.isEmpty()) {
      throw unprocessable("必须选择辖区");
    }
    String vatStatus = text(input.vatStatus()).toUpperCase(Locale.ROOT);
    if (vatStatus.isEmpty()) {
      vatStatus = VAT_UNKNOWN;
    }
    if (!VAT_STATUSES.contains(vatStatus)) {
      throw unprocessable("VAT 状态只能是 " + String.join(" / ", VAT_STATUSES));
    }
    boolean registered = Boolean.TRUE.equals(input.isRegistered());
    if (registered && !List.of(VAT_REGISTERED, VAT_PENDING, VAT_EXEMPT).contains(vatStatus)) {
      throw unprocessable("已注册时 VAT 状态只能是 REGISTERED / PENDING / EXEMPT");
    }
    String cycle = text(input.filingCycle()).toUpperCase(Locale.ROOT);
    if (!cycle.isEmpty() && !FILING_CYCLES.contains(cycle)) {
      throw unprocessable("申报周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    OffsetDateTime validFrom = parseTime(input.registrationValidFrom(), "注册有效期起");
    OffsetDateTime validUntil = parseTime(input.registrationValidUntil(), "注册有效期止");
    if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
      throw unprocessable("注册有效期止不能早于起");
    }
    String lifecycle = text(input.status()).toUpperCase(Locale.ROOT);
    if (lifecycle.isEmpty()) {
      lifecycle = "ACTIVE";
    }
    if (!List.of("ACTIVE", "ARCHIVED").contains(lifecycle)) {
      throw unprocessable("登记状态只能是 ACTIVE / ARCHIVED");
    }

    // 辖区码必须真实存在（DB 触发器也会钉一道，这里先给一句人话而不是裸 SQL 错误）。
    Object known =
        sql.fetchValue("select 1 from tax_registration_jurisdiction where code = ?", jurisdiction);
    if (known == null) {
      throw unprocessable("辖区 " + jurisdiction + " 不在受控值域里（请先在辖区码表里选）");
    }
    if (SUBJECT_SHOP.equals(type)) {
      Object shop =
          sql.fetchValue(
              "select 1 from shop where tenant_id = ? and id = ?", tenant, subject);
      if (shop == null) {
        throw unprocessable("店铺不存在或不在当前数据范围内");
      }
    }

    UUID id = Ids.next();
    OffsetDateTime now = dbNow();
    try {
      sql.execute(
          """
          insert into tax_registration_record
            (id, tenant_id, subject_type, subject_id, jurisdiction_code, tax_number, vat_status,
             is_registered, vat_number, epr_registration_no, filing_cycle,
             registration_valid_from, registration_valid_until, status, note, recorded_by,
             created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, ?, now(), now())
          """,
          id,
          tenant,
          type,
          subject,
          jurisdiction,
          text(input.taxNumber()),
          vatStatus,
          registered,
          text(input.vatNumber()),
          text(input.eprRegistrationNo()),
          cycle,
          validFrom,
          validUntil,
          lifecycle,
          text(input.note()),
          currentActor());
    } catch (org.jooq.exception.DataAccessException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.contains("duplicate key value violates unique constraint")
          && message.contains("tax_registration_record")) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "这个主体在这个辖区已经有一条登记了 —— 改那一条，不要再插一条");
      }
      throw e;
    }
    return registration(id.toString(), bearer);
  }

  /** 修改一条登记（含撤下：status=ARCHIVED）。登记是事实，**不做物理删除**。 */
  public TaxRegistrationView update(String id, TaxRegistrationInput input, String bearer) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    RegistrationRow existing = null;
    for (RegistrationRow row : loadRegistrations(tenant)) {
      if (row.id().equals(target)) {
        existing = row;
        break;
      }
    }
    if (existing == null) {
      throw notFound();
    }
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String vatStatus = text(input.vatStatus()).toUpperCase(Locale.ROOT);
    if (vatStatus.isEmpty()) {
      vatStatus = existing.vatStatus();
    }
    if (!VAT_STATUSES.contains(vatStatus)) {
      throw unprocessable("VAT 状态只能是 " + String.join(" / ", VAT_STATUSES));
    }
    boolean registered =
        input.isRegistered() == null ? existing.registered() : Boolean.TRUE.equals(input.isRegistered());
    if (registered && !List.of(VAT_REGISTERED, VAT_PENDING, VAT_EXEMPT).contains(vatStatus)) {
      throw unprocessable("已注册时 VAT 状态只能是 REGISTERED / PENDING / EXEMPT");
    }
    String cycle = text(input.filingCycle()).toUpperCase(Locale.ROOT);
    if (cycle.isEmpty()) {
      cycle = existing.filingCycle();
    }
    if (!cycle.isEmpty() && !FILING_CYCLES.contains(cycle)) {
      throw unprocessable("申报周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    OffsetDateTime validFrom =
        input.registrationValidFrom() == null || input.registrationValidFrom().isBlank()
            ? existing.validFrom()
            : parseTime(input.registrationValidFrom(), "注册有效期起");
    OffsetDateTime validUntil =
        input.registrationValidUntil() == null || input.registrationValidUntil().isBlank()
            ? existing.validUntil()
            : parseTime(input.registrationValidUntil(), "注册有效期止");
    if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
      throw unprocessable("注册有效期止不能早于起");
    }
    String lifecycle = text(input.status()).toUpperCase(Locale.ROOT);
    if (lifecycle.isEmpty()) {
      lifecycle = existing.status();
    }
    if (!List.of("ACTIVE", "ARCHIVED").contains(lifecycle)) {
      throw unprocessable("登记状态只能是 ACTIVE / ARCHIVED");
    }
    String jurisdiction =
        text(input.jurisdictionCode()).isEmpty()
            ? existing.jurisdictionCode()
            : text(input.jurisdictionCode()).toUpperCase(Locale.ROOT);
    Object known =
        sql.fetchValue("select 1 from tax_registration_jurisdiction where code = ?", jurisdiction);
    if (known == null) {
      throw unprocessable("辖区 " + jurisdiction + " 不在受控值域里（请先在辖区码表里选）");
    }

    sql.execute(
        """
        update tax_registration_record
           set jurisdiction_code = ?, tax_number = ?, vat_status = ?, is_registered = ?,
               vat_number = ?, epr_registration_no = ?, filing_cycle = ?,
               registration_valid_from = ?::timestamptz, registration_valid_until = ?::timestamptz,
               status = ?, note = ?, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        jurisdiction,
        text(input.taxNumber()),
        vatStatus,
        registered,
        text(input.vatNumber()),
        text(input.eprRegistrationNo()),
        cycle,
        validFrom,
        validUntil,
        lifecycle,
        text(input.note()),
        tenant,
        target);
    return registration(target.toString(), bearer);
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    if (raw == null || String.valueOf(raw).isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Map<UUID, ShopRow> shopIndex(UUID tenant) {
    Map<UUID, ShopRow> index = new LinkedHashMap<>();
    for (ShopRow shop : loadShops(tenant, null, null)) {
      index.put(shop.id(), shop);
    }
    return index;
  }

  // ==================================================================
  // 缺失项
  // ==================================================================

  /**
   * 🔴 <b>算出全部缺失项</b>。
   *
   * <p>两路来源：
   *
   * <ol>
   *   <li><b>外部要求未满足</b>：店铺启用的站点 ⇒ 应当登记的辖区 ⇒ 那家店 / 它的法人
   *       在那个辖区到底有没有在册登记。</li>
   *   <li><b>登记自身的缺失</b>：每一条登记自己记的号有没有填、EPR 号有没有填、
   *       有效期判得了判不了（法人主体也走这一路 —— 它没有外部要求，但缺号照样要报）。</li>
   * </ol>
   *
   * <p>同一件事（主体 × 辖区 × 缺失项类型）<b>只报一次</b>：例如「已过有效期」既可能从
   * 外部要求那侧看到、也可能从登记自身那侧看到，去重后运营看到的条数是真的。
   */
  private List<GapRow> computeGaps(UUID tenant, LegalEntitySourcePort.LegalEntitySet entities) {
    List<TaxJurisdictionView> jurisdictions = loadJurisdictions();
    Map<String, JurisdictionFact> facts = jurisdictionFacts(jurisdictions);
    List<ShopRow> shops = loadShops(tenant, null, null);
    Map<UUID, ShopRow> shopIndex = new LinkedHashMap<>();
    for (ShopRow shop : shops) {
      shopIndex.put(shop.id(), shop);
    }
    List<RegistrationRow> registrations = loadRegistrations(tenant);
    OffsetDateTime now = dbNow();

    Map<String, RegistrationRow> bySubject =
        registrations.stream()
            .filter(row -> !"ARCHIVED".equals(row.status()))
            .collect(
                java.util.stream.Collectors.toMap(
                    row -> row.subjectType() + "|" + row.subjectId() + "|" + row.jurisdictionCode(),
                    row -> row,
                    (left, right) -> left,
                    LinkedHashMap::new));

    Map<String, GapRow> unique = new LinkedHashMap<>();

    // ---- 第一路：外部要求未满足 ----
    for (ShopRow shop : shops) {
      for (String jurisdictionCode : shop.requiredJurisdictionCodes()) {
        RegistrationRow own =
            bySubject.get(SUBJECT_SHOP + "|" + shop.id() + "|" + jurisdictionCode);
        RegistrationRow viaEntity =
            shop.legalEntityId() == null
                ? null
                : bySubject.get(SUBJECT_LEGAL_ENTITY + "|" + shop.legalEntityId() + "|" + jurisdictionCode);
        RegistrationRow picked = pick(own, viaEntity, now);
        List<Gap> gaps =
            evaluateRequirement(
                new RequirementFact(SUBJECT_SHOP, shop.id().toString(), jurisdictionCode),
                picked == null ? null : picked.toFact(),
                now);
        for (Gap gap : gaps) {
          unique.putIfAbsent(
              key(gap),
              new GapRow(
                  gap,
                  shop.code(),
                  shop.name(),
                  shop.id().toString(),
                  shop.code(),
                  shop.name(),
                  facts.containsKey(jurisdictionCode) ? facts.get(jurisdictionCode).name() : ""));
        }
      }
    }

    // ---- 第二路：登记自身的缺失 ----
    for (RegistrationRow row : registrations) {
      JurisdictionFact jurisdiction = facts.get(row.jurisdictionCode());
      if (jurisdiction == null) {
        continue;
      }
      List<Gap> gaps = evaluateRegistration(row.toFact(), jurisdiction, now);
      if (gaps.isEmpty()) {
        continue;
      }
      SubjectRef subject = resolveSubject(row.subjectType(), row.subjectId(), shopIndex, entities);
      ShopRow shop = SUBJECT_SHOP.equals(row.subjectType()) ? shopIndex.get(row.subjectId()) : null;
      for (Gap gap : gaps) {
        unique.putIfAbsent(
            key(gap),
            new GapRow(
                gap,
                subject.code(),
                subject.name(),
                shop == null ? "" : shop.id().toString(),
                shop == null ? "" : shop.code(),
                shop == null ? "" : shop.name(),
                jurisdiction.name()));
      }
    }
    List<GapRow> rows = new ArrayList<>(unique.values());
    // 🔴 排序稳定：高严重度在前，同级按辖区 / 主体稳定排序 —— 分页不因并列而抖动。
    rows.sort(gapOrder());
    return rows;
  }

  private static String key(Gap gap) {
    return gap.subjectType() + "|" + gap.subjectId() + "|" + gap.jurisdictionCode() + "|"
        + gap.gapType();
  }

  /**
   * 🔴 挑出「在册」的那一条登记。
   *
   * <p>优先级：① 已注册且未过期 → ② 已注册（哪怕过期）→ ③ 店铺自己的登记 → ④ 法人的登记。
   * 理由：法人注册了 VAT 就覆盖它的店，所以只要**有一条**在册，就算覆盖；
   * 两条都过期时用店铺自己的（更具体）。
   */
  private static RegistrationRow pick(
      RegistrationRow own, RegistrationRow viaEntity, OffsetDateTime now) {
    if (own != null && viaEntity != null) {
      boolean ownCovered = own.registered() && !isExpired(own, now);
      boolean entityCovered = viaEntity.registered() && !isExpired(viaEntity, now);
      if (ownCovered) {
        return own;
      }
      if (entityCovered) {
        return viaEntity;
      }
      if (own.registered()) {
        return own;
      }
      if (viaEntity.registered()) {
        return viaEntity;
      }
      return own;
    }
    if (own != null) {
      return own;
    }
    return viaEntity;
  }

  private static boolean isExpired(RegistrationRow row, OffsetDateTime now) {
    return row.validUntil() != null && row.validUntil().isBefore(now);
  }

  /** 缺失项清单（真分页 + 汇总）。 */
  public TaxGapPage gaps(
      String gapType,
      String severity,
      String subjectType,
      String shopId,
      String jurisdictionCode,
      int page,
      int size,
      String bearer) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String type = text(gapType).toUpperCase(Locale.ROOT);
    if (!type.isEmpty() && !GAP_TYPES.contains(type)) {
      throw unprocessable("缺失项类型只能是 " + String.join(" / ", GAP_TYPES));
    }
    String level = text(severity).toUpperCase(Locale.ROOT);
    if (!level.isEmpty() && !GAP_SEVERITIES.contains(level)) {
      throw unprocessable("严重度只能是 " + String.join(" / ", GAP_SEVERITIES));
    }
    String subject = text(subjectType).toUpperCase(Locale.ROOT);
    if (!subject.isEmpty() && !SUBJECT_TYPES.contains(subject)) {
      throw unprocessable("主体类型只能是 " + String.join(" / ", SUBJECT_TYPES));
    }
    UUID shop = parseFilterUuid(shopId);
    String jurisdiction = text(jurisdictionCode).toUpperCase(Locale.ROOT);

    List<GapRow> all = computeGaps(tenant, legalEntities.legalEntities(bearer));
    List<GapRow> filtered = new ArrayList<>();
    long high = 0;
    long medium = 0;
    Set<String> affectedShops = new LinkedHashSet<>();
    for (GapRow row : all) {
      if (!type.isEmpty() && !type.equals(row.gap().gapType())) {
        continue;
      }
      if (!level.isEmpty() && !level.equals(row.gap().severity())) {
        continue;
      }
      if (!subject.isEmpty() && !subject.equals(row.gap().subjectType())) {
        continue;
      }
      if (shop != null && !shop.toString().equals(row.shopId()) && !shop.toString().equals(row.gap().subjectId())) {
        continue;
      }
      if (!jurisdiction.isEmpty() && !jurisdiction.equals(row.gap().jurisdictionCode())) {
        continue;
      }
      filtered.add(row);
      if (SEVERITY_HIGH.equals(row.gap().severity())) {
        high++;
      } else {
        medium++;
      }
      if (!row.shopId().isEmpty()) {
        affectedShops.add(row.shopId());
      }
    }

    int offset = Math.max(0, page) * limit;
    List<TaxGapItem> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      items.add(toItem(filtered.get(index)));
    }
    return new TaxGapPage(
        items,
        filtered.size(),
        Math.max(0, page),
        limit,
        high,
        medium,
        affectedShops.size(),
        GAP_SEMANTICS);
  }

  private static TaxGapItem toItem(GapRow row) {
    return new TaxGapItem(
        row.gap().gapType(),
        gapTypeLabel(row.gap().gapType()),
        row.gap().severity(),
        row.gap().subjectType(),
        row.gap().subjectId(),
        row.subjectCode(),
        row.subjectName(),
        row.shopId(),
        row.shopCode(),
        row.shopName(),
        row.gap().jurisdictionCode(),
        row.jurisdictionName(),
        row.gap().message(),
        row.gap().remediation());
  }

  /** 缺失项汇总（按类型计数 + 受影响店铺 / 主体数）。 */
  public TaxGapSummary gapSummary(
      String gapType, String severity, String subjectType, String shopId, String jurisdictionCode,
      String bearer) {
    UUID tenant = currentTenant();
    String type = text(gapType).toUpperCase(Locale.ROOT);
    String level = text(severity).toUpperCase(Locale.ROOT);
    String subject = text(subjectType).toUpperCase(Locale.ROOT);
    UUID shop = parseFilterUuid(shopId);
    String jurisdiction = text(jurisdictionCode).toUpperCase(Locale.ROOT);

    List<GapRow> all = computeGaps(tenant, legalEntities.legalEntities(bearer));
    List<GapRow> filtered = new ArrayList<>();
    long high = 0;
    long medium = 0;
    Set<String> affectedShops = new LinkedHashSet<>();
    Set<String> affectedSubjects = new LinkedHashSet<>();
    Map<String, Long> byType = new LinkedHashMap<>();
    for (GapRow row : all) {
      if (!type.isEmpty() && !type.equals(row.gap().gapType())) {
        continue;
      }
      if (!level.isEmpty() && !level.equals(row.gap().severity())) {
        continue;
      }
      if (!subject.isEmpty() && !subject.equals(row.gap().subjectType())) {
        continue;
      }
      if (shop != null && !shop.toString().equals(row.shopId()) && !shop.toString().equals(row.gap().subjectId())) {
        continue;
      }
      if (!jurisdiction.isEmpty() && !jurisdiction.equals(row.gap().jurisdictionCode())) {
        continue;
      }
      filtered.add(row);
      if (SEVERITY_HIGH.equals(row.gap().severity())) {
        high++;
      } else {
        medium++;
      }
      byType.merge(row.gap().gapType(), 1L, Long::sum);
      affectedSubjects.add(row.gap().subjectType() + "|" + row.gap().subjectId());
      if (!row.shopId().isEmpty()) {
        affectedShops.add(row.shopId());
      }
    }

    List<TaxGapTypeCount> typeCounts = new ArrayList<>();
    for (String eachType : GAP_TYPES) {
      long count = byType.getOrDefault(eachType, 0L);
      if (count == 0) {
        continue;
      }
      typeCounts.add(
          new TaxGapTypeCount(eachType, gapTypeLabel(eachType), severityOf(eachType), count));
    }
    return new TaxGapSummary(
        filtered.size(),
        high,
        medium,
        affectedShops.size(),
        affectedSubjects.size(),
        byType,
        typeCounts,
        GAP_SEMANTICS);
  }

  /** 缺失项类型的固有严重度（用于汇总里的分档计数）。 */
  private static String severityOf(String gapType) {
    return switch (gapType) {
      case GAP_JURISDICTION_NOT_REGISTERED,
              GAP_VAT_NUMBER_MISSING,
              GAP_REGISTRATION_EXPIRED -> SEVERITY_HIGH;
      default -> SEVERITY_MEDIUM;
    };
  }

  // ==================================================================
  // 覆盖情况
  // ==================================================================

  /** 逐店铺的辖区覆盖情况。 */
  public TaxCoverageList coverage(String bearer) {
    UUID tenant = currentTenant();
    List<TaxJurisdictionView> jurisdictions = loadJurisdictions();
    Map<String, JurisdictionFact> facts = jurisdictionFacts(jurisdictions);
    List<ShopRow> shops = loadShops(tenant, null, null);
    Map<UUID, ShopRow> shopIndex = new LinkedHashMap<>();
    for (ShopRow shop : shops) {
      shopIndex.put(shop.id(), shop);
    }
    List<RegistrationRow> registrations = loadRegistrations(tenant);
    Map<String, RegistrationRow> bySubject = new LinkedHashMap<>();
    for (RegistrationRow row : registrations) {
      if ("ARCHIVED".equals(row.status())) {
        continue;
      }
      bySubject.putIfAbsent(
          row.subjectType() + "|" + row.subjectId() + "|" + row.jurisdictionCode(), row);
    }
    OffsetDateTime now = dbNow();

    List<GapRow> gaps = computeGaps(tenant, legalEntities.legalEntities(bearer));
    Map<String, Long> gapCountByShop = new LinkedHashMap<>();
    for (GapRow row : gaps) {
      if (!row.shopId().isEmpty()) {
        gapCountByShop.merge(row.shopId(), 1L, Long::sum);
      }
    }

    List<TaxCoverageRow> items = new ArrayList<>();
    for (ShopRow shop : shops) {
      List<TaxCoverageItem> jurisdictionItems = new ArrayList<>();
      int covered = 0;
      for (String jurisdictionCode : shop.requiredJurisdictionCodes()) {
        RegistrationRow own =
            bySubject.get(SUBJECT_SHOP + "|" + shop.id() + "|" + jurisdictionCode);
        RegistrationRow viaEntity =
            shop.legalEntityId() == null
                ? null
                : bySubject.get(SUBJECT_LEGAL_ENTITY + "|" + shop.legalEntityId() + "|" + jurisdictionCode);
        RegistrationRow picked = pick(own, viaEntity, now);
        String state = coverageState(picked == null ? null : picked.toFact(), now);
        boolean isCovered = "REGISTERED".equals(state);
        if (isCovered) {
          covered++;
        }
        JurisdictionFact fact = facts.get(jurisdictionCode);
        jurisdictionItems.add(
            new TaxCoverageItem(
                jurisdictionCode,
                fact == null ? "" : fact.name(),
                picked == null ? "" : picked.id().toString(),
                state,
                isCovered));
      }
      int required = jurisdictionItems.size();
      long gapCount = gapCountByShop.getOrDefault(shop.id().toString(), 0L);
      // 🔴 覆盖状态只答「该登记的辖区登记齐了没有」；
      //   别的缺失（缺 EPR 号、没记有效期）**由 gapCount 单独报**，
      //   不混进「齐了没有」—— 把两个问题揉成一个，运营会不知道该去补哪一样。
      String status;
      if (required == 0) {
        status = "NO_REQUIREMENT";
      } else if (covered == required) {
        status = "COMPLETE";
      } else {
        status = "INCOMPLETE";
      }
      items.add(
          new TaxCoverageRow(
              shop.id().toString(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              required,
              covered,
              (int) gapCount,
              status,
              coverageStatusLabel(status),
              jurisdictionItems));
    }
    return new TaxCoverageList(
        items,
        items.size(),
        "🔴 覆盖情况的来源是**站点目录**：店铺启用的、要求 VAT 的站点 ⇒ 国家 ⇒ 应当登记的辖区。"
            + "「推不出要求」的辖区（美国的州一级登记）**不在这里出现** —— "
            + "那不是「都齐了」，是「系统推不出来」，能力声明里写明了这条边界。");
  }

  private static String coverageStatusLabel(String status) {
    return switch (status) {
      case "COMPLETE" -> "辖区都登记了";
      case "INCOMPLETE" -> "有辖区没登记";
      default -> "无要求（推不出）";
    };
  }

  /** 缺失项排序：严重度优先，同级按辖区与主体稳定排序（保证分页不因并列而抖动）。 */
  static Comparator<GapRow> gapOrder() {
    return Comparator.comparing((GapRow row) -> SEVERITY_HIGH.equals(row.gap().severity()) ? 0 : 1)
        .thenComparing(row -> row.gap().jurisdictionCode())
        .thenComparing(row -> row.gap().subjectType())
        .thenComparing(row -> row.gap().subjectId());
  }
}
