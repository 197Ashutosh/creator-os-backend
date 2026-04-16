# Use an official Java Runtime as a parent image
FROM openjdk:17-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Compile the Java code with the connector
RUN javac -cp .:mysql-connector-j-9.6.0.jar CreatorGrowthOS.java

# Make port 8080 available to the world
EXPOSE 8080

# Run the application
CMD ["java", "-cp", ".:mysql-connector-j-9.6.0.jar", "CreatorGrowthOS"]