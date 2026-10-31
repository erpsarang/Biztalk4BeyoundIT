/**
 * Biztalk4BeyoundIT — 카톡 비즈니스 메시지 수집 서버 (Google Apps Script)
 *
 * 수집 앱(안드로이드)이 보낸 카톡 메시지 묶음을 받아서
 *   1) 비밀 토큰을 확인하고
 *   2) 이미 처리한 메시지를 걸러 내고
 *   3) 지정인 목록과 Gemini 판단으로 저장할 메시지를 고른 뒤
 *   4) 구글 시트 "메시지" 탭에 기록한다.
 * 정해진 시간마다 앱의 생존 신호를 점검해 끊기면 이메일로 경고한다.
 *
 * 설정은 스크립트 속성(프로젝트 설정 → 스크립트 속성)에 둔다.
 *   GEMINI_API_KEY  (필수) Google AI Studio에서 발급한 키
 *   ALERT_EMAILS    (필수) 경고 메일 받을 주소, 쉼표로 구분
 *   API_TOKEN       setup()이 자동 생성. 앱 설정에 같은 값을 입력한다
 *   GEMINI_MODEL    (선택) 기본값 gemini-2.5-flash
 *   ALERT_HOURS     (선택) 생존 신호가 몇 시간 없으면 경고할지. 기본값 3
 */

var SHEET_NAMES = {
  MESSAGES: '메시지',
  PEOPLE: '지정인',
  STATUS: '상태',
  PROCESSED: '_처리기록',
};

var MESSAGE_HEADERS = ['일자', '송신자', '채팅방', '메시지', '카테고리', '수집 사유', '처리 상태'];
var PEOPLE_HEADERS = ['이름', '메모'];
var PROCESSED_HEADERS = ['메시지 ID', '처리 시각(ms)'];
var REVIEW_OPTIONS = ['미확인', '확인', '처리 완료', '관련 없음'];

var STATUS_KEYS = {
  HEARTBEAT: '마지막 생존 신호',
  LAST_MESSAGE: '마지막 메시지 수신',
  APP_VERSION: '앱 버전',
  DEVICE: '기기',
  LAST_ERROR: '마지막 오류',
  ALERTED_AT: '경고 메일 발송 시각',
};

var DEFAULTS = {
  GEMINI_MODEL: 'gemini-2.5-flash',
  ALERT_HOURS: '3',
  PROCESSED_KEEP_DAYS: '14',
};

var UNCLASSIFIED = '미분류';
var REASON_DESIGNATED = '지정인';
var REASON_AI = 'AI 판단';
var MAX_MESSAGES_PER_REQUEST = 50;
var MAX_CELL_TEXT = 5000;
var MAX_PROMPT_TEXT = 1000;
var MAX_CATEGORY_LENGTH = 20;
var MAX_EXISTING_CATEGORIES = 100;

// ---------------------------------------------------------------------------
// 웹 엔드포인트
// ---------------------------------------------------------------------------

/** 주소가 살아 있는지 확인하는 용도. 비밀 정보는 돌려주지 않는다. */
function doGet() {
  return json_({ ok: true, service: 'biztalk4beyoundit' });
}

/**
 * 요청 형식 (JSON):
 * {
 *   "token": "...",                    // 스크립트 속성 API_TOKEN과 같아야 함
 *   "type": "messages" | "heartbeat",
 *   "appVersion": "1.0", "device": "SM-S921N",
 *   "messages": [{ "id": "중복 제거 키", "receivedAt": 1730000000000,
 *                  "sender": "홍길동", "room": "단톡방 이름 또는 빈 문자열",
 *                  "isGroup": false, "text": "메시지 원문" }]
 * }
 * 응답: { ok, done: [id...], retry: [id...], stored: n, aiError? }
 *   done  = 앱이 지워도 되는 메시지 (저장됨, 관련 없음, 중복, 형식 오류)
 *   retry = 앱이 보관했다가 나중에 다시 보내야 하는 메시지 (AI 판단 실패)
 * Apps Script 웹 앱은 항상 HTTP 200을 돌려주므로 앱은 ok 값으로 성공을 판단한다.
 */
