package com.starcon.trade;

import static com.starcon.trade.CustomsElementSetModels.*;

import com.starcon.trade.CustomsElementSetPort.OffsetDateTimeHolder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.5.1.2 申报要素。
 *
 * <p><b>本模块与 catalog 的边界</b>（完整论证见 V138 文件头第零之二节，这里只说结论）：
 * catalog 的 3.7.1.1 / 3.7.1.2 管<b>归类事实</b>（HS 编码、归类口径原产地、法定计量单位、
 * 基础申报品名、基础申报要素清单、享惠资格）。本模块管三件 catalog 明确不做的事：
 *
 * <ol>
 *   <li><b>按目的地国家差异化</b> —— catalog V95 第二节明文拒绝在目的地版本里复制申报要素
 *       （理由是会造成第二个事实来源）。那个论证是对的，但同时也留下一个真空：
 *       各国申报要素体系确实不同，而那个差异在 catalog 侧无处安放。
 *       本表就是它的唯一安放处，读 catalog 的合成结果（授权 HTTP）而不复制它的行。
 *   <li><b>批次实际信息</b>（数量 / 单价 / 币种）—— catalog 的
 *       {@code declared_value_reference} 是<b>参考值不是申报值</b>，V94 零之三明文禁止
 *       下游把它当申报货值用。成交价是 10 报关的事实，本模块正是它的上游。
 *   <li><b>缺失项提示</b> —— catalog 规则 A4 是审核那一刻的一次性二元判定；
 *       蓝图对本模块要的是「逐项点名缺了哪一项、为什么」。
 * </ol>
 *
 * <p><b>跨服务只走 {@link CustomsElementSetPort}</b>：catalog 的
 * {@code GET /catalog/customs-declaration/resolve}。零跨服务 SQL、零跨库 FK、零共享视图。
 *
 * <p><b>列表与详情刻意不依赖跨服务调用</b>（10.2.1.1 的教训：为高频读列表引入
 * 「商品中心不可达就整页 503」的依赖，会让商品中心宕机时现场连自己的申报要素都看不到）。
 * 跨服务核对只发生在<b>写路径</b>上 —— 建集、改集、发布，因为那正是「必须核实上游」
 * 的时刻，而写是低频的。
 */
@Service
public class CustomsElementSetService {

  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 九件申报要素本体的缺失判据（**服务层与 DB 触发器 C4 逐条对应**）。
   *
   * <p>写成一张字段表而不是散在各处，是为了让「缺失项提示」和「发布闸」永远同口径：
   * 两者一旦各写一份，运营就会遇到「提示说不齐、可发布却是绿的」这种无法解释的状态。
   * DB 侧 V138 的 {@code guard_element_set_publish} 是同一张表的 plpgsql 复刻。
   */
  private record StandardField(String field, String label, String reason) {}

  private static final List<StandardField> STANDARD_FIELDS =
      List.of(
          new StandardField("declared_name", "申报品名",
              "报关单上申报品名一栏为空，海关无法据此归类，归类不符直接导致退单或罚款"),
          new StandardField("spec_material", "规格材质",
              "材质是绝大多数 HS 归类的直接依据，没有它海关只能按最保守的口径归类，税率随之变高"),
          new StandardField("usage", "用途",
              "用途决定归类（例「手机充电」与「工业设备」可能落在不同税目），缺用途的申报资料会被判填报不符"),
          new StandardField("brand_type", "品牌类型",
              "多个法域（例美国）的品牌申报要素问的是类型（自有 / 贴牌 / 无品牌），只报品牌名答不出这一栏"),
          new StandardField("brand", "品牌",
              "即使无品牌也要填「无品牌」或 N/A：品牌一栏空着与「明确没有品牌」在海关侧是两个不同状态"),
          new StandardField("quantity", "数量",
              "海关按法定计量单位申报，没有数量的申报资料无法完成归类与查验"),
          new StandardField("unit_price", "单价",
              "单价 × 数量 = 申报货值，缺单价就没有货值，而货值错误直接导致补税与罚款"),
          new StandardField("currency", "币种",
              "缺币种的货值是一个没有量纲的数字，海关无从换算成申报国货币"),
          new StandardField("origin_country_code", "产地",
              "产地决定适用税率与是否适用自贸协定，缺产地等于让海关按最高税率核定"));

  /** 排序白名单。注入非法键时**静默回落默认键**而不是报错（照 10.2.1.1 同一纪律）。 */
  private static final List<ElementSetSortOption> SORTS =
      List.of(
          new ElementSetSortOption("UPDATED_AT_DESC", "最近更新"),
          new ElementSetSortOption("CREATED_AT_DESC", "最近创建"),
          new ElementSetSortOption("DESTINATION_ASC", "目的地（国别）"),
          new ElementSetSortOption("VERSION_DESC", "版本号（新→旧）"),
          new ElementSetSortOption("MISSING_DESC", "缺失项多的排前面"));

  /** 缺失项严重度里 NOTICE 那一档的口径提示文案。 */
  private static final String NOTICE_TEXT =
      "「已发布」的这一版不会因为缺失项被撤回，但缺失项会在报关时被海关逐项比对，"
          + "可能构成填报不符 —— 改这一版要新建版本（version_no + 1），不能原地改";

  /** 列表行的 SELECT 列。 */
  private static final String ROW_COLUMNS =
      "s.id, s.tenant_id, s.sku_id, s.destination_country_code, s.version_no, s.declared_name,"
          + " s.spec_material, s.usage, s.brand_type, s.brand, s.quantity, s.unit_price,"
          + " s.currency, s.origin_country_code, s.origin_rule_code,"
          + " s.preferential_rate_applicable, s.status, s.effective_from, s.effective_to,"
          + " s.published_by, s.published_at, s.sales_title_snapshot, s.version, s.note,"
          + " s.created_at, s.updated_at";

  /** 九件本体的缺失条数（**现算**，见 V138 第五节「为什么不落表」）。 */
  private static final String STANDARD_MISSING_SQL =
      "((case when btrim(s.declared_name)='' then 1 else 0 end)"
          + " + (case when btrim(s.spec_material)='' then 1 else 0 end)"
          + " + (case when btrim(s.usage)='' then 1 else 0 end)"
          + " + (case when btrim(s.brand_type)='' then 1 else 0 end)"
          + " + (case when btrim(s.brand)='' then 1 else 0 end)"
          + " + (case when s.quantity is null then 1 else 0 end)"
          + " + (case when s.unit_price is null then 1 else 0 end)"
          + " + (case when btrim(s.currency)='' then 1 else 0 end)"
          + " + (case when btrim(s.origin_country_code)='' then 1 else 0 end)"
          // 原产地规则**只在主张优惠税率时**才是必填格：没主张优惠就没有协定可填。
          + " + (case when s.preferential_rate_applicable"
          + "         and btrim(s.origin_rule_code)='' then 1 else 0 end))";

