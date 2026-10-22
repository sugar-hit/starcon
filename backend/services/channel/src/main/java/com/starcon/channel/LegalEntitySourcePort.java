package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.3.1.1 税号与辖区的<b>跨服务只读端口</b>：17 法人档案（<b>identity</b> 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经授权 HTTP API</b>读：
 * {@code GET /identity/legal-entities}（法人档案，只读）。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 与
 * {@link BrandAuthorizationSourcePort} / {@link CompliancePrecheckSourcePort} 同一手法。
 * 理由是本模块的产出是<b>税务登记档案 + 缺失项提示</b>：读不到法人档案时，正确答案是
 * 「这个法人<b>读不到</b>」（available=false + 原因），而不是「法人叫空白」或
 * 「这个主体没有法人」。把读不到折成空值，界面会显示成「没绑定法人」——
 * 那是这类合规台账最致命的一种撒谎。
 *
 * <p>🔴 <b>只读、只取显示必需的字段</b>：本端口只声明 {@code id / code / legalName / status}
 * 四个字段，且全部 {@code @JsonIgnoreProperties(ignoreUnknown = true)} ——
 * 读得越少越不容易被上游加字段撑爆。
 *
 * <p>🔴 <b>本端口不建法人影子表、也不复制 identity 的任何一列</b>（法人名称 / 税号 / 注册国
 * 一律不落本库）。法人主体在 {@code tax_registration_record} 里只有<b>逻辑 ID</b>。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，identity 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 */
@Service
public class LegalEntitySourcePort {

  /** 17 法人档案：法人列表（只读）。 */
  static final String LEGAL_ENTITY_PATH = "/identity/legal-entities";

  /**
   * 法人目录的一次读取结果。
   *
   * @param available 上游读到了吗（false ⇒ 界面显示「读不到」，**不是**「没有法人」）
   * @param items 法人
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record LegalEntitySet(boolean available, List<LegalEntityRef> items, String note) {}

  /**
   * 一个法人的**最小引用**（只读、只用于显示）。
   *
   * @param id 法人逻辑 ID
   * @param code 法人编码
   * @param legalName 法人名称
   * @param status 法人状态
   */
  public record LegalEntityRef(UUID id, String code, String legalName, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LegalEntityRow(String id, String code, String legalName, String status) {}

  private final RestClient identity;

  public LegalEntitySourcePort(@Value("${IDENTITY_URL}") String identityUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.identity = RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
  }

  /**
   * 读法人目录。
   *
   * @param bearer 调用方自己的 bearer（转发它，租户由 identity 按成员关系解析）
   */
  public LegalEntitySet legalEntities(String bearer) {
    try {
      List<LegalEntityRow> rows =
          identity
              .get()
              .uri(LEGAL_ENTITY_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<LegalEntityRow>>() {});
      if (rows == null) {
        return new LegalEntitySet(false, List.of(), "identity 服务没有返回法人列表");
      }
      List<LegalEntityRef> items = new ArrayList<>();
      for (LegalEntityRow row : rows) {
        if (row == null || row.id() == null || row.id().isBlank()) {
          continue;
        }
        items.add(
            new LegalEntityRef(
                UUID.fromString(row.id()),
                row.code() == null ? "" : row.code(),
                row.legalName() == null ? "" : row.legalName(),
                row.status() == null ? "" : row.status()));
      }
      return new LegalEntitySet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new LegalEntitySet(false, List.of(), "调用方无权读取法人档案（identity 拒绝）");
      }
      return new LegalEntitySet(false, List.of(), "identity 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new LegalEntitySet(false, List.of(), "identity 服务不可达");
    } catch (RuntimeException e) {
      return new LegalEntitySet(false, List.of(), "读取法人档案失败：" + e.getMessage());
    }
  }
}
