import { AppShell, EmptyState, Button } from '@starcon/ui';
import { resolveSystemFromPath, resolveFeatureFromPath } from '@starcon/ui';
import { Package } from 'lucide-react';

interface PlaceholderProps {
  params: { slug?: string[] };
}

/**
 * 兜底路由 — 所有已规划但还没实现具体业务页的 domain
 * 走这里（订单中心 / 商品中心 / 财务中心 ...）
 *
 * 不覆盖：
 *   - /               （app/page.tsx）
 *   - /styleguide     （app/styleguide/page.tsx）
 *   - /api/[...]      （app/api/.../route.ts）
 */
export default function PlaceholderPage({ params }: PlaceholderProps) {
  // 由 pathname 推断当前 system + feature
  // 这里 params.slug 是数组，转回 string
  const pathname = '/' + (params.slug?.join('/') ?? '');
  const system = resolveSystemFromPath(pathname);
  const feature = resolveFeatureFromPath(pathname, system);

  return (
    <AppShell title={feature?.label ?? '未知页面'}>
      <EmptyState
        icon={<Package className="h-6 w-6" />}
        title={`${feature?.label ?? '该功能'} 即将上线`}
        description={
          feature?.description
            ? `${feature.description}。当前正在搭建，先用 styleguide 看设计令牌。`
            : '该功能正在搭建，先用 styleguide 看设计令牌。'
        }
        action={
          <a href="/styleguide">
            <Button variant="outline">查看设计系统</Button>
          </a>
        }
      />
    </AppShell>
  );
}