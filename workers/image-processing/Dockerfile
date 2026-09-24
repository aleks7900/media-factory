FROM python:3.12-slim
WORKDIR /app
ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir torch==2.8.0 torchvision==0.23.0 --index-url ${TORCH_INDEX}
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN useradd --uid 10001 --create-home worker && mkdir /models /tmp/processing && chown worker /models /tmp/processing
USER worker
ENV MODEL_DIR=/models PROCESSING_DEVICE=AUTO TORCH_THREADS=4
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
