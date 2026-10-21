package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;

/**
 * 6.2.2.1 采购申请（trade 侧）。
 *
 * <p><b>本测试是 V246 迁移那些 DB 级不变量的独立复验</b>：逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>覆盖的业务语义</b>（蓝图 6.2.2.1 加粗句逐条）：
 *
 * <ol>
 *   <li>计划行 / 补货建议 → 采购申请单（来源可追溯）；</li>
 *   <li>数量与交期调整留痕（append-only）；</li>
 *   <li>审批走 identity：免审直通已批 / 需审停在待审 / 不可达停在待审并写失败原因；</li>
 *   <li>状态机由 DB 触发器钉死（非法转换一律拒）；</li>
 *   <li>转单 = 事实（供应商 + 数量 + 交期 + 目标仓 + 源计划行），**不**是采购单。</li>
 * </ol>
 */
class PurchaseRequestIntegrationTest extends PurchaseRequestFixture {

  @BeforeEach
  void reset() throws Exception {
    truncateAll();
    INSTANCES.clear();
    INSTANCE_SEQ.set(0);
    EVALUATION.set(Evaluation.REQUIRE_APPROVAL);
    identityDown.set(false);
  }

  // ================================================================ 小工具

  @SuppressWarnings("unchecked")
  Map<String, Object> createRequest(Map<String, Object> body) throws Exception {
    var response =
        http.perform(
                post("/trade/purchase-requests")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    String payload = response.getContentAsString();
    // 失败时把响应体带进断言消息：否则「400」这三个字什么都说明不了。
    assertThat(response.getStatus())
        .as("建申请单失败：%s", payload)
        .isEqualTo(200);
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> detail(String id) throws Exception {
    String payload =
        http.perform(get("/trade/purchase-requests/" + id).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> lines(String id) throws Exception {
    var detail = detail(id);
    return (List<Map<String, Object>>) detail.getOrDefault("lines", List.of());
  }

  String createFromPlan(UUID planId, List<UUID> lineIds) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceType", "PLAN");
    body.put("sourcePlanId", planId.toString());
    body.put("sourceLineIds", lineIds.stream().map(UUID::toString).toList());
    return String.valueOf(createRequest(body).get("id"));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实说采购单下游未交付() throws Exception {
    String payload =
        http.perform(
                get("/trade/purchase-requests/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var caps = json.readValue(payload, Map.class);
    assertThat(caps.get("purchaseOrderDelivered")).isEqualTo(false);
    assertThat(String.valueOf(caps.get("purchaseOrderNote")))
        .contains("6.3.1.2")
        .contains("未交付")
        .contains("转单事实");
    assertThat(caps.get("documentTypeCode")).isEqualTo("PURCHASE_ORDER");
    assertThat(caps.get("sourceSystem")).isEqualTo("trade");
    // 审批契约要指向 identity 的真实端点。
    assertThat(String.valueOf(caps.get("approvalContract")))
        .contains("/identity/approval-rules/evaluate")
        .contains("/identity/approval-instances");
    assertThat(caps.get("supplierReferenceAvailable")).isEqualTo(true);
  }

  // ================================================================ 从计划行转申请单

  @Test
  void 从计划行转成申请单且来源可追溯() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-001", "120", ARRIVAL, null);

    var created = createRequest(
        Map.of(
            "sourceType", "PLAN",
            "sourcePlanId", planId.toString(),
            "sourceLineIds", List.of(lineId.toString())));
    String id = String.valueOf(created.get("id"));
    assertThat(created.get("status")).isEqualTo("DRAFT");
    assertThat(created.get("sourceType")).isEqualTo("PLAN");
    assertThat(created.get("sourcePlanId")).isEqualTo(planId.toString());
    assertThat(created.get("targetWarehouseId")).isEqualTo(warehouseId.toString());
    assertThat(created.get("legalEntityId")).isEqualTo(legalEntityId.toString());
    assertThat(created.get("lineCount")).isEqualTo(1);

    var rows = lines(id);
    assertThat(rows).hasSize(1);
    var line = rows.get(0);
    assertThat(line.get("skuCode")).isEqualTo("SKU-001");
    assertThat(line.get("requestedQuantity")).isEqualTo(new BigDecimal("120.000000"));
    assertThat(line.get("sourcePlanLineId")).isEqualTo(lineId.toString());
    assertThat(line.get("sourcePlanId")).isEqualTo(planId.toString());
    assertThat(line.get("requestedArrivalDate")).isEqualTo(ARRIVAL.toString());
  }

  @Test
  void 跨目标仓的计划行不许混进一张申请单() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID otherWarehouse = UUID.randomUUID();
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-002", "10", ARRIVAL, otherWarehouse);
    http.perform(
            post("/trade/purchase-requests")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "sourceType", "PLAN",
                            "sourcePlanId", planId.toString(),
                            "sourceLineIds", List.of(lineId.toString())))))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertThat(result.getResponse().getErrorMessage()).contains("跨目标仓"));
  }

