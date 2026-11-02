/**
 * AVIP Solar Install Check: backend for the Android app.
 *
 * Bound to the Google Sheet "AVIP Solar Install Check – Database".
 * Setup (once, from the Founder/CEO Google account that owns the sheet):
 *   1. Open the sheet → Extensions → Apps Script → paste this file → Save.
 *   2. Paste AuthClient.gs as a second file; add AUTH_URL and AUTH_KEY in Script properties.
 *      Run "setup" once and allow access (creates the photo folder, checks the common login).
 *   3. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *   4. Put the /exec URL in config.json in the app's GitHub repo ("endpoint").
 * After any change to this file: Deploy → Manage deployments → Edit → New version.
 *
 * The phone never sees the sheet. It sends JSON to this web app, signed with
 * SHARED_KEY, and every call except login carries the user's session token.
 *
 * Login is common to all AVIP Solar apps (AVIP Team Directory → AVIP Auth):
 * paste AuthClient.gs (from the AVIPSolarExpense repo, auth-service/) as a second
 * file here, and add AUTH_URL and AUTH_KEY in Project Settings → Script properties.
 */

const SHARED_KEY = 'avip-install-9Kp2';
const TZ = 'Asia/Kolkata';
const PHOTO_ROOT = 'AVIP Install Check Photos';

const S = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted for Verification',
  SENT_BACK: 'Sent Back to Sales',
  APPROVED: 'Approved for Dispatch',
  DISPATCHED: 'Dispatched',
  BALANCE: 'Balance Pending',
  IN_PROGRESS: 'Installation In Progress',
  INSTALL_SUBMITTED: 'Installation Submitted',
  REWORK: 'Rework',
  CLOSED: 'QC Approved (Closed)',
};

// Fields each team may set on a site with "saveSite".
const SALES_FIELDS = ['Customer Name', 'Mobile', 'WhatsApp', 'Address', 'District', 'Latitude', 'Longitude',
  'Google Map Link', 'Site Size (kW)', 'System Type', 'PM Surya Ghar App No.', 'DISCOM', 'CA No.',
  'Survey By', 'Survey Date', 'Sales Note'];
const MANAGER_FIELDS = SALES_FIELDS.concat(['Sales Person', 'Dispatch Date', 'Installation Team',
  'Installation Date', 'Site Incharge', 'Manager Note']);

// ------------------------------------------------------------------ web app

function doGet() {
  return out({ ok: true, app: 'AVIP Solar Install Check' });
}

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return out({ ok: false, error: 'Not allowed' });
    const handler = HANDLERS[d.kind];
    if (!handler) return out({ ok: false, error: 'unknown kind' });
    const lock = LockService.getScriptLock();
    lock.waitLock(30000);
    try {
      const result = handler(d) || {};
      result.ok = true;
      return out(result);
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return out({ ok: false, error: String((err && err.message) || err) });
  }
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

const HANDLERS = {
  login: login,
  setPin: setPin,
  bootstrap: bootstrap,
  sites: sites,
  saveSite: saveSite,
  verify: verify,
  dispatch: dispatch,
  serial: serial,
  serialDelete: serialDelete,
  serialMatch: serialMatch,
  photo: photo,
  checklist: checklist,
  pending: pending,
  submitInstall: submitInstall,
  report: report,
  sentToCustomer: sentToCustomer,
  qc: qc,
  salesVisit: salesVisit,
};

/** Run once from the editor. */
function setup() {
  photoRoot();
  const p = PropertiesService.getScriptProperties();
  if (!p.getProperty('AUTH_URL') || !p.getProperty('AUTH_KEY')) Logger.log('Add AUTH_URL and AUTH_KEY in Project Settings → Script properties (common AVIP login).');
  else Logger.log('Common login OK: ' + teamPeople().length + ' people can use AVIP Solar Installation.');
  Logger.log('Ready. Now deploy as a web app (Execute as: Me, Who has access: Anyone).');
  Logger.log('Link key for the AVIP Site Visits Log script (INSTALL_CHECK_KEY): ' + linkKey());
}

/** Separate key for the AVIP Solar Sales link, kept only in the two Google scripts. */
function linkKey() {
  const p = PropertiesService.getScriptProperties();
  let k = p.getProperty('LINK_KEY');
  if (!k) {
    k = Utilities.getUuid();
    p.setProperty('LINK_KEY', k);
  }
  return k;
}

// ------------------------------------------------------------------ sheet helpers

