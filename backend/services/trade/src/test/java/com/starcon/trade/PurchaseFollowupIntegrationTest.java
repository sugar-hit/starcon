package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseFollowupModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 6.3.2.1 跟单与交期的功能测试。
 *
 * <p><b>重点是三个口径</b>（延迟天数、当前承诺日、回写），它们每一个都容易悄悄算错：
 *
 * <ul>
 *   <li><b>延迟天数</b>按「到货时刻生效的承诺日」算 —— 供应商改期后按新日期到货是<b>准时</b>。</li>
 *   <li><b>未到货的单子</b>不算延迟天数（没有终点），但另算已逾期天数。</li>
 *   <li><b>回写</b>必须留下真实结果；失败要留 FAILED，而<b>不能</b>把交期变更一起回滚。</li>
 * </ul>
 */
class PurchaseFollowupIntegrationTest extends PurchaseFollowupFixture {

  @Autowired PurchaseFollowupService followups;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    warehouseDown.set(false);
    catalogDown.set(false);
    stockRows.clear();
    transitRows.clear();
  }

  // ---------------------------------------------------------------- 工具

  private PurchaseFollowupRecordQuery followupQuery(int page, int size) {
    return new PurchaseFollowupRecordQuery(
        null, null, null, null, null, null, null, page, size, userA, departmentA, "ALL");
  }

  private PurchaseFollowupChangeQuery changeQuery(int page, int size) {
    return new PurchaseFollowupChangeQuery(
        null, null, null, null, null, page, size, userA, departmentA, "ALL");
  }

  // ================================================================ 跟单记录

  @Test
  void 登记跟单记录_供应商快照取自采购单而不是客户端() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));

    var row =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordFollowup(
                    new PurchaseFollowupRecordInput(
                        "PO-FU-1",
                        null,
                        // 客户端**故意**传一个别的供应商 id：供应商快照必须来自采购单。
                        supplierB,
                        PurchaseFollowupType.URGING,
                        PurchaseFollowupChannel.PHONE,
                        "催一下这批货，供应商说本周五前发货",
                        "供应商说可以",
                        OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
                        null),
                    userA,
                    "ALL",
                    departmentA));

    assertThat(row.followupNo()).startsWith("PF-");
    assertThat(row.purchaseOrderNo()).isEqualTo("PO-FU-1");
    assertThat(row.supplierRefId()).isEqualTo(supplierA);
    assertThat(row.supplierCode()).isEqualTo("SUP-A");
    assertThat(row.supplierName()).isEqualTo("供应商 A");
    assertThat(row.followupTypeLabel()).isEqualTo("催单");
    assertThat(row.channelLabel()).isEqualTo("电话");
    assertThat(row.content()).contains("催一下");

    // 列表能读回来。
    var page = as("Bearer tenant-a", () -> followups.listFollowups(followupQuery(0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).followupNo()).isEqualTo(row.followupNo());
  }

  @Test
  void 跟单记录_采购单不存在时拒绝() {
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordFollowup(
                            new PurchaseFollowupRecordInput(
                                "PO-NOT-EXIST",
                                null,
                                null,
                                PurchaseFollowupType.CONFIRM,
                                PurchaseFollowupChannel.EMAIL,
                                "确认一下",
                                null,
                                OffsetDateTime.now(ZoneOffset.UTC),
                                null),
                            userA,
                            "ALL",
                            departmentA)))
        .hasMessageContaining("采购单不存在");
  }

  @Test
  void 跟单记录_未来的沟通时刻被拒() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-FUT", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordFollowup(
                            new PurchaseFollowupRecordInput(
                                "PO-FU-FUT",
                                null,
                                null,
                                PurchaseFollowupType.CONFIRM,
                                PurchaseFollowupChannel.EMAIL,
                                "未来的沟通",
                                null,
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                                null),
                            userA,
                            "ALL",
                            departmentA)))
        .hasMessageContaining("不能晚于当前时间");
  }

  @Test
  void 跟单记录_下次跟进时刻必须晚于沟通时刻() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-NEXT", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    var occurred = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordFollowup(
                            new PurchaseFollowupRecordInput(
                                "PO-FU-NEXT",
                                null,
                                null,
                                PurchaseFollowupType.CONFIRM,
                                PurchaseFollowupChannel.EMAIL,
                                "确认一下",
                                null,
                                occurred,
                                occurred.minusHours(1)),
                            userA,
                            "ALL",
                            departmentA)))
        .hasMessageContaining("必须晚于");
  }

  @Test
  void 跟单记录_空内容被DB拒() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-EMPTY", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    // 绕过服务层直接写：DB 的 CHECK 必须拒。
    assertThatThrownBy(
            () ->
                as("Bearer tenant-a", 
                    () -> {
                      sql.execute(
                          "insert into purchase_followup_record(id,tenant_id,followup_no,purchase_order_no,"
                              + " supplier_ref_id,supplier_code,supplier_name,followup_type,channel,content,"
                              + " supplier_reply,occurred_at,created_by,created_at)"
                              + " values (?::uuid,?::uuid,'PF-X','PO-FU-EMPTY',?::uuid,'SUP-A','供应商 A',"
                              + " 'URGING','PHONE','','',now(),?::uuid,now())",
                          UUID.randomUUID(), tenantA, supplierA, userA);
                      return null;
                    }))
        .hasMessageContaining("purchase_followup_record");
  }

  @Test
  void 跟单记录_是追加式_改不动也删不掉() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-IMM", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    var row =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordFollowup(
                    new PurchaseFollowupRecordInput(
                        "PO-FU-IMM", null, null, PurchaseFollowupType.URGING,
                        PurchaseFollowupChannel.PHONE, "催单内容", null,
                        OffsetDateTime.now(ZoneOffset.UTC), null),
                    userA, "ALL", departmentA));

    // 🔴 **双重收回**：生产运行角色 trade_app 被 REVOKE 掉了 UPDATE / DELETE
    // （DB 直接拒），而**迁移用户（超级用户）也绕不过触发器** —— 那是第二道闸。
    assertThatThrownBy(
            () ->
                as("Bearer tenant-a",
                    () -> {
                      sql.execute(
                          "update purchase_followup_record set content='改掉了' where tenant_id=?::uuid and id=?::uuid",
                          tenantA, row.id());
                      return null;
                    }))
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(
            () ->
                as("Bearer tenant-a",
                    () -> {
                      sql.execute(
                          "delete from purchase_followup_record where tenant_id=?::uuid and id=?::uuid",
                          tenantA, row.id());
                      return null;
                    }))
        .hasStackTraceContaining("permission denied");

    // 用**超级用户**连接直连 SQL：权限层被绕开了，触发器必须仍然把它挡下来。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_followup_record set content='改掉了' where tenant_id=?::uuid and id=?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, row.id());
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("不可改写");
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "delete from purchase_followup_record where tenant_id=?::uuid and id=?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, row.id());
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("不可改写");
    }
  }

  @Test
  void 跟单记录_timestamptz按写回的偏移往返_不漂移() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-FU-TZ", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    // 写一个带 +08:00 的时刻：断言读回**逐刻相等**（不是「没抛异常」）。
    OffsetDateTime occurred = OffsetDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    var row =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordFollowup(
                    new PurchaseFollowupRecordInput(
                        "PO-FU-TZ", null, null, PurchaseFollowupType.EXCEPTION,
                        PurchaseFollowupChannel.IM, "异常：包装破损", null, occurred, null),
                    userA, "ALL", departmentA));
    assertThat(row.occurredAt()).isEqualTo(occurred);
    assertThat(row.occurredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    // 再读一遍库里的原始值，确认不是只在内存里相等。
    var raw =
        as("Bearer tenant-a", 
            () ->
                sql.fetchOne(
                        "select occurred_at from purchase_followup_record where tenant_id=?::uuid and id=?::uuid",
                        tenantA, row.id())
                    .get(0, OffsetDateTime.class));
    assertThat(raw.toInstant()).isEqualTo(occurred.toInstant());
    assertThat(raw.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
  }

  // ================================================================ 交期变更

  @Test
  void 交期变更_落进自己的历史表_不改采购单() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DC-1", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));

    var changed =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordChange(
                    new PurchaseFollowupChangeInput(
                        "PO-DC-1", null, null,
                        LocalDate.of(2026, 4, 10),
                        LocalDate.of(2026, 4, 20),
                        "供应商说产能排满了",
                        null),
                    userA, "ALL", departmentA));

    assertThat(changed.changeNo()).startsWith("PDC-");
    assertThat(changed.fromPromisedDate()).isEqualTo(LocalDate.of(2026, 4, 10));
    assertThat(changed.toPromisedDate()).isEqualTo(LocalDate.of(2026, 4, 20));
    // 🔴 **两个承诺口径都在响应里**：单据承诺日没变（6.3.1.1 拥有它），跟单承诺日变了。
    assertThat(changed.documentPromiseDate()).isEqualTo(LocalDate.of(2026, 4, 10));
    assertThat(changed.followupPromiseDate()).isEqualTo(LocalDate.of(2026, 4, 20));
    assertThat(changed.promiseDiverged()).isTrue();

    // 🔴 **采购单的承诺到货日一个字都没动**（零 ALTER / 零 UPDATE 别人的表）。
    var docPromise =
        as("Bearer tenant-a", 
            () ->
                sql.fetchOne(
                        "select promised_arrival_date from purchase_order where tenant_id=?::uuid and order_no=?::varchar",
                        tenantA, "PO-DC-1")
                    .get(0, LocalDate.class));
    assertThat(docPromise).isEqualTo(LocalDate.of(2026, 4, 10));
  }

  @Test
  void 交期变更_变更前日期与当前跟单承诺日不一致时拒绝() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DC-2", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    asWrite(
        "Bearer tenant-a",
        () ->
            followups.recordChange(
                new PurchaseFollowupChangeInput(
                    "PO-DC-2", null, null, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 20),
                    "第一次改期", null),
                userA, "ALL", departmentA));

    // 第二次改期必须从 4/20 起算，而不是从 4/10。
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordChange(
                            new PurchaseFollowupChangeInput(
                                "PO-DC-2", null, null, LocalDate.of(2026, 4, 10),
                                LocalDate.of(2026, 4, 25), "第二次改期（起点写错了）", null),
                            userA, "ALL", departmentA)))
        .hasMessageContaining("当前跟单承诺日不一致");
  }

  @Test
  void 交期变更_改成同一天不是变更() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DC-3", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordChange(
                            new PurchaseFollowupChangeInput(
                                "PO-DC-3", null, null, LocalDate.of(2026, 4, 10),
                                LocalDate.of(2026, 4, 10), "没变", null),
                            userA, "ALL", departmentA)))
        .hasMessageContaining("不是一次交期变更");
  }

  @Test
  void 交期变更_已关闭的单子不能改交期() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DC-CLOSED", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "CLOSED", OffsetDateTime.now(ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", 
                    () ->
                        followups.recordChange(
                            new PurchaseFollowupChangeInput(
                                "PO-DC-CLOSED", null, null, LocalDate.of(2026, 4, 10),
                                LocalDate.of(2026, 4, 20), "想改", null),
                            userA, "ALL", departmentA)))
        .hasMessageContaining("已关闭");
  }

  @Test
  void 交期变更_原因是必填的() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DC-REASON", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    // 绕过服务层：DB 的 CHECK 必须拒空原因。
    assertThatThrownBy(
            () ->
                as("Bearer tenant-a", 
                    () -> {
                      sql.execute(
                          "insert into purchase_delivery_change(id,tenant_id,change_no,purchase_order_no,"
                              + " supplier_ref_id,supplier_code,supplier_name,from_promised_date,"
                              + " to_promised_date,reason,changed_by,changed_at)"
                              + " values (?::uuid,?::uuid,'PDC-X','PO-DC-REASON',?::uuid,'SUP-A','供应商 A',"
                              + " ?::date,?::date,'   ',?::uuid,now())",
                          UUID.randomUUID(), tenantA, supplierA,
                          java.sql.Date.valueOf(LocalDate.of(2026, 4, 10)),
                          java.sql.Date.valueOf(LocalDate.of(2026, 4, 20)), userA);
                      return null;
                    }))
        .hasMessageContaining("purchase_delivery_change");
  }

  // ================================================================ 延迟统计

  @Test
  void 延迟天数_按到货时刻生效的承诺日算_改期后到货算准时() throws Exception {
    // 单据承诺 4/10；4/5 改期到 4/20；4/18 到货 ⇒ **准时**（提前 2 天）。
    seedOrder(
        tenantA, userA, departmentA, "PO-DELAY-1", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT",
        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    seedArrivalTracking(
        tenantA, "PO-DELAY-1", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), OffsetDateTime.of(2026, 4, 18, 10, 0, 0, 0, ZoneOffset.UTC));

    // 改期（改期时刻早于到货时刻 ⇒ 4/20 才是到货时生效的承诺日）。
    asWrite(
        "Bearer tenant-a",
        () ->
            followups.recordChange(
                new PurchaseFollowupChangeInput(
                    "PO-DELAY-1", null, null, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 20),
                    "供应商说排产延后",
                    OffsetDateTime.of(2026, 4, 5, 9, 0, 0, 0, ZoneOffset.UTC)),
                userA, "ALL", departmentA));

    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    assertThat(stats.arrivedOrderCount()).isEqualTo(1);
    // 🔴 -2 天（提前 2 天到货），**不是** +8 天（按原始承诺日算会把守约说成违约）。
    assertThat(stats.averageDelayDays()).isEqualByComparingTo("-2");
    assertThat(stats.onTimeCount()).isEqualTo(1);
    assertThat(stats.onTimeRate()).isEqualByComparingTo("100.00");
  }

  @Test
  void 延迟天数_没改期时按单据承诺日算() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DELAY-2", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT",
        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    seedArrivalTracking(
        tenantA, "PO-DELAY-2", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), OffsetDateTime.of(2026, 4, 15, 10, 0, 0, 0, ZoneOffset.UTC));

    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    assertThat(stats.averageDelayDays()).isEqualByComparingTo("5");
    assertThat(stats.onTimeCount()).isZero();
    assertThat(stats.onTimeRate()).isEqualByComparingTo("0.00");
  }

  @Test
  void 未到货的单子不算延迟天数_但另算已逾期天数() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DELAY-3", supplierA, "SUP-A", "供应商 A",
        today().minusDays(5), "IN_FULFILLMENT",
        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    // 到货跟踪存在但**没有到货流水** ⇒ 还没全部到货。
    seedArrivalTracking(
        tenantA, "PO-DELAY-3", supplierA, "SUP-A", "供应商 A", today().minusDays(5), null);

    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    assertThat(stats.arrivedOrderCount()).isZero();
    // ⚠️ 没有已到货样本 ⇒ 平均延迟天数是 **null（算不出来）而不是 0**。
    assertThat(stats.averageDelayDays()).isNull();
    assertThat(stats.onTimeRate()).isNull();
    assertThat(stats.pendingOrderCount()).isEqualTo(1);
    assertThat(stats.overduePendingCount()).isEqualTo(1);
    assertThat(stats.averageOverdueDays()).isEqualByComparingTo("5");

    // 逐单明细：delayDays 是 null，overdueDays 是 5。
    var detail = as("Bearer tenant-a", () -> followups.deliveryDetail(null, null, false, false, 0, 50));
    var row = detail.rows().get(0);
    assertThat(row.purchaseOrderNo()).isEqualTo("PO-DELAY-3");
    assertThat(row.delayDays()).isNull();
    assertThat(row.overdueDays()).isEqualTo(5);
    assertThat(row.arrivalStatus()).isEqualTo("PENDING_SHIPMENT");
  }

  @Test
  void 部分到货的单子不进延迟统计样本() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DELAY-4", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT",
        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    // 只收一半 ⇒ 状态是 PARTIALLY_RECEIVED，不是 FULLY_RECEIVED。
    seedPartialArrival(tenantA, "PO-DELAY-4", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), OffsetDateTime.of(2026, 4, 15, 10, 0, 0, 0, ZoneOffset.UTC));

    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    assertThat(stats.arrivedOrderCount()).isZero();
    assertThat(stats.averageDelayDays()).isNull();
  }

  @Test
  void 延迟统计_汇总与逐单明细一致() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-DELAY-5", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), "IN_FULFILLMENT",
        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    seedArrivalTracking(
        tenantA, "PO-DELAY-5", supplierA, "SUP-A", "供应商 A",
        LocalDate.of(2026, 4, 10), OffsetDateTime.of(2026, 4, 12, 10, 0, 0, 0, ZoneOffset.UTC));

    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    var detail = as("Bearer tenant-a", () -> followups.deliveryDetail(null, null, false, false, 0, 50));
    assertThat(detail.total()).isEqualTo(1);
    assertThat(detail.rows().get(0).delayDays()).isEqualTo(2);
    assertThat(stats.averageDelayDays()).isEqualByComparingTo("2");
    // 两个承诺口径都在明细里。
    assertThat(detail.rows().get(0).documentPromiseDate()).isEqualTo(LocalDate.of(2026, 4, 10));
    assertThat(detail.rows().get(0).followupPromiseDate()).isEqualTo(LocalDate.of(2026, 4, 10));
    assertThat(detail.rows().get(0).promiseDiverged()).isFalse();
  }

  // ================================================================ 回写

  @Test
  void 回写_交期变更触发两个下游的重算() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-WB-1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC).minusDays(20));
    seedCoveragePolicy(tenantA, 30);
    // 让 6.2.1.1 真的算出一个 SKU（销售速度登记 = 它的 SKU 全集之一）。
    UUID sku = UUID.nameUUIDFromBytes("SKU-PO-WB-1".getBytes());
    seedVelocity(tenantA, sku, "SKU-PO-WB-1", "5");

    var changed =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordChange(
                    new PurchaseFollowupChangeInput(
                        "PO-WB-1", null, null, today().plusDays(10), today().plusDays(25),
                        "供应商说排产延后", null),
                    userA, "ALL", departmentA));

    var stockout =
        access.run(
            actors.get("Bearer tenant-a"),
            PurchaseFollowupService.UPDATE_PERMISSION,
            () -> followups.writebackTo(changed.id(), "6.2.1.1", userA, "Bearer tenant-a"));
    assertThat(stockout.status()).isEqualTo("SUCCEEDED");
    assertThat(stockout.available()).isTrue();
    // 🔴 交期事实**真的**进了 6.2.1.1 的入参（备货周期）。
    assertThat(stockout.deliveryInputApplied()).isTrue();
    assertThat(stockout.resultRef()).isNotEmpty();
    assertThat(stockout.ownerModule()).isEqualTo("6.2.1.1");
    assertThat(stockout.impact()).contains("租户级");

    var replenishment =
        access.run(
            actors.get("Bearer tenant-a"),
            PurchaseFollowupService.UPDATE_PERMISSION,
            () -> followups.writebackTo(changed.id(), "6.2.1.2", userA, "Bearer tenant-a"));
    assertThat(replenishment.status()).isEqualTo("SUCCEEDED");
    assertThat(replenishment.available()).isTrue();
    // 🔴 6.2.1.2 的交期来自 catalog 报价与它自有的采购配置 ⇒ 交期数值**没有**进它。
    assertThat(replenishment.deliveryInputApplied()).isFalse();
    assertThat(replenishment.impact()).contains("无法");
    assertThat(replenishment.ownerModule()).isEqualTo("6.2.1.2");
  }

  @Test
  void 回写_下游不可用时如实记FAILED且交期变更仍然落库() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-WB-2", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC).minusDays(20));
    seedVelocity(tenantA, UUID.nameUUIDFromBytes("SKU-PO-WB-2".getBytes()), "SKU-PO-WB-2", "5");

    var changed =
        asWrite(
            "Bearer tenant-a",
            () ->
                followups.recordChange(
                    new PurchaseFollowupChangeInput(
                        "PO-WB-2", null, null, today().plusDays(10), today().plusDays(25),
                        "供应商说排产延后", null),
                    userA, "ALL", departmentA));

    // warehouse 挂掉 ⇒ 6.2.1.1 的重算必然失败。
    warehouseDown.set(true);
    assertThatThrownBy(
            () ->
                access.run(
                    actors.get("Bearer tenant-a"),
                    PurchaseFollowupService.UPDATE_PERMISSION,
                    () -> followups.writebackTo(changed.id(), "6.2.1.1", userA, "Bearer tenant-a")))
        .hasMessageContaining("仓库");

    // 在**另一个**事务里如实补一条 FAILED 留痕。
    var failed =
        access.run(
            actors.get("Bearer tenant-a"),
            PurchaseFollowupService.UPDATE_PERMISSION,
            () ->
                followups.recordWritebackFailure(
                    changed.id(), "6.2.1.1", new RuntimeException("仓库服务暂时不可达")));
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.available()).isFalse();
    assertThat(failed.errorMessage()).contains("仓库");

    // 🔴 **交期变更本身仍然在**（回写失败不回滚硬事实）。
    var page = as("Bearer tenant-a", () -> followups.listChanges(changeQuery(0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).changeNo()).isEqualTo(changed.changeNo());
    assertThat(page.rows().get(0).writeback()).hasSize(1);
    assertThat(page.rows().get(0).writeback().get(0).status()).isEqualTo("FAILED");
  }

  @Test
  void 能力声明_如实标出供应商评级未交付() {
    var caps = as("Bearer tenant-a", () -> followups.capabilities());
    var rating =
        caps.capabilities().stream()
            .filter(c -> "supplierRating".equals(c.name()))
            .findFirst()
            .orElseThrow();
    assertThat(rating.available()).isFalse();
    assertThat(rating.ownerModule()).isEqualTo("6.1.1.3");
    assertThat(rating.impact()).contains("未交付");

    // 准时率是本模块自己算的，但**评级回写**如实标 unavailable。
    var stats = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    assertThat(stats.ratingWriteback().available()).isFalse();
    assertThat(stats.ratingWriteback().ownerModule()).isEqualTo("6.1.1.3");
  }

  // ================================================================ 时间线与 HTTP

  @Test
  void 跟单时间线_跟单与交期变更合并成一条流() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-TL-1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));

    asWrite(
        "Bearer tenant-a",
        () ->
            followups.recordFollowup(
                new PurchaseFollowupRecordInput(
                    "PO-TL-1", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
                    "催一下", null, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2), null),
                userA, "ALL", departmentA));
    asWrite(
        "Bearer tenant-a",
        () ->
            followups.recordChange(
                new PurchaseFollowupChangeInput(
                    "PO-TL-1", null, null, today().plusDays(10), today().plusDays(25),
                    "改期", null),
                userA, "ALL", departmentA));

    var timeline =
        as("Bearer tenant-a", 
            () ->
                followups.timeline(
                    new PurchaseFollowupTimelineQuery(
                        "PO-TL-1", null, null, null, null, 0, 50, userA, departmentA, "ALL")));
    assertThat(timeline.total()).isEqualTo(2);
    assertThat(timeline.rows().get(0).kind()).isEqualTo("DELIVERY_CHANGE");
    assertThat(timeline.rows().get(0).fromPromisedDate()).isNotNull();
    assertThat(timeline.rows().get(0).toPromisedDate()).isNotNull();
    assertThat(timeline.rows().get(1).kind()).isEqualTo("FOLLOWUP");
    assertThat(timeline.rows().get(1).title()).isEqualTo("催单");
  }

  @Test
  void HTTP_端点走通并且租户头不被信任() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-HTTP-1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));

    http.perform(
            post("/trade/purchase-followups")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"purchaseOrderNo\":\"PO-HTTP-1\",\"followupType\":\"URGING\","
                        + "\"channel\":\"PHONE\",\"content\":\"电话催单\","
                        + "\"occurredAt\":\"2026-03-12T09:30:00+08:00\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.followupNo").value(org.hamcrest.Matchers.startsWith("PF-")))
        .andExpect(jsonPath("$.supplierCode").value("SUP-A"))
        .andExpect(jsonPath("$.occurredAt").value("2026-03-12T09:30:00+08:00"));

    // 伪造的租户头无效：带着 B 的令牌 + A 的 X-Tenant-Id，读到的仍然是 B 的（空）数据。
    http.perform(
            get("/trade/purchase-followups")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void HTTP_交期变更端点返回回写留痕() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-HTTP-2", supplierA, "SUP-A", "供应商 A",
        today().plusDays(10), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC).minusDays(20));
    seedCoveragePolicy(tenantA, 30);
    seedVelocity(tenantA, UUID.nameUUIDFromBytes("SKU-PO-HTTP-2".getBytes()), "SKU-PO-HTTP-2", "5");

    http.perform(
            post("/trade/purchase-followups/changes")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"purchaseOrderNo\":\"PO-HTTP-2\",\"fromPromisedDate\":\""
                        + today().plusDays(10)
                        + "\",\"toPromisedDate\":\""
                        + today().plusDays(25)
                        + "\",\"reason\":\"供应商说排产延后\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changeNo").value(org.hamcrest.Matchers.startsWith("PDC-")))
        .andExpect(jsonPath("$.writeback.length()").value(2))
        .andExpect(jsonPath("$.writeback[0].targetModule").value("6.2.1.1"))
        .andExpect(jsonPath("$.writeback[1].targetModule").value("6.2.1.2"));
  }

  // ---------------------------------------------------------------- 局部到货

  /** 种一次**部分**到货（状态 PARTIALLY_RECEIVED，不进延迟统计样本）。 */
  private void seedPartialArrival(
      UUID tenant,
      String orderNo,
      UUID supplier,
      String supplierCode,
      String supplierName,
      LocalDate promised,
      OffsetDateTime arrivedAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_arrival_tracking(id,tenant_id,tracking_no,purchase_order_no,"
                    + " source_doc_no,source_doc_type,supplier_ref_id,supplier_code,supplier_name,"
                    + " receivable_qty,unit_price,currency,promised_arrival_date,arrival_status,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,'PURCHASE_ORDER',"
                    + " ?::uuid,?::varchar,?::varchar,?::numeric,?::numeric,'USD',?::date,"
                    + " 'PENDING_SHIPMENT',?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "PA-TEST-" + orderNo);
      ps.setString(4, orderNo);
      ps.setString(5, orderNo);
      ps.setObject(6, supplier);
      ps.setString(7, supplierCode);
      ps.setString(8, supplierName);
      ps.setBigDecimal(9, new BigDecimal("100"));
      ps.setBigDecimal(10, new BigDecimal("2.5"));
      ps.setDate(11, java.sql.Date.valueOf(promised));
      ps.setObject(12, userA);
      ps.executeUpdate();
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_arrival_receipt(id,tenant_id,tracking_id,arrived_qty,"
                    + " arrived_at,recorded_at,warehouse_receipt_code,warehouse_receipt_received_qty,"
                    + " warehouse_receipt_synced_at,note,recorded_by)"
                    + " values (?::uuid,?::uuid,?::uuid,?::numeric,?::timestamptz,now(),?::varchar,"
                    + " ?::numeric,now(),'上游事实',?::uuid)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setObject(3, id);
      ps.setBigDecimal(4, new BigDecimal("40"));
      ps.setObject(5, arrivedAt);
      ps.setString(6, "GR-TEST-" + orderNo);
      ps.setBigDecimal(7, new BigDecimal("40"));
      ps.setObject(8, userA);
      ps.executeUpdate();
    }
  }
}
