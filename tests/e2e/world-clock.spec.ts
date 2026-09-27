import { expect, test, type Page } from '@playwright/test';

const ROTATION_WAIT = 15_000;

function clocks(page: Page) {
  return page.getByRole('region', { name: '全球时间' });
}
function cards(page: Page) {
  return clocks(page).locator(':scope > div');
}
function rotatingCards(page: Page) {
  return clocks(page).locator(':scope > div[data-rotating="true"]');
}
function cardLabels(page: Page) {
  return cards(page).evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')));
}
test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
});

test('every clock carries a globe drawn for its own city', async ({ page }) => {
  await expect(cards(page).locator('svg[role="img"]')).toHaveCount(6);

  const globes = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const svg = card.querySelector('svg[role="img"]')!;
      const land = svg.querySelector('g[clip-path] path')!;
      const grid = svg.querySelectorAll('g[clip-path] path')[1]!;
      const box = card.getBoundingClientRect();
      const g = svg.getBoundingClientRect();
      return {
        label: card.getAttribute('aria-label'),
        title: svg.getAttribute('aria-label'),
        landPoints: (land.getAttribute('d') || '').split('M').filter(Boolean).length,
        hasGrid: (grid.getAttribute('d') || '').length > 40,
        hasPin: !!svg.querySelector('g[opacity]'),
        onRight: g.left + g.width / 2 > box.left + box.width / 2,
        onTop: g.top + g.height / 2 < box.top + box.height / 2,
        size: Math.round(g.width),
      };
    }),
  );

  for (const globe of globes) {
    expect(globe.title, globe.label).toContain('在地球上的位置');
    expect(globe.landPoints, `${globe.label} 应有大陆`).toBeGreaterThan(2);
    expect(globe.hasGrid, `${globe.label} 应有经纬网`).toBe(true);
    expect(globe.hasPin, `${globe.label} 应有定位点`).toBe(true);
    expect(globe.onRight && globe.onTop, `${globe.label} 地球应在右上角`).toBe(true);
    expect(globe.size).toBeGreaterThanOrEqual(48);
  }
});

test('each globe faces a different part of the world', async ({ page }) => {
  const outlines = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('g[clip-path] path')!.getAttribute('d')),
  );
  expect(new Set(outlines).size).toBe(6);
});

test('six fixed cities stay put and never rotate', async ({ page }) => {
  const before = await cardLabels(page);
  expect(before).toHaveLength(6);
  expect(new Set(before).size).toBe(6);
  expect(before[0]).toContain('北京');

  // 图片固定 = 城市固定：跨过原先的轮换周期也不该换城
  await page.waitForTimeout(ROTATION_WAIT + 2000);
  const after = await cardLabels(page);
  expect(after, '城市不应轮换').toEqual(before);
  // 轮换标记已下线
  await expect(rotatingCards(page)).toHaveCount(0);
});

test('each fixed city gets a distinct landmark photo', async ({ page }) => {
  await expect
    .poll(
      async () =>
        cards(page).evaluateAll(
          (els) => els.map((el) => el.querySelector('img')?.naturalWidth ?? 0).filter(Boolean).length,
        ),
      { timeout: 15_000 },
    )
    .toBe(6);

  const photos = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('span[aria-hidden="true"] img')?.getAttribute('src') ?? ''),
  );
  expect(photos.every((src) => src.length > 0), '六格都应配到照片').toBe(true);
  expect(new Set(photos).size, '六张照片应互不相同').toBe(6);
});

test('every clock colon breathes while the digits stay still', async ({ page }) => {
  const colons = clocks(page).locator('[data-clock-colon]');
  await expect(colons).toHaveCount(6);

  const first = colons.first();
  const style = await first.evaluate((el) => {
    const cs = getComputedStyle(el);
    return {
      name: cs.animationName,
      duration: Number.parseFloat(cs.animationDuration),
      iteration: cs.animationIterationCount,
      text: el.textContent,
    };
  });
  expect(style.name).toBe('clock-breathe');
  expect(style.iteration).toBe('infinite');
  expect(style.text).toBe(':');
  // 呼吸要慢：单程至少 1 秒，不能像闪烁
  expect(style.duration).toBeGreaterThanOrEqual(1);
  expect(style.duration).toBeLessThanOrEqual(4);

  const opacities = new Set<number>();
  for (let frame = 0; frame < 10; frame += 1) {
    opacities.add(
      await first.evaluate((el) => Math.round(Number(getComputedStyle(el).opacity) * 20) / 20),
    );
    await page.waitForTimeout(200);
  }
  expect(opacities.size, '冒号透明度应随时间变化').toBeGreaterThan(3);
  // 暗到头也要看得见，不能消失
  expect(Math.min(...opacities)).toBeGreaterThanOrEqual(0.35);

  const times = clocks(page).locator('time');
  expect(await times.first().evaluate((el) => getComputedStyle(el).animationName)).toBe('none');
  expect((await times.first().textContent())?.trim()).toMatch(/^\d{2}:\d{2}$/);
});

