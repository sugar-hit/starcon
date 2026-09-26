import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function PricingPage() {
  const spec = getDomainSpec('pricing');
  return (
    <AppShell title="定价中心" description="多币种价格、汇率、定价策略与平台费率模型。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}