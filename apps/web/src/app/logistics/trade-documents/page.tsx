import { TradeDocumentWorkspace } from '@/components/logistics/trade-document';

/**
 * 10.5.2.1 贸易单证。
 *
 * <p>蓝图：「管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、
 * 进口许可与认证文件，按目的地国家规则校验完整性」；L3 锚点是 10.5.2「贸易单证与原产地」。
 *
 * <p>与 10.5.1.1 报关单同在 `/logistics`：单证包是从报关单快照生成的，缺什么单证要回到那张
 * 报关单看明细，所以两个页面共享同一批订单上下文，而不是拆成互相看不见的两个系统。
 */
export default function TradeDocumentsPage() {
  return <TradeDocumentWorkspace />;
}