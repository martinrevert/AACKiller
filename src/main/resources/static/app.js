const el = id => document.getElementById(id);

function setBusy(button, busy, busyText) {
  if (!button) return;
  if (busy) {
    if (!button.dataset.originalText) button.dataset.originalText = button.textContent || '';
    button.disabled = true;
    button.classList.add('is-loading');
    button.textContent = busyText || 'Working...';
    return;
  }
  button.disabled = false;
  button.classList.remove('is-loading');
  button.textContent = button.dataset.originalText || button.textContent || '';
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
  const elS = document.getElementById('statusText');
  if (elS) elS.textContent = s;
}

function updateScanConfigVisibility() {
  const section = el('scanConfigSection');
  if (!section) return;
  const current = normalizeStatus(el('statusText')?.textContent);
  section.classList.toggle('hidden', current !== 'stopped');
}

async function doStart() {
  const btn = el('startBtn');
  setBusy(btn, true, 'Starting...');
  const r = await request('/api/v1/control/start', { method: 'POST' });
  showStatusText(r.ok ? 'started' : `error (${r.status})`);
  updateScanConfigVisibility();
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  setBusy(btn, false);
}

async function doStop() {
  const btn = el('stopBtn');
  setBusy(btn, true, 'Stopping...');
  const r = await request('/api/v1/control/stop', { method: 'POST' });
  showStatusText('stopped');
  updateScanConfigVisibility();
  await loadScanConfig();
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  setBusy(btn, false);
}

async function doClear() {
  if (!confirm('Clear index and delete all jobs?')) return;
  const btn = el('clearBtn');
  setBusy(btn, true, 'Clearing...');
  const r = await request('/api/v1/control/clear-index', { method: 'POST' });
  showToast(r.raw || JSON.stringify(r.body), r.ok ? 'success' : 'error');
  await listJobs();
  setBusy(btn, false);
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
function startSSE() {
  if (es) return;
  es = new EventSource('/api/v1/jobs/stream');
  es.addEventListener('jobsSnapshot', ev => {
    try { const data = JSON.parse(ev.data || '[]'); renderJobs(data); } catch (e) { console.error('jobsSnapshot parse', e); }
  });
  es.addEventListener('job', ev => {
    try { const job = JSON.parse(ev.data); upsertJob(job); } catch (e) { console.error('job parse', e); }
  });
  es.addEventListener('jobDeleted', ev => {
    try { const id = Number(ev.data); deleteJobById(id); } catch (e) { console.error('jobDeleted parse', e); }
  });
  es.addEventListener('indexCleared', ev => {
    jobsCacheJson = '';
    const area = el('jobsArea'); if (area) area.innerHTML = '<div class="muted">No jobs</div>';
  });
  es.addEventListener('workerStatus', ev => {
    try {
      showStatusText(ev.data || 'unknown');
      updateScanConfigVisibility();
    } catch (e) {
      console.error('workerStatus parse', e);
    }
  });
  es.onerror = (err) => {
    console.error('SSE error', err);
    try { es.close(); } catch (e) {}
    es = null;
    setTimeout(startSSE, 3000);
  };
}

function renderJobs(jobs) {
  const area = el('jobsArea');
  const newJson = JSON.stringify(jobs || []);
  if (newJson === jobsCacheJson) return;
  jobsCacheJson = newJson;
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
    setBusy(btn, false);
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
    setBusy(btn, false);
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
    await loadScanConfig();
  } finally {
    setBusy(btn, false);
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

  onScanModeChanged();
  updateScanConfigVisibility();
}

document.addEventListener('DOMContentLoaded', () => {
  const startBtn = el('startBtn'); if (startBtn) startBtn.addEventListener('click', doStart);
  const stopBtn = el('stopBtn'); if (stopBtn) stopBtn.addEventListener('click', doStop);
  const clearBtn = el('clearBtn'); if (clearBtn) clearBtn.addEventListener('click', doClear);

  const scanMode = el('scanMode'); if (scanMode) scanMode.addEventListener('change', onScanModeChanged);
  const browseSmbBtn = el('browseSmbBtn'); if (browseSmbBtn) browseSmbBtn.addEventListener('click', browseSmb);
  const testSmbBtn = el('testSmbBtn'); if (testSmbBtn) testSmbBtn.addEventListener('click', testSmbConnection);
  const saveScanBtn = el('saveScanBtn'); if (saveScanBtn) saveScanBtn.addEventListener('click', saveScanConfig);

  onScanModeChanged();
  updateScanConfigVisibility();
  loadScanConfig();
  startSSE();
});