function doPost(e) {
  var body;
  try {
    body = JSON.parse(e && e.postData ? e.postData.contents : '');
  } catch (err) {
    return json_({ ok: false, error: 'invalid_json' });
  }
  var props = getProps_();
  if (!props.API_TOKEN || !safeEqual_(String((body && body.token) || ''), props.API_TOKEN)) {
    return json_({ ok: false, error: 'unauthorized' });
  }
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) {
    return json_({ ok: false, error: 'busy' });
  }
  try {
    return json_(handleRequest_(body, props, new Date()));
  } catch (err) {
    recordError_(err);
    return json_({ ok: false, error: 'server_error', detail: String((err && err.message) || err) });
  } finally {
    lock.releaseLock();
  }
}

function handleRequest_(body, props, now) {
  var ss = getSpreadsheet_(props);
  var statusSheet = requireSheet_(ss, SHEET_NAMES.STATUS);

  // 어떤 요청이든 앱이 살아 있다는 뜻이다.
  setStatus_(statusSheet, STATUS_KEYS.HEARTBEAT, now);
  if (body.appVersion) setStatus_(statusSheet, STATUS_KEYS.APP_VERSION, String(body.appVersion).slice(0, 50));
  if (body.device) setStatus_(statusSheet, STATUS_KEYS.DEVICE, String(body.device).slice(0, 100));

  if (body.type === 'heartbeat') {
    return { ok: true, done: [], retry: [], stored: 0 };
  }
  if (body.type !== 'messages') {
    return { ok: false, error: 'unknown_type' };
  }
  if (!Array.isArray(body.messages)) {
    return { ok: false, error: 'messages_required' };
  }
  if (body.messages.length > MAX_MESSAGES_PER_REQUEST) {
    return { ok: false, error: 'too_many_messages', max: MAX_MESSAGES_PER_REQUEST };
  }

  var done = [];
  var retry = [];
  var valid = [];
  body.messages.forEach(function (raw) {
    var m = normalizeMessage_(raw);
    if (!m) {
      if (raw && typeof raw.id === 'string' && raw.id) done.push(raw.id);
      return;
    }
    valid.push(m);
  });
  if (valid.length === 0) {
    return { ok: true, done: done, retry: retry, stored: 0 };
  }

  var latest = valid.reduce(function (acc, m) { return Math.max(acc, m.receivedAt); }, 0);
  setStatus_(statusSheet, STATUS_KEYS.LAST_MESSAGE, new Date(latest));

  // 중복 제거: 이전 요청에서 처리했거나, 같은 묶음 안에서 반복된 메시지
  var processedSheet = requireSheet_(ss, SHEET_NAMES.PROCESSED);
  var processed = loadProcessedIds_(processedSheet);
  var fresh = [];
  valid.forEach(function (m) {
    if (processed[m.id]) {
      done.push(m.id);
    } else {
      processed[m.id] = true;
      fresh.push(m);
    }
  });
  if (fresh.length === 0) {
    return { ok: true, done: done, retry: retry, stored: 0 };
  }

  var designated = loadDesignated_(requireSheet_(ss, SHEET_NAMES.PEOPLE));
  fresh.forEach(function (m) { m.designated = !!designated[normalizeName_(m.sender)]; });

  var messagesSheet = requireSheet_(ss, SHEET_NAMES.MESSAGES);
  var categories = loadCategories_(messagesSheet);

  var verdicts = {};
  var aiError = null;
  try {
    verdicts = classifyMessages_(fresh, categories, props);
  } catch (err) {
    aiError = String((err && err.message) || err);
    recordError_(err);
  }

  var rows = [];
  var finished = [];
  fresh.forEach(function (m) {
    var v = verdicts[m.id];
    if (m.designated) {
      var cat = v ? matchCategory_(v.category, categories) : '';
      rows.push(buildRow_(m, cat || UNCLASSIFIED, REASON_DESIGNATED));
      finished.push(m.id);
    } else if (!v) {
      retry.push(m.id); // AI가 판단하지 못함 → 앱이 나중에 다시 보낸다
    } else if (v.relevant) {
      var category = matchCategory_(v.category, categories) || UNCLASSIFIED;
      if (categories.indexOf(category) < 0) categories.push(category);
      var reason = v.reason ? REASON_AI + ' – ' + v.reason : REASON_AI;
      rows.push(buildRow_(m, category, reason));
      finished.push(m.id);
    } else {
      finished.push(m.id); // 관련 없음으로 판단 → 저장하지 않음
    }
  });

  if (rows.length > 0) {
    appendMessageRows_(messagesSheet, rows);
  }
  appendProcessedIds_(processedSheet, finished, now);

  var result = { ok: true, done: done.concat(finished), retry: retry, stored: rows.length };
  if (aiError) result.aiError = aiError;
  return result;
}

