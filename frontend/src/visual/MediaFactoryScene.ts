import * as THREE from 'three';
import { ParticleField } from './ParticleField';
import { QualityConfig } from './quality';
import { SceneParameters } from './sceneState';

export class MediaFactoryScene {
  public scene: THREE.Scene;
  public camera: THREE.PerspectiveCamera;
  public particleField: ParticleField;

  private ambientLight: THREE.AmbientLight;
  private primaryLight: THREE.PointLight;
  private secondaryLight: THREE.PointLight;

  private coreRingGeometry: THREE.TorusGeometry;
  private coreRingMaterial: THREE.MeshBasicMaterial;
  private coreRingMesh: THREE.Mesh;

  private targetCameraPos = new THREE.Vector3(0, 0, 120);
  private currentCameraPos = new THREE.Vector3(0, 0, 120);

  constructor(config: QualityConfig, aspect: number) {
    this.scene = new THREE.Scene();

    // Subtle dark atmospheric fog for deep cinematic space
    this.scene.fog = new THREE.FogExp2('#07070b', 0.0055);

    // Perspective camera
    this.camera = new THREE.PerspectiveCamera(50, aspect, 0.1, 1000);
    this.camera.position.set(0, 0, 120);

    // Ambient light
    this.ambientLight = new THREE.AmbientLight('#2e1065', 0.8);
    this.scene.add(this.ambientLight);

    // Point lights for volumetric glow simulation
    this.primaryLight = new THREE.PointLight('#8b5cf6', 2.0, 300);
    this.primaryLight.position.set(30, 20, 50);
    this.scene.add(this.primaryLight);

    this.secondaryLight = new THREE.PointLight('#06b6d4', 1.8, 300);
    this.secondaryLight.position.set(-30, -20, 40);
    this.scene.add(this.secondaryLight);

    // Subtle abstract latent-space core ring
    this.coreRingGeometry = new THREE.TorusGeometry(32, 0.4, 16, 100);
    this.coreRingMaterial = new THREE.MeshBasicMaterial({
      color: '#7c3aed',
      wireframe: true,
      transparent: true,
      opacity: 0.18,
      blending: THREE.AdditiveBlending,
    });
    this.coreRingMesh = new THREE.Mesh(this.coreRingGeometry, this.coreRingMaterial);
    this.coreRingMesh.rotation.x = Math.PI * 0.35;
    this.scene.add(this.coreRingMesh);

    // Particle field & network
    this.particleField = new ParticleField(config);
    this.scene.add(this.particleField.group);
  }

  public update(
    delta: number,
    params: SceneParameters,
    time: number,
    isReducedMotion: boolean,
    pointer: { x: number; y: number }
  ): void {
    // 1. Update Core Ring rotation & opacity
    if (!isReducedMotion) {
      const speed = params.particleSpeed * 0.4;
      this.coreRingMesh.rotation.z += delta * speed;
      this.coreRingMesh.rotation.y += delta * speed * 0.5;
    }
    this.coreRingMaterial.opacity = THREE.MathUtils.lerp(
      this.coreRingMaterial.opacity,
      0.15 * params.glowIntensity,
      delta * 2
    );
    this.coreRingMaterial.color.lerp(new THREE.Color(params.accentColor), delta * 2);

    // 2. Light Orbit & Color interpolation
    if (!isReducedMotion) {
      this.primaryLight.position.x = Math.cos(time * 0.4) * 45;
      this.primaryLight.position.y = Math.sin(time * 0.3) * 30;
      this.secondaryLight.position.x = Math.sin(time * 0.5) * -45;
      this.secondaryLight.position.y = Math.cos(time * 0.4) * -30;
    }
    this.primaryLight.color.lerp(new THREE.Color(params.baseColor), delta * 3);
    this.secondaryLight.color.lerp(new THREE.Color(params.accentColor), delta * 3);

    // 3. Camera subtle parallax & FOV transition
    const targetDistance = params.cameraDistance;
    if (isReducedMotion) {
      this.targetCameraPos.set(0, 0, targetDistance);
    } else {
      // Damped pointer reaction (-1 to 1)
      const parallaxX = pointer.x * 12;
      const parallaxY = -pointer.y * 8;
      this.targetCameraPos.set(parallaxX, parallaxY, targetDistance);
    }

    this.currentCameraPos.lerp(this.targetCameraPos, delta * 2.5);
    this.camera.position.copy(this.currentCameraPos);
    this.camera.lookAt(0, 0, 0);

    if (Math.abs(this.camera.fov - params.cameraFov) > 0.1) {
      this.camera.fov = THREE.MathUtils.lerp(this.camera.fov, params.cameraFov, delta * 2);
      this.camera.updateProjectionMatrix();
    }

    // 4. Update Particles
    this.particleField.update(delta, params, time, isReducedMotion);
  }

  public setAspect(aspect: number): void {
    this.camera.aspect = aspect;
    this.camera.updateProjectionMatrix();
  }

  public dispose(): void {
    this.particleField.dispose();
    this.coreRingGeometry.dispose();
    this.coreRingMaterial.dispose();
    this.scene.clear();
  }
}
