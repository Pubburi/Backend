FROM opensearchproject/opensearch:3.9.0@sha256:3b55f6b6249cfdb92b52ee59adc8a266203ba9ef9f5d82544fad6f1af2f3609e

RUN /usr/share/opensearch/bin/opensearch-plugin install --batch analysis-nori \
    && grep -qx 'opensearch.version=3.9.0' /usr/share/opensearch/plugins/analysis-nori/plugin-descriptor.properties

LABEL org.opencontainers.image.title="Pubburi local search" \
      org.opencontainers.image.version="3.9.0-nori-v1"
