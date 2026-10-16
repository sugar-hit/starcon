package com.starcon.trade;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 9.4.2.1 的时钟。
 *
 * <p>抽成 Bean 的唯一理由：<b>退避节奏必须可被钉死</b>。
 * {@code next_attempt_at} 与 {@code BACKOFF_BASE} 是本模块要断言的行为，
 * 若服务里散着 {@code OffsetDateTime.now()}，测试就只能断言「大致是 60 秒后」，
 * 而那类断言在时钟偏移或时区变化时会假绿/假红 —— 批次调度里最难查的那类问题。
 *
 * <p>刻意<b>不</b>改其余 20 来个 trade 服务的类去注入时钟：那是别的车道与既有模块的
 * 写入面，本车道不碰。测试用 {@code @Primary} 的固定时钟覆盖这一个 Bean 即可。
 */
@Configuration
public class ShipmentWritebackClockConfiguration {

  @Bean
  public Clock shipmentWritebackClock() {
    return Clock.systemDefaultZone();
  }
}
