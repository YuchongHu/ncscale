#!/bin/bash
if [ "$1" == "192.168.60.242" ]
then
    echo "/rack1"
elif [ "$1" == "192.168.60.243" ]
then
    echo "/rack1"
elif [ "$1" == "192.168.60.244" ]
then
    echo "/rack2"
elif [ "$1" == "192.168.60.245" ]
then
    echo "/rack3"
elif [ "$1" == "192.168.60.246" ]
then
    echo "/rack4"
else
	echo "/default-rack"
fi


