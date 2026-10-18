import { RmaInstructionWorkspace } from '@/components/aftersales/rma-instruction-workspace';

/**
 * 11.2.1.2 退货指令下发。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「向买家发出退货指引（地址、要求、时限），
 * 生成退货标签，跟踪买家回寄物流。」
 * 输出：「退货指令 + 标签文件 + 回寄物流轨迹跟踪。」
 * 加粗句：「<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。」
 *
 * <p>**为什么这层有独立页面**：本模块的输出是**运营每天要逐单跟的工作面**
 * （已送达待确认 / 等买家寄回 / 已超时 / 轨迹异常），而不是一段被下游消费的声明。
 * 11.2.1.1 退货授权单是它的**上游**（授权批下来才轮得到这里），11.2.2.1 实收是它的下游。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/rma-instructions} 等 12 个真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「登记已收到」与「创建退货运单」两个入口：
 * 前者属 11.2.2.1（未交付），后者属 10 物流（未交付）。
 * 渲染一个注定被拒的按钮等于告诉运营系统坏了，而真相是这条路径本就不该存在。
 */
export default function AftersalesReturnInstructionsPage() {
  return <RmaInstructionWorkspace />;
}
