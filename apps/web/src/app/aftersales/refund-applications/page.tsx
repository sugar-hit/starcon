import { RefundApplicationWorkspace } from '@/components/aftersales/refund-application-workspace';

/**
 * 11.3.1.1 退款申请。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、
 * 原因，校验累计退款不超过可退余额，提交审批后交财务执行。」
 * 输出句：「退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。」
 * 加粗句：「<b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。」
 *
 * <p><b>为什么这条路由独立于 {@code /aftersales/refunds}</b>：那个路由部署的是
 * <b>11.3.1.2 退款状态</b>（执行、查证、派发、对账差异），而本模块是<b>申请与审批</b>
 * 的那一半。两者的写入所有者不同（申请单归本模块，执行记录归 11.3.1.2），
 * 混在同一个页面上迟早有人代写对方那张表。同理也不占用
 * {@code /aftersales/returns}（那是 11.2.1.1 退货授权单）。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/refund-applications} 等真实端点，
 * 加载 / 空 / 错误三态齐全；可退余额**逐条来自服务端**（五个占用桶），
 * 页面不自己算，也**没有**任何「可退余额」输入框。
 *
 * <p>页面**刻意不提供**「付款」按钮：真实付款执行属 12.2.2.2，12 财务整卷未交付，
 * capabilities 的 {@code handoffToFinance.available=false} 如实报出。
 */
export default function AftersalesRefundApplicationsPage() {
  return <RefundApplicationWorkspace />;
}
