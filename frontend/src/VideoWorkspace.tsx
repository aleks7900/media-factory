import {useState} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {Film, Play, Plus, X} from 'lucide-react';
import {api, apiUrl} from './api';
import './video.css';
import {
  VideoCollectionControls,
  VideoMotionEditor,
  VideoProviderStatistics,
  VideoTimeline
} from './VideoTools';

type Variant = {
  id: string;
  kind: string;
  video_processing_run_id: string;
  width: number;
  height: number;
  size_bytes: number;
  metadata: Record<string, unknown>
};
type Production = {
  id: string;
  status: string;
  revision: number;
  paused: boolean;
  concept_name?: string;
  thumbnail_id?: string;
  raw_asset_id?: string;
  source_asset_id?: string;
  current_run_id?: string;
  master_variant_id?: string;
  failure_code?: string;
  variants?: Variant[];
  runs?: { id: string; version: number; evidence: Record<string, unknown> }[];
  attempts?: unknown[];
  qa?: unknown[];
  costs?: unknown[];
  events?: unknown[]
};
type Profile = {
  id: string;
  profile_key: string;
  version: number;
  definition: Record<string, unknown>
};
type Source = { id: string; generation_id: string; media_type?: string };
type Provider = { provider: string; enabled: boolean; model: string };
const url = (id: string) => apiUrl(`/v1/video/variants/${id}/content`);

