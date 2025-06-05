#!/bin/bash

# Check if two arguments are provided
if [ "$#" -ne 2 ]; then
    echo "Usage: ./run.sh <torrent_file> <output_file>"
    exit 1
fi

# Run the Maven project with the specified arguments and class name
mvn exec:java -Dexec.mainClass="com.minitorrent.bitTClient" -Dexec.args="$1 $2"
