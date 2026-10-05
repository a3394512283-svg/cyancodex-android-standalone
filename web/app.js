// CyanCodex Web - 主逻辑 (全功能, 与 App 数据互通)
const $ = id => document.getElementById(id);
function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
function escAttr(s) { return esc(s).replace(/'/g, '&#39;'); }

// ============ 主题 (深色/浅色/跟随系统) ============
const THEME_MODES = ['system', 'dark', 'light'];
const SUN_ICON = 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0-13V2m0 20v-2M4.9 4.9L3.5 3.5m17 17l-1.4-1.4M2 12h2m16 0h2M4.9 19.1l-1.4 1.4m17-17l-1.4 1.4';
const MOON_ICON = 'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z';
const MONITOR_ICON = 'M2 4h20v13H2zM8 21h8m-4-4v4';
const THEME_LABELS = { system: '跟随系统', dark: '深色', light: '浅色' };
function applyTheme(mode) {
  const root = document.documentElement;
  if (mode === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', mode);
  localStorage.setItem('cc_theme', mode);
  updateThemeIcons(mode);
  // 主题切换动画: 全页淡入 (仅用户手动切换时触发)
  const b = document.body;
  b.classList.remove('theme-transition');
  void b.offsetWidth; // 强制重排以重启动画
  b.classList.add('theme-transition');
}
function updateThemeIcons(mode) {
  // 当前模式图标: 深色=月亮, 浅色=太阳, 跟随系统=电脑
  const icon = mode === 'dark' ? MOON_ICON : (mode === 'light' ? SUN_ICON : MONITOR_ICON);
  ['theme-ico-login', 'theme-ico-main'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.setAttribute('d', icon);
  });
}
function cycleTheme() {
  const cur = localStorage.getItem('cc_theme') || 'system';
  const idx = THEME_MODES.indexOf(cur);
  const next = THEME_MODES[(idx + 1) % THEME_MODES.length];
  applyTheme(next);
  toast('主题: ' + THEME_LABELS[next]);
}
// 系统主题变化时跟随
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  const mode = localStorage.getItem('cc_theme') || 'system';
  if (mode === 'system') updateThemeIcons('system');
});

function toast(msg) {
  const t = $('toast');
  t.textContent = msg; t.style.display = 'block';
  clearTimeout(t._h);
  t._h = setTimeout(() => { t.style.display = 'none'; }, 2200);
}
function copyText(text) {
  if (navigator.clipboard) { navigator.clipboard.writeText(text).then(() => toast('已复制')); }
  else { const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast('已复制'); }
}
function fmtTime(t) {
  if (!t) return '';
  const d = new Date(t); if (isNaN(d)) return String(t).slice(5, 16);
  const p = n => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function initials(name) { return (name || '?').slice(0, 1).toUpperCase(); }
function avatarHtml(u) {
  const av = u && u.avatar;
  // 服务器路径 (/uploads/...) 显示图片; 否则 (内置图标名/空) 显示首字母
  if (av && av.startsWith('/')) return `<img src="${escAttr(av)}" onerror="this.style.display='none'">`;
  return esc(initials(u && (u.nickname || u.username) || '?'));
}
function isOfficial(u) { return u && (u.official || u.official === 1 || u.user_official === 1 || (u.role === 'official')); }

// ============ 功能图标 (Material Icons 同 App) ============
const MI = {
  book: 'M18 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM6 4h5v8l-2.5-1.5L6 12V4z',
  edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  chart: 'M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zm5.6 8H19v6h-2.8v-6z',
  swap: 'M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z',
  campaign: 'M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4.53-.8 1.07-1.2 1.6.96.72 2.21 1.66 3.2 2.4.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z',
  music: 'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z',
  code: 'M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z',
  robot: 'M22 9h-8V4a2 2 0 0 0-4 0v5H2v4h2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6h2V9zm-11 7H7v-2h4v2zm6 0h-4v-2h4v2zm1-7H6v-1h12v1z',
  casino: 'M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zM7.5 18a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5zm0-9a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5zm4.5 4.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5zM16.5 18a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5zm0-9a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z',
  forum: 'M21 6h-2v9H6v2c0 .55.45 1 1 1h11l4 4V7c0-.55-.45-1-1-1zm-4 6V3c0-.55-.45-1-1-1H3c-.55 0-1 .45-1 1v14l4-4h10c.55 0 1-.45 1-1z',
  person: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  feedback: 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 4c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm1 4h-2v6h2v-6z',
};
function mi(name, size) {
  const s = size || 20;
  return `<svg viewBox="0 0 24 24" width="${s}" height="${s}" fill="currentColor" style="display:block"><path d="${MI[name] || MI.book}"/></svg>`;
}

// ============ 模态框 ============
function openModal(title, bodyHtml, actionsHtml) {
  const m = $('modal') || document.createElement('div');
  m.id = 'modal';
  m.className = 'modal';
  m.innerHTML = `<h2>${esc(title)}</h2>${bodyHtml}<div class="modal-actions">${actionsHtml || ''}<button class="btn" onclick="closeModal()">关闭</button></div>`;
  let mask = $('modal-mask');
  if (!mask) { mask = document.createElement('div'); mask.id = 'modal-mask'; mask.className = 'modal-mask'; document.body.appendChild(mask); }
  mask.innerHTML = '';
  mask.appendChild(m);
  mask.classList.add('show');
}
function closeModal() { const mask = $('modal-mask'); if (mask) mask.classList.remove('show'); }
document.addEventListener('click', e => { if (e.target && e.target.id === 'modal-mask') closeModal(); });

// ============ 登录/注册 ============
function showLoginTab(tab) {
  document.querySelectorAll('.auth-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  $('form-login').style.display = tab === 'login' ? 'block' : 'none';
  $('form-register').style.display = tab === 'register' ? 'block' : 'none';
}
async function loadCaptcha() {
  const r = await API.captcha();
  if (r.ok && r.data) {
    $('reg-captcha-img').src = r.data.image || r.data;
    window._captchaId = r.data.id;
  }
}
async function sendEmailCode() {
  const email = $('reg-email').value.trim();
  if (!email) { toast('请先填写邮箱'); return; }
  const btn = $('btn-send-email'); btn.disabled = true;
  const r = await API.sendEmailCode(email);
  if (r.ok) { toast('验证码已发送'); let n = 60; btn.textContent = n + 's';
    const iv = setInterval(() => { n--; btn.textContent = n + 's'; if (n <= 0) { clearInterval(iv); btn.disabled = false; btn.textContent = '发送验证码'; } }, 1000);
  } else { toast(r.error || '发送失败'); btn.disabled = false; }
}
async function doLogin() {
  const ident = $('login-ident').value.trim(), pass = $('login-pass').value;
  if (!ident || !pass) { $('login-err').textContent = '请输入用户名和密码'; return; }
  $('login-err').textContent = '';
  const r = await API.login(ident, pass);
  if (r.ok && r.data) {
    API.setLogin(r.data.token, r.data.user);
    enterMain();
  } else { $('login-err').textContent = r.error || '登录失败'; }
}
async function doRegister() {
  const body = {
    username: $('reg-user').value.trim(), nickname: $('reg-nick').value.trim(),
    email: $('reg-email').value.trim(), password: $('reg-pass').value,
    captcha_id: window._captchaId, captcha_text: $('reg-captcha').value.trim(),
    email_code: $('reg-emailcode').value.trim(),
  };
  $('reg-err').textContent = '';
  const r = await API.register(body);
  if (r.ok && r.data) { API.setLogin(r.data.token, r.data.user); enterMain(); }
  else { $('reg-err').textContent = r.error || '注册失败'; loadCaptcha(); }
}
document.querySelectorAll('.auth-tab').forEach(b => b.addEventListener('click', () => showLoginTab(b.dataset.tab)));

// ============ 路由 ============
let currentPage = 'home';
let chatTimer = null, chatLastId = 0, chatList = [];
let aiTimer = null, aiList = [];
let gomokuTimer = null;

function enterMain() {
  $('view-login').style.display = 'none';
  $('view-main').style.display = 'block';
  refreshUserBadge();
  showPage('home');
}
function refreshUserBadge() {
  const u = API.user;
  $('points-badge').textContent = (u && u.points != null ? u.points : 0) + ' 积分';
  const av = $('top-avatar'); av.innerHTML = avatarHtml(u);
}
function showPage(page) {
  if (page !== 'chat') { stopChat(); }
  if (page !== 'ai') { stopAi(); }
  if (page !== 'gomoku') { stopGomoku(); }
  if (page !== 'dm' && window._stopDm) { window._stopDm(); window._stopDm = null; }
  currentPage = page;
  document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.page === page));
  const mc = $('main-content');
  mc.scrollTop = 0; window.scrollTo(0, 0);
  const pages = {
    home: renderHome, library: renderLibrary, chat: renderChat,
    tools: renderTools, more: renderMore, messages: renderMessages,
    profile: renderProfile, about: renderAbout, ai: renderAi,
    gomoku: renderGomoku, tscoreboard: renderTScoreboard, tenumerate: renderTEnumerate,
    tscroller: renderTScroller, tmusic: renderTMusic, ttitleraw: renderTTitleraw,
    friends: renderFriends, dm: renderDm, userpage: renderUserPage, mycommands: renderMyCommands,
    feedback: renderFeedback, upload: renderUpload, pointslog: renderPointsLog,
  };
  const fn = pages[page] || renderHome;
  fn(mc);
  // 页面切换动画: 淡入 + 轻微上移
  mc.classList.remove('page-in');
  void mc.offsetWidth;
  mc.classList.add('page-in');
}

