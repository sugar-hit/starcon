package com.starcon.catalog;

import java.util.List;

/**
 * 僵尸清理覆盖的三种主体。
 *
 * <p>供应商与客户是 {@code party} 的两个**角色**而不是两张表 —— V27 推翻了实体目录旧稿的 supplier/customer/carrier
 * 三张表，理由是同一个对手方既是供应商又是客户时建两份记录会让 跨表对账没有稳定锚点。所以这里用两个枚举值表达"以哪个身份被观测"，指向同一张 {@code party} 表里的两类 {@code
 * party_role}。
 */
public enum ZombieEntityType {
  /** SKU。目标表 {@code sku}，状态枚举 DRAFT/ACTIVE/INACTIVE/ARCHIVED（V1）。 */
  SKU("SKU", "SKU", "ACTIVE", "INACTIVE"),
  /** 供应商。目标表 {@code party} + {@code party_role.role_type='SUPPLIER'}（V27）。 */
  SUPPLIER("供应商", "party", "ACTIVE", "INACTIVE"),
  /** 客户。目标表 {@code party} + {@code party_role.role_type='CUSTOMER'}（V27）。 */
  CUSTOMER("客户", "party", "ACTIVE", "INACTIVE");

  private final String label;
  private final String table;
  private final String activeStatus;
  private final String inactiveStatus;

  ZombieEntityType(String label, String table, String activeStatus, String inactiveStatus) {
    this.label = label;
    this.table = table;
    this.activeStatus = activeStatus;
    this.inactiveStatus = inactiveStatus;
  }

  public String label() {
    return label;
  }

  /** 主数据所在的表（{@code party} 对应两种角色）。 */
  public String table() {
    return table;
  }

  /** 停用动作的起始状态：只有仍在用的主数据才可能是僵尸。 */
  public String activeStatus() {
    return activeStatus;
  }

  /** 停用动作的目标状态（Z6：已经是它就不重复停用）。 */
  public String inactiveStatus() {
    return inactiveStatus;
  }

  /** 该主体的"最后一次维护时刻"取自哪一列 —— 这两列都是 catalog 自己的写入所有权（规则 Z2）。 */
  public boolean partyBacked() {
    return this != SKU;
  }

  public static ZombieEntityType parse(String value) {
    for (var t : values()) if (t.name().equals(value)) return t;
    throw new IllegalArgumentException("未知的主体类型：" + value);
  }

  public static List<ZombieEntityType> ordered() {
    return List.of(SKU, SUPPLIER, CUSTOMER);
  }
}
