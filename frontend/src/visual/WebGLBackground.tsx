import { useEffect, useRef, useState } from 'react';
import { SceneController } from './SceneController';
import './visual.css';

export interface WebGLBackgroundProps {
  route?: string;
  generationState?: string | null;
}

export function WebGLBackground({ route = 'Dashboard', generationState = null }: WebGLBackgroundProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const controllerRef = useRef<SceneController | null>(null);
  const [hasWebGLError, setHasWebGLError] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    try {
      const controller = new SceneController({
        canvas,
        onContextLost: () => {
          setHasWebGLError(true);
        },
        onContextRestored: () => {
          setHasWebGLError(false);
        },
        onError: () => {
          setHasWebGLError(true);
        },
      });

      controllerRef.current = controller;
    } catch {
      setHasWebGLError(true);
    }

    return () => {
      if (controllerRef.current) {
        controllerRef.current.dispose();
        controllerRef.current = null;
      }
    };
  }, []);

  // Update atmosphere on route change
  useEffect(() => {
    if (controllerRef.current) {
      controllerRef.current.setRoute(route);
    }
  }, [route]);

  // Update atmosphere on generation state change
  useEffect(() => {
    if (controllerRef.current && generationState) {
      controllerRef.current.setGenerationState(generationState);
    }
  }, [generationState]);

  return (
    <>
      <canvas
        ref={canvasRef}
        className="webgl-background-canvas"
        aria-hidden="true"
        role="presentation"
        tabIndex={-1}
        style={{ display: hasWebGLError ? 'none' : 'block' }}
      />
      {hasWebGLError && (
        <div
          className="webgl-fallback-gradient"
          aria-hidden="true"
          role="presentation"
        />
      )}
    </>
  );
}
