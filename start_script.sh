#!/bin/bash

CONTAINER_NAME=tsd_proxy
CONF=/home/aossowski/repos/tsd_proxy/conf/tsd_proxy.conf

echo "removing stale versions of container"
docker stop $CONTAINER_NAME
docker rm $CONTAINER_NAME

echo "starting container..."
ID=`docker run \
  -d \
  -v $CONF:/etc/tsd_proxy.conf \
  --name $CONTAINER_NAME \
  --net=host \
  tsd_proxy`

echo "container running as : ${ID}"
