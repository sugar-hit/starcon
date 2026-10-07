import { describe, expect, it } from 'vitest';
import type { CategoryNode, CategoryTree } from '../../../../../packages/api-contracts/src/catalog';
import {
  buildTree,
  codeProblem,
  countNodes,
  descendantsOf,
  emptyHint,
  flattenNodes,
  localeNameProblem,
  maxDepth,
  moveProblem,
  nameProblem,
  namesProblem,
  nodeCounts,
  normalizeCode,
  normalizeLocale,
  platformProblem,
  ruleImpact,
  treeKindLabel,
  treeTitle,
} from './category-tree-model';

function node(id: string, overrides: Partial<CategoryNode> = {}): CategoryNode {
  return {
    id,
    treeId: 'tree-1',
    treeCode: 'INTERNAL',
    treeKind: 'INTERNAL',
    platformCode: null,
    parentId: null,
    code: id.toUpperCase(),
    name: id,
    position: 0,
    enabled: true,
    depth: 1,
    path: id,
    directProductCount: 0,
    subtreeProductCount: 0,
    secondaryProductCount: 0,
    attributeRuleCount: 0,
    specAxisRuleCount: 0,
    names: {},
    version: 0,
    createdAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}

describe('分类编码与名称', () => {
  it('编码规范化成大写并只接受 ASCII 词表', () => {
    expect(normalizeCode('  tops ')).toBe('TOPS');
    expect(codeProblem('TOPS-1')).toBeNull();
    expect(codeProblem('tops_1')).toBeNull();
    // 中文类目名放在 name 上，编码必须是机器标识。
    expect(codeProblem('上装')).toContain('英文字母');
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('A'.repeat(65))).toContain('64');
  });

  it('名称允许中文但不允许为空与超长', () => {
    expect(nameProblem('上装')).toBeNull();
    expect(nameProblem('T-Shirt')).toBeNull();
    expect(nameProblem('  ')).toContain('不能为空');
    expect(nameProblem('名'.repeat(101))).toContain('100');
  });
});

describe('树的种类与平台', () => {
  it('平台树必须带平台，内部树必须不带', () => {
    expect(platformProblem('PLATFORM', 'AMAZON')).toBeNull();
    expect(platformProblem('PLATFORM', '')).toContain('必须指明所属平台');
    expect(platformProblem('INTERNAL', '')).toBeNull();
    expect(platformProblem('INTERNAL', 'AMAZON')).toContain('不指明平台');
  });

  it('平台树的标题带上平台名，否则和内部类目长得一样', () => {
    const platform: CategoryTree = {
      id: 't1',
      code: 'AMAZON',
      name: '商品类目',
      treeKind: 'PLATFORM',
      platformCode: 'AMAZON',
      enabled: true,
      version: 0,
      createdAt: '',
      updatedAt: '',
    };
    expect(treeTitle(platform)).toBe('商品类目（AMAZON）');
    expect(treeTitle({ ...platform, treeKind: 'INTERNAL', platformCode: null })).toBe('商品类目');
    expect(treeKindLabel('PLATFORM')).toBe('平台类目映射');
    expect(treeKindLabel('INTERNAL')).toBe('内部管理分类');
  });
});

describe('多语言名', () => {
  it('locale 规范化成小写，同一个语言不重复', () => {
    expect(normalizeLocale(' ZH-CN ')).toBe('zh-cn');
    expect(localeNameProblem('zh-CN', '上装')).toBeNull();
    expect(localeNameProblem('EN', 'Tops')).toBeNull();
    expect(localeNameProblem('中文', '上装')).toContain('BCP 47');
    expect(namesProblem([{ locale: 'en-US', name: 'Tops' }])).toBeNull();
    // zh-CN 与 zh-cn 是同一个语言：写两条只留后写的那条，用户却以为配了两套翻译。
    expect(
      namesProblem([
        { locale: 'zh-CN', name: '上装' },
        { locale: 'zh-cn', name: '衬衫' },
      ]),
    ).toContain('配置了两条');
  });

  it('名称内容不能为空，超长与超量都被挡住', () => {
    expect(localeNameProblem('en-US', '  ')).toContain('不能为空');
    expect(localeNameProblem('en-US', 'x'.repeat(201))).toContain('200');
    const many = Array.from({ length: 21 }, (_, i) => ({ locale: `l${i}`, name: 'x' }));
    expect(namesProblem(many)).toContain('20');
  });
});

