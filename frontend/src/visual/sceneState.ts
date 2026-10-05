export type AtmosphereMode =
  | 'LOGIN'
  | 'DASHBOARD'
  | 'GENERATION'
  | 'QUEUED'
  | 'PROCESSING'
  | 'RENDERING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CALM';

export interface SceneParameters {
  mode: AtmosphereMode;
  particleSpeed: number;
  flowVelocity: { x: number; y: number; z: number };
  baseColor: string;
  accentColor: string;
  glowIntensity: number;
  pulseIntensity: number;
  distortionIntensity: number;
  cameraFov: number;
  cameraDistance: number;
}

export const ATMOSPHERE_PROFILES: Record<AtmosphereMode, SceneParameters> = {
  LOGIN: {
    mode: 'LOGIN',
    particleSpeed: 0.25,
    flowVelocity: { x: 0.05, y: 0.02, z: 0.03 },
    baseColor: '#6366f1', // Electric indigo
    accentColor: '#8b5cf6', // Soft violet
    glowIntensity: 0.6,
    pulseIntensity: 0.0,
    distortionIntensity: 0.0,
    cameraFov: 45,
    cameraDistance: 130,
  },
  DASHBOARD: {
    mode: 'DASHBOARD',
    particleSpeed: 0.45,
    flowVelocity: { x: 0.12, y: 0.04, z: 0.08 },
    baseColor: '#8b5cf6', // Electric violet
    accentColor: '#06b6d4', // Cyan
    glowIntensity: 0.8,
    pulseIntensity: 0.0,
    distortionIntensity: 0.0,
    cameraFov: 50,
    cameraDistance: 120,
  },
  GENERATION: {
    mode: 'GENERATION',
    particleSpeed: 0.75,
    flowVelocity: { x: 0.25, y: 0.1, z: 0.18 },
    baseColor: '#a855f7', // Creative purple
    accentColor: '#f59e0b', // Amber creative fire
    glowIntensity: 1.1,
    pulseIntensity: 0.1,
    distortionIntensity: 0.0,
    cameraFov: 55,
    cameraDistance: 110,
  },
  QUEUED: {
    mode: 'QUEUED',
    particleSpeed: 0.35,
    flowVelocity: { x: -0.05, y: -0.05, z: -0.05 }, // Particles gather inward
    baseColor: '#3b82f6', // Blueprint blue
    accentColor: '#93c5fd',
    glowIntensity: 0.7,
    pulseIntensity: 0.2,
    distortionIntensity: 0.0,
    cameraFov: 48,
    cameraDistance: 125,
  },
  PROCESSING: {
    mode: 'PROCESSING',
    particleSpeed: 0.9,
    flowVelocity: { x: 0.3, y: 0.15, z: 0.25 },
    baseColor: '#8b5cf6',
    accentColor: '#22d3ee',
    glowIntensity: 1.25,
    pulseIntensity: 0.15,
    distortionIntensity: 0.0,
    cameraFov: 52,
    cameraDistance: 115,
  },
  RENDERING: {
    mode: 'RENDERING',
    particleSpeed: 1.2,
    flowVelocity: { x: 0.45, y: 0.2, z: 0.35 },
    baseColor: '#ec4899', // Hot magenta / render compute
    accentColor: '#38bdf8', // Neon sky
    glowIntensity: 1.4,
    pulseIntensity: 0.25,
    distortionIntensity: 0.0,
    cameraFov: 56,
    cameraDistance: 105,
  },
  COMPLETED: {
    mode: 'COMPLETED',
    particleSpeed: 0.4,
    flowVelocity: { x: 0.08, y: 0.04, z: 0.06 },
    baseColor: '#10b981', // Emerald success
    accentColor: '#34d399',
    glowIntensity: 1.3,
    pulseIntensity: 1.0, // Radiating shockwave
    distortionIntensity: 0.0,
    cameraFov: 50,
    cameraDistance: 118,
  },
  FAILED: {
    mode: 'FAILED',
    particleSpeed: 0.3,
    flowVelocity: { x: 0.04, y: 0.02, z: 0.04 },
    baseColor: '#ef4444', // Crimson warning
    accentColor: '#f87171',
    glowIntensity: 0.9,
    pulseIntensity: 0.0,
    distortionIntensity: 0.8, // Chromatic glitch pulse
    cameraFov: 52,
    cameraDistance: 125,
  },
  CALM: {
    mode: 'CALM',
    particleSpeed: 0.2,
    flowVelocity: { x: 0.03, y: 0.01, z: 0.02 },
    baseColor: '#6b7280',
    accentColor: '#a78bfa',
    glowIntensity: 0.5,
    pulseIntensity: 0.0,
    distortionIntensity: 0.0,
    cameraFov: 46,
    cameraDistance: 135,
  },
};

export class SceneStateStore {
  private currentMode: AtmosphereMode = 'LOGIN';
  private targetParams: SceneParameters = { ...ATMOSPHERE_PROFILES.LOGIN };
  private activeParams: SceneParameters = { ...ATMOSPHERE_PROFILES.LOGIN };
  private reducedMotion = false;
  private listeners = new Set<(params: SceneParameters) => void>();

  public setMode(mode: AtmosphereMode): void {
    if (this.currentMode === mode && this.targetParams.pulseIntensity <= 0.01) return;
    this.currentMode = mode;
    this.targetParams = { ...ATMOSPHERE_PROFILES[mode] };
    this.notify();
  }

  public getMode(): AtmosphereMode {
    return this.currentMode;
  }

  public getActiveParams(): SceneParameters {
    return this.activeParams;
  }

  public getTargetParams(): SceneParameters {
    return this.targetParams;
  }

  public setReducedMotion(enabled: boolean): void {
    this.reducedMotion = enabled;
  }

  public isReducedMotion(): boolean {
    return this.reducedMotion;
  }

  public triggerPulse(): void {
    this.activeParams.pulseIntensity = 1.0;
    this.targetParams.pulseIntensity = 0.0;
  }

  public triggerError(): void {
    this.activeParams.distortionIntensity = 0.8;
    this.targetParams.distortionIntensity = 0.0;
  }

  public subscribe(fn: (params: SceneParameters) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private notify(): void {
    this.listeners.forEach((fn) => fn(this.activeParams));
  }
}

export const sceneState = new SceneStateStore();
