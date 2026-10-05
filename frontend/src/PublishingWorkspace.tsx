import {useState} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {
  AlertCircle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock,
  ExternalLink,
  FileVideo,
  Pause,
  Play,
  RefreshCw,
  RotateCw,
  UploadCloud,
  X,
  XCircle
} from 'lucide-react';
import {api, apiUrl} from './api';
import './publishing.css';

export interface PublishingAccountDto {
  id: string;
  platform: string;
  accountId: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  scopes: string;
  privacyLevelOptions: string[];
  commentDisabled: boolean;
  duetDisabled: boolean;
  stitchDisabled: boolean;
  maxVideoPostDurationSec: number;
  isActive: boolean;
  isAuthorized: boolean;
  createdAt: string;
}

export interface BatchSummary {
  id: string;
  projectId: string;
  accountId: string;
  accountUsername: string;
  platform: string;
  name: string;
  status: string;
  totalVideos: number;
  published: number;
  processing: number;
  queued: number;
  failed: number;
  cancelled: number;
  draft: number;
  paused: boolean;
  isCancelled: boolean;
  createdAt: string;
  completedAt: string | null;
}

export interface PublishingTaskDto {
  id: string;
  batchId: string;
  platform: string;
  videoFilename: string;
  videoSizeBytes: number;
  durationSeconds: number | null;
  width: number | null;
  height: number | null;
  caption: string;
  privacyLevel: string;
  disableComment: boolean;
  disableDuet: boolean;
  disableStitch: boolean;
  status: string;
  validationError: string | null;
  publishId: string | null;
  postId: string | null;
  postUrl: string | null;
  attempts: number;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface BatchPreview {
  batchId: string;
  batchName: string;
  platform: string;
  targetAccount: PublishingAccountDto;
  totalCount: number;
  validCount: number;
  invalidCount: number;
  tasks: PublishingTaskDto[];
}

export function PublishingWorkspace() {
  const queryClient = useQueryClient();

  const [selectedBatchId, setSelectedBatchId] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [page, setPage] = useState<number>(0);
  const [pageSize, setPageSize] = useState<number>(20);
  const [uploadBatchName, setUploadBatchName] = useState<string>('');
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [previewData, setPreviewData] = useState<BatchPreview | null>(null);
  const [editingCaptions, setEditingCaptions] = useState<Record<string, string>>({});
  const [editingPrivacy, setEditingPrivacy] = useState<Record<string, string>>({});
  const [playingVideoId, setPlayingVideoId] = useState<string | null>(null);
  const [isUploading, setIsUploading] = useState<boolean>(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // 1. Account Query
  const accountQuery = useQuery({
    queryKey: ['publishing-account'],
    queryFn: () => api<PublishingAccountDto>('/v1/publishing/account?platform=TIKTOK')
  });

  // 2. Batches Query
  const batchesQuery = useQuery({
    queryKey: ['publishing-batches'],
    queryFn: () => api<BatchSummary[]>('/v1/publishing/batches?platform=TIKTOK'),
    refetchInterval: 3000
  });

  const batches = batchesQuery.data ?? [];
  const currentBatchId = selectedBatchId ?? (batches.length > 0 ? batches[0].id : null);

  // 3. Active Batch Detail Query
  const activeBatchQuery = useQuery({
    queryKey: ['publishing-batch', currentBatchId],
    queryFn: () => currentBatchId ? api<BatchSummary>(`/v1/publishing/batches/${currentBatchId}`) : null,
    enabled: !!currentBatchId,
    refetchInterval: (data) => {
      const b = data.state.data;
      return (b && ['QUEUED', 'RUNNING', 'UPLOADING', 'PROCESSING'].includes(b.status)) ? 2000 : 5000;
    }
  });

  const activeBatch = activeBatchQuery.data;

  // 4. Tasks Query
  const tasksQuery = useQuery({
    queryKey: ['publishing-tasks', currentBatchId, statusFilter, page, pageSize],
    queryFn: () => {
      if (!currentBatchId) return [];
      const filterParam = statusFilter === 'ALL' ? '' : `&status=${statusFilter}`;
      return api<PublishingTaskDto[]>(`/v1/publishing/batches/${currentBatchId}/tasks?page=${page}&pageSize=${pageSize}${filterParam}`);
    },
    enabled: !!currentBatchId,
    refetchInterval: (data) => {
      return (activeBatch && ['QUEUED', 'RUNNING', 'UPLOADING', 'PROCESSING'].includes(activeBatch.status)) ? 2000 : false;
    }
  });

  const tasks = tasksQuery.data ?? [];

  // Mutations for batch controls
  const pauseMutation = useMutation({
    mutationFn: (id: string) => api(`/v1/publishing/batches/${id}/pause`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['publishing-batch', currentBatchId]});
      queryClient.invalidateQueries({queryKey: ['publishing-batches']});
    }
  });

