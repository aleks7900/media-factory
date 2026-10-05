import {useState, useEffect} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {
    Activity,
    ArrowUpRight,
    Check,
    ChevronRight,
    CircleDollarSign,
    Images,
    Layers3,
    LayoutDashboard,
    ListVideo,
    Loader2,
    LogOut,
    Plug,
    Plus,
    RotateCw,
    ScanEye,
    Settings,
    Sparkles,
    X
} from 'lucide-react';
import {api, apiUrl, checkAuth, getCurrentUser, logout, subscribeUnauthorized, type AuthUser, type Row} from './api';
import {Login} from './Login';
import './login.css';
import {
    GenerationDetails,
    GenerationDialog,
    type ProviderInfo,
    ProvidersPanel
} from './ProviderPanels';
import { WebGLBackground } from './visual/WebGLBackground';
import { GlobalJobIndicator } from './components/GlobalJobIndicator';
import { ImmersiveProgress } from './components/ImmersiveProgress';
import { useGenerationProgress } from './hooks/useGenerationProgress';
import { useReducedMotion } from './hooks/useReducedMotion';
import './visual/visual.css';
import {PromptStudio} from './PromptStudio';
import {CollectionQaPolicies, QaDashboard, QaJobs, ReviewWorkspace} from './ReviewWorkspace';
import {SimilarityDashboard, SimilarityWorkspace} from './SimilarityWorkspace';
import {ProcessingWorkspace} from './ProcessingWorkspace';
import {WallpaperWorkspace} from './WallpaperWorkspace';
import {StockWorkspace} from './StockWorkspace';
import {VideoWorkspace} from './VideoWorkspace';
import {AnalyticsWorkspace} from './AnalyticsWorkspace';
import {FeedbackWorkspace} from './FeedbackWorkspace';
import {BulkWorkspace} from './BulkWorkspace';
import {PublishingWorkspace} from './PublishingWorkspace';

const pages = ['Dashboard', 'Collections', 'Generation Queue', 'Assets', 'Review', 'Providers', 'Costs', 'Settings', 'Processing', 'Wallpaper Factory', 'Stock Factory', 'Video Factory', 'Analytics', 'Feedback', 'Bulk Gemini Video', 'Bulk GPT Image'] as const;
const promptPages = ['Prompt Library', 'Prompt Editor', 'Prompt History', 'Presets', 'Experiments'] as const;
const similarityPages = ['Duplicate Review', 'Similarity Explorer', 'Collection Diversity', 'Embedding Jobs'] as const;
const publishingPages = ['TikTok Publishing'] as const;
type Page = typeof pages[number] | typeof promptPages[number] | typeof similarityPages[number] | typeof publishingPages[number];
const icons = [LayoutDashboard, Layers3, ListVideo, Images, ScanEye, Plug, CircleDollarSign, Settings, Sparkles, Images, Layers3, ListVideo, Activity, Sparkles, ListVideo, Images];
const labels: Record<string, string> = {
  generated_today: 'Generated today',
  approved_today: 'Approved today',
  rejected_today: 'Rejected today',
  pending_review: 'Pending review',
  generation_cost: 'Generation cost · USD',
  active_jobs: 'Active jobs',
  failed_jobs: 'Failed jobs'
};

function useRows(path: string, enabled = true) {
  return useQuery({queryKey: [path], queryFn: () => api<Row[]>(path), enabled});
}

function Badge({children}: { children: React.ReactNode }) {
  return <span
      className={'badge ' + String(children).toLowerCase()}>{String(children).replaceAll('_', ' ')}</span>;
}

