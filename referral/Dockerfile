FROM clojure:temurin-21-tools-deps-alpine
WORKDIR /app

# Install litestream for continuous SQLite backups
ADD https://github.com/benbjohnson/litestream/releases/download/v0.3.13/litestream-v0.3.13-linux-amd64.tar.gz /tmp/litestream.tar.gz
RUN tar -xzf /tmp/litestream.tar.gz -C /usr/local/bin && rm /tmp/litestream.tar.gz

COPY deps.edn .
RUN clj -P
COPY src/ src/
COPY resources/ resources/
COPY litestream.yml /etc/litestream.yml
COPY run.sh /app/run.sh
RUN apk add --no-cache sqlite
EXPOSE 3001
ENV PORT=3001
CMD ["/app/run.sh"]
