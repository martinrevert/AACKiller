const el = id => document.getElementById(id);

async function request(path, opts={}){
  try{
    const res = await fetch(path, opts);
    const text = await res.text();
    let body = text;
    try{ body = JSON.parse(text); }catch(e){}
    return {ok:res.ok,status:res.status,body,raw:text}
  }catch(e){ return {ok:false,status:0,body:null,raw:e.toString()} }
}

function showStatusText(s){ const elS = document.getElementById('statusText'); if(elS) elS.textContent = s }

async function doStart(){
  const r = await request('/api/v1/control/start',{method:'POST'});
  showStatusText(r.ok ? 'started' : `error (${r.status})`);
  showToast(r.raw || JSON.stringify(r.body));
}

async function doStop(){
  const r = await request('/api/v1/control/stop',{method:'POST'});
  showStatusText('stopped');
  showToast(r.raw || JSON.stringify(r.body));
}


async function doClear(){
  if(!confirm('Clear index and delete all jobs?')) return;
  // request restart so the indexer runs again and the watcher resumes
  const r = await request('/api/v1/control/clear-index?restart=true',{method:'POST'});
  showToast(r.raw || JSON.stringify(r.body));
  await listJobs();
}

function formatDate(ms){ if(!ms) return ''; try{ return new Date(Number(ms)).toLocaleString(); }catch(e){ return String(ms) } }

function badgeFor(status){
  if(!status) return '';
  const s = status.toLowerCase();
  if(s==='pending') return '<span class="badge pending">PENDING</span>';
  if(s==='running') return '<span class="badge running">RUNNING</span>';
  if(s==='done') return '<span class="badge done">DONE</span>';
  if(s==='failed') return '<span class="badge failed">FAILED</span>';
  return `<span class="badge">${status}</span>`;
}

function truncate(s,n){ if(!s) return ''; return s.length>n ? s.substring(0,n-2)+'…' : s }
function escapeHtml(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;') }

let jobsCacheJson = '';
function debounce(fn, wait){ let t; return (...a)=>{ clearTimeout(t); t=setTimeout(()=>fn(...a), wait); } }

// SSE (server-sent events) connection
let es = null;
function startSSE(){
  if(es) return;
  es = new EventSource('/api/v1/jobs/stream');
  es.addEventListener('jobsSnapshot', ev => {
    try{ const data = JSON.parse(ev.data || '[]'); renderJobs(data); }catch(e){ console.error('jobsSnapshot parse', e); }
  });
  es.addEventListener('job', ev => {
    try{ const job = JSON.parse(ev.data); upsertJob(job); }catch(e){ console.error('job parse', e); }
  });
  es.addEventListener('jobDeleted', ev => {
    try{ const id = Number(ev.data); deleteJobById(id); }catch(e){ console.error('jobDeleted parse', e); }
  });
  es.addEventListener('indexCleared', ev => {
    jobsCacheJson = '';
    const area = el('jobsArea'); if(area) area.innerHTML = '<div class="muted">No jobs</div>';
  });
  es.addEventListener('workerStatus', ev => {
    try{ showStatusText(ev.data || 'unknown'); }catch(e){ console.error('workerStatus parse', e); }
  });
  es.onerror = (err) => {
    console.error('SSE error', err);
    try{ es.close(); }catch(e){}
    es = null;
    // try reconnect after delay
    setTimeout(startSSE, 3000);
  };
}

function renderJobs(jobs){
  const area = el('jobsArea');
  const newJson = JSON.stringify(jobs || []);
  if(newJson === jobsCacheJson) return; // nothing changed
  jobsCacheJson = newJson;
  if(!jobs || jobs.length===0){ if(area) area.innerHTML = '<div class="muted">No jobs</div>'; return }

  // Responsive: cards on narrow screens, table on larger
  if(window.innerWidth < 720){
    if(area) area.innerHTML = jobs.map(j => renderJobCard(j)).join('');
    attachCardHandlers();
    return;
  }

  // desktop/table layout
  let html = '<div class="table-wrapper"><table><thead><tr><th>ID</th><th>File</th><th>Status</th><th>Created</th><th>Started</th><th>Finished</th><th>Logs</th></tr></thead><tbody>';
  for(const j of jobs){
    const name = j.fileName || '';
    html += `<tr><td>${j.id||''}</td><td title="${escapeHtml(name)}">${escapeHtml(truncate(name,60))}</td><td>${badgeFor(j.status)}</td><td>${formatDate(j.createdAt)}</td><td>${formatDate(j.startedAt)}</td><td>${formatDate(j.finishedAt)}</td><td>${escapeHtml(j.logsPath||'')}</td></tr>`;
  }
  html += '</tbody></table></div>';
  if(area) area.innerHTML = html;
}

function upsertJob(job){
  let arr = [];
  try{ arr = JSON.parse(jobsCacheJson || '[]'); }catch(e){ arr = []; }
  let found = false;
  for(let i=0;i<arr.length;i++){ if(arr[i].id === job.id){ arr[i] = job; found = true; break; } }
  if(!found) arr.push(job);
  renderJobs(arr);
}

function deleteJobById(id){
  let arr = [];
  try{ arr = JSON.parse(jobsCacheJson || '[]'); }catch(e){ arr = []; }
  arr = arr.filter(j => j.id !== id);
  renderJobs(arr);
}

function renderJobCard(j){
  const name = j.fileName || '';
  const short = truncate(name, 45);
  return `<div class="job-card" data-id="${j.id||''}" data-name="${escapeHtml(name)}">
    <div class="job-row"><div><strong>#${j.id||''}</strong></div><div>${badgeFor(j.status)}</div></div>
    <div class="job-filename">${escapeHtml(short)}</div>
    <div class="job-meta"><small>Created: ${formatDate(j.createdAt)} · Started: ${formatDate(j.startedAt)} · Finished: ${formatDate(j.finishedAt)}</small></div>
    <div class="job-actions"><button class="btn small show-path">Show</button><button class="btn small copy-path">Copy</button></div>
  </div>`;
}

function attachCardHandlers(){
  document.querySelectorAll('.job-card').forEach(card => {
    const showBtn = card.querySelector('.show-path');
    const copyBtn = card.querySelector('.copy-path');
    const filenameEl = card.querySelector('.job-filename');
    const name = card.dataset.name || '';
    // ensure truncated display
    filenameEl.textContent = truncate(name, 45);
    showBtn.addEventListener('click', ()=>{
      const expanded = card.classList.toggle('expanded');
      if(expanded){ filenameEl.textContent = name; showBtn.textContent = 'Hide'; }
      else { filenameEl.textContent = truncate(name,45); showBtn.textContent = 'Show'; }
    });
    copyBtn.addEventListener('click', async ()=>{
      try{ await navigator.clipboard.writeText(name); showToast('Copied filename to clipboard'); }
      catch(e){ window.prompt('Copy filename', name); }
    });
  });
}

window.addEventListener('resize', debounce(()=>{ if(jobsCacheJson) listJobs(); }, 300));

function showToast(msg){ console.log(msg); }

document.addEventListener('DOMContentLoaded',()=>{
  const startBtn = el('startBtn'); if(startBtn) startBtn.addEventListener('click',doStart);
  const stopBtn = el('stopBtn'); if(stopBtn) stopBtn.addEventListener('click',doStop);
  // status is updated automatically; no manual status button
  const clearBtn = el('clearBtn'); if(clearBtn) clearBtn.addEventListener('click',doClear);
  // rescan button removed — watcher runs automatically
  // manual enqueue UI removed
  // start SSE updates (worker status will be pushed via SSE)
  startSSE();
});
