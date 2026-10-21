import { ServiceTicketWorkspace } from '@/components/aftersales/service-ticket-workspace';

/**
 * 11.4.2.1 工单管理。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「管理非售后类的客户服务工单：咨询、投诉、催单、
 * 平台政策咨询；支持分配、SLA 计时、转派、关闭与满意度评价。」
 * 输出：「工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。」
 * 加粗句：「<b>工单与售后单要能互转</b>：咨询升级为投诉、投诉升级为纠纷，需要保留上下文。」
 *
 * <p><b>为什么这层有独立页面</b>：本模块是**客服工单处理人的工作面** ——
 * 接单、派单、盯 SLA、回话、转派、关闭、收评价，
 * 是一段**被时钟追着跑**的流程，不是一段被下游消费的声明。
 * 蓝图输出句「SLA 达成统计」在界面上必须看得见：
 * 倒计时由回复时限现算，已超期的工单直接显示成「已超期」。
 *
 * <p>路由挂在 {@code /aftersales} 之下（L3 11.4 平台纠纷与客诉），
 * 与同 L3 的 {@code /aftersales/knowledge}（11.4.2.2 知识库与话术）、
 * {@code /aftersales/disputes/platform-disputes}（11.4.1.1 平台纠纷）并列。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/service-tickets} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「删除工单」的入口：工单是纠纷举证材料，
 * 要停止跟单就关闭它（写明原因），不要删。
 */
export default function AftersalesServiceTicketsPage() {
  return <ServiceTicketWorkspace />;
}
