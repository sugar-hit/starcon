package com.starcon.reporting;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 13.6.2.1 的**业务参数**（下载有效期、同步导出阈值、单次导出行数上限）。
 *
 * <p>🔴 **单独成组件是刻意的**：服务层与执行器都要读这些参数，
 * 而执行器由服务层调用 —— 放进同一处会形成 Spring 的**循环依赖**。
 *
 * <p>🔴 **每次调用时读，不在构造时读**：这些是业务参数（不是结构性的常量），
 * 运维改完配置**不用重启**就能生效；集成测试也能逐例翻转它们。
 *
 * <p>⚠️ **两种写法都试**（环境变量名与 Spring 的宽松写法）：
 * 不同 PropertySource 的命中方式不同，两个都试一遍 ⇒
 * 容器注入、{@code @TestPropertyValues}、系统属性三种来源都能生效。
 */
@Component
public class ReportingExportSettings {

  private final Environment env;

  public ReportingExportSettings(Environment env) {
    this.env = env;
  }

  /** 下载有效期（小时）—— 蓝图写死的「给出有效期」，它是**可配置的业务参数**。 */
  public int downloadTtlHours() {
    return intProp("REPORTING_EXPORT_TTL_HOURS", "reporting.export.ttl-hours", 24);
  }

  /** 同步导出的行数上限 —— 超过它必须走异步任务（蓝图：大导出走异步）。 */
  public int syncRowThreshold() {
    return intProp(
        "REPORTING_EXPORT_SYNC_ROW_THRESHOLD", "reporting.export.sync-row-threshold", 1000);
  }

  /** 单次导出的行数上限 —— 超过它**失败并写明原因**，不静默截断。 */
  public int maxRows() {
    return intProp("REPORTING_EXPORT_MAX_ROWS", "reporting.export.max-rows", 200_000);
  }

  /** 读一个配置项（环境变量名优先，其次 Spring 的宽松写法）。 */
  public String prop(String envName, String relaxedName, String fallback) {
    var value = env.getProperty(envName);
    if (value == null || value.isBlank()) value = env.getProperty(relaxedName);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int intProp(String envName, String relaxedName, int fallback) {
    try {
      return Integer.parseInt(prop(envName, relaxedName, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
