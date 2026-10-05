// CyanCodex 生成器算法 (Java 版 / Minecraft Java Edition)
// 与 generators.js (基岩版) 一一对应: 入参结构完全一致, 只把输出换成 Java 版语法。
// 目标版本: 1.13+ 通用; 关键语法已核对 1.20.1 / 1.21.x 可用 (依据见下, 均查证自 Minecraft Wiki)。
//
// ── 两版语法对照与依据 ────────────────────────────────────────────────
// T显:     基岩 /titleraw @a title {"rawtext":[{...}]}
//          Java /title @a title <文本组件>   (Java 没有 titleraw)
// 文本组件: Java 的元素名与基岩 rawtext 一致: {"text":..} {"selector":..}
//          {"score":{"name":"@s","objective":"money"}} {"translate":..,"with":[..]}
//          单元素直接输出该元素; 多元素用 {"text":"","extra":[元素...]} 拼接。
// 计分板:  scoreboard objectives add / setdisplay / players set 两版同构, 原样复用。
// 音乐:    基岩 /playsound note.harp @s ~ ~ ~ 1 <pitch>
//          Java /playsound minecraft:block.note_block.harp master @s ~ ~ ~ 1 <pitch>
//          Java 需要 <source> 参数(此处用 master); 仪器名差异 bd -> basedrum;
//          Java 要求 pitch 落在 0.0~2.0, 而 note 0~24 对应 0.5~2.0 (pitch = 2^((note-12)/12)), 正好合规。
// 盔甲架:  基岩 /summon armor_stand x y z "名字"
//          Java /summon minecraft:armor_stand x y z {CustomName:'{"text":"名字"}',CustomNameVisible:1b}
//          (Java 的 CustomName 是 JSON 文本组件字符串, 需再加一层单引号; CustomNameVisible 让名字常显)
// 物品 ID: Java 必须带命名空间: give @p apple 1 -> give @p minecraft:apple 1
// 命令前缀: 与基岩版保持一致 —— 结果行保留开头的 "/" (可直接粘贴到聊天栏),
//          注释行以 "#" 开头; 命令方块里填写时去掉开头的 "/"。1.20.5+ 起物品组件化 (give @p minecraft:apple[...]),
//          纯 ID + 数量的写法在 1.20.5+/1.21.x 仍然有效, 本模块只输出这种通用写法, 不输出已废弃语法。
'use strict';

const G = require('./generators');
const { sanitizeName, escapeJson, escapeQuote, INSTRUMENTS, SLOTS, TPOS } = G;

// 基岩版仪器名 -> Java note block 音效名 (Java 的 bass drum 叫 basedrum)
const JAVA_INSTRUMENT = {
  harp: 'harp', bd: 'basedrum', snare: 'snare', hat: 'hat', bass: 'bass',
  flute: 'flute', bell: 'bell', guitar: 'guitar', chime: 'chime',
  xylophone: 'xylophone', iron_xylophone: 'iron_xylophone', cow_bell: 'cow_bell',
  didgeridoo: 'didgeridoo', bit: 'bit', banjo: 'banjo', pling: 'pling',
};

/** Java note block 音高: pitch = 2^((note-12)/12), note 0~24 -> 0.5~2.0 (Java 允许 0.0~2.0) */
function pitchOf(note) {
  const n = Math.max(0, Math.min(24, parseInt(note, 10) || 0));
  return Math.pow(2, (n - 12) / 12).toFixed(4);
}

/**
 * 把文本组件元素数组拼成 Java 版文本组件
 * 单元素直接输出; 多元素用 {"text":"","extra":[...]} 串起来 (Java 的拼接写法)
 */
function javaComponent(elements) {
  const arr = Array.isArray(elements) ? elements : [];
  if (!arr.length) return '{"text":""}';
  if (arr.length === 1) return JSON.stringify(arr[0]);
  return JSON.stringify({ text: '', extra: arr });
}

