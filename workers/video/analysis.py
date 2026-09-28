"""Deterministic temporal evidence; semantic identity/anatomy requires the separate Vision port."""
import cv2
import numpy as np
from processes import VideoError


def read_frames(path, maximum=1800):
    cap=cv2.VideoCapture(str(path)); frames=[]
    try:
        fps=cap.get(cv2.CAP_PROP_FPS)
        count=int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if count>maximum or count<2: raise VideoError('ANALYSIS_FRAME_LIMIT')
        while True:
            ok,frame=cap.read()
            if not ok: break
            frames.append(cv2.cvtColor(cv2.resize(frame,(96,64)),cv2.COLOR_BGR2GRAY).astype(np.float32)/255)
            if len(frames)>maximum: raise VideoError('ANALYSIS_FRAME_LIMIT')
        if len(frames)<2: raise VideoError('INSUFFICIENT_FRAMES')
        return np.stack(frames),fps
    finally: cap.release()


class LoopBoundaryAnalyzer:
    def analyze(self,frames):
        k=min(5,max(1,len(frames)//4)); head,tail=frames[:k],frames[-k:]
        pixel=float(np.mean(np.abs(frames[0]-frames[-1])))
        color=float(abs(frames[0].mean()-frames[-1].mean()))
        edge=float(np.mean(np.abs(cv2.Laplacian(frames[0],cv2.CV_32F)-cv2.Laplacian(frames[-1],cv2.CV_32F))))
        # Compare velocities across windows as well as their values at the seam.
        incoming=np.diff(tail,axis=0).mean(axis=0) if k>1 else np.zeros_like(head[0])
        outgoing=np.diff(head,axis=0).mean(axis=0) if k>1 else incoming
        velocity=float(np.mean(np.abs(incoming-outgoing)))
        seam=frames[0]-frames[-1]
        seam_error=float(np.mean(np.abs(seam-(incoming+outgoing)/2)))
        motion_score=max(0.,1-8*velocity-5*seam_error)
        visual=max(0.,1-4*pixel-2*edge); color_score=max(0.,1-8*color)
        return dict(windowFrames=k,pixelDifference=pixel,colorDifference=color,edgeDifference=edge,
                    motionDirectionDifference=velocity,seamMotionError=seam_error,
                    visualBoundaryScore=visual,motionContinuityScore=motion_score,
                    colorContinuityScore=color_score,overallLoopScore=.45*visual+.35*motion_score+.2*color_score)


class VideoQualityAnalyzer:
    def analyze(self,frames,fps,profile=None):
        p=profile or {}; delta=np.mean(np.abs(np.diff(frames,axis=0)),axis=(1,2))
        luminance=frames.mean(axis=(1,2)); brightness=np.abs(np.diff(luminance))
        cut_limit=float(p.get('sceneCutThreshold',.30)); flicker_limit=float(p.get('flickerThreshold',.12))
        cuts=[dict(startSeconds=i/fps,endSeconds=(i+1)/fps,code='SCENE_CUT',severity='MAJOR') for i,d in enumerate(delta) if d>cut_limit]
        # An alternating brightness pulse is distinguished from a single exposure transition.
        signed=np.diff(luminance); flicker=np.minimum(brightness[:-1],brightness[1:])*(signed[:-1]*signed[1:]<0)
        flickers=[dict(startSeconds=i/fps,endSeconds=(i+2)/fps,code='FLICKER',severity='MAJOR') for i,d in enumerate(flicker) if d>flicker_limit]
        shifts=[]
        for a,b in zip(frames[:-1],frames[1:]):
            shift,response=cv2.phaseCorrelate(a,b)
            shifts.append(shift if response>.1 else (0,0))
        shifts=np.array(shifts); motion=float(delta.mean()); shake=float(np.linalg.norm(np.diff(shifts,axis=0),axis=1).mean()) if len(shifts)>1 else 0.
        classification='VERY_LOW' if motion<.005 else 'LOW' if motion<.025 else 'MEDIUM' if motion<.075 else 'HIGH' if motion<.15 else 'VERY_HIGH'
        issues=(cuts+flickers)[:100]
        frozen_start=None
        for i,difference in enumerate(list(delta)+[1]):
            if difference<.00001 and frozen_start is None: frozen_start=i
            elif difference>=.00001 and frozen_start is not None:
                if (i-frozen_start)/fps>=.5:
                    issues.append(dict(startSeconds=frozen_start/fps,endSeconds=i/fps,code='FROZEN_FRAMES',severity='MINOR'))
                frozen_start=None
        if shake>p.get('cameraShakeThreshold',4): issues.append(dict(startSeconds=0,endSeconds=len(frames)/fps,code='CAMERA_SHAKE',severity='MAJOR'))
        if motion>p.get('maximumMotion',.15): issues.append(dict(startSeconds=0,endSeconds=len(frames)/fps,code='EXCESSIVE_MOTION',severity='MAJOR'))
        return dict(sceneCuts=len(cuts),flickerScore=float(flicker.max()) if len(flicker) else 0.,
                    motionIntensity=motion,motionClass=classification,cameraMotionScore=float(np.linalg.norm(shifts,axis=1).mean()),
                    cameraShakeScore=shake,issues=issues,status='NEEDS_REVIEW' if issues else 'PASS',
                    semanticAssessment='REQUIRES_VISION_AND_HUMAN_REVIEW')


class LoopStrategySelector:
    def select(self,evidence,profile,motion):
        if evidence['overallLoopScore']>=profile.get('directThreshold',.90): return 'DIRECT'
        if evidence['overallLoopScore']>=profile.get('crossfadeThreshold',.45): return 'CROSSFADE'
        if motion.get('reversible',False) and motion.get('cameraMotion','STATIC')=='STATIC': return 'PING_PONG'
        return 'REVIEW'
