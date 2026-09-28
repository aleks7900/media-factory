import hashlib
import math
import os
import time
import cv2
from PIL import Image
from processes import BASE, VideoError, run
from probe import FfprobeService, VideoTechnicalValidator
from analysis import read_frames, LoopBoundaryAnalyzer, VideoQualityAnalyzer, LoopStrategySelector


def bounded(value,low,high,name):
    if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or not low<=value<=high:
        raise VideoError('INVALID_'+name.upper())
    return value


def validate_profile(p):
    p=dict(p)
    for key,default,lo,hi in [('width',720,64,4096),('height',1280,64,4096),('fps',24,1,60),
                              ('duration',5,1,30),('quality',18,0,40),('trimStart',0,0,25),
                              ('crossfadeSeconds',.5,.1,2),('focalX',.5,0,1),('focalY',.5,0,1),
                              ('denoise',0,0,3),('sharpen',0,0,1),('saturation',1,.5,1.5),
                              ('maximumBytes',134217728,1024,134217728),('targetBitrate',6_000_000,100000,30000000)]:
        p[key]=bounded(p.get(key,default),lo,hi,key)
    if p['width']%2 or p['height']%2 or p['width']*p['height']>9_000_000: raise VideoError('INVALID_DIMENSIONS')
    for key,default,values in [('codec','H264',('H264','H265','AV1')),('audioPolicy','REMOVE',('REMOVE','KEEP','OPTIONAL')),
                              ('encodingMode','CPU',('CPU','GPU','AUTO')),('cropMode','FIT',('FIT','FILL')),
                              ('loopStrategy','AUTO',('AUTO','DIRECT','CROSSFADE','PING_PONG')),
                              ('bitrateMode','QUALITY',('QUALITY','BITRATE'))]:
        p.setdefault(key,default)
        if p[key] not in values: raise VideoError('INVALID_'+key.upper())
    for key in ('stabilize','interpolate','allowCpuFallback'):
        if key in p and not isinstance(p[key],bool): raise VideoError('INVALID_'+key.upper())
    if p['duration']<=2*p['crossfadeSeconds']: raise VideoError('CROSSFADE_TOO_LONG')
    return p


