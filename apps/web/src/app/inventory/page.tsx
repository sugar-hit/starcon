import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function InventoryPage() {
  const spec = getDomainSpec('inventory');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}