export type QualityLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'DISABLED';

export interface QualityConfig {
  level: QualityLevel;
  maxParticles: number;
  maxNodes: number;
  maxConnections: number;
  dprCap: number;
  enableBloom: boolean;
  enableShadows: boolean;
}

const STORAGE_KEY = 'media_factory_webgl_quality';

export const QUALITY_CONFIGS: Record<QualityLevel, QualityConfig> = {
  HIGH: {
    level: 'HIGH',
    maxParticles: 1200,
    maxNodes: 60,
    maxConnections: 120,
    dprCap: 2,
    enableBloom: true,
    enableShadows: true,
  },
  MEDIUM: {
    level: 'MEDIUM',
    maxParticles: 600,
    maxNodes: 35,
    maxConnections: 60,
    dprCap: 1.5,
    enableBloom: false,
    enableShadows: false,
  },
  LOW: {
    level: 'LOW',
    maxParticles: 250,
    maxNodes: 20,
    maxConnections: 30,
    dprCap: 1.0,
    enableBloom: false,
    enableShadows: false,
  },
  DISABLED: {
    level: 'DISABLED',
    maxParticles: 0,
    maxNodes: 0,
    maxConnections: 0,
    dprCap: 1.0,
    enableBloom: false,
    enableShadows: false,
  },
};

export function detectDefaultQuality(): QualityLevel {
  if (typeof window === 'undefined') return 'LOW';

  // Check user saved preference
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY) as QualityLevel | null;
    if (saved && saved in QUALITY_CONFIGS) {
      return saved;
    }
  } catch {
    // ignore localStorage access issues
  }

  // Check hardware heuristics
  const isMobile = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) || window.innerWidth < 768;
  const cores = navigator.hardwareConcurrency || 4;

  if (isMobile || cores <= 2) {
    return 'LOW';
  }

  if (cores <= 4 || window.innerWidth < 1280) {
    return 'MEDIUM';
  }

  return 'HIGH';
}

export function saveQualityPreference(level: QualityLevel): void {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(STORAGE_KEY, level);
    }
  } catch {
    // ignore
  }
}
