import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function InventoryPage() {
  const spec = getDomainSpec('inventory');
  return (
    <AppShell title="库存中心" description="中央库存池、跨平台库存协同。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}