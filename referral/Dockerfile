FROM clojure:temurin-21-tools-deps-alpine
WORKDIR /app
COPY deps.edn .
RUN clj -P
COPY src/ src/
COPY resources/ resources/
RUN apk add --no-cache sqlite
EXPOSE 3001
ENV PORT=3001
CMD ["clj", "-M:run"]