describe('树装配', () => {
  it('按 parentId 归组并按 position 排序', () => {
    const tree = buildTree([
      node('c', { position: 1 }),
      node('a', { position: 0 }),
      node('b', { position: 2, parentId: 'a' }),
    ]);
    expect(tree.map((b) => b.node.id)).toEqual(['a', 'c']);
    expect(tree[0]?.children.map((b) => b.node.id)).toEqual(['b']);
    expect(countNodes(tree)).toBe(3);
    expect(maxDepth(tree)).toBe(2);
    expect(flattenNodes(tree).map((n) => n.id)).toEqual(['a', 'b', 'c']);
  });

  it('parentId 指向不存在的节点时挂到根下，不静默丢弃', () => {
    const tree = buildTree([node('a'), node('b', { parentId: 'ghost' })]);
    expect(tree.map((b) => b.node.id)).toEqual(['a', 'b']);
  });

  it('空树统计为 0 层', () => {
    expect(countNodes([])).toBe(0);
    expect(maxDepth([])).toBe(0);
  });
});

describe('防环（界面先判一次）', () => {
  const tree = () =>
    buildTree([
      node('apparel'),
      node('tops', { parentId: 'apparel' }),
      node('shirt', { parentId: 'tops' }),
    ]);

  it('不能挂到自己下面', () => {
    expect(moveProblem('apparel', 'apparel', tree())).toContain('自己下面');
  });

  it('不能挂到自己的子或孙下面（与服务层 isDescendant、V14 触发器同口径）', () => {
    expect(moveProblem('apparel', 'tops', tree())).toContain('子孙节点');
    expect(moveProblem('apparel', 'shirt', tree())).toContain('子孙节点');
    expect(moveProblem('tops', 'shirt', tree())).toContain('子孙节点');
  });

  it('挂到别的分支或根层级是合法的', () => {
    const branches = buildTree([
      node('apparel'),
      node('tops', { parentId: 'apparel' }),
      node('home', { parentId: null }),
    ]);
    expect(moveProblem('tops', 'home', branches)).toBeNull();
    expect(moveProblem('tops', 'apparel', branches)).toBeNull();
    expect(moveProblem('tops', null, branches)).toBeNull();
  });

  it('落点不在当前这棵树里也要挡住', () => {
    expect(moveProblem('tops', 'ghost', tree())).toContain('不在当前这棵树里');
  });

  it('descendantsOf 不含自己', () => {
    expect(descendantsOf('apparel', tree()).map((n) => n.id)).toEqual(['tops', 'shirt']);
    expect(descendantsOf('shirt', tree())).toEqual([]);
  });
});

describe('计数语义', () => {
  it('直接商品数与子树商品数分开，商品都在子类目时给出提示', () => {
    // 1 件直接挂在服饰，2 件挂在 T恤。
    const counts = nodeCounts({ directProductCount: 1, subtreeProductCount: 3, secondaryProductCount: 0 });
    expect(counts.direct).toBe(1);
    expect(counts.subtree).toBe(3);
    expect(counts.inherited).toBe(false);

    // 本级 0 件、子树 2 件：界面上要显式说「都在子类目里」，
    // 否则「挪到子类目后这个类目的数字没变」会变成查不到的问题。
    const inherited = nodeCounts({
      directProductCount: 0,
      subtreeProductCount: 2,
      secondaryProductCount: 0,
    });
    expect(inherited.inherited).toBe(true);
  });

  it('规则影响面把属性要求与允许规格轴加起来', () => {
    expect(ruleImpact({ attributeRuleCount: 3, specAxisRuleCount: 2 })).toBe(5);
    expect(ruleImpact({ attributeRuleCount: 0, specAxisRuleCount: 0 })).toBe(0);
  });
});

describe('空态引导', () => {
  it('区分「还没建树」与「树建了但没有类目」——下一步动作不同', () => {
    expect(emptyHint(0, 'INTERNAL')).toContain('还没有内部管理分类树');
    expect(emptyHint(0, 'PLATFORM')).toContain('平台类目映射树');
    expect(emptyHint(2, 'INTERNAL')).toContain('这棵树还没有分类');
  });
});
