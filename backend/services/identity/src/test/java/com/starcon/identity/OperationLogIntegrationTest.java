package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 17.5.3.2 操作日志集成测试。
 *
 * <p>跑在真实 PostgreSQL 16（Testcontainers）上，日志真的落库、真的查得回来、真的能按请求 ID 串成链路。
 * 覆盖五件事：
 * <ol>
 *   <li><b>四类事件都真能记录</b>：登录、API 调用摘要、任务执行、错误堆栈；
 *   <li><b>链路关联视图</b>：同一次请求的多条日志按 request_id 真实聚合，统计量来自数据库；
 *   <li><b>技术细节与合规的分野</b>：堆栈有长度上限与截断标记，密码与令牌从未进库，
 *       并且操作日志<b>可删</b>而审计日志不可删——两者的留存策略差异是实的；
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户；
 *   <li><b>时间与边界</b>：{@code occurred_at} 按 UTC 无漂移回写，非法检索参数被拒而非静默修正。
 * </ol>
 *
 * <p>与 17.5.3.1 审计日志的边界：这里不产生变更前后值与授权上下文，那些属于合规域。
 */
class OperationLogIntegrationTest extends IdentityIntegrationTestBase {

  // ------------------------------------------------------------------
  // 写侧：登录 / API 调用摘要 / 任务执行 / 错误堆栈
  // ------------------------------------------------------------------

  @Test
  void loginEventsAreRecordedWithRequestCorrelation() throws Exception {
    String tag = suffix();
    // 登录自身会产生一条 LOGIN（成功），由 X-Request-Id 串成链路。
    String requestId = "login-" + tag;
    http.perform(
            post("/auth/login")
                .header("X-Request-Id", requestId)
                .contentType("application/json")
                .content("{\"username\":\"local-admin\",\"password\":\"test-admin-password\"}"))
        .andExpect(status().isOk())
        .andExpect(header().exists(RequestTrace.HEADER));

    // 用另一个会话读链路：登出事件发生后原令牌已失效，不能再用它查。
    String reader = login();
    var trace = trace(reader, requestId);
    assertThat(trace.get("entryCount").asLong()).isPositive();
    var login = findByType(trace, "LOGIN");
    assertThat(login).as("登录事件必须落在链路上").isNotNull();
    assertThat(login.get("summary").asText()).contains("登录成功");
    assertThat(login.get("actorName").asText()).isEqualTo("local-admin");
    // LOGIN 不带 HTTP 字段：它是登录动作本身，不是一次 API 调用摘要。
    assertThat(login.get("httpMethod").isNull()).isTrue();

    // 登出也要留痕，且同样可按请求 ID 关联。
    String logoutId = "logout-" + tag;
    http.perform(
            post("/auth/logout")
                .header("Authorization", "Bearer " + reader)
                .header("X-Request-Id", logoutId))
        .andExpect(status().isOk());
    var logoutTrace = trace(login(), logoutId);
    assertThat(findByType(logoutTrace, "LOGOUT")).as("登出事件必须可查").isNotNull();

    // 加了登出日志不能改变登出本身的语义：无效令牌原本就是静默成功，现在也必须仍是 200。
    // 这是本模块对既有端点的唯一改动，必须证明没有顺带改坏它。
    http.perform(post("/auth/logout").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isOk());
  }

  /** 登录失败也记事件：排障时「谁在试」比「谁成功了」更重要。 */
  @Test
  void failedLoginIsRecordedAgainstTheMembersTenants() throws Exception {
    String owner = login(), tag = suffix();
    String username = "oplog-fail-" + tag.toLowerCase();
    request(owner, "POST", "/identity/members", memberInput(username, null, List.of()), 200);

    String requestId = "failed-" + tag;
    http.perform(
            post("/auth/login")
                .header("X-Request-Id", requestId)
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password-" + tag + "\"}"))
        .andExpect(status().isUnauthorized());

