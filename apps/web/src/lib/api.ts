export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    /**
     * Parsed response body, kept so callers can read structured detail.
     *
     * <p>3.1.3.2 组合唯一性校验要靠它：后端在 409 的 ProblemDetail 上挂 `conflicts` 结构化清单，
     * 只留一句 message 的话界面就退化成"有重复"三个字，运营没法按条改。
     */
    public body: unknown = null,
  ) {
    super(message);
  }
}

/**
 * Backend service routing.
 *
 * <p>Each service is reached through /api/<service> so the browser never holds a backend address.
 * `auth` and `identity` are the identity service, `catalog` is 03 商品主数据, `channel` is
 * 02 渠道与店铺, `warehouse` is 07 库存核算 + 08 仓储作业. Callers pass the path exactly as the
 * backend exposes it (e.g. `identity/roles`).
 *
 * <p>`trade` 是 09 订单履约与 **06 采购与供应**（蓝图 README 系统地图：06 的主责服务是
 * trade + catalog）的服务。这里此前**漏了** trade 这一项：9.3.2.2 的模型已经在调
 * `trade/fulfillment/routing/...`，而本表没有对应分支，`api()` 会直接抛
 * 「未知的接口路径」。本条目是**追加**的，不改动既有任何一项（并入时按分支求并，
 * 取错一侧也不会丢掉其它服务）。
 *
 * <p>`crm` 是 **15 客户与客服**的服务（端口 8086，数据库 starcon_crm）。
 * 15.4.2.1 知识库是它的第一条业务端点，模型在调 `crm/knowledge/...`。
 * 它与 catalog 的 `catalog/kb`（11.4.2.2 客服话术，按平台规则版本组织）是
 * **两个不同的模块**，前缀不同、命名空间不同，不要互相调用。
 *
 * <p>`finance` 是 **12 财务核算**的服务（端口 8087，数据库 starcon_finance）。
 * 12.1.1.1 应收单管理是它的第一条业务端点，模型在调 `finance/receivables/...`。
 * 它与 trade 的 `/trade/b2b-orders/receivables`（9.7.1.1 批发订单的**交易侧**账期应收）
 * 是**两处不同的事实**：trade 那张是订单侧记的应收，finance 这张是财务侧确认的应收
 * （从发货确认导入而来，带汇率快照与核销状态）。两者命名空间不同，不要互相调用。
 *
 * <p>`platform` 是 **18 集成开放与智能自动化**的服务（端口 8089，数据库 starcon_platform）。
 * 18.1.1.1 应用与凭证是它的第一条业务端点，模型在调 `platform/apps/...`。
 * 它与 channel 的 `channel/platforms`（02 渠道与店铺里「电商平台」的目录）**是两回事**：
 * 前者是「谁可以接入我们」，后者是「我们接入谁」。
 */
const backends: readonly { prefix: string; route: string }[] = [
  // `reporting` 是 **13 利润与经营分析**的服务（端口 8088，数据库 starcon_reporting）。
  // 13.1.1.1 经营总览（前端入口 `/analytics`）读的就是它。
  // 它与 finance 的 `/finance/...` 是**两个不同的模块**：前者是投影读模型（口径唯一、可下钻），
  // 后者是财务核算的写模型。命名空间不同，不要互相调用。
  //
  // ⚠️ **插入锚点选在数组最开头**（本文件第一个元素之前）：本文件是并行车道共同的累积型文件，
  // 同批其它车道把新服务插在数组末尾；本车道落在开头，并入时 git 能干净求并。
  { prefix: 'reporting/', route: 'reporting' },
  { prefix: 'auth/', route: 'iam' },
  { prefix: 'identity/', route: 'iam' },
  { prefix: 'catalog/', route: 'catalog' },
  { prefix: 'channel/', route: 'channel' },
  { prefix: 'warehouse/', route: 'warehouse' },
  { prefix: 'trade/', route: 'trade' },
  { prefix: 'crm/', route: 'crm' },
  { prefix: 'finance/', route: 'finance' },
  { prefix: 'platform/', route: 'platform' },
];

let workspaceBlocked = false;

/** Block stale workspace callbacks while the session's tenant is being confirmed. */
export function blockWorkspaceRequests(blocked: boolean) {
  workspaceBlocked = blocked;
}

export async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  if (workspaceBlocked && !['auth/session', 'auth/memberships', 'auth/switch'].includes(path))
    throw new ApiError(0, '正在确认工作空间，请稍后重试');
  const backend = backends.find((b) => path.startsWith(b.prefix));
  if (!backend) throw new ApiError(0, `未知的接口路径：${path}`);
  const response = await fetch(`/api/${backend.route}/${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    cache: 'no-store',
  });
  if (response.status === 401 && typeof window !== 'undefined')
    window.dispatchEvent(new Event('identity-expired'));
  const text = await response.text();
  let result: unknown = null;
  try {
    result = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(response.ok ? 0 : response.status, '服务响应无法解析，请重试');
  }
  if (!response.ok) {
    const body = result as { detail?: string; message?: string } | null;
    throw new ApiError(
      response.status,
      body?.detail ?? body?.message ?? '保存失败，请检查输入后重试',
      result,
    );
  }
  return result as T;
}
