FROM python:3.12-slim
WORKDIR /app
ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir torch==2.8.0 torchvision==0.23.0 --index-url ${TORCH_INDEX}
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
COPY tests tests
ENV HF_HOME=/models TOKENIZERS_PARALLELISM=false
RUN useradd --uid 10001 --create-home worker && mkdir /models && chown worker /models
USER worker
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=600s CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
