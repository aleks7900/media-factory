import {useState} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {api} from './api';
import './analytics.css';

type MetricRow = Record<string, string | number | null>;
type Result = {rows: MetricRow[]; total: number; currency: string; generatedAt: string; warnings: string[]};
const sections = ['Overview','Assets','Collections','Prompts','Providers','Costs','Revenue','Platforms','Experiments'] as const;
const measures = ['views','likes','downloads','revenue','cost','profit','roi'];
export function calendarBoundary(date: string, timezone: string) {
  const target=Date.parse(date+'T00:00:00Z');let guess=target;
  for(let i=0;i<3;i++) {
    const parts=new Intl.DateTimeFormat('en-GB',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(guess));
    const values=Object.fromEntries(parts.map(p=>[p.type,p.value]));
    const represented=Date.UTC(+values.year,+values.month-1,+values.day,+values.hour,+values.minute,+values.second);
    guess+=target-represented;
  }
  return new Date(guess).toISOString();
}
export function displayMetric(value: unknown, metric: string, currency: string) {
  if (value === null || value === undefined) return 'Unavailable';
  const number = Number(value);
  if (metric === 'roi') return `${(number * 100).toFixed(1)}%`;
  return new Intl.NumberFormat('en', ['revenue','cost','profit','cost_per_approved'].includes(metric)
    ? {style:'currency',currency,maximumFractionDigits:4} : {maximumFractionDigits:2}).format(number);
}
export function AnalyticsWorkspace() {
  const [section,setSection]=useState<string>('Overview');
  const [period,setPeriod]=useState('30D');
  const [currency,setCurrency]=useState('USD');
  const [sort,setSort]=useState('cost');
  const [page,setPage]=useState(0);
  const [metric,setMetric]=useState('views');
  const [project,setProject]=useState('');
  const [collection,setCollection]=useState('');
  const [platform,setPlatform]=useState('');
  const [timezone,setTimezone]=useState('UTC');const [grain,setGrain]=useState('day');
  const [dimension,setDimension]=useState('');const [provider,setProvider]=useState('');const [model,setModel]=useState('');
  const [pipeline,setPipeline]=useState('');const [selectedAsset,setSelectedAsset]=useState('');
  const [mappingAsset,setMappingAsset]=useState('');const [externalId,setExternalId]=useState('');
  const [from,setFrom]=useState('');const [to,setTo]=useState('');
  const [csv,setCsv]=useState(''); const [batch,setBatch]=useState<Record<string,unknown>|null>(null);
  const client=useQueryClient();
  const p=new URLSearchParams({currency,timezone,size:'25',page:String(page),sort});
  const dateParts=Object.fromEntries(new Intl.DateTimeFormat('en-GB',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date()).map(p=>[p.type,p.value]));
  const now=new Date(`${dateParts.year}-${dateParts.month}-${dateParts.day}T00:00:00Z`);const end=new Date(now.getTime()+86400000);
  if(period!=='Lifetime' && period!=='Custom') {
    const start=period==='YTD'?new Date(Date.UTC(now.getUTCFullYear(),0,1)):new Date(end.getTime()-(period==='Today'?1:parseInt(period))*86400000);
    p.set('from',calendarBoundary(start.toISOString().slice(0,10),timezone));p.set('to',calendarBoundary(end.toISOString().slice(0,10),timezone));
  } else if(period==='Custom') {if(from)p.set('from',calendarBoundary(from,timezone));if(to)p.set('to',calendarBoundary(to,timezone));}
  if(project)p.set('projectId',project);if(collection)p.set('collectionId',collection);if(platform)p.set('platform',platform);
  if(provider)p.set('provider',provider);if(model)p.set('model',model);if(pipeline)p.set(pipeline,'true');
  const summaryParams=new URLSearchParams(p);summaryParams.set('page','0');
  const chartParams=new URLSearchParams(p);chartParams.set('page','0');chartParams.set('sort','group_key');chartParams.set('direction','asc');chartParams.set('size','200');chartParams.set('grain',grain);
  if(dimension)p.set('groupBy',dimension);
  const filter=p.toString();
  const data=useQuery({queryKey:['analytics',section,filter],queryFn:()=>api<Result>(`/v1/analytics/${section.toLowerCase()}?${filter}`)});
  const overview=useQuery({queryKey:['analytics','overview',summaryParams.toString()],queryFn:()=>api<Result>(`/v1/analytics/overview?${summaryParams}`)});
  const timeline=useQuery({queryKey:['analytics','timeseries',chartParams.toString()],queryFn:()=>api<Result>(`/v1/analytics/timeseries?${chartParams}`)});
  const detail=useQuery({queryKey:['analytics','asset-detail',selectedAsset,currency],queryFn:()=>api<Record<string,unknown>>(`/v1/analytics/assets/${selectedAsset}?currency=${currency}`),enabled:!!selectedAsset});
  const diagnostics=useQuery({queryKey:['analytics','diagnostics'],queryFn:()=>api<Record<string,unknown>>('/v1/analytics/diagnostics')});
  const projects=useQuery({queryKey:['/projects'],queryFn:()=>api<{id:string;name:string}[]>('/projects')});
  const collections=useQuery({queryKey:['/collections'],queryFn:()=>api<{id:string;name:string}[]>('/collections')});
  const action=useMutation({mutationFn:({path,body}:{path:string;body:unknown})=>api<Record<string,unknown>>('/v1/analytics/'+path,body),onSuccess:(result)=>{if(result.batch)setBatch(result);client.invalidateQueries({queryKey:['analytics']});}});
  const values=overview.data?.rows[0];
  const chart=(timeline.data?.rows??[]).filter(row=>row[metric]!==null&&row[metric]!==undefined);
  const max=Math.max(1,...chart.map(r=>Math.abs(Number(r[metric]??0))));
  return <section className="analytics-workspace">
    <div className="analytics-heading"><div><span className="eyebrow">MEASUREMENT & ECONOMICS</span><h2>Analytics</h2><p>Trace production spend to observed performance. Dates use {timezone}.</p></div>
      <button disabled={action.isPending} onClick={()=>action.mutate({path:'rebuild',body:{reason:'Dashboard refresh',createdBy:'local-user'}})}>Refresh aggregates</button></div>
    <div className="analytics-filters">
      <label>Period<select aria-label="Period" value={period} onChange={e=>{setPeriod(e.target.value);setPage(0);}}>{['Today','7D','30D','90D','YTD','Lifetime','Custom'].map(v=><option key={v}>{v}</option>)}</select></label>
      {period==='Custom'&&<><label>From (inclusive)<input type="date" value={from} onChange={e=>setFrom(e.target.value)}/></label><label>To (exclusive)<input type="date" value={to} onChange={e=>setTo(e.target.value)}/></label></>}
      <label>Currency<select aria-label="Currency" value={currency} onChange={e=>setCurrency(e.target.value)}>{['USD','EUR','GBP','RON'].map(v=><option key={v}>{v}</option>)}</select></label>
      <label>Timezone<select aria-label="Timezone" value={timezone} onChange={e=>{setTimezone(e.target.value);setPage(0);}}>{['UTC','Europe/Bucharest','America/New_York','Asia/Tokyo'].map(v=><option key={v}>{v}</option>)}</select></label>
      <label>Project<select aria-label="Project" value={project} onChange={e=>{setProject(e.target.value);setPage(0);}}><option value="">All projects</option>{projects.data?.map(v=><option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
      <label>Collection<select aria-label="Collection" value={collection} onChange={e=>{setCollection(e.target.value);setPage(0);}}><option value="">All collections</option>{collections.data?.map(v=><option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
      <label>Platform<input value={platform} onChange={e=>{setPlatform(e.target.value);setPage(0);}} placeholder="All platforms"/></label>
      <label>Provider<input value={provider} onChange={e=>{setProvider(e.target.value);setPage(0);}} placeholder="All providers"/></label>
      <label>Model<input value={model} onChange={e=>{setModel(e.target.value);setPage(0);}} placeholder="All models"/></label>
      <label>Pipeline<select aria-label="Pipeline" value={pipeline} onChange={e=>{setPipeline(e.target.value);setPage(0);}}><option value="">All pipelines</option>{['stock','wallpaper','video','amoled'].map(v=><option key={v}>{v}</option>)}</select></label>
    </div>
    <nav className="analytics-tabs" aria-label="Analytics sections">{sections.map(s=><button key={s} className={section===s?'selected':''} onClick={()=>{setSection(s);setDimension('');setPage(0);}}>{s}</button>)}</nav>
    {(data.error||overview.error||action.error)&&<p role="alert">{(data.error||overview.error||action.error)?.message}</p>}
    {data.isPending&&<p role="status">Loading analytics…</p>}
    <div className="analytics-cards">{measures.map(m=><article key={m}><span>{m.toUpperCase()}</span><strong className={Number(values?.[m])<0?'negative':''}>{displayMetric(values?.[m],m,currency)}</strong></article>)}</div>
    <p className="analytics-note">Unavailable means no verified measurement or a missing price/exchange rate. Zero is an observed value. Costs include failed and rejected generation attempts.</p>
    <div className="analytics-funnel" aria-label="Production counts"><span>Generations <b>{displayMetric(values?.generated,'generated',currency)}</b></span><span aria-hidden="true">→</span><span>Currently approved <b>{displayMetric(values?.approved,'approved',currency)}</b></span><span aria-hidden="true">→</span><span>Processed assets <b>{displayMetric(values?.processed,'processed',currency)}</b></span><span aria-hidden="true">→</span><span>Published assets <b>{displayMetric(values?.published,'published',currency)}</b></span><small>Creation and publication use their own occurrence dates.</small></div>
    <div className="panel"><div className="analytics-heading"><h3>Performance over time</h3><label>Interval<select aria-label="Interval" value={grain} onChange={e=>setGrain(e.target.value)}>{['day','week','month'].map(v=><option key={v}>{v}</option>)}</select></label><label>Chart metric<select aria-label="Chart metric" value={metric} onChange={e=>setMetric(e.target.value)}>{['views','downloads','revenue','cost','profit'].map(m=><option key={m}>{m}</option>)}</select></label></div>
      {timeline.error&&<p role="alert">{timeline.error.message}</p>}
      {(timeline.data?.total??0)>200&&<p>Showing the first 200 intervals. Choose a wider interval or a shorter period.</p>}
      {chart.length===0?<p>No measurements in this period.</p>:<div className="analytics-chart" role="img" aria-label={`${metric} over time`}>{chart.map(r=><div key={String(r.group_key)} title={`${r.group_key}: ${displayMetric(r[metric],metric,currency)}`}><div className={Number(r[metric])<0?'negative':''} style={{height:`${Math.max(1,Math.abs(Number(r[metric]??0))/max*130)}px`}}/><small>{String(r.group_key).slice(5)}</small></div>)}</div>}
    </div>
    <details className="panel"><summary>Revenue vs cost · {currency}</summary><p className="analytics-note">Both series use the same monetary scale. Missing values remain unavailable.</p>{(timeline.data?.rows??[]).filter(r=>r.revenue!=null||r.cost!=null).map(r=>{
      const ceiling=Math.max(1,...(timeline.data?.rows??[]).flatMap(v=>[Math.abs(Number(v.revenue??0)),Math.abs(Number(v.cost??0))]));
      return <div className="analytics-money-row" key={String(r.group_key)}><small>{r.group_key}</small>{['revenue','cost'].map(k=><div key={k}><span>{k} · {displayMetric(r[k],k,currency)}</span><i className={k} style={{width:`${Math.abs(Number(r[k]??0))/ceiling*100}%`}}/></div>)}</div>;
    })}</details>
    <div className="panel"><div className="analytics-heading"><h3>{section} performance</h3><label>Group by<select aria-label="Group by" value={dimension} onChange={e=>{setDimension(e.target.value);setPage(0);}}><option value="">Section default</option>{['asset','collection','project','concept','prompt','template','experiment','variant','provider','model','platform','style','preset','generationProfile','videoProfile','motionProfile','loopStrategy','wallpaperProfile','pipeline','amoled','generationDate','publicationDate','rejectionReason','processingProfile','cluster','publication','deviceVariant'].map(v=><option key={v}>{v}</option>)}</select></label><label>Sort by<select aria-label="Sort by" value={sort} onChange={e=>{setSort(e.target.value);setPage(0);}}>{[...measures,'generated','approved'].map(m=><option key={m}>{m}</option>)}</select></label><a href={`/api/v1/analytics/export?${filter}&groupBy=${dimension||({Overview:'overview',Collections:'collection',Prompts:'prompt',Providers:'provider',Costs:'overview',Revenue:'overview',Platforms:'platform',Experiments:'variant'} as Record<string,string>)[section]||'asset'}`}>Export CSV</a></div>
      {!data.isPending&&data.data?.rows.length===0?<p>No analytics available for these filters.</p>:<div className="analytics-table"><table><thead><tr><th>Dimension</th>{['generated','approved',...measures].map(m=><th key={m}>{m}</th>)}</tr></thead><tbody>{data.data?.rows.map(r=><tr key={String(r.group_key)}><td title={String(r.group_key)}>{(dimension==='asset'||(!dimension&&section==='Assets'))&&r.group_key!=='UNATTRIBUTED'?<button onClick={()=>setSelectedAsset(String(r.group_key))}>{r.thumbnail_id&&<img loading="lazy" className="analytics-thumbnail" src={`/api/v1/variants/${r.thumbnail_id}/content`} alt="Asset preview"/>}{String(r.group_key).slice(0,12)} · Details</button>:String(r.group_key)}</td>{['generated','approved',...measures].map(m=><td key={m} className={Number(r[m])<0?'negative':''}>{displayMetric(r[m],m,currency)}</td>)}</tr>)}</tbody></table></div>}
      <div className="analytics-pagination"><button disabled={page===0} onClick={()=>setPage(page-1)}>Previous</button><span>Page {page+1} · {data.data?.total??0} groups</span><button disabled={(page+1)*25>=(data.data?.total??0)} onClick={()=>setPage(page+1)}>Next</button></div>
    </div>
    {selectedAsset&&<div className="panel"><div className="analytics-heading"><h3>Asset economics · {selectedAsset.slice(0,12)}</h3><button onClick={()=>setSelectedAsset('')}>Close asset details</button></div>{detail.isPending?<p>Loading asset lineage…</p>:detail.error?<p role="alert">{detail.error.message}</p>:Object.entries(detail.data??{}).map(([name,value])=><details key={name}><summary>{name}</summary><pre>{JSON.stringify(value,null,2)}</pre></details>)}</div>}
    <details className="panel"><summary>Import measurements · validate before commit</summary><p>Generic CSV columns: externalId,type,value,occurredAt,currency,eventId. Map external IDs through the reference API first. ISO timestamps include a timezone. Preview does not add financial facts.</p><textarea aria-label="Analytics CSV" rows={6} value={csv} onChange={e=>setCsv(e.target.value)} placeholder="externalId,type,value,occurredAt,currency,eventId"/>
      <div className="analytics-filters"><label>Internal asset ID<input value={mappingAsset} onChange={e=>setMappingAsset(e.target.value)}/></label><label>External asset ID<input value={externalId} onChange={e=>setExternalId(e.target.value)}/></label><button disabled={!mappingAsset||!externalId||!platform||action.isPending} onClick={()=>action.mutate({path:'references',body:{assetId:mappingAsset,externalId,platform}})}>Map external asset</button></div>
      <button disabled={!csv||!platform||action.isPending} onClick={()=>action.mutate({path:'imports',body:{source:'CSV_IMPORT',platform,filename:'manual.csv',csv,columns:{externalId:'externalId',type:'type',value:'value',occurredAt:'occurredAt',currency:'currency',eventId:'eventId'},createdBy:'local-user'}})}>Validate CSV</button>
      {batch&&<><pre>{JSON.stringify(batch,null,2)}</pre><button disabled={action.isPending} onClick={()=>action.mutate({path:`imports/${(batch.batch as {id:string}).id}/commit`,body:{}})}>Commit valid rows / retry mappings</button></>}
    </details>
    <details className="panel"><summary>Data quality & reconciliation</summary><pre>{JSON.stringify(diagnostics.data,null,2)}</pre></details>
  </section>;
}