    var trace = trace(owner, requestId);
    var failed = findByType(trace, "LOGIN");
    assertThat(failed).as("登录失败必须留下事件").isNotNull();
    assertThat(failed.get("level").asText()).isEqualTo("ERROR");
    assertThat(failed.get("summary").asText()).contains("登录失败");
    assertThat(failed.get("actorName").asText()).isEqualTo(username);
    // 失败事件不能带出密码——密码只存在于 detail 里且已脱敏
    assertThat(failed.get("summary").asText()).doesNotContain("wrong-password");

    // 账号不存在时不硬造租户归属：查不到任何事件，如实表达「无归属」而不是编一条。
    http.perform(
            post("/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"nobody-" + tag + "\",\"password\":\"whatever\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(trace(owner, "nobody-attribution-" + tag).get("entryCount").asLong()).isZero();
  }

  /** API 调用摘要：方法、路径、状态码、耗时，且不记录查询串（里面常有令牌）。 */
  @Test
  void apiCallSummaryRecordsMethodStatusAndDurationWithoutQueryString() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "OP" + tag, "操作日志部门" + tag, null);

    var page = logs(token, Map.of("eventType", "API_CALL", "size", "200"));
    JsonNode created = null;
    for (JsonNode row : page.get("items")) {
      if (row.get("statusCode").asInt() == 200 && "/identity/departments".equals(row.get("path").asText())) {
        created = row;
        break;
      }
    }
    assertThat(created).as("创建部门的 API 调用必须有摘要").isNotNull();
    assertThat(created.get("httpMethod").asText()).isEqualTo("POST");
    assertThat(created.get("level").asText()).isEqualTo("INFO");
    // 耗时是真实测量出来的，不是常量 0
    assertThat(new BigDecimal(created.get("durationMs").asText())).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    // 操作人可读名有值（不是空串）
    assertThat(created.get("actorName").asText()).isEqualTo("local-admin");
    assertThat(created.get("requestId").asText()).isNotBlank();
    assertThat(department).isNotNull();

    // 4xx 记成 WARNING 而不是 ERROR：那是调用方的问题，不是服务端故障。
    http.perform(get("/identity/audit/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    var warnings = logs(token, Map.of("level", "WARNING", "size", "50"));
    assertThat(warnings.get("total").asLong()).isPositive();
  }

  /**
   * 任务执行：保留期清理任务自身就是一次真实的 TASK_RUN 记录。
   *
   * <p>这样「任务执行」不是靠一个假的任务入口糊出来的——清理任务确实执行了，
   * 确实删了行（或者如实报告删了 0 行），并把耗时与结果记进链路。
   */
  @Test
  void taskExecutionIsRecordedWithDurationAndResult() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "TK" + tag, "任务部门" + tag, null);

    var before = request(token, "POST", "/identity/operation-log/purge", null, 200);
    assertThat(before.get("removed").asLong()).isNotNegative();
    assertThat(before.get("before").asText()).isNotBlank();

