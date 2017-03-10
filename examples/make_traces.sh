#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

cd instagram-java-scraper/
patch -p0 < ../../unnamed.patch
./gradlew --stacktrace --debug clean test
patch -p0 -R < ../../unnamed.patch
./gradlew clean test
cd ../

cd HTTP/
./gradlew clean test
cd ${src_dir}
