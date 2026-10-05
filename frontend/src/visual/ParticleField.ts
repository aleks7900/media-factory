import * as THREE from 'three';
import { QualityConfig } from './quality';
import { SceneParameters } from './sceneState';

export function createRoundParticleTexture(size = 64): THREE.DataTexture {
  const data = new Uint8Array(size * size * 4);
  const center = size / 2;
  const radius = size / 2;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;
      const dx = (x + 0.5) - center;
      const dy = (y + 0.5) - center;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const normalized = dist / radius;

      if (normalized >= 1.0) {
        data[idx] = 255;
        data[idx + 1] = 255;
        data[idx + 2] = 255;
        data[idx + 3] = 0;
      } else {
        // Smooth cosine ease-out falloff for anti-aliased round particles
        const falloff = Math.pow(Math.cos(normalized * (Math.PI / 2)), 1.8);
        data[idx] = 255;
        data[idx + 1] = 255;
        data[idx + 2] = 255;
        data[idx + 3] = Math.round(falloff * 255);
      }
    }
  }

  const texture = new THREE.DataTexture(
    data,
    size,
    size,
    THREE.RGBAFormat,
    THREE.UnsignedByteType
  );
  texture.generateMipmaps = false;
  texture.minFilter = THREE.LinearFilter;
  texture.magFilter = THREE.LinearFilter;
  texture.needsUpdate = true;
  return texture;
}

export function createGlowingOrbTexture(size = 64): THREE.DataTexture {
  const data = new Uint8Array(size * size * 4);
  const center = size / 2;
  const radius = size / 2;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;
      const dx = (x + 0.5) - center;
      const dy = (y + 0.5) - center;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const normalized = dist / radius;

      if (normalized >= 1.0) {
        data[idx] = 255;
        data[idx + 1] = 255;
        data[idx + 2] = 255;
        data[idx + 3] = 0;
      } else {
        // High-intensity luminous core with soft atmospheric halo
        const core = Math.pow(Math.max(0, 1 - normalized / 0.35), 2);
        const halo = Math.pow(Math.cos(normalized * (Math.PI / 2)), 2.2);
        const intensity = Math.min(1.0, core * 0.7 + halo * 0.55);

        data[idx] = 255;
        data[idx + 1] = 255;
        data[idx + 2] = 255;
        data[idx + 3] = Math.round(intensity * 255);
      }
    }
  }

  const texture = new THREE.DataTexture(
    data,
    size,
    size,
    THREE.RGBAFormat,
    THREE.UnsignedByteType
  );
  texture.generateMipmaps = false;
  texture.minFilter = THREE.LinearFilter;
  texture.magFilter = THREE.LinearFilter;
  texture.needsUpdate = true;
  return texture;
}

export class ParticleField {
  public group = new THREE.Group();

  private particleTexture: THREE.DataTexture;
  private nodeTexture: THREE.DataTexture;

  private particlesGeometry: THREE.BufferGeometry;
  private particlesMaterial: THREE.PointsMaterial;
  private particlesMesh: THREE.Points;

  private nodesGeometry: THREE.BufferGeometry;
  private nodesMaterial: THREE.PointsMaterial;
  private nodesMesh: THREE.Points;

  private linesGeometry: THREE.BufferGeometry;
  private linesMaterial: THREE.LineBasicMaterial;
  private linesMesh: THREE.LineSegments;

  private particlePositions: Float32Array;
  private particleVelocities: Float32Array;
  private particleOriginals: Float32Array;
  private particleColors: Float32Array;

  private nodePositions: Float32Array;
  private nodeVelocities: Float32Array;

  private config: QualityConfig;
  private pulseWave = 0;
  private pulseActive = false;