export function VideoWorkspace() {
  const cache = useQueryClient();
  const [selected, setSelected] = useState('');
  const [source, setSource] = useState('');
  const [profile, setProfile] = useState('WALLPAPER_LOOP');
  const [provider, setProvider] = useState('mock-video');
  const [budget, setBudget] = useState('0');
  const [subject, setSubject] = useState('Gentle floating motion, preserve the subject');
  const [camera, setCamera] = useState('STATIC');
  const [loop, setLoop] = useState('AUTO');
  const [ack, setAck] = useState(false);
  const [notice, setNotice] = useState('');
  const [compare, setCompare] = useState('');
  const query = <T, >(path: string) => useQuery({
    queryKey: ['video', path],
    queryFn: () => api<T>(path),
    refetchInterval: 5000
  });
  const items = query<Production[]>('/v1/video/productions');
  const profiles = query<Profile[]>('/v1/video/profiles');
  const providers = query<Provider[]>('/v1/video/providers');
  const dashboard = query<Record<string, unknown>>('/v1/video/dashboard');
  const assets = query<Source[]>('/assets');
  const generations = query<{ id: string; status: string }[]>('/generations');
  const detail = useQuery({
    queryKey: ['video', selected],
    queryFn: () => api<Production>(`/v1/video/productions/${selected}`),
    enabled: !!selected,
    refetchInterval: 4000
  });
  const action = useMutation({
    mutationFn: (task: () => Promise<unknown>) => task(),
    onSuccess: () => {
      cache.invalidateQueries({queryKey: ['video']});
      setNotice('Saved. The queue will continue automatically.');
    },
    onError: e => setNotice(e.message)
  });
  const post = (path: string, body: unknown) => api(path, body, crypto.randomUUID());
  const current = detail.data;
  const variants = current?.variants?.filter(v => v.video_processing_run_id === current.current_run_id) ?? [];
  const master = variants.find(v => v.kind === 'MASTER_VIDEO');
  const repeated = variants.find(v => v.kind === 'LOOP_PREVIEW');
  const approved = (assets.data ?? []).filter(a => (!a.media_type || a.media_type.startsWith('image/')) && generations.data?.some(g => g.id === a.generation_id && g.status === 'APPROVED'));
  const mutate = (name: string) => {
    if (current) action.mutate(() => post(`/v1/video/productions/${current.id}/${name}`, {
      revision: current.revision,
      acknowledgeWarnings: ack,
      reason: 'Human video, temporal and loop evidence review'
    }));
  };
  return <div className="video-workspace">
    <div className="video-heading">
      <div><span className="eyebrow">MOTION STUDIO</span><h1>Turn approved images into video.</h1>
        <p>One image. Controlled motion. Every version preserved.</p></div>
      <Film size={38}/></div>
    <div
        className="video-metrics">{['generated_today', 'generating', 'queued', 'review', 'approved', 'failed'].map(key =>
        <div className="panel" key={key}>
          <strong>{String(dashboard.data?.[key] ?? 0)}</strong><span>{key.replaceAll('_', ' ')}</span>
        </div>)}</div>
    {notice && <p role="status" className="notice">{notice}</p>}{items.error &&
      <p role="alert">{items.error.message}</p>}
    <VideoCollectionControls onSave={task => action.mutate(task)}/><VideoProviderStatistics/>
    <details className="panel">
      <summary>Costs, processing time and active jobs</summary>
      <pre>{JSON.stringify(dashboard.data, null, 2)}</pre>
    </details>
    <div className="video-layout">
      <div className="panel video-controls"><h2><Plus size={18}/> Create motion</h2><label>Approved
        source image<select value={source} onChange={e => setSource(e.target.value)}>
          <option value="">Select a source</option>
          {approved.map(a => <option key={a.id} value={a.id}>{a.id.slice(0, 12)}</option>)}</select></label>{source &&
          <img src={apiUrl(`/assets/${source}/content`)} alt="Selected source image"/>}<label>Production
        profile<select value={profile}
                       onChange={e => setProfile(e.target.value)}>{profiles.data?.filter((p, i, a) => a.findIndex(x => x.profile_key === p.profile_key) === i).map(p =>
            <option key={p.id}>{p.profile_key}</option>)}</select></label><label>Video
        provider<select value={provider}
                        onChange={e => setProvider(e.target.value)}>{providers.data?.map(p =>
            <option key={p.provider} value={p.provider}
                    disabled={!p.enabled}>{p.provider}{!p.enabled ? ' · not configured' : ''}</option>)}</select></label><label>Subject
        motion<textarea value={subject} maxLength={240} onChange={e => setSubject(e.target.value)}/></label><label>Camera<select
          value={camera}
          onChange={e => setCamera(e.target.value)}>{['STATIC', 'PAN_LEFT', 'PAN_RIGHT', 'PUSH_IN', 'PULL_OUT'].map(x =>
          <option key={x}>{x}</option>)}</select></label><label>Maximum generation cost · USD<input
          type="number" min="0" step="0.01" value={budget}
          onChange={e => setBudget(e.target.value)}/></label><p className="muted">Mock generation
        costs $0. Real providers require an enabled adapter and configured pricing. Approval always
        requires a human review.</p>
        <button className="primary" disabled={!source || action.isPending}
                onClick={() => action.mutate(() => post('/v1/video/productions', {
                  sourceAssetId: source,
                  profile,
                  provider,
                  allowFallback: false,
                  motion: {subjectMotion: subject, cameraMotion: camera},
                  budget: Number(budget),
                  maxAttempts: 3
                }))}><Play size={15}/>Queue video
        </button>
      </div>
      <section>
        <div className="video-grid">{items.data?.map(p => <article className="panel video-card"
                                                                   key={p.id}>
          <button className="video-cover" onClick={() => {
            setSelected(p.id);
            setAck(false);
            setCompare('');
          }}>{p.thumbnail_id ?
              <img src={url(p.thumbnail_id)} alt={p.concept_name ?? 'Video preview'}
                   loading="lazy"/> : <Film size={40}/>}<span className="video-play"><Play
              size={18}/></span></button>
          <div><span className="badge">{p.status.replaceAll('_', ' ')}</span>
            <h3>{p.concept_name ?? 'Motion study'}</h3>{p.failure_code && <p>{p.failure_code}</p>}
            <button onClick={() => {
              setSelected(p.id);
              setAck(false);
            }}>Review motion
            </button>
          </div>
        </article>)}</div>
        {!items.data?.length &&
            <div className="panel video-empty"><Film size={44}/><h2>Bring your collection to
              life.</h2><p>Select an approved image to create your first motion study.</p></div>}
      </section>
    </div>
    {selected && <div className="modal-backdrop">
      <section className="modal video-detail" role="dialog" aria-label="Video review">
        <button className="close" aria-label="Close video review" onClick={() => setSelected('')}>
          <X/></button>
        <h2>Motion review</h2>{current ? <><span
          className="badge">{current.status}</span>{current.source_asset_id &&
          <img className="video-source-reference"
               src={apiUrl(`/assets/${current.source_asset_id}/content`)}
               alt="Original approved source"/>}<VideoMotionEditor id={current.id}
                                                                   revision={current.revision}
                                                                   onSave={task => action.mutate(task)}/>
        <div className="video-comparison">{current.raw_asset_id &&
            <div><h3>Original provider video</h3>
              <video controls preload="metadata"
                     src={apiUrl(`/assets/${current.raw_asset_id}/content`)}/>
            </div>}{master && <div><h3>Processed master</h3>
          <video controls preload="metadata" src={url(master.id)}/>
          <small>{master.width} × {master.height} · {(master.size_bytes / 1048576).toFixed(2)} MiB</small>
        </div>}{repeated && <div><h3>Loop continuity · three repetitions</h3>
          <video controls loop preload="metadata" src={url(repeated.id)}/>
        </div>}</div>
        <div className="video-actions"><label><input type="checkbox" checked={ack}
                                                     onChange={e => setAck(e.target.checked)}/>I
          watched the video and reviewed temporal defects and loop continuity.</label>
          <button disabled={!master || !ack || action.isPending} className="primary"
                  onClick={() => mutate('approve')}>Approve video
          </button>
          <button disabled={!master || action.isPending} onClick={() => mutate('reject')}>Reject
          </button>
          <button
              onClick={() => mutate(current.paused ? 'resume' : 'pause')}>{current.paused ? 'Resume' : 'Pause'}</button>
          <button onClick={() => mutate('cancel')}>Cancel</button>
        </div>
        <div className="panel video-actions"><label>Local loop strategy<select value={loop}
                                                                               onChange={e => setLoop(e.target.value)}>{['AUTO', 'DIRECT', 'CROSSFADE', 'PING_PONG'].map(x =>
            <option key={x}>{x}</option>)}</select></label>
          <button disabled={!current.raw_asset_id || action.isPending}
                  onClick={() => action.mutate(() => post(`/v1/video/productions/${current.id}/reprocess`, {
                    revision: current.revision,
                    settings: {loopStrategy: loop},
                    variants: {}
                  }))}>Reprocess · no new AI generation
          </button>
          <button disabled={action.isPending}
                  onClick={() => action.mutate(() => post(`/v1/video/productions/${current.id}/regenerate`, {
                    revision: current.revision,
                    budget: Number(budget)
                  }))}>Regenerate · may incur provider cost
          </button>
        </div>
        <VideoTimeline qa={current.qa ?? []}/>
        <details open>
          <summary>QA, temporal defects and loop evidence</summary>
          <pre>{JSON.stringify(current.qa, null, 2)}</pre>
        </details>
        <details>
          <summary>Device and social variants</summary>
          {variants.map(v => <p key={v.id}><a href={url(v.id)} target="_blank"
                                              rel="noreferrer">{v.kind}</a> · {v.width} × {v.height}
          </p>)}</details>
        <details>
          <summary>Compare processing versions</summary>
          <select aria-label="Earlier processing version" value={compare}
                  onChange={e => setCompare(e.target.value)}>
            <option value="">Select a version</option>
            {current.runs?.map(r => <option key={r.id} value={r.id}>Version {r.version}</option>)}
          </select>{compare && current.variants?.filter(v => v.video_processing_run_id === compare && v.kind === 'MASTER_VIDEO').map(v =>
            <video key={v.id} controls preload="metadata" src={url(v.id)}/>)}</details>
        <details>
          <summary>Provider attempts, costs and provenance</summary>
          <pre>{JSON.stringify({
            attempts: current.attempts,
            costs: current.costs,
            events: current.events
          }, null, 2)}</pre>
        </details>
      </> : <p>{detail.error?.message ?? 'Loading video…'}</p>}</section>
    </div>}
  </div>;
}