// ---------------------------------------------------------------------------
// 메시지 정리
// ---------------------------------------------------------------------------

function normalizeMessage_(raw) {
  if (!raw || typeof raw !== 'object') return null;
  if (typeof raw.id !== 'string' || !raw.id || raw.id.length > 200) return null;
  var receivedAt = Number(raw.receivedAt);
  if (!isFinite(receivedAt) || receivedAt <= 0) return null;
  if (typeof raw.sender !== 'string' || !raw.sender.trim()) return null;
  if (typeof raw.text !== 'string') return null;
  return {
    id: raw.id,
    receivedAt: receivedAt,
    sender: raw.sender.trim().slice(0, 100),
    room: typeof raw.room === 'string' ? raw.room.trim().slice(0, 100) : '',
    isGroup: raw.isGroup === true,
    text: raw.text.slice(0, MAX_CELL_TEXT),
  };
}

/** 이름 비교용: 공백 제거, 소문자, 유니코드 정규화 */
function normalizeName_(name) {
  return String(name || '').normalize('NFC').replace(/\s+/g, '').toLowerCase();
}

/** 카테고리 비교용: 공백을 무시하고 같은 이름으로 본다 */
function categoryKey_(name) {
  return String(name || '').normalize('NFC').replace(/\s+/g, '').toLowerCase();
}

/**
 * AI가 준 카테고리를 다듬고, 기존 카테고리와 공백만 다르면 기존 이름을 쓴다.
 * 예: 기존 "고객요청" + AI "고객 요청" → "고객요청"
 */
function matchCategory_(category, existing) {
  var cleaned = String(category || '').replace(/\s+/g, ' ').trim().slice(0, MAX_CATEGORY_LENGTH);
  if (!cleaned) return '';
  var key = categoryKey_(cleaned);
  for (var i = 0; i < existing.length; i++) {
    if (categoryKey_(existing[i]) === key) return existing[i];
  }
  return cleaned;
}

/** 시트가 수식으로 해석하지 않도록 =, +, -, @ 로 시작하는 문자열 앞에 ' 를 붙인다 */
function safeCell_(value) {
  var s = String(value == null ? '' : value);
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function buildRow_(m, category, reason) {
  return [
    new Date(m.receivedAt),
    safeCell_(m.sender),
    safeCell_(m.isGroup ? m.room : ''),
    safeCell_(m.text),
    safeCell_(category),
    reason,
    REVIEW_OPTIONS[0],
  ];
}

// ---------------------------------------------------------------------------
// Gemini 판단
// ---------------------------------------------------------------------------

var SYSTEM_PROMPT = [
  '당신은 IT 프로젝트 컨설턴트(이하 "사용자")가 개인 카카오톡으로 받은 메시지를 분류하는 비서입니다.',
  '각 메시지마다 아래 세 가지를 판단합니다.',
  '',
  '1. relevant: 사용자의 비즈니스와 관련된 메시지인지',
  '   - true: 프로젝트 투입 의사 확인이나 제안, 프로젝트 참여·인력 요청, 고객사 요청이나 문의,',
  '     컨설턴트 섭외나 소개, 일정·단가·계약·면접 등 프로젝트 진행에 관한 내용',
  '   - false: 안부, 잡담, 가족·지인과의 사적인 대화, 광고, 이모티콘이나 감정 표현만 있는 메시지,',
  '     업무 맥락이 없는 짧은 대답',
  '   - 애매하면 true로 판단합니다. 놓치는 것보다 조금 더 저장하는 편이 낫습니다.',
  '2. category: 메시지를 묶을 짧은 카테고리 이름(2~15자)',
  '   - 특정 프로젝트명이나 고객사명이 드러나면 그 이름을 씁니다.',
  '   - 아니면 내용의 성격을 씁니다. 예: 투입 문의, 고객요청, 컨설턴트, 일정 조율, 단가 협의',
  '   - "기존 카테고리" 목록에 같은 뜻의 이름이 있으면 반드시 그 이름을 그대로 씁니다.',
  '     새 이름은 기존 목록에 맞는 것이 없을 때만 만듭니다.',
  '   - designated가 true인 메시지는 relevant와 상관없이 category를 붙입니다.',
  '   - designated가 false이고 relevant가 false면 category는 빈 문자열입니다.',
  '3. reason: 판단 근거를 한국어 20자 이내로',
  '',
  '같은 채팅방의 다른 메시지를 문맥으로 참고해도 됩니다.',
  '입력의 모든 key에 대해 결과를 정확히 하나씩 돌려줍니다.',
].join('\n');

var RESPONSE_SCHEMA = {
  type: 'OBJECT',
  properties: {
    results: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          key: { type: 'STRING' },
          relevant: { type: 'BOOLEAN' },
          category: { type: 'STRING' },
          reason: { type: 'STRING' },
        },
        required: ['key', 'relevant', 'category', 'reason'],
      },
    },
  },
  required: ['results'],
};

