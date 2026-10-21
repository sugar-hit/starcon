package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 6.2.2.2 需求合并与批量的集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + Testcontainers + 强制 RLS</b>，跨服务走**真的本地 HTTP 替身**
 * （identity 权限 / warehouse 仓与法人 / catalog 供应商）。
 *
 * <p>本测试类重点钉住蓝图 6.2.2.2 的两条硬规则：
 *
 * <ol>
 *   <li><b>合并只在同一供应商内进行</b> —— 跨供应商不合并，且**必须给出明确原因**；</li>
 *   <li><b>批量改供应商会破坏分组不变式</b> —— 改完必须重新校验分组，且批量操作
 *       <b>整体生效或整体拒绝</b>，不留「改了一半」的中间态。</li>
 * </ol>
 */
class PurchaseDemandMergeIntegrationTest extends PurchaseDemandMergeFixture {

  @BeforeEach
  void reset() throws Exception {
    truncateAll();
  }

  // ================================================================ 调用辅助

  /** 发起一次合并，返回响应体（Map）。 */
  Map<String, Object> merge(String bearer, Map<String, Object> body) throws Exception {
    MvcResult result =
        http.perform(
                post("/trade/demand-merges")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn();
    return readJson(result.getResponse().getContentAsString());
  }

  /** 发起一次合并并断言 200。 */
  Map<String, Object> mergeOk(String bearer, Map<String, Object> body) throws Exception {
    MvcResult result =
        http.perform(
                post("/trade/demand-merges")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn();
    String payload = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus()).as("合并失败：%s", payload).isEqualTo(200);
    return readJson(payload);
  }

  /** 发起一次合并并断言失败（返回状态码与响应体）。 */
  MvcResult mergeFail(String bearer, Map<String, Object> body) throws Exception {
    return http.perform(
            post("/trade/demand-merges")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andReturn();
  }

  /** 合并入参。 */
  Map<String, Object> mergeBody(List<UUID> requestIds) {
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceRequestIds", requestIds.stream().map(UUID::toString).toList());
    body.put("targetWarehouseId", warehouseId.toString());
    body.put("legalEntityId", legalEntityId.toString());
    body.put("currencyCode", "USD");
    return body;
  }

  /** 造一张已批的申请单 + 一行需求（带供应商与单价）。 */
  UUID approvedRequestWithLine(
      UUID tenant, String skuCode, String quantity, String price, UUID supplier)
      throws Exception {
    return seedRequestWithLines(
        tenant,
        "APPROVED",
        "USD",
        warehouseId,
        legalEntityId,
        List.of(
            new LineSpec(
                skuCode,
                quantity,
                price,
                supplier,
                supplier == null ? null : "SUP-" + supplier.toString().substring(0, 4),
                supplier == null ? null : "供应商" + supplier.toString().substring(0, 4),
                ARRIVAL)));
  }

  /** 取未合并行的原因码集合。 */
  List<String> unmergedCodes(Map<String, Object> detail) {
    List<String> codes = new ArrayList<>();
    for (Map<String, Object> row : listOf(detail, "unmerged")) {
      codes.add(String.valueOf(row.get("reasonCode")));
    }
    return codes;
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实报出采购单下游未交付与合并硬规则() throws Exception {
    String payload =
        http.perform(get("/trade/demand-merges/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var caps = readJson(payload);
    assertThat(caps.get("purchaseOrderDelivered")).isEqualTo(false);
    assertThat(String.valueOf(caps.get("purchaseOrderNote"))).contains("6.3.1.2").contains("未交付");
    assertThat(String.valueOf(caps.get("mergeRule"))).contains("同一供应商");
    assertThat(caps.get("requestAvailable")).isEqualTo(true);
  }

  @Test
  void 参照下拉真的来自catalog与warehouse() throws Exception {
    String payload =
        http.perform(get("/trade/demand-merges/options").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var options = readJson(payload);
    assertThat(listOf(options, "suppliers")).hasSize(3);
    assertThat(listOf(options, "warehouses")).hasSize(1);
    assertThat(listOf(options, "legalEntities")).hasSize(1);
  }

  // ================================================================ 合并

  @Test
  void 同一供应商的多行需求合并成一张采购单草稿() throws Exception {
    UUID request = seedRequestWithLines(tenantA, "APPROVED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-1", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL),
            new LineSpec("SKU-2", "4", "3.25", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    var drafts = listOf(detail, "drafts");
    assertThat(drafts).as("同一供应商只应产出一张草稿").hasSize(1);
    assertThat(drafts.get(0).get("supplierId").toString()).isEqualTo(supplierA.toString());
    assertThat(drafts.get(0).get("status")).isEqualTo("DRAFT");
    assertThat(drafts.get(0).get("lineCount")).isEqualTo(2);
    assertThat(drafts.get(0).get("statusLabel")).isEqualTo("草稿");

    var lines = listOf(detail, "lines");
    assertThat(lines).hasSize(2);
    assertThat(decimal(lines.get(0).get("mergedQuantity"))).isEqualByComparingTo("10.000000");
    // 行金额 = 单价 × 数量（10 × 12.5 = 125）。
    assertThat(decimal(lines.get(0).get("lineAmount"))).isEqualByComparingTo("125.0000");
    // 草稿金额 = **各行金额之和**（125 + 4 × 3.25 = 138）—— 不是第一行的金额。
    assertThat(decimal(drafts.get(0).get("estimatedAmount"))).isEqualByComparingTo("138.0000");
    assertThat(decimal(drafts.get(0).get("totalQuantity"))).isEqualByComparingTo("14.000000");
    assertThat(listOf(detail, "unmerged")).isEmpty();
  }

  @Test
  void 跨供应商不合并_各自成一张草稿() throws Exception {
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-2", "7", "3.0", supplierB);

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));

    var drafts = listOf(detail, "drafts");
    assertThat(drafts).as("跨供应商必须各自一张草稿，绝不能合到一张").hasSize(2);
    var suppliers = drafts.stream().map(d -> d.get("supplierId").toString()).sorted().toList();
    assertThat(suppliers)
        .containsExactlyInAnyOrder(supplierA.toString(), supplierB.toString());
    // 每张草稿里只有自己供应商的行。
    for (Map<String, Object> draft : drafts) {
      for (Map<String, Object> line : listOf(detail, "lines")) {
        if (line.get("draftId").equals(draft.get("id"))) {
          assertThat(line.get("supplierId")).isEqualTo(draft.get("supplierId"));
        }
      }
    }
    assertThat(listOf(detail, "unmerged")).isEmpty();
  }

  @Test
  void 同一个SKU出现在多个供应商下时各自成行() throws Exception {
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-SAME", "10", "12.5", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-SAME", "7", "3.0", supplierB);

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));

    assertThat(listOf(detail, "drafts")).hasSize(2);
    var lines = listOf(detail, "lines");
    assertThat(lines).as("同一 SKU 在两个供应商下必须是两行").hasSize(2);
    var lineSuppliers = lines.stream().map(l -> l.get("supplierId").toString()).distinct().count();
    assertThat(lineSuppliers).isEqualTo(2);
  }

  @Test
  void 只合并到指定供应商时_其它供应商的行进未合并行并写明原因() throws Exception {
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-2", "7", "3.0", supplierB);

    var body = mergeBody(List.of(r1, r2));
    body.put("targetSupplierId", supplierA.toString());
    Map<String, Object> detail = mergeOk("Bearer tenant-a", body);

    assertThat(listOf(detail, "drafts")).hasSize(1);
    var unmerged = listOf(detail, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("SUPPLIER_MISMATCH");
    assertThat(String.valueOf(unmerged.get(0).get("reason")))
        .contains("跨供应商")
        .contains("对账与付款错误");
    assertThat(unmerged.get(0).get("sourceSkuCode")).isEqualTo("SKU-2");
  }

  @Test
  void 还没选供应商的行进未合并行_原因说清没选供应商() throws Exception {
    UUID request = seedRequestWithLines(tenantA, "APPROVED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-NO-SUP", "10", "12.5", null, null, null, ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    assertThat(listOf(detail, "drafts")).isEmpty();
    var unmerged = listOf(detail, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("SUPPLIER_MISSING");
    assertThat(String.valueOf(unmerged.get(0).get("reason"))).contains("还没选供应商");
  }

  @Test
  void 没审过的申请单不许合并_进未合并行() throws Exception {
    UUID request = seedRequestWithLines(tenantA, "DRAFT", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-1", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    assertThat(listOf(detail, "drafts")).isEmpty();
    var unmerged = listOf(detail, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("REQUEST_NOT_APPROVED");
    assertThat(String.valueOf(unmerged.get(0).get("reason"))).contains("还没审");
  }

  @Test
  void 已被驳回与已转单的申请单都不许合并_各有各的原因() throws Exception {
    UUID rejected = seedRequestWithLines(tenantA, "REJECTED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-R", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));
    UUID converted = seedRequestWithLines(tenantA, "CONVERTED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-C", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(rejected, converted)));

    var codes = unmergedCodes(detail);
    assertThat(codes).containsExactlyInAnyOrder("REQUEST_REJECTED", "REQUEST_ALREADY_CONVERTED");
    assertThat(listOf(detail, "drafts")).isEmpty();
  }

  @Test
  void 已经被合并过的行再合一次进未合并行() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    Map<String, Object> second = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    assertThat(listOf(second, "drafts")).isEmpty();
    var unmerged = listOf(second, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("ALREADY_MERGED");
  }

  @Test
  void 同一SKU在同一供应商下单价冲突时不合成一行() throws Exception {
    UUID r1 = seedRequestWithLines(tenantA, "APPROVED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-X", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));
    UUID r2 = seedRequestWithLines(tenantA, "APPROVED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-X", "5", "20.0", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));

    var lines = listOf(detail, "lines");
    assertThat(lines).as("单价冲突的两行不能合成一行").hasSize(1);
    var unmerged = listOf(detail, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("PRICE_CONFLICT");
  }

  @Test
  void 同一SKU在同一供应商下单价相同则数量相加且来源可追溯() throws Exception {
    UUID r1 =
        seedRequestWithLines(
            tenantA,
            "APPROVED",
            "USD",
            warehouseId,
            legalEntityId,
            List.of(new LineSpec("SKU-Y", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)));
    UUID r2 =
        seedRequestWithLines(
            tenantA,
            "APPROVED",
            "USD",
            warehouseId,
            legalEntityId,
            List.of(new LineSpec("SKU-Y", "5", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));

    var lines = listOf(detail, "lines");
    assertThat(lines).hasSize(1);
    assertThat(decimal(lines.get(0).get("mergedQuantity"))).isEqualByComparingTo("15.000000");
    assertThat(lines.get(0).get("sourceRequestCount")).isEqualTo(2);
    // 来源行两条 ⇒ 事后能追溯这 15 是由哪两条需求合成的。
    var sources = listOf(detail, "sources");
    assertThat(sources).hasSize(2);
    var sourceRequestIds = sources.stream().map(s -> s.get("sourceRequestId").toString()).toList();
    assertThat(sourceRequestIds).containsExactlyInAnyOrder(r1.toString(), r2.toString());
    assertThat(sources.stream().map(s -> s.get("sourceRequestLineId").toString()).distinct().count())
        .isEqualTo(2);
  }

  @Test
  void 目标仓不一致的行进未合并行() throws Exception {
    UUID otherWarehouse = UUID.randomUUID();
    UUID request = seedRequestWithLines(tenantA, "APPROVED", "USD", otherWarehouse, legalEntityId,
        List.of(
            new LineSpec("SKU-1", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));

    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    var unmerged = listOf(detail, "unmerged");
    assertThat(unmerged).hasSize(1);
    assertThat(unmerged.get(0).get("reasonCode")).isEqualTo("TARGET_MISMATCH");
  }

  // ================================================================ 批量改数量

  @Test
  void 批量改数量按设置值生效并留痕() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String lineId = listOf(detail, "lines").get(0).get("id").toString();

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("mode", "SET");
    input.put("value", 25);
    input.put("reason", "供应商涨价，按新量下单");
    String payload =
        http.perform(
                post("/trade/demand-merges/" + batchId(detail) + "/quantity")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> updated = readJson(payload);

    var lines = listOf(updated, "lines");
    assertThat(decimal(lines.get(0).get("mergedQuantity"))).isEqualByComparingTo("25.000000");
    // 金额跟着走：25 × 12.5 = 312.5
    assertThat(decimal(listOf(updated, "drafts").get(0).get("estimatedAmount")))
        .isEqualByComparingTo("312.5000");
    // 源需求量与合并后数量分叉 —— 这就是「改过」的证据。
    assertThat(decimal(lines.get(0).get("sourceQuantity"))).isEqualByComparingTo("10.000000");

    var adjustments = listOf(updated, "adjustments");
    assertThat(adjustments).hasSize(1);
    assertThat(adjustments.get(0).get("field")).isEqualTo("merged_quantity");
    assertThat(adjustments.get(0).get("beforeValue")).isEqualTo("10");
    assertThat(adjustments.get(0).get("afterValue")).isEqualTo("25");
    assertThat(adjustments.get(0).get("reason")).isEqualTo("供应商涨价，按新量下单");
  }

  @Test
  void 批量改数量按乘数生效() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String lineId = listOf(detail, "lines").get(0).get("id").toString();

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("mode", "MULTIPLY");
    input.put("value", 2);
    input.put("reason", "按 2 倍备货");
    String payload =
        http.perform(
                post("/trade/demand-merges/" + batchId(detail) + "/quantity")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(decimal(listOf(readJson(payload), "lines").get(0).get("mergedQuantity")))
        .isEqualByComparingTo("20.000000");
  }

  @Test
  void 批量改数量遇到非法输入整体拒绝_库里不留改了一半的中间态() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String lineId = listOf(detail, "lines").get(0).get("id").toString();
    String batchId = batchId(detail);

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId, UUID.randomUUID().toString()));
    input.put("mode", "SET");
    input.put("value", 25);
    input.put("reason", "含一个不存在的行");
    http.perform(
            post("/trade/demand-merges/" + batchId + "/quantity")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isNotFound());

    // 🔴 整批回滚：那一行**没有**被改成 25。
    Map<String, Object> after = fetchBatch("Bearer tenant-a", batchId);
    assertThat(decimal(listOf(after, "lines").get(0).get("mergedQuantity")))
        .isEqualByComparingTo("10.000000");
    assertThat(listOf(after, "adjustments")).isEmpty();
  }

  // ================================================================ 批量改供应商

  @Test
  void 批量改供应商把行搬到新供应商的草稿并重新校验分组() throws Exception {
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-2", "4", "3.0", supplierB);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));
    String batchId = batchId(detail);
    String lineId =
        listOf(detail, "lines").stream()
            .filter(l -> supplierA.toString().equals(l.get("supplierId").toString()))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("supplierId", supplierB.toString());
    input.put("reason", "改成同一家供货");
    String payload =
        http.perform(
                post("/trade/demand-merges/" + batchId + "/supplier")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> updated = readJson(payload);

    // 供应商甲的草稿被搬空 ⇒ 草稿消失；供应商乙的草稿里现在有两条行。
    var drafts = listOf(updated, "drafts");
    assertThat(drafts).hasSize(1);
    assertThat(drafts.get(0).get("supplierId").toString()).isEqualTo(supplierB.toString());
    assertThat(drafts.get(0).get("lineCount")).isEqualTo(2);

    // 🔴 重新校验分组：每张草稿的供应商必须唯一。
    for (Map<String, Object> draft : drafts) {
      var suppliers =
          listOf(updated, "lines").stream()
              .filter(l -> l.get("draftId").equals(draft.get("id")))
              .map(l -> l.get("supplierId").toString())
              .distinct()
              .toList();
      assertThat(suppliers).as("一张草稿里不能出现两个供应商").hasSize(1);
      assertThat(suppliers.get(0)).isEqualTo(draft.get("supplierId").toString());
    }

    var adjustments = listOf(updated, "adjustments");
    assertThat(adjustments).hasSize(1);
    assertThat(adjustments.get(0).get("scope")).isEqualTo("SUPPLIER");
    assertThat(adjustments.get(0).get("field")).isEqualTo("supplier_id");
  }

  @Test
  void 批量改供应商遇到同SKU单价冲突时整批拒绝_库里没有改了一半() throws Exception {
    // 供应商乙的草稿里已有 SKU-X（单价 12.5），要把供应商甲的 SKU-X（单价 20）搬过去。
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-X", "10", "20.0", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-X", "5", "12.5", supplierB);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));
    String batchId = batchId(detail);
    String lineId =
        listOf(detail, "lines").stream()
            .filter(l -> supplierA.toString().equals(l.get("supplierId").toString()))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("supplierId", supplierB.toString());
    input.put("reason", "搬到乙供应商");
    http.perform(
            post("/trade/demand-merges/" + batchId + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isConflict());

    // 🔴 整批回滚：那一行仍在供应商甲的草稿里，数量也没变。
    Map<String, Object> after = fetchBatch("Bearer tenant-a", batchId);
    var moved =
        listOf(after, "lines").stream()
            .filter(l -> l.get("id").equals(lineId))
            .findFirst()
            .orElseThrow();
    assertThat(moved.get("supplierId").toString()).isEqualTo(supplierA.toString());
    assertThat(decimal(moved.get("mergedQuantity"))).isEqualByComparingTo("10.000000");
    assertThat(listOf(after, "adjustments")).isEmpty();
  }

  @Test
  void 批量改供应商到不存在的供应商会被拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String lineId = listOf(detail, "lines").get(0).get("id").toString();

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("supplierId", UUID.randomUUID().toString());
    input.put("reason", "不存在的供应商");
    http.perform(
            post("/trade/demand-merges/" + batchId(detail) + "/supplier")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 定稿

  @Test
  void 定稿后内容冻结_不能再批量修改() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String batchId = batchId(detail);
    String lineId = listOf(detail, "lines").get(0).get("id").toString();

    String payload =
        http.perform(
                post("/trade/demand-merges/" + batchId + "/finalize")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(readJson(payload).get("status")).isEqualTo("FINALIZED");
    assertThat(readJson(payload).get("statusLabel")).isEqualTo("已定稿");

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("lineIds", List.of(lineId));
    input.put("mode", "SET");
    input.put("value", 25);
    input.put("reason", "定稿后还想改");
    http.perform(
            post("/trade/demand-merges/" + batchId + "/quantity")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isConflict());
  }

  @Test
  void 有未合并行时定稿被拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID other = seedRequestWithLines(tenantA, "DRAFT", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-2", "3", "1.0", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request, other)));

    http.perform(
            post("/trade/demand-merges/" + batchId(detail) + "/finalize")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }

  // ================================================================ 打印

  @Test
  void 批量打印每张草稿一张纸并渲染中文文本() throws Exception {
    UUID r1 = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID r2 = approvedRequestWithLine(tenantA, "SKU-2", "7", "3.0", supplierB);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(r1, r2)));
    var draftIds =
        listOf(detail, "drafts").stream().map(d -> d.get("id").toString()).toList();

    String payload =
        http.perform(
                post("/trade/demand-merges/print")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("draftIds", draftIds))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> result = readJson(payload);
    assertThat(result.get("draftCount")).isEqualTo(2);
    assertThat(result.get("lineCount")).isEqualTo(2);
    var sheets = listOf(result, "sheets");
    assertThat(sheets).hasSize(2);
    for (Map<String, Object> sheet : sheets) {
      assertThat(String.valueOf(sheet.get("textContent")))
          .contains("采购单草稿")
          .contains("供应商：")
          .contains("SKU-");
      assertThat(listOf(sheet, "lines")).hasSize(1);
    }
    assertThat(String.valueOf(result.get("note"))).contains("草稿");
  }

  // ================================================================ 清单与分页

  @Test
  void 批次清单真分页真筛选_排序键拼错被拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    String payload =
        http.perform(
                get("/trade/demand-merges?page=0&size=10")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> page = readJson(payload);
    assertThat(page.get("total")).isEqualTo(1);
    assertThat(listOf(page, "items")).hasSize(1);

    http.perform(
            get("/trade/demand-merges?sort=NOPE").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 未合并行清单可按原因码筛选() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    UUID other = seedRequestWithLines(tenantA, "DRAFT", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-2", "3", "1.0", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request, other)));
    String batchId = batchId(detail);

    String payload =
        http.perform(
                get("/trade/demand-merges/" + batchId + "/unmerged?reasonCode=REQUEST_NOT_APPROVED")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> page = readJson(payload);
    assertThat(page.get("total")).isEqualTo(1);
    assertThat(listOf(page, "items").get(0).get("reasonCode")).isEqualTo("REQUEST_NOT_APPROVED");
  }

  // ================================================================ DB 层不变量

  @Test
  void 绕服务层写sql把行的供应商改成别的供应商会被db拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String lineId = listOf(detail, "lines").get(0).get("id").toString();

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_demand_merge_line set supplier_id = ?::uuid"
                    + " where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, supplierB);
      ps.setObject(2, tenantA);
      ps.setObject(3, UUID.fromString(lineId));
      var error = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, ps::executeUpdate);
      assertThat(error.getMessage()).contains("合并只能在同一供应商内进行");
    }
  }

  @Test
  void 绕服务层写sql改草稿的供应商会被db拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String draftId = listOf(detail, "drafts").get(0).get("id").toString();

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_demand_merge_draft set supplier_id = ?::uuid"
                    + " where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, supplierB);
      ps.setObject(2, tenantA);
      ps.setObject(3, UUID.fromString(draftId));
      var error = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, ps::executeUpdate);
      assertThat(error.getMessage()).contains("不可改写");
    }
  }

  @Test
  void 三张留痕表append_only_改与删都被db拒() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String sourceId = listOf(detail, "sources").get(0).get("id").toString();

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_demand_merge_line_source set source_quantity = 1"
                    + " where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(sourceId));
      var error = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, ps::executeUpdate);
      assertThat(error.getMessage()).contains("不可改写");
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "delete from purchase_demand_merge_line_source where tenant_id = ?::uuid and id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(sourceId));
      var error = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, ps::executeUpdate);
      assertThat(error.getMessage()).contains("不可改写");
    }
  }

  // ================================================================ 单位与时间

  @Test
  void 金额算不出来是null而不是0() throws Exception {
    UUID request = seedRequestWithLines(tenantA, "APPROVED", "USD", warehouseId, legalEntityId,
        List.of(
            new LineSpec("SKU-NO-PRICE", "10", null, supplierA, "SUP-001", "深圳华强电子", ARRIVAL)
        ));
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));

    assertThat(listOf(detail, "drafts").get(0).get("estimatedAmount")).isNull();
    assertThat(batchOf(detail).get("estimatedAmount")).isNull();
    assertThat(listOf(detail, "lines").get(0).get("lineAmount")).isNull();
  }

  @Test
  void timestamptz往返逐字相等_不偏八小时() throws Exception {
    UUID request = approvedRequestWithLine(tenantA, "SKU-1", "10", "12.5", supplierA);
    Map<String, Object> detail = mergeOk("Bearer tenant-a", mergeBody(List.of(request)));
    String batchId = batchId(detail);

    // 🔴 直接读 DB 原值（**超级用户**只读，不改）。
    //    这里**不能**用注入的 `sql`：它跑在 RLS 之下，而事务外没有 `app.current_tenant`，
    //    策略会把所有行过滤掉 —— 读到 null 的是「没设租户上下文」，不是「没落库」。
    //    时刻由 PostgreSQL 自己渲染成 UTC ISO 串，避免两侧时区表示不同带来的假差异。
    String dbBatchCreated;
    String dbDraftCreated;
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "select to_char(b.created_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),"
                    + " to_char(d.created_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
                    + " from purchase_demand_merge_batch b"
                    + " join purchase_demand_merge_draft d on d.tenant_id = b.tenant_id and d.batch_id = b.id"
                    + " where b.tenant_id = ?::uuid and b.id = ?::uuid")) {
      ps.setObject(1, tenantA);
      ps.setObject(2, UUID.fromString(batchId));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("批次与草稿必须真的落库了").isTrue();
        dbBatchCreated = rs.getString(1);
        dbDraftCreated = rs.getString(2);
      }
    }
    assertThat(dbBatchCreated).as("批次时刻必须真的落库").isNotBlank();
    assertThat(dbDraftCreated).as("草稿时刻必须真的落库").isNotBlank();

    OffsetDateTime batchCreated = OffsetDateTime.parse(dbBatchCreated);
    OffsetDateTime draftCreated = OffsetDateTime.parse(dbDraftCreated);
    // 批次与草稿在同一次合并里写入 ⇒ 时刻必须一致。
    assertThat(batchCreated.toInstant()).isEqualTo(draftCreated.toInstant());

    // API 读回来的是同一个时刻（用 Timestamp.valueOf(LocalDateTime...) 会静默偏 −28800s）。
    Map<String, Object> after = fetchBatch("Bearer tenant-a", batchId);
    OffsetDateTime apiCreated =
        OffsetDateTime.parse(String.valueOf(batchOf(after).get("createdAt")));
    assertThat(apiCreated.toInstant())
        .as("API 读回的批次时刻必须与库里存的值逐刻相等（不偏 −28800s）")
        .isEqualTo(batchCreated.toInstant());
  }

  // ================================================================ 认证

  @Test
  void 未认证与无权都被拒() throws Exception {
    http.perform(get("/trade/demand-merges")).andExpect(status().isUnauthorized());
    // 走真实入参（校验先于鉴权时入参也得合法，否则 400 会盖住 401）。
    UUID request =
        seedRequestWithLines(
            tenantA,
            "APPROVED",
            "USD",
            warehouseId,
            legalEntityId,
            List.of(new LineSpec("SKU-1", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)));
    http.perform(
            post("/trade/demand-merges")
                .header("Authorization", "Bearer nobody")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mergeBody(List.of(request)))))
        .andExpect(status().isUnauthorized());
  }

  // ================================================================ 工具

  String batchId(Map<String, Object> detail) {
    return batchOf(detail).get("id").toString();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> batchOf(Map<String, Object> detail) {
    return (Map<String, Object>) detail.get("batch");
  }

  Map<String, Object> fetchBatch(String bearer, String batchId) throws Exception {
    String payload =
        http.perform(get("/trade/demand-merges/" + batchId).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return readJson(payload);
  }
}
