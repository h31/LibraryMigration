#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

cd instagram-java-scraper/
if [ ! -f "./gradlew" ]
then
	git submodule update --init --recursive
fi
cp ../log.json.instagram log.json
git apply ../0001-Instagram-Mock.patch

cd ../

cd HTTP/
./gradlew clean test
cd ${src_dir}
