from PIL import ImageFilter

def apply(image, profile):
    strength=profile.get('denoise',0)
    if strength:
        import PIL.Image
        image=PIL.Image.blend(image,image.filter(ImageFilter.MedianFilter(3)),strength)
    strength=profile.get('sharpen',0)
    if strength:
        image=image.filter(ImageFilter.UnsharpMask(radius=profile.get('sharpenRadius',1),percent=round(strength*100),threshold=profile.get('sharpenThreshold',3)))
    return image
