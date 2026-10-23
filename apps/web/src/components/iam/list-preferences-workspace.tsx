'use client';

import { useCallback, useEffect, useState } from 'react';
import { Button, EmptyState, Notice, PageHeader, SectionCard } from '@starcon/ui';
import { RefreshCw, RotateCcw } from 'lucide-react';
import {
  describePreference,
  fetchListPreferences,
  resetAllListPreferences,
  resetListPreference,
  type UserPreferenceView,
} from '@/lib/list-preferences-model';

/**
 * 1.4.1.2 列表与表格偏好 —— 「我的列表偏好」设置页（蓝图 1.4 → `/settings/profile`）。
 *
 * <p>蓝图：「记住各列表页的列显示、排序、分页大小、筛选条件，降低重复操作成本。」
 * 这一页回答两个问题：
 *
 * <ol>
 *   <li><b>我到底在哪些页面上调过视图</b> —— 每一页存了什么、什么时候存的；</li>
 *   <li><b>怎么撤销</b> —— 单页恢复默认 / 全部恢复默认。</li>
 * </ol>
 *
 * <p><b>偏好是按页面 key 存储的</b>，所以这一页列的是「页面 key」而不是页面标题 ——
 * key 是稳定的页面标识（小写字母 / 数字 / 点 / 下划线 / 连字符），
 * 标题会改、key 不会，所以 key 才是能拿来做技术支持的入口。
 *
 * <p>三态分开：加载中 / 没有偏好（不是错误）/ 读不到（是错误，要说清）。
 */
export function ListPreferencesWorkspace() {
  const [entries, setEntries] = useState<UserPreferenceView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await fetchListPreferences();
      setEntries(result.entries ?? []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '我的列表偏好暂时读不到');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function resetOne(pageKey: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await resetListPreference(pageKey);
      setNotice(`「${pageKey}」已恢复默认`);
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '恢复默认失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  async function resetAll() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await resetAllListPreferences();
      setNotice('全部列表偏好已恢复默认');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '恢复默认失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title="列表与表格偏好"
        description="记住各列表页的列显示、排序、分页大小与筛选条件；下次进入该页时自动恢复"
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" aria-hidden />
              刷新
            </Button>
            {entries && entries.length > 0 ? (
              <Button variant="secondary" onClick={() => void resetAll()} disabled={busy}>
                <RotateCcw className="mr-2 h-4 w-4" aria-hidden />
                全部恢复默认
              </Button>
            ) : null}
          </div>
        }
      />

      {error ? (
        <Notice tone="danger" title="读取失败">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已处理">
          {notice}
        </Notice>
      ) : null}

      <SectionCard title="我保存过的页面偏好">
        {loading && !entries ? (
          <p role="status" className="text-muted-foreground text-sm">
            正在读取…
          </p>
        ) : entries && entries.length === 0 ? (
          <EmptyState
            title="还没有保存过任何列表偏好"
            description="在任意列表页调整列显示、排序、每页条数或筛选条件后，这里会出现记录；不调就永远不存。"
          />
        ) : (
          <div className="space-y-3">
            {(entries ?? []).map((entry) => (
              <div key={entry.pageKey} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="font-mono text-sm">{entry.pageKey}</p>
                    <p className="text-muted-foreground text-xs">
                      {entry.updatedAt ? `最近保存于 ${entry.updatedAt}` : '尚未记录保存时刻'}
                    </p>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() => void resetOne(entry.pageKey)}
                  >
                    <RotateCcw className="mr-1 h-4 w-4" aria-hidden />
                    恢复默认
                  </Button>
                </div>
                <ul className="text-muted-foreground mt-2 space-y-0.5 text-xs">
                  {describePreference(entry.preference).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      <SectionCard title="关于偏好">
        <ul className="text-muted-foreground list-disc space-y-1 pl-5 text-sm">
          <li>
            偏好按<b>页面 key</b> 存储（形如
            <code className="font-mono">orders.pending-shipment</code>），key 稳定、标题会改。
          </li>
          <li>偏好只属于你自己：同租户的同事看不到，也改不了。</li>
          <li>
            <b>自由文本筛选不会被记住</b>（关键字、姓名这类可能含 PII 的输入）：
            只有枚举与编号这类安全值才会落库。
          </li>
          <li>读不到偏好时列表页照常用默认视图渲染 —— 偏好是省事，不是功能依赖。</li>
        </ul>
      </SectionCard>
    </div>
  );
}