/** 프롬프트에는 짧은 key(m1, m2…)를 쓰고, 결과를 메시지 ID로 되돌린다. */
function buildPromptItems_(messages) {
  return messages.map(function (m, i) {
    return {
      key: 'm' + (i + 1),
      time: formatKst_(m.receivedAt),
      sender: m.sender,
      room: m.isGroup ? m.room : '(1:1 대화)',
      designated: !!m.designated,
      text: m.text.length > MAX_PROMPT_TEXT ? m.text.slice(0, MAX_PROMPT_TEXT) + '…(생략)' : m.text,
    };
  });
}

function buildUserPrompt_(items, categories) {
  return '기존 카테고리: ' + JSON.stringify(categories.slice(-MAX_EXISTING_CATEGORIES)) +
    '\n\n메시지 목록(JSON):\n' + JSON.stringify(items);
}

/** @return {Object<string, {relevant:boolean, category:string, reason:string}>} 메시지 ID별 판단 */
function classifyMessages_(messages, categories, props) {
  if (!props.GEMINI_API_KEY) throw new Error('GEMINI_API_KEY 스크립트 속성이 없습니다');
  var items = buildPromptItems_(messages);
  var request = {
    systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
    contents: [{ role: 'user', parts: [{ text: buildUserPrompt_(items, categories) }] }],
    generationConfig: {
      temperature: 0,
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
    },
  };
  var url = 'https://generativelanguage.googleapis.com/v1beta/models/' +
    encodeURIComponent(props.GEMINI_MODEL) + ':generateContent';
  var res = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: { 'x-goog-api-key': props.GEMINI_API_KEY },
    payload: JSON.stringify(request),
    muteHttpExceptions: true,
  });
  var code = res.getResponseCode();
  var text = res.getContentText();
  if (code !== 200) {
    throw new Error('Gemini HTTP ' + code + ': ' + String(text).slice(0, 300));
  }
  var byKey = parseGeminiResponse_(text);
  var verdicts = {};
  items.forEach(function (item, i) {
    if (byKey[item.key]) verdicts[messages[i].id] = byKey[item.key];
  });
  return verdicts;
}

/** Gemini 응답 본문에서 key별 판단을 꺼낸다. 형식이 틀린 항목은 버린다. */
function parseGeminiResponse_(responseText) {
  var data = JSON.parse(responseText);
  var candidate = data && data.candidates && data.candidates[0];
  var parts = candidate && candidate.content && candidate.content.parts;
  if (!parts || !parts.length) {
    var why = (candidate && candidate.finishReason) ||
      (data && data.promptFeedback && data.promptFeedback.blockReason) || 'empty';
    throw new Error('Gemini 응답이 비어 있습니다: ' + why);
  }
  var json = parts.map(function (p) { return p.text || ''; }).join('');
  var parsed = JSON.parse(json);
  var out = {};
  (parsed.results || []).forEach(function (r) {
    if (!r || typeof r.key !== 'string' || typeof r.relevant !== 'boolean') return;
    out[r.key] = {
      relevant: r.relevant,
      category: typeof r.category === 'string' ? r.category : '',
      reason: typeof r.reason === 'string' ? r.reason.trim().slice(0, 40) : '',
    };
  });
  return out;
}

