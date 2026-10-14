import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function AftersalesPage() {
  const spec = getDomainSpec('aftersales');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}
