FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . /app
RUN javac -cp .:mysql-connector-j-9.6.0.jar CreatorGrowthOS.java
EXPOSE 8080
CMD ["java", "-cp", ".:mysql-connector-j-9.6.0.jar", "CreatorGrowthOS"]
