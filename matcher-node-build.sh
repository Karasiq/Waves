#!/bin/bash

MATCHER_NODE_VERSION=`sbt -warn 'print node/version' | tail -n 1 | sed -E 's/^(([0-9]\.){2}[0-9](\.[0-9])?).+$/\1/g'`
sbt "node/assembly"
docker build -f dockerfile.patched.docker -t wavesplatform/wavesnode:${MATCHER_NODE_VERSION} .
