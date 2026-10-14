import { AllocationPriorityWorkspace } from '@/components/inventory/allocation-priority-workspace';

/**
 * 7.8.1.2 优先级与保底。
 *
 * <p>蓝图：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其店铺
 * 绩效与平台考核。输入：店铺优先级；保底数量/比例；当前销售与绩效。输出：分摊结果（含被压低店铺的提示）；
 * 保底生效记录。」以及关系段的红线：「保底是策略不是事实，需可解释可调整。」
 *
 * <p>这是一个真实工作区而不是占位页：保底分档与换版、带保底的分摊试算与落库、
 * 逐店的保底生效与被压低留痕都在这一页，落到 PostgreSQL 而不是内存。
 */
export default function AllocationPriorityPage() {
  return <AllocationPriorityWorkspace />;
}
