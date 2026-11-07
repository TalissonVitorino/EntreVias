# O servidor roda em Docker a partir da distribuição já compilada pelo Gradle (:server:installDist).
# Não compila aqui dentro: o projeto é multiplataforma e precisaria do Android SDK na imagem.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/server/ /app/
ENV JAVA_OPTS="-Dentrevias.db=/dados/entrevias.db"
RUN mkdir -p /dados
EXPOSE 8080
CMD ["/app/bin/server"]
