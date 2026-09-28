import math
import threading
import time
import numpy as np
import pytest
from PIL import Image
from analysis import LoopBoundaryAnalyzer, VideoQualityAnalyzer, LoopStrategySelector
from ffmpeg import FfmpegService, VideoPipeline, validate_profile
from probe import FfprobeService, VideoTechnicalValidator
from processes import BASE, VideoError, run


@pytest.fixture(scope='module')
def service(): return FfmpegService()


@pytest.fixture
def raw(tmp_path,service):
    image=Image.new('RGB',(320,240)); pix=image.load()
    for x in range(320):
        for y in range(240): pix[x,y]=(x%255,y%255,(x+y)%255)
    source=tmp_path/'source.png'; image.save(source)
    raw=tmp_path/'raw.mp4'; service.mock(source,raw,dict(width=320,height=240,duration=2,fps=12,crossfadeSeconds=.25))
    return raw


def test_probe_mock_and_full_decode(raw):
    m=FfprobeService().probe(raw)
    assert m['codec']=='h264' and m['frameCount']==24 and not m['audio']
    assert VideoTechnicalValidator().validate(raw)['valid']


def test_trim_crop_resize_fps_and_interpolation(raw,tmp_path,service):
    for interpolate in (False,True):
        p=dict(width=128,height=192,duration=1.5,trimStart=.1,fps=24,interpolate=interpolate,crossfadeSeconds=.25,cropMode='FILL',focalX=.8,denoise=.5,sharpen=.2,saturation=1.05)
        target=tmp_path/f'processed-{interpolate}.mp4'; operation=service.transcode(raw,target,p)
        m=FfprobeService().probe(target)
        assert (m['width'],m['height'],m['fps'])==(128,192,24)
        assert 1.3<=m['duration']<=1.6 and 'crop=' in operation['filterGraph']


@pytest.mark.parametrize('strategy',['DIRECT','CROSSFADE','PING_PONG'])
def test_loop_revalidation_and_immutable_input(raw,tmp_path,service,strategy):
    before=raw.read_bytes(); p=validate_profile(dict(width=320,height=240,duration=2,fps=12,crossfadeSeconds=.25))
    output=tmp_path/(strategy+'.mp4'); service.loop(raw,output,strategy,p,dict(reversible=True,cameraMotion='STATIC'))
    result=VideoPipeline(service).analyze(output,p)
    assert result['technical']['valid'] and result['loop']['windowFrames']>1
    assert raw.read_bytes()==before
    if strategy=='PING_PONG': assert result['technical']['metadata']['frameCount']==46


def test_ping_pong_rejects_directional_motion(raw,tmp_path,service):
    with pytest.raises(VideoError,match='MOTION_NOT_REVERSIBLE'):
        service.loop(raw,tmp_path/'bad.mp4','PING_PONG',validate_profile({}),dict(reversible=False,cameraMotion='PUSH_IN'))


def test_boundary_windows_and_brightness_jump():
    frames=np.ones((30,64,96),dtype=np.float32)*.3
    analyzer=LoopBoundaryAnalyzer(); good=analyzer.analyze(frames)
    frames[-5:]=.9; bad=analyzer.analyze(frames)
    assert good['overallLoopScore']>.99 and bad['overallLoopScore']<.5
    assert bad['colorDifference']>.5 and bad['windowFrames']==5


def test_cut_flicker_motion_and_camera_shake():
    stable=np.ones((30,64,96),dtype=np.float32)*.3; qa=VideoQualityAnalyzer()
    assert qa.analyze(stable,10)['sceneCuts']==0
    cuts=stable.copy(); cuts[15:]=.9
    assert qa.analyze(cuts,10)['sceneCuts']==1
    flicker=stable.copy(); flicker[::2]=.9
    assert qa.analyze(flicker,10)['flickerScore']>.5
    rng=np.random.default_rng(1); texture=rng.random((64,96),dtype=np.float32)
    shake=np.array([np.roll(texture,5 if i%2 else -5,axis=1) for i in range(30)])
    assert qa.analyze(shake,10)['cameraShakeScore']>5


def test_loop_selection_policy():
    selector=LoopStrategySelector()
    assert selector.select(dict(overallLoopScore=.95),{}, {})=='DIRECT'
    assert selector.select(dict(overallLoopScore=.6),{}, {})=='CROSSFADE'
    assert selector.select(dict(overallLoopScore=.1),{},dict(reversible=True,cameraMotion='STATIC'))=='PING_PONG'
    assert selector.select(dict(overallLoopScore=.1),{},dict(reversible=False))=='REVIEW'


