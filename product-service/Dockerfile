FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS builder
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw clean package -DskipTests
FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
# Demo-catalogue product photos. `project.image` resolves to /app/images, which
# WebMvcConfig serves at /images/**, so these land at /images/seed/<file>.jpg —
# the paths seed-db/catalogue.sql writes into product.image. Only seed/ is
# copied: the rest of images/ is runtime upload scratch.
COPY images/seed ./images/seed
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
