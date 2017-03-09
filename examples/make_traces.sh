#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

cd instagram-java-scraper/
patch -p0 < ../../unnamed.patch
./gradlew --stacktrace --info clean test
cd ../

cd HTTP/
./gradlew clean test
cd ${src_dir}
