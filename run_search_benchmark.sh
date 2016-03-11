#/bin/bash
echo "$1 threads!"

echo "Testing by name"
java -jar search_benchmark.jar -p 4 -t $1 -d 120 -r "172.31.56.113:13533" -q words.txt  -w by_name

echo "Testing by Geo and name"
java -jar search_benchmark.jar -p 4 -t $1 -d 120 -r "172.31.56.113:13533" -q words.txt  -w by_name_geo

echo "Testing by geo only"
java -jar search_benchmark.jar -p 4 -t $1 -d 120 -r "172.31.56.113:13533" -q words.txt  -w by_geo


