// CyanCodex Java 版生成器本地测试 (node test-generators-java.js)
// 覆盖: 输出语法 / 无基岩专有词 / 与基岩版入参一致 / 关键换算正确
'use strict';
const assert = require('assert');
const G = require('./lib/generators');
const GJ = require('./lib/generators-java');

let pass = 0;
function t(name, fn) {
  try { fn(); console.log('PASS', name); pass++; }
  catch (e) { console.log('FAIL', name, '->', e.message); process.exitCode = 1; }
}

const BEDROCK_ONLY = ['titleraw', 'rawtext', 'note.harp', 'note.bass', 'note.bassdrum',
  'note.snare', 'note.bell', 'note.flute', 'note.guitar', 'note.chime',
  'note.xylophone', 'note.cow_bell', 'note.didgeridoo', 'note.bit', 'note.banjo', 'note.pling'];

function assertNoBedrockOnly(cmds) {
  cmds.forEach((c) => BEDROCK_ONLY.forEach((kw) => {
    assert.ok(!String(c).includes(kw), `输出含基岩专有词 ${kw}: ${c}`);
  }));
}

// ---- 音高换算 ----
t('pitchOf: note12 = 1.0000 (F#4), note0 = 0.5000, note24 = 2.0000', () => {
  assert.strictEqual(GJ.pitchOf(12), '1.0000');
  assert.strictEqual(GJ.pitchOf(0), '0.5000');
  assert.strictEqual(GJ.pitchOf(24), '2.0000');
  assert.strictEqual(GJ.pitchOf(1), '0.5297');
  assert.ok(parseFloat(GJ.pitchOf(24)) <= 2.0, 'Java 要求 pitch <= 2.0');
});

// ---- 计分板 ----
t('scoreboard tdisplay: Java 用 /title + score 组件, 不再是 titleraw', () => {
  const cmds = GJ.scoreboard({
    objective: 'sb', displayName: '分数', mode: 'tdisplay', tpos: 'title',
    tlines: [{ prefix: '金币: ', objective: 'money', suffix: ' 枚' }],
  });
  assert.ok(cmds.some((c) => c === '/title @a title {"text":"","extra":[{"text":"金币: "},{"score":{"name":"@s","objective":"money"}},{"text":" 枚"}]}'),
    '未生成期望的 /title 组件: ' + JSON.stringify(cmds));
  assertNoBedrockOnly(cmds);
});

t('scoreboard sidebar: 与基岩版逐字一致 (scoreboard 语法两版同构)', () => {
  const args = { objective: 'money', displayName: '金币', mode: 'sidebar', slot: 'sidebar',
    entries: [{ player: 'Steve', value: 1280 }, { player: 'Alex', value: 960 }] };
  assert.deepStrictEqual(GJ.scoreboard(args), G.scoreboard(args));
});

// ---- T显 JSON ----
t('titleraw 单元素: 直接输出该元素', () => {
  const s = GJ.titleraw({ target: '@a', pos: 'actionbar', segments: [{ kind: 'text', text: '单元素测试' }] });
  assert.strictEqual(s, '/title @a actionbar {"text":"单元素测试"}');
});

t('titleraw 多元素: {"text":"","extra":[...]}, selector/score/translate 语法与基岩一致', () => {
  const s = GJ.titleraw({ target: '@a', pos: 'title', segments: [
    { kind: 'text', text: '欢迎 ' }, { kind: 'selector', selector: '@p' },
    { kind: 'score', name: '@s', objective: 'money' }, { kind: 'translate', key: 'death.attack.generic' },
  ] });
  assert.strictEqual(s, '/title @a title {"text":"","extra":[{"text":"欢迎 "},{"selector":"@p"},'
    + '{"score":{"name":"@s","objective":"money"}},{"translate":"death.attack.generic"}]}');
  assertNoBedrockOnly([s]);
});

// ---- 音乐 ----
t('music: 带命名空间 + master source, bd -> basedrum, 音高保留 4 位', () => {
  const cmds = GJ.music({ loop: true, events: [
    { tick: 0, instrument: 'harp', note: 0 }, { tick: 2, instrument: 'bd', note: 4 },
  ] });
  assert.ok(cmds.includes('/execute as @a if score @s cyx_music matches 0 run '
    + 'playsound minecraft:block.note_block.harp master @s ~ ~ ~ 1 0.5000'), 'harp 行不对');
  assert.ok(cmds.includes('/execute as @a if score @s cyx_music matches 2 run '
    + 'playsound minecraft:block.note_block.basedrum master @s ~ ~ ~ 1 0.6300'), 'bd->basedrum 行不对');
  assertNoBedrockOnly(cmds);
});

