package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **4.2.1.1 刊登编辑器**（channel 服务，端口 8083）的**跨服务只读端口**。
 *
 * <p>蓝图 18.4.2.1 的关系写明「上游 = 4.2.1.1 编辑器」—— 另一个源文本来源是**刊登草稿**
 * （标题 / 描述 / 卖点逐栏内容）。运营在刊登编辑器里改文案，改完要同步出别的语言版本，
 * 源文本就在那条草稿上。
 *
 * <p>⚠️ **只读**：本服务**不**回写刊登草稿，也不碰 channel 的库（AGENTS.md：禁止跨服务 SQL / FK）。
 * 翻译结果要回到刊登编辑器，是**那边的模块**的事 —— 本模块只负责把译文准备好并交出去。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>；<b>取不到就 503</b>。
 */
@Service
public class ChannelListingPort {

  private final PlatformUpstreamClient client;

  public ChannelListingPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = PlatformUpstreamClient.to("channel 服务", url);
  }

  /** 草稿的一栏内容。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FieldValue(String field, String text, List<String> items) {}

  /** 上游一条刊登草稿。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DraftRow(
      UUID draftId,
      String shopCode,
      String platformCode,
      String locale,
      UUID sourceSkuId,
      UUID listingId,
      String state,
      List<FieldValue> fieldValues) {}

  /** 拉一条刊登草稿（逐栏内容 = 源文本）。 */
  public DraftRow draft(UUID draftId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/channel/listing-editor/drafts/" + draftId.toString()),
        bearer,
        DraftRow.class);
  }
}
