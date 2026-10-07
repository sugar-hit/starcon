import { LegalEntityTransferWorkspace } from '@/components/inventory/legal-entity-transfer';

/**
 * 7.5.2.1 跨法人调拨。
 *
 * <p>蓝图：「法人之间的库存转移（业务上是**货权转移 + 内部结算**），需要定价、生成内部往来单据、
 * 双方确认，**不能当作普通同仓调拨**」，输出「跨法人调拨单 + 内部结算单据 + 双方库存变更」。
 *
 * <p>页面必须让三件事看得见，否则就退化成了一张普通调拨单：
 *
 * <ul>
 *   <li>**两个法人**是页面的第一视觉要素，不是筛选器里的两个下拉 —— 货权变了意味着利润归属变了。
 *   <li>**发出开凭据、实收才转货权**，两个时刻分开显示。
 *   <li>**授权现状如实展示**：逐法人授权属 17 权限、尚未交付，页面写出来而不是含糊带过。
 * </ul>
 */
export default function CrossEntityTransfersPage() {
  return <LegalEntityTransferWorkspace />;
}
