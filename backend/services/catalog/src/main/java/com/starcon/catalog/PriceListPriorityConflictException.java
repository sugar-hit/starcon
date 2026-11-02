package com.starcon.catalog;

import com.starcon.catalog.PriceListPriorityModels.ConflictReport;

/**
 * 5.1.2.1 保存时被冲突检测挡下（HTTP 409）。
 *
 * <p>蓝图：「冲突即拒绝并列出冲突明细」——
 * 所以这个异常**必须**带上完整的 {@link ConflictReport}（哪两张表、哪个店铺、哪个站点、
 * 生效区间重叠在哪、两边各是什么优先级），而不是只回一句「有冲突」。
 *
 * <p>🔴 抛出它时整个事务回滚 ⇒ 用户看到的是「**什么都没保存**」，
 * 不是「保存了但带冲突标记」。
 */
public class PriceListPriorityConflictException extends RuntimeException {

  private final ConflictReport report;

  public PriceListPriorityConflictException(ConflictReport report, String message) {
    super(message);
    this.report = report;
  }

  /** 冲突报告（结构化明细，前端逐条显示）。 */
  public ConflictReport report() {
    return report;
  }
}
