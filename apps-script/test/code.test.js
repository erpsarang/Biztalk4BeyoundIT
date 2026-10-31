'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { load, geminiReply } = require('./fakes');

const TOKEN = 'test-token-0123456789';
const BASE_PROPS = { API_TOKEN: TOKEN, GEMINI_API_KEY: 'fake-key', ALERT_EMAILS: 'me@example.com, senior@example.com' };

/** 프롬프트의 메시지 목록을 읽어 규칙대로 답하는 가짜 Gemini */
function rulesGemini(rule) {
  return (request) => {
    const prompt = request.contents[0].parts[0].text;
    const items = JSON.parse(prompt.slice(prompt.indexOf('['  , prompt.indexOf('메시지 목록'))));
    return { code: 200, body: geminiReply(items.map((it) => ({ key: it.key, ...rule(it) }))) };
  };
}

const defaultRule = (it) => (/투입|프로젝트|견적/.test(it.text)
  ? { relevant: true, category: '투입 문의', reason: '투입 요청' }
  : { relevant: false, category: it.designated ? '안부' : '', reason: '잡담' });

function setupEnv(opts = {}) {
  const env = load({ props: { ...BASE_PROPS, ...(opts.props || {}) }, gemini: opts.gemini || rulesGemini(defaultRule) });
  env.ctx.setup();
  return env;
}

function post(env, body) {
  const out = env.ctx.doPost({ postData: { contents: JSON.stringify({ token: TOKEN, ...body }) } });
  return JSON.parse(out.getContent());
}

let seq = 0;
function msg(over = {}) {
  seq += 1;
  return { id: 'id-' + seq, receivedAt: Date.UTC(2026, 10, 1, 1, seq), sender: '홍길동', room: '', isGroup: false,
    text: '안녕하세요', ...over };
}

function statusValue(env, key) {
  const row = env.ss.getSheetByName('상태').data().find((r) => r[0] === key);
  return row ? row[1] : undefined;
}

test('setup은 탭, 토큰, 트리거를 만들고 여러 번 실행해도 중복되지 않는다', () => {
  const env = load({ props: { GEMINI_API_KEY: 'k', ALERT_EMAILS: 'a@b.c' } });
  env.ctx.setup();
  env.ctx.setup();
  assert.deepEqual([...env.ss.sheets.keys()].sort(), ['_처리기록', '메시지', '상태', '지정인'].sort());
  assert.deepEqual(env.ss.getSheetByName('메시지').rows[0], ['일자', '송신자', '채팅방', '메시지', '카테고리', '수집 사유', '처리 상태']);
  assert.equal(env.ss.getSheetByName('_처리기록').hidden, true);
  assert.equal(env.ss.timeZone, 'Asia/Seoul');
  assert.match(env.props.get('API_TOKEN'), /^[0-9a-f]{40,}$/);
  assert.equal(env.props.get('SPREADSHEET_ID'), 'fake-spreadsheet-id');
  assert.equal(env.triggers.length, 1);
  assert.equal(env.triggers[0].minutes, 30);
  assert.equal(env.ss.getSheetByName('상태').data().length, 6);
  assert.ok(env.logs.some((l) => l.includes('필수 스크립트 속성이 모두 있습니다')));
});

test('setup은 이미 있는 토큰을 바꾸지 않는다', () => {
  const env = setupEnv();
  assert.equal(env.props.get('API_TOKEN'), TOKEN);
});

test('토큰이 틀리거나 JSON이 아니면 거부한다', () => {
  const env = setupEnv();
  const bad = JSON.parse(env.ctx.doPost({ postData: { contents: JSON.stringify({ token: 'wrong', type: 'heartbeat' }) } }).getContent());
  assert.deepEqual(bad, { ok: false, error: 'unauthorized' });
  const broken = JSON.parse(env.ctx.doPost({ postData: { contents: '{not json' } }).getContent());
  assert.equal(broken.error, 'invalid_json');
  assert.equal(statusValue(env, '마지막 생존 신호'), '');
});

test('생존 신호는 상태 탭만 갱신한다', () => {
  const env = setupEnv();
  const res = post(env, { type: 'heartbeat', appVersion: '1.0', device: 'SM-S921N' });
  assert.equal(res.ok, true);
  assert.ok(statusValue(env, '마지막 생존 신호') instanceof Date);
  assert.equal(statusValue(env, '앱 버전'), '1.0');
  assert.equal(statusValue(env, '기기'), 'SM-S921N');
  assert.equal(env.fetches.length, 0);
});

