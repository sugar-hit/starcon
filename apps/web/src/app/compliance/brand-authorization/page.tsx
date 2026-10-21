'use client';

import { BrandAuthorizationWorkspace } from '@/components/brand-authorization/brand-authorization-workspace';

/**
 * 品牌授权：L3「16.4.2 品牌与授权」下的 16.4.2.1。
 *
 * <p>蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。
 * 输出：授权档案 + 校验结果。关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * <strong>授权过期是「平时没事、上新时出事」的典型</strong>，需到期提醒与刊登阻断。」
 *
 * <p>🔴 本页与 catalog 3.2.1.2「品牌授权」是<strong>两个模块</strong>：catalog 那套是品牌档案自带的
 * 授权凭证（回答「这个品牌有没有被授权过」），本页是<strong>合规口径的授权档案</strong>
 * （授权方 / 授权范围 / 授权文件 / 被授权主体 + 刊登时的范围校验结论）。
 * 两边不共用表、不共用端点、不共用页面。
 *
 * <p>🔴 页面上的红字<strong>不是防线</strong>：阻断结论是服务端
 * （<code>/channel/brand-authorizations/publish-gate</code>）算出来的，
 * 4.2.3.2 预检与 4.3.1.1 一键刊登消费的是那个服务端结论。
 */
export default function BrandAuthorizationPage() {
  return (
    <div className="space-y-4">
      <BrandAuthorizationWorkspace />
    </div>
  );
}
