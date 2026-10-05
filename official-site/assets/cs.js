/* CyanCodex 官网 AI 客服 · 前端
   调用后端 https://app.blueworkshop.top/api/cs/* （模型/提示词/开关都在管理端配置）
   不引第三方依赖; 只用文字与内联 SVG, 不使用 emoji */
(function () {
  'use strict';
  var API = 'https://app.blueworkshop.top';
  var history = [];          // [{role:'user'|'assistant',content}]
  var busy = false;
  var opened = false;
  var ready = false;

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  var ICON_CHAT = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-3.8-.8L3 21l1.9-4.9A8.4 8.4 0 0 1 12 3a8.4 8.4 0 0 1 9 8.5z"/></svg>';
  var ICON_CODE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6 4 12l5 6M15 6l5 6-5 6"/></svg>';

  // ---------- 骨架 ----------
  var btn = el('button', '');
  btn.id = 'csc-btn';
  btn.type = 'button';
  btn.setAttribute('aria-label', 'AI 客服');
  btn.innerHTML = ICON_CHAT + '<span class="csc-dot"></span>';

  var panel = el('div', '');
  panel.id = 'csc-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', 'CyanCodex AI 客服');
  panel.innerHTML =
    '<div class="csc-head">' +
      '<span class="csc-logo">' + ICON_CODE + '</span>' +
      '<div><b>沧蓝小助手</b><span>CyanCodex 官方 AI 客服</span></div>' +
      '<button class="csc-close" type="button" aria-label="关闭">×</button>' +
    '</div>' +
    '<div class="csc-body" id="csc-body"></div>' +
    '<div class="csc-foot">' +
      '<div class="csc-input-row">' +
        '<textarea id="csc-in" rows="1" placeholder="问我关于 CyanCodex 的问题…"></textarea>' +
        '<button id="csc-send" type="button">发送</button>' +
      '</div>' +
      '<div class="csc-tip">内容由 AI 生成，可能不准确；账号、积分、封禁等以管理员核实为准。官方群 1065025874 / 892335348，邮箱 cyancodex@blueworkshop.top</div>' +
    '</div>';

  function body() { return panel.querySelector('#csc-body'); }
  function scrollDown() { var b = body(); b.scrollTop = b.scrollHeight; }

  function addMsg(role, text) {
    var me = role === 'user';
    var wrap = el('div', 'csc-msg' + (me ? ' me' : ''));
    wrap.appendChild(el('span', 'csc-av', me ? '我' : 'AI'));
    wrap.appendChild(el('div', 'csc-bubble', esc(text).replace(/\n/g, '<br>')));
    body().appendChild(wrap);
    scrollDown();
    return wrap;
  }
  function addTyping() {
    var wrap = el('div', 'csc-msg');
    wrap.appendChild(el('span', 'csc-av', 'AI'));
    wrap.appendChild(el('div', 'csc-bubble', '<span class="csc-typing"><i></i><i></i><i></i></span>'));
    body().appendChild(wrap);
    scrollDown();
    return wrap;
  }
  function addSugs(list) {
    if (!list || !list.length) return;
    var box = el('div', 'csc-sugs');
    list.forEach(function (q) {
      var b = el('button', 'csc-sug', esc(q));
      b.type = 'button';
      b.onclick = function () { box.remove(); send(q); };
      box.appendChild(b);
    });
    body().appendChild(box);
    scrollDown();
  }

  // ---------- 交互 ----------
  function open() {
    opened = true;
    panel.classList.add('open');
    btn.classList.remove('has-new');
    loadConfig();
    setTimeout(function () { var i = panel.querySelector('#csc-in'); if (i && !busy) i.focus(); }, 150);
  }
  function close() { opened = false; panel.classList.remove('open'); }

  function send(text) {
    var input = panel.querySelector('#csc-in');
    var q = String(text || (input ? input.value : '') || '').trim();
    if (!q || busy) return;
    if (input) { input.value = ''; input.style.height = '42px'; }
    addMsg('user', q);
    history.push({ role: 'user', content: q });
    if (history.length > 12) history = history.slice(-12);
    busy = true;
    var sendBtn = panel.querySelector('#csc-send');
    if (sendBtn) sendBtn.disabled = true;
    var typing = addTyping();

    fetch(API + '/api/cs/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: history }),
    }).then(function (r) {
      return r.json().then(function (j) { return { status: r.status, body: j }; });
    }).then(function (res) {
      typing.remove();
      var reply = (res.body && res.body.ok && res.body.data && res.body.data.reply) || '';
      if (reply) {
        addMsg('assistant', reply);
        history.push({ role: 'assistant', content: reply });
      } else {
        var err = (res.body && res.body.error) || '客服暂时不可用，请稍后再试';
        addMsg('assistant', err);
        history.pop();   // 失败的一轮不留在上下文里
      }
    }).catch(function () {
      typing.remove();
      addMsg('assistant', '网络不太顺畅，没能连上客服。请稍后再试，或到官方群 @ 管理员。');
      history.pop();
    }).then(function () {
      busy = false;
      if (sendBtn) sendBtn.disabled = false;
      if (opened) { var i = panel.querySelector('#csc-in'); if (i) i.focus(); }
    });
  }

  var cfgLoaded = false;
  function loadConfig() {
    if (cfgLoaded || ready) return;
    cfgLoaded = true;
    fetch(API + '/api/cs/config').then(function (r) { return r.json(); }).then(function (j) {
      if (!j || !j.ok) throw new Error('bad');
      var d = j.data || {};
      ready = true;
      if (!d.enabled) {
        addMsg('assistant', 'AI 客服暂时关闭了。有问题可以在官方群里 @ 管理员，或发邮件到 cyancodex@blueworkshop.top。');
        return;
      }
      addMsg('assistant', d.greeting || '你好，我是 CyanCodex 的 AI 客服，有什么可以帮你？');
      addSugs(d.suggestions);
    }).catch(function () {
      cfgLoaded = false;
      addMsg('assistant', '客服暂时连不上，请稍后再试；急事可以直接在官方群 @ 管理员。');
    });
  }

  function mount() {
    document.body.appendChild(btn);
    document.body.appendChild(panel);
    btn.onclick = function () { opened ? close() : open(); };
    panel.querySelector('.csc-close').onclick = close;

    var input = panel.querySelector('#csc-in');
    var sendBtn = panel.querySelector('#csc-send');
    sendBtn.onclick = function () { send(); };
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
    });
    input.addEventListener('input', function () {
      input.style.height = 'auto';
      input.style.height = Math.min(96, input.scrollHeight) + 'px';
    });
    // 首次进入时给一个小红点提示有新客服可用
    setTimeout(function () { if (!opened) btn.classList.add('has-new'); }, 4000);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
})();
