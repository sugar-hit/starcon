package com.starcon.warehouse;

import static com.starcon.warehouse.PendingInspectionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.2.2.2 待检处理的集成测试。
 *
 * <p><b>本测试最要紧的四节</b>：
 *
 * <ol>
 *   <li><b>蓝图加粗句「待检品不可售」</b>：待检货在待检清单里、在待检切片里，
 *       但<b>不在</b>可售基数里（用 7.1.1.3 的真实口径断言，不是另写一份 SQL）；
 *       质检通过转可售之后，可售量才涨 —— 这正是本模块「输出：可售量更新」那句话。
 *   <li><b>处置必须真的动库存</b>：质量状态变更分录成对出现（待检 −Q / 可售 +Q），
 *       余额与库位明细同步变化，V252 的守���回读账本分录核对 P1/P2/P3/P4 ——
 *       <b>绕过服务层直连 SQL</b> 写一条不成对的分录，守卫照样挡住。
 *   <li><b>结论只有一个来源</b>：质检三量从 trade 的质检单回读，调用方不能自报；
 *       trade 不可达时操作明确失败（503），绝不出现「已发起」却其实没发起的假记录。
 *   <li><b>时间</b>：事件与分录的时间是业务时间，写回的值要逐字相等 ——
 *       用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800s。
 * </ol>
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
class PendingInspectionIntegrationTest extends PendingInspectionTestBase {

  // ================================================================ 清单