// ---------------------------------------------------------------------------
// 시트 읽기·쓰기
// ---------------------------------------------------------------------------

function getSpreadsheet_(props) {
  if (props.SPREADSHEET_ID) return SpreadsheetApp.openById(props.SPREADSHEET_ID);
  return SpreadsheetApp.getActiveSpreadsheet();
}

function requireSheet_(ss, name) {
  var sheet = ss.getSheetByName(name);
  if (!sheet) throw new Error('"' + name + '" 탭이 없습니다. setup()을 먼저 실행하세요');
  return sheet;
}

function dataValues_(sheet, numCols) {
  var last = sheet.getLastRow();
  if (last < 2) return [];
  return sheet.getRange(2, 1, last - 1, numCols).getValues();
}

function loadDesignated_(peopleSheet) {
  var set = {};
  dataValues_(peopleSheet, 1).forEach(function (row) {
    var key = normalizeName_(row[0]);
    if (key) set[key] = true;
  });
  return set;
}

/** 메시지 탭에 이미 쓰인 카테고리 (최근에 쓰인 것이 뒤로 오게) */
function loadCategories_(messagesSheet) {
  var seen = {};
  var list = [];
  var values = dataValues_(messagesSheet, 5);
  for (var i = values.length - 1; i >= 0; i--) {
    var c = String(values[i][4] || '').trim();
    if (!c || c === UNCLASSIFIED) continue;
    var key = categoryKey_(c);
    if (seen[key]) continue;
    seen[key] = true;
    list.unshift(c);
  }
  return list;
}

function appendMessageRows_(sheet, rows) {
  var start = sheet.getLastRow() + 1;
  sheet.getRange(start, 1, rows.length, MESSAGE_HEADERS.length).setValues(rows);
}

function loadProcessedIds_(sheet) {
  var set = {};
  dataValues_(sheet, 1).forEach(function (row) { if (row[0]) set[String(row[0])] = true; });
  return set;
}

function appendProcessedIds_(sheet, ids, now) {
  if (!ids.length) return;
  var t = now.getTime();
  var rows = ids.map(function (id) { return [id, t]; });
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, 2).setValues(rows);
}

/** 오래된 처리 기록을 지운다. 앱의 재전송은 길어야 며칠 안에 일어난다. */
function pruneProcessed_(sheet, now, keepDays) {
  var values = dataValues_(sheet, 2);
  if (!values.length) return 0;
  var cutoff = now.getTime() - keepDays * 24 * 3600 * 1000;
  var kept = values.filter(function (row) { return Number(row[1]) >= cutoff; });
  var removed = values.length - kept.length;
  if (removed === 0) return 0;
  sheet.getRange(2, 1, values.length, 2).clearContent();
  if (kept.length) sheet.getRange(2, 1, kept.length, 2).setValues(kept);
  return removed;
}

function getStatus_(sheet, key) {
  var values = dataValues_(sheet, 2);
  for (var i = 0; i < values.length; i++) {
    if (values[i][0] === key) return values[i][1];
  }
  return '';
}

function setStatus_(sheet, key, value) {
  var values = dataValues_(sheet, 2);
  for (var i = 0; i < values.length; i++) {
    if (values[i][0] === key) {
      sheet.getRange(i + 2, 2).setValue(value);
      return;
    }
  }
  sheet.getRange(sheet.getLastRow() + 1, 1, 1, 2).setValues([[key, value]]);
}

function recordError_(err) {
  try {
    var ss = getSpreadsheet_(getProps_());
    var sheet = ss.getSheetByName(SHEET_NAMES.STATUS);
    if (sheet) {
      var msg = formatKst_(Date.now()) + ' ' + String((err && err.message) || err);
      setStatus_(sheet, STATUS_KEYS.LAST_ERROR, msg.slice(0, 500));
    }
  } catch (ignored) {
    // 오류 기록 실패로 원래 처리를 망치지 않는다.
  }
}

// ---------------------------------------------------------------------------
// 생존 감시 (시간 기반 트리거로 30분마다 실행)
// ---------------------------------------------------------------------------