  constructor(config: QualityConfig) {
    this.config = config;

    // 1. Build Particles
    const count = config.maxParticles;
    this.particlePositions = new Float32Array(count * 3);
    this.particleVelocities = new Float32Array(count * 3);
    this.particleOriginals = new Float32Array(count * 3);
    this.particleColors = new Float32Array(count * 3);

    const radius = 110;
    const baseColor = new THREE.Color('#8b5cf6');
    const accentColor = new THREE.Color('#06b6d4');

    for (let i = 0; i < count; i++) {
      const i3 = i * 3;
      // Distribute in a spherical/ellipsoid studio volume
      const u = Math.random();
      const v = Math.random();
      const theta = u * 2.0 * Math.PI;
      const phi = Math.acos(2.0 * v - 1.0);
      const r = Math.cbrt(Math.random()) * radius;

      const sinPhi = Math.sin(phi);
      const x = r * sinPhi * Math.cos(theta);
      const y = (r * sinPhi * Math.sin(theta)) * 0.6; // Slightly flattened
      const z = r * Math.cos(phi) * 0.8;

      this.particlePositions[i3] = x;
      this.particlePositions[i3 + 1] = y;
      this.particlePositions[i3 + 2] = z;

      this.particleOriginals[i3] = x;
      this.particleOriginals[i3 + 1] = y;
      this.particleOriginals[i3 + 2] = z;

      this.particleVelocities[i3] = (Math.random() - 0.5) * 0.2;
      this.particleVelocities[i3 + 1] = (Math.random() - 0.5) * 0.2;
      this.particleVelocities[i3 + 2] = (Math.random() - 0.5) * 0.2;

      // Color mix
      const mix = Math.random();
      const col = baseColor.clone().lerp(accentColor, mix);
      this.particleColors[i3] = col.r;
      this.particleColors[i3 + 1] = col.g;
      this.particleColors[i3 + 2] = col.b;
    }

    this.particlesGeometry = new THREE.BufferGeometry();
    this.particlesGeometry.setAttribute('position', new THREE.BufferAttribute(this.particlePositions, 3));
    this.particlesGeometry.setAttribute('color', new THREE.BufferAttribute(this.particleColors, 3));

    this.particleTexture = createRoundParticleTexture(64);

    this.particlesMaterial = new THREE.PointsMaterial({
      size: 3.6,
      vertexColors: true,
      transparent: true,
      opacity: 0.72,
      map: this.particleTexture,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });

    this.particlesMesh = new THREE.Points(this.particlesGeometry, this.particlesMaterial);
    this.group.add(this.particlesMesh);

    // 2. Build Neural Nodes
    const nodeCount = config.maxNodes;
    this.nodePositions = new Float32Array(nodeCount * 3);
    this.nodeVelocities = new Float32Array(nodeCount * 3);

    for (let i = 0; i < nodeCount; i++) {
      const i3 = i * 3;
      const x = (Math.random() - 0.5) * 140;
      const y = (Math.random() - 0.5) * 80;
      const z = (Math.random() - 0.5) * 90;

      this.nodePositions[i3] = x;
      this.nodePositions[i3 + 1] = y;
      this.nodePositions[i3 + 2] = z;

      this.nodeVelocities[i3] = (Math.random() - 0.5) * 0.15;
      this.nodeVelocities[i3 + 1] = (Math.random() - 0.5) * 0.15;
      this.nodeVelocities[i3 + 2] = (Math.random() - 0.5) * 0.15;
    }

    this.nodesGeometry = new THREE.BufferGeometry();
    this.nodesGeometry.setAttribute('position', new THREE.BufferAttribute(this.nodePositions, 3));

    this.nodeTexture = createGlowingOrbTexture(64);

    this.nodesMaterial = new THREE.PointsMaterial({
      size: 7.2,
      color: new THREE.Color('#c4b5fd'),
      transparent: true,
      opacity: 0.9,
      map: this.nodeTexture,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });

    this.nodesMesh = new THREE.Points(this.nodesGeometry, this.nodesMaterial);
    this.group.add(this.nodesMesh);

    // 3. Build Connecting Network Lines
    const maxLineSegments = config.maxConnections * 2;
    const linePositions = new Float32Array(maxLineSegments * 3);

    this.linesGeometry = new THREE.BufferGeometry();
    this.linesGeometry.setAttribute('position', new THREE.BufferAttribute(linePositions, 3));

    this.linesMaterial = new THREE.LineBasicMaterial({
      color: new THREE.Color('#7c3aed'),
      transparent: true,
      opacity: 0.28,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });

    this.linesMesh = new THREE.LineSegments(this.linesGeometry, this.linesMaterial);
    this.group.add(this.linesMesh);
  }

