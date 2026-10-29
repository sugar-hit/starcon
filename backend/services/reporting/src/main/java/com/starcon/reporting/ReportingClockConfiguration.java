package com.starcon.reporting;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * reporting 服务的**时钟**。
 *
 * <p>🔴 **为什么要有它**：1.1.2.1 核心指标卡的「今日 / 昨日 / 本月」要按**本租户时区**算边界，
 * 而「今天是哪一天」取决于时钟。时钟写死在代码里（{@code LocalDate.now()}）就没法在测试里钉死 ——
 * 测试只能撞「正好跨过午夜」那个窗口（13.1.1.2 的教训：写死 {@code timezone='UTC'} 那一类）。
 *
 * <p>所以这里声明一个 {@link Clock} Bean（与 trade 的 {@code ShipmentWritebackClockConfiguration}
 * 同构），服务层注入它，测试用固定时钟覆盖。
 */
@Configuration
public class ReportingClockConfiguration {

  @Bean
  public Clock reportingClock() {
    return Clock.systemUTC();
  }
}
