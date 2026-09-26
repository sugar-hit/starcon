import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function PurchasePage() {
  const spec = getDomainSpec('purchase');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}