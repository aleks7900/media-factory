"""No model download or GPU: exercise decoding, batching and normalization with fixed projections."""
import base64
import io
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import torch
from PIL import Image
from fastapi import HTTPException
import app


class Inputs(dict):
    def to(self, device):
        return self


class Processor:
    def __call__(self, images=None, text=None, **kwargs):
        return Inputs(count=len(images if images is not None else text))


class Model:
    config = SimpleNamespace(projection_dim=512)

    def get_image_features(self, count):
        output = torch.zeros((count, 512))
        output[:, 0] = 3
        output[:, 1] = 4
        return output

    get_text_features = get_image_features


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.model = patch.object(app, "model", Model())
        self.processor = patch.object(app, "processor", Processor())
        self.model.start()
        self.processor.start()
        self.addCleanup(self.model.stop)
        self.addCleanup(self.processor.stop)

    def test_batched_images_are_finite_and_normalized(self):
        data = io.BytesIO()
        Image.new("RGB", (64, 64), "red").save(data, format="PNG")
        encoded = base64.b64encode(data.getvalue()).decode()
        result = app.images(app.Batch(images=[app.ImageInput(id=str(i), data=encoded) for i in range(3)]))
        self.assertEqual(len(result["vectors"]), 3)
        self.assertEqual(result["dimension"], 512)
        self.assertAlmostEqual(result["vectors"][0][0], .6, places=6)
        self.assertAlmostEqual(sum(v*v for v in result["vectors"][0]), 1, places=6)
        self.assertEqual(result["externalApiCost"], 0)
        self.assertIsNone(result["computeMonetaryCost"])

    def test_invalid_image_fails_without_embedding(self):
        with self.assertRaises(HTTPException) as error:
            app.images(app.Batch(images=[app.ImageInput(id="invalid", data="not-base64")]))
        self.assertEqual(error.exception.status_code, 422)

    def test_text_uses_same_projection_and_explicit_token_limit(self):
        result = app.texts(app.TextBatch(texts=["a forest", "a mountain"]))
        self.assertEqual(len(result["vectors"]), 2)
        self.assertEqual(result["textTokenLimit"], 77)

    def test_empty_text_is_rejected(self):
        with self.assertRaises(HTTPException):
            app.texts(app.TextBatch(texts=[" "]))


if __name__ == "__main__":
    unittest.main()
