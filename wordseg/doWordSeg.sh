#!/bin/sh

mkdir segword
for f in `ls ./indata`
do
	echo $f
	java  -cp target/wordseg-1.0-SNAPSHOT-jar-with-dependencies.jar wordseg.Text2Word  -l lib -i ./indata/$f -s stopwords.txt -o ./segword/$f
done
