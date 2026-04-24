const el = id => document.getElementById(id);

const UI_STATE_KEY = 'aac2ac3.dashboard.uiState.v1';

const uiState = {
  workerStatus: 'unknown',
  sseConnected: false,
  lastEventText: 'none',
  lastEventAt: 0,
  phase: 'waiting',
  activity: 'unknown',
  currentAction: '',
  startRequestedAt: 0,
  jobs: {
    total: 0,
    pending: 0,
    running: 0,
    done: 0,
    failed: 0
  }
};

const ACTION_BUTTONS = {
  'starting': { id: 'startBtn', text: 'Starting...' },
  'stopping': { id: 'stopBtn', text: 'Stopping...' },
  'clearing-index': { id: 'clearBtn', text: 'Clearing...' },
  'browsing-smb': { id: 'browseSmbBtn', text: 'Browsing...' },
  'testing-smb': { id: 'testSmbBtn', text: 'Testing...' },
  'saving-scan-config': { id: 'saveScanBtn', text: 'Saving...' }
};

function loadUiState() {
  try {
    const raw = localStorage.getItem(UI_STATE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') {
      if (parsed.workerStatus) uiState.workerStatus = parsed.workerStatus;
      if (typeof parsed.sseConnected === 'boolean') uiState.sseConnected = parsed.sseConnected;
      if (parsed.lastEventText) uiState.lastEventText = parsed.lastEventText;
      if (Number.isFinite(parsed.lastEventAt)) uiState.lastEventAt = Number(parsed.lastEventAt);
      if (parsed.activity) uiState.activity = parsed.activity;
      if (parsed.phase) uiState.phase = parsed.phase;
      if (parsed.currentAction) uiState.currentAction = parsed.currentAction;
      if (Number.isFinite(parsed.startRequestedAt)) uiState.startRequestedAt = Number(parsed.startRequestedAt);
      if (parsed.jobs && typeof parsed.jobs === 'object') {
        uiState.jobs = {
          total: Number(parsed.jobs.total || 0),
          pending: Number(parsed.jobs.pending || 0),
          running: Number(parsed.jobs.running || 0),
          done: Number(parsed.jobs.done || 0),
          failed: Number(parsed.jobs.failed || 0)
        };
      }
    }
  } catch (e) {
    console.warn('Could not load persisted UI state', e);
  }
}

function saveUiState() {
  try {
    localStorage.setItem(UI_STATE_KEY, JSON.stringify(uiState));
  } catch (e) {
    console.warn('Could not persist UI state', e);
  }
}

function formatEventTime(ms) {
  if (!ms) return '-';
  try { return new Date(ms).toLocaleTimeString(); } catch (e) { return '-'; }
}

function setText(id, value) {
  const node = el(id);
  if (!node) return;
  node.textContent = String(value == null ? '' : value);
}

function renderProcessOverview() {
  setText('processActivity', uiState.activity || 'unknown');
  setText('lastEventText', uiState.lastEventText || 'none');
  setText('lastEventAt', formatEventTime(uiState.lastEventAt));
  setText('jobsTotal', uiState.jobs.total);
  setText('jobsPending', uiState.jobs.pending);
  setText('jobsRunning', uiState.jobs.running);
  setText('jobsDoneFailed', `${uiState.jobs.done} / ${uiState.jobs.failed}`);

  const sse = el('sseStatus');
  if (sse) {
    sse.textContent = uiState.sseConnected ? 'connected' : 'disconnected';
    sse.classList.toggle('connected', uiState.sseConnected);
    sse.classList.toggle('disconnected', !uiState.sseConnected);
  }

  setText('statusDetailText', uiState.activity || 'unknown');
  setText('statusTotal', uiState.jobs.total);
  setText('statusPending', uiState.jobs.pending);
  setText('statusRunning', uiState.jobs.running);
  setText('statusDone', uiState.jobs.done);
  setText('statusFailed', uiState.jobs.failed);

  const phaseNode = el('processStateText');
  if (phaseNode) {
    phaseNode.textContent = uiState.phase || 'waiting';
    phaseNode.className = '';
    phaseNode.classList.add('phase-badge');
    phaseNode.classList.add(`phase-${normalizeStatus(uiState.phase || 'waiting')}`);
  }
}

