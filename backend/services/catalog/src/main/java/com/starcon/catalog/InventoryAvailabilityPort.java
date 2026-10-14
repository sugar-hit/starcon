package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * catalog 侧读 warehouse 可用量的**跨服务调用口**（AGENTS.md「模块边界纪律」）。
 *
 * <p><b>为什么必须走 HTTP 而不是 JOIN</b>：库存事实在 warehouse 的 {@code inventory_balance} / {@code
 * inventory_transfer} 里，BOM 事实在 catalog。 跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，跨库 FK / 视图同理，
 * 而且破坏「每张事实表只有一个写入所有者」。所以本模块只通过 warehouse 的授权端点 {@code POST /warehouse/inventory/availability}
 * 取数，<b>catalog 库里不存在任何库存副本</b>。
 *
 * <p><b>租户上下文必须透传</b>：本调用把调用者的 {@code Authorization} 头原样带过去， warehouse 再向 identity
 * 确认已认证成员关系、解析出租户，并在自己事务里绑定 {@code app.current_tenant} 由 RLS 强制隔离。 <b>不传这个头等于丢掉租户上下文</b>：warehouse
 * 无从解析租户，会把请求拒掉 而不是「按空租户放行」—— 这正是我们想要的失败方向（拒绝而不是串租户）。 本模块<b>不</b>额外发明一个 {@code X-Tenant-Id}
 * 之类的头传租户： 那等于新建一条「客户端自称租户」的旁路，而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>本接口在测试里由 WireMock 替身满足</b>（见 {@code BomExplosionIntegrationTest}）， 但生产代码路径永远是真实的 HTTP 调用 ——
 * 替身逻辑不写进生产代码。
 */
public interface InventoryAvailabilityPort {

  /**
   * 批量取可用与在途。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse，租户由它从成员关系解析</b>）
   * @param skuIds 整棵展开树上出现过的全部 SKU（叶子 + 中间层，一次批量查）
   * @param warehouseId 仓库过滤；null = 全仓合计
   * @return 每个请求 SKU 一行，没有库存行的返回 0
   */
  AvailabilityResult availability(String bearer, List<UUID> skuIds, UUID warehouseId);

  /** 单个 SKU 的可用与在途。{@code skuCode} 是 warehouse 侧的引用登记快照，可能为空。 */
  record AvailabilityRow(UUID skuId, String skuCode, BigDecimal available, BigDecimal inTransit) {}

  /** 一次查询的结果。{@code requestedCount} 可能大于 {@code rows().size()}。 */
  record AvailabilityResult(
      UUID warehouseId, int requestedCount, List<AvailabilityRow> rows, String note) {}

  /** warehouse 不可达 / 返回非 2xx。由服务层翻译成对调用方有意义的一句话。 */
  class AvailabilityUnavailableException extends RuntimeException {
    public AvailabilityUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public AvailabilityUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置， 与本服务既有的 {@link AccessService}（读
   * identity）完全同构 —— 跨服务调用在本仓库只有这一种写法。
   */
  class HttpInventoryAvailabilityPort implements InventoryAvailabilityPort {
    private final RestClient client;
    private final String key;

    public HttpInventoryAvailabilityPort(String url, String key) {
      this.key = key;
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(java.time.Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(java.time.Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public AvailabilityResult availability(String bearer, List<UUID> skuIds, UUID warehouseId) {
      try {
        var response =
            client
                .post()
                .uri("/warehouse/inventory/availability")
                .header("X-Internal-Key", key)
                .header("Authorization", bearer)
                .body(new Request(skuIds, warehouseId))
                .retrieve()
                .body(Response.class);
        if (response == null || response.items() == null) {
          throw new AvailabilityUnavailableException("库存服务返回了空的可用量结果");
        }
        List<AvailabilityRow> rows =
            response.items().stream()
                .map(i -> new AvailabilityRow(i.skuId(), i.skuCode(), i.available(), i.inTransit()))
                .toList();
        return new AvailabilityResult(
            response.warehouseId(), response.requestedCount(), rows, response.note());
      } catch (RestClientResponseException e) {
        throw translate(e);
      } catch (org.springframework.web.client.RestClientException e) {
        throw new AvailabilityUnavailableException(
            "库存服务暂时不可用，无法算缺口：BOM 展开需要「当前可用」与「在途」两个数，"
                + "拿不到时不能把它们当成 0 —— 那会让每个组件都显示缺货，"
                + "进而生成一张全是虚假缺口的采购单。请稍后重试",
            e);
      }
    }

    private static AvailabilityUnavailableException translate(RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new AvailabilityUnavailableException(
            "库存服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由库存服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行");
      }
      return new AvailabilityUnavailableException("库存服务返回 " + status + "，无法算缺口。请稍后重试", e);
    }

    /** 请求体：字段名与 warehouse 的 AvailabilityQuery 逐字对应。 */
    record Request(List<UUID> skuIds, UUID warehouseId) {}

    /** 响应体：字段名与 warehouse 的 AvailabilityView 逐字对应。 */
    record Response(UUID warehouseId, int requestedCount, List<ResponseItem> items, String note) {}

    record ResponseItem(UUID skuId, String skuCode, BigDecimal available, BigDecimal inTransit) {}
  }
}
