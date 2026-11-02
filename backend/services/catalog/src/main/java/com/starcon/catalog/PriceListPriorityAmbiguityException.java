package com.starcon.catalog;

import com.starcon.catalog.PriceListPriorityModels.Resolution;

/**
 * 5.1.2.1 解析不出来：同一上下文里**多条价目表优先级相同**（HTTP 409）。
 *
 * <p>蓝图 5.1.1.2 关系句：「**同优先级多条有效价目必须报错让用户选**，不随机取」——
 * 本异常就是那句话的落法：把并列的候选**全部**退回给用户选，
 * 绝不替他随机取一条。
 */
public class PriceListPriorityAmbiguityException extends RuntimeException {

  private final Resolution resolution;

  public PriceListPriorityAmbiguityException(Resolution resolution) {
    super(resolution.reason());
    this.resolution = resolution;
  }

  /** 解析现场（并列候选 + 依据 + 未覆盖说明）。 */
  public Resolution resolution() {
    return resolution;
  }
}