export function App() {
  const [currentUser, setCurrentUser] = useState<AuthUser | null>(() => getCurrentUser());
  const [authChecked, setAuthChecked] = useState(false);
  const client = useQueryClient();
  const { activeJob, setBackground } = useGenerationProgress();
  useReducedMotion();

  useEffect(() => {
    let mounted = true;
    checkAuth()
      .then(user => {
        if (mounted) {
          setCurrentUser(user);
          setAuthChecked(true);
        }
      })
      .catch(() => {
        if (mounted) {
          setCurrentUser(null);
          setAuthChecked(true);
        }
      });

    const unsubscribe = subscribeUnauthorized(() => {
      if (mounted) {
        setCurrentUser(null);
        client.clear();
      }
    });

    return () => {
      mounted = false;
      unsubscribe();
    };
  }, [client]);

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      setCurrentUser(null);
      client.clear();
    }
  };

  const isAuth = !!currentUser;
  const [page, setPage] = useState<Page>('Dashboard');
  const [creating, setCreating] = useState(false);
  const [selectedGeneration, setSelectedGeneration] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [processingAsset, setProcessingAsset] = useState<string>();
  const [processingProfiles, setProcessingProfiles] = useState<string[]>();
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api<Record<string, number>>('/dashboard'),
    enabled: isAuth
  });
  const assets = useRows('/assets', isAuth);
  const generations = useRows('/generations', isAuth);
  const jobs = useRows('/jobs', isAuth);
  const collections = useRows('/collections', isAuth);
  const projects = useRows('/projects', isAuth);
  const concepts = useRows('/concepts', isAuth);
  const costs = useRows('/costs', isAuth);
  const providers = useQuery({
    queryKey: ['/providers'],
    queryFn: () => api<ProviderInfo[]>('/v1/providers/image'),
    enabled: isAuth
  });
  const realEnabled = providers.data?.some(p => p.enabled && p.id !== 'mock') ?? false;
  const action = useMutation({
    mutationFn: ({path, body, key}: {
      path: string;
      body: unknown;
      key?: string
    }) => api(path, body, key), onSuccess: () => {
      client.invalidateQueries();
      setNotice('Saved successfully');
    }, onError: (e) => setNotice(e.message)
  });
  const status = (asset: Row) => String(generations.data?.find(g => g.id === asset.generation_id)?.status ?? 'LOADING');
  const pending = assets.data?.filter(a => ['QA_PENDING', 'QA_RUNNING', 'NEEDS_REVIEW'].includes(status(a))) ?? [];
  const visibleAssets = assets.data ?? [];
  const error = [dashboard, assets, generations, jobs, collections, projects, concepts, costs, providers].find(q => q.error)?.error;

  return (
    <>
      <WebGLBackground
        route={currentUser ? page : 'Login'}
        generationState={activeJob?.status ?? null}
      />
      <ImmersiveProgress
        onViewResult={(id) => {
          setSelectedGeneration(id);
          setPage('Generation Queue');
        }}
      />

      {!currentUser ? (
        !authChecked ? (
          <div className="login-container">
            <div className="login-card" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 180 }}>
              <Loader2 size={24} className="login-spinner" style={{ color: '#c7b2fc' }} />
            </div>
          </div>
        ) : (
          <Login onLoginSuccess={(user) => { setCurrentUser(user); client.invalidateQueries(); }} />
        )
      ) : (
        <div className="app-shell">
          <aside><a className="brand" href="#" onClick={() => setPage('Dashboard')}><span
        className="brand-mark"><Layers3
        size={24}/></span>media<span>factory</span><sup>®</sup></a>
      <div className="workspace"><span className="workspace-avatar">S</span>
        <div>Studio workspace<small>Local environment</small></div>
        <ChevronRight size={14}/></div>
      <div className="nav-label">WORKSPACE</div>
      <nav>{pages.map((name, i) => {
        const Icon = icons[i];
        return <button key={name} className={page === name ? 'selected' : ''}
                       onClick={() => setPage(name)}>
          <Icon size={18}/>{name}{name === 'Review' && pending.length > 0 &&
            <b>{pending.length}</b>}
        </button>;
      })}</nav>
      <div className="nav-label">PROMPTS</div>
      <nav>{promptPages.map(name => <button key={name} className={page === name ? 'selected' : ''}
                                            onClick={() => setPage(name)}><Layers3 size={18}/>{name}
      </button>)}</nav>
      <div className="nav-label">PUBLISHING</div>
      <nav>{publishingPages.map(name => <button key={name} className={page === name ? 'selected' : ''}
                                                onClick={() => setPage(name)}><ListVideo size={18}/>TikTok
      </button>)}</nav>
      <div className="aside-bottom">
        <div className="nav-label">SIMILARITY</div>
        <nav>{similarityPages.map(name => <button key={name}
                                                  className={page === name ? 'selected' : ''}
                                                  onClick={() => setPage(name)}><ScanEye
            size={18}/>{name}</button>)}</nav>
        <div className="mock-indicator">
          <span/>{realEnabled ? 'Provider routing enabled' : 'Mock providers enabled'}</div>
        <p>{realEnabled ? 'Provider costs tracked per attempt.' : 'Your ideas. Zero API spend.'}</p>
        <div className="profile">
          <div className="avatar">AD</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 600, textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }}>
              {currentUser.username}
            </div>
            <small>{currentUser.role}</small>
          </div>
          <button
            type="button"
            className="logout-btn"
            onClick={handleLogout}
            title="Log out"
            aria-label="Log out"
          >
            <LogOut size={13} />
          </button>
        </div>
      </div>
    </aside>
    <main>
      <header>
        <div className="breadcrumb">Workspace <ChevronRight size={13}/> <span>{page === 'TikTok Publishing' ? 'Publishing → TikTok' : page}</span></div>
        <div className="header-right">
          <GlobalJobIndicator onOpenDetails={() => {
            if (activeJob) {
              setBackground(activeJob.id, false);
            }
          }} />
          <span className="environment">● {realEnabled ? 'LIVE PROVIDERS ENABLED' : 'MOCK ENVIRONMENT'}</span>
          <button
            type="button"
            className="logout-btn"
            onClick={handleLogout}
            title="Log out"
            aria-label="Log out"
          >
            <LogOut size={13} />
            <span>Logout</span>
          </button>
          <div className="avatar">AD</div>
        </div>
      </header>
      <div className="page-content">
        <div key={page} className="page-view-container">
        <div className="page-heading">
          <div>
            <div className="eyebrow">YOUR CREATIVE OPERATING SYSTEM</div>
            <h1>{page === 'Dashboard' ? 'Studio overview' : page}</h1>
            <p>{page === 'Dashboard' ? 'From the first idea to the final frame. All in one place.' : page === 'Review' ? 'A human eye on every final detail.' : page === 'Generation Queue' ? 'Follow every generation, from queued to complete.' : 'Build, organize, and refine your creative output.'}</p>
          </div>
          <button className="primary" onClick={() => setCreating(true)}><Plus size={17}/>New
            generation
          </button>
        </div>
        {error &&
            <div role="alert" className="error">Cannot reach the factory: {error.message}. Check
              that the
              backend is running.</div>}
        {notice && <div role="status" className="notice">{notice}
          <button aria-label="Dismiss notification" onClick={() => setNotice('')}><X size={15}/>
          </button>
        </div>}
        {page === 'Dashboard' && <>
          <div className="bulk-entry-cards">
            <button onClick={() => setPage('TikTok Publishing')}><ListVideo size={24}/><strong>TikTok
              Publishing</strong><span>Bulk upload ZIP and publish directly to TikTok API v2.</span><b>Publish
              Videos →</b></button>
            <button onClick={() => setPage('Bulk Gemini Video')}><ListVideo size={24}/><strong>Bulk
              Gemini
              Video</strong><span>Turn a ZIP of prompts and references into a video queue.</span><b>Upload
              ZIP →</b></button>
            <button onClick={() => setPage('Bulk GPT Image')}><Images size={24}/><strong>Bulk GPT
              Image</strong><span>Generate independent images from one task archive.</span><b>Upload
              ZIP →</b></button>
          </div>
          <section className="hero">
            <div>
              <div className="hero-tag"><Sparkles size={14}/> IMAGINATION, IN PRODUCTION</div>
              <h2>Make something<br/><em>worth looking at.</em></h2><p>Your next collection starts
              with a
              single idea.<br/>Explore the complete pipeline with free mock generation.</p>
              <button onClick={() => setCreating(true)}>Create your first frame <ArrowUpRight
                  size={17}/>
              </button>
            </div>
            <div className="hero-art" aria-hidden="true">
              <div className="orb orb-one"/>
              <div className="orb orb-two"/>
              <div className="orb orb-three"/>
              <span>MF — CREATIVE SYSTEM / 001</span></div>
          </section>
          <section className="stats">{Object.entries(labels).map(([key, label]) => <article
              key={key}>
            <div>{label}<Activity size={14}/></div>
            <strong>{dashboard.isPending ? '—' : key === 'generation_cost' ? `$${Number(dashboard.data?.[key] ?? 0).toFixed(2)}` : dashboard.data?.[key] ?? '—'}</strong><small>{key === 'generation_cost' && Number(dashboard.data?.unknown_cost_attempts ?? 0) > 0 ? `${dashboard.data?.unknown_cost_attempts} costs unknown` : key.includes('today') ? 'Since 00:00 UTC' : key === 'generation_cost' ? 'All-time estimated spend' : 'Live workspace total'}</small>
          </article>)}</section>
          <QaDashboard/>
          <SimilarityDashboard/>
          <div className="section-title"><h2>Recent
            creations <span>{assets.data?.length ?? 0}</span></h2>
            <button onClick={() => setPage('Assets')}>View all assets <ArrowUpRight size={15}/>
            </button>
          </div>
          <AssetGrid assets={(assets.data ?? []).slice(0, 4)} status={status}/>
          <div className="section-title"><h2>Production activity</h2>
            <button onClick={() => setPage('Generation Queue')}>View queue <ArrowUpRight size={15}/>
            </button>
          </div>
          <Jobs onSelect={setSelectedGeneration} rows={(jobs.data ?? []).slice(0, 5)}
                retry={id => action.mutate({path: `/jobs/${id}/retry`, body: {}})}
                disabled={action.isPending}/></>}
        {(page === 'Assets') && <>
          <div className="section-title"><h2>Asset library <span>{visibleAssets.length}</span></h2>
            <span
                className="muted">Originals are always preserved</span></div>
          <AssetGrid assets={visibleAssets} status={status} review={false}
                     disabled={action.isPending} onProcess={id => {
            setProcessingAsset(id);
            setPage('Processing');
          }}
                     onAction={(asset, decision) => action.mutate(decision === 'REGENERATE' ? {
                       path: `/assets/${asset.id}/regenerate`,
                       body: {},
                       key: crypto.randomUUID()
                     } : {path: '/reviews', body: {assetId: asset.id, decision, reason: ''}})}/></>}
        {page === 'Review' && <ReviewWorkspace/>}
        {page === 'Processing' && <ProcessingWorkspace initialAsset={processingAsset}
                                                       initialProfiles={processingProfiles}/>}
        {page === 'Stock Factory' && <StockWorkspace onQaReview={() => setPage('Review')}/>}
        {page === 'Video Factory' && <VideoWorkspace/>}
        {page === 'Analytics' && <AnalyticsWorkspace/>}
        {page === 'Feedback' && <FeedbackWorkspace/>}
        {page === 'Bulk Gemini Video' && <BulkWorkspace key="bulk-video" kind="GEMINI_VIDEO"/>}
        {page === 'Bulk GPT Image' && <BulkWorkspace key="bulk-image" kind="GPT_IMAGE"/>}
        {page === 'TikTok Publishing' && <PublishingWorkspace/>}
        {page === 'Wallpaper Factory' && <WallpaperWorkspace onProcess={(id, profiles) => {
          setProcessingAsset(id);
          setProcessingProfiles(profiles);
          setPage('Processing');
        }}/>}
        {page === 'Generation Queue' &&
            <Jobs onSelect={setSelectedGeneration} rows={jobs.data ?? []}
                  retry={id => action.mutate({path: `/jobs/${id}/retry`, body: {}})}
                  disabled={action.isPending}/>}
        {page === 'Generation Queue' && <QaJobs/>}
        {page === 'Collections' && <CollectionQaPolicies collections={collections.data ?? []}/>}
        {page === 'Collections' && <div className="collection-layout">
          <section className="panel"><h2>Projects & collections</h2><SimpleForm
              label="Create project"
              fields={['name']}
              onSubmit={v => action.mutate({
                path: '/projects',
                body: {
                  name: v.name,
                  description: ''
                }
              })}/><SimpleForm
              label="Create collection" fields={['name']}
              select={{name: 'projectId', label: 'Project', rows: projects.data ?? []}}
              onSubmit={v => action.mutate({path: '/collections', body: v})}/><SimpleForm
              label="Create concept" fields={['name', 'prompt']}
              select={{name: 'collectionId', label: 'Collection', rows: collections.data ?? []}}
              onSubmit={v => action.mutate({path: '/concepts', body: v})}/></section>
          <section className="panel"><h2>Collections <span
              className="muted">/ {collections.data?.length ?? 0}</span>
          </h2>{collections.data?.length ? collections.data.map(c => <div className="collection-row"
                                                                          key={c.id}>
            <div className="collection-icon"><Layers3/></div>
            <div><h3>{c.name}</h3>
              <p>{projects.data?.find(p => p.id === c.project_id)?.name ?? 'Project'} · {concepts.data?.filter(x => x.collection_id === c.id).length ?? 0} concepts</p>
            </div>
          </div>) : <Empty text="Create a project, then organize your ideas into collections."/>}
          </section>
        </div>}
        {page === 'Providers' && <ProvidersPanel providers={providers.data ?? []}/>}
        {promptPages.includes(page as typeof promptPages[number]) && <PromptStudio section={page}/>}
        {similarityPages.includes(page as typeof similarityPages[number]) &&
            <SimilarityWorkspace key={page} page={page}/>}
        {page === 'Costs' &&
            <div className="panel"><h2>Operation ledger</h2><p className="muted">Every provider
              attempt is
              recorded. Estimates exclude any costs marked unknown.</p>
              <div className="table-wrap">
                <table>
                  <thead>
                  <tr>{['Provider / model', 'Operation', 'Input usage', 'Output usage', 'Cost', 'Attempt'].map(x =>
                      <th key={x}>{x}</th>)}</tr>
                  </thead>
                  <tbody>{costs.data?.map(c => <tr key={c.id}>
                    <td>{c.provider}<small>{c.model}</small></td>
                    <td>{c.operation}</td>
                    <td>{c.input_usage}</td>
                    <td>{c.output_usage}</td>
                    <td>{c.estimated_cost == null ? 'Unknown' : Number(c.estimated_cost).toFixed(5)} {c.currency}<small>{c.pricing_status} · {c.pricing_version}</small>
                    </td>
                    <td>{c.attempt}</td>
                  </tr>)}</tbody>
                </table>
              </div>
              {!costs.data?.length &&
                  <Empty text="Your operation costs will appear after your first generation."/>}
            </div>}
        {page === 'Settings' &&
            <div className="panel settings"><h2>Workspace configuration</h2><p
                className="muted">Runtime
              configuration is managed through environment
              variables.</p>{[['Generation mode', realEnabled ? 'Configurable provider routing' : 'Mock only · no paid API keys'], ['Storage policy', 'Immutable originals + SHA-256 checksums'], ['Technical review', 'Resolution, aspect ratio, integrity, size, duplicates'], ['Worker retries', 'Provider policies · backoff with jitter'], ['Daily reporting', 'UTC'], ['Deployment', 'Private workspace · localhost by default']].map(([k, v]) =>
                <div className="setting-row" key={k}><span>{k}</span><strong>{v}</strong></div>)}
            </div>}
        <footer>MEDIA
          FACTORY <span>Built for the space between idea and impact.</span><span>FOUNDATION / v0.1</span>
        </footer>
        </div>
      </div>
    </main>
    {creating && <GenerationDialog concepts={concepts.data ?? []} providers={providers.data ?? []}
                                   close={() => setCreating(false)} onCreated={id => {
      setCreating(false);
      setPage('Generation Queue');
      setSelectedGeneration(id);
      client.invalidateQueries();
    }}/>}
    {selectedGeneration &&
        <GenerationDetails id={selectedGeneration} close={() => setSelectedGeneration(null)}/>}
  </div>
      )}
    </>
  );
}

