FROM openjdk:16-jdk-slim
WORKDIR /
COPY *.jar questioner.jar
CMD [ "java", "-ea", "--enable-preview", "-Xms512m", "-Xmx1G", "-Xss256k", "-XX:+UseZGC", "--illegal-access=permit", "-jar", "questioner.jar" ]
# vim: tw=0
