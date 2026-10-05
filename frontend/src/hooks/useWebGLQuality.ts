import { useState, useCallback } from 'react';
import { QualityLevel, QualityConfig, QUALITY_CONFIGS, detectDefaultQuality, saveQualityPreference } from '../visual/quality';

export function useWebGLQuality() {
  const [level, setLevelState] = useState<QualityLevel>(() => detectDefaultQuality());

  const setQuality = useCallback((newLevel: QualityLevel) => {
    setLevelState(newLevel);
    saveQualityPreference(newLevel);
  }, []);

  const config: QualityConfig = QUALITY_CONFIGS[level];

  return {
    level,
    config,
    setQuality,
    availableLevels: Object.keys(QUALITY_CONFIGS) as QualityLevel[],
  };
}