function book() { return SpreadsheetApp.getActiveSpreadsheet(); }

function table(name) {
  const sh = book().getSheetByName(name);
  if (!sh) throw new Error('Tab missing: ' + name);
  const headers = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0].map(function (h) { return String(h).trim(); });
  return {
    sh: sh,
    headers: headers,
    col: function (h) {
      const i = headers.indexOf(h);
      if (i < 0) throw new Error('Column missing: ' + name + ' → ' + h);
      return i + 1;
    },
  };
}

/** Last row that has something in column A (ignores formulas further right). */
function lastRow(sh) {
  const n = sh.getLastRow();
  if (n < 2) return 1;
  const v = sh.getRange(2, 1, n - 1, 1).getValues();
  for (let i = v.length - 1; i >= 0; i--) if (String(v[i][0]).trim() !== '') return i + 2;
  return 1;
}

/** All data rows as objects keyed by header, plus _row. */
function rows(name) {
  const t = table(name);
  const n = lastRow(t.sh);
  if (n < 2) return [];
  const vals = t.sh.getRange(2, 1, n - 1, t.headers.length).getValues();
  const list = [];
  vals.forEach(function (r, i) {
    if (String(r[0]).trim() === '') return;
    const o = { _row: i + 2 };
    t.headers.forEach(function (h, j) { if (h) o[h] = r[j]; });
    list.push(o);
  });
  return list;
}

/** Writes the given fields into one row (cell by cell, so formula columns are never touched). */
function setFields(name, row, obj) {
  const t = table(name);
  Object.keys(obj).forEach(function (k) {
    if (k.charAt(0) === '_') return;
    t.sh.getRange(row, t.col(k)).setValue(obj[k] === undefined || obj[k] === null ? '' : obj[k]);
  });
}

function append(name, obj) {
  const t = table(name);
  const row = lastRow(t.sh) + 1;
  if (row > t.sh.getMaxRows()) t.sh.insertRowsAfter(t.sh.getMaxRows(), 200);
  setFields(name, row, obj);
  return row;
}

function clean(v, max) {
  const s = String(v === undefined || v === null ? '' : v).trim();
  return max ? s.slice(0, max) : s;
}

function last10(v) { return String(v || '').replace(/\D/g, '').slice(-10); }

function now() { return new Date(); }

function iso(v) {
  if (v instanceof Date) return Utilities.formatDate(v, TZ, 'yyyy-MM-dd HH:mm');
  return v === undefined || v === null ? '' : v;
}

/** "2026-10-30" from the phone → a real date in the sheet. */
function toDate(v) {
  const s = clean(v);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : s;
}

function num(v) {
  const n = Number(v);
  return isFinite(n) ? n : 0;
}

function plain(o) {
  const r = {};
  Object.keys(o).forEach(function (k) { if (k.charAt(0) !== '_') r[k] = iso(o[k]); });
  return r;
}

// ------------------------------------------------------------------ login (AVIP Team Directory)
//
// Login, PINs and teams come from the AVIP Team Directory through AVIP Auth
// (AuthClient.gs, pasted next to this file; AUTH_URL and AUTH_KEY in Script properties).
// The person's "Installation App Role" there (Sales, Manager, Dispatch, Installation)
// is their team in this app. The old Users tab of this sheet is no longer used.

const APP = 'installation';
const TEAMS = ['Sales', 'Manager', 'Dispatch', 'Installation'];

/** The directory person, shaped like the old Users-tab row the rest of this file reads. */
function localUser(a) {
  const t = clean(a.role);
  if (TEAMS.indexOf(t) < 0) throw new Error('Your Installation App Role "' + t + '" is not one of ' + TEAMS.join(', ') + '. Ask the office.');
  return { 'User ID': clean(a.id), 'Name': clean(a.name), 'Mobile': clean(a.mobile), 'Team': t, 'Designation': clean(a.designation) };
}

function userInfo(u) {
  return {
    id: clean(u['User ID']), name: clean(u['Name']), mobile: last10(u['Mobile']),
    team: clean(u['Team']), designation: clean(u['Designation']),
  };
}

/** Checks the session token with AVIP Auth; returns the user. */
function auth(d, allowTempPin) {
  return localUser(authUser_(APP, d, allowTempPin));
}

function team(u) { return clean(u['Team']); }
function isManager(u) { return team(u) === 'Manager'; }

function need(u, teams) {
  if (teams.indexOf(team(u)) < 0) throw new Error('Your team cannot do this.');
}

