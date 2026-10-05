import { useEffect, useState } from 'react';
import { sceneState } from '../visual/sceneState';

export function useReducedMotion(): boolean {
  const [reducedMotion, setReducedMotion] = useState<boolean>(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return false;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;

    const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = (e: MediaQueryListEvent | MediaQueryList) => {
      setReducedMotion(e.matches);
      sceneState.setReducedMotion(e.matches);
    };

    update(mediaQuery);

    try {
      mediaQuery.addEventListener('change', update);
      return () => mediaQuery.removeEventListener('change', update);
    } catch {
      // Fallback for older browsers
      mediaQuery.addListener(update);
      return () => mediaQuery.removeListener(update);
    }
  }, []);

  return reducedMotion;
}
