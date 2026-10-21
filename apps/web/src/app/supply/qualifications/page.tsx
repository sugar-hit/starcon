import { QualificationWorkspace } from '@/components/supply/qualifications';

/**
 * 6.1.2.3 供应商资质。
 *
 * <p>蓝图：「登记与跟踪供应商资质：营业执照、质检报告、认证证书（CE / ROHS / ISO 等）、授权书，
 * 明确有效期与即将过期提醒。品牌授权需关联 03 品牌。」
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**资质过期必须阻断相关商品的刊登与发货，
 * 不能只做提醒**。所以这一页上「即将过期」与「已过期」是**两个不同的状态**（不同颜色、不同措辞），
 * 提醒与阻断也分成两处：提醒清单回答「谁被提醒了、有没有人处理」，
 * 阻断判定回答「现在能不能刊登 / 发货」。
 *
 * <p>与 6.1.2.1 档口与货源池共用 L3「供应」入口，但**各占一个独立分区**：
 * 那边是外部货源渠道的档口与货源池，这边是供应商的资质档案与阻断判定。
 */
export default function SupplyQualificationsPage() {
  return <QualificationWorkspace />;
}
