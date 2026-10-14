import Link from 'next/link';
import { ArrowLeft, Search } from 'lucide-react';
import { Button, EmptyState, TaskWorkspace, findTaskPage } from '@starcon/ui';

/**
 * 兜底路由 — 仅处理未规划的路径(如拼错 /oders、未来新增路径)
 *
 * 已规划的 17 个路由都有专属 page.tsx,不走这里:
 *   /                  (工作台)
 *   /styleguide        (设计系统)
 *   /copilot           (AI Copilot)
 *   /products /listings /pricing /purchase /supply
 *   /orders /aftersales /crm /analytics /finance /profit /org
 *   /logistics /warehouse /inventory
 *   /api/[...]         (API 路由)
 */
export default async function TaskPage({ params }: { params: Promise<{ slug: string[] }> }) {
  const resolvedParams = await params;
  const page = findTaskPage('/' + resolvedParams.slug.join('/'));
  if (page) return <TaskWorkspace page={page} />;
  return (
    <EmptyState
      icon={<Search className="h-6 w-6" />}
      title="这里没有页面"
      description="你访问的路径尚未规划,或地址输入有误。可以返回工作台,或查看左侧导航。"
      action={
        <div className="flex flex-wrap justify-center gap-2">
          <Button asChild>
            <Link href="/">
              <ArrowLeft className="h-4 w-4" />
              返回工作台
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link href="/styleguide">查看设计系统</Link>
          </Button>
        </div>
      }
    />
  );
}
