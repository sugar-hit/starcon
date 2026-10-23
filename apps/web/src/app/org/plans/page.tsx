import { SaasPlanWorkspace } from '@/components/iam/saas-plan-workspace';

/**
 * 17.7.1.1 套餐与权益。
 *
 * <p>starcon **自身**的 SaaS 商业化域（套餐定义、权益项、订阅状态、试用与到期、
 * 超限策略），**仅平台管理员可见，租户不可访问**。
 *
 * <p>路由放在 `/org/plans` 而不是 `/pricing`：`/pricing` 是 ERP 货品定价（成本 / 费率 /
 * 调价），与本模块毫无关系，放进去会造成「平台套餐 = 货品价格」的概念混淆。
 *
 * <p>迁移到 `apps/admin`（蓝图里的 `/platform` 前端入口）是后续待办——`apps/admin` 目前
 * 只有空脚手架，本轮先把平台运营门控做在服务端（权限码 + 平台运营角色登记），
 * 页面入口暂时挂在 `apps/web`。
 */
export default function SaasPlansPage() {
  return <SaasPlanWorkspace />;
}
