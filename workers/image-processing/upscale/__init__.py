import hashlib
import json
import os
from pathlib import Path
import numpy as np
from PIL import Image
from common import ProcessingError, bounds

MODELS=json.loads(Path(__file__).parent.parent.joinpath('models.json').read_text())
_loaded={}

def capabilities():
    import torch
    available=torch.cuda.is_available()
    requested=os.getenv('PROCESSING_DEVICE','AUTO').upper()
    device='cuda' if available and requested!='CPU' else 'cpu'
    models=[]
    for model in MODELS:
        path=Path(os.getenv('MODEL_DIR','/models'))/model['file']
        models.append({**model,'available':path.exists()})
    return dict(gpuAvailable=available,cudaAvailable=available,device=device,
                vramBytes=torch.cuda.get_device_properties(0).total_memory if available else 0,
                models=models,formats=['JPEG','PNG','WEBP'],provider='local-realesrgan',version='processing-v1')

def choose_device(require_gpu=False):
    caps=capabilities()
    if require_gpu and caps['device']!='cuda':raise ProcessingError('GPU_UNAVAILABLE')
    if caps['device']=='cpu' and os.getenv('CPU_FALLBACK_ENABLED','true').lower()!='true':raise ProcessingError('GPU_UNAVAILABLE')
    return caps['device']

def model_for(scale, device):
    import torch
    from spandrel import ModelLoader
    metadata=next((m for m in MODELS if m['scale']==scale and m['enabled']),None)
    if metadata is None:raise ProcessingError('MODEL_UNAVAILABLE')
    key=(metadata['id'],device)
    if key not in _loaded:
        path=Path(os.getenv('MODEL_DIR','/models'))/metadata['file']
        if not path.exists():raise ProcessingError('MODEL_UNAVAILABLE')
        if hashlib.sha256(path.read_bytes()).hexdigest()!=metadata['sha256']:raise ProcessingError('MODEL_CHECKSUM_MISMATCH')
        state=torch.load(path,map_location='cpu',weights_only=True)
        descriptor=ModelLoader().load_from_state_dict(state.get('params_ema',state.get('params',state)))
        if descriptor.scale!=scale:raise ProcessingError('MODEL_SCALE_MISMATCH')
        _loaded[key]=descriptor.to(device).eval()
    return _loaded[key],metadata

def tiled(image, model, scale, device, tile, overlap=16, cancelled=lambda:False):
    import torch
    pixels=np.asarray(image.convert('RGB')).astype(np.float32)/255
    height,width=pixels.shape[:2]
    output=np.empty((height*scale,width*scale,3),dtype=np.uint8)
    # Keep only a padded input/output tile on GPU; assemble in bounded host memory.
    for y in range(0,height,tile):
        for x in range(0,width,tile):
            if cancelled():raise ProcessingError('CANCELLED')
            right,bottom=min(x+tile,width),min(y+tile,height)
            left_pad,top_pad=max(0,x-overlap),max(0,y-overlap)
            right_pad,bottom_pad=min(width,right+overlap),min(height,bottom+overlap)
            array=pixels[top_pad:bottom_pad,left_pad:right_pad]
            # x2 RRDB pixel unshuffle requires even dimensions.
            ph,pw=array.shape[:2]
            array=np.pad(array,((0,ph%2),(0,pw%2),(0,0)),mode='edge')
            tensor=torch.from_numpy(array.transpose(2,0,1).copy()).unsqueeze(0).to(device)
            with torch.inference_mode():result=model(tensor).squeeze(0).clamp(0,1).cpu().numpy().transpose(1,2,0)
            ox,oy=(x-left_pad)*scale,(y-top_pad)*scale
            output[y*scale:bottom*scale,x*scale:right*scale]=np.rint(result[oy:oy+(bottom-y)*scale,ox:ox+(right-x)*scale]*255).astype(np.uint8)
    result=Image.fromarray(output)
    if 'A' in image.getbands():result.putalpha(image.getchannel('A').resize(result.size,Image.Resampling.LANCZOS))
    return result

def upscale(image, scale, require_gpu=False, cancelled=lambda:False):
    import torch
    if scale not in (2,4):raise ProcessingError('INVALID_SCALE')
    bounds(image.width*scale,image.height*scale)
    device=choose_device(require_gpu)
    model,metadata=model_for(scale,device)
    tile=max(32,min(512,int(os.getenv('UPSCALE_TILE_SIZE','256'))))
    retries=0
    if device=='cuda':torch.cuda.reset_peak_memory_stats()
    try:
        result=tiled(image,model,scale,device,tile,cancelled=cancelled)
    except torch.cuda.OutOfMemoryError:
        torch.cuda.empty_cache();tile=max(32,tile//2);retries=1
        try:result=tiled(image,model,scale,device,tile,cancelled=cancelled)
        except torch.cuda.OutOfMemoryError as exc:raise ProcessingError('GPU_OOM') from exc
    return result,dict(provider=metadata['provider'],model=metadata['modelName'],modelVersion=metadata['version'],modelChecksum=metadata['sha256'],
                       device=device,tileSize=tile,tileOverlap=16,oomRetries=retries,
                       peakDeviceBytes=torch.cuda.max_memory_allocated() if device=='cuda' else None)
