import { Loader2, CheckCircle2, AlertCircle, Clock, Sparkles } from 'lucide-react';

export interface GenerationStatusProps {
  status: string;
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}

export function GenerationStatus({ status, className = '', size = 'md' }: GenerationStatusProps) {
  const upper = (status || 'UNKNOWN').toUpperCase();

  let icon = <Clock size={size === 'sm' ? 12 : 14} />;
  let label = upper.replace(/_/g, ' ');
  let colorClass = 'badge-queued';

  if (upper === 'QUEUED' || upper === 'PENDING') {
    icon = <Clock size={size === 'sm' ? 12 : 14} className="animate-pulse" />;
    colorClass = 'badge-queued';
    label = 'Queued';
  } else if (upper === 'GENERATING' || upper === 'RENDERING' || upper === 'PROCESSING' || upper === 'RUNNING') {
    icon = <Loader2 size={size === 'sm' ? 12 : 14} className="animate-spin" />;
    colorClass = 'badge-processing';
    label = upper === 'RENDERING' ? 'Rendering' : 'Processing';
  } else if (upper === 'SUCCEEDED' || upper === 'COMPLETED' || upper === 'APPROVED' || upper === 'READY') {
    icon = <CheckCircle2 size={size === 'sm' ? 12 : 14} />;
    colorClass = 'badge-completed';
    label = 'Completed';
  } else if (upper === 'FAILED' || upper === 'REJECTED' || upper === 'ERROR') {
    icon = <AlertCircle size={size === 'sm' ? 12 : 14} />;
    colorClass = 'badge-failed';
    label = 'Failed';
  } else if (upper === 'ACTIVE') {
    icon = <Sparkles size={size === 'sm' ? 12 : 14} />;
    colorClass = 'badge-processing';
    label = 'Active';
  }

  return (
    <span className={`generation-status-badge ${colorClass} ${size} ${className}`}>
      {icon}
      <span>{label}</span>
    </span>
  );
}
