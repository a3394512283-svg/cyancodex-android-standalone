/* CyanCodex 官网交互: 主题切换 / 标签页 / 动态版本 / 滚动动画 */
(function () {
  'use strict';

  // 服务器接口 (与 App 同一个后端)
  var API_BASE = 'https://app.blueworkshop.top';

  /* ---------- 主题 ---------- */
  var html = document.documentElement;
  function applyTheme(t) {
    html.setAttribute('data-theme', t);
    try { localStorage.setItem('cyx_theme', t); } catch (e) {}
    var ico = document.getElementById('theme-ico');
    if (ico) {
      ico.setAttribute('d', t === 'light'
        ? 'M12 3v2m0 14v2M5.6 5.6l1.4 1.4m10 10l1.4 1.4M3 12h2m14 0h2M5.6 18.4l1.4-1.4m10-10l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z'
        : 'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z');
    }
  }
  var saved = null;
  try { saved = localStorage.getItem('cyx_theme'); } catch (e) {}
  var prefersLight = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
  applyTheme(saved || (prefersLight ? 'light' : 'dark'));
  var tb = document.getElementById('theme-btn');
  if (tb) tb.onclick = function () {
    applyTheme(html.getAttribute('data-theme') === 'light' ? 'dark' : 'light');
  };

  /* ---------- 移动端菜单 ---------- */
  var menuBtn = document.getElementById('menu-btn');
  var links = document.getElementById('nav-links');
  if (menuBtn && links) {
    menuBtn.onclick = function () { links.classList.toggle('open'); };
    links.addEventListener('click', function (e) {
      if (e.target.tagName === 'A') links.classList.remove('open');
    });
  }

  /* ---------- 导航滚动效果 + 回到顶部 ---------- */
  var nav = document.getElementById('nav');
  var backTop = document.getElementById('back-top');
  function onScroll() {
    var y = window.scrollY || document.documentElement.scrollTop;
    if (nav) nav.classList.toggle('scrolled', y > 10);
    if (backTop) backTop.classList.toggle('on', y > 600);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---------- 生成器标签页 ---------- */
  var tabBar = document.getElementById('gen-tabs');
  if (tabBar) {
    tabBar.addEventListener('click', function (e) {
      var btn = e.target.closest('.tab');
      if (!btn) return;
      var key = btn.dataset.tab;
      tabBar.querySelectorAll('.tab').forEach(function (b) { b.classList.toggle('on', b === btn); });
      document.querySelectorAll('.tab-panels .panel').forEach(function (p) {
        p.classList.toggle('on', p.dataset.panel === key);
      });
    });
  }

  /* ---------- 滚动出现动画 ---------- */
  var items = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });
    items.forEach(function (el, i) {
      el.style.transitionDelay = (Math.min(i % 6, 5) * 45) + 'ms';
      io.observe(el);
    });
  } else {
    items.forEach(function (el) { el.classList.add('in'); });
  }

  /* ---------- 动态获取最新版本 (下载按钮 + 首屏提示) ---------- */
  function fmtSize(bytes) {
    if (!bytes) return '';
    return (bytes / 1048576).toFixed(2) + ' MB';
  }
  fetch(API_BASE + '/api/update/latest', { cache: 'no-store' })
    .then(function (r) { return r.json(); })
    .then(function (j) {
      var d = j && j.data;
      if (!d) throw new Error('no data');
      var url = API_BASE + d.url;
      var ver = 'v' + d.version;
      var size = fmtSize(d.size);
      var a = document.getElementById('dl-android');
      if (a) a.href = url;
      var heroA = document.getElementById('hero-download');
      if (heroA) heroA.setAttribute('href', url);
      var navA = document.getElementById('nav-download');
      if (navA) navA.setAttribute('href', url);
      var v = document.getElementById('dl-ver');
      if (v) v.textContent = '最新版 ' + ver + (size ? ' · ' + size : '');
      var meta = document.getElementById('hero-meta');
      if (meta) meta.textContent = '最新版本 ' + ver + (size ? ' · ' + size : '') + ' · 支持 Android 8.0 及以上';
    })
    .catch(function () {
      var v = document.getElementById('dl-ver');
      if (v) v.textContent = '前往下载最新版';
      var meta = document.getElementById('hero-meta');
      if (meta) meta.textContent = 'Android 版 · 基岩版指令助手';
      var a = document.getElementById('dl-android');
      if (a) a.href = API_BASE + '/downloads/';
    });

  /* ---------- 页脚年份 ---------- */
  var icp = document.getElementById('foot-icp');
  if (icp) icp.textContent = '';
})();
