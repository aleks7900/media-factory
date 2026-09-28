"""Bounded, cancellable execution. No shell or user-provided executable/filter strings."""
import os
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass


class VideoError(Exception):
    def __init__(self, code, detail=''):
        super().__init__(code)
        self.code, self.detail = code, detail[-4000:]


@dataclass
class ProcessResult:
    stdout: bytes
    stderr: str
    duration_ms: int
    exit_code: int


def run(args, *, timeout=180, cancel=None, limit=4*1024*1024):
    if args[0] not in ('ffmpeg', 'ffprobe'):
        raise VideoError('EXECUTABLE_NOT_ALLOWED')
    start = time.monotonic()
    # Files avoid pipe deadlocks; periodic size checks bound malicious/noisy subprocess output.
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        proc = subprocess.Popen(args, stdout=out, stderr=err, stdin=subprocess.DEVNULL, shell=False)
        try:
            while proc.poll() is None:
                if cancel is not None and cancel.is_set():
                    raise VideoError('CANCELLED')
                if time.monotonic()-start > timeout:
                    raise VideoError('PROCESS_TIMEOUT')
                if os.fstat(out.fileno()).st_size > limit or os.fstat(err.fileno()).st_size > limit:
                    raise VideoError('PROCESS_OUTPUT_LIMIT')
                time.sleep(.025)
        except BaseException:
            proc.kill()
            proc.wait(timeout=10)
            raise
        out.seek(0); err.seek(0)
        stdout, stderr = out.read(limit+1), err.read(limit+1)
        if len(stdout)>limit or len(stderr)>limit:
            raise VideoError('PROCESS_OUTPUT_LIMIT')
        text = stderr.decode('utf-8', errors='replace')
        result = ProcessResult(stdout, text[-4000:], int((time.monotonic()-start)*1000), proc.returncode)
        if result.exit_code:
            raise VideoError('FFMPEG_FAILED', result.stderr)
        return result


THREADS = str(max(1, min(8, int(os.getenv('VIDEO_THREADS', '2')))))
BASE = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-n',
        '-threads', THREADS, '-filter_threads', THREADS, '-filter_complex_threads', THREADS]
