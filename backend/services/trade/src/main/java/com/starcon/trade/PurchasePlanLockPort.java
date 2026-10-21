package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.3 采购计划「锁库」的**跨服务写端口** —— 计划行锁定时预占库存的唯一合法来源与唯一出口。
 *
 * <p>蓝图输入句写死「<b>锁库策略</b>」，职责句写死「支持调整、<b>锁定</b>、导出给采购员」。
 * 「锁定」这件事最容易做成假的：查一次可用量、在自己库里写个 {@code is_locked = true}，
 * 看起来锁定完成了 —— 那不是锁定，那是把「查到什么」记成「占住了什么」，
 * 并发两单会把同一份库存锁定两次。
 *
 * <p>实查集成头：<b>库存预占事实的写入所有者是 warehouse</b>（7.2.1.1 {@code inventory_reservation}，
 * 迁移 V102；物理圈货到 bucket 由 7.2.1.2 {@code reservation_allocation} 落 V107）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 外键 / 共享视图，所以本端口<b>只经 warehouse 的授权
 * HTTP 端点</b>写，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用 7.2.1.1 已交付的形状：{@code POST /warehouse/inventory/reservations}。
 * 入参的受控词表由 warehouse 的 V102 钉死：{@code source_system ∈ ('TRADE','PROCESSING','MANUAL')}、
 * {@code source_type ∈ ('SALES_ORDER','PROCESSING_ORDER','OTHER_HOLD')} —— 采购计划的锁库
 * <b>不是</b>销售单也不是加工领料，所以取 {@code TRADE} + {@code OTHER_HOLD}，
 * 并把计划行 id 放进 {@code source_ref_id}（warehouse 侧的定位键），
 * 把「计划号 / SKU 编码」放进 {@code source_ref_code}（人类可读快照）。
 *
 * <p><b>🔴 未接线时必须显式报「未接线」，不能假装锁定成功</b>：本接口有两个实现 ——
 * 真的走 HTTP 的 {@link HttpPurchasePlanLockPort}，与「上游没配」的
 * {@link UnwiredPurchasePlanLockPort}。选哪一个取决于 {@code WAREHOUSE_URL} 有没有配，
 * 而这件事是**部署事实**，不是代码分支（与 catalog 的 {@code LandedCostConfiguration} 同一手法）。
 * 未接线时的实现**显式抛错**，由服务层记成「锁定失败」并把原文带给调用方 ——
 * 绝不降级成「先记着稍后补锁」，那样会造出一批账上已锁定、仓库里没占住货的计划行。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。隔离是<b>两道</b>
 * （trade 侧 RLS + warehouse 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ 登记预占在 warehouse 侧要 {@code inventory.update} 权限。调用方角色没有该权限时
 * warehouse 返回 403，本端口<b>原样如实报出</b>，由服务层记成锁定失败。
 */
public interface PurchasePlanLockPort {

  /** 一次预占登记的入参（字段名逐字对齐 warehouse 的 {@code ReservationCreateInput}）。 */
  record LockRequest(
      String sourceSystem,
      String sourceType,
      UUID referenceId,
      String referenceCode,
      UUID legalEntityId,
      UUID skuId,
      BigDecimal requestedQuantity,
      OffsetDateTime heldAt,
      OffsetDateTime expiresAt,
      String note) {}

  /**
   * 预占登记结果（只声明本模块用到的字段）。
   *
   * <p>字段名<b>逐字对齐</b> warehouse 的 {@code ReservationCreated}：{@code id} / {@code reservationNo}
   * / {@code status} / {@code statusLabel}。写成 {@code reservationId} 会让 Jackson 认不出
   * 「响应里的 id」—— 本端口自己的 ObjectMapper 忽略未知字段，于是 id 被丢掉、
   * 端口判定「没返回登记结果」而报 503，看起来像仓库挂了。
   */
  record LockOutcome(UUID id, String reservationNo, String status, String statusLabel) {}

