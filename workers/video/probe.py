import json
import math
from fractions import Fraction
from processes import BASE, VideoError, run


class FfprobeService:
    def probe(self, path, cancel=None):
        result=run(['ffprobe','-v','error','-protocol_whitelist','file,pipe','-format_whitelist','mov,matroska,webm',
                    '-show_format','-show_streams','-of','json',str(path)],timeout=25,cancel=cancel)
        doc=json.loads(result.stdout)
        videos=[s for s in doc.get('streams',[]) if s.get('codec_type')=='video']
        if len(videos)!=1:
            raise VideoError('EXPECTED_ONE_VIDEO_STREAM')
        v=videos[0]; f=doc.get('format',{})
        try:
            fps=float(Fraction(v.get('avg_frame_rate','0')))
            duration=float(v.get('duration', f.get('duration',0)))
            width,height=int(v['width']),int(v['height'])
        except (ValueError, ZeroDivisionError, KeyError):
            raise VideoError('INVALID_VIDEO_METADATA')
        if not all(math.isfinite(x) for x in (fps,duration)):
            raise VideoError('INVALID_VIDEO_METADATA')
        return dict(container=f.get('format_name',''),codec=v.get('codec_name',''),width=width,height=height,
                    duration=duration,fps=fps,bitrate=int(f.get('bit_rate',0)),
                    audio=any(s.get('codec_type')=='audio' for s in doc['streams']),
                    fileSize=path.stat().st_size,frameCount=int(v['nb_frames']) if str(v.get('nb_frames','')).isdigit() else round(fps*duration),
                    pixelFormat=v.get('pix_fmt','unknown'))


class VideoTechnicalValidator:
    def validate(self,path,profile=None,cancel=None,deep=True):
        p=profile or {}; m=FfprobeService().probe(path,cancel); checks=[]
        def check(code,valid,actual,expected):
            checks.append(dict(code=code,status='PASS' if valid else 'FAIL',actual=actual,expected=expected))
        check('CONTAINER',bool(set(m['container'].split(',')) & {'mov','mp4','matroska','webm'}),m['container'],'MP4/Matroska')
        check('CODEC',m['codec'] in p.get('acceptedCodecs',['h264','hevc','vp9','av1']),m['codec'],p.get('acceptedCodecs',['h264','hevc','vp9','av1']))
        check('DIMENSIONS',64<=m['width']<=4096 and 64<=m['height']<=4096 and m['width']*m['height']<=9_000_000,[m['width'],m['height']],'64..4096; <=9MP')
        check('DURATION',.5<=m['duration']<=min(90,p.get('maximumDuration',30)),m['duration'],[.5,p.get('maximumDuration',30)])
        check('FPS',1<=m['fps']<=60,m['fps'],'1..60')
        check('BITRATE',0<m['bitrate']<=100_000_000,m['bitrate'],'0..100Mbps')
        check('FILE_SIZE',0<m['fileSize']<=p.get('maximumBytes',134217728),m['fileSize'],p.get('maximumBytes',134217728))
        check('FRAME_COUNT',1<=m['frameCount']<=5400,m['frameCount'],'1..5400')
        if p.get('audioPolicy')=='REMOVE': check('AUDIO',not m['audio'],m['audio'],False)
        valid=all(c['status']=='PASS' for c in checks)
        if valid and deep:
            try:
                run(BASE+['-xerror','-protocol_whitelist','file,pipe','-i',str(path),'-map','0:v:0','-an','-f','null','-'],timeout=120,cancel=cancel)
                check('FULL_DECODE',True,'DECODED','NO_ERRORS')
            except VideoError as error:
                if error.code=='CANCELLED': raise
                check('FULL_DECODE',False,error.code,'NO_ERRORS'); valid=False
        return dict(valid=valid,metadata=m,checks=checks)
