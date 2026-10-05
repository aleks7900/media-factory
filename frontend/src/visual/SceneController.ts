import * as THREE from 'three';
import { MediaFactoryScene } from './MediaFactoryScene';
import { QualityConfig, QUALITY_CONFIGS, detectDefaultQuality } from './quality';
import { sceneState, AtmosphereMode, SceneParameters } from './sceneState';

export interface SceneControllerOptions {
  canvas: HTMLCanvasElement;
  onContextLost?: () => void;
  onContextRestored?: () => void;
  onError?: (err: Error) => void;
}

export class SceneController {
  private canvas: HTMLCanvasElement;
  private renderer: THREE.WebGLRenderer | null = null;
  private sceneInstance: MediaFactoryScene | null = null;
  private config: QualityConfig;

  private lastTime = 0;
  private startTime = 0;
  private animationFrameId: number | null = null;
  private isRunning = false;
  private isTabHidden = false;

  private pointer = { x: 0, y: 0 };
  private targetPointer = { x: 0, y: 0 };

  private onContextLostCb?: () => void;
  private onContextRestoredCb?: () => void;
  private onErrorCb?: (err: Error) => void;

  constructor(options: SceneControllerOptions) {
    this.canvas = options.canvas;
    this.onContextLostCb = options.onContextLost;
    this.onContextRestoredCb = options.onContextRestored;
    this.onErrorCb = options.onError;

    const qualityLevel = detectDefaultQuality();
    this.config = QUALITY_CONFIGS[qualityLevel];

    this.init();
  }

  private init(): void {
    if (this.config.level === 'DISABLED') return;

    try {
      // Check WebGL availability safely
      if (typeof window === 'undefined' || typeof (window as any).WebGLRenderingContext === 'undefined') {
        throw new Error('WebGL not supported in this environment');
      }

      let gl: RenderingContext | null = null;
      try {
        if (typeof this.canvas.getContext === 'function') {
          gl = this.canvas.getContext('webgl2') || this.canvas.getContext('webgl');
        }
      } catch {
        gl = null;
      }

      if (!gl) {
        throw new Error('WebGL context creation failed');
      }

      this.renderer = new THREE.WebGLRenderer({
        canvas: this.canvas,
        alpha: true,
        antialias: this.config.level === 'HIGH',
        powerPreference: 'high-performance',
      });

      const dpr = Math.min(window.devicePixelRatio || 1, this.config.dprCap);
      this.renderer.setPixelRatio(dpr);
      this.renderer.setSize(window.innerWidth, window.innerHeight, false);

      const aspect = window.innerWidth / (window.innerHeight || 1);
      this.sceneInstance = new MediaFactoryScene(this.config, aspect);

      this.setupEventListeners();
      this.start();
    } catch (err) {
      if (this.onErrorCb) {
        this.onErrorCb(err instanceof Error ? err : new Error(String(err)));
      }
    }
  }

  private setupEventListeners(): void {
    window.addEventListener('resize', this.handleResize);
    window.addEventListener('mousemove', this.handleMouseMove, { passive: true });
    document.addEventListener('visibilitychange', this.handleVisibilityChange);

    this.canvas.addEventListener('webglcontextlost', this.handleContextLost, false);
    this.canvas.addEventListener('webglcontextrestored', this.handleContextRestored, false);
  }

  private handleResize = (): void => {
    if (!this.renderer || !this.sceneInstance) return;
    const width = window.innerWidth;
    const height = window.innerHeight;
    this.renderer.setSize(width, height, false);
    this.sceneInstance.setAspect(width / (height || 1));
  };

  private handleMouseMove = (e: MouseEvent): void => {
    if (sceneState.isReducedMotion()) return;
    // Normalize coordinates between -1 and 1
    this.targetPointer.x = (e.clientX / window.innerWidth) * 2 - 1;
    this.targetPointer.y = (e.clientY / window.innerHeight) * 2 - 1;
  };

