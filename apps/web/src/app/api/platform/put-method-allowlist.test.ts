import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { PUT } from './[...path]/route';

/**
 * platform 出口的 **PUT 方法**守卫。
 *
 * <p>背景：platform 出口曾经**刻意不导出 PUT**，理由写的是「本模块没有整份替换语义的端点」。
 * 那句话在写下时是真的，但 platform 后来陆续加上了 5 个 `@PutMapping` 端点 ⇒
 * 出口不导出 PUT 时，这 5 个端点在浏览器里一律 405，**功能等于没交付**
 * （后端实现得再好，浏览器根本调不到）。
 *
 * <p>本测试钉住两件事，方向相反：
 *
 * <ul>
 *   <li><b>放行</b>：这 5 条 PUT 路径必须真的被转发（不是 405）。</li>
 *   <li><b>不放行</b>：非白名单路径的 PUT 仍然 404 —— 放行 PUT **不等于**放宽路径边界。
 *       代理的放行与否由 {@code apps/web/src/lib/api-proxy.ts} 的路径白名单决定，与方法无关；
 *       出口只决定「哪些方法能进来」。</li>
 * </ul>
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（转发 = 200，拦截 = 404），
 * 不是眼看正则、也不是自己再写一份正则去比。
 */

/**
 * id 占位符必须换成**真 UUID 形状**：白名单把 id 段钉死成完整 UUID 形状
 * （`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`），用非 UUID 占位会得到一个
 * **本来就不该放行**的路径，断言会误判成「白名单坏了」。
 */
const UUID_PLACEHOLDER = '3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8';

function request(path: string) {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
    method: 'PUT',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
}

afterEach(() => vi.unstubAllGlobals());

it('platform 的「整份替换」端点在浏览器侧可通', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `platform/auto-approvals/policies/${UUID_PLACEHOLDER}`,
    `platform/data-migration/mappings/${UUID_PLACEHOLDER}`,
    'platform/automation/effect/baselines',
    `platform/automation/effect/feedbacks/${UUID_PLACEHOLDER}/status`,
    `platform/automation/effect/advices/${UUID_PLACEHOLDER}/status`,
  ]) {
    const response = await PUT(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 的 PUT 未被转发（出口没导出 PUT ⇒ 浏览器侧 405）`).toBe(200);
  }

  // 转发出去的请求必须**带 PUT 方法** —— 只断言状态码会漏掉「方法被降级成 POST」这类错。
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8089/platform/automation/effect/baselines'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('非白名单路径的 PUT 仍然被拒 —— 放行 PUT 不等于放宽路径边界', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 服务端到服务端的用量回报路径（内部密钥），浏览器打不到
    'platform/app-quota-usages',
    // 后端没有的形状
    'platform/automation/effect/unknown',
  ]) {
    const response = await PUT(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 的 PUT 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
