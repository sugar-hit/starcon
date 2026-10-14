import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function AnalyticsPage() {
  const spec = getDomainSpec('analytics');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}
