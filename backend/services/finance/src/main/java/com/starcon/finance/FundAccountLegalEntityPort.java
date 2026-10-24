package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 17 法人（identity 服务）的**跨服务授权只读端口** —— 12.7.1.1 账户管理的开户主体来源。
 *
 * <p>蓝图 12-finance.md（12.7.1.1）写死「关系：上游 = 17 法人」与加粗句
 * 「<b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误」。
 *
 * <p>🔴 <b>跨库 FK / 跨服务 SQL / 共享视图一律禁止</b>（AGENTS.md 模块边界纪律）：
 * identity 的 {@code legal_entity} 表在<b>另一个库</b>，本服务只经 identity 的
 * <b>授权只读 HTTP API</b> {@code GET /identity/legal-entities} 读，
 * 写法与 {@link CostValuationScopePort} / {@link ApprovalPort} 同构（转发调用方 bearer）。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * identity 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>
 * （finance 侧 RLS + identity 侧 RLS），不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> ——
 * 与 {@link CostValuationScopePort}、channel 的 {@code LegalEntitySourcePort} 同一手法。
 * 理由：法人清单读不到时，正确答案是「法人清单<b>读不到</b>」，
 * 而不是「这个租户没有法人」—— 后者会让人以为账户无处可配；
 * 更不能「读不到就当校验通过」—— 那等于把「账户必须绑定法人」这条纪律架空。
 *
 * <p><b>需要的权限</b>：{@code identity.legal_entity.read}（identity 侧的法人档案读权限）。
 * 调用方没有这一档时，本端口返回 {@code available=false}，
 * 服务层据此 <b>拒绝</b>建 / 改账户并说清原因（而不是写一个没校验过的法人 id）。
 */
@Service
public class FundAccountLegalEntityPort {

  /** identity 法人档案（只读）。 */
  static final String LEGAL_ENTITY_PATH = "/identity/legal-entities";

  /** 法人目录的一次读取结果。 */
  public record LegalEntitySet(boolean available, List<LegalEntityRef> items, String note) {}

  /** 一个法人的最小引用（只读、只用于显示与校验）。 */
  public record LegalEntityRef(UUID id, String code, String legalName, String status, String baseCurrency) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LegalEntityRow(
      String id,
      String code,
      String legalName,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String baseCurrency) {}

  /**
   * 上游 JSON 的解析器。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}。
   * 本端口只读 id / 编码 / 名称，金额不经过它，但解析器与同族端口保持一致，
   * 免得哪天上游多返回一个金额字段就把金额悄悄改掉一位。
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient identity;

  public FundAccountLegalEntityPort(@Value("${IDENTITY_URL}") String identityUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.identity =
        RestClient.builder()
            .baseUrl(identityUrl)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(
                    new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        JSON)))
            .build();
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
      var items = new ArrayList<LegalEntityRef>();
      for (var row : rows) {
        if (row == null || row.id() == null || row.id().isBlank()) continue;
        UUID id;
        try {
          id = UUID.fromString(row.id());
        } catch (IllegalArgumentException e) {
          // 上游给了个不是 uuid 的 id ⇒ 跳过并说明，不当成「法人存在」。
          continue;
        }
        items.add(
            new LegalEntityRef(
                id,
                row.code() == null ? "" : row.code(),
                row.legalName() == null ? "" : row.legalName(),
                row.status() == null ? "" : row.status(),
                row.baseCurrency() == null ? "" : row.baseCurrency()));
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
