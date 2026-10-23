package com.starcon.trade;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 11.2.3.2 的**库存状态变更执行端口**（trade → warehouse）。
 *
 * <p>蓝图 11-aftersales-and-returns.md 的输出句写的是「库存状态变更分录；可售量更新」——
 * 而库存事实（{@code stock_bucket} / {@code inventory_balance} / {@code inventory_entry}）
 * 的写入所有权在 warehouse（7 库存 / 7.4.1.1 调整单）。AGENTS.md「模块边界纪律」禁止
 * 跨服务 SQL / FK / 视图，所以本模块<b>只发执行命令、不建账</b>：处置决定与审批在 trade 侧，
 * 真正改库存的那一步必须由 warehouse 在它自己的事务里做。
 *
 * <p>⇒ 本端口是<b>写</b>端口（与 {@link DefectiveInventoryPort} 的「只发指令、默认不可用」不同）：
 * warehouse 侧已交付本模块的<b>退货处置专用写端点</b>
 * （{@code POST /warehouse/inventory/return-dispositions/execute}），
 * 所以这里是真的把命令送过去，并如实带回回执。
 *
 * <p><b>取不到就说取不到，绝不冒充「已执行」</b>：库存没动就是没动，
 * 说成「已执行」会让处置单显示已执行而库存纹丝不动 —— 账实两套口径比报错更糟。
 *
 * <p><b>租户不由此端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS —— 隔离是<b>两道</b>
 * （warehouse 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class ReturnDispositionWarehousePort {

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段（warehouse 的响应比这里声明的多）。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** 候选来源库位。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PendingBucket(
      UUID bucketId,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID locationId,
      String locationCode,
      String locationName,
      UUID lotId,
      String lotCode,
      UUID legalEntityId,
      String qualityStatusCode,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /** 候选目标库位。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TargetLocation(
      UUID locationId,
      String code,
      String name,
      String type,
      UUID warehouseId,
      String warehouseCode,
      boolean pickable,
      String status) {}

  /** 处置选项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Options(
      String targetInventoryState,
      String requiredLocationType,
      List<PendingBucket> pendingBuckets,
      List<TargetLocation> targetLocations) {}

  /** 执行命令的请求体（字段名与 warehouse 的 ReturnDispositionExecuteRequest 对齐）。 */
  public record ExecuteCommand(
      UUID dispositionId,
      String dispositionNo,
      UUID instructionId,
      UUID inspectionId,
      String targetInventoryState,
      UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      UUID sourceBucketId,
      UUID targetLocationId,
      OffsetDateTime occurredAt) {}

  /** 执行回执。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ExecutionReceipt(
      UUID executionId,
      String executionNo,
      UUID dispositionId,
      String targetInventoryState,
      UUID skuId,
      String skuCode,
      BigDecimal qty,
      UUID sourceBucketId,
      UUID targetBucketId,
      UUID inventoryTransactionId,
      BigDecimal sellableQuantityBefore,
      BigDecimal sellableQuantityAfter,
      OffsetDateTime occurredAt,
      boolean idempotentReplay,
      String note) {}

  private final RestClient client;
  private final String baseUrl;

  public ReturnDispositionWarehousePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    this.baseUrl = url == null ? "" : url.trim();
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：执行要落事务、锁 bucket 行，比列表慢一截，但仍必须有上限。
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(this.baseUrl.isEmpty() ? "http://127.0.0.1:0" : this.baseUrl)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /** 对端是否已配置。 */
  public boolean configured() {
    return !baseUrl.isEmpty();
  }

  /**
   * 取处置选项（候选来源库位 + 候选目标库位）。
   *
   * <p>用于**建单前的真实校验**：来源库位必须是待检库存行、目标库位类型必须与目标质量状态匹配。
   * 校验放在建单这一步，是为了让「批了一单却执行不了」这种事在提交前就暴露出来。
   *
   * @return 取不到时返回 {@code available=false} + 原因（**不返回空列表冒充「没有候选」**）
   */
  public OptionsResult options(UUID skuId, String targetState, String bearer) {
    if (!configured()) {
      return new OptionsResult(
          null, null, List.of(), List.of(), false, "未配置 WAREHOUSE_URL：取不到候选库位");
    }
    try {
      Options options =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path("/warehouse/inventory/return-dispositions/options");
                    if (skuId != null) {
                      uriBuilder.queryParam("skuId", skuId);
                    }
                    if (targetState != null && !targetState.isBlank()) {
                      uriBuilder.queryParam("targetState", targetState);
                    }
                    return uriBuilder.build();
                  })
              .header("Authorization", bearer == null ? "" : bearer)
              .retrieve()
              .body(Options.class);
      if (options == null) {
        return new OptionsResult(
            null, null, List.of(), List.of(), false, "仓库服务没有返回处置选项");
      }
      return new OptionsResult(
          options.targetInventoryState(),
          options.requiredLocationType(),
          options.pendingBuckets() == null ? List.of() : options.pendingBuckets(),
          options.targetLocations() == null ? List.of() : options.targetLocations(),
          true,
          "");
    } catch (RestClientResponseException e) {
      return new OptionsResult(
          null,
          null,
          List.of(),
          List.of(),
          false,
          "仓库服务拒绝了处置选项查询（HTTP " + e.getStatusCode().value() + "）");
    } catch (RestClientException e) {
      return new OptionsResult(
          null, null, List.of(), List.of(), false, "仓库服务暂时不可达：" + e.getMessage());
    }
  }

  /** 处置选项 + 可用性。 */
  public record OptionsResult(
      String targetInventoryState,
      String requiredLocationType,
      List<PendingBucket> pendingBuckets,
      List<TargetLocation> targetLocations,
      boolean available,
      String note) {}

  /**
   * 执行一次库存状态变更。
   *
   * @return {@code executed=false} 时 {@code failureReason} 如实说明失败原因
   *     （不可达 / 被拒 / 校验不过），**绝不返回「已执行」**
   */
  public ExecutionOutcome execute(ExecuteCommand command, String bearer) {
    if (!configured()) {
      return new ExecutionOutcome(
          false,
          false,
          null,
          null,
          null,
          null,
          "未配置 WAREHOUSE_URL：库存状态变更没有送出去，库存没有动");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("dispositionId", command.dispositionId());
    payload.put("dispositionNo", command.dispositionNo());
    payload.put("instructionId", command.instructionId());
    payload.put("inspectionId", command.inspectionId());
    payload.put("targetInventoryState", command.targetInventoryState());
    payload.put("skuId", command.skuId());
    payload.put("skuCode", command.skuCode());
    payload.put("skuName", command.skuName());
    payload.put("batchNo", command.batchNo());
    payload.put("qty", command.qty());
    payload.put("sourceBucketId", command.sourceBucketId());
    payload.put("targetLocationId", command.targetLocationId());
    payload.put("occurredAt", command.occurredAt());
    try {
      ExecutionReceipt receipt =
          client
              .post()
              .uri("/warehouse/inventory/return-dispositions/execute")
              .header("Authorization", bearer == null ? "" : bearer)
              .body(payload)
              .retrieve()
              .body(ExecutionReceipt.class);
      if (receipt == null || receipt.inventoryTransactionId() == null) {
        return new ExecutionOutcome(
            false,
            false,
            null,
            null,
            null,
            null,
            "仓库服务没有返回执行回执：无法确认库存到底动没动");
      }
      return new ExecutionOutcome(
          true,
          receipt.idempotentReplay(),
          receipt.inventoryTransactionId(),
          receipt.targetBucketId(),
          receipt.sellableQuantityBefore(),
          receipt.sellableQuantityAfter(),
          receipt.note() == null ? "" : receipt.note());
    } catch (RestClientResponseException e) {
      return new ExecutionOutcome(
          false,
          false,
          null,
          null,
          null,
          null,
          "仓库服务拒绝了这次执行（HTTP "
              + e.getStatusCode().value()
              + "）：库存状态变更没有发生，处置单不会被标成已执行");
    } catch (RestClientException e) {
      return new ExecutionOutcome(
          false,
          false,
          null,
          null,
          null,
          null,
          "仓库服务暂时不可达：库存状态变更没有送出去，处置单不会被标成已执行");
    }
  }

  /** 执行结果。 */
  public record ExecutionOutcome(
      boolean executed,
      boolean idempotentReplay,
      UUID inventoryTransactionId,
      UUID targetBucketId,
      BigDecimal sellableQuantityBefore,
      BigDecimal sellableQuantityAfter,
      String failureReason) {}
}
