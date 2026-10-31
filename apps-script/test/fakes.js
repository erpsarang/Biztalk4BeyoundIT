// Apps Script 전역 객체를 메모리로 흉내 내서 Code.js를 Node에서 실행한다.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeRange {
  constructor(sheet, row, col, numRows, numCols) {
    if (numRows < 1 || numCols < 1) {
      throw new Error(`The number of rows/columns in the range must be at least 1 (${numRows}x${numCols})`);
    }
    Object.assign(this, { sheet, row, col, numRows, numCols });
  }
  getValues() {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const line = [];
      for (let c = 0; c < this.numCols; c++) line.push(this.sheet.cell(this.row + r, this.col + c));
      out.push(line);
    }
    return out;
  }
  setValues(values) {
    if (values.length !== this.numRows || values.some((l) => l.length !== this.numCols)) {
      throw new Error('setValues: dimensions do not match the range');
    }
    values.forEach((line, r) => line.forEach((v, c) => this.sheet.setCell(this.row + r, this.col + c, v)));
    return this;
  }
  setValue(v) {
    this.sheet.setCell(this.row, this.col, v);
    return this;
  }
  clearContent() {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) this.sheet.setCell(this.row + r, this.col + c, '');
    }
    return this;
  }
  setFontWeight() { return this; }
  setNumberFormat() { return this; }
  setWrap() { return this; }
  setDataValidation() { return this; }
}

class FakeSheet {
  constructor(name) {
    this.name = name;
    this.rows = []; // rows[r-1][c-1]
    this.hidden = false;
  }
  cell(r, c) {
    const line = this.rows[r - 1];
    return line && line[c - 1] !== undefined ? line[c - 1] : '';
  }
  setCell(r, c, v) {
    while (this.rows.length < r) this.rows.push([]);
    this.rows[r - 1][c - 1] = v;
  }
  getLastRow() {
    for (let r = this.rows.length; r >= 1; r--) {
      if ((this.rows[r - 1] || []).some((v) => v !== '' && v !== undefined)) return r;
    }
    return 0;
  }
  getMaxRows() { return 1000; }
  getRange(row, col, numRows = 1, numCols = 1) { return new FakeRange(this, row, col, numRows, numCols); }
  setFrozenRows() {}
  setColumnWidth() {}
  hideSheet() { this.hidden = true; }
  getName() { return this.name; }
  /** 테스트 편의: 헤더 아래 데이터 행 */
  data() {
    const last = this.getLastRow();
    return last < 2 ? [] : this.getRange(2, 1, last - 1, Math.max(...this.rows.map((l) => l.length))).getValues();
  }
}

class FakeSpreadsheet {
  constructor() { this.sheets = new Map(); this.timeZone = null; }
  getId() { return 'fake-spreadsheet-id'; }
  getSheetByName(name) { return this.sheets.get(name) || null; }
  insertSheet(name) { const s = new FakeSheet(name); this.sheets.set(name, s); return s; }
  setSpreadsheetTimeZone(tz) { this.timeZone = tz; }
}

function geminiReply(results) {
  return JSON.stringify({
    candidates: [{ content: { parts: [{ text: JSON.stringify({ results }) }] }, finishReason: 'STOP' }],
  });
}

/**
 * @param {object} opts
 * @param {(request: object) => {code:number, body:string}} [opts.gemini] Gemini 응답을 만드는 함수
 */
function load(opts = {}) {
  const ss = new FakeSpreadsheet();
  const props = new Map(Object.entries(opts.props || {}));
  const mails = [];
  const fetches = [];
  const triggers = [];
  const logs = [];
  const gemini = opts.gemini || (() => ({ code: 500, body: 'no gemini mock' }));

  const context = {
    console,
    JSON, Date, Math, Object, Array, String, Number, Boolean, Error, isFinite, isNaN, encodeURIComponent,
    SpreadsheetApp: {
      getActiveSpreadsheet: () => ss,
      openById: () => ss,
      newDataValidation: () => {
        const b = { requireValueInList: () => b, setAllowInvalid: () => b, build: () => ({}) };
        return b;
      },
    },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperties: () => Object.fromEntries(props),
        getProperty: (k) => (props.has(k) ? props.get(k) : null),
        setProperty: (k, v) => { props.set(k, v); },
      }),
    },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: (s) => ({ content: s, setMimeType() { return this; }, getContent() { return this.content; } }),
    },
    UrlFetchApp: {
      fetch: (url, options) => {
        const request = JSON.parse(options.payload);
        fetches.push({ url, options, request });
        const { code, body } = gemini(request);
        return { getResponseCode: () => code, getContentText: () => body };
      },
    },
    MailApp: { sendEmail: (m) => { mails.push(m); } },
    Utilities: { getUuid: () => '1234abcd-0000-4000-8000-' + String(Math.random()).slice(2, 14).padEnd(12, '0') },
    ScriptApp: {
      getProjectTriggers: () => triggers.slice(),
      deleteTrigger: (t) => { triggers.splice(triggers.indexOf(t), 1); },
      newTrigger: (fn) => {
        const b = { timeBased: () => b, everyMinutes: (n) => { b.minutes = n; return b; },
          create: () => { const t = { getHandlerFunction: () => fn, minutes: b.minutes }; triggers.push(t); return t; } };
        return b;
      },
    },
    Logger: { log: (s) => { logs.push(String(s)); } },
  };
  vm.createContext(context);
  const code = fs.readFileSync(path.join(__dirname, '..', 'src', 'Code.js'), 'utf8');
  vm.runInContext(code, context, { filename: 'Code.js' });
  return { ctx: context, ss, props, mails, fetches, triggers, logs };
}

module.exports = { load, geminiReply };
