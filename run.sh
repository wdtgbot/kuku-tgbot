#!/bin/bash

# jdk的目录
JAVA_HOME=~/jdk/jdk-21+35
java=$JAVA_HOME/bin/java

jar_name=tgbot.jar
jar_new_name=tgbot-new.jar
rm -f update.pid

"${java}" -Dspring.profiles.active=prod -jar $jar_name &

function kill_java {
        pid=`cat application.pid`
        kill $pid
        exit 1
}

trap "kill_java" SIGINT

while true; do
        if [ -e "update.pid" ] ;
        then
                rm -f update.pid
                pid=`cat application.pid`
                kill $pid
                sleep 3
                rm $jar_name
                mv tmp/$jar_new_name $jar_name
                "${java}" -Dspring.profiles.active=prod -jar $jar_name &
        else
                sleep 5
        fi
done