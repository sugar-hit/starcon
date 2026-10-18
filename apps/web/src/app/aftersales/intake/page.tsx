import { AftersalesIntakeWorkspace } from '@/components/aftersales/aftersales-intake-workspace';

/**
 * 11.1.1.3 售后受理。
 *
 * <p>蓝图：「受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、
 * 设置回复时限。」输出句：「售后单（待处理）；政策判定结果；责任人分配。」
 * 加粗句：「<b>必退情形（平台强制、收到即退）要自动判定</b>，人工不能为了业绩拖着不退导致平台判责。」
 *
 * <p>路由挂在 {@code /aftersales/intake}，与 11.1.1.1 的列表（{@code /aftersales}）、
 * 11.1.1.2 的详情（{@code /aftersales/cases/{id}}）并列 —— 三者形状互斥，
 * 所以在 api-proxy 的白名单里也是三条互不相交的正则。
 *
 * <p>这是**真实工作区**：受理、判定凭据、回复时限策略与责任人名册
 * 全部走 {@code /trade/aftersales/intake} 的真实端点，不是占位页。
 */
export default function AftersalesIntakePage() {
  return <AftersalesIntakeWorkspace />;
}
