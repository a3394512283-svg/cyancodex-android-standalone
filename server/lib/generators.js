// CyanCodex 生成器算法 (Node 版)
// 与 Android 端 com.canglan.cyancodex.generators.Generators 保持完全一致 (基岩版 Bedrock)
'use strict';

const INSTRUMENTS = [
  'harp', 'bd', 'snare', 'hat', 'bass', 'flute', 'bell',
  'guitar', 'chime', 'xylophone', 'iron_xylophone', 'cow_bell',
  'didgeridoo', 'bit', 'banjo', 'pling',
];

// 中文颜色名 -> 基岩版颜色代码
const COLORS = {
  白色: 'white', 黄色: 'yellow', 金色: 'gold',
  红色: 'red', 绿色: 'green', 深绿: 'dark_green',
  蓝色: 'blue', 深蓝: 'dark_blue', 天蓝: 'aqua',
  紫色: 'light_purple', 深紫: 'dark_purple',
  灰色: 'gray', 深灰: 'dark_gray', 黑色: 'black',
};

const SLOTS = ['sidebar', 'belowname', 'list'];
const MODES = ['sidebar', 'tdisplay'];
const TPOS = ['title', 'subtitle', 'actionbar'];

// ---------------- 工具 ----------------
function sanitizeName(s) {
  return String(s == null ? '' : s).replace(/[^a-zA-Z0-9_.:-]/g, '_').slice(0, 16);
}

