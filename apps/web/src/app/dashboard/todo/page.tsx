import { AnomalyOrderWorkspace } from '@/components/dashboard/anomaly-order-workspace';

/**
 * 1.1.1.2 异常订单队列（卷 01 · L3 1.1.1 待办与待决策 → {@code /dashboard/todo}）。
 *
 * <p>蓝图原文：「识别『卡住了』的订单并给出处置建议」——
 * 异常类型：平台超时未回传发货、地址不可达、买家留言待确认、黑名单买家、
 * 库存不足卡单、地址欺诈疑似、重复下单。
 *
 * <p>🔴 **本页面只做 1.1.1.2 这一格**，不把 1.1.1.1「我的待办」与
 * 1.1.1.3「审批待办」一起包进来 —— 那两个模块尚未交付，
 * 由后续车道在同一路由下补另外两块。本页在结构上留好了扩展位
 * （顶部说明区与筛选区是共用的），但**不替兄弟模块实现**。
 *
 * <p>🔴 **工作台不写业务数据**：本页只读 + 跳转，一切状态变更回到归属系统完成
 * （卷 01 的硬边界）。
 */
export default function AnomalyOrderQueuePage() {
  return <AnomalyOrderWorkspace />;
}
