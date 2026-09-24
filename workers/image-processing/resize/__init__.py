from PIL import Image, ImageOps
from common import bounds, ProcessingError

def resize(image, profile, letterbox=False):
    w,h=profile['width'],profile['height']
    mode=profile.get('mode','FIT')
    if mode=='PRESERVE':
        return image
    bounds(w,h)
    if letterbox:
        background=profile.get('background')
        if background not in ('WHITE','BLACK'):
            raise ProcessingError('BACKGROUND_REQUIRED')
        return ImageOps.pad(image,(w,h),method=Image.Resampling.LANCZOS,color=background.lower())
    if mode=='FIT':
        return ImageOps.contain(image,(w,h),Image.Resampling.LANCZOS)
    return image.resize((w,h),Image.Resampling.LANCZOS)
