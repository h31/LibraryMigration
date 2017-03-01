#!/bin/bash

src_dir=$(pwd)
cd "$(dirname "$0")"

echo "instagram-java-scraper..."
cd instagram-java-scraper/
count=0
if ! ./gradlew clean test; then
    echo "Failed 1 time";
    if ! ./gradlew clean test; then
        echo "Failed 2 times";
        if ! ./gradlew clean test; then
            echo "Failed 3 times";
            return 1
        fi
    fi
fi
cd ../

echo "HTTP..."
cd HTTP/
./gradlew clean test
cd ${src_dir}
