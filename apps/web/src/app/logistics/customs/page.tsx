import { CustomsWorkspace } from '@/components/logistics/customs-declaration';

/**
 * 10.5.1.1 报关单管理。
 *
 * <p>蓝图：「管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR，
 * 接收放行、退单、改单结果」；L3 锚点是 10.5.1「报关与清关」，本模块是它的第一个 L4。
 *
 * <p>与 10.4.1.1 头程发运单同页签（`/logistics`）：报关单的来源就是那张发运单，
 * 「一票货只报一次关」这条约束横跨两个页面，所以它们放在同一个工作区里而不是拆成两个系统。
 */
export default function CustomsDeclarationsPage() {
  return <CustomsWorkspace />;
}
