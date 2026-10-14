import { AllocationPolicyWorkspace } from '@/components/inventory/allocation-policy-workspace';

/**
 * 7.8.1.1 分配策略配置。
 *
 * <p>蓝图：「定义同一总可用库存分配给多个店铺/平台的方式：按销量占比比例分配、按固定优先级先到先得、
 * 按店铺类型（自营/托管）优先级、留出不分配缓冲。支持 SKU 或店铺维度的策略覆盖。」输出
 * 「生效的分配策略；各店铺分摊额」，并要求「多平台共享库存时必须有一份统一的分摊算法」。
 *
 * <p>这是一个真实工作区而不是占位页：策略分档、成员名册、销量登记、分摊试算与落库、
 * 批次回看都在这一页，落到 PostgreSQL 而不是内存。
 */
export default function AllocationPolicyPage() {
  return <AllocationPolicyWorkspace />;
}