// ============ 首页 ============
async function renderHome(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const [annR, meR] = await Promise.all([API.announcements('all'), API.me().catch(() => ({ ok: false }))]);
  if (meR.ok && meR.data) { API.setUser(meR.data.user || meR.data); refreshUserBadge(); }
  const anns = (annR.ok && annR.data) ? (Array.isArray(annR.data) ? annR.data : (annR.data.list || [])) : [];

  const modules = [
    { name: '指令库', desc: '平台+用户上传', color: '#4ADE80', page: 'library', icon: 'book' },
    { name: '指令编辑器', desc: '快捷编辑指令', color: '#38BDF8', page: 'upload', icon: 'edit' },
    { name: 'T显生成器', desc: '计分板/T显示', color: '#FBBF24', page: 'tscoreboard', icon: 'chart' },
    { name: '穷举', desc: '批量生成指令', color: '#A78BFA', page: 'tenumerate', icon: 'swap' },
    { name: '滚动字幕', desc: '公告/字幕指令', color: '#F472B6', page: 'tscroller', icon: 'campaign' },
    { name: '音乐生成器', desc: '命令方块音乐', color: '#34D399', page: 'tmusic', icon: 'music' },
    { name: 'T显JSON编辑器', desc: 'rawtext 可视化编辑', color: '#22D3EE', page: 'ttitleraw', icon: 'code' },
    { name: 'AI Agent', desc: 'AI 智能编写', color: '#60A5FA', page: 'ai', icon: 'robot' },
    { name: '五子棋', desc: '人机/匹配对战', color: '#F59E0B', page: 'gomoku', icon: 'casino' },
    { name: '聊天室', desc: '和玩家实时交流', color: '#34D399', page: 'chat', icon: 'forum' },
    { name: '好友', desc: '好友私聊', color: '#F472B6', page: 'friends', icon: 'person' },
    { name: '意见反馈', desc: '反馈问题/申诉', color: '#C084FC', page: 'feedback', icon: 'feedback' },
  ];
  let html = '';
  // 公告区
  if (anns.length) {
    html += `<div class="section-title">公告</div>`;
    anns.slice(0, 3).forEach(a => {
      html += `<div class="card announce-card" onclick="showAnnounce(${a.id})">
        <div class="announce-title">${esc(a.title)}</div>
        <div class="announce-meta">${fmtTime(a.created_at)}</div>
        ${a.content ? `<div class="announce-body">${esc(a.content).slice(0, 120)}${a.content.length > 120 ? '...' : ''}</div>` : ''}
      </div>`;
    });
  }
  html += `<div class="section-title">功能</div><div class="module-grid">`;
  modules.forEach(m => {
    html += `<div class="module-card" onclick="showPage('${m.page}')">
      <div class="m-ico" style="background:${m.color}">${mi(m.icon, 22)}</div>
      <div class="m-name">${m.name}</div><div class="m-desc">${m.desc}</div></div>`;
  });
  html += `</div>`;
  mc.innerHTML = html;
}
function showAnnounce(id) {
  const r = null; // 简单: 重新拉取公告列表找该条
  (async () => {
    const annR = await API.announcements('all');
    const list = (annR.ok && annR.data) ? (Array.isArray(annR.data) ? annR.data : (annR.data.list || [])) : [];
    const a = list.find(x => x.id === id);
    if (!a) { toast('公告不存在'); return; }
    openModal(a.title,
      `<p class="announce-body" style="color:var(--text)">${esc(a.content || '')}</p>
       <p class="announce-meta">${fmtTime(a.created_at)}</p>`,
      '');
  })();
}

// ============ 指令库 ============
let libPage = 1, libQuery = '', libCat = 'all', libCats = [];
async function renderLibrary(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const catR = await API.categories().catch(() => ({ ok: false }));
  libCats = (catR.ok && catR.data) ? (Array.isArray(catR.data) ? catR.data : (catR.data.categories || [])) : [];
  renderLibraryInner(mc);
}
async function renderLibraryInner(mc) {
  const r = await API.commands(libQuery, libCat, libPage);
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  const total = (r.ok && r.data && r.data.total) ? r.data.total : 0;
  let html = `<div class="row" style="margin-bottom:10px">
    <input class="grow" style="padding:9px 12px;border-radius:8px;border:1px solid var(--border);background:var(--card2);color:var(--text);outline:none"
      id="lib-search" value="${escAttr(libQuery)}" placeholder="搜索指令...">
    <button class="btn primary" onclick="doLibSearch()">搜索</button></div>
    <div class="row" style="flex-wrap:wrap;margin-bottom:12px">
    <span class="chip ${libCat==='all'?'active':''}" onclick="setLibCat('all')">全部</span>
    ${libCats.map(c => `<span class="chip ${libCat===c?'active':''}" onclick="setLibCat('${escAttr(c)}')">${esc(c)}</span>`).join('')}
    </div>`;
  if (!list.length) html += `<div class="empty-hint">暂无指令</div>`;
  list.forEach(c => {
    const isOfficial = c.user_official === 1 || c.official === 1;
    html += `<div class="list-item" onclick="openCommand(${c.id})">
      <div class="grow">
        <div class="list-title">${esc(c.title)} ${isOfficial ? '<span class="tag red">官方</span>' : ''}
          ${c.status === 'pending' ? '<span class="tag gold">待审核</span>' : ''}</div>
        <div class="list-desc">${esc((c.description || '').slice(0, 60))}</div>
        <div style="margin-top:4px"><span class="tag green">${esc(c.category || '其他')}</span>
          <span class="tag">赞 ${c.likes || 0}</span><span class="tag">${esc(c.nickname || c.username || '?')}</span></div>
      </div></div>`;
  });
  const totalPages = Math.max(1, Math.ceil(total / 20));
  html += `<div class="row" style="justify-content:center;margin-top:12px">
    <button class="btn sm" ${libPage<=1?'disabled':''} onclick="libPage--;renderLibraryInner($('main-content'))">上一页</button>
    <span class="small muted">${libPage}/${totalPages}</span>
    <button class="btn sm" ${libPage>=totalPages?'disabled':''} onclick="libPage++;renderLibraryInner($('main-content'))">下一页</button></div>`;
  mc.innerHTML = html;
  const inp = $('lib-search');
  if (inp) inp.addEventListener('keydown', e => { if (e.key === 'Enter') doLibSearch(); });
}
function doLibSearch() { libQuery = $('lib-search').value.trim(); libPage = 1; renderLibraryInner($('main-content')); }
function setLibCat(c) { libCat = c; libPage = 1; renderLibraryInner($('main-content')); }
async function openCommand(id) {
  const r = await API.command(id);
  if (!r.ok || !r.data) { toast('加载失败'); return; }
  const c = r.data;
  const cmdText = c.command || c.output_command || '';
  let cmds = [];
  try { const parsed = JSON.parse(cmdText); if (Array.isArray(parsed)) cmds = parsed; else cmds = [cmdText]; } catch (e) { cmds = [cmdText]; }
  const me = API.user;
  // 指令块
  let cmdHtml = '';
  cmds.forEach((cmd, i) => {
    cmdHtml += `<div class="cmd-block cmd-block-modal">${esc(cmd)}
      <button class="btn sm copy-btn" onclick="copyCmdText(${id}, ${i})">复制</button></div>`;
  });
  if (!cmdHtml) cmdHtml = '<div class="empty-hint">无指令内容</div>';
  // 评论容器 (弹窗内滚动)
  let body = `
    <p style="font-size:12px;color:var(--muted);margin-bottom:6px">${esc(c.category || '其他')} · 作者 ${esc(c.nickname || c.username || '?')} · ${fmtTime(c.created_at)} · 赞 ${c.likes || 0}</p>
    ${c.description ? `<p style="font-size:13px;color:var(--muted);margin-bottom:10px">${esc(c.description)}</p>` : ''}
    <div class="cmd-block-modal-wrap">${cmdHtml}</div>
    <div style="display:flex;gap:8px;margin:10px 0">
      <button class="btn" onclick="toggleFav(${c.id}, this)">${c.favorited ? '取消收藏' : '收藏'}</button>
      <button class="btn" onclick="toggleLike(${c.id}, this)">点赞</button>
      <button class="btn" onclick="copyAllCmdText(${id})">复制全部</button>
      ${me && (me.id === c.user_id || me.role === 'admin') ? `<button class="btn" onclick="editCommand(${c.id})">编辑</button><button class="btn danger" onclick="delCommand(${c.id})">删除</button>` : ''}
    </div>
    <div class="modal-comments">
      <div class="section-title" style="margin:6px 0 8px">评论 (${c.comment_count || 0})</div>
      <div id="comments-box"><div class="empty-hint">加载中...</div></div>
      <div style="display:flex;gap:8px;margin-top:8px">
        <input class="grow" id="comment-input" style="padding:9px 12px;border-radius:8px;border:1px solid var(--border);background:var(--card2);color:var(--text);outline:none" placeholder="写下你的评论...">
        <button class="btn primary" onclick="addComment(${c.id})">评论</button>
      </div>
    </div>`;
  openModal(c.title, body, '');
  loadComments(c.id);
}
// 记录指令块文本供复制
window._cmdBlocks = {};
async function cacheCmdBlocks(id) {
  if (window._cmdBlocks[id]) return;
  const r = await API.command(id);
  if (!r.ok || !r.data) return;
  const c = r.data;
  const cmdText = c.command || c.output_command || '';
  let cmds = [];
  try { const parsed = JSON.parse(cmdText); if (Array.isArray(parsed)) cmds = parsed; else cmds = [cmdText]; } catch (e) { cmds = [cmdText]; }
  window._cmdBlocks[id] = cmds;
}
function copyCmdText(id, i) { cacheCmdBlocks(id).then(() => { const a = window._cmdBlocks[id]; if (a && a[i]) copyText(a[i]); }); }
function copyAllCmdText(id) { cacheCmdBlocks(id).then(() => { const a = window._cmdBlocks[id]; if (a && a.length) copyText(a.join('\n')); }); }
async function loadComments(cid) {
  const r = await API.comments(cid);
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  const box = $('comments-box'); if (!box) return;
  if (!list.length) { box.innerHTML = '<div class="empty-hint">暂无评论</div>'; return; }
  box.innerHTML = list.map(cm => `<div class="list-item" style="padding:8px;cursor:default">
    <div class="avatar" style="width:26px;height:26px;font-size:11px">${avatarHtml(cm)}</div>
    <div class="grow"><div class="small"><span class="green">${esc(cm.nickname || cm.username || '?')}</span> <span class="muted">${fmtTime(cm.created_at)}</span>
    ${cm.user_official === 1 ? '<span class="tag red">官方</span>' : ''}</div>
    <div class="small" style="margin-top:2px">${esc(cm.content || '')}</div></div></div>`).join('');
}
async function addComment(cid) {
  const inp = $('comment-input'); const text = inp.value.trim();
  if (!text) { toast('请输入评论内容'); return; }
  const r = await API.addComment(cid, { content: text });
  if (r.ok) { inp.value = ''; loadComments(cid); toast('评论成功'); }
  else toast(r.error || '评论失败');
}
async function toggleFav(id, btn) {
  const r = await API.toggleFav(id);
  if (r.ok) { btn.textContent = btn.textContent === '收藏' ? '取消收藏' : '收藏'; toast('操作成功'); }
  else toast(r.error || '操作失败');
}
async function toggleLike(id, btn) {
  const r = await API.like(id);
  if (r.ok) { toast('点赞成功'); }
  else toast(r.error || '点赞失败');
}
function editCommand(id) {
  (async () => {
    const r = await API.command(id);
    if (!r.ok || !r.data) return;
    const c = r.data;
    openModal('编辑指令',
      `<div class="field"><label>标题</label><input id="e-title" value="${escAttr(c.title)}"></div>
       <div class="field"><label>分类</label><input id="e-cat" value="${escAttr(c.category || '')}"></div>
       <div class="field"><label>说明</label><textarea id="e-desc" rows="3">${esc(c.description || '')}</textarea></div>
       <div class="field"><label>指令内容</label><textarea id="e-cmd" rows="8">${esc(c.command || c.output_command || '')}</textarea></div>`,
      `<button class="btn primary" onclick="saveCommand(${c.id})">保存</button>`);
  })();
}
async function saveCommand(id) {
  const r = await API.updateCommand(id, {
    title: $('e-title').value.trim(), category: $('e-cat').value.trim(),
    description: $('e-desc').value, command: $('e-cmd').value,
  });
  if (r.ok) { closeModal(); toast('已保存'); openCommand(id); } else toast(r.error || '保存失败');
}
async function delCommand(id) {
  if (!confirm('确定删除该指令?')) return;
  const r = await API.deleteCommand(id);
  if (r.ok) { toast('已删除'); renderLibrary($('main-content')); } else toast(r.error || '删除失败');
}

