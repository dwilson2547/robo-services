# Build from the robo-services repo root:
#   docker build -f services/can_discovery/Dockerfile -t dwilson2547/robo-services-can-discovery:<tag> .
FROM rust:1.92-slim-bookworm AS build
WORKDIR /src
COPY services/can_discovery/Cargo.toml services/can_discovery/Cargo.lock ./
COPY services/can_discovery/src ./src
RUN cargo build --release --locked

FROM debian:bookworm-slim
COPY --from=build /src/target/release/can-discovery /usr/local/bin/can-discovery
USER 65534
ENTRYPOINT ["/usr/local/bin/can-discovery"]