function checkHeartbeat() {
  var props = getProps_();
  var now = new Date();
  var ss = getSpreadsheet_(props);
  var status = requireSheet_(ss, SHEET_NAMES.STATUS);
  evaluateHeartbeat_(status, props, now);
  pruneProcessed_(requireSheet_(ss, SHEET_NAMES.PROCESSED), now, Number(props.PROCESSED_KEEP_DAYS) || 14);
}

/** @return {'ok'|'alerted'|'recovered'|'waiting'|'no-signal'} 테스트와 로그용 결과 */
function evaluateHeartbeat_(statusSheet, props, now) {
  var last = toDate_(getStatus_(statusSheet, STATUS_KEYS.HEARTBEAT));
  if (!last) return 'no-signal';
  var alertedAt = toDate_(getStatus_(statusSheet, STATUS_KEYS.ALERTED_AT));
  var hours = Number(props.ALERT_HOURS) || Number(DEFAULTS.ALERT_HOURS);
  var stale = now.getTime() - last.getTime() > hours * 3600 * 1000;

  if (stale && !alertedAt) {
    sendAlert_(props, '[카톡 수집] 앱 신호가 끊겼습니다',
      '수집 앱에서 ' + hours + '시간 넘게 신호가 오지 않았습니다.\n' +
      '마지막 신호: ' + formatKst_(last.getTime()) + '\n\n' +
      '확인할 것:\n' +
      '- 폰이 켜져 있고 인터넷에 연결되어 있는지\n' +
      '- 수집 앱의 알림 접근 권한이 꺼지지 않았는지\n' +
      '- 배터리 설정에서 수집 앱이 "제한 없음"인지\n\n' +
      '신호가 다시 오면 복구 메일을 보냅니다.');
    setStatus_(statusSheet, STATUS_KEYS.ALERTED_AT, now);
    return 'alerted';
  }
  if (!stale && alertedAt) {
    sendAlert_(props, '[카톡 수집] 앱 신호가 복구되었습니다',
      '수집 앱 신호가 다시 들어오고 있습니다.\n' +
      '끊긴 시점: ' + formatKst_(alertedAt.getTime()) + ' 경 경고 발송\n' +
      '복구 확인: ' + formatKst_(last.getTime()));
    setStatus_(statusSheet, STATUS_KEYS.ALERTED_AT, '');
    return 'recovered';
  }
  return stale ? 'waiting' : 'ok';
}

function sendAlert_(props, subject, body) {
  var to = String(props.ALERT_EMAILS || '').split(',')
    .map(function (s) { return s.trim(); })
    .filter(function (s) { return s; });
  if (!to.length) throw new Error('ALERT_EMAILS 스크립트 속성이 없습니다');
  MailApp.sendEmail({ to: to.join(','), subject: subject, body: body });
}

// ---------------------------------------------------------------------------
// 처음 한 번 실행: 시트 양식, 토큰, 트리거
// ---------------------------------------------------------------------------