function Empty({text}: { text: string }) {
  return <div className="empty"><Images size={28}/><h3>A little room for possibility.</h3>
    <p>{text}</p></div>;
}

function AssetGrid({assets, status, review = false, disabled = false, onAction, onProcess}: {
  assets: Row[];
  status: (a: Row) => string;
  review?: boolean;
  disabled?: boolean;
  onAction?: (a: Row, d: string) => void;
  onProcess?: (id: string) => void
}) {
  return assets.length ?
      <div className="asset-grid">{assets.map((a, i) => <article className="asset-card" key={a.id}>
        <div className="asset-image"><img src={apiUrl(`/assets/${a.id}/content`)}
                                          alt={`Generated artwork ${a.id.slice(0, 8)}`}
                                          loading="lazy"/><span
            className="asset-number">FRAME / {String(i + 1).padStart(3, '0')}</span></div>
        <div className="asset-info"><h3>Studio frame {a.id.slice(0, 6)}</h3>
          <div>
            <span>{a.width} × {a.height} · {String(a.media_type ?? 'image/png').replace('image/', '').toUpperCase()}</span><Badge>{status(a)}</Badge>
          </div>
          {onProcess &&
              <button onClick={() => onProcess(a.id)}><Sparkles size={14}/>Process & variants
              </button>}
          {review && <div className="review-actions">
            <button disabled={disabled} onClick={() => onAction?.(a, 'APPROVED')}><Check size={14}/>Approve
            </button>
            <button disabled={disabled} onClick={() => onAction?.(a, 'REJECTED')}><X size={14}/>Reject
            </button>
            <button disabled={disabled} title="Regenerate" aria-label="Regenerate"
                    onClick={() => onAction?.(a, 'REGENERATE')}><RotateCw size={14}/></button>
          </div>}</div>
      </article>)}</div> : <Empty
          text={review ? 'No assets are waiting for review. New generations will appear here after technical QA.' : 'Start a generation to fill your library with original media.'}/>;
}

