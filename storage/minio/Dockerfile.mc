FROM golang:1.24.2-bookworm AS build
ENV CGO_ENABLED=0 GOMAXPROCS=2
WORKDIR /source
RUN git init && git remote add origin https://github.com/minio/mc.git && git fetch --depth 1 origin b00526b153a31b36767991a4f5ce2cced435ee8e && git checkout FETCH_HEAD
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build go build -p 2 -trimpath -ldflags="-s -w" -o /out/mc .
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/mc /usr/local/bin/mc
COPY --from=build /source/LICENSE /usr/share/doc/mc/LICENSE
LABEL org.opencontainers.image.source="https://github.com/minio/mc" org.opencontainers.image.revision="b00526b153a31b36767991a4f5ce2cced435ee8e" org.opencontainers.image.licenses="AGPL-3.0-only"
ENTRYPOINT ["mc"]
