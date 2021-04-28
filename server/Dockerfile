FROM openjdk:16-jdk-slim
WORKDIR /
COPY *.jar questioner.jar
CMD [ "java", "-ea", "--enable-preview", "-Xss256k", "--illegal-access=permit", "-jar", "questioner.jar" ]
