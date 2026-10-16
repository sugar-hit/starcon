package com.starcon.channel;

import static com.starcon.channel.AdTypeDiffModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 14.1.2.2 广告类型差异。
 *
 * <p>蓝图：「适配不同平台与广告类型的字段差异（自动广告、SB/SD、品牌广告、DSP、大促广告），把差异字段归一到内部
 * 模型展示。输入：各平台广告类型定义；平台 API 返回。输出：归一后的广告类型视图 + 特有字段补充展示。
 * 关系：上游 = 2.1.2.1 平台映射；下游 = 14.1.1.1。<b>归一化让跨平台广告可比</b>，但<b>必须保留原始类型标记，不能丢信息
 * </b>。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>各平台广告类型定义</b> → {@code ad_type_profile} + {@code ad_type_field_spec}（V146）。
 *       差异本身是数据，不是代码里的 {@code switch(platform, type)}。
 *   <li><b>平台 API 返回</b> → {@code ad_type_payload.raw_payload}：平台原文照抄，一个字不改。
 *   <li><b>归一后的广告类型视图</b> → {@link #comparable()}：按归一后的内部类型横向聚合。
 *   <li><b>特有字段补充展示</b> → {@link #payload(String)} 返回的 {@link ExtraFieldView}，<b>服务端组装好</b>。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「必须保留原始类型标记，不能丢信息」在这里是<b>四条并存的存储</b>，不是一句注释：
 *
 * <ol>
 *   <li>{@code platform_ad_type}（原始标记）—— 由 {@code ad_type_profile} 带下来，服务层写什么都不作数；
 *   <li>{@code internal_ad_type}（归一类型）—— 与上者同表并存，归一只��多一列，不改原列；
 *   <li>{@code raw_payload}（原始字段原文）—— 归一错了还能回到原文重来；
 *   <li>{@code normalized_field_keys} / {@code raw_only_field_keys}（保真审计）—— 后者由 DB 触发器从原文
 *       <b>现场派生</b>，服务层声称什么都不作数。
 * </ol>
 *
 * <p>🔴 与之配套，「归一口径的来源」也不由服务层说了算：{@link #resolve} 直接走 2.1.2.1 的两层
 * （租户覆盖 &gt; 平台默认 &gt; 未映射），V146 的触发器还会核对 {@code mapping_source} 与那两张表里的实际行
 * 是否对得上。归一口径的唯一真相在 2.1.2.1，本模块只消费、不复制。
 *
 * <p>🔴 数据来源纪律：平台码表、店铺、枚举映射**同库同服务**读（V1 / V3 已落在 channel），
 * 本类**不复制**它们的任何一列，也不碰 trade / listing 的表；跨服务方向一律经授权 API。
 *
 * <p>🔴 前端不做归一：{@link ExtraFieldView} 在服务端拼好，前端只渲染。原始值与归一值都在同一条里给出，
 * 两端各拼一次就会出现两个版本。
 */
@Service
public class AdTypeDiffService {

  /** 上游 2.1.2.1 的枚举族名。本模块的归一口径全部挂在这一族上。 */
  static final String AD_TYPE_ENUM_KIND = "AD_TYPE";

  /** 🔴 未映射：显式值，不是兜底。照抄 2.1.2.1 加粗句「映射缺失必须显式报未映射」。 */
  static final String UNMAPPED = "UNMAPPED";

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private final DSLContext sql;
  private final ObjectMapper json;

  public AdTypeDiffService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 RateComparisonService / CarrierService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** PostgreSQL 的 {@code text[]} 读成 {@code String[]}，空数组归一成空列表（照抄 CarrierService）。 */
  private static List<String> stringArray(Record r, String column) {
    String[] values = r.get(column, String[].class);
    return values == null ? List.of() : List.of(values);
  }

  /** jsonb 的 JDBC 形态随驱动而变，优先取 PGObject.getValue()（照抄 ChannelService.jsonText）。 */
  private static String jsonText(Object raw) {
    if (raw == null) return "{}";
    if (raw instanceof String s) return s;
    if (raw instanceof org.jooq.JSONB j) return j.data();
    try {
      Object value = raw.getClass().getMethod("getValue").invoke(raw);
      return value == null ? "{}" : value.toString();
    } catch (ReflectiveOperationException e) {
      return raw.toString();
    }
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdTypeDiffCapabilities capabilities() {
    return new AdTypeDiffCapabilities(
        List.of(
            new AdTypeCapabilityDimension(
                "type_definition",
                "各平台广告类型定义（平台 × 原始类型标记 → 归一后内部类型 + 能力位）",
                true,
                "ad_type_profile（V146）",
                "",
                "写成代码里的 switch 的话，加一个平台要发一次版，而「这个平台没有 DSP」在系统里不存在"),
            new AdTypeCapabilityDimension(
                "field_differences",
                "特有字段定义（驱动「特有字段补充展示」，含可比性与内部落点）",
                true,
                "ad_type_field_spec（V146）",
                "",
                "不建模就得在前端堆 if-else，且原始值无处可查"),
            new AdTypeCapabilityDimension(
                "platform_api_payload",
                "平台 API 返回的差异字段原文",
                true,
                "ad_type_payload.raw_payload（V146）",
                "",
                "不留原文就不可逆：归一口径改一次，之前的数据再也回不去了"),
            new AdTypeCapabilityDimension(
                "normalized_view",
                "归一后的广告类型视图（按内部类型横向聚合）",
                true,
                "ad_type_payload 的 internal_ad_type（V146）",
                "",
                "没有它就只剩一堆平台私有字段，跨平台永远不可比"),
            new AdTypeCapabilityDimension(
                "campaign_binding",
                "广告 ↔ 内部 SKU / listing 的映射（归因基础）",
                false,
                "—",
                "14.1.2.1 广告组管理",
                "归因落不到内部 SKU，利润报表会把广告花费算到错的商品上；本模块刻意不碰 listing 表"),
            new AdTypeCapabilityDimension(
                "campaign_owner",
                "14.1.1.1 活动列表与 14.1.2.1 广告组（下游引用方）",
                false,
                "—",
                "14.1.1.1 / 14.1.2.1",
                "本模块能给出归一后的广告数据，但活动侧的分组与入口要等下游上线")),
        List.of(
            "AUTO_DISCOVERY", "SPONSORED_BRAND", "SPONSORED_DISPLAY",
            "BRAND_AD", "DSP_PROGRAMMATIC", "PROMOTION_EVENT", "UNMAPPED"),
        List.of("TENANT_OVERRIDE", "PLATFORM_DEFAULT", "UNMAPPED"),
        List.of("CPC", "CPM", "CPA", "CPM_GOAL", "CPC_GOAL", "HYBRID", "UNKNOWN"),
        List.of("TRAFFIC", "SALES", "BRAND_AWARE", "BACKGROUND_REACH", "DEAL", "APP_PROMOTION", "UNKNOWN"),
        List.of("STRING", "NUMBER", "MONEY", "BOOLEAN", "DATETIME", "ENUM", "LIST"),
        List.of("NORMALIZED", "PARTIAL_FIELD_COVERAGE", "UNMAPPED_TYPE"),
        "归一后的内部类型是**跨平台新增的一列**，原始平台类型标记（platform_ad_type）与原始字段原文"
            + "（raw_payload）同表并存。归一让「Amazon 的 SD」和「TikTok 的视频带货」能落在同一个内部类型下比较，"
            + "但原始标记与原文一个字节都不会被改写 —— 归一做错了也能回到原文重来。",
        "🔴「必须保留原始类型标记，不能丢信息」有四个物理落点：原始标记列、归一类型列（并存非替换）、"
            + "原始报文原文、以及由 DB 触发器从原文派生的 raw_only_field_keys（只列出归一后没有内部落点的字段）。"
            + "最后一条服务层改不动：谎报会被触发器改写，声称归一了原文里没有的键会被直接拒绝。",
        "🔴 照抄上游 2.1.2.1 的加粗句：映射缺失必须显式报「未映射」，不落兜底。UNMAPPED 是合法值，"
            + "它对应的广告**存在且带完整原始标记**，只是 2.1.2.1 没有归一口径，所以不参与任何跨平台可比聚合。"
            + "「没有可比口径」与「没有这种广告」是相反的两件事，界面上必须分开显示。",
        "上游 2.1.2.1（platform_enum_mapping 平台默认 + platform_enum_override 租户覆盖）、"
            + "2.1.1.1 平台目录、2.3.1.1 店铺，全部在 channel 服务同库同服务读，本模块不复制它们的任何一列。");
  }

  // ==================================================================
  // 2.1.2.1 归一口径解析：租户覆盖 > 平台默认 > 未映射
  // ==================================================================

  /**
   * 走 2.1.2.1 的两层解析某个原始广告类型该归到哪个内部类型。
   *
   * <p>🔴 生效顺序照抄 2.1.2.1 原文「平台默认 + 租户覆盖」：<b>租户覆盖优先于平台默认</b>。
   * 两层都查不到 ⇒ 返回 {@code mapped=false} 且 {@code internalAdType="UNMAPPED"}，
   * 绝不落到某个兜底类型。
   */
  public MappingResolution resolve(UUID platformId, String platformAdType) {
    String external = text(platformAdType);
    if (platformId == null || external.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台与原始类型标记都不能为空");
    }

    String platformCode =
        one(String.class, "select code from platform where id = ?", platformId);
    if (platformCode == null) {
      throw notFound("平台");
    }

    // 🔴 第一层：租户覆盖（V3）。platform_enum_override 带 RLS，写入发生在已绑定租户的事务里。
    String tenantOverride =
        one(
            String.class,
            "select internal_value from platform_enum_override"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and platform_id = ? and enum_kind = ? and external_value = ?",
            platformId,
            AD_TYPE_ENUM_KIND,
            external);

    if (tenantOverride != null) {
      return new MappingResolution(
          platformId, platformCode, external, tenantOverride, true, "TENANT_OVERRIDE",
          AD_TYPE_ENUM_KIND,
          "生效顺序：租户覆盖 > 平台默认 > 未映射。租户覆盖只影响本租户，全局默认保持原样。",
          "");
    }

    // 🔴 第二层：平台默认（V1，全局只读码表，所有租户一致）。
    String platformDefault =
        one(
            String.class,
            "select internal_value from platform_enum_mapping"
                + " where platform_id = ? and enum_kind = ? and external_value = ?",
            platformId,
            AD_TYPE_ENUM_KIND,
            external);

    if (platformDefault != null) {
      return new MappingResolution(
          platformId, platformCode, external, platformDefault, true, "PLATFORM_DEFAULT",
          AD_TYPE_ENUM_KIND,
          "生效顺序：租户覆盖 > 平台默认 > 未映射。本租户未覆盖，故采用平台默认。",
          "");
    }

    // 🔴 两层都没有 ⇒ 显式未映射。不兜底。
    return new MappingResolution(
        platformId, platformCode, external, UNMAPPED, false, "UNMAPPED", AD_TYPE_ENUM_KIND,
        "生效顺序：租户覆盖 > 平台默认 > 未映射。两层都未命中，因此**不归一**。",
        "上游 2.1.2.1 的 platform_enum_override 与 platform_enum_mapping 中，"
            + "都没有 enum_kind=AD_TYPE、external_value=" + external + " 的行。"
            + "按 2.1.2.1 的加粗句，此处显式报「未映射」而不落到兜底类型 —— "
            + "悄悄归到某个近似类型，会让跨平台可比表里多出一批口径其实不同的广告。");
  }

  // ==================================================================
  // 广告类型档案
  // ==================================================================

  private static final String PROFILE_COLUMNS =
      """
       select p.id, p.platform_id, f.code as platform_code, f.name as platform_name,
              p.platform_ad_type, p.internal_ad_type, p.display_name, p.auction_model, p.objective,
              p.supports_targeting, p.supports_bid_schedule, p.supports_negative_kw,
              p.supports_creative, p.supports_ascii_ad, p.mapping_source, p.enum_kind,
              p.note, p.status, p.version, p.created_at, p.updated_at,
              (select count(*) from ad_type_field_spec s
                where s.tenant_id = p.tenant_id and s.ad_type_profile_id = p.id) as field_spec_count,
              (select count(*) from ad_type_payload d
                where d.tenant_id = p.tenant_id and d.ad_type_profile_id = p.id) as payload_count
         from ad_type_profile p join platform f on f.id = p.platform_id
      """;

  private AdTypeProfileRow profileRow(Record r) {
    return new AdTypeProfileRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("platform_ad_type", String.class),
        r.get("internal_ad_type", String.class),
        r.get("display_name", String.class),
        r.get("auction_model", String.class),
        r.get("objective", String.class),
        r.get("supports_targeting", Boolean.class),
        r.get("supports_bid_schedule", Boolean.class),
        r.get("supports_negative_kw", Boolean.class),
        r.get("supports_creative", Boolean.class),
        r.get("supports_ascii_ad", Boolean.class),
        r.get("mapping_source", String.class),
        r.get("enum_kind", String.class),
        r.get("field_spec_count", Integer.class),
        r.get("payload_count", Integer.class),
        r.get("note", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 档案列表：真分页 + 真筛选。 */
  public AdTypeProfilePage profiles(
      UUID platformId, String internalAdType, String status, String sort, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where p.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (platformId != null) {
      where.append(" and p.platform_id = ?");
      binds.add(platformId);
    }
    String wanted = blankToNull(internalAdType);
    if (wanted != null) {
      where.append(" and p.internal_ad_type = ?");
      binds.add(wanted);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and p.status = ?");
      binds.add(state);
    }

    long total =
        count("select count(*) from ad_type_profile p" + where, binds.toArray());

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    String orderBy =
        switch (sort == null ? "" : sort) {
          case "TYPE_ASC" -> " order by p.internal_ad_type asc, p.platform_ad_type asc";
          case "PLATFORM" -> " order by f.code asc, p.platform_ad_type asc";
          case "RECENT" -> " order by p.updated_at desc, p.id desc";
          default -> " order by f.code asc, p.platform_ad_type asc";
        };

    List<AdTypeProfileRow> items =
        sql.fetch(
                PROFILE_COLUMNS + where + orderBy + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::profileRow)
            .toList();

    return new AdTypeProfilePage(items, total, safePage, safeSize);
  }

  /** 档案详情：档案本体 + 特有字段定义 + **尚未被任何字段定义覆盖的原始键**。 */
  public AdTypeProfileDetail profile(String id) {
    UUID profileId = parseId(id);
    Record row =
        sql.fetchOne(PROFILE_COLUMNS + TENANT_FILTER.replace("{alias}", "p") + " and p.id = ?", profileId);
    if (row == null) {
      throw notFound("广告类型档案");
    }
    AdTypeProfileRow profile = profileRow(row);
    List<AdTypeFieldSpecRow> specs = fieldSpecs(profileId.toString());
    return new AdTypeProfileDetail(
        profile,
        specs,
        undocumentedKeys(profileId),
        "「未被字段定义覆盖的原始键」= 归一后的广告报文中出现、但这里没有任何定义的键。"
            + "它们会被保真审计列成 raw_only_field_keys 并显式告诉运营「系统不认识这个字段」——"
            + "而不是被悄悄忽略。");
  }

  /** 该档案下已归一广告的原始报文里，尚未被任何字段定义覆盖的键。 */
  private List<String> undocumentedKeys(UUID profileId) {
    return sql.fetch(
            """
            select distinct k
              from ad_type_payload d,
                   lateral jsonb_object_keys(d.raw_payload) as k
             where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
               and d.ad_type_profile_id = ?
               and not exists (select 1 from ad_type_field_spec s
                                where s.tenant_id = d.tenant_id
                                  and s.ad_type_profile_id = d.ad_type_profile_id
                                  and s.field_key = k)
             order by k
            """,
            profileId)
        .getValues("k", String.class);
  }

  // ==================================================================
  // 特有字段定义
  // ==================================================================

  private static final String FIELD_SPEC_COLUMNS =
      """
       select s.id, s.ad_type_profile_id, s.field_key, s.field_label, s.data_type, s.unit,
              s.enum_values, s.comparable, s.internal_representation, s.required_on_input,
              s.position, s.note, s.created_at, s.updated_at
         from ad_type_field_spec s
      """;

  private AdTypeFieldSpecRow fieldSpecRow(Record r) {
    return new AdTypeFieldSpecRow(
        r.get("id", UUID.class),
        r.get("ad_type_profile_id", UUID.class),
        r.get("field_key", String.class),
        r.get("field_label", String.class),
        r.get("data_type", String.class),
        r.get("unit", String.class),
        r.get("enum_values", String.class),
        r.get("comparable", Boolean.class),
        r.get("internal_representation", String.class),
        r.get("required_on_input", Boolean.class),
        r.get("position", Integer.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public List<AdTypeFieldSpecRow> fieldSpecs(String profileId) {
    UUID id = parseId(profileId);
    requireProfile(id);
    return sql.fetch(
            FIELD_SPEC_COLUMNS
                + TENANT_FILTER.replace("{alias}", "s")
                + " and s.ad_type_profile_id = ? order by s.position asc, s.field_key asc",
            id)
        .stream()
        .map(this::fieldSpecRow)
        .toList();
  }

  /** 登记 / 改一条特有字段定义。 */
  public AdTypeFieldSpecRow saveFieldSpec(String profileId, SaveFieldSpecRequest request) {
    UUID parent = parseId(profileId);
    requireProfile(parent);
    UUID tenant = currentTenant();

    String fieldKey = text(request.fieldKey());
    String representation = text(request.internalRepresentation());
    // 🔴 comparable 与内部落点必须自洽：声称可比却没有内部落点在 DB 层是 CHECK 拒，
    // 这里先给出人话错误，避免使用者只拿到一句约束名。
    if (request.comparable() && representation.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "声称参与跨平台可比的字段必须给出归一后的内部落点（internalRepresentation）");
    }

    UUID id = request.id() == null ? Ids.next() : request.id();
    sql.execute(
        """
        insert into ad_type_field_spec
          (tenant_id,id,ad_type_profile_id,field_key,field_label,data_type,unit,enum_values,
           comparable,internal_representation,required_on_input,position,note)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?)
        on conflict (tenant_id,ad_type_profile_id,field_key) do update
           set field_label=excluded.field_label, data_type=excluded.data_type, unit=excluded.unit,
               enum_values=excluded.enum_values, comparable=excluded.comparable,
               internal_representation=excluded.internal_representation,
               required_on_input=excluded.required_on_input, position=excluded.position,
               note=excluded.note, updated_at=now()
        """,
        tenant, id, parent, fieldKey, text(request.fieldLabel()), text(request.dataType()),
        text(request.unit()), text(request.enumValues()), request.comparable(), representation,
        request.requiredOnInput(), request.position(), text(request.note()));

    Record saved =
        sql.fetchOne(
            FIELD_SPEC_COLUMNS
                + " where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and s.ad_type_profile_id = ? and s.field_key = ?",
            parent,
            fieldKey);
    return fieldSpecRow(saved);
  }

  // ==================================================================
  // 登记广告类型档案
  // ==================================================================

  public AdTypeProfileRow saveProfile(SaveProfileRequest request) {
    UUID tenant = currentTenant();
    String platformAdType = text(request.platformAdType());
    String internal = text(request.internalAdType()).toUpperCase(Locale.ROOT);
    String mappingSource = text(request.mappingSource()).toUpperCase(Locale.ROOT);

    // 🔴 归一口径由 2.1.2.1 说了算，不由请求说了算。
    //    这里解析一次，声明的 internal_ad_type / mapping_source 必须与解析结果一致 ——
    //    否则「这条广告归到了哪个类型」就只是服务层的一面之词（V146 触发器还会再核一遍）。
    MappingResolution resolution = resolve(request.platformId(), platformAdType);
    if (!internal.equals(resolution.internalAdType())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "声明的归一类型（" + internal + "）与 2.1.2.1 解析结果（" + resolution.internalAdType()
              + "，来源 " + resolution.mappingSource() + "）不一致 —— 归一口径的真相在 2.1.2.1，不在请求里");
    }
    if (!mappingSource.equals(resolution.mappingSource())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "声明的归一口径来源（" + mappingSource + "）与 2.1.2.1 实际命中的层（"
              + resolution.mappingSource() + "）不一致");
    }

    UUID id = request.id() == null ? Ids.next() : request.id();
    sql.execute(
        """
        insert into ad_type_profile
          (tenant_id,id,platform_id,platform_ad_type,internal_ad_type,display_name,auction_model,
           objective,supports_targeting,supports_bid_schedule,supports_negative_kw,supports_creative,
           supports_ascii_ad,mapping_source,enum_kind,note)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        on conflict (tenant_id,platform_id,platform_ad_type) do update
           set internal_ad_type=excluded.internal_ad_type, display_name=excluded.display_name,
               auction_model=excluded.auction_model, objective=excluded.objective,
               supports_targeting=excluded.supports_targeting,
               supports_bid_schedule=excluded.supports_bid_schedule,
               supports_negative_kw=excluded.supports_negative_kw,
               supports_creative=excluded.supports_creative,
               supports_ascii_ad=excluded.supports_ascii_ad,
               mapping_source=excluded.mapping_source, note=excluded.note, version=ad_type_profile.version+1,
               updated_at=now()
        """,
        tenant, id, request.platformId(), platformAdType, internal, text(request.displayName()),
        text(request.auctionModel()), text(request.objective()), request.supportsTargeting(),
        request.supportsBidSchedule(), request.supportsNegativeKw(), request.supportsCreative(),
        request.supportsAsciiAd(), mappingSource, AD_TYPE_ENUM_KIND, text(request.note()));

    UUID stored =
        one(
            UUID.class,
            "select id from ad_type_profile"
                + " where tenant_id = ? and platform_id = ? and platform_ad_type = ?",
            tenant, request.platformId(), platformAdType);
    return profileRow(
        sql.fetchOne(PROFILE_COLUMNS + TENANT_FILTER.replace("{alias}", "p") + " and p.id = ?", stored));
  }

  private void requireProfile(UUID profileId) {
    if (one(
            Long.class,
            "select count(*) from ad_type_profile"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id = ?",
            profileId)
        == 0L) {
      throw notFound("广告类型档案");
    }
  }

  // ==================================================================
  // 归一后的广告数据
  // ==================================================================

  // 🔴 列清单与 FROM 子句**分开**（照抄 10.1.2.2 的 COMPARISON_COLUMNS / COMPARISON_FROM 同款拆法）：
  //   合在一起的话，列表页往后面追加筛选条件时就得往一个已经带 FROM 的串上再拼一次 FROM，
  //   结果是 `... join platform f on f.id = p.platform_id from ad_type_payload d` 这种坏 SQL。
  private static final String PAYLOAD_COLUMNS =
      """
       select d.id, d.ad_type_profile_id, d.platform_ad_type, d.internal_ad_type,
              f.code as platform_code, p.display_name, p.auction_model, p.objective,
              d.shop_code, d.external_ad_id, d.external_campaign_id, d.ad_name, d.ad_status,
              d.currency, d.daily_budget, d.default_bid, d.bid_floor, d.start_at, d.end_at,
              d.impressions, d.clicks, d.conversions, d.spend, d.attributed_revenue,
              d.ctr, d.cpc, d.cvr, d.acos, d.normalized_field_keys, d.raw_only_field_keys,
              d.mapping_status, d.raw_fetched_at, d.ingested_at, d.source_ref, d.note,
              d.version, d.created_at, d.updated_at
      """;

  private static final String PAYLOAD_FROM =
      """
       from ad_type_payload d
       join ad_type_profile p on p.tenant_id = d.tenant_id and p.id = d.ad_type_profile_id
       join platform f on f.id = p.platform_id
      """;

  private AdTypePayloadRow payloadRow(Record r) {
    return new AdTypePayloadRow(
        r.get("id", UUID.class),
        r.get("ad_type_profile_id", UUID.class),
        r.get("platform_ad_type", String.class),
        r.get("internal_ad_type", String.class),
        r.get("platform_code", String.class),
        r.get("display_name", String.class),
        r.get("auction_model", String.class),
        r.get("objective", String.class),
        r.get("shop_code", String.class),
        r.get("external_ad_id", String.class),
        r.get("external_campaign_id", String.class),
        r.get("ad_name", String.class),
        r.get("ad_status", String.class),
        r.get("currency", String.class),
        r.get("daily_budget", BigDecimal.class),
        r.get("default_bid", BigDecimal.class),
        r.get("bid_floor", BigDecimal.class),
        r.get("start_at", OffsetDateTime.class),
        r.get("end_at", OffsetDateTime.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("conversions", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("attributed_revenue", BigDecimal.class),
        r.get("ctr", BigDecimal.class),
        r.get("cpc", BigDecimal.class),
        r.get("cvr", BigDecimal.class),
        r.get("acos", BigDecimal.class),
        stringArray(r, "normalized_field_keys"),
        stringArray(r, "raw_only_field_keys"),
        r.get("mapping_status", String.class),
        r.get("raw_fetched_at", OffsetDateTime.class),
        r.get("ingested_at", OffsetDateTime.class),
        r.get("source_ref", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 归一后的广告数据列表：真分页 + 真筛选；汇总走独立查询不受 limit/offset 影响。 */
  public AdTypePayloadPage payloads(
      UUID platformId, String internalAdType, String mappingStatus, String shopCode,
      String sort, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    if (platformId != null) {
      where.append(" and p.platform_id = ?");
      binds.add(platformId);
    }
    String internal = blankToNull(internalAdType);
    if (internal != null) {
      where.append(" and d.internal_ad_type = ?");
      binds.add(internal);
    }
    String status = blankToNull(mappingStatus);
    if (status != null) {
      where.append(" and d.mapping_status = ?");
      binds.add(status);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and d.shop_code = ?");
      binds.add(shop);
    }

    String fromWhere = PAYLOAD_FROM + where;

    long total = count("select count(*)" + fromWhere, binds.toArray());
    long unmapped =
        count(
            "select count(*) from ad_type_payload d where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and d.mapping_status = 'UNMAPPED_TYPE'");
    long partial =
        count(
            "select count(*) from ad_type_payload d where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and d.mapping_status = 'PARTIAL_FIELD_COVERAGE'");

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    String orderBy =
        switch (sort == null ? "" : sort) {
          case "SPEND_DESC" -> " order by d.spend desc nulls last, d.id desc";
          case "INGESTED_ASC" -> " order by d.ingested_at asc, d.id asc";
          case "EXTERNAL_ID" -> " order by d.external_ad_id asc, d.id asc";
          default -> " order by d.ingested_at desc, d.id desc";
        };

    List<AdTypePayloadRow> items =
        sql.fetch(
                PAYLOAD_COLUMNS + fromWhere + orderBy + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::payloadRow)
            .toList();

    return new AdTypePayloadPage(items, total, safePage, safeSize, unmapped, partial);
  }

  /**
   * 归一后的广告数据详情：含原始报文原文 + 服务端已组装好的「特有字段补充展示」。
   *
   * <p>🔴 {@link ExtraFieldView} 在这里拼好，前端只渲染：原始值与归一值在同一条里给出，
   * 两端各拼一次就会出现两个版本。
   */
  public AdTypePayloadDetail payload(String id) {
    UUID payloadId = parseId(id);
    Record row =
        sql.fetchOne(
                PAYLOAD_COLUMNS + PAYLOAD_FROM
                    + " where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and d.id = ?",
                payloadId);
    if (row == null) {
      throw notFound("归一后的广告数据");
    }
    AdTypePayloadRow data = payloadRow(row);

    String rawText = one(String.class, "select raw_payload::text from ad_type_payload"
        + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and id = ?", payloadId);
    JsonNode raw;
    try {
      raw = json.readTree(jsonText(rawText));
    } catch (Exception e) {
      throw new IllegalStateException("原始报文解析失败", e);
    }

    UUID profileId = data.adTypeProfileId();
    List<AdTypeFieldSpecRow> specs = fieldSpecsInternal(profileId);

    // 🔴 未定义的原始键也要显式露出：系统不认识它，就得让人看见它，而不是悄悄忽略。
    Map<String, JsonNode> defined = new LinkedHashMap<>();
    for (AdTypeFieldSpecRow spec : specs) {
      defined.put(spec.fieldKey(), raw.path(spec.fieldKey()));
    }
    List<String> undocumented = new ArrayList<>();
    raw.fieldNames().forEachRemaining(key -> { if (!defined.containsKey(key)) undocumented.add(key); });

    List<ExtraFieldView> extra = new ArrayList<>();
    for (AdTypeFieldSpecRow spec : specs) {
      String representation = text(spec.internalRepresentation());
      boolean normalized = !representation.isEmpty();
      extra.add(
          new ExtraFieldView(
              spec.fieldKey(),
              spec.fieldLabel(),
              spec.dataType(),
              spec.unit(),
              textNode(raw.path(spec.fieldKey())),
              normalized,
              representation,
              normalized
                  ? "该字段已归一到内部列「" + representation + "」，可参与跨平台可比"
                  : "🔴 该字段**没有内部落点**，只原样存在于原始报文里（会出现在 raw_only_field_keys 中），"
                      + "因此不参与跨平台可比 —— 但值一个字节都没有丢"));
    }

    return new AdTypePayloadDetail(
        data,
        raw,
        extra,
        undocumented,
        "🔴 保真三件套同列：原始类型标记（platformAdType）、原始报文原文（rawPayload，一个字没改）、"
            + "只留在原始报文里的键（payload.rawOnlyFieldKeys）。归一让跨平台可比，这三样让信息不丢。");
  }

  private List<AdTypeFieldSpecRow> fieldSpecsInternal(UUID profileId) {
    return sql.fetch(
            FIELD_SPEC_COLUMNS + " where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and s.ad_type_profile_id = ? order by s.position asc, s.field_key asc",
            profileId)
        .stream()
        .map(this::fieldSpecRow)
        .toList();
  }

  /** 把一个 jsonb 节点转成展示文本：对象/数组保留原样 JSON，标量去掉引号，null 显式写成「(null)」。 */
  private static String textNode(JsonNode node) {
    if (node == null || node.isMissingNode()) return "";
    if (node.isNull()) return "(null)";
    if (node.isValueNode()) return node.asText();
    return node.toString();
  }

  /**
   * 登记一条归一后的广告数据。
   *
   * <p>🔴 {@code platform_ad_type} / {@code internal_ad_type} / {@code raw_only_field_keys} /
   * {@code mapping_status} 这四列**不在入参里** —— 它们由 DB 触发器从档案与原始报文派生。
   * 服务层声称什么都不作数：谎报保真状态会被改写，谎报已归一的键会被直接拒绝。
   */
  public SaveResponse<AdTypePayloadRow> savePayload(SavePayloadRequest request) {
    UUID tenant = currentTenant();
    UUID profileId = request.adTypeProfileId();
    requireProfile(profileId);

    JsonNode raw = request.rawPayload();
    if (raw == null || !raw.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原始报文必须是 JSON 对象（不能是数组或标量）");
    }

    String rawText;
    try {
      rawText = json.writeValueAsString(raw);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原始报文不是合法 JSON");
    }

    // 🔴 归一键去重并排序：重复键会让保真率虚高，DB 触发器会直接拒；这里先规范化。
    Set<String> keys = new LinkedHashSet<>();
    request.normalizedFieldKeys().forEach(k -> keys.add(text(k)));
    List<String> normalizedKeys = keys.stream().filter(k -> !k.isEmpty()).sorted().toList();

    UUID id = request.id() == null ? Ids.next() : request.id();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        """
        insert into ad_type_payload
          (tenant_id,id,ad_type_profile_id,shop_code,external_ad_id,external_campaign_id,ad_name,
           ad_status,currency,daily_budget,default_bid,bid_floor,start_at,end_at,
           impressions,clicks,conversions,spend,attributed_revenue,
           raw_payload,raw_fetched_at,source_ref,normalized_field_keys,ingested_at,note)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?::jsonb,?::timestamptz,?,?::text[],?::timestamptz,?)
        on conflict (tenant_id,ad_type_profile_id,external_ad_id) do update
           set shop_code=excluded.shop_code, external_campaign_id=excluded.external_campaign_id,
               ad_name=excluded.ad_name, ad_status=excluded.ad_status, currency=excluded.currency,
               daily_budget=excluded.daily_budget, default_bid=excluded.default_bid,
               bid_floor=excluded.bid_floor, start_at=excluded.start_at, end_at=excluded.end_at,
               impressions=excluded.impressions, clicks=excluded.clicks,
               conversions=excluded.conversions, spend=excluded.spend,
               attributed_revenue=excluded.attributed_revenue,
               normalized_field_keys=excluded.normalized_field_keys,
               ingested_at=excluded.ingested_at, note=excluded.note,
               version=ad_type_payload.version+1, updated_at=now()
        """,
        tenant, id, profileId, text(request.shopCode()), text(request.externalAdId()),
        text(request.externalCampaignId()), text(request.adName()), text(request.adStatus()),
        text(request.currency()), request.dailyBudget(), request.defaultBid(), request.bidFloor(),
        request.startAt(), request.endAt(), nz(request.impressions()), nz(request.clicks()),
        nz(request.conversions()), nz(request.spend()), request.attributedRevenue(),
        rawText, request.rawFetchedAt(), text(request.sourceRef()),
        normalizedKeys.toArray(new String[0]), now, text(request.note()));

    UUID stored =
        one(
            UUID.class,
            "select id from ad_type_payload"
                + " where tenant_id = ? and ad_type_profile_id = ? and external_ad_id = ?",
            tenant, profileId, text(request.externalAdId()));
    Record row =
        sql.fetchOne(
            PAYLOAD_COLUMNS + PAYLOAD_FROM
                + " where d.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and d.id = ?",
            stored);

    // 🔴 回读 DB 派生出来的保真结果给调用方，让「服务层说了不算」这件事在接口上也是可见的。
    return new SaveResponse<>(
        payloadRow(row),
        row.get("mapping_status", String.class),
        stringArray(row, "raw_only_field_keys"));
  }

  // ==================================================================
  // 归一后的广告类型视图（跨平台可比）
  // ==================================================================

  /**
   * 归一后的广告类型视图：按内部类型横向聚合，**且每个类型都带着各平台的原始类型标记**。
   *
   * <p>🔴 UNMAPPED 类型被**排除在可比汇总之外**，但在 {@code unmappedTypes} 里原样列出 ——
   * 它不是「没有这种广告」，只是「没有可比口径」。从表里直接删掉会让人以为它不存在。
   */
  public ComparableView comparable() {
    String tenantFilter = "nullif(current_setting('app.current_tenant',true),'')::uuid";

    // 🔴 可比汇总只收已归一的类型（UNMAPPED 在这里被显式排除，而不是被算成一个奇怪的类型）
    Map<String, List<Record>> grouped = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            """
            select d.internal_ad_type, d.platform_ad_type, f.id as platform_id, f.code as platform_code,
                   p.display_name, p.auction_model, p.objective,
                   d.impressions, d.clicks, d.conversions, d.spend, d.attributed_revenue
              from ad_type_payload d
              join ad_type_profile p on p.tenant_id = d.tenant_id and p.id = d.ad_type_profile_id
              join platform f on f.id = p.platform_id
             where d.tenant_id = """
                + tenantFilter
                + " and d.internal_ad_type <> 'UNMAPPED'"
                + " order by d.internal_ad_type, f.code, d.platform_ad_type")) {
      grouped.computeIfAbsent(r.get("internal_ad_type", String.class), k -> new ArrayList<>()).add(r);
    }

    List<ComparableTypeSummary> types = new ArrayList<>();
    grouped.forEach(
        (internalType, rows) -> {
          BigDecimal impressions = BigDecimal.ZERO, clicks = BigDecimal.ZERO,
              conversions = BigDecimal.ZERO, spend = BigDecimal.ZERO, revenue = BigDecimal.ZERO;
          Set<String> platforms = new LinkedHashSet<>();
          Set<String> auctions = new LinkedHashSet<>();
          Set<String> objectives = new LinkedHashSet<>();
          Map<String, long[]> perMarker = new LinkedHashMap<>();
          Map<String, UUID> markerIds = new LinkedHashMap<>();
          Map<String, String> markerOriginal = new LinkedHashMap<>();
          Map<String, String> markerPlatformCode = new LinkedHashMap<>();
          Map<String, String> markerNames = new LinkedHashMap<>();

          for (Record r : rows) {
            impressions = impressions.add(nz(r.get("impressions", BigDecimal.class)));
            clicks = clicks.add(nz(r.get("clicks", BigDecimal.class)));
            conversions = conversions.add(nz(r.get("conversions", BigDecimal.class)));
            spend = spend.add(nz(r.get("spend", BigDecimal.class)));
            BigDecimal rev = r.get("attributed_revenue", BigDecimal.class);
            if (rev != null) revenue = revenue.add(rev);
            platforms.add(r.get("platform_code", String.class));
            auctions.add(r.get("auction_model", String.class));
            objectives.add(r.get("objective", String.class));

            // 🔴 标记 = 平台 + 原始类型，两者成对出现：只留平台就把「平台上怎么叫」丢了。
            //   用拼接字符串再 split 回来太脆（平台名里出现分隔符就错），所以原始标记单独存一份。
            UUID platformId = r.get("platform_id", UUID.class);
            String code = r.get("platform_code", String.class);
            String original = r.get("platform_ad_type", String.class);
            String marker = code + " :: " + original;
            perMarker.merge(marker, new long[] {1}, (a, b) -> new long[] {a[0] + b[0]});
            markerIds.putIfAbsent(marker, platformId);
            markerOriginal.putIfAbsent(marker, original);
            markerPlatformCode.putIfAbsent(marker, code);
            markerNames.putIfAbsent(marker, r.get("display_name", String.class));
          }

          List<PlatformTypeMarker> markers = new ArrayList<>();
          perMarker.forEach(
              (marker, counts) ->
                  markers.add(
                      new PlatformTypeMarker(
                          markerIds.get(marker),
                          markerPlatformCode.get(marker),
                          markerOriginal.get(marker),
                          markerNames.get(marker),
                          counts[0])));

          types.add(
              new ComparableTypeSummary(
                  internalType,
                  rows.size(),
                  platforms.size(),
                  markers,
                  List.copyOf(auctions),
                  List.copyOf(objectives),
                  impressions,
                  clicks,
                  conversions,
                  spend,
                  revenue,
                  "归一后的内部类型让这些广告第一次能放在同一行里比。横向比较成立的前提是"
                      + "拍卖方式与投放目标也归一到同一口径 —— 所以它们一并列出："
                      + "若同一内部类型下出现多个取值，说明该平台的归一口径需要复核。"));
        });

    List<UnmappedTypeSummary> unmapped =
        sql.fetch(
                """
                select f.id as platform_id, f.code as platform_code, d.platform_ad_type,
                       p.display_name, count(*) as ad_count
                  from ad_type_payload d
                  join ad_type_profile p on p.tenant_id = d.tenant_id and p.id = d.ad_type_profile_id
                  join platform f on f.id = p.platform_id
                 where d.tenant_id = """
                    + tenantFilter
                    + " and d.internal_ad_type = 'UNMAPPED'"
                    + " group by f.id, f.code, d.platform_ad_type, p.display_name"
                    + " order by f.code, d.platform_ad_type")
            .stream()
            .map(
                r ->
                    new UnmappedTypeSummary(
                        r.get("platform_id", UUID.class),
                        r.get("platform_code", String.class),
                        r.get("platform_ad_type", String.class),
                        r.get("display_name", String.class),
                        r.get("ad_count", Long.class),
                        "上游 2.1.2.1 的 platform_enum_override 与 platform_enum_mapping 都没有 "
                            + "enum_kind=AD_TYPE、external_value=" + r.get("platform_ad_type", String.class)
                            + " 的行。按 2.1.2.1 的加粗句显式报未映射，不落兜底 —— "
                            + "这些广告存在且带完整原始标记，只是不参与横向比较。"))
            .toList();

    return new ComparableView(
        types,
        unmapped,
        "归一后的广告类型视图。横轴是归一后的内部类型，纵轴是可比的金额与数量；"
            + "🔴 每个类型旁的 markers 是它归一前的原始平台类型标记 —— 归一是为了可比，"
            + "保留标记是为了可追溯，两者缺一不可。未映射的类型单列在 unmappedTypes，不混进可比汇总。");
  }

  // ==================================================================
  // 保真概览
  // ==================================================================

  /** 「系统没有归一哪些字段」的明账：raw_only_field_keys 按出现次数排序。 */
  public FidelityOverview fidelity() {
    String tenantFilter = "nullif(current_setting('app.current_tenant',true),'')::uuid";
    long total =
        count("select count(*) from ad_type_payload where tenant_id = " + tenantFilter);
    long normalized =
        count(
            "select count(*) from ad_type_payload where tenant_id = " + tenantFilter
                + " and mapping_status = 'NORMALIZED'",
            null);
    long partial =
        count(
            "select count(*) from ad_type_payload where tenant_id = " + tenantFilter
                + " and mapping_status = 'PARTIAL_FIELD_COVERAGE'",
            null);
    long unmapped =
        count(
            "select count(*) from ad_type_payload where tenant_id = " + tenantFilter
                + " and mapping_status = 'UNMAPPED_TYPE'",
            null);

    List<RawOnlyFieldCount> rawOnly =
        sql.fetch(
                """
                select k, count(*) as occurrences
                  from ad_type_payload d, lateral unnest(d.raw_only_field_keys) as k
                 where d.tenant_id = """
                    + tenantFilter
                    + " group by k order by occurrences desc, k")
            .stream()
            .map(r -> new RawOnlyFieldCount(r.get("k", String.class), r.get("occurrences", Long.class)))
            .toList();

    return new FidelityOverview(
        total, normalized, partial, unmapped, rawOnly,
        "🔴 这是蓝图「必须保留原始类型标记，不能丢信息」的明账。rawOnlyFields 里列出的每一个字段，"
            + "都是平台报回来了、系统却没有内部落点、只能原样留在原始报文里的字段 —— "
            + "它们一个字节都没丢，但也不参与跨平台可比。清单要常被看，而不是躺在表里。");
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
  }
}
