package com.starcon.trade;

import java.time.Duration;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.1.2.2 商品问题归因的**跨服务读端口** —— 7.3.1.1 批次档案在本模块的<b>唯一</b>取数入口。
 *
 * <p>蓝图 11-aftersales-and-returns.md 输入句写「<b>批次信息</b>」，
 * 而批次事实的写入所有者是 <b>warehouse</b>（7.3.1.1，迁移 V57 的 {@code inventory_lot}，
 * 在 starcon_warehouse 独立库）。AGENTS.md 模块边界纪律禁止跨服务 SQL / 外键 / 共享视图 /
 * 直连对方库，所以本端口<b>只经 warehouse 已交付的授权 HTTP 端点</b>读，
 * 不建跨库视图、不建跨库外键、不直连对方库。
 *
 * <p>用 7.3.1.1 已交付的形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory/lots/{lotId}} —— <b>批次档案详情</b>（按 id 取）；</li>
 *   <li>{@code GET /warehouse/inventory/lots?skuId=&amp;keyword=&amp;page=&amp;size=} —— <b>批次下拉</b>（列表选批）。</li>
 * </ul>
 *
 * <p><b>租户绝不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。隔离是<b>两道</b>
 * （trade 侧 RLS + warehouse 侧 RLS），而不是「本服务替对方决定租户」。
 * <b>绝不传递或信任 {@code X-Tenant-Id} 头</b> —— 那个头在本端口一次都不出现。
 *
 * <p><b>不可达一律 503，并把「为什么」如实报出</b>：批次追溯是本模块的核心输入
 * （蓝图加粗句「同一个批次退回来 10 件」的计数前提），读不到批次就<b>不能建归因单</b> ——
 * 降级成「先归因、批次稍后补」会造出一批永远补不上的批次标记，
 * 而批次标记正是下游 6.4.2.2 索赔要引用的证据。
 */
@Service
public class ProductLotTracePort {

  /**
   * 7.3.1.1 的批次档案行（字段名逐字对齐 {@code LotArchiveRow}）。
   *
   * <p>只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   * 本模块要的是「生产日期 + 供应商引用 + 批次号」三项 ——
   * 归因到「生产」时供应商引用是索赔单的定位键，生产日期是判断这批货是否超过工艺窗口的输入。
   *
   * <p>⚠️ 刻意声明为 {@code public}：Jackson 靠反射取 record 的规范构造器，
   * 包级可见的 record 在这里会解析失败，报成
   * 「no String-argument constructor/factory method」——
   * 症状离病因很远（看着像 JSON 形状不对，实际是可见性）。本仓其它 Port 同一写法。
   */
  public record LotArchiveView(
      UUID lotId,
      UUID skuId,
      String skuCode,
      String skuName,
      String lotCode,
      LocalDate producedOn,
      LocalDate expiresOn,
      String origin,
      UUID supplierRefId,
      String supplierName,
      String status,
      BigDecimal onHand,
      BigDecimal available,
      long bucketCount,
      String notes) {}

  /** 批次列表页（7.3.1.1 的 {@code LotArchivePage}）。同样必须 public，见 {@link LotArchiveView}。 */
  public record LotPage(List<LotArchiveView> rows, long total, int page, int size) {}

  /** 归因单要落的批次快照 —— 与 V172/V179 的「交接凭据自带快照」同一条纪律。 */
  public record LotSnapshot(
      UUID lotId, String lotCode, LocalDate producedOn, UUID supplierRefId, String supplierName) {}

  private final RestClient client;

  public ProductLotTracePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读档案要走对方一次完整的 RLS 查询，仍必须有上限 —— 仓库一慢就会把本服务的请求线程占住。
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 按 id 取批次档案快照。
   *
   * <p>⚠️ <b>跨租户在 warehouse 侧 RLS 下查不到 → 404</b>：本端口<b>原样如实报出</b>，
   * 不降级成「批次不存在但仍可归因」—— 那会让运营把货归到一个根本不存在的批次上。
   */
  public LotSnapshot snapshot(UUID lotId, String bearer) {
    try {
      LotArchiveView lot =
          client
              .get()
              .uri("/warehouse/inventory/lots/" + lotId)
              .header("Authorization", bearer)
              .retrieve()
              .body(LotArchiveView.class);
      if (lot == null || lot.lotId() == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "仓库侧没有返回批次档案：" + lotId + "，请确认该批次存在");
      }
      return new LotSnapshot(
          lot.lotId(),
          lot.lotCode(),
          lot.producedOn(),
          lot.supplierRefId(),
          lot.supplierName());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库批次档案，批次追溯暂时做不了");
      }
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "批次不存在或不属于当前租户：" + lotId);
      }
      throw unavailable("仓库批次档案查询返回 " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达", e);
    }
  }

  /**
   * 批次下拉：按 SKU / 关键字取候选批次（列表选批）。
   *
   * <p>刻意<b>不</b>在找不到时返回空列表冒充「这个 SKU 没有批次」：
   * 空列表与「仓库服务挂了」必须能被区分开，否则运营会以为 SKU 没问题，
   * 实际上是查询根本没成功。
   */
  public LotPage candidates(UUID skuId, String keyword, int page, int size, String bearer) {
    try {
      LotPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path("/warehouse/inventory/lots");
                    if (skuId != null) uriBuilder.queryParam("skuId", skuId);
                    if (keyword != null && !keyword.isBlank()) uriBuilder.queryParam("keyword", keyword.trim());
                    uriBuilder.queryParam("page", page);
                    uriBuilder.queryParam("size", size);
                    return uriBuilder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(LotPage.class);
      if (result == null) {
        throw unavailable("仓库服务没有返回批次列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库批次档案，批次追溯暂时做不了");
      }
      throw unavailable("仓库批次列表查询返回 " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达", e);
    }
  }

  /**
   * 不可达一律 503，并**保留原始异常**。
   *
   * <p>把 {@code RestClientException} 直接吞掉而不带上 cause，等于把「连接被重置」
   * 与「响应体解析失败」这两类完全不同的故障压成同一句话 —— 排查时只能靠猜。
   * <b>HTTP 状态码分支同样带上原异常</b>：warehouse 回 503 与「连不上」是完全不同的故障，
   * 同样不能压成一句话。
   */
  private ResponseStatusException unavailable(String reason, Throwable cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：批次追溯的合法入口只有 warehouse 的授权读接口（7.3.1.1，跨库视图与跨服务 SQL 均被"
            + " AGENTS.md 禁止），本服务不会在读不到真实批次的情况下把归因写成已生效",
        cause);
  }

  private ResponseStatusException unavailable(String reason) {
    return unavailable(reason, null);
  }
}
