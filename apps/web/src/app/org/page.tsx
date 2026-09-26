import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function OrgPage() {
  const spec = getDomainSpec('org');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}