class FfmpegService:
    def __init__(self):
        self.version=run(['ffmpeg','-version']).stdout.decode().splitlines()[0]
        self.probe_version=run(['ffprobe','-version']).stdout.decode().splitlines()[0]
        self.encoders=run(['ffmpeg','-hide_banner','-encoders']).stdout.decode()
        self.decoders=run(['ffmpeg','-hide_banner','-decoders']).stdout.decode()
        self.hardware={}
        for name in ('h264_nvenc','hevc_nvenc','h264_qsv','hevc_qsv'):
            if name in self.encoders:
                try:
                    run(BASE+['-f','lavfi','-i','color=size=128x128:rate=1','-frames:v','1','-c:v',name,'-f','null','-'],timeout=8)
                    self.hardware[name]=True
                except VideoError: self.hardware[name]=False

    def diagnostics(self):
        return dict(ffmpegVersion=self.version,ffprobeVersion=self.probe_version,
                    encoders=[n for n in ('libx264','libx265','libaom-av1','h264_nvenc','hevc_nvenc','h264_qsv','hevc_qsv') if n in self.encoders],
                    decoders=[n for n in ('h264','hevc','vp9','av1') if n in self.decoders],hardware=self.hardware)

    def encoding(self,p):
        cpu={'H264':'libx264','H265':'libx265','AV1':'libaom-av1'}[p['codec']]
        hardware=[x for x,available in self.hardware.items() if available and x.startswith('h264' if p['codec']=='H264' else 'hevc')]
        encoder=cpu
        if p['encodingMode']!='CPU' and hardware and p['codec']!='AV1': encoder=hardware[0]
        elif p['encodingMode']=='GPU' and not p.get('allowCpuFallback',False): raise VideoError('GPU_ENCODER_UNAVAILABLE')
        if encoder not in self.encoders: raise VideoError('ENCODER_UNAVAILABLE')
        args=['-c:v',encoder,'-pix_fmt','yuv420p']
        if p['bitrateMode']=='BITRATE' or encoder!=cpu:
            args+=['-b:v',str(int(p['targetBitrate'])),'-maxrate',str(int(p['targetBitrate']*1.5)),'-bufsize',str(int(p['targetBitrate']*2))]
        else:
            args+=['-crf',str(int(p['quality']))]
        if encoder in ('libx264','libx265'): args+=['-preset','medium','-threads','2']
        if encoder=='libx265': args+=['-x265-params','pools=2:frame-threads=2:log-level=error','-tag:v','hvc1']
        if encoder=='libaom-av1': args+=['-cpu-used','6','-row-mt','1','-threads','2']
        args+=['-movflags','+faststart','-map_metadata','-1','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709']
        return args,encoder

    def filters(self,p):
        w,h=int(p['width']),int(p['height']); filters=[]
        if p.get('stabilize'): filters.append('deshake=rx=16:ry=16:edge=mirror')
        if p['cropMode']=='FIT':
            filters += [f'scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2',f'pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black']
        else:
            filters += [f'scale={w}:{h}:force_original_aspect_ratio=increase:force_divisible_by=2',
                        rf'crop={w}:{h}:min(max(iw*{p["focalX"]}-{w}/2\,0)\,iw-{w}):min(max(ih*{p["focalY"]}-{h}/2\,0)\,ih-{h})']
        filters.append(f'minterpolate=fps={p["fps"]}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1' if p.get('interpolate') else f'fps={p["fps"]}')
        if p['denoise']: filters.append(f'hqdn3d={p["denoise"]}')
        if p['sharpen']: filters.append(f'unsharp=5:5:{p["sharpen"]}:5:5:0')
        if p['saturation']!=1: filters.append(f'eq=saturation={p["saturation"]}')
        filters+=['setsar=1','format=yuv420p','setpts=PTS-STARTPTS']
        return ','.join(filters)

    def transcode(self,source,target,p,cancel=None):
        p=validate_profile(p); args,encoder=self.encoding(p); graph=self.filters(p)
        audio=['-an'] if p['audioPolicy']=='REMOVE' else ['-map','0:a?','-c:a','aac','-b:a','128k']
        result=run(BASE+['-protocol_whitelist','file,pipe','-i',str(source),'-ss',str(p['trimStart']),'-t',str(p['duration']),
                         '-map','0:v:0','-vf',graph]+audio+args+[str(target)],timeout=300,cancel=cancel)
        if target.stat().st_size>p['maximumBytes']: raise VideoError('OUTPUT_SIZE_LIMIT')
        return dict(type='TRANSCODE',parameters=p,filterGraph=graph,encoder=encoder,device='CPU' if encoder.startswith('lib') else 'GPU',durationMs=result.duration_ms)

    def mock(self,source,target,p,cancel=None):
        p=validate_profile(p)
        with Image.open(source) as im:
            if im.width*im.height>64_000_000: raise VideoError('IMAGE_TOO_LARGE')
            im.verify()
        n=round(p['duration']*p['fps']); w,h=int(p['width']),int(p['height'])
        graph=f"scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,zoompan=z='1.025+0.015*sin(2*PI*on/{n})':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d={n}:s={w}x{h}:fps={p['fps']},format=yuv420p"
        args,_=self.encoding(dict(p,encodingMode='CPU'))
        run(BASE+['-i',str(source),'-vf',graph,'-frames:v',str(n),'-an']+args+[str(target)],timeout=120,cancel=cancel)
        return dict(mock=True,filterGraph=graph,frames=n)

    def loop(self,source,target,strategy,p,motion,cancel=None):
        m=FfprobeService().probe(source,cancel); duration=m['duration']; fps=m['fps']; args,encoder=self.encoding(p)
        if strategy=='PING_PONG' and (not motion.get('reversible') or motion.get('cameraMotion','STATIC')!='STATIC'):
            raise VideoError('MOTION_NOT_REVERSIBLE')
        # Reversal and crossfade change chronology. Keeping audio would be misleading.
        if strategy!='DIRECT' and m['audio'] and p['audioPolicy']!='REMOVE': raise VideoError('LOOP_REQUIRES_AUDIO_REMOVAL')
        if strategy=='DIRECT':
            graph='null'; command=BASE+['-i',str(source),'-map','0:v:0','-map','0:a?','-c','copy','-movflags','+faststart',str(target)]
        elif strategy=='PING_PONG':
            n=m['frameCount']; graph=f'[0:v]split[a][b];[b]reverse,trim=start_frame=1:end_frame={n-1},setpts=PTS-STARTPTS[r];[a][r]concat=n=2:v=1:a=0,fps={fps}[v]'
            command=BASE+['-i',str(source),'-filter_complex',graph,'-map','[v]','-an']+args+[str(target)]
        elif strategy=='CROSSFADE':
            fade=p['crossfadeSeconds']
            if duration<=2*fade: raise VideoError('CROSSFADE_TOO_LONG')
            # B..end overlapped with A..B; playback wraps from B to B+1, not back to A.
            graph=f'[0:v]split[a][b];[a]trim=start={fade},setpts=PTS-STARTPTS[tail];[b]trim=end={fade},setpts=PTS-STARTPTS[head];[tail][head]xfade=transition=fade:duration={fade}:offset={duration-2*fade},fps={fps}[v]'
            command=BASE+['-i',str(source),'-filter_complex',graph,'-map','[v]','-an']+args+[str(target)]
        else: raise VideoError('INVALID_LOOP_STRATEGY')
        result=run(command,timeout=300,cancel=cancel)
        return dict(type='LOOP_'+strategy,parameters=dict(crossfadeSeconds=p['crossfadeSeconds']),filterGraph=graph,encoder=encoder,durationMs=result.duration_ms)

    def still(self,source,target,time_seconds,width,height,cancel=None):
        graph=f'scale={width}:{height}:force_original_aspect_ratio=decrease'
        run(BASE+['-ss',str(float(time_seconds)),'-i',str(source),'-frames:v','1','-vf',graph,'-q:v','2','-update','1',str(target)],timeout=30,cancel=cancel)