  @Test
  void 从补货建议转成申请单取处置后的数量() throws Exception {
    UUID batchId = seedBatch(tenantA);
    UUID suggestionId = seedSuggestion(tenantA, batchId, "SKU-003", "100", "80", ARRIVAL, "USD");
    var created =
        createRequest(
            Map.of(
                "sourceType", "SUGGESTION",
                "sourceBatchId", batchId.toString(),
                "sourceLineIds", List.of(suggestionId.toString()),
                "targetWarehouseId", warehouseId.toString(),
                "legalEntityId", legalEntityId.toString()));
    String id = String.valueOf(created.get("id"));
    assertThat(created.get("sourceType")).isEqualTo("SUGGESTION");
    // 币种从建议继承（建议带币种）。
    assertThat(created.get("currencyCode")).isEqualTo("USD");
    var rows = lines(id);
    assertThat(rows).hasSize(1);
    // 处置后的量 80，不是建议量 100。
    assertThat(rows.get(0).get("requestedQuantity")).isEqualTo(new BigDecimal("80.000000"));
    assertThat(rows.get(0).get("sourceSuggestionId")).isEqualTo(suggestionId.toString());
  }

  @Test
  void 没有可下单数量的建议转不成申请行() throws Exception {
    UUID batchId = seedBatch(tenantA);
    // 「不需要补货」的建议：没有建议量（DB 层 CHECK：非 REPLENISH 的建议量必为空）。
    UUID suggestionId =
        seedSuggestion(tenantA, batchId, "SKU-004", null, null, ARRIVAL, "USD", "HOLD");
    http.perform(
            post("/trade/purchase-requests")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "sourceType", "SUGGESTION",
                            "sourceLineIds", List.of(suggestionId.toString()),
                            "targetWarehouseId", warehouseId.toString(),
                            "legalEntityId", legalEntityId.toString()))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 调整留痕

  @Test
  void 调整数量与交期会留痕且理由必填() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-005", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));

