#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

cd instagram-java-scraper/
./gradlew --stacktrace --debug clean test
cd ../

cd HTTP/
./gradlew clean test
cd ${src_dir}