function deriveActivity() {
  if (uiState.currentAction === 'starting') return 'Starting worker';
  if (uiState.currentAction === 'stopping') return 'Stopping worker';
  if (uiState.currentAction === 'clearing-index') return 'Clearing index and jobs';
  if (uiState.currentAction) {
    return uiState.currentAction.replace(/-/g, ' ');
  }

  const st = normalizeStatus(uiState.workerStatus);
  if (st === 'running' || st === 'started') {
    if (uiState.jobs.running > 0) return 'Converting media files (ffmpeg running)';
    if (uiState.jobs.pending > 0) return 'Queue has pending files, worker is picking next jobs';
    if (uiState.startRequestedAt > 0 && Date.now() - uiState.startRequestedAt < 30000) {
      return 'Initial indexing and probing scan path';
    }
    return 'Watching scan path and waiting for new/changed files';
  }
  if (st === 'stopped') return 'Worker stopped';
  return 'Waiting for worker status';
}

function derivePhase() {
  const st = normalizeStatus(uiState.workerStatus);
  if (uiState.currentAction === 'starting') return 'starting';
  if (uiState.currentAction === 'stopping') return 'stopping';
  if (uiState.currentAction === 'clearing-index') return 'clearing';

  if (st === 'stopped') return 'stopped';
  if (st === 'running' || st === 'started') {
    if (uiState.jobs.running > 0 || uiState.jobs.pending > 0) return 'processing';
    if (uiState.startRequestedAt > 0 && Date.now() - uiState.startRequestedAt < 30000) return 'indexing';
    return 'watching';
  }
  return 'waiting';
}

function setCurrentAction(actionName) {
  uiState.currentAction = actionName || '';
  if (actionName === 'starting') {
    uiState.startRequestedAt = Date.now();
  }
  if (actionName === 'stopping' || actionName === 'clearing-index') {
    uiState.startRequestedAt = 0;
  }
  uiState.phase = derivePhase();
  uiState.activity = deriveActivity();
  renderProcessOverview();
  saveUiState();
  syncButtonState();
  applyActionVisualState();
}

function setBusy(button, busy, busyText) {
  if (!button) return;
  if (busy) {
    if (!button.dataset.originalText) button.dataset.originalText = button.textContent || '';
    button.disabled = true;
    button.classList.add('is-loading');
    button.textContent = busyText || 'Working...';
    return;
  }
  button.classList.remove('is-loading');
  button.textContent = button.dataset.originalText || button.textContent || '';
}

function clearAllButtonBusyVisuals() {
  for (const action of Object.keys(ACTION_BUTTONS)) {
    const btn = el(ACTION_BUTTONS[action].id);
    if (btn) {
      setBusy(btn, false);
    }
  }
}

function applyActionVisualState() {
  clearAllButtonBusyVisuals();
  if (!uiState.currentAction) return;
  const cfg = ACTION_BUTTONS[uiState.currentAction];
  if (!cfg) return;
  const btn = el(cfg.id);
  if (btn) {
    setBusy(btn, true, cfg.text);
  }
}

function ensureToastHost() {
  let host = document.getElementById('toastHost');
  if (host) return host;
  host = document.createElement('div');
  host.id = 'toastHost';
  host.className = 'toast-host';
  document.body.appendChild(host);
  return host;
}

function setSmbBrowseMessage(text, type = 'info') {
  const area = el('smbBrowseArea');
  if (!area) return;
  area.classList.remove('ok', 'err', 'info');
  area.classList.add(type);
  area.innerHTML = `<div>${escapeHtml(text || '')}</div>`;
}

function setSmbTestMessage(html, type = 'info') {
  const area = el('smbTestArea');
  if (!area) return;
  area.classList.remove('ok', 'err', 'info');
  area.classList.add(type);
  area.innerHTML = html || '';
}

async function request(path, opts = {}) {
  try {
    const res = await fetch(path, opts);
    const text = await res.text();
    let body = text;
    try { body = JSON.parse(text); } catch (e) {}
    return { ok: res.ok, status: res.status, body, raw: text };
  } catch (e) {
    return { ok: false, status: 0, body: null, raw: e.toString() };
  }
}

function normalizeStatus(s) { return String(s || '').trim().toLowerCase(); }