function escapeJson(s) {
  return String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function escapeQuote(s) {
  return String(s == null ? '' : s).replace(/"/g, '\\"');
}

function pitchOf(note) {
  const n = Math.max(0, Math.min(24, parseInt(note, 10) || 0));
  return (0.5 * Math.pow(2, n / 12)).toFixed(3);
}

// ---------------- T显 / 计分板 ----------------
// args: { objective, displayName, slot, mode, tpos, entries:[{player,value}], tlines:[{prefix,objective,suffix}] }
function scoreboard(args) {
  const a = args || {};
  const out = [];
  const obj = sanitizeName(a.objective || 'sb') || 'sb';
  const display = escapeQuote(a.displayName || obj);
  out.push(`/scoreboard objectives add ${obj} dummy "${display}"`);

  if (a.mode === 'tdisplay') {
    out.push(`/scoreboard objectives setdisplay sidebar ${obj}`);
    (a.tlines || []).filter((l) => l && String(l.objective || '').trim()).forEach((l) => {
      const parts = [];
      if (String(l.prefix || '').trim()) parts.push(`{"text":"${escapeJson(l.prefix)}"}`);
      parts.push(`{"score":{"name":"@s","objective":"${sanitizeName(l.objective)}"}}`);
      if (String(l.suffix || '').trim()) parts.push(`{"text":"${escapeJson(l.suffix)}"}`);
      out.push(`/titleraw @a ${TPOS.includes(a.tpos) ? a.tpos : 'title'} {"rawtext":[${parts.join(',')}]}`);
    });
  } else {
    const slot = SLOTS.includes(a.slot) ? a.slot : 'sidebar';
    out.push(`/scoreboard objectives setdisplay ${slot} ${obj}`);
    (a.entries || []).filter((e) => e && String(e.player || '').trim()).forEach((e) => {
      const v = parseInt(e.value, 10);
      out.push(`/scoreboard players set ${String(e.player).trim()} ${obj} ${Number.isFinite(v) ? v : 0}`);
    });
  }
  return out;
}

// ---------------- 穷举 (笛卡尔积) ----------------
// args: { template, variables: {A:[..]}, maxCount }
function enumerate(args) {
  const a = args || {};
  const template = String(a.template || '');
  const variables = a.variables || {};
  const names = Object.keys(variables);
  if (!template.trim() || !names.length) return [];
  const max = (a.maxCount >= 1 && a.maxCount <= 5000) ? parseInt(a.maxCount, 10) : 100;
  const cap = Math.max(max * 8, 1000);   // 组合上限: 结果等价于 take(max), 同时避免组合爆炸撑爆内存
  let combos = [{}];
  names.forEach((name) => {
    const values = (variables[name] || []).map((v) => String(v)).filter((v) => v !== '');
    if (!values.length) return;
    const next = [];
    outer:
    for (const base of combos) {
      for (const v of values) {
        next.push(Object.assign({}, base, { [name]: v }));
        if (next.length >= cap) break outer;
      }
    }
    combos = next;
  });
  const out = [];
  for (const vars of combos) {
    let s = template;
    Object.keys(vars).forEach((k) => { s = s.split('{' + k + '}').join(vars[k]); });
    if (s.trim()) out.push(s.trim());
    if (out.length >= max) break;
  }
  return out;
}

// ---------------- 滚动字幕 ----------------
// args: { text, color, mode: roll|banner, intervalTicks, x, y, z, gap }
function scroller(args) {
  const a = args || {};
  const color = a.color || 'gold';
  const text = String(a.text || '').trim() || 'CyanCodex';
  const out = [];
  if (a.mode === 'banner') {
    out.push('# 逐字横幅 (基岩版, 盔甲架带名字, 会显示本体):');
    const x = parseInt(a.x, 10) || 0;
    const y = parseInt(a.y, 10) || 100;
    const z = parseInt(a.z, 10) || 0;
    const gap = parseInt(a.gap, 10) || 2;
    Array.from(text).forEach((ch, i) => {
      if (ch === ' ') return;
      out.push(`/summon armor_stand ${x + i * gap} ${y} ${z} "${ch}"`);
    });
    out.push('# 可选: 隐藏盔甲架本体: /effect @e[type=armor_stand] invisibility 999999 1 true');
    return out;
  }
  const lines = text.split(/\s+/).filter(Boolean);
  if (!lines.length) return [];
  const obj = 'cyx_scroll';
  const max = lines.length + 1;
  out.push(`/scoreboard objectives add ${obj} dummy "滚动字幕"`);
  out.push(`/scoreboard players set @a ${obj} 0`);
  out.push('# 以下命令按顺序放入命令方块:');
  out.push('# [循环/保持开启] 节拍器:');
  out.push(`/execute as @a run scoreboard players add @s ${obj} 1`);
  lines.forEach((line, i) => {
    const json = `{"rawtext":[{"text":"${escapeJson(line)}","color":"${color}"}]}`;
    out.push(`# [链式/条件开启] 第${i + 1}行:`);
    out.push(`/execute as @a if score @s ${obj} matches ${i + 1} run titleraw @a title ${json}`);
  });
  out.push('# [链式/条件开启] 循环复位:');
  out.push(`/execute as @a if score @s ${obj} matches ${max} run scoreboard players set @s ${obj} 0`);
  return out;
}

// ---------------- 音乐生成器 ----------------
// args: { tempo, loop, events:[{tick, instrument, note}] }
function music(args) {
  const a = args || {};
  const events = (a.events || []).filter((e) => e && Number.isFinite(parseInt(e.tick, 10)));
  if (!events.length) return ['# 请先在网格中放置音符'];
  const obj = 'cyx_music';
  const maxTick = events.reduce((m, e) => Math.max(m, parseInt(e.tick, 10)), 0) + 1;
  const out = [];
  out.push(`/scoreboard objectives add ${obj} dummy "音乐"`);
  out.push(`/scoreboard players set @a ${obj} 0`);
  out.push('# [循环/始终开启] 节拍器:');
  out.push(`/execute as @a run scoreboard players add @s ${obj} 1`);
  const grouped = new Map();
  events.forEach((e) => {
    const t = parseInt(e.tick, 10);
    if (!grouped.has(t)) grouped.set(t, []);
    grouped.get(t).push(e);
  });
  Array.from(grouped.keys()).sort((x, y) => x - y).forEach((tick) => {
    out.push(`# [链式/条件开启] tick ${tick}:`);
    grouped.get(tick).forEach((ev) => {
      const inst = INSTRUMENTS.includes(ev.instrument) ? ev.instrument : 'harp';
      out.push(`/execute as @a if score @s ${obj} matches ${tick} run playsound note.${inst} @s ~ ~ ~ 1 ${pitchOf(ev.note)}`);
    });
  });
  if (a.loop !== false) {
    out.push('# [链式/条件开启] 循环复位:');
    out.push(`/execute as @a if score @s ${obj} matches ${maxTick} run scoreboard players set @s ${obj} 0`);
  }
  return out;
}

// ---------------- T显 JSON (rawtext 元素) ----------------
// segments: [{kind:'text'|'translate'|'selector'|'score'|'concat'|'raw', ...}]
function rawtextArray(segments) {
  const arr = [];
  (segments || []).forEach((s) => {
    const seg = s || {};
    switch (seg.kind) {
      case 'translate':
        arr.push({ translate: String(seg.key || '') });
        break;
      case 'selector':
        arr.push({ selector: String(seg.selector || '@p') });
        break;
      case 'score':
        arr.push({ score: { name: String(seg.name || '@s'), objective: String(seg.objective || 'sb') } });
        break;
      case 'concat':
        arr.push({
          translate: String(seg.key || ''),
          with: (seg.with || []).map((w) => String(w)),
        });
        break;
      case 'raw':
        try {
          arr.push(typeof seg.json === 'string' ? JSON.parse(seg.json || '{}') : (seg.json || {}));
        } catch (e) {
          arr.push({ text: '' });
        }
        break;
      default:
        arr.push({ text: String(seg.text == null ? '' : seg.text) });
    }
  });
  return arr;
}

// 返回完整 /titleraw 指令
function titleraw(args) {
  const a = args || {};
  const target = String(a.target || '@a');
  const pos = TPOS.includes(a.pos) ? a.pos : 'title';
  const arr = rawtextArray(a.segments);
  return `/titleraw ${target} ${pos} {"rawtext":${JSON.stringify(arr)}}`;
}

function rawtextJson(segments) {
  return JSON.stringify(rawtextArray(segments));
}

// ---------------- 统一入口 ----------------
const GENERATORS = {
  scoreboard: { run: scoreboard, title: 'T显/计分板生成器', desc: '基岩版 scoreboard objectives + titleraw 计分板/T显字幕' },
  enumerate: { run: enumerate, title: '穷举生成器', desc: '模板 {变量} 笛卡尔积批量展开指令' },
  scroller: { run: scroller, title: '滚动字幕生成器', desc: '轮播公告 / 逐字盔甲架横幅' },
  music: { run: music, title: '音乐生成器', desc: '音符盒 playsound note.* 指令, 支持循环' },
  titleraw: { run: (a) => [titleraw(a)], title: 'T显 JSON 生成器', desc: '/titleraw rawtext 元素拼装' },
};

module.exports = {
  INSTRUMENTS, COLORS, SLOTS, MODES, TPOS, GENERATORS,
  sanitizeName, escapeJson, escapeQuote, pitchOf,
  scoreboard, enumerate, scroller, music, titleraw, rawtextArray, rawtextJson,
};
