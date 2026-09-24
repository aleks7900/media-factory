import io
import unittest
from unittest.mock import patch
from PIL import Image
import crop
import resize
import encoding
import validation
import upscale
from common import ProcessingError,decode,bounds

class PipelineTest(unittest.TestCase):
    def profile(self,**values):
        return dict(format='JPEG',width=200,height=200,mode='FIT',quality=95,minimumQuality=88,**values)
    def test_four_megapixels_exact(self):
        for width,valid in [(2000,True),(1999,False)]:
            p=self.profile(minimumMegapixels=4)
            data,_=encoding.encode(Image.new('RGB',(width,2000)),p)
            if valid:self.assertEqual('VALID',validation.validate(data,p,(width,2000))['status'])
            else:
                with self.assertRaisesRegex(ProcessingError,'OUTPUT_VALIDATION_FAILED'):validation.validate(data,p,(width,2000))
    def test_alpha_requires_explicit_background(self):
        image=Image.new('RGBA',(24,24),(255,0,0,0))
        with self.assertRaisesRegex(ProcessingError,'BACKGROUND_REQUIRED'):encoding.encode(image,self.profile())
        data,_=encoding.encode(image,self.profile(background='WHITE'))
        self.assertGreater(Image.open(io.BytesIO(data)).getpixel((1,1))[0],250)
    def test_png_webp_alpha_and_icc(self):
        for fmt in ('PNG','WEBP'):
            p=self.profile();p['format']=fmt
            data,_=encoding.encode(Image.new('RGBA',(20,30),(100,40,30,100)),p)
            im=Image.open(io.BytesIO(data));self.assertEqual('RGBA',im.mode);self.assertTrue(im.info.get('icc_profile'))
            self.assertEqual('VALID',validation.validate(data,p,(20,30))['status'])
    def test_compression_impossible(self):
        with self.assertRaisesRegex(ProcessingError,'OUTPUT_TOO_LARGE'):encoding.encode(Image.new('RGB',(20,20)),self.profile(maxBytes=10))
    def test_compression_bounds(self):
        image=Image.effect_noise((300,300),90).convert('RGB');p=self.profile()
        high,_=encoding.encode(image,p);p['quality']=88;low,_=encoding.encode(image,p)
        p['quality']=95;p['maxBytes']=(len(high)+len(low))//2
        data,q=encoding.encode(image,p);self.assertLessEqual(len(data),p['maxBytes']);self.assertGreaterEqual(q,88)
    def test_modes(self):
        image=Image.new('RGB',(400,200));p=self.profile()
        self.assertEqual((200,100),resize.resize(image,p).size)
        p['mode']='PRESERVE';self.assertEqual(image.size,resize.resize(image,p).size)
        p['mode']='EXACT';self.assertEqual((200,200),resize.resize(image,p).size)
    def test_focal_fixtures(self):
        for x in (.1,.45,.8):
            region=dict(x=x,y=.3,width=.08,height=.2,type='FACE',confidence=.99)
            result=crop.calculate(1000,600,300,600,[region])
            self.assertFalse(result['excluded']);self.assertLessEqual(result['rectangle']['x'],x)
        result=crop.calculate(1000,600,300,600,[dict(x=.1,y=.2,width=.1,height=.2,type='FACE',confidence=.99),dict(x=.8,y=.2,width=.1,height=.2,type='FACE',confidence=.99)])
        self.assertIn('SMART_CROP_UNSAFE',result['warnings'])
    def test_top_face_safe_zone(self):
        result=crop.calculate(600,1000,600,600,[dict(x=.4,y=.02,width=.2,height=.1,type='FACE',confidence=.99)],top=.15)
        self.assertTrue(result['excluded'])
    def test_unsafe_crop_letterbox_and_review(self):
        image=Image.new('RGB',(1000,600));p=self.profile();p.update(mode='SMART_FILL',width=300,height=600)
        regions=[dict(x=x,y=.3,width=.1,height=.2,type='FACE',confidence=.99) for x in (.05,.85)]
        with self.assertRaisesRegex(ProcessingError,'SMART_CROP_UNSAFE'):crop.apply(image,p,regions)
        p['unsafeCrop']='LETTERBOX';_,details=crop.apply(image,p,regions);self.assertTrue(details['letterbox'])
    def test_manual_override_and_validation(self):
        p=self.profile();p['mode']='SMART_FILL'
        _,result=crop.apply(Image.new('RGB',(400,200)),p,[],dict(x=.25,y=0,width=.5,height=1))
        self.assertTrue(result['humanOverride']);self.assertIn('automaticCrop',result)
        with self.assertRaisesRegex(ProcessingError,'INVALID_CROP'):crop.apply(Image.new('RGB',(400,200)),p,[],dict(x=-1,y=0,width=.5,height=1))
    def test_corrupt_and_limits(self):
        with self.assertRaisesRegex(ProcessingError,'CORRUPT_SOURCE'):decode(b'corrupt')
        with self.assertRaisesRegex(ProcessingError,'IMPOSSIBLE_DIMENSIONS'):bounds(8192,8192)
    def test_cpu_fallback_gpu_only(self):
        with patch('upscale.capabilities',return_value={'device':'cpu'}):
            self.assertEqual('cpu',upscale.choose_device())
            with self.assertRaisesRegex(ProcessingError,'GPU_UNAVAILABLE'):upscale.choose_device(True)
    def test_gpu_oom_changes_tile_once(self):
        import torch
        with patch('upscale.choose_device',return_value='cpu'),patch('upscale.model_for',return_value=(None,dict(provider='test',modelName='test',version='1',sha256='x'))),patch('upscale.tiled',side_effect=[torch.cuda.OutOfMemoryError(),Image.new('RGB',(40,40))]) as method:
            _,metadata=upscale.upscale(Image.new('RGB',(20,20)),2)
            self.assertEqual(1,metadata['oomRetries']);self.assertLess(method.call_args_list[1].args[4],method.call_args_list[0].args[4])
    def test_gpu_oom_bounded(self):
        import torch
        with patch('upscale.choose_device',return_value='cpu'),patch('upscale.model_for',return_value=(None,{})),patch('upscale.tiled',side_effect=torch.cuda.OutOfMemoryError()) as method:
            with self.assertRaisesRegex(ProcessingError,'GPU_OOM'):upscale.upscale(Image.new('RGB',(20,20)),2)
            self.assertEqual(2,method.call_count)

if __name__=='__main__':unittest.main()
