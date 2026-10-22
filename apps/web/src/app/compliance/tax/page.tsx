'use client';

import { TaxRegistrationWorkspace } from '@/components/tax-registration/tax-registration-workspace';

/**
 * 税号与辖区：L3「16.3.1 税号与辖区」下的 16.3.1.1。
 *
 * <p>蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * <strong>未注册辖区的销售额是合规炸弹</strong>：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * <p>🔴 本页是<strong>16.3 税号与辖区</strong>（前端入口 <code>/compliance/tax</code>），
 * 与 <code>/compliance/account</code>（16.1.1.1 账号健康）、<code>/compliance/violations</code>
 * （16.1.2.1 违规与处罚）<strong>是三个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>页面上的「缺失项提示」是后端算出来的真实清单</strong>，不是前端拼的一句文案：
 * 推导链是「店铺启用的站点 ⇒ 应当登记的辖区 ⇒ 那家店 / 它的法人在那个辖区到底有没有在册登记」。
 * 前端只负责把每一条的类型、严重度、人话说明与整改建议<strong>原样显示</strong>。
 *
 * <p>🔴 <strong>未交付的下游不编造、不跳转</strong>：16.3.1.2 申报日历、16.3.2.1 VAT/IOSS、
 * 16.3.2.2 EPR/包装法、12.6 税务当前<strong>都未交付</strong>，能力声明里逐条写清了缺什么、归谁。
 */
export default function TaxRegistrationPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  // 从缺失项跳进来时带上 ?shopId=，直接定位到那家店的缺口。
  const focus = { shopId: one(searchParams?.shopId) };

  return (
    <div className="space-y-4">
      <TaxRegistrationWorkspace focus={focus} />
    </div>
  );
}