  /** 这个端口是否**真的**接线了。没接线时 {@link #reserve} 一定失败。 */
  boolean available();

  /** 没接线时给界面看的一句话（说明归哪个服务、为什么不显示）。 */
  String unavailableNote();

  /**
   * 真登记一笔预占。
   *
   * @throws ResponseStatusException 上游不可达 / 拒绝时抛出（503 / 403 / 401），
   *     **绝不**返回一个「看起来成功」的结果。
   */
  LockOutcome reserve(LockRequest request, String bearer);

  /** 真的走 HTTP 的实现。 */
  class HttpPurchasePlanLockPort implements PurchasePlanLockPort {

    /** 端口专用的 ObjectMapper：忽略上游多出来的字段。 */
    private static final ObjectMapper PORT_JSON =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RestClient client;

    public HttpPurchasePlanLockPort(String url) {
      var factory =
          new JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      // 写操作要给得比读宽一点：预占要落对方的库并过 DB 触发器，但仍必须有上限 ——
      // 没有上限时仓库一慢就把本服务的请求线程全占住。
      factory.setReadTimeout(Duration.ofSeconds(8));
      var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
      this.client =
          RestClient.builder()
              .baseUrl(url)
              .requestFactory(factory)
              .messageConverters(java.util.List.of(converter))
              .build();
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public String unavailableNote() {
      return "已接入：warehouse 的授权写接口 POST /warehouse/inventory/reservations（7.2.1.1），"
          + "预占事实由 warehouse 持有并受其 RLS 与 DB 触发器约束。";
    }

    @Override
    public LockOutcome reserve(LockRequest request, String bearer) {
      try {
        LockOutcome outcome =
            client
                .post()
                .uri("/warehouse/inventory/reservations")
                .header("Authorization", bearer)
                .body(request)
                .retrieve()
                .body(LockOutcome.class);
        if (outcome == null || outcome.id() == null) {
          throw unavailable("仓库服务没有返回预占登记结果");
        }
        return outcome;
      } catch (RestClientResponseException e) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
          throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "当前身份无权在仓库登记预占（需要仓库的库存更新权限），本次锁定失败，没有产生任何预占");
        }
        throw unavailable("仓库预占登记返回 " + e.getStatusCode().value());
      } catch (RestClientException e) {
        throw unavailable("仓库服务暂时不可达", e);
      }
    }

    private ResponseStatusException unavailable(String reason) {
      return unavailable(reason, null);
    }

    /**
     * 不可达一律 503，并**保留原始异常**：把 {@code RestClientException} 直接吞掉而不带上 cause，
     * 等于把「连接被重置」与「响应体解析失败」这两类完全不同的故障压成同一句话。
     */
    private ResponseStatusException unavailable(String reason, Throwable cause) {
      return new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          reason
              + "：采购计划锁库的唯一合法入口是 warehouse 的授权写接口（7.2.1.1，"
              + "跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会在没有真实预占的情况下把计划行写成已锁定",
          cause);
    }
  }

  /**
   * 「上游没配」的实现：<b>显式抛错</b>，绝不返回空集合或假成功。
   *
   * <p>理由与 catalog 的 {@code UnwiredLandedCostPort} 相同：空结果会让页面显示「没有锁定过」，
   * 而真实情况是「我们没接仓库」—— 前者会被当成业务结论，后者是部署问题。
   */
  class UnwiredPurchasePlanLockPort implements PurchasePlanLockPort {

    @Override
    public boolean available() {
      return false;
    }

    @Override
    public String unavailableNote() {
      return "未接入：仓库服务地址未配置（WAREHOUSE_URL 为空），采购计划锁库发不出去。"
          + "锁库的事实来源是 warehouse 7.2.1.1 的库存预占（预占事实的写入所有者是 warehouse，"
          + "跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
          + "本服务不会在自己库里造一张影子表假装锁住了货。";
    }

    @Override
    public LockOutcome reserve(LockRequest request, String bearer) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailableNote());
    }
  }
}
