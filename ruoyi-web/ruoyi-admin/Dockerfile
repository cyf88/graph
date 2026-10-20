# 徐州数据治理应用平台 —— 单 jar 部署镜像（前端 + 后端已打包进 ruoyi-admin.jar）
FROM eclipse-temurin:21-jre

LABEL maintainer="hukou"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_OPTS=""

WORKDIR /app

# 需要先执行前端构建并把 dist 复制到 static，再执行 mvn package 生成此 jar
COPY target/ruoyi-admin.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -Djava.security.egd=file:/dev/./urandom $JAVA_OPTS -jar app.jar"]
