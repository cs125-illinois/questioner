FROM openjdk:17-jdk-slim
WORKDIR /
COPY *.jar questioner.jar
CMD [\
  "java",\
  "-ea", "--enable-preview", "-Dfile.encoding=UTF-8",\
  "-Xms512m", "-Xmx1G", "-Xss256k", "-XX:+UseZGC", "-XX:ZCollectionInterval=8",\
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",\
  "--add-opens", "java.base/java.util=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",\
  "--add-exports", "java.management/sun.management=ALL-UNNAMED",\
  "-jar", "questioner.jar"\
]
# vim: tw=0
