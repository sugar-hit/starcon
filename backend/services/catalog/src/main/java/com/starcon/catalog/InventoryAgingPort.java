package com.starcon.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * catalog 侧读 warehouse 库龄的**跨服务调用口**（AGENTS.md「模块边界纪律」）。
 *
 * <p><b>为什么必须走 HTTP 而不是 JOIN</b>：库龄事实在 warehouse 的库存桶（{@code stock_bucket}
 * 与批次）里，catalog 库里没有它的副本。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 视图同理，而且破坏「每张事实表只有一个写入所有者」。
 * 所以本模块只通过 warehouse 的授权端点 {@code GET /warehouse/inventory/aging/slow-moving}
 * 取数，<b>catalog 库里不存在任何库龄副本</b>。
 *
 * <p><b>租户上下文必须透传</b>：本调用把调用者的 {@code Authorization} 头原样带过去，
 * warehouse 再向 identity 确认已认证成员关系、解析出租户，并在自己事务里绑定
 * {@code app.current_tenant} 由 RLS 强制隔离。本模块<b>不</b>额外发明一个
 * {@code X-Tenant-Id} 之类的头传租户 —— 那等于新建一条「客户端自称租户」的旁路。
 *
 * <p><b>为什么要显式带上 NORMAL 档</b>：warehouse 的呆滞清单默认只返回
 * {@code slow_moving_status <> 'NORMAL'} 的行（可执行的那部分），而本模块要判的是
 * 「库龄有没有超过<b>本规则自己的</b>阈值」—— 阈值可能比 warehouse 的滞销线更短。
 * 所以这里显式请求三档全量，再由本模块按自己的阈值判定。
 */
public interface InventoryAgingPort {

  /**
   * 批量取库龄。
   *
   * <p>warehouse 的呆滞清单**没有**批量 SKU 过滤端点（只有 {@code skuId} 单个过滤），
   * 所以本实现按 SKU 逐个调用；调用次数由服务层的评估规模上限控制。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse，租户由它从成员关系解析</b>）
   * @param skuIds 要查的 SKU（同一个 tenant）
   * @return 每个 SKU 一行（同一个 SKU 可能有多行：按批次 / 库位拆开）
   */
  List<AgingRow> aging(String bearer, List<UUID> skuIds);

  /** 一行库龄。{@code agingDays} 为空 = 那一批没有可判定的库龄（批次未登记到货日）。 */
  record AgingRow(
      UUID skuId,
      String skuCode,
      Integer agingDays,
      BigDecimal available,
      String slowMovingStatus) {}

  /** warehouse 不可达 / 返回非 2xx。由服务层翻译成对调用方有意义的一句话。 */
  class AgingUnavailableException extends RuntimeException {
    public AgingUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public AgingUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 真实的 HTTP 实现。用 {@link RestClient} 与 {@code @Value} 读配置，与本服务既有的
   * {@link AccessService}（读 identity）完全同构 —— 跨服务调用在本仓库只有这一种写法。
   */
  class HttpInventoryAgingPort implements InventoryAgingPort {
    private final RestClient client;

    public HttpInventoryAgingPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(java.time.Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(java.time.Duration.ofSeconds(8));
      // warehouse 的响应里字段比本模块用到的多：忽略未知字段，别让上游加字段就把本服务打挂。
      var mapper =
          new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
      this.client =
          RestClient.builder()
              .baseUrl(url)
              .requestFactory(factory)
              .messageConverters(List.of(new MappingJackson2HttpMessageConverter(mapper)))
              .build();
    }

    @Override
    public List<AgingRow> aging(String bearer, List<UUID> skuIds) {
      var rows = new ArrayList<AgingRow>();
      for (var skuId : skuIds) {
        rows.addAll(one(bearer, skuId));
      }
      return rows;
    }

    private List<AgingRow> one(String bearer, UUID skuId) {
      try {
        var response =
            client
                .get()
                .uri(
                    builder ->
                        builder
                            .path("/warehouse/inventory/aging/slow-moving")
                            .queryParam("skuId", skuId)
                            .queryParam("slowMovingStatus", "NORMAL", "SLOW_MOVING", "OBSOLETE")
                            .queryParam("page", 0)
                            .queryParam("size", 200)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(Response.class);
        if (response == null || response.rows() == null) {
          throw new AgingUnavailableException("库存服务返回了空的库龄结果（SKU " + skuId + "）");
        }
        List<AgingRow> out = new ArrayList<>();
        for (var row : response.rows()) {
          if (row.skuId() == null) continue;
          out.add(
              new AgingRow(
                  row.skuId(),
                  row.skuCode(),
                  row.agingDays(),
                  row.available(),
                  row.slowMovingStatus()));
        }
        return out;
      } catch (RestClientResponseException e) {
        throw translate(e);
      } catch (org.springframework.web.client.RestClientException e) {
        throw new AgingUnavailableException(
            "库存服务暂时不可用，无法判定库龄：库龄超期规则需要「这个 SKU 最老的一批有多老」"
                + "这一个数，拿不到时不能当成 0 天（那会让每个 SKU 都判定为没超期）。请稍后重试",
            e);
      }
    }

    private static AgingUnavailableException translate(RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new AgingUnavailableException(
            "库存服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，"
                + "租户由库存服务从已认证成员关系解析。不传这个头等于丢掉租户上下文 —— "
                + "这里选择明确拒绝而不是放行");
      }
      return new AgingUnavailableException("库存服务返回 " + status + "，无法判定库龄。请稍后重试", e);
    }

    /** 响应体：字段名与 warehouse 的 SlowMovingPage 逐字对应（其余字段忽略）。 */
    record Response(List<ResponseRow> rows, long total, int page, int size) {}

    record ResponseRow(
        UUID skuId,
        String skuCode,
        Integer agingDays,
        BigDecimal available,
        String slowMovingStatus) {}
  }

  /**
   * 没配 warehouse 地址时的实现。
   *
   * <p>🔴 显式抛错，而不是返回空集合：空集合会被读成「这个 SKU 没有库存记录 ⇒ 库龄 0 天 ⇒
   * 没超期」—— 那是一条凭空来的判定。
   */
  class UnwiredInventoryAgingPort implements InventoryAgingPort {
    @Override
    public List<AgingRow> aging(String bearer, List<UUID> skuIds) {
      throw new AgingUnavailableException(
          "库存服务地址未配置（WAREHOUSE_URL 为空）：库龄超期规则需要 warehouse 的呆滞清单，"
              + "本库里没有库龄副本，读不到就不能判定。请配置 WAREHOUSE_URL 后重试");
    }
  }
}
