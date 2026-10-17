import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 17.4.2.1 待我审批的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个 `/org/my-approvals` 页面在浏览器里 404，而 CI 全绿。
 * 这里把「组件里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 identity 的全部白名单正则**；
 *   <li>从工作区源码里**反向抽取所有 `identity/my-approvals...` 与 `identity/approval-instances` 字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；
 *   <li>逐条断言应放行的放行、不该放行的（未实现的兄弟形状）不放行；
 *   <li>并**回归既有 identity 形状**：api-proxy.ts 是并行车道共同的累积型文件，
 *       本车道往里加正则时取错一侧会静默删掉别人已交付的路由（页面 404 而所有检查全绿）。
 * </ol>
 */

const IDENTITY_PREFIX = 'identity/';
const WORKSPACE = '../components/iam/my-approval-workspace.tsx';
const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

/**
 * 从 api-proxy.ts 抽出 identity 服务的**全部**白名单正则。
 *
 * <p>收全部而不是只收第一条：运行时 {@code resolve()} 逐条 fallthrough，两条元素构成并集；
 * 裸 {@code indexOf} 可能命中注释或抽到下一条元素的正则。元素边界 = 下一个元素的 prefix 行
 * （或数组结尾），不定界的话从本元素往后找第一个 {@code /^} 找到的会是**下一条元素**的正则。
 */
function identityPatterns(): RegExp[] {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: RegExp[] = [];
  for (const [i, element] of starts.entries()) {
    if (element.prefix !== IDENTITY_PREFIX) continue;
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    // pattern: 与正则字面量之间可能夹着 // 注释行，所以定位首尾而不是用一条正则跨行捞。
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${IDENTITY_PREFIX} 元素里定位白名单正则起点`);
    }
    let literal: string | undefined;
    for (let j = start + 1; j < block.length; j++) {
      const ch = block[j];
      if (ch === '\\') {
        j++; // 转义字符：跳过，不当作字面量结束
        continue;
      }
      // 判据是 `/,`（斜杠紧跟逗号）而不是裸 `/`：identity 的正则里有 `[^/]+` 这种
      // **字符类内部的未转义斜杠**（freezes 的两段路径用 `[^/]+`），裸 `/` 会把正则从那里截断，
      // 得到一个 Unterminated character class —— 一条永远读不出真正则的测试等于没有测试。
      if (ch === '/' && block[j + 1] === ',') {
        literal = block.slice(start + 1, j);
        break;
      }
    }
    if (literal === undefined) {
      throw new Error(`未能从 api-proxy.ts 的 ${IDENTITY_PREFIX} 元素里定位白名单正则终点`);
    }
    out.push(new RegExp(literal));
  }
  if (out.length === 0) throw new Error('api-proxy.ts 里没有 identity 前缀的白名单元素');
  return out;
}

/**
 * 走 resolve() 的真实形状：剥掉 identity/ 前缀后**逐条 fallthrough**，任一条命中即放行。
 *
 * <p>并且先**剥掉查询串**：resolve() 拿到的是 Next.js 路由参数 `[...path]`，查询串是之后由
 * `request.nextUrl.search` 单独拼回去的，从没参与过 pattern 匹配。带 `?` 去断言会得到一个
 * 假红，而它恰恰是最容易骗人的那种 —— 页面明明能带上筛选条件工作，测试却红了。
 */
function allowed(path: string): boolean {
  const bare = path.split('?')[0] ?? '';
  const rest = bare.startsWith(IDENTITY_PREFIX) ? bare.slice(IDENTITY_PREFIX.length) : bare;
  return identityPatterns().some((p) => p.test(rest));
}

/** 工作区源码里真实写的 identity 调用路径（模板串里的 ${...} 换成真实 UUID 再判）。 */
function workspacePaths(): string[] {
  const source = readFileSync(join(__dirname, WORKSPACE), 'utf8');
  const found = new Set<string>();
  for (const m of source.matchAll(
    /['"`](identity\/(?:my-approvals|approval-instances)[^'"`]*)['"`]/g,
  )) {
    found.add((m[1] ?? '').replaceAll('${id}', UUID).replaceAll('${query}', '?sort=WAITING'));
  }
  return [...found];
}

describe('17.4.2.1 api-proxy 白名单', () => {
  it('工作区里真实写的每一条调用路径都被放行', () => {
    const paths = workspacePaths();
    // 至少要有几条，否则说明抽取正则坏了，测试会静默变空转。
    expect(paths.length).toBeGreaterThanOrEqual(4);
    for (const path of paths) {
      expect(allowed(path), `工作区调用 ${path} 必须被白名单放行`).toBe(true);
    }
  });

  it('待审批的六种形状逐条放行', () => {
    for (const path of [
      'my-approvals',
      'my-approvals/capabilities',
      'my-approvals/batch',
      `my-approvals/${UUID}`,
      `my-approvals/${UUID}/decide`,
      `my-approvals/${UUID}/transfer`,
      'approval-instances',
    ]) {
      expect(allowed(path), `${path} 必须放行`).toBe(true);
    }
  });

  it('带查询串的列表请求与不带的是同一条路径（查询串不参与匹配）', () => {
    expect(allowed('my-approvals?sort=WAITING&minDwellSeconds=86400')).toBe(true);
    expect(allowed('my-approvals')).toBe(true);
  });

  it('未实现的兄弟形状不放行：放行不该存在的路径比 404 更糟', () => {
    for (const path of [
      // 没有「按 id 取单条实例」这个端点
      `approval-instances/${UUID}`,
      // 没有审批记录查询 —— 那是 17.4.2.2 的职责
      'my-approvals/history',
      'my-approvals/records',
      `my-approvals/${UUID}/history`,
      `my-approvals/${UUID}/approve`,
      `my-approvals/${UUID}/reject`,
      'my-approvals/batch/result',
      'my-approvals/xyz',
    ]) {
      expect(allowed(path), `${path} 不应放行`).toBe(false);
    }
  });

  it('回归：既有 identity 形状没有被本车道误删', () => {
    // 这些是别的车道已交付的入口。本车道往 api-proxy.ts 里加正则时取错一侧会静默删掉它们，
    // 症状是那些页面 404 而所有检查全绿 —— 所以每次改这个文件都要钉一遍。
    for (const path of [
      'permissions',
      'members',
      'roles',
      'departments',
      'children',
      'audit',
      'audit/export',
      'operation-log',
      'hierarchy-authorizations/check',
      'business-parameters/effective',
      'numbering/preview',
      'approval-flows',
      'approval-flows/document-types',
      'approval-flows/registrations',
      `approval-flows/${UUID}`,
      `approval-flows/${UUID}/validation`,
      'approval-flows/positions',
      'approval-rules',
      'approval-rules/effective-policy',
      'approval-rules/evaluate',
      'onboardings',
      `onboardings/${UUID}/complete`,
      'legal-entities/references',
      `legal-entities/${UUID}/accounting-books`,
      'accounting-books',
      `accounting-books/${UUID}`,
      `accounting-books/${UUID}/fact-assignments`,
      `legal-entities/${UUID}/ledger-books`,
      `legal-entities/${UUID}/default-book`,
    ]) {
      expect(allowed(path), `${path} 必须仍然放行`).toBe(true);
    }
  });
});
