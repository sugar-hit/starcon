package com.starcon.channel;

import static com.starcon.channel.LabelTemplateModels.*;
import static com.starcon.channel.LogisticsChannelModels.ChannelRestrictionItem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.LabelRenderEngine.Rendered;
import com.starcon.channel.LabelRenderEngine.Sample;
import com.starcon.channel.LabelRenderEngine.Spec;
import com.starcon.channel.LogisticsChannelModels.RestrictionVerdict;
import com.starcon.channel.LabelTemplateModels.LabelBindingResult;
import com.starcon.channel.LabelTemplateModels.LabelBindingVerdict;
import com.starcon.channel.LabelTemplateModels.LabelElement;
import com.starcon.channel.LabelTemplateModels.LabelBatteryMark;
import com.starcon.channel.LabelTemplateModels.LabelDeclaration;
import com.starcon.channel.LabelTemplateModels.LabelFinding;
import com.starcon.channel.LabelTemplateModels.LabelPreviewRequest;
import com.starcon.channel.LabelTemplateModels.LabelRenderPreview;
import com.starcon.channel.LabelTemplateModels.LabelSampleValue;
import com.starcon.channel.LabelTemplateModels.LabelSpec;
import com.starcon.channel.LabelTemplateModels.LabelVerdict;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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
 * 10.2.2.2 面单模板。
 *
 * <p>蓝图：「维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），
 * 支持按渠道与国家配置。」输出「模板 + 打印任务（经 8.3.4.2）」，并加粗
 * 「**附加声明（如电池、液体）漏打会导致清关问题**，模板配置要与渠道规则强绑定」。
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句。它的落地是三件事同时做，缺一件就退化成
 * 「把声明当文本存起来」那种假活：
 *
 * <ol>
 *   <li><b>声明必须锚定到一个真实打印位</b>（{@code elementKey}）。V141 的 trigger c
 *       从 DB 侧双向钉死：写声明时目标元素必须已存在，删元素时仍被引用的 key 不许消失。
 *       绕过服务层直接写 SQL 造一条「声明挂在虚空上」同样被拒 —— 这正是「硬绑定」与
 *       「存了没人看」的分界线。
 *   <li><b>渠道规则由服务端判定</b>（{@link #binding}）：渠道要求来自 10.1.1.2 的
 *       {@code logistics_channel_restriction}（同库只读），判定规则全部收在
 *       {@link #binding} 一个方法里。<b>不把限制字段发给前端让它自己判断</b> ——
 *       判断规则会散落到每个调用方，而总有一个调用方会漏掉「液体」这一条。
 *   <li><b>每次启用前真渲染一遍</b>（{@link #preview}）：出真的 ZPL（含真条码编码与
 *       校验位）、真落库、真记 verdict。齐备度不够的面单模板<b>不许进入 ACTIVE</b>
 *       （V141 trigger a）—— 印出去的每张面单都是真票。
 * </ol>
 *
 * <p>数据来源纪律：渠道配置<b>同库同服务</b>只读（10.1.1.2 已落在 channel，V136 建表），
 * 本服务只持有 {@code channel_id} 一个外键，<b>不复制渠道任何字段</b> —— 复制一份就会
 * 造出「同一个渠道在两处各改一次」。下游 8.3.4.2 打印在 warehouse，经授权 HTTP 端点
 * 对接，<b>本类一条 SQL 都不碰别的服务的库</b>。
 */
@Service
public class LabelTemplateService {

  private final DSLContext sql;
  private final ObjectMapper json;

  public LabelTemplateService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>（照抄 10.1.1.1 / 10.1.1.2 同款口径）。 */
  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "面单模板不存在");
  }

  // ==================================================================
  // 能力声明：未交付的段显式 available=false + ownerModule + 影响
  // ==================================================================

  public LabelTemplateCapabilities capabilities() {
    var enums = LabelSpec.enums();
    List<LabelCapabilityDimension> dimensions =
        List.of(
            new LabelCapabilityDimension(
                "label_size",
                "打印尺寸（纸张与每个元素的位置尺寸，单位 mm，NUMERIC 精确表达）",
                true,
                "本服务 V141 logistics_label_template.paper_*_mm + logistics_label_element x/y/w/h",
                "",
                "越界的打印位由 DB 触发器拒；尺寸是「印出来是什么」的根，不是备注"),
            new LabelCapabilityDimension(
                "barcode_symbology",
                "条码类型（受控枚举：Code 128 A/B · Code 39 · QR · DataMatrix）",
                true,
                "本服务 V141 primary_barcode_symbology + 元素级 symbology",
                "",
                "一维码在预览时真编码并算校验位；编不出来的值当场 BLOCK，不是印完才发现"),
            new LabelCapabilityDimension(
                "two_dimensional_encoding",
                "二维码 / DataMatrix 的**点阵生成**",
                false,
                "编码由现场打印机的 ^BQ / ^BX 完成，本模块只校验字符集与长度上限",
                "8.3.4.2",
                "本模块能判「这个值太长/含非法字符」，但不能替打印机生成点阵 —— "
                    + "现场机器才是二维码的最终权威"),
            new LabelCapabilityDimension(
                "additional_declaration",
                "🔴 附加声明（电池 / 液体 / 磁力 / 危险品），锚定到真实打印位",
                true,
                "本服务 V141 logistics_label_declaration + trigger c 双向锚定",
                "",
                "强制声明没有对应打印位 = DECLARATION_GAP，模板进不了 ACTIVE；"
                    + "漏打会导致清关问题"),
            new LabelCapabilityDimension(
                "battery_mark",
                "电池标识（电池类型 / 瓦时 / UN38.3 / 标识符号）",
                true,
                "本服务 V141 logistics_label_battery_mark + trigger e（2.7 < Wh ≤ 100 必须 UN38.3）",
                "",
                "缺 UN38.3 摘要在机场会被扣下退回；瓦时与申报口径不一致会被清关质疑"),
            new LabelCapabilityDimension(
                "language_version",
                "语言版本（受控枚举，落到 ZPL 的 ^CI28）",
                true,
                "本服务 V141 language_code + LabelRenderEngine ^CI28",
                "",
                "语言不是装饰：按 ^CI 打错的面单在部分承运商的清关系统里直接被拒收"),
            new LabelCapabilityDimension(
                "channel_binding",
                "🔴 与渠道规则强绑定（渠道 × 国家，判定在服务端）",
                true,
                "本服务同库只读 V136 logistics_channel_restriction + POST /binding 判定端点",
                "",
                "渠道要求电池声明而模板没有，当场 BLOCK；调用方拿不到限制字段就无从自己判断"),
            new LabelCapabilityDimension(
                "label_rendering",
                "🔴 模板渲染预览（真出 ZPL + 真条码 + 校验和 + 判定结论）",
                true,
                "本服务 V141 logistics_label_render + LabelRenderEngine",
                "",
                "每次预览落一条渲染历史，启用前有证据链；不是「存一条记录就算完」"),
            new LabelCapabilityDimension(
                "print_dispatch",
                "打印任务下发与打印（打印机 / 任务队列 / 重复打印 / 打印日志）",
                false,
                "下游 8.3.4.2 面单与标签打印已交付，本模块经授权 HTTP 端点对接，**不自建打印引擎**",
                "8.3.4.2",
                "本模块只到「模板与渲染产物」；真正出纸由 8.3.4.2 拥有，"
                    + "自建会出现两份「印的是什么」"),
            new LabelCapabilityDimension(
                "waybill_binding",
                "运单 ↔ 模板的自动选版（按货件属性自动挑模板）",
                false,
                "下游 10.2.1.2 运单申请已交付但只记 template_ref，自动选版未做",
                "10.2.1.2",
                "现在能算出「哪些模板能用」，但运单申请仍由人挑模板；"
                    + "自动选版要等运单侧的选版口径确定"));
    return new LabelTemplateCapabilities(
        dimensions,
        List.of("ZPL", "PDF", "HTML"),
        enums.get("symbologies"),
        List.of("WAYBILL_BARCODE", "QR_CODE"),
        enums.get("elementKinds"),
        enums.get("declarationKinds"),
        enums.get("batteryTypes"),
        enums.get("languageCodes"),
        List.of("DRAFT", "ACTIVE", "ARCHIVED"),
        List.of("NO_LAYOUT", "NO_BARCODE", "DECLARATION_GAP", "READY"),
        List.of("NO_CHANNEL", "NO_LAYOUT", "NO_BARCODE", "DECLARATION_GAP", "READY"),
        List.of("PASS", "REVIEW", "BLOCK"),
        "面单模板挂在 10.1.1.2 渠道配置之下：渠道身份（承运商 / 渠道代码 / 状态 / 适用国家）"
            + "是上游只读，本模块的写请求只提交 channelId，改渠道要回 /logistics/channels。",
        "🔴 附加声明漏打会导致清关问题 —— 声明必须锚定到真实打印位（DB 双向拒绝），"
            + "渠道要求由服务端 POST /binding 判定（规则只有一份，不发给前端自己判断），"
            + "启用前真渲染一遍，齐备度不够不许 ACTIVE。");
  }

  // ==================================================================
  // 读：真分页 + 真筛选 + 不受分页影响的真聚合
  // ==================================================================

  private static final String SELECT_COLUMNS =
      "t.id,t.channel_id,t.template_code,t.template_name,t.country_code,t.language_code,"
          + "t.status,t.paper_width_mm,t.paper_height_mm,t.dpi,t.orientation,"
          + "t.origin_offset_x_mm,t.origin_offset_y_mm,t.primary_barcode_symbology,"
          + "t.primary_barcode_height_mm,t.human_readable,t.readiness,t.note,t.version,"
          + "t.created_at,t.updated_at,"
          + "c.channel_code,c.channel_name,c.status as channel_status,c.readiness as channel_readiness,"
          + "p.party_name as carrier_name,p.party_code as carrier_code";

  private static final String FROM_CLAUSE =
      " from logistics_label_template t"
          + " join logistics_channel_config c"
          + "   on c.tenant_id = t.tenant_id and c.id = t.channel_id"
          + " join carrier_logistics_profile p"
          + "   on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id";

  /**
   * 只读渠道表的最小 FROM（供 count / summary 用）。
   *
   * <p>⚠️ JOIN 必须写在 WHERE **之前** —— 把 join 拼在 where 后面会得到
   * {@code ... where 1=1 join logistics_channel_config c on ...}，
   * PostgreSQL 报 bad SQL grammar，而症状是「列表页整个 409」。
   */
  private static final String COUNT_FROM =
      " from logistics_label_template t"
          + " join logistics_channel_config c"
          + "   on c.tenant_id = t.tenant_id and c.id = t.channel_id";

  public LabelTemplatePage templates(
      String keyword,
      String channelId,
      String countryCode,
      String status,
      String readiness,
      String symbology,
      String language,
      String sort,
      int page,
      int size) {
    // 负页码曾算出负 OFFSET 被 PostgreSQL 映射成 409，页面笔误显示成「数据冲突」——
    // 钳位到 0（照抄 10.1.1.2 的口径）。
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    // % _ \ 转义 —— 不转义的话运营搜 "100%" 会变成「匹配全部」而界面上看不出来。
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (t.template_code ilike ? or t.template_name ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
    }
    String channel = text(channelId);
    if (!channel.isEmpty()) {
      where.append(" and t.channel_id = ?");
      filters.add(UUID.fromString(channel));
    }
    String country = text(countryCode);
    if (!country.isEmpty()) {
      where.append(" and t.country_code = ?");
      filters.add(country.toUpperCase(Locale.ROOT));
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and t.status = ?");
      filters.add(st);
    }
    // readiness 筛的是**落库的本地齐备度**；NO_CHANNEL 那档在服务层合成，
    // 所以这里用 EXISTS 直接按渠道可用性筛，与列表行上的 effectiveReadiness 同源。
    String rdy = text(readiness);
    if ("NO_CHANNEL".equals(rdy)) {
      where.append(" and (c.readiness <> 'READY' or c.status <> 'ACTIVE')");
    } else if (!rdy.isEmpty()) {
      where.append(" and t.readiness = ?");
      filters.add(rdy);
    }
    String sym = text(symbology);
    if (!sym.isEmpty()) {
      where.append(" and t.primary_barcode_symbology = ?");
      filters.add(sym.toUpperCase(Locale.ROOT));
    }
    String lang = text(language);
    if (!lang.isEmpty()) {
      where.append(" and t.language_code = ?");
      filters.add(lang);
    }

    long total =
        sql.fetchOne("select count(*)" + COUNT_FROM + where, filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + SELECT_COLUMNS + FROM_CLAUSE + where
                + " order by " + orderBy(sort) + " limit ? offset ?",
            binds.toArray());

    List<LabelTemplateRow> items = new ArrayList<>();
    for (Map.Entry<UUID, LabelTemplateRow> entry : hydrate(rows).entrySet()) {
      items.add(entry.getValue());
    }
    return new LabelTemplatePage(
        items,
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        summary(where.toString(), filters));
  }

  /** 逐批取子表，批量装配。刻意<b>不做 N+1</b>：一页 50 行就是 200 次查询。 */
  private Map<UUID, LabelTemplateRow> hydrate(List<? extends Record> rows) {
    Map<UUID, LabelTemplateRow> out = new LinkedHashMap<>();
    if (rows.isEmpty()) {
      return out;
    }
    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

    Map<UUID, List<LabelElement>> elements = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id, element_key, element_kind, symbology, x_mm, y_mm, w_mm, h_mm,"
                + " font_pt, rotation, mandatory, sequence, note from logistics_label_element"
                + " where template_id in (" + placeholders + ") order by sequence, element_key",
            ids.toArray(new UUID[0]))) {
      elements
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new LabelElement(
                  r.get("element_key", String.class),
                  r.get("element_kind", String.class),
                  r.get("symbology", String.class),
                  r.get("x_mm", BigDecimal.class),
                  r.get("y_mm", BigDecimal.class),
                  r.get("w_mm", BigDecimal.class),
                  r.get("h_mm", BigDecimal.class),
                  r.get("font_pt", Integer.class),
                  r.get("rotation", Integer.class),
                  r.get("mandatory", Boolean.class),
                  r.get("sequence", Integer.class),
                  r.get("note", String.class)));
    }

    Map<UUID, List<LabelDeclaration>> declarations = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id, declaration_kind, declaration_code, display_text, element_key,"
                + " mandatory, note from logistics_label_declaration"
                + " where template_id in (" + placeholders + ") order by declaration_kind",
            ids.toArray(new UUID[0]))) {
      declarations
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new LabelDeclaration(
                  r.get("declaration_kind", String.class),
                  r.get("declaration_code", String.class),
                  r.get("display_text", String.class),
                  r.get("element_key", String.class),
                  r.get("mandatory", Boolean.class),
                  r.get("note", String.class)));
    }

    Map<UUID, List<LabelBatteryMark>> batteries = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select template_id, battery_type, watt_hours, battery_weight_kg, un38_3_attested,"
                + " marking_symbol, element_key, note from logistics_label_battery_mark"
                + " where template_id in (" + placeholders + ") order by battery_type",
            ids.toArray(new UUID[0]))) {
      batteries
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new LabelBatteryMark(
                  r.get("battery_type", String.class),
                  r.get("watt_hours", BigDecimal.class),
                  r.get("battery_weight_kg", BigDecimal.class),
                  r.get("un38_3_attested", Boolean.class),
                  r.get("marking_symbol", String.class),
                  r.get("element_key", String.class),
                  r.get("note", String.class)));
    }

    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      String stored = r.get("readiness", String.class);
      String channelReadiness = r.get("channel_readiness", String.class);
      String channelStatus = r.get("channel_status", String.class);
      String effective = effectiveReadiness(stored, channelReadiness, channelStatus);
      out.put(
          id,
          new LabelTemplateRow(
              id,
              r.get("channel_id", UUID.class),
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              r.get("carrier_name", String.class),
              r.get("carrier_code", String.class),
              channelReadiness,
              channelStatus,
              r.get("template_code", String.class),
              r.get("template_name", String.class),
              r.get("country_code", String.class),
              r.get("language_code", String.class),
              r.get("status", String.class),
              r.get("paper_width_mm", BigDecimal.class),
              r.get("paper_height_mm", BigDecimal.class),
              r.get("dpi", Integer.class),
              r.get("orientation", String.class),
              r.get("origin_offset_x_mm", BigDecimal.class),
              r.get("origin_offset_y_mm", BigDecimal.class),
              r.get("primary_barcode_symbology", String.class),
              r.get("primary_barcode_height_mm", BigDecimal.class),
              r.get("human_readable", Boolean.class),
              elements.getOrDefault(id, List.of()),
              declarations.getOrDefault(id, List.of()),
              batteries.getOrDefault(id, List.of()),
              stored,
              readinessMessage(stored),
              effective,
              readinessMessage(effective),
              "READY".equals(effective),
              r.get("note", String.class),
              r.get("version", Long.class),
              r.get("created_at", OffsetDateTime.class),
              r.get("updated_at", OffsetDateTime.class)));
    }
    return out;
  }

  /**
   * 「现在能不能用」的合成口径。
   *
   * <p>刻意<b>不在 DB 层</b>判 NO_CHANNEL：它取决于 10.1.1.2 那张表，而渠道被停用时
   * 本表一行都不会被写、触发器也就不会跑。只给一个 readiness 字段的话，页面会一直
   * 显示「齐备」，直到有人真去印一张才发现渠道早停了。
   */
  private static String effectiveReadiness(String stored, String channelReadiness, String channelStatus) {
    if (!"READY".equals(channelReadiness) || !"ACTIVE".equals(channelStatus)) {
      return "NO_CHANNEL";
    }
    return stored;
  }

  private static String readinessMessage(String readiness) {
    return switch (readiness == null ? "" : readiness) {
      case "READY" -> "本地齐备：可进入 ACTIVE";
      case "NO_LAYOUT" -> "一条打印元素都没有：印出来是一张白纸";
      case "NO_BARCODE" -> "声明了主条码类型却没有条码元素：票到了扫不出";
      case "DECLARATION_GAP" -> "🔴 强制附加声明或电池标识没有对应打印位：漏打会导致清关问题";
      case "NO_CHANNEL" -> "绑定的渠道本身不可用（未启用或配置不齐）：改了本模板也用不了";
      default -> readiness;
    };
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String orderBy(String sort) {
    return switch (text(sort)) {
      case "CODE_ASC" -> "t.template_code asc";
      case "COUNTRY_ASC" -> "t.country_code asc, t.template_code asc";
      case "UPDATED_AT_ASC" -> "t.updated_at asc";
      default -> "t.template_name asc, t.template_code asc";
    };
  }

  /** 汇总：独立查询，覆盖 where 条件下的<b>全部</b>行，与分页无关。 */
  private LabelTemplateSummary summary(String where, List<Object> filters) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where t.readiness = 'READY' and c.readiness = 'READY'"
                + "   and c.status = 'ACTIVE') as ready,"
                + " count(*) filter (where t.readiness = 'NO_LAYOUT') as no_layout,"
                + " count(*) filter (where t.readiness = 'NO_BARCODE') as no_barcode,"
                + " count(*) filter (where t.readiness = 'DECLARATION_GAP') as declaration_gap,"
                + " count(*) filter (where c.readiness <> 'READY' or c.status <> 'ACTIVE') as no_channel,"
                + " count(*) filter (where t.status = 'DRAFT') as draft,"
                + " count(*) filter (where t.status = 'ACTIVE') as active,"
                + " count(*) filter (where exists (select 1 from logistics_label_battery_mark b"
                + "   where b.tenant_id = t.tenant_id and b.template_id = t.id)) as with_battery,"
                + " count(*) filter (where exists (select 1 from logistics_label_template t2"
                + "   where t2.tenant_id = t.tenant_id and t2.channel_id = t.channel_id"
                + "     and t2.country_code = t.country_code and t2.id <> t.id)) as multi_language"
                + " from logistics_label_template t"
                + " join logistics_channel_config c"
                + "   on c.tenant_id = t.tenant_id and c.id = t.channel_id"
                + where,
            filters.toArray());
    return new LabelTemplateSummary(
        r.get("total", Long.class),
        r.get("ready", Long.class),
        r.get("no_layout", Long.class),
        r.get("no_barcode", Long.class),
        r.get("declaration_gap", Long.class),
        r.get("no_channel", Long.class),
        r.get("draft", Long.class),
        r.get("active", Long.class),
        r.get("with_battery", Long.class),
        r.get("multi_language", Long.class));
  }

  /** 概览：全量口径（不受列表筛选影响）的格式 / 条码类型 / 语言分布。 */
  public LabelTemplateOverview overview() {
    return new LabelTemplateOverview(
        summary(" where 1=1", List.of()),
        distribution("primary_barcode_symbology", List.of(
            new String[] {"NONE", "不带条码"},
            new String[] {"CODE128_B", "Code 128 B"},
            new String[] {"CODE128_A", "Code 128 A"},
            new String[] {"CODE39", "Code 39"},
            new String[] {"QR", "QR 二维码"},
            new String[] {"DATAMATRIX", "DataMatrix"})),
        distribution("language_code", List.of(
            new String[] {"en-US", "英语（美国）"},
            new String[] {"en-GB", "英语（英国）"},
            new String[] {"de-DE", "德语"},
            new String[] {"fr-FR", "法语"},
            new String[] {"es-ES", "西班牙语"},
            new String[] {"it-IT", "意大利语"},
            new String[] {"nl-NL", "荷兰语"},
            new String[] {"pt-BR", "葡萄牙语（巴西）"},
            new String[] {"ja-JP", "日语"},
            new String[] {"zh-CN", "简体中文"})),
        declarationDistribution(),
        "统计覆盖本租户全部面单模板，不随列表筛选或分页变化。");
  }

  /**
   * 附加声明分布：这一栏是蓝图加粗句「附加声明漏打会导致清关问题」在概览层面的落点 ——
   * 运营一眼看出「一张电池声明都没登记」比任何汇总数字都更值得注意。
   *
   * <p>刻意<b>只统计强制声明</b>：非强制声明印不印都不影响清关，混进来会把
   * 「齐备」的水位虚高。
   */
  private List<LabelTemplateCount> declarationDistribution() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select declaration_kind as k, count(*) as n from logistics_label_declaration"
                + " where mandatory group by declaration_kind")) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    return List.of(
        new LabelTemplateCount("BATTERY", "电池", counts.getOrDefault("BATTERY", 0L)),
        new LabelTemplateCount("LIQUID", "液体", counts.getOrDefault("LIQUID", 0L)),
        new LabelTemplateCount("MAGNET", "磁力", counts.getOrDefault("MAGNET", 0L)),
        new LabelTemplateCount("DANGEROUS_GOODS", "危险品", counts.getOrDefault("DANGEROUS_GOODS", 0L)),
        new LabelTemplateCount("BIOHAZARD", "生物危害", counts.getOrDefault("BIOHAZARD", 0L)),
        new LabelTemplateCount("RETURN_DECLARATION", "退货申报",
            counts.getOrDefault("RETURN_DECLARATION", 0L)),
        new LabelTemplateCount("OTHER", "其他", counts.getOrDefault("OTHER", 0L)));
  }

  private List<LabelTemplateCount> distribution(String column, List<String[]> options) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select " + column + " as k, count(*) as n from logistics_label_template"
                + " where " + column + " is not null group by " + column)) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    List<LabelTemplateCount> out = new ArrayList<>();
    for (String[] option : options) {
      out.add(new LabelTemplateCount(option[0], option[1], counts.getOrDefault(option[0], 0L)));
    }
    return out;
  }

  public LabelTemplateRow detail(UUID id) {
    var row = sql.fetchOne("select " + SELECT_COLUMNS + FROM_CLAUSE + " where t.id = ?", id);
    if (row == null) {
      throw notFound();
    }
    return hydrate(List.of(row)).get(id);
  }

  // ==================================================================
  // 写：面单模板
  // ==================================================================

  /**
   * 登记 / 改一个面单模板。
   *
   * <p>⚠️ 写入顺序有讲究：<b>子表先写、主表后写</b>。readiness 的触发器是在主表每一行
   * 写入时按<b>当时</b>的子表重算比对的，所以主表那次写入看到的必须是最终形态；
   * 顺序反了，触发器会在中间那次写入上拒掉一个其实正确的中间态。
   *
   * <p>子表整体替换：先清空再逐张写入。但<b>不能</b>先删元素再删声明 ——
   * trigger c 的反向分支会在删元素那一刻看到声明还引用着它并拒掉，
   * 所以先删<b>引用方</b>（电池标识 → 声明），再删被引用方（元素）。
   */
  public LabelTemplateRow save(SaveLabelTemplateRequest request) {
    UUID tenant = currentTenant();
    String code = text(request.templateCode());
    String name = text(request.templateName());
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    String language = text(request.languageCode());
    String status = text(request.status());
    String symbology = LabelRenderEngine.normalizeSymbology(request.primaryBarcodeSymbology());
    int dpi = request.dpi();
    BigDecimal width = request.paperWidthMm();
    BigDecimal height = request.paperHeightMm();

    List<LabelElement> elements = request.elements();
    List<LabelDeclaration> declarations = request.declarations();
    List<LabelBatteryMark> batteries = request.batteryMarks();

    // ---- 写入前的显式校验：把「哪个打印位撞了哪条声明」说清楚 ----
    Set<String> keys = new LinkedHashSet<>();
    for (LabelElement element : elements) {
      String key = text(element.elementKey());
      if (key.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "打印位编码不能为空");
      }
      if (!keys.add(key)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "打印位「" + key + "」重复出现：同一位置只能有一个元素");
      }
      // 🔴 **条码类元素**的条码类型必须与模板主条码类型一致：一条面单不会同时把
      //    Code128 与 QR 声明成主条码，而现场的处置方式是「随便挑一个」，
      //    最后发现打印机上少打了一种。
      //    非条码元素（地址 / 文本 / LOGO）**不参与**这个比对 —— 它们本来就没有条码，
      //    拿主条码类型去比对它们会把「这条不是条码」误判成配置冲突。
      //    反向那半（条码位必须有类型、非条码位不许有类型）由 V141 的列级 CHECK 钉死。
      boolean isBarcodeElement =
          "WAYBILL_BARCODE".equals(element.elementKind()) || "QR_CODE".equals(element.elementKind());
      if (isBarcodeElement) {
        String elementSymbology =
            "QR_CODE".equals(element.elementKind())
                ? "QR"
                : LabelRenderEngine.normalizeSymbology(element.symbology());
        if (!LabelSpec.symbologyMatches(symbology, elementSymbology)) {
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "条码打印位「" + key + "」的条码类型（"
                  + (text(elementSymbology).isEmpty() ? "无" : elementSymbology)
                  + "）与本模板的主条码类型（" + symbology + "）不一致："
                  + "一条面单不会同时把两种条码声明成主条码，现场会「随便挑一个」而少打另一种");
        }
      }
    }

    // 🔴 附加声明与电池标识必须锚定到一个真实存在的打印位（trigger c 的可读版本）
    for (LabelDeclaration declaration : declarations) {
      requireAnchor(keys, declaration.elementKey(), "附加声明「" + text(declaration.declarationKind()) + "」");
      if (text(declaration.displayText()).isEmpty() && text(declaration.declarationCode()).isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "附加声明「" + text(declaration.declarationKind())
                + "」既没有声明代码也没有印面文字：它在面单上什么都不会出现，等于没声明");
      }
    }
    for (LabelBatteryMark mark : batteries) {
      requireAnchor(keys, mark.elementKey(), "电池标识「" + text(mark.batteryType()) + "」");
    }

    UUID id = request.templateId();
    boolean creating = id == null;
    if (creating) {
      if (request.channelId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建面单模板必须指定所属渠道");
      }
      var channel =
          sql.fetchOne(
              "select id from logistics_channel_config where tenant_id=? and id=?",
              tenant,
              request.channelId());
      if (channel == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "渠道配置不存在：请先在 /logistics/channels 登记这条渠道");
      }
      id = Ids.next();
      // 新建时子表还是空的 —— 那正是正确的中间态：模板刚建出来还没排版，
      // 齐备度就是 NO_LAYOUT。
      sql.execute(
          "insert into logistics_label_template"
              + " (id,tenant_id,channel_id,template_code,template_name,country_code,language_code,"
              + "  status,paper_width_mm,paper_height_mm,dpi,orientation,"
              + "  origin_offset_x_mm,origin_offset_y_mm,primary_barcode_symbology,"
              + "  primary_barcode_height_mm,human_readable,readiness,note)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          tenant,
          request.channelId(),
          code,
          name,
          country,
          language,
          status,
          width,
          height,
          dpi,
          text(request.orientation()),
          zero(request.originOffsetXMm()),
          zero(request.originOffsetYMm()),
          symbology,
          request.primaryBarcodeHeightMm(),
          request.humanReadable(),
          // ⚠️ 新建时三张子表还是空的 —— 触发器按**当时**的子表重算，所以这里必须写
          //    NO_LAYOUT（那确实是正确的中间态），而不是按本次提交的清单算出来的 READY。
          //    写 READY 会被 trigger a 当场拒（症状是新建模板永远 409）。
          "NO_LAYOUT",
          text(request.note()));
    } else {
      var existing =
          sql.fetchOne(
              "select version, template_code, channel_id from logistics_label_template"
                  + " where tenant_id=? and id=?",
              tenant,
              id);
      if (existing == null) {
        throw notFound();
      }
      long currentVersion = existing.get("version", Long.class);
      if (request.version() != currentVersion) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "面单模板已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
      }
      if (!code.equals(existing.get("template_code", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "模板代码建后不可改（"
                + existing.get("template_code", String.class)
                + " → "
                + code
                + "）：模板代码是下游打印与单据对齐这张模板的口径，改代码会让已有单据找不到模板");
      }
      if (request.channelId() != null
          && !request.channelId().equals(existing.get("channel_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "所属渠道不可改：面单模板是渠道的合规规格，换渠道等于换一套承运商要求");
      }
    }

    // ---- 子表整体替换：先删引用方，再删被引用方（否则 trigger c 的反向分支会拒）----
    sql.execute(
        "delete from logistics_label_battery_mark where tenant_id=? and template_id=?", tenant, id);
    sql.execute(
        "delete from logistics_label_declaration where tenant_id=? and template_id=?", tenant, id);
    sql.execute("delete from logistics_label_element where tenant_id=? and template_id=?", tenant, id);

    for (LabelElement element : elements) {
      sql.execute(
          "insert into logistics_label_element (tenant_id,template_id,element_key,element_kind,"
              + " symbology,x_mm,y_mm,w_mm,h_mm,font_pt,rotation,mandatory,sequence,note)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          tenant,
          id,
          text(element.elementKey()),
          text(element.elementKind()),
          element.symbology() == null || element.symbology().isBlank()
              ? null
              : LabelRenderEngine.normalizeSymbology(element.symbology()),
          element.xMm(),
          element.yMm(),
          element.wMm(),
          element.hMm(),
          element.fontPt(),
          element.rotation() == null ? 0 : element.rotation(),
          element.mandatory(),
          element.sequence() == null ? 0 : element.sequence(),
          text(element.note()));
    }
    for (LabelDeclaration declaration : declarations) {
      sql.execute(
          "insert into logistics_label_declaration (tenant_id,template_id,declaration_kind,"
              + " declaration_code,display_text,element_key,mandatory,note) values (?,?,?,?,?,?,?,?)",
          tenant,
          id,
          text(declaration.declarationKind()),
          text(declaration.declarationCode()),
          declaration.displayText() == null ? "" : declaration.displayText(),
          text(declaration.elementKey()),
          declaration.mandatory(),
          text(declaration.note()));
    }
    for (LabelBatteryMark mark : batteries) {
      sql.execute(
          "insert into logistics_label_battery_mark (tenant_id,template_id,battery_type,watt_hours,"
              + " battery_weight_kg,un38_3_attested,marking_symbol,element_key,note)"
              + " values (?,?,?,?,?,?,?,?,?)",
          tenant,
          id,
          text(mark.batteryType()),
          mark.wattHours(),
          mark.batteryWeightKg(),
          mark.un38_3Attested(),
          text(mark.markingSymbol()),
          text(mark.elementKey()),
          text(mark.note()));
    }

    // ---- 主表最后一次写入：子表已是最终形态，触发器重算出来的就是这里提交的值 ----
    sql.execute(
        "update logistics_label_template set template_name=?, country_code=?, language_code=?,"
            + " status=?, paper_width_mm=?, paper_height_mm=?, dpi=?,"
            + " orientation=?, origin_offset_x_mm=?, origin_offset_y_mm=?,"
            + " primary_barcode_symbology=?, primary_barcode_height_mm=?, human_readable=?,"
            + " readiness=?, note=?, updated_at=now(), version=version+?"
            + " where tenant_id=? and id=?",
        name,
        country,
        language,
        status,
        width,
        height,
        dpi,
        text(request.orientation()),
        zero(request.originOffsetXMm()),
        zero(request.originOffsetYMm()),
        symbology,
        request.primaryBarcodeHeightMm(),
        request.humanReadable(),
        readiness(elements, symbology, declarations, batteries),
        text(request.note()),
        creating ? 0 : 1,
        tenant,
        id);

    return detail(id);
  }

  private static void requireAnchor(Set<String> keys, String elementKey, String what) {
    String key = text(elementKey);
    if (!keys.contains(key)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          what + "指向的打印位「" + key + "」不在本次提交的打印元素里："
              + "附加声明必须锚定到一个真实的位置，否则无法证明它真的印出来了（漏打会导致清关问题）");
    }
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * readiness 的<b>单一实现</b>（与 V141 的 trigger a 规则逐条对应，注释互相指向）。
   *
   * <p>新建时子表还是空的，所以 {@code elements} 传空列表 ⇒ 结果是 NO_LAYOUT ——
   * 这是**正确的中间态**：模板刚建出来还没排版。
   */
  private static String readiness(
      List<LabelElement> elements,
      String primarySymbology,
      List<LabelDeclaration> declarations,
      List<LabelBatteryMark> batteries) {
    if (elements == null || elements.isEmpty()) {
      return "NO_LAYOUT";
    }
    Set<String> keys = new LinkedHashSet<>();
    boolean hasBarcode = false;
    for (LabelElement element : elements) {
      keys.add(text(element.elementKey()));
      if ("WAYBILL_BARCODE".equals(element.elementKind())
          || "QR_CODE".equals(element.elementKind())) {
        hasBarcode = true;
      }
    }
    if (!"NONE".equals(primarySymbology) && !hasBarcode) {
      return "NO_BARCODE";
    }
    for (LabelDeclaration declaration : declarations) {
      if (declaration.mandatory() && !keys.contains(text(declaration.elementKey()))) {
        return "DECLARATION_GAP";
      }
    }
    for (LabelBatteryMark mark : batteries) {
      if (!keys.contains(text(mark.elementKey()))) {
        return "DECLARATION_GAP";
      }
    }
    return "READY";
  }

  /** 撤下面单模板。渲染历史随之级联删除（证据链与被撤的模板同生共死）。 */
  public void delete(UUID id) {
    UUID tenant = currentTenant();
    int rows =
        sql.execute("delete from logistics_label_template where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound();
    }
  }

  /**
   * 启用 / 停用 / 归档。
   *
   * <p>启用走一次<b>真渲染</b>（{@code trigger_source='ACTIVATION'}）：齐备度不够的模板
   * 进不了 ACTIVE（V141 trigger a），而渠道不可用的情况在这里先说清楚 ——
   * 它在 DB 层判不了（渠道被停用时本表一行都不会被写）。
   */
  public LabelTemplateRow setStatus(UUID id, String nextStatus, long version) {
    UUID tenant = currentTenant();
    String next = text(nextStatus);
    var existing =
        sql.fetchOne(
            "select version, readiness from logistics_label_template where tenant_id=? and id=?",
            tenant,
            id);
    if (existing == null) {
      throw notFound();
    }
    long currentVersion = existing.get("version", Long.class);
    if (version != currentVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "面单模板已被他人修改（当前版本 " + currentVersion + "），请重新载入后再操作");
    }
    if ("ACTIVE".equals(next)) {
      LabelTemplateRow row = detail(id);
      String effective = effectiveReadiness(row.readiness(), row.channelReadiness(), row.channelStatus());
      if (!"READY".equals(effective)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "不能启用（ACTIVE）：" + readinessMessage(effective) + "（印出去的每张面单都是真票）");
      }
      // 启用前真渲染一遍并留证：渲染历史是「这张模板被启用过」的证据链。
      persistRender(id, row, sampleFor(row), "ACTIVATION");
    }
    sql.execute(
        "update logistics_label_template set status=?, updated_at=now(), version=version+1"
            + " where tenant_id=? and id=?",
        next,
        tenant,
        id);
    return detail(id);
  }

  // ==================================================================
  // 🔴 真渲染预览
  // ==================================================================

  public LabelRenderPreview preview(UUID id, LabelPreviewRequest request) {
    LabelTemplateRow row = detail(id);
    Map<String, String> values = new LinkedHashMap<>();
    for (LabelSampleValue sample : request.elementValues()) {
      values.put(text(sample.elementKey()), sample.value() == null ? "" : sample.value());
    }
    Rendered rendered = LabelRenderEngine.render(specOf(row), new Sample(values, request.wattHours()));

    long blocks =
        rendered.findings().stream().filter(f -> "BLOCK".equals(f.severity())).count();
    long reviews =
        rendered.findings().stream().filter(f -> "REVIEW".equals(f.severity())).count();

    boolean persisted = false;
    if (request.persist()) {
      persistRender(id, row, new Sample(values, request.wattHours()), "PREVIEW");
      persisted = true;
    }
    return new LabelRenderPreview(
        row.id(),
        row.templateCode(),
        rendered.verdict(),
        LabelVerdict.message(rendered.verdict()),
        rendered.findings(),
        blocks,
        reviews,
        rendered.format(),
        rendered.symbology(),
        rendered.encodedValue(),
        rendered.modules(),
        rendered.content(),
        rendered.sha256(),
        persisted,
        "预览出的是真 ZPL 指令流（^XA…^XZ 包边，条码用 ^BC/^BQ），现场热敏机可以直接吃；"
            + "一维码的条码类型在这里真编码并算了校验位，编不出来的值当场 BLOCK。"
            + "二维码点阵由现场打印机的 ^BQ 生成，本模块只校验字符集与长度上限。");
  }

  /** 启用前的自动渲染：给每个打印位填一个形状正确的占位值，看的就是版式与锚定。 */
  private static Sample sampleFor(LabelTemplateRow row) {
    Map<String, String> values = new LinkedHashMap<>();
    for (LabelElement element : row.elements()) {
      values.put(
          element.elementKey(),
          "WAYBILL_BARCODE".equals(element.elementKind()) || "QR_CODE".equals(element.elementKind())
              ? "DE1234567890123"
              : element.elementKind());
    }
    return new Sample(values, row.batteryMarks().isEmpty() ? null : row.batteryMarks().get(0).wattHours());
  }

  private static Spec specOf(LabelTemplateRow row) {
    return new Spec(
        row.templateCode(),
        row.languageCode(),
        "ZPL",
        row.paperWidthMm(),
        row.paperHeightMm(),
        row.dpi(),
        row.orientation(),
        row.originOffsetXMm(),
        row.originOffsetYMm(),
        row.primaryBarcodeSymbology(),
        row.primaryBarcodeHeightMm(),
        row.humanReadable(),
        row.elements(),
        row.declarations(),
        row.batteryMarks());
  }

  private void persistRender(UUID templateId, LabelTemplateRow row, Sample sample, String source) {
    UUID tenant = currentTenant();
    Rendered rendered = LabelRenderEngine.render(specOf(row), sample);
    long blocks = rendered.findings().stream().filter(f -> "BLOCK".equals(f.severity())).count();
    long reviews = rendered.findings().stream().filter(f -> "REVIEW".equals(f.severity())).count();
    sql.execute(
        "insert into logistics_label_render (id,tenant_id,template_id,trigger_source,sample_payload,"
            + " symbology,encoded_value,barcode_modules,label_format,zpl_content,content_sha256,"
            + " verdict,findings,finding_count,rendered_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
        Ids.next(),
        tenant,
        templateId,
        source,
        writeJson(sample.values()),
        rendered.symbology(),
        rendered.encodedValue(),
        rendered.modules(),
        rendered.format(),
        rendered.content(),
        rendered.sha256(),
        rendered.verdict(),
        writeJson(rendered.findings()),
        blocks + reviews);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public LabelRenderPage renders(UUID id, int page, int size) {
    detail(id); // 不存在直接 404，跨租户与不存在的理由逐字相同
    int p = Math.max(page, 0);
    int s = size <= 0 ? 20 : Math.min(size, 100);
    long total =
        sql.fetchOne("select count(*) from logistics_label_render where template_id=?", id)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id, trigger_source, symbology, verdict, finding_count, content_sha256,"
                + " zpl_content, rendered_at from logistics_label_render"
                + " where template_id=? order by rendered_at desc, id desc limit ? offset ?",
            id,
            s,
            p * s);
    List<LabelRenderRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new LabelRenderRow(
              r.get("id", UUID.class),
              r.get("trigger_source", String.class),
              r.get("symbology", String.class),
              r.get("verdict", String.class),
              r.get("finding_count", Long.class),
              r.get("content_sha256", String.class),
              r.get("zpl_content", String.class),
              r.get("rendered_at", OffsetDateTime.class)));
    }
    return new LabelRenderPage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  // ==================================================================
  // 🔴 渠道规则强绑定判定
  // ==================================================================

  /**
   * 判定一批模板能不能用来打这一票货，以及缺哪个附加声明。
   *
   * <p>这是蓝图加粗句的服务端落点。规则<b>全部收在这一个方法里</b>，不散落到调用方：
   *
   * <ol>
   *   <li>渠道侧（10.1.1.2 的 {@code logistics_channel_restriction}，同库只读）：
   *       渠道禁了本票货的某个属性 ⇒ <b>BLOCK</b>，与用哪张模板无关
   *       （换模板不能让承运商收一个他拒收的货）；
   *   <li>渠道 MANUAL_REVIEW ⇒ 至少 REVIEW，<b>不允许自动放行</b>；
   *   <li>渠道要求电池 / 液体 / 磁力，而这张模板<b>没有</b>对应声明 ⇒
   *       <b>BLOCK</b> —— 这就是「模板配置要与渠道规则强绑定」的字面含义；
   *   <li>本票用不到的声明 ⇒ REVIEW（不拦，但要让人看见模板多背了一条约束）。
   * </ol>
   */
  public LabelBindingResult binding(LabelBindingRequest request) {
    UUID tenant = currentTenant();
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);

    List<Record> channelRows =
        request.channelId() == null
            ? List.of()
            : sql.fetch(
                "select channel_code, channel_name, status, readiness from logistics_channel_config"
                    + " where tenant_id=? and id=?",
                tenant,
                request.channelId());
    if (!channelRows.isEmpty()) {
      Record channel = channelRows.get(0);
      if (!"READY".equals(channel.get("readiness", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "渠道「" + channel.get("channel_code", String.class) + "」配置不齐（"
                + channel.get("readiness", String.class) + "）：先在 /logistics/channels 补齐再谈选版");
      }
    }

    // 渠道限制（10.1.1.2 只读）—— 本模块不复制这一张表的任何内容
    List<ChannelRestrictionItem> restrictions = new ArrayList<>();
    if (request.channelId() != null) {
      for (Record r :
          sql.fetch(
              "select restriction_kind, subject, restriction_mode, carrier_reason"
                  + " from logistics_channel_restriction"
                  + " where tenant_id=? and channel_id=? and restriction_mode <> 'ALLOWED'",
              tenant,
              request.channelId())) {
        restrictions.add(
            new ChannelRestrictionItem(
                r.get("restriction_kind", String.class),
                r.get("subject", String.class),
                r.get("restriction_mode", String.class),
                r.get("carrier_reason", String.class)));
      }
    }

    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where t.tenant_id=? and t.country_code=?");
    binds.add(tenant);
    binds.add(country);
    if (request.channelId() != null) {
      where.append(" and t.channel_id=?");
      binds.add(request.channelId());
    }
    if (request.templateIds() != null && !request.templateIds().isEmpty()) {
      String placeholders = String.join(",", Collections.nCopies(request.templateIds().size(), "?"));
      where.append(" and t.id in (").append(placeholders).append(")");
      binds.addAll(request.templateIds());
    }
    var rows =
        sql.fetch(
            "select " + SELECT_COLUMNS + FROM_CLAUSE + where
                + " order by t.status desc, t.template_code asc",
            binds.toArray());
    List<LabelTemplateRow> templates = new ArrayList<>(hydrate(rows).values());

    // 本票货用到的、需要声明的属性（与渠道的 restriction_kind 命名空间对齐）
    Set<String> cargoAttributes = new LinkedHashSet<>();
    if (request.containsBattery()) {
      cargoAttributes.add("BATTERY");
    }
    if (request.containsLiquid()) {
      cargoAttributes.add("LIQUID");
    }
    if (request.containsMagnet()) {
      cargoAttributes.add("MAGNET");
    }
    Set<String> categories = new LinkedHashSet<>();
    for (String category : request.categoryCodes()) {
      categories.add(text(category));
    }

    // 渠道侧先判一次（与选哪张模板无关的那部分）
    String channelVerdict = LabelVerdict.PASS;
    List<LabelFinding> channelFindings = new ArrayList<>();
    for (ChannelRestrictionItem restriction : restrictions) {
      if ("PROHIBITED_CATEGORY".equals(restriction.restrictionKind())) {
        if (categories.contains(text(restriction.subject()))) {
          channelVerdict =
              LabelVerdict.worse(channelVerdict, RestrictionVerdict.verdictForMode(restriction.restrictionMode()));
          channelFindings.add(
              new LabelFinding(
                  "BLOCK".equals(restriction.restrictionMode()) ? "BLOCK" : "REVIEW",
                  "CHANNEL_PROHIBITS_CATEGORY",
                  "渠道禁限品类「" + text(restriction.subject()) + "」（" + restriction.restrictionMode()
                      + "）：这一票根本不该走这条渠道，换模板也救不了"));
        }
        continue;
      }
      if (!cargoAttributes.contains(restriction.restrictionKind())) {
        continue;
      }
      channelVerdict =
          LabelVerdict.worse(channelVerdict, RestrictionVerdict.verdictForMode(restriction.restrictionMode()));
      channelFindings.add(
          new LabelFinding(
              "BLOCK".equals(restriction.restrictionMode()) ? "BLOCK" : "REVIEW",
              "CHANNEL_RESTRICTION",
              "渠道对本票货的「" + restriction.restrictionKind() + "」是 "
                  + restriction.restrictionMode() + "（"
                  + (text(restriction.carrierReason()).isEmpty() ? "未登记理由" : restriction.carrierReason())
                  + "）："
                  + RestrictionVerdict.message(RestrictionVerdict.verdictForMode(restriction.restrictionMode()))));
    }

    List<LabelBindingVerdict> verdicts = new ArrayList<>();
    long pass = 0;
    long review = 0;
    long block = 0;

    for (LabelTemplateRow template : templates) {
      List<LabelFinding> findings = new ArrayList<>(channelFindings);
      List<String> missingDeclarations = new ArrayList<>();
      List<String> missingBatteryMarks = new ArrayList<>();
      List<String> redundantDeclarations = new ArrayList<>();

      Set<String> declared = new LinkedHashSet<>();
      for (LabelDeclaration declaration : template.declarations()) {
        declared.add(text(declaration.declarationKind()));
      }
      Set<String> channelRequired = new LinkedHashSet<>();
      for (ChannelRestrictionItem restriction : restrictions) {
        String kind = LabelSpec.declarationKindFor(restriction.restrictionKind());
        if (kind.isEmpty()) {
          continue;
        }
        if (!cargoAttributes.contains(kind)) {
          // 本票用不到，但模板印了 —— 让现场看见模板多背了一条约束
          if (declared.contains(kind)) {
            redundantDeclarations.add(kind);
          }
          continue;
        }
        channelRequired.add(kind);
        if (!declared.contains(kind)) {
          missingDeclarations.add(kind);
          findings.add(
              new LabelFinding(
                  "BLOCK",
                  "CHANNEL_REQUIRES_DECLARATION",
                  "🔴 渠道对本票货要求「" + kind + "」声明，而这张模板没有登记它："
                      + "附加声明漏打会导致清关问题。模板配置必须与渠道规则强绑定"));
        }
      }
      // 电池声明必须配电池标识：只印一行「含电池」而没有瓦时与 UN38.3，
      // 海关要的是后者（这是清关被退回来的高频原因）。
      if (cargoAttributes.contains("BATTERY") && declared.contains("BATTERY")
          && template.batteryMarks().isEmpty()) {
        missingBatteryMarks.add("BATTERY");
        findings.add(
            new LabelFinding(
                "BLOCK",
                "BATTERY_MARK_MISSING",
                "🔴 这张模板登记了电池声明，但没有电池标识（瓦时 / UN38.3 / 标识符号）："
                    + "只印一行「含电池」过不了清关"));
      }

      if (!redundantDeclarations.isEmpty()) {
        findings.add(
            new LabelFinding(
                "REVIEW",
                "REDUNDANT_DECLARATION",
                "模板印了本票用不到的声明（" + String.join("、", redundantDeclarations)
                    + "）：不拦，但多余的声明会挤占面单版面"));
      }
      if (!"ACTIVE".equals(template.status())) {
        findings.add(
            new LabelFinding(
                "REVIEW",
                "TEMPLATE_NOT_ACTIVE",
                "这张模板当前是 " + template.status() + "：打印侧只认 ACTIVE 的模板"));
      }

      String verdict = channelVerdict;
      for (LabelFinding finding : findings) {
        verdict = LabelVerdict.worse(verdict, finding.severity());
      }
      switch (verdict) {
        case "BLOCK" -> block++;
        case "REVIEW" -> review++;
        default -> pass++;
      }
      verdicts.add(
          new LabelBindingVerdict(
              template.id(),
              template.templateCode(),
              template.templateName(),
              template.status(),
              effectiveReadiness(template.readiness(), template.channelReadiness(), template.channelStatus()),
              verdict,
              missingDeclarations,
              missingBatteryMarks,
              redundantDeclarations,
              findings));
    }

    return new LabelBindingResult(
        verdicts,
        pass,
        review,
        block,
        "判定规则只有一份：渠道要求来自 10.1.1.2 的渠道限制（同库只读，本模块不复制），"
            + "服务端一次判完，调用方拿不到限制字段就无从自己判断。"
            + "渠道禁了本票货的品类时，结论对所有模板一致 —— 换模板救不了承运商拒收。");
  }
}
