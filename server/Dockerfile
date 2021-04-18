FROM openjdk:15-jdk-slim
WORKDIR /
COPY *.jar jsp.jar
CMD [ "java", "-ea", "--enable-preview", "-Xss256k", "-jar", "jsp.jar" ]
