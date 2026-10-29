package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 1.1.1.3 审批待办（工作台只读聚合层）的**业务行为**。
 *
 * <p>多租户三断言在 {@link ApprovalTodoTenancyIntegrationTest} 里单独实跑，本类管行为。
 *
 * <p>本类刻意盯住三件事：
 *
 * <ol>
 *   <li><b>与上游清单等价</b>——工作台不能自己长出一套「归我」的判定，
 *       两边给出的待办清单必须逐条一致（上游是 17.4.2.1 的权威）；</li>
 *   <li><b>阈值是真的</b>——「多久算超时」来自节点上配的 {@code approval_flow_escalation.timeout_hours}，
 *       「落在哪一档金额」来自流程上配的 {@code approval_flow_amount_branch}，
 *       两者都不是硬编码常量；</li>
 *   <li><b>分档是整集合的计数</b>——按某一维筛完之后，那一维的分档计数仍然是它自己的真实条数。</li>
 * </ol>
 *
 * <p>🔴 <b>时间基准取数据库自己的时钟</b>（{@link #dbNow()}），而且<b>每个用例重新取一次</b>；
 * 停留时长的断言在<b>服务端判定所用的那个钟域</b>里比（服务端是 DB 侧
 * {@code approval_dwell_seconds(now(), node_entered_at)}，两端都在 PG 的时钟域里，
 * 所以夹具的起点也必须取 PG 的时钟）—— 跨钟域比，等于假设两台钟准得一模一样：
 * 差 1 毫秒就足以把「停了 10800 秒」压成 10799 秒，让超时判定在边界上翻车
 * （2026-10-27 的 L2 批次闸就是这么红的：实际 7199 vs 期望 ≥ 7200）。
 */
class ApprovalTodoIntegrationTest extends IdentityIntegrationTestBase {

  private static final String INTERNAL = "test-key";

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  @BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate approval_flow_escalation, approval_flow_amount_branch,"
              + " approval_flow_node, approval_trigger_rule, approval_rule_set,"
              + " approval_instance_action, approval_instance,"
              + " approval_flow, approval_position");
    }
  }

  // ------------------------------------------------------------------
  // 与上游清单等价
  // ------------------------------------------------------------------

  @Test
  void 工作台清单与上游待我审批清单逐条一致() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "EQ" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "eq-" + tag.toLowerCase(), role);
    var flow = flowFor(owner, "等价流程 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    submit(approver, flow, "trade", "EQ-" + tag + "-1", "等价单 " + tag + "-1", "USD", "100");
    submit(approver, flow, "warehouse", "EQ-" + tag + "-2", "等价单 " + tag + "-2", "USD", "200");

    var mine = todos(approver, "").get("items");
    var upstream = inbox(approver, "").get("items");

    assertThat(ids(mine)).containsExactlyInAnyOrderElementsOf(ids(upstream));
    assertThat(ids(mine)).hasSameSizeAs(ids(upstream));
    for (var item : mine) {
      var other = findById(upstream, item.get("id").asText());
      assertThat(other).as("上游清单必须逐条对得上").isNotNull();
      assertThat(item.get("instanceNo").asText()).isEqualTo(other.get("instanceNo").asText());
      assertThat(item.get("currentNodeKey").asText()).isEqualTo(other.get("currentNodeKey").asText());
      assertThat(item.get("amount").decimalValue()).isEqualByComparingTo(other.get("amount").decimalValue());
      assertThat(item.get("sourceSystem").asText()).isEqualTo(other.get("sourceSystem").asText());
    }
  }

  // ------------------------------------------------------------------
  // 筛选：单据类型 / 业务域 / 金额区间
  // ------------------------------------------------------------------

  @Test
  void 按单据类型筛选只返回该类型且分档仍是整集合的() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "DT" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "dt-" + tag.toLowerCase(), role);
    var purchase = flowFor(owner, "采购流 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    var payment = flowFor(owner, "付款流 " + tag, "PURCHASE_PAYMENT", role, List.of(), List.of());
    submit(approver, purchase, "trade", "DT-" + tag + "-1", "采购单 " + tag, "USD", "100");
    submit(approver, payment, "trade", "DT-" + tag + "-2", "付款单 " + tag, "USD", "200");

    var filtered = todos(approver, "?documentTypeCode=PURCHASE_PAYMENT");
    assertThat(titles(filtered.get("items"))).containsExactly("付款单 " + tag);
    assertThat(filtered.get("total").asLong()).isEqualTo(1);

    // 分档是**整集合**的计数：筛了单据类型之后，单据类型这一维仍然给出两档的真实条数
    var buckets = filtered.get("documentTypes");
    assertThat(bucket(buckets, "PURCHASE_ORDER")).isEqualTo(1);
    assertThat(bucket(buckets, "PURCHASE_PAYMENT")).isEqualTo(1);
  }

  @Test
  void 按业务域筛选与业务域分档都对得上() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "BD" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "bd-" + tag.toLowerCase(), role);
    var flow = flowFor(owner, "业务域流 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    submit(approver, flow, "trade", "BD-" + tag + "-1", "贸易单 " + tag, "USD", "100");
    submit(approver, flow, "warehouse", "BD-" + tag + "-2", "仓储单 " + tag, "USD", "200");
    submit(approver, flow, "trade", "BD-" + tag + "-3", "贸易单2 " + tag, "USD", "300");

    var filtered = todos(approver, "?sourceSystem=trade");
    assertThat(titles(filtered.get("items"))).containsExactlyInAnyOrder("贸易单 " + tag, "贸易单2 " + tag);
    assertThat(filtered.get("total").asLong()).isEqualTo(2);

    var buckets = filtered.get("businessDomains");
    assertThat(bucket(buckets, "trade")).isEqualTo(2);
    assertThat(bucket(buckets, "warehouse")).isEqualTo(1);
  }

  @Test
  void 金额区间筛选只命中金额落在区间的单据且没有金额的单据不命中() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "AM" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "am-" + tag.toLowerCase(), role);
    var flow = flowFor(owner, "金额流 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    submit(approver, flow, "trade", "AM-" + tag + "-1", "小额单 " + tag, "USD", "100");
    submit(approver, flow, "trade", "AM-" + tag + "-2", "大额单 " + tag, "USD", "5000");
    // 变更类单据本来就没有金额（库上金额与币种同进同出）
    submit(approver, flow, "trade", "AM-" + tag + "-3", "无额单 " + tag, null, null);

    var filtered = todos(approver, "?minAmount=1000");
    assertThat(titles(filtered.get("items"))).containsExactly("大额单 " + tag);
    assertThat(filtered.get("total").asLong()).isEqualTo(1);

    var ranged = todos(approver, "?minAmount=50&maxAmount=200");
    assertThat(titles(ranged.get("items"))).containsExactly("小额单 " + tag);

    // 无金额的单据在任何金额区间筛选下都不该出现
    assertThat(titles(todos(approver, "").get("items"))).contains("无额单 " + tag);
    assertThat(titles(ranged.get("items"))).doesNotContain("无额单 " + tag);
  }

  // ------------------------------------------------------------------
  // 阈值是真的：超时口径 + 金额阈值分支
  // ------------------------------------------------------------------

  @Test
  void 超时判定用节点上配的时限且没配时限的单据判不了超时() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "OD" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "od-" + tag.toLowerCase(), role);
    var escalations =
        List.<Map<String, Object>>of(
            Map.of(
                "nodeKey", "R1",
                "timeoutAction", "ESCALATE",
                "timeoutHours", 1,
                "escalateApproverType", "ROLE",
                "escalateRoleId", role.toString(),
                "description", "一小时未处理即升级"));
    var withTimeout = flowFor(owner, "有阈值流 " + tag, "PURCHASE_ORDER", role, List.of(), escalations);
    var withoutTimeout = flowFor(owner, "无阈值流 " + tag, "PURCHASE_PAYMENT", role, List.of(), List.of());
    var overdueId =
        submit(approver, withTimeout, "trade", "OD-" + tag + "-1", "超时单 " + tag, "USD", "100");
    submit(approver, withoutTimeout, "trade", "OD-" + tag + "-2", "判不了单 " + tag, "USD", "100");

    // 把「超时单」的停留起点往回拨三小时（写的是 timestamptz，用 OffsetDateTime 绑定）
    var tenant = tenantOf(approver);
    // 🔴 停留起点取**数据库自己的时钟**，不是 JVM 墙钟。
    //
    // 分域判定：生产侧 dwell/overdue 的两端本来就在**同一个钟域**里 ——
    //   approval_dwell_seconds(now(), i.node_entered_at)：终点是 PG 的 now()，起点是库里那一列。
    // 夹具却拿 JVM 墙钟去写起点 ⇒ 两端分属两个钟域（宿主钟 vs 容器钟）。
    // 两个钟差不到 1 秒就正好压在 floor() 的取整边界上：真实 10800.0s 落成 10799
    // ⇒ overdue = 10799 - 3600 = 7199 < 7200，断言翻车（2026-10-27 的 L2 批次闸）。
    // 两端同域后，落点只取决于「真实流逝了多久」，与两台机器的钟差无关 ⇒ 判定确定。
    var backdated = dbNow().atOffset(ZoneOffset.UTC).minusHours(3).truncatedTo(ChronoUnit.MICROS);
    tx()
        .run(
            tenant,
            () ->
                sql.execute(
                    "update approval_instance set node_entered_at = ?::timestamptz where id = ?",
                    backdated,
                    overdueId));
    // 写回的值必须原样读回来（用 Timestamp.valueOf 会静默偏 −28800s）
    OffsetDateTime readBack =
        tx()
            .run(
                tenant,
                () ->
                    sql.fetchOne(
                            "select node_entered_at from approval_instance where id = ?", overdueId)
                        .get("node_entered_at", OffsetDateTime.class));
    assertThat(readBack.toInstant()).as("写回的停留起点必须与写入值一致").isEqualTo(backdated.toInstant());

    // 判定时刻也在**数据库钟域**里：请求前后各读一次 DB 时钟，把服务端的判定夹在中间。
    // 服务端用的是 PG 的 now()，所以这里也必须用 PG 的时钟读，前后夹逼才成立。
    var before = dbNow().atOffset(ZoneOffset.UTC);
    var items = todos(approver, "").get("items");
    var after = dbNow().atOffset(ZoneOffset.UTC);

    var overdue = findById(items, overdueId.toString());
    assertThat(overdue.get("timeoutSeconds").asLong()).isEqualTo(3600);
    assertThat(overdue.get("overdueSeconds").asLong()).isGreaterThanOrEqualTo(7200);
    assertThat(overdue.get("dwellSeconds").asLong()).isGreaterThanOrEqualTo(10800);

    // 停留起点必须是夹具写进去的那个值（服务端读的就是这一列，不是别的时刻）
    assertThat(Instant.parse(overdue.get("nodeEnteredAt").asText()))
        .as("服务端读到的停留起点必须与夹具写入值逐位一致")
        .isEqualTo(backdated.toInstant());
    // 停留时长与服务端**同一套取整规则**、同一个钟域：请求前后的 DB 时钟把服务端的判定夹在中间
    assertThat(overdue.get("dwellSeconds").asLong())
        .as("停留时长必须等于「判定时刻 − 停留起点」，与服务端同一套向下取整规则")
        .isBetween(dwellFloor(before, backdated), dwellFloor(after, backdated));
    // 超时秒数是服务端用同一次判定的停留时长减掉节点上配的时限算出来的（不是另起一个口径）
    assertThat(overdue.get("overdueSeconds").asLong())
        .as("超时秒数必须等于「停留时长 − 节点上配的时限」")
        .isEqualTo(overdue.get("dwellSeconds").asLong() - 3600);

    var unjudgeable = findById(items, itemIdOf(items, "判不了单 " + tag));
    assertThat(unjudgeable.get("timeoutSeconds").isNull()).as("没配时限就判不了超时").isTrue();
    assertThat(unjudgeable.get("overdueSeconds").isNull()).isTrue();

    // 「只看已超时」按配置判，不是按硬编码常量判
    var onlyOverdue = todos(approver, "?overdueOnly=true");
    assertThat(titles(onlyOverdue.get("items"))).containsExactly("超时单 " + tag);
    assertThat(onlyOverdue.get("summary").get("overdueTotal").asLong()).isEqualTo(1);
    assertThat(onlyOverdue.get("summary").get("unconfiguredTimeoutTotal").asLong()).isEqualTo(1);
  }

  @Test
  void 金额阈值分支按金额比出落在哪一档且没落在任何一档时为空() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "TB" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "tb-" + tag.toLowerCase(), role);
    var branches =
        List.<Map<String, Object>>of(
            Map.of(
                "branchName", "小额档 " + tag,
                "currencyCode", "USD",
                "minAmount", new BigDecimal("0"),
                "maxAmount", new BigDecimal("1000"),
                "targetNodeKey", "R1",
                "priority", 10,
                "description", "一千以下走小额档"));
    var flow = flowFor(owner, "阈值分支流 " + tag, "PURCHASE_ORDER", role, branches, List.of());
    submit(approver, flow, "trade", "TB-" + tag + "-1", "落在档内 " + tag, "USD", "500");
    submit(approver, flow, "trade", "TB-" + tag + "-2", "落在档外 " + tag, "USD", "5000");

    var items = todos(approver, "").get("items");
    var inside = findById(items, itemIdOf(items, "落在档内 " + tag));
    assertThat(inside.get("amountThresholdName").asText()).isEqualTo("小额档 " + tag);
    assertThat(inside.get("amountThresholdMin").decimalValue()).isEqualByComparingTo("0");
    assertThat(inside.get("amountThresholdMax").decimalValue()).isEqualByComparingTo("1000");
    assertThat(inside.get("amountThresholdCurrency").asText()).isEqualTo("USD");

    var outside = findById(items, itemIdOf(items, "落在档外 " + tag));
    assertThat(outside.get("amountThresholdName").isNull())
        .as("没落在任何一档就报空，不许硬凑一档")
        .isTrue();
  }

  @Test
  void 按金额阈值分支筛选只返回该档且分档仍是整集合的() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "BF" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "bf-" + tag.toLowerCase(), role);
    var branches =
        List.<Map<String, Object>>of(
            branch("小额档 " + tag, "USD", "0", "1000", "R1", 10),
            // 上界留空 = 「一千以上都走这一档」；Map.of 不接受 null 值，所以这里用可变映射
            branch("大额档 " + tag, "USD", "1000", null, "R1", 20));
    var flow = flowFor(owner, "分支筛选流 " + tag, "PURCHASE_ORDER", role, branches, List.of());
    submit(approver, flow, "trade", "BF-" + tag + "-1", "小额单 " + tag, "USD", "500");
    submit(approver, flow, "trade", "BF-" + tag + "-2", "大额单 " + tag, "USD", "5000");
    // 变更类单据没有金额，不落在任何一档
    submit(approver, flow, "trade", "BF-" + tag + "-3", "无额单 " + tag, null, null);

    // 按档位筛：只返回该档的单据。
    // ⚠️ 档名里的中文**故意不预先 URL 编码**：MockMvc 的 URL 模板会自己 encode 一次，
    // 预先编码过的 %XX 会被二次编码成 %25XX，服务端解出来的是字面量 "%E5%B0%8F" 而不是中文。
    var filtered = todos(approver, "?amountBranchName=小额档 " + tag);
    assertThat(titles(filtered.get("items"))).containsExactly("小额单 " + tag);
    assertThat(filtered.get("total").asLong()).isEqualTo(1);

    // 筛出来的那一条，行上显示的档位必须就是这个档位（同一口径，不是两个算法）
    var item = filtered.get("items").get(0);
    assertThat(item.get("amountThresholdName").asText()).isEqualTo("小额档 " + tag);

    // 分档是**整集合**的计数：筛了某一档之后，这一维仍然给出两档的真实条数
    var buckets = filtered.get("amountBranches");
    assertThat(bucket(buckets, "小额档 " + tag)).isEqualTo(1);
    assertThat(bucket(buckets, "大额档 " + tag)).isEqualTo(1);
    // 没有金额的单据不在任何一档里，不许被收成一档「未分档」
    assertThat(bucketCodes(buckets)).doesNotContain("null");

    // 按一个不存在的档位筛 → 零条，且不是报错
    var none = todos(approver, "?amountBranchName=没有这一档 " + tag);
    assertThat(none.get("items").isEmpty()).isTrue();
    assertThat(none.get("total").asLong()).isEqualTo(0);
  }

  // ------------------------------------------------------------------
  // 分页 / 参数校验 / 能力声明
  // ------------------------------------------------------------------

  @Test
  void 真分页三页不重叠且合计不变() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "PG" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "pg-" + tag.toLowerCase(), role);
    var flow = flowFor(owner, "分页流 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    for (var i = 0; i < 5; i++) {
      submit(approver, flow, "trade", "PG-" + tag + "-" + i, "分页单 " + tag + "-" + i, "USD", "100");
    }

    var seen = new ArrayList<String>();
    for (var page = 0; page < 3; page++) {
      var body = todos(approver, "?size=2&page=" + page);
      assertThat(body.get("total").asLong()).isEqualTo(5);
      seen.addAll(ids(body.get("items")));
    }
    assertThat(seen).hasSize(5).doesNotHaveDuplicates();
  }

  @Test
  void 非法排序键与非法分页参数一律400() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "IV" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "iv-" + tag.toLowerCase(), role);

    request(approver, "GET", "/identity/approval-todos?sort=NOT_A_SORT", null, 400);
    request(approver, "GET", "/identity/approval-todos?page=-1", null, 400);
    request(approver, "GET", "/identity/approval-todos?size=0", null, 400);
    request(approver, "GET", "/identity/approval-todos?size=999", null, 400);
    request(approver, "GET", "/identity/approval-todos?minAmount=-1", null, 400);
    request(approver, "GET", "/identity/approval-todos?minAmount=100&maxAmount=10", null, 400);
    request(approver, "GET", "/identity/approval-todos?minDwellSeconds=-5", null, 400);
  }

  @Test
  void 能力声明把审批动作报成不可用并写明法人级阈值未配置() throws Exception {
    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "CP" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "cp-" + tag.toLowerCase(), role);

    var capabilities = todos(approver, "").get("capabilities");

    // 审批动作在工作台**不可用**：它们归 17.4.2.1
    for (var code : List.of("APPROVE", "REJECT", "TRANSFER")) {
      var action = action(capabilities.get("actions"), code);
      assertThat(action.get("available").asBoolean())
          .as("工作台不执行审批动作：" + code)
          .isFalse();
      assertThat(action.get("ownerModule").asText()).isEqualTo("17.4.2.1");
      assertThat(action.get("reason").asText()).contains("17");
    }
    // 跳转动作可用
    assertThat(action(capabilities.get("actions"), "OPEN_APPROVAL_DETAIL").get("available").asBoolean())
        .isTrue();

    // 法人级金额阈值：阈值未配置，如实报不可用并写明缺什么
    var legalEntityThreshold = filter(capabilities.get("filters"), "LEGAL_ENTITY_AMOUNT_THRESHOLD");
    assertThat(legalEntityThreshold.get("available").asBoolean()).isFalse();
    assertThat(legalEntityThreshold.get("reason").asText()).contains("阈值未配置");

    // 单据类型与业务域两档永远可用
    assertThat(filter(capabilities.get("filters"), "DOCUMENT_TYPE").get("available").asBoolean())
        .isTrue();
    assertThat(filter(capabilities.get("filters"), "BUSINESS_DOMAIN").get("available").asBoolean())
        .isTrue();
  }

  @Test
  void 申请人名解析不到时为空但申请人id仍可信() throws Exception {
    var owner = login();
    var tag = suffix();
    // 这个角色**没有** identity.member.read：审批人读不到成员名是常态，不是 bug
    var role = roleId(owner, "AN" + tag, perms("read", "approve", "transfer"));
    var approver = approver(owner, "an-" + tag.toLowerCase(), role);
    var flow = flowFor(owner, "申请人流 " + tag, "PURCHASE_ORDER", role, List.of(), List.of());
    submit(approver, flow, "trade", "AN-" + tag + "-1", "申请人单 " + tag, "USD", "100");

    var item = todos(approver, "").get("items").get(0);
    assertThat(item.get("applicantId").asText()).isNotBlank();
    assertThat(item.get("applicantName").isNull())
        .as("没有成员读权限时解析不出用户名，但 id 仍然可信")
        .isTrue();
  }

  @Test
  void 无令牌一律401且没有审批读权限的角色403() throws Exception {
    http.perform(get("/identity/approval-todos")).andExpect(status().isUnauthorized());

    var owner = login();
    var tag = suffix();
    var role = roleId(owner, "NP" + tag, List.of());
    var stranger = approver(owner, "np-" + tag.toLowerCase(), role);
    request(stranger, "GET", "/identity/approval-todos", null, 403);
  }

  // ------------------------------------------------------------------
  // 小工具
  // ------------------------------------------------------------------

  /**
   * 「判定时刻 − 停留起点」的秒数，与服务端 {@code approval_dwell_seconds} <b>同一个算法</b>：
   * {@code floor(extract(epoch FROM (clock - entered)))}。
   *
   * <p>服务端那把尺子就是 {@code floor(...)}（向下取整到秒），所以这里也必须向下取整：
   * 用「四舍五入」或「不取整」去算期望值，就等于拿另一把尺子去量服务端量出来的东西。
   *
   * <p>两端都取<b>数据库自己的时钟</b>（{@link #dbNow()}）：服务端判超时用的就是 PG 的
   * {@code now()}，夹具的起点也必须在同一个钟域里 —— 跨钟域比，等于假设两台钟准得一模一样。
   */
  private static long dwellFloor(OffsetDateTime clock, OffsetDateTime entered) {
    return Math.max(0L, Duration.between(entered.toInstant(), clock.toInstant()).getSeconds());
  }

  private List<String> perms(String... actions) {
    var out = new ArrayList<String>();
    for (var a : actions) out.add("identity.approval_inbox." + a);
    return out;
  }

  private Map<String, Object> node(String key, int order, String type, Object ref) {
    var n = new LinkedHashMap<String, Object>();
    n.put("nodeKey", key);
    n.put("nodeName", "节点 " + key);
    n.put("nodeOrder", order);
    n.put("stageIndex", order);
    n.put("approverType", type);
    n.put("approvalMode", "SERIAL");
    if ("ROLE".equals(type)) n.put("roleId", ref);
    if ("USER".equals(type)) n.put("membershipId", ref);
    if ("POSITION".equals(type)) n.put("positionId", ref);
    return n;
  }

  private UUID roleId(String token, String code, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of("code", code, "name", "角色 " + code, "status", "ACTIVE", "permissions", permissions),
                200)
            .asText());
  }

  private String approver(String owner, String username, UUID role) throws Exception {
    request(
            owner,
            "POST",
            "/identity/members",
            memberInput(username, null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
            200);
    return loginUser(username);
  }

  /** 建一条生效流程，首节点是指定角色的 ROLE 节点。 */
  private UUID flowFor(
      String token,
      String flowName,
      String documentType,
      UUID role,
      List<Map<String, Object>> branches,
      List<Map<String, Object>> escalations)
      throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/approval-flows",
                Map.of(
                    "documentTypeCode", documentType,
                    "flowName", flowName,
                    "status", "ACTIVE",
                    "description", "",
                    "nodes", List.of(node("R1", 1, "ROLE", role)),
                    "branches", branches,
                    "escalations", escalations),
                200)
            .asText());
  }

  /** 走上游的提交入口建一条待办（内部密钥 + 已认证会话）。 */
  private UUID submit(
      String token,
      UUID flowId,
      String source,
      String ref,
      String title,
      String currency,
      String amount)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("flowId", flowId.toString());
    body.put("sourceSystem", source);
    body.put("sourceReferenceId", ref);
    body.put("subjectTitle", title);
    if (currency != null) body.put("currencyCode", currency);
    if (amount != null) body.put("amount", new BigDecimal(amount));
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/identity/approval-instances")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Key", INTERNAL)
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().is(200))
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  private JsonNode todos(String token, String query) throws Exception {
    return request(token, "GET", "/identity/approval-todos" + query, null, 200);
  }

  private JsonNode inbox(String token, String query) throws Exception {
    return request(token, "GET", "/identity/my-approvals" + query, null, 200);
  }

  private List<String> ids(JsonNode items) {
    var out = new ArrayList<String>();
    for (var item : items) out.add(item.get("id").asText());
    return out;
  }

  private List<String> titles(JsonNode items) {
    var out = new ArrayList<String>();
    for (var item : items) out.add(item.get("subjectTitle").asText());
    return out;
  }

  private String itemIdOf(JsonNode items, String title) {
    for (var item : items) {
      if (title.equals(item.get("subjectTitle").asText())) return item.get("id").asText();
    }
    throw new AssertionError("清单里没有这条：" + title);
  }

  private JsonNode findById(JsonNode items, String id) {
    for (var item : items) {
      if (id.equals(item.get("id").asText())) return item;
    }
    return null;
  }

  private long bucket(JsonNode buckets, String code) {
    for (var bucket : buckets) {
      if (code.equals(bucket.get("code").asText())) return bucket.get("count").asLong();
    }
    throw new AssertionError("分档里没有这一档：" + code);
  }

  /** 分档里出现过的全部档位编码（用来断言「没有金额的单据」没被收成一档）。 */
  private List<String> bucketCodes(JsonNode buckets) {
    var out = new ArrayList<String>();
    for (var bucket : buckets) out.add(bucket.get("code").asText());
    return out;
  }

  /**
   * 一条金额阈值分支。
   *
   * <p>刻意用可变映射而不是 {@code Map.of}：{@code Map.of} 不接受 null 值，
   * 而「上界留空 = 一千以上都走这一档」正是要表达的合法配置。
   */
  private Map<String, Object> branch(
      String name, String currency, String min, String max, String targetNodeKey, int priority) {
    var map = new LinkedHashMap<String, Object>();
    map.put("branchName", name);
    map.put("currencyCode", currency);
    map.put("minAmount", min == null ? null : new BigDecimal(min));
    map.put("maxAmount", max == null ? null : new BigDecimal(max));
    map.put("targetNodeKey", targetNodeKey);
    map.put("priority", priority);
    map.put("description", name);
    return map;
  }

  private JsonNode filter(JsonNode filters, String key) {
    for (var filter : filters) {
      if (key.equals(filter.get("key").asText())) return filter;
    }
    throw new AssertionError("能力声明里没有这一项：" + key);
  }

  private JsonNode action(JsonNode actions, String code) {
    for (var action : actions) {
      if (code.equals(action.get("code").asText())) return action;
    }
    throw new AssertionError("能力声明里没有这个动作：" + code);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }
}
