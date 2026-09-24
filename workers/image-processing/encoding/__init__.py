import io
from PIL import Image, ImageCms
from common import ProcessingError

SRGB=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()

def color(image):
    profile=image.info.get('icc_profile')
    alpha=image.convert('RGBA').getchannel('A') if 'A' in image.getbands() or 'transparency' in image.info else None
    rgb=image.convert('RGB')
    if profile:
        try:
            rgb=ImageCms.profileToProfile(rgb,ImageCms.ImageCmsProfile(io.BytesIO(profile)),ImageCms.createProfile('sRGB'),outputMode='RGB')
        except Exception as exc:
            raise ProcessingError('INVALID_ICC_PROFILE') from exc
    if alpha is not None:
        rgb.putalpha(alpha)
    return rgb, {'inputColorProfile':'embedded ICC' if profile else 'untagged; assumed sRGB','outputColorProfile':'sRGB'}

def encode(image, profile):
    fmt=profile.get('format','JPEG')
    if fmt=='JPEG' and image.mode=='RGBA':
        bg=profile.get('background')
        if bg not in ('WHITE','BLACK'):
            raise ProcessingError('BACKGROUND_REQUIRED')
        canvas=Image.new('RGB',image.size,bg.lower());canvas.paste(image,mask=image.getchannel('A'));image=canvas
    minimum,target=profile.get('minimumQuality',88),profile.get('quality',95)
    maximum=profile.get('maxBytes',20*1024*1024)
    def once(quality):
        options={'icc_profile':SRGB}
        if fmt=='JPEG': options.update(quality=quality,progressive=profile.get('progressive',True),subsampling=profile.get('subsampling',0))
        elif fmt=='PNG': options.update(compress_level=profile.get('compressionLevel',6))
        elif fmt=='WEBP': options.update(quality=quality,lossless=profile.get('lossless',False),method=4)
        else: raise ProcessingError('UNSUPPORTED_FORMAT')
        output=io.BytesIO();image.save(output,format=fmt,**options);return output.getvalue()
    data=once(target)
    if len(data)<=maximum:return data,target
    if fmt=='PNG' or profile.get('lossless',False):raise ProcessingError('OUTPUT_TOO_LARGE')
    data=once(minimum)
    if len(data)>maximum:raise ProcessingError('OUTPUT_TOO_LARGE')
    best,quality=data,minimum
    low,high=minimum+1,target-1
    while low<=high:
        mid=(low+high)//2; candidate=once(mid)
        if len(candidate)<=maximum:best,quality=candidate,mid;low=mid+1
        else:high=mid-1
    return best,quality
