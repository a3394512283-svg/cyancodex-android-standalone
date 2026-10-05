/* CyanCodex 开发者平台 - 前端逻辑 (登录/控制台/密钥/用量/认证/在线调试) */
(function () {
  const TKEY = 'cyx_dev_token';
  const KKEY = 'cyx_dev_key';
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const mask = (k) => k ? k.slice(0, 10) + '••••••••••••••••' : '';

  let challenge = null;
  let captchaId = '';
  let resendTimer = null;
  let ME = null;
  let LOCAL = null;      // 本地运行时的配置 (含本地直登入口)
  let useFormalLogin = false;   // 本地模式下是否切到正式三重验证

  function token() { return localStorage.getItem(TKEY) || ''; }
  function setToken(t) { t ? localStorage.setItem(TKEY, t) : localStorage.removeItem(TKEY); }

  async function api(path, opt) {
    const o = opt || {};
    const headers = Object.assign({ 'Content-Type': 'application/json' }, o.headers || {});
    if (token() && !o.noAuth) headers.Authorization = 'Bearer ' + token();
    const res = await fetch('/api/dev' + path, {
      method: o.method || (o.body ? 'POST' : 'GET'),
      headers,
      body: o.body ? JSON.stringify(o.body) : undefined,
    });
    let json = null;
    try { json = await res.json(); } catch (e) { throw new Error('HTTP ' + res.status); }
    if (!json.ok) {
      if (res.status === 401) { setToken(''); ME = null; refreshWho(); }
      throw new Error(json.error || ('HTTP ' + res.status));
    }
    return json.data;
  }

  // ---------------- 路由 ----------------
  const PAGES = ['home', 'login', 'dash', 'keys', 'usage', 'verify', 'docs', 'play', 'plugin'];
  const NEED_AUTH = ['dash', 'keys', 'usage', 'verify'];

  function currentRoute() {
    const h = (location.hash || '#/home').replace('#/', '');
    return PAGES.includes(h) ? h : 'home';
  }

  async function go() {
    const p = currentRoute();
    if (NEED_AUTH.includes(p) && !token()) {
      location.hash = '#/login';
      return;
    }
    PAGES.forEach((x) => { const el = $('pg-' + x); if (el) el.classList.toggle('active', x === p); });
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
    try {
      if (p === 'dash') await renderDash();
      else if (p === 'keys') await renderKeys();
      else if (p === 'usage') await renderUsage();
      else if (p === 'verify') await renderVerify();
      else if (p === 'docs') await window.DOCS.renderDocs($('docs-nav'), $('docs-body'));
      else if (p === 'plugin') window.DOCS.renderPlugin($('plugin-body'));
      else if (p === 'play') await initPlay();
      else if (p === 'login') {
        if (token()) { location.hash = '#/dash'; return; }
        if (LOCAL && !useFormalLogin) { showLocalPanel(true); }
        else { showLocalPanel(false); newCaptcha(); }
      }
    } catch (e) {
      console.error(e);
    }
  }

  function refreshWho() {
    const el = $('who');
    if (ME) {
      el.textContent = (ME.user.nickname || ME.user.username) + ' · ' + ME.verified_label;
      el.classList.add('on');
    } else {
      el.textContent = '';
      el.classList.remove('on');
    }
  }

  // ---------------- 本地直登 (仅本机运行的这份平台可用) ----------------
  async function detectLocal() {
    try {
      const r = await fetch('/local/config', { cache: 'no-store' });
      const j = await r.json();
      if (j && j.ok && j.local && j.localLogin && j.localLogin.enabled && j.localLogin.loopback) LOCAL = j;
    } catch (e) { /* 线上环境没有这个接口, 正常忽略 */ }
    return LOCAL;
  }

  function showLocalPanel(show) {
    const box = $('login-local');
    const formal = $('login-formal');
    if (!box || !formal) return;
    box.style.display = show ? '' : 'none';
    formal.style.display = show ? 'none' : '';
    if (show && LOCAL) {
      $('ll-account').value = LOCAL.localLogin.account || '';
      $('ll-pass').value = LOCAL.localLogin.password || '';
      if (LOCAL.localLogin.note) $('ll-notice').textContent = LOCAL.localLogin.note + '：可直接登录（仅本机 127.0.0.1 有效，跳过邮箱与站内信验证码）。';
    }
  }

  async function localLogin() {
    const err = $('ll-err');
    err.textContent = '';
    const account = $('ll-account').value.trim();
    const password = $('ll-pass').value;
    if (!account || !password) { err.textContent = '请填写账号和密码'; return; }
    const btn = $('ll-login');
    btn.disabled = true; btn.textContent = '登录中…';
    try {
      const r = await fetch('/local/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ account, password }),
      });
      const j = await r.json();
      if (!j.ok) throw new Error(j.error || ('HTTP ' + r.status));
      setToken(j.data.token);
      await loadMe();
      if (j.data.verified === 'offline') alert(j.data.tip || '后端接口不可达，已使用本地离线令牌');
      location.hash = '#/dash';
    } catch (e) {
      err.textContent = e.message;
    } finally {
      btn.disabled = false; btn.textContent = '本地直接登录';
    }
  }

  // ---------------- 登录 (三因子) ----------------
  async function newCaptcha() {
    try {
      const d = await api('/captcha', { noAuth: true });
      captchaId = d.id;
      $('lg-cap-img').src = d.image;
      $('lg-cap').value = '';
    } catch (e) { $('lg-err1').textContent = '验证码加载失败：' + e.message; }
  }

  async function loginStep1() {
    const err = $('lg-err1');
    err.textContent = '';
    const account = $('lg-account').value.trim();
    const password = $('lg-pass').value;
    const cap = $('lg-cap').value.trim();
    if (!account || !password) { err.textContent = '请填写账号和密码'; return; }
    if (!cap) { err.textContent = '请填写图形验证码'; return; }
    const btn = $('lg-next');
    btn.disabled = true; btn.textContent = '发送验证码中…';
    try {
      const d = await api('/login', { noAuth: true, body: { account, password, captcha_id: captchaId, captcha_text: cap } });
      challenge = d.challenge_id;
      $('lg-email').textContent = d.email;
      $('login-step1').style.display = 'none';
      $('login-step2').style.display = '';
      $('lg-tip2').textContent = d.tip || '';
      $('lg-emailcode').value = ''; $('lg-appcode').value = '';
      $('lg-emailcode').focus();
      startResendCooldown();
    } catch (e) {
      err.textContent = e.message;
      newCaptcha();
    } finally {
      btn.disabled = false; btn.textContent = '下一步';
    }
  }

  function startResendCooldown() {
    let n = 60;
    const btn = $('lg-resend');
    btn.disabled = true;
    clearInterval(resendTimer);
    btn.textContent = '重新发送（' + n + 's）';
    resendTimer = setInterval(() => {
      n--;
      if (n <= 0) { clearInterval(resendTimer); btn.disabled = false; btn.textContent = '重新发送验证码'; }
      else btn.textContent = '重新发送（' + n + 's）';
    }, 1000);
  }

  async function loginVerify() {
    const err = $('lg-err2');
    err.textContent = '';
    const email_code = $('lg-emailcode').value.trim();
    const app_code = $('lg-appcode').value.trim();
    if (!email_code || !app_code) { err.textContent = '两个验证码都要填写'; return; }
    const btn = $('lg-verify');
    btn.disabled = true; btn.textContent = '登录中…';
    try {
      const d = await api('/login/verify', { noAuth: true, body: { challenge_id: challenge, email_code, app_code } });
      setToken(d.token);
      challenge = null;
      await loadMe();
      location.hash = '#/dash';
    } catch (e) {
      err.textContent = e.message;
    } finally {
      btn.disabled = false; btn.textContent = '登录开发者平台';
    }
  }

  async function resend() {
    const tip = $('lg-tip2');
    tip.textContent = '';
    if (!challenge) { $('lg-err2').textContent = '登录会话已失效，请返回上一步重新登录'; return; }
    try {
      const d = await api('/login/resend', { noAuth: true, body: { challenge_id: challenge } });
      tip.textContent = '验证码已重新发送到 ' + d.email + '，App 消息中心也有一条新的站内信。';
      $('lg-err2').textContent = '';
      startResendCooldown();
    } catch (e) { $('lg-err2').textContent = e.message; }
  }

  async function loadMe() {
    ME = await api('/me');
    refreshWho();
    return ME;
  }

  function logout() {
    setToken(''); ME = null; refreshWho();
    challenge = null;
    $('login-step1').style.display = '';
    $('login-step2').style.display = 'none';
    location.hash = '#/home';
  }

  // ---------------- 控制台 ----------------
  async function renderDash() {
    if (!ME) await loadMe();
    const d = ME;
    const dev = d.developer;
    const verifyBadge = d.verified ? '<span class="badge on">已认证开发者</span>'
      : (dev && dev.status === 'pending' ? '<span class="badge wait">认证审核中</span>' : '<span class="badge off">未认证</span>');
    $('dash-body').innerHTML = `
      <div class="glass stat"><div class="k">账号</div><div class="v" style="font-size:19px">${esc(d.user.nickname || d.user.username)}</div>
        <div class="s">${esc(d.user.username)} · ${esc(d.user.email)}　${verifyBadge}</div></div>
      <div class="glass stat"><div class="k">今日调用 / 每日配额</div><div class="v">${d.quota_used_today} / ${d.quota_per_day}</div>
        <div class="s">剩余 ${Math.max(0, d.quota_per_day - d.quota_used_today)} 次，按账号汇总所有密钥</div></div>
      <div class="glass stat"><div class="k">可用密钥</div><div class="v">${d.active_keys} / ${d.max_keys}</div>
        <div class="s"><a href="#/keys">前往密钥管理</a></div></div>
      <div class="glass stat"><div class="k">接口地址</div><div class="v" style="font-size:17px;word-break:break-all">${esc(location.origin)}/api/v1</div>
        <div class="s"><a href="#/docs">查看文档</a> · <a href="#/play">在线调试</a></div></div>
      <div class="glass pad" style="grid-column:1/-1">
        <h3 style="margin-top:0">快速开始</h3>
        <p class="hint">1. 在「API 密钥」页创建密钥（只显示一次，请立即保存）　2. 按文档调用接口　3. 在「用量统计」查看调用情况</p>
        <pre class="code">curl -X POST ${esc(location.origin)}/api/v1/gen/scroller \\
  -H "X-API-Key: cyx_你的密钥" -H "Content-Type: application/json" \\
  -d '{"text":"欢迎来到服务器 今晚八点活动","mode":"roll","color":"gold"}'</pre>
        <p class="hint">登录方式：沧蓝工坊账号密码 + 图形验证码 + 绑定邮箱验证码 + App 站内信验证码。密钥泄露时可随时停用或重新生成。</p>
        <button class="btn danger small" id="dash-logout">退出登录</button>
      </div>`;
    $('dash-logout').onclick = logout;
  }

  // ---------------- 密钥 ----------------
  function keyRows(list) {
    if (!list.length) return '<tr><td colspan="7" class="hint">还没有密钥，点「新建密钥」创建一个</td></tr>';
    return list.map((k) => `<tr>
      <td>${esc(k.name)}</td>
      <td><code>${esc(k.prefix)}…</code></td>
      <td>${k.status === 'active' ? '<span class="badge on">启用</span>' : '<span class="badge off">停用</span>'}</td>
      <td>${k.used_today} / ${k.daily_quota}</td>
      <td>${k.total_calls}</td>
      <td>${k.last_used_at ? esc(String(k.last_used_at).replace('T', ' ').slice(0, 16)) : '—'}</td>
      <td>
        <button class="btn small" data-act="toggle" data-id="${k.id}">${k.status === 'active' ? '停用' : '启用'}</button>
        <button class="btn small" data-act="regen" data-id="${k.id}">重置</button>
        <button class="btn small danger" data-act="del" data-id="${k.id}">删除</button>
      </td></tr>`).join('');
  }

  async function renderKeys() {
    const d = await api('/keys');
    $('key-hint').textContent = `已用 ${d.list.length} / ${d.max_keys} 个`;
    const tb = $('key-table').querySelector('tbody');
    tb.innerHTML = keyRows(d.list);
    tb.querySelectorAll('button[data-act]').forEach((b) => {
      b.onclick = async () => {
        const id = b.dataset.id;
        try {
          if (b.dataset.act === 'toggle') await api('/keys/' + id + '/toggle', { body: {} });
          else if (b.dataset.act === 'del') {
            if (!confirm('删除后使用该密钥的程序会立即失效，确定删除？')) return;
            await api('/keys/' + id, { method: 'DELETE' });
          } else if (b.dataset.act === 'regen') {
            if (!confirm('重置后旧密钥立即失效，确定重置？')) return;
            const r = await api('/keys/' + id + '/regenerate', { body: {} });
            showPlain(r.key);
          }
          await renderKeys();
          if (ME) { ME.active_keys = (await api('/me')).active_keys; }
        } catch (e) { alert(e.message); }
      };
    });
  }

  function showPlain(k) {
    $('key-result').style.display = '';
    $('key-plain').textContent = k;
    $('key-copy').onclick = () => {
      navigator.clipboard.writeText(k).then(() => { $('key-copy').textContent = '已复制'; setTimeout(() => $('key-copy').textContent = '复制', 1500); });
    };
  }

  async function createKey() {
    const name = $('key-name').value.trim();
    try {
      const d = await api('/keys', { body: { name } });
      showPlain(d.key);
      $('key-name').value = '';
      await renderKeys();
    } catch (e) { alert(e.message); }
  }

  // ---------------- 用量 ----------------
  async function renderUsage() {
    const days = $('usage-days').value;
    const d = await api('/usage?days=' + days);
    const logs = await api('/logs?limit=30');
    $('usage-sum').textContent = `区间调用 ${d.series.reduce((a, b) => a + b.n, 0)} 次 · 今日 ${d.today} 次 · 累计 ${d.total} 次 · 失败 ${d.failed} 次`;
    const max = Math.max(1, ...d.series.map((x) => x.n));
    $('usage-chart').innerHTML = d.series.map((x) => `
      <div class="col" title="${x.day}: ${x.n} 次">
        <span class="num">${x.n || ''}</span>
        <div class="bar" style="height:${Math.round((x.n / max) * 100)}%"></div>
        <span class="day">${x.day.slice(5)}</span>
      </div>`).join('');
    $('usage-ep').querySelector('tbody').innerHTML = d.by_endpoint.length
      ? d.by_endpoint.map((e) => `<tr><td><code>${esc(e.endpoint)}</code></td><td>${e.n}</td></tr>`).join('')
      : '<tr><td colspan="2" class="hint">暂无调用</td></tr>';
    $('usage-logs').querySelector('tbody').innerHTML = logs.list.length
      ? logs.list.map((l) => `<tr><td>${esc(String(l.created_at).replace('T', ' ').slice(5, 19))}</td>
          <td><code>${esc(l.endpoint)}</code></td>
          <td>${l.ok ? '<span class="badge on">成功</span>' : '<span class="badge off">失败</span>'}</td>
          <td>${l.ms} ms</td></tr>`).join('')
      : '<tr><td colspan="4" class="hint">暂无调用</td></tr>';
  }

  // ---------------- 开发者认证 ----------------
  async function renderVerify() {
    if (!ME) await loadMe();
    const dev = ME.developer;
    if (dev && dev.status === 'approved') {
      $('verify-body').innerHTML = `<div class="glass pad">
        <h3 style="margin-top:0">已认证开发者</h3>
        <p>认证时间：${esc(String(dev.reviewed_at || dev.created_at).slice(0, 16))}　每日配额：<b>${dev.api_quota}</b> 次</p>
        <p class="hint">${esc(dev.review_note || '无审核备注')}</p>
        <p class="hint">如需提升配额或修改资料，请通过 App 意见反馈联系我们。</p>
      </div>`;
      return;
    }
    const pending = dev && dev.status === 'pending';
    $('verify-body').innerHTML = `<div class="glass pad">
      <h3 style="margin-top:0">${pending ? '认证审核中' : '申请认证开发者'}</h3>
      <p class="hint">${pending
        ? '你已提交申请，管理员审核通过后会收到 App 站内信通知，同时每日配额自动提升。'
        : '认证开发者可获得更高配额与限流阈值，并可在平台展示你的插件/工具。审核结果通过 App 站内信通知。'}</p>
      <label>申请说明（准备开发什么插件或工具，至少 10 个字）</label>
      <textarea id="vf-reason" rows="4" placeholder="例如：为我的服务器开发一个公告系统，用 API 生成滚动字幕指令"></textarea>
      <label>开发方向</label>
      <input id="vf-direction" placeholder="如：服务器管理插件 / 网页工具 / 机器人">
      <label>项目或主页地址（可选）</label>
      <input id="vf-home" placeholder="https://...">
      <label>联系 QQ（可选）</label>
      <input id="vf-qq" placeholder="12345678">
      <label>备用邮箱（可选）</label>
      <input id="vf-email" placeholder="backup@example.com">
      <button class="btn primary" id="vf-submit">${pending ? '重新提交申请' : '提交申请'}</button>
      <p class="msg" id="vf-msg"></p>
    </div>`;
    $('vf-submit').onclick = async () => {
      const msg = $('vf-msg');
      msg.className = 'msg'; msg.textContent = '';
      try {
        await api('/apply', {
          body: {
            reason: $('vf-reason').value.trim(),
            direction: $('vf-direction').value.trim(),
            homepage: $('vf-home').value.trim(),
            contact_qq: $('vf-qq').value.trim(),
            backup_email: $('vf-email').value.trim(),
          },
        });
        msg.className = 'msg ok'; msg.textContent = '已提交，请等待管理员审核（结果通过 App 站内信通知）';
        ME = null; await loadMe(); await renderVerify();
      } catch (e) { msg.className = 'msg err'; msg.textContent = e.message; }
    };
  }

  // ---------------- 在线调试 ----------------
  let playReady = false;
  async function initPlay() {
    $('play-key').value = $('play-key').value || localStorage.getItem(KKEY) || '';
    if (playReady) return;
    let m;
    try { m = await window.DOCS.meta(); } catch (e) { return; }
    const sel = $('play-api');
    sel.innerHTML = (m.endpoints || []).map((e) => `<option value="${esc(e.path)}">${esc(e.title)} — ${esc(e.path)}</option>`).join('')
      + '<option value="/api/v1/me">查询当前密钥信息 — /api/v1/me</option>';
    sel.onchange = () => fillExample();
    await fillExample();
    playReady = true;
  }

  async function fillExample() {
    const path = $('play-api').value;
    const m = await window.DOCS.meta();
    const ep = (m.endpoints || []).find((e) => e.path === path);
    $('play-args').value = ep ? JSON.stringify(ep.example, null, 2) : '{}';
  }

  async function runPlay() {
    const key = $('play-key').value.trim();
    const path = $('play-api').value;
    const out = $('play-out');
    const msg = $('play-msg');
    msg.className = 'msg'; msg.textContent = '';
    if (!key) { msg.className = 'msg err'; msg.textContent = '请先填写 API 密钥'; return; }
    localStorage.setItem(KKEY, key);
    const m = await window.DOCS.meta().catch(() => null);
    const ep = m && (m.endpoints || []).find((e) => e.path === path);
    const isGet = path === '/api/v1/me' || (ep && String(ep.method || 'POST').toUpperCase() === 'GET');
    let body;
    let url = path;
    if (!isGet) {
      try { body = JSON.parse($('play-args').value || '{}'); }
      catch (e) { msg.className = 'msg err'; msg.textContent = '参数不是合法 JSON：' + e.message; return; }
    } else if (path !== '/api/v1/me') {
      // GET 接口: 把 JSON 参数拼成查询串
      try {
        const q = JSON.parse($('play-args').value || '{}');
        const qs = Object.keys(q)
          .filter((k) => q[k] !== '' && q[k] !== undefined && q[k] !== null)
          .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(q[k])}`).join('&');
        if (qs) url += '?' + qs;
      } catch (e) { msg.className = 'msg err'; msg.textContent = '参数不是合法 JSON：' + e.message; return; }
    }
    out.textContent = '请求中…';
    try {
      const res = await fetch(url, {
        method: isGet ? 'GET' : 'POST',
        headers: { 'X-API-Key': key, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      });
      const j = await res.json();
      if (j.ok && j.data && j.data.commands) {
        out.textContent = 'HTTP ' + res.status + ' · 共 ' + j.data.count + ' 条指令\n\n' + j.data.commands.join('\n');
        msg.className = 'msg ok'; msg.textContent = '调用成功';
      } else if (j.ok && j.data && j.data.results) {
        out.textContent = 'HTTP ' + res.status + '\n\n' + j.data.results.map((r) => '--- #' + r.index + ' ' + r.generator + ' ---\n' + (r.commands || []).join('\n')).join('\n\n');
        msg.className = 'msg ok'; msg.textContent = '调用成功';
      } else if (j.ok && j.data && Array.isArray(j.data.list)) {
        // 指令库查询: 列表 + 计费/额度回执
        const head = 'HTTP ' + res.status + ' · 命中 ' + j.data.total + ' 条 · 本次扣 ' + (j.data.cost || 0) + ' 积分 · 余额 ' + j.data.balance
          + ' · 今日 ' + (j.data.quota ? j.data.quota.used + '/' + j.data.quota.limit : '-') + '\n\n';
        out.textContent = head + j.data.list.map((c) => `#${c.id} [${c.category || '-'}] ${c.title}${c.locked ? ' (付费指令, 不下发内容)' : ''}\n    ${c.command}`).join('\n');
        msg.className = 'msg ok'; msg.textContent = '调用成功';
      } else {
        out.textContent = 'HTTP ' + res.status + '\n\n' + JSON.stringify(j, null, 2);
        msg.className = j.ok ? 'msg ok' : 'msg err';
        msg.textContent = j.ok ? '调用成功' : ('失败：' + j.error);
      }
    } catch (e) {
      out.textContent = '请求失败：' + e.message;
      msg.className = 'msg err'; msg.textContent = '请求失败';
    }
  }

  // ---------------- 绑定 ----------------
  function bind() {
    if ($('ll-login')) $('ll-login').onclick = localLogin;
    if ($('ll-toggle')) $('ll-toggle').onclick = (e) => { e.preventDefault(); useFormalLogin = true; showLocalPanel(false); newCaptcha(); };
    if ($('ll-pass')) $('ll-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') localLogin(); });
    $('lg-next').onclick = loginStep1;
    $('lg-verify').onclick = loginVerify;
    $('lg-resend').onclick = resend;
    $('lg-cap-img').onclick = newCaptcha;
    $('lg-cap').addEventListener('keydown', (e) => { if (e.key === 'Enter') loginStep1(); });
    $('lg-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') loginStep1(); });
    $('lg-appcode').addEventListener('keydown', (e) => { if (e.key === 'Enter') loginVerify(); });
    $('key-new').onclick = () => {
      const box = $('key-newbox');
      box.style.display = box.style.display === 'none' ? '' : 'none';
      $('key-result').style.display = 'none';
    };
    $('key-create').onclick = createKey;
    $('usage-refresh').onclick = renderUsage;
    $('usage-days').onchange = renderUsage;
    $('play-run').onclick = runPlay;
    $('play-preset').onclick = fillExample;
    window.addEventListener('hashchange', go);
  }

  async function boot() {
    bind();
    await detectLocal();
    if (token()) {
      try { await loadMe(); } catch (e) { /* token 失效已清理 */ }
    }
    await go();
  }

  boot();
})();
