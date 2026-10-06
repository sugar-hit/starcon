package com.starcon.catalog;

import java.util.List;
import java.util.Map;

/**
 * SPU 生命周期的状态机。状态不是自由文本，只能按 {@link Transition} 显式矩阵流转。
 *
 * <pre>
 * DRAFT(草稿) ──提交待上架──▶ PENDING_LISTING(待上架) ──上架──▶ ON_SALE(在售)
 *                              │   ▲                          │
 *                       退回草稿┘   └──撤回（仅未在售过）────┘
 * ON_SALE ──停售──▶ SUSPENDED(停售) ──恢复──▶ ON_SALE
 * SUSPENDED ──停产──▶ DISCONTINUED(停产)      DISCONTINUED 为终态，不可再流转
 * </pre>
 */
public enum SpuLifecycle {
  DRAFT("草稿"),
  PENDING_LISTING("待上架"),
  ON_SALE("在售"),
  SUSPENDED("停售"),
  DISCONTINUED("停产");

  private final String label;

  SpuLifecycle(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static SpuLifecycle parse(String value) {
    for (var state : values()) if (state.name().equals(value)) return state;
    throw new IllegalArgumentException("未知的 SPU 生命周期状态：" + value);
  }

  /** 一次合法流转：是否强制填原因、是否需要前置条件。 */
  public record Transition(
      SpuLifecycle from, SpuLifecycle to, boolean reasonRequired, boolean requiresActiveSku, String note) {}

  private static final Map<SpuLifecycle, List<Transition>> MATRIX =
      Map.of(
          DRAFT,
              List.of(
                  new Transition(DRAFT, PENDING_LISTING, true, true, "提交待上架"),
                  new Transition(DRAFT, DISCONTINUED, true, false, "作废草稿")),
          PENDING_LISTING,
              List.of(
                  new Transition(PENDING_LISTING, ON_SALE, true, true, "上架在售"),
                  new Transition(PENDING_LISTING, DRAFT, true, false, "退回草稿补资料")),
          ON_SALE,
              List.of(
                  new Transition(ON_SALE, SUSPENDED, true, false, "停售下架"),
                  new Transition(ON_SALE, DISCONTINUED, true, false, "直接停产")),
          SUSPENDED,
              List.of(
                  new Transition(SUSPENDED, ON_SALE, true, true, "恢复在售"),
                  new Transition(SUSPENDED, DISCONTINUED, true, false, "停产")),
          DISCONTINUED,
              List.of());

  /** 合法转换矩阵：每个 (from, to) 的合法性、是否强制原因、是否需要前置条件都在此显式建模。 */
  public List<Transition> transitionsFrom() {
    return MATRIX.get(this);
  }

  public boolean canTransitionTo(SpuLifecycle to) {
    return transitionsFrom().stream().anyMatch(t -> t.to() == to);
  }

  public Transition transitionTo(SpuLifecycle to) {
    for (var t : transitionsFrom()) if (t.to() == to) return t;
    throw new IllegalArgumentException(
        "不允许的状态流转：" + label() + " → " + (to == null ? "空状态" : to.label()));
  }
}