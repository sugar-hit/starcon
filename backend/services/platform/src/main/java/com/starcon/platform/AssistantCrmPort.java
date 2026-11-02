package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 15 CRM（crm 服务）的**跨服务只读端口**：18.4.1.1「对话助手」的「高价值客户流失名单」意图走这里。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 本模块**不直连 crm 的库表**（跨服务禁止直连读表），只走它已交付的只读端点。
 *
 * <p>⚠️ <b>风险分不是模型置信度</b>：上游给的是**规则分**（0–100，按阈值策略算出来的），
 * 口径说明里必须写明这一点 —— 把它叫「置信度」就是编造。
 */
@Service
public class AssistantCrmPort {

  private final PlatformUpstreamClient client;

  public AssistantCrmPort(@Value("${CRM_URL:http://127.0.0.1:8086}") String url) {
    this.client = PlatformUpstreamClient.to("crm 服务", url);
  }

  /** 流失预警一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantChurnAlert(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      String lastOrderAt,
      int daysSinceLastOrder,
      int expectedRepurchaseDays,
      int toleranceDays,
      int overdueDays,
      String thresholdSource,
      String thresholdSourceLabel,
      String categoryCode,
      BigDecimal riskScore,
      String riskLevel,
      String riskLevelLabel,
      String recallSuggestion,
      String suggestionOwner,
      String suggestionOwnerLabel,
      String status,
      String statusLabel,
      String statusNote,
      String detectedAt) {}

  /** 预警清单页（真分页 + 真计数）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantChurnAlertPage(
      List<AssistantChurnAlert> rows, int page, int size, long total, int totalPages, String note) {}

  /** 拉流失预警清单（**真实只读查询**）。 */
  public AssistantChurnAlertPage churnAlerts(String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/crm/customer-lifecycle/alerts").queryParam("page", 0).queryParam("size", 50);
          return uriBuilder;
        },
        bearer,
        AssistantChurnAlertPage.class);
  }
}
