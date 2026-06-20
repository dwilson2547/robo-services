FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY derivations/track_position_timescale_sink_job/pom.xml derivations/track_position_timescale_sink_job/pom.xml
COPY derivations/track_position_timescale_sink_job/src derivations/track_position_timescale_sink_job/src

RUN mvn -f derivations/track_position_timescale_sink_job/pom.xml -q -DskipTests package

FROM flink:2.0.0-scala_2.12-java17

COPY --from=build /build/derivations/track_position_timescale_sink_job/target/track-position-timescale-sink-job.jar /opt/flink/usrlib/track-position-timescale-sink-job.jar