  /** required 补充项里没填值的条数（跨表，与 V138 的 C4 同一判据）。 */
  private static final String ITEM_MISSING_SQL =
      "(select count(*) from customs_element_set_item i"
          + " where i.tenant_id = s.tenant_id and i.element_set_id = s.id"
          + "   and i.required and btrim(i.element_value)='')";

  private final DSLContext sql;
  private final CustomsElementSetPort catalog;

  public CustomsElementSetService(DSLContext sql, CustomsElementSetPort catalog) {
    this.sql = sql;
    this.catalog = catalog;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>三处「拿不到却必须说」：HS 归类与税号（属 catalog，本模块不产出也不复制）、
   * 法定计量单位（catalog 独有，V95 第二节「只能来自基础属性」）、
   * 税率与优惠幅度（16 合规 + 12 财务，本模块只声明**是否主张**，不判定能不能享）。
   */
  public ElementSetCapabilities capabilities() {
    var currencies = new ArrayList<ElementSetVocabularyItem>();
    for (var c : ElementSetCurrency.values()) {
      currencies.add(new ElementSetVocabularyItem(c.code(), c.label(), true));
    }
    var brandTypes = new ArrayList<ElementSetVocabularyItem>();
    for (var b : ElementSetBrandType.values()) {
      brandTypes.add(new ElementSetVocabularyItem(b.code(), b.label(), true));
    }
    var statuses = new ArrayList<ElementSetVocabularyItem>();
    for (var s : ElementSetStatus.values()) {
      statuses.add(new ElementSetVocabularyItem(s.name(), s.label(), true));
    }
    var severities = new ArrayList<ElementSetVocabularyItem>();
    for (var s : ElementSetMissingSeverity.values()) {
      severities.add(new ElementSetVocabularyItem(s.name(), s.label(), true));
    }

    var elementFields = new ArrayList<ElementSetVocabularyItem>();
    for (var f : STANDARD_FIELDS) {
      elementFields.add(new ElementSetVocabularyItem(f.field(), f.label(), true));
    }
    elementFields.add(new ElementSetVocabularyItem("origin_rule_code", "原产地规则", false));

    return new ElementSetCapabilities(
        List.of(
            new ElementSetCapabilityDimension(
                "ELEMENT_SET",
                "申报品名、规格材质、用途、品牌类型、品牌、数量、单价、币种、产地、原产地规则（九件本体）",
                true,
                "本模块 V138 的 customs_element_set，**唯一写入所有者**",
                null,
                "九件是**强类型列**而不是无结构文本：数量/单价要过 NUMERIC、币种要过 ISO 4217 枚举，"
                    + "存成文本就把这些约束降级成应用层自觉，DB 层再也拦不住一个非法币种"),
            new ElementSetCapabilityDimension(
                "DESTINATION_VARIANT",
                "按目的地国家差异化（同一 SKU 在不同国家各有一套申报要素）",
                true,
                "本模块 customs_element_set 的 (sku_id, destination_country_code, version_no) 维度",
                null,
                "**catalog 3.7.1.2 明文不做这件事**（V95 第二节：目的地版本不覆盖申报要素），"
                    + "所以本模块是那个差异的唯一安放处。不接受 '*' 通配目的地 —— "
                    + "照搬 V85/V92 的通配语法会让一份 CN 口径的资料被申报到德国"),
            new ElementSetCapabilityDimension(
                "SUPPLEMENT_ITEM",
                "该目的地特有的、**九件之外**的补充申报要素项（有序）",
                true,
                "本模块 customs_element_set_item（ordinal + element_code）",
                null,
                "钉成固定列会拒掉一部分真实目的地：各国申报要素编号体系不统一（常见 \"01\" 也有 \"A1\" 形态）"),
            new ElementSetCapabilityDimension(
                "BATCH_ACTUALS",
                "批次实际信息：数量、单价、币种",
                true,
                "本模块 V138（numeric(19,6) 数量 / numeric(19,4) 单价 + BigDecimal，全程禁浮点）",
                null,
                "**catalog 的 declared_value_reference 是参考值不是申报值**，"
                    + "V94 零之三明文禁止下游把它当申报货值用；成交价是 10 报关的事实，本模块正是它的上游"),
            new ElementSetCapabilityDimension(
                "MISSING_ITEMS",
                "缺失项提示（逐项点名缺了哪一项、为什么）",
                true,
                "查询时现算（V138 第五节）",
                null,
                "**刻意不落表**：缺失项是当前行的现算结论，落一列就得靠触发器每次同步，"
                    + "漏同步一次的后果是「已补齐却仍报缺失」。DB 层只留发布闸那个真二元事实"),
            new ElementSetCapabilityDimension(
                "ORIGIN_RULE",
                "原产地规则（RCEP / CPTPP 等）与优惠税率适用",
                true,
                "本模块 origin_rule_code + preferential_rate_applicable，**带生效区间**",
                null,
                "本模块只声明**是否主张优惠**，**不判定法律上能不能享**（「实质性改变」那类判断属"
                    + "10.5.2.1 / 16 合规，且那两条线都要求业务确认）。规则清单是 16 合规的资产，"
                    + "这里只钉形状并把常用值放在 originRules 词表里"),
            new ElementSetCapabilityDimension(
                "HS_CLASSIFICATION",
                "HS 编码 / 目的国税号",
                false,
                "catalog 3.7.1.1 customs_attribute.hs_code 与 3.7.1.2 destination_hs_code",
                "3.7.1.1 / 3.7.1.2",
                "本模块**不产出也不复制**归类事实：V95 第二节论证过「复制 = 第二个事实来源」。"
                    + "界面上经上游预览只读展示，供建集时对照"),
            new ElementSetCapabilityDimension(
                "STATUTORY_UOM",
                "法定计量单位",
                false,
                "catalog 3.7.1.1 statutory_unit_code",
                "3.7.1.1",
                "V95 第二节明文：法定单位**只能来自基础属性**，目的地版本不覆盖。"
                    + "本模块不落这一列 —— 数量按法定单位申报这件事由 catalog 那一层负责，"
                    + "再落一份就出现两个口径"),
            new ElementSetCapabilityDimension(
                "TARIFF_RATE",
                "优惠幅度 / 协定税率数值",
                false,
                "16 合规（税则）+ 12 财务（关税成本）均未交付可授权读的口径",
                "16 合规 / 10.5.1.3 关税与税费",
                "本模块**不产出**税率：优惠幅度取决于目的国税则与实质性改变判定，"
                    + "而那两条线都未交付。本模块只回答「这票货主张不主张优惠」，"
                    + "**主张不等于会免** —— 界面不要把勾选显示成「已享惠」"),
            new ElementSetCapabilityDimension(
                "CUSTOMS_SUBMISSION",
                "把本模块的要素集提交给报关行 / IOR",
                false,
                "10.5.1.1 报关单管理（warehouse V85）已交付，但它是**下游**，由它取本模块的数据",
                "10.5.1.1",
                "本模块**不写**报关明细行：报关单行的唯一写入所有者是 10.5.1.1，"
                    + "双写会让「这票货报了什么」出现两个来源。拍平成 jsonb 发生在 10.5.1.1 那一侧")),
        currencies,
        brandTypes,
        List.of(
            new ElementSetVocabularyItem("", "不主张优惠", true),
            new ElementSetVocabularyItem("RCEP", "RCEP（区域全面经济伙伴关系协定）", false),
            new ElementSetVocabularyItem("CPTPP", "CPTPP（跨太平洋伙伴关系协定）", false),
            new ElementSetVocabularyItem("CEPA", "CEPA / RCEP 成员国双边协定", false),
            new ElementSetVocabularyItem("CO", "CO（原产地证）", false),
            new ElementSetVocabularyItem("FTA", "FTA（其他自贸协定）", false),
            new ElementSetVocabularyItem("GSP", "GSP（普惠制）", false)),
        elementFields,
        statuses,
        severities,
        "生效区间是 **[effective_from, effective_to) 半开区间**，to 为空 = 一直有效。"
            + "必须有区间：原产地规则与优惠税率适用性会变（RCEP 降税清单逐年调整、各国税则定期换版），"
            + "一份没有区间的申报要素集永远「有效」，等于让三年前的税率口径被今天直接引用。"
            + "已发布只能向前关闭区间，不能原地改内容（改内容 = 新建版本）。",
        "**申报品名不能照搬销售标题**：申报品名是给海关归类用的清关口径，销售标题是给顾客看的转化文案，"
            + "两者用途不同，混用是常见被退单原因。两道防线：① 建集/改集时经上游接口取到真实销售标题后逐字"
            + "（忽略大小写与首尾空白）比对；② 建集时把当时的销售标题存进 sales_title_snapshot，"
            + "DB 层 CHECK + 触发器挡住绕过服务层的照抄。申报品名是**独立字段**，不是销售标题换个名字。",
        "与 catalog 的边界：HS 编码 / 归类口径原产地 / 法定计量单位 / 基础申报品名 / 基础申报要素清单 / "
            + "享惠资格**都属 catalog**（3.7.1.1 / 3.7.1.2），本模块只读不写；"
            + "本模块拥有的是**按目的地差异化的申报要素集**、**批次实际信息**与**缺失项提示**。"
            + "catalog V95 第二节明文「申报要素只来自基础属性，目的地版本不复制它」，"
            + "本模块就是那个目的地维度的唯一安放处 —— 两边都成立，因为本模块读的是 catalog 的合成结果"
            + "（授权 HTTP），不复制它的行。");
  }

  // ================================================================ 筛选下拉

  /** 筛选下拉：**真实数据 DISTINCT**，不是硬编码词表。 */
  public ElementSetFilterOptions filterOptions() {
    var destinations = new ArrayList<ElementSetVocabularyItem>();
    for (var r :
        sql.fetch(
            "select distinct destination_country_code as c from customs_element_set"
                + " order by destination_country_code")) {
      var code = r.get("c", String.class);
      destinations.add(new ElementSetVocabularyItem(code, code, false));
    }
    var statuses = new ArrayList<ElementSetVocabularyItem>();
    for (var r :
        sql.fetch("select distinct status as c from customs_element_set order by status")) {
      var code = r.get("c", String.class);
      var label = code;
      for (var s : ElementSetStatus.values()) {
        if (s.name().equals(code)) label = s.label();
      }
      statuses.add(new ElementSetVocabularyItem(code, label, false));
    }
    var skuIds = new ArrayList<String>();
    for (var r :
        sql.fetch(
            "select distinct sku_id from customs_element_set order by sku_id limit 200")) {
      skuIds.add(r.get(0, UUID.class).toString());
    }
    long total = sql.fetchOne("select count(*) from customs_element_set").get(0, Long.class);
    return new ElementSetFilterOptions(
        destinations, statuses, skuIds, SORTS, SORTS.get(0).key(), total);
  }

  // ================================================================ 列表

  /**
   * 申报要素集清单。
   *
   * <p><b>汇总不带 LIMIT/OFFSET</b>：它永远覆盖筛选后的全量。
   * 跟着分页走的汇总是列表页最常见的假活（10.2.1.1 的注释里也钉了同一条）。
   *
   * @param at 生效性判定的时刻；{@code null} = 此刻。传时刻是为了「按 2026-01-01 的口径看
   *     哪些版本生效」这种回溯查询，而那是申报追溯的常见动作。
   */
  public ElementSetPage list(
      String destination,
      String status,
      String skuId,
      Boolean missing,
      String keyword,
      String sort,
      int page,
      int size,
      OffsetDateTime at) {
    var f = filters(destination, status, skuId, missing, keyword, at);
    int sizeSafe = Math.max(1, Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE));
    int pageSafe = Math.max(0, page);
    long total =
        sql.fetchOne(
                "select count(*) as c from customs_element_set s" + f.where, f.params.toArray())
            .get("c", Long.class);
    // ⚠️ 两个必须写对的地方，少一个都只是「看起来能跑」：
    //   1) 绑定顺序要与占位符在 SQL 文本里出现的顺序一致 —— effective_now 的两个
    //      ?::timestamptz 在 SELECT 列表里，早于 WHERE，所以判定时刻先绑。
    //      顺序反了 PG 会把 "DE" 当 timestamptz 写进 bind index 1，
    //      而**没有筛选条件**的查询恰好蒙对（f.params 为空）—— 那种错只在带筛选时炸。
    //   2) 必须 .toArray()：jOOQ 的 fetch(String, Object...) 收到 List 时**不会**展开 varargs，
    //      整个 List 会被当成一个 ArrayList 参数，
    //      报 "Type class java.util.ArrayList is not supported in dialect POSTGRES"。
    var rows =
        sql.fetch(
            "select " + ROW_COLUMNS + ", " + STANDARD_MISSING_SQL + " as std_missing, "
                + ITEM_MISSING_SQL + " as item_missing,"
                + " (case when s.effective_from <= ?::timestamptz"
                + "   and (s.effective_to is null or ?::timestamptz < s.effective_to)"
                + "   then true else false end) as effective_now"
                + " from customs_element_set s" + f.where
                + " order by " + orderBy(sort)
                + " limit ? offset ?",
            concat(
                    concat(List.of(judgeAt(at), judgeAt(at)), f.params),
                    List.of(sizeSafe, (long) pageSafe * sizeSafe))
                .toArray());
    var items = new ArrayList<ElementSetRow>();
    for (var r : rows) {
      items.add(toRow(r));
    }
    // 汇总必须与行用**同一个**判定时刻：行按 at 标注 effectiveNow，
    // 汇总若偷偷用 now()，那么「按 2030 年看没有生效版本」这一问会得到 1 ——
    // 汇总与行对同一批数据给两个答案，运营会以为其中一个坏了。
    var summary = summary(f.where, f.params, at);
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / sizeSafe);
    return new ElementSetPage(
        items, summary, pageSafe, sizeSafe, total, totalPages, sortNote(sort));
  }

  private ElementSetSummary summary(String where, List<Object> params, OffsetDateTime at) {
    var r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where s.status='DRAFT') as draft,"
                + " count(*) filter (where s.status='PUBLISHED') as published,"
                + " count(*) filter (where s.effective_from <= ?::timestamptz"
                + "   and (s.effective_to is null or ?::timestamptz < s.effective_to)) as effective_now,"
                + " count(*) filter (where " + STANDARD_MISSING_SQL + " > 0 or "
                + ITEM_MISSING_SQL + " > 0) as missing_any,"
                + " count(*) filter (where s.status='PUBLISHED' and ("
                + STANDARD_MISSING_SQL + " > 0 or " + ITEM_MISSING_SQL + " > 0))"
                + "   as published_missing"
                + " from customs_element_set s" + where,
            // 同 list()：判定时刻的占位符在 SELECT 列表里（早于 WHERE），且必须 .toArray()。
            concat(List.of(judgeAt(at), judgeAt(at)), params).toArray());
    return new ElementSetSummary(
        intOf(r, "total"),
        intOf(r, "draft"),
        intOf(r, "published"),
        intOf(r, "effective_now"),
        intOf(r, "missing_any"),
        intOf(r, "published_missing"));
  }

  // ================================================================ 详情

  /** 详情：清单行 + 补充要素项 + 缺失项提示 + 可执行动作 + 边界声明。 */
  public ElementSetDetail detail(String id) {
    var setId = parseUuid(id);
    var r = load(setId);
    var items = items(setId);
    var missing = missing(r, items);
    int blocking = (int) missing.stream()
        .filter(m -> m.severity() == ElementSetMissingSeverity.BLOCKER)
        .count();
    var row = toRow(r, missing.size(), blocking);
    boolean complete = blocking == 0;
    String status = row.status();
    var actions = new ArrayList<String>();
    if ("DRAFT".equals(status)) {
      actions.add("EDIT");
      if (complete) actions.add("PUBLISH");
    } else {
      // 已发布不可原地改正文（V138 规则 C7），所以界面只给「向前关闭区间」这一个动作。
      actions.add("CLOSE");
    }
    return new ElementSetDetail(
        row,
        items,
        missing,
        complete,
        complete && "DRAFT".equals(status),
        r.get("sales_title_snapshot", String.class),
        actions,
        "本集合与 catalog 的关系：HS 编码 / 法定计量单位 / 归类口径产地 / 基础申报要素属 catalog，"
            + "本模块只读不写（经授权 HTTP）；本模块拥有的是**按目的地的申报要素集本体**、"
            + "批次实际信息与缺失项提示。10.5.1.1 报关单是本模块的下游，它把这一版抄进自己的"
            + "jsonb 快照 —— 所以已发布之后不能原地改正文，要改就新建版本。");
  }

  /** 上游合成申报资料预览（3.7.1.2 目的地报关版本，**跨服务**）。 */
  public ElementSetUpstreamPreview preview(String skuId, String destination, OffsetDateTime at, String bearer) {
    var id = parseUuid(skuId, "SKU 标识不是合法 UUID：" + skuId);
    return catalog.preview(id, destination, at == null ? OffsetDateTimeHolder.now() : OffsetDateTimeHolder.at(at), bearer);
  }

  // ================================================================ 写路径

  /**
   * 建集。
   *
   * <p><b>建集这一刻就要经上游核实两件事</b>（写路径才引入跨服务依赖的理由）：
   *
   * <ol>
   *   <li><b>这个 SKU 在这个目的地确实有生效的报关版本</b>（3.7.1.2）。没有就 404 ——
   *       不退回基础属性凑一份，那正是「用一套申报资料打通全球」。
   *   <li><b>取到真实销售标题</b>，存进 sales_title_snapshot 并与申报品名逐字比对（规则 C1）。
   *       这一条服务层之所以能做到，是因为 catalog 的 resolve 响应里带 sku_name ——
   *       销售标题在商品侧，而「申报品名 ≠ 销售标题」这道闸需要一个能与销售标题比较的实体。
   * </ol>
   */
  @Transactional
  public ElementSetSaved create(ElementSetInput input, String bearer) {
    validate(input);
    var upstream = catalog.preview(input.skuId(), input.destinationCountryCode(), OffsetDateTimeHolder.now(), bearer);
    String salesTitle = upstream.salesTitle() == null ? "" : upstream.salesTitle();
    if (salesTitle.isBlank()) {
      throw badRequest(
          "商品中心没有返回这个 SKU 的销售标题，申报品名无法校验：规则「申报品名不能照搬销售标题」"
              + "需要拿销售标题作比对基准，没有基准这道闸就形同虚设。本模块不会跳过这道校验建集。");
    }
    checkNotSalesTitle(input.declaredName(), salesTitle);
    checkOriginAgainstUpstream(input.originCountryCode(), upstream);

    var id = UUID.randomUUID();
    // ⚠️ timestamptz 一律 `?::timestamptz` + OffsetDateTime：
    //   用 Timestamp.valueOf(LocalDateTime) 会**静默偏 −28800 秒**，不抛异常只是时刻错了 8 小时。
    sql.execute(
        "insert into customs_element_set (id, tenant_id, sku_id, destination_country_code,"
            + " version_no, declared_name, sales_title_snapshot, spec_material, usage, brand_type,"
            + " brand, quantity, unit_price, currency, origin_country_code, origin_rule_code,"
            + " preferential_rate_applicable, effective_from, effective_to, status, note)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'DRAFT',?)",
        id,
        currentTenantId(),
        input.skuId(),
        input.destinationCountryCode().trim().toUpperCase(Locale.ROOT),
        input.versionNo(),
        blankToEmpty(input.declaredName()),
        truncate(salesTitle, 500),
        blankToEmpty(input.specMaterial()),
        blankToEmpty(input.usage()),
        normalize(input.brandType()),
        blankToEmpty(input.brand()),
        input.quantity(),
        input.unitPrice(),
        normalizeCurrency(input.currency()),
        normalize(input.originCountryCode()),
        normalize(input.originRuleCode()),
        Boolean.TRUE.equals(input.preferentialRateApplicable()),
        input.effectiveFrom(),
        input.effectiveTo(),
        blankToEmpty(input.note()));
    replaceItems(id, input.items());
    return new ElementSetSaved(detail(id.toString()));
  }

  /**
   * 改集（**仅草稿**）。
   *
   * <p>乐观锁：{@code where id=? and version=?}。两个人同时改，后到的那个必须撞 409
   * 而不是静默覆盖先到的那一份申报事实（与 sku_package / customs_attribute 同一套口径）。
   *
   * <p>补充要素项**整体替换**（删旧 + 插新）而不是逐项 diff：申报要素是**有序清单**，
   * 局部 diff 要额外维护位次重排规则，而那套规则错了的后果是「海关按序比对判定填报不符」。
   * 整体替换让位次由请求显式给出。
   */
  @Transactional
  public ElementSetSaved update(String id, ElementSetInput input, String bearer) {
    validate(input);
    var setId = parseUuid(id);
    var current = load(setId);
    if (!"DRAFT".equals(current.get("status", String.class))) {
      throw conflict(
          "已发布的申报要素集不能改：10.5.1.1 已把这一版抄进报关单，"
              + "改这一版会让库里的申报事实与报关单上写的那份对不上，而事后复核只能查到其中一份。"
              + "要改正文请新建一个版本（version_no + 1），不要改这一版。");
    }
    var upstream = catalog.preview(input.skuId(), input.destinationCountryCode(), OffsetDateTimeHolder.now(), bearer);
    String salesTitle = upstream.salesTitle() == null ? "" : upstream.salesTitle();
    checkNotSalesTitle(input.declaredName(), salesTitle);
    checkOriginAgainstUpstream(input.originCountryCode(), upstream);

    int affected =
        sql.execute(
            "update customs_element_set set sku_id=?, destination_country_code=?, version_no=?,"
                + " declared_name=?, sales_title_snapshot=?, spec_material=?, usage=?, brand_type=?,"
                + " brand=?, quantity=?, unit_price=?, currency=?, origin_country_code=?,"
                + " origin_rule_code=?, preferential_rate_applicable=?,"
                + " effective_from=?::timestamptz, effective_to=?::timestamptz, note=?,"
                + " version=version+1, updated_at=now()"
                + " where id=? and version=? and status='DRAFT'",
            input.skuId(),
            input.destinationCountryCode().trim().toUpperCase(Locale.ROOT),
            input.versionNo(),
            blankToEmpty(input.declaredName()),
            truncate(salesTitle, 500),
            blankToEmpty(input.specMaterial()),
            blankToEmpty(input.usage()),
            normalize(input.brandType()),
            blankToEmpty(input.brand()),
            input.quantity(),
            input.unitPrice(),
            normalizeCurrency(input.currency()),
            normalize(input.originCountryCode()),
            normalize(input.originRuleCode()),
            Boolean.TRUE.equals(input.preferentialRateApplicable()),
            input.effectiveFrom(),
            input.effectiveTo(),
            blankToEmpty(input.note()),
            setId,
            requireVersion(input.version()));
    if (affected == 0) {
      throw conflict(
          "这一版已被别人改动（乐观锁 version 不匹配），你的修改没有落库："
              + "申报要素是报关事实，后到的写入必须撞 409 而不是静默覆盖先到的那一份。"
              + "请重新载入最新版本再改。");
    }
    replaceItems(setId, input.items());
    return new ElementSetSaved(detail(id));
  }

  /**
   * 发布。
   *
   * <p><b>三道闸，缺一不可</b>：
   *
   * <ol>
   *   <li>该目的地确实有生效的上游报关版本（跨服务；取不到就 503 / 404，不降级）；
   *   <li>产地与上游归类口径产地一致（不一致时静默取其一会让申报事实与归类依据脱节）；
   *   <li>九件本体 + required 补充项齐备（与 V138 的 C4 同一判据；服务层先报错给中文逐项清单，
   *       DB 触发器再钉一道，防止绕过服务层直接写 SQL 发布）。
   * </ol>
   *
   * @param publishedBy 发布人（已认证成员 id，来自 {@code AccessService.Access}，**不是请求头**）。
   *     V138 的 CHECK 与触发器都钉死「已发布必须有发布人」：没有发布人的「已发布」等于
   *     「有人按过发布」这种无法证伪的说法，而这份资料会被 10.5.1.1 原样引用。
   */
  @Transactional
  public ElementSetSaved publish(String id, ElementSetPublishInput input, String bearer, UUID publishedBy) {
    var setId = parseUuid(id);
    var current = load(setId);
    if (!"DRAFT".equals(current.get("status", String.class))) {
      throw conflict("这一版已经是已发布状态，发布是单向的（不可撤回）：已发布版本会被 10.5.1.1 引用，"
          + "撤回会让「这批货当时按哪一版申报的」无从查证。");
    }
    var skuId = current.get("sku_id", UUID.class);
    var destination = current.get("destination_country_code", String.class);
    var upstream = catalog.preview(skuId, destination, OffsetDateTimeHolder.now(), bearer);
    checkOriginAgainstUpstream(current.get("origin_country_code", String.class), upstream);
    checkIntervalAgainstPublished(setId, skuId, destination, current);

    var items = items(setId);
    var missing = missing(current, items);
    var blockers = missing.stream()
        .filter(m -> m.severity() == ElementSetMissingSeverity.BLOCKER)
        .map(ElementSetMissingItem::label)
        .toList();
    if (!blockers.isEmpty()) {
      throw conflict(
          "不能发布：申报要素还缺 [" + String.join("、", blockers) + "]。"
              + "海关按申报要素逐项比对，缺一项就是填报不符，而退单与罚款由卖家承担。"
              + "逐项原因见详情页的缺失项提示。");
    }
    checkNotSalesTitle(current.get("declared_name", String.class),
        current.get("sales_title_snapshot", String.class));

    int affected =
        sql.execute(
            "update customs_element_set set status='PUBLISHED', published_by=?,"
                + " published_at=?::timestamptz, version=version+1, updated_at=now()"
                + " where id=? and version=? and status='DRAFT'",
            publishedBy, OffsetDateTime.now(), setId, requireVersion(input.version()));
    if (affected == 0) {
      throw conflict("这一版已被别人改动（乐观锁 version 不匹配），发布没有落库。请重新载入最新版本。");
    }
    return new ElementSetSaved(detail(id));
  }

  /**
   * 向前关闭生效区间（规则 R5）。
   *
   * <p>只允许把终点**收紧到一个仍在未来的时刻**：放宽等于把这一版继续用于它覆盖不到的
   * 未来申报，收紧到过去等于追溯改写历史申报。两者都由服务层与 DB 触发器各钉一道。
   */
  @Transactional
  public ElementSetSaved close(String id, ElementSetCloseInput input) {
    var setId = parseUuid(id);
    var current = load(setId);
    if (!"PUBLISHED".equals(current.get("status", String.class))) {
      throw conflict("只有已发布的申报要素集才谈得上「停止使用」：草稿还没被下游引用，"
          + "不需要关闭区间（草稿要停用请直接不发布）。");
    }
    var from = current.get("effective_from", OffsetDateTime.class);
    // ⚠️ null（= 一直有效 / +∞）必须**显式**拒掉，不能让它一路走到 isAfter 去炸 NPE：
    //   NPE 出去是 500 + "null"，运营完全看不懂这是「你想放宽区间」；
    //   而把终点设空正是本接口明令禁止的动作（规则 R5）。
    if (input.effectiveTo() == null) {
      throw badRequest(
          "不能把生效终点放空：终点为空 = 一直有效（+∞），而本接口只允许**收紧**区间 —— "
              + "放宽终点等于让这一版继续用于它覆盖不到的未来申报，"
              + "而它已经被 10.5.1.1 引用过了。要停止使用请给一个仍在未来的具体时刻。");
    }
    if (!input.effectiveTo().isAfter(from)) {
      throw badRequest("生效终点必须严格晚于生效起点（" + from + "）："
          + "区间是 [effective_from, effective_to) 半开区间，终点不晚于起点等于「从不生效」。");
    }
    if (!input.effectiveTo().isAfter(OffsetDateTime.now())) {
      throw badRequest("生效终点只能设在未来：收紧终点的用途是「这一版从现在起不再使用」，"
          + "设到已经过去的时刻等于追溯改写历史申报（那批货当时报的就是这一版，改不得）。");
    }
    int affected =
        sql.execute(
            "update customs_element_set set effective_to=?::timestamptz,"
                + " version=version+1, updated_at=now()"
                + " where id=? and version=? and status='PUBLISHED'",
            input.effectiveTo(), setId, requireVersion(input.version()));
    if (affected == 0) {
      throw conflict("这一版已被别人改动（乐观锁 version 不匹配），关闭区间没有落库。请重新载入最新版本。");
    }
    return new ElementSetSaved(detail(id));
  }

  // ================================================================ 缺失项提示（现算）

  /**
   * 缺失项提示：**逐项点名**缺了哪一项、为什么。
   *
   * <p>刻意逐条给理由而不是只给字段名：运营看到「品牌类型没填」不知道该填什么，
   * 看到「多个法域（例美国）的品牌申报要素问的是类型」才知道。同一条来自 V94 规则 A4
   * 的论证：「逐项缺哪一项只有一次报全才有意义」。
   */
  private List<ElementSetMissingItem> missing(Record r, List<ElementSetItemRow> items) {
    var out = new ArrayList<ElementSetMissingItem>();
    for (var f : STANDARD_FIELDS) {
      if (isBlank(r.get(f.field(), String.class))) {
        out.add(new ElementSetMissingItem(f.field(), f.label(), ElementSetMissingSeverity.BLOCKER, f.reason()));
      }
    }
    if (Boolean.TRUE.equals(r.get("preferential_rate_applicable", Boolean.class))
        && isBlank(r.get("origin_rule_code", String.class))) {
      out.add(new ElementSetMissingItem(
          "origin_rule_code", "原产地规则", ElementSetMissingSeverity.BLOCKER,
          "已勾选主张优惠税率就必须说清按哪个协定：海关要凭协定代码才能适用优惠税率，"
              + "没有它这项优惠直接失效，而运营以为已经享惠 —— 少缴的税到年底可能被追缴"));
    }
    if (Boolean.TRUE.equals(r.get("preferential_rate_applicable", Boolean.class))) {
      out.add(new ElementSetMissingItem(
          "preferential_rate_applicable", "优惠税率适用（口径提示）", ElementSetMissingSeverity.NOTICE,
          "**主张优惠不等于会免**：优惠幅度取决于目的国税则与「实质性改变」判定，"
              + "那属 16 合规与 10.5.2.1，且都要求业务确认。本模块只记录主张，不做法律判定。"));
    }
    for (var it : items) {
      if (it.required() && isBlank(it.elementValue())) {
        out.add(new ElementSetMissingItem(
            "item:" + it.elementCode(), it.elementName(), ElementSetMissingSeverity.BLOCKER,
            "补充申报要素「" + it.elementCode() + "」标记为必填但还没填值：海关逐项比对，缺一项即填报不符"));
      }
    }
    return out;
  }

  // ================================================================ 内部工具

  private Record load(UUID setId) {
    Record r =
        sql.fetchOne("select " + ROW_COLUMNS + " from customs_element_set s where s.id = ?", setId);
    if (r == null) throw notFound("申报要素集不存在：" + setId);
    return r;
  }

  private List<ElementSetItemRow> items(UUID setId) {
    var out = new ArrayList<ElementSetItemRow>();
    sql.fetch(
            "select id, ordinal, element_code, element_name, element_value, required"
                + " from customs_element_set_item where element_set_id = ? order by ordinal",
            setId)
        .forEach(
            x ->
                out.add(
                    new ElementSetItemRow(
                        x.get("id", UUID.class),
                        x.get("ordinal", Integer.class),
                        x.get("element_code", String.class),
                        x.get("element_name", String.class),
                        x.get("element_value", String.class),
                        x.get("required", Boolean.class))));
    return out;
  }

  private void replaceItems(UUID setId, List<ElementSetItemInput> inputs) {
    sql.execute("delete from customs_element_set_item where element_set_id = ?", setId);
    if (inputs == null) return;
    var seen = new java.util.HashSet<Integer>();
    for (var it : inputs) {
      if (!seen.add(it.ordinal())) {
        throw badRequest("补充申报要素的位次 " + it.ordinal() + " 重复了："
            + "申报要素是**有序**清单，海关按序比对，同一位次两行时「这一位填什么」无法回答。");
      }
      sql.execute(
          "insert into customs_element_set_item (id, tenant_id, element_set_id, ordinal,"
              + " element_code, element_name, element_value, required) values (?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), currentTenantId(), setId, it.ordinal(), it.elementCode().trim(),
          it.elementName().trim(), blankToEmpty(it.elementValue()),
          it.required() == null || it.required());
    }
  }

  private ElementSetRow toRow(Record r) {
    int stdMissing = orZero(r.get("std_missing", Integer.class));
    int itemMissing = orZero(r.get("item_missing", Integer.class));
    return toRow(r, stdMissing + itemMissing, stdMissing + itemMissing);
  }

  /**
   * 详情用的重载：**缺失条数由本方法算出的 missing 列表给出**，不从查询列读。
   *
   * <p>为什么不能直接复用列表那条查询：详情走 {@code load()}，那条 SELECT 不带
   * {@code std_missing} / {@code item_missing} 两列（它们只在列表的汇总上下文里现算）。
   * 若此处沿用「列为空记 0」，详情页的缺失计数会**恒为 0**，而清单行显示有缺失 ——
   * 同一个事实在两个页面上给两个答案，运营会以为其中一个坏了。
   */
  private ElementSetRow toRow(Record r, int missingCount, int blockingCount) {
    return new ElementSetRow(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("destination_country_code", String.class),
        r.get("version_no", Integer.class),
        r.get("declared_name", String.class),
        r.get("spec_material", String.class),
        r.get("brand", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("origin_country_code", String.class),
        r.get("origin_rule_code", String.class),
        Boolean.TRUE.equals(r.get("preferential_rate_applicable", Boolean.class)),
        r.get("status", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        // ⚠️ jOOQ 的 get(field, type) 在**字段不存在时抛 IllegalArgumentException**（严格模式），
        // 而详情路径走 load() 时 effective_now 这一列本来就不在 SELECT 里。
        // 必须先用 field() 问「有没有」：列在 → 用列（列表按传入的判定时刻算好）；
        // 列不在 → 按区间现算。列表列与详情的现算走同一段区间语义。
        effectiveFlag(r),
        missingCount,
        blockingCount,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 生效性：列表查询显式带了 effective_now 列，详情走 load() 时按区间现算。 */
  private boolean effectiveFlag(Record r) {
    org.jooq.Field<?> field = r.field("effective_now");
    if (field == null) return effectiveNow(r);
    return Boolean.TRUE.equals(field.getValue(r));
  }

  private static int orZero(Integer v) {
    return v == null ? 0 : v;
  }

  private boolean effectiveNow(Record r) {
    var from = r.get("effective_from", OffsetDateTime.class);
    var to = r.get("effective_to", OffsetDateTime.class);
    var now = OffsetDateTime.now();
    return !now.isBefore(from) && (to == null || now.isBefore(to));
  }

  // ------------------------------------------------------------ 筛选与排序

  private record Filters(String where, List<Object> params, String note) {}

  private Filters filters(
      String destination, String status, String skuId, Boolean missing, String keyword, OffsetDateTime at) {
    var clauses = new ArrayList<String>();
    var params = new ArrayList<Object>();
    var note = new StringBuilder();
    if (notBlank(destination)) {
      clauses.add("s.destination_country_code = ?");
      params.add(destination.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(status)) {
      var want = status.trim().toUpperCase(Locale.ROOT);
      boolean known = false;
      for (var v : ElementSetStatus.values()) {
        if (v.name().equals(want)) known = true;
      }
      if (!known) {
        throw badRequest("申报要素集状态筛选不认识：" + status
            + "；本模块只有 DRAFT（草稿）与 PUBLISHED（已发布）两个状态 ——"
            + "刻意**没有**「退回 / 作废」态，因为已发布不可原地改（要改就新建版本）。");
      }
      clauses.add("s.status = ?");
      params.add(want);
    }
    if (notBlank(skuId)) {
      clauses.add("s.sku_id = ?::uuid");
      params.add(parseUuid(skuId, "SKU 筛选值不是合法 UUID：" + skuId));
    }
    if (missing != null && missing) {
      clauses.add("(" + STANDARD_MISSING_SQL + " > 0 or " + ITEM_MISSING_SQL + " > 0)");
      note.append("只列有缺失项的（九件本体或 required 补充项没填）");
    }
    if (notBlank(keyword)) {
      clauses.add(
          "(s.declared_name ilike ? or s.spec_material ilike ? or s.brand ilike ?"
              + " or s.origin_rule_code ilike ? or s.note ilike ?"
              + " or s.destination_country_code ilike ? or s.sku_id::text ilike ?)");
      String like = "%" + keyword.trim() + "%";
      for (int i = 0; i < 7; i++) params.add(like);
    }
    if (note.length() > 0) note.append("；");
    note.append("生效性按 ").append(judgeAt(at)).append(" 判定");
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    return new Filters(where, params, note.toString());
  }

  private String orderBy(String sort) {
    if (sort == null || sort.isBlank()) return "s.updated_at desc, s.id";
    return switch (sort.trim().toUpperCase(Locale.ROOT)) {
      case "CREATED_AT_DESC" -> "s.created_at desc, s.id";
      case "DESTINATION_ASC" -> "s.destination_country_code asc, s.version_no desc, s.id";
      case "VERSION_DESC" -> "s.version_no desc, s.destination_country_code asc, s.id";
      case "MISSING_DESC" ->
          "(" + STANDARD_MISSING_SQL + " + " + ITEM_MISSING_SQL + ") desc, s.updated_at desc, s.id";
      default -> "s.updated_at desc, s.id";
    };
  }

  private String sortNote(String sort) {
    var key = (sort == null || sort.isBlank()) ? SORTS.get(0).key() : sort.trim().toUpperCase(Locale.ROOT);
    for (var o : SORTS) {
      if (o.key().equals(key)) return "按「" + o.label() + "」排序";
    }
    return "排序键不认识，已静默回落到「" + SORTS.get(0).label() + "」"
        + "（注入的排序键不生效，但表还在 —— 与 10.2.1.1 同一纪律）";
  }

  // ------------------------------------------------------------ 校验

  private void validate(ElementSetInput input) {
    String destination = input.destinationCountryCode().trim().toUpperCase(Locale.ROOT);
    // ⚠️ 形状必须在**服务层**自己钉一遍，不能只靠 DTO 上的 @Pattern：
    //   @Pattern 只在 HTTP 入口的 @Valid 上生效，任何一次不经控制器的调用
    //   （批处理 / 内部编排 / 另一个模块的 Service 直调）都会绕过它，
    //   然后让一个 4 字母的国家码去撞 varchar(2)，以 DataIntegrityViolation 收场 ——
    //   那是 500，而它是一个「你填错了」的 400。
    if (!destination.matches("[A-Z]{2}")) {
      throw badRequest(
          "目的地「" + input.destinationCountryCode() + "」不是有效的国家代码："
              + "必须是 ISO 3166-1 alpha-2 的**两位大写字母**（如 DE / US / JP）。"
              + "本模块不接受空值、也不接受 '*' 通配 —— 不同目的地必须用不同申报要素集，"
              + "不能用一套申报资料打通全球。");
    }
    String origin = normalize(input.originCountryCode()).toUpperCase(Locale.ROOT);
    if (!origin.isEmpty() && !origin.matches("[A-Z]{2}")) {
      throw badRequest(
          "产地「" + input.originCountryCode() + "」不是有效的国家代码："
              + "必须是 ISO 3166-1 alpha-2 的两位大写字母，或留空（留空会进缺失项提示）。");
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw badRequest("生效终点必须严格晚于生效起点：区间是 [effective_from, effective_to) 半开区间，"
          + "终点不晚于起点等于「从不生效」，而这样的版本一旦发布就成了永远命中不到的死行。");
    }
    String currencyProblem = ElementSetCurrency.problem(input.currency());
    if (currencyProblem != null) throw badRequest(currencyProblem);
    String brandTypeProblem = ElementSetBrandType.problem(input.brandType());
    if (brandTypeProblem != null) throw badRequest(brandTypeProblem);
    boolean preferential = Boolean.TRUE.equals(input.preferentialRateApplicable());
    String rule = normalize(input.originRuleCode());
    if (preferential && rule.isEmpty()) {
      throw badRequest("勾了主张优惠税率就必须填原产地规则代码（RCEP / CPTPP 等）："
          + "海关要凭协定代码才能适用优惠税率，没有它这项优惠直接失效 —— "
          + "而运营以为已经享惠，少缴的税到年底可能被追缴。");
    }
    if (!preferential && !rule.isEmpty()) {
      throw badRequest("没勾主张优惠税率就不该填原产地规则代码（当前填了「" + rule + "」）："
          + "这一行会同时告诉 10 报关「不能享惠」和 16 合规「有协定」，下游拿到两个互相矛盾的答案。");
    }
  }

  /**
   * 已发布区间不重叠（规则 R3 / 触发器 C5）的**服务层那一半**。
   *
   * <p><b>为什么 DB 触发器之外还要这一层</b>：触发器当然拦得住，但它抛出来的是
   * {@code DataAccessException} → HTTP 500。而「这两版区间撞了」是一个**业务冲突**，
   * 客户端需要拿到 409 与可读的文案去改区间，而不是 500 + 一个 JDBC 异常栈。
   * 两层都留着：服务层给 409，触发器挡绕过服务层的直接 SQL。
   */
  private void checkIntervalAgainstPublished(
      UUID setId, UUID skuId, String destination, Record current) {
    var clash =
        sql.fetchOne(
            "select version_no, effective_from, effective_to from customs_element_set"
                + " where sku_id=? and destination_country_code=? and status='PUBLISHED' and id<>?"
                + "   and effective_from < coalesce(?::timestamptz,'infinity'::timestamptz)"
                + "   and coalesce(effective_to,'infinity'::timestamptz) > ?::timestamptz"
                + " order by version_no limit 1",
            skuId,
            destination,
            setId,
            current.get("effective_to", OffsetDateTime.class),
            current.get("effective_from", OffsetDateTime.class));
    if (clash == null) return;
    var to = current.get("effective_to", OffsetDateTime.class);
    var clashTo = clash.get("effective_to", OffsetDateTime.class);
    throw conflict(
        "该 SKU 在目的国 " + destination + " 的已发布区间与 v" + clash.get("version_no", Integer.class)
            + " 撞了（v" + clash.get("version_no", Integer.class) + " 的区间是 ["
            + clash.get("effective_from", OffsetDateTime.class) + " ~ "
            + (clashTo == null ? "+∞" : clashTo) + "]，本版是 ["
            + current.get("effective_from", OffsetDateTime.class) + " ~ " + (to == null ? "+∞" : to) + "]）："
            + "同一 SKU 同一目的地任意两条已发布版本的区间不得重叠，"
            + "否则「此刻该用哪一版申报」没有答案，而答案是海关事后复核必查的第一件事。");
  }

  /** 规则 C1 的服务层那一半：申报品名不得照搬销售标题。 */
  private void checkNotSalesTitle(String declaredName, String salesTitle) {
    if (isBlank(declaredName)) return;
    if (isBlank(salesTitle)) return;
    if (declaredName.trim().equalsIgnoreCase(salesTitle.trim())) {
      throw badRequest(
          "申报品名「" + declaredName.trim() + "」与销售标题完全相同："
              + "申报品名是给海关归类用的清关口径，销售标题是给顾客看的转化文案，"
              + "两者用途不同，混用是常见被退单原因。请按清关口径重写"
              + "（材质、用途、结构，而不是「爆款」「高颜值」这类转化用语）。");
    }
  }

  /**
   * 产地与上游归类口径的一致性。
   *
   * <p>只在**两边都填了**时比对：上游没填（catalog 允许原产地为空的情形）时不拦，
   * 但留空会进缺失项提示（产地是九件之一）。
   */
  private void checkOriginAgainstUpstream(String origin, ElementSetUpstreamPreview upstream) {
    if (isBlank(origin) || upstream == null || isBlank(upstream.originCountryCode())) return;
    if (!origin.trim().equalsIgnoreCase(upstream.originCountryCode().trim())) {
      throw conflict(
          "产地「" + origin.trim() + "」与商品中心归类口径的产地「" + upstream.originCountryCode().trim()
              + "」不一致：产地既决定适用税率也决定能否适用自贸协定，"
              + "两边不一致时海关会按归类依据那一侧核定，而申报单上写的是另一侧 —— "
              + "这是一个事后无法解释的申报事实。请先在商品中心修正归类口径，或把这一版的产地改成一致。");
    }
  }

  // ------------------------------------------------------------ 小工具

  /**
   * 当前 RLS 租户。
   *
   * <p>由 backend/libraries/tenancy 在事务内设置，**客户端的 {@code X-Tenant-Id} 头改不动它**
   * （与 {@code WaybillListingService.currentTenant} 同一读法）。
   *
   * <p>每次现读而<b>不</b>缓存：跨请求缓存会把上一个请求的租户带进下一个请求，
   * 而那正是「连接池归还后串租户」那一类事故在应用层的形态。
   */
  private UUID currentTenantId() {
    var t =
        sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
            .get("t", UUID.class);
    if (t == null) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          "当前请求没有绑定租户上下文，拒绝写入申报要素集：租户来自已认证成员关系，不信任任何请求头。");
    }
    return t;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String blankToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim();
  }

  private static String normalizeCurrency(String s) {
    return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static OffsetDateTime judgeAt(OffsetDateTime at) {
    return at == null ? OffsetDateTime.now() : at;
  }

  private static List<Object> concat(List<Object> a, List<Object> b) {
    var out = new ArrayList<Object>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }

  private static int intOf(Record r, String key) {
    Integer v = r.get(key, Integer.class);
    return v == null ? 0 : v;
  }

  private static long requireVersion(Long version) {
    if (version == null) {
      throw badRequest("缺少乐观锁凭据 version：并发编辑时后到的那个必须撞 409，"
          + "而不是静默覆盖先到的那一份申报事实。");
    }
    return version;
  }

  private static UUID parseUuid(String raw) {
    return parseUuid(raw, "标识不是合法 UUID：" + raw);
  }

  private static UUID parseUuid(String raw, String message) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw badRequest(message);
    }
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  private static ResponseStatusException notFound(String reason) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
  }

  private static ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }
}
