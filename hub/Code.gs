/**
 * Now hub: the shared log for the phone app and the Windows Now widget (see PROTOCOL.md).
 *
 * Setup (once):
 *  1. Make a new Google Sheet (any name, e.g. "Now hub"), then Extensions → Apps Script.
 *  2. Replace Code.gs with this file and save.
 *  3. Choose the function "setup" at the top and click Run. Allow access when asked (it's your own
 *     sheet). Then View → Logs (or the execution log) shows your hub key.
 *  4. Deploy → New deployment → type "Web app". Execute as: Me. Who has access: Anyone.
 *     Deploy, and copy the web app URL (it ends in /exec).
 *  5. Paste the URL and the key into the phone app (Settings → Hub) and the Windows Now widget (⚙).
 *
 * "Anyone" means anyone with the URL can reach it, but nothing happens without the key.
 * After changing this script: Deploy → Manage deployments → edit → Version: New version.
 *
 * The "log" sheet is append-only: never sort or delete its rows (a row's position is its seq).
 */

const SHEET = 'log';
const HEADER = ['received', 'device', 'id', 'kind', 'version', 'deleted', 'record'];

function doPost(e) {
  let req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply({ ok: false, error: 'bad request' });
  }
  const key = PropertiesService.getScriptProperties().getProperty('KEY');
  if (!key || req.key !== key) return reply({ ok: false, error: 'wrong key' });

  const lock = LockService.getScriptLock();
  lock.waitLock(25000);
  try {
    const sh = logSheet();
    const push = Array.isArray(req.push) ? req.push : [];
    if (push.length) {
      const now = new Date();
      const rows = push
        .filter(r => r && r.id && r.k)
        .map(r => [now, String(r.dev || req.dev || ''), String(r.id), String(r.k), Number(r.v || 0), r.del ? 1 : 0, fit(r)]);
      if (rows.length) sh.getRange(sh.getLastRow() + 1, 1, rows.length, HEADER.length).setValues(rows);
    }

    const last = sh.getLastRow() - 1; // data rows; row (seq + 1) holds seq
    const since = Math.max(0, Math.min(last, Number(req.since || 0)));
    const limit = Math.min(1000, Math.max(1, Number(req.limit || 500)));
    const n = Math.min(limit, last - since);
    const changes = [];
    if (n > 0) {
      const vals = sh.getRange(since + 2, 7, n, 1).getValues();
      for (let i = 0; i < vals.length; i++) {
        try {
          const r = JSON.parse(vals[i][0]);
          r.seq = since + 1 + i;
          changes.push(r);
        } catch (err) {
          // A hand-edited cell: skip it.
        }
      }
    }
    const seq = since + Math.max(0, n);
    return reply({ ok: true, seq: seq, last: last, more: seq < last, changes: changes });
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return ContentService.createTextOutput('The Now hub is running. Devices talk to it with POST.');
}

/** Run once from the editor: makes the log sheet and a key, and shows the key in the log. */
function setup() {
  const props = PropertiesService.getScriptProperties();
  let key = props.getProperty('KEY');
  if (!key) {
    key = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '').slice(0, 8);
    props.setProperty('KEY', key);
  }
  logSheet();
  Logger.log('Your hub key: ' + key);
}

function logSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET);
  if (!sh) {
    sh = ss.insertSheet(SHEET);
    sh.getRange(1, 1, 1, HEADER.length).setValues([HEADER]);
    sh.setFrozenRows(1);
  }
  return sh;
}

/** A sheet cell holds at most 50,000 characters: a very long note is cut to fit rather than blocking the sync. */
function fit(r) {
  let s = JSON.stringify(r);
  if (s.length > 49000 && typeof r.text === 'string') {
    r.text = r.text.slice(0, Math.max(0, r.text.length - (s.length - 48000))) + ' …';
    s = JSON.stringify(r);
  }
  return s;
}

function reply(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
