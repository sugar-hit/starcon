import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function AftersalesPage() {
  const spec = getDomainSpec('aftersales');
  return (
    <AppShell title="售后中心" description="退款、退货、客诉、平台索赔。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}