function login(d) {
  const r = authLogin_(APP, d);
  return { user: userInfo(localUser(r.user)), token: r.token, mustSetPin: r.mustSetPin };
}

function setPin(d) {
  const r = authSetPin_(APP, d);
  return { token: r.token, user: userInfo(localUser(r.user)) };
}

/** People in the directory who use this app, as Users-tab-like rows. */
function teamPeople() {
  return authUsers_(APP).filter(function (p) { return TEAMS.indexOf(clean(p.role)) >= 0; }).map(localUser);
}

function findUserByMobile(mobile) {
  const m = last10(mobile);
  return teamPeople().filter(function (u) { return last10(u['Mobile']) === m; })[0];
}

// ------------------------------------------------------------------ reference data

function bootstrap(d) {
  const u = auth(d);
  const bom = rows('BOM Master')
    .filter(function (r) { return clean(r['Active']).toLowerCase() !== 'no'; })
    .map(function (r) {
      return {
        sr: clean(r['Sr. No.']), section: clean(r['Section']), item: clean(r['Item']), make: clean(r['Make / Size']),
        unit: clean(r['Unit']), qty: num(r['Default Qty (per site)'] !== undefined ? r['Default Qty (per site)'] : r['Default Qty']),
        serial: clean(r['Serial No. Photo Needed']).toLowerCase() === 'yes',
      };
    });
  const checklist = rows('Checklist Master')
    .filter(function (r) { return clean(r['Active']).toLowerCase() !== 'no'; })
    .map(function (r) {
      return {
        id: clean(r['Item ID']), section: clean(r['Section']), text: clean(r['Checklist Item (App text)']),
        critical: clean(r['Critical']).toLowerCase() === 'yes',
        photoOnNotOk: clean(r['Photo Needed on Not OK']).toLowerCase() === 'yes',
      };
    });
  const users = teamPeople();
  return {
    user: userInfo(u),
    bom: bom,
    checklist: checklist,
    installers: users.filter(function (x) { return team(x) === 'Installation'; }).map(function (x) { return clean(x['Name']); }),
    salesPeople: users.filter(function (x) { return team(x) === 'Sales' || team(x) === 'Manager'; }).map(function (x) { return clean(x['Name']); }),
  };
}

// ------------------------------------------------------------------ sites

function balanceOf(m) {
  const approved = m['Qty Approved (Manager)'];
  const need = approved === '' || approved === null || approved === undefined ? num(m['Qty Required (Sales)']) : num(approved);
  return need - num(m['Qty Dispatched']);
}

function sites(d) {
  const u = auth(d);
  const me = clean(u['Name']).toLowerCase();
  const t = team(u);
  const all = rows('Sites');
  const mats = rows('Site Materials');
  const byId = function (list) {
    const map = {};
    list.forEach(function (r) { const k = clean(r['Site ID']); (map[k] = map[k] || []).push(r); });
    return map;
  };
  const matMap = byId(mats);
  const hasBalance = function (id) {
    return (matMap[id] || []).some(function (m) { return balanceOf(m) > 0; });
  };
  const after = [S.DISPATCHED, S.BALANCE, S.IN_PROGRESS, S.INSTALL_SUBMITTED, S.REWORK];
  const cutoff = new Date(Date.now() - 60 * 86400000);

  const visible = all.filter(function (s) {
    const st = clean(s['Status']);
    const id = clean(s['Site ID']);
    if (t === 'Manager') return st !== S.CLOSED || !(s['Last Updated'] instanceof Date) || s['Last Updated'] > cutoff;
    if (t === 'Sales') return clean(s['Sales Person']).toLowerCase() === me;
    if (t === 'Dispatch') return st === S.APPROVED || st === S.BALANCE || (after.indexOf(st) >= 0 && hasBalance(id));
    if (t === 'Installation') return clean(s['Installation Team']).toLowerCase() === me &&
      [S.DISPATCHED, S.BALANCE, S.IN_PROGRESS, S.INSTALL_SUBMITTED, S.REWORK].indexOf(st) >= 0;
    return false;
  });

  const ids = {};
  visible.forEach(function (s) { ids[clean(s['Site ID'])] = true; });
  const pick = function (name) { return byId(rows(name).filter(function (r) { return ids[clean(r['Site ID'])]; })); };
  const serials = pick('Serial Numbers');
  const results = pick('Checklist Results');
  const photos = pick('Photos');
  const pend = pick('Pending Work');
  const signs = pick('Sign-off & QC');

  const list = visible.map(function (s) {
    const id = clean(s['Site ID']);
    const o = plain(s);
    o.materials = (matMap[id] || []).map(function (m) { const p = plain(m); p['Balance'] = balanceOf(m); return p; });
    o.serials = (serials[id] || []).map(plain);
    o.checklist = (results[id] || []).map(plain);
    o.photos = (photos[id] || []).map(plain);
    o.pending = (pend[id] || []).map(plain);
    o.signoffs = (signs[id] || []).map(plain);
    return o;
  });
  list.sort(function (a, b) { return String(b['Last Updated']).localeCompare(String(a['Last Updated'])); });
  return { sites: list };
}

