FROM golang:1.24.2-bookworm AS build
ENV CGO_ENABLED=0 GOMAXPROCS=2
WORKDIR /source
RUN git init && git remote add origin https://github.com/minio/minio.git && git fetch --depth 1 origin 0d7408fc9969caf07de6a8c3a84f9fbb10a6739e && git checkout FETCH_HEAD
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build go build -p 2 -trimpath -ldflags="-s -w" -o /out/minio .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /source/LICENSE /usr/share/doc/minio/LICENSE
LABEL org.opencontainers.image.source="https://github.com/minio/minio" org.opencontainers.image.revision="0d7408fc9969caf07de6a8c3a84f9fbb10a6739e" org.opencontainers.image.licenses="AGPL-3.0-only"
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