// ---- 滚动字幕 ----
t('scroller roll: Java 用 actionbar, 节拍器/复位与基岩一致', () => {
  const cmds = GJ.scroller({ text: '公告 一', mode: 'roll', color: 'gold' });
  assert.ok(cmds.includes('/execute as @a if score @s cyx_scroll matches 1 run title @a actionbar {"text":"公告","color":"gold"}'),
    '未生成 actionbar 行: ' + JSON.stringify(cmds));
  assert.ok(cmds.includes('/execute as @a run scoreboard players add @s cyx_scroll 1'));
  assert.ok(cmds.some((c) => c.startsWith('#')));
  assertNoBedrockOnly(cmds);
});

t('scroller banner: Java 用 CustomName 文本组件 + CustomNameVisible', () => {
  const cmds = GJ.scroller({ text: 'Cy', mode: 'banner', x: 0, y: 100, z: 0, gap: 2 });
  assert.ok(cmds.includes('/summon minecraft:armor_stand 0 100 0 {CustomName:\'{"text":"C"}\',CustomNameVisible:1b}'),
    '未生成期望的 summon: ' + JSON.stringify(cmds));
  assert.ok(cmds.includes('/summon minecraft:armor_stand 2 100 0 {CustomName:\'{"text":"y"}\',CustomNameVisible:1b}'));
  assertNoBedrockOnly(cmds);
});

t('scroller banner: 文本含单引号时 SNBT 单引号串仍合法', () => {
  const cmds = GJ.scroller({ text: "a'b", mode: 'banner' });
  const lines = cmds.filter((c) => c.startsWith('/summon'));
  assert.strictEqual(lines.length, 3, '每个非空格字符一个盔甲架');
  lines.forEach((l) => {
    const inner = l.slice(l.indexOf("CustomName:'") + 12, l.lastIndexOf("',CustomNameVisible"));
    assert.ok(!/(^|[^\\])'/.test(inner), '存在未转义单引号(会破坏 SNBT): ' + l);
  });
  assert.ok(lines.some((l) => l.includes("\\'")), '含引号的字符未被转义: ' + JSON.stringify(lines));
});

// ---- 穷举 ----
t('enumerate: 物品 ID 自动补 minecraft: 命名空间, 其余逐字一致', () => {
  const args = { template: 'give @p {item} {n}', variables: { item: ['apple', 'bread'], n: ['1', '64'] }, maxCount: 10 };
  const bed = G.enumerate(args);
  const java = GJ.enumerate(args);
  assert.deepStrictEqual(bed, ['give @p apple 1', 'give @p apple 64', 'give @p bread 1', 'give @p bread 64']);
  assert.deepStrictEqual(java, ['give @p minecraft:apple 1', 'give @p minecraft:apple 64',
    'give @p minecraft:bread 1', 'give @p minecraft:bread 64']);
});

t('enumerate: 已带命名空间/标签/非 give 命令保持不动', () => {
  assert.strictEqual(GJ.namespaceItems('give @p minecraft:apple 1'), 'give @p minecraft:apple 1');
  assert.strictEqual(GJ.namespaceItems('give @p #minecraft:logs 1'), 'give @p #minecraft:logs 1');
  assert.strictEqual(GJ.namespaceItems('give @p apple{display:{Name:"x"}} 1'),
    'give @p minecraft:apple{display:{Name:"x"}} 1');
  assert.strictEqual(GJ.namespaceItems('say hello'), 'say hello');
  assert.strictEqual(GJ.namespaceItems('/execute as @a run give @s stick 1'),
    '/execute as @a run give @s minecraft:stick 1');
});

// ---- 结构一致性 ----
t('每个生成器: 入参结构一致且 java 结果非空', () => {
  const samples = {
    scoreboard: { objective: 'sb', displayName: 'x', mode: 'sidebar', entries: [{ player: 'Steve', value: 1 }] },
    enumerate: { template: 'give @p {i} 1', variables: { i: ['apple'] }, maxCount: 5 },
    scroller: { text: 'a b', mode: 'roll' },
    music: { loop: true, events: [{ tick: 0, instrument: 'harp', note: 0 }] },
    titleraw: { target: '@a', pos: 'title', segments: [{ kind: 'text', text: 'x' }] },
  };
  Object.keys(G.GENERATORS).forEach((k) => {
    assert.ok(GJ.GENERATORS[k], '缺少 Java 生成器: ' + k);
    const out = GJ.GENERATORS[k].run(samples[k]);
    assert.ok(Array.isArray(out) && out.length > 0, k + ' java 输出为空');
    assertNoBedrockOnly(out);
  });
});

console.log('\nJava 生成器测试: %d 项通过', pass);
