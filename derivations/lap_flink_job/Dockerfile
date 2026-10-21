FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY derivations/iggy_source_support/pom.xml derivations/iggy_source_support/pom.xml
COPY derivations/iggy_source_support/src derivations/iggy_source_support/src

RUN mvn -f derivations/iggy_source_support/pom.xml -q -DskipTests install

COPY derivations/lap_flink_job/pom.xml derivations/lap_flink_job/pom.xml
COPY derivations/lap_flink_job/src derivations/lap_flink_job/src

RUN mvn -f derivations/lap_flink_job/pom.xml -q -DskipTests package

FROM flink:2.0.0-scala_2.12-java17

COPY --from=build /build/derivations/lap_flink_job/target/lap-flink-job.jar /opt/flink/usrlib/lap-flink-job.jar