function showStatusText(s) {
  uiState.workerStatus = s || 'unknown';
  if (normalizeStatus(uiState.workerStatus) === 'stopped') {
    uiState.startRequestedAt = 0;
  }
  const elS = document.getElementById('statusText');
  if (elS) elS.textContent = s;
  uiState.phase = derivePhase();
  uiState.activity = deriveActivity();
  renderProcessOverview();
  saveUiState();
  syncButtonState();
}

function setActionButtonsDisabled(disabled) {
  const ids = ['startBtn', 'stopBtn', 'clearBtn', 'saveScanBtn', 'browseSmbBtn', 'testSmbBtn'];
  for (const id of ids) {
    const btn = el(id);
    if (btn) btn.disabled = !!disabled;
  }
}

function syncButtonState() {
  const worker = normalizeStatus(uiState.workerStatus);
  const busy = !!uiState.currentAction;
  const isStopped = worker === 'stopped';
  const isRunning = worker === 'running' || worker === 'started';

  if (busy) {
    setActionButtonsDisabled(true);
    return;
  }

  const startBtn = el('startBtn');
  const stopBtn = el('stopBtn');
  const clearBtn = el('clearBtn');
  const saveScanBtn = el('saveScanBtn');
  const browseSmbBtn = el('browseSmbBtn');
  const testSmbBtn = el('testSmbBtn');

  if (startBtn) startBtn.disabled = !isStopped;
  if (stopBtn) stopBtn.disabled = !isRunning;
  if (clearBtn) clearBtn.disabled = !isStopped;
  if (saveScanBtn) saveScanBtn.disabled = !isStopped;
  if (browseSmbBtn) browseSmbBtn.disabled = !isStopped;
  if (testSmbBtn) testSmbBtn.disabled = !isStopped;
}

function updateJobsStats(jobs) {
  const stats = { total: 0, pending: 0, running: 0, done: 0, failed: 0 };
  const arr = Array.isArray(jobs) ? jobs : [];
  stats.total = arr.length;
  for (const j of arr) {
    const st = normalizeStatus(j && j.status);
    if (st === 'pending') stats.pending++;
    else if (st === 'running') stats.running++;
    else if (st === 'done') stats.done++;
    else if (st === 'failed') stats.failed++;
  }
  uiState.jobs = stats;
  uiState.phase = derivePhase();
  uiState.activity = deriveActivity();
  renderProcessOverview();
  saveUiState();
}

function markEvent(eventName) {
  uiState.lastEventText = eventName;
  uiState.lastEventAt = Date.now();
  renderProcessOverview();
  saveUiState();
}

function updateScanConfigVisibility() {
  const section = el('scanConfigSection');
  if (!section) return;
  const current = normalizeStatus(el('statusText')?.textContent);
  section.classList.toggle('hidden', current !== 'stopped');
  syncButtonState();
}

async function doStart() {
  const btn = el('startBtn');
  setCurrentAction('starting');
  markEvent('worker-start-clicked');
  setBusy(btn, true, 'Starting...');
  const previousStatus = uiState.workerStatus;
  const r = await request('/api/v1/control/start', { method: 'POST' });
  showStatusText(r.ok ? 'running' : previousStatus);
  markEvent(r.ok ? 'worker-start-requested' : 'worker-start-failed');
  await refreshBackendState();
  updateScanConfigVisibility();
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  if (!r.ok) {
    setCurrentAction('');
  }
  syncButtonState();
}

async function doStop() {
  const btn = el('stopBtn');
  setCurrentAction('stopping');
  markEvent('worker-stop-clicked');
  setBusy(btn, true, 'Stopping...');
  const previousStatus = uiState.workerStatus;
  const r = await request('/api/v1/control/stop', { method: 'POST' });
  showStatusText(r.ok ? 'stopped' : previousStatus);
  markEvent(r.ok ? 'worker-stop-requested' : 'worker-stop-failed');
  await refreshBackendState();
  updateScanConfigVisibility();
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  if (!r.ok) {
    setCurrentAction('');
  }
  syncButtonState();
}

async function doClear() {
  if (normalizeStatus(uiState.workerStatus) !== 'stopped') {
    showToast('Stop worker before clearing index', 'error');
    return;
  }
  if (!confirm('Clear index and delete all jobs?')) return;
  const btn = el('clearBtn');
  setCurrentAction('clearing-index');
  setBusy(btn, true, 'Clearing...');
  const r = await request('/api/v1/control/clear-index', { method: 'POST' });
  markEvent(r.ok ? 'index-cleared' : 'index-clear-failed');
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  await refreshBackendState();
  if (!r.ok) {
    setCurrentAction('');
  }
  syncButtonState();
}

