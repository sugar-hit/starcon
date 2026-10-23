package com.starcon.channel;

import static com.starcon.channel.LinkageRiskModels.*;
import static com.starcon.channel.LinkageRiskRules.*;

import com.starcon.channel.LegalEntitySourcePort.LegalEntityRef;
import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * 16.5.2.2 关联风险。
 *
 * <p>蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。<b>关联封号是跨境多店经营的头号风险</b>：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * <p>🔴🔴 本类里有<b>七条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>节点来源是真实的 {@code shop} 表</b>（2 店铺已交付，同库）：不建影子表、不跨服务读。</li>
 *   <li><b>法人边来自真实的 {@code shop.legal_entity_id}</b>：<b>不另建法人登记面</b>
 *       —— 那会造出第二份法人真相。</li>
 *   <li><b>设备 / IP / 地址维度「不自动发现」，事实靠登记</b>：登记落 PostgreSQL、查询走 jOOQ，
 *       登记必须通过校验（店铺必须真实存在且同租户），<b>不是硬编码数组当数据源</b>。</li>
 *   <li><b>支付方式 / 收款账户维度本仓读不到</b>（12.7.1.1 未交付）⇒ 能力声明
 *       {@code available=false} + {@code ownerModule=12.7.1.1}，
 *       <b>值域里根本没有这两个维度</b>（留一个写不进去的枚举等于留一个假入口）。</li>
 *   <li><b>阈值由租户显式选</b>：没有策略 ⇒ 图谱与扫描都拒绝出结论（known=false / 422），
 *       绝不「用默认阈值替租户做决定」。</li>
 *   <li><b>「读不到」绝不折成「没有」</b>：法人名称读不到（identity 不可达）时返回
 *       {@code legalEntityNameAvailable=false} + {@code null} 名称，
 *       <b>不返回空字符串冒充「这个法人没有名字」</b>。</li>
 *   <li><b>只做官方 API 授权路径</b>：整改建议的措辞与枚举里<b>没有</b>任何
 *       「模拟登录 / 指纹浏览器 / 绕关联」的口径。</li>
 * </ol>
 */
