'use client';

import { BuyerBlacklistWorkspace } from '@/components/buyer-blacklist/buyer-blacklist-workspace';

/**
 * 买家黑名单：L3「16.5.1 买家风控」下的 16.5.1.1。
 *
 * <p>蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * <strong>黑名单应支持申诉解除</strong>，永久不解除会让误判的正常买家永久损失。」
 *
 * <p>🔴 本页是 <strong>16.5.1 买家风控</strong>（前端入口 <code>/compliance/buyer-blacklist</code>），
 * 与 <code>/compliance/buyer-risk</code>（16.5.1.2 风险评分）、
 * <code>/compliance/account</code>（16.1.1.1 账号健康）、
 * <code>/compliance/violations</code>（16.1.2.1 违规与处罚）、
 * <code>/compliance/precheck</code>（16.2.2.1 合规预检）
 * <strong>是五个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>本页只有「解除」，没有「删除」</strong>：黑名单是判定凭据，
 * 解除会<strong>追加一条状态流转留痕</strong>（谁 / 何时 / 为什么），
 * 原始登记不可修改 —— 事后改写判定凭据比拉黑本身更危险。
 *
 * <p>🔴 <strong>「建议拦截」不是「已拦截」</strong>：本页产出的是建议动作与订单拦截标记，
 * 真的拦不拦单是 9.2.1.3 的职责，本页<strong>不代劳</strong>，也不<strong>代建售后单</strong>。
 */
export default function BuyerBlacklistPage() {
  return (
    <div className="space-y-4">
      <BuyerBlacklistWorkspace />
    </div>
  );
}
