// CyanCodex Web - API 封装 (与 App 数据互通, 同源反代 /api -> 后端 6579)
const API = (() => {
  const BASE = '';
  let token = localStorage.getItem('cc_token') || '';
  let user = null;
  try { user = JSON.parse(localStorage.getItem('cc_user') || 'null'); } catch (e) {}

  async function req(method, path, body) {
    const headers = { 'Accept': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let resp;
    try {
      resp = await fetch(BASE + path, {
        method, headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
      });
    } catch (e) {
      return { ok: false, error: '网络错误: ' + (e.message || e) };
    }
    let data = null;
    try { data = await resp.json(); } catch (e) {}
    if (!resp.ok && data && data.ok === false) return data;
    if (!resp.ok) return { ok: false, error: 'HTTP ' + resp.status };
    return data || { ok: false, error: '空响应' };
  }

  function qs(obj) {
    const p = new URLSearchParams();
    Object.entries(obj).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') p.set(k, v); });
    const s = p.toString();
    return s ? '?' + s : '';
  }

  return {
    get token() { return token; },
    get user() { return user; },
    isLogin() { return !!token; },
    setLogin(t, u) { token = t; user = u; localStorage.setItem('cc_token', t); localStorage.setItem('cc_user', JSON.stringify(u)); },
    setUser(u) { user = u; localStorage.setItem('cc_user', JSON.stringify(u)); },
    logout() { token = ''; user = null; localStorage.removeItem('cc_token'); localStorage.removeItem('cc_user'); },

    // 认证
    login(u, p) { return req('POST', '/api/auth/login', { username: u, password: p }); },
    register(body) { return req('POST', '/api/auth/register', body); },
    captcha() { return req('GET', '/api/auth/captcha'); },
    sendEmailCode(email) { return req('POST', '/api/auth/send-email-code', { email }); },
    me() { return req('GET', '/api/auth/me'); },
    updateProfile(body) { return req('PUT', '/api/auth/profile', body); },
    changePassword(body) { return req('PUT', '/api/auth/password', body); },

    // 公告
    announcements(type) { return req('GET', '/api/announcements' + qs({ type: type || 'all' })); },

    // 指令库
    commands(q, category, page) {
      return req('GET', '/api/commands' + qs({ q: q || '', category: category || 'all', page: page || 1, size: 20 }));
    },
    command(id) { return req('GET', '/api/commands/' + id); },
    categories() { return req('GET', '/api/commands/meta/categories'); },
    uploadCommand(body) { return req('POST', '/api/commands', body); },
    updateCommand(id, body) { return req('PUT', '/api/commands/' + id, body); },
    deleteCommand(id) { return req('DELETE', '/api/commands/' + id); },
    toggleFav(id) { return req('POST', '/api/commands/' + id + '/favorite'); },
    like(id) { return req('POST', '/api/commands/' + id + '/like'); },
    favList() { return req('GET', '/api/commands/favorites/list'); },
    comments(cid) { return req('GET', '/api/comments/command/' + cid); },
    addComment(cid, body) { return req('POST', '/api/comments/command/' + cid, body); },

    // 聊天室
    chatMessages(after) { return req('GET', '/api/chat' + qs({ after: after || 0 })); },
    chatSend(body) { return req('POST', '/api/chat', body); },
    chatReport(body) { return req('POST', '/api/chat/report', body); },

    // 站内信
    messages() { return req('GET', '/api/messages'); },
    unreadCount() { return req('GET', '/api/messages/unread-count'); },
    readMessage(id) { return req('POST', '/api/messages/' + id + '/read'); },

    // 好友/私信
    dmConversations() { return req('GET', '/api/dm/conversations'); },
    dmMessages(peerId, after) { return req('GET', '/api/dm/messages' + qs({ peer_id: peerId, after: after || 0 })); },
    dmSend(body) { return req('POST', '/api/dm/messages', body); },
    friendRequest(body) { return req('POST', '/api/friends/request', body); },
    friendHandle(body) { return req('POST', '/api/friends/handle', body); },
    friendRemove(body) { return req('POST', '/api/friends/remove', body); },
    dmHandle(body) { return req('POST', '/api/dm/handle', body); },

    // 社区/用户主页
    userProfile(id) { return req('GET', '/api/users/' + id); },
    userCommands(id) { return req('GET', '/api/users/' + id + '/commands'); },
    followUser(id) { return req('POST', '/api/users/' + id + '/follow'); },
    unfollowUser(id) { return req('DELETE', '/api/users/' + id + '/follow'); },
    feed() { return req('GET', '/api/feed'); },

    // 签到/积分
    checkin() { return req('POST', '/api/checkin'); },
    checkinStatus() { return req('GET', '/api/checkin'); },
    points() { return req('GET', '/api/points'); },
    transferPoints(body) { return req('POST', '/api/points/transfer', body); },

    // AI
    aiStatus() { return req('GET', '/api/ai/status'); },
    aiProxy(body) { return req('POST', '/api/ai/proxy', body); },
    aiHistory() { return req('GET', '/api/ai/history?page=1&size=30'); },

    // 意见反馈
    submitFeedback(body) { return req('POST', '/api/feedback', body); },

    // 更新
    latestVersion() { return req('GET', '/api/update/latest'); },

    // 五子棋 (同 App)
    gameMatch(body) { return req('POST', '/api/game/match', body); },
    gameMatchStatus() { return req('GET', '/api/game/match/status'); },
    gameRooms() { return req('GET', '/api/game/rooms'); },
    gameCreateRoom(body) { return req('POST', '/api/game/rooms', body); },
    gameJoinRoom(body) { return req('POST', '/api/game/rooms/join', body); },
    gameKick(rid, body) { return req('POST', '/api/game/' + rid + '/kick', body); },
    gameAiMove(rid) { return req('POST', '/api/game/' + rid + '/ai-move'); },
    gameState(rid) { return req('GET', '/api/game/' + rid + '/state'); },
    gameMove(rid, body) { return req('POST', '/api/game/' + rid + '/move', body); },
    gameLeave(rid) { return req('POST', '/api/game/' + rid + '/leave'); },
  };
})();
