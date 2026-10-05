import { useState, useEffect, useCallback } from 'react';

export type JobKind = 'IMAGE' | 'VIDEO' | 'BULK' | 'WALLPAPER' | 'PROCESSING';

export interface ActiveJob {
  id: string;
  title: string;
  kind: JobKind;
  status: string;
  percent: number | null; // real percentage when available (0-100), null for indeterminate
  phaseDescription: string;
  startedAt: number;
  elapsedSeconds: number;
  isBackground: boolean;
  error?: string;
}

// Global in-memory job state so progress survives route navigation
let currentJobState: ActiveJob | null = null;
const listeners = new Set<(job: ActiveJob | null) => void>();

function notify(job: ActiveJob | null) {
  currentJobState = job;
  listeners.forEach((fn) => fn(currentJobState));
}

export function resetGlobalJobState(): void {
  notify(null);
}

export function useGenerationProgress() {
  const [activeJob, setActiveJob] = useState<ActiveJob | null>(currentJobState);

  useEffect(() => {
    const handler = (job: ActiveJob | null) => setActiveJob(job);
    listeners.add(handler);
    return () => {
      listeners.delete(handler);
    };
  }, []);

  // Timer updating elapsedSeconds every 1s when a job is active
  useEffect(() => {
    if (!activeJob || activeJob.status === 'COMPLETED' || activeJob.status === 'FAILED') return;

    const interval = setInterval(() => {
      if (currentJobState) {
        const elapsed = Math.floor((Date.now() - currentJobState.startedAt) / 1000);
        notify({ ...currentJobState, elapsedSeconds: elapsed });
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [activeJob?.id, activeJob?.status]);

  const startJob = useCallback(
    (params: {
      id: string;
      title: string;
      kind: JobKind;
      status?: string;
      percent?: number | null;
      phaseDescription?: string;
    }) => {
      const now = Date.now();
      const newJob: ActiveJob = {
        id: params.id,
        title: params.title,
        kind: params.kind,
        status: params.status || 'QUEUED',
        percent: params.percent ?? null,
        phaseDescription: params.phaseDescription || 'Queued in generation pipeline',
        startedAt: now,
        elapsedSeconds: 0,
        isBackground: false,
      };
      notify(newJob);
      return newJob;
    },
    []
  );

  const updateJob = useCallback((id: string, updates: Partial<ActiveJob>) => {
    if (currentJobState && currentJobState.id === id) {
      notify({ ...currentJobState, ...updates });
    }
  }, []);

  const completeJob = useCallback((id: string) => {
    if (currentJobState && currentJobState.id === id) {
      notify({
        ...currentJobState,
        status: 'COMPLETED',
        phaseDescription: 'Generation completed successfully',
        percent: currentJobState.percent !== null ? 100 : null,
      });
    }
  }, []);

  const failJob = useCallback((id: string, error?: string) => {
    if (currentJobState && currentJobState.id === id) {
      notify({
        ...currentJobState,
        status: 'FAILED',
        phaseDescription: error || 'Generation attempt failed',
        error,
      });
    }
  }, []);

  const setBackground = useCallback((id: string, isBackground: boolean) => {
    if (currentJobState && currentJobState.id === id) {
      notify({ ...currentJobState, isBackground });
    }
  }, []);

  const clearJob = useCallback(() => {
    notify(null);
  }, []);

  return {
    activeJob,
    startJob,
    updateJob,
    completeJob,
    failJob,
    setBackground,
    clearJob,
  };
}