async function listJobs() {
  const r = await request('/api/v1/jobs');
  if (r.ok && Array.isArray(r.body)) {
    renderJobs(r.body);
  }
}

function formatDate(ms) { if (!ms) return ''; try { return new Date(Number(ms)).toLocaleString(); } catch (e) { return String(ms); } }

function badgeFor(status) {
  if (!status) return '';
  const s = status.toLowerCase();
  if (s === 'pending') return '<span class="badge pending">PENDING</span>';
  if (s === 'running') return '<span class="badge running">RUNNING</span>';
  if (s === 'done') return '<span class="badge done">DONE</span>';
  if (s === 'failed') return '<span class="badge failed">FAILED</span>';
  return `<span class="badge">${status}</span>`;
}

function truncate(s, n) { if (!s) return ''; return s.length > n ? s.substring(0, n - 2) + '…' : s; }
function escapeHtml(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }

let jobsCacheJson = '';
function debounce(fn, wait) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), wait); }; }

let es = null;
let statusPollTimer = null;

async function refreshBackendState() {
  await loadScanConfig();
  await listJobs();

  const st = normalizeStatus(uiState.workerStatus);
  if (uiState.currentAction === 'starting' && (st === 'running' || st === 'started')) {
    setCurrentAction('');
  }
  if (uiState.currentAction === 'stopping' && st === 'stopped') {
    setCurrentAction('');
  }
  if (uiState.currentAction === 'clearing-index' && uiState.jobs.total === 0) {
    setCurrentAction('');
  }
}

function startStatusPolling() {
  if (statusPollTimer) return;
  statusPollTimer = setInterval(async () => {
    try {
      await refreshBackendState();
      if (!uiState.sseConnected) {
        markEvent('poll-refresh');
      }
    } catch (e) {
      console.warn('status polling failed', e);
    }
  }, 2500);
}

function stopStatusPolling() {
  if (!statusPollTimer) return;
  clearInterval(statusPollTimer);
  statusPollTimer = null;
}

function startSSE() {
  if (es) return;
  es = new EventSource('/api/v1/jobs/stream');
  es.onopen = () => {
    uiState.sseConnected = true;
    renderProcessOverview();
    saveUiState();
    markEvent('sse-connected');
  };
  es.addEventListener('jobsSnapshot', ev => {
    try {
      const data = JSON.parse(ev.data || '[]');
      renderJobs(data);
      updateJobsStats(data);
      markEvent('jobs-snapshot');
    } catch (e) { console.error('jobsSnapshot parse', e); }
  });
  es.addEventListener('job', ev => {
    try {
      const job = JSON.parse(ev.data);
      upsertJob(job);
      markEvent(`job-${normalizeStatus(job.status || 'updated')}`);
    } catch (e) { console.error('job parse', e); }
  });
  es.addEventListener('jobDeleted', ev => {
    try {
      const id = Number(ev.data);
      deleteJobById(id);
      markEvent('job-deleted');
    } catch (e) { console.error('jobDeleted parse', e); }
  });
  es.addEventListener('indexCleared', ev => {
    jobsCacheJson = '';
    const area = el('jobsArea'); if (area) area.innerHTML = '<div class="muted">No jobs</div>';
    updateJobsStats([]);
    markEvent('index-cleared');
  });
  es.addEventListener('workerStatus', ev => {
    try {
      showStatusText(ev.data || 'unknown');
      markEvent(`worker-${normalizeStatus(ev.data || 'unknown')}`);
      updateScanConfigVisibility();
      if (normalizeStatus(ev.data) === 'running' || normalizeStatus(ev.data) === 'stopped') {
        setCurrentAction('');
      }
    } catch (e) {
      console.error('workerStatus parse', e);
    }
  });
  es.onerror = (err) => {
    console.error('SSE error', err);
    try { es.close(); } catch (e) {}
    es = null;
    uiState.sseConnected = false;
    renderProcessOverview();
    saveUiState();
    markEvent('sse-disconnected');
    setTimeout(startSSE, 3000);
  };
}

