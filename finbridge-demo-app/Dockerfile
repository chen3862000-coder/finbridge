FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress -pl finbridge-demo-app -am -DskipTests package
RUN mkdir -p /plugin-dist && \
    cp plugins/alphapay-v1/target/alphapay-v1-*-jar-with-dependencies.jar /plugin-dist/alphapay-1.0.0-jar-with-dependencies.jar && \
    cp plugins/alphapay-v2/target/alphapay-v2-*-jar-with-dependencies.jar /plugin-dist/alphapay-2.0.0-jar-with-dependencies.jar && \
    cp plugins/betapay/target/betapay-*-jar-with-dependencies.jar /plugin-dist/betapay-1.0.0-jar-with-dependencies.jar && \
    cp plugins/creditscorex/target/creditscorex-*-jar-with-dependencies.jar /plugin-dist/creditscorex-1.0.0-jar-with-dependencies.jar && \
    cp plugins/blacklisty/target/blacklisty-*-jar-with-dependencies.jar /plugin-dist/blacklisty-1.0.0-jar-with-dependencies.jar && \
    cp plugins/fraudsignalz/target/fraudsignalz-*-jar-with-dependencies.jar /plugin-dist/fraudsignalz-1.0.0-jar-with-dependencies.jar

FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/finbridge-demo-app/target/finbridge-demo-app-0.1.0-SNAPSHOT.jar /app/demo.jar
COPY --from=build /plugin-dist/ /app/plugins/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/demo.jar"]
