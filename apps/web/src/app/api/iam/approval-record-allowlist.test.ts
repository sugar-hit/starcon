import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 17.4.2.2 审批记录在 identity 代理白名单里的放行与反向断言。
 *
 * <p>白名单是**共享累积正则**：加一个分支很容易，写错一侧会静默删掉别的车道已交付的路由
 * （表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿）。
 * 所以每加一个分支都必须同时钉住：
 *
 * <ul>
 *   <li><b>放行</b>：本模块的每个真实形状都能穿透到后端；
 *   <li><b>反向</b>：多一层后缀仍然 404（否则 {@code approval-records/{uuid}/decide} 这种
 *       根本不存在的写形状也会被放行）；
 *   <li><b>不误伤</b>：上游 17.4.2.1 与 17.4.1.x 的既有形状仍然放行。
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

const ID = '0f6a1a4e-0e1a-4a0e-9f3a-1b2c3d4e5f60';

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

async function passes(path: string, method: Method = 'GET') {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  vi.stubGlobal('fetch', fetcher);
  const handler =
    method === 'GET' ? GET : method === 'POST' ? POST : method === 'PUT' ? PUT : DELETE;
  const [pathname, search] = path.split('?');
  if (pathname === undefined) throw new Error(`非法路径：${path}`);
  const response = await handler(
    new NextRequest(`http://localhost:3000/api/iam/${pathname}${search ? `?${search}` : ''}`, {
      method,
      headers: { origin: 'http://localhost:3000', cookie: 'starcon_session=real-session' },
      ...(method === 'GET' ? {} : { body: '{}' }),
    }),
    { params: Promise.resolve({ path: pathname.split('/') }) },
  );
  return { status: response.status, called: fetcher.mock.calls.length > 0 };
}

describe('审批记录放行', () => {
  it('集合读与三个字面量子路径都放行', async () => {
    for (const path of [
      'identity/approval-records',
      'identity/approval-records/statistics',
      'identity/approval-records/capabilities',
      'identity/approval-records/export',
    ]) {
      const result = await passes(path);
      expect(result.called, `${path} 必须放行`).toBe(true);
      expect(result.status).toBe(200);
    }
  });

  it('单条实例链路与按人视角都放行', async () => {
    for (const path of [
      `identity/approval-records/${ID}`,
      `identity/approval-records/by-actor/${ID}`,
    ]) {
      const result = await passes(path);
      expect(result.called, `${path} 必须放行`).toBe(true);
      expect(result.status).toBe(200);
    }
  });

  it('查询串不影响放行判定', async () => {
    // 查询串由 NextRequest 自己拆进 searchParams，不会混进 path 段 ——
    // 这里显式带上查询串，钉住「筛选条件不会让路径落到白名单之外」。
    const result = await passes('identity/approval-records?status=APPROVED&sort=NEWEST&size=20');
    expect(result.called).toBe(true);
    expect(result.status).toBe(200);
  });
});

describe('审批记录反向：多一层后缀仍然 404', () => {
  it('不存在的写形状不得被放行', async () => {
    for (const path of [
      `identity/approval-records/${ID}/decide`,
      `identity/approval-records/${ID}/transfer`,
      `identity/approval-records/${ID}/export`,
      'identity/approval-records/by-actor',
      'identity/approval-records/statistics/extra',
    ]) {
      const result = await passes(path);
      expect(result.status, `${path} 必须 404`).toBe(404);
      expect(result.called, `${path} 不得穿透到后端`).toBe(false);
    }
  });

  // 刻意**不**在这里断言「POST 审批记录被代理 404」：identity 代理的白名单是**按路径**判定的，
  // 不按 HTTP 方法 —— 已放行的路径上任何方法都会被转发，写请求最终由后端返回 405。
  // 「审批记录不可修改」的真正保证在两处，都已各自有断言：
  //   · 代理侧（本类）：白名单里没有任何审批记录的**写形状**子路径；
  //   · 后端侧（ApprovalRecordIntegrationTest）：控制器无写端点，且
  //     approval_instance_action 对 identity_app 只授 SELECT,INSERT。
});

describe('不误伤上游既有形状', () => {
  it('17.4.2.1 待我审批与提交入口仍然放行', async () => {
    for (const path of [
      'identity/my-approvals',
      'identity/my-approvals/capabilities',
      `identity/my-approvals/${ID}`,
      `identity/my-approvals/${ID}/decide`,
      `identity/my-approvals/${ID}/transfer`,
      'identity/approval-instances',
    ]) {
      const result = await passes(path);
      expect(result.called, `${path} 必须仍然放行`).toBe(true);
    }
  });

  it('17.4.1.x 审批流与审批规则仍然放行', async () => {
    for (const path of [
      'identity/approval-flows',
      'identity/approval-flows/document-types',
      `identity/approval-flows/${ID}`,
      'identity/approval-rules',
      'identity/approval-rules/effective-policy',
    ]) {
      const result = await passes(path);
      expect(result.called, `${path} 必须仍然放行`).toBe(true);
    }
  });

  it('identity 其余页面仍然放行', async () => {
    for (const path of [
      'identity/roles',
      'identity/members',
      'identity/audit',
      'identity/audit/export',
    ]) {
      const result = await passes(path);
      expect(result.called, `${path} 必须仍然放行`).toBe(true);
    }
  });
});
