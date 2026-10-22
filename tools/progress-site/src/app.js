/* starcon · 交付进度看板 —— 前端渲染 */
(function () {
  'use strict';

  var D = null;
  var state = { status: '全部', phase: '全部', q: '', open: null };

  var $ = function (id) {
    return document.getElementById(id);
  };

  var el = function (tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  };

  var esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  /* 保留 **加粗** 与 `代码` 语义，其余转义 */
  var md = function (s) {
    return esc(s)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  };

  var STATUS_CLASS = { 已交付: 'done', 进行中: 'wip', 未开发: 'todo' };

  /* ── 环形进度 ─────────────────────────────────────── */

  function ring(pct, size, stroke, color) {
    var r = (size - stroke) / 2;
    var c = 2 * Math.PI * r;
    var off = c * (1 - pct / 100);
    var sw = color || 'var(--brand)';
    return (
      '<svg viewBox="0 0 ' +
      size +
      ' ' +
      size +
      '" role="img" aria-label="完成率 ' +
      pct +
      '%">' +
      '<circle cx="' +
      size / 2 +
      '" cy="' +
      size / 2 +
      '" r="' +
      r +
      '" fill="none" stroke="var(--idle-bg)" stroke-width="' +
      stroke +
      '"/>' +
      '<circle cx="' +
      size / 2 +
      '" cy="' +
      size / 2 +
      '" r="' +
      r +
      '" fill="none" stroke="' +
      sw +
      '" stroke-width="' +
      stroke +
      '" stroke-linecap="round" stroke-dasharray="' +
      c.toFixed(2) +
      '" stroke-dashoffset="' +
      off.toFixed(2) +
      '"/>' +
      '</svg>'
    );
  }

  /* ── 渲染：总览 ───────────────────────────────────── */

  function renderHero() {
    var s = D.summary;
    var e = D.engineering;

    $('hero-facts').innerHTML = [
      ['模块总数', s.total],
      ['业务系统', s.systemCount],
      ['二级功能', s.featureCount],
      ['Flyway 迁移', e.totalMigrations],
    ]
      .map(function (f) {
        return '<span class="fact">' + esc(f[0]) + ' <b>' + f[1] + '</b></span>';
      })
      .join('');

    $('ring').innerHTML =
      ring(s.rate, 196, 16) +
      '<div class="ring__center"><div class="ring__pct">' +
      s.rate +
      '%</div><div class="ring__label">' +
      s.delivered +
      ' / ' +
      s.total +
      ' 模块</div></div>';

    $('rate-label').textContent = s.delivered + ' / ' + s.total + ' 个 L4 模块已交付';

    var kpis = [
      {
        label: '已交付模块',
        value: s.delivered,
        sub: '迁移 + API + 页面 + 测试四件齐全',
        color: 'var(--ok)',
      },
      {
        label: '进行中',
        value: s.inProgress,
        sub: s.inProgress ? '部分交付、未收口' : '当前无半成品状态',
        color: 'var(--warn)',
      },
      {
        label: '未开发',
        value: s.notStarted,
        sub: '占总量 ' + Math.round((s.notStarted / s.total) * 100) + '%',
        color: 'var(--idle)',
      },
      {
        label: '已覆盖系统',
        value: systemsWithProgress() + ' / ' + s.systemCount,
        sub: '有模块交付的业务系统数',
        color: 'var(--brand)',
      },
    ];

    $('kpis').innerHTML = kpis
      .map(function (k) {
        return (
          '<div class="kpi"><div class="kpi__label"><span class="swatch" style="background:' +
          k.color +
          '"></span>' +
          esc(k.label) +
          '</div><div class="kpi__value">' +
          k.value +
          '</div><div class="kpi__sub">' +
          esc(k.sub) +
          '</div></div>'
        );
      })
      .join('');
  }

  function systemsWithProgress() {
    return D.systems.filter(function (s) {
      return s.delivered > 0;
    }).length;
  }

  /* ── 渲染：阶段 ───────────────────────────────────── */

  function renderPhases() {
    $('phase-grid').innerHTML = D.phases
      .map(function (p) {
        var pct = p.total ? Math.round((p.delivered / p.total) * 1000) / 10 : 0;
        var fillClass = p.inProgress ? 'bar__fill--warn' : 'bar__fill--ok';
        return (
          '<div class="phase"><div class="phase__top"><span class="phase__name">' +
          esc(p.phase) +
          '</span><span class="phase__rate">' +
          pct +
          '%</span></div><div class="bar"><div class="bar__fill ' +
          fillClass +
          '" style="width:' +
          pct +
          '%"></div></div><div class="phase__nums">已交付 ' +
          p.delivered +
          ' · 进行中 ' +
          p.inProgress +
          ' · 未开发 ' +
          p.notStarted +
          ' · 共 ' +
          p.total +
          ' 个模块</div></div>'
        );
      })
      .join('');

    $('phase-notes').innerHTML = D.phaseNotes
      .map(function (n) {
        return '<div class="note"><b>' + esc(n.subject) + '</b>：' + esc(n.note) + '</div>';
      })
      .join('');
  }

  /* ── 筛选 ─────────────────────────────────────────── */

  function buildFilters() {
    var counts = { 全部: 0, 已交付: 0, 进行中: 0, 未开发: 0 };
    D.systems.forEach(function (s) {
      s.modules.forEach(function (m) {
        counts.全部++;
        counts[m.status] = (counts[m.status] || 0) + 1;
      });
    });

    $('filters').innerHTML = ['全部', '已交付', '进行中', '未开发']
      .map(function (k) {
        return (
          '<button class="chip" type="button" data-status="' +
          esc(k) +
          '" aria-pressed="' +
          (state.status === k) +
          '">' +
          esc(k) +
          '<span class="chip__count">' +
          (counts[k] || 0) +
          '</span></button>'
        );
      })
      .join('');

    var ph = ['全部'].concat(
      D.phases.map(function (p) {
        return p.phase;
      }),
    );
    $('phase-filters').innerHTML = ph
      .map(function (k) {
        return (
          '<button class="chip chip--phase" type="button" data-phase="' +
          esc(k) +
          '" aria-pressed="' +
          (state.phase === k) +
          '">' +
          esc(k) +
          '</button>'
        );
      })
      .join('');

    $('filters').addEventListener('click', function (e) {
      var b = e.target.closest('[data-status]');
      if (!b) return;
      state.status = b.dataset.status;
      syncChips();
      renderSystems();
    });

    $('phase-filters').addEventListener('click', function (e) {
      var b = e.target.closest('[data-phase]');
      if (!b) return;
      state.phase = b.dataset.phase;
      syncChips();
      renderSystems();
    });

    var timer = null;
    $('search').addEventListener('input', function (e) {
      clearTimeout(timer);
      var v = e.target.value.trim();
      timer = setTimeout(function () {
        state.q = v;
        renderSystems();
      }, 120);
    });
  }

  function syncChips() {
    Array.prototype.forEach.call($('filters').children, function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.status === state.status));
    });
    Array.prototype.forEach.call($('phase-filters').children, function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.phase === state.phase));
    });
  }

  function match(m) {
    if (state.status !== '全部' && m.status !== state.status) return false;
    if (state.phase !== '全部' && m.phase !== state.phase) return false;
    if (!state.q) return true;
    var hay = [m.id, m.name, m.duty, m.input, m.output, m.relation, m.route || '']
      .join(' ')
      .toLowerCase();
    return hay.indexOf(state.q.toLowerCase()) !== -1;
  }

  /* ── 渲染：系统卡片 ───────────────────────────────── */

  function renderSystems() {
    var grid = $('system-grid');
    grid.innerHTML = '';
    var totalMatched = 0;
    var shown = 0;

    D.systems.forEach(function (sys) {
      var mods = sys.modules.filter(match);
      if (!mods.length) return;
      shown++;
      totalMatched += mods.length;

      var delivered = mods.filter(function (m) {
        return m.status === '已交付';
      }).length;
      var pct = Math.round((delivered / mods.length) * 1000) / 10;

      var card = el('button', 'scard');
      card.type = 'button';
      card.setAttribute('aria-label', '查看 ' + sys.name + ' 详情');
      card.innerHTML =
        '<div class="scard__head"><span class="scard__num">' +
        esc(sys.id) +
        '</span><div><div class="scard__title">' +
        esc(sys.name) +
        '</div><div class="scard__tier">' +
        esc(sys.tier) +
        '</div></div><div class="scard__ring">' +
        ring(pct, 50, 5, pct >= 50 ? 'var(--ok)' : 'var(--brand)') +
        '<span>' +
        (pct === 0 ? '0' : pct) +
        '%</span></div></div>' +
        '<p class="scard__duty">' +
        esc(sys.duty) +
        '</p>' +
        '<div class="scard__foot"><span class="tag tag--phase">' +
        esc(sys.phases.join(' · ')) +
        '</span><span class="tag tag--svc">' +
        esc(sys.service) +
        '</span><span class="scard__count">' +
        delivered +
        ' / ' +
        mods.length +
        '</span></div>';

      card.addEventListener('click', function () {
        openDrawer(sys.id);
      });
      grid.appendChild(card);
    });

    $('empty').hidden = shown > 0;
    $('filter-note').innerHTML = totalMatched
      ? '当前筛选命中 <b>' +
        totalMatched +
        '</b> 个模块，分布在 <b>' +
        shown +
        '</b> 个系统。系统卡片上的百分比按筛选后的模块计算。'
      : '当前筛选没有命中任何模块。';
  }

  /* ── 抽屉：系统详情 ───────────────────────────────── */

  function openDrawer(sysId) {
    var sys = D.systems.find(function (s) {
      return s.id === sysId;
    });
    if (!sys) return;

    state.open = sysId;
    $('drawer-eyebrow').innerHTML =
      '<span>卷 ' +
      esc(sys.id) +
      '</span><span class="tag tag--phase">' +
      esc(sys.phases.join(' · ')) +
      '</span><span class="tag tag--todo">' +
      esc(sys.tier) +
      '</span>';
    $('drawer-title').textContent = sys.name;
    $('drawer-duty').innerHTML = md(sys.duty);

    var body = $('drawer-body');
    body.innerHTML = '';

    /* 元信息 */
    var meta = el('div', 'dsec');
    meta.innerHTML =
      '<div class="dsec__title">这个系统是干什么的</div>' +
      '<div class="dbox">' +
      (sys.positioning ? '<p><b>定位</b>：' + md(sys.positioning) + '</p>' : '') +
      (sys.ownership ? '<p><b>后端归属</b>：' + md(sys.ownership) + '</p>' : '') +
      (sys.entry
        ? '<p><b>前端入口</b>：<code>' + esc(sys.entry.replace(/`/g, '')) + '</code></p>'
        : '') +
      (sys.does ? '<p><b>做</b>：' + md(sys.does) + '</p>' : '') +
      (sys.notDoes ? '<p><b>不做</b>：' + md(sys.notDoes) + '</p>' : '') +
      '</div>';
    body.appendChild(meta);

    var grid = el('div', 'dgrid');
    grid.style.marginBottom = '22px';
    grid.innerHTML =
      '<div class="dmeta"><div class="dmeta__k">主责服务</div><div class="dmeta__v">' +
      esc(sys.service) +
      '</div></div>' +
      '<div class="dmeta"><div class="dmeta__k">交付阶段</div><div class="dmeta__v">' +
      esc(sys.phases.join(' · ')) +
      '</div></div>' +
      '<div class="dmeta"><div class="dmeta__k">模块总数</div><div class="dmeta__v">' +
      sys.total +
      ' 个</div></div>' +
      '<div class="dmeta"><div class="dmeta__k">已交付</div><div class="dmeta__v">' +
      sys.delivered +
      ' 个（' +
      sys.rate +
      '%）</div></div>';
    body.appendChild(grid);

    /* L2 功能 */
    if (sys.features.length) {
      var fs = el('div', 'dsec');
      fs.innerHTML = '<div class="dsec__title">二级功能职责</div>';
      var featWrap = el('div');
      sys.features.forEach(function (f) {
        var mods = sys.modules.filter(function (m) {
          return m.l2 === f.id;
        });
        var dl = mods.filter(function (m) {
          return m.status === '已交付';
        }).length;
        var block = el('div', 'l2');
        block.innerHTML =
          '<div class="l2__head"><span class="l2__id">' +
          esc(f.id) +
          '</span><span class="l2__name">' +
          esc(f.name) +
          '</span><span class="l2__svc">' +
          esc(f.service) +
          ' · ' +
          dl +
          '/' +
          mods.length +
          '</span></div>' +
          (f.duty ? '<div class="l2__duty">' + md(f.duty) + '</div>' : '');
        block.appendChild(moduleList(mods));
        featWrap.appendChild(block);
      });
      fs.appendChild(featWrap);
      body.appendChild(fs);
    }

    /* 模块清单 */
    var ms = el('div', 'dsec');
    ms.innerHTML = '<div class="dsec__title">模块清单与进展</div>';
    ms.appendChild(moduleList(sys.modules, true));
    body.appendChild(ms);

    $('scrim').hidden = false;
    $('drawer').hidden = false;
    document.body.style.overflow = 'hidden';
    $('drawer').scrollTop = 0;
    $('drawer-body').scrollTop = 0;
    $('drawer-close').focus();
  }

  function moduleList(mods, showL3) {
    var wrap = el('div');
    if (!mods.length) {
      wrap.innerHTML = '<div class="dbox">该筛选下没有模块。</div>';
      return wrap;
    }

    var lastL3 = null;
    mods.forEach(function (m) {
      if (showL3 && m.l3 !== lastL3) {
        lastL3 = m.l3;
        var h = el('div', 'l2__head');
        h.style.borderTop = '1px solid var(--line)';
        h.innerHTML =
          '<span class="l2__id">' +
          esc(m.l3) +
          '</span><span class="l2__name">' +
          esc(m.l3Name || '') +
          '</span>';
        wrap.appendChild(h);
      }

      var row = el('div', 'mod');
      row.dataset.open = 'false';
      var routeChip = m.route ? '<span class="mod__route">' + esc(m.route) + '</span>' : '';
      row.innerHTML =
        '<button class="mod__btn" type="button"><span class="mod__id">' +
        esc(m.id) +
        '</span><span class="mod__name"><span>' +
        esc(m.name) +
        '</span>' +
        routeChip +
        '</span><span class="mod__right"><span class="tag tag--' +
        (STATUS_CLASS[m.status] || 'todo') +
        '">' +
        esc(m.status) +
        '</span><span class="tag tag--phase">' +
        esc(m.phase) +
        '</span><svg class="mod__caret" viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span></button>' +
        '<div class="mod__panel"><dl>' +
        (m.duty ? '<div><dt>职责</dt><dd>' + md(m.duty) + '</dd></div>' : '') +
        (m.input ? '<div><dt>输入</dt><dd>' + md(m.input) + '</dd></div>' : '') +
        (m.output ? '<div><dt>输出</dt><dd>' + md(m.output) + '</dd></div>' : '') +
        (m.relation ? '<div><dt>关系</dt><dd>' + md(m.relation) + '</dd></div>' : '') +
        '</dl>' +
        (m.evidence
          ? '<div class="evid"><span class="evid__k">交付证据</span>' + md(m.evidence) + '</div>'
          : '') +
        '</div>';

      row.querySelector('.mod__btn').addEventListener('click', function () {
        row.dataset.open = row.dataset.open === 'true' ? 'false' : 'true';
      });
      wrap.appendChild(row);
    });
    return wrap;
  }

  function closeDrawer() {
    $('scrim').hidden = true;
    $('drawer').hidden = true;
    document.body.style.overflow = '';
    state.open = null;
  }

  /* ── 渲染：工程现状 ───────────────────────────────── */

  function renderEngineering() {
    var e = D.engineering;
    var svcNames = {
      catalog: 'catalog 商品',
      channel: 'channel 渠道',
      trade: 'trade 交易',
      warehouse: 'warehouse 仓储',
      identity: 'identity 组织',
      crm: 'crm 客户',
    };

    var migRows = Object.keys(e.migrations)
      .sort(function (a, b) {
        return e.migrations[b] - e.migrations[a];
      })
      .map(function (k) {
        var max = Math.max.apply(null, Object.values(e.migrations));
        return (
          '<div class="eng__row"><span>' +
          esc(svcNames[k] || k) +
          '</span><div class="bar"><div class="bar__fill" style="width:' +
          Math.round((e.migrations[k] / max) * 100) +
          '%"></div></div><b>' +
          e.migrations[k] +
          '</b></div>'
        );
      })
      .join('');

    var testRows = Object.keys(e.backendTestClasses)
      .sort(function (a, b) {
        return e.backendTestClasses[b] - e.backendTestClasses[a];
      })
      .map(function (k) {
        var max = Math.max.apply(null, Object.values(e.backendTestClasses));
        return (
          '<div class="eng__row"><span>' +
          esc(svcNames[k] || k) +
          '</span><div class="bar"><div class="bar__fill bar__fill--ok" style="width:' +
          Math.round((e.backendTestClasses[k] / max) * 100) +
          '%"></div></div><b>' +
          e.backendTestClasses[k] +
          '</b></div>'
        );
      })
      .join('');

    $('eng-grid').innerHTML = [
      {
        title: 'Flyway 数据库迁移',
        big: e.totalMigrations,
        sub: '每个迁移都是真实表结构，带 tenant_id NOT NULL + RLS 策略',
        rows: migRows,
      },
      {
        title: '后端测试类',
        big: e.totalBackendTests,
        sub: 'JUnit 5 + AssertJ，集成测试实跑租户隔离三断言',
        rows: testRows,
      },
      {
        title: '前端页面工作区',
        big: e.webPages + e.adminPages,
        sub:
          'apps/web ' +
          e.webPages +
          ' 个 · apps/admin ' +
          e.adminPages +
          ' 个' +
          (e.adminPages ? '' : '（仅脚手架）'),
        rows:
          '<div class="eng__row"><span>web</span><div class="bar"><div class="bar__fill" style="width:100%"></div></div><b>' +
          e.webPages +
          '</b></div>' +
          '<div class="eng__row"><span>admin</span><div class="bar"><div class="bar__fill" style="width:' +
          (e.adminPages ? Math.round((e.adminPages / e.webPages) * 100) : 0) +
          '%"></div></div><b>' +
          e.adminPages +
          '</b></div>',
      },
      {
        title: '前端测试文件',
        big: e.frontendTestFiles,
        sub: 'Vitest 单测文件数（非用例数），与后端同批合入',
        rows:
          '<div class="eng__row"><span>vitest</span><div class="bar"><div class="bar__fill bar__fill--ok" style="width:100%"></div></div><b>' +
          e.frontendTestFiles +
          '</b></div>',
      },
    ]
      .map(function (c) {
        return (
          '<div class="eng"><div class="eng__title">' +
          esc(c.title) +
          '</div><div class="eng__big">' +
          c.big +
          '</div><div class="eng__sub">' +
          esc(c.sub) +
          '</div><div class="eng__rows">' +
          c.rows +
          '</div></div>'
        );
      })
      .join('');
  }

  /* ── 渲染：判定口径 ───────────────────────────────── */

  function renderCriteria() {
    var items = [
      [
        '迁移',
        'backend/services/&lt;svc&gt;/src/main/resources/db/migration/V*.sql 里有真实表结构，带 tenant_id NOT NULL + RLS 策略，不是注释里的设计。',
      ],
      ['API', '服务里有对应端点，走 jOOQ 显式 SQL，不是返回硬编码常量的桩。'],
      ['页面', 'apps/web/src/app/**/page.tsx 是真实工作区，不是 DomainPlaceholder。'],
      [
        '测试',
        'cd backend && ./mvnw verify 绿；涉及租户隔离的必须实跑「两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户」三条断言。',
      ],
    ];
    $('criteria-grid').innerHTML = items
      .map(function (it, i) {
        return (
          '<div class="crit"><div class="crit__n">条件 ' +
          (i + 1) +
          '</div><div class="crit__title">' +
          esc(it[0]) +
          '</div><div class="crit__text">' +
          it[1] +
          '</div></div>'
        );
      })
      .join('');

    var anti = [
      'DomainPlaceholder 占位页',
      '硬编码数组 / 常量当数据源',
      'TODO + UnsupportedOperationException',
      '迁移存在但没有 RLS 策略',
      '前端有页面但后端无端点',
    ];
    $('anti-patterns').innerHTML =
      '<div class="anti__title">见到这些形态，说明没做完，不许算进已交付</div><div class="anti__list">' +
      anti
        .map(function (a) {
          return '<span class="anti__item">' + esc(a) + '</span>';
        })
        .join('') +
      '</div>';
  }

  /* ── 渲染：footer ─────────────────────────────────── */

  function renderFooter() {
    var sources = [
      ['docs/product/capability-blueprint/module-progress.md', ' — 465 个模块的交付状态台账'],
      [
        'docs/product/capability-blueprint/0N-*.md',
        ' — 18 卷蓝图，每个 L4 模块的职责/输入/输出/关系',
      ],
      ['docs/product/capability-blueprint/README.md', ' — 18 个系统的定位、层级与主责服务'],
      ['backend/ · apps/', ' — 迁移、测试类、页面工作区的真实计数'],
    ];
    $('sources').innerHTML = sources
      .map(function (s) {
        return '<li>' + esc(s[0]) + '<span>' + esc(s[1]) + '</span></li>';
      })
      .join('');

    var t = new Date(D.generatedAt);
    var pad = function (n) {
      return String(n).padStart(2, '0');
    };
    var stamp =
      t.getFullYear() +
      '-' +
      pad(t.getMonth() + 1) +
      '-' +
      pad(t.getDate()) +
      ' ' +
      pad(t.getHours()) +
      ':' +
      pad(t.getMinutes());
    $('generated-at').textContent = stamp;
    $('footer-gen').textContent =
      '生成于 ' +
      stamp +
      ' · 数据来源 ' +
      (D.sourceRef || 'working-tree') +
      ' · node tools/progress-site/build.mjs';
  }

  /* ── 启动 ─────────────────────────────────────────── */

  function boot(d) {
    D = d;
    document.title =
      'starcon · 交付进度看板（' +
      d.summary.rate +
      '% · ' +
      d.summary.delivered +
      '/' +
      d.summary.total +
      '）';
    renderHero();
    renderPhases();
    buildFilters();
    renderSystems();
    renderEngineering();
    renderCriteria();
    renderFooter();

    $('drawer-close').addEventListener('click', closeDrawer);
    $('scrim').addEventListener('click', closeDrawer);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && state.open) closeDrawer();
    });
  }

  fetch('data.json')
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    })
    .then(boot)
    .catch(function (err) {
      document.body.innerHTML =
        '<div style="padding:80px 24px;text-align:center;font-family:system-ui">' +
        '<h1 style="font-size:20px;margin-bottom:10px">数据加载失败</h1>' +
        '<p style="color:#71717a;font-size:14px">' +
        esc(err.message) +
        '</p></div>';
    });
})();
