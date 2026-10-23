import { api, ApiError } from '@/lib/api';
import {
  type GlobalSearchResponse,
  type ServiceSearchOutcome,
} from './global-search-model';

/**
 * 1.2.1.1 全局搜索框 —— 前端聚合层（并发调用各服务的只读检索端点）。
 *
 * <p><b>合并在前端做</b>（蓝图：「后端归属 = 前端聚合层 + 各服务的只读投影」）：
 * 每个服务只知道自己库里的表，跨服务的并排与去重由前端这一层完成。
 *
 * <p><b>为什么不是「一个统一检索服务」</b>：那要么让某个服务跨库查别人的表
 * （AGENTS.md 明令禁止），要么新起一个检索服务（蓝图写死「不新增业务服务」）。
 * 四个只读端点 + 前端并发，是唯一不越界的形状。
 *
 * <p><b>一个服务失败不拖垮整次搜索</b>：用 `Promise.allSettled` 语义，
 * 失败的服务进 `failedServices`，其余域照常返回 —— 仓库服务挂掉时，
 * 运营仍然应该能搜订单与商品。
 */

/** 参与检索的服务。顺序 = 结果里域的优先级（合并时按域排序，不按服务顺序）。 */
const SEARCH_SERVICES = [
  { service: 'catalog', prefix: 'catalog/global-search' },
  { service: 'trade', prefix: 'trade/global-search' },
  { service: 'channel', prefix: 'channel/global-search' },
  { service: 'warehouse', prefix: 'warehouse/global-search' },
] as const;

/** 并发调用各服务的检索端点。 */
export async function searchAllServices(
  keyword: string,
  signal?: AbortSignal,
): Promise<ServiceSearchOutcome[]> {
  return Promise.all(
    SEARCH_SERVICES.map(async ({ service, prefix }) => {
      try {
        const response = await api<GlobalSearchResponse>(
          `${prefix}?q=${encodeURIComponent(keyword)}&limit=20`,
          'GET',
        );
        return { service, response, error: null };
      } catch (error) {
        return { service, response: null, error: describeError(error) };
      }
    }),
  );
}

/** 最近搜索记录（identity 服务，个人偏好）。 */
export interface GlobalSearchRecentEntry {
  keyword: string;
  domain?: string | null;
  primaryRef?: string | null;
  searchedAt: string;
}

export async function fetchRecentSearches(limit = 10): Promise<GlobalSearchRecentEntry[]> {
  const result = await api<{ entries: GlobalSearchRecentEntry[] }>(
    `identity/global-search/recent?limit=${limit}`,
  );
  return result.entries ?? [];
}

/** 登记一条最近搜索（用户**选中**一条命中时调用，不是每次键入都调用）。 */
export async function recordRecentSearch(
  keyword: string,
  domain: string,
  primaryRef: string,
): Promise<void> {
  await api('identity/global-search/recent', 'POST', { keyword, domain, primaryRef });
}

/** 清空最近搜索。 */
export async function clearRecentSearches(): Promise<void> {
  await api('identity/global-search/recent', 'DELETE');
}

function describeError(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '服务暂时不可用';
}