test('clock readings stay instant and unanimated', async ({ page }) => {
  // 城市固定后没有换场动效了，读数必须始终即时可读、不参与任何动画
  const names = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const nodes = [
        ['time', card.querySelector('time')],
        ['label', card.querySelector('[data-clock-label]')],
        ['meta', card.querySelector('p')],
      ] as const;
      return {
        label: card.getAttribute('aria-label'),
        anims: nodes
          .filter(([, node]) => node)
          .map(([name, node]) => [name, getComputedStyle(node!).animationName] as const),
      };
    }),
  );

  for (const card of names) {
    for (const [name, animation] of card.anims) {
      expect(animation, `${card.label} 的 ${name} 不应有动画`).toBe('none');
    }
  }
});

test('cells either show their photo backdrop or fall back to a clean card', async ({ page }) => {
  const states = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const img = card.querySelector('img');
      return {
        label: card.getAttribute('aria-label') ?? '',
        hasPhoto: card.hasAttribute('data-has-photo'),
        // 照片层走 aria-hidden 的 span 包裹 img
        imgInBackdrop: card.querySelector('span[aria-hidden="true"] img') !== null,
        imgSrc: img?.getAttribute('src') ?? null,
        region: card.getAttribute('data-region'),
      };
    }),
  );

  for (const state of states) {
    expect(state.hasPhoto, `${state.label} data-has-photo 必须存在`).toBe(true);
    if (state.hasPhoto) {
      expect(state.imgInBackdrop, `${state.label} 有照片必须渲染 img`).toBe(true);
      expect(state.imgSrc, `${state.label} img 必须有 src`).not.toBeNull();
    } else {
      expect(state.imgInBackdrop, `${state.label} 无照片不应渲染 img`).toBe(false);
    }
  }

  // 第一格是本地时区（无区域标签），其余五格各带区域
  expect(states[0]?.region, '第一格应为本地时区').toBeNull();
  for (const state of states.slice(1)) {
    expect(state.region, `${state.label} 应有区域标签`).toBeTruthy();
  }
});

test('photo-backed cells keep the time readable above the backdrop', async ({ page }) => {
  // 回归:照片层是 position:absolute,时间/日期原本是 position:static,
  // 同一 stacking context 里绝对定位永远压在静态流之上 → 时间数字被整层盖住。
  // 这里用 elementFromPoint 验证时间中心点命中的就是 <time> 本身,不是照片层。
  const checks = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const time = card.querySelector('time')!;
      const r = time.getBoundingClientRect();
      const cx = r.x + r.width / 2;
      const cy = r.y + r.height / 2;
      const hit = document.elementFromPoint(cx, cy);
      const onTop = hit === time || time.contains(hit);
      return {
        label: card.getAttribute('aria-label'),
        hasPhoto: card.hasAttribute('data-has-photo'),
        onTop,
        hitTag: hit?.tagName ?? null,
        text: time.textContent?.trim() ?? '',
      };
    }),
  );

  for (const check of checks) {
    expect(check.text, `${check.label} 应有读数`).toMatch(/^\d{2}:\d{2}$/);
    if (!check.hasPhoto) continue;
    expect(
      check.onTop,
      `${check.label} 时间被压在照片层下（命中 ${check.hitTag}），读数不可见`,
    ).toBe(true);
  }
});

test('photo-backed cells read light-on-dark against the fixed text plate', async ({ page }) => {
  // 照片亮度横跨夜景/日落/正午，没有共同规律（实测文字区亮度 0.246~0.658，
  // 北京与伦敦落在中灰区，任何阈值都判不对）。所以文字区底色改为恒定深色板，
  // 墨色固定浅色；这里断言文字确实是浅色，且背景确实被压到足够暗。
  const states = await cards(page).evaluateAll((els) =>
    els
      .filter((card) => card.hasAttribute('data-has-photo'))
      .map((card) => {
        const time = card.querySelector('time')!;
        const plate = card.querySelector('span[aria-hidden="true"] > span:last-child');
        return {
          label: card.getAttribute('aria-label'),
          timeColor: getComputedStyle(time).color,
          plate: plate ? getComputedStyle(plate).backgroundImage : '',
        };
      }),
  );

  expect(states.length, '应有照片格被采样').toBe(6);
  for (const state of states) {
    expect(state.timeColor, `${state.label} 应为浅色读数`).toBe('rgb(255, 255, 255)');
    // 深色板必须存在且顶部足够实（>= 0.7 不透明度）
    expect(state.plate, `${state.label} 应有文字区深色板`).toContain('linear-gradient');
    // 浏览器会把 rgba 序列化成带空格形式，正则需容忍
    const alphas = [...state.plate.matchAll(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/g)];
    expect(alphas.length, `${state.label} 深色板应有渐变停点`).toBeGreaterThan(0);
    const topAlpha = Number(alphas[0]?.[4] ?? '0');
    expect(topAlpha, `${state.label} 顶部遮罩应 >= 0.7`).toBeGreaterThanOrEqual(0.7);
  }
});
