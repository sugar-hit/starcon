package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import com.starcon.platform.ExternalIntegrationModels.ExternalHealthStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 18.2.3.1「其他对接」的**健康判定规则**单元测试。
 *
 * <p>🔴 <b>纯函数测试，不碰数据库</b>：健康判定是本模块最核心的一条纪律
 * （蓝图加粗句「每个对接都有独立连接与独立健康状态」），它必须**独立于**任何 I/O 被钉住。
 *
 * <p>🔴 本测试同时钉住一个**结构性事实**：
 * {@link ExternalIntegrationService#computeHealth} 的入参里**只有**
 * 「适配器通不通」与「这个连接自己的记录结论」——
 * <b>根本没有</b>「别的连接的数据」这个入口。
 * 于是「一个外部服务故障不应影响其他集成」不是一条约定，而是**类型上就成立**。
 */
class ExternalIntegrationHealthRulesTest {

  @Test
  void 有记录时以这个连接自己的记录为准() {
    // 🔴 **记录优先**：外部侧的事实（哪怕是人工登记的）不被「本系统直连通道没接通」压掉。
    assertThat(ExternalIntegrationService.computeHealth(false, List.of("SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.HEALTHY);
    assertThat(ExternalIntegrationService.computeHealth(false, List.of("FAILED", "SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.DEGRADED);
    assertThat(ExternalIntegrationService.computeHealth(false, List.of("FAILED")))
        .isEqualTo(ExternalHealthStatus.UNHEALTHY);
    assertThat(ExternalIntegrationService.computeHealth(false, List.of("NOT_CONNECTED")))
        .isEqualTo(ExternalHealthStatus.NOT_CONNECTED);
  }

  @Test
  void 一条记录都没有时才看通道通不通() {
    // 通道通、还没跑过 ⇒ 未验证（**「没跑过」不等于「坏了」**）。
    assertThat(ExternalIntegrationService.computeHealth(true, List.of()))
        .isEqualTo(ExternalHealthStatus.UNKNOWN);
    assertThat(ExternalIntegrationService.computeHealth(true, null))
        .isEqualTo(ExternalHealthStatus.UNKNOWN);
    // 通道不通、还没跑过 ⇒ 未接入（**没通道时谈不上健康**）。
    assertThat(ExternalIntegrationService.computeHealth(false, List.of()))
        .isEqualTo(ExternalHealthStatus.NOT_CONNECTED);
    assertThat(ExternalIntegrationService.computeHealth(false, null))
        .isEqualTo(ExternalHealthStatus.NOT_CONNECTED);
  }

  @Test
  void 最近一次成功即健康() {
    assertThat(ExternalIntegrationService.computeHealth(true, List.of("SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.HEALTHY);
    assertThat(
            ExternalIntegrationService.computeHealth(
                true, List.of("SUCCEEDED", "FAILED", "SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.HEALTHY);
  }

  @Test
  void 最近一次未接入即未接入() {
    assertThat(ExternalIntegrationService.computeHealth(true, List.of("NOT_CONNECTED")))
        .isEqualTo(ExternalHealthStatus.NOT_CONNECTED);
    assertThat(
            ExternalIntegrationService.computeHealth(
                true, List.of("NOT_CONNECTED", "SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.NOT_CONNECTED);
  }

  @Test
  void 最近一次失败但历史上成功过是亚健康() {
    assertThat(ExternalIntegrationService.computeHealth(true, List.of("FAILED", "SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.DEGRADED);
    assertThat(
            ExternalIntegrationService.computeHealth(
                true, List.of("TIMEOUT", "FAILED", "SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.DEGRADED);
  }

  @Test
  void 从未成功过且最近一次失败是不健康() {
    assertThat(ExternalIntegrationService.computeHealth(true, List.of("FAILED")))
        .isEqualTo(ExternalHealthStatus.UNHEALTHY);
    assertThat(
            ExternalIntegrationService.computeHealth(true, List.of("FAILED", "TIMEOUT")))
        .isEqualTo(ExternalHealthStatus.UNHEALTHY);
  }

  @Test
  void 健康判定只吃这个连接自己的记录() {
    // 「别的连接成功过」这件事**进不来** —— 入参里没有它。
    // 于是：一个对接失败**不可能**让另一个对接变健康，也不会把别的对接带成不健康。
    var onlyMine = List.of("FAILED");
    assertThat(ExternalIntegrationService.computeHealth(true, onlyMine))
        .isEqualTo(ExternalHealthStatus.UNHEALTHY);
    assertThat(ExternalIntegrationService.computeHealth(true, List.of("SUCCEEDED")))
        .isEqualTo(ExternalHealthStatus.HEALTHY);
  }
}
