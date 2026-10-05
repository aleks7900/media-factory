import { useGenerationProgress } from '../hooks/useGenerationProgress';
import { ArrowRight, CheckCircle2, AlertTriangle, Layers, Eye } from 'lucide-react';

function formatElapsed(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export interface ImmersiveProgressProps {
  onViewResult?: (id: string) => void;
}

export function ImmersiveProgress({ onViewResult }: ImmersiveProgressProps) {
  const { activeJob, setBackground, clearJob } = useGenerationProgress();

  if (!activeJob || activeJob.isBackground) return null;

  const isCompleted = activeJob.status === 'COMPLETED';
  const isFailed = activeJob.status === 'FAILED';
  const hasRealPercent = activeJob.percent !== null && activeJob.percent !== undefined;

  const handleRunInBackground = () => {
    setBackground(activeJob.id, true);
  };

  const handleDismiss = () => {
    clearJob();
  };

  const handleView = () => {
    if (onViewResult) {
      onViewResult(activeJob.id);
    }
    clearJob();
  };

  return (
    <div className="immersive-overlay" role="dialog" aria-modal="true" aria-label="Generation in progress">
      <div className="immersive-hud-card">
        {/* Holographic 3D Orb Visualizer */}
        <div className="hologram-visualizer" aria-hidden="true">
          <div className="hologram-ring-outer" />
          <div className="hologram-ring-inner" />
          <div className="hologram-core" />
        </div>

        {/* Real Percentage or Indeterminate Badge */}
        {hasRealPercent ? (
          <div className="hud-percent-value">{activeJob.percent}%</div>
        ) : (
          <div className="hud-indeterminate-badge">
            {activeJob.status.replace(/_/g, ' ')}
          </div>
        )}

        <h3 className="hud-title">{activeJob.title}</h3>
        <p className="hud-phase-desc">{activeJob.phaseDescription}</p>

        {/* Progress Bar (Real vs Indeterminate) */}
        <div className="hud-progress-track" role="progressbar" aria-valuenow={hasRealPercent ? activeJob.percent! : undefined} aria-valuemin={0} aria-valuemax={100}>
          {hasRealPercent ? (
            <div
              className="hud-progress-bar-fill"
              style={{ width: `${Math.min(100, Math.max(0, activeJob.percent!))}%` }}
            />
          ) : (
            <div className="hud-progress-shimmer" />
          )}
        </div>

        {/* Stats Row */}
        <div className="hud-footer-stats">
          <span>
            {activeJob.kind} PIPELINE · {activeJob.id.slice(0, 8)}
          </span>
          <span>Elapsed {formatElapsed(activeJob.elapsedSeconds)}</span>
        </div>

        {/* Actions */}
        <div className="hud-actions">
          {!isCompleted && !isFailed && (
            <button
              type="button"
              className="hud-background-btn"
              onClick={handleRunInBackground}
              title="Continue using studio while generation runs in background"
            >
              Run in background
            </button>
          )}

          {isCompleted && (
            <>
              <button type="button" className="primary" onClick={handleView}>
                <Eye size={15} />
                View creation
              </button>
              <button type="button" className="hud-background-btn" onClick={handleDismiss}>
                Done
              </button>
            </>
          )}

          {isFailed && (
            <button type="button" className="hud-background-btn" onClick={handleDismiss}>
              Dismiss
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
