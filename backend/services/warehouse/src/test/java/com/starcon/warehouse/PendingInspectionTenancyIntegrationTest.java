package com.starcon.warehouse;

import static com.starcon.warehouse.PendingInspectionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.2.2.2 待检处理的<b>租户隔离三断言</b>。
 *
 * <p>三条断言缺一不可（AGENTS.md「多租户」）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：B 看不到 A 的待检处理单、处置明细、事件与分录；
 *   <li><b>伪造 tenant header 无效</b>：带 {@code X-Tenant-Id} 指向 A 的请求，身份是 B 时看到的仍然是 B 的；
 *       并且<b>用应用账号 warehouse_app 直连数据库</b>按主键查 A 的行必须查不到 ——
 *       用超级用户查会绕过 RLS，那条断言等于没做。
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条，交替读写多轮不串。
 * </ol>
 *
 * <p>三张表逐张确认 ENABLE + FORCE RLS。
 */
class PendingInspectionTenancyIntegrationTest extends PendingInspectionTestBase {

  // ================================================================ 断言一

  @Test
  void 断言一_两租户互不可见() {
    UUID taskA = createTaskA();

    // B 看不到 A 的任何东西：连详情都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.pending-inspection.read", () -> service.detail(taskA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.pending-inspection.read",
                    () -> service.page(null, null, null, null, null, null, 0, 20))
                .total())
        .isZero();
    // B 的待检清单里**只有 B 自己的那批货**（两个租户的库位/商品编码一模一样，
    // 所以必须比 bucket 标识而不是比编码 —— 比编码会看起来「一样」而什么也证明不了）。
    assertThat(
            access
                .run(actorB, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50))
                .rows())
        .extracting(PendingInspectionStockRow::bucketId)
        .containsExactly(bucketPendingB)
        .doesNotContain(bucketPendingA);

    // B 不能往 A 的单据上写处置。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.dispose(
                            taskA,
                            new PendingInspectionDisposeInput(
                                0L,
                                List.of(
                                    new PendingInspectionDisposeLineInput(
                                        "SELLABLE", new BigDecimal("60"), qSellB, locStorageB, null)),
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");

    // B 自己的单与 A 的互不可见。
    UUID taskB = createTaskB();
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.pending-inspection.read",
                    () -> service.page(null, null, null, null, null, null, 0, 20))
                .rows())
        .extracting(PendingInspectionTaskRow::id)
        .containsExactly(taskB);
  }

  // ================================================================ 断言二

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID taskA = createTaskA();

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(
            get("/warehouse/pending-inspections")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(
            get("/warehouse/pending-inspections/{id}", taskA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 把 A 的待检货处置掉（那等于往 A 的账里写数）。
    http.perform(
            post("/warehouse/pending-inspections/{id}/dispose", taskA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":0,\"lines\":[{\"dispositionType\":\"SELLABLE\","
                        + "\"quantity\":60,\"targetQualityStatusId\":\""
                        + qSellB
                        + "\",\"targetLocationId\":\""
                        + locStorageB
                        + "\"}]}"))
        .andExpect(status().isNotFound());

    // 🔴 用**应用账号** warehouse_app 直连数据库按主键查 A 的行必须查不到 ——
    // 用超级用户查会绕过 FORCE RLS，那条断言等于没做。
    try (var c = appConnection(); var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant', '" + b + "', false)");
      try (var rs =
          s.executeQuery(
              "select count(*) from pending_inspection_task where id = '" + taskA + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isZero();
      }
      // 换成 A 的租户就能查到 —— 证明上一条不是因为「行不存在」。
      s.execute("select set_config('app.current_tenant', '" + a + "', false)");
      try (var rs =
          s.executeQuery(
              "select count(*) from pending_inspection_task where id = '" + taskA + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }

    // 三张表逐张确认 ENABLE + FORCE RLS。
    try (var c = appConnection();
        var s = c.createStatement()) {
      for (var table :
          List.of(
              "pending_inspection_task", "pending_inspection_disposition", "pending_inspection_event")) {
        try (var rs =
            s.executeQuery(
                "select relrowsecurity, relforcerowsecurity from pg_class"
                    + " where relname = '" + table + "'")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as(table + " 必须 ENABLE ROW LEVEL SECURITY").isTrue();
          assertThat(rs.getBoolean(2)).as(table + " 必须 FORCE ROW LEVEL SECURITY").isTrue();
        }
      }
    }
  }

  // ================================================================ 断言三

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID taskA = createTaskA();
    UUID taskB = createTaskB();

    // A → B → A 三轮，连接池只有 1 条连接：每轮都必须只看到自己租户的数据。
    for (int round = 0; round < 3; round++) {
      assertThat(
              access
                  .run(
                      actorA,
                      "warehouse.pending-inspection.read",
                      () -> service.page(null, null, null, null, null, null, 0, 20))
                  .rows())
          .extracting(PendingInspectionTaskRow::id)
          .containsExactly(taskA);
      assertThat(
              access
                  .run(
                      actorB,
                      "warehouse.pending-inspection.read",
                      () -> service.page(null, null, null, null, null, null, 0, 20))
                  .rows())
          .extracting(PendingInspectionTaskRow::id)
          .containsExactly(taskB);
      // 待检清单同理：A 看到 60 件待检，B 看到 60 件待检，但不是同一批货。
      assertThat(
              access
                  .run(actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50))
                  .rows())
          .hasSize(1);
      assertThat(
              access
                  .run(actorB, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50))
                  .rows())
          .hasSize(1);
    }

    // 归还连接后，A 的余额行仍然是 A 的那一行。
    assertThat(onHand(a, balancePendingA)).isEqualByComparingTo("60");
    assertThat(onHand(b, balancePendingB)).isEqualByComparingTo("60");
    assertThat(balancePendingA).isNotEqualTo(balancePendingB);
  }

  // ================================================================ 工具

  private UUID createTaskA() {
    return access
        .run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.create(
                    new PendingInspectionTaskCreateInput(
                        bucketPendingA, receiptLineA, new BigDecimal("60"), null),
                    user))
        .id();
  }

  private UUID createTaskB() {
    return access
        .run(
            actorB,
            "warehouse.pending-inspection.update",
            () ->
                service.create(
                    new PendingInspectionTaskCreateInput(
                        bucketPendingB, receiptLineB, new BigDecimal("60"), null),
                    user))
        .id();
  }
}
