FROM openjdk:16-jdk-slim
WORKDIR /
COPY *.jar questioner.jar
CMD [ "java", "-ea", "--enable-preview", "-Xss256k", "-jar", "questioner.jar" ]
