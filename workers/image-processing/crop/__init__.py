"""Normalized [0,1] focal coordinates; rectangles use left/top/width/height."""
import math
import numpy as np
from PIL import Image
from common import ProcessingError

def detect(image):
    import cv2
    thumb = image.convert("RGB")
    thumb.thumbnail((512, 512))
    rgb = np.asarray(thumb)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    detector = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    faces = detector.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(24, 24))
    regions = [{"x":int(x)/thumb.width,"y":int(y)/thumb.height,"width":int(w)/thumb.width,
                "height":int(h)/thumb.height,"type":"FACE","confidence":0.85} for x,y,w,h in faces]
    if regions:
        return regions
    # Bounded spectral-residual saliency, a local CV heuristic rather than a semantic claim.
    small = cv2.resize(gray, (64,64)).astype(np.float32)
    spectrum = np.fft.fft2(small)
    log = np.log(np.abs(spectrum) + 1e-8)
    residual = log - cv2.blur(log, (3,3))
    saliency = np.abs(np.fft.ifft2(np.exp(residual + 1j*np.angle(spectrum))))**2
    saliency = cv2.GaussianBlur(saliency.astype(np.float32), (9,9), 2)
    if float(saliency.max()-saliency.min()) < 1e-8:
        return []
    mask = (saliency > np.percentile(saliency, 92)).astype(np.uint8)
    contours,_ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for contour in sorted(contours, key=cv2.contourArea, reverse=True)[:3]:
        x,y,w,h = cv2.boundingRect(contour)
        if w*h >= 8:
            regions.append(dict(x=x/64,y=y/64,width=w/64,height=h/64,type="PRIMARY_SUBJECT",confidence=0.55))
    return regions

def calculate(width, height, target_width, target_height, regions, padding=.15, top=.0, bottom=.0):
    ratio = target_width/target_height
    cw,ch = (height*ratio,height) if width/height > ratio else (width,width/ratio)
    padded=[]
    for r in regions:
        if not all(math.isfinite(float(r[k])) for k in ("x","y","width","height")):
            raise ProcessingError("INVALID_FOCAL_REGION")
        x,y,w,h = r['x']*width,r['y']*height,r['width']*width,r['height']*height
        padded.append((max(0,x-w*padding),max(0,y-h*padding),min(width,x+w*(1+padding)),min(height,y+h*(1+padding)),r))
    xs={0.,max(0,width-cw),(width-cw)/2}
    ys={0.,max(0,height-ch),(height-ch)/2}
    for x,y,right,lower,r in padded:
        xs.update([max(0,min(width-cw,x)),max(0,min(width-cw,right-cw)),max(0,min(width-cw,(x+right-cw)/2))])
        ys.update([max(0,min(height-ch,y-top*ch)),max(0,min(height-ch,lower-(1-bottom)*ch)),max(0,min(height-ch,(y+lower-ch)/2))])
    def score(x,y):
        result=0
        for l,t,r,b,region in padded:
            area=max(0,min(x+cw,r)-max(x,l))*max(0,min(y+ch*(1-bottom),b)-max(y+ch*top,t))
            result += area/max(1,(r-l)*(b-t))*(3 if region['type']=='FACE' else 1)*region['confidence']
        return result - 1e-9*((x-(width-cw)/2)**2+(y-(height-ch)/2)**2)
    x,y=max(((x,y) for x in xs for y in ys),key=lambda p:score(*p))
    preserved=[]; excluded=[]
    for l,t,r,b,region in padded:
        (preserved if l>=x-.5 and r<=x+cw+.5 and t>=y+ch*top-.5 and b<=y+ch*(1-bottom)+.5 else excluded).append(region)
    return dict(rectangle=dict(x=x/width,y=y/height,width=cw/width,height=ch/height),
                confidence=0.9 if regions and not excluded else 0.45,
                preserved=preserved,excluded=excluded,focalRegions=regions,safeZones=dict(top=top,bottom=bottom),
                warnings=['SMART_CROP_UNSAFE'] if excluded else ([] if regions else ['NO_SUBJECT_DETECTED']))

def apply(image, profile, regions, manual=None):
    mode=profile.get('mode','FIT')
    if mode not in ('FILL','SMART_FILL'):
        return image, {}
    result=calculate(image.width,image.height,profile['width'],profile['height'],regions,
                     profile.get('padding',.15),profile.get('safeTop',0),profile.get('safeBottom',0))
    automatic=result['rectangle'].copy()
    rectangle=manual or automatic
    if manual:
        x,y,w,h=(float(rectangle[k]) for k in ('x','y','width','height'))
        if not all(math.isfinite(v) for v in (x,y,w,h)) or min(x,y)<0 or min(w,h)<=0 or x+w>1.000001 or y+h>1.000001:
            raise ProcessingError('INVALID_CROP')
        if abs((w*image.width)/(h*image.height)-profile['width']/profile['height'])>.01:
            raise ProcessingError('INVALID_CROP_ASPECT')
    elif result['excluded'] and mode=='SMART_FILL':
        if profile.get('unsafeCrop','NEEDS_REVIEW')=='LETTERBOX':
            result.update(automaticCrop=automatic,finalCrop=None,humanOverride=False,letterbox=True)
            return image,result
        raise ProcessingError('SMART_CROP_UNSAFE')
    x,y,w,h=(rectangle[k] for k in ('x','y','width','height'))
    image=image.crop((round(x*image.width),round(y*image.height),round((x+w)*image.width),round((y+h)*image.height)))
    result.update(automaticCrop=automatic,finalCrop=rectangle,humanOverride=bool(manual))
    return image,result