  const resumeMutation = useMutation({
    mutationFn: (id: string) => api(`/v1/publishing/batches/${id}/resume`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['publishing-batch', currentBatchId]});
      queryClient.invalidateQueries({queryKey: ['publishing-batches']});
    }
  });

  const retryMutation = useMutation({
    mutationFn: (id: string) => api(`/v1/publishing/batches/${id}/retry`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['publishing-batch', currentBatchId]});
      queryClient.invalidateQueries({queryKey: ['publishing-batches']});
      queryClient.invalidateQueries({queryKey: ['publishing-tasks']});
    }
  });

  const cancelMutation = useMutation({
    mutationFn: (id: string) => api(`/v1/publishing/batches/${id}/cancel`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['publishing-batch', currentBatchId]});
      queryClient.invalidateQueries({queryKey: ['publishing-batches']});
      queryClient.invalidateQueries({queryKey: ['publishing-tasks']});
    }
  });

  // Step 1: Upload ZIP & Analyze -> Preview
  const handleUploadAndAnalyze = async () => {
    if (!uploadFile) return;
    setIsUploading(true);
    setActionError(null);

    try {
      const formData = new FormData();
      formData.append('file', uploadFile);
      if (uploadBatchName) {
        formData.append('batchName', uploadBatchName);
      }

      const res = await fetch(apiUrl('/v1/publishing/upload'), {
        method: 'POST',
        body: formData
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail || err.message || `Upload failed with status ${res.status}`);
      }

      const preview: BatchPreview = await res.json();
      setPreviewData(preview);

      // Prepopulate edit states with parsed captions
      const initCaptions: Record<string, string> = {};
      const initPrivacy: Record<string, string> = {};
      preview.tasks.forEach(t => {
        initCaptions[t.id] = t.caption;
        initPrivacy[t.id] = t.privacyLevel;
      });
      setEditingCaptions(initCaptions);
      setEditingPrivacy(initPrivacy);

      setUploadFile(null);
      setUploadBatchName('');
    } catch (e: any) {
      setActionError(e.message);
    } finally {
      setIsUploading(false);
    }
  };

  // Step 2 & 3: Save edits and Confirm Publish
  const handleConfirmPublish = async () => {
    if (!previewData) return;
    setActionError(null);

    try {
      // 1. Save any updated captions/settings in draft
      for (const task of previewData.tasks) {
        const caption = editingCaptions[task.id];
        const privacy = editingPrivacy[task.id];
        if (caption !== task.caption || privacy !== task.privacyLevel) {
          await api(`/v1/publishing/tasks/${task.id}`, {
            caption,
            privacyLevel: privacy
          }, undefined, 'PUT');
        }
      }

      // 2. Confirm publish
      const summary = await api<BatchSummary>(`/v1/publishing/batches/${previewData.batchId}/publish`, {});

      setPreviewData(null);
      setSelectedBatchId(summary.id);
      queryClient.invalidateQueries({queryKey: ['publishing-batches']});
      queryClient.invalidateQueries({queryKey: ['publishing-batch', summary.id]});
      queryClient.invalidateQueries({queryKey: ['publishing-tasks', summary.id]});
    } catch (e: any) {
      setActionError(e.message);
    }
  };

  const handleTikTokReauth = async () => {
    setActionError(null);

    try {
      const response = await fetch(
          apiUrl('/v1/publishing/tiktok/auth-url')
      );

      if (!response.ok) {
        throw new Error(
            `Failed to get TikTok authorization URL: ${response.status}`
        );
      }

      const data: { url?: string } = await response.json();

      if (!data.url) {
        throw new Error('TikTok authorization URL was not returned');
      }

      window.location.assign(data.url);
    } catch (e: any) {
      setActionError(e.message ?? 'Failed to start TikTok authorization');
    }
  };

  const account = accountQuery.data;

  return (
    <div className="publishing-workspace">
      {/* Target TikTok Account Header */}
      <section className="tiktok-account-banner">
        <div className="account-profile">
          {account?.avatarUrl ? (
            <img src={account.avatarUrl} alt={account.username} className="account-avatar" />
          ) : (
            <div className="account-avatar">TT</div>
          )}
          <div className="account-details">
            <h3>
              TikTok account: <span className="account-handle">@{account?.username || 'mediafactory_studio'}</span>
              <span className="account-badge-pill">Direct Post API v2</span>
            </h3>
            <div className="account-meta">
              <span>{account?.displayName || 'Media Content Factory'}</span>
              <span>•</span>
              <span>Available Privacy: {account?.privacyLevelOptions?.join(', ') || 'SELF_ONLY'}</span>
            </div>
          </div>
        </div>

        <div>
          <button
              className="control-btn"
              onClick={handleTikTokReauth}
          >
            <RefreshCw size={14} /> Switch / Re-auth Account
          </button>
        </div>
      </section>

      {/* Error Notice */}
      {actionError && (
        <div className="video-card-error" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>{actionError}</span>
          <button style={{ background: 'none', border: 'none', color: '#fca5a5', cursor: 'pointer' }} onClick={() => setActionError(null)}>
            <X size={16} />
          </button>
        </div>
      )}

      {/* Upload ZIP Section */}
      <section className="publish-upload-card">
        <h3>Publish New Video Batch</h3>
        <p style={{ color: '#94a3b8', fontSize: '13px', margin: '4px 0 16px 0' }}>
          Upload a ZIP containing MP4 videos (with optional <code>metadata.json</code> for captions & settings). Videos will be analyzed and previewed before publishing.
        </p>

        <div className="upload-form-row">
          <input
            type="text"
            placeholder="Batch name (e.g. Cars October)"
            value={uploadBatchName}
            onChange={(e) => setUploadBatchName(e.target.value)}
          />
          <input
            type="file"
            accept=".zip"
            id="zip-upload-input"
            style={{ display: 'none' }}
            onChange={(e) => {
              if (e.target.files && e.target.files[0]) {
                setUploadFile(e.target.files[0]);
              }
            }}
          />
          <label htmlFor="zip-upload-input" className="control-btn" style={{ cursor: 'pointer', padding: '10px 16px' }}>
            <UploadCloud size={16} /> {uploadFile ? uploadFile.name : 'Select ZIP Archive'}
          </label>

          <button
            className="btn-confirm-publish"
            disabled={!uploadFile || isUploading}
            onClick={handleUploadAndAnalyze}
            style={{ padding: '9px 18px' }}
          >
            {isUploading ? <RefreshCw size={16} className="animate-spin" /> : <Play size={16} />}
            Upload & Analyze
          </button>
        </div>
      </section>

      {/* Active Batch Dashboard Card */}
      {activeBatch ? (
        <section className="batch-dashboard-card">
          <div className="batch-top-bar">
            <div className="batch-title-group">
              <h2>Batch: {activeBatch.name}</h2>
              <span>TikTok account: @{activeBatch.accountUsername} • Created {new Date(activeBatch.createdAt).toLocaleString()}</span>
            </div>

            <div className="batch-controls">
              {/* Batch Selector if multiple batches exist */}
              {batches.length > 1 && (
                <select
                  value={currentBatchId || ''}
                  onChange={(e) => {
                    setSelectedBatchId(e.target.value);
                    setPage(0);
                  }}
                  style={{
                    background: '#192734',
                    border: '1px solid #2d3f50',
                    color: '#e2e8f0',
                    borderRadius: '8px',
                    padding: '8px 12px',
                    fontSize: '13px'
                  }}
                >
                  {batches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name} ({b.totalVideos} vids) - {b.status}
                    </option>
                  ))}
                </select>
              )}

              {/* Action Buttons as requested in prompt: [Pause] [Resume] [Retry Failed] [Cancel] */}
              <button
                className="control-btn btn-pause"
                disabled={activeBatch.paused || activeBatch.status === 'COMPLETED' || activeBatch.isCancelled}
                onClick={() => pauseMutation.mutate(activeBatch.id)}
              >
                <Pause size={14} /> Pause
              </button>

              <button
                className="control-btn btn-resume"
                disabled={!activeBatch.paused}
                onClick={() => resumeMutation.mutate(activeBatch.id)}
              >
                <Play size={14} /> Resume
              </button>

              <button
                className="control-btn btn-retry"
                disabled={activeBatch.failed === 0}
                onClick={() => retryMutation.mutate(activeBatch.id)}
              >
                <RotateCw size={14} /> Retry Failed ({activeBatch.failed})
              </button>

              <button
                className="control-btn btn-cancel"
                disabled={activeBatch.isCancelled || activeBatch.status === 'COMPLETED'}
                onClick={() => cancelMutation.mutate(activeBatch.id)}
              >
                <XCircle size={14} /> Cancel
              </button>
            </div>
          </div>

          {/* Metrics Tiles: Published, Processing, Queued, Failed, Total */}
          <div className="batch-metrics-grid">
            <div className="metric-tile total">
              <span className="label">Videos</span>
              <span className="value">{activeBatch.totalVideos}</span>
            </div>
            <div className="metric-tile published">
              <span className="label">Published</span>
              <span className="value">{activeBatch.published}</span>
            </div>
            <div className="metric-tile processing">
              <span className="label">Processing</span>
              <span className="value">{activeBatch.processing}</span>
            </div>
            <div className="metric-tile queued">
              <span className="label">Queued</span>
              <span className="value">{activeBatch.queued}</span>
            </div>
            <div className="metric-tile failed">
              <span className="label">Failed</span>
              <span className="value">{activeBatch.failed}</span>
            </div>
          </div>

          {/* Filter Tabs */}
          <div className="filter-tabs-row">
            <div className="tabs-group">
              {['ALL', 'QUEUED', 'PROCESSING', 'PUBLISHED', 'FAILED'].map((tab) => (
                <button
                  key={tab}
                  className={`filter-tab ${statusFilter === tab ? 'active' : ''}`}
                  onClick={() => {
                    setStatusFilter(tab);
                    setPage(0);
                  }}
                >
                  {tab}
                </button>
              ))}
            </div>

            <div style={{ fontSize: '12px', color: '#64748b' }}>
              Showing {tasks.length} tasks
            </div>
          </div>

          {/* Video Tasks Grid */}
          <div className="tasks-grid">
            {tasks.map((task) => (
              <div key={task.id} className="video-card">
                <div className="video-card-thumb">
                  <img
                    src={apiUrl(`/v1/publishing/tasks/${task.id}/thumbnail`)}
                    alt={task.videoFilename}
                    loading="lazy"
                  />
                  <button
                    className="play-overlay-btn"
                    title="Play Video"
                    onClick={() => setPlayingVideoId(task.id)}
                  >
                    <Play size={18} />
                  </button>

                  <span className={`video-card-status-badge status-badge-${task.status.toLowerCase()}`}>
                    {task.status}
                  </span>
                </div>

                <div className="video-card-body">
                  <div className="video-card-title" title={task.videoFilename}>
                    {task.videoFilename}
                  </div>

                  <div className="video-card-caption" title={task.caption}>
                    {task.caption || <span style={{ color: '#64748b' }}>No caption provided</span>}
                  </div>

                  {task.validationError && (
                    <div className="video-card-error">
                      <strong>Validation Error:</strong> {task.validationError}
                    </div>
                  )}

                  {task.lastErrorMessage && task.status === 'FAILED' && (
                    <div className="video-card-error">
                      <strong>Error:</strong> {task.lastErrorMessage}
                    </div>
                  )}

                  <div className="video-card-meta">
                    <span>
                      {(task.videoSizeBytes / (1024 * 1024)).toFixed(1)} MB
                      {task.durationSeconds ? ` • ${task.durationSeconds.toFixed(0)}s` : ''}
                      {task.attempts > 0 ? ` • ${task.attempts} att` : ''}
                    </span>

                    {task.postUrl ? (
                      <a
                        href={task.postUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="tiktok-post-link"
                      >
                        TikTok Post <ExternalLink size={12} />
                      </a>
                    ) : task.publishId ? (
                      <span style={{ color: '#25f4ee', fontSize: '11px' }}>
                        ID: {task.publishId.substring(0, 14)}...
                      </span>
                    ) : null}
                  </div>
                </div>
              </div>
            ))}
          </div>

          {/* Pagination for large batches */}
          <div className="pagination-bar">
            <span>
              Page {page + 1} • {pageSize} per page
            </span>

            <div className="pagination-controls">
              <button
                className="page-btn"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft size={16} /> Prev
              </button>
              <button
                className="page-btn"
                disabled={tasks.length < pageSize}
                onClick={() => setPage((p) => p + 1)}
              >
                Next <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </section>
      ) : (
        <section className="batch-dashboard-card" style={{ textAlign: 'center', padding: '48px 24px' }}>
          <FileVideo size={48} color="#64748b" style={{ margin: '0 auto 16px auto' }} />
          <h3 style={{ color: '#e2e8f0', margin: '0 0 8px 0' }}>No Publishing Batches Yet</h3>
          <p style={{ color: '#94a3b8', fontSize: '14px', maxWidth: '420px', margin: '0 auto' }}>
            Upload a ZIP archive above containing your MP4 videos to start a bulk TikTok publishing queue.
          </p>
        </section>
      )}

      {/* Safety Workflow: Pre-Publish Preview & Caption Editing Modal */}
      {previewData && (
        <div className="pre-publish-modal-overlay">
          <div className="pre-publish-dialog">
            <div className="pre-publish-header">
              <div>
                <h2>Batch Analysis & Preview: {previewData.batchName}</h2>
                <span style={{ fontSize: '13px', color: '#94a3b8' }}>
                  Target TikTok Account: <strong style={{ color: '#fe2c55' }}>@{previewData.targetAccount.username}</strong>
                </span>
              </div>
              <button
                style={{ background: 'none', border: 'none', color: '#94a3b8', cursor: 'pointer' }}
                onClick={() => setPreviewData(null)}
              >
                <X size={20} />
              </button>
            </div>

            <div className="pre-publish-stats-bar">
              <span>Total Videos: <strong>{previewData.totalCount}</strong></span>
              <span className="stat-pill valid">
                <CheckCircle2 size={14} /> Valid: {previewData.validCount}
              </span>
              {previewData.invalidCount > 0 && (
                <span className="stat-pill invalid">
                  <AlertCircle size={14} /> Invalid: {previewData.invalidCount}
                </span>
              )}
              <span style={{ marginLeft: 'auto', color: '#94a3b8' }}>
                Review captions and privacy settings below before confirming.
              </span>
            </div>

            <div className="pre-publish-body">
              {previewData.tasks.map((task) => {
                const isInvalid = Boolean(task.validationError);
                return (
                  <div key={task.id} className={`preview-task-item ${isInvalid ? 'invalid' : ''}`}>
                    <div className="preview-thumb-box">
                      <img
                        src={apiUrl(`/v1/publishing/tasks/${task.id}/thumbnail`)}
                        alt={task.videoFilename}
                      />
                      {task.durationSeconds && (
                        <span className="duration-tag">{task.durationSeconds.toFixed(0)}s</span>
                      )}
                    </div>

                    <div className="preview-task-fields">
                      <div className="preview-filename">{task.videoFilename}</div>

                      {isInvalid ? (
                        <div className="video-card-error">
                          <strong>Cannot publish:</strong> {task.validationError}
                        </div>
                      ) : (
                        <>
                          <div>
                            <label style={{ display: 'block', fontSize: '11px', color: '#94a3b8', marginBottom: '4px' }}>
                              Caption / Title
                            </label>
                            <textarea
                              className="caption-input"
                              value={editingCaptions[task.id] ?? task.caption}
                              onChange={(e) => {
                                setEditingCaptions({
                                  ...editingCaptions,
                                  [task.id]: e.target.value
                                });
                              }}
                              placeholder="Enter video caption #hashtags"
                            />
                          </div>

                          <div className="preview-toggles">
                            <label>
                              Privacy:
                              <select
                                value={editingPrivacy[task.id] ?? task.privacyLevel}
                                onChange={(e) => {
                                  setEditingPrivacy({
                                    ...editingPrivacy,
                                    [task.id]: e.target.value
                                  });
                                }}
                                style={{
                                  background: '#0f1822',
                                  border: '1px solid #2a3e50',
                                  color: '#e2e8f0',
                                  padding: '4px 8px',
                                  borderRadius: '6px',
                                  fontSize: '12px'
                                }}
                              >
                                {previewData.targetAccount.privacyLevelOptions.map((opt) => (
                                  <option key={opt} value={opt}>
                                    {opt}
                                  </option>
                                ))}
                              </select>
                            </label>
                          </div>
                        </>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="pre-publish-footer">
              <button className="control-btn" onClick={() => setPreviewData(null)}>
                Discard Draft
              </button>
              <button
                className="btn-confirm-publish"
                disabled={previewData.validCount === 0}
                onClick={handleConfirmPublish}
              >
                <CheckCircle2 size={16} /> Confirm & Publish ({previewData.validCount} Videos)
              </button>
            </div>
          </div>
        </div>
      )}

      {/* In-Browser Video Player Modal */}
      {playingVideoId && (
        <div className="video-player-modal" onClick={() => setPlayingVideoId(null)}>
          <div className="video-player-content" onClick={(e) => e.stopPropagation()}>
            <button className="btn-close-player" onClick={() => setPlayingVideoId(null)}>
              <X size={16} />
            </button>
            <video
              src={apiUrl(`/v1/publishing/tasks/${playingVideoId}/video`)}
              controls
              autoPlay
            />
          </div>
        </div>
      )}
    </div>
  );
}