  @Test
  void 待检清单只列待检库存且可售品不在其中() {
    var stock =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50));
    assertThat(stock.rows()).hasSize(1);
    var row = stock.rows().get(0);
    assertThat(row.bucketId()).isEqualTo(bucketPendingA);
    assertThat(row.onHand()).isEqualByComparingTo("60");
    assertThat(row.hasOpenTask()).isFalse();

    // 可售品不进待检清单：待检清单是「需要质检的货」，不是全部库存。
    access.run(
        actorA,
        "warehouse.pending-inspection.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              UUID.randomUUID(),
              a,
              skuA,
              whA,
              leA,
              qSellA,
              new BigDecimal("10"));
          return null;
        });
    var after =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50));
    assertThat(after.rows()).hasSize(1);
  }

  @Test
  void 待检清单支持真分页与筛选() {
    access.run(
        actorA,
        "warehouse.pending-inspection.read",
        () -> {
          // 第二个 SKU 的待检库存（另一个库位）
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              UUID.randomUUID(),
              a,
              skuB,
              whA,
              leA,
              qPendingA,
              new BigDecimal("5"));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,?,?,?,?,0,now())",
              UUID.randomUUID(),
              a,
              leA,
              skuB,
              whA,
              locQuarantineA,
              null,
              qPendingA,
              sql.fetchOne(
                      "select id from inventory_balance where tenant_id=? and sku_id=?", a, skuB)
                  .get("id", UUID.class),
              new BigDecimal("5"));
          return null;
        });

    var all =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.bucketCount()).isEqualTo(2);

    var first =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, null, null, null, 0, 1));
    assertThat(first.rows()).hasSize(1);
    assertThat(first.total()).isEqualTo(2);
    assertThat(first.page()).isZero();

    var bySku =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.stock(null, null, skuB, null, null, 0, 50));
    assertThat(bySku.rows()).hasSize(1);
    assertThat(bySku.rows().get(0).skuId()).isEqualTo(skuB);

    var byKeyword =
        access.run(
            actorA,
            "warehouse.pending-inspection.read",
            () -> service.stock(null, null, null, "商品二", null, 0, 50));
    assertThat(byKeyword.rows()).hasSize(1);
  }

  // ================================================================ 建单

  @Test
  void 建单必须挂真实收货行且批量不得超过在手量与实收量() {
    // 超过库位上的实际在手量（60）→ 拒绝
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.create(
                            new PendingInspectionTaskCreateInput(
                                bucketPendingA, receiptLineA, new BigDecimal("61"), null),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("实际在手量");

    // 超过收货明细行的实收量 —— 待检量不能多于收进来的量。
    // 这里把收货行的实收量改成 10（库存行仍有 60），造出「库存多于实收」的真实场景。
    access.run(
        actorA,
        "warehouse.pending-inspection.read",
        () -> {
          sql.execute(
              "update goods_receipt_line set received_quantity=? where id=?",
              new BigDecimal("10"),
              receiptLineA);
          return null;
        });
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.create(
                            new PendingInspectionTaskCreateInput(
                                bucketPendingA, receiptLineA, new BigDecimal("60"), null),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("实收量");

    // 收货明细行不存在 → 404
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.create(
                            new PendingInspectionTaskCreateInput(
                                bucketPendingA, UUID.randomUUID(), new BigDecimal("10"), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void 同一批待检货不能建两张未完结的单() {
    access.run(
        actorA,
        "warehouse.pending-inspection.update",
        () ->
            service.create(
                new PendingInspectionTaskCreateInput(
                    bucketPendingA, receiptLineA, new BigDecimal("10"), null),
                user));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.create(
                            new PendingInspectionTaskCreateInput(
                                bucketPendingA, receiptLineA, new BigDecimal("10"), null),
                            user)))
        .hasMessageContaining("pending_inspection_task_open_bucket");
  }

  // ================================================================ 发起质检

  @Test
  void 发起质检走trade的授权端点并把批次与来源单报上去() {
    UUID taskId = createTask(new BigDecimal("60"));
    var row =
        access.run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.requestInspection(
                    taskId, new PendingInspectionRequestInspectionInput(TEMPLATE, "开单"), user, "Bearer tenant-a"));
    assertThat(row.status()).isEqualTo("INSPECTION_REQUESTED");
    assertThat(row.inspectionOrderCode()).isNotBlank();

    // 我们真的把「哪个 SKU、来自哪张收货单的哪一行、本次检多少」报给了 trade。
    assertThat(String.valueOf(lastCreateBody.get("templateId"))).isEqualTo(TEMPLATE.toString());
    assertThat(String.valueOf(lastCreateBody.get("receiptCode"))).isEqualTo("GR-1");
    assertThat(String.valueOf(lastCreateBody.get("receiptLineId"))).isEqualTo(receiptLineA.toString());
    assertThat(new BigDecimal(String.valueOf(lastCreateBody.get("lotSize"))))
        .isEqualByComparingTo("60");
  }

  @Test
  void trade不可用时发起质检明确失败且不留下假记录() {
    UUID taskId = createTask(new BigDecimal("60"));
    tradeCreateStatus = 500;
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.requestInspection(
                            taskId,
                            new PendingInspectionRequestInspectionInput(TEMPLATE, null),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("503");

    // 单子仍然是「待发起质检」：绝不能出现「已发起」却其实没发起的假记录。
    var row =
        access.run(
            actorA, "warehouse.pending-inspection.read", () -> service.detail(taskId).task());
    assertThat(row.status()).isEqualTo("PENDING");
    assertThat(row.inspectionOrderCode()).isEmpty();
  }

  // ================================================================ 录入结果

  @Test
  void 质检结果从trade回读且三量必须与批量对得上() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);

    // 质检单还没完结 → 409
    tradeOrderStatus = "DRAFT";
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.recordResult(
                            taskId, new PendingInspectionRecordResultInput(null), user, "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    // 三量之和与批量对不上 → 409（结论与库存不是同一批货）
    tradeOrderStatus = "COMPLETED";
    tradeDefective = new BigDecimal("10");
    tradeConcession = new BigDecimal("5");
    tradeQuantityDelta = new BigDecimal("5");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.recordResult(
                            taskId, new PendingInspectionRecordResultInput(null), user, "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    // 正常情形：合格 50 / 不合格 10 / 让步 0 = 60
    tradeDefective = new BigDecimal("10");
    tradeConcession = BigDecimal.ZERO;
    tradeQuantityDelta = BigDecimal.ZERO;
    var row =
        access.run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.recordResult(
                    taskId, new PendingInspectionRecordResultInput("抽检完成"), user, "Bearer tenant-a"));
    assertThat(row.status()).isEqualTo("RESULT_RECORDED");
    assertThat(row.qualifiedQuantity()).isEqualByComparingTo("50");
    assertThat(row.unqualifiedQuantity()).isEqualByComparingTo("10");
    assertThat(row.concessionQuantity()).isEqualByComparingTo("0");
    assertThat(row.sellableQuantity()).isEqualByComparingTo("50");
    assertThat(row.nonSellableQuantity()).isEqualByComparingTo("10");
  }

  // ================================================================ 处置

  @Test
  void 处置转可售后库存真的流转且可售量更新() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    recordResult(taskId);

    // 处置前：待检 60 在待检切片里，可售基数是 0。
    // ⚠️ 可用量查询必须**在租户上下文里**跑：RLS 下没有租户上下文就一行都读不到，
    // 那时候算出来的「可售量 0」是「查不到」，不是「不可售」。
    var before =
        access.run(
            actorA,
            "warehouse.pending-inspection.read",
            () ->
                available.report(
                    new AvailableQuantityModels.AvailableQuantityQuery(
                        whA, null, null, skuA, null, null, null, null, null, 0, 50)));
    assertThat(before.totals().sellable().stripTrailingZeros()).isEqualByComparingTo("0");
    var pendingSlice =
        before.totals().slices().stream()
            .filter(s -> "PENDING_INSPECTION".equals(s.code()))
            .findFirst()
            .orElseThrow();
    assertThat(pendingSlice.amount()).isEqualByComparingTo("60");
    // 待检品**不在**可售基数里 —— 这就是蓝图那句「待检品不可售」。
    assertThat(pendingSlice.countedInSellable()).isFalse();

    var row =
        access.run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.dispose(
                    taskId,
                    new PendingInspectionDisposeInput(
                        2L,
                        List.of(
                            new PendingInspectionDisposeLineInput(
                                "SELLABLE", new BigDecimal("50"), qSellA, locStorageA, "转入可售"),
                            new PendingInspectionDisposeLineInput(
                                "DEFECTIVE", new BigDecimal("10"), qDefA, locDefectiveA, "转残次")),
                        "处置完成"),
                    user));
    assertThat(row.status()).isEqualTo("DISPOSED");
    assertThat(row.dispositionSummary()).isEqualTo("MIXED");

    // 库存真的动了：待检位空了，存储位多了可售，残次位多了残次。
    assertThat(bucketOnHand(a, locQuarantineA, qPendingA)).isEqualByComparingTo("0");
    assertThat(bucketOnHand(a, locStorageA, qSellA)).isEqualByComparingTo("50");
    assertThat(bucketOnHand(a, locDefectiveA, qDefA)).isEqualByComparingTo("10");
    assertThat(onHand(a, balancePendingA)).isEqualByComparingTo("0");

    // 质量状态变更分录：每个去向一条负向（待检 −50 / 待检 −10）+ 留仓去向一条正向
    // （可售 +50 / 残次 +10），共 4 条，净额 = 0（货还在本仓，只是换了质量状态与库位）。
    var detail = access.run(actorA, "warehouse.pending-inspection.read", () -> service.detail(taskId));
    assertThat(detail.entries()).hasSize(4);
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("0");
    assertThat(detail.dispositions()).hasSize(2);

    // 可售量更新：处置后 50 进入可售基数（7.1.1.3 的真实口径）。
    var after =
        access.run(
            actorA,
            "warehouse.pending-inspection.read",
            () ->
                available.report(
                    new AvailableQuantityModels.AvailableQuantityQuery(
                        whA, null, null, skuA, null, null, null, null, null, 0, 50)));
    assertThat(after.totals().sellable().stripTrailingZeros()).isEqualByComparingTo("50");
    assertThat(
            after.totals().slices().stream()
                .filter(s -> "PENDING_INSPECTION".equals(s.code()))
                .findFirst()
                .orElseThrow()
                .amount())
        .isEqualByComparingTo("0");
    // 本模块记录的处置前后可售量快照。
    assertThat(detail.sellableBefore()).isEqualByComparingTo("0");
    assertThat(detail.sellableAfter()).isEqualByComparingTo("50");
  }

  @Test
  void 报废与退货是离仓的净额() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    tradeDefective = new BigDecimal("20");
    recordResult(taskId);

    var row =
        access.run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.dispose(
                    taskId,
                    new PendingInspectionDisposeInput(
                        2L,
                        List.of(
                            new PendingInspectionDisposeLineInput(
                                "SELLABLE", new BigDecimal("40"), qSellA, locStorageA, null),
                            new PendingInspectionDisposeLineInput("SCRAP", new BigDecimal("20"), null, null, "报废")),
                        null),
                    user));
    assertThat(row.status()).isEqualTo("DISPOSED");
    assertThat(row.dispositionSummary()).isEqualTo("MIXED");

    // 离仓的那 20 件只有负向分录：净额 = −20（货不在本仓了）。
    var detail = access.run(actorA, "warehouse.pending-inspection.read", () -> service.detail(taskId));
    assertThat(detail.entries()).hasSize(3);
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("-20");
    assertThat(bucketOnHand(a, locQuarantineA, qPendingA)).isEqualByComparingTo("0");
    assertThat(bucketOnHand(a, locStorageA, qSellA)).isEqualByComparingTo("40");
  }

  @Test
  void 处置必须把这一批全部处置掉且不合格品不能转可售() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    tradeDefective = new BigDecimal("20");
    recordResult(taskId);

    // 处置一半 → 拒绝
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.dispose(
                            taskId,
                            new PendingInspectionDisposeInput(
                                2L,
                                List.of(
                                    new PendingInspectionDisposeLineInput(
                                        "SELLABLE", new BigDecimal("40"), qSellA, locStorageA, null)),
                                null),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("全部处置掉");

    // 把不合格的 20 件也转成可售 → 拒绝（超过质检结论里的可售量 40）
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.dispose(
                            taskId,
                            new PendingInspectionDisposeInput(
                                2L,
                                List.of(
                                    new PendingInspectionDisposeLineInput(
                                        "SELLABLE", new BigDecimal("60"), qSellA, locStorageA, null)),
                                null),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不合格品不能被转成可售");
  }

  @Test
  void 目标库位类型必须与质量角色匹配且不允许混放() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    recordResult(taskId);

    // 把可售的货放进待检库位 → 拒绝（库位类型与质量角色不匹配）
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.dispose(
                            taskId,
                            new PendingInspectionDisposeInput(
                                2L,
                                List.of(
                                    new PendingInspectionDisposeLineInput(
                                        "SELLABLE", new BigDecimal("60"), qSellA, locQuarantineA, null)),
                                null),
                            user)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只能进");
  }

  // ================================================================ 数据库级不变量

  @Test
  void 绕过服务层直连SQL写一条不成对的分录也会被守卫挡住() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    recordResult(taskId);

    // 直连 SQL：只写一条转出分录（缺转入分录），然后把单子标成已处置。
    // 绕过服务层是这条断言的全部意义 —— 否则守卫只是一行应用层 if。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () -> {
                      UUID tx = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,"
                              + "occurred_at,actor_id,notes) values(?,?,'ADJUSTMENT',"
                              + "'PENDING_INSPECTION',?,?::timestamptz,?,?)",
                          tx,
                          a,
                          taskId,
                          OffsetDateTime.now(ZoneOffset.UTC),
                          user,
                          "绕过服务层");
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,"
                              + "on_hand_delta,reserved_delta) values(?,?,?,?,?,0)",
                          UUID.randomUUID(),
                          a,
                          tx,
                          balancePendingA,
                          new BigDecimal("-60"));
                      sql.execute(
                          "update pending_inspection_task set status='DISPOSED',"
                              + "inventory_transaction_id=?,disposed_at=?::timestamptz,disposed_by=?,"
                              + "sellable_quantity_before=0,sellable_quantity_after=0 where id=?",
                          tx,
                          OffsetDateTime.now(ZoneOffset.UTC),
                          user,
                          taskId);
                      return null;
                    }))
        .hasMessageContaining("不变量");

    // 单子没有被标成已处置（事务回滚）。
    var row = access.run(actorA, "warehouse.pending-inspection.read", () -> service.detail(taskId).task());
    assertThat(row.status()).isEqualTo("RESULT_RECORDED");
  }

  @Test
  void 处置完成后是不可逆的() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);
    recordResult(taskId);
    access.run(
        actorA,
        "warehouse.pending-inspection.update",
        () ->
            service.dispose(
                taskId,
                new PendingInspectionDisposeInput(
                    2L,
                    List.of(
                        new PendingInspectionDisposeLineInput(
                            "SELLABLE", new BigDecimal("60"), qSellA, locStorageA, null)),
                    null),
                user));

    // 终态不可逆：改回「待发起质检」必须被拒。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () -> {
                      sql.execute(
                          "update pending_inspection_task set status='PENDING' where id=?", taskId);
                      return null;
                    }))
        .hasMessageContaining("终态");

    // 取消也不允许（货已经动了）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.update",
                    () ->
                        service.cancel(
                            taskId, new PendingInspectionCancelInput("反悔"), user, "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void 待检品不能被销售预占() {
    // 蓝图加粗句：质检通过前不能被销售预占。V1 / V2 的守卫直接拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.pending-inspection.read",
                    () -> {
                      sql.execute(
                          "update inventory_balance set reserved=1 where id=?", balancePendingA);
                      return null;
                    }))
        .hasMessageContaining("不可预占");
  }

  // ================================================================ 时间

  @Test
  void 业务时间写回的值逐字相等不偏八小时() {
    UUID taskId = createTask(new BigDecimal("60"));
    requestInspection(taskId);

    var before = OffsetDateTime.now(ZoneOffset.ofHours(8));
    access.run(
        actorA,
        "warehouse.pending-inspection.update",
        () ->
            service.recordResult(
                taskId, new PendingInspectionRecordResultInput("结论"), user, "Bearer tenant-a"));
    var after = OffsetDateTime.now(ZoneOffset.ofHours(8));

    var events =
        access.run(actorA, "warehouse.pending-inspection.read", () -> service.detail(taskId).events());
    var recorded =
        events.stream()
            .filter(e -> "RESULT_RECORDED".equals(e.eventType()))
            .findFirst()
            .orElseThrow();
    // 断言的是**写回来的值**：偏 28800 秒的时间戳在任何断言里都「看起来是个时间」，
    // 只有与请求时刻的真实区间比较才会露馅。
    assertThat(recorded.occurredAt().toInstant())
        .isBetween(before.toInstant().minusSeconds(60), after.toInstant().plusSeconds(60));
  }

  // ================================================================ HTTP 面

  @Test
  void http契约面() throws Exception {
    UUID taskId = createTask(new BigDecimal("60"));

    // 未认证 → 401
    http.perform(get("/warehouse/pending-inspections")).andExpect(status().isUnauthorized());
    // 不存在的单 → 404
    http.perform(get("/warehouse/pending-inspections/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    // 能力声明与清单
    http.perform(get("/warehouse/pending-inspections/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingIsNotSellable").value(true))
        .andExpect(jsonPath("$.hardRules.length()").value(5));
    http.perform(get("/warehouse/pending-inspections/stock").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1));
    http.perform(get("/warehouse/pending-inspections").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    http.perform(get("/warehouse/pending-inspections/{id}", taskId)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.taskCode").value(matchesPattern("PI-\\d{8}")));
    // 处置：入参校验（缺 expectedVersion）→ 400
    http.perform(
            post("/warehouse/pending-inspections/{id}/dispose", taskId)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[]}"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 工具

  private UUID createTask(BigDecimal quantity) {
    return access
        .run(
            actorA,
            "warehouse.pending-inspection.update",
            () ->
                service.create(
                    new PendingInspectionTaskCreateInput(
                        bucketPendingA, receiptLineA, quantity, null),
                    user))
        .id();
  }

  private void requestInspection(UUID taskId) {
    access.run(
        actorA,
        "warehouse.pending-inspection.update",
        () ->
            service.requestInspection(
                taskId, new PendingInspectionRequestInspectionInput(TEMPLATE, null), user, "Bearer tenant-a"));
  }

  private void recordResult(UUID taskId) {
    access.run(
        actorA,
        "warehouse.pending-inspection.update",
        () ->
            service.recordResult(
                taskId, new PendingInspectionRecordResultInput(null), user, "Bearer tenant-a"));
  }
}
