import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ListingsPage() {
  const spec = getDomainSpec('listings');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}