// ============ 指令上传 ============
async function renderUpload(mc) {
  const cats = libCats.length ? libCats : ['指令', '建筑', '红石', '命令方块', '其他'];
  mc.innerHTML = `<div class="section-title">上传指令</div>
  <div class="card">
    <div class="field"><label class="small muted">标题</label><input id="u-title" placeholder="指令标题"></div>
    <div class="field"><label class="small muted">分类</label><select id="u-cat">${cats.map(c => `<option>${esc(c)}</option>`).join('')}</select></div>
    <div class="field"><label class="small muted">说明</label><textarea id="u-desc" rows="3" placeholder="指令用途说明"></textarea></div>
    <div class="field"><label class="small muted">指令内容 (多条用换行分隔)</label><textarea id="u-cmd" rows="8" placeholder="/give @s diamond 64"></textarea></div>
    <button class="btn primary block" onclick="doUpload()">发布</button>
  </div>`;
}
async function doUpload() {
  const title = $('u-title').value.trim();
  const category = $('u-cat').value;
  const description = $('u-desc').value.trim();
  const raw = $('u-cmd').value.trim();
  if (!title || !raw) { toast('请填写标题和指令内容'); return; }
  const lines = raw.split('\n').map(l => l.trim()).filter(Boolean);
  const command = lines.length > 1 ? JSON.stringify(lines) : lines[0];
  const r = await API.uploadCommand({ title, category, description, command });
  if (r.ok) { toast('已提交，等待审核'); showPage('library'); } else toast(r.error || '发布失败');
}

// ============ 聊天室 ============
function renderChat(mc) {
  mc.innerHTML = `<div class="section-title">聊天室</div>
    <div class="chat-list" id="chat-list"></div>
    <div class="chat-input-bar">
      <input id="chat-input" placeholder="说点什么... (3秒限流)" maxlength="200">
      <button class="btn primary" onclick="sendChat()">发送</button>
    </div>`;
  chatList = []; chatLastId = 0;
  loadChat();
  chatTimer = setInterval(loadChat, 2500);
  const inp = $('chat-input');
  inp.addEventListener('keydown', e => { if (e.key === 'Enter') sendChat(); });
}
function stopChat() { if (chatTimer) { clearInterval(chatTimer); chatTimer = null; } }
async function loadChat() {
  const r = await API.chatMessages(chatLastId);
  if (!r.ok || !r.data) return;
  const list = Array.isArray(r.data) ? r.data : (r.data.list || []);
  if (!list.length) return;
  chatLastId = Math.max(chatLastId, ...list.map(m => m.id || 0));
  const box = $('chat-list'); if (!box) return;
  const me = API.user;
  list.forEach(m => {
    const mine = me && m.user_id === me.id;
    chatList.push(m);
    const div = document.createElement('div');
    div.className = 'chat-msg';
    div.style.justifyContent = mine ? 'flex-end' : 'flex-start';
    div.innerHTML = `<div class="c-avatar">${avatarHtml(m)}</div>
      <div class="chat-bubble">
        <div class="chat-name">${esc(m.nickname || m.username || '?')} ${m.user_official === 1 ? '<span class="tag red" style="font-size:9px">官方</span>' : ''}</div>
        <div class="chat-text">${esc(m.content || '')}</div>
        <div class="chat-time">${fmtTime(m.created_at)}</div>
      </div>`;
    box.appendChild(div);
  });
  box.scrollTop = box.scrollHeight;
}
async function sendChat() {
  const inp = $('chat-input'); const text = inp.value.trim();
  if (!text) return;
  const r = await API.chatSend({ content: text });
  if (r.ok) { inp.value = ''; loadChat(); } else toast(r.error || '发送失败(3秒限流)');
}

// ============ 站内信 ============
async function renderMessages(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const r = await API.messages();
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  if (!list.length) { mc.innerHTML = '<div class="empty-hint">暂无消息</div>'; return; }
  mc.innerHTML = list.map(m => `<div class="card" onclick="openMessage(${m.id})" style="cursor:pointer">
    <div class="card-title">${esc(m.title || '消息')} ${!m.is_read ? '<span class="tag red">未读</span>' : ''}</div>
    <div class="card-sub">${fmtTime(m.created_at)}</div>
    ${m.content ? `<p style="font-size:13px;color:var(--muted);margin-top:6px;white-space:pre-wrap;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical">${esc(m.content)}</p>` : ''}
    <div style="text-align:right;font-size:11px;color:var(--blue);margin-top:6px">查看详情</div>
  </div>`).join('');
}
function openMessage(id) {
  (async () => {
    const r = await API.messages();
    const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
    const m = list.find(x => x.id === id);
    if (!m) { toast('消息不存在'); return; }
    openModal(m.title || '消息',
      `<p style="font-size:12px;color:var(--muted);margin-bottom:8px">${fmtTime(m.created_at)}${!m.is_read ? ' · 未读' : ''}</p>
       <p style="font-size:14px;line-height:1.7;white-space:pre-wrap;color:#E7F2EA">${esc(m.content || '')}</p>`,
      '');
    readMsg(id);
  })();
}
async function readMsg(id, el) {
  const r = await API.readMessage(id);
  if (r.ok) {
    // 刷新未读徽章
    const un = await API.unreadCount().catch(() => ({ ok: false }));
    if (un.ok && un.data && un.data.count > 0) { /* 徽章可后续 */ }
  }
}