function Jobs({rows, retry, disabled, onSelect}: {
  rows: Row[];
  retry: (id: string) => void;
  disabled: boolean;
  onSelect: (id: string) => void
}) {
  return <div className="panel table-wrap">
    <table>
      <thead>
      <tr>
        <th>Generation</th>
        <th>Status</th>
        <th>Attempts</th>
        <th>Created</th>
        <th>Details</th>
      </tr>
      </thead>
      <tbody>{rows.map(j => <tr key={j.id}>
        <td>
          <button className="generation-link"
                  onClick={() => onSelect(String(j.generation_id))}><span
              className="job-icon">✧</span>{String(j.generation_id).slice(0, 8)}</button>
        </td>
        <td><Badge>{j.status}</Badge></td>
        <td>{j.attempts} / {j.max_attempts}</td>
        <td>{new Date(String(j.created_at)).toLocaleString()}</td>
        <td>{j.status === 'FAILED' ?
            <button disabled={disabled} onClick={() => onSelect(String(j.generation_id))}>Details /
              retry</button> : null}<small>{j.failure_reason}</small></td>
      </tr>)}</tbody>
    </table>
    {!rows.length && <Empty text="Your queue is clear. Create a generation to get things moving."/>}
  </div>;
}

function SimpleForm({label, fields, select, onSubmit}: {
  label: string;
  fields: string[];
  select?: { name: string; label: string; rows: Row[] };
  onSubmit: (values: Record<string, string>) => void
}) {
  return <form className="simple-form" onSubmit={e => {
    e.preventDefault();
    const form = e.currentTarget;
    onSubmit(Object.fromEntries(new FormData(form).entries()) as Record<string, string>);
  }}><h3>{label}</h3>{select &&
      <label>{select.label}<select required name={select.name} defaultValue="">
        <option value="" disabled>Select {select.label.toLowerCase()}</option>
        {select.rows.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
      </select></label>}{fields.map(f =>
      <label key={f}>{f}<input name={f} required
                               maxLength={f === 'prompt' ? 10000 : 200}/></label>)}
    <button type="submit">{label}<Plus size={14}/></button>
  </form>;
}
