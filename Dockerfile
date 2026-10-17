FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY PayrollApp.java PayrollServer.java .
RUN mkdir -p out && javac -encoding UTF-8 -Xlint:all -d out PayrollApp.java PayrollServer.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY index.html styles.css ./
RUN mkdir -p src
COPY src/app.js ./src/app.js
ENV HOST=0.0.0.0
EXPOSE 8080
CMD ["java", "-cp", "out", "PayrollServer"]
