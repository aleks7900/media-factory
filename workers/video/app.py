import asyncio
import base64
import hashlib
import json
import os
import tempfile
import threading
import uuid
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from ffmpeg import FfmpegService, VideoPipeline, validate_profile
from processes import VideoError
from probe import FfprobeService
from PIL import Image

app=FastAPI(title='Media Factory Video Worker',docs_url=None,redoc_url=None)
ffmpeg=FfmpegService(); pipeline=VideoPipeline(ffmpeg)
cpu=threading.BoundedSemaphore(max(1,int(os.getenv('VIDEO_CPU_JOBS','2'))))
gpu=threading.BoundedSemaphore(max(1,int(os.getenv('VIDEO_GPU_JOBS','1'))))
lock=threading.Lock(); active={}
MAX_BYTES=128*1024*1024


def output_metadata(path):
    if path.suffix == '.jpg':
        with Image.open(path) as image:
            return dict(width=image.width, height=image.height, fileSize=path.stat().st_size)
    return FfprobeService().probe(path)


@app.get('/health')
def health():
    with lock: jobs=len(active)
    return dict(status='UP',**ffmpeg.diagnostics(),activeJobs=jobs,
                maxCpuJobs=int(os.getenv('VIDEO_CPU_JOBS','2')),maxGpuJobs=int(os.getenv('VIDEO_GPU_JOBS','1')))


def execute(body):
    run_id=str(uuid.UUID(body['runId'])); p=validate_profile(body.get('profile',{}))
    mode=body.get('operation'); cancel=threading.Event()
    if mode not in ('MOCK','ANALYZE','PROCESS'): raise VideoError('UNKNOWN_OPERATION')
    resource=gpu if p['encodingMode'] in ('GPU','AUTO') else cpu
    if not resource.acquire(blocking=False): raise VideoError('BUSY')
    with lock:
        if run_id in active:
            resource.release(); raise VideoError('ALREADY_RUNNING')
        active[run_id]=cancel
    try:
        data=base64.b64decode(body['data'],validate=True)
        if not data or len(data)>MAX_BYTES: raise VideoError('INPUT_SIZE_LIMIT')
        with tempfile.TemporaryDirectory(prefix='video-',dir='/tmp/video' if Path('/tmp/video').is_dir() else None) as directory:
            workspace=Path(directory); source=workspace/('source.png' if mode=='MOCK' else 'raw.mp4'); source.write_bytes(data)
            if mode=='MOCK':
                target=workspace/'mock.mp4'; details=ffmpeg.mock(source,target,p,cancel)
                return dict(data=base64.b64encode(target.read_bytes()).decode(),metadata=details)
            if mode=='ANALYZE':
                result=pipeline.analyze(source,dict(p,audioPolicy='OPTIONAL'),cancel)
                if result['technical']['valid']:
                    samples=[]; duration=result['technical']['metadata']['duration']; fps=result['technical']['metadata']['fps']
                    for n,fraction in enumerate((0,.25,.5,.75,1)):
                        seconds=min(duration-1/fps,duration*fraction); path=workspace/f'frame-{n}.jpg'
                        ffmpeg.still(source,path,seconds,512,512,cancel)
                        samples.append(dict(timeSeconds=seconds,data=base64.b64encode(path.read_bytes()).decode()))
                    result['samples']=samples
                return result
            variants=body.get('variants',{})
            if not isinstance(variants,dict) or len(variants)>7: raise VideoError('VARIANT_LIMIT')
            outputs,evidence=pipeline.process(source,workspace,p,body.get('motion',{}),variants,cancel)
            if sum(path.stat().st_size for _,path,_ in outputs)>MAX_BYTES: raise VideoError('OUTPUT_SIZE_LIMIT')
            return dict(evidence=evidence,outputs=[dict(kind=name,parent=parent,
                        data=base64.b64encode(path.read_bytes()).decode(),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                        metadata=output_metadata(path),
                        mediaType='image/jpeg' if path.suffix=='.jpg' else 'video/mp4') for name,path,parent in outputs])
    finally:
        with lock: active.pop(run_id,None)
        resource.release()


@app.post('/v1/execute')
async def perform(request:Request):
    try:
        data=bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data)>MAX_BYTES*4//3+1024*1024: raise VideoError('INPUT_SIZE_LIMIT')
        body=json.loads(data)
        return await run_in_threadpool(execute,body)
    except VideoError as error:
        return JSONResponse(status_code=429 if error.code in ('BUSY','ALREADY_RUNNING') else 422,
                            content=dict(code=error.code,detail=error.detail))
    except (ValueError,KeyError,TypeError):
        return JSONResponse(status_code=400,content=dict(code='INVALID_REQUEST'))


@app.post('/v1/cancel/{run_id}')
def cancel(run_id:str):
    with lock:
        event=active.get(str(uuid.UUID(run_id)))
        if event: event.set()
    return dict(cancelRequested=event is not None)
