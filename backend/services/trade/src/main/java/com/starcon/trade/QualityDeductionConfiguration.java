package com.starcon.trade;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 6.5.1.1 质检扣量端口的装配。
 *
 * <p><b>为什么今天只有一个实现</b>：6.4.2.1 质检单与本模块<b>同批并行开发</b>，
 * 此刻在集成头（{@code dd607c5b}）上既没有端点也没有事实表。
 * 照着猜一个 HTTP 客户端等于把一份<b>不存在的契约</b>固化进本仓 ——
 * 将来 6.4.2.1 落地时形状多半不同，那个「已接线」实现就会静默返回空扣量。
 *
 * <p><b>所以未接线时的行为是「显式不可用」</b>：
 * 装配点保留在这里，6.4.2.1 落地、契约定稿后在此补 HTTP 实现并改这里的 {@code @Bean}。
 * 在那之前，{@link QualityDeductionPort.UnwiredQualityDeductionPort} 是唯一实现，
 * 服务层把它如实落成明细行上的 {@code UNAVAILABLE} 标注与影响说明。
 */
@Configuration
public class QualityDeductionConfiguration {

  /**
   * 质检扣量只读端口。
   *
   * <p>当前恒为未接线实现（6.4.2.1 未交付）。落地后在此替换为 HTTP 实现。
   */
  @Bean
  public QualityDeductionPort qualityDeductionPort() {
    return new QualityDeductionPort.UnwiredQualityDeductionPort();
  }
}
