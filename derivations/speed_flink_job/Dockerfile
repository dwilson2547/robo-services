FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY derivations/speed_flink_job/pom.xml derivations/speed_flink_job/pom.xml
COPY derivations/speed_flink_job/src derivations/speed_flink_job/src

RUN mvn -f derivations/speed_flink_job/pom.xml -q -DskipTests package

FROM flink:2.0.0-scala_2.12-java17

COPY --from=build /build/derivations/speed_flink_job/target/speed-flink-job.jar /opt/flink/usrlib/speed-flink-job.jar
