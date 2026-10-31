import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 18.2.3.1 其他对接 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「能直连 1688 同步」「能取出密钥明文」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/external-integrations/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 *
 * <p><b>不误伤其他车道已放行的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 platform/ services 条目（18.2.3.1 其他对接），
 * 不动既有任何一条 —— 但那必须被断言钉住，否则下一次并入取错一侧时
 * 会静默删掉已交付模块的路由（18.1.1.1 的 {@code platform/apps}、
 * 18.4.2.1 的 {@code platform/text}、18.2.1.1 的 {@code platform/finance-software}、
 * 18.2.2.1 的 {@code platform/payment-funds}）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/integrations/external-integration-model.ts', import.meta.url),
  ),
  'utf8',
);

/** id 段的形状：完整 UUID（与白名单正则里的那一段一致）。 */
const ID = '00000000-0000-4000-8000-000000000000';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'platform/external-integrations'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/external-integrations';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    // ⚠️ 判据是「**以 base 开头且后面跟一段路径**」，不是「包含 base」——
    //   包含会把非路径的模板串（文案里带 base 的）也当成调用点。
    if (!raw || !raw.startsWith('${base}/')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'platform/external-integrations/capabilities',
      'platform/external-integrations/overview',
      'platform/external-integrations/adapters/availability',
      'platform/external-integrations/connections',
      `platform/external-integrations/connections/${ID}`,
      `platform/external-integrations/connections/${ID}/disable`,
      `platform/external-integrations/connections/${ID}/health`,
      `platform/external-integrations/connections/${ID}/health/refresh`,
      'platform/external-integrations/sync-tasks',
      `platform/external-integrations/sync-tasks/${ID}`,
      `platform/external-integrations/sync-tasks/${ID}/disable`,
      `platform/external-integrations/sync-tasks/${ID}/run`,
      'platform/external-integrations/sync-records',
      `platform/external-integrations/sync-records/${ID}`,
      'platform/external-integrations/upstream/platforms',
      'platform/external-integrations/upstream/shops',
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/external-integrations/connections',
    'platform/external-integrations/sync-tasks',
    'platform/external-integrations/sync-records',
    `platform/external-integrations/connections/${ID}/disable`,
    `platform/external-integrations/connections/${ID}/health/refresh`,
    `platform/external-integrations/sync-tasks/${ID}/disable`,
    `platform/external-integrations/sync-tasks/${ID}/run`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  const run = await POST(request(`platform/external-integrations/sync-tasks/${ID}/run`, 'POST'), {
    params: Promise.resolve({
      path: ['platform', 'external-integrations', 'sync-tasks', ID, 'run'],
    }),
  });
  expect(run.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8089/platform/external-integrations/sync-tasks/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    'platform/external-integrations',
    // 连接配置**不删**（历史同步记录要能复现），只有停用。
    `platform/external-integrations/connections/${ID}/delete`,
    // 同步任务没有「改结构」的端点。
    `platform/external-integrations/sync-tasks/${ID}/schema`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/external-integrations/connections/not-a-uuid',
    // 同步记录是**只追加**台账，删不了。
    `platform/external-integrations/sync-records/${ID}/delete`,
    // 上游两组是**分开的**两个端点，没有裸路径。
    'platform/external-integrations/upstream',
    // 🔴 绝不放行「取密钥」的形状：库里没有密钥明文，也不提供任何取密钥的端点。
    'platform/external-integrations/secret-keys',
    // 多一层仍然 404。
    'platform/external-integrations/not-a-shape',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('删除类形状被拒 —— 本模块后端没有任何 DELETE 端点', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // ⚠️ 白名单是**按路径**判的、**不分方法**（`connections/{uuid}` 是后端真有的 GET 端点，
  //   所以 DELETE 它会被转发并由后端回 405 —— 那是后端的事，不是代理的事）。
  //   代理该管的是「后端**没有**这个路径」⇒ 一律 404。
  //   「台账只追加、配置只停用」由**后端**保证：同步记录表上有拒绝 UPDATE / DELETE 的触发器，
  //   连接与任务只有 disable 端点、没有 delete 端点。
  for (const path of [
    `platform/external-integrations/connections/${ID}/delete`,
    `platform/external-integrations/sync-tasks/${ID}/delete`,
    `platform/external-integrations/sync-records/${ID}/delete`,
  ]) {
    const response = await DELETE(request(path, 'DELETE'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `DELETE ${path} 不应被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤同服务已交付的 18.1.1.1 / 18.4.2.1 / 18.2.1.1 / 18.2.2.1 —— 白名单是累积型的', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 都是 `prefix: 'platform/'`：必须靠**正则不相交**分开，
  //   而不是靠「谁先匹配上」—— 白名单里既有那支的 `^apps…$` / `^text\/…$` /
  //   `^finance\-software\/…$` / `^payment\-funds\/…$` 吃不到
  //   `external-integrations/…`。
  for (const path of [
    'platform/apps',
    'platform/apps/capabilities',
    'platform/apps/overview',
    `platform/apps/${ID}`,
    `platform/apps/${ID}/credentials`,
    'platform/text/capabilities',
    'platform/text/overview',
    'platform/text/glossary',
    `platform/text/glossary/${ID}`,
    'platform/text/tasks',
    `platform/text/tasks/${ID}/publish`,
    'platform/finance-software/capabilities',
    'platform/finance-software/accounts',
    `platform/finance-software/accounts/${ID}/disable`,
    'platform/payment-funds/capabilities',
    'platform/payment-funds/providers',
    `platform/payment-funds/providers/${ID}/disable`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道的改动误伤了`).toBe(200);
  }
});
