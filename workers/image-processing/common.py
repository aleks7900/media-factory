import io
import os
from PIL import Image, ImageOps

MAX_PIXELS = int(os.getenv("MAX_INPUT_PIXELS", "64000000"))
Image.MAX_IMAGE_PIXELS = MAX_PIXELS

class ProcessingError(Exception):
    def __init__(self, code, message=None):
        self.code = code
        super().__init__(code + (': ' + message if message else ''))

def decode(data):
    if not data or len(data) > 64 * 1024 * 1024:
        raise ProcessingError("INPUT_TOO_LARGE")
    try:
        image = Image.open(io.BytesIO(data))
        if image.width * image.height > MAX_PIXELS or image.format not in ("PNG", "JPEG", "WEBP"):
            raise ProcessingError("UNSUPPORTED_SOURCE")
        image.verify()
        image = ImageOps.exif_transpose(Image.open(io.BytesIO(data)))
        image.load()
        if image.mode not in ("RGB", "RGBA", "L", "LA", "P"):
            raise ProcessingError("UNSUPPORTED_COLOR_MODE")
        return image
    except ProcessingError:
        raise
    except Exception as exc:
        raise ProcessingError("CORRUPT_SOURCE") from exc

def bounds(width, height):
    max_width=min(8192,int(os.getenv('MAX_OUTPUT_WIDTH','8192')))
    max_height=min(8192,int(os.getenv('MAX_OUTPUT_HEIGHT','8192')))
    max_pixels=min(64000000,int(os.getenv('MAX_OUTPUT_PIXELS','64000000')))
    if min(width, height) < 1 or width>max_width or height>max_height or width * height > max_pixels:
        raise ProcessingError("IMPOSSIBLE_DIMENSIONS")
