package com.starcon.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 18.1.1.3「调用监控」的时钟。
 *
 * <p>抽成 Bean 的唯一理由：<b>配额窗口必须可被钉死</b>。
 *
 * <p>网关按 18.1.1.1 的 {@code platform_app_quota} 判限流时，窗口起点是「现在」
 * 按窗口类型**截断**得到的（MINUTE ⇒ 截断到分钟，见
 * {@code AppCredentialService.windowStart}）。若服务里散着 {@code OffsetDateTime.now()}，
 * 那么同一批连续调用只要**跨过一次窗口边界**就会落进两个不同的窗口：
 * 上一个窗口的用量看不见了 ⇒ 配额判定被清零 ⇒ 本该被限流的那次调用反而放过去了。
 *
 * <p>🔴 <b>这不是「时钟不准」，是「窗口是离散的」</b>：截断必然有边界，与机器准不准无关。
 * 边界一旦落在两次调用之间，测试就随机变红 —— 典型概率 = 调用耗时 ÷ 窗口长度
 * （MINUTE 窗口、两次调用间隔 50ms ⇒ 约 0.08%/次；跑满一批 CI 就是迟早的事）。
 * 固定时钟把边界从「时间」里拿掉，测试才是确定的。
 *
 * <p>⚠️ 刻意<b>不</b>改其余 platform 服务（18.1.1.1 应用与凭证 / 18.1.1.2 接口目录 /
 * 18.1.2.1 事件订阅 …）去注入时钟：那是别的车道与既有模块的写入面，本车道不碰。
 *
 * <p>🕐 生产用系统时钟（UTC）；测试用 {@code @Primary} 的固定时钟覆盖这一个 Bean 即可
 * —— 与 trade 的 {@code ShipmentWritebackClockConfiguration} 同一套纪律。
 */
@Configuration
public class ApiCallMonitorClockConfiguration {

  /**
   * 网关与监控共用的时钟。
   *
   * <p>🔴 必须是**同一个** Bean：网关按它算配额窗口，告警的「建议配额」也要按**同一个**
   * 窗口去读用量 —— 两个时钟会让建议值读到另一个窗口的用量，算出一个假的「用得最满的窗口」。
   */
  @Bean
  public Clock apiCallMonitorClock() {
    return Clock.systemUTC();
  }
}