// ============ 个人中心 ============
async function renderProfile(mc) {
  const me = API.user;
  let html = `<div class="card" style="text-align:center">
    <div class="avatar" style="width:64px;height:64px;font-size:26px;margin:0 auto 8px">${avatarHtml(me)}</div>
    <div class="card-title" style="color:var(--text)">${esc(me.nickname || me.username)}</div>
    <div class="card-sub">@${esc(me.username)} · ${esc(me.email || '')}</div>
    <div style="margin-top:8px"><span class="tag gold">${me.points || 0} 积分</span>
    ${me.role === 'admin' ? '<span class="tag red">管理员</span>' : ''}
    ${me.official === 1 ? '<span class="tag red">官方</span>' : ''}</div>
    </div>`;
  html += `<div class="card">
    <div class="list-item" onclick="showPage('mycommands')"><div class="grow"><div class="list-title">我的指令</div><div class="list-desc">查看/管理我发布的指令</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('messages')"><div class="grow"><div class="list-title">站内信</div><div class="list-desc">系统通知与回复</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('friends')"><div class="grow"><div class="list-title">好友</div><div class="list-desc">好友列表与私聊</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="doCheckin()"><div class="grow"><div class="list-title">每日签到</div><div class="list-desc">领取积分</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('pointslog')"><div class="grow"><div class="list-title">积分记录</div><div class="list-desc">查看积分变动</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('about')"><div class="grow"><div class="list-title">关于</div><div class="list-desc">版本/合作方/开源</div></div><span class="muted">></span></div>
    </div>`;
  html += `<button class="btn danger block" onclick="doLogout()">退出登录</button>`;
  mc.innerHTML = html;
}
async function doCheckin() {
  const r = await API.checkin();
  if (r.ok) { toast(r.data && r.data.message ? r.data.message : '签到成功'); refreshUserBadge(); }
  else toast(r.error || '今日已签到');
}
async function doLogout() { API.logout(); location.reload(); }

async function renderMyCommands(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const me = API.user;
  const r = await API.userCommands(me.id);
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  if (!list.length) { mc.innerHTML = '<div class="empty-hint">还没有发布过指令</div>'; return; }
  mc.innerHTML = list.map(c => `<div class="list-item" onclick="openCommand(${c.id})">
    <div class="grow"><div class="list-title">${esc(c.title)}
      ${c.status === 'pending' ? '<span class="tag gold">待审核</span>' : c.status === 'rejected' ? '<span class="tag red">已驳回</span>' : '<span class="tag green">已发布</span>'}</div>
    <div class="list-desc">${fmtTime(c.created_at)} · 赞 ${c.likes || 0}</div></div></div>`).join('');
}

async function renderPointsLog(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const r = await API.points();
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  if (!list.length) { mc.innerHTML = '<div class="empty-hint">暂无积分记录</div>'; return; }
  mc.innerHTML = list.map(p => `<div class="list-item" style="cursor:default">
    <div class="grow"><div class="list-title">${esc(p.reason || '积分变动')}</div>
    <div class="list-desc">${fmtTime(p.created_at)} · 余额 ${p.balance ?? ''}</div></div>
    <span class="${p.delta >= 0 ? 'green' : 'red'}" style="font-weight:700">${p.delta >= 0 ? '+' : ''}${p.delta}</span></div>`).join('');
}

// ============ 好友 ============
async function renderFriends(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const r = await API.dmConversations();
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  let html = `<div class="section-title">好友与私信</div>
    <div class="row" style="margin-bottom:10px"><input class="grow" id="friend-search" style="padding:9px 12px;border-radius:8px;border:1px solid var(--border);background:var(--card2);color:var(--text);outline:none" placeholder="输入用户名添加好友...">
    <button class="btn primary" onclick="addFriend()">添加</button></div>`;
  if (!list.length) html += `<div class="empty-hint">还没有好友，输入用户名添加吧</div>`;
  list.forEach(c => {
    const peer = c.peer || c.user || {};
    html += `<div class="list-item" onclick="openDm(${peer.id}, '${escAttr(peer.nickname || peer.username || '好友')}')">
      <div class="avatar">${avatarHtml(peer)}</div>
      <div class="grow"><div class="list-title">${esc(peer.nickname || peer.username || '好友')}</div>
      <div class="list-desc">${esc(c.last_message || '')}</div></div></div>`;
  });
  mc.innerHTML = html;
}
async function addFriend() {
  const name = $('friend-search').value.trim();
  if (!name) return;
  const r = await API.friendRequest({ username: name });
  if (r.ok) { toast('已发送好友申请'); } else toast(r.error || '添加失败');
}
async function openDm(peerId, peerName) {
  window._dmPeer = { id: peerId, name: peerName };
  showPage('dm');
}
async function renderDm(mc) {
  const peer = window._dmPeer || { id: 0, name: '好友' };
  mc.innerHTML = `<div class="section-title">与 ${esc(peer.name)} 私聊</div>
    <div class="chat-list" id="dm-list"></div>
    <div class="chat-input-bar">
      <input id="dm-input" placeholder="输入消息..." maxlength="500">
      <button class="btn primary" onclick="sendDm()">发送</button>
    </div>`;
  let lastId = 0;
  const load = async () => {
    const r = await API.dmMessages(peer.id, lastId);
    if (r.ok && r.data) {
      const list = Array.isArray(r.data) ? r.data : (r.data.list || []);
      const box = $('dm-list'); if (!box) return;
      const me = API.user;
      list.forEach(m => {
        lastId = Math.max(lastId, m.id || 0);
        const mine = me && m.sender_id === me.id;
        const div = document.createElement('div');
        div.className = 'chat-msg';
        div.style.justifyContent = mine ? 'flex-end' : 'flex-start';
        div.innerHTML = `<div class="c-avatar">${esc((mine ? me.nickname : peer.name) || '?').slice(0,1).toUpperCase()}</div>
          <div class="chat-bubble"><div class="chat-text">${esc(m.content || '')}</div>
          <div class="chat-time">${fmtTime(m.created_at)}</div></div>`;
        box.appendChild(div);
      });
      box.scrollTop = box.scrollHeight;
    }
  };
  load();
  const iv = setInterval(load, 3000);
  window._dmTimer = iv;
  const inp = $('dm-input');
  inp.addEventListener('keydown', e => { if (e.key === 'Enter') sendDm(); });
  // 清理: showPage 时清除
  window._stopDm = () => clearInterval(iv);
}
async function sendDm() {
  const peer = window._dmPeer || {};
  const inp = $('dm-input'); const text = inp.value.trim();
  if (!text || !peer.id) return;
  const r = await API.dmSend({ peer_id: peer.id, content: text });
  if (r.ok) {
    inp.value = '';
    const box = $('dm-list');
    if (box) {
      const me = API.user;
      const div = document.createElement('div');
      div.className = 'chat-msg';
      div.style.justifyContent = 'flex-end';
      div.innerHTML = `<div class="c-avatar">${esc((me && me.nickname) || '我').slice(0,1).toUpperCase()}</div>
        <div class="chat-bubble"><div class="chat-text">${esc(text)}</div>
        <div class="chat-time">刚刚</div></div>`;
      box.appendChild(div);
      box.scrollTop = box.scrollHeight;
    }
  } else toast(r.error || '发送失败');
}

// ============ 用户主页 ============
async function renderUserPage(mc) {
  const uid = window._userPageId || API.user.id;
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const [uR, cR] = await Promise.all([API.userProfile(uid), API.userCommands(uid)]);
  const u = (uR.ok && uR.data) ? (uR.data.user || uR.data) : {};
  const list = (cR.ok && cR.data) ? (Array.isArray(cR.data) ? cR.data : (cR.data.list || [])) : [];
  let html = `<div class="card" style="text-align:center">
    <div class="avatar" style="width:56px;height:56px;font-size:24px;margin:0 auto 8px">${avatarHtml(u)}</div>
    <div class="card-title" style="color:var(--text)">${esc(u.nickname || u.username || '?')}</div>
    <div class="card-sub">@${esc(u.username || '')} ${u.official === 1 ? '<span class="tag red">官方</span>' : ''}</div>
    </div>`;
  html += `<div class="section-title">TA 的指令 (${list.length})</div>`;
  if (!list.length) html += '<div class="empty-hint">暂无指令</div>';
  list.forEach(c => {
    html += `<div class="list-item" onclick="openCommand(${c.id})"><div class="grow">
      <div class="list-title">${esc(c.title)}</div>
      <div class="list-desc">${esc(c.category || '')} · 赞 ${c.likes || 0}</div></div></div>`;
  });
  mc.innerHTML = html;
}

// ============ 意见反馈 ============
function renderFeedback(mc) {
  mc.innerHTML = `<div class="section-title">意见反馈 / 申诉</div>
  <div class="card">
    <div class="field"><label class="small muted">你的问题或建议</label>
    <textarea id="fb-content" rows="6" placeholder="描述遇到的问题或想加的功能..."></textarea></div>
    <button class="btn primary block" onclick="doFeedback()">提交</button>
    <p class="auth-hint">我们会认真阅读每条反馈</p>
  </div>`;
}
async function doFeedback() {
  const content = $('fb-content').value.trim();
  if (!content) { toast('请填写内容'); return; }
  const r = await API.submitFeedback({ content });
  if (r.ok) { toast('已提交，感谢反馈'); $('fb-content').value = ''; } else toast(r.error || '提交失败');
}

