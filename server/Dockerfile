# Use an official OpenJDK runtime as a parent image.
# Replace '17-slim' with the Java version your application requires (e.g., 11-slim, 21-slim).
FROM openjdk:21-slim

# Set the working directory in the container.
WORKDIR /app

# Copy the executable JAR file from the build context to the container.
# The 'docker build' command in your script is run from the 'server' directory,
# so 'build/libs/server-app.jar' is relative to that 'server' directory.
COPY build/libs/server-app.jar app.jar

# Make port 8080 available to the world outside this container.
# Adjust this if your backend application listens on a different port.
EXPOSE 8080

# Define the command to run your application.
ENTRYPOINT ["java", "-jar", "app.jar"]