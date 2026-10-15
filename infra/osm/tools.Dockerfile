# Throwaway tool image for the OSM pipeline (osmium-tool, osm2pgsql). Built locally by
# infra/scripts/osm-common.sh as mmr-osm-tools:<base tag>; never pushed or deployed.
#
# Why Debian packages instead of a third-party osmium/osm2pgsql image: the Debian archive is the most
# certain source (signed packages, native amd64 and arm64 builds for the Ampere VM, no unknown publisher).
#   debian:bookworm-slim -> osmium-tool 1.15.x, osm2pgsql 1.8.x  (expected versions, verify with --version)
#   debian:trixie-slim   -> osmium-tool 1.18.x, osm2pgsql 2.x    (set OSM_TOOLS_BASE=debian:trixie-slim)
# infra/osm/flex/mapmyroad.lua works with osm2pgsql 1.8 and 2.x.
# Licenses: osmium-tool GPL-3.0, osm2pgsql GPL-2.0-or-later (tools only, nothing is linked into our code).
# TODO(M4-4): pin the base image by digest.
ARG BASE=debian:bookworm-slim
FROM ${BASE}
RUN apt-get update \
 && apt-get install -y --no-install-recommends osmium-tool osm2pgsql ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /data
