import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function CrmPage() {
  const spec = getDomainSpec('crm');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}