def test_invalid_parameters_and_corruption(tmp_path):
    with pytest.raises(VideoError): validate_profile(dict(width='100;rm -rf /'))
    with pytest.raises(VideoError): validate_profile(dict(fps=float('nan')))
    with pytest.raises(VideoError): validate_profile(dict(codec='shell'))
    path=tmp_path/'broken.mp4'; path.write_bytes(b'not video')
    with pytest.raises(VideoError): FfprobeService().probe(path)


def test_audio_removal(raw,tmp_path,service):
    audio=tmp_path/'audio.mp4'
    run(BASE+['-i',str(raw),'-f','lavfi','-i','sine=frequency=440:duration=2','-c:v','copy','-c:a','aac','-shortest',str(audio)])
    assert FfprobeService().probe(audio)['audio']
    out=tmp_path/'silent.mp4'; service.transcode(audio,out,dict(width=320,height=240,duration=2,crossfadeSeconds=.25))
    assert not FfprobeService().probe(out)['audio']


def test_pipeline_variants_preview_poster_and_lineage(raw,tmp_path,service):
    outputs,evidence=VideoPipeline(service).process(raw,tmp_path,dict(width=320,height=240,duration=2,fps=12,crossfadeSeconds=.25,loopStrategy='DIRECT'),{},
        dict(VIDEO_PREVIEW=dict(width=160,height=120),SOCIAL_VERTICAL=dict(width=128,height=192),SOCIAL_SQUARE=dict(width=128,height=128)))
    by_name={name:(path,parent) for name,path,parent in outputs}
    assert by_name['MASTER_VIDEO'][1]=='PROCESSED_VIDEO'
    assert by_name['SOCIAL_VERTICAL'][1]=='MASTER_VIDEO'
    assert Image.open(by_name['VIDEO_POSTER'][0]).width>0
    assert FfprobeService().probe(by_name['LOOP_PREVIEW'][0])['duration']==pytest.approx(6,abs=.2)
    assert evidence['operations'] and evidence['ffmpegVersion']


def test_process_timeout_and_cancellation():
    event=threading.Event(); event.set()
    with pytest.raises(VideoError,match='CANCELLED'):
        run(BASE+['-re','-f','lavfi','-i','testsrc=duration=5:size=64x64','-f','null','-'],cancel=event)
    with pytest.raises(VideoError,match='PROCESS_TIMEOUT'):
        run(BASE+['-re','-f','lavfi','-i','testsrc=duration=5:size=64x64','-f','null','-'],timeout=.1)


def test_frozen_frame_timestamps():
    evidence=VideoQualityAnalyzer().analyze(np.ones((30,64,96),dtype=np.float32)*.3,10)
    frozen=next(i for i in evidence['issues'] if i['code']=='FROZEN_FRAMES')
    assert frozen['startSeconds']==0 and frozen['endSeconds']>2


def test_pingpong_variants_keep_full_loop_duration(raw,tmp_path,service):
    outputs,_=VideoPipeline(service).process(raw,tmp_path,dict(width=320,height=240,duration=2,fps=12,crossfadeSeconds=.25,loopStrategy='PING_PONG'),dict(reversible=True,cameraMotion='STATIC'),dict(VIDEO_PREVIEW=dict(width=160,height=120,duration=2)))
    paths={name:path for name,path,_ in outputs}
    assert FfprobeService().probe(paths['VIDEO_PREVIEW'])['duration']==pytest.approx(FfprobeService().probe(paths['MASTER_VIDEO'])['duration'],abs=.1)


def test_gpu_capacity_is_one_without_requiring_gpu(monkeypatch):
    import app
    import base64
    import uuid
    entered=threading.Event(); release=threading.Event()
    def slow_mock(source,target,profile,cancel):
        entered.set();release.wait(5);target.write_bytes(b'mock');return {}
    monkeypatch.setattr(app.ffmpeg,'mock',slow_mock)
    body=dict(runId=str(uuid.uuid4()),operation='MOCK',data=base64.b64encode(b'image').decode(),profile=dict(encodingMode='GPU'))
    thread=threading.Thread(target=lambda:app.execute(body));thread.start()
    try:
        assert entered.wait(2)
        with pytest.raises(VideoError,match='BUSY'):app.execute(body|dict(runId=str(uuid.uuid4())))
    finally:release.set();thread.join(5)
    assert app.health()['activeJobs']==0