// ============ 更多 ============
function renderMore(mc) {
  mc.innerHTML = `
  <div class="section-title">更多功能</div>
  <div class="card">
    <div class="list-item" onclick="showPage('profile')"><div class="grow"><div class="list-title">个人中心</div><div class="list-desc">账号信息/签到/我的指令</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('messages')"><div class="grow"><div class="list-title">站内信</div><div class="list-desc">系统通知</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('friends')"><div class="grow"><div class="list-title">好友与私信</div><div class="list-desc">好友列表/私聊</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('feedback')"><div class="grow"><div class="list-title">意见反馈</div><div class="list-desc">反馈问题/申诉</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="window.open('/dev/','_blank')"><div class="grow"><div class="list-title">开发者平台</div><div class="list-desc">开放 API / 接口文档 / 插件开发教学</div></div><span class="muted">></span></div>
    <div class="list-item" onclick="showPage('about')"><div class="grow"><div class="list-title">关于</div><div class="list-desc">版本/合作方/开源协议</div></div><span class="muted">></span></div>
  </div>
  <div class="section-title">生成器</div>
  <div class="tool-grid">
    <div class="tool-card" onclick="showPage('tscoreboard')"><div class="t-ico" style="background:#FBBF24">T</div><div class="t-name">T显生成器</div><div class="t-desc">计分板/T显示</div></div>
    <div class="tool-card" onclick="showPage('tenumerate')"><div class="t-ico" style="background:#A78BFA">举</div><div class="t-name">穷举</div><div class="t-desc">批量生成指令</div></div>
    <div class="tool-card" onclick="showPage('tscroller')"><div class="t-ico" style="background:#F472B6">幕</div><div class="t-name">滚动字幕</div><div class="t-desc">公告/字幕指令</div></div>
    <div class="tool-card" onclick="showPage('tmusic')"><div class="t-ico" style="background:#34D399">音</div><div class="t-name">音乐生成器</div><div class="t-desc">命令方块音乐</div></div>
    <div class="tool-card" onclick="showPage('ttitleraw')"><div class="t-ico" style="background:#22D3EE">J</div><div class="t-name">T显JSON编辑器</div><div class="t-desc">rawtext 可视化</div></div>
  </div>`;
}

// ============ 关于 ============
async function renderAbout(mc) {
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const vR = await API.latestVersion().catch(() => ({ ok: false }));
  const latest = (vR.ok && vR.data) ? vR.data : null;
  const ver = latest ? latest.version : '';
  const dlUrl = latest && latest.url ? latest.url : '';
  const dlSize = latest && latest.size ? (latest.size / 1048576).toFixed(1) + ' MB' : '';
  const webVer = '网页版 v1.0.81';
  mc.innerHTML = `
  <div class="card" style="text-align:center">
    <img src="logo-usericon.png" alt="CyanCodex" style="width:64px;height:64px;border-radius:16px;margin:4px auto 10px;display:block;box-shadow:0 4px 16px rgba(74,222,128,.3)">
    <div class="card-title" style="color:var(--green);font-size:20px">CyanCodex</div>
    <div class="card-sub">我的世界（基岩版）指令助手 · 沧蓝工坊开发</div>
    <div class="card-sub">${ver ? 'Android 最新版 v' + ver + (dlSize ? ' · ' + dlSize : '') : 'Android 版'}</div>
    <div class="card-sub">${webVer}</div>
    <p style="font-size:12px;color:var(--muted);margin-top:8px">数据与手机 App 完全互通，登录同一账号即可</p>
    <div style="margin-top:14px">
      ${dlUrl ? `<a class="btn primary block glass-btn" href="${escAttr(dlUrl)}" style="display:block;text-align:center;text-decoration:none;padding:13px;font-size:15px">下载 Android 版${ver ? ' v' + ver : ''}</a>` : '<div class="empty-hint">安装包生成中，请稍后再试</div>'}
      <p style="font-size:11px;color:var(--muted);margin-top:6px">Android APK 安装包，自动同步最新版本</p>
    </div>
  </div>
  <div class="section-title">开发与合作</div>
  <div class="card">
    <div class="partner-box">
      <img src="logo-shiji.png" alt="世纪集团" style="width:44px;height:44px;border-radius:10px;display:block;margin:0 auto 8px">
      <div class="p-name">世纪集团旗下</div><div class="p-desc">CyanCodex 收录于世纪集团</div>
    </div>
    <div style="border-top:1px solid var(--border);margin:10px 0"></div>
    <div class="partner-box">
      <img src="logo-jzfk.png" alt="矩阵方块" style="width:44px;height:44px;border-radius:10px;display:block;margin:0 auto 8px">
      <div class="p-name">矩阵方块</div><div class="p-desc">协助开发</div>
    </div>
    <div style="border-top:1px solid var(--border);margin:10px 0"></div>
    <div class="partner-box">
      <img src="logo-canglan.png" alt="沧蓝工坊" style="width:44px;height:44px;border-radius:10px;display:block;margin:0 auto 8px">
      <div class="p-name">沧蓝工坊工作室</div><div class="p-desc">开发与维护</div>
    </div>
  </div>
  <div class="section-title">交流与支持</div>
  <div class="card">
    <a class="list-item" href="https://qun.qq.com/universal-share/share?ac=1&amp;authKey=hwdWfjhcrT2gyREXBglanWfdHTMjrjUvEvVSSg8%2FVGx8E10%2FEVdfp3H0OJPPTPdi&amp;busi_data=eyJncm91cENvZGUiOiIxMDY1MDI1ODc0IiwidG9rZW4iOiJiZ0JFVG9Uakc3ZDcrZkZyK0cxVStZOEErYU1Ja1o5S1ZjUFlYM0padVdsVUNGKytrZ1lidvkrakZ5YjhmbWR4IiwidWluIjoiMzYxNTc4NTc2OSJ9&amp;data=ZezSPJyBUEcAwSLrXEOkeiTVTvEZK8FRMEkY2BQdfpP_GIPfHokXrTLjcvEzf7ZehLL-nD00FXXW5z7Z2chqZA&amp;svctype=4&amp;tempid=h5_group_info" target="_blank" rel="noopener">
      <div class="grow"><div class="list-title">QQ 群</div><div class="list-desc">群号 1065025874，点击加入一起交流指令</div></div><span class="muted">加入</span>
    </a>
    <a class="list-item" href="https://github.com/Diamond-Minec/CyanCodex-Android" target="_blank" rel="noopener">
      <div class="grow"><div class="list-title">GitHub 开源仓库</div><div class="list-desc">GPLv3.0 协议开源</div></div><span class="muted">></span>
    </a>
    <div class="list-item" onclick="showPage('feedback')"><div class="grow"><div class="list-title">意见反馈</div><div class="list-desc">反馈问题或建议</div></div><span class="muted">></span></div>
  </div>
  <p style="text-align:center;font-size:11px;color:var(--muted);padding:12px 0 20px;line-height:1.8">
    Copyright © 2026 世纪集团 · 沧蓝工坊工作室 · CyanCodex<br>
    本软件以 GNU GPLv3.0 协议开源发布<br>
    数据接口与 Android App 完全互通</p>`;
}

// ============ 生成器页面 (工具) ============
function renderTools(mc) {
  mc.innerHTML = `<div class="section-title">生成器</div>
  <div class="tool-grid">
    <div class="tool-card" onclick="showPage('tscoreboard')"><div class="t-ico" style="background:#FBBF24">${mi('chart', 22)}</div><div class="t-name">T显生成器</div><div class="t-desc">计分板/T显示</div></div>
    <div class="tool-card" onclick="showPage('tenumerate')"><div class="t-ico" style="background:#A78BFA">${mi('swap', 22)}</div><div class="t-name">穷举</div><div class="t-desc">批量生成指令</div></div>
    <div class="tool-card" onclick="showPage('tscroller')"><div class="t-ico" style="background:#F472B6">${mi('campaign', 22)}</div><div class="t-name">滚动字幕</div><div class="t-desc">公告/字幕指令</div></div>
    <div class="tool-card" onclick="showPage('tmusic')"><div class="t-ico" style="background:#34D399">${mi('music', 22)}</div><div class="t-name">音乐生成器</div><div class="t-desc">命令方块音乐</div></div>
    <div class="tool-card" onclick="showPage('ttitleraw')"><div class="t-ico" style="background:#22D3EE">${mi('code', 22)}</div><div class="t-name">T显JSON编辑器</div><div class="t-desc">rawtext 可视化编辑</div></div>
  </div>`;
}

