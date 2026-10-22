'use client';

import { ComplianceInterceptWorkspace } from '@/components/compliance-intercept/compliance-intercept-workspace';

/**
 * 拦截执行：L3「16.2.1 合规规则」下的 16.2.1.2。
 *
 * <p>蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * <strong>豁免必须有审批与理由</strong>，否则阻断会被随意绕过。」
 *
 * <p>🔴 本页是<strong>16.2.1.2 拦截执行</strong>（前端入口 <code>/compliance/intercept</code>），
 * 与 16.2.1.1 的 <code>/compliance/rules</code>（规则库）、16.1.2.1 的
 * <code>/compliance/violations</code>（违规与处罚）、16.1.1.1 的 <code>/compliance/account</code>
 * （账号健康总览）<strong>是四个页面</strong>：
 * 一个看「我们自己定的合规规则是什么、哪一版正在生效」、
 * 一个看「在关键节点上拦了谁、为什么拦、怎么解开」。
 *
 * <p>🔴 <strong>结论是三态 + 一个「判不了」</strong> —— 页面上「判不了」单独成卡、单独成徽章，
 * <strong>绝不</strong>并进「通过」：判不出来不等于没毛病。
 *
 * <p>🔴 <strong>阻断在服务端强制</strong>：刊登派发路径（4.3.1.1）读服务端结论才放行，
 * 页面只是把 <code>blocked</code> 与逐条原因显示出来 —— 改前端绕不过服务端。
 */
export default function ComplianceInterceptPage() {
  return (
    <div className="space-y-4">
      <ComplianceInterceptWorkspace />
    </div>
  );
}
