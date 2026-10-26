package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **3.4.3 多语言内容**（catalog 服务，端口 8082）的**跨服务只读端口**。
 *
 * <p>蓝图 18.4.2.1 的关系写明「上游 = 3.4.3 多语言」—— 要翻的文案**本身就住在** catalog 的
 * 多语言内容里（{@code spu_translation} / {@code sku_translation}）。本端口只读它，不抄它。
 *
 * <p>⚠️ **本服务不建翻译表、不抄译文**（与 reporting 的 {@code ReportingCatalogPort}
 * 同一纪律）：译文是外部事实，抄一份就会漂移 —— catalog 那边改了译文，本服务这边还留着旧的，
 * 运营会拿着过期文案去发版。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>取不到就 503</b>，不返回空集合冒充「上游没有需要翻译的文案」。
 */
@Service
public class CatalogTranslationPort {

  private final PlatformUpstreamClient client;

  public CatalogTranslationPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    this.client = PlatformUpstreamClient.to("catalog 服务", url);
  }

  /** 上游一行语言需求。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LocaleRow(
      String locale,
      String localeName,
      boolean enabled,
      long translationCount,
      long pendingCount) {}

  /** 上游一页语言需求。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LocalePage(List<LocaleRow> items, long total, int page, int size) {}

  /** 上游一行译文（**源文本**的来源）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EntryRow(
      UUID id,
      String targetType,
      UUID spuId,
      UUID skuId,
      String locale,
      String name,
      String description,
      String status) {}

  /** 上游一页译文。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EntryPage(List<EntryRow> items, long total, int page, int size) {}

  /** 拉一页语言需求（租户的目标语言清单）。 */
  public LocalePage locales(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/translations/locales")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PlatformUpstreamClient.PAGE_SIZE),
        bearer,
        LocalePage.class);
  }

  /**
   * 拉一页译文（某个 SPU / SKU 已有的人工文案 = 可拿来翻译的**源文本**）。
   *
   * @param targetType SPU / SKU
   */
  public EntryPage entries(String targetType, UUID spuId, UUID skuId, int page, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/catalog/translations/entries")
              .queryParam("targetType", targetType)
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", PlatformUpstreamClient.PAGE_SIZE);
          if (spuId != null) uriBuilder.queryParam("spuId", spuId.toString());
          if (skuId != null) uriBuilder.queryParam("skuId", skuId.toString());
          return uriBuilder;
        },
        bearer,
        EntryPage.class);
  }
}
