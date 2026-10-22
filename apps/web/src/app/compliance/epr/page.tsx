'use client';

import { EprRegistrationWorkspace } from '@/components/epr-registration/epr-registration-workspace';

/**
 * EPR / 包装法：L3「16.3.2 EPR 与包装法」下的 16.3.2.2。
 *
 * <p>蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * <strong>EPR 未注册的国家应直接限制刊登</strong>，这是避免下架与罚款最有效的做法。」
 *
 * <p>🔴 本页是<strong>16.3.2 EPR 与包装法</strong>（前端入口 <code>/compliance/epr</code>），
 * 与 <code>/compliance/tax</code>（16.3.1.1 税号与辖区）、<code>/compliance/account</code>
 * （16.1.1.1 账号健康）、<code>/compliance/violations</code>（16.1.2.1 违规与处罚）
 * <strong>是四个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>「限制刊登」不是页面上的一个红字</strong>：本页的闸门结论是服务端真算的，
 * 同一份判定函数也被 DB 层触发器用在 4 刊登的两个真实放行口上
 * （<code>listing_publish_job_target</code> → SUBMITTED、<code>publish_task</code> → DISPATCHED）。
 * 绕过前端直接调刊登端点，数据库也不会让内容进发布通道。
 *
 * <p>🔴 <strong>未交付的下游不编造、不跳转</strong>：12 财务（本仓尚未落地）、16.3.2.1 VAT/IOSS、
 * 16.3.2.3 销售税、16.3.1.2 申报日历当前<strong>都未交付</strong>，能力声明里逐条写清了缺什么、归谁。
 */
export default function EprRegistrationPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  // 从别处跳进来时带上 ?countryCode=，直接定位到那个国家的判定。
  const focus = { countryCode: one(searchParams?.countryCode) };

  return (
    <div className="space-y-4">
      <EprRegistrationWorkspace focus={focus} />
    </div>
  );
}
