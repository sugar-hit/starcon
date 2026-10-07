package com.starcon.catalog;

/**
 * 候选判定结果。**三态而不是布尔**，因为布尔无法表达「无法判定」。
 *
 * <p>这正是本模块最关键的一条纪律（规则 Z1）：把「上游还没接入」当成「这个 SKU 从未被引用」
 * 会让每个 SKU 都变成候选，运营批量一点就把整个目录停用了 —— 而系统不报错，
 * 因为它确实"如实"地读了 0。所以第三态必须存在，且**不允许被当成候选**。
 */
public enum ZombieVerdict {
  /** 判定为僵尸：所有启用中的维度都被观测到，且全部为零。 */
  CANDIDATE("僵尸候选", true),
  /** 判定为非僵尸：至少一个启用中的维度被观测到且非零（这条主数据有人在用）。 */
  NOT_ZOMBIE("在用", false),
  /**
   * **无法判定**：规则启用了某个维度，但该维度没有观测值，或观测值已过期。
   *
   * <p>刻意**不**折进 {@link #CANDIDATE} 也不折进 {@link #NOT_ZOMBIE}：
   * 折进前者会误停用，折进后者会让界面显示"有人在用"而运营永远查不出为什么
   * （真相是"我们根本不知道"）。它必须单独可见，并且批量停用端点拒收（规则 Z4）。
   */
  INCOMPLETE("无法判定", false);

  private final String label;
  private final boolean actionable;

  ZombieVerdict(String label, boolean actionable) {
    this.label = label;
    this.actionable = actionable;
  }

  public String label() {
    return label;
  }

  /** 是否可以被批量停用。只有 {@link #CANDIDATE} 为真 —— 规则 Z4 据此拒收整批。 */
  public boolean actionable() {
    return actionable;
  }
}