function renderGenResult(html) {
  return `<div class="card"><div class="section-title" style="margin:0 0 8px">生成结果</div>
    <div id="gen-out">${html}</div>
    <button class="btn primary block" style="margin-top:10px" onclick="copyAllCmds()">一键复制全部</button>
  </div>`;
}
let _genCmds = [];
function showGenCmds(cmds, mc) {
  _genCmds = cmds;
  const out = $('gen-out');
  if (!out) return;
  if (!cmds.length) { out.innerHTML = '<div class="empty-hint">无输出</div>'; return; }
  out.innerHTML = cmds.map((c, i) => {
    const isComment = c.startsWith('#');
    return `<div class="cmd-block" style="${isComment ? 'color:var(--muted)' : ''}">${esc(c)}<button class="btn sm copy-btn" onclick="copyGenCmd(${i})">复制</button></div>`;
  }).join('');
}
function copyAllCmds() { if (_genCmds.length) copyText(_genCmds.join('\n')); }
function copyGenCmd(i) { copyText(_genCmds[i] || ''); }

// --- T显/计分板 ---
function renderTScoreboard(mc) {
  mc.innerHTML = `<div class="section-title">T显生成器</div>
  <div class="card">
    <div class="field"><label class="small muted">计分板名称</label><input id="sb-obj" value="sb" placeholder="objective 名称"></div>
    <div class="field"><label class="small muted">显示名称</label><input id="sb-name" value="菜单" placeholder="显示名称"></div>
    <div class="field-row">
      <div class="field grow"><label class="small muted">模式</label>
        <select id="sb-mode"><option value="tdisplay">T显 (titleraw)</option><option value="sidebar">侧边栏</option></select></div>
      <div class="field grow"><label class="small muted">位置</label>
        <select id="sb-pos"><option value="title">title</option><option value="subtitle">subtitle</option><option value="actionbar">actionbar</option></select></div>
    </div>
    <div class="field"><label class="small muted">显示行 (每行: 前缀|计分板|后缀, 留空跳过)</label>
      <textarea id="sb-lines" rows="4" placeholder="===== 菜单 =====|sb|&lt;/&gt;&#10;钻石: |diamonds| 个&#10;等级: |level| 级"></textarea></div>
    <button class="btn primary block" onclick="genScoreboard()">生成指令</button>
  </div>`;
  mc.innerHTML += renderGenResult('<div class="empty-hint">填写上方内容后生成</div>');
}
async function genScoreboard() {
  const obj = $('sb-obj').value.trim() || 'sb';
  const name = $('sb-name').value.trim() || obj;
  const mode = $('sb-mode').value;
  const pos = $('sb-pos').value;
  const lines = $('sb-lines').value.split('\n').map(l => l.trim()).filter(Boolean)
    .map(l => {
      const parts = l.split('|');
      return { prefix: parts[0] || '', objective: (parts[1] || '').trim(), suffix: parts[2] || '' };
    });
  const cmds = GEN.scoreboard({ objective: obj, displayName: name, mode, tpos: pos, tlines: lines });
  showGenCmds(cmds, null);
  toast('已生成 ' + cmds.length + ' 条指令');
}

// --- 穷举 ---
function renderTEnumerate(mc) {
  mc.innerHTML = `<div class="section-title">穷举生成器</div>
  <div class="card">
    <div class="field"><label class="small muted">指令模板 (用 {变量名} 占位)</label>
      <textarea id="en-tpl" rows="3" placeholder="/give @p diamond {数量}"></textarea></div>
    <div class="field"><label class="small muted">变量 (每行: 变量名=值1,值2,值3)</label>
      <textarea id="en-vars" rows="4" placeholder="数量=1,16,32,64&#10;玩家=@p,Steve,Alex"></textarea></div>
    <div class="field"><label class="small muted">最大数量</label><input id="en-max" type="number" value="100"></div>
    <button class="btn primary block" onclick="genEnumerate()">生成</button>
  </div>`;
  mc.innerHTML += renderGenResult('<div class="empty-hint">填写上方内容后生成</div>');
}
function genEnumerate() {
  const tpl = $('en-tpl').value.trim();
  const vars = {};
  $('en-vars').value.split('\n').map(l => l.trim()).filter(Boolean).forEach(l => {
    const m = l.split('=');
    if (m.length >= 2) vars[m[0].trim()] = m[1].split(',').map(v => v.trim());
  });
  const max = parseInt($('en-max').value) || 100;
  const cmds = GEN.enumerate(tpl, vars, max);
  showGenCmds(cmds, null);
  toast('已生成 ' + cmds.length + ' 条指令');
}

// --- 滚动字幕 ---
function renderTScroller(mc) {
  mc.innerHTML = `<div class="section-title">滚动字幕</div>
  <div class="card">
    <div class="field"><label class="small muted">字幕文本</label><textarea id="sc-text" rows="3" placeholder="欢迎来到我的世界服务器"></textarea></div>
    <div class="field"><label class="small muted">颜色</label>
      <select id="sc-color">${Object.entries(GEN.COLORS).map(([k, v]) => `<option value="${v}">${k}</option>`).join('')}</select></div>
    <div class="field-row">
      <div class="field grow"><label class="small muted">模式</label>
        <select id="sc-mode"><option value="score">计分板轮播</option><option value="banner">盔甲架横幅</option></select></div>
    </div>
    <button class="btn primary block" onclick="genScroller()">生成</button>
  </div>`;
  mc.innerHTML += renderGenResult('<div class="empty-hint">填写上方内容后生成</div>');
}
function genScroller() {
  const text = $('sc-text').value.trim() || 'CyanCodex';
  const color = $('sc-color').value;
  const mode = $('sc-mode').value;
  const cmds = GEN.scroller({ text, color, mode, x: 0, y: 0, z: 0, gap: 2 });
  showGenCmds(cmds, null);
  toast('已生成 ' + cmds.length + ' 条指令');
}

// --- 音乐生成器 ---
function renderTMusic(mc) {
  mc.innerHTML = `<div class="section-title">音乐生成器</div>
  <div class="card">
    <div class="field"><label class="small muted">网格 (每行一格音符, 格式: 拍数 乐器 音高0-24)</label>
      <textarea id="mu-grid" rows="8" placeholder="0 harp 12&#10;0 bass 7&#10;2 harp 14&#10;4 harp 12&#10;4 bass 9"></textarea></div>
    <div class="field"><label class="small muted">乐器列表: ${GEN.INSTRUMENTS.join(' ')}</label></div>
    <div class="field"><label class="small muted">循环播放</label>
      <select id="mu-loop"><option value="1">是</option><option value="0">否</option></select></div>
    <button class="btn primary block" onclick="genMusic()">生成</button>
  </div>`;
  mc.innerHTML += renderGenResult('<div class="empty-hint">填写上方内容后生成</div>');
}
function genMusic() {
  const events = [];
  $('mu-grid').value.split('\n').map(l => l.trim()).filter(Boolean).forEach(l => {
    const p = l.split(/\s+/);
    if (p.length >= 3) events.push({ tick: parseInt(p[0]) || 0, instrument: p[1], note: Math.min(24, Math.max(0, parseInt(p[2]) || 0)) });
  });
  const loop = $('mu-loop').value === '1';
  const cmds = GEN.music({ events, loop });
  showGenCmds(cmds, null);
  toast('已生成 ' + cmds.length + ' 条指令');
}

// --- T显JSON ---
function renderTTitleraw(mc) {
  mc.innerHTML = `<div class="section-title">T显 JSON 编辑器</div>
  <div class="card">
    <div class="field-row">
      <div class="field grow"><label class="small muted">目标</label><input id="tt-target" value="@a"></div>
      <div class="field grow"><label class="small muted">位置</label>
        <select id="tt-pos"><option value="title">title</option><option value="subtitle">subtitle</option><option value="actionbar">actionbar</option></select></div>
    </div>
    <div class="field"><label class="small muted">分段 (每行: 文本|颜色 或 文本, 颜色可省)</label>
      <textarea id="tt-segs" rows="5" placeholder="欢迎来到|gold&#10;CyanCodex&#10;点击物品栏切换|aqua"></textarea></div>
    <button class="btn primary block" onclick="genTitleraw()">生成</button>
  </div>`;
  mc.innerHTML += renderGenResult('<div class="empty-hint">填写上方内容后生成</div>');
}
function genTitleraw() {
  const target = $('tt-target').value.trim() || '@a';
  const pos = $('tt-pos').value;
  const segs = $('tt-segs').value.split('\n').map(l => l.trim()).filter(Boolean).map(l => {
    const p = l.split('|');
    return p.length >= 2 ? { kind: 'text', text: p[0], color: p[1].trim() } : { kind: 'text', text: l };
  });
  const segsForGen = segs.map(s => s.color ? { kind: 'color', text: s.text, color: s.color } : { kind: 'text', text: s.text });
  const cmd = GEN.titleraw(target, pos, segsForGen);
  showGenCmds([cmd], null);
}

