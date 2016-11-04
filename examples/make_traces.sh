#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

cd instagram-java-scraper/
./gradlew test
cd ../../

cd HTTP/
./gradlew test
cd ${src_dir}