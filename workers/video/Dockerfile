FROM python:3.12-slim-bookworm
ARG FFMPEG_VERSION=7:5.1.9-0+deb12u1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg=${FFMPEG_VERSION} && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN useradd --uid 10001 --create-home worker && mkdir /tmp/video && chown worker /tmp/video
USER worker
ENV VIDEO_CPU_JOBS=2 VIDEO_GPU_JOBS=1 VIDEO_THREADS=2
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
