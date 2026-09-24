"""Opt-in live benchmark: python benchmark.py input.png --scale 2 --output /tmp/result.png."""
import argparse
import io
import json
import time
import os
import torch
from pathlib import Path
from common import decode
from encoding import color,SRGB
from upscale import upscale

parser=argparse.ArgumentParser()
parser.add_argument('input');parser.add_argument('--scale',type=int,choices=[2,4],default=2)
parser.add_argument('--output',required=True);parser.add_argument('--require-gpu',action='store_true')
args=parser.parse_args()
torch.set_num_threads(max(1,min(8,int(os.getenv('TORCH_THREADS','4')))))
source=decode(Path(args.input).read_bytes());source,_=color(source)
start=time.monotonic();result,metadata=upscale(source,args.scale,args.require_gpu)
buffer=io.BytesIO();result.save(buffer,format='PNG',icc_profile=SRGB)
with Path(args.output).open('xb') as output:output.write(buffer.getvalue())
print(json.dumps(dict(inputDimensions=source.size,outputDimensions=result.size,durationMs=round((time.monotonic()-start)*1000),outputBytes=len(buffer.getvalue()),**metadata),indent=2))
