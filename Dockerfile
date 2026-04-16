FROM openjdk:17-jdk-slim
WORKDIR /app
COPY . /app
# Make sure the .jar name here matches your actual file name exactly!
RUN javac -cp .:mysql-connector-j-9.6.0.jar CreatorGrowthOS.java
EXPOSE 8080
CMD ["java", "-cp", ".:mysql-connector-j-9.6.0.jar", "CreatorGrowthOS"]