test('지정인은 항상 저장하고, 그 외에는 AI가 관련 있다고 본 것만 저장한다', () => {
  const env = setupEnv();
  env.ss.getSheetByName('지정인').setCell(2, 1, ' 김 부장 ');
  const designatedChat = msg({ sender: '김부장', text: '주말 잘 보내세요' });
  const projectAsk = msg({ sender: '이대리', room: 'ERP 모임', isGroup: true, text: '다음 달 SAP 프로젝트 투입 가능하신가요?' });
  const smallTalk = msg({ sender: '엄마', text: '밥 먹었니?' });

  const res = post(env, { type: 'messages', messages: [designatedChat, projectAsk, smallTalk] });

  assert.equal(res.ok, true);
  assert.equal(res.stored, 2);
  assert.deepEqual(res.retry, []);
  assert.deepEqual(res.done.sort(), [designatedChat.id, projectAsk.id, smallTalk.id].sort());
  const rows = env.ss.getSheetByName('메시지').data();
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0].slice(1), ['김부장', '', '주말 잘 보내세요', '안부', '지정인', '미확인']);
  assert.deepEqual(rows[1].slice(1), ['이대리', 'ERP 모임', '다음 달 SAP 프로젝트 투입 가능하신가요?', '투입 문의', 'AI 판단 – 투입 요청', '미확인']);
  assert.ok(rows[0][0] instanceof Date);
  assert.equal(rows[0][0].getTime(), designatedChat.receivedAt);

  // 프롬프트에는 짧은 key와 지정인 여부가 들어간다
  const prompt = env.fetches[0].request.contents[0].parts[0].text;
  assert.match(prompt, /"key":"m1"/);
  assert.match(prompt, /"designated":true/);
  assert.match(prompt, /\(1:1 대화\)/);
  assert.equal(env.fetches[0].options.headers['x-goog-api-key'], 'fake-key');
  assert.match(env.fetches[0].url, /models\/gemini-2\.5-flash:generateContent$/);
});

test('같은 메시지를 다시 보내면 저장하지 않고 done으로 돌려준다', () => {
  const env = setupEnv();
  const m = msg({ text: '프로젝트 견적 부탁드립니다' });
  post(env, { type: 'messages', messages: [m] });
  const again = post(env, { type: 'messages', messages: [m, m] });
  assert.deepEqual(again.done, [m.id, m.id]);
  assert.equal(again.stored, 0);
  assert.equal(env.fetches.length, 1, '중복은 AI를 다시 부르지 않는다');
  assert.equal(env.ss.getSheetByName('메시지').data().length, 1);
});

test('AI가 실패하면 지정인은 미분류로 저장하고 나머지는 재시도로 돌려준다', () => {
  let fail = true;
  const env = setupEnv({ gemini: (req) => (fail ? { code: 429, body: 'RESOURCE_EXHAUSTED' } : rulesGemini(defaultRule)(req)) });
  env.ss.getSheetByName('지정인').setCell(2, 1, '김부장');
  const fromDesignated = msg({ sender: '김부장', text: '통화 가능하세요?' });
  const other = msg({ sender: '박과장', text: '프로젝트 투입 문의드립니다' });

  const first = post(env, { type: 'messages', messages: [fromDesignated, other] });
  assert.equal(first.ok, true);
  assert.deepEqual(first.done, [fromDesignated.id]);
  assert.deepEqual(first.retry, [other.id]);
  assert.match(first.aiError, /429/);
  assert.match(String(statusValue(env, '마지막 오류')), /Gemini HTTP 429/);
  assert.deepEqual(env.ss.getSheetByName('메시지').data()[0].slice(4, 6), ['미분류', '지정인']);

  fail = false;
  const second = post(env, { type: 'messages', messages: [other] });
  assert.deepEqual(second.done, [other.id]);
  assert.equal(second.stored, 1);
  assert.equal(env.ss.getSheetByName('메시지').data().length, 2);
});

test('AI가 일부 메시지를 빠뜨리면 그 메시지만 재시도한다', () => {
  const env = setupEnv({
    gemini: () => ({ code: 200, body: geminiReply([{ key: 'm1', relevant: true, category: '고객요청', reason: '요청' }]) }),
  });
  const a = msg({ text: '견적 요청' });
  const b = msg({ text: '프로젝트 일정 문의' });
  const res = post(env, { type: 'messages', messages: [a, b] });
  assert.deepEqual(res.done, [a.id]);
  assert.deepEqual(res.retry, [b.id]);
});

