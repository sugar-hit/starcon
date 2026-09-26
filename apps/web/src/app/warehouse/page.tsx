import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function WarehousePage() {
  const spec = getDomainSpec('warehouse');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}