'use client';

import { IpDetectionWorkspace } from '@/components/ip-detection/ip-detection-workspace';

/**
 * 检测与预警：L3「16.4 知识产权」下的 16.4.1.2。
 *
 * <p>蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * <strong>图片侵权检测难度高于文本</strong>，误判与漏判都要留人工复核环节。」
 *
 * <p>🔴 本页是<strong>16.4.1.2 检测与预警</strong>（前端入口 <code>/compliance/ip-detection</code>），
 * 与 16.4.1.1 的 <code>/compliance/ip-risk</code>（侵权库）、
 * 16.4.2.1 的 <code>/compliance/brand-authorization</code>（品牌授权）、
 * 16.4.2.2 的 <code>/compliance/brand-credential</code>（授权凭证）<strong>是四个页面</strong>：
 * 一个管「这条权利是不是别人的」、一个管「这个商品是不是撞上了别人的权利」、
 * 一个管「这个品牌有没有被授权」、一个管「授权凭证是不是真的」。
 *
 * <p>🔴 <strong>界面上没有「拦截 / 放行」</strong> —— 拦截归 16.2.1.2（已交付），
 * 本页只显示<strong>建议</strong>（recommended_action），不编指向不存在入口的放行形状。
 *
 * <p>🔴 <strong>图片命中一律标「待人工复核」</strong>，不标「已判定侵权」——
 * 本仓没有图像识别引擎，图片比对是标识文本比对，必须留人工复核环节。
 */
export default function IpDetectionPage() {
  return (
    <div className="space-y-4">
      <IpDetectionWorkspace />
    </div>
  );
}
