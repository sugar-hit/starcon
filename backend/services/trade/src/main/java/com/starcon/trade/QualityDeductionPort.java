package com.starcon.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.5.1.1 采购对账读 **6.4.2.1 质检扣量** 的只读端口。
 *
 * <p>蓝图 06-purchase-and-supply.md §6.5.1.1 的输入句写死「期间内的收货记录、<b>质检扣量</b>、索赔」，
 * 而质检扣量来自 <b>6.4.2 来料质检 / 6.4.2.1 质检单</b>（卷 06 责任表：质检单在 <b>trade</b> 服务）。
 *
 * <p><b>🔴 6.4.2.1 此刻在本仓不存在</b>：它与本模块<b>同批并行开发</b>，
 * 集成头（{@code dd607c5b}）上既没有 {@code /purchase/quality} 端点，也没有质检扣量的事实表。
 * 这与 6.2.1.3 采购计划遇到的 {@code PurchasePlanLockPort} 是<b>同一类问题</b>：
 * 上游拿不到时<b>显式报「未接线」，绝不降级成假装成功</b>。
 *
 * <p><b>为什么不写一个 HTTP 实现指向一个不存在的端点</b>：
 * 6.4.2.1 的**端点形状与字段都还没定**，照着猜写一个客户端等于把一份
 * <b>不存在的契约</b> 固化进本仓 —— 将来 6.4.2.1 落地时形状多半不一样，
 * 那时候本模块的「已接线」实现就是一个错的客户端（而且会静默返回空扣量）。
 * 所以本端口<b>当前只有未接线实现</b>；等 6.4.2.1 落地、契约定稿后，
 * 在这里补 HTTP 实现并改 {@link QualityDeductionConfiguration} 的装配。
 *
 * <p><b>未接线时的行为是「显式不可用」，不是 0</b>：
 * 对账明细行的 {@code qualityDeductionStatus='UNAVAILABLE'}、
 * {@code qualityDeductionQuantity=NULL}（<b>不是 0</b>），
 * 并在行上写明影响：「质检扣量未接入 ⇒ 本行应收数量**尚未扣除**质检不合格量」。
 * 写 0 会被采购员读成「这批没有扣量」，而真实情况是「我们还没接质检」。
 *
 * <p>取不到就<b>显式失败</b>（503），绝不返回空集合冒充「这个期间没有质检扣量」。
 */
public interface QualityDeductionPort {

  /** 一条质检扣量（6.4.2.1 的形状在本仓尚不存在 ⇒ 这里只声明本模块真正需要的语义）。 */
  record Deduction(
      UUID inspectionId,
      String inspectionNo,
      UUID skuId,
      BigDecimal inspectedQuantity,
      BigDecimal deductedQuantity,
      String currency,
      String verdict) {}

  /**
   * 读某个供应商在某个期间内的质检扣量。
   *
   * @throws ResponseStatusException 上游不可达 / 拒绝时抛出（503 / 403 / 401），
   *     **绝不**返回一个「看起来没有扣量」的结果。
   */
  List<Deduction> deductions(UUID supplierRefId, LocalDate from, LocalDate to, String bearer);

  /** 这个端口是否**真的**接线了。没接线时 {@link #deductions} 一定失败。 */
  boolean available();

  /** 没接线时给界面看的一句话（说明归哪个模块、为什么不显示）。 */
  String unavailableNote();

  /**
   * 「上游没配 / 上游不存在」的实现：<b>显式抛错</b>，绝不返回空集合或假成功。
   *
   * <p>理由与 6.2.1.3 的 {@code UnwiredPurchasePlanLockPort} 相同：
   * 空结果会让对账单显示「这批没有质检扣量」，而真实情况是「质检单模块还没交付」——
   * 前者会被当成业务结论（据此付全款），后者是交付缺口（据此拒付）。
   */
  class UnwiredQualityDeductionPort implements QualityDeductionPort {

    @Override
    public List<Deduction> deductions(
        UUID supplierRefId, LocalDate from, LocalDate to, String bearer) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailableNote());
    }

    @Override
    public boolean available() {
      return false;
    }

    @Override
    public String unavailableNote() {
      return "未接线：6.4.2.1 质检单（6.4.2 来料质检）与本模块同批并行开发，"
          + "此刻在本仓不存在 —— 既没有 /purchase/quality 端点，也没有质检扣量的事实表。"
          + "本模块**不按 0 计入**质检扣量：对账明细行标 qualityDeductionStatus='UNAVAILABLE'、"
          + "qualityDeductionQuantity=NULL，并写明「本行应收数量尚未扣除质检不合格量」。"
          + "⚠️ 把未接线当成「没有扣量」去付全款，是本模块要挡住的最贵的一种错账。";
    }
  }
}
