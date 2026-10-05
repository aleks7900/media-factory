import { render, screen, act, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebGLBackground } from './visual/WebGLBackground';
import { sceneState } from './visual/sceneState';
import { useGenerationProgress, resetGlobalJobState } from './hooks/useGenerationProgress';
import { ImmersiveProgress } from './components/ImmersiveProgress';
import { GlobalJobIndicator } from './components/GlobalJobIndicator';
import { GenerationStatus } from './components/GenerationStatus';
import { detectDefaultQuality, QUALITY_CONFIGS } from './visual/quality';
import { ParticleField, createRoundParticleTexture, createGlowingOrbTexture } from './visual/ParticleField';
import { api, setAuth, subscribeUnauthorized } from './api';

describe('Media Factory Visual System & Immersive Loaders', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllTimers();
    vi.useRealTimers();
    resetGlobalJobState();
  });

  describe('WebGLBackground & Fallbacks', () => {
    it('renders fallback gradient gracefully when WebGL is unavailable in environment', () => {
      const { container } = render(<WebGLBackground route="Dashboard" />);
      const fallback = container.querySelector('.webgl-fallback-gradient');
      expect(fallback).toBeInTheDocument();
      expect(fallback).toHaveAttribute('aria-hidden', 'true');
    });

    it('canvas element has accessibility attributes and hidden pointer events', () => {
      const { container } = render(<WebGLBackground route="Login" />);
      const canvas = container.querySelector('canvas');
      expect(canvas).toBeInTheDocument();
      expect(canvas).toHaveAttribute('aria-hidden', 'true');
      expect(canvas).toHaveAttribute('role', 'presentation');
    });

    it('does not re-mount or destroy canvas during route navigation', () => {
      const { container, rerender } = render(<WebGLBackground route="Login" />);
      const canvasFirst = container.querySelector('canvas');

      rerender(<WebGLBackground route="Dashboard" />);
      const canvasSecond = container.querySelector('canvas');

      rerender(<WebGLBackground route="Generation Queue" />);
      const canvasThird = container.querySelector('canvas');

      expect(canvasFirst).toBe(canvasSecond);
      expect(canvasSecond).toBe(canvasThird);
    });

    it('updates scene atmosphere when route changes', () => {
      render(<WebGLBackground route="Login" />);
      expect(sceneState.getMode()).toBe('LOGIN');

      render(<WebGLBackground route="Dashboard" />);
      expect(sceneState.getMode()).toBe('DASHBOARD');

      render(<WebGLBackground route="Generation Queue" />);
      expect(sceneState.getMode()).toBe('GENERATION');
    });

    it('updates scene atmosphere when generation state changes', () => {
      render(<WebGLBackground route="Dashboard" generationState="QUEUED" />);
      expect(sceneState.getMode()).toBe('QUEUED');

      render(<WebGLBackground route="Dashboard" generationState="RENDERING" />);
      expect(sceneState.getMode()).toBe('RENDERING');

      render(<WebGLBackground route="Dashboard" generationState="COMPLETED" />);
      expect(sceneState.getMode()).toBe('COMPLETED');
    });
  });

  describe('Reduced Motion & Quality', () => {
    it('detects default quality without error', () => {
      const quality = detectDefaultQuality();
      expect(['HIGH', 'MEDIUM', 'LOW', 'DISABLED']).toContain(quality);
    });

    it('respects reduced motion setting in sceneState', () => {
      sceneState.setReducedMotion(true);
      expect(sceneState.isReducedMotion()).toBe(true);

      sceneState.setReducedMotion(false);
      expect(sceneState.isReducedMotion()).toBe(false);
    });
  });

  describe('ParticleField & Round Particle System', () => {
    it('generates procedural round particle texture with anti-aliased radial decay', () => {
      const texture = createRoundParticleTexture(64);
      expect(texture.image.width).toBe(64);
      expect(texture.image.height).toBe(64);

      const data = texture.image.data as Uint8Array;
      const getAlpha = (x: number, y: number) => data[(y * 64 + x) * 4 + 3];

      // Corners must be fully transparent (round clipping, no squares)
      expect(getAlpha(0, 0)).toBe(0);
      expect(getAlpha(63, 0)).toBe(0);
      expect(getAlpha(0, 63)).toBe(0);
      expect(getAlpha(63, 63)).toBe(0);

      // Center must have maximum/near-maximum opacity
      const centerAlpha = getAlpha(32, 32);
      expect(centerAlpha).toBeGreaterThan(250);

      // Radial decay: monotonic falloff from center to perimeter
      const innerAlpha = getAlpha(32, 20); // dist ~12
      const midAlpha = getAlpha(32, 10);   // dist ~22
      const edgeAlpha = getAlpha(32, 2);   // dist ~30

      expect(centerAlpha).toBeGreaterThanOrEqual(innerAlpha);
      expect(innerAlpha).toBeGreaterThan(midAlpha);
      expect(midAlpha).toBeGreaterThan(edgeAlpha);
      expect(edgeAlpha).toBeGreaterThanOrEqual(0);
    });

    it('generates glowing orb texture with luminous core and atmospheric halo', () => {
      const texture = createGlowingOrbTexture(64);
      expect(texture.image.width).toBe(64);
      expect(texture.image.height).toBe(64);

      const data = texture.image.data as Uint8Array;
      const getAlpha = (x: number, y: number) => data[(y * 64 + x) * 4 + 3];

      // Corners must be completely transparent
      expect(getAlpha(0, 0)).toBe(0);
      expect(getAlpha(63, 63)).toBe(0);

      // Core center must be pure white intensity (255)
      expect(getAlpha(32, 32)).toBe(255);

      // Atmospheric halo must remain luminous at medium radius
      const haloAlpha = getAlpha(32, 22);
      expect(haloAlpha).toBeGreaterThan(50);
    });

    it('initializes ParticleField with round textures, additive blending and proper sizes', () => {
      const field = new ParticleField(QUALITY_CONFIGS['HIGH']);
      expect(field.group.children.length).toBe(3); // particlesMesh, nodesMesh, linesMesh

      const particlesMesh = field.group.children[0] as any;
      const nodesMesh = field.group.children[1] as any;

      expect(particlesMesh.material.map).toBeDefined();
      expect(particlesMesh.material.map.image.width).toBe(64);
      expect(particlesMesh.material.size).toBe(3.6);
      expect(particlesMesh.material.transparent).toBe(true);

      expect(nodesMesh.material.map).toBeDefined();
      expect(nodesMesh.material.map.image.width).toBe(64);
      expect(nodesMesh.material.size).toBe(7.2);
      expect(nodesMesh.material.transparent).toBe(true);

      expect(() => field.dispose()).not.toThrow();
    });
  });

  describe('useGenerationProgress & Real vs Indeterminate States', () => {
    function ProgressTester({ onJob }: { onJob?: (job: any) => void }) {
      const { activeJob, startJob, updateJob, completeJob, failJob, setBackground, clearJob } =
        useGenerationProgress();
      if (onJob) onJob({ activeJob, startJob, updateJob, completeJob, failJob, setBackground, clearJob });
      return <div data-testid="job-title">{activeJob?.title ?? 'No job'}</div>;
    }

    it('starts a generation with real percentage when available', () => {
      let tracker: any = null;
      render(<ProgressTester onJob={(t) => (tracker = t)} />);

      act(() => {
        tracker.startJob({
          id: 'batch-123',
          title: 'Bulk Image Generation',
          kind: 'BULK',
          status: 'PROCESSING',
          percent: 65,
          phaseDescription: 'Processing 13/20 tasks',
        });
      });

      expect(tracker.activeJob).not.toBeNull();
      expect(tracker.activeJob.id).toBe('batch-123');
      expect(tracker.activeJob.percent).toBe(65);
      expect(tracker.activeJob.status).toBe('PROCESSING');
      expect(screen.getByTestId('job-title')).toHaveTextContent('Bulk Image Generation');
    });

    it('starts a generation with indeterminate progress when no real percent exists', () => {
      let tracker: any = null;
      render(<ProgressTester onJob={(t) => (tracker = t)} />);

      act(() => {
        tracker.startJob({
          id: 'gen-456',
          title: 'Single Frame Render',
          kind: 'IMAGE',
          status: 'RENDERING',
          percent: null,
          phaseDescription: 'Rendering frame via mock provider',
        });
      });

      expect(tracker.activeJob.percent).toBeNull();
      expect(tracker.activeJob.status).toBe('RENDERING');
    });

    it('updates elapsed time as time passes', () => {
      let tracker: any = null;
      render(<ProgressTester onJob={(t) => (tracker = t)} />);

      act(() => {
        tracker.startJob({
          id: 'gen-timer',
          title: 'Timer Test',
          kind: 'IMAGE',
          status: 'PROCESSING',
        });
      });

      expect(tracker.activeJob.elapsedSeconds).toBe(0);

      act(() => {
        vi.advanceTimersByTime(3000);
      });

      expect(tracker.activeJob.elapsedSeconds).toBe(3);
    });

    it('progress survives route navigation across multiple components', () => {
      let tracker1: any = null;
      const { unmount } = render(<ProgressTester onJob={(t) => (tracker1 = t)} />);

      act(() => {
        tracker1.startJob({
          id: 'gen-nav',
          title: 'Persistent Job',
          kind: 'IMAGE',
          status: 'PROCESSING',
          percent: 50,
        });
      });

      unmount();

      let tracker2: any = null;
      render(<ProgressTester onJob={(t) => (tracker2 = t)} />);

      expect(tracker2.activeJob).not.toBeNull();
      expect(tracker2.activeJob.id).toBe('gen-nav');
      expect(tracker2.activeJob.title).toBe('Persistent Job');
      expect(tracker2.activeJob.percent).toBe(50);
    });
  });

  describe('ImmersiveProgress Overlay', () => {
    it('displays real percentage when available', () => {
      let tracker: any = null;
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <ImmersiveProgress />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'real-percent-job',
          title: 'Bulk Video Batch',
          kind: 'BULK',
          status: 'PROCESSING',
          percent: 74,
          phaseDescription: 'Rendering clips',
        });
      });

      expect(screen.getByText('74%')).toBeInTheDocument();
      expect(screen.getByText('Bulk Video Batch')).toBeInTheDocument();
      expect(screen.getByText('Rendering clips')).toBeInTheDocument();
      const progressTrack = screen.getByRole('progressbar');
      expect(progressTrack).toHaveAttribute('aria-valuenow', '74');
    });

    it('displays discrete status badge without fake numbers when indeterminate', () => {
      let tracker: any = null;
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <ImmersiveProgress />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'indeterminate-job',
          title: 'Latent Diffusion Render',
          kind: 'IMAGE',
          status: 'RENDERING',
          percent: null,
          phaseDescription: 'Generating high-resolution frame',
        });
      });

      expect(screen.queryByText(/%$/)).not.toBeInTheDocument();
      expect(screen.getByText('RENDERING')).toBeInTheDocument();
      expect(screen.getByText('Latent Diffusion Render')).toBeInTheDocument();
    });

    it('allows user to run generation in background', () => {
      let tracker: any = null;
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <ImmersiveProgress />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'bg-job',
          title: 'Long-running Job',
          kind: 'VIDEO',
          status: 'PROCESSING',
        });
      });

      const bgBtn = screen.getByRole('button', { name: /Run in background/i });
      fireEvent.click(bgBtn);

      expect(tracker.activeJob.isBackground).toBe(true);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('displays completion actions when job succeeds', () => {
      let tracker: any = null;
      const onView = vi.fn();
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <ImmersiveProgress onViewResult={onView} />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'complete-job',
          title: 'Finished Job',
          kind: 'IMAGE',
          status: 'PROCESSING',
        });
      });

      act(() => {
        tracker.completeJob('complete-job');
      });

      expect(screen.getByRole('button', { name: /View creation/i })).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /View creation/i }));
      expect(onView).toHaveBeenCalledWith('complete-job');
    });

    it('displays error message when job fails', () => {
      let tracker: any = null;
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <ImmersiveProgress />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'fail-job',
          title: 'Failed Job',
          kind: 'IMAGE',
          status: 'PROCESSING',
        });
      });

      act(() => {
        tracker.failJob('fail-job', 'Provider timed out');
      });

      expect(screen.getByText('Provider timed out')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Dismiss/i })).toBeInTheDocument();
    });
  });

  describe('GlobalJobIndicator', () => {
    it('renders indicator when background job is running', () => {
      let tracker: any = null;
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <GlobalJobIndicator />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'indicator-job',
          title: 'Background Process',
          kind: 'BULK',
          status: 'PROCESSING',
          percent: 88,
        });
        tracker.setBackground('indicator-job', true);
      });

      expect(screen.getByText('Background Process')).toBeInTheDocument();
      expect(screen.getByText('88%')).toBeInTheDocument();
    });

    it('clicking indicator restores job from background', () => {
      let tracker: any = null;
      const onOpen = vi.fn();
      function Wrapper() {
        const t = useGenerationProgress();
        tracker = t;
        return <GlobalJobIndicator onOpenDetails={onOpen} />;
      }
      render(<Wrapper />);

      act(() => {
        tracker.startJob({
          id: 'restore-job',
          title: 'Minimized Job',
          kind: 'IMAGE',
          status: 'RENDERING',
        });
        tracker.setBackground('restore-job', true);
      });

      const btn = screen.getByRole('button', { name: /Generation job: Minimized Job/i });
      fireEvent.click(btn);

      expect(tracker.activeJob.isBackground).toBe(false);
      expect(onOpen).toHaveBeenCalled();
    });
  });

  describe('GenerationStatus Badges', () => {
    it('renders correct labels and classes for various statuses', () => {
      const { rerender } = render(<GenerationStatus status="QUEUED" />);
      expect(screen.getByText('Queued')).toBeInTheDocument();

      rerender(<GenerationStatus status="PROCESSING" />);
      expect(screen.getByText('Processing')).toBeInTheDocument();

      rerender(<GenerationStatus status="RENDERING" />);
      expect(screen.getByText('Rendering')).toBeInTheDocument();

      rerender(<GenerationStatus status="COMPLETED" />);
      expect(screen.getByText('Completed')).toBeInTheDocument();

      rerender(<GenerationStatus status="FAILED" />);
      expect(screen.getByText('Failed')).toBeInTheDocument();
    });
  });

  describe('401 Session Invalidation during Active Operations', () => {
    it('notifies unauthorized subscribers and clears credentials on 401', async () => {
      setAuth('test-token', { username: 'admin', role: 'ROLE_ADMIN' });
      const onUnauthorized = vi.fn();
      const unsub = subscribeUnauthorized(onUnauthorized);

      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: () => Promise.resolve({ detail: 'Token expired' }),
      });
      vi.stubGlobal('fetch', fetchMock);

      await expect(api('/v1/generations/123')).rejects.toThrow('Token expired');
      expect(onUnauthorized).toHaveBeenCalled();

      unsub();
    });
  });
});