function findSite(id) {
  const s = rows('Sites').filter(function (r) { return clean(r['Site ID']) === clean(id); })[0];
  if (!s) throw new Error('Site not found: ' + id);
  return s;
}

function newSiteId() {
  const day = Utilities.formatDate(now(), TZ, 'yyMMdd');
  const prefix = 'S' + day + '-';
  let max = 0;
  rows('Sites').forEach(function (r) {
    const id = clean(r['Site ID']);
    if (id.indexOf(prefix) === 0) max = Math.max(max, Number(id.slice(prefix.length)) || 0);
  });
  return prefix + ('00' + (max + 1)).slice(-3);
}

function logStatus(siteId, from, to, u, note) {
  if (from === to) return;
  append('Status Log', {
    'Timestamp': now(), 'Site ID': siteId, 'From Status': from, 'To Status': to,
    'Changed By': u ? clean(u['Name']) : 'AVIP Solar Sales', 'Team': u ? team(u) : 'Sales', 'Note': clean(note, 500),
  });
}

function setStatus(site, to, u, note, extra) {
  const from = clean(site['Status']);
  const fields = extra || {};
  fields['Status'] = to;
  fields['Last Updated'] = now();
  setFields('Sites', site._row, fields);
  logStatus(clean(site['Site ID']), from, to, u, note);
}

function touch(site) { setFields('Sites', site._row, { 'Last Updated': now() }); }

/** Material rows of one site, keyed by Sr. No. */
function materialRows(siteId) {
  const map = {};
  rows('Site Materials').forEach(function (m) {
    if (clean(m['Site ID']) === siteId) map[clean(m['Sr. No.'])] = m;
  });
  return map;
}

function writeMaterials(siteId, list, qtyColumn, extra) {
  const existing = materialRows(siteId);
  (list || []).forEach(function (m) {
    const sr = clean(m.sr);
    if (!sr) return;
    const fields = {};
    fields[qtyColumn] = num(m.qty);
    if (m.make !== undefined) fields['Make / Size'] = clean(m.make, 100);
    if (m.remarks !== undefined) fields['Remarks'] = clean(m.remarks, 300);
    Object.keys(extra || {}).forEach(function (k) { fields[k] = extra[k]; });
    if (existing[sr]) {
      setFields('Site Materials', existing[sr]._row, fields);
    } else {
      fields['Site ID'] = siteId;
      fields['Sr. No.'] = "'" + sr; // keep 3.10 as text
      fields['Item'] = clean(m.item, 200);
      fields['Unit'] = clean(m.unit, 20);
      if (fields['Make / Size'] === undefined) fields['Make / Size'] = clean(m.make, 100);
      append('Site Materials', fields);
    }
  });
}

