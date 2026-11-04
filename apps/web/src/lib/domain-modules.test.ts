import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DOMAIN_MODULES } from './domain-modules.generated';
import {
  collectDomainModules,
  formatOutput,
  parseModuleIdentity,
  renderModuleFile,
} from '../../../../tools/codegen/build-domain-modules.mjs';

/**
 * 域模块目录的守卫。
 *
 * 🔴 这个目录是**域首页唯一的真相源**：它决定用户点开「物流中心」「CRM」这类域首页时
 * 看到的是哪些模块。目录一旦和真实页面脱节，域首页就会开始骗人 ——
 * 所以这里钉死三件事：
 *
 * 1. **生成的产物与真实文件系统同步**（加页面忘了重新生成 ⇒ CI 立刻红）；
 * 2. **每一条都真的指向一个存在的页面**（不指向幽灵路由）；
 * 3. **模块号与模块名来自页面自己的文档注释**（不是这里编出来的）。
 *
 * 重新生成：`node tools/codegen/build-domain-modules.mjs`
 */

const APP_DIR = join(process.cwd(), 'apps/web/src/app');
const GENERATED = join(process.cwd(), 'apps/web/src/lib/domain-modules.generated.ts');

/** 这七个域首页此前挂的是「域占位页」—— 一张写着「功能待实施」的静态计划表。 */
const DOMAIN_ROOTS = [
  { domain: 'pricing', prefixes: ['/pricing'] },
  { domain: 'crm', prefixes: ['/crm'] },
  { domain: 'supply', prefixes: ['/supply'] },
  { domain: 'purchase', prefixes: ['/purchase'] },
  { domain: 'logistics', prefixes: ['/logistics'] },
  { domain: 'warehouse', prefixes: ['/warehouse'] },
  { domain: 'profit', prefixes: ['/analytics/profit'] },
] as const;

describe('🔴 生成的目录必须与真实文件系统同步', () => {
  it('重新生成的结果与仓库里的文件逐字相同', async () => {
    const { domains } = collectDomainModules();
    const expected = await formatOutput(renderModuleFile(domains));
    expect(readFileSync(GENERATED, 'utf8')).toBe(expected);
  });

  it('每一条模块都真的指向一个存在的 page.tsx', () => {
    const missing: string[] = [];
    for (const list of Object.values(DOMAIN_MODULES)) {
      for (const entry of list) {
        const path = join(APP_DIR, entry.href.replace(/^\//, ''), 'page.tsx');
        if (!existsSync(path)) missing.push(entry.href);
      }
    }
    expect(missing).toEqual([]);
  });

  it('🔴 模块号与模块名必须来自页面自己的文档注释（不许编）', () => {
    for (const list of Object.values(DOMAIN_MODULES)) {
      for (const entry of list) {
        const source = readFileSync(
          join(APP_DIR, entry.href.replace(/^\//, ''), 'page.tsx'),
          'utf8',
        );
        const identity = parseModuleIdentity(source);
        expect(identity, `${entry.href} 的模块身份解析不出来`).not.toBeNull();
        expect(identity?.moduleId, `${entry.href} 的模块号对不上`).toBe(entry.moduleId);
        expect(identity?.name, `${entry.href} 的模块名对不上`).toBe(entry.name);
      }
    }
  });
});

describe('🔴 七个域首页必须是真实页面，不是占位页', () => {
  for (const { domain, prefixes } of DOMAIN_ROOTS) {
    it(`/${domain} 的页面不是 DomainPlaceholder`, () => {
      const source = readFileSync(join(APP_DIR, domain, 'page.tsx'), 'utf8');
      // 只看**代码**：文档注释里提到「此前挂的是域占位页」是历史说明，不是用法。
      const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
      expect(code, `/${domain} 不该再 import 域占位页`).not.toContain('DomainPlaceholder');
      expect(code, `/${domain} 不该再渲染 <DomainPlaceholder>`).not.toContain('<DomainPlaceholder');
      expect(code, `/${domain} 该用真实域索引`).toContain('DomainHubWorkspace');
    });

    it(`/${domain} 的域索引列得出真实模块`, () => {
      const all = Object.values(DOMAIN_MODULES).flat();
      const picked = all.filter((entry) =>
        prefixes.some((p) => entry.href === p || entry.href.startsWith(p + '/')),
      );
      expect(picked.length, `/${domain} 一条真实模块都没有 —— 域首页会是个空壳`).toBeGreaterThan(0);
    });
  }
});

describe('域索引的模块目录', () => {
  it('同一条目不重复（模块号相同时用相对路由区分开）', () => {
    for (const [domain, list] of Object.entries(DOMAIN_MODULES)) {
      const keys = list.map((m) => `${m.moduleId}|${m.variant ?? ''}`);
      expect(new Set(keys).size, `${domain} 里有重复的模块条目`).toBe(keys.length);
    }
  });

  it('🔴 同模块号的多条，区分标记必须真的不同', () => {
    for (const list of Object.values(DOMAIN_MODULES)) {
      const byModule = new Map<string, string[]>();
      for (const m of list) {
        if (!m.variant) continue;
        byModule.set(m.moduleId, [...(byModule.get(m.moduleId) ?? []), m.variant]);
      }
      for (const [moduleId, variants] of byModule) {
        expect(new Set(variants).size, `${moduleId} 的区分标记重复了`).toBe(variants.length);
      }
    }
  });

  it('每个域的模块都按模块号有序', () => {
    for (const list of Object.values(DOMAIN_MODULES)) {
      const ids = list.map((m) => m.moduleId);
      expect(ids, '模块没有按模块号排序').toEqual(
        [...ids].sort((a, b) => a.localeCompare(b, 'en')),
      );
    }
  });

  it('🔴 抽不到模块号的页面不会被编一个名字塞进来', () => {
    const { skipped } = collectDomainModules();
    const listed = new Set(
      Object.values(DOMAIN_MODULES)
        .flat()
        .map((m) => m.href),
    );
    for (const route of skipped) {
      expect(listed.has(route), `${route} 抽不到模块号却被列进了目录`).toBe(false);
    }
  });
});
