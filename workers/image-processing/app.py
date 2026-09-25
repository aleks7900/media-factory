import base64
import io
import logging
import os
from pathlib import Path
import shutil
import tempfile
import threading
import time
import uuid
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from PIL import Image
from common import ProcessingError, decode, bounds
import crop
import resize
import filters
import encoding
import validation
import upscale

import torch
torch.set_num_threads(max(1,min(8,int(os.getenv('TORCH_THREADS','4')))))

app=FastAPI()
lock=threading.Lock()
active=set(); cancelled=set()
gpu_active=set()
logger=logging.getLogger('processing')

@app.on_event('startup')
def cleanup_abandoned_workspaces():
    root=Path(os.getenv('PROCESSING_TMP','/tmp/processing'));root.mkdir(parents=True,exist_ok=True)
    retention=max(3600,int(os.getenv('TEMP_RETENTION_SECONDS','86400')))
    for path in root.iterdir():
        if path.is_dir() and not path.is_symlink() and time.time()-path.stat().st_mtime>retention:
            try:uuid.UUID(path.name[:36])
            except ValueError:continue
            shutil.rmtree(path)

@app.middleware('http')
async def limit(request:Request, call_next):
    if int(request.headers.get('content-length','0'))>90*1024*1024:
        return JSONResponse({'code':'INPUT_TOO_LARGE'},status_code=413)
    # Also bound chunked requests before JSON decoding.
    body=bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body)>90*1024*1024:return JSONResponse({'code':'INPUT_TOO_LARGE'},status_code=413)
    request._body=bytes(body)
    return await call_next(request)

@app.get('/health')
def health():
    capabilities=upscale.capabilities()
    return {**capabilities,'status':'BUSY' if active else ('ONLINE' if all(m['available'] for m in capabilities['models']) else 'DEGRADED'),
            'activeJobs':len(active),'activeGpuJobs':len(gpu_active),'maxConcurrentJobs':1}

@app.post('/v1/cancel/{run_id}')
def cancel(run_id:str):
    if run_id in active:cancelled.add(run_id)
    return {'cancelRequested':run_id in active}

@app.post('/v1/crop-preview')
def preview(body:dict):
    try:
        image=decode(base64.b64decode(body['data'],validate=True));p=body['profile']
        regions=body.get('focalRegions') or crop.detect(image)
        return crop.calculate(image.width,image.height,p['width'],p['height'],regions,p.get('padding',.15),p.get('safeTop',0),p.get('safeBottom',0))
    except ProcessingError as exc:raise HTTPException(422,detail={'code':exc.code}) from exc

@app.post('/v1/execute')
def execute(body:dict):
    run_id=str(uuid.UUID(body['runId']))
    if not lock.acquire(blocking=False):return JSONResponse({'code':'WORKER_BUSY'},status_code=429)
    active.add(run_id)
    started=time.monotonic()
    try:
        root=Path(os.getenv('PROCESSING_TMP','/tmp/processing'));root.mkdir(parents=True,exist_ok=True)
        if shutil.disk_usage(root).free<int(os.getenv('MIN_FREE_DISK_BYTES',str(1024**3))):raise ProcessingError('INSUFFICIENT_STORAGE')
        with tempfile.TemporaryDirectory(prefix=run_id+'-',dir=root):
            image=decode(base64.b64decode(body['data'],validate=True))
            scale=body.get('scale',1)
            estimated=image.width*image.height*scale*scale*16
            if estimated>int(os.getenv('TEMP_DISK_BUDGET_BYTES',str(2*1024**3))) or shutil.disk_usage(root).free<estimated+int(os.getenv('MIN_FREE_DISK_BYTES',str(1024**3))):
                raise ProcessingError('INSUFFICIENT_STORAGE')
            metadata={};operation=body['operation'];stages=[]
            def stage(name, function):
                before=time.monotonic();result=function()
                stages.append(dict(type=name,status='COMPLETED',durationMs=round((time.monotonic()-before)*1000)))
                return result
            image,color_metadata=stage('COLOR_CONVERT',lambda:encoding.color(image))
            metadata.update(color_metadata)
            if operation=='UPSCALE':
                if upscale.choose_device(body.get('requireGpu',False))=='cuda':gpu_active.add(run_id)
                try:
                    image,details=stage('UPSCALE',lambda:upscale.upscale(image,body['scale'],body.get('requireGpu',False),lambda:run_id in cancelled))
                finally:gpu_active.discard(run_id)
                metadata.update(details)
                output=io.BytesIO();image.save(output,format='PNG',icc_profile=encoding.SRGB)
                data=output.getvalue();p={'format':'PNG','maxBytes':64*1024*1024}
            elif operation=='DERIVE':
                p=body['profile']
                regions=body.get('focalRegions') or stage('SUBJECT_DETECTION',lambda:crop.detect(image))
                image,crop_details=stage('SMART_CROP',lambda:crop.apply(image,p,regions,body.get('manualCrop')))
                image=stage('RESIZE',lambda:resize.resize(image,p,crop_details.get('letterbox',False)))
                image=stage('DENOISE_SHARPEN',lambda:filters.apply(image,p))
                data,quality=stage('FORMAT_CONVERT_COMPRESS_METADATA',lambda:encoding.encode(image,p))
                metadata.update(crop=crop_details,actualQuality=quality,device='cpu',provider='local-pillow',model='deterministic',modelVersion='Pillow-11.3.0',
                                resampling='LANCZOS',metadataPolicy='strip EXIF/XMP/internal metadata; embed sRGB')
            else:raise ProcessingError('UNSUPPORTED_OPERATION')
            if run_id in cancelled:raise ProcessingError('CANCELLED')
            bounds(image.width,image.height)
            checked=stage('VALIDATE',lambda:validation.validate(data,p,image.size))
            metadata['stages']=stages
            duration=round((time.monotonic()-started)*1000)
            logger.info('processing_output_validated run=%s operation=%s durationMs=%s',run_id,operation,duration)
            return dict(data=base64.b64encode(data).decode(),validation=checked,metadata=metadata,durationMs=duration)
    except ProcessingError as exc:return JSONResponse({'code':exc.code,'message':str(exc)[:500]},status_code=422)
    except Exception:
        logger.exception('processing_failed run=%s',run_id)
        return JSONResponse({'code':'WORKER_FAILURE'},status_code=503)
    finally:
        gpu_active.discard(run_id);active.discard(run_id);cancelled.discard(run_id);lock.release()
