import {useRef, useState} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {Archive, Download, Film, ImagePlus, UploadCloud} from 'lucide-react';
import {api} from './api';
import './bulk.css';

type Data = Record<string, any>;
type Kind = 'GPT_IMAGE' | 'GEMINI_VIDEO';
const states = ['ALL', 'PENDING', 'QUEUED', 'GENERATING', 'COMPLETED', 'FAILED', 'RETRYING', 'CANCELLED'];
const date = (value: string | null) => value ? new Date(value).toLocaleString() : '—';
export function BulkWorkspace({kind}: {kind: Kind}) {
  const video = kind === 'GEMINI_VIDEO';
  const client = useQueryClient();
  const [project, setProject] = useState(''), [name, setName] = useState(''), [file, setFile] = useState<File>();
  const [provider, setProvider] = useState(video ? 'mock-video' : 'mock'), [model, setModel] = useState('');
  const [size, setSize] = useState(video ? '1280:720' : '1024:1024'), [duration, setDuration] = useState(5);
  const [quality, setQuality] = useState('AUTO'), [format, setFormat] = useState('PNG'), [transparent, setTransparent] = useState(false);
  const [paid, setPaid] = useState(false), [selected, setSelected] = useState(''), [task, setTask] = useState('');
  const [search, setSearch] = useState(''), [filter, setFilter] = useState('ALL'), [page, setPage] = useState(0);
  const [historySearch, setHistorySearch] = useState(''), [historyStatus, setHistoryStatus] = useState('ALL'), [historyPage, setHistoryPage] = useState(0);
  const [notice, setNotice] = useState('');
  // Retain this key after response loss. Only changing the submitted plan creates a new import.
  const importKey = useRef(crypto.randomUUID());
  const actionKeys = useRef(new Map<string,string>());
  const changePlan = () => {importKey.current = crypto.randomUUID(); setPaid(false);};
  const projects = useQuery({queryKey: ['/projects'], queryFn: () => api<Data[]>('/projects')});
  const capabilities = useQuery({queryKey: ['bulk', 'capabilities'], queryFn: () => api<Data>('/v1/bulk/capabilities')});
  const providers: Data[] = (capabilities.data?.[video ? 'videoProviders' : 'imageProviders'] ?? []).filter((p: Data) => video ? ['gemini', 'mock-video'].includes(p.provider) : true);
  const config = providers.find(p => p.provider === provider);
  const chosenModel = model || config?.defaultModel || config?.model || '';
  const models: string[] = config?.models ?? config?.capabilities?.models ?? [];
  const real = !provider.startsWith('mock');
  const history = useQuery({queryKey: ['bulk', 'history', project, kind, historySearch, historyStatus, historyPage], enabled: !!project,
    queryFn: () => api<Data[]>(`/v1/bulk/batches?${new URLSearchParams({projectId: project, kind, search: historySearch, status: historyStatus, page: String(historyPage)})}`), refetchInterval: 5000});
  const batch = useQuery({queryKey: ['bulk', 'batch', selected], enabled: !!selected, queryFn: () => api<Data>(`/v1/bulk/batches/${selected}`), refetchInterval: 3000});
  const tasks = useQuery({queryKey: ['bulk', 'tasks', selected, filter, search, page], enabled: !!selected,
    queryFn: () => api<Data[]>(`/v1/bulk/batches/${selected}/tasks?${new URLSearchParams({status: filter, search, page: String(page)})}`), refetchInterval: 3000});
  const detail = useQuery({queryKey: ['bulk', 'task', task], enabled: !!task, queryFn: () => api<Data>(`/v1/bulk/tasks/${task}`), refetchInterval: 3000});
  const refresh = () => client.invalidateQueries({queryKey: ['bulk']});
  const upload = useMutation({mutationFn: async () => {
    if (!file || !project || !chosenModel) throw new Error('Choose a project, ZIP archive and configured model.');
    if (file.size > (capabilities.data?.archiveLimits?.archiveBytes ?? 104857600)) throw new Error('Archive exceeds the upload limit.');
    const [width, height] = size.split(':').map(Number);
    const options = video ? {width, height, durationSeconds: duration} : {width, height, quality, format, transparentBackground: transparent, numberOfOutputs: 1};
    const form = new FormData(); form.append('archive', file);
    form.append('request', new Blob([JSON.stringify({projectId: project, name: name.trim() || file.name, kind, provider, model: chosenModel, options, authorizePaid: paid})], {type: 'application/json'}));
    const response = await fetch('/api/v1/bulk/batches', {method: 'POST', headers: {'Idempotency-Key': importKey.current}, body: form});
    if (!response.ok) {const error = await response.json().catch(() => ({})); throw new Error(error.detail || error.message || `Import failed (${response.status})`);}
    return response.json() as Promise<Data>;
  }, onSuccess: b => {setSelected(b.id);setPage(0);setTask('');setNotice(`Imported ${b.totalTasks} tasks: ${b.totalTasks - (b.counts.FAILED ?? 0)} valid, ${b.counts.FAILED ?? 0} invalid, ${b.references} references.`);refresh();}, onError: e => setNotice(e.message)});
  const action = useMutation({mutationFn: ({scope, id, action, key}: {scope: string; id: string; action: string; key: string}) => api<Data>(`/v1/bulk/${scope}/${id}/${action}`, {}, key),
    onSuccess: (r, v) => {actionKeys.current.delete(`${v.scope}/${v.id}/${v.action}`);if(v.action==='delete'){if(v.scope==='batches'){setSelected('');setTask('');}else setTask('');}if(v.action==='regenerate')setTask(r.id);setNotice(`Action recorded: ${v.action}.`);refresh();}, onError: e => setNotice(e.message)});
  const act = (scope: string, id: string, verb: string) => {
    if (verb === 'regenerate' && !String(detail.data?.provider ?? '').startsWith('mock') && !window.confirm('Regenerating creates a new billable task using the original provider settings. Continue?')) return;
    if (verb === 'delete' && !window.confirm('Remove this item from the workspace? Original media and audit history are retained.')) return;
    const identity=`${scope}/${id}/${verb}`;
    const key=actionKeys.current.get(identity) ?? crypto.randomUUID();actionKeys.current.set(identity,key);
    action.mutate({scope, id, action: verb, key});
  };
  const problem = [projects, capabilities, history, batch, tasks, detail].find(q => q.error)?.error;
  const d = detail.data, b = batch.data;
  return <div className="bulk-workspace">
    <section className="bulk-hero"><div className="bulk-symbol">{video ? <Film size={30}/> : <ImagePlus size={30}/>}</div><div><small>PARALLEL CREATION / INDEPENDENT TASKS</small><h2>Bulk {video ? 'Gemini Video' : 'GPT Image'}</h2><p>One archive. A complete production queue. Every result tracked independently.</p></div><span className="badge">{real ? 'Paid provider' : 'Zero-cost mock mode'}</span></section>
    {notice && <p role="status" className="bulk-notice">{notice}</p>}{problem && <p role="alert" className="error">{problem.message}</p>}
    <section className="panel bulk-import"><h3><UploadCloud size={19}/> Import a production batch</h3><div className="bulk-form">
      <label>Project<select value={project} onChange={e=>{setProject(e.target.value);setSelected('');setTask('');changePlan();}}><option value="">Select project</option>{projects.data?.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
      <label>Batch name<input value={name} maxLength={200} placeholder="October production" onChange={e=>{setName(e.target.value);changePlan();}}/></label>
      <label>Provider<select value={provider} onChange={e=>{setProvider(e.target.value);setModel('');setTransparent(false);if(video){setDuration(e.target.value==='gemini'?8:5);setSize('1280:720');}changePlan();}}>{providers.map(p=><option key={p.provider} value={p.provider}>{p.provider}{!p.enabled?' · not configured':''}</option>)}</select></label>
      <label>Model<select value={chosenModel} onChange={e=>{setModel(e.target.value);changePlan();}}>{models.map(m=><option key={m}>{m}</option>)}</select></label>
      <label>Resolution<select value={size} onChange={e=>{setSize(e.target.value);changePlan();}}>{(video ? config?.capabilities?.resolutions ?? ['1280:720','720:1280','1920:1080','1080:1920'] : (config?.capabilities?.supportedSizes?.length ? config.capabilities.supportedSizes.map((s:string)=>s.replace('x',':')) : ['1024:1024','1024:1536','1536:1024'])).map((s:string)=><option key={s} value={s}>{s.replace(':',' × ')}</option>)}</select></label>
      {video ? <label>Duration<select value={duration} onChange={e=>{setDuration(Number(e.target.value));changePlan();}}>{(config?.capabilities?.durations ?? [4,6,8]).map((n:number)=><option key={n} value={n}>{n} seconds</option>)}</select></label> : <><label>Quality<select value={quality} onChange={e=>{setQuality(e.target.value);changePlan();}}>{(config?.capabilities?.supportedQualities ?? ['AUTO','LOW','MEDIUM','HIGH']).map((q:string)=><option key={q}>{q}</option>)}</select></label><label>Format<select value={format} onChange={e=>{setFormat(e.target.value);if(e.target.value!=='PNG')setTransparent(false);changePlan();}}>{(config?.capabilities?.supportedFormats ?? ['PNG','JPEG']).map((f:string)=><option key={f}>{f}</option>)}</select></label><label>Outputs per task<input value="1" readOnly aria-label="Outputs per task"/><small>Current operation supports one original.</small></label><label className="bulk-check"><input type="checkbox" disabled={!config?.capabilities?.supportsTransparentBackground || format!=='PNG'} checked={transparent} onChange={e=>{setTransparent(e.target.checked);changePlan();}}/> Transparent background</label></>}
    </div><label className="bulk-drop"><Archive size={27}/><strong>{file?.name ?? 'Choose a task archive'}</strong><span>ZIP · up to 100 MiB · up to 1,000 tasks</span><input aria-label="Task ZIP archive" type="file" accept=".zip,application/zip" onChange={e=>{setFile(e.target.files?.[0]);changePlan();}}/></label>
    <p className="bulk-help">Each folder contains task.md and optional PNG/JPEG references. Alternatively, use one .md or .txt file per task at the archive root. {video ? 'Gemini supports up to three references with an 8-second duration.' : 'The current GPT generation operation does not support references; such tasks are marked invalid without blocking the rest.'}</p>
    {real && <label className="bulk-check"><input type="checkbox" checked={paid} onChange={e=>setPaid(e.target.checked)}/> I authorize paid generation for every valid task in this archive. Final cost may be unavailable before execution.</label>}
    <button className="primary" disabled={upload.isPending || !file || !project || !config?.enabled || (real && !paid)} onClick={()=>upload.mutate()}>{upload.isPending?'Validating archive…':'Import & queue tasks'}</button></section>
    <section className="panel"><div className="bulk-bar"><h3>Batch history</h3><input aria-label="Search batches" placeholder="Search batches…" value={historySearch} onChange={e=>{setHistorySearch(e.target.value);setHistoryPage(0);}}/><select aria-label="Batch status" value={historyStatus} onChange={e=>{setHistoryStatus(e.target.value);setHistoryPage(0);}}>{['ALL','QUEUED','RUNNING','PAUSED','COMPLETED','FAILED','CANCELLED'].map(s=><option key={s}>{s}</option>)}</select></div>
      {!project ? <p>Select a project to view its batches.</p> : history.isPending ? <p>Loading batches…</p> : !history.data?.length ? <p>No batches match this view.</p> : <div className="bulk-history">{history.data.map(h=><button className={selected===h.id?'selected':''} key={h.id} onClick={()=>{setSelected(h.id);setTask('');setPage(0);}}><strong>{h.name}</strong><span className="badge">{h.status}</span><small>{h.totalTasks} tasks · {h.progress}% complete · {date(h.created_at)}</small></button>)}</div>}
      <div className="bulk-pager"><button disabled={historyPage===0} onClick={()=>setHistoryPage(p=>p-1)}>Previous</button><span>Page {historyPage+1}</span><button disabled={(history.data?.length??0)<100} onClick={()=>setHistoryPage(p=>p+1)}>Next</button></div></section>
    {b && <section className="panel"><div className="bulk-bar"><h3>{b.name}</h3><span className="badge">{b.status}</span><a className="bulk-download" href={`/api/v1/bulk/batches/${selected}/results.zip`}><Download size={16}/> Results ZIP</a></div><p>{b.archive_name} · {b.totalTasks} tasks · {b.references} references · {b.provider} / {b.model}</p>
      <div className="bulk-stats">{['COMPLETED','GENERATING','QUEUED','FAILED','RETRYING','CANCELLED'].map(s=><div key={s}><strong>{b.counts[s]??0}</strong><small>{s.toLowerCase()}</small></div>)}</div><progress aria-label="Batch completion" value={b.progress} max={100}/><p>{b.progress}% completed</p>
      <div className="bulk-actions">{[b.paused?'resume':'pause','retry-failed','cancel','delete'].map(a=><button disabled={action.isPending || (b.cancelled && a!=='delete')} key={a} onClick={()=>act('batches',selected,a)}>{a.replace('-',' ')}</button>)}</div>
      <div className="bulk-bar"><input aria-label="Search tasks" placeholder="Search task names…" value={search} onChange={e=>{setSearch(e.target.value);setPage(0);}}/><select aria-label="Task status" value={filter} onChange={e=>{setFilter(e.target.value);setPage(0);}}>{states.map(s=><option key={s}>{s}</option>)}</select></div>
      <div className="bulk-table"><table><thead><tr><th>Task</th><th>Status</th><th>References</th><th>Retries</th><th>Result</th></tr></thead><tbody>{tasks.data?.map(t=><tr key={t.id}><td><button onClick={()=>setTask(t.id)}>{t.name}</button>{t.error_code&&<small className="bulk-error">{t.error_code}</small>}</td><td><span className={'badge '+t.status.toLowerCase()}>{t.status}</span></td><td>{t.inputs.length}</td><td>{t.retry_count}</td><td>{t.asset_id?<a href={`/api/assets/${t.asset_id}/content`} download>Download</a>:'—'}</td></tr>)}</tbody></table></div>
      <div className="bulk-pager"><button disabled={page===0} onClick={()=>setPage(p=>p-1)}>Previous</button><span>Page {page+1}</span><button disabled={(tasks.data?.length??0)<100} onClick={()=>setPage(p=>p+1)}>Next</button></div>
    </section>}
    {d && <section className="panel bulk-detail"><div className="bulk-bar"><h3>{d.name}</h3><span className="badge">{d.status}</span><button onClick={()=>setTask('')}>Close details</button></div><p>{d.provider} / {d.model} · {d.retry_count} retries · {d.attempts} attempts</p><p>Created {date(d.created_at)} · Started {date(d.started_at)} · Finished {date(d.completed_at)}</p><h4>Original prompt</h4><pre>{d.prompt}</pre>
      {!!d.inputs.length&&<div className="bulk-references">{d.inputs.map((r:Data,i:number)=><figure key={i}><img src={`/api/v1/bulk/tasks/${task}/references/${i}`} alt={r.name}/><figcaption>{r.name}</figcaption></figure>)}</div>}
      {d.error_message&&<p role="alert" className="error">{d.validation_error || d.error_message}</p>}{d.outcome_unknown&&<p className="error">Provider outcome is unknown. Automatic and manual resubmission are blocked until the original request is reconciled.</p>}
      {d.asset_id&&<div className="bulk-result">{video?<video controls preload="metadata" src={`/api/assets/${d.asset_id}/content`}/>:<img src={`/api/assets/${d.asset_id}/content`} alt={d.name}/>}<a href={`/api/assets/${d.asset_id}/content`} download>Download original</a></div>}
      <div className="bulk-actions">{['retry','cancel','regenerate','delete',...(d.outcome_unknown?['reconcile']:[])].map(a=><button key={a} disabled={action.isPending || ((a==='retry'||a==='regenerate')&&(d.outcome_unknown||d.validation_error||d.cancelled)) || (a==='retry'&&d.status!=='FAILED') || (a==='regenerate'&&!['COMPLETED','FAILED','CANCELLED'].includes(d.status))} onClick={()=>act('tasks',task,a)}>{a}</button>)}</div>
      <details><summary>Provider metadata and attempt history</summary><pre>{JSON.stringify({metadata:d.provider_metadata,attempts:d.attemptHistory},null,2)}</pre></details></section>}
  </div>;
}
