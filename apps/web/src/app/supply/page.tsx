import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function SupplyPage() {
  const spec = getDomainSpec('supply');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}
