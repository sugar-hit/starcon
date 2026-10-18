import { RmaAuthorizationWorkspace } from '@/components/aftersales/rma-authorization-workspace';

/**
 * 11.2.1.1 退货授权单。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「审批退货申请：确认可退数量（原订单行已退/已发数量约束）、
 * 退货地址、买家回寄物流、目标处理方式；部分退货需说明理由。」
 * 输出：「退货授权单（授权/部分授权/拒绝）+ 拒绝理由。」
 * 加粗句：「<b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。」
 *
 * <p>**为什么这层有独立页面**：本模块的输出是一张**运营每天要逐单处置的工作面**
 * （待审批草稿 + 在途等买家寄回 + 已超时），而不是一段被下游消费的声明 ——
 * 11.2.1.2 退货指令、11.2.2.x 实收是它的**下游**，不是它的用户界面。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/rma-authorizations} 等 10 个真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「登记已收到」与「下发退货指引」两个入口：
 * 前者属 11.2.2.1（未交付），后者属 11.2.1.2（未交付）。
 * 渲染一个注定被拒的按钮等于告诉运营系统坏了，而真相是这条路径本就不该存在。
 */
export default function AftersalesReturnsPage() {
  return <RmaAuthorizationWorkspace />;
}