function saveSite(d) {
  const u = auth(d);
  need(u, ['Sales', 'Manager']);
  const input = d.site || {};
  const allowed = isManager(u) ? MANAGER_FIELDS : SALES_FIELDS;
  const fields = {};
  allowed.forEach(function (k) {
    if (input[k] === undefined) return;
    let v = clean(input[k], 1000);
    if (k === 'Mobile' || k === 'WhatsApp' || k === 'PM Surya Ghar App No.' || k === 'CA No.') v = v ? "'" + v : '';
    if (k === 'Survey Date' || k === 'Dispatch Date' || k === 'Installation Date') v = toDate(v);
    if (k === 'Site Size (kW)' || k === 'Latitude' || k === 'Longitude') v = v === '' ? '' : num(v);
    fields[k] = v;
  });
  if (fields['Latitude'] && fields['Longitude'] && !fields['Google Map Link']) {
    fields['Google Map Link'] = 'https://maps.google.com/?q=' + fields['Latitude'] + ',' + fields['Longitude'];
  }

  let site;
  let id = clean(input['Site ID']);
  if (!id) {
    id = newSiteId();
    fields['Site ID'] = id;
    fields['Created On'] = now();
    if (!isManager(u) || !fields['Sales Person']) fields['Sales Person'] = clean(u['Name']);
    fields['Status'] = S.DRAFT;
    fields['Source'] = 'Install Check';
    fields['Last Updated'] = now();
    append('Sites', fields);
    site = findSite(id);
    logStatus(id, '', S.DRAFT, u, 'Site created');
  } else {
    site = findSite(id);
    const st = clean(site['Status']);
    if (!isManager(u)) {
      if (clean(site['Sales Person']).toLowerCase() !== clean(u['Name']).toLowerCase()) throw new Error('This is not your site.');
      if (st !== S.DRAFT && st !== S.SENT_BACK) throw new Error('Already sent to the Manager; ask the Manager to change it.');
    }
    fields['Last Updated'] = now();
    setFields('Sites', site._row, fields);
  }

  if (d.materials) {
    writeMaterials(id, d.materials, isManager(u) && !d.asSales ? 'Qty Approved (Manager)' : 'Qty Required (Sales)');
  }

  if (d.submit) {
    site = findSite(id);
    const st = clean(site['Status']);
    if (st === S.DRAFT || st === S.SENT_BACK) setStatus(site, S.SUBMITTED, u, 'Sent for verification');
  }
  return { siteId: id };
}

function verify(d) {
  const u = auth(d);
  need(u, ['Manager']);
  const site = findSite(d.siteId);
  const note = clean(d.note, 500);
  if (d.action === 'sendBack') {
    if (!note) throw new Error('Write a note for Sales.');
    setStatus(site, S.SENT_BACK, u, note, { 'Manager Note': note });
    return {};
  }
  if (!clean(d.installationTeam)) throw new Error('Choose the installation team.');
  if (!clean(d.dispatchDate) || !clean(d.installationDate)) throw new Error('Set dispatch and installation dates.');
  if (d.materials) writeMaterials(clean(site['Site ID']), d.materials, 'Qty Approved (Manager)');
  setStatus(site, S.APPROVED, u, note || 'Approved', {
    'Manager Note': note, 'Verified By': clean(u['Name']), 'Verified On': now(),
    'Dispatch Date': toDate(d.dispatchDate), 'Installation Date': toDate(d.installationDate),
    'Installation Team': clean(d.installationTeam), 'Site Incharge': clean(d.siteIncharge || d.installationTeam),
  });
  return {};
}

// ------------------------------------------------------------------ dispatch