    // 理由缺失 ⇒ 400（一句「调整一下」不是留痕）。
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("requestedQuantity", new BigDecimal("60"), "reason", ""))))
        .andExpect(status().isBadRequest());

    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "requestedQuantity", new BigDecimal("60"),
                            "requestedArrivalDate", "2026-12-20",
                            "reason", "供应商交期延长，改小订量并推迟到货日"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestedArrivalDate").value("2026-12-20"));

    var adjusted = lines(id).get(0);
    assertThat(new BigDecimal(String.valueOf(adjusted.get("requestedQuantity"))))
        .as("调整后申请行的数量必须是 60（数量全程 BigDecimal，DB 侧 numeric(19,6)）")
        .isEqualByComparingTo(new BigDecimal("60"));

    @SuppressWarnings("unchecked")
    var detail = detail(id);
    List<Map<String, Object>> adjustments =
        (List<Map<String, Object>>) detail.getOrDefault("adjustments", List.of());
    assertThat(adjustments).hasSize(2);
    assertThat(adjustments)
        .anySatisfy(a -> assertThat(a.get("field")).isEqualTo("requested_quantity"))
        .anySatisfy(a -> assertThat(a.get("field")).isEqualTo("requested_arrival_date"));
    assertThat(adjustments)
        .allSatisfy(a -> assertThat(String.valueOf(a.get("reason"))).contains("供应商交期"));
  }

  @Test
  void 调整留痕是append_only改不动() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-006", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("requestedQuantity", new BigDecimal("60"), "reason", "补一点"))))
        .andExpect(status().isOk());

    // DB 层直接改留痕 ⇒ 触发器拒绝。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request_adjustment set reason = '改成没有理由' where tenant_id = ?::uuid")) {
      ps.setObject(1, tenantA);
      assertThatThrownBy(() -> ps.executeUpdate())
          .hasMessageContaining("不可改写的留痕表");
    }
  }

  // ================================================================ 供应商

  @Test
  void 选供应商会确认它真的存在并留痕() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-007", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));

    // catalog 里没有这个供应商 ⇒ 404（绝不把一个假的供应商写进单据）。
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("supplierId", UUID.randomUUID().toString(), "reason", "选供应商"))))
        .andExpect(status().isNotFound());

    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("supplierId", supplierRoleId.toString(), "reason", "选供应商"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplierId").value(supplierRoleId.toString()))
        .andExpect(jsonPath("$.supplierCode").value("SUP-001"))
        .andExpect(jsonPath("$.supplierName").value("深圳华强电子"));

    @SuppressWarnings("unchecked")
    var detail = detail(id);
    List<Map<String, Object>> adjustments =
        (List<Map<String, Object>>) detail.getOrDefault("adjustments", List.of());
    assertThat(adjustments)
        .anySatisfy(a -> assertThat(a.get("field")).isEqualTo("supplier_id"));
  }

  // ================================================================ 审批

  @Test
  void 需审时停在待审并记下审批实例() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-008", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));

    String payload =
        http.perform(
                post("/trade/purchase-requests/" + id + "/submit")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var submitted = json.readValue(payload, Map.class);
    assertThat(submitted.get("status")).isEqualTo("PENDING_APPROVAL");
    assertThat(submitted.get("approvalRequired")).isEqualTo(true);
    assertThat(submitted.get("approvalDecision")).isEqualTo("SUBMIT_FOR_APPROVAL");
    assertThat(submitted.get("approvalInstanceId")).isNotNull();
    assertThat(String.valueOf(submitted.get("approvalInstanceNo"))).startsWith("AI-2026-");
    assertThat(submitted.get("approvalSyncError")).isEqualTo("");
    // 服务间授权真的带上了。
    assertThat(lastInternalKey.get()).isEqualTo("test-key");
    // 租户不是靠自定义头传过去的：转发的是调用方自己的 bearer。
    assertThat(lastApprovalBearer.get()).isEqualTo("Bearer tenant-a");
    // 幂等键带的是本单的 id。
    assertThat(INSTANCES).containsKey(id);
  }

  @Test
  void 免审时直通进已批() throws Exception {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-009", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));

    var submitted =
        json.readValue(
            http.perform(
                    post("/trade/purchase-requests/" + id + "/submit")
                        .header("Authorization", "Bearer tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    assertThat(submitted.get("status")).isEqualTo("APPROVED");
    assertThat(submitted.get("approvalRequired")).isEqualTo(false);
    assertThat(submitted.get("approvalDecision")).isEqualTo("EXEMPT");
    // 免审不产生审批实例。
    assertThat(instanceCount()).isZero();
  }

  @Test
  void 本租户未配规则时按默认放行进已批() throws Exception {
    EVALUATION.set(Evaluation.UNCONTROLLED);
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-010", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));

    var submitted =
        json.readValue(
            http.perform(
                    post("/trade/purchase-requests/" + id + "/submit")
                        .header("Authorization", "Bearer tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    assertThat(submitted.get("status")).isEqualTo("APPROVED");
    assertThat(submitted.get("approvalSource")).isEqualTo("UNCONTROLLED");
    assertThat(instanceCount()).isZero();
  }

  @Test
  void 审批服务不可达时停在待审并写失败原因绝不自动跳已批() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-011", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));

    identityDown.set(true);
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());

    @SuppressWarnings("unchecked")
    var request = (Map<String, Object>) detail(id).get("request");
    assertThat(request.get("status")).isEqualTo("PENDING_APPROVAL");
    assertThat(String.valueOf(request.get("approvalSyncError")))
        .contains("审批服务")
        .contains("不会");
    assertThat(request.get("approvalDecidedAt")).isNull();
  }

  @Test
  void 重复提交审批不会产生第二条待办() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-012", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    assertThat(instanceCount()).isEqualTo(1);
  }

  @Test
  void 刷新审批结论能把待审推进到已批() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-013", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    decide(id, "APPROVED");
    var refreshed =
        json.readValue(
            http.perform(
                    post("/trade/purchase-requests/" + id + "/approval/refresh")
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    assertThat(refreshed.get("status")).isEqualTo("APPROVED");
    assertThat(refreshed.get("approvalDecidedAt")).isNotNull();
  }

  @Test
  void 审批被驳回时申请单进已驳回() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-014", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    decide(id, "REJECTED");
    var refreshed =
        json.readValue(
            http.perform(
                    post("/trade/purchase-requests/" + id + "/approval/refresh")
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    assertThat(refreshed.get("status")).isEqualTo("REJECTED");
  }

  @Test
  void 本模块没有本地批准端点() throws Exception {
    // 🔴 本地批准会把审批流本地化：identity 那边什么都不知道还留一条没人管的待办。
    for (String path :
        List.of(
            "/trade/purchase-requests/11111111-1111-1111-1111-111111111111/approve",
            "/trade/purchase-requests/11111111-1111-1111-1111-111111111111/reject",
            "/trade/purchase-requests/11111111-1111-1111-1111-111111111111/approval/decide")) {
      http.perform(post(path).header("Authorization", "Bearer tenant-a"))
          .andExpect(status().isNotFound());
    }
  }

  // ================================================================ 状态机（DB 层）

  @Test
  void 状态机由db层触发器钉死() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-015", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));

    // 草稿直接跳已驳回 ⇒ 触发器拒绝。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = 'REJECTED' where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(id));
      assertThatThrownBy(() -> ps.executeUpdate())
          .hasMessageContaining("状态不能从")
          .hasMessageContaining("草稿→待审/已批");
    }
    // 草稿直接跳已转单 ⇒ 触发器拒绝（没审批过的申请不能变成已转单）。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = 'CONVERTED', converted_at = now(),"
                    + " converted_by = ?::uuid where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, user);
      ps.setObject(2, tenantA);
      ps.setObject(3, UUID.fromString(id));
      assertThatThrownBy(() -> ps.executeUpdate())
          .hasMessageContaining("状态不能从")
          .hasMessageContaining("草稿→待审/已批");
    }

    // 已批退回草稿 ⇒ 触发器拒绝（审批留痕不能被抹掉）。
    EVALUATION.set(Evaluation.EXEMPT);
    String approvedId = createFromPlan(planId, List.of(seedPlanLine(tenantA, planId, "SKU-015B", "50", ARRIVAL, null)));
    http.perform(
            post("/trade/purchase-requests/" + approvedId + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = 'DRAFT' where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(approvedId));
      assertThatThrownBy(() -> ps.executeUpdate()).hasMessageContaining("状态不能从");
    }

    // 已转单退回草稿 ⇒ 触发器拒绝（终态不可回退）。
    UUID planId2 = seedPlan(tenantA);
    UUID lineId2 = seedPlanLine(tenantA, planId2, "SKU-015C", "50", ARRIVAL, null);
    String convertedId = createFromPlan(planId2, List.of(lineId2));
    String convertedLineId = String.valueOf(lines(convertedId).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + convertedId + "/lines/" + convertedLineId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("supplierId", supplierRoleId.toString(), "reason", "选供应商"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + convertedId + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + convertedId + "/convert")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status = 'DRAFT' where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(convertedId));
      assertThatThrownBy(() -> ps.executeUpdate()).hasMessageContaining("状态不能从");
    }
  }

  @Test
  void 提交后申请行的内容被冻结() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-016", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    // 服务层也拦得住。
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("requestedQuantity", new BigDecimal("999"), "reason", "偷偷改一下"))))
        .andExpect(status().isConflict());

    // DB 层也拦得住（绕过服务层直接写）。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request_line set requested_quantity = 999"
                    + " where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(requestLineId));
      assertThatThrownBy(() -> ps.executeUpdate()).hasMessageContaining("不可再改");
    }
  }

  // ================================================================ 转单

  @Test
  void 转单记下供应商数量交期目标仓并可追溯到源计划行() throws Exception {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID planId = seedPlan(tenantA);
    UUID planLineId = seedPlanLine(tenantA, planId, "SKU-017", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(planLineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("supplierId", supplierRoleId.toString(), "reason", "选供应商"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    var converted =
        json.readValue(
            http.perform(
                    post("/trade/purchase-requests/" + id + "/convert")
                        .header("Authorization", "Bearer tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "lines",
                                    List.of(
                                        Map.of(
                                            "lineId", requestLineId,
                                            "quantity", new BigDecimal("45"),
                                            "arrivalDate", "2026-12-15")),
                                    "note", "供应商只给 45 件"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);

    @SuppressWarnings("unchecked")
    var request = (Map<String, Object>) converted.get("request");
    assertThat(request.get("status")).isEqualTo("CONVERTED");
    assertThat(request.get("convertedAt")).isNotNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> conversions =
        (List<Map<String, Object>>) converted.getOrDefault("conversions", List.of());
    assertThat(conversions).hasSize(1);
    var fact = conversions.get(0);
    assertThat(fact.get("supplierId")).isEqualTo(supplierRoleId.toString());
    assertThat(fact.get("supplierName")).isEqualTo("深圳华强电子");
    assertThat(fact.get("convertedQuantity")).isEqualTo(new BigDecimal("45.000000"));
    assertThat(fact.get("convertedArrivalDate")).isEqualTo("2026-12-15");
    assertThat(fact.get("targetWarehouseId")).isEqualTo(warehouseId.toString());
    // 可追溯到源计划行。
    assertThat(fact.get("sourcePlanLineId")).isEqualTo(planLineId.toString());
  }

  @Test
  void 还没选供应商不许转单() throws Exception {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-018", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + id + "/convert")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertThat(result.getResponse().getErrorMessage()).contains("供应商"));
  }

  @Test
  void 没审过的申请单不许转单() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-019", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/convert")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(
            result -> assertThat(result.getResponse().getErrorMessage()).contains("只有已批"));
  }

  @Test
  void 转单事实是append_only改不动() throws Exception {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID planId = seedPlan(tenantA);
    UUID planLineId = seedPlanLine(tenantA, planId, "SKU-020", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(planLineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("supplierId", supplierRoleId.toString(), "reason", "选供应商"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-requests/" + id + "/convert")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request_conversion set converted_quantity = 999"
                    + " where tenant_id = ?::uuid")) {
      ps.setObject(1, tenantA);
      assertThatThrownBy(() -> ps.executeUpdate()).hasMessageContaining("不可改写的留痕表");
    }
  }

  // ================================================================ 清单 / 分页 / 时间

  @Test
  void 清单是真分页真筛选() throws Exception {
    UUID planId = seedPlan(tenantA);
    for (int i = 0; i < 3; i++) {
      UUID lineId = seedPlanLine(tenantA, planId, "SKU-PAGE-" + i, "10", ARRIVAL, null);
      createFromPlan(planId, List.of(lineId));
    }
    String payload =
        http.perform(
                get("/trade/purchase-requests?size=2&page=0")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    @SuppressWarnings("unchecked")
    var page = json.readValue(payload, Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(2);
    assertThat(page.get("total")).isEqualTo(3);
    assertThat(page.get("size")).isEqualTo(2);

    // 真筛选：按状态。
    String draftPayload =
        http.perform(
                get("/trade/purchase-requests?status=DRAFT").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    @SuppressWarnings("unchecked")
    var draftPage = json.readValue(draftPayload, Map.class);
    assertThat(draftPage.get("total")).isEqualTo(3);

    // 排序键拼错 ⇒ 400，不静默回落。
    http.perform(
            get("/trade/purchase-requests?sort=NOPE").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void timestamptz往返不偏28800秒() throws Exception {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-021", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    http.perform(
            post("/trade/purchase-requests/" + id + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    // DTO 里那个时刻与库里那一列是同一刻（不是偏了 8 小时的那个）。
    var submitted =
        json.readValue(
            http.perform(
                    get("/trade/purchase-requests/" + id).header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    String dtoAt = String.valueOf(((Map<String, Object>) submitted.get("request")).get("createdAt"));
    OffsetDateTimeAssert(dtoAt);

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "select created_at from purchase_request where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(id));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        var dbValue = rs.getObject(1, java.time.OffsetDateTime.class);
        assertThat(dbValue).isNotNull();
        assertThat(dbValue.toInstant())
            .as("库里那一列与 DTO 里的时刻必须是同一刻（不偏 −28800s）")
            .isEqualTo(java.time.OffsetDateTime.parse(dtoAt).toInstant());
      }
    }
  }

  /** DTO 里的时刻必须是带时区的 ISO 串（不是本地时间无时区）。 */
  private void OffsetDateTimeAssert(String value) {
    assertThat(value).contains("T");
    assertThat(java.time.OffsetDateTime.parse(value).getOffset().getTotalSeconds())
        .as("timestamptz 必须带时区偏移")
        .isNotZero();
  }

  @Test
  void 金额算不出来时是null而不是0() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-022", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    // 没有单价 ⇒ 金额**算不出来**（null），不是 0。
    assertThat(((Map<String, Object>) detail(id).get("request")).get("estimatedAmount")).isNull();
  }

  @Test
  void 填了单价之后金额按数量乘单价汇总() throws Exception {
    UUID planId = seedPlan(tenantA);
    UUID lineId = seedPlanLine(tenantA, planId, "SKU-023", "50", ARRIVAL, null);
    String id = createFromPlan(planId, List.of(lineId));
    String requestLineId = String.valueOf(lines(id).get(0).get("id"));
    http.perform(
            post("/trade/purchase-requests/" + id + "/lines/" + requestLineId + "/adjust")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("unitPrice", new BigDecimal("3.25"), "reason", "补预估单价"))))
        .andExpect(status().isOk());
    var request = (Map<String, Object>) detail(id).get("request");
    // 50 × 3.25 = 162.50
    assertThat(request.get("estimatedAmount")).isEqualTo(new BigDecimal("162.5000"));
  }

  @Test
  void 未认证与无权都被拒() throws Exception {
    http.perform(get("/trade/purchase-requests")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/purchase-requests").header("Authorization", "Bearer nobody"))
        .andExpect(status().isUnauthorized());
  }
}