  private handleVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') {
      this.isTabHidden = true;
      this.stop();
    } else {
      this.isTabHidden = false;
      this.lastTime = performance.now(); // reset delta to prevent huge leap
      this.start();
    }
  };

  private handleContextLost = (e: Event): void => {
    e.preventDefault();
    this.stop();
    if (this.onContextLostCb) this.onContextLostCb();
  };

  private handleContextRestored = (): void => {
    if (this.sceneInstance) {
      this.sceneInstance.dispose();
      this.sceneInstance = null;
    }
    const aspect = window.innerWidth / (window.innerHeight || 1);
    this.sceneInstance = new MediaFactoryScene(this.config, aspect);
    this.start();
    if (this.onContextRestoredCb) this.onContextRestoredCb();
  };

  public setRoute(route: string): void {
    if (route === 'Login') {
      sceneState.setMode('LOGIN');
    } else if (route === 'Dashboard') {
      sceneState.setMode('DASHBOARD');
    } else if (
      route === 'Generation Queue' ||
      route === 'Video Factory' ||
      route === 'Wallpaper Factory' ||
      route === 'Bulk Gemini Video' ||
      route === 'Bulk GPT Image'
    ) {
      sceneState.setMode('GENERATION');
    } else {
      sceneState.setMode('CALM');
    }
  }

  public setGenerationState(status: string | null): void {
    if (!status) return;
    const upper = status.toUpperCase();
    if (upper === 'QUEUED' || upper === 'PENDING') {
      sceneState.setMode('QUEUED');
    } else if (upper === 'PROCESSING' || upper === 'RUNNING') {
      sceneState.setMode('PROCESSING');
    } else if (upper === 'RENDERING' || upper === 'GENERATING') {
      sceneState.setMode('RENDERING');
    } else if (upper === 'COMPLETED' || upper === 'SUCCEEDED' || upper === 'APPROVED') {
      sceneState.setMode('COMPLETED');
      sceneState.triggerPulse();
    } else if (upper === 'FAILED' || upper === 'ERROR' || upper === 'REJECTED') {
      sceneState.setMode('FAILED');
      sceneState.triggerError();
    }
  }

  public setQuality(level: QualityConfig): void {
    this.config = level;
    if (this.sceneInstance) {
      this.sceneInstance.dispose();
      this.sceneInstance = null;
    }
    if (this.renderer) {
      this.renderer.dispose();
      this.renderer = null;
    }
    this.init();
  }

  public start(): void {
    if (this.isRunning || this.isTabHidden || !this.renderer || !this.sceneInstance) return;
    this.isRunning = true;
    this.lastTime = performance.now();
    if (!this.startTime) this.startTime = this.lastTime;
    this.tick();
  }

  public stop(): void {
    this.isRunning = false;
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
  }

  private tick = (): void => {
    if (!this.isRunning || !this.renderer || !this.sceneInstance) return;

    const now = performance.now();
    let delta = (now - this.lastTime) / 1000;
    this.lastTime = now;

    // Clamp delta to 1/15th second to avoid simulation explosion
    if (delta > 0.066) delta = 0.066;

    const time = (now - this.startTime) / 1000;
    const params = sceneState.getActiveParams();
    const targetParams = sceneState.getTargetParams();
    const isReduced = sceneState.isReducedMotion();

    // Smoothly interpolate active parameters toward target parameters
    this.interpolateParameters(params, targetParams, delta);

    // Smoothly interpolate pointer
    this.pointer.x = THREE.MathUtils.lerp(this.pointer.x, this.targetPointer.x, delta * 3.5);
    this.pointer.y = THREE.MathUtils.lerp(this.pointer.y, this.targetPointer.y, delta * 3.5);

    // Update scene objects
    this.sceneInstance.update(delta, params, time, isReduced, this.pointer);

    // Render frame
    this.renderer.render(this.sceneInstance.scene, this.sceneInstance.camera);

    this.animationFrameId = requestAnimationFrame(this.tick);
  };

  private interpolateParameters(active: SceneParameters, target: SceneParameters, delta: number): void {
    const rate = delta * 2.8;
    active.particleSpeed = THREE.MathUtils.lerp(active.particleSpeed, target.particleSpeed, rate);
    active.glowIntensity = THREE.MathUtils.lerp(active.glowIntensity, target.glowIntensity, rate);
    active.cameraDistance = THREE.MathUtils.lerp(active.cameraDistance, target.cameraDistance, rate);
    active.cameraFov = THREE.MathUtils.lerp(active.cameraFov, target.cameraFov, rate);

    active.flowVelocity.x = THREE.MathUtils.lerp(active.flowVelocity.x, target.flowVelocity.x, rate);
    active.flowVelocity.y = THREE.MathUtils.lerp(active.flowVelocity.y, target.flowVelocity.y, rate);
    active.flowVelocity.z = THREE.MathUtils.lerp(active.flowVelocity.z, target.flowVelocity.z, rate);

    active.baseColor = target.baseColor;
    active.accentColor = target.accentColor;

    // Decay transient pulse and distortion
    if (active.pulseIntensity > 0) {
      active.pulseIntensity = Math.max(0, active.pulseIntensity - delta * 0.8);
    }
    if (active.distortionIntensity > 0) {
      active.distortionIntensity = Math.max(0, active.distortionIntensity - delta * 0.9);
    }
  }

  public dispose(): void {
    this.stop();
    window.removeEventListener('resize', this.handleResize);
    window.removeEventListener('mousemove', this.handleMouseMove);
    document.removeEventListener('visibilitychange', this.handleVisibilityChange);

    this.canvas.removeEventListener('webglcontextlost', this.handleContextLost);
    this.canvas.removeEventListener('webglcontextrestored', this.handleContextRestored);

    if (this.sceneInstance) {
      this.sceneInstance.dispose();
      this.sceneInstance = null;
    }
    if (this.renderer) {
      this.renderer.dispose();
      this.renderer = null;
    }
  }
}