function setup() {
  var store = PropertiesService.getScriptProperties();
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) throw new Error('시트의 확장 프로그램 → Apps Script에서 연 프로젝트에서 실행하세요');
  store.setProperty('SPREADSHEET_ID', ss.getId());
  ss.setSpreadsheetTimeZone('Asia/Seoul');

  var messages = ensureSheet_(ss, SHEET_NAMES.MESSAGES, MESSAGE_HEADERS);
  messages.setColumnWidth(1, 130);
  messages.setColumnWidth(2, 100);
  messages.setColumnWidth(3, 140);
  messages.setColumnWidth(4, 420);
  messages.setColumnWidth(5, 120);
  messages.setColumnWidth(6, 200);
  messages.setColumnWidth(7, 90);
  var maxRows = messages.getMaxRows();
  messages.getRange(2, 1, maxRows - 1, 1).setNumberFormat('yyyy-mm-dd hh:mm');
  messages.getRange(2, 4, maxRows - 1, 1).setWrap(true);
  messages.getRange(2, 7, maxRows - 1, 1).setDataValidation(
    SpreadsheetApp.newDataValidation().requireValueInList(REVIEW_OPTIONS, true).setAllowInvalid(false).build());

  var people = ensureSheet_(ss, SHEET_NAMES.PEOPLE, PEOPLE_HEADERS);
  people.setColumnWidth(1, 160);
  people.setColumnWidth(2, 300);

  var status = ensureSheet_(ss, SHEET_NAMES.STATUS, ['항목', '값']);
  status.setColumnWidth(1, 160);
  status.setColumnWidth(2, 320);
  Object.keys(STATUS_KEYS).forEach(function (k) {
    if (!hasStatusRow_(status, STATUS_KEYS[k])) {
      status.getRange(status.getLastRow() + 1, 1, 1, 2).setValues([[STATUS_KEYS[k], '']]);
    }
  });
  status.getRange(2, 2, Object.keys(STATUS_KEYS).length, 1).setNumberFormat('yyyy-mm-dd hh:mm:ss');

  var processed = ensureSheet_(ss, SHEET_NAMES.PROCESSED, PROCESSED_HEADERS);
  processed.hideSheet();

  if (!store.getProperty('API_TOKEN')) {
    store.setProperty('API_TOKEN', (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, ''));
  }

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'checkHeartbeat') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('checkHeartbeat').timeBased().everyMinutes(30).create();

  var props = getProps_();
  var missing = ['GEMINI_API_KEY', 'ALERT_EMAILS'].filter(function (k) { return !props[k]; });
  Logger.log('설정 완료. 앱에 입력할 API_TOKEN: ' + props.API_TOKEN);
  Logger.log(missing.length
    ? '아직 없는 스크립트 속성: ' + missing.join(', ') + ' → 프로젝트 설정 → 스크립트 속성에서 추가하세요'
    : '필수 스크립트 속성이 모두 있습니다.');
}

function ensureSheet_(ss, name, headers) {
  var sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
  sheet.setFrozenRows(1);
  return sheet;
}

function hasStatusRow_(sheet, key) {
  return dataValues_(sheet, 1).some(function (row) { return row[0] === key; });
}

/** 편집기에서 실행: Gemini 키와 프롬프트가 동작하는지 샘플 메시지로 확인한다. 시트에는 쓰지 않는다. */
function testGemini() {
  var now = Date.now();
  var samples = [
    { id: 't1', receivedAt: now, sender: '김부장', room: '', isGroup: false, designated: false,
      text: '다음 달부터 OO은행 차세대 프로젝트 PMO 자리 있는데 투입 가능하실까요? 단가는 협의 가능합니다.' },
    { id: 't2', receivedAt: now, sender: '엄마', room: '', isGroup: false, designated: false,
      text: '저녁 먹었니? 주말에 집에 올 거야?' },
    { id: 't3', receivedAt: now, sender: '이대리', room: 'ERP 컨설턴트 모임', isGroup: true, designated: false,
      text: 'SAP FI 컨설턴트 한 분 소개 부탁드립니다. 11월 중순 시작, 6개월입니다.' },
  ];
  var verdicts = classifyMessages_(samples, ['투입 문의', '고객요청'], getProps_());
  samples.forEach(function (m) {
    Logger.log(m.sender + ': ' + JSON.stringify(verdicts[m.id] || '(결과 없음)'));
  });
}

// ---------------------------------------------------------------------------
// 공통
// ---------------------------------------------------------------------------

function getProps_() {
  var stored = PropertiesService.getScriptProperties().getProperties();
  var out = {};
  Object.keys(DEFAULTS).forEach(function (k) { out[k] = DEFAULTS[k]; });
  Object.keys(stored).forEach(function (k) { if (stored[k] !== '') out[k] = stored[k]; });
  return out;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

/** 길이와 무관하게 끝까지 비교해 토큰 추측을 어렵게 한다 */
function safeEqual_(a, b) {
  var diff = a.length ^ b.length;
  var n = Math.max(a.length, b.length);
  for (var i = 0; i < n; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

function toDate_(v) {
  if (v instanceof Date) return v;
  if (v === '' || v == null) return null;
  var d = new Date(v);
  return isNaN(d.getTime()) ? null : d;
}

function formatKst_(ms) {
  var d = new Date(Number(ms) + 9 * 3600 * 1000);
  return d.toISOString().slice(0, 16).replace('T', ' ');
}