function renderJobs(jobs) {
  const area = el('jobsArea');
  const newJson = JSON.stringify(jobs || []);
  if (newJson === jobsCacheJson) return;
  jobsCacheJson = newJson;
  updateJobsStats(jobs || []);
  if (!jobs || jobs.length === 0) { if (area) area.innerHTML = '<div class="muted">No jobs</div>'; return; }

  if (window.innerWidth < 720) {
    if (area) area.innerHTML = jobs.map(j => renderJobCard(j)).join('');
    attachCardHandlers();
    return;
  }

  let html = '<div class="table-wrapper"><table><thead><tr><th>ID</th><th>File</th><th>Status</th><th>Created</th><th>Started</th><th>Finished</th><th>Logs</th></tr></thead><tbody>';
  for (const j of jobs) {
    const name = j.fileName || '';
    html += `<tr><td>${j.id || ''}</td><td title="${escapeHtml(name)}">${escapeHtml(truncate(name, 60))}</td><td>${badgeFor(j.status)}</td><td>${formatDate(j.createdAt)}</td><td>${formatDate(j.startedAt)}</td><td>${formatDate(j.finishedAt)}</td><td>${escapeHtml(j.logsPath || '')}</td></tr>`;
  }
  html += '</tbody></table></div>';
  if (area) area.innerHTML = html;
}

function upsertJob(job) {
  let arr = [];
  try { arr = JSON.parse(jobsCacheJson || '[]'); } catch (e) { arr = []; }
  let found = false;
  for (let i = 0; i < arr.length; i++) { if (arr[i].id === job.id) { arr[i] = job; found = true; break; } }
  if (!found) arr.push(job);
  renderJobs(arr);
}

function deleteJobById(id) {
  let arr = [];
  try { arr = JSON.parse(jobsCacheJson || '[]'); } catch (e) { arr = []; }
  arr = arr.filter(j => j.id !== id);
  renderJobs(arr);
}

function renderJobCard(j) {
  const name = j.fileName || '';
  const short = truncate(name, 45);
  return `<div class="job-card" data-id="${j.id || ''}" data-name="${escapeHtml(name)}">
    <div class="job-row"><div><strong>#${j.id || ''}</strong></div><div>${badgeFor(j.status)}</div></div>
    <div class="job-filename">${escapeHtml(short)}</div>
    <div class="job-meta"><small>Created: ${formatDate(j.createdAt)} · Started: ${formatDate(j.startedAt)} · Finished: ${formatDate(j.finishedAt)}</small></div>
    <div class="job-actions"><button class="btn small show-path">Show</button><button class="btn small copy-path">Copy</button></div>
  </div>`;
}

function attachCardHandlers() {
  document.querySelectorAll('.job-card').forEach(card => {
    const showBtn = card.querySelector('.show-path');
    const copyBtn = card.querySelector('.copy-path');
    const filenameEl = card.querySelector('.job-filename');
    const name = card.dataset.name || '';
    filenameEl.textContent = truncate(name, 45);
    showBtn.addEventListener('click', () => {
      const expanded = card.classList.toggle('expanded');
      if (expanded) { filenameEl.textContent = name; showBtn.textContent = 'Hide'; }
      else { filenameEl.textContent = truncate(name, 45); showBtn.textContent = 'Show'; }
    });
    copyBtn.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(name); showToast('Copied filename to clipboard'); }
      catch (e) { window.prompt('Copy filename', name); }
    });
  });
}

window.addEventListener('resize', debounce(() => { if (jobsCacheJson) listJobs(); }, 300));

function showToast(msg, type = 'info') {
  const host = ensureToastHost();
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.textContent = String(msg || 'done');
  host.appendChild(t);
  window.setTimeout(() => {
    t.classList.add('hide');
    window.setTimeout(() => t.remove(), 260);
  }, 2600);
}

function extractApiError(r, fallback) {
  if (!r) return fallback;
  if (r.body && typeof r.body === 'object') {
    const details = r.body.details ? String(r.body.details) : '';
    const error = r.body.error ? String(r.body.error) : '';
    if (details && error) return `${error}: ${details}`;
    if (details) return details;
    if (error) return error;
  }
  if (typeof r.raw === 'string' && r.raw.trim()) return r.raw;
  return fallback;
}

function onScanModeChanged() {
  const mode = el('scanMode')?.value || 'local';
  const localArea = el('localPathArea');
  const smbArea = el('smbArea');
  if (localArea) localArea.classList.toggle('hidden', mode !== 'local');
  if (smbArea) smbArea.classList.toggle('hidden', mode !== 'smb');
}