  public update(delta: number, params: SceneParameters, time: number, isReducedMotion: boolean): void {
    if (this.config.level === 'DISABLED') return;

    const speedFactor = isReducedMotion ? 0.05 : params.particleSpeed;
    const count = this.config.maxParticles;
    const positions = this.particlesGeometry.attributes.position.array as Float32Array;

    // Pulse effect progression
    if (params.pulseIntensity > 0.01) {
      this.pulseWave += delta * 60;
      this.pulseActive = true;
      if (this.pulseWave > 180) {
        this.pulseWave = 0;
        this.pulseActive = false;
      }
    } else {
      this.pulseWave = 0;
      this.pulseActive = false;
    }

    // Update Particles
    for (let i = 0; i < count; i++) {
      const i3 = i * 3;

      if (!isReducedMotion) {
        // Procedural vector field flow
        const flowX = Math.sin(time * 0.4 + positions[i3 + 1] * 0.03) * params.flowVelocity.x;
        const flowY = Math.cos(time * 0.3 + positions[i3] * 0.03) * params.flowVelocity.y;
        const flowZ = Math.sin(time * 0.5 + positions[i3 + 2] * 0.03) * params.flowVelocity.z;

        positions[i3] += (this.particleVelocities[i3] * speedFactor) + (flowX * speedFactor);
        positions[i3 + 1] += (this.particleVelocities[i3 + 1] * speedFactor) + (flowY * speedFactor);
        positions[i3 + 2] += (this.particleVelocities[i3 + 2] * speedFactor) + (flowZ * speedFactor);

        // Gentle boundary containment
        const distSq = positions[i3] * positions[i3] + positions[i3 + 1] * positions[i3 + 1] + positions[i3 + 2] * positions[i3 + 2];
        if (distSq > 16000) {
          positions[i3] *= 0.98;
          positions[i3 + 1] *= 0.98;
          positions[i3 + 2] *= 0.98;
          this.particleVelocities[i3] *= -1;
          this.particleVelocities[i3 + 1] *= -1;
        }

        // Pulse wave perturbation
        if (this.pulseActive) {
          const d = Math.sqrt(distSq);
          const diff = Math.abs(d - this.pulseWave);
          if (diff < 15) {
            const push = (15 - diff) * 0.15;
            positions[i3] += (positions[i3] / (d || 1)) * push;
            positions[i3 + 1] += (positions[i3 + 1] / (d || 1)) * push;
            positions[i3 + 2] += (positions[i3 + 2] / (d || 1)) * push;
          }
        }

        // Error distortion
        if (params.distortionIntensity > 0.05) {
          positions[i3] += (Math.random() - 0.5) * params.distortionIntensity * 1.5;
          positions[i3 + 1] += (Math.random() - 0.5) * params.distortionIntensity * 1.5;
        }
      }
    }
    this.particlesGeometry.attributes.position.needsUpdate = true;

    // Update Nodes & Connection Lines
    const nodeCount = this.config.maxNodes;
    const nPos = this.nodesGeometry.attributes.position.array as Float32Array;

    if (!isReducedMotion) {
      for (let i = 0; i < nodeCount; i++) {
        const i3 = i * 3;
        nPos[i3] += this.nodeVelocities[i3] * speedFactor * 0.8;
        nPos[i3 + 1] += this.nodeVelocities[i3 + 1] * speedFactor * 0.8;
        nPos[i3 + 2] += this.nodeVelocities[i3 + 2] * speedFactor * 0.8;

        if (Math.abs(nPos[i3]) > 75) this.nodeVelocities[i3] *= -1;
        if (Math.abs(nPos[i3 + 1]) > 45) this.nodeVelocities[i3 + 1] *= -1;
        if (Math.abs(nPos[i3 + 2]) > 50) this.nodeVelocities[i3 + 2] *= -1;
      }
      this.nodesGeometry.attributes.position.needsUpdate = true;
    }

    // Dynamic Connections
    const linePos = this.linesGeometry.attributes.position.array as Float32Array;
    let lineIdx = 0;
    const maxConn = this.config.maxConnections;
    const maxDistance = 38;

    for (let i = 0; i < nodeCount && lineIdx < maxConn * 6; i++) {
      const i3 = i * 3;
      const x1 = nPos[i3];
      const y1 = nPos[i3 + 1];
      const z1 = nPos[i3 + 2];

      for (let j = i + 1; j < nodeCount && lineIdx < maxConn * 6; j++) {
        const j3 = j * 3;
        const dx = x1 - nPos[j3];
        const dy = y1 - nPos[j3 + 1];
        const dz = z1 - nPos[j3 + 2];
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < maxDistance) {
          linePos[lineIdx++] = x1;
          linePos[lineIdx++] = y1;
          linePos[lineIdx++] = z1;
          linePos[lineIdx++] = nPos[j3];
          linePos[lineIdx++] = nPos[j3 + 1];
          linePos[lineIdx++] = nPos[j3 + 2];
        }
      }
    }

    // Clear unused line vertices
    for (let i = lineIdx; i < linePos.length; i++) {
      linePos[i] = 0;
    }
    this.linesGeometry.attributes.position.needsUpdate = true;
    this.linesGeometry.setDrawRange(0, lineIdx / 3);

    // Color interpolation
    const targetBase = new THREE.Color(params.baseColor);
    const targetAccent = new THREE.Color(params.accentColor);
    this.nodesMaterial.color.lerp(targetAccent, delta * 3);
    this.linesMaterial.color.lerp(targetBase, delta * 3);
    this.particlesMaterial.opacity = THREE.MathUtils.lerp(
      this.particlesMaterial.opacity,
      Math.min(0.85, 0.5 * params.glowIntensity),
      delta * 2
    );
  }

  public dispose(): void {
    if (this.particleTexture) {
      this.particleTexture.dispose();
    }
    if (this.nodeTexture) {
      this.nodeTexture.dispose();
    }
    this.particlesGeometry.dispose();
    this.particlesMaterial.dispose();
    this.nodesGeometry.dispose();
    this.nodesMaterial.dispose();
    this.linesGeometry.dispose();
    this.linesMaterial.dispose();
  }
}