class VideoPipeline:
    def __init__(self,ffmpeg): self.ffmpeg=ffmpeg

    def analyze(self,path,p,cancel=None):
        validation=VideoTechnicalValidator().validate(path,p,cancel)
        if not validation['valid']: return dict(technical=validation)
        frames,fps=read_frames(path)
        return dict(technical=validation,qa=VideoQualityAnalyzer().analyze(frames,fps,p),loop=LoopBoundaryAnalyzer().analyze(frames))

    def process(self,source,workspace,profile,motion,variants,cancel=None):
        p=validate_profile(profile); operations=[]; start=time.monotonic()
        before=self.analyze(source,dict(p,audioPolicy='OPTIONAL'),cancel)
        if not before['technical']['valid']: raise VideoError('RAW_VALIDATION_FAILED')
        intermediate=workspace/'processed.mp4'
        operations.append(self.ffmpeg.transcode(source,intermediate,p,cancel))
        middle=self.analyze(intermediate,p,cancel)
        strategy=p['loopStrategy']
        if strategy=='AUTO': strategy=LoopStrategySelector().select(middle['loop'],p,motion)
        needs_review=strategy=='REVIEW'
        if needs_review: strategy='DIRECT'
        master=workspace/'master.mp4'
        operations.append(self.ffmpeg.loop(intermediate,master,strategy,p,motion,cancel))
        after=self.analyze(master,p,cancel)
        if not after['technical']['valid']: raise VideoError('MASTER_VALIDATION_FAILED')
        outputs=[('PROCESSED_VIDEO',intermediate,'RAW_VIDEO'),('MASTER_VIDEO',master,'PROCESSED_VIDEO')]
        for name,variant in variants.items():
            if name not in ('ANDROID_VIDEO_FHD','ANDROID_VIDEO_QHD','ANDROID_VIDEO_GENERIC','SOCIAL_VERTICAL','SOCIAL_HORIZONTAL','SOCIAL_SQUARE','VIDEO_PREVIEW'): raise VideoError('UNKNOWN_VARIANT')
            path=workspace/(name.lower()+'.mp4'); vp=validate_profile(p | variant | dict(trimStart=0,duration=after['technical']['metadata']['duration']))
            op=self.ffmpeg.transcode(master,path,vp,cancel); op['variant']=name; operations.append(op)
            validation=VideoTechnicalValidator().validate(path,vp,cancel)
            if not validation['valid']: raise VideoError('VARIANT_VALIDATION_FAILED')
            outputs.append((name,path,'MASTER_VIDEO'))
        preview=next((path for name,path,_ in outputs if name=='VIDEO_PREVIEW'),master)
        repeated=workspace/'loop-preview.mp4'
        run(BASE+['-stream_loop','2','-i',str(preview),'-map','0:v:0','-c','copy','-an','-movflags','+faststart',str(repeated)],timeout=30,cancel=cancel)
        outputs.append(('LOOP_PREVIEW',repeated,'VIDEO_PREVIEW' if preview!=master else 'MASTER_VIDEO'))
        # Choose the sharpest non-cut sample instead of a fixed potentially blurred frame.
        cap=cv2.VideoCapture(str(master)); candidates=[]
        try:
            for fraction in (.1,.25,.5,.75,.9):
                seconds=after['technical']['metadata']['duration']*fraction; cap.set(cv2.CAP_PROP_POS_MSEC,seconds*1000)
                ok,frame=cap.read()
                if ok: candidates.append((float(cv2.Laplacian(cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY),cv2.CV_64F).var()),seconds))
        finally: cap.release()
        poster_time=max(candidates)[1] if candidates else 0
        for name,width,height in [('VIDEO_POSTER',720,1280),('VIDEO_THUMBNAIL',240,426)]:
            path=workspace/(name.lower()+'.jpg'); self.ffmpeg.still(master,path,poster_time,width,height,cancel); outputs.append((name,path,'MASTER_VIDEO'))
        return outputs,dict(raw=before,processed=middle,master=after,strategy=strategy,needsLoopReview=needs_review or after['loop']['overallLoopScore']<p.get('minimumLoopScore',.8),
                            operations=operations,ffmpegVersion=self.ffmpeg.version,posterTime=poster_time,
                            durationMs=int((time.monotonic()-start)*1000),inputBytes=source.stat().st_size,outputBytes=sum(path.stat().st_size for _,path,_ in outputs))
