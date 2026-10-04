#!/usr/bin/env bash
# 一键构建 VmessageSuppress-1.1.0.jar
#
# 为什么不用 Maven：本项目只有一个类、只依赖 Bukkit API，引入 Maven 反而要联网拉插件
# （本机 ~/.m2 是离线的），直接 javac + jar 就够了。
#
# 用法：
#   ./build.sh                                            # 自动找 paper-api（推荐）
#   SERVER_JAR=D:/path/to/paper-1.21.4.jar ./build.sh     # 手头只有服务端 jar 时用这个
set -e
cd "$(dirname "$0")"

JDK="${JDK:-D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64}"
M2="${M2:-C:/Users/PC/.m2/repository}"
TMP=".buildtmp"

# ---------------------------------------------------------------------------
# 找编译用的 API jar
#
# ⚠️ 坑 1：Paper 1.21+ 的服务端 jar 是 paperclip 包装，org.bukkit 那套 API **不在 jar 根目录**，
#         而是内嵌在 META-INF/libraries/io/papermc/paper/paper-api/<版本>/paper-api-*.jar。
#         直接拿服务端 jar 当 -cp 会报「程序包 org.bukkit.entity 不存在」。
# ⚠️ 坑 2：光有 paper-api 还不够 —— Player 实现了 adventure 的 Identified/BossBarViewer，
#         方法签名里还带着 jetbrains 的 @Nullable，缺了会报「无法访问 xxx 的类文件」。
#         所以 classpath 要把这几个传递依赖一起带上（都只是编译用，不打进产物）。
# ---------------------------------------------------------------------------
API_JAR=""

# ① Maven 本地仓库里大概率已有（VTpa 的 bridge 模块用的就是它）
for f in "$M2"/io/papermc/paper/paper-api/*/paper-api-*.jar; do
    [ -f "$f" ] && API_JAR="$f" && break
done

# ② 没有就从一个服务端 jar 里把内嵌的 paper-api 抠出来
if [ -z "$API_JAR" ] && [ -n "$SERVER_JAR" ] && [ -f "$SERVER_JAR" ]; then
    rm -rf "$TMP"; mkdir -p "$TMP"
    INNER="$(unzip -l "$SERVER_JAR" \
        | grep -oE "META-INF/libraries/io/papermc/paper/paper-api/[^ ]+\.jar" | head -1)"
    if [ -n "$INNER" ]; then
        unzip -p "$SERVER_JAR" "$INNER" > "$TMP/paper-api.jar"
        API_JAR="$TMP/paper-api.jar"
    fi
fi

if [ -z "$API_JAR" ]; then
    echo "找不到 paper-api。"
    echo "指定一个服务端 jar 让脚本自己抠："
    echo "  SERVER_JAR=D:/game/Server/killer/paper-1.21.4-138.jar ./build.sh"
    exit 1
fi

# classpath：paper-api + adventure 全家桶 + jetbrains 注解。
# 之所以整包 Adventure 都带上而不是一个个点名：类型批注会层层引用
# （Component → ComponentDecoder → …），漏一个就报「无法将类型批注附加到 xxx」。
CP="$API_JAR"
for f in "$M2"/net/kyori/*/*/*.jar; do
    [ -f "$f" ] && CP="$CP;$f"
done
for f in "$M2"/org/jetbrains/annotations/*/annotations-*.jar; do
    [ -f "$f" ] && CP="$CP;$f" && break
done

echo "API jar: $API_JAR"

rm -rf out target
mkdir -p out target

echo "编译（--release 17，兼容 1.17+ 服务端）..."
"$JDK/bin/javac" -encoding UTF-8 --release 17 \
    -cp "$CP" \
    -d out \
    src/main/java/cn/shijiu/vmessagesuppress/VmessageSuppress.java

cp src/main/resources/plugin.yml src/main/resources/config.yml out/

"$JDK/bin/jar" cf target/VmessageSuppress-1.1.0.jar -C out .

rm -rf "$TMP"
echo
echo "产物：target/VmessageSuppress-1.1.0.jar"
"$JDK/bin/jar" tf target/VmessageSuppress-1.1.0.jar