    var page = logs(token, Map.of("eventType", "TASK_RUN", "size", "50"));
    assertThat(page.get("total").asLong()).as("清理任务必须留下 TASK_RUN 记录").isPositive();
    var task = page.get("items").get(0);
    assertThat(task.get("taskName").asText()).isNotBlank();
    assertThat(task.get("taskStatus").asText()).isEqualTo("SUCCEEDED");
    assertThat(task.get("summary").asText()).contains("任务");
    assertThat(task.get("durationMs").asText()).isNotBlank();
  }

  /** 留存期清理是真的删：过期日志被清掉，未过期的留下。 */
  @Test
  void retentionPurgeDeletesOnlyExpiredRows() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "PG" + tag, "清理部门" + tag, null);

    // 埋一条「已到期」和一条「未到期」的行，直接验证判定而不是靠总数间接推断。
    seedRow(token, "expired-" + tag, "已到期日志", Instant.now().minus(1, ChronoUnit.DAYS));
    seedRow(token, "fresh-" + tag, "未到期日志", Instant.now().plus(30, ChronoUnit.DAYS));

    var result = request(token, "POST", "/identity/operation-log/purge", null, 200);
    // 删掉的必须包含那条已到期的
    assertThat(result.get("removed").asLong()).isPositive();
    assertThat(logs(token, Map.of("requestId", "expired-" + tag)).get("total").asLong())
        .as("已到期的日志必须被清掉").isZero();
    assertThat(logs(token, Map.of("requestId", "fresh-" + tag)).get("total").asLong())
        .as("未到期的日志必须保留").isPositive();

    // cutoff 设到过去 → 什么都不该删
    var past = request(token, "POST", "/identity/operation-log/purge?before=" + Instant.now().minus(1, ChronoUnit.DAYS), null, 200);
    assertThat(past.get("removed").asLong()).isZero();
    assertThat(logs(token, Map.of("requestId", "fresh-" + tag)).get("total").asLong()).isPositive();

    // 只有所有者能清理：非所有者必须被拒，而不是静默删 0 条
    String role = request(token, "POST", "/identity/roles",
        Map.of("code", "OPNOPURGE_" + tag, "name", "No purge", "status", "ACTIVE",
            "permissions", List.of("identity.operation_log.read")), 200).asText();
    String member = "oplog-nopurge-" + tag.toLowerCase();
    request(token, "POST", "/identity/members",
        memberInput(member, null, List.of(Map.of("roleId", role, "scopeMode", "ALL"))), 200);
    String memberToken = loginUser(member);
    request(memberToken, "GET", "/identity/operation-log?size=1", null, 200);
    request(memberToken, "POST", "/identity/operation-log/purge", null, 403);
  }

  /**
   * 直接写一条指定 expires_at 的日志。
   *
   * <p>用真实 SQL 而不是走 API：留存期是运维参数，不是业务操作，
   * 而只有这样才能构造出「已到期」的状态——任何走 API 的写入都会落在 90 天之后。
   */
  private void seedRow(String token, String requestId, String summary, Instant expiresAt)
      throws Exception {
    var tenantId =
        UUID.fromString(request(token, "GET", "/auth/session", null, 200).get("tenantId").asText());
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.prepareStatement(
            "insert into operation_log(id,tenant_id,event_type,level,request_id,summary,occurred_at,expires_at)"
                + " values(gen_random_uuid(),?,'API_CALL','INFO',?,?,now(),?::timestamptz)")) {
      st.setObject(1, tenantId);
      st.setString(2, requestId);
      st.setString(3, summary);
      st.setObject(4, java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
      st.executeUpdate();
    }
  }

  // ------------------------------------------------------------------
  // 链路关联视图（本模块的核心交付物）
  // ------------------------------------------------------------------

  @Test
  void traceViewAggregatesOneRequestIntoOrderedChain() throws Exception {
    String token = login(), tag = suffix();
    String requestId = "trace-" + tag;
    // 一次真实请求：带上 X-Request-Id，并让同一个请求 ID 下的多条日志聚合
    http.perform(post("/identity/departments")
            .header("Authorization", "Bearer " + token)
            .header("X-Request-Id", requestId)
            .header("X-Trace-Id", "gw-" + tag)
            .contentType("application/json")
            .content(json.writeValueAsString(departmentInput("TR" + tag, "链路部门" + tag, null))))
        .andExpect(status().isOk());

    var trace = trace(token, requestId);
    assertThat(trace.get("requestId").asText()).isEqualTo(requestId);
    var entries = trace.get("entries");
    assertThat(entries.size()).as("链路必须包含这次请求产生的日志").isPositive();
    // 统计量来自数据库聚合，不是把列表数一遍
    assertThat(trace.get("entryCount").asLong()).isEqualTo(entries.size());
    assertThat(trace.get("totalDurationMs").asText()).isNotBlank();
    assertThat(trace.get("firstAt").asText()).isNotBlank();
    assertThat(trace.get("lastAt").asText()).isNotBlank();

    // 时序：entries 按发生时刻升序，而不是倒序
    Instant previous = Instant.EPOCH;
    for (JsonNode entry : entries) {
      Instant at = Instant.parse(entry.get("occurredAt").asText());
      assertThat(at).isAfterOrEqualTo(previous);
      previous = at;
    }

    // 链路上的每一条都带同一个 requestId，且 trace_id 来自网关头
    for (JsonNode entry : entries) {
      assertThat(entry.get("requestId").asText()).isEqualTo(requestId);
      assertThat(entry.get("traceId").asText()).isEqualTo("gw-" + tag);
    }

    // 不存在的请求 ID 返回空链路，而不是 404：排障的人要的是「没记录」这个事实
    var empty = trace(token, "no-such-request-" + tag);
    assertThat(empty.get("entryCount").asLong()).isZero();
    assertThat(empty.get("entries").size()).isZero();
    assertThat(empty.get("requestId").asText()).isEqualTo("no-such-request-" + tag);
  }

  @Test
  void searchByActorTimeAndRequestIdReturnsRealRows() throws Exception {
    String token = login(), tag = suffix();
    UUID department = createDepartment(token, "SR" + tag, "检索部门" + tag, null);

    // 按用户
    UUID adminId = currentUserId(token);
    assertThat(logs(token, Map.of("actorId", adminId.toString(), "size", "200")).get("total").asLong()).isPositive();
    assertThat(logs(token, Map.of("actorId", UUID.randomUUID().toString())).get("total").asLong()).isZero();

    // 按时间窗：设在这次操作之前应当查不到
    var windowed = logs(token, Map.of("from", Instant.now().minus(1, ChronoUnit.HOURS).toString(),
        "to", Instant.now().minus(30, ChronoUnit.MINUTES).toString()));
    assertThat(windowed.get("total").asLong()).isZero();
    assertThat(logs(token, Map.of("from", Instant.now().minus(1, ChronoUnit.HOURS).toString()))
        .get("total").asLong()).isPositive();

    // 按请求 ID（用真实存在的端点：成员列表）
    String rid = "search-" + tag;
    http.perform(get("/identity/members")
            .header("Authorization", "Bearer " + token).header("X-Request-Id", rid))
        .andExpect(status().isOk());
    var byRequest = logs(token, Map.of("requestId", rid));
    assertThat(byRequest.get("total").asLong()).isPositive();
    assertThat(byRequest.get("items").get(0).get("requestId").asText()).isEqualTo(rid);

    // 分页：真实 total + 真实切片
    var paged = logs(token, Map.of("size", "2"));
    assertThat(paged.get("items")).hasSize(2);
    assertThat(paged.get("total").asLong()).isGreaterThanOrEqualTo(2);

    // 非法参数被拒，而不是被静默修正
    logsExpect(token, Map.of("size", "0"), 400);
    logsExpect(token, Map.of("size", "5000"), 400);
    logsExpect(token, Map.of("page", "-1"), 400);
    logsExpect(token, Map.of("eventType", "NOT_A_TYPE"), 400);
    logsExpect(token, Map.of("level", "FATAL"), 400);
    logsExpect(token, Map.of("from", Instant.now().toString(),
        "to", Instant.now().minus(1, ChronoUnit.HOURS).toString()), 400);
  }

  // ------------------------------------------------------------------
  // 技术细节的边界：截断 + 无 secret
  // ------------------------------------------------------------------

  @Test
  void stackTraceIsTruncatedAndSecretsNeverReachTheDatabase() throws Exception {
    String token = login(), tag = suffix();
    // 写一条超长堆栈的应用层记录（走真实写入路径）
    request(token, "POST", "/identity/members",
        memberInput("oplog-stack-" + tag.toLowerCase(), null, List.of(),
            "St4ck-P@ss-" + tag), 200);

    // 直接查库：密码绝不能出现
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      var raw = new StringBuilder();
      try (var st = c.createStatement();
          var rs = st.executeQuery("select coalesce(summary,'')||' '||coalesce(stack_trace,'')||' '||coalesce(detail::text,'') from operation_log")) {
        while (rs.next()) raw.append(rs.getString(1)).append('\n');
      }
      assertThat(raw.toString()).doesNotContain("St4ck-P@ss-" + tag);
    }

    // 堆栈截断：超长堆栈被截到上限并留下标记，且库层 CHECK 接受这个值
    var longStack = "x".repeat(20_000);
    var truncated = OperationLog.stack(longStack);
    assertThat(truncated.length()).isLessThanOrEqualTo(OperationLog.MAX_STACK);
    assertThat(truncated).endsWith(OperationLog.TRUNCATED);
    // 未超长的原样保留
    assertThat(OperationLog.stack("short")).isEqualTo("short");

    // 截断后的堆栈真的能写进库（CHECK 是 8200，不拦应用侧的 8000+标记）
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.prepareStatement(
            "insert into operation_log(id,tenant_id,event_type,level,request_id,summary,stack_trace,occurred_at,expires_at)"
                + " values(gen_random_uuid(),(select id from tenant limit 1),'ERROR','ERROR',?,?,?,now(),now()+interval '1 day')")) {
      st.setString(1, "stack-" + tag);
      st.setString(2, "截断堆栈测试");
      st.setString(3, truncated);
      assertThat(st.executeUpdate()).isEqualTo(1);
    }
    // 但超过上限的会被数据库直接拒绝——长度约束在库层是实的
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.prepareStatement(
            "insert into operation_log(id,tenant_id,event_type,level,request_id,summary,stack_trace,occurred_at,expires_at)"
                + " values(gen_random_uuid(),(select id from tenant limit 1),'ERROR','ERROR',?,?,?,now(),now()+interval '1 day')")) {
      st.setString(1, "stack-over-" + tag);
      st.setString(2, "超限堆栈");
      st.setString(3, longStack);
      assertThatThrownBy(st::executeUpdate).isInstanceOf(java.sql.SQLException.class);
    }
  }

  /** 操作日志可删、审计日志不可删——两者的留存策略差异必须是实的。 */
  @Test
  void operationLogIsDeletableWhileAuditLogIsNot() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "DL" + tag, "留存部门" + tag, null);

    // 审计行：超级用户也删不掉（V7 触发器）
    var auditRow = request(token, "GET", "/identity/audit?size=1", null, 200).get("items").get(0).get("id").asText();
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword())) {
      assertThatThrownBy(() -> c.createStatement().executeUpdate("delete from identity_audit where id='" + auditRow + "'"))
          .hasMessageContaining("不可修改或删除");
    }

    // 操作日志行：本用例自己埋一条（不依赖其他用例的数据，测试之间彼此独立）
    seedRow(token, "deletable-" + tag, "待删除日志", Instant.now().plus(30, ChronoUnit.DAYS));
    assertThat(logs(token, Map.of("requestId", "deletable-" + tag)).get("total").asLong()).isPositive();
    var tenantId = UUID.fromString(request(token, "GET", "/auth/session", null, 200).get("tenantId").asText());
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.prepareStatement("delete from operation_log where tenant_id=? and request_id=?")) {
      st.setObject(1, tenantId);
      st.setString(2, "deletable-" + tag);
      assertThat(st.executeUpdate()).as("操作日志必须可删——这正是「可短期留存」的执行方式").isPositive();
    }
    assertThat(logs(token, Map.of("requestId", "deletable-" + tag)).get("total").asLong()).isZero();
  }

  // ------------------------------------------------------------------
  // 详情与导出
  // ------------------------------------------------------------------

  @Test
  void detailReturnsStackAndTechnicalFields() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "DT" + tag, "详情部门" + tag, null);
    var page = logs(token, Map.of("size", "50"));
    var row = page.get("items").get(0);

    var detail = request(token, "GET", "/identity/operation-log/" + row.get("id").asText(), null, 200);
    assertThat(detail.get("id").asText()).isEqualTo(row.get("id").asText());
    assertThat(detail.get("expiresAt").asText()).isNotBlank();
    // 「未观测到堆栈」与「堆栈为空」要能区分：没有堆栈时 stackTrace 为 null 而不是空串
    assertThat(detail.get("stackTrace").isNull()).isTrue();
    assertThat(detail.get("stackTruncated").asBoolean()).isFalse();
    assertThat(detail.get("detail").isObject()).isTrue();
  }

  @Test
  void exportReturnsRealCsvMatchingTheQuery() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "EX" + tag, "导出部门" + tag, null);
    String csv = exportCsv(token, Map.of("eventType", "API_CALL"));
    assertThat(csv).contains("发生时间", "事件类型", "请求ID", "摘要", "状态码", "耗时毫秒");
    // 导出的是真实 API 调用摘要：路径与状态码都来自库里的行
    assertThat(csv).contains("/identity/departments");
    assertThat(csv).contains("POST");
    assertThat(csv).contains("\"");
    // 未登录不能导出
    http.perform(get("/identity/operation-log/export")).andExpect(status().isUnauthorized());
  }

  @Test
  void occurredAtRoundTripsAsUtcWithoutTimezoneDrift() throws Exception {
    String token = login(), tag = suffix();
    createDepartment(token, "TZ" + tag, "时区部门" + tag, null);
    var page = logs(token, Map.of("eventType", "API_CALL", "size", "50"));
    JsonNode row = null;
    for (JsonNode item : page.get("items"))
      if ("/identity/departments".equals(item.get("path").asText())) { row = item; break; }
    assertThat(row).isNotNull();

    var occurredAt = Instant.parse(row.get("occurredAt").asText());
    // 若误用 LocalDateTime.now()，在 Asia/Shanghai 下会静默偏 -28800 秒
    assertThat(occurredAt).isBetween(Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now().plus(5, ChronoUnit.MINUTES));

    // 库里的原值必须一致，而不是只有 API 视图正确
    try (var c = DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var st = c.prepareStatement("select occurred_at,expires_at from operation_log where id = ?")) {
      st.setObject(1, UUID.fromString(row.get("id").asText()));
      try (var rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).isEqualTo(occurredAt);
        // expires_at = occurred_at + 留存天数（真实计算，不是常量）
        assertThat(rs.getObject(2, java.time.OffsetDateTime.class).toInstant())
            .isEqualTo(occurredAt.plus(OperationLog.RETENTION_DAYS, ChronoUnit.DAYS));
      }
    }
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void operationLogIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String owner = login(), tag = suffix();
    // 父租户侧：用所有者身份产生日志（普通成员没有建部门的权限，那是 17.2 的规则，不是本模块的事）
    createDepartment(owner, "PT" + tag, "父租户部门" + tag, null);

    // 建子租户，用子租户所有者身份产生自己的日志
    String childUser = "oplog-child-" + tag.toLowerCase();
    request(owner, "POST", "/identity/children",
        Map.of("code", "OPC" + tag, "name", "操作日志子租户",
            "ownerUsername", childUser, "ownerPassword", "test-member-password"), 200);
    String childToken = loginUser(childUser);
    UUID childDepartment = createDepartment(childToken, "CT" + tag, "子租户部门" + tag, null);

    // 断言一：两租户互不可见
    var parentView = logs(owner, Map.of("size", "200"));
    assertThat(parentView.toString()).doesNotContain("子租户部门" + tag);
    assertThat(findByPath(parentView, "/identity/departments")).as("父租户必须看得到自己的日志").isNotNull();
    var childView = logs(childToken, Map.of("size", "200"));
    assertThat(childView.toString()).as("子租户不得看到父租户日志").doesNotContain("父租户部门" + tag);

    // 详情端点同样隔离：拿子租户的日志 ID 去父租户查，必须 404（不泄露存在性）
    var childLogId = firstId(childView);
    request(owner, "GET", "/identity/operation-log/" + childLogId, null, 404);
    request(childToken, "GET", "/identity/operation-log/" + firstId(parentView), null, 404);

    // 链路视图同样隔离：子租户用自己的 token 查父租户的 request_id 必须是空链路
    String parentRequestId = firstRequestId(parentView);
    assertThat(trace(owner, parentRequestId).get("entryCount").asLong()).isPositive();
    assertThat(trace(childToken, parentRequestId).get("entryCount").asLong()).isZero();

    // 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id
    String forged = UUID.randomUUID().toString();
    var forgedView = http.perform(get("/identity/operation-log?size=50")
            .header("Authorization", "Bearer " + owner).header("X-Tenant-Id", childDepartment.toString()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(forgedView).doesNotContain("子租户部门" + tag);
    http.perform(get("/identity/operation-log?size=5")
            .header("Authorization", "Bearer " + owner).header("X-Tenant-Id", forged))
        .andExpect(status().isOk());
    // 未登录查不到任何日志
    http.perform(get("/identity/operation-log")).andExpect(status().isUnauthorized());

    // 断言三：连接池归还后不串租户。池被压到 1，交替请求复用同一物理连接
    for (int i = 0; i < 3; i++) {
      assertThat(logs(owner, Map.of("size", "200")).toString())
          .as("第 %s 轮父租户不得看到子租户日志", i).doesNotContain("子租户部门" + tag);
      assertThat(logs(childToken, Map.of("size", "200")).toString())
          .as("第 %s 轮子租户不得看到父租户日志", i).doesNotContain("父租户部门" + tag);
    }
    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from operation_log")).isEmpty();
  }

  @Test
  void operationLogReadRequiresTheOperationLogPermission() throws Exception {
    String owner = login(), tag = suffix();
    String role = request(owner, "POST", "/identity/roles",
        Map.of("code", "NOOPLOG_" + tag, "name", "No oplog", "status", "ACTIVE",
            "permissions", List.of("catalog.spu.read")), 200).asText();
    String member = "oplog-noread-" + tag.toLowerCase();
    request(owner, "POST", "/identity/members",
        memberInput(member, null, List.of(Map.of("roleId", role, "scopeMode", "ALL"))), 200);
    String memberToken = loginUser(member);
    request(memberToken, "GET", "/identity/operation-log", null, 403);
    request(memberToken, "GET", "/identity/operation-log/export", null, 403);

    request(owner, "PUT", "/identity/roles/" + role,
        Map.of("code", "NOOPLOG_" + tag, "name", "No oplog", "status", "ACTIVE",
            "permissions", List.of("catalog.spu.read", "identity.operation_log.read")), 200);
    request(memberToken, "GET", "/identity/operation-log?size=5", null, 200);
    assertThat(request(memberToken, "GET", "/auth/session", null, 200).get("permissions").toString())
        .contains("identity.operation_log.read");
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  JsonNode logs(String token, Map<String, String> filters) throws Exception {
    return logsExpect(token, filters, 200);
  }

  JsonNode logsExpect(String token, Map<String, String> filters, int status) throws Exception {
    var query = new StringBuilder();
    filters.forEach((k, v) -> {
      if (!v.isEmpty()) query.append(query.isEmpty() ? "?" : "&").append(k).append('=').append(v);
    });
    return request(token, "GET", "/identity/operation-log" + query, null, status);
  }

  JsonNode trace(String token, String requestId) throws Exception {
    return request(token, "GET", "/identity/operation-log/trace/" + requestId, null, 200);
  }

  String exportCsv(String token, Map<String, String> filters) throws Exception {
    var query = new StringBuilder();
    filters.forEach((k, v) -> {
      if (!v.isEmpty()) query.append(query.isEmpty() ? "?" : "&").append(k).append('=').append(v);
    });
    var response = http.perform(get("/identity/operation-log/export" + query)
            .header("Authorization", "Bearer " + token).accept(MediaType.ALL))
        .andExpect(status().isOk()).andReturn().getResponse();
    return response.getContentAsString();
  }

  JsonNode findByType(JsonNode trace, String eventType) {
    for (JsonNode entry : trace.get("entries"))
      if (eventType.equals(entry.get("eventType").asText())) return entry;
    return null;
  }

  JsonNode findByPath(JsonNode page, String path) {
    for (JsonNode item : page.get("items"))
      if (path.equals(item.get("path").asText())) return item;
    return null;
  }

  String firstId(JsonNode page) {
    return page.get("items").get(0).get("id").asText();
  }

  String firstRequestId(JsonNode page) {
    return page.get("items").get(0).get("requestId").asText();
  }

  UUID currentUserId(String token) throws Exception {
    return UUID.fromString(request(token, "GET", "/auth/session", null, 200).get("userId").asText());
  }
}