function renderSmbBrowse(path, directories) {
  const area = el('smbBrowseArea');
  if (!area) return;
  if (!directories || directories.length === 0) {
    area.innerHTML = `<div>No subdirectories under ${escapeHtml(path || '/')}</div>`;
    return;
  }
  area.innerHTML = directories.map(d =>
    `<div class="smb-item"><span>${escapeHtml(d.name)}</span><button class="btn small smb-pick" data-path="${escapeHtml(d.path)}">Use</button></div>`
  ).join('');
  area.querySelectorAll('.smb-pick').forEach(btn => {
    btn.addEventListener('click', () => {
      const p = btn.getAttribute('data-path') || '';
      const pathInput = el('smbPathInput');
      if (pathInput) pathInput.value = p;
    });
  });
}

function renderSmbTestResult(result, fallbackError) {
  const steps = Array.isArray(result?.steps) ? result.steps : [];
  if (!steps.length) {
    const msg = escapeHtml(fallbackError || result?.message || 'No diagnostic steps returned.');
    setSmbTestMessage(`<div>${msg}</div>`, 'err');
    return;
  }
  const rows = steps.map(s => {
    const ok = !!s.ok;
    const badge = ok ? 'OK' : 'FAIL';
    const cls = ok ? 'ok' : 'fail';
    return `<div class="smb-step ${cls}"><span class="name">${escapeHtml(String(s.name || 'step'))}</span><span class="badge">${badge}</span><span class="msg">${escapeHtml(String(s.message || ''))}</span></div>`;
  }).join('');
  const top = result?.message ? `<div class="smb-test-summary">${escapeHtml(String(result.message))}</div>` : '';
  setSmbTestMessage(`${top}${rows}`, steps.every(s => !!s.ok) ? 'ok' : 'err');
}

async function browseSmb() {
  const btn = el('browseSmbBtn');
  if (normalizeStatus(el('statusText')?.textContent) !== 'stopped') {
    showToast('Stop worker before browsing/saving SMB directory', 'error');
    setSmbBrowseMessage('Stop worker before browsing SMB directories.', 'err');
    return;
  }
  const payload = {
    smbHost: el('smbHostInput')?.value || '',
    smbShare: el('smbShareInput')?.value || '',
    smbPath: el('smbPathInput')?.value || '',
    smbUsername: el('smbUserInput')?.value || '',
    smbPassword: el('smbPassInput')?.value || '',
    smbDomain: el('smbDomainInput')?.value || ''
  };
  setBusy(btn, true, 'Browsing...');
  setCurrentAction('browsing-smb');
  setSmbBrowseMessage('Browsing directories...', 'info');
  try {
    const r = await request('/api/v1/control/samba/browse', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!r.ok) {
      const err = extractApiError(r, `SMB browse failed (${r.status || 0})`);
      showToast(err, 'error');
      setSmbBrowseMessage(err, 'err');
      return;
    }
    showToast('SMB directories loaded', 'success');
    renderSmbBrowse(r.body.path, r.body.directories || []);
  } finally {
    setCurrentAction('');
    setBusy(btn, false);
    syncButtonState();
  }
}

async function testSmbConnection() {
  const btn = el('testSmbBtn');
  if (normalizeStatus(el('statusText')?.textContent) !== 'stopped') {
    showToast('Stop worker before testing SMB connection', 'error');
    setSmbTestMessage('<div>Stop worker before testing SMB connection.</div>', 'err');
    return;
  }
  const payload = {
    smbHost: el('smbHostInput')?.value || '',
    smbShare: el('smbShareInput')?.value || '',
    smbPath: el('smbPathInput')?.value || '',
    smbUsername: el('smbUserInput')?.value || '',
    smbPassword: el('smbPassInput')?.value || '',
    smbDomain: el('smbDomainInput')?.value || ''
  };

  setBusy(btn, true, 'Testing...');
  setCurrentAction('testing-smb');
  setSmbTestMessage('<div>Running SMB diagnostics (DNS/TCP/auth/path)...</div>', 'info');
  try {
    const r = await request('/api/v1/control/samba/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!r.ok) {
      const err = extractApiError(r, `SMB test failed (${r.status || 0})`);
      showToast(err, 'error');
      renderSmbTestResult(r.body, err);
      return;
    }
    showToast((r.body && r.body.message) || 'SMB test passed', 'success');
    renderSmbTestResult(r.body, 'SMB test passed');
  } finally {
    setCurrentAction('');
    setBusy(btn, false);
    syncButtonState();
  }
}

