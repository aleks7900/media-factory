import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Sparkles, X, ArrowRight, Clock, ShieldCheck } from 'lucide-react';
import { api, type Row } from './api';

export interface ProviderInfo {
 id:string; name:string; enabled:boolean; health:string; defaultModel:string; models:string[]; default:boolean; environment:string;
 capabilities:{ supportedFormats:string[]; supportedQualities:string[]; supportedSizes:string[]; arbitraryDimensions:boolean; supportsTransparentBackground:boolean };
 requestsPerMinute:number;maxConcurrent:number;
 stats:{ requests_today:number; success_rate:number; average_latency_ms:number; estimated_cost_today:number|null; unknown_cost_attempts:number };
}
export function ProvidersPanel({providers}:{providers:ProviderInfo[]}) {
 return <section className="provider-grid">{providers.map(p=><article className="panel provider-card" key={p.id}>
  <div className="section-title"><div className="collection-icon"><Sparkles/></div><span className={'badge '+p.health.toLowerCase()}>{p.health}</span></div>
  <h2>{p.name} {p.default&&<span className="badge">Default route</span>}</h2><p className="muted">{p.defaultModel}</p>
  <dl className="provider-stats"><div><dt>Requests today</dt><dd>{p.stats.requests_today}</dd></div><div><dt>Success rate</dt><dd>{Number(p.stats.success_rate).toFixed(1)}%</dd></div><div><dt>Average latency</dt><dd>{(Number(p.stats.average_latency_ms)/1000).toFixed(2)}s</dd></div><div><dt>Estimated cost · USD</dt><dd>{p.stats.estimated_cost_today==null?'Unknown':`$${Number(p.stats.estimated_cost_today).toFixed(4)}`}</dd></div></dl>
  {p.stats.unknown_cost_attempts>0&&<p className="muted">{p.stats.unknown_cost_attempts} attempts have unknown costs.</p>}
  <div className="provider-config"><span>{p.requestsPerMinute} requests/min</span><span>{p.maxConcurrent} concurrent</span></div>
  <p className="muted">{p.capabilities.supportedFormats.join(' / ')} · {p.capabilities.arbitraryDimensions?'Custom dimensions':p.capabilities.supportedSizes.join(' / ')}</p>
  <p className="muted">{p.id==='mock'?'Free local generation · '+p.environment:p.enabled?'Credentials configured on the server':'Disabled or credentials not configured'}</p>
 </article>)}</section>;
}

