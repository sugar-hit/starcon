'use client';

import { IpRiskLibraryWorkspace } from '@/components/ip-risk-library/ip-risk-library-workspace';

/**
 * 侵权库：L3「16.4 知识产权」下的 16.4.1.1。
 *
 * <p>蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * <strong>侵权检测是刊登前拦截而非事后申诉</strong>，成本差一个数量级。」
 *
 * <p>🔴 本页是<strong>16.4.1.1 侵权库</strong>（前端入口 <code>/compliance/ip-risk</code>），
 * 与 16.4.2.1 的 <code>/compliance/brand-authorization</code>（品牌授权）、
 * 16.4.2.2 的 <code>/compliance/brand-credential</code>（授权凭证）<strong>是三个页面</strong>：
 * 一个管「这条权利是不是别人的」、一个管「这个品牌有没有被授权」、
 * 一个管「授权凭证是不是真的」。三者<strong>互不改对方的表</strong>。
 *
 * <p>🔴 <strong>界面上没有「立即检测 / 放行」</strong> —— 下游 16.4.1.2 检测与预警当前<strong>未交付</strong>，
 * 页面上如实标出，<strong>不编</strong>指向不存在入口的放行形状。
 *
 * <p>🔴 <strong>没有删除入口</strong> —— 条目只停用（停用要写理由），历史记录保留。
 */
export default function IpRiskLibraryPage() {
  return (
    <div className="space-y-4">
      <IpRiskLibraryWorkspace />
    </div>
  );
}
