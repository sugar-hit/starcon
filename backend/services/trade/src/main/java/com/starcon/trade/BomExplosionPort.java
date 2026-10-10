package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.4.1.2 冻结 BOM 展开结果时，读上游 3.5.2.2 的**跨服务端口**。
 *
 * <p>蓝图 6.4.1.2 的关系句把上游写死为「3.5.2.2 展开」，而展开表在 **catalog 服务的库**里。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * **只经 catalog 的授权 HTTP 接口**读，不直连对方库、不建跨库外键。
 *
 * <p>用的是 catalog V26 第七节**明确留给下游**的那两个接口：
 *
 * <ul>
 *   <li>{@code POST /catalog/bom-explosions} —— 按 BOM 版本 + 产量 + 仓库 + as_of 展开，
 *       返回一棵完整的展开树（{@code BomExplosionRun} 含 {@code lines}）；
 *   <li>{@code GET /catalog/boms/{id}/effective?at=} —— 规则 R4 的时刻查询，
 *       用于回答「此刻生效的是哪一版」（本模块的漂移判定）。
 * </ul>
 *
 * <p><b>本端口不重算任何东西</b>：多层递归展开（规则 E1 的逐层结转）、子 BOM 判定（E3）、
 * 深度截断（E4）、缺口公式（E2）全部是 catalog 的职责。两处各实现一遍必然漂移，
 * 而漂移的后果是「按不同口径冻结的料」—— 那正是本模块要消灭的东西。
 *
 * <p>取不到就**显式失败**，绝不返回一棵空树冒充「这个 BOM 没有组件」：
 * 那会让加工单凭空生成一份零行的冻结快照，而 DB 侧
 * {@code processing_bom_snapshot_has_lines} 会挡下它 —— 但服务层应当先把「为什么失败」
 * 如实报出来，而不是让调用方去读一个 CHECK 约束名。
 */
@Service
public class BomExplosionPort {

  /** catalog 展开树的一行（字段名逐字对齐 catalog 的 {@code BomExplosionLine} 契约）。 */
  record ExplosionLine(
      UUID id,
      UUID parentLineId,
      int depth,
      String path,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID componentId,
      UUID sourceVersionId,
      String sourceVersionNo,
      String sourceBomCode,
      BigDecimal unitQuantity,
      BigDecimal scrapRate,
      BigDecimal requiredQuantity,
      BigDecimal available,
      BigDecimal inTransit,
      BigDecimal gap,
      BigDecimal surplus,
      boolean isSubAssembly,
      UUID subBomId,
      String subBomCode,
      UUID subVersionId,
      String subVersionNo,
      boolean ambiguousSubBom,
      boolean expanded,
      boolean covered,
      boolean truncated,
      String role,
      String note) {}

  /** catalog 的一次展开运行（字段名逐字对齐 catalog 的 {@code BomExplosionRun} 契约）。 */
  record ExplosionRun(
      UUID id,
      UUID bomId,
      String bomCode,
      UUID versionId,
      String versionNo,
      String versionStatus,
      UUID outputSkuId,
      String outputSkuCode,
      BigDecimal outputQuantity,
      UUID warehouseId,
      String asOf,
      int maxDepth,
      int reachedDepth,
      String status,
      boolean truncated,
      int lineCount,
      int purchaseLineCount,
      BigDecimal totalRequired,
      BigDecimal totalGap,
      List<ExplosionLine> lines) {}

  /**
   * catalog 规则 R4 的时刻查询结果。
   *
   * <p>逐字对齐 catalog 的 {@code BomEffectiveView} 里的判定两列：
   * {@code effective=false} 是**真实业务状态**（草稿未发布、区间有缝、已发布版本全部关闭），
   * 不是「查不到」，所以 catalog 用显式布尔而不是 404 表达，本端口照此读。
   */
  record EffectiveVersion(boolean effective, String versionNo) {}

  private final RestClient client;

