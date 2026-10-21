import { ConsignmentStockWorkspace } from '@/components/inventory/consignment-stock-workspace';

/**
 * 7.7.2.2 平台托管与全托管备货。
 *
 * <p>蓝图：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，
 * 需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。」
 *
 * <p>这是一个真实工作区而不是占位页：备货单、平台仓收发回执、平台结算回执、托管备货台账
 * 与自有库存的对账结果全部落到 PostgreSQL 16。
 *
 * <p>界面上最要紧的一条是蓝图加粗的那句「全托管下"备货"不等于"自有库存"」：
 * 备货量 / 平台在库 / 平台已售 / 结算未结是**四张分开的卡**，界面上不存在把它们相加的合计数；
 * 自有库存那一侧并排给出并标注「不含托管量」。
 */
export default function ConsignmentStockPage() {
  return <ConsignmentStockWorkspace />;
}
