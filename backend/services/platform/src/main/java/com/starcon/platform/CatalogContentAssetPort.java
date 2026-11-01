package com.starcon.platform;

import static com.starcon.platform.NotificationTemplateModels.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **3.4.2 文案资产**（catalog 服务，端口 8082）的**跨服务只读端口**。
 *
 * <p>蓝图 18.5.1.1 的关系写明「上游 = 3.4.2 文案资产」—— 标题库（3.4.2.1）与
 * 描述与卖点库（3.4.2.2）是**商品域的事实**，本模块只是把它们**读出来当参考**。
 *
 * <p>⚠️ **本服务不建文案资产表、不抄一份**（与 {@code CatalogProductPort} /
 * {@code CatalogTranslationPort} 同一纪律）：抄一份就会漂移 —— catalog 那边改了标题模板，
 * 本服务这边还留着旧的，运营会拿着过期文案去配通知。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（platform 侧 RLS + catalog 侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>，不返回空集合冒充「上游没有文案资产」——
 * 一个空清单在界面上会长成「上游真的没有标题模板」，而真相是「这一格没读出来」。
 */
@Service
public class CatalogContentAssetPort {

  private final PlatformUpstreamClient client;

  public CatalogContentAssetPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    this.client = PlatformUpstreamClient.to("catalog 服务", url);
  }

  /** 上游的一行标题模板（3.4.2.1 标题库）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TitleTemplateRow(
      UUID id,
      String code,
      String name,
      String note,
      String pattern,
      boolean enabled,
      long version) {}

  /** 上游的一行描述模板（3.4.2.2 描述与卖点库）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DescriptionTemplateRow(
      UUID id,
      String code,
      String name,
      String note,
      String sourceLocale,
      boolean enabled,
      long version) {}

  /**
   * 拉上游的文案资产清单。
   *
   * <p>⚠️ <b>字段名必须与上游逐字一致</b>：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
   * 写错字段名不会报错，只会静默变成 null —— 那会让清单里出现整列空白，比不写还危险。
   */
  public NotifyTemplateContentAssetView contentAssets(String bearer) {
    var titles =
        client.get(
            uriBuilder -> uriBuilder.path("/catalog/titles/templates"), bearer, TitleTemplateRow[].class);
    var descriptions =
        client.get(
            uriBuilder -> uriBuilder.path("/catalog/description-templates/templates"),
            bearer,
            DescriptionTemplateRow[].class);
    return new NotifyTemplateContentAssetView(
        mapTitles(titles), mapDescriptions(descriptions), true,
        "读的是 catalog 服务 3.4.2 文案资产（标题库 + 描述与卖点库）的**当前事实**，本模块只读不抄。");
  }

  private static List<NotifyTemplateContentAssetRow> mapTitles(TitleTemplateRow[] rows) {
    if (rows == null) return List.of();
    var out = new java.util.ArrayList<NotifyTemplateContentAssetRow>();
    for (var row : rows) {
      out.add(
          new NotifyTemplateContentAssetRow(
              "TITLE", row.id(), row.code(), row.name(), row.note(), row.enabled(), row.pattern(),
              row.version()));
    }
    return out;
  }

  private static List<NotifyTemplateContentAssetRow> mapDescriptions(DescriptionTemplateRow[] rows) {
    if (rows == null) return List.of();
    var out = new java.util.ArrayList<NotifyTemplateContentAssetRow>();
    for (var row : rows) {
      out.add(
          new NotifyTemplateContentAssetRow(
              "DESCRIPTION", row.id(), row.code(), row.name(), row.note(), row.enabled(), "",
              row.version()));
    }
    return out;
  }
}
