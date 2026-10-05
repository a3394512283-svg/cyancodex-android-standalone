/* CyanCodex 开发者平台 - API 文档 & 插件开发教学 */
(function () {
  const BASE = location.origin;
  let META = null;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  async function meta() {
    if (META) return META;
    const r = await fetch('/api/v1/meta');
    const j = await r.json();
    META = j.data;
    return META;
  }

  // ---------------- 示例代码 ----------------
  function isGet(ep) { return String(ep.method || 'POST').toUpperCase() === 'GET'; }

  function queryString(ep) {
    const q = ep.example || {};
    return Object.keys(q).map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(q[k])}`).join('&');
  }

  function curlSample(ep) {
    if (isGet(ep)) {
      const qs = queryString(ep);
      return `curl "${BASE}${ep.path}${qs ? '?' + qs : ''}" \\
  -H "X-API-Key: cyx_你的密钥"`;
    }
    return `curl -X POST ${BASE}${ep.path} \\
  -H "X-API-Key: cyx_你的密钥" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(ep.example)}'`;
  }

  // 生成器接口返回 data.commands 数组, AI 接口返回 data.content 文本, 指令库查询返回 data.list
  function outExpr(ep) {
    if (ep.output === 'content') return 'json.data.content';
    if (ep.output === 'list') return 'json.data.total + " 条 · 本次扣 " + json.data.cost + " 积分 · 余额 " + json.data.balance';
    return 'json.data.commands.join("\\n")';
  }

  function jsSample(ep) {
    if (isGet(ep)) {
      const qs = queryString(ep);
      return `const res = await fetch("${BASE}${ep.path}${qs ? '?' + qs : ''}", {
  headers: { "X-API-Key": "cyx_你的密钥" }
});
const json = await res.json();
if (json.ok) console.log(${outExpr(ep)});
else console.error(json.error);`;
    }
    return `const res = await fetch("${BASE}${ep.path}", {
  method: "POST",
  headers: { "X-API-Key": "cyx_你的密钥", "Content-Type": "application/json" },
  body: JSON.stringify(${JSON.stringify(ep.example, null, 2)})
});
const json = await res.json();
if (json.ok) console.log(${outExpr(ep)});
else console.error(json.error);`;
  }

  function pySample(ep) {
    const out = ep.output === 'content' ? 'data["data"]["content"]'
      : (ep.output === 'list' ? 'data["data"]["total"], data["data"]["list"]' : '"\\n".join(data["data"]["commands"])');
    if (isGet(ep)) {
      return `import requests

r = requests.get("${BASE}${ep.path}",
    headers={"X-API-Key": "cyx_你的密钥"},
    params=${JSON.stringify(ep.example || {}).replace(/true/g, 'True').replace(/false/g, 'False')},
    timeout=30)
data = r.json()
print(${out} if data.get("ok") else data["error"])`;
    }
    return `import requests

r = requests.post("${BASE}${ep.path}",
    headers={"X-API-Key": "cyx_你的密钥"},
    json=${JSON.stringify(ep.example).replace(/true/g, 'True').replace(/false/g, 'False')},
    timeout=30)
data = r.json()
print(${out} if data.get("ok") else data["error"])`;
  }

  function paramTable(ep) {
    const rows = (ep.params || []).map((p) => `<tr>
      <td><code>${esc(p.name)}</code></td><td>${esc(p.type)}</td>
      <td>${p.def === undefined ? '' : '<code>' + esc(typeof p.def === 'object' ? JSON.stringify(p.def) : p.def) + '</code>'}</td>
      <td>${esc(p.desc)}</td></tr>`).join('');
    return `<div class="tablewrap"><table class="params"><thead><tr><th>参数</th><th>类型</th><th>默认</th><th>说明</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }

  // 单个接口的文档块
  function epBlock(e) {
    return `
      <h4 id="ep-${esc(e.id)}">${esc(e.title)}</h4>
      <div class="glass">
        <div class="ep"><span class="method">${esc(e.method || 'POST')}</span><span class="path">${esc(e.path)}</span><span class="tag">${esc(e.id)}</span></div>
        <p>${esc(e.desc)}</p>
        ${paramTable(e)}
        <p><b>请求示例</b></p>
        <pre class="code">${esc(curlSample(e))}</pre>
        <p><b>JavaScript</b></p>
        <pre class="code">${esc(jsSample(e))}</pre>
        <p><b>Python</b></p>
        <pre class="code">${esc(pySample(e))}</pre>
        <p>也可以直接在 <a href="#/play">在线调试</a> 里试。</p>
      </div>`;
  }

  // ---------------- API 文档 ----------------
  async function renderDocs(navEl, bodyEl) {
    let m;
    try { m = await meta(); } catch (e) {
      bodyEl.innerHTML = '<p class="err">文档加载失败：' + esc(e.message) + '</p>';
      return;
    }
    const eps = m.endpoints || [];
    const ais = eps.filter((e) => e.output === 'content');                        // AI 类接口 (返回 content 文本)
    const libs = eps.filter((e) => e.output === 'list');                          // 指令库查询 (返回 list)
    const gens = eps.filter((e) => e.output !== 'content' && e.output !== 'list'); // 生成器接口 (返回 commands 数组)
    const bill = m.billing || {};

    navEl.innerHTML = `
      <div class="grp">开始</div>
      <a href="#/docs" data-doc="intro">概述与鉴权</a>
      <a href="#/docs" data-doc="errors">返回格式与错误码</a>
      <a href="#/docs" data-doc="limits">限流与配额</a>
      <div class="grp">生成器接口</div>
      ${gens.map((e) => `<a href="#/docs" data-doc="ep-${e.id}">${esc(e.title)}</a>`).join('')}
      <div class="grp">指令库接口</div>
      ${libs.map((e) => `<a href="#/docs" data-doc="ep-${e.id}">${esc(e.title)}</a>`).join('')}
      <div class="grp">AI 接口</div>
      ${ais.map((e) => `<a href="#/docs" data-doc="ep-${e.id}">${esc(e.title)}</a>`).join('')}
      <div class="grp">其它</div>
      <a href="#/docs" data-doc="meta">GET /api/v1/meta</a>
      <a href="#/docs" data-doc="me">GET /api/v1/me</a>
      <a href="#/docs" data-doc="notes">基岩版注意事项</a>`;

    const instr = (m.instruments || []).map((i) => `<code>${esc(i)}</code>`).join(' ');
    const colors = Object.keys(m.colors || {}).map((k) => `<code>${esc(k)}=${esc(m.colors[k])}</code>`).join(' ');

    // 版本快照：把"这一版承诺了哪些接口、什么时候定稿"直接摆在页面上。
    // 后端没给 snapshot 字段时（旧版后端）整段不显示 —— 不能显示成 undefined。
    const snap = m.snapshot || null;
    const snapshotLine = snap
      ? `　<b>本版快照</b>：<code>${esc(snap.generated_at || '')}</code>`
        + ` · 共 ${esc(String((m.endpoints || []).length))} 个接口`
        + `（${(m.editions || []).map((e) => esc(e)).join(' / ')}）`
      : '';

    bodyEl.innerHTML = `
      <h3 id="intro">概述与鉴权</h3>
      <div class="glass">
        <p><b>基础地址</b>：<code>${esc(BASE)}</code>，所有接口以 <code>/api/v1</code> 开头。<br>
        <b>当前版本</b>：<code>${esc(m.version)}</code>${snapshotLine}<br>
        <b>输出语法</b>：<code>edition=bedrock</code>（基岩版，默认）或 <code>edition=java</code>（Java 版 1.13+，已验证 1.20.1 / 1.21.x）</p>
        <p><b>鉴权方式</b>（两种任选其一）：</p>
        <pre class="code">X-API-Key: cyx_xxxxxxxxxxxxxxxx
Authorization: Bearer cyx_xxxxxxxxxxxxxxxx</pre>
        <p>密钥在 <a href="#/keys">API 密钥</a> 页面创建，仅在创建时显示一次，服务端只保存 SHA-256 摘要，无法找回，丢失请重新生成。</p>
        <p>所有生成器接口都是 <span class="method">POST</span>，请求体为 JSON。响应统一为：</p>
        <pre class="code">{
  "ok": true,
  "data": {
    "generator": "scoreboard",
    "edition": "bedrock",     // bedrock（默认）或 java
    "args": { ... },          // 服务端最终采用的参数（已做长度/范围收敛）
    "commands": [ "/...", "/..." ],
    "count": 2,
    "truncated": false        // 超过 ${esc(m.limits.max_output)} 条时会被截断
  }
}</pre>
      </div>

      <h3 id="errors">返回格式与错误码</h3>
      <div class="glass">
        <p>失败时返回 <code>{"ok":false,"error":"中文原因"}</code>，HTTP 状态码：</p>
        <div class="tablewrap"><table><thead><tr><th>状态码</th><th>含义</th><th>处理建议</th></tr></thead><tbody>
          <tr><td>400</td><td>参数不足/无法识别</td><td>检查必填项，如 template、text、events</td></tr>
          <tr><td>401</td><td>缺少或无效的 API 密钥</td><td>检查请求头 X-API-Key 是否完整</td></tr>
          <tr><td>402</td><td>积分不足（计费接口）</td><td>响应里带 <code>price</code> 与 <code>balance</code>，到 App 内充值积分后重试</td></tr>
          <tr><td>403</td><td>密钥被停用 / 已过期 / 账号封禁</td><td>到控制台重新生成密钥</td></tr>
          <tr><td>429</td><td>超出每分钟限流或每日配额</td><td>退避重试；<code>quota</code> 字段给出上限与已用次数</td></tr>
          <tr><td>500</td><td>服务器内部错误</td><td>稍后重试；持续失败请反馈</td></tr>
          <tr><td>503</td><td>该接口暂时关闭</td><td>管理员关闭了此功能，稍后再试</td></tr>
        </tbody></table></div>
      </div>

      <h3 id="limits">限流与配额</h3>
      <div class="glass">
        <p><b>生成器 / AI 接口</b>：每分钟 60 次，每日 2000 次（按密钥独立计算，认证开发者由管理员按需上调）。</p>
        <p><b>指令库查询接口</b>（<code>GET /api/v1/commands</code>）：按账号等级给额度 ——
          普通账号每日 <b>${esc(bill.quota_free !== undefined ? bill.quota_free : 100)}</b> 次，
          VIP / 超级用户每日 <b>${esc(bill.quota_vip !== undefined ? bill.quota_vip : 2000)}</b> 次；
          该接口<b>按次计费</b>，每次调用从密钥归属账号扣
          <b>${esc(bill.price_library !== undefined ? bill.price_library : 1)}</b> 积分（单价与额度都由管理员在管理端配置），
          余额不足返回 <code>402</code>。响应里的 <code>cost</code> / <code>balance</code> / <code>quota</code> 会告诉你本次扣费与今日剩余次数。</p>
        <p>单次请求输入上限：文本 ${esc(m.limits.max_text)} 字符、穷举变量 ${esc(m.limits.max_variables)} 个（每个最多 ${esc(m.limits.max_values)} 个候选值）、
        玩家分数 ${esc(m.limits.max_entries)} 条、音符 ${esc(m.limits.max_events)} 个、rawtext 元素 ${esc(m.limits.max_segments)} 个、单次输出最多 ${esc(m.limits.max_output)} 条指令。</p>
        <p>查看自己的实时用量：<code>GET /api/v1/me</code>，或在 <a href="#/usage">用量统计</a> 页面查看图表。</p>
      </div>

      <h3>生成器接口</h3>
      ${gens.map(epBlock).join('')}

      <h3>指令库接口</h3>
      <div class="glass">
        <p>查询 CyanCodex 指令库里<b>已上架</b>的指令（官方收录 + 用户上传），支持关键字、分类、版本筛选与分页。
          这个接口是<span class="method">GET</span>，参数放在查询串里。</p>
        <p><b>计费与额度</b>：每次调用扣 <b>${esc(bill.price_library !== undefined ? bill.price_library : 1)}</b> 积分
          （记在密钥归属账号上，写入积分流水）；普通账号每日 ${esc(bill.quota_free !== undefined ? bill.quota_free : 100)} 次，
          VIP / 超级用户每日 ${esc(bill.quota_vip !== undefined ? bill.quota_vip : 2000)} 次。余额不足返回 <code>402</code>。</p>
        <p><b>付费指令</b>：指令库里需要积分解锁的指令，本接口只返回标题、说明、分类等信息，
          <code>command</code> 为空且 <code>locked: true</code>，不会下发指令内容（与 App 内的展示口径一致）。</p>
        <pre class="code">{
  "ok": true,
  "data": {
    "list": [
      { "id": 128, "title": "一键清理掉落物", "description": "……", "command": "kill @e[type=item]",
        "category": "survival", "source": "user", "mc_version": "1.21", "tags": "清理,掉落物",
        "price": 0, "locked": false, "like_count": 12, "view_count": 340, "author": "测试员" }
    ],
    "total": 142,          // 命中总数
    "page": 1,
    "size": 20,            // 每页条数, 最大 50
    "source": "all",       // 本次实际使用的数据来源
    "cost": 1,             // 本次扣的积分
    "balance": 993,        // 扣费后账号余额
    "quota": { "limit": 100, "used": 7, "remaining": 93, "vip": false }
  }
}</pre>
      </div>
      ${libs.map(epBlock).join('')}

      <h3>AI 接口</h3>
      <div class="glass">
        <p>AI 接口与生成器接口<b>共用同一把密钥、同一套限流与每日配额</b>，区别在计费：AI 按积分扣费
          （基础 ${esc(m.ai && m.ai.base_cost !== undefined ? m.ai.base_cost : 5)} 积分 + 每 1000 tokens 扣 1 积分），
          账单记在该密钥<b>归属账号</b>上，余额不足返回 <code>400</code> 与中文提示。</p>
        <p>模型、知识库与 App 内完全一致（官方指令文档 + 社区经验库 + 我的世界维基检索）；出于平台身份规则，响应不返回底层模型名称。</p>
        <pre class="code">{
  "ok": true,
  "data": {
    "content": "……AI 回复正文……",
    "cost": 6,          // 本次扣的积分 (基础 + 按量)
    "tokens": 812,      // 本次消耗 tokens
    "balance": 994,     // 扣费后余额
    "turn_id": 123,
    "cached": false     // true = 命中社区经验库直接回答, 不扣积分
  }
}</pre>
      </div>
      ${ais.map(epBlock).join('')}

      <h3 id="meta">GET /api/v1/meta <span class="tag">无需密钥</span></h3>
      <div class="glass">
        <p>返回接口清单、参数定义、可用乐器/颜色/槽位等元数据，可用于自动生成表单或校验参数。
          其中的 <code>billing</code> 字段是各接口当前的计费与配额策略（
          指令库单价 ${esc(bill.price_library !== undefined ? bill.price_library : 1)} 积分/次，
          普通 ${esc(bill.quota_free !== undefined ? bill.quota_free : 100)} 次/天，
          VIP ${esc(bill.quota_vip !== undefined ? bill.quota_vip : 2000)} 次/天）。</p>
        <p><b>乐器（instrument）</b>：${instr}</p>
        <p><b>颜色名</b>：${colors}</p>
        <p><b>计分板槽位</b>：${(m.slots || []).map((s) => `<code>${esc(s)}</code>`).join(' ')}　
           <b>T显位置</b>：${(m.title_positions || []).map((s) => `<code>${esc(s)}</code>`).join(' ')}</p>
      </div>

      <h3 id="me">GET /api/v1/me <span class="tag">需密钥</span></h3>
      <div class="glass">
        <p>查询当前密钥归属、限流配置与今日剩余配额，适合程序启动时自检。</p>
        <pre class="code">{
  "ok": true,
  "data": {
    "key": { "id": 2, "name": "服务器插件", "scopes": "gen", "rate_per_min": 60 },
    "owner": { "username": "tester01", "nickname": "测试员" },
    "quota": { "limit": 2000, "used": 12, "remaining": 1988 }
  }
}</pre>
      </div>

      <h3 id="notes">版本注意事项（edition）</h3>
      <div class="glass">
        <ul>
          <li>基岩版计分项只有 <code>dummy</code> 一种，生成的指令已按此处理。</li>
          <li>标题请使用 <code>/titleraw</code> + <code>rawtext</code>，<code>/title</code> 的 JSON 写法在基岩版不生效。</li>
          <li>音乐使用 <code>playsound note.&lt;乐器&gt;</code>，音高由 <code>0.5 * 2^(n/12)</code> 计算，范围 0.5–2.0。</li>
          <li>音效名不带 <code>minecraft:</code> 前缀。</li>
          <li><b>Java 版（<code>edition=java</code>）</b>：T显改用 <code>/title &lt;目标&gt; &lt;title|subtitle|actionbar&gt; &lt;文本组件&gt;</code>
            （单元素直接输出，多元素用 <code>{"text":"","extra":[...]}</code>）；音乐用
            <code>playsound minecraft:block.note_block.&lt;乐器&gt; master</code>，音高 <code>2^((note-12)/12)</code>（note 0–24 → 0.5–2.0）；
            物品 ID 自动补 <code>minecraft:</code> 命名空间；逐字横幅用 <code>summon minecraft:armor_stand … {CustomName:'{"text":"字"}',CustomNameVisible:1b}</code>。
            命令前缀与基岩版一致（保留开头的 <code>/</code>），放进命令方块时去掉即可。</li>
          <li>滚动字幕与音乐生成的是<b>命令方块序列</b>：注释行标注了「循环/保持开启」与「链式/条件开启」，请按注释放进对应类型的命令方块。</li>
          <li>穷举生成器一次最多返回 5000 条，超长结果请分批调用。</li>
        </ul>
      </div>`;

    navEl.querySelectorAll('a[data-doc]').forEach((a) => {
      a.addEventListener('click', (ev) => {
        ev.preventDefault();
        const t = document.getElementById(a.dataset.doc);
        if (t) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    });
  }

  // ---------------- 插件开发教学 ----------------
  function renderPlugin(bodyEl) {
    const cyanPlugin = `package dev.cyancodex.plugins.demo;

import android.content.Context;
import com.canglan.cyancodex.plugin.CyanPlugin;

public class DemoPlugin implements CyanPlugin {
    @Override public String getId()          { return "dev.cyancodex.demo"; }   // 全局唯一
    @Override public String getName()        { return "我的第一个插件"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getDescription() { return "演示插件 API"; }
    @Override public void onEnable(Context context) { /* 启用时初始化 */ }
    @Override public void onDisable()               { /* 禁用时释放资源 */ }
}`;

    const bgPlugin = `package dev.cyancodex.plugins.bg;

import android.content.Context;
import com.canglan.cyancodex.plugin.CyanPlugin;
import com.canglan.cyancodex.plugin.BackgroundProvider;
import com.canglan.cyancodex.plugin.PluginBackground;

public class BgPlugin implements CyanPlugin, BackgroundProvider {
    @Override public String getId()          { return "dev.cyancodex.bg"; }
    @Override public String getName()        { return "我的背景"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getDescription() { return "自定义主页背景"; }
    @Override public void onEnable(Context context) {}
    @Override public void onDisable() {}
    @Override public PluginBackground background() {
        // kind: gradient 上下渐变 / color 纯色；返回 null 表示不改背景
        return new PluginBackground("gradient", "#0B1A14", "#10352B");
    }
}`;

    const build = `# 1) 编译 (需要 android.jar, compileSdk 的 platforms/android-36/android.jar)
javac --release 8 -cp android.jar -d classes src/dev/cyancodex/plugins/demo/DemoPlugin.java

# 2) 转成 Android 字节码
d8 --release --lib android.jar --output dexout classes/*.class

# 3) 组装 jar 结构
#    demo/
#      ├─ classes.dex                                   <- 第 2 步产物
#      └─ META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin
#         (内容只有一行: dev.cyancodex.plugins.demo.DemoPlugin)
jar cf demo.jar -C demo .

# 4) 安装: 把 demo.jar 放进插件目录, 然后在 App「优化设置 → 插件」点「重新加载」再打开开关`;

    const dir = 'Android/data/com.canglan.cyancodex/files/plugins/';

    bodyEl.innerHTML = `
      <div class="glass">
        <h3 style="margin-top:0">不想写代码？用 App 里的「插件工坊」</h3>
        <p>CyanCodex v1.0.84 起，App 首页 →「开发者功能 · 插件工坊」提供图形化(积木式)插件制作：
        填插件名称/版本/简介、拼积木（记录日志、弹出提示、读写插件配置、自定义主界面背景），
        点「打包 JAR」即可<b>离线</b>生成可加载的插件文件，自动写入插件目录并热重载。</p>
        <p>工坊生成的插件与手写 JAR 结构一致（<code>classes.dex</code> + <code>META-INF/services</code> +
        <code>cyancodex-plugin.json</code> 行为规格），可以把自己生成的 JAR 分享给其他用户；
        想实现积木覆盖不到的功能，再按下面的教程手写 Java 插件即可。</p>

        <h3>插件是什么</h3>
        <p>插件是独立打包的 Java 代码（JAR），可以在不更新 App 的情况下扩展功能。App 内置两个默认插件：
        「自定义背景」演示 <code>BackgroundProvider</code> 扩展，「开发者示例插件」是带完整注释的教科书插件。</p>

        <h3>安装目录</h3>
        <p>把 jar 放进：</p>
        <pre class="code">${esc(dir)}</pre>
        <p>然后用文件管理器或 ADB 推送，最后在 App「优化设置 → 插件」里点「重新加载」，打开插件开关即可生效。
        Android 11+ 访问该目录需要文件管理器授权或使用电脑/ADB。</p>

        <h3>插件 API v1</h3>
        <p>实现 <code>com.canglan.cyancodex.plugin.CyanPlugin</code> 接口：</p>
        <pre class="code">public interface CyanPlugin {
    String getId();               // 插件唯一 id, 如 dev.cyancodex.demo
    String getName();             // 设置页显示的名称
    String getVersion();          // 版本号
    String getDescription();      // 设置页显示的简介
    void onEnable(Context ctx);   // 启用时回调 (ApplicationContext)
    void onDisable();             // 禁用时回调
}</pre>
        <p>可选扩展接口 <code>BackgroundProvider</code>：实现 <code>background()</code> 返回 <code>PluginBackground(kind, colorTop, colorBottom)</code>
        即可自定义主页背景（<code>gradient</code> 渐变 / <code>color</code> 纯色，返回 <code>null</code> 表示不修改）。</p>

        <h3>最小示例</h3>
        <pre class="code">${esc(cyanPlugin)}</pre>

        <h3>背景扩展示例</h3>
        <pre class="code">${esc(bgPlugin)}</pre>

        <h3>开发步骤（命令行）</h3>
        <pre class="code">${esc(build)}</pre>

        <h3>JAR 结构约定</h3>
        <div class="tablewrap"><table><thead><tr><th>路径</th><th>说明</th></tr></thead><tbody>
          <tr><td><code>classes.dex</code></td><td>插件字节码，必须由 d8 转换（不能直接放 .class）</td></tr>
          <tr><td><code>META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin</code></td><td>纯文本，一行一个实现类的完整类名（可写多个，<code>#</code> 开头为注释）</td></tr>
        </tbody></table></div>

        <h3>加载机制</h3>
        <ul>
          <li>App 启动时把内置插件和外部插件目录里的 jar 同步到应用私有目录 <code>files/plugins</code>，再用 <code>DexClassLoader</code> 加载。</li>
          <li>只有被 <code>META-INF/services</code> 声明的类才会被实例化；类名写错会加载失败但不会影响其它插件。</li>
          <li>Android 14+ 禁止从可写路径加载 dex，App 会自动把 jar 置为只读后再加载。</li>
          <li>启用状态按插件 id 持久化，重启 App 后保持。</li>
        </ul>

        <h3>常见问题</h3>
        <ul>
          <li><b>插件列表里没有我的插件</b>：确认 jar 在插件目录、扩展名是 <code>.jar</code>、services 文件路径与内容完全正确，然后点「重新加载」。</li>
          <li><b>插件报错导致看不到界面</b>：插件异常会被捕获并写进 logcat（TAG 为 <code>CyanPlugin</code>），可在日志里看到具体堆栈。</li>
          <li><b>不要做耗时/弹窗操作</b>：<code>onEnable</code> 在启动流程里同步调用，请只做轻量初始化，重活放线程里。</li>
          <li><b>插件 id 冲突</b>：两个插件用同一个 id 时后加载的会覆盖启用状态，请保证 id 全局唯一。</li>
          <li><b>需要访问网络/文件</b>：插件运行在 App 进程内，权限由 App 自身声明决定（网络已声明；外部存储请用应用私有目录）。</li>
        </ul>

        <h3>配合开放 API 使用</h3>
        <p>插件里可以用普通的 <code>HttpURLConnection</code>/<code>OkHttp</code> 调 <a href="#/docs">开放 API</a> 生成指令，
        例如把服务器聊天里的关键词转成 <code>/titleraw</code> 公告。密钥请放在插件自己的配置里，不要硬编码在公开仓库。</p>
      </div>`;
  }

  window.DOCS = { renderDocs, renderPlugin, meta };
})();
