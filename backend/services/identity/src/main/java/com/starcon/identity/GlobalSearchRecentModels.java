package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.2.1.1 全局搜索框 —— identity 侧的「最近搜索记录」形状。
 *
 * <p>与各业务服务的 {@code GlobalSearchModels} 分开：这里记的是<b>个人偏好</b>，
 * 业务服务那边记的是<b>业务事实的只读投影</b>，两者的写入所有权、生命周期与保留口径都不一样。
 */
public final class GlobalSearchRecentModels {

  private GlobalSearchRecentModels() {}

  /**
   * 一条最近搜索记录。
   *
   * @param keyword 用户当时键入的关键词
   * @param domain 选中时命中的域；空串表示只搜了没选中
   * @param primaryRef 选中那条的主标识；空串同上
   * @param searchedAt 最近一次命中这个关键词的时刻
   */
  public record GlobalSearchRecentEntry(
      String keyword,
      @Schema(nullable = true) String domain,
      @Schema(nullable = true) String primaryRef,
      OffsetDateTime searchedAt) {}

  /** 最近搜索列表。 */
  public record GlobalSearchRecentList(List<GlobalSearchRecentEntry> entries, int limit) {}

  /** 登记一条最近搜索的入参。 */
  public record GlobalSearchRecentInput(
      String keyword, @Schema(nullable = true) String domain, @Schema(nullable = true) String primaryRef) {}
}
