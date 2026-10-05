// 生成器回归测试: node test-generators.js
const G = require('./lib/generators');
let fail = 0;
const eq = (a, b, name) => {
  const ok = JSON.stringify(a) === JSON.stringify(b);
  if (!ok) fail++;
  console.log((ok ? 'PASS ' : 'FAIL ') + name);
  if (!ok) { console.log('  got     ', JSON.stringify(a)); console.log('  expect  ', JSON.stringify(b)); }
};

eq(G.sanitizeName('我的包 sb!!'), '____sb__', 'sanitizeName');
eq(G.pitchOf(12), '1.000', 'pitchOf(12)');
eq(G.scoreboard({ objective: 'sb', displayName: '分数', slot: 'sidebar', mode: 'sidebar', entries: [{ player: 'Steve', value: 10 }] }),
  ['/scoreboard objectives add sb dummy "分数"', '/scoreboard objectives setdisplay sidebar sb', '/scoreboard players set Steve sb 10'], 'scoreboard sidebar');
eq(G.scoreboard({ objective: 'sb', mode: 'tdisplay', tpos: 'actionbar', tlines: [{ prefix: '分数:', objective: 'sb', suffix: ' 分' }] }),
  ['/scoreboard objectives add sb dummy "sb"', '/scoreboard objectives setdisplay sidebar sb',
    '/titleraw @a actionbar {"rawtext":[{"text":"分数:"},{"score":{"name":"@s","objective":"sb"}},{"text":" 分"}]}'], 'scoreboard tdisplay');
eq(G.enumerate({ template: 'give @p {item} {n}', variables: { item: ['apple', 'bread'], n: ['1', '2'] }, maxCount: 10 }),
  ['give @p apple 1', 'give @p apple 2', 'give @p bread 1', 'give @p bread 2'], 'enumerate');
eq(G.scroller({ text: 'A B', mode: 'roll', color: 'gold' }).length, 11, 'scroller roll length');
eq(G.scroller({ text: 'ABC', mode: 'banner', x: 0, y: 100, z: 0, gap: 2 }),
  ['# 逐字横幅 (基岩版, 盔甲架带名字, 会显示本体):', '/summon armor_stand 0 100 0 "A"', '/summon armor_stand 2 100 0 "B"',
    '/summon armor_stand 4 100 0 "C"', '# 可选: 隐藏盔甲架本体: /effect @e[type=armor_stand] invisibility 999999 1 true'], 'scroller banner');
eq(G.music({ loop: true, events: [{ tick: 1, instrument: 'harp', note: 0 }, { tick: 1, instrument: 'bell', note: 12 }] }).length, 9, 'music');
eq(G.titleraw({ target: '@a', pos: 'title', segments: [{ kind: 'text', text: '你好' }, { kind: 'score', name: '@s', objective: 'sb' }] }),
  '/titleraw @a title {"rawtext":[{"text":"你好"},{"score":{"name":"@s","objective":"sb"}}]}', 'titleraw');
eq(G.enumerate({ template: '{a}', variables: { a: Array.from({ length: 500 }, (_, i) => 'v' + i) }, maxCount: 3 }), ['v0', 'v1', 'v2'], 'enumerate cap');
eq(G.rawtextJson([{ kind: 'concat', key: '%1 的分数', with: ['@s'] }]), '[{"translate":"%1 的分数","with":["@s"]}]', 'concat');
console.log('INSTRUMENTS', G.INSTRUMENTS.length, 'COLORS', Object.keys(G.COLORS).length, 'GENERATORS', Object.keys(G.GENERATORS).length);
console.log(fail ? 'FAILED ' + fail : 'ALL PASS');
process.exit(fail ? 1 : 0);
