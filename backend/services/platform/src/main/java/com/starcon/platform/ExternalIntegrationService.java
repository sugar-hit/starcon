package com.starcon.platform;

import static com.starcon.platform.ExternalIntegrationModels.*;

import com.starcon.platform.ExternalIntegrationPort.ExternalSyncCommand;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncContext;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncOutcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.2.3.1「其他对接」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 每个对接独立连接、独立健康状态</b>（蓝图加粗句，本模块最核心的一条）：
 *       健康状态是<b>连接自己那一行</b>的列，健康重算只读<b>同一个 connection_id</b> 的记录。
 *       ⇒ 一个外部服务故障**在物理上不可能**把别的对接带成异常。
 *       本类<b>刻意没有</b>「全局集成健康」这种东西：一旦有它，
 *       「一个对接挂了」就会显示成「全部都不健康」。
 *   <li><b>没接通就如实说没接通</b>：适配器未接入 ⇒ 同步记录 status = NOT_CONNECTED、
 *       HTTP 409、<b>不写成功记录</b>、<b>不写外部系统侧的单号</b>。
 *   <li><b>本地台账成功 ≠ 外部侧已登记</b>：{@code provider_notified} 与 {@code status}
 *       是<b>两个分开的字段</b>。DIRECT + SUCCEEDED 一定 {@code provider_notified=true}
 *       （DB 层 CHECK 钉死）；人工登记外部侧已完成的记录一定 {@code source=EXTERNAL}
 *       且 {@code provider_notified=false}。
 *   <li><b>密钥只有引用</b>：连接配置里存的是密钥管理器的<b>引用 ID</b>
 *       （照 18.1.1.1 应用凭证与 18.2.2.1 的纪律），<b>库里没有任何密钥明文</b>。
 *   <li><b>不抄 channel 的表</b>：上游 2 渠道机制只经 {@link ChannelPlatformPort} 的
 *       授权 HTTP 端点<b>只读</b>，不建跨库 FK、不抄店铺档案。
 *   <li><b>条数一律 BigDecimal</b>：库里 {@code NUMERIC(19,6)}，本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class ExternalIntegrationService {

  /** 一次同步允许处理的最大条数（防一手：适配器回来一个巨大的数也不照单全收）。 */
  static final int MAX_RECORDS = 1_000_000;

  private final DSLContext sql;
  private final ExternalIntegrationRegistry adapters;
  private final ChannelPlatformPort channel;

  public ExternalIntegrationService(
      DSLContext sql, ExternalIntegrationRegistry adapters, ChannelPlatformPort channel) {
    this.sql = sql;
    this.adapters = adapters;
    this.channel = channel;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 找不到（404）。 */
  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 数量（超时秒数 / 重试次数 / 间隔秒数）。 */
  private static int count(Integer raw, String label, int min, int max) {
    var value = raw == null ? min : raw;
    check(value >= min && value <= max, label + "只能在 " + min + " 到 " + max + " 之间");
    return value;
  }

  /** 条数（BigDecimal，禁浮点；最多 {@link #MAX_RECORDS}）。 */
  private static BigDecimal records(BigDecimal raw, String label) {
    if (raw == null) return BigDecimal.ZERO;
    check(raw.signum() >= 0, label + "不能为负");
    check(raw.stripTrailingZeros().scale() <= COUNT_SCALE, label + "最多 " + COUNT_SCALE + " 位小数");
    check(raw.compareTo(BigDecimal.valueOf(MAX_RECORDS)) <= 0, label + "不能超过 " + MAX_RECORDS);
    return raw;
  }

  /** SHA-256 十六进制摘要。 */
  static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 当前会话绑定的租户（**不**来自请求头）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** **DB 时钟**（与落库的时间列同一个钟域）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  private long countOf(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  /** ISO-8601 带时区的时间（如 {@code 2026-10-30T00:00:00Z}）。 */
  private static OffsetDateTime parseTime(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          label + "必须是 ISO-8601 带时区的时间（如 2026-10-30T00:00:00Z）");
    }
  }

  // ================================================================ 能力声明

  /**
   * 18.2.3.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **六个外部系统适配器全部如实答「未接入」** —— 本仓没有任何真实的外部系统
   * 凭据与网络通道。能力声明把这几格写清楚，「缺在谁身上」才是一条<b>可查的事实</b>，
   * 而不是一句注释。
   */
  public ExternalIntegrationCapabilities capabilities() {
    var capabilities =
        List.of(
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "统一连接配置管理（对接连接档案）",
                true,
                "platform_external_connection 表 + 连接配置端点；"
                    + "记对接类别 / 适配器代码 / 配置名 / 接口地址 / 绑定的上游店铺 / 启用状态，"
                    + "🔴 **只存密钥的引用**（credential_ref + 其 SHA-256 定位摘要 + 末四位提示），"
                    + "**库里没有任何密钥明文**（照 18.1.1.1 应用凭证与 18.2.2.1 的纪律）",
                "",
                ""),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "同步任务配置",
                true,
                "platform_external_sync_task 表 + 任务配置端点；"
                    + "UNIQUE(tenant_id, connection_id, task_type) ⇒ 一个连接一个任务类型，"
                    + "**两个对接的任务互不影响**",
                "",
                "任务类型必须与连接的类别相容（货源平台只能配 CATALOG_SYNC），"
                    + "否则界面上会出现「给海外仓配货源同步」这种永远跑不通的配置"),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "🔴 **每个对接独立连接与独立健康状态**",
                true,
                "platform_external_connection.health_status 是**每一行自己的**列；"
                    + "健康重算只读**同一个 connection_id** 的记录（SQL 的 WHERE 带连接 id）；"
                    + "🔴 **没有**「全局集成健康」这种列 —— 有它就会把「一个对接挂了」"
                    + "显示成「全部都不健康」",
                "",
                "蓝图加粗句「一个外部服务故障不应影响其他集成」在数据层是物理事实，"
                    + "不是一条约定"),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "同步记录（输出里的「同步记录」）",
                true,
                "platform_external_sync_record 表（**append-only**，触发器钉死）+ 记录端点；"
                    + "每次同步落一条，结论与条数都记下来",
                "",
                "「本地台账成功」**不等于**「外部侧已登记」："
                    + "provider_notified 与 status 是两个分开的字段"),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "「外部侧已完成」的同步人工登记",
                true,
                "POST /sync-records（source=EXTERNAL，provider_notified=false）；"
                    + "外部系统那边跑完同步，在这里补一条记录，"
                    + "**这个对接**的健康状态就如实反映现状",
                "",
                "🔴 人工登记的行**永远**标 provider_notified=false —— "
                    + "它记的是「本系统没有与外部系统核对过」，不能被读成「已登记」"),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "**经由外部通道发起同步**（直连）",
                false,
                "本仓**不存在** 1688 / 淘宝 / 海外仓 / 物流商 / 聚合认证 / 自建 BI 的任何真实适配器"
                    + "（backend / services / tools 下没有对应的客户端），"
                    + "也没有任何真实的外部系统凭据与网络通道",
                "18.2.3",
                "端点如实答 **409**（不是假成功、不写成功记录）。"
                    + "适配器**不猜**各外部系统的接口形状（路径 / 字段名 / 签名方式）——"
                    + "没有真实对接文档时编一个出来比留空更危险"),
            new ExternalIntegrationCapability(
                "18.2.3.1",
                "上游 2 渠道机制（**只读**）",
                true,
                "ChannelPlatformPort 读 channel 服务（8083）的 /channel/platforms 与 /channel/shops；"
                    + "**只转发调用方自己的 bearer、不传租户号、不建跨库 FK**；读不到一律 503",
                "",
                "读不到是「读不到」不是「没有数据」—— 空集合会让人以为上游没有店铺"));
    var gaps =
        List.of(
            new ExternalIntegrationGap(
                "18.2.3 卷级",
                "六个外部系统的**直连适配器**",
                "platform",
                false,
                "下游 = 外部服务",
                "归 18.2.3 卷级：外部通道是 18.2 各模块共用的外部通道，"
                    + "不是某一格 L4 独有的"),
            new ExternalIntegrationGap(
                "2 渠道机制",
                "上游店铺 / 平台目录",
                "channel",
                true,
                "上游 = 2 渠道机制",
                "**只读**：本模块不建跨库 FK、不抄店铺档案；"
                    + "连接档案里只存平台代码与店铺 id 这两个**只读引用**"));
    return new ExternalIntegrationCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public ExternalIntegrationOverview overview() {
    var now = dbNow();
    return new ExternalIntegrationOverview(
        countOf("select count(*) from platform_external_connection"),
        countOf("select count(*) from platform_external_connection where status = 'ACTIVE'"),
        countOf("select count(*) from platform_external_connection where health_status = 'HEALTHY'"),
        countOf("select count(*) from platform_external_connection where health_status = 'DEGRADED'"),
        countOf("select count(*) from platform_external_connection where health_status = 'UNHEALTHY'"),
        countOf(
            "select count(*) from platform_external_connection where health_status = 'NOT_CONNECTED'"),
        countOf("select count(*) from platform_external_connection where health_status = 'UNKNOWN'"),
        countOf("select count(*) from platform_external_sync_task"),
        countOf("select count(*) from platform_external_sync_task where enabled"),
        countOf("select count(*) from platform_external_sync_record"),
        countOf(
            "select count(*) from platform_external_sync_record where status in ('FAILED','TIMEOUT')"),
        now);
  }

  /** 逐个外部系统的通道可用性（**逐个如实答**）。 */
  public List<ExternalAdapterAvailability> availability() {
    var result = new ArrayList<ExternalAdapterAvailability>();
    for (var adapter : adapters.all()) {
      var category = ExternalIntegrationCategory.parse(adapter.category());
      result.add(
          new ExternalAdapterAvailability(
              adapter.adapterCode(),
              adapter.displayName(),
              adapter.category(),
              category == null ? adapter.category() : category.label(),
              adapter.connected(),
              adapter.unavailableReason(),
              AbstractUnavailableExternalIntegrationAdapter.OWNER_MODULE));
    }
    return result;
  }

  // ================================================================ 连接配置

  /** 连接配置列表（真筛选 + 真关键词）。 */
  public List<ExternalConnectionRow> connectionRows(
      String category, String adapterCode, String status, String healthStatus, String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (category != null && !category.isBlank()) {
      where.append(" and c.category = ?");
      params.add(category.trim().toUpperCase(Locale.ROOT));
    }
    if (adapterCode != null && !adapterCode.isBlank()) {
      where.append(" and c.adapter_code = ?");
      params.add(adapterCode.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and c.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (healthStatus != null && !healthStatus.isBlank()) {
      where.append(" and c.health_status = ?");
      params.add(healthStatus.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (c.name ilike ? or c.remark ilike ? or c.base_url ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return sql.fetch(
            "select c.id,c.category,c.adapter_code,c.name,c.base_url,c.credential_ref,c.credential_hint,"
                + " c.channel_platform_code,c.channel_shop_id,c.status,c.health_status,c.last_health_at,"
                + " c.last_sync_at,c.last_sync_record_id,c.timeout_seconds,c.max_attempts,c.remark,"
                + " c.created_by,c.created_at,c.updated_at,"
                + " (select count(*) from platform_external_sync_task t where t.connection_id = c.id)"
                + "   as task_count,"
                + " (select count(*) from platform_external_sync_record r where r.connection_id = c.id)"
                + "   as record_count"
                + " from platform_external_connection c"
                + where
                + " order by c.category, c.created_at",
            params.toArray())
        .map(ExternalIntegrationService::toConnectionRow);
  }

  /** 一条连接配置。 */
  public ExternalConnectionRow connectionRow(UUID id) {
    var row =
        sql.fetchOne(
            "select c.id,c.category,c.adapter_code,c.name,c.base_url,c.credential_ref,"
                + " c.credential_hint,c.channel_platform_code,c.channel_shop_id,c.status,"
                + " c.health_status,c.last_health_at,c.last_sync_at,c.last_sync_record_id,"
                + " c.timeout_seconds,c.max_attempts,c.remark,c.created_by,c.created_at,c.updated_at,"
                + " (select count(*) from platform_external_sync_task t where t.connection_id = c.id)"
                + "   as task_count,"
                + " (select count(*) from platform_external_sync_record r where r.connection_id = c.id)"
                + "   as record_count"
                + " from platform_external_connection c where c.id = ?",
            id);
    if (row == null) notFound("对接连接不存在");
    return toConnectionRow(row);
  }

  /** 新增或修改一个对接连接配置（**密钥只存引用**）。 */
  public ExternalConnectionRow saveConnection(
      ExternalConnectionInput input, AccessService.Access actor) {
    var category = ExternalIntegrationCategory.parse(input.category());
    check(category != null, "对接类别只能是 SUPPLY_SOURCE / OVERSEAS_WAREHOUSE / LOGISTICS_CARRIER / CERTIFICATION_AGGREGATOR / SELF_HOSTED_BI");
    var adapterCode = required(input.adapterCode(), "适配器代码", 40).toUpperCase(Locale.ROOT);
    check(
        List.of(
                "ALIBABA_1688",
                "TAOBAO",
                "OVERSEAS_WAREHOUSE",
                "LOGISTICS_CARRIER",
                "CERTIFICATION_AGGREGATOR",
                "SELF_HOSTED_BI")
            .contains(adapterCode),
        "适配器代码只能是 ALIBABA_1688 / TAOBAO / OVERSEAS_WAREHOUSE / LOGISTICS_CARRIER / CERTIFICATION_AGGREGATOR / SELF_HOSTED_BI");
    // 类别与适配器必须相容（DB 层也有同一道 CHECK —— 这里先挡一道，给一句人话）。
    var categoryOfAdapter = categoryOfAdapter(adapterCode);
    check(
        categoryOfAdapter == category,
        "适配器 " + adapterCode + " 属于「" + categoryOfAdapter.label() + "」这一类，"
            + "不能配到「" + category.label() + "」上");
    var name = required(input.name(), "配置名", 120);
    var baseUrl = optional(input.baseUrl(), "接口地址", 300);
    if (!baseUrl.isEmpty()) {
      check(
          baseUrl.startsWith("http://") || baseUrl.startsWith("https://"),
          "接口地址必须是 http:// 或 https:// 开头的完整地址");
    }
    var credentialRef = optional(input.credentialRef(), "密钥引用", 200);
    var credentialHint = optional(input.credentialHint(), "密钥末位提示", 16);
    check(
        credentialRef.isEmpty() == credentialHint.isEmpty(),
        "密钥引用与末位提示必须同时填或同时不填 —— 三者（同一条凭据的三个面）缺一个就说不清配的是哪条");
    var channelPlatformCode = optional(input.channelPlatformCode(), "上游平台代码", 40);
    var channelShopId = input.channelShopId();
    var status =
        input.status() == null || input.status().isBlank()
            ? "ACTIVE"
            : input.status().trim().toUpperCase(Locale.ROOT);
    check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
    var timeoutSeconds = count(input.timeoutSeconds(), "超时秒数", 1, 120);
    var maxAttempts = count(input.maxAttempts(), "最大重试次数", 1, 10);
    var remark = optional(input.remark(), "备注", 500);

    var now = dbNow();
    var lookup = credentialRef.isEmpty() ? "" : sha256Hex(credentialRef);
    // 🔴 初始健康状态**用同一套规则算出来**（而不是写死一个值）——
    //    写死的值会与 {@link #computeHealth} 的结论对不上，
    //    概览里的「健康 / 亚健康 / 不健康 / 未接入」各数就会说谎。
    var initialHealth = computeHealth(adapterConnected(adapterCode), List.of());
    var id = input.id();
    if (id == null) {
      var existing =
          sql.fetchOne(
              "select id from platform_external_connection where name = ?", name);
      id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
      if (existing == null) {
        sql.execute(
            "insert into platform_external_connection(id,tenant_id,category,adapter_code,name,base_url,"
                + " credential_ref,credential_ref_lookup,credential_hint,channel_platform_code,"
                + " channel_shop_id,status,health_status,timeout_seconds,max_attempts,remark,"
                + " created_by,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?::uuid,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            id,
            currentTenant(),
            category.name(),
            adapterCode,
            name,
            baseUrl,
            credentialRef,
            lookup,
            credentialHint,
            channelPlatformCode,
            channelShopId,
            status,
            initialHealth.name(),
            timeoutSeconds,
            maxAttempts,
            remark,
            actor.userId(),
            now,
            now);
      } else {
        sql.execute(
            "update platform_external_connection set category = ?, adapter_code = ?, name = ?,"
                + " base_url = ?, credential_ref = ?, credential_ref_lookup = ?, credential_hint = ?,"
                + " channel_platform_code = ?, channel_shop_id = ?, status = ?,"
                + " timeout_seconds = ?, max_attempts = ?, remark = ?, updated_at = ?::timestamptz"
                + " where id = ?",
            category.name(),
            adapterCode,
            name,
            baseUrl,
            credentialRef,
            lookup,
            credentialHint,
            channelPlatformCode,
            channelShopId,
            status,
            timeoutSeconds,
            maxAttempts,
            remark,
            now,
            id);
      }
    } else {
      var updated =
          sql.execute(
              "update platform_external_connection set category = ?, adapter_code = ?, name = ?,"
                  + " base_url = ?, credential_ref = ?, credential_ref_lookup = ?, credential_hint = ?,"
                  + " channel_platform_code = ?, channel_shop_id = ?, status = ?,"
                  + " timeout_seconds = ?, max_attempts = ?, remark = ?, updated_at = ?::timestamptz"
                  + " where id = ?",
              category.name(),
              adapterCode,
              name,
              baseUrl,
              credentialRef,
              lookup,
              credentialHint,
              channelPlatformCode,
              channelShopId,
              status,
              timeoutSeconds,
              maxAttempts,
              remark,
              now,
              id);
      if (updated == 0) notFound("对接连接不存在");
      // 🔴 改了配置（尤其换了适配器）之后，**只**重算**这一个连接**的健康状态，
      //    让「配置」与「健康结论」不会各说各话。
      persistHealth(id);
    }
    return connectionRow(id);
  }

  /** 停用一个对接连接（**不删**：历史同步记录要能复现）。 */
  public ExternalConnectionRow disableConnection(UUID id, AccessService.Access actor) {
    var updated =
        sql.execute(
            "update platform_external_connection set status = 'DISABLED', updated_at = ?::timestamptz"
                + " where id = ?",
            dbNow(),
            id);
    if (updated == 0) notFound("对接连接不存在");
    return connectionRow(id);
  }

  // ================================================================ 健康状态

  /**
   * 🔴 **健康判定规则**（纯函数，可单测）。
   *
   * <p>输入只有两样：<b>这个适配器通不通</b> 与 <b>这个连接自己的记录结论</b>
   * （按时间倒序，最新的在前）。
   *
   * <p>🔴 **输入里没有任何「别的连接」** —— 这就是「一个外部服务故障不应影响其他集成」
   * 在代码里的落点：健康判定函数<b>根本拿不到</b>别的连接的数据。
   *
   * <p>规则（<b>记录优先</b>）：
   *
   * <ol>
   *   <li><b>有记录</b> ⇒ 以**这个连接自己最新的那条记录**为准：
   *       <ul>
   *         <li>最新一条成功 ⇒ {@code HEALTHY}；
   *         <li>最新一条未接入 ⇒ {@code NOT_CONNECTED}；
   *         <li>最新一条失败 / 超时：
   *             <ul>
   *               <li>这个连接<b>历史上成功过</b> ⇒ {@code DEGRADED}（还能用，但已经不稳）；
   *               <li>这个连接<b>从未成功过</b> ⇒ {@code UNHEALTHY}。
   *             </ul>
   *       </ul>
   *   <li><b>没有记录</b> ⇒ 看通道：适配器通 ⇒ {@code UNKNOWN}（<b>「没跑过」不等于「坏了」</b>）；
   *       适配器不通 ⇒ {@code NOT_CONNECTED}。
   * </ol>
   *
   * <p>🔴 <b>为什么是「记录优先」而不是「通道优先」</b>：本模块承诺「外部系统那边跑完同步，
   * 在这里补一条记录，<b>这个对接</b>的健康状态就如实反映现状」—— 那条人工登记记录
   * （{@code source=EXTERNAL}、{@code provider_notified=false}）记的是**外部侧的事实**，
   * 它**不该**被「本系统的直连通道没接通」这一条压掉。若改成通道优先，
   * 本模块唯一「今天就能用」的那条路径会永远显示成「未接入」，能力声明就成了空话。
   *
   * <p>⚠️ 「有没有与外部系统核对过」由 {@code providerNotified} 这一**独立字段**承担，
   * <b>不</b>混进健康状态：人工登记的成功是「外部侧确实成功了，但我们没去核对」，
   * 健康是健康的，核对与否是另一回事。
   */
  static ExternalHealthStatus computeHealth(
      boolean adapterConnected, List<String> ownStatusesNewestFirst) {
    if (ownStatusesNewestFirst != null && !ownStatusesNewestFirst.isEmpty()) {
      var latest = ownStatusesNewestFirst.get(0);
      if ("SUCCEEDED".equals(latest)) return ExternalHealthStatus.HEALTHY;
      if ("NOT_CONNECTED".equals(latest)) return ExternalHealthStatus.NOT_CONNECTED;
      // 失败 / 超时：看这个连接自己历史上成功过没有。
      for (var status : ownStatusesNewestFirst) {
        if ("SUCCEEDED".equals(status)) return ExternalHealthStatus.DEGRADED;
      }
      return ExternalHealthStatus.UNHEALTHY;
    }
    // 一条记录都没有 ⇒ 只剩「通道通不通」这一样可看的事实。
    return adapterConnected ? ExternalHealthStatus.UNKNOWN : ExternalHealthStatus.NOT_CONNECTED;
  }

  /**
   * 读**这个连接自己**的记录结论（按时间倒序）。
   *
   * <p>🔴 SQL 里的 {@code where connection_id = ?} 是本模块最核心的一行代码：
   * 它保证健康重算<b>只</b>看得到这个连接的记录。
   */
  private List<String> ownRecordStatuses(UUID connectionId) {
    return sql.fetch(
            "select status from platform_external_sync_record where connection_id = ?"
                + " order by finished_at desc, run_no desc",
            connectionId)
        .map(r -> r.get(0, String.class));
  }

  /**
   * 这个连接**自己最新的一条**同步记录（没有则 {@code null}）。
   *
   * <p>🔴 同样带 {@code where connection_id = ?} —— 「最近一次同步」也是**这个对接自己**的。
   */
  private org.jooq.Record newestOwnRecord(UUID connectionId) {
    return sql.fetchOne(
        "select id, finished_at from platform_external_sync_record where connection_id = ?"
            + " order by finished_at desc, run_no desc limit 1",
        connectionId);
  }

  /** 这个适配器通不通（注册表里查不到 ⇒ 不通）。 */
  private boolean adapterConnected(String adapterCode) {
    var adapter = adapters.find(adapterCode);
    return adapter != null && adapter.connected();
  }

  /** 🔴 **一个对接自己**的健康状态（**绝不**混入别的连接）。 */
  public ExternalConnectionHealth healthOf(UUID id) {
    var connection = connectionRow(id);
    var connected = adapterConnected(connection.adapterCode());
    var statuses = ownRecordStatuses(id);
    var health = computeHealth(connected, statuses);
    var successCount =
        (long) statuses.stream().filter("SUCCEEDED"::equals).count();
    var failureCount =
        (long) statuses.stream().filter(s -> "FAILED".equals(s) || "TIMEOUT".equals(s)).count();
    var recent = recordRows(id, null, null, null, null, 10);
    return new ExternalConnectionHealth(
        connection.id(),
        connection.name(),
        connection.adapterCode(),
        connection.adapterLabel(),
        health.name(),
        health.label(),
        health == ExternalHealthStatus.HEALTHY,
        diagnose(health, connected, statuses.size()),
        recommend(health, connected),
        statuses.size(),
        successCount,
        failureCount,
        connection.lastHealthAt(),
        connection.lastSyncAt(),
        connection.lastSyncRecordId(),
        recent);
  }

  /**
   * 🔴 **重算某一个连接的健康状态**并落库。
   *
   * <p>🔴 WHERE 带连接 id ⇒ **只**改这一行。别的连接的健康状态<b>一个字都不会变</b>。
   */
  public ExternalConnectionHealth refreshHealth(UUID id, AccessService.Access actor) {
    connectionRow(id);
    persistHealth(id);
    return healthOf(id);
  }

  /**
   * 落库一次健康重算（**只**改这一个连接）。
   *
   * <p>⚠️ 与 {@link #refreshHealth} 的区别：这里**不**对外暴露，
   * 它是「写了一条同步记录之后」自动跟着走的一步。
   *
   * <p>🔴 这一步同时维护「最近一次同步」两列（{@code last_sync_at} /
   * {@code last_sync_record_id}）—— 取的是**这个连接自己**最新那条记录，
   * 同样是「一个对接的指针不指向别的对接」。
   */
  private void persistHealth(UUID connectionId) {
    var connection = connectionRow(connectionId);
    var connected = adapterConnected(connection.adapterCode());
    var health = computeHealth(connected, ownRecordStatuses(connectionId));
    var now = dbNow();
    var newest = newestOwnRecord(connectionId);
    if (newest == null) {
      // 还没有任何记录 ⇒ 不去动「最近一次同步」两列（避免绑 null 到 uuid / timestamptz）。
      sql.execute(
          "update platform_external_connection set health_status = ?, last_health_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz where id = ?",
          health.name(),
          now,
          now,
          connectionId);
    } else {
      sql.execute(
          "update platform_external_connection set health_status = ?, last_health_at = ?::timestamptz,"
              + " last_sync_at = ?::timestamptz, last_sync_record_id = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          health.name(),
          now,
          newest.get("finished_at", OffsetDateTime.class),
          newest.get("id", UUID.class),
          now,
          connectionId);
    }
  }

  private static String diagnose(
      ExternalHealthStatus health, boolean connected, int recordCount) {
    return switch (health) {
      case NOT_CONNECTED ->
          connected
              ? "最近一次同步未接入外部通道（根本没发出去）"
              : "外部通道未接入：没有与外部系统对话的通道";
      case UNKNOWN -> recordCount == 0 ? "还没有跑过同步 —— 未验证不等于坏了" : "尚未得出结论";
      case HEALTHY -> "最近一次同步成功";
      case DEGRADED -> "最近一次同步失败，但这个对接历史上成功过 —— 已经不稳了";
      case UNHEALTHY -> "这个对接的同步从未成功过，最近一次是失败";
    };
  }

  private static String recommend(ExternalHealthStatus health, boolean connected) {
    return switch (health) {
      case NOT_CONNECTED ->
          connected
              ? "先补上密钥引用与接口地址，再重跑这个任务"
              : "外部通道未接入（归 18.2.3 卷级）；"
                  + "可以先在外部系统跑完同步，再回这里登记一条「外部侧已完成」的同步记录";
      case UNKNOWN -> "先跑一次同步，让这个对接的健康状态有个真实结论";
      case HEALTHY -> "无需处理";
      case DEGRADED -> "看一眼这个对接最近失败的原因；不影响其它对接";
      case UNHEALTHY -> "检查这个对接的密钥引用 / 接口地址 / 外部系统侧状态；不影响其它对接";
    };
  }

  // ================================================================ 同步任务

  /** 同步任务列表（真筛选 + 真关键词）。 */
  public List<ExternalSyncTaskRow> taskRows(
      UUID connectionId, String taskType, String status, String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (connectionId != null) {
      where.append(" and t.connection_id = ?");
      params.add(connectionId);
    }
    if (taskType != null && !taskType.isBlank()) {
      where.append(" and t.task_type = ?");
      params.add(taskType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (t.name ilike ? or t.remark ilike ? or t.resource_ref ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return sql.fetch(
            "select t.id,t.connection_id,t.task_type,t.direction,t.name,t.resource_ref,"
                + " t.schedule_kind,t.schedule_expression,t.interval_seconds,t.enabled,t.status,"
                + " t.last_run_status,t.last_run_at,t.last_record_id,t.remark,"
                + " t.created_by,t.created_at,t.updated_at,"
                + " c.name as connection_name,c.adapter_code,c.category"
                + " from platform_external_sync_task t"
                + " join platform_external_connection c on c.id = t.connection_id"
                + where
                + " order by t.created_at",
            params.toArray())
        .map(ExternalIntegrationService::toTaskRow);
  }

  /** 一条同步任务。 */
  public ExternalSyncTaskRow taskRow(UUID id) {
    var row =
        sql.fetchOne(
            "select t.id,t.connection_id,t.task_type,t.direction,t.name,t.resource_ref,"
                + " t.schedule_kind,t.schedule_expression,t.interval_seconds,t.enabled,t.status,"
                + " t.last_run_status,t.last_run_at,t.last_record_id,t.remark,"
                + " t.created_by,t.created_at,t.updated_at,"
                + " c.name as connection_name,c.adapter_code,c.category"
                + " from platform_external_sync_task t"
                + " join platform_external_connection c on c.id = t.connection_id where t.id = ?",
            id);
    if (row == null) notFound("同步任务不存在");
    return toTaskRow(row);
  }

  /** 新增或修改一个同步任务配置。 */
  public ExternalSyncTaskRow saveTask(ExternalSyncTaskInput input, AccessService.Access actor) {
    var connectionId = input.connectionId();
    check(connectionId != null, "必须指定对接连接");
    var connection = connectionRow(connectionId);
    var taskType = ExternalTaskType.parse(input.taskType());
    check(taskType != null, "同步类型只能是 CATALOG_SYNC / INVENTORY_SYNC / SHIPMENT_TRACKING_SYNC / CERTIFICATE_SYNC / BI_DATASET_SYNC");
    var category = ExternalIntegrationCategory.parse(connection.category());
    check(
        category != null && taskType.supports(category),
        "同步类型 "
            + taskType.name()
            + "（"
            + taskType.label()
            + "）不属于「"
            + connection.categoryLabel()
            + "」这一类对接 —— 给"
            + connection.categoryLabel()
            + "配这个类型只会得到一个永远跑不通的任务");
    var direction =
        input.direction() == null || input.direction().isBlank()
            ? "PULL"
            : input.direction().trim().toUpperCase(Locale.ROOT);
    check(List.of("PULL", "PUSH").contains(direction), "同步方向只能是 PULL 或 PUSH");
    var name = required(input.name(), "任务名", 120);
    var resourceRef = optional(input.resourceRef(), "同步对象", 200);
    var scheduleKind =
        input.scheduleKind() == null || input.scheduleKind().isBlank()
            ? "MANUAL"
            : input.scheduleKind().trim().toUpperCase(Locale.ROOT);
    check(List.of("MANUAL", "INTERVAL", "CRON").contains(scheduleKind), "调度方式只能是 MANUAL / INTERVAL / CRON");
    var scheduleExpression = optional(input.scheduleExpression(), "调度表达式", 120);
    var intervalSeconds =
        input.intervalSeconds() == null
            ? null
            : BigDecimal.valueOf(count(input.intervalSeconds(), "间隔秒数", 1, 86400));
    if ("INTERVAL".equals(scheduleKind)) {
      check(intervalSeconds != null, "调度方式选了 INTERVAL 就必须给间隔秒数");
    }
    if ("CRON".equals(scheduleKind)) {
      check(!scheduleExpression.isEmpty(), "调度方式选了 CRON 就必须给调度表达式");
    }
    var enabled = input.enabled() == null || input.enabled();
    var status =
        input.status() == null || input.status().isBlank()
            ? "ACTIVE"
            : input.status().trim().toUpperCase(Locale.ROOT);
    check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
    var remark = optional(input.remark(), "备注", 500);

    var now = dbNow();
    var id = input.id();
    if (id == null) {
      var existing =
          sql.fetchOne(
              "select id from platform_external_sync_task where connection_id = ? and task_type = ?",
              connectionId,
              taskType.name());
      id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
      if (existing == null) {
        sql.execute(
            "insert into platform_external_sync_task(id,tenant_id,connection_id,task_type,direction,"
                + " name,resource_ref,schedule_kind,schedule_expression,interval_seconds,enabled,status,"
                + " last_run_status,remark,created_by,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?::numeric,?,?,'',?,?,?::timestamptz,?::timestamptz)",
            id,
            currentTenant(),
            connectionId,
            taskType.name(),
            direction,
            name,
            resourceRef,
            scheduleKind,
            scheduleExpression,
            intervalSeconds,
            enabled,
            status,
            remark,
            actor.userId(),
            now,
            now);
      } else {
        sql.execute(
            "update platform_external_sync_task set direction = ?, name = ?, resource_ref = ?,"
                + " schedule_kind = ?, schedule_expression = ?, interval_seconds = ?::numeric, enabled = ?,"
                + " status = ?, remark = ?, updated_at = ?::timestamptz where id = ?",
            direction,
            name,
            resourceRef,
            scheduleKind,
            scheduleExpression,
            intervalSeconds,
            enabled,
            status,
            remark,
            now,
            id);
      }
    } else {
      var updated =
          sql.execute(
              "update platform_external_sync_task set connection_id = ?, task_type = ?, direction = ?,"
                  + " name = ?, resource_ref = ?, schedule_kind = ?, schedule_expression = ?,"
                  + " interval_seconds = ?::numeric, enabled = ?, status = ?, remark = ?, updated_at = ?::timestamptz"
                  + " where id = ?",
              connectionId,
              taskType.name(),
              direction,
              name,
              resourceRef,
              scheduleKind,
              scheduleExpression,
              intervalSeconds,
              enabled,
              status,
              remark,
              now,
              id);
      if (updated == 0) notFound("同步任务不存在");
    }
    return taskRow(id);
  }

  /** 停用一个同步任务（**不删**：历史同步记录要能复现）。 */
  public ExternalSyncTaskRow disableTask(UUID id, AccessService.Access actor) {
    var updated =
        sql.execute(
            "update platform_external_sync_task set status = 'DISABLED', enabled = false,"
                + " updated_at = ?::timestamptz where id = ?",
            dbNow(),
            id);
    if (updated == 0) notFound("同步任务不存在");
    return taskRow(id);
  }

  // ================================================================ 同步记录

  /** 同步记录列表（真筛选 + 真关键词）。 */
  public List<ExternalSyncRecordRow> recordRows(
      UUID connectionId,
      UUID taskId,
      String status,
      String source,
      String keyword,
      Integer limit) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (connectionId != null) {
      where.append(" and r.connection_id = ?");
      params.add(connectionId);
    }
    if (taskId != null) {
      where.append(" and r.task_id = ?");
      params.add(taskId);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and r.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (source != null && !source.isBlank()) {
      where.append(" and r.source = ?");
      params.add(source.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (r.external_ref ilike ? or r.failure_reason ilike ? or r.provider_notice ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
    }
    var max = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
    params.add(max);
    return sql.fetch(
            "select r.id,r.connection_id,r.task_id,r.run_no,r.status,r.source,r.records_total,"
                + " r.records_succeeded,r.records_failed,r.external_ref,r.failure_reason,"
                + " r.failure_class,r.provider_notified,r.provider_notice,r.started_at,r.finished_at,"
                + " r.created_by,r.created_at,"
                + " c.name as connection_name,t.name as task_name"
                + " from platform_external_sync_record r"
                + " join platform_external_connection c on c.id = r.connection_id"
                + " join platform_external_sync_task t on t.id = r.task_id"
                + where
                + " order by r.finished_at desc, r.run_no desc limit ?",
            params.toArray())
        .map(ExternalIntegrationService::toRecordRow);
  }

  /** 一条同步记录。 */
  public ExternalSyncRecordRow recordRow(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id,r.connection_id,r.task_id,r.run_no,r.status,r.source,r.records_total,"
                + " r.records_succeeded,r.records_failed,r.external_ref,r.failure_reason,"
                + " r.failure_class,r.provider_notified,r.provider_notice,r.started_at,r.finished_at,"
                + " r.created_by,r.created_at,"
                + " c.name as connection_name,t.name as task_name"
                + " from platform_external_sync_record r"
                + " join platform_external_connection c on c.id = r.connection_id"
                + " join platform_external_sync_task t on t.id = r.task_id where r.id = ?",
            id);
    if (row == null) notFound("同步记录不存在");
    return toRecordRow(row);
  }

  /**
   * 🔴 **经由外部通道执行一次同步**。
   *
   * <p>外部通道未接入 ⇒ 适配器如实答 {@code NOT_CONNECTED} ⇒ 这里**照样落一条同步记录**
   * （本系统自己的账：谁在什么时候对哪个对接发起了一次同步、结论是什么），
   * 但 {@code status=NOT_CONNECTED}、{@code provider_notified=false}、
   * {@code provider_notice} 写明「未向外部系统登记」。
   * ⇒ **一条 {@code SUCCEEDED 且 provider_notified=false} 的行不可能存在**
   * （DB 层 CHECK 钉死），也**不可能**被读成「外部侧已登记过」。
   *
   * <p>🔴 写完记录后**只**重算<b>这一个连接</b>的健康状态 —— 别的连接一个字都不动。
   */
  public ExternalSyncRecordRow runSync(
      UUID taskId, AccessService.Access actor, String bearer) {
    var task = taskRow(taskId);
    if (!"ACTIVE".equals(task.status()) || !task.enabled()) {
      conflict("这个同步任务已停用（" + task.name() + "），先启用再跑");
    }
    var connection = connectionRow(task.connectionId());
    var adapter = adapters.find(connection.adapterCode());
    if (adapter == null) {
      conflict(
          "适配器 "
              + connection.adapterCode()
              + " 没有登记在适配器注册表里 —— 配置里的适配器代码与内置实现对不上");
    }
    var startedAt = dbNow();
    var outcome =
        adapter.executeSync(
            new ExternalSyncContext(
                connection.adapterCode(),
                connection.category(),
                connection.baseUrl(),
                connection.credentialRef(),
                connection.timeoutSeconds(),
                connection.maxAttempts(),
                bearer),
            new ExternalSyncCommand(
                task.taskType(),
                task.direction(),
                task.resourceRef(),
                task.scheduleExpression()));
    var finishedAt = dbNow();
    if (finishedAt.isBefore(startedAt)) finishedAt = startedAt;

    var recordId =
        writeRecord(
            connection.id(),
            task.id(),
            outcome,
            startedAt,
            finishedAt,
            actor.userId(),
            "DIRECT");
    touchTask(task.id(), outcome.status(), finishedAt, recordId);
    // 🔴 只重算**这一个连接**的健康状态。
    persistHealth(connection.id());
    return recordRow(recordId);
  }

  /**
   * 🔴 **登记一条「外部侧已完成」的同步**（人工登记；**没有**经过本系统的外部通道）。
   *
   * <p>这条路径是**今天就能用**的：外部系统那边跑完同步（运营在对方门户里点的），
   * 在这里补一条记录，**这个对接**的健康状态就如实反映了现状。
   *
   * <p>🔴 人工登记的行**永远**是 {@code source=EXTERNAL} 且 {@code provider_notified=false} ——
   * 它记的是「本系统没有与外部系统核对过」，不能被读成「已登记」。
   */
  public ExternalSyncRecordRow recordExternal(
      ExternalSyncRecordInput input, AccessService.Access actor) {
    var taskId = input.taskId();
    check(taskId != null, "必须指定同步任务");
    var task = taskRow(taskId);
    var status = ExternalSyncStatus.parse(input.status());
    check(
        status == ExternalSyncStatus.SUCCEEDED
            || status == ExternalSyncStatus.FAILED
            || status == ExternalSyncStatus.TIMEOUT,
        "人工登记的结论只能是 SUCCEEDED / FAILED / TIMEOUT"
            + "（NOT_CONNECTED 的意思是「根本没发出去」，人工登记不了）");
    var recordsTotal = records(input.recordsTotal(), "处理条数");
    var recordsSucceeded = records(input.recordsSucceeded(), "成功条数");
    var recordsFailed = records(input.recordsFailed(), "失败条数");
    check(
        recordsSucceeded.compareTo(recordsTotal) <= 0 && recordsFailed.compareTo(recordsTotal) <= 0,
        "成功条数与失败条数都不能超过处理条数");
    var failureReason = optional(input.failureReason(), "失败原因", 500);
    var failureClass = optional(input.failureClass(), "失败分类", 40).toUpperCase(Locale.ROOT);
    check(
        failureClass.isEmpty()
            || List.of("REJECTED", "TIMEOUT", "UNAVAILABLE", "NOT_CONFIGURED", "DATA_ERROR")
                .contains(failureClass),
        "失败分类只能是 REJECTED / TIMEOUT / UNAVAILABLE / NOT_CONFIGURED / DATA_ERROR");
    var externalRef = optional(input.externalRef(), "外部单号", 200);
    var providerNotice =
        optional(input.providerNotice(), "未核对说明", 500);
    if (providerNotice.isEmpty()) {
      providerNotice = "外部侧已完成，人工登记进本系统台账；本系统**未**与外部系统核对过";
    }
    var startedAt = parseTime(input.startedAt(), "开始时间");
    var finishedAt = parseTime(input.finishedAt(), "结束时间");
    var now = dbNow();
    if (startedAt == null) startedAt = now;
    if (finishedAt == null) finishedAt = now;
    if (finishedAt.isBefore(startedAt)) {
      check(false, "结束时间不能早于开始时间");
    }
    var outcome =
        new ExternalSyncOutcome(
            status.name(),
            recordsTotal,
            recordsSucceeded,
            recordsFailed,
            externalRef,
            failureReason,
            failureClass,
            false,
            providerNotice);
    var recordId =
        writeRecord(
            task.connectionId(),
            task.id(),
            outcome,
            startedAt,
            finishedAt,
            actor.userId(),
            "EXTERNAL");
    touchTask(task.id(), status.name(), finishedAt, recordId);
    // 🔴 只重算**这一个连接**的健康状态。
    persistHealth(task.connectionId());
    return recordRow(recordId);
  }

  /** 写一条同步记录（**只追加**），返回记录 id。 */
  private UUID writeRecord(
      UUID connectionId,
      UUID taskId,
      ExternalSyncOutcome outcome,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      UUID actorUserId,
      String source) {
    var runNo =
        sql.fetchOne(
                "select coalesce(max(run_no),0) + 1 from platform_external_sync_record"
                    + " where task_id = ?",
                taskId)
            .get(0, Integer.class);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_external_sync_record(id,tenant_id,connection_id,task_id,run_no,status,"
            + " source,records_total,records_succeeded,records_failed,external_ref,failure_reason,"
            + " failure_class,provider_notified,provider_notice,started_at,finished_at,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz)",
        id,
        currentTenant(),
        connectionId,
        taskId,
        runNo,
        outcome.status(),
        source,
        records(outcome.recordsTotal(), "处理条数"),
        records(outcome.recordsSucceeded(), "成功条数"),
        records(outcome.recordsFailed(), "失败条数"),
        optional(outcome.externalRef(), "外部单号", 200),
        optional(outcome.failureReason(), "失败原因", 500),
        optional(outcome.failureClass(), "失败分类", 40),
        outcome.providerNotified(),
        optional(outcome.providerNotice(), "未核对说明", 500),
        startedAt,
        finishedAt,
        actorUserId,
        dbNow());
    return id;
  }

  /** 更新任务的「最近一次同步」指针（**只**改这一个任务）。 */
  private void touchTask(
      UUID taskId, String status, OffsetDateTime at, UUID recordId) {
    sql.execute(
        "update platform_external_sync_task set last_run_status = ?, last_run_at = ?::timestamptz,"
            + " last_record_id = ?, updated_at = ?::timestamptz where id = ?",
        status,
        at,
        recordId,
        at,
        taskId);
  }

  // ================================================================ 上游（2 渠道机制）

  /** 上游 2.1 平台目录（**只读**；读不到 ⇒ 503）。 */
  public List<UpstreamChannelPlatformRow> upstreamPlatforms(String bearer) {
    return channel.platforms(bearer).stream()
        .map(
            p ->
                new UpstreamChannelPlatformRow(
                    p.id(), p.code(), p.name(), p.integration(), p.supportStatus()))
        .toList();
  }

  /** 上游 2.3 店铺（**只读**；读不到 ⇒ 503）。 */
  public List<UpstreamChannelShopRow> upstreamShops(String bearer) {
    return channel.shops(bearer).stream()
        .map(
            s ->
                new UpstreamChannelShopRow(
                    s.id(),
                    s.code(),
                    s.name(),
                    s.platformCode(),
                    s.platformName(),
                    s.status(),
                    s.lastSyncAt() == null ? null : s.lastSyncAt().atOffset(java.time.ZoneOffset.UTC)))
        .toList();
  }

  // ================================================================ 行映射

  private static ExternalIntegrationCategory categoryOfAdapter(String adapterCode) {
    return switch (adapterCode) {
      case "ALIBABA_1688", "TAOBAO" -> ExternalIntegrationCategory.SUPPLY_SOURCE;
      case "OVERSEAS_WAREHOUSE" -> ExternalIntegrationCategory.OVERSEAS_WAREHOUSE;
      case "LOGISTICS_CARRIER" -> ExternalIntegrationCategory.LOGISTICS_CARRIER;
      case "CERTIFICATION_AGGREGATOR" -> ExternalIntegrationCategory.CERTIFICATION_AGGREGATOR;
      case "SELF_HOSTED_BI" -> ExternalIntegrationCategory.SELF_HOSTED_BI;
      default -> null;
    };
  }

  private static String adapterLabel(String adapterCode) {
    return switch (adapterCode) {
      case "ALIBABA_1688" -> "1688 开放平台";
      case "TAOBAO" -> "淘宝开放平台";
      case "OVERSEAS_WAREHOUSE" -> "海外仓服务商";
      case "LOGISTICS_CARRIER" -> "物流商";
      case "CERTIFICATION_AGGREGATOR" -> "聚合认证服务";
      case "SELF_HOSTED_BI" -> "自建 BI";
      default -> adapterCode;
    };
  }

  private static ExternalConnectionRow toConnectionRow(org.jooq.Record row) {
    var category = ExternalIntegrationCategory.parse(row.get("category", String.class));
    var adapterCode = row.get("adapter_code", String.class);
    var status = row.get("status", String.class);
    var health = ExternalHealthStatus.parse(row.get("health_status", String.class));
    var credentialRef = row.get("credential_ref", String.class);
    return new ExternalConnectionRow(
        row.get("id", UUID.class),
        category == null ? row.get("category", String.class) : category.name(),
        category == null ? row.get("category", String.class) : category.label(),
        adapterCode,
        adapterLabel(adapterCode),
        row.get("name", String.class),
        row.get("base_url", String.class),
        credentialRef,
        row.get("credential_hint", String.class),
        credentialRef != null && !credentialRef.isBlank(),
        row.get("channel_platform_code", String.class),
        row.get("channel_shop_id", UUID.class),
        status,
        "ACTIVE".equals(status) ? "启用" : "已停用",
        health == null ? row.get("health_status", String.class) : health.name(),
        health == null ? row.get("health_status", String.class) : health.label(),
        row.get("last_health_at", OffsetDateTime.class),
        row.get("last_sync_at", OffsetDateTime.class),
        row.get("last_sync_record_id", UUID.class),
        row.get("timeout_seconds", Integer.class),
        row.get("max_attempts", Integer.class),
        row.get("task_count", Long.class),
        row.get("record_count", Long.class),
        row.get("remark", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ExternalSyncTaskRow toTaskRow(org.jooq.Record row) {
    var taskType = ExternalTaskType.parse(row.get("task_type", String.class));
    var direction = ExternalSyncDirection.parse(row.get("direction", String.class));
    var status = row.get("status", String.class);
    var lastRunStatus = row.get("last_run_status", String.class);
    var lastRun = ExternalSyncStatus.parse(lastRunStatus);
    var adapterCode = row.get("adapter_code", String.class);
    var category = ExternalIntegrationCategory.parse(row.get("category", String.class));
    return new ExternalSyncTaskRow(
        row.get("id", UUID.class),
        row.get("connection_id", UUID.class),
        row.get("connection_name", String.class),
        adapterCode,
        category == null ? row.get("category", String.class) : category.name(),
        taskType == null ? row.get("task_type", String.class) : taskType.name(),
        taskType == null ? row.get("task_type", String.class) : taskType.label(),
        direction == null ? row.get("direction", String.class) : direction.name(),
        direction == null ? row.get("direction", String.class) : direction.label(),
        row.get("name", String.class),
        row.get("resource_ref", String.class),
        row.get("schedule_kind", String.class),
        row.get("schedule_expression", String.class),
        row.get("interval_seconds", BigDecimal.class),
        row.get("enabled", Boolean.class),
        status,
        "ACTIVE".equals(status) ? "启用" : "已停用",
        lastRunStatus,
        lastRun == null ? (lastRunStatus.isEmpty() ? "还没跑过" : lastRunStatus) : lastRun.label(),
        row.get("last_run_at", OffsetDateTime.class),
        row.get("last_record_id", UUID.class),
        row.get("remark", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ExternalSyncRecordRow toRecordRow(org.jooq.Record row) {
    var status = ExternalSyncStatus.parse(row.get("status", String.class));
    var source = ExternalSyncSource.parse(row.get("source", String.class));
    return new ExternalSyncRecordRow(
        row.get("id", UUID.class),
        row.get("connection_id", UUID.class),
        row.get("connection_name", String.class),
        row.get("task_id", UUID.class),
        row.get("task_name", String.class),
        row.get("run_no", Integer.class),
        status == null ? row.get("status", String.class) : status.name(),
        status == null ? row.get("status", String.class) : status.label(),
        source == null ? row.get("source", String.class) : source.name(),
        source == null ? row.get("source", String.class) : source.label(),
        row.get("records_total", BigDecimal.class),
        row.get("records_succeeded", BigDecimal.class),
        row.get("records_failed", BigDecimal.class),
        row.get("external_ref", String.class),
        row.get("failure_reason", String.class),
        row.get("failure_class", String.class),
        row.get("provider_notified", Boolean.class),
        row.get("provider_notice", String.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }
}