export function GenerationDialog({concepts,providers,close,onCreated}:{concepts:Row[];providers:ProviderInfo[];close:()=>void;onCreated:(id:string)=>void}) {
 const [provider,setProvider]=useState('');const [ratio,setRatio]=useState('SQUARE');const [model,setModel]=useState('');
 const idempotency=useRef({payload:'',key:crypto.randomUUID()});
 const selected=providers.find(p=>p.id===provider)||providers.find(p=>p.default);
 const mutation=useMutation({mutationFn:(body:unknown)=>{const payload=JSON.stringify(body);if(idempotency.current.payload&&idempotency.current.payload!==payload) idempotency.current.key=crypto.randomUUID();idempotency.current.payload=payload;return api<{generationId:string}>('/v1/generations/images',body,idempotency.current.key);},onSuccess:r=>onCreated(r.generationId)});
 function submit(e:FormEvent<HTMLFormElement>) {e.preventDefault();const f=new FormData(e.currentTarget);mutation.mutate({conceptId:f.get('conceptId'),prompt:f.get('prompt'),provider:provider||null,model:model||null,aspectRatio:ratio,quality:f.get('quality'),format:f.get('format'),...(ratio==='CUSTOM'?{width:Number(f.get('width')),height:Number(f.get('height'))}:{})});}
 return <Modal title="New generation" close={close}><div className="eyebrow">LET'S MAKE SOMETHING</div><h2>New generation</h2><p className="muted">Select a provider or let the configured route choose. Paid providers may incur charges.</p>
 {!concepts.length?<p>Create a project, collection, and concept on the Collections page first.</p>:<form onSubmit={submit}>
  <label>Concept<select name="conceptId" required>{concepts.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
  <label>Creative prompt<textarea name="prompt" required maxLength={10000} placeholder="Describe the scene you have in mind…"/></label>
  <div className="dimensions"><label>Provider<select aria-label="Provider" value={provider} onChange={e=>{setProvider(e.target.value);setModel('');}}><option value="">Auto{providers.find(p=>p.default)?` · ${providers.find(p=>p.default)?.name}`:''}</option>{providers.map(p=><option key={p.id} value={p.id} disabled={!p.enabled}>{p.name}{!p.enabled?' (disabled)':''}</option>)}</select></label>
  <label>Model<select aria-label="Model" value={model} onChange={e=>setModel(e.target.value)}><option value="">Provider default</option>{selected?.models.map(m=><option key={m}>{m}</option>)}</select></label></div>
  <div className="dimensions"><label>Aspect ratio<select name="aspectRatio" value={ratio} onChange={e=>setRatio(e.target.value)}><option value="SQUARE">Square · 1:1</option><option value="PORTRAIT">Portrait · 2:3</option><option value="LANDSCAPE">Landscape · 3:2</option><option value="CUSTOM">Custom dimensions</option></select></label>
  <label>Quality<select name="quality" defaultValue="AUTO">{['AUTO','LOW','MEDIUM','HIGH'].map(q=><option key={q}>{q}</option>)}</select></label></div>
  {ratio==='CUSTOM'&&<div className="dimensions"><label>Width<input name="width" type="number" min="64" max="4096" defaultValue="1024" required/></label><label>Height<input name="height" type="number" min="64" max="4096" defaultValue="1024" required/></label></div>}
  <label>Format<select name="format"><option>PNG</option><option>JPEG</option></select></label>
  {mutation.error&&<p role="alert" className="error">{mutation.error.message}</p>}<button className="primary" disabled={mutation.isPending}>{mutation.isPending?'Queuing…':'Generate image'}<Sparkles size={16}/></button>
 </form>}</Modal>;
}

interface Attempt {id:string;provider:string;model:string;attempt_number:number;status:string;duration_ms:number|null;fallback:boolean;error_type:string|null;error_message:string|null;outcome_unknown:boolean;estimated_cost:number|null;actual_cost:number|null;currency:string;provider_request_id:string|null}
interface Details {id:string;status:string;selected_provider:string;final_provider:string|null;model:string;created_at:string;started_at:string|null;completed_at:string|null;provider_route:{provider:string;model:string}[];attempts:Attempt[];assets:Row[];job:{id:string;status:string;failure_reason:string|null;recovery_required:boolean};costs:{currency:string;estimated_total:number|null;actual_total:number|null;unknown_attempts:number}[]}
export function GenerationDetails({id,close}:{id:string;close:()=>void}) {
 const client=useQueryClient();const [acknowledged,setAcknowledged]=useState(false);
 const {data,error}=useQuery({queryKey:['generation-details',id],queryFn:()=>api<Details>(`/v1/generations/${id}`),refetchInterval:2000});
 const retry=useMutation({mutationFn:()=>api(`/jobs/${data!.job.id}/retry`,{acknowledgeDuplicateRisk:acknowledged}),onSuccess:()=>{client.invalidateQueries();setAcknowledged(false);}});
 return <Modal title="Generation details" close={close}><div className="eyebrow">GENERATION / {id.slice(0,8)}</div><h2>Generation details</h2>{error&&<p className="error" role="alert">{error.message}</p>}{!data?<p className="muted">Loading generation…</p>:<>
  <div className="lifecycle"><span className={'badge '+data.status.toLowerCase()}>{data.status.replaceAll('_',' ')}</span><span className="muted">{data.attempts.length} attempts</span></div>
  <div className="route-line">{data.provider_route.map((h,i)=><span key={h.provider}>{i>0&&<ArrowRight size={13}/>}<strong>{h.provider}</strong><small>{h.model}</small></span>)}</div>
  <p className="muted">Selected: {data.selected_provider} · Final: {data.final_provider??'Pending'}</p>
  <div className="attempt-timeline">{data.attempts.map(a=><article key={a.id}><div className="timeline-dot"/><div className="attempt-heading"><strong>{a.provider} <small>Attempt {a.attempt_number}{a.fallback?' · fallback':''}</small></strong><span className={'badge '+a.status.toLowerCase()}>{a.status}</span></div><p className="muted">{a.model} · {a.duration_ms==null?'In progress':`${(a.duration_ms/1000).toFixed(2)}s`} · {a.estimated_cost==null?'Cost unknown':`${Number(a.estimated_cost).toFixed(5)} ${a.currency}`}</p>{a.error_message&&<p className="attempt-error">{a.error_type}: {a.error_message}</p>}{a.outcome_unknown&&<p className="attempt-error">Provider outcome unknown; billing may have occurred.</p>}{a.provider_request_id&&<small className="muted">Request: {a.provider_request_id}</small>}</article>)}</div>
  {!data.attempts.length&&<p className="muted"><Clock size={14}/> Waiting for a provider permit.</p>}
  {data.assets.map(a=><img className="detail-preview" key={a.id} src={`/api/assets/${a.id}/content`} alt="Generated original"/>)}
  <div className="detail-cost"><h3>Generation cost</h3>{data.costs.length?data.costs.map(c=><p key={c.currency}>{c.estimated_total==null?'Unknown':`${Number(c.estimated_total).toFixed(5)} ${c.currency} estimated`}{c.unknown_attempts>0?` + ${c.unknown_attempts} unknown attempt(s)`:''}<small className="muted"> · Actual: {c.actual_total==null?'not reported':`${Number(c.actual_total).toFixed(5)} ${c.currency}`}</small></p>):<p className="muted">No provider call yet.</p>}</div>
  {data.job.failure_reason&&<p className="attempt-error">{data.job.failure_reason}</p>}
  {data.job.status==='FAILED'&&<div>{data.job.recovery_required&&<label className="acknowledgement"><input type="checkbox" checked={acknowledged} onChange={e=>setAcknowledged(e.target.checked)}/>I checked the provider outcome and accept that retrying may incur another charge.</label>}<button disabled={retry.isPending||(data.job.recovery_required&&!acknowledged)} onClick={()=>retry.mutate()}>Retry generation</button></div>}
  {retry.error&&<p className="error" role="alert">{retry.error.message}</p>}<p className="muted detail-footnote"><ShieldCheck size={13}/> Original files and attempt history are preserved.</p>
 </>}</Modal>;
}
function Modal({title,close,children}:{title:string;close:()=>void;children:React.ReactNode}) {
 const panel=useRef<HTMLElement>(null);
 useEffect(()=>{const previous=document.activeElement as HTMLElement|null;panel.current?.focus();return()=>previous?.focus();},[]);
 return <div className="modal-backdrop"><section ref={panel} tabIndex={-1} className="modal" role="dialog" aria-modal="true" aria-label={title} onKeyDown={e=>{if(e.key==='Escape')close();if(e.key==='Tab'){const items=Array.from(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select,textarea')??[]);const first=items[0],last=items[items.length-1];if(e.shiftKey&&(document.activeElement===first||document.activeElement===panel.current)){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}}}><button className="modal-close" aria-label="Close" onClick={close}><X/></button>{children}</section></div>;
}