async function saveScanConfig() {
  const btn = el('saveScanBtn');
  if (normalizeStatus(el('statusText')?.textContent) !== 'stopped') {
    showToast('Stop worker before changing scan directory', 'error');
    return;
  }
  const mode = el('scanMode')?.value || 'local';
  const payload = {
    mode,
    localPath: el('localPathInput')?.value || '',
    smbHost: el('smbHostInput')?.value || '',
    smbShare: el('smbShareInput')?.value || '',
    smbPath: el('smbPathInput')?.value || '',
    smbUsername: el('smbUserInput')?.value || '',
    smbPassword: el('smbPassInput')?.value || '',
    smbDomain: el('smbDomainInput')?.value || ''
  };
  setBusy(btn, true, 'Saving...');
  setCurrentAction('saving-scan-config');
  try {
    const r = await request('/api/v1/control/scan-config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!r.ok) {
      const err = extractApiError(r, `Save failed (${r.status || 0})`);
      showToast(err, 'error');
      return;
    }
    showToast((r.body && r.body.message) || r.raw || 'Saved', 'success');
    markEvent('scan-config-saved');
    await loadScanConfig();
  } finally {
    setCurrentAction('');
    setBusy(btn, false);
    syncButtonState();
  }
}

async function loadScanConfig() {
  const r = await request('/api/v1/control/scan-config');
  if (!r.ok || !r.body) return;
  const cfg = r.body;

  const scanPath = String(cfg.scanPath || '');
  const modeEl = el('scanMode');
  const isSmb = scanPath.toLowerCase().startsWith('smb://');
  if (modeEl) modeEl.value = isSmb ? 'smb' : 'local';

  const localPath = el('localPathInput');
  if (localPath && !isSmb) localPath.value = scanPath;

  const smbHost = el('smbHostInput'); if (smbHost) smbHost.value = cfg.smbHost || '';
  const smbShare = el('smbShareInput'); if (smbShare) smbShare.value = cfg.smbShare || '';
  const smbPath = el('smbPathInput'); if (smbPath) smbPath.value = cfg.smbPath || '';
  const smbUser = el('smbUserInput'); if (smbUser) smbUser.value = cfg.smbUsername || '';
  const smbPass = el('smbPassInput'); if (smbPass) smbPass.value = '';
  const smbDomain = el('smbDomainInput'); if (smbDomain) smbDomain.value = cfg.smbDomain || '';

  if (typeof cfg.workerRunning === 'boolean') {
    showStatusText(cfg.workerRunning ? 'running' : 'stopped');
  }

  onScanModeChanged();
  updateScanConfigVisibility();
}

document.addEventListener('DOMContentLoaded', () => {
  loadUiState();
  uiState.sseConnected = false;
  showStatusText(uiState.workerStatus || 'unknown');
  uiState.phase = derivePhase();
  renderProcessOverview();
  syncButtonState();
  applyActionVisualState();

  const startBtn = el('startBtn'); if (startBtn) startBtn.addEventListener('click', doStart);
  const stopBtn = el('stopBtn'); if (stopBtn) stopBtn.addEventListener('click', doStop);
  const clearBtn = el('clearBtn'); if (clearBtn) clearBtn.addEventListener('click', doClear);

  const scanMode = el('scanMode'); if (scanMode) scanMode.addEventListener('change', onScanModeChanged);
  const browseSmbBtn = el('browseSmbBtn'); if (browseSmbBtn) browseSmbBtn.addEventListener('click', browseSmb);
  const testSmbBtn = el('testSmbBtn'); if (testSmbBtn) testSmbBtn.addEventListener('click', testSmbConnection);
  const saveScanBtn = el('saveScanBtn'); if (saveScanBtn) saveScanBtn.addEventListener('click', saveScanConfig);

  onScanModeChanged();
  updateScanConfigVisibility();
  refreshBackendState();
  startStatusPolling();
  startSSE();
});

window.addEventListener('beforeunload', () => {
  stopStatusPolling();
  if (es) {
    try { es.close(); } catch (e) {}
    es = null;
  }
});
