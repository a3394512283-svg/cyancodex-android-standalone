// CyanCodex Web - 生成器 (输出与 App 版一致)
const GEN = (() => {
  const INSTRUMENTS = ["harp","bd","snare","hat","bass","flute","bell","guitar","chime","xylophone","iron_xylophone","cow_bell","didgeridoo","bit","banjo","pling"];
  const sanitize = s => (s || '').replace(/[^a-zA-Z0-9_.:-]/g, '_').slice(0, 16);
  const escJson = s => String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
  const escQuote = s => String(s).replace(/"/g, '\\"');
  const pitchOf = note => (0.5 * Math.pow(2, Math.max(0, Math.min(24, note)) / 12)).toFixed(3);

  // T显 / 计分板
  function scoreboard(args) {
    const out = [];
    const obj = sanitize(args.objective || 'sb');
    const display = escQuote(args.displayName || obj);
    out.push(`/scoreboard objectives add ${obj} dummy "${display}"`);
    if (args.mode === 'tdisplay') {
      out.push(`/scoreboard objectives setdisplay sidebar ${obj}`);
      (args.tlines || []).filter(l => l.objective && l.objective.trim()).forEach(l => {
        const parts = [];
        if (l.prefix && l.prefix.trim()) parts.push(`{"text":"${escJson(l.prefix)}"}`);
        parts.push(`{"score":{"name":"@s","objective":"${sanitize(l.objective)}"}}`);
        if (l.suffix && l.suffix.trim()) parts.push(`{"text":"${escJson(l.suffix)}"}`);
        out.push(`/titleraw @a ${args.tpos || 'title'} {"rawtext":[${parts.join(',')}]}`);
      });
    } else {
      if (args.slot) out.push(`/scoreboard objectives setdisplay ${args.slot} ${obj}`);
      (args.entries || []).filter(e => e.player && e.player.trim()).forEach(e => {
        out.push(`/scoreboard players set ${e.player.trim()} ${obj} ${e.value}`);
      });
    }
    return out;
  }

  // 穷举: 笛卡尔积
  function enumerate(template, variables, maxCount) {
    if (!template || !variables || !Object.keys(variables).length) return [];
    let combos = [{}];
    Object.keys(variables).forEach(name => {
      const values = variables[name].filter(v => v !== '');
      if (values.length) combos = combos.flatMap(base => values.map(v => ({ ...base, [name]: v })));
    });
    return combos.map(vars => {
      let s = template;
      Object.keys(vars).forEach(k => s = s.replace(new RegExp('\\{' + k + '\\}', 'g'), vars[k]));
      return s.trim();
    }).filter(Boolean).slice(0, (maxCount >= 1 && maxCount <= 5000) ? maxCount : 100);
  }

  const COLORS = { '白色':'white','黄色':'yellow','金色':'gold','红色':'red','绿色':'green','深绿':'dark_green','蓝色':'blue','深蓝':'dark_blue','天蓝':'aqua','紫色':'light_purple','深紫':'dark_purple','灰色':'gray','深灰':'dark_gray','黑色':'black' };

  // 滚动字幕
  function scroller(args) {
    const color = args.color || 'gold';
    const text = args.text || 'CyanCodex';
    const out = [];
    if (args.mode === 'banner') {
      out.push('# 逐字横幅 (基岩版, 盔甲架带名字, 会显示本体):');
      [...text].forEach((ch, i) => {
        if (ch === ' ') return;
        out.push(`/summon armor_stand ${args.x + i * (args.gap || 2)} ${args.y} ${args.z} "${ch}"`);
      });
      out.push('# 可选: 隐藏盔甲架本体: /effect @e[type=armor_stand] invisibility 999999 1 true');
      return out;
    }
    const lines = text.trim().split(/\s+/).filter(Boolean);
    if (!lines.length) return [];
    const obj = 'cyx_scroll';
    const max = lines.length + 1;
    out.push(`/scoreboard objectives add ${obj} dummy "滚动字幕"`);
    out.push(`/scoreboard players set @a ${obj} 0`);
    out.push('# 以下命令按顺序放入命令方块:');
    out.push('# [循环/保持开启] 节拍器:');
    out.push(`/execute as @a run scoreboard players add @s ${obj} 1`);
    lines.forEach((line, i) => {
      const json = `{"rawtext":[{"text":"${escJson(line)}","color":"${color}"}]}`;
      out.push(`# [链式/条件开启] 第${i + 1}行:`);
      out.push(`/execute as @a if score @s ${obj} matches ${i + 1} run titleraw @a title ${json}`);
    });
    out.push(`# [链式/条件开启] 循环复位:`);
    out.push(`/execute as @a if score @s ${obj} matches ${max} run scoreboard players set @s ${obj} 0`);
    return out;
  }

  // 音乐: events = [{tick, instrument, note}]
  function music(args) {
    if (!args.events || !args.events.length) return ['# 请先在网格中放置音符'];
    const obj = 'cyx_music';
    const maxTick = Math.max(...args.events.map(e => e.tick)) + 1;
    const out = [];
    out.push(`/scoreboard objectives add ${obj} dummy "音乐"`);
    out.push(`/scoreboard players set @a ${obj} 0`);
    out.push('# [循环/始终开启] 节拍器:');
    out.push(`/execute as @a run scoreboard players add @s ${obj} 1`);
    const grouped = {};
    args.events.forEach(e => { (grouped[e.tick] = grouped[e.tick] || []).push(e); });
    Object.keys(grouped).map(Number).sort((a, b) => a - b).forEach(tick => {
      out.push(`# [链式/条件开启] tick ${tick}:`);
      grouped[tick].forEach(ev => {
        const inst = INSTRUMENTS.includes(ev.instrument) ? ev.instrument : 'harp';
        out.push(`/execute as @a if score @s ${obj} matches ${tick} run playsound note.${inst} @s ~ ~ ~ 1 ${pitchOf(ev.note)}`);
      });
    });
    if (args.loop) {
      out.push(`# [链式/条件开启] 循环复位:`);
      out.push(`/execute as @a if score @s ${obj} matches ${maxTick} run scoreboard players set @s ${obj} 0`);
    }
    return out;
  }

  // T显JSON: 简易 rawtext 构建
  function titleraw(target, pos, segments) {
    const arr = segments.map(s => {
      if (s.kind === 'text') return { text: s.text };
      if (s.kind === 'selector') return { selector: s.selector || '@p' };
      if (s.kind === 'score') return { score: { name: s.name || '@s', objective: s.obj || 'sb' } };
      if (s.kind === 'color') return { text: s.text || '', color: s.color || 'white' };
      return { text: s.text || '' };
    });
    return `/titleraw ${target} ${pos} {"rawtext":${JSON.stringify(arr)}}`;
  }

  return { scoreboard, enumerate, scroller, music, titleraw, INSTRUMENTS, COLORS, pitchOf };
})();