@Service
public class LinkageRiskService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final LegalEntitySourcePort legalEntities;

  public LinkageRiskService(DSLContext sql, LegalEntitySourcePort legalEntities) {
    this.sql = sql;
    this.legalEntities = legalEntities;
  }

  // ================================================================ 内部行模型

  /** 店铺行（节点来源）。 */
  private record ShopRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      UUID legalEntityId,
      String status) {}

  /** 策略行。 */
  private record PolicyRow(
      UUID id,
      String policyName,
      String status,
      int minSharedShopCount,
      String evaluationScope,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 维度口径行。 */
  private record DimensionRow(
      UUID id,
      String dimension,
      String sensitivity,
      int minShopCount,
      String policyBasis,
      boolean enabled) {}

  /** 关联标识行。 */
  private record IdentifierRow(
      UUID id,
      String dimension,
      String identifierKey,
      String displayLabel,
      String status,
      OffsetDateTime firstObservedAt,
      OffsetDateTime lastObservedAt,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 边行（店铺 ↔ 标识）。 */
  private record EdgeRow(UUID identifierId, UUID shopId, String source, OffsetDateTime observedAt) {}

  /** 算出来的一条关联边。 */
  private record Link(
      Dimension dimension,
      String factorKey,
      String factorLabel,
      String platformCode,
      List<UUID> shopIds,
      boolean autoDiscovered,
      Sensitivity sensitivity) {}

  /** 算出来的一个关联组。 */
  private record Cluster(
      String clusterKey,
      List<UUID> shopIds,
      List<String> dimensions,
      Severity severity,
      Severity riskLevel,
      boolean crossPlatform,
      String summary) {}

  /** 图谱计算的产物。 */
  private record GraphResult(
      List<ShopRow> shops,
      Map<UUID, ShopRow> shopById,
      List<Link> links,
      List<Cluster> clusters,
      Map<UUID, List<String>> shopDimensions,
      Map<UUID, Integer> shopLinkCounts) {}

  /** 法人档案（17 identity，授权 API）。读不到时**如实报读不到**。 */
  private record LegalNames(boolean available, Map<UUID, String> byId, String note) {}

  // ================================================================ 能力声明

  /** 能力声明：逐维度 available + autoDiscoverable + ownerModule + 影响面。 */
  public LinkageRiskCapabilities capabilities() {
    List<LinkageRiskCapability> capabilities =
        List.of(
            new LinkageRiskCapability(
                "SHOP_GRAPH",
                "关联图谱节点（店铺）",
                true,
                true,
                "channel 的 shop 表（2 店铺 2.3.1.1 / 2.3.1.2，已交付）",
                "",
                "能读：图谱的**节点**就是本租户真实的店铺（代码 / 名称 / 平台 / 绑定法人），"
                    + "不需要跨服务读、也不建影子表。"
                    + "⚠️ 店铺表上有一条 RESTRICTIVE 的数据范围策略 ⇒ 读店铺必须用 "
                    + "channel.operation.read 权限码，否则**静默读到零行**。"),
            new LinkageRiskCapability(
                "LEGAL_ENTITY",
                "法人关联",
                true,
                true,
                "channel 的 shop.legal_entity_id（上游真实数据，自动读取）",
                "",
                "能判：同一法人下的多家店铺是**真实存在的关联事实**，本模块直接读它。"
                    + "🔴 **不另建法人登记面** —— 那会造出第二份法人真相。"
                    + "⚠️ 法人**名称**来自 17 identity：identity 不可达时如实标「读不到」，"
                    + "**不返回空字符串冒充「这个法人没有名字」**。"),
            new LinkageRiskCapability(
                "DEVICE",
                "设备关联",
                true,
                false,
                "本服务自持的关联事实登记面（linkage_identifier / linkage_shop_identifier）",
                "",
                "能判，但**不自动发现**：本仓 channel 侧**没有设备数据的上游表**，"
                    + "本模块也**不生成、不采集设备指纹**。"
                    + "⇒ 租户登记**已观测到**的共享事实后，本模块据此实算图谱与风险。"
                    + "🔴 登记什么就是什么，**绝不推断、绝不编造**。"),
            new LinkageRiskCapability(
                "NETWORK_IP",
                "网络 IP 关联",
                true,
                false,
                "本服务自持的关联事实登记面（linkage_identifier / linkage_shop_identifier）",
                "",
                "能判，但**不自动发现**：本仓 channel 侧没有网络出口数据表，"
                    + "identity 的登录事件也**不带来源 IP**（16.5.2.1 已实查并标注）。"
                    + "⇒ 靠登记。IP 字面量走**真实网段判定**（非法字面量直接 400），"
                    + "**不接受主机名**（那会让服务变成 DNS 客户端）。"),
            new LinkageRiskCapability(
                "ADDRESS",
                "地址关联",
                true,
                false,
                "本服务自持的关联事实登记面（linkage_identifier / linkage_shop_identifier）",
                "",
                "能判，但**不自动发现**：本仓 channel 侧没有店铺地址数据表。"
                    + "⇒ 靠登记。展示标签可以是脱敏后的（到区级），"
                    + "本模块**不复制**任何完整地址数据。"),
            new LinkageRiskCapability(
                "PAYMENT_METHOD",
                "支付方式关联",
                false,
                false,
                "12.7.1 资金账户（12.7.1.1 账户管理）",
                "12.7.1.1",
                "🔴 **本仓读不到**：12.7.1.1 在台账里是**未开发**，"
                    + "且卷 12 的主责服务 finance **不在集成头**。"
                    + "⇒ 本模块**不建它的影子数据**：两份资金账户真相必然打架。"
                    + "影响：本模块**判不出**「同一支付方式关联」这一维度，"
                    + "只能靠 12.7.1.1 交付后由它提供事实。"),
            new LinkageRiskCapability(
                "PAYOUT_ACCOUNT",
                "收款账户关联",
                false,
                false,
                "12.7.1 资金账户（12.7.1.1 账户管理）",
                "12.7.1.1",
                "🔴 **本仓读不到**（同 PAYMENT_METHOD）：收款账户维度**不在本模块的值域里**，"
                    + "接口层也**不接受**登记。影响：跨店铺共用收款账户这类关联本模块判不出。"),
            new LinkageRiskCapability(
                "LINKAGE_GRAPH",
                "关联图谱",
                true,
                true,
                "本服务实算（shop + 登记事实 + 策略）",
                "",
                "能出：节点（店铺）+ 边（共享因子）+ 关联组（**连通分量**："
                    + "A 与 B 共享设备、B 与 C 共享地址 ⇒ A/B/C 是一组）。"
                    + "🔴 图谱是**实算**的，关联组的键由成员集合**确定性**算出 ⇒ "
                    + "重扫同一批事实不会刷出一串重复的提示。"),
            new LinkageRiskCapability(
                "RISK_ALERT",
                "风险提示",
                true,
                true,
                "本服务自持（linkage_alert + linkage_alert_action）",
                "",
                "能出：严重度是**受控值域**（低 / 中 / 高 / 严重），"
                    + "由「因子敏感度 + 组内店铺数 + 是否跨平台」**实算**得出，"
                    + "阈值由租户在策略里显式选（系统不给默认值）。"
                    + "处置走状态机（待处置 → 已确认 / 已整改 / 已忽略），"
                    + "每一次处置都**留一行**（append-only）。"),
            new LinkageRiskCapability(
                "REMEDIATION",
                "整改建议",
                true,
                true,
                "本服务自持（linkage_remediation）",
                "",
                "能出：按维度生成（共享设备 ⇒ 建议分离设备；共享法人 ⇒ 建议核对法人绑定），"
                    + "并附「用官方 API 授权把店铺分开」这条通用口径。"
                    + "🔴 **没有一条**建议是「用模拟登录绕开关联」—— 那种能力本仓不提供。"),
            new LinkageRiskCapability(
                "WORKBENCH_ALERT",
                "工作台预警推送",
                false,
                false,
                "1 工作台预警（下游）",
                "1",
                "🔴 **下游未交付**（非阻塞）：本模块只产出风险提示与整改建议，"
                    + "**不代**下游推送预警。工作台那一格要等下游模块交付后再接。"));

    return new LinkageRiskCapabilities(
        capabilities,
        SHOP_SOURCE_NOTE,
        DIMENSION_NOTE,
        REGISTER_NOTE,
        GRAPH_NOTE,
        ALERT_NOTE,
        REMEDIATION_NOTE,
        OFFICIAL_API_NOTE,
        DOWNSTREAM_NOTE,
        FINANCE_NOTE);
  }

  private static final String SHOP_SOURCE_NOTE =
      "🔴 图谱的**节点**就是本租户真实的店铺（channel 的 shop 表，2 店铺 2.3.1.1 / 2.3.1.2 已交付）。"
          + "本模块**不建店铺影子表**、**不跨服务读店铺**。"
          + "🔴 法人边直接来自真实的 `shop.legal_entity_id` —— "
          + "**不另建法人登记面**（那会造出第二份法人真相）。"
          + "⚠️ 法人**名称**来自 17 identity（授权 API）：identity 不可达时如实标「读不到」，"
          + "**不返回空字符串冒充「这个法人没有名字」**。";

  private static final String DIMENSION_NOTE =
      "🔴 **阈值不给默认值**：几个店铺共享同一标识才算关联（min_shared_shop_count）、"
          + "某个维度要几家店共享才成立一条边（min_shop_count），**都必须由租户显式选**。"
          + "🔴 每个维度的**敏感度与政策依据**也是租户自己写明的（平台政策要求是本模块的输入之一），"
          + "系统**不替租户决定**哪些维度敏感。";

  private static final String REGISTER_NOTE =
      "🔴 **设备 / 网络 IP / 地址三个维度在本仓没有上游数据表** ⇒ 本模块**不自动发现**它们，"
          + "而是自己持有一个**关联事实登记面**（linkage_identifier + linkage_shop_identifier）。"
          + "租户登记**已经观测到**的共享事实，本模块据此实算图谱与风险。"
          + "🔴 这**不是 mock 数据源**：事实落 PostgreSQL、查询走 jOOQ 显式 SQL、"
          + "登记必须通过校验（店铺必须真实存在且同租户，否则触发器直接拒）。"
          + "🔴 **一个维度必须先在策略里启用并写明政策依据，才允许登记**（fail-closed，"
          + "否则会出现一批事实永远不参与判定、运营却以为「已经在管了」）。";

  private static final String GRAPH_NOTE =
      "🔴 图谱是**实算**的：节点来自 shop，边来自「共享同一因子」，"
          + "关联组是这些边连成的**连通分量**（A↔B 共享设备、B↔C 共享地址 ⇒ A/B/C 是一组，"
          + "哪怕 A 与 C 之间没有直接共享）。"
          + "🔴 关联组的键由**成员店铺集合确定性**算出（sha256）⇒ "
          + "重扫同一批事实落到同一组，风险提示被**更新**而不是刷出新的。";

  private static final String ALERT_NOTE =
      "🔴 严重度是**受控值域**（低 / 中 / 高 / 严重），由三件事**实算**："
          + "① 因子敏感度（租户在策略里写明）；② 组内店铺数（超过阈值升一档）；"
          + "③ 是否跨平台（跨平台再升一档 —— 平台封号是**按主体**判的）。"
          + "🔴 处置走状态机（待处置 → 已确认 / 已整改 / 已忽略），"
          + "每一次处置都**留一行**（append-only：GRANT 只给 SELECT,INSERT，"
          + "DB 层守卫触发器直接 RAISE —— 连迁移超级账号都改不动）。";

  private static final String REMEDIATION_NOTE =
      "🔴 整改建议**按维度生成**（共享设备 ⇒ 建议分离设备；共享地址 ⇒ 建议分离地址；"
          + "共享法人 ⇒ 建议核对法人绑定），并附「用官方 API 授权把店铺分开」这条通用口径。"
          + "🔴 **没有一条**建议指向模拟登录 / 指纹浏览器 / 绕关联 —— "
          + "那种能力本仓**不提供**，也不该提供。";

  private static final String OFFICIAL_API_NOTE =
      "🔴 蓝图加粗句「**官方 API 授权是规避关联的关键，本系统只做官方 API，"
          + "绝不提供任何模拟登录/指纹浏览器能力**」的物理落点："
          + "① 关联标识的维度值域里**没有**「浏览器指纹 / 模拟登录」这一类；"
          + "② 本模块**不生成、不采集**任何设备指纹，也没有采集设备信息的入口；"
          + "③ 整改建议的措辞固定为「**用官方 API 授权把店铺分开**」。";

  private static final String DOWNSTREAM_NOTE =
      "🔴 下游 **1 工作台预警未交付**（非阻塞）：本模块只产出风险提示与整改建议，"
          + "**不代**下游推送预警，也**不假装**已推送。";

  private static final String FINANCE_NOTE =
      "🔴 **12.7.1 资金账户（12.7.1.1 账户管理）未交付**，且卷 12 的主责服务 finance "
          + "**不在集成头** ⇒ 支付方式 / 收款账户两个维度**本仓读不到**。"
          + "本模块**不建它们的影子数据**（两份资金账户真相必然打架），"
          + "维度值域里也**没有**它们 —— 能力声明里报 available=false + ownerModule=12.7.1.1。";

  // ================================================================ 策略

  /** 关联风险策略。没登记过返回 configured=false（**不预填任何默认阈值**）。 */
  public LinkageRiskPolicyView policy() {
    PolicyRow policy = policyRow();
    if (policy == null) {
      return new LinkageRiskPolicyView(null, "", "DRAFT", 0, "TENANT", List.of(), 0, false, null, null);
    }
    return policyView(policy);
  }

  private PolicyRow policyRow() {
    Record row =
        sql.fetchOne(
            "select id,policy_name,status,min_shared_shop_count,evaluation_scope,version,"
                + "created_at,updated_at from linkage_policy");
    if (row == null) return null;
    return new PolicyRow(
        row.get("id", UUID.class),
        row.get("policy_name", String.class),
        row.get("status", String.class),
        num(row.get("min_shared_shop_count", Integer.class)),
        row.get("evaluation_scope", String.class),
        num(row.get("version", Long.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private List<DimensionRow> dimensionRows(UUID policyId) {
    return sql
        .fetch(
            "select id,dimension,sensitivity,min_shop_count,policy_basis,enabled"
                + " from linkage_policy_dimension where policy_id=? order by dimension",
            policyId)
        .map(
            row ->
                new DimensionRow(
                    row.get("id", UUID.class),
                    row.get("dimension", String.class),
                    row.get("sensitivity", String.class),
                    num(row.get("min_shop_count", Integer.class)),
                    row.get("policy_basis", String.class),
                    Boolean.TRUE.equals(row.get("enabled", Boolean.class))));
  }

  private LinkageRiskPolicyView policyView(PolicyRow policy) {
    return new LinkageRiskPolicyView(
        policy.id(),
        policy.policyName(),
        policy.status(),
        policy.minSharedShopCount(),
        policy.evaluationScope(),
        dimensionRows(policy.id()).stream().map(this::dimensionView).toList(),
        policy.version(),
        true,
        policy.createdAt(),
        policy.updatedAt());
  }

  private LinkageRiskPolicyDimensionView dimensionView(DimensionRow row) {
    return new LinkageRiskPolicyDimensionView(
        row.id(),
        row.dimension(),
        LinkageRiskRules.dimensionLabel(row.dimension()),
        row.sensitivity(),
        row.minShopCount(),
        row.policyBasis(),
        row.enabled());
  }

  /**
   * 登记 / 修改策略（upsert，一租户一条）+ 整体替换维度口径。
   *
   * <p>🔴 阈值**必须由租户显式给**（入参必填，没有默认值）。
   * 🔴 维度口径整体替换：被删掉的维度**立刻**不再参与判定（它登记过的事实保留，
   * 但不再产出关联边）—— 停用即刻生效。
   */
  public LinkageRiskPolicyView savePolicy(
      LinkageRiskPolicyInput input, AccessService.Access actor) {
    Set<String> seen = new LinkedHashSet<>();
    for (LinkageRiskPolicyDimensionInput dimension : input.dimensions()) {
      if (!seen.add(dimension.dimension())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "维度 " + dimension.dimension() + " 在策略里出现了两次");
      }
    }
    Record row =
        sql.fetchOne(
            "insert into linkage_policy(id,tenant_id,policy_name,status,min_shared_shop_count,"
                + "evaluation_scope,version,created_at,updated_at)"
                + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,0,now(),now())"
                + " on conflict (tenant_id) do update set policy_name=excluded.policy_name,"
                + " status=excluded.status,min_shared_shop_count=excluded.min_shared_shop_count,"
                + " evaluation_scope=excluded.evaluation_scope,"
                + " version=linkage_policy.version+1,updated_at=now()"
                + " returning id,policy_name,status,min_shared_shop_count,evaluation_scope,version,"
                + "created_at,updated_at",
            Ids.next(),
            input.policyName().trim(),
            input.status(),
            input.minSharedShopCount(),
            input.evaluationScope());
    if (row == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "策略保存失败");
    UUID policyId = row.get("id", UUID.class);

    // 🔴 整体替换维度口径：先删旧的，再写新的（维度口径不是留痕凭据）。
    sql.execute("delete from linkage_policy_dimension where policy_id=?", policyId);
    for (LinkageRiskPolicyDimensionInput dimension : input.dimensions()) {
      sql.execute(
          "insert into linkage_policy_dimension(id,tenant_id,policy_id,dimension,sensitivity,"
              + "min_shop_count,policy_basis,enabled,created_at,updated_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,now(),now())",
          Ids.next(),
          policyId,
          dimension.dimension(),
          dimension.sensitivity(),
          dimension.minShopCount(),
          nz(dimension.policyBasis()),
          dimension.enabled() == null || dimension.enabled());
    }
    return policyView(policyRow());
  }

  // ================================================================ 店铺（节点来源）

  /**
   * 店铺列表（真分页 + 真搜索）。
   *
   * <p>🔴 法人名称**读不到就是读不到**：identity 不可达时返回
   * {@code legalEntityNameAvailable=false} + {@code null} 名称，
   * **不返回空字符串**（那会被界面读成「这个法人没有名字」）。
   */
  public LinkageRiskShopPage shops(String bearer, String keyword, int page, int size) {
    checkPage(page, size);
    List<ShopRow> all = shopRows();
    Map<UUID, ShopRow> byId = new HashMap<>();
    for (ShopRow shop : all) byId.put(shop.id(), shop);
    Map<UUID, Integer> linkCounts = linkCounts(byId.keySet());
    Map<UUID, List<String>> dimensions = shopDimensions(byId.keySet());

    List<ShopRow> filtered = new ArrayList<>();
    for (ShopRow shop : all) {
      if (keyword == null || keyword.isBlank()) {
        filtered.add(shop);
        continue;
      }
      String needle = keyword.trim().toLowerCase(Locale.ROOT);
      if (shop.code().toLowerCase(Locale.ROOT).contains(needle)
          || shop.name().toLowerCase(Locale.ROOT).contains(needle)
          || shop.platformCode().toLowerCase(Locale.ROOT).contains(needle)) {
        filtered.add(shop);
      }
    }
    int from = Math.min(page * size, filtered.size());
    int to = Math.min(from + size, filtered.size());

    LegalNames names = legalEntityNames(bearer);
    List<LinkageRiskShopView> items = new ArrayList<>();
    for (ShopRow shop : filtered.subList(from, to)) {
      String name = null;
      if (shop.legalEntityId() != null && names.available()) {
        name = names.byId().get(shop.legalEntityId());
      }
      items.add(
          new LinkageRiskShopView(
              shop.id(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              shop.platformName(),
              shop.status(),
              shop.legalEntityId(),
              name,
              names.available(),
              linkCounts.getOrDefault(shop.id(), 0),
              dimensions.getOrDefault(shop.id(), List.of())));
    }
    return new LinkageRiskShopPage(items, page, size, filtered.size());
  }

  private LegalNames legalEntityNames(String bearer) {
    try {
      LegalEntitySourcePort.LegalEntitySet set =
          legalEntities.legalEntities(bearer == null ? "" : bearer);
      if (!set.available()) {
        return new LegalNames(false, Map.of(), set.note());
      }
      Map<UUID, String> byId = new HashMap<>();
      for (LegalEntityRef ref : set.items()) {
        if (ref.id() != null) byId.put(ref.id(), ref.legalName());
      }
      return new LegalNames(true, byId, "");
    } catch (RuntimeException e) {
      return new LegalNames(false, Map.of(), "读取法人档案失败：" + e.getMessage());
    }
  }

  // ================================================================ 关联标识登记

  /** 关联标识列表（真分页 + 真筛选）。 */
  public LinkageRiskIdentifierPage identifiers(String dimension, String status, int page, int size) {
    checkPage(page, size);
    List<IdentifierRow> all = identifierRows();
    Map<UUID, ShopRow> shopById = shopById();
    List<EdgeRow> edges = edgeRows();

    List<IdentifierRow> filtered = new ArrayList<>();
    for (IdentifierRow row : all) {
      if (dimension != null && !dimension.isBlank() && !dimension.equals(row.dimension())) continue;
      if (status != null && !status.isBlank() && !status.equals(row.status())) continue;
      filtered.add(row);
    }
    int from = Math.min(page * size, filtered.size());
    int to = Math.min(from + size, filtered.size());

    List<LinkageRiskIdentifierView> items = new ArrayList<>();
    for (IdentifierRow row : filtered.subList(from, to)) {
      items.add(identifierView(row, edges, shopById));
    }
    return new LinkageRiskIdentifierPage(items, page, size, filtered.size());
  }

  /**
   * 登记一个关联标识（共享因子）。
   *
   * <p>🔴 同一维度下同一个判定键**只登记一次**：重复登记 = 更新观测时刻（不是新建）。
   * 🔴 维度必须**已在策略里启用**，否则 DB 守卫直接拒（fail-closed）。
   */
  public LinkageRiskIdentifierView createIdentifier(
      LinkageRiskIdentifierInput input, AccessService.Access actor) {
    Dimension dimension = Dimension.valueOf(input.dimension());
    if (!LinkageRiskRules.isValidIdentifier(dimension, input.identifierKey())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          dimension == Dimension.NETWORK_IP
              ? "网络 IP 必须写成 IP 字面量或网段（如 203.0.113.7 / 203.0.113.0/24），不接受主机名"
              : "标识不能为空");
    }
    assertDimensionEnabled(dimension.name());
    String key = LinkageRiskRules.normalizeIdentifierKey(dimension, input.identifierKey());
    String label = nz(input.displayLabel()).isBlank() ? key : nz(input.displayLabel()).trim();
    OffsetDateTime observedAt =
        input.firstObservedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : input.firstObservedAt().withOffsetSameInstant(ZoneOffset.UTC);

    Record existing =
        sql.fetchOne(
            "select id from linkage_identifier where dimension=? and identifier_key=?",
            dimension.name(),
            key);
    UUID id;
    if (existing == null) {
      id = Ids.next();
      sql.execute(
          "insert into linkage_identifier(id,tenant_id,dimension,identifier_key,display_label,status,"
              + "first_observed_at,last_observed_at,note,version,created_at,updated_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,'ACTIVE',"
              + "?::timestamptz,?::timestamptz,?,0,now(),now())",
          id,
          dimension.name(),
          key,
          label,
          observedAt,
          observedAt,
          nz(input.note()));
    } else {
      id = existing.get("id", UUID.class);
      sql.execute(
          "update linkage_identifier set last_observed_at=?::timestamptz,display_label=?,"
              + "note=case when ?='' then note else ? end,version=version+1,updated_at=now()"
              + " where id=?",
          observedAt,
          label,
          nz(input.note()),
          nz(input.note()),
          id);
    }
    return identifierView(id);
  }

  /**
   * 维度必须**已在策略里启用**才允许登记（fail-closed）。
   *
   * <p>🔴 这是服务层的**第一道**检查（好报错），DB 层的守卫触发器是**第二道**（防绕过）。
   * 没有这道检查时，一批事实会永远不参与判定，而运营在界面上却以为「已经在管了」。
   */
  private void assertDimensionEnabled(String dimension) {
    Integer enabled =
        sql.fetchOne(
                "select count(*) from linkage_policy_dimension d"
                    + " join linkage_policy p on p.tenant_id=d.tenant_id and p.id=d.policy_id"
                    + " where d.dimension=? and d.enabled",
                dimension)
            .get(0, Integer.class);
    if (enabled == null || enabled < 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "维度 "
              + dimension
              + " 还没有被纳入关联风险判定（策略里没有启用它的口径）"
              + "—— 请先在策略里启用该维度并写明政策依据");
    }
  }

  /** 修改一个关联标识（标签 / 状态 / 备注）。 */
  public LinkageRiskIdentifierView updateIdentifier(
      String id, LinkageRiskIdentifierUpdateInput input, AccessService.Access actor) {
    UUID identifierId = uuid(id);
    Record row = identifierRow(identifierId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    long version = num(row.get("version", Long.class));
    if (input.version() != null && input.version() != version) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "关联标识已被别人改过，请刷新后重试");
    }
    String label =
        nz(input.displayLabel()).isBlank()
            ? row.get("display_label", String.class)
            : input.displayLabel().trim();
    sql.execute(
        "update linkage_identifier set display_label=?,status=?,note=?,version=version+1,"
            + "updated_at=now() where id=?",
        label,
        input.status(),
        nz(input.note()),
        identifierId);
    return identifierView(identifierId);
  }

  /** 删除一个关联标识（连同它的边）。 */
  public void deleteIdentifier(String id) {
    UUID identifierId = uuid(id);
    if (identifierRow(identifierId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    sql.execute("delete from linkage_shop_identifier where identifier_id=?", identifierId);
    sql.execute("delete from linkage_identifier where id=?", identifierId);
  }

  /** 把一家店铺挂到某个标识上（登记一条关联事实）。 */
  public LinkageRiskIdentifierView linkShop(
      String identifierId, LinkageRiskShopLinkInput input, AccessService.Access actor) {
    UUID id = uuid(identifierId);
    Record identifier = identifierRow(id);
    if (identifier == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    if (!"ACTIVE".equals(identifier.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "关联标识已停用，停用状态下不再登记新的共享事实");
    }
    assertDimensionEnabled(identifier.get("dimension", String.class));
    if (shopRow(input.shopId()) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在或无权查看");
    }
    OffsetDateTime observedAt =
        input.observedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : input.observedAt().withOffsetSameInstant(ZoneOffset.UTC);
    Record existing =
        sql.fetchOne(
            "select id from linkage_shop_identifier where identifier_id=? and shop_id=?",
            id,
            input.shopId());
    if (existing == null) {
      sql.execute(
          "insert into linkage_shop_identifier(id,tenant_id,identifier_id,shop_id,source,observed_at,"
              + "note,created_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?::timestamptz,?,now())",
          Ids.next(),
          id,
          input.shopId(),
          input.source(),
          observedAt,
          nz(input.note()));
    } else {
      sql.execute(
          "update linkage_shop_identifier set source=?,observed_at=?::timestamptz,"
              + "note=case when ?='' then note else ? end where id=?",
          input.source(),
          observedAt,
          nz(input.note()),
          nz(input.note()),
          existing.get("id", UUID.class));
    }
    sql.execute(
        "update linkage_identifier set last_observed_at=?::timestamptz,version=version+1,"
            + "updated_at=now() where id=?",
        observedAt,
        id);
    return identifierView(id);
  }

  /** 解除一条共享事实。 */
  public void unlinkShop(String identifierId, String shopId) {
    UUID id = uuid(identifierId);
    UUID shop = uuid(shopId);
    if (identifierRow(id) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    sql.execute("delete from linkage_shop_identifier where identifier_id=? and shop_id=?", id, shop);
  }

  private Record identifierRow(UUID id) {
    return sql.fetchOne(
        "select id,dimension,identifier_key,display_label,status,first_observed_at,"
            + "last_observed_at,note,version,created_at,updated_at from linkage_identifier where id=?",
        id);
  }

  private LinkageRiskIdentifierView identifierView(UUID id) {
    Record row = identifierRow(id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    return identifierView(toIdentifierRow(row), edgeRows(), shopById());
  }

  private static IdentifierRow toIdentifierRow(Record row) {
    return new IdentifierRow(
        row.get("id", UUID.class),
        row.get("dimension", String.class),
        row.get("identifier_key", String.class),
        row.get("display_label", String.class),
        row.get("status", String.class),
        row.get("first_observed_at", OffsetDateTime.class),
        row.get("last_observed_at", OffsetDateTime.class),
        row.get("note", String.class),
        num(row.get("version", Long.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private LinkageRiskIdentifierView identifierView(
      IdentifierRow row, List<EdgeRow> edges, Map<UUID, ShopRow> shopById) {
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联标识不存在或无权查看");
    }
    UUID identifierId = row.id();
    List<LinkageRiskIdentifierShopView> shops = new ArrayList<>();
    for (EdgeRow edge : edges) {
      if (!edge.identifierId().equals(identifierId)) continue;
      ShopRow shop = shopById.get(edge.shopId());
      if (shop == null) continue;
      shops.add(
          new LinkageRiskIdentifierShopView(shop.id(), shop.code(), shop.name(), shop.platformCode()));
    }
    String dimension = row.dimension();
    return new LinkageRiskIdentifierView(
        identifierId,
        dimension,
        LinkageRiskRules.dimensionLabel(dimension),
        row.identifierKey(),
        row.displayLabel(),
        row.status(),
        shops.size(),
        shops,
        row.note(),
        row.version(),
        row.firstObservedAt(),
        row.lastObservedAt(),
        row.createdAt(),
        row.updatedAt());
  }

  private List<IdentifierRow> identifierRows() {
    return sql
        .fetch(
            "select id,dimension,identifier_key,display_label,status,first_observed_at,"
                + "last_observed_at,note,version,created_at,updated_at from linkage_identifier"
                + " order by dimension,identifier_key")
        .map(LinkageRiskService::toIdentifierRow);
  }

  private List<EdgeRow> edgeRows() {
    return sql
        .fetch(
            "select identifier_id,shop_id,source,observed_at from linkage_shop_identifier"
                + " order by identifier_id,shop_id")
        .map(
            row ->
                new EdgeRow(
                    row.get("identifier_id", UUID.class),
                    row.get("shop_id", UUID.class),
                    row.get("source", String.class),
                    row.get("observed_at", OffsetDateTime.class)));
  }

  // ================================================================ 图谱

  /**
   * 关联图谱（**实算**：节点来自 shop，边来自共享因子，关联组来自连通分量）。
   *
   * <p>🔴 没有策略 ⇒ {@code known=false} + 说明，**不是**「算过了，没有关联」。
   */
  public LinkageRiskGraph graph(String bearer) {
    PolicyRow policy = policyRow();
    if (policy == null) {
      return new LinkageRiskGraph(
          false,
          "还没登记关联风险策略 —— 关联图谱尚未启用。"
              + "🔴 **这不等于「没有关联」**：没有阈值与维度口径就没有判定依据，"
              + "请先在策略里显式选择阈值并写明各维度的政策依据。",
          0,
          0,
          0,
          List.of(),
          List.of(),
          List.of());
    }
    LegalNames names = legalEntityNames(bearer);
    GraphResult graph = computeGraph(policy, names);
    LinkageRiskGraph view = graphView(graph);
    String note = view.note();
    if (!names.available()) {
      note =
          (note.isBlank() ? "" : note + " ")
              + "🔴 **法人名称读不到**（identity 不可达："
              + names.note()
              + "）—— 图谱里的法人因子只显示 id，**不显示成空名字**。";
    }
    return new LinkageRiskGraph(
        true,
        note,
        view.shopCount(),
        view.linkCount(),
        view.clusterCount(),
        view.nodes(),
        view.links(),
        view.clusters());
  }

  private LinkageRiskGraph graphView(GraphResult graph) {
    List<LinkageRiskGraphNode> nodes = new ArrayList<>();
    for (ShopRow shop : graph.shops()) {
      int count = graph.shopLinkCounts().getOrDefault(shop.id(), 0);
      if (count == 0) continue;
      nodes.add(
          new LinkageRiskGraphNode(
              shop.id(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              count,
              graph.shopDimensions().getOrDefault(shop.id(), List.of())));
    }
    List<LinkageRiskGraphLink> links = new ArrayList<>();
    for (Link link : graph.links()) {
      links.add(
          new LinkageRiskGraphLink(
              link.dimension().name(),
              link.dimension().label(),
              link.factorKey(),
              link.factorLabel(),
              link.platformCode(),
              link.shopIds().size(),
              link.shopIds(),
              link.autoDiscovered(),
              link.sensitivity().name()));
    }
    List<LinkageRiskGraphCluster> clusters = new ArrayList<>();
    for (Cluster cluster : graph.clusters()) {
      clusters.add(
          new LinkageRiskGraphCluster(
              cluster.clusterKey(),
              cluster.shopIds(),
              cluster.shopIds().size(),
              cluster.dimensions(),
              cluster.severity().name(),
              cluster.severity().label(),
              cluster.riskLevel().name(),
              cluster.riskLevel().label(),
              cluster.crossPlatform(),
              cluster.summary()));
    }
    return new LinkageRiskGraph(
        true,
        "",
        graph.shops().size(),
        graph.links().size(),
        graph.clusters().size(),
        nodes,
        links,
        clusters);
  }

  /**
   * 算图谱：节点（店铺）+ 边（共享因子）+ 关联组（连通分量）。
   *
   * <p>🔴 <b>判定范围</b>：{@code PLATFORM} 时同一条边**按平台拆开**（跨平台不算关联）；
   * {@code TENANT} 时跨平台也算（平台封号是**按主体**判的）。
   */
  private GraphResult computeGraph(PolicyRow policy, LegalNames names) {
    List<ShopRow> shops = shopRows();
    Map<UUID, ShopRow> shopById = new HashMap<>();
    for (ShopRow shop : shops) shopById.put(shop.id(), shop);

    Map<String, DimensionRow> dimensions = new HashMap<>();
    for (DimensionRow row : dimensionRows(policy.id())) {
      if (row.enabled()) dimensions.put(row.dimension(), row);
    }
    boolean platformScope = "PLATFORM".equals(policy.evaluationScope());

    List<Link> links = new ArrayList<>();

    // ---- 法人维度：事实来自真实的 shop.legal_entity_id（自动发现）----
    DimensionRow legalEntityPolicy = dimensions.get(Dimension.LEGAL_ENTITY.name());
    if (legalEntityPolicy != null) {
      Map<String, List<ShopRow>> byLegalEntity = new LinkedHashMap<>();
      for (ShopRow shop : shops) {
        if (shop.legalEntityId() == null) continue;
        byLegalEntity.computeIfAbsent(shop.legalEntityId().toString(), k -> new ArrayList<>()).add(shop);
      }
      for (var entry : byLegalEntity.entrySet()) {
        List<ShopRow> group = entry.getValue();
        if (!LinkageRiskRules.factorQualifies(group.size(), legalEntityPolicy.minShopCount())) continue;
        String label = legalEntityLabel(entry.getKey(), names);
        for (List<ShopRow> part : scopeParts(group, platformScope)) {
          // 🔴 按平台拆开之后要**重新**判阈值：拆出来只有 1 家店的那一段**不成立**一条边
          if (!LinkageRiskRules.factorQualifies(part.size(), legalEntityPolicy.minShopCount())) {
            continue;
          }
          links.add(
              new Link(
                  Dimension.LEGAL_ENTITY,
                  entry.getKey(),
                  label,
                  part.get(0).platformCode(),
                  part.stream().map(ShopRow::id).toList(),
                  true,
                  Sensitivity.valueOf(legalEntityPolicy.sensitivity())));
        }
      }
    }

    // ---- 登记维度：事实来自租户登记的观测（不自动发现）----
    Map<UUID, List<EdgeRow>> edgesByIdentifier = new HashMap<>();
    for (EdgeRow edge : edgeRows()) {
      edgesByIdentifier.computeIfAbsent(edge.identifierId(), k -> new ArrayList<>()).add(edge);
    }
    for (IdentifierRow identifier : identifierRows()) {
      if (!"ACTIVE".equals(identifier.status())) continue;
      DimensionRow dimensionPolicy = dimensions.get(identifier.dimension());
      if (dimensionPolicy == null) continue;
      List<ShopRow> group = new ArrayList<>();
      for (EdgeRow edge : edgesByIdentifier.getOrDefault(identifier.id(), List.of())) {
        ShopRow shop = shopById.get(edge.shopId());
        if (shop != null) group.add(shop);
      }
      if (!LinkageRiskRules.factorQualifies(group.size(), dimensionPolicy.minShopCount())) continue;
      for (List<ShopRow> part : scopeParts(group, platformScope)) {
        // 🔴 按平台拆开之后要**重新**判阈值
        if (!LinkageRiskRules.factorQualifies(part.size(), dimensionPolicy.minShopCount())) {
          continue;
        }
        links.add(
            new Link(
                Dimension.valueOf(identifier.dimension()),
                identifier.identifierKey(),
                identifier.displayLabel(),
                part.get(0).platformCode(),
                part.stream().map(ShopRow::id).toList(),
                false,
                Sensitivity.valueOf(dimensionPolicy.sensitivity())));
      }
    }

    // ---- 关联组：连通分量 ----
    Map<UUID, Set<UUID>> adjacency = new HashMap<>();
    for (Link link : links) {
      List<UUID> ids = link.shopIds();
      for (int i = 0; i < ids.size(); i++) {
        for (int j = i + 1; j < ids.size(); j++) {
          adjacency.computeIfAbsent(ids.get(i), k -> new LinkedHashSet<>()).add(ids.get(j));
          adjacency.computeIfAbsent(ids.get(j), k -> new LinkedHashSet<>()).add(ids.get(i));
        }
      }
    }
    Set<UUID> visited = new HashSet<>();
    List<Cluster> clusters = new ArrayList<>();
    List<UUID> nodeIds = new ArrayList<>(adjacency.keySet());
    nodeIds.sort(Comparator.naturalOrder());
    for (UUID seed : nodeIds) {
      if (visited.contains(seed)) continue;
      List<UUID> members = new ArrayList<>();
      List<UUID> stack = new ArrayList<>();
      stack.add(seed);
      visited.add(seed);
      while (!stack.isEmpty()) {
        UUID current = stack.remove(stack.size() - 1);
        members.add(current);
        for (UUID next : adjacency.getOrDefault(current, Set.of())) {
          if (visited.add(next)) stack.add(next);
        }
      }
      members.sort(Comparator.naturalOrder());
      if (members.size() < 2) continue;

      List<Link> memberLinks = new ArrayList<>();
      for (Link link : links) {
        boolean allInside = true;
        for (UUID id : link.shopIds()) {
          if (!members.contains(id)) {
            allInside = false;
            break;
          }
        }
        if (allInside) memberLinks.add(link);
      }
      if (memberLinks.isEmpty()) continue;

      Sensitivity maxSensitivity = Sensitivity.LOW;
      Set<String> dimensionNames = new LinkedHashSet<>();
      for (Link link : memberLinks) {
        dimensionNames.add(link.dimension().name());
        if (link.sensitivity().weight() > maxSensitivity.weight()) maxSensitivity = link.sensitivity();
      }
      Set<String> platforms = new LinkedHashSet<>();
      for (UUID id : members) {
        ShopRow shop = shopById.get(id);
        if (shop != null) platforms.add(shop.platformCode());
      }
      boolean crossPlatform = platforms.size() > 1;
      Severity severity =
          LinkageRiskRules.clusterSeverity(maxSensitivity, members.size(), policy.minSharedShopCount());
      Severity riskLevel = LinkageRiskRules.clusterRiskLevel(severity, crossPlatform);
      String key = LinkageRiskRules.clusterKey(members);
      String summary = clusterSummary(members, shopById, memberLinks);
      clusters.add(
          new Cluster(
              key,
              members,
              new ArrayList<>(dimensionNames),
              severity,
              riskLevel,
              crossPlatform,
              summary));
    }

    Map<UUID, List<String>> shopDimensions = new HashMap<>();
    Map<UUID, Integer> shopLinkCounts = new HashMap<>();
    for (Link link : links) {
      for (UUID id : link.shopIds()) {
        shopLinkCounts.merge(id, 1, Integer::sum);
        List<String> list = shopDimensions.computeIfAbsent(id, k -> new ArrayList<>());
        if (!list.contains(link.dimension().name())) list.add(link.dimension().name());
      }
    }
    return new GraphResult(shops, shopById, links, clusters, shopDimensions, shopLinkCounts);
  }

  /** 按判定范围把一组店铺拆成「同平台」的小组。 */
  private List<List<ShopRow>> scopeParts(List<ShopRow> group, boolean platformScope) {
    if (!platformScope) return List.of(group);
    Map<String, List<ShopRow>> byPlatform = new LinkedHashMap<>();
    for (ShopRow shop : group) {
      byPlatform.computeIfAbsent(shop.platformCode(), k -> new ArrayList<>()).add(shop);
    }
    return new ArrayList<>(byPlatform.values());
  }

  /** 法人标签：读不到 / 档案里没有 / 有名字 —— 三种情况文案各不相同。 */
  private String legalEntityLabel(String legalEntityId, LegalNames names) {
    if (!names.available()) {
      return "法人名称读不到（identity 不可达）";
    }
    String name = names.byId().get(UUID.fromString(legalEntityId));
    if (name == null || name.isBlank()) {
      return "法人档案里没有这一条法人（可能已停用）";
    }
    return name;
  }

  private String clusterSummary(
      List<UUID> members, Map<UUID, ShopRow> shopById, List<Link> memberLinks) {
    StringBuilder out = new StringBuilder();
    out.append(members.size()).append(" 家店铺连成一组：");
    List<String> shopNames = new ArrayList<>();
    for (UUID id : members) {
      ShopRow shop = shopById.get(id);
      shopNames.add(shop == null ? id.toString() : shop.code() + "（" + shop.name() + "）");
    }
    out.append(String.join("、", shopNames));
    out.append("。关联依据：");
    List<String> factors = new ArrayList<>();
    for (Link link : memberLinks) {
      factors.add(link.dimension().label() + "「" + link.factorLabel() + "」");
    }
    out.append(String.join("、", factors));
    out.append("。");
    return out.toString();
  }

  // ================================================================ 扫描

  /**
   * 跑一次关联扫描：实算图谱 ⇒ 落关联组 ⇒ 落风险提示 ⇒ 落整改建议。
   *
   * <p>🔴 <b>阈值必须先登记策略</b>：没有策略就没有阈值，扫描直接 422 拒绝，
   * 不用系统默认阈值替租户做决定。
   * 🔴 同一组关联重扫时**更新**既有提示，而不是刷出一串一模一样的提示。
   */
  public LinkageRiskScanResult scan(String bearer, AccessService.Access actor) {
    PolicyRow policy = policyRow();
    if (policy == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "先登记关联风险策略再扫描 —— 关联阈值与各维度的政策依据必须由租户显式选，系统不给默认值");
    }
    GraphResult graph = computeGraph(policy, legalEntityNames(bearer));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    int clustersCreated = 0;
    int clustersUpdated = 0;
    int alertsCreated = 0;
    int alertsUpdated = 0;
    int remediationsCreated = 0;
    Set<String> currentKeys = new LinkedHashSet<>();

    for (Cluster cluster : graph.clusters()) {
      currentKeys.add(cluster.clusterKey());
      boolean clusterIsNew =
          sql.fetchOne("select id from linkage_cluster where cluster_key=?", cluster.clusterKey())
              == null;
      UUID clusterId = upsertCluster(cluster, now);
      if (clusterId == null) continue;
      if (clusterIsNew) clustersCreated++;
      else clustersUpdated++;
      replaceClusterMembers(clusterId, cluster, graph.shopById());

      boolean alertIsNew =
          sql.fetchOne(
                  "select id from linkage_alert where dedup_key=?",
                  LinkageRiskRules.alertDedupKey(cluster.clusterKey()))
              == null;
      UUID alertId = upsertAlert(clusterId, cluster, now);
      if (alertId == null) continue;
      if (alertIsNew) alertsCreated++;
      else alertsUpdated++;
      recordRescanAction(alertId, cluster, actor, now);
      remediationsCreated += upsertRemediations(alertId, cluster);
    }

    // 🔴 事实已经变了的旧提示：只有在**确实读到了店铺**时才敢自动收口，
    //   否则一次数据范围异常会把全部提示误判成「已整改」。
    if (!graph.shops().isEmpty()) {
      resolveVanishedAlerts(currentKeys, actor, now);
    }

    List<LinkageRiskGraphCluster> clusters = new ArrayList<>();
    for (Cluster cluster : graph.clusters()) {
      clusters.add(
          new LinkageRiskGraphCluster(
              cluster.clusterKey(),
              cluster.shopIds(),
              cluster.shopIds().size(),
              cluster.dimensions(),
              cluster.severity().name(),
              cluster.severity().label(),
              cluster.riskLevel().name(),
              cluster.riskLevel().label(),
              cluster.crossPlatform(),
              cluster.summary()));
    }
    String note =
        graph.shops().isEmpty()
            ? "🔴 这个租户当前**读到 0 家店铺** —— 图谱是空的。"
                + "请确认店铺已录入（2 店铺）以及调用方有 channel.operation.read 权限"
                + "（店铺表上有数据范围策略，权限不对会**静默读到零行**）。"
            : "已按当前事实实算关联图谱："
                + graph.shops().size()
                + " 家店铺、"
                + graph.links().size()
                + " 条关联边、"
                + graph.clusters().size()
                + " 个关联组。";
    return new LinkageRiskScanResult(
        true,
        note,
        graph.shops().size(),
        graph.links().size(),
        graph.clusters().size(),
        clustersCreated,
        clustersUpdated,
        alertsCreated,
        alertsUpdated,
        remediationsCreated,
        clusters);
  }

  private UUID upsertCluster(Cluster cluster, OffsetDateTime now) {
    Record row =
        sql.fetchOne(
            "insert into linkage_cluster(id,tenant_id,cluster_key,shop_count,dimensions,severity,"
                + "risk_level,status,summary,first_detected_at,last_detected_at,version,created_at,"
                + "updated_at)"
                + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,'OPEN',"
                + "?,?::timestamptz,?::timestamptz,0,now(),now())"
                + " on conflict (tenant_id,cluster_key) do update set"
                + " shop_count=excluded.shop_count,dimensions=excluded.dimensions,"
                + " severity=excluded.severity,risk_level=excluded.risk_level,"
                + " summary=excluded.summary,last_detected_at=now(),"
                + " version=linkage_cluster.version+1,updated_at=now()"
                + " returning id",
            Ids.next(),
            cluster.clusterKey(),
            cluster.shopIds().size(),
            String.join(",", cluster.dimensions()),
            cluster.severity().name(),
            cluster.riskLevel().name(),
            cluster.summary(),
            now,
            now);
    return row == null ? null : row.get("id", UUID.class);
  }

  /** 替换组成员（保留店铺当时的代码 / 名称快照）。 */
  private void replaceClusterMembers(
      UUID clusterId, Cluster cluster, Map<UUID, ShopRow> shopById) {
    sql.execute("delete from linkage_cluster_shop where cluster_id=?", clusterId);
    for (UUID shopId : cluster.shopIds()) {
      ShopRow shop = shopById.get(shopId);
      sql.execute(
          "insert into linkage_cluster_shop(id,tenant_id,cluster_id,shop_id,shop_code,shop_name,"
              + "platform_code,created_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,now())",
          Ids.next(),
          clusterId,
          shopId,
          shop == null ? "" : shop.code(),
          shop == null ? "" : shop.name(),
          shop == null ? "" : shop.platformCode());
    }
  }

  private UUID upsertAlert(UUID clusterId, Cluster cluster, OffsetDateTime now) {
    String title =
        cluster.shopIds().size() + " 家店铺存在关联（" + String.join("、", cluster.dimensions()) + "）";
    Record row =
        sql.fetchOne(
            "insert into linkage_alert(id,tenant_id,cluster_id,cluster_key,alert_type,severity,status,"
                + "dedup_key,shop_count,dimensions,title,summary,detected_at,version,created_at,"
                + "updated_at)"
                + " values(?,current_setting('app.current_tenant')::uuid,?,?,'SHARED_FACTOR',?,'OPEN',"
                + "?,?,?,?,?,?::timestamptz,0,now(),now())"
                + " on conflict (tenant_id,dedup_key) do update set"
                + " severity=excluded.severity,shop_count=excluded.shop_count,"
                + " dimensions=excluded.dimensions,summary=excluded.summary,"
                + " cluster_id=excluded.cluster_id,detected_at=now(),"
                + " version=linkage_alert.version+1,updated_at=now()"
                + " returning id",
            Ids.next(),
            clusterId,
            cluster.clusterKey(),
            cluster.severity().name(),
            LinkageRiskRules.alertDedupKey(cluster.clusterKey()),
            cluster.shopIds().size(),
            String.join(",", cluster.dimensions()),
            title,
            cluster.summary(),
            now);
    return row == null ? null : row.get("id", UUID.class);
  }

  /**
   * 重扫留痕：每次扫描都往提示的处置流水里写一行 {@code RESCAN}。
   *
   * <p>🔴 流水是 append-only 的凭据（GRANT 只给 SELECT,INSERT + 守卫触发器），
   * 所以「这条关联是什么时候被谁发现的」这件事**改不动** —— 事后争议时它是唯一的证据。
   * 🔴 from_status 与 to_status 相同（重扫**不改**处置状态：处置是人的决定，
   * 系统不该悄悄把「已整改」改回「待处置」）。
   */
  private void recordRescanAction(
      UUID alertId, Cluster cluster, AccessService.Access actor, OffsetDateTime now) {
    String status =
        sql.fetchOne("select status from linkage_alert where id=?", alertId).get(0, String.class);
    sql.execute(
        "insert into linkage_alert_action(id,tenant_id,alert_id,action,from_status,to_status,"
            + "actor_id,actor_name,reason,occurred_at,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,'RESCAN',?,?,?,?,?,now(),now())",
        Ids.next(),
        alertId,
        status,
        status,
        actor.userId(),
        actor.tenantName(),
        "重扫发现关联："
            + cluster.shopIds().size()
            + " 家店铺 · "
            + cluster.severity().label()
            + " · "
            + String.join("、", cluster.dimensions()));
  }

  /** 按维度生成整改建议（已存在的建议保留它的处置状态）。 */
  private int upsertRemediations(UUID alertId, Cluster cluster) {
    int created = 0;
    Map<RecommendationCode, String> codes = new LinkedHashMap<>();
    for (String dimension : cluster.dimensions()) {
      Dimension value = Dimension.valueOf(dimension);
      codes.put(LinkageRiskRules.recommendationFor(value), value.name());
    }
    if (cluster.severity().weight() >= Severity.HIGH.weight()) {
      codes.put(RecommendationCode.OFFICIAL_API_AUTHORIZATION, "ALL");
    }
    for (var entry : codes.entrySet()) {
      RecommendationCode code = entry.getKey();
      int inserted =
          sql.execute(
              "insert into linkage_remediation(id,tenant_id,alert_id,recommendation_code,dimension,"
                  + "title,detail,priority,status,dedup_key,version,created_at,updated_at)"
                  + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,'PROPOSED',?,0,"
                  + "now(),now())"
                  + " on conflict (tenant_id,dedup_key) do nothing",
              Ids.next(),
              alertId,
              code.name(),
              entry.getValue(),
              code.label(),
              remediationDetail(code),
              LinkageRiskRules.remediationPriority(cluster.severity()),
              LinkageRiskRules.remediationDedupKey(alertId.toString(), code));
      created += inserted;
    }
    return created;
  }

  private String remediationDetail(RecommendationCode code) {
    return switch (code) {
      case SEPARATE_DEVICE ->
          "这几家店铺登记过**共享设备**。请把各店铺的运营账号、浏览器与设备分开，"
              + "并确认各自只用**官方 API 授权**接入。";
      case SEPARATE_NETWORK ->
          "这几家店铺登记过**共享网络出口**。请给各店铺安排独立的网络出口（独立网络 / 独立专线），"
              + "不要让它们共用同一个出口 IP。";
      case SEPARATE_ADDRESS ->
          "这几家店铺登记过**共享地址**。请确认各店铺的发货地址 / 退货地址确实独立，"
              + "避免平台按地址把它们判成同一主体。";
      case VERIFY_LEGAL_ENTITY ->
          "这几家店铺绑定了**同一个法人**。请核对这是否是有意为之："
              + "如果是有意多店经营，请确保各店都走**官方 API 授权**；"
              + "如果不是，请把绑定关系改正确。";
      case REVIEW_LINKAGE ->
          "这几家店铺登记过**其它共享因子**。请人工复核这些共享是否会被平台判成关联。";
      case OFFICIAL_API_AUTHORIZATION ->
          "🔴 关联封号是跨境多店经营的头号风险，**官方 API 授权是规避关联的关键**。"
              + "请确认这几家店铺各自持有独立的官方 API 授权凭据。"
              + "本系统**只做官方 API**，不提供任何模拟登录 / 指纹浏览器能力。";
    };
  }

  /** 把「事实已经不存在」的旧提示收口成「已整改」。 */
  private void resolveVanishedAlerts(
      Set<String> currentKeys, AccessService.Access actor, OffsetDateTime now) {
    List<Record> stale =
        sql.fetch(
            "select id,cluster_id,cluster_key,status from linkage_alert"
                + " where status in ('OPEN','ACKNOWLEDGED')");
    for (Record row : stale) {
      if (currentKeys.contains(row.get("cluster_key", String.class))) continue;
      UUID alertId = row.get("id", UUID.class);
      String from = row.get("status", String.class);
      sql.execute(
          "update linkage_alert set status='REMEDIATED',resolved_by=?,resolved_at=?::timestamptz,"
              + "resolution_note=?,version=version+1,updated_at=now() where id=?",
          actor.userId(),
          now,
          "重扫时该关联已不存在（事实已变化）",
          alertId);
      sql.execute(
          "update linkage_cluster set status='REMEDIATED',version=version+1,updated_at=now()"
              + " where id=? and status in ('OPEN','ACKNOWLEDGED')",
          row.get("cluster_id", UUID.class));
      sql.execute(
          "insert into linkage_alert_action(id,tenant_id,alert_id,action,from_status,to_status,"
              + "actor_id,actor_name,reason,occurred_at,created_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,'RESOLVE',?,'REMEDIATED',"
              + "?,?,?,now(),now())",
          Ids.next(),
          alertId,
          from,
          actor.userId(),
          actor.tenantName(),
          "重扫时该关联已不存在（事实已变化）");
    }
  }

  // ================================================================ 风险提示

  /** 风险提示列表（真分页 + 真筛选 + 汇总）。 */
  public LinkageRiskAlertPage alerts(String status, String severity, int page, int size) {
    checkPage(page, size);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> binds = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      where.append(" and status=?");
      binds.add(status.trim());
    }
    if (severity != null && !severity.isBlank()) {
      where.append(" and severity=?");
      binds.add(severity.trim());
    }
    long total =
        sql.fetchOne("select count(*) from linkage_alert" + where, binds.toArray()).get(0, Long.class);
    var records =
        sql.fetch(
            "select id,cluster_id,cluster_key,alert_type,severity,status,shop_count,dimensions,title,"
                + "summary,detected_at,acknowledged_by,acknowledged_at,acknowledge_reason,"
                + "resolved_by,resolved_at,resolution_note,dismissed_by,dismissed_at,dismiss_reason,"
                + "version,created_at,updated_at from linkage_alert"
                + where
                + " order by detected_at desc,id desc limit ? offset ?",
            bindsWith(binds, size, (long) page * size));
    List<LinkageRiskAlertView> items = new ArrayList<>();
    for (Record row : records) {
      items.add(alertView(row, false));
    }
    long open = countAlerts("status", "OPEN");
    long acknowledged = countAlerts("status", "ACKNOWLEDGED");
    long remediated = countAlerts("status", "REMEDIATED");
    long dismissed = countAlerts("status", "DISMISSED");
    long high = countAlerts("severity", "HIGH") + countAlerts("severity", "CRITICAL");
    return new LinkageRiskAlertPage(
        items, page, size, total, open, acknowledged, remediated, dismissed, high);
  }

  private long countAlerts(String column, String value) {
    return sql.fetchOne("select count(*) from linkage_alert where " + column + "=?", value)
        .get(0, Long.class);
  }

  /** 风险提示详情（含处置留痕与整改建议）。 */
  public LinkageRiskAlertView alert(String id) {
    UUID alertId = uuid(id);
    Record row =
        sql.fetchOne(
            "select id,cluster_id,cluster_key,alert_type,severity,status,shop_count,dimensions,title,"
                + "summary,detected_at,acknowledged_by,acknowledged_at,acknowledge_reason,"
                + "resolved_by,resolved_at,resolution_note,dismissed_by,dismissed_at,dismiss_reason,"
                + "version,created_at,updated_at from linkage_alert where id=?",
            alertId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联风险提示不存在或无权查看");
    }
    return alertView(row, true);
  }

  private LinkageRiskAlertView alertView(Record row, boolean detail) {
    UUID alertId = row.get("id", UUID.class);
    List<LinkageRiskAlertShopView> shops = new ArrayList<>();
    for (Record shop :
        sql.fetch(
            "select shop_id,shop_code,shop_name,platform_code from linkage_cluster_shop"
                + " where cluster_id=? order by shop_code",
            row.get("cluster_id", UUID.class))) {
      shops.add(
          new LinkageRiskAlertShopView(
              shop.get("shop_id", UUID.class),
              shop.get("shop_code", String.class),
              shop.get("shop_name", String.class),
              shop.get("platform_code", String.class)));
    }
    List<LinkageRiskAlertActionView> actions = new ArrayList<>();
    for (Record action :
        sql.fetch(
            "select id,action,from_status,to_status,actor_name,reason,occurred_at"
                + " from linkage_alert_action where alert_id=? order by occurred_at desc,id desc",
            alertId)) {
      actions.add(
          new LinkageRiskAlertActionView(
              action.get("id", UUID.class),
              action.get("action", String.class),
              action.get("from_status", String.class),
              action.get("to_status", String.class),
              action.get("actor_name", String.class),
              action.get("reason", String.class),
              action.get("occurred_at", OffsetDateTime.class)));
    }
    List<LinkageRiskRemediationView> remediations = new ArrayList<>();
    for (Record remediation :
        sql.fetch(
            "select id,recommendation_code,dimension,title,detail,priority,status,decision_note,"
                + "decided_at,version from linkage_remediation where alert_id=? order by priority desc,id",
            alertId)) {
      remediations.add(
          new LinkageRiskRemediationView(
              remediation.get("id", UUID.class),
              remediation.get("recommendation_code", String.class),
              LinkageRiskRules.recommendationLabel(remediation.get("recommendation_code", String.class)),
              remediation.get("dimension", String.class),
              remediation.get("title", String.class),
              remediation.get("detail", String.class),
              remediation.get("priority", String.class),
              remediation.get("status", String.class),
              remediation.get("decision_note", String.class),
              remediation.get("decided_at", OffsetDateTime.class),
              num(remediation.get("version", Long.class))));
    }
    String severity = row.get("severity", String.class);
    String status = row.get("status", String.class);
    return new LinkageRiskAlertView(
        alertId,
        row.get("cluster_id", UUID.class),
        row.get("cluster_key", String.class),
        row.get("alert_type", String.class),
        severity,
        LinkageRiskRules.severityLabel(severity),
        status,
        LinkageRiskRules.alertStatusLabel(status),
        num(row.get("shop_count", Integer.class)),
        splitDimensions(row.get("dimensions", String.class)),
        row.get("title", String.class),
        row.get("summary", String.class),
        row.get("detected_at", OffsetDateTime.class),
        row.get("acknowledged_by", UUID.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("acknowledge_reason", String.class),
        row.get("resolved_by", UUID.class),
        row.get("resolved_at", OffsetDateTime.class),
        row.get("resolution_note", String.class),
        row.get("dismissed_by", UUID.class),
        row.get("dismissed_at", OffsetDateTime.class),
        row.get("dismiss_reason", String.class),
        num(row.get("version", Long.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        shops,
        actions,
        remediations);
  }

  /** 确认提示（待处置 → 已确认）。 */
  public LinkageRiskAlertView acknowledgeAlert(
      String id, String reason, AccessService.Access actor) {
    return disposeAlert(id, reason, actor, AlertStatus.ACKNOWLEDGED, "ACKNOWLEDGE");
  }

  /** 整改完成（待处置 / 已确认 → 已整改）。 */
  public LinkageRiskAlertView resolveAlert(String id, String reason, AccessService.Access actor) {
    return disposeAlert(id, reason, actor, AlertStatus.REMEDIATED, "RESOLVE");
  }

  /** 忽略提示（待处置 / 已确认 → 已忽略）。 */
  public LinkageRiskAlertView dismissAlert(String id, String reason, AccessService.Access actor) {
    return disposeAlert(id, reason, actor, AlertStatus.DISMISSED, "DISMISS");
  }

  private LinkageRiskAlertView disposeAlert(
      String id, String reason, AccessService.Access actor, AlertStatus target, String action) {
    UUID alertId = uuid(id);
    Record row = sql.fetchOne("select id,cluster_id,status from linkage_alert where id=?", alertId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联风险提示不存在或无权查看");
    }
    String from = row.get("status", String.class);
    AlertStatus current = AlertStatus.valueOf(from);
    if (!LinkageRiskRules.canTransition(current, target)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "提示状态不能从「"
              + current.label()
              + "」变到「"
              + target.label()
              + "」（已整改与已忽略是终态）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (target == AlertStatus.ACKNOWLEDGED) {
      sql.execute(
          "update linkage_alert set status=?,acknowledged_by=?,acknowledged_at=?::timestamptz,"
              + "acknowledge_reason=?,version=version+1,updated_at=now() where id=?",
          target.name(),
          actor.userId(),
          now,
          reason,
          alertId);
    } else if (target == AlertStatus.REMEDIATED) {
      sql.execute(
          "update linkage_alert set status=?,resolved_by=?,resolved_at=?::timestamptz,resolution_note=?,"
              + "version=version+1,updated_at=now() where id=?",
          target.name(),
          actor.userId(),
          now,
          reason,
          alertId);
    } else {
      sql.execute(
          "update linkage_alert set status=?,dismissed_by=?,dismissed_at=?::timestamptz,dismiss_reason=?,"
              + "version=version+1,updated_at=now() where id=?",
          target.name(),
          actor.userId(),
          now,
          reason,
          alertId);
    }
    sql.execute(
        "update linkage_cluster set status=?,version=version+1,updated_at=now()"
            + " where id=? and status in ('OPEN','ACKNOWLEDGED')",
        target.name(),
        row.get("cluster_id", UUID.class));
    sql.execute(
        "insert into linkage_alert_action(id,tenant_id,alert_id,action,from_status,to_status,"
            + "actor_id,actor_name,reason,occurred_at,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,?,now(),now())",
        Ids.next(),
        alertId,
        action,
        from,
        target.name(),
        actor.userId(),
        actor.tenantName(),
        reason);
    return alert(alertId.toString());
  }

  // ================================================================ 整改建议

  /** 整改建议列表（真分页 + 真筛选）。 */
  public LinkageRiskRemediationPage remediations(String status, int page, int size) {
    checkPage(page, size);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> binds = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      where.append(" and status=?");
      binds.add(status.trim());
    }
    long total =
        sql.fetchOne("select count(*) from linkage_remediation" + where, binds.toArray())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,recommendation_code,dimension,title,detail,priority,status,decision_note,"
                + "decided_at,version from linkage_remediation"
                + where
                + " order by priority desc,id limit ? offset ?",
            bindsWith(binds, size, (long) page * size));
    List<LinkageRiskRemediationView> items = new ArrayList<>();
    for (Record row : records) {
      items.add(
          new LinkageRiskRemediationView(
              row.get("id", UUID.class),
              row.get("recommendation_code", String.class),
              LinkageRiskRules.recommendationLabel(row.get("recommendation_code", String.class)),
              row.get("dimension", String.class),
              row.get("title", String.class),
              row.get("detail", String.class),
              row.get("priority", String.class),
              row.get("status", String.class),
              row.get("decision_note", String.class),
              row.get("decided_at", OffsetDateTime.class),
              num(row.get("version", Long.class))));
    }
    return new LinkageRiskRemediationPage(items, page, size, total);
  }

  /** 处置一条整改建议（已采纳 / 已忽略 / 已完成）。 */
  public LinkageRiskRemediationView updateRemediation(
      String id, LinkageRiskRemediationStatusInput input, AccessService.Access actor) {
    UUID remediationId = uuid(id);
    Record row =
        sql.fetchOne("select id,status,version from linkage_remediation where id=?", remediationId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "整改建议不存在或无权查看");
    }
    RemediationStatus current = RemediationStatus.valueOf(row.get("status", String.class));
    RemediationStatus target = RemediationStatus.valueOf(input.status());
    if (!LinkageRiskRules.canTransition(current, target)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "整改建议状态不能从「"
              + current.label()
              + "」变到「"
              + target.label()
              + "」（已忽略与已完成是终态）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update linkage_remediation set status=?,decided_by=?,decided_at=?::timestamptz,decision_note=?,"
            + "version=version+1,updated_at=now() where id=?",
        target.name(),
        actor.userId(),
        now,
        nz(input.note()),
        remediationId);
    Record fresh =
        sql.fetchOne(
            "select id,recommendation_code,dimension,title,detail,priority,status,decision_note,"
                + "decided_at,version from linkage_remediation where id=?",
            remediationId);
    return new LinkageRiskRemediationView(
        fresh.get("id", UUID.class),
        fresh.get("recommendation_code", String.class),
        LinkageRiskRules.recommendationLabel(fresh.get("recommendation_code", String.class)),
        fresh.get("dimension", String.class),
        fresh.get("title", String.class),
        fresh.get("detail", String.class),
        fresh.get("priority", String.class),
        fresh.get("status", String.class),
        fresh.get("decision_note", String.class),
        fresh.get("decided_at", OffsetDateTime.class),
        num(fresh.get("version", Long.class)));
  }

  // ================================================================ 读事实

  /**
   * 店铺行（节点来源）。
   *
   * <p>🔴 列名必须<b>全部取别名</b>：{@code shop} 与 {@code platform} 都有 {@code code} 与
   * {@code name}，不取别名时 jOOQ 按名字取值会**取到第一列**，平台列会被静默读成店铺列。
   */
  private List<ShopRow> shopRows() {
    return sql
        .fetch(
            "select s.id as shop_id,s.code as shop_code,s.name as shop_name,s.platform_id,"
                + "s.legal_entity_id,s.status as shop_status,"
                + "p.code as platform_code,p.name as platform_name"
                + " from shop s join platform p on p.id=s.platform_id"
                + " where s.status<>'ARCHIVED' order by s.code,s.id")
        .map(
            row ->
                new ShopRow(
                    row.get("shop_id", UUID.class),
                    row.get("shop_code", String.class),
                    row.get("shop_name", String.class),
                    row.get("platform_code", String.class),
                    row.get("platform_name", String.class),
                    row.get("legal_entity_id", UUID.class),
                    row.get("shop_status", String.class)));
  }

  private ShopRow shopRow(UUID shopId) {
    if (shopId == null) return null;
    for (ShopRow shop : shopRows()) {
      if (shop.id().equals(shopId)) return shop;
    }
    return null;
  }

  private Map<UUID, ShopRow> shopById() {
    Map<UUID, ShopRow> byId = new HashMap<>();
    for (ShopRow shop : shopRows()) byId.put(shop.id(), shop);
    return byId;
  }

  private Map<UUID, Integer> linkCounts(Set<UUID> shopIds) {
    Map<UUID, Integer> counts = new HashMap<>();
    for (EdgeRow edge : edgeRows()) {
      if (!shopIds.contains(edge.shopId())) continue;
      counts.merge(edge.shopId(), 1, Integer::sum);
    }
    return counts;
  }

  private Map<UUID, List<String>> shopDimensions(Set<UUID> shopIds) {
    Map<UUID, List<String>> dimensions = new HashMap<>();
    Map<UUID, String> identifierDimension = new HashMap<>();
    for (IdentifierRow identifier : identifierRows()) {
      identifierDimension.put(identifier.id(), identifier.dimension());
    }
    for (EdgeRow edge : edgeRows()) {
      if (!shopIds.contains(edge.shopId())) continue;
      String dimension = identifierDimension.get(edge.identifierId());
      if (dimension == null) continue;
      List<String> list = dimensions.computeIfAbsent(edge.shopId(), k -> new ArrayList<>());
      if (!list.contains(dimension)) list.add(dimension);
    }
    return dimensions;
  }

  // ================================================================ 工具

  private static Object[] bindsWith(List<Object> binds, int size, long offset) {
    Object[] values = new Object[binds.size() + 2];
    for (int i = 0; i < binds.size(); i++) values[i] = binds.get(i);
    values[binds.size()] = size;
    values[binds.size() + 1] = offset;
    return values;
  }

  private static List<String> splitDimensions(String value) {
    if (value == null || value.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }

  private static void checkPage(int page, int size) {
    if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码不能为负");
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
    }
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法的 UUID");
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static int num(Integer value) {
    return value == null ? 0 : value;
  }

  private static long num(Long value) {
    return value == null ? 0L : value;
  }
}