function dispatch(d) {
  const u = auth(d);
  need(u, ['Dispatch', 'Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const st = clean(site['Status']);
  if ([S.DRAFT, S.SUBMITTED, S.SENT_BACK].indexOf(st) >= 0) throw new Error('This site is not approved for dispatch yet.');

  writeMaterials(id, d.materials, 'Qty Dispatched', {
    'Dispatch Date': now(), 'Dispatched By': clean(u['Name']),
  });
  const mr = materialRows(id);
  const pendingBalance = Object.keys(mr).some(function (k) { return balanceOf(mr[k]) > 0; });

  const trips = rows('Dispatch Log').filter(function (r) { return clean(r['Site ID']) === id; }).length;
  const loading = rows('Photos').filter(function (p) {
    return clean(p['Site ID']) === id && clean(p['Photo Type']) === 'Loading';
  }).pop();
  append('Dispatch Log', {
    'Dispatch ID': id + '-D' + (trips + 1), 'Site ID': id, 'Dispatch Date': now(),
    'Vehicle No.': clean(d.vehicle, 30), 'Driver Mobile': d.driver ? "'" + clean(d.driver, 15) : '',
    'Loading Photo Link': loading ? loading['Drive Link'] : '', 'Dispatched By': clean(u['Name']),
    'Balance Pending': pendingBalance ? 'Yes' : 'No', 'Notes': clean(d.notes, 500),
  });

  if (st === S.APPROVED || st === S.BALANCE || st === S.DISPATCHED) {
    setStatus(site, pendingBalance ? S.BALANCE : S.DISPATCHED, u, pendingBalance ? 'Dispatched with balance pending' : 'Dispatched');
  } else {
    touch(site);
  }
  return { balancePending: pendingBalance };
}

function serial(d) {
  const u = auth(d);
  need(u, ['Dispatch', 'Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const sn = clean(d.serial, 60);
  if (!sn) throw new Error('Serial number is empty.');
  const dup = rows('Serial Numbers').filter(function (r) { return clean(r['Serial No.']).toLowerCase() === sn.toLowerCase(); })[0];
  if (dup && clean(dup['Site ID']) !== id) throw new Error('Serial ' + sn + ' is already used at site ' + clean(dup['Site ID']) + '.');
  if (dup) return { link: dup['Label Photo Link'] };
  let link = '';
  if (d.data) link = saveFile(site, 'serial_' + sn.replace(/[^\w-]/g, '_') + '.jpg', 'image/jpeg', d.data).getUrl();
  append('Serial Numbers', {
    'Site ID': id, 'Item Type': clean(d.itemType, 40), 'Make / Model': clean(d.make, 100),
    'Serial No.': "'" + sn, 'Label Photo Link': link, 'Scanned By (Dispatch)': clean(u['Name']),
    'Scanned On': now(), 'Matched at Site': 'No', 'Matched By (Installation)': '',
  });
  touch(site);
  return { link: link };
}

function serialDelete(d) {
  const u = auth(d);
  need(u, ['Dispatch', 'Manager']);
  const id = clean(d.siteId);
  const sn = clean(d.serial).toLowerCase();
  const t = table('Serial Numbers');
  const list = rows('Serial Numbers').filter(function (r) {
    return clean(r['Site ID']) === id && clean(r['Serial No.']).toLowerCase() === sn;
  });
  list.reverse().forEach(function (r) { t.sh.deleteRow(r._row); });
  return {};
}

function serialMatch(d) {
  const u = auth(d);
  need(u, ['Installation', 'Manager']);
  const id = clean(d.siteId);
  const sn = clean(d.serial).toLowerCase();
  const r = rows('Serial Numbers').filter(function (x) {
    return clean(x['Site ID']) === id && clean(x['Serial No.']).toLowerCase() === sn;
  })[0];
  if (!r) throw new Error('This serial number was not dispatched to this site. Check it, or call the godown.');
  setFields('Serial Numbers', r._row, { 'Matched at Site': 'Yes', 'Matched By (Installation)': clean(u['Name']) });
  return { itemType: clean(r['Item Type']) };
}

// ------------------------------------------------------------------ photos & files

function photoRoot() {
  const p = PropertiesService.getScriptProperties();
  const id = p.getProperty('PHOTO_ROOT');
  if (id) {
    try { return DriveApp.getFolderById(id); } catch (e) { /* recreated below */ }
  }
  const f = DriveApp.createFolder(PHOTO_ROOT);
  p.setProperty('PHOTO_ROOT', f.getId());
  return f;
}

function siteFolder(site) {
  const root = photoRoot();
  const name = clean(site['Site ID']) + ' - ' + clean(site['Customer Name'], 60);
  const it = root.getFoldersByName(name);
  return it.hasNext() ? it.next() : root.createFolder(name);
}

function saveFile(site, name, mime, data) {
  const blob = Utilities.newBlob(Utilities.base64Decode(data), mime, name);
  const f = siteFolder(site).createFile(blob);
  return f;
}

function photo(d) {
  const u = auth(d);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const pid = clean(d.photoId, 60);
  const existing = rows('Photos').filter(function (p) { return clean(p['Photo ID']) === pid; })[0];
  if (existing) return { link: existing['Drive Link'] };
  const f = saveFile(site, clean(d.name, 80) || (pid + '.jpg'), 'image/jpeg', d.data);
  const link = f.getUrl();
  append('Photos', {
    'Photo ID': pid, 'Site ID': id, 'Stage': clean(d.stage, 30), 'Photo Type': clean(d.type, 60),
    'Drive Link': link, 'Latitude': d.lat ? num(d.lat) : '', 'Longitude': d.lng ? num(d.lng) : '',
    'Taken On': clean(d.takenAt), 'Taken By': clean(u['Name']), 'Linked Item ID': clean(d.linkedItem, 40),
  });
  const item = clean(d.linkedItem);
  if (item) {
    rows('Checklist Results').forEach(function (r) {
      if (clean(r['Site ID']) === id && clean(r['Item ID']) === item && !clean(r['Photo Link'])) {
        setFields('Checklist Results', r._row, { 'Photo Link': link });
      }
    });
  }
  touch(site);
  return { link: link };
}

// ------------------------------------------------------------------ installation

function checklist(d) {
  const u = auth(d);
  need(u, ['Installation', 'Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const round = clean(d.round) || '1';
  const photos = rows('Photos').filter(function (p) { return clean(p['Site ID']) === id; });
  const existing = {};
  rows('Checklist Results').forEach(function (r) {
    if (clean(r['Site ID']) === id && clean(r['Round']) === round) existing[clean(r['Item ID'])] = r;
  });
  (d.results || []).forEach(function (x) {
    const item = clean(x.id);
    const ph = photos.filter(function (p) { return clean(p['Linked Item ID']) === item; }).pop();
    const fields = {
      'Site ID': id, 'Item ID': item, 'Checklist Item': clean(x.text, 300), 'Result': clean(x.result, 10),
      'Remark': clean(x.remark, 500), 'Photo Link': ph ? ph['Drive Link'] : '',
      'Checked By': clean(u['Name']), 'Checked On': now(), 'Round': round,
    };
    if (existing[item]) setFields('Checklist Results', existing[item]._row, fields);
    else append('Checklist Results', fields);
  });
  const st = clean(site['Status']);
  if (st === S.DISPATCHED || st === S.BALANCE) setStatus(site, S.IN_PROGRESS, u, 'Installation started');
  else touch(site);
  return {};
}

function pending(d) {
  const u = auth(d);
  need(u, ['Installation', 'Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  (d.items || []).forEach(function (x) {
    if (!clean(x.item)) return;
    append('Pending Work', {
      'Site ID': id, 'Pending Item': clean(x.item, 300), 'Reason': clean(x.reason, 300),
      'Expected Date': toDate(x.date), 'Status': 'Open', 'Added By': clean(u['Name']), 'Added On': now(), 'Closed On': '',
    });
  });
  (d.close || []).forEach(function (item) {
    rows('Pending Work').forEach(function (r) {
      if (clean(r['Site ID']) === id && clean(r['Pending Item']) === clean(item) && clean(r['Status']) === 'Open') {
        setFields('Pending Work', r._row, { 'Status': 'Closed', 'Closed On': now() });
      }
    });
  });
  touch(site);
  return {};
}

function submitInstall(d) {
  const u = auth(d);
  need(u, ['Installation', 'Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const st = clean(site['Status']);
  if ([S.DISPATCHED, S.BALANCE, S.IN_PROGRESS, S.REWORK].indexOf(st) < 0) throw new Error('This site cannot be submitted now (' + st + ').');

  const required = ['PH1', 'PH2', 'PH3', 'PH4', 'PH5'];
  const have = {};
  rows('Photos').forEach(function (p) { if (clean(p['Site ID']) === id) have[clean(p['Linked Item ID'])] = true; });
  const missing = required.filter(function (k) { return !have[k]; });
  if (missing.length) throw new Error('Photos still uploading or missing: ' + missing.join(', ') + '. Wait for upload, then submit again.');

  const tl = d.tlSignature ? saveFile(site, 'sign_team_leader_' + Date.now() + '.png', 'image/png', d.tlSignature).getUrl() : '';
  const cs = d.customerSignature ? saveFile(site, 'sign_customer_' + Date.now() + '.png', 'image/png', d.customerSignature).getUrl() : '';
  if (!cs) throw new Error('Customer signature is needed.');
  append('Sign-off & QC', {
    'Site ID': id, 'Team Leader': clean(u['Name']), 'Team Leader Signature Link': tl, 'Customer Signature Link': cs,
    'Customer OTP Verified': 'No', 'Customer Demo Given': d.demo ? 'Yes' : 'No', 'Submitted On': now(),
    'Report PDF Link': '', 'Sent to Customer On': '', 'QC By': '', 'QC Result': '', 'QC On': '', 'QC Remarks': '',
    'Portal Upload Done': 'No', 'Portal Upload On': '',
  });
  setStatus(site, S.INSTALL_SUBMITTED, u, 'Installation submitted' + (clean(d.note) ? ': ' + clean(d.note, 300) : ''));
  return {};
}

function latestSignoff(id) {
  return rows('Sign-off & QC').filter(function (r) { return clean(r['Site ID']) === id; }).pop();
}

function report(d) {
  const u = auth(d);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const f = saveFile(site, 'Installation Report - ' + id + '.pdf', 'application/pdf', d.data);
  const s = latestSignoff(id);
  if (s) setFields('Sign-off & QC', s._row, { 'Report PDF Link': f.getUrl() });
  return { link: f.getUrl(), by: clean(u['Name']) };
}

function sentToCustomer(d) {
  auth(d);
  const s = latestSignoff(clean(d.siteId));
  if (s) setFields('Sign-off & QC', s._row, { 'Sent to Customer On': now() });
  return {};
}

function qc(d) {
  const u = auth(d);
  need(u, ['Manager']);
  const site = findSite(d.siteId);
  const id = clean(site['Site ID']);
  const s = latestSignoff(id);
  if (!s) throw new Error('Installation is not submitted yet.');
  const remarks = clean(d.remarks, 500);
  const approved = d.result === 'Approved';
  if (!approved && !remarks) throw new Error('Write what needs rework.');
  const fields = { 'QC By': clean(u['Name']), 'QC Result': approved ? 'Approved' : 'Rework', 'QC On': now(), 'QC Remarks': remarks };
  if (d.portalDone) { fields['Portal Upload Done'] = 'Yes'; fields['Portal Upload On'] = now(); }
  setFields('Sign-off & QC', s._row, fields);
  setStatus(site, approved ? S.CLOSED : S.REWORK, u, remarks || 'QC approved');
  return {};
}

// ------------------------------------------------------------------ from AVIP Solar Sales

/**
 * Called by the AVIP Site Visits Log script when a visit is marked Converted
 * with an installation date. Creates a Draft site for that sales executive,
 * with the material list filled from BOM Master defaults. A visit is copied once;
 * later calls only update the installation date while the site is still a Draft.
 */
function salesVisit(d) {
  if (clean(d.linkKey) !== linkKey()) throw new Error('Not allowed');
  const visitNo = clean(d.visitNo);
  if (!visitNo) throw new Error('Visit number missing.');
  const existing = rows('Sites').filter(function (r) { return clean(r['Sales Visit No.']) === visitNo; })[0];
  if (existing) {
    const upd = {};
    if ([S.DRAFT, S.SENT_BACK].indexOf(clean(existing['Status'])) >= 0 && clean(d.installDate)) {
      upd['Installation Date'] = toDate(d.installDate);
    }
    if (clean(d.reportPdf) && !clean(existing['Site Report PDF'])) upd['Site Report PDF'] = clean(d.reportPdf, 300);
    if (Object.keys(upd).length) {
      upd['Last Updated'] = now();
      setFields('Sites', existing._row, upd);
    }
    return { siteId: clean(existing['Site ID']), existing: true };
  }
  // the sales executive is found by mobile number, so names need not match between the two apps
  const exec = d.salesMobile ? findUserByMobile(d.salesMobile) : null;
  const salesPerson = exec ? clean(exec['Name']) : clean(d.salesPerson, 80);
  const id = newSiteId();
  const loc = clean(d.location).split(',');
  const lat = loc.length === 2 ? num(loc[0]) : '';
  const lng = loc.length === 2 ? num(loc[1]) : '';
  append('Sites', {
    'Site ID': id, 'Created On': now(), 'Sales Person': salesPerson,
    'Customer Name': clean(d.customer, 120), 'Mobile': d.mobile ? "'" + last10(d.mobile) : '',
    'WhatsApp': d.mobile ? "'" + last10(d.mobile) : '', 'Address': clean(d.address, 300), 'District': clean(d.district, 60),
    'Latitude': lat, 'Longitude': lng,
    'Google Map Link': lat && lng ? 'https://maps.google.com/?q=' + lat + ',' + lng : '',
    'Site Size (kW)': d.kwp ? num(d.kwp) : '', 'System Type': clean(d.systemType, 30),
    'DISCOM': clean(d.discom, 20), 'CA No.': d.caNo ? "'" + clean(d.caNo, 30) : '',
    'Survey By': salesPerson, 'Survey Date': toDate(d.visitDate),
    'Status': S.DRAFT, 'Installation Date': toDate(d.installDate), 'Last Updated': now(),
    'Source': 'AVIP Solar Sales', 'Sales Visit No.': "'" + visitNo, 'Quoted System': clean(d.system, 200),
    'Site Report PDF': clean(d.reportPdf, 300),
  });
  const bom = rows('BOM Master').filter(function (r) { return clean(r['Active']).toLowerCase() !== 'no'; });
  writeMaterials(id, bom.map(function (r) {
    return {
      sr: clean(r['Sr. No.']), item: clean(r['Item']), make: clean(r['Make / Size']), unit: clean(r['Unit']),
      qty: num(r['Default Qty (per site)'] !== undefined ? r['Default Qty (per site)'] : r['Default Qty']),
    };
  }), 'Qty Required (Sales)');
  logStatus(id, '', S.DRAFT, null, 'From AVIP Solar Sales visit ' + visitNo);
  return { siteId: id, existing: false };
}
