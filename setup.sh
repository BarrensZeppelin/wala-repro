#!/usr/bin/bash
set -euo pipefail

mkdir vert.x
cd vert.x

REPO=https://github.com/eclipse/vert.x
COMMITHASH=26e47151ec8dcf10db394ebcd58ea5836e677fb6

git init
git config advice.detachedHead false
git remote add origin "$REPO"
git fetch --depth 1 origin "$COMMITHASH"
git checkout FETCH_HEAD

sed -i 's|<stack.version>4.0.0-SNAPSHOT</stack.version>|<stack.version>4.0.0</stack.version>|g' pom.xml

if [ -z "${JAVA_HOME+x}" ]; then
	export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
fi

mvn compile
mvn install -DskipTests
mvn dependency:copy-dependencies -DoutputDirectory=libs

cd ..
