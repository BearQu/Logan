FROM maven:3.9-eclipse-temurin-8 AS build-env
WORKDIR /app
COPY mvn/settings.xml /root/.m2/settings.xml
COPY pom.xml /app/
RUN mvn -B -q dependency:go-offline || true
COPY . /app/
# PoC env 兜底：compose 可用 LOGAN_DB_* 覆盖；防止占位符模板打进 WAR
ARG LOGAN_DB_HOST=db
ARG LOGAN_DB_PORT=3306
ARG LOGAN_DB_NAME=logan
ARG LOGAN_DB_USER=logan
ARG LOGAN_DB_PASSWORD=logan_poc_2026
RUN sed -i \
  -e "s#//host:port/database#//${LOGAN_DB_HOST}:${LOGAN_DB_PORT}/${LOGAN_DB_NAME}#" \
  -e "s#^jdbc.username=.*#jdbc.username=${LOGAN_DB_USER}#" \
  -e "s#^jdbc.password=.*#jdbc.password=${LOGAN_DB_PASSWORD}#" \
  src/main/resources/db.properties \
 && cat src/main/resources/db.properties
RUN mvn -B verify -DskipTests
FROM tomcat:9-jre8
COPY --from=build-env /app/target/*.war /usr/local/tomcat/webapps/logan-web.war
ENV JAVA_OPTS="-Xms128m -Xmx384m"
EXPOSE 8080
