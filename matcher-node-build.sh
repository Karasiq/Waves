#!/bin/bash

MATCHER_NODE_VERSION=`sbt -warn -no-colors -Dsbt.supershell=false 'print node/version' | tail -n 1 | sed -E 's/^(([0-9]\.){2}[0-9](\.[0-9])?).+$/\1/g'`
sbt "node/assembly"
docker build -f dockerfile.patched.docker -t wavesplatform/wavesnode:${MATCHER_NODE_VERSION} .

docker tag wavesplatform/wavesnode:${MATCHER_NODE_VERSION} "registry.wvservices.com/waves/dex/wavesnode:${MATCHER_NODE_VERSION}"
docker push registry.wvservices.com/waves/dex/wavesnode:${MATCHER_NODE_VERSION}