  public BomExplosionPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 按 BOM 版本展开一次，取回整棵展开树（3.5.2.2 的唯一入口）。
   *
   * <p>用 POST：展开会写运行记录与明细行，且一次可能有几百行。
   *
   * <p>展开超时给得比其它端口长（15s）：多层递归 + 每层一次子版本查询 + 一次
   * warehouse 可用量调用，比单层 R7 慢得多是正常的。这里放宽的是**读超时**，
   * 连接超时仍然是 3s —— catalog 挂了就该立刻失败，不该让加工单页面转 15 秒圈。
   */
  public ExplosionRun explode(
      UUID versionId,
      BigDecimal outputQuantity,
      UUID warehouseId,
      OffsetDateTime asOf,
      Integer maxDepth,
      String note,
      String bearer) {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("versionId", versionId);
    payload.put("outputQuantity", outputQuantity);
    payload.put("warehouseId", warehouseId);
    // asOf 传 ISO-8601 带偏移量：catalog 按这一刻取子 BOM 的生效版本（规则 R4）。
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，所以这里显式带偏移量。
    payload.put("asOf", asOf == null ? null : asOf.toString());
    payload.put("maxDepth", maxDepth);
    payload.put("note", note);
    try {
      ExplosionRun run =
          client
              .post()
              .uri("/catalog/bom-explosions")
              .header("Authorization", bearer)
              .body(payload)
              .retrieve()
              .body(ExplosionRun.class);
      if (run == null || run.lines() == null) {
        throw unavailable("商品中心没有返回展开结果");
      }
      return run;
    } catch (RestClientResponseException e) {
      // 循环引用：catalog 按规则 E4 返 409 且不落任何记录。
      // 这里原样把它的理由带出去 —— 「绕回自己的 BOM」必须点名路径，
      // 截断成一个笼统的「展开失败」等于让运营不知道改哪一行。
      if (e.getStatusCode().value() == 409) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "BOM 展开被商品中心拒绝（循环引用）："
                + e.getResponseBodyAsString()
                + "。绕回自己的 BOM 意味着需求量在数学上无界，不能截断成一个错的小数；"
                + "请把绕回路径上的某一环删掉再冻结");
      }
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "BOM 版本不存在或无权展开：" + versionId);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权展开该 BOM 版本，冻结快照无法进行");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法展开 BOM");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /**
   * 规则 R4 的时刻查询：此刻（{@code at}）生效的是哪一版已发布版本。
   *
   * <p>只用于**漂移提示**：回答「这张单冻结的那一版现在还生效吗」。
   * 返回 {@code effective=false} 不是错误 —— 已发布版本只能向前关闭（R5），
   * 老版本被接替后就不是生效版本了，而这正是要显式告诉运营的那件事。
   */
  public EffectiveVersion effective(UUID bomId, OffsetDateTime at, String bearer) {
    try {
      EffectiveVersion view =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/catalog/boms/{id}/effective")
                          .queryParam("at", at.toString())
                          .build(bomId))
              .header("Authorization", bearer)
              .retrieve()
              .body(EffectiveVersion.class);
      // catalog 用显式 effective 布尔表达「这一刻没有生效版本」，不是 404。
      // 这里把 null（理论上不会发生）也归到「无生效版本」，避免把真实业务状态
      // 变成一个 503 —— 那是把「BOM 还没发布」误报成「商品中心挂了」。
      return view == null ? new EffectiveVersion(false, null) : view;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new EffectiveVersion(false, null); // BOM 本身不存在或无权查看
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法核对生效版本");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达，无法核对生效版本");
    }
  }

  /**
   * 未接入 / 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意**不返回空树**：一张零行的冻结快照等于「这批料不用领任何组件」，
   * 而这恰恰是本模块要消灭的那类静默错误。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：冻结快照必须由商品中心的 BOM 展开（3.5.2.2）真算，"
            + "多层递归与逐层结转的口径只有那一个实现，本服务不复制一份算法，"
            + "也不会凭空生成一份空快照");
  }
}