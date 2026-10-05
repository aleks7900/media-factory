import { useGenerationProgress } from '../hooks/useGenerationProgress';

function formatElapsed(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export function GlobalJobIndicator({ onOpenDetails }: { onOpenDetails?: () => void }) {
  const { activeJob, setBackground } = useGenerationProgress();

  if (!activeJob) return null;

  // Show if job is running in background, or is active
  const isFinished = activeJob.status === 'COMPLETED' || activeJob.status === 'FAILED';
  if (isFinished && activeJob.isBackground) return null;

  const handleClick = () => {
    // Bring back full screen view if in background
    if (activeJob.isBackground) {
      setBackground(activeJob.id, false);
    }
    if (onOpenDetails) {
      onOpenDetails();
    }
  };

  return (
    <button
      type="button"
      className="global-job-indicator"
      onClick={handleClick}
      title="View generation progress"
      aria-label={`Generation job: ${activeJob.title}`}
    >
      <span className="global-job-pulse" />
      <strong>{activeJob.title}</strong>
      <span>·</span>
      {activeJob.percent !== null ? (
        <span>{activeJob.percent}%</span>
      ) : (
        <span>{activeJob.status.replace(/_/g, ' ')}</span>
      )}
      <span>·</span>
      <span className="muted">{formatElapsed(activeJob.elapsedSeconds)}</span>
    </button>
  );
}