// ============ AI ============
function renderAi(mc) {
  mc.innerHTML = `<div class="section-title">AI Agent</div>
    <div class="ai-chat" id="ai-list"></div>
    <div class="ai-input-bar">
      <input id="ai-input" placeholder="输入你的问题..." maxlength="500">
      <button class="btn primary" onclick="sendAi()">发送</button>
    </div>`;
  aiList = [];
  addAiMsg('bot', '我是 CyanCodex AI 助手，可以帮你编写我的世界指令。例如：给我一组钻石的指令。');
  const inp = $('ai-input');
  inp.addEventListener('keydown', e => { if (e.key === 'Enter') sendAi(); });
}
function stopAi() { /* 无轮询 */ }
function addAiMsg(role, text) {
  const box = $('ai-list'); if (!box) return;
  const div = document.createElement('div');
  div.className = 'ai-msg ' + role;
  div.innerHTML = `<div class="ai-bubble">${esc(text)}</div>`;
  box.appendChild(div);
  box.scrollTop = box.scrollHeight;
}
async function sendAi() {
  const inp = $('ai-input'); const text = inp.value.trim();
  if (!text) return;
  addAiMsg('user', text);
  inp.value = '';
  addAiMsg('bot', '思考中...');
  const box = $('ai-list');
  const r = await API.aiProxy({ messages: JSON.stringify([{ role: 'user', content: text }]) });
  const bubbles = box.querySelectorAll('.ai-bubble');
  if (r.ok && r.data && r.data.content) {
    bubbles[bubbles.length - 1].textContent = r.data.content;
  } else {
    bubbles[bubbles.length - 1].textContent = (r.error || 'AI 请求失败，请稍后再试');
  }
  box.scrollTop = box.scrollHeight;
}

