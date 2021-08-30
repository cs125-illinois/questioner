FROM openjdk:16-jdk-slim
WORKDIR /
COPY *.jar questioner.jar
CMD [ "java", "-ea", "--enable-preview", "-Xmx1G", "-Xss256k", "-XX:+ExitOnOutOfMemoryError", "--illegal-access=permit", "-jar", "questioner.jar" ]
