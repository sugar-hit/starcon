import { WarehouseFacilityWorkspace } from '@/components/warehouse/warehouse-facility-workspace';

/**
 * 8.1.1.1 仓库列表。
 *
 * <p>蓝图：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、
 * 时区、启用状态、库存量与作业量，支持筛选与新增。」以及关系里的红线：
 * 「**仓库绑定法人是财务前提**：不同法人的库存货权与收入归属不同。」
 *
 * <p>这是一个真实工作区而不是占位页：筛选（类型 / 法人 / 国家 / 状态 / 关键字）、
 * 真分页、排序白名单、库存量与作业量的真实聚合、平台仓观测值与自有账本分开显示、
 * 新增仓库（含 IANA 时区与货权法人的真实校验）都落到 PostgreSQL 16。
 *
 * <p>路由取蓝图 08 的 L3 锚点 {@code /warehouse/facilities}。刻意不并进
 * {@code /warehouse}：那一页是 L3「仓库与库位」在 8.1.2.x 交付前的入口，
 * 两条车道并行时各改各的才不会撞。
 */
export default function WarehouseFacilitiesPage() {
  return <WarehouseFacilityWorkspace />;
}