/** SNBT 里嵌 JSON 字符串: 用单引号包裹, 并转义可能出现的单引号 */
function snbtJsonString(json) {
  return "'" + String(json).replace(/'/g, "\\'") + "'";
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
      if (String(l.prefix || '').trim()) parts.push({ text: String(l.prefix) });
      parts.push({ score: { name: '@s', objective: sanitizeName(l.objective) } });
      if (String(l.suffix || '').trim()) parts.push({ text: String(l.suffix) });
      out.push(`/title @a ${TPOS.includes(a.tpos) ? a.tpos : 'title'} ${javaComponent(parts)}`);
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
// 组合逻辑与基岩版完全一致, 只对展开后的指令做 Java 化处理 (物品 ID 补命名空间)
function enumerate(args) {
  return G.enumerate(args).map(namespaceItems);
}

/**
 * Java 版物品 ID 必须带命名空间。
 * 只保守处理最常见、语义明确的命令, 避免误改用户模板:
 *   give <目标> <物品> [数量] / clear <目标> [物品]
 * 已经是 xxx:yyy 或 #tag 形式的保持不动; 带组件/SNBT 后缀的 (minecraft:apple[...] / apple{...}) 只给主 ID 补前缀。
 */
function namespaceItems(line) {
  const src = String(line);
  const m = src.match(/^(.*?\brun\s+)?(\/?\s*)(give|clear)\s+(\S+)\s+([^\s]+)([\s\S]*)$/i);
  if (!m) return src;
  const [, prefix = '', slash = '', cmd = '', target = '', itemTok = '', rest = ''] = m;
  // 去掉组件/NBT 后缀 (apple[component=..] / apple{display:..}) 只看主 ID
  const bare = itemTok.split(/[\[{]/)[0];
  // 已带命名空间 (minecraft:apple / 其它模组命名空间) 或标签 (#minecraft:logs) 一律不动
  if (bare.indexOf(':') >= 0 || !/^[A-Za-z0-9_]+$/.test(bare)) return src;
  const fixed = 'minecraft:' + bare + itemTok.slice(bare.length);
  return `${prefix}${slash}${cmd} ${target} ${fixed}${rest}`;
}

// ---------------- 滚动字幕 ----------------
// args: { text, color, mode: roll|banner, x, y, z, gap }
function scroller(args) {
  const a = args || {};
  const color = a.color || 'gold';
  const text = String(a.text || '').trim() || 'CyanCodex';
  const out = [];

  if (a.mode === 'banner') {
    out.push('# 逐字横幅 (Java 版, 盔甲架 CustomName 常显名字, 默认会显示本体):');
    const x = parseInt(a.x, 10) || 0;
    const y = parseInt(a.y, 10) || 100;
    const z = parseInt(a.z, 10) || 0;
    const gap = parseInt(a.gap, 10) || 2;
    Array.from(text).forEach((ch, i) => {
      if (ch === ' ') return;
      const name = snbtJsonString(JSON.stringify({ text: ch }));
      out.push(`/summon minecraft:armor_stand ${x + i * gap} ${y} ${z} {CustomName:${name},CustomNameVisible:1b}`);
    });
    out.push('# 可选: 隐藏盔甲架本体: /effect give @e[type=minecraft:armor_stand] minecraft:invisibility 999999 0 true');
    return out;
  }

  const lines = text.split(/\s+/).filter(Boolean);
  if (!lines.length) return [];
  const obj = 'cyx_scroll';
  const max = lines.length + 1;
  out.push(`/scoreboard objectives add ${obj} dummy "滚动字幕"`);
  out.push(`/scoreboard players set @a ${obj} 0`);
  out.push('# 以下命令按顺序放入命令方块:');
  out.push('# [Java] 用 actionbar 轮播: 不会反复播放大标题动画(无闪烁), 也不需要像 bossbar 那样额外建条,');
  out.push('#       零初始化即可用 (基岩版这一行是标题位, Java 侧改用物品栏上方一行)。');
  out.push('# [循环/保持开启] 节拍器:');
  out.push(`/execute as @a run scoreboard players add @s ${obj} 1`);
  lines.forEach((line, i) => {
    const comp = JSON.stringify({ text: line, color });
    out.push(`# [链式/条件开启] 第${i + 1}行:`);
    out.push(`/execute as @a if score @s ${obj} matches ${i + 1} run title @a actionbar ${comp}`);
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
  out.push('# [Java] 音效名带命名空间, 并补上 Java 必需的 sound source (master):');
  out.push('#        playsound minecraft:block.note_block.<乐器> master @s ~ ~ ~ 1 <pitch>');
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
      const sound = 'minecraft:block.note_block.' + (JAVA_INSTRUMENT[inst] || 'harp');
      out.push(`/execute as @a if score @s ${obj} matches ${tick} run playsound ${sound} master @s ~ ~ ~ 1 ${pitchOf(ev.note)}`);
    });
  });
  if (a.loop !== false) {
    out.push('# [链式/条件开启] 循环复位:');
    out.push(`/execute as @a if score @s ${obj} matches ${maxTick} run scoreboard players set @s ${obj} 0`);
  }
  return out;
}

// ---------------- T显 JSON (文本组件) ----------------
// 与基岩版 rawtextArray 共用元素定义, 再按 Java 规则拼成文本组件
function titleraw(args) {
  const a = args || {};
  const target = String(a.target || '@a');
  const pos = TPOS.includes(a.pos) ? a.pos : 'title';
  return `/title ${target} ${pos} ${javaComponent(G.rawtextArray(a.segments))}`;
}

/** 只输出文本组件本身 (便于调用方二次拼装) */
function componentJson(segments) {
  return javaComponent(G.rawtextArray(segments));
}

// ---------------- 统一入口 (键名与基岩版一致) ----------------
const GENERATORS = {
  scoreboard: { run: scoreboard, title: 'T显/计分板生成器 (Java)', desc: 'Java 版 scoreboard + /title 文本组件' },
  enumerate: { run: enumerate, title: '穷举生成器 (Java)', desc: '模板展开 + 物品 ID 自动补 minecraft: 命名空间' },
  scroller: { run: scroller, title: '滚动字幕生成器 (Java)', desc: 'actionbar 轮播公告 / 逐字盔甲架横幅' },
  music: { run: music, title: '音乐生成器 (Java)', desc: 'playsound minecraft:block.note_block.* + 音高换算' },
  titleraw: { run: (a) => [titleraw(a)], title: 'T显 JSON 生成器 (Java)', desc: '/title 文本组件 (text/selector/score/translate)' },
};

module.exports = {
  JAVA_INSTRUMENT, GENERATORS,
  pitchOf, javaComponent, snbtJsonString, namespaceItems,
  scoreboard, enumerate, scroller, music, titleraw, componentJson,
};