// ============ 五子棋 ============
const G = {
  SIZE: 15, BLACK: 1, WHITE: 2, EMPTY: 0,
  board: null, myTurn: true, myPiece: 1, over: false, moves: 0,
  mode: 'ai', difficulty: 2, roomId: '', polling: false,
};
function emptyBoard() { return Array.from({ length: G.SIZE }, () => Array(G.SIZE).fill(0)); }
function copyBoard(b) { return b.map(r => r.slice()); }
function inBoard(x, y) { return x >= 0 && x < G.SIZE && y >= 0 && y < G.SIZE; }
function checkWin(b, x, y, player) {
  const dirs = [[1, 0], [0, 1], [1, 1], [1, -1]];
  for (const [dx, dy] of dirs) {
    let count = 1;
    let nx = x + dx, ny = y + dy;
    while (inBoard(nx, ny) && b[nx][ny] === player) { count++; nx += dx; ny += dy; }
    nx = x - dx; ny = y - dy;
    while (inBoard(nx, ny) && b[nx][ny] === player) { count++; nx -= dx; ny -= dy; }
    if (count >= 5) return true;
  }
  return false;
}
function hasNeighbor(b, x, y) {
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    if (inBoard(x + dx, y + dy) && b[x + dx][y + dy] !== G.EMPTY) return true;
  }
  return false;
}
function scoreAt(b, x, y, player) {
  let score = 0;
  const dirs = [[1, 0], [0, 1], [1, 1], [1, -1]];
  for (const [dx, dy] of dirs) {
    let count = 1, openEnds = 0;
    let nx = x + dx, ny = y + dy;
    while (inBoard(nx, ny) && b[nx][ny] === player) { count++; nx += dx; ny += dy; }
    if (inBoard(nx, ny) && b[nx][ny] === G.EMPTY) openEnds++;
    nx = x - dx; ny = y - dy;
    while (inBoard(nx, ny) && b[nx][ny] === player) { count++; nx -= dx; ny -= dy; }
    if (inBoard(nx, ny) && b[nx][ny] === G.EMPTY) openEnds++;
    if (count >= 5) score += 1000000;
    else if (count === 4 && openEnds === 2) score += 100000;
    else if (count === 4 && openEnds === 1) score += 10000;
    else if (count === 3 && openEnds === 2) score += 5000;
    else if (count === 3 && openEnds === 1) score += 800;
    else if (count === 2 && openEnds === 2) score += 200;
    else if (count === 2 && openEnds === 1) score += 50;
    else if (count === 1 && openEnds === 2) score += 10;
  }
  return score;
}
function aiMove(level) {
  const empties = [];
  for (let i = 0; i < G.SIZE; i++) for (let j = 0; j < G.SIZE; j++) if (G.board[i][j] === G.EMPTY) empties.push([i, j]);
  if (!empties.length) return [7, 7];
  if (empties.length === 225) return [7, 7];
  const me = G.myPiece === G.BLACK ? G.WHITE : G.BLACK;
  const opp = G.myPiece;
  let candidates = empties.filter(([x, y]) => hasNeighbor(G.board, x, y));
  if (!candidates.length) candidates = empties;
  if (level === 1 && Math.random() < 0.25) { const p = candidates[Math.floor(Math.random() * candidates.length)]; return p; }
  if (level >= 2) {
    for (const [x, y] of candidates) if (scoreAt(G.board, x, y, me) >= 500000) return [x, y];
    let blockPos = null, blockScore = 0;
    for (const [x, y] of candidates) {
      const s = scoreAt(G.board, x, y, opp);
      if (s >= 500000) return [x, y];
      if (s >= 10000 && s > blockScore) { blockScore = s; blockPos = [x, y]; }
    }
    if (blockPos) return blockPos;
  }
  let best = candidates[0], bestScore = -Infinity;
  const defW = level >= 3 ? 1.2 : 0.9;
  for (const [x, y] of candidates) {
    const atk = scoreAt(G.board, x, y, me);
    const def = scoreAt(G.board, x, y, opp);
    const total = atk + def * defW;
    if (total > bestScore) { bestScore = total; best = [x, y]; }
  }
  return best;
}
function renderGomokuBoard(mc) {
  let html = `<div class="section-title">五子棋</div>`;
  html += `<div class="gomoku-chips">
    <span class="chip ${G.mode==='ai'?'active':''}" onclick="setGomokuMode('ai')">人机对战</span>
    <span class="chip ${G.mode==='match'?'active':''}" onclick="setGomokuMode('match')">匹配对手</span>
    <span class="chip ${G.mode==='rooms'?'active':''}" onclick="setGomokuMode('rooms')">房间大厅</span>
  </div>`;
  if (G.mode === 'ai') {
    html += `<div class="gomoku-chips">
      ${[[1,'简单'],[2,'普通'],[3,'困难'],[4,'老叟戏顽童'],[5,'顽童宗师']].map(([lv, n]) =>
        `<span class="chip ${G.difficulty===lv?'active':''}" onclick="G.difficulty=${lv};renderGomokuBoard($('main-content'))">${n}</span>`).join('')}
    </div>`;
  }
  html += `<div class="gomoku-info" id="g-info">${G.over ? '对局结束' : (G.myTurn ? '轮到你落子' : 'AI 思考中...')} · 第 ${G.moves + 1} 手</div>`;
  html += `<div class="gomoku-board" id="g-board"></div>`;
  html += `<div class="gomoku-actions">
    <button class="btn primary" onclick="gomokuReset()">重新开始</button>
    ${G.mode === 'match' && !G.roomId ? `<button class="btn" onclick="startMatch()">开始匹配</button>` : ''}
    ${G.roomId ? `<button class="btn" onclick="leaveRoom()">退出房间</button>` : ''}
    ${G.mode === 'rooms' && !G.roomId ? `<button class="btn" onclick="showRoomLobby()">房间大厅</button>` : ''}
  </div>`;
  mc.innerHTML = html;
  drawGomokuBoard();
}
function drawGomokuBoard() {
  const el = $('g-board'); if (!el) return;
  const size = el.clientWidth || 300;
  const step = size / G.SIZE;
  let svg = `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" style="display:block">`;
  for (let i = 0; i < G.SIZE; i++) {
    svg += `<line x1="${step/2}" y1="${step/2 + i*step}" x2="${size - step/2}" y2="${step/2 + i*step}" stroke="#8B6B3E" stroke-width="1.5"/>`;
    svg += `<line x1="${step/2 + i*step}" y1="${step/2}" x2="${step/2 + i*step}" y2="${size - step/2}" stroke="#8B6B3E" stroke-width="1.5"/>`;
  }
  [[3,3],[11,3],[3,11],[11,11],[7,7]].forEach(([sx, sy]) => {
    svg += `<circle cx="${step/2 + sx*step}" cy="${step/2 + sy*step}" r="${step*0.08}" fill="#8B6B3E"/>`;
  });
  for (let i = 0; i < G.SIZE; i++) for (let j = 0; j < G.SIZE; j++) {
    if (G.board[i][j] !== G.EMPTY) {
      const c = G.board[i][j] === G.BLACK ? '#1A1A1A' : '#F5F5F5';
      svg += `<circle cx="${step/2 + i*step}" cy="${step/2 + j*step}" r="${step*0.42}" fill="${c}" stroke="#333" stroke-width="1"/>`;
    }
  }
  svg += `</svg>`;
  el.innerHTML = svg;
  el.onclick = (e) => {
    const rect = el.getBoundingClientRect();
    const x = Math.floor((e.clientX - rect.left) / step);
    const y = Math.floor((e.clientY - rect.top) / step);
    if (x >= 0 && x < G.SIZE && y >= 0 && y < G.SIZE) playerMove(x, y);
  };
}
function renderGomoku(mc) {
  if (!G.board) G.board = emptyBoard();
  renderGomokuBoard(mc);
}
function setGomokuMode(mode) {
  G.mode = mode; G.board = emptyBoard(); G.myTurn = true; G.myPiece = G.BLACK;
  G.over = false; G.moves = 0; G.roomId = ''; stopGomoku();
  renderGomokuBoard($('main-content'));
  if (mode === 'rooms') showRoomLobby();
}
function gomokuReset() {
  G.board = emptyBoard(); G.myTurn = true; G.myPiece = G.BLACK;
  G.over = false; G.moves = 0; stopGomoku();
  renderGomokuBoard($('main-content'));
}
function stopGomoku() { if (gomokuTimer) { clearInterval(gomokuTimer); gomokuTimer = null; } G.polling = false; }
function playerMove(x, y) {
  if (G.over || !G.myTurn) return;
  if (G.mode === 'match' && !G.roomId) { toast('请先匹配对手'); return; }
  const b = copyBoard(G.board);
  if (b[x][y] !== G.EMPTY) return;
  b[x][y] = G.myPiece;
  G.board = b; G.moves++;
  if (checkWin(b, x, y, G.myPiece)) { G.over = true; updateGInfo('你赢了!'); return; }
  if (G.moves === 225) { G.over = true; updateGInfo('平局'); return; }
  G.myTurn = false;
  updateGInfo();
  if (G.mode === 'match' && G.roomId) {
    API.gameMove(G.roomId, { x, y }).then(r => { if (r.ok && r.data) applyState(r.data); });
  } else {
    const p = aiMove(G.difficulty);
    setTimeout(() => {
      if (G.over) return;
      const b2 = copyBoard(G.board);
      b2[p[0]][p[1]] = G.myPiece === G.BLACK ? G.WHITE : G.BLACK;
      G.board = b2; G.moves++;
      if (checkWin(b2, p[0], p[1], G.myPiece === G.BLACK ? G.WHITE : G.BLACK)) {
        G.over = true; updateGInfo('AI 胜'); drawGomokuBoard(); return;
      }
      if (G.moves === 225) { G.over = true; updateGInfo('平局'); drawGomokuBoard(); return; }
      G.myTurn = true; updateGInfo(); drawGomokuBoard();
    }, 200);
  }
  drawGomokuBoard();
}
function updateGInfo(msg) {
  const el = $('g-info'); if (!el) return;
  if (msg) el.textContent = msg;
  else el.textContent = `${G.over ? '对局结束' : (G.myTurn ? '轮到你落子' : '等待中...')} · 第 ${G.moves + 1} 手`;
}
function applyState(s) {
  if (s.board && s.board.length === 15) G.board = s.board.map(r => r.slice());
  G.moves = s.moves || 0;
  if (s.over) { G.over = true; updateGInfo('对局结束: ' + (s.winner || '')); }
  else {
    G.myTurn = s.turn === G.myPiece;
    updateGInfo();
  }
  drawGomokuBoard();
}
async function startMatch() {
  toast('匹配中，等待对手...');
  const r = await API.gameMatch({ game: 'gomoku' });
  if (!r.ok) { toast(r.error || '匹配失败'); return; }
  G.polling = true;
  gomokuTimer = setInterval(async () => {
    const s = await API.gameMatchStatus();
    if (s.ok && s.data && s.data.matched) {
      clearInterval(gomokuTimer); gomokuTimer = null;
      G.roomId = s.data.room_id;
      G.myPiece = (s.data.player && s.data.player.piece) || 1;
      G.board = emptyBoard(); G.moves = 0; G.over = false;
      G.myTurn = G.myPiece === 1;
      renderGomokuBoard($('main-content'));
      toast('匹配成功，对局开始');
      pollRoom();
    }
  }, 2000);
}
function pollRoom() {
  stopGomoku();
  G.polling = true;
  gomokuTimer = setInterval(async () => {
    if (!G.roomId) { stopGomoku(); return; }
    const r = await API.gameState(G.roomId);
    if (r.ok && r.data) {
      if (r.data.over) { clearInterval(gomokuTimer); gomokuTimer = null; }
      applyState(r.data);
    } else if (r.error && (r.error.includes('不存在') || r.error.includes('已结束'))) {
      stopGomoku(); G.over = true; updateGInfo('房间已关闭');
    }
  }, 1500);
}
async function leaveRoom() {
  if (G.roomId) { API.gameLeave(G.roomId); }
  stopGomoku(); G.roomId = ''; gomokuReset();
  toast('已退出房间');
}
// 房间大厅
async function showRoomLobby() {
  const mc = $('main-content');
  mc.innerHTML = `<div class="empty-hint">加载中...</div>`;
  const r = await API.gameRooms();
  const list = (r.ok && r.data) ? (Array.isArray(r.data) ? r.data : (r.data.list || [])) : [];
  let html = `<div class="section-title">房间大厅</div>
    <div class="row" style="margin-bottom:10px">
      <input class="grow" id="room-code" style="padding:9px 12px;border-radius:8px;border:1px solid var(--border);background:var(--card2);color:var(--text);outline:none" placeholder="输入房间号加入">
      <button class="btn primary" onclick="joinRoomByCode()">按号加入</button></div>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" onclick="createRoom('pvp')">创建对战房间</button>
      <button class="btn" onclick="createRoom('ai')">创建AI房间</button>
    </div>`;
  if (!list.length) html += `<div class="empty-hint">暂无公开房间</div>`;
  list.forEach(r2 => {
    html += `<div class="list-item">
      <div class="grow"><div class="list-title">房间 ${esc(r2.code)} · ${r2.type === 'ai' ? 'AI(难度' + r2.ai_level + ')' : '真人对战'}</div>
      <div class="list-desc">玩家 ${(r2.players || []).length}/2 · 观战 ${r2.spectator_count || 0} · 手数 ${r2.moves || 0}</div></div>
      <button class="btn sm" onclick="joinRoom('${r2.room_id}', false)">加入</button>
      <button class="btn sm" onclick="joinRoom('${r2.room_id}', true)">观战</button>
    </div>`;
  });
  mc.innerHTML = html;
}
async function createRoom(type) {
  const r = await API.gameCreateRoom({ type, ai_level: 2, public: true });
  if (r.ok && r.data) {
    G.roomId = r.data.room_id; G.mode = 'rooms'; G.myPiece = 1; G.myTurn = true;
    G.board = emptyBoard(); G.moves = 0; G.over = false;
    toast('房间 ' + r.data.code + ' 已创建');
    renderGomokuBoard($('main-content'));
    if (type === 'ai') { G.roomId = r.data.room_id; startAiRoom(); }
    else pollRoom();
  } else toast(r.error || '创建失败');
}
function startAiRoom() {
  stopGomoku();
  G.polling = true;
  gomokuTimer = setInterval(async () => {
    if (!G.roomId) { stopGomoku(); return; }
    const r = await API.gameState(G.roomId);
    if (r.ok && r.data) {
      const s = r.data;
      if (s.board) G.board = s.board.map(row => row.slice());
      G.moves = s.moves || 0;
      if (s.over) { stopGomoku(); G.over = true; updateGInfo('对局结束: ' + (s.winner || '')); drawGomokuBoard(); return; }
      const my = s.my_piece || 0;
      if (s.turn !== my) {
        const am = await API.gameAiMove(G.roomId);
        if (am.ok && am.data) {
          if (am.data.board) G.board = am.data.board.map(row => row.slice());
          G.moves = am.data.moves || 0;
          if (am.data.over) { stopGomoku(); G.over = true; updateGInfo('对局结束: ' + (am.data.winner || '')); }
          else G.myTurn = true;
          drawGomokuBoard();
        }
      } else { G.myTurn = true; drawGomokuBoard(); }
    }
  }, 1500);
}
async function joinRoomByCode() {
  const code = $('room-code').value.trim();
  if (!code) { toast('请输入房间号'); return; }
  const r = await API.gameJoinRoom({ code });
  if (r.ok && r.data) {
    const d = r.data;
    G.roomId = d.room_id; G.mode = 'rooms';
    G.board = emptyBoard(); G.moves = 0; G.over = false;
    if (d.spectator) { toast('已加入房间 ' + d.code + ' 观战'); pollRoom(); }
    else {
      G.myPiece = (d.player && d.player.piece) || 2;
      G.myTurn = G.myPiece === 1;
      toast('已加入房间 ' + d.code + '，你执' + (G.myPiece === 1 ? '黑' : '白'));
      if (d.type === 'ai') startAiRoom(); else pollRoom();
    }
    renderGomokuBoard($('main-content'));
  } else toast(r.error || '加入失败');
}
async function joinRoom(rid, asSpec) {
  const r = await API.gameJoinRoom({ room_id: rid, spectator: asSpec });
  if (r.ok && r.data) {
    const d = r.data;
    G.roomId = d.room_id; G.mode = 'rooms';
    G.board = emptyBoard(); G.moves = 0; G.over = false;
    if (d.spectator) { toast('已进入房间观战'); pollRoom(); }
    else {
      G.myPiece = (d.player && d.player.piece) || 2;
      G.myTurn = G.myPiece === 1;
      toast('已加入房间，你执' + (G.myPiece === 1 ? '黑' : '白'));
      if (d.type === 'ai') startAiRoom(); else pollRoom();
    }
    renderGomokuBoard($('main-content'));
  } else toast(r.error || '加入失败');
}

// ============ 初始化 ============
document.addEventListener('DOMContentLoaded', () => {
  if (!document.getElementById('toast')) {
    const t = document.createElement('div'); t.id = 'toast'; document.body.appendChild(t);
  }
  applyTheme(localStorage.getItem('cc_theme') || 'system');
  if (API.isLogin()) { enterMain(); }
  else { showLoginTab('login'); loadCaptcha(); }
});
