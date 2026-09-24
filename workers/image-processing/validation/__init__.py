import io
from PIL import Image
from common import ProcessingError, bounds

def validate(data, profile, expected):
    try:
        image=Image.open(io.BytesIO(data));image.verify()
        image=Image.open(io.BytesIO(data));image.load()
        bounds(*image.size)
        checks={'readable':True,'format':image.format==profile['format'],'dimensions':image.size==expected,
                'fileSize':0<len(data)<=profile.get('maxBytes',20*1024*1024),
                'minimumMegapixels':image.width*image.height>=profile.get('minimumMegapixels',0)*1000000,
                'colorSpace':bool(image.info.get('icc_profile')),
                'alpha':image.mode in ('RGB','RGBA') and (profile['format']!='JPEG' or image.mode=='RGB')}
        if not all(checks.values()):raise ProcessingError('OUTPUT_VALIDATION_FAILED',str(checks))
        return dict(status='VALID',checks=checks,width=image.width,height=image.height,format=image.format,megapixels=image.width*image.height/1000000)
    except ProcessingError:raise
    except Exception as exc:raise ProcessingError('CORRUPT_OUTPUT') from exc
