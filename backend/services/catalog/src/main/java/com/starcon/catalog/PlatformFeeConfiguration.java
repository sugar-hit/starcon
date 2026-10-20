package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.2.3.1 平台费用的跨服务端口装配。
 *
 * <p><b>为什么单独一个装配类</b>：{@link PlatformFeePort} 有两个实现 ——
 * 真的走 HTTP 的 {@link PlatformFeePort.HttpPlatformFeePort}，
 * 与「上游没配」的 {@link UnwiredPlatformFeePort}。选哪一个取决于
 * {@code CHANNEL_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空集合」的实现</b>：
 * 平台费用规则挂在「平台 / 站点 / 类目」三个维度上，平台与站点的唯一真相是
 * channel 服务的全局码表（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本库里没有它们的副本）。地址没配就等于**一个平台都读不到** ——
 * 返回空清单会让运营以为「这个租户没接任何平台」，从而以为「不配置费率就是对的」，
 * 而真实情况是「我们没接上游」。{@link UnwiredPlatformFeePort} 因此**显式抛错**，
 * 由服务层翻成 503，理由写「读不到上游」而不是「没有平台」。
 */
@Configuration
public class PlatformFeeConfiguration {

  /**
   * 平台目录端口。
   *
   * @param url channel 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public PlatformFeePort platformFeePort(@Value("${CHANNEL_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new UnwiredPlatformFeePort();
    }
    return new PlatformFeePort.HttpPlatformFeePort(url);
  }

  /**
   * 没配 channel 地址时的实现：**显式抛错**，不返回空清单。
   *
   * <p>与 {@link CostBasisPort.UnwiredCostBasisPort} 同一纪律（照抄 5.2.1.1 的写法）。
   */
  static class UnwiredPlatformFeePort implements PlatformFeePort {

    @Override
    public PlatformFeeModels.PlatformDirectory directory(String bearer) {
      throw new PlatformDirectoryUnavailableException(
          "渠道服务地址没有配置（CHANNEL_URL 为空），读不到平台目录。"
              + "平台费用规则挂在「平台 / 站点」维度上，平台与站点的唯一真相是渠道服务的全局码表，"
              + "本服务不持有副本（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），"
              + "所以读不到平台清单时只能报「读不到」，不能拿空清单冒充「这个租户没有平台」");
    }
  }
}
