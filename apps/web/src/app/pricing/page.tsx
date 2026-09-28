import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function PricingPage() {
  const spec = getDomainSpec('pricing');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}