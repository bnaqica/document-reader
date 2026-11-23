# The base image Jib builds the service on: the Temurin 25 JRE plus ImageMagick with libheif, because Java has no
# reliable HEIC decoder. Pinned to Ubuntu 24.04 (noble), so the distribution and ImageMagick version can't change
# under the service. Build it once per environment (or push it to the registry):
#   docker build -t document-reader-base:1 src/main/docker/base
FROM eclipse-temurin:25-jre-noble

# libheif-plugin-libde265 is the HEVC decoder iPhone photos need; libheif1 alone (without recommends) brings only an
# AV1 decoder. The x265 encoder is installed only to prove, while building, that a HEIC really round-trips, and then
# removed: a coder list can claim HEIC support that fails at decode time.
RUN apt-get update \
    && apt-get install -y --no-install-recommends imagemagick libheif1 libheif-plugin-libde265 libheif-plugin-x265 \
    && convert logo: -resize 64x64 heic:/tmp/check.heic \
    && test "$(convert -ping heic:/tmp/check.heic[0] -format '%w' info:)" -gt 0 \
    && convert heic:/tmp/check.heic[0] -auto-orient png:/tmp/check.png \
    && rm -f /tmp/check.heic /tmp/check.png \
    && apt-get purge -y libheif-plugin-x265 \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