test('공백만 다른 카테고리는 기존 이름으로 맞춘다', () => {
  const env = setupEnv({ gemini: rulesGemini(() => ({ relevant: true, category: '고객 요청', reason: '요청' })) });
  const sheet = env.ss.getSheetByName('메시지');
  sheet.setCell(2, 1, new Date());
  sheet.setCell(2, 5, '고객요청');
  post(env, { type: 'messages', messages: [msg({ text: '견적 부탁드려요' })] });
  assert.equal(sheet.data()[1][4], '고객요청');
  const prompt = env.fetches[0].request.contents[0].parts[0].text;
  assert.match(prompt, /기존 카테고리: \["고객요청"\]/);
});

test('수식으로 시작하는 메시지는 문자열로 저장한다', () => {
  const env = setupEnv({ gemini: rulesGemini(() => ({ relevant: true, category: '=SUM(1)', reason: 'x' })) });
  post(env, { type: 'messages', messages: [msg({ sender: '@해커', text: '=IMPORTXML("http://x")' })] });
  const row = env.ss.getSheetByName('메시지').data()[0];
  assert.equal(row[1], "'@해커");
  assert.equal(row[3], '\'=IMPORTXML("http://x")');
  assert.equal(row[4], "'=SUM(1)");
});

test('형식이 틀린 메시지는 done으로 버리고, 너무 많으면 거부한다', () => {
  const env = setupEnv();
  const res = post(env, { type: 'messages', messages: [{ id: 'bad-1', sender: '', text: 'x', receivedAt: 1 }, { nope: true }] });
  assert.deepEqual(res, { ok: true, done: ['bad-1'], retry: [], stored: 0 });
  const many = post(env, { type: 'messages', messages: Array.from({ length: 51 }, () => msg()) });
  assert.equal(many.error, 'too_many_messages');
});

test('생존 신호가 끊기면 한 번만 경고하고, 돌아오면 복구 메일을 보낸다', () => {
  const env = setupEnv();
  const status = env.ss.getSheetByName('상태');
  const props = env.ctx.getProps_();
  const t0 = new Date('2026-11-01T00:00:00Z');
  env.ctx.setStatus_(status, '마지막 생존 신호', t0);

  assert.equal(env.ctx.evaluateHeartbeat_(status, props, new Date(t0.getTime() + 2 * 3600e3)), 'ok');
  assert.equal(env.ctx.evaluateHeartbeat_(status, props, new Date(t0.getTime() + 4 * 3600e3)), 'alerted');
  assert.equal(env.ctx.evaluateHeartbeat_(status, props, new Date(t0.getTime() + 5 * 3600e3)), 'waiting');
  assert.equal(env.mails.length, 1);
  assert.equal(env.mails[0].to, 'me@example.com,senior@example.com');
  assert.match(env.mails[0].subject, /끊겼습니다/);

  env.ctx.setStatus_(status, '마지막 생존 신호', new Date(t0.getTime() + 6 * 3600e3));
  assert.equal(env.ctx.evaluateHeartbeat_(status, props, new Date(t0.getTime() + 6.5 * 3600e3)), 'recovered');
  assert.equal(env.mails.length, 2);
  assert.match(env.mails[1].subject, /복구/);
  assert.equal(env.ctx.evaluateHeartbeat_(status, props, new Date(t0.getTime() + 7 * 3600e3)), 'ok');
});

test('신호가 한 번도 없으면 경고하지 않는다', () => {
  const env = setupEnv();
  env.ctx.checkHeartbeat();
  assert.equal(env.mails.length, 0);
});

test('오래된 처리 기록은 지운다', () => {
  const env = setupEnv();
  const sheet = env.ss.getSheetByName('_처리기록');
  const now = new Date('2026-11-30T00:00:00Z');
  sheet.getRange(2, 1, 3, 2).setValues([
    ['old', now.getTime() - 20 * 86400e3],
    ['recent', now.getTime() - 2 * 86400e3],
    ['older', now.getTime() - 15 * 86400e3],
  ]);
  assert.equal(env.ctx.pruneProcessed_(sheet, now, 14), 2);
  assert.deepEqual(sheet.data().map((r) => r[0]), ['recent']);
});

test('Gemini 응답 파싱: 차단되거나 비면 오류, 형식이 틀린 항목은 버린다', () => {
  const env = load();
  assert.throws(() => env.ctx.parseGeminiResponse_(JSON.stringify({ promptFeedback: { blockReason: 'SAFETY' } })), /SAFETY/);
  const parsed = env.ctx.parseGeminiResponse_(geminiReply([
    { key: 'm1', relevant: true, category: 'A', reason: 'r' },
    { key: 'm2', relevant: 'yes' },
  ]));
  assert.deepEqual(Object.keys(parsed), ['m1']);
});
